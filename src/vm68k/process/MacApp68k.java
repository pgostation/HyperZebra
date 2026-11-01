import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * One running classic 68K Macintosh application ("process").
 *
 * Each process owns its own address space, CPU, Toolbox state and host windows
 * and runs on its own thread, so several applications can run at once, much
 * like Wine runs Windows programs side by side: the CPU is emulated, the
 * operating system calls are served natively by {@link Toolbox68k} and
 * friends.
 */
final class MacApp68k implements Runnable {
	static final int TYPE_APPL=0x4150504c;
	private static final List<MacApp68k> running=Collections.synchronizedList(new ArrayList<MacApp68k>());
	private static int nextSerial=1;

	final File file;
	final String name;
	final ResourceFile68k resources;
	final List<File> documents=new ArrayList<File>();
	final int serialNumber;
	/** The program that started this one with _Launch (its serial number), 0 when the host did - as the Finder does on a Mac. */
	int launcherSerial;
	M68kMemory memory;
	M68kCpu cpu;
	/** The PowerPC side of a PowerPC application (null for a 68K one): its code runs from the jump table's host stub. */
	PpcRuntime ppc;
	/** Runs the PowerPC code fragment in the data fork instead of 68K CODE segments (a PowerPC-only program, or a fat one unless -Dhzppc.fat=false). */
	boolean powerPC;
	Mac68kApi api;
	Toolbox68k toolbox;
	MacAppHost host;
	Thread thread;
	int a5,jumpTableOffset,jumpTableSize,belowA5,aboveA5,stackTop,stackBottom,appParms;
	/** A PowerPC program's stack: the bottom {@code ppcStackSize} bytes of the stack area, from stackBottom (ApplLimit) up. */
	int ppcStackSize;
	int codeSegmentCount;
	volatile boolean finished;
	/** System.nanoTime() once start-up (the extensions' INITs, their notification responses) is over and the program's own
	 *  code is about to run; 0 before that. Until then A5 and CurrentA5 may belong to an INIT, not to the program. */
	volatile long codeStartedAt;
	/** A service VM (QuickTimeService's stub): no display, not listed among the running programs, failures only logged. */
	boolean service;
	/**
	 * Run without the user (the expanders' installers, {@link #launchUnattended}): its dialogs are answered with their default
	 * button (Return) and its Save dialogs with the offered name in the program's own folder, so nothing waits for a click.
	 */
	volatile boolean unattended;
	/** Running in the 16 MB, 24-bit world (see {@link #addressing24()}). */
	boolean addressing24;
	/** The most screen memory a 24-bit program's 16 MB may give to the main device (deeper screens are made shallower). */
	private static final long ADDRESSING24_SCREEN_BYTES=4L*1024*1024;
	/** Code a host loop runs instead of an application's CODE segments: a control panel or a desk accessory. */
	interface HostedCode {
		/** One pass of the host's event loop; true when it has closed and the process should exit. */
		boolean step() throws M68kException;
		/** What went wrong when it could not open (an explanation for the user), or null. */
		String failure();
	}
	/** A control panel ('cdev' file) shown by {@link ControlPanelHost68k} instead of an application's CODE segments. */
	ControlPanelHost68k controlPanel;
	/** The control panel, or the desk accessory run by {@link DeskAccessoryHost68k}, or null for an application. */
	HostedCode hosted;
	String exitMessage;
	long startedAt=System.currentTimeMillis();
	/**
	 * The launcher's per-program settings (LauncherStore.AppSettings): overrides of the VM preferences, null = follow the
	 * preference. The screen depth, size and magnification are read once at launch; wide dialogs from the next dialog;
	 * the speed at every scheduler poll, so it changes while the program runs.
	 */
	Integer depthOverride;volatile String screenSizeOverride;volatile Integer screenScaleOverride;volatile Boolean screenTopLeftOverride;volatile Integer smoothZoomOverride;volatile Boolean wideDialogsOverride;volatile Boolean controlKeysOverride;volatile Boolean realSpeedOverride;volatile Double mipsOverride;
	/** The program's own JIT setting for its PowerPC code (null: the shared one, PpcJit.wanted); a change applies at the next poll. */
	volatile Boolean ppcJitOverride;
	/** The program's own keys for Command, Option and Control (null: the default keys); changes while the program runs. */
	volatile ModifierKeys modifierKeysOverride;
	/** The launcher's System version for this program (Toolbox68k.systemVersionFor); null = the standard, Mac OS 9.2.2. */
	Integer systemVersionOverride;
	/** The launcher's System language for this program ("English"/"Japanese", Toolbox68k.applicationEncoding); null = HyperZebra's language.
	 *  With translated resources, the language of their script when the launcher sets none. */
	final String languageOverride;
	/** The program's translated resources laid over its own (ResourceTranslation), or null. */
	final ResourceTranslation.Overlay translatedResources;
	/** The program's font substitutions (the launcher's, as Font Patchin' made them): used by its thread and the threads it starts. */
	final Fonts68k.Substitution fontSubstitution=new Fonts68k.Substitution();
	/** Whether the program sees a Japanese System (KanjiTalk: Shift-JIS, Osaka), where its text in a Roman font can come out garbled. */
	boolean japaneseSystem(){Toolbox68k tb=toolbox;return (tb!=null?tb.encoding():Toolbox68k.applicationEncoding(resources,languageOverride))==StackTextEncoding.JAPANESE;}
	/** The font substitutions changed in the launcher: the program's windows are drawn again with them at once. */
	void fontSubstitutionsChanged(List<String> pairs){
		fontSubstitution.set(pairs,japaneseSystem());
		Toolbox68k tb=toolbox;if(tb==null||finished)return;
		tb.laterAtEvent(()->{try{tb.windows.redrawAll();}catch(M68kCpuException e){if(Mac68kApi.TRACE)System.err.println("[68K] font substitution redraw: "+e);}});
	}
	/** How many of the fonts the program drew text in "Fix Garbled Text…" tries at most (each costs a redraw). */
	static final int GARBLED_TEXT_TRIES=6;
	/**
	 * "Fix Garbled Text…" (the program's Apple menu): the user clicked garbled text at (x,y) of window {@code window}'s image.
	 * Users know where the garbled text is, not the name of its font, nor which Japanese font to take: each font the program
	 * drew text in (Fonts68k.Substitution.candidates: garbled-looking text first) is given its automatic replacement and the
	 * windows drawn again until the pixels at the place change; that substitution stays and goes into the launcher's settings.
	 * The others are taken back. The place is read after a redraw of its own first, so a part that changes by itself (iTunes's
	 * elapsed time) is not taken for the font's doing.
	 */
	void fixGarbledText(final int window,final int x,final int y){
		final Toolbox68k tb=toolbox;final MacAppHost h=host;if(tb==null||h==null||finished)return;
		Thread search=new Thread(()->{
			final List<String> before=fontSubstitution.pairs();final boolean japanese=fontSubstitution.japaneseSystem();
			int[] box={x-10,y-6,21,13};
			h.fontSearching(true);
			try{
				int[] baseline=redrawnPixels(tb,h,window,box);
				List<Fonts68k.Substitution.Drawn> candidates=fontSubstitution.candidates();
				if(Mac68kApi.TRACE)System.err.println("[68K "+name+"] fix garbled text at "+x+","+y+" of $"+Integer.toHexString(window)+": "+candidates.size()+" fonts to try");
				for(Fonts68k.Substitution.Drawn d:candidates.subList(0,Math.min(GARBLED_TEXT_TRIES,candidates.size()))){
					if(finished)return;
					List<String> trial=new ArrayList<String>();
					for(String p:before)if(!p.substring(0,p.indexOf('\t')).equalsIgnoreCase(d.name))trial.add(p);
					trial.add(d.name+"\t"+Fonts68k.AUTOMATIC);
					fontSubstitution.set(trial,japanese);
					int[] after=redrawnPixels(tb,h,window,box);
					if(Mac68kApi.TRACE)System.err.println("[68K "+name+"] fix garbled text: "+d.name+" ("+d.garbled+" garbled of "+d.count+") "+(baseline!=null&&after!=null&&!java.util.Arrays.equals(baseline,after)?"changes the place":"changes nothing there"));
					if(baseline!=null&&after!=null&&!java.util.Arrays.equals(baseline,after)){
						keepFontSubstitutions(trial);
						h.garbledTextFixed(d.name,Fonts68k.nameOf(Fonts68k.automaticReplacement(d.id)),()->{fontSubstitutionsChanged(before);keepFontSubstitutions(before);});
						return;
					}
				}
				fontSubstitutionsChanged(before);
				h.garbledTextNotFound();
			}catch(InterruptedException e){fontSubstitutionsChanged(before);}
			finally{h.fontSearching(false);}
		},"Fix Garbled Text: "+name);
		search.setDaemon(true);search.start();
	}
	/** The program's windows drawn again (update events), then the pixels of {@code box} {x,y,w,h} of the window's image once the update is done and they stay still. */
	private static int[] redrawnPixels(Toolbox68k tb,MacAppHost h,int window,int[] box) throws InterruptedException {
		final java.util.concurrent.CountDownLatch asked=new java.util.concurrent.CountDownLatch(1);
		tb.laterAtEvent(()->{try{tb.windows.redrawAll();}catch(M68kCpuException e){}finally{asked.countDown();}});
		asked.await(3,java.util.concurrent.TimeUnit.SECONDS);
		long until=System.currentTimeMillis()+3000;int[] last=null;int still=0;
		while(System.currentTimeMillis()<until){
			Thread.sleep(60);
			boolean pending;try{pending=tb.windows.updatePending(window);}catch(M68kCpuException e){pending=false;}
			int[] now=h.windowPixels(window,box[0],box[1],box[2],box[3]);
			if(!pending&&now!=null&&java.util.Arrays.equals(now,last)){if(++still>=3)return now;}else still=0;
			last=now;
		}
		return last;
	}
	/** Keeps the program's font substitutions in the launcher's settings ("Fix Garbled Text…" and its undo). */
	private void keepFontSubstitutions(final List<String> pairs){
		if(!LauncherStore.enabled)return;
		SwingUtilities.invokeLater(()->{LauncherStore store=LauncherStore.get();LauncherStore.AppSettings s=store.settingsFor(file);
			s=s==null?new LauncherStore.AppSettings():s.copy();s.fontSubstitutions.clear();s.fontSubstitutions.addAll(pairs);store.putSettings(file,s);});
	}

	/** The translation of what the program shows (VMTranslation), or null when it is off; replaced when the settings change. */
	volatile VMTranslation.Translator translator;
	private long translationRedrawAt;private boolean translationRedrawQueued;
	/** The translator last in use, also after translation was turned off (its text ports are erased to show the originals again). */
	private volatile VMTranslation.Translator lastTranslator;
	VMTranslation.Translator translatorForRedraw(){VMTranslation.Translator t=translator;return t!=null?t:lastTranslator;}
	/** The settings changed: the next redraw erases every window that showed translatable text, not only those with new translations. */
	private volatile boolean fullTranslationRedraw;
	boolean takeFullTranslationRedraw(){boolean b=fullTranslationRedraw;fullTranslationRedraw=false;return b;}
	/** The translation settings changed: every running program translates (or shows its own texts again) at once. */
	static void translationChangedAll(){for(MacApp68k app:runningApplications())app.translationChanged();}
	/**
	 * Starts (or stops) translating with the settings in effect for this program. The source language, when automatic,
	 * is found from the program's resources on another thread; nothing is translated until then.
	 */
	void translationChanged(){
		if(service){translator=null;return;}
		VMTranslation.Config config=VMTranslation.configFor(file);
		if(translator!=null)lastTranslator=translator;
		fullTranslationRedraw=true;
		if(!config.on||!VMTranslation.live()){boolean was=translator!=null;if(was)translator.closed=true;translator=null;if(was)redrawForTranslation();return;}
		final VMTranslation.Translator t=new VMTranslation.Translator(config,null,this::redrawForTranslation);
		t.shareEditable(translator);
		if(translator!=null)translator.closed=true;
		translator=t;
		Thread detect=new Thread(()->{
			StackTextEncoding encoding=Toolbox68k.applicationEncoding(resources,languageOverride);
			List<String> strings=VMTranslation.programStrings(resources,encoding,4000);
			List<String> known=new ArrayList<String>(strings); // the program's own texts (its language is found from these, the originals)
			if(translatedResources!=null){t.setTranslatedAlready(translatedResources.texts);known.addAll(translatedResources.texts);} // and its translated resources' (never sent again)
			t.setKnown(known);
			String from=VMTranslation.AUTO.equals(config.from)?VMTranslation.programLanguage(strings,encoding):config.from;
			if(Mac68kApi.TRACE)System.err.println("[68K "+name+"] translation: "+from+" -> "+config.to+" ("+config.engine+"), "+strings.size()+" resource strings");
			t.setFrom(from);
			redrawForTranslation();
			t.prefetchProgram(VMTranslation.programTexts(resources,encoding)); // in the background, after what is shown
		},"Translation language");
		detect.setDaemon(true);detect.start();
	}
	/**
	 * Translations arrived (or translation was turned off): the menus are rebuilt, window titles set again and every window
	 * gets an update event, so the program draws its texts again and they come out translated. At most a few times a second.
	 */
	void redrawForTranslation(){
		Toolbox68k tb=toolbox;if(tb==null||finished)return;
		synchronized(this){
			if(translationRedrawQueued)return;
			translationRedrawQueued=true;
		}
		long wait=Math.max(0,translationRedrawAt+300-System.currentTimeMillis());
		VMTranslation.later(wait,()->{
			synchronized(this){translationRedrawQueued=false;translationRedrawAt=System.currentTimeMillis();}
			tb.later(()->{try{tb.translationInPlace();}catch(M68kCpuException e){if(Mac68kApi.TRACE)System.err.println("[68K] translation in place: "+e);}});
			tb.laterAtEvent(()->{
				try{tb.translationRedraw();}catch(M68kCpuException e){if(Mac68kApi.TRACE)System.err.println("[68K] translation redraw: "+e);}
			});
		});
	}

	/** Told on the EDT when a program starts or finishes (the launcher's dock). */
	interface Listener { void applicationLaunched(MacApp68k app); void applicationFinished(MacApp68k app); }
	private static final List<Listener> listeners=Collections.synchronizedList(new ArrayList<Listener>());
	static void addListener(Listener l){listeners.add(l);}
	static void removeListener(Listener l){listeners.remove(l);}
	private static void tell(final boolean launched,final MacApp68k app){
		final List<Listener> all;synchronized(listeners){all=new ArrayList<Listener>(listeners);}
		if(all.isEmpty()||java.awt.GraphicsEnvironment.isHeadless())return;
		SwingUtilities.invokeLater(new Runnable(){@Override public void run(){for(Listener l:all){if(launched)l.applicationLaunched(app);else l.applicationFinished(app);}}});
	}

	private MacApp68k(File file,ResourceFile68k resources,List<File> documents){
		this.file=file;this.resources=resources;
		LauncherStore.AppSettings settings=LauncherStore.enabled?LauncherStore.get().settingsFor(file):null;
		translatedResources=ResourceTranslation.forLaunch(file,resources,settings); // in memory only: the file keeps its own
		languageOverride=settings!=null&&settings.language!=null?settings.language:translatedResources!=null?translatedResources.systemLanguage():null; // before the name: it is decoded in the System's script
		// The name the program sees (CurApName, GetAppParms) is the Mac name: a Shift-JIS name unpacked on the host as Mac OS Roman
		// mojibake such as "Ç«Å[Ç±Çæ..." must not become "???[?±..." because a projector-style application may look up its own file by it.
		String n=FileManager68k.programName(Toolbox68k.applicationEncoding(resources,languageOverride),file.getName());
		ResourceFile68k.MacBinary mb=null;
		try{mb=ResourceFile68k.MacBinary.read(file);}catch(IOException ignored){}
		if(mb!=null&&mb.nameBytes.length>0)n=Toolbox68k.applicationEncoding(null,languageOverride).decode(mb.nameBytes);
		name=n;
		if(documents!=null)this.documents.addAll(documents);
		serialNumber=newSerialNumber();
	}
	/** The next process serial number (the low long of a ProcessSerialNumber): a program's, or one a program runs on a task of its own. */
	static synchronized int newSerialNumber(){int serial;do serial=nextSerial++;while(Toolbox68k.standInSerial(serial));return serial;}

	/** The zoom setting changed: every running program shows its screen magnified by the new choice at once. */
	static void zoomChangedAll(){for(MacApp68k app:runningApplications()){MacAppHost h=app.host;if(h!=null)h.rezoom(app);}}
	/** This program's own zoom setting changed ({@code own}: null follows the preference). */
	void zoomChanged(Integer own){screenScaleOverride=own!=null?own:compactScreen?Integer.valueOf(2):null;MacAppHost h=host;if(h!=null)h.rezoom(this);}
	/** The game screen place preference (centre or top left) changed: every running program's game screen moves there at once. */
	static void screenPlaceChangedAll(){for(MacApp68k app:runningApplications()){MacAppHost h=app.host;if(h!=null)h.reposition(app);}}
	/** This program's own game screen place changed ({@code own}: true = top left, null follows the preference). */
	void screenPlaceChanged(Boolean own){screenTopLeftOverride=own;MacAppHost h=host;if(h!=null)h.reposition(this);}
	/** The smooth zoom preference changed: every running program that follows it draws into planes of the new kind at once. */
	static void smoothChangedAll(){for(MacApp68k app:runningApplications()){MacAppHost h=app.host;if(h!=null)h.resmooth();}}
	/** This program's own smooth zoom setting changed ({@code own}: null follows the preference). */
	void smoothChanged(Integer own){smoothZoomOverride=own;MacAppHost h=host;if(h!=null)h.resmooth();}
	static List<MacApp68k> runningApplications(){synchronized(running){return new ArrayList<MacApp68k>(running);}}
	/** The program whose window the host activated last (MacAppHost), or null. */
	static volatile MacApp68k activeApplication;
	/** The Finder stand-in was made the front process (SetFrontProcess, A-Dock's Finder icon) and no program window was activated since. */
	static volatile boolean finderFront;
	/** The front process: the program the user activated last, else the last one launched that is not background-only (a faceless
	 *  'appe' such as A-Dock is never in front), else the last one launched. */
	static MacApp68k frontApplication(){
		List<MacApp68k> all=runningApplications();MacApp68k active=activeApplication;
		if(active!=null&&!active.finished&&all.contains(active))return active;
		for(int i=all.size()-1;i>=0;i--)if(!all.get(i).backgroundOnly())return all.get(i);
		return all.isEmpty()?null:all.get(all.size()-1);
	}
	/** SIZE flag onlyBackground ($0400): a faceless background application. */
	boolean backgroundOnly(){return (sizeFlags()&0x0400)!=0;}
	static MacApp68k bySerial(int serial){for(MacApp68k app:runningApplications())if(app.serialNumber==serial)return app;return null;}
	/** The program whose emulation thread is the current one (its per-program settings apply), or null on any other thread. */
	static MacApp68k forCurrentThread(){Thread t=Thread.currentThread();synchronized(running){for(MacApp68k app:running)if(app.thread==t||app.toolbox!=null&&app.toolbox.threads.isCarrier(t))return app;}return null;} // a Thread Manager coroutine thread runs the program too
	/** The running, unfinished program launched from this file (the launcher brings it to the front instead of launching it again), or null. */
	static MacApp68k runningFrom(File file){
		String id=LauncherStore.identity(file);if(id==null)return null;
		for(MacApp68k app:runningApplications())if(!app.finished&&id.equals(LauncherStore.identity(app.file)))return app;
		return null;
	}

	/** The running program that owns this host window (its own controller mapping applies while it is active), or null. */
	static MacApp68k applicationOf(java.awt.Window w){
		if(w==null)return null;
		for(MacApp68k app:runningApplications()){MacAppHost h=app.host;if(h!=null&&!app.finished&&h.ownsWindow(w))return app;}
		return null;
	}

	/** The OpenDoc signature: OpenDoc documents carry it as their creator, the OpenDoc™ program (Extensions:OpenDoc Libraries) is that 'APPL'. */
	static final int OPENDOC_CREATOR=0x6f64746d;
	/**
	 * An OpenDoc document (creator 'odtm', any type but 'APPL'). Most carry a DocumentStub (a 'cfrg' application fragment that
	 * runs OpenDocLib's ShellMain), so {@link #isApplication} says yes - the Process Manager runs it - but the Finder never
	 * launches one: it hands it to the OpenDoc™ program, which runs the document as its own process with Open Documents.
	 * Launched directly the stub gets Open Application, and OpenDoc then opens its own file read-only (the file of a running
	 * program): WAV's pages took no typing at all.
	 */
	static boolean isOpenDocDocument(File file){
		return file!=null&&file.isFile()&&FinderInfo68k.creator(file)==OPENDOC_CREATOR&&FinderInfo68k.type(file)!=ResourceFile68k.fourcc("APPL");
	}
	/** The OpenDoc™ program on the startup disk (its System Folder, where the OpenDoc installer puts it), or null when OpenDoc is not installed. */
	static File openDocProgram(){
		File cached=openDocProgram;
		if(cached!=null&&cached.isFile())return cached;
		File found=findProgram(new File(FileSystem68k.bootVolumeDirectory(),"System Folder"),OPENDOC_CREATOR,3);
		openDocProgram=found;
		return found;
	}
	private static volatile File openDocProgram;
	private static File findProgram(File folder,int signature,int depth){
		File[] children=folder.listFiles();
		if(children==null)return null;
		for(File f:children)if(f.isFile()&&FinderInfo68k.creator(f)==signature&&FinderInfo68k.type(f)==ResourceFile68k.fourcc("APPL")&&isApplication(f))return f;
		if(depth>0)for(File f:children)if(f.isDirectory()){File r=findProgram(f,signature,depth-1);if(r!=null)return r;}
		return null;
	}

	/** True when the file has a resource fork with CODE 0 (or is a MacBinary/AppleDouble packaged APPL). */
	static boolean isApplication(File file){
		if(file==null||!file.isFile())return false;
		// A stack saved as a stand-alone application (HyperCard's "Save a Copy… as Application": CODE resources of the player
		// around a data fork that is still the stack) opens as a stack here rather than running its player
		if(OStack.looksLikeHyperCardStack(file.getPath()))return false;
		try{
			ResourceFile68k rf=ResourceFile68k.open(file);
			if(rf.get(ResourceFile68k.fourcc("CODE"),0)!=null&&!rf.ofType(ResourceFile68k.fourcc("CODE")).isEmpty())return true;
			if(isPowerPcOnly(rf))return PpcRuntime.applicationFragment(rf)!=null; // a PowerPC program; a shared library (no application fragment) is no program
		}catch(IOException e){
			// a damaged application (FVoyage File Converter's resource map runs past the end of its truncated fork): launch() says
			// so, rather than the file being opened as a stack and failing with an XML parse error
			// (recorded info only: guessing asks isApplication again - µZak Collection starter died of a StackOverflowError)
			byte[] info=FinderInfo68k.stored(file);return info!=null&&u4(info,0)==ResourceFile68k.fourcc("APPL");
		}
		return false;
	}
	/**
	 * A stack saved as a stand-alone application (HyperCard 2.x "Save a Copy… as Application", Myst Preview): the data fork is
	 * the stack and the resource fork carries the player's CODE. {@link #isApplication} says no (it opens as a stack by
	 * default), but {@link #launch} runs it in the VM like any 68K program when the user asks for that.
	 */
	static boolean isStandaloneStack(File file){
		if(file==null||!file.isFile()||!OStack.looksLikeHyperCardStack(file.getPath()))return false;
		try{ResourceFile68k rf=ResourceFile68k.open(file);return rf.get(ResourceFile68k.fourcc("CODE"),0)!=null;}
		catch(IOException e){return false;}
	}

	/**
	 * A Mac OS 9 package (a folder "X.app" holding Contents/MacOSClassic or Contents/MacOS, DragThing 4.5): the program
	 * inside, which the Finder of Mac OS 9 opens for it - the file the Info plist's CFBundleExecutable names, else one named
	 * like the package, else the only program there. Null when the folder is no package of a program the VM runs (a Mach-O
	 * application has no code fragment in a resource fork).
	 */
	static File packageProgram(File dir){
		if(dir==null||!dir.isDirectory())return null;
		File contents=new File(dir,"Contents");
		if(!contents.isDirectory())return null;
		List<String> names=new ArrayList<String>();
		File plist=PpcCoreFoundation.infoPlist(contents);
		if(plist!=null)try{
			java.util.regex.Matcher m=java.util.regex.Pattern.compile("<key>\\s*CFBundleExecutable\\s*</key>\\s*<string>([^<]+)</string>")
				.matcher(new String(java.nio.file.Files.readAllBytes(plist.toPath()),java.nio.charset.StandardCharsets.UTF_8));
			if(m.find())names.add(m.group(1).trim());
		}catch(IOException ignored){}
		String name=dir.getName();
		names.add(name.toLowerCase(java.util.Locale.ROOT).endsWith(".app")?name.substring(0,name.length()-4):name);
		for(String sub:new String[]{"MacOSClassic","MacOS"}){
			File folder=new File(contents,sub);
			if(!folder.isDirectory())continue;
			for(String n:names){File f=new File(folder,n);if(isApplication(f))return f;}
			File[] children=folder.listFiles();File only=null;int count=0;
			if(children!=null)for(File f:children)if(isApplication(f)){only=f;count++;}
			if(count==1)return only;
		}
		return null;
	}
	/**
	 * A Mac OS X package ("X.app" folder whose Contents/MacOS executable is a Mach-O file, e.g. the Kid Pix Deluxe 3X
	 * installer): a program the VM cannot run (it emulates classic Mac OS only). False for anything else.
	 */
	static boolean isMachOPackage(File dir){
		if(dir==null||!dir.isDirectory()||packageProgram(dir)!=null)return false;
		File[] files=new File(new File(dir,"Contents"),"MacOS").listFiles();
		if(files==null)return false;
		for(File f:files){
			if(!f.isFile())continue;
			try(java.io.InputStream in=new java.io.FileInputStream(f)){
				byte[] h=new byte[4];
				if(in.read(h)!=4)continue;
				int m=u4(h,0);
				if(m==0xFEEDFACE||m==0xCEFAEDFE||m==0xFEEDFACF||m==0xCFFAEDFE||m==0xCAFEBABE)return true;
			}catch(IOException ignored){}
		}
		return false;
	}
	/** The program a package stands for (see packageProgram), else the file itself. */
	static File programOf(File file){
		File program=packageProgram(file);
		return program!=null?program:file;
	}

	/**
	 * A Finder alias file ('alis' 0 and no code; shareware launchers such as サマーデイズ sit next to a DATA folder holding the
	 * application): the application it names, found near the alias - the record's file name (bytes in the program's
	 * encoding, offset 50) inside a folder named like its parent-folder entry (tag 0 of the variable part) when there is
	 * one, else anywhere up to three levels below the alias's folder. Null when it is no alias or the target is absent.
	 */
	static File resolveAliasFile(File file){
		if(file==null||!file.isFile())return null;
		try{
			ResourceFile68k rf=ResourceFile68k.open(file);
			ResourceFile68k.Resource r=rf.get(ResourceFile68k.fourcc("alis"),0);
			if(r==null)return null;
			byte[] d=r.data();if(d.length<150)return null;
			int n=d[50]&255;if(n==0||n>63)return null;
			byte[] name=java.util.Arrays.copyOfRange(d,51,51+n),parent=null;
			for(int p=150;p+4<=d.length;){int tag=(short)(((d[p]&255)<<8)|(d[p+1]&255)),len=((d[p+2]&255)<<8)|(d[p+3]&255);if(tag==-1||p+4+len>d.length)break;
				if(tag==0&&len>0)parent=java.util.Arrays.copyOfRange(d,p+4,p+4+len);p+=4+len+(len&1);}
			File folder=file.getAbsoluteFile().getParentFile();if(folder==null)return null;
			File target=findAliasTarget(folder,name,parent,3,file);
			return target;
		}catch(IOException e){return null;}
	}
	private static File findAliasTarget(File folder,byte[] name,byte[] parent,int depth,File self){
		File[] children=folder.listFiles();if(children==null)return null;
		boolean here=parent==null||sameMacName(folder.getName(),parent);
		if(here)for(File child:children)if(child.isFile()&&!child.equals(self)&&sameMacName(child.getName(),name)&&isApplication(child))return child;
		if(depth>0)for(File child:children)if(child.isDirectory()){File found=findAliasTarget(child,name,parent,depth-1,self);if(found!=null)return found;}
		if(!here&&parent!=null&&depth==3)return findAliasTarget(folder,name,null,3,self); // no folder of that name: take the name alone
		return null;
	}
	private static boolean sameMacName(String hostName,byte[] macName){
		if(hostName.toLowerCase().endsWith(".app")&&sameMacName(hostName.substring(0,hostName.length()-4),macName))return true; // copies renamed on the host (怪盗ごっこ.app)
		for(StackTextEncoding e:new StackTextEncoding[]{StackTextEncoding.JAPANESE,StackTextEncoding.ROMAN}){
			try{if(java.util.Arrays.equals(e.encode(hostName),macName))return true;}catch(Exception ignored){}}
		try{return java.util.Arrays.equals(hostName.getBytes("UTF-8"),macName);}catch(Exception e){return false;}
	}

	/**
	 * A PowerPC application carries its code in a code fragment ('cfrg' resource) and, at most, a small 68K stub
	 * (CODE 0/1 of a few KB that alerts "requires a PowerPC"); a fat binary has the cfrg and a full set of CODE segments.
	 * The stubs among the samples are 2-6 KB (Internet Explorer 5.1, Virtual Wings Demo, All JPEG Fat); the smallest real
	 * fat program, Julia 'O Matic, has 15 KB of 68K code and was turned away by a 16 KB limit.
	 * Without CODE 0 (no jump table) the Segment Loader cannot start the 68K side at all, whatever other CODE there is:
	 * FileMaker Pro 4.0 carries only an 11 KB Installer VISE stub, CODE 10000.
	 */
	static boolean isPowerPcOnly(ResourceFile68k rf){
		if(rf.ofType(ResourceFile68k.fourcc("cfrg")).isEmpty())return false;
		List<ResourceFile68k.Resource> code=rf.ofType(ResourceFile68k.fourcc("CODE"));
		if(code.isEmpty()||rf.get(ResourceFile68k.fourcc("CODE"),0)==null)return true;
		long bytes=0;for(ResourceFile68k.Resource r:code)if(r.id!=0)bytes+=r.data().length;
		return bytes<8*1024;
	}

	/**
	 * Whether a program runs its PowerPC code: a PowerPC-only program always does; a fat one (68K CODE segments too) runs its
	 * PowerPC code too, as on a Power Macintosh, unless its Processor setting or -Dhzppc.fat=false says 68K.
	 */
	static boolean runsAsPowerPc(ResourceFile68k rf){return runsAsPowerPc(rf,null);}
	/** As above, with the launcher's per-program choice for {@code file} (a fat program's Processor setting). */
	static boolean runsAsPowerPc(ResourceFile68k rf,File file){
		if(PpcRuntime.applicationFragment(rf)==null)return false;
		if(isPowerPcOnly(rf))return true;
		if(file!=null&&LauncherStore.enabled){LauncherStore.AppSettings s=LauncherStore.get().settingsFor(file);if(s!=null&&s.powerPC!=null)return s.powerPC;}
		return fatRunsPowerPc();
	}
	/** The default for a fat program: its PowerPC code, unless -Dhzppc.fat=false. */
	static boolean fatRunsPowerPc(){return !"false".equalsIgnoreCase(System.getProperty("hzppc.fat"));}
	/** Whether {@code file} runs its PowerPC code with the settings {@code s} (the launcher's settings dialog, before they are stored). */
	static boolean runsAsPowerPc(File file,LauncherStore.AppSettings s){
		try{ResourceFile68k rf=ResourceFile68k.open(file);
			if(PpcRuntime.applicationFragment(rf)==null)return false;
			if(isPowerPcOnly(rf))return true;
			return s!=null&&s.powerPC!=null?s.powerPC:fatRunsPowerPc();
		}catch(IOException e){return false;}
	}
	/** The program's own modifier keys in its launcher settings, or null for the default keys. */
	static ModifierKeys modifierKeys(LauncherStore.AppSettings s){
		if(s==null||s.modifierKeys==null)return null;ModifierKeys m=ModifierKeys.parse(s.modifierKeys);return m.isEmpty()?null:m;
	}
	/** Whether a file is a fat program: a PowerPC application fragment and a full set of 68K CODE segments. */
	static boolean isFatApplication(File file){
		try{ResourceFile68k rf=ResourceFile68k.open(file);return PpcRuntime.applicationFragment(rf)!=null&&!isPowerPcOnly(rf)&&rf.get(ResourceFile68k.fourcc("CODE"),0)!=null;}
		catch(IOException e){return false;}
	}

	/** Starts a display-less service VM running {@code file} (see {@link QuickTimeService}); it is not listed as a running program. */
	static MacApp68k launchService(File file) throws IOException {
		ResourceFile68k rf=ResourceFile68k.open(file);
		if(rf.get(ResourceFile68k.fourcc("CODE"),0)==null)throw new IOException("not a 68K application (no CODE 0 resource): "+file.getName());
		MacApp68k app=new MacApp68k(file,rf,null);app.service=true;
		app.thread=new Thread(app,"68K service: "+app.name);app.thread.setDaemon(true);app.thread.start();
		return app;
	}
	/** True when the file is a control panel: a 'cdev' code resource at the control-panel ID and no CODE 0 (a 'cdev' with CODE segments is a program). */
	static boolean isControlPanel(File file){
		if(file==null||!file.isFile())return false;
		try{ResourceFile68k rf=ResourceFile68k.open(file);
			return rf.get(ResourceFile68k.fourcc("cdev"),ControlPanelHost68k.CPANEL_ID)!=null&&rf.get(ResourceFile68k.fourcc("CODE"),0)==null;
		}catch(IOException e){return false;}
	}
	/** Opens a control panel in a process of its own, as the Finder does when a 'cdev' file is opened. */
	static MacApp68k launchControlPanel(File file) throws IOException {
		ResourceFile68k rf=ResourceFile68k.open(file);
		if(rf.get(ResourceFile68k.fourcc("cdev"),ControlPanelHost68k.CPANEL_ID)==null)throw new IOException("not a control panel (no 'cdev' resource): "+file.getName());
		return launch(file,rf,null,true,null);
	}
	/**
	 * True when the file is a desk accessory: a 'DRVR' and no CODE 0 or 'cdev' (the System 7 'dfil' files, also old
	 * Font/DA Mover suitcases with a single accessory). A HyperCard stack is none, even with a 'DRVR' in its resource
	 * fork (FPlay XCMD's ".SndDriver" in Celtic Museum / NLU, Info-Mac II).
	 */
	static boolean isDeskAccessory(File file){
		if(file==null||!file.isFile())return false;
		if(OStack.looksLikeHyperCardStack(file.getPath()))return false;
		try{ResourceFile68k rf=ResourceFile68k.open(file);
			return !rf.ofType(ResourceFile68k.fourcc("DRVR")).isEmpty()&&rf.get(ResourceFile68k.fourcc("CODE"),0)==null&&rf.get(ResourceFile68k.fourcc("cdev"),ControlPanelHost68k.CPANEL_ID)==null;
		}catch(IOException e){return false;}
	}
	/** Opens a desk accessory in a process of its own (the DA Handler of System 7); {@code name} null = the file's first 'DRVR'. */
	static MacApp68k launchDeskAccessory(File file,String name) throws IOException {
		ResourceFile68k rf=ResourceFile68k.open(file);
		if(rf.ofType(ResourceFile68k.fourcc("DRVR")).isEmpty())throw new IOException("not a desk accessory (no 'DRVR' resource): "+file.getName());
		return launch(file,rf,null,false,name==null?"":name);
	}
	/** Opens a desk accessory, or brings it to the front when it is open already (from the Finder, the launcher). */
	static MacApp68k openDeskAccessory(File file,String name) throws IOException {
		MacApp68k running=runningFrom(file);
		if(running!=null){running.bringToFront();return running;}
		return launchDeskAccessory(file,name);
	}
	/** Loads and starts the application on a new thread. */
	static MacApp68k launch(File file,List<File> documents) throws IOException {
		ResourceFile68k rf;
		try{rf=ResourceFile68k.open(file);}
		catch(IOException e){throw new IOException(dialogText("The application's resource fork is damaged (an incomplete copy or extraction?).")+"\n"+e.getMessage());}
		if(isPowerPcOnly(rf)||runsAsPowerPc(rf,file)){
			if(PpcRuntime.applicationFragment(rf)==null)throw new IOException(dialogText("This file holds PowerPC code but no application fragment (a shared library or plug-in?); it cannot be run by itself."));
			return launch(file,rf,documents,false,null);
		}
		if(rf.get(ResourceFile68k.fourcc("CODE"),0)==null)throw new IOException("not a 68K application (no CODE 0 resource): "+file.getName());
		return launch(file,rf,documents,false,null);
	}
	/** Launches a program that runs without the user ({@link #unattended}): an installer or a self-extracting archive. */
	static MacApp68k launchUnattended(File file) throws IOException {
		UNATTENDED_LAUNCH.set(Boolean.TRUE);
		try{return launch(file,null);}finally{UNATTENDED_LAUNCH.remove();}
	}
	private static final ThreadLocal<Boolean> UNATTENDED_LAUNCH=new ThreadLocal<Boolean>();
	/** {@code deskAccessory}: the name of the accessory to open ("" = the first), or null. */
	private static MacApp68k launch(File file,ResourceFile68k rf,List<File> documents,boolean controlPanel,String deskAccessory) throws IOException {
		MacApp68k app=new MacApp68k(file,rf,documents);
		app.unattended=UNATTENDED_LAUNCH.get()!=null||Boolean.getBoolean("hz68k.unattended"); // debug aid: -Dhz68k.unattended=true runs every program so
		app.unpaced=UNATTENDED_LAUNCH.get()!=null||!controlPanel&&deskAccessory==null&&ExtractorApps.shippedExpander(file); // the shipped expanders and their installers
		MacApp68k parent=forCurrentThread();if(parent!=null)app.launcherSerial=parent.serialNumber; // _Launch runs on the launching program's thread
		Integer loadSegHook=LOAD_SEG_HOOK.get();if(loadSegHook!=null&&parent!=null)app.launchBridge=new LaunchHookBridge68k(parent,app,loadSegHook);
		app.powerPC=!controlPanel&&deskAccessory==null&&runsAsPowerPc(rf,file);
		if(controlPanel)app.hosted=app.controlPanel=new ControlPanelHost68k(app,file);
		else if(deskAccessory!=null)app.hosted=new DeskAccessoryHost68k(app,file,deskAccessory.isEmpty()?null:deskAccessory);
		// Debug aid: -Dhz68k.documents=path[:path...] opens these documents in the launched program (Open Documents event).
		String extra=System.getProperty("hz68k.documents");
		if(extra!=null)for(String path:extra.split(File.pathSeparator))if(!path.isEmpty()&&new File(path).exists())app.documents.add(new File(path));
		// Debug aid: -Dhz68k.mount=image[:image...] mounts these disk images before the program starts (multi-disk installers).
		String images=System.getProperty("hz68k.mount");
		if(images!=null)for(String path:images.split(File.pathSeparator))if(!path.isEmpty()&&new File(path).isFile()){
			try{DiskImages.Mounted m=DiskImages.mount(new File(path),null);System.err.println("[68K] mounted "+m.name+" ("+m.description+")");}
			catch(IOException|RuntimeException e){System.err.println("[68K] can't mount "+path+": "+e);}
		}
		Sandbox.programLaunched(app.name,file,app.documents); // its folder to read, its own file and its documents to change; anything else it asks for (Sandbox)
		Sandbox.runsUnattended(app.name,app.unattended);
		app.fontSubstitution.set(null,app.japaneseSystem()); // its System's script also without settings: garbled-looking text is counted on a Japanese System
		if(LauncherStore.enabled){
			LauncherStore.AppSettings s=LauncherStore.get().settingsFor(file);
			if(s!=null){app.fontSubstitution.set(s.fontSubstitutions,app.fontSubstitution.japaneseSystem());app.depthOverride=s.depth;app.screenSizeOverride=s.screenSize;app.screenScaleOverride=s.screenScale;app.screenTopLeftOverride=s.screenTopLeft;app.smoothZoomOverride=s.smoothZoom;app.wideDialogsOverride=s.wideDialogs;app.controlKeysOverride=s.controlKeys;app.realSpeedOverride=s.realSpeed;app.mipsOverride=s.mips;app.ppcJitOverride=s.ppcJit;app.modifierKeysOverride=modifierKeys(s);app.systemVersionOverride=s.systemVersion;}
			for(File d:app.documents)LauncherStore.get().recordDocument(file,d);
		}
		// one Mac, one System: a program another one launches reports the System version its launcher does (OpenDoc™ runs each
		// document as a process of its own - set to 7.5.5 for OpenDoc 1.x, the WAV document still said "cannot run under MacOS 8")
		if(app.systemVersionOverride==null&&parent!=null)app.systemVersionOverride=parent.systemVersionOverride;
		if(app.hosted==null&&app.compactMacProgram()){ // the screen it was written for, unless the user chose another for it
			app.compactScreen=true;
			if(app.screenSizeOverride==null)app.screenSizeOverride="512x342";
			if(app.screenScaleOverride==null)app.screenScaleOverride=2;
			System.err.println("[68K "+app.name+"] writes a compact Macintosh screen itself (ScrnBase/ScreenRow/64-byte rows, no SIZE resource): 512x342 at 1 bit");
		}
		if(parent!=null&&sharesAddressSpace(parent,app)){ // on the launcher's thread, in its _Launch: its memory may grow now
			int depth=app.plannedDepth();app.sharedMemory=parent.memory.launchPartition(app.plannedMemorySize(depth),Toolbox68k.APPL_HEAP_START);
			if(app.sharedMemory!=null)System.err.printf("[68K %s] runs in the address space of \"%s\", as on a Mac: partition $%08X..$%08X%n",app.name,parent.name,app.sharedMemory.partitionBase(),app.sharedMemory.partitionBase()+app.sharedMemory.partitionSize());
		}
		app.translationChanged();
		app.thread=new Thread(app,"68K: "+app.name);
		app.thread.setDaemon(true);
		running.add(app);installFlushHook();
		app.thread.start();
		tell(true,app);
		final String snapshotDir=System.getProperty("hz68k.snapshot");
		if(snapshotDir!=null){Thread t=new Thread(new Runnable(){@Override public void run(){while(!app.finished){try{Thread.sleep(Integer.getInteger("hz68k.snapinterval",2000));}catch(InterruptedException e){return;}if(app.host!=null){if(Mac68kApi.TRACE)System.err.println("[68K] t="+(System.currentTimeMillis()/100%10000)+" snapshot");app.host.snapshot(new File(snapshotDir,"app"+app.serialNumber));app.runDebugCommands(new File(snapshotDir,"commands.txt"));/* the shared file goes to whichever program reads it first, app<serial>/commands.txt to that program only */app.runDebugCommands(new File(new File(snapshotDir,"app"+app.serialNumber),"commands.txt"));}}}},"68K snapshots");t.setDaemon(true);t.start();}
		return app;
	}

	@Override public void run(){
		Fonts68k.useSubstitution(service?null:fontSubstitution); // not the launching program's: this thread inherited those
		try{
			setUp();
			loadSegPatchAtStart=cpu.patchedTrap(0xa9f0); // the extensions' patch, if any: not the program's own
			if(launchBridge!=null)launchBridge.childStarting();
			codeStartedAt=Math.max(1,System.nanoTime());
			cpu.run(a5+jumpTableOffset+2);
			toolbox.files.ejectAddedDrives(cpu); // returned from main: as ExitToShell does
			exitMessage=hosted!=null?hosted.failure():null;
		}catch(Toolbox68k.ExitToShell e){
			exitMessage=hosted!=null?hosted.failure():null;
		}catch(M68kAbortException e){
			exitMessage=null;
		}catch(M68kMacApiException e){
			exitMessage=e.getMessage();crashDump(e.pc);
			if(ppc!=null)System.err.println(ppc.describe(ppc.cpu.lastPc()));
		}catch(M68kCpuException e){
			exitMessage=e.getMessage();crashDump(e.pc);cpuCrash=true;
			System.err.println("[68K "+name+"] "+exitMessage);
			if(Mac68kApi.TRACE)e.printStackTrace(); // which host routine raised it (e.g. a heap allocation)
			if(ppc!=null)System.err.println(ppc.describe(ppc.cpu.lastPc()));
			System.err.println(cpu.dumpState(e.pc));
			System.err.println(toolbox.describeSegments());
			retryIndexedAfterCrash();
			if(M68kMemory.ADDR_PROBE!=null){System.err.println(memory.describeAddrRing());
				try{StringBuilder sb=new StringBuilder("jump table entries $2100..$2140(A5):");for(int o=0x2100;o<0x2140;o+=8){sb.append(String.format("%n  $%04X(A5): %04X %04X %04X %04X",o,memory.readWord(a5+o),memory.readWord(a5+o+2),memory.readWord(a5+o+4),memory.readWord(a5+o+6)));}System.err.println(sb);}catch(Exception x){}}
		}catch(M68kException e){
			exitMessage=e.getMessage();
		}catch(IOException e){
			exitMessage=e.getMessage();
		}catch(RuntimeException e){
			e.printStackTrace();
			exitMessage=e.toString();
		}finally{
			tearDown();
			if(launchBridge!=null)launchBridge.childEnded();
			Runnable exit=onExit;if(exit!=null)try{exit.run();}catch(RuntimeException ignored){}
		}
	}
	/** Run once the program has ended (Toolbox68k.launch: the THINK C environment's exit report to the launcher). */
	volatile Runnable onExit;
	/** The launcher's _LoadSeg patch run for this program (null: none), and the _LoadSeg patch present when this program's code started (an extension's). */
	LaunchHookBridge68k launchBridge;volatile int loadSegPatchAtStart;
	/** Set by Toolbox68k's _Launch around {@link #launch(File,List)}: the launcher's own _LoadSeg patch. */
	static final ThreadLocal<Integer> LOAD_SEG_HOOK=new ThreadLocal<Integer>();

	/** The least guest memory of a PowerPC program: the RAM of the Power Macs it ran on, temporary memory included. */
	private static final int PPC_MIN_MEMORY=256*1024*1024;
	/**
	 * Whether {@code app}, which {@code parent} launches, runs in the launcher's address space (M68kMemory.launchPartition), as
	 * every program did on a classic Mac: a partition of its own above the launcher's memory, its own low memory, and each sees the
	 * other's partition at the same addresses. Done for a launcher that is a Macintosh Common Lisp program: the Apple Dylan IDE
	 * reads its Application Nub's runtime globals and stacks at the addresses the nub reports over Apple events ("Illegal runtime
	 * address" otherwise). Other programs keep a memory of their own: a separate space keeps one program's stray writes out of
	 * the other, and their layouts stay as they were. -Dhz68k.sharedspace=all does it for every 68K program another launches, =off never.
	 */
	private static boolean sharesAddressSpace(MacApp68k parent,MacApp68k app){
		String mode=System.getProperty("hz68k.sharedspace","");
		if(mode.equals("off")||!(mode.equals("all")||parent.mclHeap))return false;
		M68kMemory m=parent.memory;
		if(m==null||parent.powerPC||parent.hosted!=null||parent.service||m.addressing24||parent.finished)return false; // (a 68K program's PowerPC runtime for fat INITs' code loads and stores through the memory: fine)
		return !app.powerPC&&app.hosted==null&&!app.compactScreen&&!app.service&&!app.addressing24();
	}
	/** The memory {@link #launch} made for this program in its launcher's address space (see {@link #sharesAddressSpace}), or null. */
	private M68kMemory sharedMemory;

	/** Bytes of guest memory for this program at a main device of {@code depth} bits (not the 24-bit world's 16 MB). */
	private int plannedMemorySize(int depth){
		int memorySize=M68kMemory.SIZE*2; // 32 MB: room for the screen-sized main device and large programs
		int preferred=preferredHeapSize();
		// a PowerPC program's partition can be large (Heavy Metal F.A.K.K.2 asks for 100 MB and quits when NewPtr fails): twice the SIZE request, up to 512 MB
		int memoryCap=powerPC?512*1024*1024:64*1024*1024;
		while(memorySize<memoryCap&&preferred>memorySize/2)memorySize*=2;
		// Temporary memory (TempNewHandle) lies outside the partition on a real Mac, and MacMAME relies on it (its FAQ: do not raise the
		// partition, it takes what it needs: Neo Geo games 192 MB and more): a PowerPC program's guest memory is a Power Mac's RAM, not just its partition
		if(powerPC)while(memorySize<PPC_MIN_MEMORY)memorySize*=2;
		// The main device PixMap and a full-screen window both live in guest memory: at full colour on a
		// large display that is tens of megabytes, so keep the base 32 MB free of them.
		java.awt.Rectangle screen=MacAppHost.screenBounds();
		long screenBytes=(long)Math.min(screen.width,QuickDraw68k.maxScreenWidth(depth,powerPC))*screen.height*Math.max(1,depth/8); // the screen programs see stops where rowBytes would overflow
		while(memorySize<256*1024*1024&&memorySize<M68kMemory.SIZE*2+2*screenBytes)memorySize*=2;
		return memorySize;
	}
	private int plannedDepth(){return screenDepthFor(depthOverride!=null?depthOverride:QuickDraw68k.screenDepth);}

	private void setUp() throws IOException,M68kException {
		int depth=plannedDepth();
		int memorySize=sharedMemory!=null?sharedMemory.partitionSize():plannedMemorySize(depth);
		java.awt.Rectangle screen=MacAppHost.screenBounds();
		addressing24=sharedMemory==null&&addressing24();
		if(addressing24){
			// the 24-bit world of the Macs these programs were written for: 16 MB, so the top address byte is ignored as the
			// hardware did (Word 1.0 tags its p-code pointers with $01 there), and a screen that leaves room for the heap
			memorySize=M68kMemory.SIZE;
			while(depth>1&&(long)Math.min(screen.width,QuickDraw68k.maxScreenWidth(depth))*screen.height*depth/8>ADDRESSING24_SCREEN_BYTES)depth=depth==32?16:depth==16?8:depth==8?4:depth/2;
			System.err.println("[68K "+name+"] 24-bit addressing (a program written for the 24-bit Memory Manager), screen depth "+depth);
		}
		// in its launcher's address space: the zone record starts the partition (ApplZone), the heap right after it
		int zone=sharedMemory!=null?sharedMemory.partitionBase():Toolbox68k.APPL_ZONE,heapBase=zone+(Toolbox68k.APPL_HEAP_START-Toolbox68k.APPL_ZONE);
		memory=sharedMemory!=null?sharedMemory:new M68kMemory(memorySize,Toolbox68k.APPL_HEAP_START);
		memory.addressing24=addressing24;
		memory.blockTags=walksHeapHeaders();
		mpwShell=isMpwShell();
		mclHeap=isMcl();
		memory.headerZone=zone;memory.blockHeaders=mpwShell||mclHeap;memory.blockHeaders24=!memory.blockHeaders&&readsBlockHeaders24();
		ResourceFile68k.Resource code0=resources.get(ResourceFile68k.fourcc("CODE"),0);
		byte[] c0;
		if(hosted!=null||powerPC){ // no CODE 0: a jump-table-sized slot for the host stub (see below) and a small A5 world
			c0=new byte[16+CONTROL_PANEL_STUB];aboveA5=32+CONTROL_PANEL_STUB;belowA5=0x2000;jumpTableSize=CONTROL_PANEL_STUB;jumpTableOffset=32;
		}else{
			c0=code0.data();
			if(c0.length<16)throw new IOException("CODE 0 resource is too short");
			aboveA5=u4(c0,0);belowA5=u4(c0,4);jumpTableSize=u4(c0,8);jumpTableOffset=u4(c0,12);
			if(aboveA5<0||belowA5<0||jumpTableSize<0||jumpTableOffset<0)throw new IOException("CODE 0 header is invalid (far-model or PowerPC application?)");
		}
		codeSegmentCount=resources.ofType(ResourceFile68k.fourcc("CODE")).size();
		int romBase=memory.romBase();
		int worldTop=(romBase-0x1000)&~0xf;
		a5=(worldTop-aboveA5)&~0xf;
		// CurStackBase must be exactly A5-belowA5: some startup code copies its DATA resource from
		// CurStackBase upward and only stops when the destination reaches A5.
		stackTop=(a5-belowA5)&~1;
		int stackSize=Math.max(256*1024,Math.min(2*1024*1024,memorySize/16));
		// A PowerPC program's stack lies in the application's stack area too, between ApplLimit and CurStackBase, as on a Power
		// Mac (68K code called from it gets the 68K stack above it). It came from the heap below ApplLimit before: Director 7
		// measures its room as SP - GetApplLimit, took the negative result for a stack overflow and silently aborted every
		// deeper Lingo call (Last Call never got past "Click anywhere to start your shift!").
		if(powerPC){PpcRuntime.FragmentEntry e=PpcRuntime.applicationFragment(resources);ppcStackSize=(Math.max(1024*1024,Math.min(8*1024*1024,e==null?0:e.stackSize))+0xf)&~0xf;}
		stackBottom=(stackTop-stackSize-ppcStackSize)&~0xf;
		memory.setHeapRange(heapBase,stackBottom);
		// jump table: copy the CODE 0 entries after the 16-byte header
		byte[] table=new byte[jumpTableSize];
		System.arraycopy(c0,16,table,0,Math.min(jumpTableSize,c0.length-16));
		memory.load(a5+jumpTableOffset,table);
		api=new Mac68kApi(memory,null);
		api.quickDraw().setPreferredScreenDepth(depth);startDepth=depth;
		toolbox=new Toolbox68k(this);
		toolbox.ownClockShift=ownClockShift();
		api.setApplication(toolbox);
		cpu=new M68kCpu(memory,0,api,null);
		cpu.setInstructionLimit(Long.MAX_VALUE);
		cpu.setRomHandler(toolbox);
		cpu.setScheduler(toolbox);
		toolbox.attach(cpu);
		if(hosted!=null)installHostStub();
		if(powerPC){memory.setBlockAlignment(16);ppc=new PpcRuntime(this);ppc.setUpStack(stackBottom,ppcStackSize);installPowerPcStub();} // heap blocks 16-byte aligned, as the PowerPC Memory Manager makes them
		host=toolbox.host();
		host.encoding=toolbox.encoding();
		toolbox.initializeLowMemory();
		cpu.a[5]=a5;cpu.a[7]=stackTop;cpu.a[6]=stackTop;
		cpu.sr=0x2000;
		cpu.a[7]-=4;memory.writeLong(cpu.a[7],M68kCpu.RETURN_SENTINEL);
		// System 7 launches applications with the return address of ExitToShell on the stack; the
		// sentinel above ends the run loop instead, which behaves the same way.
		int[] screenBefore=null;byte[] screenPixels=null;
		try{screenBefore=api.quickDraw().mainDeviceFrame();if(screenBefore!=null&&screenBefore[0]>0){int length=screenBefore[1]*(screenBefore[6]-screenBefore[4]);if(length>0&&screenBefore[0]+(long)length<=memory.raw().length)screenPixels=java.util.Arrays.copyOfRange(memory.raw(),screenBefore[0],screenBefore[0]+length);}}
		catch(M68kCpuException e){screenPixels=null;}
		toolbox.extensions.loadAll();
		api.quickDraw().forgetExtensionGlobals();
		// WWExist/QDExist: $FF until the program's own InitWindows/InitGraf (0 = initialized). THINK C's console initializes the
		// Toolbox - and marks itself ready to read the keyboard - only when WWExist says the Window Manager is not up yet; with 0
		// its input saw end-of-file at once (wumpus repeated its prompt forever). Hosted desk accessories and control panels
		// run in a world their host has set up.
		if(hosted==null){memory.writeByte(0x8f2,0xff);memory.writeByte(0x8f3,0xff);}
		if(controlPanel!=null)toolbox.extensions.loadControlPanel(file);
		toolbox.resources.extensionsLoaded();
		// start-up is over: notification responses the INITs queued get their system task time (QuickTime finishes its start-up)
		toolbox.runNotificationResponses();
		// The INITs drew their icons along the bottom of the screen, as at boot; on a real Mac the desktop was painted over them
		// before any program ran. A program drawing on the screen itself (video_demo, Fractal Islands) showed them in a corner.
		try{int[] screenAfter=api.quickDraw().mainDeviceFrame();if(screenPixels!=null&&screenAfter!=null&&java.util.Arrays.equals(screenBefore,screenAfter))System.arraycopy(screenPixels,0,memory.raw(),screenAfter[0],screenPixels.length);}
		catch(M68kCpuException e){}
		host.extensionsLoaded(); // the INIT icons they drew on the screen are not the program's own screen drawing
		if(colorOnlyOnSlotScreen()){try{api.quickDraw().setScreenAddressAlias(0xf0000000);}catch(M68kCpuException e){}} // before its InitGraf: ScrnBase/screenBits in slot space
		if(readsScreenBase()||compactScreen&&readsScreenRow()){api.quickDraw().setScreenPortBits(true);System.err.println("[68K "+name+"] reads ScrnBase/ScreenRow itself: classic windows show the screen as their portBits");}
		else if(checksCardWindowOnScreen()){api.quickDraw().setScreenPortBits(true);System.err.println("[68K "+name+"] checks its card window's portBits against the screen: classic windows show the screen as their portBits");}
		// On a real Mac the INITs ran at boot, before any application: QuickTime's hashes the longs at $928/$92C in place, which
		// here is the tail of CurApName (a Str31 at $910); a 30-character name then
		// lost its last bytes and the installer could not open its own data file ("The file name is invalid"). Restore it.
		// A control panel runs in the Finder's process on a real Mac, so CurApName is "Finder" there (as GetProcessInformation
		// says): QuicKeys' engine opens the editor its panel asked for only in the program whose CurApName the request names.
		toolbox.writePascal(0x910,controlPanel!=null?"Finder":name,31); // CurApName
		openResFileAtLaunch();
		// High-level-event-aware programs (SIZE flag 0x0040) receive Open Application / Open Documents.
		if((sizeFlags()&0x0040)!=0)toolbox.events.postLaunchDocuments(new ArrayList<File>(documents));
	}

	/**
	 * The launch opened the program's resource fork by its name (CurApName) through the Resource Manager, where INITs that
	 * patched OpenResFile/HOpenResFile saw every launch. QuicKeys' engine installs its per-program patches there
	 * (MenuSelect, GetOSEvent...) and without them never opened the editor its control panel asked for. Here the file is
	 * already open, so the call is made only when one of those traps is patched; a control panel's process is the Finder's,
	 * whose file is opened in the System Folder (and closed again if that put a new map into the panel's chain).
	 */
	private void openResFileAtLaunch(){
		if(cpu.patchedTrap(0xa9c4)==0&&cpu.patchedTrap(0xa997)==0&&cpu.patchedTrap(0xa81a)==0)return;
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sp=cpu.a[7];int stub=0,namePtr=0;
		try{
			File folder=controlPanel!=null?toolbox.files.systemFolder:file.getParentFile();
			int vRefNum=folder==null?0:toolbox.files.workingDirectoryFor(folder);
			stub=memory.allocate(8,2);namePtr=memory.allocate(32,2);
			toolbox.writePascal(namePtr,controlPanel!=null?"Finder":name,31);
			memory.writeWord(stub,0xa81a);memory.writeWord(stub+2,0x4ef9);memory.writeLong(stub+4,M68kCpu.RETURN_SENTINEL);
			int savedCurrent=toolbox.resources.currentRefNum();
			cpu.a[7]-=2;int result=cpu.a[7];memory.writeWord(result,0);
			cpu.pushWord(vRefNum<0?vRefNum:0);cpu.pushLong(0);cpu.pushLong(namePtr);cpu.pushWord(0); // HOpenResFile(vRefNum, dirID, name, fsCurPerm): INTEGER
			cpu.runUntilSentinel(stub,12);
			int ref=(short)memory.readWord(result);
			if(ref>0&&ref!=toolbox.resources.applicationRefNum()&&ref!=savedCurrent&&controlPanel!=null){
				cpu.a[7]=result;cpu.pushWord(ref);memory.writeWord(stub,0xa99a);cpu.runUntilSentinel(stub,2);} // CloseResFile
			toolbox.resources.useResFile(savedCurrent);
			memory.writeWord(0xa60,0); // ResErr
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[68K "+name+"] OpenRFPerm at launch: "+e);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp;
			try{if(stub!=0)memory.free(stub);if(namePtr!=0)memory.free(namePtr);}catch(Exception e){}}
	}

	/** Bytes of the host stub at the jump table's first entry (+2, where an application's first entry is run). */
	private static final int CONTROL_PANEL_STUB=16;
	/**
	 * The guest side of a control panel or desk accessory process: `loop: JSR gate; TST.W D0; BEQ.S loop; _ExitToShell`.
	 * The gate runs one pass of {@link HostedCode#step} and returns D0 = 1 once the panel or accessory has closed.
	 */
	private void installHostStub() throws M68kCpuException {
		final HostedCode code=hosted;
		int gate=toolbox.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {c.d[0]=code.step()?1:0;}});
		int at=a5+jumpTableOffset+2;
		memory.writeWord(at,0x4eb9);memory.writeLong(at+2,gate); // JSR gate.L
		memory.writeWord(at+6,0x4a40);                          // TST.W D0
		memory.writeWord(at+8,0x67f6);                          // BEQ.S loop
		memory.writeWord(at+10,0xa9f4);                         // _ExitToShell
	}

	/**
	 * The guest side of a PowerPC program: `JSR gate; _ExitToShell`. The gate loads the program's code fragment and its
	 * libraries and calls its main routine (PpcRuntime.runApplication); the program ends there or by calling ExitToShell.
	 */
	private void installPowerPcStub() throws M68kCpuException {
		final PpcRuntime runtime=ppc;
		int gate=toolbox.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {
			try{runtime.runApplication();}
			catch(IOException e){throw new M68kCpuException(c.pc,"PowerPC program: "+e.getMessage());}
		}});
		int at=a5+jumpTableOffset+2;
		memory.writeWord(at,0x4eb9);memory.writeLong(at+2,gate); // JSR gate.L
		memory.writeWord(at+6,0xa9f4);                          // _ExitToShell
	}

	/** Debug aid: executes and clears "click <windowHex> <x> <y>", "dblclick ..." and "key <windowHex> <char>" lines. */
	/** Debug aid (-Dhz68k.crashdump=dir): on a fatal error, writes the code around the PC and the memory around each address register as .bin files for a disassembler. */
	void crashDump(int pc){
		String dir=System.getProperty("hz68k.crashdump");if(dir==null)return;
		try{File d=new File(dir);d.mkdirs();
			java.nio.file.Files.write(new File(d,String.format("pc-%06X.bin",Math.max(0,pc-0x800))).toPath(),memory.readBytes(Math.max(0,pc-0x800),0x1000));
			for(int i=0;i<7;i++){int a=cpu.a[i];if(a<=0x100||a+0x200>memory.size())continue;java.nio.file.Files.write(new File(d,String.format("a%d-%06X.bin",i,Math.max(0,a-0x100))).toPath(),memory.readBytes(Math.max(0,a-0x100),0x300));}
			java.nio.file.Files.write(new File(d,"regs.txt").toPath(),cpu.dumpState(pc).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}catch(Exception e){System.err.println("[68K debug] crash dump failed: "+e);}
	}

	private void runDebugCommands(File file){
		if(!file.isFile())return;
		try{java.util.List<String> lines=java.nio.file.Files.readAllLines(file.toPath());java.nio.file.Files.write(file.toPath(),new byte[0]);
			for(String line:lines)ownerOf(line).runDebugCommand(line);
		}catch(Exception e){System.err.println("[68K debug] "+e);}
	}
	/** The running program that has the window a window command (click, key, goaway ...) names: every program reads the one command file. */
	private MacApp68k ownerOf(String line){
		String[] p=line.trim().split("\\s+");
		if(p.length<2||!java.util.Arrays.asList("click","dblclick","key","osclick","activate","goaway","resize","ime").contains(p[0])||host.window(parseHex(p[1]))!=null)return this;
		for(MacApp68k other:runningApplications())if(other.host!=null&&other.host.window(parseHex(p[1]))!=null)return other;
		return this;
	}
	private static int parseHex(String s){try{return Integer.parseInt(s,16);}catch(NumberFormatException e){return 0;}}
	private void runDebugCommand(String line){
		try{
			do{String[] p=line.trim().split("\\s+");
				if(p[0].equals("trace")&&p.length>=2){Mac68kApi.TRACE=p[1].equals("on");continue;}
				if(p[0].equals("ppctrace")&&p.length>=2){PpcRuntime.TRACE=p[1].equals("on");continue;} // ppctrace on|off: -Dhzppc.trace at run time
				if(p[0].equals("wait")&&p.length>=2){Thread.sleep(Integer.parseInt(p[1]));continue;} // wait <ms>: pause between commands (human-paced double clicks for programs that poll Button())
				if(p[0].equals("menuopen")&&p.length>=2){final int id=Integer.parseInt(p[1]);new Thread(()->System.err.println("[68K debug] menuopen "+id+": "+host.debugOpenMenu(id)),"menuopen").start();continue;} // menuopen <menuID>: the host menu opened and closed as by the user (its listeners run), its items printed
				if(p[0].equals("ppctrace")&&p.length>=2){PpcRuntime.TRACE=p[1].equals("on");continue;} // ppctrace on|off: PowerPC native-call trace (-Dhzppc.trace)
				if(p[0].equals("ppccalls")&&p.length>=2){PpcCpu.CALL_TRACE=p[1].equals("on");continue;} // ppccalls on|off: every PowerPC call taken (bl/bctrl) with its target
				if(p[0].equals("pctrace")){M68kCpu.setPcTrace(p.length>=2?p[1]:"");continue;} // pctrace start-end[,start-end] (hex) / pctrace alone = off
				if(p[0].equals("sclick")&&p.length>=3){ // sclick <screenX> <screenY>: press and release the emulated button there (Button()/GetMouse pollers, the screen stand-in)
					int sx=Integer.parseInt(p[1]),sy=Integer.parseInt(p[2]);host.simulateMouse(sx,sy,true);try{Thread.sleep(80);}catch(InterruptedException ignored){}host.simulateMouse(sx,sy,false);continue;}
				if(p[0].equals("mouse")&&p.length>=4){ // mouse press|drag|release <screenX> <screenY>: AWT-level mouse events (drives Swing popups)
					host.injectAwtMouse(p[1].equals("press")?java.awt.event.MouseEvent.MOUSE_PRESSED:p[1].equals("release")?java.awt.event.MouseEvent.MOUSE_RELEASED:java.awt.event.MouseEvent.MOUSE_DRAGGED,Integer.parseInt(p[2]),Integer.parseInt(p[3]));continue;}
				if(p[0].equals("mount")&&p.length>=2){ // mount <disk image path>: inserts a disk while the program runs (diskEvt; installers asking for the next disk)
					String path=line.trim().substring(5).trim();
					try{DiskImages.Mounted m=DiskImages.mount(new File(path),null);System.err.println("[68K debug] mounted "+m.name);}
					catch(IOException|RuntimeException e){System.err.println("[68K debug] can't mount "+path+": "+e);}
					continue;}
				if(p[0].equals("dump")&&p.length>=3){ // dump <hexaddr> <hexlen>: hex dump of guest memory
					int at=(int)Long.parseLong(p[1],16),n=Integer.parseInt(p[2],16);StringBuilder sb=new StringBuilder();
					for(int i=0;i<n;i++){if(i%16==0)sb.append(String.format("%n%08X:",at+i));sb.append(String.format(" %02X",memory.readByte(at+i)));}
					System.err.println("[68K debug] dump"+sb);continue;}
				if(p[0].equals("palette")){int[] c=host.devicePalette(8);StringBuilder sb=new StringBuilder("[68K debug] palette");if(p.length>=2&&p[1].equals("all"))for(int i=0;i<256;i++)sb.append(String.format(" %06x",c[i]));else for(int i:new int[]{0,1,2,3,4,5,6,7,64,128,200,254,255})sb.append(String.format(" [%d]=%06x",i,c[i]));System.err.println(sb);continue;} // palette [all]: main device CLUT sample (all: the 256 entries in order)
				if(p[0].equals("planes")){SmoothZoom sz=host.smoothZoom();System.err.println("[68K debug] planes"+(sz==null?" (none)":sz.describe(p.length>=2?new File(p[1]):null)));continue;} // planes [dir]: the smooth zoom's planes (images to dir)
				if(p[0].equals("paintdump")&&p.length>=2){MacAppHost.paintDumpDir=new File(p[1]);host.invalidateDisplays();continue;}
				if(p[0].equals("dumppng")&&p.length>=6){ // dumppng <hexaddr> <rowBytes> <width> <height> <file> [1]: 8-bit pixels through the screen palette (or a 1-bit BitMap)
					int at=(int)Long.parseLong(p[1],16),rb=Integer.parseInt(p[2]),w=Integer.parseInt(p[3]),h=Integer.parseInt(p[4]);int[] clut=host.devicePalette(8);boolean oneBit=p.length>6&&p[6].equals("1"),direct=p.length>6&&p[6].equals("32"); // [32]: xRGB pixels
					java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_RGB);
					for(int y=0;y<h;y++)for(int x=0;x<w;x++)img.setRGB(x,y,direct?memory.readLong(at+y*rb+x*4)&0xffffff:oneBit?((memory.readByte(at+y*rb+(x>>3))>>(7-(x&7))&1)!=0?0:0xffffff):clut[memory.readByte(at+y*rb+x)]);
					try{javax.imageio.ImageIO.write(img,"png",new File(p[5]));}catch(Exception e){}continue;}
				if(p[0].equals("pointer")&&p.length>=3){host.simulateMouse(Integer.parseInt(p[1]),Integer.parseInt(p[2]),p.length>3&&p[3].equals("down"));continue;} // pointer <screenX> <screenY> [down]: move the emulated pointer (Mouse low memory / mouse-warp games)
				if(p[0].equals("addrring")){System.err.println("[68K debug] "+memory.describeAddrRing());continue;} // addrring: the -Dhz68k.addrprobe write ring without a crash
				if(p[0].equals("rmap")&&p.length>=2){System.err.println("[68K debug] rmap"+toolbox.resources.describeGuestMap(Integer.parseInt(p[1])));continue;} // rmap <refNum>: the program's resource map in memory
				if(p[0].equals("threads")){System.err.println("[68K debug] threads"+toolbox.threads.describe());continue;} // threads: the Thread Manager's threads and their states
				if(p[0].equals("otstate")){System.err.println("[68K debug] otstate"+toolbox.openTransport().describe());continue;} // otstate: Open Transport providers and undelivered events
				if(p[0].equals("menus")){System.err.println("[68K debug] menus"+toolbox.menus.describeMenus());continue;} // menus: the menu bar as the program built it
				if(p[0].equals("ppc")){System.err.println("[68K debug] ppc\n"+(ppc==null?"not a PowerPC program":ppc.describe(ppc.cpu.lastPc())));continue;} // ppc: PowerPC registers, recent PCs and fragment sections (racy, read-only)
				if(p[0].equals("newcontrol")&&p.length>=10){ // newcontrol <windowHex> <procID> <top> <left> <bottom> <right> <value> <min> <max> [title]: NewControl in that window (CDEF comparisons)
					final int window=parseHex(p[1]);final int[] v=new int[8];for(int i=0;i<8;i++)v[i]=Integer.parseInt(p[2+i]);
					final String title=p.length>10?line.trim().split("\\s+",11)[10]:"";
					final Toolbox68k tb=toolbox;tb.laterAtEvent(()->{try{byte[] t=tb.encoding.encode(title);byte[] pt=new byte[Math.min(255,t.length)+1];pt[0]=(byte)(pt.length-1);System.arraycopy(t,0,pt,1,pt.length-1);
						int h=tb.controls.create(window,v[1],v[2],v[3],v[4],pt,true,v[5],v[6],v[7],v[0],0);System.err.println("[68K debug] newcontrol $"+Integer.toHexString(h));}
						catch(M68kException|java.nio.charset.CharacterCodingException e){throw new RuntimeException(e);}});continue;}
				if(p[0].equals("ctlhilite")&&p.length>=3){ // ctlhilite <controlHex> <part>: HiliteControl (255 = inactive)
					final int h=parseHex(p[1]),part=Integer.parseInt(p[2]);final Toolbox68k tb=toolbox;
					tb.laterAtEvent(()->{try{int d=tb.controls.data(h);if(d!=0){memory.writeByte(d+17,part);tb.controls.draw(h);}}catch(M68kException e){throw new RuntimeException(e);}});continue;}
				if(p[0].equals("poke")&&p.length>=3){int at=parseHex(p[1]);for(int i=0;i+1<p[2].length();i+=2)memory.writeByte(at+i/2,Integer.parseInt(p[2].substring(i,i+2),16));continue;} // poke <hexaddr> <hexbytes>: write guest memory (debugging: a game's lives counter)
				if(p[0].equals("segments")){System.err.println("[68K debug] segments\n"+toolbox.describeSegments());continue;} // segments: where each loaded CODE segment lives
				if(p[0].equals("regs")){System.err.println("[68K debug] regs\n"+cpu.dumpState(cpu.pc));continue;} // regs: registers, recent PCs, code and stack of the running program (racy, read-only)
				if(p[0].equals("dirty")){System.err.println("[68K debug] "+memory.describeTracking()+host.describeWindowSerials());continue;} // dirty: direct screen write statistics
				if(p[0].equals("watch")&&p.length>=2){M68kCpu.WATCH=(int)Long.parseLong(p[1],16);System.err.println("[68K debug] watching $"+p[1]);continue;} // watch <hexaddr>: log every change of that long with the PC
				if(p[0].equals("find")&&p.length>=2){ // find <hexbytes> [<hexstart> <hexend>]: addresses of a byte pattern in guest memory
					byte[] pattern=new byte[p[1].length()/2];for(int i=0;i<pattern.length;i++)pattern[i]=(byte)Integer.parseInt(p[1].substring(i*2,i*2+2),16);
					int from=p.length>3?(int)Long.parseLong(p[2],16):0,to=p.length>3?(int)Long.parseLong(p[3],16):memory.size();StringBuilder sb=new StringBuilder();int hits=0;
					for(int at=from;at+pattern.length<=to&&hits<64;at++){int i=0;while(i<pattern.length&&(byte)memory.readByte(at+i)==pattern[i])i++;if(i==pattern.length){sb.append(String.format(" %08X",at));hits++;}}
					System.err.println("[68K debug] find "+p[1]+":"+sb);continue;}
				if(p[0].equals("ime")&&p.length>=3){host.injectInputMethod(Integer.parseInt(p[1],16),p[2].equals("-")?"":p[2],p.length>3?p[3]:"");continue;} // ime <recordHex> <composing>|- [committed]
				if(p[0].equals("wheel")&&p.length>=4){host.listener().postWheel(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),p.length>4&&p[4].equals("h"));continue;} // wheel <screenX> <screenY> <steps> [h]
				if(p[0].equals("popup")&&p.length>=2){String[] at=p[1].split("\\.");host.injectPopupChoice(Integer.parseInt(at[0]),at.length>1?Integer.parseInt(at[1]):0);continue;} // popup <item>[.<subitem>]: choose in the open PopUpMenuSelect menu (0 = dismiss)
				if(p[0].equals("activate")&&p.length>=2){host.injectActivate(Integer.parseInt(p[1],16));continue;} // activate <recordHex>: activate/deactivate events
				if(p[0].equals("deactivate")&&p.length>=2){host.injectDeactivate(Integer.parseInt(p[1],16));continue;} // deactivate <recordHex>: what switching to another application does
				if(p[0].equals("osclick")&&p.length>=4){host.injectOsClick(Integer.parseInt(p[1],16),Integer.parseInt(p[2]),Integer.parseInt(p[3]));continue;} // osclick <recordHex> <x> <y>: what a real click on a background window does (OS activation, then the press and release)
				if(p[0].equals("movewin")&&p.length>=4){host.injectWindowMove(Integer.parseInt(p[1],16),Integer.parseInt(p[2]),Integer.parseInt(p[3]));continue;} // movewin <recordHex> <dx> <dy>: move the native frame by host pixels (the user dragging its title bar)
				if(p[0].equals("restack")){host.bringToFront();continue;} // restack: raise the program's windows in its stacking order again
				if(p[0].equals("focusinfo")){System.err.println("[68K debug] "+host.describeFocus());continue;} // focusinfo: the host windows' focus state
				if(p[0].equals("screenbar")&&p.length>=3){final int dx=Integer.parseInt(p[1]),dy=Integer.parseInt(p[2]);javax.swing.SwingUtilities.invokeAndWait(()->host.dragScreenBar(dx,dy));System.err.println("[68K debug] screenbar "+host.screenBarBounds());continue;} // screenbar <dx> <dy>: drag the game screen's title bar by host pixels
				if(p[0].equals("quit")){host.listener().postQuit();continue;} // quit: what closing the stand-in window does
				if(p[0].equals("hostquit")){javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){PCARD.exit();}});continue;} // hostquit: quit HyperZebra itself as its Quit menu does (programs get to save their preferences)
				if(p[0].equals("goaway")&&p.length>=2){host.listener().postGoAway(Integer.parseInt(p[1],16));continue;} // goaway <recordHex>: native close box click
				if(p[0].equals("resize")&&p.length>=4){host.listener().postWindowResized(Integer.parseInt(p[1],16),Integer.parseInt(p[2]),Integer.parseInt(p[3]));continue;} // resize <recordHex> <width> <height>: what dragging the native frame's edge reports
				if(p[0].equals("keymap")&&p.length>=3){host.setKeyState(Integer.parseInt(p[1],16),p[2].equals("down"));continue;} // keymap <hexKeyCode> down|up: GetKeys state
				if(p[0].equals("mem")&&p.length>=3){ // mem <hexAddress> <length>: hex dump to stderr
					int at=parseHex(p[1]),n=Integer.parseInt(p[2]);StringBuilder sb=new StringBuilder("[68K debug] memory at $"+p[1]+":");
					for(int i=0;i<n;i++){if(i%16==0)sb.append(String.format("%n  %08X:",at+i));sb.append(String.format(" %02X",memory.readByte(at+i)));}
					System.err.println(sb);continue;}
				if(p[0].equals("port")&&p.length>=3){ // port <portHex> <file.png>: the pixels of a colour port or GWorld (debugging offscreen drawing)
					java.awt.image.BufferedImage im=api.quickDraw().scratchPortImage(parseHex(p[1]));
					if(im!=null)javax.imageio.ImageIO.write(im,"png",new File(p[2]));else System.err.println("[68K debug] no pixels for port "+p[1]);continue;}
				if(p[0].equals("sfsave")&&p.length>=2){StandardFile68k.scriptedSave=new File(line.trim().substring(6).trim());continue;} // sfsave <path>: the next Save panel answers this file
				if(p[0].equals("navreply")){String v=line.trim().substring(8).trim();if(v.isEmpty())System.clearProperty("hzppc.navreply");else System.setProperty("hzppc.navreply",v);continue;} // navreply <answers>: the Navigation Services answers from now on, as -Dhzppc.navreply (paths separated by ':' within one, ';' between dialogs; none = the user's again)
				if(p[0].equals("sfreply")&&p.length>=2){StandardFile68k.scriptedReply=new File(line.trim().substring(7).trim());continue;} // sfreply <path>: the next plain Open panel answers this file (spaces allowed)
				if(p[0].equals("drop")&&p.length>=4){ // drop [shift] [cmd] [option] [control] <screenX> <screenY> <path>: files dragged from the host's Finder and dropped there (Drag Manager, else Open Documents)
					int skip=1,mods=0x80;while(skip<p.length&&!p[skip].matches("-?\\d+")){String m=p[skip++];mods|=m.equals("cmd")?0x100:m.equals("shift")?0x200:m.equals("option")?0x800:m.equals("control")?0x1000:0;}
					if(skip+2>=p.length)continue;
					java.util.List<File> docs=new java.util.ArrayList<File>();docs.add(new File(line.trim().split("\\s+",skip+3)[skip+2]));int x=Integer.parseInt(p[skip]),y=Integer.parseInt(p[skip+1]);
					// moves for almost two seconds, as a hand hovers: A-Dock's tracking handler opens a gap in the dock and only then knows where the drop goes
					for(int i=11;i>=0;i--){host.holdSyntheticPointer(new java.awt.Point(x-(i&3),y));host.listener().postExternalDrag(DragManager68k.EXTERNAL_MOVE,x-(i&3),y,docs,mods);try{Thread.sleep(150);}catch(InterruptedException e){break;}}
					host.listener().postExternalDrag(DragManager68k.EXTERNAL_DROP,x,y,docs,mods);continue;}
				if(p[0].equals("launch")&&p.length>=2){ // launch <path>: open a program or document as the host Finder would (a second program beside this one)
					final java.util.List<File> docs=new java.util.ArrayList<File>();docs.add(new File(line.trim().substring(7).trim()));
					javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){PCARD.openFiles(docs,false);}});continue;}
				if(p[0].equals("open")&&p.length>=2){ // open <path>: Open Documents event, like a drop on the program's window
					java.util.List<File> docs=new java.util.ArrayList<File>();docs.add(new File(line.trim().substring(5).trim()));host.listener().postOpenDocuments(docs);continue;}
				if(p.length<3)continue;int record=Integer.parseInt(p[1],16);
				if(p[0].equals("click")||p[0].equals("dblclick")){int mods=0; // click <recordHex> <x> <y> [shift] [cmd] [option] [control]
					for(int i=4;i<p.length;i++)mods|=p[i].equals("cmd")?0x100:p[i].equals("shift")?0x200:p[i].equals("option")?0x800:p[i].equals("control")?0x1000:0;
					host.injectClick(record,Integer.parseInt(p[2]),Integer.parseInt(p[3]),p[0].equals("click")?1:2,mods);}
				else if(p[0].equals("key")){ // key <recordHex> <char|0xNN> [cmd] [shift] [option]
					int ch=p[2].startsWith("0x")?Integer.parseInt(p[2].substring(2),16):p[2].charAt(0),mods=0;
					for(int i=3;i<p.length;i++)mods|=p[i].equals("cmd")?0x100:p[i].equals("shift")?0x200:p[i].equals("option")?0x800:0;
					if(p.length>3)host.injectKey(record,ch,mods);else host.injectKey(record,ch);}
				else if(p[0].equals("menu"))host.listener().postMenuSelection(Integer.parseInt(p[1]),Integer.parseInt(p[2]));}while(false); // do-while: the commands above end with continue
		}catch(Exception e){System.err.println("[68K debug] "+e);}
	}

	/**
	 * The main screen depth this program gets: the preference, unless the program predates 32-Bit QuickDraw - then 256
	 * colours (8 bits), the deepest screen such programs know. Colour programs written before 1989 assume every device
	 * is indexed (gdType clut, pixelSize <= 8): some graphics applications size their colour table with `1 << pixelSize` in a
	 * word and only handles clut devices, so on a direct 16/32-bit screen it runs wild over its jump table. The test
	 * is static: the CODE resources use Color QuickDraw traps ($AAxx) but neither _Gestalt (System 6.0.4, 1989) nor
	 * _QDExtensions ($AB1D: NewGWorld and the other 32-Bit QuickDraw calls) - any program aware of direct devices
	 * uses one of them. A program whose resources ask for a 16-colour screen gets 4 bits whatever the preference (Color VETTE!
	 * checks for exactly 4 and quits otherwise). -Dhz68k.depth (scripts and tests) and -Dhz68k.autodepth=false keep the
	 * preference as is.
	 */
	private int screenDepthFor(int preferred){
		if(compactScreen&&depthOverride==null&&System.getProperty("hz68k.depth")==null)return 1;
		if(depthOverride==null&&System.getProperty("hz68k.depth")==null&&!"false".equals(System.getProperty("hz68k.autodepth"))&&colorOnlyOnSlotScreen()){
			System.err.println("[68K "+name+"] screen depth "+preferred+" set to 4: the program draws its own 16-colour screen (ScrnBase shown in NuBus slot space)");
			return 4;}
		if(preferred!=4&&depthOverride==null&&System.getProperty("hz68k.depth")==null&&!"false".equals(System.getProperty("hz68k.autodepth"))&&!"false".equals(System.getProperty("hz68k.autodepthscan"))){
			String sixteen=IndexedScreenText.findSixteenIn(resources);
			if(sixteen!=null){System.err.println("[68K "+name+"] screen depth "+preferred+" set to 4: the program asks for a 16-colour screen (\""+sixteen+"\")");return 4;}}
		if(preferred<=8||System.getProperty("hz68k.depth")!=null||"false".equals(System.getProperty("hz68k.autodepth")))return preferred;
		if(indexedThisSession.contains(file.getAbsolutePath())){System.err.println("[68K "+name+"] screen depth "+preferred+" lowered to 8: the program asked for 256 colours at its last start");return 8;}
		if(depthOverride!=null)return preferred; // the user chose this program's depth
		if(predatesDirectDevices()){
			System.err.println("[68K "+name+"] screen depth "+preferred+" lowered to 8: the program predates 32-Bit QuickDraw (Color QuickDraw without Gestalt or QDExtensions)");
			return 8;}
		String asks="false".equals(System.getProperty("hz68k.autodepthscan"))?null:IndexedScreenText.findIn(resources); // debug: -Dhz68k.autodepthscan=false leaves it to the run-time check
		if(asks!=null){System.err.println("[68K "+name+"] screen depth "+preferred+" lowered to 8: the program asks for a 256-colour screen (\""+asks+"\")");return 8;}
		return preferred;
	}

	/** Programs found asking for 256 colours at run time in this session (their relaunch starts at 8 bits even without the launcher's store). */
	private static final java.util.Set<String> indexedThisSession=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	/** The depth this run's screen started with, and the recent text the program drew (see {@link #textShown}). */
	private volatile int startDepth;private final StringBuilder recentText=new StringBuilder();private volatile boolean relaunching;
	/** How long after the start drawn text is still watched for a request of 256 colours. */
	private static final long INDEXED_WATCH_MILLIS=60_000;

	/**
	 * Text the program just drew (DrawString, DrawChar, TextBox, dialog items). A program started on a screen deeper than 8
	 * bits that tells the user within its first minute to set the monitor to 256 colours (Cheese Toast "Reset your monitor to
	 * 256 colors and try again", Citadel of the Dead "Set your monitor to 256-colors/greys") is restarted at 8 bits and keeps
	 * that depth (the launcher's per-program setting) - the static look at its resources ({@link IndexedScreenText}) misses
	 * texts kept compressed or built at run time.
	 */
	void textShown(byte[] text){
		if(relaunching||startDepth<=8||service||hosted!=null||System.currentTimeMillis()-startedAt>INDEXED_WATCH_MILLIS)return;
		if(System.getProperty("hz68k.depth")!=null||"false".equals(System.getProperty("hz68k.autodepth"))||depthOverride!=null)return;
		String hit;
		synchronized(recentText){
			for(byte b:text)recentText.append((char)(b&0xff));
			if(recentText.length()>600)recentText.delete(0,recentText.length()-600);
			byte[] window=new byte[recentText.length()];for(int i=0;i<window.length;i++)window[i]=(byte)recentText.charAt(i);
			hit=IndexedScreenText.find(window);
		}
		if(hit!=null)relaunchIndexed(hit);
	}
	/**
	 * The program asked for a block of negative size (Mac68kApi.negativeSize). Within its first minute on a screen deeper than
	 * 8 bits that is 16-bit rowBytes arithmetic that overflowed (width*pixelSize above 32767) - no 8-bit screen up to 4095
	 * pixels wide can make it - and the program would go on with a nil block: it is restarted at 8 bits like one asking for
	 * 256 colours.
	 */
	void negativeAllocation(int size){
		if(relaunching||startDepth<=8||service||hosted!=null||System.currentTimeMillis()-startedAt>INDEXED_WATCH_MILLIS)return;
		if(System.getProperty("hz68k.depth")!=null||"false".equals(System.getProperty("hz68k.autodepth"))||depthOverride!=null)return;
		kill(); // it would go on with the nil block to its crash
		relaunchIndexed(null,"a block of "+size+" bytes requested (16-bit size arithmetic overflowed on the "+startDepth+"-bit screen)");
	}
	/**
	 * The program's 68K code crashed within its first minute on a screen deeper than 8 bits: a program that treats every device as
	 * indexed (Space Junkie 1.2 fades in by walking the main device's colour table with the word ctSize, -1 on a direct
	 * device, over its own code) is restarted once at 8 bits - for this session only, the launcher's per-program depth stays as it was.
	 */
	private void retryIndexedAfterCrash(){
		if(relaunching||startDepth<=8||service||hosted!=null||java.awt.GraphicsEnvironment.isHeadless()||System.currentTimeMillis()-startedAt>INDEXED_WATCH_MILLIS)return;
		if(toolbox!=null&&toolbox.events.quitInProgress())return; // crashed while closing down (Mactella's thread teardown): quitting must not start it again
		if(System.getProperty("hz68k.depth")!=null||"false".equals(System.getProperty("hz68k.autodepth"))||depthOverride!=null)return;
		relaunchIndexed(null,"crashed within its first minute on the "+startDepth+"-bit screen",false);
	}
	private void relaunchIndexed(String why){relaunchIndexed(why,null,true);}
	private void relaunchIndexed(String why,String reason){relaunchIndexed(why,reason,true);}
	private void relaunchIndexed(String why,String reason,boolean keep){
		relaunching=true;
		System.err.println(why!=null?"[68K "+name+"] asks for 256 colours on a "+startDepth+"-bit screen (\""+why+"\"): restarting it at 8 bits"
			:"[68K "+name+"] "+reason+": restarting it at 8 bits");
		indexedThisSession.add(file.getAbsolutePath());
		if(keep&&LauncherStore.enabled){try{LauncherStore store=LauncherStore.get();LauncherStore.AppSettings s=store.settingsFor(file);if(s==null)s=new LauncherStore.AppSettings();if(s.depth==null){s.depth=8;store.putSettings(file,s);}}catch(Exception e){System.err.println("[68K "+name+"] could not keep the 8-bit setting: "+e);}}
		final List<File> docs=new ArrayList<File>(documents);final File program=file;
		Thread t=new Thread(new Runnable(){@Override public void run(){
			kill();
			for(int i=0;i<100&&!finished;i++){try{Thread.sleep(50);}catch(InterruptedException e){return;}}
			try{launch(program,docs);}catch(IOException e){System.err.println("[68K] relaunch of "+program.getName()+" failed: "+e.getMessage());}
		}},"68K relaunch");
		t.setDaemon(true);t.start();
	}
	/**
	 * True when the program's code reads ScrnBase ($824) with an absolute short address (move.l/movea.l $824.w,...): it writes the
	 * screen memory itself, the way programs for the compact Macs did, and takes window positions in it from portBits (see
	 * QuickDraw68k.setScreenPortBits). -Dhz68k.screenportbits=false turns that off.
	 */
	/** Set at launch for a {@link #compactMacProgram}: its screen is the 512x342 1-bit one of the compact Macs. */
	boolean compactScreen;
	/**
	 * A program for the compact Macintoshes that writes their screen itself: it reads ScrnBase and has no SIZE resource (it
	 * predates MultiFinder, 1987). Such programs address the screen with the 512x342 geometry built in - Space Quest's picture
	 * copy steps 64 bytes a row whatever the screen's rowBytes - so they get that screen, shown twice as large.
	 */
	boolean compactMacProgram(){return !hasSizeResource()&&(readsScreenBase()||readsScreenRow()||stepsCompactScreenRows());}
	/**
	 * True when the program's code steps through screen rows of the compact Macs' 64 bytes itself: a store through (An), then
	 * adda.w #$40,An and a DBRA closing the loop. The Guided Tour's Labyrinthe (1984) ORs its 16x16 maze cells into the screen
	 * that way, the base from screenBits - on a wider screen the walls were never seen and the menu bar rows filled with noise.
	 */
	boolean stepsCompactScreenRows(){
		if(hosted!=null||"false".equals(System.getProperty("hz68k.screenportbits")))return false;
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=4;i+9<code.length;i+=2){
				int store=((code[i]&0xff)<<8)|(code[i+1]&0xff),adda=((code[i+2]&0xff)<<8)|(code[i+3]&0xff),dbra=((code[i+6]&0xff)<<8)|(code[i+7]&0xff);
				if((adda&0xf1ff)!=0xd0fc||(code[i+4]&0xff)!=0x00||(code[i+5]&0xff)!=0x40||(dbra&0xfff8)!=0x51c8)continue; // adda.w #$40,An; dbra Dn
				int size=store>>>12;
				if(size>=1&&size<=3&&(store&0x01c0)==0x0080&&((store>>9)&7)==((adda>>9)&7))return true; // move.b/w/l <ea>,(An)
			}
		}
		return false;
	}
	/**
	 * True when the program's code reads ScreenRow ($106, the screen's rowBytes) with an absolute short address (move.w
	 * $106.w,<ea> or mulu.w $106.w,Dn): it steps through the screen memory itself. MacPaint 1.5 blits its 1-bit document
	 * rows to the screen that way (base from screenBits, stride ScreenRow) and never reads ScrnBase.
	 */
	boolean readsScreenRow(){
		if(hosted!=null||"false".equals(System.getProperty("hz68k.screenportbits")))return false;
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=4;i+3<code.length;i+=2){
				int word=((code[i]&0xff)<<8)|(code[i+1]&0xff);
				if((code[i+2]&0xff)!=0x01||(code[i+3]&0xff)!=0x06)continue;
				if((word&0xf03f)==0x3038||(word&0xf1ff)==0xc0f8)return true; // move.w $106.w,<ea> (movea.w too); mulu.w $106.w,Dn
			}
		}
		return false;
	}
	boolean readsScreenBase(){
		if(hosted!=null||"false".equals(System.getProperty("hz68k.screenportbits")))return false;
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=4;i+3<code.length;i+=2){
				int word=((code[i]&0xff)<<8)|(code[i+1]&0xff);
				if((word&0xf03f)==0x2038&&(code[i+2]&0xff)==0x08&&(code[i+3]&0xff)==0x24)return true; // move.l $824.w,<ea> (movea.l too)
			}
		}
		return false;
	}
	/**
	 * True for HyperCard 2.0-2.2 and their stand-alone stacks: every pass of the main event loop checks that the card window's
	 * portBits.baseAddr is screenBits.baseAddr (both through StripAddress) and stops with "Unexpected error 123453" otherwise -
	 * on a real Mac a NewWindow port's portBits is the screen BitMap. The check is the instruction sequence
	 * move.l (sp)+,d0; cmp.l (sp)+,d0; beq.s *+12; move.l #123453,-(sp); HyperCard 2.3 dropped it. Myst Preview reached the
	 * loop once its intro script had finished and quit with that alert.
	 */
	boolean checksCardWindowOnScreen(){
		if(hosted!=null||"false".equals(System.getProperty("hz68k.screenportbits")))return false;
		byte[] check={0x20,0x1f,(byte)0xb0,(byte)0x9f,0x67,0x0a,0x2f,0x3c,0x00,0x01,(byte)0xe2,0x3d};
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			outer:for(int i=4;i+check.length<=code.length;i+=2){
				for(int j=0;j<check.length;j++)if(code[i+j]!=check[j])continue outer;
				return true;
			}
		}
		return false;
	}
	/**
	 * True when the program writes the screen itself (reads ScrnBase) and takes its colour path only for a screen in NuBus slot
	 * space: its code compares an address with $F0000000 (cmpi.l #$F0000000,<ea>). Sierra's Macintosh AGI interpreter (Mixed-Up
	 * Mother Goose, Space Quest 1 16-colour) does that with screenBits.baseAddr; the screen memory here is in the heap, so it always
	 * writes 1-bit rows (80 bytes for 640 pixels) and a deeper screen shows them as stripes. Such programs get a 1-bit screen.
	 */
	boolean colorOnlyOnSlotScreen(){
		if(!readsScreenBase())return false;
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=4;i+5<code.length;i+=2){
				int word=((code[i]&0xff)<<8)|(code[i+1]&0xff);
				if((word&0xffc0)==0x0c80&&(code[i+2]&0xff)==0xf0&&code[i+3]==0&&code[i+4]==0&&code[i+5]==0)return true;
			}
		}
		return false;
	}
	/** True when the program's code uses Color QuickDraw but neither _Gestalt nor _QDExtensions (see {@link #screenDepthFor}). */
	boolean predatesDirectDevices(){
		boolean colorQuickDraw=false;
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=4;i+1<code.length;i+=2){
				int word=((code[i]&0xff)<<8)|(code[i+1]&0xff);
				if(word==0xab1d||(word&0xf9ff)==0xa1ad)return false; // _QDExtensions; _Gestalt $A1AD, _NewGestalt $A3AD, _ReplaceGestalt $A5AD
				if(word>=0xaa00&&word<=0xaaff)colorQuickDraw=true;
			}
		}
		return colorQuickDraw;
	}

	/**
	 * How far this program's clock runs behind the VM clock (Toolbox68k.ownClockShift). Sierra's Macintosh SCI interpreter
	 * (Quest for Glory I) turns Time into the time of day as (Time - $19BFCC00) / 43200 with a 16-bit DIVU, which overflows
	 * from May 2007 on: the 68000 leaves the dividend as it is, the time of day changes once in 18 hours, and every script
	 * timer counting seconds waits for ever - the class chosen at the character selection never went on to its sheet. Such a
	 * program gets today's date whole 28-year cycles back (the same weekdays and leap years), as far as the quotient needs.
	 * An explicit -Dhz68k.yearshift leaves the clock to it.
	 */
	private long ownClockShift(){
		String setting=System.getProperty("hz68k.yearshift");
		if(setting!=null&&!setting.trim().equalsIgnoreCase("auto")||!sierraTimeOfDay())return 0;
		long now=Toolbox68k.macNow(),shift=0;
		while(now+shift-SIERRA_TIME_BASE>=65536L*43200L)shift-=Toolbox68k.CLOCK_CYCLE_SECONDS;
		if(shift!=0)System.err.println("[68K "+name+"] clock set back "+(-shift/Toolbox68k.CLOCK_CYCLE_SECONDS*28)+" years: the program's time of day overflows a 16-bit divide after May 2007 (Sierra SCI)");
		return shift;
	}
	private static final long SIERRA_TIME_BASE=0x19BFCC00L;
	/** Whether a CODE resource holds Sierra's time-of-day code: SUBI.L #$19BFCC00,Dn then DIVU.W #43200,Dn. */
	private boolean sierraTimeOfDay(){
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=2;i+9<code.length;i+=2)
				if((code[i]&0xff)==0x04&&(code[i+1]&0xf8)==0x80&&(code[i+2]&0xff)==0x19&&(code[i+3]&0xff)==0xbf&&(code[i+4]&0xff)==0xcc&&code[i+5]==0
					&&(code[i+6]&0xf1)==0x80&&(code[i+7]&0xff)==0xfc&&(code[i+8]&0xff)==0xa8&&(code[i+9]&0xff)==0xc0)return true;
		}
		return false;
	}

	/** The partition the SIZE resource asks for (1 MB without one). */
	int preferredHeapSize(){
		ResourceFile68k.Resource size=resources.get(ResourceFile68k.fourcc("SIZE"),-1);
		if(size==null)size=resources.get(ResourceFile68k.fourcc("SIZE"),0);
		if(size==null||size.length()<10)return 1024*1024;
		return u4(size.data(),2);
	}

	/** Whether the program carries a SIZE resource: without one it predates MultiFinder and was written for machines with a few megabytes at most. */
	boolean hasSizeResource(){return resources.get(ResourceFile68k.fourcc("SIZE"),-1)!=null||resources.get(ResourceFile68k.fourcc("SIZE"),0)!=null;}
	/**
	 * Whether the program runs in a 24-bit world ({@link #setUp}): -Dhz68k.addr24=true/false decides, else a Microsoft p-code
	 * program ('PCOD' resources) without a SIZE resource does - written before MultiFinder (1987) for the 24-bit Memory
	 * Manager, it keeps flags in the top byte of its pointers (Word 1.0). So does one whose SIZE resource does not claim
	 * is32BitCompatible ($0080): Excel 1.01F asks NewPtr for $01699F22 bytes and never opened a window in the large world. The
	 * same goes for such a program that reads the 24-bit Memory Manager's block headers itself ({@link #readsBlockHeaders24}):
	 * Uninvited keeps flags in the top byte of the Handles its resource cache links together.
	 * Other programs without a SIZE resource stay in the large world: some size buffers from the screen (Fractal Contours
	 * asks for 14 MB on a wide display).
	 */
	/** THINK C 5 / THINK Pascal (owner resource 'KAHL' / 'PJMM'): their project managers read heap block headers themselves
	 *  (M68kMemory.blockTags). */
	/**
	 * MPW Shell or ToolServer (owner resource 'MPS ' / 'MPSX'), the hosts of MPW tools: the Shell checks a Handle's 24-bit block
	 * header before every DisposHandle (M68kMemory.blockHeaders), and its tools size themselves from ApplLimit - ApplZone, which
	 * is kept to a partition's size (Toolbox68k.initializeLowMemory).
	 */
	boolean mpwShell;
	private boolean isMpwShell(){
		return !resources.ofType(ResourceFile68k.fourcc("MPS ")).isEmpty()||!resources.ofType(ResourceFile68k.fourcc("MPSX")).isEmpty();
	}
	/**
	 * A Macintosh Common Lisp saved application (its 'CCL2' resource; Apple Dylan's 68K and PowerPC environments are built on
	 * MCL): the Lisp checks every Handle it is given the way the 32-bit Memory Manager lays it out - inside SysZone or ApplZone
	 * (below the zone's bkLim), the master pointer to a block whose 12-byte header says relocatable and whose relative handle
	 * plus the zone gives the Handle back (M68kMemory.blockHeaders, a 32-bit zone record). Without it Apple Dylan's FRED
	 * editor took every region Handle for a bad one: "Invalid selection region in #<FRED-RECORD>" at start-up.
	 */
	boolean mclHeap;
	private boolean isMcl(){return !resources.ofType(ResourceFile68k.fourcc("CCL2")).isEmpty();}
	/**
	 * True when the program's code reads a 24-bit block header itself: move.l Lo3Bytes,Dn; subq #8,An; and.l (An),Dn - the
	 * physical size of the block a master pointer points at (M68kMemory.blockHeaders24; Uninvited's resource cache).
	 */
	boolean readsBlockHeaders24(){
		if(hosted!=null||powerPC)return false;
		for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc("CODE"))){
			if(r.id==0)continue;
			byte[] code=r.data();
			for(int i=4;i+7<code.length;i+=2){
				int move=((code[i]&0xff)<<8)|(code[i+1]&0xff);
				if((move&0xf1ff)!=0x2038||(code[i+2]&0xff)!=0x03||(code[i+3]&0xff)!=0x1a)continue; // move.l $31A.w,Dn
				int subq=((code[i+4]&0xff)<<8)|(code[i+5]&0xff),and=((code[i+6]&0xff)<<8)|(code[i+7]&0xff);
				int dn=(move>>9)&7,an=subq&7;
				if((subq&0xfff8)!=0x5148&&(subq&0xfff8)!=0x5188)continue; // subq.w/subq.l #8,An
				if(and==(0xc090|dn<<9|an))return true; // and.l (An),Dn
			}
		}
		return false;
	}
	private boolean walksHeapHeaders(){
		return !resources.ofType(ResourceFile68k.fourcc("KAHL")).isEmpty()||!resources.ofType(ResourceFile68k.fourcc("PJMM")).isEmpty();
	}

	private boolean addressing24(){
		String forced=System.getProperty("hz68k.addr24");
		if(forced!=null)return Boolean.parseBoolean(forced);
		return !service&&hosted==null&&(!hasSizeResource()||(sizeFlags()&0x80)==0)&&(!resources.ofType(ResourceFile68k.fourcc("PCOD")).isEmpty()||readsBlockHeaders24());
	}

	/** SIZE resource flags word ($4000 acceptSuspendResumeEvents, $1000 canBackground, $0080 is32BitCompatible, $0040 isHighLevelEventAware...): also the low word of the Process Manager's processMode. */
	int sizeFlags(){
		ResourceFile68k.Resource size=resources.get(ResourceFile68k.fourcc("SIZE"),-1);
		if(size==null)size=resources.get(ResourceFile68k.fourcc("SIZE"),0);
		if(size==null||size.length()<2)return 0;
		return ((size.data()[0]&0xff)<<8)|(size.data()[1]&0xff);
	}

	private final java.util.concurrent.atomic.AtomicBoolean tornDown=new java.util.concurrent.atomic.AtomicBoolean();
	private void tearDown(){
		if(!tornDown.compareAndSet(false,true))return; // once: the emulation thread's own end, or a force quit that gave up waiting for it
		finished=true;
		VMTranslation.Translator t=translator;if(t!=null)t.closed=true; // no more texts of a program that is gone
		running.remove(this);
		if(ppc!=null)try{ppc.shutDown();}catch(RuntimeException e){System.err.println("[ppc "+name+"] at shut-down: "+e);}
		if(toolbox!=null)toolbox.shutDown();
		if(memory!=null)memory.releasePartition(); // in a launcher's address space: free for the next program it launches
		if(!service)tell(false,this);
		if(exitMessage!=null&&(service||relaunching||toolbox!=null&&toolbox.events.quitInProgress())){System.err.println("[68K "+name+"] "+exitMessage);return;} // a program being restarted at 8 bits, or one the user asked to quit (Mactella's threads fail while it closes), may fail on its way out: no dialog
		if(exitMessage!=null){
			System.err.println("["+(ppc!=null?"ppc ":"68K ")+name+"] "+exitMessage);
			final String hint=wideScreenHint();
			final String details=exitMessage+(cpu==null?"":String.format("%n(pc=$%06X, %d instructions)",cpu.pc,cpu.instructions));
			System.err.println("[68K "+name+"] "+details.replace('\n',' '));
			SwingUtilities.invokeLater(new Runnable(){@Override public void run(){showQuitAlert(name,hint,details);}});
		}
	}

	/**
	 * Tells the user a program quit unexpectedly, the way the Finder did: a plain sentence titled with the program's name,
	 * the technical reason (CPU error, missing Toolbox call) folded away under "Details" for whoever wants to report it.
	 */
	private static void showQuitAlert(String name,String hint,String details){
		javax.swing.JPanel panel=new javax.swing.JPanel();
		panel.setLayout(new javax.swing.BoxLayout(panel,javax.swing.BoxLayout.Y_AXIS));
		javax.swing.JLabel headline=new javax.swing.JLabel(String.format(dialogText("\"%s\" quit unexpectedly."),name));
		headline.setFont(headline.getFont().deriveFont(java.awt.Font.BOLD));
		panel.add(leftAligned(headline));
		panel.add(javax.swing.Box.createVerticalStrut(6));
		panel.add(leftAligned(wrappedLabel(dialogText("Other programs and your stacks are not affected. Opening it again may work.")+(hint.isEmpty()?"":"\n\n"+hint))));
		panel.add(javax.swing.Box.createVerticalStrut(8));
		final javax.swing.JTextArea text=new javax.swing.JTextArea(details);
		text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);
		text.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.PLAIN,11));
		text.setForeground(java.awt.Color.GRAY);
		final javax.swing.JScrollPane scroll=new javax.swing.JScrollPane(text);
		scroll.setPreferredSize(new java.awt.Dimension(380,80));
		scroll.setVisible(false);
		final String show="\u25b8 "+dialogText("Details"),hide="\u25be "+dialogText("Details");
		final javax.swing.JButton toggle=new javax.swing.JButton(show);
		toggle.setBorderPainted(false);toggle.setContentAreaFilled(false);toggle.setFocusPainted(false);
		toggle.setMargin(new java.awt.Insets(0,0,0,0));
		toggle.setFont(toggle.getFont().deriveFont(toggle.getFont().getSize2D()-1f));
		toggle.setForeground(java.awt.Color.GRAY);
		toggle.addActionListener(new java.awt.event.ActionListener(){@Override public void actionPerformed(java.awt.event.ActionEvent e){
			scroll.setVisible(!scroll.isVisible());toggle.setText(scroll.isVisible()?hide:show);
			java.awt.Window w=SwingUtilities.getWindowAncestor(toggle);if(w!=null)w.pack();
		}});
		panel.add(leftAligned(toggle));
		panel.add(leftAligned(scroll));
		JOptionPane.showMessageDialog(null,panel,name,JOptionPane.INFORMATION_MESSAGE); // no warning sign: the details are there for whoever wants them
	}
	private static String dialogText(String english){return PCARD.pc!=null&&PCARD.pc.intl!=null?PCARD.pc.intl.getDialogText(english):english;}
	private static javax.swing.JComponent leftAligned(javax.swing.JComponent c){c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);return c;}
	/** A label that wraps at about 320 pixels. */
	private static javax.swing.JLabel wrappedLabel(String text){
		String html=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\n","<br>");
		return new javax.swing.JLabel("<html><div style='width:320px'>"+html+"</div></html>");
	}

	/** The program stopped on a CPU error (a wild jump, an unknown instruction), not on a Toolbox call it could not make. */
	private boolean cpuCrash;
	/**
	 * A hint after a CPU error on a screen wider than any classic Mac had: programs of the time size their blitters for the
	 * monitors they knew - MegaPong's unrolled row copy stops at 1200 pixels and jumped off its end on a 1920-pixel screen,
	 * but runs on a 1024x768 or 640x480 screen chosen in the program's own settings.
	 */
	private String wideScreenHint(){
		if(!cpuCrash||host==null||host.screenRect==null||screenSizeOverride!=null||MacAppHost.virtualScreen!=null)return "";
		if(host.screenWidth<=1280)return "";
		return String.format(dialogText("The screen (%d pixels wide) is wider than any Mac of the time, which may be the cause. Setting this program's resolution to 640×480 or 1024×768 in its settings in the launcher may make it work."),host.screenWidth);
	}

	/** Requests termination from another thread (Quit menu of the host, HyperZebra shutdown). */
	void kill(){if(cpu!=null)cpu.requestAbort();}

	/**
	 * The launcher's "Force Quit": stops the program without asking it (its Quit handler does not run, nothing is saved).
	 * The emulation stops at its next instruction or wait; a program still running a second later (stuck in a host call)
	 * has its thread interrupted, and one still there after three seconds is taken off the running list and its windows
	 * closed, the thread left to end on its own.
	 */
	void forceQuit(){
		if(finished)return;
		System.err.println("[68K "+name+"] force quit");
		kill();
		Thread watchdog=new Thread(new Runnable(){@Override public void run(){
			try{
				for(int waited=0;waited<3000&&!finished;waited+=50){
					Thread.sleep(50);
					if(waited==1000&&!finished&&thread!=null)thread.interrupt();
				}
			}catch(InterruptedException e){return;}
			if(finished)return;
			System.err.println("[68K "+name+"] did not stop after a force quit; its windows are closed and it is left behind");
			tearDown();
		}},"68K force quit: "+name);
		watchdog.setDaemon(true);watchdog.start();
	}

	/** Asks this program to quit the way choosing its Quit menu item does (it gets to save its preferences). */
	void requestQuit(){if(toolbox!=null&&!finished)toolbox.hostListener().postQuit();}

	/** The running program one of whose host windows {@code w} is (or is owned by), or null. */
	static MacApp68k applicationOwning(java.awt.Window w){
		if(w==null)return null;
		for(MacApp68k app:runningApplications())if(!app.finished&&app.host!=null&&app.host.ownsWindow(w))return app;
		return null;
	}

	/**
	 * Cmd-Q typed while a hosted program's window is in front: as on a Mac it belongs to the frontmost application, not
	 * to HyperZebra. macOS answers the keystroke with the application menu's "Quit HyperZebra" before the window's key
	 * listener sees it, so HyperZebra's quit handler passes it here; true when the program took it (the handler then
	 * cancels the quit). The Command key is still down when the handler runs - on the EDT, queued behind the key-down
	 * that triggered it and ahead of its release - whereas "Quit HyperZebra" chosen with the mouse, or a logout, has no
	 * key down and quits HyperZebra together with every program.
	 */
	static boolean forwardCommandQ(java.awt.Window active){
		MacApp68k app=applicationOwning(active);
		if(Mac68kApi.TRACE)System.err.println("[68K] Cmd-Q quit request: active window "+(active==null?"none":"\""+active.getName()+"\" "+active.getClass().getSimpleName()+" \""+(active instanceof java.awt.Frame?((java.awt.Frame)active).getTitle():active instanceof java.awt.Dialog?((java.awt.Dialog)active).getTitle():"")+"\"")+(app==null?" is not a program's":" belongs to "+app.name+(app.host.isKeyDown(55)?"":" (Command key not down)"))+(MacAppHost.commandQJustDelivered()?", the keystroke already went to a program":""));
		// A frame without a JMenuBar also passes the keystroke to its canvas: the program already has it (ウルティメイト・スペル
		// reads Cmd-Q itself and may have quit before this handler runs, leaving no program window in front).
		if(MacAppHost.commandQJustDelivered())return true;
		if(app==null||!app.host.isKeyDown(55))return false; // 55: the Command key
		app.host.postCommandQ();
		return true;
	}

	/** Best-effort flush of the resource files running programs have changed when the JVM is terminated from outside
	 * (SIGTERM, a harness kill, logout): some programs create a preferences file at first launch (FSpCreateResFile: an empty
	 * map on disk) and only writes the settings resource at quit - killed before that, the next launch read the empty
	 * file, dereferenced the NIL 'sETT' handle and divided by the zero it found. QuitSequence handles HyperZebra's own quit. */
	private static boolean flushHookInstalled;
	private static synchronized void installFlushHook(){
		if(flushHookInstalled)return;flushHookInstalled=true;
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){@Override public void run(){
			for(MacApp68k app:runningApplications())if(!app.finished&&app.toolbox!=null)try{app.toolbox.resources.flushAll();}catch(Exception ignored){}
		}},"68K resource flush"));
	}

	/**
	 * The screen the program sees changes size while it runs - a game switching the display mode (DrawSprocket), as it did on a
	 * real Macintosh: every program has a screen of its own here, so only this one changes. {@code size} is "WIDTHxHEIGHT", or the
	 * program's former setting (null: none of its own) to go back. Called on the program's thread. The main device, screenBits,
	 * GrayRgn and the Window Manager ports describe the new screen and the host places its windows again. Returns false, and
	 * changes nothing, when the screen cannot change (a fixed {@code -Dhz68k.screen}, no host UI).
	 */
	boolean changeScreenSize(String size) throws M68kCpuException {
		Toolbox68k tb=toolbox;
		if(tb==null||!tb.hosted()||tb.windows==null||MacAppHost.screenSizeFixed())return false;
		if(java.util.Objects.equals(size,screenSizeOverride))return true;
		if(baseScreen==null){java.awt.Rectangle now=MacAppHost.screenBounds();baseScreen=new int[]{now.width,now.height};baseScreenSetting=screenSizeOverride;}
		screenSizeOverride=size;
		tb.api.quickDraw().screenResized();
		tb.windows.screenResized();
		tb.host().screenResized(this,MacAppHost.screenBounds());
		return true;
	}

	/** The screen as it was before the first switch of its size (null: not switched yet) and the setting that gave it. */
	private int[] baseScreen;private String baseScreenSetting;
	/** The size {width, height} of the program's own full screen, from which the display modes it can switch to are made. Called on the program's thread. */
	int[] fullScreen(){
		if(baseScreen!=null)return baseScreen.clone();
		java.awt.Rectangle now=MacAppHost.screenBounds();return new int[]{now.width,now.height};
	}
	/** Switches the screen to {@code width} x {@code height} (the display mode a game asked the Display Manager for); the full screen's size brings back the setting it had. */
	boolean setScreenMode(int width,int height) throws M68kCpuException {
		int[] full=fullScreen();
		if(width==full[0]&&height==full[1])return baseScreen==null||changeScreenSize(baseScreenSetting);
		return changeScreenSize(width+"x"+height);
	}

	/** HyperZebra's quit stopped: a quit request the program has not taken yet is withdrawn. */
	void withdrawQuit(){Toolbox68k tb=toolbox;if(tb!=null&&tb.events!=null)tb.events.withdrawQuit();}
	/** How HyperZebra's own Quit is getting on with this program (QuitSequence waits for it; nothing is killed on a timer). */
	EventManager68k.QuitProgress quitProgress(){Toolbox68k tb=toolbox;return tb==null||tb.events==null?EventManager68k.QuitProgress.QUITTING:tb.events.quitProgress();}

	/** Opens documents in this running application (host drop / open-with). */
	void openDocuments(List<File> files){
		if(toolbox==null)return;
		for(File d:files)Sandbox.chosenByUser(name,d,false); // the user handed them to this program: its to change (or to read, opened read-only)
		toolbox.hostListener().postOpenDocuments(files);
		if(LauncherStore.enabled)for(File d:files)LauncherStore.get().recordDocument(file,d);
	}
	/** Whether a running instance takes documents (Open Documents events): only high-level-event-aware programs (SIZE flag 0x0040). */
	boolean acceptsDocumentsWhileRunning(){return (sizeFlags()&0x0040)!=0;}
	/** A document the user opened inside the program (its Open dialog): the launcher lists it under the program. */
	void recordDocument(File document){if(LauncherStore.enabled&&!service)LauncherStore.get().recordDocument(file,document);}
	/** Raises every window of this program (the launcher's "Bring to front"). */
	void bringToFront(){if(host!=null&&!finished)host.bringToFront();}
	/** HyperZebra's own expanders (ExtractorApps.shippedExpander) and the installers it runs without the user: unlimited
	 *  unless the program has a speed of its own. Nobody watches them; a slow CPU only makes the user wait for the files. */
	boolean unpaced;
	/** The effective CPU speed: the program's own setting, else the VM preference (Toolbox68k.mips). */
	/**
	 * The instruction rate this program is paced to (0 = unlimited): its own setting, else the shared one ({@link #unpaced}:
	 * unlimited). A PowerPC program counts PowerPC instructions and its "real machine" is a G3 iMac (PpcRuntime.REAL_MIPS)
	 * instead of a 68040; the shared setting's rate is scaled between the two.
	 */
	double mips(){
		Double m=mipsOverride;if(m!=null)return m;
		Boolean b=realSpeedOverride;double real=powerPC?PpcRuntime.REAL_MIPS:Toolbox68k.DEFAULT_MIPS;
		if(b!=null)return b?real:0;
		if(unpaced)return 0;
		double vm=Toolbox68k.mips;return !powerPC||vm<=0?vm:vm*PpcRuntime.REAL_MIPS/Toolbox68k.DEFAULT_MIPS;
	}
	/** The "Classic Macintosh (68000)" per-program speed: about a 68000 at 8 MHz. */
	static final int CLASSIC_MIPS=2;

	static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|(b[o+3]&0xff);}
	static int u2(byte[] b,int o){return (b[o]&0xff)<<8|(b[o+1]&0xff);}

	@Override public String toString(){return name+" (68K)";}
}

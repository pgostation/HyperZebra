import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * System extension loading for hosted applications: files placed in the
 * virtual System Folder's "Extensions" folder are opened at startup, their
 * 'thng' components registered and their 'INIT' code executed inside the
 * process (like Wine loading a DLL into each program). Extensions that patch
 * traps use SetTrapAddress, which the CPU honours.
 *
 * The scan follows System 7's order: Extensions, then Control Panels, then the
 * System Folder itself (where System 6 kept its INITs). A 'sysz' request needs
 * no reservation here: the system and application zones are one growable heap.
 */
final class Extensions68k {
	/** Apple's installed QuickTime extension (and its companions) run instead of the native Movie Toolbox whenever the System
	 *  Folder holds it; -Dhz68k.quicktime=native keeps the native one in charge (and =extension is the old explicit opt-in). */
	static final boolean QUICKTIME_EXTENSION=!"native".equalsIgnoreCase(System.getProperty("hz68k.quicktime",""));
	private final Toolbox68k tb;
	private boolean loaded;private volatile boolean finished;
	/** Whether the extensions' INITs have all run (they are system software: see Toolbox68k.seesPowerPC). */
	boolean finished(){return finished;}
	/**
	 * Control panels' INITs at start-up: off unless {@code -Dhz68k.cdevinits=true}. On a Mac they ran once at boot for the
	 * whole machine; here every program would start its own copy - After Dark's engine would blank each program's screen and
	 * Macintosh Easy Open would patch every Standard File dialog - so a panel's INIT runs when the panel itself is opened
	 * ({@link #loadControlPanel}) unless asked for.
	 */
	static final boolean CONTROL_PANEL_INITS=Boolean.getBoolean("hz68k.cdevinits");
	/** True once every extension's INIT has run (or been skipped). */
	boolean loaded(){return finished;}

	Extensions68k(Toolbox68k tb){this.tb=tb;}

	/** Loads extensions once; failures are logged and skipped so the application still starts. */
	void loadAll(){
		if(loaded)return;loaded=true;
		try{
			loadFolder(new File(tb.files.systemFolder,"Extensions"),false);
			if(CONTROL_PANEL_INITS)loadFolder(new File(tb.files.systemFolder,"Control Panels"),true);
			loadFolder(tb.files.systemFolder,true);
		}finally{finished=true;}
		// QuickDraw 3D (and its Viewer) served natively answer Gestalt as the skipped INIT would have: 68K programs such as
		// SimpleText check 'q3vc' at start-up, before any PowerPC code has run, and call the Viewer through the CFM
		if(PpcQuickDraw3D.NATIVE&&tb.seesPowerPC()){
			tb.gestaltValues.putIfAbsent(0x71643364,1);                      // qd3d: gestaltQD3DPresent
			tb.gestaltValues.putIfAbsent(0x71337620,PpcQuickDraw3D.RELEASE); // 'q3v ': gestaltQD3DVersion
			tb.gestaltValues.putIfAbsent(0x71337663,1);                      // 'q3vc': gestaltQD3DViewer
		}
	}
	/** Loads a folder's extensions in name order. {@code typed}: only files the Finder types as extensions ('INIT', 'appe', 'thng', 'scri')
	 *  or control panels with INIT code - the System Folder also holds the System, the Finder, fonts and preferences. Otherwise
	 *  untyped files load too (copies without Finder info), but files typed as something else stay closed as at boot on a Mac:
	 *  Kaleidoscope Extension is an 'APPL' whose own WIND/wctb 128 sat in every program's resource chain, and Ultimate Spell's
	 *  GetNewCWindow(128) took that wctb's black content colour, so its transparent CopyBits no longer dropped the white. */
	private void loadFolder(File folder,boolean typed){
		File[] files=folder.listFiles();
		if(files==null)return;
		Arrays.sort(files,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
		for(File file:files){
			if(!file.isFile()||file.getName().startsWith("."))continue;
			if(!loadsAtStartup(file,typed))continue;
			if(tb.app!=null&&file.getAbsoluteFile().equals(tb.app.file.getAbsoluteFile()))continue; // a control panel opened as a program runs its INIT itself
			// QuickTime and the extensions that depend on it stay out while the native Movie Toolbox is in charge (their INITs
			// would only find "QuickTime already installed" and show their crossed-out icons).
			if(!tb.guestQuickTime&&isQuickTimeFamily(file.getName()))continue;
			// QuickDraw 3D's own INIT stays out while this library serves it (PpcQuickDraw3D): it would register the installed version
			if(PpcQuickDraw3D.NATIVE&&isQuickDraw3D(file))continue;
			if(isOleServer(file))continue;
			try{load(file);}
			catch(Exception e){System.err.println("[68K "+owner()+"] extension "+file.getName()+" failed: "+e.getMessage());
				int pc=e instanceof M68kCpuException?((M68kCpuException)e).pc:tb.cpu.pc;
				if(Mac68kApi.TRACE)System.err.println(tb.cpu.dumpState(pc));
				if(tb.app!=null)tb.app.crashDump(pc);}
		}
	}
	private String owner(){return tb.app!=null?tb.app.name:tb.name;}

	/** Extension creators an external's VM loads when the external first asks for one, by file (an external's VM runs no INITs at its start). */
	private final java.util.Set<Integer> loadedForExternals=new java.util.HashSet<Integer>();
	/**
	 * An external's VM loads no extensions when it starts - most externals only handle text - but one that asks for a manager an
	 * extension provides gets it then: the Speech Manager (creator 'ttsc') at the first Gestalt('ttsc') or call of its _SoundDispatch
	 * group (Rinaldi's SayText and VoicesList said "Speech Mgr must be installed"). Loads the Extensions folder's INIT file with that
	 * creator, once; false when there is none.
	 */
	boolean loadForExternal(int creator){
		if(tb.app!=null||!loadedForExternals.add(creator))return false;
		File[] files=new File(tb.files.systemFolder,"Extensions").listFiles();if(files==null)return false;
		Arrays.sort(files,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
		for(File file:files){
			if(!file.isFile()||file.getName().startsWith("."))continue;
			byte[] info=FinderInfo68k.read(file);
			if(info==null||MacApp68k.u4(info,0)!=0x494e4954||MacApp68k.u4(info,4)!=creator)continue; // 'INIT' of that creator
			try{load(file);return true;}
			catch(Exception e){System.err.println("[68K "+owner()+"] extension "+file.getName()+" failed: "+e.getMessage());return false;}
		}
		return false;
	}

	/**
	 * Microsoft OLE Extension (type 'INIT', creator 'Ole2'): its INIT leaves a block in the system heap behind Gestalt 'OLE2' that
	 * the extension's own background application fills in once it runs, and OLE's clients call through it. Processes here share
	 * no memory and that application never runs, so the block stays empty and PageMaker 6 jumped to NIL at start-up. Without
	 * the INIT the Microsoft OLE Library works within the program (over the PPC Toolbox calls), as it does when the extension is off.
	 */
	static boolean isOleServer(File file){byte[] info=FinderInfo68k.read(file);return info!=null&&info[0]=='I'&&info[1]=='N'&&info[2]=='I'&&info[3]=='T'&&info[4]=='O'&&info[5]=='l'&&info[6]=='e'&&info[7]=='2';}

	/** The QuickDraw 3D extension (type 'INIT', creator 'QD3D'). */
	private static boolean isQuickDraw3D(File file){byte[] info=FinderInfo68k.read(file);return info!=null&&info[0]=='I'&&info[1]=='N'&&info[2]=='I'&&info[3]=='T'&&info[4]=='Q'&&info[5]=='D'&&info[6]=='3'&&info[7]=='D';}

	private void load(File file) throws Exception {
		int ref=tb.resources.openFileNoChain(file);
		if(ref<0)return;
		tb.resources.pin(ref); // stays open: components registered from it load their code on demand
		int saved=tb.cpu.a[7];
		java.util.List<ResourceFile68k.Resource> inits=initsOf(ref);
		// The Component Manager registers the components of files of type 'thng' by itself; an INIT file (QuickTime, Sound
		// Manager) registers its own with RegisterComponentResourceFile, deciding which ones fit the machine.
		byte[] info=FinderInfo68k.read(file);
		boolean thngFile=info!=null&&info[0]=='t'&&info[1]=='h'&&info[2]=='n'&&info[3]=='g';
		if(thngFile||inits.isEmpty())for(ResourceFile68k.Resource r:new java.util.ArrayList<ResourceFile68k.Resource>(componentsOf(ref)))try{int h=tb.resources.handleFor(r);if(h!=0)tb.components.registerResource(h);}catch(Exception e){}
		tb.resources.initsStarting(ref);
		try{runInits(ref,file,inits,saved);}
		finally{tb.resources.initsDone(ref);} // INIT 31 closes the file: programs no longer see its resources
	}

	/**
	 * A control panel's own INIT (After Dark's engine, which its cdev then finds installed) runs in the control panel's
	 * process before the panel opens, as it ran at boot on a real Mac; the file is already open as the process's resource file.
	 */
	void loadControlPanel(File file){
		try{runInits(tb.resources.applicationRefNum(),file,initsOf(tb.resources.applicationRefNum()),tb.cpu.a[7]);}
		catch(Exception e){System.err.println("[68K "+owner()+"] INIT of "+file.getName()+" failed: "+e.getMessage());
			int pc=e instanceof M68kCpuException?((M68kCpuException)e).pc:tb.cpu.pc;
			if(Mac68kApi.TRACE)System.err.println(tb.cpu.dumpState(pc));}
	}

	private void runInits(int ref,File file,java.util.List<ResourceFile68k.Resource> inits,int saved) throws Exception {
		int savedCurrent=tb.resources.currentRefNum();
		for(ResourceFile68k.Resource r:inits){
			int h=tb.resources.handleFor(r);if(h==0)continue;
			tb.api.setHandleState(h,tb.api.handleState(h)|0x80);
			int entry=tb.memory.readLong(h);
			System.out.println("[68K "+owner()+"] running INIT "+r.id+" of "+file.getName());
			tb.cpu.setInstructionLimit(Long.MAX_VALUE);
			tb.resources.useResFile(ref); // an INIT runs with its own file as the current resource file
			// At INIT time a real Mac has not set CurApName ($910, 32 bytes) yet: the bytes are still the boot code's $FF fill.
			// Apple's linked-patch INITs (Sound Manager 3.x, Speech Manager) rely on that: they use $918-$927 as an engineer's debug
			// protocol (a hook routine at $918 unless negative, a patch index and a break range at $920-$925) and jump through
			// whatever an application name put there otherwise. The length byte is $FF too: QuicKeys' engine driver opens only
			// when it finds that (start-up time) and answered openErr otherwise, leaving its control panel all dimmed.
			byte[] curApName=new byte[32];System.arraycopy(tb.memory.raw(),0x910,curApName,0,32);
			tb.memory.fill(0x910,32,0xff);
			try{tb.callRoutine(entry);}finally{tb.cpu.a[7]=saved;tb.resources.useResFile(savedCurrent);tb.memory.load(0x910,curApName);}
		}
	}

	// ------------------------------------------------------------------ installing (files dropped on HyperZebra)

	/** File types the Finder files under Extensions: system extensions, background programs, components, script systems. */
	private static final int[] EXTENSION_TYPES={0x494e4954,0x61707065,0x74686e67,0x73637269}; // INIT appe thng scri
	/** Control panels ('cdev') go to the Control Panels folder; their INIT code is not run here, but a program can still open them. */
	private static final int CDEV=0x63646576;

	/** The virtual System Folder's Extensions folder (the one {@link #loadFolder} reads). */
	static File folder(){return new File(QuickTimeService.systemFolder(),"Extensions");}

	/**
	 * Whether a host file is a system extension document: decided like the Finder by file type ('INIT', 'appe', 'thng',
	 * 'scri'), and for a file without Finder info by its contents - 'INIT' code resources with no 'CODE' segments (a
	 * program with an INIT is still a program). A control panel ('cdev') counts too: it is filed under Control Panels.
	 */
	static boolean isExtensionFile(File file){
		if(file==null||!file.isFile())return false;
		int fileType=FinderInfo68k.type(file);
		if(fileType==CDEV)return true;
		for(int t:EXTENSION_TYPES)if(t==fileType)return true;
		if(fileType!=0)return false;
		try{ResourceFile68k rf=ResourceFile68k.open(file);
			return !rf.ofType(ResourceFile68k.fourcc("INIT")).isEmpty()&&rf.ofType(ResourceFile68k.fourcc("CODE")).isEmpty();
		}catch(IOException e){return false;}
	}

	/**
	 * Copies an extension into the System Folder (Extensions, or Control Panels for a 'cdev'): data fork, then the
	 * resource fork the way this platform stores forks; MacBinary containers are copied whole. Returns the installed
	 * file. Programs launched from now on load it; the ones already running do not.
	 */
	static File install(File source) throws IOException {return install(source,QuickTimeService.systemFolder());}
	static File install(File source,File systemFolder) throws IOException {
		return copyInto(source,new File(systemFolder,FinderInfo68k.type(source)==CDEV?"Control Panels":"Extensions"));
	}
	/** Copies a Mac file (data fork, resource fork, Finder info) into {@code folder}, made if missing; the copy keeps the name. */
	static File copyInto(File source,File folder) throws IOException {
		folder.mkdirs();
		File target=new File(folder,source.getName());
		if(target.getAbsoluteFile().equals(source.getAbsoluteFile()))return target;
		ResourceFile68k probe=new ResourceFile68k(source);byte[] fork=ResourceFile68k.readForkBytes(source,probe);
		// empty counts as none: where there are no native forks (Windows, Linux) the file type lives in an AppleDouble "._" sidecar
		// with only Finder info, whose resource fork entry is empty
		if(fork==null||fork.length==0)throw new IOException("no resource fork: "+source.getName());
		java.nio.file.Files.copy(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		if(probe.storage!=ResourceFile68k.Storage.MACBINARY){
			ResourceFile68k.writeForkBytes(target,fork,ResourceFile68k.Storage.NONE);
			byte[] info=FinderInfo68k.read(source);if(info!=null)FinderInfo68k.write(target,info);
		}
		return target;
	}

	/** QuickTime\u2122, QuickTime\u2122 VR, QuickTime\u2122 Musical Instruments, QuickTime PowerPlug...: files named after QuickTime. */
	static boolean isQuickTimeFamily(String name){return name.startsWith("QuickTime");}

	/** Whether the System Folder's Extensions folder holds Apple's "QuickTime\u2122" extension (the name's trademark sign may have been decoded either way). */
	static boolean hasQuickTimeExtension(File systemFolder){
		File[] files=new File(systemFolder,"Extensions").listFiles();
		if(files==null)return false;
		// hidden files are not loaded (loadFolder): an AppleDouble "._QuickTime™" is another file's sidecar, not the extension (one left alone in Extensions made every
		// program wait for a QuickTime that never loaded: "the installed QuickTime extension did not install the trap")
		for(File f:files){if(!f.isFile()||f.getName().startsWith("."))continue;String n=f.getName().replaceAll("[^A-Za-z]","");if(n.equals("QuickTime")&&(f.length()>0||ResourceFile68k.nativeForkLength(f)>0||ResourceFile68k.AppleDouble.findSidecar(f)!=null)&&quickTimeMajorVersion(f)<5)return true;}
		return false;
	}
	/**
	 * The major version in the extension's 'vers' 1 (0 when unreadable). QuickTime 5 and later need a PowerPC: QuickTime 6's
	 * INIT stops in its PowerPC part (QTPixMapExtensionPatch), so with it installed the native Movie Toolbox stays in charge
	 * and its family is skipped, as when no QuickTime is installed.
	 */
	private static int quickTimeMajorVersion(File f){
		String key=f.getAbsolutePath()+"\u0000"+f.lastModified();
		synchronized(QUICKTIME_VERSIONS){Integer v=QUICKTIME_VERSIONS.get(key);if(v!=null)return v;}
		int major=0;
		try{ResourceFile68k.Resource r=ResourceFile68k.open(f).get(ResourceFile68k.fourcc("vers"),1);byte[] d=r==null?null:r.data();
			if(d!=null&&d.length>0)major=(d[0]>>4&15)*10+(d[0]&15);} // BCD
		catch(IOException|RuntimeException ignored){}
		synchronized(QUICKTIME_VERSIONS){QUICKTIME_VERSIONS.put(key,major);}
		return major;
	}
	private static final java.util.Map<String,Integer> QUICKTIME_VERSIONS=new java.util.HashMap<String,Integer>();

	/** Whether {@link #loadFolder} opens {@code file}: a start-up file, or in the Extensions folder ({@code typed} false) any file without Finder info. */
	static boolean loadsAtStartup(File file,boolean typed){return !typed&&FinderInfo68k.type(file)==0||startupFile(file);}
	/** Outside Extensions, a file loads at start-up when its type is an extension's, or it is a control panel carrying 'INIT' code. */
	static boolean startupFile(File file){
		int fileType=FinderInfo68k.type(file);
		for(int t:EXTENSION_TYPES)if(t==fileType)return true;
		if(fileType!=CDEV)return false;
		try{return !ResourceFile68k.open(file).ofType(ResourceFile68k.fourcc("INIT")).isEmpty();}catch(IOException e){return false;}
	}

	private java.util.List<ResourceFile68k.Resource> componentsOf(int ref){return tb.resources.resourcesOfFile(ref,ResourceFile68k.fourcc("thng"));}
	private java.util.List<ResourceFile68k.Resource> initsOf(int ref){return tb.resources.resourcesOfFile(ref,ResourceFile68k.fourcc("INIT"));}
}

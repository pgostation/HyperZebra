import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The classic Macintosh OS/Toolbox, shared by HyperCard externals and hosted
 * applications: one implementation of every manager, with the environment
 * (resources, files, host windows) supplied by the process it serves.
 *
 * It owns the Segment Loader, low-memory globals, trap patching, the Process,
 * Time, Vertical Retrace and Notification Managers, Gestalt, utilities and
 * packages, and delegates windows, events, menus, controls, dialogs, TextEdit,
 * resources, files, Apple events, networking, QuickTime and components to the
 * dedicated {@code *68k} manager classes. {@link Mac68kApi} keeps only the
 * Memory Manager and register-based utilities and forwards everything else here.
 *
 * Three environments exist: an application ({@code app != null}: its own
 * resource fork, the host volumes, native windows), an external of an open
 * stack ({@code stack != null}: the stack's resources, the stack directory as
 * the only volume, native windows) and a headless VM (tests: no host UI,
 * nonblocking events, ModalDialog returns at once).
 */
final class Toolbox68k implements M68kCpu.RomHandler,M68kCpu.Scheduler {
	static final int NO_ERR=0,PARAM_ERR=-50,MEM_FULL_ERR=-108,NIL_HANDLE_ERR=-109,RES_NOT_FOUND=-192,UNIMPL_CORE_ROUTINE=-4,GESTALT_UNDEF=-5551,GESTALT_UNKNOWN=-5550,GESTALT_DUP=-5552;
	/** System 7.5.5: what HyperZebra's own HyperCard reports ("the systemVersion") and the XCMDs of its stacks see. */
	static final int SYSTEM_VERSION=0x0755;
	static final String SYSTEM_VERSION_STRING="7.5.5";
	/** Mac OS 9.2.2, the last classic system: what programs (68K and PowerPC) see as standard - the Power Macintosh they run on
	 *  (seesPowerPC) ran it, and installers of PowerPC software ask for it (VISE's Install iTunes 2: "requires System 9.0.0"). */
	static final int MAC_OS_9_VERSION=0x0922;
	/** The choices of the launcher's per-program System version (GAppSettingsDialog). */
	static final int[] SYSTEM_VERSION_CHOICES={MAC_OS_9_VERSION,SYSTEM_VERSION};
	static String systemVersionName(int v){return (v>=0x0800?"Mac OS ":"System ")+((v>>8)&0xff)+"."+((v>>4)&0xf)+"."+(v&0xf);}
	/** The System version a program sees: the launcher's choice for it, else Mac OS 9.2.2 (-Dhz68k.sysv=0755 changes the standard);
	 *  stack externals (no program) see HyperZebra's System 7.5.5. */
	static int systemVersionFor(MacApp68k app){
		if(app==null)return SYSTEM_VERSION;
		if(app.systemVersionOverride!=null)return app.systemVersionOverride;
		String p=System.getProperty("hz68k.sysv");
		if(p!=null)try{return Integer.parseInt(p,16);}catch(NumberFormatException e){System.err.println("hz68k.sysv: not a hex version: "+p);}
		return MAC_OS_9_VERSION;
	}
	/** This VM's System version (see systemVersionFor). */
	final int baseSystemVersion;
	static final long MAC_EPOCH_DELTA=2_082_844_800L;

	/** Thrown by _ExitToShell to unwind the CPU run loop. */
	static final class ExitToShell extends M68kException { private static final long serialVersionUID=1L; ExitToShell(){super("ExitToShell");} }

	/** A Java routine reachable from 68K code through a ROM gate address. */
	interface Gate { void call(M68kCpu cpu) throws M68kException; }

	/** The hosted application, or null when serving a HyperCard external. */
	final MacApp68k app;
	/** The current stack when serving an external (null for applications and headless tests). */
	OStack stack;
	/** HyperCard's windows as the external sees them (see {@link CardWindow68k}); the snapshots are taken at its first QuickDraw/Window Manager trap. */
	CardWindow68k cardWindow;
	/** HyperCard's window records are at their fixed place in this external's VM ({@link Xcmd68k#CARD_WINDOW_RECORD}). */
	boolean hyperCardWindowRecords;
	/** An external of an open stack starts with HyperCard's windows in place (thePort = the card window, WindowList). */
	void openCardWindow(M68kCpu cpu) throws M68kCpuException {
		if(app!=null||stack==null)return;
		if(cardWindow==null){attach(cpu);if(hosted())host().followStackZoom();cardWindow=CardWindow68k.open(this,stack);if(cardWindow!=null)windows.refreshLinks();} // WindowList: the external's own windows first, then HyperCard's
		// every call starts with thePort = the card window, whatever port the last external in this VM left set (Myst Preview:
		// the Movie XCMD left its movie window's port, and HTLock, which finds HyperTint by the card window's grafProcs, said
		// "Not Installed")
		else{attach(cpu);cardWindow.syncScreenPort();api.quickDraw().setPortTo(cpu,cardWindow.record);}
		if(resources!=null)resources.syncStacksInUse(); // start/stop using since the VM was made
		menus.externalCallStarts(); // HyperCard's menus as they are now, at the call's first Menu Manager trap
		api.soundManager().syncSdVolume(); // SdVolume as another external's VM may have set it (SetVolume XCMD sets, GetSoundVolume reads)
		openA5World(cpu);
	}
	private int a5World;
	/** Bytes above an external's A5: HyperCard J-2.3's own above-A5 size (CODE 0: 32 + its jump table). */
	static final int A5_WORLD_ABOVE=0x3bf8;
	/** The A5 world externals run with (0 until the first call; applications have their own). */
	int externalA5World(){return a5World;}
	/**
	 * An external runs with HyperCard's A5: (A5) points at the QuickDraw globals (thePort, then white/black/gray/ltGray/dkGray,
	 * arrow, screenBits, randSeed below it), which XCMDs read directly for patterns and the current port (some drawing externals
	 * reads (A5) and crashed with A5 = 0). A small A5 world is made once per VM and A5 set on every call. CurrentA5 ($904) names
	 * it too, as it names HyperCard's: Dartmouth's externals find screenBits through CurrentA5 to centre their dialogs and keep
	 * their windows on the screen, and read an empty rectangle (TDWindow's window went to -10,0).
	 */
	void openA5World(M68kCpu cpu) throws M68kCpuException {
		if(a5World==0){
			int globals=memory.allocate(336,2);memory.fill(globals,336,0);
			// above A5: HyperCard's 32 bytes of application parameters and the room of its jump table, zeroed. An external linked
			// with Apple's A5-relative library glue keeps that glue's stub records and globals there (GetHTTP's Open Transport
			// glue reaches A5+$1A2; with 64 bytes it read whatever block followed and jumped into it)
			a5World=memory.allocate(A5_WORLD_ABOVE,2);memory.fill(a5World,A5_WORLD_ABOVE,0);
			cpu.a[5]=a5World;
			api.quickDraw().initializeGlobals(cpu,globals+224); // thePort at +224, the other globals (206 bytes with QuickDraw's privates) below it
		}
		cpu.a[5]=a5World;
		memory.writeLong(0x904,a5World); // CurrentA5
	}
	/** An external whose VM lives on (an XWindoid) is called or gets an event again: what it drew on the card is shown, and
	 *  HyperCard's windows are snapshotted afresh at its next drawing, since the card may have changed meanwhile. */
	void refreshCardWindow() throws M68kCpuException {
		if(cardWindow!=null)cardWindow.refresh();else if(cpu!=null)openCardWindow(cpu);
	}
	final M68kMemory memory;
	M68kCpu cpu;
	final Mac68kApi api;
	final StackTextEncoding encoding;
	/** Host UI; created lazily. Headless VMs (no application, no stack) get a no-op host. */
	private MacAppHost host;
	final String name;
	final EventManager68k events;
	final WindowManager68k windows;
	final MenuManager68k menus;
	final ControlManager68k controls;
	/** ControlDispatch $AA73 / AppearanceDispatch $AA74 (Mac OS 8 Appearance Manager). */
	final AppearanceManager68k appearance;
	final CursorDevices68k cursorDevices;
	/** Collection Manager, ColorSync, Translation Manager, AVL trees, DebugLib, Power Manager bits, absent services. */
	final MiscManagers68k managers;
	final TextEdit68k textEdit;
	final DialogManager68k dialogs;
	/** The Communications Toolbox (_CommToolboxDispatch). */
	final CommToolbox68k ctb;
	final ResourceManager68k resources;
	final HostQuickDraw68k quickDraw;
	/** The ".Wacom" tablet driver: the host's pen pressure for tablet-aware programs (Painter) */
	final WacomTablet68k wacom=new WacomTablet68k(this);
	final AppleEvents68k appleEvents;
	final StandardFile68k standardFile;
	final EditionManager68k editions;
	final FileSystem68k files;
	/** The classic Sound Driver (.Sound, refNum -4) behind StartSound/StopSound/SoundDone. */
	final SoundDriver68k soundDriver;
	final ListManager68k listManager;
	final MacTCP68k macTcp;
	final AppleTalk68k appleTalk;
	/** Open Transport, made when a program first uses it (the 68K glue's library manager stand-in is installed at launch). */
	private OpenTransport openTransport;
	synchronized OpenTransport openTransport(){if(openTransport==null)openTransport=new OpenTransport(this);return openTransport;}
	final DeviceManager68k devices;
	final DeskAccessories68k desk;
	final QuickTime68k quickTime;
	final ImageCompression68k imageCompression;
	/** True when a real QuickTime extension is installed in the virtual System Folder (the native Movie Toolbox is then disabled). */
	final boolean guestQuickTime;
	/** The installed QuickTime is a PowerPC one (QuickTime 5/6 with its PowerPlug): its components run as PowerPC code. */
	final boolean guestPowerPcQuickTime;
	final Components68k components;
	final Extensions68k extensions;
	final HelpManager68k help;
	final PrintManager68k print;
	final DragManager68k drag;
	final ThreadManager68k threads;
	final AdbManager68k adb;
	final TextServices68k textServices;
	private final List<Gate> gates=new ArrayList<Gate>();
	final Map<Integer,Integer> gestaltValues=new HashMap<Integer,Integer>();
	private final Map<Integer,Integer> gestaltProcs=new HashMap<Integer,Integer>();
	private final Map<Integer,Integer> loadedSegments=new HashMap<Integer,Integer>();
	private final Map<Integer,int[]> segmentEntries=new HashMap<Integer,int[]>();
	/** Resource Handle -> segment number of every loaded CODE segment. */
	private final Map<Integer,Integer> segmentHandles=new HashMap<Integer,Integer>();
	/** True when {@code handle} holds a loaded CODE segment; its jump-table entries still point into it. */
	boolean isLoadedSegment(int handle){return segmentHandles.containsKey(handle);}
	final List<TimerTask> timers=new ArrayList<TimerTask>();
	private final List<Integer> vblTasks=new ArrayList<Integer>();
	private final ConcurrentLinkedQueue<Runnable> deferred=new ConcurrentLinkedQueue<Runnable>();
	private long lastVblTick;
	private int notificationCount;
	private int growZoneProc;
	/** The grow-zone procedure SetGrowZone installed (0 = none): Carbon's GetGrowZone. */
	int growZoneProc(){return growZoneProc;}
	/** The Mac's "boot": TickCount and Microseconds count from here. A real Mac has been up for a while when a program starts,
	 *  so the clock starts 5 minutes to about 6 hours in (a different value every launch). From 0, Photoshop 3's scratch file
	 *  ("Photoshop Temp" + TickCount) got the same name at every launch and a copy left by a killed session stopped it with
	 *  "Could not initialize Photoshop because a file of the same name already exists".
	 *  An external runs in HyperCard's process, whose TickCount is `the ticks`: its VM counts from the same origin as
	 *  HyperTalk ({@link TUtil#currentTicks}), else LastBoot() + the ticks div 60 was hours off the seconds. */
	private final long startNanos;
	private boolean shutDown;
	private int unimplementedRomAddress;
	/** Offset in the ROM image of the entry the system's exception vectors point at: past the ROM header, in the first 64 KB. */
	static final int ROM_VECTOR_ENTRY=0x100;
	private final Map<Integer,Integer> tempHandles=new HashMap<Integer,Integer>();
	/**
	 * Code blocks ({start, end, 1 when its 68K routine does its work}) of the externals here with PowerPC code: 'fat' and
	 * 'safe fat' ones (PpcRuntime.powerPcFat) and those with no 68K half (HyperCard 2.3's native 'xcmd'/'xfcn'). The VM has
	 * Mixed Mode for them, as HyperCard on a Power Macintosh had, and their routine descriptor runs the PowerPC code in
	 * {@link #externalPpc}: through the JIT it is faster than their 68K code. Other externals see the 68K Mac.
	 * -Dhz.xcmd.ppc=false keeps a fat external's 68K routine (only the PowerPC-only ones run PowerPC code then).
	 */
	private final List<int[]> powerPcExternals=new ArrayList<int[]>();
	private static final boolean FAT_EXTERNALS_PPC=!"false".equals(System.getProperty("hz.xcmd.ppc"));
	private String powerPcExternalName;
	/** The PowerPC runtime of those externals, made at the first call into one. */
	private PpcRuntime externalPpc;
	/** Records an external loaded at {@code start} when it has PowerPC code to run. */
	void externalLoaded(int start,byte[] code,String name){
		if(app!=null)return;
		boolean only=PpcRuntime.powerPcOnlyFat(code);
		if(!only&&!(FAT_EXTERNALS_PPC&&PpcRuntime.powerPcFat(code)))return;
		powerPcExternals.add(new int[]{start,start+code.length,only?0:1});
		if(powerPcExternalName==null)powerPcExternalName=name;
		if(Mac68kApi.TRACE_XCMD)System.err.println("[xcmd] "+name+": "+(only?"PowerPC code only":"fat - its PowerPC code runs"));
	}
	/** The external block holding {@code at}, or null. */
	private int[] powerPcExternal(int at){for(int[] r:powerPcExternals)if(at>=r[0]&&at<r[1])return r;return null;}
	/** The PowerPC runtime for a routine descriptor at {@code rd} inside an external with PowerPC code, or null. */
	PpcRuntime powerPcExternalAt(M68kCpu cpu,int rd) throws M68kCpuException {
		if(powerPcExternal(rd)==null)return null;
		if(externalPpc==null)externalPpc=PpcRuntime.forExternal(this,cpu,powerPcExternalName,stack==null?null:stack.file);
		return externalPpc;
	}
	/** Whether the external holding {@code at} has a 68K routine that does its work (a fat one): run when its PowerPC code cannot be prepared. */
	boolean externalHas68kRoutine(int at){int[] r=powerPcExternal(at);return r!=null&&r[2]!=0;}

	static final class TimerTask {final int record;long dueNanos,scheduledNanos;boolean active,extended,microseconds;TimerTask(int record,boolean extended){this.record=record;this.extended=extended;}}

	Toolbox68k(MacApp68k app){this(app,app.api,null);}

	/** Toolbox for HyperCard externals: resources and files come from {@code stack} (null = headless). */
	Toolbox68k(Mac68kApi api,OStack stack){this(null,api,stack);}

	/**
	 * One refNum space for resource files and data/resource forks, as on a real Mac: every open fork is an entry of the
	 * file control block table (FCBSPtr, $34E - a length word then 94-byte FCBs) and its refNum is the entry's offset in
	 * that table, so the System file is 2, the next file 96... Programs that read the table directly (QuickTime's data
	 * handlers take the volume of an open file from fcbVPtr) find the fields the File Manager keeps there.
	 */
	static final int FCB_SIZE=94,FCB_COUNT=348,FCB_VPTR=20;
	private int fcbTable;
	private final java.util.BitSet fcbUsed=new java.util.BitSet();
	int allocateFileReference(){int i=fcbUsed.nextClearBit(0);if(i>=FCB_COUNT)i=FCB_COUNT-1;fcbUsed.set(i);return 2+i*FCB_SIZE;}
	void releaseFileReference(int ref){int i=(ref-2)/FCB_SIZE;if(ref<2||(ref-2)%FCB_SIZE!=0||i>=FCB_COUNT)return;fcbUsed.clear(i);try{if(fcbTable!=0)memory.fill(fcbTable+ref,FCB_SIZE,0);}catch(M68kCpuException ignored){}}
	/** Fills the FCB of an open fork (fcbFlNm, fcbFlags, fcbEOF/PLen, fcbVPtr, fcbDirID, fcbCName); a null file is the built-in System file. */
	void describeFileControlBlock(int ref,java.io.File file,boolean resourceFork,boolean writable,long eof){
		if(fcbTable==0||ref<2||(ref-2)%FCB_SIZE!=0||(ref-2)/FCB_SIZE>=FCB_COUNT)return;
		try{int fcb=fcbTable+ref;memory.fill(fcb,FCB_SIZE,0);
			// the built-in System is "System" in the virtual System Folder, as PBGetFCBInfo of SysMap says (with dirID 2 Alsoft's
			// List Open Files looked for it at the volume's root and listed "[unknown file]")
			java.io.File system=file==null&&files!=null?files.systemFile():null;
			if(system!=null&&system.exists())file=system;
			int fileNumber=file==null?2:files.fileManager.fileNumberFor(file);
			memory.writeLong(fcb,fileNumber);
			memory.writeWord(fcb+4,(resourceFork?0x0200:0)|(writable?0x0100:0));
			memory.writeLong(fcb+8,(int)Math.min(0x7fffffffL,eof));memory.writeLong(fcb+12,(int)Math.min(0x7fffffffL,eof));
			memory.writeLong(fcb+FCB_VPTR,files.volumeControlBlockFor(file));
			memory.writeLong(fcb+30,4096); // fcbClmpSize
			memory.writeLong(fcb+58,file==null?2:files.fileManager.parentDirectoryIdFor(file));
			writePascal(fcb+62,file==null?"System":files.fileManager.macNameOf(file),31);
		}catch(Exception ignored){}
	}
	/**
	 * _HFSUtilDispatch ($A824, FSM.h: InterfaceLib 8.5 and later) - the File System Manager's FCB utilities over the FCB table
	 * above: UTIndexFCB (4), UTResolveFCB (5), UTCheckWDRefNum/FileRefNum/VolRefNum ($13-$15). Code built with the Mac OS 9
	 * libraries resolves its open files' FCBs through them once the System is 8.5 or later (Good People, 何かon林檎).
	 */
	private boolean hfsUtilDispatch(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7];
		switch(cpu.d[0]&0xffff){
			case 5:{int out=memory.readLong(sp),ref=(short)memory.readWord(sp+4);cpu.a[7]+=6;boolean open=fcbOpen(ref);
				if(out!=0)memory.writeLong(out,open?fcbTable+ref:0);memory.writeWord(cpu.a[7],open?NO_ERR:-51);return true;} // rfNumErr
			case 4:{int out=memory.readLong(sp),refPtr=memory.readLong(sp+4);cpu.a[7]+=12;int ref=refPtr==0?0:(short)memory.readWord(refPtr);
				int i=ref<2?0:(ref-2)/FCB_SIZE+1;i=fcbUsed.nextSetBit(i);
				if(i<0||i>=FCB_COUNT||fcbTable==0){memory.writeWord(cpu.a[7],-43);return true;}
				int next=2+i*FCB_SIZE;if(refPtr!=0)memory.writeWord(refPtr,next);if(out!=0)memory.writeLong(out,fcbTable+next);memory.writeWord(cpu.a[7],NO_ERR);return true;}
			case 0x14:{int ref=(short)memory.readWord(sp);cpu.a[7]+=2;memory.writeWord(cpu.a[7],fcbOpen(ref)?NO_ERR:-51);return true;}
			case 0x13:case 0x15:cpu.a[7]+=2;memory.writeWord(cpu.a[7],NO_ERR);return true;
			default:throw new M68kMacApiException(cpu.pc,0xa824,"HFSUtilDispatch selector "+(cpu.d[0]&0xffff));
		}
	}
	private boolean fcbOpen(int ref){return fcbTable!=0&&ref>=2&&(ref-2)%FCB_SIZE==0&&(ref-2)/FCB_SIZE<FCB_COUNT&&fcbUsed.get((ref-2)/FCB_SIZE);}
	private void allocateFileControlBlocks(){
		try{fcbTable=memory.allocate(2+FCB_COUNT*FCB_SIZE,4);memory.fill(fcbTable,2+FCB_COUNT*FCB_SIZE,0);memory.writeWord(fcbTable,2+FCB_COUNT*FCB_SIZE);memory.writeLong(0x34e,fcbTable);}
		catch(M68kCpuException e){fcbTable=0;}
	}

	/** Script of the System an application runs under: {@code -Dhz68k.language=Japanese|English} when given (tests, debugging),
	 *  otherwise HyperZebra's language setting (the preference, or the OS locale when none is saved - {@link PCARD#language}).
	 *  Japanese gives a KanjiTalk-like System (Shift-JIS, Osaka, itl 16384); anything else a U.S. System (Mac OS Roman). */
	static StackTextEncoding applicationEncoding(){
		String lang=System.getProperty("hz68k.language");
		if(lang==null||lang.isEmpty())lang=PCARD.language;
		return International.sameLanguage(lang,"ja")?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN;
	}

	/** A program's Japanese dialog text is a stronger clue than the host UI language.
	 * Keep the explicit VM language switch authoritative for tests and users. */
	static StackTextEncoding applicationEncoding(ResourceFile68k resources){return applicationEncoding(resources,null);}
	/** The same with the launcher's System language for the program ({@code "English"}/{@code "Japanese"}, LauncherStore.AppSettings.language),
	 *  which wins over HyperZebra's language and the dialog text (not over -Dhz68k.language): the U.S. QuarkXPress 3.31 compares
	 *  the itl0 region (intl0Vers high byte) with its own list, has no Japan in it and says "can't be run on this System. [4]". */
	static StackTextEncoding applicationEncoding(ResourceFile68k resources,String languageOverride){
		if(languageOverride!=null&&System.getProperty("hz68k.language")==null)
			return "Japanese".equals(languageOverride)?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN;
		StackTextEncoding configured=applicationEncoding();
		if(System.getProperty("hz68k.language")!=null||configured==StackTextEncoding.JAPANESE||resources==null)return configured;
		for(ResourceFile68k.Resource dialog:resources.ofType(ResourceFile68k.fourcc("DITL"))){
			byte[] data=dialog.data();
			if(data.length<2)continue;
			int count=((data[0]&255)<<8|(data[1]&255))+1,at=2;
			for(int i=0;i<count&&at+14<=data.length;i++){
				int kind=data[at+12]&0x7f,length=data[at+13]&0xff;
				if(at+14+length>data.length)break;
				if((kind==4||kind==5||kind==6||kind==8||kind==16)&&japaneseText(data,at+14,length))return StackTextEncoding.JAPANESE;
				at+=14+length+(length&1);
			}
		}
		return japaneseProgram(resources)?StackTextEncoding.JAPANESE:configured;
	}

	/** Clues besides dialog text, for programs whose dialogs are English or absent (REALbasic keeps its texts in code, an AppleScript
	 *  applet in its compiled script): a 'vers' 1 region of Japan (14); Japanese menu/string/window-title texts making up a fair
	 *  share of all of them (multilingual programs carry a few: Disk Copy's Japanese licence, a localiser's messages); kana in a
	 *  'scpt'; or a Japanese file name (a Shift-JIS Mac name means it was named on a KanjiTalk Mac: 占い, へぇボタン, 水戸黄門). */
	private static boolean japaneseProgram(ResourceFile68k resources){
		for(ResourceFile68k.Resource vers:resources.ofType(ResourceFile68k.fourcc("vers"))){
			byte[] data=vers.data();
			if(vers.id==1&&data.length>=6&&((data[4]&255)<<8|(data[5]&255))==14)return true;
		}
		int japanese=0,roman=0;
		for(String type:new String[]{"MENU","STR#","STR ","WIND","DLOG"}){
			for(ResourceFile68k.Resource r:resources.ofType(ResourceFile68k.fourcc(type))){
				if(type.equals("STR#")&&r.id>=5000&&r.id<6000)continue; // software licence texts, one per region
				byte[] data=r.data();
				int at=type.equals("MENU")?14:type.equals("STR#")?2:type.equals("WIND")?18:type.equals("DLOG")?20:0;
				int strings=type.equals("STR#")&&data.length>=2?((data[0]&255)<<8|(data[1]&255)):type.equals("MENU")?Integer.MAX_VALUE:1;
				for(int i=0;i<strings&&at<data.length;i++){
					int length=data[at]&255;
					if(type.equals("MENU")&&i>0&&length==0)break; // end of the item list
					if(at+1+length>data.length)break;
					int kind=textKind(data,at+1,length);
					if(kind>0)japanese++;else if(kind<0)roman++;
					at+=1+length+(type.equals("MENU")&&i>0?4:0); // a menu item: icon, key, mark, style
				}
			}
		}
		if(japanese>0&&japanese*3>=roman)return true;
		int kanaRuns=0;
		for(ResourceFile68k.Resource script:resources.ofType(ResourceFile68k.fourcc("scpt"))){
			byte[] data=script.data();
			for(int i=0,run=0;i+1<data.length;){
				int lead=data[i]&255,trail=data[i+1]&255;
				if((lead==0x82&&trail>=0x9f&&trail<=0xf1)||(lead==0x83&&trail>=0x40&&trail<=0x96)){if(++run==3)kanaRuns++;i+=2;}
				else{run=0;i++;}
			}
		}
		if(kanaRuns>=2)return true;
		File file=resources.file;
		if(file!=null&&!resources.ofType(ResourceFile68k.fourcc("RUNT")).isEmpty()&&japaneseDataFork(file))return true;
		if(file!=null){
			int han=0;
			for(char c:file.getName().toCharArray()){
				Character.UnicodeScript script=Character.UnicodeScript.of(c);
				if((script==Character.UnicodeScript.HIRAGANA||script==Character.UnicodeScript.KATAKANA)&&c<0xff00)return true; // not half-width katakana: "Juliaﾕs" is a Roman ’ read as Shift-JIS
				if(script==Character.UnicodeScript.HAN||(c>=0xff01&&c<=0xff5e))han++;
			}
			if(han>=2)return true;
		}
		return false;
	}

	/** A SuperCard standalone ('RUNT') keeps its project - every card text - in the data fork, and its resources are the English
	 *  runtime's: MazeBrew's Help field drew its Shift-JIS as Mac Roman under a U.S. System. Japanese prose there shows as runs of
	 *  four or more Shift-JIS characters with three different ones and three hiragana; pictures and 8-bit sounds near $80 make
	 *  kanji-like pairs but hardly ever hiragana ones (MazeBrew 494 runs, English data forks 0-2). */
	private static boolean japaneseDataFork(File file){
		int runs=0;
		try(java.io.InputStream in=new java.io.BufferedInputStream(new java.io.FileInputStream(file),1<<16)){
			int[] run=new int[8];int length=0,hiragana=0,distinct=0,pending=-1;long read=0;
			while(read<(32L<<20)){
				int c=pending;pending=-1;if(c<0){c=in.read();read++;}
				if(c<0)break;
				int t=-1;
				if((c>=0x81&&c<=0x83)||(c>=0x88&&c<=0x9f)){t=in.read();read++;}
				boolean pair=t>=0&&((c==0x82&&t>=0x9f&&t<=0xf1)||(c==0x83&&t>=0x40&&t<=0x96)||(c>=0x88&&t>=0x40&&t<=0xfc&&t!=0x7f)||(c==0x81&&t>=0x40&&t<=0x49));
				if(pair){
					int code=c<<8|t;boolean seen=false;
					for(int i=0;i<distinct;i++)if(run[i]==code)seen=true;
					if(!seen&&distinct<run.length)run[distinct++]=code;
					length++;if(c==0x82&&t>=0x9f)hiragana++;
					continue;
				}
				if(length>=4&&distinct>=3&&hiragana>=3&&++runs>=8)return true;
				length=hiragana=distinct=0;
				if(t>=0)pending=t; // the second byte may start the next pair
			}
		}catch(java.io.IOException e){return false;}
		return false;
	}

	/** 1 for Japanese text (only valid Shift-JIS, two pairs or more with a trail byte of $80 or more: an accented Roman letter and a
	 *  plain one, "é" + "t", also read as a pair), -1 for Roman text (three letters, no pairs), 0 otherwise. Control characters or
	 *  undefined lead bytes mean data kept in a string resource (installers keep compressed bytes in 'STR#'). */
	private static int textKind(byte[] data,int start,int length){
		int pairs=0,strong=0,letters=0;
		for(int i=start,end=start+length;i<end;i++){
			int c=data[i]&255;
			if(c<0x20||c==0x7f)return 0;
			if((c>=0x81&&c<=0x9f)||(c>=0xe0&&c<=0xfc)){
				if(c==0x85||c==0x86||c==0x87||c>=0xeb||i+1>=end)return 0;
				int trail=data[++i]&255;
				if(trail<0x40||trail>0xfc||trail==0x7f)return 0;
				pairs++;
				if(trail>=0x80)strong++;
			}
			else if(c>='A'&&c<='z')letters++;
		}
		return pairs>=2&&strong>=2?1:pairs==0&&letters>=3?-1:0;
	}

	/** Require several complete Shift-JIS pairs in a text item, so an accented Roman label cannot switch the VM. */
	private static boolean japaneseText(byte[] data,int start,int length){
		int pairs=0;
		for(int i=start,end=start+length;i<end;i++){
			int lead=data[i]&255;
			if((lead>=0x81&&lead<=0x9f)||(lead>=0xe0&&lead<=0xfc)){
				if(++i>=end)return false;
				int trail=data[i]&255;
				if(trail<0x40||trail>0xfc||trail==0x7f)return false;
				pairs++;
			}
		}
		return pairs>=3;
	}

	private Toolbox68k(MacApp68k app,Mac68kApi api,OStack stack){
		this.app=app;this.api=api;this.stack=stack;memory=api.memory();baseSystemVersion=systemVersionFor(app);
		applZone=memory.partitionBase()!=0?memory.partitionBase():APPL_ZONE;currentZone=applZone;
		startNanos=app==null&&stack!=null?0:System.nanoTime()-(5*60*60+System.currentTimeMillis()/17%(6*60*60*60))*16_666_667L;
		StackTextEncoding e=app!=null?applicationEncoding(app.resources,app.languageOverride):stack!=null?stack.legacyTextEncoding:StackTextEncoding.ROMAN;
		encoding=e==null?StackTextEncoding.ROMAN:e;
		Fonts68k.japaneseSystem=encoding==StackTextEncoding.JAPANESE; // installed Osaka stands in for the system font of a Japanese program
		name=app!=null?app.name:stack!=null&&stack.name!=null&&!stack.name.isEmpty()?stack.name:"HyperZebra";
		allocateFileControlBlocks();
		resources=new ResourceManager68k(this);
		quickDraw=new HostQuickDraw68k(this);
		events=new EventManager68k(this);
		windows=new WindowManager68k(this);
		menus=new MenuManager68k(this);
		controls=new ControlManager68k(this);appearance=new AppearanceManager68k(this);cursorDevices=new CursorDevices68k(this);managers=new MiscManagers68k(this);
		textEdit=new TextEdit68k(this);
		dialogs=new DialogManager68k(this);ctb=new CommToolbox68k(this);
		devices=new DeviceManager68k(this);
		desk=new DeskAccessories68k(this);
		files=new FileSystem68k(this);
		soundDriver=new SoundDriver68k(this);
		appleEvents=new AppleEvents68k(this);
		standardFile=new StandardFile68k(this);
		editions=new EditionManager68k(this);
		listManager=new ListManager68k(this);
		macTcp=new MacTCP68k(this);
		appleTalk=new AppleTalk68k(this);
		// A real QuickTime extension in the virtual System Folder takes over: the native Movie Toolbox, its Gestalt selectors and
		// its movie controller component stand aside so the extension's INIT installs itself as on a real Mac (-Dhz68k.quicktime=
		// native keeps the native one, see Extensions68k.QUICKTIME_EXTENSION; the QuickTime files are then not loaded).
		guestQuickTime=app!=null&&Extensions68k.QUICKTIME_EXTENSION&&Extensions68k.hasQuickTimeExtension(files.systemFolder);
		guestPowerPcQuickTime=guestQuickTime&&Extensions68k.runsPowerPcQuickTime(files.systemFolder);
		quickTime=new QuickTime68k(this);imageCompression=new ImageCompression68k(this);
		components=new Components68k(this);
		InternetConfig68k.register(components,this); // Internet Config ('ICAp'), part of Mac OS 9.2.2: XTernal Collection's LaunchURL and IC getters
		extensions=new Extensions68k(this);
		help=new HelpManager68k(this);
		print=new PrintManager68k(this);
		drag=new DragManager68k(this);
		threads=new ThreadManager68k(this);
		adb=new AdbManager68k(this);
		textServices=new TextServices68k(this);
	}

	/** Binds the CPU (once); externals reach this through the first trap they make. */
	void attach(M68kCpu cpu){
		if(this.cpu==cpu)return;
		this.cpu=cpu;unimplementedRomAddress=cpu.trapRomAddress(0xa89f);
		if(cpu.romHandler()==null)cpu.setRomHandler(this);
		if(cpu.scheduler()==null)cpu.setScheduler(this);
		Fonts68k.registerBitmapLookup(bitmapLookup);
		macTcp.installDnr(resources.system);
		if(app==null){try{initializeLowMemory();}catch(M68kCpuException e){}}
	}
	boolean isApplication(){return app!=null;}

	/** Bitmapped strikes the program carries itself, by font number/size/face (null = not one of its fonts; host fonts then). */
	private final Map<String,Object> strikes=new java.util.concurrent.ConcurrentHashMap<String,Object>();
	private final Fonts68k.BitmapLookup bitmapLookup=new Fonts68k.BitmapLookup(){ // one object per toolbox: Fonts68k keeps it weakly
		public BitmapFont68k find(int id,int size,int face){return bitmapFont(id,size,face);}
		// asked from any VM's thread (the lookups are shared): a resource chain changing meanwhile just means "not one of ours"
		// a family with no 'FOND' is named by its size-0 'FONT' (id = family*128, a 16-bit resource ID: PageMaker 6's ".AldusFont" is $C000, family 384)
		public int familyNumber(String name){try{ResourceFile68k.Resource fond=resources.findNamed(0x464f4e44,name,false);if(fond!=null)return resources.isSystem(fond)?0:fond.id;
			ResourceFile68k.Resource font=resources.findNamed(0x464f4e54,name,false);return font==null||resources.isSystem(font)?0:(font.id&0xffff)>>>7;}catch(RuntimeException e){return 0;}}
		public String familyName(int id){try{ResourceFile68k.Resource fond=resources.find(0x464f4e44,id);
			if(fond==null&&id>0&&id<512)fond=resources.find(0x464f4e54,(short)(id<<7));
			return fond==null||resources.isSystem(fond)||fond.name==null||fond.name.isEmpty()?null:fond.name;}catch(RuntimeException e){return null;}}
	};
	private static final Object NO_STRIKE=new Object();
	BitmapFont68k bitmapFont(int id,int size,int face){
		String key=id+"/"+size+"/"+(face&0x7f)+(Fonts68k.scaleDisabled?"u":"s");
		Object cached=strikes.get(key);
		if(cached==null){BitmapFont68k strike=null;try{strike=loadStrike(id,size,face&0x7f);}catch(M68kCpuException e){}
			if(Mac68kApi.TRACE&&strike!=null)System.err.println("[68K] font "+id+" size "+size+" face "+face+" -> "+strike);
			cached=strike==null?NO_STRIKE:strike;strikes.put(key,cached);}
		return cached==NO_STRIKE?null:(BitmapFont68k)cached;
	}
	/**
	 * The program's 'FOND' names the strike for a size and style (bold/italic/... bits 0-2); a plain 'FONT' id*128+size
	 * is the pre-FOND form. A size the FOND has no strike for comes from the family's TrueType 'sfnt' (its size-0 entry) when it
	 * has one, else is scaled from the nearest strike, as the Font Manager did. Cythera's dialogs are set in ArgosANouveau, an
	 * sfnt-only family in its data file: a host stand-in's wider letters cut "Attributes" and "Female" off.
	 */
	private BitmapFont68k loadStrike(int id,int size,int face) throws M68kCpuException {return loadStrike(id,size,face,true);}
	private BitmapFont68k loadStrike(int id,int size,int face,boolean scale) throws M68kCpuException {
		ResourceFile68k.Resource fond=resources.find(0x464f4e44,id);int strikeId=0,synthesized=face,sfntId=0;java.util.List<Integer> sizes=new java.util.ArrayList<Integer>();
		if(fond!=null&&!resources.isSystem(fond)){
			byte[] d=resources.data(fond);
			if(d.length>=54){int n=(short)MacApp68k.u2(d,52)+1;int plain=0;
				for(int i=0;i<n&&54+i*6+5<d.length;i++){int fs=MacApp68k.u2(d,54+i*6),style=MacApp68k.u2(d,56+i*6),fid=(short)MacApp68k.u2(d,58+i*6);
					if(fs==0&&sfntId==0)sfntId=fid;
					if(fs>0&&!sizes.contains(fs)&&strikeResource(fid)!=null)sizes.add(fs);
					if(fs!=size)continue;if(style==(face&7)){strikeId=fid;synthesized=face&~style;break;}if(style==0)plain=fid;} // a real bold strike is not smeared again
				if(strikeId==0)strikeId=plain;}
		}
		ResourceFile68k.Resource strike=null;
		if(strikeId!=0)strike=strikeResource(strikeId);
		else if(fond==null&&id>0&&id<512&&size<128){
			// 'FONT' id = family*128+size as a signed 16-bit ID: families 256-511 have negative IDs (PageMaker 6's tool and control
			// fonts are families 384 and 510 in "PM6.0 RSRC"); a size it lacks is scaled from the nearest one it has
			strike=strikeResource((short)(id*128+size));
			if(strike==null&&scale)for(int s=1;s<128;s++)if(strikeResource((short)(id*128+s))!=null)sizes.add(s);}
		SfntBitmaps68k sfnt=sfntId==0?null:programSfnt(sfntId);
		if(strike!=null||sfnt!=null){BitmapFont68k font=BitmapFont68k.parse(id,size,synthesized,strike==null?null:resources.data(strike),sfnt);if(font!=null)return font;}
		int from=scale?BitmapFont68k.nearestSize(sizes,size):0;
		return from==0?null:BitmapFont68k.scaled(loadStrike(id,from,face,false),size,Fonts68k.scaleDisabled);
	}
	/** The program's own 'sfnt' resources by ID, parsed once (NO_STRIKE: none, or neither outlines nor bitmaps in it). */
	private final Map<Integer,Object> sfnts=new java.util.concurrent.ConcurrentHashMap<Integer,Object>();
	private SfntBitmaps68k programSfnt(int id) throws M68kCpuException {
		Object cached=sfnts.get(id);
		if(cached==null){ResourceFile68k.Resource r=resources.find(0x73666e74,id);SfntBitmaps68k parsed=r==null||resources.isSystem(r)?null:SfntBitmaps68k.parse(resources.data(r));
			cached=parsed==null?NO_STRIKE:parsed;sfnts.put(id,cached);}
		return cached==NO_STRIKE?null:(SfntBitmaps68k)cached;
	}
	/** The program's own 'NFNT' or 'FONT' resource of an id (never the System file's). */
	private ResourceFile68k.Resource strikeResource(int id) throws M68kCpuException {
		ResourceFile68k.Resource r=resources.find(0x4e464e54,id);if(r==null)r=resources.find(0x464f4e54,id);
		return r==null||resources.isSystem(r)?null:r;
	}
	/** Whether a real host UI is available (applications and externals of an open stack). */
	boolean hosted(){return app!=null||stack!=null;}
	/** The program's translator while translation is on and its source language is known; null otherwise (an external's VM: always). */
	VMTranslation.Translator translator(){MacApp68k a=app;if(a==null||a.service)return null;VMTranslation.Translator t=a.translator;return t!=null&&t.active()?t:null;}
	/** The translation of a text the program shows, or null: show the original (off, nothing to translate, or still coming). */
	String translated(VMTranslation.Kind kind,String text){VMTranslation.Translator t=translator();return t==null?null:t.lookup(kind,text);}
	/** Whether the text is one of the program's own resource strings (menus, string lists, dialog items, window titles). */
	boolean translationKnows(String text){VMTranslation.Translator t=translator();return t!=null&&t.knows(text);}
	/** Ports where a run could not be replaced in place: their windows are erased and drawn again at the next event call. */
	private final java.util.Set<Integer> translationFailedPorts=new java.util.HashSet<Integer>();
	/**
	 * The translations that arrived replace the runs drawn while they were coming, in place. Run at the emulation's safe
	 * points too, not only at event calls: a program waiting in a Button() loop (ウルティメイト・スペル's prologue) makes none.
	 */
	void translationInPlace() throws M68kCpuException {
		java.util.Set<Integer> failed=quickDraw.replacePendingRuns(translator());
		synchronized(translationFailedPorts){translationFailedPorts.addAll(failed);}
	}
	/** Translations arrived, or translation was turned on or off: menus, window titles and window contents are shown again. */
	void translationRedraw() throws M68kCpuException {
		menus.markDirty();menus.flush();
		windows.retitle();
		// erased first: the windows that showed originals of what arrived; after a settings change every window with translatable text
		MacApp68k a=app;VMTranslation.Translator t=a==null?null:a.translatorForRedraw();
		// runs drawn while their translations were coming are replaced in place (offscreen worlds too: a program may never draw
		// that text again); only windows where that failed - drawn over since - are erased for the program to draw again
		translationInPlace();
		java.util.Set<Integer> failed=new java.util.HashSet<Integer>();
		synchronized(translationFailedPorts){failed.addAll(translationFailedPorts);translationFailedPorts.clear();}
		java.util.Set<Integer> erase=new java.util.HashSet<Integer>();
		if(t!=null){t.takeArrivedPorts();if(a.takeFullTranslationRedraw())erase.addAll(t.textPorts());}
		for(int port:failed)if(windows.isWindow(port))erase.add(port);
		windows.redrawAll(erase);
	}
	/** The folder Standard File opens in: the program's own, or for an external the stack's (some externals call SFGetFile). */
	java.io.File programDirectory(){
		if(app!=null&&app.file!=null){java.io.File d=app.file.getAbsoluteFile().getParentFile();if(d!=null)return d;}
		if(files!=null&&files.fileManager!=null&&!files.fileManager.volumes.isEmpty())return files.fileManager.volumes.get(0);
		return new java.io.File(System.getProperty("user.home",""));
	}
	/** The screen an external sees: a classic screen one size up round the card window ({@link ExternalScreenArea}), else HyperCard's, the display. */
	java.awt.Rectangle externalScreenBounds(){java.awt.Rectangle r=host().externalScreen();return r!=null?r:MacAppHost.screenBounds(true);}
	MacAppHost host(){
		if(host==null){host=new MacAppHost(events,memory,name,!hosted()||(app!=null&&app.service)||java.awt.GraphicsEnvironment.isHeadless(),app==null);host.quickDraw=api.quickDraw();host.owner=app;host.setApplicationIcon(applicationIcon());host.configureSmooth(false);} // a service VM (QuickTime playback for XCMDs) has no display
		return host;
	}
	/** Classic Finder icon used for the hosted application's native windows and the drop stand-in: the colour 'icl8' (ID 128,
	 *  else the first) masked by the matching 'ICN#', then a black-and-white 'ICN#', then a plain 'ICON'. */
	private java.awt.image.BufferedImage applicationIcon(){
		if(app==null)return null;
		try{
			ResourceFile68k.Resource icl8=firstIconResource("icl8");
			ResourceFile68k.Resource icn=icl8==null?firstIconResource("ICN#"):app.resources.get(ResourceFile68k.fourcc("ICN#"),icl8.id);
			if(icn==null)icn=firstIconResource("ICN#");
			if(icl8!=null&&icl8.data().length>=1024)return FinderIcons68k.decodeIcl8(icl8.data(),icn==null?null:icn.data());
			if(icn!=null&&icn.data().length>=256)return FinderIcons68k.decodeIcn(icn.data());
			ResourceFile68k.Resource r=firstIconResource("ICON");
			if(r==null||r.data().length<128)return null;
			return HCResource.decodeICON(r.data());
		}catch(RuntimeException ignored){return null;}
	}
	private ResourceFile68k.Resource firstIconResource(String type){
		int t=ResourceFile68k.fourcc(type);
		ResourceFile68k.Resource r=app.resources.get(t,128);
		if(r==null){java.util.List<ResourceFile68k.Resource> all=app.resources.ofType(t);if(!all.isEmpty())r=all.get(0);}
		return r;
	}
	byte[] readPascalBytesOf(String s){byte[] b=encoding.encodeReplacing(s==null?"":s);int n=Math.min(255,b.length);byte[] r=new byte[n+1];r[0]=(byte)n;System.arraycopy(b,0,r,1,n);return r;}
	StackTextEncoding encoding(){return encoding;}
	MacAppHost.Listener hostListener(){return events;}

	// ------------------------------------------------------------------ gates

	int registerGate(Gate gate){gates.add(gate);return cpu.gateAddress(gates.size()-1);}

	@Override public void gate(M68kCpu cpu,int gate) throws M68kException {
		if(gate<0||gate>=gates.size())throw new M68kCpuException(cpu.pc,"invalid host gate "+gate);
		long started=SLOW_TRAP_NANOS>0?System.nanoTime():0;
		try{gates.get(gate).call(cpu);}
		finally{if(SLOW_TRAP_NANOS>0){long took=System.nanoTime()-started;if(took>=SLOW_TRAP_NANOS)System.err.println("[68K "+Thread.currentThread().getName()+"] slow host gate "+gate+" ("+gates.get(gate).getClass().getName()+") from pc $"+Integer.toHexString(cpu.romReturnAddress())+": "+took/1_000_000+" ms");}}
	}

	@Override public void romTrap(M68kCpu cpu,int trapWord) throws M68kException {
		cpu.charge(trapCost(cpu,trapWord));if(observable(trapWord))lastObservable=cpu.instructions+cpu.charged;
		if(!api.handle(cpu,trapWord,cpu.pc))throw new M68kMacApiException(cpu.pc,trapWord,api.name(trapWord));
	}
	/** A trap called in place (the usual way: the trap word in the program's code) is as visible as one through its ROM address:
	 *  only these counted, so a program polling Button or drawing ran paced for {@link #QUIET_MILLIS} and then at host speed. */
	@Override public void inlineTrap(M68kCpu cpu,int trapWord){cpu.charge(trapCost(cpu,trapWord));if(observable(trapWord))lastObservable=cpu.instructions+cpu.charged;}

	// ------------------------------------------------------------------ speed

	/**
	 * Emulated CPU speed in million instructions per second, 0 = as fast as the host runs. The default stands for a
	 * fast 68LC040 (33/66 MHz, a little above it): the interpreter runs an order of magnitude faster than that on a
	 * modern host, which broke programs paced by their own loops (animations flashed by and click handling
	 * window after an animated card transition closed at once). {@code -Dhz68k.mips=N} overrides the preference
	 * (PCARD.realSpeed68k, the "68K CPU speed" setting) for the whole session. It is a machine's nominal rate: the VM runs
	 * {@link #WEIGHT} times fewer of its own instructions in that time (since Nov 2026).
	 */
	static final int DEFAULT_MIPS=32;
	static volatile int mips=Integer.getInteger("hz68k.mips",DEFAULT_MIPS);
	/** Instruction equivalents charged per trap: the dispatcher plus a typical routine body, which the native Toolbox runs for free. */
	static final int TRAP_COST=Integer.getInteger("hz68k.trapcost",150);
	/**
	 * What a trap's ROM or System routine took on a real 68K Mac, in instructions: the native Toolbox here runs it for free.
	 * Before Nov 2026 only traps reached through a ROM address paid {@link #TRAP_COST}; the usual inline A-line traps paid
	 * nothing, and HyperCard ran several times faster than on the machines its scripts were timed on. State traps (ports,
	 * handle flags, rectangle arithmetic, text settings) are cheap; allocation, the Resource and File Managers, TextEdit's
	 * layout and SANE's software floating point (HyperTalk's arithmetic: a Mac without an FPU ran it as 68K code) are dear.
	 * Drawing is charged on top by QuickDraw68k (DRAW_COST).
	 */
	static long trapCost(M68kCpu cpu,int trapWord){
		int t=trapWord&0x0fff;
		if((trapWord&0x0800)==0){ // OS traps
			t=trapWord&0xff;
			switch(t){
				case 0x29:case 0x2a:case 0x49:case 0x4a:case 0x69:case 0x6a:case 0x25:case 0x21:case 0x65:case 0x62:case 0x66:case 0x63:return 40; // HLock HUnlock HPurge HNoPurge HGetState HSetState GetHandleSize GetPtrSize StackSpace PurgeSpace HandleZone RecoverHandle
				case 0x22:case 0x1e:return 300; // NewHandle, NewPtr
				case 0x23:case 0x1f:return 150; // DisposeHandle, DisposePtr
				case 0x24:case 0x20:case 0x27:return 300; // SetHandleSize, SetPtrSize, ReallocHandle
				case 0x2e:return 30+Math.max(0,cpu.d[0])/4; // BlockMove: a long word a move; a negative count moves nothing (Bartender's Friend's LDEF passes -1: 70 s asleep in the throttle)
				case 0x02:case 0x03:{ // Read, Write: the call, the cache and a copy of the bytes
					long count=0;try{count=Math.max(0,cpu.memory.readLong(cpu.a[0]+36));}catch(Exception e){}
					return 800+count/2;}
				case 0x00:case 0x01:case 0x08:case 0x09:case 0x0c:case 0x0d:case 0x60:return 2000; // Open, Close, Create, Delete, GetFileInfo, SetFileInfo, HFSDispatch
				default:return TRAP_COST;
			}
		}
		switch(t){
			case 0x975:case 0x873:case 0x874:case 0x887:case 0x888:case 0x889:case 0x88a:case 0x89e:case 0x893:case 0x894:case 0x89a:
			case 0x8a7:case 0x8a8:case 0x8a9:case 0x8aa:case 0x8ab:case 0x8ac:case 0x8ad:case 0x8ae:case 0x8a6:case 0x862:case 0x863:case 0x864:
				return 40; // TickCount, SetPort/GetPort, TextFont/Face/Mode/Size, PenNormal, MoveTo/Move, GetPen, rectangle arithmetic, colours
			case 0x9a0:case 0x81f:case 0x9a1:case 0x820:case 0x9bb:case 0x9bc:case 0x9b9:case 0x9ba:case 0xa1e:return 1200; // GetResource and the Get* that use it: the map searches
			case 0x9d3:return 58000; // TEUpdate: the lines laid out again and drawn
			case 0x9d0:return 4000; // TECalText
			case 0x9cf:case 0x9de:case 0x9d7:case 0x9d4:case 0x9dc:return 1500; // TESetText, TEInsert, TEDelete, TEClick-free edits, TEKey
			case 0x83d:return 1000; // TEDispatch (styled TextEdit)
			case 0x8b5:case 0x9ed:return 300; // ScriptUtil, Pack6 (International Utilities)
			case 0x9eb:return saneCost(cpu);
			case 0x9ec:return 8000; // Elems68K: transcendental functions
			case 0x9ee:return 1500; // DecStr68K: decimal strings
			default:return TRAP_COST;
		}
	}
	/** An FP68K operation of software SANE (the opword on top of the stack): the arithmetic of 80-bit extended numbers. */
	private static long saneCost(M68kCpu cpu){
		int op;try{op=cpu.memory.readWord(cpu.a[7])&0x1f;}catch(Exception e){return 800;}
		switch(op){
			case 0x01:case 0x03:case 0x05:case 0x07:case 0x15:case 0x17:case 0x19:case 0x1b:return 80; // environment, halts, exceptions
			case 0x0d:case 0x0f:case 0x11:return 120; // negate, absolute value, copy sign
			case 0x1c:return 300; // class
			case 0x08:case 0x0a:return 400; // compare
			case 0x0e:case 0x10:return 500; // conversions between formats
			case 0x14:case 0x16:return 600; // round to integer, truncate
			case 0x00:case 0x02:return 700; // add, subtract
			case 0x04:return 1000; // multiply
			case 0x06:return 1800; // divide
			case 0x0c:return 2500; // remainder
			case 0x12:return 3000; // square root
			case 0x09:case 0x0b:return 5000; // decimal records to and from binary
			case 0x18:case 0x1a:case 0x13:return 400; // scalb, logb, nextafter
			default:return 800;
		}
	}
	/**
	 * How many of the VM's instructions a real Mac's take in time: the presets (and -Dhz68k.mips) are machines' nominal
	 * instruction rates, and a real 68040 ran a program's code (memory traffic, cache misses) and the Toolbox's well below its
	 * nominal rate. Fitted so that HyperCard 2.2 here at 30 MIPS gives the "HC速度計" scores a 68LC040 at 33 MHz printed with
	 * HyperCard J-2.2 (44/27/37/28). -Dhz68k.weight=F overrides it.
	 */
	static final double WEIGHT=weight("hz68k.weight",4.2);
	/**
	 * The same for a PowerPC program, fitted to a Power Macintosh G3 at 333 MHz (HyperCard J-2.3 PowerPC printed 900/228/480/220
	 * there; at 333 MIPS here it gives about that): its PowerPC instructions weigh {@link #PPC_WEIGHT}, and its Toolbox calls
	 * (the trap charges in 68K instructions) {@link #PPC_TOOLBOX_WEIGHT} - much of the Toolbox of a Power Mac of the time was
	 * still 68K code run by its emulator - and its drawing (QuickDraw68k's DRAW_COST charges) {@link #PPC_DRAW_WEIGHT}: QuickDraw
	 * was native. -Dhzppc.weight / -Dhzppc.toolboxweight / -Dhzppc.drawweight override them.
	 */
	static final double PPC_WEIGHT=weight("hzppc.weight",2),PPC_TOOLBOX_WEIGHT=weight("hzppc.toolboxweight",8.5),PPC_DRAW_WEIGHT=weight("hzppc.drawweight",3.6);
	private static double weight(String property,double standard){
		try{return Math.max(0.01,Double.parseDouble(System.getProperty(property,String.valueOf(standard))));}catch(NumberFormatException e){return standard;}
	}
	/** The throttle lets a stretch of code run at host speed once this much virtual time has passed without anything the
	 *  user could notice (drawing, sound, input polls, timed waits, direct screen writes): a decompressor or a long
	 *  computation gains nothing from a slow CPU. A counted delay loop longer than this is cut short, which is the trade-off. */
	static final long QUIET_MILLIS=Long.getLong("hz68k.quiet",500L);
	/** Wall time the program may run ahead after being blocked (WaitNextEvent, a tick park): the pace is enforced as a token bucket. */
	private static final long THROTTLE_SLACK_NANOS=20_000_000L;
	/** Virtual instruction count ({@code instructions+charged}) at the last observable trap or device write. */
	private long lastObservable;
	private long throttleCounted,throttleDeadline;
	/** Traps whose timing the user can perceive: drawing and cursors (QuickDraw, $A850-$A8FF incl. WaitNextEvent), sound,
	 *  the Window/Menu/Control/Event/Dialog/TextEdit managers ($A900-$A9FF minus the Resource Manager and the numeric
	 *  packages), event and mouse polls, Delay and the Time Manager. Memory, file and resource traps are not. */
	static boolean observable(int trapWord){
		int t=trapWord&0x0fff;
		if((trapWord&0x0800)!=0){
			if(t>=0x850&&t<0x900)return true;
			if(t>=0x800&&t<=0x807)return true; // Sound Manager
			if(t>=0x900){if(t>=0x992&&t<=0x9ab)return false;if(t>=0x9eb&&t<=0x9ee)return false;if(t==0x975)return false;return t!=0x9f4&&t!=0x9ff;} // TickCount alone is not a wait
			return false;
		}
		t=trapWord&0xff;
		return t>=0x2f&&t<=0x32||t==0x3b||t>=0x58&&t<=0x5a; // PostEvent, OSEventAvail, GetOSEvent, FlushEvents, Delay, InsTime, RmvTime, PrimeTime
	}
	/**
	 * Paces the program to {@link #mips} from the scheduler poll: the virtual time of the instructions and charges since the
	 * last poll is added to a deadline, and the thread parks until the deadline when it is ahead of the wall clock. Time
	 * spent blocked in a trap counts as elapsed (the deadline is clamped to {@link #THROTTLE_SLACK_NANOS} behind now), so a
	 * program that waited for an event does not get a burst afterwards. Headless and service VMs are never paced.
	 */
	private void throttle(M68kCpu cpu,boolean deviceWritten){
		long virtual=cpu.instructions+cpu.charged;
		if(deviceWritten)lastObservable=virtual;
		double rate=app!=null?app.mips():stack!=null?StackSpeed.externalMips(mips):mips; // the launcher's per-program speed setting, a stack's speed limit for its XCMDs, else the preference
		rate/=app!=null&&app.powerPC?PPC_TOOLBOX_WEIGHT:WEIGHT; // the machine's nominal rate: the VM's instructions it runs in that time
		// offscreenMdef: the host menus learnt from a custom MDEF, thousands of calls no real Mac makes (BBEdit 3.5 spent over a minute of start-up paced here)
		if(rate<=0||!hosted()||app!=null&&(app.service||app.finished)||offscreenMdef>0){throttleCounted=virtual;return;}
		long delta=virtual-throttleCounted;throttleCounted=virtual;
		long now=System.nanoTime();
		if(virtual-lastObservable>(long)(rate*QUIET_MILLIS*1000)){throttleDeadline=now;return;} // nothing to see: run at host speed
		long deadline=Math.max(throttleDeadline,now-THROTTLE_SLACK_NANOS)+(long)(delta*1000/rate);
		throttleDeadline=deadline;
		long wait=deadline-now;
		if(wait>=500_000L)java.util.concurrent.locks.LockSupport.parkNanos(wait);
	}

	/** Runs {@code r} on the emulation thread at the next safe point. */
	void later(Runnable r){deferred.add(r);if(events!=null)events.wake();}
	/** Host changes to the program's windows (moved or resized on the host side) wait for the program's next event call: they
	 *  run its WDEF and change its window records, which a Mac only does inside the Toolbox calls a program makes - never at the
	 *  interrupt-like scheduler polls where {@link #later} work runs. Excel 2.2's p-code WDEF re-entered its interpreter
	 *  in the middle of a segment load (_Read) and wrecked it (negative segment size, spinning allocator, odd PCs). */
	void laterAtEvent(Runnable r){atEvent.add(r);if(events!=null)events.wake();}
	private final ConcurrentLinkedQueue<Runnable> atEvent=new ConcurrentLinkedQueue<Runnable>();private boolean runningAtEvent;
	/** Deferred work plus the {@link #laterAtEvent} work: event calls (GetNextEvent, WaitNextEvent, SystemTask, ModalDialog, an
	 *  external's pump between calls) only. */
	/** The program has written the screen memory itself: its windows' visRgns say what the screen shows of each one from now on
	 *  (WindowManager68k.calcVis). Checked at scheduler polls and event calls. */
	void noticeScreenWrites(){if(host!=null&&memory.deviceWritten()&&host.extensionsDone())windows.screenWritten();}
	void runEventWork() throws M68kException {
		runDeferred();
		noticeScreenWrites();
		runNotificationResponses();
		if(runningAtEvent)return;runningAtEvent=true;
		try{Runnable r;while((r=atEvent.poll())!=null){
				try{r.run();}
				catch(RuntimeException e){Throwable c=e.getCause();if(c instanceof M68kException)throw (M68kException)c;throw e;}}}
		finally{runningAtEvent=false;}
	}
	/** Ends an idle wait early (MIDI arrived from the host, a wake-up was armed): the timers run again at once. */
	void wakeIdle(){if(events!=null)events.wake();}
	/** When the next timed work (a MIDI Manager wake-up or due packet) is wanted, in nanoTime terms; Long.MAX_VALUE for none. */
	long nextDueNanos(){MidiManager68k midi=api.soundManager().midiIfAny();return Math.min(Math.min(Math.min(Math.min(appleTalk.nextDueNanos(),midi==null?Long.MAX_VALUE:midi.nextDueNanos()),threads.nextTaskDeadline()),io==null?Long.MAX_VALUE:io.nextDueNanos()),timersDueNanos());}

	@Override public void poll(M68kCpu cpu) throws M68kException {
		long started=SLOW_TRAP_NANOS>0?System.nanoTime():0;
		runDeferred();
		runDeferredTasks();
		updateTicks(pollTicks(cpu));
		if(cardWindow!=null)cardWindow.flush();
		pollingAtInstruction=true;
		try{runTimers();runPeriodic();}finally{pollingAtInstruction=false;}
		boolean deviceWritten=host!=null&&host.adoptDeviceWrites(); // direct screen writes become the pixels of the windows under them
		noticeScreenWrites();
		// samples written to the sound buffer: a writer ahead of the playback waits here; making sound is activity the user
		// hears (the throttle keeps its pace) and progress, not a runaway loop (MacinTalk speaks without a trap)
		boolean sounding=io!=null&&io.sound.pace();
		if(sounding)cpu.yielded();
		throttle(cpu,deviceWritten||sounding);
		if(SLOW_TRAP_NANOS>0){long took=System.nanoTime()-started;if(took>=SLOW_TRAP_NANOS)System.err.println("[68K "+Thread.currentThread().getName()+"] slow poll at pc $"+Integer.toHexString(cpu.pc)+": "+took/1_000_000+" ms");}
	}

	private boolean runningDeferred;
	boolean hasDeferred(){return !deferred.isEmpty();}
	/** Runs at every safe point of the VM thread (polls and event waits), outside any other deferred work: the QuickTime service drives MoviesTask from it. */
	volatile Runnable idleHook;
	/**
	 * Interrupt-time work while the program waits inside a trap for the host (a pop-up menu held open): deferred tasks, sound
	 * callbacks and Time Manager tasks run, as a Mac's interrupts went on under the Menu Manager. Called on the program's thread.
	 */
	void serviceWhileBlocked(){
		try{runDeferred();runTimers();}
		catch(M68kException|RuntimeException e){if(Mac68kApi.TRACE)System.err.println("[68K] interrupt work during a host wait: "+e);}
	}
	void runDeferred() throws M68kException {
		// Not re-entrant: a deferred task that runs guest code (sound double-buffer callbacks, completion
		// routines) polls too, and interrupt-time routines must not nest inside themselves.
		if(runningDeferred)return;runningDeferred=true;
		try{
			Runnable r;
			while((r=deferred.poll())!=null){
				try{r.run();}
				catch(RuntimeException e){Throwable c=e.getCause();if(c instanceof M68kException)throw (M68kException)c;throw e;}
			}
			// Deferred tasks run as the interrupt that installed them ends, not at the next scheduler poll: a completion routine's
			// DTInstall during a WaitNextEvent sleep waited out the sleep (100 ms) - SoundApp queues each next bufferCmd from such a
			// task, and with its 2 KB VOC buffers the channel ran dry and the sound stopped after a fraction of a second
			runDeferredTasks();
			Runnable hook=idleHook;if(hook!=null)hook.run();
		}finally{runningDeferred=false;}
	}

	/** Wraps a checked emulator exception for deferred runnables. */
	static RuntimeException wrap(M68kException e){return new RuntimeException(e);}

	static final long TICK_NANOS=16_666_667L;
	int tickCount(){return (int)((System.nanoTime()-startNanos)/TICK_NANOS);}
	/** Spin detection for the TickCount trap: consecutive tight calls that saw the same tick. */
	private int spinTick=-1,spinCalls;private long spinLastInstructions;
	/**
	 * TickCount as programs call it. Frame-rate locks spin on it ("while (TickCount() < next) ;", sometimes making hundreds of calls
	 * per frame, 15 instructions apart), which burns a host core for nothing: after 32 such calls within one tick the
	 * emulation thread parks until the tick boundary. The program observes exactly the same sequence of ticks, only without
	 * the busy wait in between. Only calls fewer than TICK_SPIN_INSTRUCTIONS apart count as spinning: HyperCard reads
	 * TickCount once per HyperTalk statement (at least 44 instructions, usually thousands, apart) and parking after 32 of
	 * those capped scripts at 32 statements per tick - HC速度計 scored 94 instead of ~1150.
	 */
	static final int TICK_PARK_AFTER=Integer.getInteger("hz68k.tickpark",32),TICK_SPIN_INSTRUCTIONS=Integer.getInteger("hz68k.tickspin",32);
	private static final boolean TICK_STATS=Boolean.getBoolean("hz68k.tickstats");private long tickStatLast,tickStatCalls,tickStatMin=Long.MAX_VALUE,tickStatSum;private long[] tickStatBuckets=new long[8];private long tickStatPrinted;
	int tickCountPolled(){
		int tick=tickCount();
		// PowerPC programs call TickCount through the 68K glue: the PowerPC work in between counts too (何かon林檎's REALbasic
		// runtime reads TickCount at every loop turn of an image scan and was parked as if it were spinning - boot stopped at "Shell setting...")
		long executed=(cpu==null?0:cpu.instructions)+(app!=null&&app.ppc!=null?app.ppc.cpu.instructions:0),since=executed-spinLastInstructions;spinLastInstructions=executed;
		if(TICK_STATS){long d=since;tickStatCalls++;tickStatSum+=d;if(d<tickStatMin)tickStatMin=d; // -Dhz68k.tickstats=true: instructions between calls, every 2 s
			int b=0;for(long v=d;v>=16&&b<7;v>>=2)b++;tickStatBuckets[b]++;
			long now=System.currentTimeMillis();if(now-tickStatPrinted>2000){tickStatPrinted=now;System.err.println("[68K] TickCount stats: calls="+tickStatCalls+" min="+tickStatMin+" avg="+(tickStatCalls==0?0:tickStatSum/tickStatCalls)+" buckets(<16,<64,<256,<1k,<4k,<16k,<64k,>=64k)="+java.util.Arrays.toString(tickStatBuckets));tickStatCalls=0;tickStatSum=0;tickStatMin=Long.MAX_VALUE;java.util.Arrays.fill(tickStatBuckets,0);}}
		if(tick!=spinTick||since>=TICK_SPIN_INSTRUCTIONS){spinTick=tick;spinCalls=0;return tick;}
		if(++spinCalls>=TICK_PARK_AFTER&&(app==null||!app.finished)){
			long elapsed=System.nanoTime()-startNanos,remaining=TICK_NANOS-elapsed%TICK_NANOS;
			java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining,4_000_000L));
			int now=tickCount();if(now!=tick){spinTick=now;spinCalls=0;}return now;}
		return tick;
	}
	/** Spin detection for programs that read low-memory Ticks ($16A) instead of calling TickCount: consecutive polls
	 *  that saw the same tick and no trap in between. */
	private int pollTick=-1,pollSpinPolls,spinYieldedTicks;private long pollTraps,pollInstructions=-1;
	/**
	 * The tick for the scheduler poll. A loop such as CompileIt!'s "repeat until ticks >= whenn" (UxFlash) waits by
	 * comparing $16A in a tight loop with no trap at all, which burns a host core and, in an external, runs through
	 * the runaway-loop instruction limit within a few ticks on a fast host. After {@link #TICKS_PARK_AFTER} polls
	 * inside one tick without a trap, and only when the recent instructions are a small loop that references $16A
	 * (a trap-free block copy or computation must keep its speed), the thread parks until the tick boundary (as {@link #tickCountPolled} does for
	 * TickCount), and each tick that passes this way counts as a yield, like a Delay: the program is waiting on real
	 * time, not looping forever. The yield stops after {@link #TICKS_SPIN_LIMIT} ticks without a trap so that an
	 * external which really hangs still trips the limit (a minute later).
	 */
	static final int TICKS_PARK_AFTER=Integer.getInteger("hz68k.tickspark",4),TICKS_SPIN_LIMIT=Integer.getInteger("hz68k.tickspinlimit",3600);
	private int pollTicks(M68kCpu cpu){
		int tick=tickCount();
		boolean trapped=cpu.traps!=pollTraps;pollTraps=cpu.traps;
		// a poll from PowerPC code (PpcRuntime.poll): the 68K CPU stands still inside a Mixed Mode call, so its recent PCs say
		// nothing about the PowerPC work (StuffIt Expander's UnStuffEnginePPC was parked at every poll: 1.5 MIPS)
		boolean ran68k=cpu.instructions!=pollInstructions;pollInstructions=cpu.instructions;
		if(trapped||!ran68k){pollSpinPolls=0;spinYieldedTicks=0;pollTick=tick;return tick;}
		if(tick!=pollTick){pollTick=tick;pollSpinPolls=0;return tick;}
		if(++pollSpinPolls<TICKS_PARK_AFTER||(app!=null&&app.finished))return tick;
		if(pollSpinPolls==TICKS_PARK_AFTER&&!cpu.spinningOn(0x016a,0x016d)){pollSpinPolls=Integer.MIN_VALUE;return tick;} // a long trap-free computation, not a wait: leave it alone until the next tick
		if(pollSpinPolls<0)return tick;
		long elapsed=System.nanoTime()-startNanos,remaining=TICK_NANOS-elapsed%TICK_NANOS;
		java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining,4_000_000L));
		int now=tickCount();
		if(now!=tick){pollTick=now;pollSpinPolls=0;if(spinYieldedTicks<TICKS_SPIN_LIMIT){spinYieldedTicks+=now-tick;cpu.yielded();}}
		return now;
	}
	private int mousePollTick,mousePolls,mouseYieldedTicks;
	/**
	 * An external polling the mouse (Button, StillDown, WaitMouseUp, GetMouse) in a loop is waiting for the user, as
	 * Some pop-up externals do this for the click that dismisses their window: from the fourth poll inside one tick the
	 * thread parks until the tick boundary and each tick that passes counts as a yield, like {@link #pollTicks}, so the
	 * 2M-instruction limit does not fire while the user has not clicked yet. Capped at {@link #TICKS_SPIN_LIMIT} ticks
	 * so an external that really hangs still trips the limit. Hosted programs keep their own pacing.
	 */
	void mousePolled(M68kCpu cpu){
		if(hosted()&&!cpu.external)host().mousePoll();
		if(!cpu.external)return;
		int tick=tickCount();
		if(tick!=mousePollTick){mousePollTick=tick;mousePolls=0;return;}
		if(++mousePolls<TICKS_PARK_AFTER||(app!=null&&app.finished))return;
		// an external tracking the mouse in a loop of its own draws its feedback on the card between two polls (DragNDrop's XOR
		// outline and target frame): shown before the wait, not at the next scheduler poll ~1 s later
		if(cardWindow!=null)try{cardWindow.flush();}catch(M68kCpuException e){}
		long elapsed=System.nanoTime()-startNanos,remaining=TICK_NANOS-elapsed%TICK_NANOS;
		java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining,4_000_000L));
		int now=tickCount();
		if(now!=tick){mousePollTick=now;mousePolls=0;if(mouseYieldedTicks<TICKS_SPIN_LIMIT){mouseYieldedTicks+=now-tick;cpu.yielded();}}
	}
	/** Low-memory Ticks ($16A) and Time ($20C): written only when they change (TickCount is the most frequent trap of a
	 *  running game, ~16000 calls a second, and the poll every 4096 instructions comes here too). */
	void updateTicks(){updateTicks(tickCount());}
	void updateTicks(int tick){
		try{
			if(tick!=lastWrittenTick){lastWrittenTick=tick;memory.writeLong(0x16a,tick);updateMouse(false);wacom.refresh(tick);}
			long second=System.currentTimeMillis()/1000L; // at each new second (written a second after the last write, Time lagged up to 1 s behind `the seconds`)
			if(second!=lastWrittenTime){lastWrittenTime=second;memory.writeLong(0x20c,(int)(macSeconds(second*1000L)+programClockSeconds+ownClockShift));}
		}catch(M68kCpuException ignored){}}
	private int lastWrittenTick=-1;private long lastWrittenTime=Long.MIN_VALUE;
	/** How far this program's "now" (Time, ReadDateTime) runs behind the VM clock: 0, or whole 28-year cycles for a program
	 *  whose date arithmetic breaks with today's date (MacApp68k.ownClockShift). File dates are not moved. */
	long ownClockShift;
	/**
	 * A program loading GCS's decompressor ('GCdc' - Apple Installer 3 scripts with "GCcm" compressed files, Alias Sketch! 1.5)
	 * gets its own clock set back by whole 28-year cycles before April 2020: each archive's FMap 1000 holds an expiry (year
	 * and month + 4747; March 2020 in every one seen), checked against GetTime, and the install rolled back with "Archive
	 * File has Expired". An explicit -Dhz68k.yearshift leaves the clock to it.
	 */
	public void clockBeforeGcsExpiry(){
		String setting=System.getProperty("hz68k.yearshift");
		if(setting!=null&&!setting.trim().equalsIgnoreCase("auto"))return;
		final long april2020=3668544000L; // 2020-04-01 00:00 in seconds since 1904
		long shift=ownClockShift;
		while(macNow()+shift>=april2020)shift-=CLOCK_CYCLE_SECONDS;
		if(shift==ownClockShift)return;
		ownClockShift=shift;lastWrittenTime=Long.MIN_VALUE;
		System.err.println("[68K] clock set back "+(-shift/CLOCK_CYCLE_SECONDS*28)+" years: GCS compressed archives expire in March 2020");
	}
	/** JGNEFilter ($29A), the GetNextEvent filter hook, and its default (a no-op gate standing for the ROM's RTS). */
	static final int JGNE_FILTER=0x29a;int gneFilterGate;
	private int keyTransGate;
	/** The VIAs and the SCC (VBase, SCCRd, SCCWr). */
	MacIo68k io;
	/** The block ASCBase ($CC0) points to (0 until the low memory is set up). */
	private int ascBase;
	/** Low-memory MTemp ($828), RawMouse ($82C), Mouse ($830) and MBState ($172): the pointer position and button, written once a tick when they changed
	 *  (programs read Mouse directly; a value the program just stored itself in RawMouse must survive until its JCrsrTask call). */
	private int lastWrittenMouse=Integer.MIN_VALUE;private int cursorTaskGate,swapFontGate;private int[] cursorVectorGates;
	private void updateMouse(boolean force) throws M68kCpuException {
		// an external's VM too (HyperCard's low memory: Memory Tools' PeekWord("830") / PeekByte("172") read 0,0 and a held button);
		// its pointer place and button come from the host once it has one (Xcmd68k publishes them as each call starts)
		if(app==null&&(stack==null||host==null)||host==null&&!force)return;
		// the documented warp (IM I-259): the program stores MTemp and RawMouse and sets CrsrNew from CrsrCouple, then the
		// cursor VBL task moves the pointer - games that re-centre the mouse (Apache Strike, Wolfenstein 3D) do it this way
		if(!force&&memory.readByte(0x8ce)!=0){if(memory.readLong(0x82c)!=lastWrittenMouse){cursorTask();return;}memory.writeByte(0x8ce,0);}
		if(host!=null)events.updateKeyMap(); // KeyMap ($174): games read the keyboard there instead of calling GetKeys
		int button=host().isMouseDown()?0:0x80; // MBState ($172): bit 7 set = button up
		if(force||button!=lastWrittenButton){lastWrittenButton=button;memory.writeByte(0x172,button);memory.writeLong(0x16e,tickCount());} // MBTicks
		java.awt.Point p=host().mouseLocation();int packed=(p.y<<16)|(p.x&0xffff);
		if(!force&&packed==lastWrittenMouse)return;
		lastWrittenMouse=packed;memory.writeLong(0x828,packed);memory.writeLong(0x82c,packed);memory.writeLong(0x830,packed);
	}
	private int lastWrittenButton=-1;
	/** Writes the pointer to Mouse/RawMouse/MTemp now rather than at the next tick (a made-up click's place, see EventManager68k.delivered). */
	void publishMouse() throws M68kCpuException {updateMouse(false);}
	/** Moves the pointer to (h,v) and publishes it as Mouse/RawMouse/MTemp now (CursorDeviceMove/MoveTo, and the cursor task's warp). */
	void warpPointer(int h,int v) throws M68kCpuException {
		if(host==null)return;
		host().warpMouse(h,v);adb.pointerWarped(h,v);
		int packed=(v<<16)|(h&0xffff);lastWrittenMouse=packed;memory.writeLong(0x828,packed);memory.writeLong(0x82c,packed);memory.writeLong(0x830,packed);
	}
	/** The cursor VBL task (JCrsrTask vector): moves the pointer to RawMouse and publishes it as Mouse (IM I-259 mouse warping). */
	private void cursorTask() throws M68kCpuException {
		int raw=memory.readLong(0x82c);
		if(raw==lastWrittenMouse)return; // the tick's own call through a program's JCrsrTask hook: nothing to warp
		host().warpMouse((short)raw,raw>>16);adb.pointerWarped((short)raw,raw>>16);
		lastWrittenMouse=raw;memory.writeLong(0x828,raw);memory.writeLong(0x830,raw);memory.writeByte(0x8ce,0); // CrsrNew
	}
	/** Local time zone offset, re-read from the zone rules at most once a second: updateTicks asks every 4096 instructions
	 *  and TimeZone.getOffset searches the transition table each time (it showed up as the busiest frame of a running game). */
	static int localOffsetSeconds(){refreshClock();return localOffsetSeconds;}
	/** The VM clock's shift from the host's (seconds, 0 or whole 28-year cycles back): Mac dates are unsigned 32-bit seconds since
	 *  1904 local, the last one 2040-02-06 06:28:15, so from 2040 on the programs see the host's date 28 years back (56 from 2068 …),
	 *  the same weekdays and leap years (1901-2099). -Dhz68k.yearshift=auto (the default), 0 for the host's date, or a number of
	 *  years (rounded to 28s). Host file dates cross with it both ways; dates stored inside disk images and HyperTalk's own
	 *  dates (the seconds, convert) are not shifted. */
	static long clockShiftSeconds(){refreshClock();return clockShiftSeconds;}
	/** 28 Gregorian years without a century day: 7 leap days. */
	static final long CLOCK_CYCLE_SECONDS=(28L*365+7)*86400L;
	static long clockShiftSeconds(long localMillis,String setting){
		String s=setting==null?"":setting.trim().toLowerCase(java.util.Locale.ROOT);
		if(s.equals("off")||s.equals("false"))return 0;
		if(!s.isEmpty()&&!s.equals("auto"))try{return Math.round(Integer.parseInt(s)/28.0)*CLOCK_CYCLE_SECONDS;}catch(NumberFormatException ignored){}
		Calendar c=macCalendar();c.setTimeInMillis(localMillis);int year=c.get(Calendar.YEAR);
		return year<2040?0:-((year-2040)/28+1)*CLOCK_CYCLE_SECONDS;
	}
	/** A host time (UTC milliseconds) as a Mac date on the VM clock: local seconds since 1904, shifted (the caller keeps 32 bits). */
	static long macSeconds(long hostMillis){return Math.floorDiv(hostMillis,1000L)+MAC_EPOCH_DELTA+localOffsetSeconds()+clockShiftSeconds();}
	/** A Mac date on the VM clock as host UTC milliseconds (macSeconds back). */
	static long hostMillis(long macSeconds){return (macSeconds-MAC_EPOCH_DELTA-localOffsetSeconds()-clockShiftSeconds())*1000L;}
	/** Now on the VM clock, as GetDateTime and Time ($20C) have it (with what SetDateTime changed). */
	static long macNow(){return macSeconds(System.currentTimeMillis())+programClockSeconds;}
	/**
	 * SetDateTime's change of the VM clock (seconds), for this run of HyperZebra only and for every VM (one Mac, one clock):
	 * a program or external setting the date sees it go on from there (XTernal's SetDateTime then GetDateTime; a Date & Time
	 * control panel), the host's clock is never touched. Only "now" moves - host file dates still convert unchanged (they
	 * happened when they did), and the 2040 shift above stays the host clock's own. HyperTalk's dates can follow through
	 * {@link #programClockSeconds()}.
	 */
	private static volatile long programClockSeconds;
	/** How far programs have set the VM clock from the host's (seconds; 0 unless SetDateTime was called this run). */
	static long programClockSeconds(){return programClockSeconds;}
	/** SetDateTime(secs): the VM clock reads {@code secs} now (an unsigned Mac date). */
	static void setProgramClock(long macSeconds){programClockSeconds=(macSeconds&0xffffffffL)-macSeconds(System.currentTimeMillis());}
	private static void refreshClock(){long now=System.currentTimeMillis();
		if(Math.abs(now-localOffsetCheckedAt)>1000L){int offset=TimeZone.getDefault().getOffset(now)/1000;
			clockShiftSeconds=clockShiftSeconds(now+offset*1000L,System.getProperty("hz68k.yearshift"));localOffsetSeconds=offset;localOffsetCheckedAt=now;}}
	private static volatile long localOffsetCheckedAt=Long.MIN_VALUE/2;private static volatile int localOffsetSeconds;private static volatile long clockShiftSeconds;

	// ------------------------------------------------------------------ low memory

	/** The boot value of RndSeed: random in 1..2^31-2 (the range Random's 7^5 x mod (2^31-1) keeps), or -Dhz68k.rndseed. */
	static int initialRndSeed(){
		Long fixed=Long.getLong("hz68k.rndseed");
		long seed=fixed!=null?fixed:java.util.concurrent.ThreadLocalRandom.current().nextLong(1,2147483647L);
		seed%=2147483647L;if(seed<=0)seed+=2147483646L;return (int)Math.max(1,seed);
	}

	void initializeLowMemory() throws M68kCpuException {
		int rom=cpu.romBase();
		int stackTop=app!=null?app.stackTop:0x00fff000,stackBottom=app!=null?app.stackBottom:memory.heapEnd();
		memory.writeLong(0x108,memory.romBase());       // MemTop
		memory.writeLong(0x114,stackBottom);            // HeapEnd
		memory.writeLong(0x118,applZone);              // TheZone
		writeApplZoneRecord();
		// BufPtr: the top of application memory. Programs of the 1980s measure the room above their heap as BufPtr - bkLim in
		// kilobytes kept in a signed word (some games otherwise refuse to start as "low on memory" with the 45 MB between the zone's
		// 16 MB limit and the stack here), so BufPtr ends within what FreeMem would report (see reportedFree) of the zone's limit.
		int bufferTop=stackTop;if(app!=null&&!app.service){int limit=memory.readLong(applZone);if(limit>0&&limit<stackTop)bufferTop=(int)Math.min(stackTop,limit+(long)reportedFree(Integer.MAX_VALUE));}
		memory.writeLong(0x10c,bufferTop);              // BufPtr
		// ApplLimit: the stack's lower end - but an MPW tool takes ApplLimit - ApplZone as its partition: Asm 3.1 divides it
		// into 1016-byte pages counted in a signed word, so the 60 MB here made the count negative, it never got a page, spilled
		// through a NIL buffer and wrote over low memory ($316, the Shell's globals). MPW gets a 24 MB partition (above the zone's
		// 16 MB bkLim; Asm's pages after its 188K/298K reserves stay below 32768); the stack stays where it is, above.
		memory.writeLong(0x130,app!=null&&app.mpwShell?Math.min(stackBottom,applZone+MPW_PARTITION):stackBottom); // ApplLimit
		// VBLQueue ($160) never is empty on a real Mac (the ROM's cursor task sits in it); some games look for the task they just
		// installed among the qLinks starting at qHead, so it only finds one that follows another. A permanent system task heads the queue.
		{int system=memory.allocate(16,2);memory.writeLong(system,0);memory.writeWord(system+4,1);memory.writeLong(system+6,0);memory.writeWord(system+10,0x7fff);
			memory.writeWord(VBL_QUEUE,0);memory.writeLong(VBL_QUEUE+2,system);memory.writeLong(VBL_QUEUE+6,system);}
		memory.writeWord(EventManager68k.SYS_EVT_MASK,EventManager68k.DEFAULT_SYS_EVT_MASK); // SysEvtMask: everyEvent - keyUpMask
		// RndSeed: a different seed every launch - on a used Mac it is never where it was at boot, and programs that start their
		// Random() from it (MacYahtzee: randSeed := RndSeed) rolled the same dice each time. -Dhz68k.rndseed=<n> fixes it for checks.
		memory.writeLong(0x156,initialRndSeed());
		memory.writeWord(0x15a,baseSystemVersion);      // SysVersion
		memory.writeByte(0x15c,1);                      // SEvtEnb
		memory.writeWord(0x18e,12);memory.writeWord(0x190,2); // KeyThresh, KeyRepThresh
		memory.writeByte(0x12f,3);                      // CPUFlag: 68030, as gestaltProcessorType and SysEnvirons say (MacBrickout 3.1 said "needs a 68020" with 0)
		memory.writeWord(0x28e,0x3fff);                 // ROM85: 0x3fff = has Color QD (bit 15 clear = 128K ROM+, bit 14 clear = color)
		memory.writeWord(0xb22,0xc000|0x0400|(M68kFpu.ENABLED?0x1000:0)); // HWCfgFlags: hwCbSCSI, hwCbClock, hwCbADB (Key Caps: KbdType is an ADB handler ID), hwCbFPU (programs that predate Gestalt look here)
		memory.writeLong(0x2a6,applZone);memory.writeLong(0x2aa,applZone); // SysZone, ApplZone
		memory.writeLong(0x2ae,rom);                    // ROMBase
		cpu.initializeTrapTable(this::defaultTrapAddress); // OS and Toolbox trap tables at $400 / $E00
		// ROM header: version $067C (the universal ROM of the 1991-93 68030/68040 Macs, matching gestaltMachineType 26 = Quadra 950).
		// QuickTime's ROM-dependent patch refuses to install (SysError 99) unless the version is one it knows.
		memory.writeWord(rom+8,0x067c);memory.writeLong(rom+0x1a,M68kMemory.ROM_SIZE); // ROM85 version word, ROM size
		memory.writeLong(0x2b2,0);                      // RAMBase
		// ExpandMem: the System 7 extension of low memory. Programs that link the old Object Support Library expect the two
		// Apple Event Manager globals at +$154 and +$17C to point at records whose long at +8 holds their handler tables.
		// Netscape 2.02's OSL also takes them as Handles: (global) -> table, whose long at +$38 it calls when non-zero. With a NIL
		// master pointer it read low memory $38 instead - vector 14, now pointing into the ROM - and jumped there on every page load.
		int expandMem=memory.allocate(0x1e8,4);memory.fill(expandMem,0x1e8,0);memory.writeWord(expandMem,0x0110);memory.writeLong(expandMem+2,0x1e8);
		for(int off:new int[]{0x154,0x17c}){int rec=memory.allocate(64,4);memory.fill(rec,64,0);int table=memory.allocate(0x200,4);memory.fill(table,0x200,0);
			memory.writeLong(rec,table);memory.writeLong(expandMem+off,rec);}
		// a System-heap Ptr, as on a real Mac: the Shared Library Manager glue that Open Transport clients link (EthernetAddress,
		// GetHTTP) checks GetPtrSize(ExpandMem) >= $110 and otherwise copies it into a fresh zeroed $110 block - which lost the
		// globals at +$10C (and the key cache, drag globals ...) for the whole VM
		api.adoptPointer(expandMem,0x1e8);
		memory.writeLong(0x2b6,expandMem);              // ExpandMem
		OpenTransport68k.install(this,expandMem);       // ExpandMem+$10C: the Shared Library Manager globals the Open Transport glue binds through
		// emDragGlobals (+$118, SysPrivateEqu.a): the Drag Manager is built in (DragManager68k), as in Mac OS 8/9. The old "Macintosh
		// Drag and Drop" extension (1.1, which the CodeWarrior 5 installer puts into Extensions) installs its own Drag Manager unless
		// these globals exist with a version word >= $100 (its 'pflp' preflight); with 0 here it patched _DragDispatch over ours and
		// called on into the unimplemented-trap stub - CodeWarrior's New Project crashed.
		int dragGlobals=memory.allocate(0x40,4);memory.fill(dragGlobals,0x40,0);memory.writeWord(dragGlobals,0x0110);memory.writeLong(expandMem+0x118,dragGlobals);
		// emKeyCache (+$0E): the KCHR in use, which Key Caps translates its key labels with (KeyTranslate); emKeyDeadState (+$0A) stays 0
		byte[] layout=SystemResources68k.keyboardLayout();keyCache=memory.allocate(layout.length,4);memory.load(keyCache,layout);memory.writeLong(expandMem+0x0e,keyCache);
		memory.writeByte(0x218,AdbManager68k.KEYBOARD_ADDRESS); // KbdLast: the ADB keyboard
		memory.writeLong(0x28a,components.globals());   // Component Manager globals (QuickTime keeps its own globals pointer at +8)
		memory.writeLong(0x2f0,30);memory.writeLong(0x2f4,32); // DoubleTime, CaretTime
		for(int i=0;i<8;i++)memory.writeByte(0xa3c+i,(i&1)==0?0xaa:0x55); // DeskPattern: the System's 'PAT ' 16 (grey)
		memory.writeLong(0xae8,-1);                     // SoundGlue: -1 until the Sound Driver glue allocates its parameter block (SoundDone reads through it otherwise)
		memory.writeByte(0x260,7);api.soundManager().initSdVolume(); // SdVolume: the output volume every VM shares (SoundMixer68k.outputVolume, full at start)
		memory.writeWord(0x210,systemFolderRef());      // BootDrive
		// The Finder launches an application with its folder as the default directory: SFSaveDisk holds the
		// negated vRefNum/WDRefNum (old programs open themselves at -SFSaveDisk) and CurDirStore its dirID.
		int defaultRef=files.fileManager.defaultVolumeRef();int defaultDir=2;try{defaultDir=files.fileManager.defaultDirectoryId();}catch(IOException e){}
		memory.writeWord(0x214,-defaultRef);            // SFSaveDisk
		memory.writeLong(0x398,defaultDir);             // CurDirStore
		memory.writeLong(0x31a,0x00ffffff);             // Lo3Bytes
		memory.writeLong(0x31e,0x400);memory.writeLong(0x322,0x2000); // MinStack, DefltStack
		// SysParam, the low-memory copy of parameter RAM: valid, printer/modem port settings, the alarm, the application font,
		// keyboard repeat, speaker volume (SPVolCtl: SoftPC stayed silent at volume 0), double-click/caret times, mouse and
		// menu blink - the values a freshly zapped PRAM gives (SPConfig belongs to AppleTalk)
		memory.writeByte(0x1f8,0xa8);memory.writeWord(0x1fc,0xcc0a);memory.writeWord(0x1fe,0xcc0a);memory.writeLong(0x200,0);
		memory.writeWord(0x204,2);memory.writeByte(0x206,0x63);memory.writeByte(0x207,0);memory.writeByte(0x208,0x03);
		memory.writeByte(0x209,0x88);memory.writeByte(0x20a,0);memory.writeByte(0x20b,0xcc); // top bit of $20B = pCDeskPat: colour desktop ('ppat' 16)
		// Key1Trans/Key2Trans: the keyboard translation vectors the Event Manager calls (key code in D2, modifiers in D1 -> the
		// character in D0); CloseView and other key remappers chain to them, and they were 0
		if(keyTransGate==0)keyTransGate=registerGate(new Gate(){@Override public void call(M68kCpu c){int m=c.d[1];
			c.d[0]=SystemResources68k.keyChar(c.d[2]&0x7f,(m&0x200)!=0,(m&0x400)!=0,(m&0x1000)!=0);}});
		memory.writeLong(0x29e,keyTransGate);memory.writeLong(0x2a2,keyTransGate);
		memory.writeWord(0x384,defaultRef);             // DefVRefNum
		memory.writeWord(0x3f6,94);                     // FSFCBLen (HFS present)
		memory.writeLong(0x824,0);                      // ScrnBase: the main device's pixels, set by quickDraw.initializeLowMemory below
		memory.writeByte(0x8f2,0);memory.writeByte(0x8f3,0); // WWExist, QDExist (0 = exists!)
		memory.writeWord(0x9dc,0xffff);                 // PaintWhite: windows are erased to white when shown (InitWindows sets it too)
		memory.writeWord(0x900,resources.applicationRefNum()); // CurApRefNum
		memory.writeLong(0x904,app!=null?app.a5:0);     // CurrentA5
		memory.writeLong(0x908,stackTop);               // CurStackBase
		writePascal(0x910,name,31);                     // CurApName
		writePascal(0xad8,"System",19);                 // SysResName: Aladdin InstallerMaker opens "System" in the blessed folder by this name
		writePascal(0x2e0,"Finder",15);                 // FinderName
		memory.writeWord(0x934,app!=null?app.jumpTableOffset:32); // CurJTOffset (an external sees HyperCard's: 32. MacroMind's PlayMovie XCMD builds the Player's jump table at A5+CurJTOffset)
		memory.writeByte(0x938,0xff);                   // HiliteMode: bit 7 set = no highlighting pending (a program clears it before an invert)
		memory.writeWord(0xda0,0xcccc);memory.writeWord(0xda2,0xcccc);memory.writeWord(0xda4,0xffff); // HiliteRGB: the highlight colour (lavender)
		memory.writeLong(0xa02,0x00010001);memory.writeLong(0xa06,0xffffffff); // OneOne, MinusOne
		memory.writeWord(0xa5a,resources.applicationRefNum()); // CurMap
		memory.writeWord(0xa5e,0xffff);                 // ResLoad: both bytes (ResourceManager68k SetResLoad)
		memory.writeWord(0xa60,0);                      // ResErr
		memory.writeWord(0x220,0);                      // MemErr
		// For a program with ResEdit's bit editor ('PACK' 1, BitEdit), bytes 6-$17 hold a 2-entry ColorTable body - ctSize 1,
		// value 0 white, value 1 black - so that a colour table read through a NIL Handle (the long at 0 stays 0) is the
		// black-and-white one. ResEdit's ICON editor hands BitEdit no table for 1-bit images, so it builds its GDevice's table
		// from it anyway; a real Mac never runs that code (NewWindow ports are basic there, and BitEdit takes its black-and-white
		// path), whereas here NewWindow ports are colour ports inside, and a table read as all zeros (black at 0, white at 1)
		// turned every drawing tool of the ICON editor inside out. BitEdit reads the value fields too (a value of no pixel
		// left the fat bits blank), so the table stays exact - for that program only. Every other program finds the bus error,
		// address error, illegal instruction and zero divide vectors there, ROM addresses as on a Mac: Aldus Installer 3.1's
		// cursor spinner, called with no spinner, reads a NIL object's fields at 6-$13; the table made it look active, with its
		// acur Handle -1 from $C, and the Gallery Effects installer crashed at its first Apple event.
		boolean nilColorTable=app!=null&&app.resources.get(ResourceFile68k.fourcc("PACK"),1)!=null;
		if(nilColorTable){
			memory.writeWord(0x6,1);memory.writeWord(0x8,0);memory.writeWord(0xa,0xffff);memory.writeWord(0xc,0xffff);memory.writeWord(0xe,0xffff);
			memory.writeWord(0x10,1);memory.writeWord(0x12,0);memory.writeWord(0x14,0);memory.writeWord(0x16,0);
		}
		// the other exception vectors (2-63) point into the ROM as a Mac's do, at an entry that stops the program as before; a
		// program that stores its own handler there gets the exception. The address shares ROMBase's high word, as on a Mac whose
		// ROM is under 64 KB into its image there: Sierra's AGI games link a StartSound glue that keeps its I/O Handle in vector 57
		// ($E4) and whose SoundDone answers "done" only while the word at $E4 equals the word at ROMBase ($2AE) - the trap-table
		// entry, 64 KB up, made every sound look busy, and Space Quest stopped each tune before its first note.
		int vectorEntry=rom+ROM_VECTOR_ENTRY;
		memory.writeWord(vectorEntry,0x4ef9);memory.writeLong(vectorEntry+2,unimplementedRomAddress); // JMP unimplemented.L
		for(int v=nilColorTable?6:2;v<64;v++)memory.writeLong(v*4,vectorEntry);
		// SoundBase ($266) and VBase ($1D4): a compact Mac's sound buffer (370 words, samples in the high bytes) and VIA
		// registers ($200 apart). 1984-87 games write samples and VIA bits straight there - with both at 0 those writes landed
		// on MemTop, Ticks and the heap pointers.
		// The VIA registers are live (MacIo68k: VIA1, VIA2 $2000 above it as on a Mac II, the SCC at SCCRd/SCCWr): MIDI
		// sequencers of 1989 (Opcode Vision) clock their music from a VIA timer interrupt and send MIDI through the SCC
		// themselves. VIA2 used to fall past a $2000-byte buffer into the heap blocks after it. The sound buffer is in the same
		// region and plays (SoundBuffer68k): MacinTalk 1 speaks through it.
		if(io==null){io=new MacIo68k(this,memory,memory.allocate(MacIo68k.LENGTH,2));memory.mapIo(io.base,MacIo68k.LENGTH,io);}
		memory.writeLong(0x266,io.soundBase());memory.writeLong(0x1d4,io.base);
		// ASCBase ($CC0): the Apple Sound Chip of a Mac II / SE/30 family machine, 4 KB (FIFO A $000, FIFO B $400, registers $800-). A program
		// that drives it directly (3D Demo, 1992: silence into both FIFOs and the volume at start-up) wrote 8 bytes apart through
		// the whole 2 KB of low memory with ASCBase 0 - trap table and vectors included. The chip itself is not emulated: the block is plain RAM.
		if(app!=null){if(ascBase==0)ascBase=memory.allocate(0x1000,2);memory.writeLong(0xcc0,ascBase);} // applications only: the small heaps of XCMDs and tests have no room to spare
		// TimeDBRA ($D00): DBRA iterations per millisecond, for programs that time short waits with a counted loop (MacinTalk 1
		// waits 1 ms at the end of each sound buffer). At the paced speed one DBRA is one instruction.
		double rate=app!=null&&!app.powerPC?app.mips():stack!=null?StackSpeed.externalMips(mips):mips;
		memory.writeWord(0xd00,(int)Math.min(0x7fff,(rate>0?rate:DEFAULT_MIPS)*1000));
		memory.writeLong(0x1d8,io.base+MacIo68k.SCC_OFFSET);memory.writeLong(0x1dc,io.base+MacIo68k.SCC_OFFSET); // SCCRd, SCCWr
		// SysFontFam / ApFontID: Chicago 0 and Geneva 3 on a U.S. System, Osaka (16384) for both on a KanjiTalk System - programs
		// take the System's script from the application font; Font2Script(ApFontID) may decide the menu language.
		boolean japaneseSystem=encoding==StackTextEncoding.JAPANESE;
		memory.writeWord(0xba6,japaneseSystem?0x4000:0);memory.writeWord(0xba8,12); // SysFontFam, SysFontSiz
		memory.writeWord(0x984,japaneseSystem?0x4000:3); // ApFontID
		memory.writeByte(0x21e,KeyboardLayout68k.KEYBOARD_TYPE); // KbdType: the keyboard whose 'KCAP' Key Caps draws
		memory.writeWord(0xc10,0);memory.writeWord(0xc12,0);memory.writeWord(0xc14,0);                     // RGBBlack
		memory.writeWord(0xc16,0xffff);memory.writeWord(0xc18,0xffff);memory.writeWord(0xc1a,0xffff);      // RGBWhite (Key Caps colours its text box with them)
		memory.writeWord(0xbaa,MacAppHost.MENU_BAR_HEIGHT); // MBarHeight
		memory.writeWord(0xa9a,-1);memory.writeWord(0xa98,-1); // ACount, ANumber: no alert yet
		memory.writeWord(0x100,0xffff);                 // MonkeyLives: negative, the Monkey is not running (MacPaint ignores Quit while it is, i.e. at >= 0)
		memory.writeWord(0x102,72);memory.writeWord(0x104,72); // ScrVRes, ScrHRes
		memory.writeLong(0xaec,app!=null?app.appParms:0); // AppParmHandle (set below)
		memory.writeLong(0x9ee,0);                      // GrayRgn (set by windows)
		memory.writeLong(0x8b0,HostQuickDraw68k.standardColors(memory)); // QDColors: the eight QuickDraw colours
		memory.writeWord(0x8ec,4);                      // CrsrThresh
		memory.writeByte(0x8cc,0xff);                   // CrsrVis
		memory.writeByte(0xcb2,1);                      // MMU32Bit: 32-bit addressing is on (QuickTime's code loader otherwise keeps its lock bit in the master pointer's high byte and strips it - a Handle above 16 MB then jumps into garbage)
		if(cursorTaskGate==0)cursorTaskGate=registerGate(new Gate(){@Override public void call(M68kCpu c) throws M68kException {cursorTask();}});
		memory.writeLong(0x8ee,cursorTaskGate);         // JCrsrTask (some programs call it after storing a new RawMouse)
		if(cursorVectorGates==null){cursorVectorGates=new int[CursorManager68k.JUMP_VECTORS.length];
			for(int i=0;i<cursorVectorGates.length;i++){final int trap=CursorManager68k.JUMP_VECTORS[i][1];
				cursorVectorGates[i]=registerGate(new Gate(){@Override public void call(M68kCpu c) throws M68kException {if(trap!=0)api.cursorManager().handle(c,trap);}});}}
		for(int i=0;i<cursorVectorGates.length;i++)memory.writeLong(CursorManager68k.JUMP_VECTORS[i][0],cursorVectorGates[i]); // JHideCursor..JCrsrObscure
		if(swapFontGate==0)swapFontGate=registerGate(new Gate(){@Override public void call(M68kCpu c) throws M68kException {quickDraw.swapFont(c);}});
		memory.writeLong(0x8e0,swapFontGate);           // JSwapFont: FMSwapFont's jump vector (HyperCard's text engine calls it directly to build the width table)
		if(gneFilterGate==0)gneFilterGate=registerGate(new Gate(){@Override public void call(M68kCpu c){}});
		memory.writeLong(JGNE_FILTER,gneFilterGate);   // JGNEFilter: an RTS until an INIT or program hooks GetNextEvent (their filters chain to it)
		updateMouse(true);
		if(app!=null)buildAppParms();
		windows.initializeLowMemory();
		menus.initializeLowMemory();            // SystemMenuList
		dialogs.initializeLowMemory();          // DABeeper
		api.scrapManager().initializeLowMemory(); // ScrapInfo at $960
		devices.initializeLowMemory();
		appleTalk.initializeLowMemory();
		files.writeVolumeControlBlocks();
		resources.describeOpenFiles();
		quickDraw.initializeLowMemory();
		updateTicks();
		memory.writeLong(0xa8c,0);                      // ResumeProc
		cpu.snapshotVectors();                          // from here on a changed exception vector is the program's own handler
	}

	/** A Finder alias file: kIsAlias (bit 15 of fdFlags) in its Finder info. */
	static boolean isAliasFile(File f){
		if(f==null||!f.isFile())return false;
		byte[] info=FinderInfo68k.stored(f);return info!=null&&info.length>=10&&(info[8]&0x80)!=0;
	}

	private void buildAppParms() throws M68kCpuException {
		// A high-level-event-aware program (SIZE $0040) has no application parameters at all: AppParmHandle is NIL and its
		// documents come only in the Open Documents event (measured, System 7.5.3 Finder). With both, AtariVD Editor opened a
		// dropped disk twice (CountAppFiles and its 'odoc' handler).
		if(app.hosted==null&&(app.sizeFlags()&0x0040)!=0){app.appParms=0;memory.writeLong(0xaec,0);return;}
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.write(0);out.write(0); // message: appOpen
		int count=app.documents.size();out.write(count>>>8);out.write(count);
		for(File document:app.documents){
			int vRefNum=files.workingDirectoryFor(document.getAbsoluteFile().getParentFile());
			out.write(vRefNum>>>8);out.write(vRefNum);
			int type=files.fileType(document);
			out.write(type>>>24);out.write(type>>>16);out.write(type>>>8);out.write(type);
			out.write(0);out.write(0); // versNum is an INTEGER (a single byte here shifted the name: Animation Toolkit read "Fish" as 'ish...' and failed with -37)
			byte[] name=encoding.encodeReplacing(files.fileManager.programName(document.getName()));int len=Math.min(255,name.length);
			out.write(len);out.write(name,0,len);
			if(((1+len)&1)!=0)out.write(0);
		}
		app.appParms=api.createHandle(out.toByteArray());
		memory.writeLong(0xaec,app.appParms);
	}

	// ------------------------------------------------------------------ dispatch

	/** Handles Toolbox/OS traps; returns false for the few served by {@link Mac68kApi} itself. */
	static final long SLOW_TRAP_NANOS=Mac68kApi.SLOW_TRAP_NANOS;
	boolean handle(M68kCpu cpu,int opcode,int instructionPc) throws M68kException {
		if(this.cpu!=cpu)attach(cpu);
		boolean hashProbe=M68kMemory.ADDR_PROBE!=null&&M68kMemory.ADDR_PROBE[1]-M68kMemory.ADDR_PROBE[0]<=0x10000; // hashing a large probed range at every trap would stall the program
		long before=hashProbe?memory.rangeHash(M68kMemory.ADDR_PROBE[0],M68kMemory.ADDR_PROBE[1]):0;
		try{return dispatch(cpu,opcode,instructionPc);}
		finally{quickDraw.flushSurfaces(); // Java2D drawing staged for 16-bit/indexed window buffers lands before the program looks
			if(memory.deviceWritten()&&hosted()&&app!=null)host().publishWindowDrawing(); // a program that writes the screen memory itself also reads its windows' pixels there
			if(hashProbe&&memory.rangeHash(M68kMemory.ADDR_PROBE[0],M68kMemory.ADDR_PROBE[1])!=before)System.err.println("[68K] probed range changed during trap $"+Integer.toHexString(opcode)+" "+trapName(opcode)+" at pc $"+Integer.toHexString(instructionPc));}
	}
	/**
	 * Toolbox traps outside QuickDraw and the Window Manager that draw into the current port, so into the card window: Tbx's
	 * "TextBox" (TETextBox alone in its call) never showed, the card snapshot being taken only for QuickDraw/Window Manager
	 * traps. TETextBox, TEUpdate, PlotIcon, PlotCIcon, IconDispatch (PlotIconID...), DrawControls, Draw1Control, UpdateControls,
	 * DrawDialog, UpdateDialog, the List Manager (LUpdate/LDraw...).
	 */
	private static final java.util.Set<Integer> CARD_DRAWING_TRAPS=java.util.Set.of(0x9ce,0x9d3,0x94b,0x81f,0xbc9,0x969,0x96d,0x953,0x981,0x978,0x9e7);
	private boolean dispatch(M68kCpu cpu,int opcode,int instructionPc) throws M68kException {
		boolean toolbox=(opcode&0x0800)!=0;
		int trap=toolbox?opcode&0x0fff:opcode&0xff;
		// GetPixel reads the card too (ASCII-art's thePixel XFCN): it saw the screen before the card was in it
		if(toolbox&&cardWindow!=null&&(QuickDraw68k.name(trap)!=null||WindowManager68k.name(trap)!=null||trap==0x865||CARD_DRAWING_TRAPS.contains(trap)))cardWindow.snapshot();
		if(toolbox){
			switch(trap){
				case 0x9f0:return loadSeg(cpu,instructionPc);
				case 0x9f1:return unloadSeg(cpu);
				case 0x9f2:return launch(cpu);                   // Launch / LaunchApplication
				case 0x9f3:return launch(cpu);                   // Chain
				case 0x9f5:return getAppParms(cpu);
				case 0x9f8:return methodDispatch(cpu,instructionPc);
				case 0x815:return scsiDispatch(cpu);
				case 0x82d:return editionDispatch(cpu);
				case 0x9f4:if(app==null)throw new M68kCpuException(instructionPc,"ExitToShell called by an external");files.ejectAddedDrives(cpu);if(Mac68kApi.TRACE)System.err.println("[68K] ExitToShell from $"+Integer.toHexString(instructionPc)+"\n"+cpu.dumpState(instructionPc)+describeSegments());if(Boolean.getBoolean("hz68k.tmpexitdump")&&app.ppc!=null)System.err.println("TMPEXIT "+app.ppc.describe(app.ppc.cpu.lastPc()));throw new ExitToShell();
			case 0x9ff:return debugger(cpu,false);
				case 0xbff:return debugger(cpu,true);
				case 0x9c9:return sysError(cpu);
				case 0x9e5:cpu.a[7]+=2;return true;              // InitPack
				case 0x9e9:return diskInit(cpu);                 // Pack2 (Disk Initialization)
				case 0x9e6:return true;                          // InitAllPacks
				case 0x895:{ // ShutDown(selector): ShutDwnPower/ShutDwnStart end the program like ExitToShell, ShutDwnInstall(proc, flags)
					// and ShutDwnRemove(proc) pop their arguments: the procedures would only run when the Mac shuts down
					int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
					if(selector==3)cpu.a[7]+=6;else if(selector==4)cpu.a[7]+=4;
					else if((selector==1||selector==2)&&app!=null)throw new ExitToShell();
					return true;}
				case 0x8b5:return scriptUtil(cpu);
				case 0x9ed:return pack6(cpu);
				case 0x9ec:return pack5(cpu);
				case 0x823:return aliasDispatch(cpu);
				case 0x88f:return osDispatch(cpu);
				case 0x9c6:return secondsToDate(cpu);
				case 0x9c7:return dateToSeconds(cpu);
				case 0x96f:return enqueue(cpu);
				case 0x96e:return dequeue(cpu);
				case 0x906:return newString(cpu);
				case 0x907:return setString(cpu);
				case 0x9e3:return ptrToHand(cpu);
				case 0x9e2:return ptrToXHand(cpu);
				case 0x9e4:return handAndHand(cpu);
				case 0x9ef:return ptrAndHand(cpu);
				case 0x8fd:return print.dispatch(cpu);
				case 0xbed:return drag.dispatch(cpu);
				case 0xbf2:return threads.dispatch(cpu);         // ThreadDispatch
				case 0xbeb:return displayDispatch(cpu);          // DisplayDispatch (Display Manager)
				case 0xa54:return textServices.dispatch(cpu);    // TSMDispatch
				case 0x8cf:return packBits(cpu);
				case 0x8d0:return unpackBits(cpu);
				case 0x868:return fixMul(cpu);
				case 0x869:return fixRatio(cpu);
				case 0x86c:return fixRound(cpu);
				case 0x867:return longMul(cpu);
				case 0x83f:return long2Fix(cpu);
				case 0x840:return fix2Long(cpu);
				case 0x841:return fix2Frac(cpu);
				case 0x842:return frac2Fix(cpu);
				case 0x844:return x2Fix(cpu,true);
				case 0x846:return x2Fix(cpu,false);
				case 0x843:return fix2X(cpu,true);
				case 0x845:return fix2X(cpu,false);
				case 0x847:return fracTrig(cpu,false);
				case 0x848:return fracTrig(cpu,true);
				case 0x849:return fracSqrt(cpu);
				case 0x84a:return fracMul(cpu);
				case 0x84b:return fracDiv(cpu);
				case 0x84d:return fixDiv(cpu);
				case 0x818:return fixATan2(cpu);
				case 0x8bc:return slopeFromAngle(cpu);
				case 0x8c4:return angleFromSlope(cpu);
				case 0x94f:return deltaPoint(cpu);
				case 0x9b4:desk.systemTask(cpu);return systemTask(cpu); // SystemTask: desk accessories' accRun
				case 0x9b2:case 0x9b3:case 0x9c2:case 0x9b6:case 0x9b7:return desk.handle(cpu,trap); // SystemEvent, SystemClick, SystemEdit, OpenDeskAcc, CloseDeskAcc
				case 0x9c3:return keyTranslate(cpu);
				case 0xa52:return files.highLevelDispatch(cpu);
				case 0x82a:return components.dispatch(cpu);
				case 0x829:return layerDispatch(cpu);
				case 0x82b:return pack9(cpu);
				case 0xbf1:return gestaltValueDispatch(cpu);
				case 0xaa3:if(guestQuickTime)throw new M68kMacApiException(cpu.pc,opcode,"Image Compression Manager: the installed QuickTime extension did not install the trap");return imageCompression.handle(cpu);
				case 0xaaa:if(guestQuickTime)throw new M68kMacApiException(cpu.pc,opcode,"QuickTime Movie Toolbox: the installed QuickTime extension did not install the trap");return quickTime.dispatch(cpu);
				case 0x816:return appleEvents.pack8(cpu);
				case 0xa73:case 0xa74:case 0xa75:return appearance.dispatch(cpu,trap); // ControlDispatch, AppearanceDispatch, IconServicesDispatch
				case 0xadb:return cursorDevices.dispatch(cpu);
				case 0xa72:return contextualMenuDispatch(cpu);
				case 0xa59:return mixedModeDispatch(cpu);
				case 0xa5a:return codeFragmentDispatch(cpu);
				case 0x824:return hfsUtilDispatch(cpu);
				case 0xbf6:case 0xbee:case 0xbfc:case 0xa80:case 0xa7e:case 0xa56:case 0xbe0:case 0x82f:if(managers.dispatch(cpu,trap))return true;break; // Collection Manager, ColorSync, Translation Manager, AVL trees, DebugLib, speech recognition, cursor components, Data Access Manager
				case 0x9ea:return standardFile.pack3(cpu);
				case 0x9e7:return listManager.pack0(cpu);
				case 0x82e:return quickDraw.pack12(cpu);
				case 0x830:return help.dispatch(cpu);
				case 0x831:return quickDraw.pack15(cpu);
				case 0xbc0:{ // private QuickDraw dispatch (D0 = selector): QuickTime's OpenCPort patch calls selector 9 with the new port's grafVars Handle; nothing to do here
					int selector=cpu.d[0]&0xffff;if(Mac68kApi.TRACE)System.err.println("[68K] private dispatch $ABC0 selector "+selector+" arg $"+Integer.toHexString(memory.readLong(cpu.a[7])));
					cpu.a[7]+=4;cpu.d[0]=0;return true;}
				case 0x861:{ // Random: qd.randSeed (RndSeed before InitGraf), kept in step with the low-memory copy
					int seedAddress=api.quickDraw().randSeedAddress();int seed=memory.readLong(seedAddress);seed=(int)((seed*16807L)%2147483647L);if(seed==0)seed=1;memory.writeLong(seedAddress,seed);memory.writeLong(0x156,seed);
					int value=seed&0xffff;if(value==0x8000)value=0;memory.writeWord(cpu.a[7],value);cpu.d[0]=(short)value;return true;} // also in D0 like the ROM's handler (Dark Castle drops the stack result and uses D0)
				default:break;
			}
			if(events.handle(cpu,trap))return true;
			if(windows.handle(cpu,trap))return true;
			if(menus.handle(cpu,trap))return true;
			if(controls.handle(cpu,trap))return true;
			if(textEdit.handle(cpu,trap))return true;
			if(dialogs.handle(cpu,trap))return true;
			if(resources.handle(cpu,trap))return true;
			if(quickDraw.handle(cpu,trap))return true;
			return false;
		}
		// OS traps
		if(files.handle(cpu,opcode))return true;
		if(trap>=0x77&&trap<=0x7c)return adb.handle(cpu,trap);
		switch(trap){
			case 0x3d:return devices.drvrInstall(cpu);           // DrvrInstall / DrvrInstallReserveMem ($A43D)
			case 0x3e:return devices.drvrRemove(cpu);
			case 0x4e:return files.addDrive(cpu);                // AddDrive
			case 0x46:return getTrapAddress(cpu,opcode);
			case 0x47:return setTrapAddress(cpu,opcode);
			case 0xfc:return readResourceData(cpu);
			case 0xad:return gestalt(cpu,opcode);
			case 0x90:return sysEnvirons(cpu);
			case 0x3b:return delay(cpu);
			case 0x8b:if(dialogs.commToolboxDispatch(cpu)||ctb.dispatch(cpu))return true; // _CommToolboxDispatch: the DITL utilities, then the Communications Toolbox
				System.err.println("[68K "+name+"] CommToolboxDispatch selector $"+Integer.toHexString(memory.readWord(cpu.a[0])&0xffff)+" ignored");cpu.d[0]=-1;return true;
			case 0xdd:{ // _PPC (PPC Toolbox; the call is D0: PPCInit 0, PPCOpen 1, ...; A0 = PPCParamBlock): no program linking here.
				// PPCInit succeeds so browsers that only initialize it can continue. PPCInit's glue (moveq #0,d0) leaves A0 as it was:
				// Microsoft OLE 2's PPCInit failed on a stray csCode. IPCListPorts lists this machine's programs (Rinaldi's PPCList:
				// "the apple-event aware applications"); ports open and close (ppcPorts), no session ever starts.
				int pb=cpu.a[0],code=cpu.d[0]&0xffff;boolean async=(opcode&0x400)!=0;
				int result=code==0?NO_ERR:pb==0?-50:code==0x0a?ppcListPorts(pb):ppcPortCall(cpu,code,pb,async);
				if(result==1){cpu.d[0]=NO_ERR;return true;} // pending (an asynchronous PPCInform): ioResult stays 1
				if(pb!=0&&code!=0)memory.writeWord(pb+16,result);cpu.d[0]=result;
				if(async&&pb!=0&&code!=0)files.completion(cpu,pb);
				return true;}
			case 0x30:return events.osEvent(cpu,false);          // OSEventAvail
			case 0x31:return events.osEvent(cpu,true);           // GetOSEvent
			case 0x58:return insTime(cpu,(opcode&0x400)!=0);      // InsTime / InsXTime $A458
			case 0x59:return rmvTime(cpu);
			case 0x5a:return primeTime(cpu);
			case 0x33:case 0x6f:return vInstall(cpu);           // VInstall / SlotVInstall $A06F (slot number in D0 ignored: one screen)
			case 0x34:case 0x70:return vRemove(cpu);            // VRemove / SlotVRemove $A070
			case 0x71:case 0x72:cpu.d[0]=NO_ERR;return true;    // AttachVBL / DoVBLTask: one screen, slot VBLs are the VBL queue
			case 0x82:return dtInstall(cpu);                    // DTInstall
			// Start Manager (IM V-352): the one built-in screen is on-board video (slot 0, sResource $80), start-up from the
			// first SCSI drive (sdRefNum -33), Mac OS. Monkey Island and Silicon Volleyball read the video default first.
			case 0x7d:memory.writeWord(cpu.a[0],0);memory.writeWord(cpu.a[0]+2,-33);return true; // GetDefaultStartup (DefStartRec)
			case 0x80:memory.writeByte(cpu.a[0],0);memory.writeByte(cpu.a[0]+1,0x80);return true; // GetVideoDefault (DefVideoRec)
			case 0x84:memory.writeWord(cpu.a[0],0);memory.writeWord(cpu.a[0]+2,1);return true; // GetOSDefault (DefOSRec: sdOSType 1)
			case 0x7e:case 0x81:case 0x83:return true;          // SetDefaultStartup/SetVideoDefault/SetOSDefault: PRAM stays as it is
			// HoldMemory/UnholdMemory/LockMemory/UnlockMemory...: no virtual memory. ProtectMemory (6) / UnprotectMemory (7)
			// (A0 address, A1 count) write-protect pages for PowerPC stores (M68kMemory.protect)
			case 0x5c:if(cpu.d[0]==6||cpu.d[0]==7)memory.protect(cpu.a[0],cpu.a[1],cpu.d[0]==6);cpu.d[0]=NO_ERR;return true;
			case 0x6e:return slotManager(cpu);                  // SlotManager: the built-in video's sResource in slot 0, no NuBus cards
			case 0x5e:return nmInstall(cpu);
			case 0x5f:pendingNotifications.remove(Integer.valueOf(cpu.a[0]));cpu.d[0]=NO_ERR;return true; // NMRemove
			case 0x1a:if(currentZone==applZone)writeApplZoneRecord();cpu.a[0]=currentZone;return true; // GetZone
			case 0x1b:currentZone=cpu.a[0];memory.writeLong(0x118,currentZone);return true; // SetZone (TheZone)
			case 0x19:return initZone(cpu);
			case 0x1d:cpu.d[0]=privateZones.containsKey(currentZone)?privateZoneFree(currentZone):reportedFree(api.guestAvailable(memory.largestFreeBlock()));return true; // MaxMem (A0 = grow)
			case 0x1c:cpu.d[0]=privateZones.containsKey(currentZone)?privateZoneFree(currentZone):reportedFree(api.guestAvailable(memory.availableBytes()));return true;   // FreeMem
			case 0x26:case 0x48:cpu.a[0]=zoneOf(cpu.a[0]);cpu.d[0]=NO_ERR;memory.writeWord(0x220,NO_ERR);return true; // HandleZone / PtrZone: D0 = MemErr (MPW Shell 3.1 checks it before every DisposHandle and drops into DebugStr "bad handle" when nonzero)
			case 0x4b:growZoneProc=cpu.a[0];cpu.d[0]=NO_ERR;return true; // SetGrowZone
			case 0x4c:cpu.d[0]=reportedFree(api.guestAvailable(memory.largestFreeBlock()));return true; // CompactMem
			case 0x4d:cpu.d[0]=NO_ERR;return true;               // PurgeMem
			case 0x40:cpu.d[0]=NO_ERR;api.memErr(NO_ERR);return true; // ResrvMem
			case 0x61:cpu.d[0]=reportedFree(api.guestAvailable(memory.largestFreeBlock()));return true; // MaxBlock
			case 0x62:cpu.d[0]=reportedFree(api.guestAvailable(memory.availableBytes()));cpu.a[0]=reportedFree(api.guestAvailable(memory.largestFreeBlock()));return true; // PurgeSpace
			case 0x63:writeApplZoneRecord();cpu.d[0]=NO_ERR;api.memErr(NO_ERR);return true; // MaxApplZone (bkLim = ApplLimit)
			// SetApplLimit(A0): ApplLimit follows it (the zone here already spans up to HeapEnd, so it is only recorded); below the
			// zone's end - its bkLim as the zone record shows it - it is memFullErr. Excel 2.2's loader sets its limit to SP minus the
			// stack it needs and quits with "not enough memory" unless ApplLimit then lies above HeapEnd. The zone record's bkLim stays
			// within 16 MB of the heap (writeApplZoneRecord), below HeapEnd in the large world: a program lowering its limit a little
			// for more stack at startup - TESample's SetApplLimit(GetApplLimit() - 32K), まきがめ 2.17 - is not refused there
			// (it alerted "Application Memory Size is too small" on memFullErr), as a real Mac's still small zone did not refuse it.
			case 0x2d:{int limit=memory.addressing24?cpu.a[0]&0xffffff:cpu.a[0];int heapEnd=memory.readLong(0x114);
				int zoneEnd=memory.readLong(applZone);if(zoneEnd<=applZone||Integer.compareUnsigned(zoneEnd,heapEnd)>0)zoneEnd=heapEnd;
				if(Integer.compareUnsigned(limit,zoneEnd)<0){cpu.d[0]=MEM_FULL_ERR;api.memErr(MEM_FULL_ERR);return true;}
				if(app!=null&&app.mpwShell&&Integer.compareUnsigned(limit,applZone+MPW_PARTITION)>0)limit=applZone+MPW_PARTITION; // MPW Shell sets it from its stack at start-up: kept to the partition (initializeLowMemory)
				memory.writeLong(0x130,limit);cpu.d[0]=NO_ERR;api.memErr(NO_ERR);return true;}
			case 0x64:cpu.d[0]=api.moveHandleHigh(cpu.a[0]);return true; // MoveHHi
			case 0x57:cpu.d[0]=NO_ERR;api.memErr(NO_ERR);return true; // SetApplBase
			case 0xa4:return heapDispatch(cpu);                  // HeapDispatch (private Memory Manager entry used by QuickTime's loader)
			case 0x9d:cpu.d[0]=NO_ERR;return true;               // $A09D: private trap QuickTime's loader applies to freshly allocated code Handles; nothing to do here
			case 0x69:return hGetState(cpu);
			case 0x6a:return hSetState(cpu);
			case 0x67:case 0x68:{int h=cpu.a[0];api.setResourceBit(h,trap==0x67);cpu.d[0]=h==0?NIL_HANDLE_ERR:NO_ERR;api.memErr((short)cpu.d[0]);return true;} // HSetRBit/HClrRBit
			case 0x28:return recoverHandle(cpu);
			case 0x20:if((opcode&0x0800)==0)return setPtrSize(cpu);return false;
			case 0x39:{int now=(int)(macNow()+ownClockShift);memory.writeLong(0x20c,now);if(cpu.a[0]!=0)memory.writeLong(cpu.a[0],now);cpu.d[0]=0;return true;} // ReadDateTime(A0 = VAR secs): Time and secs set, D0 = noErr (the glue returns D0 as the result)
			case 0x3a:setProgramClock((cpu.d[0]&0xffffffffL)-ownClockShift);lastWrittenTime=Long.MIN_VALUE;memory.writeLong(0x20c,(int)(macNow()+ownClockShift));cpu.d[0]=NO_ERR;return true; // SetDateTime(D0 = secs): the VM clock only (setProgramClock), from this program's own clock
			case 0x3f:cpu.d[0]=NO_ERR;return true;               // InitUtil
			case 0x38:cpu.d[0]=NO_ERR;return true;               // WriteParam
			case 0x51:case 0x52:return xpRam(cpu,trap==0x52);    // ReadXPRam/WriteXPRam
			case 0x5d:if(app!=null){host().directScreenAccess(api.quickDraw());host().swapMMUMode();}return true; // SwapMMUMode: programs call it before (and after) writing to screen memory
			case 0xbd:return true; // FlushCodeCache: no result, every register kept - programs JSR its table entry ($6F4, jCacheFlush) in the middle of work (Alliance Power Tools' 'dcmp' 128 keeps its decryption count in D0 across it)
			case 0x98:cpu.d[0]=NO_ERR;return true;               // HWPriv
			case 0x94:memory.writeWord(cpu.a[0]+16,PARAM_ERR);cpu.d[0]=PARAM_ERR;return true; // _ServerDispatch: no AppleShare server here (Server Sleuth says so itself)
			// DebugUtil with no debugger: DebuggerGetMax says 8; EnterSupervisorMode (8) answers the SR it found - code here runs in
			// supervisor mode already, as on a Mac without virtual memory (SoftwareFPU's panel stopped at it); the rest do nothing
			case 0x8d:{int selector=cpu.d[0];cpu.d[0]=selector==0?8:selector==8?cpu.sr&0xffff:selector>=1&&selector<=7?NO_ERR:PARAM_ERR;return true;}
			case 0x91:cpu.d[0]=cpu.d[0]&0x00ffffff;return true;  // Translate24To32
			case 0x54:return uprString(cpu,opcode);
			case 0x56:return lwrString(cpu,opcode);
			case 0x3c:return cmpString(cpu,opcode);
			case 0x93:{long micros=(System.nanoTime()-startNanos)/1000L+60_000_000L; // Microseconds: since "boot", as on a real Mac
				// The glue (Timer.h: _Microseconds; MOVEA.L (SP)+,A1; MOVE.L A0,(A1)+; MOVE.L D0,(A1)) stores A0 as UnsignedWide.hi and D0 as .lo.
				// With the two swapped QuickTime's clock read the low half as the high one: a movie's time base jumped to its end at once.
				cpu.a[0]=(int)(micros>>>32);cpu.d[0]=(int)micros;return true;}
			case 0x2f:{boolean queued=events.postApplicationEvent(cpu.a[0]&0xffff,cpu.d[0]);cpu.d[0]=queued?NO_ERR:1;if((opcode&0x100)!=0)cpu.a[0]=events.lastPostedElement;return true;} // PostEvent: evtNotEnb (1) for a type SysEvtMask leaves out; PPostEvent ($A12F) answers the queue element in A0 (Caliban's PostClick writes its click point there)
			case 0x32:{events.flush(cpu.d[0]&0xffff);cpu.d[0]=NO_ERR;return true;} // FlushEvents
			case 0x9e:cpu.d[0]=NO_ERR;return true;               // PowerDispatch etc.
			case 0x85:case 0x8a:case 0x89:if(managers.dispatchOs(cpu,opcode))return true;break; // IdleUpdate/IdleState/SerialPower, SleepQInstall/Remove, SCSIAction (not _PMgrOp/_Sleep themselves)
			default:break;
		}
		return false;
	}

	// ------------------------------------------------------------------ segment loader

	/**
	 * _SCSIDispatch ($A815), the original SCSI Manager: a bus with no devices on it. The selector word is on the stack
	 * (SCSIReset pushes 0); each routine pops its own arguments and returns an OSErr (SCSIStat: the bus status, 0).
	 * Loony Labyrinth probes the bus for its CD; a missing trap stopped it.
	 */
	private boolean scsiDispatch(M68kCpu cpu) throws M68kException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		int args,result;final int SC_COMM_ERR=2,SC_SEQUENCE_ERR=8;
		switch(selector){
			case 0:args=0;result=0;break;                  // SCSIReset
			case 1:args=0;result=0;break;                  // SCSIGet: the bus is ours
			case 2:case 11:args=2;result=SC_COMM_ERR;break; // SCSISelect/SCSISelAtn(targetID): nobody answers
			case 3:args=6;result=SC_SEQUENCE_ERR;break;    // SCSICmd(buffer, count)
			case 4:args=12;result=SC_SEQUENCE_ERR;break;   // SCSIComplete(VAR stat, VAR message, wait)
			case 5:case 6:case 8:case 9:case 12:args=4;result=SC_SEQUENCE_ERR;break; // SCSIRead/Write/RBlind/WBlind(tib), SCSIMsgIn(VAR message)
			case 10:args=0;result=0;break;                 // SCSIStat: bus free
			case 13:args=2;result=SC_SEQUENCE_ERR;break;   // SCSIMsgOut(message)
			default:throw new M68kMacApiException(cpu.pc,0xa815,"SCSIDispatch selector "+selector);
		}
		cpu.a[7]+=args;memory.writeWord(cpu.a[7],result);return true;
	}

	/** _Pack11 ($A82D), the Edition Manager: Publish & Subscribe (EditionManager68k). Gestalt 'edtn' says it is present (MacApp 3 requires it). */
	private boolean editionDispatch(M68kCpu cpu) throws M68kException {
		if(packageEntry(0xa82d)!=0)return false; // a program's own PACK 11 runs instead
		return editions.dispatch(cpu);
	}

	/** MacApp/Object Pascal's class-info globals: the error routine (%_OBJERROR) and a pointer to the pointer to the table of
	 *  superclass indexes, both set by the program's own %_OPTINITOBJ/%_INITOBJ on a 128K-or-later ROM. */
	private static final int METHOD_ERROR=0xbe8,METHOD_CLASS_TABLE=0xbec;

	/**
	 * _MethodDispatch ($A9F8), the Object Pascal / C+- method dispatcher (Photoshop 1.0 and other MacApp 1 programs). Programs
	 * do not call it with the A-line trap: %_OPTINITOBJ stores its GetTrapAddress($1F8) address, and each virtual method's
	 * selector proc is {@code JSR dispatcher} followed by its dispatch table: a count word, a cache entry and
	 * {class index, offset} entries sorted by descending class index. The object (SELF, a Handle whose block starts with
	 * its class index) is the argument pushed last, just above the caller's return address. The method found for the
	 * object's class or its nearest superclass (from the table at *$BEC) is jumped to with the stack as the caller left it:
	 * an even offset is an A5 jump-table offset, an odd one (bit 0 cleared) is relative to the entry + 2. Behaves like the
	 * software dispatcher MPW's ObjLib carries for 64K ROMs (the cache entry remembers the last class and its offset).
	 */
	private boolean methodDispatch(M68kCpu cpu,int instructionPc) throws M68kException {
		if(!cpu.isTrapRomAddress(instructionPc))throw new M68kMacApiException(instructionPc,0xa9f8,"MethodDispatch called as an A-line trap (it is reached by a JSR to its trap address)");
		int table=cpu.romReturnAddress(); // the selector proc's inline dispatch table
		int self=memory.readLong(cpu.a[7]+4),classIndex=memory.readWord(memory.readLong(self));
		int count=memory.readWord(table),entry=table+2,cache=entry;
		if(memory.readWord(cache)!=classIndex){
			memory.writeWord(cache,classIndex);
			int d1=count,found=-1,cls=classIndex;
			// first pass: skip the entries of higher class indexes (DBCC D1: stop at an entry <= the class or when the count runs out)
			while(true){entry+=4;int e=memory.readWord(entry);if(cls>=e){if(cls==e)found=entry;break;}d1=(d1-1)&0xffff;if(d1==0xffff)break;}
			if(found<0){
				int supers=memory.readLong(memory.readLong(METHOD_CLASS_TABLE));
				for(int guard=0;found<0;guard++){
					cls=memory.readWord(supers+(short)cls); // the superclass
					if(cls==0||guard>1_000_000){ // no class up the chain has the method: the program's error routine
						memory.writeLong(cache,0);int error=memory.readLong(METHOD_ERROR);
						if(error==0)throw new M68kMacApiException(instructionPc,0xa9f8,"MethodDispatch: no method for class "+classIndex+" and no error routine at $BE8");
						cpu.a[1]=cache;cpu.pc=error;return true;}
					while(true){int e=memory.readWord(entry);if(cls>=e){if(cls==e)found=entry;break;}d1=(d1-1)&0xffff;if(d1==0xffff)break;entry+=4;}
				}
			}
			memory.writeWord(cache+2,memory.readWord(found+2));
			entry=found;
		}
		int offset=memory.readWord(entry+2);cpu.a[0]=entry;cpu.d[0]=offset&~1;
		cpu.pc=(offset&1)==0?cpu.a[5]+(short)offset:entry+2+(short)(offset&~1);
		return true;
	}

	private boolean loadSeg(M68kCpu cpu,int instructionPc) throws M68kException {
		if(app==null)return externalLoadSeg(cpu,instructionPc);
		// A far-model entry is [seg.w][_LoadSeg][offset.l]: the trap is reached without a pushed segment number, and the
		// return address is entry+4; a near-model entry [offset.w][MOVE.W #seg,-(SP)][_LoadSeg] returns to entry+8.
		int returnAddress=cpu.isTrapRomAddress(instructionPc)?cpu.romReturnAddress():instructionPc+2;
		int jtStart=jumpTableStart(cpu),jtEnd=jumpTableEnd(jtStart);
		boolean far=returnAddress-4>=jtStart&&returnAddress<=jtEnd&&((returnAddress-4-jtStart)&7)==0&&memory.readWord(returnAddress-6)!=0x3f3c;
		int segment;
		if(far)segment=(short)memory.readWord(returnAddress-4);
		else{segment=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;}
		// CODE 0 is the jump table, not a segment: DAE preloads every CODE resource by GetResInfo ID, 0 included; its
		// header (above-A5 size, below-A5 size) read as [first entry][count] rewrote thousands of entries
		if(segment==0)return true;
		ResourceFile68k.Resource found=jtStart==app.a5+app.jumpTableOffset?null:resources.find(ResourceFile68k.fourcc("CODE"),segment);
		if(found!=null&&found!=app.resources.get(ResourceFile68k.fourcc("CODE"),segment))loadForeignSegment(found,segment,jtStart,jtEnd);
		else loadSegment(segment,jtStart,jtEnd);
		if(far){int entry=returnAddress-4;if(memory.readWord(entry+2)==0x4ef9)cpu.pc=entry+2;return true;}
		// The trap sits at the end of a jump-table entry: [offset][MOVE.W #seg,-(SP)][_LoadSeg], so the return
		// address is entry+8. Like the ROM, find the entry from the return address rather than the trap's own PC:
		// a program's LoadSeg patch (some applications pre-load segments) chains to the original with JMP through its
		// GetTrapAddress address, leaving the entry's return address on the stack.
		// After loading, the entry reads [seg][JMP address]; continue from the JMP.
		int entry=returnAddress-8;
		if((entry>=jtStart&&entry<jtEnd||isWrappedEntry(entry,jtStart))&&memory.readWord(entry+2)==0x4ef9)cpu.pc=entry+2;
		// Reached through its trap address from outside the jump table: the ROM backs the return address up by 6 whatever
		// it points at (the JMP of a jump-table entry). MacApp 3's segment preloader relies on it - PEA ret(PC); JMP (A0)
		// with ret 6 bytes past the code that marks the load as done; returning to ret itself (mid-instruction) made
		// Blackjack Solitaire report "not enough memory".
		else if(cpu.isTrapRomAddress(instructionPc))cpu.pc=returnAddress-6;
		return true;
	}

	/**
	 * LoadSeg in an external's VM (no application here): the ROM's way - CODE through the resource chain, entries patched at
	 * A5 + CurJTOffset. MacroMind's PlayMovie XCMD runs the "MacroMind Player" file's segments with an A5 world of its own
	 * (Cosmic Osmo, Spelunx).
	 */
	private boolean externalLoadSeg(M68kCpu cpu,int instructionPc) throws M68kException {
		int returnAddress=cpu.isTrapRomAddress(instructionPc)?cpu.romReturnAddress():instructionPc+2;
		int jtStart=cpu.a[5]+(short)memory.readWord(0x934),jtEnd=jtStart+0x40000;
		int segment=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		if(segment==0)return true;
		ResourceFile68k.Resource found=resources.find(ResourceFile68k.fourcc("CODE"),segment);
		// the ROM takes segments from the application's file (CurApRefNum): PlayMovie points it at the Player, whose file is
		// above the current one once HyperCard's file is current again
		if(found==null)found=resources.findInOpenFiles((short)memory.readWord(0x900),ResourceFile68k.fourcc("CODE"),segment);
		if(found==null)throw new M68kCpuException(instructionPc,"CODE "+segment+" resource is missing");
		loadForeignSegment(found,segment,jtStart,jtEnd);
		int entry=returnAddress-8;
		if(memory.readWord(entry+2)==0x4ef9)cpu.pc=entry+2;
		else if(cpu.isTrapRomAddress(instructionPc))cpu.pc=returnAddress-6;
		return true;
	}

	/**
	 * The jump table LoadSeg and UnloadSeg work on: register A5 + CurJTOffset, as in the ROM - not the table the
	 * launcher built. Some application stubs expand their compressed CODE 0 into a heap
	 * block, points A5 at that block while it preloads every segment through the block's entries, and restores A5
	 * afterwards; the launcher's table (built from the compressed bytes) is rewritten by the stub itself.
	 */
	private int jumpTableStart(M68kCpu cpu) throws M68kCpuException {int offset=memory.readWord(0x934);return cpu.a[5]+(offset>0?offset:app.jumpTableOffset);}
	private int jumpTableEnd(int jtStart){return jtStart==app.a5+app.jumpTableOffset?jtStart+app.jumpTableSize:jtStart+0x40000;} // a relocated table: the entry checks bound it
	/**
	 * The address of the jump-table entry {@code offset} bytes into the table at {@code jtStart}. Like the ROM, the
	 * application's own table is addressed by a 16-bit A5 displacement (CurJTOffset + offset as a signed word): the MPW
	 * Linker's -wrap option lets a table larger than 32K continue below A5, in the application globals, where the
	 * program's calls (JSR d16(A5)) find those entries. Strata StudioPro 1.1's CODE 66 lists its entries from $8C10.
	 */
	private int jumpTableEntry(int jtStart,int offset){
		if(app==null||jtStart!=app.a5+app.jumpTableOffset)return jtStart+offset;
		int displacement=app.jumpTableOffset+offset;
		return displacement>=0x8000&&displacement<0x10000?app.a5+(short)displacement:jtStart+offset;
	}
	/** Whether {@code entry} lies in the wrapped part of the application's jump table (below A5, see {@link #jumpTableEntry}). */
	private boolean isWrappedEntry(int entry,int jtStart){
		return app!=null&&jtStart==app.a5+app.jumpTableOffset&&app.jumpTableOffset+app.jumpTableSize>=0x7ff8
			&&entry>=app.a5-0x8000&&entry>=app.a5-app.belowA5&&entry+8<=app.a5;
	}
	/** Whether a whole entry at {@code entry} is inside the table at {@code jtStart} (its wrapped part included). */
	private boolean jumpTableHolds(int entry,int jtStart,int jtEnd){return entry>=jtStart&&entry+8<=jtEnd||isWrappedEntry(entry,jtStart);}

	/** Load addresses of the CODE segments, for crash dumps. */
	String describeSegments(){
		StringBuilder b=new StringBuilder("segments:");
		if(app!=null)b.append(String.format(" A5=%08X jumpTable=%08X..%08X",app.a5,app.a5+app.jumpTableOffset,app.a5+app.jumpTableOffset+app.jumpTableSize));
		for(Map.Entry<Integer,Integer> e:new java.util.TreeMap<Integer,Integer>(loadedSegments).entrySet()){
			int base=e.getValue();
			ResourceFile68k.Resource code=app==null?null:app.resources.get(ResourceFile68k.fourcc("CODE"),e.getKey());
			int end=code==null?base:base+(code.handle!=0?api.handleSize(code.handle):code.data().length);
			b.append(String.format(" CODE %d=%08X..%08X",e.getKey(),base,end));
		}
		return b.toString();
	}

	/**
	 * Loads CODE {@code segment} into memory and patches its jump-table entries. Like the real LoadSeg the
	 * segment is the resource's own Handle (GetResource + HLock): a program that has already loaded and rewritten
	 * it - some startup stubs decompress their CODE resources in place - runs the rewritten code.
	 */
	int loadSegment(int segment) throws M68kException {return loadSegment(segment,app.a5+app.jumpTableOffset,app.a5+app.jumpTableOffset+app.jumpTableSize);}
	int loadSegment(int segment,int jtStart,int jtEnd) throws M68kException {
		Integer loaded=loadedSegments.get(segment);
		if(loaded!=null)return loaded;
		ResourceFile68k.Resource code=app.resources.get(ResourceFile68k.fourcc("CODE"),segment);
		if(code==null)throw new M68kCpuException(cpu.pc,"CODE "+segment+" resource is missing");
		int handle=resources.handleFor(code);
		if(handle==0||(memory.readLong(handle)==0&&!resources.loadIfResource(handle)))throw new M68kCpuException(cpu.pc,"CODE "+segment+" resource cannot be loaded");
		int base=memory.readLong(handle),length=api.handleSize(handle);
		api.setHandleState(handle,api.handleState(handle)|0x80);
		loadedSegments.put(segment,base);segmentHandles.put(handle,segment);
		if(Mac68kApi.TRACE)System.err.println("[68K] CODE "+segment+" loaded at $"+Integer.toHexString(base)+".."+Integer.toHexString(base+length));
		if(length>=40&&memory.readWord(base)==0xffff)return loadFarSegment(segment,base,length,jtStart,jtEnd);
		int first=memory.readWord(base),count=memory.readWord(base+2);
		boolean relocated=jtStart!=app.a5+app.jumpTableOffset;
		int[] originals=new int[count];
		for(int i=0;i<count;i++){
			int entry=jumpTableEntry(jtStart,first+i*8);
			if(!jumpTableHolds(entry,jtStart,jtEnd))break;
			if(relocated&&!(memory.readWord(entry+2)==0x3f3c&&(short)memory.readWord(entry+4)==segment&&memory.readWord(entry+6)==0xa9f0))break; // only entries that are this segment's
			int offset=memory.readWord(entry);originals[i]=offset;
			memory.writeWord(entry,segment);memory.writeWord(entry+2,0x4ef9);memory.writeLong(entry+4,base+4+offset);
		}
		segmentEntries.put(segment,originals);segmentTables.put(segment,jtStart);
		return base;
	}
	/**
	 * LoadSeg for a jump table in another A5 world whose CODE comes from another file: MPW Shell runs a tool (a CODE
	 * resource file of type MPST) with A5 on the tool's own globals and the tool's file current; its LoadSeg patch
	 * GetResources the segment, then chains to the ROM's, which takes CODE through the resource chain and patches the
	 * entries at A5 + CurJTOffset. The application's own segment of that number (loadedSegments) is not involved:
	 * returning it left the tool's entry unloaded and the call looped through LoadSeg for ever.
	 */
	private void loadForeignSegment(ResourceFile68k.Resource code,int segment,int jtStart,int jtEnd) throws M68kException {
		int handle=resources.handleFor(code);
		if(handle==0||(memory.readLong(handle)==0&&!resources.loadIfResource(handle)))throw new M68kCpuException(cpu.pc,"CODE "+segment+" resource cannot be loaded");
		int base=memory.physical(memory.readLong(handle));
		api.setHandleState(handle,api.handleState(handle)|0x80);
		if(Mac68kApi.TRACE)System.err.println("[68K] CODE "+segment+" of another file loaded at $"+Integer.toHexString(base)+" for the jump table at $"+Integer.toHexString(jtStart));
		int first=memory.readWord(base),count=memory.readWord(base+2);
		for(int i=0;i<count;i++){
			int entry=jtStart+first+i*8;
			if(entry+8>jtEnd)break;
			if(!(memory.readWord(entry+2)==0x3f3c&&(short)memory.readWord(entry+4)==segment&&memory.readWord(entry+6)==0xa9f0))continue; // loaded already, or not this segment's
			int offset=memory.readWord(entry);
			memory.writeWord(entry,segment);memory.writeWord(entry+2,0x4ef9);memory.writeLong(entry+4,base+4+offset);
		}
	}
	/** UnloadSeg of a {@link #loadForeignSegment} segment: its entries go back to [offset][MOVE.W #seg,-(SP)][_LoadSeg], the Handle is unlocked. */
	private void unloadForeignSegment(ResourceFile68k.Resource code,int segment,int jtStart,int jtEnd) throws M68kCpuException {
		int handle=resources.handleFor(code);if(handle==0||memory.readLong(handle)==0)return;
		int base=memory.physical(memory.readLong(handle)),first=memory.readWord(base),count=memory.readWord(base+2);
		for(int i=0;i<count;i++){
			int entry=jtStart+first+i*8;
			if(entry+8>jtEnd)break;
			if(memory.readWord(entry+2)!=0x4ef9||(short)memory.readWord(entry)!=segment)continue;
			int offset=memory.readLong(entry+4)-(base+4);
			memory.writeWord(entry,offset);memory.writeWord(entry+2,0x3f3c);memory.writeWord(entry+4,segment);memory.writeWord(entry+6,0xa9f0);
		}
		api.setHandleState(handle,api.handleState(handle)&~0x80);
	}
	/** The jump table each loaded segment's entries were patched in (see {@link #jumpTableStart}). */
	private final Map<Integer,Integer> segmentTables=new HashMap<Integer,Integer>();

	/**
	 * A far-model ("32-bit everything") segment: header $FFFF, 0, first JT entry offset.l, entry count.l, A5 value and load
	 * address of the last relocation (12, 16), A5 relocation list offset (20), reserved, segment relocation list offset
	 * (28), reserved; code from 40. Each list is a sequence of word deltas from the previous 32-bit reference (a byte,
	 * $80-$FF introducing a 15-bit delta, $00 a 16-bit one) ending in $00 $00: A5 references get A5 added, segment
	 * references the address of the code (offset 40). Its jump-table entries are [seg.w][_LoadSeg][offset.l] with the
	 * routine offset from the resource start; loaded they become [seg.w][JMP.L address].
	 */
	private int loadFarSegment(int segment,int base,int length,int jtStart,int jtEnd) throws M68kCpuException {
		int first=memory.readLong(base+4),count=memory.readLong(base+8),oldA5=memory.readLong(base+12),oldLoad=memory.readLong(base+16);
		int a5List=memory.readLong(base+20),segList=memory.readLong(base+28);
		if(oldA5!=app.a5||oldLoad!=base){ // fresh from the file (0, 0), or the same bytes at another address / A5
			if(a5List>0&&a5List<length)relocate(base,a5List,length,app.a5-oldA5);
			if(segList>0&&segList<length)relocate(base,segList,length,(base+40)-(oldLoad==0?0:oldLoad+40));
			memory.writeLong(base+12,app.a5);memory.writeLong(base+16,base);
		}
		int[] originals=new int[Math.max(0,count)];
		for(int i=0;i<count;i++){
			int entry=jtStart+first+i*8;
			if(entry+8>jtEnd)break;
			int offset=memory.readLong(entry+4);originals[i]=offset;
			memory.writeWord(entry,segment);memory.writeWord(entry+2,0x4ef9);memory.writeLong(entry+4,base+offset);
		}
		segmentEntries.put(segment,originals);segmentTables.put(segment,jtStart);farSegments.add(segment);
		if(Mac68kApi.TRACE)System.err.println("[68K] CODE "+segment+" is far-model: "+count+" jump-table entries from "+first+", relocation lists at "+a5List+"/"+segList);
		return base;
	}
	private final java.util.Set<Integer> farSegments=new java.util.HashSet<Integer>();
	private void relocate(int base,int list,int length,int delta) throws M68kCpuException {
		if(delta==0)return;int position=0,i=list;
		while(i<length){int b=memory.readByte(base+i++);int words;
			if(b==0){if(i+1>=length)break;words=memory.readWord(base+i);i+=2;if(words==0)break;}
			else if(b>=0x80){if(i>=length)break;words=(b&0x7f)<<8|memory.readByte(base+i++);}
			else words=b;
			position+=words*2;if(position+4>length)break;
			memory.writeLong(base+position,memory.readLong(base+position)+delta);
		}
	}

	/**
	 * UnloadSeg(routineAddr): the argument is a jump-table entry (or an address inside the segment). The segment's
	 * entries go back to their unloaded form [offset][MOVE.W #seg,-(SP)][_LoadSeg] and its Handle is unlocked; the
	 * code stays in memory until the program releases the resource (some applications use UnloadSeg + ReleaseResource
	 * and expects the next call to reload through LoadSeg).
	 */
	private boolean unloadSeg(M68kCpu cpu) throws M68kCpuException {
		int routine=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(app==null){ // an external's own A5 world (externalLoadSeg)
			int jtStart=cpu.a[5]+(short)memory.readWord(0x934),jtEnd=jtStart+0x40000;
			if(routine>=jtStart&&routine<jtEnd){int entry=jtStart+((routine-jtStart)&~7);
				if(memory.readWord(entry+2)==0x4ef9){int segment=(short)memory.readWord(entry);
					ResourceFile68k.Resource found=resources.find(ResourceFile68k.fourcc("CODE"),segment);
					if(found!=null)unloadForeignSegment(found,segment,jtStart,jtEnd);}}
			return true;
		}
		int jtStart=jumpTableStart(cpu),jtEnd=jumpTableEnd(jtStart);
		int segment=-1;
		if(routine>=jtStart&&routine<jtEnd){int entry=jtStart+((routine-jtStart)&~7);if(memory.readWord(entry+2)==0x4ef9)segment=(short)memory.readWord(entry);}
		else if(isWrappedEntry(routine&~1,jtStart)){int entry=jtStart+((routine-jtStart)&~7);if(isWrappedEntry(entry,jtStart)&&memory.readWord(entry+2)==0x4ef9)segment=(short)memory.readWord(entry);}
		if(segment>0&&jtStart!=app.a5+app.jumpTableOffset){ // a tool's own table (see loadForeignSegment): its entries, not the application's segment
			ResourceFile68k.Resource found=resources.find(ResourceFile68k.fourcc("CODE"),segment);
			if(found!=null&&found!=app.resources.get(ResourceFile68k.fourcc("CODE"),segment)){unloadForeignSegment(found,segment,jtStart,jtEnd);return true;}
		}
		else for(Map.Entry<Integer,Integer> e:loadedSegments.entrySet()){int base=e.getValue();ResourceFile68k.Resource code=app.resources.get(ResourceFile68k.fourcc("CODE"),e.getKey());int length=code!=null&&code.handle!=0?api.handleSize(code.handle):0;if(routine>=base&&routine<base+length){segment=e.getKey();break;}}
		if(segment<0||!loadedSegments.containsKey(segment))return true;
		int base=loadedSegments.remove(segment);
		int[] originals=segmentEntries.remove(segment);
		boolean far=farSegments.remove(segment);int first=far?memory.readLong(base+4):memory.readWord(base);
		Integer table=segmentTables.remove(segment);if(table!=null){jtStart=table;jtEnd=jumpTableEnd(jtStart);}
		if(originals!=null)for(int i=0;i<originals.length;i++){
			int entry=far?jtStart+first+i*8:jumpTableEntry(jtStart,first+i*8);
			if(!(far?entry+8<=jtEnd:jumpTableHolds(entry,jtStart,jtEnd)))break;
			if(far){memory.writeWord(entry,segment);memory.writeWord(entry+2,0xa9f0);memory.writeLong(entry+4,originals[i]);}
			else{memory.writeWord(entry,originals[i]);memory.writeWord(entry+2,0x3f3c);memory.writeWord(entry+4,segment);memory.writeWord(entry+6,0xa9f0);}
		}
		for(Iterator<Map.Entry<Integer,Integer>> it=segmentHandles.entrySet().iterator();it.hasNext();){Map.Entry<Integer,Integer> e=it.next();if(e.getValue()==segment){api.setHandleState(e.getKey(),api.handleState(e.getKey())&~0x80);it.remove();}}
		if(Mac68kApi.TRACE)System.err.println("[68K] CODE "+segment+" unloaded");
		return true;
	}

	/**
	 * $A0FC, the Resource Manager's internal "read resource data" routine: A2 = reference-list entry, (A4) = map
	 * pointer; reads the resource's bytes from the map's file (data offset at map+0, file refnum at map+$14; the
	 * entry's 3-byte data offset at 4(A2)) into a new Handle stored at 8(A2). Programs with compressed resources
	 * Self-extracting stubs and applications patch it and chain here for the raw bytes; ResourceManager68k.data sets
	 * the registers up for them.
	 */
	private boolean readResourceData(M68kCpu cpu) throws M68kCpuException {
		int entry=cpu.a[2],map=memory.readLong(cpu.a[4]);
		int refNum=(short)memory.readWord(map+0x14);
		long offset=(memory.readLong(map)&0xffffffffL)+(memory.readLong(entry+4)&0xffffff);
		byte[] data=null;
		try{byte[] header=files.fileManager.readReference(refNum,offset,4);
			if(header!=null&&header.length==4)data=files.fileManager.readReference(refNum,offset+4,((header[0]&0xff)<<24)|((header[1]&0xff)<<16)|((header[2]&0xff)<<8)|(header[3]&0xff));
		}catch(IOException e){data=null;}
		if(data==null){memory.writeLong(entry+8,0);memory.writeWord(0xa60,ResourceManager68k.RES_NOT_FOUND);cpu.d[0]=ResourceManager68k.RES_NOT_FOUND;return true;}
		int handle=api.createHandle(data);
		memory.writeLong(entry+8,handle);memory.writeWord(0xa60,0);
		cpu.a[0]=handle;cpu.d[0]=NO_ERR;return true;
	}

	private boolean getAppParms(M68kCpu cpu) throws M68kCpuException {
		int paramPtr=memory.readLong(cpu.a[7]),refNumPtr=memory.readLong(cpu.a[7]+4),namePtr=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
		if(namePtr!=0)writePascal(namePtr,name,255);
		if(refNumPtr!=0)memory.writeWord(refNumPtr,resources.applicationRefNum());
		if(paramPtr!=0)memory.writeLong(paramPtr,app!=null?app.appParms:0);
		return true;
	}

	private boolean launch(M68kCpu cpu) throws M68kException {
		int block=cpu.a[0];
		if(Mac68kApi.TRACE&&block!=0)System.err.println("[68K] Launch block "+java.util.HexFormat.of().formatHex(memory.readBytes(block,44)));
		File target=null;List<File> documents=new ArrayList<File>();boolean extended=false,continues=false;int exitReport=0;
		try{
			if(block!=0&&memory.readWord(block+6)==0x4c43&&memory.readLong(block+8)<32){
				// MultiFinder's LaunchStruct (System 6): pfName +0, param +4, 'LC' +6, extBlockLen +8 (6), fFlags +12, launchFlags +14,
				// whose launchContinue bit ($4000) keeps the launcher running, as in the Process Manager's launchControlFlags (THINK C 5's
				// Run sets $C000; QuicKeys' engine launches its faceless "QuicKeys Toolbox" with $4000 from inside another program).
				continues=(memory.readWord(block+14)&0x4000)!=0;
				int namePtr=memory.readLong(block);
				if(namePtr!=0)target=files.fileForClassicPath(readPascal(namePtr));
				// THINK C 5's Run: ApplScratch ($A78) holds its environment block and $A7C the long it watches while the program runs;
				// the program's start-up code (THINK C's runtime) keeps that pointer and clears the long when it quits. The two share
				// one address space on a Mac, here they don't: the clearing is done for it when the launched process ends.
				if(continues&&memory.readLong(0xa78)!=0)exitReport=memory.readLong(0xa7c);
			}else if(block!=0&&memory.readWord(block+6)==0x4c43){ // extended LaunchParamBlockRec (Processes.h): launchFileFlags +12, launchControlFlags +14,
				// launchAppSpec +16, launchProcessSN +20, the three sizes +28..+39, launchAppParameters +40
				extended=true;continues=(memory.readWord(block+14)&0x4000)!=0; // launchContinue
				int spec=memory.readLong(block+16);
				if(spec!=0)target=files.fileForSpec(spec);
				int appParameters=memory.readLong(block+40);
				if(appParameters!=0)documents.addAll(appleEvents.documentsFromLaunchParameters(appParameters));
			}else if(block!=0){
				int namePtr=memory.readLong(block);
				if(namePtr!=0)target=files.fileForClassicPath(readPascal(namePtr));
			}
		}catch(IOException e){target=null;}
		if(target!=null&&!MacApp68k.isApplication(target)&&MacApp68k.isDeskAccessory(target)){ // a desk accessory runs in a process of its own
			if(!Sandbox.allowFile(target,Sandbox.Access.EXECUTE,name)){cpu.d[0]=-54;return true;}
			try{MacApp68k launched=MacApp68k.openDeskAccessory(target,null);cpu.d[0]=NO_ERR;if(extended){memory.writeLong(block+20,PSN_HIGH);memory.writeLong(block+24,launched.serialNumber);}}
			catch(IOException e){cpu.d[0]=-43;}
			return true;}
		if(target==null||!MacApp68k.isApplication(target)){cpu.d[0]=-43;return true;}
		if(!Sandbox.allowFile(target,Sandbox.Access.EXECUTE,name)){cpu.d[0]=-54;return true;}
		// a PowerPC program's helper file (not an 'APPL'): it runs in the program's own process, as on a Mac where both share one address space
		if(app!=null&&app.powerPC&&app.ppc!=null&&continues&&documents.isEmpty()&&PpcRuntime.runsInLauncher(target)){
			int serial=MacApp68k.newSerialNumber();
			cpu.d[0]=app.ppc.launchInProcess(target,serial);
			if(cpu.d[0]==NO_ERR&&extended){memory.writeLong(block+20,PSN_HIGH);memory.writeLong(block+24,serial);}
			return true;
		}
		if(target.getName().equals("Apple Software Restore")&&extended&&!documents.isEmpty()){
			// Apple Software Restore (the Apple Software Installer's engine for ".img" components) restores a disk image onto a volume through
			// the .HDI/.UDIF disk drivers, which do not run here: the image is mounted by the host and its files are copied in place instead
			// (the Upgrader passes the image and its "Upgrader ASR Prefs" configuration; vtgt -1 is the startup volume). No process results.
			final File image=documents.get(0);final File volume;
			try{volume=files.fileManager.volumeRoot(-1);}catch(IOException e){cpu.d[0]=-35;return true;}
			asrBusy=true;final String requester=name;final MacApp68k launcher=app;
			Thread t=new Thread(()->{
				try{AsrRestore.restore(image,volume,requester);}catch(IOException e){System.err.println("[ASR] restore failed: "+e);}
				try{Thread.sleep(1500);}catch(InterruptedException ignored){}
				// ASR reports its result to the sender - 'ERNI'/'revl' with rnum (0 = the restore went through) - and stays until it is told to quit
				Toolbox68k t2=launcher==null?null:launcher.toolbox;
				if(t2!=null&&!launcher.finished){
					AppleEvents68k.Portable prog=new AppleEvents68k.Portable();prog.type=AppleEvents68k.TYPE_AEVT;prog.isRecord=true;prog.eventClass=0x45524e49;prog.eventId=0x70726f67; // progress: pgcv of pgtl
					for(int key:new int[]{0x70676376,0x7067746c}){AppleEvents68k.Portable v=new AppleEvents68k.Portable();v.type=AppleEvents68k.TYPE_LONG;v.data=new byte[]{0,0,0,100};prog.items.put(key,v);}
					t2.events.postAppleEvent(prog,ASR_SERIAL);
					AppleEvents68k.Portable ev=new AppleEvents68k.Portable();ev.type=AppleEvents68k.TYPE_AEVT;ev.isRecord=true;ev.eventClass=0x45524e49;ev.eventId=0x7265766c;
					AppleEvents68k.Portable rnum=new AppleEvents68k.Portable();rnum.type=AppleEvents68k.TYPE_SHOR;rnum.data=new byte[]{0,0};ev.items.put(0x726e756d,rnum);
					t2.events.postAppleEvent(ev,ASR_SERIAL);}
			},"asr-restore");
			t.setDaemon(true);t.start();
			cpu.d[0]=NO_ERR;memory.writeLong(block+20,PSN_HIGH);memory.writeLong(block+24,ASR_SERIAL);
			return true;
		}
		// one trap table and one address space on a Mac: a _LoadSeg patch the launcher wrote in its own code runs at the launched
		// program's first LoadSeg (LaunchHookBridge68k; Aladdin's trial engine hands its purchase record over that way)
		int loadSegHook=continues&&app!=null?cpu.patchedTrap(0xa9f0):0;
		if(loadSegHook!=0&&loadSegHook!=app.loadSegPatchAtStart)MacApp68k.LOAD_SEG_HOOK.set(loadSegHook);
		try{MacApp68k launched;try{launched=MacApp68k.launch(target,documents);}finally{MacApp68k.LOAD_SEG_HOOK.remove();}cpu.d[0]=NO_ERR;
			if(exitReport!=0){final int flag=exitReport;final M68kMemory launcherMemory=memory;launched.onExit=new Runnable(){@Override public void run(){try{launcherMemory.writeLong(flag,0);}catch(M68kCpuException ignored){}}};}
			if(extended){memory.writeLong(block+20,PSN_HIGH);memory.writeLong(block+24,launched.serialNumber);}
			// acceptAppDiedEvents ($0100) in the launcher's SIZE: it hears 'aevt'/'obit' when this program quits
			if(continues&&app!=null&&(app.sizeFlags()&0x0100)!=0){final MacApp68k launcher=app;final int serial=launched.serialNumber;final Runnable before=launched.onExit;
				launched.onExit=new Runnable(){@Override public void run(){if(before!=null)before.run();Toolbox68k t=launcher.toolbox;if(!launcher.finished&&t!=null)t.events.postApplicationDied(PSN_HIGH,serial,0);}};}
		}catch(IOException e){cpu.d[0]=-43;return true;}
		// Without launchContinue the launching program is terminated (IM VI, Process Manager): the classic _Launch never
		// returns. Some small launcher applications otherwise run off into garbage when control comes back.
		if(app!=null&&!continues){if(Mac68kApi.TRACE)System.err.println("[68K "+name+"] Launch without launchContinue: terminating the launcher");throw new ExitToShell();}
		return true;
	}

	/**
	 * LaunchDeskAccessory and _Launch of a 'dfil' file: the accessory opens in a process of its own (the DA Handler of
	 * System 7), or comes to the front when it is open already. {@code namePtr}: the accessory in the file (nil = the first).
	 */
	private int launchDeskAccessory(int spec,int namePtr) throws M68kCpuException {
		File target;
		try{target=spec==0?null:files.fileForSpec(spec);}catch(IOException e){target=null;}
		if(target==null||!MacApp68k.isDeskAccessory(target))return -43; // fnfErr
		if(!Sandbox.allowFile(target,Sandbox.Access.EXECUTE,name))return -54;
		String accessory=namePtr==0?null:resources.resourceName(namePtr);
		try{MacApp68k.openDeskAccessory(target,accessory==null||accessory.isEmpty()?null:accessory);return NO_ERR;}
		catch(IOException e){return -43;}
	}

	private boolean debugger(M68kCpu cpu,boolean withString) throws M68kCpuException {
		if(withString){int s=memory.readLong(cpu.a[7]);cpu.a[7]+=4;System.err.println("[68K "+name+"] DebugStr: "+readPascal(s));}
		else System.err.println("[68K "+name+"] Debugger");
		return true;
	}

	private boolean sysError(M68kCpu cpu) throws M68kException {
		throw new M68kCpuException(cpu.pc,"SysError "+cpu.d[0]);
	}

	// ------------------------------------------------------------------ trap table

	private int trapWordFor(int opcode,int number){
		boolean newStyle=(opcode&0x0200)!=0;
		boolean toolbox=newStyle?(opcode&0x0400)!=0:(number&0x0800)!=0;
		if(!newStyle&&(number&0xf000)!=0xa000){
			// a bare trap number in the old style (the 64K ROM's single table): $00-$4F, $54 and $57 are OS traps, every other
			// number a Toolbox trap (Technote 156). Super MANDELZOOM gets MoveTo, PenPat and Line with D0 = $93, $9D, $91 -
			// as OS traps they were Microseconds and friends, and its stack came apart.
			int n=number&0x1ff;
			toolbox=!(n<=0x4f||n==0x54||n==0x57);
			return toolbox?0xa800|n:0xa000|n;
		}
		return toolbox?0xa800|(number&0x3ff):0xa000|(number&0xff);
	}

	/** The address GetTrapAddress reports for an unpatched trap (also the low-memory trap tables' initial contents). */
	int defaultTrapAddress(int word){
		// $A0FC (the ROM's own resource-data reader, which self-extracting engines may probe and replace) is implemented here and so
		// keeps a distinct address; calling an unimplemented entry still raises "not supported".
		// The OS trap table of a real Mac holds the Unimplemented routine for every unused entry, like the Toolbox table:
		// TrapAvailable() compares against _Unimplemented for both kinds (M 2.2.4 probes _PMgrOp that way and then calls it),
		// except for ROM-internal entries such as $A0FC that have a routine of their own.
		return implemented(word)?cpu.trapRomAddress(word):unimplementedRomAddress;
	}

	private boolean getTrapAddress(M68kCpu cpu,int opcode){
		int word=trapWordFor(opcode,cpu.d[0]);
		int patched=cpu.patchedTrap(word);
		cpu.a[0]=patched!=0?patched:defaultTrapAddress(word);
		return true;
	}

	private boolean setTrapAddress(M68kCpu cpu,int opcode){
		int word=trapWordFor(opcode,cpu.d[0]);
		int address=cpu.a[0];
		cpu.patchTrap(word,address==defaultTrapAddress(word)?0:address);
		return true;
	}

	/** Package traps by number (_Pack0.._Pack15). */
	private static final int[] PACK_TRAPS={0xa9e7,0xa9e8,0xa9e9,0xa9ea,0xa9eb,0xa9ec,0xa9ed,0xa9ee,0xa816,0xa82b,0xa82c,0xa82d,0xa82e,0xa82f,0xa830,0xa831};
	/** A 'PACK' resource the program (not the built-in System file) supplies for a package trap without a native
	 *  implementation, loaded and locked like the Package Manager does; returns its code address or 0. */
	int packageEntry(int word) throws M68kException {
		int number=-1;for(int i=0;i<PACK_TRAPS.length;i++)if(PACK_TRAPS[i]==(word&0xffff))number=i;
		if(number<0)return 0;
		ResourceFile68k.Resource r=resources.find(0x5041434b,number,false);
		if(r==null||resources.system.byKey.get(r.key())==r)return 0;
		int h=resources.handleFor(r);if(h==0)return 0;
		if(memory.readLong(h)==0)resources.loadIfResource(h);
		api.setHandleState(h,api.handleState(h)|0x80);
		int code=memory.readLong(h);
		if(Mac68kApi.TRACE)System.err.println("[68K] _Pack"+number+" -> program's PACK "+number+" resource at $"+Integer.toHexString(code));
		return code;
	}

	boolean implemented(int word){
		if(word==0xa89f)return false;
		// With the installed QuickTime extension its INIT installs the Image Compression Manager only when _CodecDispatch is
		// _Unimplemented: it calls CodecManagerVersion through an existing entry and keeps that ICM otherwise.
		if(word==0xaaa3&&guestQuickTime)return false;
		if(word==0xaafe&&!powerPcExternals.isEmpty())return true; // _MixedModeMagic: a PowerPC-only external's stub looks for it
		// _IconServicesDispatch (Mac OS 8.5+, IconServices68k) is there, unlike the Appearance traps $AA73/$AA74 kept hidden with
		// 'appr': ACTION Menus' INIT saw System 9.2.2, patched it and called through the old address - _Unimplemented
		if(word==0xaa75)return true;
		if(api.isImplementedTrap(word))return true;
		boolean toolbox=(word&0x0800)!=0;
		int trap=toolbox?word&0xfff:word&0xff;
		if(toolbox)return events.implemented(trap)||windows.implemented(trap)||menus.implemented(trap)||controls.implemented(trap)||textEdit.implemented(trap)||dialogs.implemented(trap)||resources.implemented(trap)||quickDraw.implemented(trap)||ownToolboxTrap(trap)||MiscManagers68k.implemented(trap);
		return ownOsTrap(trap);
	}

	/** Human-readable trap name (null when unknown here). */
	String trapName(int word){
		boolean toolbox=(word&0x0800)!=0;int trap=toolbox?word&0xfff:word&0xff;
		if(!toolbox)return null;
		String n=EventManager68k.name(trap);if(n==null)n=WindowManager68k.name(trap);if(n==null)n=MenuManager68k.name(trap);if(n==null)n=ControlManager68k.name(trap);if(n==null)n=TextEdit68k.name(trap);if(n==null)n=DialogManager68k.name(trap);if(n==null)n=ResourceManager68k.name(trap);
		if(n==null)n=MiscManagers68k.name(trap);
		if(n==null)switch(trap){case 0x9f0:return "LoadSeg";case 0x9e9:return "Pack2";case 0xbed:return "DragDispatch";case 0x9f4:return "ExitToShell";case 0x9f5:return "GetAppParms";case 0x9f8:return "MethodDispatch";case 0x815:return "SCSIDispatch";case 0x82d:return "EditionDispatch";case 0x88f:return "OSDispatch";case 0x8b5:return "ScriptUtil";case 0x9ed:return "Pack6";case 0x823:return "AliasDispatch";case 0x816:return "AppleEvents";case 0x9ea:return "StandardFile";case 0x8fd:return "PrGlue";case 0x82a:return "ComponentDispatch";case 0x82b:return "Pack9";case 0xaaa:return "QuickTime";case 0xbf2:return "ThreadDispatch";case 0xa54:return "TSMDispatch";case 0xadb:return "CursorDeviceDispatch";default:return null;}
		return n;
	}

	private static boolean ownToolboxTrap(int trap){
		switch(trap){
			case 0xbc0:
			case 0x9f0:case 0x9f1:case 0x9f2:case 0x9f3:case 0x9f4:case 0x9f5:case 0x9f8:case 0x815:case 0x82d:case 0x9ff:case 0xbff:case 0x9c9:case 0x9e5:case 0x9e6:case 0x9e9:case 0x895:case 0x8b5:case 0x9ed:case 0x9ec:case 0x823:case 0x88f:
			case 0x9c6:case 0x9c7:case 0x96f:case 0x96e:case 0x906:case 0x907:case 0x9e3:case 0x9e2:case 0x9e4:case 0x9ef:case 0x8cf:case 0x8d0:case 0x868:case 0x869:case 0x86c:case 0x867:case 0x83f:case 0x840:
			case 0x841:case 0x842:case 0x843:case 0x844:case 0x846:case 0x845:case 0x847:case 0x848:case 0x849:case 0x84a:case 0x84b:case 0x84d:case 0x818:case 0x8bc:case 0x8c4:case 0x94f:case 0x9b4:case 0x9b2:case 0x9b3:case 0x9c2:
			case 0x9b6:case 0x9b7:case 0x9c3:case 0xa52:case 0x82a:case 0x829:case 0x82b:case 0xb27:case 0xb29:case 0xbf1:case 0xaaa:case 0xaa3:case 0x816:case 0x9ea:case 0x9e7:case 0x82e:case 0x830:case 0x831:case 0x861:case 0x8fd:case 0xbed:case 0xbf2:case 0xa54:case 0xadb:return true;
			default:return false;
		}
	}
	/** TheZone: programs may carve private zones out of a block with InitZone; blocks are
	 *  still allocated from the one host heap, the zone record only exists so GetZone/SetZone round-trip and its header reads sanely. */
	private int currentZone;
	/** Address of the application zone record; the host heap starts right after its 52-byte header ({@link #APPL_HEAP_START}). */
	static final int APPL_ZONE=0x3000;
	/** This program's application zone record: {@link #APPL_ZONE}, or the start of its partition when it runs in its launcher's
	 *  address space (M68kMemory.launchPartition), where the launcher's own zone lies at $3000. */
	final int applZone;
	static final int APPL_HEAP_START=APPL_ZONE+0x40;
	/** Keeps the application zone header believable: programs read bkLim/zcbFree themselves (HyperCard 2.2 measures the
	 *  heap as bkLim - heapData at startup and quits below 768 KB), and the header must not be part of the heap or the
	 *  first allocated block overwrites it. */
	private void writeApplZoneRecord() throws M68kCpuException {
		int zone=applZone,limit=memory.heapStart()>zone?memory.heapEnd():zone+52;
		// The heap is presented as ONE free block in the 24-bit Memory Manager layout (tag byte 0 = free, 24-bit size), so
		// programs that walk the zone from heapData to bkLim themselves terminate: MacWrite 2.2 sums the free blocks at
		// startup (ADDA the size, loop until bkLim) and spun forever on the zero header word that used to be there.
		// The size field is 24 bits, so bkLim stays within 16 MB of heapData; HyperCard only needs bkLim-heapData >= 768 KB.
		// MPW Shell and MCL get the 32-bit Memory Manager's zone (heapType 1, 12-byte headers - M68kMemory.blockHeaders): free block = tag 0, size at +4.
		// An MCL program's bkLim covers the whole heap: the Lisp takes a Handle above bkLim for a bad one (MacApp68k.mclHeap).
		boolean zone32=app!=null&&(app.mpwShell||app.mclHeap);
		int heapData=zone+52,blockSize=(app!=null&&app.mclHeap?limit-heapData:Math.min(limit-heapData,0xfffff8))&~7;limit=heapData+blockSize;
		memory.writeLong(zone,limit);                       // bkLim
		if(zone32){memory.writeLong(heapData,0);memory.writeLong(heapData+4,blockSize);memory.writeLong(heapData+8,0);}
		else if(blockSize>=8){memory.writeLong(heapData,blockSize);memory.writeLong(heapData+4,0);} // the free block's header (the 12 spare bytes before the heap)
		memory.writeLong(zone+4,0);                         // purgePtr
		memory.writeLong(zone+8,0);                         // hFstFree
		memory.writeLong(zone+12,memory.heapStart()>zone?(app==null?reportedFree(memory.availableBytes()):memory.availableBytes()):0); // zcbFree (an external's: what FreeMem tells - DevStack's MaxApplZone XFCN divides it by 1024 into an INTEGER)
		memory.writeLong(zone+16,growZoneProc);             // gzProc
		memory.writeWord(zone+20,64);                       // moreMast
		memory.writeWord(zone+22,0);                        // flags
		for(int i=24;i<36;i+=2)memory.writeWord(zone+i,0);  // cntRel..cntHandles
		// heapType: k32BitHeap for a 32-bit zone. Never kNewStyleHeap (2), although the Modern Memory Manager is always on under Mac OS
		// 8.5+: with it Yoot Tower's SetHandleSize (through a patch) answered memFullErr and it quit at once; Escape Velocity 1.0.5
		// only warns "you have the Modern Memory Manager turned off" without it
		if(zone32)memory.writeByte(zone+30,1);
		memory.writeLong(zone+36,0);                        // minCBFree
		memory.writeLong(zone+40,0);                        // purgeProc
		memory.writeLong(zone+44,0);                        // sparePtr
		memory.writeLong(zone+48,0);                        // allocPtr
	}
	/**
	 * Free space of the private zones made with InitZone: blocks made while one is the current zone are counted against it (block
	 * header 8 bytes, sizes rounded up to 4, 12 at the least, a Handle also its 4-byte master pointer) although they live in the
	 * one host heap. Nisus Writer 4.03 sizes a scratch zone at start-up by allocating three Handles in it and shrinking the zone by
	 * FreeMem until it reports 0 - the application heap's 32 MB made that size negative and it quit.
	 */
	private final java.util.Map<Integer,Integer> privateZones=new java.util.HashMap<Integer,Integer>();
	/** Block (Ptr or Handle address) -> {zone, bytes counted}. */
	private final java.util.Map<Integer,int[]> zoneBlocks=new java.util.HashMap<Integer,int[]>();
	private int privateZoneFree(int zone) throws M68kCpuException {
		int free=Math.max(0,memory.readLong(zone)-zone-52-privateZones.get(zone));
		memory.writeLong(zone+12,free);return free;
	}
	/** Counts a block just made against the current zone when that is a private one. */
	void zoneCharge(int block,int size,boolean handle) throws M68kCpuException {
		Integer used=privateZones.get(currentZone);if(used==null||block==0)return;
		int cost=Math.max(12,(Math.max(0,size)+8+3)&~3)+(handle?4:0);
		zoneBlocks.put(block,new int[]{currentZone,cost});privateZones.put(currentZone,used+cost);privateZoneFree(currentZone);
	}
	/** PtrZone/HandleZone: the private zone a block was made in, else the application zone. Flash 4's Windows layer finds
	 *  which of its InitZone heaps a pointer belongs to that way (window class records); always the application zone made
	 *  its CreateWindowEx("MDICLIENT") fail and the program quit at start-up. */
	/** The current zone when it is one the program made with InitZone (its Ptrs are carved out of it), else 0. */
	int currentPrivateZone(){return privateZones.containsKey(currentZone)?currentZone:0;}
	private int zoneOf(int block){int[] z=zoneBlocks.get(block);return z==null?applZone:z[0];}
	void zoneRelease(int block) throws M68kCpuException {
		if(zoneBlocks.isEmpty())return;
		int[] z=zoneBlocks.remove(block);if(z==null)return;
		Integer used=privateZones.get(z[0]);if(used==null)return;
		privateZones.put(z[0],Math.max(0,used-z[1]));privateZoneFree(z[0]);
	}
	private boolean initZone(M68kCpu cpu) throws M68kCpuException {
		int block=cpu.a[0];int start=memory.readLong(block),limit=memory.readLong(block+4),moreMasters=memory.readWord(block+8),growZone=memory.readLong(block+10);
		if(start==0||limit<=start+52){cpu.d[0]=-108;return true;}
		memory.fill(start,52,0);
		memory.writeLong(start,limit);memory.writeLong(start+12,limit-start-52);memory.writeLong(start+16,growZone);memory.writeWord(start+20,moreMasters);
		privateZones.put(start,0);zoneBlocks.values().removeIf(z->z[0]==start);privateZoneFree(start);api.resetZoneHeap(start);
		currentZone=start;memory.writeLong(0x118,currentZone);cpu.d[0]=NO_ERR;return true;
	}
	/** Extended parameter RAM (256 bytes) shared by every program of this session: the standard PRAM bytes are
	 * preset like a real machine's, the rest starts zeroed (some programs keep private settings there). */
	private static final byte[] XPRAM=new byte[256];
	static{int[] preset={0x08,0x13,0x09,0x88,0x0b,0xcc,0x0c,'N',0x0d,'u',0x0e,'M',0x0f,'c',
		0x10,0xa8,0x13,0x22,0x14,0xcc,0x15,0x0a,0x16,0xcc,0x17,0x0a,0x1d,0x02,0x1e,0x63};for(int i=0;i<preset.length;i+=2)XPRAM[preset[i]]=(byte)preset[i+1];}

	/** The Map control panel's MachineLocation at XPRAM $E4 (ReadLocation = _ReadXPRam $000C00E4): latitude and longitude
	 *  as Fracts of 90 degrees and the GMT offset in seconds (24 bits) under the daylight-saving flag byte, from the host's
	 *  time zone and the main city of that zone (0,0 for zones not listed). All zeros meant "never set": umich DX Map's
	 *  GetLocale XCMD answered "Location information has not been set!". */
	static void machineLocation(byte[] ram,int at,java.util.TimeZone zone,long now){
		double[] place=ZONE_PLACES.getOrDefault(zone.getID(),new double[]{0,0});
		int lat=(int)Math.round(place[0]/90*(1<<30)),lon=(int)Math.round(place[1]/90*(1<<30));
		int gmt=zone.getOffset(now)/1000;
		int flags=(zone.inDaylightTime(new java.util.Date(now))?0x80000000:0)|(gmt&0xffffff);
		for(int i=0;i<4;i++){ram[at+i]=(byte)(lat>>>(24-8*i));ram[at+4+i]=(byte)(lon>>>(24-8*i));ram[at+8+i]=(byte)(flags>>>(24-8*i));}
	}
	/** latitude (north +) and longitude (east +) of the main city of common time zones */
	private static final java.util.Map<String,double[]> ZONE_PLACES=new java.util.HashMap<>();
	static{
		Object[] t={"Asia/Tokyo",35.68,139.69,"Asia/Seoul",37.57,126.98,"Asia/Shanghai",31.23,121.47,"Asia/Hong_Kong",22.32,114.17,
			"Asia/Taipei",25.03,121.57,"Asia/Singapore",1.35,103.82,"Asia/Kolkata",22.57,88.36,"Australia/Sydney",-33.87,151.21,
			"Pacific/Auckland",-36.85,174.76,"Europe/London",51.51,-0.13,"Europe/Paris",48.86,2.35,"Europe/Berlin",52.52,13.40,
			"Europe/Madrid",40.42,-3.70,"Europe/Rome",41.90,12.50,"Europe/Amsterdam",52.37,4.90,"Europe/Stockholm",59.33,18.07,
			"Europe/Moscow",55.76,37.62,"America/New_York",40.71,-74.01,"America/Chicago",41.88,-87.63,"America/Denver",39.74,-104.99,
			"America/Los_Angeles",34.05,-118.24,"America/Toronto",43.65,-79.38,"America/Vancouver",49.28,-123.12,
			"America/Sao_Paulo",-23.55,-46.63,"America/Mexico_City",19.43,-99.13,"Pacific/Honolulu",21.31,-157.86};
		for(int i=0;i<t.length;i+=3)ZONE_PLACES.put((String)t[i],new double[]{(Double)t[i+1],(Double)t[i+2]});
		machineLocation(XPRAM,0xe4,java.util.TimeZone.getDefault(),System.currentTimeMillis());
	}
	/** _ReadXPRam / _WriteXPRam: D0 = byte count (high word) and XPRAM offset (low word), A0 = buffer. */
	private boolean xpRam(M68kCpu cpu,boolean write) throws M68kCpuException {
		int count=cpu.d[0]>>>16,offset=cpu.d[0]&0xffff;
		if(offset>=XPRAM.length||count>XPRAM.length-offset){cpu.d[0]=PARAM_ERR;return true;}
		synchronized(XPRAM){for(int i=0;i<count;i++)if(write)XPRAM[offset+i]=(byte)memory.readByte(cpu.a[0]+i);else memory.writeByte(cpu.a[0]+i,XPRAM[offset+i]);}
		cpu.d[0]=NO_ERR;return true;
	}

	private static boolean ownOsTrap(int trap){
		switch(trap){
			case 0x51:case 0x52:case 0x71:case 0x72:case 0x82:case 0x5c:case 0x0e:case 0x0f:case 0x17:case 0x35:case 0x43:
			case 0x46:case 0x47:case 0xad:case 0x90:case 0x3b:case 0xdd:case 0x58:case 0x59:case 0x5a:case 0x33:case 0x34:case 0x6e:case 0x6f:case 0x70:case 0x5e:case 0x5f:case 0x19:case 0x1a:case 0x1b:case 0x1d:case 0x1c:case 0x26:case 0x48:case 0x4b:case 0x4c:case 0x4d:case 0x40:case 0x61:case 0x62:
			case 0x63:case 0x64:case 0x2d:case 0x57:case 0x69:case 0x6a:case 0x67:case 0x68:case 0xa4:case 0x9d:case 0x28:case 0x20:case 0x39:case 0x3a:case 0x3f:case 0x38:case 0x5d:case 0xbd:case 0x98:case 0x8d:case 0x91:case 0x54:case 0x56:case 0x3c:case 0x93:case 0x60:
			case 0x04:case 0x05:case 0x06:case 0x00:case 0x01:case 0x02:case 0x03:case 0x9e:case 0x2f:case 0x32:case 0xfc:return true;
			case 0x30:case 0x31:case 0x3d:case 0x3e:return true; // the rest of the OS switch above (drivers, file system extras, 32-bit memory calls)
			case 0x77:case 0x78:case 0x79:case 0x7a:case 0x7b:case 0x7c:return true; // ADB Manager
			case 0x8b:return true; // CommToolboxDispatch (DITL utilities)
			default:return false;
		}
	}

	// ------------------------------------------------------------------ Gestalt

	private boolean gestalt(M68kCpu cpu,int opcode) throws M68kException {
		int selector=cpu.d[0];
		if((opcode&0x0600)==0x0200){ // NewGestalt
			if(gestaltProcs.containsKey(selector)||gestaltValue(selector)!=null){cpu.d[0]=GESTALT_DUP;return true;}
			gestaltProcs.put(selector,cpu.a[0]);cpu.d[0]=NO_ERR;return true;
		}
		if((opcode&0x0600)==0x0400){ // ReplaceGestalt
			Integer old=gestaltProcs.get(selector);gestaltProcs.put(selector,cpu.a[0]);cpu.a[0]=old==null?0:old;cpu.d[0]=NO_ERR;return true;
		}
		if(selector==SPEECH_MANAGER&&app==null&&gestaltProcs.get(selector)==null&&gestaltValue(selector)==null)extensions.loadForExternal(SPEECH_MANAGER); // its INIT defines 'ttsc'
		Integer proc=gestaltProcs.get(selector);
		// The trap itself only returns the response in A0 (result in D0); storing it through A1 is the job of the Pascal glue
		// (MOVE.L A0,(A1)). Programs that call _Gestalt inline may have anything in A1, so the trap must never write there.
		if(proc!=null){ // pascal OSErr proc(selector, VAR response)
			int response=cpu.a[7]-4;cpu.a[7]-=4;memory.writeLong(response,0);
			// the result slot starts as noErr: the QuickTime PowerPlug's 'qtrs' function (ptch -19063) only stores the response
			// (its CLR.W -(A7) misses the slot), and stack garbage there told QuickTime's extension INITs that the PowerPlug was
			// absent - each queued itself again for a later pass, and the pass never ended
			cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushLong(selector);cpu.pushLong(response);
			// the OS trap dispatcher keeps D1/D2/A1 across the trap, also across a selector function that uses them as scratch:
			// Kaleidoscope's control panel stores the response with the glue's MOVE.L A0,(A1) and read "Not Installed" when its
			// 'Copl' function had left A1 elsewhere
			int d1=cpu.d[1],d2=cpu.d[2],a1=cpu.a[1];
			try{cpu.callPascal(proc,8);}finally{cpu.d[1]=d1;cpu.d[2]=d2;cpu.a[1]=a1;}
			int result=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;
			cpu.a[0]=memory.readLong(response);cpu.a[7]+=4;cpu.d[0]=result;
			// PowerPC programs see the Mac OS 9.2.2 QuickTime (5.0.2) although the installed 68K extension is 4.0.3 (Heavy Metal F.A.K.K.2 demands 4.1.2)
			if(selector==0x7174696d&&result==0&&powerPcProgramAsks()&&Integer.compareUnsigned(cpu.a[0],QUICKTIME_PPC_VERSION)<0)cpu.a[0]=QUICKTIME_PPC_VERSION;
			return true;
		}
		Integer value=gestaltValue(selector);
		// an undefined selector answers 0 in A0 (the glue stores it): Quake Demo tests BitTst(&response,31) after a failed
		// Gestalt('sdev') without checking the error and called a missing trap when the old response was left there
		// 'a/ux' without A/UX answers gestaltUnknownErr: MacHTTP 2.2 accepts only -5550 there (gestaltUndefSelectorErr, -5551, made it
		// log "Error calling Gestalt (A/UX) -5551" and quit at once) and -5551 for 'cpnt'
		if(value==null){cpu.a[0]=0;cpu.d[0]=selector==0x612f7578?GESTALT_UNKNOWN:GESTALT_UNDEF;return true;}
		// the same version from the native Movie Toolbox (Otto Matic: "This application requires Quicktime 4.1.1 or newer")
		if(selector==0x7174696d&&powerPcProgramAsks()&&Integer.compareUnsigned(value,QUICKTIME_PPC_VERSION)<0)value=QUICKTIME_PPC_VERSION;
		cpu.a[0]=value;cpu.d[0]=NO_ERR;return true;
	}

	/** 'ttsc': the Speech Manager's Gestalt selector and its extension's creator (loaded for an external that asks, see Extensions68k.loadForExternal). */
	static final int SPEECH_MANAGER=0x74747363;
	/** Debug aid: -Dhz68k.gestalt=snhw=61736320,foo=1 defines (or overrides) Gestalt selectors with the given hex values; mtcp=- makes one undefined. */
	private static final Map<Integer,Integer> GESTALT_OVERRIDES=new HashMap<Integer,Integer>();private static final java.util.Set<Integer> GESTALT_UNDEFINED=new java.util.HashSet<Integer>();
	static{String spec=System.getProperty("hz68k.gestalt","");for(String item:spec.split(",")){int eq=item.indexOf('=');if(eq!=4)continue;
		if(item.substring(eq+1).equals("-")){GESTALT_UNDEFINED.add(ResourceFile68k.fourcc(item.substring(0,4)));continue;}
		try{GESTALT_OVERRIDES.put(ResourceFile68k.fourcc(item.substring(0,4)),(int)Long.parseLong(item.substring(eq+1),16));}catch(NumberFormatException e){System.err.println("hz68k.gestalt: bad value in "+item);}}}
	/** The QuickTime PowerPC programs see: Mac OS 9.2.2's 5.0.2 (Final Cut Pro 2.0 requires it; Heavy Metal F.A.K.K.2 4.1.2); the
	 *  QuickTime 5 calls missing from the 4.0.3 extension are natives in PpcLibraries. */
	static final int QUICKTIME_PPC_VERSION=0x05028000;
	/** Gestalt called by the PowerPC program itself - not by the 4.0.3 extension's own 68K code, which takes a newer version for
	 *  "Some of your QuickTime software failed to load because it is out of date". */
	private boolean powerPcProgramAsks(){return app!=null&&app.powerPC&&app.ppc!=null&&"Gestalt".equals(app.ppc.toolboxCall);}
	/** Gestalt called by the PowerPC code of a stack's external ({@link #externalPpc}): such code runs only on a Power Macintosh, so
	 *  it sees one, with the Mac OS 9.2.2 PowerPC programs see (URLAccess 1.3 answered "requires MacOS 8.6" to System 7.5.5). The
	 *  VM's 68K code and HyperTalk's systemVersion keep the 68K Mac and System 7.5.5. */
	private boolean powerPcExternalAsks(){return app==null&&externalPpc!=null&&"Gestalt".equals(externalPpc.toolboxCall);}
	Integer gestaltValue(int selector){
		Integer custom=gestaltValues.get(selector);
		if(custom!=null)return custom;
		custom=GESTALT_OVERRIDES.get(selector);if(custom!=null)return custom;
		if(GESTALT_UNDEFINED.contains(selector))return null;
		switch(selector){
			case 0x73797376:return powerPcExternalAsks()?MAC_OS_9_VERSION:baseSystemVersion; // sysv
			case 0x76657273:return 1;                          // vers: Gestalt Manager version (DOCMaker documents beep and quit without it)
			case 0x746d6772:return 3;                          // tmgr: extended Time Manager (おーぶ・おぶ・ふぉれすと refuses to start without it)
			case 0x61646472:return 7;                          // addr: gestalt32BitAddressing|32BitSysZone|32BitCapable, what a Mac booted in 32-bit mode answers (Odyssey 1.1 requires exactly 7: "Odyssey requires 32-bit Addressing turned on")
			case 0x64706c79:return 1;                          // dply: gestaltDisplayMgrPresent (the Display Manager: DisplayManager68k lists and switches the display modes; RezLib's init stops without it)
			case 0x64706c76:return 0x00020000;                 // dplv: gestaltDisplayMgrVers 2.0 (Quake Demo: "Quake requires Display Manager 2.0 or later")
			// mach: gestaltPowerMac7200, or gestaltMacIIvx on a 68K Mac (Gestalt.h): a 68030 Mac with a 68882 and the 1 MB $067C ROM,
			// what the CPU here is (68020/030 instructions, no MOVE16/CPUSH) - it said Quadra 950, a 68040, and XTernal's MachineID,
			// Processor (68030/68882) and MMUType then described no real Mac. 25 is gestaltPowerBook140: Vic Kombat refused to run "on a Powerbook"
			case 0x6d616368:return seesPowerPC()?108:48;
			case 0x6d69636e:return -16386;                     // micn: gestaltMachineIcon - the resource id of the machine's icon in the System file (About 1.0.1 stops with "Gestalt machineIcon" -5551 at start-up when it is undefined)
			case 0x70726f63:return 4;                          // proc: 68030 (3=68020,4=68030,5=68040)
			case 0x63707574:return seesPowerPC()?0x0106:3;   // cput: the PowerPC 603e of a Power Macintosh (68030 on a 68K Mac)
			case 0x63707566:return seesPowerPC()?0x0103:null; // cpuf: gestaltNativeCPUfamily, the 603 family of that 603e (undefined on a 68K Mac)
			case 0x66707520:return M68kFpu.GESTALT_FPU_TYPE;   // fpu : 68882 (0 = none with -Dhz68k.fpu=false)
			case 0x6d6d7520:return seesPowerPC()?5:3;         // mmu : gestaltEMMU1 of a Power Macintosh's 68K emulator, gestalt68030MMU of the 68030 (32-bit addressing, 'addr' 7, needs one)
			case 0x6f737474:return M68kCpu.OS_TRAP_TABLE;      // ostt: gestaltOSTable, the OS trap dispatch table base
			case 0x74627474:return M68kCpu.TOOLBOX_TRAP_TABLE; // tbtt: gestaltToolboxTable (Aladdin's trial engine patches _LoadSeg through it and quits without it: ShrinkWrap 3.0)
			// xttt: gestaltExtToolboxTable, where the entries of $AA00-$ABFF (the half System 7 added) lie - here right after the first
			// 512 of the one 1024-entry table. A System 7.5 defines it (its System file registers the selector); GetExtendedToolbox said "Undefined"
			case 0x78747474:return M68kCpu.TOOLBOX_TRAP_TABLE+0x200*4;
			case 0x71642020:return 0x0230;                     // qd  : 32-bit Color QuickDraw 1.3
			case 0x71647672:return 0x0230;                     // qdvr
			case 0x71647277:return 0x0001|0x0002|0x0004|0x0008;// qdrw: QuickDraw features - hasColor, deep GWorlds, direct PixMaps, grayishTextOr (Altair quits with "a monitor ... could not be found" when undefined)
			// te  : gestaltTE4 (TextEdit of System 7.x), gestaltTE5 (with Appearance, Mac OS 8.5+); the microarchitecture simulator
			// of usim 1.04 (ArchSim) stops with "needs System 7.0 or later" below 4
			case 0x74652020:{Integer sv=gestaltValue(0x73797376);return sv!=null&&sv>=0x0850?5:4;}
			case 0x66732020:return 0x1003;                     // fs  : File Manager with FSSpec, HFS Plus APIs (bit 12)
			case 0x6f732020:return 0x7f;                       // os  : sysZoneGrowable|launchCanReturn|launchFullFileSpec|launchControl|tempMemSupport|realTempMemory|tempMemTracked (some programs require all three temp-memory bits)
			case 0x6c72616d:case 0x72616d20:return memory.physicalSize(); // lram / ram: an external's VM may grow to that (TotalRAM said more than the Mac had)
			case 0x726f6d20:return M68kMemory.ROM_SIZE;         // rom : ROM size in bytes (MacBench requires it)
			case 0x726f6d76:return 0x067c;                     // romv: same version as the synthetic ROM header
			case 0x7374646e:case 0x73746466:return 1;          // stdf: standard file 5.8 (bit 0)
			// snd : Gestalt.h bits - stereo(0), stereoMixing(1), SoundIOMgr(3), builtInInput(4), inputDevice(5), playAndRecord(6), 16-bit I/O(7),
			// SndPlayDoubleBuffer(10), multiChannels(11), 16-bit audio(12): Sound Manager 3.x (Dark Forces refuses to run without 3.0's bits).
			// Bit 2 (not in Gestalt.h) is what QuickTime 6's sound media handler takes for 16-bit output: without it the handler
			// converted every sound track down to 8 bits and asked for the synthesizers' CPU load first
			case 0x736e6420:return 0x0001|0x0002|0x0004|0x0008|0x0010|0x0020|0x0040|0x0080|0x0400|0x0800|0x1000;
			case 0x736e6476:return 0x0321;                     // sndv: Sound Manager 3.2.1
			case 0x616c6973:return 1;                          // alis: Alias Manager
			case 0x65766e74:return Integer.getInteger("hz68k.evnt",0x0001|0x0002|0x0004);       // evnt: high level events, AppleEvents
			case 0x706f7021:return 0x0001;                              // pop!: gestaltPopupPresent - the pop-up menu CDEF (63) is there (HyperCard 2.2+ shows its script editor's scripting-language pop-up only with it)
			case 0x696e7466:return 2;
			case 0x6b626420:return KeyboardLayout68k.GESTALT_KEYBOARD; // kbd : extended ADB keyboard
			case 0x77646574:return 0;                          // wdet
			case 0x7364656e:case 0x77646E63:return 0;
			case 0x73797361:return seesPowerPC()?2:1;        // sysa: gestaltPowerPC (see POWERPC_MACHINE), gestalt68k
			case 0x74696d72:return 3;                          // timr: extended Time Manager
			case 0x6e6d6772:case 0x6e746d67:return 1;          // nmgr: Notification Manager present ('ntmg' was a misspelling; MandelNet said "requires System 7")
			case 0x64697370:return 0;                          // disp: no Display Manager
			case 0x666f6e74:return 1;                          // font: gestaltOutlineFonts (TrueType; Warlords 2 refuses to start without it - this used to be spelled 'fond')
			case 0x66746e2e:return 0;
			case 0x636f6d70:return 1;                          // comp: Component Manager
			case 0x63706e74:return Components68k.CM_VERSION;   // cpnt: Component Manager version (>= 8: QuickTime 4 does not patch it)
			case 0x6275677a:return 1<<8;                       // bugz: QuickTime's own "ROM bugs fixed" selector; bit 8 = Microseconds is accurate, so its VIA-timer replacement (no VIA here) stays out
			case 0x7174696d:return quickTime.version()!=0?quickTime.version():null; // qtim: QuickTime version (undefined when the installed extension is to answer)
			case 0x71746673:return quickTime.version()!=0?1:null;// qtfs? features
			// qtrs: gestaltQuickTimeFeatures, bit 0 gestaltPPCQuickTimeLibPresent - PowerPC code gets QuickTimeLib here whichever
			// QuickTime answers. QuickTime VR's INIT on a Power Mac queues itself until the bit shows, and QuickTime's start-up
			// finish then reported it "not installed"
			// An external gets 0: QuickTime without the PowerPC library (its code is 68K). A 68K Mac's QuickTime 4.0.3 leaves the
			// selector undefined (measured), a Power Mac's answers 1, and HyperCard ran on Power Macs: Rinaldi's 68K externals
			// (CreateThumbnail, MovieMediaType, PictInfos ...) take a defined 'qtrs' as "QuickTime is installed"
			// A PowerPC QuickTime defines it itself: its PowerPlug INIT installs QuickTimeLib only while bit 0 (gestaltPPCQuickTimeLibPresent) is clear
			case 0x71747273:return guestPowerPcQuickTime?null:seesPowerPC()?1:app==null&&quickTime.version()!=0?0:null;
			case 0x6d746370:return 0x0203;                     // mtcp: MacTCP 2.0.x
			case 0x6f74616e:return OpenTransport.ENABLED?0x0001|0x0002|0x0010|0x0020:null; // otan: Open Transport present and loaded, TCP/IP present and loaded (no AppleTalk or IPX through OT)
			case 0x6f747672:return OpenTransport.ENABLED?(app!=null&&app.powerPC?0x02608000:0x01308000):null; // otvr: 2.6 for PowerPC programs (Mac OS 9), 1.3 for 68K ones (the last for 68K Macs)
			case 0x61746c6b:return appleTalk.isEnabled()?58:0;   // atlk: AppleTalk 58 (Phase 2), 0 = switched off
			case 0x61746b76:return appleTalk.isEnabled()?0x58008000:0; // atkv: 58.0 final
			case 0x6469746c:return 1;                          // ditl: AppendDITL/CountDITL/ShortenDITL present (DialogManager68k.commToolboxDispatch); MandelNet said "requires System 7" without it
			case 0x70706320:return 0;                          // ppc : no PPC toolbox
			case 0x68656c70:return 1;                          // help: Help Manager
			// dbac: gestaltDBAccessMgrPresent - System 7's built-in Data Access Manager (_Pack13, MiscManagers68k: InitDBPack works, with no
			// 'ddev' to connect to). GetDBAccessManager said "Undefined" and its stack's demo button stopped with a script error on it
			case 0x64626163:return 1;
			// 'nlup' (StandardNBP) stays undefined: CommToolbox68k's StandardNBP shows no lookup dialog (it answers nlCancel at once), so a
			// program that offers "choose a server" only when the selector is there keeps that item off rather than doing nothing
			case 0x6564746e:return 1;                          // edtn: gestaltEditionMgrPresent - every System 7 has it, and MacApp 3 programs refuse to start without it (Blackjack Solitaire: "lacks the required hardware or system software"); Publish & Subscribe is EditionManager68k. Was misspelled 'edit' (undefined)
			// dsig: undefined - DigiSign (the AOCE signature manager, SigDispatch $AA5D) only exists with PowerTalk installed. Disk Copy
			// 6.3 took a noErr answer as "present" and called SIGFileIsSigned through the unimplemented trap when mounting an image
			case 0x64736967:return null;
			case 0x76656e74:return 0;
			case 0x736c7463:return 0;                          // sltc: no Slot Manager
			case 0x706d6772:return 0;                          // pmgr
			// fndr: gestaltFinderAttr of Mac OS 9's Finder (drop event, magic placement, AEProcessAppleEvent, scriptable (OSL), 4 GB volumes,
			// clippings, full Drag Manager, large flavours, Extensible Folder Manager) / 7.5's (the first four and clippings). XTernal's
			// RestartMac/ShutdownMac/SleepMac said "Finder Suite not available" without it; the Finder stand-in takes their events
			// (AppleEvents68k.send). A real Finder running in the VM defines its own.
			case 0x666e6472:return isFinder()?null:baseSystemVersion>=0x0800?0x06df:0x004f;
			case 0x706f7772:return 0;                          // powr: Power Manager attributes, defined on every Mac since System 7.5 (bit 0 clear = no Power Manager); FastAction skipped its whole start-up (menus, Apple event handlers) when the selector was undefined
			// 'appr' stays undefined (gestaltUndefSelectorErr), as on a System without the Appearance Manager; some applications
			// Enhanced took a noErr answer as "present" and called RegisterAppearanceClient
			case 0x73637270:return 0;                          // scrp: Script Manager
			case 0x73637269:return 0x0710;                     // scri: Script Manager version 7.1 (some programs show English menus when this is undefined)
			case 0x73637223:return encoding==StackTextEncoding.JAPANESE?2:1; // scr#: enabled scripts - Roman, and Japanese on a KanjiTalk System
			case 0x6b657973:return 0;
			case 0x6a706e20:return encoding==StackTextEncoding.JAPANESE?1:0;
			case 0x6d626172:return MacAppHost.MENU_BAR_HEIGHT; // mbar
			case 0x7363726e:return 0;                          // scr
			case 0x766d2020:return 0;                          // vm  : off
			case 0x61646220:return 0;
			case 0x64726167:return 1;                          // drag: Drag Manager present
			// teat: gestaltTEHasGetHiliteRgn (TextEdit68k has TEGetHiliteRgn). SimpleText drags text only when both 'drag' and this
			// bit are set; undefined, a press in the selection started a new selection instead of a drag
			case 0x74656174:return 1;
			case 0x69636f6e:return 1;                          // icon: Icon Utilities
			case 0x636c746e:return 1;                          // cltn: Collection Manager
			case 0x636d7463:return ColorSync68k.VERSION;       // cmtc: ColorSync 3.0
			case 0x636d7461:return 0x3;                        // cmta: high-level matching, ColorSync library loaded
			// xlat: Translation Manager (PowerPC, GetPath API). Not for the System 7.5 Finder: with Easy Open present it calls the
			// extension's private Finder selectors ($03 $0D $0F-$13 $2F $30 $32-$34) that this Translation Manager has not got
			case 0x786c6174:return isFinder()?null:0x0d;
			case 0x63746276:return 2;                          // ctbv: Communications Toolbox 2 (CommToolbox68k)
			// the CTB managers themselves (CommToolbox68k implements all four): GetManager/Informant said "Terminal = False" although
			// 'ctbv' is 2. crm : gestaltCRMPresent; conn: present, CMSearch fix, CMGetErrorString, multiple async I/O (CMPBRead...);
			// fxfr: present; term: present, TMGetErrorString
			case 0x63726d20:return 1;                          // crm
			case 0x636f6e6e:return 0x0001|0x0002|0x0004|0x0008; // conn
			case 0x66786672:return 1;                          // fxfr
			case 0x7465726d:return 0x0001|0x0002;              // term
			// hdwr: gestaltHardwareAttr of the Mac IIvx this VM is: VIA1, VIA2, SCC, SCSI (a bus with no devices: scsiDispatch),
			// the universal ROM; defined on every Mac since System 6.0.4. via1/via2/sccr/sccw: where those chips are (MacIo68k)
			case 0x68647772:return 0x0001|0x0002|0x0010|0x0080|0x01000000;
			case 0x76696131:return io==null?null:io.base;      // via1
			case 0x76696132:return io==null?null:io.base+MacIo68k.VIA2_OFFSET; // via2
			case 0x73636372:case 0x73636377:return io==null?null:io.base+MacIo68k.SCC_OFFSET; // sccr, sccw
			case 0x73637369:return 0;                          // scsi: the original SCSI Manager (no asynchronous SCSI)
			case 0x72737263:return 1;                          // rsrc: gestaltPartialRsrcs (Read/WritePartialResource)
			case 0x73657220:return 0;                          // ser : no GPi lines wired to the ports
			case 0x6c6d656d:try{int sys=memory.partitionBase()!=0?APPL_ZONE:memory.readLong(0x2a6);return sys>0?sys:null;}catch(M68kCpuException e){return null;} // lmem: low memory ends where the System heap (SysZone) begins
			case 0x74726565:return 1;                          // tree: AVL tree routines
			case 0x666f6c64:return 1;                          // fold: FindFolder
			case 0x6d697864:return seesPowerPC()?1:0;        // mixd: Mixed Mode on a Power Macintosh
			case 0x636f6465:return 0;                          // 'code'
			case 0x63667267:return seesPowerPC()?1:null;      // cfrg: gestaltCFMPresent (the PowerPC Code Fragment Manager of a Power Macintosh)
			case 0x70706366:return seesPowerPC()?0x0003:null; // ppcf: PowerPC floating-point features
			case 0x6c617a79:return 0;
			case 0x74786d72:return 0;
			case 0x74736d67:return 0;                          // tsmg? Text Services
			case 0x74736d76:return 0x0150;                     // tsmv: Text Services Manager 1.5
			case 0x49434170:return "false".equals(System.getProperty("hz68k.internetconfig"))?null:0x02008000; // ICAp: Internet Config version (NumVersion 2.0, like the extension's 'vers'); JPEGBatcher wants v2 or later
			case 0x74736d61:return 0;                          // tsma: TSM attributes
			case 0x74686473:return 0x0001|0x0002;              // thds: Thread Manager present, specific-match support
			case 0x74786d76:return 0;
			case 0x70726e74:return 0;
			case 0x6d6d6772:return 1;                          // mmgr? Memory Manager
			case 0x70726f62:return 1;
			case 0x65707020:return 0;
			case 0x6c6f6769:return 3;                          // logi: 68030 logical RAM etc.
			case 0x6c6f6f70:return 0;
			case 0x64657620:return 0;
			case 0x69636d70:return quickTime.version()==0?null:app==null?imageCompression.version():1; // icmp: Image Compression Manager comes with QuickTime (an external's: CodecManagerVersion of QuickTime 4.0.3; CompressPictFile wants >= 15)
			case 0x616d6963:return 0;
			default:return null;
		}
	}

	/**
	 * _HeapDispatch ($A0A4), an undocumented Memory Manager entry of System 7.5 and later. QuickTime 4's 68K loader is
	 * the only known caller; its selectors were inferred from how it uses the results: 0 on a patch Handle before
	 * SetTrapAddress (a residency hint), 13 with A0 = size returning a Handle (the code block allocation), 2/3 on every
	 * loaded block before and after an allocation (lock/unlock so the heap can compact), 6 and 8 with parameter blocks
	 * (zone bookkeeping) which just succeed.
	 */
	private boolean heapDispatch(M68kCpu cpu) throws M68kCpuException {
		int selector=cpu.d[0]&0xffff;
		switch(selector){
			// QuickTime's segment loader keeps a lock flag in bit 31 of these Handles' master pointers and masks addresses with
			// $FFFFFF (24-bit style), so its code must lie in the first 16 MB of the heap.
			case 13:{int size=cpu.a[0];int h=api.createHandleBelow(Math.max(0,size),0x01000000);cpu.a[0]=h;cpu.d[0]=h==0?MEM_FULL_ERR:NO_ERR;return true;}
			case 2:if(cpu.a[0]!=0)api.setHandleState(cpu.a[0],api.handleState(cpu.a[0])|0x80);cpu.d[0]=NO_ERR;return true;
			case 3:if(cpu.a[0]!=0)api.setHandleState(cpu.a[0],api.handleState(cpu.a[0])&~0x80);cpu.d[0]=NO_ERR;return true;
			default:cpu.d[0]=NO_ERR;return true;
		}
	}

	/**
	 * $A829 _LayerDispatch, the undocumented System 7 Layer Manager. HyperCard 2.x tests the trap and, when it exists,
	 * walks its windows from the layer instead of WindowList ($9D6): selector 3 (no arguments) returns the process's
	 * layer, selector 6 (LayerPtr) the first window of that layer (the rest follow the nextWindow links), selector 2
	 * (WindowPtr) whether a list entry is a nested layer rather than a window. Without them `the windows` is empty and
	 * `show window "startUp"` after a `picture` fails with "No such window". The layer is a token: this Window Manager
	 * keeps one flat list, so it stands for WindowList. Selector 9 is the extended ('dlgx'/'wind') window positioning the
	 * Dialog Manager uses: pascal (WindowPtr, where, parent, screen) with the three position bits of the extended flags
	 * (where: 0 none, 1 centre, 2 alert position, 3 stagger; the others select the main screen, the parent window or
	 * the parent's screen), no result; QuickTime calls it itself before showing its own dialogs.
	 */
	/** Pack9, the PPC Browser: PPCBrowser(prompt, applListLabel, defaultSpecified, VAR theLocation, VAR thePortInfo, portFilter,
	 *  theLocNBPType) : OSErr, MOVE.W #$0D00,D0 (13 words of parameters, selector 0): this machine's programs ({@link PpcBrowser68k}) */
	private boolean pack9(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;
		if((selector&0xff)!=0)throw new M68kMacApiException(cpu.pc,0xa82b,"Pack9 selector $"+Integer.toHexString(selector));
		return PpcBrowser68k.browse(this,cpu);
	}

	private boolean layerDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;
		switch(selector){
			case 2:{int window=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeWord(cpu.a[7],window!=0&&window==appLayer?0x100:0);return true;} // IsLayer
			case 3:{if(appLayer==0)appLayer=api.createHandle(new byte[156]);memory.writeLong(cpu.a[7],appLayer);return true;} // the process's layer (a WindowRecord-sized block, never linked into the list)
			case 6:{cpu.a[7]+=4;memory.writeLong(cpu.a[7],memory.readLong(0x9d6));return true;} // first window of the layer
			case 0xffff:{ // GetRootLayer (MOVEQ #-1): the layer tree the System 7.5 Finder walks - windowPic ($94) is a layer's first child,
				// nextWindow ($90) its next sibling; here one process layer below the root, holding this program's window list
				if(rootLayer==0){rootLayer=memory.allocate(0x9c,4);memory.fill(rootLayer,0x9c,0);processLayer=memory.allocate(0x9c,4);memory.fill(processLayer,0x9c,0);memory.writeLong(rootLayer+0x94,processLayer);}
				memory.writeLong(processLayer+0x94,memory.readLong(0x9d6));memory.writeLong(cpu.a[7],rootLayer);return true;}
			case 0xfff8:{ // EachWindow(layer, stopWindow, parent, proc, refCon) : OSErr - calls pascal short proc(window, layer, refCon)
				// for each window front to back until it returns non-zero. Kaleidoscope's panel passes GetRootLayer's layer, -1, NIL
				// and redraws each window (InvalRect/ValidRect) or recomputes the visible regions (CalcVisBehind, then 701 = stop) when
				// the scheme changes; ignored, its 20 bytes of arguments stayed on the stack and the panel returned to garbage
				int refCon=memory.readLong(cpu.a[7]),proc=memory.readLong(cpu.a[7]+4),layer=memory.readLong(cpu.a[7]+16);cpu.a[7]+=20;
				java.util.List<Integer> list=new java.util.ArrayList<Integer>();
				for(int w=memory.readLong(0x9d6);w!=0&&list.size()<1000;w=memory.readLong(w+144))list.add(w);
				int[] d=cpu.d.clone(),a=cpu.a.clone();
				try{for(int w:list){
					cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushLong(w);cpu.pushLong(layer);cpu.pushLong(refCon);
					cpu.callPascal(proc,12);int stop=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;
					if(stop!=0)break;}}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
				memory.writeWord(cpu.a[7],NO_ERR);return true;}
			// (window, byte, byte) : Boolean - whether the window is placed well enough on the screens; Key Caps asks after
			// NewCWindow and auto-positions it (selector 9) on FALSE. The host keeps windows on the display itself: TRUE
			case 12:{cpu.a[7]+=8;writeBoolean(cpu.a[7],true);return true;}
			case 9:{
				int screen=memory.readByte(cpu.a[7]),parent=memory.readByte(cpu.a[7]+2),where=memory.readByte(cpu.a[7]+4),window=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
				if(window!=0&&where!=0)windows.autoPosition(window,where);
				return true;}
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] $A829 selector "+selector+" ignored");
		return true;
	}
	private int appLayer,rootLayer,processLayer;
	/** The KCHR the System's key cache points at (ExpandMem emKeyCache). */
	private int keyCache;

	/** _GestaltValueDispatch ($ABF1): NewGestaltValue ($0401), ReplaceGestaltValue ($0402), SetGestaltValue ($0404), DeleteGestaltValue ($0203); the selector's high byte is the argument size in words. */
	/**
	 * The Contextual Menu Manager (_ContextualMenuDispatch $AA72, selector in D0): a control-click asks for a contextual menu,
	 * ContextualMenuSelect pops the program's menu up where the click was (no Contextual Menu plug-ins add items to it).
	 */
	private boolean contextualMenuDispatch(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7];
		switch(cpu.d[0]&0xffff){
			case 1:memory.writeLong(sp,NO_ERR);return true;                                 // InitContextualMenus
			case 2:{int ev=memory.readLong(sp);cpu.a[7]+=4;                                   // IsShowContextualMenuClick(EventRecord*)
				boolean control=ev!=0&&memory.readWord(ev)==1&&(memory.readWord(ev+14)&0x1000)!=0;memory.writeWord(cpu.a[7],control?0x0100:0);return true;}
			case 3:{ // ContextualMenuSelect(menu, globalLocation, reserved, helpType, helpItemString, selection*, userSelectionType*, menuID*, menuItem*)
				int menuItem=memory.readLong(sp),menuId=memory.readLong(sp+4),type=memory.readLong(sp+8);
				int where=memory.readLong(sp+26),h=memory.readLong(sp+30);cpu.a[7]+=34;
				int chosen=h==0?0:menus.popUp(h,(short)where,(short)(where>>>16)); // Point: v in the high word
				if(type!=0)memory.writeLong(type,chosen!=0?1:0);                                 // kCMMenuItemSelected / kCMNothingSelected
				if(chosen!=0){if(menuId!=0)memory.writeWord(menuId,chosen>>>16);if(menuItem!=0)memory.writeWord(menuItem,chosen&0xffff);}
				memory.writeLong(cpu.a[7],chosen!=0?NO_ERR:-128);return true;}                 // userCanceledErr
			case 4:cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true;                      // ProcessIsContextualMenuClient: false
			default:throw new M68kMacApiException(cpu.pc,0xaa72,"Contextual Menu Manager selector "+(cpu.d[0]&0xffff));
		}
	}
	private boolean gestaltValueDispatch(M68kCpu cpu) throws M68kCpuException {
		int selector=cpu.d[0]&0xffff,words=(selector>>>8)&0xff,code=selector&0xff;
		int sp=cpu.a[7];int gestaltSelector,value=0;
		if(words>=4){value=memory.readLong(sp);gestaltSelector=memory.readLong(sp+4);}else gestaltSelector=memory.readLong(sp);
		cpu.a[7]+=words*2;
		int err=NO_ERR;
		switch(code){
			case 1:if(gestaltProcs.containsKey(gestaltSelector)||gestaltValue(gestaltSelector)!=null)err=GESTALT_DUP;else gestaltValues.put(gestaltSelector,value);break;
			case 2:if(!gestaltProcs.containsKey(gestaltSelector)&&gestaltValue(gestaltSelector)==null)err=GESTALT_UNDEF;else{gestaltProcs.remove(gestaltSelector);gestaltValues.put(gestaltSelector,value);}break;
			case 4:gestaltProcs.remove(gestaltSelector);gestaltValues.put(gestaltSelector,value);break;
			case 3:if(gestaltValues.remove(gestaltSelector)==null&&gestaltProcs.remove(gestaltSelector)==null)err=GESTALT_UNDEF;break;
			default:err=GESTALT_UNDEF;
		}
		memory.writeWord(cpu.a[7],err);return true;
	}

	/**
	 * The working directory of the System Folder, what BootDrive and SysEnvirons' sysVRefNum hold on a real System 7:
	 * programs look for their support folders there (some applications open a support folder in sysVRefNum for translators
	 * translators; with the bare volume -1 it searched the disk's root and saved every format as its own). Used as a
	 * volume with a dirID, the refnum still names the startup disk (installers may create folders at dirID 2). Externals get
	 * it too, as under HyperCard: Cosmic Osmo's SysDir XFCN walks it up to "<disk>:System Folder:" (-1, the stack's folder,
	 * made it answer "CO.Intro.hzb:").
	 */
	private int systemFolderRef(){if(files==null||files.fileManager==null)return -1;try{return files.fileManager.workingDirectoryRefFor(files.systemFolder);}catch(Exception e){return -1;}}
	/**
	 * _MixedModeDispatch ($AA59) for 68K code: a program that finds Mixed Mode through Gestalt (68K programs see a Power
	 * Macintosh, {@link #POWERPC_MACHINE}) builds its UPPs with the trap. A 68K routine is its own UPP here (68K code calls UPPs
	 * directly); PowerPC ones get a real routine descriptor when the PowerPC runtime is there.
	 * 0 NewRoutineDescriptorTrap(proc, procInfo, ISA), 1 DisposeRoutineDescriptorTrap(upp), 2 NewFatRoutineDescriptorTrap(m68k,
	 * ppc, procInfo), 3/4 Save/RestoreMixedModeState(storage, version) - Pascal, D0 the selector.
	 */
	private boolean mixedModeDispatch(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7];
		switch(cpu.d[0]&0xffff){
			case 0:{int isa=memory.readByte(sp),procInfo=memory.readLong(sp+2),proc=memory.readLong(sp+6);cpu.a[7]+=10;int upp=proc;
				if((isa&0x7f)==1&&proc!=0&&app!=null){if(app.ppc==null)app.ppc=PpcRuntime.forCallsFrom68k(app);upp=app.ppc.newRoutineDescriptor(proc,procInfo,PpcRuntime.ISA_PPC);}
				memory.writeLong(cpu.a[7],upp);return true;}
			case 1:{int upp=memory.readLong(sp);cpu.a[7]+=4;if(upp!=0&&app!=null&&app.ppc!=null&&app.ppc.isRoutineDescriptor(upp)&&memory.isAllocated(upp))memory.free(upp);return true;}
			case 2:{int m68k=memory.readLong(sp+8);cpu.a[7]+=12;memory.writeLong(cpu.a[7],m68k);return true;}
			case 3:case 4:cpu.a[7]+=8;memory.writeWord(cpu.a[7],NO_ERR);return true;
			default:throw new M68kMacApiException(cpu.pc,0xaa59,"MixedModeDispatch selector "+(cpu.d[0]&0xffff));
		}
	}
	/**
	 * _CodeFragmentDispatch ($AA5A) from 68K code: the PowerPC Code Fragment Manager of a Power Macintosh, which 68K programs
	 * reach through this trap (an installer loading a PowerPC library to call it through Mixed Mode). Pascal, the selector
	 * word on top: 1 GetSharedLibrary, 2 GetDiskFragment, 3 GetMemFragment, 4 CloseConnection, 5 FindSymbol, 6 CountSymbols,
	 * 7 GetIndSymbol - served by the PowerPC runtime's own CFM (started for the program when it has none).
	 */
	private boolean codeFragmentDispatch(M68kCpu cpu) throws M68kException {
		int selector=(short)memory.readWord(cpu.a[7]);int sp=cpu.a[7]+2;
		String name;int count;
		switch(selector){
			// private selectors of system software: -20 (FSSpec*, Boolean) registers the libraries of a file (an extension's INIT
			// registers its own file: QuickTime, the Speech Manager); -17 (connection, connection, long) ties a fragment to another
			// one it updates (QuickTime's installers). Registered files are searched for import libraries; the tie is accepted.
			case -20:{int spec=memory.readLong(sp+2);cpu.a[7]=sp+6;
				if(app==null){memory.writeWord(cpu.a[7],-43);return true;} // an external (no program): no libraries to register
				if(app.ppc==null)app.ppc=PpcRuntime.forCallsFrom68k(app);
				java.io.File f=spec==0?null:app.ppc.libraries.fileOf(spec);
				if(f!=null&&f.isFile()&&!app.ppc.registeredLibraryFiles.contains(f))app.ppc.registeredLibraryFiles.add(f);
				memory.writeWord(cpu.a[7],f!=null&&f.isFile()?NO_ERR:-43);return true;}
			case -17:cpu.a[7]=sp+12;memory.writeWord(cpu.a[7],NO_ERR);return true;
			// -19 (cfrg Ptr, VAR, VAR, VAR memberPtr): the 'cfrg' member a 68K caller could run natively. MPW Shell 3.5's 68K code asks
			// it for every fat tool and runs the PowerPC member through the private selectors -1..-3 when it gets one; those are not
			// here, so the answer is cfragArchitectureErr and the Shell runs the tool's 68K CODE, as on a 68K Mac.
			case -19:cpu.a[7]=sp+16;memory.writeWord(cpu.a[7],-2823);return true;
			case 1:name="GetSharedLibrary";count=6;break;
			case 2:name="GetDiskFragment";count=8;break;
			case 3:name="GetMemFragment";count=7;break;
			case 4:name="CloseConnection";count=1;break;
			case 5:name="FindSymbol";count=4;break;
			case 6:name="CountSymbols";count=2;break;
			case 7:name="GetIndSymbol";count=5;break;
			default:throw new M68kMacApiException(cpu.pc,0xaa5a,"CodeFragmentDispatch selector "+selector);
		}
		int[] args=new int[count];for(int i=0;i<count;i++)args[count-1-i]=memory.readLong(sp+4*i); // pushed left to right
		cpu.a[7]=sp+4*count;
		// an external runs in HyperCard on a 68K Mac (Gestalt sysa 68K): no fragments to connect to - cfragNoLibraryErr, so
		// Rinaldi's FullNavigation answers "Navigation not available" (a Java NullPointerException ended its handler silently)
		if(app==null){memory.writeWord(cpu.a[7],selector<=3?-2804:-2801);return true;} // cfragNoLibraryErr / cfragConnectionIDErr
		if(app.ppc==null)app.ppc=PpcRuntime.forCallsFrom68k(app);
		int[] saved68=cpu.d.clone(),savedA=cpu.a.clone();
		int result=app.ppc.libraries.invoke(name,args);
		if(PpcRuntime.TRACE)System.err.printf("[ppc] 68K %s(%s) -> %d%s%n",name,java.util.Arrays.stream(args).mapToObj(a->String.format("%08X",a)).collect(java.util.stream.Collectors.joining(" ")),result,
			selector==1&&args[0]!=0?" \""+app.ppc.pString(args[0])+"\"":selector==5&&args[1]!=0?" \""+app.ppc.pString(args[1])+"\"":"");
		System.arraycopy(saved68,0,cpu.d,0,8);System.arraycopy(savedA,0,cpu.a,0,8);
		memory.writeWord(cpu.a[7],result);return true;
	}
	/** The program's PowerPC runtime, started at the first use (null in an external's VM, which has no program). */
	PpcRuntime powerPcRuntime() throws M68kCpuException {
		if(app==null)return null;
		if(app.ppc==null)app.ppc=PpcRuntime.forCallsFrom68k(app);
		return app.ppc;
	}
	/** The System version the program sees (Gestalt sysv, as a program may have replaced it). */
	int systemVersion(){Integer v=gestaltValue(0x73797376);return v!=null?v:baseSystemVersion;}
	/**
	 * 68K programs see a Power Macintosh (sysa gestaltPowerPC, a 603e, Mixed Mode and the Code Fragment Manager, a Power Mac
	 * 7200), as 68K code did on the real ones: installers of PowerPC software (VISE's "Microsoft Internet First Run") refuse a
	 * 68K Mac. The processor type (proc, CPUFlag) stays the 68030 the emulator is. -Dhz68k.powerpc=false (and an external's VM): a 68K
	 * Macintosh, the 68030 Mac IIvx.
	 */
	static final boolean POWERPC_MACHINE=!"false".equals(System.getProperty("hz68k.powerpc"));
	/**
	 * Whether the code asking Gestalt now sees the Power Macintosh. With PPC_EXTENSIONS (the default) everything does, the
	 * system software around the program too: the extensions' INITs (QuickTime installs its PowerPC trap patches and QTMathPPC,
	 * loading fragments through _CodeFragmentDispatch and calling them through Mixed Mode), control panels, desk accessories
	 * and the QuickTime service VM. -Dhz68k.ppcextensions=false keeps those on the 68K Macintosh whose 68K halves they run;
	 * only the program itself (after the INITs) then sees the Power Macintosh.
	 */
	static final boolean PPC_EXTENSIONS=!"false".equals(System.getProperty("hz68k.ppcextensions"));
	boolean seesPowerPC(){
		if(!POWERPC_MACHINE)return false;
		if(app==null)return powerPcExternalAsks();
		if(PPC_EXTENSIONS)return true;
		if(app.service)return false;
		if(app.hosted instanceof ControlPanelHost68k||app.hosted instanceof DeskAccessoryHost68k)return false;
		return extensions==null||extensions.finished();
	}
	private boolean sysEnvirons(M68kCpu cpu) throws M68kCpuException {
		int record=cpu.a[0];
		if(record==0){cpu.d[0]=PARAM_ERR;return true;}
		// machineType = Gestalt 'mach' - 2 (the Mac IIvx or Power Mac Gestalt reports), keyboardType = Gestalt 'kbd ' (the same codes),
		// atDrvrVersNum = Gestalt 'atlk' (0 while AppleTalk is off): Apple's sysEnvironment XFCN showed an LC III, a standard
		// keyboard and no AppleTalk while Gestalt said otherwise
		Integer machine=gestaltValue(0x6d616368);memory.writeWord(record,2);memory.writeWord(record+2,machine==null?0:Math.max(0,machine-2));memory.writeWord(record+4,systemVersion());memory.writeWord(record+6,4);
		memory.writeByte(record+8,M68kFpu.ENABLED?1:0);memory.writeByte(record+9,1);memory.writeWord(record+10,KeyboardLayout68k.GESTALT_KEYBOARD);
		memory.writeWord(record+12,appleTalk.isEnabled()?58:0);memory.writeWord(record+14,systemFolderRef()); // hasFPU, hasColorQD, keyboardType, atDrvrVersNum, sysVRefNum
		// versionRequested is D0.W (some callers leave data in the high word); envBadVers -5501 for a nonpositive version,
		// envVersTooBig -5502 above 2 (the record is still filled), envNotPresent -5500 is only ever returned by the glue
		// (MacErrors.h; these were one off, and Siege of Darkwood asks for version $7FFF and accepts only -5502).
		int version=(short)cpu.d[0];cpu.d[0]=version<=0?-5501:version>2?-5502:NO_ERR;return true;
	}

	// ------------------------------------------------------------------ memory extras

	// Both set MemErr like every Memory Manager trap: PowerPlant's StHandleLocker reads MemError() after HGetState, and a stale error
	// from an earlier failed call (QuickTime probes SetHandleSize on bad handles) would make it throw.
	private boolean hGetState(M68kCpu cpu){int handle=cpu.a[0];cpu.d[0]=handle==0?NIL_HANDLE_ERR:(api.handleState(handle)&0xff);api.memErr(handle==0?NIL_HANDLE_ERR:NO_ERR);return true;}
	private boolean hSetState(M68kCpu cpu){int handle=cpu.a[0];if(handle!=0)api.setHandleState(handle,cpu.d[0]&0xff);cpu.d[0]=NO_ERR;api.memErr(NO_ERR);return true;}
	private boolean recoverHandle(M68kCpu cpu) throws M68kCpuException {
		int h=api.quickDraw().recoverGWorldPixels(cpu.a[0]); // a GWorld's own pixels: see QuickDraw68k.recoverGWorldPixels
		cpu.a[0]=h!=0?h:api.handleForPointer(cpu.a[0]);return true;}
	private boolean setPtrSize(M68kCpu cpu) throws M68kCpuException {cpu.d[0]=api.setPtrSize(cpu.a[0],cpu.d[0]);return true;}

	private Boolean finder;
	/** Whether the running program is a Finder (file type 'FNDR'). */
	boolean isFinder(){
		if(finder==null){boolean f=false;try{if(app!=null&&app.file!=null)f=FinderInfo68k.type(app.file)==ResourceFile68k.fourcc("FNDR");}catch(Exception ignored){}finder=f;}
		return finder;
	}
	private int desktopLayer;
	/** Whether a window takes its content region from the desktop layer (the System 7.5 Finder's desktop window). */
	boolean isDesktopWindow(int w){try{return desktopLayer!=0&&w!=0&&memory.readLong(w+0x76)==memory.readLong(desktopLayer+0x18);}catch(M68kCpuException e){return false;}}
	/** A window record standing for the desktop layer: its visRgn covers the screen below the menu bar, its updateRgn is empty. */
	private int desktopLayer() throws M68kCpuException {
		if(desktopLayer!=0)return desktopLayer;
		int[] screen=api.quickDraw().screenBounds();
		int layer=memory.allocate(0x9c,4);memory.fill(layer,0x9c,0);
		memory.writeWord(layer+16,screen[0]);memory.writeWord(layer+18,screen[1]);memory.writeWord(layer+20,screen[2]);memory.writeWord(layer+22,screen[3]); // portRect
		int desk=api.createHandle(HostQuickDraw68k.regionBytes(screen[0]+MacAppHost.MENU_BAR_HEIGHT,screen[1],screen[2],screen[3]));
		memory.writeLong(layer+0x18,desk);                                                                  // visRgn
		memory.writeLong(layer+0x1c,api.createHandle(HostQuickDraw68k.regionBytes(-32767,-32767,32767,32767))); // clipRgn
		memory.writeByte(layer+0x6e,1);                                                                     // visible
		memory.writeLong(layer+0x72,api.createHandle(HostQuickDraw68k.regionBytes(screen[0],screen[1],screen[2],screen[3]))); // strucRgn
		memory.writeLong(layer+0x76,desk);                                                                  // contRgn
		memory.writeLong(layer+0x7a,api.createHandle(HostQuickDraw68k.regionBytes(0,0,0,0)));                // updateRgn
		return desktopLayer=layer;
	}
	private boolean osDispatch(M68kCpu cpu) throws M68kException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		switch(selector){
			// Undocumented MultiFinder selector 1 ("GetActiveTaskInfo" in Aladdin InstallerMaker 1.x: After Dark 2.0's installer,
			// called before a "live update" of the startup disk): (size: word, buffer: Ptr) : word = number of running tasks;
			// more than one and the installer tries to quit the others. Only this program counts.
			case 0x01:cpu.a[7]+=6;memory.writeWord(cpu.a[7],1);return true;
			// GetSwitchInfo(SwitchInfoPtr): the Process Manager's own signature and version (DoGetSwitchInfo in Puppet.c: the null
			// process's p_signature/p_version) - Word 5.1 asks for it at start-up to see which MultiFinder it runs under.
			case 0x02:{int info=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(info!=0){memory.writeLong(info,0x4d414353);memory.writeLong(info+4,0x07558000);}return true;}
			// undocumented (VAR layer: WindowPtr) : OSErr - the desktop's layer. The System 7.5 Finder calls it with the system zone
			// current (SysError 41 on an error) and gives its desktop window the layer's visRgn (as contRgn and visRgn) and updateRgn
			case 0x14:{int layerVar=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(layerVar!=0)memory.writeLong(layerVar,desktopLayer());memory.writeWord(cpu.a[7],NO_ERR);return true;}
			case 0x15:{cpu.a[7]+=4;memory.writeLong(cpu.a[7],api.guestAvailable(memory.largestFreeBlock()));return true;} // TempMaxMem(grow)
			// TempTopMem/MFTopMem: Ptr, no arguments (it popped 4 bytes as if it had TempMaxMem's VAR grow: Apple's TotalRAM got its
			// stack 4 bytes off), the RAM as Gestalt 'ram ' says (was the largest free block: more than the RAM)
			case 0x16:memory.writeLong(cpu.a[7],memory.physicalSize());return true;
			// undocumented MultiFinder selector (index: Integer, info: Ptr) : OSErr - the running application number index (from 0).
			// Apple's ApplicationList XFCN (Support Tools eXternals 1.2.5, 1990) walks indexes 0..32 skipping errors and reads the
			// signature at +8, the partition size at +30 and the name (Str31) at +46 of a 78-byte record; the rest is cleared here.
			// Unimplemented, it stopped the calling script with an unsupported-call error.
			case 0x17:{int info=memory.readLong(cpu.a[7]),index=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;
				List<Object[]> list=new ArrayList<Object[]>(); // {name, signature, partition size}
				if(app==null&&stack!=null)list.add(new Object[]{"HyperCard",HYPERCARD_CREATOR,memory.size()});
				boolean finderRuns=!finderStandInPresent();
				for(MacApp68k a:MacApp68k.runningApplications()){if(a.toolbox==null)continue;if(a.toolbox.isFinder())finderRuns=true;
					list.add(new Object[]{a.name,files.fileCreator(a.file),a.toolbox.memory.size()});}
				if(!finderRuns)list.add(new Object[]{"Finder",0x4d414353,0x58e00});
				if(info==0||index<0||index>=list.size()){memory.writeWord(cpu.a[7],-600);return true;} // procNotFound
				Object[] entry=list.get(index);memory.fill(info,78,0);
				memory.writeLong(info+8,(Integer)entry[1]);memory.writeLong(info+30,(Integer)entry[2]);writePascal(info+46,(String)entry[0],31);
				memory.writeWord(cpu.a[7],NO_ERR);return true;}
			case 0x18:memory.writeLong(cpu.a[7],api.guestAvailable(memory.availableBytes()));return true; // TempFreeMem
			case 0x1d:{int resultPtr=memory.readLong(cpu.a[7]),size=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8; // TempNewHandle
				int handle=0,err=NO_ERR;try{api.checkGuestRoom(size);handle=api.createHandle(new byte[Math.max(0,size)]);}catch(M68kCpuException e){err=MEM_FULL_ERR;}
				if(resultPtr!=0)memory.writeWord(resultPtr,err);memory.writeLong(cpu.a[7],handle);return true;}
			case 0x1e:case 0x1f:{int resultPtr=memory.readLong(cpu.a[7]);cpu.a[7]+=8;if(resultPtr!=0)memory.writeWord(resultPtr,NO_ERR);return true;}
			case 0x20:{int resultPtr=memory.readLong(cpu.a[7]),handle=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;api.disposeHandleAndFree(handle);if(resultPtr!=0)memory.writeWord(resultPtr,NO_ERR);return true;}
			// AddAppleMenuItem(itemName, ScriptCode, itemStyle, itemIcon Handle, itemMark, itemHelpString, itemRefCon) : OSErr (22 bytes,
			// Process Manager MFPrivate.h): the System 7.5 Finder adds each file of "Apple Menu Items" with it. The items are not shown
			// in the host menus (no 'amis' events to send back); accepted with the Process Manager's own paramErr checks
			case 0x31:{int refCon=memory.readLong(cpu.a[7]),name=memory.readLong(cpu.a[7]+18);cpu.a[7]+=22;
				memory.writeWord(cpu.a[7],name==0||memory.readByte(name)==0||refCon==0?PARAM_ERR:NO_ERR);return true;}
			case 0x32:cpu.a[7]+=4;memory.writeWord(cpu.a[7],NO_ERR);return true; // DeleteAppleMenuItem(itemRefCon) : OSErr - the System 7.5 Finder calls it with 0 (all its items) once it is set up
			case 0x33:return appleEvents.acceptHighLevelEvent(cpu);
			case 0x34:return appleEvents.postHighLevelEvent(cpu);
			case 0x36:{int name=memory.readLong(cpu.a[7]),spec=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8; // LaunchDeskAccessory(pFileSpec, pDAName): the Finder opening a 'dfil' file
				memory.writeWord(cpu.a[7],launchDeskAccessory(spec,name));return true;}
			case 0x37:{int psn=memory.readLong(cpu.a[7]);cpu.a[7]+=4;writePsn(psn,currentProcessOverride!=0?currentProcessOverride:serialNumber());memory.writeWord(cpu.a[7],NO_ERR);return true;} // GetCurrentProcess
			case 0x39:{// GetFrontProcess glue pushes PSN* then a -1 long (moveq #-1,d0; move.l d0,-(sp)) before the selector
				int psn=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;MacApp68k front=MacApp68k.frontApplication();awaitFirstEventCall(front);
				writePsn(psn,MacApp68k.finderFront&&finderStandInPresent()?FINDER_SERIAL:front==null?serialNumber():front.serialNumber);memory.writeWord(cpu.a[7],NO_ERR);return true;}
			// GetNextProcess: HyperCard when an external asks (it runs in HyperCard's process; Rinaldi's ProcessesList listed the
			// Finder only), the running programs, then the Finder stand-in (a Mac always runs the Finder; Outlook Express 5 waits
			// in a GetNextProcess loop until a process of type 'FNDR', creator 'MACS' appears) unless a real Finder runs
			case 0x38:{int psn=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int current=memory.readLong(psn+4);List<MacApp68k> all=MacApp68k.runningApplications();
				boolean finderRuns=false;for(MacApp68k a:all)if(a.toolbox!=null&&a.toolbox.isFinder())finderRuns=true;
				if(!finderStandInPresent())finderRuns=true; // quit by an installer: no stand-in in the list
				List<Integer> serials=new ArrayList<Integer>();
				if(app==null&&stack!=null)serials.add(HYPERCARD_SERIAL);
				for(MacApp68k a:all)serials.add(a.serialNumber);
				if(!finderRuns)serials.add(FINDER_SERIAL);
				int nextSerial=0;
				if(current==0)nextSerial=serials.isEmpty()?0:serials.get(0);
				else{int i=serials.indexOf(current);nextSerial=i>=0&&i+1<serials.size()?serials.get(i+1):0;}
				if(Mac68kApi.TRACE)System.err.println("[68K] GetNextProcess "+current+" -> "+nextSerial+" of "+serials.size());
				if(nextSerial==0){writePsn(psn,0);memory.writeWord(cpu.a[7],-600);}else{writePsn(psn,nextSerial);memory.writeWord(cpu.a[7],NO_ERR);}return true;}
			case 0x3a:return getProcessInformation(cpu);
			case 0x3b:{int psn=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int serial=serialOf(psn); // SetFrontProcess
				if(serial==FINDER_SERIAL&&finderStandInPresent()){ // the Finder stand-in: the launcher, HyperZebra's own Finder, comes forward
					MacApp68k.finderFront=true;GLauncher.showLauncher(true);memory.writeWord(cpu.a[7],NO_ERR);return true;}
				MacApp68k target=MacApp68k.bySerial(serial);
				if(target!=null&&!target.backgroundOnly()){MacApp68k.activeApplication=target;MacApp68k.finderFront=false;}
				if(target!=null&&target.host!=null)target.host.setProcessHidden(false); // a hidden program made the front one comes back
				if(target!=null&&target.host!=null){MacAppHost.HostWindow hw=target.host.frontWindow();if(hw!=null)target.host.selectWindow(hw.record);}memory.writeWord(cpu.a[7],NO_ERR);return true;}
			case 0x3c:cpu.a[7]+=4;memory.writeWord(cpu.a[7],NO_ERR);return true; // WakeUpProcess
			case 0x3d:{int resultPtr=memory.readLong(cpu.a[7]),b=memory.readLong(cpu.a[7]+4),a=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				boolean same=serialOf(a)==serialOf(b);
				if(resultPtr!=0)memory.writeByte(resultPtr,same?1:0);/* VAR Boolean: one byte */memory.writeWord(cpu.a[7],NO_ERR);return true;} // SameProcess
			case 0x40:case 0x41:memory.writeWord(cpu.a[7],NO_ERR);return true; // undocumented: QuickTime brackets TempNewHandle with these when Gestalt reports real temporary memory (OSErr results, no arguments)
			case 0x42:case 0x43:cpu.a[7]+=8;memory.writeWord(cpu.a[7],NO_ERR);return true; // undocumented pair (0L, Ptr) : OSErr - the System 7.5 Finder brackets something with them and ignores the results
			case 0x45:return appleEvents.getSpecificHighLevelEvent(cpu);
			case 0x46:{ // GetPortNameFromProcessSerialNumber(VAR PPCPortRec, PSN): a process's default PPC port - its name, ppcByCreatorAndType,
				// its signature and 'ep01'. Microsoft OLE 2 copies the strings out without checking the result (garbage lengths overran its stack).
				int psn=memory.readLong(cpu.a[7]),port=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
				memory.writeWord(cpu.a[7],writePortFor(serialOf(psn),port));return true;}
			case 0x35:{ // GetProcessSerialNumberFromPortName(PPCPortRec, VAR PSN): the program whose default port that is
				int psn=memory.readLong(cpu.a[7]),port=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;MacApp68k found=null;
				if(port!=0&&memory.readWord(port+0x24)==1&&memory.readLong(port+0x2a)==0x65703031){int creator=memory.readLong(port+0x26);
					for(MacApp68k a:MacApp68k.runningApplications())if(a.toolbox!=null&&files.fileCreator(a.file)==creator){found=a;break;}}
				if(found==null){memory.writeWord(cpu.a[7],-600);return true;}
				if(psn!=0)writePsn(psn,found.serialNumber);memory.writeWord(cpu.a[7],NO_ERR);return true;}
			// undocumented (VAR extra, VAR limit) : OSErr - how far the system heap may grow. The System 7.5 Finder compares
			// ExpandMem+$1AE - 4K + the bytes it needs with limit (and limit + extra) and gives up with memFullErr past them;
			// Sound Manager 3.4's decompressor components ask for limit before allocating. The one heap here is shared: its
			// size in use (+4K) goes to ExpandMem+$1AE and the limit is that plus what is still free.
			case 0x47:{int limitPtr=memory.readLong(cpu.a[7]),extraPtr=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
				long used=Math.max(0L,(long)memory.heapEnd()-memory.heapStart()-memory.availableBytes());
				int expandMem=memory.readLong(0x2b6);if(expandMem!=0)memory.writeLong(expandMem+0x1ae,(int)Math.min(0x7fff0000L,used+0x1000));
				if(limitPtr!=0)memory.writeLong(limitPtr,(int)Math.min(0x7fff0000L,used+memory.availableBytes()));
				if(extraPtr!=0)memory.writeLong(extraPtr,0);
				memory.writeWord(cpu.a[7],NO_ERR);return true;}
			// IsProcessVisible(PSN*) : Boolean (Mac OS 9; DragThing 4.5 asks at start-up, A-Dock tracks hidden programs with it):
			// false only for a program hidden with ShowHideProcess
			case 0x5f:{int psn=memory.readLong(cpu.a[7]);cpu.a[7]+=4;MacApp68k target=psn==0?null:MacApp68k.bySerial(serialOf(psn));
				writeBoolean(cpu.a[7],target==null||target.host==null||!target.host.processHidden());return true;}
			// ShowHideProcess(PSN*, visible: Boolean) : OSErr (Mac OS 9; A-Dock's Hide / Show All): the program's windows leave the
			// screen or come back (MacAppHost.setProcessHidden); hiding the front program makes the next one the front one
			case 0x60:{boolean visible=memory.readByte(cpu.a[7])!=0;int psn=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;
				int serial=psn==0?0:serialOf(psn);MacApp68k target=MacApp68k.bySerial(serial);
				if(serial==FINDER_SERIAL){if(!visible&&MacApp68k.finderFront)MacApp68k.finderFront=false;memory.writeWord(cpu.a[7],NO_ERR);return true;} // the stand-in has no windows of its own
				if(target==null||target.toolbox==null){memory.writeWord(cpu.a[7],-600);return true;}
				if(target.host!=null)target.host.setProcessHidden(!visible);
				if(!visible&&MacApp68k.frontApplication()==target){MacApp68k.activeApplication=null;
					MacApp68k next=null;for(MacApp68k a:MacApp68k.runningApplications())if(a!=target&&!a.backgroundOnly()&&a.toolbox!=null&&(a.host==null||!a.host.processHidden()))next=a;
					if(next!=null){MacApp68k.activeApplication=next;if(next.host!=null){MacAppHost.HostWindow hw=next.host.frontWindow();if(hw!=null)next.host.selectWindow(hw.record);}}
					else MacApp68k.finderFront=true;}
				memory.writeWord(cpu.a[7],NO_ERR);return true;}
			// undocumented Mac OS 8 call (ProcPtr) : OSErr - NotePad installs a pascal (VAR result: Integer; EventRecord*) : OSErr
			// hook when the Drag Manager is present; the hook answers whether a click in its background window starts a text drag
			// instead of bringing the window forward. Accepted and never called: background clicks activate the window.
			case 0x55:cpu.a[7]+=4;memory.writeWord(cpu.a[7],NO_ERR);return true;
			// NewSystemWindow (undocumented, Mac OS 8.5+; the name is in A-Dock's traceback tables): (bounds, title, visible, procID, behind,
			// goAwayFlag, refCon, handler UPP, handlerData) : WindowPtr - a system window with an event handler (A-Dock's dock and
			// tooltips): see WindowManager68k.createSystemWindow
			case 0x5a:{int a=cpu.a[7];int data=memory.readLong(a),proc=memory.readLong(a+4),refCon=memory.readLong(a+8);boolean goAway=memory.readByte(a+12)!=0;int behind=memory.readLong(a+14);
				int procId=(short)memory.readWord(a+18);boolean visible=memory.readByte(a+20)!=0;int title=memory.readLong(a+22),bounds=memory.readLong(a+26);cpu.a[7]+=30;
				int w=windows.createSystemWindow(windows.readRect(bounds),title==0?"":readPascal(title),visible,procId,behind,goAway,refCon,proc,data);
				if(Mac68kApi.TRACE)System.err.println("[68K] system window $"+Integer.toHexString(w)+" procID "+procId+" handler $"+Integer.toHexString(proc)+" data $"+Integer.toHexString(data));
				memory.writeLong(cpu.a[7],w);return true;}
			case 0x5b:{int w=memory.readLong(cpu.a[7]);cpu.a[7]+=4; // DisposeSystemWindow(WindowPtr) : OSErr - the other half of NewSystemWindow ($5A)
				boolean ok=windows.isSystemWindow(w);if(ok)windows.disposeWindow(w);memory.writeWord(cpu.a[7],ok?NO_ERR:PARAM_ERR);return true;}
			default:if(Mac68kApi.TRACE)System.err.println("[68K] OSDispatch "+selector+" args "+java.util.HexFormat.of().formatHex(memory.readBytes(cpu.a[7],48)));
				throw new M68kMacApiException(cpu.pc,0xa88f,"OSDispatch selector "+selector);
		}
	}

	/**
	 * The programs the Process Manager's application menu lists, by name: every running program that is not background-only
	 * and is not a control panel (those run in the Finder's process), and the Finder stand-in unless a real Finder runs. The
	 * second element of each entry is "1" for the front process (the menu's check mark).
	 */
	List<String[]> applicationMenuEntries(){
		List<String[]> r=new ArrayList<String[]>();MacApp68k front=MacApp68k.frontApplication();boolean finderRuns=!finderStandInPresent();
		for(MacApp68k a:MacApp68k.runningApplications()){
			if(a.toolbox==null||a.finished||a.backgroundOnly()||a.hosted instanceof ControlPanelHost68k)continue;
			if(a.toolbox.isFinder())finderRuns=true;
			r.add(new String[]{a.name,a==front&&!MacApp68k.finderFront?"1":""});}
		if(!finderRuns)r.add(new String[]{"Finder",front==null||MacApp68k.finderFront?"1":""});
		r.sort((x,y)->x[0].compareToIgnoreCase(y[0])); // the menu lists the programs alphabetically
		return r;
	}
	/** A PPCPortRec (0x48 bytes) of a process's default port: its name, ppcByCreatorAndType, its signature and 'ep01'; procNotFound without such a process. */
	int writePortFor(int serial,int port) throws M68kCpuException {
		MacApp68k target=app!=null&&serial==app.serialNumber?app:MacApp68k.bySerial(serial);
		String processName=serial==FINDER_SERIAL?"Finder":serial==HYPERCARD_SERIAL&&target==null?"HyperCard":target!=null?target.name:null;
		int creator=serial==FINDER_SERIAL?0x4d414353:serial==HYPERCARD_SERIAL&&target==null?HYPERCARD_CREATOR:target!=null?files.fileCreator(target.file):0;
		if(processName==null||port==0)return -600;
		writePort(port,processName,creator);
		return NO_ERR;
	}
	private void writePort(int port,String processName,int creator) throws M68kCpuException {
		memory.fill(port,0x48,0);memory.writeWord(port,encoding==StackTextEncoding.JAPANESE?1:0); // nameScript
		writePascal(port+2,processName,32);memory.writeWord(port+0x24,1); // portKindSelector ppcByCreatorAndType
		memory.writeLong(port+0x26,creator);memory.writeLong(port+0x2a,0x65703031); // 'ep01'
	}
	/** This program's open PPC ports: refNum -> the PPCPortRec's bytes (name, kind, type), and the asynchronous PPCInforms waiting on each. */
	private final Map<Integer,byte[]> ppcPorts=new HashMap<Integer,byte[]>();
	private final Map<Integer,List<Integer>> ppcInforms=new HashMap<Integer,List<Integer>>();
	private static int nextPpcPort=1;
	/**
	 * The PPC Toolbox's port calls: PPCOpen (portRefNum +38, portName +46) gives a port of this machine (portNameExistsErr for
	 * a name this program already opened), PPCInform (portRefNum +38) waits asynchronously for a session that never comes (a
	 * synchronous one would wait for ever: notInitErr as before), PPCClose ends the port and its waiting informs with
	 * portClosedErr. PPCStart finds no other program's port (destPortErr); the session calls have no session (noSessionErr).
	 * tn3270 2.5 (PPC build) opens a port for its debugger at start-up: "PPC debugger cannot be used: unable to open a port".
	 */
	private int ppcPortCall(M68kCpu cpu,int code,int pb,boolean async) throws M68kException {
		switch(code){
			case 1:{int name=memory.readLong(pb+46);if(name==0)return -919; // PPCOpen; badPortNameErr
				byte[] rec=memory.readBytes(name,0x2e);
				for(byte[] open:ppcPorts.values())if(java.util.Arrays.equals(open,rec))return -910; // portNameExistsErr
				int ref;synchronized(Toolbox68k.class){ref=nextPpcPort++;if(nextPpcPort>0x7fff)nextPpcPort=1;}
				ppcPorts.put(ref,rec);memory.writeWord(pb+38,ref);memory.writeByte(pb+55,0); // nbpRegistered false
				if(Mac68kApi.TRACE)System.err.println("[68K] PPCOpen \""+readPascal(name+2)+"\" -> port "+ref);
				return NO_ERR;}
			case 3:{int ref=memory.readWord(pb+38);if(!ppcPorts.containsKey(ref))return -903; // PPCInform; noPortErr
				if(!async)return -900;
				memory.writeWord(pb+16,1);ppcInforms.computeIfAbsent(ref,k->new ArrayList<Integer>()).add(pb);return 1;}
			case 9:{int ref=memory.readWord(pb+38);if(ppcPorts.remove(ref)==null)return -903; // PPCClose
				List<Integer> waiting=ppcInforms.remove(ref);
				if(waiting!=null)for(int inform:waiting){memory.writeWord(inform+16,-916);files.completion(cpu,inform);} // portClosedErr
				return NO_ERR;}
			case 2:return -906;                                  // PPCStart: destPortErr
			case 4:case 5:case 6:case 7:case 8:return -908;      // PPCAccept, PPCReject, PPCWrite, PPCRead, PPCEnd: noSessionErr
			default:return -909;                                 // badReqErr
		}
	}
	/** The computer's name, as Sharing Setup keeps it ('STR ' -16413): never the host's own. */
	static final String COMPUTER_NAME="HyperZebra";
	/**
	 * IPCListPorts (IPCListPortsPBRec after the 40-byte PPC header: startIndex +40, requestCount +42, actualCount +44, portName +46,
	 * locationName +50, bufferPtr +54, as Rinaldi's PPCList fills it in): the default ports of this machine's programs - the program asking (HyperCard for an external), the others running, the
	 * Finder - as PortInfoRecs (filler, authRequired, PPCPortRec: 0x4A bytes), from startIndex on; noResponseErr for another machine.
	 */
	private int ppcListPorts(int pb) throws M68kCpuException {
		int start=memory.readWord(pb+40),request=memory.readWord(pb+42),filter=memory.readLong(pb+46),location=memory.readLong(pb+50),buffer=memory.readLong(pb+54);
		if(location!=0&&memory.readWord(location)==1){ // ppcNBPLocation: this machine by its name, in its zone
			// EntityName's three Str32Fields are 34 bytes each (a Str32 padded to a word): the zone is at +68. Read at +66, PPCList 1.1
			// (which writes "PPCToolBox" as the type) found garbage there and said "Unknown node name"
			String object=readPascal(location+2),zone=readPascal(location+2+34+34);
			if(!object.equalsIgnoreCase(COMPUTER_NAME)||!(zone.isEmpty()||zone.equals("*"))){memory.writeWord(pb+44,0);return -915;}
		}
		List<Object[]> ports=new ArrayList<Object[]>();
		int me=serialNumber();
		if(me!=0){MacApp68k self=app;ports.add(new Object[]{self!=null?self.name:"HyperCard",self!=null&&self.file!=null?files.fileCreator(self.file):HYPERCARD_CREATOR});}
		List<Object[]> others=new ArrayList<Object[]>();
		for(MacApp68k a:MacApp68k.runningApplications())if(a!=app&&a.toolbox!=null&&!a.finished&&!(a.hosted instanceof ControlPanelHost68k))others.add(new Object[]{a.name,a.file==null?0:files.fileCreator(a.file)});
		if(finderStandInPresent())others.add(new Object[]{"Finder",0x4d414353});
		others.sort((x,y)->((String)x[0]).compareToIgnoreCase((String)y[0]));ports.addAll(others);
		String nameFilter=filter==0?"=":readPascal(filter+2);int kind=filter==0?0:memory.readWord(filter+0x24),creatorFilter=filter==0?0:memory.readLong(filter+0x26);
		int written=0,index=0;
		for(Object[] p:ports){
			if(!nameFilter.equals("=")&&!nameFilter.isEmpty()&&!nameFilter.equalsIgnoreCase((String)p[0]))continue;
			if(kind==1&&creatorFilter!=0&&creatorFilter!=0x3d3d3d3d&&creatorFilter!=(Integer)p[1])continue; // ppcByCreatorAndType ("====" any)
			if(index++<start)continue;
			if(written>=request||buffer==0)break;
			int at=buffer+written*0x4a;memory.writeWord(at,0);writePort(at+2,(String)p[0],(Integer)p[1]);
			// the PPC Toolbox hands ports out with their type as a string (ppcByString, "WILDep01"): PPCList reads the creator as its
			// characters 1-4 (it showed "ILDe" for HyperCard from the creator-and-type form)
			memory.writeWord(at+2+0x24,2);memory.writeByte(at+2+0x26,8);memory.writeLong(at+2+0x27,(Integer)p[1]);memory.writeLong(at+2+0x2b,0x65703031);
			written++;
		}
		memory.writeWord(pb+44,written);
		if(Mac68kApi.TRACE)System.err.println("[68K] IPCListPorts from "+start+" -> "+written+" of "+ports.size()+(filter==0?"":" (name \""+nameFilter+"\" kind "+kind+" "+java.util.HexFormat.of().formatHex(memory.readBytes(filter,0x2e))+")")+(location==0?"":" location "+java.util.HexFormat.of().formatHex(memory.readBytes(location,8))));
		return NO_ERR;
	}
	/** This program's process serial number (low long); an external (XCMD/XFCN) runs in HyperCard's process. */
	private int serialNumber(){return app!=null?app.serialNumber:stack!=null?HYPERCARD_SERIAL:0;}
	/** The process GetCurrentProcess reports while this program's code runs on behalf of another one (a launcher's trap patch run for the program it launched, {@link LaunchHookBridge68k}); 0 = this program. */
	int currentProcessOverride;
	/** A ProcessSerialNumber: high long 1 for a real process, so program serial 2 is never read as kCurrentProcess ({0, 2}). */
	private void writePsn(int psn,int serial) throws M68kCpuException {if(psn!=0){memory.writeLong(psn,serial==0?0:PSN_HIGH);memory.writeLong(psn+4,serial);}}
	static final int PSN_HIGH=1;
	/** The program a ProcessSerialNumber names: kCurrentProcess {0, 2} and kNoProcess {0, 0} stand for this one. */
	/**
	 * GetFrontProcess asked by another program while the front one has not made its first event call yet: on a Mac the asker
	 * did not run in between (the new front program had the processor from its launch to its first WaitNextEvent), so it
	 * waits for that here. CinéMac's saver INIT (PMSCRSVR) types its own file 'APPL', launches it as the Director projector
	 * and types it 'cdev' again once the projector is in front; running at the same time, it did so before the projector
	 * looked at its file type ("Problem with the file", 27). Only during the program's start-up: one that never asks for events
	 * holds nobody up for long.
	 */
	private void awaitFirstEventCall(MacApp68k front){
		if(front==null||front==app)return;
		long until=front.startedAt+FIRST_EVENT_CALL_WAIT_MS;
		while(!front.finished&&(front.toolbox==null||!front.toolbox.events.firstEventCallMade())&&System.currentTimeMillis()<until)
			try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
	}
	private static final long FIRST_EVENT_CALL_WAIT_MS=20000;
	private int serialOf(int psn) throws M68kCpuException {int high=memory.readLong(psn),low=memory.readLong(psn+4);return high==0&&(low==2||low==0)?serialNumber():low;}

	/** Serial number of the program that sent 'quit' to the Finder stand-in (0: nobody). Until that program ends the stand-in is gone from the
	 *  process list and GetProcessInformation fails for it, as after a real Finder quit: the Apple Software Installer ("Install iMovie") quits the
	 *  Finder before it installs and waits until the Finder's process has disappeared; the Finder is back when the installer has ended. */
	private static volatile int finderQuitBy;
	static void quitFinderStandIn(int bySerial){finderQuitBy=bySerial;}
	static boolean finderStandInPresent(){int by=finderQuitBy;if(by==0)return true;if(MacApp68k.bySerial(by)!=null)return false;finderQuitBy=0;return true;}
	/** Process serial number (low long) of the pseudo-process that stands for the Finder: the sender of the Open Documents / Open Application events the host queues. */
	/** The stand-in process of Apple Software Restore (see launch): present while AsrRestore copies the image. */
	static final int ASR_SERIAL=0x2002;
	static volatile boolean asrBusy;
	static final int FINDER_SERIAL=0x2000;
	/** Process serial number (low long) of HyperCard, the process an external (XCMD/XFCN) runs in. The stand-ins' serials are small
	 *  numbers as a Mac's (8192 + n; programs count from 1 and skip them, {@link #standInSerial}): they were 'WILD'/'Find' as longs,
	 *  and Process Monitor's PSN column printed 1464421444 over the process names. */
	static final int HYPERCARD_SERIAL=0x2001;
	/** HyperCard's signature (creator), which externals and the Desktop Database know it by. */
	static final int HYPERCARD_CREATOR=0x57494c44; // 'WILD'
	/** Whether a serial number is one of the stand-in processes' (no program gets it). */
	static boolean standInSerial(int serial){return serial==FINDER_SERIAL||serial==HYPERCARD_SERIAL||serial==ASR_SERIAL;}
	private boolean getProcessInformation(M68kCpu cpu) throws M68kCpuException {
		int info=memory.readLong(cpu.a[7]),psn=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		int serial=serialOf(psn);
		if(serial==ASR_SERIAL){
			if(!asrBusy){memory.writeWord(cpu.a[7],-600);return true;}
			int namePtr=memory.readLong(info+4);if(namePtr!=0)writePascal(namePtr,"Apple Software Restore",31);
			memory.writeLong(info+8,PSN_HIGH);memory.writeLong(info+12,serial);memory.writeLong(info+16,MacApp68k.TYPE_APPL);memory.writeLong(info+20,0x41535220); // 'ASR '
			memory.writeLong(info+24,0x58e0);for(int o=28;o<56;o+=4)memory.writeLong(info+o,0);memory.writeLong(info+52,1); // processActiveTime: the Upgrader waits until it is not 0 (the program has started)
			memory.writeWord(cpu.a[7],NO_ERR);return true;}
		if(serial==FINDER_SERIAL){ // the Finder stand-in: a name, a signature and a mode, no file
			if(!finderStandInPresent()){memory.writeWord(cpu.a[7],-600);return true;}
			int namePtr=memory.readLong(info+4);if(namePtr!=0)writePascal(namePtr,"Finder",31);
			memory.writeLong(info+8,PSN_HIGH);memory.writeLong(info+12,serial);memory.writeLong(info+16,0x464e4452);memory.writeLong(info+20,0x4d414353); // processType 'FNDR' (the Finder's file type), signature 'MACS'
			memory.writeLong(info+24,0x58e0);memory.writeLong(info+28,0); // Finder 7's SIZE flags: suspend/resume, background, activate on switch, 32-bit, high-level events
			// processSize: Finder 7.5's partition (its 'SIZE' -1 asks for 360K); ListProcesses listed "Finder, 0"
			memory.writeLong(info+32,0x5a000);memory.writeLong(info+36,0);
			memory.writeLong(info+40,0);memory.writeLong(info+44,0);memory.writeLong(info+48,0);memory.writeLong(info+52,0);
			// processAppSpec: the System Folder's Finder file (A-Dock takes the Finder's icon from it and left the Finder out of its dock without one)
			int specPtr=memory.readLong(info)>=60?memory.readLong(info+56):0;
			// (written also before the stub file exists: ListProcesses names each process from its spec, and the Finder's row showed
			// the name left in its buffer by the process before)
			if(specPtr!=0){File finder=files.systemFolder==null?null:new File(files.systemFolder,"Finder");try{if(finder!=null)files.writeFileSpec(specPtr,finder);}catch(IOException|RuntimeException e){}}
			memory.writeWord(cpu.a[7],NO_ERR);return true;}
		MacApp68k target=app!=null&&serial==app.serialNumber?app:MacApp68k.bySerial(serial); // a service VM is not in the running list
		if(target==null&&app==null&&stack!=null&&(serial==serialNumber()||serial==HYPERCARD_SERIAL)){
			// An external (XCMD/XFCN) runs in HyperCard's process: describe HyperCard, with this VM's memory as its partition.
			// This used to fail with procNotFound, and externals that ignore the error read the uninitialized record - the
			// GetMem XFCN of はいぱあれもん Vol.6 took the garbage processSize for HyperCard's partition and refused to start.
			int namePtr=memory.readLong(info+4);if(namePtr!=0)writePascal(namePtr,"HyperCard",31);
			memory.writeLong(info+8,PSN_HIGH);memory.writeLong(info+12,HYPERCARD_SERIAL);
			memory.writeLong(info+16,MacApp68k.TYPE_APPL);memory.writeLong(info+20,HYPERCARD_CREATOR);
			memory.writeLong(info+24,0x58c0); // HyperCard 2.x's SIZE flags: suspend/resume, background, activate on switch, 32-bit, high-level events
			memory.writeLong(info+28,applZone);memory.writeLong(info+32,memory.size());memory.writeLong(info+36,memory.availableBytes());
			memory.writeLong(info+40,0);memory.writeLong(info+44,0);memory.writeLong(info+48,0);memory.writeLong(info+52,0);
			// processAppSpec: HyperCard in its folder, which an external sees as the stack's (the default directory). ListProcesses
			// names each process from its spec and listed HyperCard as ", 16777216" (the Finder's row had a name)
			int specPtr=memory.readLong(info)>=60?memory.readLong(info+56):0;
			File folder=files.fileManager==null?null:files.fileManager.stackFolder();
			if(specPtr!=0&&folder!=null){try{files.writeFileSpec(specPtr,new File(folder,"HyperCard"));}catch(IOException|RuntimeException e){}}
			memory.writeWord(cpu.a[7],NO_ERR);return true;
		}
		if(target==null){memory.writeWord(cpu.a[7],-600);return true;}
		int length=memory.readLong(info);
		// A control panel runs in the Finder's process on a real Mac: its process is the Finder's (name, 'MACS'), not an
		// application with the panel's creator. Kaleidoscope's panel looks for a process with its own creator 'Acid' (its
		// faceless "Kaleidoscope Extension") and took itself for it.
		boolean controlPanel=target.hosted instanceof ControlPanelHost68k;
		int namePtr=memory.readLong(info+4);if(namePtr!=0)writePascal(namePtr,controlPanel?"Finder":target.name,31);
		memory.writeLong(info+8,PSN_HIGH);memory.writeLong(info+12,serial);
		boolean accessory=target.hosted instanceof DeskAccessoryHost68k;
		memory.writeLong(info+16,accessory?0x6466696c:MacApp68k.TYPE_APPL);memory.writeLong(info+20,controlPanel?0x4d414353:files.fileCreator(target.file)); // a desk accessory's process: type 'dfil'
		memory.writeLong(info+24,(target.sizeFlags()&0xffff)|(accessory?0x00020000:0)); // processMode: the SIZE flags word (modeHighLevelEventAware = isHighLevelEventAware...) + modeDeskAccessory
		// processLocation, processSize, processFreeMem: a program in this address space (M68kMemory.launchPartition) has its partition
		M68kMemory targetMemory=target.memory;Toolbox68k targetToolbox=target.toolbox;
		if(targetMemory!=null&&targetMemory.shared()&&targetToolbox!=null){memory.writeLong(info+28,targetToolbox.applZone);memory.writeLong(info+32,targetMemory.partitionSize());memory.writeLong(info+36,targetMemory.availableBytes());}
		else{memory.writeLong(info+28,APPL_ZONE);memory.writeLong(info+32,memory.size());memory.writeLong(info+36,memory.availableBytes());}
		// processLauncher: the program that launched it, else the Finder (stand-in). FileMaker Pro 4.0 shows its Open dialog at
		// start-up only when its launcher's signature is 'MACS' (or MPW Shell, QuicKeys - its 'ShOp' resource)
		memory.writeLong(info+40,PSN_HIGH);memory.writeLong(info+44,target.launcherSerial!=0?target.launcherSerial:FINDER_SERIAL);
		memory.writeLong(info+48,(int)((target.startedAt-(app!=null?app.startedAt:target.startedAt))/16L));
		memory.writeLong(info+52,target.toolbox==null?0:Math.max(1,(int)((System.currentTimeMillis()-target.startedAt)/16L))); // processActiveTime (ticks): the Apple Software Installer waits for it to be non-zero after a launch
		int specPtr=length>=60?memory.readLong(info+56):0;
		if(specPtr!=0){try{files.writeFileSpec(specPtr,target.file);}catch(IOException e){}}
		if(Mac68kApi.TRACE)System.err.println("[68K] GetProcessInformation serial "+serial+" -> "+java.util.HexFormat.of().formatHex(memory.readBytes(info,Math.min(60,Math.max(0,length))))+(specPtr!=0?" spec "+java.util.HexFormat.of().formatHex(memory.readBytes(specPtr,40)):""));
		memory.writeWord(cpu.a[7],NO_ERR);return true;
	}

	// ------------------------------------------------------------------ time, VBL, notification

	/** Nesting depth of the Menu Manager's offscreen MDEF probes (layout learning in a scratch port). HyperCard's Tools palette
	 *  MDEF waits with _Delay whenever the highlighted tool changes; nobody sees those probes, and the ~480 of them held the
	 *  start-up (Home) back for 24 s. */
	int offscreenMdef;
	/** The probe now tells the MDEF the pointer left the menu: the button counts as up. AutoCAD R11's tear-off MDEF takes the
	 *  pointer leaving with the button down for a tear-off and tracks until the release, which a probe never sends. */
	boolean offscreenMdefLeft;
	private boolean delay(M68kCpu cpu) throws M68kException {
		if(offscreenMdef>0){cpu.d[0]=tickCount();return true;}
		int ticks=Math.max(0,cpu.a[0]);
		long target=System.nanoTime()+(long)ticks*16_666_667L;
		cpu.yielded();
		while(true){
			long remaining=target-System.nanoTime();if(remaining<=0)break;
			if(cpu.abortRequested())throw new M68kAbortException(cpu.pc);
			runDeferred();runTimers();
			long due=nextDueNanos()-System.nanoTime();if(due<=0)continue; // a MIDI wake-up is due
			try{Thread.sleep(Math.max(1L,Math.min(Math.min(remaining,due)/1_000_000L,10L)));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
		}
		cpu.d[0]=tickCount();updateTicks();return true;
	}

	// TMTask.qType (record+4) carries the Time Manager's flags: bit 15 = task active (set by PrimeTime, cleared when the
	// task expires or is removed), bit 14 = installed with InsXTime. Programs poll the active bit as a delay with tmAddr 0
	// (Terazza XFCN waits between animation frames with InsXTime/PrimeTime + BitTst(qType,0)).
	private static final int TM_TASK_ACTIVE=0x8000,TM_TASK_EXTENDED=0x4000;
	private void setTaskActive(int record,boolean active) throws M68kCpuException {int q=memory.readWord(record+4);memory.writeWord(record+4,active?q|TM_TASK_ACTIVE:q&~TM_TASK_ACTIVE);}
	private boolean insTime(M68kCpu cpu,boolean extended) throws M68kCpuException {
		int record=cpu.a[0];if(record==0){cpu.d[0]=PARAM_ERR;return true;}
		for(TimerTask t:timers)if(t.record==record){cpu.d[0]=NO_ERR;return true;}
		timers.add(new TimerTask(record,extended));memory.writeWord(record+4,extended?TM_TASK_EXTENDED:0);cpu.d[0]=NO_ERR;return true;
	}
	/**
	 * RmvTime leaves the unexpired time in tmCount (record+10), in the units PrimeTime was given: milliseconds, or negative
	 * microseconds. Programs time things with it - HyperCard's visual effects prime a 600 s task, run a frame, RmvTime, and
	 * compute the frame's duration from the remainder to pace the effect; a remainder of 0 made every effect instantaneous.
	 */
	private boolean rmvTime(M68kCpu cpu) throws M68kCpuException {int record=cpu.a[0];
		for(int i=0;i<timers.size();i++){TimerTask t=timers.get(i);if(t.record!=record)continue;
			int remaining=0;if(t.active){long micros=Math.max(0L,(t.dueNanos-System.nanoTime()+999L)/1000L);remaining=t.microseconds?(int)-Math.min(micros,Integer.MAX_VALUE):(int)Math.min((micros+999L)/1000L,Integer.MAX_VALUE);}
			memory.writeLong(record+10,remaining);timers.remove(i);setTaskActive(record,false);break;}
		cpu.d[0]=NO_ERR;return true;}
	/**
	 * BitTst on a TMTask's qType: the program spins until the task's active bit clears (some animation externals pace frames of
	 * fades so, up to 100 ms each). Spinning through the trap burns the runaway-loop budget of an external in well under
	 * that, so the wait is served here like a Delay: the thread parks until the task is due (deferred work and other
	 * timers keep running), and the spin counts as a yield.
	 */
	private int timerPollAddress;private long timerPollInstructions;
	void timerPolled(M68kCpu cpu,int address) throws M68kException {
		if(timers.isEmpty())return;
		boolean spinning=address==timerPollAddress&&cpu.instructions-timerPollInstructions<64; // the same test again within a few instructions: a wait loop, not a one-off check
		timerPollAddress=address;timerPollInstructions=cpu.instructions;
		if(!spinning)return;
		TimerTask task=null;for(TimerTask t:timers)if(t.active&&t.record+4==address){task=t;break;}
		if(task==null)return;
		cpu.yielded();
		while(task.active){
			long remaining=task.dueNanos-System.nanoTime();if(remaining<=0)break;
			if(cpu.abortRequested())throw new M68kAbortException(cpu.pc);
			runDeferred();runTimers();if(!task.active)break;
			if(cardWindow!=null)cardWindow.flush(); // a frame drawn on the card shows while the external waits for the next
			try{Thread.sleep(Math.max(1L,Math.min(remaining/1_000_000L,10L)));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
		}
		runTimers();updateTicks();
	}
	/**
	 * PrimeTime is what queues a task on a real Mac (InsTime only sets up its fields), so a task taken out with RmvTime runs
	 * again when primed without a new InsTime: VocalWriter's synthesizer removes its 8 timers when the music stops and only
	 * primes them when it starts again; ignoring those left the beat counter and karaoke still from the second play on.
	 */
	private boolean primeTime(M68kCpu cpu) throws M68kCpuException {
		int record=cpu.a[0],count=cpu.d[0];
		if(record!=0&&timers.stream().noneMatch(t->t.record==record))timers.add(new TimerTask(record,(memory.readWord(record+4)&TM_TASK_EXTENDED)!=0));
		for(TimerTask t:timers)if(t.record==record){long micros=count<0?-(long)count:(long)count*1000L,now=System.nanoTime();
			// Extended Time Manager: with tmWakeUp (record+14) non-zero the delay counts from the time the task was last due, not
			// from now - drift-free periodic tasks (SoundApp's 20 ms MIDI clock re-primes from a deferred task; counted from now,
			// the music ran 8% slow). A base far behind (the host stalled) starts over from now instead of firing a burst.
			long base=now;
			if(t.extended&&t.scheduledNanos!=0&&memory.readLong(record+14)!=0&&now-t.scheduledNanos<100_000_000L)base=t.scheduledNanos;
			t.dueNanos=base+micros*1000L;t.scheduledNanos=t.dueNanos;t.active=true;t.microseconds=count<0;setTaskActive(record,true);
			if(t.extended)memory.writeLong(record+14,(int)(t.dueNanos/1000L)|1); // tmWakeUp: when it runs (an opaque non-zero time)
			break;}
		cpu.d[0]=NO_ERR;return true;
	}
	void runTimers() throws M68kException {
		adb.poll(); // reached from every idle path and the scheduler poll: the mouse packets of a program's ADB routine
		MidiManager68k midi=api.soundManager().midiIfAny();if(midi!=null)midi.service(this); // wake-ups and read hooks, like its 1 ms interrupt
		appleTalk.service(); // NBP/ATP retries and time-outs
		if(io!=null){io.service(cpu);cpu.interruptSource=io.armed()?io:null;} // VIA timer and SCC interrupts of programs that drive the chips themselves
		if(timers.isEmpty())return;
		long now=System.nanoTime();boolean fired=false;
		for(TimerTask t:new ArrayList<TimerTask>(timers)){
			if(!t.active||t.dueNanos>now)continue;
			t.active=false;setTaskActive(t.record,false);
			int proc=memory.readLong(t.record+6);
			if(proc==0)continue;
			if(Mac68kApi.TRACE)System.err.println("[68K] time manager task $"+Integer.toHexString(t.record)+" -> $"+Integer.toHexString(proc));
			int savedA1=cpu.a[1];cpu.a[1]=t.record;
			callInterruptRoutine(proc);
			cpu.a[1]=savedA1;
			fired=true;
		}
		// the deferred tasks a timer task installed run as its interrupt ends: SoundApp plays MIDI from a 20 ms Time Manager task
		// that DTInstalls the real work, which waited for the next idle round (~45 ms a cycle: the music 2.5 times too slow)
		if(fired)runDeferredTasks();
	}
	/** When the earliest primed Time Manager task is due (nanoTime), Long.MAX_VALUE for none: idle waits end then. */
	private long timersDueNanos(){long due=Long.MAX_VALUE;for(TimerTask t:timers)if(t.active&&t.dueNanos<due)due=t.dueNanos;return due;}
	/** VBLQueue ($160, a QHdr: qFlags, qHead, qTail): tasks are also linked into the real queue, since programs may search it
	 *  for the task just installed and spin when it is missing. */
	private static final int VBL_QUEUE=0x160;
	private boolean vInstall(M68kCpu cpu) throws M68kCpuException {int record=cpu.a[0];
		if(record!=0&&!vblTasks.contains(record)){vblTasks.add(record);
			memory.writeLong(record,0);memory.writeWord(record+4,1); // qLink, qType = vType
			int tail=memory.readLong(VBL_QUEUE+6);if(tail!=0)memory.writeLong(tail,record);else memory.writeLong(VBL_QUEUE+2,record);memory.writeLong(VBL_QUEUE+6,record);}
		cpu.d[0]=NO_ERR;return true;}
	private boolean vRemove(M68kCpu cpu) throws M68kCpuException {int record=cpu.a[0];
		if(!vblTasks.contains(record)){cpu.d[0]=-2;return true;} // vTypErr: not in the queue
		unlinkVbl(record);
		cpu.d[0]=NO_ERR;return true;}
	/** DTInstall: the deferred task (dtAddr at 8, dtParm at 12 - passed in A1) runs once, at the next scheduler poll. */
	private final List<Integer> deferredTasks=new ArrayList<Integer>();
	private boolean dtInstall(M68kCpu cpu){int record=cpu.a[0];if(record!=0&&!deferredTasks.contains(record))deferredTasks.add(record);cpu.d[0]=NO_ERR;return true;}
	/** Whether a deferred task (DTInstall) is waiting to run. */
	boolean deferredTasksPending(){return !deferredTasks.isEmpty();}
	/** Runs what interrupt time would while a synchronous I/O request waits for its driver: deferred tasks and timers. */
	void runInterruptWork() throws M68kException {runDeferredTasks();runTimers();}
	private void runDeferredTasks() throws M68kException {
		while(!deferredTasks.isEmpty()){
			int record=deferredTasks.remove(0);int proc=memory.readLong(record+8);if(proc==0)continue;
			int savedA1=cpu.a[1];cpu.a[1]=memory.readLong(record+12);
			try{callRoutine(proc);}finally{cpu.a[1]=savedA1;}
		}
	}
	/**
	 * Notification response procedures run at system task time (event calls, and once the extensions have all loaded - the
	 * Finder's first event on a real Mac), never inside NMInstall nor at an interrupt-like poll: QuickTime's INIT installs a
	 * silent NMRec whose nmResp finishes its start-up and reports the extensions still waiting for a Gestalt selector as
	 * "not installed". Run at a poll during a later INIT (QuickDraw 3D's PowerPC Gestalt function made one happen inside the
	 * Speech Manager's INIT) it reported QuickTime VR, whose INIT had only just queued itself.
	 */
	void runNotificationResponses() throws M68kException {
		while(!pendingNotifications.isEmpty()){
			int record=pendingNotifications.remove(0);int response=memory.readLong(record+28);if(response==0||response==-1)continue;
			// pascal procedure (nmReqPtr: NMRecPtr): the record pointer goes on the stack (QuickTime's response ends with
			// MOVE.L (SP)+,(SP) / RTS to drop it - called without it, that overwrote the caller's saved A6 under the stack
			// and MacPaint 1.5 died in its startup). A0 carries it too for assembly-language responses.
			int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;cpu.a[0]=record;
			try{cpu.pushLong(record);cpu.callPascal(response,4);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
		}
	}
	private final List<Integer> pendingNotifications=new ArrayList<Integer>();
	/** Whether a notification response is still waiting for system task time (QuickTime finishes its start-up in one). */
	boolean notificationsPending(){return !pendingNotifications.isEmpty();}
	/**
	 * Pack2, the Disk Initialization Package (selector word on the stack). There is no floppy drive: DILoad/DIUnload are
	 * no-ops, DIBadMount(where, evtMessage) reports that the disk was ejected (1) and DIFormat/DIVerify/DIZero fail with
	 * nsDrvErr.
	 */
	private boolean diskInit(M68kCpu cpu) throws M68kException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		switch(selector){
			case 2:case 4:return true;                                              // DILoad / DIUnload
			case 0:{ // DIBadMount(where, evtMessage): on a drive the program added (Disk Copy's new image, zeroed) the disk is
				// initialized as "untitled" and mounted - the dialog that would ask first is taken as answered "Initialize"
				int drive=memory.readWord(cpu.a[7]+2);cpu.a[7]+=8;
				int result=1;
				if(files.isAddedDrive(drive)){
					result=files.mountAddedDrive(cpu,drive)[0];
					if(result==-57){result=files.initializeAddedDrive(cpu,drive,"untitled".getBytes(java.nio.charset.StandardCharsets.US_ASCII));if(result==0)result=files.mountAddedDrive(cpu,drive)[0];}
					if(result==-55)result=0;
				}
				memory.writeWord(cpu.a[7],result);return true;}                     // otherwise "ejected"
			case 6:case 8:{int drive=memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeWord(cpu.a[7],files.isAddedDrive(drive)?0:-56);return true;} // DIFormat / DIVerify(drvNum): nothing to do on a disk image, nsDrvErr otherwise
			case 10:{int name=memory.readLong(cpu.a[7]),drive=memory.readWord(cpu.a[7]+4);cpu.a[7]+=6; // DIZero(drvNum, volName): writes the volume, then mounts it
				byte[] volumeName=java.util.Arrays.copyOfRange(readPascalBytes(name),1,1+memory.readByte(name));
				int result=files.isAddedDrive(drive)?files.initializeAddedDrive(cpu,drive,volumeName):-56;
				if(result==0)files.mountZeroedDrive(drive,volumeName);
				memory.writeWord(cpu.a[7],result);return true;}
			default:memory.writeWord(cpu.a[7],-56);return true;
		}
	}
	/**
	 * The tasks a real Macintosh runs from its 60 Hz vertical-retrace interrupt: the VBL queue and the JCrsrTask hook.
	 * Both keep their own "once a tick" gate, so this may be called as often as convenient - and it must be, from every
	 * place the emulation thread waits, not only from the instruction-count poll. While a program sleeps in its event
	 * loop it executes no instructions, so the poll does not come round: Space Quest 1 drives its music from a routine
	 * hooked to JCrsrTask and got it about 17 times a second instead of 60, which played the tune at a third of its
	 * tempo. The Event Manager's idle wait and the Dialog Manager's modal loop call this too.
	 */
	void runPeriodic() throws M68kException {runVbl();runCursorTaskHook();}

	/** A program that hooks the cursor VBL vector (JCrsrTask, $8EE) gets its routine called once a tick, as the VBL interrupt
	 *  does on a real Mac: A-10 Attack! keeps its frame timing there and spins until the hook clears a counter. */
	private int lastCursorTaskTick;
	private boolean inCursorTask;
	private void runCursorTaskHook() throws M68kException {
		int vector=memory.readLong(0x8ee);if(vector==0||vector==cursorTaskGate||inCursorTask)return; // a hook that spins past a tick (Space Arena) must not re-enter itself from the poll
		int tick=tickCount();if(tick==lastCursorTaskTick)return;lastCursorTaskTick=tick;
		inCursorTask=true;try{callInterruptRoutine(vector);}finally{inCursorTask=false;}
	}
	private void runVbl() throws M68kException {
		if(vblTasks.isEmpty())return;
		int tick=tickCount();if(tick==lastVblTick)return;
		int elapsed=(int)Math.min(60,tick-lastVblTick);lastVblTick=tick;
		for(int record:new ArrayList<Integer>(vblTasks)){
			int count=memory.readWord(record+10);
			if(count==0)continue;
			count-=elapsed;
			if(count>0){memory.writeWord(record+10,count);continue;}
			memory.writeWord(record+10,0);
			int proc=memory.readLong(record+6);
			if(proc==0)continue;
			int savedA0=cpu.a[0];cpu.a[0]=record;
			callInterruptRoutine(proc);
			cpu.a[0]=savedA0;
			// a task that did not set vblCount again leaves the queue (the Vertical Retrace Manager runs it once): tetrad installs
			// a VBL task in a stack frame per delay, and the left-over record came back to life as garbage and jumped to $100
			if(vblTasks.contains(record)&&memory.readWord(record+10)==0)unlinkVbl(record);
		}
	}
	private void unlinkVbl(int record) throws M68kCpuException {
		vblTasks.remove((Integer)record);
		int previous=0,at=memory.readLong(VBL_QUEUE+2);
		while(at!=0&&at!=record){previous=at;at=memory.readLong(at);}
		if(at==record){int next=memory.readLong(record);if(previous==0)memory.writeLong(VBL_QUEUE+2,next);else memory.writeLong(previous,next);if(memory.readLong(VBL_QUEUE+6)==record)memory.writeLong(VBL_QUEUE+6,previous);memory.writeLong(record,0);}
	}
	/** The free memory a program is told about. A program without a SIZE resource predates MultiFinder and never saw more than a
	 *  few megabytes: Animation Toolkit (1985) divides FreeMem by its frame size into a 16-bit frame count, which overflowed with
	 *  the 58 MB here into a negative NewHandle and a crash - so such programs see at most a Mac Plus-sized heap. Its frame table
	 *  is a 16-bit count*4+4 bytes too: 292-byte frames overflow it above 2.39 MB (with 4 MB the NewHandle came back NIL and it
	 *  cleared its table through a stale Handle - over its own event loop in CODE 1, so it never took an event). */
	private static final int SIZELESS_FREE_MEMORY=2*1024*1024;
	/** Programs with a SIZE resource ran in partitions of a few megabytes and keep free kilobytes in 16-bit words: Souls in the System
	 *  (SIZE 6 MB) shifts FreeMem into a word and quits with "Not enough memory" once it exceeds 32 MB. */
	private static final int SIZED_FREE_MEMORY=32*1024*1024-64*1024;
	/** ApplLimit - ApplZone for MPW Shell and ToolServer (see initializeLowMemory). */
	private static final int MPW_PARTITION=24*1024*1024;
	/** An external's VM: HyperCard's partition (it has a SIZE resource): DevStack's MaxApplZone keeps the free kilobytes in a
	 *  16-bit INTEGER and said "-2226K" with the whole VM (~240 MB). */
	private int reportedFree(int bytes){if(app==null)return stack!=null?Math.min(bytes,SIZED_FREE_MEMORY):bytes;if(app.service)return bytes;return Math.min(bytes,app.hasSizeResource()?SIZED_FREE_MEMORY:SIZELESS_FREE_MEMORY);}

	/**
	 * The Slot Manager ($A06E, D0 = selector, A0 = SpBlock) for the one screen: the on-board video in slot 0 (GetVideoDefault's
	 * slot 0 / sResource $80) as a functional sResource of catDisplay/typeVideo/drSwApple whose list holds sRsrcType, sRsrcName
	 * and a mode list per depth $80 (1 bit) .. $85 (32 bits: mVidParams VPBlock, mPageCnt 1, mDevType), the modes InitGDevice
	 * switches the screen to. Prototyping Toolkit's SetColorState looks for mode $83 with SNextTypeSRsrc + SFindStruct before
	 * it asks for 256 colours, and settled for 16 with every slot empty. The declaration data lies in ordinary memory with all
	 * four byte lanes. Other slots and calls answer smEmptySlot as before.
	 */
	private boolean slotManager(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff,sp=cpu.a[0];int result;
		if(sp==0){cpu.d[0]=-50;return true;}
		int slot=memory.readByte(sp+49),id=memory.readByte(sp+50);
		switch(selector){
			case 0x00:case 0x01:case 0x02:case 0x03:case 0x05:case 0x06:{ // SReadByte/SReadWord/SReadLong/SGetCString/SGetBlock/SFindStruct: spsPointer list, spID
				int entry=sResourceEntry(memory.readLong(sp+4),id);if(entry==0){result=-330;break;} // smBadRefId
				int field=memory.readLong(entry)&0xffffff,target=entry+((field<<8)>>8);result=0;
				if(selector==0x00)memory.writeLong(sp,field&0xff);
				else if(selector==0x01)memory.writeLong(sp,field&0xffff);
				else if(selector==0x02)memory.writeLong(sp,memory.readLong(target));
				else if(selector==0x06)memory.writeLong(sp+4,target);
				else if(selector==0x03){int n=0;while(n<255&&memory.readByte(target+n)!=0)n++;int ptr=memory.allocate(n+1,2);api.adoptPointer(ptr,n+1);memory.copy(target,ptr,n+1);memory.writeLong(sp,ptr);}
				else{int length=memory.readLong(target)-4;if(length<0||length>65536){result=-331;break;}int ptr=memory.allocate(Math.max(1,length),2);api.adoptPointer(ptr,length);memory.copy(target+4,ptr,length);memory.writeLong(sp,ptr);memory.writeLong(sp+8,length);}
				break;}
			case 0x07:{int size=memory.readLong(sp+8),from=memory.readLong(sp+4),to=memory.readLong(sp);if(from==0||to==0||size<0){result=-335;break;}memory.copy(from,to,size);result=0;break;} // SReadStruct
			case 0x14:case 0x15:{ // SNextSRsrc / SNextTypeSRsrc: the next sResource after spSlot/spID (of the type spTBMask leaves to compare)
				boolean after=(byte)slot<0||slot==0&&id<0x80;
				if(selector==0x15){int mask=memory.readByte(sp+48);int[] want={(short)memory.readWord(sp+40),(short)memory.readWord(sp+42),(short)memory.readWord(sp+44),(short)memory.readWord(sp+46)},have={3,1,1,VIDEO_DRVR_HW};
					for(int i=0;i<4;i++)if((mask&(8>>i))==0&&want[i]!=have[i])after=false;}
				if(!after){result=-344;break;} // smNoMoresRsrcs
				videoSResourceInfo(sp);result=0;break;}
			case 0x16:if(slot!=0||id!=0x80){result=-351;break;}videoSResourceInfo(sp);result=0;break; // SRsrcInfo; smRecNotFnd for anything else
			case 0x18:result=slot==0?0:-300;break; // SCkCardStat
			case 0x19:{if(slot!=0||id!=0x80){result=-351;break;}int out=memory.readLong(sp);if(out!=0)writePascal(out,"."+VIDEO_SRSRC_NAME,31);result=0;break;} // SReadDrvrName
			case 0x1b:case 0x1c:{if(slot!=0){result=-300;break;}int device=memory.readLong(0x8a4),data=device==0?0:memory.readLong(device),pm=data==0?0:memory.readLong(data+22);memory.writeLong(sp,pm==0?0:memory.readLong(memory.readLong(pm)));result=0;break;} // SFindDevBase / SFindBigDevBase: the screen's baseAddr
			default:result=-300;break;
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] SlotManager selector "+selector+" slot "+slot+" id $"+Integer.toHexString(id)+" -> "+result);
		cpu.d[0]=result;return true;
	}
	private static final int VIDEO_DRVR_HW=0x0001;private static final String VIDEO_SRSRC_NAME="Display_Video_HyperZebra";
	/** The video sResource list (built once): sRsrcType, sRsrcName, sRsrcHWDevId, modes $80-$85, end of list. */
	private int videoSResource;
	private int videoSResource() throws M68kCpuException {
		if(videoSResource!=0)return videoSResource;
		int device=memory.readLong(0x8a4),data=device==0?0:memory.readLong(device); // MainDevice's gdRect
		int width=data==0?640:(short)memory.readWord(data+40)-(short)memory.readWord(data+36),height=data==0?480:(short)memory.readWord(data+38)-(short)memory.readWord(data+34);
		int modes=6,listSize=(3+modes+1)*4,modeListSize=4*4,vpSize=4+42,nameSize=VIDEO_SRSRC_NAME.length()+1;
		int total=listSize+8+((nameSize+1)&~1)+modes*(modeListSize+vpSize);
		int base=memory.allocate(total,4);memory.fill(base,total,0);
		int type=base+listSize,name=type+8,mode=name+((nameSize+1)&~1);
		memory.writeWord(type,3);memory.writeWord(type+2,1);memory.writeWord(type+4,1);memory.writeWord(type+6,VIDEO_DRVR_HW); // catDisplay, typeVideo, drSwApple
		for(int i=0;i<VIDEO_SRSRC_NAME.length();i++)memory.writeByte(name+i,VIDEO_SRSRC_NAME.charAt(i));
		int e=base;sEntry(e,1,type);e+=4;sEntry(e,2,name);e+=4;memory.writeLong(e,(8<<24)|1);e+=4; // sRsrcHWDevId 1
		for(int m=0;m<modes;m++){int depth=1<<m,list=mode+m*(modeListSize+vpSize),vp=list+modeListSize;
			sEntry(e,0x80+m,list);e+=4;
			sEntry(list,1,vp);memory.writeLong(list+4,(3<<24)|1);memory.writeLong(list+8,(4<<24)|(depth<=8?0:2));memory.writeLong(list+12,0xff000000); // mVidParams, mPageCnt 1, mDevType clutType/directType
			int rowBytes=((width*depth+31)/32)*4;
			memory.writeLong(vp,vpSize);memory.writeLong(vp+4,0);memory.writeWord(vp+8,rowBytes);memory.writeWord(vp+10,0);memory.writeWord(vp+12,0);memory.writeWord(vp+14,height);memory.writeWord(vp+16,width); // vpBaseOffset, vpRowBytes, vpBounds
			memory.writeWord(vp+18,0);memory.writeWord(vp+20,0);memory.writeLong(vp+22,0);memory.writeLong(vp+26,72<<16);memory.writeLong(vp+30,72<<16); // vpVersion, vpPackType, vpPackSize, vpHRes, vpVRes
			memory.writeWord(vp+34,depth<=8?0:16);memory.writeWord(vp+36,depth);memory.writeWord(vp+38,depth<=8?1:3);memory.writeWord(vp+40,depth<=8?depth:depth==16?5:8);memory.writeLong(vp+42,0);} // vpPixelType, vpPixelSize, vpCmpCount, vpCmpSize, vpPlaneBytes
		memory.writeLong(e,0xff000000);
		return videoSResource=base;
	}
	private void sEntry(int at,int id,int target) throws M68kCpuException {memory.writeLong(at,(id<<24)|((target-at)&0xffffff));}
	/** The entry with {@code id} in the sResource list at {@code list} (ids ascending, $FF ends it), or 0. */
	private int sResourceEntry(int list,int id) throws M68kCpuException {
		if(list==0)return 0;
		for(int i=0;i<256;i++){int entry=list+i*4,eid=memory.readByte(entry);if(eid==0xff||eid>id)return 0;if(eid==id)return entry;}
		return 0;
	}
	/** The SpBlock outputs for the video sResource (slot 0, id $80). */
	private void videoSResourceInfo(int sp) throws M68kCpuException {
		memory.writeLong(sp+4,videoSResource());memory.writeWord(sp+36,0);memory.writeWord(sp+38,QuickDraw68k.VIDEO_DRIVER_REF);
		memory.writeWord(sp+40,3);memory.writeWord(sp+42,1);memory.writeWord(sp+44,1);memory.writeWord(sp+46,VIDEO_DRVR_HW);
		memory.writeByte(sp+49,0);memory.writeByte(sp+50,0x80);memory.writeByte(sp+51,0);memory.writeByte(sp+52,1);memory.writeByte(sp+53,0x0f);
	}
	private boolean nmInstall(M68kCpu cpu) throws M68kException {
		int record=cpu.a[0];
		if(record!=0){
			// NMRec: nmMark +14, nmIcon +16, nmSound +20 (-1 = system alert sound), nmStr +24, nmResp +28 (-1 = remove when done), nmRefCon +32
			int sound=memory.readLong(record+20),text=memory.readLong(record+24),response=memory.readLong(record+28);
			if(Mac68kApi.TRACE)System.err.println("[68K] NMInstall record $"+Integer.toHexString(record)+" mark "+memory.readWord(record+14)+" icon $"+Integer.toHexString(memory.readLong(record+16))+" sound $"+Integer.toHexString(sound)+" str $"+Integer.toHexString(text)+(text!=0?" \""+readPascal(text)+"\"":"")+" resp $"+Integer.toHexString(response));
			if(sound!=0)Toolkit.getDefaultToolkit().beep();
			if(text!=0){final String message=readPascal(text);javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){javax.swing.JOptionPane.showMessageDialog(null,message,name,javax.swing.JOptionPane.INFORMATION_MESSAGE);}});}
			if(response!=0&&response!=-1&&!pendingNotifications.contains(record))pendingNotifications.add(record);
			notificationCount++;}
		cpu.d[0]=NO_ERR;return true;
	}

	/** Calls a 68K routine with no arguments, preserving all registers. */
	void callRoutine(int address) throws M68kException {
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		try{cpu.a[7]-=INTERRUPT_STACK_SLACK;cpu.call(address);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}
	/** Set while poll runs the interrupt-time work: the CPU stands between two instructions of the program, so cpu.pc is the
	 *  PC a real interrupt would have stacked (elsewhere the program waits in a trap - on a Mac, a PC in ROM). */
	private boolean pollingAtInstruction;
	/**
	 * Calls a VBL, Time Manager or cursor task the way the interrupt does: below the program's stack pointer lie a 68020
	 * exception frame (SR, interrupted PC, format 0 / level-1 autovector), the dispatcher's MOVEM.L D0-D3/A0-A3 and room
	 * for the managers' return addresses - INTERRUPT_STACK_SLACK bytes in all. A task may look for the frame: MPW Shell
	 * 3.x stops itself at start-up with MOVEQ #0,D0 / BRA.S * and installs a VBL task that scans up to 256 bytes above its
	 * A6 for that PC (with Z set in the stacked CCR), adds 2 to step it past the loop, and keeps the offset to look at the
	 * interrupted PC later (Command-period in tools). A PC or SR the task changed in the frame takes effect as at RTE.
	 */
	void callInterruptRoutine(int address) throws M68kException {
		boolean boundary=pollingAtInstruction;pollingAtInstruction=false;
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr,pc=boundary?cpu.pc:memory.romBase();
		int frame=a[7]-8,saved=frame-32;
		memory.writeWord(frame,sr);memory.writeLong(frame+2,pc);memory.writeWord(frame+6,0x0064);
		for(int i=0;i<4;i++){memory.writeLong(saved+4*i,d[i]);memory.writeLong(saved+16+4*i,a[i]);}
		for(int at=a[7]-INTERRUPT_STACK_SLACK;at<saved;at+=4)memory.writeLong(at,0);
		int newPc=pc,newSr=sr;
		try{cpu.a[7]-=INTERRUPT_STACK_SLACK;cpu.call(address);newPc=memory.readLong(frame+2);newSr=memory.readWord(frame);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;pollingAtInstruction=boundary;}
		if(boundary&&newPc!=pc)cpu.pc=newPc;
		if(boundary&&newSr!=sr)cpu.sr=newSr;
	}
	/**
	 * Bytes left free below the interrupted program's stack pointer when a routine of its is run at "interrupt time" (VBL and
	 * Time Manager tasks, Sound Manager callbacks and completion routines): on a real Mac an exception frame and the
	 * interrupting manager's own frames sit there, so a routine that writes past its arguments cannot touch the program's
	 * frame. Factory 1.1's sound callback is compiled as a Pascal FUNCTION and stores a word result where its arguments were
	 * - straight over the return address of the routine polling for it, which then returned into garbage.
	 */
	static final int INTERRUPT_STACK_SLACK=64;

	// ------------------------------------------------------------------ utilities

	private boolean systemTask(M68kCpu cpu) throws M68kException {runEventWork();updateTicks();runTimers();events.idle(1);threads.yieldAtEventLoop(cpu);return true;}

	private boolean secondsToDate(M68kCpu cpu) throws M68kCpuException {
		long seconds=cpu.d[0]&0xffffffffL;int record=cpu.a[0];
		Calendar c=macCalendar();c.setTimeInMillis((seconds-MAC_EPOCH_DELTA)*1000L);
		memory.writeWord(record,c.get(Calendar.YEAR));memory.writeWord(record+2,c.get(Calendar.MONTH)+1);memory.writeWord(record+4,c.get(Calendar.DAY_OF_MONTH));
		memory.writeWord(record+6,c.get(Calendar.HOUR_OF_DAY));memory.writeWord(record+8,c.get(Calendar.MINUTE));memory.writeWord(record+10,c.get(Calendar.SECOND));
		memory.writeWord(record+12,c.get(Calendar.DAY_OF_WEEK));return true;
	}
	private boolean dateToSeconds(M68kCpu cpu) throws M68kCpuException {
		int record=cpu.a[0];
		Calendar c=macCalendar();c.clear();
		c.set((short)memory.readWord(record),(short)memory.readWord(record+2)-1,(short)memory.readWord(record+4),(short)memory.readWord(record+6),(short)memory.readWord(record+8),(short)memory.readWord(record+10));
		cpu.d[0]=(int)(c.getTimeInMillis()/1000L+MAC_EPOCH_DELTA);return true;
	}
	private boolean enqueue(M68kCpu cpu) throws M68kCpuException {
		int element=cpu.a[0],header=cpu.a[1];
		if(element==0||header==0)return true;
		memory.writeLong(element,0);
		int tail=memory.readLong(header+6);
		if(tail==0)memory.writeLong(header+2,element);else memory.writeLong(tail,element);
		memory.writeLong(header+6,element);return true;
	}
	private boolean dequeue(M68kCpu cpu) throws M68kCpuException {
		int element=cpu.a[0],header=cpu.a[1];
		int current=memory.readLong(header+2),previous=0;
		while(current!=0&&current!=element){previous=current;current=memory.readLong(current);}
		if(current==0){cpu.d[0]=-1;return true;}
		int next=memory.readLong(current);
		if(previous==0)memory.writeLong(header+2,next);else memory.writeLong(previous,next);
		if(memory.readLong(header+6)==current)memory.writeLong(header+6,previous);
		cpu.d[0]=NO_ERR;return true;
	}
	private boolean newString(M68kCpu cpu) throws M68kCpuException {
		int s=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int length=s==0?0:memory.readByte(s)+1;
		memory.writeLong(cpu.a[7],api.createHandle(s==0?new byte[]{0}:memory.readBytes(s,length)));return true;
	}
	private boolean setString(M68kCpu cpu) throws M68kCpuException {
		int s=memory.readLong(cpu.a[7]),handle=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(handle!=0&&s!=0)api.loadHandle(handle,memory.readBytes(s,memory.readByte(s)+1));return true;
	}
	private boolean ptrToHand(M68kCpu cpu) throws M68kCpuException {
		int source=cpu.a[0],size=cpu.d[0];
		try{cpu.a[0]=api.createHandle(memory.readBytes(source,Math.max(0,size)));cpu.d[0]=NO_ERR;}catch(M68kCpuException e){cpu.a[0]=0;cpu.d[0]=MEM_FULL_ERR;}
		return true;
	}
	private boolean ptrToXHand(M68kCpu cpu) throws M68kCpuException {
		int source=cpu.a[0],handle=cpu.a[1],size=cpu.d[0];
		if(handle==0){cpu.d[0]=NIL_HANDLE_ERR;return true;}
		try{api.loadHandle(handle,memory.readBytes(source,Math.max(0,size)));cpu.a[0]=handle;cpu.d[0]=NO_ERR;}catch(M68kCpuException e){cpu.d[0]=MEM_FULL_ERR;}
		return true;
	}
	private boolean handAndHand(M68kCpu cpu) throws M68kCpuException {
		int source=cpu.a[0],destination=cpu.a[1];
		byte[] a=api.handleContents(source),b=api.handleContents(destination);
		if(a==null)a=new byte[0];if(b==null)b=new byte[0];
		byte[] joined=new byte[a.length+b.length];System.arraycopy(b,0,joined,0,b.length);System.arraycopy(a,0,joined,b.length,a.length);
		cpu.a[0]=destination; // on exit A0 = the destination handle (MacinTalk Stack's Speak returns it as its result)
		try{api.loadHandle(destination,joined);cpu.d[0]=NO_ERR;}catch(M68kCpuException e){cpu.d[0]=MEM_FULL_ERR;}
		return true;
	}
	// ------------------------------------------------------------------ Printing Manager (PrGlue $A8FD)

	private boolean ptrAndHand(M68kCpu cpu) throws M68kCpuException {
		int source=cpu.a[0],destination=cpu.a[1],size=cpu.d[0];
		byte[] a=memory.readBytes(source,Math.max(0,size)),b=api.handleContents(destination);if(b==null)b=new byte[0];
		byte[] joined=new byte[a.length+b.length];System.arraycopy(b,0,joined,0,b.length);System.arraycopy(a,0,joined,b.length,a.length);
		cpu.a[0]=destination;
		try{api.loadHandle(destination,joined);cpu.d[0]=NO_ERR;}catch(M68kCpuException e){cpu.d[0]=MEM_FULL_ERR;}
		return true;
	}
	static byte[] packBitsEncode(byte[] in){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int i=0;
		while(i<in.length){
			int run=1;while(i+run<in.length&&in[i+run]==in[i]&&run<128)run++;
			if(run>=3){out.write(1-run);out.write(in[i]);i+=run;continue;}
			int start=i;int lit=0;while(i<in.length&&lit<128){int r=1;while(i+r<in.length&&in[i+r]==in[i]&&r<128)r++;if(r>=3)break;i++;lit++;}
			if(lit==0){i++;lit=1;}
			out.write(lit-1);out.write(in,start,lit);
		}
		return out.toByteArray();
	}
	private boolean packBits(M68kCpu cpu) throws M68kCpuException {
		int srcPtrPtr=memory.readLong(cpu.a[7]+6),dstPtrPtr=memory.readLong(cpu.a[7]+2),count=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=10;
		int src=memory.readLong(srcPtrPtr),dst=memory.readLong(dstPtrPtr);
		byte[] in=memory.readBytes(src,Math.max(0,count));byte[] out=packBitsEncode(in);
		memory.load(dst,out);memory.writeLong(srcPtrPtr,src+in.length);memory.writeLong(dstPtrPtr,dst+out.length);return true;
	}
	private boolean unpackBits(M68kCpu cpu) throws M68kCpuException {
		int srcPtrPtr=memory.readLong(cpu.a[7]+6),dstPtrPtr=memory.readLong(cpu.a[7]+2),count=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=10;
		int src=memory.readLong(srcPtrPtr),dst=memory.readLong(dstPtrPtr);
		int produced=0;
		while(produced<count){
			int n=(byte)memory.readByte(src++);
			if(n>=0){for(int i=0;i<=n&&produced<count;i++){memory.writeByte(dst++,memory.readByte(src++));produced++;}}
			else if(n!=-128){int value=memory.readByte(src++);for(int i=0;i<1-n&&produced<count;i++){memory.writeByte(dst++,value);produced++;}}
		}
		memory.writeLong(srcPtrPtr,src);memory.writeLong(dstPtrPtr,dst);return true;
	}

	// Fixed-point math (Fixed = 16.16, Fract = 2.30)
	/** FixMul rounds the 64-bit product to the nearest Fixed (the ROM adds $8000 before taking the middle long): PlayMOD's
	 *  ISTk stub checksums its ©STk resource with FixMul and refuses to play ("Error Manager") when a truncated product breaks the sum. */
	private boolean fixMul(M68kCpu cpu) throws M68kCpuException {long a=memory.readLong(cpu.a[7]),b=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],(int)((a*b+0x8000)>>16));return true;}
	private boolean fixRatio(M68kCpu cpu) throws M68kCpuException {int denom=(short)memory.readWord(cpu.a[7]),numer=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;memory.writeLong(cpu.a[7],denom==0?(numer<0?Integer.MIN_VALUE:Integer.MAX_VALUE):(int)(((long)numer<<16)/denom));return true;}
	private boolean fixRound(M68kCpu cpu) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeWord(cpu.a[7],(x+0x8000)>>16);return true;}
	private boolean longMul(M68kCpu cpu) throws M68kCpuException {int result=memory.readLong(cpu.a[7]);long a=memory.readLong(cpu.a[7]+4),b=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;long p=a*b;memory.writeLong(result,(int)(p>>32));memory.writeLong(result+4,(int)p);return true;}
	private boolean long2Fix(M68kCpu cpu) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeLong(cpu.a[7],x<<16);return true;}
	/**
	 * Display Manager ($ABEB, selector in D0: low byte, high byte = parameter words; 0-3 are moveq forms). One display, the
	 * main GDevice, which cannot be reconfigured: the queries answer for it, notification procs are accepted (no display
	 * ever changes), configuration and the AV-component lists answer an error. Acrobat Reader 5.0 registers an extended
	 * notification proc at start-up and stopped on the unimplemented trap.
	 */
	private boolean displayDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff,sp=cpu.a[7];int mainDevice=memory.readLong(0x8a4);
		if(Mac68kApi.TRACE)System.err.println("[68K] DisplayDispatch $"+Integer.toHexString(selector));
		// negative MOVEQ selectors are private and their high byte $FF is no parameter count (it popped 510 bytes): the
		// System 7.5 Finder calls -10 (event):Boolean before it handles a desktop click - true = the Display Manager took
		// it, so icons never got clicks - and -9 (no arguments) after it changes the desktop region
		if((selector&0xff00)==0xff00){
			switch(selector){
				case 0xfff6:cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true;
				case 0xfff7:return true;
				default:throw new M68kMacApiException(cpu.pc,0xabeb,"DisplayDispatch private selector "+(short)selector);
			}
		}
		switch(selector&0xff){
			case 0x00:{cpu.a[7]+=2;memory.writeLong(cpu.a[7],memory.readLong(0x8a8));return true;}             // DMGetFirstScreenDevice(activeOnly)
			case 0x01:{int gd=memory.readLong(sp+2);cpu.a[7]+=6;int p=gd==0?0:memory.readLong(gd);memory.writeLong(cpu.a[7],p==0?0:memory.readLong(p+30));return true;} // DMGetNextScreenDevice(gd, activeOnly): gdNextGD
			case 0x02:case 0x03:cpu.a[7]+=4;return true;                                                        // DMDrawDesktopRect/Region
			case 0x1c:case 0x1d:case 0x25:memory.writeWord(cpu.a[7],0);return true;                             // (Un)BlockMirroring, ResolveDisplayComponents
		}
		int argBytes=(selector>>>8)*2,result=0;
		switch(selector&0xff){
			case 0x1f:{int idPtr=memory.readLong(sp+2),gd=memory.readLong(sp+6);int id=displayIdOf(gd);if(id==0&&memory.readByte(sp)!=0)id=1;if(id==0)result=-6222;if(idPtr!=0)memory.writeLong(idPtr,id);break;} // GetDisplayIDByGDevice(gd, VAR id, failToMain)
			case 0x20:{int gdPtr=memory.readLong(sp+2),id=memory.readLong(sp+6);int gd=0,n=1;for(int d=memory.readLong(0x8a8);d!=0&&n<64;n++){if(n==id){gd=d;break;}int p=memory.readLong(d);d=p==0?0:memory.readLong(p+30);}
				if(gd==0&&memory.readByte(sp)!=0)gd=mainDevice;if(gd==0)result=-6222;if(gdPtr!=0)memory.writeLong(gdPtr,gd);break;} // GetGDeviceByDisplayID(id, VAR gd, failToMain)
			case 0x13:{int rgnPtr=memory.readLong(sp);if(rgnPtr!=0)memory.writeLong(rgnPtr,memory.readLong(0x9ee));break;}  // GetDeskRegion
			case 0x16:case 0x17:case 0x18:{int p=memory.readLong(sp);if(p!=0)memory.writeByte(p,0);break;}             // QDIsMirroringCapable, CanMirrorNow, IsMirroringOn
			case 0x1b:{int p=memory.readLong(sp);if(p!=0)memory.writeLong(p,0);break;}                               // GetNextMirroredDevice
			case 0x1e:{int p=memory.readLong(sp);if(p!=0)memory.writeLong(p,cpu.a[5]);break;}                        // GetDisplayMgrA5World
			case 0x14:case 0x15:case 0xef:case 0x26:break;                                                          // (Remove)NotifyProc, (Remove)ExtendedNotifyProc
			case 0x06:{int p=memory.readLong(sp);if(p!=0)memory.writeLong(p,0);break;}                               // BeginConfigureDisplays(VAR displayState)
			case 0x07:break;                                                                                        // EndConfigureDisplays
			case 0x22:{int p=memory.readLong(sp);if(p!=0)memory.writeLong(p,0);break;}                               // GetDisplayComponent(id, VAR component)
			case 0x3e:result=displays().displayMode(sp);break;                                                      // GetDisplayMode(gd, VAR VDSwitchInfoRec)
			case 0x11:result=displays().setMode(sp);break;                                                          // SetDisplayMode(gd, mode, VAR depthMode, reserved, displayState)
			case 0x12:result=displays().checkMode(sp);break;                                                        // CheckDisplayMode(gd, mode, depthMode, VAR flags, reserved, VAR valid)
			case 0x36:result=displays().newModeList(sp);break;                                                      // NewDisplayModeList(id, flags, reserved, VAR count, VAR list)
			case 0x37:result=displays().indexedMode(sp);break;                                                      // GetIndexedDisplayModeFromList(list, index, flags, callback, userData)
			case 0x2c:result=displays().disposeList(sp);break;                                                      // DisposeList
			default:result=-50;break;                                                                              // configuration, AV components
		}
		cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],result);return true;
	}
	private DisplayManager68k displayManager;
	private DisplayManager68k displays(){if(displayManager==null)displayManager=new DisplayManager68k(this);return displayManager;}
	private int displayIdOf(int gd) throws M68kCpuException {int n=1;for(int d=memory.readLong(0x8a8);d!=0&&n<64;n++){if(d==gd)return n;int p=memory.readLong(d);d=p==0?0:memory.readLong(p+30);}return 0;}
	private boolean fix2Long(M68kCpu cpu) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeLong(cpu.a[7],(x+0x8000)>>16);return true;}
	private boolean fix2Frac(M68kCpu cpu) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeLong(cpu.a[7],x<<14);return true;}
	private boolean frac2Fix(M68kCpu cpu) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeLong(cpu.a[7],x>>14);return true;}
	private boolean x2Fix(M68kCpu cpu,boolean fixed) throws M68kCpuException {int ext=memory.readLong(cpu.a[7]);cpu.a[7]+=4;double v=Sane68k.readExtended(memory,ext);memory.writeLong(cpu.a[7],(int)Math.round(v*(fixed?65536.0:1073741824.0)));return true;}
	/**
	 * Fix2X/Frac2X return an Extended: the caller pushes the result address, then x; the trap pops only x and leaves
	 * the result address for the caller to discard (some applications call it repeatedly and then adjust SP
	 * after reusing one slot; popping both smashed its saved registers).
	 */
	private boolean fix2X(M68kCpu cpu,boolean fixed) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int ext=memory.readLong(cpu.a[7]);Sane68k.writeExtended(memory,ext,x/(fixed?65536.0:1073741824.0));return true;}
	private boolean fracTrig(M68kCpu cpu,boolean sin) throws M68kCpuException {int x=memory.readLong(cpu.a[7]);cpu.a[7]+=4;double angle=x/65536.0;memory.writeLong(cpu.a[7],(int)Math.round((sin?Math.sin(angle):Math.cos(angle))*1073741824.0));return true;}
	private boolean fracSqrt(M68kCpu cpu) throws M68kCpuException {long x=memory.readLong(cpu.a[7])&0xffffffffL;cpu.a[7]+=4;memory.writeLong(cpu.a[7],(int)Math.round(Math.sqrt(x/1073741824.0)*1073741824.0));return true;}
	private boolean fracMul(M68kCpu cpu) throws M68kCpuException {long a=memory.readLong(cpu.a[7]),b=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],(int)((a*b)>>30));return true;}
	// FixDiv(x, y) / FracDiv(x, y): x is pushed first, so the divisor y is on top of the stack (some applications size
	// paint canvas with FixDiv(width, zoom); the reversed operands gave 0 and an empty canvas)
	private boolean fracDiv(M68kCpu cpu) throws M68kCpuException {long b=memory.readLong(cpu.a[7]),a=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],b==0?(a<0?Integer.MIN_VALUE:Integer.MAX_VALUE):saturate((a<<30)/b));return true;}
	private boolean fixDiv(M68kCpu cpu) throws M68kCpuException {long b=memory.readLong(cpu.a[7]),a=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],b==0?(a<0?Integer.MIN_VALUE:Integer.MAX_VALUE):saturate((a<<16)/b));return true;}
	/** Fixed/Fract results pin at the largest magnitude instead of wrapping (Toolbox Utilities semantics). */
	private static int saturate(long value){return value>Integer.MAX_VALUE?Integer.MAX_VALUE:value<Integer.MIN_VALUE?Integer.MIN_VALUE:(int)value;}
	private boolean fixATan2(M68kCpu cpu) throws M68kCpuException {int y=memory.readLong(cpu.a[7]),x=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],(int)Math.round(Math.atan2(y,x)*65536.0));return true;}
	private boolean slopeFromAngle(M68kCpu cpu) throws M68kCpuException {int angle=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;double r=Math.toRadians(angle);double v=Math.abs(Math.cos(r))<1e-9?0:-Math.sin(r)/Math.cos(r);memory.writeLong(cpu.a[7],(int)Math.round(v*65536.0));return true;}
	private boolean angleFromSlope(M68kCpu cpu) throws M68kCpuException {int slope=memory.readLong(cpu.a[7]);cpu.a[7]+=4;double a=Math.toDegrees(Math.atan(-slope/65536.0));int angle=(int)Math.round(a);if(angle<=0)angle+=180;memory.writeWord(cpu.a[7],angle);return true;} // 1..180 (IM I-476): a slope of 0 is 180
	/** DeltaPoint(ptA, ptB) = ptA - ptB (IM I-475: ptB is subtracted from ptA); ptB is the last pushed, at the top of the stack. */
	private boolean deltaPoint(M68kCpu cpu) throws M68kCpuException {int b=memory.readLong(cpu.a[7]),a=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int v=(short)(a>>16)-(short)(b>>16),h=(short)a-(short)b;memory.writeLong(cpu.a[7],(v&0xffff)<<16|(h&0xffff));return true;}

	/** Case conversion of Mac Roman text in place; double-byte characters are left alone when the stack is Japanese. */
	private void caseText(int pointer,int length,boolean upper,boolean lower) throws M68kCpuException {caseText(pointer,length,upper,lower,false);}
	/** Upper- or lower-cases text in place (Mac OS Roman letters with their accents too), optionally stripping the accents;
	 *  a Japanese script's double-byte characters are left alone. */
	private void caseText(int pointer,int length,boolean upper,boolean lower,boolean strip) throws M68kCpuException {
		boolean japanese=encoding==StackTextEncoding.JAPANESE;
		for(int i=0;i<length;i++){int c=memory.readByte(pointer+i),was=c;
			if(japanese&&isJapaneseLead(c)){i++;continue;}
			if(japanese&&c>=0x80)continue; // half-width kana and the like
			if(strip)c=ROMAN_STRIP[c];
			if(upper)c=ROMAN_UPPER[c];else if(lower)c=ROMAN_LOWER[c];
			if(c!=was)memory.writeByte(pointer+i,c);}
	}
	/** Mac OS Roman byte tables: upper case, lower case, and the letter without its accent (other bytes map to themselves). */
	private static final int[] ROMAN_UPPER=new int[256],ROMAN_LOWER=new int[256],ROMAN_STRIP=new int[256];
	static{
		java.nio.charset.Charset roman=java.nio.charset.Charset.forName("x-MacRoman");
		byte[] bytes=new byte[256];for(int i=0;i<256;i++)bytes[i]=(byte)i;String chars=new String(bytes,roman);
		java.util.Map<Character,Integer> byChar=new java.util.HashMap<Character,Integer>();for(int i=0;i<256;i++)byChar.put(chars.charAt(i),i);
		for(int i=0;i<256;i++){char c=chars.charAt(i);ROMAN_UPPER[i]=ROMAN_LOWER[i]=ROMAN_STRIP[i]=i;
			if(i<0x20)continue;
			Integer u=byChar.get(Character.toUpperCase(c)),l=byChar.get(Character.toLowerCase(c));
			if(u!=null&&Character.toUpperCase(c)!=c&&Character.toString(c).toUpperCase(java.util.Locale.ROOT).length()==1)ROMAN_UPPER[i]=u;
			if(l!=null&&Character.toLowerCase(c)!=c)ROMAN_LOWER[i]=l;
			String base=java.text.Normalizer.normalize(Character.toString(c),java.text.Normalizer.Form.NFD);
			if(base.length()>1&&base.charAt(0)<0x80&&Character.isLetter(base.charAt(0)))ROMAN_STRIP[i]=base.charAt(0);}
	}
	/** _UprString; with the MARKS bit ($0200, diacSensitive FALSE) the accents go too. */
	private boolean uprString(M68kCpu cpu,int opcode) throws M68kCpuException {
		caseText(cpu.a[0],cpu.d[0]&0xffff,true,false,(opcode&0x0200)!=0);return true;
	}
	private boolean lwrString(M68kCpu cpu,int opcode) throws M68kCpuException {
		// _LwrString and its variants: LowerText ($A056), StripText ($A256), UpperText ($A456), StripUpperText ($A656) - the
		// variant bits used to be ignored and every one lower-cased
		boolean strip=(opcode&0x0200)!=0,upper=(opcode&0x0400)!=0;
		caseText(cpu.a[0],cpu.d[0]&0xffff,upper,!upper&&!strip,strip);return true;
	}
	private boolean cmpString(M68kCpu cpu,int opcode) throws M68kCpuException {
		int la=cpu.d[0]>>>16,lb=cpu.d[0]&0xffff;
		String a=encoding.decode(memory.readBytes(cpu.a[0],la)),b=encoding.decode(memory.readBytes(cpu.a[1],lb));
		if((opcode&0x0400)==0){a=a.toUpperCase(Locale.ROOT);b=b.toUpperCase(Locale.ROOT);}
		cpu.d[0]=a.equals(b)?0:1;return true;
	}

	// ------------------------------------------------------------------ Script Manager

	/** Whether the Shift-JIS byte at {@code index} starts a double-byte character (counted from the start of the text). */
	private static boolean leadsDoubleByte(byte[] text,int index){
		int i=0;while(i<index){int b=text[i]&255;i+=(b>=0x81&&b<=0x9f)||(b>=0xe0&&b<=0xfc)?2:1;}
		return i==index;
	}

	/**
	 * CharacterType of the character holding byte {@code offset} (any byte of it) - Script.h's classes: type in bits 0-3
	 * (smCharPunct 0, smCharAscii 1, Katakana 2, Hiragana 3, Ideographic 4, two-byte Greek 5 / Russian 6, smCharExtAscii 7),
	 * class in bits 8-11 (smPunctNumber $100, smPunctSymbol $200, smPunctBlank $300, smPunctGraphic $500; smKanaSmall $100;
	 * smIdeographicLevel2 $100), smCharUpper $4000, smChar2byte $8000. REALbasic's Trim keeps only what is not
	 * smCharPunct+smPunctBlank: the old answer (2 for a space, a Katakana class) left 何かon林檎's dictionary entry names with a
	 * trailing blank, so no event entry was ever found and the ghost never spoke.
	 */
	private int characterType(int text,int offset,boolean japanese) throws M68kCpuException {
		int start=0;
		if(japanese){int i=0;while(i<=offset){int c=memory.readByte(text+i);int n=isJapaneseLead(c)?2:1;if(i+n>offset){start=i;break;}i+=n;}}
		else start=offset;
		int c=memory.readByte(text+start);
		if(japanese&&isJapaneseLead(c))return 0x8000|twoByteType((c<<8)|memory.readByte(text+start+1));
		if(c==' '||c=='\t'||c==0xca&&!japanese)return 0x0300;                          // smPunctBlank (Mac OS Roman $CA: non-breaking space)
		if(c>='A'&&c<='Z')return 0x4001;                                                  // smCharAscii + smCharUpper
		if(c>='a'&&c<='z')return 0x0001;
		if(c>='0'&&c<='9')return 0x0100;                                                  // smPunctNumber
		if(c<0x80)return "$+<=>^`|~".indexOf((char)c)>=0?0x0200:0x0000;                  // smPunctSymbol / smPunctNormal
		if(japanese)return c>=0xa1&&c<=0xdf?0x0002:0x0000;                               // one-byte (half-width) Katakana
		char ch=new String(new byte[]{(byte)c},java.nio.charset.Charset.forName("x-MacRoman")).charAt(0);
		return Character.isLetter(ch)?0x0007|(Character.isUpperCase(ch)?0x4000:0):0x0000; // smCharExtAscii
	}
	/** CharacterType of a Shift-JIS two-byte character, without smChar2byte. */
	private static int twoByteType(int code){
		if(code==0x8140)return 0x0300;                                                    // ideographic space: smPunctBlank
		if(code==0x8152||code==0x8153||code==0x8154||code==0x8155||code==0x8156||code==0x8157||code==0x8158)return 0x0400; // repeat marks
		if(code>=0x824f&&code<=0x8258)return 0x0100;                                      // full-width digits
		if(code>=0x8260&&code<=0x8279)return 0x4001;                                      // full-width A-Z
		if(code>=0x8281&&code<=0x829a)return 0x0001;                                      // full-width a-z
		if(code>=0x829f&&code<=0x82f1)return 0x0003|("ぁぃぅぇぉっゃゅょゎ".indexOf(new String(new byte[]{(byte)(code>>8),(byte)code},java.nio.charset.Charset.forName("MS932")).charAt(0))>=0?0x0100:0); // Hiragana
		if(code>=0x8340&&code<=0x8396)return 0x0002|("ァィゥェォッャュョヮヵヶ".indexOf(new String(new byte[]{(byte)(code>>8),(byte)code},java.nio.charset.Charset.forName("MS932")).charAt(0))>=0?0x0100:0); // Katakana
		if(code>=0x839f&&code<=0x83d6)return 0x0005|(code<=0x83b6?0x4000:0);              // Greek
		if(code>=0x8440&&code<=0x8491)return 0x0006|(code<=0x8460?0x4000:0);              // Cyrillic
		if(code>=0x849f&&code<=0x84be)return 0x0500;                                      // line graphics
		if(code>=0x889f&&code<=0x9872)return 0x0004;                                      // JIS level 1 kanji
		if(code>=0x989f&&code<=0xeaa4)return 0x0104;                                      // JIS level 2 kanji
		if(code>=0xf040&&code<=0xf9fc)return 0x0204;                                      // user-defined
		return 0x0000;                                                                    // punctuation and symbols
	}
	private boolean scriptUtil(M68kCpu cpu) throws M68kException {
		int selector=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		int argBytes=(selector>>>16)&0xff;int routine=(short)(selector&0xffff); // high byte of the selector carries flags ($82.. for TruncString)
		boolean japanese=encoding==StackTextEncoding.JAPANESE;
		// Bit 30 of the selector: the script-specific form - a ScriptCode word sits between the selector and the routine's own
		// parameters, which the selector's argument count does not include (some runtimes call ParseTable as $C2040022 with
		// FontScript() first; taking the script word for the table pointer filled 256 bytes over low memory and the trap tables).
		if((selector&0x40000000)!=0){int script=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;japanese=script==1;}
		int args=cpu.a[7];
		switch(routine){
			case 0x0000:{int font=Fonts68k.substitute(api.quickDraw().textState()[0]);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],japanese&&(font==0||font==1||font>=0x4000&&font<0x4400)?1:0);return true;} // FontScript: the script of the current port's font (0/1 = the System's own fonts), or of the family the program's font substitution draws instead
			case 0x0002:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],japanese?1:0);return true; // IntlScript
			case 0x0004:{int code=(short)memory.readWord(args);cpu.a[7]+=argBytes;keyScript(code,japanese);return true;} // KeyScript
			case 0x0006:{int font=Fonts68k.substitute((short)memory.readWord(args));cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],(font==0||font==1||font>=0x4000&&font<0x4400)&&japanese?1:0);return true;} // FontToScript (0/1: the System's own fonts, Osaka on a KanjiTalk System)
			case 0x0008:{int sel=(short)memory.readWord(args);cpu.a[7]+=argBytes;memory.writeLong(cpu.a[7],scriptManagerVariable(sel));return true;} // GetScriptManagerVariable
			case 0x000a:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],0);return true; // SetScriptManagerVariable
			case 0x000c:{int sel=(short)memory.readWord(args),script=(short)memory.readWord(args+2);cpu.a[7]+=argBytes;memory.writeLong(cpu.a[7],scriptVariable(script,sel));return true;} // GetScriptVariable
			case 0x000e:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],0);return true; // SetScriptVariable
			case 0x0010:{int offset=(short)memory.readWord(args),text=memory.readLong(args+2);cpu.a[7]+=argBytes; // CharByte
				int result=0;if(japanese){int i=0;while(i<offset){int c=memory.readByte(text+i);if(isJapaneseLead(c))i+=2;else i++;}
					if(i>offset)result=1;else if(isJapaneseLead(memory.readByte(text+offset)))result=-1;}
				memory.writeWord(cpu.a[7],result);return true;}
			case 0x0012:{int offset=(short)memory.readWord(args),text=memory.readLong(args+2);cpu.a[7]+=argBytes; // CharType / CharacterType
				memory.writeWord(cpu.a[7],characterType(text,offset,japanese));return true;}
			// The routines below take Pascal arguments: the last one lies at (SP), the first (the text pointer) highest up.
			case 0x0014:{ // Pixel2Char(textBuf, textLen, slop, pixelWidth, VAR leadingEdge) : INTEGER
				int leading=memory.readLong(args),pixel=(short)memory.readWord(args+4),slop=(short)memory.readWord(args+6),length=(short)memory.readWord(args+8),text=memory.readLong(args+10);cpu.a[7]+=argBytes;
				int[] result=justifiedPixelToChar(text,length,pixel,slop,japanese);writeVarBoolean(leading,result[1]!=0);memory.writeWord(cpu.a[7],result[0]);return true;}
			case 0x0016:{int offset=(short)memory.readWord(args+2),slop=(short)memory.readWord(args+4),length=(short)memory.readWord(args+6),text=memory.readLong(args+8);cpu.a[7]+=argBytes; // Char2Pixel(textBuf, textLen, slop, offset, direction)
				memory.writeWord(cpu.a[7],quickDraw.charToPixel(text,length,offset)+slopBefore(text,length,offset,slop,japanese));return true;}
			case 0x0018:{int srcMask=memory.readLong(args),target=(short)memory.readWord(args+4),dst=memory.readLong(args+6),src=memory.readLong(args+10);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],transliterate(src,dst,target,srcMask));return true;} // Transliterate
			case 0x001a:{int offsets=memory.readLong(args);boolean leadingEdge=memory.readByte(args+8)!=0;int offset=(short)memory.readWord(args+10),length=(short)memory.readWord(args+12),text=memory.readLong(args+14);cpu.a[7]+=argBytes; // FindWord(textPtr, textLength, offset, leadingEdge, breaks, VAR offsets)
				int[] word=findWord(text,length,offset,leadingEdge);if(offsets!=0){memory.writeWord(offsets,word[0]);memory.writeWord(offsets+2,word[1]);memory.writeWord(offsets+4,0);}return true;}
			case 0x001c:{ // HiliteText(textPtr, textLength, firstOffset, secondOffset, VAR offsets): one run for unidirectional text
				int offsets=memory.readLong(args),second=(short)memory.readWord(args+4),first=(short)memory.readWord(args+6);cpu.a[7]+=argBytes;
				if(offsets!=0){memory.fill(offsets,12,0);memory.writeWord(offsets,Math.min(first,second));memory.writeWord(offsets+2,Math.max(first,second));}return true;}
			case 0x001e:{int slop=(short)memory.readWord(args),length=(short)memory.readWord(args+2),text=memory.readLong(args+4);cpu.a[7]+=argBytes;drawJustified(text,length,slop,japanese);return true;} // DrawJust(textPtr, textLength, slop)
			case 0x0020:{int widths=memory.readLong(args),slop=(short)memory.readWord(args+4),length=(short)memory.readWord(args+6),text=memory.readLong(args+8);cpu.a[7]+=argBytes;measureJustified(text,length,slop,widths,japanese);return true;} // MeasureJust(textPtr, textLength, slop, charLocs)
			case 0x0022:{int table=memory.readLong(args);cpu.a[7]+=argBytes;for(int i=0;i<256;i++)memory.writeByte(table+i,japanese&&isJapaneseLead(i)?1:0);writeBoolean(cpu.a[7],true);return true;} // ParseTable
			case 0x0026:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],0);return true; // FindScriptRun
			case 0x0028:{ // VisibleLength(textPtr, textLen: LONGINT): LONGINT - the length without the trailing white space (spaces, tabs,
				// returns; a Japanese script's double-byte space too). It used to read the arguments as PixelToChar's.
				int length=memory.readLong(args),text=memory.readLong(args+4);cpu.a[7]+=argBytes;
				int end=Math.max(0,length);byte[] bytes=text==0||end==0?new byte[0]:memory.readBytes(text,end);end=bytes.length;
				while(end>0){int b=bytes[end-1]&255;
					if(b==0x20||b==0x09||b==0x0d||b==0x0a){end--;continue;}
					if(japanese&&end>=2&&b==0x40&&(bytes[end-2]&255)==0x81&&leadsDoubleByte(bytes,end-2)){end-=2;continue;}
					break;}
				memory.writeLong(cpu.a[7],end);return true;}
			case 0x002e:{ // PixelToChar(textBuf, textLen, slop, pixelWidth, VAR leadingEdge, VAR widthRemaining, styleRunPosition, numer, denom)
				int remaining=memory.readLong(args+10),leading=memory.readLong(args+14),pixel=memory.readLong(args+18),length=memory.readLong(args+26),text=memory.readLong(args+30);cpu.a[7]+=argBytes;
				int[] r=justifiedPixelToChar(text,length,pixel>>16,memory.readLong(args+22)/65536.0,japanese);writeVarBoolean(leading,r[1]!=0);if(remaining!=0)memory.writeLong(remaining,r[2]<<16);memory.writeWord(cpu.a[7],r[0]);return true;}
			case 0x0030:{int offset=memory.readLong(args+12),slop=memory.readLong(args+16),length=memory.readLong(args+20),text=memory.readLong(args+24);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],quickDraw.charToPixel(text,length,offset)+slopBefore(text,length,offset,slop/65536.0,japanese));return true;} // CharToPixel(textBuf, textLength, slop, offset, direction, styleRunPosition, numer, denom)
			case 0x0032:{int slop=memory.readLong(args+10),length=memory.readLong(args+14),text=memory.readLong(args+18);cpu.a[7]+=argBytes;drawJustified(text,length,slop/65536.0,japanese);return true;} // DrawJustified(textPtr, textLength, slop, styleRunPosition, numer, denom)
			case 0x0034:{int widths=memory.readLong(args+10),slop=memory.readLong(args+14),length=memory.readLong(args+18),text=memory.readLong(args+22);cpu.a[7]+=argBytes;measureJustified(text,length,slop/65536.0,widths,japanese);return true;} // MeasureJustified(textPtr, textLength, slop, charLocs, styleRunPosition, numer, denom)
			case 0x0036:cpu.a[7]+=argBytes;memory.writeLong(cpu.a[7],0x00010000);return true; // PortionLine
			case (short)0xfffe:{ // StyledLineBreak(textPtr, textLen, textStart, textEnd, flags, VAR textWidth, VAR textOffset) : StyledLineBreakCode - the last argument lies at (SP)
				int offsetPtr=memory.readLong(args),widthPtr=memory.readLong(args+4),end=memory.readLong(args+12),start=memory.readLong(args+16),length=memory.readLong(args+20),text=memory.readLong(args+24);cpu.a[7]+=argBytes;
				int width=memory.readLong(widthPtr)>>16;int[] r=quickDraw.pixelToChar(text+start,end-start,width);int breakAt=r[0];
				int code;if(breakAt>=end-start){breakAt=end-start;code=2;}else{ // smBreakOverflow: the whole range fits; else back up to a word boundary (smBreakWord 0) or break the word (smBreakChar 1)
					int b=wordBreakBefore(text+start,breakAt,japanese);if(b>0){breakAt=b;code=0;}else{breakAt=Math.max(1,r[0]);code=1;}}
				// A carriage return ends the line right after it, whether or not the rest would fit (WASTE leaves hard line breaks to this;
				// FumiEdit showed "Hello World<CR>next" on one line). Shift-JIS trail bytes are $40 and up, so a $0D byte is always a CR.
				for(int i=0,scan=Math.min(end-start,r[0]+1);i<scan;i++)if(memory.readByte(text+start+i)==0x0d){breakAt=i+1;code=0;break;} // smBreakWord even at the range's end: the next run starts a new line
				// StyledLineBreakCode is a byte: a Pascal byte result sits in the high byte of its word slot; word-wrap code may read it with move.b (sp)+
				memory.writeLong(widthPtr,Math.max(0,width-quickDraw.charToPixel(text+start,end-start,breakAt))<<16);memory.writeLong(offsetPtr,start+breakAt);memory.writeWord(cpu.a[7],code<<8);return true;}
			case (short)0xffe0:{/* TruncString(width, VAR string, truncWhere): pushed width, string ptr, truncWhere */int width=(short)memory.readWord(args+6),s=memory.readLong(args+2),where=(short)memory.readWord(args);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],quickDraw.truncString(s,width,where));return true;}
			case (short)0xffde:{/* TruncText(width, textPtr, VAR length, truncWhere): pushed width, text ptr, length ptr, truncWhere (UxTruncText XFCN) */int width=(short)memory.readWord(args+10),text=memory.readLong(args+6),lengthPtr=memory.readLong(args+2),where=(short)memory.readWord(args);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],quickDraw.truncText(text,lengthPtr,width,where));return true;}
			// ReplaceText(baseText, substitutionText, key: Str15) : count - every occurrence of key in baseText becomes the substitution
			// text, character by character (a match never starts on a trail byte); MacBeat's folder dialog titles its button with
			// 'Select "^0"' and showed the ^0 itself
			case (short)0xffdc:{int key=memory.readLong(args),substitution=memory.readLong(args+4),base=memory.readLong(args+8);cpu.a[7]+=argBytes;
				byte[] k=key==0?new byte[0]:memory.readBytes(key+1,memory.readByte(key));byte[] text=base==0?null:api.handleContents(base),with=substitution==0?new byte[0]:api.handleContents(substitution);
				if(text==null||k.length==0){memory.writeWord(cpu.a[7],text==null?-109:0);return true;} // nilHandleErr
				if(with==null)with=new byte[0];
				java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int count=0;
				for(int i=0;i<text.length;){
					boolean match=i+k.length<=text.length;for(int j=0;match&&j<k.length;j++)if(text[i+j]!=k[j])match=false;
					if(match){out.write(with,0,with.length);i+=k.length;count++;continue;}
					int n=encoding==StackTextEncoding.JAPANESE&&isJapaneseLead(text[i]&0xff)&&i+1<text.length?2:1;out.write(text,i,n);i+=n;}
				if(count>0)api.loadHandle(base,out.toByteArray());memory.writeWord(cpu.a[7],count);return true;}
			case (short)0xfff2:{int result=memory.readLong(args),longDate=memory.readLong(args+4);cpu.a[7]+=argBytes; // LongDateToSeconds(lDate, VAR lSecs): lSecs is on top
				Calendar c=macCalendar();c.clear();c.set((short)memory.readWord(longDate+2),(short)memory.readWord(longDate+4)-1,(short)memory.readWord(longDate+6),(short)memory.readWord(longDate+8),(short)memory.readWord(longDate+10),(short)memory.readWord(longDate+12));
				long secs=c.getTimeInMillis()/1000L+MAC_EPOCH_DELTA;memory.writeLong(result,(int)(secs>>32));memory.writeLong(result+4,(int)secs);return true;}
			case (short)0xfff0:{int longDate=memory.readLong(args),secondsPtr=memory.readLong(args+4);cpu.a[7]+=argBytes; // LongSecondsToDate(lSecs, VAR lDate): lDate is on top (the old order wrote 28 bytes over the caller's 8-byte lSecs and smashed HyperCard's frame)
				long secs=((long)memory.readLong(secondsPtr)<<32)|(memory.readLong(secondsPtr+4)&0xffffffffL);Calendar c=macCalendar();c.setTimeInMillis((secs-MAC_EPOCH_DELTA)*1000L);
				memory.fill(longDate,28,0);memory.writeWord(longDate+2,c.get(Calendar.YEAR));memory.writeWord(longDate+4,c.get(Calendar.MONTH)+1);memory.writeWord(longDate+6,c.get(Calendar.DAY_OF_MONTH));memory.writeWord(longDate+8,c.get(Calendar.HOUR_OF_DAY));memory.writeWord(longDate+10,c.get(Calendar.MINUTE));memory.writeWord(longDate+12,c.get(Calendar.SECOND));memory.writeWord(longDate+14,c.get(Calendar.DAY_OF_WEEK));return true;}
			case (short)0xfff8:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],0);return true; // InitDateCache
			case (short)0xfff6:case (short)0xfff4:{ // StringToDate ($FFF6) / StringToTime ($FFF4)(textPtr, textLen, cache, VAR lengthUsed, VAR dateTime): StringToDateStatus
				// (MPW Script.h: String2Date = $8214FFF6, String2Time = $8214FFF4. They were swapped: HyperCard's convert got a time for
				// a date and failed for every date and time string - "convert the long time to dateItems" left it unchanged)
				int dateTime=memory.readLong(args),lengthUsed=memory.readLong(args+4),textLen=memory.readLong(args+12),text=memory.readLong(args+16);cpu.a[7]+=argBytes;
				String t=encoding.decode(memory.readBytes(text,Math.max(0,Math.min(255,textLen))));
				boolean date=routine==(short)0xfff6;
				int[] r=date?parseDate(t):parseTime(t);
				// lengthUsed counts bytes (a Japanese date "1995年 12月 23日" is 16 bytes, 12 characters: HyperCard took the 4 bytes
				// the old character count left over as junk and refused the conversion)
				int used=r==null?0:encoding.encodeReplacing(t.substring(0,Math.min(r[0],t.length()))).length;
				if(lengthUsed!=0)memory.writeLong(lengthUsed,used);
				// nothing found: dateTimeNotFound ($8400). HyperCard J-2.3's convert compares the two results' absolute values
				// (|time| >= |date| to take the date); fatalDateTime alone ($8000) stays negative under NEG.W and made every date
				// string fail ("12/23/95", "1995年 12月 23日" stayed unconverted) - CODE at $382766 in the 68K VM
				if(r==null){memory.writeWord(cpu.a[7],0x8400);return true;}
				if(date){memory.writeWord(dateTime,1);memory.writeWord(dateTime+2,r[1]);memory.writeWord(dateTime+4,r[2]);memory.writeWord(dateTime+6,r[3]);} // era, year, month, day
				else{memory.writeWord(dateTime+8,r[1]);memory.writeWord(dateTime+10,r[2]);memory.writeWord(dateTime+12,r[3]);memory.writeWord(dateTime+20,r[1]>=12?1:0);} // hour, minute, second, pm
				memory.writeWord(cpu.a[7],used<Math.max(0,Math.min(255,textLen))?2:0);return true;} // leftOverChars
			case (short)0xffe6:{ // StringToExtended(source, canonical, parts, VAR x): the number in the string (the format record is not
				// consulted: plain digits, sign, point and exponent), fFormatOK or fSpuriousChars; x used to be left unwritten
				int x=memory.readLong(args),source=memory.readLong(args+12);cpu.a[7]+=argBytes;
				String text=source==0?"":readPascal(source).trim().replace(",","");double value;int status=0;
				try{value=Double.parseDouble(text);}catch(NumberFormatException e){value=Double.NaN;status=3;}
				if(x!=0)Sane68k.writeExtended(memory,x,value);memory.writeWord(cpu.a[7],status);return true;}
			case (short)0xffe8:{ // ExtendedToString(x, canonical, parts, VAR outString): the number in the canonical format's pattern
				// (one StringToFormatRec made), else as plain decimal text
				int out=memory.readLong(args),canonical=memory.readLong(args+8),x=memory.readLong(args+12);cpu.a[7]+=argBytes;
				double value=x==0?0:Sane68k.readExtended(memory,x);String text;
				if(Double.isNaN(value))text="NAN";else if(Double.isInfinite(value))text=value>0?"INF":"-INF";
				else{String pattern=formatPattern(canonical);text=pattern==null?null:formatNumber(pattern,value);
					if(text==null){java.math.BigDecimal d=java.math.BigDecimal.valueOf(value).stripTrailingZeros();text=d.scale()<=0?d.toBigInteger().toString():d.toPlainString();}}
				if(out!=0)writePascal(out,text,255);memory.writeWord(cpu.a[7],0);return true;}
			case (short)0xffec:{ // StringToFormatRec(inString, partsTable, VAR outString: NumFormatString): FormatStatus. The record is private
				// to the Script Manager: here it keeps the format text itself (fLength, fVersion, 'HZ', the Pascal string)
				int outRec=memory.readLong(args),in=memory.readLong(args+8);cpu.a[7]+=argBytes;
				byte[] format=in==0?new byte[0]:memory.readBytes(in+1,memory.readByte(in));int n=Math.min(format.length,250);
				if(outRec!=0){memory.writeByte(outRec,n+3);memory.writeByte(outRec+1,0);memory.writeByte(outRec+2,'H');memory.writeByte(outRec+3,'Z');memory.writeByte(outRec+4,n);for(int i=0;i<n;i++)memory.writeByte(outRec+5+i,format[i]);}
				memory.writeWord(cpu.a[7],0);return true;} // fFormatOK
			case (short)0xffea:{ // FormatRecToString(myCanonical, partsTable, VAR outString, VAR positions: TripleInt): FormatStatus - the format text
				// back, with where its positive, negative and zero parts (separated by ';') start and how long they are
				int positions=memory.readLong(args),out=memory.readLong(args+4),canonical=memory.readLong(args+12);cpu.a[7]+=argBytes;
				byte[] format=formatBytes(canonical);if(format==null)format=new byte[0];
				if(out!=0){memory.writeByte(out,format.length);for(int i=0;i<format.length;i++)memory.writeByte(out+1+i,format[i]);}
				if(positions!=0){int start=0,part=0;
					for(int i=0;i<=format.length&&part<3;i++)if(i==format.length||format[i]==';'){memory.writeWord(positions+part*4,start+1);memory.writeWord(positions+part*4+2,i-start);part++;start=i+1;}
					for(;part<3;part++){memory.writeWord(positions+part*4,0);memory.writeWord(positions+part*4+2,0);}}
				memory.writeWord(cpu.a[7],0);return true;}
			case (short)0xffee:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],0);return true; // ToggleDate
			case (short)0xffb6:{ // LowercaseText/UppercaseText/StripDiacritics/UppercaseStripDiacritics(textPtr, len, script) + mode word ($0400 upper, $0200 strip): no result
				int mode=memory.readWord(args),length=(short)memory.readWord(args+4),text=memory.readLong(args+6);cpu.a[7]+=argBytes;
				caseText(text,length,(mode&0x0400)!=0,(mode&0x0600)==0,(mode&0x0200)!=0);return true;} // some projectors uppercase file names; the old stub wrote a word "result" over the caller's stack
			case (short)0xffd0:{ // IsCmdChar(event, test): the Command key down with a key that types test - its character with or
				// without Shift (Cmd-Shift-. and the like still count as Cmd-.); MacApp-style code checks Cmd-. with it
				int test=memory.readWord(args)&0xff,event=memory.readLong(args+2);cpu.a[7]+=argBytes;boolean hit=false;
				if(event!=0){int message=memory.readLong(event+2),modifiers=memory.readWord(event+14),key=(message>>>8)&0x7f;
					if((modifiers&0x100)!=0)hit=(message&0xff)==test||SystemResources68k.keyChar(key,(modifiers&0x200)!=0,(modifiers&0x400)!=0,(modifiers&0x1000)!=0)==test||SystemResources68k.keyChar(key,false,false,false)==test;}
				writeBoolean(cpu.a[7],hit);return true;}
			case (short)0xfffa:{int block=memory.readLong(args);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],intlTokenize(block,japanese)<<8);return true;} // IntlTokenize(tokenParam): TokenResults (a byte)
			default:cpu.a[7]+=argBytes;throw new M68kMacApiException(cpu.pc,0xa8b5,String.format("ScriptUtil selector $%08X",selector));
		}
	}

	/**
	 * IntlTokenize (IM Text, Script Manager): the source text of a TokenBlock cut into TokenRecs (theToken, position, length,
	 * stringPosition; 14 bytes each, tokenLength of them at most) with the token types of {@link SystemResources68k#tokenType}.
	 * Runs of white space, of letters (and digits after them with doAlphanumeric) and of digits are one token each; digits,
	 * the decimalCode character and digits make a tokenRealNum; "<=" ">=" "==" ":=" "<>" "!=" are the two-character tokens.
	 * A leftDelims token opens a literal: tokenLeftLit, the text up to the matching rightDelims token as tokenLiteral (an
	 * escapeCode character and the one after it are a tokenEscape inside it), tokenRightLit. A leftComment pair opens a comment
	 * (tokenLeftComment, its text skipped, nested with doNest, tokenRightComment). With doString every token gets a copy of its
	 * text in stringList (a Pascal string, NUL-terminated, even-aligned) at stringPosition. Results: tokenOK 0, tokenOverflow 1,
	 * stringOverflow 2, badEnding 4 (the text ends inside a literal or comment). Apple's Serial Tool parses its CTB
	 * configuration string ("Baud 2400 Port \"Modem Port\"") with it: CTBConfigure stopped at "Mac API is not supported".
	 * The token boundaries are written from IM Text and Script.h, not measured against the ROM's tokenizer.
	 */
	private int intlTokenize(int block,boolean japanese) throws M68kCpuException {
		if(block==0)return 5; // crash
		int source=memory.readLong(block),sourceLength=memory.readLong(block+4),tokenList=memory.readLong(block+8),tokenLength=memory.readLong(block+12);
		int stringList=memory.readLong(block+20),stringLength=memory.readLong(block+24);
		boolean doString=memory.readByte(block+32)!=0,doAppend=memory.readByte(block+33)!=0,doAlphanumeric=memory.readByte(block+34)!=0,doNest=memory.readByte(block+35)!=0;
		int[] leftDelims={(short)memory.readWord(block+36),(short)memory.readWord(block+38)},rightDelims={(short)memory.readWord(block+40),(short)memory.readWord(block+42)};
		int[] leftComment=new int[4],rightComment=new int[4];for(int i=0;i<4;i++){leftComment[i]=(short)memory.readWord(block+44+i*2);rightComment[i]=(short)memory.readWord(block+52+i*2);}
		int escapeCode=(short)memory.readWord(block+60),decimalCode=(short)memory.readWord(block+62);
		if(!doAppend){memory.writeLong(block+16,0);memory.writeLong(block+28,0);}
		byte[] text=source==0||sourceLength<=0?new byte[0]:memory.readBytes(source,sourceLength);int n=text.length;
		int[] types=new int[n];boolean[] pair=new boolean[n]; // a 2-byte character's type sits on its lead byte
		for(int i=0;i<n;i++){int c=text[i]&0xff;
			if(japanese&&i+1<n&&(c>=0x81&&c<=0x9f||c>=0xe0&&c<=0xfc)){types[i]=c==0x81&&(text[i+1]&0xff)==0x40?1:c==0x81?0:4;pair[i]=true;i++;types[i]=-1;}
			else types[i]=SystemResources68k.tokenType(c,japanese);}
		int[] state={memory.readLong(block+16),memory.readLong(block+28)}; // tokenCount, stringCount
		int status=0;
		for(int i=0;i<n&&status==0;){
			int t=types[i],end=i+(pair[i]?2:1);
			int literal=-1;for(int k=0;k<2;k++)if(leftDelims[k]>=0&&t==leftDelims[k])literal=k;
			int comment=-1;for(int k=0;k<2;k++)if(leftComment[2*k]>=0&&t==leftComment[2*k]&&(leftComment[2*k+1]<0||end<n&&types[end]==leftComment[2*k+1]))comment=k;
			if(literal>=0){
				status=addToken(block,state,2,source,text,i,end,tokenList,tokenLength,stringList,stringLength,doString);i=end;
				int run=i;boolean closed=false;
				while(i<n&&status==0){int u=types[i],e=i+(pair[i]?2:1);
					if(u==rightDelims[literal]){if(run<i)status=addToken(block,state,9,source,text,run,i,tokenList,tokenLength,stringList,stringLength,doString);
						if(status==0)status=addToken(block,state,3,source,text,i,e,tokenList,tokenLength,stringList,stringLength,doString);i=e;closed=true;break;}
					if(escapeCode>=0&&u==escapeCode&&e<n){if(run<i)status=addToken(block,state,9,source,text,run,i,tokenList,tokenLength,stringList,stringLength,doString);
						int after=e+(pair[e]?2:1);if(status==0)status=addToken(block,state,10,source,text,i,after,tokenList,tokenLength,stringList,stringLength,doString);i=after;run=i;continue;}
					i=e;}
				if(!closed&&status==0){if(run<i)status=addToken(block,state,9,source,text,run,i,tokenList,tokenLength,stringList,stringLength,doString);if(status==0)status=4;} // badEnding
				continue;}
			if(comment>=0){
				int open=leftComment[2*comment+1]<0?end:end+(pair[end]?2:1);
				status=addToken(block,state,7,source,text,i,open,tokenList,tokenLength,stringList,stringLength,doString);i=open;int depth=1;
				while(i<n&&status==0){int e=i+(pair[i]?2:1);int r0=rightComment[2*comment],r1=rightComment[2*comment+1];
					if(types[i]==r0&&(r1<0||e<n&&types[e]==r1)){int close=r1<0?e:e+(pair[e]?2:1);if(--depth==0){status=addToken(block,state,8,source,text,i,close,tokenList,tokenLength,stringList,stringLength,doString);i=close;break;}i=close;continue;}
					if(doNest&&types[i]==leftComment[2*comment]&&(leftComment[2*comment+1]<0||e<n&&types[e]==leftComment[2*comment+1])){depth++;i=leftComment[2*comment+1]<0?e:e+(pair[e]?2:1);continue;}
					i=e;}
				if(depth>0&&status==0)status=4;
				continue;}
			int type=t;
			if(t==1){while(end<n&&types[end]==1)end+=pair[end]?2:1;}
			else if(t==4){while(end<n&&(types[end]==4||doAlphanumeric&&types[end]==5))end+=pair[end]?2:1;}
			else if(t==5){while(end<n&&types[end]==5)end++;
				if(decimalCode>=0&&end+1<n&&types[end]==decimalCode&&types[end+1]==5){type=12;end++;while(end<n&&types[end]==5)end++;}}
			else if(end<n&&!pair[i]){int c=text[i]&0xff,d=text[end]&0xff;int two=c=='<'&&d=='='?34:c=='>'&&d=='='?36:c=='='&&d=='='?38:c==':'&&d=='='?39:c=='<'&&d=='>'?41:c=='!'&&d=='='?42:-1;
				if(two>=0){type=two;end++;}}
			status=addToken(block,state,type,source,text,i,end,tokenList,tokenLength,stringList,stringLength,doString);
			i=end;
		}
		memory.writeLong(block+16,state[0]);memory.writeLong(block+28,state[1]);
		return status;
	}
	/** One TokenRec of IntlTokenize (and its string with doString); tokenOverflow / stringOverflow when the lists are full. */
	private int addToken(int block,int[] state,int type,int source,byte[] text,int start,int end,int tokenList,int tokenLength,int stringList,int stringLength,boolean doString) throws M68kCpuException {
		if(tokenList==0||state[0]>=tokenLength)return 1;
		int string=0;
		if(doString){int length=Math.min(255,end-start),need=(length+2+1)&~1;
			if(stringList==0||state[1]+need>stringLength)return 2;
			string=stringList+state[1];memory.writeByte(string,length);for(int k=0;k<length;k++)memory.writeByte(string+1+k,text[start+k]&0xff);memory.writeByte(string+1+length,0);if((length&1)!=0)memory.writeByte(string+2+length,0);
			state[1]+=need;}
		int record=tokenList+state[0]*14;
		memory.writeWord(record,type);memory.writeLong(record+2,source+start);memory.writeLong(record+6,end-start);memory.writeLong(record+10,string);
		state[0]++;
		return 0;
	}

	// ------------------------------------------------------------------ justified text (DrawJust, MeasureJust, Char2Pixel... with a slop)
	// The Roman script system justifies a line by spreading the slop (extra pixels, negative to narrow it) over its space characters;
	// a run without spaces stays as it is. These routines drew and measured as if the slop were 0 (QuickDraw Utilities' DrawJust
	// ended at the same pen position as DrawText).
	/** The space characters of a run (one-byte spaces; a double-byte character's trail byte is never one), in its first {@code limit} bytes. */
	private int spacesIn(int text,int length,int limit,boolean japanese) throws M68kCpuException {
		int n=0;for(int i=0;i<Math.min(length,limit);i++){int c=memory.readByte(text+i);if(japanese&&isJapaneseLead(c)){i++;continue;}if(c==0x20)n++;}
		return n;
	}
	/** The part of the slop that falls before byte {@code offset}: the slop of each space before it. */
	private int slopBefore(int text,int length,int offset,double slop,boolean japanese) throws M68kCpuException {
		if(slop==0||length<=0)return 0;int spaces=spacesIn(text,length,length,japanese);if(spaces==0)return 0;
		return (int)Math.round(slop*spacesIn(text,length,offset,japanese)/spaces);
	}
	/** DrawJust/DrawJustified: the run drawn with the port's spExtra (SpaceExtra) raised by the slop of one space. */
	private void drawJustified(int text,int length,double slop,boolean japanese) throws M68kCpuException {
		int port=api.currentQuickDrawPort(),spaces=slop==0||length<=0?0:spacesIn(text,length,length,japanese);
		if(spaces==0||port==0){quickDraw.drawTextRun(text,length);return;}
		int saved=memory.readLong(port+76); // spExtra (Fixed), at the same place in a GrafPort and a CGrafPort
		memory.writeLong(port+76,saved+(int)Math.round(slop/spaces*65536));
		try{quickDraw.drawTextRun(text,length);}finally{memory.writeLong(port+76,saved);}
	}
	/** MeasureJust/MeasureJustified: the charLocs of the plain run plus the slop of the spaces before each position. */
	private void measureJustified(int text,int length,double slop,int widths,boolean japanese) throws M68kCpuException {
		quickDraw.measureRun(text,length,widths);
		int spaces=widths==0||slop==0||length<=0?0:spacesIn(text,length,length,japanese);if(spaces==0)return;
		for(int i=0,seen=0;i<length;i++){int c=memory.readByte(text+i);
			if(japanese&&isJapaneseLead(c)&&i+1<length){memory.writeWord(widths+(i+1)*2,(short)memory.readWord(widths+(i+1)*2)+(int)Math.round(slop*seen/spaces));i++;}
			else if(c==0x20)seen++;
			memory.writeWord(widths+(i+1)*2,(short)memory.readWord(widths+(i+1)*2)+(int)Math.round(slop*seen/spaces));}
	}
	/** Pixel2Char/PixelToChar on a justified run: {offset, leadingEdge, remaining width} as {@link HostQuickDraw68k#pixelToChar} answers for a plain one. */
	private int[] justifiedPixelToChar(int text,int length,int pixel,double slop,boolean japanese) throws M68kCpuException {
		if(slop==0||length<=0||spacesIn(text,length,length,japanese)==0)return quickDraw.pixelToChar(text,length,pixel);
		if(pixel<=0)return new int[]{0,1,-1};
		int previous=0,start=0;
		for(int i=0;i<length;){int c=memory.readByte(text+i),next=japanese&&isJapaneseLead(c)&&i+1<length?i+2:i+1;
			int w=quickDraw.charToPixel(text,length,next)+slopBefore(text,length,next,slop,japanese);
			if(pixel<w)return pixel<(previous+w)/2?new int[]{start,1,-1}:new int[]{next,0,-1};
			previous=w;start=next;i=next;}
		return new int[]{length,1,pixel-previous};
	}

	/**
	 * StyledLineBreak's word boundary at or before {@code limit}: after a space, or - in Japanese text - on either side of a
	 * two-byte character (kanji and kana break anywhere, a run of Roman letters is one word: "...状態でREALbasicが" breaks
	 * before "REALbasic", not inside it). 0 when the range has none.
	 */
	private int wordBreakBefore(int text,int limit,boolean japanese) throws M68kCpuException {
		boolean[] boundary=new boolean[limit+1],wide=new boolean[limit+1];
		for(int i=0;i<limit;){boundary[i]=true;int n=japanese&&isJapaneseLead(memory.readByte(text+i))?2:1;wide[i]=n==2;i+=n;}
		boundary[limit]=true; // pixelToChar answers character boundaries
		for(int b=limit;b>0;b--){if(!boundary[b])continue;if(memory.readByte(text+b-1)==' ')return b;
			if(!japanese)continue;int prev=b-1;while(prev>0&&!boundary[prev])prev--;
			if(wide[prev]||b<limit&&wide[b]||b==limit&&limit>0&&isJapaneseLead(memory.readByte(text+b)))return b;}
		return 0;
	}
	static boolean isJapaneseLead(int c){return (c>=0x81&&c<=0x9f)||(c>=0xe0&&c<=0xfc);}
	private int[] findWord(int text,int length,int offset,boolean leadingEdge) throws M68kCpuException {
		int start=Math.max(0,Math.min(offset,length)),end=start;
		// past either end there is no character: an empty break there, as the Script Manager answers (WAV counts words by
		// calling FindWordBreaks from each word's end until the first offset reaches the length - the trailing "." came back
		// as a word ending at the length forever and Word Count hung)
		if(leadingEdge?start==length:start==0)return new int[]{start,start};
		if(!leadingEdge)start--;
		boolean word=start<length&&isWordChar(memory.readByte(text+start));
		while(start>0&&isWordChar(memory.readByte(text+start-1))==word)start--;
		end=start;while(end<length&&isWordChar(memory.readByte(text+end))==word)end++;
		return new int[]{start,end};
	}
	private static boolean isWordChar(int c){return Character.isLetterOrDigit((char)c)||c>=0x80||c=='_';}
	/** The keyboard script (KeyScript): 0 Roman, 1 Japanese; reported by GetScriptManagerVariable(smKeyScript). One for the whole
	 *  system, as on a Mac: KeyScript 0 in one external's VM and GetKeyScript in another's (UDI's KeyScript/GetKeyScript stacks)
	 *  see the same, and HyperCard's menu "Keyboards" checks it (TTalk.keyboardsMenu); -1 until set: the stack's script. */
	static volatile int keyScript=-1;
	static int currentKeyScript(boolean japanese){int k=keyScript;return k>=0&&(k==0||japanese)?k:japanese?1:0;}
	/**
	 * KeyScript(code): a script code selects that script's keyboard (Roman turns the host input method's composition
	 * off, Japanese on) - a script that is not installed (only Roman, and Japanese for a Japanese stack) changes nothing;
	 * the negative verbs of Script.h: smKeyNextScript -1 and smKeySwapScript -3 toggle between the two, smKeySysScript -2
	 * selects the system script, smKeyRoman -17 Roman; the others (next keyboard -4, enable/disable keyboards -6/-7,
	 * inline -8, direction -9, input methods -10/-11, disable switching -12, ...) leave the script as it is.
	 */
	private void keyScript(int code,boolean japanese){
		int current=currentKeyScript(japanese),next=current;
		if(code>=0){if(code==0||code==1&&japanese)next=code;}
		else if(code==-1||code==-3){if(japanese)next=current==1?0:1;}
		else if(code==-2)next=japanese?1:0;
		else if(code==-17)next=0;
		keyScript=next;
		if(host!=null)host.setCompositionEnabled(next==1);
		if(Mac68kApi.TRACE)System.err.println("[68K] KeyScript "+code+" -> "+(next==1?"Japanese":"Roman"));
	}
	/**
	 * Transliterate(srcHandle, dstHandle, target, srcMask): the text classes named by the mask (smMaskAscii 1 = 1-byte Roman,
	 * smMaskNative 2 = 2-byte characters, smMaskAscii2 8 = 2-byte Roman, smMaskKana1 $10 = 1-byte katakana, smMaskKana2 $20,
	 * smMaskGana2 $80 = hiragana) are converted to the target form (smTransAscii 0 / smTransAscii1 2 = 1-byte Roman,
	 * smTransNative 1 / smTransAscii2 3 = 2-byte Roman, smTransKana1 4, smTransKana2 5, smTransGana2 7) with the case
	 * flags (smTransLower $4000, smTransUpper $8000) applied to Roman letters; the result replaces the destination Handle.
	 */
	private int transliterate(int srcHandle,int dstHandle,int target,int srcMask) throws M68kCpuException {
		if(srcHandle==0||dstHandle==0)return PARAM_ERR;byte[] src=api.handleContents(srcHandle);if(src==null)return PARAM_ERR;
		String text=encoding.decode(src);int form=target&0xff;boolean upper=(target&0x8000)!=0,lower=(target&0x4000)!=0;
		StringBuilder out=new StringBuilder();
		for(int i=0;i<text.length();i++){char c=text.charAt(i);int cls=classOf(c);char next=i+1<text.length()?text.charAt(i+1):0;
			if((srcMask&cls)!=0){
				switch(form){
					case 0:case 2:{String h=toHalfwidth(c);if(h!=null){out.append(h);continue;}break;}                  // 1-byte forms
					case 1:case 3:case 5:{String f=toFullwidth(c,next);if(f!=null){out.append(f.charAt(0));if(f.length()>1)i++;if(form==5&&f.charAt(0)>=0x3041&&f.charAt(0)<=0x3096)out.setCharAt(out.length()-1,(char)(f.charAt(0)+0x60));continue;}
						if(form==5&&c>=0x3041&&c<=0x3096){out.append((char)(c+0x60));continue;}break;}
					case 4:{String h=toHalfwidthKana(c>=0x3041&&c<=0x3096?(char)(c+0x60):c);if(h!=null){out.append(h);continue;}break;} // 1-byte katakana
					case 7:{String f=toFullwidth(c,next);char k=f!=null?f.charAt(0):c;if(f!=null&&f.length()>1)i++;if(k>=0x30a1&&k<=0x30f6){out.append((char)(k-0x60));continue;}if(f!=null){out.append(k);continue;}break;} // hiragana
					default:break;
				}
			}
			out.append(c);
		}
		String result=out.toString();
		if(upper||lower){StringBuilder cased=new StringBuilder();for(int i=0;i<result.length();i++){char c=result.charAt(i);int cls=classOf(c);
			if((srcMask&cls)!=0&&(cls==1||cls==8)){if(upper)c=Character.toUpperCase(c);else c=Character.toLowerCase(c);}cased.append(c);}result=cased.toString();}
		byte[] bytes=encoding.encodeReplacing(result);api.loadHandle(dstHandle,bytes);return NO_ERR;
	}
	/** smMask class of a character: 1 Roman, 8 2-byte Roman, $10 1-byte katakana, $20 2-byte katakana, $80 hiragana, 2 other 2-byte. */
	private static int classOf(char c){
		if(c<0x80)return 1;if(c>=0xff01&&c<=0xff5e)return 8;if(c>=0xff61&&c<=0xff9f)return 0x10;if(c>=0x30a1&&c<=0x30fc)return 0x20;if(c>=0x3041&&c<=0x309f)return 0x80;return 2;
	}
	private static String toHalfwidth(char c){
		if(c>=0xff01&&c<=0xff5e)return String.valueOf((char)(c-0xfee0));if(c==0x3000)return " ";
		return toHalfwidthKana(c);
	}
	/** Fullwidth katakana (and punctuation) to its halfwidth form, built from the halfwidth block's own compatibility decompositions. */
	private static final java.util.Map<Character,Character> HALFWIDTH_KANA=new java.util.HashMap<Character,Character>();
	static{for(char h=0xff61;h<=0xff9d;h++){String full=java.text.Normalizer.normalize(String.valueOf(h),java.text.Normalizer.Form.NFKC);if(full.length()==1)HALFWIDTH_KANA.put(full.charAt(0),h);}
		HALFWIDTH_KANA.put('\u30ee','\uff9c');HALFWIDTH_KANA.put('\u30f0','\uff72');HALFWIDTH_KANA.put('\u30f1','\uff74');} // small wa, wi, we: no halfwidth forms of their own
	private static String toHalfwidthKana(char c){
		String nfd=java.text.Normalizer.normalize(String.valueOf(c),java.text.Normalizer.Form.NFD);
		Character h=HALFWIDTH_KANA.get(nfd.charAt(0));if(h==null)return null;
		char mark=nfd.length()>1?nfd.charAt(1):0;return mark==0x3099?h+"\uff9e":mark==0x309a?h+"\uff9f":String.valueOf(h);
	}
	/** The 2-byte form of a 1-byte character (Roman to fullwidth Roman, halfwidth katakana with a following voice mark to one katakana); null when it has none. */
	private static String toFullwidth(char c,char next){
		if(c>=0x21&&c<=0x7e)return String.valueOf((char)(c+0xfee0));if(c==' ')return "\u3000";
		if(c>=0xff61&&c<=0xff9f){String pair=(next==0xff9e||next==0xff9f)?""+c+next:String.valueOf(c);String n=java.text.Normalizer.normalize(pair,java.text.Normalizer.Form.NFKC);
			if(n.length()==1)return pair.length()==2?n+"\u0000":n;return n.substring(0,1);} // NFKC composes ｶ+ﾞ into ガ; the marker length tells the caller to skip the mark
		return null;
	}
	/** GetScriptManagerVariable / GetEnvirons: the selectors are the Script.h values (smEnabled 4 = number of enabled scripts,
	 *  smSysScript 18, smKeyScript 22, smDoubleByte 36, smRegionCode 40). Some programs show Japanese menus only when more than
	 *  one script is enabled and the system script is Japanese, as on a KanjiTalk System. */
	private int scriptManagerVariable(int selector){
		boolean japanese=encoding==StackTextEncoding.JAPANESE;
		switch(selector){
			case 0:return 0x0710;  // smVersion: Script Manager 7.1
			case 2:return 0;       // smMunged
			case 4:return japanese?2:1; // smEnabled: Roman, plus Japanese on a KanjiTalk System
			case 6:return 0;       // smBidirect
			case 8:return 0;       // smFontForce
			case 10:return 0;      // smIntlForce
			case 12:return 0;      // smForced
			case 14:return 0;      // smDefault
			case 16:return 0;      // smPrint
			case 18:return japanese?1:0; // smSysScript
			case 20:return japanese?1:0; // smLastScript
			case 22:return currentKeyScript(japanese); // smKeyScript
			case 24:return 0;      // smSysRef: the System file's refNum
			case 26:return 0;      // smKeyCache
			case 28:return 0;      // smKeySwap
			case 30:return 0;      // smGenFlags
			case 32:return 0;      // smOverride
			case 34:return 0;      // smCharPortion
			case 36:return japanese?1:0; // smDoubleByte: a two-byte script is installed
			case 38:return keyCache; // smKCHRCache: the KCHR in use
			case 40:return japanese?14:0; // smRegionCode: verJapan
			case 42:return 0;      // smKeyDisableState
			default:return 0;
		}
	}
	/** GetScriptVariable: the selectors are the Script.h values (smScriptKeys is 32 - HyperCard asks it for the KCHR id at every key-down and beeps when that resource is missing). */
	private int scriptVariable(int script,int selector){
		boolean jp=encoding==StackTextEncoding.JAPANESE&&(script==1||script==-1||script==-2);
		int itl=jp?SystemResources68k.JAPANESE_ITL:0;
		switch(selector){
			case 0:return 1; // smScriptVersion
			case 2:return 0; // smScriptMunged
			case 4:return 1; // smScriptEnabled (Roman is always enabled; Japanese only asked with the Japanese encoding)
			case 6:return 0; // smScriptRight
			case 8:return 0; // smScriptJust (left)
			case 10:return jp?1:0; // smScriptRedraw
			case 12:return jp?0x4000:0; // smScriptSysFond: Chicago 0, Osaka 16384
			case 14:return jp?0x4000:3; // smScriptAppFond: Geneva 3
			case 16:return itl; // smScriptBundle / smScriptNumber: itl0
			case 18:return itl; // smScriptDate: itl1
			case 20:return itl; // smScriptSort: itl2
			case 22:return jp?0:0x00c6; // smScriptFlags: Roman = singByte | natCase | autoInit | univExt
			case 24:return itl; // smScriptToken: itl4
			case 26:return itl; // smScriptEncoding: itl5
			case 28:return jp?11:0; // smScriptLang: langJapanese 11
			case 30:return 0; // smScriptNumDate
			case 32:return jp?SystemResources68k.JAPANESE_ITL+1:0; // smScriptKeys: KCHR
			case 34:return jp?SystemResources68k.JAPANESE_ITL+1:0; // smScriptIcon: kcs#
			case 36:case 38:case 40:case 42:case 44:return 0; // smScriptPrint/Trap/Creator/File/Name
			case 78:return jp?(SystemResources68k.JAPANESE_ITL+52)<<16|12:4<<16|9; // smScriptMonoFondSize: Monaco 9, Osaka－等幅 12
			case 80:return jp?0x4000<<16|12:3<<16|12; // smScriptPrefFondSize
			case 82:return jp?0x4000<<16|12:3<<16|9; // smScriptSmallFondSize
			case 84:return jp?0x4000<<16|12:12; // smScriptSysFondSize: Chicago 12
			case 86:return jp?0x4000<<16|12:3<<16|12; // smScriptAppFondSize
			case 88:return jp?0x4000<<16|12:3<<16|9; // smScriptHelpFondSize
			case 90:return jp?0x7b:0x7f; // smScriptValidStyles
			case 92:return 2; // smScriptAliasStyle: italic
			default:return 0;
		}
	}

	// ------------------------------------------------------------------ Pack 6 (International Utilities)

	/** StringToDate: {charsUsed, year, month, day} from "2026/9/13", "9/13/26", "2026年9月13日", "Sep 13, 2026" ...; null when no date. */
	static int[] parseDate(String t){
		// The Script Manager reads the date (and the time) from the start of the text, after white space: "95.12.23 16:05" is a
		// date with the time left over, not a time found in the middle (HyperCard then reads the time from what is left)
		java.util.regex.Matcher m=java.util.regex.Pattern.compile("^\\s*(\\d{1,4})\\s*(年|/|-|\\.)\\s*(\\d{1,2})\\s*(月|/|-|\\.)\\s*(\\d{1,4})(?:\\s*日)?").matcher(t);
		String[] months={"jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"};
		if(m.find()){int a=Integer.parseInt(m.group(1)),b=Integer.parseInt(m.group(3)),c=Integer.parseInt(m.group(5));int year,month,day;
			// y/m/d (Japanese) or m/d/y. Dots are the Japanese short date's separators (y.m.d: HyperCard J's and HyperZebra's `the date`
			// "26.10.18"): its two-digit years 00-31 were read as months, so convert the date - and Dartmouth's ConvertDate - failed
			boolean dots=m.group(2).equals(".")&&m.group(4).equals(".")&&b>=1&&b<=12&&c>=1&&c<=31;
			if(m.group(1).length()>2||a>31||t.contains("年")||dots){year=a;month=b;day=c;}else{month=a;day=b;year=c;}
			if(year<100)year+=year<40?2000:1900;
			if(!validDate(year,month,day))return null;return new int[]{m.end(),year,month,day};}
		m=java.util.regex.Pattern.compile("(?i)^\\s*(?:[a-z]+\\.?,?\\s+)?([a-z]{3})[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{2,4})").matcher(t); // [Sunday,] September 13, 2026
		if(m.find()){int month=java.util.Arrays.asList(months).indexOf(m.group(1).toLowerCase())+1;if(month==0)return null;int year=Integer.parseInt(m.group(3));if(year<100)year+=year<40?2000:1900;
			int day=Integer.parseInt(m.group(2));return validDate(year,month,day)?new int[]{m.end(),year,month,day}:null;}
		m=java.util.regex.Pattern.compile("(?i)^\\s*(?:[a-z]+\\.?,?\\s+)?(\\d{1,2})\\s+([a-z]{3})[a-z]*\\.?,?\\s+(\\d{2,4})").matcher(t); // [Sunday,] 13 Sep 2026
		if(m.find()){int month=java.util.Arrays.asList(months).indexOf(m.group(2).toLowerCase())+1;if(month==0)return null;int year=Integer.parseInt(m.group(3));if(year<100)year+=year<40?2000:1900;
			int day=Integer.parseInt(m.group(1));return validDate(year,month,day)?new int[]{m.end(),year,month,day}:null;}
		return null;
	}
	/** A UTC calendar for Mac dates: proleptic Gregorian, as the Script Manager's long dates are (Java switches to the Julian calendar
	 *  before October 1582: Rinaldi's DaysAndDate counted 366 days in 1000 and 1 day from 10/4/1582 to 10/15/1582). */
	static Calendar macCalendar(){GregorianCalendar c=new GregorianCalendar(TimeZone.getTimeZone("UTC"));c.setGregorianChange(new java.util.Date(Long.MIN_VALUE));return c;}
	/** Whether the day exists in that month (the Script Manager answers dateTimeInvalid for 2/30/2000 or 2/29/1999: Rinaldi's IsDate
	 *  takes them for real dates otherwise), leap years by the Gregorian rule. */
	static boolean validDate(int year,int month,int day){
		if(month<1||month>12||day<1)return false;
		int[] days={31,(year%4==0&&year%100!=0)||year%400==0?29:28,31,30,31,30,31,31,30,31,30,31};
		return day<=days[month-1];
	}
	/** StringToTime: {charsUsed, hour, minute, second} from "3:05 PM", "15:05:30", "午後3:05", "3時5分" ...; null when no time. */
	static int[] parseTime(String t){
		// separators before the time are skipped: what is left after a date ("6/15/2000,12:30:45 PM" → ",12:30:45 PM", the form
		// Rinaldi's DateConverter documents) still reads as a time
		java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)^[\\s,;]*(午前|午後|AM|PM)?\\s*(\\d{1,2})\\s*(?::|時)\\s*(\\d{1,2})\\s*(?:(?::|分)\\s*(\\d{1,2})\\s*秒?)?\\s*(午前|午後|AM|PM|A\\.M\\.|P\\.M\\.)?").matcher(t);
		if(!m.find())return null;
		int hour=Integer.parseInt(m.group(2)),minute=Integer.parseInt(m.group(3)),second=m.group(4)==null?0:Integer.parseInt(m.group(4));
		String ampm=m.group(1)!=null?m.group(1):m.group(5)==null?"":m.group(5);String u=ampm.toUpperCase();
		boolean pm=u.startsWith("P")||u.equals("午後"),am=u.startsWith("A")||u.equals("午前");
		if(hour>23||minute>59||second>59)return null;
		if(pm&&hour<12)hour+=12;if(am&&hour==12)hour=0;
		return new int[]{m.end(),hour,minute,second};
	}

	/** IUDateString's text for seconds since 1904: form 0 shortDate, 1 longDate, 2 abbrevDate. */
	private String dateString(long dateTime,int form){
		Calendar c=macCalendar();c.setTimeInMillis((dateTime-MAC_EPOCH_DELTA)*1000L);
		boolean jp=encoding==StackTextEncoding.JAPANESE;
		// short date as the itl0 we install describes it (no century, no leading zeros): US m/d/yy "5/9/92", Japan y.m.d "92.5.9"
		if(form==0)return jp?String.format("%02d.%d.%d",c.get(Calendar.YEAR)%100,c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH)):String.format("%d/%d/%02d",c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH),c.get(Calendar.YEAR)%100);
		String[] days={"","Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"};String[] months={"January","February","March","April","May","June","July","August","September","October","November","December"};
		// Japanese itl1: "" year "年 " month "月 " day "日 " weekday, the weekday cut to abbrLen (4 bytes = 2 kanji) in abbrevDate
		if(jp){String[] jdays={"","日曜日","月曜日","火曜日","水曜日","木曜日","金曜日","土曜日"};String day=jdays[c.get(Calendar.DAY_OF_WEEK)];
			return String.format("%d年 %d月 %d日 %s",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH),form==1?day:day.substring(0,2));}
		return form==1?days[c.get(Calendar.DAY_OF_WEEK)]+", "+months[c.get(Calendar.MONTH)]+" "+c.get(Calendar.DAY_OF_MONTH)+", "+c.get(Calendar.YEAR):days[c.get(Calendar.DAY_OF_WEEK)].substring(0,3)+", "+months[c.get(Calendar.MONTH)].substring(0,3)+" "+c.get(Calendar.DAY_OF_MONTH)+", "+c.get(Calendar.YEAR);
	}
	/** IUTimeString's text for seconds since 1904, as the System's itl0 has it: 12-hour with " AM"/" PM", noon and midnight 12:00 (U.S., timeCycle 255) or 0:00
	 *  (KanjiTalk, timeCycle 1: FileInfo's sample made on a Japanese Mac reads "11:49:35 PM"). */
	private String timeString(long dateTime,boolean seconds){
		Calendar c=macCalendar();c.setTimeInMillis((dateTime-MAC_EPOCH_DELTA)*1000L);
		int hour=c.get(Calendar.HOUR);if(hour==0&&encoding!=StackTextEncoding.JAPANESE)hour=12;
		String s=String.format("%d:%02d",hour,c.get(Calendar.MINUTE));
		if(seconds)s+=String.format(":%02d",c.get(Calendar.SECOND));s+=c.get(Calendar.AM_PM)==Calendar.AM?" AM":" PM";
		return s;
	}

	/** A string's primary collation key: case and diacriticals dropped (the International Utilities' weak comparison). Folded to
	 *  UPPER case: the Roman sort order puts each lower-case letter with its capital, so [ \ ] ^ _ ` still sort after Z (HyperHIT's
	 *  relation directory, built on a Mac, holds "orderHeaders" before "Order_Items"; lower-case folding put "_" before the letters
	 *  and its openCollection walked that directory for ever). */
	private static String primaryKey(String s){return java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").toUpperCase(java.util.Locale.ROOT);}
	/** The International Utilities' sort order (IUMagString, IUTextOrder): letters first without case and diacriticals, so é sorts with
	 *  e (Rinaldi's FullSort "t=i" put every accented letter after z); equal that way, the plain letter before the accented one, then
	 *  lower case before upper case. */
	static int macTextOrder(String a,String b){
		int order=primaryKey(a).compareTo(primaryKey(b));if(order!=0)return Integer.signum(order);
		order=a.toLowerCase(java.util.Locale.ROOT).compareTo(b.toLowerCase(java.util.Locale.ROOT));if(order!=0)return Integer.signum(order);
		return Integer.signum(-a.compareTo(b));
	}
	private boolean pack6(M68kCpu cpu) throws M68kException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		switch(selector){
			case 0x0000:case 0x000e:{ // IUDateString(dateTime, form, VAR result) / IUDatePString(... intlParam)
				if(selector==0x000e)cpu.a[7]+=4;
				// DateForm is a byte: Pascal (MOVE.B to -(SP)) leaves it in the HIGH byte of its word and garbage below (Super Dice-It
				// pushed $000A for shortDate and got the long date); the ROM reads that byte only, so $0001 is shortDate too
				// (Script Utilities' IULDateString(s, 0) got the long date from the stale low byte)
				int result=memory.readLong(cpu.a[7]),formWord=memory.readWord(cpu.a[7]+4),form=formWord>>>8;long dateTime=memory.readLong(cpu.a[7]+6)&0xffffffffL;cpu.a[7]+=10;
				writePascal(result,dateString(dateTime,form),255);return true;}
			case 0x0002:case 0x0010:{ // IUTimeString(dateTime, wantSeconds, VAR result)
				if(selector==0x0010)cpu.a[7]+=4;
				int result=memory.readLong(cpu.a[7]);boolean seconds=memory.readByte(cpu.a[7]+4)!=0;long dateTime=memory.readLong(cpu.a[7]+6)&0xffffffffL;cpu.a[7]+=10;
				writePascal(result,timeString(dateTime,seconds),255);return true;}
			case 0x0004:writeBoolean(cpu.a[7],encoding==StackTextEncoding.JAPANESE);return true; // IUMetric
			case 0x0006:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],resources.internationalResource(id));return true;} // IUGetIntl
			case 0x0008:{int h=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4),ref=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;resources.setInternational(ref,id,h);return true;} // IUSetIntl(refNum, theID, intlHandle): 8 bytes
			case 0x000a:case 0x001a:{ // IUMagString(aPtr,bPtr,aLen,bLen) : INTEGER
				if(selector==0x001a)cpu.a[7]+=4;
				int bLen=(short)memory.readWord(cpu.a[7]),aLen=(short)memory.readWord(cpu.a[7]+2),b=memory.readLong(cpu.a[7]+4),a=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				String sa=encoding.decode(memory.readBytes(a,Math.max(0,aLen))),sb=encoding.decode(memory.readBytes(b,Math.max(0,bLen)));
				memory.writeWord(cpu.a[7],macTextOrder(sa,sb));return true;}
			case 0x0012:case 0x0026:{ // MagWString / MagWPString(.., itl2 Handle): private "weak" IUMagString added for Microsoft (Excel 2.2's
				// sorting): -1/0/1, but strings differing only in case or diacriticals are equal (InternationalPACK.a WeakMag)
				if(selector==0x0026)cpu.a[7]+=4;
				int bLen=(short)memory.readWord(cpu.a[7]),aLen=(short)memory.readWord(cpu.a[7]+2),b=memory.readLong(cpu.a[7]+4),a=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				String sa=primaryKey(encoding.decode(memory.readBytes(a,Math.max(0,aLen)))),sb=primaryKey(encoding.decode(memory.readBytes(b,Math.max(0,bLen))));
				memory.writeWord(cpu.a[7],Integer.signum(sa.compareTo(sb)));return true;}
			case 0x000c:case 0x001c:{ // IUMagIDString: equality only
				if(selector==0x001c)cpu.a[7]+=4;
				int bLen=(short)memory.readWord(cpu.a[7]),aLen=(short)memory.readWord(cpu.a[7]+2),b=memory.readLong(cpu.a[7]+4),a=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				String sa=encoding.decode(memory.readBytes(a,Math.max(0,aLen))),sb=encoding.decode(memory.readBytes(b,Math.max(0,bLen)));
				memory.writeWord(cpu.a[7],sa.equalsIgnoreCase(sb)?0:1);
				if(Mac68kApi.TRACE)System.err.println("[68K] IUMagIDString \""+sa+"\" \""+sb+"\" -> "+(sa.equalsIgnoreCase(sb)?0:1));return true;}
			case 0x0014:case 0x0016:{ // IULDateString(VAR dateTime: LongDateTime, longFlag, VAR result, intlParam) / IULTimeString(..., wantSeconds, ...):
				// the 64-bit seconds since 1904 read through the pointer (LongDateString/LongTimeString; the result used to be left unwritten)
				// longFlag is a DateForm byte like IUDateString's: Pascal pushes it in the HIGH byte of its word (Dartmouth's ConvertDate
				// XFCN: IULDateString(lSecs, longDate, …) read as $0100 gave the abbreviated date for "long date")
				int formWord=memory.readWord(cpu.a[7]+8),form=formWord>>>8; // the high byte only, as the ROM reads it
				int result=memory.readLong(cpu.a[7]+4),flag=selector==0x0014?form:memory.readByte(cpu.a[7]+8),at=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;
				long dateTime=(long)memory.readLong(at)<<32|(memory.readLong(at+4)&0xffffffffL);
				writePascal(result,selector==0x0014?dateString(dateTime,flag):timeString(dateTime,flag!=0),255);return true;}
			case 0x0018:return true; // IUClearCache
			case 0x001e:case 0x0020:{cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true;} // IUScriptOrder / IULangOrder: one script, one language
			case 0x0022:{ // IUTextOrder(aPtr, bPtr, aLen, bLen, aScript, bScript, aLang, bLang) : INTEGER - the strings' collation order; AppleScript sorts and searches text with it (a stub answering "equal" made every `offset`/text item match at 1)
				int bLang=(short)memory.readWord(cpu.a[7]),aLang=(short)memory.readWord(cpu.a[7]+2),bScript=(short)memory.readWord(cpu.a[7]+4),aScript=(short)memory.readWord(cpu.a[7]+6);
				int bLen=(short)memory.readWord(cpu.a[7]+8),aLen=(short)memory.readWord(cpu.a[7]+10),b=memory.readLong(cpu.a[7]+12),a=memory.readLong(cpu.a[7]+16);cpu.a[7]+=20;
				StackTextEncoding ea=aScript==1||(aScript<0&&encoding==StackTextEncoding.JAPANESE)?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN,eb=bScript==1||(bScript<0&&encoding==StackTextEncoding.JAPANESE)?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN;
				String sa=ea.decode(memory.readBytes(a,Math.max(0,aLen))),sb=eb.decode(memory.readBytes(b,Math.max(0,bLen)));
				memory.writeWord(cpu.a[7],macTextOrder(sa,sb));return true;} // primary order ignores case and diacriticals; equal that way, lower case sorts first
			case 0x0024:{ // IUGetItlTable(script, tableCode, VAR itlHandle, VAR offset, VAR length): a table of the script's itl2 (word select/wrap) or itl4 (number parts, untoken, white space)
				int length=memory.readLong(cpu.a[7]),offset=memory.readLong(cpu.a[7]+4),handle=memory.readLong(cpu.a[7]+8),tableCode=(short)memory.readWord(cpu.a[7]+12),script=(short)memory.readWord(cpu.a[7]+14);cpu.a[7]+=16;
				int[] table=resources.internationalTable(script,tableCode);
				if(Mac68kApi.TRACE)System.err.println("[68K] IUGetItlTable script "+script+" table "+tableCode+" -> "+(table==null?"none":String.format("$%x+%d (%d)",table[0],table[1],table[2])));
				if(handle!=0)memory.writeLong(handle,table==null?0:table[0]);if(offset!=0)memory.writeLong(offset,table==null?0:table[1]);if(length!=0)memory.writeLong(length,table==null?0:table[2]);return true;}
			// Type Select Utilities (StringCompare.h): the record is tsrLastKeyTime (long), tsrScript (word), tsrKeyStrokes (Str63);
			// the Finder selects icons by typed names with them
			case 0x0028:{int tsr=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeLong(tsr,0);memory.writeWord(tsr+4,0);memory.writeByte(tsr+6,0);return true;} // TypeSelectClear
			case 0x002a:{ // TypeSelectNewKey(event, tsr) : Boolean - a printable key without Command extends the keystrokes
				int tsr=memory.readLong(cpu.a[7]),event=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
				int what=memory.readWord(event),ch=memory.readLong(event+2)&0xff,when=memory.readLong(event+6),modifiers=memory.readWord(event+14);
				boolean key=(what==3||what==5)&&(modifiers&0x100)==0&&ch>=0x20&&ch!=0x7f;
				if(key){
					int threshold=Math.max(60,2*(short)memory.readWord(0x18e));int length=memory.readByte(tsr+6);
					if(length>0&&(when-memory.readLong(tsr))>threshold)length=0;
					if(length<63){memory.writeByte(tsr+7+length,ch);memory.writeByte(tsr+6,length+1);}
					memory.writeLong(tsr,when);memory.writeWord(tsr+4,encoding==StackTextEncoding.JAPANESE?1:0);
				}
				writeBoolean(cpu.a[7],key);return true;}
			// TypeSelectFindItem(tsr, listSize, selectMode, getStringProc, yourDataPtr) : short - items are numbered 1..listSize and
			// 0 means none (the Finder's getStringProc reads its icon array at item-1; item 0 read past it and jumped to 0); the
			// getStringProc's Boolean is "continue", false ends the search
			case 0x002c:{
				int data=memory.readLong(cpu.a[7]),proc=memory.readLong(cpu.a[7]+4),mode=(short)memory.readWord(cpu.a[7]+8),count=(short)memory.readWord(cpu.a[7]+10),tsr=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;
				String keys=typeSelectKeys(tsr);int best=0;String bestName=null;int last=0;String lastName=null;
				int[] regs=cpu.a.clone(),dr=cpu.d.clone();int saved=cpu.a[7];
				try{
					cpu.a[7]-=8;int scriptVar=cpu.a[7],stringVar=cpu.a[7]+2;
					for(int item=1;item<=count;item++){
						memory.writeWord(scriptVar,0);memory.writeLong(stringVar,0);
						cpu.pushWord(0);int slot=cpu.a[7];cpu.pushWord(item);cpu.pushLong(scriptVar);cpu.pushLong(stringVar);cpu.pushLong(data);
						cpu.callPascal(proc,14);boolean stop=memory.readByte(slot)==0;cpu.a[7]=slot+2; // the proc answers true to go on (the Finder's always does)
						int str=memory.readLong(stringVar);
						if(str!=0){
							String name=(memory.readWord(scriptVar)==1?StackTextEncoding.JAPANESE:encoding).decode(memory.readBytes(str+1,memory.readByte(str)));
							if(Mac68kApi.TRACE)System.err.println("[68K] TypeSelectFindItem item "+item+" \""+name+"\""+(stop?" (stop)":""));
							int order=typeSelectOrder(name,keys,mode!=0);
							// normal: the first name at or after the keys; next: after them; previous: the last name before them
							if(mode<0){if(order<0&&(bestName==null||typeSelectOrder(name,bestName,true)>0)){best=item;bestName=name;}}
							else if(order>=(mode>0?1:0)&&(bestName==null||typeSelectOrder(name,bestName,true)<0)){best=item;bestName=name;}
							if(lastName==null||typeSelectOrder(name,lastName,true)>0){last=item;lastName=name;}
						}
						if(stop)break;
					}
				}finally{cpu.a[7]=saved;System.arraycopy(dr,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
				if(Mac68kApi.TRACE)System.err.println("[68K] TypeSelectFindItem \""+keys+"\" mode "+mode+" of "+count+" -> "+(best>0?best+" "+bestName:mode>=0?last+" "+lastName:"0"));
				memory.writeWord(cpu.a[7],best>0?best:mode>=0?last:0);return true;}
			case 0x002e:{ // TypeSelectCompare(tsr, testStringScript, testStringPtr) : short
				int str=memory.readLong(cpu.a[7]),script=(short)memory.readWord(cpu.a[7]+4),tsr=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
				String name=str==0?"":(script==1?StackTextEncoding.JAPANESE:encoding).decode(memory.readBytes(str+1,memory.readByte(str)));
				memory.writeWord(cpu.a[7],typeSelectOrder(name,typeSelectKeys(tsr),false));return true;}
			default:throw new M68kMacApiException(cpu.pc,0xa9ed,"Pack6 selector "+selector);
		}
	}
	private String typeSelectKeys(int tsr) throws M68kCpuException {
		StackTextEncoding e=memory.readWord(tsr+4)==1?StackTextEncoding.JAPANESE:encoding;
		return e.decode(memory.readBytes(tsr+7,memory.readByte(tsr+6)));
	}
	/** The order of a name against the typed keys, case-insensitively; unless {@code whole}, only as many characters as were typed count. */
	private static int typeSelectOrder(String name,String keys,boolean whole){
		String n=whole||name.length()<=keys.length()?name:name.substring(0,keys.length());
		return Integer.signum(n.compareToIgnoreCase(keys));
	}

	// ------------------------------------------------------------------ Pack 5 (Elems68K: transcendental functions on SANE extended)

	private boolean pack5(M68kCpu cpu) throws M68kException {
		int opcode=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		int op=opcode&0xff;
		int dst=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		double x=Sane68k.readExtended(memory,dst),r;
		switch(op){
			case 0x00:r=Math.log(x);break;      // Ln
			case 0x02:r=Math.log(x)/Math.log(2);break; // Log2
			case 0x04:r=Math.log1p(x);break;    // Ln1
			case 0x06:r=Math.log1p(x)/Math.log(2);break; // Log21
			case 0x08:r=Math.exp(x);break;      // Exp
			case 0x0a:r=Math.pow(2,x);break;    // Exp2
			case 0x0c:r=Math.expm1(x);break;    // Exp1
			case 0x0e:r=Math.pow(2,x)-1;break;  // Exp21
			// Binary operations: the destination x is pushed last (on top), the other operand(s) below it (SANE.a: PEA y; PEA x; FXPWRY).
			case 0x10:{int src=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int i=(short)memory.readWord(src);r=Math.pow(x,i);Sane68k.writeExtended(memory,dst,r);return true;} // XpwrI: x := x^i (i is a 16-bit integer)
			case 0x12:{int src=memory.readLong(cpu.a[7]);cpu.a[7]+=4;double y=Sane68k.readExtended(memory,src);r=Math.pow(x,y);Sane68k.writeExtended(memory,dst,r);return true;} // XpwrY: x := x^y
			case 0x14:{int n=memory.readLong(cpu.a[7]),rate=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;double nn=Sane68k.readExtended(memory,n),rr=Sane68k.readExtended(memory,rate);r=Math.pow(1+rr,nn);Sane68k.writeExtended(memory,dst,r);return true;} // Compound(r, n): (1+r)^n
			case 0x16:{int n=memory.readLong(cpu.a[7]),rate=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;double nn=Sane68k.readExtended(memory,n),rr=Sane68k.readExtended(memory,rate);r=rr==0?nn:(1-Math.pow(1+rr,-nn))/rr;Sane68k.writeExtended(memory,dst,r);return true;} // Annuity(r, n): (1-(1+r)^-n)/r
			case 0x18:r=Math.sin(x);break;      // FSINX  (opcode order from SANE.a: sin $18, cos $1A, tan $1C, atan $1E)
			case 0x1a:r=Math.cos(x);break;      // FCOSX
			case 0x1c:r=Math.tan(x);break;      // FTANX
			case 0x1e:r=Math.atan(x);break;     // FATANX
			case 0x20:{long seed=(long)x;if(seed<=0||seed>=2147483647L)seed=1;r=(16807L*seed)%2147483647L;break;} // RandomX: x := 7^5 x mod (2^31-1) (x holds the seed)
			default:throw new M68kMacApiException(cpu.pc,0xa9ec,"Elems68K opcode "+op);
		}
		Sane68k.writeExtended(memory,dst,r);if(Mac68kApi.TRACE)System.err.println("[68K] Elems68K op $"+Integer.toHexString(op)+" x="+x+" -> "+r);return true;
	}

	// ------------------------------------------------------------------ Alias Manager / FindFolder

	static final int BAD_FOLDER_DESC_ERR=-4270,DUPLICATE_FOLDER_DESC_ERR=-4271;
	/** Folder descriptors added with AddFolderDescriptor: type -> {foldClass, foldLocation} and type -> name. */
	private final Map<Integer,int[]> folderDescriptors=new HashMap<Integer,int[]>();private final Map<Integer,String> folderDescriptorNames=new HashMap<Integer,String>();
	/** FindFolder's folder of {@code type}: a built-in special folder, or a relative folder ('relf') a program described: the
	 *  folder of that name inside the folder of its foldLocation type. */
	private File folderOfType(int type,boolean create,int depth){
		File f=files.specialFolder(type,create);
		if(f!=null||depth>8)return f;
		int[] d=folderDescriptors.get(type);String name=folderDescriptorNames.get(type);
		if(d==null||d[0]!=0x72656c66||name==null||name.isEmpty())return null;
		File parent=folderOfType(d[1],create,depth+1);if(parent==null)return null;
		File folder;try{folder=files.fileManager.childNamed(parent,name);}catch(IOException|SecurityException e){return null;}
		if(folder==null)return null;
		if(!folder.exists()&&create)folder.mkdirs();
		return folder.isDirectory()?folder:null;
	}
	/** The Finder's kIsAlias flag (fdFlags bit 15) of a file. */
	private static boolean isAliasFlag(File f){byte[] info=FinderInfo68k.read(f);return info!=null&&info.length>=10&&(info[8]&0x80)!=0;}
	private boolean aliasDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;
		switch(selector){
			// Private: FindFolder with the folder names of a given 'fld#' Handle pushed last (Apple's Installer 4 hands in the
			// target disk's System 'fld#'); the special folders here have fixed names, so it is FindFolder once the Handle is popped
			case 0x0a:cpu.a[7]+=4; // fall through
			case 0:{ // FindFolder(vRefNum, folderType, createFolder, VAR foundVRefNum, VAR foundDirID) : OSErr
				int dirIdPtr=memory.readLong(cpu.a[7]),vRefPtr=memory.readLong(cpu.a[7]+4);boolean create=memory.readByte(cpu.a[7]+8)!=0;int type=memory.readLong(cpu.a[7]+10);
				int vRefNum=(short)memory.readWord(cpu.a[7]+14);
				cpu.a[7]+=16;
				// a volume's Trash lives on that volume: only the startup disk has one here (the host folders mounted as volumes
				// get no Trash folder made in them)
				boolean trash=type==0x74727368||type==0x656d7074;
				// the result also comes back in D0, as from the Folder Manager's dispatcher: UxFindFolder (CompileIt!) reads it there and
				// answered "" instead of "Error : Not found folder" for an unknown type
				if(trash&&vRefNum!=0&&vRefNum!=(short)0x8000&&vRefNum!=files.startupVolumeRefNum()){memory.writeWord(cpu.a[7],-43);cpu.d[0]=-43;return true;}
				// The Finder keeps its desktop in the startup disk's "Desktop Folder" (other programs hear of the host's Desktop): run
				// on the host's own, it would lay out and rewrite the user's desktop files
				File folder=type==0x6465736b&&isFinder()?files.finderDesktopFolder():folderOfType(type,create,0);
				// 'root' (kRootFolderType): the root directory of the volume asked about (A-Dock's desktop menu lists each volume's
				// contents from it; kOnSystemDisk is the startup disk)
				if(type==0x726f6f74){folder=null;try{folder=files.fileManager.volumeRoot(vRefNum==(short)0x8000?files.startupVolumeRefNum():vRefNum);}catch(IOException e){}}
				if(Mac68kApi.TRACE)System.err.println("[68K] FindFolder vol "+vRefNum+" '"+AppleEvents68k.fourcc(type)+"'"+(create?" create":"")+" -> "+folder);
				if(folder==null){memory.writeWord(cpu.a[7],-43);cpu.d[0]=-43;return true;}
				try{memory.writeWord(vRefPtr,files.volumeRefNumFor(folder));memory.writeLong(dirIdPtr,files.directoryIdFor(folder));memory.writeWord(cpu.a[7],NO_ERR);cpu.d[0]=NO_ERR;}
				catch(IOException e){memory.writeWord(cpu.a[7],-43);cpu.d[0]=-43;}
				return true;}
			case 2:case 8:{ // NewAlias(fromFile, target, VAR alias) / NewAliasMinimal(target, VAR alias)
				int aliasPtr,target;if(selector==2){aliasPtr=memory.readLong(cpu.a[7]);target=memory.readLong(cpu.a[7]+4);cpu.a[7]+=12;}else{aliasPtr=memory.readLong(cpu.a[7]);target=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;}
				try{File f=files.fileForSpec(target);if(f==null)throw new IOException("no file for the FSSpec");byte[] alias=files.makeAlias(f);memory.writeLong(aliasPtr,api.createHandle(alias));memory.writeWord(cpu.a[7],NO_ERR);}
				catch(IOException e){if(Mac68kApi.TRACE)System.err.println("[68K] NewAlias "+files.describeSpec(target)+" -> fnfErr: "+e.getMessage());memory.writeLong(aliasPtr,0);memory.writeWord(cpu.a[7],-43);}
				return true;}
			case 9:{ // NewAliasMinimalFromFullPath(len, path, zone, server, VAR alias)
				int aliasPtr=memory.readLong(cpu.a[7]);int path=memory.readLong(cpu.a[7]+12),len=(short)memory.readWord(cpu.a[7]+16);cpu.a[7]+=18;
				String fullPath=encoding.decode(memory.readBytes(path,Math.max(0,len)));if(Mac68kApi.TRACE||PpcRuntime.TRACE)System.err.println("[68K] NewAliasMinimalFromFullPath \""+fullPath+"\"");
				try{File f=files.fileForClassicPath(fullPath);if(f==null)throw new IOException("no such path");byte[] alias=files.makeAlias(f);memory.writeLong(aliasPtr,api.createHandle(alias));memory.writeWord(cpu.a[7],NO_ERR);}
				catch(IOException e){memory.writeLong(aliasPtr,0);memory.writeWord(cpu.a[7],-43);}
				return true;}
			case 3:case 0x2b:case 0x0f:{ // ResolveAlias(fromFile, alias, target, VAR wasChanged), ResolveAliasWithMountFlags(..., mountFlags), FollowFinderAlias(fromFile, alias, logon, target, VAR wasChanged)
				int m=selector==0x2b?4:0;int changed=memory.readLong(cpu.a[7]+m),target=memory.readLong(cpu.a[7]+m+4),alias=memory.readLong(cpu.a[7]+m+(selector==0x0f?10:8));
				int fromSpec=memory.readLong(cpu.a[7]+m+(selector==0x0f?14:12));cpu.a[7]+=selector==0x0f?18:16+m;
				File from=null;if(fromSpec!=0)try{from=files.fileForSpec(fromSpec);}catch(IOException ignored){}
				byte[] record=api.handleContents(alias);
				File f=files.aliasTarget(record,from); // a missing target in an existing folder: fnfErr with a valid FSSpec
				// A missing folder on the way is dirNFErr: Buddy API's baFolderExists resolves the folder's path plus a made-up
				// file name and takes fnfErr for "the folder is there" (Last Call then never made its preferences folder)
				if(f==null){memory.writeWord(cpu.a[7],files.aliasFolderMissing(record)?-120:-43);return true;}
				try{files.writeFileSpec(target,f);if(changed!=0)memory.writeByte(changed,0);memory.writeWord(cpu.a[7],f.exists()?NO_ERR:-43);}catch(IOException e){memory.writeWord(cpu.a[7],-43);}
				return true;}
			case 12:case 0x29:{ // ResolveAliasFile(VAR spec, resolveChains, VAR targetIsFolder, VAR wasAliased) / ...WithMountFlags(..., mountFlags): no mounting here
				int m=selector==0x29?4:0;int wasAliased=memory.readLong(cpu.a[7]+m),isFolder=memory.readLong(cpu.a[7]+m+4),spec=memory.readLong(cpu.a[7]+m+10);
				boolean chains=memory.readByte(cpu.a[7]+m+8)!=0;cpu.a[7]+=14+m;
				try{File f=files.fileForSpec(spec);if(f==null||!f.exists()){if(Mac68kApi.TRACE)System.err.println("[68K] ResolveAliasFile "+files.describeSpec(spec)+" -> fnfErr ("+f+")");
						// the flags answer false even then: Virtual Desktop reads targetIsFolder after fnfErr to accept a first run with no preferences file
						writeVarBoolean(isFolder,false);writeVarBoolean(wasAliased,false);memory.writeWord(cpu.a[7],-43);return true;}
					// an alias file (Finder flag kIsAlias) leads to the target its 'alis' 0 names, through further alias files with
					// resolveChains. Interarchy 4.0 keeps its start-up interface as an alias in Startup Items (the installer makes it):
					// handed the alias file itself it found no checksum in the empty data fork and asked "Corrupted Interface?"
					boolean aliased=false;int err=NO_ERR;
					for(int hops=0;hops<10&&isAliasFile(f);hops++){
						ResourceFile68k.Resource r=ResourceFile68k.open(f).get(ResourceFile68k.fourcc("alis"),0);
						File target=r==null?null:files.aliasTarget(r.data(),f);
						if(Mac68kApi.TRACE)System.err.println("[68K] ResolveAliasFile "+f+" -> "+target);
						if(target==null){err=-43;break;}
						f=target;aliased=true;
						if(!f.exists()){err=-43;break;}
						if(!chains)break;
					}
					if(aliased)files.writeFileSpec(spec,f);
					writeVarBoolean(isFolder,f.isDirectory());writeVarBoolean(wasAliased,aliased);memory.writeWord(cpu.a[7],err);}
				catch(IOException e){memory.writeWord(cpu.a[7],-43);}
				return true;}
			case 5:{ // MatchAlias(fromFile, rulesMask, alias, VAR aliasCount, aliasList, VAR needsUpdate, filter, yourData): the one target our aliases name
				int needsUpdate=memory.readLong(cpu.a[7]+8),aliasList=memory.readLong(cpu.a[7]+12),countPtr=memory.readLong(cpu.a[7]+16),alias=memory.readLong(cpu.a[7]+20),fromSpec=memory.readLong(cpu.a[7]+28);cpu.a[7]+=32;
				File from=null;if(fromSpec!=0)try{from=files.fileForSpec(fromSpec);}catch(IOException ignored){}
				File f=alias==0?null:files.aliasTarget(api.handleContents(alias),from);int max=countPtr==0?0:(short)memory.readWord(countPtr);
				if(Mac68kApi.TRACE)System.err.println("[68K] MatchAlias rules $"+Integer.toHexString(memory.readLong(cpu.a[7]-8))+" max "+max+" -> "+f+(f!=null&&!f.exists()?" (missing)":""));
				if(f==null||max<1||aliasList==0){if(countPtr!=0)memory.writeWord(countPtr,0);memory.writeWord(cpu.a[7],-43);return true;}
				// a missing target in an existing folder still comes back as one FSSpec, with fnfErr (FlattenMovieData's new file)
				try{files.writeFileSpec(aliasList,f);memory.writeWord(countPtr,1);if(needsUpdate!=0)memory.writeByte(needsUpdate,0);memory.writeWord(cpu.a[7],f.exists()?NO_ERR:-43);}
				catch(IOException e){memory.writeWord(countPtr,0);memory.writeWord(cpu.a[7],-43);}
				return true;}
			case 1:{ // private CanonifyFile(vRefNum, dirID, fileName, VAR canonicalFile): FSMakeFSSpec with the same stack (ROM alExt.c
				// AL_canonifyFile; NIL spec = paramErr). Word 5.1 calls it on its first Save.
				if(memory.readLong(cpu.a[7])==0){cpu.a[7]+=14;memory.writeWord(cpu.a[7],PARAM_ERR);return true;}
				cpu.d[0]=1;return files.highLevelDispatch(cpu);}
			case 6:cpu.a[7]+=16;memory.writeWord(cpu.a[7],NO_ERR);return true; // UpdateAlias
			// Folder Manager change notifications (Mac OS 8.5+): FolderManagerRegisterNotificationProc(proc, refCon, options) /
			// ...Unregister...(proc, refCon). No folder here is ever re-routed, so the procs are never called. Kaleidoscope 2.3's
			// patches register one at start-up.
			case 0x062f:cpu.a[7]+=12;memory.writeWord(cpu.a[7],NO_ERR);return true;
			case 0x0430:cpu.a[7]+=8;memory.writeWord(cpu.a[7],NO_ERR);return true;
			// Folder Manager 2 (Mac OS 8.5+; QuickTime 4 on 9.x adds its folders): descriptors are kept for FindFolder
			case 0x20:{ // AddFolderDescriptor(foldType, flags, foldClass, foldLocation, badgeSignature, badgeType, name, replaceFlag)
				int sp=cpu.a[7];boolean replace=memory.readByte(sp)!=0;int name=memory.readLong(sp+2),location=memory.readLong(sp+14),folderClass=memory.readLong(sp+18),type=memory.readLong(sp+26);
				cpu.a[7]+=30;
				int err=NO_ERR;
				if(folderDescriptors.containsKey(type)&&!replace)err=DUPLICATE_FOLDER_DESC_ERR;
				else{folderDescriptors.put(type,new int[]{folderClass,location});folderDescriptorNames.put(type,name==0?"":readPascal(name));}
				if(Mac68kApi.TRACE)System.err.println("[68K] AddFolderDescriptor '"+AppleEvents68k.fourcc(type)+"' class '"+AppleEvents68k.fourcc(folderClass)+"' in '"+AppleEvents68k.fourcc(location)+"' \""+folderDescriptorNames.get(type)+"\" -> "+err);
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x21:{int type=memory.readLong(cpu.a[7]);cpu.a[7]+=4;boolean had=folderDescriptors.remove(type)!=null;folderDescriptorNames.remove(type);memory.writeWord(cpu.a[7],had?NO_ERR:BAD_FOLDER_DESC_ERR);return true;} // RemoveFolderDescriptor(foldType)
			case 0x0b:cpu.a[7]+=6;memory.writeWord(cpu.a[7],NO_ERR);return true; // ReleaseFolder(vRefNum, folderType)
			case 0x2a:{ // IsAliasFile(fileFSSpec, VAR aliasFileFlag, VAR folderFlag): the Finder's isAlias flag
				int folderFlag=memory.readLong(cpu.a[7]),aliasFlag=memory.readLong(cpu.a[7]+4),spec=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				try{File f=files.fileForSpec(spec);if(f==null||!f.exists()){memory.writeWord(cpu.a[7],-43);return true;}
					writeVarBoolean(folderFlag,f.isDirectory());writeVarBoolean(aliasFlag,!f.isDirectory()&&isAliasFlag(f));memory.writeWord(cpu.a[7],NO_ERR);}
				catch(IOException e){memory.writeWord(cpu.a[7],-43);}
				return true;}
			case 7:{ // GetAliasInfo(alias, index, Str63): asiZoneName -3, asiServerName -2, asiVolumeName -1, asiAliasName 0, asiParentName 1 and the folders above it
				int str=memory.readLong(cpu.a[7]),index=(short)memory.readWord(cpu.a[7]+4),alias=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
				String info=files.aliasInfo(alias==0?null:api.handleContents(alias),index);
				if(str!=0)writePascal(str,info==null?"":info,63);memory.writeWord(cpu.a[7],alias==0?PARAM_ERR:NO_ERR);return true;}
			default:throw new M68kMacApiException(cpu.pc,0xa823,"AliasDispatch selector "+selector);
		}
	}

	/**
	 * KeyTranslate(transData, keycode, VAR state): the character of a key under its modifiers through the 'KCHR' transData
	 * points at (NIL: the System's) - the modifier byte (keycode's high byte) picks the table - with its dead keys: a dead key
	 * answers 0 and leaves state set; the next key answers the combined character, or the accent (high word) and its own
	 * character (low word). The old version read the US layout whatever KCHR it was given and knew no Option layer.
	 */
	private boolean keyTranslate(M68kCpu cpu) throws M68kCpuException {
		int statePtr=memory.readLong(cpu.a[7]),keycode=memory.readWord(cpu.a[7]+4),transData=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
		byte[] kchr=transData!=0?kchrAt(transData):SystemResources68k.keyboardLayout();
		int result;
		if(kchr==null)result=SystemResources68k.keyChar(keycode,(keycode&0x200)!=0,(keycode&0x400)!=0,(keycode&0x1000)!=0);
		else{
			int key=keycode&0x7f,modifiers=keycode>>>8&0xff,tables=u16(kchr,258),table=kchr[2+modifiers]&0xff;if(table>=tables)table=0;
			int ch=kchr[260+table*128+key]&0xff;
			int deadAt=260+tables*128,deadCount=u16(kchr,deadAt),state=statePtr==0?0:memory.readLong(statePtr);
			boolean up=(keycode&0x80)!=0;
			if(state!=0){ // the previous key was dead: combine
				int record=deadRecord(kchr,deadAt,state-1);result=ch;
				if(record>=0&&!up){int n=u16(kchr,record+2),accent=kchr[record+4+n*2+1]&0xff;result=accent<<16|ch;
					for(int i=0;i<n;i++)if((kchr[record+4+i*2]&0xff)==ch){result=kchr[record+4+i*2+1]&0xff;break;}}
				if(!up&&statePtr!=0)memory.writeLong(statePtr,0);
			}else{
				result=ch;
				if(!up)for(int i=0;i<deadCount;i++){int record=deadRecord(kchr,deadAt,i);if(record<0)break;
					if((kchr[record]&0xff)==table&&(kchr[record+1]&0xff)==key){if(statePtr!=0)memory.writeLong(statePtr,i+1);result=0;break;}}
			}
		}
		memory.writeLong(cpu.a[7],result);return true;
	}
	private static int u16(byte[] b,int at){return at+1<b.length?(b[at]&0xff)<<8|(b[at+1]&0xff):0;}
	/** The offset of dead-key record {@code index} (table, key code, count, completions, no-match), or -1. */
	private static int deadRecord(byte[] kchr,int deadAt,int index){
		int at=deadAt+2;for(int i=0;i<index;i++){if(at+4>kchr.length)return -1;at+=4+u16(kchr,at+2)*2+2;}
		return at+4<=kchr.length?at:-1;
	}
	/** A 'KCHR' in guest memory, read to its dead-key records; null when it does not look like one. */
	private byte[] kchrAt(int pointer) throws M68kCpuException {
		int tables=memory.readWord(pointer+258);if(tables<1||tables>64)return null;
		int deadAt=260+tables*128,deadCount=memory.readWord(pointer+deadAt);if(deadCount>256)return null;
		int end=deadAt+2;for(int i=0;i<deadCount;i++)end+=4+memory.readWord(pointer+end+2)*2+2;
		return memory.readBytes(pointer,end);
	}

	// ------------------------------------------------------------------ memory helpers

	/** The format text a NumFormatString made by StringToFormatRec keeps (see there), or null for another record. */
	private byte[] formatBytes(int canonical) throws M68kCpuException {
		if(canonical==0||memory.readByte(canonical+2)!='H'||memory.readByte(canonical+3)!='Z')return null;
		int n=Math.min(memory.readByte(canonical+4),Math.max(0,memory.readByte(canonical)-3));return memory.readBytes(canonical+5,n);
	}
	private String formatPattern(int canonical) throws M68kCpuException {byte[] b=formatBytes(canonical);return b==null?null:encoding.decode(b);}
	/**
	 * A number in a Script Manager number format ("#,##0.00;-#,##0.00;0"): the part for its sign (or zero), '#' optional and
	 * '0' required digits, '^' a digit or a space, ',' grouping, '.' the decimal point, "E+"/"E-" an exponent (its sign always /
	 * only when negative, its digits as many as the '0's after it: "0.00E+00" makes 12345 "1.23E+04", "#.##E-0" 0.00123 "1.23E-3";
	 * Rinaldi's FormatNum gave "12345.0000E+"), quoted or other characters literal. Null when the pattern has no digits.
	 */
	static String formatNumber(String pattern,double value){
		String[] parts=pattern.split(";",-1);
		String part=value<0&&parts.length>1?parts[1]:value==0&&parts.length>2?parts[2]:parts[0];
		boolean ownSign=value<0&&parts.length>1;double v=ownSign?-value:value;
		int mark=exponentMark(part);
		if(mark>=0){ // scientific: the mantissa has as many integer digits as the pattern before its point
			String mantissaPattern=part.substring(0,mark),exponentPattern=part.substring(mark+2);boolean alwaysSign=part.charAt(mark+1)=='+';
			int integerDigits=0,fractionDigits=0;boolean quote=false,point=false;
			for(char c:mantissaPattern.toCharArray()){if(c=='\''||c=='"')quote=!quote;else if(!quote&&c=='.')point=true;else if(!quote&&(c=='#'||c=='0'||c=='^')){if(point)fractionDigits++;else integerDigits++;}}
			integerDigits=Math.max(1,integerDigits);
			int exponent=v==0?0:(int)Math.floor(Math.log10(Math.abs(v)))-(integerDigits-1);
			// rounding may carry into one more integer digit (9.999 with two decimals): then one more power of ten
			if(v!=0&&java.math.BigDecimal.valueOf(Math.abs(v/Math.pow(10,exponent))).setScale(fractionDigits,java.math.RoundingMode.HALF_UP).compareTo(java.math.BigDecimal.TEN.pow(integerDigits))>=0)exponent++;
			String mantissa=formatNumber(mantissaPattern,v/Math.pow(10,exponent));
			if(mantissa==null)return null;
			int minDigits=0;StringBuilder tail=new StringBuilder();
			for(char c:exponentPattern.toCharArray()){if(c=='0')minDigits++;else if(c!='#'&&c!='^')tail.append(c);}
			String digits=String.valueOf(Math.abs(exponent));while(digits.length()<minDigits)digits="0"+digits;
			return mantissa+"E"+(exponent<0?"-":alwaysSign?"+":"")+digits+tail.toString().replace("'","").replace("\"","");
		}
		StringBuilder decimal=new StringBuilder(),literal=new StringBuilder();boolean digits=false,quoted=false;
		for(int i=0;i<part.length();i++){char c=part.charAt(i);
			if(c=='\''||c=='"'){quoted=!quoted;continue;}
			boolean special=!quoted&&(c=='#'||c=='0'||c=='^'||c==','||c=='.');
			if(!special){literal.append(c);continue;}
			if(literal.length()>0){decimal.append('\'').append(literal.toString().replace("'","''")).append('\'');literal.setLength(0);}
			if(c!=','&&c!='.')digits=true;decimal.append(c=='^'?'#':c);}
		if(literal.length()>0)decimal.append('\'').append(literal.toString().replace("'","''")).append('\'');
		if(!digits)return part.isEmpty()?null:part.replace("'","").replace("\"","");
		try{java.text.DecimalFormat f=new java.text.DecimalFormat(decimal.toString(),java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT));f.setRoundingMode(java.math.RoundingMode.HALF_UP);return f.format(v);}
		catch(IllegalArgumentException e){return null;}
	}
	/** Where a number format's exponent ("E+" or "E-" outside quotes) starts, or -1. */
	private static int exponentMark(String part){
		boolean quote=false;
		for(int i=0;i+1<part.length();i++){char c=part.charAt(i);if(c=='\''||c=='"')quote=!quote;else if(!quote&&(c=='E'||c=='e')&&(part.charAt(i+1)=='+'||part.charAt(i+1)=='-'))return i;}
		return -1;
	}
	String readPascal(int pointer) throws M68kCpuException {
		if(pointer==0)return "";int length=memory.readByte(pointer);return encoding.decode(memory.readBytes(pointer+1,length));
	}
	byte[] readPascalBytes(int pointer) throws M68kCpuException {if(pointer==0)return new byte[]{0};return memory.readBytes(pointer,memory.readByte(pointer)+1);}
	void writePascal(int pointer,String value,int maximum) throws M68kCpuException {
		if(pointer==0)return;byte[] bytes=encoding.encodeReplacing(value==null?"":value);int length=Math.min(maximum,bytes.length);
		memory.writeByte(pointer,length);for(int i=0;i<length;i++)memory.writeByte(pointer+1+i,bytes[i]);
	}
	/** A Boolean function result on the stack: a word whose high byte carries the value. */
	void writeBoolean(int address,boolean value) throws M68kCpuException {memory.writeByte(address,value?1:0);memory.writeByte(address+1,0);}
	/** A VAR Boolean parameter: a single byte (writing a word would clobber the caller's neighbouring local, e.g. its saved A6). */
	void writeVarBoolean(int address,boolean value) throws M68kCpuException {if(address!=0)memory.writeByte(address,value?1:0);}
	int readRectTop(int r) throws M68kCpuException {return (short)memory.readWord(r);}
	String decode(byte[] bytes){return encoding.decode(bytes);}
	/** Decodes a control/menu title, mapping Chicago's special glyphs ($11 command, $12 check, $13 diamond, $14 apple). */
	String decodeTitle(byte[] bytes){return decode(bytes).replace('\u0011','\u2318').replace('\u0012','\u2713').replace('\u0013','\u25c6').replace('\u0014','\uf8ff');}
	/** {@link #decodeTitle} for text drawn in {@code font}: see {@link #decodeFor}. */
	String decodeTitleFor(java.awt.Font font,byte[] bytes){return decodeFor(font,bytes).replace('\u0011','\u2318').replace('\u0012','\u2713').replace('\u0013','\u25c6').replace('\u0014','\uf8ff');}
	/**
	 * The text as {@code font} shows it. Under a Japanese system a Roman-script font (Monaco, Times, Courier ...; not 0/1/3, which
	 * are Osaka) draws its one-byte characters with its own glyphs, as KanjiTalk does: they read as Mac OS Roman ($A5 is a
	 * bullet, $A9 a copyright sign, not half-width katakana; $5C a backslash, not a yen sign). A valid Shift-JIS pair still
	 * reads as Japanese, the Osaka fallback's character, so Japanese text set in such a font stays readable.
	 */
	String decodeFor(java.awt.Font font,byte[] bytes){return classicGlyphs(decodeText(font,bytes));}
	/**
	 * $11-$14 as the Mac's fonts draw them, the command key, check mark, diamond and Apple (a host font has nothing at U+0011):
	 * HyperToaster's dialog text "(Press \x11-period or Esc to Cancel)" and Progress 2.0r's "Type \x11-. to cancel." showed a gap
	 * in static text items, which DrawString already drew (QuickDraw's text goes through {@link #decodeTitleFor}).
	 * A tab is a blank glyph in those fonts (Geneva 9: 6 pixels to a 3-pixel space, Geneva 12: 8 to 4, Monaco 9: one 6-pixel
	 * cell), where a host font draws nothing: an en space is about two spaces of a proportional font and one cell of a
	 * monospaced one. NetPresenz writes its log lines with DrawString and tabs between the fields; they ran together.
	 */
	private static String classicGlyphs(String text){
		for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c>=0x11&&c<=0x14||c=='\t')return text.replace('\u0011','\u2318').replace('\u0012','\u2713').replace('\u0013','\u25c6').replace('\u0014','\uf8ff').replace('\t','\u2002');}
		return text;
	}
	private String decodeText(java.awt.Font font,byte[] bytes){
		if(encoding!=StackTextEncoding.JAPANESE||!Fonts68k.romanScript(font))return decode(bytes);
		StringBuilder sb=new StringBuilder(bytes.length);int i=0,single=0;
		while(i<bytes.length){
			int c=bytes[i]&0xff,t=i+1<bytes.length?bytes[i+1]&0xff:-1;
			if(!isJapaneseLead(c)||!((t>=0x40&&t<=0x7e)||(t>=0x80&&t<=0xfc))){i++;continue;} // one byte: a run of them read together below
			if(i>single)sb.append(StackTextEncoding.ROMAN.decode(java.util.Arrays.copyOfRange(bytes,single,i)));
			sb.append(decode(new byte[]{(byte)c,(byte)t}));i+=2;single=i;
		}
		if(single<bytes.length)sb.append(StackTextEncoding.ROMAN.decode(java.util.Arrays.copyOfRange(bytes,single,bytes.length)));
		return sb.toString();
	}

	void shutDown(){shutDown(false);}
	/** With releaseSound (an external's VM ending because its stack closed) the sounds playing fade out after a short grace. */
	void shutDown(boolean releaseSound){
		if(shutDown)return;shutDown=true;
		threads.shutDown(); // coroutine threads parked for good: their Java threads end
		if(cardWindow!=null)cardWindow.close();
		macTcp.shutDown();appleTalk.shutDown();synchronized(this){if(openTransport!=null)openTransport.shutDown();}quickTime.shutDown();api.soundManager().shutDown(releaseSound);soundDriver.shutDown(releaseSound);if(io!=null)io.shutDown();
		try{resources.flushAll();}catch(Exception ignored){}
		try{files.fileManager.closeAll();}catch(Exception ignored){} // files left open reach the host (a PICT saved as PNG needs its Close)
		try{files.fileManager.giveNewProgramsIcons();}catch(Exception ignored){} // programs it made (expanded from an archive) get their icons on the host
		if(host!=null)host.dispose();
	}
}

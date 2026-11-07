import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CarbonLib for PowerPC programs written for Carbon (Mac OS 8.6-9): the same Toolbox as InterfaceLib (served by the
 * header table), plus what Carbon added on top - the accessor functions for the records Carbon made opaque (ports,
 * windows, dialogs, controls, menus, regions, QuickDraw globals), the New/Dispose/Invoke functions of every UPP, the
 * Scrap Manager's flavor calls, a few Menu/Help/Folder calls, the session printing calls (answered "cancelled"), and a
 * small Carbon Event Manager: handlers on the application and windows, timers, and RunApplicationEventLoop turning classic
 * events into Carbon events (commands, mouse, keyboard, window update/activate/close) with the standard handling for
 * anything the program's handlers leave alone. A Carbon program never initializes the Toolbox itself: CarbonLib did,
 * which {@link #initializeToolbox} does before its main routine.
 */
final class PpcCarbon {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcCarbon(PpcRuntime rt){this.rt=rt;memory=rt.memory;}

	static final int EVENT_NOT_HANDLED=-9874;
	static final int CLASS_MOUSE=0x6d6f7573,CLASS_KEYBOARD=0x6b657962,CLASS_WINDOW=0x77696e64,CLASS_COMMAND=0x636d6473,CLASS_APPLICATION=0x6170706c,CLASS_MENU=0x6d656e75;
	/** The pseudo-address that stands for the application event target (GetApplicationEventTarget). */
	static final int APPLICATION_TARGET=0x41505054;

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	/** A Handle standing for a registered definition procedure: its master pointer is the UPP (a routine descriptor the 68K caller enters through Mixed Mode). */
	private int definitionHandle(int upp) throws M68kCpuException {int h=rt.toolbox.api.createHandle(new byte[4]);rt.toolbox.api.setHandleState(h,0x80);memory.writeLong(h,upp);return h;} // locked: nothing moves the block it no longer points at
	private void ret(int v){rt.ret(v);}
	private void writeRgb(int p,int[] rgb) throws M68kCpuException {if(p!=0){memory.writeWord(p,rgb[0]);memory.writeWord(p+2,rgb[1]);memory.writeWord(p+4,rgb[2]);}}
	/** Name, size and style of a theme font (Appearance 1.1 Platinum values). */
	private String[] themeFont(int id){
		boolean japanese=rt.toolbox.encoding==StackTextEncoding.JAPANESE;String system=japanese?"Osaka":"Charcoal",small=japanese?"Osaka":"Geneva";
		switch(id){
			case 1:case 6:return new String[]{small,"10","0"};           // small system, label
			case 2:return new String[]{small,"10","1"};                   // small emphasized
			case 3:return new String[]{small,japanese?"12":"10","0"};     // views
			case 4:case 107:return new String[]{system,"12","1"};          // emphasized system, alert header
			case 5:return new String[]{japanese?"Osaka":"Geneva","12","0"};// application
			case 106:return new String[]{small,"9","0"};                  // utility window title
			default:return new String[]{system,"12","0"};                 // system, menus, window titles, push buttons
		}
	}
	private void applyThemeFont(int id) throws M68kException {
		if(id==200)return; // kThemeCurrentPortFont
		String[] f=themeFont(id);int p=pascal(f[0]);int num=rt.allocate(2,2);rt.toolbox("GetFNum",p,num);
		rt.toolbox("TextFont",(short)memory.readWord(num));rt.toolbox("TextSize",Integer.parseInt(f[1]));rt.toolbox("TextFace",Integer.parseInt(f[2]));memory.free(num);memory.free(p);
	}
	/** The current port's font, size and face into / back from 6 bytes at {@code p}. */
	private void saveFont(int p) throws M68kException {int port=rt.allocate(4,2);rt.toolbox("GetPort",port);int g=memory.readLong(port);memory.free(port);
		memory.writeWord(p,memory.readWord(g+68));memory.writeWord(p+2,memory.readWord(g+74));memory.writeWord(p+4,memory.readByte(g+70));}
	private void restoreFont(int p) throws M68kException {rt.toolbox("TextFont",(short)memory.readWord(p));rt.toolbox("TextSize",(short)memory.readWord(p+2));rt.toolbox("TextFace",memory.readWord(p+4));}
	private int themeWidth(String t) throws M68kException {byte[] b=rt.libraries.textServices.systemBytes(t);if(b.length==0)return 0;int p=rt.allocate(b.length+2,2);memory.load(p,b);int w=rt.toolbox("TextWidth",p,0,b.length);memory.free(p);return w;}
	/** The class and attributes each window was created with (CreateNewWindow, CreateCustomWindow). */
	private final Map<Integer,int[]> windowClasses=new HashMap<Integer,int[]>();
	/** A window's class: as created, else by its classic definition variant (dialog boxes modal, the rest documents). */
	private int windowClass(int w) throws M68kException {
		int[] k=windowClasses.get(w);if(k!=null)return k[0];
		int variant=rt.toolbox("GetWVariant",w)&0xffff;
		if(variant==1||variant==2||variant==3)return 3;     // dBoxProc, plainDBox, altDBoxProc
		if(variant==5)return 4;                             // movableDBoxProc
		return 6;                                           // kDocumentWindowClass
	}
	/** FSRefs: the File Manager's (an FSRef holds 'hzFR' and the index of its file there). */
	void writeFSRef(int ref,java.io.File f) throws M68kCpuException {rt.toolbox.files.writeFSRef(ref,f);}
	java.io.File fsRefFile(int ref) throws M68kCpuException {return rt.toolbox.files.fsRefFile(ref);}
	/** A new FSSpec block (the caller frees it) for the file an FSRef stands for, or 0. */
	private int specOf(int ref) throws M68kException {java.io.File f=fsRefFile(ref);if(f==null)return 0;int spec=rt.allocate(80,2);rt.toolbox.files.fileManager.writeFSSpec(spec,f);return spec;}
	/** The FSRef of the file an FSSpec names. */
	private void refFromSpec(int spec,int ref) throws M68kException {try{java.io.File f=rt.toolbox.files.fileForSpec(spec);if(f!=null)writeFSRef(ref,f);}catch(java.io.IOException ignored){}}
	/** FSCreateResFile: an empty resource file named by a UTF-16 name in a folder; its FSRef/FSSpec when asked; the ResErr. */
	private int createResourceFile(int parentRef,int nameLength,int name,int newRef,int newSpec) throws M68kException {
		java.io.File parent=fsRefFile(parentRef);if(parent==null||!parent.isDirectory()){memory.writeWord(0xa60,-120);return -120;} // dirNFErr
		StringBuilder sb=new StringBuilder();for(int i=0;i<nameLength;i++)sb.append((char)memory.readWord(name+2*i));
		java.io.File f=new java.io.File(parent,sb.toString().replace('/',':'));
		if(f.exists()){byte[] fork=null;try{fork=ResourceFile68k.readForkBytes(f,null);}catch(java.io.IOException ignored){}if(fork!=null&&fork.length>=16){memory.writeWord(0xa60,-48);return -48;}} // dupFNErr unless the file only lacks a resource fork (BOOM creates the file first, then its resource fork)
		rt.toolbox.resources.createFile(f);int err=(short)memory.readWord(0xa60);
		if(f.exists()){writeFSRef(newRef,f);if(newSpec!=0)rt.toolbox.files.fileManager.writeFSSpec(newSpec,f);}
		return err;
	}
	/** An Internet Config key (or file name) from a Pascal string. */
	private String icKey(int p) throws M68kCpuException {byte[] b=rt.toolbox.readPascalBytes(p);return b.length<1?"":StackTextEncoding.ROMAN.decode(java.util.Arrays.copyOfRange(b,1,b.length));}
	/** The packed map entries of an entries Handle, or of the "Mapping" preference for 0. */
	private java.util.List<PpcInternetConfig.Entry> icMappings(PpcInternetConfig ic,int handle) throws M68kCpuException {
		byte[] v;
		if(handle!=0)v=rt.toolbox.api.handleContents(handle);else{byte[][] p=ic.get("Mapping");v=p==null?null:p[1];}
		return v==null?new java.util.ArrayList<PpcInternetConfig.Entry>():PpcInternetConfig.entries(v);
	}
	private int icMapped(PpcInternetConfig ic,PpcInternetConfig.Entry e,int out) throws M68kCpuException {if(e==null)return PpcInternetConfig.NOT_FOUND;if(out!=0)ic.writeEntry(out,e);return 0;}
	private PpcInternetConfig.Entry icEntryAt(PpcInternetConfig ic,int handle,int pos) throws M68kCpuException {if(handle==0)return null;for(PpcInternetConfig.Entry e:icMappings(ic,handle))if(e.pos==pos)return e;return null;}
	/** Replaces one packed entry inside an entries Handle (an empty replacement deletes it). */
	private void icReplace(int handle,PpcInternetConfig.Entry at,byte[] with) throws M68kException {
		byte[] old=rt.toolbox.api.handleContents(handle);byte[] b=new byte[old.length-at.total+with.length];
		System.arraycopy(old,0,b,0,at.pos);System.arraycopy(with,0,b,at.pos,with.length);System.arraycopy(old,at.pos+at.total,b,at.pos+with.length,old.length-at.pos-at.total);
		rt.toolbox("SetHandleSize",handle,b.length);memory.load(memory.readLong(handle),b);
	}
	/** Window and control properties (creator, tag) and control IDs, kept on the host side. */
	private final Map<Long,byte[]> properties=new HashMap<Long,byte[]>();
	private final Map<Integer,byte[]> controlIds=new HashMap<Integer,byte[]>();
	private static long propertyKey(int object,int creator,int tag){return ((long)object<<32)^((long)creator*31+tag)&0xffffffffL;}
	private int getProperty(long key,int size,int actual,int buffer) throws M68kCpuException {
		byte[] v=properties.get(key);if(v==null)return -5604; // errWindowPropertyNotFound / controlPropertyNotFoundErr
		if(actual!=0)memory.writeLong(actual,v.length);if(buffer!=0)memory.load(buffer,java.util.Arrays.copyOf(v,Math.min(v.length,size)));return 0;
	}
	/** From {@code w} along the window list: the first window (visible, when asked). */
	private int nextWindow(int w,int cls,int mustBeVisible) throws M68kException {
		for(int n=0;w!=0&&n<1000;w=memory.readLong(w+144),n++)if((mustBeVisible==0||memory.readByte(w+110)!=0)&&(cls==-1||windowClass(w)==cls))return w;return 0;}
	/** A new Pascal string in the program's encoding (the caller frees it). */
	private int pascal(String text) throws M68kCpuException {byte[] b=text==null?new byte[0]:rt.libraries.textServices.systemBytes(text);int n=Math.min(255,b.length);int p=rt.allocate(n+2,2);memory.writeByte(p,n);memory.load(p+1,java.util.Arrays.copyOf(b,n));return p;}
	private void setItemText(int menu,int item,int cfString) throws M68kException {int p=pascal(rt.libraries.textServices.cfText(cfString));rt.toolbox("SetMenuItemText",menu,item,p);memory.free(p);}
	/** *out = a new CFString with the Pascal string at {@code p}. */
	private void copyAsCFString(int p,int out) throws M68kCpuException {if(out!=0)memory.writeLong(out,rt.libraries.textServices.newCFString(rt.toolbox.readPascal(p)));}

	/** What CarbonLib did before a Carbon program's main: QuickDraw globals, the managers, the arrow cursor. */
	/** SetUserFocusWindow's window (0: the front window). */
	private int userFocus;
	/** A Carbon program (CarbonLib imported): keys reach focused MLTE objects as text input. */
	private boolean carbonProgram;
	boolean carbonProgram(){return carbonProgram;}
	private int focusWindow() throws M68kException {return userFocus!=0&&windowsContain(userFocus)?userFocus:rt.toolbox("FrontWindow");}
	/** A key-down or auto-key event that a focused MLTE object in the focus window takes as text input (then it is consumed). */
	private boolean textInput(int e) throws M68kException {
		int what=memory.readWord(e);if(!carbonProgram||what!=3&&what!=5)return false;
		int ch=memory.readLong(e+2)&0xff,mods=memory.readWord(e+14);
		if((mods&0x100)!=0||ch==0x0d||ch==0x03||ch==0x09||ch==0x1b)return false; // cmdKey; Return, Enter, Tab, Escape
		return rt.libraries.textension.keyToFocused(focusWindow(),e);
	}
	private boolean waitNextEvent(int mask,int e,int sleep,int mouseRgn) throws M68kException {
		if(!carbonProgram||timers.isEmpty()){boolean got;do{got=(rt.toolbox("WaitNextEvent",mask,e,sleep,mouseRgn)&0xff)!=0;}while(got&&textInput(e));return got;}
		double end=now()+(sleep&0xffffffffL)/60.0;
		while(true){
			double wait=timers.isEmpty()?end-now():fireTimers(),left=end-now();
			int ticks=(int)Math.ceil(Math.max(0,Math.min(wait,left))*60);
			boolean got=(rt.toolbox("WaitNextEvent",mask,e,ticks,mouseRgn)&0xff)!=0;
			if(got&&!textInput(e))return true;
			if(!got&&now()>=end)return false;
		}
	}
	void initializeToolbox() throws M68kException {
		carbonProgram=true;
		int qd=rt.allocate(256,4);int thePort=qd+206-4;
		rt.toolbox("InitGraf",thePort);qdGlobals=thePort;
		rt.toolbox("InitFonts");rt.toolbox("InitWindows");rt.toolbox("InitMenus");rt.toolbox("TEInit");rt.toolbox("InitDialogs",0);rt.toolbox("InitCursor");
		rt.toolbox("FlushEvents",0xffff,0);
		WacomTablet68k.available(); // the host's pen reader, for the tablet data of mouse events
	}
	/** The address of qd.thePort (QuickDraw's globals end there; the others are below it). */
	private int qdGlobals;
	/** The WDEF IDs CreateCustomWindow gives the definition procs it registers (IDs programs rarely ship). */
	private int nextCustomWdef=2000; // procID = id<<4 must stay a positive short
	private final Map<Integer,Integer> customWdefs=new HashMap<Integer,Integer>();
	private int qd() throws M68kCpuException {return qdGlobals!=0?qdGlobals:memory.readLong(rt.cpu68.a[5]);}

	void define(PpcLibraries l){
		// ---- QuickDraw globals and ports
		l.register(null,"GetQDGlobalsThePort",(r,c)->ret(memory.readLong(qd())));
		l.register(null,"SetQDGlobalsThePort",(r,c)->rt.toolbox("SetPort",arg(0)));
		l.register(null,"GetQDGlobalsScreenBits",(r,c)->{memory.copy(qd()-122,arg(0),14);ret(arg(0));});
		l.register(null,"GetQDGlobalsArrow",(r,c)->{memory.copy(qd()-108,arg(0),68);ret(arg(0));});
		l.register(null,"GetQDGlobalsDarkGray",(r,c)->{memory.copy(qd()-40,arg(0),8);ret(arg(0));});
		l.register(null,"GetQDGlobalsLightGray",(r,c)->{memory.copy(qd()-32,arg(0),8);ret(arg(0));});
		l.register(null,"GetQDGlobalsGray",(r,c)->{memory.copy(qd()-24,arg(0),8);ret(arg(0));});
		l.register(null,"GetQDGlobalsBlack",(r,c)->{memory.copy(qd()-16,arg(0),8);ret(arg(0));});
		l.register(null,"GetQDGlobalsWhite",(r,c)->{memory.copy(qd()-8,arg(0),8);ret(arg(0));});
		l.register(null,"GetQDGlobalsRandomSeed",(r,c)->ret(memory.readLong(qd()-126)));
		l.register(null,"SetQDGlobalsRandomSeed",(r,c)->memory.writeLong(qd()-126,arg(0)));
		l.register(null,"GetPortBounds",(r,c)->{memory.copy(arg(0)+16,arg(1),8);ret(arg(1));});
		l.register(null,"SetPortBounds",(r,c)->memory.copy(arg(1),arg(0)+16,8));
		l.register(null,"GetPortVisibleRegion",(r,c)->{rt.toolbox("CopyRgn",memory.readLong(arg(0)+24),arg(1));ret(arg(1));});
		l.register(null,"SetPortVisibleRegion",(r,c)->rt.toolbox("CopyRgn",arg(1),memory.readLong(arg(0)+24)));
		l.register(null,"GetPortClipRegion",(r,c)->{rt.toolbox("CopyRgn",memory.readLong(arg(0)+28),arg(1));ret(arg(1));});
		l.register(null,"SetPortClipRegion",(r,c)->rt.toolbox("CopyRgn",arg(1),memory.readLong(arg(0)+28)));
		l.register(null,"GetPortForeColor",(r,c)->{memory.copy(arg(0)+36,arg(1),6);ret(arg(1));});
		l.register(null,"GetPortBackColor",(r,c)->{memory.copy(arg(0)+42,arg(1),6);ret(arg(1));});
		l.register(null,"GetPortPenLocation",(r,c)->{memory.copy(arg(0)+48,arg(1),4);ret(arg(1));});
		l.register(null,"GetPortPenSize",(r,c)->{memory.copy(arg(0)+52,arg(1),4);ret(arg(1));});
		l.register(null,"GetPortPenMode",(r,c)->ret(memory.readWord(arg(0)+56)));
		l.register(null,"GetPortPenVisibility",(r,c)->ret((short)memory.readWord(arg(0)+66)));
		l.register(null,"GetPortTextFont",(r,c)->ret((short)memory.readWord(arg(0)+68)));
		l.register(null,"GetPortTextFace",(r,c)->ret(memory.readByte(arg(0)+70)));
		l.register(null,"GetPortTextMode",(r,c)->ret((short)memory.readWord(arg(0)+72)));
		l.register(null,"GetPortTextSize",(r,c)->ret((short)memory.readWord(arg(0)+74)));
		l.register(null,"GetPortGrafProcs",(r,c)->ret(memory.readLong(arg(0)+104)));
		l.register(null,"SetPortGrafProcs",(r,c)->memory.writeLong(arg(0)+104,arg(1)));
		l.register(null,"GetPortPixMap",(r,c)->ret(memory.readLong(arg(0)+2)));
		l.register(null,"GetPortBitMapForCopyBits",(r,c)->ret(arg(0)+2));
		l.register(null,"IsPortColor",(r,c)->ret((memory.readWord(arg(0)+6)&0xc000)!=0?1:0));
		l.register(null,"QDIsPortBuffered",(r,c)->ret(0));
		l.register(null,"QDIsPortBufferDirty",(r,c)->ret(0));
		l.register(null,"QDFlushPortBuffer",(r,c)->{});
		l.register(null,"LockPortBits",(r,c)->ret(0));
		l.register(null,"UnlockPortBits",(r,c)->ret(0));
		l.register(null,"GetRegionBounds",(r,c)->{memory.copy(memory.readLong(arg(0))+2,arg(1),8);ret(arg(1));});
		l.register(null,"IsRegionRectangular",(r,c)->ret(memory.readWord(memory.readLong(arg(0)))==10?1:0));
		l.register(null,"CreateNewPort",(r,c)->{int p=rt.allocate(108,4);rt.toolbox("OpenCPort",p);ret(p);});
		l.register(null,"DisposePort",(r,c)->rt.toolbox("CloseCPort",arg(0)));
		// ---- windows
		// Carbon-only calls REALbasic's Carbon runtime (STRuntime) makes at start-up without checking its weak imports
		l.register(null,"MoreMasterPointers",(r,c)->{});
		// Carbon's resource chain calls (Acrobat Reader 5.0 places its plug-in and locale files with them at start-up)
		l.register(null,"InsertResourceFile",(r,c)->ret(rt.toolbox.resources.insertResourceFile((short)arg(0),arg(1))));
		l.register(null,"DetachResourceFile",(r,c)->ret(rt.toolbox.resources.detachResourceFile((short)arg(0))));
		// CarbonLib 1.3 walks of the chain, top down (Airburst's FreeRegLib calls them at start-up without checking the weak
		// imports): the next file after the last one is 0 with resFNotFound, so both loop styles end
		l.register(null,"GetTopResourceFile",(r,c)->{java.util.List<Integer> l2=rt.toolbox.resources.chainFromTop();
			if(arg(0)!=0)memory.writeWord(arg(0),l2.isEmpty()?0:l2.get(0));ret(l2.isEmpty()?-193:0);});
		l.register(null,"GetNextResourceFile",(r,c)->{java.util.List<Integer> l2=rt.toolbox.resources.chainFromTop();int i=l2.indexOf((int)(short)arg(0));
			int next=i>=0&&i+1<l2.size()?l2.get(i+1):0;if(arg(1)!=0)memory.writeWord(arg(1),next);ret(next==0?-193:0);});
		l.register(null,"FSpOpenOrphanResFile",(r,c)->{int spec=arg(0);java.io.File f;
			try{f=rt.toolbox.files.fileFor((short)memory.readWord(spec),memory.readLong(spec+2),rt.toolbox.readPascal(spec+6));}catch(java.io.IOException e){f=null;}
			int ref=rt.toolbox.resources.openOrphan(f);if(arg(2)!=0)memory.writeWord(arg(2),ref);ret(ref<0?(short)memory.readWord(0xa60):0);}); // ResErr
		l.register(null,"RegisterControlDefinition",(r,c)->{ // (CDEF resource ID, ControlDefSpec*, conversion UPP): a kControlDefProcPtr spec runs like a CDEF of that ID
			int spec=arg(1);if(spec==0){rt.toolbox.controls.registerDefinition((short)arg(0),0);ret(0);return;}
			if(memory.readLong(spec)!=0){ret(-50);return;} // paramErr for kControlDefObjectClass: HIToolbox objects are not here
			rt.toolbox.controls.registerDefinition((short)arg(0),definitionHandle(memory.readLong(spec+4)));ret(0);});
		l.register(null,"RegisterWindowDefinition",(r,c)->{ // (WDEF resource ID, WindowDefSpec*): a kWindowDefProcPtr spec runs like a WDEF of that ID
			int spec=arg(1);if(spec==0){rt.toolbox.windows.registerDefinition((short)arg(0),0);ret(0);return;}
			if(memory.readLong(spec)!=0){ret(-50);return;}
			rt.toolbox.windows.registerDefinition((short)arg(0),definitionHandle(memory.readLong(spec+4)));ret(0);});
		l.register(null,"GetWindowPortBounds",(r,c)->{memory.copy(arg(0)+16,arg(1),8);ret(arg(1));});
		l.register(null,"GetGlobalMouse",(r,c)->memory.writeLong(arg(0),memory.readLong(0x830))); // Mouse: the global pointer
		l.register(null,"SetWindowContentColor",(r,c)->{int rgb=arg(1);rt.toolbox.windows.setContentColor(arg(0),(memory.readWord(rgb)>>>8)<<16|(memory.readWord(rgb+2)>>>8)<<8|memory.readWord(rgb+4)>>>8);ret(0);});
		l.register(null,"GetWindowContentColor",(r,c)->{memory.copy(arg(0)+42,arg(1),6);ret(0);}); // the port's background, which SetWindowContentColor sets
		// HideMenuBar: MBarHeight 0, as full-screen games of the time hid it (the host's overlaid bars step aside, see MacAppHost.updateOverlayBars)
		l.register(null,"HideMenuBar",(r,c)->memory.writeWord(0xbaa,0));
		l.register(null,"ShowMenuBar",(r,c)->{if(memory.readWord(0xbaa)==0){memory.writeWord(0xbaa,MacAppHost.MENU_BAR_HEIGHT);rt.toolbox("DrawMenuBar");}});
		l.register(null,"IsMenuBarVisible",(r,c)->ret(memory.readWord(0xbaa)!=0?1:0));
		l.register(null,"GetPortHiliteColor",(r,c)->{memory.copy(0xda0,arg(1),6);ret(arg(1));}); // HiliteRGB
		l.register(null,"CheckEventQueueForUserCancel",(r,c)->ret(0));
		l.register(null,"QDGetDirtyRegion",(r,c)->{rt.toolbox("SetEmptyRgn",arg(1));ret(0);});
		l.register(null,"QDSetDirtyRegion",(r,c)->ret(0));
		l.register(null,"GetThemeDrawingState",(r,c)->{memory.writeLong(arg(0),rt.allocate(8,4));ret(0);}); // an opaque token: this Appearance keeps no state to restore
		l.register(null,"SetThemeDrawingState",(r,c)->ret(0));
		l.register(null,"DisposeThemeDrawingState",(r,c)->ret(0));
		l.register(null,"GetThemeMetric",(r,c)->{int v; // Appearance 1.1 metrics of the Platinum theme
			switch(arg(0)){case 0:v=16;break;case 1:v=11;break;case 2:case 3:v=12;break;case 4:v=3;break;case 5:case 6:v=1;break;case 7:v=4;break;default:v=0;}
			memory.writeLong(arg(1),v);ret(0);});
		l.register(null,"GetWindowPort",(r,c)->ret(arg(0)));
		l.register(null,"GetWindowFromPort",(r,c)->ret(arg(0)));
		l.register(null,"SetPortWindowPort",(r,c)->rt.toolbox("SetPort",arg(0)));
		l.register(null,"GetWindowKind",(r,c)->ret((short)memory.readWord(arg(0)+108)));
		l.register(null,"SetWindowKind",(r,c)->memory.writeWord(arg(0)+108,arg(1)));
		l.register(null,"IsWindowVisible",(r,c)->ret(memory.readByte(arg(0)+110)!=0?1:0));
		l.register(null,"IsWindowHilited",(r,c)->ret(memory.readByte(arg(0)+111)!=0?1:0));
		l.register(null,"GetWindowGoAwayFlag",(r,c)->ret(memory.readByte(arg(0)+112)!=0?1:0));
		l.register(null,"GetNextWindow",(r,c)->ret(memory.readLong(arg(0)+144)));
		l.register(null,"GetWindowList",(r,c)->ret(memory.readLong(0x9d6))); // WindowList
		l.register(null,"GetWindowPic",(r,c)->ret(memory.readLong(arg(0)+148)));
		l.register(null,"GetWindowStructureWidths",(r,c)->{memory.fill(arg(1),8,0);ret(0);});
		l.register(null,"GetWindowBounds",(r,c)->{ // (window, regionCode, Rect*): kWindowStructureRgn 32, kWindowContentRgn 33, else the global port rectangle
			int w=arg(0),code=arg(1)&0xffff,out=arg(2);
			int rgn=code==32?memory.readLong(w+114):code==33?memory.readLong(w+118):0;
			if(rgn!=0)memory.copy(memory.readLong(rgn)+2,out,8);else globalPortRect(w,out);
			ret(0);});
		l.register(null,"SetWindowBounds",(r,c)->{int w=arg(0),rect=arg(2); // the content (global port) rectangle; the structure's would differ by the frame
			changeBounds(w,new int[]{(short)memory.readWord(rect),(short)memory.readWord(rect+2),(short)memory.readWord(rect+4),(short)memory.readWord(rect+6)},0,0,1);ret(0);});
		// CarbonLib's window events from the Window Manager calls themselves: kEventWindowShown/Hidden once a window appears or
		// disappears, kEventWindowBoundsChanging (whose handler may change kEventParamCurrentBounds) and Changed around a move or
		// resize the program asks for. Acrobat 5 makes its views visible (AVWindowDidShow) and lays them out only from these.
		l.register(null,"ShowWindow",(r,c)->showHide(arg(0),true,"ShowWindow"));
		l.register(null,"HideWindow",(r,c)->showHide(arg(0),false,"HideWindow"));
		l.register(null,"ShowHide",(r,c)->showHide(arg(0),(arg(1)&0xff)!=0,"ShowHide"));
		l.register(null,"MoveWindow",(r,c)->{int w=arg(0);int[] b=portBounds(w);int h=(short)arg(1),v=(short)arg(2); // (window, h, v, front)
			changeBounds(w,new int[]{v,h,v+b[2]-b[0],h+b[3]-b[1]},1,arg(3)&0xff,0);});
		l.register(null,"SizeWindow",(r,c)->{int w=arg(0);int[] b=portBounds(w); // (window, width, height, update)
			changeBounds(w,new int[]{b[0],b[1],b[0]+(short)arg(2),b[1]+(short)arg(1)},2,0,arg(3)&0xff);});
		l.register(null,"GetWindowStandardState",(r,c)->{int d=zoomData(arg(0));if(d!=0)memory.copy(d+8,arg(1),8);ret(arg(1));});
		l.register(null,"SetWindowStandardState",(r,c)->{int d=zoomData(arg(0));if(d!=0)memory.copy(arg(1),d+8,8);});
		l.register(null,"GetWindowUserState",(r,c)->{int d=zoomData(arg(0));if(d!=0)memory.copy(d,arg(1),8);ret(arg(1));});
		l.register(null,"SetWindowUserState",(r,c)->{int d=zoomData(arg(0));if(d!=0)memory.copy(arg(1),d,8);});
		l.register(null,"InvalWindowRect",(r,c)->{inPort(arg(0),()->rt.toolbox("InvalRect",arg(1)));ret(0);});
		l.register(null,"InvalWindowRgn",(r,c)->{inPort(arg(0),()->rt.toolbox("InvalRgn",arg(1)));ret(0);});
		l.register(null,"ValidWindowRect",(r,c)->{inPort(arg(0),()->rt.toolbox("ValidRect",arg(1)));ret(0);});
		l.register(null,"ValidWindowRgn",(r,c)->{inPort(arg(0),()->rt.toolbox("ValidRgn",arg(1)));ret(0);});
		l.register(null,"SetWindowModified",(r,c)->ret(0));
		l.register(null,"IsWindowModified",(r,c)->ret(0));
		l.register(null,"SetWindowProxyCreatorAndType",(r,c)->ret(0));
		l.register(null,"SetWindowProxyFSSpec",(r,c)->ret(0));
		l.register(null,"SetWindowProxyIcon",(r,c)->ret(0));
		l.register(null,"RemoveWindowProxy",(r,c)->ret(0));
		l.register(null,"IsWindowPathSelectClick",(r,c)->ret(0));
		l.register(null,"WindowPathSelect",(r,c)->ret(-128)); // userCanceledErr
		l.register(null,"TrackWindowProxyDrag",(r,c)->ret(-5608)); // errUserWantsToDragWindow: drag the window itself
		l.register(null,"GetWindowAttributes",(r,c)->{int[] k=windowClasses.get(arg(0));memory.writeLong(arg(1),k==null?0:k[1]);ret(0);});
		l.register(null,"GetWindowClass",(r,c)->{memory.writeLong(arg(1),windowClass(arg(0)));ret(0);}); // what it was created as; a classic window by its definition
		l.register(null,"CreateNewWindow",(r,c)->{ // (windowClass, attributes, bounds, *outWindow): a classic window of the matching kind, invisible
			int cls=arg(0),attr=arg(1);
			boolean close=(attr&1)!=0,zoom=(attr&6)!=0,grow=(attr&16)!=0;
			int proc;
			switch(cls){
				case 1:case 3:proc=1;break;                 // alert, modal: dBoxProc
				case 2:case 4:case 11:case 15:proc=5;break; // movable alert/modal, sheet, sheet alert (Mac OS 9 shows sheets as movable modal dialogs): movableDBoxProc
				case 5:case 8:proc=1985+(close?8:0);break;  // floating, utility: floatProc (+ close box variant)
				case 13:case 14:proc=2;break;               // plain: plainDBox
				default:proc=zoom?(grow?8:12):(grow?0:4);   // document: zoomDocProc / zoomNoGrow / documentProc / noGrowDocProc
			}
			int title=r.allocate(2,2);
			int w=rt.toolbox("NewCWindow",0,arg(2),title,0,proc,-1,close?1:0,0);
			memory.free(title);if(w!=0)windowClasses.put(w,new int[]{cls,attr});
			if(w!=0&&(cls==5||cls==8))rt.toolbox.windows.setFloatingLayer(w); // floating, utility: the floating layer
			// alert and modal classes get the dialog background brush, as the Window Manager gives them (ADM, Acrobat 5's
			// dialog library, paints its items on that grey and leaves the rest of the window to the erase)
			if(w!=0&&(cls<=4||cls==11||cls==15))rt.toolbox("SetThemeWindowBackground",w,cls==1||cls==2||cls==15?3:1,0); // kThemeBrushAlert/DialogBackgroundActive
			if(arg(3)!=0)memory.writeLong(arg(3),w);ret(w==0?-108:0);});
		l.register(null,"CreateCustomWindow",(r,c)->{ // (WindowDefSpec*, windowClass, attributes, bounds, *outWindow): the definition runs like a WDEF
			int spec=arg(0),type=spec==0?-1:memory.readLong(spec),attr=arg(2);int proc;
			if(type==2)proc=(short)memory.readWord(spec+4);                                   // kWindowDefProcID
			else if(type==0||type==1){int upp=type==0?memory.readLong(spec+4):PpcCarbonToolbox.objectClassWindowDefinition(rt,memory.readLong(spec+4)); // kWindowDefObjectClass: the class's events behind a WDEF (PpcCarbonToolbox)
				if(upp==0){ret(-50);return;}Integer id=customWdefs.get(upp);if(id==null){id=nextCustomWdef++;customWdefs.put(upp,id);rt.toolbox.windows.registerDefinition(id,definitionHandle(upp));}proc=id<<4;} // kWindowDefProcPtr: one ID per definition proc
			else{ret(-50);return;}                                                             // no other kind of WindowDefSpec
			int title=r.allocate(2,2);
			int w=rt.toolbox("NewCWindow",0,arg(3),title,0,proc,-1,(attr&1)!=0?1:0,0);
			memory.free(title);if(w!=0)windowClasses.put(w,new int[]{arg(1),attr});
			if(w!=0&&(arg(1)==5||arg(1)==8))rt.toolbox.windows.setFloatingLayer(w);
			if(arg(4)!=0)memory.writeLong(arg(4),w);ret(w==0?-108:0);});
		l.register(null,"RepositionWindow",(r,c)->{ // (window, parent, method): centred (on the parent or the main screen), alert position a third down
			int w=arg(0),parent=arg(1),method=arg(2);
			int width=(short)memory.readWord(w+22)-(short)memory.readWord(w+18),height=(short)memory.readWord(w+20)-(short)memory.readWord(w+16);
			int bounds=r.allocate(8,4);
			if(parent!=0&&(method==2||method==5||method==8))globalPortRect(parent,bounds);else{memory.copy(qd()-122+6,bounds,8);memory.writeWord(bounds,(short)memory.readWord(bounds)+20);}
			int top=(short)memory.readWord(bounds),left=(short)memory.readWord(bounds+2),bottom=(short)memory.readWord(bounds+4),right=(short)memory.readWord(bounds+6);memory.free(bounds);
			int x=left+(right-left-width)/2;int y=method==4||method==5?top+(bottom-top-height)/3:top+(bottom-top-height)/2;
			rt.toolbox("MoveWindow",w,x,Math.max(top,y),0);ret(0);});
		l.register(null,"SetPortDialogPort",(r,c)->rt.toolbox("SetPort",arg(0)));
		l.register(null,"ChangeWindowAttributes",(r,c)->{int[] k=windowClasses.get(arg(0));if(k==null){k=new int[]{windowClass(arg(0)),0};windowClasses.put(arg(0),k);}k[1]=(k[1]|arg(1))&~arg(2);ret(0);}); // (window, setThese, clearThese): kept for GetWindowAttributes
		// every region code as WMGetWindowRegion answers it: kWindowUpdateRgn (34) used to come back as the whole structure, and
		// DragThing, which takes the pending update out of its clip before drawing a dock cell, then drew nothing at all
		l.register(null,"GetWindowRegion",(r,c)->{int w=arg(0),code=arg(1)&0xffff;int err=windowRegion(w,code,arg(2));
			if(err==-5600){rt.toolbox("CopyRgn",code==33?memory.readLong(w+118):memory.readLong(w+114),arg(2));err=0;} // not one of the program's windows: its own regions
			ret(err);});
		l.register(null,"InsertMenuItemText",(r,c)->{ // (menu, text, afterItem): the text as is, no metacharacters
			int blank=r.allocate(4,2);memory.writeByte(blank,1);memory.writeByte(blank+1,' ');
			rt.toolbox("InsertMenuItem",arg(0),blank,arg(2)&0xffff);memory.free(blank);
			rt.toolbox("SetMenuItemText",arg(0),(arg(2)&0xffff)+1,arg(1));ret(0);});
		l.register(null,"AppendMenuItemText",(r,c)->{
			int blank=r.allocate(4,2);memory.writeByte(blank,1);memory.writeByte(blank+1,' ');
			rt.toolbox("AppendMenu",arg(0),blank);memory.free(blank);
			rt.toolbox("SetMenuItemText",arg(0),rt.toolbox("CountMenuItems",arg(0)),arg(1));ret(0);});
		l.register(null,"ActivateWindow",(r,c)->{rt.toolbox("HiliteWindow",arg(0),arg(1)&0xff);ret(0);});
		// ---- dialogs
		l.register(null,"GetDialogWindow",(r,c)->ret(arg(0)));
		l.register(null,"GetDialogPort",(r,c)->ret(arg(0)));
		l.register(null,"GetDialogFromWindow",(r,c)->ret(arg(0)));
		l.register(null,"GetDialogTextEditHandle",(r,c)->ret(memory.readLong(arg(0)+160)));
		l.register(null,"GetDialogKeyboardFocusItem",(r,c)->ret((short)memory.readWord(arg(0)+164)+1));
		l.register(null,"GetDialogDefaultItem",(r,c)->ret((short)memory.readWord(arg(0)+168)));
		l.register(null,"GetDialogCancelItem",(r,c)->ret(0));
		// ---- controls
		// kEventControlHit comes from TrackControl and HandleControlClick themselves, under a program's own WaitNextEvent loop too
		// (REALbasic 5 push buttons: Barcode Automator's Agree acts on the kEventCommandProcess it leads to, nothing on TrackControl's result)
		l.register(null,"TrackControl",(r,c)->{int control=arg(0),part=(short)rt.toolbox("TrackControl",control,arg(1),arg(2));tracked(control,part,rt.toolbox.host().modifiers());ret(part);});
		l.register(null,"GetControlBounds",(r,c)->{memory.copy(memory.readLong(arg(0))+8,arg(1),8);ret(arg(1));});
		l.register(null,"SetControlBounds",(r,c)->memory.copy(arg(1),memory.readLong(arg(0))+8,8));
		l.register(null,"GetControlHilite",(r,c)->ret(memory.readByte(memory.readLong(arg(0))+17)));
		l.register(null,"GetControlOwner",(r,c)->ret(memory.readLong(memory.readLong(arg(0))+4)));
		l.register(null,"GetControlDataHandle",(r,c)->ret(memory.readLong(memory.readLong(arg(0))+28)));
		l.register(null,"GetControlPopupMenuHandle",(r,c)->{int data=memory.readLong(memory.readLong(arg(0))+28);ret(data==0?0:memory.readLong(memory.readLong(data)));}); // popupPrivateData.mHandle
		l.register(null,"GetControlPopupMenuID",(r,c)->{int data=memory.readLong(memory.readLong(arg(0))+28);ret(data==0?0:(short)memory.readWord(memory.readLong(data)+4));});
		l.register(null,"GetControlRegion",(r,c)->{int b=memory.readLong(arg(0))+8;rt.toolbox("RectRgn",arg(2),b);ret(0);});
		// ---- menus
		l.register(null,"GetMenuID",(r,c)->ret((short)memory.readWord(memory.readLong(arg(0)))));
		l.register(null,"SetMenuID",(r,c)->memory.writeWord(memory.readLong(arg(0)),arg(1)));
		l.register(null,"GetMenuWidth",(r,c)->ret((short)memory.readWord(memory.readLong(arg(0))+2)));
		l.register(null,"GetMenuHeight",(r,c)->ret((short)memory.readWord(memory.readLong(arg(0))+4)));
		l.register(null,"GetMenuTitle",(r,c)->{int p=memory.readLong(arg(0))+14;int n=memory.readByte(p);memory.copy(p,arg(1),n+1);ret(arg(1));});
		l.register(null,"SetMenuTitle",(r,c)->{rt.toolbox.menus.setTitle(arg(0),rt.toolbox.readPascal(arg(1)));ret(0);});
		l.register(null,"SetMenuTitleWithCFString",(r,c)->{String t=rt.libraries.textServices.cfText(arg(1));if(t!=null)rt.toolbox.menus.setTitle(arg(0),t);ret(0);});
		// ---- the CFString forms of titles and item texts (Acrobat Reader 5.0 builds its menus and windows with them)
		l.register(null,"InsertMenuItemTextWithCFString",(r,c)->{ // (menu, text, afterItem, attributes, commandID)
			int blank=r.allocate(4,2);memory.writeByte(blank,1);memory.writeByte(blank+1,' ');
			int after=Math.max(0,Math.min(arg(2)&0xffff,rt.toolbox("CountMenuItems",arg(0))));
			rt.toolbox("InsertMenuItem",arg(0),blank,after);memory.free(blank);
			setItemText(arg(0),after+1,arg(1));if(arg(4)!=0)commandIds.put(key(arg(0),after+1),arg(4));ret(0);});
		l.register(null,"AppendMenuItemTextWithCFString",(r,c)->{ // (menu, text, attributes, commandID, *outNewItem)
			int blank=r.allocate(4,2);memory.writeByte(blank,1);memory.writeByte(blank+1,' ');
			rt.toolbox("AppendMenu",arg(0),blank);memory.free(blank);int item=rt.toolbox("CountMenuItems",arg(0));
			setItemText(arg(0),item,arg(1));if(arg(3)!=0)commandIds.put(key(arg(0),item),arg(3));if(arg(4)!=0)memory.writeWord(arg(4),item);ret(0);});
		l.register(null,"SetMenuItemTextWithCFString",(r,c)->{setItemText(arg(0),arg(1)&0xffff,arg(2));ret(0);});
		l.register(null,"CopyMenuItemTextAsCFString",(r,c)->{int p=r.allocate(256,2);rt.toolbox("GetMenuItemText",arg(0),arg(1)&0xffff,p);copyAsCFString(p,arg(2));memory.free(p);ret(0);});
		l.register(null,"CopyMenuTitleAsCFString",(r,c)->{copyAsCFString(memory.readLong(arg(0))+14,arg(1));ret(0);});
		l.register(null,"SetWindowTitleWithCFString",(r,c)->{setUnicodeTitle(arg(0),rt.libraries.textServices.cfText(arg(1)));ret(0);}); // the Unicode text is kept too: CopyWindowTitleAsCFString gives back what the Pascal title cannot hold
		l.register(null,"CopyWindowTitleAsCFString",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),rt.libraries.textServices.newCFString(titleText(arg(0))));ret(0);});
		l.register(null,"SetControlTitleWithCFString",(r,c)->{int p=pascal(rt.libraries.textServices.cfText(arg(1)));rt.toolbox("SetControlTitle",arg(0),p);memory.free(p);ret(0);});
		l.register(null,"CopyControlTitleAsCFString",(r,c)->{int p=r.allocate(256,2);rt.toolbox("GetControlTitle",arg(0),p);copyAsCFString(p,arg(1));memory.free(p);ret(0);});
		l.register(null,"DeleteMenuItems",(r,c)->{for(int i=0;i<(arg(2)&0xffff);i++)rt.toolbox("DeleteMenuItem",arg(0),arg(1)&0xffff);ret(0);}); // (menu, firstItem, numItems)
		l.register(null,"DisableAllMenuItems",(r,c)->{int p=memory.readLong(arg(0));memory.writeLong(p+10,memory.readLong(p+10)&1);});
		l.register(null,"EnableAllMenuItems",(r,c)->{int p=memory.readLong(arg(0));memory.writeLong(p+10,-1);});
		l.register(null,"MenuHasEnabledItems",(r,c)->{int p=memory.readLong(arg(0));ret((memory.readLong(p+10)&~1)!=0?1:0);});
		l.register(null,"ChangeMenuAttributes",(r,c)->ret(0));
		// ---- window and control validity, classes, properties and IDs
		l.register(null,"IsValidWindowPtr",(r,c)->{int w=arg(0);boolean ok=false;for(int p=memory.readLong(0x9d6),n=0;p!=0&&n<1000;p=memory.readLong(p+144),n++)if(p==w){ok=true;break;}ret(ok?1:0);}); // WindowList
		l.register(null,"IsValidControlHandle",(r,c)->{int h=arg(0);ret(h!=0&&(h&1)==0&&memory.readLong(h)!=0?1:0);});
		// (class, mustBeVisible) / (inWindow, class, mustBeVisible): kAllWindowClasses (-1) or the class the window was made as.
		// Acrobat 5 asks for an alert (class 1) before it quits; any window used to answer, so it never quit.
		l.register(null,"GetFrontWindowOfClass",(r,c)->ret(nextWindow(memory.readLong(0x9d6),arg(0),arg(1)&0xff)));
		l.register(null,"GetNextWindowOfClass",(r,c)->ret(arg(0)==0?0:nextWindow(memory.readLong(arg(0)+144),arg(1),arg(2)&0xff)));
		l.register(null,"SetWindowProperty",(r,c)->{properties.put(propertyKey(arg(0),arg(1),arg(2)),memory.readBytes(arg(4),arg(3)));ret(0);}); // (window, creator, tag, size, data)
		l.register(null,"GetWindowProperty",(r,c)->ret(getProperty(propertyKey(arg(0),arg(1),arg(2)),arg(3),arg(4),arg(5)))); // (window, creator, tag, bufferSize, *actualSize, buffer)
		l.register(null,"GetWindowPropertySize",(r,c)->{byte[] v=properties.get(propertyKey(arg(0),arg(1),arg(2)));if(v==null){ret(-5604);return;}if(arg(3)!=0)memory.writeLong(arg(3),v.length);ret(0);});
		l.register(null,"RemoveWindowProperty",(r,c)->ret(properties.remove(propertyKey(arg(0),arg(1),arg(2)))==null?-5604:0));
		l.register(null,"SetControlProperty",(r,c)->{properties.put(propertyKey(arg(0),arg(1),arg(2)),memory.readBytes(arg(4),arg(3)));ret(0);});
		l.register(null,"GetControlProperty",(r,c)->ret(getProperty(propertyKey(arg(0),arg(1),arg(2)),arg(3),arg(4),arg(5))));
		l.register(null,"GetControlPropertySize",(r,c)->{byte[] v=properties.get(propertyKey(arg(0),arg(1),arg(2)));if(v==null){ret(-5604);return;}if(arg(3)!=0)memory.writeLong(arg(3),v.length);ret(0);});
		l.register(null,"RemoveControlProperty",(r,c)->ret(properties.remove(propertyKey(arg(0),arg(1),arg(2)))==null?-5604:0));
		l.register(null,"SetControlID",(r,c)->{controlIds.put(arg(0),memory.readBytes(arg(1),8));ret(0);}); // ControlID {signature, id}
		l.register(null,"GetControlID",(r,c)->{byte[] v=controlIds.get(arg(0));if(v==null)v=new byte[8];memory.load(arg(1),v);ret(0);});
		l.register(null,"GetControlByID",(r,c)->{ // (window, ControlID*, *outControl): a control of that window with the ID
			byte[] want=memory.readBytes(arg(1),8);int found=0;
			for(Map.Entry<Integer,byte[]> e:controlIds.entrySet())if(java.util.Arrays.equals(e.getValue(),want)){int h=e.getKey();int p=h==0?0:memory.readLong(h);if(p!=0&&memory.readLong(p+4)==arg(0)){found=h;break;}}
			if(arg(2)!=0)memory.writeLong(arg(2),found);ret(found==0?-30584:0);}); // errUnknownControl
		l.register(null,"GetCurrentKeyModifiers",(r,c)->{int keys=r.allocate(16,2);rt.toolbox("GetKeys",keys);int b6=memory.readByte(keys+6),b7=memory.readByte(keys+7);memory.free(keys);
			// KeyMap bytes are little-endian bit order per byte: key $37 (command) = byte 6 bit 7, $38-$3B (shift, caps lock, option, control) = byte 7 bits 0-3
			ret(((b6>>>7)&1)<<8|(b7&1)<<9|((b7>>>1)&1)<<10|((b7>>>2)&1)<<11|((b7>>>3)&1)<<12);});
		// ---- Appearance Manager calls AppearanceLib served without a trap (Platinum; the Japanese system font is Osaka)
		l.register(null,"GetThemeFont",(r,c)->{ // (fontID, script, name, *size, *style)
			String[] f=themeFont(arg(0)&0xffff);int p=pascal(f[0]);memory.copy(p,arg(2),memory.readByte(p)+1);memory.free(p);
			if(arg(3)!=0)memory.writeWord(arg(3),Integer.parseInt(f[1]));if(arg(4)!=0)memory.writeByte(arg(4),Integer.parseInt(f[2]));ret(0);});
		l.register(null,"GetThemeTextColor",(r,c)->{writeRgb(arg(3),AppearanceManager68k.textColor((short)arg(0)));ret(0);}); // (color, depth, isColor, *RGBColor)
		l.register(null,"GetThemeBrushAsColor",(r,c)->{writeRgb(arg(3),AppearanceManager68k.brush((short)arg(0)));ret(0);});
		l.register(null,"GetThemeStandaloneGrowBoxBounds",(r,c)->{int v=arg(0)>>16,h=(short)arg(0),n=(arg(2)&0xff)!=0?12:15,rect=arg(3); // (origin, growDirection, isSmall, *bounds)
			memory.writeWord(rect,v-n);memory.writeWord(rect+2,h-n);memory.writeWord(rect+4,v);memory.writeWord(rect+6,h);ret(0);});
		l.register(null,"DrawThemeStandaloneGrowBox",(r,c)->ret(0));
		l.register(null,"DrawThemeTextBox",(r,c)->{ // (CFString, fontID, state, wrapToWidth, bounds, just, context)
			String t=rt.libraries.textServices.cfText(arg(0));if(t==null){ret(-50);return;}
			byte[] b=rt.libraries.textServices.systemBytes(t);
			int saved=r.allocate(8,2);saveFont(saved);applyThemeFont(arg(1)&0xffff);
			Toolbox68k tb=rt.toolbox;String translation=tb.translator()==null?null:tb.translated(VMTranslation.Kind.TEXT,tb.decode(b));
			tb.textEdit.textBox(b,arg(4),(short)arg(5),translation,false,(arg(3)&0xff)!=0); // over the background, as Appearance draws theme text
			restoreFont(saved);memory.free(saved);ret(0);});
		l.register(null,"GetThemeTextDimensions",(r,c)->{ // (CFString, fontID, state, wrapToWidth, *ioBounds (Point: width limit in), *baseline)
			String t=rt.libraries.textServices.cfText(arg(0));if(t==null)t="";
			byte[] b=rt.libraries.textServices.systemBytes(t);int text=r.allocate(b.length+2,2);memory.load(text,b);
			int saved=r.allocate(8,2);saveFont(saved);applyThemeFont(arg(1)&0xffff);
			int info=r.allocate(8,2);rt.toolbox("GetFontInfo",info);int ascent=(short)memory.readWord(info),descent=(short)memory.readWord(info+2),leading=(short)memory.readWord(info+6);memory.free(info);
			int lineHeight=ascent+descent+leading,width=0,lines=1,limit=arg(4)==0?0:(short)memory.readWord(arg(4)+2),lineWidth=0;
			int start=0;for(int i=0;i<=b.length;i++)if(i==b.length||b[i]==13){int w=b.length==0?0:rt.toolbox("TextWidth",text,start,i-start);
				if((arg(3)&0xff)!=0&&limit>0&&w>limit){lines+=w/limit;w=limit;}width=Math.max(width,w);if(i<b.length)lines++;start=i+1;}
			restoreFont(saved);memory.free(saved);memory.free(text);
			if(arg(4)!=0){memory.writeWord(arg(4),lines*lineHeight);memory.writeWord(arg(4)+2,width);}if(arg(5)!=0)memory.writeWord(arg(5),-descent);ret(0);});
		l.register(null,"TruncateThemeText",(r,c)->{ // (CFMutableString, fontID, state, pixelWidthLimit, truncWhere, *truncated)
			String t=rt.libraries.textServices.cfText(arg(0));if(t==null){ret(-50);return;}
			int saved=r.allocate(8,2);saveFont(saved);applyThemeFont(arg(1)&0xffff);
			int limit=(short)arg(3);boolean end=(short)arg(4)==0;String out=t;boolean cut=false;
			while(themeWidth(out)>limit&&out.length()>1){cut=true;String core=out.endsWith("\u2026")||out.startsWith("\u2026")?(end?out.substring(0,out.length()-1):out.substring(1)):out;
				if(core.length()<=1){out="\u2026";break;}out=end?core.substring(0,core.length()-1)+"\u2026":core.substring(0,core.length()/2)+"\u2026"+core.substring(core.length()/2+1);}
			restoreFont(saved);memory.free(saved);
			if(cut)rt.libraries.textServices.setCFText(arg(0),out);if(arg(5)!=0)memory.writeByte(arg(5),cut?1:0);ret(0);});
		l.register(null,"GetPixDepth",(r,c)->{int h=arg(0);int p=h==0?0:memory.readLong(h);ret(p==0?0:(short)memory.readWord(p+32));});
		// ---- FSRefs (HFS Plus API): an FSRef here is 'hzFR' + an index into the host files this program has named; paths
		// are the host's POSIX paths in UTF-8, as Mac OS X gives them (MacBeat builds its Scheme folder path with them)
		l.register(null,"FSPathMakeRef",(r,c)->{ // (path, *ref, *isDirectory)
			int p=arg(0);if(p==0){ret(-50);return;}int n=0;while(n<4096&&memory.readByte(p+n)!=0)n++;
			java.io.File f=new java.io.File(new String(memory.readBytes(p,n),java.nio.charset.StandardCharsets.UTF_8));
			if(!f.isAbsolute()||!f.exists()||!Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name)){ret(-43);return;} // fnfErr
			writeFSRef(arg(1),f);if(arg(2)!=0)memory.writeByte(arg(2),f.isDirectory()?1:0);ret(0);});
		l.register(null,"FSRefMakePath",(r,c)->{ // (ref, path, maxPathSize)
			java.io.File f=fsRefFile(arg(0));int out=arg(1),max=arg(2);
			if(f==null){if(out!=0&&max>0)memory.writeByte(out,0);ret(-50);return;} // an empty string too: MacBeat appends to it after a failed lookup
			byte[] b=f.getPath().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(b.length+1>max){if(out!=0&&max>0)memory.writeByte(out,0);ret(-2110);return;} // buffersTooSmall
			memory.load(out,b);memory.writeByte(out+b.length,0);ret(0);});
		// FSpMakeFSRef, FSMakeFSRefUnicode, FSCompareFSRefs, FSGetCatalogInfo and the rest of the HFS Plus API: the File Manager ($AA52) through the bridge
		// resource files and aliases named by FSRefs (Resources.h, Aliases.h): through the FSSpec calls
		l.register(null,"FSOpenResFile",(r,c)->{java.io.File f=fsRefFile(arg(0));if(f==null){memory.writeWord(0xa60,-50);ret(-1);return;}ret((short)rt.toolbox.resources.openFile(f,arg(1)&0xff));}); // (ref, permission): refNum or -1 with ResErr
		l.register(null,"FSOpenResourceFile",(r,c)->{ // (ref, forkNameLength, forkName, permissions, *refNum): the resource fork (a data-fork resource file is not supported)
			java.io.File f=fsRefFile(arg(0));if(f==null){ret(-50);return;}int ref=(short)rt.toolbox.resources.openFile(f,arg(3)&0xff);if(arg(4)!=0)memory.writeWord(arg(4),ref);ret(ref<0?(short)memory.readWord(0xa60):0);});
		l.register(null,"FSCreateResFile",(r,c)->ret(createResourceFile(arg(0),arg(1),arg(2),arg(5),arg(6))));                  // (parentRef, nameLength, name, whichInfo, catalogInfo, *newRef, *newSpec)
		l.register(null,"FSCreateResourceFile",(r,c)->ret(createResourceFile(arg(0),arg(1),arg(2),arg(7),arg(8))));             // (..., forkNameLength, forkName, *newRef, *newSpec)
		l.register(null,"FSIsAliasFile",(r,c)->{int spec=specOf(arg(0));if(spec==0){ret(-43);return;}try{ret(rt.toolbox("IsAliasFile",spec,arg(1),arg(2)));}finally{memory.free(spec);}});
		l.register(null,"FSResolveAliasFileWithMountFlags",(r,c)->{ // (*ref in/out, resolveChains, *targetIsFolder, *wasAliased, mountFlags)
			int spec=specOf(arg(0));if(spec==0){ret(-43);return;}try{int err=rt.toolbox("ResolveAliasFileWithMountFlags",spec,arg(1)&0xff,arg(2),arg(3),arg(4));if(err==0)refFromSpec(spec,arg(0));ret(err);}finally{memory.free(spec);}});
		l.register(null,"FSResolveAliasFile",(r,c)->{int spec=specOf(arg(0));if(spec==0){ret(-43);return;}try{int err=rt.toolbox("ResolveAliasFile",spec,arg(1)&0xff,arg(2),arg(3));if(err==0)refFromSpec(spec,arg(0));ret(err);}finally{memory.free(spec);}});
		l.register(null,"FSNewAlias",(r,c)->{int from=arg(0)==0?0:specOf(arg(0)),to=specOf(arg(1));if(to==0){ret(-43);return;}try{ret(rt.toolbox("NewAlias",from,to,arg(2)));}finally{memory.free(to);if(from!=0)memory.free(from);}});
		l.register(null,"FSNewAliasMinimal",(r,c)->{int to=specOf(arg(0));if(to==0){ret(-43);return;}try{ret(rt.toolbox("NewAliasMinimal",to,arg(1)));}finally{memory.free(to);}});
		l.register(null,"FSResolveAlias",(r,c)->{int from=arg(0)==0?0:specOf(arg(0)),target=r.allocate(70,2);try{int err=rt.toolbox("ResolveAlias",from,arg(1),target,arg(3));if(err==0)refFromSpec(target,arg(2));ret(err);}finally{memory.free(target);if(from!=0)memory.free(from);}});
		l.register(null,"FSResolveAliasWithMountFlags",(r,c)->{int from=arg(0)==0?0:specOf(arg(0)),target=r.allocate(70,2);try{int err=rt.toolbox("ResolveAliasWithMountFlags",from,arg(1),target,arg(3),arg(4));if(err==0)refFromSpec(target,arg(2));ret(err);}finally{memory.free(target);if(from!=0)memory.free(from);}});
		l.register(null,"CreateNewMenu",(r,c)->{ // (menuID, attributes, *outMenu): an empty, untitled menu
			int title=r.allocate(2,2);int m=rt.toolbox("NewMenu",arg(0)&0xffff,title);memory.free(title);if(m!=0&&arg(1)!=0)carbon("ChangeMenuAttributes",m,arg(1),0);
			if(arg(2)!=0)memory.writeLong(arg(2),m);ret(m==0?-108:0);});
		l.register(null,"IsValidMenu",(r,c)->{int m=arg(0);ret(m!=0&&(m&1)==0&&memory.readLong(m)!=0?1:0);});
		l.register(null,"SetUserFocusWindow",(r,c)->{userFocus=arg(0)==-1?0:arg(0);ret(0);}); // kUserFocusAuto (-1): the front window
		l.register(null,"GetUserFocusWindow",(r,c)->ret(focusWindow()));
		// CarbonLib's WaitNextEvent hands a key typed into a focused MLTE object to it through the Text Services Manager
		// (kEventTextInputUnicodeForKeyEvent) and returns a null event: ADM (Acrobat 5's dialogs) never calls TXNKeyDown.
		// Command keys and the keys a dialog acts on (Return, Enter, Tab, Escape) still come back.
		// Event loop timers fire while a Carbon program waits in WaitNextEvent, as CarbonLib runs the event loop there: Acrobat 5
		// installs its idle timer around each WaitNextEvent and draws thumbnails and other views in pieces from it.
		l.register(null,"WaitNextEvent",(r,c)->ret(waitNextEvent(arg(0),arg(1),arg(2),arg(3))?1:0));
		l.register(null,"GetNextEvent",(r,c)->{int e=arg(1);boolean got=(rt.toolbox("GetNextEvent",arg(0),e)&0xff)!=0;if(got&&textInput(e)){memory.writeWord(e,0);got=false;}ret(got?1:0);});
		l.register(null,"FrontNonFloatingWindow",(r,c)->ret(rt.toolbox.windows.frontNonFloating()));
		l.register(null,"EnableMenuItem",(r,c)->rt.toolbox("EnableItem",arg(0),arg(1)&0xffff));
		l.register(null,"DisableMenuItem",(r,c)->rt.toolbox("DisableItem",arg(0),arg(1)&0xffff));
		l.register(null,"IsMenuItemEnabled",(r,c)->{int flags=memory.readLong(memory.readLong(arg(0))+10);int item=arg(1)&0xffff;ret((flags&1)!=0&&(item>31||(flags&(1<<item))!=0)?1:0);});
		l.register(null,"EnableMenuCommand",(r,c)->{int[] m=commandItem(arg(0),arg(1));if(m!=null)rt.toolbox("EnableItem",m[0],m[1]);});
		l.register(null,"DisableMenuCommand",(r,c)->{int[] m=commandItem(arg(0),arg(1));if(m!=null)rt.toolbox("DisableItem",m[0],m[1]);});
		l.register(null,"SetMenuFont",(r,c)->ret(0));
		l.register(null,"GetItemStyle",(r,c)->{int style=menuItemField(arg(0),arg(1)&0xffff,3);memory.writeByte(arg(2),style);});
		l.register(null,"SetMenuItemCommandKey",(r,c)->{ // (menu, item, isVirtualKey, key)
			if((arg(2)&0xff)==0)rt.toolbox("SetItemCmd",arg(0),arg(1)&0xffff,arg(3)&0xff);ret(0);});
		l.register(null,"SetMenuItemCommandID",(r,c)->{commandIds.put(key(arg(0),arg(1)&0xffff),arg(2));ret(0);});
		l.register(null,"GetMenuItemCommandID",(r,c)->{Integer v=commandIds.get(key(arg(0),arg(1)&0xffff));memory.writeLong(arg(2),v==null?0:v);ret(0);});
		l.register(null,"GetIndMenuItemWithCommandID",(r,c)->{ // (menu, commandID, itemIndex, *outMenu, *outIndex)
			int[] m=commandItem(arg(0),arg(1));
			if(m==null){ret(-5620);return;} // errMenuItemNotFound... (menuItemNotFoundErr)
			if(arg(3)!=0)memory.writeLong(arg(3),m[0]);if(arg(4)!=0)memory.writeWord(arg(4),m[1]);ret(0);});
		l.register(null,"DisposeMenuBar",(r,c)->{if(arg(0)!=0)rt.trap(0xa023,0,arg(0),0);});
		// The Help menu is created on demand (CarbonLib does too). It has no system items here, so the program's own items start
		// at 1. An error made ADM (Acrobat 5) throw out of every modal dialog - it disables the Help items while one is up.
		l.register(null,"HMGetHelpMenu",(r,c)->{int m=rt.toolbox.menus.helpMenuHandle();if(arg(0)!=0)memory.writeLong(arg(0),m);if(arg(1)!=0)memory.writeWord(arg(1),1);ret(0);});
		l.register(null,"HMGetHelpMenuHandle",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),rt.toolbox.menus.helpMenuHandle());ret(0);});
		// ---- Apple events, strings, files, Internet Config
		l.register(null,"AEGetDescDataSize",(r,c)->{int h=memory.readLong(arg(0)+4);ret(h==0?0:rt.toolbox("GetHandleSize",h));});
		l.register(null,"AEGetDescData",(r,c)->{int h=memory.readLong(arg(0)+4);if(h==0){ret(0);return;}int n=Math.min(arg(2),rt.toolbox("GetHandleSize",h));memory.copy(memory.readLong(h),arg(1),n);ret(0);});
		l.register(null,"AEReplaceDescData",(r,c)->{int e=rt.toolbox("AEDisposeDesc",arg(3));ret(rt.toolbox("AECreateDesc",arg(0),arg(1),arg(2),arg(3)));});
		l.register(null,"PLstrcpy",(r,c)->{int n=memory.readByte(arg(1));memory.copy(arg(1),arg(0),n+1);ret(arg(0));});
		l.register(null,"PLstrcat",(r,c)->{int d=arg(0),s=arg(1);int a=memory.readByte(d),b=memory.readByte(s);int n=Math.min(255-a,b);memory.copy(s+1,d+1+a,n);memory.writeByte(d,a+n);ret(d);});
		l.register(null,"PLstrlen",(r,c)->ret(memory.readByte(arg(0))));
		l.register(null,"PLstrcmp",(r,c)->{byte[] a=r.pBytes(arg(0)),b=r.pBytes(arg(1));int res=0;for(int i=0;i<Math.min(a.length,b.length)&&res==0;i++)res=(a[i]&0xff)-(b[i]&0xff);if(res==0)res=a.length-b.length;ret(Integer.signum(res));});
		l.register(null,"PLstrncpy",(r,c)->{int n=Math.min(memory.readByte(arg(1)),arg(2));memory.copy(arg(1)+1,arg(0)+1,n);memory.writeByte(arg(0),n);ret(arg(0));});
		l.register(null,"CompareString",(r,c)->{int a=arg(0),b=arg(1);ret(rt.toolbox("IUMagString",a+1,b+1,memory.readByte(a),memory.readByte(b)));});
		l.register(null,"FSFindFolder",(r,c)->ret(rt.toolbox("FindFolder",arg(0),arg(1),arg(2),arg(3),arg(4))));
		// Internet Config: the preferences and file mappings of System Folder:Preferences:Internet Preferences (PpcInternetConfig),
		// built-in defaults without that file; URLs go to the host's browser. Acrobat Reader 5.0 told the user its Internet
		// features were unavailable when ICStart failed, and died under that alert.
		final PpcInternetConfig ic=new PpcInternetConfig(rt);
		l.register(null,"ICStart",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),rt.allocate(16,4));ret(0);});
		l.register(null,"ICStop",(r,c)->ret(0));
		for(String name:new String[]{"ICFindConfigFile","ICFindUserConfigFile","ICBegin","ICEnd","ICGeneralFindConfigFile","ICChooseConfig","ICChooseNewConfig"})l.register(null,name,(r,c)->ret(0));
		l.register(null,"ICGetPref",(r,c)->{ // (instance, key, *attr, buffer, *size)
			String key=icKey(arg(1));byte[][] v=ic.get(key);
			if(PpcRuntime.TRACE)System.err.println("[ppc]   IC key \""+key+"\""+(v==null?" (none)":" "+v[1].length+" bytes"));
			if(v==null){ret(PpcInternetConfig.NOT_FOUND);return;}
			if(arg(2)!=0)memory.load(arg(2),v[0]);int size=arg(4)==0?0:memory.readLong(arg(4));
			if(arg(3)!=0)memory.load(arg(3),java.util.Arrays.copyOf(v[1],Math.min(size,v[1].length)));if(arg(4)!=0)memory.writeLong(arg(4),v[1].length);
			ret(size<v[1].length&&arg(3)!=0?PpcInternetConfig.TRUNCATED:0);});
		l.register(null,"ICFindPrefHandle",(r,c)->{ // (instance, key, *attr, handle)
			String key=icKey(arg(1));byte[][] v=ic.get(key);
			if(PpcRuntime.TRACE)System.err.println("[ppc]   IC key \""+key+"\""+(v==null?" (none)":" "+v[1].length+" bytes"));
			if(v==null){ret(PpcInternetConfig.NOT_FOUND);return;}
			if(arg(2)!=0)memory.load(arg(2),v[0]);if(arg(3)!=0){rt.toolbox("SetHandleSize",arg(3),v[1].length);memory.load(memory.readLong(arg(3)),v[1]);}ret(0);});
		l.register(null,"ICGetPrefHandle",(r,c)->{ // (instance, key, *attr, *handle): a new Handle
			byte[][] v=ic.get(icKey(arg(1)));if(v==null){if(arg(3)!=0)memory.writeLong(arg(3),0);ret(PpcInternetConfig.NOT_FOUND);return;}
			if(arg(2)!=0)memory.load(arg(2),v[0]);if(arg(3)!=0)memory.writeLong(arg(3),rt.toolbox.api.createHandle(v[1]));ret(0);});
		l.register(null,"ICSetPref",(r,c)->ret(ic.set(icKey(arg(1)),arg(2),arg(3)==0||arg(4)<=0?new byte[0]:memory.readBytes(arg(3),arg(4))))); // (instance, key, attr, buffer, size)
		l.register(null,"ICSetPrefHandle",(r,c)->{byte[] v=arg(3)==0?null:rt.toolbox.api.handleContents(arg(3));ret(v==null?PpcInternetConfig.PARAM_ERR:ic.set(icKey(arg(1)),arg(2),v));}); // (instance, key, attr, handle)
		l.register(null,"ICDeletePref",(r,c)->ret(ic.delete(icKey(arg(1)))));
		l.register(null,"ICCountPref",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),ic.keys().size());ret(0);});
		l.register(null,"ICGetIndPref",(r,c)->{java.util.List<String> keys=ic.keys();int i=arg(1); // (instance, index from 1, key)
			if(i<1||i>keys.size()){ret(PpcInternetConfig.NOT_FOUND);return;}rt.toolbox.writePascal(arg(2),keys.get(i-1),255);ret(0);});
		l.register(null,"ICGetSeed",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),ic.seed());ret(0);});
		// file mappings: the "Mapping" preference, or an entries Handle the program got from it
		l.register(null,"ICMapFilename",(r,c)->ret(icMapped(ic,PpcInternetConfig.byFilename(icMappings(ic,0),icKey(arg(1))),arg(2)))); // (instance, filename, *entry)
		l.register(null,"ICMapTypeCreator",(r,c)->ret(icMapped(ic,PpcInternetConfig.byTypeCreator(icMappings(ic,0),arg(1),arg(2),arg(3)==0?"":icKey(arg(3))),arg(4)))); // (instance, type, creator, filename, *entry)
		l.register(null,"ICMapEntriesFilename",(r,c)->ret(icMapped(ic,PpcInternetConfig.byFilename(icMappings(ic,arg(1)),icKey(arg(2))),arg(3)))); // (instance, entries, filename, *entry)
		l.register(null,"ICMapEntriesTypeCreator",(r,c)->ret(icMapped(ic,PpcInternetConfig.byTypeCreator(icMappings(ic,arg(1)),arg(2),arg(3),arg(4)==0?"":icKey(arg(4))),arg(5)))); // (instance, entries, type, creator, filename, *entry)
		l.register(null,"ICCountMapEntries",(r,c)->{if(arg(2)!=0)memory.writeLong(arg(2),icMappings(ic,arg(1)).size());ret(0);}); // (instance, entries, *count)
		l.register(null,"ICGetIndMapEntry",(r,c)->{java.util.List<PpcInternetConfig.Entry> list=icMappings(ic,arg(1));int i=arg(2); // (instance, entries, index, *pos, *entry)
			if(i<1||i>list.size()){ret(PpcInternetConfig.NOT_FOUND);return;}PpcInternetConfig.Entry e=list.get(i-1);if(arg(3)!=0)memory.writeLong(arg(3),e.pos);ret(icMapped(ic,e,arg(4)));});
		l.register(null,"ICGetMapEntry",(r,c)->{PpcInternetConfig.Entry found=null;for(PpcInternetConfig.Entry e:icMappings(ic,arg(1)))if(e.pos==arg(2))found=e; // (instance, entries, pos, *entry)
			ret(found==null?PpcInternetConfig.PARAM_ERR:icMapped(ic,found,arg(3)));});
		l.register(null,"ICAddMapEntry",(r,c)->{int h=arg(1);if(h==0||arg(2)==0){ret(PpcInternetConfig.PARAM_ERR);return;} // (instance, entries, *entry): appended to the Handle
			byte[] add=ic.packEntry(arg(2));int n=rt.toolbox("GetHandleSize",h);rt.toolbox("SetHandleSize",h,n+add.length);memory.load(memory.readLong(h)+n,add);ret(0);});
		l.register(null,"ICSetMapEntry",(r,c)->{int h=arg(1);PpcInternetConfig.Entry at=icEntryAt(ic,h,arg(2));if(at==null||arg(3)==0){ret(PpcInternetConfig.PARAM_ERR);return;} // (instance, entries, pos, *entry)
			icReplace(h,at,ic.packEntry(arg(3)));ret(0);});
		l.register(null,"ICDeleteMapEntry",(r,c)->{int h=arg(1);PpcInternetConfig.Entry at=icEntryAt(ic,h,arg(2));if(at==null){ret(PpcInternetConfig.PARAM_ERR);return;} // (instance, entries, pos)
			icReplace(h,at,new byte[0]);ret(0);});
		l.register(null,"ICGetVersion",(r,c)->{if(arg(2)!=0)memory.writeLong(arg(2),0x02000000);ret(0);});
		l.register(null,"ICLaunchURL",(r,c)->{ // (instance, hint, data, length, *selStart, *selEnd): the text between the offsets
			int data=arg(2),len=arg(3),start=arg(4)==0?0:memory.readLong(arg(4)),end=arg(5)==0?len:memory.readLong(arg(5));
			if(data==0||start<0||end>len||start>=end){ret(-50);return;}
			String url=new String(memory.readBytes(data+start,end-start),java.nio.charset.StandardCharsets.ISO_8859_1).trim();
			if(url.startsWith("<")&&url.endsWith(">"))url=url.substring(1,url.length()-1);if(url.startsWith("URL:"))url=url.substring(4);
			try{java.net.URI uri=new java.net.URI(url);
				if(uri.getScheme()==null||!Sandbox.allowOther(rt.toolbox.name,Sandbox.OPEN_URL,url)||!java.awt.Desktop.isDesktopSupported()){ret(-666);return;}
				java.awt.Desktop.getDesktop().browse(uri);ret(0);}catch(Exception e){ret(-666);}});
		// ---- Scrap Manager (Carbon): one scrap, the desk scrap
		l.register(null,"GetCurrentScrap",(r,c)->{memory.writeLong(arg(0),SCRAP);ret(0);});
		l.register(null,"ClearCurrentScrap",(r,c)->ret(rt.toolbox("ZeroScrap")));
		l.register(null,"GetScrapFlavorFlags",(r,c)->{int offset=rt.allocate(4,4);int n=rt.toolbox("GetScrap",0,arg(1),offset);memory.free(offset);if(arg(2)!=0)memory.writeLong(arg(2),0);ret(n<0?-102:0);}); // noTypeErr
		l.register(null,"GetScrapFlavorSize",(r,c)->{int offset=rt.allocate(4,4);int n=rt.toolbox("GetScrap",0,arg(1),offset);memory.free(offset);if(n<0){ret(-102);return;}memory.writeLong(arg(2),n);ret(0);});
		l.register(null,"GetScrapFlavorData",(r,c)->{ // (scrap, type, *byteCount, destination)
			int h=rt.toolbox("NewHandle",0);int offset=rt.allocate(4,4);int n=rt.toolbox("GetScrap",h,arg(1),offset);memory.free(offset);
			if(n<0){rt.trap(0xa023,0,h,0);ret(-102);return;}
			int want=memory.readLong(arg(2));int copy=Math.min(want,n);memory.copy(memory.readLong(h),arg(3),copy);memory.writeLong(arg(2),copy);rt.trap(0xa023,0,h,0);ret(0);});
		l.register(null,"PutScrapFlavor",(r,c)->ret(rt.toolbox("PutScrap",arg(3),arg(1),arg(4))));
		l.register(null,"GetScrapFlavorCount",(r,c)->{memory.writeLong(arg(1),0);ret(0);});
		// ---- session printing: no printer dialogs here - every job is "cancelled"
		for(String name:new String[]{"PMBegin","PMEnd","PMNewPageFormat","PMNewPrintSettings","PMDefaultPageFormat","PMDefaultPrintSettings","PMDisposePageFormat","PMDisposePrintSettings",
				"PMFlattenPageFormat","PMUnflattenPageFormat","PMConvertOldPrintRecord","PMGetCopies","PMGetFirstPage","PMGetLastPage","PMSetError","PMValidatePageFormat","PMValidatePrintSettings"})
			l.register(null,name,(r,c)->ret(0));
		l.register(null,"PMError",(r,c)->ret(0));
		l.register(null,"PMPageSetupDialog",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),0);ret(0);});
		l.register(null,"PMPrintDialog",(r,c)->{if(arg(2)!=0)memory.writeByte(arg(2),0);ret(0);});
		for(String name:new String[]{"PMBeginDocument","PMEndDocument","PMBeginPage","PMEndPage","PMGetGrafPtr"})l.register(null,name,(r,c)->ret(-128));
		// ---- Navigation Services: not available (programs fall back to Standard File)
		l.register(null,"NavServicesAvailable",(r,c)->ret(0));
		l.register(null,"NavServicesCanRun",(r,c)->ret(0));
		l.register(null,"NavLibraryVersion",(r,c)->ret(0));
		// ---- Open Transport's time stamps (Carbon networking)
		defineEvents(l);defineWindows(l);defineWindows16(l);
	}
	private static final int SCRAP=0x53435250;

	private interface Body { void run() throws M68kException; }
	/** Runs {@code body} with {@code port} as the current port. */
	private void inPort(int port,Body body) throws M68kException {
		int slot=rt.allocate(4,4);
		try{rt.toolbox("GetPort",slot);int old=memory.readLong(slot);rt.toolbox("SetPort",port);try{body.run();}finally{rt.toolbox("SetPort",old);}}
		finally{memory.free(slot);}
	}
	/** A window's port rectangle in global coordinates. */
	private void globalPortRect(int w,int out) throws M68kException {
		memory.copy(w+16,out,8);
		inPort(w,()->{rt.toolbox("LocalToGlobal",out);rt.toolbox("LocalToGlobal",out+4);});
	}
	/** The WStateData (user state, standard state) of a zoomable window, or 0. */
	/** The window's WStateData, made for a window whose definition keeps none (WindowManager68k.stateData). */
	private int zoomData(int w) throws M68kCpuException {return rt.toolbox.windows.stateData(w,true);}
	/** A field of a menu item's record in the MenuInfo (0 icon, 1 key, 2 mark, 3 style). */
	private int menuItemField(int menu,int item,int field) throws M68kCpuException {
		int p=memory.readLong(menu);int at=p+14;at+=memory.readByte(at)+1;
		for(int i=1;memory.readByte(at)!=0;i++){int n=memory.readByte(at);if(i==item)return memory.readByte(at+1+n+field);at+=n+5;}
		return 0;
	}

	// ------------------------------------------------------------------ command IDs (SetMenuItemCommandID, 'xmnu')
	private final Map<Long,Integer> commandIds=new HashMap<Long,Integer>();
	private static long key(int menu,int item){return ((long)menu<<16)|item;}
	/** The menu and item with a command ID: SetMenuItemCommandID, else the 'xmnu' resource of each menu in the menu list. */
	private int[] commandItem(int inMenu,int command) throws M68kException {
		for(Map.Entry<Long,Integer> e:commandIds.entrySet())if(e.getValue()==command){int m=(int)(e.getKey()>>>16);if(inMenu==0||inMenu==m)return new int[]{m,(int)(e.getKey()&0xffff)};}
		loadXmnu();
		for(Map.Entry<Long,Integer> e:commandIds.entrySet())if(e.getValue()==command){int m=(int)(e.getKey()>>>16);if(inMenu==0||inMenu==m)return new int[]{m,(int)(e.getKey()&0xffff)};}
		return null;
	}
	private final java.util.Set<Integer> xmnuLoaded=new java.util.HashSet<Integer>();
	/**
	 * Command IDs from the 'xmnu' resources of the menus in the menu bar (ID = the menu's ID): version, count, then an entry
	 * per item - 0 (skip, 2 bytes) or 1 (34 bytes: command ID, modifiers, icon, encoding, refcons, hierarchical ID, font, glyph).
	 */
	private void loadXmnu() throws M68kException {
		int list=memory.readLong(0xa1c);if(list==0)return;int p=memory.readLong(list);if(p==0)return;
		int last=memory.readWord(p);
		for(int off=6;off<=last;off+=6){
			int menu=memory.readLong(p+off);if(menu==0||memory.readLong(menu)==0||!xmnuLoaded.add(menu))continue;
			int id=(short)memory.readWord(memory.readLong(menu));
			int h=rt.toolbox("GetResource",0x786d6e75,id);if(h==0)continue;
			int x=memory.readLong(h);int size=rt.toolbox("GetHandleSize",h);int count=memory.readWord(x+2);int at=x+4;
			for(int i=1;i<=count&&at+2<=x+size;i++){
				int type=memory.readWord(at);
				if(type==1){int cmd=memory.readLong(at+2);if(cmd!=0)commandIds.put(key(menu,i),cmd);at+=34;}
				else at+=2;
			}
		}
	}

	// ------------------------------------------------------------------ Window Manager additions (Carbon), alerts, nibs, Launch Services

	/** Per-window state the Window Manager here has no field for: modality, sheet parent, alpha, alternate title, ideal state, content pattern, group. */
	private static final class WindowExtra { int modality=-1,modalityParent,sheetParent,alternateTitle,idealState,contentPattern,group,paintProc,retainCount=1,defaultButton,cancelButton;float alpha=1;final Map<Long,Integer> propertyAttributes=new HashMap<Long,Integer>();
		/** CarbonLib 1.6: the Unicode title and the Pascal title it was set as (the sync token), window shade state and the content height it hides,
		 *  SetWindowFeatures' override (null: by class), the buffering flag, SetWindowTransform's bytes, SetDragRegion's region (global). */
		String unicodeTitle;byte[] unicodeTitlePascal;boolean collapsed,buffered;int collapsedHeight,dragRegion;Integer features;byte[] transform; }
	private final Map<Integer,WindowExtra> windowExtras=new HashMap<Integer,WindowExtra>();
	private WindowExtra extra(int w){return windowExtras.computeIfAbsent(w,k->new WindowExtra());}
	/** A window group: attributes, level, parent, owner, name, retain count (the Window Manager here layers windows by class). */
	private static final class WindowGroup { int attributes,level,parent,owner,name,references=1;final int windowClass;WindowGroup(int windowClass){this.windowClass=windowClass;} }
	private final Map<Integer,WindowGroup> windowGroups=new HashMap<Integer,WindowGroup>();
	private final Map<Integer,Integer> classGroups=new HashMap<Integer,Integer>();
	private int classGroup(int cls) throws M68kCpuException {Integer g=classGroups.get(cls);if(g==null){WindowGroup w=new WindowGroup(cls);w.level=cls==5||cls==8?3:cls==1||cls==2||cls==3||cls==4?8:0;g=rt.allocate(4,4);windowGroups.put(g,w);classGroups.put(cls,g);}return g;}
	private int groupOf(int w) throws M68kException {WindowExtra x=windowExtras.get(w);return x!=null&&x.group!=0?x.group:classGroup(windowClass(w));}
	/** The windows of running app-modal states, innermost first (RunAppModalLoopForWindow, BeginAppModalStateForWindow), and the loops asked to end. */
	private final List<Integer> modalWindows=new ArrayList<Integer>();
	private final java.util.Set<Integer> quitModal=new java.util.HashSet<Integer>();
	/** kControlPushButtonDefaultTag on a push button (SetWindowDefaultButton), redrawn when visible. */
	private void setDefaultFlag(int control,boolean on) throws M68kException {
		int p=rt.allocate(2,2);memory.writeByte(p,on?1:0);rt.toolbox("SetControlData",control,0,0x64666c74,1,p);memory.free(p);
		if((carbon("IsControlVisible",control)&0xff)!=0)rt.toolbox("Draw1Control",control);
	}
	/**
	 * ConstrainWindowToScreen's rectangle for structure {@code s} in {@code area} (top, left, bottom, right):
	 * kWindowConstrainMayResize 1 shrinks a window too big for the area, kWindowConstrainMoveRegardlessOfFit 2 moves one that
	 * still does not fit, kWindowConstrainAllowPartial 4 only keeps the title bar reachable.
	 */
	static int[] constrain(int[] s,int[] area,int opt){
		int[] n=s.clone();int aw=area[3]-area[1],ah=area[2]-area[0];
		if((opt&4)!=0){
			int dv=n[0]<area[0]?area[0]-n[0]:n[0]>area[2]-20?area[2]-20-n[0]:0,dh=n[1]>area[3]-40?area[3]-40-n[1]:n[3]<area[1]+40?area[1]+40-n[3]:0;
			n[0]+=dv;n[2]+=dv;n[1]+=dh;n[3]+=dh;return n;
		}
		boolean fits=n[3]-n[1]<=aw&&n[2]-n[0]<=ah;
		if(!fits&&(opt&1)!=0){n[3]=n[1]+Math.min(n[3]-n[1],aw);n[2]=n[0]+Math.min(n[2]-n[0],ah);fits=true;}
		if(!fits&&(opt&2)==0)return n;
		int dh=n[3]>area[3]?area[3]-n[3]:0;if(n[1]+dh<area[1])dh=area[1]-n[1]; // the top-left corner wins when it still does not fit
		int dv=n[2]>area[2]?area[2]-n[2]:0;if(n[0]+dv<area[0])dv=area[0]-n[0];
		n[0]+=dv;n[2]+=dv;n[1]+=dh;n[3]+=dh;return n;
	}
	/** The windows in the window list, front to back. */
	private List<Integer> windows() throws M68kCpuException {List<Integer> l=new ArrayList<Integer>();for(int p=memory.readLong(0x9d6),n=0;p!=0&&n<1000;p=memory.readLong(p+144),n++)l.add(p);return l;}
	/** The main screen's rectangle (the main GDevice's gdRect). */
	private void mainScreen(int out) throws M68kCpuException {int gd=memory.readLong(0x8a4);if(gd!=0&&memory.readLong(gd)!=0)memory.copy(memory.readLong(gd)+34,out,8);else memory.copy(qd()-122+6,out,8);}
	/** Standard alerts made by CreateStandardAlert, run (and disposed) by RunStandardAlert. */
	private static final class StandardAlert { int type,error,explanation,params,notify;boolean sheet; }
	private final Map<Integer,StandardAlert> standardAlerts=new HashMap<Integer,StandardAlert>();

	// ---- CarbonLib 1.6 Window Manager additions: Unicode titles, system properties, window shade (the WM* definition layer), process visibility

	/** The Window Manager's own properties (Get/SetWindowSystemProperty...): the same semantics as the program's, in a store of their own. */
	private final Map<Long,byte[]> systemProperties=new HashMap<Long,byte[]>();
	private final Map<Long,Integer> systemPropertyAttributes=new HashMap<Long,Integer>();
	/** SetDesktopWindow's window; the title double-click action (kWindowDoubleClickNone 0, Collapse 1 - the Mac OS 9 Appearance default -, Zoom 2); the gadget drop threshold in pixels. */
	private int desktopWindow,doubleClickAction=1,gadgetDropThreshold=4;
	/** ShowHideProcess(false) / HideProcessWindows: the program's windows hidden with it (shown again with the process). */
	private boolean processHidden;
	private final List<Integer> processHiddenWindows=new ArrayList<Integer>();
	static final int WINDOW_LATENT_VISIBLE_APP_HIDDEN=1<<3; // kWindowLatentVisibleAppHidden
	static final int WINDOW_CAN_GROW=1,WINDOW_CAN_ZOOM=2,WINDOW_CAN_COLLAPSE=4,WINDOW_IS_MODAL=8,WINDOW_CAN_GET_REGION=0x10,WINDOW_IS_ALERT=0x20,WINDOW_HAS_TITLE_BAR=0x40; // GetWindowFeatures
	/** The window's Pascal title (length byte included) as GetWTitle gives it. */
	private byte[] pascalTitle(int w) throws M68kException {int p=rt.allocate(256,2);try{rt.toolbox("GetWTitle",w,p);return memory.readBytes(p,memory.readByte(p)+1);}finally{memory.free(p);}}
	/** The Unicode title set by SetWindowUnicodeTitle / SetWindowTitleWithCFString while the Pascal title is still the one made from it; a SetWTitle since drops it (null). */
	private String unicodeTitle(int w) throws M68kException {
		WindowExtra x=windowExtras.get(w);if(x==null||x.unicodeTitle==null)return null;
		if(!java.util.Arrays.equals(pascalTitle(w),x.unicodeTitlePascal)){x.unicodeTitle=null;x.unicodeTitlePascal=null;return null;}
		return x.unicodeTitle;
	}
	/** The title as text: the Unicode title when in sync, else the Pascal title in the program's encoding. */
	private String titleText(int w) throws M68kException {String u=unicodeTitle(w);if(u!=null)return u;byte[] b=pascalTitle(w);return rt.toolbox.encoding.decode(java.util.Arrays.copyOfRange(b,1,b.length));}
	/** Sets the Pascal title from {@code text} (in the program's encoding, what cannot be encoded as the converter leaves it) and keeps the Unicode text with it. */
	private void setUnicodeTitle(int w,String text) throws M68kException {
		int p=pascal(text);try{rt.toolbox("SetWTitle",w,p);}finally{memory.free(p);}
		WindowExtra x=extra(w);x.unicodeTitle=text;x.unicodeTitlePascal=text==null?null:pascalTitle(w);
	}
	/** A window's WindowAttributes: as created (CreateNewWindow, ChangeWindowAttributes), else what its classic definition variant has. */
	private int windowAttributes(int w) throws M68kException {
		int[] k=windowClasses.get(w);if(k!=null)return k[1];
		int v=rt.toolbox("GetWVariant",w)&0xffff,cls=windowClass(w);
		return (memory.readByte(w+112)!=0?1:0)|(cls==6&&(v==8||v==12)?6:0)|(cls==6?8:0)|(cls==6&&(v==0||v==8)?16:0); // close, zoom (zoomDocProc/zoomNoGrow), collapse (Mac OS 9 shades documents), grow
	}
	private static boolean titledClass(int cls){return cls==2||cls==4||cls==5||cls==6||cls==8;}
	/** GetWindowFeatures' bits: SetWindowFeatures' override, else what the class and attributes give the window. */
	private int windowFeatures(int w) throws M68kException {
		WindowExtra x=windowExtras.get(w);if(x!=null&&x.features!=null)return x.features;
		int cls=windowClass(w),attr=windowAttributes(w);boolean titled=titledClass(cls);
		return WINDOW_CAN_GET_REGION|(titled?WINDOW_HAS_TITLE_BAR:0)|((attr&16)!=0?WINDOW_CAN_GROW:0)|((attr&6)!=0?WINDOW_CAN_ZOOM:0)|(titled&&(attr&8)!=0?WINDOW_CAN_COLLAPSE:0)
			|(cls>=1&&cls<=4||cls==11||cls==15?WINDOW_IS_MODAL:0)|(cls==1||cls==2||cls==15?WINDOW_IS_ALERT:0);
	}
	/** GetAvailableWindowAttributes: the attributes CreateNewWindow takes for a class (MacWindows.h bits), 0 for an unknown class. */
	static int availableAttributes(int cls){
		int common=1<<16|1<<17|1<<21|1<<24|1<<25|1<<26|1<<27|1<<28; // no updates, no activates, no shadow, hide on suspend, standard handler, hide on full screen, in Window menu, live resize
		switch(cls){
			case 6:return common|0x1f;                  // document: close, full zoom, collapse, resizable
			case 5:case 8:return common|0x3f;           // floating, utility: those and the side title bar
			case 2:case 4:case 11:case 15:return common|0x10; // movable alert/modal, sheets: resizable
			case 1:case 3:case 10:case 12:case 13:case 16:return common;
			case 14:return common|1<<18;                // overlay: opaque for events
			default:return 0;
		}
	}
	static boolean validWindowClass(int cls){return cls>=1&&cls<=6||cls==8||cls>=10&&cls<=16;}
	/**
	 * Window shade (CollapseWindow and the WM* layer): the content shrinks under the title bar - the Window Manager here has no
	 * collapsed frame, so the content is sized to one row and its height kept for the expand - with kEventWindowCollapsed/Expanded.
	 */
	private int collapse(int w,boolean on) throws M68kException {
		if(w==0||!windowsContain(w))return -5600; // errInvalidWindowRef
		WindowExtra x=extra(w);if(x.collapsed==on)return 0;
		if(on&&(windowFeatures(w)&WINDOW_CAN_COLLAPSE)==0)return -5615; // windowWrongStateErr: this window has no window shade
		int[] b=portBounds(w);
		if(on){x.collapsedHeight=b[2]-b[0];x.collapsed=true;rt.toolbox("SizeWindow",w,b[3]-b[1],1,0);}
		else{x.collapsed=false;rt.toolbox("SizeWindow",w,b[3]-b[1],Math.max(1,x.collapsedHeight),1);}
		windowEvent(w,on?67:70,0); // kEventWindowCollapsed / kEventWindowExpanded
		return 0;
	}
	private boolean collapsed(int w){WindowExtra x=windowExtras.get(w);return x!=null&&x.collapsed;}
	private int collapseAll(boolean on) throws M68kException {for(int w:windows())if(on?(windowFeatures(w)&WINDOW_CAN_COLLAPSE)!=0:collapsed(w))collapse(w,on);return 0;}
	private int[] regionRect(int rgn) throws M68kCpuException {int d=rgn==0?0:memory.readLong(rgn);if(d==0)return new int[4];int[] r=new int[4];for(int i=0;i<4;i++)r[i]=(short)memory.readWord(d+2+2*i);return r;}
	private void setRegionRect(int rgn,int[] r) throws M68kException {
		if(r==null||r[2]<=r[0]||r[3]<=r[1]){rt.toolbox("SetEmptyRgn",rgn);return;}
		int p=rt.allocate(8,2);memory.load(p,rectBytes(r));rt.toolbox("RectRgn",rgn,p);memory.free(p);
	}
	/**
	 * WMGetWindowRegion / GetDragRegion: a window region (global coordinates) by WindowRegionCode, from the window's own
	 * structure and content regions; the title bar is the structure above the content, its boxes placed as Platinum places
	 * them (13 pixels, 7 in from the ends; 9 and 2 in a utility window's thin bar).
	 */
	private int windowRegion(int w,int code,int rgn) throws M68kException {
		if(rgn==0)return -50;if(!windowsContain(w))return -5600;
		int s=memory.readLong(w+114),ct=memory.readLong(w+118);int[] S=regionRect(s),C=regionRect(ct);
		int f=windowFeatures(w),attr=windowAttributes(w);boolean shade=collapsed(w);
		int[] bar=(f&WINDOW_HAS_TITLE_BAR)!=0&&C[0]>S[0]?new int[]{S[0],S[1],C[0],S[3]}:null;
		int size=bar!=null&&bar[2]-bar[0]<18?9:13,inset=size==9?2:7;int y=bar==null?0:bar[0]+(bar[2]-bar[0]-size)/2;
		int[] close=bar!=null&&((attr&1)!=0||memory.readByte(w+112)!=0)?new int[]{y,bar[1]+inset,y+size,bar[1]+inset+size}:null;
		int[] shadeBox=bar!=null&&(f&WINDOW_CAN_COLLAPSE)!=0&&(attr&8)!=0?new int[]{y,bar[3]-inset-size,y+size,bar[3]-inset}:null;
		int zoomRight=shadeBox!=null?shadeBox[1]-4:bar==null?0:bar[3]-inset;
		int[] zoom=bar!=null&&(f&WINDOW_CAN_ZOOM)!=0?new int[]{y,zoomRight-size,y+size,zoomRight}:null;
		switch(code){
			case 32:rt.toolbox("CopyRgn",s,rgn);return 0;                                         // kWindowStructureRgn
			case 33:if(shade)rt.toolbox("SetEmptyRgn",rgn);else rt.toolbox("CopyRgn",ct,rgn);return 0; // kWindowContentRgn: empty while shaded
			case 34:{int u=memory.readLong(w+122);if(u!=0)rt.toolbox("CopyRgn",u,rgn);else rt.toolbox("SetEmptyRgn",rgn);return 0;} // kWindowUpdateRgn
			case 40:{int p=rt.allocate(8,4);globalPortRect(w,p);rt.toolbox("RectRgn",rgn,p);memory.free(p);return 0;} // kWindowGlobalPortRgn
			case 0:setRegionRect(rgn,bar);return 0;                                                // kWindowTitleBarRgn
			case 1:{if(bar==null){setRegionRect(rgn,null);return 0;}int l=close!=null?close[3]+6:bar[1]+inset,rr=zoom!=null?zoom[1]-6:shadeBox!=null?shadeBox[1]-6:bar[3]-inset;
				setRegionRect(rgn,new int[]{bar[0]+1,l,bar[2]-1,rr});return 0;}                        // kWindowTitleTextRgn: between the boxes
			case 2:setRegionRect(rgn,close);return 0;                                              // kWindowCloseBoxRgn
			case 3:setRegionRect(rgn,zoom);return 0;                                               // kWindowZoomBoxRgn
			case 7:setRegionRect(rgn,shadeBox);return 0;                                           // kWindowCollapseBoxRgn
			case 5:{WindowExtra x=windowExtras.get(w);if(x!=null&&x.dragRegion!=0)rt.toolbox("CopyRgn",x.dragRegion,rgn);else setRegionRect(rgn,bar);return 0;} // kWindowDragRgn
			case 6:setRegionRect(rgn,(f&WINDOW_CAN_GROW)!=0&&!shade?new int[]{C[2]-15,C[3]-15,C[2],C[3]}:null);return 0; // kWindowGrowRgn
			case 4:case 8:case 9:case 10:setRegionRect(rgn,null);return 0;                         // dirty, proxy icon, toolbar button, global port...: no such part here
			default:return -30593;                                                                 // errWindowRegionCodeInvalid
		}
	}
	/** Whether a ProcessSerialNumber names this program: kCurrentProcess {0, 2} or its own number (NIL too when {@code nilIsCurrent}). */
	private boolean currentProcess(int psn,boolean nilIsCurrent) throws M68kException {
		if(psn==0)return nilIsCurrent;
		int hi=memory.readLong(psn),lo=memory.readLong(psn+4);if(hi==0&&lo==2)return true;
		int own=rt.allocate(8,4);try{rt.toolbox("GetCurrentProcess",own);return memory.readLong(own)==hi&&memory.readLong(own+4)==lo;}finally{memory.free(own);}
	}
	/** ShowHideProcess for this program: hides its visible windows (kept for the show) or shows those again. */
	private void showHideProcess(boolean show) throws M68kException {
		if(!show&&!processHidden){processHidden=true;processHiddenWindows.clear();for(int w:windows())if(visible(w)){processHiddenWindows.add(w);showHide(w,false,"HideWindow");}}
		else if(show&&processHidden){processHidden=false;List<Integer> l=new ArrayList<Integer>(processHiddenWindows);processHiddenWindows.clear();for(int w:l)if(windowsContain(w))showHide(w,true,"ShowWindow");}
	}
	/** A Carbon event that is a mouse-down (kEventMouseDown or a window click event): {global point (v << 16 | h), modifiers}, else null. */
	private int[] mouseDown(int ref){
		Event e=events.get(ref);if(e==null)return null;
		boolean down=e.eventClass==CLASS_MOUSE&&e.kind==1||e.eventClass==CLASS_WINDOW&&e.kind>=32&&e.kind<=41;
		if(!down)return null;
		Object[] loc=e.params.get(P_MLOC),mod=e.params.get(P_KMOD);byte[] rec=e.record;
		int where=loc!=null?toInt((byte[])loc[1]):rec!=null&&rec.length>=14?(rec[10]&0xff)<<24|(rec[11]&0xff)<<16|(rec[12]&0xff)<<8|rec[13]&0xff:0;
		int modifiers=mod!=null?toInt((byte[])mod[1]):rec!=null&&rec.length>=16?(rec[14]&0xff)<<8|rec[15]&0xff:0;
		if(loc==null&&rec==null)return null;
		return new int[]{where,modifiers};
	}

	private void defineWindows16(PpcLibraries l){
		// ---- Unicode titles (CarbonLib-private; signatures inferred: (window, length, chars) to set - either order, the count
		// being the small one -, (window, maxLength, *actualLength, buffer) or (window, buffer, maxLength, *actualLength) to get)
		l.register(null,"SetWindowUnicodeTitle",(r,c)->{
			int a=arg(1),b=arg(2),count=Integer.compareUnsigned(a,0x1000)<0?a:b,chars=count==a?b:a;
			if(!windowsContain(arg(0))){ret(-5600);return;}if(chars==0&&count!=0){ret(-50);return;}
			StringBuilder sb=new StringBuilder();for(int i=0;i<count;i++)sb.append((char)memory.readWord(chars+2*i));
			setUnicodeTitle(arg(0),sb.toString());ret(0);});
		l.register(null,"GetWindowUnicodeTitle",(r,c)->{
			boolean bufferFirst=Integer.compareUnsigned(arg(1),0x1000)>=0;int max=bufferFirst?arg(2):arg(1),actual=bufferFirst?arg(3):arg(2),buffer=bufferFirst?arg(1):arg(3);
			if(!windowsContain(arg(0))){if(actual!=0)memory.writeLong(actual,0);ret(-5600);return;}
			String t=titleText(arg(0));int n=Math.min(t.length(),Math.max(0,max));
			if(buffer!=0)for(int i=0;i<n;i++)memory.writeWord(buffer+2*i,t.charAt(i));
			if(actual!=0)memory.writeLong(actual,t.length());ret(0);});
		l.register(null,"WindowHasUnicodeTitle",(r,c)->ret(windowsContain(arg(0))&&unicodeTitle(arg(0))!=null?1:0));
		l.register(null,"GetWindowTitleLength",(r,c)->ret(windowsContain(arg(0))?titleText(arg(0)).length():0)); // in UniChars
		// ---- system properties: as Get/SetWindowProperty, a store of their own
		l.register(null,"SetWindowSystemProperty",(r,c)->{if(arg(4)==0&&arg(3)!=0){ret(-50);return;}systemProperties.put(propertyKey(arg(0),arg(1),arg(2)),memory.readBytes(arg(4),arg(3)));ret(0);}); // (window, creator, tag, size, data)
		l.register(null,"GetWindowSystemProperty",(r,c)->{ // (window, creator, tag, bufferSize, *actualSize, buffer)
			byte[] v=systemProperties.get(propertyKey(arg(0),arg(1),arg(2)));if(v==null){if(arg(4)!=0)memory.writeLong(arg(4),0);ret(-5604);return;} // errWindowPropertyNotFound
			if(arg(4)!=0)memory.writeLong(arg(4),v.length);if(arg(5)!=0)memory.load(arg(5),java.util.Arrays.copyOf(v,Math.max(0,Math.min(v.length,arg(3)))));ret(0);});
		l.register(null,"GetWindowSystemPropertySize",(r,c)->{byte[] v=systemProperties.get(propertyKey(arg(0),arg(1),arg(2)));if(arg(3)!=0)memory.writeLong(arg(3),v==null?0:v.length);ret(v==null?-5604:0);});
		l.register(null,"RemoveWindowSystemProperty",(r,c)->{long k=propertyKey(arg(0),arg(1),arg(2));systemPropertyAttributes.remove(k);ret(systemProperties.remove(k)==null?-5604:0);});
		l.register(null,"GetWindowSystemPropertyAttributes",(r,c)->{long k=propertyKey(arg(0),arg(1),arg(2));boolean has=systemProperties.containsKey(k);if(arg(3)!=0)memory.writeLong(arg(3),has?systemPropertyAttributes.getOrDefault(k,0):0);ret(has?0:-5604);});
		l.register(null,"ChangeWindowSystemPropertyAttributes",(r,c)->{long k=propertyKey(arg(0),arg(1),arg(2));if(!systemProperties.containsKey(k)){ret(-5604);return;}systemPropertyAttributes.put(k,(systemPropertyAttributes.getOrDefault(k,0)|arg(3))&~arg(4));ret(0);});
		// ---- order, classes, features
		l.register(null,"GetPreviousWindow",(r,c)->{int prev=0;for(int w:windows()){if(w==arg(0)){ret(prev);return;}prev=w;}ret(0);});
		l.register(null,"IsValidWindowClass",(r,c)->ret(validWindowClass(arg(0))?1:0));
		l.register(null,"GetAvailableWindowAttributes",(r,c)->ret(availableAttributes(arg(0))));
		l.register(null,"GetWindowVisualClass",(r,c)->{ // (window, *class): the class it is drawn as - on Mac OS 9 a sheet looks like a movable modal dialog, a sheet alert like a movable alert
			if(!windowsContain(arg(0))){if(arg(1)!=0)memory.writeLong(arg(1),0);ret(-5600);return;}int cls=windowClass(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),cls==11?4:cls==15?2:cls);ret(0);});
		l.register(null,"GetWindowProcess",(r,c)->{ // (window, *psn): every window here is the program's own
			if(!windowsContain(arg(0))){if(arg(1)!=0)memory.fill(arg(1),8,0);ret(-5600);return;}if(arg(1)!=0)rt.toolbox("GetCurrentProcess",arg(1));ret(0);});
		l.register(null,"SetWindowFeatures",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}extra(arg(0)).features=arg(1);ret(0);}); // (window, features): what the definition reports from now on
		l.register(null,"GetWindowStructurePort",(r,c)->ret(windowsContain(arg(0))?memory.readLong(0xd2c):0)); // the frame is drawn in the Window Manager port (WMgrCPort)
		// ---- the window-definition layer (WM*): window shade, features, regions
		l.register(null,"WMCollapseWindow",(r,c)->ret(collapse(arg(0),(arg(1)&0xff)!=0)));
		l.register(null,"WMIsWindowCollapsed",(r,c)->ret(collapsed(arg(0))?1:0));
		l.register(null,"WMIsWindowCollapsable",(r,c)->ret(windowsContain(arg(0))&&(windowFeatures(arg(0))&WINDOW_CAN_COLLAPSE)!=0?1:0));
		l.register(null,"WMCollapseAllWindows",(r,c)->ret(collapseAll((arg(0)&0xff)!=0)));
		l.register(null,"WMGetWindowFeatures",(r,c)->{if(!windowsContain(arg(0))){if(arg(1)!=0)memory.writeLong(arg(1),0);ret(-5600);return;}if(arg(1)!=0)memory.writeLong(arg(1),windowFeatures(arg(0)));ret(0);});
		l.register(null,"WMGetWindowRegion",(r,c)->ret(windowRegion(arg(0),arg(1)&0xffff,arg(2))));
		// the Appearance calls of the same layer for Carbon programs, so that both agree (the 68K Appearance trap keeps no shade state)
		l.register("CarbonLib","CollapseWindow",(r,c)->ret(collapse(arg(0),(arg(1)&0xff)!=0)));
		l.register("CarbonLib","IsWindowCollapsed",(r,c)->ret(collapsed(arg(0))?1:0));
		l.register("CarbonLib","IsWindowCollapsable",(r,c)->ret(windowsContain(arg(0))&&(windowFeatures(arg(0))&WINDOW_CAN_COLLAPSE)!=0?1:0));
		l.register("CarbonLib","CollapseAllWindows",(r,c)->ret(collapseAll((arg(0)&0xff)!=0)));
		l.register("CarbonLib","GetWindowFeatures",(r,c)->{if(!windowsContain(arg(0))){if(arg(1)!=0)memory.writeLong(arg(1),0);ret(-5600);return;}if(arg(1)!=0)memory.writeLong(arg(1),windowFeatures(arg(0)));ret(0);});
		l.register(null,"GetDragRegion",(r,c)->ret(windowRegion(arg(0),5,arg(1)))); // (window, rgn): SetDragRegion's or the title bar
		l.register(null,"SetDragRegion",(r,c)->{ // (window, rgn or NIL for the title bar again), global coordinates
			if(!windowsContain(arg(0))){ret(-5600);return;}WindowExtra x=extra(arg(0));
			if(arg(1)==0){if(x.dragRegion!=0)rt.toolbox("DisposeRgn",x.dragRegion);x.dragRegion=0;ret(0);return;}
			if(x.dragRegion==0)x.dragRegion=rt.toolbox("NewRgn");rt.toolbox("CopyRgn",arg(1),x.dragRegion);ret(0);});
		l.register(null,"ConstrainedDragRgn",(r,c)->ret(rt.toolbox("DragGrayRgn",arg(0),arg(1),arg(2),arg(3),arg(4)&0xffff,arg(5)))); // (rgn, startPt, limitRect, slopRect, constraint, actionProc): DragGrayRgn's tracking along the axis
		// ---- title bar behaviour
		l.register(null,"GetWindowDoubleClickAction",(r,c)->ret(doubleClickAction)); // private, inferred: () -> action
		l.register(null,"SetWindowDoubleClickAction",(r,c)->{if(arg(0)<0||arg(0)>2){ret(-50);return;}doubleClickAction=arg(0);ret(0);});
		l.register(null,"DoubleClickWindowTitle",(r,c)->{ // (window): what the user setting says - window shade on/off or zoom in/out
			int w=arg(0);if(!windowsContain(w)){ret(-5600);return;}int f=windowFeatures(w);
			if(doubleClickAction==1&&(f&WINDOW_CAN_COLLAPSE)!=0){ret(collapse(w,!collapsed(w)));return;}
			if(doubleClickAction==2&&(f&WINDOW_CAN_ZOOM)!=0){int before=r.allocate(8,4);globalPortRect(w,before);int part=(carbon("IsWindowInStandardState",w,0,0)&0xff)!=0?7:8; // inZoomIn / inZoomOut
				inPort(w,()->rt.toolbox("EraseRect",w+16));rt.toolbox("ZoomWindow",w,part,0);boundsChanged(w,before,4|8);memory.free(before);}
			ret(0);});
		l.register(null,"GetWindowGadgetDropThreshold",(r,c)->ret(gadgetDropThreshold)); // private, inferred: how far (pixels) the mouse may leave a title bar box and still release in it
		l.register(null,"SetWindowGadgetDropThreshold",(r,c)->{gadgetDropThreshold=Math.max(0,arg(0));ret(0);});
		l.register(null,"IsWindowPathSelectEvent",(r,c)->{ // (window, event): a Command-click in the title (WindowPathSelect then pops up the path)
			int[] m=mouseDown(arg(1));if(m==null||(m[1]&0x100)==0||!windowsContain(arg(0))){ret(0);return;}
			int rgn=rt.toolbox("NewRgn");boolean in=windowRegion(arg(0),1,rgn)==0&&(rt.toolbox("PtInRgn",m[0],rgn)&0xff)!=0;rt.toolbox("DisposeRgn",rgn);ret(in?1:0);});
		// ---- activation: through the low-memory CurActivate ($A64) / CurDeactive ($A68) the Event Manager reports first
		l.register(null,"PostWindowActivateEvent",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}memory.writeLong((arg(1)&0xff)!=0?0xa64:0xa68,arg(0));ret(0);}); // (window, activate)
		l.register(null,"GetNextWindowActivateEvent",(r,c)->{ // (*EventRecord): the pending deactivate, then activate, taken
			int e=arg(0);for(int global:new int[]{0xa68,0xa64}){int w=memory.readLong(global);if(w==0)continue;memory.writeLong(global,0);if(!windowsContain(w))continue;
				if(e!=0){memory.writeWord(e,8);memory.writeLong(e+2,w);memory.writeLong(e+6,memory.readLong(0x16a));memory.writeLong(e+10,memory.readLong(0x830));memory.writeWord(e+14,global==0xa64?1:0);}ret(1);return;} // activateEvt, Ticks, the mouse, activeFlag
			if(e!=0)memory.fill(e,16,0);ret(0);});
		// ---- geometry and drawing
		l.register(null,"CheckWindowPosition",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}ret(carbon("ConstrainWindowToScreen",arg(0),32,4,0,0));}); // the title bar kept reachable (kWindowConstrainAllowPartial)
		l.register(null,"EraseWindowRegion",(r,c)->{ // (window, rgn in local coordinates, NIL for the whole port): erased to the window's background
			int w=arg(0),rgn=arg(1);if(!windowsContain(w)){ret(-5600);return;}inPort(w,()->{if(rgn!=0)rt.toolbox("EraseRgn",rgn);else rt.toolbox("EraseRect",w+16);});ret(0);});
		l.register(null,"GlobalFindWindow",(r,c)->{int slot=r.allocate(4,4);int part=(short)rt.toolbox("FindWindow",arg(0),slot);if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(slot));memory.free(slot);ret(part);}); // (where, *window): one process's windows here
		l.register(null,"GetWindowTransform",(r,c)->{ // (window, *transform): what SetWindowTransform stored, else identity (CGAffineTransform of floats)
			if(arg(1)==0){ret(-50);return;}WindowExtra x=windowExtras.get(arg(0));byte[] t=x!=null&&x.transform!=null?x.transform:longs(Float.floatToRawIntBits(1f),0,0,Float.floatToRawIntBits(1f),0,0);memory.load(arg(1),t);ret(0);});
		l.register(null,"SetWindowTransform",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}extra(arg(0)).transform=arg(1)==0?null:memory.readBytes(arg(1),24);ret(0);}); // kept for GetWindowTransform; not applied - this Window Manager draws windows unscaled
		l.register(null,"EnableWindowBuffering",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}extra(arg(0)).buffered=true;ret(0);}); // the host composites every window anyway; the flag is kept
		l.register(null,"DisableWindowBuffering",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}extra(arg(0)).buffered=false;ret(0);});
		l.register(null,"IsWindowBufferingEnabled",(r,c)->{WindowExtra x=windowExtras.get(arg(0));ret(x!=null&&x.buffered?1:0);});
		l.register(null,"ReshapeWindows",(r,c)->{for(int w:windows())reshape(w);ret(0);});         // every frame recalculated and drawn, the content invalidated
		l.register(null,"PostDelayedWindowReshape",(r,c)->{if(!windowsContain(arg(0))){ret(-5600);return;}reshape(arg(0));ret(0);}); // done at once
		l.register(null,"RecalculateAllWindowTitleWidths",(r,c)->{for(int w:windows()){WindowManager68k.Window win=rt.toolbox.windows.window(w);if(win!=null)rt.toolbox.windows.drawFrame(win);}ret(0);}); // titles are measured as frames draw
		l.register(null,"GetDesktopWindow",(r,c)->ret(desktopWindow!=0&&windowsContain(desktopWindow)?desktopWindow:0));
		l.register(null,"SetDesktopWindow",(r,c)->{if(arg(0)!=0&&!windowsContain(arg(0))){ret(-5600);return;}desktopWindow=arg(0);ret(0);});
		l.register(null,"SetThemeBackgroundForWindow",(r,c)->ret(rt.toolbox("SetThemeWindowBackground",arg(0),(short)arg(1),arg(2)&0xff))); // (window, brush, update)
		l.register(null,"WindowManagerSetThemeWindowBackground",(r,c)->ret(rt.toolbox("SetThemeWindowBackground",arg(0),(short)arg(1),arg(2)&0xff)));
		// ---- Process Manager window visibility (Processes.h): this program's windows; another process is not found (procNotFound -600)
		l.register(null,"IsProcessVisible",(r,c)->ret(currentProcess(arg(0),false)&&!processHidden?1:0));
		l.register(null,"ShowHideProcess",(r,c)->{if(!currentProcess(arg(0),false)){ret(-600);return;}showHideProcess((arg(1)&0xff)!=0);ret(0);});
		l.register(null,"HideProcessWindows",(r,c)->{if(!currentProcess(arg(0),true)){ret(-600);return;}showHideProcess(false);ret(0);});
		l.register(null,"ShowProcessWindows",(r,c)->{if(!currentProcess(arg(0),true)){ret(-600);return;}showHideProcess(true);ret(0);});
		l.register(null,"BringProcessWindowsToFront",(r,c)->{if(!currentProcess(arg(0),true)){ret(-600);return;}showHideProcess(true);
			int front=rt.toolbox.windows.frontNonFloating();if(front!=0)rt.toolbox("SelectWindow",front);ret(0);});
		l.register(null,"IsWindowLatentVisible",(r,c)->{ // (window, *latent): hidden only for a reason that goes away (here: the process hidden)
			int latent=processHidden&&processHiddenWindows.contains(arg(0))?WINDOW_LATENT_VISIBLE_APP_HIDDEN:0;if(arg(1)!=0)memory.writeLong(arg(1),latent);ret(latent!=0?1:0);});
	}
	/** ReshapeWindows for one window: a definition of the program's own recalculates its regions (wCalcRgns) and draws; the content is invalidated. */
	private void reshape(int w) throws M68kException {
		WindowManager68k.Window win=rt.toolbox.windows.window(w);if(win==null)return;
		if(win.custom){rt.toolbox.windows.callWdef(win,2,0);rt.toolbox.windows.drawFrame(win);}
		if(visible(w))inPort(w,()->rt.toolbox("InvalRect",w+16));
	}

	private void defineWindows(PpcLibraries l){
		// ---- modality and sheets (on Mac OS 9 a sheet is a movable modal dialog centred on its parent)
		l.register(null,"SetWindowModality",(r,c)->{WindowExtra x=extra(arg(0));x.modality=arg(1);x.modalityParent=arg(2);ret(0);});
		l.register(null,"GetWindowModality",(r,c)->{WindowExtra x=windowExtras.get(arg(0));int m=x!=null&&x.modality>=0?x.modality:windowClass(arg(0))<=4?2:0; // kWindowModalityAppModal for alerts and modal dialogs
			if(arg(1)!=0)memory.writeLong(arg(1),m);if(arg(2)!=0)memory.writeLong(arg(2),x==null?0:x.modalityParent);ret(0);});
		l.register(null,"ShowSheetWindow",(r,c)->{int sheet=arg(0),parent=arg(1);
			StandardAlert standard=standardAlerts.get(sheet);if(standard!=null&&standard.sheet){runStandardSheet(sheet,standard);ret(0);return;}extra(sheet).sheetParent=parent;extra(sheet).modality=3;
			carbon("RepositionWindow",sheet,parent,2); // kWindowCenterOnParentWindow
			rt.toolbox("ShowWindow",sheet);rt.toolbox("SelectWindow",sheet);ret(0);});
		l.register(null,"HideSheetWindow",(r,c)->{rt.toolbox("HideWindow",arg(0));WindowExtra x=windowExtras.get(arg(0));int parent=x==null?0:x.sheetParent;if(parent!=0&&windowsContain(parent))rt.toolbox("SelectWindow",parent);ret(0);});
		l.register(null,"GetSheetWindowParent",(r,c)->{WindowExtra x=windowExtras.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),x==null?0:x.sheetParent);ret(x==null||x.sheetParent==0?-5600:0);}); // errInvalidWindowRef
		l.register(null,"ActiveNonFloatingWindow",(r,c)->{for(int w:windows())if(memory.readByte(w+110)!=0&&windowClass(w)!=5&&windowClass(w)!=8){ret(w);return;}ret(0);});
		l.register(null,"IsWindowActive",(r,c)->ret(memory.readByte(arg(0)+111)!=0?1:0));
		l.register(null,"SetWindowClass",(r,c)->{int[] k=windowClasses.get(arg(0));if(k!=null)k[0]=arg(1);else windowClasses.put(arg(0),new int[]{arg(1),0});ret(0);});
		// ---- CarbonLib 1.3+ (Mac OS X 10.0): retain counts, app-modal loops, default and cancel buttons, keeping a window on screen
		l.register(null,"RetainWindow",(r,c)->{extra(arg(0)).retainCount++;ret(0);});
		l.register(null,"ReleaseWindow",(r,c)->{int w=arg(0);WindowExtra x=extra(w);if(--x.retainCount>0){ret(0);return;}windowExtras.remove(w);carbon("DisposeWindow",w);ret(0);});
		l.register(null,"GetWindowRetainCount",(r,c)->{WindowExtra x=windowExtras.get(arg(0));ret(x==null?1:x.retainCount);});
		l.register(null,"BeginAppModalStateForWindow",(r,c)->{modalWindows.add(0,arg(0));ret(0);});
		l.register(null,"EndAppModalStateForWindow",(r,c)->ret(modalWindows.remove((Integer)arg(0))?0:-50));
		l.register(null,"RunAppModalLoopForWindow",(r,c)->{ // runs the event loop, other windows and the menu bar refusing clicks, until QuitAppModalLoopForWindow
			final int w=arg(0);modalWindows.add(0,w);quitModal.remove(w);
			try{pump(()->quitModal.contains(w)||!windowsContain(w));}finally{modalWindows.remove((Integer)w);quitModal.remove(w);}
			ret(0);});
		l.register(null,"QuitAppModalLoopForWindow",(r,c)->{if(!modalWindows.contains(arg(0))){ret(-50);return;}quitModal.add(arg(0));ret(0);});
		l.register(null,"SetWindowDefaultButton",(r,c)->{WindowExtra x=extra(arg(0));int old=x.defaultButton;x.defaultButton=arg(1); // drawn with the default ring
			if(old!=0&&old!=arg(1))setDefaultFlag(old,false);if(arg(1)!=0)setDefaultFlag(arg(1),true);ret(0);});
		l.register(null,"GetWindowDefaultButton",(r,c)->{WindowExtra x=windowExtras.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),x==null?0:x.defaultButton);ret(0);});
		l.register(null,"SetWindowCancelButton",(r,c)->{extra(arg(0)).cancelButton=arg(1);ret(0);});
		l.register(null,"GetWindowCancelButton",(r,c)->{WindowExtra x=windowExtras.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),x==null?0:x.cancelButton);ret(0);});
		l.register(null,"ConstrainWindowToScreen",(r,c)->{ // (window, regionCode, options, *screenRect or NIL, *outStructure)
			int w=arg(0),opt=arg(2),out=arg(4);int[] area=new int[4];int rect=r.allocate(8,4);
			if(arg(3)!=0)memory.copy(arg(3),rect,8);else carbon("GetAvailableWindowPositioningBounds",memory.readLong(0x8a4),rect);
			for(int i=0;i<4;i++)area[i]=(short)memory.readWord(rect+2*i);
			carbon("GetWindowBounds",w,32,rect);int[] s=new int[4];for(int i=0;i<4;i++)s[i]=(short)memory.readWord(rect+2*i);memory.free(rect);
			int[] n=constrain(s,area,opt);
			if((opt&8)==0){ // not kWindowConstrainCalcOnly: the content follows the structure's move and size change
				int[] b=portBounds(w);int dh=n[3]-n[1]-(s[3]-s[1]),dv=n[2]-n[0]-(s[2]-s[0]);
				if(dh!=0||dv!=0)rt.toolbox("SizeWindow",w,b[3]-b[1]+dh,b[2]-b[0]+dv,1);
				if(n[0]!=s[0]||n[1]!=s[1])rt.toolbox("MoveWindow",w,b[1]+n[1]-s[1],b[0]+n[0]-s[0],0);
			}
			if(out!=0)memory.load(out,rectBytes(n));ret(0);});
		// ---- screens
		l.register(null,"GetAvailableWindowPositioningBounds",(r,c)->{ // (device, *availableRect): the device less the menu bar on the main screen
			int gd=arg(0)==0?memory.readLong(0x8a4):arg(0),out=arg(1);if(out==0){ret(-50);return;}
			if(gd!=0&&memory.readLong(gd)!=0)memory.copy(memory.readLong(gd)+34,out,8);else mainScreen(out);
			if(gd==0||gd==memory.readLong(0x8a4))memory.writeWord(out,(short)memory.readWord(out)+(short)rt.toolbox("GetMBarHeight"));
			ret(0);});
		l.register(null,"GetWindowGreatestAreaDevice",(r,c)->{ // (window, regionCode, *outDevice, *outRect): one screen here
			int gd=memory.readLong(0x8a4);if(arg(2)!=0)memory.writeLong(arg(2),gd);if(arg(3)!=0)mainScreen(arg(3));ret(0);});
		// ---- look and state
		l.register(null,"SetWindowAlpha",(r,c)->{extra(arg(0)).alpha=(float)r.farg(0);ret(0);});
		l.register(null,"GetWindowAlpha",(r,c)->{WindowExtra x=windowExtras.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),Float.floatToRawIntBits(x==null?1f:x.alpha));ret(0);});
		l.register(null,"SetWindowAlternateTitle",(r,c)->{WindowExtra x=extra(arg(0));if(x.alternateTitle!=0)rt.libraries.coreFoundation.release(x.alternateTitle);x.alternateTitle=arg(1)==0?0:rt.libraries.coreFoundation.retain(arg(1));ret(0);});
		l.register(null,"CopyWindowAlternateTitle",(r,c)->{WindowExtra x=windowExtras.get(arg(0));int t=x==null?0:x.alternateTitle;if(arg(1)!=0)memory.writeLong(arg(1),t==0?0:rt.libraries.coreFoundation.retain(t));ret(0);});
		l.register(null,"GetWindowSpareFlag",(r,c)->ret(memory.readByte(arg(0)+113)!=0?1:0));
		l.register(null,"AreFloatingWindowsVisible",(r,c)->ret(1));
		l.register(null,"ShowFloatingWindows",(r,c)->ret(0));
		l.register(null,"HideFloatingWindows",(r,c)->ret(0));
		l.register(null,"InitFloatingWindows",(r,c)->ret(0)); // Mac OS 8.6 InterfaceLib (Gestalt 'wind' gestaltHasFloatingWindows): floating windows need no set-up here
		l.register(null,"CloneWindow",(r,c)->ret(0));
		l.register(null,"GetWindowOwnerCount",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),1);ret(0);});
		l.register(null,"IsWindowUpdatePending",(r,c)->{int rgn=memory.readLong(arg(0)+122);ret(rgn!=0&&(rt.toolbox("EmptyRgn",rgn)&0xff)==0?1:0);});
		l.register(null,"ReshapeCustomWindow",(r,c)->ret(0));
		l.register(null,"InstallWindowContentPaintProc",(r,c)->{extra(arg(0)).paintProc=arg(1);ret(0);});
		l.register(null,"SetWindowContentPattern",(r,c)->{extra(arg(0)).contentPattern=arg(1);inPort(arg(0),()->rt.toolbox("BackPixPat",arg(1)));ret(0);});
		l.register(null,"GetWindowContentPattern",(r,c)->{WindowExtra x=windowExtras.get(arg(0));if(x==null||x.contentPattern==0){ret(-50);return;}rt.toolbox("CopyPixPat",x.contentPattern,arg(1));ret(0);});
		l.register(null,"GetWindowPropertyAttributes",(r,c)->{WindowExtra x=windowExtras.get(arg(0));long k=propertyKey(arg(0),arg(1),arg(2));if(!properties.containsKey(k)){ret(-5604);return;}Integer a=x==null?null:x.propertyAttributes.get(k);if(arg(3)!=0)memory.writeLong(arg(3),a==null?0:a);ret(0);});
		l.register(null,"ChangeWindowPropertyAttributes",(r,c)->{long k=propertyKey(arg(0),arg(1),arg(2));if(!properties.containsKey(k)){ret(-5604);return;}WindowExtra x=extra(arg(0));int a=x.propertyAttributes.getOrDefault(k,0);x.propertyAttributes.put(k,(a|arg(3))&~arg(4));ret(0);});
		// ---- geometry
		l.register(null,"GetWindowIdealUserState",(r,c)->{WindowExtra x=windowExtras.get(arg(0));if(x==null||x.idealState==0)globalPortRect(arg(0),arg(1));else memory.copy(x.idealState,arg(1),8);ret(0);});
		l.register(null,"SetWindowIdealUserState",(r,c)->{WindowExtra x=extra(arg(0));if(x.idealState==0)x.idealState=r.allocate(8,4);memory.copy(arg(1),x.idealState,8);ret(0);});
		l.register(null,"IsWindowInStandardState",(r,c)->{ // (window, *idealSize, *idealStandardState)
			int w=arg(0),now=r.allocate(8,4);globalPortRect(w,now);int d=zoomData(w);boolean standard=d!=0&&java.util.Arrays.equals(memory.readBytes(d+8,8),memory.readBytes(now,8));
			if(arg(2)!=0){if(d!=0)memory.copy(d+8,arg(2),8);else memory.copy(now,arg(2),8);}memory.free(now);ret(standard?1:0);});
		l.register(null,"ZoomWindowIdeal",(r,c)->{ // (window, partCode, *ioIdealSize): zoom out to the ideal size (on the screen), in to the user state
			int w=arg(0),part=arg(1)&0xffff,size=arg(2);int d=zoomData(w);
			if(part==8&&d!=0&&size!=0){int now=r.allocate(8,4);globalPortRect(w,now);int top=(short)memory.readWord(now),left=(short)memory.readWord(now+2);
				int h=(short)memory.readWord(size),wd=(short)memory.readWord(size+2);memory.writeWord(d+8,top);memory.writeWord(d+10,left);memory.writeWord(d+12,top+h);memory.writeWord(d+14,left+wd);memory.free(now);}
			inPort(w,()->rt.toolbox("EraseRect",w+16));rt.toolbox("ZoomWindow",w,part,0);ret(0);});
		l.register(null,"MoveWindowStructure",(r,c)->{ // (window, h, v): the structure's top-left there
			int w=arg(0);int s=memory.readLong(w+114),ct=memory.readLong(w+118);int dh=0,dv=0;
			if(s!=0&&ct!=0){dh=(short)memory.readWord(memory.readLong(ct)+4)-(short)memory.readWord(memory.readLong(s)+4);dv=(short)memory.readWord(memory.readLong(ct)+2)-(short)memory.readWord(memory.readLong(s)+2);}
			rt.toolbox("MoveWindow",w,(short)arg(1)+dh,(short)arg(2)+dv,0);ret(0);});
		l.register(null,"ResizeWindow",(r,c)->{ // (window, startPoint, sizeConstraints, *newContentRect): true when the size changed
			int w=arg(0),bounds=r.allocate(8,4);
			if(arg(2)!=0)memory.copy(arg(2),bounds,8);else{memory.writeWord(bounds,64);memory.writeWord(bounds+2,64);memory.writeWord(bounds+4,32000);memory.writeWord(bounds+6,32000);}
			int size=rt.toolbox("GrowWindow",w,arg(1),bounds);memory.free(bounds);
			if(size!=0)rt.toolbox("SizeWindow",w,size&0xffff,size>>>16,1);
			if(arg(3)!=0)globalPortRect(w,arg(3));ret(size!=0?1:0);});
		l.register(null,"TransitionWindow",(r,c)->{int action=arg(2);if(action==1)rt.toolbox("ShowWindow",arg(0));else if(action==2)rt.toolbox("HideWindow",arg(0));ret(0);}); // (window, effect, action, rect): no animation
		l.register(null,"ScrollWindowRect",(r,c)->{ // (window, rect, dh, dv, options, exposedRgn): kScrollWindowInvalidate 1, kScrollWindowEraseToPortBackground 2
			int w=arg(0),rect=arg(1),opt=arg(4),exposed=arg(5);int rgn=rt.toolbox("NewRgn");
			inPort(w,()->{rt.toolbox("ScrollRect",rect,(short)arg(2),(short)arg(3),rgn);if((opt&1)!=0)rt.toolbox("InvalRgn",rgn);});
			if(exposed!=0)rt.toolbox("CopyRgn",rgn,exposed);rt.toolbox("DisposeRgn",rgn);ret(0);});
		l.register(null,"ScrollWindowRegion",(r,c)->{
			int w=arg(0),opt=arg(4),exposed=arg(5);int rect=r.allocate(8,4);memory.copy(memory.readLong(arg(1))+2,rect,8);int rgn=rt.toolbox("NewRgn");
			inPort(w,()->{rt.toolbox("ScrollRect",rect,(short)arg(2),(short)arg(3),rgn);if((opt&1)!=0)rt.toolbox("InvalRgn",rgn);});
			if(exposed!=0)rt.toolbox("CopyRgn",rgn,exposed);rt.toolbox("DisposeRgn",rgn);memory.free(rect);ret(0);});
		l.register(null,"FindWindowOfClass",(r,c)->{ // (where*, class, *outWindow, *outPart)
			int slot=r.allocate(4,4);int part=(short)rt.toolbox("FindWindow",memory.readLong(arg(0)),slot);int w=memory.readLong(slot);memory.free(slot);
			if(arg(2)!=0)memory.writeLong(arg(2),w);if(arg(3)!=0)memory.writeWord(arg(3),part);ret(w==0?-5600:0);});
		l.register(null,"CreateWindowFromResource",(r,c)->{ // (resID, *outWindow): the 'WIND' template of that ID (the Carbon 'wind' resource is Mac OS X's)
			int w=rt.toolbox("GetNewCWindow",(short)arg(0),0,-1);if(arg(1)!=0)memory.writeLong(arg(1),w);ret(w==0?-192:0);}); // resNotFound
		l.register(null,"CreateWindowFromCollection",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(-50);});
		l.register(null,"StoreWindowIntoCollection",(r,c)->ret(-50));
		// proxies: no proxy icons in the title bars here
		l.register(null,"GetWindowProxyAlias",(r,c)->ret(-5602)); // errWindowDoesNotHaveProxy
		l.register(null,"GetWindowProxyFSSpec",(r,c)->ret(-5602));
		l.register(null,"GetWindowProxyIcon",(r,c)->ret(-5602));
		l.register(null,"SetWindowProxyAlias",(r,c)->ret(0));
		l.register(null,"BeginWindowProxyDrag",(r,c)->ret(-5608));
		l.register(null,"EndWindowProxyDrag",(r,c)->ret(0));
		l.register(null,"TrackWindowProxyFromExistingDrag",(r,c)->ret(-5608));
		// ---- window groups: kept, ordering is by window class here
		l.register(null,"CreateWindowGroup",(r,c)->{int g=r.allocate(4,4);WindowGroup w=new WindowGroup(0);w.attributes=arg(0);windowGroups.put(g,w);if(arg(1)!=0)memory.writeLong(arg(1),g);ret(0);});
		l.register(null,"RetainWindowGroup",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null)g.references++;ret(g==null?-50:0);});
		l.register(null,"ReleaseWindowGroup",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null&&g.windowClass==0&&--g.references<=0){windowGroups.remove(arg(0));memory.free(arg(0));}ret(g==null?-50:0);});
		l.register(null,"GetWindowGroupRetainCount",(r,c)->{WindowGroup g=windowGroups.get(arg(0));ret(g==null?0:g.references);});
		l.register(null,"GetWindowGroupOfClass",(r,c)->ret(classGroup(arg(0))));
		l.register(null,"SetWindowGroup",(r,c)->{extra(arg(0)).group=arg(1);ret(0);});
		l.register(null,"GetWindowGroup",(r,c)->ret(groupOf(arg(0))));
		l.register(null,"SetWindowGroupLevel",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null)g.level=arg(1);ret(g==null?-50:0);});
		l.register(null,"GetWindowGroupLevel",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),g==null?0:g.level);ret(g==null?-50:0);});
		l.register(null,"SetWindowGroupParent",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null)g.parent=arg(1);ret(g==null?-50:0);});
		l.register(null,"GetWindowGroupParent",(r,c)->{WindowGroup g=windowGroups.get(arg(0));ret(g==null?0:g.parent);});
		l.register(null,"SetWindowGroupOwner",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null)g.owner=arg(1);ret(g==null?-50:0);});
		l.register(null,"GetWindowGroupOwner",(r,c)->{WindowGroup g=windowGroups.get(arg(0));ret(g==null?0:g.owner);});
		l.register(null,"ChangeWindowGroupAttributes",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null)g.attributes=(g.attributes|arg(1))&~arg(2);ret(g==null?-50:0);});
		l.register(null,"GetWindowGroupAttributes",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),g==null?0:g.attributes);ret(g==null?-50:0);});
		l.register(null,"SetWindowGroupName",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(g!=null)g.name=arg(1)==0?0:rt.libraries.coreFoundation.retain(arg(1));ret(g==null?-50:0);});
		l.register(null,"CopyWindowGroupName",(r,c)->{WindowGroup g=windowGroups.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),g==null||g.name==0?0:rt.libraries.coreFoundation.retain(g.name));ret(g==null?-50:0);});
		l.register(null,"SendWindowGroupBehind",(r,c)->ret(0));
		l.register(null,"CountWindowGroupContents",(r,c)->{int n=0;for(int w:windows())if(groupOf(w)==arg(0))n++;ret(n);});
		l.register(null,"GetWindowGroupContents",(r,c)->{ // (group, options, allowedItems, *numItems, items[])
			int n=0;for(int w:windows())if(groupOf(w)==arg(0)&&n<arg(2)){if(arg(4)!=0)memory.writeLong(arg(4)+4*n,w);n++;}if(arg(3)!=0)memory.writeLong(arg(3),n);ret(0);});
		l.register(null,"GetIndexedWindow",(r,c)->{ // (group, index (1-based), options, *outWindow)
			int n=0,found=0;for(int w:windows())if(groupOf(w)==arg(0)&&++n==arg(1)){found=w;break;}if(arg(3)!=0)memory.writeLong(arg(3),found);ret(found==0?-50:0);});
		l.register(null,"GetWindowIndex",(r,c)->{int n=0,index=0;for(int w:windows()){if(arg(1)==0||groupOf(w)==arg(1))n++;if(w==arg(0)){index=n;break;}}if(arg(3)!=0)memory.writeLong(arg(3),index);ret(index==0?-50:0);});
		// ---- standard alerts (CFString form): run through the Dialog Manager's StandardAlert
		l.register(null,"GetStandardAlertDefaultParams",(r,c)->{ // (AlertStdCFStringAlertParamRec*, version)
			int p=arg(0);if(p==0){ret(-50);return;}memory.fill(p,28,0);memory.writeLong(p,arg(1));memory.writeByte(p+4,1);memory.writeLong(p+6,-1);memory.writeWord(p+18,1);ret(0);});
		l.register(null,"CreateStandardAlert",(r,c)->{ // (alertType, error, explanation, param, *outAlert)
			StandardAlert a=new StandardAlert();a.type=(short)arg(0);a.error=arg(1)==0?0:rt.libraries.coreFoundation.retain(arg(1));a.explanation=arg(2)==0?0:rt.libraries.coreFoundation.retain(arg(2));
			if(arg(3)!=0){a.params=r.allocate(28,4);memory.copy(arg(3),a.params,28);for(int at:new int[]{6,10,14}){int s=memory.readLong(a.params+at);if(s!=0&&s!=-1&&s!=-2&&s!=-3)rt.libraries.coreFoundation.retain(s);}}
			int token=r.allocate(8,4);standardAlerts.put(token,a);if(arg(4)!=0)memory.writeLong(arg(4),token);ret(0);});
		l.register(null,"RunStandardAlert",(r,c)->{ // (alert, filterProc, *outItemHit): the alert is disposed afterwards
			StandardAlert a=standardAlerts.remove(arg(0));if(a==null){ret(-50);return;}
			int err=pascal(a.error==0?"":rt.libraries.textServices.cfText(a.error)),exp=a.explanation==0?0:pascal(rt.libraries.textServices.cfText(a.explanation));
			int classic=0;List<Integer> strings=new ArrayList<Integer>();
			if(a.params!=0){classic=r.allocate(24,2);memory.writeByte(classic,memory.readByte(a.params+4));memory.writeByte(classic+1,memory.readByte(a.params+5));memory.writeLong(classic+2,arg(1));
				for(int i=0;i<3;i++){int s=memory.readLong(a.params+6+4*i);int v=s==-1||s==-2||s==-3||s==0?s:pascal(rt.libraries.textServices.cfText(s));if(v!=s)strings.add(v);memory.writeLong(classic+6+4*i,v);}
				memory.writeWord(classic+18,memory.readWord(a.params+18));memory.writeWord(classic+20,memory.readWord(a.params+20));memory.writeWord(classic+22,memory.readWord(a.params+22));}
			int hit=r.allocate(2,2);int result=rt.toolbox("StandardAlert",a.type,err,exp,classic,hit);
			if(arg(2)!=0)memory.writeWord(arg(2),memory.readWord(hit));
			memory.free(hit);memory.free(err);if(exp!=0)memory.free(exp);if(classic!=0)memory.free(classic);for(int s:strings)memory.free(s);
			disposeAlert(a);memory.free(arg(0));ret(result);});
		// ---- folder notifications (Folders.h, CarbonLib 1.1+): nobody else watches folders here, so a notification reaches no one
		for(String name:new String[]{"FNNotify","FNNotifyByPath","FNNotifyAll","FNUnsubscribe"})l.register(null,name,(r,c)->ret(0));
		l.register(null,"FNSubscribe",(r,c)->{if(arg(4)!=0)memory.writeLong(arg(4),r.allocate(4,4));ret(0);});       // (directory, callback, refcon, flags, *subscription)
		l.register(null,"FNSubscribeByPath",(r,c)->{if(arg(4)!=0)memory.writeLong(arg(4),r.allocate(4,4));ret(0);});
		l.register(null,"FNGetDirectoryForSubscription",(r,c)->ret(-50));
		// ---- the Mac OS X about box, as a plain alert with the program's name and version ('vers' 1)
		l.register(null,"StandardInternalAboutBox",(r,c)->{
			String version="";int vers=rt.toolbox("GetResource",0x76657273,1);if(vers!=0&&memory.readLong(vers)!=0){int p=memory.readLong(vers)+6;version=rt.toolbox.readPascal(p);}
			int title=pascal(rt.app.name),text=pascal(version.isEmpty()?"":"Version "+version),hit=r.allocate(2,2);
			rt.toolbox("StandardAlert",3,title,text,0,hit);memory.free(hit);memory.free(title);memory.free(text);ret(0);}); // kAlertPlainAlert
		// ---- QuickTime 4.1's load state: every movie here is loaded completely when it opens
		l.register(null,"GetMovieLoadState",(r,c)->ret(arg(0)==0?-2:100000)); // kMovieLoadStateError / kMovieLoadStateComplete
		// ---- standard sheets (CarbonLib 1.3+): on Mac OS 9 the alert runs as a modal alert when shown, and its button
		// reaches the notify target as kHICommandOK / kHICommandCancel / kHICommandOther
		l.register(null,"CreateStandardSheet",(r,c)->{ // (alertType, error, explanation, param, notifyTarget, *outSheet)
			StandardAlert a=new StandardAlert();a.type=(short)arg(0);a.error=arg(1)==0?0:rt.libraries.coreFoundation.retain(arg(1));a.explanation=arg(2)==0?0:rt.libraries.coreFoundation.retain(arg(2));a.notify=arg(4);a.sheet=true;
			if(arg(3)!=0){a.params=r.allocate(28,4);memory.copy(arg(3),a.params,28);for(int at:new int[]{6,10,14}){int s2=memory.readLong(a.params+at);if(s2!=0&&s2!=-1&&s2!=-2&&s2!=-3)rt.libraries.coreFoundation.retain(s2);}}
			int token=r.allocate(8,4);standardAlerts.put(token,a);if(arg(5)!=0)memory.writeLong(arg(5),token);ret(0);});
		l.register(null,"CloseStandardSheet",(r,c)->{StandardAlert a=standardAlerts.remove(arg(0));if(a==null){ret(-50);return;}disposeAlert(a);memory.free(arg(0));ret(0);});
		// ---- Mac OS X's Dock: no Dock here
		for(String name:new String[]{"SetApplicationDockTileImage","OverlayApplicationDockTileImage","RestoreApplicationDockTileImage","UpdateCollapsedWindowDockTile","EndQDContextForApplicationDockTile","EndCGContextForApplicationDockTile","ReleaseQDContextForCollapsedWindowDockTile"})l.register(null,name,(r,c)->ret(0));
		l.register(null,"BeginQDContextForApplicationDockTile",(r,c)->ret(0));             // CGrafPtr: none
		l.register(null,"BeginCGContextForApplicationDockTile",(r,c)->ret(0));
		l.register(null,"CreateQDContextForCollapsedWindowDockTile",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(-50);});
		// ---- Quartz and other Mac OS X-only services: not on this system
		l.register(null,"CreateCGImageFromPixMaps",(r,c)->{if(arg(2)!=0)memory.writeLong(arg(2),0);ret(-4);}); // unimpErr
		l.register(null,"CreateNewPortForCGDisplayID",(r,c)->ret(0));
		l.register(null,"ConvertBundlePreLocator",(r,c)->ret(-50));
		l.register(null,"QDDisplayWaitCursor",(r,c)->{});
		l.register(null,"SetQDError",(r,c)->memory.writeWord(0xd6e,arg(0))); // QDErr
		// ---- controls: enabling (CarbonLib 1.3+: an inactive control is a disabled one here) and kinds
		l.register(null,"EnableControl",(r,c)->{rt.toolbox("HiliteControl",arg(0),0);ret(0);});
		l.register(null,"DisableControl",(r,c)->{rt.toolbox("HiliteControl",arg(0),255);ret(0);});
		l.register(null,"IsControlEnabled",(r,c)->{int p=memory.readLong(arg(0));ret(p!=0&&memory.readByte(p+17)!=255?1:0);});
		l.register(null,"GetControlKind",(r,c)->{ // (control, *ControlKind {signature 'appl', kind}): the kind of the control's definition
			int p=memory.readLong(arg(0));int id=p==0?0:(rt.toolbox("GetControlVariant",arg(0))&0xffff);int proc=p==0?0:procIdOf(arg(0));String kind=controlKind(proc);
			if(arg(1)!=0){memory.writeLong(arg(1),0x6170706c);memory.writeLong(arg(1)+4,kind==null?0x3f3f3f3f:fourcc(kind));}ret(0);});
		l.register(null,"CreateRoundButtonControl",(r,c)->{int ctl=rt.toolbox("NewControl",arg(0),arg(1),blankTitle(),1,0,0,1,368,0);if(arg(4)!=0)memory.writeLong(arg(4),ctl);ret(ctl==0?-108:0);}); // a round bevel: a push button here
		l.register(null,"CreateDisclosureButtonControl",(r,c)->{int ctl=rt.toolbox("NewControl",arg(0),arg(1),blankTitle(),1,arg(2),0,1,64,0);if(arg(4)!=0)memory.writeLong(arg(4),ctl);ret(ctl==0?-108:0);}); // (window, bounds, value, autoToggle, *out): a disclosure triangle
		for(String n:new String[]{"New","Dispose","Invoke"}){final String k=n;l.register(null,k+"EditUnicodePostUpdateUPP",(r,c)->{if(k.equals("New"))ret(r.newRoutineDescriptor(arg(0),0xfd1,PpcRuntime.ISA_PPC));else if(k.equals("Invoke"))ret(r.callUniversal(arg(4),0xfd1,new int[]{arg(0),arg(1),arg(2),arg(3)}));});}
		// ---- more window calls of the 3.4 stub
		l.register(null,"GetWindowActivationScope",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),windowClass(arg(0))==5||windowClass(arg(0))==8?1:0);ret(0);}); // kWindowActivationScopeNone 0 / Independent 1
		l.register(null,"SetWindowActivationScope",(r,c)->ret(0));
		l.register(null,"GetWindowWidgetHilite",(r,c)->{if(arg(1)!=0)memory.writeWord(arg(1),0);ret(0);});
		l.register(null,"TransitionWindowAndParent",(r,c)->{int action=arg(3);if(action==1)rt.toolbox("ShowWindow",arg(0));else if(action==2)rt.toolbox("HideWindow",arg(0));ret(0);}); // (window, parent, effect, action, rect)
		l.register(null,"IsWindowContainedInGroup",(r,c)->ret(groupOf(arg(0))==arg(1)?1:0));
		l.register(null,"GetWindowGroupSibling",(r,c)->ret(0));
		l.register(null,"DebugPrintWindowGroup",(r,c)->{WindowGroup g=windowGroups.get(arg(0));System.err.println("[ppc "+rt.app.name+"] window group "+Integer.toHexString(arg(0))+(g==null?" (none)":" level "+g.level+" attributes "+Integer.toHexString(g.attributes)));});
		l.register(null,"DebugPrintAllWindowGroups",(r,c)->{for(Map.Entry<Integer,WindowGroup> e:windowGroups.entrySet())System.err.println("[ppc "+rt.app.name+"] window group "+Integer.toHexString(e.getKey())+" level "+e.getValue().level);});
		// ---- TSMTE dialogs (TSMTE.h): no inline input in dialog text here
		l.register(null,"IsTSMTEDialog",(r,c)->ret(0));
		for(String name:new String[]{"GetTSMTEDialogDocumentID","GetTSMTEDialogTSMTERecHandle"})l.register(null,name,(r,c)->ret(0));
		for(String name:new String[]{"SetTSMTEDialogDocumentID","SetTSMTEDialogTSMTERecHandle"})l.register(null,name,(r,c)->{});
		// ---- Apple event descriptors (CarbonLib 1.x additions): flattening in a layout of this layer ('type', kind, size, data / items)
		l.register(null,"AEInitializeDesc",(r,c)->{if(arg(0)!=0){memory.writeLong(arg(0),0x6e756c6c);memory.writeLong(arg(0)+4,0);}});
		l.register(null,"AECheckIsRecord",(r,c)->ret(descKind(arg(0))==2?1:0));
		l.register(null,"AESizeOfFlattenedDesc",(r,c)->{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();flatten(arg(0),out);ret(out.size());});
		l.register(null,"AEFlattenDesc",(r,c)->{ // (desc, buffer, bufferSize, *actualSize)
			java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();flatten(arg(0),out);byte[] b=out.toByteArray();if(arg(3)!=0)memory.writeLong(arg(3),b.length);
			if(b.length>arg(2)){ret(-1707);return;}memory.load(arg(1),b);ret(0);}); // errAEBufferTooSmall
		l.register(null,"AEUnflattenDesc",(r,c)->{byte[] b=memory.readBytes(arg(0),Math.max(0,Math.min(1<<24,arg(0)==0?0:r.toolbox("GetPtrSize",arg(0))>0?r.toolbox("GetPtrSize",arg(0)):65536)));int[] at={0};ret(unflatten(b,at,arg(1)));});
		// ---- Interface Builder nib files: PpcNibs
		defineLaunchServices(l);
	}
	/** A standard sheet shown: the alert runs (modally), then its button goes to the notify target as an HICommand and the sheet is gone. */
	private void runStandardSheet(int token,StandardAlert a) throws M68kException {
		standardAlerts.remove(token);
		int err=pascal(a.error==0?"":rt.libraries.textServices.cfText(a.error)),exp=a.explanation==0?0:pascal(rt.libraries.textServices.cfText(a.explanation));
		int hit=rt.allocate(2,2);rt.toolbox("StandardAlert",a.type,err,exp,0,hit);int item=(short)memory.readWord(hit);memory.free(hit);memory.free(err);if(exp!=0)memory.free(exp);
		int command=item==2?0x6e6f7421:item==3?0x6f746872:0x6f6b2020; // kHICommandCancel 'not!', kHICommandOther 'othr', kHICommandOK 'ok  '
		byte[] hi=new byte[14];System.arraycopy(longs(command),0,hi,4,4);Event e=command(hi);
		try{if(a.notify!=0)dispatch(a.notify,e);}finally{release(e);}
		disposeAlert(a);memory.free(token);
	}
	/** 0 plain, 1 list, 2 record (an AE record under any type) - by what the Apple Event Manager answers for it. */
	private int descKind(int desc) throws M68kException {
		if(desc==0)return 0;int type=memory.readLong(desc);if(type==0x6c697374)return 1;if(type==0x7265636f||type==0x61657674)return 2;
		int n=rt.allocate(4,4);int err=(short)rt.toolbox("AECountItems",desc,n);memory.free(n);return err==0?2:0;
	}
	/** A descriptor tree as bytes: type, kind (0 plain, 1 list, 2 record), then the data or the items (records with their keywords). */
	private void flatten(int desc,java.io.ByteArrayOutputStream out) throws M68kException {
		int type=memory.readLong(desc),kind=descKind(desc);out.writeBytes(longs(type));out.write(kind);
		if(kind==0){int h=memory.readLong(desc+4);int n=h==0?0:rt.toolbox("GetHandleSize",h);out.writeBytes(longs(n));if(n>0)out.writeBytes(memory.readBytes(memory.readLong(h),n));return;}
		int count=rt.allocate(4,4);rt.toolbox("AECountItems",desc,count);int n=memory.readLong(count);memory.free(count);out.writeBytes(longs(n));
		int item=rt.allocate(8,4),key=rt.allocate(4,4),actual=0;
		try{for(int i=1;i<=n;i++){if((short)rt.toolbox("AEGetNthDesc",desc,i,0x2a2a2a2a,key,item)!=0)break;if(kind==2)out.writeBytes(longs(memory.readLong(key)));flatten(item,out);rt.toolbox("AEDisposeDesc",item);}}
		finally{memory.free(item);memory.free(key);}
	}
	/** Rebuilds a descriptor flattened by {@link #flatten} into {@code desc}; returns the OSErr. */
	private int unflatten(byte[] b,int[] at,int desc) throws M68kException {
		if(at[0]+9>b.length)return -1702; // errAEBadListItem... (errAECorruptData)
		int type=u4(b,at[0]),kind=b[at[0]+4];at[0]+=5;
		if(kind==0){int n=u4(b,at[0]);at[0]+=4;int p=rt.allocate(Math.max(1,n),4);memory.load(p,java.util.Arrays.copyOfRange(b,at[0],at[0]+n));at[0]+=n;int err=rt.toolbox("AECreateDesc",type,p,n,desc);memory.free(p);return (short)err;}
		int n=u4(b,at[0]);at[0]+=4;
		int err=(short)rt.toolbox("AECreateList",0,0,kind==2?1:0,desc);if(err!=0)return err;
		int item=rt.allocate(8,4);
		try{for(int i=1;i<=n;i++){int key=0;if(kind==2){key=u4(b,at[0]);at[0]+=4;}err=unflatten(b,at,item);if(err!=0)return err;
			err=(short)(kind==2?rt.toolbox("AEPutParamDesc",desc,key,item):rt.toolbox("AEPutDesc",desc,0,item));rt.toolbox("AEDisposeDesc",item);if(err!=0)return err;}}
		finally{memory.free(item);}
		if(type!=0x6c697374&&type!=0x7265636f)memory.writeLong(desc,type); // a record of another type keeps its type
		return 0;
	}
	private static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;}
	private int blankTitle() throws M68kCpuException {int p=rt.allocate(4,2);return p;}
	/** A control's procID (variant | definition ID << 4) from its definition Handle's resource ID, if the Control Manager says. */
	private int procIdOf(int control) throws M68kException {int p=memory.readLong(control);int def=memory.readLong(p+24);int id=0;
		if(def!=0){int idp=rt.allocate(4,4),type=rt.allocate(4,4),name=rt.allocate(256,2);rt.toolbox("GetResInfo",def,idp,type,name);id=(short)memory.readWord(idp);memory.free(idp);memory.free(type);memory.free(name);}
		return (id<<4)|(rt.toolbox("GetControlVariant",control)&15);}
	/** ControlKind codes (Controls.h, CarbonLib 1.1+) by procID. */
	private static String controlKind(int proc){
		int def=proc>>4;
		switch(def){
			case 0:return (proc&15)==1?"cbox":(proc&15)==2?"rdio":"push";case 1:return "sbar";case 2:return "bevl";case 4:return "sldr";case 5:return "prgb";case 6:return "larr";case 7:return "carr";
			case 8:return "tabs";case 9:return "sepa";case 10:return "grpb";case 11:return "wel ";case 12:return "popa";case 13:return "dscl";case 14:return "plac";case 15:return "clck";
			case 16:return "upan";case 17:return "etxt";case 18:return "stxt";case 19:return "pict";case 20:return "icon";case 21:return "wind";case 22:return "lbox";case 23:return "push";
			case 24:return "rgrp";case 25:return "rdio";case 26:return "sbar";case 27:return "stxb";case 63:return "popb";
			default:return null;
		}
	}
	private void disposeAlert(StandardAlert a) throws M68kException {
		PpcCoreFoundation cf=rt.libraries.coreFoundation;if(a.error!=0)cf.release(a.error);if(a.explanation!=0)cf.release(a.explanation);
		if(a.params!=0){for(int at:new int[]{6,10,14}){int s=memory.readLong(a.params+at);if(s!=0&&s!=-1&&s!=-2&&s!=-3)cf.release(s);}memory.free(a.params);}
	}

	// ---- Launch Services (CarbonLib 1.3+): opening documents and applications, item information

	private static final int LS_APPLICATION_NOT_FOUND=-10814,LS_DATA_UNAVAILABLE=-10813;
	private void defineLaunchServices(PpcLibraries l){
		l.register(null,"LSInit",(r,c)->ret(0));
		l.register(null,"LSTerm",(r,c)->ret(0));
		l.register(null,"LSOpenFSRef",(r,c)->{java.io.File f=fsRefFile(arg(0));ret(f==null?-50:open(f,null,arg(1)));});
		l.register(null,"LSOpenCFURLRef",(r,c)->{ // (url, *launchedURL): a file URL opens the file, others go to the host's browser
			PpcCoreFoundation.CFURL u=rt.libraries.coreFoundation.url(arg(0));if(u==null){ret(-50);return;}
			java.io.File f=rt.libraries.coreFoundation.fileOf(u);
			if(f!=null){int result=open(f,null,0);if(result==0&&arg(1)!=0)memory.writeLong(arg(1),rt.libraries.coreFoundation.retain(arg(0)));ret(result);return;}
			ret(openURL(u.absolute()));});
		l.register(null,"LSOpenFromRefSpec",(r,c)->{ // (LSLaunchFSRefSpec*: appRef, numDocs, itemRefs, passThruParams, launchFlags, asyncRefCon; *outLaunchedRef)
			int spec=arg(0);if(spec==0){ret(-50);return;}
			java.io.File app=fsRefFile(memory.readLong(spec));List<java.io.File> docs=new ArrayList<java.io.File>();int n=memory.readLong(spec+4),items=memory.readLong(spec+8);
			for(int i=0;i<n&&items!=0;i++){java.io.File d=fsRefFile(items+80*i);if(d!=null)docs.add(d);}
			if(app==null&&docs.isEmpty()){ret(-50);return;}
			ret(app!=null?launch(app,docs,arg(1)):open(docs.get(0),docs,arg(1)));});
		l.register(null,"LSOpenFromURLSpec",(r,c)->{ // (LSLaunchURLSpec*: appURL, itemURLs, passThruParams, launchFlags, asyncRefCon; *outLaunchedURL)
			int spec=arg(0);if(spec==0){ret(-50);return;}PpcCoreFoundation cf=rt.libraries.coreFoundation;
			PpcCoreFoundation.CFURL app=cf.url(memory.readLong(spec));PpcCoreFoundation.CFArray items=cf.array(memory.readLong(spec+4));List<java.io.File> docs=new ArrayList<java.io.File>();
			if(items!=null)for(int v:items.values){PpcCoreFoundation.CFURL u=cf.url(v);java.io.File f=u==null?null:cf.fileOf(u);if(f!=null)docs.add(f);else if(u!=null)openURL(u.absolute());}
			java.io.File a=app==null?null:cf.fileOf(app);
			if(a!=null){ret(launch(a,docs,0));return;}ret(docs.isEmpty()?0:open(docs.get(0),docs,0));});
		l.register(null,"LSCopyItemInfoForRef",(r,c)->{java.io.File f=fsRefFile(arg(0));ret(f==null?-50:itemInfo(f,arg(1),arg(2)));}); // (ref, whichInfo, *LSItemInfoRecord)
		l.register(null,"LSCopyItemInfoForURL",(r,c)->{PpcCoreFoundation.CFURL u=rt.libraries.coreFoundation.url(arg(0));java.io.File f=u==null?null:rt.libraries.coreFoundation.fileOf(u);ret(f==null?-43:itemInfo(f,arg(1),arg(2)));});
		l.register(null,"LSCopyDisplayNameForRef",(r,c)->{java.io.File f=fsRefFile(arg(0));if(f==null){ret(-50);return;}if(arg(1)!=0)memory.writeLong(arg(1),rt.libraries.textServices.newCFString(displayName(f)));ret(0);});
		l.register(null,"LSCopyDisplayNameForURL",(r,c)->{PpcCoreFoundation.CFURL u=rt.libraries.coreFoundation.url(arg(0));java.io.File f=u==null?null:rt.libraries.coreFoundation.fileOf(u);if(f==null){ret(-43);return;}if(arg(1)!=0)memory.writeLong(arg(1),rt.libraries.textServices.newCFString(displayName(f)));ret(0);});
		l.register(null,"LSCopyKindStringForURL",(r,c)->{PpcCoreFoundation.CFURL u=rt.libraries.coreFoundation.url(arg(0));java.io.File f=u==null?null:rt.libraries.coreFoundation.fileOf(u);if(f==null){ret(-43);return;}if(arg(1)!=0)memory.writeLong(arg(1),rt.libraries.textServices.newCFString(f.isDirectory()?"Folder":MacApp68k.isApplication(f)?"Application":"Document"));ret(0);});
		l.register(null,"LSCopyKindStringForRef",(r,c)->{java.io.File f=fsRefFile(arg(0));if(f==null){ret(-50);return;}if(arg(1)!=0)memory.writeLong(arg(1),rt.libraries.textServices.newCFString(f.isDirectory()?"Folder":MacApp68k.isApplication(f)?"Application":"Document"));ret(0);});
		l.register(null,"LSSetExtensionHiddenForRef",(r,c)->ret(0));
		l.register(null,"LSGetExtensionInfo",(r,c)->{ // (nameLength, UniChar name[], *outExtStartIndex): after the last dot, kLSInvalidExtensionIndex when none
			int n=arg(0),p=arg(1),dot=-1;for(int i=n-1;i>0;i--){int ch=memory.readWord(p+2*i);if(ch=='.'){dot=i+1;break;}if(ch==' ')break;}
			if(arg(2)!=0)memory.writeLong(arg(2),dot<0||dot>=n?-1:dot);ret(0);});
		l.register(null,"LSGetApplicationForItem",(r,c)->{ // (ref, roleMask, *outAppRef, *outAppURL)
			java.io.File f=fsRefFile(arg(0));if(f==null){ret(-50);return;}java.io.File app=MacApp68k.isApplication(f)?f:applicationFor(rt.toolbox.files.fileCreator(f));
			ret(answerApplication(app,arg(2),arg(3)));});
		l.register(null,"LSGetApplicationForURL",(r,c)->{PpcCoreFoundation.CFURL u=rt.libraries.coreFoundation.url(arg(0));java.io.File f=u==null?null:rt.libraries.coreFoundation.fileOf(u);
			java.io.File app=f==null?null:MacApp68k.isApplication(f)?f:applicationFor(rt.toolbox.files.fileCreator(f));ret(answerApplication(app,arg(2),arg(3)));});
		l.register(null,"LSGetApplicationForInfo",(r,c)->ret(answerApplication(applicationFor(arg(1)),arg(4),arg(5)))); // (type, creator, extension, roleMask, *outAppRef, *outAppURL)
		l.register(null,"LSFindApplicationForInfo",(r,c)->{ // (creator, bundleID, name, *outAppRef, *outAppURL)
			java.io.File app=arg(0)!=0x3f3f3f3f&&arg(0)!=0?applicationFor(arg(0)):null;
			if(app==null&&arg(2)!=0){String name=rt.libraries.textServices.cfText(arg(2));if(name!=null&&name.equals(rt.app.name))app=rt.app.file;}
			ret(answerApplication(app,arg(3),arg(4)));});
		l.register(null,"LSCanRefAcceptItem",(r,c)->{if(arg(4)!=0)memory.writeByte(arg(4),MacApp68k.isApplication(fsRefFile(arg(1)))?1:0);ret(0);});
		l.register(null,"LSCanURLAcceptURL",(r,c)->{if(arg(4)!=0)memory.writeByte(arg(4),0);ret(0);}); // (item, target, role, flags, *outAccepts): no drag-and-drop matching here
	}
	/** Opens a file as the Finder would: an application is launched, a document goes to its creator (this program included). */
	private int open(java.io.File f,List<java.io.File> docs,int launchedRef) throws M68kException {
		if(f.isDirectory()&&!MacApp68k.isApplication(f))return -50;
		if(MacApp68k.isApplication(f))return launch(f,new ArrayList<java.io.File>(),launchedRef);
		List<java.io.File> all=docs!=null?docs:java.util.Collections.singletonList(f);
		int creator=rt.toolbox.files.fileCreator(f);
		if(creator!=0&&creator==rt.toolbox.files.fileCreator(rt.app.file)){rt.app.openDocuments(new ArrayList<java.io.File>(all));if(launchedRef!=0)writeFSRef(launchedRef,rt.app.file);return 0;}
		java.io.File app=applicationFor(creator);
		if(app==null)return LS_APPLICATION_NOT_FOUND;
		return launch(app,all,launchedRef);
	}
	private int launch(java.io.File app,List<java.io.File> docs,int launchedRef) throws M68kException {
		if(!Sandbox.allowFile(app,Sandbox.Access.EXECUTE,rt.toolbox.name))return -54; // permErr
		try{MacApp68k.launch(app,docs);}catch(java.io.IOException e){return -43;}
		if(launchedRef!=0)writeFSRef(launchedRef,app);return 0;
	}
	private int openURL(String url){
		try{java.net.URI uri=new java.net.URI(url);
			if(uri.getScheme()==null||!Sandbox.allowOther(rt.toolbox.name,Sandbox.OPEN_URL,url)||!java.awt.Desktop.isDesktopSupported())return LS_APPLICATION_NOT_FOUND;
			java.awt.Desktop.getDesktop().browse(uri);return 0;}catch(Exception e){return LS_APPLICATION_NOT_FOUND;}
	}
	/** The application with a creator code on the mounted volumes (the running program first), or null. */
	private java.io.File applicationFor(int creator){
		if(creator==0||creator==0x3f3f3f3f)return null;
		if(creator==rt.toolbox.files.fileCreator(rt.app.file))return rt.app.file;
		return rt.toolbox.files.fileManager.findApplication(creator,0);
	}
	private int answerApplication(java.io.File app,int outRef,int outURL) throws M68kException {
		if(app==null)return LS_APPLICATION_NOT_FOUND;
		if(outRef!=0)writeFSRef(outRef,app);
		if(outURL!=0){PpcCoreFoundation cf=rt.libraries.coreFoundation;memory.writeLong(outURL,cf.newURL(cf.fileURLString(app,app.isDirectory()),0));}
		return 0;
	}
	private String displayName(java.io.File f){try{return rt.toolbox.files.fileManager.volumeRoot(f)?rt.toolbox.files.fileManager.volumeNameOf(f):rt.toolbox.files.fileManager.macNameOf(f);}catch(java.io.IOException e){return f.getName();}}
	/** LSItemInfoRecord {flags, filetype, creator, extension (CFString), iconFileName, kindID} for kLSRequestAllInfo-style requests. */
	private int itemInfo(java.io.File f,int which,int out) throws M68kException {
		if(out==0)return -50;memory.fill(out,24,0);
		int flags=0;boolean app=MacApp68k.isApplication(f);
		if(f.isDirectory())flags|=app?0x2|0x4|0x80:0x8;else flags|=app?0x1|0x4:0x1; // kLSItemInfoIsPlainFile 1, IsPackage 2, IsApplication 4, IsContainer 8, IsVolume 0x40?, ...
		if(app)flags|=0x4;
		if(rt.toolbox.files.fileManager.volumeRoot(f))flags=0x8|0x80; // container | volume
		memory.writeLong(out,flags);
		if(!f.isDirectory()){memory.writeLong(out+4,rt.toolbox.files.fileType(f));memory.writeLong(out+8,rt.toolbox.files.fileCreator(f));}
		String name=f.getName();int dot=name.lastIndexOf('.');
		if((which&0x10)!=0&&dot>0&&dot<name.length()-1)memory.writeLong(out+12,rt.libraries.textServices.newCFString(name.substring(dot+1))); // kLSRequestExtension
		return 0;
	}

	// ------------------------------------------------------------------ Carbon events

	/** Pseudo-addresses of the targets and queue that are not objects of their own. */
	static final int DISPATCHER_TARGET=0x44535054,MAIN_QUEUE=0x51554555;
	static final int CLASS_APPLE_EVENT=0x65707063,CLASS_TEXT_INPUT=0x74657874,CLASS_CONTROL=0x636e746c;
	private static final int P_CPRT=0x63707274,T_CPRT=0x63707274,T_CONTROL=0x6374726c; // kEventParamControlPart, typeControlPartCode, typeControlRef
	static final int EVENT_LOOP_TIMED_OUT=-9875,EVENT_PARAMETER_NOT_FOUND=-9870,EVENT_ALREADY_POSTED=-9860,EVENT_NOT_IN_QUEUE=-9877;
	/** An installed handler: which event kinds (class<<32|kind), the UPP, its refcon. */
	private static final class Handler { final int target,upp,userData;final List<Long> types=new ArrayList<Long>();int ref;Handler(int target,int upp,int userData){this.target=target;this.upp=upp;this.userData=userData;} }
	private final List<Handler> handlers=new ArrayList<Handler>();
	private static final class Timer { int upp,userData,ref;double next,interval;boolean removed; }
	private final List<Timer> timers=new ArrayList<Timer>();
	/**
	 * An event: class, kind, time, parameters by name (type, bytes) and a retain count; {@code record} is the classic
	 * EventRecord a pumped event came from (its standard handling runs when the handlers leave it alone).
	 */
	private static final class Event { int eventClass,kind,ref,references=1,attributes;double time;byte[] record;boolean standardDone;final Map<Integer,Object[]> params=new java.util.LinkedHashMap<Integer,Object[]>(); }
	private final Map<Integer,Event> events=new HashMap<Integer,Event>();
	/** GetEventAttributes (PpcCarbonToolbox): the attributes an event was made with, 0 for an unknown EventRef. */
	int eventAttributes(int ref){Event e=events.get(ref);return e==null?0:e.attributes;}
	/** Events posted to the main queue (PostEventToQueue) or pumped but not yet received. */
	private final List<Event> queue=new ArrayList<Event>();
	/** A dispatch in progress, for CallNextEventHandler: the event, the handlers still to run and the target chain. */
	private static final class Call { final Event event;final List<Handler> rest;boolean standard;Call(Event event,List<Handler> rest){this.event=event;this.rest=rest;} }
	private final Map<Integer,Call> calls=new HashMap<Integer,Call>();
	private boolean quitLoop;
	private final long startNanos=System.nanoTime();
	private double now(){return (System.nanoTime()-startNanos)/1e9;}
	/** Registered hot keys: {keyCode, modifiers, signature, id, target, ref}. */
	private final List<int[]> hotKeys=new ArrayList<int[]>();
	private int lastClickWhen,lastClickWhere,clickCount,mouseModifiers;
	private static int fourcc(String s){return (s.charAt(0)&0xff)<<24|(s.charAt(1)&0xff)<<16|(s.charAt(2)&0xff)<<8|s.charAt(3)&0xff;}
	private static final int P_DIRECT=0x2d2d2d2d,P_WINDOW=0x77696e64,P_MLOC=0x6d6c6f63,P_KMOD=0x6b6d6f64,P_MBTN=0x6d62746e,P_CCNT=0x63636e74,P_KCHR=0x6b636872,P_KCOD=0x6b636f64,
		P_KBDT=0x6b626474,P_ATTR=0x61747472,P_ORIG=0x6f726967,P_PREV=0x70726576,P_CRCT=0x63726374,P_WPAR=0x77706172,P_MENU=0x6d656e75,P_EVCL=0x6576636c,P_EVID=0x65766964;
	private static final int T_WINDOW=0x77696e64,T_POINT=0x51447074,T_UINT32=0x6d61676e,T_CHAR=0x54455854,T_RECT=0x71647274,T_MBTN=0x6d62746e,T_HICMD=0x68636d64,T_MENU=0x6d656e75,T_SINT16=0x73686f72,T_TYPE=0x74797065,T_HOTKEY=0x686b6964;

	private void defineEvents(PpcLibraries l){
		l.register(null,"GetApplicationEventTarget",(r,c)->ret(APPLICATION_TARGET));
		l.register(null,"GetEventDispatcherTarget",(r,c)->ret(DISPATCHER_TARGET));
		l.register(null,"GetWindowEventTarget",(r,c)->ret(arg(0)));
		l.register(null,"GetMenuEventTarget",(r,c)->ret(arg(0)));
		l.register(null,"GetControlEventTarget",(r,c)->ret(arg(0)));
		l.register(null,"GetUserFocusEventTarget",(r,c)->{int w=rt.toolbox("FrontWindow");ret(w!=0?w:APPLICATION_TARGET);});
		l.register(null,"GetMainEventLoop",(r,c)->ret(1));
		l.register(null,"GetCurrentEventLoop",(r,c)->ret(1));
		l.register(null,"GetMainEventQueue",(r,c)->ret(MAIN_QUEUE));
		l.register(null,"GetCurrentEventQueue",(r,c)->ret(MAIN_QUEUE));
		l.register(null,"GetCurrentEventTime",(r,c)->rt.retD(now()));
		l.register(null,"GetLastUserEventTime",(r,c)->rt.retD(now()));
		// events
		l.register(null,"CreateEvent",(r,c)->{ // (allocator, class, kind, when: slots 3-4, attributes, *outEvent)
			Event e=newEvent(arg(1),arg(2));double when=r.farg(0);e.time=when==0?now():when;e.attributes=arg(5);if(arg(6)!=0)memory.writeLong(arg(6),e.ref);ret(0);});
		l.register(null,"RetainEvent",(r,c)->{Event e=events.get(arg(0));if(e!=null)e.references++;ret(arg(0));});
		l.register(null,"ReleaseEvent",(r,c)->{Event e=events.get(arg(0));if(e!=null)release(e);});
		l.register(null,"GetEventRetainCount",(r,c)->{Event e=events.get(arg(0));ret(e==null?0:e.references);});
		l.register(null,"CopyEvent",(r,c)->{Event e=events.get(arg(0));if(e==null){ret(0);return;}Event x=newEvent(e.eventClass,e.kind);x.time=e.time;x.attributes=e.attributes;x.record=e.record;x.params.putAll(e.params);ret(x.ref);});
		l.register(null,"GetEventClass",(r,c)->{Event e=events.get(arg(0));ret(e==null?0:e.eventClass);});
		l.register(null,"GetEventKind",(r,c)->{Event e=events.get(arg(0));ret(e==null?0:e.kind);});
		l.register(null,"GetEventTime",(r,c)->{Event e=events.get(arg(0));rt.retD(e==null?0:e.time);});
		l.register(null,"SetEventTime",(r,c)->{Event e=events.get(arg(0));if(e!=null)e.time=r.farg(0);ret(0);});
		l.register(null,"GetEventParameter",(r,c)->{ // (event, name, desiredType, *actualType, bufferSize, *actualSize, outData)
			Event e=events.get(arg(0));Object[] p=e==null?null:e.params.get(arg(1));
			if(p==null){ret(EVENT_PARAMETER_NOT_FOUND);return;}
			byte[] b=coerce((Integer)p[0],(byte[])p[1],arg(2));
			if(arg(3)!=0)memory.writeLong(arg(3),arg(2)==0x2a2a2a2a||b==p[1]?(Integer)p[0]:arg(2));
			int n=Math.min(arg(4),b.length);if(arg(6)!=0&&n>0)memory.load(arg(6),b,0,n);
			if(arg(5)!=0)memory.writeLong(arg(5),b.length);
			ret(0);});
		l.register(null,"SetEventParameter",(r,c)->{Event e=events.get(arg(0));if(e!=null)e.params.put(arg(1),new Object[]{arg(2),memory.readBytes(arg(4),arg(3))});ret(0);});
		l.register(null,"ConvertEventRefToEventRecord",(r,c)->{Event e=events.get(arg(0));int out=arg(1);byte[] rec=e==null?null:eventRecordOf(e);if(out!=0)memory.load(out,rec==null?new byte[16]:rec);ret(rec!=null?1:0);});
		l.register(null,"IsUserCancelEventRef",(r,c)->{Event e=events.get(arg(0));boolean cancel=false;
			if(e!=null&&e.eventClass==CLASS_KEYBOARD&&(e.kind==1||e.kind==2)){Object[] ch=e.params.get(P_KCHR),mod=e.params.get(P_KMOD);int k=ch==null?0:((byte[])ch[1])[0]&0xff;int m=mod==null?0:toInt((byte[])mod[1]);cancel=k==27||k=='.'&&(m&0x100)!=0;}
			ret(cancel?1:0);});
		// handlers
		l.register(null,"InstallEventHandler",(r,c)->{ // (target, upp, numTypes, list, userData, *outRef)
			Handler h=new Handler(arg(0),arg(1),arg(4));addTypes(h,arg(2),arg(3));h.ref=rt.allocate(4,4);handlers.add(h);
			if(arg(5)!=0)memory.writeLong(arg(5),h.ref);ret(0);});
		l.register(null,"InstallStandardEventHandler",(r,c)->ret(0));
		l.register(null,"RemoveEventHandler",(r,c)->{final int ref=arg(0);handlers.removeIf(h->h.ref==ref);ret(0);});
		l.register(null,"AddEventTypesToHandler",(r,c)->{for(Handler h:handlers)if(h.ref==arg(0))addTypes(h,arg(1),arg(2));ret(0);});
		l.register(null,"RemoveEventTypesFromHandler",(r,c)->{for(Handler h:handlers)if(h.ref==arg(0))for(int i=0;i<arg(1);i++)h.types.remove(Long.valueOf(type(arg(2)+8*i)));ret(0);});
		l.register(null,"CallNextEventHandler",(r,c)->{Call k=calls.get(arg(0));Event e=events.get(arg(1));if(k==null||e==null){ret(EVENT_NOT_HANDLED);return;}ret(continueDispatch(k)?0:EVENT_NOT_HANDLED);});
		l.register(null,"SendEventToEventTarget",(r,c)->{Event e=events.get(arg(0));if(e==null){ret(-50);return;}ret(send(e,arg(1))?0:EVENT_NOT_HANDLED);});
		l.register(null,"ProcessHICommand",(r,c)->{Event e=command(memory.readBytes(arg(0),14));try{ret(dispatchCommand(e)?0:EVENT_NOT_HANDLED);}finally{release(e);}});
		// the queue
		l.register(null,"PostEventToQueue",(r,c)->{Event e=events.get(arg(1));if(e==null){ret(-50);return;}if(queue.contains(e)){ret(EVENT_ALREADY_POSTED);return;}e.references++;if(arg(2)==2)queue.add(0,e);else queue.add(e);ret(0);});
		l.register(null,"GetNumEventsInQueue",(r,c)->ret(queue.size()));
		l.register(null,"IsEventInQueue",(r,c)->{Event e=events.get(arg(1));ret(e!=null&&queue.contains(e)?1:0);});
		l.register(null,"RemoveEventFromQueue",(r,c)->{Event e=events.get(arg(1));if(e==null||!queue.remove(e)){ret(EVENT_NOT_IN_QUEUE);return;}release(e);ret(0);});
		l.register(null,"FlushEventQueue",(r,c)->{List<Event> old=new ArrayList<Event>(queue);queue.clear();for(Event e:old)release(e);ret(0);});
		l.register(null,"FlushEventsMatchingListFromQueue",(r,c)->{for(Event e:new ArrayList<Event>(queue))if(matches(e,arg(1),arg(2))){queue.remove(e);release(e);}ret(0);});
		l.register(null,"FlushSpecificEventsFromQueue",(r,c)->{for(Event e:new ArrayList<Event>(queue))if((rt.callUniversal(arg(1),COMPARATOR_PROC_INFO,new int[]{e.ref,arg(2)})&0xff)!=0){queue.remove(e);release(e);}ret(0);});
		l.register(null,"FindSpecificEventInQueue",(r,c)->{for(Event e:new ArrayList<Event>(queue))if((rt.callUniversal(arg(1),COMPARATOR_PROC_INFO,new int[]{e.ref,arg(2)})&0xff)!=0){ret(e.ref);return;}ret(0);});
		l.register(null,"AcquireFirstMatchingEventInQueue",(r,c)->{for(Event e:queue)if(matches(e,arg(1),arg(2))){e.references++;ret(e.ref);return;}ret(0);});
		l.register(null,"NewEventComparatorUPP",(r,c)->ret(rt.newRoutineDescriptor(arg(0),COMPARATOR_PROC_INFO,PpcRuntime.ISA_PPC)));
		l.register(null,"DisposeEventComparatorUPP",(r,c)->{});
		l.register(null,"InvokeEventComparatorUPP",(r,c)->ret(rt.callUniversal(arg(2),COMPARATOR_PROC_INFO,new int[]{arg(0),arg(1)})));
		l.register(null,"ReceiveNextEvent",(r,c)->{ // (numTypes, list, timeout: slots 2-3, pullEvent, *outEvent)
			Event e=receive(arg(0),arg(1),r.farg(0),(arg(4)&0xff)!=0);
			if(e==null){ret(quitLoop?-9876:EVENT_LOOP_TIMED_OUT);return;} // eventLoopQuitErr / eventLoopTimedOutErr
			if(arg(5)!=0)memory.writeLong(arg(5),e.ref);ret(0);});
		// timers and loops
		l.register(null,"InstallEventLoopTimer",(r,c)->{ // (loop, firstFire f1, interval f2, upp, userData, *outTimer): the doubles take slots 1-4
			Timer t=new Timer();t.next=now()+Math.max(0,r.farg(0));t.interval=r.farg(1);t.upp=arg(5);t.userData=arg(6);t.ref=rt.allocate(4,4);timers.add(t);
			if(arg(7)!=0)memory.writeLong(arg(7),t.ref);ret(0);});
		l.register(null,"InstallEventLoopIdleTimer",(r,c)->{ // an idle timer fires like a plain one here (no user-activity tracking)
			Timer t=new Timer();t.next=now()+Math.max(0,r.farg(0));t.interval=r.farg(1);t.upp=arg(5);t.userData=arg(6);t.ref=rt.allocate(4,4);timers.add(t);
			if(arg(7)!=0)memory.writeLong(arg(7),t.ref);ret(0);});
		l.register(null,"RemoveEventLoopTimer",(r,c)->{final int ref=arg(0);for(Timer t:timers)if(t.ref==ref)t.removed=true;timers.removeIf(t->t.ref==ref);ret(0);});
		l.register(null,"SetEventLoopTimerNextFireTime",(r,c)->{for(Timer t:timers)if(t.ref==arg(0))t.next=now()+r.farg(0);ret(0);});
		l.register(null,"QuitApplicationEventLoop",(r,c)->quitLoop=true);
		l.register(null,"QuitEventLoop",(r,c)->{quitLoop=true;ret(0);});
		l.register(null,"RunApplicationEventLoop",(r,c)->runLoop());
		l.register(null,"RunCurrentEventLoop",(r,c)->{idle(r.farg(0));boolean quit=quitLoop;quitLoop=false;ret(quit?-9876:EVENT_LOOP_TIMED_OUT);});
		l.register(null,"NewEventHandlerUPP",(r,c)->ret(rt.newRoutineDescriptor(arg(0),HANDLER_PROC_INFO,PpcRuntime.ISA_PPC)));
		l.register(null,"NewEventLoopTimerUPP",(r,c)->ret(rt.newRoutineDescriptor(arg(0),0x000003c0,PpcRuntime.ISA_PPC)));
		l.register(null,"NewEventLoopIdleTimerUPP",(r,c)->ret(rt.newRoutineDescriptor(arg(0),0x00000fc0,PpcRuntime.ISA_PPC)));
		// Display Manager callbacks (RezLib passes the iterator of DMGetIndexedDisplayModeFromList that NewDMDisplayModeListIteratorUPP made)
		for(Object[] u:new Object[][]{{"DMDisplayModeListIterator",0x00000fc0},{"DMComponentListIterator",0x00000fc0},{"DMNotification",0x000003c0},{"DMExtendedNotification",0x00000ec0}}){
			final int info=(Integer)u[1];
			l.register(null,"New"+u[0]+"UPP",(r,c)->ret(rt.newRoutineDescriptor(arg(0),info,PpcRuntime.ISA_PPC)));
			l.register(null,"Dispose"+u[0]+"UPP",(r,c)->rt.disposeRoutineDescriptor(arg(0)));
		}
		l.register(null,"DisposeEventHandlerUPP",(r,c)->{});
		l.register(null,"DisposeEventLoopTimerUPP",(r,c)->{});
		l.register(null,"DisposeEventLoopIdleTimerUPP",(r,c)->{});
		l.register(null,"InvokeEventHandlerUPP",(r,c)->ret(rt.callUniversal(arg(3),HANDLER_PROC_INFO,new int[]{arg(0),arg(1),arg(2)})));
		l.register(null,"InvokeEventLoopTimerUPP",(r,c)->rt.callUniversal(arg(2),0x3c0,new int[]{arg(0),arg(1)}));
		// hot keys: a key-down with these modifiers goes to the target as kEventHotKeyPressed
		l.register(null,"RegisterEventHotKey",(r,c)->{ // (keyCode, modifiers, hotKeyID {signature, id}: 2 slots, target, options, *outRef)
			int ref=rt.allocate(4,4);hotKeys.add(new int[]{arg(0),arg(1)&0xff00,arg(2),arg(3),arg(4),ref});if(arg(6)!=0)memory.writeLong(arg(6),ref);ret(0);});
		l.register(null,"UnregisterEventHotKey",(r,c)->{final int ref=arg(0);hotKeys.removeIf(k->k[5]==ref);ret(0);});
		// mouse tracking
		l.register(null,"TrackMouseLocation",(r,c)->{int[] res=trackMouse(arg(0),-1,0);if(arg(1)!=0)memory.writeLong(arg(1),res[0]);if(arg(2)!=0)memory.writeWord(arg(2),res[1]);ret(0);}); // (port, *outPt, *outResult)
		l.register(null,"TrackMouseLocationWithOptions",(r,c)->{ // (port, options, timeout: slots 2-3, *outPt, *outModifiers, *outResult)
			int[] res=trackMouse(arg(0),r.farg(0),0);if(arg(4)!=0)memory.writeLong(arg(4),res[0]);if(arg(5)!=0)memory.writeLong(arg(5),res[2]);if(arg(6)!=0)memory.writeWord(arg(6),res[1]);ret(0);});
		l.register(null,"TrackMouseRegion",(r,c)->{ // (port, region, *ioWasInRgn, *outResult): entered/exited or released
			int port=arg(0),rgn=arg(1),was=arg(2);boolean in=was!=0&&memory.readByte(was)!=0;int result;
			while(true){int[] res=trackMouse(port,-1,rgn);int pt=res[0];boolean now=(rt.toolbox("PtInRgn",pt,rgn)&0xff)!=0;
				if(res[1]==2){result=2;break;}if(now!=in){in=now;result=now?4:3;break;}}
			if(was!=0)memory.writeByte(was,in?1:0);if(arg(3)!=0)memory.writeWord(arg(3),result);ret(0);});
		l.register(null,"IsMouseCoalescingEnabled",(r,c)->ret(1));
		l.register(null,"SetMouseCoalescingEnabled",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),1);ret(0);});
	}
	static final int HANDLER_PROC_INFO=0x00000ff0,COMPARATOR_PROC_INFO=0x000003d0;
	private void addTypes(Handler h,int n,int list) throws M68kCpuException {
		for(int i=0;i<n;i++){long t=type(list+8*i);if(!h.types.contains(t))h.types.add(t);}
		if(PpcRuntime.TRACE){StringBuilder sb=new StringBuilder();for(long t:h.types)sb.append(' ').append(AppleEvents68k.fourcc((int)(t>>>32))).append('/').append((int)t);System.err.println("[ppc]   handler target $"+Integer.toHexString(h.target)+" types"+sb);}
	}
	private long type(int p) throws M68kCpuException {return ((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);}
	private boolean matches(Event e,int n,int list) throws M68kCpuException {if(n==0||list==0)return true;for(int i=0;i<n;i++)if(memory.readLong(list+8*i)==e.eventClass&&memory.readLong(list+8*i+4)==e.kind)return true;return false;}
	private static int toInt(byte[] b){int v=0;for(int i=0;i<Math.min(4,b.length);i++)v=v<<8|(b[i]&0xff);return b.length==2?(short)v:v;}
	/** GetEventParameter's coercions between the integer types (a UInt32 asked as SInt16 and so on); else the bytes as stored. */
	private static byte[] coerce(int have,byte[] b,int want){
		if(want==have||want==0x2a2a2a2a)return b;
		int[] ints={0x6d61676e,0x6c6f6e67,0x73686f72,0x73686f72};
		boolean haveInt=have==0x6d61676e||have==0x6c6f6e67||have==0x73686f72||have==T_MBTN||have==T_SINT16,wantInt=want==0x6d61676e||want==0x6c6f6e67||want==0x73686f72||want==T_MBTN||want==0x75736872;
		if(haveInt&&wantInt){int v=toInt(b);if(want==0x73686f72||want==T_MBTN||want==0x75736872)return word(v);return longs(v);}
		return b;
	}

	/** Fires the timers that are due; returns the seconds until the next one (or 1). */
	private double fireTimers() throws M68kException {
		double wait=1;
		for(Timer t:new ArrayList<Timer>(timers)){
			if(t.removed)continue;
			double n=now();
			if(n>=t.next){
				if(t.interval>0)t.next=n+t.interval;else t.next=Double.MAX_VALUE;
				rt.callUniversal(t.upp,0x000003c0,new int[]{t.ref,t.userData});
				if(t.interval<=0)timers.remove(t);
			}
			if(!t.removed)wait=Math.min(wait,t.next-now());
		}
		return Math.max(0,wait);
	}
	/** RunCurrentEventLoop: events (dispatched) and timers for {@code seconds}. */
	private void idle(double seconds) throws M68kException {
		double end=seconds<0?Double.MAX_VALUE:now()+seconds;
		while(now()<end&&!quitLoop){double wait=Math.min(fireTimers(),end-now());processOneEvent(wait);}
	}
	/** RunApplicationEventLoop: classic events become Carbon events until QuitApplicationEventLoop. */
	private void runLoop() throws M68kException {
		quitLoop=false;
		pump(()->quitLoop);
		quitLoop=false;
	}
	private interface Stop { boolean now() throws M68kException; }
	/** The event loop of RunApplicationEventLoop and RunAppModalLoopForWindow, until {@code stop}. */
	private void pump(Stop stop) throws M68kException {
		while(!stop.now()){
			// events posted by the program come first, then the classic queue
			if(!queue.isEmpty()){Event e=queue.remove(0);try{send(e,DISPATCHER_TARGET);}finally{release(e);}continue;}
			double wait=fireTimers();
			if(stop.now())break;
			processOneEvent(wait);
		}
	}
	private void processOneEvent(double wait) throws M68kException {
		tabletProximity();
		if(!queue.isEmpty()){Event e=queue.remove(0);try{send(e,DISPATCHER_TARGET);}finally{release(e);}return;}
		int record=rt.allocate(16,4);
		try{
			int ticks=motionTicks((int)Math.min(30,Math.ceil(wait*60)));
			boolean got=(rt.toolbox("WaitNextEvent",0xffff,record,ticks,0)&0xff)!=0;
			if(got)dispatchClassic(memory.readBytes(record,16));
			else{Event m=mouseMotion();if(m!=null)try{send(m,DISPATCHER_TARGET);}finally{release(m);}}
		}finally{memory.free(record);}
	}
	/**
	 * ReceiveNextEvent: the first event of the queue that matches the list, else classic events pumped (and queued when
	 * they do not match) until one does or the timeout (seconds; negative = forever) passes. Timers fire meanwhile.
	 * The caller owns one reference to the result.
	 */
	private Event receive(int n,int list,double timeout,boolean pull) throws M68kException {
		double end=timeout<0?Double.MAX_VALUE:now()+timeout;
		int record=rt.allocate(16,4);
		try{
			while(true){
				tabletProximity();
				for(Event e:queue)if(matches(e,n,list)){if(pull)queue.remove(e);else e.references++;return e;}
				if(quitLoop){quitLoop=false;return null;}
				double wait=Math.min(fireTimers(),end-now());
				if(wait<0)return null;
				int ticks=motionTicks((int)Math.max(0,Math.min(30,Math.ceil(wait*60))));
				if((rt.toolbox("WaitNextEvent",0xffff,record,ticks,0)&0xff)==0){Event m=mouseMotion();if(m!=null)enqueueMotion(m);}
				else{
					Event e=fromClassic(memory.readBytes(record,16));
					if(e!=null){
						if(e.record!=null&&(e.record[1]==6)){ // an update event repeats until BeginUpdate: queue it once
							boolean dup=false;for(Event q:queue)if(q.record!=null&&q.record[1]==6&&toInt(java.util.Arrays.copyOfRange(q.record,2,6))==toInt(java.util.Arrays.copyOfRange(e.record,2,6)))dup=true;
							if(dup){release(e);if(now()>=end)return null;continue;}
						}
						if(matches(e,n,list)&&queue.isEmpty()){if(!pull)queue.add(0,e);else return e;e.references++;return e;}
						queue.add(e);
					}
				}
				if(now()>=end)return null;
			}
		}finally{memory.free(record);}
	}

	private Event newEvent(int eventClass,int kind) throws M68kCpuException {
		Event e=new Event();e.eventClass=eventClass;e.kind=kind;e.time=now();e.ref=rt.allocate(4,4);events.put(e.ref,e);return e;
	}
	private void release(Event e){if(--e.references>0)return;events.remove(e.ref);if(memory.isAllocated(e.ref))memory.free(e.ref);}
	private static byte[] longs(int... v){byte[] b=new byte[4*v.length];for(int i=0;i<v.length;i++){b[4*i]=(byte)(v[i]>>>24);b[4*i+1]=(byte)(v[i]>>>16);b[4*i+2]=(byte)(v[i]>>>8);b[4*i+3]=(byte)v[i];}return b;}
	private static byte[] word(int v){return new byte[]{(byte)(v>>>8),(byte)v};}
	private byte[] rect(int p) throws M68kCpuException {return memory.readBytes(p,8);}
	/** A kEventCommandProcess event for an HICommand (attributes, commandID, menu, item). */
	private Event command(byte[] hiCommand) throws M68kCpuException {
		Event e=newEvent(CLASS_COMMAND,1);e.params.put(P_DIRECT,new Object[]{T_HICMD,hiCommand});return e;
	}
	/** An EventRecord for an event (the one it came from, or one made for a mouse or keyboard event); null when there is none. */
	private byte[] eventRecordOf(Event e){
		if(e.record!=null)return e.record;
		int what=0,message=0,where=0,mods=0;Object[] p;
		if(e.eventClass==CLASS_MOUSE&&(e.kind==1||e.kind==2)){what=e.kind;p=e.params.get(P_MLOC);if(p!=null)where=toInt((byte[])p[1]);}
		else if(e.eventClass==CLASS_KEYBOARD&&(e.kind==1||e.kind==2||e.kind==3)){what=e.kind==1?3:e.kind==2?5:4;p=e.params.get(P_KCHR);if(p!=null)message=((byte[])p[1])[0]&0xff;p=e.params.get(P_KCOD);if(p!=null)message|=(toInt((byte[])p[1])&0xff)<<8;}
		else return null;
		p=e.params.get(P_KMOD);if(p!=null)mods=toInt((byte[])p[1]);
		byte[] r=new byte[16];r[0]=(byte)(what>>>8);r[1]=(byte)what;System.arraycopy(longs(message),0,r,2,4);System.arraycopy(longs((int)(e.time*60)),0,r,6,4);System.arraycopy(longs(where),0,r,10,4);r[14]=(byte)(mods>>>8);r[15]=(byte)mods;
		return r;
	}

	// ---- dispatching

	/**
	 * Offers an event to the handlers of {@code target} (most recently installed first), then of the targets it belongs to
	 * (a window's, then the application's); true when one handled it. A handler that calls CallNextEventHandler runs the
	 * rest of that chain - and the standard handling of a classic event - from inside itself.
	 */
	private boolean dispatch(int target,Event e) throws M68kException {
		List<Handler> chain=new ArrayList<Handler>();long type=((long)e.eventClass<<32)|(e.kind&0xffffffffL);
		for(int t:targetChain(target))for(int i=handlers.size()-1;i>=0;i--){Handler h=handlers.get(i);if(h.target==t&&h.types.contains(type))chain.add(h);}
		Call k=new Call(e,chain);
		return continueDispatch(k);
	}
	/** The handlers still to run in a dispatch (CallNextEventHandler), then the standard handling. */
	private boolean continueDispatch(Call k) throws M68kException {
		while(!k.rest.isEmpty()){
			Handler h=k.rest.remove(0);
			int callRef=rt.allocate(4,4);calls.put(callRef,k);
			int result;
			try{result=rt.callUniversal(h.upp,HANDLER_PROC_INFO,new int[]{callRef,k.event.ref,h.userData});}
			finally{calls.remove(callRef);memory.free(callRef);}
			if(result!=EVENT_NOT_HANDLED)return true;
		}
		if(!k.standard&&k.event.record!=null&&!k.event.standardDone){k.standard=true;k.event.standardDone=true;standardHandling(k.event);return true;}
		return false;
	}
	/** A target and the ones above it: a control's window, a window, the application. */
	private int[] targetChain(int target) throws M68kCpuException {
		if(target==APPLICATION_TARGET||target==0)return new int[]{APPLICATION_TARGET};
		if(target==DISPATCHER_TARGET)return new int[]{DISPATCHER_TARGET,APPLICATION_TARGET};
		if(windowsContain(target))return new int[]{target,APPLICATION_TARGET};
		int owner=controlOwner(target);
		return owner!=0?new int[]{target,owner,APPLICATION_TARGET}:new int[]{target,APPLICATION_TARGET};
	}
	/** The window of a control Handle (contrlOwner, when it is a window in the list), else 0 (a menu or another target). */
	private int controlOwner(int h){
		try{if(h==0||(h&1)!=0)return 0;int p=memory.readLong(h);if(p==0||(p&1)!=0)return 0;int w=memory.readLong(p+4);return windowsContain(w)?w:0;}catch(M68kCpuException e){return 0;}
	}
	private boolean windowsContain(int w) throws M68kCpuException {for(int p=memory.readLong(0x9d6),n=0;p!=0&&n<1000;p=memory.readLong(p+144),n++)if(p==w)return true;return false;}

	/** SendEventToEventTarget: the dispatcher target routes an event to where it belongs, the others take it as sent. */
	private boolean send(Event e,int target) throws M68kException {
		if(target!=DISPATCHER_TARGET)return dispatch(target,e);
		if(e.eventClass==CLASS_COMMAND)return dispatchCommand(e);
		return dispatch(naturalTarget(e),e);
	}
	/** Where the dispatcher sends an event: a window's events to the window, mouse events to the window under the mouse, keyboard events to the focus. */
	private int naturalTarget(Event e) throws M68kException {
		Object[] p=e.params.get(P_WINDOW);if(p==null&&e.eventClass==CLASS_WINDOW)p=e.params.get(P_DIRECT);
		if(p!=null){int w=toInt((byte[])p[1]);if(w!=0)return w;}
		if(e.eventClass==CLASS_KEYBOARD||e.eventClass==CLASS_TEXT_INPUT){int w=rt.toolbox("FrontWindow");return w!=0?w:APPLICATION_TARGET;}
		return APPLICATION_TARGET;
	}
	/** A command goes to the user focus (the front window) and up to the application; a quit left alone ends the event loop. */
	private boolean dispatchCommand(Event e) throws M68kException {
		int front=rt.toolbox("FrontWindow");
		boolean handled=dispatch(front!=0?front:APPLICATION_TARGET,e);
		if(!handled&&e.kind==1){Object[] p=e.params.get(P_DIRECT);int id=p==null?0:toInt(java.util.Arrays.copyOfRange((byte[])p[1],4,8));if(id==0x71756974){quitLoop=true;handled=true;}} // kHICommandQuit
		return handled;
	}

	/** One classic event through the Carbon handlers, with the standard handling when they leave it alone. */
	private void dispatchClassic(byte[] record) throws M68kException {
		Event e=fromClassic(record);
		if(e==null)return;
		try{send(e,DISPATCHER_TARGET);if(!e.standardDone){e.standardDone=true;standardHandling(e);}}finally{release(e);}
	}
	/** The Carbon event CarbonLib made of a classic event (null for null events and the ones it has none for). */
	private Event fromClassic(byte[] record) throws M68kException {
		int what=(record[0]&0xff)<<8|record[1]&0xff,message=toInt(java.util.Arrays.copyOfRange(record,2,6)),when=toInt(java.util.Arrays.copyOfRange(record,6,10)),where=toInt(java.util.Arrays.copyOfRange(record,10,14)),modifiers=(record[14]&0xff)<<8|record[15]&0xff;
		Event e;
		switch(what){
			case 1:case 2:{ // mouse down/up
				e=newEvent(CLASS_MOUSE,what);
				int slot=rt.allocate(4,4);int part=(short)rt.toolbox("FindWindow",where,slot);int window=memory.readLong(slot);memory.free(slot);
				if(what==1){int dbl=rt.toolbox("GetDblTime");clickCount=when-lastClickWhen<=dbl&&Math.abs((short)where-(short)lastClickWhere)<5&&Math.abs((where>>16)-(lastClickWhere>>16))<5?clickCount+1:1;lastClickWhen=when;lastClickWhere=where;}
				e.params.put(P_MLOC,new Object[]{T_POINT,longs(where)});e.params.put(P_KMOD,new Object[]{T_UINT32,longs(modifiers)});
				e.params.put(P_MBTN,new Object[]{T_MBTN,word(1)});e.params.put(P_CCNT,new Object[]{T_UINT32,longs(Math.max(1,clickCount))});
				if(window!=0)e.params.put(P_WINDOW,new Object[]{T_WINDOW,longs(window)});
				e.params.put(P_WPAR,new Object[]{T_SINT16,word(part)});
				lastMouse=where;if(what==1)dragWindow=window;
				tabletPoint(e,where,what==1);
				break;}
			case 3:case 4:case 5:{ // key down, up, auto-key
				e=newEvent(CLASS_KEYBOARD,what==3?1:what==5?2:3);
				e.params.put(P_KCHR,new Object[]{T_CHAR,new byte[]{(byte)message}});e.params.put(P_KCOD,new Object[]{T_UINT32,longs((message>>>8)&0xff)});
				e.params.put(P_KMOD,new Object[]{T_UINT32,longs(modifiers)});e.params.put(P_KBDT,new Object[]{T_UINT32,longs(0)});
				break;}
			case 6:e=newEvent(CLASS_WINDOW,1);e.params.put(P_DIRECT,new Object[]{T_WINDOW,longs(message)});break;                       // kEventWindowUpdate
			case 8:e=newEvent(CLASS_WINDOW,(modifiers&1)!=0?5:6);e.params.put(P_DIRECT,new Object[]{T_WINDOW,longs(message)});break;  // kEventWindowActivated / Deactivated
			case 15:{int kind=(message>>>24)&0xff;
				if(kind==1)e=newEvent(CLASS_APPLICATION,(message&1)!=0?1:2);                          // resume/suspend: kEventAppActivated / Deactivated
				else if(kind==0xfa){e=newEvent(CLASS_MOUSE,5);e.params.put(P_MLOC,new Object[]{T_POINT,longs(where)});e.params.put(P_KMOD,new Object[]{T_UINT32,longs(modifiers)});tabletPoint(e,where,false);} // mouse moved
				else return null;
				break;}
			case 23:e=newEvent(CLASS_APPLE_EVENT,1);e.params.put(P_EVCL,new Object[]{T_TYPE,longs(message)});e.params.put(P_EVID,new Object[]{T_TYPE,longs(where)});break; // kEventAppleEvent
			default:return null;
		}
		e.record=record;e.time=(when&0xffffffffL)/60.0;
		return e;
	}

	// ---- mouse motion and pen tablets

	/** Where the pointer was last seen (Mouse $830), and the window the button went down in (kEventMouseDragged goes there). */
	private int lastMouse=Integer.MIN_VALUE,dragWindow;
	/** WaitNextEvent's sleep: one tick while the button is down, so a drag gives kEventMouseDragged at the tick rate. */
	private int motionTicks(int ticks) throws M68kException {return (rt.toolbox("Button")&0xff)!=0?Math.min(ticks,1):ticks;}
	/**
	 * With no classic event: kEventMouseDragged (button down) or kEventMouseMoved when the pointer moved since it was last
	 * seen, as CarbonLib made them (the classic Event Manager has no mouse-moved events here); null when it stayed.
	 */
	private Event mouseMotion() throws M68kException {
		int where=memory.readLong(0x830);
		if(where==lastMouse)return null;
		boolean first=lastMouse==Integer.MIN_VALUE;lastMouse=where;
		if(first)return null;
		boolean down=(rt.toolbox("Button")&0xff)!=0;
		Event e=newEvent(CLASS_MOUSE,down?6:5);
		e.params.put(P_MLOC,new Object[]{T_POINT,longs(where)});e.params.put(P_KMOD,new Object[]{T_UINT32,longs(modifiers())});
		int window=dragWindow;
		if(down)e.params.put(P_MBTN,new Object[]{T_MBTN,word(1)});
		else{int slot=rt.allocate(4,4);rt.toolbox("FindWindow",where,slot);window=memory.readLong(slot);memory.free(slot);}
		if(window!=0&&windowsContain(window))e.params.put(P_WINDOW,new Object[]{T_WINDOW,longs(window)});
		tabletPoint(e,where,down);
		return e;
	}
	/** A motion event into the queue, in place of a motion event of the same kind still waiting there (mouse coalescing). */
	private void enqueueMotion(Event m){
		for(Event q:new ArrayList<Event>(queue))if(q.eventClass==CLASS_MOUSE&&q.kind==m.kind&&q.record==null){queue.remove(q);release(q);}
		queue.add(m);
	}

	/**
	 * Pen tablets, as the Wacom driver gave Carbon programs: while the pen is near, mouse events carry
	 * kEventParamTabletEventType = kEventTabletPoint and kEventParamTabletPointRec (pressure 0..65535, tilt -32767..32767 of
	 * full tilt, the pen tip as button 1; place = the pointer, tablet = screen); the pen coming near or leaving (and the
	 * eraser end turned to the tablet: the pen leaves, the eraser comes) is a kEventClassTablet kEventTabletProximity event
	 * with kEventParamTabletProximityRec. From the host's pen ({@link TabletInput}); -Dhz68k.tablet=false turns it off.
	 */
	static final int CLASS_TABLET=0x74626c74; // 'tblt'
	private static final int P_TABLET_TYPE=0x74626c74,P_TABLET_POINT=0x74627263,P_TABLET_PROXIMITY=0x74627078, // 'tblt', 'tbrc', 'tbpx'
		T_TABLET_POINT=0x74627263,T_TABLET_PROXIMITY=0x74627078,TABLET_POINT=1,TABLET_PROXIMITY=2;
	/** NX_TABLET_POINTER_PEN / _ERASER, and the fields a TabletPointRec gives (device ID, abs X/Y, buttons, tilt X/Y, pressure). */
	private static final int POINTER_PEN=1,POINTER_ERASER=3,CAPABILITIES=0x0001|0x0002|0x0004|0x0040|0x0080|0x0100|0x0400;
	/** Full tilt in TabletInput's degrees ([NSEvent tilt] 1 = 60 degrees). */
	private static final float FULL_TILT=60f;
	/** The pen end last seen near (0: none), for kEventTabletProximity; the last pressure (a pressed pen between samples). */
	private int tabletPointer;
	private float tabletPressure;
	private static boolean tabletOn(){return TabletInput.available&&!"false".equals(System.getProperty("hz68k.tablet"));}
	/** The tablet parameters of a mouse event while the pen is near ({@code pressed}: the tip on the tablet). */
	private void tabletPoint(Event e,int where,boolean pressed){
		if(!tabletOn()||!TabletInput.penNear)return;
		float p=0;
		if(pressed){p=TabletInput.latest();if(Float.isNaN(p))p=tabletPressure;else tabletPressure=p;}
		float[] tilt=TabletInput.latestTilt();
		byte[] b=new byte[32];
		System.arraycopy(longs((short)where,where>>16,0),0,b,0,12);                                      // absX, absY, absZ
		System.arraycopy(word(pressed?1:0),0,b,12,2);                                                     // buttons: the tip
		System.arraycopy(word(Math.round(Math.max(0f,Math.min(1f,p))*65535)),0,b,14,2);                   // pressure
		if(tilt!=null){System.arraycopy(word(scaledTilt(tilt[0])),0,b,16,2);System.arraycopy(word(scaledTilt(-tilt[1])),0,b,18,2);} // tiltX, tiltY (positive away from the user)
		System.arraycopy(word(1),0,b,24,2);                                                               // deviceID
		e.params.put(P_TABLET_TYPE,new Object[]{T_UINT32,longs(TABLET_POINT)});
		e.params.put(P_TABLET_POINT,new Object[]{T_TABLET_POINT,b});
	}
	private static int scaledTilt(float degrees){return Math.round(Math.max(-1f,Math.min(1f,degrees/FULL_TILT))*32767);}
	/** The pen came near, left or turned round since the last look: kEventTabletProximity events into the queue. */
	private void tabletProximity() throws M68kCpuException {
		int pointer=tabletOn()&&TabletInput.penNear?TabletInput.penNearEraser?POINTER_ERASER:POINTER_PEN:0;
		if(pointer==tabletPointer)return;
		if(tabletPointer!=0)queue.add(proximityEvent(tabletPointer,false));
		if(pointer!=0)queue.add(proximityEvent(pointer,true));
		tabletPointer=pointer;
	}
	/** A kEventTabletProximity event: TabletProximityRec {vendorID, tabletID, pointerID, deviceID, systemTabletID, vendorPointerType, serial, uniqueID, capabilities, pointerType, entering}. */
	private Event proximityEvent(int pointer,boolean entering) throws M68kCpuException {
		byte[] b=new byte[30];
		System.arraycopy(word(0x056a),0,b,0,2);                       // Wacom
		System.arraycopy(word(1),0,b,6,2);System.arraycopy(word(1),0,b,8,2); // deviceID, systemTabletID
		System.arraycopy(word(pointer==POINTER_ERASER?0x080a:0x0802),0,b,10,2); // vendor pointer type: a pen's tip or eraser
		System.arraycopy(longs(1),0,b,12,4);                          // pointerSerialNumber
		System.arraycopy(longs(CAPABILITIES),0,b,24,4);
		b[28]=(byte)pointer;b[29]=(byte)(entering?1:0);
		Event e=newEvent(CLASS_TABLET,TABLET_PROXIMITY);
		e.params.put(P_TABLET_PROXIMITY,new Object[]{T_TABLET_PROXIMITY,b});
		return e;
	}

	/** What CarbonLib's standard handlers did with a classic event nobody handled (menus, window parts, updates, Apple events). */
	private void standardHandling(Event e) throws M68kException {
		byte[] record=e.record;if(record==null)return;
		int what=(record[0]&0xff)<<8|record[1]&0xff,message=toInt(java.util.Arrays.copyOfRange(record,2,6)),where=toInt(java.util.Arrays.copyOfRange(record,10,14)),modifiers=(record[14]&0xff)<<8|record[15]&0xff;
		switch(what){
			case 1:{ // mouseDown
				mouseModifiers=modifiers;
				int slot=rt.allocate(4,4);int part=rt.toolbox("FindWindow",where,slot);int window=memory.readLong(slot);memory.free(slot);
				// an app-modal window: the menu bar and the other windows (floaters aside) refuse clicks
				if(!modalWindows.isEmpty()&&(part==1||part>=3&&window!=modalWindows.get(0)&&windowClass(window)!=5&&windowClass(window)!=8)){rt.toolbox("SysBeep",1);break;}
				switch(part){
					case 1:{enableMenus();int choice=rt.toolbox("MenuSelect",where);menuChoice(choice);break;} // inMenuBar
					case 2:{int rec=rt.allocate(16,4);memory.load(rec,record);rt.toolbox("SystemClick",rec,window);memory.free(rec);break;} // inSysWindow
					case 3:{ // inContent
						if(window!=rt.toolbox("FrontWindow")&&window!=0){rt.toolbox("SelectWindow",window);break;}
						if(windowEvent(window,37,where)||windowEvent(window,85,where))break; // kEventWindowClickContentRgn, then kEventWindowHandleContentClick
						int local=r4(window,where);int slot2=rt.allocate(4,4);rt.toolbox("FindControl",local,window,slot2);int control=memory.readLong(slot2);memory.free(slot2); // the standard handler tracks controls
						if(control!=0)carbon("HandleControlClick",control,local,modifiers,-1);
						break;}
					case 4:{ // inDrag
						if(windowEvent(window,32,where))break;                                       // kEventWindowClickDragRgn
						int before=rt.allocate(8,4);globalPortRect(window,before);
						int bounds=rt.allocate(8,4);memory.copy(memory.readLong(memory.readLong(0x9ee))+2,bounds,8);rt.toolbox("DragWindow",window,where,bounds);memory.free(bounds);
						boundsChanged(window,before,1|8);windowEvent(window,31,where);memory.free(before);break;} // kEventWindowDragCompleted
					case 5:{ // inGrow
						if(windowEvent(window,33,where))break;                                       // kEventWindowClickResizeRgn
						int before=rt.allocate(8,4);globalPortRect(window,before);
						int bounds=rt.allocate(8,4);memory.writeWord(bounds,64);memory.writeWord(bounds+2,64);memory.writeWord(bounds+4,32000);memory.writeWord(bounds+6,32000);
						int size=rt.toolbox("GrowWindow",window,where,bounds);memory.free(bounds);
						if(size!=0){rt.toolbox("SizeWindow",window,size&0xffff,size>>>16,1);boundsChanged(window,before,2|4);windowEvent(window,29,where);} // kEventWindowResizeCompleted
						memory.free(before);break;}
					case 6:{ // inGoAway
						if(windowEvent(window,35,where))break;                                       // kEventWindowClickCloseRgn
						if((rt.toolbox("TrackGoAway",window,where)&0xff)!=0&&!windowEvent(window,72,where)){windowEvent(window,73,where);rt.toolbox("DisposeWindow",window);} // kEventWindowClose, Closed
						break;}
					case 7:case 8:{ // inZoomIn/Out
						if(windowEvent(window,36,where))break;                                       // kEventWindowClickZoomRgn
						if((rt.toolbox("TrackBox",window,where,part)&0xff)!=0&&!windowEvent(window,75,where)){
							int before=rt.allocate(8,4);globalPortRect(window,before);
							inPort(window,()->rt.toolbox("EraseRect",window+16));rt.toolbox("ZoomWindow",window,part,0);boundsChanged(window,before,4|8);windowEvent(window,76,where);memory.free(before);} // kEventWindowZoom, Zoomed
						break;}
					case 11:{if(!windowEvent(window,34,where)){WindowExtra x=windowExtras.get(window);collapse(window,x==null||!x.collapsed);}break;} // inCollapseBox: window shade on and off
					default:break;
				}
				break;}
			case 3:case 5:{ // keyDown, autoKey
				int ch=message&0xff,code=(message>>>8)&0xff;
				for(int[] k:hotKeys)if(k[0]==code&&k[1]==(modifiers&0xff00)){Event h=newEvent(CLASS_KEYBOARD,5);h.params.put(P_DIRECT,new Object[]{T_HOTKEY,longs(k[2],k[3])});try{dispatch(k[4],h);}finally{release(h);}return;}
				if(what==3&&defaultOrCancel(ch,code,modifiers))break;
				if((modifiers&0x100)!=0&&what==3&&modalWindows.isEmpty()){enableMenus();int choice=rt.toolbox("MenuKey",ch);if((choice>>>16)!=0){menuChoice(choice);break;}}
				int front=rt.toolbox("FrontWindow");if(front==0)break;
				int focus=rt.allocate(4,4);int err=(short)carbon("GetKeyboardFocus",front,focus);int control=memory.readLong(focus);memory.free(focus);
				if(err==0&&control!=0)carbon("HandleControlKey",control,code,ch,modifiers);
				break;}
			case 6:{ // updateEvt: kEventWindowUpdate unhandled -> BeginUpdate, kEventWindowDrawContent, EndUpdate
				int window=message;
				rt.toolbox("BeginUpdate",window);
				try{inPort(window,()->{if(!windowEvent(window,2,0)){rt.toolbox("UpdateControls",window,memory.readLong(window+24));}});}finally{rt.toolbox("EndUpdate",window);}
				break;}
			case 23:{int rec=rt.allocate(16,4);memory.load(rec,record);rt.toolbox("AEProcessAppleEvent",rec);memory.free(rec);break;} // kHighLevelEvent
			default:break;
		}
	}
	/**
	 * The standard window handler's keys for the front window's buttons (SetWindowDefaultButton/CancelButton): Return or
	 * Enter press the default button, Escape or Command-period the cancel button - flashed, then kEventControlHit.
	 */
	private boolean defaultOrCancel(int ch,int code,int modifiers) throws M68kException {
		int front=rt.toolbox("FrontWindow");WindowExtra x=front==0?null:windowExtras.get(front);if(x==null)return false;
		boolean command=(modifiers&0x100)!=0;
		int button=(ch==0x0d||ch==0x03)&&!command?x.defaultButton:ch==0x1b&&code==0x35||ch=='.'&&command?x.cancelButton:0;
		if(button==0||(carbon("IsControlVisible",button)&0xff)==0||(carbon("IsControlActive",button)&0xff)==0)return false;
		int ticks=rt.allocate(4,4);
		inPort(front,()->{rt.toolbox("HiliteControl",button,10);rt.toolbox("Delay",8,ticks);rt.toolbox("HiliteControl",button,0);}); // kControlButtonPart
		memory.free(ticks);
		controlHit(button,10,modifiers);return true;
	}
	/** The end of TrackControl/HandleControlClick: a part hit, unless a live thumb drag only came back for the scrolling. */
	void tracked(int control,int part,int modifiers) throws M68kException {if(part!=0&&!rt.toolbox.host().isMouseDown())controlHit(control,part,modifiers);}
	/**
	 * A control clicked (or pressed from the keyboard): kEventControlHit to the control, its window and the application; left
	 * alone, a control with a command ID sends kEventCommandProcess (kHICommandFromControl) the same way.
	 */
	private void controlHit(int control,int part,int modifiers) throws M68kException {
		Event e=newEvent(CLASS_CONTROL,1);e.params.put(P_DIRECT,new Object[]{T_CONTROL,longs(control)});e.params.put(P_CPRT,new Object[]{T_CPRT,word(part)});e.params.put(P_KMOD,new Object[]{T_UINT32,longs(modifiers)});
		boolean handled;try{handled=dispatch(control,e);}finally{release(e);}
		if(handled)return;
		int slot=rt.allocate(4,4);memory.writeLong(slot,0);carbon("GetControlCommandID",control,slot);int id=memory.readLong(slot);memory.free(slot);
		if(id==0)return;
		byte[] hi=new byte[14];System.arraycopy(longs(2),0,hi,0,4);System.arraycopy(longs(id),0,hi,4,4);System.arraycopy(longs(control),0,hi,8,4);
		Event cmd=command(hi);
		try{if(!dispatch(control,cmd)&&id==0x71756974)quitLoop=true;}finally{release(cmd);} // kHICommandQuit left alone ends the event loop
	}
	/** Calls CarbonLib's {@code name} as the program would (a native of this layer, else the Toolbox bridge). */
	private int carbon(String name,int... args) throws M68kException {return rt.cpu.call(rt.nativeSymbol("CarbonLib",name,Pef.CLASS_TVECTOR,false),args);}
	/** A global point in a window's local coordinates. */
	private int r4(int window,int global) throws M68kException {int pt=rt.allocate(4,4);memory.writeLong(pt,global);inPort(window,()->rt.toolbox("GlobalToLocal",pt));int v=memory.readLong(pt);memory.free(pt);return v;}
	/** A kEventClassWindow event of {@code kind} to the window's handlers (mouse location given for the click events); true when handled. */
	private boolean windowEvent(int window,int kind,int where) throws M68kException {
		if(window==0)return false;
		Event e=newEvent(CLASS_WINDOW,kind);e.params.put(P_DIRECT,new Object[]{T_WINDOW,longs(window)});
		if(where!=0){e.params.put(P_MLOC,new Object[]{T_POINT,longs(where)});e.params.put(P_KMOD,new Object[]{T_UINT32,longs(mouseModifiers)});e.params.put(P_MBTN,new Object[]{T_MBTN,word(1)});e.params.put(P_CCNT,new Object[]{T_UINT32,longs(Math.max(1,clickCount))});} // the click events carry the modifiers, button and click count too (BOOM reads kmod)
		try{return dispatch(window,e);}finally{release(e);}
	}
	private boolean visible(int w) throws M68kCpuException {return memory.readByte(w+110)!=0;}
	private void showHide(int w,boolean show,String call) throws M68kException {
		boolean was=w!=0&&visible(w);
		if(call.equals("ShowHide"))rt.toolbox(call,w,show?1:0);else rt.toolbox(call,w);
		if(w!=0&&visible(w)!=was)windowEvent(w,show?24:25,0); // kEventWindowShown / kEventWindowHidden
	}
	/** The global port rectangle {top, left, bottom, right}. */
	private int[] portBounds(int w) throws M68kException {
		int p=rt.allocate(8,4);globalPortRect(w,p);
		int[] b={(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};memory.free(p);return b;
	}
	private static byte[] rectBytes(int[] b){byte[] x=new byte[8];for(int i=0;i<4;i++){x[2*i]=(byte)(b[i]>>8);x[2*i+1]=(byte)b[i];}return x;}
	/** Windows whose bounds are changing: a handler that moves its own window again gets no second round of events. */
	private final java.util.Set<Integer> boundsChanging=new java.util.HashSet<Integer>();
	/**
	 * MoveWindow (call 1: front flag), SizeWindow (2: update flag) or SetWindowBounds (0) to the global port rectangle
	 * {@code target}: kEventWindowBoundsChanging first (attributes size 4 / origin 8; the handler may rewrite
	 * kEventParamCurrentBounds), the change, then kEventWindowBoundsChanged.
	 */
	private void changeBounds(int w,int[] target,int call,int front,int update) throws M68kException {
		int[] before=portBounds(w);
		boolean events=!boundsChanging.contains(w);
		int attributes=(target[0]!=before[0]||target[1]!=before[1]?8:0)|(target[2]-target[0]!=before[2]-before[0]||target[3]-target[1]!=before[3]-before[1]?4:0);
		if(events&&attributes!=0){
			boundsChanging.add(w);
			Event e=newEvent(CLASS_WINDOW,26);e.params.put(P_DIRECT,new Object[]{T_WINDOW,longs(w)});e.params.put(P_ATTR,new Object[]{T_UINT32,longs(attributes)});
			e.params.put(P_ORIG,new Object[]{T_RECT,rectBytes(before)});e.params.put(P_PREV,new Object[]{T_RECT,rectBytes(before)});e.params.put(P_CRCT,new Object[]{T_RECT,rectBytes(target)});
			try{dispatch(w,e);Object[] cur=e.params.get(P_CRCT);if(cur!=null&&((byte[])cur[1]).length>=8){byte[] x=(byte[])cur[1];for(int i=0;i<4;i++)target[i]=(short)((x[2*i]&0xff)<<8|x[2*i+1]&0xff);}}
			finally{release(e);boundsChanging.remove(w);}
		}
		boolean moved=target[0]!=before[0]||target[1]!=before[1],sized=target[2]-target[0]!=before[2]-before[0]||target[3]-target[1]!=before[3]-before[1];
		if(moved||call==1)rt.toolbox("MoveWindow",w,target[1],target[0],front);
		if(sized||call==2)rt.toolbox("SizeWindow",w,target[3]-target[1],target[2]-target[0],update);
		if(!events||!(moved||sized))return;
		int[] after=portBounds(w);
		Event e=newEvent(CLASS_WINDOW,27);e.params.put(P_DIRECT,new Object[]{T_WINDOW,longs(w)});e.params.put(P_ATTR,new Object[]{T_UINT32,longs((moved?8:0)|(sized?4:0))});
		e.params.put(P_ORIG,new Object[]{T_RECT,rectBytes(before)});e.params.put(P_PREV,new Object[]{T_RECT,rectBytes(before)});e.params.put(P_CRCT,new Object[]{T_RECT,rectBytes(after)});
		boundsChanging.add(w);
		try{dispatch(w,e);}finally{release(e);boundsChanging.remove(w);}
	}
	/** kEventWindowBoundsChanged after a drag, resize or zoom (attributes: user drag 1, user resize 2, size 4, origin 8). */
	private void boundsChanged(int window,int before,int attributes) throws M68kException {
		int after=rt.allocate(8,4);globalPortRect(window,after);
		if(!java.util.Arrays.equals(rect(before),rect(after))){
			Event e=newEvent(CLASS_WINDOW,27);e.params.put(P_DIRECT,new Object[]{T_WINDOW,longs(window)});e.params.put(P_ATTR,new Object[]{T_UINT32,longs(attributes)});
			e.params.put(P_ORIG,new Object[]{T_RECT,rect(before)});e.params.put(P_PREV,new Object[]{T_RECT,rect(before)});e.params.put(P_CRCT,new Object[]{T_RECT,rect(after)});
			try{dispatch(window,e);}finally{release(e);}
		}
		memory.free(after);
	}
	/** Before a menu is shown: kEventMenuEnableItems per menu and kEventCommandUpdateStatus per item with a command ID, as CarbonLib sent them. */
	private void enableMenus() throws M68kException {
		if(handlers.isEmpty())return;
		loadXmnu();
		int list=memory.readLong(0xa1c);if(list==0)return;int p=memory.readLong(list);if(p==0)return;
		int last=memory.readWord(p);
		for(int off=6;off<=last;off+=6){
			int menu=memory.readLong(p+off);if(menu==0||memory.readLong(menu)==0)continue;
			Event e=newEvent(CLASS_MENU,8);e.params.put(P_DIRECT,new Object[]{T_MENU,longs(menu)});
			try{dispatch(menu,e);}finally{release(e);}
			int count=rt.toolbox("CountMenuItems",menu);
			for(int item=1;item<=count;item++){Integer id=commandIds.get(key(menu,item));if(id==null)continue;
				byte[] hi=new byte[14];System.arraycopy(longs(1),0,hi,0,4);System.arraycopy(longs(id),0,hi,4,4);System.arraycopy(longs(menu),0,hi,8,4);hi[12]=(byte)(item>>>8);hi[13]=(byte)item;
				Event u=newEvent(CLASS_COMMAND,2);u.params.put(P_DIRECT,new Object[]{T_HICMD,hi});try{int front=rt.toolbox("FrontWindow");dispatch(front!=0?front:APPLICATION_TARGET,u);}finally{release(u);}}
		}
	}
	/** A menu choice (MenuSelect/MenuKey result) as kEventCommandProcess with the item's command ID; the Apple menu's desk accessories open. */
	private void menuChoice(int choice) throws M68kException {
		int menuId=(short)(choice>>>16),item=choice&0xffff;
		if(menuId==0)return;
		int menu=rt.toolbox("GetMenuHandle",menuId);
		int commandId=0;
		if(menu!=0){loadXmnu();Integer v=commandIds.get(key(menu,item));if(v!=null)commandId=v;}
		byte[] hi=new byte[14];
		System.arraycopy(longs(0x00000001),0,hi,0,4); // kHICommandFromMenu
		System.arraycopy(longs(commandId),0,hi,4,4);System.arraycopy(longs(menu),0,hi,8,4);hi[12]=(byte)(item>>>8);hi[13]=(byte)item;
		Event e=command(hi);
		try{dispatchCommand(e);}finally{release(e);}
		rt.toolbox("HiliteMenu",0);
	}

	/**
	 * TrackMouseLocation: waits (the Toolbox keeps running) until the button goes up (2), the mouse moves (5), the
	 * modifiers change (6), or the timeout passes (8); {point in port coordinates, result, modifiers}. {@code region}:
	 * returns on every move so TrackMouseRegion can test entering and leaving.
	 */
	private int[] trackMouse(int port,double timeout,int region) throws M68kException {
		int saved=rt.allocate(4,4),pt=rt.allocate(4,4);rt.toolbox("GetPort",saved);
		try{
			if(port!=0)rt.toolbox("SetPort",port);
			rt.toolbox("GetMouse",pt);int start=memory.readLong(pt);int startMods=modifiers();
			double end=timeout<0?Double.MAX_VALUE:now()+timeout;
			while(true){
				if((rt.toolbox("StillDown")&0xff)==0&&(rt.toolbox("Button")&0xff)==0){rt.toolbox("GetMouse",pt);return new int[]{memory.readLong(pt),2,modifiers()};}
				rt.toolbox("GetMouse",pt);int p=memory.readLong(pt);
				if(p!=start)return new int[]{p,5,modifiers()};
				int m=modifiers();if(m!=startMods)return new int[]{p,6,m};
				if(now()>=end)return new int[]{p,8,m};
				try{Thread.sleep(8);}catch(InterruptedException ex){Thread.currentThread().interrupt();return new int[]{p,7,m};}
			}
		}finally{rt.toolbox("SetPort",memory.readLong(saved));memory.free(saved);memory.free(pt);}
	}
	/** The modifier keys now, as EventRecord modifiers. */
	private int modifiers() throws M68kException {
		int keys=rt.allocate(16,2);rt.toolbox("GetKeys",keys);int b6=memory.readByte(keys+6),b7=memory.readByte(keys+7);memory.free(keys);
		return ((b6>>>7)&1)<<8|(b7&1)<<9|((b7>>>1)&1)<<10|((b7>>>2)&1)<<11|((b7>>>3)&1)<<12|((rt.toolbox("Button")&0xff)!=0?0:0x80);
	}
}

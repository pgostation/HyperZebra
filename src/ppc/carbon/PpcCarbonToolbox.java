import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CarbonLib's Toolbox additions that have no 68K inline words: the accessors Carbon added for records it made opaque
 * (ports, lists, controls, menus, dialogs, TextEdit) and the Carbon-only calls of those managers - 32-bit control values,
 * the Create...Control functions of CarbonLib 1.1, menu and menu item attributes and properties, command IDs across
 * menus, custom definition procedures given as UPPs (MDEF, LDEF, CDEF specs), help tag content, QDRegionToRects, the
 * scrap's flavor list. Everything is built on the 68K Toolbox (a record field, a trap through {@code rt.toolbox}) or kept
 * here when the Toolbox has no place for it (view sizes, attributes, properties, help content, timeouts).
 * <p>
 * The Create...Control functions use the classic procIDs for the kinds the Control Manager draws itself (push buttons,
 * check boxes, radio buttons, scroll bars, pop-up menus - Appearance draws them the same) and the Appearance procIDs for
 * the others, whose content goes to the control's data tags (SetControlData) as the Appearance CDEFs keep it.
 * <p>
 * A custom list or menu definition given as a UPP (CreateCustomList, RegisterListDefinition, RegisterMenuDefinition)
 * needs its 68K manager to find it by resource ID: it is registered with the manager's
 * {@code registerDefinition(int id,int handle)} (ControlManager68k, ListManager68k, MenuManager68k).
 */
final class PpcCarbonToolbox {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcCarbonToolbox(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;INSTANCES.put(rt,this);}

	static final int NO_ERR=0,UNIMP_ERR=-4,PARAM_ERR=-50,MEM_FULL_ERR=-108,DRAG_NOT_ACCEPTED_ERR=-1857,
		PROPERTY_INVALID_ERR=-5603,PROPERTY_NOT_FOUND_ERR=-5604,MENU_NOT_FOUND_ERR=-5620,MENU_ITEM_NOT_FOUND_ERR=-5622,DIALOG_NO_TIMEOUT_ERR=-5640,
		ERR_DATA_NOT_SUPPORTED=-30581;
	static int ostype(String s){return s.charAt(0)<<24|s.charAt(1)<<16|s.charAt(2)<<8|s.charAt(3);}
	private static final int TAG_TEXT=ostype("text"),TAG_CFSTRING=ostype("cfst"),TAG_PASSWORD=ostype("pass"),TAG_CONTENT=ostype("cont"),TAG_TRANSFORM=ostype("tran"),
		TAG_TEXT_ALIGN=ostype("tali"),TAG_TEXT_OFFSET=ostype("toff"),TAG_GRAPHIC_ALIGN=ostype("gali"),TAG_GRAPHIC_OFFSET=ostype("goff"),TAG_TEXT_PLACE=ostype("tplc"),
		TAG_MENU_VALUE=ostype("mval"),TAG_BEVEL_MENU=ostype("mhnd"),TAG_POPUP_MENU=ostype("mhan"),TAG_TAB_RECT=ostype("rect"),TAG_TAB_ENABLED=ostype("enab"),TAG_TAB_INFO=ostype("tabi"),
		TAG_LAST_VALUE=ostype("last"),TAG_INDETERMINATE=ostype("inde"),TAG_PICTURE=ostype("pich"),TAG_LIST=ostype("lhan"),
		TAG_SCROLL_DELAY=ostype("stdl"),TAG_SCROLL_BETWEEN=ostype("scdl"),TAG_SCROLL_AMOUNT=ostype("samt");
	/** CDEF resource IDs (procID >> 4) of the Appearance kinds some calls check. */
	private static final int CDEF_BEVEL=2,CDEF_TABS=8,CDEF_IMAGE_WELL=11,CDEF_TRIANGLE=4;
	/** procInfo of ControlColorUPP (CarbonLib 1.1, not in the Universal Interfaces 3.3 table): pascal OSStatus (ControlRef, SInt16, SInt16, Boolean). */
	static final int CONTROL_COLOR_PROC_INFO=0x00001af0;
	private static final int REGION_TO_RECTS_PROC_INFO=0x00003fb1;
	/** HMHelpContentRec: version, absHotRect, tagSide, two HMHelpContent {contentType, union up to a Str255} (mac68k alignment). */
	private static final int HELP_CONTENT_SIZE=534;

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	/** The first {@code n} arguments: read before any call that might run PowerPC code (a nested call may reuse r3-r10). */
	private int[] args(int n) throws M68kCpuException {int[] a=new int[n];for(int i=0;i<n;i++)a[i]=rt.arg(i);return a;}
	private void ret(int v){rt.ret(v);}
	private static int clampShort(int v){return Math.max(-32768,Math.min(32767,v));}
	private String cfText(int ref){return ref==0?null:rt.libraries.textServices.cfText(ref);}
	/** A new Pascal string in the program's encoding (the caller frees it). */
	private int pascal(String text) throws M68kCpuException {byte[] b=text==null?new byte[0]:rt.libraries.textServices.systemBytes(text);int n=Math.min(255,b.length);int p=rt.allocate(n+2,2);memory.writeByte(p,n);memory.load(p+1,java.util.Arrays.copyOf(b,n));return p;}
	private int pascalBytes(byte[] b) throws M68kCpuException {int n=Math.min(255,b.length);int p=rt.allocate(n+2,2);memory.writeByte(p,n);memory.load(p+1,b,0,n);return p;}
	private static byte[] word(int v){return new byte[]{(byte)(v>>>8),(byte)v};}
	private static byte[] longs(int... v){byte[] b=new byte[4*v.length];for(int i=0;i<v.length;i++){b[4*i]=(byte)(v[i]>>>24);b[4*i+1]=(byte)(v[i]>>>16);b[4*i+2]=(byte)(v[i]>>>8);b[4*i+3]=(byte)v[i];}return b;}
	/** Calls another CarbonLib native through its transition vector, as a program would (PpcCarbon's menu command IDs, control properties). */
	private int carbon(String name,int... a) throws M68kException {return rt.cpu.call(rt.nativeSymbol("CarbonLib",name,Pef.CLASS_TVECTOR,false),a);}
	private interface Body { void run() throws M68kException; }
	/** Runs {@code body} with {@code port} as the current port. */
	private void inPort(int port,Body body) throws M68kException {
		int slot=rt.allocate(4,4);
		try{rt.toolbox("GetPort",slot);int old=memory.readLong(slot);rt.toolbox("SetPort",port);try{body.run();}finally{rt.toolbox("SetPort",old);}}
		finally{memory.free(slot);}
	}
	/** A locked Handle standing for a definition procedure given as a UPP: its master pointer is the UPP, which a 68K
	 *  manager enters through Mixed Mode like a definition resource's code. */
	private int definitionHandle(int upp) throws M68kCpuException {int h=rt.toolbox.api.createHandle(new byte[4]);rt.toolbox.api.setHandleState(h,0x80);memory.writeLong(h,upp);return h;}
	void define(PpcLibraries l){
		defineQuickDraw(l);defineLists(l);defineControls(l);defineControlCreation(l);defineMenus(l);defineDialogs(l);defineTextEdit(l);defineScrap(l);defineHelp(l);defineMisc(l);
		defineMenuItemData(l);defineMenus16(l);defineDefinitions(l);defineObjectClasses(l); // CarbonLib 1.6 additions
	}

	// ------------------------------------------------------------------ QuickDraw

	/** Handle-valued fields of a port: the colour port's bkPixPat, pnPixPat, fillPixPat; picSave, rgnSave, polySave. */
	private static final int BK_PIX_PAT=32,PN_PIX_PAT=58,FILL_PIX_PAT=62,PIC_SAVE=92,RGN_SAVE=96,POLY_SAVE=100;
	/** True when {@code port} has the CGrafPort layout at offsets 8-15 (grafVars, chExtra, pnLocHFrac): windows made with
	 *  NewWindow keep a classic GrafPort's portBits.bounds there (see QuickDraw68k.newWindowPort). */
	private boolean colorLayout(int port) throws M68kCpuException {return (memory.readWord(port+6)&0xc000)==0xc000&&rt.toolbox.api.isHandle(memory.readLong(port+8));}
	/** CopyPixPat of a port's pattern into the program's PixPatHandle; returns that Handle. */
	private int copyPortPattern(int port,int offset,int out) throws M68kException {int src=memory.readLong(port+offset);if(src!=0&&out!=0&&src!=out)rt.toolbox("CopyPixPat",src,out);return out;}

	private void defineQuickDraw(PpcLibraries l){
		l.register(null,"GetPixBounds",(r,c)->{int pm=arg(0),out=arg(1);memory.copy(memory.readLong(pm)+6,out,8);ret(out);}); // PixMap.bounds
		l.register(null,"GetPortBackPixPat",(r,c)->{int[] a=args(2);ret(copyPortPattern(a[0],BK_PIX_PAT,a[1]));});
		l.register(null,"GetPortPenPixPat",(r,c)->{int[] a=args(2);ret(copyPortPattern(a[0],PN_PIX_PAT,a[1]));});
		l.register(null,"GetPortFillPixPat",(r,c)->{int[] a=args(2);ret(copyPortPattern(a[0],FILL_PIX_PAT,a[1]));});
		l.register(null,"SetPortBackPixPat",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("BackPixPat",a[1]));});
		l.register(null,"SetPortPenPixPat",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("PenPixPat",a[1]));});
		l.register(null,"SetPortFillPixPat",(r,c)->{int[] a=args(2);int old=memory.readLong(a[0]+FILL_PIX_PAT);if(old!=0&&a[1]!=0)rt.toolbox("CopyPixPat",a[1],old);else memory.writeLong(a[0]+FILL_PIX_PAT,a[1]);});
		l.register(null,"GetPortChExtra",(r,c)->{int port=arg(0);ret(colorLayout(port)?(short)memory.readWord(port+12):0);});
		l.register(null,"GetPortSpExtra",(r,c)->ret(memory.readLong(arg(0)+76)));
		l.register(null,"GetPortFracHPenLocation",(r,c)->{int port=arg(0);ret(colorLayout(port)?memory.readWord(port+14):0);});
		l.register(null,"SetPortFracHPenLocation",(r,c)->{int port=arg(0);if(colorLayout(port))memory.writeWord(port+14,arg(1));});
		l.register(null,"GetPortOpColor",(r,c)->{int port=arg(0),out=arg(1); // GrafVars.rgbOpColor; black (OpColor's default) without one
			int vars=colorLayout(port)?memory.readLong(port+8):0;int p=vars==0?0:memory.readLong(vars);if(p!=0)memory.copy(p,out,6);else memory.fill(out,6,0);ret(out);});
		l.register(null,"SetPortOpColor",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("OpColor",a[1]));});
		l.register(null,"SetPortPenMode",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("PenMode",(short)a[1]));});
		l.register(null,"SetPortPenSize",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("PenSize",(short)a[1],(short)(a[1]>>16)));}); // Point by value: v high, h low
		l.register(null,"IsPortOffscreen",(r,c)->ret(offscreen(arg(0))?1:0));
		l.register(null,"IsPortPictureBeingDefined",(r,c)->{int port=arg(0);QuickDraw68k qd=rt.toolbox.api.quickDraw();
			ret(memory.readLong(port+PIC_SAVE)!=0||port==qd.currentPortAddress()&&qd.pictureIsOpen()?1:0);}); // pictures record in the current port
		l.register(null,"IsPortRegionBeingDefined",(r,c)->ret(memory.readLong(arg(0)+RGN_SAVE)!=0?1:0));
		l.register(null,"IsPortPolyBeingDefined",(r,c)->ret(memory.readLong(arg(0)+POLY_SAVE)!=0?1:0));
		l.register(null,"IsValidPort",(r,c)->{int port=arg(0);
			ret(port!=0&&(port&1)==0&&(rt.toolbox.windows.isWindow(port)||(memory.readWord(port+6)&0xc000)==0xc000&&rt.toolbox.api.isHandle(memory.readLong(port+2)))?1:0);});
		l.register(null,"SetQDGlobalsArrow",(r,c)->{int q=qdGlobals();if(q!=0&&arg(0)!=0)memory.copy(arg(0),q-108,68);}); // qd.arrow (InitCursor shows it)
		l.register(null,"QDRegionToRects",(r,c)->{int[] a=args(4);ret(regionToRects(a[0],a[1],a[2],a[3]));});
		l.register(null,"HandleToRgn",(r,c)->{int[] a=args(2);copyHandle(a[0],a[1]);}); // (flattened region Handle, region)
		l.register(null,"RgnToHandle",(r,c)->{int[] a=args(2);copyHandle(a[0],a[1]);});
		// No buffered (Quartz) ports: nothing is ever dirty, and there are no CGContexts
		l.register(null,"QDAddRectToDirtyRegion",(r,c)->ret(NO_ERR));
		l.register(null,"QDAddRegionToDirtyRegion",(r,c)->ret(NO_ERR));
		l.register(null,"CreateCGContextForPort",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(UNIMP_ERR);});
		l.register(null,"QDBeginCGContext",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(UNIMP_ERR);});
		l.register(null,"QDEndCGContext",(r,c)->ret(UNIMP_ERR));
		l.register(null,"ClipCGContextToRegion",(r,c)->ret(UNIMP_ERR));
		l.register(null,"SyncCGContextOriginWithPort",(r,c)->ret(UNIMP_ERR));
		l.register(null,"DisableScreenUpdates",(r,c)->ret(NO_ERR)); // nothing to hold back: windows draw into their own buffers
		l.register(null,"EnableScreenUpdates",(r,c)->ret(NO_ERR));
	}
	/** The address of qd.thePort: the ROM keeps it at 0(A5), where InitGraf put it (CarbonLib's InitGraf for this program). */
	private int qdGlobals() throws M68kCpuException {int a5=rt.cpu68.a[5];return a5==0?0:memory.readLong(a5);}
	/** A GWorld's port: not a window, and its GrafVars carry the offscreen GDevice (QuickDraw68k's private extension at 26). */
	private boolean offscreen(int port) throws M68kCpuException {
		if(port==0||rt.toolbox.windows.isWindow(port)||!colorLayout(port))return false;
		int vars=memory.readLong(port+8);return rt.toolbox.api.handleSize(vars)>=30&&memory.readLong(memory.readLong(vars)+26)!=0;
	}
	private void copyHandle(int from,int to) throws M68kException {
		if(from==0||to==0)return;int n=rt.toolbox("GetHandleSize",from);rt.toolbox("SetHandleSize",to,n);
		int src=memory.readLong(from),dst=memory.readLong(to);if(src!=0&&dst!=0)memory.copy(src,dst,n);
	}
	/**
	 * QDRegionToRects(rgn, dir, proc, refCon): the region as rectangles, handed to the program's RegionToRectsUPP between an init
	 * and a terminate message. The scan lines of the classic region format list the points where membership changes against
	 * the line above; each band between two scan lines is a set of spans, and a span that continues one of the band above
	 * with the same left and right extends that rectangle instead of starting a new one.
	 */
	private int regionToRects(int rgn,int direction,int proc,int refCon) throws M68kException {
		int p=memory.readLong(rgn);int size=memory.readWord(p);
		int top=(short)memory.readWord(p+2),left=(short)memory.readWord(p+4),bottom=(short)memory.readWord(p+6),right=(short)memory.readWord(p+8);
		List<int[]> rects=new ArrayList<int[]>(); // top, left, bottom, right
		if(size<=10){if(bottom>top&&right>left)rects.add(new int[]{top,left,bottom,right});}
		else{
			java.util.TreeSet<Integer> points=new java.util.TreeSet<Integer>();List<int[]> open=new ArrayList<int[]>();
			int at=p+10,end=p+size,band=0;boolean started=false;
			while(at+2<=end){
				int v=(short)memory.readWord(at);at+=2;if(v==0x7fff)break;
				if(started&&v>band){ // the spans between band and v
					List<int[]> next=new ArrayList<int[]>();Integer[] x=points.toArray(new Integer[0]);
					for(int i=0;i+1<x.length;i+=2){int[] span=null;
						for(int[] o:open)if(o[2]==band&&o[1]==x[i]&&o[3]==x[i+1]){span=o;break;}
						if(span!=null)span[2]=v;else{span=new int[]{band,x[i],v,x[i+1]};rects.add(span);}
						next.add(span);}
					open=next;}
				while(at+2<=end){int h=(short)memory.readWord(at);at+=2;if(h==0x7fff)break;if(!points.remove(h))points.add(h);}
				band=v;started=true;
			}
		}
		final boolean fromBottom=(direction&2)!=0,fromRight=(direction&8)!=0;
		rects.sort((x,y)->x[0]!=y[0]?(fromBottom?y[0]-x[0]:x[0]-y[0]):(fromRight?y[1]-x[1]:x[1]-y[1]));
		int rect=rt.allocate(8,4);
		try{
			memory.copy(p+2,rect,8);
			int err=rt.callUniversal(proc,REGION_TO_RECTS_PROC_INFO,new int[]{1,rgn,rect,refCon}); // kQDRegionToRectsMsgInit
			for(int i=0;err==0&&i<rects.size();i++){int[] q=rects.get(i);memory.writeWord(rect,q[0]);memory.writeWord(rect+2,q[1]);memory.writeWord(rect+4,q[2]);memory.writeWord(rect+6,q[3]);
				err=rt.callUniversal(proc,REGION_TO_RECTS_PROC_INFO,new int[]{2,rgn,rect,refCon});} // kQDRegionToRectsMsgParse
			memory.copy(p+2,rect,8);
			int end=rt.callUniversal(proc,REGION_TO_RECTS_PROC_INFO,new int[]{3,rgn,rect,refCon}); // kQDRegionToRectsMsgTerminate
			return err!=0?err:end;
		}finally{memory.free(rect);}
	}

	// ------------------------------------------------------------------ List Manager

	/** ListRec offsets. */
	private static final int L_VIEW=0,L_PORT=8,L_INDENT=12,L_CELL_SIZE=16,L_VISIBLE=20,L_V_SCROLL=28,L_H_SCROLL=32,L_SEL_FLAGS=36,L_ACTIVE=37,L_FLAGS=39,
		L_CLIK_TIME=40,L_CLIK_LOC=44,L_MOUSE_LOC=48,L_CLICK_LOOP=52,L_LAST_CLICK=56,L_REF_CON=60,L_DEF_PROC=64,L_USER_HANDLE=68,L_DATA_BOUNDS=72,L_CELLS=80;
	/** Custom list definitions given as UPPs, by UPP: the LDEF resource ID they are registered under, and their Handle. */
	private final Map<Integer,int[]> customLdefs=new HashMap<Integer,int[]>();
	private int nextCustomLdef=1900;
	private int list(int handle) throws M68kCpuException {return memory.readLong(handle);}

	private void defineLists(PpcLibraries l){
		int[][] copies={{L_VIEW,8},{L_INDENT,4},{L_CELL_SIZE,4},{L_VISIBLE,8},{L_CLIK_LOC,4},{L_MOUSE_LOC,4},{L_DATA_BOUNDS,8}};
		String[] getters={"GetListViewBounds","GetListCellIndent","GetListCellSize","GetListVisibleCells","GetListClickLocation","GetListMouseLocation","GetListDataBounds"};
		for(int i=0;i<getters.length;i++){final int offset=copies[i][0],n=copies[i][1];l.register(null,getters[i],(r,c)->{int out=arg(1);memory.copy(list(arg(0))+offset,out,n);ret(out);});} // (list, *out): returns out
		int[] longs={L_PORT,L_V_SCROLL,L_H_SCROLL,L_CLIK_TIME,L_CLICK_LOOP,L_REF_CON,L_DEF_PROC,L_USER_HANDLE,L_CELLS};
		String[] longGetters={"GetListPort","GetListVerticalScrollBar","GetListHorizontalScrollBar","GetListClickTime","GetListClickLoop","GetListRefCon","GetListDefinition","GetListUserHandle","GetListDataHandle"};
		for(int i=0;i<longs.length;i++){final int offset=longs[i];l.register(null,longGetters[i],(r,c)->ret(memory.readLong(list(arg(0))+offset)));}
		l.register(null,"GetListActive",(r,c)->ret(memory.readByte(list(arg(0))+L_ACTIVE)!=0?1:0));
		l.register(null,"GetListFlags",(r,c)->ret(memory.readByte(list(arg(0))+L_FLAGS)));
		l.register(null,"GetListSelectionFlags",(r,c)->ret(memory.readByte(list(arg(0))+L_SEL_FLAGS)));
		l.register(null,"SetListViewBounds",(r,c)->memory.copy(arg(1),list(arg(0))+L_VIEW,8)); // the List Manager adopts rView and port from the record at its next call
		l.register(null,"SetListCellIndent",(r,c)->memory.copy(arg(1),list(arg(0))+L_INDENT,4));
		l.register(null,"SetListLastClick",(r,c)->memory.copy(arg(1),list(arg(0))+L_LAST_CLICK,4));
		String[] longSetters={"SetListPort","SetListClickTime","SetListClickLoop","SetListRefCon","SetListUserHandle"};int[] setOffsets={L_PORT,L_CLIK_TIME,L_CLICK_LOOP,L_REF_CON,L_USER_HANDLE};
		for(int i=0;i<longSetters.length;i++){final int offset=setOffsets[i];l.register(null,longSetters[i],(r,c)->memory.writeLong(list(arg(0))+offset,arg(1)));}
		l.register(null,"SetListFlags",(r,c)->memory.writeByte(list(arg(0))+L_FLAGS,arg(1)));
		l.register(null,"SetListSelectionFlags",(r,c)->memory.writeByte(list(arg(0))+L_SEL_FLAGS,arg(1)));
		l.register(null,"CreateCustomList",(r,c)->{ // (rView, dataBounds, cellSize (Point), ListDefSpec*, window, drawIt, hasGrow, scrollHoriz, scrollVert, *outList)
			int[] a=args(10);int spec=a[3],type=spec==0?1:memory.readLong(spec);
			if(type!=0&&type!=1&&type!=2){ret(PARAM_ERR);return;}
			int proc=0,handle=0;
			if(type==0){int[] def=customLdef(memory.readLong(spec+4));handle=def[1];if(def[0]>0)proc=def[0];} // kListDefProcPtr; text (1) and icon (2) lists: the standard LDEF
			int list=rt.toolbox("LNew",a[0],a[1],a[2],proc,a[4],a[5]&0xff,a[6]&0xff,a[7]&0xff,a[8]&0xff);
			if(list!=0&&handle!=0)memory.writeLong(list(list)+L_DEF_PROC,handle);
			if(a[9]!=0)memory.writeLong(a[9],list);ret(list==0?MEM_FULL_ERR:NO_ERR);});
		l.register(null,"RegisterListDefinition",(r,c)->{int[] a=args(2);int id=(short)a[0],spec=a[1]; // (resID, ListDefSpec*): a kListDefProcPtr spec runs like an LDEF of that ID
			if(spec!=0&&memory.readLong(spec)!=0){ret(PARAM_ERR);return;}
			rt.toolbox.listManager.registerDefinition(id,spec==0?0:definitionHandle(memory.readLong(spec+4)));ret(NO_ERR);});
	}
	/** The LDEF ID (0 when the List Manager takes no registrations) and Handle standing for a list definition UPP. */
	private int[] customLdef(int upp) throws M68kCpuException {
		int[] def=customLdefs.get(upp);if(def!=null)return def;
		int handle=definitionHandle(upp),id=nextCustomLdef;
		rt.toolbox.listManager.registerDefinition(id,handle);nextCustomLdef++;
		def=new int[]{id,handle};customLdefs.put(upp,def);return def;
	}

	// ------------------------------------------------------------------ Control Manager

	private final Map<Integer,Integer> viewSizes=new HashMap<Integer,Integer>(),colorProcs=new HashMap<Integer,Integer>(),commandIds=new HashMap<Integer,Integer>();
	private final Set<Integer> dragTracking=new HashSet<Integer>(),automaticDragTracking=new HashSet<Integer>();
	private final Map<Long,Integer> controlPropertyAttributes=new HashMap<Long,Integer>();
	private final Map<Integer,Integer> customCdefs=new HashMap<Integer,Integer>();
	private int nextCustomCdef=1900; // procID = id<<4 stays a positive short
	private static long propertyKey(int object,int creator,int tag){return ((long)object<<32)^((long)creator*31+tag)&0xffffffffL;}
	private int record(int control) throws M68kCpuException {return memory.readLong(control);}
	/** The CDEF resource ID (procID >> 4) of a control, or -1. */
	private int cdef(int control){ControlManager68k.Control k=rt.toolbox.controls.controls.get(control);return k==null?-1:k.procId>>4;}
	/** SetControlData(control, part, tag, bytes); returns its OSErr. */
	private int setData(int control,int part,int tag,byte[] v) throws M68kException {
		int p=rt.allocate(Math.max(4,v.length),4);
		try{memory.load(p,v);return (short)rt.toolbox("SetControlData",control,part,tag,v.length,p);}finally{memory.free(p);}
	}
	/** GetControlData of a tag set before, or null. */
	private byte[] getData(int control,int part,int tag) throws M68kException {
		int size=rt.allocate(4,4);
		try{if((short)rt.toolbox("GetControlDataSize",control,part,tag,size)!=0)return null;int n=memory.readLong(size);if(n<=0)return new byte[0];
			int p=rt.allocate(n,4);try{if((short)rt.toolbox("GetControlData",control,part,tag,n,p,size)!=0)return null;return memory.readBytes(p,n);}finally{memory.free(p);}}
		finally{memory.free(size);}
	}
	/** A data tag into {@code out}: what was set, else {@code fallback} for a control of the kind that keeps the tag
	 *  (CDEF {@code kind}), else errDataNotSupported. */
	private int getTag(int control,int tag,int out,byte[] fallback,int kind) throws M68kException {
		byte[] v=getData(control,0,tag);
		if(v==null){if(cdef(control)!=kind)return ERR_DATA_NOT_SUPPORTED;v=fallback;}
		if(out!=0)memory.load(out,java.util.Arrays.copyOf(v,fallback.length));return NO_ERR;
	}

	private void defineControls(PpcLibraries l){
		// 32-bit values: this Control Manager keeps the classic short fields, so values are clamped to them
		l.register(null,"GetControl32BitValue",(r,c)->ret((short)rt.toolbox("GetControlValue",arg(0))));
		l.register(null,"GetControl32BitMinimum",(r,c)->ret((short)rt.toolbox("GetControlMinimum",arg(0))));
		l.register(null,"GetControl32BitMaximum",(r,c)->ret((short)rt.toolbox("GetControlMaximum",arg(0))));
		l.register(null,"SetControl32BitValue",(r,c)->{int[] a=args(2);rt.toolbox("SetControlValue",a[0],clampShort(a[1]));});
		l.register(null,"SetControl32BitMinimum",(r,c)->{int[] a=args(2);rt.toolbox("SetControlMinimum",a[0],clampShort(a[1]));});
		l.register(null,"SetControl32BitMaximum",(r,c)->{int[] a=args(2);rt.toolbox("SetControlMaximum",a[0],clampShort(a[1]));});
		l.register(null,"GetControlViewSize",(r,c)->{Integer v=viewSizes.get(arg(0));ret(v==null?0:v);}); // kept for proportional thumbs
		l.register(null,"SetControlViewSize",(r,c)->viewSizes.put(arg(0),arg(1)));
		l.register(null,"SetControlDataHandle",(r,c)->memory.writeLong(record(arg(0))+28,arg(1))); // contrlData
		l.register(null,"SetControlPopupMenuHandle",(r,c)->{int[] a=args(2);setPopupMenu(a[0],a[1],a[1]==0?0:(short)memory.readWord(memory.readLong(a[1])));});
		l.register(null,"SetControlPopupMenuID",(r,c)->{int[] a=args(2);int id=(short)a[1]; // popupPrivateData.mID; the menu of that ID when the pop-up has none (or another)
			int data=memory.readLong(record(a[0])+28);int menu=data==0?0:memory.readLong(memory.readLong(data));
			if(menu==0||(short)memory.readWord(memory.readLong(menu))!=id){int h=rt.toolbox("GetMenuHandle",id);if(h!=0)menu=h;}
			setPopupMenu(a[0],menu,id);});
		l.register(null,"IsControlHilited",(r,c)->{int hilite=memory.readByte(record(arg(0))+17);ret(hilite!=0&&hilite<254?1:0);}); // a part, not kControlDisabledPart/InactivePart
		l.register(null,"GetControlClickActivation",(r,c)->{if(arg(3)!=0)memory.writeLong(arg(3),2);ret(NO_ERR);}); // kActivateAndIgnoreClick: the standard controls' answer
		l.register(null,"HandleControlSetCursor",(r,c)->{if(arg(3)!=0)memory.writeByte(arg(3),0);ret(NO_ERR);}); // the controls here have no cursor of their own
		l.register(null,"HandleControlContextualMenuClick",(r,c)->{if(arg(2)!=0)memory.writeByte(arg(2),0);ret(NO_ERR);});
		l.register(null,"HandleControlDragTracking",(r,c)->{if(arg(3)!=0)memory.writeByte(arg(3),0);ret(NO_ERR);}); // no control accepts drags
		l.register(null,"HandleControlDragReceive",(r,c)->ret(DRAG_NOT_ACCEPTED_ERR));
		l.register(null,"SetControlDragTrackingEnabled",(r,c)->{if((arg(1)&0xff)!=0)dragTracking.add(arg(0));else dragTracking.remove(arg(0));ret(NO_ERR);});
		l.register(null,"IsControlDragTrackingEnabled",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),dragTracking.contains(arg(0))?1:0);ret(NO_ERR);});
		l.register(null,"SetAutomaticControlDragTrackingEnabledForWindow",(r,c)->{if((arg(1)&0xff)!=0)automaticDragTracking.add(arg(0));else automaticDragTracking.remove(arg(0));ret(NO_ERR);});
		l.register(null,"IsAutomaticControlDragTrackingEnabledForWindow",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),automaticDragTracking.contains(arg(0))?1:0);ret(NO_ERR);});
		l.register(null,"SetUpControlTextColor",(r,c)->{int ctl=arg(0);int rgb=rt.allocate(6,2); // black text, grey when the control is inactive
			try{if(memory.readByte(record(ctl)+17)==255){memory.writeWord(rgb,0x7777);memory.writeWord(rgb+2,0x7777);memory.writeWord(rgb+4,0x7777);}rt.toolbox("RGBForeColor",rgb);}finally{memory.free(rgb);}
			ret(NO_ERR);});
		l.register(null,"SetControlColorProc",(r,c)->{if(arg(1)==0)colorProcs.remove(arg(0));else colorProcs.put(arg(0),arg(1));ret(NO_ERR);});
		l.register(null,"NewControlColorUPP",(r,c)->ret(rt.newRoutineDescriptor(arg(0),CONTROL_COLOR_PROC_INFO,PpcRuntime.ISA_PPC)));
		l.register(null,"DisposeControlColorUPP",(r,c)->{int rd=arg(0);if(rd!=0&&rt.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);});
		l.register(null,"InvokeControlColorUPP",(r,c)->{int[] a=args(5);ret(rt.callUniversal(a[4],CONTROL_COLOR_PROC_INFO,new int[]{a[0],a[1],a[2],a[3]}));});
		l.register(null,"SetControlCommandID",(r,c)->{commandIds.put(arg(0),arg(1));ret(NO_ERR);});
		l.register(null,"GetControlCommandID",(r,c)->{Integer v=commandIds.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),v==null?0:v);ret(NO_ERR);});
		l.register(null,"GetControlPropertyAttributes",(r,c)->{int[] a=args(4); // the property itself is PpcCarbon's (SetControlProperty); its attributes are kept here
			int err=controlPropertyExists(a[0],a[1],a[2]);if(err!=0){ret(err);return;}
			Integer v=controlPropertyAttributes.get(propertyKey(a[0],a[1],a[2]));if(a[3]!=0)memory.writeLong(a[3],v==null?0:v);ret(NO_ERR);});
		l.register(null,"ChangeControlPropertyAttributes",(r,c)->{int[] a=args(5);
			int err=controlPropertyExists(a[0],a[1],a[2]);if(err!=0){ret(err);return;}
			long key=propertyKey(a[0],a[1],a[2]);Integer v=controlPropertyAttributes.get(key);controlPropertyAttributes.put(key,((v==null?0:v)|a[3])&~a[4]);ret(NO_ERR);});
		l.register(null,"CreateCustomControl",(r,c)->{ // (window, bounds, ControlDefSpec*, initData, *outControl): a kControlDefProcPtr spec runs like a CDEF
			int[] a=args(5);int spec=a[2];
			if(spec==0||memory.readLong(spec)!=0&&memory.readLong(spec)!=1){ret(PARAM_ERR);return;}
			if(memory.readLong(spec)==1){int id=controlDefinitionId(memory.readLong(spec+4)); // kControlDefObjectClass: a CDEF turning messages into the class's control events
				if(id==0){if(a[4]!=0)memory.writeLong(a[4],0);ret(PARAM_ERR);return;}ret(control(a[0],a[1],null,0,0,0,id<<4,a[4])==0?MEM_FULL_ERR:NO_ERR);return;}
			int upp=memory.readLong(spec+4);Integer id=customCdefs.get(upp);
			if(id==null){id=nextCustomCdef++;customCdefs.put(upp,id);rt.toolbox.controls.registerDefinition(id,definitionHandle(upp));}
			ret(control(a[0],a[1],null,0,0,0,id<<4,a[4])==0?MEM_FULL_ERR:NO_ERR);});
		// ---- ControlDefinitions.h accessors: the Appearance CDEFs' data tags
		l.register(null,"GetBevelButtonMenuValue",(r,c)->{int[] a=args(2);ret(getTag(a[0],TAG_MENU_VALUE,a[1],new byte[2],CDEF_BEVEL));});
		l.register(null,"SetBevelButtonMenuValue",(r,c)->{int[] a=args(2);ret(setData(a[0],0,TAG_MENU_VALUE,word(a[1])));});
		l.register(null,"GetBevelButtonMenuHandle",(r,c)->{int[] a=args(2);ret(getTag(a[0],TAG_BEVEL_MENU,a[1],new byte[4],CDEF_BEVEL));});
		l.register(null,"GetBevelButtonContentInfo",(r,c)->{int[] a=args(2);ret(getTag(a[0],TAG_CONTENT,a[1],new byte[6],CDEF_BEVEL));});
		l.register(null,"SetBevelButtonContentInfo",(r,c)->{int[] a=args(2);ret(a[1]==0?PARAM_ERR:setData(a[0],0,TAG_CONTENT,memory.readBytes(a[1],6)));});
		l.register(null,"SetBevelButtonTransform",(r,c)->{int[] a=args(2);ret(setData(a[0],0,TAG_TRANSFORM,word(a[1])));});
		l.register(null,"SetBevelButtonGraphicAlignment",(r,c)->{int[] a=args(4); // (button, alignment, hOffset, vOffset): the offset is a Point
			int err=setData(a[0],0,TAG_GRAPHIC_ALIGN,word(a[1]));if(err==0)err=setData(a[0],0,TAG_GRAPHIC_OFFSET,new byte[]{(byte)(a[3]>>8),(byte)a[3],(byte)(a[2]>>8),(byte)a[2]});ret(err);});
		l.register(null,"SetBevelButtonTextAlignment",(r,c)->{int[] a=args(3);int err=setData(a[0],0,TAG_TEXT_ALIGN,word(a[1]));if(err==0)err=setData(a[0],0,TAG_TEXT_OFFSET,word(a[2]));ret(err);});
		l.register(null,"SetBevelButtonTextPlacement",(r,c)->{int[] a=args(2);ret(setData(a[0],0,TAG_TEXT_PLACE,word(a[1])));});
		l.register(null,"GetImageWellContentInfo",(r,c)->{int[] a=args(2);ret(getTag(a[0],TAG_CONTENT,a[1],new byte[6],CDEF_IMAGE_WELL));});
		l.register(null,"SetImageWellContentInfo",(r,c)->{int[] a=args(2);ret(a[1]==0?PARAM_ERR:setData(a[0],0,TAG_CONTENT,memory.readBytes(a[1],6)));});
		l.register(null,"SetImageWellTransform",(r,c)->{int[] a=args(2);ret(setData(a[0],0,TAG_TRANSFORM,word(a[1])));});
		l.register(null,"GetTabContentRect",(r,c)->{int[] a=args(2);int ctl=a[0],out=a[1]; // what was set, else the bounds below the tabs
			byte[] v=getData(ctl,0,TAG_TAB_RECT);if(v!=null&&v.length>=8){memory.load(out,v,0,8);ret(NO_ERR);return;}
			if(cdef(ctl)!=CDEF_TABS){ret(ERR_DATA_NOT_SUPPORTED);return;}
			int p=record(ctl);boolean small=(rt.toolbox.controls.controls.get(ctl).procId&1)!=0;
			memory.writeWord(out,(short)memory.readWord(p+8)+(small?17:24)+1);memory.writeWord(out+2,(short)memory.readWord(p+10)+1);
			memory.writeWord(out+4,(short)memory.readWord(p+12)-1);memory.writeWord(out+6,(short)memory.readWord(p+14)-1);ret(NO_ERR);});
		l.register(null,"SetTabEnabled",(r,c)->{int[] a=args(3);ret(setData(a[0],(short)a[1],TAG_TAB_ENABLED,new byte[]{(byte)((a[2]&0xff)!=0?1:0)}));});
		l.register(null,"SetDisclosureTriangleLastValue",(r,c)->{int[] a=args(2);ret(setData(a[0],0,TAG_LAST_VALUE,word(a[1])));});
	}
	/** The pop-up's menu (popupPrivateData {menuHandle, menuID}) and its range, as the pop-up CDEF keeps them. */
	private void setPopupMenu(int control,int menu,int id) throws M68kException {
		int data=memory.readLong(record(control)+28);if(data==0||memory.readLong(data)==0)return;
		int pd=memory.readLong(data);memory.writeLong(pd,menu);memory.writeWord(pd+4,id);
		// the range follows the menu but a value of 0 (nothing chosen) stays 0: REALbasic sets its row menu here and a new
		// PopupMenu keeps ListIndex -1 until the program sets it (SetControlMinimum would have raised it to the first row)
		if(menu!=0){int p=record(control);memory.writeWord(p+20,1);rt.toolbox("SetControlMaximum",control,Math.max(1,(short)rt.toolbox("CountMenuItems",menu)));}
	}
	private int controlPropertyExists(int control,int creator,int tag) throws M68kException {
		int size=rt.allocate(4,4);try{return carbon("GetControlPropertySize",control,creator,tag,size);}finally{memory.free(size);}
	}

	// ------------------------------------------------------------------ Create...Control (CarbonLib 1.1)

	/** NewControl, visible, with a CFString title; the ControlRef into *out (NIL on failure); returns it. */
	private int control(int window,int bounds,String title,int value,int min,int max,int procId,int out) throws M68kException {
		int p=pascal(title);int ctl;
		try{ctl=rt.toolbox("NewControl",window,bounds,p,1,clampShort(value),clampShort(min),clampShort(max),procId,0);}finally{memory.free(p);}
		if(out!=0)memory.writeLong(out,ctl);return ctl;
	}
	private void created(int ctl){ret(ctl==0?MEM_FULL_ERR:NO_ERR);}
	/** The text of a Create...Control CFString for the text tags, in the program's encoding. */
	private byte[] textBytes(int cfString){String t=cfText(cfString);return t==null?new byte[0]:rt.libraries.textServices.systemBytes(t);}
	private void fontStyle(int ctl,int style) throws M68kException {if(ctl!=0&&style!=0)rt.toolbox("SetControlFontStyle",ctl,style);}
	private void content(int ctl,int info) throws M68kException {if(ctl!=0&&info!=0)setData(ctl,0,TAG_CONTENT,memory.readBytes(info,6));}
	/** The resource ID of a resource-based ControlButtonContentInfo (types 1-4), else 0. */
	private int contentResId(int info) throws M68kCpuException {if(info==0)return 0;int type=(short)memory.readWord(info);return type>=1&&type<=4?(short)memory.readWord(info+2):0;}

	private void defineControlCreation(PpcLibraries l){
		// the kinds this Control Manager draws natively: classic procIDs (pushButProc, checkBoxProc, radioButProc, scrollBarProc, popupMenuProc)
		l.register(null,"CreatePushButtonControl",(r,c)->{int[] a=args(4);created(control(a[0],a[1],cfText(a[2]),0,0,1,0,a[3]));});
		l.register(null,"CreateCheckBoxControl",(r,c)->{int[] a=args(6);created(control(a[0],a[1],cfText(a[2]),a[3],0,2,1,a[5]));}); // max 2: kControlCheckBoxMixedValue
		l.register(null,"CreateRadioButtonControl",(r,c)->{int[] a=args(6);created(control(a[0],a[1],cfText(a[2]),a[3],0,2,2,a[5]));});
		l.register(null,"CreateScrollBarControl",(r,c)->{ // (window, bounds, value, min, max, viewSize, liveTracking, liveTrackingProc, *out)
			int[] a=args(9);int ctl=control(a[0],a[1],null,a[2],a[3],a[4],16,a[8]);
			if(ctl!=0){viewSizes.put(ctl,a[5]);if((a[6]&0xff)!=0&&a[7]!=0)rt.toolbox("SetControlAction",ctl,a[7]);}created(ctl);});
		l.register(null,"CreatePopupButtonControl",(r,c)->{ // (window, bounds, title, menuID, variableWidth, titleWidth, titleJustification, titleStyle, *out)
			int[] a=args(9);created(control(a[0],a[1],cfText(a[2]),1,(short)a[3],(short)a[5],1008+((a[4]&0xff)!=0?2:0),a[8]));}); // min = the menu, max = the title width
		l.register(null,"CreatePushButtonWithIconControl",(r,c)->{ // (window, bounds, title, icon, alignment, *out): kControlPushButLeft/RightIconProc
			int[] a=args(6);int ctl=control(a[0],a[1],cfText(a[2]),0,0,1,368+((short)a[4]==7?7:6),a[5]);content(ctl,a[3]);created(ctl);});
		// the other Appearance kinds: Appearance procIDs, content in their data tags
		l.register(null,"CreateStaticTextControl",(r,c)->{int[] a=args(5); // (window, bounds, text, style, *out)
			int ctl=control(a[0],a[1],null,0,0,0,288,a[4]);if(ctl!=0){setData(ctl,0,TAG_TEXT,textBytes(a[2]));fontStyle(ctl,a[3]);}created(ctl);});
		l.register(null,"CreateEditTextControl",(r,c)->{int[] a=args(7); // (window, bounds, text, isPassword, useInlineInput, style, *out)
			boolean password=(a[3]&0xff)!=0;int ctl=control(a[0],a[1],null,0,0,0,password?274:(a[4]&0xff)!=0?276:272,a[6]);
			if(ctl!=0){byte[] t=textBytes(a[2]);setData(ctl,0,TAG_TEXT,t);if(password)setData(ctl,0,TAG_PASSWORD,t);fontStyle(ctl,a[5]);}created(ctl);});
		l.register(null,"CreateEditUnicodeTextControl",(r,c)->{int[] a=args(6); // (window, bounds, text, isPassword, style, *out)
			int ctl=control(a[0],a[1],null,0,0,0,(a[3]&0xff)!=0?914:912,a[5]);
			if(ctl!=0){setData(ctl,0,TAG_TEXT,textBytes(a[2]));setData(ctl,0,TAG_CFSTRING,longs(rt.libraries.textServices.newCFString(cfText(a[2]))));fontStyle(ctl,a[4]);} // the control's own copy of the text
			created(ctl);});
		l.register(null,"CreateBevelButtonControl",(r,c)->{ // (window, bounds, title, thickness, behavior, content, menuID, menuBehavior, menuPlacement, *out)
			int[] a=args(10);int ctl=control(a[0],a[1],cfText(a[2]),0,(a[4]|a[7])&0xff00,1,32+(a[3]&3)+(((short)a[8]&4)!=0?4:0),a[9]);
			if(ctl!=0){content(ctl,a[5]);int id=(short)a[6];
				if(id!=0){int menu=rt.toolbox("GetMenuHandle",id);if(menu==0)menu=rt.toolbox("GetMenu",id);if(menu!=0){setData(ctl,0,TAG_BEVEL_MENU,longs(menu));setData(ctl,0,TAG_MENU_VALUE,word(0));}}}
			created(ctl);});
		l.register(null,"CreateSliderControl",(r,c)->{ // (window, bounds, value, min, max, orientation, numTickMarks, liveTracking, liveTrackingProc, *out)
			int[] a=args(10);int orientation=(short)a[5];boolean live=(a[7]&0xff)!=0;
			int variant=(live?1:0)|((a[6]&0xffff)>0?2:0)|(orientation==1?4:0)|(orientation==2?8:0); // live feedback, tick marks, reverse, non-directional
			int ctl=control(a[0],a[1],null,a[2],a[3],a[4],48+variant,a[9]);if(ctl!=0&&live&&a[8]!=0)rt.toolbox("SetControlAction",ctl,a[8]);created(ctl);});
		l.register(null,"CreateProgressBarControl",(r,c)->{int[] a=args(7); // (window, bounds, value, min, max, indeterminate, *out)
			int ctl=control(a[0],a[1],null,a[2],a[3],a[4],80,a[6]);if(ctl!=0&&(a[5]&0xff)!=0)setData(ctl,0,TAG_INDETERMINATE,new byte[]{1});created(ctl);});
		l.register(null,"CreateRelevanceBarControl",(r,c)->{int[] a=args(6);created(control(a[0],a[1],null,a[2],a[3],a[4],81,a[5]));});
		l.register(null,"CreateLittleArrowsControl",(r,c)->{int[] a=args(7);created(control(a[0],a[1],null,a[2],a[3],a[4],96,a[6]));});
		l.register(null,"CreateChasingArrowsControl",(r,c)->{int[] a=args(3);created(control(a[0],a[1],null,0,0,0,112,a[2]));});
		l.register(null,"CreateSeparatorControl",(r,c)->{int[] a=args(3);created(control(a[0],a[1],null,0,0,0,144,a[2]));});
		l.register(null,"CreatePlacardControl",(r,c)->{int[] a=args(3);created(control(a[0],a[1],null,0,0,0,224,a[2]));});
		l.register(null,"CreateRadioGroupControl",(r,c)->{int[] a=args(3);created(control(a[0],a[1],null,0,0,0,416,a[2]));});
		l.register(null,"CreateWindowHeaderControl",(r,c)->{int[] a=args(4);created(control(a[0],a[1],null,0,0,0,336+((a[2]&0xff)!=0?1:0),a[3]));});
		l.register(null,"CreateUserPaneControl",(r,c)->{int[] a=args(4);created(control(a[0],a[1],null,a[2],0,a[2],256,a[3]));}); // value = the feature bits
		l.register(null,"CreateGroupBoxControl",(r,c)->{int[] a=args(5);created(control(a[0],a[1],cfText(a[2]),0,0,0,(a[3]&0xff)!=0?160:164,a[4]));});
		l.register(null,"CreateCheckGroupBoxControl",(r,c)->{int[] a=args(7);created(control(a[0],a[1],cfText(a[2]),a[3],0,2,(a[4]&0xff)!=0?161:165,a[6]));});
		l.register(null,"CreatePopupGroupBoxControl",(r,c)->{ // (window, bounds, title, primary, menuID, variableWidth, titleWidth, titleJustification, titleStyle, *out)
			int[] a=args(10);int ctl=control(a[0],a[1],cfText(a[2]),0,0,0,(a[3]&0xff)!=0?162:166,a[9]);
			if(ctl!=0){int menu=rt.toolbox("GetMenuHandle",(short)a[4]);if(menu!=0)setData(ctl,0,TAG_POPUP_MENU,longs(menu));}created(ctl);});
		l.register(null,"CreatePopupArrowControl",(r,c)->{int[] a=args(5);created(control(a[0],a[1],null,0,0,0,192+((short)a[2]&3)+((short)a[3]==1?4:0),a[4]));}); // orientation, small size
		l.register(null,"CreateDisclosureTriangleControl",(r,c)->{ // (window, bounds, orientation, title, initialValue, drawTitle, autoToggles, *out)
			int[] a=args(8);int proc=64+((short)a[2]==2?1:0)+((a[6]&0xff)!=0?2:0);
			created(control(a[0],a[1],(a[5]&0xff)!=0?cfText(a[3]):null,a[4],0,1,proc,a[7]));});
		l.register(null,"CreateTabsControl",(r,c)->{ // (window, bounds, size, direction, numTabs, ControlTabEntry[] {icon, name, enabled}, *out)
			int[] a=args(7);int count=a[4]&0xffff;int proc=128+2*((short)a[3]&3)+((short)a[2]==1?1:0);
			int ctl=control(a[0],a[1],null,0,count>0?1:0,count,proc,a[6]);
			if(ctl!=0&&a[5]!=0)for(int i=0;i<count;i++){int e=a[5]+10*i; // ControlTabInfoRec {version 0, iconSuiteID, Str255 name} per tab
				byte[] info=new byte[260];int icon=memory.readLong(e);if(icon!=0&&(short)memory.readWord(icon)==1){info[2]=(byte)memory.readByte(icon+2);info[3]=(byte)memory.readByte(icon+3);}
				byte[] name=textBytes(memory.readLong(e+4));int n=Math.min(255,name.length);info[4]=(byte)n;System.arraycopy(name,0,info,5,n);
				setData(ctl,i+1,TAG_TAB_INFO,info);setData(ctl,i+1,TAG_TAB_ENABLED,new byte[]{(byte)(memory.readByte(e+8)!=0?1:0)});}
			if(ctl!=0&&count>0)rt.toolbox("SetControlValue",ctl,1);
			created(ctl);});
		l.register(null,"CreateIconControl",(r,c)->{ // (window, bounds, content, dontTrack, *out): ICON/cicn, icon suite or IconRef variant by content type
			int[] a=args(5);int type=a[2]==0?0:(short)memory.readWord(a[2]);int base=type==1||type==129?322:type==132?324:320;
			int ctl=control(a[0],a[1],null,contentResId(a[2]),0,contentResId(a[2]),base+((a[3]&0xff)!=0?1:0),a[4]);content(ctl,a[2]);created(ctl);});
		l.register(null,"CreatePictureControl",(r,c)->{int[] a=args(5); // (window, bounds, content, dontTrack, *out)
			int ctl=control(a[0],a[1],null,contentResId(a[2]),0,contentResId(a[2]),304+((a[3]&0xff)!=0?1:0),a[4]);content(ctl,a[2]);
			if(ctl!=0&&a[2]!=0&&(short)memory.readWord(a[2])==131)setData(ctl,0,TAG_PICTURE,longs(memory.readLong(a[2]+2)));created(ctl);}); // kControlContentPictHandle
		l.register(null,"CreateImageWellControl",(r,c)->{int[] a=args(4); // (window, bounds, content, *out)
			int ctl=control(a[0],a[1],null,contentResId(a[2]),0,contentResId(a[2]),176,a[3]);content(ctl,a[2]);created(ctl);});
		l.register(null,"CreateClockControl",(r,c)->{int[] a=args(5);created(control(a[0],a[1],null,a[3],0,a[3],240+((short)a[2]&3),a[4]));}); // (window, bounds, clockType, flags, *out)
		l.register(null,"CreateScrollingTextBoxControl",(r,c)->{ // (window, bounds, contentResID, autoScroll, delayBefore, delayBetween, amount, *out)
			int[] a=args(8);boolean auto=(a[3]&0xff)!=0;int ctl=control(a[0],a[1],null,(short)a[2],0,(short)a[2],432+(auto?1:0),a[7]);
			if(ctl!=0&&auto){setData(ctl,0,TAG_SCROLL_DELAY,longs(a[4]));setData(ctl,0,TAG_SCROLL_BETWEEN,longs(a[5]));setData(ctl,0,TAG_SCROLL_AMOUNT,word(a[6]));}created(ctl);});
		l.register(null,"CreateListBoxControl",(r,c)->{ // (window, bounds, autoSize, rows, columns, horizScroll, vertScroll, cellHeight, cellWidth, hasGrowSpace, ListDefSpec*, *out)
			int[] a=args(12);int ctl=control(a[0],a[1],null,0,0,0,(a[2]&0xff)!=0?353:352,a[11]);
			if(ctl!=0){ // the list inside the box's frame, left of its scroll bar
				boolean h=(a[5]&0xff)!=0,v=(a[6]&0xff)!=0;int rv=rt.allocate(8,2),db=rt.allocate(8,2);
				try{int top=(short)memory.readWord(a[1]),left=(short)memory.readWord(a[1]+2),bottom=(short)memory.readWord(a[1]+4),right=(short)memory.readWord(a[1]+6);
					memory.writeWord(rv,top+1);memory.writeWord(rv+2,left+1);memory.writeWord(rv+4,bottom-1-(h?15:0));memory.writeWord(rv+6,right-1-(v?15:0));
					memory.writeWord(db+4,(short)a[3]);memory.writeWord(db+6,(short)a[4]);
					int spec=a[10],proc=0;if(spec!=0&&memory.readLong(spec)==0){int[] def=customLdef(memory.readLong(spec+4));if(def[0]>0)proc=def[0];}
					int list=rt.toolbox("LNew",rv,db,((short)a[7]<<16)|((short)a[8]&0xffff),proc,a[0],1,(a[9]&0xff),h?1:0,v?1:0);
					if(list!=0)setData(ctl,0,TAG_LIST,longs(list));}
				finally{memory.free(rv);memory.free(db);}}
			created(ctl);});
	}

	// ------------------------------------------------------------------ Menu Manager

	private final Map<Integer,Integer> menuAttributes=new HashMap<Integer,Integer>(),menuFonts=new HashMap<Integer,Integer>(),menuRetainCounts=new HashMap<Integer,Integer>();
	private final Map<Long,Integer> itemAttributes=new HashMap<Long,Integer>(),itemIndents=new HashMap<Long,Integer>();
	/** Menu item properties by (menu, item, creator, tag): the data and its attributes. */
	private static final class Property { byte[] data;int attributes;Property(byte[] d){data=d;} }
	private final Map<String,Property> itemProperties=new HashMap<String,Property>();
	private static long itemKey(int menu,int item){return ((long)menu<<16)|(item&0xffff);}
	private static String propertyName(int menu,int item,int creator,int tag){return menu+"/"+(item&0xffff)+"/"+creator+"/"+tag;}
	private static final int MENU_ATTR_EXCLUDES_MARK=1,ITEM_ATTR_DISABLED=1,ITEM_ATTR_SEPARATOR=1<<6;
	/** The menu and item with a command ID ({@code menu} 0 = any menu), through PpcCarbon's GetIndMenuItemWithCommandID; null when none has it. */
	private int[] commandItem(int menu,int command) throws M68kException {
		int out=rt.allocate(8,4);
		try{if(carbon("GetIndMenuItemWithCommandID",menu,command,1,out,out+4)!=0)return null;return new int[]{memory.readLong(out),memory.readWord(out+4)};}
		finally{memory.free(out);}
	}
	/** The menus of the menu bar (the MenuList's regular part). */
	private List<Integer> menuBarMenus() throws M68kCpuException {
		List<Integer> menus=new ArrayList<Integer>();int h=memory.readLong(0xa1c);if(h==0||memory.readLong(h)==0)return menus;
		int p=memory.readLong(h),last=memory.readWord(p);for(int at=6;at<=last;at+=6){int m=memory.readLong(p+at);if(m!=0)menus.add(m);}
		return menus;
	}
	private boolean isSeparator(int menu,int item) throws M68kException {
		int text=rt.allocate(256,2);try{rt.toolbox("GetMenuItemText",menu,item,text);return memory.readByte(text)>0&&memory.readByte(text+1)=='-';}finally{memory.free(text);}
	}
	/** Submenus attached with SetMenuItemHierarchicalMenu, by (menu, item): any MenuRef, where the classic encoding holds IDs 1-255 only. */
	private final Map<Long,Integer> hierarchicalMenus=new HashMap<Long,Integer>();
	/** The submenu of an item (SetMenuItemHierarchicalMenu, or SetItemCmd $1B with the mark = its menu ID), or 0. */
	private int submenu(int menu,int item) throws M68kException {
		Integer attached=hierarchicalMenus.get(itemKey(menu,item));if(attached!=null)return attached;
		int p=rt.allocate(4,2);
		try{rt.toolbox("GetItemCmd",menu,item,p);if(memory.readWord(p)!=0x1b)return 0;rt.toolbox("GetItemMark",menu,item,p);return rt.toolbox("GetMenuHandle",(short)memory.readWord(p));}
		finally{memory.free(p);}
	}
	private int countCommand(int menu,int command,Set<Integer> seen) throws M68kException {
		if(menu==0||!seen.add(menu))return 0;int n=0,out=rt.allocate(4,4);
		try{int items=(short)rt.toolbox("CountMenuItems",menu);
			for(int i=1;i<=items;i++){memory.writeLong(out,0);carbon("GetMenuItemCommandID",menu,i,out);if(memory.readLong(out)==command)n++;n+=countCommand(submenu(menu,i),command,seen);}}
		finally{memory.free(out);}
		return n;
	}
	private Property commandProperty(int menu,int command,int creator,int tag) throws M68kException {int[] m=commandItem(menu,command);return m==null?null:itemProperties.get(propertyName(m[0],m[1],creator,tag));}
	private int getProperty(Property p,int size,int actual,int buffer) throws M68kCpuException {
		if(p==null)return PROPERTY_NOT_FOUND_ERR;
		if(actual!=0)memory.writeLong(actual,p.data.length);if(buffer!=0&&size>0)memory.load(buffer,p.data,0,Math.min(size,p.data.length));return NO_ERR;
	}
	private void setMenuDefinition(int menu,int upp) throws M68kException {memory.writeLong(memory.readLong(menu)+6,definitionHandle(upp));rt.toolbox("CalcMenuSize",menu);} // menuProc

	private void defineMenus(PpcLibraries l){
		l.register(null,"GetMenuAttributes",(r,c)->{Integer v=menuAttributes.get(arg(0));if(arg(1)!=0)memory.writeLong(arg(1),v==null?0:v);ret(NO_ERR);});
		l.register(null,"ChangeMenuAttributes",(r,c)->{Integer v=menuAttributes.get(arg(0));menuAttributes.put(arg(0),((v==null?0:v)|arg(1))&~arg(2));ret(NO_ERR);}); // replaces PpcCarbon's stub
		l.register(null,"GetMenuExcludesMarkColumn",(r,c)->{Integer v=menuAttributes.get(arg(0));ret(v!=null&&(v&MENU_ATTR_EXCLUDES_MARK)!=0?1:0);});
		l.register(null,"SetMenuExcludesMarkColumn",(r,c)->{Integer v=menuAttributes.get(arg(0));int a=v==null?0:v;menuAttributes.put(arg(0),(arg(1)&0xff)!=0?a|MENU_ATTR_EXCLUDES_MARK:a&~MENU_ATTR_EXCLUDES_MARK);ret(NO_ERR);});
		l.register(null,"SetMenuFont",(r,c)->{menuFonts.put(arg(0),(arg(1)&0xffff)<<16|(arg(2)&0xffff));ret(NO_ERR);}); // (menu, fontID, size): replaces PpcCarbon's stub; menus still draw in the system font
		l.register(null,"GetMenuFont",(r,c)->{Integer v=menuFonts.get(arg(0));int f=v==null?0:v;if(arg(1)!=0)memory.writeWord(arg(1),f>>>16);if(arg(2)!=0)memory.writeWord(arg(2),f);ret(NO_ERR);});
		l.register(null,"SetMenuWidth",(r,c)->memory.writeWord(memory.readLong(arg(0))+2,arg(1)));
		l.register(null,"SetMenuHeight",(r,c)->memory.writeWord(memory.readLong(arg(0))+4,arg(1)));
		l.register(null,"GetMenuItemAttributes",(r,c)->{int[] a=args(3);int menu=a[0],item=a[1]&0xffff; // what was set, plus what the item shows (disabled, a separator)
			Integer v=itemAttributes.get(itemKey(menu,item));int attr=v==null?0:v&~(ITEM_ATTR_DISABLED|ITEM_ATTR_SEPARATOR);
			if((carbon("IsMenuItemEnabled",menu,item)&0xff)==0)attr|=ITEM_ATTR_DISABLED;if(isSeparator(menu,item))attr|=ITEM_ATTR_SEPARATOR;
			if(a[2]!=0)memory.writeLong(a[2],attr);ret(NO_ERR);});
		l.register(null,"ChangeMenuItemAttributes",(r,c)->{int[] a=args(4);int menu=a[0],item=a[1]&0xffff,set=a[2],clear=a[3];
			if(((set|clear)&ITEM_ATTR_DISABLED)!=0)rt.toolbox((set&ITEM_ATTR_DISABLED)!=0?"DisableItem":"EnableItem",menu,item);
			if((set&ITEM_ATTR_SEPARATOR)!=0){int dash=pascal("-");rt.toolbox("SetMenuItemText",menu,item,dash);memory.free(dash);rt.toolbox("DisableItem",menu,item);}
			long key=itemKey(menu,item);Integer v=itemAttributes.get(key);itemAttributes.put(key,((v==null?0:v)|set)&~clear);ret(NO_ERR);});
		// menu item properties
		l.register(null,"SetMenuItemProperty",(r,c)->{int[] a=args(6);if(a[2]==0){ret(PROPERTY_INVALID_ERR);return;} // (menu, item, creator, tag, size, data)
			String k=propertyName(a[0],a[1],a[2],a[3]);Property old=itemProperties.get(k);Property p=new Property(a[5]==0?new byte[a[4]]:memory.readBytes(a[5],a[4]));if(old!=null)p.attributes=old.attributes;itemProperties.put(k,p);ret(NO_ERR);});
		l.register(null,"GetMenuItemProperty",(r,c)->{int[] a=args(7);ret(getProperty(itemProperties.get(propertyName(a[0],a[1],a[2],a[3])),a[4],a[5],a[6]));}); // (menu, item, creator, tag, bufferSize, *actualSize, buffer)
		l.register(null,"GetMenuItemPropertySize",(r,c)->{int[] a=args(5);ret(getProperty(itemProperties.get(propertyName(a[0],a[1],a[2],a[3])),0,a[4],0));});
		l.register(null,"RemoveMenuItemProperty",(r,c)->{int[] a=args(4);ret(itemProperties.remove(propertyName(a[0],a[1],a[2],a[3]))==null?PROPERTY_NOT_FOUND_ERR:NO_ERR);});
		l.register(null,"GetMenuItemPropertyAttributes",(r,c)->{int[] a=args(5);Property p=itemProperties.get(propertyName(a[0],a[1],a[2],a[3]));if(p==null){ret(PROPERTY_NOT_FOUND_ERR);return;}if(a[4]!=0)memory.writeLong(a[4],p.attributes);ret(NO_ERR);});
		l.register(null,"ChangeMenuItemPropertyAttributes",(r,c)->{int[] a=args(6);Property p=itemProperties.get(propertyName(a[0],a[1],a[2],a[3]));if(p==null){ret(PROPERTY_NOT_FOUND_ERR);return;}p.attributes=(p.attributes|a[4])&~a[5];ret(NO_ERR);});
		// the same, for the item carrying a command ID
		l.register(null,"SetMenuCommandProperty",(r,c)->{int[] a=args(6);int[] m=commandItem(a[0],a[1]);if(m==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}if(a[2]==0){ret(PROPERTY_INVALID_ERR);return;}
			itemProperties.put(propertyName(m[0],m[1],a[2],a[3]),new Property(a[5]==0?new byte[a[4]]:memory.readBytes(a[5],a[4])));ret(NO_ERR);});
		l.register(null,"GetMenuCommandProperty",(r,c)->{int[] a=args(7);if(commandItem(a[0],a[1])==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}ret(getProperty(commandProperty(a[0],a[1],a[2],a[3]),a[4],a[5],a[6]));});
		l.register(null,"GetMenuCommandPropertySize",(r,c)->{int[] a=args(5);if(commandItem(a[0],a[1])==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}ret(getProperty(commandProperty(a[0],a[1],a[2],a[3]),0,a[4],0));});
		l.register(null,"RemoveMenuCommandProperty",(r,c)->{int[] a=args(4);int[] m=commandItem(a[0],a[1]);if(m==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}ret(itemProperties.remove(propertyName(m[0],m[1],a[2],a[3]))==null?PROPERTY_NOT_FOUND_ERR:NO_ERR);});
		l.register(null,"CountMenuItemsWithCommandID",(r,c)->{int[] a=args(2);int menu=a[0],command=a[1]; // (menu or NIL = the menu bar, commandID), submenus included
			commandItem(menu,command); // PpcCarbon reads the menus' 'xmnu' command IDs at the first lookup
			Set<Integer> seen=new HashSet<Integer>();int n=0;if(menu!=0)n=countCommand(menu,command,seen);else for(int m:menuBarMenus())n+=countCommand(m,command,seen);ret(n);});
		l.register(null,"IsMenuCommandEnabled",(r,c)->{int[] a=args(2);int[] m=commandItem(a[0],a[1]);ret(m!=null&&(carbon("IsMenuItemEnabled",m[0],m[1])&0xff)!=0?1:0);});
		l.register(null,"SetMenuCommandMark",(r,c)->{int[] a=args(3);int[] m=commandItem(a[0],a[1]);if(m==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;} // (menu, command, UniChar mark)
			int mark=a[2]&0xffff;rt.toolbox("SetItemMark",m[0],m[1],mark==0x2713?0x12:mark==0x25c6?0x13:mark&0xff);ret(NO_ERR);}); // check mark and diamond glyphs of the system font
		l.register(null,"GetMenuCommandMark",(r,c)->{int[] a=args(3);int[] m=commandItem(a[0],a[1]);if(m==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}
			int p=rt.allocate(2,2);rt.toolbox("GetItemMark",m[0],m[1],p);int mark=memory.readWord(p)&0xff;memory.free(p);if(a[2]!=0)memory.writeWord(a[2],mark);ret(NO_ERR);});
		l.register(null,"GetMenuTrackingData",(r,c)->ret(MENU_NOT_FOUND_ERR)); // only while a menu is open, which never is to a program here
		l.register(null,"GetMenuType",(r,c)->{if(arg(1)!=0)memory.writeWord(arg(1),0);ret(NO_ERR);}); // kThemeMenuTypePullDown
		l.register(null,"MacEnableMenuItem",(r,c)->{int[] a=args(2);rt.toolbox("EnableItem",a[0],a[1]&0xffff);});
		l.register(null,"SetMenuItemIndent",(r,c)->{itemIndents.put(itemKey(arg(0),arg(1)&0xffff),arg(2));ret(NO_ERR);});
		l.register(null,"GetMenuItemIndent",(r,c)->{Integer v=itemIndents.get(itemKey(arg(0),arg(1)&0xffff));if(arg(2)!=0)memory.writeLong(arg(2),v==null?0:v);ret(NO_ERR);});
		// retain counts (CarbonLib 1.1): the last ReleaseMenu disposes of the menu
		l.register(null,"GetMenuRetainCount",(r,c)->{Integer v=menuRetainCounts.get(arg(0));ret(v==null?1:v);});
		l.register(null,"RetainMenu",(r,c)->{Integer v=menuRetainCounts.get(arg(0));menuRetainCounts.put(arg(0),(v==null?1:v)+1);ret(NO_ERR);});
		l.register(null,"ReleaseMenu",(r,c)->{int menu=arg(0);Integer v=menuRetainCounts.get(menu);int n=(v==null?1:v)-1;
			if(n>0)menuRetainCounts.put(menu,n);else{menuRetainCounts.remove(menu);rt.toolbox("DisposeMenu",menu);}ret(NO_ERR);});
		l.register(null,"DuplicateMenu",(r,c)->{int[] a=args(2);int n=rt.toolbox("GetHandleSize",a[0]);int h=rt.toolbox("NewHandle",n);
			if(h!=0)memory.copy(memory.readLong(a[0]),memory.readLong(h),n);if(a[1]!=0)memory.writeLong(a[1],h);ret(h==0?MEM_FULL_ERR:NO_ERR);});
		// the menu's contents are rebuilt from its record whenever it changes: nothing is ever out of date
		l.register(null,"InvalidateMenuSize",(r,c)->{rt.toolbox("CalcMenuSize",arg(0));ret(NO_ERR);});
		l.register(null,"IsMenuSizeInvalid",(r,c)->ret(0));
		l.register(null,"InvalidateMenuEnabling",(r,c)->ret(NO_ERR));
		l.register(null,"IsMenuBarInvalid",(r,c)->ret(0));
		l.register(null,"InvalidateMenuItems",(r,c)->ret(NO_ERR));
		l.register(null,"UpdateInvalidMenuItems",(r,c)->ret(NO_ERR));
		l.register(null,"IsMenuItemInvalid",(r,c)->ret(0));
		// definitions given as UPPs (MenuDefSpec {defType, defProc})
		l.register(null,"GetMenuDefinition",(r,c)->{int[] a=args(2);if(a[1]==0){ret(PARAM_ERR);return;}
			int h=memory.readLong(memory.readLong(a[0])+6);memory.writeLong(a[1],0);memory.writeLong(a[1]+4,h==0?0:memory.readLong(h));ret(NO_ERR);}); // kMenuDefProcPtr: the UPP (or the MDEF's code)
		l.register(null,"SetMenuDefinition",(r,c)->{int[] a=args(2);if(a[1]==0||memory.readLong(a[1])!=0){ret(PARAM_ERR);return;}setMenuDefinition(a[0],memory.readLong(a[1]+4));ret(NO_ERR);});
		l.register(null,"CreateCustomMenu",(r,c)->{int[] a=args(4); // (MenuDefSpec*, menuID, attributes, *outMenu)
			if(a[0]==0||memory.readLong(a[0])!=0){ret(PARAM_ERR);return;}
			int title=rt.allocate(2,2);int menu=rt.toolbox("NewMenu",(short)a[1],title);memory.free(title);
			if(menu!=0){setMenuDefinition(menu,memory.readLong(a[0]+4));if(a[2]!=0)menuAttributes.put(menu,a[2]);}
			if(a[3]!=0)memory.writeLong(a[3],menu);ret(menu==0?MEM_FULL_ERR:NO_ERR);});
		l.register(null,"RegisterMenuDefinition",(r,c)->{int[] a=args(2);int id=(short)a[0],spec=a[1]; // (resID, MenuDefSpec*): menus whose menuProc names that MDEF ID
			if(spec!=0&&memory.readLong(spec)!=0){ret(PARAM_ERR);return;}
			rt.toolbox.menus.registerDefinition(id,spec==0?0:definitionHandle(memory.readLong(spec+4)));ret(NO_ERR);});
		// hierarchical menus (CarbonLib 1.1): the classic encoding - the item's key $1B, its mark the submenu's ID - which the
		// Menu Manager shows; a submenu whose ID does not fit the mark byte is only remembered
		l.register(null,"SetMenuItemHierarchicalMenu",(r,c)->{int[] a=args(3);int menu=a[0],item=a[1]&0xffff,sub=a[2];
			if(sub==0){hierarchicalMenus.remove(itemKey(menu,item));rt.toolbox("SetItemCmd",menu,item,0);rt.toolbox("SetItemMark",menu,item,0);ret(NO_ERR);return;}
			hierarchicalMenus.put(itemKey(menu,item),sub);int id=(short)memory.readWord(memory.readLong(sub));rt.toolbox("InsertMenu",sub,-1);
			if(id>0&&id<256){rt.toolbox("SetItemCmd",menu,item,0x1b);rt.toolbox("SetItemMark",menu,item,id);}ret(NO_ERR);});
		l.register(null,"GetMenuItemHierarchicalMenu",(r,c)->{int[] a=args(3);int sub=submenu(a[0],a[1]&0xffff);if(a[2]!=0)memory.writeLong(a[2],sub);ret(NO_ERR);});
		// the standard font menu: the installed fonts' names, families by name
		l.register(null,"CreateStandardFontMenu",(r,c)->{int[] a=args(5); // (menu, afterItem, firstHierMenuID, options, *outHierMenuCount): no style submenus
			rt.toolbox("InsertResMenu",a[0],0x464f4e54,a[1]&0xffff);if(a[4]!=0)memory.writeLong(a[4],0);ret(NO_ERR);}); // 'FONT'
		l.register(null,"UpdateStandardFontMenu",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(NO_ERR);});
		l.register(null,"GetFontFamilyFromMenuSelection",(r,c)->{int[] a=args(4); // (menu, item, *family, *style)
			int text=rt.allocate(256,2),num=rt.allocate(2,2);
			try{rt.toolbox("GetMenuItemText",a[0],a[1]&0xffff,text);if(memory.readByte(text)==0){ret(PARAM_ERR);return;}rt.toolbox("GetFNum",text,num);
				if(a[2]!=0)memory.writeWord(a[2],memory.readWord(num));if(a[3]!=0)memory.writeWord(a[3],0);ret(NO_ERR);}
			finally{memory.free(text);memory.free(num);}});
		l.register(null,"CreateStandardWindowMenu",(r,c)->{int out=arg(1); // (options, *outMenu): a static Window menu with the standard commands
			int id=32000;while(id>256&&rt.toolbox("GetMenuHandle",id)!=0)id--;
			int title=pascal("Window");int menu=rt.toolbox("NewMenu",id,title);memory.free(title);
			if(menu!=0){String[] items={"Minimize Window/M","Zoom Window","(-","Bring All to Front"};int[] commands={ostype("mini"),ostype("zoom"),0,ostype("bfrt")};
				for(int i=0;i<items.length;i++){int p=pascal(items[i]);rt.toolbox("AppendMenu",menu,p);memory.free(p);if(commands[i]!=0)carbon("SetMenuItemCommandID",menu,i+1,commands[i]);}}
			if(out!=0)memory.writeLong(out,menu);ret(menu==0?MEM_FULL_ERR:NO_ERR);});
	}

	// ------------------------------------------------------------------ Dialog Manager

	private final Map<Integer,int[]> dialogTimeouts=new HashMap<Integer,int[]>();
	private final Map<Integer,Integer> modalEventMasks=new HashMap<Integer,Integer>();
	/** DialogRecord.items: the DITL Handle. */
	private static final int DIALOG_ITEMS=156;
	/** An item of a dialog as it stands (DITL bytes with its handle cleared, plus its current text, control state or procedure). */
	private static final class ItemState { byte[] ditl;int type,handle,value,min,max,hilite;byte[] text; }

	/** The items after number {@code keep} as they stand now. */
	private List<ItemState> tail(int dialog,int keep) throws M68kException {
		List<ItemState> items=new ArrayList<ItemState>();
		int data=memory.readLong(memory.readLong(dialog+DIALOG_ITEMS));int count=(short)memory.readWord(data)+1;int at=data+2;
		int type=rt.allocate(2,2),handle=rt.allocate(4,4),box=rt.allocate(8,2),text=rt.allocate(256,2);
		try{
			for(int i=1;i<=count;i++){int n=memory.readByte(at+13);int size=14+n+(n&1);
				if(i>keep){ItemState s=new ItemState();s.ditl=memory.readBytes(at,size);java.util.Arrays.fill(s.ditl,0,4,(byte)0);
					rt.toolbox("GetDialogItem",dialog,i,type,handle,box);s.type=memory.readWord(type)&0x7f;s.handle=memory.readLong(handle);
					if(s.type==8||s.type==16){rt.toolbox("GetDialogItemText",s.handle,text);s.text=rt.toolbox.readPascalBytes(text);}
					else if(s.type>=4&&s.type<=7&&s.handle!=0){int p=memory.readLong(s.handle);s.value=(short)memory.readWord(p+18);s.min=(short)memory.readWord(p+20);s.max=(short)memory.readWord(p+22);s.hilite=memory.readByte(p+17);}
					items.add(s);}
				at+=size;}
		}finally{memory.free(type);memory.free(handle);memory.free(box);memory.free(text);}
		return items;
	}
	/** Appends items (AppendDITL, placed as they are) and gives them back their text, control state and procedures. */
	private void appendItems(int dialog,List<ItemState> items) throws M68kException {
		if(items.isEmpty())return;
		java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();b.write((items.size()-1)>>8);b.write(items.size()-1);for(ItemState s:items)b.write(s.ditl,0,s.ditl.length);
		byte[] ditl=b.toByteArray();int h=rt.toolbox.api.createHandle(ditl);int first=(short)rt.toolbox("CountDITL",dialog)+1;
		try{rt.toolbox("AppendDITL",dialog,h,0);}finally{rt.toolbox.api.disposeHandleAndFree(h);}
		int type=rt.allocate(2,2),handle=rt.allocate(4,4),box=rt.allocate(8,2);
		try{
			for(int i=0;i<items.size();i++){ItemState s=items.get(i);int number=first+i;
				rt.toolbox("GetDialogItem",dialog,number,type,handle,box);int now=memory.readLong(handle);
				if(s.text!=null&&now!=0){int p=pascalBytes(java.util.Arrays.copyOfRange(s.text,1,s.text.length));rt.toolbox("SetDialogItemText",now,p);memory.free(p);}
				else if(s.type>=4&&s.type<=7&&now!=0){rt.toolbox("SetControlMinimum",now,s.min);rt.toolbox("SetControlMaximum",now,s.max);rt.toolbox("SetControlValue",now,s.value);if(s.hilite!=0)rt.toolbox("HiliteControl",now,s.hilite);}
				else if((s.type==0||s.type==32||s.type==64)&&s.handle!=0&&s.handle!=now)rt.toolbox("SetDialogItem",dialog,number,memory.readWord(type),s.handle,box); // user item procedures, icons, pictures
			}
		}finally{memory.free(type);memory.free(handle);memory.free(box);}
	}

	private void defineDialogs(PpcLibraries l){
		l.register(null,"GetParamText",(r,c)->{int[] a=args(4); // the ParamText strings: DAStrings ($AA0) holds them as StringHandles
			for(int i=0;i<4;i++){if(a[i]==0)continue;int h=memory.readLong(DialogManager68k.DA_STRINGS+4*i);int p=h==0?0:memory.readLong(h);
				if(p==0)memory.writeByte(a[i],0);else memory.copy(p,a[i],Math.min(256,memory.readByte(p)+1));}});
		l.register(null,"InsertDialogItem",(r,c)->{ // (dialog, afterItem, itemType, itemHandle, box): the items after it leave and come back behind the new one
			int[] a=args(5);int dialog=a[0],type=a[2]&0xff,itemHandle=a[3],box=a[4];
			if(!rt.toolbox.dialogs.dialogs.containsKey(dialog)||box==0){ret(PARAM_ERR);return;}
			int count=(short)rt.toolbox("CountDITL",dialog);int after=Math.max(0,Math.min(count,(short)a[1]));
			List<ItemState> tail=tail(dialog,after);if(!tail.isEmpty())rt.toolbox("ShortenDITL",dialog,tail.size());
			int kind=type&0x7f;byte[] text=(kind==8||kind==16||kind<=6&&kind>=4)&&itemHandle!=0&&rt.toolbox.api.isHandle(itemHandle)?rt.toolbox.api.handleContents(itemHandle):null;
			boolean controlHandle=kind>=4&&kind<=7&&itemHandle!=0&&rt.toolbox.controls.data(itemHandle)!=0;
			if(controlHandle)rt.toolbox.controls.moveToWindow(itemHandle,dialog); // a control of another window (a nib's) comes into the dialog
			if(controlHandle)text=null; // an existing control takes the item's place below
			int n=text==null?(kind==7||kind==32||kind==64?2:0):Math.min(255,text.length);byte[] item=new byte[14+n+(n&1)];
			for(int i=0;i<8;i++)item[4+i]=(byte)memory.readByte(box+i);item[12]=(byte)type;item[13]=(byte)n;if(text!=null)System.arraycopy(text,0,item,14,n);
			ItemState s=new ItemState();s.ditl=item;s.type=kind;List<ItemState> added=new ArrayList<ItemState>();added.add(s);appendItems(dialog,added);
			int number=after+1;
			if(controlHandle||kind==0||kind==32||kind==64||kind==7){ // the program's own Handle: a control, a user item procedure, an icon or picture
				int handle=rt.allocate(4,4),t=rt.allocate(2,2),b=rt.allocate(8,2);
				try{rt.toolbox("GetDialogItem",dialog,number,t,handle,b);int made=memory.readLong(handle);
					if(itemHandle!=0&&made!=itemHandle){if(controlHandle&&made!=0&&rt.toolbox.controls.data(made)!=0)rt.toolbox("DisposeControl",made);rt.toolbox("SetDialogItem",dialog,number,type,itemHandle,box);}}
				finally{memory.free(handle);memory.free(t);memory.free(b);}}
			appendItems(dialog,tail);ret(NO_ERR);});
		l.register(null,"RemoveDialogItems",(r,c)->{ // (dialog, itemNo, amountToRemove, disposeItemData)
			int[] a=args(4);int dialog=a[0],first=(short)a[1],amount=(short)a[2];
			if(!rt.toolbox.dialogs.dialogs.containsKey(dialog)){ret(PARAM_ERR);return;}
			int count=(short)rt.toolbox("CountDITL",dialog);if(first<1||first>count||amount<=0){ret(PARAM_ERR);return;}
			int last=Math.min(count,first+amount-1);List<ItemState> tail=tail(dialog,last);
			rt.toolbox("ShortenDITL",dialog,count-first+1);appendItems(dialog,tail);ret(NO_ERR);});
		l.register(null,"SetDialogTimeout",(r,c)->{if((arg(2))==0)dialogTimeouts.remove(arg(0));else dialogTimeouts.put(arg(0),new int[]{(short)arg(1),arg(2),(int)(System.currentTimeMillis()/1000)});ret(NO_ERR);}); // kept; ModalDialog does not press the button
		l.register(null,"GetDialogTimeout",(r,c)->{int[] a=args(4);int[] t=dialogTimeouts.get(a[0]);if(t==null){ret(DIALOG_NO_TIMEOUT_ERR);return;}
			if(a[1]!=0)memory.writeWord(a[1],t[0]);if(a[2]!=0)memory.writeLong(a[2],t[1]);
			if(a[3]!=0)memory.writeLong(a[3],Math.max(0,t[1]-((int)(System.currentTimeMillis()/1000)-t[2])));ret(NO_ERR);});
		l.register(null,"SetModalDialogEventMask",(r,c)->{modalEventMasks.put(arg(0),arg(1)&0xffff);ret(NO_ERR);});
		l.register(null,"GetModalDialogEventMask",(r,c)->{Integer v=modalEventMasks.get(arg(0));if(arg(1)!=0)memory.writeWord(arg(1),v==null?0xffff:v);ret(NO_ERR);}); // everyEvent
	}

	// ------------------------------------------------------------------ TextEdit

	/** Low-memory TextEdit hooks: TEDoText ($A70), TERecal ($A74); the find-word hook has no low-memory home. */
	private static final int TE_DO_TEXT=0xa70,TE_RECAL=0xa74,TE_SCRAP_HANDLE=0xab4,TE_CLICK_LOOP=42;
	private int findWordHook;

	private void defineTextEdit(PpcLibraries l){
		l.register(null,"TEGetDoTextHook",(r,c)->ret(memory.readLong(TE_DO_TEXT)));
		l.register(null,"TESetDoTextHook",(r,c)->memory.writeLong(TE_DO_TEXT,arg(0)));
		l.register(null,"TEGetRecalcHook",(r,c)->ret(memory.readLong(TE_RECAL)));
		l.register(null,"TESetRecalcHook",(r,c)->memory.writeLong(TE_RECAL,arg(0)));
		l.register(null,"TEGetFindWordHook",(r,c)->ret(findWordHook));
		l.register(null,"TESetFindWordHook",(r,c)->findWordHook=arg(0));
		l.register(null,"TEGetScrapHandle",(r,c)->ret(memory.readLong(TE_SCRAP_HANDLE)));
		l.register(null,"TESetScrapHandle",(r,c)->memory.writeLong(TE_SCRAP_HANDLE,arg(0)));
		l.register(null,"TESetClickLoop",(r,c)->{int[] a=args(2);if(a[1]!=0)memory.writeLong(memory.readLong(a[1])+TE_CLICK_LOOP,a[0]);}); // (clikProc, hTE): TERec.clickLoop, which TEClick calls
	}

	// ------------------------------------------------------------------ Scrap Manager

	private int promiseKeeper,promiseKeeperData;
	/** The flavors on the desk scrap: {type, size} in scrap order (the scrap Handle holds type, length, data padded to even). */
	private List<int[]> scrapFlavors() throws M68kException {
		List<int[]> flavors=new ArrayList<int[]>();
		rt.toolbox("LoadScrap");int info=rt.toolbox("InfoScrap");if(info==0)return flavors;
		int size=memory.readLong(info),h=memory.readLong(info+4);int p=h==0?0:memory.readLong(h);if(p==0)return flavors;
		for(int at=0;at+8<=size;){int type=memory.readLong(p+at),n=memory.readLong(p+at+4);if(n<0)break;flavors.add(new int[]{type,n});at+=8+n+(n&1);}
		return flavors;
	}

	private void defineScrap(PpcLibraries l){
		l.register(null,"GetScrapFlavorCount",(r,c)->{int out=arg(1);int n=scrapFlavors().size();if(out!=0)memory.writeLong(out,n);ret(NO_ERR);}); // replaces PpcCarbon's 0
		l.register(null,"GetScrapFlavorInfoList",(r,c)->{int[] a=args(3); // (scrap, *infoCount in/out, ScrapFlavorInfo[] {type, flags})
			List<int[]> flavors=scrapFlavors();int max=a[1]==0?0:memory.readLong(a[1]);int n=Math.min(max,flavors.size());
			for(int i=0;i<n&&a[2]!=0;i++){memory.writeLong(a[2]+8*i,flavors.get(i)[0]);memory.writeLong(a[2]+8*i+4,0);}
			if(a[1]!=0)memory.writeLong(a[1],n);ret(NO_ERR);});
		l.register(null,"SetScrapPromiseKeeper",(r,c)->{promiseKeeper=arg(1);promiseKeeperData=arg(2);ret(NO_ERR);}); // kept; flavors are put with their data here, so nothing is promised
		l.register(null,"CallInScrapPromises",(r,c)->ret(NO_ERR));
		l.register(null,"ClearScrap",(r,c)->{int ref=arg(0);int err=rt.toolbox("ZeroScrap");if(ref!=0)memory.writeLong(ref,0x53435250);ret(err);}); // (*inOutScrap): the scrap is PpcCarbon's one scrap ref
	}

	// ------------------------------------------------------------------ Help tags (MacHelp.h)

	/** Help content records by object ("c" control, "w" window, "m" menu item), and the content callbacks. */
	private final Map<String,byte[]> helpContent=new HashMap<String,byte[]>();
	private final Map<String,Integer> helpCallbacks=new HashMap<String,Integer>();
	private final Map<Integer,int[]> dialogBalloons=new HashMap<Integer,int[]>();
	private int tagDelay=500;
	private int setHelp(String key,int record) throws M68kCpuException {if(record==0)helpContent.remove(key);else helpContent.put(key,memory.readBytes(record,HELP_CONTENT_SIZE));return NO_ERR;}
	/** A stored content record, or one with no content (kMacHelpVersion, kHMNoContent twice). */
	private int getHelp(String key,int out) throws M68kCpuException {
		if(out==0)return PARAM_ERR;byte[] v=helpContent.get(key);
		if(v!=null)memory.load(out,v);else{memory.fill(out,HELP_CONTENT_SIZE,0);memory.writeLong(out,3);memory.writeLong(out+14,ostype("none"));memory.writeLong(out+14+260,ostype("none"));}
		return NO_ERR;
	}
	private int getCallback(String key,int out) throws M68kCpuException {Integer v=helpCallbacks.get(key);if(out!=0)memory.writeLong(out,v==null?0:v);return NO_ERR;}
	private int setCallback(String key,int upp){if(upp==0)helpCallbacks.remove(key);else helpCallbacks.put(key,upp);return NO_ERR;}

	private void defineHelp(PpcLibraries l){
		// help tags on Mac OS 9 are Balloon Help: shown when balloons are on
		l.register(null,"HMAreHelpTagsDisplayed",(r,c)->ret((rt.toolbox("HMGetBalloons")&0xff)!=0?1:0));
		l.register(null,"HMSetHelpTagsDisplayed",(r,c)->{int on=(arg(0)&0xff)!=0?1:0;rt.toolbox("HMSetBalloons",on);ret(NO_ERR);});
		l.register(null,"HMSetTagDelay",(r,c)->{tagDelay=arg(0);ret(NO_ERR);});
		l.register(null,"HMGetTagDelay",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),tagDelay);ret(NO_ERR);});
		l.register(null,"HMSetControlHelpContent",(r,c)->ret(setHelp("c"+arg(0),arg(1))));
		l.register(null,"HMGetControlHelpContent",(r,c)->ret(getHelp("c"+arg(0),arg(1))));
		l.register(null,"HMSetWindowHelpContent",(r,c)->ret(setHelp("w"+arg(0),arg(1))));
		l.register(null,"HMGetWindowHelpContent",(r,c)->ret(getHelp("w"+arg(0),arg(1))));
		l.register(null,"HMSetMenuItemHelpContent",(r,c)->ret(setHelp("m"+arg(0)+"/"+(arg(1)&0xffff),arg(2))));
		l.register(null,"HMGetMenuItemHelpContent",(r,c)->ret(getHelp("m"+arg(0)+"/"+(arg(1)&0xffff),arg(2))));
		l.register(null,"HMInstallControlContentCallback",(r,c)->ret(setCallback("c"+arg(0),arg(1))));
		l.register(null,"HMInstallWindowContentCallback",(r,c)->ret(setCallback("w"+arg(0),arg(1))));
		l.register(null,"HMInstallMenuTitleContentCallback",(r,c)->ret(setCallback("t"+arg(0),arg(1))));
		l.register(null,"HMInstallMenuItemContentCallback",(r,c)->ret(setCallback("m"+arg(0),arg(1))));
		l.register(null,"HMGetControlContentCallback",(r,c)->ret(getCallback("c"+arg(0),arg(1))));
		l.register(null,"HMGetWindowContentCallback",(r,c)->ret(getCallback("w"+arg(0),arg(1))));
		l.register(null,"HMGetMenuTitleContentCallback",(r,c)->ret(getCallback("t"+arg(0),arg(1))));
		l.register(null,"HMGetMenuItemContentCallback",(r,c)->ret(getCallback("m"+arg(0),arg(1))));
		l.register(null,"HMSetMenuHelpFromBalloonRsrc",(r,c)->{int[] a=args(2);ret((short)rt.toolbox("HMSetMenuResID",(short)memory.readWord(memory.readLong(a[0])),(short)a[1]));}); // (menu, 'hmnu' ID)
		l.register(null,"HMSetDialogHelpFromBalloonRsrc",(r,c)->{dialogBalloons.put(arg(0),new int[]{(short)arg(1),(short)arg(2)});ret(NO_ERR);}); // kept: the Help Manager here takes 'hdlg' help for the next dialog only
		l.register(null,"HMDisplayTag",(r,c)->ret(NO_ERR));
		l.register(null,"HMHideTag",(r,c)->ret(NO_ERR));
	}

	// ------------------------------------------------------------------ Process Manager, Apple Help, Drag Manager, others (CarbonLib 1.1+)

	private void defineMisc(PpcLibraries l){
		l.register(null,"CopyProcessName",(r,c)->{int[] a=args(2); // (psn, *CFStringRef): the name GetProcessInformation gives
			int info=rt.allocate(64,4),name=rt.allocate(64,2);
			try{memory.writeLong(info,60);memory.writeLong(info+4,name);int err=(short)rt.toolbox("GetProcessInformation",a[0],info);
				if(err!=0){ret(err);return;}if(a[1]!=0)memory.writeLong(a[1],rt.libraries.textServices.newCFString(rt.toolbox.readPascal(name)));ret(NO_ERR);}
			finally{memory.free(info);memory.free(name);}});
		l.register(null,"GetProcessBundleLocation",(r,c)->ret(-43)); // fnfErr: a CFM application is no bundle
		l.register(null,"CreateTypeStringWithOSType",(r,c)->{int t=arg(0);ret(rt.libraries.textServices.newCFString(new String(longs(t),java.nio.charset.Charset.forName("MacRoman"))));});
		l.register(null,"CSCopyUserName",(r,c)->{int h=rt.toolbox("GetString",-16096);int p=h==0?0:memory.readLong(h); // (useShortName): the owner name ('STR ' -16096), as CFGetUserName - never the host account's name
			ret(rt.libraries.textServices.newCFString(p==0?"":rt.pString(p)));});
		l.register(null,"SetDragImageWithAlpha",(r,c)->ret(NO_ERR));
		l.register(null,"AHRegisterHelpBook",(r,c)->ret(NO_ERR));
	}

	// ------------------------------------------------------------------ CarbonLib 1.6: menu item data (Copy/SetMenuItemData)

	/** MenuItemDataFlags (Menus.h), the low word of the UInt64 whichData. */
	static final int MID_TEXT=1,MID_MARK=1<<1,MID_CMD_KEY=1<<2,MID_CMD_KEY_GLYPH=1<<3,MID_CMD_KEY_MODIFIERS=1<<4,MID_STYLE=1<<5,MID_ENABLED=1<<6,MID_ICON_ENABLED=1<<7,
		MID_ICON_ID=1<<8,MID_ICON_HANDLE=1<<9,MID_COMMAND_ID=1<<10,MID_TEXT_ENCODING=1<<11,MID_SUBMENU_ID=1<<12,MID_SUBMENU_HANDLE=1<<13,MID_FONT_ID=1<<14,MID_REFCON=1<<15,
		MID_ATTRIBUTES=1<<16,MID_CFSTRING=1<<17,MID_PROPERTIES=1<<18,MID_INDENT=1<<19,MID_CMD_VIRTUAL_KEY=1<<20,MID_ALL=0x1fffff;
	/** The flags that also apply to the menu itself (item 0): its title, enabling, attributes and properties. */
	private static final int MID_MENU=MID_TEXT|MID_ENABLED|MID_ATTRIBUTES|MID_CFSTRING|MID_PROPERTIES;
	/** MenuItemDataRec offsets (mac68k alignment, 80 bytes). */
	static final int MD_FLAGS=4,MD_TEXT=8,MD_MARK=12,MD_CMD_KEY=14,MD_CMD_KEY_GLYPH=16,MD_CMD_KEY_MODIFIERS=20,MD_STYLE=24,MD_ENABLED=25,MD_ICON_ENABLED=26,MD_ICON_ID=28,
		MD_ICON_TYPE=32,MD_ICON_HANDLE=36,MD_COMMAND_ID=40,MD_ENCODING=44,MD_SUBMENU_ID=48,MD_SUBMENU_HANDLE=50,MD_FONT_ID=54,MD_REFCON=58,MD_ATTRIBUTES=62,MD_CFTEXT=66,
		MD_PROPERTIES=70,MD_INDENT=74,MD_CMD_VIRTUAL_KEY=78,MD_SIZE=80;
	/** Items whose text was last set as a CFString (the format CopyMenuItemData returns when both are asked for). */
	private final Set<Long> cfTextItems=new HashSet<Long>();
	/** Items whose icon is drawn disabled (DisableMenuItemIcon); the Menu Manager here keeps no icon state of its own. */
	private final Set<Long> iconsDisabled=new HashSet<Long>();
	/** Icon Handles of items ({type, handle}: kMenuIconType ... kMenuIconRefType) and of menu titles (SetMenuTitleIcon). */
	private final Map<Long,int[]> itemIcons=new HashMap<Long,int[]>();
	private final Map<Integer,int[]> titleIcons=new HashMap<Integer,int[]>();
	/** Virtual key codes given as command keys (SetMenuItemCommandKey with isVirtualKey, kMenuItemDataCmdVirtualKey). */
	private final Map<Long,Integer> virtualKeys=new HashMap<Long,Integer>();
	private static final java.nio.charset.Charset MAC_ROMAN=java.nio.charset.Charset.forName("x-MacRoman");
	/** A Mac (system font) character as a UniChar: the command, check mark, diamond and apple glyphs at $11-$14. */
	static int uniChar(int b){b&=0xff;switch(b){case 0x11:return 0x2318;case 0x12:return 0x2713;case 0x13:return 0x25c6;case 0x14:return 0xf8ff;}return b<0x80?b:new String(new byte[]{(byte)b},MAC_ROMAN).charAt(0);}
	static int macChar(int u){u&=0xffff;switch(u){case 0x2318:return 0x11;case 0x2713:return 0x12;case 0x25c6:return 0x13;case 0xf8ff:return 0x14;}
		if(u<0x80)return u;byte[] b=String.valueOf((char)u).getBytes(MAC_ROMAN);return b.length==1&&b[0]!='?'?b[0]&0xff:0;}
	private int countItems(int menu) throws M68kException {return (short)rt.toolbox("CountMenuItems",menu);}
	/** A Menu Manager getter (menu, item, *out) reading a word, byte or long. */
	private int itemWord(String getter,int menu,int item) throws M68kException {int t=rt.allocate(4,4);try{rt.toolbox(getter,menu,item,t);return memory.readWord(t);}finally{memory.free(t);}}
	private int itemByte(String getter,int menu,int item) throws M68kException {int t=rt.allocate(4,4);try{rt.toolbox(getter,menu,item,t);return memory.readByte(t);}finally{memory.free(t);}}
	private int itemLong(String getter,int menu,int item) throws M68kException {int t=rt.allocate(4,4);try{rt.toolbox(getter,menu,item,t);return memory.readLong(t);}finally{memory.free(t);}}
	/** The item's Style (PpcCarbon's GetItemStyle: the 68K glue has no bridge for it). */
	private int itemStyle(int menu,int item) throws M68kException {int t=rt.allocate(4,4);try{memory.writeLong(t,0);carbon("GetItemStyle",menu,item,t);return memory.readByte(t);}finally{memory.free(t);}}
	private int carbonLong(String getter,int menu,int item) throws M68kException {int t=rt.allocate(4,4);try{carbon(getter,menu,item,t);return memory.readLong(t);}finally{memory.free(t);}}
	private boolean menuEnabled(int menu) throws M68kCpuException {return (memory.readLong(memory.readLong(menu)+10)&1)!=0;}
	private boolean itemEnabled(int menu,int item) throws M68kException {return (carbon("IsMenuItemEnabled",menu,item)&0xff)!=0;}
	/** The menu and item a Copy/SetMenuItemData call names: an index (0 = the menu itself), or a command ID found in the menu and its submenus. */
	private int[] dataTarget(int menu,int item,boolean isCommandID) throws M68kException {
		if(isCommandID)return commandItem(menu,item);
		if(menu==0||item<0||item>countItems(menu))return null;return new int[]{menu,item};
	}
	private boolean collectionsAvailable(){return rt.toolbox.implemented(0xabf6);}
	/** The properties of an item (item 0: of the menu) in creator/tag order of setting, as (creator, tag, property). */
	private List<Object[]> itemPropertyList(int menu,int item){
		List<Object[]> list=new ArrayList<Object[]>();String prefix=menu+"/"+(item&0xffff)+"/";
		for(Map.Entry<String,Property> e:itemProperties.entrySet())if(e.getKey().startsWith(prefix)){String[] k=e.getKey().split("/");list.add(new Object[]{Integer.parseInt(k[2]),Integer.parseInt(k[3]),e.getValue()});}
		return list;
	}

	/** CopyMenuItemData for one item (or the menu, item 0): every asked-for field of {@code rec}; the flags of fields that do not apply are cleared. */
	private int copyItemData(int menu,int item,int rec) throws M68kException {
		int which=memory.readLong(rec+MD_FLAGS)&MID_ALL;if(item==0)which&=MID_MENU;long key=itemKey(menu,item);
		if((which&(MID_TEXT|MID_CFSTRING))==(MID_TEXT|MID_CFSTRING))which&=cfTextItems.contains(key)?~MID_TEXT:~MID_CFSTRING; // only the format it was last set in
		int p=memory.readLong(menu);
		if((which&MID_TEXT)!=0){int out=memory.readLong(rec+MD_TEXT);if(out!=0){if(item==0)memory.copy(p+14,out,memory.readByte(p+14)+1);else rt.toolbox("GetMenuItemText",menu,item,out);}}
		if((which&MID_CFSTRING)!=0){int out=rec+MD_CFTEXT;memory.writeLong(out,0);if(item==0)carbon("CopyMenuTitleAsCFString",menu,out);else carbon("CopyMenuItemTextAsCFString",menu,item,out);}
		if((which&MID_MARK)!=0)memory.writeWord(rec+MD_MARK,(itemWord("GetItemCmd",menu,item)&0xff)==0x1b?0:uniChar(itemWord("GetItemMark",menu,item))); // a submenu's mark is its ID
		if((which&MID_CMD_KEY)!=0){int k=itemWord("GetItemCmd",menu,item)&0xff;memory.writeWord(rec+MD_CMD_KEY,k>=0x1b&&k<=0x1f?0:uniChar(k));} // $1B-$1F: submenu, script, icon kinds - no key
		if((which&MID_CMD_KEY_GLYPH)!=0)memory.writeLong(rec+MD_CMD_KEY_GLYPH,(short)itemWord("GetMenuItemKeyGlyph",menu,item));
		if((which&MID_CMD_KEY_MODIFIERS)!=0)memory.writeLong(rec+MD_CMD_KEY_MODIFIERS,itemByte("GetMenuItemModifiers",menu,item));
		if((which&MID_STYLE)!=0)memory.writeByte(rec+MD_STYLE,itemStyle(menu,item));
		if((which&MID_ENABLED)!=0)memory.writeByte(rec+MD_ENABLED,(item==0?menuEnabled(menu):itemEnabled(menu,item))?1:0);
		if((which&MID_ICON_ENABLED)!=0)memory.writeByte(rec+MD_ICON_ENABLED,iconsDisabled.contains(key)?0:1);
		if((which&MID_ICON_ID)!=0)memory.writeLong(rec+MD_ICON_ID,itemWord("GetItemIcon",menu,item)&0xff);
		if((which&MID_ICON_HANDLE)!=0){int[] icon=itemIcons.get(key);memory.writeLong(rec+MD_ICON_TYPE,icon==null?0:icon[0]);memory.writeLong(rec+MD_ICON_HANDLE,icon==null?0:icon[1]);}
		if((which&MID_COMMAND_ID)!=0)memory.writeLong(rec+MD_COMMAND_ID,carbonLong("GetMenuItemCommandID",menu,item));
		if((which&MID_TEXT_ENCODING)!=0)memory.writeLong(rec+MD_ENCODING,itemLong("GetMenuItemTextEncoding",menu,item));
		if((which&MID_SUBMENU_ID)!=0){int id=(short)itemWord("GetMenuItemHierarchicalID",menu,item);if(id==0){int sub=submenu(menu,item);if(sub!=0)id=(short)memory.readWord(memory.readLong(sub));}memory.writeWord(rec+MD_SUBMENU_ID,id);}
		if((which&MID_SUBMENU_HANDLE)!=0)memory.writeLong(rec+MD_SUBMENU_HANDLE,submenu(menu,item));
		if((which&MID_FONT_ID)!=0)memory.writeLong(rec+MD_FONT_ID,(short)itemWord("GetMenuItemFontID",menu,item));
		if((which&MID_REFCON)!=0)memory.writeLong(rec+MD_REFCON,itemLong("GetMenuItemRefCon",menu,item));
		if((which&MID_ATTRIBUTES)!=0)memory.writeLong(rec+MD_ATTRIBUTES,item==0?carbonLongMenu("GetMenuAttributes",menu):carbonLong("GetMenuItemAttributes",menu,item));
		if((which&MID_PROPERTIES)!=0){ // a Collection: tag = the property's creator, id = its tag
			if(!collectionsAvailable())which&=~MID_PROPERTIES;
			else{int coll=memory.readLong(rec+MD_PROPERTIES);if(coll==0){coll=rt.toolbox("NewCollection");memory.writeLong(rec+MD_PROPERTIES,coll);}else rt.toolbox("EmptyCollection",coll);
				if(coll==0)return MEM_FULL_ERR;
				for(Object[] e:itemPropertyList(menu,item)){byte[] d=((Property)e[2]).data;int b=rt.allocate(Math.max(1,d.length),4);
					try{memory.load(b,d);rt.toolbox("AddCollectionItem",coll,(Integer)e[0],(Integer)e[1],d.length,b);}finally{memory.free(b);}}}}
		if((which&MID_INDENT)!=0)memory.writeLong(rec+MD_INDENT,carbonLong("GetMenuItemIndent",menu,item));
		if((which&MID_CMD_VIRTUAL_KEY)!=0){Integer v=virtualKeys.get(key);memory.writeWord(rec+MD_CMD_VIRTUAL_KEY,v==null?0:v);}
		memory.writeLong(rec,0);memory.writeLong(rec+MD_FLAGS,which);
		return NO_ERR;
	}
	private int carbonLongMenu(String getter,int menu) throws M68kException {int t=rt.allocate(4,4);try{carbon(getter,menu,t);return memory.readLong(t);}finally{memory.free(t);}}

	/** SetMenuItemData for one item (or the menu, item 0). Submenus first (detaching one clears the classic key and mark), then
	 *  the key and mark - which the classic encoding of a submenu ($1B, mark = its ID) keeps for itself, so they are left alone there. */
	private int setItemData(int menu,int item,int rec) throws M68kException {
		int which=memory.readLong(rec+MD_FLAGS)&MID_ALL;if(item==0)which&=MID_MENU;long key=itemKey(menu,item);
		if((which&MID_TEXT)!=0){int text=memory.readLong(rec+MD_TEXT);if(text!=0){if(item==0)rt.toolbox.menus.setTitle(menu,rt.toolbox.readPascal(text));else rt.toolbox("SetMenuItemText",menu,item,text);cfTextItems.remove(key);}}
		if((which&MID_CFSTRING)!=0){int cf=memory.readLong(rec+MD_CFTEXT);if(cf!=0){if(item==0)carbon("SetMenuTitleWithCFString",menu,cf);else carbon("SetMenuItemTextWithCFString",menu,item,cf);cfTextItems.add(key);}}
		if((which&MID_SUBMENU_ID)!=0){int id=(short)memory.readWord(rec+MD_SUBMENU_ID);
			if(id!=0||(itemWord("GetItemCmd",menu,item)&0xff)==0x1b){hierarchicalMenus.remove(key);rt.toolbox("SetMenuItemHierarchicalID",menu,item,id);}}
		if((which&MID_SUBMENU_HANDLE)!=0){int sub=memory.readLong(rec+MD_SUBMENU_HANDLE);if(sub!=0||submenu(menu,item)!=0)carbon("SetMenuItemHierarchicalMenu",menu,item,sub);}
		boolean hierarchical=item!=0&&(itemWord("GetItemCmd",menu,item)&0xff)==0x1b;
		if((which&MID_MARK)!=0&&!hierarchical)rt.toolbox("SetItemMark",menu,item,macChar(memory.readWord(rec+MD_MARK)));
		if((which&MID_CMD_KEY)!=0&&!hierarchical)rt.toolbox("SetItemCmd",menu,item,macChar(memory.readWord(rec+MD_CMD_KEY)));
		if((which&MID_CMD_KEY_GLYPH)!=0)rt.toolbox("SetMenuItemKeyGlyph",menu,item,(short)memory.readLong(rec+MD_CMD_KEY_GLYPH));
		if((which&MID_CMD_KEY_MODIFIERS)!=0)rt.toolbox("SetMenuItemModifiers",menu,item,memory.readLong(rec+MD_CMD_KEY_MODIFIERS)&0xff);
		if((which&MID_STYLE)!=0)rt.toolbox("SetItemStyle",menu,item,memory.readByte(rec+MD_STYLE));
		if((which&MID_ICON_ENABLED)!=0){if(memory.readByte(rec+MD_ICON_ENABLED)!=0)iconsDisabled.remove(key);else iconsDisabled.add(key);}
		if((which&MID_ICON_ID)!=0)rt.toolbox("SetItemIcon",menu,item,memory.readLong(rec+MD_ICON_ID)&0xff);
		if((which&MID_ICON_HANDLE)!=0){int type=memory.readLong(rec+MD_ICON_TYPE),h=memory.readLong(rec+MD_ICON_HANDLE);if(type==0||h==0)itemIcons.remove(key);else itemIcons.put(key,new int[]{type,h});}
		if((which&MID_COMMAND_ID)!=0)carbon("SetMenuItemCommandID",menu,item,memory.readLong(rec+MD_COMMAND_ID));
		if((which&MID_TEXT_ENCODING)!=0)rt.toolbox("SetMenuItemTextEncoding",menu,item,memory.readLong(rec+MD_ENCODING));
		if((which&MID_FONT_ID)!=0)rt.toolbox("SetMenuItemFontID",menu,item,(short)memory.readLong(rec+MD_FONT_ID));
		if((which&MID_REFCON)!=0)rt.toolbox("SetMenuItemRefCon",menu,item,memory.readLong(rec+MD_REFCON));
		if((which&MID_ATTRIBUTES)!=0){int attr=memory.readLong(rec+MD_ATTRIBUTES);if(item==0)carbon("ChangeMenuAttributes",menu,attr,~attr);else carbon("ChangeMenuItemAttributes",menu,item,attr,~attr);}
		if((which&MID_ENABLED)!=0)rt.toolbox(memory.readByte(rec+MD_ENABLED)!=0?"EnableItem":"DisableItem",menu,item); // after the attributes, which carry a disabled bit too
		if((which&MID_PROPERTIES)!=0&&memory.readLong(rec+MD_PROPERTIES)!=0){
			if(!collectionsAvailable())return UNIMP_ERR;
			int coll=memory.readLong(rec+MD_PROPERTIES),n=rt.toolbox("CountCollectionItems",coll),info=rt.allocate(16,4);
			try{for(int i=1;i<=n;i++){
				if((short)rt.toolbox("GetIndexedCollectionItemInfo",coll,i,info,info+4,info+8,info+12)!=0)continue;
				int size=memory.readLong(info+8),b=rt.allocate(Math.max(4,size),4);
				try{memory.writeLong(info+8,size);rt.toolbox("GetIndexedCollectionItem",coll,i,info+8,b);
					String k=propertyName(menu,item,memory.readLong(info),memory.readLong(info+4));Property old=itemProperties.get(k);Property np=new Property(memory.readBytes(b,size));if(old!=null)np.attributes=old.attributes;itemProperties.put(k,np);}
				finally{memory.free(b);}}}
			finally{memory.free(info);}}
		if((which&MID_INDENT)!=0)carbon("SetMenuItemIndent",menu,item,memory.readLong(rec+MD_INDENT));
		if((which&MID_CMD_VIRTUAL_KEY)!=0){int v=memory.readWord(rec+MD_CMD_VIRTUAL_KEY);virtualKeys.put(key,v);}
		return NO_ERR;
	}

	/** Menu item system properties (CarbonLib-private, the same calls as the item properties, a separate store) and control system properties. */
	private final Map<String,Property> menuSystemProperties=new HashMap<String,Property>(),controlSystemProperties=new HashMap<String,Property>();
	private int setProperty(Map<String,Property> store,String k,int creator,int size,int data) throws M68kCpuException {
		if(creator==0)return PROPERTY_INVALID_ERR;Property old=store.get(k);Property p=new Property(data==0?new byte[Math.max(0,size)]:memory.readBytes(data,size));if(old!=null)p.attributes=old.attributes;store.put(k,p);return NO_ERR;
	}
	private int propertyAttributes(Property p,int out) throws M68kCpuException {if(p==null)return PROPERTY_NOT_FOUND_ERR;if(out!=0)memory.writeLong(out,p.attributes);return NO_ERR;}
	private int changePropertyAttributes(Property p,int set,int clear){if(p==null)return PROPERTY_NOT_FOUND_ERR;p.attributes=(p.attributes|set)&~clear;return NO_ERR;}
	private static String controlPropertyName(int control,int creator,int tag){return control+"/"+creator+"/"+tag;}

	private void defineMenuItemData(PpcLibraries l){
		l.register(null,"CopyMenuItemData",(r,c)->{int[] a=args(4);int[] m=dataTarget(a[0],a[1],(a[2]&0xff)!=0); // (menu, item, isCommandID, MenuItemDataPtr)
			if(a[3]==0){ret(PARAM_ERR);return;}if(m==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}ret(copyItemData(m[0],m[1],a[3]));});
		l.register(null,"SetMenuItemData",(r,c)->{int[] a=args(4);int[] m=dataTarget(a[0],a[1],(a[2]&0xff)!=0);
			if(a[3]==0){ret(PARAM_ERR);return;}if(m==null){ret(MENU_ITEM_NOT_FOUND_ERR);return;}ret(setItemData(m[0],m[1],a[3]));});
		// the item icons the 68K Menu Manager does not keep (its GetMenuItemIconHandle answers none): kept here, so CopyMenuItemData sees them
		l.register(null,"SetMenuItemIconHandle",(r,c)->{int[] a=args(4);long k=itemKey(a[0],a[1]&0xffff);int type=a[2]&0xff;if(type==0||a[3]==0)itemIcons.remove(k);else itemIcons.put(k,new int[]{type,a[3]});ret(NO_ERR);});
		l.register(null,"GetMenuItemIconHandle",(r,c)->{int[] a=args(4);int[] icon=itemIcons.get(itemKey(a[0],a[1]&0xffff));if(a[2]!=0)memory.writeByte(a[2],icon==null?0:icon[0]);if(a[3]!=0)memory.writeLong(a[3],icon==null?0:icon[1]);ret(NO_ERR);});
		l.register(null,"EnableMenuItemIcon",(r,c)->iconsDisabled.remove(itemKey(arg(0),arg(1)&0xffff)));
		l.register(null,"DisableMenuItemIcon",(r,c)->iconsDisabled.add(itemKey(arg(0),arg(1)&0xffff)));
		l.register(null,"IsMenuItemIconEnabled",(r,c)->ret(iconsDisabled.contains(itemKey(arg(0),arg(1)&0xffff))?0:1));
		l.register(null,"SetMenuItemCommandKey",(r,c)->{int[] a=args(4);long k=itemKey(a[0],a[1]&0xffff); // (menu, item, isVirtualKey, key): a virtual key is matched by IsMenuKeyEvent
			if((a[2]&0xff)!=0)virtualKeys.put(k,a[3]&0xffff);else{virtualKeys.remove(k);rt.toolbox("SetItemCmd",a[0],a[1]&0xffff,macChar(a[3]));}ret(NO_ERR);});
		l.register(null,"GetMenuItemCommandKey",(r,c)->{int[] a=args(4); // (menu, item, getVirtualKey, *key)
			int key;if((a[2]&0xff)!=0){Integer v=virtualKeys.get(itemKey(a[0],a[1]&0xffff));key=v==null?0:v;}else{key=itemWord("GetItemCmd",a[0],a[1]&0xffff)&0xff;key=key>=0x1b&&key<=0x1f?0:uniChar(key);}
			if(a[3]!=0)memory.writeWord(a[3],key);ret(NO_ERR);});
		// menu item system properties
		l.register(null,"SetMenuItemSystemProperty",(r,c)->{int[] a=args(6);ret(setProperty(menuSystemProperties,propertyName(a[0],a[1],a[2],a[3]),a[2],a[4],a[5]));});
		l.register(null,"GetMenuItemSystemProperty",(r,c)->{int[] a=args(7);ret(getProperty(menuSystemProperties.get(propertyName(a[0],a[1],a[2],a[3])),a[4],a[5],a[6]));});
		l.register(null,"GetMenuItemSystemPropertySize",(r,c)->{int[] a=args(5);ret(getProperty(menuSystemProperties.get(propertyName(a[0],a[1],a[2],a[3])),0,a[4],0));});
		l.register(null,"RemoveMenuItemSystemProperty",(r,c)->{int[] a=args(4);ret(menuSystemProperties.remove(propertyName(a[0],a[1],a[2],a[3]))==null?PROPERTY_NOT_FOUND_ERR:NO_ERR);});
		l.register(null,"GetMenuItemSystemPropertyAttributes",(r,c)->{int[] a=args(5);ret(propertyAttributes(menuSystemProperties.get(propertyName(a[0],a[1],a[2],a[3])),a[4]));});
		l.register(null,"ChangeMenuItemSystemPropertyAttributes",(r,c)->{int[] a=args(6);ret(changePropertyAttributes(menuSystemProperties.get(propertyName(a[0],a[1],a[2],a[3])),a[4],a[5]));});
		// control system properties: (control, creator, tag, ...) as the control properties
		l.register(null,"SetControlSystemProperty",(r,c)->{int[] a=args(5);ret(setProperty(controlSystemProperties,controlPropertyName(a[0],a[1],a[2]),a[1],a[3],a[4]));});
		l.register(null,"GetControlSystemProperty",(r,c)->{int[] a=args(6);ret(getProperty(controlSystemProperties.get(controlPropertyName(a[0],a[1],a[2])),a[3],a[4],a[5]));});
		l.register(null,"GetControlSystemPropertySize",(r,c)->{int[] a=args(4);ret(getProperty(controlSystemProperties.get(controlPropertyName(a[0],a[1],a[2])),0,a[3],0));});
		l.register(null,"RemoveControlSystemProperty",(r,c)->{int[] a=args(3);ret(controlSystemProperties.remove(controlPropertyName(a[0],a[1],a[2]))==null?PROPERTY_NOT_FOUND_ERR:NO_ERR);});
		l.register(null,"GetControlSystemPropertyAttributes",(r,c)->{int[] a=args(4);ret(propertyAttributes(controlSystemProperties.get(controlPropertyName(a[0],a[1],a[2])),a[3]));});
		l.register(null,"ChangeControlSystemPropertyAttributes",(r,c)->{int[] a=args(5);ret(changePropertyAttributes(controlSystemProperties.get(controlPropertyName(a[0],a[1],a[2])),a[3],a[4]));});
	}

	// ------------------------------------------------------------------ CarbonLib 1.6: menus, the menu bar, menu keys

	/** kMenuEventIncludeDisabledItems, kMenuEventQueryOnly, kMenuEventDontCheckSubmenus. */
	private static final int MENU_EVENT_INCLUDE_DISABLED=1,MENU_EVENT_QUERY_ONLY=2,MENU_EVENT_DONT_CHECK_SUBMENUS=4;
	private static final int CLASS_KEYBOARD=ostype("keyb"),CLASS_MOUSE=ostype("mous"),CLASS_WINDOW=ostype("wind"),CLASS_CONTROL=ostype("cntl"),
		P_DIRECT=ostype("----"),P_KEY_CHAR=ostype("kchr"),P_KEY_CODE=ostype("kcod"),P_KEY_MODIFIERS=ostype("kmod"),P_MOUSE_BUTTON=ostype("mbtn"),P_MOUSE_LOCATION=ostype("mloc"),
		T_WILD=ostype("****"),T_WINDOW=ostype("wind"),T_CONTROL=ostype("ctrl"),T_QD_POINT=ostype("QDpt"),T_RGN=ostype("rgnh"),T_RECT=ostype("qdrt"),T_UINT32=ostype("magn"),
		T_SHORT=ostype("shor"),T_LONG=ostype("long"),T_BOOLEAN=ostype("bool");
	static final int EVENT_NOT_HANDLED=-9874,EVENT_HANDLER_PROC_INFO=0x00000ff0;
	/** An event parameter's bytes, whatever its type; null when the event has none. */
	private byte[] eventParam(int event,int name) throws M68kException {
		int t=rt.allocate(8,4);
		try{memory.writeLong(t,0);if(carbon("GetEventParameter",event,name,T_WILD,0,0,t,0)!=0)return null;int n=memory.readLong(t);if(n<=0)return new byte[0];
			int b=rt.allocate(n,4);try{carbon("GetEventParameter",event,name,T_WILD,0,n,0,b);return memory.readBytes(b,n);}finally{memory.free(b);}}
		finally{memory.free(t);}
	}
	/** An integer parameter (1, 2 or 4 bytes, big-endian), or {@code fallback}. */
	private int eventInt(int event,int name,int fallback) throws M68kException {
		byte[] b=eventParam(event,name);if(b==null||b.length==0)return fallback;
		if(b.length==1)return b[0]&0xff;if(b.length<4)return (short)((b[0]&0xff)<<8|b[1]&0xff);return (b[0]&0xff)<<24|(b[1]&0xff)<<16|(b[2]&0xff)<<8|b[3]&0xff;
	}
	/**
	 * The item a keyboard event's command key chooses ({@code start} and its submenus, or the menu bar's menus): the item's key
	 * (case-insensitive) or virtual key, and its modifiers (kMenuShiftModifier ... kMenuNoCommandModifier) exactly the event's,
	 * as MenuEvent matches them. {top-level menu, menu, item}, or null.
	 */
	private int[] menuKeyItem(int start,int event,int options) throws M68kException {
		int cls=carbon("GetEventClass",event),kind=carbon("GetEventKind",event);
		if(cls!=CLASS_KEYBOARD||(kind!=1&&kind!=2))return null; // kEventRawKeyDown, kEventRawKeyRepeat
		int ch=eventInt(event,P_KEY_CHAR,0)&0xff,code=eventInt(event,P_KEY_CODE,-1),modifiers=eventInt(event,P_KEY_MODIFIERS,0);
		int wanted=((modifiers&0x200)!=0?1:0)|((modifiers&0x800)!=0?2:0)|((modifiers&0x1000)!=0?4:0)|((modifiers&0x100)==0?8:0);
		List<Integer> roots=new ArrayList<Integer>();if(start!=0)roots.add(start);else roots.addAll(menuBarMenus());
		Set<Integer> seen=new HashSet<Integer>();
		for(int root:roots){int[] hit=menuKeyIn(root,ch,code,wanted,options,seen);if(hit!=null)return new int[]{root,hit[0],hit[1]};}
		return null;
	}
	private int[] menuKeyIn(int menu,int ch,int code,int wanted,int options,Set<Integer> seen) throws M68kException {
		if(menu==0||!seen.add(menu))return null;boolean all=(options&MENU_EVENT_INCLUDE_DISABLED)!=0;
		if(!all&&!menuEnabled(menu))return null;
		int n=countItems(menu);char lower=Character.toLowerCase((char)ch);
		for(int i=1;i<=n;i++){
			if(!all&&!itemEnabled(menu,i))continue; // nor the submenu of a disabled item
			int cmd=itemWord("GetItemCmd",menu,i)&0xff;Integer vk=virtualKeys.get(itemKey(menu,i));
			boolean key=vk!=null?vk==code:cmd>=0x20&&Character.toLowerCase((char)cmd)==lower;
			if(key&&itemByte("GetMenuItemModifiers",menu,i)==wanted)return new int[]{menu,i};
			if((options&MENU_EVENT_DONT_CHECK_SUBMENUS)==0){int[] hit=menuKeyIn(submenu(menu,i),ch,code,wanted,options,seen);if(hit!=null)return hit;}
		}
		return null;
	}
	/** The root menu (SetRootMenu / AcquireRootMenu): an item per menu bar menu, its submenu that menu. */
	private int rootMenu;
	private void retainMenu(int menu){Integer v=menuRetainCounts.get(menu);menuRetainCounts.put(menu,(v==null?1:v)+1);}
	/** The menu bar's MenuList entry at {@code offset} (6, 12, ...): its menu, or 0. */
	private int menuAtOffset(int offset) throws M68kCpuException {int list=memory.readLong(0xa1c);int p=list==0?0:memory.readLong(list);if(p==0||offset<6||offset>memory.readWord(p)||offset%6!=0)return 0;return memory.readLong(p+offset);}

	private void defineMenus16(PpcLibraries l){
		l.register(null,"IsMenuKeyEvent",(r,c)->{int[] a=args(5); // (startMenu, event, options, *outMenu, *outItem): matches without choosing anything
			int[] hit=a[1]==0?null:menuKeyItem(a[0],a[1],a[2]);
			if(a[3]!=0)memory.writeLong(a[3],hit==null?0:hit[1]);if(a[4]!=0)memory.writeWord(a[4],hit==null?0:hit[2]);
			if(hit!=null&&(a[2]&MENU_EVENT_QUERY_ONLY)==0)rt.toolbox("HiliteMenu",(short)memory.readWord(memory.readLong(hit[0]))); // the title hilited, as MenuKey does
			ret(hit!=null?1:0);});
		// CarbonLib-private (ReceiveNextEvent asks it before the menus are searched): a key-down with the command key, or one an item takes without it
		l.register(null,"CouldBeMenuKeyEvent",(r,c)->{int e=arg(0);
			if(e==0||carbon("GetEventClass",e)!=CLASS_KEYBOARD){ret(0);return;}int kind=carbon("GetEventKind",e);if(kind!=1&&kind!=2){ret(0);return;}
			ret((eventInt(e,P_KEY_MODIFIERS,0)&0x100)!=0||menuKeyItem(0,e,MENU_EVENT_INCLUDE_DISABLED|MENU_EVENT_QUERY_ONLY)!=null?1:0);});
		l.register(null,"IsShowContextualMenuEvent",(r,c)->{int e=arg(0); // a mouse-down with the control key, or the secondary button
			if(e==0||carbon("GetEventClass",e)!=CLASS_MOUSE||carbon("GetEventKind",e)!=1){ret(0);return;}
			ret((eventInt(e,P_KEY_MODIFIERS,0)&0x1000)!=0||eventInt(e,P_MOUSE_BUTTON,1)==2?1:0);});
		l.register(null,"CopyMenuItems",(r,c)->{int[] a=args(5);int src=a[0],first=a[1]&0xffff,count=a[2],dst=a[3],after=a[4]&0xffff; // (source, firstItem, numItems, dest, insertAfter)
			if(src==0||dst==0||count<0){ret(PARAM_ERR);return;}int n=countItems(src);if(count>0&&(first<1||first+count-1>n)){ret(MENU_ITEM_NOT_FOUND_ERR);return;}
			after=Math.min(after,countItems(dst));
			int[] recs=new int[count];List<List<Object[]>> props=new ArrayList<List<Object[]>>();
			try{for(int i=0;i<count;i++){ // everything read first: the destination may be the source
					int rec=recs[i]=rt.allocate(MD_SIZE+256,4);memory.writeLong(rec+MD_TEXT,rec+MD_SIZE);memory.writeLong(rec+MD_FLAGS,MID_ALL&~(MID_CFSTRING|MID_PROPERTIES));
					copyItemData(src,first+i,rec);memory.writeLong(rec+MD_FLAGS,MID_ALL&~(MID_CFSTRING|MID_PROPERTIES));props.add(itemPropertyList(src,first+i));}
				int blank=pascal(" ");
				try{for(int i=0;i<count;i++){int item=after+i+1;rt.toolbox("InsertMenuItem",dst,blank,item-1);setItemData(dst,item,recs[i]);
					for(Object[] e:props.get(i)){Property p=(Property)e[2];Property q=new Property(p.data.clone());q.attributes=p.attributes;itemProperties.put(propertyName(dst,item,(Integer)e[0],(Integer)e[1]),q);}}}
				finally{memory.free(blank);}}
			finally{for(int rec:recs)if(rec!=0)memory.free(rec);}
			ret(NO_ERR);});
		l.register(null,"DuplicateMenuBar",(r,c)->{int[] a=args(2);if(a[0]==0||a[1]==0){ret(PARAM_ERR);return;} // (MenuBarHandle, *out): the menus themselves are shared, as with GetMenuBar's copy
			int h=rt.toolbox.api.createHandle(memory.readBytes(memory.readLong(a[0]),rt.toolbox.api.handleSize(a[0])));memory.writeLong(a[1],h);ret(h==0?MEM_FULL_ERR:NO_ERR);});
		l.register(null,"SetMenuTitleIcon",(r,c)->{int[] a=args(3); // (menu, type, icon): kMenuIconSuiteType / kMenuIconRefType; the host bar still shows the text title
			if(a[0]==0){ret(PARAM_ERR);return;}
			if(a[1]==0){if(a[2]!=0){ret(PARAM_ERR);return;}titleIcons.remove(a[0]);ret(NO_ERR);return;}
			if(a[1]!=5&&a[1]!=6||a[2]==0){ret(PARAM_ERR);return;}titleIcons.put(a[0],new int[]{a[1],a[2]});ret(NO_ERR);});
		l.register(null,"GetMenuTitleIcon",(r,c)->{int[] a=args(3);int[] icon=titleIcons.get(a[0]);if(a[1]!=0)memory.writeLong(a[1],icon==null?0:icon[0]);if(a[2]!=0)memory.writeLong(a[2],icon==null?0:icon[1]);ret(a[0]==0?PARAM_ERR:NO_ERR);});
		// the root menu: its items stand for the menu bar's menus (their submenus); SetRootMenu installs the submenus as the menu bar
		l.register(null,"AcquireRootMenu",(r,c)->{
			if(rootMenu==0||memory.readLong(rootMenu)==0){int title=pascal("");rootMenu=rt.toolbox("NewMenu",0,title);memory.free(title);if(rootMenu==0){ret(0);return;}}
			for(int n=countItems(rootMenu);n>0;n--){hierarchicalMenus.remove(itemKey(rootMenu,n));rt.toolbox("DeleteMenuItem",rootMenu,n);}
			int blank=pascal(" ");
			try{List<Integer> bar=menuBarMenus();for(int i=0;i<bar.size();i++){int m=bar.get(i);rt.toolbox("AppendMenu",rootMenu,blank);if(memory.readByte(memory.readLong(m)+14)>0)rt.toolbox("SetMenuItemText",rootMenu,i+1,memory.readLong(m)+14); /* an untitled menu keeps the blank: an empty item text ends the item list */hierarchicalMenus.put(itemKey(rootMenu,i+1),m);}}
			finally{memory.free(blank);}
			retainMenu(rootMenu);ret(rootMenu);});
		l.register(null,"SetRootMenu",(r,c)->{int menu=arg(0);if(menu==0){ret(PARAM_ERR);return;}
			List<Integer> subs=new ArrayList<Integer>();int n=countItems(menu);for(int i=1;i<=n;i++){int s=submenu(menu,i);if(s!=0)subs.add(s);}
			rt.toolbox("ClearMenuBar");for(int s:subs)rt.toolbox("InsertMenu",s,0);rt.toolbox("DrawMenuBar");
			if(menu!=rootMenu){retainMenu(menu);rootMenu=menu;}ret(NO_ERR);});
		l.register(null,"ValidateMenuBar",(r,c)->ret(NO_ERR)); // private: the menu bar is never invalid here (IsMenuBarInvalid), nothing to validate
		l.register(null,"IsSystemMenu",(r,c)->{int m=arg(0);int id=m==0||memory.readLong(m)==0?0:(short)memory.readWord(memory.readLong(m)); // private: the Help, Keyboard and Application menus
			ret(id==-16490||id==-16491||id==-16489?1:0);});
		l.register(null,"SetSubmenuParentChoosable",(r,c)->{int[] a=args(3);long k=itemKey(a[0],a[1]&0xffff);Integer v=itemAttributes.get(k); // private: kMenuItemAttrSubmenuParentChoosable
			itemAttributes.put(k,(a[2]&0xff)!=0?(v==null?0:v)|4:(v==null?0:v)&~4);ret(NO_ERR);});
		l.register(null,"GetSubmenuParentChoosable",(r,c)->{Integer v=itemAttributes.get(itemKey(arg(0),arg(1)&0xffff));ret(v!=null&&(v&4)!=0?1:0);});
		l.register(null,"InvalidateAllMenuSizes",(r,c)->{for(int m:menuBarMenus())rt.toolbox("CalcMenuSize",m);ret(NO_ERR);});
		l.register(null,"InvalidateMenuKeyCache",(r,c)->ret(NO_ERR)); // command keys are looked up afresh at every key: no cache to drop
		// The host draws the menus: no pixels are cached, so there is nothing to invalidate, and nothing to cache or redraw from
		l.register(null,"InvalidateCachedMenuPixels",(r,c)->ret(NO_ERR));
		l.register(null,"InvalidateAllCachedMenuPixels",(r,c)->ret(NO_ERR));
		l.register(null,"CacheMenuPixels",(r,c)->ret(UNIMP_ERR));
		l.register(null,"RedrawCachedMenuPixels",(r,c)->ret(UNIMP_ERR));
		l.register(null,"EraseMenuBackground",(r,c)->{int[] a=args(3);if(a[1]==0){ret(PARAM_ERR);return;}rt.toolbox("DrawThemeMenuBackground",a[1],0);ret(NO_ERR);}); // (menu, rect, CGContext): kThemeMenuTypePullDown
		l.register(null,"ScrollMenuImage",(r,c)->{int[] a=args(5);if(a[1]==0){ret(PARAM_ERR);return;} // (menu, rect, dh, dv, CGContext): in the current port
			int rgn=rt.toolbox("NewRgn");try{rt.toolbox("ScrollRect",a[1],(short)a[2],(short)a[3],rgn);}finally{rt.toolbox("DisposeRgn",rgn);}ret(NO_ERR);});
		l.register(null,"SetMenuBarHeight",(r,c)->{memory.writeWord(0xbaa,Math.max(0,(short)arg(0)));ret(NO_ERR);}); // private: MBarHeight, as LMSetMBarHeight (HideMenuBar writes 0)
		l.register(null,"MenuBarVisibleKludgeNotification",(r,c)->ret(NO_ERR)); // private notification for Hide/ShowMenuBar: the host bar follows MBarHeight already
		l.register(null,"AdjustDesktopRgnForMenuBar",(r,c)->ret(NO_ERR));       // private: the desktop region (GrayRgn) leaves out the menu bar already
		l.register(null,"RebuildKeybdMenu",(r,c)->ret(NO_ERR));                 // private: there is no Keyboard (script) menu in this menu bar
		// The menu bar clock belongs to the Date & Time control panel's menu bar; the host's bar has none
		l.register(null,"IsMenuBarClockEnabled",(r,c)->ret(0));
		l.register(null,"EnableMenuBarClock",(r,c)->ret(UNIMP_ERR));
		l.register(null,"DisableMenuBarClock",(r,c)->ret(NO_ERR)); // it is off
		l.register(null,"RefreshMenuBarClock",(r,c)->ret(UNIMP_ERR));
		l.register(null,"GetMenuBarClockConfig",(r,c)->ret(UNIMP_ERR)); // private record layout: nothing written
		l.register(null,"SetMenuBarClockConfig",(r,c)->ret(UNIMP_ERR));
		// CarbonLib-private calls whose parameters are not known: answered without touching memory
		l.register(null,"GetMenuItemPencilGlyph",(r,c)->ret(UNIMP_ERR));
		l.register(null,"HMSetMenuItem",(r,c)->ret(UNIMP_ERR));
		l.register(null,"GetThemeMenuBarShape",(r,c)->ret(UNIMP_ERR));
		l.register(null,"GetThemeMenuTitleOffset",(r,c)->ret(UNIMP_ERR));
		l.register(null,"DrawThemeProcessMenuHandle",(r,c)->ret(UNIMP_ERR));
		// Carbon Event Manager companions
		l.register(null,"IsEventInMask",(r,c)->{int[] a=args(2);int rec=rt.allocate(16,4); // (event, EventMask): the classic event it converts to
			try{boolean ok=(carbon("ConvertEventRefToEventRecord",a[0],rec)&0xff)!=0;int what=memory.readWord(rec);ret(ok&&what<16&&(a[1]&(1<<what))!=0?1:0);}finally{memory.free(rec);}});
		l.register(null,"GetEventAttributes",(r,c)->ret(rt.libraries.carbon.eventAttributes(arg(0))));
		l.register(null,"GetEventPriority",(r,c)->ret(1)); // private: the queue keeps no priority once an event is posted - kEventPriorityStandard
	}

	// ------------------------------------------------------------------ CarbonLib's definition procedures, callable as code

	/** procInfos: pascal void MDEF(short, MenuRef, Rect*, Point, short*); pascal long WDEF/CDEF(short, Ref, short, long); pascal long MBDF(short, short, short, long). */
	static final int MENU_DEF_PROC_INFO=0x0000ff80,WINDOW_DEF_PROC_INFO=0x00003bb0,CONTROL_DEF_PROC_INFO=0x00003bb0,MENU_BAR_DEF_PROC_INFO=0x00003ab0;
	/** The standard MDEF's rows: CalcMenuSize gives every item 19 pixels. */
	static final int MENU_ITEM_HEIGHT=19;
	/** Objects a forwarding definition is running for: a definition that ends up calling itself gets the standard behaviour instead. */
	private final Set<Integer> forwarding=new HashSet<Integer>();
	/** The System file's definition stubs (MOVE.L (SP)+,A0; ADDA.W #n,SP ...): the Toolbox draws those objects itself. */
	private boolean isStub(int code,int argumentBytes) throws M68kCpuException {return code!=0&&memory.readWord(code)==0x205f&&memory.readWord(code+2)==0xdefc&&memory.readWord(code+4)==argumentBytes;}
	/** The System's MDEF 0 (SystemResources68k.nativeMdefStub: MOVE.L #'HZM',D0; _ResourceDispatch): Carbon's own standard definition answers instead. */
	private boolean isNativeMdefStub(int code) throws M68kCpuException {return code!=0&&memory.readWord(code)==0x203c&&memory.readLong(code+2)==ResourceManager68k.MDEF_SELECTOR&&memory.readWord(code+6)==0xa822;}
	private int[] readRect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	private void writeRect(int p,int top,int left,int bottom,int right) throws M68kCpuException {memory.writeWord(p,top);memory.writeWord(p+2,left);memory.writeWord(p+4,bottom);memory.writeWord(p+6,right);}

	/** kMenuSizeMsg: the sizes CalcMenuSize gives a standard menu (title + 16, items + 40 in the system font; 19 per item). */
	private void standardMenuSize(int menu) throws M68kException {
		int p=memory.readLong(menu);java.awt.Font f=Fonts68k.font(0,12,0);int width=Fonts68k.width(f,rt.toolbox.readPascal(p+14))+16,n=countItems(menu),text=rt.allocate(256,2);
		try{for(int i=1;i<=n;i++){rt.toolbox("GetMenuItemText",menu,i,text);width=Math.max(width,Fonts68k.width(f,rt.toolbox.readPascal(text))+40);}}finally{memory.free(text);}
		p=memory.readLong(menu);memory.writeWord(p+2,width);memory.writeWord(p+4,n*MENU_ITEM_HEIGHT);
	}
	private boolean choosable(int menu,int item) throws M68kException {return item>=1&&item<=countItems(menu)&&menuEnabled(menu)&&itemEnabled(menu,item)&&!isSeparator(menu,item);}
	/** The item under a point of the menu's rectangle (0 none). */
	private int itemAt(int menu,int[] r,int top,int h,int v) throws M68kException {if(h<r[1]||h>=r[3]||v<r[0]||v>=r[2]||v<top)return 0;int item=(v-top)/MENU_ITEM_HEIGHT+1;return item<=countItems(menu)?item:0;}
	/** One item as the standard MDEF draws it, in the current port: the theme's item background, mark, text in its style, command key. */
	private void drawMenuItem(int menu,int item,int[] r,int top,boolean hilited) throws M68kException {
		int itemTop=top+(item-1)*MENU_ITEM_HEIGHT;int mr=rt.allocate(16,2),ir=mr+8,text=rt.allocate(256,2);
		try{writeRect(mr,r[0],r[1],r[2],r[3]);writeRect(ir,itemTop,r[1],itemTop+MENU_ITEM_HEIGHT,r[3]);
			if(isSeparator(menu,item)){rt.toolbox("DrawThemeMenuSeparator",ir);return;}
			boolean enabled=menuEnabled(menu)&&itemEnabled(menu,item);int state=!enabled?3:hilited?1:0; // kThemeMenuDisabled / Selected / Active
			rt.toolbox("DrawThemeMenuItem",mr,ir,top,top+countItems(menu)*MENU_ITEM_HEIGHT,state,0,0,0);
			rt.toolbox("SetThemeTextColor",!enabled?36:hilited?35:34,32,1); // kThemeTextColorMenuItemDisabled / Selected / Active
			int base=itemTop+14,mark=itemWord("GetItemMark",menu,item)&0xff,cmd=itemWord("GetItemCmd",menu,item)&0xff;
			if(mark!=0&&cmd!=0x1b){rt.toolbox("MoveTo",r[1]+2,base);rt.toolbox("DrawChar",mark);}
			rt.toolbox("TextFace",itemStyle(menu,item));rt.toolbox("GetMenuItemText",menu,item,text);rt.toolbox("MoveTo",r[1]+14,base);rt.toolbox("DrawString",text);rt.toolbox("TextFace",0);
			if(cmd==0x1b){rt.toolbox("MoveTo",r[3]-14,base);rt.toolbox("DrawChar",0xc8);} // the submenu triangle
			else if(cmd>=0x20){rt.toolbox("MoveTo",r[3]-26,base);rt.toolbox("DrawChar",0x11);rt.toolbox("DrawChar",cmd);}}
		finally{memory.free(mr);memory.free(text);}
	}
	/**
	 * The standard menu definition (MDEF 0, which the host otherwise draws itself): the classic messages and Carbon's - size,
	 * draw, choose (classic), pop-up placement (hitPt with swapped coordinates), item rectangle, theme savvy, init, find item
	 * (MenuTrackingData), hilite (MDEFHiliteItemData), draw items (MDEFDrawItemsData).
	 */
	private void standardMdef(int message,int menu,int rectPtr,int hitPt,int which) throws M68kException {
		if(menu==0||memory.readLong(menu)==0)return;
		int p=memory.readLong(menu);if(message!=2&&((short)memory.readWord(p+2)<=0||(short)memory.readWord(p+4)<=0))standardMenuSize(menu);
		int[] r=rectPtr==0?new int[4]:readRect(rectPtr);int n=countItems(menu);
		switch(message){
			case 0:{if(rectPtr==0)return;rt.toolbox("DrawThemeMenuBackground",rectPtr,0);for(int i=1;i<=n;i++)drawMenuItem(menu,i,r,r[0],false);return;} // kMenuDrawMsg
			case 1:{if(which==0)return;int old=(short)memory.readWord(which),hit=itemAt(menu,r,r[0],(short)hitPt,(short)(hitPt>>>16)); // mChooseMsg
				if(!choosable(menu,hit))hit=0;
				if(hit!=old&&rectPtr!=0){if(old>0&&old<=n)drawMenuItem(menu,old,r,r[0],false);if(hit>0)drawMenuItem(menu,hit,r,r[0],true);}
				memory.writeWord(which,hit);return;}
			case 2:standardMenuSize(menu);return; // kMenuSizeMsg
			case 3:{ // kMenuPopUpMsg: hitPt.v holds the left, hitPt.h the top; *whichItem in: the item to put there, out: the menu's top
				int left=(short)(hitPt>>>16),top=(short)hitPt,item=which==0?0:(short)memory.readWord(which);p=memory.readLong(menu);int w=(short)memory.readWord(p+2),h=(short)memory.readWord(p+4);
				int t=top-(item>0?(item-1)*MENU_ITEM_HEIGHT:0);int[] screen=rt.toolbox.api.quickDraw().screenBounds();
				t=Math.max(t,screen[0]+(short)memory.readWord(0xbaa));if(t+h>screen[2])t=Math.max(screen[0]+(short)memory.readWord(0xbaa),screen[2]-h);
				if(rectPtr!=0)writeRect(rectPtr,t,left,t+h,left+w);if(which!=0)memory.writeWord(which,t);memory.writeWord(0xa0a,t);return;} // TopMenuItem
			case 4:{if(which!=0&&rectPtr!=0){int item=(short)memory.readWord(which);if(item>0&&item<=n)drawMenuItem(menu,item,r,r[0],false);}return;} // mDrawItemMsg
			case 5:{if(which==0||rectPtr==0)return;int item=(short)memory.readWord(which),t=r[0]+(item-1)*MENU_ITEM_HEIGHT;writeRect(rectPtr,t,r[1],t+MENU_ITEM_HEIGHT,r[3]);return;} // kMenuCalcItemMsg
			case 7:if(which!=0)memory.writeWord(which,0x7473);return; // kMenuThemeSavvyMsg: kThemeSavvyMenuResponse
			case 8:if(which!=0)memory.writeWord(which,0);return;      // kMenuInitMsg: *whichItem is an OSErr
			case 10:{if(which==0)return;int top=memory.readLong(which+16);if(top==0)top=r[0]; // kMenuFindItemMsg: MenuTrackingData
				int hit=itemAt(menu,r,top,(short)hitPt,(short)(hitPt>>>16));memory.writeWord(which+6,hit);memory.writeWord(which+4,choosable(menu,hit)?hit:0);
				if(hit>0){int t=top+(hit-1)*MENU_ITEM_HEIGHT;writeRect(which+8,t,r[1],t+MENU_ITEM_HEIGHT,r[3]);}else writeRect(which+8,0,0,0,0);return;}
			case 11:{if(which==0||rectPtr==0)return;int prev=(short)memory.readWord(which),next=(short)memory.readWord(which+2); // kMenuHiliteItemMsg
				if(prev>0&&prev<=n)drawMenuItem(menu,prev,r,r[0],false);if(next>0&&next<=n)drawMenuItem(menu,next,r,r[0],true);return;}
			case 12:{if(which==0||rectPtr==0)return;int first=Math.max(1,(short)memory.readWord(which)),last=Math.min(n,(short)memory.readWord(which+2)); // kMenuDrawItemsMsg
				for(int i=first;i<=last;i++)drawMenuItem(menu,i,r,r[0],false);return;}
			default:return; // kMenuDisposeMsg
		}
	}
	/** A menu's own definition (its menuProc's code), or the standard one for the System's stub or a definition calling back into this. */
	private void callMenuDefinition(int message,int menu,int rect,int hitPt,int which) throws M68kException {
		int proc=menu==0||memory.readLong(menu)==0?0:memory.readLong(memory.readLong(menu)+6),code=proc==0?0:memory.readLong(proc);
		if(code==0||isStub(code,18)||isNativeMdefStub(code)||!forwarding.add(menu)){standardMdef(message,menu,rect,hitPt,which);return;}
		try{rt.callUniversal(code,MENU_DEF_PROC_INFO,new int[]{message,menu,rect,hitPt,which});}finally{forwarding.remove(menu);}
	}
	/** CarbonLibWrapperMDEF: Carbon's messages for a classic MDEF - find item and hilite become mChooseMsg, draw items mDrawMsg. */
	private void wrapperMdef(int message,int menu,int rect,int hitPt,int which) throws M68kException {
		switch(message){
			case 8:if(which!=0)memory.writeWord(which,0);return;
			case 9:return;
			case 10:{if(which==0)return;int item=rt.allocate(2,2);
				try{memory.writeWord(item,(short)memory.readWord(which+4));callMenuDefinition(1,menu,rect,hitPt,item);int hit=(short)memory.readWord(item);
					memory.writeWord(which+4,hit);memory.writeWord(which+6,hit);if(hit>0&&rect!=0){int[] r=readRect(rect);int t=r[0]+(hit-1)*MENU_ITEM_HEIGHT;writeRect(which+8,t,r[1],t+MENU_ITEM_HEIGHT,r[3]);}}
				finally{memory.free(item);}return;}
			case 11:case 12:callMenuDefinition(0,menu,rect,hitPt,0);return;
			default:callMenuDefinition(message,menu,rect,hitPt,which);
		}
	}
	/** The MBDF rectangle answered for kMenuBarRectMsg (a pointer the caller reads). */
	private int mbdfRect;
	/** CarbonLibDefaultMBDF: the standard menu bar definition over MenuList - draw, hit (the title's MenuList offset, 0 in the bar, -1 outside), hilite, height, rectangles. */
	private int standardMbdf(int message,int param1,int param2) throws M68kException {
		int height=(short)memory.readWord(0xbaa),list=memory.readLong(0xa1c),p=list==0?0:memory.readLong(list);
		switch(message){
			case 0:rt.toolbox("DrawMenuBar");return 0;
			case 1:{int v=(short)(param2>>>16),h=(short)param2;if(v<0||v>=height||p==0)return -1;int last=memory.readWord(p),right=(short)memory.readWord(p+2);
				for(int at=6;at<=last;at+=6){int left=(short)memory.readWord(p+at+4),end=at+6<=last?(short)memory.readWord(p+at+10):right;if(h>=left&&h<end)return at;}return 0;}
			case 5:{int m=(param2>>>16)!=0?menuAtOffset(param2&0xffff):0;memory.writeWord(0xa26,m==0?0:(short)memory.readWord(memory.readLong(m)));return 0;} // TheMenu (HiliteMenu would call this MBDF again)
			case 6:return height;
			case 9:{if(mbdfRect==0)mbdfRect=rt.allocate(8,2);int[] screen=rt.toolbox.api.quickDraw().screenBounds();int offset=param2&0xffff;
				if(p!=0&&menuAtOffset(offset)!=0){int last=memory.readWord(p),left=(short)memory.readWord(p+offset+4),end=offset+6<=last?(short)memory.readWord(p+offset+10):(short)memory.readWord(p+2);writeRect(mbdfRect,screen[0],left,screen[0]+height,end);}
				else writeRect(mbdfRect,screen[0],screen[1],screen[0]+height,screen[3]);
				return mbdfRect;}
			default:return 0; // calc, init, dispose, save, restore
		}
	}
	/** The standard window definition here: the host draws frames and the Window Manager keeps the regions; hit testing by the regions. */
	private int standardWdef(int window,int message,int param) throws M68kException {
		if(message!=1||window==0)return 0;
		int v=(short)(param>>>16),h=(short)param;
		if((rt.toolbox("PtInRgn",param,memory.readLong(window+118))&0xff)!=0)return 1; // wInContent
		return (rt.toolbox("PtInRgn",param,memory.readLong(window+114))&0xff)!=0?2:0; // wInDrag, wNoHit
	}
	/** CarbonLibCustomWDEF: the window's own definition (its windowDefProc), the standard one for the System's stub. */
	private int customWdef(int varCode,int window,int message,int param) throws M68kException {
		int proc=window==0?0:memory.readLong(window+126),code=proc==0?0:memory.readLong(proc);
		if(code==0||isStub(code,12)||!forwarding.add(window))return standardWdef(window,message,param);
		try{return rt.callUniversal(code,WINDOW_DEF_PROC_INFO,new int[]{varCode,window,message,param});}finally{forwarding.remove(window);}
	}
	/** The natively drawn control kinds as CDEFs: a Java definition's control (edit text ...) through its definition, else the Control Manager's own drawing and part testing. */
	private int nativeCdef(int id,int varCode,int control,int message,int param) throws M68kException {
		ControlManager68k.Control k=rt.toolbox.controls.controls.get(control);if(k==null)return 0;
		if(k.nativeDef!=null){ControlManager68k.NativeDefinition d=k.nativeDef;int rec=memory.readLong(control);
			switch(message){
				case 0:if(memory.readByte(rec+16)!=0)d.draw(control);return 0;
				case 1:return d.test(control,(short)param,(short)(param>>>16));
				case 16:return d.focus(control,(short)param);
				case 18:d.idle(control);return 0;
				case 19:return d.features(control);
				case 22:d.activate(control,param!=0);return 0;
				default:return 0;}}
		return rt.toolbox.controls.standardCdef(id,varCode,control,message,param);
	}

	private void defineDefinitions(PpcLibraries l){
		l.register(null,"StandardMDEF",(r,c)->{int[] a=args(5);standardMdef((short)a[0],a[1],a[2],a[3],a[4]);});
		l.register(null,"CarbonLibCustomMDEF",(r,c)->{int[] a=args(5);callMenuDefinition((short)a[0],a[1],a[2],a[3],a[4]);});
		l.register(null,"CarbonLibWrapperMDEF",(r,c)->{int[] a=args(5);wrapperMdef((short)a[0],a[1],a[2],a[3],a[4]);});
		l.register(null,"CallMenuDef",(r,c)->{int[] a=args(5);callMenuDefinition((short)a[0],a[1],a[2],a[3],a[4]);ret(NO_ERR);}); // private; MDEF order (message, menu, rect, hitPt, whichItem) assumed, as CallMenuDefProc without the routine
		l.register(null,"CarbonLibDefaultMBDF",(r,c)->{int[] a=args(4);ret(standardMbdf((short)a[1],(short)a[2],a[3]));});
		l.register(null,"CarbonLibCustomWDEF",(r,c)->{int[] a=args(4);ret(customWdef((short)a[0],a[1],(short)a[2],a[3]));});
		l.register(null,"CarbonLibObjectWDEF",(r,c)->{int[] a=args(4);Integer k=windowObjectClasses.get(a[1]); // the window's toolbox object class, when it has one
			ret(k!=null?objectWdef(k,(short)a[0],a[1],(short)a[2],a[3]):standardWdef(a[1],(short)a[2],a[3]));});
		l.register(null,"CarbonLibInterceptorWDEF",(r,c)->{int[] a=args(4);final int varCode=(short)a[0],window=a[1]; // Carbon window events to the window's handlers first, then its own WDEF
			ret(windowDefinitionEvent(event->carbon("SendEventToEventTarget",event,window),window,(short)a[2],a[3],(m,p)->customWdef(varCode,window,m,p)));});
		l.register(null,"PopupButtonCDEF",(r,c)->{int[] a=args(4);ret(nativeCdef(25,(short)a[0],a[1],(short)a[2],a[3]));});           // kControlPopupButtonProc (CDEF 25)
		l.register(null,"ShrinkToFitEditFieldCDEF",(r,c)->{int[] a=args(4);ret(nativeCdef(17,(short)a[0],a[1],(short)a[2],a[3]));});  // the edit text field (CDEF 17)
	}

	// ------------------------------------------------------------------ Toolbox object classes (RegisterToolboxObjectClass)

	/** A registered class: its ID, base class, event handler and the events it takes; the definitions made for it. */
	private static final class ObjectClass { final String id;final int base,handler,userData;final Set<Long> types=new HashSet<Long>();int windowDefinition,controlDefId;
		ObjectClass(String id,int base,int handler,int userData){this.id=id;this.base=base;this.handler=handler;this.userData=userData;} }
	private final Map<Integer,ObjectClass> objectClasses=new HashMap<Integer,ObjectClass>();
	/** Windows and controls made from an object class (CreateCustomWindow/CreateCustomControl with kWindowDefObjectClass/kControlDefObjectClass), and their features. */
	private final Map<Integer,Integer> windowObjectClasses=new HashMap<Integer,Integer>(),controlObjectClasses=new HashMap<Integer,Integer>(),objectFeatures=new HashMap<Integer,Integer>();
	private int callRef;
	private static final java.util.Map<PpcRuntime,PpcCarbonToolbox> INSTANCES=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<PpcRuntime,PpcCarbonToolbox>());
	private interface Sender { int send(int event) throws M68kException; }
	private interface Fallback { int call(int message,int param) throws M68kException; }

	/** A new Carbon event (CreateEvent, now) with its direct object. */
	private int newEvent(int cls,int kind,int direct,int directType) throws M68kException {
		int out=rt.allocate(4,4);
		try{memory.writeLong(out,0);rt.cpu.call(rt.nativeSymbol("CarbonLib","CreateEvent",Pef.CLASS_TVECTOR,false),new int[]{0,cls,kind,0,0,0,out},new double[]{0});int e=memory.readLong(out);
			if(e!=0)setParam(e,P_DIRECT,directType,longs(direct));return e;}
		finally{memory.free(out);}
	}
	private void setParam(int event,int name,int type,byte[] v) throws M68kException {int p=rt.allocate(Math.max(4,v.length),4);try{memory.load(p,v);carbon("SetEventParameter",event,name,type,v.length,p);}finally{memory.free(p);}}
	/** Sends an event to a class's handler, then its base classes' while they answer eventNotHandledErr. */
	private int sendToClass(int classRef,int cls,int kind,int event) throws M68kException {
		if(callRef==0)callRef=rt.allocate(4,4); // CallNextEventHandler with it answers eventNotHandledErr: the base class runs next
		for(int depth=0;classRef!=0&&depth<32;depth++){ObjectClass k=objectClasses.get(classRef);if(k==null)break;
			if(k.types.contains(((long)cls<<32)|(kind&0xffffffffL))&&k.handler!=0){
				int[] args={callRef,event,k.userData};int status=rt.isRoutineDescriptor(k.handler)?rt.callUniversal(k.handler,EVENT_HANDLER_PROC_INFO,args):rt.cpu.call(k.handler,args);
				if(status!=EVENT_NOT_HANDLED)return status;}
			classRef=k.base;}
		return EVENT_NOT_HANDLED;
	}
	/** The window's content in global coordinates (as the Window Manager has it), or null. */
	private int[] windowContent(int window){WindowManager68k.Window w=rt.toolbox.windows.window(window);return w==null?null:new int[]{w.top,w.left,w.top+w.height,w.left+w.width};}
	/**
	 * WDEF messages as the window definition events (CarbonEvents.h): wDraw - kEventWindowDrawFrame/DrawPart, wHit -
	 * kEventWindowHitTest, wCalcRgns - kEventWindowGetRegion for the structure and content regions (after the fallback's), wNew -
	 * kEventWindowInit (its features kept), wDispose, wGrow - kEventWindowGetGrowImageRegion, wDrawGIcon - kEventWindowDrawGrowBox,
	 * kWindowMsgGetFeatures, kWindowMsgGetRegion. What no handler takes goes to {@code fallback}.
	 */
	private int windowDefinitionEvent(Sender s,int window,int message,int param,Fallback fallback) throws M68kException {
		final int W=CLASS_WINDOW;
		switch(message){
			case 0:{int e=newEvent(W,param==0?1000:1001,window,T_WINDOW);if(param!=0)setParam(e,ostype("wdpc"),ostype("wdpt"),word(param));
				try{return s.send(e)==0?0:fallback.call(message,param);}finally{carbon("ReleaseEvent",e);}}
			case 1:{int e=newEvent(W,1003,window,T_WINDOW);setParam(e,P_MOUSE_LOCATION,T_QD_POINT,longs(param));
				try{if(s.send(e)==0)return eventInt(e,ostype("wdpc"),0);return fallback.call(message,param);}finally{carbon("ReleaseEvent",e);}}
			case 2:{int result=fallback.call(message,param);
				for(int code:new int[]{32,33}){int rgn=memory.readLong(window+(code==32?114:118));if(rgn==0)continue; // kWindowStructureRgn, kWindowContentRgn
					int e=newEvent(W,1002,window,T_WINDOW);setParam(e,ostype("wshp"),ostype("wshp"),word(code));setParam(e,ostype("rgnh"),T_RGN,longs(rgn));
					try{s.send(e);}finally{carbon("ReleaseEvent",e);}}
				return result;}
			case 3:{int e=newEvent(W,1004,window,T_WINDOW);
				try{if(s.send(e)==0){objectFeatures.put(window,eventInt(e,ostype("wftr"),0));return 0;}return fallback.call(message,param);}finally{carbon("ReleaseEvent",e);}}
			case 4:{int e=newEvent(W,1005,window,T_WINDOW);try{s.send(e);}finally{carbon("ReleaseEvent",e);}objectFeatures.remove(window);windowObjectClasses.remove(window);return fallback.call(message,param);}
			case 5:{if(param==0)return 0;int e=newEvent(W,1012,window,T_WINDOW),rgn=rt.toolbox("NewRgn");
				try{setParam(e,ostype("grct"),T_RECT,memory.readBytes(param,8));setParam(e,ostype("rgnh"),T_RGN,longs(rgn));
					if(s.send(e)==0){rt.toolbox("FrameRgn",rgn);return 0;}return fallback.call(message,param);}
				finally{carbon("ReleaseEvent",e);rt.toolbox("DisposeRgn",rgn);}}
			case 6:{int e=newEvent(W,1011,window,T_WINDOW);try{return s.send(e)==0?0:fallback.call(message,param);}finally{carbon("ReleaseEvent",e);}}
			case 7:{Integer f=objectFeatures.get(window);if(f==null)return fallback.call(message,param);if(param!=0)memory.writeLong(param,f);return 1;} // kWindowMsgGetFeatures
			case 8:{if(param==0)return PARAM_ERR;int e=newEvent(W,1002,window,T_WINDOW); // kWindowMsgGetRegion: GetWindowRegionRec {RgnHandle, WindowRegionCode}
				try{setParam(e,ostype("wshp"),ostype("wshp"),word(memory.readWord(param+4)));setParam(e,ostype("rgnh"),T_RGN,longs(memory.readLong(param)));
					return s.send(e)==0?0:fallback.call(message,param);}finally{carbon("ReleaseEvent",e);}}
			default:return fallback.call(message,param);
		}
	}
	/** The default behaviour of an object class window: content and structure the window's rectangle, hit testing by them. */
	private int objectWindowDefault(int window,int message,int param) throws M68kException {
		int[] c=windowContent(window);
		switch(message){
			case 2:if(c!=null){rt.toolbox.windows.setRegion(memory.readLong(window+114),c[0],c[1],c[2],c[3]);rt.toolbox.windows.setRegion(memory.readLong(window+118),c[0],c[1],c[2],c[3]);}return 0;
			case 8:{int code=memory.readWord(param+4);if(c==null||code!=32&&code!=33)return -5600;rt.toolbox.windows.setRegion(memory.readLong(param),c[0],c[1],c[2],c[3]);return 0;} // errWindowRegionCodeInvalid
			default:return standardWdef(window,message,param);
		}
	}
	private int objectWdef(int classRef,int varCode,int window,int message,int param) throws M68kException {
		if(window!=0&&message!=4)windowObjectClasses.put(window,classRef);
		return windowDefinitionEvent(event->sendToClass(classRef,CLASS_WINDOW,carbon("GetEventKind",event),event),window,message,param,(m,p)->objectWindowDefault(window,m,p));
	}
	/**
	 * CreateCustomWindow's kWindowDefObjectClass (PpcCarbon): a WindowDefUPP for the class - a routine descriptor around a native
	 * WDEF that turns the messages into the class's window definition events. 0 for an unknown class.
	 */
	static int objectClassWindowDefinition(PpcRuntime rt,int classRef) throws M68kCpuException {PpcCarbonToolbox t=INSTANCES.get(rt);return t==null?0:t.windowDefinition(classRef);}
	private int windowDefinition(int classRef) throws M68kCpuException {
		ObjectClass k=objectClasses.get(classRef);if(k==null)return 0;
		if(k.windowDefinition==0){int tv=rt.nativeVector("ObjectClassWDEF "+k.id,(r,c)->{int[] a=args(4);ret(objectWdef(classRef,(short)a[0],a[1],(short)a[2],a[3]));});
			k.windowDefinition=rt.newRoutineDescriptor(tv,WINDOW_DEF_PROC_INFO,PpcRuntime.ISA_PPC);}
		return k.windowDefinition;
	}
	/**
	 * CDEF messages as the control definition events: drawCntl - kEventControlDraw, testCntl - kEventControlHitTest, the region
	 * messages - kEventControlGetPartRegion, initCntl - kEventControlInitialize (features kept for kControlMsgGetFeatures),
	 * dispCntl, activation, focus; any other message as kEventControlArbitraryMessage.
	 */
	private int objectCdef(int classRef,int varCode,int control,int message,int param) throws M68kException {
		final int C=CLASS_CONTROL;int rec=control==0?0:memory.readLong(control);if(rec==0)return 0;
		int[] bounds=readRect(rec+8);
		switch(message){
			case 0:{int e=newEvent(C,4,control,T_CONTROL);if((param&0xffff)!=0)setParam(e,ostype("cprt"),ostype("cprt"),word(param));try{sendToClass(classRef,C,4,e);}finally{carbon("ReleaseEvent",e);}return 0;}
			case 1:{int e=newEvent(C,3,control,T_CONTROL);setParam(e,P_MOUSE_LOCATION,T_QD_POINT,longs(param));
				try{return sendToClass(classRef,C,3,e)==0?eventInt(e,ostype("cprt"),0):0;}finally{carbon("ReleaseEvent",e);}}
			case 2:case 10:case 11:{boolean thumb=message==11||message==2&&param<0;int rgn=param&0x7fffffff;
				rt.toolbox.windows.setRegion(rgn,bounds[0],bounds[1],bounds[2],bounds[3]);
				int e=newEvent(C,101,control,T_CONTROL);setParam(e,ostype("cprt"),ostype("cprt"),word(thumb?129:-1));setParam(e,ostype("crgn"),T_RGN,longs(rgn)); // kControlIndicatorPart / kControlStructureMetaPart
				try{sendToClass(classRef,C,101,e);}finally{carbon("ReleaseEvent",e);}return 0;}
			case 3:{controlObjectClasses.put(control,classRef);int e=newEvent(C,1000,control,T_CONTROL);
				try{if(sendToClass(classRef,C,1000,e)==0)objectFeatures.put(control,eventInt(e,ostype("cftr"),0));}finally{carbon("ReleaseEvent",e);}return 0;}
			case 4:{int e=newEvent(C,1001,control,T_CONTROL);try{sendToClass(classRef,C,1001,e);}finally{carbon("ReleaseEvent",e);}controlObjectClasses.remove(control);objectFeatures.remove(control);return 0;}
			case 16:{int e=newEvent(C,7,control,T_CONTROL);setParam(e,ostype("cprt"),ostype("cprt"),word(param));
				try{return sendToClass(classRef,C,7,e)==0?eventInt(e,ostype("cprt"),0):0;}finally{carbon("ReleaseEvent",e);}}
			case 19:{Integer f=objectFeatures.get(control);return f==null?0:f;}
			case 22:{int kind=param!=0?9:10;int e=newEvent(C,kind,control,T_CONTROL);try{sendToClass(classRef,C,kind,e);}finally{carbon("ReleaseEvent",e);}return 0;}
			case 27:return 0x206f6b20; // kControlSupportsNewMessages
			case 31:{if(param==0)return PARAM_ERR;int rgn=memory.readLong(param),part=(short)memory.readWord(param+4); // ControlGetRegionRec
				int e=newEvent(C,101,control,T_CONTROL);setParam(e,ostype("cprt"),ostype("cprt"),word(part));setParam(e,ostype("crgn"),T_RGN,longs(rgn));
				try{if(sendToClass(classRef,C,101,e)==0)return NO_ERR;}finally{carbon("ReleaseEvent",e);}
				if(part==-1||part==0){rt.toolbox.windows.setRegion(rgn,bounds[0],bounds[1],bounds[2],bounds[3]);return NO_ERR;}return -30565;} // errInvalidPartCode
			default:{int e=newEvent(C,201,control,T_CONTROL);setParam(e,ostype("cmsg"),T_SHORT,word(message));setParam(e,ostype("cprm"),T_LONG,longs(param));
				try{return sendToClass(classRef,C,201,e)==0?eventInt(e,ostype("crsl"),0):0;}finally{carbon("ReleaseEvent",e);}}
		}
	}
	/** CreateCustomControl's kControlDefObjectClass: a CDEF resource ID registered for the class (0 for an unknown class). */
	private int controlDefinitionId(int classRef) throws M68kException {
		ObjectClass k=objectClasses.get(classRef);if(k==null)return 0;
		if(k.controlDefId==0){int tv=rt.nativeVector("ObjectClassCDEF "+k.id,(r,c)->{int[] a=args(4);ret(objectCdef(classRef,(short)a[0],a[1],(short)a[2],a[3]));});
			k.controlDefId=nextCustomCdef++;rt.toolbox.controls.registerDefinition(k.controlDefId,definitionHandle(rt.newRoutineDescriptor(tv,CONTROL_DEF_PROC_INFO,PpcRuntime.ISA_PPC)));}
		return k.controlDefId;
	}

	private void defineObjectClasses(PpcLibraries l){
		l.register(null,"RegisterToolboxObjectClass",(r,c)->{int[] a=args(7); // (classID, baseClass, numEvents, EventTypeSpec*, handler, userData, *outClassRef)
			String id=cfText(a[0]);if(a[6]!=0)memory.writeLong(a[6],0);
			if(id==null||id.isEmpty()||a[1]!=0&&!objectClasses.containsKey(a[1])||a[2]>0&&a[3]==0){ret(PARAM_ERR);return;}
			for(ObjectClass k:objectClasses.values())if(k.id.equals(id)){ret(PARAM_ERR);return;} // a class ID is registered once
			int ref=rt.allocate(4,4);ObjectClass k=new ObjectClass(id,a[1],a[4],a[5]);
			for(int i=0;i<a[2];i++)k.types.add(((long)memory.readLong(a[3]+8*i)<<32)|(memory.readLong(a[3]+8*i+4)&0xffffffffL));
			objectClasses.put(ref,k);if(a[6]!=0)memory.writeLong(a[6],ref);ret(NO_ERR);});
		l.register(null,"UnregisterToolboxObjectClass",(r,c)->{int ref=arg(0);ObjectClass k=objectClasses.get(ref);if(k==null){ret(PARAM_ERR);return;}
			for(ObjectClass o:objectClasses.values())if(o.base==ref){ret(PARAM_ERR);return;} // still the base of another class
			if(windowObjectClasses.containsValue(ref)||controlObjectClasses.containsValue(ref)){ret(PARAM_ERR);return;} // still in use
			objectClasses.remove(ref);memory.free(ref);ret(NO_ERR);});
		// CarbonLib-private Toolbox instance definitions (the HIObject machinery's precursor): parameters not known, nothing touched
		l.register(null,"InstallTBInstanceDefinition",(r,c)->ret(UNIMP_ERR));
		l.register(null,"InstallTBInstanceDefinitionDirect",(r,c)->ret(UNIMP_ERR));
		l.register(null,"RemoveTBInstanceDefinition",(r,c)->ret(UNIMP_ERR));
		l.register(null,"FindDefinitionFromTBInstance",(r,c)->ret(0));
		l.register(null,"RegisterSystemControlDefinition",(r,c)->ret(UNIMP_ERR)); // private: the system's CDEFs are the Control Manager's own here
	}
}

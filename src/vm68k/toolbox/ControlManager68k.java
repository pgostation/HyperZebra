import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Control Manager for hosted applications: push buttons, check boxes, radio
 * buttons, scroll bars and popup menus drawn natively (Java2D) into the owning
 * window, with real mouse tracking and action procedures. The Appearance
 * Manager's kinds (static and edit text, bevel buttons, tabs, user panes...)
 * are {@link AppearanceControls68k}, Java definitions like the Data Browser's;
 * keyboard focus and the ControlDispatch calls reaching such definitions are
 * kept here ({@link #appearanceCall}).
 */
final class ControlManager68k {
	static final int RECORD_SIZE=296;
	static final int IN_BUTTON=10,IN_CHECK_BOX=11,IN_UP=20,IN_DOWN=21,IN_PAGE_UP=22,IN_PAGE_DOWN=23,IN_THUMB=129;
	private static final Color FRAME=new Color(0x50,0x50,0x50),FILL=new Color(0xf4,0xf4,0xf4),SHADE=new Color(0xd8,0xd8,0xd8),DISABLED=new Color(0xa0,0xa0,0xa0),THUMB=new Color(0xb0,0xb0,0xb0),TRACK=new Color(0xe8,0xe8,0xe8);

	final class Control {final int handle;int window,procId;int popupMenu;
		/** A push button's default ring is on the screen (kControlPushButtonDefaultTag, drawn outside the rectangle). */
		boolean ringDrawn;
		/** A pop-up's title width: NewControl's max as the pop-up CDEF reads it before it replaces max with the item count
		 *  (0 = no title shown: Photoshop 3's unit pop-ups carry "Units 5" titles that are never drawn); negative = the title's own width. */
		int popupTitleWidth=-1;
		/** Locked handle of the program's own CDEF code when the control is not one of the natively drawn kinds. */
		int cdef;
		/** contrlDefProc as last seen: a program that stores another Handle there gets it called ({@link #adoptDefProc}). */
		int defProcSeen;
		/** The standard kind (CDEF 0/1 procID) a program's CDEF hands the control on to, once it has called the System's CDEF:
		 *  tracking then follows that kind (Excel 2.2's CDEF 8 scroll bars are scrollBarProcs underneath). */
		int standardProc=-1;
		/** A definition implemented in Java (the Carbon Data Browser), in place of a CDEF. */
		NativeDefinition nativeDef;
		Control(int handle,int window,int procId){this.handle=handle;this.window=window;this.procId=procId;}}
	/**
	 * A control definition implemented in Java: the Control Manager hands it the CDEF messages - drawCntl, testCntl, the
	 * mouse tracking (autoTrack with the modifiers of the click), key presses and dispCntl. The control is an ordinary
	 * ControlHandle in its window's list, so bounds, visibility, highlighting and disposal work as for any control.
	 */
	interface NativeDefinition {
		/** What {@link #setData}/{@link #getData} answer to leave a tag to the Appearance Manager's own store. */
		int UNHANDLED=Integer.MIN_VALUE;
		/** Draws the control (visible, in a visible window). */
		void draw(int handle) throws M68kException;
		/** The part at a local point inside the control's rectangle (0 = none). */
		int test(int handle,int h,int v) throws M68kException;
		/** Tracks a click at a local point until the button is released; returns the part code. */
		int track(int handle,int h,int v,int modifiers) throws M68kException;
		/** Tracking with TrackControl's action procedure ({@code action}: 0 none, else a ProcPtr/UPP - contrlAction already put
		 *  in for -1). */
		default int track(int handle,int h,int v,int modifiers,int action) throws M68kException {return track(handle,h,v,modifiers);}
		/** A key press sent to the control (HandleControlKey); returns the part code, 0 when not handled. */
		int key(int handle,int keyCode,int charCode,int modifiers) throws M68kException;
		/** The control is being disposed of. */
		void dispose(int handle) throws M68kException;
		/** SetControlData(part, tag, size, data): an OSErr, or {@link #UNHANDLED}. */
		default int setData(int handle,int part,int tag,int size,int data) throws M68kException {return UNHANDLED;}
		/** GetControlData ({@code buffer} 0 for GetControlDataSize): an OSErr with the data's size put in {@code *actualSize}
		 *  (when non-zero), or {@link #UNHANDLED}. */
		default int getData(int handle,int part,int tag,int bufferSize,int buffer,int actualSize) throws M68kException {return UNHANDLED;}
		/** kControlMsgFocus: {@code part} 0 takes the focus away, -1/-2 moves it to the next/previous part, else to that part;
		 *  answers the part now focused, 0 when the control has none (or the focus moved past its last part). */
		default int focus(int handle,int part) throws M68kException {return 0;}
		/** IdleControls: caret blinking, animation. */
		default void idle(int handle) throws M68kException {}
		/** ActivateControl / DeactivateControl (the hilite has been set; the Control Manager redraws afterwards). */
		default void activate(int handle,boolean active) throws M68kException {}
		/** GetControlFeatures bits (kControlSupportsFocus...). */
		default int features(int handle) throws M68kException {return 0;}
		/** GetBestControlRect: {top, left, bottom, right, baseline offset}, or null to answer the control's rectangle. */
		default int[] bestRect(int handle) throws M68kException {return null;}
	}
	// CDEF messages
	private static final int DRAW_CNTL=0,TEST_CNTL=1,INIT_CNTL=3,DISP_CNTL=4,DRAG_CNTL=7,AUTO_TRACK=8;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	final Map<Integer,Control> controls=new HashMap<Integer,Control>();
	/** Controls' own auxiliary records (AuxCtlRec Handles) by ControlHandle, and the default record (owner NIL, the
	 *  standard colours) at the end of the AuxCtlHead ($CD4) list. */
	private final Map<Integer,Integer> auxRecords=new HashMap<Integer,Integer>();private int defaultAux;
	private static final int AUX_CTL_HEAD=0xcd4;
	private final Map<Integer,List<Integer>> byWindow=new HashMap<Integer,List<Integer>>();
	/** The Appearance Manager's standard control kinds (static text, edit text, bevel buttons, tabs...) defined in Java. */
	final AppearanceControls68k appearanceKinds;
	/** The control holding the keyboard focus of each window (SetKeyboardFocus). */
	private final Map<Integer,Integer> focus=new HashMap<Integer,Integer>();

	ControlManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;appearanceKinds=new AppearanceControls68k(tb,this);}

	/** The classic procID an Appearance-era one behaves as: kControlScrollBarProc/LiveProc (384, 386) are scroll bars and
	 *  kControlPopupButtonProc (400) the pop-up menu (same variant bits); the others stay as they are. */
	static int classic(int procId){int kind=procId&0xfff0;return kind==384?16|procId&15:kind==400?1008|procId&15:procId;}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x954:return "NewControl";case 0x9be:return "GetNewControl";case 0x955:return "DisposeControl";case 0x956:return "KillControls";case 0x957:return "ShowControl";case 0x958:return "HideControl";
			case 0x95d:return "HiliteControl";case 0x969:return "DrawControls";case 0x96d:return "Draw1Control";case 0x953:return "UpdateControls";case 0x95b:return "SetCRefCon";case 0x95a:return "GetCRefCon";
			case 0x96b:return "SetCtlAction";case 0x96a:return "GetCtlAction";case 0x809:return "GetCVariant";case 0xa44:return "GetAuxCtl";case 0x96c:return "FindControl";case 0x968:return "TrackControl";
			case 0x966:return "TestControl";case 0x963:return "SetCtlValue";case 0x960:return "GetCtlValue";case 0x964:return "SetCtlMin";case 0x961:return "GetCtlMin";case 0x965:return "SetCtlMax";case 0x962:return "GetCtlMax";
			case 0x959:return "MoveControl";case 0x967:return "DragControl";case 0x95c:return "SizeControl";case 0xa43:return "SetCtlColor";case 0x95f:return "SetCTitle";case 0x95e:return "GetCTitle";
			default:return null;
		}
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		switch(trap){
			case 0x954:return newControl(cpu);
			case 0x9be:{int window=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;memory.writeLong(cpu.a[7],getNewControl(id,window));return true;}
			case 0x955:{int h=pop(cpu);dispose(h,true);return true;}
			case 0x956:{int w=pop(cpu);killControls(w);return true;}
			case 0x957:{int h=pop(cpu);int d=data(h);if(d!=0){memory.writeByte(d+16,0xff);draw(h);}return true;}
			// HideControl of a control that is already invisible does nothing (the ROM tests contrlVis first): Chips & Dips Info
			// hides its invisible CNTL 257 at 0,0,150,16 before moving it out as the scroll bar - the erase cut the first letters of its text
			case 0x958:{int h=pop(cpu);int d=data(h);if(d!=0&&memory.readByte(d+16)!=0){memory.writeByte(d+16,0);erase(h);}return true;}
			case 0x95d:{int part=(short)memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;int d=data(h);if(d!=0){memory.writeByte(d+17,part);draw(h);}return true;}
			case 0x969:{int w=pop(cpu);drawAll(w);return true;}
			case 0x953:{int rgn=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;drawAll(w);return true;}
			case 0x96d:{int h=pop(cpu);draw(h);return true;}
			case 0x95b:{int v=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int d=data(h);if(d!=0)memory.writeLong(d+36,v);return true;}
			case 0x95a:{int h=pop(cpu);int d=data(h);memory.writeLong(cpu.a[7],d==0?0:memory.readLong(d+36));return true;}
			case 0x96b:{int v=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int d=data(h);if(d!=0)memory.writeLong(d+32,v);return true;}
			case 0x96a:{int h=pop(cpu);int d=data(h);memory.writeLong(cpu.a[7],d==0?0:memory.readLong(d+32));return true;}
			case 0x809:{int h=pop(cpu);Control c=controls.get(h);memory.writeWord(cpu.a[7],c==null?0:c.procId&15);return true;}
			case 0xa44:{ // GetAuxCtl(theControl, VAR acHndl): TRUE with the control's own AuxCtlRec (SetCtlColor), else FALSE with the
				// default one - the VAR used to be left unset and callers dereferenced garbage
				int result=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;Integer own=auxRecords.get(h);
				if(result!=0)memory.writeLong(result,own!=null?own:defaultAuxRecord());tb.writeBoolean(cpu.a[7],own!=null);return true;}
			case 0x96c:return findControl(cpu);
			case 0x968:return trackControl(cpu);
			case 0x966:{int v=(short)memory.readWord(cpu.a[7]),h=(short)memory.readWord(cpu.a[7]+2),c=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeWord(cpu.a[7],partAt(c,h,v));return true;}
			case 0x963:{int v=(short)memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;setValue(h,v);return true;}
			case 0x960:{int h=pop(cpu);int d=data(h);memory.writeWord(cpu.a[7],d==0?0:memory.readWord(d+18));return true;}
			case 0x964:case 0x965:{int v=(short)memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;setLimit(h,trap==0x964,v);return true;}
			case 0x961:case 0x962:{int h=pop(cpu);int d=data(h);memory.writeWord(cpu.a[7],d==0?0:memory.readWord(d+(trap==0x961?20:22)));return true;}
			case 0x959:{int v=(short)memory.readWord(cpu.a[7]),h=(short)memory.readWord(cpu.a[7]+2),c=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;moveTo(c,h,v);return true;}
			case 0x967:{ // DragControl(theControl, startPt, limitRect, slopRect, axis): the control's outline dragged like DragGrayRgn, then MoveControl
				int axis=(short)memory.readWord(cpu.a[7]),slop=memory.readLong(cpu.a[7]+2),limit=memory.readLong(cpu.a[7]+6),start=memory.readLong(cpu.a[7]+10),h=memory.readLong(cpu.a[7]+14);cpu.a[7]+=18;
				int d=data(h);if(d==0)return true;
				int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
				int region=tb.api.createHandle(HostQuickDraw68k.regionBytes(top,left,bottom,right));
				int moved;try{moved=tb.windows.dragRegion(region,start,limit,slop,axis,0);}finally{tb.api.disposeHandle(region);}
				if(moved!=0x80008000&&moved!=0)moveTo(h,left+(short)moved,top+(moved>>16));
				return true;}
			case 0x95c:{int ht=(short)memory.readWord(cpu.a[7]),w=(short)memory.readWord(cpu.a[7]+2),c=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int d=data(c);if(d!=0){erase(c);int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10);writeRect(d+8,top,left,top+ht,left+w);draw(c);}return true;}
			case 0xa43:{ // SetCtlColor(theControl, newColorTable): the control gets an AuxCtlRec of its own holding the table
				int table=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
				if(h!=0&&controls.containsKey(h)){Integer own=auxRecords.get(h);int aux=own!=null?own:newAuxRecord(h);memory.writeLong(memory.readLong(aux)+8,table);}
				return true;}
			case 0x95f:{int title=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int d=data(h);if(d!=0){erase(h);byte[] t=tb.readPascalBytes(title);memory.fill(d+40,256,0);memory.load(d+40,t);draw(h);}return true;}
			case 0x95e:{int title=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int d=data(h);if(title!=0){memory.writeByte(title,0);if(d!=0)memory.copy(d+40,title,Math.min(256,1+memory.readByte(d+40)));}return true;}
			default:return false;
		}
	}

	private int pop(M68kCpu cpu) throws M68kCpuException {int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return h;}
	int data(int handle) throws M68kCpuException {return controls.containsKey(handle)?memory.readLong(handle):0;}

	// ------------------------------------------------------------------ creation

	private boolean newControl(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7],refCon=memory.readLong(sp),procId=(short)memory.readWord(sp+4),max=(short)memory.readWord(sp+6),min=(short)memory.readWord(sp+8),value=(short)memory.readWord(sp+10);
		boolean visible=memory.readByte(sp+12)!=0;int title=memory.readLong(sp+14),bounds=memory.readLong(sp+18),window=memory.readLong(sp+22);cpu.a[7]+=26;
		if(bounds==0){memory.writeLong(cpu.a[7],0);return true;}
		// the clock only has flag bits 0 (display only) and 1 (live): a program passing others (HyperStudio's Stack To Go asks for
		// procID 240 with 0x0B / 0x86 for its own text CDEF 15, which only the full application carries) wants a CDEF that is not
		// there, and NewControl fails like it does without one instead of drawing the time into every text object
		if((procId>>4)==AppearanceControls68k.CLOCK&&(value&~3)!=0&&!registeredDefs.containsKey(procId>>4)&&tb.resources.find(0x43444546,procId>>4)==null){memory.writeLong(cpu.a[7],0);return true;}
		int handle=create(window,(short)memory.readWord(bounds),(short)memory.readWord(bounds+2),(short)memory.readWord(bounds+4),(short)memory.readWord(bounds+6),tb.readPascalBytes(title),visible,value,min,max,procId,refCon);
		memory.writeLong(cpu.a[7],handle);return true;
	}

	/**
	 * GetNewControl: the 'CNTL' and, when there is one, the 'cctb' of the same ID as the control's own colour table (its
	 * AuxCtlRec). The Dialog Manager makes a DITL's resource control items with it too: CodeWarrior 5's Find window check boxes
	 * (its own CDEF) take their background from their cctb's body entry (red = a palette index) and were erased white without it.
	 */
	int getNewControl(int id,int window) throws M68kException {
		int h=fromResource(id,window);
		ResourceFile68k.Resource cctb=h==0?null:tb.resources.find(0x63637462,id);
		if(cctb!=null){int table=tb.api.createHandle(tb.resources.data(cctb));int aux=newAuxRecord(h);memory.writeLong(memory.readLong(aux)+8,table);}
		return h;
	}
	int fromResource(int id,int window) throws M68kException {
		ResourceFile68k.Resource res=tb.resources.find(0x434e544c,id);
		if(res==null||res.length()<23)return 0;byte[] d=tb.resources.data(res);
		int n=Math.min(d[22]&0xff,d.length-23);byte[] title=new byte[n+1];title[0]=(byte)n;System.arraycopy(d,23,title,1,n);
		return create(window,(short)MacApp68k.u2(d,0),(short)MacApp68k.u2(d,2),(short)MacApp68k.u2(d,4),(short)MacApp68k.u2(d,6),title,MacApp68k.u2(d,10)!=0,(short)MacApp68k.u2(d,8),(short)MacApp68k.u2(d,14),(short)MacApp68k.u2(d,12),(short)MacApp68k.u2(d,16),MacApp68k.u4(d,18));
	}

	int create(int window,int top,int left,int bottom,int right,byte[] title,boolean visible,int value,int min,int max,int procId,int refCon) throws M68kException {
		return create(window,top,left,bottom,right,title,visible,value,min,max,procId,refCon,null);
	}
	/** A control whose definition is implemented in Java ({@code definition}; null = the usual CDEF lookup by procID). */
	int create(int window,int top,int left,int bottom,int right,byte[] title,boolean visible,int value,int min,int max,int procId,int refCon,NativeDefinition definition) throws M68kException {
		byte[] record=new byte[RECORD_SIZE];
		putLong(record,4,window);putWord(record,8,top);putWord(record,10,left);putWord(record,12,bottom);putWord(record,14,right);
		// The initial value is stored as passed, outside min..max too, as the ROM's NewControl does (only SetCtlValue pins it):
		// a CDEF may take it as a parameter at initCntl - an Appearance kind's 'tab#' or 'PICT' (iTunes' Preferences tabs, value
		// 1340, max 5, had no names when clamped), Alias Sketch's tool buttons their 'cicn' ID (20001 with min = max = 0: every
		// tool showed the stop icon when it came out as 0).
		record[16]=(byte)(visible?0xff:0);putWord(record,18,value);putWord(record,20,min);putWord(record,22,max);putLong(record,36,refCon);
		int n=Math.min(255,title==null||title.length==0?0:title[0]&255);record[40]=(byte)n;if(title!=null)System.arraycopy(title,1,record,41,Math.min(n,title.length-1));
		int handle=tb.api.createHandle(record);int data=memory.readLong(handle);
		Control c=new Control(handle,window,procId);c.nativeDef=definition;controls.put(handle,c);
		if((classic(procId)&0xfff0)==1008){ // popup: min = menu ID, value = item; contrlData -> {menuHandle, menuID}
			// like the popup CDEF: contrlMin is the 'MENU' RESOURCE id (GetMenu); several resources may carry the same menuID
			// field (multiple MENU resources may share one menu id), so a lookup by menu id only serves menus built with NewMenu
			// GetMenu(resourceID): MenuHandle - the result slot goes under the argument (without it the Handle landed on the
			// caller's top of stack: VT102 Tool's AppendDITL returned to its own menu Handle)
			c.popupMenu=min;c.popupTitleWidth=max;int savedSp=tb.cpu.a[7];tb.cpu.a[7]-=6;memory.writeLong(tb.cpu.a[7]+2,0);memory.writeWord(tb.cpu.a[7],min);tb.menus.handle(tb.cpu,0x9bf);int menuHandle=memory.readLong(savedSp-4);tb.cpu.a[7]=savedSp;
			if(menuHandle==0)menuHandle=tb.menus.anyMenuHandle(min);
			// popupUseAddResMenu (variation 4): the refCon names a resource type whose names are appended (a font or sound pop-up)
			if((procId&4)!=0&&menuHandle!=0){int type=memory.readLong(data+36);if(type!=0)tb.menus.appendResourceNames(menuHandle,type);}
			int priv=tb.api.createHandle(new byte[6]);int pd=memory.readLong(priv);memory.writeLong(pd,menuHandle);memory.writeWord(pd+4,min);memory.writeLong(data+28,priv);
			// with an empty menu a value of 0 stays 0: REALbasic makes its PopupMenus so (rows come later) and reads ListIndex as
			// value-1, indexing its rows with it - 1 made a fresh empty pop-up's Text raise OutOfBounds (GameBox's details stopped
			// updating). Fetch 4 passes 1 and hands the menu over later with SetControlData('mhan'): that keeps 1.
			MenuManager68k.Menu m=tb.menus.read(menuHandle);int count=m==null?0:m.items.size();putWord(record,20,1);memory.writeWord(data+20,1);memory.writeWord(data+22,count);memory.writeWord(data+18,count==0?(value<=0?0:1):clamp(value,1,count));
		}
		List<Integer> list=byWindow.get(window);if(list==null){list=new ArrayList<Integer>();byWindow.put(window,list);}list.add(0,handle);
		refreshLinks(window,list);
		if(definition==null)attachDefProc(c,data);
		c.defProcSeen=memory.readLong(data+24);
		if(c.cdef==0&&c.nativeDef==null&&AppearanceControls68k.handles(procId))c.nativeDef=appearanceKinds.attach(handle,procId); // no CDEF resource of that ID: the Java one
		if(visible)draw(handle);
		return handle;
	}

	/** Buttons, check boxes, radio buttons (CDEF 0), scroll bars (1, 24) and pop-ups (63, 25) are drawn natively; any other procID whose
	 *  CDEF resource the program carries is run as guest code (custom text panes...); the Appearance kinds without one are Java definitions. */
	/** CDEFs a Carbon program registered by resource ID (RegisterControlDefinition): a Handle whose master pointer is its ControlDefUPP. */
	private final java.util.Map<Integer,Integer> registeredDefs=new java.util.HashMap<Integer,Integer>();
	void registerDefinition(int id,int handle){if(handle==0)registeredDefs.remove(id);else registeredDefs.put(id,handle);}
	/** Debug aid: -Dhz68k.guestcdef=true runs the CDEF 0/1/63/24/25 the resource chain finds as guest code too (the System's
	 *  stubs come back to {@link #standardCdef}): in the Kaleidoscope control panel's process, whose file is open, its own CDEFs
	 *  draw - the reference for {@link KaleidoscopeScheme68k}. */
	static final boolean GUEST_CDEFS=Boolean.getBoolean("hz68k.guestcdef");
	/** Debug aid: -Dhz68k.nativecdef=5,8 draws those CDEF IDs natively although the resource chain has them (the Kaleidoscope
	 *  panel's own CDEF 5 would otherwise draw every progress bar in its process). */
	private static final java.util.Set<Integer> NATIVE_CDEFS=new java.util.HashSet<Integer>();
	static{for(String id:System.getProperty("hz68k.nativecdef","").split(","))if(!id.trim().isEmpty())try{NATIVE_CDEFS.add(Integer.parseInt(id.trim()));}catch(NumberFormatException ignored){}}
	private void attachDefProc(Control c,int data) throws M68kException {
		int id=c.procId>>4;
		Integer registered=registeredDefs.get(id);
		if(registered!=null){c.cdef=registered;memory.writeLong(data+24,registered);callDefProc(c,INIT_CNTL,0);return;} // FutureBASIC's Carbon runtime registers its custom controls
		if(NATIVE_CDEFS.contains(id))return;
		ResourceFile68k.Resource res=tb.resources.find(0x43444546,id); // 'CDEF'
		if((id==0||id==1||id==63||id==24||id==25)&&!(GUEST_CDEFS&&res!=null)){ // natively drawn (24, 25: the Appearance scroll bar and pop-up button): contrlDefProc still carries the Handle a program may read (the System stub)
			if(res!=null){int h=tb.resources.handleFor(res);if(h!=0)memory.writeLong(data+24,h);}
			return;
		}
		if(res==null)return;
		int h=tb.resources.handleFor(res);if(h==0||memory.readLong(h)==0)return;
		tb.api.setHandleState(h,tb.api.handleState(h)|0x80);
		c.cdef=h;memory.writeLong(data+24,h);
		callDefProc(c,INIT_CNTL,0);
	}

	/**
	 * A program may store its own definition in contrlDefProc after making the control: MacWeb's NewCallbackControl does
	 * GetNewControl with procID 8192 (a CDEF 512 it does not carry) and then puts a 6-byte JMP stub Handle there - from then
	 * on the Control Manager calls that Handle like any CDEF (no initCntl: the control was made already). Its progress bars
	 * showed as push buttons titled "Progress Bar".
	 */
	private void adoptDefProc(Control c,int data) throws M68kCpuException {
		if(data==0)return;
		int h=memory.readLong(data+24);if(h==c.defProcSeen)return;
		c.defProcSeen=h;
		if(h==0||h==c.cdef||c.nativeDef!=null||memory.readLong(h)==0)return;
		c.cdef=h;c.standardProc=-1;
	}

	/**
	 * The window a control draws in: its record's contrlOwner when that is another window, else the window it was created in.
	 * MacApp 3 builds its controls in a hidden work window and points contrlOwner at the window being drawn only around
	 * Draw1Control (Blackjack Solitaire's Game Setup dialog came up without its buttons and radio buttons).
	 */
	private int owner(Control c,int data) throws M68kCpuException {
		int o=data==0?0:memory.readLong(data+4);
		return o!=0&&o!=c.window&&tb.windows.isWindow(o)?o:c.window;
	}

	/** pascal long CDEF(short varCode, ControlHandle theControl, short message, long param), drawing in the owner's port. */
	private int callDefProc(Control c,int message,int param) throws M68kException {
		if(c.cdef==0)return 0;
		int entry=memory.readLong(c.cdef);if(entry==0)return 0;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		int savedPort=tb.api.quickDraw().currentPortAddress(),window=tb.quickDraw.controlPort(owner(c,data(c.handle)));
		try{
			if(window!=0&&savedPort!=window)setPort(cpu,window);
			cpu.a[7]-=4;memory.writeLong(cpu.a[7],0);cpu.pushWord(c.procId&15);cpu.pushLong(c.handle);cpu.pushWord(message);cpu.pushLong(param);
			cpu.callPascal(entry,12);
			return memory.readLong(cpu.a[7]);
		}finally{
			if(window!=0&&savedPort!=window&&savedPort!=0)setPort(cpu,savedPort);
			System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);
		}
	}
	/** EraseRect in the window's port (its background colour and pattern, as the CDEF's own EraseRect). */
	private void eraseInPort(int window,int top,int left,int bottom,int right) throws M68kCpuException {
		M68kCpu cpu=tb.cpu;QuickDraw68k qd=tb.api.quickDraw();int saved=qd.currentPortAddress(),port=tb.quickDraw.controlPort(window),sp=cpu.a[7];
		try{
			if(saved!=port)setPort(cpu,port);
			cpu.a[7]-=8;int rect=cpu.a[7];writeRect(rect,top,left,bottom,right);cpu.pushLong(rect);qd.handle(cpu,0x8a3);
		}catch(M68kCpuException e){throw e;}catch(M68kException ignored){}
		finally{cpu.a[7]=sp;if(saved!=port&&saved!=0)try{setPort(cpu,saved);}catch(M68kException ignored){}}
	}
	private void setPort(M68kCpu cpu,int port) throws M68kException {int saved=cpu.a[7];cpu.pushLong(port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}
	/** Runs a CDEF message from code that only declares CPU exceptions. */
	private int defProc(Control c,int message,int param) throws M68kCpuException {
		try{return callDefProc(c,message,param);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
	}

	/** MoveControl: moves the control's top-left corner to (h,v) in window coordinates. */
	/** MoveControl and SizeControl in one: the old rectangle erased, the new one drawn (the List Manager's LSize). */
	void setBounds(int handle,int top,int left,int bottom,int right) throws M68kCpuException {
		int d=data(handle);if(d==0)return;
		if((short)memory.readWord(d+8)==top&&(short)memory.readWord(d+10)==left&&(short)memory.readWord(d+12)==bottom&&(short)memory.readWord(d+14)==right)return;
		erase(handle);writeRect(d+8,top,left,bottom,right);draw(handle);
	}
	void moveTo(int handle,int h,int v) throws M68kCpuException {
		int d=data(handle);if(d==0)return;
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10);if(top==v&&left==h)return;
		erase(handle);int w=(short)memory.readWord(d+14)-left,ht=(short)memory.readWord(d+12)-top;writeRect(d+8,v,h,v+ht,h+w);draw(handle);
	}

	/** The default AuxCtlRec: acNext NIL, acOwner NIL, acCTable a 'cctb' of the standard colours (frame, body, text, thumb). */
	private int defaultAuxRecord() throws M68kCpuException {
		if(defaultAux!=0)return defaultAux;
		byte[] cctb=new byte[8+4*8];cctb[7]=3; // ccSeed 0, ccRider 0, ctSize 3
		int[][] rgb={{0,0,0},{0xffff,0xffff,0xffff},{0,0,0},{0xffff,0xffff,0xffff}};
		for(int i=0;i<4;i++){int o=8+i*8;cctb[o+1]=(byte)i;for(int c=0;c<3;c++){cctb[o+2+c*2]=(byte)(rgb[i][c]>>8);cctb[o+3+c*2]=(byte)rgb[i][c];}}
		int table=tb.api.createHandle(cctb);
		byte[] rec=new byte[22];rec[8]=(byte)(table>>>24);rec[9]=(byte)(table>>>16);rec[10]=(byte)(table>>>8);rec[11]=(byte)table;
		defaultAux=tb.api.createHandle(rec);if(memory.readLong(AUX_CTL_HEAD)==0)memory.writeLong(AUX_CTL_HEAD,defaultAux);
		return defaultAux;
	}
	/** A control's own AuxCtlRec, put at the head of the AuxCtlHead list (acOwner = the control). */
	private int newAuxRecord(int control) throws M68kCpuException {
		int fallback=defaultAuxRecord();
		int aux=tb.api.createHandle(new byte[22]);int p=memory.readLong(aux);
		int head=memory.readLong(AUX_CTL_HEAD);memory.writeLong(p,head!=0?head:fallback);memory.writeLong(p+4,control);
		memory.writeLong(p+8,memory.readLong(memory.readLong(fallback)+8));memory.writeLong(AUX_CTL_HEAD,aux);
		auxRecords.put(control,aux);return aux;
	}
	private void unlinkAuxRecord(int aux) throws M68kCpuException {
		int next=memory.readLong(memory.readLong(aux));
		if(memory.readLong(AUX_CTL_HEAD)==aux){memory.writeLong(AUX_CTL_HEAD,next);return;}
		for(int r=memory.readLong(AUX_CTL_HEAD),guard=0;r!=0&&guard<100000;guard++){int p=memory.readLong(r);if(p==0)return;if(memory.readLong(p)==aux){memory.writeLong(p,next);return;}r=memory.readLong(p);}
	}

	void dispose(int handle,boolean redraw) throws M68kCpuException {
		Control c=controls.remove(handle);if(c==null)return;
		if(tb.appearance!=null)tb.appearance.forget(handle);
		focus.values().removeIf(f->f==handle);
		Integer aux=auxRecords.remove(handle);if(aux!=null){unlinkAuxRecord(aux);tb.api.disposeHandle(aux);}
		adoptDefProc(c,data(handle));
		if(c.cdef!=0)try{defProc(c,DISP_CNTL,0);}catch(RuntimeException ignored){}
		if(c.nativeDef!=null)try{c.nativeDef.dispose(handle);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
		if(redraw)try{erase(handle);}catch(M68kCpuException ignored){}
		List<Integer> list=byWindow.get(c.window);if(list!=null){list.remove((Integer)handle);refreshLinks(c.window,list);if(list.isEmpty())byWindow.remove(c.window);}
		disposeControlData(c,handle);
		tb.api.disposeHandleAndFree(handle);
	}
	/** A control moved into another window's control list, owned by it (InsertDialogItem of a control made in another window:
	 *  Acrobat 5 builds its print dialog items in a nib window, moves them into the print dialog and disposes the nib window). */
	void moveToWindow(int handle,int window) throws M68kCpuException {
		Control c=controls.get(handle);int d=data(handle);if(c==null||d==0||c.window==window)return;
		List<Integer> old=byWindow.get(c.window);if(old!=null){old.remove((Integer)handle);refreshLinks(c.window,old);if(old.isEmpty())byWindow.remove(c.window);}
		c.window=window;memory.writeLong(d+4,window);
		List<Integer> list=byWindow.get(window);if(list==null){list=new ArrayList<Integer>();byWindow.put(window,list);}list.add(0,handle);refreshLinks(window,list);
	}
	void killControls(int window) throws M68kCpuException {
		List<Integer> list=byWindow.remove(window);if(list==null)return;
		for(int h:new ArrayList<Integer>(list))dispose(h,false);
		if(window!=0)memory.writeLong(window+140,0);
	}
	/** Releases storage owned by a control definition. The system pop-up CDEF obtains its MENU resource at init time;
	 *  keeping that shared resource loaded after the control dies also keeps every item the application appended to it. */
	private void disposeControlData(Control c,int handle) throws M68kCpuException {
		if((classic(c.procId)&0xfff0)!=1008)return;
		int data=memory.readLong(handle),priv=data==0?0:memory.readLong(data+28);
		if(priv==0)return;
		int pd=memory.readLong(priv),menuHandle=pd==0?0:memory.readLong(pd);
		if(menuHandle!=0&&tb.resources.isResourceHandle(menuHandle)){
			tb.menus.resourceReleased(menuHandle);
			tb.resources.release(menuHandle);
		}
		tb.api.disposeHandleAndFree(priv);
	}
	private void refreshLinks(int window,List<Integer> list) throws M68kCpuException {
		for(int i=0;i<list.size();i++){int d=memory.readLong(list.get(i));if(d!=0)memory.writeLong(d,i+1<list.size()?list.get(i+1):0);}
		if(window!=0)memory.writeLong(window+140,list.isEmpty()?0:list.get(0));
	}
	/** A classic pop-up button (popupMenuProc, or the Appearance kControlPopupButtonProc it stands for). */
	boolean isPopup(int handle){Control c=controls.get(handle);return c!=null&&c.nativeDef==null&&(classic(c.procId)&0xfff0)==1008;}
	/** A pop-up's menu (popupPrivateData.mHandle), 0 for none or not a pop-up. */
	int popupMenuHandle(int handle) throws M68kCpuException {
		if(!isPopup(handle))return 0;int d=data(handle);int priv=d==0?0:memory.readLong(d+28);return priv==0||memory.readLong(priv)==0?0:memory.readLong(memory.readLong(priv));
	}
	/**
	 * SetControlData kControlPopupButtonMenuHandleTag / MenuIDTag on a pop-up: popupPrivateData {mHandle, mID} gets the menu and
	 * the range follows its items, as the pop-up CDEF does. Fetch 4 makes its folder and format pop-ups with NewControl (menu ID
	 * -12345, no menu) and hands them a NewMenu this way: the private data kept the empty menu, so the buttons showed no text.
	 */
	boolean setPopupMenu(int handle,int menu,int id) throws M68kException {
		if(!isPopup(handle))return false;int d=data(handle);int priv=d==0?0:memory.readLong(d+28);
		if(priv==0||memory.readLong(priv)==0)return false;
		int pd=memory.readLong(priv);memory.writeLong(pd,menu);memory.writeWord(pd+4,id);
		MenuManager68k.Menu m=tb.menus.read(menu);int n=m==null?0:m.items.size();
		memory.writeWord(d+20,1);memory.writeWord(d+22,n);
		int value=(short)memory.readWord(d+18);if(n>0&&value>n)memory.writeWord(d+18,n);
		return true;
	}
	/** The Java definition of a control, null for the others. */
	NativeDefinition nativeDefinition(int handle){Control c=controls.get(handle);return c==null?null:c.nativeDef;}
	/** HandleControlKey for a control with a Java definition; -1 for the others (they take no keys here). */
	int key(int handle,int keyCode,int charCode,int modifiers) throws M68kException {
		Control c=controls.get(handle);if(c==null||c.nativeDef==null)return -1;
		int d=data(handle);if(d==0||memory.readByte(d+16)==0||memory.readByte(d+17)==255)return 0;
		return c.nativeDef.key(handle,keyCode,charCode,modifiers);
	}
	/** The window a control draws in (its owner, see {@link #owner}). */
	int ownerWindow(int handle) throws M68kCpuException {Control c=controls.get(handle);return c==null?0:owner(c,data(handle));}
	/** The controls FindControl sees in {@code window}: those whose record's contrlOwner is that window. Cyberdog 2.0 builds
	 *  its controls in a 1x1 hidden work window and then points contrlOwner at the window they belong in for good (its
	 *  Preferences list scroll bar could not be clicked); controls moved away from {@code window} are not in it any more. */
	List<Integer> controlsOwnedBy(int window) throws M68kCpuException {
		List<Integer> list=new ArrayList<Integer>();
		for(int handle:controlsOf(window)){Control c=controls.get(handle);if(c==null||owner(c,data(handle))==window)list.add(handle);}
		for(Control c:new ArrayList<Control>(controls.values()))if(c.window!=window&&owner(c,data(c.handle))==window)list.add(c.handle);
		return list;
	}
	List<Integer> controlsOf(int window){List<Integer> list=byWindow.get(window);return list==null?new ArrayList<Integer>():new ArrayList<Integer>(list);}

	// ------------------------------------------------------------------ Appearance Manager calls of Java-defined controls

	private static final int FONT_TAG=0x666f6e74; // kControlFontStyleTag 'font'
	private static final int ERR_CONTROL_DOESNT_SUPPORT_FOCUS=-30582,ERR_UNKNOWN_CONTROL=-30584,ERR_COULDNT_SET_FOCUS=-30585,ERR_CONTROL_HIDDEN_OR_DISABLED=-30592;
	private NativeDefinition def(int handle){Control c=controls.get(handle);return c==null?null:c.nativeDef;}

	/**
	 * ControlDispatch ($AA73) selectors for controls defined in Java: the Appearance Manager offers each call here first and
	 * keeps what this declines (false: nothing popped) - data tags a definition leaves UNHANDLED stay in its own store.
	 * Keyboard focus (Get/Set/Advance/Reverse/ClearKeyboardFocus) and IdleControls are answered here for every window.
	 */
	boolean appearanceCall(M68kCpu cpu,int selector) throws M68kException {
		int sp=cpu.a[7];
		switch(selector){
			case 0x07:case 0x08:{int h=memory.readLong(sp);NativeDefinition n=def(h);if(n==null)return false;cpu.a[7]+=4; // Activate/DeactivateControl
				int d=data(h);boolean on=selector==7;
				if(d!=0&&(memory.readByte(d+17)!=255)!=on){memory.writeByte(d+17,on?0:255);n.activate(h,on);draw(h);}
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x0b:{int h=memory.readLong(sp+6);if(def(h)==null)return false; // HandleControlKey(control, keyCode, charCode, modifiers)
				int mods=memory.readWord(sp),ch=memory.readWord(sp+2),code=memory.readWord(sp+4);cpu.a[7]+=10;memory.writeWord(cpu.a[7],Math.max(0,key(h,code,ch,mods)));return true;}
			case 0x0c:{int w=memory.readLong(sp);cpu.a[7]+=4;idleControls(w);return true;} // IdleControls
			case 0x0d:{int out=memory.readLong(sp),w=memory.readLong(sp+4);cpu.a[7]+=8;if(out!=0)memory.writeLong(out,keyboardFocus(w));memory.writeWord(cpu.a[7],0);return true;} // GetKeyboardFocus
			case 0x0e:{int part=(short)memory.readWord(sp),h=memory.readLong(sp+2),w=memory.readLong(sp+6);cpu.a[7]+=10;memory.writeWord(cpu.a[7],setKeyboardFocus(w,h,part));return true;} // SetKeyboardFocus
			case 0x0f:case 0x10:{int w=memory.readLong(sp);cpu.a[7]+=4;memory.writeWord(cpu.a[7],advanceKeyboardFocus(w,selector==0x10));return true;} // Advance/ReverseKeyboardFocus
			case 0x19:{int w=memory.readLong(sp);cpu.a[7]+=4;memory.writeWord(cpu.a[7],setKeyboardFocus(w,0,0));return true;} // ClearKeyboardFocus
			case 0x11:{int out=memory.readLong(sp),h=memory.readLong(sp+4);NativeDefinition n=def(h);if(n==null)return false; // GetControlFeatures
				cpu.a[7]+=8;if(out!=0)memory.writeLong(out,n.features(h));memory.writeWord(cpu.a[7],0);return true;}
			case 0x12:{int ptr=memory.readLong(sp),size=memory.readLong(sp+4),tag=memory.readLong(sp+8),part=(short)memory.readWord(sp+12),h=memory.readLong(sp+14); // SetControlData(control, part, tag, size, data)
				NativeDefinition n=def(h);if(n==null)return false;int err=n.setData(h,part,tag,size,ptr);if(err==NativeDefinition.UNHANDLED)return false;
				cpu.a[7]+=18;memory.writeWord(cpu.a[7],err);return true;}
			case 0x13:{int actual=memory.readLong(sp),buf=memory.readLong(sp+4),size=memory.readLong(sp+8),tag=memory.readLong(sp+12),part=(short)memory.readWord(sp+16),h=memory.readLong(sp+18); // GetControlData
				NativeDefinition n=def(h);if(n==null)return false;int err=n.getData(h,part,tag,size,buf,actual);if(err==NativeDefinition.UNHANDLED)return false;
				cpu.a[7]+=22;memory.writeWord(cpu.a[7],err);return true;}
			case 0x14:{int actual=memory.readLong(sp),tag=memory.readLong(sp+4),part=(short)memory.readWord(sp+8),h=memory.readLong(sp+10); // GetControlDataSize
				NativeDefinition n=def(h);if(n==null)return false;int err=n.getData(h,part,tag,0,0,actual);if(err==NativeDefinition.UNHANDLED)return false;
				cpu.a[7]+=14;memory.writeWord(cpu.a[7],err);return true;}
			case 0x1b:{int base=memory.readLong(sp),rect=memory.readLong(sp+4),h=memory.readLong(sp+8); // GetBestControlRect(control, *rect, *baselineOffset)
				NativeDefinition n=def(h);int[] r=n==null?null:n.bestRect(h);if(r==null)return false;
				cpu.a[7]+=12;if(rect!=0)writeRect(rect,r[0],r[1],r[2],r[3]);if(base!=0)memory.writeWord(base,r[4]);memory.writeWord(cpu.a[7],0);return true;}
			case 0x1c:{int style=memory.readLong(sp),h=memory.readLong(sp+4);NativeDefinition n=def(h);if(n==null)return false; // SetControlFontStyle: the 'font' tag
				int err=n.setData(h,0,FONT_TAG,style==0?0:24,style);if(err==NativeDefinition.UNHANDLED)return false;cpu.a[7]+=8;memory.writeWord(cpu.a[7],err);return true;}
			default:return false;
		}
	}

	/** IdleControls: every Java-defined control of the window that is shown gets its idle time. */
	void idleControls(int window) throws M68kException {
		for(int h:controlsOf(window)){NativeDefinition n=def(h);int d=data(h);if(n!=null&&d!=0&&memory.readByte(d+16)!=0)n.idle(h);}
	}
	/** The control holding the keyboard focus of {@code window}, 0 for none. */
	int keyboardFocus(int window){Integer f=focus.get(window);return f==null||!controls.containsKey(f)?0:f;}
	private boolean focusable(int handle) throws M68kException {
		int d=data(handle);NativeDefinition n=def(handle);
		return d!=0&&n!=null&&memory.readByte(d+16)!=0&&memory.readByte(d+17)!=255&&(n.features(handle)&4)!=0; // visible, active, kControlSupportsFocus
	}
	/** SetKeyboardFocus: control 0 or part 0 (kControlFocusNoPart) clears the window's focus; answers an OSErr. */
	int setKeyboardFocus(int window,int control,int part) throws M68kException {
		int old=keyboardFocus(window);
		if(control==0||part==0){focus.remove(window);if(old!=0){NativeDefinition o=def(old);if(o!=null)o.focus(old,0);}return 0;}
		NativeDefinition n=def(control);if(!controls.containsKey(control))return ERR_UNKNOWN_CONTROL;
		if(n==null||(n.features(control)&4)==0)return ERR_CONTROL_DOESNT_SUPPORT_FOCUS;
		if(!focusable(control))return ERR_CONTROL_HIDDEN_OR_DISABLED;
		if(old!=0&&old!=control){focus.remove(window);NativeDefinition o=def(old);if(o!=null)o.focus(old,0);}
		if(n.focus(control,part)==0){focus.remove(window);return ERR_COULDNT_SET_FOCUS;}
		focus.put(window,control);return 0;
	}
	/** Advance/ReverseKeyboardFocus: the focused control's next (previous) part, else the next focusable control in creation
	 *  order (the reverse for Reverse...), wrapping around. */
	int advanceKeyboardFocus(int window,boolean reverse) throws M68kException {
		List<Integer> order=controlsOf(window);if(!reverse)java.util.Collections.reverse(order); // the window's list keeps the newest first
		int old=keyboardFocus(window),part=reverse?-2:-1;
		if(old!=0){NativeDefinition o=def(old);if(o!=null&&o.focus(old,part)!=0)return 0;focus.remove(window);}
		int start=old==0?0:order.indexOf(old)+1;
		for(int i=0;i<order.size();i++){int h=order.get((start+i)%order.size());if(h==old||!focusable(h))continue;
			if(def(h).focus(h,part)!=0){focus.put(window,h);return 0;}}
		if(old!=0&&focusable(old)&&def(old).focus(old,part)!=0){focus.put(window,old);return 0;} // the only one: round to its first part again
		return old==0?ERR_COULDNT_SET_FOCUS:0;
	}

	/**
	 * A tab control drawn by a program's (or Kaleidoscope's) CDEF 8: the Appearance Control Manager makes the tab clicked the
	 * control's value when the tracking ends there. Kaleidoscope's panel reads it in hitDev to switch its pane; left at the
	 * old tab, the pane stayed as the CDEF had erased it.
	 */
	private void selectTab(Control c,int handle,int part) throws M68kCpuException {
		if(c.cdef==0||(c.procId>>4)!=AppearanceControls68k.TABS||part<1)return;
		int d=data(handle);if(d==0||part>(short)memory.readWord(d+22)||part==(short)memory.readWord(d+18))return;
		setValue(handle,part);
	}

	// ------------------------------------------------------------------ values

	void setValue(int handle,int value) throws M68kCpuException {
		int d=data(handle);if(d==0)return;
		// pinned as the ROM does - raised to the minimum, then lowered to the maximum - so with max < min the maximum wins:
		// REALbasic sets a new PopupMenu's ListIndex to 0 (value 1) before it has rows (min 1, max 0); the value stays 0 and
		// ListIndex -1, and Text answers "" (Art Directors Toolkit 3 read Text of an empty pop-up: "Failed Assertion array.cpp:73")
		int min=(short)memory.readWord(d+20),max=(short)memory.readWord(d+22);
		int clamped=Math.min(Math.max(value,min),max);
		memory.writeWord(d+18,clamped);
		// the ROM redraws a value change as drawCntl part 129 (indicator only): a check box or radio button gets its box drawn
		// again, not its whole rectangle - Resorcerer draws attribute icons beside title-less check boxes, which a full redraw erased
		Control c=controls.get(handle);
		if(c!=null&&c.cdef==0&&c.nativeDef==null&&(classic(c.procId)&0xfff0)==0&&((classic(c.procId)&7)==1||(classic(c.procId)&7)==2))drawIndicatorOnly=true;
		try{draw(handle);}finally{drawIndicatorOnly=false;}
	}
	/** SetCtlMin / SetCtlMax: a value below the new minimum is raised to it, one above the new maximum lowered to it (Inside Macintosh);
	 *  the other side is left alone, so a maximum below the minimum still lowers the value - REALbasic's PopupMenu.DeleteAllRows is
	 *  SetControl32BitMaximum(0) with min 1 and must leave ListIndex (value-1) at -1. */
	void setLimit(int handle,boolean minimum,int limit) throws M68kCpuException {
		int d=data(handle);if(d==0)return;
		memory.writeWord(d+(minimum?20:22),limit);
		int value=(short)memory.readWord(d+18);
		if(minimum?value<limit:value>limit)memory.writeWord(d+18,value=limit);
		// a tab control always has a front tab once it has tabs (as from a 'tab#'): REALbasic's TabPanel is NewControl(value 0, min 0,
		// max 0) and one SetControl32BitMaximum per tab, then reads the value to show panel 1 (a blank Preferences window before)
		Control c=controls.get(handle);
		if(!minimum&&value<1&&limit>=1&&c!=null&&c.cdef==0&&(c.procId>>4)==AppearanceControls68k.TABS){memory.writeWord(d+20,1);memory.writeWord(d+18,1);}
		draw(handle);
	}
	/** Set while {@link #setValue} redraws a check box or radio button: only its box is drawn (see there). */
	private boolean drawIndicatorOnly;
	int value(int handle) throws M68kCpuException {int d=data(handle);return d==0?0:(short)memory.readWord(d+18);}
	static int clamp(int value,int min,int max){return min<=max?Math.max(min,Math.min(max,value)):value;}

	// ------------------------------------------------------------------ hit testing

	int partAt(int handle,int h,int v) throws M68kCpuException {
		int d=data(handle);Control c=controls.get(handle);
		if(d==0||c==null||memory.readByte(d+16)==0||memory.readByte(d+17)==255||tb.appearance!=null&&tb.appearance.hiddenByEmbedder(handle))return 0;
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		if(h<left||h>=right||v<top||v>=bottom)return 0;
		adoptDefProc(c,d);
		if(c.cdef!=0)return (short)defProc(c,TEST_CNTL,(v<<16)|(h&0xffff));
		if(c.nativeDef!=null)try{return c.nativeDef.test(handle,h,v);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
		return partAs(d,c.procId,h,v);
	}
	private int partAs(int d,int procId,int h,int v) throws M68kCpuException {
		procId=classic(procId);int kind=procId&0xfff0;int base=procId&7; // bit 3 is useWFont, not a variant
		if(kind==0){if(base==1||base==2)return IN_CHECK_BOX;return IN_BUTTON;}
		if(kind==16)return scrollPart(d,h,v);
		if(kind==1008)return 1;
		return IN_BUTTON;
	}

	private int scrollPart(int d,int h,int v) throws M68kCpuException {
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		boolean vertical=(bottom-top)>=(right-left);
		int min=(short)memory.readWord(d+20),max=(short)memory.readWord(d+22),value=(short)memory.readWord(d+18);
		KaleidoscopeScheme68k.Bar bar=bar(top,left,bottom,right,min,max,value);
		return bar.part(vertical?v-top:h-left);
	}
	/** A scroll bar's arrows, track and thumb: the Kaleidoscope scheme's (double arrows, a thumb of fixed size) when one is in use. */
	KaleidoscopeScheme68k.Bar bar(int top,int left,int bottom,int right,int min,int max,int value){
		if(max>min)value=clamp(value,min,max); // NewControl leaves an initial value outside min..max: the thumb shows it pinned
		KaleidoscopeScheme68k k=KaleidoscopeScheme68k.active(tb);
		KaleidoscopeScheme68k.Bar bar=k==null?null:k.bar(left,top,right,bottom,min,max,value);
		return bar!=null?bar:nativeBar(top,left,bottom,right,min,max,value);
	}
	static KaleidoscopeScheme68k.Bar nativeBar(int top,int left,int bottom,int right,int min,int max,int value){
		boolean vertical=(bottom-top)>=(right-left);int length=vertical?bottom-top:right-left;int arrow=Math.min(16,length/2);
		int[] thumb=max>min?thumbRange(length,arrow,min,max,value):new int[]{0,0};
		return new KaleidoscopeScheme68k.Bar(vertical,length,new int[][]{{0,arrow,IN_UP},{length-arrow,length,IN_DOWN}},arrow,length-arrow,thumb[0],thumb[1],false,false);
	}
	private KaleidoscopeScheme68k.Bar bar(int d) throws M68kCpuException {
		return bar((short)memory.readWord(d+8),(short)memory.readWord(d+10),(short)memory.readWord(d+12),(short)memory.readWord(d+14),(short)memory.readWord(d+20),(short)memory.readWord(d+22),(short)memory.readWord(d+18));
	}
	/** {start,end} of the thumb along the track. */
	static int[] thumbRange(int length,int arrow,int min,int max,int value){
		if(max>min)value=Math.max(min,Math.min(max,value)); // an initial value outside min..max (NewControl keeps it) is drawn pinned
		int track=length-2*arrow;int thumbSize=Math.max(16,Math.min(track,track/Math.max(2,(max-min)/8+2)));
		int travel=Math.max(0,track-thumbSize);int start=arrow+(max>min?(int)((long)(value-min)*travel/(max-min)):0);
		return new int[]{start,start+thumbSize};
	}

	private boolean findControl(M68kCpu cpu) throws M68kCpuException {
		int resultPtr=memory.readLong(cpu.a[7]),window=memory.readLong(cpu.a[7]+4),v=(short)memory.readWord(cpu.a[7]+8),h=(short)memory.readWord(cpu.a[7]+10);cpu.a[7]+=12;
		int found=0,part=0;
		for(int handle:controlsOwnedBy(window)){part=partAt(handle,h,v);if(part!=0){found=handle;break;}}
		if(resultPtr!=0)memory.writeLong(resultPtr,found);memory.writeWord(cpu.a[7],part);return true;
	}

	// ------------------------------------------------------------------ tracking

	private boolean trackControl(M68kCpu cpu) throws M68kException {
		int action=memory.readLong(cpu.a[7]),v=(short)memory.readWord(cpu.a[7]+4),h=(short)memory.readWord(cpu.a[7]+6),handle=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
		memory.writeWord(cpu.a[7],track(handle,h,v,action));return true;
	}

	/** Live scrolling: a thumb drag returns from TrackControl at every value change so the program scrolls right away,
	 *  and the Event Manager presses the thumb again while the button stays down (-Dhz68k.livescroll=false for the classic
	 *  outline drag). {@code liveThumb} is the control being dragged, {@code liveGrab} where in the thumb it was grabbed. */
	private static final boolean LIVE_SCROLL=!"false".equals(System.getProperty("hz68k.livescroll"));
	private int liveThumb,liveGrab;

	/** The vertical (or horizontal) scroll bar of {@code window} nearest to a local point, for the mouse wheel; 0 when none. */
	int scrollBarNear(int window,int h,int v,boolean horizontal) throws M68kCpuException {
		int best=0,bestScore=Integer.MAX_VALUE;
		for(Control c:new ArrayList<Control>(controls.values())){
			if(c.window!=window||(classic(c.procId)&0xfff0)!=16||c.cdef!=0)continue;
			int d=data(c.handle);if(d==0||memory.readByte(d+16)==0||memory.readByte(d+17)==255)continue;
			int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
			boolean vertical=(bottom-top)>=(right-left);if(vertical==horizontal)continue;
			if((short)memory.readWord(d+22)<=(short)memory.readWord(d+20))continue;
			int along=vertical?v:h,alongMin=vertical?top:left,alongMax=vertical?bottom:right,across=vertical?h:v,acrossMin=vertical?left:top,acrossMax=vertical?right:bottom;
			int score=(across<acrossMin?acrossMin-across:across>=acrossMax?across-acrossMax+1:0)+(along>=alongMin&&along<alongMax?0:10000);
			if(score<bestScore){bestScore=score;best=c.handle;}
		}
		return best;
	}
	/** Centre of a scroll bar's down/right ({@code forward}) or up/left arrow, in window coordinates. */
	int[] arrowPoint(int handle,boolean forward) throws M68kCpuException {
		int d=data(handle);if(d==0)return null;
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		boolean vertical=(bottom-top)>=(right-left);
		int pos=bar(d).arrowCentre(forward?IN_DOWN:IN_UP);
		return vertical?new int[]{(left+right)/2,top+pos}:new int[]{left+pos,(top+bottom)/2};
	}
	/** Called by the Event Manager before handing out events: presses the thumb again where the mouse now holds it. */
	void continueThumbDrag() throws M68kCpuException {
		int handle=liveThumb;if(handle==0)return;liveThumb=0;
		if(!tb.host().isMouseDown())return;
		int d=data(handle);Control c=controls.get(handle);if(d==0||c==null||memory.readByte(d+16)==0||memory.readByte(d+17)==255)return;
		WindowManager68k.Window w=tb.windows.window(c.window);if(w==null||!w.visible)return;
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		boolean vertical=(bottom-top)>=(right-left);int min=(short)memory.readWord(d+20),max=(short)memory.readWord(d+22),value=(short)memory.readWord(d+18);
		KaleidoscopeScheme68k.Bar bar=bar(top,left,bottom,right,min,max,value);
		int pos=bar.thumbStart+Math.max(0,Math.min(bar.thumbEnd-bar.thumbStart-1,liveGrab));
		int h=vertical?(left+right)/2:left+pos,v=vertical?top+pos:(top+bottom)/2;
		liveThumb=handle; // stays live until the button goes up or the program stops tracking it
		tb.events.postContinuedPress(h+w.left,v+w.top);
	}

	/** Tracks the mouse in a control until the button is released; returns the part code hit. */
	int track(int handle,int startH,int startV,int action) throws M68kException {
		int part=trackPart(handle,startH,startV,action);
		// the ROM's tracking ends in WaitMouseUp, which takes the mouse-up out of the queue (a live thumb drag returns with the button still down)
		if(!tb.host().isMouseDown())tb.events.mouseUpTracked();
		return part;
	}
	private int trackPart(int handle,int startH,int startV,int action) throws M68kException {
		int d=data(handle);Control c=controls.get(handle);if(d==0||c==null)return 0;
		int startPart=partAt(handle,startH,startV);
		// a start point that hits no part: the caller passed the mouse-down's GLOBAL point (MacWeb's form check boxes, radio
		// buttons and buttons: TrackControl(ctl, event.where)) - the part under the mouse now, as the ROM's TrackControl
		// finds it (Executor does the same for Super Dice It); they did not react to clicks at all
		if(startPart==0){int[] m=tb.events.localMouse();int p=partAt(handle,m[0],m[1]);if(p!=0){startH=m[0];startV=m[1];startPart=p;}}
		if(Mac68kApi.TRACE)System.err.println("[68K] TrackControl $"+Integer.toHexString(handle)+" proc "+c.procId+" rect "+(short)memory.readWord(d+8)+","+(short)memory.readWord(d+10)+","+(short)memory.readWord(d+12)+","+(short)memory.readWord(d+14)+" vis "+memory.readByte(d+16)+" hilite "+memory.readByte(d+17)+" at "+startH+","+startV+" part "+startPart);
		if(startPart==0)return 0;
		if(c.nativeDef!=null){int proc=action==-1?memory.readLong(d+32):action;return c.nativeDef.track(handle,startH,startV,tb.host().modifiers(),proc==-1?0:proc);}
		if(action==0)action=0;else if(action==-1)action=memory.readLong(d+32);
		int kind=classic(c.cdef!=0&&c.standardProc>=0?c.standardProc:c.procId)&0xfff0;
		if(kind==1008)return trackPopup(handle,c);
		if(c.cdef!=0&&c.standardProc<0&&action==-1){ // contrlAction = -1: the CDEF tracks itself through autoTrack messages; a pop-up CDEF may run PopUpMenuSelect from it
			memory.writeByte(d+17,startPart);int result=startPart;
			try{
				defProc(c,AUTO_TRACK,startPart);
				while(tb.host().isMouseDown()){int[] m=tb.events.localMouse();if(partAt(handle,m[0],m[1])!=startPart){result=0;break;}tb.events.idle(1);if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);}
			}finally{memory.writeByte(d+17,0);draw(handle);}
			selectTab(c,handle,result);
			return result;
		}
		if(action==-1)action=0; // contrlAction -1 on a control that does not track itself: its autoTrack message does nothing
		if(c.cdef!=0&&c.standardProc<0&&startPart==IN_THUMB&&action!=-1){ // a program's own scroll bar CDEF tracks its indicator itself: dragCntl with the control's action set (iTunes' Aqua Scroll Bar)
			int savedAction=memory.readLong(d+32);if(action!=0)memory.writeLong(d+32,action);
			try{
				memory.writeByte(d+17,startPart);
				defProc(c,DRAG_CNTL,1);
			}finally{memory.writeLong(d+32,savedAction);memory.writeByte(d+17,0);draw(handle);}
			return startPart;
		}
		if(kind==16&&startPart==IN_THUMB)return trackThumb(handle,d,startH,startV,action);
		boolean inside=true;memory.writeByte(d+17,startPart);draw(handle);
		if(tb.events.takeSyntheticClick()){ // a wheel click: act once, as a real TrackControl does before it first samples the button
			if(Mac68kApi.TRACE)System.err.println("[68K] synthetic click: action $"+Integer.toHexString(action)+" part "+startPart);
			if(action!=0&&(kind==16||c.cdef!=0&&c.standardProc<0))callAction(action,handle,startPart);
			memory.writeByte(d+17,0);draw(handle);return startPart;
		}
		boolean acts=kind==16||c.cdef!=0&&c.standardProc<0; // a program's own CDEF (iTunes' Aqua scroll bar) also gets its action procedure called
		long nextAction=0;
		while(tb.host().isMouseDown()){
			int[] m=tb.events.localMouse();
			boolean now=partAt(handle,m[0],m[1])==startPart;
			if(now!=inside){inside=now;memory.writeByte(d+17,inside?startPart:0);draw(handle);}
			if(inside&&action!=0&&acts&&System.nanoTime()>=nextAction){callAction(action,handle,startPart);nextAction=System.nanoTime()+60_000_000L;}
			tb.events.idle(1);
			if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
		}
		memory.writeByte(d+17,0);draw(handle);
		if(inside)selectTab(c,handle,startPart);
		return inside?startPart:0;
	}

	private int trackThumb(int handle,int d,int startH,int startV,int action) throws M68kException {
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		boolean vertical=(bottom-top)>=(right-left);int min=(short)memory.readWord(d+20),max=(short)memory.readWord(d+22),startValue=(short)memory.readWord(d+18);
		KaleidoscopeScheme68k.Bar bar=bar(top,left,bottom,right,min,max,startValue);
		int travel=Math.max(1,(bar.trackEnd-bar.trackStart)-(bar.thumbEnd-bar.thumbStart));
		int grab=liveThumb==handle?liveGrab:(vertical?startV-top:startH-left)-bar.thumbStart;liveThumb=0;
		while(tb.host().isMouseDown()){
			int[] m=tb.events.localMouse();int delta=vertical?m[1]-startV:m[0]-startH;
			int value=clamp(startValue+(int)Math.round((double)delta*(max-min)/travel),min,max);
			if(value!=(short)memory.readWord(d+18)){
				memory.writeWord(d+18,value);draw(handle);if(action!=0)callAction(action,handle,IN_THUMB);
				if(LIVE_SCROLL){liveThumb=handle;liveGrab=grab;return IN_THUMB;}
			}
			tb.events.idle(1);
		}
		return IN_THUMB;
	}

	private int trackPopup(int handle,Control c) throws M68kException {
		int d=data(handle);int priv=memory.readLong(d+28);int menuHandle=priv==0?0:memory.readLong(memory.readLong(priv));
		MenuManager68k.Menu m=tb.menus.read(menuHandle);if(m==null)return 0;
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10);int titleWidth=popupTitleWidth(d,c);
		int[] g=tb.windows.localToGlobal(left+titleWidth,top);
		memory.writeByte(d+17,1);draw(handle);
		int savedSp=tb.cpu.a[7];
		// PopUpMenuSelect(menu, top, left, popUpItem): Pascal arguments pushed left to right under the LONGINT result
		tb.cpu.a[7]-=4;tb.cpu.pushLong(menuHandle);tb.cpu.pushWord(g[1]);tb.cpu.pushWord(g[0]);tb.cpu.pushWord((short)memory.readWord(d+18));
		tb.menus.handle(tb.cpu,0x80b);
		int result=memory.readLong(tb.cpu.a[7]);tb.cpu.a[7]=savedSp;
		memory.writeByte(d+17,0);
		if((result&0xffff)!=0){memory.writeWord(d+18,result&0xffff);KaleidoscopeScheme68k.popupChosen(tb,m.id,result&0xffff);draw(handle);return 1;}
		draw(handle);return 0;
	}

	private void callAction(int proc,int handle,int part) throws M68kException {
		if(proc==0)return;
		int[] dregs=tb.cpu.d.clone(),aregs=tb.cpu.a.clone();
		tb.cpu.pushLong(handle);tb.cpu.pushWord(part);
		try{tb.cpu.callPascal(proc,6);}
		finally{System.arraycopy(dregs,0,tb.cpu.d,0,8);System.arraycopy(aregs,0,tb.cpu.a,0,8);}
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * The System's CDEF 0 (buttons) and 1 (scroll bars) run for a program that calls them through their Handles - a CDEF of its
	 * own that draws some variants itself and hands the others on (Excel 2.2's CDEF 8). The native drawing and part testing of
	 * the standard kinds serve them; regions are the control's rectangle (the thumb's for calcThumbRgn).
	 */
	int standardCdef(int id,int varCode,int handle,int message,int param) throws M68kCpuException {
		int d=data(handle);Control c=controls.get(handle);if(d==0||c==null)return 0;
		int procId=id<<4|varCode&0xf;c.standardProc=procId;
		switch(message){
			case 0:{ // drawCntl
				if(memory.readByte(d+16)==0)return 0;int window=owner(c,d);
				if(!drawableOwner(window))return 0;
				drawAs(handle,d,c,window,procId);return 0;}
			case 1:{int h=(short)param,v=(short)(param>>>16); // testCntl
				int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
				if(memory.readByte(d+17)==255||h<left||h>=right||v<top||v>=bottom)return 0;return partAs(d,procId,h,v);}
			case 2:case 10:case 11:{ // calcCRgns (high bit: the thumb), calcCntlRgn, calcThumbRgn
				boolean thumb=message==11||(message==2&&param<0);int rgn=param&0x7fffffff;
				int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
				if(thumb&&(procId&0xfff0)==16){boolean vertical=(bottom-top)>=(right-left);
					KaleidoscopeScheme68k.Bar t=bar(top,left,bottom,right,(short)memory.readWord(d+20),(short)memory.readWord(d+22),(short)memory.readWord(d+18));
					if(vertical){bottom=top+t.thumbEnd;top=top+t.thumbStart;}else{right=left+t.thumbEnd;left=left+t.thumbStart;}}
				tb.windows.setRegion(rgn,top,left,bottom,right);return 0;}
			default:return 0; // initCntl, dispCntl, posCntl/thumbCntl/dragCntl (TrackControl moves the thumb itself), autoTrack
		}
	}

	/**
	 * Whether controls of {@code window} draw now: a shown window, or one a picture is recording. HyperCard's card window and
	 * message box are no Window Manager windows, but externals put controls on the card (ScrollControl's NewControl over a
	 * button, the ScrollBar XFCN's "paint" bar recorded with Draw1Control between OpenPicture and ClosePicture): never drawn.
	 */
	private boolean drawableOwner(int window) throws M68kCpuException {
		boolean recording=tb.api.quickDraw().pictureIsOpen();
		if(tb.windows.isWindow(window))return tb.windows.window(window).visible||recording;
		return tb.cardWindow!=null&&tb.cardWindow.isWindowRecord(window)&&(memory.readByte(window+110)!=0||recording);
	}

	void drawAll(int window) throws M68kCpuException {List<Integer> list=controlsOf(window);for(int i=list.size()-1;i>=0;i--)draw(list.get(i));}

	void draw(int handle) throws M68kCpuException {
		int d=data(handle);Control c=controls.get(handle);
		if(d==0||c==null||memory.readByte(d+16)==0||tb.appearance!=null&&tb.appearance.hiddenByEmbedder(handle))return;
		int window=owner(c,d);
		// A control in an invisible window still draws while a picture records it - dialog editors record the
		// buttons of an offscreen dialog into its mini-screen preview (same reason DialogManager.draw runs then).
		if(!drawableOwner(window))return;
		adoptDefProc(c,d);
		if(c.cdef!=0){defProc(c,DRAW_CNTL,0);return;}
		if(c.nativeDef!=null){try{c.nativeDef.draw(handle);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}}
		else drawAs(handle,d,c,window,c.procId);
		// drawn with Java2D, which picture recording does not see: an open picture gets the control's pixels (a program's CDEF
		// draws through QuickDraw and is recorded as it goes)
		if(tb.api.quickDraw().pictureIsOpen())recordDrawn(handle,window);
	}
	/** The control's rectangle of its port into the open picture (the port's picSave decides whether it records). */
	private void recordDrawn(int handle,int window) throws M68kCpuException {
		int d=data(handle);if(d==0)return;
		QuickDraw68k qd=tb.api.quickDraw();int saved=qd.currentPortAddress(),port=tb.quickDraw.controlPort(window);
		try{
			if(port!=0&&saved!=port)setPort(tb.cpu,port);
			qd.recordPortSnapshot((short)memory.readWord(d+8),(short)memory.readWord(d+10),(short)memory.readWord(d+12),(short)memory.readWord(d+14));
		}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
		finally{if(port!=0&&saved!=port)try{setPort(tb.cpu,saved);}catch(M68kException e){throw Toolbox68k.wrap(e);}}
	}
	/** A control's title in the user's language (VMTranslation), or null to show it as it is. */
	private String translatedTitle(String title){
		if(title.length()<2||tb.translator()==null)return null;
		return tb.translated(VMTranslation.Kind.UI,title);
	}
	/** A host font of the family for a translated title: the size, or smaller (to 60%, 7 points at least) until it is {@code room} wide. */
	static Font fitFont(int fontId,int size,String text,int room){
		if(size<=0)size=12;
		Font f=Fonts68k.hostFont(fontId,size,0);
		for(int sz=size-1,least=Math.max(7,(int)Math.ceil(size*0.6));sz>=least&&Fonts68k.width(f,text)>room;sz--)f=Fonts68k.hostFont(fontId,sz,0);
		return f;
	}
	private void drawAs(int handle,int d,Control c,int window,int procId) throws M68kCpuException {
		procId=classic(procId);
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		// only the rows matter (change tracking); titles may run past the rectangle, as with a real CDEF
		Graphics2D g=tb.quickDraw.windowSurface(window,top-4,-32768,bottom+4,32767);java.awt.image.BufferedImage staged=null;
		if(g==null){
			// The window's port is not a direct 32-bit surface (some programs draw updates into an 8-bit offscreen
			// PixMap installed with SetPortPix): render the control into an image and copy it through QuickDraw.
			if(right<=left||bottom<=top||(long)(right-left)*(bottom-top)>16_777_216L)return;
			staged=new java.awt.image.BufferedImage(right-left,bottom-top,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			g=staged.createGraphics();g.translate(-left,-top);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
		}
		try{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,tb.quickDraw.antialiasHint(tb.quickDraw.controlPort(window)));
			int hilite=memory.readByte(d+17);int value=(short)memory.readWord(d+18),min=(short)memory.readWord(d+20),max=(short)memory.readWord(d+22);
			String title=tb.decodeTitle(memory.readBytes(d+41,memory.readByte(d+40)));
			boolean disabled=hilite==255;int kind=procId&0xfff0,base=procId&7; // pushButProc/checkBoxProc/radioButProc, + useWFont (8)
			Font font=(procId&8)!=0?windowFont(window):Fonts68k.font(0,12,0);
			String translation=translatedTitle(title);
			if(translation!=null){ // a host font has the translation's characters; smaller sizes until it fits the control
				int room=kind==0&&base==0?right-left-8:kind==0&&(base==1||base==2)?right-left-18:kind==1008?Math.max(8,right-left-24):right-left-4;
				font=fitFont((procId&8)!=0?memory.readWord(window+68):0,font.getSize(),translation,room);title=translation;}
			// a check box or radio button no wider than its box: the CDEF clips the title to the rectangle, so none of it shows -
			// allShare's cdev labels such 16-pixel radio buttons with static texts beside them (the titles came out over the labels)
			if(titleClipped(kind,base,left,right))title="";
			g.setFont(font);FontMetrics fm=Fonts68k.metrics(font); // an installed Chicago strike measures and draws itself (Fonts68k.drawString)
			KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);
			if(scheme!=null&&kind==0&&base==0&&scheme.element(KaleidoscopeScheme68k.PUSH)!=null){
				boolean ring=tb.appearance.isDefaultButton(handle);
				if(c.ringDrawn&&!ring){g.setColor(new Color(tb.api.quickDraw().colorsOf(window)[1]));g.fillRect(left-5,top-5,right-left+10,bottom-top+10);}
				if(ring)scheme.drawDefaultRing(g,left,top,right,bottom,disabled);
				c.ringDrawn=ring;
				scheme.drawPushButton(g,left,top,right,bottom,title,hilite==IN_BUTTON,disabled,fm);}
			else if(scheme!=null&&kind==16&&drawSchemeScrollBar(scheme,g,left,top,right,bottom,value,min,max,hilite,disabled,memory.readByte(window+111)!=0)){}
			else if(scheme!=null&&kind==80&&scheme.hasProgressBar()){ // over the port's background, its pattern too (Aqua III's pin stripes show under the corners)
				eraseInPort(window,top,left,bottom,right);scheme.drawProgressBar(g,left,top,right,bottom,value,min,max,disabled);}
			else if(kind==0&&base==0){
				// With the Appearance Manager, CDEF 0's push button is the Appearance one: SetControlData('dflt') puts the
				// default ring round it (ADM, Acrobat 5's dialogs, marks OK that way, not with a user item)
				boolean ring=tb.appearance.isDefaultButton(handle);
				if(ring||c.ringDrawn){int arc=Math.min(bottom-top,16)+6;
					g.setColor(ring?(disabled?DISABLED:Color.BLACK):new Color(tb.api.quickDraw().colorsOf(window)[1]));g.setStroke(new BasicStroke(3f));
					g.drawRoundRect(left-3,top-3,right-left+5,bottom-top+5,arc,arc);g.setStroke(new BasicStroke(1f));}
				c.ringDrawn=ring;
				drawButton(g,left,top,right,bottom,title,hilite==IN_BUTTON,disabled,fm);}
			else if(scheme!=null&&kind==0&&(base==1||base==2)&&scheme.checkBoxWidth(base==2)>0){if(drawIndicatorOnly)g.clipRect(left-1,top,Math.min(right-left+1,scheme.checkBoxWidth(base==2)+1),bottom-top);
				scheme.drawCheckOrRadio(g,left,top,right,bottom,title,value,base==2,hilite==IN_CHECK_BOX,disabled,fm,new Color(tb.api.quickDraw().colorsOf(window)[1]));}
			else if(kind==0&&(base==1||base==2)){if(drawIndicatorOnly)g.clipRect(left-1,top,Math.min(right-left+1,15),bottom-top); // the 12-pixel box and its frame
				drawCheckOrRadio(g,left,top,right,bottom,title,value!=0,base==2,hilite==IN_CHECK_BOX,disabled,fm,value==2,new Color(tb.api.quickDraw().colorsOf(window)[1]));}
			else if(kind==16){ // crisp and inside its rectangle, as a CDEF draws: an antialiased frame bled into the row above, which
				// a program scrolling the pixels right above the bar (Excel 2.2's grid) then carried up as a grey line
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);g.clipRect(left,top,right-left,bottom-top);
				drawScrollBar(g,left,top,right,bottom,value,min,max,hilite,disabled,scheme);}
			else if(kind==1008)drawPopup(g,d,c,left,top,right,bottom,title,value,hilite!=0,disabled,fm);
			else if(kind==80)drawProgressBar(g,left,top,right,bottom,value,min,max,disabled); // kControlProgressBarProc (Appearance CDEF 5)
			else drawButton(g,left,top,right,bottom,title,hilite!=0&&hilite!=255,disabled,fm);
		}finally{g.dispose();}
		if(staged!=null){
			QuickDraw68k qd=tb.api.quickDraw();int saved=qd.currentPortAddress(),port=tb.quickDraw.controlPort(window);
			try{if(saved!=port)setPort(tb.cpu,port);tb.quickDraw.drawImage(staged,top,left,bottom,right);}
			catch(M68kException e){if(e instanceof M68kCpuException)throw (M68kCpuException)e;}
			finally{if(saved!=port&&saved!=0)try{setPort(tb.cpu,saved);}catch(M68kException ignored){}}
		}
	}

	private static boolean drawSchemeScrollBar(KaleidoscopeScheme68k scheme,Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,int hilite,boolean disabled,boolean windowActive){
		java.awt.Shape clip=g.getClip();g.clipRect(left,top,right-left,bottom-top);
		try{return scheme.drawScrollBar(g,left,top,right,bottom,value,min,max,hilite,disabled,windowActive);}finally{g.setClip(clip);}
	}

	/** The Appearance Manager's determinate progress bar: a sunken track filled from the left in proportion to the value
	 *  (むりやりMusic's Instrument, Pitch and Progress bars, which its music thread sets as it plays). */
	private static void drawProgressBar(Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,boolean disabled){
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
		int w=right-left,h=bottom-top;if(w<=2||h<=2)return;
		g.setColor(TRACK);g.fillRect(left,top,w,h);
		int range=max-min,filled=range<=0?0:(int)((long)(w-2)*(Math.max(min,Math.min(max,value))-min)/range);
		if(filled>0){g.setColor(disabled?DISABLED:PROGRESS);g.fillRect(left+1,top+1,filled,h-2);g.setColor(disabled?DISABLED:PROGRESS_LIGHT);g.drawLine(left+1,top+1,left+filled,top+1);}
		g.setColor(disabled?DISABLED:FRAME);g.drawRect(left,top,w-1,h-1);
	}
	private static final Color PROGRESS=new Color(0x55,0x55,0xcc),PROGRESS_LIGHT=new Color(0x99,0x99,0xee);

	private Font windowFont(int window) throws M68kCpuException {return Fonts68k.font(memory.readWord(window+68),Math.max(9,memory.readWord(window+74)),memory.readWord(window+70));}

	/** HideControl's erase (also MoveControl/SizeControl of a visible control): the rectangle is filled with the port's
	 *  background and added to the update region, so whatever the control covered is drawn again (IM I-322) - iTunes moves
	 *  its buttons when its window grows and redraws its metal backdrop only where the update region says. Only a push button
	 *  takes the 4 pixels round it as well, where its default ring is drawn: HyperCard hides its script window's scroll bars at
	 *  every deactivate and ValidRects each control rectangle, and the rings left over made one update of the whole window -
	 *  TEUpdate drew the script over itself without an erase, and its smoothed letters grew darker at each switch. */
	private void erase(int handle) throws M68kCpuException {
		int d=data(handle);Control c=controls.get(handle);if(d==0||c==null)return;
		int top=(short)memory.readWord(d+8),left=(short)memory.readWord(d+10),bottom=(short)memory.readWord(d+12),right=(short)memory.readWord(d+14);
		int window=owner(c,d),kind=classic(c.procId)&0xfff0,base=c.procId&7; // pushButProc, or the Appearance one (368, 374/375 with an icon)
		int m=(kind==0||kind==368)&&base!=1&&base!=2?4:0;
		Graphics2D g=tb.quickDraw.windowSurface(window,top-4,-32768,bottom+4,32767);if(g==null)return;
		try{g.setColor(new Color(tb.api.quickDraw().colorsOf(window)[1]));g.fillRect(left-m,top-m,right-left+2*m,bottom-top+2*m);}
		finally{g.dispose();}
		if(tb.windows.isWindow(window))tb.windows.invalidate(window,top-m,left-m,bottom+m,right+m);
	}

	/** Whether a standard check box or radio button (kind 0, base 1/2) is too narrow to show any of its title: the title starts 19 pixels in. */
	static boolean titleClipped(int kind,int base,int left,int right){return kind==0&&(base==1||base==2)&&right-left<=19;}
	static void drawButton(Graphics2D g,int left,int top,int right,int bottom,String title,boolean pressed,boolean disabled,FontMetrics fm){
		int w=right-left,h=bottom-top;int arc=Math.min(h,16);
		g.setColor(pressed?SHADE:FILL);g.fillRoundRect(left,top,w-1,h-1,arc,arc);
		g.setColor(disabled?DISABLED:FRAME);g.setStroke(new BasicStroke(1f));g.drawRoundRect(left,top,w-1,h-1,arc,arc);
		g.setColor(disabled?DISABLED:Color.BLACK);
		// the CDEF draws inside the control's rectangle: PageMaker 6 rules its dialogs with 2-pixel-high disabled buttons titled
		// "Button", which show as lines (the title came out across the rule); only the height clips, so a substitute font a
		// little wider than the program's keeps its whole title
		java.awt.Shape clip=g.getClip();g.clip(new java.awt.Rectangle(left-1000,top,w+2000,h));
		try{drawTitleLines(g,fm,title,left,top,w,h,true);}finally{g.setClip(clip);}
	}
	/** A standard control title: System 7's CDEF 0 breaks it at Returns into lines stacked around the vertical centre (Spades'
	 *  "Suggestions\r or Problems" button), each line centred in a button or left-aligned after a check box / radio button. */
	private static void drawTitleLines(Graphics2D g,FontMetrics fm,String title,int left,int top,int w,int h,boolean centre){
		String[] lines=title.split("\r",-1);int lh=fm.getHeight();int y=top+(h-lh*lines.length)/2+fm.getAscent();
		for(String line:lines){Fonts68k.drawString(g,fm,line,centre?left+(w-fm.stringWidth(line))/2:left,y);y+=lh;}
	}
	static void drawDefaultOutline(Graphics2D g,int left,int top,int right,int bottom){
		g.setColor(FRAME);g.setStroke(new BasicStroke(3f));int arc=Math.min(bottom-top+8,20);g.drawRoundRect(left-4,top-4,right-left+7,bottom-top+7,arc,arc);g.setStroke(new BasicStroke(1f));
	}
	/** {@code background}: the owner port's background colour, which the CDEF erases the control's rectangle with (the grey of
	 *  an Appearance dialog - iTunes' Setup Assistant - rather than white bars behind its radio buttons). */
	static void drawCheckOrRadio(Graphics2D g,int left,int top,int right,int bottom,String title,boolean on,boolean radio,boolean pressed,boolean disabled,FontMetrics fm,boolean mixed,Color background){
		int size=12;int y=top+(bottom-top-size)/2;
		// without a title only the box's part is erased: HyperFind's first radio button has an empty title and the dialog's
		// static text "default" inside its rect is the label (drawn before the controls, it vanished under the erase)
		g.setColor(background);g.fillRect(left-1,top,title.isEmpty()?Math.min(right-left+1,size+4):right-left+1,bottom-top);
		g.setColor(pressed?SHADE:Color.WHITE);
		if(radio)g.fillOval(left+1,y,size,size);else g.fillRect(left+1,y,size,size);
		g.setColor(disabled?DISABLED:FRAME);
		if(radio)g.drawOval(left+1,y,size,size);else g.drawRect(left+1,y,size,size);
		if(on){g.setColor(disabled?DISABLED:Color.BLACK);if(radio)g.fillOval(left+4,y+3,size-6,size-6);else if(mixed)g.fillRect(left+4,y+size/2,size-6,2);else{g.setStroke(new BasicStroke(2f));g.drawLine(left+3,y+6,left+6,y+9);g.drawLine(left+6,y+9,left+11,y+3);g.setStroke(new BasicStroke(1f));}}
		g.setColor(disabled?DISABLED:Color.BLACK);drawTitleLines(g,fm,title,left+size+7,top,0,bottom-top,false);
	}
	static void drawScrollBar(Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,int hilite,boolean disabled){drawScrollBar(g,left,top,right,bottom,value,min,max,hilite,disabled,null);}
	/** ... with the thumb of a Kaleidoscope scheme that has no bars of its own (Apple platinum's accent colour thumbs). */
	static void drawScrollBar(Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,int hilite,boolean disabled,KaleidoscopeScheme68k scheme){
		int w=right-left,h=bottom-top;boolean vertical=h>=w;int length=vertical?h:w;int arrow=Math.min(16,length/2);
		g.setColor(TRACK);g.fillRect(left,top,w,h);g.setColor(FRAME);g.drawRect(left,top,w-1,h-1);
		boolean inactive=disabled||max<=min;
		Color arrowColor=inactive?DISABLED:Color.BLACK;
		// arrows
		g.setColor(hilite==IN_UP?SHADE:FILL);if(vertical)g.fillRect(left+1,top+1,w-2,arrow-1);else g.fillRect(left+1,top+1,arrow-1,h-2);
		g.setColor(hilite==IN_DOWN?SHADE:FILL);if(vertical)g.fillRect(left+1,bottom-arrow,w-2,arrow-1);else g.fillRect(right-arrow,top+1,arrow-1,h-2);
		g.setColor(arrowColor);
		int cx=left+w/2,cy=top+h/2;
		if(vertical){int ay=top+arrow/2;g.fillPolygon(new int[]{cx,cx-4,cx+4},new int[]{ay-3,ay+2,ay+2},3);ay=bottom-arrow/2;g.fillPolygon(new int[]{cx,cx-4,cx+4},new int[]{ay+3,ay-2,ay-2},3);}
		else{int ax=left+arrow/2;g.fillPolygon(new int[]{ax-3,ax+2,ax+2},new int[]{cy,cy-4,cy+4},3);ax=right-arrow/2;g.fillPolygon(new int[]{ax+3,ax-2,ax-2},new int[]{cy,cy-4,cy+4},3);}
		g.setColor(FRAME);if(vertical){g.drawLine(left,top+arrow,right-1,top+arrow);g.drawLine(left,bottom-arrow-1,right-1,bottom-arrow-1);}else{g.drawLine(left+arrow,top,left+arrow,bottom-1);g.drawLine(right-arrow-1,top,right-arrow-1,bottom-1);}
		if(inactive)return;
		int[] thumb=thumbRange(length,arrow,min,max,value);
		if(scheme!=null&&(vertical?scheme.drawThumbOver(g,left,top+thumb[0],w,thumb[1]-thumb[0],true,hilite==IN_THUMB):scheme.drawThumbOver(g,left+thumb[0],top,thumb[1]-thumb[0],h,false,hilite==IN_THUMB)))return;
		g.setColor(hilite==IN_THUMB?FRAME:THUMB);
		if(vertical)g.fillRoundRect(left+2,top+thumb[0]+1,w-4,thumb[1]-thumb[0]-2,6,6);else g.fillRoundRect(left+thumb[0]+1,top+2,thumb[1]-thumb[0]-2,h-4,6,6);
	}
	private int popupTitleWidth(int d,Control c) throws M68kCpuException {
		String title=tb.decodeTitle(memory.readBytes(d+41,memory.readByte(d+40)));
		if(title.isEmpty())return 0;
		if(c!=null&&c.popupTitleWidth>=0)return c.popupTitleWidth;
		return Fonts68k.width(Fonts68k.font(0,12,0),title)+6;
	}
	private void drawPopup(Graphics2D g,int d,Control c,int left,int top,int right,int bottom,String title,int value,boolean pressed,boolean disabled,FontMetrics fm) throws M68kCpuException {
		int titleWidth=title.isEmpty()?0:c!=null&&c.popupTitleWidth>=0?c.popupTitleWidth:fm.stringWidth(title)+6;
		if(titleWidth==0)title="";
		KaleidoscopeScheme68k kaleidoscope=c!=null?KaleidoscopeScheme68k.active(tb):null;
		g.setColor(kaleidoscope!=null&&kaleidoscope.hasPopup()?new Color(tb.api.quickDraw().colorsOf(owner(c,d))[1]):Color.WHITE);if(c==null||(c.procId&0xfff0)!=400)g.fillRect(left,top,right-left,bottom-top); // a scheme's pop-up sits on the window's background; an Appearance pop-up (400-family) never erases (ICQ's Network Settings "Protocol" group: white block over the Host/Port labels)
		g.setColor(disabled?DISABLED:Color.BLACK);if(!title.isEmpty())Fonts68k.drawString(g,fm,title,left,top+(bottom-top-fm.getHeight())/2+fm.getAscent());
		int bl=left+titleWidth,w=right-bl,h=bottom-top;
		KaleidoscopeScheme68k scheme=kaleidoscope;
		if(scheme!=null&&scheme.hasPopup()){
			int priv=memory.readLong(d+28);int menuHandle=priv==0?0:memory.readLong(memory.readLong(priv));MenuManager68k.Menu m=tb.menus.read(menuHandle);
			if(scheme.drawPopupBox(g,bl,top,right,bottom,m!=null&&value>=1&&value<=m.items.size()?m.items.get(value-1).text:"",pressed,disabled,fm)!=null)return;
		}
		g.setColor(pressed?SHADE:FILL);g.fillRoundRect(bl,top,w-1,h-1,6,6);g.setColor(disabled?DISABLED:FRAME);g.drawRoundRect(bl,top,w-1,h-1,6,6);
		int priv=memory.readLong(d+28);int menuHandle=priv==0?0:memory.readLong(memory.readLong(priv));MenuManager68k.Menu m=tb.menus.read(menuHandle);
		String text=m!=null&&value>=1&&value<=m.items.size()?m.items.get(value-1).text:"";
		// the pop-up CDEF truncates the current item (TruncString, "…" at the end) to the room left of the arrow: King's Edit's
		// folder pop-up in its open dialog is narrower than the folder name, which ran over the arrow and out of the button
		int room=w-6-18;if(fm.stringWidth(text)>room){int n=text.length();while(n>0&&fm.stringWidth(text.substring(0,n)+"\u2026")>room)n--;text=text.substring(0,n)+"\u2026";}
		g.setColor(disabled?DISABLED:Color.BLACK);Fonts68k.drawString(g,fm,text,bl+6,top+(h-fm.getHeight())/2+fm.getAscent());
		int ax=right-12,ay=top+h/2;g.fillPolygon(new int[]{ax-4,ax+4,ax},new int[]{ay-2,ay-2,ay+3},3);
	}

	private void writeRect(int p,int top,int left,int bottom,int right) throws M68kCpuException {memory.writeWord(p,top);memory.writeWord(p+2,left);memory.writeWord(p+4,bottom);memory.writeWord(p+6,right);}
	private static void putWord(byte[] b,int o,int v){b[o]=(byte)(v>>>8);b[o+1]=(byte)v;}
	private static void putLong(byte[] b,int o,int v){putWord(b,o,v>>>16);putWord(b,o+2,v);}
}

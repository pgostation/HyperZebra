import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Window Manager for hosted applications. Every WindowRecord is backed by a
 * native host window; the record's GrafPort draws into a 32-bit PixMap that the
 * host displays. Title bars, dragging, resizing and close boxes are native, and
 * their effects are reported back to the application through the classic
 * FindWindow / GrowWindow / TrackGoAway protocol.
 */
final class WindowManager68k {
	static final int WINDOW_SIZE=156;
	static final int IN_DESK=0,IN_MENU_BAR=1,IN_SYS_WINDOW=2,IN_CONTENT=3,IN_DRAG=4,IN_GROW=5,IN_GO_AWAY=6,IN_ZOOM_IN=7,IN_ZOOM_OUT=8;

	final class Window {
		final int record;int left,top,width,height;int procId;boolean visible,goAway,color;String title="";
		/** The title as last given to the host: {@link #title}, or its translation. */
		String shownTitle="";
		/** Created in Carbon's floating or utility window class (CreateNewWindow): it sits in the floating layer over the
		 *  documents and never takes the activation from the front document (3D Sokoban's toolbar deactivated its game window,
		 *  whose PowerPlant target then ignored every key). Classic floating WDEFs without a class keep the ROM's behaviour. */
		boolean floatingLayer;
		int contentColor=0xffffff; // wctb/dctb part 0: what the content is erased to
		/** A colour tile the content is erased to instead (a Kaleidoscope scheme's dialog background): {width, height, argb...}. */
		int[] contentTile;
		int pendingGrowWidth,pendingGrowHeight;boolean everShown;
		/** The WDEF 0 variant the program last had the regions calculated for, calling the defProc itself (HyperCard's
		 *  `hide titleBar`: plainDBox on its zoomNoGrow card window); -1 = the procID's own. */
		int variant=-1;
		/** The visRgn before BeginUpdate narrowed it to the update region (null outside BeginUpdate/EndUpdate). */
		QdRegion updateVis;
		/** What of the content PaintOne/PaintBehind would have erased since the last BeginUpdate (content-local), null for none:
		 *  see {@link #expose}. */
		QdRegion unerased;
		/** The global visible part of the content {@link #calcVis} last gave it (null: not computed, or the window is hidden). */
		QdRegion trueVis;
		/** portRect.topLeft when BeginUpdate saved {@link #updateVis}: the origin the saved shape is in. */
		int updateOriginV,updateOriginH;
		/** The port BeginUpdate replaced by this window's (0: none), and QuickDraw's SetPort count just after: EndUpdate puts that
		 *  port back unless the program set one itself meanwhile. */
		int portBeforeUpdate,portSetsAtUpdate;
		/** windowDefProc as NewWindow stored it: a program that puts another definition there runs the window with it ({@link #swappedDefinition}). */
		int createdDefProc;
	/** A window whose frame the program's own WDEF draws: the structure region relative to the content (frameLeft/Top <= 0) and its size. */
		boolean custom;int frameLeft,frameTop,frameWidth,frameHeight;
		/** HyperCard's own WDEF-0 wrapper changed the variant (PowerPC HyperCard's `hide titleBar`): the frame is rebuilt at DrawNew. */
		boolean reframePending;
		/** The structure region relative to the frame's top-left when it is not a plain rectangle (the host window takes that shape), else null. */
		QdRegion frameShape;
		/** dataHandle holds a WStateData the Window Manager allocated (zoomDocProc windows). */
		boolean zoomState;
		/** The WStateData kept for a window whose definition keeps none (see {@link #stateData}), 0 until one is needed. */
		int ownState;
		/** The title bar box a native floating-window definition shows pressed (wHit code), 0 for none. */
		int pressedPart;
		/** The Kaleidoscope scheme's frame the window is drawn in ({@link #themedFrame}), null for none; the frame last drawn
		 *  (hit tests look at its clear pixels) and whether the program has drawn the grow box (it is part of the frame since). */
		KaleidoscopeScheme68k.WindowFrame theme;java.awt.image.BufferedImage themeImage;boolean themeGrow;
		/** The scheme's frame a window created hidden takes when it is first shown, unless it covers the screen by then ({@link #themedFrame}). */
		KaleidoscopeScheme68k.WindowFrame pendingTheme;
		/** The Platinum flavor of a dialog frame drawn natively ({@link #dialogFrame}: dBoxProc 1, altDBoxProc 6), -1 for none. */
		int dialogFlavor=-1;
		/** A Text Services Manager service window (NewServiceWindow): it floats in TSM's own layer, so the program's WindowList,
		 *  FrontWindow and FindWindow do not see it - FindServiceWindow and GetFrontServiceWindow do. */
		boolean service;
		/** A Process Manager system window (OSDispatch $5A, see {@link #createSystemWindow}): over every program's windows, never
		 *  active, its mouse-downs and updates handed to {@link #systemProc} instead of the program's event loop. */
		boolean system;int systemProc,systemData;
		/** The record is the Window Manager's own Ptr (NewWindow/NewDialog with NIL wStorage): GetPtrSize knows it, DisposeWindow frees it. */
		boolean ownRecord;
		Window(int record){this.record=record;}
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final QuickDraw68k qd;
	final Map<Integer,Window> windows=new HashMap<Integer,Window>();
	/** Front-to-back order. */
	final List<Window> order=new ArrayList<Window>();
	/** The Window Manager's colour port (WMgrCPort, $D2C): WDEFs are called with it current, as on a Mac with Color QuickDraw. */
	private int wmgrPort;
	/** The classic Window Manager port (WMgrPort, $9DE): a real GrafPort, since a WDEF written for it reads its 8-byte pnPat/bkPat
	 *  straight from the record (HyperCard's message box WDEF does, and got a PixPat Handle as its background pattern from a CGrafPort). */
	private int wmgrClassicPort;
	private int pendingGoAwayWindow,pendingGrowWindow;
	/** The synthetic grow-corner click of a native resize: where it was posted (FindWindow recognises it by these coordinates, whatever
	 *  the window's size has become meanwhile), until when it is honoured (a program that ignores inGrow must not see its next corner
	 *  click as a grow), and whether one is still out (a slow native drag reports many sizes: only the latest size is kept and no
	 *  further click is posted until GrowWindow consumed the one out - extra clicks otherwise landed in the resource list). */
	private int pendingGrowClickH,pendingGrowClickV;private long pendingGrowUntil;private boolean growClickPosted;
	/**
	 * The windows whose program tracks drags of its own (an external's XWindows, set by XWindows68k): a native move of such a
	 * window reaches the program as a click in its drag region, and only DragWindow then moves the window, as on a Mac.
	 * CalendarPallete tells the card "moveCalendar" when DragWindow moved its palette; the native drag moved it silently.
	 */
	public java.util.function.IntPredicate dragClickWindows;
	/** A native move of such a window waiting for that click: the window, its new content place, when it was last reported,
	 *  and the click once posted (where; FindWindow and windowAt answer it as the window's drag region). */
	private int pendingDragWindow,pendingDragLeft,pendingDragTop,pendingDragH,pendingDragV;private long pendingDragAt;private boolean dragClickPosted;

	WindowManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;qd=tb.api.quickDraw();}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x912:return "InitWindows";case 0x913:return "NewWindow";case 0xa45:return "NewCWindow";case 0x9bd:return "GetNewWindow";case 0xa46:return "GetNewCWindow";
			case 0x914:return "DisposeWindow";case 0x92d:return "CloseWindow";case 0x915:return "ShowWindow";case 0x916:return "HideWindow";case 0x908:return "ShowHide";
			case 0x91f:return "SelectWindow";case 0x920:return "BringToFront";case 0x921:return "SendBehind";case 0x924:return "FrontWindow";case 0x92c:return "FindWindow";
			case 0x91a:return "SetWTitle";case 0x919:return "GetWTitle";case 0x918:return "SetWRefCon";case 0x917:return "GetWRefCon";case 0x91b:return "MoveWindow";
			case 0x91d:return "SizeWindow";case 0x925:return "DragWindow";case 0x92b:return "GrowWindow";case 0x91e:return "TrackGoAway";case 0x83b:return "TrackBox";
			case 0x83a:return "ZoomWindow";case 0x91c:return "HiliteWindow";case 0x922:return "BeginUpdate";case 0x923:return "EndUpdate";case 0x928:return "InvalRect";
			case 0x927:return "InvalRgn";case 0x92a:return "ValidRect";case 0x929:return "ValidRgn";case 0x904:return "DrawGrowIcon";case 0x910:return "GetWMgrPort";
			case 0xa48:return "GetCWMgrPort";case 0x80a:return "GetWVariant";case 0x92e:return "SetWindowPic";case 0x92f:return "GetWindowPic";case 0x911:return "CheckUpdate";
			case 0x94e:return "PinRect";case 0x905:return "DragGrayRgn";case 0x926:return "DragTheRgn";case 0x90b:return "ClipAbove";case 0x90e:return "SaveOld";case 0x90f:return "DrawNew";
			case 0x90c:return "PaintOne";case 0x90d:return "PaintBehind";case 0x909:return "CalcVis";case 0x90a:return "CalcVisBehind";case 0xa41:return "SetWinColor";case 0xa42:return "GetAuxWin";
			default:return null;
		}
	}

	/** While the host runs a menu definition procedure offscreen, GetWMgrPort (and WMgrPort $9DE) answer that offscreen port: MDEFs
	 *  draw into the Window Manager port, where the ROM's menu code draws menus on the screen. 0 = the real one. */
	private int wmgrPortOverride;
	void overrideWMgrPort(int port) throws M68kCpuException {wmgrPortOverride=port;memory.writeLong(0x9de,port!=0?port:wmgrClassicPort);memory.writeLong(0xd2c,port!=0?port:wmgrPort);}

	void initializeLowMemory() throws M68kCpuException {
		wmgrPort=memory.allocate(QuickDraw68k_GRAF_PORT_SIZE(),4);
		qd.newWindowPort(wmgrPort,2,2);
		int[] screen=qd.screenBounds(); // the screen programs see (its width may end before the host display's, see QuickDraw68k.MAX_SCREEN_ROW_BYTES)
		// WMgrPort.portRect describes the whole screen for programs that read it.
		memory.writeWord(wmgrPort+16,screen[0]);memory.writeWord(wmgrPort+18,screen[1]);memory.writeWord(wmgrPort+20,screen[2]);memory.writeWord(wmgrPort+22,screen[3]);
		wmgrClassicPort=memory.allocate(QuickDraw68k_GRAF_PORT_SIZE(),4);qd.initializeClassicScreenPort(wmgrClassicPort);
		memory.writeLong(0x9de,wmgrClassicPort);memory.writeLong(0xd2c,wmgrPort); // WMgrPort (classic GrafPort), WMgrCPort (colour); both draw on the screen
		// WMgrCPort.portPixMap is the screen's PixMap on a real Mac: pre-Gestalt games take the screen's depth, bounds, rowBytes
		// and pixels from it (some games insist on "16 colors mode" based on this value)
		if(tb.hosted()){qd.makeScreenPort(wmgrPort);qd.makeScreenPort(wmgrClassicPort);}
		memory.writeLong(0x9d6,0); // WindowList
	}
	private static int QuickDraw68k_GRAF_PORT_SIZE(){return 108;}
	/** The screen changed size (see MacApp68k.changeScreenSize): GrayRgn and the Window Manager ports describe the new one. */
	void screenResized() throws M68kCpuException {
		int[] screen=qd.screenBounds();
		setRegion(memory.readLong(0x9ee),screen[0]+MacAppHost.MENU_BAR_HEIGHT,screen[1],screen[2],screen[3]); // GrayRgn
		if(wmgrPort!=0){memory.writeWord(wmgrPort+16,screen[0]);memory.writeWord(wmgrPort+18,screen[1]);memory.writeWord(wmgrPort+20,screen[2]);memory.writeWord(wmgrPort+22,screen[3]);qd.makeScreenPort(wmgrPort);}
		if(wmgrClassicPort!=0)qd.makeScreenPort(wmgrClassicPort);
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		switch(trap){
			case 0x912: // InitWindows leaves the Window Manager port current (IM I-281): Bee.B. never calls SetPort and draws its backdrop PICT there with global coordinates.
				// A repeat call from code running inside a program that already has windows changes nothing: Apple's license-agreement
				// module (Installer 4 LPic scripts) calls InitWindows right after showing its dialog and then TEStyleNew in that dialog.
				if(memory.readLong(0x9d6)==0&&wmgrPort!=0)setPortTo(wmgrPort);
				memory.writeByte(0x8f2,0);memory.writeWord(0x9dc,0xffff); // WWExist; PaintWhite TRUE
				// DeskCPat: the System's 'ppat' 16 when pCDeskPat asks for a colour desktop (the ROM's InitWindows loads it)
				if((memory.readByte(0x20b)&0x80)!=0&&memory.readLong(0xcd8)==0)memory.writeLong(0xcd8,qd.pixPatResource(16));
				return true;
			case 0x913:case 0xa45:return newWindow(cpu,trap==0xa45);
			case 0x9bd:case 0xa46:return getNewWindow(cpu,trap==0xa46);
			case 0x914:case 0x92d:{int w=pop(cpu);disposeWindow(w);return true;}
			// HyperCard's card window shows and hides as the real one (HideHyper: FrontWindow, HideWindow), like MoveWindow/SizeWindow below
			case 0x915:{int w=pop(cpu);if(isCardRecord(w)){tb.cardWindow.showWindow(true);return true;}show(w,true);return true;}
			case 0x916:{int w=pop(cpu);if(isCardRecord(w)){tb.cardWindow.showWindow(false);return true;}hide(w);return true;}
			case 0x908:{boolean visible=memory.readByte(cpu.a[7])!=0;int w=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;if(isCardRecord(w)){tb.cardWindow.showWindow(visible);return true;}show(w,visible);return true;}
			case 0x91f:case 0x920:{int w=pop(cpu);if(Boolean.getBoolean("hz68k.hosttrace"))System.err.println("[host] "+(trap==0x91f?"SelectWindow":"BringToFront")+" $"+Integer.toHexString(w)+" from pc $"+Integer.toHexString(cpu.pc)+" ret $"+Integer.toHexString(memory.readLong(cpu.a[7])));if(trap==0x91f)select(w);else bringToFront(w);return true;}
			case 0x921:{int behind=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(Boolean.getBoolean("hz68k.hosttrace"))System.err.println("[host] SendBehind $"+Integer.toHexString(w)+" behind $"+Integer.toHexString(behind)+" from pc $"+Integer.toHexString(cpu.pc));Window win=windows.get(w);if(win!=null){behind=behindHyperCardWindow(w,behind);boolean wasActive=win.visible&&!win.floatingLayer&&memory.readByte(w+111)!=0&&activeFront()==w;List<Window> before=new ArrayList<Window>(order);place(win,behind);exposeReordered(win,before);tb.host().placeBehind(w,behind);
				// the active window sent behind another: it is unhighlighted and deactivated and the window now in front is activated, as the ROM (HideWindow's way);
				// NewsWatcher's "Cycle Windows" only calls SendBehind(front, nil) and waits for that activate event to enable its menu items
				if(wasActive&&activeFront()!=w){unhighlight(win);tb.events.postActivate(w,false);activateNext();}}return true;}
			case 0x924:memory.writeLong(cpu.a[7],frontWindowSkippingGhost());if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder("[68K] window list:");for(Window w:order)sb.append(String.format(" $%x%s kind=%d refCon=$%x next=$%x",w.record,w.visible?"":"(hidden)",(short)memory.readWord(w.record+108),memory.readLong(w.record+152),memory.readLong(w.record+144)));System.err.println(sb);}return true;
			case 0x92c:return findWindow(cpu);
			case 0x91a:{int title=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setTitle(w,tb.readPascal(title));return true;}
			case 0x919:{int dst=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;Window win=windows.get(w);tb.writePascal(dst,win!=null?win.title:tb.cardWindow!=null&&tb.cardWindow.isWindowRecord(w)?tb.cardWindow.title(w):"",255);return true;}
			// any window record, as the ROM does: HyperCard's card window too (Rinaldi's FullDrag XFCN keeps its data in the card
			// window's refCon during DragGrayRgn and its actionProc reads it back through GetPort/GetWRefCon)
			case 0x918:{int value=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(windowRecordAddress(w))memory.writeLong(w+152,value);return true;}
			case 0x917:{int w=pop(cpu);memory.writeLong(cpu.a[7],windowRecordAddress(w)?memory.readLong(w+152):0);return true;}
			// HyperCard's card window is no window of this VM: an external moving or sizing it moves or sizes the real one
			// (Dartmouth's SizeCardWindow XCMD: GetPort, then SizeWindow of that port; nothing happened)
			case 0x91b:{boolean front=memory.readByte(cpu.a[7])!=0;int v=(short)memory.readWord(cpu.a[7]+2),h=(short)memory.readWord(cpu.a[7]+4),w=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;if(tb.cardWindow!=null&&w==tb.cardWindow.record){tb.cardWindow.moveWindow(h,v);return true;}move(w,h,v,userDragged(w));if(front)select(w);return true;}
			case 0x91d:{boolean update=memory.readByte(cpu.a[7])!=0;int h=(short)memory.readWord(cpu.a[7]+2),wdt=(short)memory.readWord(cpu.a[7]+4),w=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;if(tb.cardWindow!=null&&w==tb.cardWindow.record){tb.cardWindow.sizeWindow(wdt,h);return true;}resize(w,wdt,h,true,update,true);return true;}
			case 0x925:{int bounds=memory.readLong(cpu.a[7]),start=memory.readLong(cpu.a[7]+4),w=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				if(tb.cardWindow!=null&&w==tb.cardWindow.record){dragCardWindow(start,bounds);return true;}
				if(w!=0&&w==pendingDragWindow&&dragClickPosted){finishPendingDrag();return true;} // the native drag's place
				Window win=windows.get(w);if(win!=null&&win.custom)dragCustom(win,start,bounds);return true;} // DragWindow: the host drags its own frames
			case 0x92b:{int bounds=memory.readLong(cpu.a[7]),pt=memory.readLong(cpu.a[7]+4),w=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;Window win=windows.get(w);
				int result=0;if(tb.cardWindow!=null&&w==tb.cardWindow.record){if(tb.host().isMouseDown())result=growCardWindow(pt,bounds);}
				else if(win!=null&&win.pendingGrowWidth>0){ // a native resize: the host frame's new size, within the program's sizeRect like a tracked grow
					int[] limit=bounds==0?new int[]{16,16,4000,4000}:readRect(bounds);
					int height=Math.max(Math.max(1,limit[0]),Math.min(Math.max(limit[0],limit[2]),win.pendingGrowHeight)),width=Math.max(Math.max(1,limit[1]),Math.min(Math.max(limit[1],limit[3]),win.pendingGrowWidth));
					win.pendingGrowWidth=0;win.pendingGrowHeight=0;if(pendingGrowWindow==w){pendingGrowWindow=0;growClickPosted=false;}
					if(Mac68kApi.TRACE)System.err.println("[68K] GrowWindow $"+Integer.toHexString(w)+" native resize "+win.width+"x"+win.height+" -> "+width+"x"+height);
					result=width==win.width&&height==win.height?0:(height<<16)|(width&0xffff);}
				else if(win!=null&&tb.host().isMouseDown())result=growWindow(win,pt,bounds); // the program tracks a grow itself (its WDEF's wGrow draws the outline)
				memory.writeLong(cpu.a[7],result);return true;}
			case 0x91e:{int pt=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;Window win=windows.get(w);boolean hit;
				if(win!=null&&win.custom)hit=trackCustom(win,4);else{hit=pendingGoAwayWindow==w&&w!=0;pendingGoAwayWindow=0;tb.events.clickTracked();}tb.writeBoolean(cpu.a[7],hit);goAwayTracks++;return true;}
			case 0x83b:{int part=(short)memory.readWord(cpu.a[7]),pt=memory.readLong(cpu.a[7]+2),w=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Window win=windows.get(w);
				tb.writeBoolean(cpu.a[7],win!=null&&win.custom&&trackCustom(win,part==IN_ZOOM_IN?5:6));return true;} // TrackBox: the host's frames have no zoom box; a custom WDEF's is tracked like its close box
			case 0x83a:{boolean front=memory.readByte(cpu.a[7])!=0;int part=(short)memory.readWord(cpu.a[7]+2),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;zoomWindow(w,part,front);return true;}
			case 0x91c:{boolean active=memory.readByte(cpu.a[7])!=0;int w=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Window win=windows.get(w);if(win!=null){boolean was=memory.readByte(w+111)!=0;memory.writeByte(w+111,active?1:0);if(win.custom&&win.visible&&was!=active)drawFrame(win);}return true;}
			case 0x922:{int w=pop(cpu);Window win=windows.get(w);if(win!=null)beginUpdate(win);return true;}
			// EndUpdate: the visRgn goes back to its shape before BeginUpdate - in the coordinates of the port's CURRENT origin. Netscape
			// Communicator's PowerPlant panes call EndUpdate still focused on a subpane (SetOrigin(-32,-84)); the shape restored as
			// saved drifted by that origin at every update, so later wizard pages were clipped away (profile pages 5-6 not drawn)
			case 0x923:{int w=pop(cpu);Window win=windows.get(w);if(win!=null){setRegion(memory.readLong(w+122),0,0,0,0);if(win.updateVis!=null){
				int[] portRect=readRect(w+16);QdRegion saved=win.updateVis;if(portRect[0]!=win.updateOriginV||portRect[1]!=win.updateOriginH)saved=saved.offset(portRect[1]-win.updateOriginH,portRect[0]-win.updateOriginV);
				qd.restoreVisRgn(w,saved);win.updateVis=null;}
				int before=win.portBeforeUpdate;win.portBeforeUpdate=0;
				if(before!=0&&qd.portSets==win.portSetsAtUpdate&&tb.api.currentQuickDrawPort()==w&&windows.containsKey(before))setPortTo(before);}return true;}
			case 0x928:case 0x927:{int shape=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int w=currentWindow();
				CardWindow68k card=tb.cardWindow;int port=tb.api.currentQuickDrawPort();
				if(w==0&&shape!=0&&card!=null&&port==card.record){ // HyperCard's card window: HyperCard updates it after the call (PgFlashX)
					int[] r=trap==0x927?readRegion(shape):readRect(shape),portRect=readRect(port+16);
					card.invalidate(r[0]-portRect[0],r[1]-portRect[1],r[2]-portRect[0],r[3]-portRect[1]);return true;}
				if(w!=0&&shape!=0){int[] r=trap==0x927?readRegion(shape):readRect(shape);
				int[] portRect=readRect(w+16);if(Mac68kApi.TRACE)System.err.println("[68K] "+(trap==0x927?"InvalRgn":"InvalRect")+" "+java.util.Arrays.toString(r)+" portRect "+java.util.Arrays.toString(portRect));
				unionUpdate(w,new int[]{r[0]-portRect[0],r[1]-portRect[1],r[2]-portRect[0],r[3]-portRect[1]});tb.events.postUpdate(w);}return true;} // InvalRect/InvalRgn: port-local (origin applied) -> content-local
			case 0x92a:case 0x929:{int shape=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int w=currentWindow();
				CardWindow68k card=tb.cardWindow;int port=tb.api.currentQuickDrawPort();
				if(w==0&&shape!=0&&card!=null&&port==card.record){
					int[] r=trap==0x929?readRegion(shape):readRect(shape),portRect=readRect(port+16);
					card.validate(r[0]-portRect[0],r[1]-portRect[1],r[2]-portRect[0],r[3]-portRect[1]);return true;}
				if(w!=0&&shape!=0){int[] r=trap==0x929?readRegion(shape):readRect(shape); // ValidRect/ValidRgn
				int[] portRect=readRect(w+16);validateUpdate(w,new int[]{r[0]-portRect[0],r[1]-portRect[1],r[2]-portRect[0],r[3]-portRect[1]},trap==0x929?qd.regionShapeOf(shape).offset(-portRect[1],-portRect[0]):null);}return true;}
			case 0x904:{int w=pop(cpu);Window win=windows.get(w);if(win!=null&&win.custom)callWdef(win,6,0);else drawGrowIcon(w);return true;}
			case 0x910:case 0xa48:{int dst=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(dst!=0)memory.writeLong(dst,wmgrPortOverride!=0?wmgrPortOverride:trap==0x910?wmgrClassicPort:wmgrPort);return true;} // GetWMgrPort / GetCWMgrPort
			case 0x80a:{int w=pop(cpu);Window win=windows.get(w);memory.writeWord(cpu.a[7],win==null?0:win.procId&15);return true;}
			case 0x92e:{int pic=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(windows.containsKey(w))memory.writeLong(w+148,pic);return true;}
			case 0x92f:{int w=pop(cpu);memory.writeLong(cpu.a[7],windows.containsKey(w)?memory.readLong(w+148):0);return true;}
			case 0x911:cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],false);return true;
			case 0x94e:{int pt=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int v=(short)(pt>>16),h=(short)pt;int[] r=readRect(rect);v=Math.max(r[0],Math.min(r[2]-1,v));h=Math.max(r[1],Math.min(r[3]-1,h));memory.writeLong(cpu.a[7],(v&0xffff)<<16|(h&0xffff));return true;}
			case 0x905:return dragGrayRegion(cpu);
			case 0x926:{dragTheRgn=true;try{return dragGrayRegion(cpu);}finally{dragTheRgn=false;}} // DragTheRgn: DragGrayRgn that also calls DragHook ($9F6)
			case 0x90b:case 0x90e:cpu.a[7]+=4;return true; // ClipAbove(window) and SaveOld(window): one WindowPeek each (the clip stays the caller's - windows in front are not subtracted)
			case 0x90f:{int w=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;adoptRecordGeometry(w);Window win=windows.get(w);if(win!=null&&win.reframePending){win.reframePending=false;reframe(win);}return true;} // DrawNew: the program reshaped the record itself
			case 0x90c:case 0x90d:{int rgn=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8; // PaintOne/PaintBehind(window, clobberedRgn)
				if(tb.hosted())tb.host().screenSaverRestored(); // how a screen saver puts the desktop back (After Dark)
				// the card window among the windows painted: HyperCard gets an update event and copies its card back, so what an
				// external drew over the card goes (Apple's UpdateDesktop XCMD after PlotPICT drew on the screen)
				CardWindow68k card=tb.cardWindow;Window start=windows.get(w);
				if(card!=null&&rgn!=0&&(w==card.record||trap==0x90d&&start!=null&&!behindCard(start))){int r=memory.readLong(rgn);
					if(r!=0)card.repaint((short)memory.readWord(r+2),(short)memory.readWord(r+4),(short)memory.readWord(r+6),(short)memory.readWord(r+8));}
				return true;}
			case 0x909:cpu.a[7]+=4;return true;
			case 0x90a:cpu.a[7]+=8;return true;
			// SetWinColor repaints a visible window with PaintOne(window, strucRgn) (WindowMgr.a): the content is erased in the new
			// colour AND added to the update region, so the program draws it again. Kaleidoscope's control panel sets its
			// window colours after drawing; the erased panel stayed empty grey without that update.
			// The same colours again change nothing: Flash 4's Windows layer calls SetWinColor with the table the window already has
			// whenever it paints a dialog, and the erase + update made it paint again forever (Movie Properties stayed blank).
			case 0xa41:{int table=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;byte[] data=table==0?null:tb.api.handleContents(table);
				if(data!=null&&java.util.Arrays.equals(data,windowColorTables.get(w)))return true;
				if(data!=null)applyWindowColors(w,data);
				Window win=windows.get(w);if(data!=null&&win!=null&&win.visible){int[] r=readRect(w+16);invalidate(w,r[0],r[1],r[2],r[3]);}return true;}
			case 0xa42:{int handlePtr=memory.readLong(cpu.a[7]),w=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int aux=auxWindowRecord(w);if(handlePtr!=0)memory.writeLong(handlePtr,aux);tb.writeBoolean(cpu.a[7],aux!=0);return true;} // GetAuxWin(window, VAR awHndl): the window's colour table record (the default one for NIL)
			default:return false;
		}
	}

	private int pop(M68kCpu cpu) throws M68kCpuException {int w=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return w;}

	// ------------------------------------------------------------------ creation

	private boolean newWindow(M68kCpu cpu,boolean color) throws M68kCpuException {
		int refCon=memory.readLong(cpu.a[7]);boolean goAway=memory.readByte(cpu.a[7]+4)!=0;int behind=memory.readLong(cpu.a[7]+6);
		int procId=(short)memory.readWord(cpu.a[7]+10);boolean visible=memory.readByte(cpu.a[7]+12)!=0;
		int title=memory.readLong(cpu.a[7]+14),bounds=memory.readLong(cpu.a[7]+18),storage=memory.readLong(cpu.a[7]+22);cpu.a[7]+=26;
		int[] r=readRect(bounds);if(Mac68kApi.TRACE)System.err.println("[68K] NewWindow bounds t,l,b,r="+r[0]+","+r[1]+","+r[2]+","+r[3]+" procID="+procId);
		int savedPort=tb.api.currentQuickDrawPort();
		int w=create(storage,WINDOW_SIZE,r,tb.readPascal(title),visible,procId,behind,goAway,refCon,color);
		keepCallersPort(savedPort);memory.writeLong(cpu.a[7],w);return true;
	}
	/**
	 * NewWindow, NewCWindow, GetNewWindow and GetNewCWindow leave the caller's port current, as the ROM's window creation does
	 * (Executor's NewWindow saves and restores thePort around OpenPort as well). Alone in the Dark 2 sizes its GetNewCWindow
	 * window to thePort's portRect right after creating it - the Window Manager port, the whole screen - and centres its
	 * 320x200 view in it; with the new window current it stayed at the 512x342 of its 'WIND', the view in its bottom right
	 * corner. Dark Castle's SetModal makes a 1-pixel NewWindow and goes on blitting its frames through thePort, its game window.
	 */
	void keepCallersPort(int savedPort) throws M68kCpuException {if(savedPort!=0&&!"false".equals(System.getProperty("hz68k.newwindowkeepsport")))setPortTo(savedPort);}

	private boolean getNewWindow(M68kCpu cpu,boolean color) throws M68kCpuException {
		int behind=memory.readLong(cpu.a[7]),storage=memory.readLong(cpu.a[7]+4),id=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;
		int savedPort=tb.api.currentQuickDrawPort();
		int w=createFromTemplate(0x57494e44,id,storage,behind,color);keepCallersPort(savedPort);memory.writeLong(cpu.a[7],w);return true;
	}

	/** GetNewWindow's work for a 'WIND'-layout template of any type (HyperCard's GetNewXWindow names the type): 0 when there is none. */
	int createFromTemplate(int type,int id,int storage,int behind,boolean color) throws M68kCpuException {
		ResourceFile68k.Resource wind=tb.resources.find(type,id);
		if(wind==null||wind.length()<18)return 0;
		byte[] d=tb.resources.data(wind);
		int[] bounds={(short)MacApp68k.u2(d,0),(short)MacApp68k.u2(d,2),(short)MacApp68k.u2(d,4),(short)MacApp68k.u2(d,6)};
		int procId=(short)MacApp68k.u2(d,8);boolean visible=MacApp68k.u2(d,10)!=0,goAway=MacApp68k.u2(d,12)!=0;int refCon=MacApp68k.u4(d,14);
		String title="";int n=0;if(d.length>18){n=Math.min(d[18]&0xff,d.length-19);title=tb.decode(java.util.Arrays.copyOfRange(d,19,19+n));}
		int after=19+n+((19+n)&1); // the extended template's positioning word follows the title, on an even offset
		if(after+2<=d.length)bounds=positionTemplate(bounds,MacApp68k.u2(d,after));
		ResourceFile68k.Resource wctb=tb.resources.find(0x77637462,id);
		// The trap decides the port kind, not the 'wctb': GetNewWindow's port is a classic GrafPort even with window colours
		// (Revenge of Monsters takes portBits.bounds at offset 8 of such a window for its direct screen drawing).
		int w=create(storage,WINDOW_SIZE,bounds,title,false,procId,behind,goAway,refCon,color);
		if(w!=0&&wctb!=null)applyWindowColors(w,tb.resources.data(wctb));
		if(w!=0&&color&&type==0x57494e44)tb.quickDraw.attachTemplatePalette(w,id);
		if(w!=0&&visible)show(w,true);
		return w;
	}

	/**
	 * Extended 'WIND' positioning (the word the template carries after its title, as the Dialog Manager reads it from an
	 * extended 'DLOG'): the high byte's $28 centres, $30 takes the alert position (a third of the way down), $38 staggers;
	 * its $80 bit places the window on the parent window - the frontmost one, where the user was working - and $40 on the
	 * parent window's screen (one screen here: the main one); the low byte is always $0A (MacWindows.h
	 * kWindowCenterParentWindow = $A80A, kWindowAlertPositionParentWindowScreen = $700A). GetNewWindow applies it before the
	 * window is shown: without it a window whose template bounds were laid out for a 512x342 screen (Ultimate Spell's main
	 * window, (40,40)-(440,600) with $300A) stays in the top-left corner of a large display, and Flappy Mac's play area
	 * ($A80A) missed the hole in its centred ($280A) backdrop.
	 */
	int[] positionTemplate(int[] bounds,int positioning) throws M68kCpuException {
		int kind=(positioning>>>8)&0x38;
		if((kind!=0x28&&kind!=0x30&&kind!=0x38)||(positioning&0xff)!=0x0a||(positioning&0x0700)!=0)return bounds;
		int[] screen=qd.screenBounds();int w=bounds[3]-bounds[1],h=bounds[2]-bounds[0];
		int top=screen[0]+MacAppHost.MENU_BAR_HEIGHT,left=screen[1],bottom=screen[2],right=screen[3];
		Window parent=(positioning&0x8000)!=0?windows.get(frontWindow()):null;
		int x,y;
		if(parent!=null&&parent.width>0&&parent.height>0){
			if(kind==0x38){x=parent.left+20;y=parent.top+20;}
			else{x=parent.left+(parent.width-w)/2;y=Math.max(top,parent.top+(parent.height-h)/(kind==0x30?3:2));}
		}else if(kind==0x38){x=left+20+windows.size()*20;y=top+20+windows.size()*20;}
		else{x=left+Math.max(0,(right-left-w)/2);y=top+Math.max(0,(bottom-top-h)/(kind==0x30?3:2));}
		if(Mac68kApi.TRACE)System.err.println("[68K] WIND positioning $"+Integer.toHexString(positioning)+": "+bounds[1]+","+bounds[0]+" -> "+x+","+y);
		return new int[]{y,x,y+h,x+w};
	}

	/** AuxWinRec Handles by window (0: the default), each with a 'wctb'-style colour table: content white, frame/text/hilite black, title bar white. */
	private final Map<Integer,Integer> auxRecords=new HashMap<Integer,Integer>();
	private final Map<Integer,byte[]> windowColorTables=new HashMap<Integer,byte[]>();
	private int auxWindowRecord(int w) throws M68kCpuException {
		if(w!=0&&!windows.containsKey(w))w=0;Integer aux=auxRecords.get(w);if(aux!=null)return aux;
		byte[] table=windowColorTables.get(w);
		if(table==null){table=new byte[8+5*8];table[7]=4;int[][] entries={{0,0xffff},{1,0},{2,0},{3,0},{4,0xffff}};
			for(int i=0;i<5;i++){int e=8+i*8;table[e+1]=(byte)entries[i][0];for(int k=2;k<8;k+=2){table[e+k]=(byte)(entries[i][1]>>8);table[e+k+1]=(byte)entries[i][1];}}}
		int ctab=tb.api.createHandle(table);byte[] rec=new byte[28];int handle=tb.api.createHandle(rec);int data=memory.readLong(handle);
		memory.writeLong(data+4,w);memory.writeLong(data+8,ctab);auxRecords.put(w,handle);return handle;
	}
	/** wctb/dctb: part 0 is the content colour; it becomes the port's background and the initial fill. */
	void applyWindowColors(int w,byte[] table) throws M68kCpuException {
		if(table==null||table.length<16)return;windowColorTables.put(w,table);int count=(short)MacApp68k.u2(table,6)+1;
		for(int i=0;i<count&&8+i*8+8<=table.length;i++){int e=8+i*8;if(MacApp68k.u2(table,e)!=0)continue;
			int rgb=(table[e+2]&0xff)<<16|(table[e+4]&0xff)<<8|(table[e+6]&0xff);setContentColor(w,rgb);return;}
	}

	/** SetThemeWindowBackground: the Window Manager erases the content to the brush from now on (when shown, resized, exposed);
	 *  a window not shown yet starts out in it. */
	void setThemeBackground(int w,int rgb) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;win.contentColor=rgb;
		if(!win.everShown)tb.api.quickDraw().fillWindowPort(w,rgb);
	}
	void setContentColor(int w,int rgb) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;win.contentColor=rgb;
		// RGBBackColor on the window's port, then fill the content with it
		int savedPort=tb.api.currentQuickDrawPort();M68kCpu cpu=tb.cpu;int savedSp=cpu.a[7];
		cpu.a[7]-=4;memory.writeLong(cpu.a[7],w);qd.handle(cpu,0x873);
		int color=memory.allocate(6,2);memory.writeWord(color,((rgb>>>16)&255)*257);memory.writeWord(color+2,((rgb>>>8)&255)*257);memory.writeWord(color+4,(rgb&255)*257);
		cpu.a[7]-=4;memory.writeLong(cpu.a[7],color);qd.handle(cpu,0xa15);memory.free(color);
		cpu.a[7]=savedSp;
		if(savedPort!=0){cpu.a[7]-=4;memory.writeLong(cpu.a[7],savedPort);qd.handle(cpu,0x873);cpu.a[7]=savedSp;}
		tb.api.quickDraw().noteWindowBackground(w,rgb&0xffffff);
		tb.api.quickDraw().fillWindowPort(w,rgb);
	}

	/** The content erased to the window's background: its tile when it has one, else its colour. */
	private void eraseContent(int w,Window win) throws M68kCpuException {eraseContent(w,win,new int[]{0,0,Integer.MAX_VALUE,Integer.MAX_VALUE});}
	/** The same in the content-local rectangle {@code r} {top,left,bottom,right}. */
	private void eraseContent(int w,Window win,int[] r) throws M68kCpuException {
		int[] t=win.contentTile;
		if(t==null||!tb.api.quickDraw().fillWindowPortTile(w,java.util.Arrays.copyOfRange(t,2,t.length),t[0],t[1],r[0],r[1],r[2],r[3]))tb.api.quickDraw().fillWindowPort(w,win.contentColor,r[0],r[1],r[2],r[3]);
	}
	/** A 'ppat' as the window's background: its port's bkPixPat (the program's EraseRect) and what the content is erased to. */
	void setContentPattern(int w,byte[] ppat) throws M68kCpuException {
		Window win=windows.get(w);if(win==null||ppat==null)return;
		KaleidoscopeScheme68k.Img img=KaleidoscopeScheme68k.decodePpat(ppat);if(img==null)return;
		int[] t=new int[2+img.argb.length];t[0]=img.w;t[1]=img.h;System.arraycopy(img.argb,0,t,2,img.argb.length);win.contentTile=t;
		int pattern=tb.api.quickDraw().pixPatFromResource(ppat);tb.api.quickDraw().setBackPixPat(w,pattern);
		eraseContent(w,win);
	}

	/** Creates a window record (dialogs pass a larger record size). */
	/** The 'WDEF' Handle of a procID (procID div 16), as the Window Manager stores it in windowDefProc; the frame itself is drawn natively. */
	private int definitionProcedure(int procId) throws M68kCpuException {
		Integer registered=registeredDefs.get(procId>>4);if(registered!=null)return registered;
		ResourceFile68k.Resource res=tb.resources.find(0x57444546,procId>>4);
		if(res==null)res=tb.resources.system.get(0x57444546,0);
		return res==null?0:tb.resources.handleFor(res);
	}

	int create(int storage,int recordSize,int[] bounds,String title,boolean visible,int procId,int behind,boolean goAway,int refCon,boolean color) throws M68kCpuException {
		int width=bounds[3]-bounds[1],height=bounds[2]-bounds[0];
		// An inverted bounds rect makes a window with an empty content too (the Window Manager does not check it): Apple's Movie
		// XCMD 1.2 opens its windoid as (v,h)-(v/2,h/2) and sizes it to the movie afterwards (Myst Preview)
		if(width<0)width=0;if(height<0)height=0;
		// An empty content rect is legal (Play it Cool opens sound-only movies in a 160x0 window and grows it for the controller): give it a 1-pixel port until SizeWindow.
		// A window's buffer covers its content, but no more than a screen-sized one (plus room to drag it about): MIDIGraphy's
		// document is a DLOG of (0,0)-(9999,9999) it sizes afterwards, which asked for 400 MB of pixels and ran out of heap.
		int[] screenArea=qd.screenBounds();int limitW=Math.max(2048,screenArea[3]-screenArea[1]),limitH=Math.max(2048,screenArea[2]-screenArea[0]);
		width=Math.max(1,Math.min(width,limitW));height=Math.max(1,Math.min(height,limitH));
		int record=storage!=0?storage:memory.allocate(Math.max(WINDOW_SIZE,recordSize),4);
		memory.fill(record,Math.max(WINDOW_SIZE,recordSize),0);
		// NIL wStorage: the record is a Ptr in the heap, as the ROM's NewPtr makes it - Dialoger's dChanger checks the DialogPtr it
		// is handed with GetPtrSize (170 on a Mac) and refused the dialog when it got 0/paramErr
		if(storage==0)tb.api.adoptPointer(record,Math.max(WINDOW_SIZE,recordSize));
		qd.newWindowPort(record,width,height,!color); // NewWindow: the record also carries the classic portBits.bounds
		Window win=new Window(record);win.ownRecord=storage==0;win.left=bounds[1];win.top=bounds[0];win.width=width;win.height=height;win.procId=procId;win.goAway=goAway;win.title=title;win.color=true;
		memory.writeWord(record+108,8);memory.writeByte(record+110,0);memory.writeByte(record+111,0);memory.writeByte(record+112,goAway?1:0);
		memory.writeLong(record+114,regionHandle(bounds[0]-20,bounds[1]-1,bounds[2]+1,bounds[3]+1));
		memory.writeLong(record+118,regionHandle(bounds[0],bounds[1],bounds[2],bounds[3]));
		memory.writeLong(record+122,regionHandle(bounds[0],bounds[1],bounds[2],bounds[3])); // updateRgn is global like strucRgn/contRgn
		memory.writeLong(record+126,definitionProcedure(procId)); // windowDefProc: the WDEF Handle (the program's own for a custom procID, else the System stub)
		win.createdDefProc=memory.readLong(record+126);
		memory.writeLong(record+134,tb.api.createHandle(tb.readPascalBytesOf(title)));
		memory.writeWord(record+138,Fonts68k.width(Fonts68k.font(0,12,1),title));
		memory.writeLong(record+152,refCon);
		if((procId>>4)==0&&((procId&15)==8||(procId&15)==12)){ // zoomDocProc/zoomNoGrow: dataHandle holds the WStateData (userState = where it is, stdState = the whole screen below the menu bar)
			int[] screen=qd.screenBounds();byte[] state=new byte[16];int[] std={screen[0]+MacAppHost.MENU_BAR_HEIGHT+22,screen[1]+3,screen[2]-3,screen[3]-3};
			for(int i=0;i<4;i++){state[i*2]=(byte)(bounds[i]>>8);state[i*2+1]=(byte)bounds[i];state[8+i*2]=(byte)(std[i]>>8);state[8+i*2+1]=(byte)std[i];}
			memory.writeLong(record+130,tb.api.createHandle(state));win.zoomState=true;}
		windows.put(record,win);windowsCreated++;
		behind=behindHyperCardWindow(record,behind);
		place(win,behind);
		qd.describeWindowPixMap(record); // the port's PixMap record describes the screen at the window's position, as on a real Mac
		win.dialogFlavor=dialogFrameFlavor(procId,bounds);
		win.shownTitle=shownTitle(win);
		if(win.dialogFlavor<0){KaleidoscopeScheme68k.WindowFrame theme=themedFrame(procId,bounds);if(visible)win.theme=theme;else win.pendingTheme=theme;}
		if(customWdef(procId)||win.dialogFlavor>=0||win.theme!=null)prepareCustomFrame(win,true);
		int[] px=qd.windowPortPixels(record);
		win.shownTitle=shownTitle(win);
		if(visible)tb.host().willShow(record);
		if(win.custom){tb.host().createWindow(record,win.left+win.frameLeft,win.top+win.frameTop,win.frameWidth,win.frameHeight,win.shownTitle,procId,false,goAway,px[0],px[1],px[4],true);if(win.frameShape!=null)tb.host().setWindowShape(record,hostShape(win.frameShape));}
		else tb.host().createWindow(record,win.left,win.top,width,height,win.shownTitle,procId,false,goAway,px[0],px[1],px[4]);
		if(behind!=-1)tb.host().placeBehind(record,behind); // the host stacks new windows in front; NewWindow's behind argument says otherwise
		// Erase to the window's background (white) so nothing stale shows before the first update.
		tb.api.quickDraw().fillWindowPort(record,0xffffff);
		if(visible)show(record,true);else qd.setWindowPortVisible(record,false); // an invisible window's visRgn is empty until it is shown
		// The new window's port is current for the rest of the creation (OpenPort); the NewWindow traps give the caller's back
		// ({@link #keepCallersPort}), the Toolbox's own windows (dialogs, alerts, Standard File) draw in theirs from here on.
		setPortTo(record);
		return record;
	}

	/** CloseWindow/DisposeWindow. A record the Window Manager allocated is freed by both: on a Mac CloseWindow leaves it to the
	 *  program's DisposePtr, which finds nothing to do here then, and the external that never disposes it leaves no block
	 *  behind that would keep its VM alive. */
	void disposeWindow(int w) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		boolean wasActive=win.visible&&activeFront()==w&&memory.readByte(w+111)!=0;
		// the frames it uncovers are drawn once the host window is gone (its screen writes are dropped then: MacAppHost.disposeWindow)
		deferExposedFrames=true;try{if(win.visible)exposeBehind(win,structureBounds(win));}finally{deferExposedFrames=false;}
		windows.remove(w);if(win.ownState!=0)memory.free(win.ownState);
		Integer aux=auxRecords.remove(w);if(aux!=null){int data=memory.readLong(aux);if(data!=0)tb.api.disposeHandleAndFree(memory.readLong(data+8));tb.api.disposeHandleAndFree(aux);}windowColorTables.remove(w);
		order.remove(win);refreshLinks();
		tb.controls.killControls(w);
		tb.dialogs.windowDisposed(w);
		tb.events.discardWindowEvents(w); // a disposed window must not leave a pending activate/update event behind
		tb.host().disposeWindow(w);
		drawExposedFrames();
		for(int off:new int[]{114,118,122,134}){int h=memory.readLong(w+off);if(h!=0)tb.api.disposeHandleAndFree(h);}
		if(win.zoomState){int h=memory.readLong(w+130);if(h!=0)tb.api.disposeHandleAndFree(h);}
		boolean wasCurrent=qd.currentPortAddress()==w;
		qd.disposeWindowPort(w);
		// The ROM's CloseWindow leaves the Window Manager port current when the closed window was thePort:
		// Some programs dispose a colour-depth dialog and size their main window from thePort->portRect - through a NIL
		// thePort it got an inverted rectangle, no window, and later a "memory error" alert.
		if(wasCurrent&&wmgrPort!=0)setPortTo(wmgrPort);
		if(pendingGoAwayWindow==w)pendingGoAwayWindow=0;
		if(wasActive)activateNext();
		if(win.ownRecord)disposePointer(w);
	}
	/** DisposePtr through the Memory Manager (the Ptr's size entry goes, so an external that disposed its dialog leaves no block behind). */
	private void disposePointer(int p) throws M68kCpuException {
		M68kCpu cpu=tb.cpu;if(cpu==null||p==0)return;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{cpu.a[0]=p;tb.api.handle(cpu,0xa01f,cpu.pc);}catch(M68kCpuException e){throw e;}catch(M68kException e){}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	/** The record of HyperCard's card window (not a window of this VM's list). */
	private boolean isCardRecord(int w){return w!=0&&tb.cardWindow!=null&&w==tb.cardWindow.record;}
	/**
	 * The active front window went away (DisposeWindow, HideWindow): the ROM highlights the window now in front and makes it
	 * the next activation, as SelectWindow would. PowerPlant relies on it: DragThing's dock stayed inactive, stripes and boxes
	 * gone, after its registration dialog was disposed.
	 */
	private void activateNext() throws M68kCpuException {
		int front=activeFront();Window next=windows.get(front);
		if(next==null||memory.readByte(front+111)!=0)return;
		memory.writeByte(front+111,1);if(next.custom)drawFrame(next);
		tb.quickDraw.windowActivated(front);tb.events.postActivate(front,true);
	}

	// ------------------------------------------------------------------ state changes

	/** TrackGoAway calls and windows created so far: a program that did either after a close-box click answered it
	 *  (EventManager68k.closeIgnoredGoAway), even if the "save changes?" dialog it opened is gone again. */
	int goAwayTracks,windowsCreated;

	/** The colours DeskPattern is drawn in (a quiet grey texture, as the colour Finder's default desktop). */
	static final int DESK_FORE=0x7f7f8c,DESK_BACK=0xa6a6b3;
	void show(int w,boolean visible) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		boolean was=win.visible;win.visible=visible;memory.writeByte(w+110,visible?1:0);
		if(visible){
			// A program that shaped a hidden window by editing the record (Nisus Writer 4.03: portRect right/bottom and the
			// PixMap bounds written by its own window code, no SizeWindow) gets the window that shape when it is shown, as after DrawNew.
			if(!was&&!win.custom&&!win.everShown&&!"false".equals(System.getProperty("hz68k.adoptonshow")))adoptRecordGeometry(w);
			// The ROM asks a WDEF for its regions when the window becomes visible: MoveWindow/SizeWindow of a hidden window skip
			// wCalcRgns, and WDEFs themselves ignore it while invisible (some palettes are created hidden, moved, then shown).
			boolean adopted=!was&&!win.custom&&swappedDefinition(win);
			if(adopted){win.theme=null;win.pendingTheme=null;win.dialogFlavor=-1;tb.host().setCustomFrame(w);}
			// A window created hidden takes the scheme's frame now, where the program has put it: JumpStart 1st Grade 2001 opens its
			// window hidden at its WIND's size, then moves and sizes it over the whole screen (title bar out of sight) and shows it.
			// Framed at NewWindow, the host kept the drawn title bar on the display, inside the frame round the game screen.
			boolean themed=false;
			if(!was&&win.pendingTheme!=null){KaleidoscopeScheme68k.WindowFrame theme=win.pendingTheme;win.pendingTheme=null;
				if(!coversScreen(new int[]{win.top,win.left,win.top+win.height,win.left+win.width})){win.theme=theme;themed=true;tb.host().setCustomFrame(w);}}
			if(!was&&(win.custom||adopted||themed)){prepareCustomFrame(win,false);int[] px=qd.windowPortPixels(w);tb.host().resizeWindow(w,win.frameWidth,win.frameHeight,px[0],px[1],px[4]);tb.host().moveWindow(w,win.left+win.frameLeft,win.top+win.frameTop);}
			// A window shown again after being hidden gets its content erased to the background (the real
			// Window Manager's PaintOne does this) before the update event asks the program to redraw it.
			// With PaintWhite FALSE the Window Manager leaves the content alone: on a real Mac the window then shows the screen that
			// was under it. Bryce 2 clears PaintWhite around ShowWindow of its dialogs and pop-up palettes, then darkens those
			// pixels for its drop shadows; with white buffers every dialog sat on a white box.
			boolean paintWhite=memory.readWord(0x9dc)!=0;
			if(!was&&!paintWhite&&!tb.isDesktopWindow(w)&&tb.hosted())qd.fillWindowFromScreen(w); // a custom WDEF draws its frame over it below
			else if(!was&&win.everShown)eraseContent(w,win);
			if(!was&&!win.everShown)qd.setWindowPortVisible(w,true); // the first ShowWindow computes the visRgn (empty since NewWindow)
			// the Finder's desktop window (its content is the desktop layer's region): the desk shows through it, painted with DeskPattern
			if(!was&&!win.everShown&&tb.isDesktopWindow(w)){qd.fillWindowPattern(w,memory.readBytes(0xa3c,8),DESK_FORE,DESK_BACK);if(tb.hosted())tb.host().setIgnoresDeviceWrites(w,true);}
			tb.host().showWindow(w);if(!was){tb.events.postUpdate(w);unionUpdate(w,new int[]{0,0,win.height,win.width});}if(!win.everShown){win.everShown=true;}
			// the ROM's ShowWindow highlights a window that comes up in front and unhighlights the one it covers - that one only:
			// DragThing shows its dock without ever selecting it, and the dock's title bar stayed inactive. Programs with their own
			// floating palettes keep the front document highlighted under them (Photoshop 3 left its document inactive after
			// every filter dialog when showing the dialog unhighlighted all windows).
			if(win.floatingLayer){if(!was)memory.writeByte(w+111,1);} // the floating layer is active along with the front document
			// The window coming up in front is the next activation (CurActivate): one still queued for another window is over. A modal
			// dialog's DisposeDialog made the window under it the next activation; Wingz then opens the chosen document, and that
			// activate event (taken after the document's window was up) made it bring its old sheet back in front of the document.
			else if(!was&&activeFront()==w){memory.writeByte(w+111,1);Window covered=nextVisibleDocument(win);if(covered!=null)unhighlight(covered);tb.events.dropActivations(w);}
			if(win.custom)drawFrame(win);
			if(!was&&!win.floatingLayer&&activeFront()==w)tb.quickDraw.windowActivated(w);}
		else{tb.host().hideWindow(w);if(was)exposeBehind(win,structureBounds(win));}
		if(was!=visible)calcVis();
	}
	/** HideWindow: ShowHide(false), and when the window was the active front one, it is unhighlighted and deactivated and the
	 *  window now in front takes over (IM Toolbox Essentials 4-78). ShowHide itself leaves highlighting alone. */
	void hide(int w) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		boolean wasActive=win.visible&&memory.readByte(w+111)!=0&&activeFront()==w;
		show(w,false);
		if(wasActive){memory.writeByte(w+111,0);tb.events.postActivate(w,false);activateNext();}
	}

	void select(int w) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		// The ROM keeps one pending activation (CurActivate) and SelectWindow overwrites it: an activate event still queued for
		// the window selected before is never delivered. Classic Gin Rummy selects its table, then its shareware dialog; the
		// table's late activate event made its handler select the table again, over the dialog it was waiting on.
		tb.events.dropActivations(w);
		Window previous=windows.get(activeFront());
		List<Window> before=new ArrayList<Window>(order);order.remove(win);
		// a document goes in front of the other documents, under the Carbon floating layer
		int at=0;if(!win.floatingLayer)while(at<order.size()&&order.get(at).floatingLayer)at++;
		order.add(at,win);refreshLinks();exposeRaised(win,before);
		if(win.floatingLayer)previous=null; // selecting a floating window leaves the document active
		// HiliteWindow(old front, false) and HiliteWindow(win, true), as the ROM: other windows keep their highlighting
		if(previous!=null&&previous!=win)unhighlight(previous);
		if(memory.readByte(w+111)!=1){memory.writeByte(w+111,1);if(win.custom&&win.visible)drawFrame(win);}
		tb.host().selectWindow(w);if(win.visible)tb.quickDraw.windowActivated(w);
		// the ROM's SelectWindow sets CurActivate whether or not the window is visible: some programs select a hidden
		// design window and only shows it from the activate event that follows
		else{
			// and its deactivate event for the window that was in front comes first (CurDeactive before CurActivate): the host would
			// post it only when the new window is shown, after this activate - Nisus Writer takes that order as "no active document"
			// and never draws the update of the document window it opens beside another one (it came up blank).
			if(previous!=null&&previous!=win&&previous.visible&&!previous.service&&tb.hosted()){MacAppHost h=tb.host();var ph=h.window(previous.record);if(ph!=null)h.deactivateHost(ph);}
			tb.events.postActivate(w,true);
		}
	}

	/**
	 * The title the host shows: translated (VMTranslation) when the program took it from its own resources (a WIND, a DLOG,
	 * a string list). A title it made up itself is usually a document's name, which is the user's and stays as it is.
	 */
	String shownTitle(Window win){
		String title=win.title==null?"":win.title;
		if(title.isEmpty()||!tb.translationKnows(title))return title;
		String t=tb.translated(VMTranslation.Kind.UI,title);
		return t==null?title:t;
	}
	/** Whether a window of the program has this title (a document's name in a Windows menu). */
	boolean hasWindowTitled(String title){
		for(Window win:new ArrayList<Window>(windows.values()))if(title.equals(win.title))return true;
		return false;
	}
	/** Translations arrived or translation was turned on or off: the host title bars show the titles again. */
	void retitle() throws M68kCpuException {
		for(Map.Entry<Integer,Window> e:new ArrayList<Map.Entry<Integer,Window>>(windows.entrySet())){Window win=e.getValue();
			String shown=shownTitle(win);if(shown.equals(win.shownTitle))continue;
			win.shownTitle=shown;tb.host().setTitle(e.getKey(),shown);
			if(win.custom&&win.visible)drawFrame(win);}
	}

	void setTitle(int w,String title) throws M68kCpuException {
		Window win=windows.get(w);if(win==null){if(tb.cardWindow!=null)tb.cardWindow.setTitle(w,title);return;}win.title=title;
		int old=memory.readLong(w+134);if(old!=0)tb.api.disposeHandleAndFree(old);
		memory.writeLong(w+134,tb.api.createHandle(tb.readPascalBytesOf(title)));
		memory.writeWord(w+138,Fonts68k.width(Fonts68k.font(0,12,1),title)); // titleWidth, as NewWindow sets it: Resorcerer's WDEF 64 centres the title in titleWidth pixels (0 = a 12-pixel gap, no title)
		win.shownTitle=shownTitle(win);tb.host().setTitle(w,win.shownTitle);
		if(win.custom&&!floaterDef(win.procId>>4))calcCustomRegions(win); // like the ROM's SetWTitle: the WDEF lays the title out again
		if(win.theme!=null&&win.visible)drawFrame(win);
		if(win.custom)drawFrame(win); // the frame carries the title
	}

	/** DrawNew: a program that sizes or moves a window by editing the record (HyperCard's card window: portBits.bounds
	 *  offset, portRect right/bottom written, then SaveOld / its WDEF's wCalcRgns / CalcVis / DrawNew) expects the window
	 *  to be that shape afterwards; the host window follows the record here, and the whole content is invalidated like the
	 *  real DrawNew's newly exposed area. */
	private void adoptRecordGeometry(int w) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		int[] r=readRect(w+16);int width=r[3]-r[1],height=r[2]-r[0];
		int[] pos=qd.classicWindowPosition(w);
		boolean moved=pos!=null&&(pos[0]!=win.left||pos[1]!=win.top),sized=width>0&&height>0&&(width!=win.width||height!=win.height);
		if(Mac68kApi.TRACE&&(moved||sized))System.err.println("[68K] DrawNew $"+Integer.toHexString(w)+": record says "+(pos==null?"":pos[0]+","+pos[1]+" ")+width+"x"+height+", window was "+win.left+","+win.top+" "+win.width+"x"+win.height);
		if(moved)move(w,pos[0],pos[1]);
		if(sized)resize(w,width,height,true,true);
	}

	/** Extended-resource auto positioning on the main screen: 1 = centred, 2 = alert position (a third of the way down), 3 = staggered. */
	void autoPosition(int w,int where) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		int[] screen=qd.screenBounds();int top=screen[0]+MacAppHost.MENU_BAR_HEIGHT,left=screen[1],bottom=screen[2],right=screen[3];
		int frameW=win.width-Math.min(0,win.frameLeft)*2,frameH=win.height-Math.min(0,win.frameTop);
		int x=left+Math.max(0,(right-left-frameW)/2),y;
		if(where==2)y=top+Math.max(0,(bottom-top-frameH)/3);else if(where==3){x=left+20+windows.size()*20;y=top+20+windows.size()*20;}else y=top+Math.max(0,(bottom-top-frameH)/2);
		move(w,x-Math.min(0,win.frameLeft),y-Math.min(0,win.frameTop));
	}

	void move(int w,int left,int top) throws M68kCpuException {move(w,left,top,false);}
	/** {@code byUser}: the user drags it (DragWindow of a frame drawn here): the host lets it go anywhere on the display. */
	void move(int w,int left,int top,boolean byUser) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		int[] pending=readRegion(memory.readLong(w+122));if(pending[2]>pending[0]&&pending[3]>pending[1])setRegion(memory.readLong(w+122),pending[0]+top-win.top,pending[1]+left-win.left,pending[2]+top-win.top,pending[3]+left-win.left); // the global updateRgn moves with the window
		int[] old=win.visible&&(left!=win.left||top!=win.top)?structureBounds(win):null;
		win.left=left;win.top=top;qd.describeWindowPixMap(w);updateRegions(win);
		if(old!=null)exposeBehind(win,old); // the record's portBits.bounds first: a WDEF computes its regions from it
		tb.host().moveWindow(w,left+win.frameLeft,top+win.frameTop,byUser);if(byUser)rememberUserState(win);
		calcVis();
	}
	/** WStateData.userState follows the window the USER moves or resizes while it is not in its standard state (so zooming in
	 *  returns to where the user left it; ZoomWindow saves it too when zooming out). A program's own MoveWindow/SizeWindow leave
	 *  it alone, as the ROM does: CS ChemDraw 3.5 writes the place for a new document into userState, calls SizeWindow and then
	 *  MoveWindow to userState's corner - rewritten by SizeWindow, the window went to 0,0 with its title bar off the screen. */
	private void rememberUserState(Window win) throws M68kCpuException {
		if(!win.zoomState)return;int h=memory.readLong(win.record+130),data=h==0?0:memory.readLong(h);if(data==0)return;
		int[] std=readRect(data+8);boolean inStd=std[0]==win.top&&std[1]==win.left&&std[2]==win.top+win.height&&std[3]==win.left+win.width;
		if(!inStd)writeRect(data,win.top,win.left,win.top+win.height,win.left+win.width);
	}
	/**
	 * A window's WStateData {userState, stdState}: the one its dataHandle holds, else - a window whose definition keeps none
	 * (dataHandle NIL, or a program's own WDEF using it for its private data) - one the Window Manager keeps for it, made when
	 * {@code create}. Carbon's Set/GetWindowStandardState and ZoomWindow work on any window: DragThing sizes its docks (a WDEF
	 * of its own) with SetWindowStandardState + ZoomWindow, and they stayed at their WIND's 300x200. 0 when there is none.
	 */
	int stateData(int w,boolean create) throws M68kCpuException {
		Window win=windows.get(w);
		int h=memory.readLong(w+130);
		if(win==null)return h==0?0:memory.readLong(h);
		if(h!=0&&(win.zoomState||!win.custom)&&tb.api.handleSize(h)>=16)return memory.readLong(h);
		if(win.ownState==0&&create){win.ownState=memory.allocate(16,2);writeRect(win.ownState,win.top,win.left,win.top+win.height,win.left+win.width);writeRect(win.ownState+8,win.top,win.left,win.top+win.height,win.left+win.width);}
		return win.ownState;
	}
	/** ZoomWindow(window, partCode, front): the window takes the rectangle of its WStateData ({@link #stateData}) - stdState for
	 *  inZoomOut, userState for inZoomIn - and its content is redrawn from scratch like a real zoom. */
	void zoomWindow(int w,int part,boolean front) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		int data=stateData(w,false);if(data==0)return;
		int[] r=readRect(data+(part==IN_ZOOM_OUT?8:0));int width=r[3]-r[1],height=r[2]-r[0];if(width<=0||height<=0)return;
		if(part==IN_ZOOM_OUT)writeRect(data,win.top,win.left,win.top+win.height,win.left+win.width); // where to come back to
		if(Mac68kApi.TRACE)System.err.println("[68K] ZoomWindow $"+Integer.toHexString(w)+" part "+part+" -> "+r[1]+","+r[0]+" "+width+"x"+height);
		boolean zoomState=win.zoomState;win.zoomState=false; // the states are being applied, not recorded
		try{if(r[1]!=win.left||r[0]!=win.top)move(w,r[1],r[0]);if(width!=win.width||height!=win.height)resize(w,width,height,true,true);else{unionUpdate(w,new int[]{0,0,height,width});tb.events.postUpdate(w);}}
		finally{win.zoomState=zoomState;}
		if(front&&frontWindow()!=w)select(w);
	}

	/** Host reported that the user moved the window. */
	void hostMoved(int w,int left,int top){Window win=windows.get(w);if(win==null)return;
		// a move report that leaves the window where it is (the host showed or placed its frame) is no drag: its made-up click on
		// the title bar opened UxBack's File menu on the backdrop of FR&セシル's Lecture, and the menu held the script
		if(left-win.frameLeft==win.left&&top-win.frameTop==win.top&&(pendingDragWindow!=w||dragClickPosted))return;
		java.util.function.IntPredicate tracked=dragClickWindows;
		if(tracked!=null&&tracked.test(w)&&win.visible&&!win.custom){
			if(pendingDragWindow!=0&&pendingDragWindow!=w)finishPendingDrag();
			pendingDragWindow=w;pendingDragLeft=left;pendingDragTop=top;pendingDragAt=System.currentTimeMillis();return;}
		applyHostMove(w,left,top);
	}
	/** A native move of a window whose program tracks its drags ({@link #dragClickWindows}) has settled: a click in its drag
	 *  region (on the title bar, where the window still is for the program) goes to it. Called before its events are pumped. */
	public void postPendingDragClick(){
		Window win=pendingDragWindow==0?null:windows.get(pendingDragWindow);
		if(win==null){pendingDragWindow=0;dragClickPosted=false;return;}
		long now=System.currentTimeMillis();
		if(dragClickPosted||now-pendingDragAt<250)return; // macOS reports a drag move by move: one click at its end
		pendingDragH=win.left+Math.max(1,Math.min(win.width/2,40));pendingDragV=win.top-3;dragClickPosted=true;
		tb.events.postMouse(true,pendingDragH,pendingDragV,0,now);
		tb.events.postMouse(false,pendingDragH,pendingDragV,0x80,now);
	}
	/** The click went to the program, which did not call DragWindow (or nothing took it): the window takes its new place now. */
	public void finishPendingDrag(){
		int w=pendingDragWindow;if(w==0)return;
		pendingDragWindow=0;dragClickPosted=false;
		if(windows.containsKey(w))applyHostMove(w,pendingDragLeft,pendingDragTop);
	}
	/** While a handler runs HyperCard hands its windows no clicks: a settled native move just takes effect. */
	public void settlePendingDrag(){if(pendingDragWindow!=0&&!dragClickPosted&&System.currentTimeMillis()-pendingDragAt>=250)finishPendingDrag();}
	/** Whether a native drag of a window is waiting for its click ({@link #postPendingDragClick}). */
	public boolean dragClickOut(){return pendingDragWindow!=0&&dragClickPosted;}
	private boolean isDragClick(int h,int v){return dragClickPosted&&pendingDragWindow!=0&&h==pendingDragH&&v==pendingDragV&&windows.containsKey(pendingDragWindow);}
	private void applyHostMove(int w,int left,int top){Window win=windows.get(w);if(win==null)return;int newLeft=left-win.frameLeft,newTop=top-win.frameTop;
		try{int[] pending=readRegion(memory.readLong(w+122));if(pending[2]>pending[0]&&pending[3]>pending[1])setRegion(memory.readLong(w+122),pending[0]+newTop-win.top,pending[1]+newLeft-win.left,pending[2]+newTop-win.top,pending[3]+newLeft-win.left);
			int oldLeft=win.left,oldTop=win.top;
			if((newLeft!=oldLeft||newTop!=oldTop)&&tb.hosted())tb.host().windowMovedByUser(w);
			win.left=newLeft;win.top=newTop;qd.describeWindowPixMap(w);updateRegions(win);rememberUserState(win);
			exposeFromOffScreen(w,win,oldLeft,oldTop);calcVis();}catch(M68kCpuException ignored){win.left=newLeft;win.top=newTop;}}
	/**
	 * The host moved a window onto the display by itself (grown past the screen's edge, placed partly off it): the program
	 * learns it at its next event, possibly in the middle of drawing with the old position - iTunes' backdrop, decompressed
	 * by QuickTime straight to the screen, landed 60 pixels off after a grow. The whole content is drawn again.
	 */
	void hostPlaced(int w,int left,int top){
		Window win=windows.get(w);if(win==null)return;int oldLeft=win.left,oldTop=win.top;applyHostMove(w,left,top);
		if(win.visible&&(win.left!=oldLeft||win.top!=oldTop))try{unionUpdate(w,new int[]{0,0,win.height,win.width});tb.events.postUpdate(w);}catch(M68kCpuException ignored){}
	}
	/**
	 * A move that brings content from beyond the screen onto it (the host keeps a window grown past the screen's edge on the
	 * display) exposes what the program never drew there: like MoveWindow, that part joins the update region. iTunes clips its
	 * backdrop to the gray region; grown past the right edge and moved back, its window kept an unpainted white strip.
	 */
	private void exposeFromOffScreen(int w,Window win,int oldLeft,int oldTop) throws M68kCpuException {
		if(!win.visible||oldLeft==win.left&&oldTop==win.top)return;
		int[] screen=tb.api.quickDraw().screenBounds();
		// the content's on-screen part before and after, in local coordinates
		int[] before={Math.max(0,screen[0]-oldTop),Math.max(0,screen[1]-oldLeft),Math.min(win.height,screen[2]-oldTop),Math.min(win.width,screen[3]-oldLeft)};
		int[] after={Math.max(0,screen[0]-win.top),Math.max(0,screen[1]-win.left),Math.min(win.height,screen[2]-win.top),Math.min(win.width,screen[3]-win.left)};
		if(after[2]<=after[0]||after[3]<=after[1])return;
		boolean hadNone=before[2]<=before[0]||before[3]<=before[1];
		if(!hadNone&&after[0]>=before[0]&&after[1]>=before[1]&&after[2]<=before[2]&&after[3]<=before[3])return; // nothing new on screen
		int[] exposed=after.clone();
		if(!hadNone){ // the band(s) of 'after' outside 'before' (the update region is kept as a bounding box anyway)
			if(after[1]>=before[1]&&after[3]<=before[3]){exposed[0]=after[0]<before[0]?after[0]:before[2];exposed[2]=after[2]>before[2]?after[2]:before[0];if(after[0]<before[0]&&after[2]>before[2]){exposed[0]=after[0];exposed[2]=after[2];}}
			else if(after[0]>=before[0]&&after[2]<=before[2]){exposed[1]=after[1]<before[1]?after[1]:before[3];exposed[3]=after[3]>before[3]?after[3]:before[1];if(after[1]<before[1]&&after[3]>before[3]){exposed[1]=after[1];exposed[3]=after[3];}}
		}
		if(exposed[2]<=exposed[0]||exposed[3]<=exposed[1])return;
		int[] portRect=readRect(w+16);
		expose(win,new int[]{exposed[0],exposed[1],exposed[2],exposed[3]});
		if(Mac68kApi.TRACE)System.err.println("[68K] window $"+Integer.toHexString(w)+" moved on screen: exposed "+java.util.Arrays.toString(exposed)+" portRect origin "+portRect[1]+","+portRect[0]);
	}

	/** The main screen's PixMap changed (created, new depth): every window's PixMap record describes it anew. */
	void describePixMaps() throws M68kCpuException {for(int w:new ArrayList<Integer>(windows.keySet()))qd.describeWindowPixMap(w);}

	/** Host reported a native resize: tell the application via inGrow/GrowWindow/SizeWindow. */
	void hostResized(int w,int width,int height) throws M68kException {
		Window win=windows.get(w);if(win==null||(width==win.width&&height==win.height))return;
		if(Mac68kApi.TRACE||Boolean.getBoolean("hz68k.hosttrace"))System.err.println("[68K] host resized window $"+Integer.toHexString(w)+" "+win.width+"x"+win.height+" -> "+width+"x"+height);
		long now=System.currentTimeMillis();
		boolean clickOut=growClickPosted&&pendingGrowWindow==w&&now<pendingGrowUntil;
		if(pendingGrowWindow!=0&&pendingGrowWindow!=w){Window other=windows.get(pendingGrowWindow);if(other!=null){other.pendingGrowWidth=0;other.pendingGrowHeight=0;}}
		win.pendingGrowWidth=width;win.pendingGrowHeight=height;pendingGrowWindow=w;pendingGrowUntil=now+3000;
		// A real Window Manager only shows an outline here. The window changes size if the application handles the
		// resulting inGrow hit, calls GrowWindow and then SizeWindow. Restore the native frame until that happens
		// (the host frame is only resizable once the program has drawn a grow icon, but it may still refuse a size).
		int[] px=qd.windowPortPixels(w);tb.host().resizeWindow(w,win.width,win.height,px[0],px[1],px[4]);
		if(clickOut)return; // the click already out will report this latest size
		int h=win.left+win.width-8,v=win.top+win.height-8;pendingGrowClickH=h;pendingGrowClickV=v;growClickPosted=true;
		tb.events.postMouse(true,h,v,0,now);
		tb.events.postMouse(false,h,v,0x80,now);
	}

	/** Every visible window gets an update event for all of its content (the smooth zoom changed: the program draws its text into the new planes). */
	void redrawAll() throws M68kCpuException {redrawAll(java.util.Collections.<Integer>emptySet());}
	/** The same, the windows in {@code erase} erased to their background first (translations replace the text drawn there). */
	void redrawAll(java.util.Set<Integer> erase) throws M68kCpuException {
		for(Map.Entry<Integer,Window> e:new ArrayList<Map.Entry<Integer,Window>>(windows.entrySet())){Window win=e.getValue();
			if(win.visible){if(erase.contains(e.getKey()))eraseContent(e.getKey(),win);unionUpdate(e.getKey(),new int[]{0,0,win.height,win.width});tb.events.postUpdate(e.getKey());}}
	}
	/** The main screen changed depth (SetDepth): window buffers are rebuilt at the new depth, as a real Mac redraws every window. */
	void screenDepthChanged() throws M68kCpuException {
		for(Map.Entry<Integer,Window> e:new ArrayList<Map.Entry<Integer,Window>>(windows.entrySet())){int w=e.getKey();Window win=e.getValue();
			int[] before=qd.windowPortPixels(w);int depth=qd.windowDepth();if(before!=null&&before.length>4&&before[4]==depth){qd.describeWindowPixMap(w);continue;}
			qd.resizeWindowPort(w,win.width,win.height,win.contentColor);int[] px=qd.windowPortPixels(w);
			tb.host().resizeWindow(w,win.width,win.height,px[0],px[1],px[4]);
			if(win.visible){unionUpdate(w,new int[]{0,0,win.height,win.width});tb.events.postUpdate(w);}}
	}

	void resize(int w,int width,int height,boolean fromApplication,boolean update) throws M68kCpuException {resize(w,width,height,fromApplication,update,false);}
	/** @param exposedOnly SizeWindow: only the content newly uncovered goes into the update region (else all of it) */
	void resize(int w,int width,int height,boolean fromApplication,boolean update,boolean exposedOnly) throws M68kCpuException {
		Window win=windows.get(w);if(win==null||width<=0||height<=0)return;
		if(width>4000||height>4000){
			// A window far larger than any screen is a backdrop (WaterRace: MoveWindow -16000,-16000 then SizeWindow 32000 x 32000,
			// to cover every screen): it keeps the part on the screen, as only that can be seen, rather than a buffer of 4000 x 4000.
			java.awt.Rectangle screen=MacAppHost.screenBounds();
			int left=Math.max(win.left,screen.x),top=Math.max(win.top,screen.y),right=(int)Math.min((long)win.left+width,screen.x+screen.width),bottom=(int)Math.min((long)win.top+height,screen.y+screen.height);
			if(right>left&&bottom>top){if(left!=win.left||top!=win.top)move(w,left,top);width=right-left;height=bottom-top;}
		}
		width=Math.min(width,4000);height=Math.min(height,4000);
		boolean changed=width!=win.width||height!=win.height;int oldWidth=win.width,oldHeight=win.height;
		int[] old=changed&&win.visible?structureBounds(win):null;
		win.width=width;win.height=height;
		if(fromApplication){win.pendingGrowWidth=0;win.pendingGrowHeight=0;if(pendingGrowWindow==w){pendingGrowWindow=0;growClickPosted=false;}}
		if(changed){
			if(win.custom){prepareCustomFrame(win,false);int[] px=qd.windowPortPixels(w);tb.host().resizeWindow(w,win.frameWidth,win.frameHeight,px[0],px[1],px[4]);tb.host().moveWindow(w,win.left+win.frameLeft,win.top+win.frameTop);if(win.visible)drawFrame(win);}
			else{
				qd.resizeWindowPort(w,width,height,win.contentColor); // newly exposed content is erased to the window's content colour (PaintOne)
				int[] px=qd.windowPortPixels(w);
				tb.host().resizeWindow(w,width,height,px[0],px[1],px[4]);
				updateRegions(win);
			}
			tb.dialogs.windowResized(w);if(!fromApplication)rememberUserState(win);
			if(old!=null)exposeBehind(win,old); // a shrinking window uncovers the ones behind
			calcVis();
		}else if(fromApplication){
			// SizeWindow sets portRect from its top left whatever size the window had: MetaCard writes the NewCWindow bounds'
			// right/bottom into portRect (886x522 for its 424x24 message box) and relies on the SizeWindow that follows
			int[] r=readRect(w+16);
			if(r[2]-r[0]!=height||r[3]-r[1]!=width){memory.writeWord(w+20,r[0]+height);memory.writeWord(w+22,r[1]+width);}
		}
		// SizeWindow(fUpdate) adds only the content it newly uncovers to the update region (IM Toolbox Essentials 4-104); the program
		// invalidates what else it moves. With all the content added, SoundApp's playlist (PowerPlant) erased its column header on
		// every grow and never redrew it - the header pane had not changed. (The region is kept as a bounding box: growing both
		// ways still covers the whole window.)
		if(update){
			if(!exposedOnly||oldWidth<=0||oldHeight<=0){unionUpdate(w,new int[]{0,0,height,width});tb.events.postUpdate(w);}
			else if(width>oldWidth||height>oldHeight){
				int[] r=width>oldWidth&&height>oldHeight?new int[]{0,0,height,width}:width>oldWidth?new int[]{0,oldWidth,height,width}:new int[]{oldHeight,0,height,width};
				unionUpdate(w,r);tb.events.postUpdate(w);}}
	}

	private void updateRegions(Window win) throws M68kCpuException {
		if(win.custom){calcCustomRegions(win);return;}
		int[] out=win.variant<0?STANDARD_OUTSETS[0]:standardOutsets(win.variant);
		setRegion(memory.readLong(win.record+114),win.top-out[0],win.left-out[1],win.top+win.height+out[2],win.left+win.width+out[3]);
		setRegion(memory.readLong(win.record+118),win.top,win.left,win.top+win.height,win.left+win.width);
	}
	/** Top, left, bottom, right frame widths of WDEF 0 variants: document windows 20/1/1/1, dBoxProc 8, plainDBox 1, altDBoxProc 1 with a 2-pixel shadow. */
	private static final int[][] STANDARD_OUTSETS={{20,1,1,1},{8,8,8,8},{1,1,1,1},{1,1,3,3}};
	private static int[] standardOutsets(int varCode){varCode&=15;return STANDARD_OUTSETS[varCode>=1&&varCode<=3?varCode:0];}

	// ------------------------------------------------------------------ custom WDEFs

	/** Whether a procID names a WDEF of the program's own (not the System's 0/1): its code then runs for the window's frame. */
	/** WDEFs a Carbon program registered by resource ID (RegisterWindowDefinition): a Handle whose master pointer is its WindowDefUPP. */
	private final Map<Integer,Integer> registeredDefs=new HashMap<Integer,Integer>();
	void registerDefinition(int id,int handle){if(handle==0)registeredDefs.remove(id);else registeredDefs.put(id,handle);}
	private boolean customWdef(int procId){
		if(CUSTOM_WDEF_DISABLED)return false;int id=procId>>4;if(registeredDefs.containsKey(id))return true;if(id==0||id==1)return false;
		ResourceFile68k.Resource res=tb.resources.find(0x57444546,id);return res!=null&&!tb.resources.isSystem(res)||floaterDef(id);
	}
	/** WDEF 66/67 (and the System 7.5 floating windows of WDEF 124, which Appearance draws the same way) without a definition
	 *  of the program's own: the Appearance floating windows, drawn by {@link #floater}. */
	private boolean floaterDef(int id){
		if(id!=66&&id!=67&&id!=124||registeredDefs.containsKey(id))return false;
		ResourceFile68k.Resource res=tb.resources.find(0x57444546,id);return res==null||tb.resources.isSystem(res);
	}

	/**
	 * The Appearance Manager's floating windows (kWindowFloat*Proc 1057..1071, WDEF 66, title bar on top; kWindowFloatSide*Proc
	 * 1073..1087, WDEF 67, title bar on the left) as the Mac OS 8/9 WDEF draws them: a Platinum utility frame (AppearanceManager68k.
	 * Platinum flavors 8/9) around the content, drawn here instead of a host title bar. DragThing's docks are such windows and
	 * came up as ordinary macOS windows with a full-size title bar. Variation code: bit 1 grow box, bit 2 vertical zoom, bit 3
	 * horizontal zoom (as WDEF 64's bits 0-2); the close box follows goAwayFlag. Messages as a WDEF's (0 draw, 1 hit, 2 regions,
	 * 5 grow outline, 6 grow box); the part being tracked is drawn pressed.
	 */
	/** Java2D straight into the window's buffer, which covers its frame, within the global rectangle {@code r} (the painter draws in global coordinates). */
	private void paintFrame(Window win,int[] r,AppearanceManager68k.Painter p) throws M68kCpuException {
		// the surface is in the port's local coordinates: global - window position + the portRect's top-left (the origin). A
		// control panel's window has its origin at 87,-1 (ControlPanelHost68k) and its title bar came out 87 pixels to the left.
		int[] pr=readRect(win.record+16);int top=win.top-pr[0],left=win.left-pr[1];
		java.awt.Graphics2D g=tb.quickDraw.frameSurface(win.record,new int[]{r[0]-top,r[1]-left,r[2]-top,r[3]-left});if(g==null)return;
		// the Platinum helpers draw as on a fresh image (AppearanceManager68k.paint): normalized strokes, no antialiasing
		g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,java.awt.RenderingHints.VALUE_STROKE_DEFAULT);g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
		try{g.translate(-left,-top);p.paint(g);}finally{g.dispose();}
		tb.quickDraw.flushSurfaces();
	}
	private int floater(Window win,int message,int param) throws M68kCpuException {
		int id=win.procId>>4,v=win.procId&15;
		// WDEF 124 (floatProc 1985 .. floatSideZoomGrowProc 1999): bit 1 grow box, bit 2 zoom box, bit 3 title bar on the side.
		// Flash 4's Windows layer sizes its tool palette by the strucRgn-contRgn difference; WDEF 0's dBoxProc (8-pixel frame)
		// stood in and the two-column palette came out 43 pixels wide, its second column cut off
		final int flavor=id==124?((v&8)!=0?9:8):id==67?9:8;if(id==124)v&=6;
		int attributes=(win.goAway?0x20:0)|((v&4)!=0?0x10:0)|((v&8)!=0?0x08:0)|((v&2)!=0?1:0)|(flavor==8?0x80:0);
		int[] c={win.top,win.left,win.top+win.height,win.left+win.width};
		switch(message){
			case 0:{ // wDraw: the frame, or (param = a part) that part's box drawn pressed or back up
				if(!win.visible)return 0;
				boolean active=memory.readByte(win.record+111)!=0;
				if(param==4||param==5||param==6){int widget=param==4?0:1;int[] box=AppearanceManager68k.Platinum.widget(flavor,c,attributes,widget);
					if(box!=null&&active){boolean pressed=win.pressedPart!=param;win.pressedPart=pressed?param:0;paintFrame(win,box,g->AppearanceManager68k.Platinum.widgetBox(g,box,widget,pressed));}
					return 0;}
				String own=win.title==null?"":win.title,title=shownTitle(win);
				java.awt.Font font=own.equals(title)?Fonts68k.font(Fonts68k.numberOf(tb.encoding==StackTextEncoding.JAPANESE?"Osaka":"Geneva"),9,0)
					:Fonts68k.hostFont(0,9,0); // a translation: a host font has its characters
				java.awt.FontMetrics fm=Fonts68k.metrics(font);
				int[] text=flavor==8&&!title.isEmpty()?AppearanceManager68k.Platinum.titleText(flavor,c,attributes,Fonts68k.width(font,title)+2,fm.getAscent()+fm.getDescent()):null;
				paintFrame(win,AppearanceManager68k.Platinum.structure(flavor,c),g->{
					AppearanceManager68k.Platinum.windowFrame(g,flavor,c,active?1:0,attributes,text);
					if(text!=null){g.setColor(new java.awt.Color(active?0:0x888888));java.awt.Shape clip=g.getClip();g.clipRect(text[1],text[0],text[3]-text[1],text[2]-text[0]);
						g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
						Fonts68k.drawString(g,font,title,text[1]+1,text[0]+(text[2]-text[0]-fm.getAscent()-fm.getDescent())/2+fm.getAscent());g.setClip(clip);}
				});
				return 0;}
			case 1:{ // wHit
				int pv=(short)(param>>16),ph=(short)param;
				int code=AppearanceManager68k.Platinum.regionHit(flavor,c,attributes,pv,ph);
				switch(code){
					case 2:return 4;                                                         // wInGoAway
					case 3:{int data=stateData(win.record,false);boolean standard=false; // wInZoomIn when the window is in its standard state
						if(data!=0){int[] s=readRect(data+8);standard=java.util.Arrays.equals(s,c);}return standard?5:6;}
					case 6:return 3;                                                         // wInGrow
					case 33:return 1;                                                        // wInContent
					case 5:case 0:return 2;                                                  // wInDrag: the title bar and the frame
					default:return code<0?0:2;
				}}
			case 2:{ // wCalcRgns
				int[] s=AppearanceManager68k.Platinum.structure(flavor,c);
				setRegion(memory.readLong(win.record+114),s[0],s[1],s[2],s[3]);setRegion(memory.readLong(win.record+118),c[0],c[1],c[2],c[3]);
				return 0;}
			case 5:{ // wGrow: the outline of the frame the window would have at the rectangle param points to, with the port's pen
				if(param==0)return 0;int[] r=readRect(param);int[] s=AppearanceManager68k.Platinum.structure(flavor,r);
				int rect=memory.allocate(8,2);M68kCpu cpu=tb.cpu;int saved=cpu.a[7];
				try{writeRect(rect,s[0],s[1],s[2],s[3]);cpu.a[7]-=4;memory.writeLong(cpu.a[7],rect);qd.handle(cpu,0x8a1); // FrameRect
					writeRect(rect,r[0],r[1],r[2],r[3]);cpu.a[7]=saved-4;memory.writeLong(cpu.a[7],rect);qd.handle(cpu,0x8a1);}
				catch(M68kException e){throw e instanceof M68kCpuException?(M68kCpuException)e:new M68kCpuException(cpu.pc,e.getMessage());}
				finally{cpu.a[7]=saved;memory.free(rect);}
				return 0;}
			case 6:{ // wDrawGIcon: the grow box in the content's corner
				if((v&2)==0||!win.visible)return 0;int[] box={c[2]-15,c[3]-15,c[2],c[3]};int state=memory.readByte(win.record+111)!=0?1:0;
				paintFrame(win,box,g->AppearanceManager68k.Platinum.growBox(g,box,true,state));return 0;}
			default:return 0; // wNew, wDispose
		}
	}
	private static final boolean CUSTOM_WDEF_DISABLED=Boolean.getBoolean("hz68k.nowdef");
	private static final boolean NATIVE_DIALOG_FRAMES=!"false".equals(System.getProperty("hz68k.dialogframes"));
	/**
	 * The System WDEF 0's dialog frames, drawn here around the content as Mac OS 8/9 draws them (the Platinum modal frame for
	 * dBoxProc, outline and shadow for altDBoxProc) rather than as lines over the content's edge pixels. Not for a window that
	 * covers the screen (a game's backdrop) or with -Dhz68k.dialogframes=false. Returns the flavor, or -1.
	 */
	private int dialogFrameFlavor(int procId,int[] bounds){
		if(!NATIVE_DIALOG_FRAMES||(procId>>4)!=0)return -1;int kind=procId&15;if(kind!=1&&kind!=3)return -1;
		int[] screen=qd.screenBounds();if(bounds[1]<=screen[1]&&bounds[3]>=screen[3]&&bounds[0]<=screen[0]+MacAppHost.MENU_BAR_HEIGHT&&bounds[2]>=screen[2]-MacAppHost.MENU_BAR_HEIGHT)return -1;
		return kind==1?1:6;
	}
	/** WDEF messages of a natively drawn dialog frame: the frame (into the buffer, and over it for the host in true greys), regions, hits. */
	private int dialogFrame(Window win,int message,int param) throws M68kCpuException {
		final int flavor=win.dialogFlavor;final int[] c={win.top,win.left,win.top+win.height,win.left+win.width};
		switch(message){
			case 0:{
				if(!win.visible||param!=0)return 0;
				final int[] s=AppearanceManager68k.Platinum.structure(flavor,c);
				paintFrame(win,s,g->AppearanceManager68k.Platinum.windowFrame(g,flavor,c,1,0,null));
				// the buffer has the screen's depth: on a 1-bit screen the frame's greys round to black or white there, so the host
				// shows the frame drawn in colour over it
				java.awt.image.BufferedImage overlay=new java.awt.image.BufferedImage(Math.max(1,s[3]-s[1]),Math.max(1,s[2]-s[0]),java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D g=overlay.createGraphics();
				try{g.translate(-s[1],-s[0]);AppearanceManager68k.Platinum.windowFrame(g,flavor,c,1,0,null);}finally{g.dispose();}
				tb.host().setOverlay(win.record,overlay);
				return 0;}
			case 1:{int pv=(short)(param>>16),ph=(short)param;return pv>=c[0]&&pv<c[2]&&ph>=c[1]&&ph<c[3]?1:0;} // wInContent, else wNoHit
			case 2:{ // wCalcRgns: the outline's rectangle and its shadow's
				int[] s=AppearanceManager68k.Platinum.structure(flavor,c);
				QdRegion struc=flavor==1?QdRegion.combine(QdRegion.rect(s[0],s[1],s[2]-1,s[3]-1),QdRegion.rect(s[0]+2,s[1]+2,s[2],s[3]),QdRegion.UNION)
					:QdRegion.combine(QdRegion.rect(s[0],s[1],s[2]-2,s[3]-2),QdRegion.rect(s[0]+2,s[1]+2,s[2],s[3]),QdRegion.UNION);
				int handle=memory.readLong(win.record+114);if(handle!=0)qd.writeRegionShape(handle,struc);
				setRegion(memory.readLong(win.record+118),c[0],c[1],c[2],c[3]);
				return 0;}
			default:return 0;
		}
	}
	// ------------------------------------------------------------------ Kaleidoscope document windows

	/** -Dhz68k.kaleidoscope.windows=false: documents keep the host's own frames whatever the scheme. */
	private static final boolean THEMED_WINDOWS=!"false".equals(System.getProperty("hz68k.kaleidoscope.windows"));
	/**
	 * The document window frame of the Kaleidoscope scheme in use for a new window, or null. With a scheme, Kaleidoscope draws
	 * every document window (WDEF 0's documentProc, noGrowDocProc, zoomDocProc, zoomNoGrow; the Appearance WDEF 64 documents) in
	 * its own frame: such a window is framed here like a custom WDEF's - the frame drawn into its buffer, the title bar, close and
	 * zoom boxes and grow box hit and tracked by the Window Manager - instead of with a host title bar. Not for a program's own
	 * WDEF, nor a window covering the screen (a game's backdrop, its title bar out of sight).
	 */
	private KaleidoscopeScheme68k.WindowFrame themedFrame(int procId,int[] bounds){
		if(!THEMED_WINDOWS)return null;int id=procId>>4,kind=procId&15;
		if(!(id==0&&(kind==0||kind==4||kind==8||kind==12)||id==64&&kind<8)||customWdef(procId))return null;
		if(coversScreen(bounds))return null;
		KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);return scheme==null?null:scheme.documentWindow();
	}
	/** Whether a content rect {top, left, bottom, right} covers the whole screen, its top at most the menu bar's height down. */
	private boolean coversScreen(int[] bounds){
		int[] screen=qd.screenBounds();
		return bounds[1]<=screen[1]&&bounds[3]>=screen[3]&&bounds[0]<=screen[0]+MacAppHost.MENU_BAR_HEIGHT&&bounds[2]>=screen[2]-MacAppHost.MENU_BAR_HEIGHT;
	}
	/** The WDEF 0 variant the themed window is drawn as now: the procID's, or the one the program switched it to by calling the
	 *  System's WDEF itself (HyperCard's `hide titleBar`: plainDBox); -1 for an Appearance (WDEF 64) document. */
	private static int themedKind(Window win){return win.variant>=0?win.variant:(win.procId>>4)==64?-1:win.procId&15;}
	/** The widgets the themed window has: the close box as the record's goAwayFlag says now (HyperCard sets it after NewWindow), the zoom box by variant. */
	private int themedWidgets(Window win) throws M68kCpuException {
		int k=themedKind(win),v=win.procId&15;boolean goAway=memory.readByte(win.record+112)!=0;
		if(k<0)return (goAway?KaleidoscopeScheme68k.CLOSE:0)|((v&6)!=0?KaleidoscopeScheme68k.ZOOM:0);
		return (goAway&&k!=5?KaleidoscopeScheme68k.CLOSE:0)|((k&8)!=0?KaleidoscopeScheme68k.ZOOM:0);
	}
	private static boolean themedGrows(Window win){int k=themedKind(win);return k<0?(win.procId&1)!=0:k==0||k==8;}
	private static java.awt.Font themedTitleFont(Window win){String own=win.title==null?"":win.title;return own.equals(win.shownTitle)?Fonts68k.font(0,12,0):Fonts68k.hostFont(0,12,0);}
	private KaleidoscopeScheme68k.FrameLayout themedLayout(Window win) throws M68kCpuException {return win.theme.layout(win.width,win.height,win.shownTitle==null?"":win.shownTitle,themedWidgets(win),themedTitleFont(win));}
	/** The frame for the window's state, with the grow box over its corner once the program drew it (it may reach into the frame). */
	private java.awt.image.BufferedImage themedImage(Window win,KaleidoscopeScheme68k.FrameLayout l) throws M68kCpuException {
		boolean active=memory.readByte(win.record+111)!=0;
		int pressed=win.pressedPart==4?KaleidoscopeScheme68k.CLOSE:win.pressedPart==5||win.pressedPart==6?KaleidoscopeScheme68k.ZOOM:0;
		java.awt.image.BufferedImage img=win.theme.paint(l,win.shownTitle==null?"":win.shownTitle,active,pressed,themedTitleFont(win));
		KaleidoscopeScheme68k.Img grow=win.themeGrow&&themedGrows(win)?win.theme.growBox(active):null;
		if(grow!=null){Graphics2D g=img.createGraphics();try{g.drawImage(grow.image,l.outsets[1]+l.contentWidth-15,l.outsets[0]+l.contentHeight-15,null);}finally{g.dispose();}}
		return img;
	}
	/** WDEF messages of a document window in the Kaleidoscope scheme's frame ({@link #themedFrame}). */
	private int themedWdef(Window win,int message,int param) throws M68kCpuException {
		final int[] c={win.top,win.left,win.top+win.height,win.left+win.width};
		int kind=themedKind(win);
		if(kind==1||kind==2||kind==3){ // switched to a dialog frame by the program: the Platinum modal frame, outline and shadow, or outline
			final int flavor=kind==1?1:kind==3?6:7;final int[] s=AppearanceManager68k.Platinum.structure(flavor,c);
			switch(message){
				case 0:{if(!win.visible||param!=0)return 0;
					paintFrame(win,s,g->AppearanceManager68k.Platinum.windowFrame(g,flavor,c,1,0,null));
					java.awt.image.BufferedImage overlay=new java.awt.image.BufferedImage(Math.max(1,s[3]-s[1]),Math.max(1,s[2]-s[0]),java.awt.image.BufferedImage.TYPE_INT_ARGB);
					Graphics2D g=overlay.createGraphics();try{g.translate(-s[1],-s[0]);AppearanceManager68k.Platinum.windowFrame(g,flavor,c,1,0,null);}finally{g.dispose();}
					tb.host().setOverlay(win.record,overlay);return 0;}
				case 1:{int pv=(short)(param>>16),ph=(short)param;return pv>=c[0]&&pv<c[2]&&ph>=c[1]&&ph<c[3]?1:0;}
				case 2:setRegion(memory.readLong(win.record+114),s[0],s[1],s[2],s[3]);setRegion(memory.readLong(win.record+118),c[0],c[1],c[2],c[3]);return 0;
				default:return 0;
			}
		}
		boolean active=memory.readByte(win.record+111)!=0;
		switch(message){
			case 0:{ // wDraw: the frame; wInGoAway/wInZoomIn/wInZoomOut: that box goes down or back up
				if(!win.visible)return 0;
				if(param==4||param==5||param==6){boolean pressed=win.pressedPart!=param;win.pressedPart=pressed?param:0;}else if(param!=0)return 0;
				KaleidoscopeScheme68k.FrameLayout l=themedLayout(win);final java.awt.image.BufferedImage img=themedImage(win,l);win.themeImage=img;
				final int sx=c[1]-l.outsets[1],sy=c[0]-l.outsets[0];
				paintFrame(win,new int[]{sy,sx,sy+l.height,sx+l.width},g->g.drawImage(img,sx,sy,null));
				tb.host().setOverlay(win.record,img); // the buffer has the screen's depth: the host shows the scheme's colours over it
				return 0;}
			case 1:{ // wHit
				int pv=(short)(param>>16),ph=(short)param;
				if(pv>=c[0]&&pv<c[2]&&ph>=c[1]&&ph<c[3])return themedGrows(win)&&ph>=c[3]-15&&pv>=c[2]-15?3:1; // wInGrow / wInContent
				KaleidoscopeScheme68k.FrameLayout l=themedLayout(win);int x=ph-(c[1]-l.outsets[1]),y=pv-(c[0]-l.outsets[0]);
				if(x<0||y<0||x>=l.width||y>=l.height)return 0;
				if(active){
					for(int[] b:l.close)if(y>=b[0]&&y<b[2]&&x>=b[1]&&x<b[3])return 4; // wInGoAway
					for(int[] b:l.zoom)if(y>=b[0]&&y<b[2]&&x>=b[1]&&x<b[3]){int data=stateData(win.record,false);boolean standard=data!=0&&java.util.Arrays.equals(readRect(data+8),c);return standard?5:6;} // wInZoomIn / wInZoomOut
				}
				java.awt.image.BufferedImage img=win.themeImage;
				if(img!=null&&img.getWidth()==l.width&&img.getHeight()==l.height&&(img.getRGB(x,y)>>>24)==0)return 0; // a clear corner of the scheme's frame
				return 2;} // wInDrag: the title bar and the frame
			case 2:{ // wCalcRgns: the frame's pixels (a scheme's round corners and notches) and the content
				KaleidoscopeScheme68k.FrameLayout l=themedLayout(win);int sx=c[1]-l.outsets[1],sy=c[0]-l.outsets[0];
				java.awt.image.BufferedImage img=win.themeImage;
				if(img==null||img.getWidth()!=l.width||img.getHeight()!=l.height){img=themedImage(win,l);win.themeImage=img;}
				java.util.List<int[]> rows=new java.util.ArrayList<int[]>();boolean rect=true;int[] argb=img.getRGB(0,0,l.width,l.height,null,0,l.width);
				for(int y=0;y<l.height;y++){java.util.List<Integer> xs=new java.util.ArrayList<Integer>();boolean in=false;
					for(int x=0;x<=l.width;x++){boolean on=x<l.width&&((argb[y*l.width+x]>>>24)!=0||y>=l.outsets[0]&&y<l.outsets[0]+l.contentHeight&&x>=l.outsets[1]&&x<l.outsets[1]+l.contentWidth);
						if(on!=in){xs.add(x+sx);in=on;}if(x<l.width&&!on)rect=false;}
					int[] a=new int[xs.size()];for(int i=0;i<a.length;i++)a[i]=xs.get(i);rows.add(a);}
				int struc=memory.readLong(win.record+114);
				if(rect)setRegion(struc,sy,sx,sy+l.height,sx+l.width);else if(struc!=0)qd.writeRegionShape(struc,QdRegion.fromRows(sy,rows));
				setRegion(memory.readLong(win.record+118),c[0],c[1],c[2],c[3]);
				return 0;}
			case 5:{ // wGrow: the outline of the frame the window would have at the rectangle param points to, with the port's pen
				if(param==0)return 0;int[] r=readRect(param);int[] o=win.theme.outsets();
				int rect=memory.allocate(8,2);M68kCpu cpu=tb.cpu;int saved=cpu.a[7];
				try{writeRect(rect,r[0]-o[0],r[1]-o[1],r[2]+o[2],r[3]+o[3]);cpu.a[7]-=4;memory.writeLong(cpu.a[7],rect);qd.handle(cpu,0x8a1); // FrameRect
					writeRect(rect,r[0],r[1],r[2],r[3]);cpu.a[7]=saved-4;memory.writeLong(cpu.a[7],rect);qd.handle(cpu,0x8a1);}
				catch(M68kException e){throw e instanceof M68kCpuException?(M68kCpuException)e:new M68kCpuException(cpu.pc,e.getMessage());}
				finally{cpu.a[7]=saved;memory.free(rect);}
				return 0;}
			case 6:{ // wDrawGIcon: the scroll bar lines and the scheme's grow box in the content's corner
				if(!themedGrows(win)||!win.visible)return 0;
				KaleidoscopeScheme68k.Img grow=win.theme.growBox(active);
				drawGrowIcon(win.record,grow);
				if(grow!=null&&!win.themeGrow){win.themeGrow=true;KaleidoscopeScheme68k.FrameLayout l=themedLayout(win);java.awt.image.BufferedImage img=themedImage(win,l);win.themeImage=img;tb.host().setOverlay(win.record,img);}
				return 0;}
			default:return 0; // wNew, wDispose
		}
	}
	/** The themed window's frame changed shape (the program switched its variant): regions, buffer and host window follow. */
	private void reframe(Window win) throws M68kCpuException {
		int[] old=win.visible?structureBounds(win):null;win.themeImage=null;
		prepareCustomFrame(win,false);int[] px=qd.windowPortPixels(win.record);
		tb.host().resizeWindow(win.record,win.frameWidth,win.frameHeight,px[0],px[1],px[4]);tb.host().moveWindow(win.record,win.left+win.frameLeft,win.top+win.frameTop);
		if(win.visible){drawFrame(win);if(old!=null)exposeBehind(win,old);}
	}
	/** Calls into a window's definition from {@link #callWdef} under way (a WDEF 0 reached through them is not the program switching variants). */
	private int wdefCalls;
	/** pascal long WDEF(short varCode, WindowPtr theWindow, short message, long param), in the Window Manager port. */
	int callWdef(Window win,int message,int param) throws M68kCpuException {
		if(win.theme!=null){int savedPort=tb.api.currentQuickDrawPort();
			try{setPortTo(wmgrPort);return themedWdef(win,message,param);}finally{if(savedPort!=0)setPortTo(savedPort);}}
		if(win.dialogFlavor>=0){int savedPort=tb.api.currentQuickDrawPort();
			try{setPortTo(wmgrPort);return dialogFrame(win,message,param);}finally{if(savedPort!=0)setPortTo(savedPort);}}
		if(floaterDef(win.procId>>4)){int savedPort=tb.api.currentQuickDrawPort(); // the grow outline draws in the Window Manager port
			try{setPortTo(wmgrPort);return floater(win,message,param);}finally{if(savedPort!=0)setPortTo(savedPort);}}
		int defProc=memory.readLong(win.record+126);if(defProc==0)return 0;
		if(memory.readLong(defProc)==0&&!registeredDefs.containsValue(defProc)){ResourceFile68k.Resource res=tb.resources.find(0x57444546,win.procId>>4);if(res==null||!tb.resources.loadIfResource(defProc))return 0;}
		int entry=memory.readLong(defProc);if(entry==0)return 0;
		tb.api.setHandleState(defProc,tb.api.handleState(defProc)|0x80);
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int savedPort=tb.api.currentQuickDrawPort();
		try{
			setPortTo(wmgrPort);
			cpu.a[7]-=4;cpu.pushWord(win.variant>=0&&(win.procId>>4)==0?win.variant:win.procId&15);cpu.pushLong(win.record);cpu.pushWord(message);cpu.pushLong(param);
			wdefCalls++;try{cpu.callPascal(entry,12);}finally{wdefCalls--;}
			int result=memory.readLong(cpu.a[7]);
			if(Mac68kApi.TRACE)System.err.printf("[68K] WDEF %d message %d window $%x param $%x -> %d (visible %d hilited %d)%n",win.procId>>4,message,win.record,param,result,memory.readByte(win.record+110),memory.readByte(win.record+111));
			return result;
		}catch(M68kException e){throw e instanceof M68kCpuException?(M68kCpuException)e:new M68kCpuException(cpu.pc,"WDEF "+(win.procId>>4)+": "+e.getMessage());}
		finally{if(savedPort!=0)setPortTo(savedPort);System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	private void setPortTo(int port) throws M68kCpuException {M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}
	/** A definition proc a Carbon program registered (CreateCustomWindow) that reports kWindowCanGetWindowRegion to
	 *  kWindowMsgGetFeatures (7) answers kWindowMsgGetRegion (8) for the structure and content regions and may ignore wCalcRgns
	 *  (Acrobat 5's SimpleFrameDef for its tool bar: without this its regions stayed stale and the document window was placed
	 *  below a 362-pixel "tool bar"). False when the definition does not offer that. */
	private boolean regionsByMessage(Window win) throws M68kCpuException {
		if(!registeredDefs.containsKey(win.procId>>4))return false;
		int rec=memory.allocate(8,2);
		try{
			memory.writeLong(rec,0);
			if(callWdef(win,7,rec)==0||(memory.readLong(rec)&0x10)==0)return false; // kWindowCanGetWindowRegion
			for(int[] r:new int[][]{{32,114},{33,118}}){ // GetWindowRegionRec {RgnHandle winRgn; WindowRegionCode regionCode}
				int h=memory.readLong(win.record+r[1]);if(h==0)continue;
				memory.writeLong(rec,h);memory.writeWord(rec+4,r[0]);callWdef(win,8,rec);}
			return true;
		}finally{memory.free(rec);}
	}
	/** wCalcRgns: the WDEF fills the structure and content regions from the window's position; the frame geometry follows. */
	private void calcCustomRegions(Window win) throws M68kCpuException {
		if(!regionsByMessage(win))callWdef(win,2,0);
		int[] struc=regionBounds(memory.readLong(win.record+114));
		if(Mac68kApi.TRACE)System.err.println("[68K] wCalcRgns $"+Integer.toHexString(win.record)+" window "+win.left+","+win.top+" "+win.width+"x"+win.height+" strucRgn bbox "+java.util.Arrays.toString(struc)+" contRgn bbox "+java.util.Arrays.toString(regionBounds(memory.readLong(win.record+118)))+" portRect "+java.util.Arrays.toString(readRect(win.record+16))+" portBits.bounds "+java.util.Arrays.toString((memory.readWord(win.record+6)&0x8000)!=0?readRect(memory.readLong(memory.readLong(win.record+2))+6):readRect(win.record+6)));
		// An empty structure, or one nowhere near the window (some WDEFs read portBits.bounds at the classic offsets of a
		// CGrafPort and lands 19000 pixels away), gets the window's own rect: unioning the two would demand a frame buffer the size
		// of the gap, which the 4000-pixel clamp below cannot keep within the heap (five palettes = 5 x 64 MB).
		if(struc!=null&&struc[3]>struc[1]&&struc[2]>struc[0]&&(struc[2]<win.top-64||struc[0]>win.top+win.height+64||struc[3]<win.left-64||struc[1]>win.left+win.width+64)){
			// A classic-GrafPort WDEF given a CGrafPort (for example, a tool palette using OffsetRect(portRect,
			// -portBits.bounds) reads the grafVars Handle) lands its regions thousands of pixels away; the shapes are right, only
			// the origin is off. Such a WDEF offset its local shape by -(grafVars low word, high word) where it meant -portBits.bounds:
			// undo exactly that when it lands the shape on the window (何かon林檎's WDEF 128 globalizes its PICT mask so; sliding the
			// mask's bounding box to the window's corner put the cut-out left of and above the picture by the mask's margins).
			// Otherwise slide both regions so the content region sits on the window: FindWindow's wHit does PtInRgn(strucRgn).
			int[] cont=regionBounds(memory.readLong(win.record+118));int[] base=cont!=null&&cont[3]>cont[1]&&cont[2]>cont[0]?cont:struc;
			int dh=win.left-base[1],dv=win.top-base[0];
			if((memory.readWord(win.record+6)&0xc000)==0xc000){
				int grafVars=memory.readLong(win.record+8);int[] bounds=readRect(memory.readLong(memory.readLong(win.record+2))+6);
				int gh=(short)grafVars-bounds[1],gv=(short)(grafVars>>>16)-bounds[0];
				if(struc[0]+gv>=win.top-64&&struc[2]+gv<=win.top+win.height+64&&struc[1]+gh>=win.left-64&&struc[3]+gh<=win.left+win.width+64){dh=gh;dv=gv;}
			}
			for(int offset:new int[]{114,118}){int h=memory.readLong(win.record+offset),data=h==0?0:memory.readLong(h);if(data==0)continue;
				QdRegion r=QdRegion.decode(memory,data);if(!r.isEmpty())qd.writeRegionShape(h,r.offset(dh,dv));}
			struc=regionBounds(memory.readLong(win.record+114));
			if(Mac68kApi.TRACE)System.err.println("[68K] wCalcRgns $"+Integer.toHexString(win.record)+": regions moved by "+dh+","+dv+" onto the window, strucRgn bbox "+java.util.Arrays.toString(struc));
		}
		if(struc==null||struc[3]<=struc[1]||struc[2]<=struc[0]||struc[2]<win.top-64||struc[0]>win.top+win.height+64||struc[3]<win.left-64||struc[1]>win.left+win.width+64){struc=new int[]{win.top,win.left,win.top+win.height,win.left+win.width};}
		struc[0]=Math.min(struc[0],win.top);struc[1]=Math.min(struc[1],win.left);struc[2]=Math.max(struc[2],win.top+win.height);struc[3]=Math.max(struc[3],win.left+win.width);
		win.frameLeft=struc[1]-win.left;win.frameTop=struc[0]-win.top;win.frameWidth=Math.min(4000,struc[3]-struc[1]);win.frameHeight=Math.min(4000,struc[2]-struc[0]);
		applyFrameShape(win,struc);
	}
	/** A structure region that is not a rectangle (round or notched palettes) gives the host window its outline: the shape is
	 *  kept relative to the frame's top-left and only handed over when it changed. */
	private void applyFrameShape(Window win,int[] struc) throws M68kCpuException {
		int handle=memory.readLong(win.record+114);int data=handle==0?0:memory.readLong(handle);
		QdRegion region=data==0?QdRegion.empty():QdRegion.decode(memory,data);
		QdRegion shape=region.isEmpty()||region.isRect()?null:region.offset(-struc[1],-struc[0]);
		if(shape==null?win.frameShape==null:win.frameShape!=null&&shape.sameShape(win.frameShape))return;
		win.frameShape=shape;
		if(Mac68kApi.TRACE)System.err.println("[68K] custom WDEF window $"+Integer.toHexString(win.record)+(shape==null?": rectangular frame":": frame shaped by "+shape.rects().size()+" rectangles"));
		tb.host().setWindowShape(win.record,hostShape(shape));
	}
	static java.awt.Shape hostShape(QdRegion shape){
		if(shape==null)return null;java.awt.geom.Path2D.Float path=new java.awt.geom.Path2D.Float(java.awt.geom.Path2D.WIND_NON_ZERO);
		for(int[] r:shape.rects())path.append(new java.awt.Rectangle(r[1],r[0],r[3]-r[1],r[2]-r[0]),false);return path;
	}
	/**
	 * GrowWindow(window, startPt, sizeRect) tracked here: the mouse drags the bottom-right corner of the content within
	 * sizeRect's limits (top/left = smallest height/width, bottom/right = largest), a host outline shows the future frame, and
	 * a program's own WDEF gets wGrow with the prospective content rectangle (drawn once to show and once more to erase, in
	 * the Window Manager port with the grey XOR pen the ROM sets up). Result: new height in the high word, width in the low, 0 when unchanged.
	 */
	private int growWindow(Window win,int start,int sizeRect) throws M68kCpuException {
		MacAppHost host=tb.host();int startH=(short)(start&0xffff),startV=(short)(start>>16);
		int[] limit=sizeRect==0?new int[]{16,16,4000,4000}:readRect(sizeRect);
		int minH=Math.max(1,limit[0]),minW=Math.max(1,limit[1]),maxH=Math.max(minH,Math.min(4000,limit[2])),maxW=Math.max(minW,Math.min(4000,limit[3]));
		int width=win.width,height=win.height,drawnW=-1,drawnH=-1;int rect=memory.allocate(8,2);
		int frameRight=win.frameWidth+win.frameLeft-win.width,frameBottom=win.frameHeight+win.frameTop-win.height; // frame pixels right of / below the content
		host.showDragOutline(new int[]{win.left+win.frameLeft,win.top+win.frameTop,win.frameWidth,win.frameHeight});
		long deadline=System.currentTimeMillis()+120_000;
		try{
			while(host.isMouseDown()&&System.currentTimeMillis()<deadline){
				java.awt.Point p=host.mouseLocation();
				int w=Math.max(minW,Math.min(maxW,win.width+p.x-startH)),h=Math.max(minH,Math.min(maxH,win.height+p.y-startV));
				if(w!=width||h!=height||drawnW<0){
					if(win.custom&&drawnW>0){writeRect(rect,win.top,win.left,win.top+drawnH,win.left+drawnW);growImage(win,rect);}
					width=w;height=h;writeRect(rect,win.top,win.left,win.top+height,win.left+width);
					if(win.custom)growImage(win,rect);drawnW=width;drawnH=height;
					host.setDragOutline(win.left+win.frameLeft,win.top+win.frameTop,width-win.frameLeft+frameRight,height-win.frameTop+frameBottom);
				}
				try{Thread.sleep(15);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			}
			if(win.custom&&drawnW>0){writeRect(rect,win.top,win.left,win.top+drawnH,win.left+drawnW);growImage(win,rect);}
		}finally{host.hideDragOutline();memory.free(rect);}
		if(Mac68kApi.TRACE)System.err.println("[68K] GrowWindow $"+Integer.toHexString(win.record)+" "+win.width+"x"+win.height+" -> "+width+"x"+height);
		return width==win.width&&height==win.height?0:(height<<16)|(width&0xffff);
	}
	/** wGrow with the pen the Window Manager uses for grow outlines (grey pattern, notPatXor) so drawing twice erases. */
	private void growImage(Window win,int rect) throws M68kCpuException {
		M68kCpu cpu=tb.cpu;int saved=cpu.a[7];int savedPort=tb.api.currentQuickDrawPort();
		try{
			setPortTo(wmgrPort);
			int gray=memory.allocate(8,2);for(int i=0;i<8;i++)memory.writeByte(gray+i,(i&1)==0?0xaa:0x55);
			cpu.a[7]-=4;memory.writeLong(cpu.a[7],gray);qd.handle(cpu,0x89d);cpu.a[7]=saved; // PenPat(gray)
			cpu.a[7]-=2;memory.writeWord(cpu.a[7],10);qd.handle(cpu,0x89c);cpu.a[7]=saved;   // PenMode(notPatXor)
			memory.free(gray);
			callWdef(win,5,rect);
			setPortTo(wmgrPort);qd.handle(cpu,0x89e); // PenNormal
		}finally{cpu.a[7]=saved;if(savedPort!=0)setPortTo(savedPort);}
	}
	private int[] regionBounds(int handle) throws M68kCpuException {int data=handle==0?0:memory.readLong(handle);if(data==0)return null;return new int[]{(short)memory.readWord(data+2),(short)memory.readWord(data+4),(short)memory.readWord(data+6),(short)memory.readWord(data+8)};}
	/**
	 * The program replaced a standard window's windowDefProc after NewWindow: the window is run by that definition from its
	 * ShowWindow on. Apple's Movie XCMD 1.2 opens a "borderLess" movie as a windoid (procID 2052) and stores a JMP stub to a
	 * WDEF of its own there, whose regions are the content alone; the host kept a titled frame (Wings -翼の軌跡-'s portrait).
	 */
	private boolean swappedDefinition(Window win) throws M68kCpuException {
		if(CUSTOM_WDEF_DISABLED||win.createdDefProc==0)return false;
		int defProc=memory.readLong(win.record+126);
		if(defProc==0||defProc==win.createdDefProc||memory.readLong(defProc)==0)return false;
		return (win.procId>>4)!=0||!delegatesToStandard(win);
	}
	/** wCalcRgns calls of {@link #standardWdef}, counted so {@link #delegatesToStandard} can tell a wrapper from a WDEF of its own. */
	private int standardCalcCalls;
	/**
	 * A definition put over a standard (WDEF 0) window that hands its messages on to WDEF 0: PowerPC HyperCard (J-2.3 is a fat
	 * binary) stores a routine descriptor in every window's windowDefProc that calls WDEF 0 with the variant it wants (plainDBox
	 * for `hide titleBar`). The window stays framed by the host - adopting it as a custom frame drew HyperCard's card window
	 * with the System 6 black-and-white title bar - and the variant it asks for reaches the host through standardWdef.
	 * Asked with wCalcRgns, which the ROM's ShowWindow sends too.
	 */
	private boolean delegatesToStandard(Window win) throws M68kCpuException {
		int before=standardCalcCalls;KaleidoscopeScheme68k.WindowFrame theme=win.theme;int flavor=win.dialogFlavor;
		win.theme=null;win.dialogFlavor=-1; // the program's definition itself, not the frame the host would draw in its place
		try{callWdef(win,2,0);}catch(M68kCpuException e){return false;}finally{win.theme=theme;win.dialogFlavor=flavor;}
		return standardCalcCalls!=before;
	}
	/**
	 * SendBehind/NewWindow behind one of HyperCard's windows in front of the card window (the message box, which the window
	 * list puts right before the card): the window stays in front of the card, behind the external's own windows there. Movie
	 * XCMD 1.2 puts a floating movie behind the window before FrontDocWindow; in Wings -翼の軌跡- that was the message box
	 * (`put empty into msg` while the movies load), and the host, not knowing the record, stacked the movie behind the card.
	 */
	private int behindHyperCardWindow(int record,int behind){
		CardWindow68k card=tb.cardWindow;
		if(behind==-1||behind==0||windows.containsKey(behind)||card==null||behind==card.record||!card.isWindowRecord(behind))return behind;
		Window last=null;for(Window w:order){if(behindCard(w))break;if(!w.service&&w.record!=record)last=w;}
		return last==null?-1:last.record;
	}
	/** A window run by the program's WDEF: wNew, the regions, and a buffer covering the whole structure so the frame has pixels. */
	private void prepareCustomFrame(Window win,boolean created) throws M68kCpuException {
		if(!win.custom){win.custom=true;qd.makeScreenPort(wmgrPort);qd.makeScreenPort(wmgrClassicPort);if(created){callWdef(win,3,0);adoptWdefPortRect(win);}}
		if(!created){int[] pr=readRect(win.record+16);writeRect(win.record+16,pr[0],pr[1],pr[0]+win.height,pr[1]+win.width);} // SizeWindow: the WDEF computes the new regions from the new portRect
		calcCustomRegions(win);
		qd.resizeWindowPort(win.record,win.width,win.height,0xffffff,new int[]{win.frameTop,win.frameLeft,win.frameTop+win.frameHeight,win.frameLeft+win.frameWidth});
		if(Mac68kApi.TRACE)System.err.println("[68K] custom WDEF "+(win.procId>>4)+" window $"+Integer.toHexString(win.record)+": frame "+win.frameLeft+","+win.frameTop+" "+win.frameWidth+"x"+win.frameHeight);
	}
	/**
	 * A WDEF that sizes its window at wNew: Pictoid's "Picture Window" (WDEF 16) gets an empty NewWindow rect and makes the
	 * portRect its picture's size (512x90). That size is the window's, or showing it would shrink the portRect back to 1x1.
	 */
	private void adoptWdefPortRect(Window win) throws M68kCpuException {
		int[] pr=readRect(win.record+16);int width=pr[3]-pr[1],height=pr[2]-pr[0];
		if(width>0&&height>0&&(width!=win.width||height!=win.height)){win.width=width;win.height=height;}
	}
	/** wDraw with the hilite state the record carries: the WDEF paints the frame into the Window Manager port, which lands in the window's buffer.
	 *  Like the ROM's PaintOne, the Window Manager port is clipped to the window's structure while the WDEF draws (some WDEFs intersect
	 *  its title clip with GetClip: a stale clip left there by the program's own DragGrayRgn set-up hid every title). */
	void drawFrame(Window win) throws M68kCpuException {
		if(!win.custom||!win.visible)return;
		int clip=wmgrPort==0?0:memory.readLong(wmgrPort+28),data=clip==0?0:memory.readLong(clip); // clipRgn (+16 is portRect, whose top-left long is 0: the clip was never set)
		byte[] saved=null;if(data!=0){int size=Math.max(10,Math.min(tb.api.handleSize(clip),memory.readWord(data)&0xffff));saved=memory.readBytes(data,size);}
		// ... less the windows in front of it (the ROM's ClipAbove): the frame drawn into the screen under a front window would
		// become that window's pixels (Resorcerer: the file window's border showed as a line through every editor over it).
		QdRegion visible=null;
		if(data!=0){int[] own={win.top+win.frameTop,win.left+win.frameLeft,win.top+win.frameTop+win.frameHeight,win.left+win.frameLeft+win.frameWidth};
			for(Window o:order){if(o==win)break;if(!o.visible||o.service)continue;int[] b=structureBounds(o);
				if(b[0]<own[2]&&b[2]>own[0]&&b[1]<own[3]&&b[3]>own[1])visible=QdRegion.combine(visible!=null?visible:QdRegion.rect(own[0],own[1],own[2],own[3]),QdRegion.rect(b[0],b[1],b[2],b[3]),QdRegion.DIFF);}
			if(visible!=null&&visible.isEmpty())return; // wholly covered: nothing of the frame shows
			if(visible==null)setRegion(clip,own[0],own[1],own[2],own[3]);else qd.writeRegionShape(clip,visible);}
		try{callWdef(win,0,0);}
		finally{
			if(saved!=null&&visible==null){if(memory.readLong(clip)==data)memory.load(data,saved);}
			else if(saved!=null){if(tb.api.handleSize(clip)<saved.length)tb.api.resizeHandle(clip,saved.length);int now=memory.readLong(clip);if(now!=0)memory.load(now,saved);}} // the clipped shape may have grown (moved) the region
	}
	/** DragWindow for a custom-framed window: the host cannot drag a bare window, so the window follows the mouse until the button goes up. */
	/**
	 * DragWindow / GrowWindow of HyperCard's card window, which is no window of this VM: the outline follows the mouse while the
	 * button is down, and DragWindow then moves the real card window by the offset (MoveWindow); GrowWindow returns the new size
	 * (height in the high word, width in the low, 0 when unchanged) for the external's SizeWindow. UxDragCard drags a card window
	 * without a title bar by its content, UxGrowCard sizes it from a button: both did nothing.
	 */
	private void dragCardWindow(int start,int bounds) throws M68kCpuException {
		MacAppHost host=tb.host();int startH=(short)(start&0xffff),startV=(short)(start>>16);
		java.awt.Rectangle r=host.toGuestRect(TUtil.cardWindowRect());int[] limit=bounds==0?null:readRect(bounds);
		host.showDragOutline(new int[]{r.x,r.y,r.width,r.height});
		int dh=0,dv=0;long deadline=System.currentTimeMillis()+120_000;
		try{
			while(host.isMouseDown()&&System.currentTimeMillis()<deadline){
				java.awt.Point p=host.mouseLocation();int h=p.x,v=p.y; // the pointer is pinned to boundsRect, as by DragGrayRgn
				if(limit!=null){h=Math.max(limit[1],Math.min(limit[3]-1,h));v=Math.max(limit[0],Math.min(limit[2]-1,v));}
				if(h-startH!=dh||v-startV!=dv){dh=h-startH;dv=v-startV;host.moveDragOutline(r.x+dh,r.y+dv);}
				try{Thread.sleep(15);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			}
		}finally{host.hideDragOutline();}
		if(Mac68kApi.TRACE)System.err.println("[68K] DragWindow of the card window from "+startH+","+startV+" by "+dh+","+dv);
		if(dh!=0||dv!=0)tb.cardWindow.moveWindow(r.x+dh,r.y+dv);
	}
	private int growCardWindow(int start,int sizeRect) throws M68kCpuException {
		MacAppHost host=tb.host();int startH=(short)(start&0xffff),startV=(short)(start>>16);
		java.awt.Rectangle r=host.toGuestRect(TUtil.cardWindowRect());
		int[] limit=sizeRect==0?new int[]{16,16,4000,4000}:readRect(sizeRect);
		int minH=Math.max(1,limit[0]),minW=Math.max(1,limit[1]),maxH=Math.max(minH,Math.min(4000,limit[2])),maxW=Math.max(minW,Math.min(4000,limit[3]));
		int width=r.width,height=r.height;host.showDragOutline(new int[]{r.x,r.y,width,height});
		long deadline=System.currentTimeMillis()+120_000;
		try{
			while(host.isMouseDown()&&System.currentTimeMillis()<deadline){
				java.awt.Point p=host.mouseLocation();
				int w=Math.max(minW,Math.min(maxW,r.width+p.x-startH)),h=Math.max(minH,Math.min(maxH,r.height+p.y-startV));
				if(w!=width||h!=height){width=w;height=h;host.setDragOutline(r.x,r.y,width,height);}
				try{Thread.sleep(15);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			}
		}finally{host.hideDragOutline();}
		if(Mac68kApi.TRACE)System.err.println("[68K] GrowWindow of the card window "+r.width+"x"+r.height+" -> "+width+"x"+height);
		return width==r.width&&height==r.height?0:(height<<16)|(width&0xffff);
	}
	private void dragCustom(Window win,int start,int bounds) throws M68kCpuException {
		MacAppHost host=tb.host();int startH=(short)(start&0xffff),startV=(short)(start>>16);int left0=win.left,top0=win.top,lastLeft=left0,lastTop=top0;
		long deadline=System.currentTimeMillis()+60_000;
		while(host.isMouseDown()&&System.currentTimeMillis()<deadline){
			java.awt.Point p=host.unpinnedMouseLocation();int left=left0+p.x-startH,top=top0+p.y-startV;
			// as far as a native frame goes: anywhere on the display with a piece of the frame left to take hold of (the
			// program's bounds are its screen, which a game screen makes a 640x480 patch in the middle of the display)
			int[] area=host.userDragArea();
			if(area!=null){int sl=left+win.frameLeft,st=top+win.frameTop;sl=Math.max(area[1]-win.frameWidth+16,Math.min(area[3]-16,sl));st=Math.max(area[0],Math.min(area[2]-16,st));left=sl-win.frameLeft;top=st-win.frameTop;}
			else if(bounds!=0){int[] b=readRect(bounds);left=Math.max(b[1]-win.width+16,Math.min(b[3]-16,left));top=Math.max(b[0],Math.min(b[2]-16,top));}
			if(left!=lastLeft||top!=lastTop){move(win.record,left,top,true);lastLeft=left;lastTop=top;}
			try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
		}
		if(frontWindow()!=win.record&&(host.modifiers()&0x100)==0)select(win.record);
	}
	/**
	 * DragGrayRgn/DragTheRgn(theRgn, startPt, limitRect, slopRect, axis, actionProc): LONGINT - the region's outline follows the
	 * mouse (pinned to limitRect, along one axis when asked) until the button goes up; the result is the offset (v in the
	 * high word, h in the low) or $80008000 when the mouse went up outside slopRect. Programs drag their own windows and
	 * palettes with it (HyperCard's windoids), and pictures within documents.
	 */
	private boolean dragGrayRegion(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7],action=memory.readLong(sp),axis=(short)memory.readWord(sp+4),slopRect=memory.readLong(sp+6),limitRect=memory.readLong(sp+10),start=memory.readLong(sp+14),region=memory.readLong(sp+18);cpu.a[7]+=22;
		int moved=dragRegion(region,start,limitRect,slopRect,axis,action);
		// The ROM's DragTheRgn moves the region itself along with its outline (OffsetRgn), so theRgn is left where it was dropped:
		// Bag-o-Tricks' DragGrayRect XFCN takes the region's rgnBBox afterwards as the dragged rect (every "Drag me" snapped
		// back). A window's own structure/content region is left alone: HyperZebra keeps those, MoveWindow follows.
		if(moved!=0x80008000&&moved!=0&&region!=0&&!windowOwnRegion(region))qd.offsetRegion(region,(short)moved,moved>>16);
		memory.writeLong(cpu.a[7],moved);return true;
	}
	private boolean windowOwnRegion(int handle) throws M68kCpuException {
		for(Window w:order)if(memory.readLong(w.record+114)==handle||memory.readLong(w.record+118)==handle)return true;
		return false;
	}
	/** The window whose outline the user dragged last ({@link #dragRegion}) and until when its MoveWindow counts as the user's. */
	private int userDragWindow;private long userDragUntil;
	private boolean userDragged(int w){if(w==0||w!=userDragWindow||System.currentTimeMillis()>userDragUntil)return false;userDragWindow=0;return true;}
	/** The visible window whose structure has exactly these global bounds, or null. */
	private Window windowWithStructure(int[] r) throws M68kCpuException {
		for(Window w:order){if(!w.visible)continue;int[] b=structureBounds(w);if(b!=null&&java.util.Arrays.equals(b,r))return w;}
		return null;
	}
	/** DragGrayRgn's tracking: the outline follows the mouse; the offset (v << 16 | h), or $80008000 outside slopRect. */
	private boolean dragTheRgn;
	/** A window of the Window Manager's list, or another window record a program was given (HyperCard's card window). */
	private boolean windowRecordAddress(int w){
		return windows.containsKey(w)||w>0&&(tb.cardWindow!=null&&tb.cardWindow.isWindowRecord(w));
	}
	int dragRegion(int region,int start,int limitRect,int slopRect,int axis,int action) throws M68kException {
		int startH=(short)(start&0xffff),startV=(short)(start>>16);int[] limit=limitRect==0?null:readRect(limitRect),slop=slopRect==0?null:readRect(slopRect);
		int[] bounds=regionBounds(region);MacAppHost host=tb.host();
		// startPt, limitRect, slopRect and the region are in the current port's local coordinates (THINK C 5 drags project
		// window rows in its window's port); the mouse and the on-screen outline are global
		int[] origin=globalToLocal(0,0);int toGlobalH=-origin[0],toGlobalV=-origin[1];
		// a window's outline (a DragWindow patch - Kaleidoscope's INIT - drags the structure region with DragGrayRgn): it goes as
		// far as a native frame, anywhere on the display, rather than within the program's screen (a 640x480 game screen kept it
		// in the middle of the display); the MoveWindow that follows counts as the user's move
		Window dragged=bounds==null?null:windowWithStructure(new int[]{bounds[0]+toGlobalV,bounds[1]+toGlobalH,bounds[2]+toGlobalV,bounds[3]+toGlobalH});
		int[] area=dragged==null?null:host.userDragArea();
		if(area!=null){
			int sh=startH-bounds[1],sv=startV-bounds[0],w=bounds[3]-bounds[1]; // the start point within the outline
			limit=new int[]{area[0]+sv-toGlobalV,area[1]-w+16+sh-toGlobalH,area[2]-16+sv-toGlobalV,area[3]-16+sh-toGlobalH};slop=null;
			userDragWindow=dragged.record;userDragUntil=System.currentTimeMillis()+120_000;
		}
		if(bounds!=null&&bounds[3]>bounds[1]&&bounds[2]>bounds[0])host.showDragOutline(new int[]{bounds[1]+toGlobalH,bounds[0]+toGlobalV,bounds[3]-bounds[1],bounds[2]-bounds[0]});
		int dh=0,dv=0;boolean inside=true;long deadline=System.currentTimeMillis()+120_000;
		try{
			while(host.isMouseDown()&&System.currentTimeMillis()<deadline){
				java.awt.Point p=area!=null?host.unpinnedMouseLocation():host.mouseLocation();int h=p.x-toGlobalH,v=p.y-toGlobalV;
				inside=slop==null||(h>=slop[1]&&h<slop[3]&&v>=slop[0]&&v<slop[2]);
				if(limit!=null){h=Math.max(limit[1],Math.min(limit[3]-1,h));v=Math.max(limit[0],Math.min(limit[2]-1,v));}
				dh=axis==2?0:h-startH;dv=axis==1?0:v-startV;
				if(bounds!=null)host.moveDragOutline(bounds[1]+toGlobalH+dh,bounds[0]+toGlobalV+dv);
				if(action!=0)tb.callRoutine(action);
				if(dragTheRgn){int hook=memory.readLong(0x9f6);if(hook!=0)tb.callRoutine(hook);}
				try{Thread.sleep(15);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			}
		}finally{host.hideDragOutline();}
		if(area!=null)userDragUntil=System.currentTimeMillis()+2000; // the MoveWindow comes right after
		if(Mac68kApi.TRACE)System.err.println("[68K] DragGrayRgn from "+startH+","+startV+" -> offset "+dh+","+dv+(inside?"":" (outside slop)")+(area!=null?" (window $"+Integer.toHexString(dragged.record)+")":""));
		return inside?(dv<<16|dh&0xffff):0x80008000;
	}
	/** TrackGoAway/TrackBox for a custom-framed window: true when the mouse goes up in the same part (wHit code {@code part}). */
	private boolean trackCustom(Window win,int part) throws M68kCpuException {
		MacAppHost host=tb.host();long deadline=System.currentTimeMillis()+60_000;boolean native_=floaterDef(win.procId>>4)||win.theme!=null;
		if(native_)callWdef(win,0,part); // the box goes down as the mouse presses it
		try{while(host.isMouseDown()&&System.currentTimeMillis()<deadline)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
		finally{if(native_&&win.pressedPart!=0)callWdef(win,0,win.pressedPart);}
		java.awt.Point p=host.mouseLocation();return callWdef(win,1,p.y<<16|p.x&0xffff)==part;
	}

	/** Puts {@code win} where the Window Manager's list wants it: behind = -1 front, 0 (or an unknown window) back, else right behind that window. */
	/**
	 * BringToFront: only the stacking changes - no highlighting and no activate events, unlike SelectWindow. AppleWorks 5 brings
	 * its document over its button bar for the Page Setup dialog and the button bar back afterwards; as a SelectWindow the
	 * second call deactivated the document, and Close/Save/Print stayed disabled.
	 */
	private void bringToFront(int w){
		Window win=windows.get(w);if(win==null)return;
		List<Window> before=new ArrayList<Window>(order);order.remove(win);
		int at=0;if(!win.floatingLayer)while(at<order.size()&&order.get(at).floatingLayer)at++; // in front of its own layer
		order.add(at,win);refreshLinks();
		try{exposeRaised(win,before);}catch(M68kCpuException e){}
		tb.host().bringToFront(w,at==0?-1:order.get(at-1).record);
	}
	/**
	 * SelectWindow/BringToFront's PaintOne: what the windows it now moves in front of covered of a window joins its update
	 * region, as the screen there held their pixels (its drawing went to them). Iconographer opens an icon from its browser
	 * into the empty untitled window behind the browser, draws it, then brings it forward (SendBehind its tool palette): the
	 * canvas stayed blank.
	 */
	private void exposeRaised(Window win,List<Window> before) throws M68kCpuException {
		if(!win.visible||!before.contains(win))return; // a window new to the list exposes nothing of its own
		for(Window o:before){if(o==win)break;if(!o.visible||order.indexOf(o)<order.indexOf(win))continue; // still in front of it
			exposeUnder(win,structureBounds(o));}
	}
	/** SendBehind: the window's own exposure when it moves forward ({@link #exposeRaised}), and when it moves back, that of the
	 *  windows now in front of it which it covered (PaintBehind). */
	private void exposeReordered(Window win,List<Window> before) throws M68kCpuException {
		exposeRaised(win,before);
		if(!win.visible||!before.contains(win))return;int[] s=structureBounds(win);
		for(int i=before.indexOf(win)+1;i<before.size();i++){Window o=before.get(i);if(o.visible&&order.indexOf(o)<order.indexOf(win))exposeUnder(o,s);}
	}
	/** The part of window {@code win}'s content that the global rectangle {@code covered} hid joins its update region. */
	private void exposeUnder(Window win,int[] covered) throws M68kCpuException {
		int top=Math.max(covered[0],win.top),left=Math.max(covered[1],win.left),bottom=Math.min(covered[2],win.top+win.height),right=Math.min(covered[3],win.left+win.width);
		if(bottom<=top||right<=left)return;
		expose(win,new int[]{top-win.top,left-win.left,bottom-win.top,right-win.left});
	}
	/**
	 * Content that comes into view again (a window in front went away, moved or went behind): it joins the update region, and the
	 * update erases it first, as PaintOne/PaintBehind erase it on a real Mac before the update event (unless PaintWhite is
	 * FALSE). Here the window's own buffer still holds its pixels, and programs draw their updates over what is there: TextEdit's
	 * anti-aliased letters grew darker at each pass (HyperCard's script window brought in front of the card window). The erase
	 * waits for BeginUpdate ({@link #eraseUnerased}) so the uncovered part is never shown blank before the program redraws it.
	 */
	private void expose(Window win,int[] r) throws M68kCpuException {
		unionUpdate(win.record,r);tb.events.postUpdate(win.record);
		if(memory.readWord(0x9dc)==0||tb.isDesktopWindow(win.record))return; // PaintWhite FALSE: the Window Manager leaves the content alone
		QdRegion e=QdRegion.rect(r[0],r[1],r[2],r[3]);win.unerased=win.unerased==null?e:QdRegion.combine(win.unerased,e,QdRegion.UNION);
	}
	/** BeginUpdate: what {@link #expose} left unerased and the update region still holds goes to the background. */
	private void eraseUnerased(Window win,int updateRgn) throws M68kCpuException {
		QdRegion e=win.unerased;win.unerased=null;if(e==null)return;
		e=QdRegion.combine(e,qd.regionShapeOf(updateRgn).offset(-win.left,-win.top),QdRegion.SECT);
		for(int[] r:e.rects())eraseContent(win.record,win,r);
	}
	private void place(Window win,int behind){
		order.remove(win);
		Window ref=behind==-1||behind==0?null:windows.get(behind);
		if(behind==-1)order.add(0,win);else if(ref==null)order.add(win);else order.add(Math.min(order.size(),order.indexOf(ref)+1),win);
		refreshLinks();
	}
	/** WindowList ($9D6) and the nextWindow links: the program's windows front to back, then HyperCard's own (card, message box) for an
	 *  external - in front of the external's windows the host keeps behind the card window (xWindowFrame's backdrop after
	 *  doMenu "next window"). Movie XCMD 1.2 puts a floating movie right in front of FrontDocWindow (the card window) by walking
	 *  these links: with the card last it went behind Myst's backdrop, so behind the card. */
	void refreshLinks(){
		try{List<Integer> chain=new ArrayList<Integer>();boolean card=tb.cardWindow==null;
			for(Window w:order)if(w.service)memory.writeLong(w.record+144,0);else{if(!card&&behindCard(w)){chain.addAll(tb.cardWindow.records());card=true;}chain.add(w.record);}
			if(!card)chain.addAll(tb.cardWindow.records());
			for(int i=0;i<chain.size();i++)memory.writeLong(chain.get(i)+144,i+1<chain.size()?chain.get(i+1):0);memory.writeLong(0x9d6,chain.isEmpty()?0:chain.get(0));}
		catch(M68kCpuException ignored){}
		calcVis();
	}

	// ------------------------------------------------------------------ visible regions (CalcVis)

	/**
	 * Hosted windows keep private buffers, so for QuickDraw a window's whole content stays drawable and its visRgn is its
	 * portRect whatever lies in front of it. A program that writes the screen memory itself asks the visRgn where it may
	 * write: PlayerPRO 4.5 draws its oscilloscope straight into the screen when the window's visRgn is its portRect, and
	 * pixel by pixel through PtInRgn otherwise - with the whole portRect it drew its traces over its Pattern window, About
	 * box and Help in front. Once the program has written the screen ({@link #screenWritten}), the visRgns are those of the
	 * ROM's CalcVisBehind: the content minus the structure of every visible window in front; and what a change uncovers
	 * joins the update region, as PaintBehind does, since the program may have left it undrawn meanwhile.
	 * -Dhz68k.calcvis=always computes them for every program, =off never.
	 */
	private static final String CALC_VIS=System.getProperty("hz68k.calcvis","auto");
	private boolean trueVis="always".equals(CALC_VIS);
	private static final boolean CALC_VIS_TRACE=Boolean.getBoolean("hz68k.calcvistrace"); // -Dhz68k.calcvistrace=true: what each change uncovers
	/** The program wrote into the screen memory (emulation thread, from the scheduler poll): its windows get true visRgns from now on. */
	void screenWritten(){
		if(trueVis||"off".equals(CALC_VIS))return;trueVis=true;calcVis();
		// what the program wrote before took the whole content of the windows behind for theirs and went into the windows in front
		// of them (PlayerPRO's first oscilloscope frames left lines in its digital view): every window over another one is redrawn
		try{for(Window win:order){if(!win.visible||win.service||win.trueVis==null)continue;
			for(Window other:order)if(other!=win&&other.visible&&other.left<win.left+win.width&&win.left<other.left+other.width&&other.top<win.top+win.height&&win.top<other.top+other.height){
				unionUpdate(win.record,new int[]{0,0,win.height,win.width});tb.events.postUpdate(win.record);break;}}}
		catch(M68kCpuException e){}
	}
	/**
	 * The program copied the screen into a buffer of its own (CopyBits from the Window Manager port): it takes the screen for
	 * what lies behind its windows, and a real Mac tells it with an update event when another window stops covering one. Audion
	 * hides its control window on an update, copies the screen there into the backdrop of its translucent face edges and shows
	 * it again; without the update the playlist window, moved away, stayed in that backdrop. Its windows get true visRgns too.
	 */
	void screenRead(){if(trueVis||"off".equals(CALC_VIS))return;trueVis=true;calcVis();}
	/** CalcVisBehind for every window, after anything that changes which part of a window can be seen (see {@link #trueVis}). */
	void calcVis(){
		if(!trueVis)return;
		try{
			QdRegion above=QdRegion.empty(); // the structures of the visible windows in front, global
			for(Window win:order){
				if(!win.visible){win.trueVis=null;continue;}
				if(!win.service&&!win.system&&!tb.isDesktopWindow(win.record)){
					QdRegion vis=QdRegion.combine(QdRegion.rect(win.top,win.left,win.top+win.height,win.left+win.width),above,QdRegion.DIFF);
					QdRegion before=win.trueVis;win.trueVis=vis;
					// in the port's coordinates (portRect.topLeft is the origin); during an update the shape EndUpdate restores changes
					if(win.updateVis!=null)win.updateVis=vis.offset(win.updateOriginH-win.left,win.updateOriginV-win.top);
					else{int[] portRect=readRect(win.record+16);qd.writeRegionShape(memory.readLong(win.record+24),vis.offset(portRect[1]-win.left,portRect[0]-win.top));}
					QdRegion uncovered=before==null?null:QdRegion.combine(vis,before,QdRegion.DIFF);
					if(uncovered!=null&&!uncovered.isEmpty()){int[] b=uncovered.bounds();if(CALC_VIS_TRACE)System.err.println("[68K] CalcVis: $"+Integer.toHexString(win.record)+" '"+win.title+"' uncovered "+java.util.Arrays.toString(b));expose(win,new int[]{b[0]-win.top,b[1]-win.left,b[2]-win.top,b[3]-win.left});}
				}
				int[] s=structureBounds(win);above=QdRegion.combine(above,QdRegion.rect(s[0],s[1],s[2],s[3]),QdRegion.UNION);
			}
		}catch(M68kCpuException e){if(Mac68kApi.TRACE)System.err.println("[68K] CalcVis: "+e.getMessage());}
	}

	/** FrontWindow: the frontmost visible window that is not the GhostWindow ($A84), a floating window a program declares
	 *  that way (SuperPaint's tool palette) so that its document window stays the "front" one. */
	private int frontWindowSkippingGhost() throws M68kCpuException {
		int ghost=memory.readLong(0xa84);if(ghost==0)return frontWindow();
		for(Window w:order){if(behindCard(w))break;if(w.visible&&!w.service&&w.record!=ghost)return w.record;}
		return tb.cardWindow!=null?tb.cardWindow.record:0;
	}
	/** The window activation follows: the front visible window outside the Carbon floating layer (FrontNonFloatingWindow);
	 *  {@link #frontWindow} when only floating windows are up. */
	int activeFront(){for(Window w:order)if(w.visible&&!w.service&&!w.floatingLayer)return w.record;return frontWindow();}
	/** FrontNonFloatingWindow: the front visible window outside the floating layer, 0 if none. */
	int frontNonFloating(){for(Window w:order)if(w.visible&&!w.service&&!w.floatingLayer)return w.record;return 0;}
	/** Puts a window in the floating layer (Carbon's floating and utility window classes). */
	void setFloatingLayer(int record){Window w=windows.get(record);if(w==null)return;w.floatingLayer=true;if(tb.hosted())tb.host().setFloatingLayer(record);}
	/** The first visible document (non-floating) window behind {@code win}, or null. */
	private Window nextVisibleDocument(Window win){boolean behind=false;for(Window o:order){if(o==win){behind=true;continue;}if(behind&&o.visible&&!o.service&&!o.floatingLayer)return o;}return null;}
	private void unhighlight(Window o) throws M68kCpuException {if(memory.readByte(o.record+111)==0)return;memory.writeByte(o.record+111,0);if(o.custom&&o.visible)drawFrame(o);}
	/** The front window is a modal dialog's: WDEF 0's dBoxProc, plainDBox, altDBoxProc or movableDBoxProc, or an Appearance
	 *  modal dialog or alert (1040-1043) - a program running its own dialog loop waits for the user's answer. */
	boolean frontIsModalDialog(){Window w=windows.get(frontWindow());if(w==null||!w.visible)return false;int def=w.procId>>4,v=w.procId&15;return def==0&&(v==1||v==2||v==3||v==5)||def==65&&v<=3;}
	int frontWindow(){for(Window w:order){if(behindCard(w))break;if(w.visible&&!w.service)return w.record;}return tb.cardWindow!=null?tb.cardWindow.record:0;} // an external with no windows of its own: HyperCard's card window is in front
	/** An external's window the host keeps behind HyperCard's card window ({@link MacAppHost#behindHyperCard}). */
	private boolean behindCard(Window w){return tb.cardWindow!=null&&tb.hosted()&&tb.host().behindHyperCard(w.record);}
	boolean isVisible(int record){Window w=windows.get(record);return w!=null&&w.visible;}
	/** Records of the visible windows, front to back. */
	List<Integer> visibleWindows(){List<Integer> r=new ArrayList<Integer>();for(Window w:order)if(w.visible)r.add(w.record);return r;}
	/** Visible windows front to back as (port, global left, global top) triples, for drawing into the screen port: where the
	 *  port's local (0,0) lies on the screen, so its buffer (in local coordinates) takes global = local + that. The content's
	 *  top-left is portRect.topLeft, which a program's SetOrigin moves - Fontographer scrolls its tool palette's port by 35 pixels,
	 *  and every frame its WDEF drew in the Window Manager port landed 35 pixels down inside the palette. */
	int[] screenWindows(){List<Window> visible=new ArrayList<Window>();for(Window w:order)if(w.visible)visible.add(w);
		int[] r=new int[visible.size()*3];for(int i=0;i<visible.size();i++){Window w=visible.get(i);int originV=0,originH=0;
			try{originV=(short)memory.readWord(w.record+16);originH=(short)memory.readWord(w.record+18);}catch(M68kCpuException e){}
			r[i*3]=w.record;r[i*3+1]=w.left-originH;r[i*3+2]=w.top-originV;}
		return r;}
	Window window(int record){return windows.get(record);}
	/** Where the Event Manager's CheckUpdate meets a window: its place in the window list (0 = frontmost), or -1 while it is
	 *  invisible - a hidden window's invalidated area waits until it is shown. */
	int updateRank(int record){Window win=windows.get(record);if(win==null)return -1;
		boolean visible=win.visible;if(!visible)try{visible=memory.readByte(record+110)!=0;}catch(M68kCpuException e){} // the record's visible flag, which some programs set themselves
		if(!visible)return -1;int i=order.indexOf(win);return i<0?Integer.MAX_VALUE-1:i;}
	/** The frontmost visible window containing the global point, or 0. */
	int windowAt(int h,int v){if(isDragClick(h,v))return pendingDragWindow;for(Window w:order){if(w.visible&&h>=w.left&&h<w.left+w.width&&v>=w.top&&v<w.top+w.height)return w.record;}return 0;}
	/** True (once) when the host frame's close box of this window was clicked: SystemClick closes a desk accessory with it. */
	boolean takeGoAway(int w){if(w==0||pendingGoAwayWindow!=w)return false;pendingGoAwayWindow=0;tb.events.clickTracked();return true;}
	int currentWindow(){int port=tb.api.currentQuickDrawPort();return windows.containsKey(port)?port:0;}
	boolean isWindow(int record){return windows.containsKey(record);}

	// ------------------------------------------------------------------ hit testing / coordinates

	private boolean findWindow(M68kCpu cpu) throws M68kCpuException {
		int dst=memory.readLong(cpu.a[7]),v=(short)memory.readWord(cpu.a[7]+4),h=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;
		int found=0,part=IN_DESK;
		EventManager68k.Event pending=tb.events.pendingMenuEvent;
		if(pending!=null&&pending.menuId!=0&&v==pending.whereV){
			if(pending.menuId==-1){found=pending.window;part=IN_GO_AWAY;pendingGoAwayWindow=found;
				if(found!=0&&windows.containsKey(found)&&(short)memory.readWord(found+108)<0)part=IN_SYS_WINDOW;} // a desk accessory's close box: SystemClick closes it
			else part=IN_MENU_BAR;
		}else if(pendingGrowWindow!=0&&h==pendingGrowClickH&&v==pendingGrowClickV&&windows.containsKey(pendingGrowWindow)&&windows.get(pendingGrowWindow).pendingGrowWidth>0&&System.currentTimeMillis()<pendingGrowUntil){
			// The synthetic corner click of a native resize is inGrow for every FindWindow of that click (some main loops ask once,
			// then hands the mouse-down to the window's own handler which asks again and calls GrowWindow); GrowWindow consumes it.
			found=pendingGrowWindow;part=IN_GROW;
		}else if(isDragClick(h,v)){ // the click of a native move: the window's title bar
			found=pendingDragWindow;part=IN_DRAG;
		}else{
			int[] hit=hitTest(h,v,false);found=hit[0];if(found!=0)part=hit[1];
			if(found==0&&v<MacAppHost.MENU_BAR_HEIGHT&&!GMenu.isMacOSX())part=IN_DESK;
		}
		if(dst!=0)memory.writeLong(dst,found);
		if(Mac68kApi.TRACE)System.err.println("[68K] FindWindow "+h+","+v+" -> part "+part+" window $"+Integer.toHexString(found));
		memory.writeWord(cpu.a[7],part);return true;
	}

	/** The frontmost visible window of one layer (the program's, or TSM's service windows) at a global point: {window, part}, window 0 = none. */
	private int[] hitTest(int h,int v,boolean service) throws M68kCpuException {
		if(!service&&tb.hosted()){Window pressed=windows.get(tb.host().pressedWindowAt(h,v)); // a displaced window clicked where another lies for the program
			if(pressed!=null&&pressed.visible&&!pressed.service&&!pressed.custom&&h>=pressed.left&&h<pressed.left+pressed.width&&v>=pressed.top&&v<pressed.top+pressed.height)return contentHit(pressed,h,v);}
		// HyperCard's card window lies among them: in front of the windows the host keeps behind it, behind the others
		int[] card=service||tb.cardWindow==null?null:tb.cardWindow.contentRect();boolean cardTested=card==null;
		for(Window w:order){
			if(!cardTested&&behindCard(w)){cardTested=true;if(inRect(card,h,v))return new int[]{tb.cardWindow.record,IN_CONTENT};}
			if(!w.visible||w.service!=service)continue;
			if(w.custom){int sl=w.left+w.frameLeft,st=w.top+w.frameTop;if(h<sl||h>=sl+w.frameWidth||v<st||v>=st+w.frameHeight)continue;
				// the ROM's part code is the wHit result + 2, the WDEF's own codes too (Appearance: wInCollapseBox 9 = inCollapseBox 11;
				// Infini-D 2.0.1's view WDEF answers 10 for the pop-up in its title bar and opens it on part 12)
				int hit=callWdef(w,1,v<<16|h&0xffff);if(hit==0)continue;return new int[]{w.record,partForHit(hit)};}
			if(h>=w.left&&h<w.left+w.width&&v>=w.top&&v<w.top+w.height)return contentHit(w,h,v);
		}
		if(!cardTested&&inRect(card,h,v))return new int[]{tb.cardWindow.record,IN_CONTENT};
		return new int[]{0,IN_DESK};
	}
	private static boolean inRect(int[] r,int h,int v){return r!=null&&v>=r[0]&&v<r[2]&&h>=r[1]&&h<r[3];}
	/** {window, part} of a point within a window's content. */
	private int[] contentHit(Window w,int h,int v) throws M68kCpuException {
		if(growsInContent(w)&&h>=w.left+w.width-15&&v>=w.top+w.height-15)return new int[]{w.record,IN_GROW};
		return new int[]{w.record,(short)memory.readWord(w.record+108)<0?IN_SYS_WINDOW:IN_CONTENT};
	}
	/** FindWindow's part code for a WDEF's wHit answer (non-zero): the answer + 2, the WDEF's own codes included. */
	static int partForHit(int hit){return hit>0&&hit<0x7ffd?hit+2:IN_CONTENT;}
	/** Appearance Manager grow windows (WDEF 64, odd variants 1025-1031) draw their grow box inside the content area. */
	private static boolean growsInContent(Window w){return (w.procId>>4)==64&&(w.procId&8)==0&&(w.procId&1)!=0;}
	/** FindServiceWindow: {window, part} of the service window at a global point (window 0: none). */
	int[] findServiceWindow(int h,int v) throws M68kCpuException {return hitTest(h,v,true);}
	/** NewServiceWindow/NewCServiceWindow: a window in front of the program's own that only TSM's calls see. */
	int createServiceWindow(int storage,int[] bounds,String title,boolean visible,int procId,int behind,boolean goAway,boolean color) throws M68kCpuException {
		int w=create(storage,WINDOW_SIZE,bounds,title,visible,procId,behind,goAway,0,color);
		Window win=windows.get(w);if(win!=null){win.service=true;refreshLinks();}
		return w;
	}
	/**
	 * NewSystemWindow, the undocumented Process Manager call OSDispatch $5A of Mac OS 8.5 and later (A-Dock 2.8/3.0, a faceless
	 * 'appe', shows its dock, tooltips and menu arrow with it; DisposeSystemWindow is $5B): (bounds, title, visible, procID, behind, goAwayFlag, refCon, handler UPP, handlerData) :
	 * WindowPtr - NewCWindow's arguments without the storage, plus an event handler. A background-only application has no
	 * windows of its own and gets no mouse or update events, so the system calls
	 * pascal OSErr handler(WindowPtr, EventRecord*, short, long handlerData) for the window's mouse-downs and update events
	 * (A-Dock's handler dispatches on the event's what - 1 and 6 - and ignores the short; the window comes first, measured
	 * from its PowerPC code). Here the window is the calling program's, floating over every program's windows on the host,
	 * and {@link #offerSystemWindowEvent} hands those events to the handler as the program's event calls take them.
	 */
	int createSystemWindow(int[] bounds,String title,boolean visible,int procId,int behind,boolean goAway,int refCon,int proc,int data) throws M68kCpuException {
		int w=create(0,WINDOW_SIZE,bounds,title,false,procId,behind,goAway,refCon,true);
		Window win=windows.get(w);if(win==null)return w;
		win.system=true;win.systemProc=proc;win.systemData=data;win.floatingLayer=true;
		if(tb.hosted()){tb.host().setFloatingLayer(w);tb.host().setSystemWindow(w);}
		if(visible)show(w,true);
		return w;
	}
	boolean isSystemWindow(int record){Window w=windows.get(record);return w!=null&&w.system;}
	/**
	 * An event the program's WaitNextEvent/GetNextEvent is about to return: a mouse-down in a system window and the update event
	 * of one go to the window's handler instead (true: taken, the program sees a null event); its activate events are dropped.
	 */
	boolean offerSystemWindowEvent(M68kCpu cpu,int event) throws M68kException {
		if(event==0)return false;
		int what=memory.readWord(event),w;
		switch(what){
			case 1:{ // mouseDown
				int v=(short)memory.readWord(event+10),h=(short)memory.readWord(event+12);
				int[] hit=hitTest(h,v,false);w=hit[0];if(w==0||!isSystemWindow(w))return false;
				Window win=windows.get(w);callSystemHandler(cpu,win,event,hit[1]);tb.events.clickTracked();return true;}
			case 6:{w=memory.readLong(event+2);Window win=windows.get(w);if(win==null||!win.system)return false;
				callSystemHandler(cpu,win,event,0);
				if(updateStillNeeded(w))clearUpdate(w); // the handler did not draw it: keep the update from coming back
				return true;}
			case 8:w=memory.readLong(event+2);return isSystemWindow(w); // activateEvt: a system window is never active
			default:return false;
		}
	}
	private void callSystemHandler(M68kCpu cpu,Window win,int event,int part) throws M68kException {
		if(win.systemProc==0)return;
		int saved=cpu.a[7];int[] regs=cpu.d.clone(),addr=cpu.a.clone();int port=tb.api.currentQuickDrawPort();
		try{cpu.a[7]-=2;cpu.pushLong(win.record);cpu.pushLong(event);cpu.pushWord(part);cpu.pushLong(win.systemData);cpu.callPascal(win.systemProc,14);}
		finally{cpu.a[7]=saved;System.arraycopy(regs,0,cpu.d,0,8);System.arraycopy(addr,0,cpu.a,0,8);}
		if(Mac68kApi.TRACE)System.err.println("[68K] system window $"+Integer.toHexString(win.record)+" handler took event "+memory.readWord(event)+" (port $"+Integer.toHexString(port)+")");
	}
	/** GetFrontServiceWindow: the frontmost service window (visible or not), or 0. */
	int frontServiceWindow(){for(Window w:order)if(w.service)return w.record;return 0;}
	boolean isServiceWindow(int record){Window w=windows.get(record);return w!=null&&w.service;}

	/** Global -> local for the current port ({h,v}). */
	int[] globalToLocal(int h,int v){
		int port=tb.api.currentQuickDrawPort();Window w=windows.get(port);int[] o=qd.portOrigin();
		if(w!=null)return new int[]{h-w.left+o[1],v-w.top+o[0]};
		int[] card=cardWindowPlace(port);if(card!=null)return new int[]{h-card[0]+o[1],v-card[1]+o[0]};
		int[] screen=screenPortOffset(port);if(screen!=null)return new int[]{h-screen[0],v-screen[1]}; // the bounds carry the origin too (SetOrigin moves them)
		return new int[]{h+o[1],v+o[0]};
	}
	int[] localToGlobal(int h,int v){
		int port=tb.api.currentQuickDrawPort();Window w=windows.get(port);int[] o=qd.portOrigin();
		if(w!=null)return new int[]{h+w.left-o[1],v+w.top-o[0]};
		int[] card=cardWindowPlace(port);if(card!=null)return new int[]{h+card[0]-o[1],v+card[1]-o[0]};
		int[] screen=screenPortOffset(port);if(screen!=null)return new int[]{h+screen[0],v+screen[1]}; // global = local - bounds.topLeft (IM I-193)
		return new int[]{h-o[1],v-o[0]};
	}
	/** A screen port's place on the screen (MovePortTo moved its bounds), or null. */
	private int[] screenPortOffset(int port){try{return qd.screenPortOffset(port);}catch(M68kCpuException e){return null;}}
	/** {left, top} of HyperCard's card window on the screen when {@code port} is its port (an external sees it there), else null. */
	private int[] cardWindowPlace(int port){CardWindow68k card=tb.cardWindow;return card!=null&&port==card.record?card.screenPlace():null;}
	/** Global -> local for a specific window ({h,v}). */
	/** A global point in the window's local coordinates: its port's origin (portRect's top left) counts, as GlobalToLocal's does.
	 *  A control panel's window has its origin at the Control Panel's 87,-1 (ControlPanelHost68k): clicks missed its items. */
	int[] globalToLocal(int window,int h,int v) throws M68kCpuException {Window w=windows.get(window);if(w==null)return new int[]{h,v};return new int[]{h-w.left+(short)memory.readWord(window+18),v-w.top+(short)memory.readWord(window+16)};}
	/** A global point relative to the window's content area (0,0 at its top left whatever the port's origin). */
	int[] globalToContent(int window,int h,int v){Window w=windows.get(window);if(w==null)return new int[]{h,v};return new int[]{h-w.left,v-w.top};}

	// ------------------------------------------------------------------ drawing helpers

	private void drawGrowIcon(int w) throws M68kCpuException {drawGrowIcon(w,null);}
	/** DrawGrowIcon: the scroll bar lines and the grow box ({@code box}: a Kaleidoscope scheme's, else the standard one). */
	private void drawGrowIcon(int w,KaleidoscopeScheme68k.Img box) throws M68kCpuException {
		Window win=windows.get(w);if(win==null)return;
		tb.host().setWindowResizable(w,true); // only a program that sizes its window draws the grow icon
		Graphics2D g=tb.quickDraw.windowSurface(w);if(g==null)return;
		// The ROM draws it in the window's own port, within that port's clipRgn and visRgn (local coordinates, whatever the current
		// port): PowerPlant clips to the size box first ("only the box, not the scroll bar lines") - and draws the box of a window
		// being deactivated while another window is the current port. The lines crossed SoundApp's playlist buttons.
		int[] pr=readRect(w+16),clip=readRegion(memory.readLong(w+28)),vis=readRegion(memory.readLong(w+24));
		int top=Math.max(clip[0],vis[0]),left=Math.max(clip[1],vis[1]),bottom=Math.min(clip[2],vis[2]),right=Math.min(clip[3],vis[3]);
		if(Mac68kApi.TRACE)System.err.printf("[68K] DrawGrowIcon $%x portRect %s clip %s vis %s%n",w,java.util.Arrays.toString(pr),java.util.Arrays.toString(clip),java.util.Arrays.toString(vis));
		if(bottom<=top||right<=left){g.dispose();return;}
		g.clipRect(left,top,right-left,bottom-top);
		int r=pr[3],b=pr[2];
		try{g.setColor(Color.BLACK);g.drawLine(r-15,pr[0],r-15,b);g.drawLine(pr[1],b-15,r,b-15);
			if(box!=null)g.drawImage(box.image,r-15,b-15,null); // the scheme's, as large as it is (Sherbet's reaches the frame's outer edge)
			else{g.setColor(Color.WHITE);g.fillRect(r-14,b-14,14,14);g.setColor(new Color(0xa0a0a0));for(int i=0;i<3;i++)g.drawLine(r-3-i*4,b-2,r-2,b-3-i*4);}}
		finally{g.dispose();}
	}

	/** Adds content-local {@code r} to the window's updateRgn, which is kept in GLOBAL coordinates like the real record's:
	 *  QuickTime's movie controller copies it out of the record, offsets it by the port's global origin and re-invalidates
	 *  what it did not draw (a local-coordinate region ended up outside the window and the title pane never got its update). */
	/** Global structure bounds of a window, or null. */
	private int[] structureBounds(Window win) throws M68kCpuException {
		int[] r=readRegion(memory.readLong(win.record+114));if(r[2]>r[0]&&r[3]>r[1])return r;
		return new int[]{win.top+win.frameTop,win.left+win.frameLeft,win.top+win.frameTop+Math.max(win.height,win.frameHeight),win.left+win.frameLeft+Math.max(win.width,win.frameWidth)};
	}
	/**
	 * Like the ROM's PaintBehind: when a window disappears, moves or shrinks, every visible window behind it that the old
	 * structure {@code exposed} covered gets that part invalidated, so the program repaints it (the screen memory there held
	 * the front window's pixels; some media windows paint their chrome straight into it).
	 */
	private void exposeBehind(Window gone,int[] exposed) throws M68kCpuException {
		if(exposed==null)return;boolean behind=gone==null;
		for(Window win:new ArrayList<Window>(order)){
			if(win==gone){behind=true;continue;}
			if(!behind||!win.visible)continue;
			// PaintOne redraws the frame part that shows again: a WDEF's frame is drawn clipped to what is not covered (drawFrame)
			if(win.custom){int[] s=structureBounds(win);if(s[0]<exposed[2]&&s[2]>exposed[0]&&s[1]<exposed[3]&&s[3]>exposed[1]&&!exposedFrames.contains(win))exposedFrames.add(win);}
			int top=Math.max(exposed[0],win.top),left=Math.max(exposed[1],win.left),bottom=Math.min(exposed[2],win.top+win.height),right=Math.min(exposed[3],win.left+win.width);
			if(bottom<=top||right<=left)continue;
			expose(win,new int[]{top-win.top,left-win.left,bottom-win.top,right-win.left});
		}
		if(!deferExposedFrames)drawExposedFrames();
	}
	/** Custom-WDEF windows whose frame a disappearing window uncovered (exposeBehind), redrawn once that window is off the screen. */
	private final List<Window> exposedFrames=new ArrayList<Window>();private boolean deferExposedFrames;
	private void drawExposedFrames() throws M68kCpuException {
		List<Window> todo=new ArrayList<Window>(exposedFrames);exposedFrames.clear();
		for(Window win:todo)if(windows.get(win.record)==win&&win.visible)drawFrame(win);
	}
	/** The colour Window Manager port (WMgrCPort, drawing on the screen), whatever overrides it for the moment. */
	int screenPort(){return wmgrPort;}
	/** Whether the port is one of the Window Manager ports (WMgrPort or WMgrCPort), which draw on the whole screen. */
	boolean isWMgrPort(int port){return port!=0&&(port==wmgrPort||port==wmgrClassicPort);}
	/** Whether the window's update region holds anything (invalidated or newly shown, and not yet redrawn through EndUpdate). */
	boolean updatePending(int w) throws M68kCpuException {int[] r=readRegion(memory.readLong(w+122));return r[2]>r[0]&&r[3]>r[1];}
	/** Empties the update region, as EndUpdate does once an update was drawn. */
	/** Draws the window's picture (SetWindowPic) over its content and empties its update region; false when it has none. */
	boolean drawWindowPicture(int w){
		try{if(!windows.containsKey(w))return false;int pic=memory.readLong(w+148);if(pic==0)return false;
			qd.drawWindowPicture(w,pic);clearUpdate(w);return true;}
		catch(M68kCpuException ex){if(Mac68kApi.TRACE)System.err.println("[68K] window picture of $"+Integer.toHexString(w)+" not drawn: "+ex.getMessage());return false;}
	}
	/**
	 * BeginUpdate: drawing is limited to the update region (kept here as its bounding box) until EndUpdate, as the visRgn
	 * narrowed to it does on a real Mac. Ruins validates its whole new splash window, draws the picture, then invalidates
	 * one pixel and answers that update with EraseRect of a huge rectangle - which only ever erased that pixel. With no
	 * pending region the visRgn becomes empty, as visRgn * updateRgn does in the ROM: HyperCard, after closing a dialog, runs
	 * BeginUpdate on every window and redraws only those whose visRgn is not empty - its message box got its text drawn
	 * again in srcOr over itself (bold, smeared letters).
	 * Like the ROM, it then empties the update region: between BeginUpdate and EndUpdate the region is empty. ClarisWorks
	 * draws its update offscreen and copies it back through a visRgn of visRgn minus the (local) update region, so that
	 * areas invalidated meanwhile wait for the next update; with the region still full, nothing was copied (blank
	 * document window, no tool panel).
	 */
	private void beginUpdate(Window win) throws M68kCpuException {
		// Nisus Writer 4.03 draws the update of a window that is not the front one without a SetPort of its own (ClipRect of the
		// window's portRect, DrawControls, its draw procedure): with two documents open, the covered one's update wiped the front one.
		// It works on a real Mac, so the port is the window being updated from BeginUpdate on. -Dhz68k.beginupdateport=false turns it off.
		// The ROM's BeginUpdate leaves the port alone, so EndUpdate gives the former port back when the program set none in between:
		// WaterRace answers the update of its backdrop window with BeginUpdate/EndUpdate alone while its preferences dialog is the
		// port, then hit-tests the dialog's items with GetMouse (Done and Cancel did nothing: the mouse was in backdrop coordinates).
		if(!"false".equals(System.getProperty("hz68k.beginupdateport"))){
			int before=tb.api.currentQuickDrawPort();setPortTo(win.record);
			win.portBeforeUpdate=before!=win.record?before:0;win.portSetsAtUpdate=qd.portSets;
		}
		int w=win.record;int[] u=readRegion(memory.readLong(w+122));
		if(Mac68kApi.TRACE)System.err.println("[68K] BeginUpdate $"+Integer.toHexString(w)+" updateRgn (global) "+java.util.Arrays.toString(u)+" window at "+win.left+","+win.top+(win.updateVis!=null?" (nested)":""));
		if(win.updateVis!=null)return;
		int[] origin=readRect(w+16);win.updateOriginV=origin[0];win.updateOriginH=origin[1];
		if(u[2]<=u[0]||u[3]<=u[1]){win.updateVis=qd.clipVisRgn(w,0,0,0,0);win.unerased=null;return;}
		eraseUnerased(win,memory.readLong(w+122));
		clearUpdate(w);
		int[] portRect=readRect(w+16);int dv=portRect[0]-win.top,dh=portRect[1]-win.left;
		if(u[0]+dv<=portRect[0]&&u[1]+dh<=portRect[1]&&u[2]+dv>=portRect[2]&&u[3]+dh>=portRect[3])return; // the whole content: nothing to narrow
		win.updateVis=qd.clipVisRgn(w,u[0]+dv,u[1]+dh,u[2]+dv,u[3]+dh);
	}
	/** InvalRect of a port-local rectangle of window {@code w} (whatever port is current), with its update event. */
	void invalidate(int w,int top,int left,int bottom,int right) throws M68kCpuException {
		if(!windows.containsKey(w))return;int[] portRect=readRect(w+16);
		unionUpdate(w,new int[]{top-portRect[0],left-portRect[1],bottom-portRect[0],right-portRect[1]});tb.events.postUpdate(w);
	}
	void clearUpdate(int w) throws M68kCpuException {setRegion(memory.readLong(w+122),0,0,0,0);}
	/** ValidRect/ValidRgn (content-local {@code r}, or the program's region {@code shape} moved by {@code r}'s offset): the
	 *  area leaves the update region, as DiffRgn takes it out. Fractals! 1.0 answers every update with InvalRect, BeginUpdate,
	 *  EraseRect, EndUpdate, ValidRect: the update event that InvalRect queued used to come back at once, and the window was
	 *  erased in a loop before the aggregate could grow. What stays invalid comes back as another update event, as CheckUpdate
	 *  makes one while the region is not empty: Premiere 1.0's Construction window draws ONE of its seven panes per update and
	 *  validates that pane only - with the rest never asked for, only the work-area bar was ever drawn. */
	private void validateUpdate(int w,int[] r,QdRegion shape) throws M68kCpuException {
		int handle=memory.readLong(w+122);if(handle==0)return;int[] old=readRegion(handle);if(old[2]<=old[0]||old[3]<=old[1])return;
		Window win=windows.get(w);int dv=win==null?0:win.top,dh=win==null?0:win.left;
		if(r[0]+dv<=old[0]&&r[1]+dh<=old[1]&&r[2]+dv>=old[2]&&r[3]+dh>=old[3]&&(shape==null||shape.isRect())){setRegion(handle,0,0,0,0);if(win!=null)win.unerased=null;return;}
		QdRegion valid=shape!=null?shape:QdRegion.rect(r[0],r[1],r[2],r[3]);
		QdRegion left=QdRegion.combine(qd.regionShapeOf(handle),valid.offset(dh,dv),QdRegion.DIFF);
		qd.setRegion(handle,left);
		if(!left.isEmpty())tb.events.postUpdate(w);else if(win!=null)win.unerased=null;
	}
	/** Whether an update event for {@code w} still has something to update: CheckUpdate derives the events from the window's
	 *  update region, so one queued before a BeginUpdate/EndUpdate or ValidRect emptied it is not delivered. */
	boolean updateStillNeeded(int w){
		try{int handle=memory.readLong(w+122);if(handle==0)return true;return updatePending(w);}catch(M68kCpuException e){return true;}
	}
	private void unionUpdate(int w,int[] r) throws M68kCpuException {
		int handle=memory.readLong(w+122);if(handle==0)return;int[] old=readRegion(handle);Window win=windows.get(w);int dv=win==null?0:win.top,dh=win==null?0:win.left;
		r=new int[]{r[0]+dv,r[1]+dh,r[2]+dv,r[3]+dh};
		if(old[2]<=old[0]||old[3]<=old[1])setRegion(handle,r[0],r[1],r[2],r[3]);
		else if(memory.readWord(memory.readLong(handle))!=10)qd.setRegion(handle,QdRegion.combine(qd.regionShapeOf(handle),QdRegion.rect(r[0],r[1],r[2],r[3]),QdRegion.UNION)); // what a partial ValidRgn left keeps its shape
		else setRegion(handle,Math.min(old[0],r[0]),Math.min(old[1],r[1]),Math.max(old[2],r[2]),Math.max(old[3],r[3]));
	}

	int regionHandle(int top,int left,int bottom,int right) throws M68kCpuException {return tb.api.createHandle(HostQuickDraw68k.regionBytes(top,left,bottom,right));}
	// ------------------------------------------------------------------ the System's WDEF 0 called by a program

	/**
	 * The System's WDEF 0 (documentProc family) run for a program that calls it through its Handle: standard windows are framed
	 * by the host, so no one else ever runs it, but a WDEF of a program's own may hand document windows on to it - Excel 2.2's
	 * WDEF 8 does for its worksheets (variant 8, zoomDocProc) and got a do-nothing stub: no title bar, and FindWindow never hit
	 * the window. System 7 black-and-white geometry: a 1-pixel frame, a 19-pixel title bar above the content (strucRgn = the
	 * content rect outset by 1 and by 20 at the top), close box at the left, zoom box at the right, grow box in the content.
	 * Drawing goes to the current port (the Window Manager port, global coordinates) as a WDEF's does.
	 */
	int standardWdef(int varCode,int window,int message,int param) throws M68kCpuException {
		// No window record lies in low memory: Excel 2.2's WDEF 8 hands wCalcRgns on with a nil WindowPtr while NewCWindow is
		// still making its (invisible) worksheet, and the regions "at" 0 were low-memory longs taken for Handles - written
		// through, silently, on an 8-bit screen; on a 32-bit one the master pointer read $FFFFFFFF and the program died.
		if(Integer.compareUnsigned(window,Toolbox68k.APPL_HEAP_START)<0)return 0;
		Window floating=windows.get(window); // a floating window's defProc Handle called by the program itself
		if(floating!=null&&floaterDef(floating.procId>>4)){int savedPort=tb.api.currentQuickDrawPort();try{setPortTo(wmgrPort);return floater(floating,message,param);}finally{if(savedPort!=0)setPortTo(savedPort);}}
		if(floating!=null&&floating.theme!=null){ // a window in the Kaleidoscope scheme's frame, its defProc called by the program itself
			int asked=varCode&15,now=floating.variant>=0?floating.variant:floating.procId&15;
			if(message==2&&(floating.procId>>4)==0&&asked!=now){ // the program switches the frame (HyperCard's hide/show titleBar)
				floating.variant=asked==(floating.procId&15)?-1:asked;reframe(floating);return 0;}
			int savedPort=tb.api.currentQuickDrawPort();try{setPortTo(wmgrPort);return themedWdef(floating,message,param);}finally{if(savedPort!=0)setPortTo(savedPort);}}
		int[] c=globalContent(window);if(c==null)return 0;int top=c[0],left=c[1],bottom=c[2],right=c[3];
		// variants: 0/4/8/12 document windows (4: no grow box, 8: zoom box), 5 movable modal (title bar only),
		// 1 dBoxProc (the modal dialog's double frame, 8 pixels), 2 plainDBox (1-pixel frame), 3 altDBoxProc (frame and a 2-pixel shadow)
		varCode&=15;boolean titled=varCode!=1&&varCode!=2&&varCode!=3;
		boolean hilited=memory.readByte(window+111)!=0,goAway=memory.readByte(window+112)!=0&&varCode!=5,zoom=(varCode&8)!=0,grow=titled&&(varCode&4)==0;
		int[] out=standardOutsets(varCode);
		switch(message){
			case 0:{ // wDraw: 0 the frame, wInGoAway/wInZoomIn/wInZoomOut: toggle that box's pressed look
				if(memory.readByte(window+110)==0)return 0;
				int part=param==4||param==5||param==6?param:0;
				if(part!=0){Integer key=window*8+(part==4?4:5);if(!pressedBoxes.remove(key))pressedBoxes.add(key);}
				else{pressedBoxes.remove(window*8+4);pressedBoxes.remove(window*8+5);}
				if(titled)drawDocumentFrame(window,top,left,bottom,right,hilited,goAway,zoom);else drawDialogFrame(varCode,top,left,bottom,right);return 0;}
			case 1:{ // wHit
				int v=(short)(param>>>16),h=(short)param;
				if(v>=top&&v<bottom&&h>=left&&h<right)return grow&&h>=right-15&&v>=bottom-15?3:1; // wInGrow / wInContent
				if(!titled||v<top-out[0]||v>=bottom+out[2]||h<left-out[1]||h>=right+out[3])return 0; // a dialog frame is not a drag area
				if(v<top&&hilited){
					if(goAway&&h>=left+8&&h<left+19&&v>=top-16&&v<top-5)return 4; // wInGoAway
					if(zoom&&h>=right-19&&h<right-8&&v>=top-16&&v<top-5)return atStandardState(window,c)?5:6; // wInZoomIn / wInZoomOut
				}
				return v<top?2:0;} // wInDrag on the title bar
			case 2:{ // wCalcRgns
				standardCalcCalls++;
				setRegion(memory.readLong(window+114),top-out[0],left-out[1],bottom+out[2],right+out[3]);
				setRegion(memory.readLong(window+118),top,left,bottom,right);
				Window win=windows.get(window);
				if(win!=null&&!win.custom&&(win.procId>>4)==0&&varCode!=(win.variant<0?win.procId&15:win.variant)){ // the program switched the frame (HyperCard's hide/show titleBar)
					win.variant=varCode==(win.procId&15)?-1:varCode;tb.host().setWindowVariant(window,varCode);
				}
				// PowerPC HyperCard puts its own definition (a routine descriptor that calls WDEF 0 with the variant it wants) into
				// every window's windowDefProc, so its windows are run as custom ones; `hide titleBar` then calls it with plainDBox
				// itself (SaveOld / wCalcRgns / DrawNew). Not when the call comes from here (callWdef passes the variant on).
				else if(win!=null&&win.custom&&(win.procId>>4)==0&&wdefCalls==0&&varCode!=(win.variant<0?win.procId&15:win.variant)){
					win.variant=varCode==(win.procId&15)?-1:varCode;win.reframePending=true;
				}
				return 0;}
			default:return 0; // wNew, wDispose, wGrow (the host draws the outline), wDrawGIcon (the program's scroll bars frame it)
		}
	}
	private final java.util.Set<Integer> pressedBoxes=new java.util.HashSet<Integer>();
	/** The content rectangle in global coordinates: portRect offset by the port's bounds (a CGrafPort's are in its PixMap). */
	private int[] globalContent(int window) throws M68kCpuException {
		int bounds=(memory.readWord(window+6)&0xc000)==0xc000?memory.readLong(memory.readLong(window+2))+6:window+8;
		if(bounds<=6)return null;
		int bt=(short)memory.readWord(bounds),bl=(short)memory.readWord(bounds+2);
		int[] r=readRect(window+16);return new int[]{r[0]-bt,r[1]-bl,r[2]-bt,r[3]-bl};
	}
	/** Whether the content fills the WStateData's standard state (the zoom box then zooms in, back to the user state). */
	private boolean atStandardState(int window,int[] content) throws M68kCpuException {
		int data=memory.readLong(window+150);int p=data==0?0:memory.readLong(data);if(p==0)return false;
		int[] std=readRect(p+8);return java.util.Arrays.equals(std,content);
	}
	private void drawDocumentFrame(int window,int top,int left,int bottom,int right,boolean hilited,boolean goAway,boolean zoom) throws M68kCpuException {
		int x0=left-1,y0=top-20,w=right-left+2,h=bottom-top+21;if(w<=2||h<=21||(long)w*h>16_777_216L)return;
		java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=img.createGraphics();
		try{
			g.setColor(Color.WHITE);g.fillRect(1,1,w-2,18);
			g.setColor(Color.BLACK);g.drawRect(0,0,w-1,h-1);g.drawLine(0,19,w-1,19); // frame and the line under the title bar
			String title=tb.decodeTitle(titleBytes(window));
			java.awt.Font font=Fonts68k.font(0,12,0);
			Window win=windows.get(window);
			if(win!=null&&title.equals(win.title)&&!title.equals(shownTitle(win))){title=shownTitle(win);font=Fonts68k.hostFont(0,12,0);}java.awt.FontMetrics fm=Fonts68k.metrics(font);g.setFont(font);
			int tw=title.isEmpty()?0:Math.min(w-64,fm.stringWidth(title));int tx=(w-tw)/2;
			if(hilited){
				for(int y=4;y<=14;y+=2)g.drawLine(2,y,w-3,y); // the six stripes
				if(!title.isEmpty()){g.setColor(Color.WHITE);g.fillRect(tx-6,3,tw+12,13);g.setColor(Color.BLACK);}
				if(goAway)drawTitleBox(g,9,pressedBoxes.contains(window*8+4),false);
				if(zoom)drawTitleBox(g,w-21,pressedBoxes.contains(window*8+5),true);
			}
			if(!title.isEmpty()){
				g.setColor(hilited?Color.BLACK:new Color(0x80,0x80,0x80));g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
				java.awt.Shape clip=g.getClip();g.clipRect(tx,1,tw,18);Fonts68k.drawString(g,font,title,tx,14);g.setClip(clip);
			}
		}finally{g.dispose();}
		tb.quickDraw.drawImage(img,y0,x0,y0+h,x0+w); // the content area stays transparent
	}
	/** The frames of the dialog variants: plainDBox's line, altDBoxProc's line and shadow, dBoxProc's outer line and 2-pixel inner frame. */
	private void drawDialogFrame(int varCode,int top,int left,int bottom,int right) throws M68kCpuException {
		int o=varCode==1?8:1,x0=left-o,y0=top-o,w=right-left+2*o+(varCode==3?2:0),h=bottom-top+2*o+(varCode==3?2:0);if(w<=0||h<=0||(long)w*h>16_777_216L)return;
		java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=img.createGraphics();
		try{
			int fw=w-(varCode==3?2:0),fh=h-(varCode==3?2:0);
			if(varCode==1){g.setColor(Color.WHITE);g.fillRect(0,0,fw,fh);g.setColor(Color.BLACK);g.drawRect(0,0,fw-1,fh-1);g.drawRect(3,3,fw-7,fh-7);g.drawRect(4,4,fw-9,fh-9);
				g.setComposite(java.awt.AlphaComposite.Clear);g.fillRect(o,o,right-left,bottom-top);}
			else{g.setColor(Color.BLACK);g.drawRect(0,0,fw-1,fh-1);if(varCode==3){g.fillRect(fw,2,2,fh);g.fillRect(2,fh,fw,2);}}
		}finally{g.dispose();}
		tb.quickDraw.drawImage(img,y0,x0,y0+h,x0+w);
	}
	/** A close box (11 x 11 with a white margin) or a zoom box (the same, with a 7 x 7 box in its top-left corner). */
	private static void drawTitleBox(Graphics2D g,int x,boolean pressed,boolean zoom){
		g.setColor(Color.WHITE);g.fillRect(x-1,3,13,13);g.setColor(Color.BLACK);g.drawRect(x,4,10,10);
		if(pressed){g.drawLine(x+2,6,x+8,12);g.drawLine(x+8,6,x+2,12);g.drawLine(x+5,5,x+5,13);g.drawLine(x+1,9,x+9,9);return;}
		if(zoom)g.drawRect(x,4,6,6);
	}
	private byte[] titleBytes(int window) throws M68kCpuException {
		int handle=memory.readLong(window+134);int p=handle==0?0:memory.readLong(handle);if(p==0)return new byte[0];
		return memory.readBytes(p+1,memory.readByte(p));
	}

	void setRegion(int handle,int top,int left,int bottom,int right) throws M68kCpuException {if(handle==0)return;int data=memory.readLong(handle);if(data==0)return;memory.writeWord(data,10);memory.writeWord(data+2,top);memory.writeWord(data+4,left);memory.writeWord(data+6,bottom);memory.writeWord(data+8,right);}
	int[] readRegion(int handle) throws M68kCpuException {int data=handle==0?0:memory.readLong(handle);if(data==0)return new int[]{0,0,0,0};return readRect(data+2);}
	int[] readRect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	void writeRect(int p,int top,int left,int bottom,int right) throws M68kCpuException {memory.writeWord(p,top);memory.writeWord(p+2,left);memory.writeWord(p+4,bottom);memory.writeWord(p+6,right);}
}

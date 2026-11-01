import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HyperCard 2.x external windows (XWindoids) of one 68K external: the callbacks that create and configure them
 * (requests 100-113 of HyperXCmd.h) and the delivery of their events.
 *
 * HyperCard calls the external's code for every event of its window with an XCmdBlock whose paramCount is -1 and
 * whose params[0] is an XWEventInfo (the EventRecord, the window, nine parameters, a result Handle). The window is
 * created by the Window Manager like any other, so the host shows it as a native window; its events land in the VM's
 * event queue and are handed to the external from the script thread ({@link #pump}), which also sends null events at
 * the interval SetXWIdleTime asked for. xOpenEvt goes to the external once the call that made the window has returned, as in
 * HyperCard ({@link #afterCall}); the close box sends xCloseEvt, and the window is closed afterwards when the external did not do it.
 */
final class XWindows68k {
	static final int X_OPEN_EVT=1000,X_CLOSE_EVT=1001,X_GIVE_UP_EDIT_EVT=1002,X_GIVE_UP_SOUND_EVT=1003,X_HIDE_PALETTES_EVT=1004,X_SHOW_PALETTES_EVT=1005,
		X_SEND_EVT=1200,X_SET_PROP_EVT=1201,X_GET_PROP_EVT=1202,X_CURSOR_WITHIN=1300,X_MENU_EVT=1400,X_MBAR_CLICKED_EVT=1401;
	private static final int XRES_SUCC=0,XRES_FAIL=1,XRES_NOT_IMP=2;
	private static final int RESULT_OFFSET=78,IN_ARGS_OFFSET=80,OUT_ARGS_OFFSET=112;
	private static final int EVENT_INFO_SIZE=60,BLOCK_SIZE=128;
	private static final boolean TRACE=Mac68kApi.TRACE||Boolean.getBoolean("hz68k.xwindow");
	/** Ticks between null events unless SetXWIdleTime asked for more: Terazza2 repaints the whole card at every null event, which at
	 *  HyperCard's own rate (every pass of its event loop) kept a core a third busy while the stack sat idle (-Dhz68k.xwidle=N). */
	private static final int DEFAULT_IDLE_TICKS=Integer.getInteger("hz68k.xwidle",3);

	/** One external window and what the external asked for it. */
	static final class XWindow {
		final int record;final boolean floating;
		/** Ticks between null events (SetXWIdleTime); 0 = at every pass. */
		int idleTicks;int lastIdleTick=Integer.MIN_VALUE;
		boolean interruptCode,moveHigh,allowSystemEvents,allowHyperCardEvents,editing;
		/** xOpenEvt has been delivered. */
		boolean opened;
		/** How deep in the VM's calls the window was made (Session.running): a nested call that returns opens only its own windows. */
		int madeAt;
		final List<Integer> menus=new ArrayList<Integer>();
		/** Where the external that created the window starts: its events go to that code (a VM can hold several externals). */
		int entry=Xcmd68k.CODE_BASE;
		XWindow(int record,boolean floating){this.record=record;this.floating=floating;}
	}

	private final Xcmd68k.Session session;
	private final M68kMemory memory;
	private final Mac68kApi macApi;
	private final Map<Integer,XWindow> windows=new LinkedHashMap<Integer,XWindow>();
	private int depth;
	private boolean pumping;
	/** The host menu bar was clicked: windows that registered menus (RegisterXWMenu) get xMBarClickedEvt at the next pump. */
	private volatile boolean menuBarClicked;
	/** Choices {menuId, item} from menus a window registered, for its xMenuEvt at the next pump. */
	private final java.util.concurrent.ConcurrentLinkedQueue<int[]> menuChoices=new java.util.concurrent.ConcurrentLinkedQueue<int[]>();

	XWindows68k(Xcmd68k.Session session){this.session=session;memory=session.memory;macApi=session.macApi;trackDrags();}
	/** The external tracks drags of its windows itself (FindWindow inDrag, DragWindow): a native move of one reaches it as that click. */
	private void trackDrags(){Toolbox68k tb=tb();if(tb!=null&&tb.windows!=null)tb.windows.dragClickWindows=r->windows.containsKey(r);}

	boolean isEmpty(){return windows.isEmpty();}
	XWindow window(int record){return windows.get(record);}
	List<XWindow> windows(){return new ArrayList<XWindow>(windows.values());}
	private Toolbox68k tb(){return macApi.application();}

	// ------------------------------------------------------------------ callbacks

	/** Serves the external-window callbacks; false for requests that are not about windows. */
	boolean callback(int request,int block) throws M68kException {
		tb().attach(session.cpu); // an external may open its window before its first trap
		switch(request){
			case 100:{ // NewXWindow / GetNewXWindow: NewXWindow passes the bounds Rect and title, GetNewXWindow a template type and ID
				boolean color=in(block,0)!=0,visible=in(block,1)!=0,floating=in(block,7)!=0;
				int type=in(block,2),templateId=(short)in(block,3),procId=(short)in(block,4),rect=in(block,5),title=in(block,6);
				int w=rect!=0?newWindow(rect,title,visible,procId,color,floating):newWindowFromTemplate(type,templateId,color,floating);
				out(block,0,w);result(block,w==0?XRES_FAIL:XRES_SUCC);return true;}
			// CloseXWindow: the window gets its xCloseEvt first, unless the call comes from that xCloseEvt (HyperCard J-2.3 measured,
			// Oct 24 2026: an assembled XCMD counted one xCloseEvt per CloseXWindow). XMover's "Close" loops `while FindInstance(file):
			// CloseXWindow(its window)` and unlinks the instance only in its xCloseEvt handler (HandleClose, which then passes): with no
			// event it called CloseXWindow 93,000 times and HyperCard hung. HyperCard also leaves the window open when the handler
			// neither passes nor closes it; here it closes anyway (no external is known to want it kept)
			case 101:{XWindow w=windows.get(in(block,0));if(w!=null){sendClose(w);if(windows.containsKey(w.record))close(w);}result(block,w==null?XRES_FAIL:XRES_SUCC);return true;}
			case 102:{XWindow w=windows.get(in(block,0));if(w!=null)w.idleTicks=Math.max(0,in(block,1));if(TRACE)System.err.println("[xwindow] SetXWIdleTime $"+Integer.toHexString(in(block,0))+" "+in(block,1));result(block,w==null?XRES_FAIL:XRES_SUCC);return true;} // SetXWIdleTime
			case 103:{XWindow w=windows.get(in(block,0));if(w!=null)w.interruptCode=in(block,1)!=0;result(block,w==null?XRES_FAIL:XRES_SUCC);return true;} // XWHasInterruptCode
			case 104:{XWindow w=windows.get(in(block,0));int menu=in(block,1);if(w!=null){ // RegisterXWMenu(window, MenuHandle, registering)
				if(in(block,2)!=0){if(!w.menus.contains(menu))w.menus.add(menu);}else w.menus.remove(Integer.valueOf(menu));}result(block,w==null?XRES_FAIL:XRES_SUCC);return true;} // RegisterXWMenu
			case 105:{XWindow w=windows.get(in(block,0));if(w!=null)w.editing=in(block,1)!=0;result(block,XRES_SUCC);return true;} // BeginXWEdit / EndXWEdit: HyperCard's Edit menu is not shared here
			case 106:result(block,XRES_NOT_IMP);return true; // SaveXWScript (script editor windoids)
			case 107:case 108:result(block,XRES_NOT_IMP);return true; // Get/SetCheckPoints (debugger windoids)
			case 109:{XWindow w=windows.get(in(block,0));if(w!=null){w.allowSystemEvents=in(block,1)!=0;w.allowHyperCardEvents=in(block,2)!=0;}result(block,w==null?XRES_FAIL:XRES_SUCC);return true;} // XWAllowReentrancy
			case 110:{ // SendWindowMessage(window, windowName, msg): xSendEvt with the message string
				XWindow w=windows.get(in(block,0));
				if(w==null&&in(block,1)!=0){String name=tb().readPascal(in(block,1));for(XWindow x:windows.values()){WindowManager68k.Window win=tb().windows.window(x.record);if(win!=null&&name.equalsIgnoreCase(win.title)){w=x;break;}}}
				if(w==null){result(block,XRES_FAIL);return true;}
				//HyperCard J-2.3 (CODE 43) hands the external's own message pointer (a Str255) on as eventParams[0]
				dispatch(w,X_SEND_EVT,0,new int[]{in(block,2)});
				result(block,XRES_SUCC);return true;}
			// Hide/ShowHCPalettes: the message box and the Tools palette go away until ShowHCPalettes (Caliban's HideHCpals before ScreenShot)
			case 111:case 112:showHyperCardPalettes(request==112);result(block,XRES_SUCC);return true;
			case 113:{XWindow w=windows.get(in(block,0));if(w!=null)w.moveHigh=in(block,1)!=0;result(block,w==null?XRES_FAIL:XRES_SUCC);return true;} // XWAlwaysMoveHigh
			default:return false;
		}
	}

	/** The palettes HideHCPalettes hid, which ShowHCPalettes brings back (only those). */
	private static final java.util.List<java.awt.Window> hiddenPalettes=new java.util.ArrayList<java.awt.Window>();
	private static void showHyperCardPalettes(boolean show){
		synchronized(hiddenPalettes){
			if(show){for(java.awt.Window w:hiddenPalettes)w.setVisible(true);hiddenPalettes.clear();return;}
			java.awt.Window tools=PCARD.pc!=null&&PCARD.pc.toolbar!=null?PCARD.pc.toolbar.tb:null;
			for(java.awt.Window w:new java.awt.Window[]{GMsg.msg,tools})if(w!=null&&w.isVisible()&&!hiddenPalettes.contains(w)){w.setVisible(false);hiddenPalettes.add(w);}
		}
	}

	private int newWindow(int rect,int title,boolean visible,int procId,boolean color,boolean floating) throws M68kException {
		Toolbox68k tb=tb();int[] bounds=tb.windows.readRect(rect);String name=tb.readPascal(title);
		if(TRACE)System.err.println("[xwindow] NewXWindow \""+name+"\" t,l,b,r="+bounds[0]+","+bounds[1]+","+bounds[2]+","+bounds[3]+" procID="+procId+" visible="+visible+" color="+color+" floating="+floating);
		// HyperCard makes it with NewCWindow, which leaves the caller's port current: PgColorX creates a hidden 0x0 window, then
		// GetPorts the card window for "cd" - its first drawing of a session went into the hidden window
		int savedPort=tb.api.currentQuickDrawPort();
		int w=tb.windows.create(0,WindowManager68k.WINDOW_SIZE,bounds,name,false,procId,-1,true,0,color);
		tb.windows.keepCallersPort(savedPort);
		return w==0?0:register(w,visible,floating);
	}

	private static final int DLOG=0x444c4f47;
	private int newWindowFromTemplate(int type,int id,boolean color,boolean floating) throws M68kException {
		if(TRACE)System.err.println("[xwindow] GetNewXWindow '"+ResourceFile68k.typeName(type)+"' "+id+" color="+color+" floating="+floating);
		// a 'DLOG' template is a dialog with its 'DITL' items, as GetNewDialog makes it (Rinaldi's ResMover: a blank window titled
		// "\u00e0ResMover" when the DLOG was read as a WIND, its lists and buttons missing)
		Toolbox68k tb=tb();int savedPort=tb.api.currentQuickDrawPort();
		int w=type==DLOG?tb.dialogs.fromResource(id,0,-1,false):tb.windows.createFromTemplate(type,id,0,-1,color);
		tb.windows.keepCallersPort(savedPort); // GetNewCWindow/GetNewDialog leave the caller's port current
		if(w==0)return 0;
		boolean visible=tb.windows.isVisible(w);if(visible)tb.windows.show(w,false);
		return register(w,visible,floating);
	}

	/** Registers a freshly created window as an XWindoid; its xOpenEvt follows once the creating call has returned ({@link #afterCall}). */
	private int register(int record,boolean visible,boolean floating) throws M68kException {
		XWindow w=new XWindow(record,floating);w.entry=session.entry;w.madeAt=session.running;windows.put(record,w);
		// windowKind: HyperCard J-2.3 gives a floating windoid 8 (as its palettes and the message box) and a document-layer external
		// window 20 (measured with NewXWindow and the picture command's windoid/zoom windows); the stack windows are $1E
		memory.writeWord(record+108,floating?8:20);
		if(floating&&tb().hosted())tb().host().setWindoid(record); // HyperCard's card window stays active under a floating windoid
		if(visible)tb().windows.show(record,true);
		return record;
	}

	/**
	 * The external has returned to HyperCard (from a HyperTalk call or an event): a window it created gets its xOpenEvt now,
	 * "the first event after you are created". It is not sent from inside NewXWindow: externals keep the XCmdBlock of the
	 * running call in a global (Terazza2 does) and re-entering them with another block would leave that global dangling -
	 * which is also why re-entrancy is off until an external asks for it (XWAllowReentrancy, typically from its xOpenEvt).
	 */
	void afterCall() throws M68kException {afterCall(0);}

	/**
	 * {@link #afterCall()} for a call {@code depth} deep that returned while the calls below it still run: only the windows it
	 * (or a call inside it) made get their xOpenEvt now. HyperCard J-2.3 measured (Oct 24 2026, an assembled XCMD that makes its
	 * window from a SendCardMessage "XMover o" and then sets a property of it: xOpenEvt came at once when the nested call returned,
	 * before the next callback and the xSetPropEvt). PowerPalettes' doPowerPalettes runs SuperPalette that way and sets the
	 * palette's properties right after: before xOpenEvt SuperPalette refused them ("Cannot set that palette property ...").
	 * A window the still-running outer call made waits for that call to return (Terazza2, above).
	 */
	void afterCall(int depth) throws M68kException {
		for(XWindow w:windows()){
			if(w.opened||w.madeAt<depth||!windows.containsKey(w.record))continue;
			w.opened=true;
			dispatch(w,X_OPEN_EVT,0,null);
		}
	}

	/** The windows whose xCloseEvt is being delivered: a CloseXWindow from that handler closes the window without another one. */
	private final java.util.Set<Integer> closing=new java.util.HashSet<Integer>();
	/** xCloseEvt to {@code w}, unless it is getting one now (its handler calls CloseXWindow, as most do). */
	private Answer sendClose(XWindow w) throws M68kException {
		if(!closing.add(w.record))return null;
		try{return dispatch(w,X_CLOSE_EVT,0,null);}finally{closing.remove(w.record);}
	}

	/** Disposes an external window (its xCloseEvt has been sent, see {@link #sendClose}). */
	private void close(XWindow w) throws M68kCpuException {
		if(windows.remove(w.record)==null)return;
		if(TRACE){System.err.println("[xwindow] close $"+Integer.toHexString(w.record));if(Boolean.getBoolean("hz68k.xwindowstack"))new Throwable("close").printStackTrace();}
		Toolbox68k tb=tb();
		if(tb.windows.window(w.record)!=null)tb.windows.disposeWindow(w.record);
	}

	/** xCloseEvt, then close, for the windows made by the externals loaded at {@code entries} (a stack that closes while the VM
	 *  stays for another stack's externals) */
	void closeMadeBy(java.util.Set<Integer> entries) throws M68kException {
		for(XWindow w:windows()){
			if(!windows.containsKey(w.record)||!entries.contains(w.entry))continue;
			try{sendClose(w);}catch(M68kException e){if(TRACE)System.err.println("[xwindow] xCloseEvt failed: "+e.getMessage());}
			if(windows.containsKey(w.record))close(w);
		}
	}

	/** xCloseEvt to every window, then they are closed; the VM is about to end. */
	void closeAll() throws M68kException {
		for(XWindow w:windows()){
			if(!windows.containsKey(w.record))continue;
			try{sendClose(w);}catch(M68kException e){if(TRACE)System.err.println("[xwindow] xCloseEvt failed: "+e.getMessage());}
			if(windows.containsKey(w.record))close(w);
		}
	}

	// ------------------------------------------------------------------ events

	/** What the external answered to an event: whether it passed it (passFlag) and its eventResult text, when it returned one. */
	static final class Answer {boolean passed;String result;String returnValue;}

	/** Calls the external with an event of kind {@code what} for {@code w}; {@code params} fill eventParams. */
	private Answer dispatch(XWindow w,int what,int message,int[] params) throws M68kException {
		EventManager68k.Event e=new EventManager68k.Event(what);e.message=message;
		Toolbox68k tb=tb();java.awt.Point p=tb.host().mouseLocation();e.whereH=p.x;e.whereV=p.y;e.modifiers=tb.host().modifiers()|(tb.host().isMouseDown()?0:0x80);
		return dispatch(w,e,params);
	}

	private Answer dispatch(XWindow w,EventManager68k.Event e,int[] params) throws M68kException {
		Answer answer=new Answer();
		Toolbox68k tb=tb();M68kCpu cpu=session.cpu;
		int info=memory.allocate(EVENT_INFO_SIZE,2),block=memory.allocate(BLOCK_SIZE,2);
		memory.fill(info,EVENT_INFO_SIZE,0);memory.fill(block,BLOCK_SIZE,0);
		tb.events.writeEventRecord(info,e);
		memory.writeLong(info+16,w.record);
		if(params!=null)for(int i=0;i<params.length&&i<9;i++)memory.writeLong(info+20+i*4,params[i]);
		memory.writeWord(block,0xffff); // paramCount -1: an XWindoid event
		memory.writeLong(block+2,info);
		memory.writeLong(block+72,M68kCpu.CALLBACK_SENTINEL);
		int savedPort=macApi.currentQuickDrawPort();
		if(TRACE)System.err.println("[xwindow] event "+e.what+" message=$"+Integer.toHexString(e.message)+" -> $"+Integer.toHexString(w.record)+(depth>0?" (nested)":""));
		depth++;session.running++;
		int savedA4=cpu.a[4];
		try{
			if(session.running==1){cpu.a[7]=0x00fff000;if(tb.hosted())tb.host().externalEntered();}
			tb.attach(cpu);
			// the port is HyperCard's card window, not the event's window (HC J-2.3 measured, Oct 25 2026: thePort was the card window
			// in xOpenEvt, update, activate, xSetPropEvt, xGetPropEvt, xSendEvt, a mouseDown and xCloseEvt). MegaWindow's
			// xSetPropEvt "rect" saves that port and offsets the rect by its origin: with its own window current, `set the rect of
			// window "Example"` added the window's place every time and the window walked off the screen
			if(tb.cardWindow!=null)tb.api.quickDraw().setPortTo(cpu,tb.cardWindow.record);
			cpu.a[4]=session.strayA4; // HyperCard's A4, not 0 (see Xcmd68k.Session.strayA4)
			cpu.callExternal(w.entry,block);
		}
		finally{
			cpu.a[4]=savedA4;
			depth--;session.running--;
			answer.passed=memory.readByte(block+70)!=0; // passFlag: the external did not handle the event
			// the external's returnValue: `the result` of send "x" to window (Chartoid's saveNoDialog path, Listoid's "Error : Not found")
			int result=memory.readLong(block+66);if(result!=0){int text=memory.readLong(result);if(text!=0)answer.returnValue=Xcmd68k.swapLineBreaks(Xcmd68k.decodeText(session.encoding,memory.readCString(text,Integer.MAX_VALUE)));macApi.disposeHandleAndFree(result);}
			int eventResult=memory.readLong(info+56);
			if(eventResult!=0){int text=memory.readLong(eventResult);if(text!=0)answer.result=Xcmd68k.decodeText(session.encoding,memory.readCString(text,Integer.MAX_VALUE));answer.result=Xcmd68k.swapLineBreaks(answer.result);macApi.disposeHandleAndFree(eventResult);}
			memory.free(info);memory.free(block);
			// back to the port of the caller - unless the external closed that window meanwhile
			boolean alive=savedPort!=0&&(tb.windows.window(savedPort)!=null||(tb.cardWindow!=null&&tb.cardWindow.records().contains(savedPort)));
			int port=alive?savedPort:tb.cardWindow!=null?tb.cardWindow.record:0;
			if(port!=0)tb.api.quickDraw().setPortTo(cpu,port);
		}
		return answer;
	}

	// ------------------------------------------------------------------ script access (window "name")

	/** The external window titled {@code name} (case-insensitive), or null. */
	XWindow findByTitle(String name){
		if(name==null)return null;
		for(XWindow x:windows.values()){WindowManager68k.Window win=tb().windows.window(x.record);if(win!=null&&name.equalsIgnoreCase(win.title))return x;}
		return null;
	}
	/** The Window Manager record behind {@code w} (null once it is gone). */
	WindowManager68k.Window record(XWindow w){return windows.containsKey(w.record)?tb().windows.window(w.record):null;}
	void show(XWindow w,boolean visible) throws M68kCpuException {tb().attach(session.cpu);tb().windows.show(w.record,visible);}
	void move(XWindow w,int left,int top) throws M68kCpuException {tb().attach(session.cpu);tb().windows.move(w.record,left,top);}
	void resize(XWindow w,int width,int height) throws M68kCpuException {tb().attach(session.cpu);tb().windows.resize(w.record,width,height,false,true);}

	/**
	 * send "text" to window "name": xSendEvt with eventParams[0] a pointer to the message as a Str255 (HyperCard J-2.3 CODE 41
	 * copies it into a 256-byte local string before the event; Pictoid XCMD looks its verbs up with CmpString on that length byte).
	 */
	String send(XWindow w,String message) throws M68kException {
		int text=pascal(classicLines(message));
		try{Answer a=dispatch(w,X_SEND_EVT,0,new int[]{text});return a==null||a.returnValue==null?"":a.returnValue;}finally{memory.free(text);}
	}

	/**
	 * the {@code property} of window "name" for a property HyperCard does not keep itself: xGetPropEvt with params[0] a
	 * pointer to the property name (Pascal string); the external answers with eventResult, a Handle to a zero-terminated
	 * string. Null when it passed the event (no such property).
	 */
	String getProperty(XWindow w,String property) throws M68kException {
		int name=pascal(property);
		try{Answer a=dispatch(w,X_GET_PROP_EVT,0,new int[]{name});return a.passed?null:a.result==null?"":a.result;}
		finally{memory.free(name);}
	}

	/**
	 * set the {@code property} of window "name" to {@code value}: xSetPropEvt with params[0] the property name (Pascal
	 * string pointer) and params[1] the value, a Handle to a zero-terminated string.
	 * False when the external passed the event.
	 */
	boolean setProperty(XWindow w,String property,String value) throws M68kException {
		int name=pascal(property);
		//lines end in return, as in HyperCard (Macbeth's OWindow parses "set the objects of window" line by line; an LF ran
		//the whole definition into one Str255 on its stack)
		byte[] bytes=session.encoding.encodeReplacing(classicLines(value==null?"":value));
		int handle=macApi.newHandleBlock(bytes.length+1);int text=memory.readLong(handle);memory.load(text,bytes);memory.writeByte(text+bytes.length,0);
		try{return !dispatch(w,X_SET_PROP_EVT,0,new int[]{name,handle}).passed;}
		finally{memory.free(name);if(macApi.isHandle(handle))macApi.disposeHandleAndFree(handle);}
	}

	/** close window "name": xCloseEvt, then the window goes unless the external closed it already. */
	void closeFromScript(XWindow w) throws M68kException {
		sendClose(w);
		if(windows.containsKey(w.record))close(w);
	}

	private static String classicLines(String text){return Xcmd68k.swapLineBreaks(text);}

	private int pascal(String text) throws M68kCpuException {
		byte[] bytes=session.encoding.encodeReplacing(text==null?"":text);int length=Math.min(255,bytes.length);
		int p=memory.allocate(length+2,2);memory.writeByte(p,length);for(int i=0;i<length;i++)memory.writeByte(p+1+i,bytes[i]);memory.writeByte(p+1+length,0);
		return p;
	}

	/** FrontDocWindow: this VM's frontmost visible document (not floating) window when it holds the activation - the user clicked
	 *  it or the external selected it in front of the card window - else 0 (the card window is the front document). A window sent
	 *  behind the card (UxBack's backdrop) never is. */
	int frontDocument(){
		Toolbox68k tb=tb();if(!tb.hosted())return 0;
		for(int record:tb.windows.visibleWindows()){XWindow w=windows.get(record);if(w==null||w.floating)continue;
			return tb.host().isActiveWindow(record)&&!tb.host().behindHyperCard(record)?record:0;}
		return 0;
	}

	/** The window that registered menu {@code menuId} with RegisterXWMenu (null: none). */
	private XWindow ownerOfMenu(int menuId){
		// RegisterXWMenu hands the MenuHandle; its menuID is the record's first word
		try{for(XWindow w:windows.values())for(int h:w.menus){int p=h==0?0:memory.readLong(h);if(p!=0&&(short)memory.readWord(p)==menuId)return w;}}
		catch(java.util.ConcurrentModificationException|M68kCpuException e){} // asked from the host's thread too
		return null;
	}
	/** Whether a window registered a menu (it wants xMBarClickedEvt). */
	boolean hasRegisteredMenus(){try{for(XWindow w:windows.values())if(!w.menus.isEmpty())return true;}catch(java.util.ConcurrentModificationException e){}return false;}
	/**
	 * The user clicked the menu bar (called from the host's thread): HyperCard sends xMBarClickedEvt to every window that
	 * registered a menu before the menu opens, so the external can fill or update it - WindowsMenu lists the open windows in
	 * its "Windows" menu then, which stayed empty forever.
	 */
	void noteMenuBarClicked(){if(hasRegisteredMenus())menuBarClicked=true;}
	/** An item of menu {@code menuId} was chosen (host thread): true when a window registered that menu - it gets xMenuEvt, and
	 *  HyperCard does not handle the choice itself. */
	boolean noteMenuChoice(int menuId,int item){if(ownerOfMenu(menuId)==null)return false;menuChoices.add(new int[]{menuId,item});return true;}
	boolean hasPendingMenuWork(){return menuBarClicked||!menuChoices.isEmpty();}

	/** The frontmost visible external window (null when none). */
	private XWindow front(){
		for(int record:tb().windows.visibleWindows()){XWindow w=windows.get(record);if(w!=null)return w;}
		return null;
	}

	/** Hands the queued host events to the windows they belong to and sends the null events that are due. */
	void pump() throws M68kException {pump(false);}

	/**
	 * {@link #pump()}; {@code handlerRunning}: called between the statements of a running handler, when HyperCard serves no
	 * clicks - they stay queued for its main loop, and the script's {@code the mouseClick} takes them ({@link #takeClick}).
	 * Myst Preview's PlayLogo loops on {@code send movieIdle to window} + {@code if the mouseClick}: a click on the movie went
	 * to the Movie XCMD mid-loop, whose mouseDownInMovie closed the window, and the next movieIdle had no window.
	 */
	void pump(boolean handlerRunning) throws M68kException {pump(handlerRunning,handlerRunning);}

	/**
	 * {@link #pump(boolean)}; {@code scriptRunning}: a handler runs or waits (wait), and only windows whose external asked for
	 * them with XWHasInterruptCode get null events then. HyperCard J-2.3 measured: KKprogressBar's window, which closes itself
	 * at its first null event, stays open through a handler's loops and waits and closes at the next idle.
	 */
	void pump(boolean handlerRunning,boolean scriptRunning) throws M68kException {
		if(pumping||windows.isEmpty())return;pumping=true;
		try{
			Toolbox68k tb=tb();tb.attach(session.cpu);trackDrags();
			tb.runEventWork();tb.updateTicks();tb.runTimers();
			tb.refreshCardWindow(); // the card may have changed since the external last drew on it
			afterCall();
			if(!handlerRunning)tb.windows.postPendingDragClick(); // a window the user dragged: its click in the title bar
			else tb.windows.settlePendingDrag();
			if(menuBarClicked){menuBarClicked=false;for(XWindow w:windows())if(windows.containsKey(w.record)&&!w.menus.isEmpty()){dispatch(w,X_MBAR_CLICKED_EVT,0,new int[0]);afterCall();}}
			for(int[] c;(c=menuChoices.poll())!=null;){XWindow w=ownerOfMenu(c[0]);if(w!=null){dispatch(w,X_MENU_EVT,(c[0]<<16)|(c[1]&0xffff),new int[]{c[0],c[1]});afterCall();}}
			for(int n=0;n<64&&!windows.isEmpty();n++){
				EventManager68k.Event e=tb.events.take(handlerRunning?0xffff&~(EventManager68k.MOUSE_DOWN_MASK|EventManager68k.MOUSE_UP_MASK):0xffff,true);if(e==null)break;
				route(e);afterCall();
			}
			if(tb.windows.dragClickOut())tb.windows.finishPendingDrag(); // the external did not drag it itself: it stays where the user put it
			int tick=tb.tickCount();
			for(XWindow w:windows()){
				if(!windows.containsKey(w.record)||scriptRunning&&!w.interruptCode)continue;
				if((long)tick-w.lastIdleTick<Math.max(w.idleTicks,DEFAULT_IDLE_TICKS))continue;
				w.lastIdleTick=tick;
				EventManager68k.Event idle=new EventManager68k.Event(EventManager68k.NULL_EVENT);
				java.awt.Point p=tb.host().mouseLocation();idle.whereH=p.x;idle.whereV=p.y;idle.modifiers=tb.host().modifiers()|(tb.host().isMouseDown()?0:0x80);
				dispatch(w,idle,null);
			}
			tb.menus.flushIfDirty(); // what the window events did to the menus (WindowsMenu fills its menu at xMBarClickedEvt)
			if(tb.cardWindow!=null)tb.cardWindow.flush();
		}
		finally{pumping=false;}
	}

	/** the mouseClick: a click in one of the windows (mouse-down and its mouse-up) still queued is taken out, as HyperCard's
	 *  function takes it from the event queue - it reaches no window afterwards. */
	boolean takeClick(){return !windows.isEmpty()&&tb().events.takeClick();}

	private void route(EventManager68k.Event e) throws M68kException {
		Toolbox68k tb=tb();
		switch(e.what){
			case EventManager68k.MOUSE_DOWN:{
				if(e.menuId==-1){ // the host close button
					XWindow w=windows.get(e.window);if(w==null)return;
					// first the click in the close box for the external, as HyperCard hands it on: one that tracks it decides (UxPalette
					// asks its stack's okUxPaletteClose and may keep the window, a cancel left it closed); FindWindow says inGoAway
					int tracks=tb.windows.goAwayTracks;
					dispatch(w,e,null);
					if(!windows.containsKey(w.record)||tb.windows.goAwayTracks!=tracks)return;
					// an external that does not look at its close box: xCloseEvt, then the window goes unless the external closed it already
					tb.windows.takeGoAway(w.record);
					sendClose(w);
					if(windows.containsKey(w.record))close(w);
					return;}
				if(e.menuId!=0||e.menuItem!=0){XWindow w=ownerOfMenu(e.menuId);if(w==null)w=front();if(w!=null)dispatch(w,X_MENU_EVT,(e.menuId<<16)|(e.menuItem&0xffff),new int[]{e.menuId,e.menuItem});return;}
				XWindow w=windows.get(tb.windows.windowAt(e.whereH,e.whereV));if(w==null)w=front();
				if(w!=null)dispatch(w,e,null);return;}
			case EventManager68k.MOUSE_UP:{XWindow w=windows.get(tb.windows.windowAt(e.whereH,e.whereV));if(w==null)w=front();if(w!=null)dispatch(w,e,null);return;}
			case EventManager68k.KEY_DOWN:case EventManager68k.AUTO_KEY:case EventManager68k.KEY_UP:{XWindow w=front();if(w!=null)dispatch(w,e,null);return;}
			case EventManager68k.UPDATE_EVT:case EventManager68k.ACTIVATE_EVT:{XWindow w=windows.get(e.message);if(w!=null)dispatch(w,e,null);return;}
			default:return; // high-level and OS events are HyperCard's
		}
	}

	/**
	 * SendHCEvent: an event an external took from the queue itself, handed to HyperCard, which gives an external window's
	 * update, activate, click and key to that window's external as its own loop would (HC J-2.3 measured: an updateEvt and a
	 * mouseDown of an XWindoid reached its external, a keyDown while the window was not the front one did not). MegaWindow's
	 * MegaModalize runs its dialog this way. False when no external window takes it.
	 */
	boolean fromSendHCEvent(EventManager68k.Event e) throws M68kException {
		if(windows.isEmpty())return false;
		Toolbox68k tb=tb();XWindow w=null;
		switch(e.what){
			case EventManager68k.UPDATE_EVT:case EventManager68k.ACTIVATE_EVT:w=windows.get(e.message);break;
			case EventManager68k.MOUSE_DOWN:case EventManager68k.MOUSE_UP:w=windows.get(tb.windows.windowAt(e.whereH,e.whereV));break;
			case EventManager68k.KEY_DOWN:case EventManager68k.AUTO_KEY:case EventManager68k.KEY_UP:{
				List<Integer> visible=tb.windows.visibleWindows();w=visible.isEmpty()?null:windows.get(visible.get(0));
				if(w!=null&&w.floating)w=null;break;}
			default:break;
		}
		if(TRACE&&e.what!=EventManager68k.NULL_EVENT)System.err.println("[xwindow] SendHCEvent "+e.what+" message=$"+Integer.toHexString(e.message)+" -> "+(w==null?"HyperCard (visible "+tb.windows.visibleWindows()+")":"$"+Integer.toHexString(w.record)));
		if(w==null)return false;
		dispatch(w,e,null);
		return true;
	}

	private int in(int block,int index) throws M68kCpuException {return memory.readLong(block+IN_ARGS_OFFSET+index*4);}
	private void out(int block,int index,int value) throws M68kCpuException {memory.writeLong(block+OUT_ARGS_OFFSET+index*4,value);}
	private void result(int block,int value) throws M68kCpuException {memory.writeWord(block+RESULT_OFFSET,value);}
}

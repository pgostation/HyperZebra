import java.awt.Point;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Event Manager for hosted applications: a thread-safe queue fed by the Swing
 * host, WaitNextEvent/GetNextEvent with sleeping, activate and update event
 * synthesis, and the synthetic menu-bar events used to report native menu
 * selections.
 */
final class EventManager68k implements MacAppHost.Listener {
	static final int NULL_EVENT=0,MOUSE_DOWN=1,MOUSE_UP=2,KEY_DOWN=3,KEY_UP=4,AUTO_KEY=5,UPDATE_EVT=6,DISK_EVT=7,ACTIVATE_EVT=8,OS_EVT=15,HIGH_LEVEL_EVT=23;
	static final int MOUSE_DOWN_MASK=2,MOUSE_UP_MASK=4,KEY_DOWN_MASK=8,KEY_UP_MASK=16,AUTO_KEY_MASK=32,UPDATE_MASK=64,ACTIVATE_MASK=256,OS_MASK=1<<15,HIGH_LEVEL_MASK=1<<10;

	static final class Event {
		int what,message,when,whereV,whereH,modifiers;
		int menuId,menuItem;List<File> files;int window;
		/** A click made up by the Event Manager (mouse wheel): TrackControl acts once and returns without waiting for the button. */
		boolean synthetic;
		/** A high-level event from another running program: the Apple event itself (AESend), or a raw message buffer (PostHighLevelEvent). */
		AppleEvents68k.Portable appleEvent;byte[] buffer;int refcon,senderSerial;
		/** HyperZebra's quit request (the Quit menu item chosen for the program, or the 'quit' Apple event): see {@link #quitProgress}. */
		boolean quitRequest;
		/** A menu-bar click made up while the user opens a menu of the host bar (see {@link #postMenuProbe}): MenuSelect answers 0. */
		boolean probe;
		/** Queued by the program itself (PostEvent): its queue element (EvQEl, PPostEvent hands it back) holds what it may change
		 *  afterwards, 0 when there is none. */
		boolean posted;int element;
		/** A key typed on HyperCard's card window, handed to an external without windows (see {@link #hostKeys}). */
		CardKey cardKey;
		/** The Open Application/Documents event of the launch: not seen by the program's first GetNextEvent/WaitNextEvent (see {@link #take}). */
		boolean launch;
		/** A suspend or resume event whose clipboard state was taken (once, though EventAvail may show it first). */
		boolean clipboardNoted;
		Event(int what){this.what=what;}
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Deque<Event> queue=new ArrayDeque<Event>();
	private final Object lock=new Object();
	/** Pending synthetic menu selection reported by FindWindow/MenuSelect. */
	Event pendingMenuEvent;
	private int lastMouseDownTick,lastKeyDownCode=-1;
	private long lastKeyDownTime;
	private volatile boolean quitRequested;
	private int lastActivatedWindow;
	/** Window whose close-box mouseDown the program has just taken, and the windows visible at that moment (see {@link #closeIgnoredGoAway}). */
	private int goAwayDelivered;private List<Integer> visibleAtGoAway;private int goAwayTracksBefore,windowsCreatedBefore;private boolean goAwayBehindDialog;
	/** The Dialog Manager refused a host close-box click while a modal dialog was up (it beeped): nothing for closeIgnoredGoAway to do. */
	void goAwayRefused(){goAwayDelivered=0;visibleAtGoAway=null;}

	EventManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x860:return "WaitNextEvent";case 0x970:return "GetNextEvent";case 0x971:return "EventAvail";case 0x972:return "GetMouse";case 0x973:return "StillDown";
			case 0x974:return "Button";case 0x977:return "WaitMouseUp";case 0x975:return "TickCount";case 0x976:return "GetKeys";case 0x9c3:return "KeyTranslate";
			default:return null;
		}
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		// The Menu Manager's offscreen MDEF probes stand for MenuSelect's tracking, where the button is down: HyperCard's Patterns
		// palette MDEF takes an mChooseMsg over a cell with the button up for the release and sets that pattern (the layout
		// probes chose pattern 1, white, when a paint tool put the palette in the menu bar).
		if(tb.offscreenMdef>0&&(trap==0x973||trap==0x974||trap==0x977)){tb.writeBoolean(cpu.a[7],!tb.offscreenMdefLeft);return true;}
		switch(trap){
			case 0x860:if(tb.hosted()){tb.host().eventCall();tb.menus.showMenuBarHatching();}return waitNextEvent(cpu); // no blit spans an event call, and the frame is done: shown now
			case 0x970:if(tb.hosted()){tb.host().eventCall();tb.menus.showMenuBarHatching();}return getNextEvent(cpu,true);
			case 0x971:lastQueueLook=System.nanoTime();return getNextEvent(cpu,false);
			case 0x972:tb.mousePolled(cpu);return getMouse(cpu);
			case 0x973:tb.runDeferred();tb.menus.flushIfDirty();tb.mousePolled(cpu);tb.writeBoolean(cpu.a[7],tb.host().isMouseDown());return true;
			case 0x974:tb.runDeferred();tb.menus.flushIfDirty();tb.mousePolled(cpu);tb.writeBoolean(cpu.a[7],tb.host().isMouseDown());return true; // menus changed in a loop that polls Button/GetKeys instead of WaitNextEvent (Factory's day) reach the host bar too
			case 0x977:{tb.mousePolled(cpu);boolean down=tb.host().isMouseDown();if(!down)discardNextMouseUp();tb.writeBoolean(cpu.a[7],down);return true;}
			case 0x975:{int tick=tb.tickCountPolled();tb.updateTicks(tick);memory.writeLong(cpu.a[7],tick);return true;}
			case 0x976:{int keyMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;tb.menus.flushIfDirty();lastKeyPoll=System.nanoTime();memory.fill(keyMap,16,0);writeKeys(keyMap);if(tb.host().external)GUI.noteKeyPoll();return true;} // an XCMD reading the keys (KStealKeyX) owns them
			default:return false;
		}
	}

	// ------------------------------------------------------------------ host -> queue

	private boolean post(Event e){
		if(!enabled(e.what))return false;
		e.when=tb.tickCount();
		synchronized(lock){
			if(e.what==MOUSE_UP&&System.nanoTime()<swallowMouseUpUntil){swallowMouseUpUntil=0;return true;} // the release TrackControl already ended on (mouseUpTracked)
			queue.addLast(e);lock.notifyAll();}
		return true;
	}
	/**
	 * TrackControl ended on the button's release: the mouse-up is taken out of the queue, as the ROM's tracking loop does
	 * with WaitMouseUp. Storybook Weaver's Help window handles a mouse-up in a button like a mouse-down, so its Help button
	 * showed the topic and went straight back to the list. The host may post the mouse-up just after the button state
	 * changed, so one arriving within a short time is dropped too.
	 */
	void mouseUpTracked(){
		synchronized(lock){
			for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();)if(it.next().what==MOUSE_UP){it.remove();swallowMouseUpUntil=0;return;}
			swallowMouseUpUntil=System.nanoTime()+250_000_000L;
		}
	}
	private long swallowMouseUpUntil;
	/**
	 * SysEvtMask ($144): the mouse, keyboard and disk events PostEvent queues. It starts as every event but key-up (IM I-254),
	 * so key-ups reach only a program that asked for them with SetEventMask. Dartmouth's Password XFCN filters ModalDialog's
	 * events by their message alone: a key-up of the Return that closed one dialog ended the next one before it was seen.
	 */
	private boolean enabled(int what){
		if(what<0||what>15||((1<<what)&OS_QUEUE_MASK)==0)return true;
		try{int mask=memory.readWord(SYS_EVT_MASK);return mask==0||(mask&(1<<what))!=0;}catch(M68kCpuException e){return true;} // 0: low memory not set up yet
	}
	static final int SYS_EVT_MASK=0x144,DEFAULT_SYS_EVT_MASK=0xffff&~KEY_UP_MASK;

	/** Wheel turns waiting to be turned into scroll-bar arrow clicks on the emulation thread ({h, v, steps, horizontal}). */
	private final List<int[]> wheelRequests=new ArrayList<int[]>();
	private boolean syntheticClick;
	@Override public void postWheel(int globalH,int globalV,int steps,boolean horizontal){
		synchronized(lock){wheelRequests.add(new int[]{globalH,globalV,steps,horizontal?1:0});lock.notifyAll();}
	}
	/** Host file drags over the program's windows waiting for the emulation thread: {phase, h, v, modifiers} and the files (null while unknown). */
	private final java.util.ArrayDeque<Object[]> hostDrags=new java.util.ArrayDeque<Object[]>();
	@Override public void postExternalDrag(int phase,int globalH,int globalV,List<File> files,int modifiers){
		synchronized(lock){
			Object[] last=hostDrags.peekLast(); // moves pile up while the program is busy: only the latest counts
			if(phase==DragManager68k.EXTERNAL_MOVE&&last!=null&&(int)last[0]==DragManager68k.EXTERNAL_MOVE)hostDrags.removeLast();
			hostDrags.addLast(new Object[]{phase,globalH,globalV,modifiers,files==null?null:new ArrayList<File>(files)});lock.notifyAll();}
	}
	/** WaitNextEvent/GetNextEvent: host drags go to the Drag Manager's handlers; a drop no receive handler takes opens the files (Open Documents). */
	@SuppressWarnings("unchecked")
	private void serviceHostDrags(M68kCpu cpu) throws M68kException {
		while(true){
			Object[] r;synchronized(lock){r=hostDrags.pollFirst();}if(r==null)return;
			int phase=(int)r[0];List<File> files=(List<File>)r[4];
			boolean taken=tb.drag.external(cpu,phase,(int)r[1],(int)r[2],files,(int)r[3]);
			if(phase==DragManager68k.EXTERNAL_DROP&&!taken&&files!=null&&!files.isEmpty())postOpenDocuments(files);
		}
	}
	@Override public int[] textCaret(){int[] inline=tb.textServices.inlineCaret();return inline!=null?inline:tb.textEdit.caretLocation();}
	@Override public boolean postInputMethodText(String committed,String composing,int[] styles,int caret){return tb.textServices.offer(committed,composing,styles,caret);}
	/** Whether a Command-period (or Escape) key-down waits in the queue; it is taken out when {@code take}. */
	boolean commandPeriodQueued(boolean take){
		synchronized(lock){
			for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event e=it.next();
				if(e.what!=KEY_DOWN)continue;int ch=e.message&0xff;
				if(ch==0x1b||(ch=='.'&&(e.modifiers&0x100)!=0)){if(take)it.remove();return true;}}
		}
		return false;
	}
	/** Whether the mouse-down the program is now tracking was synthesized (cleared once asked). */
	boolean takeSyntheticClick(){boolean r=syntheticClick;syntheticClick=false;return r;}
	/** Posts a synthesized click (down and up) at a global point. */
	void postSyntheticClick(int globalH,int globalV){
		int mods=tb.host().modifiers();
		Event down=new Event(MOUSE_DOWN);down.whereH=globalH;down.whereV=globalV;down.modifiers=mods&~0x80;down.synthetic=true;post(down);
		Event up=new Event(MOUSE_UP);up.whereH=globalH;up.whereV=globalV;up.modifiers=mods|0x80;up.synthetic=true;post(up);
	}
	/** Posts a mouse-down the program will track for real (the button is still down): live thumb dragging. */
	void postContinuedPress(int globalH,int globalV){
		synchronized(lock){for(Event e:queue)if(e.what==MOUSE_DOWN)return;}
		Event down=new Event(MOUSE_DOWN);down.whereH=globalH;down.whereV=globalV;down.modifiers=tb.host().modifiers()&~0x80;post(down);
	}
	/** Work done whenever the program asks for an event: wheel turns become arrow clicks, thumb drags continue. */
	private void beforeEvents() throws M68kException {
		tb.controls.continueThumbDrag();
		tb.files.fileManager.syncViews(); // a saved document the program keeps open reaches the host now, not at its Close
		List<int[]> requests;synchronized(lock){if(wheelRequests.isEmpty())return;requests=new ArrayList<int[]>(wheelRequests);wheelRequests.clear();}
		for(int[] r:requests){
			int window=tb.windows.windowAt(r[0],r[1]);
			int[] l=window==0?null:tb.windows.globalToLocal(window,r[0],r[1]);
			int bar=window==0?0:tb.controls.scrollBarNear(window,l[0],l[1],r[3]!=0);
			if(Mac68kApi.TRACE)System.err.println("[68K] wheel "+r[2]+" at "+r[0]+","+r[1]+": window $"+Integer.toHexString(window)+" scroll bar $"+Integer.toHexString(bar));
			if(bar==0)continue;
			int[] arrow=tb.controls.arrowPoint(bar,r[2]>0);if(arrow==null)continue;
			WindowManager68k.Window w=tb.windows.window(window);int gh=arrow[0]+w.left,gv=arrow[1]+w.top;
			int clicks=Math.min(10,Math.abs(r[2]));
			if(Mac68kApi.TRACE)System.err.println("[68K] wheel "+r[2]+" -> "+clicks+" click(s) on scroll bar $"+Integer.toHexString(bar)+" at "+gh+","+gv);
			for(int i=0;i<clicks;i++)postSyntheticClick(gh,gv);
		}
	}

	@Override public void postMouse(boolean down,int globalH,int globalV,int modifiers,long when){
		Event e=new Event(down?MOUSE_DOWN:MOUSE_UP);e.whereH=globalH;e.whereV=globalV;e.modifiers=modifiers;post(e);
	}
	@Override public void postKey(int what,int keyCode,int charCode,int modifiers){
		long now=System.currentTimeMillis();
		if(what==KEY_DOWN&&keyCode==lastKeyDownCode&&(tb.host().isKeyDown(keyCode)||now-lastKeyDownTime<600))what=AUTO_KEY;
		if(what==KEY_DOWN||what==AUTO_KEY){lastKeyDownCode=keyCode;lastKeyDownTime=now;}
		if(what==KEY_UP)lastKeyDownCode=-1;
		Event e=new Event(what);e.message=(keyCode<<8)|(charCode&0xff);e.modifiers=modifiers;Point p=tb.host().mouseLocation();e.whereH=p.x;e.whereV=p.y;post(e);
		// what the user types is never translated: the translator watches for the program drawing these characters (VMTranslation)
		if((what==KEY_DOWN||what==AUTO_KEY)&&(modifiers&0x0100)==0&&(charCode&0xff)>=0x21&&(charCode&0xff)<0x7f){
			VMTranslation.Translator t=tb.app==null?null:tb.app.translator;if(t!=null)t.typed((char)(charCode&0xff));}
	}
	/** Host menus being opened while the program updates its menus at pull-down time (MenuManager68k.prepareOpening): served
	 *  only while it waits in WaitNextEvent, its event loop - {menuId, latch the host waits on}. */
	private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> menuOpenings=new java.util.concurrent.ConcurrentLinkedQueue<Object[]>();
	private volatile boolean inWaitNextEvent;
	@Override public boolean menuOpening(int menuId,long timeoutMillis){
		java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
		menuOpenings.add(new Object[]{menuId,done});wake();
		try{return done.await(timeoutMillis,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
	}
	private void serveMenuOpenings() throws M68kException {
		Object[] r;
		while((r=menuOpenings.poll())!=null){int menuId=(Integer)r[0];java.util.concurrent.CountDownLatch done=(java.util.concurrent.CountDownLatch)r[1];
			if(!tb.menus.updatesMenusOnOpening()&&postMenuProbe(menuId,done))continue;
			try{tb.menus.prepareOpening(menuId);}finally{done.countDown();}}
	}
	/**
	 * A program that sets up its menus when the menu bar is clicked (EnableItem/CheckItem between the mouse-down and MenuSelect,
	 * as Apple's sample code does in AdjustMenus) has its host menus as they were before - Wingz 1.0 disables every item at
	 * startup and enables them only there, so the whole bar was grey. It gets the click it would have had: a mouse-down in the
	 * menu bar whose MenuSelect answers 0 (the user let go outside the menu), and the host shows the menu as the program left it.
	 * {@code done} is released then, or when the program asks for its next event without calling MenuSelect.
	 */
	private boolean postMenuProbe(int menuId,java.util.concurrent.CountDownLatch done){
		if(!tb.menus.hasMenu(menuId))return false;
		endMenuProbe();
		Event down=new Event(MOUSE_DOWN);down.menuId=menuId;down.probe=true;down.whereH=MacAppHost.MENU_EVENT_H;down.whereV=MacAppHost.MENU_EVENT_V;down.modifiers=0x80;
		Event up=new Event(MOUSE_UP);up.whereH=MacAppHost.MENU_EVENT_H;up.whereV=MacAppHost.MENU_EVENT_V;up.modifiers=0x80;
		probeDone=done;postTrackedClick(down,up);synchronized(lock){deferredRetries=0;}
		return true;
	}
	private java.util.concurrent.CountDownLatch probeDone;private boolean probeTaken;
	/** The made-up menu-bar click is over (its MenuSelect, or the program's next event call): the host gets the menus now. */
	void endMenuProbe(){
		java.util.concurrent.CountDownLatch done=probeDone;probeDone=null;probeTaken=false;
		if(done!=null){tb.menus.markDirty();tb.menus.flush();done.countDown();}
	}
	/** The enable state of the menus when a menu choice of the host bar reached the program (see MenuManager68k.noteMenuSelect). */
	int[] menusAtClick;
	@Override public void postMenuSelection(int menuId,int item){
		if(pressAsKeyEquivalent(menuId,item))return;
		Event e=new Event(MOUSE_DOWN);e.menuId=menuId;e.menuItem=item;e.whereH=MacAppHost.MENU_EVENT_H;e.whereV=MacAppHost.MENU_EVENT_V;e.modifiers=0x80;
		Event up=new Event(MOUSE_UP);up.whereH=MacAppHost.MENU_EVENT_H;up.whereV=MacAppHost.MENU_EVENT_V;up.modifiers=0x80;postTrackedClick(e,up);
	}
	/**
	 * The mouse-up of a menu or close-box click the host made up is held back until the program tracks the click (MenuSelect,
	 * TrackGoAway, SystemClick) - on a real Mac the button is still down then. HyperCard's check for user events while a
	 * handler (idle) runs takes a mouse-down out of the queue and keeps it for its main loop, but a mouse-up already queued
	 * behind it made HyperCard drop the kept click: menu choices and the close box did nothing most of the time.
	 * If the program never tracks it, the mouse-up follows {@link #DEFERRED_UP_NANOS} after the mouse-down was taken.
	 */
	private void postTrackedClick(Event down,Event up){
		synchronized(lock){
			// An earlier made-up click's mouse-up the program has not taken yet is dropped: HyperCard's event check masks
			// mouse-ups out, took the new mouse-down ahead of it, and the old mouse-up then made it drop the kept click
			// (closing the last stack window chooses Quit right away - its Quit did nothing).
			if(lastTrackedUp!=null)queue.remove(lastTrackedUp);
			deferredUp=up;lastTrackedUp=up;deferredDown=down;deferredRetries=DEFERRED_RETRIES;deferredUpDeadline=System.nanoTime()+5_000_000_000L; // backstop: the mouse-down was flushed unseen
		}
		post(down);
	}
	private Event deferredUp,deferredDown,lastTrackedUp;private long deferredUpDeadline;private int deferredRetries;
	private static final long DEFERRED_UP_NANOS=500_000_000L;
	/** A made-up click taken but never tracked is queued again this many times: HyperCard's event check during a running
	 *  handler keeps a mouse-down for scripts ("the click") and drops a menu-bar or close-box one - a real Mac user whose
	 *  menu did not open clicks again. */
	private static final int DEFERRED_RETRIES=2;
	static final boolean CLICKLOG=Boolean.getBoolean("hz68k.clicklog"); // -Dhz68k.clicklog=true: made-up menu/close-box clicks (taken, tracked, retried, mouse-up released) on stderr
	/** The program tracks the held-back click now (MenuSelect, TrackGoAway, SystemClick): its mouse-up can follow. */
	void clickTracked(){synchronized(lock){if(CLICKLOG)System.err.println("[clicklog] tracked up="+(deferredUp!=null)+" downQueued="+queue.contains(deferredDown));if(deferredUp!=null&&!queue.contains(deferredDown)){queue.addLast(deferredUp);deferredUp=null;deferredDown=null;lock.notifyAll();}}}
	/** Whether the mouse-up of that click is still held back (the program may still come to track it). */
	private boolean clickUntracked(){synchronized(lock){return deferredUp!=null;}}
	/** Called under the lock: queues a held-back mouse-up whose time is up. */
	private void releaseDeferredUp(){
		if(deferredUp==null||System.nanoTime()<deferredUpDeadline)return;
		if(deferredRetries>0&&!queue.contains(deferredDown)){ // taken, not tracked: offer the click again
			if(CLICKLOG)System.err.println("[clicklog] retry");
			deferredRetries--;deferredDown.when=tb.tickCount();queue.addLast(deferredDown);deferredUpDeadline=System.nanoTime()+5_000_000_000L;return;}
		if(CLICKLOG)System.err.println("[clicklog] up released by timeout");queue.addLast(deferredUp);deferredUp=null;deferredDown=null;
	}
	@Override public void postMenuKey(int menuId,int item,int charCode,int modifiers){
		if(pressAsKeyEquivalent(menuId,item))return;
		Event e=new Event(KEY_DOWN);e.message=charCode&0xff;e.modifiers=modifiers|0x100;e.menuId=menuId;e.menuItem=item;Point p=tb.host().mouseLocation();e.whereH=p.x;e.whereV=p.y;post(e);
		Event up=new Event(KEY_UP);up.message=charCode&0xff;up.modifiers=modifiers|0x100|0x80;post(up);
	}
	/** The last GetKeys call (nanoTime). */
	private volatile long lastKeyPoll=System.nanoTime()-60_000_000_000L;
	/** The last EventAvail call (nanoTime): a program looking into the queue this way fetches what it finds with an event call
	 *  (Kid Pix with its Rubber Stamp tool waits in a Button/GetKeys/EventAvail loop and calls WaitNextEvent only for an event). */
	private volatile long lastQueueLook=System.nanoTime()-60_000_000_000L;
	/** A program reading the keys this recently and making no event call for as long polls the keyboard instead of an event loop. */
	private static final long KEY_POLLING_NANOS=300_000_000L;
	/** Menu choices pressed as their keyboard equivalents, one after the other ({@link #pressAsKeyEquivalent}): {Mac key code,
	 *  GetKeys calls that saw this phase, phase: 0 Command and the key, 1 Command alone, when the phase began (ms, 0 not yet)}. */
	private final java.util.concurrent.ConcurrentLinkedQueue<long[]> pressedEquivalents=new java.util.concurrent.ConcurrentLinkedQueue<long[]>();
	/** How long such a key stays down at least, and Command alone after it (each for two GetKeys calls too): a quick Command-key stroke. */
	private static final long KEY_EQUIVALENT_MILLIS=150;
	/**
	 * A host menu choice while the program polls the keyboard with GetKeys and makes no event calls: it would reach the program
	 * only at its next WaitNextEvent. Such a program reads its menus' Command keys from the key map (Factory hides the menu bar
	 * while a day runs and takes Cmd-S/W/P/D/R from GetKeys; Speed Up/Slow Down are enabled only then), so the item's keyboard
	 * equivalent is pressed instead and the choice is not queued (it would act once more when the event loop runs again).
	 * A program that calls EventAvail meanwhile has an event loop after all: the queued choice reaches it (Kid Pix's Print... was
	 * pressed as Cmd-P into the key map, which it reads only for Shift/Option/Command as modifiers, and so was lost).
	 */
	private boolean pressAsKeyEquivalent(int menuId,int item){
		long now=System.nanoTime();
		if(now-lastKeyPoll>KEY_POLLING_NANOS||now-Math.max(lastEventCall,lastQueueLook)<KEY_POLLING_NANOS||!tb.hosted())return false;
		char c=tb.host().menuCommand(menuId,item);if(c==0)return false;
		int code=MacAppHost.keyCodeForChar(c);if(code==0&&Character.toLowerCase(c)!='a')return false;
		pressedEquivalents.add(new long[]{code,0,0,0});
		if(Mac68kApi.TRACE)System.err.println("[68K] menu "+menuId+"/"+item+" pressed as Cmd-"+c+" (the program polls GetKeys)");
		return true;
	}
	/** Drops queued activate (not deactivate) events of windows other than {@code window} (see WindowManager68k.select). */
	void dropActivations(int window){
		synchronized(lock){queue.removeIf(q->q.what==ACTIVATE_EVT&&q.message!=window&&(q.modifiers&1)!=0);}
	}
	@Override public void postActivate(int window,boolean active){
		Event e=new Event(ACTIVATE_EVT);e.message=window;e.modifiers=active?1:0;e.window=window;
		if(Boolean.getBoolean("hz68k.hosttrace"))System.err.println("[host] postActivate $"+Integer.toHexString(window)+" "+active);
		synchronized(lock){
			// collapse redundant activate events for the same window
			for(Event q:queue)if(q.what==ACTIVATE_EVT&&q.message==window){q.modifiers=e.modifiers;return;}
		}
		post(e);
	}
	/** Removes a queued update event for the window (its update was drawn by the Dialog Manager); true when there was one. */
	boolean takeUpdate(int window){
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event q=it.next();if(q.what==UPDATE_EVT&&q.message==window){it.remove();return true;}}}
		return false;
	}
	@Override public void postUpdate(int window){
		synchronized(lock){for(Event q:queue)if(q.what==UPDATE_EVT&&q.message==window)return;}
		Event e=new Event(UPDATE_EVT);e.message=window;e.window=window;post(e);
	}
	/**
	 * Drops pending activate and update events for a window being disposed. The real Toolbox never delivers an
	 * activate event for a window that no longer exists (activate events are derived from the live window list),
	 * but here they are queued with the window pointer: HideWindow of the front window queues its deactivate, and a
	 * CloseWindow right after would otherwise leave that deactivate to be read against the freed window; an application
	 * could then follow the dangling window record and display an unrelated error.
	 */
	/**
	 * An external returned to HyperCard: the clicks and keys left in its kept VM's queue are HyperCard's, whose event loop takes
	 * them before the external runs again (the mouse-up of a click the external waited for the press of ended Dartmouth's
	 * PictureShow "onRelease" at once on the next call). Only for a VM without windows of its own (theirs come as their events).
	 */
	void discardUserEvents(){
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){int w=it.next().what;if(w==MOUSE_DOWN||w==MOUSE_UP||w==KEY_DOWN||w==KEY_UP||w==AUTO_KEY)it.remove();}}
	}
	/** Windows with a picture (SetWindowPic) waiting for an update are drawn now, as the Window Manager does for them whenever
	 *  HyperCard's loop gets events: between two calls of an external nothing else asks (Dartmouth's AuxWindow stayed white). */
	/**
	 * Modeless dialogs of an external that returned with an update waiting get it as HyperCard's event loop answers it for them
	 * (DialogSelect: DrawDialog, the user items' procedures included): Palette XFCN draws its palette, a dialog made hidden with
	 * user items, only in that update after its ShowWindow - HyperZebra showed it blank. Not the external windows ({@code own}):
	 * their updates are the external's events (a 'DLOG' XWindow draws itself).
	 */
	void drawDialogUpdates(java.util.function.IntPredicate own) throws M68kException {
		List<Integer> dialogs=new ArrayList<Integer>();
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event q=it.next();
			if(q.what==UPDATE_EVT&&tb.dialogs!=null&&tb.dialogs.isDialog(q.message)&&!own.test(q.message)&&!dialogs.contains(q.message)){dialogs.add(q.message);it.remove();}}}
		for(int w:dialogs)if(tb.windows.window(w)!=null)tb.dialogs.drawUpdate(w);
	}
	void drawWindowPictures(){
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event q=it.next();
			if(q.what==UPDATE_EVT&&tb.windows.drawWindowPicture(q.message))it.remove();}}
	}
	void discardWindowEvents(int window){
		if(window==0)return;
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event q=it.next();if((q.what==ACTIVATE_EVT||q.what==UPDATE_EVT)&&q.message==window)it.remove();}}
	}
	@Override public void postWindowMoved(final int window,final int left,final int top){tb.laterAtEvent(new Runnable(){@Override public void run(){tb.windows.hostMoved(window,left,top);}});}
	@Override public void postWindowPlaced(final int window,final int left,final int top){tb.laterAtEvent(new Runnable(){@Override public void run(){tb.windows.hostPlaced(window,left,top);}});}
	@Override public void postWindowResized(final int window,final int width,final int height){tb.laterAtEvent(new Runnable(){@Override public void run(){try{tb.windows.hostResized(window,width,height);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});}
	@Override public void postGoAway(int window){
		// A native close button click is reported as a mouseDown that FindWindow classifies as inGoAway.
		Event e=new Event(MOUSE_DOWN);e.window=window;e.menuId=-1;e.whereH=MacAppHost.MENU_EVENT_H+1;e.whereV=MacAppHost.MENU_EVENT_V;e.modifiers=0x80;
		Event up=new Event(MOUSE_UP);up.whereH=e.whereH;up.whereV=e.whereV;up.modifiers=0x80;postTrackedClick(e,up);
	}
	@Override public void postTearOff(int menuId){
		String script=tb.menus.tearOffScript(menuId);if(script==null)return;
		// HyperCard's do script event ('misc'/'dosc', the script as the direct object), sent by the "Finder"
		AppleEvents68k.Portable p=new AppleEvents68k.Portable();p.type=AppleEvents68k.TYPE_AEVT;p.isRecord=true;p.eventClass=0x6d697363;p.eventId=0x646f7363;
		AppleEvents68k.Portable text=new AppleEvents68k.Portable();text.type=AppleEvents68k.TYPE_TEXT;text.data=script.getBytes(java.nio.charset.StandardCharsets.US_ASCII);p.items.put(AppleEvents68k.KEY_DIRECT,text);
		AppleEvents68k.Portable from=new AppleEvents68k.Portable();from.type=AppleEvents68k.TYPE_PSN;from.data=new byte[]{0,0,0,0,(byte)(Toolbox68k.FINDER_SERIAL>>24),(byte)(Toolbox68k.FINDER_SERIAL>>16),(byte)(Toolbox68k.FINDER_SERIAL>>8),(byte)Toolbox68k.FINDER_SERIAL};p.attributes.put(AppleEvents68k.KEY_ADDRESS,from);
		postAppleEvent(p,Toolbox68k.FINDER_SERIAL);
	}
	/**
	 * The Application Died event ('aevt'/'obit') the Process Manager sends a program whose SIZE has acceptAppDiedEvents when a
	 * program it launched quits: keyProcessSerialNumber names the dead process, keyErrorNumber its exit status. PageMaker 6's
	 * installer launches its registration program and waits for this before it carries on.
	 */
	void postApplicationDied(int psnHigh,int serial,int error){
		AppleEvents68k.Portable p=new AppleEvents68k.Portable();p.type=AppleEvents68k.TYPE_AEVT;p.isRecord=true;p.eventClass=0x61657674;p.eventId=0x6f626974;
		AppleEvents68k.Portable psn=new AppleEvents68k.Portable();psn.type=AppleEvents68k.TYPE_PSN;psn.data=new byte[]{(byte)(psnHigh>>24),(byte)(psnHigh>>16),(byte)(psnHigh>>8),(byte)psnHigh,(byte)(serial>>24),(byte)(serial>>16),(byte)(serial>>8),(byte)serial};p.items.put(0x70736e20,psn);
		AppleEvents68k.Portable err=new AppleEvents68k.Portable();err.type=AppleEvents68k.TYPE_LONG;err.data=new byte[]{(byte)(error>>24),(byte)(error>>16),(byte)(error>>8),(byte)error};p.items.put(AppleEvents68k.KEY_ERROR_NUMBER,err);
		AppleEvents68k.Portable from=new AppleEvents68k.Portable();from.type=AppleEvents68k.TYPE_PSN;from.data=new byte[]{0,0,0,0,0,0,0,1};p.attributes.put(AppleEvents68k.KEY_ADDRESS,from); // kSystemProcess
		postAppleEvent(p,0);
		if(Mac68kApi.TRACE)System.err.println("[68K] Application Died event queued for process "+serial);
	}
	@Override public void postZoom(int window){}
	@Override public void postOpenDocuments(final List<File> files){postOpenDocuments(files,false);}
	/** The launch's Open Application/Documents event (MacApp68k, high-level-event-aware programs). */
	void postLaunchDocuments(List<File> files){postOpenDocuments(files,true);}
	private void postOpenDocuments(List<File> files,boolean launch){
		Event e=new Event(HIGH_LEVEL_EVT);e.files=new ArrayList<File>(files);e.message=0x61657674;e.launch=launch;post(e); // 'aevt'
		if(Mac68kApi.TRACE)System.err.println("[68K] high-level event queued: "+(files.isEmpty()?"oapp":"odoc "+files));
	}
	/** Whether the program installed its own handler for the launch's 'oapp'/'odoc' (not a wildcard class an extension put in). */
	private boolean launchHandlerInstalled(Event e){
		int id=e.files==null||e.files.isEmpty()?0x6f617070:0x6f646f63;
		return tb.appleEvents!=null&&tb.appleEvents.hasCoreHandler(id);
	}
	/** Whether the program has made its first GetNextEvent/WaitNextEvent call. */
	private volatile boolean firstEventCallMade;
	boolean firstEventCallMade(){return firstEventCallMade;}
	/** Whether a quit was asked for and not cancelled by the program (the program is closing down). */
	public boolean quitInProgress(){return quitRequested;}
	@Override public void postQuit(){
		quitRequested=true;
		startQuitTracking();
		// A dialog of the program is up (ModalDialog, an alert, a file dialog): ModalDialog would beep the menu choice away.
		// The quit waits for its event loop, as one asked during start-up does.
		if(modalUp()){synchronized(lock){if(quitPendingSince==0)quitPendingSince=System.nanoTime();}return;}
		// Choosing the program's Quit menu item (Cmd-Q) is what every program answers; the 'quit' Apple event
		// is only understood by high-level-event-aware programs, and some that claim to be ignore it.
		int[] quit=tb.menus.quitItem();
		if(quit!=null){postQuitMenuSelection(quit[0],quit[1]);return;}
		// Asked while the program is still starting up (extension INITs, the runtime, an 'odoc' to come) and has no menus yet:
		// the quit waits for its event loop, where the Quit item has usually appeared by then. Posted as a 'quit' Apple event
		// right away it was lost - a program that is not high-level-event aware drops it (LauncherFlowTest's drop launch).
		// A program that has been taking events for a while without a Quit item gets the Apple event now.
		long now=System.nanoTime();
		synchronized(lock){if(tb.app!=null&&(eventLoopSince==0||now-eventLoopSince<QUIT_MENU_WAIT_NANOS)){if(quitPendingSince==0)quitPendingSince=now;return;}}
		postQuitAppleEvent();
	}
	/**
	 * The 'quit' Apple event for a program without a Quit item - only one that is high-level-event aware: the Process Manager
	 * gives the others no high-level events. Launcher 3.5 (a dialog with no menus) indexed its event table with what = 23 and
	 * ran into data; such a program stays, and HyperZebra's quit offers to force it.
	 */
	private void postQuitAppleEvent(){
		if(tb.app!=null&&(tb.app.sizeFlags()&0x0040)==0){if(Mac68kApi.TRACE)System.err.println("[68K] quit: no Quit item and not high-level-event aware - nothing sent");return;}
		Event e=new Event(HIGH_LEVEL_EVT);e.message=0x61657674;e.menuId=-2;e.quitRequest=true;post(e);
	}
	/** The Quit item chosen for the program, marked so that its taking it is seen ({@link #quitProgress}). */
	private void postQuitMenuSelection(int menuId,int item){
		Event e=new Event(MOUSE_DOWN);e.menuId=menuId;e.menuItem=item;e.whereH=MacAppHost.MENU_EVENT_H;e.whereV=MacAppHost.MENU_EVENT_V;e.modifiers=0x80;e.quitRequest=true;
		Event up=new Event(MOUSE_UP);up.whereH=MacAppHost.MENU_EVENT_H;up.whereV=MacAppHost.MENU_EVENT_V;up.modifiers=0x80;postTrackedClick(e,up);
	}
	/** When a quit asked for before the program had a Quit menu item was requested (System.nanoTime), or 0. */
	private long quitPendingSince;
	/** When the program first asked GetNextEvent/WaitNextEvent for an event (System.nanoTime), or 0. */
	private long eventLoopSince;
	/** How long a program may take events without a Quit item before a quit goes to it as a 'quit' Apple event. */
	private static final long QUIT_MENU_WAIT_NANOS=2_000_000_000L;
	/** GetNextEvent/WaitNextEvent: a pending quit (see {@link #postQuit}) is chosen from the Quit item once there is one. */
	private void deliverPendingQuit(){
		long now=System.nanoTime();
		synchronized(lock){if(eventLoopSince==0&&tb.app!=null)eventLoopSince=now;if(quitPendingSince==0)return;}
		if(modalUp())return; // asked while a dialog of the program was up: once it is gone
		int[] quit=tb.menus.quitItem();
		if(quit==null&&(eventLoopSince==0||now-Math.max(quitPendingSince,eventLoopSince)<QUIT_MENU_WAIT_NANOS))return;
		synchronized(lock){quitPendingSince=0;}
		if(Mac68kApi.TRACE)System.err.println("[68K] pending quit delivered "+(quit!=null?"as its Quit menu item":"as a 'quit' Apple event (no Quit item)"));
		if(quit!=null){postQuitMenuSelection(quit[0],quit[1]);return;}
		postQuitAppleEvent();
	}

	// ------------------------------------------------------------------ HyperZebra's own Quit (QuitSequence)

	/** How HyperZebra's quit request is getting on (MacApp68k.quitProgress). */
	enum QuitProgress{QUITTING,WAITING_FOR_USER,NOT_RESPONDING,CANCELLED}
	/** The quit request has been taken by the program (its Quit item or the 'quit' Apple event). */
	private volatile boolean quitTaken;
	/** After taking it, a dialog of the program waited for the user (its "Save changes?"). */
	private volatile boolean quitDialogSeen;
	/** Since when (nanoTime) the program has been back in its own event loop with no dialog, after one; 0 = not. */
	private long quitIdleSince,quitLastLoopCall;
	private volatile boolean quitCancelled;
	/** The latest look at the program from its event calls: a modal-kind window in front (a dialog loop of its own). */
	private volatile boolean frontDialog;
	/** The last GetNextEvent/WaitNextEvent call (nanoTime): a program that stops calling them is busy or hung. */
	private volatile long lastEventCall=System.nanoTime();
	/** How long a program must stay in its own event loop, dialog gone, before its quit counts as cancelled. */
	private static final long QUIT_CANCEL_NANOS=2_000_000_000L;
	/** A pause between event calls longer than this is work (saving a document), not the event loop. */
	private static final long LOOP_GAP_NANOS=500_000_000L;

	private void startQuitTracking(){quitTaken=false;quitDialogSeen=false;quitCancelled=false;synchronized(lock){quitIdleSince=0;quitLastLoopCall=0;}}
	/** ModalDialog/Alert or a dialog the host shows for the program (Standard File, Navigation Services) is up. */
	private boolean modalUp(){return tb.hosted()&&tb.host().modalWindowUp();}
	/** HyperZebra's quit stopped (Don't Quit): a quit still waiting for the program's dialog to close is not delivered. */
	void withdrawQuit(){synchronized(lock){quitPendingSince=0;}quitRequested=false;}
	/** A dialog of the program waits for the user: after it took the quit, that is its "Save changes?". */
	@Override public void dialogShown(){if(quitRequested&&quitTaken)quitDialogSeen=true;}
	/** The program's 'quit' Apple event handler answered userCanceledErr: the user cancelled the quit in its dialog. */
	void quitCancelledByProgram(){if(quitRequested){quitCancelled=true;quitRequested=false;}}
	/** Event loop bookkeeping for {@link #quitProgress}; GetNextEvent/WaitNextEvent (the CPU thread). */
	private void trackQuit(){
		long now=System.nanoTime();lastEventCall=now;
		boolean front=false;try{front=tb.windows!=null&&tb.windows.frontIsModalDialog();}catch(RuntimeException ignored){}
		frontDialog=front;
		if(!quitRequested||!quitTaken||quitCancelled)return;
		if(front||modalUp()){quitDialogSeen=true;synchronized(lock){quitIdleSince=0;quitLastLoopCall=now;}return;}
		if(!quitDialogSeen)return;
		synchronized(lock){
			if(quitIdleSince==0||now-quitLastLoopCall>LOOP_GAP_NANOS)quitIdleSince=now; // busy meanwhile: start again
			quitLastLoopCall=now;
			if(now-quitIdleSince<QUIT_CANCEL_NANOS)return;
		}
		// The program answered its own "Save changes?" with Cancel and went back to work: the quit is off.
		if(Mac68kApi.TRACE)System.err.println("[68K] the quit was cancelled in the program's dialog");
		quitCancelled=true;quitRequested=false;
	}
	/** How the quit HyperZebra asked for is going (any thread). */
	QuitProgress quitProgress(){
		if(quitCancelled)return QuitProgress.CANCELLED;
		if(modalUp()||quitTaken&&frontDialog)return QuitProgress.WAITING_FOR_USER;
		if(System.nanoTime()-lastEventCall>5_000_000_000L)return QuitProgress.NOT_RESPONDING;
		return QuitProgress.QUITTING;
	}
	/** An Apple event sent by another running program (the where field carries its event ID, the message its class). */
	void postAppleEvent(AppleEvents68k.Portable event,int senderSerial){Event e=new Event(HIGH_LEVEL_EVT);e.message=event.eventClass;e.appleEvent=event;e.senderSerial=senderSerial;post(e);}
	/** An Apple event for a program this process runs on a task of its own (ThreadManager68k.newProgramTask); false when it is gone. */
	boolean postAppleEventToProgram(int serial,AppleEvents68k.Portable event,int senderSerial){Event e=new Event(HIGH_LEVEL_EVT);e.message=event.eventClass;e.appleEvent=event;e.senderSerial=senderSerial;return tb.threads.postToProgram(serial,e);}
	/** A raw high-level event from PostHighLevelEvent in another program: AcceptHighLevelEvent hands the buffer over. */
	void postHighLevel(int eventClass,int eventId,byte[] buffer,int refcon,int senderSerial){Event e=new Event(HIGH_LEVEL_EVT);e.message=eventClass;e.menuItem=eventId;e.buffer=buffer;e.refcon=refcon;e.senderSerial=senderSerial;post(e);}
	/**
	 * The Process Manager's suspend and resume events, when the program's windows lose and get back the host focus - only for a
	 * program whose SIZE resource sets acceptSuspendResumeEvents. ClarisWorks keeps its own clipboard and hands it to the desk
	 * scrap only on suspend: without the event, what was copied there never reached SimpleText. A suspend still queued when the
	 * resume comes (a host Open dialog of the program's own) is dropped along with it.
	 */
	@Override public void postSuspendResume(boolean resume){
		if(tb.app==null||(tb.app.sizeFlags()&0x4000)==0)return;
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event q=it.next();
			if(q.what==OS_EVT&&(q.message>>>24)==SUSPEND_RESUME_MESSAGE&&(q.message&1)!=(resume?1:0)){it.remove();return;}}}
		Event e=new Event(OS_EVT);e.message=SUSPEND_RESUME_MESSAGE<<24|(resume?1:0);post(e);
	}
	private static final int SUSPEND_RESUME_MESSAGE=1;
	/** An operating-system event with this message (the ".Wacom" driver's proximity notice, WacomTablet68k). */
	void postOsEvent(int message){Event e=new Event(OS_EVT);e.message=message;Point p=tb.host().mouseLocation();e.whereH=p.x;e.whereV=p.y;post(e);}

	private void discardNextMouseUp(){synchronized(lock){for(Event q:queue)if(q.what==MOUSE_UP){queue.remove(q);return;}}}

	// ------------------------------------------------------------------ queue -> application

	/** Host clicks already turned into events (see {@link #hostClicks}). */
	private int seenPresses=GUI.mousePresses;private boolean seenDown=GUI.mouseDown;
	/** An external starts a call: clicks made before it (the one on the button that ran the script) are not its events. */
	void resyncHostClicks(){seenPresses=GUI.mousePresses;seenDown=GUI.mouseDown;installCardKeyWatcher();synchronized(cardKeys){seenCardKey=firstWaitingCardKey()-1;}}
	/**
	 * The oldest card key still waiting for HyperCard (its key message not taken yet: a handler was running when it was typed), or
	 * the next key to come. On a Mac such a key waits in the one event queue until someone takes it: MyDialogs' dialog loop calls
	 * getKeyEvent() (GetNextEvent keyDownMask) from a HyperTalk repeat, and the keys typed while the HyperTalk part ran were
	 * dropped at the next call. Keys whose message HyperCard handled (or an external took) are not offered again.
	 */
	private static int firstWaitingCardKey(){
		int first=cardKeySeq+1;
		synchronized(TTalk.messageList){
			int keys=0; // newest first: the n-th newest key waits while at least n key messages queued after it wait
			for(java.util.Iterator<CardKey> it=cardKeys.descendingIterator();it.hasNext();){CardKey k=it.next();keys++;
				int waiting=0;for(Message m:TTalk.messageList)if(isKeyMessage(m)&&!k.before.contains(m))waiting++;
				if(waiting<keys)break;first=k.seq;}
		}
		return first;
	}
	/** Set by the external's session while it has no windows of its own (their clicks come as their own events). */
	volatile boolean hostClicksEnabled;
	/**
	 * An external's VM has no windows of its own for the host to report clicks in, but on a Mac HyperCard's clicks reach
	 * the one event queue it reads: AnimationMaker's AMPlay plays until a mouseDown turns up in WaitNextEvent.
	 */
	private void hostClicks(){
		if(!hostClicksEnabled||tb.stack==null||tb.app!=null)return;
		int presses=GUI.mousePresses;boolean down=GUI.mouseDown;
		if(presses==seenPresses&&down==seenDown)return;
		if(Mac68kApi.TRACE)System.err.println("[68K] host click for the external: presses "+seenPresses+" -> "+presses+", down "+down);
		Point p=tb.host().mouseLocation();int mods=tb.host().modifiers()&~0x80;
		if(presses!=seenPresses){
			Event e=new Event(MOUSE_DOWN);e.whereH=p.x;e.whereV=p.y;e.modifiers=mods;post(e);
			if(!down){Event u=new Event(MOUSE_UP);u.whereH=p.x;u.whereV=p.y;u.modifiers=mods|0x80;post(u);}
		}
		else if(seenDown&&!down){Event u=new Event(MOUSE_UP);u.whereH=p.x;u.whereV=p.y;u.modifiers=mods|0x80;post(u);}
		seenPresses=presses;seenDown=down;
	}

	Event take(int mask,boolean remove){
		hostClicks();hostKeys();
		synchronized(lock){
			releaseDeferredUp();
			if((mask&(1<<ACTIVATE_EVT))!=0){Event low=lowMemoryActivation(remove);if(low!=null)return low;}
			// The Event Manager's order (Toolbox Essentials 2-12): activate, then mouse/keyboard/disk events first in first out,
			// then update events, then OS (suspend/resume) and high-level events. MacPipes' shareware dialog filter answers each
			// update event TRUE without validating the window, so a click queued behind one never reached ModalDialog.
			boolean updateWaiting=false;Event later=null;
			for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event e=it.next();
				int bit=e.what==HIGH_LEVEL_EVT?HIGH_LEVEL_MASK:1<<e.what;
				if((mask&bit)==0)continue;
				// The host's deactivation of a closed frame arrives on the EDT after the program disposed the window (an alert's):
				// a real Mac derives activate events from the live window list, so one for a dead window is never delivered
				// Some class libraries can treat a stale pointer's refCon as an object and stop with an error.
				if((e.what==ACTIVATE_EVT||e.what==UPDATE_EVT)&&tb.windows!=null&&tb.windows.window(e.message)==null){it.remove();continue;}
				if(e.what==UPDATE_EVT&&tb.windows!=null){updateWaiting=true;continue;}
				// A launched program comes to the front during its first event call, which answers no Apple event yet: The Writing
				// Center calls GetNextEvent(everyEvent) once right after InitDialogs, ignores what it gets, installs its handlers and
				// then waits for 'oapp'; given to that first call, the event was lost and no window or menu ever appeared.
				// A program that installed its handler first is asking for it: ClarisWorks 2.1 calls GetNextEvent(highLevelEventMask)
				// right after AEInstallEventHandler and, with nothing there, put up its New Document dialog itself - then the
				// 'oapp' handler put it up a second time.
				if(e.launch&&!firstEventCallMade&&!launchHandlerInstalled(e))continue;
				if(e.what==OS_EVT||e.what==HIGH_LEVEL_EVT){if(later==null)later=e;continue;}
				if(remove)it.remove();
				if(e.posted)fromElement(e);
				if(remove&&e.cardKey!=null)cardKeyTaken(e.cardKey);
				return e;
			}
			if(updateWaiting){Event front=frontUpdate();if(front!=null){if(remove)queue.remove(front);return front;}}
			if(later!=null&&remove)queue.remove(later);
			return later;
		}
	}
	/** The ROM makes activate events from the low-memory CurDeactive ($A68), then CurActivate ($A64), before looking at the
	 *  queue, and clears the one it reports. The native SelectWindow queues its events itself, but a program with its own
	 *  floating-window layer may write these globals: Photoshop 3's patched SelectWindow sets CurActivate to the document it
	 *  brought forward, and without the activate event every click in the document only selected it again (no tool worked). */
	private Event lowMemoryActivation(boolean remove){
		if(tb.app==null||tb.windows==null)return null;
		try{
			for(int global:new int[]{0xa68,0xa64}){
				int w=memory.readLong(global);if(w==0)continue;
				if(tb.windows.window(w)==null){memory.writeLong(global,0);continue;}
				if(remove)memory.writeLong(global,0);
				Event e=new Event(ACTIVATE_EVT);e.message=w;e.window=w;e.modifiers=global==0xa64?1:0;
				return e;
			}
		}catch(M68kCpuException e){}
		return null;
	}
	/** The update event of the frontmost visible window (see {@link #take}), or null; the caller holds the lock. A window with a
	 *  picture (SetWindowPic) is updated by the Window Manager itself and never gets update events (Citadel of the Dead shows
	 *  its title and town screens this way; they stayed black). */
	private Event frontUpdate(){
		while(true){
			Event best=null;int bestRank=Integer.MAX_VALUE;
			for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event q=it.next();if(q.what!=UPDATE_EVT)continue;
				int rank=tb.windows.updateRank(q.message);if(rank<0)continue;
				if(!tb.windows.updateStillNeeded(q.message)){it.remove();continue;} // drawn and validated since it was queued
				if(rank<bestRank){best=q;bestRank=rank;}}
			if(best==null)return null;
			if(tb.windows.drawWindowPicture(best.message)){queue.remove(best);continue;}
			return best;
		}
	}

	/** The high-level events waiting in the queue, oldest first (GetSpecificHighLevelEvent shows them to a filter). */
	List<Event> pendingHighLevelEvents(){synchronized(lock){List<Event> r=new ArrayList<Event>();for(Event e:queue)if(e.what==HIGH_LEVEL_EVT)r.add(e);return r;}}
	/** Takes one event out of the queue ahead of its turn (a filter accepted it). */
	boolean remove(Event e){synchronized(lock){return queue.remove(e);}}
	/** Takes out the first plain click (a mouse-down that is no menu or close-box choice, and the mouse-up after it) once
	 *  both are queued: HyperTalk's the mouseClick read it (see {@link XWindows68k#takeClick}). */
	boolean takeClick(){
		synchronized(lock){
			Event down=null;
			for(Event e:queue){
				if(down==null){if(e.what==MOUSE_DOWN&&e.menuId==0&&e.menuItem==0)down=e;}
				else if(e.what==MOUSE_UP){queue.remove(down);queue.remove(e);return true;}
			}
			return false;
		}
	}

	/** Wakes {@link #idle} early (a deferred task is waiting). */
	void wake(){synchronized(lock){lock.notifyAll();}}

	/** Lets the host breathe while the application is idle (SystemTask, WaitNextEvent sleep). */
	void idle(int ticks) throws M68kException {idle(ticks,-1);}
	/** Whether a fetch with this mask would get an event now (take's own rules: update events for windows it skips don't count). */
	private boolean queuedFor(int mask){return !queue.isEmpty()&&take(mask,false)!=null;}
	/**
	 * Idles until an event the caller could take is queued (or the ticks run out). Waking for ANY queued event made a wait
	 * spin at full speed whenever something it cannot take stays queued - one it masks out, or an update event take() skips
	 * (Tarantula's registration alert used a whole CPU core).
	 */
	void idle(int ticks,int mask) throws M68kException {
		long deadline=System.nanoTime()+ticks*16_666_667L;
		tb.cpu.yielded();
		// Java2D drawing staged for a 16-bit/indexed window lands at the end of a trap - but a Toolbox loop that waits here inside
		// one trap (Standard File's dialog) would keep its last drawing staged: the folder pop-up showed the previous folder.
		if(tb.quickDraw!=null)tb.quickDraw.flushSurfaces();
		while(true){
			tb.runDeferred();tb.runTimers();tb.runPeriodic();
			if(inWaitNextEvent&&!menuOpenings.isEmpty())serveMenuOpenings();
			if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			synchronized(lock){if(queuedFor(mask))return;}
			long remaining=deadline-System.nanoTime();if(remaining<=0)return;
			if(tb.hasDeferred())continue;
			if(tb.threads.taskRunnable())return; // an MP task can run: WaitNextEvent returns so it gets its turn
			long due=tb.nextDueNanos()-System.nanoTime();if(due<=0)continue; // a MIDI wake-up is due: run the timers again now
			// Object.wait only counts whole milliseconds and wakes late on top: a timed wake-up (a Time Manager task) is reached
			// with a short wait that ends early plus a precise park for the rest. SoundApp's 20 ms MIDI clock task fired ~2 ms
			// late each time, and its music ran 10% slow
			long nanos=Math.min(remaining,due);
			if(due<=remaining&&nanos<2_000_000L){java.util.concurrent.locks.LockSupport.parkNanos(nanos);continue;}
			long millis=due<=remaining?(nanos-1_500_000L)/1_000_000L:nanos/1_000_000L;
			synchronized(lock){try{lock.wait(Math.max(1,Math.min(millis,20)));}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
		}
	}

	/** The window whose update event the program got last. CheckUpdate makes update events from the update region, so one the
	 *  program did not answer comes again until BeginUpdate/EndUpdate or ValidRect empties it: ｼｽﾃﾑｿﾌﾄｴﾃﾞｨﾀ's 表現チェック plug-in
	 *  InvalRects its 原文 pane in a state that ignores events and draws it from the next update - it got only the first.
	 *  Not without end: a window nobody answers for would take the null events away for good. */
	private int updateGiven,updateRepeats;
	private static final int UPDATE_REPEATS=30;
	private void updateAgain(){
		int w=updateGiven;updateGiven=0;if(w==0)return;
		if(tb.windows.window(w)==null||!tb.windows.updateStillNeeded(w)){updateRepeats=0;return;}
		if(++updateRepeats>UPDATE_REPEATS){updateRepeats=0;if(Mac68kApi.TRACE)System.err.println("[68K] update of $"+Integer.toHexString(w)+" never answered");return;}
		postUpdate(w);
	}

	private boolean waitNextEvent(M68kCpu cpu) throws M68kException {
		int mask=memory.readWord(cpu.a[7]+12),eventPtr=memory.readLong(cpu.a[7]+8),sleep=memory.readLong(cpu.a[7]+4);cpu.a[7]+=14;
		if(tb.threads.inProgramTask()){ // a program run in the launcher's process: the events are the launcher's, this is only its turn's end
			Event own=(mask&HIGH_LEVEL_MASK)!=0?tb.threads.takeProgramEvent():null; // but the Apple events sent to it are its own
			if(own!=null&&eventPtr!=0){writeEvent(eventPtr,own);tb.writeBoolean(cpu.a[7],true);return true;}
			if(eventPtr!=0)writeEvent(eventPtr,null);
			tb.writeBoolean(cpu.a[7],false);
			tb.threads.yieldProgram(cpu,(int)Math.max(0,Math.min(sleep,30)));
			return true;
		}
		if(probeTaken)endMenuProbe(); // the made-up menu-bar click went by without MenuSelect
		tb.runEventWork();tb.updateTicks();tb.runTimers();tb.runPeriodic();tb.menus.flushIfDirty();
		closeIgnoredGoAway();beforeEvents();serviceHostDrags(cpu);deliverPendingQuit();trackQuit();updateAgain();
		if(tb.app!=null&&tb.hosted())tb.host().programWaiting(); // no window and no menus: the stand-in is its drop target
		// WaitNextEvent gives the drivers and accessories their time as SystemTask does (a program using it need not call
		// SystemTask): QuicKeys plays a shortcut from its driver's accRun
		tb.desk.systemTask(cpu);
		if(cpu.patchedTrap(0xa970)!=0&&eventPtr!=0)return waitThroughGetNextEvent(cpu,mask,eventPtr,sleep);
		Boolean os=takeThroughGetOSEvent(cpu,mask,eventPtr);
		Event e=os!=null?null:take(mask,true);
		// Hosted programs sleep for real; a headless external (its script is waiting) sleeps at most one tick, which paces
		// frame-by-frame animations the way a real Mac does (ANIMATE XCMD) without stalling externals that only poll.
		cpu.yielded();
		if(!menuOpenings.isEmpty())serveMenuOpenings();
		if(os==null){
			if(e==null&&tb.hosted()){inWaitNextEvent=true;try{idle((int)Math.max(0,Math.min(sleep,10)),mask);}finally{inWaitNextEvent=false;}
				os=takeThroughGetOSEvent(cpu,mask,eventPtr);if(os==null)e=take(mask,true);
				if(Mac68kApi.TRACE&&e==null&&os==null){synchronized(lock){if(!queue.isEmpty())System.err.printf("[68K] WaitNextEvent(mask $%04X) sleep %d with a queued event it masks out: what=%d message=$%08X%n",mask,sleep,queue.peekFirst().what,queue.peekFirst().message);}}}
			else if(e==null&&sleep>0){idle(1,mask);os=takeThroughGetOSEvent(cpu,mask,eventPtr);if(os==null)e=take(mask,true);}
			if(e==null&&os==null&&tb.hosted())idleOnce();
		}
		if(os==null){writeEvent(eventPtr,e);delivered(e);}
		int what=memory.readWord(eventPtr);
		if(Mac68kApi.TRACE&&what!=NULL_EVENT)System.err.printf("[68K] WaitNextEvent(mask $%04X) -> what=%d message=$%08X modifiers $%04X%s%n",mask,what,memory.readLong(eventPtr+2),memory.readWord(eventPtr+14),os!=null?" (through GetOSEvent)":"");
		boolean result=os!=null?os:e!=null&&e.what!=NULL_EVENT;if(result&&tb.desk.offerEvent(cpu,eventPtr))result=false;
		if(result&&tb.windows.offerSystemWindowEvent(cpu,eventPtr)){result=false;memory.writeWord(eventPtr,NULL_EVENT);}
		if(result&&what==UPDATE_EVT)updateGiven=memory.readLong(eventPtr+2);
		tb.writeBoolean(cpu.a[7],gneFilter(cpu,eventPtr,result)); // WaitNextEvent is GetNextEvent to SystemEvent and the filter too
		firstEventCallMade=true;
		tb.textServices.deliverPending(cpu);tb.threads.yieldAtEventLoop(cpu); // input-method text for the TSM document, then other threads get their turn
		return true;
	}

	/** OSEventAvail/GetOSEvent (register based: D0 = mask, A0 = event record). The OS event queue holds only the
	 *  low-level events (mouse, key, disk, OS events) - update, activate and high-level events are made up by
	 *  GetNextEvent. D0 comes back 0 when an event was found and -1 for a null event (HyperCard drains the queue
	 *  with `repeat while OSEventAvail`, so a 0 for "nothing there" spins forever). */
	boolean osEvent(M68kCpu cpu,boolean remove) throws M68kException {
		int mask=cpu.d[0]&0xffff&~(UPDATE_MASK|ACTIVATE_MASK|HIGH_LEVEL_MASK),eventPtr=cpu.a[0];
		// A disk inserted meanwhile (DiskImages.mount) is mounted by the event-call work: installers asking for the next disk
		// poll GetOSEvent(diskMask) alone (VISE's "Please insert the disk"), never GetNextEvent.
		if((mask&(1<<DISK_EVT))!=0)tb.runEventWork();else tb.runDeferred();
		tb.updateTicks();tb.runTimers();tb.runPeriodic();
		if(remove){closeIgnoredGoAway();beforeEvents();}
		Event e=take(mask,remove);
		if(e==null&&tb.hosted())idleOnce();
		writeEvent(eventPtr,e);if(remove)delivered(e);
		cpu.d[0]=e!=null&&e.what!=NULL_EVENT?0:-1;
		return true;
	}

	private boolean getNextEvent(M68kCpu cpu,boolean remove) throws M68kException {
		int mask=memory.readWord(cpu.a[7]+4),eventPtr=memory.readLong(cpu.a[7]);cpu.a[7]+=6;
		tb.runEventWork();tb.updateTicks();tb.runTimers();tb.runPeriodic();tb.menus.flushIfDirty();
		if(remove){closeIgnoredGoAway();beforeEvents();serviceHostDrags(cpu);deliverPendingQuit();trackQuit();updateAgain();if(tb.app!=null&&tb.hosted())tb.host().programWaiting();}
		if(remove)cpu.yielded();
		Boolean os=remove?takeThroughGetOSEvent(cpu,mask,eventPtr):null;
		Event e=os!=null?null:take(mask,remove);
		if(e==null&&os==null&&remove&&tb.hosted())idleOnce();
		if(os==null){writeEvent(eventPtr,e);if(remove)delivered(e);}
		if(Mac68kApi.TRACE&&eventPtr!=0&&memory.readWord(eventPtr)!=NULL_EVENT)System.err.printf("[68K] %s(mask $%04X) -> what=%d message=$%08X%s%n",remove?"GetNextEvent":"EventAvail",mask,memory.readWord(eventPtr),memory.readLong(eventPtr+2),os!=null?" (through GetOSEvent)":"");
		boolean result=os!=null?os:e!=null&&e.what!=NULL_EVENT;
		if(remove&&result&&tb.desk.offerEvent(cpu,eventPtr))result=false;
		if(remove&&result&&tb.windows.offerSystemWindowEvent(cpu,eventPtr)){result=false;memory.writeWord(eventPtr,NULL_EVENT);}
		if(remove&&result&&memory.readWord(eventPtr)==UPDATE_EVT)updateGiven=memory.readLong(eventPtr+2);
		if(remove){result=gneFilter(cpu,eventPtr,result);firstEventCallMade=true;}
		tb.writeBoolean(cpu.a[7],result);
		if(remove){tb.textServices.deliverPending(cpu);tb.threads.yieldAtEventLoop(cpu);}
		return true;
	}

	private int getNextEventStub;
	/**
	 * WaitNextEvent takes its event with the GetNextEvent trap, so patches on GetNextEvent see the events of programs that
	 * use WaitNextEvent too (QuicKeys hands out the keystrokes of the shortcut it plays from there). Used once the trap is
	 * patched: the original at the end of the chain is {@link #getNextEvent}, which also runs the GetNextEvent filter.
	 */
	private boolean waitThroughGetNextEvent(M68kCpu cpu,int mask,int eventPtr,int sleep) throws M68kException {
		if(!menuOpenings.isEmpty())serveMenuOpenings();
		boolean result=guestGetNextEvent(cpu,mask,eventPtr);
		if(!result&&tb.hosted()&&sleep>0){
			inWaitNextEvent=true;try{idle((int)Math.max(0,Math.min(sleep,10)),mask);}finally{inWaitNextEvent=false;}
			result=guestGetNextEvent(cpu,mask,eventPtr);}
		tb.writeBoolean(cpu.a[7],result);
		return true;
	}
	/** Calls GetNextEvent(mask, eventPtr) through the guest's trap table (patches included); returns its Boolean. */
	private boolean guestGetNextEvent(M68kCpu cpu,int mask,int eventPtr) throws M68kException {
		if(getNextEventStub==0){getNextEventStub=memory.allocate(8,2);memory.writeWord(getNextEventStub,0xa970);memory.writeWord(getNextEventStub+2,0x4ef9);memory.writeLong(getNextEventStub+4,M68kCpu.RETURN_SENTINEL);}
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sp=cpu.a[7];
		try{
			cpu.a[7]=sp-2;int slot=cpu.a[7];memory.writeWord(slot,0);
			cpu.pushWord(mask);cpu.pushLong(eventPtr);
			cpu.runUntilSentinel(getNextEventStub,6);
			return (memory.readWord(slot)&0xff00)!=0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp;}
	}

	/** The low-level events GetNextEvent takes out of the OS event queue through GetOSEvent. */
	private static final int OS_QUEUE_MASK=(1<<MOUSE_DOWN)|(1<<MOUSE_UP)|(1<<KEY_DOWN)|(1<<KEY_UP)|(1<<AUTO_KEY)|(1<<DISK_EVT);
	private int getOSEventStub;
	/**
	 * GetNextEvent and WaitNextEvent take mouse, keyboard and disk events out of the OS queue with GetOSEvent (after the
	 * activate events, before updates and high-level events), where INITs that patched the trap see every one: QuicKeys
	 * watches the keystrokes there for its shortcuts, swallows the ones that trigger, and hands out the keystrokes of the
	 * shortcut it plays when the queue is empty. When GetOSEvent is patched, it is called through the guest's trap (the
	 * original at the end of the chain is {@link #osEvent}). Returns TRUE when it left an event in the record at
	 * {@code eventPtr}, else null: the caller takes the next event itself (updates, high-level events...).
	 */
	private Boolean takeThroughGetOSEvent(M68kCpu cpu,int mask,int eventPtr) throws M68kException {
		if(eventPtr==0||(mask&OS_QUEUE_MASK)==0||cpu.patchedTrap(0xa031)==0)return null;
		Event next=take(mask,false);
		if(next!=null&&next.what==ACTIVATE_EVT)return null;
		if(getOSEventStub==0){getOSEventStub=memory.allocate(8,2);memory.writeWord(getOSEventStub,0xa031);memory.writeWord(getOSEventStub+2,0x4ef9);memory.writeLong(getOSEventStub+4,M68kCpu.RETURN_SENTINEL);}
		int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{cpu.d[0]=mask&OS_QUEUE_MASK;cpu.a[0]=eventPtr;cpu.runUntilSentinel(getOSEventStub,0);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		return memory.readWord(eventPtr)!=NULL_EVENT?Boolean.TRUE:null;
	}

	/**
	 * GetNextEvent's filter hook (JGNEFilter, $29A), which INITs and programs install to see or change every event: called
	 * just before GetNextEvent returns with A1 = the event record and the function result both in D0 and in the word at
	 * 4(SP) (a Pascal Boolean, which the filter may change). Not called while the vector still holds the default.
	 */
	private boolean gneFilter(M68kCpu cpu,int eventPtr,boolean result) throws M68kException {
		int filter=memory.readLong(Toolbox68k.JGNE_FILTER);
		if(filter==0||filter==tb.gneFilterGate||eventPtr==0)return result;
		int slot=cpu.a[7]-2;cpu.a[7]=slot;memory.writeWord(slot,result?0x0100:0);
		int[] d=cpu.d.clone(),a=cpu.a.clone();
		// D0 holds the result as the word on the stack does (a Pascal Boolean: high byte), and its low byte too for filters
		// that test D0.B: QuicKeys' filter writes D0.W back into 4(SP), and D0 = 1 there turned every event into FALSE -
		// SimpleText never saw its Open Application event and opened no window
		cpu.a[1]=eventPtr;cpu.d[0]=result?0x0101:0;
		try{cpu.callPascal(filter,2);}
		finally{int sp=a[7];System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp+2;}
		return (memory.readWord(slot)&0xff00)!=0;
	}

	/** ModalDialog's event fetch: the same preparation as GetNextEvent (wheel turns become scroll-bar clicks, thumb drags go
	 *  on) and the same bookkeeping, so a wheel turn scrolls a dialog's list and TrackControl knows a synthetic click. */
	Event takeForDialog(int mask) throws M68kException {beforeEvents();Event e=take(mask,true);delivered(e);return e;}

	/** The program has taken this event out of the queue (not just peeked at it). */
	private void delivered(Event e){
		if(e!=null&&e.quitRequest&&quitRequested)quitTaken=true;
		if(e!=null&&e.what==MOUSE_DOWN)syntheticClick=e.synthetic;
		// GetMouse and the low-memory Mouse answer a made-up click's place while it is handled: a synthetic click's, and a host
		// menu choice's point in the menu bar (on a Mac the pointer is up there when the program takes the click). SoftWindows
		// tells a PC click from a menu-bar click by where the pointer is (Mouse in its PC screen's region): a menu choice made
		// while the pointer rested on the PC screen went to Windows as a click and the menu command never ran
		if(e!=null&&(e.what==MOUSE_DOWN||e.what==MOUSE_UP)&&tb.hosted()&&tb.host()!=null){
			boolean hold=e.what==MOUSE_DOWN&&(e.synthetic||e.menuId>0);
			tb.host().holdSyntheticPointer(hold?new Point(e.whereH,e.whereV):null);
			if(hold)try{tb.publishMouse();}catch(M68kCpuException ignored){}
		}
		if(CLICKLOG&&e!=null&&(e.what==MOUSE_DOWN||e.what==MOUSE_UP))System.err.println("[clicklog] delivered what="+e.what+" menuId="+e.menuId+" pc="+Integer.toHexString(tb.cpu.pc)+" sp="+Integer.toHexString(tb.cpu.a[7]));
		if(e!=null&&e==deferredDown)synchronized(lock){deferredUpDeadline=Math.min(deferredUpDeadline,System.nanoTime()+DEFERRED_UP_NANOS);}
		if(e!=null&&e.what==MOUSE_DOWN&&e.menuId==-1){goAwayDelivered=e.window;visibleAtGoAway=tb.windows.visibleWindows();goAwayTracksBefore=tb.windows.goAwayTracks;windowsCreatedBefore=tb.windows.windowsCreated;
			int front=tb.windows.frontWindow();try{goAwayBehindDialog=front!=0&&front!=e.window&&tb.windows.isWindow(front)&&(short)memory.readWord(front+108)==2;}catch(M68kCpuException x){goAwayBehindDialog=false;}} // windowKind dialogKind
	}

	/**
	 * The host close button arrives as an inGoAway mouseDown, which a program whose window has no close box
	 * (or which ignores that part code) leaves unanswered. The user still expects the window to go away: when
	 * the program comes back for its next event without having hidden or disposed the window, and without
	 * having opened another one (a "save changes?" alert, say), the window is hidden here as HideWindow would.
	 * If that leaves nothing the user could reach the program through (no visible window, no menu bar), the
	 * program is terminated as if it had called ExitToShell.
	 */
	private void closeIgnoredGoAway() throws M68kException {
		int w=goAwayDelivered;if(w==0||clickUntracked())return; // the program may still track it (HyperCard keeps the click for its main loop)
		goAwayDelivered=0;if(CLICKLOG)System.err.println("[clicklog] closeIgnored w="+Integer.toHexString(w)+" visible="+tb.windows.isVisible(w)+" all="+tb.windows.visibleWindows()+" tracks="+(tb.windows.goAwayTracks-goAwayTracksBefore)+" created="+(tb.windows.windowsCreated-windowsCreatedBefore));
		List<Integer> before=visibleAtGoAway;visibleAtGoAway=null;
		if(!tb.windows.isVisible(w))return;
		// a dialog in front of the window took the click (an alert or modal dialog refuses clicks elsewhere): the program did not
		// ignore it. SimpleText's "save changes?" alert was up when the document's close box was clicked again - the document was
		// hidden, and after Cancel the unsaved document stayed out of sight
		if(goAwayBehindDialog)return;
		for(int v:tb.windows.visibleWindows())if(!before.contains(v))return; // the program answered with a window of its own
		// ... or tracked the close box (MacPaint: TrackGoAway, "Save changes before closing?", Cancel - the window stays open)
		if(tb.windows.windowsCreated!=windowsCreatedBefore)return;
		if(tb.windows.goAwayTracks!=goAwayTracksBefore){
			// Tracked, nothing asked, and its only window is still there: the program keeps its last window open on purpose
			// (HyperCard J-2.3 runs "close card window" and ignores it for the last stack; "Close Stack" is dimmed then).
			// Closing the window is how a user quits a program here, so its Quit menu item is chosen.
			if(tb.app!=null&&tb.windows.visibleWindows().size()==1&&tb.menus.quitItem()!=null){
				if(Mac68kApi.TRACE)System.err.printf("[68K] close box of the last window $%X kept open by the program: choosing Quit%n",w);
				postQuit();
			}
			return;
		}
		if(Mac68kApi.TRACE)System.err.printf("[68K] close box of window $%X ignored by the program: hiding it%n",w);
		tb.windows.show(w,false);
		if(tb.app!=null&&tb.windows.frontWindow()==0&&!tb.menus.hasMenuBar()){
			if(Mac68kApi.TRACE)System.err.println("[68K] no window or menu bar left: terminating the program");
			throw new Toolbox68k.ExitToShell();
		}
	}

	private long lastIdleNanos;
	/** Polling loops without WaitNextEvent sleep would spin; yield a little. */
	private void idleOnce(){
		long now=System.nanoTime();
		if(now-lastIdleNanos<2_000_000L){try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
		lastIdleNanos=System.nanoTime();
	}

	private void writeEvent(int eventPtr,Event e) throws M68kCpuException {
		if(eventPtr==0)return;
		int what=e==null?NULL_EVENT:e.what;
		// a resume says whether the clipboard changed while the program was in the background (convertClipboard): another
		// program or host application put something new on the host clipboard since its suspend
		if(e!=null&&e.what==OS_EVT&&(e.message>>>24)==SUSPEND_RESUME_MESSAGE&&!e.clipboardNoted){e.clipboardNoted=true;
			ScrapManager68k scrap=tb.api.scrapManager();if((e.message&1)==0)scrap.suspended();else if(scrap.changedSinceSuspend())e.message|=2;}
		memory.writeWord(eventPtr,what);
		memory.writeLong(eventPtr+2,e==null?0:e.message);
		memory.writeLong(eventPtr+6,tb.tickCount());
		if(e!=null&&e.what==HIGH_LEVEL_EVT)memory.writeLong(eventPtr+10,highLevelEventId(e)); // where = event ID (message = event class)
		else if(e==null||(e.what!=MOUSE_DOWN&&e.what!=MOUSE_UP)){Point p=tb.host().mouseLocation();memory.writeWord(eventPtr+10,p.y);memory.writeWord(eventPtr+12,p.x);}
		else{memory.writeWord(eventPtr+10,e.whereV);memory.writeWord(eventPtr+12,e.whereH);}
		int modifiers=e==null?tb.host().modifiers()|(tb.host().isMouseDown()?0:0x80):e.modifiers;
		if(e!=null&&e.what==ACTIVATE_EVT)modifiers=e.modifiers|(tb.host().isMouseDown()?0:0x80);
		memory.writeWord(eventPtr+14,modifiers);
		if(e!=null&&e.what==MOUSE_DOWN&&(e.menuId!=0||e.menuItem!=0))pendingMenuEvent=e;
		else if(e!=null&&e.what==MOUSE_DOWN)pendingMenuEvent=null;
		if(e!=null&&e.probe)probeTaken=true;
		else if(e!=null&&e.what==MOUSE_DOWN&&e.menuId>0)menusAtClick=tb.menus.enableState();
		if(e!=null&&e.what==KEY_DOWN&&e.menuId!=0)tb.menus.pendingMenuKey(e.menuId,e.menuItem);
		if(e!=null&&e.what==HIGH_LEVEL_EVT)tb.appleEvents.queued(e);
	}

	/**
	 * The Apple event ID a queued high-level event stands for: 'quit', 'odoc' (documents attached) or 'oapp'.
	 * A program that reads the event record directly and compares the where field with 'oapp'/'odoc'
	 * sees it there, as it would under System 7.
	 */
	static int highLevelEventId(Event e){if(e.appleEvent!=null)return e.appleEvent.eventId;if(e.buffer!=null)return e.menuItem;return e.menuId==-2?0x71756974:e.files!=null&&!e.files.isEmpty()?0x6f646f63:0x6f617070;}

	/** Writes an EventRecord without side effects (used for filter procedures). */
	void writeEventRecord(int eventPtr,Event e) throws M68kCpuException {
		memory.writeWord(eventPtr,e.what);memory.writeLong(eventPtr+2,e.message);memory.writeLong(eventPtr+6,e.when!=0?e.when:tb.tickCount());
		if(e.what==HIGH_LEVEL_EVT)memory.writeLong(eventPtr+10,highLevelEventId(e));
		else if(e.what==MOUSE_DOWN||e.what==MOUSE_UP){memory.writeWord(eventPtr+10,e.whereV);memory.writeWord(eventPtr+12,e.whereH);}
		else{Point p=tb.host().mouseLocation();memory.writeWord(eventPtr+10,p.y);memory.writeWord(eventPtr+12,p.x);}
		memory.writeWord(eventPtr+14,e.what==NULL_EVENT?tb.host().modifiers()|(tb.host().isMouseDown()?0:0x80):e.modifiers);
	}

	/** Reads a classic EventRecord from memory. */
	Event readEvent(int eventPtr) throws M68kCpuException {
		Event e=new Event(memory.readWord(eventPtr));
		e.message=memory.readLong(eventPtr+2);e.when=memory.readLong(eventPtr+6);e.whereV=(short)memory.readWord(eventPtr+10);e.whereH=(short)memory.readWord(eventPtr+12);e.modifiers=memory.readWord(eventPtr+14);
		return e;
	}

	private boolean getMouse(M68kCpu cpu) throws M68kCpuException {
		int point=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		Point p=tb.host().mouseLocation();
		int[] local=tb.windows.globalToLocal(p.x,p.y);
		if(point!=0){memory.writeWord(point,local[1]);memory.writeWord(point+2,local[0]);}
		if(Mac68kApi.TRACE)System.err.println("[68K] GetMouse "+p.x+","+p.y+" -> "+local[0]+","+local[1]+" in port $"+Integer.toHexString(tb.api.currentQuickDrawPort()));
		return true;
	}

	/** Current mouse position in the current port's local coordinates. */
	int[] localMouse() throws M68kCpuException {Point p=tb.host().mouseLocation();return tb.windows.globalToLocal(p.x,p.y);}

	/** The KeyMap low-memory global ($174, 16 bytes) GetKeys copies: kept up to date once a tick. */
	void updateKeyMap() throws M68kCpuException {memory.fill(0x174,16,0);writeKeys(0x174);}
	private void writeKeys(int keyMap) throws M68kCpuException {
		int m=tb.host().modifiers();
		if((m&0x100)!=0)setKey(keyMap,55);if((m&0x200)!=0)setKey(keyMap,56);if((m&0x800)!=0)setKey(keyMap,58);if((m&0x1000)!=0)setKey(keyMap,59);
		if(tb.host().capsLocked())setKey(keyMap,57);
		byte[] map=new byte[16];tb.host().keyMap(map);
		for(int i=0;i<16;i++)if(map[i]!=0)memory.writeByte(keyMap+i,memory.readByte(keyMap+i)|map[i]&0xff);
		// Injected keys (debug scripts, headless) never reach the host key map.
		if(lastKeyDownCode>=0&&!tb.host().isKeyDown(lastKeyDownCode)&&System.currentTimeMillis()-lastKeyDownTime<700)setKey(keyMap,lastKeyDownCode);
		long[] equivalent=pressedEquivalents.peek();
		if(equivalent!=null){setKey(keyMap,55);long now=System.currentTimeMillis();if(equivalent[3]==0)equivalent[3]=now;
			// the letter goes up before Command, as in a typed Command-key stroke: Factory acts on a letter again only after it
			// saw Command down without it. Each phase lasts a while too: a program may call GetKeys once per key it tests.
			if(equivalent[2]==0){setKey(keyMap,(int)equivalent[0]);if(++equivalent[1]>=2&&now-equivalent[3]>=KEY_EQUIVALENT_MILLIS){equivalent[1]=0;equivalent[2]=1;equivalent[3]=now;}}
			else if(++equivalent[1]>=2&&now-equivalent[3]>=KEY_EQUIVALENT_MILLIS)pressedEquivalents.poll();}
	}
	private void setKey(int map,int keyCode) throws M68kCpuException {
		int byteAddress=map+(keyCode>>>3);memory.writeByte(byteAddress,memory.readByte(byteAddress)|(1<<(keyCode&7)));
	}

	boolean quitRequested(){return quitRequested;}
	void clear(){synchronized(lock){queue.clear();}}
	/** FlushEvents(eventMask, stopMask). */
	/** FlushEvents: update and activate events are not in the ROM's event queue (the Window Manager makes them up at event
	 *  time from the update regions and the window list), so a FlushEvents(everyEvent) never loses them - some applications
	 *  flushes right after showing its title dialog and then waits for its update event. */
	void flush(int mask){synchronized(lock){queue.removeIf(e->(mask&(1<<e.what))!=0&&e.what!=UPDATE_EVT&&e.what!=ACTIVATE_EVT);}}
	boolean hasPending(){synchronized(lock){return !queue.isEmpty();}}
	/** Posts an event created by the application (PostEvent); {@link #lastPostedElement} is its queue element then. */
	boolean postApplicationEvent(int what,int message){
		Event e=new Event(what);e.message=message;Point p=tb.host().mouseLocation();e.whereH=p.x;e.whereV=p.y;e.modifiers=tb.host().modifiers();
		lastPostedElement=0;
		if(!post(e))return false;
		e.posted=true;e.element=toElement(e);lastPostedElement=e.element;
		return true;
	}

	// ------------------------------------------------------------------ events an external posts for HyperCard

	/** The queue element (EvQEl) of the event PostEvent queued last, 0 when none: PPostEvent ($A12F) answers it in A0. */
	int lastPostedElement;
	/** EvQEl: qLink, qType (evType = 4), then the EventRecord (what +6, message +8, when +12, where +16, modifiers +20). The
	 *  OS event queue has a fixed set of them (IM I-249: 30 by default), used round. */
	private static final int EVQ_ELEMENT_SIZE=22,EVQ_ELEMENTS=30;
	private int evqElements,evqNext;
	private int toElement(Event e){
		try{
			if(evqElements==0)evqElements=memory.allocate(EVQ_ELEMENT_SIZE*EVQ_ELEMENTS,2);
			int q=evqElements+EVQ_ELEMENT_SIZE*evqNext;evqNext=(evqNext+1)%EVQ_ELEMENTS;
			memory.writeLong(q,0);memory.writeWord(q+4,4);memory.writeWord(q+6,e.what);memory.writeLong(q+8,e.message);memory.writeLong(q+12,e.when);
			memory.writeWord(q+16,e.whereV);memory.writeWord(q+18,e.whereH);memory.writeWord(q+20,e.modifiers);
			return q;
		}catch(M68kCpuException x){return 0;}
	}
	/** What the program wrote into a posted event's queue element after PPostEvent: Caliban's PostClick sets the where (its
	 *  click point, made global) and clears the modifiers there. */
	private void fromElement(Event e){
		if(e.element==0)return;
		try{e.message=memory.readLong(e.element+8);e.whereV=(short)memory.readWord(e.element+16);e.whereH=(short)memory.readWord(e.element+18);e.modifiers=memory.readWord(e.element+20);}
		catch(M68kCpuException x){}
	}
	/**
	 * The mouse-downs the external posted and left in the queue, taken out: {where h, where v (global), modifiers}. On a Mac they
	 * wait in the one event queue for HyperCard, whose event loop takes them once the external has returned - a click there
	 * (PostClick "389,270",true clicks the card's Next button). Only for a VM without windows of its own.
	 */
	List<int[]> takePostedClicks(){
		List<int[]> out=new ArrayList<int[]>();
		synchronized(lock){for(java.util.Iterator<Event> it=queue.iterator();it.hasNext();){Event e=it.next();
			if(!e.posted||e.what!=MOUSE_DOWN)continue;
			fromElement(e);out.add(new int[]{e.whereH,e.whereV,e.modifiers});it.remove();}}
		return out;
	}

	// ------------------------------------------------------------------ keys typed on the card, for an external without windows

	/** A key pressed in HyperCard's card window, and the key messages HyperZebra had queued before it (the one it queues for
	 *  this key is not among them). */
	static final class CardKey {
		final int seq;final java.awt.event.KeyEvent event;final java.util.Set<Object> before;
		CardKey(int seq,java.awt.event.KeyEvent event,java.util.Set<Object> before){this.seq=seq;this.event=event;this.before=before;}
	}
	private static final java.util.ArrayDeque<CardKey> cardKeys=new java.util.ArrayDeque<CardKey>();
	private static int cardKeySeq;
	private int seenCardKey;
	private static boolean cardKeyWatcher;
	private static final java.util.Set<String> KEY_MESSAGES=java.util.Set.of("keydown","commandkeydown","controlkey","returnkey","enterkey","returninfield","enterinfield","tabkey","arrowkey","functionkey");
	/** Watches the card window's key presses (once): an external that reads keys with EventAvail/GetNextEvent and has no window of
	 *  its own got them on a Mac, where HyperCard's keys and its are one queue (Caliban's keyEvent()). */
	private static synchronized void installCardKeyWatcher(){
		if(cardKeyWatcher)return;cardKeyWatcher=true;
		try{java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e->{
			if(e.getID()!=java.awt.event.KeyEvent.KEY_PRESSED||!(e.getSource() instanceof java.awt.Component))return;
			java.awt.Component c=(java.awt.Component)e.getSource();PCARD pc=PCARD.pc;
			java.awt.Window w=c instanceof java.awt.Window?(java.awt.Window)c:javax.swing.SwingUtilities.getWindowAncestor(c);
			if(pc!=null&&w==pc)noteCardKey((java.awt.event.KeyEvent)e);
		},java.awt.AWTEvent.KEY_EVENT_MASK);}
		catch(RuntimeException e){} // no toolkit: no keys to watch
	}
	/** Records a key pressed on the card (the watcher; tests call it directly). */
	static void noteCardKey(java.awt.event.KeyEvent e){
		switch(e.getKeyCode()){case java.awt.event.KeyEvent.VK_SHIFT:case java.awt.event.KeyEvent.VK_CONTROL:case java.awt.event.KeyEvent.VK_ALT:case java.awt.event.KeyEvent.VK_META:case java.awt.event.KeyEvent.VK_CAPS_LOCK:return;default:break;}
		java.util.Set<Object> before=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
		synchronized(TTalk.messageList){for(Message m:TTalk.messageList)if(isKeyMessage(m))before.add(m);}
		synchronized(cardKeys){cardKeys.addLast(new CardKey(++cardKeySeq,e,before));while(cardKeys.size()>32)cardKeys.removeFirst();}
	}
	private static boolean isKeyMessage(Message m){return m!=null&&m.action==null&&!m.do_script&&!m.idle&&m.message!=null&&KEY_MESSAGES.contains(m.message.toLowerCase(java.util.Locale.ROOT));}
	/**
	 * Keys typed on the card since the external was called go into its queue, as {@link #hostClicks} does for clicks
	 * (Caliban's keyEvent() = EventAvail(keyDownMask) + GetNextEvent: "Type a key, click to exit" saw no key).
	 */
	private void hostKeys(){
		if(!hostClicksEnabled||tb.stack==null||tb.app!=null)return;
		List<CardKey> fresh=new ArrayList<CardKey>();
		synchronized(cardKeys){if(seenCardKey==cardKeySeq)return;for(CardKey k:cardKeys)if(k.seq>seenCardKey)fresh.add(k);seenCardKey=cardKeySeq;}
		for(CardKey k:fresh){
			int keyCode=MacAppHost.macKeyCode(k.event.getKeyCode(),k.event.getKeyLocation()),ch=tb.host().macChar(k.event);
			if(ch<0&&keyCode<0)continue;
			if(Mac68kApi.TRACE)System.err.println("[68K] host key for the external: code "+keyCode+" char "+ch);
			Event e=new Event(KEY_DOWN);e.message=((keyCode<0?0:keyCode)<<8)|(ch<0?0:ch&0xff);e.modifiers=tb.host().hostModifiers(k.event.getModifiersEx())|0x80;
			Point p=tb.host().mouseLocation();e.whereH=p.x;e.whereV=p.y;e.cardKey=k;post(e);
		}
	}
	/** The external took the key (GetNextEvent): HyperCard never sees it, so the key message HyperZebra queued for it goes. */
	private static void cardKeyTaken(CardKey k){
		synchronized(TTalk.messageList){for(java.util.Iterator<Message> it=TTalk.messageList.iterator();it.hasNext();){Message m=it.next();
			if(isKeyMessage(m)&&!k.before.contains(m)){it.remove();return;}}}
	}
}

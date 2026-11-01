import java.io.File;

/**
 * Opens a control panel ('cdev' file) the way the System 7 Finder does: a window sized from the cdev's 'nrct'
 * rectangle list holding its 'DITL' items, and the cdev code resource called with the control-panel messages
 * (initDev, hitDev, closeDev, nulDev, updateDev, activDev, deactivDev, keyEvtDev, macDev...) as events arrive.
 *
 * The panel runs as a program of its own ({@link MacApp68k#launchControlPanel}): the cdev file is the process's
 * resource file, so the cdev finds its resources as usual, and the event loop below is run from a two-instruction
 * guest stub through a host gate once per pass. Toolbox calls made from here go through real trap words in guest
 * memory, so trap patches the cdev's own INIT installed (After Dark's) see them like the Finder's.
 */
final class ControlPanelHost68k implements MacApp68k.HostedCode {
	// cdev messages
	private static final int INIT_DEV=0,HIT_DEV=1,CLOSE_DEV=2,NUL_DEV=3,UPDATE_DEV=4,ACTIV_DEV=5,DEACTIV_DEV=6,KEY_EVT_DEV=7,MAC_DEV=8,
		UNDO_DEV=9,CUT_DEV=10,COPY_DEV=11,PASTE_DEV=12,CLEAR_DEV=13;
	/** Control panel resources live at this ID (the "CPanelID" the Finder passes). */
	static final int CPANEL_ID=-4064;
	/** cdevValue before initDev, and the result values that mean an error. */
	private static final int CDEV_UNSET=3,CDEV_GEN_ERR=-1,CDEV_MEM_ERR=0,CDEV_RES_ERR=1;
	private static final int TYPE_CDEV=0x63646576,TYPE_NRCT=0x6e726374,TYPE_DITL=0x4449544c,TYPE_MACH=0x6d616368;
	/** Event kinds and modifiers. */
	private static final int NULL_EVENT=0,MOUSE_DOWN=1,KEY_DOWN=3,AUTO_KEY=5,UPDATE_EVT=6,ACTIVATE_EVT=8,CMD_KEY=0x100,ACTIVE_FLAG=1;
	private static final int IN_MENU_BAR=1,IN_SYS_WINDOW=2,IN_CONTENT=3,IN_DRAG=4,IN_GO_AWAY=6;

	private final MacApp68k app;
	private final File file;
	private Toolbox68k tb;
	private M68kMemory memory;
	private M68kCpu cpu;
	private int stubs;                       // trap stubs: [trap word][RTS] per Toolbox call made from here
	private final java.util.Map<Integer,Integer> stubAddress=new java.util.HashMap<Integer,Integer>();
	private int eventRecord,scratch,titleP;  // 16-byte EventRecord; scratch words for rects, results and a window pointer; the Str255 title
	private int dialog,codeHandle,cdevValue=CDEV_UNSET;
	private boolean opened,closed;
	private String failure;

	ControlPanelHost68k(MacApp68k app,File file){this.app=app;this.file=file;}

	/** The title the Finder shows: the file's name (as the program sees it). */
	private String title(){return FileManager68k.programName(tb.encoding(),file.getName());}

	/** One pass of the Finder's control-panel loop; true when the panel has closed and the process should exit. */
	@Override public boolean step() throws M68kException {
		if(closed)return true;
		if(!opened){opened=true;tb=app.toolbox;memory=app.memory;cpu=app.cpu;
			try{open();}catch(M68kException e){failure=e.getMessage();throw e;}
			if(closed)return true;}
		if(tb.events.quitRequested()){close();return true;}
		waitNextEvent();
		int what=memory.readWord(eventRecord);
		switch(what){
			case NULL_EVENT:call(NUL_DEV,0);break;
			case MOUSE_DOWN:mouseDown();break;
			case KEY_DOWN:case AUTO_KEY:keyDown();break;
			case UPDATE_EVT:if(memory.readLong(eventRecord+2)==dialog)update();else dialogEvent();break;
			case ACTIVATE_EVT:if(memory.readLong(eventRecord+2)==dialog){call((memory.readWord(eventRecord+14)&ACTIVE_FLAG)!=0?ACTIV_DEV:DEACTIV_DEV,0);dialogEvent();}break;
			default:dialogEvent();break;
		}
		return closed;
	}

	/** What went wrong when the panel could not open (an explanation for the user), or null. */
	@Override public String failure(){return failure;}

	// ------------------------------------------------------------------ opening and closing

	private void open() throws M68kException {
		stubs=memory.allocate(512,2);
		eventRecord=memory.allocate(16,2);memory.fill(eventRecord,16,0);
		scratch=memory.allocate(64,2);titleP=memory.allocate(256,2);
		// the managers, as the Finder had them initialized: a cdev reads the QuickDraw globals through the host's A5 (screenBits for After Dark's miniature screen)
		cpu.pushLong(cpu.a[5]-4);trap(0xa86e,4); // InitGraf(&qd.thePort)
		trap(0xa8fe,0);trap(0xa912,0);trap(0xa930,0);trap(0xa9cc,0); // InitFonts, InitWindows, InitMenus, TEInit
		cpu.pushLong(0);trap(0xa97b,4);trap(0xa850,0); // InitDialogs(nil), InitCursor
		ResourceFile68k.Resource code=tb.resources.find(TYPE_CDEV,CPANEL_ID,true);
		if(code==null)throw new M68kCpuException(cpu.pc,"\""+title()+"\" has no 'cdev' "+CPANEL_ID+" resource");
		codeHandle=tb.resources.handleFor(code);
		if(codeHandle==0||memory.readLong(codeHandle)==0)throw new M68kCpuException(cpu.pc,"the 'cdev' resource of \""+title()+"\" could not be loaded");
		tb.api.setHandleState(codeHandle,tb.api.handleState(codeHandle)|0x80); // HLock
		// 'mach': softMask then hardMask; 0/$FFFF asks the cdev itself (macDev) whether it runs on this machine
		ResourceFile68k.Resource mach=tb.resources.find(TYPE_MACH,CPANEL_ID,true);
		if(mach!=null&&mach.length()>=4){byte[] m=tb.resources.data(mach);
			if(MacApp68k.u2(m,0)==0&&MacApp68k.u2(m,2)==0xffff&&call(MAC_DEV,0)==0)throw new M68kCpuException(cpu.pc,"\""+title()+"\" says it cannot run on this machine");}
		// 'nrct': the rectangles the cdev's items occupy; the Finder's window is their union, moved to the origin
		int[] union=null;
		ResourceFile68k.Resource nrct=tb.resources.find(TYPE_NRCT,CPANEL_ID,true);
		if(nrct!=null&&nrct.length()>=10){byte[] n=tb.resources.data(nrct);int count=MacApp68k.u2(n,0);
			for(int i=0;i<count&&2+i*8+8<=n.length;i++){int o=2+i*8;int t=(short)MacApp68k.u2(n,o),l=(short)MacApp68k.u2(n,o+2),b=(short)MacApp68k.u2(n,o+4),r=(short)MacApp68k.u2(n,o+6);
				if(union==null)union=new int[]{t,l,b,r};else{union[0]=Math.min(union[0],t);union[1]=Math.min(union[1],l);union[2]=Math.max(union[2],b);union[3]=Math.max(union[3],r);}}}
		ResourceFile68k.Resource ditl=tb.resources.find(TYPE_DITL,CPANEL_ID,true);
		byte[] items=ditl==null?new byte[]{(byte)0xff,(byte)0xff}:tb.resources.data(ditl);
		if(union==null)union=itemBounds(items);
		int width=Math.max(64,union[3]-union[1]),height=Math.max(32,union[2]-union[0]);
		// The items keep the Control Panel desk accessory's coordinates (the cdev area began at 87,-1 right of its icon list): the
		// window's origin is moved there instead, as the System 7 Finder does. Church Windows 1.1 creates its controls from
		// 'CNTL' resources in those coordinates; with the items moved to the origin they lay 87 pixels right of them, cut off.
		int itemsHandle=tb.api.createHandle(items);
		// the window: a document window with a close box, placed like the Finder's (upper third of the screen, centred)
		java.awt.Rectangle screen=MacAppHost.screenBounds();
		int left=Math.max(4,(screen.width-width)/2),top=Math.max(MacAppHost.MENU_BAR_HEIGHT+24,MacAppHost.MENU_BAR_HEIGHT+(screen.height-MacAppHost.MENU_BAR_HEIGHT-height)/3);
		memory.writeWord(scratch,top);memory.writeWord(scratch+2,left);memory.writeWord(scratch+4,top+height);memory.writeWord(scratch+6,left+width);
		memory.load(titleP,tb.readPascalBytesOf(title()));
		cpu.pushLong(0);cpu.pushLong(0);cpu.pushLong(scratch);cpu.pushLong(titleP);cpu.pushWord(0);cpu.pushWord(0);cpu.pushLong(-1);cpu.pushWord(0x100);cpu.pushLong(0);cpu.pushLong(itemsHandle);
		trap(0xaa4b,30); // NewCDialog(nil, &bounds, title, visible=false, documentProc, behind=-1, goAway=true, refCon=0, items)
		dialog=cpu.popLong();
		if(dialog==0)throw new M68kCpuException(cpu.pc,"the control panel window could not be created");
		setPort();
		cpu.pushWord(union[1]);cpu.pushWord(union[0]);trap(0xa878,4); // SetOrigin(union.left, union.top)
		// the panel window's font is the application font at 9 points, as in the Control Panel desk accessory before: cdevs lay out
		// their items for it (QuicKeys' "QuicKeys:" label is 51 pixels wide, its copyright two lines in 26) and give their
		// controls useWFont, so with the system font their titles were cut off
		cpu.pushWord(1);trap(0xa887,2);cpu.pushWord(9);trap(0xa88a,2); // TextFont(applFont), TextSize(9)
		int result=call(INIT_DEV,0);
		// cdevUnset back is no error: the value initDev was given, handed back by a panel that keeps no storage (SoftwareFPU 3.07)
		if(result==CDEV_GEN_ERR||result==CDEV_MEM_ERR||result==CDEV_RES_ERR){
			cdevValue=result;
			String why=result==CDEV_MEM_ERR?"not enough memory":result==CDEV_RES_ERR?"a resource is missing":"a general error";
			failure="\""+title()+"\" could not be opened: "+why+" (cdev result "+result+")";
			cpu.pushLong(dialog);trap(0xa983,4);dialog=0;closed=true;return; // DisposDialog
		}
		cdevValue=result;
		cpu.pushLong(dialog);trap(0xa915,4); // ShowWindow
		cpu.pushLong(dialog);trap(0xa91f,4); // SelectWindow
		if(Mac68kApi.TRACE)System.err.println("[68K "+app.name+"] control panel opened: "+width+"x"+height+", cdevValue $"+Integer.toHexString(cdevValue));
	}

	private void close() throws M68kException {
		if(closed)return;closed=true;
		if(dialog!=0){setPort();call(CLOSE_DEV,0);cpu.pushLong(dialog);trap(0xa983,4);dialog=0;} // DisposDialog
	}

	/** The union of the item rectangles of a DITL (for a cdev without 'nrct'). */
	private static int[] itemBounds(byte[] d){
		int[] u=null;int count=(short)MacApp68k.u2(d,0)+1,o=2;
		for(int i=0;i<count&&o+14<=d.length;i++){
			int t=(short)MacApp68k.u2(d,o+4),l=(short)MacApp68k.u2(d,o+6),b=(short)MacApp68k.u2(d,o+8),r=(short)MacApp68k.u2(d,o+10);
			if(u==null)u=new int[]{t,l,b,r};else{u[0]=Math.min(u[0],t);u[1]=Math.min(u[1],l);u[2]=Math.max(u[2],b);u[3]=Math.max(u[3],r);}
			int len=d[o+13]&0xff;o+=14+len+(len&1);
		}
		return u==null?new int[]{0,0,64,64}:u;
	}

	// ------------------------------------------------------------------ events

	/**
	 * SystemTask then GetNextEvent, the System 6 event loop the Control Panel DA's host ran: a control panel's own INIT
	 * (After Dark's screen-saver engine) patches these two traps and runs from them, and WaitNextEvent is not patched.
	 */
	private void waitNextEvent() throws M68kException {
		trap(0xa9b4,0); // SystemTask
		cpu.pushWord(0);cpu.pushWord(0xffff);cpu.pushLong(eventRecord);
		trap(0xa970,6); // GetNextEvent(everyEvent, &event)
		cpu.popWord();
	}

	private void mouseDown() throws M68kException {
		int where=memory.readLong(eventRecord+10); // EventRecord: what +0, message +2, when +6, where +10, modifiers +14
		cpu.pushWord(0);cpu.pushLong(where);cpu.pushLong(scratch+32);
		trap(0xa92c,8); // FindWindow(where, &window)
		int part=cpu.popWord(),window=memory.readLong(scratch+32);
		switch(part){
			case IN_MENU_BAR:{cpu.pushLong(0);cpu.pushLong(where);trap(0xa93d,4);cpu.popLong();break;} // MenuSelect: no menus of our own; the host's per-window bar answers Cmd-Q/Cmd-W
			case IN_SYS_WINDOW:{cpu.pushLong(eventRecord);cpu.pushLong(window);trap(0xa9b3,8);break;} // SystemClick
			case IN_DRAG:{if(window==0)break;
				memory.writeWord(scratch+16,MacAppHost.MENU_BAR_HEIGHT+4);memory.writeWord(scratch+18,4);memory.writeWord(scratch+20,0x3fff);memory.writeWord(scratch+22,0x3fff);
				cpu.pushLong(window);cpu.pushLong(where);cpu.pushLong(scratch+16);trap(0xa925,12);break;} // DragWindow
			case IN_GO_AWAY:{if(window!=dialog)break;
				cpu.pushWord(0);cpu.pushLong(window);cpu.pushLong(where);trap(0xa91e,8); // TrackGoAway
				if((cpu.popWord()&0xff00)!=0)close();break;}
			case IN_CONTENT:{
				if(window!=dialog){cpu.pushLong(window);trap(0xa91f,4);break;} // SelectWindow
				if(frontWindow()!=dialog){cpu.pushLong(window);trap(0xa91f,4);break;}
				dialogEvent();break;}
			default:break;
		}
	}

	private void keyDown() throws M68kException {
		int message=memory.readLong(eventRecord+2),modifiers=memory.readWord(eventRecord+14);
		char ch=(char)(message&0xff);
		if((modifiers&CMD_KEY)!=0&&(ch=='q'||ch=='Q'||ch=='w'||ch=='W')){close();return;}
		if(frontWindow()!=dialog)return;
		setPort();
		call(KEY_EVT_DEV,0);
		// a cdev that handled the key sets the event's what to nullEvent; otherwise the Dialog Manager types it
		if(memory.readWord(eventRecord)!=NULL_EVENT)dialogEvent();
	}

	/** The window's update: the Dialog Manager draws the standard items, the cdev its own (updateDev). */
	private void update() throws M68kException {
		cpu.pushLong(dialog);trap(0xa922,4); // BeginUpdate
		// the invalid part is erased first, as the Control Panel shell does: Kaleidoscope draws its scheme's name and
		// description with DrawString/TETextBox over what was there (a new name came out over the old one)
		try{setPort();cpu.pushLong(dialog+16);trap(0xa8a3,4);cpu.pushLong(dialog);trap(0xa981,4);call(UPDATE_DEV,0);} // EraseRect(portRect) inside the update, DrawDialog
		finally{cpu.pushLong(dialog);trap(0xa923,4);} // EndUpdate
	}

	/** DialogSelect for the event in the record: an item hit in the panel goes to the cdev as hitDev. */
	private void dialogEvent() throws M68kException {
		if(dialog==0)return;
		cpu.pushWord(0);cpu.pushLong(eventRecord);trap(0xa97f,4); // IsDialogEvent
		if((cpu.popWord()&0xff00)==0)return;
		memory.writeLong(scratch+24,0);memory.writeWord(scratch+28,0);
		cpu.pushWord(0);cpu.pushLong(eventRecord);cpu.pushLong(scratch+24);cpu.pushLong(scratch+28);trap(0xa980,12); // DialogSelect(event, &dialog, &item)
		boolean hit=(cpu.popWord()&0xff00)!=0;
		int hitDialog=memory.readLong(scratch+24),item=(short)memory.readWord(scratch+28);
		if(hit&&hitDialog==dialog&&item>0){setPort();call(HIT_DEV,item);}
	}

	private int frontWindow() throws M68kCpuException {return tb.windows.frontWindow();}

	private void setPort() throws M68kException {if(dialog!=0){cpu.pushLong(dialog);trap(0xa873,4);}} // SetPort

	// ------------------------------------------------------------------ calling the cdev

	/**
	 * pascal long cdev(short message, short item, short numItems, short CPanelID, EventRecord *event, long cdevValue,
	 * DialogPtr dialog): numItems is 0 because, as in the System 7 Finder, no host items precede the cdev's own.
	 * Returns the cdev's result; cdevValue is updated when the result is a storage value rather than an error code.
	 */
	private int call(int message,int item) throws M68kException {
		if(codeHandle==0)return cdevValue;
		int entry=memory.readLong(codeHandle);
		if(entry==0){tb.api.loadHandle(codeHandle,tb.resources.data(tb.resources.find(TYPE_CDEV,CPANEL_ID,true)));entry=memory.readLong(codeHandle);} // purged
		int saved=cpu.a[7];
		cpu.pushLong(0);cpu.pushWord(message);cpu.pushWord(item);cpu.pushWord(0);cpu.pushWord(CPANEL_ID);cpu.pushLong(eventRecord);cpu.pushLong(cdevValue);cpu.pushLong(dialog);
		int result;
		try{cpu.callPascal(entry,20);result=memory.readLong(cpu.a[7]);}
		finally{cpu.a[7]=saved;}
		if(Mac68kApi.TRACE&&message!=NUL_DEV)System.err.println("[68K "+app.name+"] cdev message "+message+" item "+item+" -> $"+Integer.toHexString(result));
		if(message!=MAC_DEV&&result!=CDEV_GEN_ERR&&result!=CDEV_MEM_ERR&&result!=CDEV_RES_ERR&&result!=CDEV_UNSET)cdevValue=result;
		return result;
	}

	/**
	 * Executes a Toolbox trap as the guest would (through its trap word, so patches apply). The arguments are on the
	 * stack; {@code argumentBytes} is what the trap pops, so that afterwards the stack pointer is at its result (or past
	 * the arguments when it has none). The stub is [trap word][JMP sentinel]: no return address may sit above the arguments.
	 */
	private void trap(int word,int argumentBytes) throws M68kException {
		Integer at=stubAddress.get(word);
		if(at==null){at=stubs+stubAddress.size()*8;if(stubAddress.size()>=64)throw new M68kCpuException(cpu.pc,"too many trap stubs");
			memory.writeWord(at,word);memory.writeWord(at+2,0x4ef9);memory.writeLong(at+4,M68kCpu.RETURN_SENTINEL);stubAddress.put(word,at);}
		int before=cpu.a[7];
		cpu.runUntilSentinel(at,argumentBytes);
		cpu.a[7]=before+argumentBytes;
	}
}

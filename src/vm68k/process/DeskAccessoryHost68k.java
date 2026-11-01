import java.io.File;

/**
 * Runs a desk accessory ('dfil' file: a 'DRVR' and the resources it owns) as a process of its own, as System 7's DA
 * Handler does when the Finder opens one (LaunchDeskAccessory): the file is the process's resource file, the
 * accessory is opened with OpenDeskAcc, and the event loop below hands it its events through the System routines
 * (SystemTask, SystemClick, SystemEvent, SystemMenu, SystemEdit). The menu bar has a File menu (Close, Quit) and an
 * Edit menu for the accessory; its own menu, if it has one, it inserts itself. The process ends when the accessory
 * closes.
 * <p>
 * Like {@link ControlPanelHost68k}, the loop runs once per pass from a guest stub through a host gate, and every
 * Toolbox call made from here goes through a real trap word.
 */
final class DeskAccessoryHost68k implements MacApp68k.HostedCode {
	private static final int NULL_EVENT=0,MOUSE_DOWN=1,KEY_DOWN=3,AUTO_KEY=5,UPDATE_EVT=6,CMD_KEY=0x100;
	private static final int IN_MENU_BAR=1,IN_SYS_WINDOW=2;
	private static final int FILE_MENU=129,EDIT_MENU=130;
	private static final int TYPE_DRVR=0x44525652;

	private final MacApp68k app;
	private final File file;
	/** The accessory's name in the file (null: its first 'DRVR'). */
	private final String accessoryName;
	private Toolbox68k tb;
	private M68kMemory memory;
	private M68kCpu cpu;
	private int stubs;
	private final java.util.Map<Integer,Integer> stubAddress=new java.util.HashMap<Integer,Integer>();
	private int eventRecord,scratch,text;
	private int refNum;
	private boolean opened,closed;
	private String failure;

	DeskAccessoryHost68k(MacApp68k app,File file,String accessoryName){this.app=app;this.file=file;this.accessoryName=accessoryName;}

	@Override public String failure(){return failure;}

	@Override public boolean step() throws M68kException {
		if(closed)return true;
		if(!opened){opened=true;tb=app.toolbox;memory=app.memory;cpu=app.cpu;
			try{open();}catch(M68kException e){failure=e.getMessage();throw e;}
			if(closed)return true;}
		if(tb.events.quitRequested()){close();return true;}
		trap(0xa9b4,0); // SystemTask: accRun, accCursor
		cpu.pushWord(0);cpu.pushWord(0xffff);cpu.pushLong(eventRecord);
		trap(0xa970,6); // GetNextEvent(everyEvent, &event)
		// FALSE: nothing for the host, or GetNextEvent's own SystemEvent has already handed the event (a key, an update) to the
		// accessory - the record still holds it, and passing it on again typed every key twice into Acta's outline
		boolean got=(cpu.popWord()&0xff00)!=0;
		if(got)switch(memory.readWord(eventRecord)){
			case NULL_EVENT:break;
			case MOUSE_DOWN:mouseDown();break;
			case KEY_DOWN:case AUTO_KEY:
				if((memory.readWord(eventRecord+14)&CMD_KEY)!=0){cpu.pushLong(0);cpu.pushWord(memory.readLong(eventRecord+2)&0xff);trap(0xa93e,2);menuCommand(cpu.popLong());} // MenuKey
				else systemEvent();
				break;
			case UPDATE_EVT:
				if(!systemEvent()){int w=memory.readLong(eventRecord+2);cpu.pushLong(w);trap(0xa922,4);cpu.pushLong(w);trap(0xa923,4);} // BeginUpdate/EndUpdate: nobody else draws it
				break;
			default:systemEvent();break;
		}
		if(!tb.desk.isOpen(refNum))closed=true; // its close box or Close: the process ends with it
		return closed;
	}

	// ------------------------------------------------------------------ opening and closing

	private void open() throws M68kException {
		stubs=memory.allocate(512,2);
		eventRecord=memory.allocate(16,2);memory.fill(eventRecord,16,0);
		scratch=memory.allocate(64,2);text=memory.allocate(256,2);
		cpu.pushLong(cpu.a[5]-4);trap(0xa86e,4); // InitGraf(&qd.thePort)
		trap(0xa8fe,0);trap(0xa912,0);trap(0xa930,0);trap(0xa9cc,0); // InitFonts, InitWindows, InitMenus, TEInit
		cpu.pushLong(0);trap(0xa97b,4);trap(0xa850,0); // InitDialogs(nil), InitCursor
		boolean japanese=tb.encoding()==StackTextEncoding.JAPANESE;
		addMenu(FILE_MENU,japanese?"ファイル":"File",japanese?"閉じる/W;終了/Q":"Close/W;Quit/Q");
		addMenu(EDIT_MENU,japanese?"編集":"Edit",japanese?"取り消し/Z;(-;カット/X;コピー/C;ペースト/V;消去":"Undo/Z;(-;Cut/X;Copy/C;Paste/V;Clear");
		trap(0xa937,0); // DrawMenuBar
		if(accessoryName!=null)memory.load(text,tb.readPascalBytesOf(accessoryName));
		else{ResourceFile68k.Resource first=firstAccessory();
			if(first==null){failure="\""+app.name+"\" has no desk accessory ('DRVR' resource)";closed=true;return;}
			byte[] name=StackTextEncoding.ROMAN.encodeReplacing(first.name==null?"":first.name);int n=Math.min(255,name.length);
			memory.writeByte(text,n);memory.load(text+1,java.util.Arrays.copyOf(name,n));}
		cpu.pushWord(0);cpu.pushLong(text);trap(0xa9b6,4); // OpenDeskAcc(name)
		refNum=(short)cpu.popWord();
		if(refNum==0){failure="\""+app.name+"\" could not be opened as a desk accessory";closed=true;return;}
		if(Mac68kApi.TRACE)System.err.println("[68K "+app.name+"] desk accessory open, refNum "+refNum);
	}

	private ResourceFile68k.Resource firstAccessory(){
		java.util.List<ResourceFile68k.Resource> all=tb.resources.resourcesOfFile(tb.resources.applicationRefNum(),TYPE_DRVR);
		return all.isEmpty()?null:all.get(0);
	}

	private void close() throws M68kException {
		if(closed)return;closed=true;
		if(refNum!=0&&tb.desk.isOpen(refNum)){cpu.pushWord(refNum);trap(0xa9b7,2);} // CloseDeskAcc
	}

	private void addMenu(int id,String title,String items) throws M68kException {
		memory.load(text,tb.readPascalBytesOf(title));
		cpu.pushLong(0);cpu.pushWord(id);cpu.pushLong(text);trap(0xa931,6); // NewMenu(id, title)
		int menu=cpu.popLong();
		memory.load(text,tb.readPascalBytesOf(items));
		cpu.pushLong(menu);cpu.pushLong(text);trap(0xa933,8); // AppendMenu(menu, items)
		cpu.pushLong(menu);cpu.pushWord(0);trap(0xa935,6); // InsertMenu(menu, 0)
	}

	// ------------------------------------------------------------------ events

	private void mouseDown() throws M68kException {
		int where=memory.readLong(eventRecord+10);
		cpu.pushWord(0);cpu.pushLong(where);cpu.pushLong(scratch);
		trap(0xa92c,8); // FindWindow(where, &window)
		int part=cpu.popWord(),window=memory.readLong(scratch);
		if(part==IN_MENU_BAR){cpu.pushLong(0);cpu.pushLong(where);trap(0xa93d,4);menuCommand(cpu.popLong());} // MenuSelect
		else if(part==IN_SYS_WINDOW){cpu.pushLong(eventRecord);cpu.pushLong(window);trap(0xa9b3,8);} // SystemClick
	}

	private boolean systemEvent() throws M68kException {
		cpu.pushWord(0);cpu.pushLong(eventRecord);trap(0xa9b2,4); // SystemEvent
		return (cpu.popWord()&0xff00)!=0;
	}

	/** File: Close / Quit (both close the accessory, which ends the process); Edit: SystemEdit(item - 1). The accessory's own menu never gets here (MenuSelect passed it on). */
	private void menuCommand(int result) throws M68kException {
		int menu=(short)(result>>>16),item=result&0xffff;
		if(menu==FILE_MENU&&(item==1||item==2))close();
		else if(menu==EDIT_MENU&&item>=1){cpu.pushWord(0);cpu.pushWord(item-1);trap(0xa9c2,2);cpu.popWord();} // SystemEdit
		if(menu!=0){cpu.pushWord(0);trap(0xa938,2);} // HiliteMenu(0)
	}

	/** Executes a Toolbox trap through its trap word (see {@link ControlPanelHost68k}): the stub is [trap word][JMP sentinel]. */
	private void trap(int word,int argumentBytes) throws M68kException {
		Integer at=stubAddress.get(word);
		if(at==null){at=stubs+stubAddress.size()*8;if(stubAddress.size()>=64)throw new M68kCpuException(cpu.pc,"too many trap stubs");
			memory.writeWord(at,word);memory.writeWord(at+2,0x4ef9);memory.writeLong(at+4,M68kCpu.RETURN_SENTINEL);stubAddress.put(word,at);}
		int before=cpu.a[7];
		cpu.runUntilSentinel(at,argumentBytes);
		cpu.a[7]=before+argumentBytes;
	}
}

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desk accessories: 'DRVR' resources whose driver runs as guest code with a window of its own (Calculator, Key Caps...).
 * OpenDeskAcc installs the driver at the unit its resource ID names (so the IDs of the resources it owns,
 * $C000 | id << 5 | n, match) and calls its open routine; the System routines then hand the accessory what concerns it
 * through Control calls, as in Inside Macintosh I (The Desk Manager):
 * <ul>
 * <li>SystemClick: a click in the accessory's window (accEvent), its close box (CloseDeskAcc);
 * <li>SystemEvent: keys while its window is in front, updates and activates of its window (accEvent);
 * <li>SystemTask: accRun every dCtlDelay ticks for dNeedTime accessories, accCursor while its window is in front;
 * <li>SystemMenu: its own menu (dCtlMenu), which MenuSelect/MenuKey pass on themselves; SystemEdit: the Edit commands.
 * </ul>
 * In System 7 an accessory opened from the Finder runs in a process of its own ({@link DeskAccessoryHost68k}, the DA Handler).
 */
final class DeskAccessories68k {
	static final int ACC_EVENT=64,ACC_RUN=65,ACC_CURSOR=66,ACC_MENU=67,ACC_UNDO=68;
	private static final int TYPE_DRVR=0x44525652,TICKS=0x16a;
	private static final int D_NEED_TIME=0x2000,D_CTL_ENABLE=0x0400;
	private static final int MOUSE_UP=2,KEY_DOWN=3,KEY_UP=4,AUTO_KEY=5,UPDATE_EVT=6,ACTIVATE_EVT=8,CMD_KEY=0x100;
	/** The CntrlParam blocks we build: 50 bytes (csParam is 22). */
	private static final int PB_SIZE=50;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Open accessories: refNum -> their 'DRVR' Handle. */
	private final Map<Integer,Integer> open=new LinkedHashMap<Integer,Integer>();
	/** SystemTask is not re-entered from an accessory's own accRun. */
	private boolean running;

	DeskAccessories68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean isOpen(int refNum){return open.containsKey(refNum);}
	boolean anyOpen(){return !open.isEmpty();}

	// ------------------------------------------------------------------ traps

	/** The Desk Manager traps; false for any other. */
	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		switch(trap){
			case 0x9b6:{int name=memory.readLong(cpu.a[7]);cpu.a[7]+=4; // FUNCTION OpenDeskAcc(theAcc: Str255): INTEGER
				int refNum=open(cpu,name==0?null:tb.readPascalBytes(name));
				memory.writeWord(cpu.a[7],refNum);return true;}
			case 0x9b7:{int refNum=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;close(cpu,refNum);return true;} // CloseDeskAcc(refNum)
			case 0x9b3:{int window=memory.readLong(cpu.a[7]),event=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;systemClick(cpu,event,window);return true;} // SystemClick(event, window)
			case 0x9b2:{int event=memory.readLong(cpu.a[7]);cpu.a[7]+=4;boolean taken=systemEvent(cpu,event);tb.writeBoolean(cpu.a[7],taken);return true;} // SystemEvent(event): BOOLEAN
			case 0x9c2:{int command=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;boolean taken=systemEdit(cpu,command);tb.writeBoolean(cpu.a[7],taken);return true;} // SystemEdit(editCmd): BOOLEAN
			case 0x9b5:{int result=memory.readLong(cpu.a[7]);cpu.a[7]+=4;systemMenu(cpu,result);return true;} // SystemMenu(menuResult)
			default:return false;
		}
	}

	// ------------------------------------------------------------------ opening and closing

	/**
	 * OpenDeskAcc: the 'DRVR' with that name (as the Apple menu shows it, or with the leading NUL of an accessory's
	 * resource name; null = the first one of the current resource file) is installed and opened. Returns its refNum,
	 * or 0 when there is no such accessory or it could not be opened. An accessory already open is brought to the front.
	 */
	int open(M68kCpu cpu,byte[] pascalName) throws M68kException {
		ResourceFile68k.Resource r=null;
		if(pascalName==null){java.util.List<ResourceFile68k.Resource> all=tb.resources.resourcesOfFile(tb.resources.currentRefNum(),TYPE_DRVR);if(!all.isEmpty())r=all.get(0);}
		else{String name=StackTextEncoding.ROMAN.decode(pascalName,1,pascalName.length-1);
			r=tb.resources.findNamed(TYPE_DRVR,name,false);
			if(r==null)r=tb.resources.findNamed(TYPE_DRVR,"\0"+name,false);}
		if(r==null||r.id<0||r.id>=memory.readWord(DeviceManager68k.UNIT_NTRY_CNT))return 0;
		int refNum=~r.id;
		if(open.containsKey(refNum)){int dce=tb.devices.dcePointer(refNum),window=dce==0?0:memory.readLong(dce+30);if(window!=0&&tb.windows.isWindow(window))tb.windows.select(window);return refNum;}
		int handle=tb.resources.handleFor(r);
		if(handle==0||memory.readLong(handle)==0)return 0;
		tb.api.setHandleState(handle,tb.api.handleState(handle)|0x80); // HLock: a driver is called through its master pointer
		if(tb.devices.installDeskAccessory(handle,refNum)==0){System.err.println("[68K "+tb.name+"] desk accessory \""+r.name.substring(r.name.startsWith("\0")?1:0)+"\": unit "+r.id+" is taken");return 0;}
		open.put(refNum,handle);
		int result;
		int saved=cpu.a[7];
		try{int pb=parameterBlock(cpu);
			byte[] name=StackTextEncoding.ROMAN.encodeReplacing(r.name==null?"":r.name);int n=Math.min(255,name.length);
			memory.writeByte(pb+PB_SIZE,n);memory.load(pb+PB_SIZE+1,java.util.Arrays.copyOf(name,n));
			memory.writeLong(pb+18,pb+PB_SIZE); // ioNamePtr
			result=tb.devices.call(cpu,refNum,DeviceManager68k.OPEN,pb,0xa000);
		}finally{cpu.a[7]=saved;}
		if(Mac68kApi.TRACE)System.err.println("[68K "+tb.name+"] OpenDeskAcc \""+r.name+"\" refNum "+refNum+" -> "+result);
		if(result!=0){open.remove(refNum);tb.devices.remove(refNum);return 0;}
		int dce=tb.devices.dcePointer(refNum);
		memory.writeWord(dce+4,memory.readWord(dce+4)|0x20); // dOpened
		memory.writeLong(dce+26,memory.readLong(TICKS)); // dCtlCurTicks
		// the Desk Manager shows the accessory's window and makes it active (Key Caps creates it invisible)
		int window=memory.readLong(dce+30);
		if(window!=0&&tb.windows.isWindow(window)){tb.windows.show(window,true);tb.windows.select(window);}
		return refNum;
	}

	/** CloseDeskAcc: the accessory's close routine (it disposes of its window), then the unit is freed. */
	void close(M68kCpu cpu,int refNum) throws M68kException {
		Integer handle=open.remove(refNum);
		if(handle==null)return;
		int saved=cpu.a[7];
		try{int pb=parameterBlock(cpu);tb.devices.call(cpu,refNum,DeviceManager68k.CLOSE,pb,0xa001);}
		finally{cpu.a[7]=saved;tb.devices.remove(refNum);}
		tb.api.setHandleState(handle,tb.api.handleState(handle)&~0x80);
		if(Mac68kApi.TRACE)System.err.println("[68K "+tb.name+"] CloseDeskAcc "+refNum);
	}

	// ------------------------------------------------------------------ the System routines

	/** The accessory that owns a window (windowKind = its refNum), or 0. */
	private int ownerOf(int window) throws M68kCpuException {
		if(window==0||!tb.windows.isWindow(window))return 0;
		int kind=(short)memory.readWord(window+108);
		if(kind>=0)return 0;
		if(open.containsKey(kind))return kind;
		// a driver the program installed itself (DrvrInstall) and gave its window: THINK C's console puts a driver of its own
		// in a free unit and reads the keyboard through SystemEvent's accEvent calls to it, as the ROM makes for any DA window
		int dce=tb.devices.dcePointer(kind);
		return dce!=0&&memory.readLong(dce)!=0?kind:0;
	}

	private void systemClick(M68kCpu cpu,int event,int window) throws M68kException {
		int refNum=ownerOf(window);
		if(refNum==0)return;
		if(tb.windows.takeGoAway(window)){close(cpu,refNum);return;} // the host frame's close box
		if(tb.windows.frontWindow()!=window){tb.windows.select(window);return;}
		if(wants(refNum,memory.readWord(event)))control(cpu,refNum,ACC_EVENT,event);
	}

	/** Keys for the front accessory window (not Command keys: those are the program's menu keys), updates and activates of accessory windows. */
	/** GetNextEvent's own SystemEvent call (SEvtEnb set): an event for an open accessory goes to it, and the program is told
	 *  there was none - accessories opened with OpenDeskAcc used to get no keys, updates or activates unless the program
	 *  called SystemEvent itself. */
	boolean offerEvent(M68kCpu cpu,int event) throws M68kException {
		if(open.isEmpty()||event==0||memory.readByte(0x15c)==0)return false;
		return systemEvent(cpu,event);
	}
	private boolean systemEvent(M68kCpu cpu,int event) throws M68kException {
		int what=memory.readWord(event),refNum;
		switch(what){
			case KEY_DOWN:case AUTO_KEY:case KEY_UP:case MOUSE_UP:
				refNum=ownerOf(tb.windows.frontWindow());
				if(refNum==0)return false;
				if(what!=KEY_UP&&what!=MOUSE_UP&&(memory.readWord(event+14)&CMD_KEY)!=0)return false;
				break;
			case UPDATE_EVT:case ACTIVATE_EVT:
				refNum=ownerOf(memory.readLong(event+2));
				if(refNum==0)return false;
				break;
			default:return false;
		}
		if(wants(refNum,what))control(cpu,refNum,ACC_EVENT,event);
		else if(what==UPDATE_EVT)tb.windows.clearUpdate(memory.readLong(event+2)); // nobody draws it: keep the update from coming back
		return true;
	}

	/**
	 * accRun for the accessories that asked for time, when their delay has passed, and likewise for the other open drivers
	 * in the unit table (the ROM walks the whole table: QuicKeys' engine drivers do their work - opening the editor the
	 * control panel asked for - from accRun); accCursor for the accessory in front.
	 */
	void systemTask(M68kCpu cpu) throws M68kException {
		if(running)return;
		java.util.List<Integer> drivers=tb.devices.openDriverRefNums();
		if(open.isEmpty()&&drivers.isEmpty())return;
		running=true;
		try{
			int now=memory.readLong(TICKS);
			java.util.Set<Integer> units=new java.util.LinkedHashSet<Integer>(open.keySet());units.addAll(drivers);
			for(Integer refNum:units){
				int dce=tb.devices.dcePointer(refNum);if(dce==0)continue;
				int flags=memory.readWord(dce+4);
				if((flags&D_NEED_TIME)==0||(flags&D_CTL_ENABLE)==0)continue;
				int delay=memory.readWord(dce+34)&0xffff;
				if(now-memory.readLong(dce+26)<delay)continue;
				memory.writeLong(dce+26,now);
				control(cpu,refNum,ACC_RUN,0);
			}
			int front=ownerOf(tb.windows.frontWindow());
			if(front!=0&&open.containsKey(front))control(cpu,front,ACC_CURSOR,0);
		}finally{running=false;}
	}

	/** True when an open accessory has this menu (its dCtlMenu). */
	boolean ownsMenu(int menuId) throws M68kCpuException {
		if(menuId==0)return false;
		for(Integer refNum:open.keySet()){int dce=tb.devices.dcePointer(refNum);if(dce!=0&&(short)memory.readWord(dce+38)==menuId)return true;}
		return false;
	}

	/** accMenu to the accessory whose menu was chosen (csParam: the menu ID, then the item). */
	void systemMenu(M68kCpu cpu,int menuResult) throws M68kException {
		int menuId=(short)(menuResult>>>16);
		for(Integer refNum:new java.util.ArrayList<Integer>(open.keySet())){
			int dce=tb.devices.dcePointer(refNum);
			if(dce!=0&&(short)memory.readWord(dce+38)==menuId){control(cpu,refNum,ACC_MENU,menuResult);return;}
		}
	}

	/** Undo (0), Cut (2), Copy (3), Paste (4), Clear (5) for the accessory in front: accUndo + editCmd. */
	private boolean systemEdit(M68kCpu cpu,int command) throws M68kException {
		int refNum=ownerOf(tb.windows.frontWindow());
		if(refNum==0||command<0||command>5)return false;
		control(cpu,refNum,ACC_UNDO+command,0);
		return true;
	}

	private boolean wants(int refNum,int what) throws M68kCpuException {
		int dce=tb.devices.dcePointer(refNum);
		return dce!=0&&what>=0&&what<16&&(memory.readWord(dce+36)&(1<<what))!=0;
	}

	/**
	 * A Control call to the accessory. csParam holds {@code parameter}: an EventRecord pointer (accEvent), or the menu
	 * ID and item (accMenu).
	 */
	private void control(M68kCpu cpu,int refNum,int csCode,int parameter) throws M68kException {
		int saved=cpu.a[7];
		try{
			int pb=parameterBlock(cpu);
			memory.writeWord(pb+26,csCode);
			memory.writeLong(pb+28,parameter);
			tb.devices.call(cpu,refNum,DeviceManager68k.CONTROL,pb,0xa004);
		}finally{cpu.a[7]=saved;}
	}

	/** A cleared parameter block (and 256 bytes for a name after it) below the stack pointer; the caller restores A7. */
	private int parameterBlock(M68kCpu cpu) throws M68kCpuException {
		cpu.a[7]=(cpu.a[7]-PB_SIZE-256)&~3;
		memory.fill(cpu.a[7],PB_SIZE+256,0);
		return cpu.a[7];
	}
}

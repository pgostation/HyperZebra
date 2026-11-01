import java.util.HashMap;
import java.util.Map;

/**
 * Device Manager for drivers a program installs itself ('DRVR' code run as guest code): the unit table
 * (UTableBase/UnitNtryCnt), DrvrInstall/DrvrRemove, and the Open/Close/Read/Write/Control/Status/KillIO
 * calls on the reference numbers of those drivers, dispatched to the driver's routines with A0 = parameter
 * block and A1 = DCE like the ROM does. Some disk-image utilities install a driver this way and quit
 * with "could not load the disk image driver" without a unit table of at least 49 entries (units 0-47 are
 * Apple's, so it installs at 48 or above and checks UnitNtryCnt first).
 * <p>
 * Drivers the VM provides natively (.Sony, .Sound, .IPP, the display driver...) keep their refNums outside
 * the table; FileSystem68k answers those before asking here.
 */
final class DeviceManager68k {
	static final int UTABLE_BASE=0x11c,UNIT_NTRY_CNT=0x1d2,JIODONE=0x8fc;
	/** System 7 starts with 64 unit table entries (IM Devices 1-33: 48 on earlier systems; grown by programs through UTableBase). */
	static final int UNIT_COUNT=64;
	static final int DCE_SIZE=40;
	static final int NO_ERR=0,BAD_UNIT_ERR=-21,D_INST_ERR=-26,NOT_OPEN_ERR=-28;
	private static final int D_OPENED=0x20,D_RAM_BASED=0x40;
	static final int OPEN=0,PRIME=1,CONTROL=2,STATUS=3,CLOSE=4;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Units the program installed (unit -> DCE handle); the ROM's own units stay empty in the table. */
	private final Map<Integer,Integer> installed=new HashMap<Integer,Integer>();
	private int unitTable;
	private int ioDoneResult;

	/** Thrown by the JIODone gate: the driver finished the request (D0 = result) instead of returning. */
	private static final class IoDone extends M68kException {
		private static final long serialVersionUID=1L;
		IoDone(){super("IODone");}
	}

	DeviceManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** Allocates the unit table and points UTableBase/UnitNtryCnt/JIODone at it (called once the low-memory globals are being set up). */
	void initializeLowMemory() throws M68kCpuException {
		if(unitTable==0){unitTable=memory.allocate(UNIT_COUNT*4,4);memory.fill(unitTable,UNIT_COUNT*4,0);
			for(int i=0;i<ROM_UNITS.length;i+=2)romUnit((Integer)ROM_UNITS[i],(String)ROM_UNITS[i+1]);}
		memory.writeLong(UTABLE_BASE,unitTable);
		memory.writeWord(UNIT_NTRY_CNT,UNIT_COUNT);
		memory.writeLong(JIODONE,tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {ioDone(c);}}));
	}

	/**
	 * The drivers the VM answers natively, as the ROM/System leave them in the unit table: refNum, driver name. Programs read
	 * the table themselves (GetDCtlEntry glue) and compare the name at dCtlDriver+18 - Apple's CD Audio Toolkit takes the
	 * volume whose driver is ".AppleCD" for a CD, and with these units empty it read a nil DCE as a match for the boot disk.
	 */
	private static final Object[] ROM_UNITS={-4,".Sound",-5,".Sony",-6,".AIn",-7,".AOut",-8,".BIn",-9,".BOut",-10,".MPP",-11,".ATP",
		QuickDraw68k.VIDEO_DRIVER_REF,".Display_Video_HyperZebra",FileManager68k.CD_DRIVER_REF,".AppleCD",FileManager68k.DISK_DRIVER_REF,".HyperZebra_Disk"};

	/** A ROM-based (not dRAMBased), open DCE whose dCtlDriver is a driver header with that name; its calls stay native (FileSystem68k). */
	private void romUnit(int refNum,String name) throws M68kCpuException {
		int unit=~refNum&0xffff;if(unit>=UNIT_COUNT)return;
		int header=memory.allocate(18+1+name.length()+1,2);memory.fill(header,18+1+name.length()+1,0);
		memory.writeWord(header,0x4f00); // dReadEnable/dWritEnable/dCtlEnable/dStatEnable/dNeedGoodBye
		memory.writeByte(header+18,name.length());for(int i=0;i<name.length();i++)memory.writeByte(header+19+i,name.charAt(i));
		int dce=memory.allocate(DCE_SIZE,2);memory.fill(dce,DCE_SIZE,0);
		// the serial drivers are closed until a program opens them (FirstClass asks the unit table whether the modem port is in use)
		memory.writeLong(dce,header);memory.writeWord(dce+4,0x4f00|(name.matches("\\.[AB](In|Out)")?0:D_OPENED));memory.writeWord(dce+24,refNum);
		int master=memory.allocate(4,4);memory.writeLong(master,dce); // the unit table holds DCE Handles
		memory.writeLong(unitTable+unit*4,master);
	}

	/** Sets or clears dOpened in the DCE of a ROM unit (the serial drivers, which the file system opens and closes natively). */
	void setRomUnitOpened(int refNum,boolean opened) throws M68kCpuException {
		int unit=~refNum&0xffff,table=memory.readLong(UTABLE_BASE);
		if(refNum>=0||table==0||unit>=memory.readWord(UNIT_NTRY_CNT))return;
		int master=memory.readLong(table+unit*4);if(master==0)return;
		int dce=memory.readLong(master);if(dce==0)return;
		int flags=memory.readWord(dce+4);memory.writeWord(dce+4,opened?flags|D_OPENED:flags&~D_OPENED);
	}

	// ------------------------------------------------------------------ DrvrInstall / DrvrRemove

	/** _DrvrInstall ($A03D, $A43D reserve-memory variant): A0 = driver pointer (or handle with dRAMBased set later by the caller), D0 = refNum. */
	boolean drvrInstall(M68kCpu cpu) throws M68kCpuException {
		int refNum=(short)cpu.d[0],unit=~refNum&0xffff,driver=cpu.a[0];
		int count=memory.readWord(UNIT_NTRY_CNT),table=memory.readLong(UTABLE_BASE);
		if(refNum>=0||unit>=count||table==0){cpu.d[0]=BAD_UNIT_ERR;return true;}
		if(memory.readLong(table+unit*4)!=0){cpu.d[0]=D_INST_ERR;return true;} // the unit is taken
		int dce;
		try{dce=tb.api.createHandle(new byte[DCE_SIZE]);}catch(M68kCpuException e){cpu.d[0]=Toolbox68k.MEM_FULL_ERR;return true;}
		int p=memory.readLong(dce);
		memory.writeLong(p,driver);                                   // dCtlDriver
		memory.writeWord(p+4,driver==0?0:memory.readWord(driver)&~D_RAM_BASED); // dCtlFlags from the header (the caller may set dRAMBased)
		memory.writeWord(p+24,refNum);                                // dCtlRefNum
		if(driver!=0){memory.writeWord(p+34,memory.readWord(driver+2));memory.writeWord(p+36,memory.readWord(driver+4));memory.writeWord(p+38,memory.readWord(driver+6));} // dCtlDelay/EMask/Menu
		memory.writeLong(table+unit*4,dce);
		installed.put(unit,dce);
		if(Mac68kApi.TRACE)System.err.println("[68K] DrvrInstall "+driverName(p)+" refNum "+refNum+" (unit "+unit+") -> DCE "+Integer.toHexString(dce));
		// the ROM returns the new DCE's Handle in A0: THINK C's console installs its ".console" driver with junk in A0 and then
		// fills the DCE through MOVEA.L (A0),A4 - with A0 left alone it wrote over the DCE Handle's master pointer
		cpu.a[0]=dce;cpu.d[0]=NO_ERR;return true;
	}

	/** _DrvrRemove ($A03E): D0 = refNum; the DCE is disposed and the unit freed (the driver itself belongs to the caller). */
	boolean drvrRemove(M68kCpu cpu) throws M68kCpuException {
		int refNum=(short)cpu.d[0],unit=~refNum&0xffff;
		Integer dce=installed.remove(unit);
		if(dce==null){cpu.d[0]=BAD_UNIT_ERR;return true;}
		int table=memory.readLong(UTABLE_BASE);
		if(table!=0&&unit<memory.readWord(UNIT_NTRY_CNT))memory.writeLong(table+unit*4,0);
		tb.api.disposeHandle(dce);
		cpu.d[0]=NO_ERR;return true;
	}

	// ------------------------------------------------------------------ desk accessories

	/**
	 * Installs a desk accessory's 'DRVR' (a locked resource Handle, so dRAMBased) at the unit its refNum names, the way
	 * OpenDeskAcc does before calling the open routine. Returns the DCE Handle, or 0 when the unit is out of the table or taken.
	 */
	int installDeskAccessory(int driverHandle,int refNum) throws M68kCpuException {
		int unit=~refNum&0xffff,count=memory.readWord(UNIT_NTRY_CNT),table=memory.readLong(UTABLE_BASE);
		if(refNum>=0||unit>=count||table==0||memory.readLong(table+unit*4)!=0)return 0;
		int dce=tb.api.createHandle(new byte[DCE_SIZE]);
		int p=memory.readLong(dce),driver=memory.readLong(driverHandle);
		memory.writeLong(p,driverHandle);                                         // dCtlDriver
		memory.writeWord(p+4,(memory.readWord(driver)&0xff00)|D_RAM_BASED);        // dCtlFlags: the header's enable/need bits
		memory.writeWord(p+24,refNum);                                            // dCtlRefNum
		memory.writeWord(p+34,memory.readWord(driver+2));memory.writeWord(p+36,memory.readWord(driver+4));memory.writeWord(p+38,memory.readWord(driver+6)); // dCtlDelay/EMask/Menu
		memory.writeLong(table+unit*4,dce);
		installed.put(unit,dce);
		return dce;
	}
	/** Frees an installed unit (the driver itself stays with whoever loaded it). */
	void remove(int refNum) throws M68kCpuException {
		int unit=~refNum&0xffff;
		Integer dce=installed.remove(unit);
		if(dce==null)return;
		int table=memory.readLong(UTABLE_BASE);
		if(table!=0&&unit<memory.readWord(UNIT_NTRY_CNT)&&memory.readLong(table+unit*4)==dce)memory.writeLong(table+unit*4,0);
		tb.api.disposeHandle(dce);
	}
	/** The refNums of the installed drivers that are open (dOpened), in unit order. */
	java.util.List<Integer> openDriverRefNums() throws M68kCpuException {
		java.util.List<Integer> refNums=new java.util.ArrayList<Integer>();
		for(Integer unit:new java.util.TreeSet<Integer>(installed.keySet())){
			int dce=dceOf(~unit);if(dce==0)continue;
			int p=memory.readLong(dce);
			if(p!=0&&(memory.readWord(p+4)&D_OPENED)!=0)refNums.add(~unit);
		}
		return refNums;
	}
	/** The DCE (a pointer) of an installed unit, or 0. */
	int dcePointer(int refNum) throws M68kCpuException {int dce=dceOf(refNum);return dce==0?0:memory.readLong(dce);}
	/**
	 * Calls a routine of an installed driver with a parameter block the caller filled in (ioNamePtr for open, csCode and
	 * csParam for control) and returns the result; {@code opcode} is the trap word the routine finds in ioTrap.
	 */
	int call(M68kCpu cpu,int refNum,int routine,int pb,int opcode) throws M68kException {
		int dce=dceOf(refNum);
		if(dce==0)return BAD_UNIT_ERR;
		memory.writeWord(pb+24,refNum);
		int result=callDriver(cpu,opcode,pb,dce,routine);
		memory.writeWord(pb+16,result);
		return result;
	}

	// ------------------------------------------------------------------ device calls

	/** The DCE handle in the unit table for a refNum the program installed, or 0. */
	private int dceOf(int refNum) throws M68kCpuException {
		if(refNum>=0)return 0;
		int unit=~refNum&0xffff;
		Integer dce=installed.get(unit);
		if(dce==null)return 0;
		int table=memory.readLong(UTABLE_BASE);
		if(table==0||unit>=memory.readWord(UNIT_NTRY_CNT))return 0;
		return memory.readLong(table+unit*4)==dce?dce:0;
	}
	/** The DCE handle of an installed driver with that name (case-insensitive like the ROM), or 0. */
	private int dceNamed(String name) throws M68kCpuException {
		for(Map.Entry<Integer,Integer> e:installed.entrySet()){
			int p=memory.readLong(e.getValue());
			if(name.equalsIgnoreCase(driverName(p)))return e.getValue();
		}
		return 0;
	}
	private int driverBase(int dcePtr) throws M68kCpuException {
		int driver=memory.readLong(dcePtr);
		if(driver==0)return 0;
		return (memory.readWord(dcePtr+4)&D_RAM_BASED)!=0?memory.readLong(driver):driver;
	}
	private String driverName(int dcePtr){
		try{int base=driverBase(dcePtr);return base==0?"?":tb.readPascal(base+18);}catch(M68kCpuException e){return "?";}
	}

	/**
	 * Routes the Device Manager traps ($A000-$A006: Open/Close/Read/Write/Control/Status/KillIO) whose refNum, or driver
	 * name for Open, names a driver the program installed. Returns false when the call is not for one of them.
	 */
	boolean handle(M68kCpu cpu,int opcode,int pb,int trap,boolean async) throws M68kException {
		int dce;
		if(trap==0x00){
			if((opcode&0x0200)!=0)return false; // HOpen: a file
			int name=memory.readLong(pb+18);
			if(name==0||memory.readByte(name)==0||memory.readByte(name+1)!='.')return false;
			dce=dceNamed(tb.readPascal(name));
			if(dce==0)return false;
			int p=memory.readLong(dce),refNum=(short)memory.readWord(p+24);
			memory.writeWord(pb+24,refNum);
			int result=NO_ERR;
			if((memory.readWord(p+4)&D_OPENED)==0){
				result=callDriver(cpu,opcode,pb,dce,OPEN);
				if(result==NO_ERR)memory.writeWord(p+4,memory.readWord(p+4)|D_OPENED);
			}
			if(Mac68kApi.TRACE)System.err.println("[68K] OpenDriver "+driverName(p)+" (installed) -> "+refNum+" result "+result);
			tb.files.finish(cpu,pb,result,async);return true;
		}
		int refNum=(short)memory.readWord(pb+24);
		dce=dceOf(refNum);
		if(dce==0)return false;
		int p=memory.readLong(dce);
		int result;
		switch(trap){
			case 0x01:{ // Close
				if((memory.readWord(p+4)&D_OPENED)==0)result=NOT_OPEN_ERR;
				else{result=callDriver(cpu,opcode,pb,dce,CLOSE);if(result==NO_ERR)memory.writeWord(p+4,memory.readWord(p+4)&~D_OPENED);}
				break;}
			case 0x02:case 0x03:result=callDriver(cpu,opcode,pb,dce,PRIME);break;   // Read / Write
			case 0x04:result=callDriver(cpu,opcode,pb,dce,CONTROL);break;           // Control (KillIO comes through here with csCode 1)
			case 0x05:result=callDriver(cpu,opcode,pb,dce,STATUS);break;
			case 0x06:memory.writeWord(pb+26,1);result=callDriver(cpu,opcode,pb,dce,CONTROL);break; // KillIO: csCode 1 to the control routine
			default:return false;
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] driver "+driverName(p)+" "+(trap==0x01?"Close":trap==0x02||trap==0x03?(trap==0x02?"Read":"Write")+" drive "+(short)memory.readWord(pb+22)+" mode "+memory.readWord(pb+44)+" offset "+memory.readLong(pb+46)+" count "+memory.readLong(pb+36)+" actual "+memory.readLong(pb+40):trap==0x04?"Control csCode "+(short)memory.readWord(pb+26):trap==0x05?"Status csCode "+(short)memory.readWord(pb+26):"KillIO")+" -> "+result);
		tb.files.finish(cpu,pb,result,async);return true;
	}

	/** The DCEs whose routine is running now (callDriver): IODone from inside one ends that call. */
	private final java.util.ArrayDeque<Integer> running=new java.util.ArrayDeque<Integer>();
	/**
	 * IODone (JIODone, A1 = DCE, D0 = result). From the routine of the call under way it ends that call (the driver jumps there
	 * instead of returning). Later - a deferred task or a timer finishing a request the routine left in progress, as Disk Copy's
	 * image driver does when it decompresses - it completes the request at the head of the driver's queue: ioResult, the queue
	 * emptied, ioCompletion called; then returns like the ROM's IODone.
	 */
	private void ioDone(M68kCpu c) throws M68kException {
		int dcePtr=c.a[1],result=(short)c.d[0];
		if(!running.isEmpty()&&running.peek()==dcePtr){ioDoneResult=result;throw new IoDone();}
		int pb=dcePtr==0?0:memory.readLong(dcePtr+8);
		if(Mac68kApi.TRACE)System.err.println("[68K] IODone later: driver "+driverName(dcePtr)+" request $"+Integer.toHexString(pb)+" -> "+result);
		if(pb==0)return;
		memory.writeLong(dcePtr+8,0);memory.writeLong(dcePtr+12,0);
		memory.writeWord(pb+16,result);
		int completion=memory.readLong(pb+12);
		if(completion!=0){int[] d=c.d.clone(),a=c.a.clone();c.a[0]=pb;c.d[0]=result;
			try{c.call(completion);}finally{System.arraycopy(d,0,c.d,0,8);System.arraycopy(a,0,c.a,0,8);}}
	}
	/**
	 * Runs one driver routine as the ROM does: A0 = parameter block, A1 = DCE pointer, the request queued in dCtlQHdr,
	 * ioTrap/ioResult set. The routine either returns with the result in D0 or jumps through JIODone with D0 = result.
	 */
	private int callDriver(M68kCpu cpu,int opcode,int pb,int dce,int routine) throws M68kException {
		int p=memory.readLong(dce),base=driverBase(p);
		if(base==0)return D_INST_ERR;
		int flags=memory.readWord(p+4);  // dReadEnable/dWritEnable/dCtlEnable/dStatEnable (bits 8-11)
		if(routine==PRIME){boolean read=(opcode&0xff)==0x02;if((flags&(read?0x100:0x200))==0)return read?-19:-20;} // readErr/writErr
		else if(routine==CONTROL&&(flags&0x400)==0)return -17; // controlErr
		else if(routine==STATUS&&(flags&0x800)==0)return -18;  // statusErr
		int offset=memory.readWord(base+8+routine*2);
		int entry=base+offset;
		if(routine==PRIME){ // dCtlPosition from ioPosMode/ioPosOffset, as the Device Manager sets it for a block device: Disk Copy's
			// image driver reads from there (it ignores ioPosOffset), so a mount read the image from wherever the last read ended
			int mode=memory.readWord(pb+44)&3,position=memory.readLong(pb+46);
			if(mode==1)memory.writeLong(p+16,position);else if(mode==3)memory.writeLong(p+16,memory.readLong(p+16)+position);
		}
		memory.writeWord(pb+6,opcode);   // ioTrap
		memory.writeWord(pb+16,1);       // ioResult: in progress
		memory.writeLong(pb,0);          // qLink
		memory.writeWord(pb+4,2);        // qType ioQType
		memory.writeLong(p+8,pb);memory.writeLong(p+12,pb); // dCtlQHdr.qHead/qTail: the current request
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		int result;
		try{
			cpu.a[0]=pb;cpu.a[1]=p;cpu.d[0]=0;
			running.push(p);
			boolean returned=false;
			try{cpu.call(entry);result=(short)cpu.d[0];returned=true;}
			catch(IoDone e){result=ioDoneResult;}
			finally{running.pop();}
			// returned with the request still in progress and work queued to finish it (a deferred task): the caller waits, as the
			// ROM's synchronous calls spin on ioResult, while interrupt-time work runs; that work's IODone fills in the result
			if(returned&&memory.readWord(pb+16)==1&&tb.deferredTasksPending()){
				for(int i=0;i<10000&&memory.readWord(pb+16)==1;i++){tb.runInterruptWork();if(!tb.deferredTasksPending()&&memory.readWord(pb+16)==1)Thread.onSpinWait();}
				if(memory.readWord(pb+16)!=1)result=(short)memory.readWord(pb+16);
			}
		}finally{
			int keep=cpu.d[0];
			System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;cpu.d[0]=keep;
			memory.writeLong(p+8,0);memory.writeLong(p+12,0);
		}
		return result;
	}
}

/**
 * ADB Manager: the two devices of a standard Macintosh, a keyboard at address 2 and a mouse at address 3. There is no bus
 * to talk to - the pointer and the keys come from the host - so the managers exists for programs that read the mouse raw:
 * Some programs install their own service routine on the mouse with SetADBInfo and expect Talk-register-0 packets (button and
 * 7-bit deltas) from every movement, chaining to the ROM's routine afterwards. Here the ROM routine is a host gate that
 * does nothing (the cursor position is the host's) and the packets are made from the pointer's movement between polls.
 */
final class AdbManager68k {
	static final int KEYBOARD_ADDRESS=2,MOUSE_ADDRESS=3;
	private static final int TALK_REGISTER_0=0x0c;
	/** Largest step one packet carries (7-bit two's complement deltas); bigger moves are split over several packets. */
	private static final int MAX_DELTA=63,MAX_PACKETS_PER_POLL=8;

	private static final class Device {
		final int address,devType;int serviceRoutine,dataArea;
		Device(int address,int devType){this.address=address;this.devType=devType;}
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Device keyboard=new Device(KEYBOARD_ADDRESS,KeyboardLayout68k.KEYBOARD_TYPE),mouse=new Device(MOUSE_ADDRESS,3);
	private final Device[] devices={keyboard,mouse};
	private int romRoutine,packet;
	private int lastH=Integer.MIN_VALUE,lastV,lastPollTick=-1;private boolean lastDown;

	AdbManager68k(Toolbox68k tb){this.tb=tb;this.memory=tb.memory;}

	/** The ROM's service routine, as programs read it back before replacing it: a gate that does nothing. */
	private int romRoutine(){
		if(romRoutine==0)romRoutine=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c){}});
		return romRoutine;
	}
	private int routineOf(Device d){return d.serviceRoutine!=0?d.serviceRoutine:romRoutine();}
	private Device deviceAt(int address){for(Device d:devices)if(d.address==(address&0xf))return d;return null;}

	/** OS traps $A077-$A07C (register based; D0 = result code). */
	boolean handle(M68kCpu cpu,int trap) throws M68kCpuException {
		switch(trap){
			case 0x77:cpu.d[0]=devices.length;return true;                       // CountADBs
			case 0x78:{int index=(short)cpu.d[0];                                // GetIndADB(block, index): D0 = ADB address, -1 past the end
				if(index<1||index>devices.length){cpu.d[0]=-1;return true;}
				Device d=devices[index-1];writeDataBlock(cpu.a[0],d);cpu.d[0]=d.address;return true;}
			case 0x79:{Device d=deviceAt(cpu.d[0]);                              // GetADBInfo(block, address)
				if(d==null){cpu.d[0]=-1;return true;}
				writeDataBlock(cpu.a[0],d);cpu.d[0]=Toolbox68k.NO_ERR;return true;}
			case 0x7a:{Device d=deviceAt(cpu.d[0]);                              // SetADBInfo(ADBSetInfoBlock, address)
				if(d==null){cpu.d[0]=-1;return true;}
				d.serviceRoutine=memory.readLong(cpu.a[0]);d.dataArea=memory.readLong(cpu.a[0]+4);
				if(Mac68kApi.TRACE)System.err.printf("[68K] SetADBInfo address %d: service routine $%06X data area $%08X%n",d.address,d.serviceRoutine,d.dataArea);
				cpu.d[0]=Toolbox68k.NO_ERR;return true;}
			case 0x7b:for(Device d:devices){d.serviceRoutine=0;d.dataArea=0;}cpu.d[0]=Toolbox68k.NO_ERR;return true; // ADBReInit
			case 0x7c:return adbOp(cpu);
			default:return false;
		}
	}

	/** ADBDataBlock: devType.b, origADBAddr.b, dbServiceRtPtr.l, dbDataAreaAddr.l. */
	private void writeDataBlock(int block,Device d) throws M68kCpuException {
		memory.writeByte(block,d.devType);memory.writeByte(block+1,d.address);
		memory.writeLong(block+2,routineOf(d));memory.writeLong(block+6,d.dataArea);
	}

	/**
	 * ADBOp(A0 = ADBOpBlock: dataBuffPtr, opServiceRtPtr, opDataAreaPtr; D0 = command byte). Nothing answers on the bus:
	 * a Talk completes with an empty packet, Listen/Flush/Reset with the buffer as given, and the completion routine (when
	 * there is one) runs at once with the usual registers.
	 */
	/** Register 2 contents a Listen gave each device (by ADB address). */
	private final java.util.Map<Integer,byte[]> register2=new java.util.HashMap<Integer,byte[]>();
	private boolean adbOp(M68kCpu cpu) throws M68kCpuException {
		int block=cpu.a[0],command=cpu.d[0]&0xff;
		int buffer=memory.readLong(block),completion=memory.readLong(block+4),dataArea=memory.readLong(block+8);
		if(Mac68kApi.TRACE)System.err.printf("[68K] ADBOp command $%02X buffer $%08X completion $%08X%n",command,buffer,completion);
		if(deviceAt(command>>4)==null){cpu.d[0]=-1;return true;}
		// register 2 of the extended keyboard holds its LED bits (0-2, active low): what a Listen wrote is answered by the next
		// Talk (ADB Lights: SetLights 5 then Lights() read 255 back); the host's own LEDs do not change
		if((command&0x0f)==0x0a&&buffer!=0){int n=Math.min(8,memory.readByte(buffer));byte[] r=new byte[n];for(int i=0;i<n;i++)r[i]=(byte)memory.readByte(buffer+1+i);register2.put(command>>4,r);}
		else if((command&0x0f)==0x0e&&buffer!=0&&(command>>4)==2){byte[] r=register2.getOrDefault(2,new byte[]{(byte)0xff,(byte)0xff});memory.writeByte(buffer,r.length);for(int i=0;i<r.length;i++)memory.writeByte(buffer+1+i,r[i]&0xff);}
		else if((command&0x0c)==TALK_REGISTER_0&&buffer!=0)memory.writeByte(buffer,0);
		if(completion!=0){
			final int routine=completion;
			tb.later(new Runnable(){@Override public void run(){try{callService(routine,buffer,dataArea,command);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});
		}
		cpu.d[0]=Toolbox68k.NO_ERR;return true;
	}

	/** Service routine convention: A0 = packet (Pascal string), A1 = the routine, A2 = its data area, D0 = command. */
	private void callService(int routine,int buffer,int dataArea,int command) throws M68kException {
		int[] d=tb.cpu.d.clone(),a=tb.cpu.a.clone();int sr=tb.cpu.sr;
		tb.cpu.a[0]=buffer;tb.cpu.a[1]=routine;tb.cpu.a[2]=dataArea;tb.cpu.d[0]=command;
		try{tb.cpu.call(routine);}
		finally{System.arraycopy(d,0,tb.cpu.d,0,8);System.arraycopy(a,0,tb.cpu.a,0,8);tb.cpu.sr=sr;}
	}

	/** The pointer jumped (RawMouse warp through the cursor task): no packets for the jump, like a real bus. */
	void pointerWarped(int h,int v){lastH=h;lastV=v;}

	/**
	 * Interrupt-time work: the mouse's Talk-register-0 packets for the movement and button change since the last poll
	 * (byte 1 = button up bit 7 + Y delta, byte 2 = bit 7 set + X delta), delivered only while a program has its own
	 * routine on the mouse - the host keeps the cursor itself.
	 */
	void poll() throws M68kException {
		if(mouse.serviceRoutine==0||!tb.hosted())return;
		// Once a tick: the poll comes every 4096 instructions and reading the host pointer is a native call (it can starve a
		// music mixer); a real mouse reports at about that rate anyway.
		int tick=tb.tickCount();if(tick==lastPollTick)return;lastPollTick=tick;
		MacAppHost host=tb.host();java.awt.Point p=host.mouseLocation();boolean down=host.isMouseDown();
		if(lastH==Integer.MIN_VALUE){lastH=p.x;lastV=p.y;lastDown=down;return;}
		int dx=p.x-lastH,dy=p.y-lastV;boolean changed=down!=lastDown;
		if(dx==0&&dy==0&&!changed)return;
		lastDown=down;
		if(packet==0)packet=memory.allocate(4,4);
		for(int i=0;i<MAX_PACKETS_PER_POLL&&(dx!=0||dy!=0||changed);i++){
			int sx=Math.max(-MAX_DELTA-1,Math.min(MAX_DELTA,dx)),sy=Math.max(-MAX_DELTA-1,Math.min(MAX_DELTA,dy));dx-=sx;dy-=sy;changed=false;
			memory.writeByte(packet,2);memory.writeByte(packet+1,(down?0:0x80)|(sy&0x7f));memory.writeByte(packet+2,0x80|(sx&0x7f));
			callService(mouse.serviceRoutine,packet,mouse.dataArea,(MOUSE_ADDRESS<<4)|TALK_REGISTER_0);
		}
		lastH=p.x-dx;lastV=p.y-dy; // whatever did not fit goes out with the next poll
	}

	static String name(int trap){switch(trap){
		case 0x77:return "CountADBs";case 0x78:return "GetIndADB";case 0x79:return "GetADBInfo";
		case 0x7a:return "SetADBInfo";case 0x7b:return "ADBReInit";case 0x7c:return "ADBOp";
		default:return null;
	}}
}

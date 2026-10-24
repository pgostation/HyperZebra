import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Apple MIDI Manager 2.0 for hosted 68K programs, natively: the group-4 selectors of _SoundDispatch (MIDI.h - clients,
 * ports, data and time connections, time bases with wake-ups, packets and read hooks), with the Apple MIDI Driver
 * standing in as client 'amdr' whose ports 'Ain '/'Aout' (Modem Port) and 'Bin '/'Bout' (Printer Port) reach the host's
 * MIDI devices through {@link MidiHost68k}. PatchBay's job - wiring a program's ports to the driver - is done lazily: an
 * output port that has no connection when the program first writes to it is connected to the Modem Port, and an input
 * port that has none when MIDI arrives from the host is connected from it (a program that wires its own ports first,
 * as M 2.2.4 does with its 'Pinf' resources, keeps its wiring; MIDIGraphy, which leaves that to PatchBay, plays).
 * <p>
 * Everything runs on the VM thread: MIDI from the host is queued and handed out, read hooks and wake-ups are called, at
 * the Toolbox's safe points ({@link #service}), the way the real manager's 1 ms interrupt would. MIDIWritePacket to a
 * port of another client in the same VM delivers straight to that port's read hook when the packet is due.
 */
final class MidiManager68k {
	static final int NO_ERR=0,NO_CLIENT_ERR=-250,NO_PORT_ERR=-251,TOO_MANY_PORTS_ERR=-252,TOO_MANY_CONS_ERR=-253,VCONNECT_ERR=-254,NO_CON_ERR=-257,WRITE_ERR=-258,NAME_LEN_ERR=-259,DUP_ID_ERR=-260,INVALID_CMD_ERR=-261;
	/** MIDIVersion: 2.0.1 final. */
	static final int VERSION=0x02018000;
	static final int PORT_TIME=0,PORT_INPUT=1,PORT_OUTPUT=2,PORT_TIME_INVISIBLE=3,PORT_INVISIBLE=0x8000,PORT_TYPE_MASK=7;
	static final int GET_EVERYTHING=0x7fffffff,GET_NOTHING=0x80000000,GET_CURRENT=0;
	static final int FLAG_CONT_MASK=3,FLAG_START_CONT=1,FLAG_END_CONT=2,FLAG_MID_CONT=3,FLAG_TYPE_MASK=0x70,FLAG_MGR_TYPE=0x10,FLAG_STAMP_CURRENT=0x80;
	static final int KEEP_PACKET=0,MORE_PACKET=1,NO_MORE_PACKET=2,HOLD_PACKET=3;
	static final int FORMAT_MSEC=0,FORMAT_BEATS=1;
	static final int SYNC_INTERNAL=0,SYNC_EXTERNAL=1;
	static final int DRIVER_CLIENT=0x616d6472; // 'amdr'
	static final int PORT_A_IN=0x41696e20,PORT_A_OUT=0x416f7574,PORT_B_IN=0x42696e20,PORT_B_OUT=0x426f7574; // 'Ain ' 'Aout' 'Bin ' 'Bout'
	private static final int PACKET_HEADER=6,PACKET_MAX_DATA=249,DEFAULT_BUFFER=1024;

	private final M68kMemory memory;
	private final Mac68kApi macApi;
	private final Map<Integer,Client> clients=new LinkedHashMap<Integer,Client>();
	private final Map<Integer,Port> portsByRefNum=new LinkedHashMap<Integer,Port>();
	private int nextRefNum=1;
	/** Bumped by every change of clients, ports or connections; a client's MIDIWorldChanged answers whether it has seen the latest. */
	private int worldVersion;
	private final ConcurrentLinkedQueue<byte[]> hostInput=new ConcurrentLinkedQueue<byte[]>();
	private final MidiHost68k.Listener hostListener=new MidiHost68k.Listener(){@Override public void received(int port,byte[] message){byte[] m=new byte[message.length+1];m[0]=(byte)port;System.arraycopy(message,0,m,1,message.length);hostInput.add(m);Toolbox68k tb=macApi.application();if(tb!=null)tb.wakeIdle();}};
	private boolean listening,servicing,inHook;
	private final MidiStream[] outputStreams={new MidiStream(MidiHost68k.MODEM),new MidiStream(MidiHost68k.PRINTER)};

	static final class Client {
		final int id;int refCon,icon;String name;final boolean virtual;
		final List<Port> ports=new ArrayList<Port>();
		int seenWorld;
		Client(int id,String name,boolean virtual){this.id=id;this.name=name;this.virtual=virtual;}
	}
	static final class Packet {
		int flags;int tStamp;final byte[] data;boolean held;
		Packet(int flags,int tStamp,byte[] data){this.flags=flags;this.tStamp=tStamp;this.data=data;}
	}
	static final class Port {
		final Client client;final int id,refNum,type;String name;int refCon,readHook,offsetTime,bufferSize;
		/** The refNum of the time port this port's stamps are read against (0 = none). */
		Port timeBase;
		/** Data connections (an output port's inputs, an input port's outputs) and time connections (this time port's slaves and master). */
		final List<Port> dataConnections=new ArrayList<Port>(),timeConnections=new ArrayList<Port>();
		/** For a time port: the time port it follows (MIDIConnectTime), which makes its own clock external. */
		Port timeSource;
		// a time port's clock
		int format,sync;boolean running;long value,valueNanos;int rate=256;
		// a time port's wake-up
		int wakeProc;long wakeTime,wakePeriod;boolean wakeArmed;
		// an input port's buffer and the guest-side scratch packet its read hook sees
		final Deque<Packet> buffer=new ArrayDeque<Packet>();int buffered,scratch;
		int connectionProc,connectionRefCon;
		/** Which host port a driver port stands for. */
		int hostPort=-1;
		Port(Client client,int id,int refNum,int type,String name){this.client=client;this.id=id;this.refNum=refNum;this.type=type;this.name=name;}
		int kind(){return type&PORT_TYPE_MASK;}
		boolean isTime(){int k=kind();return k==PORT_TIME||k==PORT_TIME_INVISIBLE;}
		boolean isInput(){return kind()==PORT_INPUT;}
		boolean isOutput(){return kind()==PORT_OUTPUT;}
	}

	MidiManager68k(M68kMemory memory,Mac68kApi macApi){
		this.memory=memory;this.macApi=macApi;
		Client driver=new Client(DRIVER_CLIENT,"Apple MIDI Driver",true);clients.put(DRIVER_CLIENT,driver);
		addVirtualPort(driver,PORT_A_IN,PORT_OUTPUT,"Modem Port",MidiHost68k.MODEM);
		addVirtualPort(driver,PORT_A_OUT,PORT_INPUT,"Modem Port",MidiHost68k.MODEM);
		addVirtualPort(driver,PORT_B_IN,PORT_OUTPUT,"Printer Port",MidiHost68k.PRINTER);
		addVirtualPort(driver,PORT_B_OUT,PORT_INPUT,"Printer Port",MidiHost68k.PRINTER);
	}
	private void addVirtualPort(Client c,int id,int type,String name,int hostPort){Port p=new Port(c,id,nextRefNum++,type,name);p.hostPort=hostPort;c.ports.add(p);portsByRefNum.put(p.refNum,p);}

	void shutDown(){if(listening){MidiHost68k.removeListener(hostListener);listening=false;}for(MidiStream s:outputStreams)s.allNotesOff();}

	// ------------------------------------------------------------------ dispatch

	/**
	 * A group-4 _SoundDispatch call: {@code selector} as MIDI.h numbers them (0 = MIDIVersion, 4 = MIDISignIn...), SP at the
	 * last argument. The glue carries no argument size, so each selector pops its own; the result goes where the caller
	 * made room. Returns false for a selector this manager does not know (the caller reports it).
	 */
	boolean dispatch(M68kCpu cpu,int selector) throws M68kException {
		Toolbox68k tb=macApi.application();int sp=cpu.a[7];
		if(Mac68kApi.TRACE)System.err.println("[midi] "+name(selector)+" at SP $"+Integer.toHexString(sp));
		switch(selector){
			case 0x00:memory.writeLong(sp,VERSION);return true; // MIDIVersion
			case 0x04:{int clientId=memory.readLong(sp+12),refCon=memory.readLong(sp+8),icon=memory.readLong(sp+4),name=memory.readLong(sp);cpu.a[7]+=16;result(cpu,signIn(tb,clientId,refCon,icon,name));return true;}
			case 0x08:{int clientId=memory.readLong(sp);cpu.a[7]+=4;signOut(clientId);return true;}
			case 0x0c:{List<Integer> ids=new ArrayList<Integer>();for(Client c:clients.values())ids.add(c.id);memory.writeLong(sp,idList(ids));return true;} // MIDIGetClients
			case 0x10:{int clientId=memory.readLong(sp+4),name=memory.readLong(sp);cpu.a[7]+=8;Client c=clients.get(clientId);tb.writePascal(name,c==null?"":c.name,31);return true;}
			case 0x14:{int clientId=memory.readLong(sp+4),name=memory.readLong(sp);cpu.a[7]+=8;Client c=clients.get(clientId);if(c!=null){c.name=tb.readPascal(name);changed(c);}return true;}
			case 0x18:{int clientId=memory.readLong(sp);cpu.a[7]+=4;Client c=clients.get(clientId);if(c==null){memory.writeLong(cpu.a[7],0);return true;}List<Integer> ids=new ArrayList<Integer>();for(Port p:c.ports)ids.add(p.id);memory.writeLong(cpu.a[7],idList(ids));return true;} // MIDIGetPorts
			case 0x1c:{int clientId=memory.readLong(sp+10),bufSize=(short)memory.readWord(sp+8),refNumPtr=memory.readLong(sp+4),init=memory.readLong(sp);cpu.a[7]+=14;result(cpu,addPort(tb,clientId,bufSize,refNumPtr,init));return true;}
			case 0x20:{int clientId=memory.readLong(sp+4),portId=memory.readLong(sp);cpu.a[7]+=8;Port p=port(clientId,portId);memory.writeLong(cpu.a[7],p==null?0:portInfo(p));return true;}
			case 0x24:case 0x28:case 0x2c:case 0x30:{int srcCl=memory.readLong(sp+12),srcPort=memory.readLong(sp+8),dstCl=memory.readLong(sp+4),dstPort=memory.readLong(sp);cpu.a[7]+=16;
				result(cpu,connect(tb,port(srcCl,srcPort),port(dstCl,dstPort),selector==0x24||selector==0x28,selector==0x24||selector==0x2c));return true;}
			case 0x34:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;if(p!=null){p.buffer.clear();p.buffered=0;}return true;} // MIDIFlush
			case 0x38:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;memory.writeLong(cpu.a[7],p==null?0:p.readHook);return true;}
			case 0x3c:{int refNum=(short)memory.readWord(sp+4),hook=memory.readLong(sp);cpu.a[7]+=6;Port p=portsByRefNum.get(refNum);if(p!=null)p.readHook=hook;return true;}
			case 0x40:{int clientId=memory.readLong(sp+8),portId=memory.readLong(sp+4),name=memory.readLong(sp);cpu.a[7]+=12;Port p=port(clientId,portId);tb.writePascal(name,p==null?"":p.name,31);return true;}
			case 0x44:{int clientId=memory.readLong(sp+8),portId=memory.readLong(sp+4),name=memory.readLong(sp);cpu.a[7]+=12;Port p=port(clientId,portId);if(p!=null){p.name=tb.readPascal(name);changed(p.client);}return true;}
			case 0x48:{int refNum=(short)memory.readWord(sp+12),time=memory.readLong(sp+8),period=memory.readLong(sp+4),proc=memory.readLong(sp);cpu.a[7]+=14;wakeUp(refNum,time,period,proc);return true;}
			case 0x4c:{int refNum=(short)memory.readWord(sp);cpu.a[7]+=2;Port p=portsByRefNum.get(refNum);if(p!=null&&!p.client.virtual)removePort(tb,p);return true;}
			case 0x50:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;memory.writeWord(cpu.a[7],p==null?0:p.timeSource!=null?SYNC_EXTERNAL:p.sync);return true;}
			case 0x54:{int refNum=(short)memory.readWord(sp+2),sync=(short)memory.readWord(sp);cpu.a[7]+=4;Port p=portsByRefNum.get(refNum);if(p!=null&&p.isTime()){rebase(p);p.sync=sync;}return true;}
			case 0x58:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;memory.writeLong(cpu.a[7],p==null?0:(int)currentTime(p));return true;}
			case 0x5c:{int refNum=(short)memory.readWord(sp+4),time=memory.readLong(sp);cpu.a[7]+=6;Port p=portsByRefNum.get(refNum);if(p!=null&&p.isTime()){p.value=time;p.valueNanos=System.nanoTime();}return true;}
			case 0x60:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;if(p!=null&&p.isTime()){rebase(p);p.running=true;}return true;}
			case 0x64:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;if(p!=null&&p.isTime()){rebase(p);p.running=false;}return true;}
			case 0x68:{int refNum=(short)memory.readWord(sp+4),offset=memory.readLong(sp);cpu.a[7]+=6;Port p=portsByRefNum.get(refNum);if(p!=null&&p.isInput())deliver(tb,p,offset,true);return true;} // MIDIPoll
			case 0x6c:{int refNum=(short)memory.readWord(sp+4),packet=memory.readLong(sp);cpu.a[7]+=6;result(cpu,writePacket(tb,portsByRefNum.get(refNum),packet));return true;}
			case 0x70:{int clientId=memory.readLong(sp);cpu.a[7]+=4;Client c=clients.get(clientId);boolean changed=c!=null&&c.seenWorld!=worldVersion;if(c!=null)c.seenWorld=worldVersion;tb.writeBoolean(cpu.a[7],changed);return true;}
			case 0x74:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;memory.writeLong(cpu.a[7],p==null?0:p.offsetTime);return true;}
			case 0x78:{int refNum=(short)memory.readWord(sp+4),offset=memory.readLong(sp);cpu.a[7]+=6;Port p=portsByRefNum.get(refNum);if(p!=null)p.offsetTime=offset;return true;}
			case 0x7c:{int src=(short)memory.readWord(sp+6),dst=(short)memory.readWord(sp+4),time=memory.readLong(sp);cpu.a[7]+=8;memory.writeLong(cpu.a[7],(int)convertTime(src,dst,time));return true;}
			case 0x80:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;memory.writeLong(cpu.a[7],p==null?0:p.refCon);return true;}
			case 0x84:{int refNum=(short)memory.readWord(sp+4),refCon=memory.readLong(sp);cpu.a[7]+=6;Port p=portsByRefNum.get(refNum);if(p!=null)p.refCon=refCon;return true;}
			case 0x88:{Client c=clients.get(memory.readLong(sp));cpu.a[7]+=4;memory.writeLong(cpu.a[7],c==null?0:c.refCon);return true;}
			case 0x8c:{int clientId=memory.readLong(sp+4),refCon=memory.readLong(sp);cpu.a[7]+=8;Client c=clients.get(clientId);if(c!=null)c.refCon=refCon;return true;}
			case 0x90:{Port p=portsByRefNum.get((int)(short)memory.readWord(sp));cpu.a[7]+=2;memory.writeWord(cpu.a[7],p==null?0:p.format);return true;}
			case 0x94:{int refNum=(short)memory.readWord(sp+2),format=(short)memory.readWord(sp);cpu.a[7]+=4;Port p=portsByRefNum.get(refNum);if(p!=null&&p.isTime()){long now=currentTime(p);p.format=format;p.value=now;p.valueNanos=System.nanoTime();}return true;}
			case 0x98:{int refNum=(short)memory.readWord(sp+6),rate=(short)memory.readWord(sp+4);cpu.a[7]+=8;Port p=portsByRefNum.get(refNum);if(p!=null&&p.isTime()){rebase(p);p.rate=Math.max(0,rate);}return true;} // MIDISetRunRate(refnum, rate, time): $100 = real time; applied now
			case 0x9c:{Client c=clients.get(memory.readLong(sp));cpu.a[7]+=4;memory.writeLong(cpu.a[7],c==null?0:c.icon);return true;} // MIDIGetClientIcon
			case 0xa4:cpu.a[7]+=2;memory.writeLong(cpu.a[7],0);return true; // MIDICallAddress: no routine to jump to
			case 0xa8:{int refNum=(short)memory.readWord(sp+8),proc=memory.readLong(sp+4),refCon=memory.readLong(sp);cpu.a[7]+=10;Port p=portsByRefNum.get(refNum);if(p!=null){p.connectionProc=proc;p.connectionRefCon=refCon;}return true;}
			case 0xac:{int refNum=(short)memory.readWord(sp+8),procPtr=memory.readLong(sp+4),refConPtr=memory.readLong(sp);cpu.a[7]+=10;Port p=portsByRefNum.get(refNum);if(procPtr!=0)memory.writeLong(procPtr,p==null?0:p.connectionProc);if(refConPtr!=0)memory.writeLong(refConPtr,p==null?0:p.connectionRefCon);return true;}
			case 0xb0:{int refNum=(short)memory.readWord(sp+4);cpu.a[7]+=6;Port p=portsByRefNum.get(refNum);if(p!=null)discardHeld(p);return true;} // MIDIDiscardPacket(refnum, packet): the packet the read hook kept
			case 0xb4:cpu.a[7]+=16;result(cpu,INVALID_CMD_ERR);return true; // MDVRSignIn: no driver-side API
			case 0xb8:cpu.a[7]+=4;return true;
			case 0xbc:cpu.a[7]+=4;memory.writeLong(cpu.a[7],0);return true; // MDVROpen
			case 0xc0:cpu.a[7]+=4;return true;case 0xc4:case 0xc8:cpu.a[7]+=8;return true;case 0xcc:cpu.a[7]+=4;return true;case 0xd0:cpu.a[7]+=10;return true;case 0xd4:cpu.a[7]+=6;return true;
			default:return false;
		}
	}
	private void result(M68kCpu cpu,int error) throws M68kCpuException {memory.writeWord(cpu.a[7],error);}
	private void changed(Client by){worldVersion++;if(by!=null)by.seenWorld=worldVersion;}

	/** A new MIDIIDList handle (numIDs, then the IDs) the caller disposes. */
	private int idList(List<Integer> ids) throws M68kCpuException {
		byte[] b=new byte[2+ids.size()*4];putWord(b,0,ids.size());for(int i=0;i<ids.size();i++)putLong(b,2+i*4,ids.get(i));
		return macApi.createHandle(b);
	}
	/** A new MIDIPortInfo handle: portType, timeBase (client, port), numConnects, the connected ports. */
	private int portInfo(Port p) throws M68kCpuException {
		List<Port> connections=p.isTime()?p.timeConnections:p.dataConnections;
		byte[] b=new byte[12+connections.size()*8];
		putWord(b,0,p.type);
		if(p.timeBase!=null){putLong(b,2,p.timeBase.client.id);putLong(b,6,p.timeBase.id);}
		putWord(b,10,connections.size());
		for(int i=0;i<connections.size();i++){putLong(b,12+i*8,connections.get(i).client.id);putLong(b,16+i*8,connections.get(i).id);}
		return macApi.createHandle(b);
	}
	private Port port(int clientId,int portId){Client c=clients.get(clientId);if(c==null)return null;for(Port p:c.ports)if(p.id==portId)return p;return null;}

	// ------------------------------------------------------------------ clients and ports

	private int signIn(Toolbox68k tb,int clientId,int refCon,int icon,int namePtr) throws M68kCpuException {
		if(clients.containsKey(clientId))return DUP_ID_ERR;
		String name=tb.readPascal(namePtr);if(name.length()>31)name=name.substring(0,31);
		Client c=new Client(clientId,name,false);c.refCon=refCon;c.icon=icon;clients.put(clientId,c);changed(c);
		if(Mac68kApi.TRACE)System.err.println("[midi] client "+fourcc(clientId)+" \""+name+"\" signed in");
		return NO_ERR;
	}
	private void signOut(int clientId){
		Client c=clients.get(clientId);if(c==null||c.virtual)return;
		Toolbox68k tb=macApi.application();
		for(Port p:new ArrayList<Port>(c.ports))try{removePort(tb,p);}catch(M68kException e){if(Mac68kApi.TRACE)System.err.println("[midi] port not removed: "+e);}
		clients.remove(clientId);changed(null);
		if(Mac68kApi.TRACE)System.err.println("[midi] client "+fourcc(clientId)+" signed out");
	}
	/** MIDIAddPort(clientID, bufSize, VAR refnum, init: MIDIPortParams). */
	private int addPort(Toolbox68k tb,int clientId,int bufSize,int refNumPtr,int init) throws M68kCpuException {
		Client c=clients.get(clientId);if(c==null||c.virtual)return NO_CLIENT_ERR;
		if(init==0)return INVALID_CMD_ERR;
		int portId=memory.readLong(init),type=(short)memory.readWord(init+4),timeBase=(short)memory.readWord(init+6),offset=memory.readLong(init+8),readHook=memory.readLong(init+12),refCon=memory.readLong(init+16);
		int sync=(short)memory.readWord(init+20),curTime=memory.readLong(init+22),format=(short)memory.readWord(init+26);
		String name=tb.readPascal(init+28);if(name.length()>31)name=name.substring(0,31);
		if(port(clientId,portId)!=null)return DUP_ID_ERR;
		Port p=new Port(c,portId,nextRefNum++,type,name);
		p.offsetTime=offset;p.readHook=readHook;p.refCon=refCon;p.bufferSize=bufSize>0?bufSize:DEFAULT_BUFFER;
		if(p.isTime()){p.sync=sync;p.format=format;p.value=curTime;p.valueNanos=System.nanoTime();}
		else if(timeBase!=0){Port t=portsByRefNum.get(timeBase);if(t!=null&&t.isTime())p.timeBase=t;}
		c.ports.add(p);portsByRefNum.put(p.refNum,p);
		if(refNumPtr!=0)memory.writeWord(refNumPtr,p.refNum);
		changed(c);
		if(Mac68kApi.TRACE)System.err.println("[midi] port "+fourcc(portId)+" \""+name+"\" type "+type+" of "+fourcc(clientId)+" -> refNum "+p.refNum+(p.isInput()?" readHook $"+Integer.toHexString(readHook)+" offset "+offset:p.isTime()?" format "+format+" sync "+sync:""));
		return NO_ERR;
	}
	private void removePort(Toolbox68k tb,Port p) throws M68kException {
		for(Port other:new ArrayList<Port>(p.dataConnections))connect(tb,p,other,true,false);
		for(Port other:new ArrayList<Port>(p.timeConnections))connect(tb,p,other,false,false);
		for(Port other:portsByRefNum.values())if(other.timeBase==p)other.timeBase=null;
		portsByRefNum.remove(p.refNum);p.client.ports.remove(p);
		if(p.scratch!=0){memory.free(p.scratch);p.scratch=0;}
		changed(p.client);
	}

	// ------------------------------------------------------------------ connections

	/**
	 * MIDIConnectData / MIDIUnConnectData ({@code data}) and MIDIConnectTime / MIDIUnConnectTime: a data connection joins
	 * an input port and an output port, given in either order; a time connection makes the second time port follow the first.
	 */
	private int connect(Toolbox68k tb,Port a,Port b,boolean data,boolean make) throws M68kException {
		if(a==null||b==null)return NO_PORT_ERR;
		if(data){
			if(!(a.isInput()&&b.isOutput()||a.isOutput()&&b.isInput()))return VCONNECT_ERR;
			if(make){if(a.dataConnections.contains(b))return NO_ERR;a.dataConnections.add(b);b.dataConnections.add(a);}
			else{if(!a.dataConnections.remove(b))return NO_CON_ERR;b.dataConnections.remove(a);}
		}else{
			if(!(a.isTime()&&b.isTime()))return VCONNECT_ERR;
			if(make){if(a.timeConnections.contains(b))return NO_ERR;a.timeConnections.add(b);b.timeConnections.add(a);b.value=currentTime(b);b.valueNanos=System.nanoTime();b.timeSource=a;}
			else{if(!a.timeConnections.remove(b))return NO_CON_ERR;b.timeConnections.remove(a);if(b.timeSource==a){b.value=currentTime(b);b.valueNanos=System.nanoTime();b.timeSource=null;}if(a.timeSource==b){a.value=currentTime(a);a.valueNanos=System.nanoTime();a.timeSource=null;}}
		}
		changed(null);
		if(Mac68kApi.TRACE)System.err.println("[midi] "+(make?"connect":"unconnect")+(data?" data ":" time ")+describe(a)+" - "+describe(b));
		if(tb!=null){notifyConnection(tb,a,b,make);notifyConnection(tb,b,a,make);}
		return NO_ERR;
	}
	/** pascal void connectionProc(short refnum, long refcon, short portType, OSType clientID, OSType portID, Boolean connect, short direction). */
	private void notifyConnection(Toolbox68k tb,Port p,Port other,boolean make) throws M68kException {
		if(p.connectionProc==0)return;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		try{cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushWord(p.refNum);cpu.pushLong(p.connectionRefCon);cpu.pushWord(other.type);cpu.pushLong(other.client.id);cpu.pushLong(other.id);cpu.pushWord(make?0x100:0);cpu.pushWord(p.isOutput()?1:0);cpu.callPascal(p.connectionProc,20);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}
	/** PatchBay's part for a program that left its ports unwired: the Modem Port, or the Printer Port when the client already has a port on the Modem Port. */
	private void autoConnect(Toolbox68k tb,Port p) throws M68kException {
		Client driver=clients.get(DRIVER_CLIENT);
		int wantA=p.isOutput()?PORT_A_OUT:PORT_A_IN,wantB=p.isOutput()?PORT_B_OUT:PORT_B_IN;
		Port a=port(DRIVER_CLIENT,wantA),b=port(DRIVER_CLIENT,wantB);
		boolean aTaken=false;for(Port q:p.client.ports)if(q!=p&&q.dataConnections.contains(a))aTaken=true;
		Port target=aTaken?b:a;
		if(driver!=null&&target!=null)connect(tb,p,target,true,true);
	}
	private String describe(Port p){return fourcc(p.client.id)+"/"+fourcc(p.id);}

	// ------------------------------------------------------------------ time bases

	/** A time port's clock: it runs from its last set value while started, internally synchronised and not following another port. */
	private long currentTime(Port p){
		if(p.timeSource!=null)return convertTime(p.timeSource.format,p.format,currentTime(p.timeSource));
		if(!p.running||p.sync!=SYNC_INTERNAL)return p.value;
		long elapsedMs=(System.nanoTime()-p.valueNanos)/1_000_000L;
		return p.value+elapsedMs*p.rate/256;
	}
	/** Fixes the clock's value at now, before a change of what makes it run. */
	private void rebase(Port p){p.value=currentTime(p);p.valueNanos=System.nanoTime();}
	/**
	 * MIDIConvertTime between the time formats: milliseconds, beats (left alone) and SMPTE - the "QF" formats count
	 * quarter frames in a long, the bit formats pack hours, minutes, seconds and frames into a byte each.
	 */
	static long convertTime(int src,int dst,long time){
		if(src==dst||src==FORMAT_BEATS||dst==FORMAT_BEATS)return time;
		long ms=src==FORMAT_MSEC?time:toMillis(src,time);
		return dst==FORMAT_MSEC?ms:fromMillis(dst,ms);
	}
	private static double fps(int format){switch(format){case 2:case 6:return 24;case 3:case 7:return 25;case 4:case 8:return 29.97;default:return 30;}}
	private static long toMillis(int format,long time){
		double fps=fps(format);
		if(format>=6)return Math.round(time/4.0/fps*1000.0); // quarter frames
		long h=(time>>>24)&0xff,m=(time>>>16)&0xff,s=(time>>>8)&0xff,f=time&0xff;
		return Math.round(((h*60+m)*60+s)*1000.0+f/fps*1000.0);
	}
	private static long fromMillis(int format,long ms){
		double fps=fps(format);
		if(format>=6)return Math.round(ms/1000.0*fps*4.0);
		long s=ms/1000,f=Math.round((ms%1000)/1000.0*fps);if(f>=Math.round(fps)){f=0;s++;}
		long h=s/3600,m=(s/60)%60;s%=60;
		return (h&0xff)<<24|(m&0xff)<<16|(s&0xff)<<8|(f&0xff);
	}
	/** MIDIWakeUp(refnum, time, period, timeProc): the proc is called when the port's clock reaches {@code time} (at once when it is already past), then every {@code period}; a nil proc cancels. */
	private void wakeUp(int refNum,int time,int period,int proc){
		Port p=portsByRefNum.get(refNum);if(p==null||!p.isTime())return;
		p.wakeProc=proc;p.wakeTime=time;p.wakePeriod=period;p.wakeArmed=proc!=0;
		if(Mac68kApi.TRACE)System.err.println("[midi] wake up "+describe(p)+(proc==0?" cancelled":" at "+time+" every "+period+" -> $"+Integer.toHexString(proc)));
		Toolbox68k tb=macApi.application();if(tb!=null)tb.wakeIdle();
	}

	// ------------------------------------------------------------------ packets

	/** MIDIWritePacket(refnum, packet): the packet goes to every port connected to this output port - the driver's ports send it to the host, other clients' ports buffer it (or read it at once when it is due). */
	private int writePacket(Toolbox68k tb,Port p,int packet) throws M68kException {
		if(p==null)return NO_PORT_ERR;
		if(packet==0||!p.isOutput())return INVALID_CMD_ERR;
		int flags=memory.readByte(packet),len=memory.readByte(packet+1),tStamp=memory.readLong(packet+2);
		int dataLength=Math.max(0,Math.min(len-PACKET_HEADER,PACKET_MAX_DATA));
		byte[] data=memory.readBytes(packet+PACKET_HEADER,dataLength);
		if((flags&FLAG_TYPE_MASK)==FLAG_MGR_TYPE)return NO_ERR; // a manager message, not MIDI
		if((flags&FLAG_STAMP_CURRENT)!=0&&p.timeBase!=null){tStamp=(int)currentTime(p.timeBase);flags&=~FLAG_STAMP_CURRENT;}
		if(p.dataConnections.isEmpty())autoConnect(tb,p);
		if(Mac68kApi.TRACE)System.err.println("[midi] write "+describe(p)+" flags $"+Integer.toHexString(flags)+" stamp "+tStamp+" "+MidiHost68k.hex(data)+" -> "+p.dataConnections.size()+" port(s)");
		for(Port dst:new ArrayList<Port>(p.dataConnections)){
			if(dst.hostPort>=0){outputStreams[dst.hostPort].feed(data,flags&FLAG_CONT_MASK);continue;}
			receive(tb,dst,new Packet(flags,tStamp,data),true);
		}
		return NO_ERR;
	}
	/** A packet arriving at an input port: stamped with the port's clock when it asked for the current time, read at once when due and {@code immediate}, else buffered. */
	private void receive(Toolbox68k tb,Port dst,Packet packet,boolean immediate) throws M68kException {
		if((packet.flags&FLAG_STAMP_CURRENT)!=0){packet.tStamp=dst.timeBase==null?0:(int)currentTime(dst.timeBase);packet.flags&=~FLAG_STAMP_CURRENT;}
		if(dst.buffered+packet.data.length+PACKET_HEADER>dst.bufferSize){Packet dropped=dst.buffer.pollFirst();if(dropped!=null)dst.buffered-=dropped.data.length+PACKET_HEADER;if(Mac68kApi.TRACE)System.err.println("[midi] input buffer of "+describe(dst)+" overflowed");}
		dst.buffer.addLast(packet);dst.buffered+=packet.data.length+PACKET_HEADER;
		if(immediate&&dst.readHook!=0&&!servicing&&!inHook)deliver(tb,dst,dst.offsetTime,false);
	}
	private boolean due(Port p,Packet packet,int offset){
		if(p.timeBase==null||offset==GET_EVERYTHING)return true;
		if(offset==GET_NOTHING)return false;
		return packet.tStamp-(int)currentTime(p.timeBase)<=offset;
	}
	/**
	 * Calls the port's read hook with each due packet - pascal short readHook(MIDIPacketPtr, long refCon) - and does what
	 * it answers: midiMorePacket removes the packet and goes on, midiNoMorePacket removes it and stops, midiKeepPacket
	 * leaves it in the buffer and goes on, midiHoldPacket leaves it and stops. {@code poll} is MIDIPoll's pass, which also
	 * re-offers the packets kept by an earlier pass.
	 */
	private void deliver(Toolbox68k tb,Port p,int offset,boolean poll) throws M68kException {
		if(p.readHook==0||p.buffer.isEmpty()||tb==null)return;
		if(poll)for(Packet packet:p.buffer)packet.held=false;
		if(p.scratch==0)p.scratch=memory.allocate(PACKET_HEADER+PACKET_MAX_DATA+1,2);
		if(inHook)return;
		M68kCpu cpu=tb.cpu;
		inHook=true;
		try{
			for(java.util.Iterator<Packet> it=p.buffer.iterator();it.hasNext();){
				Packet packet=it.next();
				if(packet.held||!due(p,packet,offset))continue;
				memory.writeByte(p.scratch,packet.flags);memory.writeByte(p.scratch+1,packet.data.length+PACKET_HEADER);memory.writeLong(p.scratch+2,packet.tStamp);memory.load(p.scratch+PACKET_HEADER,packet.data);
				int answer;
				int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
				try{cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushWord(0);cpu.pushLong(p.scratch);cpu.pushLong(p.refCon);cpu.callPascal(p.readHook,8);answer=(short)memory.readWord(cpu.a[7]);}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
				if(Mac68kApi.TRACE)System.err.println("[midi] read hook of "+describe(p)+" "+MidiHost68k.hex(packet.data)+" -> "+answer);
				if(answer==MORE_PACKET||answer==NO_MORE_PACKET){it.remove();p.buffered-=packet.data.length+PACKET_HEADER;}
				else packet.held=true;
				if(answer==NO_MORE_PACKET||answer==HOLD_PACKET)break;
				if(!portsByRefNum.containsKey(p.refNum))break; // the hook removed the port
			}
		}finally{inHook=false;}
	}
	private void discardHeld(Port p){for(java.util.Iterator<Packet> it=p.buffer.iterator();it.hasNext();){Packet packet=it.next();if(packet.held){it.remove();p.buffered-=packet.data.length+PACKET_HEADER;return;}}}

	// ------------------------------------------------------------------ the interrupt's work

	/** Whether anything here needs the VM's attention soon: the earliest wake-up or delivery, in nanoTime terms (Long.MAX_VALUE when nothing). */
	long nextDueNanos(){
		if(!hostInput.isEmpty())return 0;
		long next=Long.MAX_VALUE,now=System.nanoTime();
		for(Port p:portsByRefNum.values()){
			if(p.wakeArmed&&p.isTime()){long cur=currentTime(p);long remaining=p.wakeTime-cur;if(remaining<=0)return 0;if(p.running&&p.sync==SYNC_INTERNAL&&p.rate>0&&p.timeSource==null)next=Math.min(next,now+remaining*256/p.rate*1_000_000L);}
			if(p.isInput()&&p.readHook!=0&&p.timeBase!=null&&!p.buffer.isEmpty()&&p.offsetTime!=GET_NOTHING){Port t=p.timeBase;if(t.running&&t.sync==SYNC_INTERNAL&&t.rate>0&&t.timeSource==null)for(Packet packet:p.buffer){if(packet.held)continue;long remaining=packet.tStamp-currentTime(t)-(long)p.offsetTime;next=Math.min(next,remaining<=0?0:now+remaining*256/t.rate*1_000_000L);}}
		}
		return next;
	}
	/** Called at the Toolbox's safe points: MIDI from the host reaches the ports, wake-ups and due packets reach the program. */
	void service(Toolbox68k tb) throws M68kException {
		if(servicing||tb==null)return;
		if(!listening&&hasInputPorts()){MidiHost68k.addListener(hostListener);listening=true;}
		servicing=true;
		try{
			byte[] m;
			while((m=hostInput.poll())!=null){
				int hostPort=m[0];Port source=port(DRIVER_CLIENT,hostPort==MidiHost68k.MODEM?PORT_A_IN:PORT_B_IN);if(source==null)continue;
				if(source.dataConnections.isEmpty())for(Port p:new ArrayList<Port>(portsByRefNum.values()))if(p.isInput()&&!p.client.virtual&&p.dataConnections.isEmpty()&&p.readHook!=0)autoConnect(tb,p);
				if(Mac68kApi.TRACE&&!source.dataConnections.isEmpty())System.err.println("[midi] host input "+MidiHost68k.hex(java.util.Arrays.copyOfRange(m,1,m.length))+" -> "+source.dataConnections.size()+" port(s)");
				for(Port dst:new ArrayList<Port>(source.dataConnections)){
					int length=m.length-1,offset=1;
					if(length<=PACKET_MAX_DATA){receive(tb,dst,new Packet(FLAG_STAMP_CURRENT,0,java.util.Arrays.copyOfRange(m,1,m.length)),false);continue;}
					while(length>0){int n=Math.min(length,PACKET_MAX_DATA);int cont=offset==1?FLAG_START_CONT:n==length?FLAG_END_CONT:FLAG_MID_CONT;receive(tb,dst,new Packet(FLAG_STAMP_CURRENT|cont,0,java.util.Arrays.copyOfRange(m,offset,offset+n)),false);offset+=n;length-=n;}
				}
			}
			for(Port p:new ArrayList<Port>(portsByRefNum.values())){
				if(!p.wakeArmed||!p.isTime()||!portsByRefNum.containsKey(p.refNum))continue;
				long cur=currentTime(p);
				if(cur-p.wakeTime<0)continue;
				if(p.wakePeriod>0){p.wakeTime+=p.wakePeriod;if(p.wakeTime-cur<=0)p.wakeTime=cur+p.wakePeriod;}
				else p.wakeArmed=false;
				int proc=p.wakeProc;if(proc==0)continue;
				if(Mac68kApi.TRACE)System.err.println("[midi] wake up "+describe(p)+" at "+cur+" -> $"+Integer.toHexString(proc));
				M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;inHook=true;
				try{cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushLong((int)cur);cpu.pushLong(p.refCon);cpu.callPascal(proc,8);}
				finally{inHook=false;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
			}
			for(Port p:new ArrayList<Port>(portsByRefNum.values())){
				if(!p.isInput()||p.readHook==0||p.buffer.isEmpty()||!portsByRefNum.containsKey(p.refNum))continue;
				deliver(tb,p,p.offsetTime,false);
			}
		}finally{servicing=false;}
	}
	private boolean hasInputPorts(){for(Port p:portsByRefNum.values())if(p.isInput()&&!p.client.virtual)return true;return false;}

	/**
	 * The bytes a program writes to a driver port, made into whole messages for the host: running status is resolved,
	 * real-time bytes pass through the middle of anything, and a system exclusive message is collected until its EOX
	 * (across continued packets).
	 */
	static final class MidiStream {
		private final int hostPort;private int status;private final byte[] pending=new byte[3];private int pendingLength,needed;private boolean used;
		private java.io.ByteArrayOutputStream sysex;
		MidiStream(int hostPort){this.hostPort=hostPort;}
		void feed(byte[] data,int continuation){
			used=true;
			for(byte value:data){
				int b=value&0xff;
				if(b>=0xf8){MidiHost68k.send(hostPort,new byte[]{(byte)b});continue;}
				if(sysex!=null){
					if(b<0x80){sysex.write(b);continue;}
					sysex.write(0xf7);MidiHost68k.send(hostPort,sysex.toByteArray());sysex=null;
					if(b==0xf7)continue;
				}
				if(b==0xf0){sysex=new java.io.ByteArrayOutputStream();sysex.write(0xf0);status=0;continue;}
				if(b>=0x80){
					status=b;pendingLength=0;
					int high=b&0xf0;
					needed=high==0xc0||high==0xd0?1:b==0xf1||b==0xf3?1:b==0xf2?2:b>=0xf4?0:2;
					if(needed==0){MidiHost68k.send(hostPort,new byte[]{(byte)b});status=0;}
					continue;
				}
				if(status==0)continue; // a data byte with no status to go with it
				pending[pendingLength++]=(byte)b;
				if(pendingLength>=needed){byte[] m=new byte[1+needed];m[0]=(byte)status;System.arraycopy(pending,0,m,1,needed);MidiHost68k.send(hostPort,m);pendingLength=0;if(status>=0xf0)status=0;}
			}
		}
		/** Silence for a program that quit with notes sounding. */
		void allNotesOff(){if(!used)return;for(int channel=0;channel<16;channel++){MidiHost68k.send(hostPort,new byte[]{(byte)(0xb0|channel),123,0});MidiHost68k.send(hostPort,new byte[]{(byte)(0xb0|channel),120,0});}}
	}

	static String fourcc(int v){return ""+(char)((v>>>24)&0xff)+(char)((v>>>16)&0xff)+(char)((v>>>8)&0xff)+(char)(v&0xff);}
	private static void putWord(byte[] b,int at,int v){b[at]=(byte)(v>>>8);b[at+1]=(byte)v;}
	private static void putLong(byte[] b,int at,int v){putWord(b,at,v>>>16);putWord(b,at+2,v);}
	static String name(int selector){
		switch(selector){
			case 0x00:return "MIDIVersion";case 0x04:return "MIDISignIn";case 0x08:return "MIDISignOut";case 0x0c:return "MIDIGetClients";case 0x10:return "MIDIGetClientName";case 0x14:return "MIDISetClientName";
			case 0x18:return "MIDIGetPorts";case 0x1c:return "MIDIAddPort";case 0x20:return "MIDIGetPortInfo";case 0x24:return "MIDIConnectData";case 0x28:return "MIDIUnConnectData";case 0x2c:return "MIDIConnectTime";case 0x30:return "MIDIUnConnectTime";
			case 0x34:return "MIDIFlush";case 0x38:return "MIDIGetReadHook";case 0x3c:return "MIDISetReadHook";case 0x40:return "MIDIGetPortName";case 0x44:return "MIDISetPortName";case 0x48:return "MIDIWakeUp";case 0x4c:return "MIDIRemovePort";
			case 0x50:return "MIDIGetSync";case 0x54:return "MIDISetSync";case 0x58:return "MIDIGetCurTime";case 0x5c:return "MIDISetCurTime";case 0x60:return "MIDIStartTime";case 0x64:return "MIDIStopTime";case 0x68:return "MIDIPoll";case 0x6c:return "MIDIWritePacket";
			case 0x70:return "MIDIWorldChanged";case 0x74:return "MIDIGetOffsetTime";case 0x78:return "MIDISetOffsetTime";case 0x7c:return "MIDIConvertTime";case 0x80:return "MIDIGetRefCon";case 0x84:return "MIDISetRefCon";case 0x88:return "MIDIGetClRefCon";case 0x8c:return "MIDISetClRefCon";
			case 0x90:return "MIDIGetTCFormat";case 0x94:return "MIDISetTCFormat";case 0x98:return "MIDISetRunRate";case 0x9c:return "MIDIGetClientIcon";case 0xa4:return "MIDICallAddress";case 0xa8:return "MIDISetConnectionProc";case 0xac:return "MIDIGetConnectionProc";case 0xb0:return "MIDIDiscardPacket";
			default:return selector>=0xb4?"MDVR call $"+Integer.toHexString(selector):"MIDI selector $"+Integer.toHexString(selector);
		}
	}
}

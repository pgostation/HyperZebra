import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AppleTalk for hosted 68K programs: the .MPP driver (DDP sockets with the program's socket listeners, LAP protocol
 * handlers, NBP names, self-send, GetAppleTalkInfo), the .ATP driver (transactions with retries, exactly-once
 * responders) and a .XPP that answers the zone calls of a router-less LocalTalk, plus the low-memory globals the
 * MPW glue reads (PortBUse, SPConfig, ABusVars with the node/bridge/net numbers, LAPMgrPtr for the transition queue).
 * <p>
 * The link is LocalTalk over UDP (multicast 239.192.76.84 port 1954, each datagram a 4-byte sender tag followed by
 * the LLAP frame), the framing Mini vMac and the other emulators use, so a program here sees programs in other windows
 * of HyperZebra, in other HyperZebra processes and in emulators on the LAN. One {@link Hub} per process owns the
 * socket and delivers frames between the VMs of the process itself (a program's own frames come back only with
 * self-send on, which is the default as in System 7). -Dhz68k.appletalk=local keeps the traffic in the process,
 * off leaves AppleTalk switched off (IsMPPOpen false, the port unconfigured); the LAN is joined at a program's first
 * AppleTalk call, after the access dialog. Node numbers are acquired the LocalTalk way (lapENQ probes, lapACK on a
 * clash) among the user-node range 1-127; there is no router, so the network number is 0 and zone calls answer
 * noBridgeErr as a LocalTalk-only Mac's do.
 */
final class AppleTalk68k {
	static final int MPP_REF=-10,ATP_REF=-11,XPP_REF=-41,DSP_REF=-59;
	/** Low memory: PortBUse (bit 7 set = port B free, low nibble 1 = AppleTalk, bit 4 = ATP loaded, bit 5 = XPP loaded), SPConfig (port B: 1 = AppleTalk, 2 = serial), ABusVars, LAPMgrPtr. */
	static final int PORT_B_USE=0x291,SP_CONFIG=0x1fb,ABUS_VARS=0x2d8,LAP_MGR_PTR=0xb18;
	/** MPP variables as the glue reads them: sysLAPAddr at 0, the read-header area at 1 (LAP header, then the DDP header), sysABridge at $19, sysNetNum at $1A. */
	private static final int VARS_SIZE=64,RHA=1,SYS_A_BRIDGE=0x19,SYS_NET_NUM=0x1a,LAP_HEADER=3,DDP_LONG=13,DDP_SHORT=5;
	private static final int LAP_DDP_SHORT=1,LAP_DDP_LONG=2,LAP_ENQ=0x81,LAP_ACK=0x82;
	private static final int DDP_NBP=2,DDP_ATP=3,DDP_AEP=4,DDP_ADSP=7,NBP_SOCKET=2,AEP_SOCKET=4;
	static final int DDP_MAX_DATA=586,ATP_MAX_DATA=578;
	// results
	static final int NO_ERR=0,PARAM_ERR=-50,NOT_OPEN_ERR=-28,CONTROL_ERR=-17,DDP_SKT_ERR=-91,DDP_LEN_ERR=-92,NO_BRIDGE_ERR=-93,LAP_PROT_ERR=-94,PORT_NOT_CF=-98,
		NBP_BUFF_OVR=-1024,NBP_NO_CONFIRM=-1025,NBP_CONF_DIFF=-1026,NBP_DUPLICATE=-1027,NBP_NOT_FOUND=-1028,
		ASP_NO_SERVERS=-1069,ASP_PARAM_ERR=-1070,REQ_FAILED=-1096,TOO_MANY_SKTS=-1098,BAD_ATP_SKT=-1099,BAD_BUFF_NUM=-1100,CB_NOT_FOUND=-1102,REQ_ABORTED=-1105,SKT_CLOSED_ERR=-3109;
	/** udp (the default: the LAN), local (this process only) or off; the preference sets it unless -Dhz68k.appletalk does, for the programs launched next. */
	static final String UDP="udp",LOCAL="local",OFF="off";
	static volatile String mode=System.getProperty("hz68k.appletalk",UDP).toLowerCase(java.util.Locale.ROOT);
	private static final boolean TRACE=Mac68kApi.TRACE||Boolean.getBoolean("hz68k.traceatalk");
	private static final int ATP_XO=0x20,ATP_EOM=0x10,ATP_STS=0x08;
	private static final long XO_RELEASE_NANOS=30_000_000_000L;

	final Toolbox68k tb;
	final M68kMemory memory;
	final Adsp68k adsp;
	private int vars,lapMgr,readGates,dce;
	int node,net;
	private boolean enabled,selfSend=true,mppOpen,atpOpen,xppOpen,joined,shutDown;
	final Map<Integer,DdpSocket> sockets=new HashMap<Integer,DdpSocket>();
	private final Map<Integer,Integer> protocolHandlers=new HashMap<Integer,Integer>();
	private final List<Name> names=new ArrayList<Name>();
	private final List<NbpRequest> nbpRequests=new ArrayList<NbpRequest>();
	private final List<SendRequest> sendRequests=new ArrayList<SendRequest>();
	private final List<Responder> responders=new ArrayList<Responder>();
	private final List<Integer> transitionQueue=new ArrayList<Integer>();
	private final ArrayDeque<byte[]> inbound=new ArrayDeque<byte[]>();
	private int nextTid=1,nextNbpId=1;
	/** The packet being delivered to a listener: what ReadPacket/ReadRest still have to give. */
	private byte[] current;private int currentPos;

	static final class DdpSocket {final int number;int listener;AtpSocket atp;/** ADSP connection ends and listeners sharing this socket (Adsp68k). */int adspUsers;DdpSocket(int number){this.number=number;}}
	private static final class AtpSocket {int filterNet,filterNode,filterSocket;final ArrayDeque<Integer> getRequests=new ArrayDeque<Integer>();}
	private static final class Name {int nte,socket,enumerator;byte[] object,type,zone;}
	private static final class NbpRequest {static final int LOOKUP=0,REGISTER=1,CONFIRM=2;int kind,pb,id,count;long intervalNanos,nextSend;byte[] object,type,zone;boolean found;int confirmNet,confirmNode,confirmSocket,confirmedSocket;Name name;}
	private static final class SendRequest {int pb,tid,srcSocket,dstNet,dstNode,dstSocket,bitmap,numOfBuffs,retries,responses;boolean xo,temporary,sent;byte[] request;long timeoutNanos,due;}
	/** An exactly-once transaction answered (or being answered) at one of our ATP sockets: the responses are kept for retransmission until the release. */
	private static final class Responder {int socket,srcNet,srcNode,srcSocket,tid,pendingPb;boolean xo;final byte[][] responses=new byte[8][];long expires;}

	AppleTalk68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;adsp=new Adsp68k(this);}

	// ------------------------------------------------------------------ set-up

	/** Sets the globals up: an open .MPP with ATP and XPP loaded as on a System 7 Mac with AppleTalk active (or a free, unconfigured port B when AppleTalk is off). */
	void initializeLowMemory() throws M68kCpuException {
		if(vars==0){vars=memory.allocate(VARS_SIZE,4);memory.fill(vars,VARS_SIZE,0);}
		if(lapMgr==0){
			// LAPMgrPtr -> a block whose +2 is the LAP Manager entry: JSR LAPMgrCall(A1) with D0 = selector (23 LAPAddATQ, 24 LAPRmvATQ), A0 = the queue element
			int gate=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {lapManager(c);}});
			lapMgr=memory.allocate(8,2);memory.writeWord(lapMgr,0);memory.writeWord(lapMgr+2,0x4ef9);memory.writeLong(lapMgr+4,gate);
			// A4 of a socket listener / protocol handler: JSR (A4) = ReadPacket, JSR 2(A4) = ReadRest
			int readPacket=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {readPacket(c,false);}});
			int readRest=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {readPacket(c,true);}});
			readGates=memory.allocate(14,2);
			memory.writeWord(readGates,0x6006);memory.writeWord(readGates+2,0x4ef9);memory.writeLong(readGates+4,readRest);memory.writeWord(readGates+8,0x4ef9);memory.writeLong(readGates+10,readPacket);
			dce=memory.allocate(DeviceManager68k.DCE_SIZE,4);memory.fill(dce,DeviceManager68k.DCE_SIZE,0);memory.writeWord(dce+24,MPP_REF);
		}
		memory.writeLong(LAP_MGR_PTR,lapMgr);
		enabled=!mode.equals(OFF)&&!hyperCard();
		if(!enabled){memory.writeByte(PORT_B_USE,0x80);memory.writeByte(SP_CONFIG,0x02);memory.writeLong(ABUS_VARS,0);return;}
		memory.writeByte(SP_CONFIG,0x01);
		if(node==0)node=Hub.get().assignNode(this);
		mppOpen=atpOpen=xppOpen=true;
		writeVars();
	}
	private void writeVars() throws M68kCpuException {
		memory.writeByte(PORT_B_USE,mppOpen?0x01|(atpOpen?0x10:0)|(xppOpen?0x20:0):0x80);
		memory.writeLong(ABUS_VARS,mppOpen?vars:0);
		memory.writeByte(vars,node);memory.writeByte(vars+SYS_A_BRIDGE,0);memory.writeWord(vars+SYS_NET_NUM,net);
	}
	/**
	 * HyperCard (creator 'WILD') sees AppleTalk as off: nobody uses it there, and the network access dialog it would raise at
	 * start-up only puzzles. The VMs that run XCMDs for HyperZebra see it on (Rinaldi's ATalkZones, GetNetworkInfos, DeviceList and
	 * ServerVols answered "AppleTalk is inactive"): setting the globals up joins nothing, the LAN is joined - after the dialog - at
	 * an external's first AppleTalk call ({@link #ensureJoined}).
	 */
	private boolean hyperCard(){
		if(tb.app==null)return false;
		try{return tb.app.file!=null&&tb.files.fileCreator(tb.app.file)==Toolbox68k.HYPERCARD_CREATOR;}catch(RuntimeException e){return false;}
	}
	int node(){return node;}
	/** Whether AppleTalk is on for this program (decided when its globals were set up). */
	boolean isEnabled(){return enabled;}
	String owner(){return tb.name;}
	/** Node numbers taken by this VM's programs are not offered to another VM of the process. */
	private void setNode(int n){node=n;try{if(vars!=0)memory.writeByte(vars,n);}catch(M68kCpuException ignored){}}

	/** Joins the LAN (once, after the access dialog) at the first AppleTalk call; the process-wide hub is used either way. */
	private void ensureJoined(){
		if(joined)return;joined=true;
		if(mode.equals(UDP)&&Sandbox.allowNetwork(tb.name,Sandbox.NET_APPLETALK,Hub.GROUP+":"+Hub.PORT,0))Hub.get().joinLan();
		int probed=Hub.get().probe(this,node);if(probed!=node)setNode(probed);
	}

	void shutDown(){shutDown=true;adsp.closeAll();Hub.get().leave(this);}

	// ------------------------------------------------------------------ device calls

	/** Open of .MPP/.ATP/.XPP by name: returns the fixed refNum (portNotCf when AppleTalk is off). */
	boolean open(M68kCpu cpu,int pb,String name,boolean async) throws M68kException {
		int ref=name.equalsIgnoreCase(".MPP")?MPP_REF:name.equalsIgnoreCase(".ATP")?ATP_REF:name.equalsIgnoreCase(".DSP")?DSP_REF:XPP_REF;
		if(!enabled){tb.files.finish(cpu,pb,PORT_NOT_CF,async);return true;}
		if(ref==MPP_REF)mppOpen=true;else if(ref==ATP_REF){mppOpen=true;atpOpen=true;}else if(ref==XPP_REF)xppOpen=true;
		writeVars();memory.writeWord(pb+24,ref);
		if(TRACE)System.err.println("[atalk] OpenDriver "+name+" -> "+ref+" node "+node);
		tb.files.finish(cpu,pb,NO_ERR,async);return true;
	}

	/** Close/Read/Write/Control/Status/KillIO on the three refNums (the glue uses them without an Open). */
	boolean handle(M68kCpu cpu,int pb,int trap,boolean async) throws M68kException {
		int ref=(short)memory.readWord(pb+24);
		if(!enabled){tb.files.finish(cpu,pb,NOT_OPEN_ERR,async);return true;}
		if(trap==0x01){ // Close: .MPP takes AppleTalk down (sockets, names, transactions), .ATP its sockets
			if(ref==MPP_REF){closeAll();mppOpen=false;atpOpen=false;}
			else if(ref==ATP_REF){closeAtp();atpOpen=false;}
			else if(ref==DSP_REF)adsp.closeAll();
			else xppOpen=false;
			writeVars();tb.files.finish(cpu,pb,NO_ERR,async);return true;
		}
		if(trap==0x06){tb.files.finish(cpu,pb,NO_ERR,async);return true;} // KillIO
		if(trap==0x02||trap==0x03){tb.files.finish(cpu,pb,trap==0x02?-19:-20,async);return true;}
		if(ref==MPP_REF&&!mppOpen||ref==ATP_REF&&!atpOpen){tb.files.finish(cpu,pb,NOT_OPEN_ERR,async);return true;}
		int csCode=(short)memory.readWord(pb+26);
		memory.writeWord(pb+16,1);cpu.d[0]&=0xffff0000; // in progress; an asynchronous call answers noErr at once
		if(TRACE)System.err.println("[atalk] "+(ref==MPP_REF?".MPP":ref==ATP_REF?".ATP":ref==DSP_REF?".DSP":".XPP")+(trap==0x05?" Status ":" Control ")+csCode+(async?" async":"")+" pb $"+Integer.toHexString(pb));
		ensureJoined();
		if(ref==XPP_REF)return xpp(pb,csCode,async);
		if(ref==DSP_REF)return adsp.control(pb,csCode,async);
		if(ref==ATP_REF)return atp(pb,csCode,async);
		switch(csCode){
			case 243:return writeLap(pb,async);
			case 244:{int type=memory.readByte(pb+28);if(protocolHandlers.remove(type)==null){finish(pb,LAP_PROT_ERR,async);return true;}finish(pb,NO_ERR,async);return true;}
			case 245:{int type=memory.readByte(pb+28),handler=memory.readLong(pb+30);if(type==LAP_DDP_SHORT||type==LAP_DDP_LONG||protocolHandlers.containsKey(type)||handler==0){finish(pb,LAP_PROT_ERR,async);return true;}protocolHandlers.put(type,handler);finish(pb,NO_ERR,async);return true;}
			case 246:return writeDdp(pb,async);
			case 247:{int s=memory.readByte(pb+28);DdpSocket d=sockets.get(s);if(d==null||d.atp!=null||d.adspUsers>0){finish(pb,DDP_SKT_ERR,async);return true;}sockets.remove(s);finish(pb,NO_ERR,async);return true;}
			case 248:{int s=memory.readByte(pb+28),listener=memory.readLong(pb+30);
				if(s==0)s=dynamicSocket();else if(s<1||s>254||sockets.containsKey(s))s=-1;
				if(s<0){finish(pb,DDP_SKT_ERR,async);return true;}
				DdpSocket d=new DdpSocket(s);d.listener=listener;sockets.put(s,d);memory.writeByte(pb+28,s);finish(pb,NO_ERR,async);return true;}
			case 249:case 255:case 257:case 259:finish(pb,NO_ERR,async);return true; // loadNBP / unloadNBP / SetMyZone / ATalkClosePrep
			case 250:return nbpConfirm(pb,async);
			case 251:return nbpLookup(pb,async);
			case 252:return nbpRemove(pb,async);
			case 253:return nbpRegister(pb,async);
			case 254:{int target=memory.readLong(pb+28);
				for(Iterator<NbpRequest> it=nbpRequests.iterator();it.hasNext();){NbpRequest r=it.next();if(r.pb==target){it.remove();complete(r.pb,REQ_ABORTED);finish(pb,NO_ERR,async);return true;}}
				finish(pb,CB_NOT_FOUND,async);return true;}
			case 256:{boolean old=selfSend;selfSend=memory.readByte(pb+28)!=0;memory.writeByte(pb+29,old?1:0);finish(pb,NO_ERR,async);return true;}
			case 258:return getInfo(pb,async);
			default:finish(pb,CONTROL_ERR,async);return true;
		}
	}

	void finish(int pb,int result,boolean async) throws M68kException {
		memory.writeWord(pb+16,result);tb.cpu.d[0]=(tb.cpu.d[0]&0xffff0000)|(result&0xffff);
		if(TRACE&&result!=NO_ERR)System.err.println("[atalk] csCode "+(short)memory.readWord(pb+26)+" -> "+result);
		if(async)tb.files.completion(tb.cpu,pb);
	}
	/** Completes a call that was left pending: ioResult, then its completion routine. */
	private void complete(int pb,int result){
		try{memory.writeWord(pb+16,result);if(TRACE)System.err.println("[atalk] completed csCode "+(short)memory.readWord(pb+26)+" pb $"+Integer.toHexString(pb)+" -> "+result);tb.files.completion(tb.cpu,pb);}
		catch(M68kException e){throw Toolbox68k.wrap(e);}
	}
	/** Blocks a synchronous call until {@code done[0]}: the link and the timers are kept running from here. */
	void waitFor(int pb,boolean[] done) throws M68kException {
		while(!done[0]&&!shutDown){
			pump();service();tb.runDeferred();tb.runTimers();
			if(done[0])break;
			if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			synchronized(inbound){if(inbound.isEmpty()){try{inbound.wait(5);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}}
		}
	}
	/** A pending request finishes through this: an asynchronous one runs its completion, a synchronous one is released from waitFor. */
	void settle(int pb,int result,boolean[] sync){
		if(sync!=null){try{memory.writeWord(pb+16,result);}catch(M68kCpuException ignored){}sync[0]=true;}
		else complete(pb,result);
	}

	int dynamicSocket(){for(int s=128;s<=254;s++)if(!sockets.containsKey(s))return s;return -1;}

	// ------------------------------------------------------------------ LAP / DDP

	/** The data entries of a WDS after its first (the header entry, whose length the caller need not fill in). */
	private byte[] readWds(int wds) throws M68kCpuException {
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(wds+=6;;wds+=6){int length=memory.readWord(wds);if(length==0)break;int ptr=memory.readLong(wds+2);out.write(memory.readBytes(ptr,length),0,length);}
		return out.toByteArray();
	}
	/** writeLAP: the WDS's first entry is the 3-byte LAP header (destination and type filled in by the caller). */
	private boolean writeLap(int pb,boolean async) throws M68kException {
		int wds=memory.readLong(pb+30);if(wds==0){finish(pb,PARAM_ERR,async);return true;}
		int header=memory.readLong(wds+2);
		byte[] data=readWds(wds);
		if(data.length>600){finish(pb,DDP_LEN_ERR,async);return true;}
		byte[] frame=new byte[LAP_HEADER+data.length];
		frame[0]=(byte)memory.readByte(header);frame[1]=(byte)node;frame[2]=(byte)memory.readByte(header+2);
		System.arraycopy(data,0,frame,LAP_HEADER,data.length);
		Hub.get().send(this,frame);finish(pb,NO_ERR,async);return true;
	}
	/** writeDDP: the WDS's first entry is the 16-byte header buffer the caller (BuildDDPwds) filled with the destination and type. */
	private boolean writeDdp(int pb,boolean async) throws M68kException {
		int socket=memory.readByte(pb+28),wds=memory.readLong(pb+30);
		if(wds==0){finish(pb,PARAM_ERR,async);return true;}
		if(!sockets.containsKey(socket)){finish(pb,DDP_SKT_ERR,async);return true;}
		int header=memory.readLong(wds+2);
		int dstNet=memory.readWord(header+7),dstNode=memory.readByte(header+11),dstSocket=memory.readByte(header+13),type=memory.readByte(header+15);
		byte[] data=readWds(wds);
		if(data.length>DDP_MAX_DATA){finish(pb,DDP_LEN_ERR,async);return true;}
		finish(pb,send(dstNet,dstNode,dstSocket,socket,type,data),async);return true;
	}

	/** Sends a DDP datagram: a short header on this network, a long one for other networks (which need a router we do not have). */
	int send(int dstNet,int dstNode,int dstSocket,int srcSocket,int type,byte[] data){
		if(dstNet!=0&&dstNet!=net)return NO_BRIDGE_ERR;
		if(data.length>DDP_MAX_DATA)return DDP_LEN_ERR;
		byte[] frame=new byte[LAP_HEADER+DDP_SHORT+data.length];
		int length=DDP_SHORT+data.length;
		frame[0]=(byte)dstNode;frame[1]=(byte)node;frame[2]=LAP_DDP_SHORT;
		frame[3]=(byte)(length>>>8&3);frame[4]=(byte)length;frame[5]=(byte)dstSocket;frame[6]=(byte)srcSocket;frame[7]=(byte)type;
		System.arraycopy(data,0,frame,LAP_HEADER+DDP_SHORT,data.length);
		if(TRACE)System.err.println("[atalk] node "+node+" send DDP type "+type+" "+srcSocket+" -> "+dstNode+":"+dstSocket+" ("+data.length+" bytes)");
		Hub.get().send(this,frame);
		return NO_ERR;
	}

	/** A frame from the link (any thread): queued for the emulation thread. */
	void receive(byte[] frame){
		synchronized(inbound){boolean idle=inbound.isEmpty();inbound.add(frame);inbound.notifyAll();if(idle)tb.later(new Runnable(){@Override public void run(){try{pump();}catch(M68kException e){throw Toolbox68k.wrap(e);}}});}
	}
	/** Delivers the queued frames (emulation thread; re-entrant, as a listener may wait on a call of its own). */
	private void pump() throws M68kException {
		while(!shutDown){
			byte[] frame;
			synchronized(inbound){frame=inbound.poll();}
			if(frame==null)return;
			try{deliver(frame);}
			catch(RuntimeException e){Throwable c=e.getCause();if(c instanceof M68kException)throw (M68kException)c;throw e;}
		}
	}
	private void deliver(byte[] f) throws M68kException {
		if(f.length<LAP_HEADER||shutDown)return;
		int lapDst=f[0]&0xff,lapSrc=f[1]&0xff,lapType=f[2]&0xff;
		if(lapType!=LAP_DDP_SHORT&&lapType!=LAP_DDP_LONG){
			Integer handler=protocolHandlers.get(lapType);
			if(handler!=null)callHandler(handler,f,LAP_HEADER,f.length-LAP_HEADER,lapDst,lapSrc,lapType,-1,null);
			return;
		}
		int srcNet=0,dstNet=0,srcNode=lapSrc,dstNode=lapDst,srcSocket,dstSocket,type,headerLength,length;
		if(lapType==LAP_DDP_SHORT){
			if(f.length<LAP_HEADER+DDP_SHORT)return;
			length=(f[3]&3)<<8|f[4]&0xff;dstSocket=f[5]&0xff;srcSocket=f[6]&0xff;type=f[7]&0xff;headerLength=DDP_SHORT;
		}else{
			if(f.length<LAP_HEADER+DDP_LONG)return;
			length=(f[3]&3)<<8|f[4]&0xff;dstNet=(f[7]&0xff)<<8|f[8]&0xff;srcNet=(f[9]&0xff)<<8|f[10]&0xff;dstNode=f[11]&0xff;srcNode=f[12]&0xff;dstSocket=f[13]&0xff;srcSocket=f[14]&0xff;type=f[15]&0xff;headerLength=DDP_LONG;
			if(dstNet!=0&&dstNet!=net)return;
		}
		int dataLength=Math.max(0,Math.min(length-headerLength,f.length-LAP_HEADER-headerLength));
		byte[] data=new byte[dataLength];System.arraycopy(f,LAP_HEADER+headerLength,data,0,dataLength);
		if(TRACE)System.err.println("[atalk] node "+node+" got DDP type "+type+" from "+srcNode+":"+srcSocket+" to socket "+dstSocket+" ("+dataLength+" bytes)");
		if(dstSocket==NBP_SOCKET&&type==DDP_NBP){nbpReceive(srcNet,srcNode,srcSocket,data);return;}
		if(dstSocket==AEP_SOCKET&&type==DDP_AEP){if(data.length>0&&data[0]==1){byte[] reply=data.clone();reply[0]=2;send(srcNet,srcNode,srcSocket,AEP_SOCKET,DDP_AEP,reply);}return;}
		DdpSocket s=sockets.get(dstSocket);
		if(s==null)return;
		if(s.atp!=null){if(type==DDP_ATP)atpReceive(s,srcNet,srcNode,srcSocket,data);return;}
		if(s.adspUsers>0&&type==DDP_ADSP){adsp.receive(dstSocket,srcNet,srcNode,srcSocket,data);return;}
		if(s.listener==0)return;
		// The listener sees a long header in the read-header area whatever the wire form was
		byte[] header=new byte[DDP_LONG];int total=DDP_LONG+dataLength;
		header[0]=(byte)(total>>>8&3);header[1]=(byte)total;header[4]=(byte)(dstNet>>>8);header[5]=(byte)dstNet;header[6]=(byte)(srcNet>>>8);header[7]=(byte)srcNet;
		header[8]=(byte)dstNode;header[9]=(byte)srcNode;header[10]=(byte)dstSocket;header[11]=(byte)srcSocket;header[12]=(byte)type;
		callHandler(s.listener,data,0,dataLength,lapDst,lapSrc,LAP_DDP_LONG,dstSocket,header);
	}
	/**
	 * Calls a socket listener or protocol handler the way MPP does: A2 = the MPP variables, A3 = the next free byte of the read-header
	 * area (after the LAP header, and the DDP header for a listener), A4 = ReadPacket/ReadRest, D0 = the destination socket,
	 * D1 = the bytes still to be read; the routine pulls the data through JSR (A4) / JSR 2(A4).
	 */
	private void callHandler(int routine,byte[] data,int offset,int length,int lapDst,int lapSrc,int lapType,int socket,byte[] ddpHeader) throws M68kException {
		M68kCpu cpu=tb.cpu;
		memory.writeByte(vars+RHA,lapDst);memory.writeByte(vars+RHA+1,lapSrc);memory.writeByte(vars+RHA+2,lapType);
		int a3=vars+RHA+LAP_HEADER;
		if(ddpHeader!=null){memory.load(a3,ddpHeader);a3+=DDP_LONG;}
		byte[] savedCurrent=current;int savedPos=currentPos;
		current=new byte[length];System.arraycopy(data,offset,current,0,length);currentPos=0;
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		try{
			cpu.a[0]=0;cpu.a[1]=0;cpu.a[2]=vars;cpu.a[3]=a3;cpu.a[4]=readGates;
			cpu.d[0]=socket&0xff;cpu.d[1]=length&0xffff;cpu.d[2]=0;cpu.d[3]=0;
			cpu.call(routine);
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;current=savedCurrent;currentPos=savedPos;}
	}
	/** ReadPacket (D3 bytes to (A3), Z clear when the packet is shorter) and ReadRest (the rest into a D3-byte buffer; D3 = the room left, negative when the packet was longer). */
	private void readPacket(M68kCpu cpu,boolean rest) throws M68kCpuException {
		int remaining=current==null?0:current.length-currentPos;
		int n=rest?(short)cpu.d[3]:cpu.d[3]&0xffff;
		if(rest){
			int take=Math.max(0,Math.min(n,remaining));
			if(take>0)memory.load(cpu.a[3],current,currentPos,take);
			cpu.a[3]+=take;currentPos=current==null?0:current.length;
			cpu.d[3]=(cpu.d[3]&0xffff0000)|((n-remaining)&0xffff);
			cpu.sr|=M68kCpu.FLAG_Z;
		}else{
			if(n>remaining){cpu.sr&=~M68kCpu.FLAG_Z;return;}
			if(n>0)memory.load(cpu.a[3],current,currentPos,n);
			cpu.a[3]+=n;currentPos+=n;
			cpu.sr|=M68kCpu.FLAG_Z;
		}
	}

	/** The LAP Manager's entry: D0 = selector, A0 = an AppleTalk transition queue element for LAPAddATQ (23) / LAPRmvATQ (24). */
	private void lapManager(M68kCpu cpu){
		int selector=cpu.d[0]&0xffff;
		if(selector==23){if(!transitionQueue.contains(cpu.a[0]))transitionQueue.add(cpu.a[0]);cpu.d[0]=NO_ERR;}
		else if(selector==24){cpu.d[0]=transitionQueue.remove((Integer)cpu.a[0])?NO_ERR:PARAM_ERR;}
		else cpu.d[0]=PARAM_ERR;
	}

	private boolean getInfo(int pb,boolean async) throws M68kException {
		memory.writeWord(pb+28,1);memory.writeLong(pb+30,vars);memory.writeLong(pb+34,dce);memory.writeWord(pb+38,0);
		memory.writeLong(pb+40,0);memory.writeWord(pb+44,selfSend?1:0);memory.writeWord(pb+46,0);memory.writeWord(pb+48,0);
		memory.writeLong(pb+50,(net&0xffff)<<8|node);memory.writeLong(pb+54,0);memory.writeWord(pb+58,8);memory.writeWord(pb+60,12);memory.writeWord(pb+62,12);
		memory.writeLong(pb+64,0);memory.writeWord(pb+68,1);
		int linkAddr=memory.readLong(pb+70);if(linkAddr!=0)memory.writeByte(linkAddr,node);
		int zone=memory.readLong(pb+74);if(zone!=0){memory.writeByte(zone,1);memory.writeByte(zone+1,'*');}
		finish(pb,NO_ERR,async);return true;
	}

	// ------------------------------------------------------------------ NBP

	/** Three packed Pascal strings (object, type, zone) at {@code p}. */
	private byte[][] readEntity(int p) throws M68kCpuException {
		byte[][] e=new byte[3][];
		for(int i=0;i<3;i++){int n=memory.readByte(p);e[i]=memory.readBytes(p+1,Math.min(n,32));p+=1+n;}
		return e;
	}
	private static boolean sameName(byte[] a,byte[] b){
		if(a.length!=b.length)return false;
		for(int i=0;i<a.length;i++)if(fold(a[i])!=fold(b[i]))return false;
		return true;
	}
	private static int fold(byte b){int c=b&0xff;return c>='A'&&c<='Z'?c+32:c;}
	/** NBP matching: "=" is any name, "≈" (Mac Roman $C5) a wildcard inside one, the rest a case-insensitive comparison. */
	private static boolean matches(byte[] pattern,byte[] name){
		if(pattern.length==1&&pattern[0]=='=')return true;
		int wild=-1;for(int i=0;i<pattern.length;i++)if((pattern[i]&0xff)==0xc5){wild=i;break;}
		if(wild<0)return sameName(pattern,name);
		byte[] head=java.util.Arrays.copyOfRange(pattern,0,wild),tail=java.util.Arrays.copyOfRange(pattern,wild+1,pattern.length);
		if(name.length<head.length+tail.length)return false;
		return sameName(head,java.util.Arrays.copyOfRange(name,0,head.length))&&sameName(tail,java.util.Arrays.copyOfRange(name,name.length-tail.length,name.length));
	}
	private static boolean zoneMatches(byte[] zone){return zone.length==0||zone.length==1&&zone[0]=='*';}

	private static void tuple(java.io.ByteArrayOutputStream out,int net,int node,int socket,int enumerator,byte[] object,byte[] type,byte[] zone){
		out.write(net>>>8);out.write(net);out.write(node);out.write(socket);out.write(enumerator);
		for(byte[] s:new byte[][]{object,type,zone}){out.write(s.length);out.write(s,0,s.length);}
	}
	private void nbpSend(int dstNet,int dstNode,int dstSocket,int function,int id,int net,int node,int socket,int enumerator,byte[] object,byte[] type,byte[] zone){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.write(function<<4|1);out.write(id);tuple(out,net,node,socket,enumerator,object,type,zone);
		send(dstNet,dstNode,dstSocket,NBP_SOCKET,DDP_NBP,out.toByteArray());
	}

	private void nbpReceive(int srcNet,int srcNode,int srcSocket,byte[] data){
		if(data.length<2)return;
		int function=(data[0]&0xff)>>>4,count=data[0]&15,id=data[1]&0xff,p=2;
		List<Object[]> tuples=new ArrayList<Object[]>();
		for(int i=0;i<count&&p+5<=data.length;i++){
			int tnet=(data[p]&0xff)<<8|data[p+1]&0xff,tnode=data[p+2]&0xff,tsocket=data[p+3]&0xff,tenum=data[p+4]&0xff;p+=5;
			byte[][] s=new byte[3][];
			for(int k=0;k<3;k++){if(p>=data.length)return;int n=data[p]&0xff;if(p+1+n>data.length)return;s[k]=java.util.Arrays.copyOfRange(data,p+1,p+1+n);p+=1+n;}
			tuples.add(new Object[]{tnet,tnode,tsocket,tenum,s[0],s[1],s[2]});
		}
		if(function==1||function==2){ // BrRq / LkUp: answer with our matching names
			for(Object[] t:tuples){
				if(!zoneMatches((byte[])t[6]))continue;
				java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int n=0;
				for(Name name:names){
					if(!matches((byte[])t[4],name.object)||!matches((byte[])t[5],name.type))continue;
					if(n==15||out.size()+5+3+name.object.length+name.type.length+name.zone.length+2>DDP_MAX_DATA){break;}
					tuple(out,net,node,name.socket,name.enumerator,name.object,name.type,name.zone);n++;
				}
				if(n==0)continue;
				byte[] body=out.toByteArray();byte[] reply=new byte[body.length+2];reply[0]=(byte)(3<<4|n);reply[1]=(byte)id;System.arraycopy(body,0,reply,2,body.length);
				send(srcNet,srcNode,srcSocket,NBP_SOCKET,DDP_NBP,reply);
			}
			return;
		}
		if(function!=3)return; // LkUp-Reply
		for(Iterator<NbpRequest> it=nbpRequests.iterator();it.hasNext();){
			NbpRequest r=it.next();
			if(r.id!=id)continue;
			try{
				for(Object[] t:tuples){
					if(!matches(r.object,(byte[])t[4])||!matches(r.type,(byte[])t[5]))continue;
					switch(r.kind){
						case NbpRequest.REGISTER:r.found=true;break;
						case NbpRequest.CONFIRM:if((Integer)t[1]==r.confirmNode){r.found=true;r.confirmedSocket=(Integer)t[2];}break;
						default:{
							int buffer=memory.readLong(r.pb+34),size=memory.readWord(r.pb+38),max=(short)memory.readWord(r.pb+40),got=memory.readWord(r.pb+42);
							if(got>=max)break;
							// one entry per address (the same reply may come back from a retry)
							int q=buffer;boolean seen=false;
							for(int i=0;i<got;i++){int enet=memory.readWord(q),enode=memory.readByte(q+2),esocket=memory.readByte(q+3);
								if(enet==(Integer)t[0]&&enode==(Integer)t[1]&&esocket==(Integer)t[2]&&sameName(memory.readBytes(q+6,memory.readByte(q+5)),(byte[])t[4]))seen=true;
								q+=5;for(int k=0;k<3;k++)q+=1+memory.readByte(q);}
							if(seen)break;
							java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
							tuple(out,(Integer)t[0],(Integer)t[1],(Integer)t[2],(Integer)t[3],(byte[])t[4],(byte[])t[5],(byte[])t[6]);
							if(q-buffer+out.size()>size){it.remove();memory.writeWord(r.pb+42,got);settle(r.pb,NBP_BUFF_OVR,syncOf(r.pb));return;}
							memory.load(q,out.toByteArray());memory.writeWord(r.pb+42,got+1);
							if(got+1>=max){it.remove();settle(r.pb,NO_ERR,syncOf(r.pb));return;}
						}
					}
				}
				if(r.found&&r.kind!=NbpRequest.LOOKUP){it.remove();finishNbp(r);return;}
			}catch(M68kCpuException e){throw Toolbox68k.wrap(e);}
		}
	}
	/** Synchronous NBP/ATP calls wait in {@link #waitFor}; their flag is found here by the parameter block. */
	final Map<Integer,boolean[]> syncFlags=new HashMap<Integer,boolean[]>();
	boolean[] syncOf(int pb){return syncFlags.remove(pb);}

	private NbpRequest nbpStart(int pb,int kind,byte[][] entity) throws M68kCpuException {
		NbpRequest r=new NbpRequest();r.kind=kind;r.pb=pb;r.id=nextNbpId;nextNbpId=nextNbpId%255+1;
		int interval=memory.readByte(pb+28),count=memory.readByte(pb+29);
		r.intervalNanos=Math.max(1,interval)*8L*Toolbox68k.TICK_NANOS;r.count=Math.max(1,count);
		r.object=entity[0];r.type=entity[1];r.zone=entity[2];r.nextSend=System.nanoTime();
		nbpRequests.add(r);return r;
	}
	private boolean nbpRun(int pb,NbpRequest r,boolean async) throws M68kException {
		if(async){service();return true;}
		boolean[] done={false};syncFlags.put(pb,done);
		service();waitFor(pb,done);
		syncFlags.remove(pb);tb.cpu.d[0]=(tb.cpu.d[0]&0xffff0000)|(memory.readWord(pb+16)&0xffff);return true;
	}
	private boolean nbpLookup(int pb,boolean async) throws M68kException {
		int entity=memory.readLong(pb+30);if(entity==0||memory.readLong(pb+34)==0){finish(pb,PARAM_ERR,async);return true;}
		memory.writeWord(pb+42,0);
		NbpRequest r=nbpStart(pb,NbpRequest.LOOKUP,readEntity(entity));
		return nbpRun(pb,r,async);
	}
	private boolean nbpRegister(int pb,boolean async) throws M68kException {
		int nte=memory.readLong(pb+30);if(nte==0){finish(pb,PARAM_ERR,async);return true;}
		byte[][] entity=readEntity(nte+9);
		if(entity[0].length==0||entity[1].length==0){finish(pb,PARAM_ERR,async);return true;}
		for(Name n:names)if(sameName(n.object,entity[0])&&sameName(n.type,entity[1])){finish(pb,NBP_DUPLICATE,async);return true;}
		Name name=new Name();name.nte=nte;name.object=entity[0];name.type=entity[1];name.zone=new byte[]{'*'};name.socket=memory.readByte(nte+7);name.enumerator=memory.readByte(nte+8);
		if(memory.readByte(pb+34)==0){addName(name);finish(pb,NO_ERR,async);return true;} // no verification asked for
		NbpRequest r=nbpStart(pb,NbpRequest.REGISTER,entity);r.name=name;
		return nbpRun(pb,r,async);
	}
	private void addName(Name name) throws M68kCpuException {
		names.add(name);
		memory.writeWord(name.nte+4,net);memory.writeByte(name.nte+6,node); // the entry's address is ours now
		if(TRACE)System.err.println("[atalk] node "+node+" registered "+new String(name.object,java.nio.charset.StandardCharsets.ISO_8859_1)+":"+new String(name.type,java.nio.charset.StandardCharsets.ISO_8859_1)+" at socket "+name.socket);
	}
	private boolean nbpRemove(int pb,boolean async) throws M68kException {
		int entity=memory.readLong(pb+30);if(entity==0){finish(pb,PARAM_ERR,async);return true;}
		byte[][] e=readEntity(entity);
		for(Iterator<Name> it=names.iterator();it.hasNext();){Name n=it.next();if(sameName(n.object,e[0])&&sameName(n.type,e[1])){it.remove();finish(pb,NO_ERR,async);return true;}}
		finish(pb,NBP_NOT_FOUND,async);return true;
	}
	private boolean nbpConfirm(int pb,boolean async) throws M68kException {
		int entity=memory.readLong(pb+30);if(entity==0){finish(pb,PARAM_ERR,async);return true;}
		NbpRequest r=nbpStart(pb,NbpRequest.CONFIRM,readEntity(entity));
		r.confirmNet=memory.readWord(pb+34);r.confirmNode=memory.readByte(pb+36);r.confirmSocket=memory.readByte(pb+37);
		return nbpRun(pb,r,async);
	}
	private void finishNbp(NbpRequest r){
		boolean[] sync=syncOf(r.pb);
		try{
			switch(r.kind){
				case NbpRequest.REGISTER:if(r.found)settle(r.pb,NBP_DUPLICATE,sync);else{addName(r.name);settle(r.pb,NO_ERR,sync);}break;
				case NbpRequest.CONFIRM:
					if(!r.found)settle(r.pb,NBP_NO_CONFIRM,sync);
					else{memory.writeByte(r.pb+38,r.confirmedSocket);settle(r.pb,r.confirmedSocket==r.confirmSocket?NO_ERR:NBP_CONF_DIFF,sync);}
					break;
				default:settle(r.pb,NO_ERR,sync);
			}
		}catch(M68kCpuException e){throw Toolbox68k.wrap(e);}
	}

	// ------------------------------------------------------------------ timers

	/** When the next retry is due (nanoTime), Long.MAX_VALUE for none: the idle loop wakes for it. */
	long nextDueNanos(){
		long due=Long.MAX_VALUE;
		for(NbpRequest r:nbpRequests)due=Math.min(due,r.nextSend);
		for(SendRequest r:sendRequests)due=Math.min(due,r.due);
		for(Responder r:responders)due=Math.min(due,r.expires);
		return Math.min(due,adsp.nextDueNanos());
	}
	/** Retries and time-outs, run from the Toolbox's timer pass and from synchronous waits. */
	void service() throws M68kException {
		if(nbpRequests.isEmpty()&&sendRequests.isEmpty()&&responders.isEmpty()&&!adsp.active())return;
		try{serviceNow();adsp.service();}
		catch(RuntimeException e){Throwable c=e.getCause();if(c instanceof M68kException)throw (M68kException)c;throw e;}
	}
	private void serviceNow(){
		long now=System.nanoTime();
		for(NbpRequest r:new ArrayList<NbpRequest>(nbpRequests)){
			if(r.nextSend>now||!nbpRequests.contains(r))continue;
			if(r.count==0){nbpRequests.remove(r);finishNbp(r);continue;}
			r.count--;r.nextSend=now+r.intervalNanos;
			if(r.kind==NbpRequest.CONFIRM)nbpSend(r.confirmNet,r.confirmNode,NBP_SOCKET,2,r.id,net,node,NBP_SOCKET,0,r.object,r.type,r.zone);
			else nbpSend(0,255,NBP_SOCKET,2,r.id,net,node,NBP_SOCKET,0,r.object,r.type,r.zone);
		}
		for(SendRequest r:new ArrayList<SendRequest>(sendRequests)){
			if(r.due>now||!sendRequests.contains(r))continue;
			if(r.sent){if(r.retries==0){endRequest(r,REQ_FAILED);continue;}if(r.retries!=255)r.retries--;}
			transmitRequest(r);
		}
		for(Responder r:new ArrayList<Responder>(responders))if(r.expires<=now)releaseResponder(r,NO_ERR);
	}

	// ------------------------------------------------------------------ ATP

	private DdpSocket atpSocketOf(int number){DdpSocket s=sockets.get(number);return s==null||s.atp==null?null:s;}

	private boolean atp(int pb,int csCode,boolean async) throws M68kException {
		switch(csCode){
			case 254:{ // openATPSkt
				int s=memory.readByte(pb+28);
				if(s==0)s=dynamicSocket();else if(s<1||s>254||sockets.containsKey(s))s=-1;
				if(s<0){finish(pb,TOO_MANY_SKTS,async);return true;}
				DdpSocket d=new DdpSocket(s);d.atp=new AtpSocket();d.atp.filterNet=memory.readWord(pb+30);d.atp.filterNode=memory.readByte(pb+32);d.atp.filterSocket=memory.readByte(pb+33);
				sockets.put(s,d);memory.writeByte(pb+28,s);finish(pb,NO_ERR,async);return true;}
			case 250:{ // closeATPSkt
				DdpSocket d=atpSocketOf(memory.readByte(pb+28));if(d==null){finish(pb,BAD_ATP_SKT,async);return true;}
				closeAtpSocket(d);finish(pb,NO_ERR,async);return true;}
			case 255:case 248:return sendRequest(pb,csCode==248,async);
			case 253:{ // getRequest
				DdpSocket d=atpSocketOf(memory.readByte(pb+28));if(d==null){finish(pb,BAD_ATP_SKT,async);return true;}
				d.atp.getRequests.add(pb);
				if(async)return true;
				boolean[] done={false};syncFlags.put(pb,done);waitFor(pb,done);syncFlags.remove(pb);
				tb.cpu.d[0]=(tb.cpu.d[0]&0xffff0000)|(memory.readWord(pb+16)&0xffff);return true;}
			case 252:return sendResponse(pb,async);
			case 251:return addResponse(pb,async);
			case 256:{ // relTCB: the requester gives up on a transaction
				int tid=memory.readWord(pb+46),dstNet=memory.readWord(pb+30),dstNode=memory.readByte(pb+32),dstSocket=memory.readByte(pb+33);
				for(SendRequest r:sendRequests)if(r.tid==tid&&r.dstNode==dstNode&&r.dstSocket==dstSocket&&r.dstNet==dstNet){endRequest(r,REQ_ABORTED);finish(pb,NO_ERR,async);return true;}
				finish(pb,CB_NOT_FOUND,async);return true;}
			case 249:{ // relRspCB: the responder drops an exactly-once transaction
				int socket=memory.readByte(pb+28),tid=memory.readWord(pb+46),srcNode=memory.readByte(pb+32),srcSocket=memory.readByte(pb+33);
				Responder r=responder(socket,srcNode,srcSocket,tid);
				if(r==null){finish(pb,CB_NOT_FOUND,async);return true;}
				releaseResponder(r,NO_ERR);finish(pb,NO_ERR,async);return true;}
			case 258:{int target=memory.readLong(pb+44);for(SendRequest r:sendRequests)if(r.pb==target){endRequest(r,REQ_ABORTED);finish(pb,NO_ERR,async);return true;}finish(pb,CB_NOT_FOUND,async);return true;} // killSendReq
			case 257:{int target=memory.readLong(pb+44); // killGetReq
				for(DdpSocket d:sockets.values())if(d.atp!=null&&d.atp.getRequests.remove((Integer)target)){settle(target,REQ_ABORTED,syncOf(target));finish(pb,NO_ERR,async);return true;}
				finish(pb,CB_NOT_FOUND,async);return true;}
			case 259:{DdpSocket d=atpSocketOf(memory.readByte(pb+28));if(d==null){finish(pb,BAD_ATP_SKT,async);return true;} // killAllGetReq
				Integer g;while((g=d.atp.getRequests.poll())!=null)settle(g,REQ_ABORTED,syncOf(g));
				finish(pb,NO_ERR,async);return true;}
			default:finish(pb,CONTROL_ERR,async);return true;
		}
	}
	private void closeAtpSocket(DdpSocket d){
		sockets.remove(d.number);
		Integer g;while((g=d.atp.getRequests.poll())!=null)settle(g,SKT_CLOSED_ERR,syncOf(g));
		for(SendRequest r:new ArrayList<SendRequest>(sendRequests))if(r.srcSocket==d.number)endRequest(r,SKT_CLOSED_ERR);
		for(Responder r:new ArrayList<Responder>(responders))if(r.socket==d.number)releaseResponder(r,SKT_CLOSED_ERR);
	}
	private void closeAtp(){for(DdpSocket d:new ArrayList<DdpSocket>(sockets.values()))if(d.atp!=null)closeAtpSocket(d);}
	private void closeAll(){
		adsp.closeAll();closeAtp();sockets.clear();names.clear();protocolHandlers.clear();
		for(NbpRequest r:new ArrayList<NbpRequest>(nbpRequests)){nbpRequests.remove(r);settle(r.pb,REQ_ABORTED,syncOf(r.pb));}
	}

	private boolean sendRequest(int pb,boolean fromSocket,boolean async) throws M68kException {
		SendRequest r=new SendRequest();r.pb=pb;
		if(fromSocket){DdpSocket d=atpSocketOf(memory.readByte(pb+28));if(d==null){finish(pb,BAD_ATP_SKT,async);return true;}r.srcSocket=d.number;}
		else{int s=dynamicSocket();if(s<0){finish(pb,TOO_MANY_SKTS,async);return true;}DdpSocket d=new DdpSocket(s);d.atp=new AtpSocket();sockets.put(s,d);r.srcSocket=s;r.temporary=true;}
		int flags=memory.readByte(pb+29);r.xo=(flags&ATP_XO)!=0;
		r.dstNet=memory.readWord(pb+30);r.dstNode=memory.readByte(pb+32);r.dstSocket=memory.readByte(pb+33);
		int length=memory.readWord(pb+34),data=memory.readLong(pb+36);
		if(length<0||length>ATP_MAX_DATA||memory.readLong(pb+40)==0){if(r.temporary)sockets.remove(r.srcSocket);finish(pb,length>ATP_MAX_DATA?DDP_LEN_ERR:PARAM_ERR,async);return true;}
		r.numOfBuffs=memory.readByte(pb+44);if(r.numOfBuffs<1||r.numOfBuffs>8){if(r.temporary)sockets.remove(r.srcSocket);finish(pb,BAD_BUFF_NUM,async);return true;}
		int timeout=memory.readByte(pb+45);r.timeoutNanos=Math.max(1,timeout)*1_000_000_000L;r.retries=memory.readByte(pb+47);
		r.tid=nextTid;nextTid=nextTid+1&0xffff;if(nextTid==0)nextTid=1;
		r.bitmap=(1<<r.numOfBuffs)-1;
		byte[] request=new byte[8+length];
		request[0]=(byte)(0x40|(r.xo?ATP_XO:0)|(flags&7));request[1]=(byte)r.bitmap;request[2]=(byte)(r.tid>>>8);request[3]=(byte)r.tid;
		int user=memory.readLong(pb+18);request[4]=(byte)(user>>>24);request[5]=(byte)(user>>>16);request[6]=(byte)(user>>>8);request[7]=(byte)user;
		if(length>0)System.arraycopy(memory.readBytes(data,length),0,request,8,length);
		r.request=request;memory.writeWord(pb+22,r.tid);memory.writeByte(pb+46,0);
		sendRequests.add(r);r.due=0;
		if(async){transmitRequest(r);return true;}
		boolean[] done={false};syncFlags.put(pb,done);
		transmitRequest(r);waitFor(pb,done);syncFlags.remove(pb);
		tb.cpu.d[0]=(tb.cpu.d[0]&0xffff0000)|(memory.readWord(pb+16)&0xffff);return true;
	}
	private void transmitRequest(SendRequest r){
		r.request[1]=(byte)r.bitmap;r.sent=true;r.due=System.nanoTime()+r.timeoutNanos;
		int result=send(r.dstNet,r.dstNode,r.dstSocket,r.srcSocket,DDP_ATP,r.request);
		if(result!=NO_ERR)endRequest(r,result);
	}
	private void endRequest(SendRequest r,int result){
		if(!sendRequests.remove(r))return;
		try{memory.writeByte(r.pb+46,r.responses);memory.writeByte(r.pb+28,r.bitmap);}catch(M68kCpuException ignored){}
		if(r.temporary)sockets.remove(r.srcSocket);
		settle(r.pb,result,syncOf(r.pb));
	}

	private Responder responder(int socket,int srcNode,int srcSocket,int tid){
		for(Responder r:responders)if(r.socket==socket&&r.srcNode==srcNode&&r.srcSocket==srcSocket&&r.tid==tid)return r;
		return null;
	}
	private void releaseResponder(Responder r,int result){
		if(!responders.remove(r))return;
		if(r.pendingPb!=0){int pb=r.pendingPb;r.pendingPb=0;settle(pb,result,syncOf(pb));}
	}
	private void transmitResponse(Responder r,int seq){
		byte[] p=r.responses[seq];if(p==null)return;
		send(r.srcNet,r.srcNode,r.srcSocket,r.socket,DDP_ATP,p);
	}
	private byte[] response(int seq,int tid,boolean eom,int userBytes,byte[] data){
		byte[] p=new byte[8+data.length];
		p[0]=(byte)(0x80|(eom?ATP_EOM:0));p[1]=(byte)seq;p[2]=(byte)(tid>>>8);p[3]=(byte)tid;
		p[4]=(byte)(userBytes>>>24);p[5]=(byte)(userBytes>>>16);p[6]=(byte)(userBytes>>>8);p[7]=(byte)userBytes;
		System.arraycopy(data,0,p,8,data.length);return p;
	}
	private boolean sendResponse(int pb,boolean async) throws M68kException {
		DdpSocket d=atpSocketOf(memory.readByte(pb+28));if(d==null){finish(pb,BAD_ATP_SKT,async);return true;}
		int flags=memory.readByte(pb+29),srcNet=memory.readWord(pb+30),srcNode=memory.readByte(pb+32),srcSocket=memory.readByte(pb+33);
		int bds=memory.readLong(pb+40),numOfBuffs=memory.readByte(pb+44),bdsSize=memory.readByte(pb+45),tid=memory.readWord(pb+46);
		if(bds==0||numOfBuffs<0||numOfBuffs>8){finish(pb,BAD_BUFF_NUM,async);return true;}
		Responder r=responder(d.number,srcNode,srcSocket,tid);
		boolean xo=(flags&ATP_XO)!=0||r!=null&&r.xo;
		if(r==null){r=new Responder();r.socket=d.number;r.srcNet=srcNet;r.srcNode=srcNode;r.srcSocket=srcSocket;r.tid=tid;r.xo=xo;responders.add(r);}
		r.expires=System.nanoTime()+XO_RELEASE_NANOS;
		for(int i=0;i<numOfBuffs;i++){
			// a response's length is buffSize (+0): dataSize (+6) is what a requester's BDS gets back (Apple's Respond() sets only buffSize)
			int e=bds+i*12,size=(short)memory.readWord(e),ptr=memory.readLong(e+2),user=memory.readLong(e+8);
			byte[] data=ptr==0||size<=0?new byte[0]:memory.readBytes(ptr,Math.min(size,ATP_MAX_DATA));
			boolean eom=(flags&ATP_EOM)!=0&&i==numOfBuffs-1;
			r.responses[i]=response(i,tid,eom,user,data);transmitResponse(r,i);
		}
		if(!xo){responders.remove(r);finish(pb,NO_ERR,async);return true;}
		if(async){r.pendingPb=pb;return true;}
		boolean[] done={false};syncFlags.put(pb,done);r.pendingPb=pb;waitFor(pb,done);syncFlags.remove(pb);
		tb.cpu.d[0]=(tb.cpu.d[0]&0xffff0000)|(memory.readWord(pb+16)&0xffff);return true;
	}
	private boolean addResponse(int pb,boolean async) throws M68kException {
		DdpSocket d=atpSocketOf(memory.readByte(pb+28));if(d==null){finish(pb,BAD_ATP_SKT,async);return true;}
		int flags=memory.readByte(pb+29),srcNet=memory.readWord(pb+30),srcNode=memory.readByte(pb+32),srcSocket=memory.readByte(pb+33);
		int length=memory.readWord(pb+34),ptr=memory.readLong(pb+36),seq=memory.readByte(pb+44),tid=memory.readWord(pb+46),user=memory.readLong(pb+18);
		if(seq<0||seq>7||length<0||length>ATP_MAX_DATA){finish(pb,BAD_BUFF_NUM,async);return true;}
		Responder r=responder(d.number,srcNode,srcSocket,tid);
		if(r==null){r=new Responder();r.socket=d.number;r.srcNet=srcNet;r.srcNode=srcNode;r.srcSocket=srcSocket;r.tid=tid;r.xo=(flags&ATP_XO)!=0;r.expires=System.nanoTime()+XO_RELEASE_NANOS;if(r.xo)responders.add(r);}
		r.responses[seq]=response(seq,tid,(flags&ATP_EOM)!=0,user,ptr==0||length==0?new byte[0]:memory.readBytes(ptr,length));
		transmitResponse(r,seq);finish(pb,NO_ERR,async);return true;
	}

	private void atpReceive(DdpSocket d,int srcNet,int srcNode,int srcSocket,byte[] p) throws M68kException {
		if(p.length<8)return;
		int control=p[0]&0xff,function=control>>>6,seqOrBitmap=p[1]&0xff,tid=(p[2]&0xff)<<8|p[3]&0xff,user=(p[4]&0xff)<<24|(p[5]&0xff)<<16|(p[6]&0xff)<<8|p[7]&0xff;
		int length=p.length-8;
		if(function==1){ // TReq
			AtpSocket a=d.atp;
			if(a.filterNode!=0&&a.filterNode!=srcNode||a.filterSocket!=0&&a.filterSocket!=srcSocket)return;
			boolean xo=(control&ATP_XO)!=0;
			Responder r=responder(d.number,srcNode,srcSocket,tid);
			if(r!=null){for(int i=0;i<8;i++)if((seqOrBitmap>>i&1)!=0)transmitResponse(r,i);return;} // a retransmitted exactly-once request: send the responses again
			Integer pb=a.getRequests.poll();
			if(pb==null)return; // nobody waiting: the requester will try again
			if(xo){r=new Responder();r.socket=d.number;r.srcNet=srcNet;r.srcNode=srcNode;r.srcSocket=srcSocket;r.tid=tid;r.xo=true;r.expires=System.nanoTime()+XO_RELEASE_NANOS;responders.add(r);}
			memory.writeByte(pb+29,(memory.readByte(pb+29)&~ATP_XO)|(xo?ATP_XO:0)|(control&7));
			memory.writeWord(pb+30,srcNet);memory.writeByte(pb+32,srcNode);memory.writeByte(pb+33,srcSocket);
			int room=memory.readWord(pb+34),ptr=memory.readLong(pb+36),copy=Math.min(room,length);
			if(ptr!=0&&copy>0)memory.load(ptr,p,8,copy);
			memory.writeWord(pb+34,copy);memory.writeLong(pb+18,user);memory.writeByte(pb+44,seqOrBitmap);memory.writeWord(pb+46,tid);
			settle(pb,NO_ERR,syncOf(pb));
			return;
		}
		if(function==2){ // TResp
			SendRequest r=null;
			for(SendRequest s:sendRequests)if(s.tid==tid&&s.srcSocket==d.number&&s.dstNode==srcNode&&s.dstSocket==srcSocket){r=s;break;}
			if(r==null||seqOrBitmap>=r.numOfBuffs||(r.bitmap>>seqOrBitmap&1)==0)return;
			int bds=memory.readLong(r.pb+40),e=bds+seqOrBitmap*12,size=(short)memory.readWord(e),ptr=memory.readLong(e+2);
			int copy=Math.max(0,Math.min(size,length));
			if(ptr!=0&&copy>0)memory.load(ptr,p,8,copy);
			memory.writeWord(e+6,copy);memory.writeLong(e+8,user);
			r.bitmap&=~(1<<seqOrBitmap);r.responses=Math.max(r.responses,seqOrBitmap+1);
			if((control&ATP_EOM)!=0)r.bitmap&=(1<<seqOrBitmap)-1;
			if(r.bitmap==0){
				if(r.xo){byte[] rel=new byte[8];rel[0]=(byte)0xc0;rel[2]=(byte)(tid>>>8);rel[3]=(byte)tid;send(r.dstNet,r.dstNode,r.dstSocket,r.srcSocket,DDP_ATP,rel);}
				endRequest(r,NO_ERR);
			}else if((control&ATP_STS)!=0)transmitRequest(r);
			return;
		}
		if(function==3){Responder r=responder(d.number,srcNode,srcSocket,tid);if(r!=null)releaseResponder(r,NO_ERR);} // TRel
	}

	// ------------------------------------------------------------------ XPP: ASP/AFP sessions have no servers, the zone calls no router

	private boolean xpp(int pb,int csCode,boolean async) throws M68kException {
		switch(csCode){
			case 246:{int sub=memory.readWord(pb+28);
				if(sub==5||sub==6||sub==7){memory.writeWord(pb+38,0);memory.writeByte(pb+40,1);finish(pb,NO_BRIDGE_ERR,async);return true;}
				finish(pb,ASP_PARAM_ERR,async);return true;}
			case 249:memory.writeWord(pb+28,ATP_MAX_DATA);memory.writeWord(pb+30,4624);memory.writeWord(pb+32,10);finish(pb,NO_ERR,async);return true;
			case 255:case 250:finish(pb,ASP_NO_SERVERS,async);return true;
			case 247:case 248:finish(pb,NO_ERR,async);return true;
			default:finish(pb,ASP_PARAM_ERR,async);return true;
		}
	}

	// ------------------------------------------------------------------ the link

	/**
	 * The process's LocalTalk segment: every VM's frames reach the other VMs here (self-send permitting) and, once the LAN is
	 * joined, the LocalTalk-over-UDP multicast group. Node numbers are unique among the VMs; a lapENQ probe finds clashes
	 * on the LAN, and ENQs from the LAN for our nodes are answered with lapACK.
	 */
	static final class Hub {
		static final String GROUP="239.192.76.84";static final int PORT=1954;
		private static Hub shared;
		static synchronized Hub get(){if(shared==null)shared=new Hub();return shared;}
		private final List<AppleTalk68k> members=new CopyOnWriteArrayList<AppleTalk68k>();
		private final byte[] tag=new byte[4];
		private DatagramChannel channel;private boolean lanTried,sendFailed;
		private InetAddress group;
		/** Set by the reader when a frame from the node being probed arrives. */
		private volatile int probing=-1;private volatile boolean probeHit;
		private final java.util.Random random=new java.util.Random();
		private Hub(){random.nextBytes(tag);}

		synchronized int assignNode(AppleTalk68k m){
			if(!members.contains(m))members.add(m);
			for(int tries=0;tries<200;tries++){int n=1+random.nextInt(127);if(!taken(n,m))return n;}
			return 1+random.nextInt(127);
		}
		private boolean taken(int n,AppleTalk68k except){for(AppleTalk68k o:members)if(o!=except&&o.node()==n)return true;return false;}
		void leave(AppleTalk68k m){members.remove(m);}

		/** Opens the multicast channel once (IPv4 only: a dual-stack socket cannot send to the group on macOS); without a usable interface the segment stays inside the process. */
		synchronized void joinLan(){
			if(lanTried)return;lanTried=true;
			try{
				group=InetAddress.getByName(GROUP);
				DatagramChannel c=DatagramChannel.open(StandardProtocolFamily.INET);
				c.setOption(StandardSocketOptions.SO_REUSEADDR,true);
				try{c.setOption(StandardSocketOptions.SO_REUSEPORT,true);}catch(Exception ignored){} // other HyperZebra processes on this computer share the port
				c.bind(new InetSocketAddress(PORT));
				c.setOption(StandardSocketOptions.IP_MULTICAST_LOOP,true);
				NetworkInterface send=null;int joinedCount=0;
				for(Enumeration<NetworkInterface> e=NetworkInterface.getNetworkInterfaces();e.hasMoreElements();){
					NetworkInterface ni=e.nextElement();
					try{
						if(!ni.isUp()||ni.isLoopback()||!ni.supportsMulticast())continue;
						boolean v4=false;for(Enumeration<InetAddress> a=ni.getInetAddresses();a.hasMoreElements();)if(a.nextElement().getAddress().length==4)v4=true;
						if(!v4)continue;
						c.join(group,ni);joinedCount++;
						if(send==null||ni.getName().equals("en0"))send=ni;
					}catch(Exception ex){if(TRACE)System.err.println("[atalk] "+ni.getName()+": "+ex);}
				}
				if(joinedCount==0){c.close();if(TRACE)System.err.println("[atalk] no multicast interface: AppleTalk stays inside this process");return;}
				if(send!=null)c.setOption(StandardSocketOptions.IP_MULTICAST_IF,send);
				channel=c;
				Thread reader=new Thread(this::read,"AppleTalk LToUDP");reader.setDaemon(true);reader.start();
				if(TRACE)System.err.println("[atalk] joined "+GROUP+":"+PORT+" on "+joinedCount+" interface(s), sending on "+(send==null?"default":send.getName()));
			}catch(Exception e){if(TRACE)System.err.println("[atalk] LToUDP unavailable: "+e);channel=null;}
		}

		/** LLAP node acquisition: ENQs for {@code candidate} on the LAN; another node's answer (or any of its frames) means trying another number. */
		synchronized int probe(AppleTalk68k m,int candidate){
			if(channel==null)return candidate;
			for(int attempt=0;attempt<16;attempt++){
				probeHit=false;probing=candidate;
				try{for(int i=0;i<4&&!probeHit;i++){transmit(new byte[]{(byte)candidate,(byte)candidate,(byte)LAP_ENQ});Thread.sleep(15);}}
				catch(InterruptedException e){Thread.currentThread().interrupt();break;}
				probing=-1;
				if(!probeHit)return candidate;
				if(TRACE)System.err.println("[atalk] node "+candidate+" is taken on the LAN");
				do{candidate=1+random.nextInt(127);}while(taken(candidate,m));
			}
			return candidate;
		}

		/** A frame from a member: to the other members it is addressed to (and itself with self-send) and to the LAN. */
		void send(AppleTalk68k from,byte[] frame){
			int dst=frame[0]&0xff;
			for(AppleTalk68k m:members){
				if(m==from&&!from.selfSend)continue;
				if(dst==255||dst==m.node())m.receive(frame);
			}
			transmit(frame);
		}
		private void transmit(byte[] frame){
			DatagramChannel c=channel;if(c==null)return;
			ByteBuffer d=ByteBuffer.allocate(4+frame.length);d.put(tag).put(frame).flip();
			try{c.send(d,new InetSocketAddress(group,PORT));}
			catch(Exception e){if(TRACE&&!sendFailed)System.err.println("[atalk] send: "+e+" (is HyperZebra allowed to use the local network?)");sendFailed=true;}
		}
		private void read(){
			ByteBuffer buf=ByteBuffer.allocate(2048);
			while(true){
				buf.clear();
				try{if(channel.receive(buf)==null)continue;}catch(Exception e){return;}
				int n=buf.position();if(n<4+LAP_HEADER)continue;
				boolean own=true;for(int i=0;i<4;i++)if(buf.get(i)!=tag[i])own=false;
				if(own)continue;
				byte[] frame=new byte[n-4];buf.position(4);buf.get(frame);
				int dst=frame[0]&0xff,src=frame[1]&0xff,type=frame[2]&0xff;
				if(src==probing)probeHit=true;
				if(type==LAP_ENQ){for(AppleTalk68k m:members)if(m.node()==dst){transmit(new byte[]{(byte)src,(byte)dst,(byte)LAP_ACK});break;}continue;}
				if(type==LAP_ACK)continue;
				for(AppleTalk68k m:members)if(dst==255||dst==m.node())m.receive(frame);
			}
		}
	}
}

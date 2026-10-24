import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Open Transport for hosted Mac programs: the client side of OT 1.3 / 2.x (endpoints with the XTI state machine, TCP and UDP
 * over the host's sockets, the Internet services provider with the DNR calls, an AppleTalk-less mapper, OTAlloc, the
 * utilities, deferred/system/timer tasks and the notifiers), independent of the processor the program runs on. The
 * bindings give it its callers: {@link PpcLibraries} serves the functions to PowerPC programs by name (OTClientLib,
 * OTInetClientLib, OTUtilityLib, OTClientUtilLib, CarbonLib's InContext forms), {@link OpenTransport68k} to 68K programs
 * through a stand-in for the Apple Shared Library Manager that the MPW static glue (OpenTransport.o) binds its stubs through.
 *
 * Notifiers run like deferred tasks on a Mac: at the emulator's scheduler polls ({@link Toolbox68k#later}), never inside
 * another OT call and never nested for the same provider (OTEnterNotifier holds them back as well). Synchronous blocking
 * calls wait in the host, running the VM's deferred work and timers and the provider's kOTSyncIdleEvent notifier meanwhile.
 * The socket threads only change the endpoint state (under its lock) and post events, so waits never depend on deferred work.
 *
 * All OT structures use 68K alignment (the headers say so for PowerPC too): one layout for both processors.
 */
final class OpenTransport {
	static final boolean TRACE=Boolean.getBoolean("hz68k.traceot");
	/** Whether programs are told Open Transport is there (Gestalt 'otan'): -Dhz68k.opentransport=off hides it (programs then use MacTCP). */
	static final boolean ENABLED=!"off".equalsIgnoreCase(System.getProperty("hz68k.opentransport","on"));

	// ------------------------------------------------------------------ constants (OpenTransport.h, OpenTransportProviders.h)

	static final int NO_ERR=0,BAD_ADDRESS=-3150,BAD_OPTION=-3151,ACCESS=-3152,BAD_REFERENCE=-3153,NO_ADDRESS=-3154,OUT_STATE=-3155,
		BAD_SEQUENCE=-3156,SYS_ERROR=-3157,LOOK=-3158,BAD_DATA=-3159,BUFFER_OVERFLOW=-3160,FLOW=-3161,NO_DATA=-3162,NO_DISCONNECT=-3163,
		NO_UDERR=-3164,BAD_FLAG=-3165,NO_RELEASE=-3166,NOT_SUPPORTED=-3167,STATE_CHANGE=-3168,NO_STRUCTURE_TYPE=-3169,BAD_NAME=-3170,
		BAD_QLEN=-3171,ADDRESS_BUSY=-3172,INDOUT=-3173,PROVIDER_MISMATCH=-3174,CANCELED=-3180,NOT_FOUND=-3201,OUT_OF_MEMORY=-3211,
		INVALID=-3221,TIMED_OUT=-3259,CLIENT_NOT_INITTED=-3279,BAD_CONFIGURATION=-3282,
		NO_SUCH_DEVICE=-3205 /* kENXIOErr */;
	/** Disconnect reasons (the E error numbers; TDiscon.reason). */
	static final int ECONNRESET=54,ETIMEDOUT=60,ECONNREFUSED=61,EHOSTUNREACH=65;

	static final int T_LISTEN=0x0001,T_CONNECT=0x0002,T_DATA=0x0004,T_EXDATA=0x0008,T_DISCONNECT=0x0010,T_UDERR=0x0040,T_ORDREL=0x0080,
		T_GODATA=0x0100,T_PASSCON=0x1000;
	static final int T_BINDCOMPLETE=0x20000001,T_UNBINDCOMPLETE=0x20000002,T_ACCEPTCOMPLETE=0x20000003,T_DISCONNECTCOMPLETE=0x20000005,
		T_OPTMGMTCOMPLETE=0x20000006,T_OPENCOMPLETE=0x20000007,T_GETPROTADDRCOMPLETE=0x20000008,T_RESOLVEADDRCOMPLETE=0x20000009,
		T_GETINFOCOMPLETE=0x2000000A,T_SYNCCOMPLETE=0x2000000B,T_MEMORYRELEASED=0x2000000C,T_REGNAMECOMPLETE=0x2000000D,
		T_DELNAMECOMPLETE=0x2000000E,T_LKUPNAMECOMPLETE=0x2000000F,SYNC_IDLE=0x20000011,
		T_DNRSTRINGTOADDRCOMPLETE=0x10000001,T_DNRADDRTONAMECOMPLETE=0x10000002,T_DNRSYSINFOCOMPLETE=0x10000003,
		T_DNRMAILEXCHANGECOMPLETE=0x10000004,T_DNRQUERYCOMPLETE=0x10000005;
	static final int T_UNINIT=0,T_UNBND=1,T_IDLE=2,T_OUTCON=3,T_INCON=4,T_DATAXFER=5,T_OUTREL=6,T_INREL=7;
	static final int T_MORE=0x0001,T_EXPEDITED=0x0002;
	static final int T_NEGOTIATE=0x0004,T_CHECK=0x0008,T_DEFAULT=0x0010,T_CURRENT=0x0080,
		T_SUCCESS=0x0020,T_FAILURE=0x0040,T_PARTSUCCESS=0x0100,T_READONLY=0x0200,T_NOTSUPPORT=0x0400;
	static final int T_INFINITE=-1,T_INVALID=-2,T_COTS_ORD=2,T_CLTS=3,T_XPG4_1=0x0002,T_CAN_RESOLVE_ADDR=0x40000000;
	static final int AF_INET=2,AF_DNS=42,AF_8022=0x2008;
	static final int XTI_GENERIC=0xffff,INET_IP=0,INET_TCP=6,INET_UDP=0x11;
	static final int XTI_LINGER=0x0080,XTI_RCVBUF=0x1002,XTI_RCVLOWAT=0x1004,XTI_SNDBUF=0x1001,XTI_SNDLOWAT=0x1003,OPT_KEEPALIVE=0x0008,
		TCP_NODELAY=0x01,TCP_MAXSEG=0x02,IP_REUSEADDR=0x04,IP_BROADCAST=0x20,IP_TTL=0x03,IP_REUSEPORT=0x0200;
	static final int NETBUF_IS_OT_DATA=0xfffffffe,NETBUF_IS_OT_BUFFER_STAR=0xfffffffd;
	static final int CONFIG_INVALID=-1,DEFAULT_INET_SERVICES=-3;
	/** OTAlloc structure types and fields. */
	static final int T_BIND=1,T_OPTMGMT=2,T_CALL=3,T_DIS=4,T_UNITDATA=5,T_UDERROR=6,T_INFO=7,T_ADDR=1,T_OPT=2,T_UDATA=4;
	static final int I_NREAD=('A'<<8)|1;

	/** Provider kinds (ENET: a link-level "enet" endpoint, bound only to learn the Ethernet address). */
	static final int TCP=1,UDP=2,INET_SERVICES=3,MAPPER=4,ENET=5;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	OpenTransport(Toolbox68k tb){this.tb=tb;this.memory=tb.memory;define();}

	// ------------------------------------------------------------------ callers

	/** How guest routines are run: a notifier, an OTProcessProcPtr, a list search routine. The 68K binding calls them as
	 *  Pascal routines, the PowerPC one through their TVectors (or Mixed Mode for routine descriptors). */
	interface Guest {
		void notify(int proc,int context,int code,int result,int cookie) throws M68kException;
		void process(int proc,int arg) throws M68kException;
		boolean listSearch(int proc,int ref,int link) throws M68kException;
		/** Processor name for OTInetSysInfo. */
		String cpuType();
	}
	private Guest guest;
	void setGuest(Guest g){guest=g;}
	/** The 68K caller, unless a PowerPC program's runtime (made before the 68K low memory is set up) installed its own. */
	void setDefaultGuest(Guest g){if(guest==null)guest=g;}

	/** A client call: arguments in the order of the C prototype, result as the function returns it. */
	interface Impl { int call(int[] a) throws M68kException; }
	/**
	 * A function and how its arguments look to a 68K Pascal caller: {@code result} 'v' (none), '4', '2' or 'B' (Boolean, the
	 * high byte of a word); {@code args} one size per argument ('4', '2', '1' = a byte parameter occupying a word, value in
	 * its high byte). C callers pass every argument as a long.
	 */
	static final class Fn {
		final String name;final char result;final String args;final Impl impl;
		Fn(String name,String sig,Impl impl){this.name=name;int c=sig.indexOf(':');result=sig.charAt(0);args=sig.substring(c+1);this.impl=impl;}
	}
	private final Map<String,Fn> functions=new HashMap<String,Fn>();
	/** The function called {@code name} in any capitalization (68K Pascal entry points are upper case), or null. */
	Fn function(String name){return functions.get(name.toUpperCase(Locale.ROOT));}
	private void def(String name,String sig,Impl impl){functions.put(name.toUpperCase(Locale.ROOT),new Fn(name,sig,impl));}
	private void alias(String name,String existing){Fn f=function(existing);functions.put(name.toUpperCase(Locale.ROOT),new Fn(name,f.result+":"+f.args,f.impl));}

	// ------------------------------------------------------------------ providers

	final class Provider {
		final int kind;int ref;
		boolean sync=true,blocking=false,/* OT default: synchronous, non-blocking (Descent 3 polls OTRcvUData without ever calling OTSetBlocking) */ ackSends,syncIdle,closed;
		int notifier,context;boolean inNotifier;int enterDepth;
		/** The code OTCancelSynchronousCalls asked a waiting call to return (0 = none). */
		volatile int cancel;
		// endpoint state
		int state=T_UNBND;int localIp,localPort,qlen,remoteIp,remotePort;
		Socket socket;ServerSocket server;DatagramSocket datagram;Socket prebound;
		final ByteQueue in=new ByteQueue();
		boolean dataNotified,peerClosed,disconnectPending,connectConfirmed,connecting,flowBlocked;int discReason,discSequence;
		/** An "enet" endpoint's bound T8022Address. */
		byte[] linkAddress;
		final ArrayDeque<Incoming> incoming=new ArrayDeque<Incoming>();
		final ArrayDeque<DatagramPacket> datagrams=new ArrayDeque<DatagramPacket>();
		int partialOffset; // bytes of the head datagram already handed out (T_MORE)
		Writer writer;
		final Map<Integer,Integer> options=new HashMap<Integer,Integer>();
		/** Held back events while the program is in its notifier (or between OTEnterNotifier and OTLeaveNotifier). */
		final ArrayDeque<int[]> held=new ArrayDeque<int[]>();
		Provider(int kind){this.kind=kind;}
		boolean endpoint(){return kind==TCP||kind==UDP;}
		String name(){switch(kind){case TCP:return "tcp";case UDP:return "udp";case INET_SERVICES:return "dnr";case ENET:return "enet";default:return "mapper";}}
	}
	/** A connection accepted by a listener, waiting for OTListen and OTAccept. */
	static final class Incoming { final Socket socket;final int sequence;boolean listened;Incoming(Socket s,int seq){socket=s;sequence=seq;} }
	private int nextSequence=1;

	private final Map<Integer,Provider> providers=new HashMap<Integer,Provider>();
	private final Object lock=new Object();
	private volatile boolean shutDown;
	private static final int REF_MAGIC=0x6f745072; // 'otPr'

	private Provider provider(int ref){synchronized(providers){Provider p=providers.get(ref);return p==null||p.closed?null:p;}}
	private int newRef(Provider p) throws M68kCpuException {
		int ref=memory.allocate(32,4);memory.fill(ref,32,0);memory.writeLong(ref,REF_MAGIC);memory.writeLong(ref+4,p.kind);
		p.ref=ref;synchronized(providers){providers.put(ref,p);}return ref;
	}

	/** Debug aid (the snapshot command "otstate"): the open providers, their endpoint state and the events not yet delivered. */
	String describe(){
		StringBuilder b=new StringBuilder();
		synchronized(providers){for(Provider p:providers.values())b.append(String.format("%n  %s $%x state %d notifier $%x%s%s held %d%s%s",p.name(),p.ref,p.state,p.notifier,
			p.sync?" sync":" async",p.inNotifier?" in-notifier":"",p.held.size(),p.enterDepth>0?" entered "+p.enterDepth:"",p.closed?" closed":""));}
		return b.append(String.format("%n  queued events %d, drain scheduled %b",events.size(),drainScheduled.get())).toString();
	}
	/** Wakes synchronous waits (a socket thread changed something). */
	private void wake(){synchronized(lock){lock.notifyAll();}}

	// ------------------------------------------------------------------ events

	private final ConcurrentLinkedQueue<int[]> events=new ConcurrentLinkedQueue<int[]>();
	private final Map<Integer,Provider> eventProviders=new HashMap<Integer,Provider>();
	private final AtomicBoolean drainScheduled=new AtomicBoolean();

	/** Queues a notifier call (delivered at the next scheduler poll). */
	private void post(Provider p,int code,int result,int cookie){
		if(TRACE)System.err.println("[ot] post "+eventName(code)+" to "+p.name()+" $"+Integer.toHexString(p.ref)+" result "+result+" cookie $"+Integer.toHexString(cookie));
		events.add(new int[]{p.ref,code,result,cookie});
		synchronized(eventProviders){eventProviders.put(p.ref,p);}
		if(drainScheduled.compareAndSet(false,true))tb.later(()->{try{drain();}catch(M68kException e){throw Toolbox68k.wrap(e);}});
		wake();
	}
	/**
	 * The completion of an asynchronous call that finished at once (unbinding, disconnecting): as Open Transport, the notifier
	 * runs before the call returns - unless the program is in its notifier or between OTEnterNotifier and OTLeaveNotifier, or
	 * the provider has events still queued (they keep their order), where it is queued like any other. GUSI's close (MacSSH)
	 * waits for T_UNBINDCOMPLETE in a loop that yields when the flag is not set yet, and its yield runs the deferred close of
	 * the same socket: the nested close disposed of the socket the outer one went on using (a jump to address 0).
	 */
	private void complete(Provider p,int code,int result,int cookie) throws M68kException {
		boolean queued=false;for(int[] e:events)if(e[0]==p.ref){queued=true;break;}
		if(queued||p.inNotifier||p.enterDepth>0||p.notifier==0||guest==null||p.closed){post(p,code,result,cookie);return;}
		if(TRACE)System.err.println("[ot] complete "+eventName(code)+" "+p.name()+" $"+Integer.toHexString(p.ref)+" result "+result+" at once");
		callNotifier(p,code,result,cookie);
	}
	private void drain() throws M68kException {
		drainScheduled.set(false);
		int[] e;
		while((e=events.poll())!=null){
			Provider p;synchronized(eventProviders){p=eventProviders.get(e[0]);}
			if(p==null)continue;
			deliver(p,e);
		}
	}
	private void deliver(Provider p,int[] e) throws M68kException {
		if(p.closed)return;
		if(p.notifier==0||guest==null){if(e[1]==T_DATA)synchronized(p){p.dataNotified=false;}return;}
		if(p.inNotifier||p.enterDepth>0){p.held.add(e);return;}
		callNotifier(p,e[1],e[2],e[3]);
		while(!p.held.isEmpty()&&!p.inNotifier&&p.enterDepth==0&&!p.closed){int[] h=p.held.poll();callNotifier(p,h[1],h[2],h[3]);}
	}
	private void callNotifier(Provider p,int code,int result,int cookie) throws M68kException {
		if(p.notifier==0||p.closed)return;
		if(TRACE&&code!=SYNC_IDLE)System.err.println("[ot] notify "+eventName(code)+" "+p.name()+" $"+Integer.toHexString(p.ref)+" result "+result);
		p.inNotifier=true;
		try{guest.notify(p.notifier,p.context,code,result,cookie);}
		finally{p.inNotifier=false;}
	}
	/** Releases the events held back while the program was in its notifier. */
	private void releaseHeld(Provider p){
		if(p.held.isEmpty())return;
		for(int[] e:p.held)events.add(e);p.held.clear();
		if(drainScheduled.compareAndSet(false,true))tb.later(()->{try{drain();}catch(M68kException e){throw Toolbox68k.wrap(e);}});
	}
	static String eventName(int code){
		switch(code){
			case T_LISTEN:return "T_LISTEN";case T_CONNECT:return "T_CONNECT";case T_DATA:return "T_DATA";case T_DISCONNECT:return "T_DISCONNECT";
			case T_ORDREL:return "T_ORDREL";case T_GODATA:return "T_GODATA";case T_PASSCON:return "T_PASSCON";case T_UDERR:return "T_UDERR";
			case T_BINDCOMPLETE:return "T_BINDCOMPLETE";case T_UNBINDCOMPLETE:return "T_UNBINDCOMPLETE";case T_ACCEPTCOMPLETE:return "T_ACCEPTCOMPLETE";
			case T_DISCONNECTCOMPLETE:return "T_DISCONNECTCOMPLETE";case T_OPTMGMTCOMPLETE:return "T_OPTMGMTCOMPLETE";case T_OPENCOMPLETE:return "T_OPENCOMPLETE";
			case T_GETPROTADDRCOMPLETE:return "T_GETPROTADDRCOMPLETE";case T_RESOLVEADDRCOMPLETE:return "T_RESOLVEADDRCOMPLETE";case T_MEMORYRELEASED:return "T_MEMORYRELEASED";
			case T_LKUPNAMECOMPLETE:return "T_LKUPNAMECOMPLETE";case T_REGNAMECOMPLETE:return "T_REGNAMECOMPLETE";case T_DELNAMECOMPLETE:return "T_DELNAMECOMPLETE";
			case T_DNRSTRINGTOADDRCOMPLETE:return "T_DNRSTRINGTOADDRCOMPLETE";case T_DNRADDRTONAMECOMPLETE:return "T_DNRADDRTONAMECOMPLETE";
			case T_DNRSYSINFOCOMPLETE:return "T_DNRSYSINFOCOMPLETE";case T_DNRMAILEXCHANGECOMPLETE:return "T_DNRMAILEXCHANGECOMPLETE";case SYNC_IDLE:return "kOTSyncIdleEvent";
			default:return "$"+Integer.toHexString(code);
		}
	}

	// ------------------------------------------------------------------ waiting

	private interface Cond { boolean done(); }
	/**
	 * Waits in a synchronous blocking call until {@code c} holds: the VM's deferred work and timers keep running, and the
	 * provider's notifier gets kOTSyncIdleEvent when it asked for them (programs yield to their threads there). Returns 0, the
	 * code of OTCancelSynchronousCalls, or kETIMEDOUTErr after {@code timeoutMillis} (0 = none).
	 */
	private int await(Provider p,Cond c,long timeoutMillis) throws M68kException {
		long deadline=timeoutMillis<=0?Long.MAX_VALUE:System.currentTimeMillis()+timeoutMillis;
		if(p!=null)p.cancel=0;
		long lastIdle=0;
		while(!c.done()){
			if(shutDown)return CANCELED;
			if(p!=null&&p.cancel!=0){int r=p.cancel;p.cancel=0;return r;}
			if(System.currentTimeMillis()>=deadline)return TIMED_OUT;
			tb.runDeferred();tb.runTimers();
			if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			long now=System.currentTimeMillis();
			if(p!=null&&p.syncIdle&&p.notifier!=0&&!p.inNotifier&&now-lastIdle>=16){lastIdle=now;callNotifier(p,SYNC_IDLE,0,0);if(c.done())break;}
			synchronized(lock){if(!c.done())try{lock.wait(8);}catch(InterruptedException e){Thread.currentThread().interrupt();return CANCELED;}}
		}
		return NO_ERR;
	}
	private boolean waits(Provider p){return p.sync&&p.blocking;}

	// ------------------------------------------------------------------ memory helpers

	private String cString(int p) throws M68kCpuException {
		if(p==0)return "";
		return new String(memory.readCString(p,1024),StandardCharsets.ISO_8859_1);
	}
	private void writeCString(int p,String s,int max) throws M68kCpuException {
		byte[] b=s.getBytes(StandardCharsets.ISO_8859_1);int n=Math.min(b.length,max-1);memory.load(p,b,0,n);memory.writeByte(p+n,0);
	}
	/** Guest memory for the program (OTAllocMem): cleared, 0 when there is none. */
	private int allocate(int size){try{int p=memory.allocate(Math.max(4,size),4);memory.fill(p,Math.max(4,size),0);return p;}catch(M68kCpuException e){return 0;}}
	private void free(int p){if(p!=0&&memory.isAllocated(p))memory.free(p);}
	private static String dotted(int a){return MacTCP68k.dotted(a);}
	private static int toInt(InetAddress a){return MacTCP68k.toInt(a);}
	private static InetAddress fromInt(int a) throws IOException {return MacTCP68k.fromInt(a);}
	private static Integer parseDotted(String s){String[] parts=s.trim().split("\\.");if(parts.length!=4)return null;int v=0;
		try{for(String p:parts){int n=Integer.parseInt(p);if(n<0||n>255)return null;v=v<<8|n;}}catch(NumberFormatException e){return null;}return v;}

	/** An address as a program gives it: an InetAddress (AF_INET) or a DNSAddress ("name[:port]", AF_DNS). */
	static final class Addr { int ip,port;String name; }
	/** The address in the TNetbuf at {@code nb}; null when it has none (len 0 or no buffer), BAD_ADDRESS is signalled by an Addr with neither. */
	private Addr readAddress(int nb) throws M68kCpuException {
		if(nb==0)return null;int len=memory.readLong(nb+4),buf=memory.readLong(nb+8);
		if(len<2||buf==0)return null;
		return readAddressAt(buf,len);
	}
	private Addr readAddressAt(int buf,int len) throws M68kCpuException {
		Addr a=new Addr();int type=memory.readWord(buf);
		if(type==AF_INET&&len>=8){a.port=memory.readWord(buf+2);a.ip=memory.readLong(buf+4);return a;}
		if(type==AF_DNS){
			byte[] b=memory.readBytes(buf+2,Math.max(0,Math.min(len-2,256)));int n=0;while(n<b.length&&b[n]!=0)n++;
			String s=new String(b,0,n,StandardCharsets.ISO_8859_1).trim();
			int colon=s.lastIndexOf(':');
			if(colon>0){try{a.port=Integer.parseInt(s.substring(colon+1).trim());s=s.substring(0,colon);}catch(NumberFormatException ignored){}}
			Integer ip=parseDotted(s);if(ip!=null)a.ip=ip;else a.name=s;
			return a;
		}
		a.port=-1;return a; // an address family we do not know
	}
	private static boolean bad(Addr a){return a!=null&&a.port<0;}
	/** Stores an InetAddress in the TNetbuf at {@code nb}; BUFFER_OVERFLOW when its buffer is too small (maxlen 0: nothing wanted). */
	private int writeInetAddress(int nb,int ip,int port) throws M68kCpuException {
		if(nb==0)return NO_ERR;int max=memory.readLong(nb),buf=memory.readLong(nb+8);
		if(max==0||buf==0){memory.writeLong(nb+4,0);return NO_ERR;}
		if(max<16){memory.writeLong(nb+4,0);return BUFFER_OVERFLOW;}
		memory.writeWord(buf,AF_INET);memory.writeWord(buf+2,port);memory.writeLong(buf+4,ip);memory.writeLong(buf+8,0);memory.writeLong(buf+12,0);
		memory.writeLong(nb+4,16);return NO_ERR;
	}
	private void clearNetbuf(int nb) throws M68kCpuException {if(nb!=0)memory.writeLong(nb+4,0);}
	/** Copies {@code data} into the TNetbuf at {@code nb} (as much as fits); the length that did not fit, 0 when all did. */
	private int writeNetbuf(int nb,byte[] data,int off,int n) throws M68kCpuException {
		if(nb==0)return n;int max=memory.readLong(nb),buf=memory.readLong(nb+8);
		int k=buf==0?0:Math.min(n,Math.max(0,max));if(k>0)memory.load(buf,data,off,k);memory.writeLong(nb+4,k);return n-k;
	}
	/** The bytes a program sends: {@code nbytes} at {@code buf}, or an OTData chain (nbytes == kNetbufDataIsOTData). */
	private byte[] sendData(int buf,int nbytes) throws M68kCpuException {
		if(nbytes==NETBUF_IS_OT_DATA){
			java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
			for(int d=buf,guard=0;d!=0&&guard<4096;guard++){int data=memory.readLong(d+4),len=memory.readLong(d+8);if(len>0&&data!=0)out.write(memory.readBytes(data,len),0,len);d=memory.readLong(d);}
			return out.toByteArray();
		}
		if(nbytes<=0||buf==0)return new byte[0];
		return memory.readBytes(buf,nbytes);
	}

	// ------------------------------------------------------------------ host network facts

	private int cachedLocalIp,cachedNetmask=0xffffff00,cachedBroadcast,cachedMtu=1500;private byte[] cachedHw;private String cachedDomain="";private boolean scanned;
	/** The host's first IPv4 interface that is up and not the loopback (127.0.0.1 when there is none). */
	private synchronized void scanInterfaces(){
		if(scanned)return;scanned=true;cachedLocalIp=0x7f000001;cachedBroadcast=0x7fffffff;
		try{
			for(java.net.NetworkInterface ni:java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())){
				if(!ni.isUp()||ni.isLoopback()||ni.isVirtual())continue;
				for(java.net.InterfaceAddress ia:ni.getInterfaceAddresses()){
					if(!(ia.getAddress() instanceof java.net.Inet4Address))continue;
					cachedLocalIp=toInt(ia.getAddress());int prefix=ia.getNetworkPrefixLength();cachedNetmask=prefix<=0?0:prefix>=32?-1:-1<<(32-prefix);
					cachedBroadcast=ia.getBroadcast()!=null?toInt(ia.getBroadcast()):(cachedLocalIp|~cachedNetmask);
					try{cachedMtu=ni.getMTU();cachedHw=ni.getHardwareAddress();}catch(IOException ignored){}
					return;
				}
			}
		}catch(IOException|RuntimeException ignored){}
	}
	int localIp(){scanInterfaces();return cachedLocalIp;}

	// ------------------------------------------------------------------ client

	private int clientId=0x6f74436c,clientContext; // 'otCl'
	private boolean initialized;
	private int clientContext() {
		if(clientContext==0){clientContext=allocate(64);try{memory.writeLong(clientContext,0x6f744378);}catch(M68kCpuException ignored){}}
		return clientContext;
	}

	/** Closes every provider (CloseOpenTransport, the program quitting). */
	void closeAll(){
		List<Provider> all;synchronized(providers){all=new ArrayList<Provider>(providers.values());}
		for(Provider p:all)close(p);
		synchronized(timers){for(Timer t:timers.values())t.cancelled=true;timers.clear();}
	}
	void shutDown(){shutDown=true;closeAll();wake();}

	// ------------------------------------------------------------------ configurations

	private final Map<Integer,String> configurations=new HashMap<Integer,String>();
	private static final int CONFIG_MAGIC=0x6f744366; // 'otCf'
	private int createConfiguration(String path) throws M68kCpuException {
		String module=moduleName(path);
		if(module.isEmpty())return CONFIG_INVALID;
		int p=allocate(16);if(p==0)return 0;
		memory.writeLong(p,CONFIG_MAGIC);synchronized(configurations){configurations.put(p,path);}
		if(TRACE)System.err.println("[ot] OTCreateConfiguration(\""+path+"\") -> $"+Integer.toHexString(p));
		return p;
	}
	/** The first module of a configuration string, lower case: "tcp", "udp", "dnr", "Mapper" ... ("tcp(NoDelay=1),ip" gives "tcp"). */
	static String moduleName(String path){
		String s=path.trim();int end=0;while(end<s.length()&&"(,:;= ".indexOf(s.charAt(end))<0)end++;
		return s.substring(0,end).toLowerCase(Locale.ROOT);
	}
	/** The configuration string of {@code cfig} (and forgets it: opening a provider consumes its configuration). */
	private String takeConfiguration(int cfig){
		if(cfig==DEFAULT_INET_SERVICES)return "dnr";
		String s;synchronized(configurations){s=configurations.remove(cfig);}
		if(s!=null)free(cfig);
		return s;
	}
	/** The provider a configuration opens: its transport module, whatever modules are pushed above it ("tilisten,tcp" - the
	 *  listen-queue module over TCP, as Fetch opens its data connections - is TCP). */
	private int kindOf(String path){
		if(path==null)return 0;
		StringBuilder cur=new StringBuilder();int depth=0;
		for(int i=0;i<=path.length();i++){
			char c=i<path.length()?path.charAt(i):',';
			if(c=='(')depth++;else if(c==')')depth--;
			if(c==','&&depth==0){int k=moduleKind(cur.toString());if(k!=0)return k;cur.setLength(0);}else cur.append(c);
		}
		return 0;
	}
	private static int moduleKind(String module){
		switch(moduleName(module)){
			case "tcp":return TCP;
			case "udp":return UDP;
			case "dnr":return INET_SERVICES;
			case "nbp":case "mapper":case "ddp":return MAPPER; // name registration without AppleTalk: nothing is ever found
			case "enet":return ENET;
			default:return 0;
		}
	}

	// ------------------------------------------------------------------ opening and closing

	/** OTOpenEndpoint / OTOpenInternetServices / OTOpenMapper: the provider, or 0 with the error in {@code *err}. */
	private int open(int cfig,int info,int errPtr,int want) throws M68kException {
		int[] r=openProvider(cfig,info,want);
		if(errPtr!=0)memory.writeLong(errPtr,r[1]);
		return r[0];
	}
	private int[] openProvider(int cfig,int info,int want) throws M68kException {
		if(cfig==0)return new int[]{0,OUT_OF_MEMORY};
		if(cfig==CONFIG_INVALID)return new int[]{0,BAD_CONFIGURATION};
		String path=takeConfiguration(cfig);
		int kind=kindOf(path);
		if(TRACE)System.err.println("[ot] open "+path+" (kind "+kind+")");
		if(path==null)return new int[]{0,BAD_CONFIGURATION};
		if(kind==0)return new int[]{0,BAD_NAME};
		if(want==TCP&&kind!=TCP&&kind!=UDP&&kind!=ENET)return new int[]{0,BAD_NAME};
		if(kind==ENET&&hardwareAddress()==null)return new int[]{0,NO_SUCH_DEVICE}; // no Ethernet port
		if(want==INET_SERVICES&&kind!=INET_SERVICES)kind=INET_SERVICES; // OTOpenInternetServices with its own path ("dnr" in practice)
		if(want==MAPPER&&kind!=MAPPER)kind=MAPPER;
		Provider p=new Provider(kind);
		int ref=newRef(p);
		if(p.endpoint()||kind==ENET){p.state=T_UNBND;if(info!=0)writeInfo(p,info);}
		return new int[]{ref,NO_ERR};
	}
	private int asyncOpen(int cfig,int info,int proc,int context,int want) throws M68kException {
		final int[] r=openProvider(cfig,info,want);
		Provider p=provider(r[0]);
		if(p!=null){p.sync=false;p.notifier=proc;p.context=context;post(p,T_OPENCOMPLETE,NO_ERR,r[0]);}
		else if(proc!=0){ // no provider to post through: a stand-in carries the failure to the notifier
			Provider dummy=new Provider(want);dummy.notifier=proc;dummy.context=context;dummy.ref=-1-(nextSequence++);
			synchronized(providers){providers.put(dummy.ref,dummy);}
			post(dummy,T_OPENCOMPLETE,r[1],0);
			tb.later(()->{synchronized(providers){providers.remove(dummy.ref);}});
		}
		return p!=null||proc!=0?NO_ERR:r[1];
	}
	private int closeProvider(int ref){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		close(p);return NO_ERR;
	}
	private void close(Provider p){
		if(TRACE){System.err.println("[ot] close "+p.name()+" $"+Integer.toHexString(p.ref));if(Boolean.getBoolean("hz68k.traceotclose"))new Throwable().printStackTrace();}
		synchronized(p){p.closed=true;}
		closeSockets(p,true);
		for(Incoming i:p.incoming)closeQuietly(i.socket);p.incoming.clear();
		synchronized(providers){providers.remove(p.ref);}
		synchronized(eventProviders){eventProviders.remove(p.ref);}
		free(p.ref);wake();
	}
	private void closeSockets(Provider p,boolean all){
		Socket s;ServerSocket ss;DatagramSocket ds;Socket pre;Writer w;
		synchronized(p){s=p.socket;p.socket=null;w=p.writer;p.writer=null;if(all){ss=p.server;p.server=null;ds=p.datagram;p.datagram=null;pre=p.prebound;p.prebound=null;}else{ss=null;ds=null;pre=null;}}
		if(w!=null)w.stop();
		closeQuietly(s);closeQuietly(pre);
		if(ss!=null)try{ss.close();}catch(IOException ignored){}
		if(ds!=null)ds.close();
	}
	private static void closeQuietly(Socket s){if(s!=null)try{s.close();}catch(IOException ignored){}}

	private void writeInfo(Provider p,int info) throws M68kCpuException {
		if(info==0)return;
		if(p.kind==ENET){ // an Ethernet DLPI endpoint: T8022Address, connectionless frames of up to 1500 bytes
			memory.writeLong(info,15);memory.writeLong(info+4,512);memory.writeLong(info+8,1500);memory.writeLong(info+12,T_INVALID);
			memory.writeLong(info+16,T_INVALID);memory.writeLong(info+20,T_INVALID);memory.writeLong(info+24,T_CLTS);memory.writeLong(info+28,T_XPG4_1);
			return;
		}
		boolean tcp=p.kind==TCP;
		memory.writeLong(info,16);                         // addr: sizeof(InetAddress)
		memory.writeLong(info+4,512);                      // options
		memory.writeLong(info+8,tcp?0:65507);              // tsdu: a byte stream / the largest UDP payload
		memory.writeLong(info+12,tcp?T_INFINITE:T_INVALID);// etsdu: TCP urgent data
		memory.writeLong(info+16,T_INVALID);               // connect: no data on connect
		memory.writeLong(info+20,T_INVALID);               // discon
		memory.writeLong(info+24,tcp?T_COTS_ORD:T_CLTS);   // servtype
		memory.writeLong(info+28,T_XPG4_1|T_CAN_RESOLVE_ADDR);
	}

	// ------------------------------------------------------------------ modes and notifiers

	private int setMode(int ref,int which){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		switch(which){case 0:p.sync=true;break;case 1:p.sync=false;break;case 2:p.blocking=true;break;case 3:p.blocking=false;break;case 4:p.ackSends=true;break;default:p.ackSends=false;break;}
		return NO_ERR;
	}
	private int installNotifier(int ref,int proc,int context){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.notifier!=0&&proc!=0&&p.notifier!=proc)return STATE_CHANGE; // OT refuses a second notifier (kOTStateChangeErr)
		p.notifier=proc;p.context=context;
		// data or a disconnect that arrived before there was a notifier to tell
		synchronized(p){if(p.in.available()>0&&!p.dataNotified){p.dataNotified=true;post(p,T_DATA,NO_ERR,0);}}
		return NO_ERR;
	}

	// ------------------------------------------------------------------ binding

	private int bind(int ref,int reqAddr,int retAddr) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(!p.endpoint()&&p.kind!=ENET)return NOT_SUPPORTED;
		int r=p.kind==ENET?bindLink(p,reqAddr,retAddr):bindNow(p,reqAddr,retAddr);
		if(!p.sync&&r!=OUT_STATE){post(p,T_BINDCOMPLETE,r,retAddr);return NO_ERR;} // a call made in the wrong state fails at once, without a completion event (see sndDisconnect)
		return r;
	}
	private int bindNow(Provider p,int reqAddr,int retAddr) throws M68kException {
		if(p.state!=T_UNBND)return OUT_STATE;
		int port=0,qlen=0;
		if(reqAddr!=0){
			Addr a=readAddress(reqAddr);if(bad(a))return BAD_ADDRESS;
			if(a!=null)port=a.port;
			qlen=memory.readLong(reqAddr+12);
		}
		try{
			if(p.kind==UDP){
				if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_UDP,"",port))return ACCESS;
				DatagramSocket ds=new DatagramSocket(null);ds.setReuseAddress(p.options.getOrDefault(key(INET_IP,IP_REUSEADDR),0)!=0);
				ds.bind(new InetSocketAddress(port));ds.setBroadcast(true);
				p.datagram=ds;p.localPort=ds.getLocalPort();startDatagramReader(p,ds);
			}else if(qlen>0){
				if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_LISTEN,"",port))return ACCESS;
				ServerSocket ss=new ServerSocket();ss.setReuseAddress(true);ss.bind(new InetSocketAddress(port),Math.max(qlen,4));
				p.server=ss;p.localPort=ss.getLocalPort();p.qlen=qlen;startAcceptor(p,ss);
			}else{
				Socket s=new Socket();if(port!=0)s.setReuseAddress(true);s.bind(new InetSocketAddress(port));
				p.prebound=s;p.localPort=s.getLocalPort();
			}
		}catch(java.net.BindException e){if(TRACE)System.err.println("[ot] bind port "+port+": "+e);return ADDRESS_BUSY;}
		catch(IOException e){if(TRACE)System.err.println("[ot] bind port "+port+": "+e);return SYS_ERROR;}
		p.localIp=localIp();p.state=T_IDLE;
		if(TRACE)System.err.println("[ot] bind "+p.name()+" port "+p.localPort+" qlen "+qlen);
		if(retAddr!=0){int r=writeInetAddress(retAddr,p.localIp,p.localPort);memory.writeLong(retAddr+12,p.qlen);if(r!=NO_ERR)return r;}
		return NO_ERR;
	}
	/**
	 * Binds an "enet" endpoint: the bound address is the requested T8022Address (fAddrFamily, fHWAddr[6], fSAP, fSNAP[5])
	 * with the port's hardware address - the host's network interface, as OTInetGetInterfaceInfo reports it. No frames are
	 * sent or received (EthernetAddress binds only to read the address).
	 */
	private int bindLink(Provider p,int reqAddr,int retAddr) throws M68kException {
		if(p.state!=T_UNBND)return OUT_STATE;
		byte[] request=new byte[15];int length=10;
		if(reqAddr!=0){int len=memory.readLong(reqAddr+4),buf=memory.readLong(reqAddr+8);
			if(len>0&&buf!=0){length=Math.min(len,15);System.arraycopy(memory.readBytes(buf,length),0,request,0,length);
				if(length<2||((request[0]&0xff)<<8|request[1]&0xff)!=AF_8022)return BAD_ADDRESS;}}
		request[0]=(byte)(AF_8022>>8);request[1]=(byte)AF_8022;
		byte[] hw=hardwareAddress();System.arraycopy(hw,0,request,2,Math.min(6,hw.length));
		p.linkAddress=java.util.Arrays.copyOf(request,Math.max(length,10));p.state=T_IDLE;
		if(TRACE)System.err.println("[ot] bind enet "+hex(p.linkAddress));
		if(retAddr!=0&&writeNetbuf(retAddr,p.linkAddress,0,p.linkAddress.length)>0)return BUFFER_OVERFLOW;
		return NO_ERR;
	}
	private static String hex(byte[] b){StringBuilder sb=new StringBuilder();for(byte x:b)sb.append(String.format("%02x",x&0xff));return sb.toString();}
	/** The host's Ethernet (or Wi-Fi) hardware address, or null when it has none. */
	byte[] hardwareAddress(){scanInterfaces();return cachedHw!=null&&cachedHw.length==6?cachedHw:null;}
	private int unbind(int ref) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int r;
		if(p.state!=T_IDLE)r=OUT_STATE;
		else{closeSockets(p,true);p.incoming.clear();p.state=T_UNBND;p.qlen=0;r=NO_ERR;}
		if(!p.sync&&r!=OUT_STATE){complete(p,T_UNBINDCOMPLETE,r,0);return NO_ERR;}
		return r;
	}

	private void startAcceptor(final Provider p,final ServerSocket ss){
		Thread t=new Thread(()->{
			while(!p.closed&&!shutDown){
				Socket s;try{s=ss.accept();}catch(IOException e){break;}
				try{s.setTcpNoDelay(true);}catch(IOException ignored){}
				Incoming i;
				synchronized(p){if(p.closed||p.server!=ss){closeQuietly(s);break;}i=new Incoming(s,nextSequence++);p.incoming.add(i);}
				if(TRACE)System.err.println("[ot] incoming connection from "+s.getRemoteSocketAddress()+" seq "+i.sequence);
				post(p,T_LISTEN,NO_ERR,0);
			}
		},"OT accept");t.setDaemon(true);t.start();
	}
	private void startDatagramReader(final Provider p,final DatagramSocket ds){
		Thread t=new Thread(()->{
			byte[] buf=new byte[65536];
			while(!p.closed&&!shutDown){
				DatagramPacket dp=new DatagramPacket(buf,buf.length);
				try{ds.receive(dp);}catch(IOException e){break;}
				byte[] copy=java.util.Arrays.copyOf(dp.getData(),dp.getLength());
				boolean tell;
				synchronized(p){if(p.datagrams.size()>=256)continue;p.datagrams.add(new DatagramPacket(copy,copy.length,dp.getAddress(),dp.getPort()));tell=!p.dataNotified;p.dataNotified=true;}
				if(tell)post(p,T_DATA,NO_ERR,0);else wake();
			}
		},"OT udp");t.setDaemon(true);t.start();
	}

	// ------------------------------------------------------------------ connecting

	private int connect(int ref,int sndCall,int rcvCall) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.kind!=TCP)return NOT_SUPPORTED;
		if(p.state==T_UNBND){int r=bindNow(p,0,0);if(r!=NO_ERR)return r;}
		if(p.state!=T_IDLE||p.qlen>0)return OUT_STATE;
		final Addr a=sndCall==0?null:readAddress(sndCall);
		if(a==null)return NO_ADDRESS;
		if(bad(a))return BAD_ADDRESS;
		String host=a.name!=null?a.name:dotted(a.ip);
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_CONNECT,host,a.port))return ACCESS;
		if(TRACE)System.err.println("[ot] connect to "+host+":"+a.port+(p.sync?"":" (async)")+(p.blocking?"":" (non-blocking)"));
		final Socket s;synchronized(p){s=p.prebound!=null?p.prebound:new Socket();p.prebound=null;p.connecting=true;p.connectConfirmed=false;p.disconnectPending=false;p.peerClosed=false;p.in.clear();p.dataNotified=false;}
		p.state=T_OUTCON;
		final boolean tellNotifier=!waits(p);
		Thread t=new Thread(()->{
			try{
				InetAddress target=a.name!=null?InetAddress.getByName(a.name):fromInt(a.ip);
				if(a.name!=null)Sandbox.hostResolved(a.name,new String[]{target.getHostAddress()});
				s.connect(new InetSocketAddress(target,a.port),30000);s.setTcpNoDelay(p.options.getOrDefault(key(INET_TCP,TCP_NODELAY),1)!=0);
				synchronized(p){if(p.closed||!p.connecting){closeQuietly(s);return;}attach(p,s);p.connecting=false;p.connectConfirmed=true;}
				if(TRACE)System.err.println("[ot] connected to "+s.getRemoteSocketAddress()+" from port "+s.getLocalPort());
				if(tellNotifier)post(p,T_CONNECT,NO_ERR,0);else wake();
			}catch(IOException e){
				int reason=e instanceof ConnectException?ECONNREFUSED:e instanceof SocketTimeoutException?ETIMEDOUT:e instanceof NoRouteToHostException||e instanceof java.net.UnknownHostException?EHOSTUNREACH:ECONNRESET;
				if(TRACE)System.err.println("[ot] connect to "+host+":"+a.port+" failed: "+e);
				closeQuietly(s);
				synchronized(p){if(p.closed||!p.connecting)return;p.connecting=false;p.disconnectPending=true;p.discReason=reason;p.discSequence=0;}
				if(tellNotifier)post(p,T_DISCONNECT,NO_ERR,0);else wake();
			}
		},"OT connect");t.setDaemon(true);t.start();
		if(tellNotifier)return NO_DATA; // in progress: T_CONNECT (or T_DISCONNECT) follows, then OTRcvConnect
		int r=await(p,()->{synchronized(p){return !p.connecting;}},0);
		if(r!=NO_ERR){synchronized(p){p.connecting=false;}closeQuietly(s);p.state=T_IDLE;return r;}
		synchronized(p){
			if(p.disconnectPending)return LOOK; // refused: OTLook gives T_DISCONNECT, OTRcvDisconnect returns to T_IDLE
			p.connectConfirmed=false;
		}
		p.state=T_DATAXFER;
		if(rcvCall!=0){writeInetAddress(rcvCall,p.remoteIp,p.remotePort);clearNetbuf(rcvCall+12);clearNetbuf(rcvCall+24);}
		return NO_ERR;
	}
	private int rcvConnect(int ref,int call) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.state!=T_OUTCON)return OUT_STATE;
		if(!p.connectConfirmed){
			if(p.disconnectPending)return LOOK;
			if(!waits(p))return NO_DATA;
			int r=await(p,()->{synchronized(p){return !p.connecting;}},0);if(r!=NO_ERR)return r;
			if(p.disconnectPending)return LOOK;
		}
		p.connectConfirmed=false;p.state=T_DATAXFER;
		if(call!=0){int r=writeInetAddress(call,p.remoteIp,p.remotePort);clearNetbuf(call+12);clearNetbuf(call+24);if(r!=NO_ERR)return r;}
		return NO_ERR;
	}

	/** Takes a connected socket into the endpoint: remote/local addresses, the reader and the writer. Called with p locked. */
	private void attach(final Provider p,final Socket s) throws IOException {
		p.socket=s;p.remoteIp=toInt(s.getInetAddress());p.remotePort=s.getPort();p.localIp=toInt(s.getLocalAddress());p.localPort=s.getLocalPort();
		if(p.localIp==0||p.localIp==0x7f000001&&p.remoteIp!=0x7f000001)p.localIp=localIp();
		p.peerClosed=false;p.disconnectPending=false;p.in.clear();p.dataNotified=false;
		final InputStream in=s.getInputStream();
		p.writer=new Writer(p,s.getOutputStream());
		Thread reader=new Thread(()->{
			byte[] chunk=new byte[16384];
			try{
				int n;
				while((n=in.read(chunk))>=0){
					if(n==0)continue;
					boolean tell;
					synchronized(p){if(p.socket!=s)return;p.in.append(chunk,0,n);tell=!p.dataNotified;p.dataNotified=true;}
					if(tell)post(p,T_DATA,NO_ERR,0);else wake();
				}
				synchronized(p){if(p.socket!=s)return;p.peerClosed=true;}
				if(TRACE)System.err.println("[ot] peer closed "+s.getRemoteSocketAddress());
				post(p,T_ORDREL,NO_ERR,0);
			}catch(IOException e){
				synchronized(p){if(p.socket!=s||p.closed)return;p.disconnectPending=true;p.discReason=ECONNRESET;p.discSequence=0;}
				if(TRACE)System.err.println("[ot] connection reset: "+e);
				post(p,T_DISCONNECT,NO_ERR,0);
			}
		},"OT reader");
		reader.setDaemon(true);reader.start();
	}

	/** Sends a connection's data from its own thread, so a program's send never blocks the emulation; OTSnd is flow
	 *  controlled at {@link #SEND_WINDOW} bytes queued (kOTFlowErr, T_GODATA once it drained below half). */
	static final int SEND_WINDOW=64*1024;
	final class Writer {
		final Provider p;final OutputStream out;final ArrayDeque<byte[]> queue=new ArrayDeque<byte[]>();int queued;boolean stopped,shutdownAfter;Thread thread;
		Writer(Provider p,OutputStream out){this.p=p;this.out=out;thread=new Thread(this::run,"OT writer");thread.setDaemon(true);thread.start();}
		synchronized int room(){return Math.max(0,SEND_WINDOW-queued);}
		synchronized void add(byte[] b){queue.add(b);queued+=b.length;notifyAll();}
		synchronized void shutdownOutput(){shutdownAfter=true;notifyAll();}
		synchronized void stop(){stopped=true;notifyAll();}
		synchronized boolean idle(){return queue.isEmpty();}
		private void run(){
			try{
				for(;;){
					byte[] b;boolean shut;
					synchronized(this){while(queue.isEmpty()&&!stopped&&!shutdownAfter)wait();if(stopped)return;b=queue.poll();shut=b==null&&shutdownAfter;}
					if(shut){Socket s=p.socket;if(s!=null)try{s.shutdownOutput();}catch(IOException ignored){}return;}
					out.write(b);out.flush();
					boolean go;
					synchronized(this){queued-=b.length;go=p.flowBlocked&&queued<=SEND_WINDOW/2;}
					if(go){synchronized(p){p.flowBlocked=false;}post(p,T_GODATA,NO_ERR,0);}else wake();
				}
			}catch(InterruptedException e){Thread.currentThread().interrupt();}
			catch(IOException e){
				synchronized(p){if(p.closed||p.writer!=this)return;p.disconnectPending=true;p.discReason=ECONNRESET;}
				post(p,T_DISCONNECT,NO_ERR,0);
			}
		}
	}

	// ------------------------------------------------------------------ listening

	private int listen(int ref,int call) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.qlen==0||p.state!=T_IDLE&&p.state!=T_INCON)return OUT_STATE;
		Incoming i=nextUnlistened(p);
		if(i==null){
			if(!waits(p))return NO_DATA;
			int r=await(p,()->nextUnlistened(p)!=null,0);if(r!=NO_ERR)return r;
			i=nextUnlistened(p);
		}
		synchronized(p){i.listened=true;}
		p.state=T_INCON;
		if(call!=0){
			int r=writeInetAddress(call,toInt(i.socket.getInetAddress()),i.socket.getPort());
			clearNetbuf(call+12);clearNetbuf(call+24);memory.writeLong(call+36,i.sequence);
			if(r!=NO_ERR)return r;
		}
		if(TRACE)System.err.println("[ot] listen -> seq "+i.sequence+" from "+i.socket.getRemoteSocketAddress());
		return NO_ERR;
	}
	private Incoming nextUnlistened(Provider p){synchronized(p){for(Incoming i:p.incoming)if(!i.listened)return i;return null;}}
	private Incoming bySequence(Provider p,int seq){synchronized(p){for(Incoming i:p.incoming)if(i.listened&&i.sequence==seq)return i;return null;}}
	private boolean anyListened(Provider p){synchronized(p){for(Incoming i:p.incoming)if(i.listened)return true;return false;}}

	private int accept(int listenerRef,int workerRef,int call) throws M68kException {
		Provider l=provider(listenerRef),w=provider(workerRef);
		if(l==null||w==null)return BAD_REFERENCE;
		int r=acceptNow(l,w,call);
		if(!l.sync){post(l,T_ACCEPTCOMPLETE,r,workerRef);return NO_ERR;}
		return r;
	}
	private int acceptNow(Provider l,Provider w,int call) throws M68kException {
		if(l.state!=T_INCON)return OUT_STATE;
		int seq=call==0?0:memory.readLong(call+36);
		Incoming i=seq!=0?bySequence(l,seq):null;
		if(i==null){synchronized(l){for(Incoming x:l.incoming)if(x.listened){i=x;break;}}}
		if(i==null)return BAD_SEQUENCE;
		if(w.kind!=TCP)return PROVIDER_MISMATCH;
		if(w!=l&&w.state!=T_UNBND&&w.state!=T_IDLE)return OUT_STATE;
		if(w!=l&&w.state==T_IDLE&&w.qlen>0)return INDOUT;
		synchronized(l){l.incoming.remove(i);}
		if(w!=l&&w.prebound!=null){closeQuietly(w.prebound);w.prebound=null;}
		try{synchronized(w){attach(w,i.socket);}}catch(IOException e){closeQuietly(i.socket);return SYS_ERROR;}
		w.state=T_DATAXFER;
		if(w!=l)l.state=anyListened(l)?T_INCON:T_IDLE;
		if(TRACE)System.err.println("[ot] accept seq "+i.sequence+" on $"+Integer.toHexString(w.ref));
		post(w,T_PASSCON,NO_ERR,0);
		// data that came in before the worker took the connection
		synchronized(w){if(w.in.available()>0&&!w.dataNotified){w.dataNotified=true;post(w,T_DATA,NO_ERR,0);}}
		return NO_ERR;
	}

	// ------------------------------------------------------------------ disconnecting

	private int sndDisconnect(int ref,int call) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int r=NO_ERR;
		if(p.state==T_INCON&&call!=0&&memory.readLong(call+36)!=0){ // rejecting a pending connection
			Incoming i=bySequence(p,memory.readLong(call+36));
			if(i==null)r=BAD_SEQUENCE;
			else{synchronized(p){p.incoming.remove(i);}try{i.socket.setSoLinger(true,0);}catch(IOException ignored){}closeQuietly(i.socket);p.state=anyListened(p)?T_INCON:T_IDLE;}
		}else if(p.state==T_DATAXFER||p.state==T_OUTCON||p.state==T_OUTREL||p.state==T_INREL||p.state==T_INCON){
			Socket s;synchronized(p){s=p.socket;p.connecting=false;}
			if(s!=null)try{s.setSoLinger(true,0);}catch(IOException ignored){}
			closeSockets(p,false);
			synchronized(p){p.in.clear();p.disconnectPending=false;p.peerClosed=false;p.dataNotified=false;}
			p.state=T_IDLE;
		}else return OUT_STATE; // as Open Transport: the call fails at once and no T_DISCONNECTCOMPLETE follows - GUSI's close (MacSSH) answers that
		// event by closing again, and on an unbound endpoint each round sent another failing disconnect until the recursion crashed
		if(!p.sync){complete(p,T_DISCONNECTCOMPLETE,r,0);return NO_ERR;}
		return r;
	}
	private int sndOrderlyDisconnect(int ref){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.state==T_DATAXFER){p.state=T_OUTREL;if(p.writer!=null)p.writer.shutdownOutput();return NO_ERR;}
		if(p.state==T_INREL){if(p.writer!=null)p.writer.shutdownOutput();finishRelease(p);return NO_ERR;}
		return OUT_STATE;
	}
	/** Both sides released: back to T_IDLE once the queued data went out. */
	private void finishRelease(final Provider p){
		p.state=T_IDLE;
		final Writer w=p.writer;final Socket s=p.socket;
		synchronized(p){p.socket=null;p.writer=null;p.in.clear();p.peerClosed=false;p.dataNotified=false;}
		Thread t=new Thread(()->{long end=System.currentTimeMillis()+10000;while(w!=null&&!w.idle()&&System.currentTimeMillis()<end)try{Thread.sleep(10);}catch(InterruptedException e){break;}
			try{Thread.sleep(50);}catch(InterruptedException ignored){}if(w!=null)w.stop();closeQuietly(s);},"OT release");t.setDaemon(true);t.start();
	}
	private int rcvOrderlyDisconnect(int ref){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		synchronized(p){
			if(p.disconnectPending)return LOOK;
			if(!p.peerClosed||p.in.available()>0)return p.in.available()>0?LOOK:NO_RELEASE;
		}
		if(p.state==T_DATAXFER){p.state=T_INREL;return NO_ERR;}
		if(p.state==T_OUTREL){finishRelease(p);return NO_ERR;}
		return OUT_STATE;
	}
	private int rcvDisconnect(int ref,int discon) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int reason,seq;
		synchronized(p){if(!p.disconnectPending)return NO_DISCONNECT;p.disconnectPending=false;reason=p.discReason;seq=p.discSequence;p.in.clear();p.dataNotified=false;p.peerClosed=false;}
		closeSockets(p,false);
		p.state=p.qlen>0&&anyListened(p)?T_INCON:T_IDLE;
		if(discon!=0){clearNetbuf(discon);memory.writeLong(discon+12,reason);memory.writeLong(discon+16,seq);}
		return NO_ERR;
	}

	// ------------------------------------------------------------------ data

	private int snd(int ref,int buf,int nbytes,int flags) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.kind!=TCP)return NOT_SUPPORTED;
		if(p.disconnectPending)return LOOK;
		if(p.state!=T_DATAXFER&&p.state!=T_INREL)return OUT_STATE;
		final Writer w=p.writer;if(w==null)return OUT_STATE;
		byte[] data=sendData(buf,nbytes);
		int sent=0;
		while(sent<data.length){
			int room=w.room();
			if(room==0){
				if(!waits(p)){if(sent>0)break;synchronized(p){p.flowBlocked=true;}return FLOW;}
				int r=await(p,()->w.room()>0||p.disconnectPending||p.writer!=w,0);
				if(r!=NO_ERR)return sent>0?sent:r;
				if(p.disconnectPending||p.writer!=w)return sent>0?sent:LOOK;
				continue;
			}
			int n=Math.min(room,data.length-sent);
			w.add(java.util.Arrays.copyOfRange(data,sent,sent+n));sent+=n;
		}
		if(p.ackSends&&sent>0)post(p,T_MEMORYRELEASED,sent,buf);
		return sent;
	}
	private int rcv(int ref,int buf,int nbytes,int flagsPtr) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.kind!=TCP)return NOT_SUPPORTED;
		if(p.state!=T_DATAXFER&&p.state!=T_OUTREL){
			synchronized(p){if(p.disconnectPending)return LOOK;}
			return OUT_STATE;
		}
		for(;;){
			synchronized(p){
				int avail=p.in.available();
				if(avail>0){
					if(nbytes==NETBUF_IS_OT_BUFFER_STAR)return rcvBuffer(p,buf,flagsPtr);
					int n=Math.min(avail,Math.max(0,nbytes));
					byte[] b=new byte[n];p.in.take(b,n);if(n>0)memory.load(buf,b);
					if(flagsPtr!=0)memory.writeLong(flagsPtr,0);
					if(p.in.available()==0)p.dataNotified=false; // the next data tells the notifier again
					return n;
				}
				p.dataNotified=false;
				if(p.disconnectPending||p.peerClosed)return LOOK;
			}
			if(!waits(p))return NO_DATA;
			int r=await(p,()->{synchronized(p){return p.in.available()>0||p.disconnectPending||p.peerClosed||p.closed;}},0);
			if(r!=NO_ERR)return r;
			if(p.closed)return BAD_REFERENCE;
		}
	}
	/** A no-copy receive (nbytes == kOTNetbufDataIsOTBufferStar): an OTBuffer (38 bytes + data) the program releases with OTReleaseBuffer. */
	private int rcvBuffer(Provider p,int bufPtr,int flagsPtr) throws M68kCpuException {
		int n=p.in.available();byte[] b=new byte[n];p.in.take(b,n);p.dataNotified=false;
		int ob=allocate(40+n);if(ob==0)return OUT_OF_MEMORY;
		memory.writeLong(ob+12,ob+40);memory.writeLong(ob+16,n);memory.writeByte(ob+25,0);memory.load(ob+40,b);
		memory.writeLong(bufPtr,ob);if(flagsPtr!=0)memory.writeLong(flagsPtr,0);
		return n;
	}
	private int countDataBytes(int ref,int countPtr) throws M68kCpuException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int n;
		synchronized(p){
			if(p.kind==UDP){DatagramPacket d=p.datagrams.peek();n=d==null?0:d.getLength()-p.partialOffset;}
			else n=p.in.available();
			if(n==0)return NO_DATA;
		}
		if(countPtr!=0)memory.writeLong(countPtr,n);
		return NO_ERR;
	}

	private int sndUData(int ref,int udata) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.kind!=UDP)return NOT_SUPPORTED;
		if(p.state==T_UNBND){int r=bindNow(p,0,0);if(r!=NO_ERR)return r;}
		if(p.state!=T_IDLE)return OUT_STATE;
		Addr a=readAddress(udata);if(a==null)return NO_ADDRESS;if(bad(a))return BAD_ADDRESS;
		int len=memory.readLong(udata+28),buf=memory.readLong(udata+32);
		byte[] data=sendData(buf,len);
		String host=a.name!=null?a.name:dotted(a.ip);
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_UDP,host,a.port))return ACCESS;
		try{InetAddress target=a.name!=null?InetAddress.getByName(a.name):fromInt(a.ip);p.datagram.send(new DatagramPacket(data,data.length,target,a.port));}
		catch(IOException e){if(TRACE)System.err.println("[ot] udp send to "+host+":"+a.port+": "+e);return BAD_ADDRESS;}
		if(p.ackSends)post(p,T_MEMORYRELEASED,data.length,udata);
		return NO_ERR;
	}
	private int rcvUData(int ref,int udata,int flagsPtr) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(p.kind!=UDP)return NOT_SUPPORTED;
		if(p.state!=T_IDLE)return OUT_STATE;
		for(;;){
			synchronized(p){
				DatagramPacket d=p.datagrams.peek();
				if(d!=null){
					int r=writeInetAddress(udata,toInt(d.getAddress()),d.getPort());
					clearNetbuf(udata+12);
					int left=writeNetbuf(udata+24,d.getData(),p.partialOffset,d.getLength()-p.partialOffset);
					int flags=0;
					if(left>0){p.partialOffset=d.getLength()-left;flags=T_MORE;}
					else{p.datagrams.poll();p.partialOffset=0;}
					if(p.datagrams.isEmpty())p.dataNotified=false;
					if(flagsPtr!=0)memory.writeLong(flagsPtr,flags);
					return r;
				}
				p.dataNotified=false;
			}
			if(!waits(p))return NO_DATA;
			int r=await(p,()->{synchronized(p){return !p.datagrams.isEmpty()||p.closed;}},0);
			if(r!=NO_ERR)return r;
			if(p.closed)return BAD_REFERENCE;
		}
	}

	// ------------------------------------------------------------------ state, addresses, options

	private int look(int ref){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		synchronized(p){
			if(p.disconnectPending)return T_DISCONNECT;
			if(nextUnlistened(p)!=null)return T_LISTEN;
			if(p.connectConfirmed&&p.state==T_OUTCON)return T_CONNECT;
			if(p.in.available()>0||!p.datagrams.isEmpty())return T_DATA;
			if(p.peerClosed&&(p.state==T_DATAXFER||p.state==T_OUTREL))return T_ORDREL;
			if(p.flowBlocked&&p.writer!=null&&p.writer.room()>0){p.flowBlocked=false;return T_GODATA;}
		}
		return 0;
	}
	private int getProtAddress(int ref,int boundAddr,int peerAddr) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int r=NO_ERR;
		if(p.kind==ENET){if(boundAddr!=0){if(p.linkAddress==null||p.state==T_UNBND)clearNetbuf(boundAddr);else if(writeNetbuf(boundAddr,p.linkAddress,0,p.linkAddress.length)>0)r=BUFFER_OVERFLOW;}clearNetbuf(peerAddr);}
		else if(boundAddr!=0){if(p.state==T_UNBND)clearNetbuf(boundAddr);else r=writeInetAddress(boundAddr,p.localIp,p.localPort);}
		if(peerAddr!=0){if(p.state==T_DATAXFER||p.state==T_OUTREL||p.state==T_INREL){int r2=writeInetAddress(peerAddr,p.remoteIp,p.remotePort);if(r==NO_ERR)r=r2;}else clearNetbuf(peerAddr);}
		if(!p.sync){post(p,T_GETPROTADDRCOMPLETE,r,boundAddr!=0?boundAddr:peerAddr);return NO_ERR;}
		return r;
	}
	private int resolveAddress(int ref,final int reqAddr,final int retAddr,int timeout) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		final Addr a=readAddress(reqAddr);if(a==null)return NO_ADDRESS;if(bad(a))return BAD_ADDRESS;
		if(a.name==null){int r=writeInetAddress(retAddr,a.ip,a.port);if(!p.sync){post(p,T_RESOLVEADDRCOMPLETE,r,retAddr);return NO_ERR;}return r;}
		final Lookup l=lookup(a.name);
		if(l==null){if(!p.sync){post(p,T_RESOLVEADDRCOMPLETE,ACCESS,retAddr);return NO_ERR;}return ACCESS;}
		if(!p.sync){l.then(()->{int r;try{r=l.result!=NO_ERR?l.result:writeInetAddress(retAddr,l.addresses[0],a.port);}catch(M68kCpuException e){r=SYS_ERROR;}post(p,T_RESOLVEADDRCOMPLETE,r,retAddr);});return NO_ERR;}
		int r=await(p,l::done,timeout>0?timeout:0);if(r!=NO_ERR)return r;
		if(l.result!=NO_ERR)return l.result;
		return writeInetAddress(retAddr,l.addresses[0],a.port);
	}

	private static int key(int level,int name){return level<<16|name&0xffff;}
	/**
	 * OTOptionManagement: every option of the request comes back with its status (T_SUCCESS, or T_NOTSUPPORT for what the
	 * host cannot do) and, for T_CURRENT/T_DEFAULT/T_CHECK, its value; negotiated values go to the socket where Java can apply them.
	 */
	private int optionManagement(int ref,int req,int ret) throws M68kException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int r=optionsNow(p,req,ret);
		if(!p.sync){post(p,T_OPTMGMTCOMPLETE,r,ret);return NO_ERR;}
		return r;
	}
	private int optionsNow(Provider p,int req,int ret) throws M68kException {
		if(req==0)return BAD_OPTION;
		int flags=memory.readLong(req+12),len=memory.readLong(req+4),buf=memory.readLong(req+8);
		byte[] reqBytes=len>0&&buf!=0?memory.readBytes(buf,len):new byte[0];
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		int overall=0;boolean anyOk=false,anyBad=false;
		for(int o=0;o+16<=reqBytes.length;){
			int olen=be32(reqBytes,o),level=be32(reqBytes,o+4),name=be32(reqBytes,o+8);
			if(olen<16||o+olen>reqBytes.length)break;
			int value=olen>=20?be32(reqBytes,o+16):0;
			if(olen==17)value=reqBytes[o+16]&0xff;else if(olen==18)value=(reqBytes[o+16]&0xff)<<8|reqBytes[o+17]&0xff;
			boolean supported=optionSupported(p,level,name);
			int status;
			if(!supported)status=T_NOTSUPPORT;
			else if((flags&T_NEGOTIATE)!=0){applyOption(p,level,name,value,reqBytes,o,olen);status=T_SUCCESS;}
			else status=T_SUCCESS;
			byte[] opt=java.util.Arrays.copyOfRange(reqBytes,o,o+olen);
			put32(opt,12,status);
			if(supported&&(flags&(T_CURRENT|T_DEFAULT|T_CHECK))!=0&&olen>=20){int v=(flags&T_DEFAULT)!=0?defaultOption(level,name):currentOption(p,level,name);put32(opt,16,v);}
			out.write(opt,0,opt.length);for(int pad=olen;(pad&3)!=0;pad++)out.write(0);
			if(supported)anyOk=true;else anyBad=true;
			o+=(olen+3)&~3;
		}
		overall=anyBad?(anyOk?T_PARTSUCCESS:T_NOTSUPPORT):T_SUCCESS;
		if(ret!=0){
			byte[] b=out.toByteArray();int left=writeNetbuf(ret,b,0,b.length);memory.writeLong(ret+12,overall);
			if(left>0&&memory.readLong(ret)>0)return BUFFER_OVERFLOW;
		}
		if(TRACE)System.err.println("[ot] OTOptionManagement flags $"+Integer.toHexString(flags)+" -> $"+Integer.toHexString(overall));
		return NO_ERR;
	}
	private static int be32(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;}
	private static void put32(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
	private boolean optionSupported(Provider p,int level,int name){
		switch(level){
			case XTI_GENERIC:return name==XTI_LINGER||name==XTI_RCVBUF||name==XTI_SNDBUF||name==XTI_RCVLOWAT||name==XTI_SNDLOWAT||name==OPT_KEEPALIVE||name==1;
			case INET_TCP:return p.kind==TCP&&(name==TCP_NODELAY||name==TCP_MAXSEG||name==OPT_KEEPALIVE||(name>=0x10&&name<=0x15));
			case INET_UDP:return p.kind==UDP;
			case INET_IP:return name==IP_REUSEADDR||name==IP_BROADCAST||name==IP_TTL||name==0x02||name==IP_REUSEPORT||name==0x10||name==0x01||(name>=0x1010&&name<=0x1016);
			default:return false;
		}
	}
	private int defaultOption(int level,int name){
		if(level==XTI_GENERIC&&(name==XTI_RCVBUF||name==XTI_SNDBUF))return 32768;
		if(level==XTI_GENERIC&&(name==XTI_RCVLOWAT||name==XTI_SNDLOWAT))return 1;
		if(level==INET_TCP&&name==TCP_MAXSEG)return 1460;
		if(level==INET_IP&&name==IP_TTL)return 64;
		return 0;
	}
	private int currentOption(Provider p,int level,int name){Integer v=p.options.get(key(level,name));return v!=null?v:defaultOption(level,name);}
	private void applyOption(Provider p,int level,int name,int value,byte[] b,int o,int olen){
		p.options.put(key(level,name),value);
		try{
			Socket s=p.socket!=null?p.socket:p.prebound;
			if(level==INET_TCP&&name==TCP_NODELAY&&s!=null)s.setTcpNoDelay(value!=0);
			if((level==XTI_GENERIC||level==INET_TCP)&&name==OPT_KEEPALIVE&&s!=null)s.setKeepAlive(value!=0);
			if(level==INET_IP&&name==IP_BROADCAST&&p.datagram!=null)p.datagram.setBroadcast(value!=0);
			if(level==INET_IP&&name==IP_REUSEADDR){if(p.datagram!=null)p.datagram.setReuseAddress(value!=0);if(s!=null&&!s.isBound())s.setReuseAddress(value!=0);}
			if(level==XTI_GENERIC&&name==XTI_RCVBUF&&value>0&&s!=null)s.setReceiveBufferSize(value);
			if(level==XTI_GENERIC&&name==XTI_SNDBUF&&value>0&&s!=null)s.setSendBufferSize(value);
		}catch(IOException|RuntimeException e){if(TRACE)System.err.println("[ot] option "+level+"/"+name+": "+e);}
	}

	/** OTIoctl: I_NREAD answers the bytes waiting; the rest succeed without effect. */
	private int ioctl(int ref,int cmd,int data) throws M68kCpuException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(cmd==I_NREAD){int n;synchronized(p){n=p.kind==UDP?(p.datagrams.isEmpty()?0:p.datagrams.peek().getLength()):p.in.available();}if(data!=0)memory.writeLong(data,n);return n>0?1:0;}
		if(TRACE)System.err.println("[ot] OTIoctl $"+Integer.toHexString(cmd)+" ignored");
		return NO_ERR;
	}

	// ------------------------------------------------------------------ OTAlloc

	private int otAlloc(int ref,int type,int fields,int errPtr) throws M68kException {
		Provider p=provider(ref);
		int err=NO_ERR,result=0;
		if(p==null)err=BAD_REFERENCE;
		else{
			int addrSize=16,optSize=512,udataSize=p.kind==UDP?65507:0;
			int size;int[] netbufs;
			switch(type){
				case T_BIND:size=16;netbufs=new int[]{0,addrSize};break;
				case T_OPTMGMT:size=16;netbufs=new int[]{0,optSize};break;
				case T_CALL:size=40;netbufs=new int[]{0,addrSize,12,optSize,24,udataSize};break;
				case T_DIS:size=20;netbufs=new int[]{0,0};break;
				case T_UNITDATA:size=36;netbufs=new int[]{0,addrSize,12,optSize,24,udataSize};break;
				case T_UDERROR:size=28;netbufs=new int[]{0,addrSize,12,optSize};break;
				case T_INFO:size=32;netbufs=new int[0];break;
				default:size=0;netbufs=null;
			}
			if(netbufs==null)err=NO_STRUCTURE_TYPE;
			else{
				result=allocate(size);
				if(result==0)err=OUT_OF_MEMORY;
				else{
					if(type==T_INFO)writeInfo(p,result);
					for(int i=0;i+1<netbufs.length;i+=2){
						int field=netbufs[i]==0?T_ADDR:netbufs[i]==12?T_OPT:T_UDATA;
						if(type==T_OPTMGMT)field=T_OPT;
						if(type==T_DIS)field=T_UDATA;
						int n=netbufs[i+1];
						if((fields&field)==0||n<=0)continue;
						int b=allocate(n);if(b==0){err=OUT_OF_MEMORY;break;}
						memory.writeLong(result+netbufs[i],n);memory.writeLong(result+netbufs[i]+8,b);
					}
				}
			}
		}
		if(errPtr!=0)memory.writeLong(errPtr,err);
		return err==NO_ERR?result:0;
	}
	private int otFree(int ptr,int type) throws M68kCpuException {
		if(ptr==0)return NO_ERR;
		int[] offsets;
		switch(type){case T_BIND:case T_OPTMGMT:case T_DIS:offsets=new int[]{0};break;case T_CALL:case T_UNITDATA:offsets=new int[]{0,12,24};break;case T_UDERROR:offsets=new int[]{0,12};break;default:offsets=new int[0];}
		for(int o:offsets)free(memory.readLong(ptr+o+8));
		free(ptr);return NO_ERR;
	}

	// ------------------------------------------------------------------ Internet services (DNR)

	/** A name lookup running on a host thread. */
	final class Lookup {
		volatile boolean done;int result=NO_ERR;int[] addresses=new int[0];String canonical="";
		private final List<Runnable> after=new ArrayList<Runnable>();
		synchronized void then(Runnable r){if(done)tb.later(r);else after.add(r);}
		synchronized void finish(){done=true;for(Runnable r:after)tb.later(r);after.clear();wake();}
		boolean done(){return done;}
	}
	/** Starts resolving {@code name}; null when the user does not allow the lookup. */
	private Lookup lookup(final String name){
		final Lookup l=new Lookup();
		Integer ip=parseDotted(name);
		if(ip!=null){l.addresses=new int[]{ip};l.canonical=name;l.finish();return l;}
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_LOOKUP,name,0))return null;
		Thread t=new Thread(()->{
			try{
				InetAddress[] all=InetAddress.getAllByName(name);List<Integer> v4=new ArrayList<Integer>();
				for(InetAddress a:all)if(a.getAddress().length==4)v4.add(toInt(a));
				if(v4.isEmpty())l.result=NO_DATA;
				else{l.addresses=new int[Math.min(10,v4.size())];for(int i=0;i<l.addresses.length;i++)l.addresses[i]=v4.get(i);
					String[] dottedAll=new String[l.addresses.length];for(int i=0;i<dottedAll.length;i++)dottedAll[i]=dotted(l.addresses[i]);Sandbox.hostResolved(name,dottedAll);
					l.canonical=name;try{String c=all[0].getCanonicalHostName();if(c!=null&&!c.isEmpty()&&parseDotted(c)==null)l.canonical=c;}catch(RuntimeException ignored){}}
			}catch(IOException e){l.result=NO_DATA;}
			if(TRACE)System.err.println("[ot] lookup "+name+" -> "+(l.result==NO_ERR?dotted(l.addresses[0]):"error "+l.result));
			l.finish();
		},"OT DNR");t.setDaemon(true);t.start();
		return l;
	}
	private void writeHostInfo(int hinfo,Lookup l) throws M68kCpuException {
		writeCString(hinfo,l.canonical,256);
		for(int i=0;i<10;i++)memory.writeLong(hinfo+256+i*4,i<l.addresses.length?l.addresses[i]:0);
	}
	private int stringToAddress(int ref,int namePtr,final int hinfo) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		String name=cString(namePtr).trim();
		if(TRACE)System.err.println("[ot] OTInetStringToAddress \""+name+"\""+(p.sync?"":" (async)"));
		if(name.endsWith("."))name=name.substring(0,name.length()-1);
		final Lookup l=name.isEmpty()?null:lookup(name);
		if(l==null){int r=name.isEmpty()?BAD_NAME:ACCESS;if(!p.sync){post(p,T_DNRSTRINGTOADDRCOMPLETE,r,hinfo);return NO_ERR;}return r;}
		if(!p.sync){l.then(()->{int r=l.result;try{if(r==NO_ERR)writeHostInfo(hinfo,l);}catch(M68kCpuException e){r=SYS_ERROR;}post(p,T_DNRSTRINGTOADDRCOMPLETE,r,hinfo);});return NO_ERR;}
		int r=await(p,l::done,60000);if(r!=NO_ERR)return r;
		if(l.result==NO_ERR)writeHostInfo(hinfo,l);
		return l.result;
	}
	private int addressToName(int ref,final int host,final int namePtr) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		final String[] out={null};final boolean[] done={false};
		Thread t=new Thread(()->{try{String n=fromInt(host).getCanonicalHostName();out[0]=n;}catch(IOException e){out[0]=null;}synchronized(done){done[0]=true;}wake();},"OT DNR");
		t.setDaemon(true);t.start();
		if(!p.sync){
			Thread w=new Thread(()->{synchronized(done){while(!done[0])try{done.wait(20);}catch(InterruptedException e){return;}}
				tb.later(()->{int r;try{r=writeName(namePtr,out[0],host);}catch(M68kCpuException e){r=SYS_ERROR;}post(p,T_DNRADDRTONAMECOMPLETE,r,namePtr);});},"OT DNR completion");
			w.setDaemon(true);w.start();return NO_ERR;
		}
		int r=await(p,()->{synchronized(done){return done[0];}},30000);if(r!=NO_ERR)return r;
		return writeName(namePtr,out[0],host);
	}
	private int writeName(int namePtr,String name,int host) throws M68kCpuException {
		if(name==null||name.equals(dotted(host)))return NO_DATA; // no PTR record
		writeCString(namePtr,name.endsWith(".")?name:name+".",256);return NO_ERR;
	}
	private int sysInfo(int ref,int namePtr,int info) throws M68kCpuException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		writeCString(info,guest!=null?guest.cpuType():"POWERPC",32);writeCString(info+32,"MACOS",32);
		if(!p.sync){post(p,T_DNRSYSINFOCOMPLETE,NO_ERR,info);return NO_ERR;}
		return NO_ERR;
	}
	/** OTInetMailExchange: MX records through the JDK's DNS naming provider (none when it is not there). */
	private int mailExchange(int ref,int namePtr,final int numPtr,final int mx) throws M68kException {
		final Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		final String name=cString(namePtr).trim();final int max=numPtr==0?0:memory.readWord(numPtr);
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_LOOKUP,name,0))return ACCESS;
		final List<String[]> found=new ArrayList<String[]>();final boolean[] done={false};
		Thread t=new Thread(()->{
			try{java.util.Hashtable<String,String> env=new java.util.Hashtable<String,String>();env.put("java.naming.factory.initial","com.sun.jndi.dns.DnsContextFactory");
				javax.naming.directory.DirContext ctx=new javax.naming.directory.InitialDirContext(env);
				javax.naming.directory.Attribute a=ctx.getAttributes(name,new String[]{"MX"}).get("MX");
				if(a!=null)for(int i=0;i<a.size();i++){String[] parts=a.get(i).toString().trim().split("\\s+");if(parts.length==2)found.add(parts);}
			}catch(Exception|LinkageError ignored){}
			synchronized(done){done[0]=true;}wake();
		},"OT DNR MX");t.setDaemon(true);t.start();
		Cond c=()->{synchronized(done){return done[0];}};
		Runnable store=()->{try{int n=Math.min(max,found.size());for(int i=0;i<n;i++){memory.writeWord(mx+i*258,Integer.parseInt(found.get(i)[0]));String host=found.get(i)[1];writeCString(mx+i*258+2,host,256);}memory.writeWord(numPtr,n);}catch(M68kCpuException|NumberFormatException ignored){}};
		if(!p.sync){Thread w=new Thread(()->{synchronized(done){while(!done[0])try{done.wait(20);}catch(InterruptedException e){return;}}tb.later(()->{store.run();post(p,T_DNRMAILEXCHANGECOMPLETE,found.isEmpty()?NO_DATA:NO_ERR,mx);});},"OT DNR completion");w.setDaemon(true);w.start();return NO_ERR;}
		int r=await(p,c,30000);if(r!=NO_ERR)return r;
		store.run();return found.isEmpty()?NO_DATA:NO_ERR;
	}
	private int interfaceInfo(int info,int index) throws M68kCpuException {
		if(index!=0&&index!=-1)return NOT_FOUND;
		scanInterfaces();
		int gateway=(cachedLocalIp&cachedNetmask)|1;
		memory.fill(info,300,0);
		memory.writeLong(info,cachedLocalIp);memory.writeLong(info+4,cachedNetmask);memory.writeLong(info+8,cachedBroadcast);
		memory.writeLong(info+12,gateway);memory.writeLong(info+16,gateway);
		memory.writeWord(info+20,3);                               // fVersion: kInetInterfaceInfoVersion
		if(cachedHw!=null&&cachedHw.length>0){if(hwAddress==0){hwAddress=allocate(cachedHw.length);memory.load(hwAddress,cachedHw);}memory.writeWord(info+22,cachedHw.length);memory.writeLong(info+24,hwAddress);}
		memory.writeLong(info+28,cachedMtu);
		writeCString(info+40,cachedDomain,256);
		return NO_ERR;
	}
	private int hwAddress;

	// ------------------------------------------------------------------ mapper (name registration)

	private int nextNameId=1;
	private int registerName(int ref,int req,int reply) throws M68kCpuException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		int id=nextNameId++;
		if(reply!=0){clearNetbuf(reply);memory.writeLong(reply+12,id);}
		if(!p.sync){post(p,T_REGNAMECOMPLETE,NO_ERR,reply);return NO_ERR;}
		return NO_ERR;
	}
	private int deleteName(int ref,int what){
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(!p.sync){post(p,T_DELNAMECOMPLETE,NO_ERR,what);return NO_ERR;}
		return NO_ERR;
	}
	private int lookupName(int ref,int req,int reply) throws M68kCpuException {
		Provider p=provider(ref);if(p==null)return BAD_REFERENCE;
		if(reply!=0){clearNetbuf(reply);memory.writeLong(reply+12,0);}
		if(!p.sync){post(p,T_LKUPNAMECOMPLETE,NO_ERR,reply);return NO_ERR;}
		return NO_ERR;
	}

	// ------------------------------------------------------------------ tasks and timers

	/** A deferred, system or timer task: a routine and its argument. */
	final class Task { final int proc,arg;boolean scheduled,destroyed;Task(int proc,int arg){this.proc=proc;this.arg=arg;} }
	private final Map<Integer,Task> tasks=new HashMap<Integer,Task>();
	private int nextTask=0x6f740001;
	private int createTask(int proc,int arg){if(proc==0)return 0;int id=nextTask++;synchronized(tasks){tasks.put(id,new Task(proc,arg));}return id;}
	private Task task(int id){synchronized(tasks){return tasks.get(id);}}
	/** Schedules a deferred (at the next poll) or system (at the next event call) task; false when it already was. */
	private boolean scheduleTask(int id,boolean atEvent){
		final Task t=task(id);if(t==null||t.scheduled)return false;
		t.scheduled=true;
		Runnable run=()->{if(!t.scheduled||t.destroyed)return;t.scheduled=false;try{guest.process(t.proc,t.arg);}catch(M68kException e){throw Toolbox68k.wrap(e);}};
		if(atEvent)tb.laterAtEvent(run);else tb.later(run);
		return true;
	}
	private boolean cancelTask(int id){Task t=task(id);if(t==null||!t.scheduled)return false;t.scheduled=false;return true;}
	private int destroyTask(int id){Task t;synchronized(tasks){t=tasks.remove(id);}if(t==null)return BAD_REFERENCE;t.destroyed=true;t.scheduled=false;return NO_ERR;}

	final class Timer { final int proc,arg;volatile boolean cancelled;long due;Timer(int proc,int arg){this.proc=proc;this.arg=arg;} }
	private final Map<Integer,Timer> timers=new HashMap<Integer,Timer>();
	private final Map<Integer,Timer[]> timerHandles=new HashMap<Integer,Timer[]>();
	private int createTimer(int proc,int arg){if(proc==0)return 0;int id=nextTask++;synchronized(timers){timerHandles.put(id,new Timer[]{new Timer(proc,arg)});}return id;}
	private boolean scheduleTimer(int id,int millis){
		Timer[] h;synchronized(timers){h=timerHandles.get(id);}if(h==null)return false;
		final Timer old=h[0];
		old.cancelled=true;
		final Timer t=new Timer(old.proc,old.arg);h[0]=t;synchronized(timers){timers.put(id,t);}
		Thread th=new Thread(()->{try{Thread.sleep(Math.max(0,millis));}catch(InterruptedException e){return;}
			if(t.cancelled||shutDown)return;
			tb.later(()->{if(t.cancelled)return;synchronized(timers){if(timers.get(id)==t)timers.remove(id);}try{guest.process(t.proc,t.arg);}catch(M68kException e){throw Toolbox68k.wrap(e);}});
		},"OT timer");th.setDaemon(true);th.start();
		return true;
	}
	private boolean cancelTimer(int id){Timer t;synchronized(timers){t=timers.remove(id);}if(t==null)return false;t.cancelled=true;return true;}
	private void destroyTimer(int id){cancelTimer(id);synchronized(timers){timerHandles.remove(id);}}

	// ------------------------------------------------------------------ utilities

	private final long startNanos=System.nanoTime();
	private long microseconds(){return (System.nanoTime()-startNanos)/1000;}
	private long readWide(int p) throws M68kCpuException {return ((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);}
	private void writeWide(int p,long v) throws M68kCpuException {memory.writeLong(p,(int)(v>>>32));memory.writeLong(p+4,(int)v);}
	private static int bool(boolean b){return b?1:0;}
	private int delay(int seconds) throws M68kException {await(null,()->false,Math.max(1,seconds)*1000L);return 0;}

	/** OTCreateOptions: an option string ("NoDelay=1 KeepAlive=1") as option buffer entries (the ones we know by name). */
	private int createOptions(int endPtName,int strPtr,int netbuf) throws M68kCpuException {
		int s=memory.readLong(strPtr);String text=cString(s);
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(String item:text.split("[ ,]+")){
			int eq=item.indexOf('=');if(eq<=0)continue;
			String k=item.substring(0,eq).trim().toLowerCase(Locale.ROOT);int v;try{v=Integer.parseInt(item.substring(eq+1).trim());}catch(NumberFormatException e){v=item.substring(eq+1).trim().equalsIgnoreCase("true")?1:0;}
			int level,name;
			switch(k){case "nodelay":level=INET_TCP;name=TCP_NODELAY;break;case "keepalive":level=INET_TCP;name=OPT_KEEPALIVE;break;case "reuseaddr":level=INET_IP;name=IP_REUSEADDR;break;
				case "broadcast":level=INET_IP;name=IP_BROADCAST;break;case "ttl":level=INET_IP;name=IP_TTL;break;case "rcvbuf":level=XTI_GENERIC;name=XTI_RCVBUF;break;case "sndbuf":level=XTI_GENERIC;name=XTI_SNDBUF;break;default:continue;}
			byte[] o=new byte[20];put32(o,0,20);put32(o,4,level);put32(o,8,name);put32(o,16,v);out.write(o,0,20);
		}
		byte[] b=out.toByteArray();
		memory.writeLong(strPtr,s+text.length());
		return writeNetbuf(netbuf,b,0,b.length)>0?BUFFER_OVERFLOW:NO_ERR;
	}
	private int nextOption(int buffer,int buflen,int prevPtr) throws M68kCpuException {
		int prev=memory.readLong(prevPtr);int next;
		if(prev==0)next=buffer;else{int len=memory.readLong(prev);next=prev+((len+3)&~3);}
		if(next+16>buffer+buflen){memory.writeLong(prevPtr,0);return NOT_FOUND;}
		memory.writeLong(prevPtr,next);return NO_ERR;
	}
	private int findOption(int buffer,int buflen,int level,int name) throws M68kCpuException {
		for(int o=buffer;o+16<=buffer+buflen;){int len=memory.readLong(o);if(len<16)break;if(memory.readLong(o+4)==level&&memory.readLong(o+8)==name)return o;o+=(len+3)&~3;}
		return 0;
	}
	/** OTPortRef packing: device type (10 bits), bus (4), slot (8), other (10). */
	private static int portRef(int bus,int dev,int slot,int other){return (dev&0x3ff)<<22|(bus&0xf)<<18|(slot&0xff)<<10|other&0x3ff;}
	private static final int ETHERNET_PORT=portRef(1,10,0,0);
	private boolean portRecord(int rec) throws M68kCpuException {
		memory.fill(rec,320,0);
		memory.writeLong(rec,ETHERNET_PORT);memory.writeLong(rec+4,1);memory.writeLong(rec+8,2);
		writeCString(rec+24,"enet",36);writeCString(rec+60,"enet",32);writeCString(rec+92,"Built-in",8);
		return true;
	}

	// ------------------------------------------------------------------ the function table

	private void define(){
		// client
		def("InitOpenTransportPriv","4:44",a->{initialized=true;if(a[1]!=0){memory.writeLong(a[1],clientContext());memory.writeLong(a[1]+4,clientId);}return NO_ERR;});
		def("InitOpenTransportInContext","4:44",a->{initialized=true;if(a[1]!=0)memory.writeLong(a[1],clientContext());return NO_ERR;});
		def("InitOpenTransport","4:",a->{initialized=true;return NO_ERR;});
		def("InitOpenTransportUtilities","4:",a->NO_ERR);
		def("CloseOpenTransportPriv","v:4",a->{closeAll();return 0;});
		alias("CloseOpenTransportInContext","CloseOpenTransportPriv");
		def("CloseOpenTransport","v:",a->{closeAll();return 0;});
		def("OTExitPatchPriv","v:4",a->{closeAll();return 0;});
		def("OTRegisterAsClientPriv","4:444",a->NO_ERR);
		alias("OTRegisterAsClientInContext","OTRegisterAsClientPriv");
		def("OTRegisterAsClient","4:44",a->NO_ERR);
		def("OTUnregisterAsClientPriv","4:4",a->NO_ERR);
		alias("OTUnregisterAsClientInContext","OTUnregisterAsClientPriv");
		def("OTUnregisterAsClient","4:",a->NO_ERR);
		def("OTWhoAmI","4:",a->clientId);
		def("OTSetMemoryLimits","4:44",a->NO_ERR);
		def("OTAllocMemPriv","4:44",a->allocate(a[0]));
		alias("OTAllocMemInContext","OTAllocMemPriv");
		def("OTAllocMem","4:4",a->allocate(a[0]));
		def("OTFreeMemPriv","v:44",a->{free(a[0]);return 0;});
		def("OTFreeMem","v:4",a->{free(a[0]);return 0;});
		def("OTEnterInterrupt","v:",a->0);
		def("OTLeaveInterrupt","v:",a->0);
		def("OTIsAtInterruptLevel","B:",a->0);
		def("OTCanLoadLibraries","B:",a->1);
		def("OTCanMakeSyncCall","B:",a->1);
		// tasks
		def("OTCreateDeferredTaskPriv","4:444",a->createTask(a[0],a[1]));
		alias("OTCreateDeferredTaskInContext","OTCreateDeferredTaskPriv");
		def("OTCreateDeferredTask","4:44",a->createTask(a[0],a[1]));
		def("OTScheduleDeferredTask","B:4",a->bool(scheduleTask(a[0],false)));
		alias("OTScheduleInterruptTask","OTScheduleDeferredTask");
		def("OTDestroyDeferredTask","4:4",a->destroyTask(a[0]));
		def("OTCreateSystemTaskPriv","4:444",a->createTask(a[0],a[1]));
		alias("OTCreateSystemTaskInContext","OTCreateSystemTaskPriv");
		def("OTCreateSystemTask","4:44",a->createTask(a[0],a[1]));
		def("OTScheduleSystemTask","B:4",a->bool(scheduleTask(a[0],true)));
		def("OTCancelSystemTask","B:4",a->bool(cancelTask(a[0])));
		def("OTDestroySystemTask","4:4",a->destroyTask(a[0]));
		def("OTCreateTimerTask","4:44",a->createTimer(a[0],a[1]));
		def("OTCreateTimerTaskInContext","4:444",a->createTimer(a[0],a[1]));
		def("OTScheduleTimerTask","B:44",a->bool(scheduleTimer(a[0],a[1])));
		def("OTCancelTimerTask","B:4",a->bool(cancelTimer(a[0])));
		def("OTDestroyTimerTask","v:4",a->{destroyTimer(a[0]);return 0;});
		// configurations and providers
		def("OTCreateConfiguration","4:4",a->createConfiguration(cString(a[0])));
		def("OTCloneConfiguration","4:4",a->{String s;synchronized(configurations){s=configurations.get(a[0]);}return s==null?CONFIG_INVALID:createConfiguration(s);});
		def("OTDestroyConfiguration","v:4",a->{takeConfiguration(a[0]);return 0;});
		def("OTOpenEndpointPriv","4:44444",a->open(a[0],a[2],a[3],TCP));
		alias("OTOpenEndpointInContext","OTOpenEndpointPriv");
		def("OTOpenEndpoint","4:4444",a->open(a[0],a[2],a[3],TCP));
		def("OTAsyncOpenEndpointPriv","4:444444",a->asyncOpen(a[0],a[2],a[3],a[4],TCP));
		alias("OTAsyncOpenEndpointInContext","OTAsyncOpenEndpointPriv");
		def("OTAsyncOpenEndpoint","4:44444",a->asyncOpen(a[0],a[2],a[3],a[4],TCP));
		def("OTOpenInternetServicesPriv","4:4444",a->open(a[0],0,a[2],INET_SERVICES));
		alias("OTOpenInternetServicesInContext","OTOpenInternetServicesPriv");
		def("OTOpenInternetServices","4:444",a->open(a[0],0,a[2],INET_SERVICES));
		def("OTAsyncOpenInternetServicesPriv","4:44444",a->asyncOpen(a[0],0,a[2],a[3],INET_SERVICES));
		alias("OTAsyncOpenInternetServicesInContext","OTAsyncOpenInternetServicesPriv");
		def("OTAsyncOpenInternetServices","4:4444",a->asyncOpen(a[0],0,a[2],a[3],INET_SERVICES));
		def("OTOpenMapperPriv","4:4444",a->open(a[0],0,a[2],MAPPER));
		alias("OTOpenMapperInContext","OTOpenMapperPriv");
		def("OTOpenMapper","4:444",a->open(a[0],0,a[2],MAPPER));
		def("OTAsyncOpenMapperPriv","4:44444",a->asyncOpen(a[0],0,a[2],a[3],MAPPER));
		alias("OTAsyncOpenMapperInContext","OTAsyncOpenMapperPriv");
		def("OTAsyncOpenMapper","4:4444",a->asyncOpen(a[0],0,a[2],a[3],MAPPER));
		def("OTOpenProviderPriv","4:4444",a->{takeConfiguration(a[0]);if(a[2]!=0)memory.writeLong(a[2],NOT_SUPPORTED);return 0;});
		alias("OTOpenProviderInContext","OTOpenProviderPriv");
		def("OTOpenProvider","4:444",a->{takeConfiguration(a[0]);if(a[2]!=0)memory.writeLong(a[2],NOT_SUPPORTED);return 0;});
		def("OTAsyncOpenProviderPriv","4:44444",a->{takeConfiguration(a[0]);return NOT_SUPPORTED;});
		alias("OTAsyncOpenProviderInContext","OTAsyncOpenProviderPriv");
		def("OTAsyncOpenProvider","4:4444",a->{takeConfiguration(a[0]);return NOT_SUPPORTED;});
		def("OTCloseProviderPriv","4:44",a->closeProvider(a[0]));
		def("OTCloseProvider","4:4",a->closeProvider(a[0]));
		def("OTTransferOwnershipPriv","4:4444",a->{if(a[2]!=0)memory.writeLong(a[2],NO_ERR);return a[0];});
		def("OTTransferProviderOwnership","4:444",a->{if(a[2]!=0)memory.writeLong(a[2],NO_ERR);return a[0];});
		def("OTGetProviderPortRef","4:4",a->provider(a[0])==null?0:ETHERNET_PORT);
		def("OTSetSynchronous","4:4",a->setMode(a[0],0));
		def("OTSetAsynchronous","4:4",a->setMode(a[0],1));
		def("OTSetBlocking","4:4",a->setMode(a[0],2));
		def("OTSetNonBlocking","4:4",a->setMode(a[0],3));
		def("OTAckSends","4:4",a->setMode(a[0],4));
		def("OTDontAckSends","4:4",a->setMode(a[0],5));
		def("OTIsSynchronous","B:4",a->{Provider p=provider(a[0]);return bool(p!=null&&p.sync);});
		def("OTIsBlocking","B:4",a->{Provider p=provider(a[0]);return bool(p!=null&&p.blocking);});
		def("OTIsAckingSends","B:4",a->{Provider p=provider(a[0]);return bool(p!=null&&p.ackSends);});
		def("OTInstallNotifier","4:444",a->installNotifier(a[0],a[1],a[2]));
		def("OTRemoveNotifier","v:4",a->{Provider p=provider(a[0]);if(p!=null){p.notifier=0;p.held.clear();}return 0;});
		def("OTUseSyncIdleEvents","4:41",a->{Provider p=provider(a[0]);if(p==null)return BAD_REFERENCE;p.syncIdle=(a[1]&0xff)!=0||(a[1]&0xff00)!=0;return NO_ERR;});
		def("OTEnterNotifier","B:4",a->{Provider p=provider(a[0]);if(p==null)return 0;p.enterDepth++;return 1;});
		def("OTLeaveNotifier","v:4",a->{Provider p=provider(a[0]);if(p!=null&&p.enterDepth>0&&--p.enterDepth==0&&!p.inNotifier)releaseHeld(p);return 0;});
		def("OTCancelSynchronousCalls","4:44",a->{Provider p=provider(a[0]);if(p==null)return BAD_REFERENCE;p.cancel=a[1]==0?CANCELED:a[1];wake();return NO_ERR;});
		def("OTIoctl","4:444",a->ioctl(a[0],a[1],a[2]));
		for(String n:new String[]{"OTGetMessage:4:4444","OTGetPriorityMessage:4:44444","OTPutMessage:4:4444","OTPutPriorityMessage:4:44444"}){String[] s=n.split(":",2);def(s[0],s[1],a->NOT_SUPPORTED);}
		// endpoints
		def("OTGetEndpointInfo","4:44",a->{Provider p=provider(a[0]);if(p==null)return BAD_REFERENCE;writeInfo(p,a[1]);return NO_ERR;});
		def("OTGetEndpointState","4:4",a->{Provider p=provider(a[0]);return p==null?BAD_REFERENCE:p.state;});
		def("OTLook","4:4",a->look(a[0]));
		def("OTSync","4:4",a->{Provider p=provider(a[0]);if(p==null)return BAD_REFERENCE;if(!p.sync){post(p,T_SYNCCOMPLETE,p.state,0);return NO_ERR;}return p.state;});
		def("OTCountDataBytes","4:44",a->countDataBytes(a[0],a[1]));
		def("OTGetProtAddress","4:444",a->getProtAddress(a[0],a[1],a[2]));
		def("OTResolveAddress","4:4444",a->resolveAddress(a[0],a[1],a[2],a[3]));
		def("OTAllocPriv","4:44444",a->otAlloc(a[0],a[1],a[2],a[3]));
		alias("OTAllocInContext","OTAllocPriv");
		def("OTAlloc","4:4444",a->otAlloc(a[0],a[1],a[2],a[3]));
		def("OTFree","4:44",a->otFree(a[0],a[1]));
		def("OTOptionManagement","4:444",a->optionManagement(a[0],a[1],a[2]));
		def("OTCreateOptions","4:444",a->createOptions(a[0],a[1],a[2]));
		def("OTCreateOptionString","4:44444",a->{if(a[3]!=0&&a[4]>0)memory.writeByte(a[3],0);return NO_ERR;});
		def("OTNextOption","4:444",a->nextOption(a[0],a[1],a[2]));
		def("OTFindOption","4:4444",a->findOption(a[0],a[1],a[2],a[3]));
		def("OTBind","4:444",a->bind(a[0],a[1],a[2]));
		def("OTUnbind","4:4",a->unbind(a[0]));
		def("OTConnect","4:444",a->connect(a[0],a[1],a[2]));
		def("OTRcvConnect","4:44",a->rcvConnect(a[0],a[1]));
		def("OTListen","4:44",a->listen(a[0],a[1]));
		def("OTAccept","4:444",a->accept(a[0],a[1],a[2]));
		def("OTSndDisconnect","4:44",a->sndDisconnect(a[0],a[1]));
		def("OTSndOrderlyDisconnect","4:4",a->sndOrderlyDisconnect(a[0]));
		def("OTRcvDisconnect","4:44",a->rcvDisconnect(a[0],a[1]));
		def("OTRcvOrderlyDisconnect","4:4",a->rcvOrderlyDisconnect(a[0]));
		def("OTRcv","4:4444",a->rcv(a[0],a[1],a[2],a[3]));
		def("OTSnd","4:4444",a->snd(a[0],a[1],a[2],a[3]));
		def("OTSndUData","4:44",a->sndUData(a[0],a[1]));
		def("OTRcvUData","4:444",a->rcvUData(a[0],a[1],a[2]));
		def("OTRcvUDErr","4:44",a->{Provider p=provider(a[0]);return p==null?BAD_REFERENCE:NO_UDERR;});
		for(String n:new String[]{"OTSndRequest:4:444","OTRcvReply:4:444","OTSndReply:4:444","OTRcvRequest:4:444","OTCancelRequest:4:44","OTCancelReply:4:44",
				"OTSndURequest:4:444","OTRcvUReply:4:444","OTSndUReply:4:444","OTRcvURequest:4:444","OTCancelURequest:4:44","OTCancelUReply:4:44"}){String[] s=n.split(":",2);def(s[0],s[1],a->NOT_SUPPORTED);}
		// mapper
		def("OTRegisterName","4:444",a->registerName(a[0],a[1],a[2]));
		def("OTDeleteName","4:44",a->deleteName(a[0],a[1]));
		def("OTDeleteNameByID","4:44",a->deleteName(a[0],a[1]));
		def("OTLookupName","4:444",a->lookupName(a[0],a[1],a[2]));
		// Internet
		def("OTInitInetAddress","v:424",a->{memory.writeWord(a[0],AF_INET);memory.writeWord(a[0]+2,a[1]);memory.writeLong(a[0]+4,a[2]);memory.writeLong(a[0]+8,0);memory.writeLong(a[0]+12,0);return 0;});
		def("OTInitDNSAddress","4:44",a->{String s=cString(a[1]);memory.writeWord(a[0],AF_DNS);writeCString(a[0]+2,s,256);return Math.min(s.length(),255)+3;});
		def("OTInetStringToHost","4:44",a->{Integer ip=parseDotted(cString(a[0]));if(ip==null)return BAD_ADDRESS;if(a[1]!=0)memory.writeLong(a[1],ip);return NO_ERR;});
		def("OTInetHostToString","v:44",a->{writeCString(a[1],dotted(a[0]),16);return 0;});
		def("OTInetGetInterfaceInfo","4:44",a->interfaceInfo(a[0],a[1]));
		def("OTInetGetSecondaryAddresses","4:444",a->{if(a[1]!=0)memory.writeLong(a[1],0);return NO_ERR;});
		def("OTInetGetDHCPConfigInfo","4:4444",a->NOT_FOUND);
		def("OTInetStringToAddress","4:444",a->stringToAddress(a[0],a[1],a[2]));
		def("OTInetAddressToName","4:444",a->addressToName(a[0],a[1],a[2]));
		def("OTInetSysInfo","4:444",a->sysInfo(a[0],a[1],a[2]));
		def("OTInetMailExchange","4:4444",a->mailExchange(a[0],a[1],a[2],a[3]));
		def("OTInetQuery","4:442244444",a->NOT_SUPPORTED);
		// ports
		def("OTGetIndexedPort","B:44",a->bool(a[1]==0&&portRecord(a[0])));
		def("OTFindPort","B:44",a->{String n=cString(a[1]);return bool(n.startsWith("enet")&&portRecord(a[0]));});
		def("OTFindPortByRef","B:44",a->bool(a[1]==ETHERNET_PORT&&portRecord(a[0])));
		def("OTCreatePortRef","4:1222",a->portRef(a[0]&0xff,a[1],a[2],a[3]));
		def("OTGetDeviceTypeFromPortRef","2:4",a->a[0]>>>22&0x3ff);
		def("OTGetBusTypeFromPortRef","2:4",a->a[0]>>>18&0xf);
		def("OTGetSlotFromPortRef","2:44",a->{if(a[1]!=0)memory.writeWord(a[1],a[0]&0x3ff);return a[0]>>>10&0xff;});
		def("OTSetDeviceTypeInPortRef","4:42",a->(a[0]&~(0x3ff<<22))|(a[1]&0x3ff)<<22);
		def("OTSetBusTypeInPortRef","4:41",a->(a[0]&~(0xf<<18))|(a[1]&0xf)<<18);
		def("OTRegisterPort","4:44",a->NOT_SUPPORTED);
		def("OTUnregisterPort","4:44",a->NOT_FOUND);
		def("OTChangePortState","4:444",a->NO_ERR);
		// utilities
		def("OTMemcpy","v:444",a->{if(a[2]>0)memory.load(a[0],memory.readBytes(a[1],a[2]));return 0;});
		alias("OTMemmove","OTMemcpy");
		def("OTMemcmp","B:444",a->bool(a[2]<=0||java.util.Arrays.equals(memory.readBytes(a[0],a[2]),memory.readBytes(a[1],a[2]))));
		def("OTMemzero","v:44",a->{if(a[1]>0)memory.fill(a[0],a[1],0);return 0;});
		def("OTMemset","v:444",a->{if(a[2]>0)memory.fill(a[0],a[2],a[1]&0xff);return 0;});
		def("OTStrLength","4:4",a->{int n=0;while(memory.readByte(a[0]+n)!=0&&n<1<<20)n++;return n;});
		def("OTStrCopy","v:44",a->{byte[] b=memory.readCString(a[1],1<<20);memory.load(a[0],b);memory.writeByte(a[0]+b.length,0);return 0;});
		def("OTStrCat","v:44",a->{int n=0;while(memory.readByte(a[0]+n)!=0&&n<1<<20)n++;byte[] b=memory.readCString(a[1],1<<20);memory.load(a[0]+n,b);memory.writeByte(a[0]+n+b.length,0);return 0;});
		def("OTStrEqual","B:44",a->bool(java.util.Arrays.equals(memory.readCString(a[0],1<<20),memory.readCString(a[1],1<<20))));
		def("OTGetTimeStamp","v:4",a->{writeWide(a[0],microseconds());return 0;});
		def("OTSubtractTimeStamps","4:444",a->{writeWide(a[0],readWide(a[2])-readWide(a[1]));return a[0];});
		def("OTTimeStampInMilliseconds","4:4",a->(int)(readWide(a[0])/1000));
		def("OTTimeStampInMicroseconds","4:4",a->(int)readWide(a[0]));
		def("OTElapsedMilliseconds","4:4",a->(int)((microseconds()-readWide(a[0]))/1000));
		def("OTElapsedMicroseconds","4:4",a->(int)(microseconds()-readWide(a[0])));
		def("OTGetClockTimeInSecs","4:",a->(int)(microseconds()/1000000));
		def("OTGetTimerTicks","4:",a->(int)(microseconds()/1000));
		def("OTDelay","v:4",a->delay(a[0]));
		def("OTIdle","v:",a->{tb.runDeferred();tb.runTimers();return 0;});
		def("OTEnterCriticalSection","v:",a->0);
		def("OTLeaveCriticalSection","v:",a->0);
		// lists (OTLink: fNext at 0; OTLIFO/OTList: fHead at 0)
		def("OTLIFOEnqueue","v:44",a->{memory.writeLong(a[1],memory.readLong(a[0]));memory.writeLong(a[0],a[1]);return 0;});
		def("OTLIFODequeue","4:4",a->{int h=memory.readLong(a[0]);if(h!=0)memory.writeLong(a[0],memory.readLong(h));return h;});
		def("OTLIFOStealList","4:4",a->{int h=memory.readLong(a[0]);memory.writeLong(a[0],0);return h;});
		def("OTReverseList","4:4",a->{int prev=0,cur=a[0];for(int guard=0;cur!=0&&guard<1<<20;guard++){int next=memory.readLong(cur);memory.writeLong(cur,prev);prev=cur;cur=next;}return prev;});
		alias("OTAddFirst","OTLIFOEnqueue");
		def("OTAddLast","v:44",a->{memory.writeLong(a[1],0);int at=a[0];for(int guard=0;memory.readLong(at)!=0&&guard<1<<20;guard++)at=memory.readLong(at);memory.writeLong(at,a[1]);return 0;});
		alias("OTRemoveFirst","OTLIFODequeue");
		def("OTRemoveLast","4:4",a->{int at=a[0];int cur=memory.readLong(at);if(cur==0)return 0;for(int guard=0;memory.readLong(cur)!=0&&guard<1<<20;guard++){at=cur;cur=memory.readLong(cur);}memory.writeLong(at,0);return cur;});
		def("OTGetFirst","4:4",a->memory.readLong(a[0]));
		def("OTGetLast","4:4",a->{int cur=memory.readLong(a[0]);if(cur==0)return 0;for(int guard=0;memory.readLong(cur)!=0&&guard<1<<20;guard++)cur=memory.readLong(cur);return cur;});
		def("OTIsInList","B:44",a->{for(int cur=memory.readLong(a[0]),guard=0;cur!=0&&guard<1<<20;guard++,cur=memory.readLong(cur))if(cur==a[1])return 1;return 0;});
		def("OTFindLink","4:444",a->{for(int cur=memory.readLong(a[0]),guard=0;cur!=0&&guard<1<<20;guard++,cur=memory.readLong(cur))if(guest.listSearch(a[1],a[2],cur))return cur;return 0;});
		def("OTRemoveLink","B:44",a->bool(unlink(a[0],a[1])));
		def("OTFindAndRemoveLink","4:444",a->{for(int cur=memory.readLong(a[0]),guard=0;cur!=0&&guard<1<<20;guard++,cur=memory.readLong(cur))if(guest.listSearch(a[1],a[2],cur)){unlink(a[0],cur);return cur;}return 0;});
		def("OTGetIndexedLink","4:44",a->{int cur=memory.readLong(a[0]);for(int i=0;i<a[1]&&cur!=0;i++)cur=memory.readLong(cur);return cur;});
		def("OTEnqueue","v:444",a->{memory.writeLong(a[1]+a[2],memory.readLong(a[0]));memory.writeLong(a[0],a[1]);return 0;});
		def("OTDequeue","4:44",a->{int o=memory.readLong(a[0]);if(o!=0)memory.writeLong(a[0],memory.readLong(o+a[1]));return o;});
		// atomic operations (one thread runs the program: plain reads and writes; bit 0 = least significant)
		def("OTAtomicSetBit","B:44",a->{int b=memory.readByte(a[0]),m=1<<(a[1]&7);memory.writeByte(a[0],b|m);return bool((b&m)!=0);});
		def("OTAtomicClearBit","B:44",a->{int b=memory.readByte(a[0]),m=1<<(a[1]&7);memory.writeByte(a[0],b&~m);return bool((b&m)!=0);});
		def("OTAtomicTestBit","B:44",a->bool((memory.readByte(a[0])&1<<(a[1]&7))!=0));
		def("OTAtomicAdd32","4:44",a->{int v=memory.readLong(a[1])+a[0];memory.writeLong(a[1],v);return v;});
		def("OTAtomicAdd16","2:44",a->{int v=(short)(memory.readWord(a[1])+a[0]);memory.writeWord(a[1],v);return v;});
		def("OTAtomicAdd8","4:44",a->{int v=(byte)(memory.readByte(a[1])+a[0]);memory.writeByte(a[1],v);return v;});
		def("OTCompareAndSwapPtr","B:444",a->{if(memory.readLong(a[2])!=a[0])return 0;memory.writeLong(a[2],a[1]);return 1;});
		alias("OTCompareAndSwap32","OTCompareAndSwapPtr");
		def("OTCompareAndSwap16","B:444",a->{if(memory.readWord(a[2])!=(a[0]&0xffff))return 0;memory.writeWord(a[2],a[1]);return 1;});
		def("OTCompareAndSwap8","B:444",a->{if(memory.readByte(a[2])!=(a[0]&0xff))return 0;memory.writeByte(a[2],a[1]);return 1;});
		// no-copy receive buffers
		def("OTReleaseBuffer","v:4",a->{for(int b=a[0],guard=0;b!=0&&guard<4096;guard++){int next=memory.readLong(b+8);free(b);b=next;}return 0;});
		def("OTBufferDataSize","4:4",a->{int n=0;for(int b=a[0],guard=0;b!=0&&guard<4096;guard++){n+=memory.readLong(b+16);b=memory.readLong(b+8);}return n;});
		def("OTReadBuffer","B:444",a->{ // OTBufferInfo {fBuffer, fOffset, fPad}: copies *len bytes on from the current position
			int info=a[0],buf=memory.readLong(info),off=memory.readLong(info+4),want=memory.readLong(a[2]),got=0;
			while(buf!=0&&got<want){int len=memory.readLong(buf+16);if(off>=len){buf=memory.readLong(buf+8);off=0;continue;}int n=Math.min(len-off,want-got);memory.load(a[1]+got,memory.readBytes(memory.readLong(buf+12)+off,n));got+=n;off+=n;}
			memory.writeLong(info,buf);memory.writeLong(info+4,off);memory.writeLong(a[2],got);return bool(got==want);});
	}
	private boolean unlink(int list,int link) throws M68kCpuException {
		int at=list;for(int cur=memory.readLong(at),guard=0;cur!=0&&guard<1<<20;guard++){if(cur==link){memory.writeLong(at,memory.readLong(cur));return true;}at=cur;cur=memory.readLong(cur);}
		return false;
	}

	// ------------------------------------------------------------------ a byte queue

	/** The received bytes of a connection, in order. */
	static final class ByteQueue {
		private byte[] buf=new byte[8192];private int head,size;
		int available(){return size;}
		void clear(){head=0;size=0;}
		void append(byte[] b,int off,int n){
			if(size+n>buf.length){byte[] nb=new byte[Math.max(buf.length*2,size+n)];for(int i=0;i<size;i++)nb[i]=buf[(head+i)%buf.length];buf=nb;head=0;}
			for(int i=0;i<n;i++)buf[(head+size+i)%buf.length]=b[off+i];
			size+=n;
		}
		int take(byte[] dst,int max){int n=Math.min(max,size);for(int i=0;i<n;i++)dst[i]=buf[(head+i)%buf.length];head=(head+n)%buf.length;size-=n;if(size==0)head=0;return n;}
	}
}

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

/**
 * MacTCP compatibility: the ".IPP" driver (TCP and UDP streams over Java
 * sockets, with asynchronous completion and notification routines) and the
 * Domain Name Resolver code resource ('dnrp'), so classic networking programs
 * can use the host's TCP/IP stack.
 */
final class MacTCP68k {
	static final int IN_PROGRESS=1,NO_ERR=0,IP_BAD_ADDR=-23004,CONNECTION_CLOSING=-23005,INVALID_LENGTH=-23006,CONNECTION_DOESNT_EXIST=-23008,INSUFFICIENT_RESOURCES=-23009,INVALID_STREAM=-23010,STREAM_ALREADY_OPEN=-23011,CONNECTION_TERMINATED=-23012,INVALID_BUF=-23013,OPEN_FAILED=-23015,COMMAND_TIMEOUT=-23016,DUPLICATE_SOCKET=-23017,NAME_SYNTAX_ERR=-23041,CACHE_FAULT=-23042,NO_NAME_SERVER=-23044,AUTH_NAME_ERR=-23045,NO_ANS_ERR=-23046,DNR_ERR=-23047;
	private static final int DRIVER_REF=-32;
	private static final int CS_CODE=26,STREAM=28,CS=32;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,Stream> streams=new HashMap<Integer,Stream>();
	/** The TCP/UDP streams open now (an external that created one keeps its VM: Xcmd68k). */
	int openStreams(){return streams.size();}
	private int nextStream=0x00fe0000;
	private int dnrGate;
	private boolean resolverOpen;
	private volatile boolean shutDown;

	final class Stream {
		final int pointer;boolean udp;int notifyProc,userData,rcvBuff,rcvBuffLen,localPort,remoteHost,remotePort,localHost;
		Socket socket;ServerSocket server;DatagramSocket datagram;InputStream in;OutputStream out;
		final ByteArrayOutputStream buffer=new ByteArrayOutputStream();boolean remoteClosed,terminated,closing;int state;Thread reader;
		/** The TCPClosing ASR has been sent (see {@link #closingIfDrained}). */
		boolean closingNotified;
		Waiter waiting;Listener listener;
		/** Asynchronous UDPReads still waiting: a release completes them at once (see {@link #tcpRelease}). */
		final java.util.Set<Integer> pendingReads=java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<Integer>());
		/** An asynchronous TCPActiveOpen whose completion routine has not run yet: TCPStatus keeps reporting SYN-sent (6) until then, as MacTCP
		 *  completes the call the moment the connection is established; otherwise a client may reuse the open parameter block before a late completion. */
		volatile boolean opening;
		int rdsBase;// offset in rcvBuff used for TCPNoCopyRcv
		java.util.ArrayDeque<DatagramPacket> packets=new java.util.ArrayDeque<DatagramPacket>();
		Stream(int pointer){this.pointer=pointer;}
	}

	MacTCP68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	int driverRefNum(){return DRIVER_REF;}
	int version(){return 0x0203;}

	/** Installs the 'dnrp' stub (JMP to the host gate) into the System resource file. */
	void installDnr(ResourceFile68k system){
		if(dnrGate==0)dnrGate=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu cpu) throws M68kException {dnr(cpu);}});
		byte[] stub={0x4e,(byte)0xf9,(byte)(dnrGate>>>24),(byte)(dnrGate>>>16),(byte)(dnrGate>>>8),(byte)dnrGate,0x4e,0x75};
		system.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("dnrp"),1,"MacTCP DNR",0,stub));
	}

	void shutDown(){shutDown=true;for(Stream s:streams.values())closeStream(s);streams.clear();}

	// ------------------------------------------------------------------ driver control calls

	boolean control(M68kCpu cpu,int pb,int trap,boolean async) throws M68kException {
		if(trap==0x06){finish(pb,NO_ERR,async);return true;}
		int csCode=(short)memory.readWord(pb+CS_CODE);
		if(trap==0x05){ // Status: TCPStatus/UDP status use Control in MacTCP; treat the same
			return control(cpu,pb,0x04,async);
		}
		memory.writeWord(pb+16,IN_PROGRESS);cpu.d[0]=NO_ERR; // an asynchronous call answers noErr in D0 at once (queued); sync ones overwrite it when they finish
		if(Mac68kApi.TRACE)System.err.println("[tcp] control csCode "+csCode+" stream $"+Integer.toHexString(memory.readLong(pb+STREAM))+(async?" async":""));
		switch(csCode){
			case 15:{InetAddress local=localAddress();memory.writeLong(pb+28,toInt(local));memory.writeLong(pb+32,localNetmask);finish(pb,NO_ERR,async);return true;} // ipctlGetAddr
			case 30:return tcpCreate(pb,async);
			case 31:return tcpPassiveOpen(pb,async);
			case 32:return tcpActiveOpen(pb,async);
			case 34:return tcpSend(pb,async);
			case 35:return tcpReceive(pb,async,true);
			case 36:finish(pb,NO_ERR,async);return true;
			case 37:return tcpReceive(pb,async,false);
			case 38:return tcpClose(pb,async);
			case 39:return tcpAbort(pb,async);
			case 40:return tcpStatus(pb,async);
			case 41:return tcpStatus(pb,async);
			case 42:return tcpRelease(pb,async);
			case 43:{finish(pb,NO_ERR,async);return true;}
			case 20:return udpCreate(pb,async);
			case 21:return udpRead(pb,async);
			case 22:finish(pb,NO_ERR,async);return true;
			case 23:return udpWrite(pb,async);
			case 24:return tcpRelease(pb,async);
			case 25:memory.writeWord(pb+CS,1500);finish(pb,NO_ERR,async);return true;
			default:finish(pb,-17,async);return true;
		}
	}

	private void finish(int pb,int result,boolean async) throws M68kException {
		memory.writeWord(pb+16,result);tb.cpu.d[0]=(tb.cpu.d[0]&0xffff0000)|(result&0xffff);
		if(Mac68kApi.TRACE&&result!=NO_ERR)System.err.println("[tcp] csCode "+(short)memory.readWord(pb+CS_CODE)+" -> "+result);
		if(async)complete(pb);
	}
	/** Runs the ioCompletion routine of an asynchronous call. MacTCP's TCPIOCompletionProc/UDPIOCompletionProc take the
	 *  parameter block as a C stack argument (kCStackBased in MacTCP.h), not only in A0 as other drivers pass it: Netscape
	 *  Navigator 2.02 read a zero from its stack, logged "complete bad", never retired the call and hung at Quit waiting for
	 *  network operations. A0 and D0 are set as well for completion routines written against the Device Manager convention. */
	private int completionDepth;
	private void complete(final int pb) throws M68kException {
		int proc=memory.readLong(pb+12);if(proc==0)return;
		// A completion routine that issues the next asynchronous call and gets it back finished at once (a released stream, a datagram
		// already waiting) would recurse without end (Maven 2.0d23 at Quit: StackOverflowError); MacTCP completes such a call after
		// the routine has returned.
		if(completionDepth>0){tb.later(new Runnable(){@Override public void run(){try{complete(pb);}catch(M68kException e){}}});return;}
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		completionDepth++;
		try{cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.a[0]=pb;cpu.d[0]=(short)memory.readWord(pb+16);cpu.pushLong(pb);cpu.call(proc);}
		finally{completionDepth--;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}
	/** Completes an asynchronous call from a background thread: results are applied on the emulation thread. */
	private void completeLater(final int pb,final int result,final Runnable before){
		tb.later(new Runnable(){@Override public void run(){try{if(before!=null)before.run();memory.writeWord(pb+16,result);if(Mac68kApi.TRACE)System.err.println("[tcp] async csCode "+(short)memory.readWord(pb+CS_CODE)+" completed -> "+result);complete(pb);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});
	}
	/** Blocks a synchronous call until {@code done[0]}, while keeping timers and host requests alive. */
	private void waitFor(boolean[] done,long timeoutMillis) throws M68kException {
		long deadline=timeoutMillis<=0?Long.MAX_VALUE:System.currentTimeMillis()+timeoutMillis;
		while(!done[0]&&System.currentTimeMillis()<deadline&&!shutDown){
			tb.runDeferred();tb.runTimers();
			if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			synchronized(done){try{done.wait(5);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
		}
	}
	private static void signal(boolean[] done){synchronized(done){done[0]=true;done.notifyAll();}}

	private Stream stream(int pb) throws M68kCpuException {return streams.get(memory.readLong(pb+STREAM));}

	private boolean tcpCreate(int pb,boolean async) throws M68kException {
		Stream s=new Stream(nextStream+=0x10);
		s.rcvBuff=memory.readLong(pb+CS);s.rcvBuffLen=memory.readLong(pb+CS+4);s.notifyProc=memory.readLong(pb+CS+8);s.userData=memory.readLong(pb+CS+12);
		streams.put(s.pointer,s);memory.writeLong(pb+STREAM,s.pointer);
		finish(pb,NO_ERR,async);return true;
	}

	private boolean tcpActiveOpen(final int pb,boolean async) throws M68kException {
		final Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		if(s.socket!=null){finish(pb,STREAM_ALREADY_OPEN,async);return true;}
		final int host=memory.readLong(pb+CS+4);final int port=memory.readWord(pb+CS+8);int timeout=memory.readByte(pb+CS)&0xff;
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_CONNECT,dotted(host),port)){finish(pb,OPEN_FAILED,async);return true;}
		final boolean[] done={false};final int[] result={NO_ERR};
		s.state=6;s.opening=async;
		Thread t=new Thread(new Runnable(){@Override public void run(){
			try{Socket socket=new Socket();socket.connect(new InetSocketAddress(fromInt(host),mappedPort(port)),30000);socket.setTcpNoDelay(true);attach(s,socket);s.remotePort=port;}
			catch(IOException e){result[0]=OPEN_FAILED;s.state=0;if(Mac68kApi.TRACE)System.err.println("[tcp] active open $"+Integer.toHexString(host)+":"+port+" failed: "+e);}
			finally{signal(done);}
		}},"MacTCP open");t.setDaemon(true);t.start();
		if(async){waitAsync(done,pb,result,new Runnable(){@Override public void run(){s.opening=false;try{writeOpenResult(pb,s);}catch(M68kCpuException e){}}});return true;}
		waitFor(done,0);s.opening=false;writeOpenResult(pb,s);finish(pb,result[0],false);return true;
	}
	/** Debug aid (-Dhz68k.tcpportmap=21:2121[,80:8080]): connections to a remote port go to another one - test servers run as an
	 *  ordinary user cannot listen on the well-known ports that programs without a port setting (Anarchie) always use. */
	static int mappedPort(int port){
		String map=System.getProperty("hz68k.tcpportmap");if(map==null)return port;
		for(String pair:map.split(",")){String[] p=pair.trim().split(":");try{if(p.length==2&&Integer.parseInt(p[0].trim())==port)return Integer.parseInt(p[1].trim());}catch(NumberFormatException ignored){}}
		return port;
	}
	private void waitAsync(final boolean[] done,final int pb,final int[] result,final Runnable before){
		Thread w=new Thread(new Runnable(){@Override public void run(){synchronized(done){while(!done[0]&&!shutDown){try{done.wait(50);}catch(InterruptedException e){return;}}}completeLater(pb,result[0],before);}},"MacTCP completion");w.setDaemon(true);w.start();
	}
	private void writeOpenResult(int pb,Stream s) throws M68kCpuException {memory.writeLong(pb+CS+4,s.remoteHost);memory.writeWord(pb+CS+8,s.remotePort);memory.writeLong(pb+CS+10,s.localHost);memory.writeWord(pb+CS+14,s.localPort);}


	/** A passive open waiting for a connection on a shared listening port. */
	private static final class Waiter{final Stream s;final boolean[] done;final int[] result;Waiter(Stream s,boolean[] done,int[] result){this.s=s;this.done=done;this.result=result;}}
	private static final class Listener{final int port;final ServerSocket server;final java.util.ArrayDeque<Waiter> waiters=new java.util.ArrayDeque<Waiter>();Listener(int port,ServerSocket server){this.port=port;this.server=server;}}
	private final java.util.Map<Integer,Listener> listeners=new java.util.HashMap<Integer,Listener>();
	/** Hands each incoming connection to the oldest waiting passive open; the port closes with the last one (a refused connection while none waits). */
	private void startAcceptor(final Listener l){
		Thread t=new Thread(new Runnable(){@Override public void run(){
			try{while(true){
				Socket socket=l.server.accept();Waiter w;
				synchronized(listeners){w=l.waiters.poll();if(w!=null){w.s.waiting=null;w.s.listener=null;}}
				if(w==null){socket.close();continue;}
				try{attach(w.s,socket);}catch(IOException e){w.result[0]=OPEN_FAILED;w.s.state=0;}
				signal(w.done);
				synchronized(listeners){if(l.waiters.isEmpty()){l.server.close();listeners.remove(l.port);return;}}
			}}catch(IOException e){
				synchronized(listeners){Waiter w;while((w=l.waiters.poll())!=null){w.s.waiting=null;w.s.listener=null;if(!w.s.terminated)w.result[0]=OPEN_FAILED;w.s.state=0;signal(w.done);}listeners.remove(l.port,l);}
			}
		}},"MacTCP listen "+l.port);t.setDaemon(true);t.start();
	}
	private boolean tcpPassiveOpen(final int pb,boolean async) throws M68kException {
		final Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		final int port=memory.readWord(pb+CS+14);
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_LISTEN,"",port)){finish(pb,OPEN_FAILED,async);return true;}
		final boolean[] done={false};final int[] result={NO_ERR};
		// the listener is bound before the call returns, and the port it got (and this host's address) are in the parameter block at
		// once, as MacTCP fills localPort when it accepts the passive open: an FTP client puts them into its PORT command while the
		// asynchronous open is still pending - Anarchie 2.0 polls csParam.open.localPort and waited forever for a non-zero port
		if(port!=0){ // several passive opens on one local port (MacHTTP keeps a handful listening on 80) share one listener
			Waiter waiter=new Waiter(s,done,result);
			try{synchronized(listeners){
				Listener l=listeners.get(port);
				if(l==null||l.server.isClosed()){l=new Listener(port,new ServerSocket(port));listeners.put(port,l);startAcceptor(l);}
				l.waiters.add(waiter);s.waiting=waiter;s.listener=l;}}
			catch(IOException e){finish(pb,OPEN_FAILED,async);return true;}
			s.localPort=port;s.state=2;
		}else{
		final ServerSocket server;
		try{server=new ServerSocket(port);}catch(IOException e){finish(pb,OPEN_FAILED,async);return true;}
		s.server=server;s.localPort=server.getLocalPort();s.state=2;
		Thread t=new Thread(new Runnable(){@Override public void run(){
			try{Socket socket=server.accept();server.close();s.server=null;attach(s,socket);}
			catch(IOException e){if(!s.terminated)result[0]=OPEN_FAILED;s.state=0;}
			finally{signal(done);}
		}},"MacTCP listen");t.setDaemon(true);t.start();
		}
		InetAddress local=localAddress();if(local!=null&&s.localHost==0)s.localHost=toInt(local);
		memory.writeLong(pb+CS+10,s.localHost);memory.writeWord(pb+CS+14,s.localPort);
		if(async){waitAsync(done,pb,result,new Runnable(){@Override public void run(){try{writeOpenResult(pb,s);}catch(M68kCpuException e){}}});return true;}
		waitFor(done,0);writeOpenResult(pb,s);finish(pb,result[0],false);return true;
	}

	private void attach(final Stream s,Socket socket) throws IOException {
		s.socket=socket;s.in=socket.getInputStream();s.out=socket.getOutputStream();
		s.remoteHost=toInt(socket.getInetAddress());s.remotePort=socket.getPort();s.localHost=toInt(socket.getLocalAddress());s.localPort=socket.getLocalPort();s.state=8;
		s.reader=new Thread(new Runnable(){@Override public void run(){
			byte[] chunk=new byte[4096];
			try{int n;while((n=s.in.read(chunk))>=0){if(n==0)continue;synchronized(s.buffer){s.buffer.write(chunk,0,n);}notifyAsr(s,4,0);}
				synchronized(s.buffer){s.remoteClosed=true;s.state=s.closing?0:14;}closingIfDrained(s);} // after our own TCPClose both sides are done: closed, not "please close"
			catch(IOException e){if(!s.terminated){s.terminated=true;s.state=0;notifyAsr(s,3,2);}}
		}},"MacTCP reader");s.reader.setDaemon(true);s.reader.start();
	}

	/**
	 * TCPClosing goes to the ASR once the remote side has closed AND the program has received every byte that came before:
	 * MacTCP's "all data has been delivered". The UCSD socket library (MacWeb 2.0) marks the socket closed on that event and its
	 * recv answers end-of-file from then on without looking at unread data - sent at the FIN, MacWeb read nothing of a page.
	 */
	private void closingIfDrained(Stream s){
		synchronized(s.buffer){if(!s.remoteClosed||s.closingNotified||s.buffer.size()>0)return;s.closingNotified=true;}
		notifyAsr(s,1,0);
	}

	/** Schedules the stream's notification routine (ASR) on the emulation thread. */
	private void notifyAsr(final Stream s,final int code,final int reason){
		if(s.notifyProc==0||shutDown)return;
		tb.later(new Runnable(){@Override public void run(){
			try{M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
				// pascal void asr(StreamPtr, eventCode, userData, terminReason, ICMPReport*)
				cpu.pushLong(s.pointer);cpu.pushWord(code);cpu.pushLong(s.userData);cpu.pushWord(reason);cpu.pushLong(0);
				try{cpu.callPascal(s.notifyProc,16);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
			}catch(M68kException e){throw Toolbox68k.wrap(e);}
		}});
	}

	private boolean tcpSend(final int pb,boolean async) throws M68kException {
		final Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		if(s.out==null){finish(pb,CONNECTION_DOESNT_EXIST,async);return true;}
		int wds=memory.readLong(pb+CS+6);ByteArrayOutputStream data=new ByteArrayOutputStream();
		while(wds!=0){int length=memory.readWord(wds);if(length==0)break;int ptr=memory.readLong(wds+2);data.write(memory.readBytes(ptr,length),0,length);wds+=6;}
		final byte[] bytes=data.toByteArray();
		final boolean[] done={false};final int[] result={NO_ERR};
		Thread t=new Thread(new Runnable(){@Override public void run(){try{s.out.write(bytes);s.out.flush();}catch(IOException e){result[0]=CONNECTION_TERMINATED;}finally{signal(done);}}},"MacTCP send");t.setDaemon(true);t.start();
		if(async){waitAsync(done,pb,result,null);return true;}
		waitFor(done,0);finish(pb,result[0],false);return true;
	}

	private boolean tcpReceive(final int pb,boolean async,final boolean noCopy) throws M68kException {
		final Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		final int timeoutSeconds=memory.readByte(pb+CS)&0xff;
		if(available(s)>0||s.remoteClosed||s.terminated){int r=deliver(pb,s,noCopy);finish(pb,r,async);return true;}
		if(s.socket==null){finish(pb,CONNECTION_DOESNT_EXIST,async);return true;}
		final boolean[] done={false};
		Thread t=new Thread(new Runnable(){@Override public void run(){long deadline=timeoutSeconds==0?Long.MAX_VALUE:System.currentTimeMillis()+timeoutSeconds*1000L;
			while(available(s)==0&&!s.remoteClosed&&!s.terminated&&System.currentTimeMillis()<deadline&&!shutDown){try{Thread.sleep(5);}catch(InterruptedException e){break;}}signal(done);}},"MacTCP receive");t.setDaemon(true);t.start();
		if(async){
			Thread w=new Thread(new Runnable(){@Override public void run(){synchronized(done){while(!done[0]&&!shutDown){try{done.wait(50);}catch(InterruptedException e){return;}}}
				tb.later(new Runnable(){@Override public void run(){try{int r=available(s)>0||s.remoteClosed||s.terminated?deliver(pb,s,noCopy):COMMAND_TIMEOUT;memory.writeWord(pb+16,r);complete(pb);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});}},"MacTCP completion");w.setDaemon(true);w.start();
			return true;
		}
		waitFor(done,0);
		int r=available(s)>0||s.remoteClosed||s.terminated?deliver(pb,s,noCopy):COMMAND_TIMEOUT;finish(pb,r,false);return true;
	}
	private int available(Stream s){synchronized(s.buffer){return s.buffer.size();}}
	private byte[] take(Stream s,int max){synchronized(s.buffer){byte[] all=s.buffer.toByteArray();int n=Math.min(max,all.length);s.buffer.reset();if(n<all.length)s.buffer.write(all,n,all.length-n);return java.util.Arrays.copyOf(all,n);}}

	private int deliver(int pb,Stream s,boolean noCopy) throws M68kCpuException {
		if(available(s)==0){if(s.terminated)return CONNECTION_TERMINATED;if(s.remoteClosed)return CONNECTION_CLOSING;}
		if(!noCopy){int buff=memory.readLong(pb+CS+4),len=memory.readWord(pb+CS+8);byte[] data=take(s,Math.max(0,len));memory.load(buff,data);memory.writeWord(pb+CS+8,data.length);memory.writeByte(pb+CS+2,0);closingIfDrained(s);return NO_ERR;}
		int rds=memory.readLong(pb+CS+10),slots=memory.readWord(pb+CS+14);
		if(rds==0||slots<1||s.rcvBuff==0||s.rcvBuffLen<64){return INVALID_BUF;}
		int capacity=Math.max(64,s.rcvBuffLen/2);
		byte[] data=take(s,capacity);closingIfDrained(s);
		int base=s.rcvBuff+s.rdsBase;if(s.rdsBase+data.length>s.rcvBuffLen){s.rdsBase=0;base=s.rcvBuff;}
		memory.load(base,data);s.rdsBase=(s.rdsBase+data.length+1)&~1;if(s.rdsBase>=s.rcvBuffLen-64)s.rdsBase=0;
		memory.writeWord(rds,data.length);memory.writeLong(rds+2,base);memory.writeWord(rds+6,0);
		if(Mac68kApi.TRACE)System.err.println("[tcp] noCopyRcv "+data.length+" bytes at $"+Integer.toHexString(base)+" rds $"+Integer.toHexString(rds)+" slots "+slots);
		return NO_ERR;
	}

	private boolean tcpClose(int pb,boolean async) throws M68kException {
		Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		s.closing=true;s.state=s.remoteClosed?0:12; // the host socket ACKs at once: last-ack -> closed, FIN-wait-1 -> FIN-wait-2
		try{if(s.socket!=null)s.socket.shutdownOutput();}catch(IOException ignored){}
		finish(pb,NO_ERR,async);return true;
	}
	private boolean tcpAbort(int pb,boolean async) throws M68kException {
		Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		s.terminated=true;closeStream(s);s.state=0;finish(pb,NO_ERR,async);return true;
	}
	private boolean tcpRelease(int pb,boolean async) throws M68kException {
		Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		s.terminated=true;closeStream(s);streams.remove(s.pointer);
		memory.writeLong(pb+CS,s.rcvBuff);memory.writeLong(pb+CS+4,s.rcvBuffLen);
		// MacTCP ends the reads still pending when the stream goes, before the release returns: letting them finish a moment later ran
		// Maven's completion routine after it had torn its state down (PPC code jumped to 0 at Quit)
		java.util.List<Integer> pending;synchronized(s.pendingReads){pending=new java.util.ArrayList<Integer>(s.pendingReads);s.pendingReads.clear();}
		for(int read:pending){memory.writeWord(read+16,COMMAND_TIMEOUT);complete(read);}
		finish(pb,NO_ERR,async);return true;
	}
	private void closeStream(Stream s){
		synchronized(listeners){Waiter w=s.waiting;Listener l=s.listener;
			if(w!=null&&l!=null){l.waiters.remove(w);s.waiting=null;s.listener=null;signal(w.done);
				if(l.waiters.isEmpty()){try{l.server.close();}catch(IOException ignored){}listeners.remove(l.port,l);}}}
		try{if(s.socket!=null)s.socket.close();}catch(IOException ignored){}
		try{if(s.server!=null)s.server.close();}catch(IOException ignored){}
		if(s.datagram!=null)s.datagram.close();
	}
	private boolean tcpStatus(int pb,boolean async) throws M68kException {
		Stream s=stream(pb);if(s==null){finish(pb,INVALID_STREAM,async);return true;}
		memory.writeLong(pb+CS+6,s.remoteHost);memory.writeWord(pb+CS+10,s.remotePort);memory.writeLong(pb+CS+12,s.localHost);memory.writeWord(pb+CS+16,s.localPort);
		memory.writeByte(pb+CS+20,s.opening?6:s.state);memory.writeWord(pb+CS+22,8192);memory.writeWord(pb+CS+24,8192);memory.writeWord(pb+CS+26,0);memory.writeWord(pb+CS+28,Math.min(0xffff,available(s)));
		memory.writeLong(pb+CS+58,1460);
		// A connection being opened (state 6, SYN sent) has a status too: clients may poll TCPStatus while their asynchronous
		// TCPActiveOpen is pending and took connectionDoesntExist for a refusal.
		finish(pb,s.socket==null&&s.server==null&&s.state==0?CONNECTION_DOESNT_EXIST:NO_ERR,async);return true;
	}

	// ------------------------------------------------------------------ UDP

	private boolean udpCreate(int pb,boolean async) throws M68kException {
		final Stream s=new Stream(nextStream+=0x10);s.udp=true;
		s.rcvBuff=memory.readLong(pb+CS);s.rcvBuffLen=memory.readLong(pb+CS+4);s.notifyProc=memory.readLong(pb+CS+8);s.localPort=memory.readWord(pb+CS+12);s.userData=memory.readLong(pb+CS+14);
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_UDP,"",s.localPort)){finish(pb,OPEN_FAILED,async);return true;}
		try{s.datagram=new DatagramSocket(s.localPort==0?null:new InetSocketAddress(s.localPort));s.localPort=s.datagram.getLocalPort();memory.writeWord(pb+CS+12,s.localPort);}
		catch(IOException e){finish(pb,DUPLICATE_SOCKET,async);return true;}
		streams.put(s.pointer,s);memory.writeLong(pb+STREAM,s.pointer);
		Thread reader=new Thread(new Runnable(){@Override public void run(){byte[] buf=new byte[65536];while(!s.terminated&&!shutDown){try{DatagramPacket p=new DatagramPacket(buf,buf.length);s.datagram.receive(p);byte[] copy=java.util.Arrays.copyOf(p.getData(),p.getLength());synchronized(s.packets){s.packets.add(new DatagramPacket(copy,copy.length,p.getAddress(),p.getPort()));}notifyAsr(s,4,0);}catch(IOException e){break;}}}},"MacUDP reader");
		reader.setDaemon(true);reader.start();
		finish(pb,NO_ERR,async);return true;
	}
	private boolean udpRead(final int pb,boolean async) throws M68kException {
		final Stream s=stream(pb);if(s==null||!s.udp){finish(pb,INVALID_STREAM,async);return true;}
		final int timeout=memory.readWord(pb+CS);
		final boolean[] done={false};
		Thread t=new Thread(new Runnable(){@Override public void run(){long deadline=timeout==0?Long.MAX_VALUE:System.currentTimeMillis()+timeout*1000L;while(!s.terminated&&System.currentTimeMillis()<deadline&&!shutDown){synchronized(s.packets){if(!s.packets.isEmpty())break;}try{Thread.sleep(5);}catch(InterruptedException e){break;}}signal(done);}},"MacUDP read");t.setDaemon(true);t.start();
		if(async){s.pendingReads.add(pb);Thread w=new Thread(new Runnable(){@Override public void run(){synchronized(done){while(!done[0]&&!shutDown){try{done.wait(50);}catch(InterruptedException e){return;}}}tb.later(new Runnable(){@Override public void run(){try{if(!s.pendingReads.remove(pb))return;memory.writeWord(pb+16,deliverPacket(pb,s));complete(pb);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});}},"MacUDP completion");w.setDaemon(true);w.start();return true;}
		waitFor(done,0);finish(pb,deliverPacket(pb,s),false);return true;
	}
	private int deliverPacket(int pb,Stream s) throws M68kCpuException {
		DatagramPacket p;synchronized(s.packets){p=s.packets.poll();}
		if(p==null)return COMMAND_TIMEOUT;
		int base=s.rcvBuff;if(base==0||s.rcvBuffLen<p.getLength())return INVALID_BUF;
		memory.load(base,java.util.Arrays.copyOf(p.getData(),p.getLength()));
		memory.writeLong(pb+CS+2,toInt(p.getAddress()));memory.writeWord(pb+CS+6,p.getPort());memory.writeLong(pb+CS+8,base);memory.writeWord(pb+CS+12,p.getLength());
		return NO_ERR;
	}
	private boolean udpWrite(int pb,boolean async) throws M68kException {
		Stream s=stream(pb);if(s==null||!s.udp){finish(pb,INVALID_STREAM,async);return true;}
		int host=memory.readLong(pb+CS+2),port=memory.readWord(pb+CS+6),wds=memory.readLong(pb+CS+8);
		if(!Sandbox.allowNetwork(tb.name,Sandbox.NET_UDP,dotted(host),port)){finish(pb,IP_BAD_ADDR,async);return true;}
		ByteArrayOutputStream data=new ByteArrayOutputStream();
		while(wds!=0){int length=memory.readWord(wds);if(length==0)break;int ptr=memory.readLong(wds+2);data.write(memory.readBytes(ptr,length),0,length);wds+=6;}
		try{byte[] bytes=data.toByteArray();s.datagram.send(new DatagramPacket(bytes,bytes.length,fromInt(host),port));finish(pb,NO_ERR,async);}
		catch(IOException e){finish(pb,IP_BAD_ADDR,async);}
		return true;
	}

	// ------------------------------------------------------------------ DNR

	void dnr(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7];int selector=memory.readLong(sp);
		if(Mac68kApi.TRACE)System.err.println("[tcp] DNR selector "+selector+(selector==3?" "+cString(memory.readLong(sp+4))+" hostInfo $"+Integer.toHexString(memory.readLong(sp+8))+" resultProc $"+Integer.toHexString(memory.readLong(sp+12)):""));
		switch(selector){
			case 1:resolverOpen=true;cpu.d[0]=NO_ERR;return;
			case 2:resolverOpen=false;cpu.d[0]=NO_ERR;return;
			case 3:{ // StrToAddr(hostName, hostInfo*, resultProc, userData)
				final int hostName=memory.readLong(sp+4),info=memory.readLong(sp+8),resultProc=memory.readLong(sp+12),userData=memory.readLong(sp+16);
				final String name=cString(hostName);
				Integer immediate=parseDotted(name);
				// a lookup the sandbox refuses fails like one without an answer - with a resultProc after StrToAddr has returned
				// cacheFault, as MacTCP always answers then, at the program's next event call: MacWeb shows its window and draws its toolbar while it waits; failing at
				// once put the "Can't locate remote host" alert up before the toolbar's first update
				if(immediate==null&&!Sandbox.allowNetwork(tb.name,Sandbox.NET_LOOKUP,name,0)){
					writeHostInfo(info,NO_ANS_ERR,name,new int[0]);
					if(resultProc==0){cpu.d[0]=NO_ANS_ERR;return;}
					memory.writeLong(info,CACHE_FAULT);cpu.d[0]=CACHE_FAULT;
					tb.laterAtEvent(new Runnable(){@Override public void run(){try{writeHostInfo(info,NO_ANS_ERR,name,new int[0]);
						M68kCpu c=tb.cpu;int[] d=c.d.clone(),a=c.a.clone();c.pushLong(info);c.pushLong(userData);try{c.callPascal(resultProc,8);}finally{System.arraycopy(d,0,c.d,0,8);System.arraycopy(a,0,c.a,0,8);}}catch(M68kException e){throw Toolbox68k.wrap(e);}}});
					return;}
				// a dotted-decimal address is answered at once with an EMPTY cname: MacWeb's inet_addr (StrToAddr, then "cname empty
				// = it was a number") returned INADDR_NONE when the cname held the address text, and connected to 255.255.255.255
				if(immediate!=null){writeHostInfo(info,NO_ERR,"",new int[]{immediate});cpu.d[0]=NO_ERR;return;}
				final boolean[] done={false};final int[][] addresses={null};final String[] cname={name};
				Thread t=new Thread(new Runnable(){@Override public void run(){try{InetAddress[] all=InetAddress.getAllByName(name);java.util.List<Integer> v4=new java.util.ArrayList<Integer>();for(InetAddress a:all)if(a.getAddress().length==4)v4.add(toInt(a));int[] arr=new int[v4.size()];for(int i=0;i<arr.length;i++)arr[i]=v4.get(i);addresses[0]=arr;String[] dottedAll=new String[arr.length];for(int i=0;i<arr.length;i++)dottedAll[i]=dotted(arr[i]);Sandbox.hostResolved(name,dottedAll);try{cname[0]=all.length>0?all[0].getCanonicalHostName():name;}catch(Exception e){}}catch(IOException e){addresses[0]=null;}finally{signal(done);}}},"DNR");
				t.setDaemon(true);t.start();
				if(resultProc!=0){
					memory.writeLong(info,CACHE_FAULT);cpu.d[0]=CACHE_FAULT;
					Thread w=new Thread(new Runnable(){@Override public void run(){synchronized(done){while(!done[0]&&!shutDown){try{done.wait(50);}catch(InterruptedException e){return;}}}
						tb.later(new Runnable(){@Override public void run(){try{int[] addr=addresses[0];writeHostInfo(info,addr==null||addr.length==0?NO_ANS_ERR:NO_ERR,cname[0],addr==null?new int[0]:addr);
							M68kCpu c=tb.cpu;int[] d=c.d.clone(),a=c.a.clone();c.pushLong(info);c.pushLong(userData);try{c.callPascal(resultProc,8);}finally{System.arraycopy(d,0,c.d,0,8);System.arraycopy(a,0,c.a,0,8);}}catch(M68kException e){throw Toolbox68k.wrap(e);}}});}},"DNR completion");
					w.setDaemon(true);w.start();return;
				}
				waitFor(done,30000);
				int[] addr=addresses[0];int rc=addr==null||addr.length==0?NO_ANS_ERR:NO_ERR;writeHostInfo(info,rc,cname[0],addr==null?new int[0]:addr);cpu.d[0]=rc;return;}
			case 4:{int addr=memory.readLong(sp+4),str=memory.readLong(sp+8);String s=dotted(addr);byte[] b=s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);memory.load(str,b);memory.writeByte(str+b.length,0);cpu.d[0]=NO_ERR;return;}
			case 5:cpu.d[0]=NO_ERR;return;
			case 6:{final int addr=memory.readLong(sp+4),info=memory.readLong(sp+8),resultProc=memory.readLong(sp+12),userData=memory.readLong(sp+16);
				String host;try{host=fromInt(addr).getCanonicalHostName();}catch(Exception e){host=dotted(addr);}
				writeHostInfo(info,NO_ERR,host,new int[]{addr});
				if(resultProc!=0){M68kCpu c=tb.cpu;int[] d=c.d.clone(),a=c.a.clone();c.pushLong(info);c.pushLong(userData);try{c.callPascal(resultProc,8);}finally{System.arraycopy(d,0,c.d,0,8);System.arraycopy(a,0,c.a,0,8);}}
				cpu.d[0]=NO_ERR;return;}
			default:cpu.d[0]=AUTH_NAME_ERR;return;
		}
	}
	private void writeHostInfo(int info,int rc,String cname,int[] addresses) throws M68kCpuException {
		memory.writeLong(info,rc);byte[] b=cname.getBytes(java.nio.charset.StandardCharsets.US_ASCII);int n=Math.min(254,b.length);memory.load(info+4,java.util.Arrays.copyOf(b,n));memory.writeByte(info+4+n,0);
		for(int i=0;i<4;i++)memory.writeLong(info+260+i*4,i<addresses.length?addresses[i]:0);
	}
	private String cString(int p) throws M68kCpuException {if(p==0)return "";return new String(memory.readCString(p,255),java.nio.charset.StandardCharsets.US_ASCII);}
	private static Integer parseDotted(String s){String[] parts=s.trim().split("\\.");if(parts.length!=4)return null;int v=0;try{for(String p:parts){int n=Integer.parseInt(p);if(n<0||n>255)return null;v=v<<8|n;}}catch(NumberFormatException e){return null;}return v;}
	static String dotted(int a){return ((a>>>24)&255)+"."+((a>>>16)&255)+"."+((a>>>8)&255)+"."+(a&255);}
	static int toInt(InetAddress a){if(a==null)return 0;byte[] b=a.getAddress();if(b.length!=4)return 0x7f000001;return (b[0]&255)<<24|(b[1]&255)<<16|(b[2]&255)<<8|(b[3]&255);}
	static InetAddress fromInt(int a) throws IOException {return InetAddress.getByAddress(new byte[]{(byte)(a>>>24),(byte)(a>>>16),(byte)(a>>>8),(byte)a});}
	/**
	 * The Mac's IP address: the host's first IPv4 interface that is up and not the loopback, as Open Transport answers (else
	 * 127.0.0.1). InetAddress.getLocalHost() was the loopback on a Mac whose name does not resolve: XTernal Collection's
	 * CurrentIP showed 127.0.0.1 while the Mac had a LAN address.
	 */
	private static InetAddress localAddress(){
		InetAddress found=hostInterface;if(found!=null)return found;
		try{
			for(java.net.NetworkInterface ni:java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())){
				if(!ni.isUp()||ni.isLoopback()||ni.isVirtual())continue;
				for(java.net.InterfaceAddress ia:ni.getInterfaceAddresses()){
					if(!(ia.getAddress() instanceof java.net.Inet4Address))continue;
					int prefix=ia.getNetworkPrefixLength();localNetmask=prefix<=0?0:prefix>=32?-1:-1<<(32-prefix);
					return hostInterface=ia.getAddress();
				}
			}
		}catch(IOException|RuntimeException ignored){}
		try{return hostInterface=InetAddress.getByName("127.0.0.1");}catch(IOException e){return null;}
	}
	private static volatile InetAddress hostInterface;private static volatile int localNetmask=0xffffff00;
}

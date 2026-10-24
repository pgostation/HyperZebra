import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The .DSP driver: AppleTalk Data Stream Protocol connection ends and connection listeners over the DDP of
 * {@link AppleTalk68k}, speaking the wire protocol of Inside AppleTalk (DDP type 7: 13-byte header of source connection
 * ID, sequence, acknowledgement, receive window and descriptor; open request/ack/deny handshake with the version stamp,
 * destination ID and attention sequence; data packets with EOM (which takes a sequence number) and ack requests; probes,
 * retransmit advice, attention messages with their own sequence space, close advice, forward reset), so a program here
 * talks to Apple's ADSP in an emulator on the LAN as well as to the next window. The client's queues are kept on the
 * host side (the buffers it passed only size the windows); reads and writes complete as data flows, connection events
 * (closed, torn down, attention, forward reset) set userFlags in the CCB and call the user routine with A1 = CCB.
 * <p>
 * Marathon's network games gather over connections made this way.
 */
final class Adsp68k {
	static final int DDP_TYPE=7,HEADER=13,MAX_DATA=572,ATTN_MAX=570;
	// csCodes
	private static final int DSP_INIT=255,DSP_REMOVE=254,DSP_OPEN=253,DSP_CLOSE=252,DSP_CL_INIT=251,DSP_CL_REMOVE=250,DSP_CL_LISTEN=249,DSP_CL_DENY=248,DSP_STATUS=247,DSP_READ=246,DSP_WRITE=245,DSP_ATTENTION=244,DSP_OPTIONS=243,DSP_RESET=242,DSP_NEW_CID=241;
	private static final int OC_REQUEST=1,OC_PASSIVE=2,OC_ACCEPT=3,OC_ESTABLISH=4;
	private static final int S_LISTENING=1,S_PASSIVE=2,S_OPENING=3,S_OPEN=4,S_CLOSING=5,S_CLOSED=6;
	private static final int E_CLOSED=0x80,E_TEAR_DOWN=0x40,E_ATTENTION=0x20,E_FWD_RESET=0x10;
	// descriptor bits and control codes
	private static final int CONTROL=0x80,ATTENTION=0x40,EOM=0x20,ACK_REQ=0x10;
	private static final int C_PROBE=0,C_OPEN_REQ=1,C_OPEN_ACK=2,C_OPEN_REQ_ACK=3,C_OPEN_DENY=4,C_CLOSE=5,C_FWD_RESET=6,C_FWD_RESET_ACK=7,C_RETRANSMIT=8;
	// results
	static final int NO_ERR=0,ERR_REF_NUM=-1280,ERR_ABORTED=-1279,ERR_STATE=-1278,ERR_OPENING=-1277,ERR_ATTENTION=-1276,ERR_FWD_RESET=-1275,ERR_DSP_QUEUE_SIZE=-1274,ERR_OPEN_DENIED=-1273,PARAM_ERR=-50,DDP_SKT_ERR=-91;
	private static final long RETRANSMIT_NANOS=500_000_000L,PROBE_NANOS=30_000_000_000L,DEAD_NANOS=120_000_000_000L,OPEN_TICK_NANOS=10*Toolbox68k.TICK_NANOS;
	private static final boolean TRACE=Mac68kApi.TRACE||Boolean.getBoolean("hz68k.traceatalk");

	private final AppleTalk68k at;
	private final M68kMemory memory;
	private final Map<Integer,End> ends=new HashMap<Integer,End>();
	private final Map<Integer,Listener> listeners=new HashMap<Integer,Listener>();
	private int nextRefNum=1;
	private final java.util.Random random=new java.util.Random();

	/** Data queued for sending or received: a run of bytes, possibly ending a logical message. */
	private static final class Segment {byte[] data;int offset;boolean eom;Segment(byte[] data,boolean eom){this.data=data;this.eom=eom;}int remaining(){return data.length-offset;}}
	/** A pending dspRead: arriving data goes straight into its buffer (freeing the receive queue, and so the window) until its count or an end of message. */
	private static final class Read {int pb,req,ptr,got;Read(int pb,int req,int ptr){this.pb=pb;this.req=req;this.ptr=ptr;}}
	/** A data packet in flight: retransmitted until its bytes (and its EOM) are acknowledged. */
	private static final class Flight {long seq;byte[] data;boolean eom;long sentAt;int length(){return data.length+(eom?1:0);}}

	private final class End {
		int refNum,ccb,socket,userRoutine,attnBuffer,sendQSize,recvQSize;
		int state=S_CLOSED;
		int localCid,remoteCid,remoteNet,remoteNode,remoteSocket,filterNet,filterNode,filterSocket;
		long sendSeq,recvSeq,attnSendSeq,attnRecvSeq,remoteWindow;
		final ArrayDeque<Segment> unsent=new ArrayDeque<Segment>();int unsentBytes;
		final ArrayDeque<Flight> inflight=new ArrayDeque<Flight>();int inflightBytes;
		final ArrayDeque<Segment> received=new ArrayDeque<Segment>();int receivedBytes;
		final ArrayDeque<Read> reads=new ArrayDeque<Read>();final ArrayDeque<Integer> writes=new ArrayDeque<Integer>();
		int openPb,openMode,openRetries;long openDue,openIntervalNanos;
		int attnPb;byte[] attnPacket;long attnDue;int attnRetries;
		int closePb;boolean closeAfterDrain;
		long lastHeard,nextProbe,ackDue=Long.MAX_VALUE;
		int recvWindow(){return Math.max(0,recvQSize-receivedBytes);}
		int sendPending(){return unsentBytes+inflightBytes;}
	}
	private final class Listener {int refNum,ccb,socket;final ArrayDeque<Integer> listens=new ArrayDeque<Integer>();final Map<String,Long> recent=new HashMap<String,Long>();}

	Adsp68k(AppleTalk68k at){this.at=at;memory=at.memory;}

	boolean active(){return !ends.isEmpty();}

	// ------------------------------------------------------------------ driver calls

	boolean control(int pb,int csCode,boolean async) throws M68kException {
		switch(csCode){
			case DSP_INIT:return init(pb,async);
			case DSP_CL_INIT:return clInit(pb,async);
			case DSP_NEW_CID:memory.writeWord(pb+34,newCid());return finish(pb,NO_ERR,async);
			default:break;
		}
		int refNum=memory.readWord(pb+32);
		Listener l=listeners.get(refNum);
		if(l!=null){
			switch(csCode){
				case DSP_CL_LISTEN:{l.listens.add(pb);if(async)return true;return waitSync(pb);}
				case DSP_CL_DENY:{int cid=memory.readWord(pb+36),net=memory.readWord(pb+38),node=memory.readByte(pb+40),socket=memory.readByte(pb+41);
					sendOpen(l.socket,net,node,socket,C_OPEN_DENY,newCid(),cid,0,0,0,0);return finish(pb,NO_ERR,async);}
				case DSP_CL_REMOVE:{removeListener(l);return finish(pb,NO_ERR,async);}
				default:return finish(pb,ERR_STATE,async);
			}
		}
		End e=ends.get(refNum);
		if(e==null)return finish(pb,ERR_REF_NUM,async);
		switch(csCode){
			case DSP_REMOVE:{boolean abort=memory.readByte(pb+34)!=0;if(e.state==S_OPEN||e.state==S_OPENING||e.state==S_PASSIVE)close(e,abort);remove(e,ERR_ABORTED);return finish(pb,NO_ERR,async);}
			case DSP_OPEN:return open(e,pb,async);
			case DSP_CLOSE:{
				boolean abort=memory.readByte(pb+34)!=0;
				if(e.state!=S_OPEN&&e.state!=S_OPENING&&e.state!=S_PASSIVE)return finish(pb,ERR_STATE,async);
				if(!abort&&e.state==S_OPEN&&e.sendPending()>0){e.closeAfterDrain=true;e.state=S_CLOSING;writeState(e);e.closePb=pb;if(async)return true;return waitSync(pb);}
				close(e,abort);return finish(pb,NO_ERR,async);}
			case DSP_STATUS:{
				memory.writeLong(pb+34,e.ccb);memory.writeWord(pb+38,e.sendPending());memory.writeWord(pb+40,Math.max(0,e.sendQSize-e.sendPending()));
				memory.writeWord(pb+42,e.receivedBytes);memory.writeWord(pb+44,e.recvWindow());return finish(pb,NO_ERR,async);}
			case DSP_READ:{
				if(e.state!=S_OPEN&&e.state!=S_CLOSING&&e.received.isEmpty())return finish(pb,ERR_STATE,async);
				Read read=new Read(pb,memory.readWord(pb+34),memory.readLong(pb+38));e.reads.add(read);serveReads(e);
				if(!e.reads.contains(read)){completeSyncResult(pb);return true;}
				if(async)return true;return waitSync(pb);}
			case DSP_WRITE:{
				if(e.state!=S_OPEN)return finish(pb,ERR_STATE,async);
				e.writes.add(pb);serveWrites(e);
				if(!e.writes.contains(pb)){completeSyncResult(pb);return true;}
				if(async)return true;return waitSync(pb);}
			case DSP_ATTENTION:{
				if(e.state!=S_OPEN)return finish(pb,ERR_STATE,async);
				if(e.attnPb!=0)return finish(pb,ERR_ATTENTION,async);
				int size=memory.readWord(pb+36);if(size>ATTN_MAX)return finish(pb,ERR_ATTENTION,async);
				byte[] data=new byte[2+size];int code=memory.readWord(pb+34);data[0]=(byte)(code>>>8);data[1]=(byte)code;
				int ptr=memory.readLong(pb+38);if(size>0&&ptr!=0)System.arraycopy(memory.readBytes(ptr,size),0,data,2,size);
				e.attnPacket=packet(e,ATTENTION,e.attnSendSeq,e.attnRecvSeq,data);e.attnPb=pb;e.attnRetries=0;
				int interval=memory.readByte(pb+42);e.attnDue=System.nanoTime()+Math.max(1,interval)*OPEN_TICK_NANOS;
				transmit(e,e.attnPacket);
				if(async)return true;return waitSync(pb);}
			case DSP_OPTIONS:return finish(pb,NO_ERR,async);
			case DSP_RESET:{
				if(e.state!=S_OPEN)return finish(pb,ERR_STATE,async);
				e.unsent.clear();e.unsentBytes=0;e.inflight.clear();e.inflightBytes=0;
				for(Integer w=e.writes.poll();w!=null;w=e.writes.poll())at.settle(w,ERR_FWD_RESET,at.syncOf(w));
				transmit(e,packet(e,CONTROL|C_FWD_RESET,e.sendSeq,e.recvSeq,null));return finish(pb,NO_ERR,async);}
			default:return finish(pb,PARAM_ERR,async);
		}
	}
	private boolean finish(int pb,int result,boolean async) throws M68kException {at.finish(pb,result,async);return true;}
	/** A synchronous call that has just been completed by the serving code: D0 carries its result. */
	private void completeSyncResult(int pb) throws M68kCpuException {at.tb.cpu.d[0]=(at.tb.cpu.d[0]&0xffff0000)|(memory.readWord(pb+16)&0xffff);}
	private boolean waitSync(int pb) throws M68kException {
		boolean[] done={false};at.syncFlags.put(pb,done);at.waitFor(pb,done);at.syncFlags.remove(pb);completeSyncResult(pb);return true;
	}

	private int newCid(){
		while(true){int cid=1+random.nextInt(0xfffe);boolean used=false;for(End e:ends.values())if(e.localCid==cid)used=true;if(!used)return cid;}
	}
	/** Opens or shares the DDP socket an end or listener uses; -1 when it cannot be had. */
	private int useSocket(int wanted){
		int number=wanted;
		if(number==0){number=at.dynamicSocket();if(number<0)return -1;}
		else if(number<1||number>254)return -1;
		AppleTalk68k.DdpSocket s=at.sockets.get(number);
		if(s==null){s=new AppleTalk68k.DdpSocket(number);at.sockets.put(number,s);}
		else if(s.atp!=null||s.listener!=0||s.adspUsers==0)return -1;
		s.adspUsers++;return number;
	}
	private void releaseSocket(int number){
		AppleTalk68k.DdpSocket s=at.sockets.get(number);
		if(s!=null&&--s.adspUsers<=0)at.sockets.remove(number);
	}

	private boolean init(int pb,boolean async) throws M68kException {
		int ccb=memory.readLong(pb+34);if(ccb==0)return finish(pb,PARAM_ERR,async);
		int sendQ=memory.readWord(pb+42),recvQ=memory.readWord(pb+48);
		if(sendQ<100||recvQ<100)return finish(pb,ERR_DSP_QUEUE_SIZE,async);
		End e=new End();e.ccb=ccb;e.userRoutine=memory.readLong(pb+38);e.sendQSize=sendQ;e.recvQSize=recvQ;e.attnBuffer=memory.readLong(pb+54);
		e.socket=useSocket(memory.readByte(pb+58));if(e.socket<0)return finish(pb,DDP_SKT_ERR,async);
		e.refNum=nextRefNum++;e.state=S_CLOSED;ends.put(e.refNum,e);
		memory.writeWord(pb+32,e.refNum);memory.writeByte(pb+58,e.socket);
		memory.writeWord(ccb+4,e.refNum);memory.writeByte(ccb+8,0);memory.writeByte(ccb+9,e.socket);memory.writeLong(ccb+18,e.attnBuffer);writeState(e);
		if(TRACE)System.err.println("[adsp] init end "+e.refNum+" socket "+e.socket);
		return finish(pb,NO_ERR,async);
	}
	private boolean clInit(int pb,boolean async) throws M68kException {
		int ccb=memory.readLong(pb+34);if(ccb==0)return finish(pb,PARAM_ERR,async);
		Listener l=new Listener();l.ccb=ccb;l.socket=useSocket(memory.readByte(pb+58));if(l.socket<0)return finish(pb,DDP_SKT_ERR,async);
		l.refNum=nextRefNum++;listeners.put(l.refNum,l);
		memory.writeWord(pb+32,l.refNum);memory.writeByte(pb+58,l.socket);
		memory.writeWord(ccb+4,l.refNum);memory.writeWord(ccb+6,S_LISTENING);memory.writeByte(ccb+9,l.socket);
		if(TRACE)System.err.println("[adsp] listener "+l.refNum+" on socket "+l.socket);
		return finish(pb,NO_ERR,async);
	}
	private void removeListener(Listener l){
		listeners.remove(l.refNum);releaseSocket(l.socket);
		for(Integer p=l.listens.poll();p!=null;p=l.listens.poll())at.settle(p,ERR_ABORTED,at.syncOf(p));
	}
	private void writeState(End e){try{memory.writeWord(e.ccb+6,e.state);}catch(M68kCpuException ignored){}}
	private void writeRemote(End e){try{memory.writeWord(e.ccb+10,e.remoteNet);memory.writeByte(e.ccb+12,e.remoteNode);memory.writeByte(e.ccb+13,e.remoteSocket);}catch(M68kCpuException ignored){}}

	private boolean open(End e,int pb,boolean async) throws M68kException {
		if(e.state!=S_CLOSED)return finish(pb,ERR_STATE,async);
		int mode=memory.readByte(pb+64);
		e.localCid=memory.readWord(pb+34);if(e.localCid==0)e.localCid=newCid();
		e.remoteCid=memory.readWord(pb+36);
		e.remoteNet=memory.readWord(pb+38);e.remoteNode=memory.readByte(pb+40);e.remoteSocket=memory.readByte(pb+41);
		e.filterNet=memory.readWord(pb+42);e.filterNode=memory.readByte(pb+44);e.filterSocket=memory.readByte(pb+45);
		e.sendSeq=random.nextInt()&0xffffffffL;e.attnSendSeq=random.nextInt()&0xffffffffL;
		e.unsent.clear();e.inflight.clear();e.received.clear();e.unsentBytes=e.inflightBytes=e.receivedBytes=0;
		e.openMode=mode;e.openPb=pb;e.openRetries=0;
		int interval=memory.readByte(pb+65),maximum=memory.readByte(pb+66);
		e.openIntervalNanos=(interval==0?6:interval)*OPEN_TICK_NANOS;e.openRetries=maximum==0?3:maximum;
		e.lastHeard=System.nanoTime();
		switch(mode){
			case OC_REQUEST:
				if(e.remoteNode==0)return finish(pb,PARAM_ERR,async);
				e.state=S_OPENING;e.remoteCid=0;e.openDue=0;
				break;
			case OC_PASSIVE:
				e.state=S_PASSIVE;e.openDue=Long.MAX_VALUE;
				break;
			case OC_ACCEPT:
				// the listener gave the requester's ID, address, first sequence number, window and attention sequence
				e.recvSeq=memory.readLong(pb+46)&0xffffffffL;e.remoteWindow=memory.readWord(pb+50);e.attnRecvSeq=memory.readLong(pb+56)&0xffffffffL;
				e.state=S_OPENING;e.openDue=0;
				break;
			case OC_ESTABLISH:
				e.recvSeq=memory.readLong(pb+52)&0xffffffffL;e.sendSeq=memory.readLong(pb+46)&0xffffffffL;e.remoteWindow=memory.readWord(pb+50);
				e.attnSendSeq=memory.readLong(pb+56)&0xffffffffL;e.attnRecvSeq=memory.readLong(pb+60)&0xffffffffL;
				established(e);return finish(pb,NO_ERR,async);
			default:return finish(pb,PARAM_ERR,async);
		}
		writeState(e);writeRemote(e);
		if(TRACE)System.err.println("[adsp] open end "+e.refNum+" mode "+mode+" cid "+e.localCid+" -> "+e.remoteNode+":"+e.remoteSocket+" cid "+e.remoteCid);
		service(); // the first request goes out now
		if(async)return true;
		return waitSync(pb);
	}
	/** The handshake is done: the open call reports the numbers, the connection carries data. */
	private void established(End e){
		e.state=S_OPEN;e.openDue=Long.MAX_VALUE;e.lastHeard=System.nanoTime();e.nextProbe=e.lastHeard+PROBE_NANOS;
		writeState(e);writeRemote(e);
		int pb=e.openPb;e.openPb=0;
		if(pb!=0){
			try{memory.writeWord(pb+34,e.localCid);memory.writeWord(pb+36,e.remoteCid);memory.writeWord(pb+38,e.remoteNet);memory.writeByte(pb+40,e.remoteNode);memory.writeByte(pb+41,e.remoteSocket);
				memory.writeLong(pb+46,(int)e.sendSeq);memory.writeWord(pb+50,(int)e.remoteWindow);memory.writeLong(pb+52,(int)e.recvSeq);memory.writeLong(pb+56,(int)e.attnSendSeq);memory.writeLong(pb+60,(int)e.attnRecvSeq);}
			catch(M68kCpuException ignored){}
			at.settle(pb,NO_ERR,at.syncOf(pb));
		}
		if(TRACE)System.err.println("[adsp] end "+e.refNum+" open: cid "+e.localCid+" <-> "+e.remoteNode+":"+e.remoteSocket+" cid "+e.remoteCid);
	}
	private void openFailed(End e,int result){
		e.state=S_CLOSED;writeState(e);
		int pb=e.openPb;e.openPb=0;
		if(pb!=0)at.settle(pb,result,at.syncOf(pb));
	}

	/** Closes the connection: close advice to the remote end (twice, it is not acknowledged), pending calls aborted. */
	private void close(End e,boolean abort){
		if(e.state==S_OPEN||e.state==S_CLOSING){byte[] p=packet(e,CONTROL|C_CLOSE,e.sendSeq,e.recvSeq,null);transmit(e,p);transmit(e,p);}
		else if(e.state==S_OPENING||e.state==S_PASSIVE)openFailed(e,ERR_ABORTED);
		e.state=S_CLOSED;writeState(e);
		abortCalls(e,ERR_ABORTED);
		int pb=e.closePb;e.closePb=0;e.closeAfterDrain=false;
		if(pb!=0)at.settle(pb,NO_ERR,at.syncOf(pb));
		if(TRACE)System.err.println("[adsp] end "+e.refNum+" closed"+(abort?" (abort)":""));
	}
	private void abortCalls(End e,int result){
		for(Integer p=e.writes.poll();p!=null;p=e.writes.poll())at.settle(p,result,at.syncOf(p));
		serveReads(e); // what was received is still read
		for(Read r=e.reads.poll();r!=null;r=e.reads.poll()){try{memory.writeWord(r.pb+36,r.got);}catch(M68kCpuException ignored){}at.settle(r.pb,result,at.syncOf(r.pb));}
		if(e.attnPb!=0){int p=e.attnPb;e.attnPb=0;e.attnPacket=null;at.settle(p,result,at.syncOf(p));}
		e.unsent.clear();e.inflight.clear();e.unsentBytes=e.inflightBytes=0;
	}
	private void remove(End e,int result){
		ends.remove(e.refNum);abortCalls(e,result);releaseSocket(e.socket);
		if(e.openPb!=0)openFailed(e,result);
	}
	void closeAll(){
		for(End e:new ArrayList<End>(ends.values())){if(e.state==S_OPEN||e.state==S_CLOSING)close(e,true);remove(e,ERR_ABORTED);}
		for(Listener l:new ArrayList<Listener>(listeners.values()))removeListener(l);
	}
	/** The connection went away under the client: userFlags event, everything pending fails. */
	private void tearDown(End e,int flag){
		if(TRACE)System.err.println("[adsp] end "+e.refNum+(flag==E_CLOSED?" closed by the remote end":" torn down"));
		e.state=S_CLOSED;writeState(e);
		int pb=e.closePb;e.closePb=0;e.closeAfterDrain=false;
		if(pb!=0)at.settle(pb,NO_ERR,at.syncOf(pb));
		abortCalls(e,ERR_ABORTED);
		event(e,flag);
	}
	private void event(End e,int flag){
		try{memory.writeByte(e.ccb+8,memory.readByte(e.ccb+8)|flag);}catch(M68kCpuException ignored){}
		if(e.userRoutine==0)return;
		M68kCpu cpu=at.tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		try{cpu.a[1]=e.ccb;cpu.call(e.userRoutine);}
		catch(M68kException ex){throw Toolbox68k.wrap(ex);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}

	// ------------------------------------------------------------------ reads and writes

	/** Moves received data into the pending reads in order; a read completes at its count or at an end of message (or with what there is once the connection is gone). */
	private void serveReads(End e){
		int freed=0;
		try{
			while(!e.reads.isEmpty()){
				Read r=e.reads.peek();boolean eom=false;
				while(r.got<r.req&&!e.received.isEmpty()){
					Segment s=e.received.peek();
					int n=Math.min(r.req-r.got,s.remaining());
					if(n>0&&r.ptr!=0)memory.load(r.ptr+r.got,s.data,s.offset,n);
					r.got+=n;s.offset+=n;e.receivedBytes-=n;freed+=n;
					if(s.remaining()==0){e.received.poll();if(s.eom){eom=true;break;}}
				}
				boolean open=e.state==S_OPEN||e.state==S_CLOSING;
				if(r.got<r.req&&!eom&&open)break; // more may come
				e.reads.poll();
				memory.writeWord(r.pb+36,r.got);memory.writeByte(r.pb+42,eom?1:0);
				at.settle(r.pb,r.got==0&&!eom&&!open?ERR_STATE:NO_ERR,at.syncOf(r.pb));
			}
		}catch(M68kCpuException ex){throw Toolbox68k.wrap(ex);}
		if(freed>0&&(e.state==S_OPEN||e.state==S_CLOSING))sendAck(e); // the window grew: tell the sender
	}
	/** Moves waiting writes into the send queue while the queue has room, then sends what the remote window allows. */
	private void serveWrites(End e){
		while(!e.writes.isEmpty()&&e.state==S_OPEN){
			int pb=e.writes.peek();
			try{
				int req=memory.readWord(pb+34),ptr=memory.readLong(pb+38);boolean eom=memory.readByte(pb+42)!=0;
				if(req+e.sendPending()>e.sendQSize&&e.sendPending()>0)break; // no room yet: acknowledgements free it
				byte[] data=req==0||ptr==0?new byte[0]:memory.readBytes(ptr,req);
				e.unsent.add(new Segment(data,eom));e.unsentBytes+=req;
				e.writes.poll();memory.writeWord(pb+36,req);
				at.settle(pb,NO_ERR,at.syncOf(pb));
			}catch(M68kCpuException ex){throw Toolbox68k.wrap(ex);}
		}
		push(e);
	}
	/** Sends queued data as packets of up to 572 bytes within the remote window; each asks for an acknowledgement. */
	private void push(End e){
		while(!e.unsent.isEmpty()){
			Segment s=e.unsent.peek();
			long room=e.remoteWindow-e.inflightBytes;
			if(room<=0&&!(s.remaining()==0&&s.eom))return;
			int n=(int)Math.min(Math.min(MAX_DATA,room),s.remaining());
			byte[] data=new byte[n];System.arraycopy(s.data,s.offset,data,0,n);s.offset+=n;e.unsentBytes-=n;
			boolean eom=s.eom&&s.remaining()==0;
			if(s.remaining()==0)e.unsent.poll();
			Flight f=new Flight();f.seq=e.sendSeq;f.data=data;f.eom=eom;f.sentAt=System.nanoTime();
			e.sendSeq=(e.sendSeq+f.length())&0xffffffffL;
			e.inflight.add(f);e.inflightBytes+=f.length();
			transmit(e,dataPacket(e,f));
		}
	}
	private byte[] dataPacket(End e,Flight f){return packet(e,ACK_REQ|(f.eom?EOM:0),f.seq,e.recvSeq,f.data);}

	// ------------------------------------------------------------------ packets

	private byte[] packet(End e,int descriptor,long seq,long ack,byte[] data){
		int n=data==null?0:data.length;byte[] p=new byte[HEADER+n];
		p[0]=(byte)(e.localCid>>>8);p[1]=(byte)e.localCid;
		p[2]=(byte)(seq>>>24);p[3]=(byte)(seq>>>16);p[4]=(byte)(seq>>>8);p[5]=(byte)seq;
		p[6]=(byte)(ack>>>24);p[7]=(byte)(ack>>>16);p[8]=(byte)(ack>>>8);p[9]=(byte)ack;
		int window=e.recvWindow();p[10]=(byte)(window>>>8);p[11]=(byte)window;
		p[12]=(byte)descriptor;
		if(n>0)System.arraycopy(data,0,p,HEADER,n);
		return p;
	}
	private void transmit(End e,byte[] p){at.send(e.remoteNet,e.remoteNode,e.remoteSocket,e.socket,DDP_TYPE,p);}
	/** An open request/ack/deny: the version stamp, the destination's ID and our attention sequence follow the header. */
	private void sendOpen(int fromSocket,int net,int node,int socket,int code,int localCid,int destCid,long seq,long ack,int window,long attnSeq){
		byte[] p=new byte[HEADER+8];
		p[0]=(byte)(localCid>>>8);p[1]=(byte)localCid;
		p[2]=(byte)(seq>>>24);p[3]=(byte)(seq>>>16);p[4]=(byte)(seq>>>8);p[5]=(byte)seq;
		p[6]=(byte)(ack>>>24);p[7]=(byte)(ack>>>16);p[8]=(byte)(ack>>>8);p[9]=(byte)ack;
		p[10]=(byte)(window>>>8);p[11]=(byte)window;p[12]=(byte)(CONTROL|code);
		p[13]=0x01;p[14]=0x00;p[15]=(byte)(destCid>>>8);p[16]=(byte)destCid;
		p[17]=(byte)(attnSeq>>>24);p[18]=(byte)(attnSeq>>>16);p[19]=(byte)(attnSeq>>>8);p[20]=(byte)attnSeq;
		at.send(net,node,socket,fromSocket,DDP_TYPE,p);
	}
	private void sendOpen(End e,int code){sendOpen(e.socket,e.remoteNet,e.remoteNode,e.remoteSocket,code,e.localCid,e.remoteCid,e.sendSeq,e.recvSeq,e.recvWindow(),e.attnSendSeq);}
	private void sendAck(End e){transmit(e,packet(e,CONTROL|C_PROBE,e.sendSeq,e.recvSeq,null));e.ackDue=Long.MAX_VALUE;}

	private static long u32(byte[] p,int i){return ((p[i]&0xffL)<<24)|((p[i+1]&0xff)<<16)|((p[i+2]&0xff)<<8)|(p[i+3]&0xff);}
	private static int u16(byte[] p,int i){return (p[i]&0xff)<<8|p[i+1]&0xff;}
	/** Sequence comparison in the 32-bit ring. */
	private static int cmp(long a,long b){return (int)(a-b);}

	void receive(int socket,int srcNet,int srcNode,int srcSocket,byte[] p){
		if(p.length<HEADER)return;
		int srcCid=u16(p,0);long seq=u32(p,2),ack=u32(p,6);int window=u16(p,10),descriptor=p[12]&0xff;
		boolean control=(descriptor&CONTROL)!=0,attention=(descriptor&ATTENTION)!=0;int code=descriptor&15;
		if(TRACE)System.err.println("[adsp] socket "+socket+" <- "+srcNode+":"+srcSocket+" cid "+srcCid+(control?" control "+code:attention?" attention":" data "+(p.length-HEADER))+" seq "+seq+" ack "+ack+" window "+window);
		if(control&&!attention&&(code==C_OPEN_REQ||code==C_OPEN_REQ_ACK||code==C_OPEN_ACK||code==C_OPEN_DENY)){
			if(p.length<HEADER+8)return;
			int destCid=u16(p,15);long attnSeq=u32(p,17);
			openPacket(socket,srcNet,srcNode,srcSocket,srcCid,seq,ack,window,code,destCid,attnSeq);
			return;
		}
		End e=find(socket,srcNode,srcSocket,srcCid);
		if(e==null||e.state!=S_OPEN&&e.state!=S_CLOSING)return;
		e.lastHeard=System.nanoTime();e.nextProbe=e.lastHeard+PROBE_NANOS;
		if(attention){
			if(control){ // attention acknowledgement
				if(e.attnPb!=0&&cmp(ack,e.attnSendSeq)>0){e.attnSendSeq=ack;int pb=e.attnPb;e.attnPb=0;e.attnPacket=null;at.settle(pb,NO_ERR,at.syncOf(pb));}
				return;
			}
			if(p.length<HEADER+2)return;
			if(seq==e.attnRecvSeq){
				e.attnRecvSeq=(e.attnRecvSeq+1)&0xffffffffL;
				int size=p.length-HEADER-2;
				try{memory.writeWord(e.ccb+14,u16(p,HEADER));memory.writeWord(e.ccb+16,size);if(e.attnBuffer!=0&&size>0)memory.load(e.attnBuffer,p,HEADER+2,Math.min(size,ATTN_MAX));}
				catch(M68kCpuException ex){throw Toolbox68k.wrap(ex);}
				transmit(e,packet(e,CONTROL|ATTENTION,e.attnSendSeq,e.attnRecvSeq,null));
				event(e,E_ATTENTION);
			}else if(cmp(seq,e.attnRecvSeq)<0)transmit(e,packet(e,CONTROL|ATTENTION,e.attnSendSeq,e.attnRecvSeq,null)); // a repeat: acknowledge again
			return;
		}
		acknowledged(e,ack,window);
		if(control){
			switch(code){
				case C_PROBE:if((descriptor&ACK_REQ)!=0)sendAck(e);break;
				case C_CLOSE:tearDown(e,E_CLOSED);break;
				case C_FWD_RESET:
					e.received.clear();e.receivedBytes=0;e.recvSeq=seq;
					transmit(e,packet(e,CONTROL|C_FWD_RESET_ACK,e.sendSeq,e.recvSeq,null));
					for(Read r=e.reads.poll();r!=null;r=e.reads.poll())at.settle(r.pb,ERR_FWD_RESET,at.syncOf(r.pb));
					event(e,E_FWD_RESET);break;
				case C_FWD_RESET_ACK:break;
				case C_RETRANSMIT:retransmit(e,ack);break;
				default:break;
			}
			return;
		}
		// data
		int n=p.length-HEADER;boolean eom=(descriptor&EOM)!=0;
		if(seq==e.recvSeq){
			if(n+(eom?1:0)==0)return;
			if(n>e.recvWindow()){if((descriptor&ACK_REQ)!=0)sendAck(e);return;} // beyond our window: the sender will retry
			byte[] data=new byte[n];System.arraycopy(p,HEADER,data,0,n);
			e.received.add(new Segment(data,eom));e.receivedBytes+=n;
			e.recvSeq=(e.recvSeq+n+(eom?1:0))&0xffffffffL;
			serveReads(e);
			if((descriptor&ACK_REQ)!=0)sendAck(e);else e.ackDue=Math.min(e.ackDue,System.nanoTime()+RETRANSMIT_NANOS/2);
		}else if(cmp(seq,e.recvSeq)<0){sendAck(e);} // already have it
		else{transmit(e,packet(e,CONTROL|C_RETRANSMIT,e.sendSeq,e.recvSeq,null));} // a gap: ask for the missing bytes
	}
	private End find(int socket,int srcNode,int srcSocket,int srcCid){
		for(End e:ends.values())if(e.socket==socket&&e.remoteCid==srcCid&&e.remoteNode==srcNode&&e.remoteSocket==srcSocket&&(e.state==S_OPEN||e.state==S_CLOSING))return e;
		return null;
	}
	/** The remote end has everything before {@code ack}: those packets leave the flight, the window is theirs now. */
	private void acknowledged(End e,long ack,int window){
		while(!e.inflight.isEmpty()){
			Flight f=e.inflight.peek();
			if(cmp(ack,f.seq+f.length())<0)break;
			e.inflight.poll();e.inflightBytes-=f.length();
		}
		e.remoteWindow=window;
		serveWrites(e);
		if(e.closeAfterDrain&&e.sendPending()==0)close(e,false);
	}
	private void retransmit(End e,long from){
		long now=System.nanoTime();
		for(Flight f:e.inflight){if(cmp(f.seq+f.length(),from)<=0)continue;f.sentAt=now;transmit(e,dataPacket(e,f));}
	}

	private void openPacket(int socket,int srcNet,int srcNode,int srcSocket,int srcCid,long seq,long ack,int window,int code,int destCid,long attnSeq){
		// an end already talking to that remote end (a repeated request, or their ack)
		for(End e:ends.values()){
			if(e.remoteCid!=srcCid||e.remoteNode!=srcNode||e.remoteSocket!=srcSocket&&code!=C_OPEN_REQ)continue;
			if(e.state==S_OPEN&&(code==C_OPEN_REQ||code==C_OPEN_REQ_ACK)){sendOpen(e,C_OPEN_ACK);return;} // our ack was lost
			if(e.state==S_OPENING&&e.openMode==OC_ACCEPT&&code==C_OPEN_REQ){sendOpen(e,C_OPEN_REQ_ACK);return;}
			if(e.state==S_OPENING&&code==C_OPEN_ACK&&destCid==e.localCid){established(e);return;}
		}
		if(code==C_OPEN_REQ_ACK||code==C_OPEN_DENY){
			for(End e:ends.values()){
				if(e.state!=S_OPENING||e.openMode!=OC_REQUEST||destCid!=e.localCid||e.remoteNode!=srcNode)continue;
				if(code==C_OPEN_DENY){openFailed(e,ERR_OPEN_DENIED);return;}
				e.remoteCid=srcCid;e.remoteNet=srcNet;e.remoteSocket=srcSocket;e.recvSeq=seq;e.remoteWindow=window;e.attnRecvSeq=attnSeq;
				sendOpen(e,C_OPEN_ACK);established(e);return;
			}
			return;
		}
		if(code!=C_OPEN_REQ)return;
		// a passive end on this socket takes it
		for(End e:ends.values()){
			if(e.state!=S_PASSIVE||e.socket!=socket)continue;
			if(e.filterNode!=0&&e.filterNode!=srcNode||e.filterSocket!=0&&e.filterSocket!=srcSocket)continue;
			e.remoteCid=srcCid;e.remoteNet=srcNet;e.remoteNode=srcNode;e.remoteSocket=srcSocket;e.recvSeq=seq;e.remoteWindow=window;e.attnRecvSeq=attnSeq;
			e.state=S_OPENING;e.openMode=OC_ACCEPT;e.openDue=0;writeState(e);writeRemote(e);service();return;
		}
		// a connection listener reports it to the client (once per request while it is being accepted)
		for(Listener l:listeners.values()){
			if(l.socket!=socket)continue;
			String key=srcNode+":"+srcSocket+":"+srcCid;Long seen=l.recent.get(key);long now=System.nanoTime();
			if(seen!=null&&now-seen<5_000_000_000L)return;
			Integer pb=l.listens.poll();if(pb==null)return;
			l.recent.put(key,now);
			try{memory.writeWord(pb+36,srcCid);memory.writeWord(pb+38,srcNet);memory.writeByte(pb+40,srcNode);memory.writeByte(pb+41,srcSocket);
				memory.writeLong(pb+46,(int)seq);memory.writeWord(pb+50,window);memory.writeLong(pb+56,(int)attnSeq);}
			catch(M68kCpuException ex){throw Toolbox68k.wrap(ex);}
			if(TRACE)System.err.println("[adsp] listener "+l.refNum+" got a request from "+srcNode+":"+srcSocket+" cid "+srcCid);
			at.settle(pb,NO_ERR,at.syncOf(pb));return;
		}
	}

	// ------------------------------------------------------------------ timers

	long nextDueNanos(){
		long due=Long.MAX_VALUE;
		for(End e:ends.values()){
			if(e.state==S_OPENING)due=Math.min(due,e.openDue);
			if(e.state==S_OPEN||e.state==S_CLOSING){
				if(!e.inflight.isEmpty())due=Math.min(due,e.inflight.peek().sentAt+RETRANSMIT_NANOS);
				if(e.attnPb!=0)due=Math.min(due,e.attnDue);
				due=Math.min(due,Math.min(e.nextProbe,e.ackDue));
			}
		}
		return due;
	}
	void service(){
		if(ends.isEmpty())return;
		long now=System.nanoTime();
		for(End e:new ArrayList<End>(ends.values())){
			if(!ends.containsKey(e.refNum))continue;
			if(e.state==S_OPENING&&now>=e.openDue){
				if(e.openDue!=0&&e.openRetries!=255&&--e.openRetries<0){openFailed(e,ERR_OPENING);continue;}
				sendOpen(e,e.openMode==OC_ACCEPT?C_OPEN_REQ_ACK:C_OPEN_REQ);
				e.openDue=now+e.openIntervalNanos;
				continue;
			}
			if(e.state!=S_OPEN&&e.state!=S_CLOSING)continue;
			if(now-e.lastHeard>DEAD_NANOS){tearDown(e,E_TEAR_DOWN);continue;}
			if(!e.inflight.isEmpty()&&now-e.inflight.peek().sentAt>=RETRANSMIT_NANOS)retransmit(e,e.inflight.peek().seq);
			if(e.attnPb!=0&&now>=e.attnDue){if(++e.attnRetries>60){int pb=e.attnPb;e.attnPb=0;e.attnPacket=null;at.settle(pb,ERR_ATTENTION,at.syncOf(pb));}else{transmit(e,e.attnPacket);e.attnDue=now+RETRANSMIT_NANOS;}}
			if(now>=e.ackDue)sendAck(e);
			if(now>=e.nextProbe){transmit(e,packet(e,CONTROL|C_PROBE|ACK_REQ,e.sendSeq,e.recvSeq,null));e.nextProbe=now+PROBE_NANOS;}
		}
	}
}

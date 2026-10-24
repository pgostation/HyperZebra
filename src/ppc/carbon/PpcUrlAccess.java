import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * URL Access Manager, Keychain Manager and Internet Config additions for PowerPC programs (URLAccessLib, KeychainLib and
 * CarbonLib's copies of both, InternetConfig's CarbonLib 1.1 calls).
 * <p>
 * <b>URL Access</b> (URLAccess.h, URL Access 2.0): http, https, ftp and file URLs. Transfers run on a Java thread per URL
 * reference; the thread never touches guest memory - it fills Java buffers, writes host files and posts events. The
 * program's URLNotifyProc (a TVector: TVECTOR_UPP_TYPE) is called only on the emulation thread: from URLIdle and at the
 * program's event calls (GetNextEvent, WaitNextEvent, SystemTask, ModalDialog - {@link Toolbox68k#laterAtEvent}), as the
 * real manager called it at system-task time. Downloaded data goes to a host file (an FSSpec) or into guest buffers that
 * URLGetBuffer hands out. Synchronous calls (URLSimpleDownload, URLDownload, URLSimpleUpload, URLUpload) wait with the
 * VM's deferred work and timers running. Every connection (and every redirect) passes {@link Sandbox#allowNetwork}; a
 * refusal ends the transfer with kEACCESErr. No progress or authentication dialogs are shown.
 * <p>
 * <b>Keychain</b> (KeychainCore.h / KeychainHI.h, Keychain 2.0): a Keychain Manager is installed but no keychain exists and
 * none can be made - finds answer errKCItemNotFound, adds and unlocks errKCNoDefaultKeychain. Items made with KCNewItem
 * live in memory for the run (their attributes and data can be set and read back) but can never be added. No secret is
 * stored anywhere.
 * <p>
 * <b>Internet Config</b>: ICParseURL / ICLaunchURL find the URL around the selection as IC did (angle brackets, "URL:",
 * "name@address" with the hint); ICCreateGURLEvent builds the 'GURL' Apple event, ICSendGURLEvent opens its URL in the
 * host's browser (Sandbox OPEN_URL gate, as {@link PpcCarbon}'s ICLaunchURL). ICSetPref values are kept for the run and
 * read back by ICGetPref, over the defaults PpcCarbon's ICFindPrefHandle provides.
 */
final class PpcUrlAccess {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcUrlAccess(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;icFile=new PpcInternetConfig(rt);}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}

	void define(PpcLibraries l){defineUrlAccess(l);defineKeychain(l);defineInternetConfig(l);}

	// ================================================================== URL Access

	static final int URL_ACCESS_VERSION=0x02318000; // NumVersion 2.3.1 final, as on Mac OS 9.2.2 (Acrobat Reader 5 disables its Internet features below 2.3)
	static final String USER_AGENT="URLAccess/2.0 (Macintosh; PPC)";
	static final int NO_ERR=0,PARAM_ERR=-50,IO_ERR=-36,FNF_ERR=-43,DIR_NF_ERR=-120,PERM_ERR=-54,MEM_FULL_ERR=-108,USER_CANCELED=-128;
	static final int INVALID_REFERENCE=-30770,DESTINATION_EXISTS=-30772,INVALID_URL=-30773,UNSUPPORTED_SCHEME=-30774,SERVER_BUSY=-30775,
		AUTHENTICATION=-30776,NOT_YET_KNOWN=-30777,UNKNOWN_PROPERTY=-30778,BUFFER_TOO_SMALL=-30779,UNSETTABLE=-30780,INVALID_CALL=-30781;
	/** Open Transport errors for network failures: kEACCESErr (the Sandbox said no), kOTBadNameErr, kETIMEDOUTErr, kECONNREFUSEDErr, kEHOSTUNREACHErr. */
	static final int ACCESS_DENIED=-3212,BAD_NAME=-3170,TIMED_OUT=-3259,CONNECTION_REFUSED=-3260,HOST_UNREACHABLE=-3264;
	/** URLOpenFlags. */
	static final int REPLACE_EXISTING=1,UPLOAD=1<<5,NO_AUTO_REDIRECT=1<<10,DO_NOT_DELETE_ON_ERROR=1<<12,RESUME_DOWNLOAD=1<<13;
	/** URLState values; the events share the numbers (kURLDataAvailableEvent = kURLDataAvailableState = $15). */
	static final int S_NULL=0,S_INITIATING=1,S_LOOKING_UP=2,S_CONNECTING=3,S_RESOURCE_FOUND=4,S_DOWNLOADING=5,S_DATA_AVAILABLE=0x15,
		S_TRANSACTION_COMPLETE=6,S_ERROR=7,S_ABORTING=8,S_COMPLETED=9,S_UPLOADING=10;
	static final int EV_PERCENT=30,EV_PERIODIC=31,EV_PROPERTY_CHANGED=32;
	/** uppURLNotifyProcInfo, for a notifier that is a routine descriptor after all. */
	private static final int NOTIFY_PROC_INFO=0x00000ff0;
	/** Downloaded data waiting for URLGetBuffer before the transfer thread pauses (an asynchronous download the program drains). */
	private static final int MAX_PENDING=4<<20,CHUNK=16384;

	/** A URL reference (URLNewReference), or the transfer of a URLSimpleDownload/URLSimpleUpload. Guarded by itself. */
	private static final class Ref {
		final int address;final String url;final URI uri;final String scheme;
		// properties the program sets
		String method,requestHeader,userAgent=USER_AGENT,user,password;byte[] requestBody;int connectTimeout;
		// what the server said
		long resourceSize=-1,lastModified;String mime,charset,status,responseHeader,redirected;boolean headersKnown;
		// the transfer
		int flags;boolean opened,upload,bufferMode,collect;File file,source;long resumeFrom;
		volatile boolean aborted,done;volatile int error;volatile int phase=S_NULL;volatile long transferred;
		boolean tcPosted,querySeen,completed;int seenRound=-1;
		final ArrayDeque<byte[]> chunks=new ArrayDeque<byte[]>();long pending;
		final Set<Integer> handedOut=new HashSet<Integer>();
		int notify,mask,context,info;
		/** Events for the notifier: {Integer event, String property}. */
		final ConcurrentLinkedQueue<Object[]> events=new ConcurrentLinkedQueue<Object[]>();
		volatile Closeable stream;volatile Thread worker;int lastPercent=-1;
		Ref(int address,String url,URI uri){this.address=address;this.url=url;this.uri=uri;scheme=uri.getScheme().toLowerCase(Locale.ROOT);}
	}
	/** URL references by address (touched on the emulation thread only). */
	private final Map<Integer,Ref> refs=new LinkedHashMap<Integer,Ref>();
	private final AtomicBoolean deliveryScheduled=new AtomicBoolean();
	private boolean delivering;private int deliveryRound;
	/** Guest C strings of property names, for URLCallbackInfo.property. */
	private final Map<String,Integer> propertyNames=new HashMap<String,Integer>();

	private void defineUrlAccess(PpcLibraries l){
		l.register(null,"URLGetURLAccessVersion",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),URL_ACCESS_VERSION);ret(0);});
		l.register(null,"URLNewReference",(r,c)->{ // (url, *urlRef)
			if(arg(1)!=0)memory.writeLong(arg(1),0);
			Object o=newRef(arg(0));if(o instanceof Integer){ret((Integer)o);return;}
			Ref u=(Ref)o;refs.put(u.address,u);if(arg(1)!=0)memory.writeLong(arg(1),u.address);ret(0);});
		l.register(null,"URLDisposeReference",(r,c)->{Ref u=refs.remove(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			stop(u);if(u.info!=0)memory.free(u.info);memory.free(u.address);ret(0);});
		l.register(null,"URLSimpleDownload",(r,c)->{ // (url, destination, destinationHandle, openFlags, eventProc, userContext)
			Object o=newRef(arg(0));if(o instanceof Integer){ret((Integer)o);return;}
			Ref u=(Ref)o;try{ret(download(u,arg(1),arg(2),arg(3)));}finally{memory.free(u.address);}});
		l.register(null,"URLDownload",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}if(u.opened){ret(INVALID_CALL);return;}ret(download(u,arg(1),arg(2),arg(3)));});
		l.register(null,"URLSimpleUpload",(r,c)->{ // (url, source, openFlags, eventProc, userContext)
			Object o=newRef(arg(0));if(o instanceof Integer){ret((Integer)o);return;}
			Ref u=(Ref)o;try{ret(upload(u,arg(1),arg(2)));}finally{memory.free(u.address);}});
		l.register(null,"URLUpload",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}if(u.opened){ret(INVALID_CALL);return;}ret(upload(u,arg(1),arg(2)));});
		l.register(null,"URLOpen",(r,c)->{ // (urlRef, fileSpec, openFlags, notifyProc, eventRegister, userContext): asynchronous
			Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}if(u.opened){ret(INVALID_CALL);return;}
			int flags=arg(2),e=(flags&UPLOAD)!=0?prepareUpload(u,arg(1),flags):prepareDownload(u,arg(1),0,flags);
			if(e!=0){ret(e);return;}
			u.notify=arg(3);u.mask=arg(4);u.context=arg(5);if(u.notify!=0)u.info=rt.allocate(20,4);
			start(u);ret(0);});
		l.register(null,"URLAbort",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			if(!u.opened||u.done||u.aborted){ret(INVALID_CALL);return;}
			abort(u);post(u,S_ABORTING,null);ret(0);});
		l.register(null,"URLIdle",(r,c)->{deliver();ret(0);});
		l.register(null,"URLGetDataAvailable",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			long n;synchronized(u){n=u.pending;}if(arg(1)!=0)memory.writeLong(arg(1),(int)Math.min(Integer.MAX_VALUE,n));ret(0);});
		l.register(null,"URLGetBuffer",(r,c)->{ // (urlRef, **buffer, *bufferSize): the next buffer of downloaded data, 0 bytes when none is waiting
			Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			if(arg(1)==0||arg(2)==0){ret(PARAM_ERR);return;}
			byte[] chunk;synchronized(u){chunk=u.chunks.poll();if(chunk!=null){u.pending-=chunk.length;u.notifyAll();}}
			if(chunk==null){memory.writeLong(arg(1),0);memory.writeLong(arg(2),0);ret(u.error!=0&&!u.aborted?u.error:0);return;}
			int p=rt.allocate(chunk.length,4);memory.load(p,chunk);u.handedOut.add(p);
			memory.writeLong(arg(1),p);memory.writeLong(arg(2),chunk.length);
			transactionComplete(u);ret(0);});
		l.register(null,"URLReleaseBuffer",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			if(arg(1)==0){ret(0);return;}if(!u.handedOut.remove(arg(1))){ret(INVALID_CALL);return;}memory.free(arg(1));ret(0);});
		l.register(null,"URLGetCurrentState",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			int s=state(u);
			if(s==S_TRANSACTION_COMPLETE||s==S_ABORTING&&u.done){ // shown once, then kURLCompletedState (programs wait for either)
				boolean seen;synchronized(u){seen=u.querySeen;u.querySeen=true;}
				if(seen){complete(u,true);s=state(u);}}
			if(arg(1)!=0)memory.writeLong(arg(1),s);ret(0);});
		l.register(null,"URLGetError",(r,c)->{Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}if(arg(1)!=0)memory.writeLong(arg(1),u.error);ret(0);});
		l.register(null,"URLGetPropertySize",(r,c)->{ // (urlRef, property, *propertySize)
			Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			Object v=property(u,rt.cString(arg(1)));if(v instanceof Integer){if(arg(2)!=0)memory.writeLong(arg(2),0);ret((Integer)v);return;}
			if(arg(2)!=0)memory.writeLong(arg(2),((byte[])v).length);ret(0);});
		l.register(null,"URLGetProperty",(r,c)->{ // (urlRef, property, propertyBuffer, bufferSize)
			Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			Object v=property(u,rt.cString(arg(1)));if(v instanceof Integer){ret((Integer)v);return;}
			byte[] b=(byte[])v;if(arg(3)<b.length||arg(2)==0){ret(BUFFER_TOO_SMALL);return;}
			memory.load(arg(2),b);ret(0);});
		l.register(null,"URLSetProperty",(r,c)->{ // (urlRef, property, propertyBuffer, bufferSize)
			Ref u=refs.get(arg(0));if(u==null){ret(INVALID_REFERENCE);return;}
			int n=Math.max(0,arg(3));ret(setProperty(u,rt.cString(arg(1)),arg(2)==0?new byte[0]:memory.readBytes(arg(2),n)));});
		l.register(null,"URLGetFileInfo",(r,c)->{ // (fName, *fType, *fCreator): the Finder type a downloaded file of that name gets
			int[] tc=typeForName(arg(0)==0?"":rt.toolbox.readPascal(arg(0)),null);
			if(arg(1)!=0)memory.writeLong(arg(1),tc[0]);if(arg(2)!=0)memory.writeLong(arg(2),tc[1]);ret(0);});
	}

	// ------------------------------------------------------------------ references

	/** A new reference to the URL (a C string) at {@code p}, not yet registered; an Integer error when the URL is not usable. */
	private Object newRef(int p) throws M68kCpuException {
		if(p==0)return INVALID_URL;
		String url=new String(rt.cBytes(p),StandardCharsets.ISO_8859_1).trim();
		URI uri=parseUri(url);if(uri==null)return INVALID_URL;
		String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
		if(!scheme.equals("http")&&!scheme.equals("https")&&!scheme.equals("ftp")&&!scheme.equals("file"))return UNSUPPORTED_SCHEME;
		if(!scheme.equals("file")&&(uri.getHost()==null||uri.getHost().isEmpty()))return INVALID_URL;
		Ref u=new Ref(rt.allocate(16,4),url,uri);memory.writeLong(u.address,0x55524c72); // 'URLr'
		return u;
	}
	/** A URI of a URL as programs wrote them: spaces, 8-bit bytes and the other characters URIs forbid are %-escaped first. */
	static URI parseUri(String url){
		if(url.isEmpty())return null;
		StringBuilder sb=new StringBuilder();
		for(int i=0;i<url.length();i++){char ch=url.charAt(i);
			if(ch<=0x20||ch>=0x7f||"\"<>\\^`{|}".indexOf(ch)>=0){if(ch>=0x100)ch='?';sb.append(String.format("%%%02X",(int)ch));}else sb.append(ch);}
		try{URI u=new URI(sb.toString());return u.getScheme()==null||!u.getScheme().matches("[A-Za-z][A-Za-z0-9+.-]*")?null:u;}
		catch(URISyntaxException e){return null;}
	}
	/** The last path component of the URL (the name a download gets), "index.html" for a directory. */
	private static String resourceName(Ref u){
		String path=u.uri.getPath();if(path==null)path="";
		int slash=path.lastIndexOf('/');String name=path.substring(slash+1);
		if(name.isEmpty())name=u.scheme.startsWith("http")?"index.html":"Untitled";
		name=name.replace(':','-');return name.length()>31?name.substring(0,31):name;
	}

	// ------------------------------------------------------------------ starting a transfer

	/** Sets a download up: to the host file an FSSpec names (a folder: a file named after the URL inside it), into a Handle
	 *  (collected, for the synchronous calls) or into guest buffers (URLOpen without a file). */
	private int prepareDownload(Ref u,int spec,int handle,int flags) throws M68kException {
		u.flags=flags;u.opened=true;
		if(spec!=0){
			File f;try{f=rt.toolbox.files.fileForSpec(spec);}catch(IOException e){f=null;}
			if(f==null)return DIR_NF_ERR;
			if(f.isDirectory())f=new File(f,resourceName(u));
			File parent=f.getAbsoluteFile().getParentFile();if(parent==null||!parent.isDirectory())return DIR_NF_ERR;
			boolean replace=(flags&REPLACE_EXISTING)!=0,resume=(flags&RESUME_DOWNLOAD)!=0&&!replace&&u.scheme.startsWith("http");
			if(f.isDirectory())return DESTINATION_EXISTS;
			if(f.exists()&&!replace&&!resume)return DESTINATION_EXISTS;
			if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,rt.toolbox.name))return PERM_ERR;
			u.file=f;u.resumeFrom=resume&&f.exists()?f.length():0;
		}
		else if(handle!=0){u.bufferMode=true;u.collect=true;}
		else u.bufferMode=true;
		return localSource(u);
	}
	/** Sets an upload up from the file an FSSpec names (http(s) PUT or the program's method, ftp STOR). */
	private int prepareUpload(Ref u,int spec,int flags) throws M68kException {
		u.flags=flags;u.opened=true;u.upload=true;u.bufferMode=true;u.collect=true; // the reply (if any) is kept for the properties only
		if(u.scheme.equals("file"))return UNSUPPORTED_SCHEME;
		if(spec==0)return PARAM_ERR;
		File f;try{f=rt.toolbox.files.fileForSpec(spec);}catch(IOException e){f=null;}
		if(f==null||!f.isFile())return FNF_ERR;
		if(!Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name))return PERM_ERR;
		u.source=f;return NO_ERR;
	}
	/** A file: URL's host file ("file:///Volume/Folder/Name", as URL Access wrote them; else a host path, as Mac OS X wrote
	 *  them), resolved on the emulation thread. */
	private int localSource(Ref u){
		if(!u.scheme.equals("file"))return NO_ERR;
		String path=u.uri.getPath();if(path==null)return INVALID_URL;
		while(path.startsWith("/"))path=path.substring(1);
		if(path.endsWith("/"))path=path.substring(0,path.length()-1);
		File f;try{f=rt.toolbox.files.fileForClassicPath(path.replace('/',':'));}catch(IOException e){f=null;}
		if(f==null||!f.exists())f=new File(File.separator+path.replace('/',File.separatorChar));
		if(f==null||!f.isFile())return FNF_ERR;
		if(!Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name))return PERM_ERR;
		u.source=f;return NO_ERR;
	}
	private void start(Ref u){
		u.phase=S_INITIATING;post(u,S_INITIATING,null);
		final String requester=rt.toolbox.name;
		Thread t=new Thread(()->transfer(u,requester),"URL Access "+u.url);t.setDaemon(true);u.worker=t;t.start();
	}
	/** A synchronous download: runs the transfer and waits, the VM's deferred work and timers running meanwhile. */
	private int download(Ref u,int spec,int handle,int flags) throws M68kException {
		if(spec==0&&handle==0)return PARAM_ERR;
		int e=prepareDownload(u,spec,spec!=0?0:handle,flags);if(e!=0)return e;
		start(u);await(u);
		if(u.error!=0)return u.error;
		if(spec!=0){ // a folder was named: the FSSpec now names the file
			try{File named=rt.toolbox.files.fileForSpec(spec);if(named!=null&&named.isDirectory())rt.toolbox.files.writeFileSpec(spec,u.file);}catch(IOException ignored){}
			return NO_ERR;
		}
		synchronized(u){ // into the Handle, resized to the data
			int n=(int)Math.min(Integer.MAX_VALUE,u.pending);
			rt.toolbox("SetHandleSize",handle,n);if(rt.toolbox("GetHandleSize",handle)!=n)return MEM_FULL_ERR;
			int p=memory.readLong(handle),at=0;
			for(byte[] chunk:u.chunks){memory.load(p+at,chunk);at+=chunk.length;}
			u.chunks.clear();u.pending=0;
		}
		return NO_ERR;
	}
	private int upload(Ref u,int spec,int flags) throws M68kException {
		int e=prepareUpload(u,spec,flags);if(e!=0)return e;
		start(u);await(u);
		synchronized(u){u.chunks.clear();u.pending=0;}
		return u.error;
	}
	private void await(Ref u) throws M68kException {
		while(!u.done){
			rt.toolbox.runDeferred();rt.toolbox.runTimers();
			if(rt.cpu68.abortRequested()){abort(u);throw new M68kAbortException(rt.cpu.pc);}
			synchronized(u){if(!u.done)try{u.wait(10);}catch(InterruptedException ex){Thread.currentThread().interrupt();abort(u);return;}}
		}
	}
	private void abort(Ref u){
		synchronized(u){u.aborted=true;if(u.error==0)u.error=USER_CANCELED;u.notifyAll();}
		Closeable s=u.stream;if(s!=null)try{s.close();}catch(IOException|RuntimeException ignored){}
		Thread t=u.worker;if(t!=null)t.interrupt();
	}
	/** URLDisposeReference: an unfinished transfer is abandoned. */
	private void stop(Ref u){if(u.opened&&!u.done)abort(u);}

	// ------------------------------------------------------------------ states and notifications (emulation thread)

	private static int state(Ref u){
		synchronized(u){
			if(u.aborted)return u.completed?S_COMPLETED:S_ABORTING;
			if(u.error!=0)return S_ERROR;
			if(!u.chunks.isEmpty())return S_DATA_AVAILABLE;
			if(!u.done)return u.phase;
			return u.completed?S_COMPLETED:S_TRANSACTION_COMPLETE;
		}
	}
	/** kURLTransactionCompleteEvent: the transfer is over and the program has taken every buffer. */
	private void transactionComplete(Ref u){
		boolean now;synchronized(u){now=u.done&&u.error==0&&!u.aborted&&u.chunks.isEmpty()&&!u.tcPosted;if(now)u.tcPosted=true;}
		if(now)post(u,S_TRANSACTION_COMPLETE,null);
	}
	/** kURLCompletedState after kURLTransactionCompleteState or kURLAbortingState was seen: by an earlier state query
	 *  ({@code query}), or by the notifier in an earlier delivery round - so a program that idles and then asks for the state
	 *  sees kURLTransactionCompleteState once too. */
	private void complete(Ref u,boolean query){
		boolean now;synchronized(u){now=!u.completed&&u.done&&(query?u.querySeen:u.seenRound>=0&&u.seenRound<deliveryRound)&&(u.aborted||u.error==0&&u.chunks.isEmpty());if(now)u.completed=true;}
		if(now)post(u,S_COMPLETED,null);
	}
	/** Queues {@code event} for the reference's notifier and asks for a delivery at the program's next event call. */
	private void post(Ref u,int event,String property){
		if(u.notify==0)return;
		u.events.add(new Object[]{event,property});
		scheduleDelivery();
	}
	private void scheduleDelivery(){
		if(deliveryScheduled.compareAndSet(false,true))rt.toolbox.laterAtEvent(()->{
			deliveryScheduled.set(false);
			try{deliver();}catch(M68kException e){throw Toolbox68k.wrap(e);}});
	}
	/** Calls the notifiers with their queued events (URLIdle and event calls; never re-entered from a notifier). */
	private void deliver() throws M68kException {
		if(delivering)return;delivering=true;deliveryRound++;
		try{
			for(Ref u:new ArrayList<Ref>(refs.values())){
				if(u.notify==0||!refs.containsKey(u.address))continue;
				for(int round=0;round<4;round++){
					Object[] e;
					while((e=u.events.poll())!=null){
						int event=(Integer)e[0];
						if(!refs.containsKey(u.address))break;
						callNotifier(u,event,(String)e[1]);
						if(event==S_TRANSACTION_COMPLETE||event==S_ABORTING)synchronized(u){if(u.seenRound<0)u.seenRound=deliveryRound;}
					}
					if(u.done)transactionComplete(u);
					complete(u,false);
					if(u.events.isEmpty())break;
				}
				if(u.opened&&!u.done&&refs.containsKey(u.address))callNotifier(u,EV_PERIODIC,null);
			}
		}finally{delivering=false;}
	}
	private void callNotifier(Ref u,int event,String property) throws M68kException {
		if(u.notify==0||event<1||event>32||(u.mask&(1<<(event-1)))==0)return;
		int info=u.info; // URLCallbackInfo: version, urlRef, property, currentSize, systemEvent
		memory.writeLong(info,0);memory.writeLong(info+4,u.address);memory.writeLong(info+8,property==null?0:propertyName(property));
		memory.writeLong(info+12,(int)Math.min(Integer.MAX_VALUE,u.transferred));memory.writeLong(info+16,0);
		if(rt.isRoutineDescriptor(u.notify))rt.callUniversal(u.notify,NOTIFY_PROC_INFO,new int[]{u.context,event,info});
		else rt.cpu.call(u.notify,u.context,event,info);
	}
	private int propertyName(String name) throws M68kCpuException {
		Integer p=propertyNames.get(name);
		if(p==null){byte[] b=name.getBytes(StandardCharsets.ISO_8859_1);p=rt.allocate(b.length+1,4);memory.load(p,b);propertyNames.put(name,p);}
		return p;
	}

	// ------------------------------------------------------------------ properties

	private static byte[] cz(String s){byte[] b=s.getBytes(StandardCharsets.ISO_8859_1);return java.util.Arrays.copyOf(b,b.length+1);}
	private static byte[] long4(long v){int i=(int)Math.min(0xffffffffL,Math.max(0,v));return new byte[]{(byte)(i>>>24),(byte)(i>>>16),(byte)(i>>>8),(byte)i};}
	/** A property's value (strings as C strings with their NUL), or an Integer error. */
	private Object property(Ref u,String name){
		if(name==null)return UNKNOWN_PROPERTY;
		synchronized(u){
			switch(name){
				case "URLString":return cz(u.url);
				case "URLResourceSize":return u.headersKnown&&u.resourceSize>=0?long4(u.resourceSize):(Object)NOT_YET_KNOWN;
				case "URLLastModifiedTime":return u.headersKnown&&u.lastModified>0?long4(FileManager68k.macDate(u.lastModified)&0xffffffffL):(Object)NOT_YET_KNOWN;
				case "URLMIMEType":return u.mime!=null?cz(u.mime):(Object)NOT_YET_KNOWN;
				case "URLCharacterSet":return u.charset!=null?cz(u.charset):(Object)NOT_YET_KNOWN;
				case "URLFileType":return long4(typeForName(resourceName(u),u.mime)[0]&0xffffffffL);
				case "URLFileCreator":return long4(typeForName(resourceName(u),u.mime)[1]&0xffffffffL);
				case "URLResourceName":return cz(resourceName(u));
				case "URLHost":return cz(u.uri.getHost()==null?"":u.uri.getHost());
				case "URLUserName":return cz(u.user!=null?u.user:userInfo(u,0));
				case "URLPassword":return cz(u.password!=null?u.password:userInfo(u,1));
				case "URLStatusString":return u.status!=null?cz(u.status):(Object)NOT_YET_KNOWN;
				case "URLIsSecure":return new byte[]{(byte)(u.scheme.equals("https")?1:0)};
				case "URLTotalItems":return long4(1);
				case "URLConnectTimeout":return long4(u.connectTimeout);
				case "URLHTTPRequestMethod":return cz(method(u));
				case "URLHTTPRequestHeader":return cz(u.requestHeader==null?"":u.requestHeader);
				case "URLHTTPRequestBody":return u.requestBody==null?new byte[0]:u.requestBody.clone();
				case "URLHTTPRespHeader":return u.responseHeader!=null?cz(u.responseHeader):(Object)NOT_YET_KNOWN;
				case "URLHTTPUserAgent":return cz(u.userAgent);
				case "URLHTTPRedirectedURL":return u.redirected!=null?cz(u.redirected):(Object)NOT_YET_KNOWN;
				case "URLAuthType":case "URLCertificate":case "URLSSLCipherSuite":return NOT_YET_KNOWN;
				default:return UNKNOWN_PROPERTY;
			}
		}
	}
	private static String text(byte[] b){int n=b.length;while(n>0&&b[n-1]==0)n--;return new String(b,0,n,StandardCharsets.ISO_8859_1);}
	private int setProperty(Ref u,String name,byte[] value){
		if(name==null)return UNKNOWN_PROPERTY;
		synchronized(u){
			switch(name){
				case "URLUserName":u.user=text(value);return NO_ERR;
				case "URLPassword":u.password=text(value);return NO_ERR;
				case "URLHTTPRequestMethod":{String m=text(value).trim().toUpperCase(Locale.ROOT);u.method=m.isEmpty()?null:m;return NO_ERR;}
				case "URLHTTPRequestHeader":u.requestHeader=text(value);return NO_ERR;
				case "URLHTTPRequestBody":u.requestBody=value;return NO_ERR;
				case "URLHTTPUserAgent":u.userAgent=text(value);return NO_ERR;
				case "URLConnectTimeout":if(value.length<4)return PARAM_ERR;u.connectTimeout=(value[0]&0xff)<<24|(value[1]&0xff)<<16|(value[2]&0xff)<<8|value[3]&0xff;return NO_ERR;
				case "URLSSLCipherSuite":return NO_ERR; // Java picks the cipher suites
				case "URLString":case "URLResourceSize":case "URLLastModifiedTime":case "URLMIMEType":case "URLFileType":case "URLFileCreator":
				case "URLCharacterSet":case "URLResourceName":case "URLHost":case "URLAuthType":case "URLStatusString":case "URLIsSecure":
				case "URLCertificate":case "URLTotalItems":case "URLHTTPRespHeader":case "URLHTTPRedirectedURL":return UNSETTABLE;
				default:return UNKNOWN_PROPERTY;
			}
		}
	}
	private static String method(Ref u){return u.method!=null?u.method:u.upload?"PUT":u.requestBody!=null?"POST":"GET";}
	/** The user name (0) or password (1) written into the URL ("ftp://user:pass@host/"), "" when none. */
	private static String userInfo(Ref u,int part){
		String info=u.uri.getUserInfo();if(info==null)return "";
		int colon=info.indexOf(':');return part==0?(colon<0?info:info.substring(0,colon)):(colon<0?"":info.substring(colon+1));
	}
	/** Finder type and creator for a downloaded file: the host's mapping of the name, else the MIME type; 'BINA'/'????' otherwise. */
	static int[] typeForName(String name,String mime){
		try{byte[] info=FinderInfo68k.guess(new File(new File(System.getProperty("java.io.tmpdir"),"hz-url-names-"+System.nanoTime()),name));
			if(info!=null)return new int[]{u4(info,0),u4(info,4)};}catch(RuntimeException ignored){}
		String m=mime==null?"":mime.toLowerCase(Locale.ROOT);
		if(m.startsWith("text/"))return new int[]{fourcc("TEXT"),fourcc("ttxt")};
		if(m.equals("image/gif"))return new int[]{fourcc("GIFf"),fourcc("ogle")};
		if(m.equals("image/jpeg"))return new int[]{fourcc("JPEG"),fourcc("ogle")};
		if(m.equals("image/png"))return new int[]{fourcc("PNGf"),fourcc("ogle")};
		if(m.equals("application/pdf"))return new int[]{fourcc("PDF "),fourcc("CARO")};
		if(m.equals("application/mac-binhex40"))return new int[]{fourcc("TEXT"),fourcc("BnHq")};
		if(m.contains("stuffit"))return new int[]{fourcc("SIT!"),fourcc("SIT!")};
		return new int[]{fourcc("BINA"),fourcc("????")};
	}
	private static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;}
	static int fourcc(String s){return u4(s.getBytes(StandardCharsets.ISO_8859_1),0);}

	// ------------------------------------------------------------------ the transfer thread (no guest memory here)

	/** The Sandbox refused the connection. */
	private static final class RefusedException extends IOException {private static final long serialVersionUID=1L;RefusedException(String host){super("network access to "+host+" not allowed");}}
	private static final class StatusException extends IOException {private static final long serialVersionUID=1L;final int code;StatusException(int code){super("HTTP "+code);this.code=code;}}

	private void transfer(Ref u,String requester){
		try{
			switch(u.scheme){
				case "http":case "https":http(u,requester);break;
				case "ftp":ftp(u,requester);break;
				default:local(u);break;
			}
			if(u.file!=null&&!u.upload&&!u.aborted)typeDownloadedFile(u);
		}catch(Throwable t){
			if(!u.aborted){int e=errorFor(t);synchronized(u){u.error=e;}post(u,S_ERROR,null);}
			if(u.file!=null&&!u.upload&&(u.flags&DO_NOT_DELETE_ON_ERROR)==0&&u.resumeFrom==0)u.file.delete();
		}finally{
			u.stream=null;
			if(u.aborted&&u.file!=null&&!u.upload&&(u.flags&DO_NOT_DELETE_ON_ERROR)==0&&u.resumeFrom==0)u.file.delete();
			synchronized(u){u.done=true;u.notifyAll();}
			transactionComplete(u);scheduleDelivery();
		}
	}
	private static int errorFor(Throwable t){
		if(t instanceof RefusedException)return ACCESS_DENIED;
		if(t instanceof StatusException){int code=((StatusException)t).code;
			return code==401||code==403||code==407?AUTHENTICATION:code==404||code==410?FNF_ERR:code==400||code==414?INVALID_URL:code>=500?SERVER_BUSY:IO_ERR;}
		if(t instanceof UnknownHostException)return BAD_NAME;
		if(t instanceof NoRouteToHostException)return HOST_UNREACHABLE;
		if(t instanceof ConnectException)return CONNECTION_REFUSED;
		if(t instanceof SocketTimeoutException)return TIMED_OUT;
		if(t instanceof FileNotFoundException)return FNF_ERR;
		if(t instanceof MalformedURLException||t instanceof IllegalArgumentException)return INVALID_URL;
		if(t.getClass().getName().contains("Login"))return AUTHENTICATION; // sun.net.ftp.FtpLoginException
		if(t instanceof OutOfMemoryError)return MEM_FULL_ERR;
		return IO_ERR;
	}
	private static void gate(URL url,String requester) throws RefusedException {
		int port=url.getPort()<0?url.getDefaultPort():url.getPort();
		if(!Sandbox.allowNetwork(requester,Sandbox.NET_CONNECT,url.getHost(),port))throw new RefusedException(url.getHost());
	}

	private void http(Ref u,String requester) throws IOException {
		URL url=u.uri.toURL();String method;byte[] body;String header,agent,user,password;int timeout;
		synchronized(u){method=method(u);body=u.upload?null:u.requestBody;header=u.requestHeader;agent=u.userAgent;user=u.user;password=u.password;timeout=u.connectTimeout;}
		boolean sendFile=u.upload;
		if(user==null&&url.getUserInfo()!=null){user=userInfo(u,0);password=userInfo(u,1);}
		for(int hop=0;;hop++){
			u.phase=S_LOOKING_UP;gate(url,requester);
			if(u.aborted)return;
			u.phase=S_CONNECTING;
			HttpURLConnection c=(HttpURLConnection)url.openConnection();
			c.setInstanceFollowRedirects(false);c.setConnectTimeout(timeout>0?timeout*1000:60000);c.setReadTimeout(120000);
			c.setRequestMethod(method);c.setRequestProperty("User-Agent",agent);
			if(user!=null&&!user.isEmpty())c.setRequestProperty("Authorization","Basic "+java.util.Base64.getEncoder().encodeToString((user+":"+(password==null?"":password)).getBytes(StandardCharsets.ISO_8859_1)));
			if(header!=null)for(String line:header.split("\r\n|\r|\n")){int colon=line.indexOf(':');if(colon>0)c.addRequestProperty(line.substring(0,colon).trim(),line.substring(colon+1).trim());}
			if(u.resumeFrom>0)c.setRequestProperty("Range","bytes="+u.resumeFrom+"-");
			u.stream=c::disconnect;
			if(sendFile||body!=null){
				c.setDoOutput(true);long length=sendFile?u.source.length():body.length;c.setFixedLengthStreamingMode(length);
				if(sendFile){u.phase=S_UPLOADING;post(u,S_UPLOADING,null);}
				try(OutputStream o=c.getOutputStream()){
					if(sendFile)try(InputStream in=new FileInputStream(u.source)){copy(u,in,o,length);}
					else o.write(body);
				}
			}
			int code=c.getResponseCode();
			String location=c.getHeaderField("Location");
			if(code>=300&&code<400&&code!=304&&location!=null&&(u.flags&NO_AUTO_REDIRECT)==0&&hop<10){
				c.disconnect();url=new URL(url,location);
				synchronized(u){u.redirected=url.toString();}
				if(code==303||(code==301||code==302)&&!method.equals("GET")&&!method.equals("HEAD")){method="GET";body=null;sendFile=false;}
				continue;
			}
			record(u,c,code);
			if(code>=400){c.disconnect();throw new StatusException(code);}
			if(u.upload){try(InputStream in=c.getInputStream()){in.readAllBytes();}return;} // the server's reply to an upload is not data
			if(method.equals("HEAD")){c.disconnect();return;}
			boolean append=u.resumeFrom>0&&code==206;
			receive(u,c.getInputStream(),append);
			return;
		}
	}
	private void ftp(Ref u,String requester) throws IOException {
		String user,password;synchronized(u){user=u.user;password=u.password;}
		URI uri=u.uri;
		if(u.upload&&uri.getPath()!=null&&uri.getPath().endsWith("/"))uri=uri.resolve(u.source.getName().replace(' ','_'));
		if(user!=null&&!user.isEmpty())try{uri=new URI("ftp",user+(password==null?"":":"+password),uri.getHost(),uri.getPort(),uri.getPath(),null,null);}catch(URISyntaxException e){throw new MalformedURLException(e.getMessage());}
		URL url=uri.toURL();
		u.phase=S_LOOKING_UP;gate(url,requester);
		if(u.aborted)return;
		u.phase=S_CONNECTING;
		URLConnection c=url.openConnection();c.setConnectTimeout(60000);c.setReadTimeout(120000);
		if(u.upload){
			c.setDoOutput(true);u.phase=S_UPLOADING;post(u,S_UPLOADING,null);
			try(OutputStream o=c.getOutputStream();InputStream in=new FileInputStream(u.source)){u.stream=o;copy(u,in,o,u.source.length());}
			return;
		}
		InputStream in=c.getInputStream();u.stream=in;
		synchronized(u){u.resourceSize=c.getContentLengthLong();String type=URLConnection.guessContentTypeFromName(resourceName(u));u.mime=type!=null?type:"application/octet-stream";u.headersKnown=true;}
		found(u,false);
		receive(u,in,false);
	}
	private void local(Ref u) throws IOException {
		synchronized(u){u.resourceSize=u.source.length();u.lastModified=u.source.lastModified();String type=URLConnection.guessContentTypeFromName(u.source.getName());u.mime=type!=null?type:"application/octet-stream";u.headersKnown=true;}
		found(u,false);
		receive(u,new FileInputStream(u.source),false);
	}
	/** Records the response: status line, headers, size, type, date. */
	private void record(Ref u,HttpURLConnection c,int code){
		StringBuilder h=new StringBuilder();
		for(int i=0;i<1000;i++){String v=c.getHeaderField(i);if(v==null)break;String k=c.getHeaderFieldKey(i);h.append(k==null?v:k+": "+v).append("\r\n");}
		h.append("\r\n");
		String type=c.getContentType();
		synchronized(u){
			u.status=c.getHeaderField(0);if(u.status==null||u.status.contains(":"))u.status="HTTP/1.1 "+code;
			u.responseHeader=h.toString();u.lastModified=c.getLastModified();
			long length=c.getContentLengthLong();u.resourceSize=length>=0&&code==206?length+u.resumeFrom:length;
			if(type!=null){String[] parts=type.split(";");u.mime=parts[0].trim();
				for(int i=1;i<parts.length;i++){String p=parts[i].trim();if(p.toLowerCase(Locale.ROOT).startsWith("charset="))u.charset=p.substring(8).replace("\"","").trim();}}
			u.headersKnown=true;
		}
		if(code<400)found(u,true);
	}
	/** kURLResourceFoundEvent and the property changes it brings. */
	private void found(Ref u,boolean http){
		u.phase=S_RESOURCE_FOUND;post(u,S_RESOURCE_FOUND,null);
		List<String> known=new ArrayList<String>();
		synchronized(u){
			if(u.resourceSize>=0)known.add("URLResourceSize");if(u.mime!=null)known.add("URLMIMEType");if(u.lastModified>0)known.add("URLLastModifiedTime");
			if(u.charset!=null)known.add("URLCharacterSet");
			if(http){known.add("URLStatusString");known.add("URLHTTPRespHeader");if(u.redirected!=null)known.add("URLHTTPRedirectedURL");}
		}
		for(String p:known)post(u,EV_PROPERTY_CHANGED,p);
	}
	/** Reads the resource into the file or the buffers. */
	private void receive(Ref u,InputStream stream,boolean append) throws IOException {
		try(InputStream in=stream){
			u.stream=in;u.phase=S_DOWNLOADING;post(u,S_DOWNLOADING,null);
			if(append)u.transferred=u.resumeFrom;
			OutputStream out=u.file!=null?new FileOutputStream(u.file,append):null;
			try{
				byte[] buf=new byte[CHUNK];int fill=0;
				for(;;){
					if(u.aborted)throw new InterruptedIOException("aborted");
					int n=in.read(buf,fill,buf.length-fill);if(n<0)break;
					fill+=n;u.transferred+=n;percent(u);
					if(fill==buf.length||in.available()==0){sink(u,out,buf,fill);fill=0;}
				}
				if(fill>0)sink(u,out,buf,fill);
			}finally{if(out!=null)out.close();}
			if(u.aborted)throw new InterruptedIOException("aborted");
		}
	}
	private void sink(Ref u,OutputStream out,byte[] buf,int n) throws IOException {
		if(out!=null){out.write(buf,0,n);return;}
		byte[] chunk=java.util.Arrays.copyOf(buf,n);
		synchronized(u){
			while(!u.collect&&u.pending>=MAX_PENDING&&!u.aborted)try{u.wait(50);}catch(InterruptedException e){throw new InterruptedIOException("aborted");}
			u.chunks.add(chunk);u.pending+=n;
		}
		post(u,S_DATA_AVAILABLE,null);
	}
	private void copy(Ref u,InputStream in,OutputStream out,long total) throws IOException {
		byte[] buf=new byte[CHUNK];int n;
		while((n=in.read(buf))>0){if(u.aborted)throw new InterruptedIOException("aborted");out.write(buf,0,n);u.transferred+=n;percent(u,total);}
	}
	private void percent(Ref u){long size;synchronized(u){size=u.resourceSize;}percent(u,size);}
	private void percent(Ref u,long size){
		if(size<=0)return;int p=(int)Math.min(100,u.transferred*100/size);
		if(p!=u.lastPercent){u.lastPercent=p;post(u,EV_PERCENT,null);}
	}
	/** A downloaded file whose name the host cannot type gets the Finder type of its MIME type. */
	private void typeDownloadedFile(Ref u){
		try{
			if(!u.file.isFile()||FinderInfo68k.stored(u.file)!=null||FinderInfo68k.guess(u.file)!=null)return;
			int[] tc=typeForName("",u.mime);byte[] info=new byte[16];
			for(int i=0;i<4;i++){info[i]=(byte)(tc[0]>>>(24-8*i));info[4+i]=(byte)(tc[1]>>>(24-8*i));}
			FinderInfo68k.write(u.file,info);
		}catch(RuntimeException ignored){}
	}

	// ================================================================== Keychain Manager: installed, no keychain

	static final int KC_VERSION=0x02008000;
	static final int KC_NO_SUCH_KEYCHAIN=-25294,KC_INVALID_KEYCHAIN=-25295,KC_DUPLICATE_CALLBACK=-25297,KC_INVALID_CALLBACK=-25298,
		KC_ITEM_NOT_FOUND=-25300,KC_BUFFER_TOO_SMALL=-25301,KC_NO_SUCH_ATTR=-25303,KC_INVALID_ITEM=-25304,KC_INVALID_SEARCH=-25305,
		KC_NO_DEFAULT_KEYCHAIN=-25307,KC_READ_ONLY_ATTR=-25309;
	/** An item made by KCNewItem: in this run's memory only, never in a keychain. */
	private static final class KcItem {int itemClass,creator;final Map<Integer,byte[]> attributes=new HashMap<Integer,byte[]>();byte[] data=new byte[0];}
	private final Map<Integer,KcItem> kcItems=new HashMap<Integer,KcItem>();
	private final Set<Integer> kcCallbacks=new HashSet<Integer>();
	private boolean kcInteractionAllowed=true;

	private void defineKeychain(PpcLibraries l){
		l.register(null,"KCGetKeychainManagerVersion",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),KC_VERSION);ret(0);}); // KeychainManagerAvailable() passes NULL
		l.register(null,"KCCountKeychains",(r,c)->ret(0));
		l.register(null,"KCGetIndKeychain",(r,c)->{out(arg(1),0);ret(KC_NO_SUCH_KEYCHAIN);});
		l.register(null,"KCGetDefaultKeychain",(r,c)->{out(arg(0),0);ret(KC_NO_DEFAULT_KEYCHAIN);});
		l.register(null,"KCSetDefaultKeychain",(r,c)->ret(KC_INVALID_KEYCHAIN));
		l.register(null,"KCGetStatus",(r,c)->{out(arg(1),0);ret(arg(0)==0?KC_NO_DEFAULT_KEYCHAIN:KC_INVALID_KEYCHAIN);});
		l.register(null,"KCMakeKCRefFromFSSpec",(r,c)->{out(arg(1),0);ret(KC_NO_SUCH_KEYCHAIN);});
		l.register(null,"KCMakeKCRefFromAlias",(r,c)->{out(arg(1),0);ret(KC_NO_SUCH_KEYCHAIN);});
		l.register(null,"KCMakeAliasFromKCRef",(r,c)->{out(arg(1),0);ret(KC_INVALID_KEYCHAIN);});
		l.register(null,"KCReleaseKeychain",(r,c)->{out(arg(0),0);ret(0);});
		l.register(null,"KCGetKeychainName",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),0);ret(KC_INVALID_KEYCHAIN);});
		l.register(null,"kcgetkeychainname",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),0);ret(KC_INVALID_KEYCHAIN);});
		l.register(null,"KCGetKeychain",(r,c)->{out(arg(1),0);ret(kcItems.containsKey(arg(0))?KC_NO_SUCH_KEYCHAIN:KC_INVALID_ITEM);});
		for(String name:new String[]{"KCUnlock","kcunlock","KCLock","KCChangeSettings"})
			l.register(null,name,(r,c)->ret(arg(0)==0?KC_NO_DEFAULT_KEYCHAIN:KC_INVALID_KEYCHAIN));
		for(String name:new String[]{"KCCreateKeychain","kccreatekeychain"}) // the new-keychain dialog, "cancelled": no keychain is ever made
			l.register(null,name,(r,c)->{out(arg(1),0);ret(USER_CANCELED);});
		l.register(null,"KCSetInteractionAllowed",(r,c)->{kcInteractionAllowed=(arg(0)&0xff)!=0;ret(0);});
		l.register(null,"KCIsInteractionAllowed",(r,c)->ret(kcInteractionAllowed?1:0));
		l.register(null,"KCAddCallback",(r,c)->{if(arg(0)==0){ret(KC_INVALID_CALLBACK);return;}ret(kcCallbacks.add(arg(0))?0:KC_DUPLICATE_CALLBACK);}); // never called: nothing ever changes
		l.register(null,"KCRemoveCallback",(r,c)->ret(kcCallbacks.remove(arg(0))?0:KC_INVALID_CALLBACK));
		// items
		l.register(null,"KCNewItem",(r,c)->{ // (itemClass, itemCreator, length, data, *item)
			if(arg(4)==0){ret(PARAM_ERR);return;}
			KcItem it=new KcItem();it.itemClass=arg(0);it.creator=arg(1);if(arg(3)!=0&&arg(2)>0)it.data=memory.readBytes(arg(3),arg(2));
			int p=rt.allocate(8,4);memory.writeLong(p,0x4b436974);kcItems.put(p,it);memory.writeLong(arg(4),p);ret(0);});
		l.register(null,"KCSetAttribute",(r,c)->{ // (item, KCAttribute *{tag, length, data})
			KcItem it=kcItems.get(arg(0));if(it==null){ret(KC_INVALID_ITEM);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			int tag=memory.readLong(arg(1)),n=memory.readLong(arg(1)+4),data=memory.readLong(arg(1)+8);
			if(tag==fourcc("clas")){ret(KC_READ_ONLY_ATTR);return;}
			byte[] v=data==0||n<=0?new byte[0]:memory.readBytes(data,n);
			if(tag==fourcc("crtr")&&v.length>=4)it.creator=u4(v,0);else it.attributes.put(tag,v);ret(0);});
		l.register(null,"KCGetAttribute",(r,c)->{ // (item, KCAttribute *, *actualLength)
			KcItem it=kcItems.get(arg(0));if(it==null){ret(KC_INVALID_ITEM);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			int tag=memory.readLong(arg(1));byte[] v=tag==fourcc("clas")?long4(it.itemClass&0xffffffffL):tag==fourcc("crtr")?long4(it.creator&0xffffffffL):it.attributes.get(tag);
			if(v==null){ret(KC_NO_SUCH_ATTR);return;}
			out(arg(2),v.length);int room=memory.readLong(arg(1)+4),data=memory.readLong(arg(1)+8);
			if(room<v.length||data==0&&v.length>0){ret(KC_BUFFER_TOO_SMALL);return;}
			if(v.length>0)memory.load(data,v);memory.writeLong(arg(1)+4,v.length);ret(0);});
		l.register(null,"KCSetData",(r,c)->{KcItem it=kcItems.get(arg(0));if(it==null){ret(KC_INVALID_ITEM);return;}it.data=arg(2)==0||arg(1)<=0?new byte[0]:memory.readBytes(arg(2),arg(1));ret(0);});
		l.register(null,"KCGetData",(r,c)->{ // (item, maxLength, data, *actualLength)
			KcItem it=kcItems.get(arg(0));if(it==null){ret(KC_INVALID_ITEM);return;}
			out(arg(3),it.data.length);if(arg(2)==0||arg(1)<it.data.length){ret(arg(2)==0?0:KC_BUFFER_TOO_SMALL);return;}
			memory.load(arg(2),it.data);ret(0);});
		l.register(null,"KCReleaseItem",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}int p=memory.readLong(arg(0));
			if(kcItems.remove(p)==null){ret(p==0?0:KC_INVALID_ITEM);return;}memory.free(p);memory.writeLong(arg(0),0);ret(0);});
		l.register(null,"KCAddItem",(r,c)->ret(kcItems.containsKey(arg(0))?KC_NO_DEFAULT_KEYCHAIN:KC_INVALID_ITEM));
		for(String name:new String[]{"KCUpdateItem","KCDeleteItem"})l.register(null,name,(r,c)->ret(KC_INVALID_ITEM)); // no item is in a keychain
		l.register(null,"KCCopyItem",(r,c)->{out(arg(2),0);ret(kcItems.containsKey(arg(0))?KC_INVALID_KEYCHAIN:KC_INVALID_ITEM);});
		l.register(null,"KCFindFirstItem",(r,c)->{out(arg(2),0);out(arg(3),0);ret(KC_ITEM_NOT_FOUND);});
		l.register(null,"KCFindNextItem",(r,c)->{out(arg(1),0);ret(arg(0)==0?KC_INVALID_SEARCH:KC_ITEM_NOT_FOUND);});
		l.register(null,"KCReleaseSearch",(r,c)->{out(arg(0),0);ret(0);});
		l.register(null,"KCFindX509Certificates",(r,c)->{out(arg(4),0);ret(KC_ITEM_NOT_FOUND);});
		l.register(null,"KCChooseCertificate",(r,c)->{out(arg(1),0);ret(KC_ITEM_NOT_FOUND);});
		// passwords: (Pascal and C-string variants) - the item pointer is the last argument, *actualLength the one before for finds
		kcFind(l,8,"KCFindAppleSharePassword","kcfindapplesharepassword");
		kcFind(l,9,"KCFindInternetPassword","kcfindinternetpassword");
		kcFind(l,10,"KCFindInternetPasswordWithPath","kcfindinternetpasswordwithpath");
		kcFind(l,5,"KCFindGenericPassword","kcfindgenericpassword");
		kcAdd(l,7,"KCAddAppleSharePassword","kcaddapplesharepassword");
		kcAdd(l,8,"KCAddInternetPassword","kcaddinternetpassword");
		kcAdd(l,9,"KCAddInternetPasswordWithPath","kcaddinternetpasswordwithpath");
		kcAdd(l,4,"KCAddGenericPassword","kcaddgenericpassword");
	}
	private void out(int p,int v) throws M68kCpuException {if(p!=0)memory.writeLong(p,v);}
	/** A password search: nothing is found (no keychain); *actualLength and *item are cleared. */
	private void kcFind(PpcLibraries l,int itemArg,String... names){for(String name:names)l.register(null,name,(r,c)->{out(arg(itemArg-1),0);out(arg(itemArg),0);ret(KC_ITEM_NOT_FOUND);});}
	/** Adding a password: there is no default keychain to add it to (and nothing is stored). */
	private void kcAdd(PpcLibraries l,int itemArg,String... names){for(String name:names)l.register(null,name,(r,c)->{out(arg(itemArg),0);ret(KC_NO_DEFAULT_KEYCHAIN);});}

	// ================================================================== Internet Config

	static final int IC_PREF_NOT_FOUND=-666,IC_PERM_ERR=-667,IC_TRUNCATED=-670,IC_NO_URL=-673,BAD_COMPONENT_INSTANCE=0x80008001;
	static final int IC_NO_PERM=0,IC_READ_ONLY_PERM=1;
	private static final int ATTR_NO_CHANGE=-1,ATTR_LOCKED=1;
	private static final int TYPE_CHAR=0x54455854,KEY_DIRECT_OBJECT=0x2d2d2d2d,GURL=0x4755524c,TYPE_APPL_SIGNATURE=0x7369676e;
	/** Preferences set by the program in this run, by lower-cased key: {key bytes, value}; deleted ones are in {@link #icDeleted}. */
	private Map<String,byte[][]> icValues=new LinkedHashMap<String,byte[][]>();
	private Map<String,Integer> icAttributes=new HashMap<String,Integer>();
	private Set<String> icDeleted=new HashSet<String>();
	/** An Internet Config profile (InternetConfig.h's profile routines): a name and a preference set of its own - the three maps
	 *  above are the current profile's. Profile 1, "Default", is the one there is at start-up. */
	private static final class IcProfile {
		byte[] name;final Map<String,byte[][]> values;final Map<String,Integer> attributes;final Set<String> deleted;
		IcProfile(byte[] name,Map<String,byte[][]> values,Map<String,Integer> attributes,Set<String> deleted){this.name=name;this.values=values;this.attributes=attributes;this.deleted=deleted;}
	}
	private final Map<Integer,IcProfile> icProfiles=new LinkedHashMap<Integer,IcProfile>();
	private int icCurrentProfile=1,icNextProfile=2;
	static final int IC_PROFILE_NOT_FOUND=-676,IC_NIL_PROFILE_ID=0;
	private final Map<Integer,Integer> icPerms=new HashMap<Integer,Integer>();
	private int icSeed=1;
	/** PpcCarbon's ICFindPrefHandle (the built-in defaults: user strings, the web helpers), called through its own TVector. */
	private PpcRuntime.Native defaultFind;private int defaultFindVector;

	private void defineInternetConfig(PpcLibraries l){
		defaultFind=l.function("CarbonLib","ICFindPrefHandle");
		l.register(null,"ICBegin",(r,c)->{icPerms.put(arg(0),arg(1)&0xff);ret(0);});
		l.register(null,"ICEnd",(r,c)->{icPerms.remove(arg(0));ret(0);});
		l.register(null,"ICGetPerm",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),icPerms.getOrDefault(arg(0),IC_NO_PERM));ret(0);});
		l.register(null,"ICGetSeed",(r,c)->{out(arg(1),icSeed);ret(0);});
		l.register(null,"ICRefreshCaches",(r,c)->ret(0));
		l.register(null,"ICGetConfigName",(r,c)->{if(arg(2)!=0)rt.writePString(arg(2),(arg(1)&0xff)!=0?"HyperZebra Internet Preferences":"HyperZebra");ret(0);});
		l.register(null,"ICGetComponentInstance",(r,c)->{out(arg(1),0);ret(BAD_COMPONENT_INSTANCE);}); // IC "glue": no component
		l.register(null,"ICEditPreferences",(r,c)->ret(FNF_ERR)); // the Internet control panel (the IC application) is not there to launch
		l.register(null,"ICGetPref",(r,c)->{ // (inst, key, *attr, buf, *size)
			String key=icKey(arg(1));if(key==null){ret(PARAM_ERR);return;}
			byte[] v=icValue(key,arg(1));
			if(v==null){out(arg(2),ATTR_NO_CHANGE);out(arg(4),0);ret(IC_PREF_NOT_FOUND);return;}
			out(arg(2),icAttributes.getOrDefault(key,0));
			if(arg(3)==0){out(arg(4),v.length);ret(0);return;}
			int room=arg(4)==0?0:Math.max(0,memory.readLong(arg(4))),n=Math.min(room,v.length);
			memory.load(arg(3),v,0,n);out(arg(4),v.length);ret(n<v.length?IC_TRUNCATED:0);});
		l.register(null,"ICSetPref",(r,c)->{ // (inst, key, attr, buf, size)
			ret(icSet(arg(0),arg(1),arg(2),arg(3)==0?null:memory.readBytes(arg(3),Math.max(0,arg(4)))));});
		l.register(null,"ICSetPrefHandle",(r,c)->{ // (inst, key, attr, prefh)
			int h=arg(3);ret(icSet(arg(0),arg(1),arg(2),h==0?null:memory.readBytes(memory.readLong(h),rt.toolbox("GetHandleSize",h))));});
		l.register(null,"ICFindPrefHandle",(r,c)->{ // (inst, key, *attr, prefh)
			String key=icKey(arg(1));if(key==null||arg(3)==0){ret(PARAM_ERR);return;}
			byte[] v=icValue(key,arg(1));if(v==null){out(arg(2),ATTR_NO_CHANGE);ret(IC_PREF_NOT_FOUND);return;}
			out(arg(2),icAttributes.getOrDefault(key,0));ret(setHandle(arg(3),v));});
		l.register(null,"ICGetPrefHandle",(r,c)->{ // (inst, key, *attr, *prefh): a new handle, empty when there is no such preference
			String key=icKey(arg(1));if(key==null||arg(3)==0){ret(PARAM_ERR);return;}
			byte[] v=icValue(key,arg(1));out(arg(2),v==null?0:icAttributes.getOrDefault(key,0));
			int h=rt.toolbox("NewHandle",v==null?0:v.length);if(h==0){out(arg(3),0);ret(MEM_FULL_ERR);return;}
			if(v!=null)memory.load(memory.readLong(h),v);out(arg(3),h);ret(0);});
		l.register(null,"ICGetDefaultPref",(r,c)->{ // (inst, key, prefH)
			if(icKey(arg(1))==null||arg(2)==0){ret(PARAM_ERR);return;}
			byte[] v=defaultPref(arg(1));ret(v==null?IC_PREF_NOT_FOUND:setHandle(arg(2),v));});
		l.register(null,"ICDeletePref",(r,c)->{
			String key=icKey(arg(1));if(key==null){ret(PARAM_ERR);return;}
			if(icPerms.getOrDefault(arg(0),IC_NO_PERM)==IC_READ_ONLY_PERM){ret(IC_PERM_ERR);return;}
			if(icValue(key,arg(1))==null){ret(IC_PREF_NOT_FOUND);return;}
			icValues.remove(key);icDeleted.add(key);if(icCurrentProfile==1)icFile.delete(StackTextEncoding.ROMAN.decode(rt.pBytes(arg(1))));icSeed++;ret(0);});
		l.register(null,"ICCountPref",(r,c)->{out(arg(1),icKeys().size());ret(0);});
		l.register(null,"ICGetIndPref",(r,c)->{ // (inst, index, Str255 key): the preferences set in this run
			List<byte[]> keys=icKeys();int i=arg(1);if(i<1||i>keys.size()){ret(IC_PREF_NOT_FOUND);return;}
			byte[] k=keys.get(i-1);
			if(arg(2)!=0){memory.writeByte(arg(2),k.length);memory.load(arg(2)+1,k);}ret(0);});
		l.register(null,"ICParseURL",(r,c)->{ // (inst, hint, data, len, *selStart, *selEnd, url)
			if(arg(2)==0||arg(3)<0||arg(6)==0){ret(PARAM_ERR);return;}
			Found f=icFind(arg(1),arg(2),arg(3),arg(4),arg(5));if(f==null){ret(PARAM_ERR);return;}
			out(arg(4),f.start);out(arg(5),f.end);
			if(f.url==null){ret(IC_NO_URL);return;}
			ret(setHandle(arg(6),f.url.getBytes(StandardCharsets.ISO_8859_1)));});
		l.register(null,"ICLaunchURL",(r,c)->{ // (inst, hint, data, len, *selStart, *selEnd): the URL around the selection to the host's browser
			if(arg(2)==0||arg(3)<0){ret(PARAM_ERR);return;}
			Found f=icFind(arg(1),arg(2),arg(3),arg(4),arg(5));if(f==null){ret(PARAM_ERR);return;}
			out(arg(4),f.start);out(arg(5),f.end);
			ret(f.url==null?IC_NO_URL:openInBrowser(f.url));});
		l.register(null,"ICCreateGURLEvent",(r,c)->{ // (inst, helperCreator, urlH, *theEvent)
			int h=arg(2),event=arg(3);if(h==0||event==0){ret(PARAM_ERR);return;}
			byte[] url=memory.readBytes(memory.readLong(h),rt.toolbox("GetHandleSize",h));
			int target=rt.allocate(8,4),sig=rt.allocate(4,4),text=rt.allocate(url.length+1,4);
			try{
				memory.writeLong(sig,arg(1));memory.load(text,url); // the URL copied out of the (movable) handle first
				int e=(short)rt.toolbox("AECreateDesc",TYPE_APPL_SIGNATURE,sig,4,target);
				if(e==0){e=(short)rt.toolbox("AECreateAppleEvent",GURL,GURL,target,-1,0,event); // kAutoGenerateReturnID, kAnyTransactionID
					rt.toolbox("AEDisposeDesc",target);}
				if(e==0){e=(short)rt.toolbox("AEPutParamPtr",event,KEY_DIRECT_OBJECT,TYPE_CHAR,text,url.length);if(e!=0)rt.toolbox("AEDisposeDesc",event);}
				ret(e);
			}finally{memory.free(target);memory.free(sig);memory.free(text);}});
		defineIcProfiles(l);
		l.register(null,"ICSendGURLEvent",(r,c)->{ // (inst, *theEvent): the helper here is the host's browser
			if(arg(1)==0){ret(PARAM_ERR);return;}
			int type=rt.allocate(4,4),size=rt.allocate(4,4),buf=rt.allocate(4096,4);
			try{
				int e=(short)rt.toolbox("AEGetParamPtr",arg(1),KEY_DIRECT_OBJECT,TYPE_CHAR,type,buf,4096,size);
				if(e!=0){ret(e);return;}
				int n=Math.min(4096,memory.readLong(size));
				ret(openInBrowser(new String(memory.readBytes(buf,n),StandardCharsets.ISO_8859_1).trim()));
			}finally{memory.free(type);memory.free(size);memory.free(buf);}});
	}
	private boolean icReadOnly(int inst){return icPerms.getOrDefault(inst,IC_NO_PERM)==IC_READ_ONLY_PERM;}
	/** Makes profile {@code id} the current one: its preference set is the one every IC call sees from now on. */
	private void icSwitchTo(int id){IcProfile p=icProfiles.get(id);icCurrentProfile=id;icValues=p.values;icAttributes=p.attributes;icDeleted=p.deleted;icSeed++;}
	/** A profile name no other profile has: "Profile 2", "Profile 3" ... */
	private byte[] icUniqueName(){
		for(int n=icProfiles.size()+1;;n++){byte[] b=("Profile "+n).getBytes(StandardCharsets.ISO_8859_1);boolean used=false;for(IcProfile p:icProfiles.values())if(java.util.Arrays.equals(p.name,b))used=true;if(!used)return b;}
	}
	/** The profile routines (ICGetCurrentProfile ... ICDeleteProfile): real profiles, each with its own preferences, switched by ICSetCurrentProfile. */
	private void defineIcProfiles(PpcLibraries l){
		if(icProfiles.isEmpty())icProfiles.put(1,new IcProfile("Default".getBytes(StandardCharsets.ISO_8859_1),icValues,icAttributes,icDeleted));
		l.register(null,"ICGetCurrentProfile",(r,c)->{if(arg(1)==0){ret(PARAM_ERR);return;}out(arg(1),icCurrentProfile);ret(0);}); // (inst, *currentID)
		l.register(null,"ICSetCurrentProfile",(r,c)->{int id=arg(1); // (inst, newID)
			if(icReadOnly(arg(0))){ret(IC_PERM_ERR);return;}if(!icProfiles.containsKey(id)){ret(IC_PROFILE_NOT_FOUND);return;}
			if(id!=icCurrentProfile)icSwitchTo(id);ret(0);});
		l.register(null,"ICCountProfiles",(r,c)->{if(arg(1)==0){ret(PARAM_ERR);return;}out(arg(1),icProfiles.size());ret(0);}); // (inst, long *count)
		l.register(null,"ICGetIndProfile",(r,c)->{int i=arg(1);out(arg(2),IC_NIL_PROFILE_ID); // (inst, index (1-based), *thisID)
			if(i<1||arg(2)==0){ret(PARAM_ERR);return;}if(i>icProfiles.size()){ret(IC_PROFILE_NOT_FOUND);return;}
			out(arg(2),new ArrayList<Integer>(icProfiles.keySet()).get(i-1));ret(0);});
		l.register(null,"ICGetProfileName",(r,c)->{IcProfile p=icProfiles.get(arg(1));int name=arg(2); // (inst, thisID, Str255 name)
			if(name==0){ret(PARAM_ERR);return;}if(p==null){memory.writeByte(name,0);ret(IC_PROFILE_NOT_FOUND);return;}
			memory.writeByte(name,p.name.length);memory.load(name+1,p.name);ret(0);});
		l.register(null,"ICSetProfileName",(r,c)->{IcProfile p=icProfiles.get(arg(1)); // (inst, thisID, ConstStr255Param name): names need not be unique
			if(arg(2)==0){ret(PARAM_ERR);return;}if(icReadOnly(arg(0))){ret(IC_PERM_ERR);return;}if(p==null){ret(IC_PROFILE_NOT_FOUND);return;}
			p.name=rt.pBytes(arg(2));icSeed++;ret(0);});
		l.register(null,"ICAddProfile",(r,c)->{int proto=arg(1),newId=arg(2);out(newId,IC_NIL_PROFILE_ID); // (inst, prototypeID, *newID): kICNilProfileID = the defaults, else a copy
			if(newId==0){ret(PARAM_ERR);return;}if(icReadOnly(arg(0))){ret(IC_PERM_ERR);return;}
			IcProfile from=proto==IC_NIL_PROFILE_ID?null:icProfiles.get(proto);if(proto!=IC_NIL_PROFILE_ID&&from==null){ret(IC_PROFILE_NOT_FOUND);return;}
			Map<String,byte[][]> values=new LinkedHashMap<String,byte[][]>();Map<String,Integer> attributes=new HashMap<String,Integer>();Set<String> deleted=new HashSet<String>();
			if(from!=null){for(Map.Entry<String,byte[][]> e:from.values.entrySet())values.put(e.getKey(),new byte[][]{e.getValue()[0].clone(),e.getValue()[1].clone()});attributes.putAll(from.attributes);deleted.addAll(from.deleted);}
			int id=icNextProfile++;icProfiles.put(id,new IcProfile(icUniqueName(),values,attributes,deleted));out(newId,id);icSeed++;ret(0);}); // not made current
		l.register(null,"ICDeleteProfile",(r,c)->{int id=arg(1); // (inst, thisID): neither the current profile nor the last one
			if(icReadOnly(arg(0))){ret(IC_PERM_ERR);return;}if(!icProfiles.containsKey(id)){ret(IC_PROFILE_NOT_FOUND);return;}
			if(id==icCurrentProfile||icProfiles.size()==1){ret(PARAM_ERR);return;}
			icProfiles.remove(id);icSeed++;ret(0);});
	}
	/** A preference key (Pascal string) as the map key; null for the empty key. */
	private String icKey(int p) throws M68kCpuException {
		if(p==0)return null;byte[] k=rt.pBytes(p);if(k.length==0)return null;
		return new String(k,StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
	}
	/** The Internet Preferences file (PpcInternetConfig), which the first profile reads through and writes to. */
	private final PpcInternetConfig icFile;
	/** The preference keys there are: the file's (first profile) and the ones set in this run, less the deleted ones. */
	private List<byte[]> icKeys(){
		Map<String,byte[]> keys=new LinkedHashMap<String,byte[]>();
		if(icCurrentProfile==1)for(String k:icFile.keys()){String low=k.toLowerCase(java.util.Locale.ROOT);if(!icDeleted.contains(low))keys.put(low,StackTextEncoding.ROMAN.encodeReplacing(k));}
		for(Map.Entry<String,byte[][]> e:icValues.entrySet())keys.put(e.getKey(),e.getValue()[0]);
		return new ArrayList<byte[]>(keys.values());
	}
	private byte[] icValue(String key,int keyPtr) throws M68kException {
		if(icDeleted.contains(key))return null;
		byte[][] v=icValues.get(key);if(v!=null)return v[1];
		if(icCurrentProfile!=1){byte[] d=PpcInternetConfig.defaults(StackTextEncoding.ROMAN.decode(rt.pBytes(keyPtr)));return d==null?null:d.clone();} // other profiles start empty: the Internet Preferences file is the first one's
		return defaultPref(keyPtr);
	}
	private int icSet(int inst,int keyPtr,int attr,byte[] value) throws M68kException {
		String key=icKey(keyPtr);if(key==null)return PARAM_ERR;
		if(icPerms.getOrDefault(inst,IC_NO_PERM)==IC_READ_ONLY_PERM)return IC_PERM_ERR;
		int current=icAttributes.getOrDefault(key,0),next=attr==ATTR_NO_CHANGE?current:attr;
		if(value!=null&&(current&ATTR_LOCKED)!=0&&(next&ATTR_LOCKED)!=0)return IC_PERM_ERR;
		if(value!=null){icValues.put(key,new byte[][]{rt.pBytes(keyPtr),value});icDeleted.remove(key);}
		if(attr!=ATTR_NO_CHANGE)icAttributes.put(key,attr);
		// the first profile is the Internet Preferences file's: what is set there stays for the next run and for other programs
		if(icCurrentProfile==1&&value!=null)icFile.set(StackTextEncoding.ROMAN.decode(rt.pBytes(keyPtr)),attr,value);
		icSeed++;return NO_ERR;
	}
	/** The built-in value of a preference (PpcCarbon's ICFindPrefHandle), or null. */
	private byte[] defaultPref(int keyPtr) throws M68kException {
		if(defaultFind==null)return null;
		if(defaultFindVector==0)defaultFindVector=rt.nativeVector("ICFindPrefHandle (defaults)",defaultFind);
		int h=rt.toolbox("NewHandle",0),attr=rt.allocate(4,4);
		try{
			if(h==0||rt.cpu.call(defaultFindVector,0,keyPtr,attr,h)!=0)return null;
			return memory.readBytes(memory.readLong(h),rt.toolbox("GetHandleSize",h));
		}finally{if(h!=0)rt.trap(0xa023,0,h,0);memory.free(attr);}
	}
	private int setHandle(int h,byte[] v) throws M68kException {
		rt.toolbox("SetHandleSize",h,v.length);if(rt.toolbox("GetHandleSize",h)!=v.length)return MEM_FULL_ERR;
		if(v.length>0)memory.load(memory.readLong(h),v);return NO_ERR;
	}
	private int openInBrowser(String url){
		try{java.net.URI uri=new java.net.URI(url);
			if(uri.getScheme()==null||!Sandbox.allowOther(rt.toolbox.name,Sandbox.OPEN_URL,url)||!java.awt.Desktop.isDesktopSupported())return IC_PREF_NOT_FOUND;
			java.awt.Desktop.getDesktop().browse(uri);return NO_ERR;}
		catch(Exception e){return IC_PREF_NOT_FOUND;}
	}

	/** A URL found in text: its bounds (angle brackets included) and the URL, null when the text there is not one. */
	static final class Found {int start,end;String url;}
	private Found icFind(int hintPtr,int data,int len,int selStartPtr,int selEndPtr) throws M68kCpuException {
		int s=selStartPtr==0?0:memory.readLong(selStartPtr),e=selEndPtr==0?s:memory.readLong(selEndPtr);
		if(s<0||e<s||e>len)return null;
		String hint=hintPtr==0?"":new String(rt.pBytes(hintPtr),StandardCharsets.ISO_8859_1);
		return findUrl(memory.readBytes(data,len),s,e,hint);
	}
	private static boolean delimiter(byte b){int c=b&0xff;return c<=0x20||c>=0x7f||c=='<'||c=='>'||c=='"';}
	private static boolean space(byte b){int c=b&0xff;return c<=0x20;}
	/**
	 * Internet Config's URL finding: a selection is the URL (angle brackets and "URL:" dropped, line breaks inside brackets
	 * removed; the first URL-looking word when it holds several words); an insertion point inside "&lt;...&gt;" takes the
	 * bracketed text, otherwise the run of URL characters around it, without trailing punctuation. Text without a scheme is a
	 * URL only as "name@address" with a hint ("mailto" makes "mailto:name@address").
	 */
	static Found findUrl(byte[] t,int selStart,int selEnd,String hint){
		Found f=new Found();int s=selStart,e=selEnd;
		if(s<e){
			while(s<e&&space(t[s]))s++;while(e>s&&space(t[e-1]))e--;
			if(!(e-s>=2&&t[s]=='<'&&t[e-1]=='>')){ // several words selected: the first that is a URL
				for(int i=s;i<e;){while(i<e&&delimiter(t[i]))i++;int j=i;while(j<e&&!delimiter(t[j]))j++;
					int[] b=trim(t,i,j);if(b[0]<b[1]&&url(new String(t,b[0],b[1]-b[0],StandardCharsets.ISO_8859_1),hint)!=null){s=b[0];e=b[1];break;}i=j;}
			}
		}else{
			int lt=-1,gt=-1;
			if(s<t.length&&t[s]=='<')lt=s;else for(int i=s-1;i>=0;i--){if(t[i]=='<'){lt=i;break;}if(t[i]=='>')break;}
			if(lt>=0)for(int i=Math.max(s,lt+1);i<t.length;i++){if(t[i]=='>'){gt=i;break;}if(t[i]=='<')break;}
			if(lt>=0&&gt>lt&&url(clean(t,lt,gt+1),hint)!=null){s=lt;e=gt+1;}
			else{while(s>0&&!delimiter(t[s-1]))s--;while(e<t.length&&!delimiter(t[e]))e++;int[] b=trim(t,s,e);s=b[0];e=b[1];}
		}
		f.start=s;f.end=e;f.url=s<e?url(clean(t,s,e),hint):null;
		return f;
	}
	/** Without leading brackets/quotes and trailing punctuation. */
	private static int[] trim(byte[] t,int s,int e){
		while(e>s&&".,;:!?)]}'".indexOf(t[e-1])>=0)e--;
		while(s<e&&"([{'".indexOf(t[s])>=0)s++;
		return new int[]{s,e};
	}
	/** The text of a URL: angle brackets, "URL:" and white space dropped. */
	private static String clean(byte[] t,int s,int e){
		if(e-s>=2&&t[s]=='<'&&t[e-1]=='>'){s++;e--;}
		StringBuilder sb=new StringBuilder();for(int i=s;i<e;i++)if(!space(t[i]))sb.append((char)(t[i]&0xff));
		String u=sb.toString();if(u.regionMatches(true,0,"URL:",0,4))u=u.substring(4);
		return u;
	}
	/** {@code text} as a URL: with a scheme, or name@address given a hint; null otherwise. */
	private static String url(String text,String hint){
		if(text.matches("[A-Za-z][A-Za-z0-9+.-]*:.+"))return text;
		int at=text.indexOf('@');
		if(!hint.isEmpty()&&at>0&&at<text.length()-1&&text.indexOf('@',at+1)<0)return hint+":"+text;
		return null;
	}
}

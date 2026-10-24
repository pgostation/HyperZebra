import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple Event Manager (Pack 8) for hosted applications: descriptors, lists,
 * records, handlers and the required-suite events (open application, open
 * documents, quit) that HyperZebra sends when documents are dropped on the
 * program's windows or when it is asked to quit.
 */
final class AppleEvents68k {
	static final int TYPE_AEVT=0x61657674,TYPE_LIST=0x6c697374,TYPE_RECO=0x7265636f,TYPE_ALIS=0x616c6973,TYPE_FSS=0x66737320,TYPE_FSREF=0x66737266,TYPE_PSN=0x70736e20,TYPE_NULL=0x6e756c6c,TYPE_LONG=0x6c6f6e67,TYPE_SHOR=0x73686f72,TYPE_TEXT=0x54455854,TYPE_BOOL=0x626f6f6c,TYPE_TYPE=0x74797065,TYPE_ENUM=0x656e756d,TYPE_CHAR=0x54455854,TYPE_WILD=0x2a2a2a2a;
	static final int KEY_DIRECT=0x2d2d2d2d,KEY_ERROR_NUMBER=0x6572726e,KEY_ERROR_STRING=0x65727273,KEY_MISSED=0x6d697373,KEY_EVENT_CLASS=0x6576636c,KEY_EVENT_ID=0x65766964,KEY_ADDRESS=0x61646472,KEY_RETURN_ID=0x72746964,KEY_INTERACT=0x696e7465,KEY_TRANSACTION=0x7472616e,KEY_EVENT_SOURCE=0x65737263,KEY_TIMEOUT=0x74696d6f,KEY_ORIGINAL_ADDRESS=0x66726f6d;
	static final int TYPE_SIGN=0x7369676e,TYPE_TARGET_ID=0x74617267;
	static final int ERR_NOT_HANDLED=-1708,ERR_TIMEOUT=-1712,ERR_HANDLER_NOT_FOUND=-1717,ERR_DESC_NOT_FOUND=-1701,ERR_COERCION_FAIL=-1700,ERR_ILLEGAL_INDEX=-1719,ERR_WRONG_TYPE=-1703,ERR_NOT_A_LIST=-1705,ERR_BUFFER_TOO_SMALL=-1707;

	/** Java-side content of a descriptor whose data Handle is opaque (records, lists, events). */
	static final class Record {
		int eventClass,eventId;boolean isList,isRecord;
		/** A record whose Handle keeps the bare item layout [count, 0, items...] (a record-shaped item taken out of a list or
		 *  event under another type, as the real Apple Event Manager hands it over): its bytes are read and written that way. */
		boolean bareBody;
		final LinkedHashMap<Integer,int[]> items=new LinkedHashMap<Integer,int[]>(); // keyword -> {type, dataHandle}
		final List<int[]> list=new ArrayList<int[]>();
		final LinkedHashMap<Integer,int[]> attributes=new LinkedHashMap<Integer,int[]>();
	}

	/** A descriptor tree detached from any program's memory: Apple events travel between running programs as these. */
	static final class Portable {
		int type;byte[] data;boolean isList,isRecord;int eventClass,eventId;
		final List<Portable> list=new ArrayList<Portable>();final LinkedHashMap<Integer,Portable> items=new LinkedHashMap<Integer,Portable>(),attributes=new LinkedHashMap<Integer,Portable>();
		/** Set by the receiver once its handler returned: the reply (AESend with kAEWaitReply waits for it). */
		volatile Portable reply;volatile boolean answered;
	}
	Portable export(int[] d) throws M68kCpuException {
		Portable p=new Portable();p.type=d[0];Record r=recordFor(d[0],d[1]);
		if(r!=null){p.isList=r.isList;p.isRecord=r.isRecord;p.eventClass=r.eventClass;p.eventId=r.eventId;
			for(Map.Entry<Integer,int[]> e:r.items.entrySet())p.items.put(e.getKey(),export(e.getValue()));
			for(int[] item:r.list)p.list.add(export(item));
			for(Map.Entry<Integer,int[]> e:r.attributes.entrySet())p.attributes.put(e.getKey(),export(e.getValue()));}
		else{byte[] data=d[1]==0?null:tb.api.handleContents(d[1]);p.data=data==null?new byte[0]:data;}
		return p;
	}
	int[] materialize(Portable p) throws M68kCpuException {
		if(!p.isList&&!p.isRecord)return new int[]{p.type,tb.api.createHandle(p.data==null?new byte[0]:p.data)};
		Record r=new Record();r.isList=p.isList;r.isRecord=p.isRecord;r.eventClass=p.eventClass;r.eventId=p.eventId;
		for(Map.Entry<Integer,Portable> e:p.items.entrySet())r.items.put(e.getKey(),materialize(e.getValue()));
		for(Portable item:p.list)r.list.add(materialize(item));
		for(Map.Entry<Integer,Portable> e:p.attributes.entrySet())r.attributes.put(e.getKey(),materialize(e.getValue()));
		int h=register(r);return new int[]{p.type,h};
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,Record> records=new HashMap<Integer,Record>();
	private final Map<Long,int[]> handlers=new HashMap<Long,int[]>(); // (class<<32|id) -> {proc, refcon}
	private final Map<Long,int[]> coercions=new HashMap<Long,int[]>(); // (fromType<<32|toType) -> {proc, refcon, fromTypeIsDesc}
	private EventManager68k.Event current;
	private int returnId=1;

	AppleEvents68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;objects=new AEObjects68k(tb,this);}
	/** Selectors this Pack8 implements itself (anything else may belong to a program's keySelectProc). */
	private static boolean knownSelector(int selector){
		switch(selector){
			case 0x091f:case 0x0720:case 0x0921:case 0x021b:case 0x0812:case 0x0826:case 0x0e11:case 0x0e15:case 0x0407:case 0x100a:case 0x0a0b:case 0x0204:case 0x092e:case 0x0632:case 0x0833:case 0x0831:case 0x0830:case 0x0405:case 0x0825:case 0x0706:case 0x0b14:
			case 0x0a0f:case 0x0a16:case 0x0610:case 0x0627:case 0x0a08:case 0x0609:case 0x0413:case 0x040e:case 0x0829:case 0x0828:case 0x082a:case 0x0d17:case 0x061c:case 0x011e:case 0x021d:case 0x0219:case 0x022c:case 0x022b:case 0x0818:case 0x021a:
			case 0x0603:case 0x0a02:case 0x0a22:case 0x0723:case 0x0b24:case 0x0500:case 0x0501:case 0x0d0c:case 0x0b0d:case 0x0441:case 0x052d:return true;
			default:return false;
		}
	}

	void queued(EventManager68k.Event e){current=e;}

	/**
	 * The documents of an Open Documents event handed to LaunchApplication (launchAppParameters): AppParameters is the event
	 * record (message = class, where = ID), eventRefCon, messageLength and the Apple event as a message (the wire form:
	 * 'aevt', version, attributes, ';;;;', keyed parameters) or in the in-memory layout; the direct object's aliases/FSSpecs
	 * name the files. The System 7.5 Finder opens a document this way.
	 */
	List<File> documentsFromLaunchParameters(int appParameters) throws M68kCpuException {
		List<File> files=new ArrayList<File>();
		if(appParameters==0)return files;
		int eventClass=memory.readLong(appParameters+2),eventId=memory.readLong(appParameters+10),length=memory.readLong(appParameters+20);
		if(eventClass!=TYPE_AEVT||(eventId!=0x6f646f63&&eventId!=0x70646f63)||length<=0||length>(1<<20))return files; // 'odoc' / 'pdoc' (the Finder leaves what 0)
		return documentsFromMessage(memory.readBytes(appParameters+24,length));
	}
	/** The files the direct object of an Apple event names, from its message bytes (wire form or in-memory layout). */
	List<File> documentsFromMessage(byte[] b) throws M68kCpuException {
		List<File> files=new ArrayList<File>();
		byte[] direct=null;int directType=0;
		if(b.length>=8&&u4(b,0)==TYPE_AEVT){
			// the message as it travels: 'aevt', version, attributes, ';;;;', then the parameters, each keyword, type, size, data (even)
			for(int p=8;p+4<=b.length;){int keyword=u4(b,p);if(keyword==0x3b3b3b3b){p+=4;continue;}
				if(p+12>b.length)break;int type=u4(b,p+4),size=u4(b,p+8);if(size<0||p+12+size>b.length)break;
				if(keyword==KEY_DIRECT){direct=java.util.Arrays.copyOfRange(b,p+12,p+12+size);directType=type;break;}
				p+=12+size+(size&1);}
		}else{Record event=parse(b);int[] d=event==null?null:event.items.get(KEY_DIRECT);
			if(d!=null){directType=d[0];Record list=d[0]==TYPE_LIST?records.get(d[1]):null;
				if(list!=null){for(int[] item:list.list){File f=fileOf(item[0],item[1]==0?null:tb.api.handleContents(item[1]));if(f!=null)files.add(f);}return files;}
				direct=d[1]==0?null:tb.api.handleContents(d[1]);}}
		if(direct==null)return files;
		if(directType==TYPE_LIST){int count=u4(direct,0);int p=8;
			for(int i=0;i<count&&p+8<=direct.length;i++){int type=u4(direct,p),size=u4(direct,p+4);if(size<0||p+8+size>direct.length)break;
				File f=fileOf(type,java.util.Arrays.copyOfRange(direct,p+8,p+8+size));if(f!=null)files.add(f);p+=8+size+(size&1);}}
		else{File f=fileOf(directType,direct);if(f!=null)files.add(f);}
		return files;
	}
	/** The files the aliases, FSSpecs and FSRefs anywhere in {@code p} name (a list, an object specifier's key data...). */
	private void filesIn(Portable p,List<File> out,int depth) throws M68kCpuException {
		if(p==null||depth>8)return;
		if(p.data!=null&&p.list.isEmpty()&&p.items.isEmpty()){File f=fileOf(p.type,p.data);if(f!=null&&!out.contains(f))out.add(f);return;}
		for(Portable item:p.list)filesIn(item,out,depth+1);
		for(Portable item:p.items.values())filesIn(item,out,depth+1);
	}
	/** The file an 'alis', 'fss ' or 'fsrf' descriptor's data names, or null. */
	private File fileOf(int type,byte[] data) throws M68kCpuException {
		if(data==null)return null;File f=null;
		if(type==TYPE_ALIS)f=tb.files.resolveAlias(data);
		else if(type==TYPE_FSS){int spec=memory.allocate(70,2);memory.load(spec,java.util.Arrays.copyOf(data,70));try{f=tb.files.fileForSpec(spec);}catch(java.io.IOException e){f=null;}memory.free(spec);}
		else if(type==TYPE_FSREF)f=fileOfFSRef(data);
		return f!=null&&f.exists()?f:null;
	}
	/** The file of an FSRef's 80 bytes (the File Manager's own FSRefs), or null. */
	private File fileOfFSRef(byte[] data) throws M68kCpuException {if(data.length<80)return null;int ref=memory.allocate(80,4);try{memory.load(ref,data,0,80);return tb.files.fsRefFile(ref);}finally{memory.free(ref);}}
	/** The FSRef (80 bytes) of an existing file, as a descriptor of type 'fsrf'; null when the file is gone. */
	private int[] fsRefDescriptor(File f) throws M68kException {if(f==null||!f.exists())return null;int ref=memory.allocate(80,4);try{tb.files.writeFSRef(ref,f);return new int[]{TYPE_FSREF,tb.api.createHandle(memory.readBytes(ref,80))};}finally{memory.free(ref);}}


	/**
	 * GetSpecificHighLevelEvent(aFilter, contextPtr, VAR err): Boolean. Each waiting high-level event is shown to the filter
	 * as a HighLevelEventMsg (header, the event record WaitNextEvent would return, refcon, posting options, message length)
	 * with the sender's TargetID; the first one the filter takes leaves the queue and becomes the event AEProcessAppleEvent
	 * and AcceptHighLevelEvent see next. HyperCard asks this way, before it opens Home, whether the Finder launched it to
	 * open a stack: the stack then opens instead of Home.
	 */
	boolean getSpecificHighLevelEvent(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7],errPtr=memory.readLong(sp),context=memory.readLong(sp+4),filter=memory.readLong(sp+8);cpu.a[7]+=12;
		boolean found=false;
		if(filter!=0)for(EventManager68k.Event e:tb.events.pendingHighLevelEvents()){
			int msg=memory.allocate(36,2),sender=memory.allocate(TARGET_ID_SIZE,2);
			try{
				memory.fill(msg,36,0);memory.fill(sender,TARGET_ID_SIZE,0);
				memory.writeWord(msg,36);memory.writeWord(msg+2,1); // HighLevelEventMsgHdrLen, version
				int record=msg+8;
				memory.writeWord(record,EventManager68k.HIGH_LEVEL_EVT);memory.writeLong(record+2,e.message);memory.writeLong(record+6,e.when);
				memory.writeLong(record+10,EventManager68k.highLevelEventId(e));memory.writeWord(record+14,e.modifiers);
				memory.writeLong(msg+24,e.refcon);memory.writeLong(msg+32,e.buffer==null?0:e.buffer.length);
				memory.writeLong(sender,e.senderSerial!=0?e.senderSerial:Toolbox68k.FINDER_SERIAL); // TargetID.sessionID
				int[] d=cpu.d.clone(),a=cpu.a.clone();boolean took;
				EventManager68k.Event before=current;current=e; // the filter may process the event on the spot (HyperCard's calls AEProcessAppleEvent from inside)
				cpu.a[7]-=2;cpu.pushLong(context);cpu.pushLong(msg);cpu.pushLong(sender); // pascal Boolean filter(contextPtr, msgBuff, sender)
				try{cpu.callPascal(filter,12);took=memory.readByte(cpu.a[7])!=0;}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
				boolean processed=current==null;
				if(took||processed){tb.events.remove(e);if(!processed)current=e;found=true;
					if(Mac68kApi.TRACE)System.err.println("[68K] GetSpecificHighLevelEvent took "+fourcc(EventManager68k.highLevelEventId(e))+(processed?" (processed by the filter)":""));
					break;}
				current=before;
			}finally{memory.free(msg);memory.free(sender);}
		}
		if(errPtr!=0)memory.writeWord(errPtr,found?0:-600); // procNotFound: nothing the filter wanted
		tb.writeBoolean(cpu.a[7],found);return true;
	}
	/** sizeof(TargetID): sessionID, the sender's PPCPortRec, LocationNameRec and receiver PPCPortRec. */
	private static final int TARGET_ID_SIZE=4+72+104+72;

	/** AcceptHighLevelEvent(VAR sender: TargetID, VAR msgRefcon, msgBuff, VAR msgLen): the buffer PostHighLevelEvent sent with
	 *  the high-level event just returned by WaitNextEvent (bufferIsSmallErr with the needed length when it does not fit). */
	boolean acceptHighLevelEvent(M68kCpu cpu) throws M68kCpuException {
		int lenPtr=memory.readLong(cpu.a[7]),buffer=memory.readLong(cpu.a[7]+4),refconPtr=memory.readLong(cpu.a[7]+8),sender=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;
		EventManager68k.Event e=current;
		if(e==null||e.buffer==null){if(lenPtr!=0)memory.writeLong(lenPtr,0);if(refconPtr!=0)memory.writeLong(refconPtr,0);memory.writeWord(cpu.a[7],-608);return true;} // noOutstandingHLE
		int room=lenPtr==0?0:memory.readLong(lenPtr);int result=0;
		if(e.buffer.length>room){result=-607;}else if(buffer!=0&&e.buffer.length>0)memory.load(buffer,e.buffer); // bufferIsSmallErr
		if(lenPtr!=0)memory.writeLong(lenPtr,e.buffer.length);if(refconPtr!=0)memory.writeLong(refconPtr,e.refcon);
		if(sender!=0)memory.writeLong(sender,e.senderSerial); // TargetID.sessionID: the sender's serial number
		if(result==0)current=null;
		memory.writeWord(cpu.a[7],result);return true;
	}
	/** PostHighLevelEvent(theEvent, receiverID, msgRefcon, msgBuff, msgLen, postingOptions): the raw message goes to the running
	 *  program named by a process serial number or a signature; the receiver's WaitNextEvent returns it as a high-level event. */
	boolean postHighLevelEvent(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],options=memory.readLong(sp),length=memory.readLong(sp+4),buffer=memory.readLong(sp+8),refcon=memory.readLong(sp+12),receiver=memory.readLong(sp+16),event=memory.readLong(sp+20);cpu.a[7]+=24;
		MacApp68k target=null;int kind=options&0xf000;
		if(receiver!=0&&kind==0x8000&&event!=0&&memory.readLong(receiver)==0&&memory.readLong(receiver+4)==1){ // kSystemProcess: the Process Manager itself
			// 'aevt'/'quia' ("quit all other applications", Installer VISE before a restart-needing install): the sender waits in
			// WaitNextEvent for the 'aevt'/'ansr' reply. Nothing is quit here (host programs are not Mac processes); the empty
			// reply carries no 'errn', i.e. every application quit.
			if(memory.readLong(event+2)==0x61657674&&memory.readLong(event+10)==0x71756961){
				tb.events.postHighLevel(0x61657674,0x616e7372,new byte[0],refcon,1);memory.writeWord(cpu.a[7],0);return true;}
			memory.writeWord(cpu.a[7],-600);return true;}
		if(receiver!=0&&kind==0x8000){int serial=memory.readLong(receiver+4);boolean current=memory.readLong(receiver)==0&&serial==2;target=current||tb.app!=null&&serial==tb.app.serialNumber?tb.app:MacApp68k.bySerial(serial);}
		else if(receiver!=0&&kind==0x7000){int creator=memory.readLong(receiver);for(MacApp68k app:MacApp68k.runningApplications())if(app.toolbox!=null&&app.toolbox.files.fileCreator(app.file)==creator){target=app;break;}}
		if(target==null||target.toolbox==null||event==0){memory.writeWord(cpu.a[7],-600);return true;} // procNotFound
		byte[] data=length>0&&buffer!=0?memory.readBytes(buffer,Math.min(length,1<<20)):new byte[0];
		target.toolbox.events.postHighLevel(memory.readLong(event+2),memory.readLong(event+10),data,refcon,tb.app==null?0:tb.app.serialNumber);
		memory.writeWord(cpu.a[7],0);return true;
	}

	final AEObjects68k objects;
	/** Special handlers by keyword: 'selh' (the program's own selector dispatcher: the linked-in Object Support Library), 'phac' (pre-dispatch). */
	private final Map<Integer,Integer> specialHandlers=new HashMap<Integer,Integer>();
	static final int KEY_SELECT_PROC=0x73656c68,KEY_PRE_DISPATCH=0x70686163;

	boolean pack8(M68kCpu cpu) throws M68kException {return dispatch(cpu,true);}

	/** The address a keySelectProc chains to for the selectors it does not take: Pack8's own dispatcher, entered like the trap
	 *  (return address on top of the arguments, selector in D0) but never detouring to the program again. */
	private int dispatcherGate;
	private int dispatcherGate(){
		if(dispatcherGate==0)dispatcherGate=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {dispatch(c,false);}});
		return dispatcherGate;
	}

	private boolean dispatch(M68kCpu cpu,boolean chain) throws M68kException {
		int selector=cpu.d[0]&0xffff;int argBytes=(selector>>>8)*2;int sp=cpu.a[7];int result;
		boolean osl=(selector&0xff)>=0x35&&(selector&0xff)<=0x3b;
		Integer selectProc=chain?specialHandlers.get(KEY_SELECT_PROC):null;
		if(selectProc!=null&&(osl||!knownSelector(selector))){
			// The program's own dispatcher (the linked-in Object Support Library) takes the selectors Pack8 leaves to it: D0 and the
			// arguments untouched, A1 = Pack8's dispatcher, which it JMPs to for anything it does not handle itself (HyperCard 2.2's
			// AEObjectInit never asks AEGetSpecialHandler for the previous one - the real Pack8 passes it this way).
			if(Mac68kApi.TRACE)System.err.println("[68K] Pack8 selector $"+Integer.toHexString(selector)+" -> the program's keySelectProc at $"+Integer.toHexString(selectProc));
			cpu.a[1]=dispatcherGate();cpu.pushLong(cpu.pc);cpu.pc=selectProc;return true;}
		if(osl){Integer r=objects.pack8(cpu,selector,sp);if(r!=null){flushDirty();cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],r);return true;}}
		switch(selector){
			case 0x091f:{int isSys=memory.readWord(sp),refcon=memory.readLong(sp+2),handler=memory.readLong(sp+6),id=memory.readLong(sp+10),cls=memory.readLong(sp+14);handlers.put(key(cls,id),new int[]{handler,refcon});result=0;break;}
			case 0x0720:{int id=memory.readLong(sp+6),cls=memory.readLong(sp+10);result=handlers.remove(key(cls,id))==null?ERR_HANDLER_NOT_FOUND:0;break;}
			case 0x0921:{int refconPtr=memory.readLong(sp+2),handlerPtr=memory.readLong(sp+6),id=memory.readLong(sp+10),cls=memory.readLong(sp+14);int[] h=handlers.get(key(cls,id));if(h==null)result=ERR_HANDLER_NOT_FOUND;else{if(handlerPtr!=0)memory.writeLong(handlerPtr,h[0]);if(refconPtr!=0)memory.writeLong(refconPtr,h[1]);result=0;}break;}
			case 0x021b:{int eventPtr=memory.readLong(sp);cpu.a[7]+=argBytes;result=processAppleEvent(eventPtr);flushDirty();memory.writeWord(cpu.a[7],result);return true;}
			case 0x0812:case 0x0826:{int resultDesc=memory.readLong(sp),desired=memory.readLong(sp+4),keyword=memory.readLong(sp+8),event=memory.readLong(sp+12);result=getDesc(event,keyword,desired,resultDesc,selector==0x0826);break;}
			case 0x0e11:case 0x0e15:{int actualPtr=memory.readLong(sp),max=memory.readLong(sp+4),dataPtr=memory.readLong(sp+8),typePtr=memory.readLong(sp+12),desired=memory.readLong(sp+16),keyword=memory.readLong(sp+20),event=memory.readLong(sp+24);
				int[] desc=lookup(event,keyword,selector==0x0e15);result=desc==null?ERR_DESC_NOT_FOUND:copyOut(desc,desired,typePtr,dataPtr,max,actualPtr);break;}
			case 0x0407:{int countPtr=memory.readLong(sp),list=memory.readLong(sp+4);Record r=record(list);if(r==null||!(r.isList||r.isRecord)){if(Mac68kApi.TRACE){int h=memory.readLong(list+4);byte[] raw=h==0?null:tb.api.handleContents(h);StringBuilder b=new StringBuilder();if(raw!=null)for(int i=0;i<Math.min(64,raw.length);i++)b.append(String.format("%02x",raw[i]));System.err.println("[ae] AECountItems on "+fourcc(memory.readLong(list))+" handle $"+Integer.toHexString(h)+" ("+(raw==null?-1:raw.length)+" bytes: "+b+")");}result=ERR_NOT_A_LIST;}else{memory.writeLong(countPtr,r.isList?r.list.size():r.items.size());result=0;}break;}
			case 0x100a:{int actualPtr=memory.readLong(sp),max=memory.readLong(sp+4),dataPtr=memory.readLong(sp+8),typePtr=memory.readLong(sp+12),keywordPtr=memory.readLong(sp+16),desired=memory.readLong(sp+20),index=memory.readLong(sp+24),list=memory.readLong(sp+28);
				int[] desc=nth(list,index,keywordPtr);result=desc==null?ERR_ILLEGAL_INDEX:copyOut(desc,desired,typePtr,dataPtr,max,actualPtr);break;}
			case 0x0a0b:{int resultDesc=memory.readLong(sp),keywordPtr=memory.readLong(sp+4),desired=memory.readLong(sp+8),index=memory.readLong(sp+12),list=memory.readLong(sp+16);
				int[] desc=nth(list,index,keywordPtr);if(desc==null)result=ERR_ILLEGAL_INDEX;else result=coerceInto(desc,desired,resultDesc);
				if(Mac68kApi.TRACE&&desc!=null){int h=memory.readLong(resultDesc+4);byte[] raw=h==0?null:tb.api.handleContents(h);StringBuilder b=new StringBuilder();if(raw!=null)for(int i=0;i<Math.min(24,raw.length);i++)b.append(String.format("%02x",raw[i]));System.err.println("[ae] nth "+index+" -> "+(keywordPtr==0?"":fourcc(memory.readLong(keywordPtr))+" ")+fourcc(memory.readLong(resultDesc))+" ("+(raw==null?0:raw.length)+" bytes: "+b+")");}break;}
			case 0x0204:{int desc=memory.readLong(sp);disposeDesc(desc);result=0;break;}
			// The Apple Event Manager's private handler-table entry points (HyperCard 2.2 keeps its own dispatch tables through
			// them and crashes on `go` when they answer errAEEventNotHandled). Layouts as Executor documents them (AE_hdlr.cpp).
			case 0x092e:{int tablePtr=memory.readLong(sp);result=hdlrTableAlloc(tablePtr);break;}
			case 0x0632:{int sel=memory.readLong(sp),table=memory.readLong(sp+8);result=hdlrTableDelete(table,sel);break;}
			case 0x0833:{int hdlr=memory.readLong(sp),sel=memory.readLong(sp+4),table=memory.readLong(sp+12);result=hdlrTableLookup(table,sel,hdlr);break;}
			case 0x0831:case 0x0830:{int hdlr=memory.readLong(sp),sel=memory.readLong(sp+4),table=memory.readLong(sp+12);result=hdlrTableInstall(table,sel,hdlr);break;} // $0830: same arguments; HyperCard 2.x fills its own event table with it (undocumented, treated as an install)
			case 0x0405:{int resultDesc=memory.readLong(sp),source=memory.readLong(sp+4);result=duplicate(source,resultDesc);break;}
			case 0x0825:{int resultDesc=memory.readLong(sp),size=memory.readLong(sp+4),dataPtr=memory.readLong(sp+8),type=memory.readLong(sp+12);createDesc(resultDesc,type,size>0?memory.readBytes(dataPtr,size):new byte[0]);result=0;break;}
			case 0x0706:{int resultDesc=memory.readLong(sp);boolean isRecord=memory.readByte(sp+4)!=0;/* Boolean: high-order byte */Record r=new Record();r.isList=!isRecord;r.isRecord=isRecord;int h=register(r);writeDesc(resultDesc,isRecord?TYPE_RECO:TYPE_LIST,h);result=0;break;}
			case 0x0b14:{int resultDesc=memory.readLong(sp),transaction=memory.readLong(sp+4),retId=(short)memory.readWord(sp+8),target=memory.readLong(sp+10),id=memory.readLong(sp+14),cls=memory.readLong(sp+18);
				Record r=new Record();r.isRecord=true;r.eventClass=cls;r.eventId=id;int h=register(r);writeDesc(resultDesc,TYPE_AEVT,h);
				r.attributes.put(KEY_EVENT_CLASS,new int[]{TYPE_TYPE,tb.api.createHandle(longBytes(cls))});r.attributes.put(KEY_EVENT_ID,new int[]{TYPE_TYPE,tb.api.createHandle(longBytes(id))});
				// The target: a process serial number or a signature names the receiver, which AESend then looks up (before, every event
				// was dispatched to the sender itself: QuarkXPress 3.31's installer sends 'quit' to the Finder 'MACS' and quit itself)
				if(target!=0){int[] t=copyDesc(target);if(t!=null&&(t[0]==TYPE_PSN||t[0]==TYPE_SIGN))r.attributes.put(KEY_ADDRESS,t);}
				result=0;break;}
			case 0x0a0f:case 0x0a16:{int size=memory.readLong(sp),dataPtr=memory.readLong(sp+4),type=memory.readLong(sp+8),keyword=memory.readLong(sp+12),event=memory.readLong(sp+16);Record r=record(event);if(r==null)result=ERR_DESC_NOT_FOUND;else{int h=tb.api.createHandle(size>0?memory.readBytes(dataPtr,size):new byte[0]);(selector==0x0a16?r.attributes:r.items).put(keyword,new int[]{type,h});result=0;}break;}
			case 0x0610:case 0x0627:{int desc=memory.readLong(sp),keyword=memory.readLong(sp+4),event=memory.readLong(sp+8);Record r=record(event);if(r==null)result=ERR_DESC_NOT_FOUND;else{int[] copy=copyDesc(desc);(selector==0x0627?r.attributes:r.items).put(keyword,copy);result=0;}break;}
			case 0x0a08:{int size=memory.readLong(sp),dataPtr=memory.readLong(sp+4),type=memory.readLong(sp+8),index=memory.readLong(sp+12),list=memory.readLong(sp+16);Record r=record(list);if(r==null||!r.isList)result=ERR_NOT_A_LIST;else{int[] item={type,tb.api.createHandle(size>0?memory.readBytes(dataPtr,size):new byte[0])};putNth(r,index,item);result=0;}break;}
			case 0x0609:{int desc=memory.readLong(sp),index=memory.readLong(sp+4),list=memory.readLong(sp+8);Record r=record(list);if(r==null||!r.isList)result=ERR_NOT_A_LIST;else{putNth(r,index,copyDesc(desc));result=0;}break;}
			case 0x0413:{int keyword=memory.readLong(sp),event=memory.readLong(sp+4);Record r=record(event);result=r==null||r.items.remove(keyword)==null?ERR_DESC_NOT_FOUND:0;break;}
			case 0x040e:{int index=memory.readLong(sp),list=memory.readLong(sp+4);Record r=record(list);if(r==null||!r.isList||index<1||index>r.list.size())result=ERR_ILLEGAL_INDEX;else{r.list.remove(index-1);result=0;}break;}
			case 0x0829:case 0x0828:{int sizePtr=memory.readLong(sp),typePtr=memory.readLong(sp+4),keyword=memory.readLong(sp+8),event=memory.readLong(sp+12);int[] desc=lookup(event,keyword,selector==0x0828);if(desc==null)result=ERR_DESC_NOT_FOUND;else{if(typePtr!=0)memory.writeLong(typePtr,desc[0]);if(sizePtr!=0)memory.writeLong(sizePtr,tb.api.handleSize(desc[1]));result=0;}break;}
			case 0x082a:{int sizePtr=memory.readLong(sp),typePtr=memory.readLong(sp+4),index=memory.readLong(sp+8),list=memory.readLong(sp+12);int[] desc=nth(list,index,0);if(desc==null)result=ERR_ILLEGAL_INDEX;else{if(typePtr!=0)memory.writeLong(typePtr,desc[0]);if(sizePtr!=0)memory.writeLong(sizePtr,tb.api.handleSize(desc[1]));result=0;}break;}
			case 0x0d17:{int timeout=memory.readLong(sp+8),sendMode=memory.readLong(sp+14),replyDesc=memory.readLong(sp+18),event=memory.readLong(sp+22);result=send(event,replyDesc,sendMode,timeout);break;}
			case 0x061c:result=0;break;
			case 0x011e:result=0;break;
			case 0x021d:{int levelPtr=memory.readLong(sp);if(levelPtr!=0)memory.writeByte(levelPtr,2);result=0;break;}
			case 0x022b:{int event=memory.readLong(sp);if(event!=0)suspended.add(memory.readLong(event+4));result=0;break;} // AESuspendTheCurrentEvent: the program keeps the event, the AE Manager no longer disposes of it
			case 0x0219:case 0x022c:case 0x0818:result=0;break;
			case 0x021a:{int descPtr=memory.readLong(sp);int[] cur=handling.isEmpty()?null:handling.peek();if(descPtr!=0){if(cur==null)writeDesc(descPtr,TYPE_NULL,0);else writeDesc(descPtr,cur[0],cur[1]);}result=0;break;} // AEGetTheCurrentEvent: the event whose handler is running (HyperCard's `request appleEvent data` reads it), a null descriptor outside a handler
			case 0x0603:{int resultDesc=memory.readLong(sp),toType=memory.readLong(sp+4),desc=memory.readLong(sp+8);result=coerceInto(readDesc(desc),toType,resultDesc);break;}
			case 0x0a02:{int resultDesc=memory.readLong(sp),toType=memory.readLong(sp+4),size=memory.readLong(sp+8),dataPtr=memory.readLong(sp+12),type=memory.readLong(sp+16);int[] temp={type,tb.api.createHandle(size>0?memory.readBytes(dataPtr,size):new byte[0])};result=coerceInto(temp,toType,resultDesc);tb.api.disposeHandleAndFree(temp[1]);break;}
			case 0x0a22:{int isDesc=memory.readWord(sp+2)>>>8,refcon=memory.readLong(sp+4),handler=memory.readLong(sp+8),to=memory.readLong(sp+12),from=memory.readLong(sp+16);coercions.put(key(from,to),new int[]{handler,refcon,isDesc!=0?1:0});result=0;break;} // AEInstallCoercionHandler
			case 0x0723:{int to=memory.readLong(sp+6),from=memory.readLong(sp+10);result=coercions.remove(key(from,to))==null?ERR_HANDLER_NOT_FOUND:0;break;} // AERemoveCoercionHandler
			case 0x0b24:{int isDescPtr=memory.readLong(sp+2),refconPtr=memory.readLong(sp+6),handlerPtr=memory.readLong(sp+10),to=memory.readLong(sp+14),from=memory.readLong(sp+18);int[] h=coercions.get(key(from,to)); // AEGetCoercionHandler
				if(h==null)result=ERR_HANDLER_NOT_FOUND;else{if(handlerPtr!=0)memory.writeLong(handlerPtr,h[0]);if(refconPtr!=0)memory.writeLong(refconPtr,h[1]);if(isDescPtr!=0)memory.writeByte(isDescPtr,h[2]);result=0;}break;}
			case 0x0500:{int handler=memory.readLong(sp+2),keyword=memory.readLong(sp+6);specialHandlers.put(keyword,handler);result=0;break;} // AEInstallSpecialHandler
			case 0x0501:{int keyword=memory.readLong(sp+6);result=specialHandlers.remove(keyword)==null?ERR_HANDLER_NOT_FOUND:0;break;}     // AERemoveSpecialHandler
			case 0x0d0c:{int countPtr=memory.readLong(sp),sizePtr=memory.readLong(sp+4),typePtr=memory.readLong(sp+8),max=memory.readLong(sp+12),array=memory.readLong(sp+16),kind=arrayType(memory.readWord(sp+20)),list=memory.readLong(sp+22);
				result=getArray(list,kind,array,max,typePtr,sizePtr,countPtr);break;} // AEGetArray
			case 0x0b0d:{int count=memory.readLong(sp),size=memory.readLong(sp+4),type=memory.readLong(sp+8),array=memory.readLong(sp+12),kind=arrayType(memory.readWord(sp+16)),list=memory.readLong(sp+18);
				result=putArray(list,kind,array,type,size,count);break;} // AEPutArray
			case 0x0441:{int resultPtr=memory.readLong(sp),keyword=memory.readLong(sp+4);if(resultPtr!=0)memory.writeLong(resultPtr,keyword==0x76657273?0x01048000:0);result=0;break;}
			case 0x052d:{int handlerPtr=memory.readLong(sp+2),keyword=memory.readLong(sp+6);Integer h=specialHandlers.get(keyword);if(handlerPtr!=0)memory.writeLong(handlerPtr,h==null?0:h);result=h==null?ERR_HANDLER_NOT_FOUND:0;break;} // AEGetSpecialHandler
			default:result=ERR_NOT_HANDLED;break;
		}
		flushDirty();
		if(Mac68kApi.TRACE){StringBuilder b=new StringBuilder("[ae] $"+Integer.toHexString(selector)+" (");for(int i=0;i<argBytes;i+=4)b.append(i==0?"":" ").append(String.format("%08X",memory.readLong(sp+i)));System.err.println(b+") -> "+result);}
		cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],result);return true;
	}


	// ------------------------------------------------------------------ the descriptors as the program sees them in memory
	//
	// Lists, records and Apple events keep their Java-side Record as the source of truth, but their data Handles hold the
	// flattened layout the System 7 Apple Event Manager used, because AEGizmos (AEStream/AEBuild, linked into some utilities and
	// other Apple programs) writes parameters straight into an Apple event's Handle and reads list items out of Handles:
	//   list/record Handle: long 0, long 0, long 0x18 (offset of the first item), OSType 'list'/'reco', long count, long 0, items...
	//   Apple event Handle:  long 0, long 0, long 0x30, 'aevt', long parameter count, long attribute count, class, ID, return ID,
	//                        transaction ID, long 0, long scratch (AEStream parks its optional-keyword list Handle at $2C), then the
	//                        attributes and the parameters as keyed items
	//   item: [keyword (records/events)] type, size, data padded to a word; a nested list/record's data is [count, 0, items...]
	// Every Pack8 call re-reads a Handle whose bytes changed since we last wrote them, and rewrites the Handles of the records it
	// touched (flushDirty) before returning to the program.
	private final Map<Integer,byte[]> mirrored=new HashMap<Integer,byte[]>();
	private final java.util.Set<Integer> dirty=new java.util.LinkedHashSet<Integer>();
	private static final int HEADER_LIST=0x18,HEADER_EVENT=0x30;

	/** Registers a record under a fresh Handle holding its flattened layout. */
	int register(Record r) throws M68kCpuException {
		byte[] bytes=serialize(r);int h=tb.api.createHandle(bytes);records.put(h,r);mirrored.put(h,bytes);return h;
	}
	/** The record behind a Handle: ours (re-read when the program rewrote its bytes), or one parsed from a list/record/event the program built itself. */
	Record recordFor(int type,int h) throws M68kCpuException {
		if(h==0)return null;
		Record r=records.get(h);
		if(r!=null){
			byte[] last=mirrored.get(h);
			if(last!=null){byte[] now=tb.api.handleContents(h);
				if(now!=null&&!java.util.Arrays.equals(now,last)){Record parsed=parse(now);
					if(parsed!=null){replaceContents(r,parsed);mirrored.put(h,now);}
					else if(now.length<HEADER_LIST||u4(now,12)!=u4(last,12)){records.remove(h);mirrored.remove(h);dirty.remove(h);return null;}}} // not a record any more: the program reused the Handle
			dirty.add(h);return r;
		}
		// The descriptor's own type may be anything a record can stand for ('obj ', 'rang', a comparison...): the Handle's header decides.
		byte[] now=tb.api.handleContents(h);if(now==null||now.length<HEADER_LIST)return null;
		int t=u4(now,12);
		if(t!=TYPE_LIST&&t!=TYPE_RECO&&t!=TYPE_AEVT){ // not the Handle layout: a record-shaped body under another type ('obj ', 'Rinf'...) is still a record for the record calls
			if(type==TYPE_LIST||type==TYPE_RECO||type==TYPE_AEVT)return null;
			Record body=parseRecordBody(now);if(body==null)return null;body.bareBody=true;records.put(h,body);mirrored.put(h,now);dirty.add(h);return body;} // the header's first two longs are not checked: on a real Mac the second holds a heap-zone address, and a list stored in a resource keeps whatever it had (奈美江ちゃんとの世界's 'Lens' window descriptions carry 'TXTB' there)
		Record parsed=parse(now);if(parsed==null)return null;
		records.put(h,parsed);mirrored.put(h,now);dirty.add(h);return parsed;
	}
	/** Disposes a descriptor's Handle and, for a record, everything under it - forgetting the records too, since the Memory Manager
	 *  hands the same addresses out again (a stale entry would turn the next plain Handle at that address into a record). */
	private void disposeHandleOf(int h) throws M68kCpuException {
		if(h==0)return;
		Record r=records.remove(h);mirrored.remove(h);dirty.remove(h);
		if(r!=null){for(int[] item:r.items.values())disposeHandleOf(item[1]);for(int[] item:r.list)disposeHandleOf(item[1]);for(int[] item:r.attributes.values())disposeHandleOf(item[1]);}
		tb.api.disposeHandleAndFree(h);
	}
	private void replaceContents(Record r,Record parsed) throws M68kCpuException {
		for(int[] item:r.items.values())disposeHandleOf(item[1]);for(int[] item:r.list)disposeHandleOf(item[1]);for(int[] item:r.attributes.values())disposeHandleOf(item[1]);
		r.items.clear();r.list.clear();r.attributes.clear();
		r.items.putAll(parsed.items);r.list.addAll(parsed.list);r.attributes.putAll(parsed.attributes);
		r.isList=parsed.isList;r.isRecord=parsed.isRecord;if(parsed.eventClass!=0||parsed.eventId!=0){r.eventClass=parsed.eventClass;r.eventId=parsed.eventId;}
	}
	/** Writes the Handles of the records touched since the last flush. */
	void flushDirty() throws M68kCpuException {
		if(dirty.isEmpty())return;
		Integer[] touched=dirty.toArray(new Integer[0]);dirty.clear();
		for(int h:touched){Record r=records.get(h);if(r==null)continue;
			byte[] bytes=serialize(r);byte[] last=mirrored.get(h);
			if(last!=null&&java.util.Arrays.equals(bytes,last))continue;
			try{tb.api.resizeHandle(h,bytes.length);int p=memory.readLong(h);if(p!=0)memory.load(p,bytes);mirrored.put(h,bytes);}catch(M68kCpuException e){}
		}
	}
	private static boolean isEvent(Record r){return !r.isList&&(r.eventClass!=0||r.eventId!=0||!r.attributes.isEmpty());}
	byte[] serialize(Record r) throws M68kCpuException {
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		if(isEvent(r)){
			int[] header={0,0,HEADER_EVENT,TYPE_AEVT,r.items.size(),r.attributes.size(),r.eventClass,r.eventId,0,0,0,0};
			for(int v:header)putLong(out,v);
			for(Map.Entry<Integer,int[]> e:r.attributes.entrySet())putItem(out,e.getKey(),e.getValue(),true);
			for(Map.Entry<Integer,int[]> e:r.items.entrySet())putItem(out,e.getKey(),e.getValue(),true);
		}else if(r.bareBody){
			putLong(out,r.isList?r.list.size():r.items.size());putLong(out,0);putItems(out,r);
		}else{
			int[] header={0,0,HEADER_LIST,r.isList?TYPE_LIST:TYPE_RECO,r.isList?r.list.size():r.items.size(),0};
			for(int v:header)putLong(out,v);
			putItems(out,r);
		}
		return out.toByteArray();
	}
	private void putItems(java.io.ByteArrayOutputStream out,Record r) throws M68kCpuException {
		if(r.isList)for(int[] item:r.list)putItem(out,0,item,false);
		else for(Map.Entry<Integer,int[]> e:r.items.entrySet())putItem(out,e.getKey(),e.getValue(),true);
	}
	private void putItem(java.io.ByteArrayOutputStream out,int keyword,int[] item,boolean keyed) throws M68kCpuException {
		byte[] data;
		Record nested=item[1]==0?null:records.get(item[1]);
		if(nested!=null&&!isEvent(nested)){java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();putLong(body,nested.isList?nested.list.size():nested.items.size());putLong(body,0);putItems(body,nested);data=body.toByteArray();}
		else if(nested!=null)data=serialize(nested);
		else{data=item[1]==0?null:tb.api.handleContents(item[1]);if(data==null)data=new byte[0];}
		if(keyed)putLong(out,keyword);
		putLong(out,item[0]);putLong(out,data.length); // a record keeps whatever type it was coerced to ('obj ', 'rang'...)
		out.write(data,0,data.length);if((data.length&1)!=0)out.write(0);
	}
	private static void putLong(java.io.ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}
	private static int u4(byte[] b,int o){return o+4<=b.length?MacApp68k.u4(b,o):0;}
	/** A record from its flattened layout, or null when the bytes are not one. */
	Record parse(byte[] b) throws M68kCpuException {
		if(b.length<HEADER_LIST)return null;
		int first=u4(b,8),type=u4(b,12),count=u4(b,16);
		if(type!=TYPE_LIST&&type!=TYPE_RECO&&type!=TYPE_AEVT)return null;
		if(first<HEADER_LIST||first>b.length||count<0)return null;
		Record r=new Record();
		int[] pos={first};
		if(type==TYPE_AEVT){
			if(first<HEADER_EVENT)return null;
			r.isRecord=true;r.eventClass=u4(b,0x18);r.eventId=u4(b,0x1c);int attrCount=u4(b,0x14);
			for(int i=0;i<attrCount;i++){int[] kv=parseItem(b,pos,true);if(kv==null)break;r.attributes.put(kv[0],new int[]{kv[1],kv[2]});}
			for(int i=0;i<count;i++){int[] kv=parseItem(b,pos,true);if(kv==null)break;r.items.put(kv[0],new int[]{kv[1],kv[2]});}
		}else{
			r.isList=type==TYPE_LIST;r.isRecord=!r.isList;
			parseItems(b,pos,count,r);
		}
		return r;
	}
	private void parseItems(byte[] b,int[] pos,int count,Record r) throws M68kCpuException {
		for(int i=0;i<count;i++){int[] kv=parseItem(b,pos,r.isRecord);if(kv==null)break;if(r.isList)r.list.add(new int[]{kv[1],kv[2]});else r.items.put(kv[0],new int[]{kv[1],kv[2]});}
	}
	/** One item at pos: {keyword, type, handle}; nested lists/records become registered records. */
	private int[] parseItem(byte[] b,int[] pos,boolean keyed) throws M68kCpuException {
		int p=pos[0];int need=keyed?12:8;if(p+need>b.length)return null;
		int keyword=keyed?u4(b,p):0;if(keyed)p+=4;
		int type=u4(b,p),size=u4(b,p+4);p+=8;
		if(size<0||p+size>b.length)return null;
		byte[] data=java.util.Arrays.copyOfRange(b,p,p+size);p+=size+(size&1);pos[0]=p;
		int handle;
		if((type==TYPE_LIST||type==TYPE_RECO)&&data.length>=8){Record nested=new Record();nested.isList=type==TYPE_LIST;nested.isRecord=!nested.isList;parseItems(data,new int[]{8},u4(data,0),nested);handle=register(nested);}
		else if(type==TYPE_AEVT){Record nested=parse(data);handle=nested!=null?register(nested):tb.api.createHandle(data);}
		else handle=tb.api.createHandle(data); // an object specifier, a range, a 'Rinf'...: its bytes as they are (a record under another type is recognised when a record call touches it - recordFor - and programs that read such data directly, 奈美江ちゃんとの世界's runtime for its picture-resource records, see the bare item layout the real Apple Event Manager gives them)
		return new int[]{keyword,type,handle};
	}
	/**
	 * A record coerced to another type ('obj ', 'rang', 'cmpd'...) keeps the record layout for its data, so a keyed item body
	 * [count, 0, items...] whose items fill it exactly, with four-character keywords, is taken for one; anything else is plain data.
	 */
	Record parseRecordBody(byte[] data) throws M68kCpuException {
		if(data.length<8+12||u4(data,4)!=0)return null;
		int count=u4(data,0);if(count<=0||count>256)return null;
		int p=8;
		for(int i=0;i<count;i++){
			if(p+12>data.length)return null;
			for(int k=0;k<8;k++){int c=data[p+k]&0xff;if(c<32||c>126)return null;} // keyword and type
			int size=u4(data,p+8);if(size<0||p+12+size>data.length)return null;
			p+=12+size+(size&1);
		}
		if(p!=data.length&&p!=data.length+1)return null;
		Record r=new Record();r.isRecord=true;parseItems(data,new int[]{8},count,r);
		return r;
	}

	private static long key(int cls,int id){return ((long)cls<<32)|(id&0xffffffffL);}
	private static void putLong(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
	static String fourcc(int v){return new String(new char[]{(char)(v>>>24&255),(char)(v>>>16&255),(char)(v>>>8&255),(char)(v&255)});}
	private static int[] lookupPortable(Portable p,int keyword){Portable item=p.items.get(keyword);return item==null||item.data==null||item.data.length<4?null:new int[]{MacApp68k.u4(item.data,0)};}
	/** An AEArrayType argument: a one-byte enum pushed as a word (in its high byte by Pascal glue, the low byte by C glue). */
	private static int arrayType(int word){int high=word>>>8&0xff;return high!=0?high:word&0xff;}
	private static final int ARRAY_DATA=0,ARRAY_PACKED=1,ARRAY_HANDLE=2,ARRAY_DESC=3,ARRAY_KEY_DESC=4;
	/** AEGetArray: the items of a list as a packed C array of data, Handles, AEDescs or AEKeyDescs (record items). */
	private int getArray(int list,int kind,int array,int max,int typePtr,int sizePtr,int countPtr) throws M68kCpuException {
		Record r=record(list);if(r==null||!(r.isList||r.isRecord))return ERR_NOT_A_LIST;
		List<int[]> items=new ArrayList<int[]>();List<Integer> keys=new ArrayList<Integer>();
		if(r.isList)items.addAll(r.list);else for(Map.Entry<Integer,int[]> e:r.items.entrySet()){keys.add(e.getKey());items.add(e.getValue());}
		int itemType=items.isEmpty()?TYPE_NULL:items.get(0)[0],itemSize=0,written=0,offset=0;
		for(int i=0;i<items.size();i++){int[] item=items.get(i);byte[] data=item[1]==0?new byte[0]:tb.api.handleContents(item[1]);if(data==null)data=new byte[0];
			if(i==0)itemSize=data.length;
			int need=kind==ARRAY_HANDLE?4:kind==ARRAY_DESC?8:kind==ARRAY_KEY_DESC?12:itemSize;
			if(offset+need>max)break;
			switch(kind){
				case ARRAY_HANDLE:memory.writeLong(array+offset,tb.api.createHandle(data));break;
				case ARRAY_DESC:{int[] copy=copyDescriptor(item);writeDesc(array+offset,copy[0],copy[1]);break;}
				case ARRAY_KEY_DESC:{int[] copy=copyDescriptor(item);memory.writeLong(array+offset,i<keys.size()?keys.get(i):0);writeDesc(array+offset+4,copy[0],copy[1]);break;}
				default:if(item[0]!=itemType)return ERR_WRONG_TYPE;memory.load(array+offset,java.util.Arrays.copyOf(data,itemSize));break;
			}
			offset+=need;written++;}
		if(typePtr!=0)memory.writeLong(typePtr,itemType);if(sizePtr!=0)memory.writeLong(sizePtr,itemSize);if(countPtr!=0)memory.writeLong(countPtr,written);
		return 0;
	}
	/** AEPutArray: replaces the list's items with the array's. */
	private int putArray(int list,int kind,int array,int type,int size,int count) throws M68kCpuException {
		Record r=record(list);if(r==null||!(r.isList||r.isRecord))return ERR_NOT_A_LIST;
		if(count<0||size<0||array==0&&count>0)return -1704; // errAENotAEDesc
		for(int[] item:r.list)disposeHandleOf(item[1]);r.list.clear();
		if(kind==ARRAY_KEY_DESC){for(int[] item:r.items.values())disposeHandleOf(item[1]);r.items.clear();}
		for(int i=0;i<count;i++){
			switch(kind){
				case ARRAY_HANDLE:{byte[] data=tb.api.handleContents(memory.readLong(array+i*4));r.list.add(new int[]{type,tb.api.createHandle(data==null?new byte[0]:data)});break;}
				case ARRAY_DESC:r.list.add(copyDesc(array+i*8));break;
				case ARRAY_KEY_DESC:r.items.put(memory.readLong(array+i*12),copyDesc(array+i*12+4));break;
				default:r.list.add(new int[]{type,tb.api.createHandle(size>0?memory.readBytes(array+i*size,size):new byte[0])});break;
			}
		}
		return 0;
	}
	private static byte[] longBytes(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}

	// ------------------------------------------------------------------ descriptors

	void writeDesc(int desc,int type,int handle) throws M68kCpuException {if(desc==0)return;memory.writeLong(desc,type);memory.writeLong(desc+4,handle);}
	int[] readDesc(int desc) throws M68kCpuException {return desc==0?new int[]{TYPE_NULL,0}:new int[]{memory.readLong(desc),memory.readLong(desc+4)};}
	private Record record(int desc) throws M68kCpuException {if(desc==0)return null;return recordFor(memory.readLong(desc),memory.readLong(desc+4));}
	/** The record/list behind a descriptor's data Handle, or null for plain data. */
	Record recordOf(int handle){try{return recordFor(0,handle);}catch(M68kCpuException e){return null;}}
	/** Registers a Java-side record under a fresh Handle (its descriptor type is the caller's business). */
	int adopt(Record r) throws M68kCpuException {int h=register(r);return h;}
	int createHandleOf(byte[] data) throws M68kCpuException {return tb.api.createHandle(data);}
	/** Disposes a descriptor given as {type, handle} rather than at an address. */
	void disposeDescriptor(int[] d) throws M68kCpuException {int temp=memory.allocate(8,2);try{writeDesc(temp,d[0],d[1]);disposeDesc(temp);}finally{memory.free(temp);}}
	private void createDesc(int desc,int type,byte[] data) throws M68kCpuException {writeDesc(desc,type,tb.api.createHandle(data));}
	void disposeDesc(int desc) throws M68kCpuException {
		if(desc==0)return;int h=memory.readLong(desc+4);
		disposeHandleOf(h);
		writeDesc(desc,TYPE_NULL,0);
	}
	private int[] copyDesc(int desc) throws M68kCpuException {int[] d=readDesc(desc);return copyDescriptor(d);}
	int[] copyDescriptor(int[] d) throws M68kCpuException {
		if(d[1]==0)return new int[]{d[0],0};
		Record r=recordFor(d[0],d[1]);
		if(r!=null){Record copy=new Record();copy.isList=r.isList;copy.isRecord=r.isRecord;copy.bareBody=r.bareBody;copy.eventClass=r.eventClass;copy.eventId=r.eventId;
			for(Map.Entry<Integer,int[]> e:r.items.entrySet())copy.items.put(e.getKey(),copyDescriptor(e.getValue()));
			for(int[] item:r.list)copy.list.add(copyDescriptor(item));
			for(Map.Entry<Integer,int[]> e:r.attributes.entrySet())copy.attributes.put(e.getKey(),copyDescriptor(e.getValue()));
			int h=register(copy);return new int[]{d[0],h};}
		byte[] data=tb.api.handleContents(d[1]);return new int[]{d[0],tb.api.createHandle(data==null?new byte[0]:data)};
	}
	private int duplicate(int source,int resultDesc) throws M68kCpuException {int[] copy=copyDesc(source);if(Mac68kApi.TRACE)System.err.println("[ae] duplicate "+fourcc(memory.readLong(source))+" ("+(copy[1]==0?0:tb.api.handleSize(copy[1]))+" bytes)");writeDesc(resultDesc,copy[0],copy[1]);return 0;}

	private int[] lookup(int event,int keyword,boolean attribute) throws M68kCpuException {
		Record r=record(event);if(r==null)return null;
		int[] d=(attribute?r.attributes:r.items).get(keyword);
		if(d==null&&attribute){ // synthesized attributes
			if(keyword==KEY_EVENT_CLASS)return new int[]{TYPE_TYPE,tb.api.createHandle(longBytes(r.eventClass))};
			if(keyword==KEY_EVENT_ID)return new int[]{TYPE_TYPE,tb.api.createHandle(longBytes(r.eventId))};
			if(keyword==KEY_MISSED)return null;
			// the sender's interaction flags (AESend mode bits): kAECanInteract, as the Finder's Open Documents carries. Fontographer 4
			// opens a document only when they allow interaction ($20/$30) - with 2 (AEGetInteractionAllowed's kAEInteractWithAll, not
			// a send mode) every dropped .fog failed with errAEEventNotHandled
			if(keyword==KEY_INTERACT)return new int[]{TYPE_LONG,tb.api.createHandle(longBytes(0x20))};
			// attributes the Apple Event Manager stamps on every received event: HyperCard reads the transaction ID and the
			// event source of an Open Documents event before it acts on it
			if(keyword==KEY_TRANSACTION||keyword==KEY_TIMEOUT)return new int[]{TYPE_LONG,tb.api.createHandle(longBytes(keyword==KEY_TIMEOUT?-1:0))};
			if(keyword==KEY_EVENT_SOURCE)return new int[]{TYPE_SHOR,tb.api.createHandle(new byte[]{0,3})}; // kAELocalProcess
			if(keyword==KEY_RETURN_ID)return new int[]{TYPE_LONG,tb.api.createHandle(longBytes(1))};
			if(keyword==KEY_ADDRESS||keyword==KEY_ORIGINAL_ADDRESS)return new int[]{TYPE_PSN,tb.api.createHandle(new byte[]{0,0,0,0,0,0,0,2})}; // kCurrentProcess
		}
		return d;
	}
	private int[] nth(int list,int index,int keywordPtr) throws M68kCpuException {
		Record r=record(list);if(r==null)return null;
		if(r.isList){if(index<1||index>r.list.size())return null;if(keywordPtr!=0)memory.writeLong(keywordPtr,0);return r.list.get(index-1);}
		int i=1;for(Map.Entry<Integer,int[]> e:r.items.entrySet()){if(i++==index){if(keywordPtr!=0)memory.writeLong(keywordPtr,e.getKey());return e.getValue();}}
		return null;
	}
	private void putNth(Record r,int index,int[] item){if(index<=0||index>r.list.size())r.list.add(item);else r.list.set(index-1,item);}

	/** Copies (and coerces) a descriptor's data to the application's buffer. */
	private int copyOut(int[] desc,int desired,int typePtr,int dataPtr,int max,int actualPtr) throws M68kException {
		int[] coerced=coerce(desc,desired);
		if(coerced==null)return ERR_COERCION_FAIL;
		byte[] data=coerced[1]==0?new byte[0]:tb.api.handleContents(coerced[1]);if(data==null)data=new byte[0];
		if(typePtr!=0)memory.writeLong(typePtr,coerced[0]);
		if(actualPtr!=0)memory.writeLong(actualPtr,data.length);
		int n=Math.min(max,data.length);if(dataPtr!=0&&n>0)memory.load(dataPtr,java.util.Arrays.copyOf(data,n));
		if(Mac68kApi.TRACE)System.err.println("[ae] copy out "+fourcc(desc[0])+" as "+fourcc(coerced[0])+": "+data.length+" bytes, "+n+" to $"+Integer.toHexString(dataPtr)+" (max "+max+")");
		if(coerced!=desc)disposeDescriptor(coerced);
		return 0;
	}
	private int getDesc(int event,int keyword,int desired,int resultDesc,boolean attribute) throws M68kException {
		int[] d=lookup(event,keyword,attribute);if(d==null){writeDesc(resultDesc,TYPE_NULL,0);return ERR_DESC_NOT_FOUND;}
		if(Mac68kApi.TRACE){byte[] data=d[1]==0?null:tb.api.handleContents(d[1]);StringBuilder b=new StringBuilder();if(data!=null)for(int i=0;i<Math.min(80,data.length);i++)b.append(String.format("%02x",data[i]));
			System.err.println("[ae] get "+fourcc(keyword)+" -> "+fourcc(d[0])+" ("+tb.api.handleSize(d[1])+" bytes"+(recordFor(d[0],d[1])!=null?", record":"")+") as "+fourcc(desired)+" "+b);}
		return coerceInto(d,desired,resultDesc);
	}
	private int coerceInto(int[] d,int desired,int resultDesc) throws M68kException {
		int[] coerced=coerce(d,desired);if(coerced==null){writeDesc(resultDesc,TYPE_NULL,0);return ERR_COERCION_FAIL;}
		int[] copy=coerced==d?copyDescriptor(d):coerced;writeDesc(resultDesc,copy[0],copy[1]);return 0;
	}

	/** Returns {@code d} itself when no coercion is needed, a new descriptor otherwise, or null. */
	private int[] coerce(int[] d,int desired) throws M68kException {
		if(desired==TYPE_WILD||desired==d[0]||desired==0)return d;
		byte[] data=d[1]==0?new byte[0]:tb.api.handleContents(d[1]);if(data==null)data=new byte[0];
		if(d[0]==TYPE_ALIS&&desired==TYPE_FSS){File f=tb.files.resolveAlias(data);if(f==null)return null;int spec=memory.allocate(70,2);try{tb.files.writeFileSpec(spec,f);}catch(IOException e){memory.free(spec);return null;}byte[] bytes=memory.readBytes(spec,70);memory.free(spec);return new int[]{TYPE_FSS,tb.api.createHandle(bytes)};}
		// FSRefs (Carbon programs ask AEGetNthPtr for typeFSRef where Navigation Services and the Finder hand out 'fss ' and 'alis')
		if(desired==TYPE_FSREF&&(d[0]==TYPE_FSS||d[0]==TYPE_ALIS))return fsRefDescriptor(fileOf(d[0],data));
		if(d[0]==TYPE_FSREF&&(desired==TYPE_FSS||desired==TYPE_ALIS)){File f=fileOfFSRef(data);if(f==null||!f.exists())return null;if(desired==TYPE_ALIS)return new int[]{TYPE_ALIS,tb.api.createHandle(tb.files.makeAlias(f))};
			int spec=memory.allocate(70,2);try{tb.files.writeFileSpec(spec,f);return new int[]{TYPE_FSS,tb.api.createHandle(memory.readBytes(spec,70))};}catch(IOException e){return null;}finally{memory.free(spec);}}
		if(d[0]==TYPE_FSS&&desired==TYPE_ALIS){int spec=memory.allocate(70,2);memory.load(spec,data);File f;try{f=tb.files.fileForSpec(spec);}catch(IOException e){f=null;}memory.free(spec);if(f==null)return null;return new int[]{TYPE_ALIS,tb.api.createHandle(tb.files.makeAlias(f))};}
		// typeAppParameters: an AppParameters record (EventRecord of a high-level event + eventRefCon + messageLength) followed by the event; the
		// Apple Software Installer coerces its Open Documents event to it and hands the bytes to LaunchApplication
		if(desired==0x61707061&&d[0]==TYPE_AEVT){Record r=recordFor(d[0],d[1]);
			if(r!=null){byte[] message=serialize(r);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
				out.write(0);out.write(23);putLong(out,TYPE_AEVT);putLong(out,0);putLong(out,r.eventId);out.write(0);out.write(0);putLong(out,0);putLong(out,message.length);
				out.write(message,0,message.length);return new int[]{desired,tb.api.createHandle(out.toByteArray())};}}
		// typeTargetID from a process serial number: sessionID = the serial, name = the process's default port (the Installer Engine looks the
		// sender of 'inst'/'iist' up by it)
		if(desired==0x74617267&&d[0]==TYPE_PSN&&data.length>=8){
			int buf=memory.allocate(TARGET_ID_SIZE,2);
			try{memory.fill(buf,TARGET_ID_SIZE,0);int low=MacApp68k.u4(data,4);memory.writeLong(buf,low);tb.writePortFor(low,buf+4);
				return new int[]{desired,tb.api.createHandle(memory.readBytes(buf,TARGET_ID_SIZE))};}
			finally{memory.free(buf);}}
		if(desired==TYPE_LONG&&d[0]==TYPE_SHOR&&data.length>=2){return new int[]{TYPE_LONG,tb.api.createHandle(longBytes((short)MacApp68k.u2(data,0)))};}
		if(desired==TYPE_SHOR&&d[0]==TYPE_LONG&&data.length>=4){int v=MacApp68k.u4(data,0);return new int[]{TYPE_SHOR,tb.api.createHandle(new byte[]{(byte)(v>>8),(byte)v})};}
		if((desired==TYPE_LONG||desired==TYPE_SHOR)&&d[0]==TYPE_TEXT){try{int v=Integer.parseInt(tb.decode(data).trim());return desired==TYPE_LONG?new int[]{TYPE_LONG,tb.api.createHandle(longBytes(v))}:new int[]{TYPE_SHOR,tb.api.createHandle(new byte[]{(byte)(v>>8),(byte)v})};}catch(NumberFormatException e){return null;}}
		if(desired==TYPE_TEXT&&(d[0]==TYPE_LONG||d[0]==TYPE_SHOR)){int v=d[0]==TYPE_LONG?MacApp68k.u4(data,0):(short)MacApp68k.u2(data,0);return new int[]{TYPE_TEXT,tb.api.createHandle(tb.encoding.encodeReplacing(Integer.toString(v)))};}
		// typeTrue/typeFalse (no data) and the enumerators 'true'/'fals' are booleans, and a boolean is either of them: AppleScript makes
		// HyperCard's "true" a 'true' descriptor and asks for 'bool' ("Can't make "true" into a boolean" from AppleScript Text Controls)
		if(desired==TYPE_BOOL&&(d[0]==TYPE_TRUE||d[0]==TYPE_FALSE))return new int[]{TYPE_BOOL,tb.api.createHandle(new byte[]{(byte)(d[0]==TYPE_TRUE?1:0)})};
		if(desired==TYPE_BOOL&&d[0]==TYPE_ENUM&&data.length>=4&&(MacApp68k.u4(data,0)==TYPE_TRUE||MacApp68k.u4(data,0)==TYPE_FALSE))return new int[]{TYPE_BOOL,tb.api.createHandle(new byte[]{(byte)(MacApp68k.u4(data,0)==TYPE_TRUE?1:0)})};
		if(d[0]==TYPE_BOOL&&data.length>=1&&(desired==TYPE_TRUE||desired==TYPE_FALSE)){boolean v=data[data.length-1]!=0;return v==(desired==TYPE_TRUE)?new int[]{desired,0}:null;}
		if(d[0]==TYPE_BOOL&&data.length>=1&&desired==TYPE_ENUM)return new int[]{TYPE_ENUM,tb.api.createHandle(longBytes(data[data.length-1]!=0?TYPE_TRUE:TYPE_FALSE))};
		if((d[0]==TYPE_TRUE||d[0]==TYPE_FALSE)&&(desired==TYPE_SHOR||desired==TYPE_LONG)){int v=d[0]==TYPE_TRUE?1:0;return new int[]{desired,tb.api.createHandle(desired==TYPE_LONG?longBytes(v):new byte[]{0,(byte)v})};}
		if(desired==TYPE_BOOL&&(d[0]==TYPE_LONG||d[0]==TYPE_SHOR)){boolean v=data.length>0&&data[data.length-1]!=0;return new int[]{TYPE_BOOL,tb.api.createHandle(new byte[]{(byte)(v?1:0)})};}
		// the numeric coercions the Apple Event Manager makes itself (Inside Macintosh: Interapplication Communication, table 4-1):
		// short and long integers, single, double and extended floats, comp and magnitude into one another, and text into numbers.
		// Scriptable Text Editor coerces a font size it gets as 'long' to 'exte': HyperCard's "AppleScript Text Controls" stopped
		// with "Can't make some data into the expected type" when it opened.
		if(numeric(desired)){Double v=d[0]==TYPE_TEXT?parseNumber(tb.decode(data)):numberOf(d[0],data);
			if(v!=null){byte[] b=numberBytes(desired,v);if(b!=null)return new int[]{desired,tb.api.createHandle(b)};}}
		if(desired==TYPE_TEXT&&numeric(d[0])){Double v=numberOf(d[0],data);if(v!=null)return new int[]{TYPE_TEXT,tb.api.createHandle(tb.encoding.encodeReplacing(numberText(d[0],v)))};}
		if(desired==TYPE_LIST&&recordFor(d[0],d[1])==null){Record r=new Record();r.isList=true;r.list.add(copyDescriptor(d));int h=register(r);return new int[]{TYPE_LIST,h};}
		// a list of one item coerces as that item: Apple's Navigation Services samples coerce reply.selection straight to 'fss '
		// (Audion's Save Playlist got errAECoercionFail and wrote nothing)
		if(d[0]==TYPE_LIST){Record r=recordFor(d[0],d[1]);if(r!=null&&r.isList&&r.list.size()==1){int[] item=r.list.get(0);int[] c=coerce(item,desired);return c==item?copyDescriptor(item):c;}}
		if(desired==TYPE_TYPE&&d[0]==TYPE_ENUM||desired==TYPE_ENUM&&d[0]==TYPE_TYPE)return new int[]{desired,tb.api.createHandle(data)};
		if(desired==TYPE_AEVT&&d[0]==TYPE_RECO||desired==TYPE_RECO&&d[0]==TYPE_AEVT)return d;
		// An AERecord coerces to any type - the type is what makes it an object specifier ('obj '), a range, a comparison... - and any
		// record-shaped descriptor coerces back to 'reco'/'aevt'; between two other types (some utilities ask 'obj ' -> 'fss ' first and
		// resolves the specifier itself when that fails) only a coercion handler of the program's can help.
		{Record r=recordFor(d[0],d[1]);if(r!=null&&r.isRecord&&(d[0]==TYPE_RECO||d[0]==TYPE_AEVT||desired==TYPE_RECO||desired==TYPE_AEVT)){int[] copy=copyDescriptor(d);return new int[]{desired,copy[1]};}}
		if(desired==TYPE_RECO||desired==TYPE_AEVT){Record body=parseRecordBody(data);if(body!=null)return new int[]{desired,register(body)};} // a record's item data (AEGizmos hands those around as descriptors of their own)
		int[] viaHandler=coerceWithHandler(d,desired,data);
		if(viaHandler==null&&Mac68kApi.TRACE){StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(48,data.length);i++)b.append(String.format("%02x",data[i]));System.err.println("[ae] no coercion from "+fourcc(d[0])+" to "+fourcc(desired)+" ("+data.length+" bytes: "+b+")");}
		return viaHandler;
	}
	static final int TYPE_TRUE=0x74727565,TYPE_FALSE=0x66616c73;
	static final int TYPE_SING=0x73696e67,TYPE_DOUB=0x646f7562,TYPE_EXTE=0x65787465,TYPE_COMP=0x636f6d70,TYPE_MAGN=0x6d61676e;
	static boolean numeric(int type){return type==TYPE_SHOR||type==TYPE_LONG||type==TYPE_SING||type==TYPE_DOUB||type==TYPE_EXTE||type==TYPE_COMP||type==TYPE_MAGN;}
	/** A number descriptor's value; null when the type is no number or the data is short. */
	static Double numberOf(int type,byte[] b){
		switch(type){
			case TYPE_SHOR:return b.length>=2?(double)(short)MacApp68k.u2(b,0):null;
			case TYPE_LONG:return b.length>=4?(double)MacApp68k.u4(b,0):null;
			case TYPE_MAGN:return b.length>=4?(double)(MacApp68k.u4(b,0)&0xffffffffL):null;
			case TYPE_COMP:return b.length>=8?(double)((long)MacApp68k.u4(b,0)<<32|MacApp68k.u4(b,4)&0xffffffffL):null;
			case TYPE_SING:return b.length>=4?(double)Float.intBitsToFloat(MacApp68k.u4(b,0)):null;
			case TYPE_DOUB:return b.length>=8?Double.longBitsToDouble((long)MacApp68k.u4(b,0)<<32|MacApp68k.u4(b,4)&0xffffffffL):null;
			case TYPE_EXTE:return b.length>=10?extendedValue(b,b.length>=12?4:2):null; // SANE's 10 bytes, or the 68881's 12 (2 pad bytes)
			default:return null;
		}
	}
	/** A number as a descriptor of {@code type}'s data; null when it does not fit (an integer type out of range, NaN). */
	static byte[] numberBytes(int type,double v){ // integers rounded to the nearest, ties to even, as SANE rounds
		switch(type){
			case TYPE_SHOR:{if(Double.isNaN(v)||v<-32768.5||v>=32767.5)return null;long n=(long)Math.rint(v);return new byte[]{(byte)(n>>8),(byte)n};}
			case TYPE_LONG:{if(Double.isNaN(v)||v<-2147483648.5||v>=2147483647.5)return null;return longBytes((int)(long)Math.rint(v));}
			case TYPE_MAGN:{if(Double.isNaN(v)||v<-0.5||v>=4294967295.5)return null;return longBytes((int)(long)Math.rint(v));}
			case TYPE_COMP:{if(Double.isNaN(v)||Math.abs(v)>=9.2e18)return null;long n=(long)Math.rint(v);return new byte[]{(byte)(n>>56),(byte)(n>>48),(byte)(n>>40),(byte)(n>>32),(byte)(n>>24),(byte)(n>>16),(byte)(n>>8),(byte)n};}
			case TYPE_SING:return longBytes(Float.floatToIntBits((float)v));
			case TYPE_DOUB:{long n=Double.doubleToLongBits(v);return new byte[]{(byte)(n>>56),(byte)(n>>48),(byte)(n>>40),(byte)(n>>32),(byte)(n>>24),(byte)(n>>16),(byte)(n>>8),(byte)n};}
			case TYPE_EXTE:return extendedBytes(v);
			default:return null;
		}
	}
	/** Text as a number: an integer or a decimal ("12", "-3.5", "1e3"), null otherwise. */
	static Double parseNumber(String text){
		String t=text.trim();if(t.isEmpty()||!t.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?"))return null;
		try{return Double.parseDouble(t);}catch(NumberFormatException e){return null;}
	}
	/** A number as text: integers without a decimal point, floats as AppleScript writes them. */
	static String numberText(int type,double v){
		if(type==TYPE_SHOR||type==TYPE_LONG||type==TYPE_MAGN||type==TYPE_COMP)return Long.toString((long)v);
		if(v==Math.rint(v)&&Math.abs(v)<1e15)return Long.toString((long)v)+".0";
		return Double.toString(v);
	}
	/** SANE's 80-bit extended format: sign and 15-bit exponent (bias 16383), then a 64-bit significand with its integer bit. */
	static byte[] extendedBytes(double v){
		byte[] b=new byte[10];long bits=Double.doubleToRawLongBits(v);int sign=(int)(bits>>>63),exponent=(int)(bits>>>52&0x7ff);long fraction=bits&0xfffffffffffffL;
		int e;long m;
		if(exponent==0x7ff){e=0x7fff;m=fraction==0?0x8000000000000000L:0xc000000000000000L;}
		else if(exponent==0){if(fraction==0){e=0;m=0;}else{int lz=Long.numberOfLeadingZeros(fraction);e=(63-lz)-1074+16383;m=fraction<<lz;}}
		else{e=exponent-1023+16383;m=1L<<63|fraction<<11;}
		e|=sign<<15;b[0]=(byte)(e>>8);b[1]=(byte)e;for(int i=0;i<8;i++)b[2+i]=(byte)(m>>>(56-8*i));
		return b;
	}
	/** The value of an extended float whose significand starts at {@code mantissa} (the sign and exponent are the first word). */
	static double extendedValue(byte[] b,int mantissa){
		int se=MacApp68k.u2(b,0),e=se&0x7fff;long m=0;for(int i=0;i<8;i++)m=m<<8|b[mantissa+i]&255;
		double v;
		if(e==0x7fff)v=(m<<1)==0?Double.POSITIVE_INFINITY:Double.NaN;
		else if(m==0)v=0;
		else v=Math.scalb((double)(m>>>11),e-16383-52); // the top 53 bits of the significand
		return (se&0x8000)!=0?-v:v;
	}

	/** The program's coercion handler for (fromType, toType), or the wildcard ones: pascal OSErr handler(fromDesc, toType, refcon,
	 *  VAR result) for descriptor handlers, handler(typeCode, dataPtr, dataSize, toType, refcon, VAR result) for pointer ones. */
	private int[] coerceWithHandler(int[] d,int desired,byte[] data) throws M68kException {
		int[] h=coercions.get(key(d[0],desired));if(h==null)h=coercions.get(key(d[0],TYPE_WILD));if(h==null)h=coercions.get(key(TYPE_WILD,desired));if(h==null)return null;
		if(Mac68kApi.TRACE)System.err.println("[ae] coercion handler "+fourcc(d[0])+" -> "+fourcc(desired)+" at $"+Integer.toHexString(h[0]));
		M68kCpu cpu=tb.cpu;int saved=cpu.a[7];
		cpu.a[7]-=16;int from=cpu.a[7],result=from+8;writeDesc(from,d[0],d[1]);writeDesc(result,TYPE_NULL,0);
		try{
			//the data copy goes above the Pascal result slot: between the slot and the arguments it shifted the slot the handler
			//writes (SendAppleEvent's makeTargetID cleared the first bytes of the port name, "\0\0SuchApp")
			int buffer=0;if(h[2]==0){cpu.a[7]-=(data.length+1)&~1;buffer=cpu.a[7];memory.load(buffer,data);}
			cpu.pushWord(0);int slot=cpu.a[7];
			if(h[2]!=0){cpu.pushLong(from);cpu.pushLong(desired);cpu.pushLong(h[1]);cpu.pushLong(result);cpu.callPascal(h[0],16);}
			else{cpu.pushLong(d[0]);cpu.pushLong(buffer);cpu.pushLong(data.length);cpu.pushLong(desired);cpu.pushLong(h[1]);cpu.pushLong(result);cpu.callPascal(h[0],24);}
			int err=(short)memory.readWord(slot);
			if(err!=0)return null;
			int[] coerced=readDesc(result);return coerced[1]==0&&coerced[0]==TYPE_NULL?null:coerced;
		}finally{cpu.a[7]=saved;}
	}

	// ------------------------------------------------------------------ dispatch

	private int processAppleEvent(int eventRecord) throws M68kException {
		EventManager68k.Event e=current;current=null;
		if(e==null||e.what!=EventManager68k.HIGH_LEVEL_EVT)return -1701;
		if(e.appleEvent!=null)return processDelivered(e.appleEvent);
		int cls=TYPE_AEVT,id=EventManager68k.highLevelEventId(e); // quit / odoc / oapp
		Record r=new Record();r.isRecord=true;r.eventClass=cls;
		r.attributes.put(KEY_ADDRESS,new int[]{TYPE_PSN,tb.api.createHandle(new byte[]{0,0,0,0,(byte)(Toolbox68k.FINDER_SERIAL>>24),(byte)(Toolbox68k.FINDER_SERIAL>>16),(byte)(Toolbox68k.FINDER_SERIAL>>8),(byte)Toolbox68k.FINDER_SERIAL})}); // sent by the "Finder": HyperCard ignores an Open Documents event whose sender is itself
		if(id==0x6f646f63){Record list=new Record();list.isList=true;List<File> docs=e.files!=null?e.files:e.buffer!=null?documentsFromMessage(e.buffer):new ArrayList<File>();for(File f:docs)list.list.add(new int[]{TYPE_ALIS,tb.api.createHandle(tb.files.makeAlias(f))});int lh=register(list);r.items.put(KEY_DIRECT,new int[]{TYPE_LIST,lh});}
		r.eventId=id;
		int eh=register(r);
		int event=memory.allocate(8,2),reply=memory.allocate(8,2);writeDesc(event,TYPE_AEVT,eh);
		Record replyRecord=new Record();replyRecord.isRecord=true;int rh=register(replyRecord);writeDesc(reply,TYPE_AEVT,rh);
		int result=dispatch(cls,id,event,reply);
		disposeDesc(event);disposeDesc(reply);memory.free(event);memory.free(reply);
		if(id==0x71756974&&result==ERR_NOT_HANDLED)throw new Toolbox68k.ExitToShell();
		if(id==0x71756974&&result==USER_CANCELED)tb.events.quitCancelledByProgram(); // "Save changes?" answered Cancel: HyperZebra's quit stops
		return result;
	}
	/** userCanceledErr: a 'quit' handler's answer when the user cancelled in its "Save changes?" dialog. */
	static final int USER_CANCELED=-128;

	/** Whether the program installed a handler that would take (class, id). */
	boolean hasHandler(int cls,int id){return handlers.containsKey(key(cls,id))||handlers.containsKey(key(cls,TYPE_WILD))||handlers.containsKey(key(TYPE_WILD,id))||handlers.containsKey(key(TYPE_WILD,TYPE_WILD));}
	/**
	 * An event the Toolbox makes itself (the Text Services Manager passing an input method's events) goes straight to the
	 * program's handler, as if the program had sent it to itself: {@code params} are keyword -> {type, data bytes}. The
	 * reply's item {@code wantedReply} (0 = none) comes back in {@code replyOut} as {type, data...} when the handler put one
	 * there. Returns the handler's result (errAEEventNotHandled without a handler).
	 */
	int sendToSelf(int cls,int id,LinkedHashMap<Integer,Object[]> params,int wantedReply,java.util.List<byte[]> replyOut) throws M68kException {
		Record r=new Record();r.isRecord=true;r.eventClass=cls;r.eventId=id;
		for(Map.Entry<Integer,Object[]> e:params.entrySet())r.items.put(e.getKey(),new int[]{(Integer)e.getValue()[0],tb.api.createHandle((byte[])e.getValue()[1])});
		int eh=register(r);
		int event=memory.allocate(8,2),reply=memory.allocate(8,2);writeDesc(event,TYPE_AEVT,eh);
		Record replyRecord=new Record();replyRecord.isRecord=true;replyRecord.eventClass=TYPE_AEVT;replyRecord.eventId=0x616e7372;int rh=register(replyRecord);writeDesc(reply,TYPE_AEVT,rh); // 'ansr'
		int result;
		try{result=dispatch(cls,id,event,reply);
			if(wantedReply!=0&&replyOut!=null){Record answer=record(reply);int[] d=answer==null?null:answer.items.get(wantedReply);if(d!=null){replyOut.add(longBytes(d[0]));byte[] data=d[1]==0?new byte[0]:tb.api.handleContents(d[1]);replyOut.add(data==null?new byte[0]:data);}}}
		finally{disposeDesc(event);disposeDesc(reply);memory.free(event);memory.free(reply);}
		return result;
	}

	/** SendAEFromTSMComponent: an event the program built (posing as an input method) goes to its own handler; the reply
	 *  descriptor gets a record for the handler to fill (the caller disposes it). */
	int sendFromComponent(int event,int reply) throws M68kException {
		Record r=record(event);if(r==null)return -1704; // errAENotAEDesc
		int temp=0;
		if(reply==0){temp=memory.allocate(8,2);reply=temp;writeDesc(reply,TYPE_NULL,0);}
		if(memory.readLong(reply+4)==0){Record answer=new Record();answer.isRecord=true;answer.eventClass=TYPE_AEVT;answer.eventId=0x616e7372;int rh=register(answer);writeDesc(reply,TYPE_AEVT,rh);}
		try{return dispatch(r.eventClass,r.eventId,event,reply);}
		finally{if(temp!=0){disposeDesc(temp);memory.free(temp);}}
	}

	private int dispatch(int cls,int id,int event,int reply) throws M68kException {
		Integer pre=specialHandlers.get(KEY_PRE_DISPATCH);
		if(pre!=null&&pre!=0){int r=callHandler(new int[]{pre,0},event,reply);if(Mac68kApi.TRACE)System.err.println("[ae] pre-dispatch handler for "+fourcc(cls)+"/"+fourcc(id)+" returned "+r);if(r!=ERR_NOT_HANDLED)return r;} // keyPreDispatch: errAEEventNotHandled lets the event through to its handler; anything else (noErr included) is the event's result (AppleScript's kOSAUseStandardDispatch pre-dispatch runs the script's handlers first and answers -1708 for events the script does not handle)
		int[] h=handlers.get(key(cls,id));
		if(h==null)h=handlers.get(key(cls,TYPE_WILD));
		if(h==null)h=handlers.get(key(TYPE_WILD,id));
		if(h==null)h=handlers.get(key(TYPE_WILD,TYPE_WILD));
		if(Mac68kApi.TRACE)System.err.println("[ae] dispatch "+fourcc(cls)+"/"+fourcc(id)+" -> "+(h==null?"no handler":"$"+Integer.toHexString(h[0])));
		if(h==null||h[0]==0)return ERR_NOT_HANDLED;
		int r=callHandler(h,event,reply);if(Mac68kApi.TRACE)System.err.println("[ae] handler "+fourcc(cls)+"/"+fourcc(id)+" returned "+r+(r!=0&&r!=ERR_NOT_HANDLED&&Boolean.getBoolean("hz68k.aetracepcs")?"\n"+tb.cpu.dumpState(tb.cpu.pc):""));return r;
	}
	/** Data handles of events a handler suspended (AESuspendTheCurrentEvent): the program disposes of those itself. */
	private final java.util.Set<Integer> suspended=new java.util.HashSet<Integer>();
	/** The events whose handlers are running, innermost last: {type, handle} of each event descriptor (AEGetTheCurrentEvent). */
	private final java.util.ArrayDeque<int[]> handling=new java.util.ArrayDeque<int[]>();
	int callHandler(int[] h,int event,int reply) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		// pascal OSErr handler(event, reply, refCon), called as the AE Manager does (a Pascal FUNCTION inline, CallTableDispatch):
		// a cleared result slot, and the result is the word the stack pointer is left on. A handler compiled without `pascal`
		// leaves the arguments there, so the AE Manager reads the refCon's high word (0 for most programs), not its D0: Peekaboo's
		// 'oapp' handler is one, and the uncleared slot was reported as "AEProcessAppleEvent -21156".
		cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushLong(event);cpu.pushLong(reply);cpu.pushLong(h[1]);
		int result;
		handling.push(new int[]{memory.readLong(event),memory.readLong(event+4)});
		try{cpu.callPascal(h[0],12);result=(short)memory.readWord(cpu.lastReturnSp);cpu.a[7]+=2;}
		finally{handling.pop();System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		return result;
	}

	// ------------------------------------------------------------------ private handler tables (_AE_hdlr_table_alloc & co.)

	/** AE_hdlr_table: pad, n_allocated_bytes, n_elts, 40 bytes of pad, then 24-byte elements {-1, sel0, sel1, fn, refcon, -1}. */
	private static final int TABLE_HEADER=52,ELT_SIZE=24;
	private int hdlrTableAlloc(int tablePtr) throws M68kCpuException {
		int table=tb.api.createHandle(new byte[TABLE_HEADER]);int p=memory.readLong(table);
		memory.writeLong(p+4,TABLE_HEADER);memory.writeLong(p+8,0);
		if(tablePtr!=0)memory.writeLong(tablePtr,table);
		return 0;
	}
	/** Index of the element with the selector (and, when {@code fn} is non-zero, that handler), or -1. */
	private int hdlrTableFind(int table,int selector,int fn) throws M68kCpuException {
		if(table==0)return -1;int p=memory.readLong(table);if(p==0)return -1;
		int n=memory.readLong(p+8),sel0=memory.readLong(selector),sel1=memory.readLong(selector+4);
		for(int i=0;i<n;i++){int e=p+TABLE_HEADER+i*ELT_SIZE;
			if(memory.readLong(e+4)==sel0&&memory.readLong(e+8)==sel1&&(fn==0||memory.readLong(e+12)==fn))return i;}
		return -1;
	}
	private int hdlrTableLookup(int table,int selector,int hdlr) throws M68kCpuException {
		int i=hdlrTableFind(table,selector,0);
		if(i<0){ // the Apple Event Manager's fallbacks: (class, ****), (****, id), (****, ****) - HyperCard installs its event handlers as ****/oapp etc. and dispatches through this lookup
			int sel0=memory.readLong(selector),sel1=memory.readLong(selector+4);int temp=memory.allocate(8,2);
			try{int[][] tries={{sel0,TYPE_WILD},{TYPE_WILD,sel1},{TYPE_WILD,TYPE_WILD}};
				for(int[] t:tries){if(t[0]==sel0&&t[1]==sel1)continue;memory.writeLong(temp,t[0]);memory.writeLong(temp+4,t[1]);i=hdlrTableFind(table,temp,0);if(i>=0)break;}}
			finally{memory.free(temp);}}
		if(Mac68kApi.TRACE)System.err.println("[ae] handler table $"+Integer.toHexString(table)+" lookup "+fourcc(memory.readLong(selector))+"/"+fourcc(memory.readLong(selector+4))+" -> "+(i<0?"none":"$"+Integer.toHexString(memory.readLong(memory.readLong(table)+TABLE_HEADER+i*ELT_SIZE+12))));
		if(i<0)return ERR_HANDLER_NOT_FOUND;
		int e=memory.readLong(table)+TABLE_HEADER+i*ELT_SIZE;
		if(hdlr!=0){memory.writeLong(hdlr,memory.readLong(e+12));memory.writeLong(hdlr+4,memory.readLong(e+16));}
		return 0;
	}
	private int hdlrTableInstall(int table,int selector,int hdlr) throws M68kCpuException {
		if(table==0||memory.readLong(table)==0)return -50;
		if(Mac68kApi.TRACE)System.err.println("[ae] handler table $"+Integer.toHexString(table)+" install "+fourcc(memory.readLong(selector))+"/"+fourcc(memory.readLong(selector+4))+" -> $"+Integer.toHexString(memory.readLong(hdlr)));
		int i=hdlrTableFind(table,selector,memory.readLong(hdlr));
		if(i<0){int n=memory.readLong(memory.readLong(table)+8);tb.api.resizeHandle(table,TABLE_HEADER+(n+1)*ELT_SIZE);
			int p=memory.readLong(table);memory.writeLong(p+8,n+1);i=n;int e=p+TABLE_HEADER+i*ELT_SIZE;
			memory.writeLong(e,-1);memory.writeLong(e+4,memory.readLong(selector));memory.writeLong(e+8,memory.readLong(selector+4));memory.writeLong(e+20,-1);}
		int e=memory.readLong(table)+TABLE_HEADER+i*ELT_SIZE;
		memory.writeLong(e+12,memory.readLong(hdlr));memory.writeLong(e+16,memory.readLong(hdlr+4));
		return 0;
	}
	private int hdlrTableDelete(int table,int selector) throws M68kCpuException {
		int i=hdlrTableFind(table,selector,0);if(i<0)return ERR_HANDLER_NOT_FOUND;
		int p=memory.readLong(table),n=memory.readLong(p+8);
		memory.copy(p+TABLE_HEADER+(i+1)*ELT_SIZE,p+TABLE_HEADER+i*ELT_SIZE,(n-i-1)*ELT_SIZE);
		memory.writeLong(p+8,n-1);
		return 0;
	}

	/**
	 * AESend: to kCurrentProcess the event is dispatched directly. Another running program (or this one, addressed by its signature or serial number) (addressed by process serial number
	 * or by signature) gets it through its event queue as a detached copy; with kAEWaitReply the sender waits for the
	 * receiver's handler to return (up to the timeout, a minute for the defaults) and gets the reply back the same way.
	 */
	/**
	 * A program LaunchApplication has just started is a process already: on a Mac an event sent to it waits in its queue. Here its
	 * VM still runs the extensions' INITs, so the sender waits (a few seconds at most) until the program's own code runs. Adobe
	 * PageMill launches QuickHelp and sends it the help request at once; it said "An error occurred while launching the Help
	 * system" (procNotFound) the first time.
	 */
	private static void awaitStart(MacApp68k target){
		long until=System.currentTimeMillis()+20_000;
		while(target.codeStartedAt==0&&!target.finished&&System.currentTimeMillis()<until){
			try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
	}

	private int send(int event,int replyDesc,int sendMode,int timeout) throws M68kException {
		Record r=record(event);if(r==null)return ERR_DESC_NOT_FOUND;
		if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder("[ae] AESend "+fourcc(r.eventClass)+"/"+fourcc(r.eventId)+" params");
			for(java.util.Map.Entry<Integer,int[]> e:r.items.entrySet()){byte[] d=tb.api.handleContents(e.getValue()[1]);sb.append(" ").append(fourcc(e.getKey())).append(":").append(fourcc(e.getValue()[0])).append("=").append(d==null?"null":d.length<=64?MidiHost68k.hex(d):d.length+" bytes");}
			int h=memory.readLong(event+4);byte[] raw=tb.api.handleContents(h);if(raw!=null)sb.append("\n[ae]   handle ").append(MidiHost68k.hex(java.util.Arrays.copyOf(raw,Math.min(raw.length,320))));
			System.err.println(sb);}
		// kAEDontExecute: the event is only for the recording of a script, nothing handles it (Disk Copy 6.3 sends itself 'Crea' so
		// after making a new image - handled, it "imaged" an unnamed disk and failed the whole operation)
		if((sendMode&0x2000)!=0){if(replyDesc!=0)writeDesc(replyDesc,TYPE_NULL,0);return 0;}
		int[] address=r.attributes.get(KEY_ADDRESS);
		boolean self=address==null;MacApp68k target=null;boolean finderStandIn=false;
		// a PSN {0,2} is kCurrentProcess, {1,2} the program with serial 2
		if(address!=null&&address[0]==TYPE_PSN){byte[] psn=tb.api.handleContents(address[1]);if(psn!=null&&psn.length>=8){int low=MacApp68k.u4(psn,4);self=MacApp68k.u4(psn,0)==0&&low==2||tb.app!=null&&low==tb.app.serialNumber||inHyperCard()&&low==Toolbox68k.HYPERCARD_SERIAL;if(!self){target=MacApp68k.bySerial(low);finderStandIn=target==null&&low==Toolbox68k.FINDER_SERIAL;if(target==null&&low==Toolbox68k.ASR_SERIAL){ // the Apple Software Restore stand-in: it ends when it is told to quit
					if(!Toolbox68k.asrBusy)return -600;
					if(r.eventClass==TYPE_AEVT&&r.eventId==0x71756974){Toolbox68k.asrBusy=false;if(tb.app!=null&&(tb.app.sizeFlags()&0x0100)!=0)tb.events.postApplicationDied(Toolbox68k.PSN_HIGH,Toolbox68k.ASR_SERIAL,0);}
					if(replyDesc!=0)writeDesc(replyDesc,TYPE_NULL,0);return 0;}}}}
		else if(address!=null&&address[0]==TYPE_SIGN){byte[] sign=tb.api.handleContents(address[1]);if(sign!=null&&sign.length>=4){int creator=MacApp68k.u4(sign,0);
			if(tb.app!=null&&tb.files.fileCreator(tb.app.file)==creator||inHyperCard()&&creator==Toolbox68k.HYPERCARD_CREATOR)self=true;else for(MacApp68k app:MacApp68k.runningApplications())if(app.toolbox!=null&&app.toolbox.files.fileCreator(app.file)==creator){target=app;break;}
			// the Finder by its signature 'MACS' while no real Finder runs: the stand-in (Rinaldi's LaunchDoc sends FNDR/sope this way: -600)
			if(!self&&target==null&&creator==0x4d414353)finderStandIn=true;}}
		else if(address!=null&&address[0]==TYPE_TARGET_ID){
			// a TargetID (what the PPC Browser picked, or a coercion handler made from "zone:machine:program"): sessionID, then the
			// port - its name (Str32 at +6), its kind (+$28) with the creator after it (+$2A by creator and type, chars 1-4 of the
			// type string +$2B by string): the program of that serial number, signature or name on this machine
			byte[] t=tb.api.handleContents(address[1]);
			if(t!=null&&t.length>=0x2f){int session=MacApp68k.u4(t,0),kind=MacApp68k.u2(t,0x28);String name=tb.decode(java.util.Arrays.copyOfRange(t,7,7+Math.min(32,t[6]&255)));
				int creator=kind==1?MacApp68k.u4(t,0x2a):kind==2&&(t[0x2a]&255)>=4?MacApp68k.u4(t,0x2b):0;
				if(inHyperCard()&&(session==Toolbox68k.HYPERCARD_SERIAL||creator==Toolbox68k.HYPERCARD_CREATOR||name.equalsIgnoreCase("HyperCard")))self=true;
				else if(tb.app!=null&&(session==tb.app.serialNumber||creator!=0&&tb.files.fileCreator(tb.app.file)==creator||name.equalsIgnoreCase(tb.app.name)))self=true;
				else{target=session!=0?MacApp68k.bySerial(session):null;
					if(target==null)for(MacApp68k app:MacApp68k.runningApplications())if(app.toolbox!=null&&(creator!=0&&app.toolbox.files.fileCreator(app.file)==creator||name.equalsIgnoreCase(app.name))){target=app;break;}
					if(target==null&&(session==Toolbox68k.FINDER_SERIAL||creator==0x4d414353||name.equalsIgnoreCase("Finder")))finderStandIn=true;}}}
		// The Finder stand-in GetNextProcess lists has no event queue: the event is accepted and ignored, with an empty reply. The Apple
		// Software Installer (iMovie 1.0 "Install iMovie") sends it 'quit' before installing and stopped with "not able to quit application".
		if(finderStandIn){
			if(!Toolbox68k.finderStandInPresent())return -600; // already quit
			if(r.eventClass==TYPE_AEVT&&r.eventId==0x71756974&&tb.app!=null)Toolbox68k.quitFinderStandIn(tb.app.serialNumber);
			// 'odoc': the Finder opens the items (DragThing's dock opens what is clicked this way, the direct object an object
			// specifier for the file's alias); they open the way HyperZebra opens a file the user double-clicks
			if(r.eventClass==TYPE_AEVT&&r.eventId==0x6f646f63){List<File> files=new ArrayList<File>();filesIn(export(new int[]{TYPE_AEVT,memory.readLong(event+4)}).items.get(KEY_DIRECT),files,0);
				if(Mac68kApi.TRACE)System.err.println("[68K] AESend aevt/odoc -> Finder "+files);
				if(!files.isEmpty()&&tb.hosted())PCARD.openFromFinder(tb.app==null?Sandbox.scriptRequester():tb.app.name,files);}
			// 'FNDR'/'sope' (Finder suite Open Selection: the direct object is the items' folder, keySelection 'fsel' the items): A-Dock
			// opens a favorite this way - a program that is not running is launched, a document opened, as from a double-click
			if(r.eventClass==0x464e4452&&r.eventId==0x736f7065){Portable sope=export(new int[]{TYPE_AEVT,memory.readLong(event+4)});List<File> files=new ArrayList<File>();
				filesIn(sope.items.get(0x6673656c),files,0);if(files.isEmpty())filesIn(sope.items.get(KEY_DIRECT),files,0);
				if(Mac68kApi.TRACE)System.err.println("[68K] AESend FNDR/sope -> Finder "+files);
				if(!files.isEmpty()&&tb.hosted())PCARD.openFromFinder(tb.app==null?Sandbox.scriptRequester():tb.app.name,files);}
			// The Finder suite's 'fndr'/'rest', 'shut', 'slep' (XTernal's RestartMac/ShutdownMac/SleepMac, a launcher's Shut Down): the host is never restarted, shut
			// down or put to sleep. Before restarting a Finder asks every program to quit, so a program that asks for a restart or a shut
			// down is told to quit as its own Quit item would (it may save, or cancel). An external's request does nothing: its
			// "program" is HyperZebra's stack session, which no external may end. Sleep has nothing to do.
			if(r.eventClass==0x666e6472&&(r.eventId==0x72657374||r.eventId==0x73687574)&&tb.app!=null&&tb.events!=null){
				if(Mac68kApi.TRACE)System.err.println("[68K] AESend fndr/"+fourcc(r.eventId)+" -> Finder: "+tb.app.name+" is told to quit");
				tb.events.postQuit();}
			if(replyDesc!=0)writeDesc(replyDesc,TYPE_NULL,0);return 0;}
		if(!self&&target!=null)awaitStart(target);
		if(!self&&(target==null||target.toolbox==null||target.finished))return -600; // procNotFound
		// Only kCurrentProcess (or no address) is dispatched directly: an event a program addresses to itself by signature or by
		// its own serial number goes through its event queue like anyone's, unless it waits for the reply. Cyberdog 2.0's Quit
		// sends 'quit' to its own signature from inside the menu command; handled at once, the document closed under the
		// command's own objects and the VM crashed on the freed SOM object when the command returned.
		boolean currentProcess=address==null||address[0]==TYPE_PSN&&isCurrentProcess(address[1]);
		if(self&&!currentProcess&&(sendMode&3)!=3&&tb.app!=null&&tb.app.toolbox!=null){target=tb.app;self=false;}
		// A program that is not high-level-event aware (SIZE flag isHighLevelEventAware clear, or no SIZE) never gets high-level
		// events: told to quit, it has its Quit item chosen, as the Finder does with "puppet strings". A-Dock's "Quit" for BinHex 5.0
		// queued the 'quit' event, and BinHex's event dispatch jumped through its table with what = 23 into data.
		if(!self&&(target.sizeFlags()&0x0040)==0&&r.eventClass==TYPE_AEVT&&r.eventId==0x71756974){
			target.toolbox.events.postQuit();
			if(Mac68kApi.TRACE)System.err.println("[68K] AESend aevt/quit -> "+target.name+" (not high-level-event aware): Quit menu item");
			if(replyDesc!=0)writeDesc(replyDesc,TYPE_NULL,0);
			return 0;}
		if(!self){
			Portable p=export(new int[]{TYPE_AEVT,memory.readLong(event+4)});
			if(!p.attributes.containsKey(KEY_RETURN_ID)){Portable id=new Portable();id.type=TYPE_LONG;id.data=longBytes(returnId++);p.attributes.put(KEY_RETURN_ID,id);}
			Portable sender=new Portable();sender.type=TYPE_PSN;sender.data=new byte[8];putLong(sender.data,4,tb.app==null?0:tb.app.serialNumber);p.attributes.put(0x66726f6d,sender); // keyOriginalAddressAttr 'from'
			target.toolbox.events.postAppleEvent(p,tb.app==null?0:tb.app.serialNumber);
			if(Mac68kApi.TRACE)System.err.println("[68K] AESend "+fourcc(p.eventClass)+"/"+fourcc(p.eventId)+" -> "+target.name+" mode "+Integer.toHexString(sendMode));
			if(replyDesc!=0)writeDesc(replyDesc,TYPE_NULL,0);
			if((sendMode&3)!=3)return 0; // kAENoReply / kAEQueueReply (queued replies are not delivered)
			long deadline=System.currentTimeMillis()+(timeout==-2?600_000L:timeout<=0?60_000L:Math.max(1000L,timeout*1000L/60));
			while(!p.answered&&System.currentTimeMillis()<deadline&&!target.finished)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			if(!p.answered)return ERR_TIMEOUT;
			if(replyDesc!=0&&p.reply!=null){int[] reply=materialize(p.reply);writeDesc(replyDesc,reply[0],reply[1]);}
			int[] error=p.reply==null?null:lookupPortable(p.reply,KEY_ERROR_NUMBER);
			return 0;
		}
		// to ourselves the handler gets a copy of the event too, as AESend always sends one: the sender disposes of its own event when
		// AESend returns. Anarchie 2.0 sends itself 'Arch'/'List' with the URL's parts, suspends the event in the handler
		// (AESuspendTheCurrentEvent) and reads the parameters later - from the disposed original every AEGetParamPtr was -1701, so
		// it listed the server's root as anonymous whatever user and path were given.
		int copy=memory.allocate(8,2);int[] copied=materialize(export(new int[]{memory.readLong(event),memory.readLong(event+4)}));writeDesc(copy,copied[0],copied[1]);
		int reply=memory.allocate(8,2);Record replyRecord=new Record();replyRecord.isRecord=true;int rh=register(replyRecord);writeDesc(reply,TYPE_AEVT,rh);
		int result;
		try{result=dispatch(r.eventClass,r.eventId,copy,reply);
			// an external runs in HyperCard's process: what its own handlers leave is HyperCard's to handle (SendAEData sends
			// "Demo"/"NewC" to HyperCard by its process serial number and got procNotFound)
			if(result==ERR_NOT_HANDLED&&inHyperCard())result=toHyperCard(export(new int[]{memory.readLong(copy),memory.readLong(copy+4)}),record(reply));}
		finally{if(!suspended.remove(copied[1]))disposeDesc(copy);memory.free(copy);}
		if(replyDesc!=0){memory.copy(reply,replyDesc,8);}else disposeDesc(reply);
		memory.free(reply);
		if(result==ERR_NOT_HANDLED&&r.eventId==0x71756974&&r.eventClass==TYPE_AEVT)throw new Toolbox68k.ExitToShell();
		return result;
	}

	/** Whether this VM runs HyperCard externals (XCMD/XFCN): their process is HyperCard's, which HyperZebra is. */
	private boolean inHyperCard(){return tb.app==null&&tb.stack!=null;}

	/**
	 * An Apple event HyperCard handles for an external that sent it to HyperCard (itself, as externals run in HyperCard's process).
	 * As HyperCard does, the current card gets the message {@code appleEvent class, id, sender}; its handler reads the event with
	 * {@code request appleEvent class|id|sender|data [with keyword k]} and answers with {@code reply} ({@link #currentHyperCardEvent}).
	 */
	static final class HyperCardEvent {
		final String eventClass,eventId,sender;
		/** The text of each parameter by its keyword ('----' the direct object), coerced as HyperCard does for request. */
		final LinkedHashMap<Integer,String> parameters=new LinkedHashMap<Integer,String>();
		/** Set by HyperTalk when the appleEvent message got to HyperCard itself (no handler took it, or one passed it): HyperCard's own handling follows. */
		volatile boolean reachedHyperCard;
		/** What `reply` set: text by keyword ('errs' for reply error). */
		final LinkedHashMap<Integer,String> reply=new LinkedHashMap<Integer,String>();
		HyperCardEvent(String eventClass,String eventId,String sender){this.eventClass=eventClass;this.eventId=eventId;this.sender=sender;}
		/** `request appleEvent <what> [with keyword <keyword>]`: class, id, sender or data (the direct object, or the keyword's parameter); null for a parameter the event lacks. */
		String request(String what,String keyword){
			String w=what==null?"data":what.trim().toLowerCase(java.util.Locale.ROOT);
			if(w.equals("class"))return eventClass;if(w.equals("id"))return eventId;if(w.equals("sender"))return sender;
			return parameters.get(keyword==null||keyword.isEmpty()?KEY_DIRECT:keywordOf(keyword));}
		/** `reply <text> [with keyword <keyword>]` / `reply error <text>` (keyErrorString). */
		void reply(String text,String keyword,boolean error){reply.put(error?KEY_ERROR_STRING:keyword==null||keyword.isEmpty()?KEY_DIRECT:keywordOf(keyword),text==null?"":text);}
		private static int keywordOf(String k){String s=(k+"    ").substring(0,4);return (s.charAt(0)&255)<<24|(s.charAt(1)&255)<<16|(s.charAt(2)&255)<<8|(s.charAt(3)&255);}
	}
	/** The HyperCard events whose `appleEvent` messages run, innermost last. */
	private static final java.util.Deque<HyperCardEvent> HYPERCARD_EVENTS=new java.util.concurrent.ConcurrentLinkedDeque<HyperCardEvent>();
	/** The Apple event HyperCard is handling for an external (what `request appleEvent` reads and `reply` answers), or null. */
	static HyperCardEvent currentHyperCardEvent(){return HYPERCARD_EVENTS.peekLast();}
	/** How HyperCard takes an event: sends the appleEvent message and handles what passes; a test puts its own in. Answers noErr, errAEEventNotHandled... */
	interface HyperCardReceiver {int receive(HyperCardEvent event);}
	static volatile HyperCardReceiver hyperCardReceiver=AppleEvents68k::hyperCardReceives;
	private int toHyperCard(Portable event,Record reply) throws M68kException {
		HyperCardEvent e=new HyperCardEvent(fourcc(event.eventClass),fourcc(event.eventId),"HyperCard");
		for(Map.Entry<Integer,Portable> item:event.items.entrySet()){String text=textOf(item.getValue());if(text!=null)e.parameters.put(item.getKey(),text);}
		if(Mac68kApi.TRACE)System.err.println("[ae] AESend "+e.eventClass+"/"+e.eventId+" -> HyperCard "+e.parameters);
		int result;
		HYPERCARD_EVENTS.addLast(e);
		try{result=hyperCardReceiver.receive(e);}finally{HYPERCARD_EVENTS.removeLastOccurrence(e);}
		if(event.eventClass==TYPE_AEVT&&event.eventId==0x6f646f63&&result==ERR_NOT_HANDLED){List<File> files=new ArrayList<File>();filesIn(event.items.get(KEY_DIRECT),files,0);
			if(!files.isEmpty()){if(tb.hosted()&&!java.awt.GraphicsEnvironment.isHeadless())PCARD.openFromFinder(Sandbox.scriptRequester(),files);result=0;}} // open documents: as HyperCard opens a stack the Finder hands it
		if(reply!=null)for(Map.Entry<Integer,String> r:e.reply.entrySet())reply.items.put(r.getKey(),new int[]{TYPE_TEXT,tb.api.createHandle(tb.encoding.encodeReplacing(r.getValue()))});
		return result;
	}
	/** A parameter's text (request appleEvent data): text as it is, numbers and the like coerced to 'TEXT'; null for what has no text form. */
	private String textOf(Portable p) throws M68kException {
		if(p==null)return null;
		if(p.type==TYPE_TEXT||p.type==TYPE_CHAR)return tb.decode(p.data==null?new byte[0]:p.data);
		if(p.isList||p.isRecord)return null;
		int[] d=materialize(p);try{int[] c=coerce(d,TYPE_TEXT);if(c==null)return null;byte[] b=c[1]==0?new byte[0]:tb.api.handleContents(c[1]);if(c!=d&&c[1]!=d[1]&&c[1]!=0)tb.api.disposeHandleAndFree(c[1]);return b==null?null:tb.decode(b);}
		finally{if(d[1]!=0)tb.api.disposeHandleAndFree(d[1]);}
	}
	/**
	 * HyperCard's own way with an event: the message {@code appleEvent class, id, sender} to the current card; a handler that does
	 * not pass it has handled it. What passes (or finds no handler) HyperCard handles itself: do script ('misc'/'dosc') runs the
	 * direct object as statements and evaluate ('misc'/'eval') evaluates it, the result as the reply; 'quit' does nothing (no
	 * external may end HyperZebra's session), open application is accepted; anything else is errAEEventNotHandled.
	 */
	private static int hyperCardReceives(HyperCardEvent e){
		PCARD pc=PCARD.pc;OStack stack=pc==null?null:pc.stack;OObject card=stack==null?null:stack.curCard;
		if(card==null)return ERR_NOT_HANDLED;
		// the message goes from the card up the message path; one that no handler takes (or that is passed) reaches HyperCard,
		// for which appleEvent is no command: "Can't understand"
		try{TTalk.runExternalStatement("appleEvent "+literal(e.eventClass)+","+literal(e.eventId)+","+literal(e.sender),card);if(!e.reachedHyperCard)return 0;}
		catch(xTalkException|RuntimeException x){if(Mac68kApi.TRACE)System.err.println("[ae] appleEvent "+e.eventClass+"/"+e.eventId+" not handled by a script: "+x.getMessage());}
		int cls=HyperCardEvent.keywordOf(e.eventClass),id=HyperCardEvent.keywordOf(e.eventId);
		if(cls==TYPE_AEVT&&(id==0x71756974||id==0x6f617070))return 0; // quit, open application
		if(cls==0x6d697363&&(id==0x646f7363||id==0x6576616c)){ // misc/dosc, misc/eval
			String text=e.parameters.get(KEY_DIRECT);if(text==null)return ERR_DESC_NOT_FOUND;
			try{String value=id==0x646f7363?TTalk.runExternalStatement(text,card):TTalk.evaluateExternalExpression(text,card,card);if(!e.reply.containsKey(KEY_DIRECT))e.reply(value==null?"":value,null,false);return 0;} // the reply's direct object even when empty: SendAEData's getReply read -1701 without one
			catch(xTalkException|RuntimeException x){e.reply(x.getMessage()==null?"":x.getMessage(),null,true);return -10000;} // errAEEventFailed with the script's error
		}
		return ERR_NOT_HANDLED;
	}
	private static String literal(String s){return "\""+s.replace("\"","\" & quote & \"")+"\"";}

	private boolean isCurrentProcess(int handle) throws M68kCpuException {byte[] psn=tb.api.handleContents(handle);return psn!=null&&psn.length>=8&&MacApp68k.u4(psn,0)==0&&MacApp68k.u4(psn,4)==2;}

	/** An Apple event another program sent: materialized here, dispatched, and its reply handed back to the sender. */
	private int processDelivered(Portable p) throws M68kException {
		int[] desc=materialize(p);int event=memory.allocate(8,2),reply=memory.allocate(8,2);writeDesc(event,TYPE_AEVT,desc[1]);
		Record replyRecord=new Record();replyRecord.isRecord=true;replyRecord.eventClass=TYPE_AEVT;replyRecord.eventId=0x616e7377;int rh=register(replyRecord);writeDesc(reply,TYPE_AEVT,rh);
		int result;
		try{result=dispatch(p.eventClass,p.eventId,event,reply);
			if(result!=0&&!replyRecord.items.containsKey(KEY_ERROR_NUMBER))replyRecord.items.put(KEY_ERROR_NUMBER,new int[]{TYPE_LONG,tb.api.createHandle(longBytes(result))});
			p.reply=export(new int[]{TYPE_AEVT,rh});}
		finally{p.answered=true;if(!suspended.remove(desc[1]))disposeDesc(event);disposeDesc(reply);memory.free(event);memory.free(reply);}
		if(result==ERR_NOT_HANDLED&&p.eventId==0x71756974&&p.eventClass==TYPE_AEVT)throw new Toolbox68k.ExitToShell();
		return result;
	}

	boolean hasHandlers(){return !handlers.isEmpty();}
}

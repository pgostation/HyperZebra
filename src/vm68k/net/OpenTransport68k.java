import java.util.HashMap;
import java.util.Map;

/**
 * Open Transport for 68K programs. A classic 68K program links Apple's static glue (OpenTransport.o, OpenTptInet.o,
 * OpenTptUtils.o from the MPW libraries), whose entry points are Apple Shared Library Manager stubs: each is a 14-byte
 * stub record - cached address (+0), the function set's client vector record (+4, its class ID string at +16, such as
 * "OTClnt$LclClnt"), and the function's index (+12). An unbound stub jumps to __SLM11FuncDispatch, which calls the
 * dispatcher the ASLM keeps in its globals (ExpandMem+$10C, +$88) with A0 = the stub record. This class is that much
 * of the ASLM: the globals with a library manager object whose virtual functions succeed (InitLibraryManager creates the
 * client's manager through +$9C, InitOpenTransport loads its function set through +$40), and the dispatcher, which binds
 * each stub to a host gate running the {@link OpenTransport} function of that name - Pascal convention for the upper-case
 * names (the public API: the callee pops the arguments and stores the result above them), C for the rest (the *Priv
 * calls and the utilities: result in D0).
 *
 * The indexes below are the function sets' published order, as the stub records of the MPW glue number them.
 */
final class OpenTransport68k {
	private static final String[] FUNCTION_SETS={
		"OTClnt$LclUtils:1=OTAllocMemPriv,OTFreeMemPriv,InitOpenTransportPriv,CloseOpenTransportPriv,OTRegisterAsClientPriv,OTUnregisterAsClientPriv,OTExitPatchPriv,OTRegisterPort,OTUnregisterPort,OTScanPorts,OTFINDPORT,OTFINDPORTBYREF,OTGETINDEXEDPORT,OTChangePortState,OTDUMMYUTILPROC,OTCREATECONFIGURATION,OTDESTROYCONFIGURATION,OTCLONECONFIGURATION,OTCREATEPROTOCOLINFO,OTDESTROYPROTOCOLINFO,OTGETOPTIONINFOBYNAME,OTGETOPTIONINFOBYID,OTGETPROTOCOLLEVEL,OTGETUPPERINTERFACE,OTGETLOWERINTERFACE,OTHASOPTIONINFO,OTGETOPTIONS,OTCREATEOPTIONS,OTGETOPTIONSTRING,OTCREATEOPTIONSTRING,OTFreeMem",
		"OTClnt$CmnShared:1=OTCREATEPORTREF,OTGETDEVICETYPEFROMPORTREF,OTGETBUSTYPEFROMPORTREF,OTGETSLOTFROMPORTREF,OTSETDEVICETYPEINPORTREF,OTSETBUSTYPEINPORTREF,OTAtomicSetBit,OTAtomicClearBit,OTAtomicTestBit,OTAtomicAdd32,OTAtomicAdd16,OTAtomicAdd8,OTCompareAndSwapPtr,OTCompareAndSwap32,OTCompareAndSwap16,OTCompareAndSwap8,OTLIFOEnqueue,OTLIFODequeue,OTLIFOStealList,OTReverseList,OTEnqueue,OTDequeue,OTAddFirst,OTAddLast,OTRemoveFirst,OTRemoveLast,OTGetFirst,OTGetLast,OTIsInList,OTFindLink,OTRemoveLink,OTFindAndRemoveLink,OTGetIndexedLink,OTGetClockTimeInSecs,OTGetTimeStamp,OTSubtractTimeStamps,OTTimeStampInMilliseconds,OTTimeStampInMicroseconds,OTElapsedMilliseconds,OTElapsedMicroseconds,OTGetTimerTicks,OTIsAtInterruptLevel,OTCanLoadLibraries,OTEnterCriticalSection,OTLeaveCriticalSection,OTENTERINTERRUPT,OTLEAVEINTERRUPT,OTMemcpy,OTMemmove,OTMemzero,OTMemset,OTMemcmp,OTStrLength,OTStrCat,OTStrCopy,OTStrEqual,OTReleaseBuffer,OTBufferDataSize,OTReadBuffer,OTDoTimingLog,OTCreateSystemTaskPriv,OTDESTROYSYSTEMTASK,OTSCHEDULESYSTEMTASK,OTCANCELSYSTEMTASK,OTCreateDeferredTaskPriv,OTDESTROYDEFERREDTASK,OTSCHEDULEDEFERREDTASK,OTRunDeferredTaskProc,OTCANMAKESYNCCALL,OTSCHEDULEINTERRUPTTASK,OTEnterSynchronousSection,OTLeaveSynchronousSection",
		"OTClnt$LclClnt:1=OTIDLE,OTDELAY,OTDestroyOpenMessage,OTCreateOpenMessage,OTNEXTOPTION,OTFINDOPTION,OTOpenProviderPriv,OTAsyncOpenProviderPriv,OTCloseProviderPriv,OTSETNONBLOCKING,OTSETBLOCKING,OTISBLOCKING,OTSETSYNCHRONOUS,OTSETASYNCHRONOUS,OTISSYNCHRONOUS,OTDONTACKSENDS,OTACKSENDS,OTISACKINGSENDS,OTCANCELSYNCHRONOUSCALLS,OTINSTALLNOTIFIER,OTREMOVENOTIFIER,OTIOCTL,OTOpenMapperPriv,OTAsyncOpenMapperPriv,OTREGISTERNAME,OTDELETENAME,OTDELETENAMEBYID,OTLOOKUPNAME,OTOpenEndpointPriv,OTAsyncOpenEndpointPriv,OTGETENDPOINTINFO,OTGETPROTADDRESS,OTRESOLVEADDRESS,OTGETENDPOINTSTATE,OTAllocPriv,OTFREE,OTLOOK,OTSYNC,OTCOUNTDATABYTES,OTOPTIONMANAGEMENT,OTBIND,OTUNBIND,OTCONNECT,OTRCVCONNECT,OTLISTEN,OTACCEPT,OTSNDDISCONNECT,OTSNDORDERLYDISCONNECT,OTRCVDISCONNECT,OTRCVORDERLYDISCONNECT,OTSND,OTRCV,OTSNDUDATA,OTRCVUDATA,OTRCVUDERR,OTSNDREQUEST,OTRCVREPLY,OTSNDREPLY,OTRCVREQUEST,OTCANCELREQUEST,OTCANCELREPLY,OTSNDUREQUEST,OTRCVUREPLY,OTSNDUREPLY,OTRCVUREQUEST,OTCANCELUREQUEST,OTCANCELUREPLY,OTGETMESSAGE,OTGETPRIORITYMESSAGE,OTPUTMESSAGE,OTPUTPRIORITYMESSAGE,OTGETPROVIDERPORTREF,OTTransferOwnershipPriv,OTUSESYNCIDLEEVENTS,OTENTERNOTIFIER,OTLEAVENOTIFIER",
		"ot:inet$LclInetClnt:1=OTAsyncOpenInternetServicesPriv,OTOpenInternetServicesPriv,OTINETSTRINGTOADDRESS,OTINETADDRESSTONAME,OTINETSYSINFO,OTINETMAILEXCHANGE,OTINITINETADDRESS,OTINETSTRINGTOHOST,OTINETHOSTTOSTRING,OTINETGETINTERFACEINFO,OTINITDNSADDRESS,OTINETQUERY,OTINETGETSECONDARYADDRESSES,OTINETGETDHCPCONFIGINFO",
		"OTClnt$NtvUtils:25=OTCREATETIMERTASK,OTSCHEDULETIMERTASK,OTCANCELTIMERTASK,OTDESTROYTIMERTASK",
		"!$fset:19=PushException,PopException,Fail",
	};
	/** Function name by "set/index". */
	private static final Map<String,String> NAMES=new HashMap<String,String>();
	static{
		for(String set:FUNCTION_SETS){
			int colon=set.lastIndexOf(':',set.indexOf('='));String id=set.substring(0,colon);int index=0;
			for(String item:set.substring(colon+1).split(",")){int eq=item.indexOf('=');if(eq>0){index=Integer.parseInt(item.substring(0,eq));item=item.substring(eq+1);}NAMES.put(id+"/"+index,item);index++;}
		}
	}
	/** Pascal signatures of set members the engine does not implement: they are answered kOTNotSupportedErr (never called by programs we know). */
	private static final Map<String,String> UNIMPLEMENTED=Map.of("OTSCANPORTS","v:","OTDUMMYUTILPROC","v:","OTCREATEPROTOCOLINFO","4:444","OTDESTROYPROTOCOLINFO","v:4",
		"OTGETOPTIONINFOBYNAME","4:44","OTGETOPTIONINFOBYID","4:444","OTGETPROTOCOLLEVEL","4:44","OTGETOPTIONS","4:44","OTGETOPTIONSTRING","4:4444","OTHASOPTIONINFO","B:44");

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final OpenTransport ot;
	private final Map<String,Integer> gates=new HashMap<String,Integer>();

	private OpenTransport68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;ot=tb.openTransport();}

	/**
	 * Puts the ASLM globals in place (ExpandMem+$10C) so the glue finds a loaded library manager, and makes the 68K
	 * processor the engine's caller of notifiers and tasks (unless a PowerPC program's runtime already made itself the caller).
	 */
	static void install(Toolbox68k tb,int expandMem) throws M68kCpuException {
		if(!OpenTransport.ENABLED)return;
		OpenTransport68k b=new OpenTransport68k(tb);
		b.ot.setDefaultGuest(b.guest());
		M68kMemory memory=tb.memory;
		int returnZero=tb.registerGate(cpu->cpu.d[0]=0);
		int create=tb.registerGate(b::createManager);
		int dispatch=tb.registerGate(b::dispatch);
		int vtable=memory.allocate(0x200,4);
		for(int i=0;i<0x80;i++)memory.writeLong(vtable+i*4,returnZero);
		memory.writeLong(vtable+0x9c,create); // the root manager: makes the client's library manager (here the same object)
		b.manager=memory.allocate(64,4);memory.fill(b.manager,64,0);memory.writeLong(b.manager,vtable);
		int globals=memory.allocate(0x100,4);memory.fill(globals,0x100,0);
		memory.writeLong(globals+0x10,b.manager);
		memory.writeLong(globals+0x64,returnZero);
		memory.writeLong(globals+0x88,dispatch);
		memory.writeLong(expandMem+0x10c,globals);
	}
	private int manager;

	/** Root manager +$9C (this, &clientManager, jmp_buf, ...): the client's library manager is the same object. */
	private void createManager(M68kCpu cpu) throws M68kException {
		int out=memory.readLong(cpu.a[7]+4);
		if(out!=0)memory.writeLong(out,manager);
		cpu.d[0]=0;
	}

	/** __SLM11FuncDispatch: A0 = the stub record. Binds it (its cached address becomes the function's gate) and runs the function. */
	private void dispatch(M68kCpu cpu) throws M68kException {
		int stub=cpu.a[0];
		int cvr=memory.readLong(stub+4),index=memory.readWord(stub+12);
		String set=cvr==0?"?":new String(memory.readCString(cvr+16,64),java.nio.charset.StandardCharsets.ISO_8859_1);
		String name=NAMES.get(set+"/"+index);
		if(OpenTransport.TRACE)System.err.println("[ot] 68K bind "+set+" #"+index+" = "+name);
		if(name==null)throw new M68kMacApiException(cpu.pc,0,"Open Transport function "+index+" of the function set \""+set+"\" is unknown");
		int gate=gate(name);
		memory.writeLong(stub,gate);
		runners.get(gate).call(cpu);
	}
	private final Map<Integer,Toolbox68k.Gate> runners=new HashMap<Integer,Toolbox68k.Gate>();

	/**
	 * A HyperCard external built as a CodeWarrior code resource with A4 globals and Apple's A5-relative Open Transport glue
	 * linked in (Rinaldi's GetHTTP 1.2): the linker filled the glue's stub records in among the resource's own globals, at
	 * A4+d, but the glue's stubs still address them as d(A5) - in an external that is HyperCard's A5 world, where a Mac read
	 * HyperCard's jump table (and the A5+0 stub the QuickDraw globals pointer) as the bound address and jumped into it.
	 * Each such stub ({@code LEA d(A5),A0; MOVE.L (A0),D0; BEQ.S; MOVEA.L D0,A0; JMP (A0); JMP dispatch}) whose A4+d holds a
	 * stub record of a known function set is made to address that record ({@code LEA d(A4),A0}). The glue's other globals stay
	 * A5-relative; they are its own and lie in the external's zeroed A5 world. Returns how many stubs were changed.
	 */
	static int linkA4Stubs(byte[] code){
		int a4=a4Globals(code);if(a4<0)return 0;
		int changed=0;
		for(int i=0;i+14<=code.length;i+=2){
			if(word(code,i)!=0x41ed||word(code,i+4)!=0x2010||word(code,i+6)!=0x6704||word(code,i+8)!=0x2040||word(code,i+10)!=0x4ed0)continue;
			if(word(code,i+12)!=0x4efa&&word(code,i+12)!=0x4ef9)continue;
			int record=a4+(short)word(code,i+2);
			if(record<0||record+14>code.length)continue;
			int cvr=(word(code,record+4)<<16)|word(code,record+6),index=word(code,record+12);
			if(cvr<0||cvr+16>=code.length)continue;
			StringBuilder set=new StringBuilder();for(int k=cvr+16;k<code.length&&code[k]!=0&&set.length()<64;k++)set.append((char)(code[k]&0xff));
			if(!NAMES.containsKey(set+"/"+index))continue;
			code[i+1]=(byte)0xec;changed++;
		}
		return changed;
	}
	/** Where a CodeWarrior code resource's A4 globals start (its SetUpA4: {@code LEA start(PC),A0; ADDA.L #n,A0; MOVE.L A0,D0;
	 *  _StripAddress; EXG D0,A4; RTS}), or -1. */
	private static int a4Globals(byte[] code){
		for(int i=0;i+18<=code.length&&i<0x400;i+=2){
			if(word(code,i)!=0x41fa||word(code,i+4)!=0xd1fc||word(code,i+10)!=0x2008||word(code,i+12)!=0xa055||word(code,i+14)!=0xc18c||word(code,i+16)!=0x4e75)continue;
			int start=i+2+(short)word(code,i+2),offset=(word(code,i+6)<<16)|word(code,i+8);
			int a4=start+offset;
			return a4>=0&&a4<code.length?a4:-1;
		}
		return -1;
	}
	private static int word(byte[] b,int i){return (b[i]&0xff)<<8|(b[i+1]&0xff);}

	/** The gate running {@code name} with its calling convention. */
	private int gate(final String name){
		Integer g=gates.get(name);if(g!=null)return g;
		final boolean pascal=name.equals(name.toUpperCase(java.util.Locale.ROOT));
		OpenTransport.Fn fn=ot.function(name);
		Toolbox68k.Gate run;
		if(fn==null&&(name.equals("PushException")||name.equals("PopException")))run=cpu->cpu.d[0]=0;
		else if(fn==null){
			String sig=UNIMPLEMENTED.get(name);
			if(sig==null&&pascal)run=cpu->{throw new M68kMacApiException(cpu.pc,0,"Open Transport function "+name+" is not implemented for 68K programs");};
			else{final OpenTransport.Fn stand=new OpenTransport.Fn(name,sig==null?"4:":sig,a->OpenTransport.NOT_SUPPORTED);
				run=cpu->{System.err.println("[ot] 68K Open Transport function "+name+" is not implemented: kOTNotSupportedErr");call(cpu,stand,pascal);};}
		}else run=cpu->call(cpu,fn,pascal);
		int address=tb.registerGate(run);
		gates.put(name,address);runners.put(address,run);
		return address;
	}

	/** Runs {@code fn} with the arguments on the 68K stack (the return address is already popped). */
	private void call(M68kCpu cpu,OpenTransport.Fn fn,boolean pascal) throws M68kException {
		int sp=cpu.a[7];int n=fn.args.length();int[] a=new int[n];
		if(pascal){
			int total=0;for(int i=0;i<n;i++)total+=fn.args.charAt(i)=='4'?4:2;
			int off=0;
			for(int i=n-1;i>=0;i--){
				char c=fn.args.charAt(i);
				if(c=='4'){a[i]=memory.readLong(sp+off);off+=4;}
				else if(c=='2'){a[i]=memory.readWord(sp+off);off+=2;}
				else{a[i]=memory.readByte(sp+off);off+=2;}
			}
			int r=fn.impl.call(a);
			int slot=sp+total;
			switch(fn.result){
				case '4':memory.writeLong(slot,r);break;
				case '2':memory.writeWord(slot,r);break;
				case 'B':memory.writeWord(slot,r!=0?0x0100:0);break;
				default:break;
			}
			cpu.a[7]=sp+total;
			if(fn.result!='v')cpu.d[0]=fn.result=='B'?(r!=0?1:0):r;
		}else{
			for(int i=0;i<n;i++)a[i]=memory.readLong(sp+4*i);
			int r=fn.impl.call(a);
			cpu.d[0]=fn.result=='B'?(r!=0?1:0):fn.result=='2'?(short)r:r;
			// a pointer result in A0 as well: CodeWarrior's 68K C takes pointers from A0 (MPW's from D0), and its callers of the
			// glue's OTAllocMem (GetHTTP's receive buffer) read A0 - which held the stub record, so the data went over the globals
			if(fn.result=='4')cpu.a[0]=r;
		}
		if(OpenTransport.TRACE&&!fn.name.startsWith("OTMem")&&!fn.name.startsWith("OTAtomic")&&!fn.name.startsWith("OTStr"))System.err.println("[ot] 68K "+fn.name+" -> "+cpu.d[0]);
	}

	/** Notifiers (pascal void (void *context, OTEventCode, OTResult, void *cookie)), task routines and list searches for 68K code. */
	private OpenTransport.Guest guest(){
		return new OpenTransport.Guest(){
			@Override public void notify(int proc,int context,int code,int result,int cookie) throws M68kException {
				M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
				try{cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushLong(context);cpu.pushLong(code);cpu.pushLong(result);cpu.pushLong(cookie);cpu.callPascal(proc,16);}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
			}
			@Override public void process(int proc,int arg) throws M68kException {
				M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
				try{cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushLong(arg);cpu.callPascal(proc,4);}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
			}
			@Override public boolean listSearch(int proc,int ref,int link) throws M68kException {
				M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
				try{cpu.pushLong(link);cpu.pushLong(ref);cpu.call(proc);return (cpu.d[0]&0xff)!=0;}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
			}
			@Override public String cpuType(){return "68040";}
		};
	}
}

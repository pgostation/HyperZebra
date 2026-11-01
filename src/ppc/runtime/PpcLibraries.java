import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * The system-library functions a PowerPC program imports that are not a 68K trap with inline words: what the 68K
 * libraries implemented as glue (File Manager shortcuts on parameter blocks, C-string variants, string comparison,
 * low-memory accessors), the PowerPC-only managers (Code Fragment Manager, Mixed Mode) and the C libraries (MathLib,
 * StdCLib). Everything else is bridged from the Universal Interfaces declarations by {@link PpcRuntime}.
 */
final class PpcLibraries {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	private static final Charset MAC_ROMAN=Charset.forName("MacRoman");
	/** Functions by "Library/name" (library-specific) and by name (any library). */
	private final Map<String,PpcRuntime.Native> functions=new HashMap<String,PpcRuntime.Native>();
	private final Map<String,Integer> dataSymbols=new HashMap<String,Integer>();
	/** CarbonLib's additions (accessors, Carbon events). */
	PpcCarbon carbon;PpcTextServices textServices;PpcTextension textension;
	/** CoreFoundation (CFType objects, collections, bundles, preferences, property lists). */
	PpcCoreFoundation coreFoundation;
	PpcRave rave;
	/** QuickDraw 3D 1.6 ("QuickDraw™ 3D"), when served natively. */
	PpcQuickDraw3D qd3d;
	PpcOpenGL openGL;
	/** Data items the helper classes provide, made when a program first imports them (address 0 = not made). */
	interface DataSymbol { int address() throws M68kCpuException; }
	private final Map<String,DataSymbol> dataProviders=new HashMap<String,DataSymbol>();
	/** Registration of a data symbol for the helper classes: {@code provider} runs once, at the first import. */
	void registerData(String name,DataSymbol provider){dataProviders.put(name,provider);}
	/** Tests (tests/PpcCarbonHarness): the native routine a program's import of CarbonLib "HZSelfTest" binds to. */
	static volatile PpcRuntime.Native selfTest;

	/**
	 * 68K glue written as inline words, in the {@link PpcInterfaceTable} line format, for InterfaceLib functions that the
	 * headers declare without any (their 68K versions were library code): register moves around the trap.
	 */
	static final String[] MANUAL_GLUE={
		"NumToString P v 4,4 :D0,A0 4267,A9EE",                  // CLR.W -(SP); _Pack7 (selector 0)
		"StringToNum P v 4,4 :A0,A1 3F3C,0001,A9EE,2280",        // MOVE.W #1,-(SP); _Pack7; MOVE.L D0,(A1)
		"GetHandleSize P 4 4 D0:A0 A025,4A80,6A02,7000",          // _GetHandleSize; errors (negative D0) give 0
		"GetPtrSize P 4 4 D0:A0 A021,4A80,6A02,7000",
		"HandToHand P 2 4 D0:A1 2051,A9E1,2288",                 // MOVEA.L (A1),A0; _HandToHand; MOVE.L A0,(A1)
		"PtrToHand P 2 4,4,4 D0:A0,A2,D0 A9E3,2488",             // _PtrToHand; MOVE.L A0,(A2)
		"PurgeSpace P v 4,4 :A1,A2 A162,2280,2488",              // _PurgeSpace; total and contiguous
		"DateToSeconds P v 4,4 :A0,A1 A9C7,2280",                // _Date2Secs; MOVE.L D0,(A1)
		"SecondsToDate P v 4,4 :D0,A0 A9C6",                     // _Secs2Date
		"AppendDITL P v 4,4,2 - 3F3C,0402,204F,A08B",            // the Communications Toolbox DITL calls: selector on the arguments, A0 = SP
		"CountDITL P 2 4 D0: 3F3C,0403,204F,A08B",
		"ShortenDITL P v 4,2 - 3F3C,0404,204F,A08B",
		// GetTrapVector(trap word): WHERE the trap's address is kept - its entry in the Toolbox table at $E00 (bit 11 set, 1024
		// entries) or the OS table at $400 - which the caller reads (A-Dock: lwz r3,0(r3), then CallUniversalProc for
		// ShowHideProcess). MOVE.W D0,D1; BTST #11,D1; BEQ.S os; ANDI.W #$3FF,D1; LSL.W #2,D1; LEA $E00,A0; BRA.S add;
		// os: ANDI.W #$FF,D1; LSL.W #2,D1; LEA $400,A0; add: ADDA.W D1,A0. It answered NIL, and A-Dock's Hide jumped to address 0.
		"GetTrapVector P 4 H A0:D0 3200,0801,000B,670C,0241,03FF,E549,41F8,0E00,600A,0241,00FF,E549,41F8,0400,D0C1",
		"InitZone P v 4,2,4,4 - -",
		"DrvrInstall P 2 4,2 D0:A0,D0 A03D",                      // DrvrInstall(drvrPtr, refNum): _DrvrInstall with A0 = the driver
		"DrvrRemove P 2 2 D0:D0 A03E",
	};

	// Open Transport's notifiers become PowerPC calls only for a PowerPC program: a 68K program's runtime is started by the
	// first PowerPC code its extensions run (QuickDraw 3D's INIT now comes first), and its 68K notifiers were then called as TVectors
	PpcLibraries(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;define();if(rt.app==null||rt.app.powerPC)installOpenTransport();}

	PpcRuntime.Native function(String library,String name){
		if(name.equals("HZSelfTest")&&selfTest!=null)return selfTest;
		PpcRuntime.Native n=openTransportFunction(name);if(n!=null)return n;
		n=functions.get(library+"/"+name);if(n!=null)return n;
		n=functions.get(name);if(n!=null)return n;
		if(library.equals("CarbonLib")){ // CarbonLib carries MathLib and StdCLib too
			n=functions.get("MathLib/"+name);if(n==null)n=functions.get("StdCLib/"+name);if(n!=null)return n;}
		return uppFunction(name);
	}
	/** UPP types the headers declare with TVECTOR_UPP_TYPE (no procInfo): New returns the routine itself - REALbasic's Carbon
	 *  runtime calls NewThreadEntryUPP at start-up, and a NIL result made コプリン jump to address 0. */
	private static final java.util.Set<String> TVECTOR_UPPS=new java.util.HashSet<String>(java.util.Arrays.asList("OTListSearch","OTNotify","OTProcess","URLNotify","URLSystemEvent"));
	/** The Thread Manager's TVECTOR_UPP_TYPE callbacks with the procInfo of their 68K (Pascal) form: ThreadManager68k calls a thread's entry,
	 *  scheduler, switchers and terminator as 68K routines, so a PowerPC program's TVector goes in a routine descriptor (むりやりMusic's
	 *  REALbasic thread ran its TVector's bytes as 68K code). */
	private static final java.util.Map<String,Integer> THREAD_UPPS=java.util.Map.of("ThreadEntry",0xf0,"ThreadScheduler",0xf0,"ThreadSwitch",0x3c0,
		"ThreadTermination",0x3c0,"DebuggerNewThread",0xc0,"DebuggerDisposeThread",0xc0,"DebuggerThreadScheduler",0xf0);
	/** New<X>UPP / Dispose<X>UPP / Invoke<X>UPP for any UPP type the headers give a procInfo for (the Invoke form takes the UPP last). */
	private PpcRuntime.Native uppFunction(String name){
		java.util.regex.Matcher m=java.util.regex.Pattern.compile("(New|Dispose|Invoke)(\\w+)UPP").matcher(name);
		if(!m.matches())return null;
		final Integer threadProcInfo=THREAD_UPPS.get(m.group(2));
		if(threadProcInfo!=null)switch(m.group(1)){ // the Thread Manager here is 68K code: its callbacks need routine descriptors
			case "New":return (r,c)->ret(r.newRoutineDescriptor(arg(0),threadProcInfo,PpcRuntime.ISA_PPC));
			case "Dispose":return (r,c)->{int rd=arg(0);if(rd!=0&&r.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);};
			default:{final int n=argCount(threadProcInfo);return (r,c)->{int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);ret(r.callUniversal(arg(n),threadProcInfo,a));};}
		}
		if(TVECTOR_UPPS.contains(m.group(2)))switch(m.group(1)){ // TVECTOR_UPP_TYPE: on PowerPC the "UPP" is the routine's own TVector
			case "New":return (r,c)->ret(arg(0));
			case "Dispose":return (r,c)->{};
			default:{PpcRuntime.Declaration d=PpcRuntime.declaration(name);final int n=d==null?1:Math.max(0,d.params.length-1);
				return (r,c)->{int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);ret(r.cpu.call(arg(n),a));};}
		}
		final Integer procInfo=PpcRuntime.uppProcInfo(m.group(2));
		if(procInfo==null)return null;
		switch(m.group(1)){
			case "New":return (r,c)->ret(r.newRoutineDescriptor(arg(0),procInfo,PpcRuntime.ISA_PPC));
			case "Dispose":return (r,c)->{int rd=arg(0);if(rd!=0&&r.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);};
			default:{final int n=argCount(procInfo);return (r,c)->{int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);ret(r.callUniversal(arg(n),procInfo,a));};}
		}
	}
	/** A data symbol's address (0 = not known). */
	int data(String library,String name) throws M68kCpuException {
		Integer v=dataSymbols.get(library+"/"+name);if(v==null)v=dataSymbols.get(name);
		if(v!=null)return v;
		DataSymbol provider=dataProviders.get(name);
		if(provider!=null){int p=provider.address();if(p!=0){dataSymbols.put(name,p);return p;}}
		int size;
		switch(name){
			case "pi":{int p=rt.allocate(8,8);memory.writeLong(p,(int)(Double.doubleToRawLongBits(Math.PI)>>>32));memory.writeLong(p+4,(int)Double.doubleToRawLongBits(Math.PI));dataSymbols.put(name,p);return p;}
			// StdCLib's <float.h> limits (DBL_EPSILON = *(double *)_DBL_EPSILON ...); long double is a double-double pair on PowerPC
			case "_DBL_EPSILON":case "_DBL_MAX":case "_DBL_MIN":case "_LDBL_EPSILON":case "_LDBL_MAX":case "_LDBL_MIN":{
				double d=name.endsWith("EPSILON")?(name.startsWith("_L")?0x1p-106:Math.ulp(1.0)):name.endsWith("MAX")?Double.MAX_VALUE:Double.MIN_NORMAL;
				int p=rt.allocate(16,8);long bits=Double.doubleToRawLongBits(d);memory.writeLong(p,(int)(bits>>>32));memory.writeLong(p+4,(int)bits);
				memory.writeLong(p+8,0);memory.writeLong(p+12,0);dataSymbols.put(name,p);return p;}
			case "_FLT_EPSILON":case "_FLT_MAX":case "_FLT_MIN":{
				float f=name.endsWith("EPSILON")?Math.ulp(1.0f):name.endsWith("MAX")?Float.MAX_VALUE:Float.MIN_NORMAL;
				int p=rt.allocate(4,4);memory.writeLong(p,Float.floatToRawIntBits(f));dataSymbols.put(name,p);return p;}
			case "errno":case "_IntEnv":case "__C_phase":case "_exit_status":case "__NubAt3":case "MacOSErr":size=64;break;
			// IntEnv.h's StandAlone: non-zero in an application (MPW tools run under the Shell see 0); MPW Shell 3.5 imports it
			case "StandAlone":{int p=rt.allocate(4,4);memory.writeLong(p,1);dataSymbols.put(name,p);return p;}
			// MixedMode's pointer to the 68K emulator's registers (PpcRuntime.emulatorContext): MCL 4's kernel quits with "Can't
			// determine 68K emulated context address" when the variable is missing or holds 0
			case "Emulated68KContext":{int block=rt.allocate(128,8);memory.fill(block,128,0);rt.emulatorContext=block;rt.syncEmulatorContext();
				int p=rt.allocate(4,4);memory.writeLong(p,block);dataSymbols.put(name,p);return p;}
			// a jmp_buf (MPW's PowerPC jmp_buf is 64 longs; PpcStdCLib.setjmp fills 244 bytes): the runtime's start-up code does
			// __setjmp(__target_for_exit) - at 64 bytes it ran over the next block (RSB's strlen transition vector)
			case "__target_for_exit":size=256;break;
			case "_iob":size=PpcStdio.NFILE*PpcStdio.FILE_SIZE;break; // MPW stdio.h: FILE _iob[_NFILE]
			case "_ctype":case "__ctype":{int p=ctypeTable();dataSymbols.put(name,p);return p;}
			// MPW's ctype.h: "extern const char *__p_CType" - a pointer variable to the table its macros index (isalpha(c) =
			// __p_CType[(unsigned char)c] & (_UPP|_LOW)), with _BLA $40 and _HEX $80. MPW Shell 3.5's PowerPC code took the
			// table's first bytes for the pointer and found none of its built-in commands.
			case "__p_CType":{int table=ctypeTable();for(int ch=0;ch<256;ch++){int b=memory.readByte(table+ch);memory.writeByte(table+ch,(b&0x3f)|((b&0x40)<<1)|((b&0x80)>>1));}
				int p=rt.allocate(4,4);memory.writeLong(p,table);dataSymbols.put(name,p);return p;}
			case "CallComponentUPP":{ // a UniversalProcPtr variable: CallUniversalProc(CallComponentUPP, uppCallComponentProcInfo, &params)
				int tv=rt.nativeVector("CallComponentUPP",(r,c)->{ // params: flags, paramSize, what, the arguments, the instance - as on the 68K stack
					int pb=arg(0);int paramSize=memory.readByte(pb+1);
					ret(r.runOnStack(new int[]{0x7000,0xa82a},pb,4+paramSize+4,4));}); // MOVEQ #0,D0; _ComponentDispatch
				int rd=rt.newRoutineDescriptor(tv,0x000000f0,PpcRuntime.ISA_PPC);
				int p=rt.allocate(4,4);memory.writeLong(p,rd);dataSymbols.put(name,p);return p;}
			default:return 0;
		}
		int p=rt.allocate(size,8);dataSymbols.put(library+"/"+name,p);return p;
	}

	/** Routine descriptors made for bare TVectors handed to the 68K Thread Manager, one per (TVector, procInfo). */
	private final Map<Long,Integer> threadDescriptors=new HashMap<Long,Integer>();
	/** A ThreadsLib function whose callback arguments ({@code callbacks} = position, procInfo pairs) may be bare TVectors. */
	private void threadGlue(final String name,final int n,final int... callbacks){
		def(name,(r,c)->{
			int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);
			for(int k=0;k<callbacks.length;k+=2){
				int at=callbacks[k],procInfo=callbacks[k+1],tv=a[at];
				if(tv==0||r.isRoutineDescriptor(tv))continue;
				long key=((long)tv<<32)|(procInfo&0xffffffffL);
				Integer rd=threadDescriptors.get(key);
				if(rd==null){rd=r.newRoutineDescriptor(tv,procInfo,PpcRuntime.ISA_PPC);threadDescriptors.put(key,rd);}
				a[at]=rd;
			}
			ret(r.toolbox(name,a));
		});
	}
	/** Calls one of these natives from Java with up to 8 arguments (r3-r10), as PowerPC code would; answers r3. */
	int invoke(String name,int... args) throws M68kException {
		PpcRuntime.Native n=functions.get(name);if(n==null)throw new M68kCpuException(0,"no native "+name);
		PpcCpu c=rt.cpu;int[] saved=new int[8];for(int i=0;i<8;i++){saved[i]=c.r[3+i];c.r[3+i]=i<args.length?args[i]:0;}
		try{n.call(rt,c);return c.r[3];}finally{int result=c.r[3];for(int i=0;i<8;i++)c.r[3+i]=saved[i];c.r[3]=saved[0];}
	}
	private void def(String name,PpcRuntime.Native n){functions.put(name,n);}
	private void def(String library,String name,PpcRuntime.Native n){functions.put(library+"/"+name,n);}
	/** A Device Manager Control ($A004) or Status ($A005) call on serial driver {@code refNum} with {@code csParam}; {@code outLen} bytes of csParam go to {@code out} afterwards. */
	private int serialCall(int trap,int refNum,int csCode,byte[] csParam,int out,int outLen) throws M68kException {
		if(!rt.toolbox.files.serialDriverOpen((short)refNum))return -28; // notOpenErr
		int pb=rt.allocate(80,4);
		try{
			memory.writeWord(pb+24,refNum);memory.writeWord(pb+26,csCode);memory.load(pb+28,csParam,0,csParam.length);
			int result=(short)rt.trap(trap,0,pb,0);
			if(result==0&&out!=0)memory.copy(pb+28,out,outLen);
			return result;
		}finally{memory.free(pb);}
	}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private int deref(int handle) throws M68kCpuException {return handle==0?0:memory.readLong(handle);}
	/** kQTMacGamma-like platform level (1.8) a PixMap reports until a program sets one. */
	private static final int MAC_GAMMA=0x0001CCCC;
	private final java.util.Map<Integer,Integer> pixMapGamma=new java.util.HashMap<>(),pixMapRequestedGamma=new java.util.HashMap<>();

	// ------------------------------------------------------------------ Open Transport

	/** Mixed Mode descriptions of OT's callbacks: notifier (pascal void (void*, OTEventCode, OTResult, void*)), OTProcessProcPtr,
	 *  OTListSearchProcPtr (C, Boolean result). */
	private static final int OT_NOTIFY_PROC_INFO=0x3fc0,OT_PROCESS_PROC_INFO=0xc0,OT_LIST_SEARCH_PROC_INFO=0x3d1;
	/** The engine calls a PowerPC program's notifiers and tasks through their TVectors (routine descriptors through Mixed Mode). */
	private void installOpenTransport(){
		rt.toolbox.openTransport().setGuest(new OpenTransport.Guest(){
			@Override public void notify(int proc,int context,int code,int result,int cookie) throws M68kException {call(proc,OT_NOTIFY_PROC_INFO,context,code,result,cookie);}
			@Override public void process(int proc,int arg) throws M68kException {call(proc,OT_PROCESS_PROC_INFO,arg);}
			@Override public boolean listSearch(int proc,int ref,int link) throws M68kException {return (call(proc,OT_LIST_SEARCH_PROC_INFO,ref,link)&0xff)!=0;}
			@Override public String cpuType(){return "POWERPC";}
			private int call(int proc,int procInfo,int... args) throws M68kException {return rt.isRoutineDescriptor(proc)?rt.callUniversal(proc,procInfo,args):rt.cpu.call(proc,args);}
		});
	}
	private final Map<String,PpcRuntime.Native> otNatives=new HashMap<String,PpcRuntime.Native>();
	/** The Open Transport function {@code name} (OTClientLib, OTInetClientLib, OTUtilityLib, OTClientUtilLib, OTNtvUtilLib,
	 *  CarbonLib and its "Apple;Carbon;Networking" alias all export the same names), or null. */
	private PpcRuntime.Native openTransportFunction(String name){
		if(!name.startsWith("OT")&&!name.contains("OpenTransport"))return null;
		PpcRuntime.Native n=otNatives.get(name);if(n!=null)return n;
		final OpenTransport.Fn fn=rt.toolbox.openTransport().function(name);
		if(fn==null)return null;
		final int count=fn.args.length();
		n=(r,c)->{
			int[] a=new int[count];
			for(int i=0;i<count;i++){int v=arg(i);char k=fn.args.charAt(i);a[i]=k=='1'?v&0xff:k=='2'?v&0xffff:v;}
			int result=fn.impl.call(a);
			if(OpenTransport.TRACE&&!name.startsWith("OTMem")&&!name.startsWith("OTAtomic")&&!name.startsWith("OTStr")&&!name.startsWith("OTGetTimeStamp")&&!name.startsWith("OTElapsed"))System.err.println("[ot] PPC "+name+java.util.Arrays.toString(a)+" -> "+result);
			if(fn.result=='B')ret(result!=0?1:0);else if(fn.result!='v')ret(result);
		};
		otNatives.put(name,n);return n;
	}

	// ------------------------------------------------------------------ helpers

	/** A parameter block of {@code size} bytes carved from the 68K stack for {@code body}; the stack is restored afterwards. */
	private interface PbBody { int run(int pb) throws M68kException; }
	private int withPb(int size,PbBody body) throws M68kException {
		int sp=rt.cpu68.a[7];int pb=(sp-size-16)&~3;rt.cpu68.a[7]=pb-16;memory.fill(pb,size,0);
		try{return body.run(pb);}finally{rt.cpu68.a[7]=sp;}
	}
	/** Runs an OS trap with A0 = the parameter block; returns the OSErr. */
	private int pbTrap(int word,int pb) throws M68kException {return (short)rt.trap(word,0,pb,0);}
	private int pbTrap(int word,int d0,int pb) throws M68kException {return (short)rt.trap(word,d0,pb,0);}
	/** A copy of the C string at {@code p} as a Pascal string at {@code to} (255 bytes at most); returns {@code to}. */
	private int cToPascalAt(int p,int to) throws M68kCpuException {
		int n=0;if(p!=0)while(n<255&&memory.readByte(p+n)!=0){memory.writeByte(to+1+n,memory.readByte(p+n));n++;}
		memory.writeByte(to,n);return to;
	}
	/** C string at {@code p} to a Pascal string in place (returns p). */
	private int c2p(int p) throws M68kCpuException {
		if(p==0)return 0;
		int n=0;while(n<255&&memory.readByte(p+n)!=0)n++;
		for(int i=n;i>0;i--)memory.writeByte(p+i,memory.readByte(p+i-1));
		memory.writeByte(p,n);return p;
	}
	/** Pascal string at {@code p} to a C string in place (returns p). */
	private int p2c(int p) throws M68kCpuException {
		if(p==0)return 0;
		int n=memory.readByte(p);
		for(int i=0;i<n;i++)memory.writeByte(p+i,memory.readByte(p+i+1));
		memory.writeByte(p+n,0);return p;
	}
	/** FSSpec of a host file into guest memory at {@code spec} (70 bytes). */
	void makeFSSpec(File file,int spec) throws M68kCpuException {
		try{
			FileManager68k fm=rt.toolbox.files.fileManager;
			File parent=file.getAbsoluteFile().getParentFile();
			memory.writeWord(spec,fm.volumeRefNumFor(parent));memory.writeLong(spec+2,fm.directoryIdFor(parent));
			rt.toolbox.writePascal(spec+6,fm.macNameOf(file),63);
		}catch(IOException e){throw new M68kCpuException(spec,"FSSpec of "+file+": "+e.getMessage());}
	}
	/** The host file an FSSpec names, or null. */
	File fileOf(int spec) throws M68kCpuException {
		try{
			// as the File Manager resolves it: a partial pathname in the name (Ray Dream Studio loads its extensions with
			// GetDiskFragment of (vRefNum, the extensions folder's dirID, ":CObject")), Mac names of host files
			File resolved=rt.toolbox.files.fileForSpec(spec);
			if(resolved!=null&&resolved.exists())return resolved;
			FileManager68k fm=rt.toolbox.files.fileManager;
			File dir=fm.directoryForId(memory.readLong(spec+2),(short)memory.readWord(spec));
			if(dir==null)return null;
			String name=rt.toolbox.readPascal(spec+6);
			File f=new File(dir,name.replace('/',':'));
			if(!f.exists())f=new File(dir,name);
			return f;
		}catch(IOException e){return null;}
	}

	// ------------------------------------------------------------------ definitions

	private void define(){
		// PPC Toolbox (program-to-program communication): no user is logged on to program linking; Netscape Communicator asks at start-up
		// CanQuickTimeOpenFile(spec, fileType, extension, *gi, *movie, *preferGi, flags) (QuickTime 4): the headers of the Universal Interfaces
		// used here lack it, so a program (Germs) that finds QuickTime present called an unresolved import (address 0)
		def("CanQuickTimeOpenFile",(r,c)->{
			File f=arg(0)==0?null:fileOf(arg(0));int type=arg(1),ext=arg(2);byte[] head=null;
			if(f!=null&&f.isFile())try(java.io.InputStream in=new java.io.FileInputStream(f)){head=in.readNBytes(1024);}catch(java.io.IOException e){}
			int sub=GraphicsImporter68k.subTypeOf(head,f==null?null:f.getName());
			String n=f==null?"":f.getName().toLowerCase();
			boolean movie=type==0x4d6f6f56||ext==0x6d6f6f76||n.endsWith(".mov")||n.endsWith(".qt");
			if(arg(3)!=0)memory.writeByte(arg(3),sub!=0?1:0);
			if(arg(4)!=0)memory.writeByte(arg(4),movie?1:0);
			if(arg(5)!=0)memory.writeByte(arg(5),sub!=0?1:0);
			ret(0);
		});
		// PixMap gamma levels (QuickTime 4.1+, not in the Universal Interfaces used here): Final Cut Pro weak-imports them and
		// reads their absence as "QuickTime too old". A level is remembered per PixMap; nothing is gamma-corrected
		def("QTGetPixMapPtrGammaLevel",(r,c)->ret(pixMapGamma.getOrDefault(arg(0),MAC_GAMMA)));
		def("QTGetPixMapHandleGammaLevel",(r,c)->ret(pixMapGamma.getOrDefault(deref(arg(0)),MAC_GAMMA)));
		def("QTSetPixMapPtrGammaLevel",(r,c)->{pixMapGamma.put(arg(0),arg(1));ret(0);});
		def("QTSetPixMapHandleGammaLevel",(r,c)->{pixMapGamma.put(deref(arg(0)),arg(1));ret(0);});
		// SndDispVersion(selector): InterfaceLib exports it, the 3.x headers no longer declare it (no glue in the generated table).
		// BigEye calls it with 4 at start-up and jumped to address 0. The version of the Sound Manager (SndSoundManagerVersion's).
		def("SndDispVersion",(r,c)->ret(0x03648000));
		def("QTGetPixMapPtrRequestedGammaLevel",(r,c)->ret(pixMapRequestedGamma.getOrDefault(arg(0),0)));
		def("QTGetPixMapHandleRequestedGammaLevel",(r,c)->ret(pixMapRequestedGamma.getOrDefault(deref(arg(0)),0)));
		def("QTSetPixMapPtrRequestedGammaLevel",(r,c)->{pixMapRequestedGamma.put(arg(0),arg(1));ret(0);});
		def("QTSetPixMapHandleRequestedGammaLevel",(r,c)->{pixMapRequestedGamma.put(deref(arg(0)),arg(1));ret(0);});
		// QuickTime 5 calls (Final Cut Pro 2.0 weak-imports them once Gestalt says 5.0): no video output components, sample caches or
		// preferred-codec lists exist here; codecs report no latency information
		def("SetMovieVideoOutput",(r,c)->ret(0));
		def("MediaSetUserPreferredCodecs",(r,c)->ret(0));
		def("MediaEmptySampleCache",(r,c)->ret(0));
		def("ImageCodecGetDecompressLatency",(r,c)->ret(-32766)); // badComponentSelector, as for a codec without the call
		def("AddClonedTrackToMovie",(r,c)->{if(arg(3)!=0)memory.writeLong(arg(3),0);ret(-4);}); // unimpErr
		def("GetLogicalPageSize",(r,c)->ret(4096)); // DriverServicesLib
		// The Communications Resource Manager is CommToolbox68k's (its glue: the Modem Port and Printer Port are registered). A bare
		// CRMSearch(0) stub stood here for Final Cut Pro; Newton Toolkit 1.6.4 looks for a serial port at start-up and stopped at
		// "Could not start the application because of some unknown error" when none was found.
		// Serial.h glue (InterfaceLib makes Control/Status calls): an open port is the 68K serial driver's (nothing connected:
		// every call succeeds), an unopened one notOpenErr (Final Cut Pro asks ports it never opened). Pretzel 3.0b4 opens
		// .AIn/.AOut and stopped at "Unexpected error, ID = -28" from SerReset when these always answered notOpenErr.
		def("SerReset",(r,c)->ret(serialCall(0xa004,arg(0),8,new byte[]{(byte)(arg(1)>>>8),(byte)arg(1)},0,0)));
		def("SerSetBuf",(r,c)->{int p=arg(1),n=arg(2);ret(serialCall(0xa004,arg(0),9,new byte[]{(byte)(p>>>24),(byte)(p>>>16),(byte)(p>>>8),(byte)p,(byte)(n>>>8),(byte)n},0,0));});
		def("SerHShake",(r,c)->ret(serialCall(0xa004,arg(0),10,arg(1)==0?new byte[8]:memory.readBytes(arg(1),8),0,0)));
		def("SerClrBrk",(r,c)->ret(serialCall(0xa004,arg(0),11,new byte[0],0,0)));
		def("SerSetBrk",(r,c)->ret(serialCall(0xa004,arg(0),12,new byte[0],0,0)));
		def("SerGetBuf",(r,c)->ret(serialCall(0xa005,arg(0),2,new byte[0],arg(1),4)));
		def("SerStatus",(r,c)->ret(serialCall(0xa005,arg(0),8,new byte[0],arg(1),6)));
		def("GetDefaultUser",(r,c)->ret(-922)); // noDefaultUserErr
		// DriverLoaderLib (Mac OS 8.6+): programs weak-import it to tell the OS version (Heavy Metal F.A.K.K.2 demo); no driver is described
		def("GetDriverInformation",(r,c)->ret(-56)); // nsDrvErr
		defineMixedMode();defineCfm();defineMemoryAndStrings();defineFiles();defineLowMem();defineMisc();defineCStringGlue();defineAppleTalk();
		PpcMathLib.define(this);PpcStdCLib.define(this);carbon=new PpcCarbon(rt);carbon.define(this);textServices=new PpcTextServices(rt);textServices.define(this);textension=new PpcTextension(rt);textension.define(this);new PpcNibs(rt).define(this);
		// CarbonLib's other managers (after the above: a later registration of the same name wins)
		coreFoundation=new PpcCoreFoundation(rt);coreFoundation.define(this);
		new PpcNavServices(rt).define(this);new PpcPrinting(rt).define(this);new PpcDataBrowser(rt).define(this);
		new PpcCarbonUtilities(rt).define(this);new PpcCarbonToolbox(rt).define(this);new PpcMultiprocessing(rt).define(this);
		new PpcAtsui(rt).define(this);new PpcAppearance(rt).define(this);new PpcUrlAccess(rt).define(this);new PpcNameRegistry(rt).define(this);
		new PpcAppleEventHelpers(rt).define(this);new PpcHtmlRendering(rt).define(this);new PpcQuickDrawExtras(rt).define(this); // CarbonLib 1.6 additions
		new PpcGameSprockets(rt).define(this); // DrawSprocket, InputSprocket, SoundSprocket
		rave=new PpcRave(rt);rave.define(this); // QuickDraw 3D RAVE (the "QuickDraw™ 3D Accelerator" library)
		qd3d=new PpcQuickDraw3D(rt);qd3d.define(this); // QuickDraw 3D 1.6 itself (the "QuickDraw™ 3D" library)
		openGL=new PpcOpenGL(rt);openGL.define(this); // Apple OpenGL 1.2.1 (OpenGLLibrary, OpenGLUtility, OpenGLMemory)
		defineFindByContent();
		// the Window Manager's private layer calls (PrivateInterfaceLib, _LayerDispatch $A829 on 68K): one layer per process,
		// the program's window list its sub-windows. Audion 3 walks it (GetCurLayer was unresolved: a jump to 0)
		def("GetCurLayer",(r,c)->ret(r.runOnStack(new int[]{0x7003,0xa829},0,0,4)));
		def("GetParent",(r,c)->ret(r.runOnStack(new int[]{0x7003,0xa829},0,0,4)));
		def("SwapCurLayer",(r,c)->ret(r.runOnStack(new int[]{0x7003,0xa829},0,0,4)));
		def("SetCurLayer",(r,c)->{});
		// FragRegisterFileLibs(const FSSpec*, Boolean): an extension registers its own file's 'cfrg' libraries (the PowerPC side
		// of the 68K private selector -20; QuickDraw 3D's Gestalt function imports it weakly and called 0 without it)
		def("FragRegisterFileLibs",(r,c)->{File f=arg(0)==0?null:fileOf(arg(0));
			if(f!=null&&f.isFile()&&!r.registeredLibraryFiles.contains(f))r.registeredLibraryFiles.add(f);ret(f!=null&&f.isFile()?0:-43);});
		def("GetSubWindows",(r,c)->{int b=r.allocate(4,4);try{memory.writeLong(b,arg(0));ret(r.runOnStack(new int[]{0x7006,0xa829},b,4,4));}finally{memory.free(b);}});
	}
	/**
	 * Mac OS 9's "Find by Content" library (FindByContent.h) with no volume indexed: sessions can be made, every search finds
	 * nothing (kFBCnoIndexesFound), indexing is not offered. Sherlock 2 warned at every launch that the library was missing and
	 * searches by name through the File Manager anyway.
	 */
	private void defineFindByContent(){
		final String lib="Find by Content";final int noIndexes=-30501;
		final java.util.Set<Integer> sessions=new java.util.HashSet<Integer>();
		def(lib,"FBCVolumeIsIndexed",(r,c)->ret(0));
		def(lib,"FBCVolumeIsRemote",(r,c)->ret(0));
		def(lib,"FBCVolumeIndexTimeStamp",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(noIndexes);});
		def(lib,"FBCVolumeIndexPhysicalSize",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(noIndexes);});
		def(lib,"FBCSetCallback",(r,c)->ret(0));
		def(lib,"FBCSetHeapReservation",(r,c)->ret(0));
		def(lib,"FBCCreateSearchSession",(r,c)->{int session=r.allocate(16,4);sessions.add(session);if(arg(0)!=0)memory.writeLong(arg(0),session);ret(0);});
		def(lib,"FBCDestroySearchSession",(r,c)->{sessions.remove(arg(0));ret(0);});
		def(lib,"FBCCloneSearchSession",(r,c)->{int session=r.allocate(16,4);sessions.add(session);if(arg(1)!=0)memory.writeLong(arg(1),session);ret(0);});
		for(String n:new String[]{"FBCAddAllVolumesToSession","FBCSetSessionVolumes","FBCAddVolumeToSession","FBCRemoveVolumeFromSession","FBCReleaseSessionHits","FBCSetSessionHitTest"})
			def(lib,n,(r,c)->ret(0));
		def(lib,"FBCGetSessionVolumeCount",(r,c)->{if(arg(1)!=0)memory.writeWord(arg(1),0);ret(0);});
		def(lib,"FBCGetSessionVolumes",(r,c)->{if(arg(2)!=0)memory.writeWord(arg(2),0);ret(0);});
		for(String n:new String[]{"FBCDoQuerySearch","FBCDoQuickQuerySearch","FBCDoExampleSearch","FBCDoQuickExampleSearch","FBCDoCFStringSearch"})
			def(lib,n,(r,c)->ret(noIndexes));
		def(lib,"FBCBlindExampleSearch",(r,c)->ret(noIndexes));
		def(lib,"FBCGetHitCount",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(0);});
		def(lib,"FBCGetHitDocument",(r,c)->ret(-50));
		def(lib,"FBCGetHitScore",(r,c)->{if(arg(2)!=0)memory.writeLong(arg(2),0);ret(-50);});
		def(lib,"FBCGetMatchedWords",(r,c)->ret(-50));
		def(lib,"FBCGetTopicWords",(r,c)->ret(-50));
		def(lib,"FBCIndexItems",(r,c)->ret(noIndexes));
		def(lib,"FBCDeleteIndexFileForVolume",(r,c)->ret(noIndexes));
		def(lib,"DestroyVolumeIndexFile",(r,c)->ret(noIndexes));
		// the private language calls of Mac OS 9's library: no language analysis beyond the default
		def(lib,"IsJapaneseEnabled",(r,c)->ret(0));
		def(lib,"IsKoreanEnabled",(r,c)->ret(0));
		def(lib,"GetLanguageListData",(r,c)->ret(-50));
		def(lib,"AnalyzeLanguageListData",(r,c)->ret(-50));
	}
	/** Registration for the helper classes. */
	void register(String library,String name,PpcRuntime.Native n){if(library==null)def(name,n);else def(library,name,n);}
	PpcRuntime runtime(){return rt;}

	private int argCount(int procInfo){
		int conv=procInfo&15;
		if(conv==2){int n=0;for(int i=0;i<4;i++){if(((procInfo>>>(11+5*i))&31)==0)break;n++;}return n;}
		int first=conv==0||conv==1||conv==5?6:8;int n=conv==0||conv==1||conv==5?0:1;
		for(int bit=first;bit<32;bit+=2){if(((procInfo>>>bit)&3)==0)break;n++;}
		return n;
	}

	private void defineMixedMode(){
		def("NewRoutineDescriptor",(r,c)->{
			if(PpcRuntime.TRACE&&arg(0)!=0&&(arg(2)&0xff)==PpcRuntime.ISA_PPC){StringBuilder sb=new StringBuilder("[ppc]   TVector "+Integer.toHexString(arg(0))+":");int code=memory.readLong(arg(0));
				sb.append(String.format(" code %08X toc %08X:",code,memory.readLong(arg(0)+4)));for(int i=0;i<12;i++)sb.append(String.format(" %08X",memory.readLong(code+4*i)));System.err.println(sb);}
			// no procedure, no descriptor (the Mixed Mode Manager answers NULL): Resorcerer passes NewDlgHookProc(NULL) to SFPutFile
			// as "no hook" - a descriptor of address 0 was called as the hook and crashed Save File As
			ret(arg(0)==0?0:r.newRoutineDescriptor(arg(0),arg(1),arg(2)&0xff));});
		def("NewRoutineDescriptorTrap",(r,c)->ret(arg(0)==0?0:r.newRoutineDescriptor(arg(0),arg(1),arg(2)&0xff)));
		def("NewFatRoutineDescriptor",(r,c)->{ // (m68kProc, powerPCProc, procInfo)
			int rd=r.allocate(PpcRuntime.RD_HEADER+2*PpcRuntime.RD_RECORD,4);r.toolbox.api.adoptPointer(rd,PpcRuntime.RD_HEADER+2*PpcRuntime.RD_RECORD);
			memory.writeWord(rd,PpcRuntime.MIXED_MODE_MAGIC);memory.writeByte(rd+2,7);memory.writeWord(rd+10,1);
			int r0=rd+PpcRuntime.RD_HEADER,r1=r0+PpcRuntime.RD_RECORD;
			memory.writeLong(r0,arg(2));memory.writeByte(r0+5,PpcRuntime.ISA_68K);memory.writeLong(r0+8,arg(0));
			memory.writeLong(r1,arg(2));memory.writeByte(r1+5,PpcRuntime.ISA_PPC);memory.writeWord(r1+6,0x04);memory.writeLong(r1+8,arg(1));
			ret(rd);});
		def("DisposeRoutineDescriptor",(r,c)->r.disposeRoutineDescriptor(arg(0)));
		def("DisposeRoutineDescriptorTrap",(r,c)->r.disposeRoutineDescriptor(arg(0)));
		// Programs built without New<X>UPP (classic REALbasic, 何かon林檎) hand ThreadsLib their callbacks' TVectors: wrap them for the 68K Thread Manager
		threadGlue("NewThread",7,1,THREAD_UPPS.get("ThreadEntry"));
		threadGlue("SetThreadScheduler",1,0,THREAD_UPPS.get("ThreadScheduler"));
		threadGlue("SetThreadSwitcher",4,1,THREAD_UPPS.get("ThreadSwitch"));
		threadGlue("SetThreadTerminator",3,1,THREAD_UPPS.get("ThreadTermination"));
		threadGlue("SetDebuggerNotificationProcs",3,0,THREAD_UPPS.get("DebuggerNewThread"),1,THREAD_UPPS.get("DebuggerDisposeThread"),2,THREAD_UPPS.get("DebuggerThreadScheduler"));
		PpcRuntime.Native call=(r,c)->{
			// The arguments in r5-r10 always go along: PowerPC code is called directly with them, whatever procInfo says (the real
			// Mixed Mode does not look at it then). SoundApp calls its I/O completion object with procInfo $101 - no parameter in it -
			// and the routine got a NIL parameter block: the last buffer of every sound was never queued (files stopped ~0.8 s short)
			int upp=arg(0),procInfo=arg(1);int n=Math.max(argCount(procInfo),6);int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(2+i);
			ret(r.callUniversal(upp,procInfo,a));};
		def("CallUniversalProc",call);def("CallOSTrapUniversalProc",call);
		def("SaveMixedModeState",(r,c)->ret(0));def("RestoreMixedModeState",(r,c)->ret(0));
		def("GetCurrentArchitecture",(r,c)->ret(PpcRuntime.ISA_PPC));
	}

	/** GetDiskFragment: the fragment of a file's data fork at offset/length (0 = to the end), shared or private by findFlags. */
	private int diskFragment(PpcRuntime r,int spec,int off,int len,int namePtr,int findFlags,int connPtr,int mainPtr,int errPtr) throws M68kException {
		File f=fileOf(spec);
		if(f==null||!f.isFile()){if(PpcRuntime.TRACE)System.err.printf("[ppc] GetDiskFragment: no file for vRefNum %d dirID %d \"%s\" (%s)%n",(short)memory.readWord(spec),memory.readLong(spec+2),rt.toolbox.readPascal(spec+6),f);return -43;}
		try{
			// CFM tells containers apart by location, not by name: kFindCFrag (2) only finds the loaded instance of this container,
			// kReferenceCFrag (1) shares it, kPrivateCFragCopy (5: bit 2) always prepares a new one. Acrobat Reader 5 loads each plug-in
			// with the same fragment name and kPrivateCFragCopy; a lookup by name handed every plug-in after the first the first
			// one's instance ("two plug-ins tried to register the same name: Escript")
			String want=namePtr==0?null:r.pString(namePtr);
			PpcRuntime.Fragment fr=(findFlags&4)!=0?null:r.fragmentByContainer(f,off);
			if(fr==null&&findFlags==2){if(errPtr!=0)r.writePString(errPtr,f.getName());return PpcRuntime.CFRAG_NO_LIBRARY;}
			if(fr==null){
				byte[] data=PpcRuntime.dataFork(f);
				fr=r.loadFragment(want!=null&&!want.isEmpty()?want:f.getName(),f,data,off,len==0?0:len);
			}
			if(connPtr!=0)memory.writeLong(connPtr,fr.connection);if(mainPtr!=0)memory.writeLong(mainPtr,fr.instance.main());
			return 0;
		}catch(IOException e){System.err.println("[ppc] GetDiskFragment "+f+": "+e.getMessage());if(errPtr!=0)r.writePString(errPtr,f.getName());return PpcRuntime.CFRAG_FORMAT;}
	}

	private void defineCfm(){
		def("GetSharedLibrary",(r,c)->{ // (libName, archType, loadFlags, *connID, *mainAddr, errMessage)
			String name=r.pString(arg(0));int[] conn={0},main={0};
			int err=r.getSharedLibrary(name,conn,main);
			if(err==0){if(arg(3)!=0)memory.writeLong(arg(3),conn[0]);if(arg(4)!=0)memory.writeLong(arg(4),main[0]);if(arg(5)!=0)memory.writeByte(arg(5),0);}
			else if(arg(5)!=0)r.writePString(arg(5),name);
			if(PpcRuntime.TRACE)System.err.println("[ppc] GetSharedLibrary(\""+name+"\") = "+err);
			ret(err);});
		def("GetDiskFragment",(r,c)->ret(diskFragment(r,arg(0),arg(1),arg(2),arg(3),arg(4),arg(5),arg(6),arg(7)))); // (fileSpec, offset, length, fragName, findFlags, *connID, *mainAddr, errMessage)
		// The private layer under GetDiskFragment/GetMemFragment: FragPrepare(context, CFragSystem7Locator*, fragName, options,
		// architecture, *connID, *mainAddr, errMessage), the locator {where, fileSpec|address, offset|length, length} with where
		// 1 = data fork, 0 = memory. QuickDraw 3D loads its renderer plug-ins (QuickDraw 3D IR...) this way; FragRelease undoes it.
		def("FragPrepare",(r,c)->{int loc=arg(1);if(loc==0){ret(-50);return;}int where=memory.readLong(loc);
			if(where==1){ret(diskFragment(r,memory.readLong(loc+4),memory.readLong(loc+8),memory.readLong(loc+12),arg(2),arg(3),arg(5),arg(6),arg(7)));return;}
			if(where==0){String name=arg(2)==0?"memory fragment":r.pString(arg(2));PpcRuntime.Fragment fr=r.tryLoadMemoryFragment(memory.readLong(loc+4),memory.readLong(loc+8),name);if(fr==null){ret(PpcRuntime.CFRAG_FORMAT);return;}
				if(arg(5)!=0)memory.writeLong(arg(5),fr.connection);if(arg(6)!=0)memory.writeLong(arg(6),fr.instance.main());ret(0);return;}
			System.err.println("[ppc] FragPrepare: locator kind "+where+" not supported");ret(PpcRuntime.CFRAG_NO_LIBRARY);});
		def("FragRelease",(r,c)->ret(0));
		// SOMobjects' private CFM calls (OpenDoc, Cyberdog): FragGetConnectionInfo(context, connID, next, info) with next = 1 walks
		// the loaded connections (connID 0 = the first one, then the ID it returned; cfragConnectionIDErr at the end), next = 0
		// describes connID itself. Layout read off SOM's own use: +0 connection ID, +$14 location kind (1 = data fork),
		// +$18 FSSpec* (the caller's buffer), +$1C/+$20 container offset/length, +$24/+$28 address and length of the data
		// section (SOM asks whether a class's data still lies in a loaded fragment), +$4C fragment name. SOM registers a
		// load/unload notification with FragAddNotifyProc; fragments are never unloaded here, so it is never called.
		def("FragGetConnectionInfo",(r,c)->{
			java.util.List<PpcRuntime.Fragment> l=r.loadedFragments();PpcRuntime.Fragment f=null;int id=arg(1),info=arg(3);
			if(arg(2)!=0){if(id==0)f=l.isEmpty()?null:l.get(0);else for(int i=0;i<l.size()-1;i++)if(l.get(i).connection==id){f=l.get(i+1);break;}}
			else f=r.fragmentByConnection(id);
			if(f==null||f.instance==null||info==0){ret(PpcRuntime.CFRAG_CONNECTION_ID);return;}
			int dataAddr=0,dataLen=0;
			for(int i=0;i<f.pef.sections.length;i++){Pef.Section s=f.pef.sections[i];
				if(s.kind==Pef.KIND_UNPACKED_DATA||s.kind==Pef.KIND_PATTERN_DATA){dataAddr=f.instance.sectionAddress[i];dataLen=s.totalSize;break;}}
			memory.writeLong(info,f.connection);
			memory.writeLong(info+0x14,f.containerOffset>=0?1:2);
			int spec=memory.readLong(info+0x18);if(spec!=0&&f.file!=null)makeFSSpec(f.file,spec);
			memory.writeLong(info+0x1c,Math.max(f.containerOffset,0));memory.writeLong(info+0x20,0);
			memory.writeLong(info+0x24,dataAddr);memory.writeLong(info+0x28,dataLen);
			r.writePString(info+0x4c,f.name.length()>63?f.name.substring(0,63):f.name);
			if(PpcRuntime.TRACE)System.err.println("[ppc] FragGetConnectionInfo -> \""+f.name+"\" data "+Integer.toHexString(dataAddr)+"+"+Integer.toHexString(dataLen));
			ret(0);});
		def("FragAddNotifyProc",(r,c)->ret(0));
		def("FragRemoveNotifyProc",(r,c)->ret(0));
		def("GetMemFragment",(r,c)->{ // (memAddr, length, fragName, findFlags, *connID, *mainAddr, errMessage)
			String name=arg(2)==0?"memory fragment":r.pString(arg(2));
			PpcRuntime.Fragment fr=r.tryLoadMemoryFragment(arg(0),arg(1),name);if(fr==null){ret(PpcRuntime.CFRAG_FORMAT);return;}
			if(arg(4)!=0)memory.writeLong(arg(4),fr.connection);if(arg(5)!=0)memory.writeLong(arg(5),fr.instance.main());
			ret(0);});
		def("CloseConnection",(r,c)->ret(0));
		def("FindSymbol",(r,c)->{ // (connID, symName, *symAddr, *symClass)
			int id=arg(0);String name=r.pString(arg(1));
			int addr=0,cls=Pef.CLASS_TVECTOR;
			PpcRuntime.Fragment f=r.fragmentByConnection(id);
			if(f!=null){Integer v=f.exports.get(name);if(v==null){if(PpcRuntime.TRACE)System.err.println("[ppc] FindSymbol: \""+f.name+"\" has no "+name);ret(PpcRuntime.CFRAG_NO_SYMBOL);return;}addr=v;Integer k=f.exportClasses.get(name);if(k!=null)cls=k;}
			else{String lib=r.nativeConnectionName(id);if(lib==null){ret(PpcRuntime.CFRAG_CONNECTION_ID);return;}
				addr=r.nativeSymbol(lib,name,Pef.CLASS_TVECTOR,true);if(addr==0){ret(PpcRuntime.CFRAG_NO_SYMBOL);return;}}
			if(PpcRuntime.TRACE)System.err.println("[ppc] FindSymbol "+name+" = "+Integer.toHexString(addr));
			if(arg(2)!=0)memory.writeLong(arg(2),addr);if(arg(3)!=0)memory.writeByte(arg(3),cls);
			ret(0);});
		def("CountSymbols",(r,c)->{PpcRuntime.Fragment f=r.fragmentByConnection(arg(0));if(f==null){ret(r.nativeConnectionName(arg(0))!=null?0:PpcRuntime.CFRAG_CONNECTION_ID);if(arg(1)!=0)memory.writeLong(arg(1),0);return;}
			if(arg(1)!=0)memory.writeLong(arg(1),f.exports.size());ret(0);});
		def("GetIndSymbol",(r,c)->{ // (connID, index, symName, *symAddr, *symClass)
			PpcRuntime.Fragment f=r.fragmentByConnection(arg(0));if(f==null){ret(PpcRuntime.CFRAG_CONNECTION_ID);return;}
			int i=arg(1);if(i<0||i>=f.exports.size()){ret(PpcRuntime.CFRAG_NO_SYMBOL);return;}
			String name=(String)f.exports.keySet().toArray()[i];
			if(arg(2)!=0)r.writePString(arg(2),name);if(arg(3)!=0)memory.writeLong(arg(3),f.exports.get(name));
			if(arg(4)!=0){Integer k=f.exportClasses.get(name);memory.writeByte(arg(4),k==null?0:k);}
			ret(0);});
	}

	private void defineMemoryAndStrings(){
		def("BlockZero",(r,c)->{if(arg(1)>0)memory.fill(arg(0),arg(1),0);});
		def("BlockZeroUncached",(r,c)->{if(arg(1)>0)memory.fill(arg(0),arg(1),0);});
		def("BlockClear",(r,c)->{if(arg(1)>0)memory.fill(arg(0),arg(1),0);}); // PrivateInterfaceLib: QuickTime 6's sound decompressors
		// DriverServicesLib's string compare (QuickTime 6's 'yuvs' decompressor calls the weak import without testing it)
		def("CStrCmp",(r,c)->{int a=arg(0),b=arg(1);for(int i=0;;i++){int x=memory.readByte(a+i),y=memory.readByte(b+i);if(x!=y||x==0){ret((short)(x-y));return;}}});
		// PrivateInterfaceLib's system-heap helpers (weak imports of QuickTime 6's streaming library and components): one heap here
		def("BeginSystemMode",(r,c)->ret(0));def("EndSystemMode",(r,c)->ret(0));
		def("EnoughSystemHeapSlop",(r,c)->ret(1));
		PpcRuntime.Native move=(r,c)->{int n=arg(2);if(n>0)memory.copy(arg(0),arg(1),n);};
		def("BlockMoveData",move);def("BlockMoveUncached",move);def("BlockMoveDataUncached",move);def("BlockMove",move);
		def("StackSpace",(r,c)->ret(Math.max(0,c.r[1]-r.stackBase)));
		def("c2pstr",(r,c)->ret(c2p(arg(0))));def("C2PStr",(r,c)->ret(c2p(arg(0))));
		def("p2cstr",(r,c)->ret(p2c(arg(0))));def("P2CStr",(r,c)->ret(p2c(arg(0))));
		def("CopyPascalStringToC",(r,c)->{int s=arg(0),d=arg(1);int n=memory.readByte(s);memory.copy(s+1,d,n);memory.writeByte(d+n,0);});
		def("CopyCStringToPascal",(r,c)->{int s=arg(0),d=arg(1);byte[] b=r.cBytes(s);int n=Math.min(255,b.length);memory.load(d+1,b,0,n);memory.writeByte(d,n);});
		def("EqualString",(r,c)->{ // (str1, str2, caseSensitive, diacSensitive) via _CmpString
			int a=arg(0),b=arg(1);int word=0xa03c|((arg(2)&0xff)!=0?0x0400:0)|((arg(3)&0xff)!=0?0:0x0200);
			int d0=(memory.readByte(a)<<16)|memory.readByte(b);
			ret(r.trap(word,d0,a+1,b+1)==0?1:0);});
		def("RelString",(r,c)->{
			int a=arg(0),b=arg(1);int word=0xa050|((arg(2)&0xff)!=0?0x0400:0)|((arg(3)&0xff)!=0?0:0x0200);
			int d0=(memory.readByte(a)<<16)|memory.readByte(b);
			ret((short)r.trap(word,d0,a+1,b+1));});
		def("UpperString",(r,c)->{int s=arg(0);r.trap(0xa054|((arg(1)&0xff)!=0?0:0x0200),memory.readByte(s),s+1,0);});
		def("IUCompString",(r,c)->{int a=arg(0),b=arg(1);ret(r.toolbox("IUMagString",a+1,b+1,memory.readByte(a),memory.readByte(b)));});
		def("IUEqualString",(r,c)->{int a=arg(0),b=arg(1);ret(r.toolbox("IUMagIDString",a+1,b+1,memory.readByte(a),memory.readByte(b)));});
		def("IUCompPString",(r,c)->{int a=arg(0),b=arg(1);ret(r.toolbox("IUMagString",a+1,b+1,memory.readByte(a),memory.readByte(b)));});
		def("IUEqualPString",(r,c)->{int a=arg(0),b=arg(1);ret(r.toolbox("IUMagIDString",a+1,b+1,memory.readByte(a),memory.readByte(b)));});
		// PrivateInterfaceLib's IUMagWString(aPtr, bPtr, aLen, bLen): ordered like IUMagString. Flash 4's Windows layer compares
		// strings with it; the missing function answered 0 ("equal") for strings of different lengths
		def("IUMagWString",(r,c)->ret(r.toolbox("IUMagString",arg(0),arg(1),(short)arg(2),(short)arg(3))));
		def("GetIndString",(r,c)->{ // (theString, strListID, index)
			int dst=arg(0),id=(short)arg(1),index=(short)arg(2);
			memory.writeByte(dst,0);
			int h=r.toolbox("GetResource",0x53545223,id);
			if(h==0||index<1)return;
			int p=memory.readLong(h);if(p==0)return;
			int count=memory.readWord(p);if(index>count)return;
			int at=p+2;for(int i=1;i<index;i++)at+=memory.readByte(at)+1;
			int n=memory.readByte(at);memory.copy(at,dst,n+1);});
		def("GetIndPattern",(r,c)->{ // (thePattern, patternListID, index)
			int dst=arg(0),id=(short)arg(1),index=(short)arg(2);
			int h=r.toolbox("GetResource",0x50415423,id);
			if(h==0)return;int p=memory.readLong(h);if(p==0)return;
			int count=memory.readWord(p);if(index<1||index>count)return;
			memory.copy(p+2+8*(index-1),dst,8);});
	}

	private void defineFiles(){
		// ioNamePtr 18, ioVRefNum 22, ioRefNum 24, ioPermssn 27, ioMisc 28, ioBuffer 32, ioReqCount 36, ioActCount 40, ioPosMode 44, ioPosOffset 46, ioDirID 48
		def("FSOpen",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa000,pb);if(arg(2)!=0)memory.writeWord(arg(2),memory.readWord(pb+24));return e;})));
		def("OpenDF",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa260,0x1a,pb);if(arg(2)!=0)memory.writeWord(arg(2),memory.readWord(pb+24));return e;})));
		def("OpenRF",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa00a,pb);if(arg(2)!=0)memory.writeWord(arg(2),memory.readWord(pb+24));return e;})));
		def("HOpen",(r,c)->ret(hOpen(0xa200,0)));
		def("HOpenDF",(r,c)->ret(hOpen(0xa260,0x1a)));
		def("HOpenRF",(r,c)->ret(hOpen(0xa20a,0)));
		def("FSRead",(r,c)->ret(readWrite(0xa002)));
		def("FSWrite",(r,c)->ret(readWrite(0xa003)));
		def("FSClose",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));return pbTrap(0xa001,pb);})));
		def("GetEOF",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));int e=pbTrap(0xa011,pb);if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(pb+28));return e;})));
		def("SetEOF",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));memory.writeLong(pb+28,arg(1));return pbTrap(0xa012,pb);})));
		def("GetFPos",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));int e=pbTrap(0xa018,pb);if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(pb+46));return e;})));
		def("SetFPos",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));memory.writeWord(pb+44,arg(1));memory.writeLong(pb+46,arg(2));return pbTrap(0xa044,pb);})));
		def("Allocate",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));int n=arg(1)==0?0:memory.readLong(arg(1));memory.writeLong(pb+36,n);int e=pbTrap(0xa010,pb);if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(pb+40));return e;})));
		def("Create",(r,c)->ret(withPb(112,pb->{ // (fileName, vRefNum, creator, fileType)
			memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa008,pb);if(e!=0)return e;
			memory.writeWord(pb+28,0);e=pbTrap(0xa00c,pb);if(e!=0)return e;
			memory.writeLong(pb+32,arg(3));memory.writeLong(pb+36,arg(2));memory.writeWord(pb+22,arg(1));return pbTrap(0xa00d,pb);})));
		def("HCreate",(r,c)->ret(withPb(112,pb->{ // (vRefNum, dirID, fileName, creator, fileType)
			memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));int e=pbTrap(0xa208,pb);if(e!=0)return e;
			memory.writeWord(pb+28,0);memory.writeLong(pb+48,arg(1));e=pbTrap(0xa20c,pb);if(e!=0)return e;
			memory.writeLong(pb+32,arg(4));memory.writeLong(pb+36,arg(3));memory.writeLong(pb+48,arg(1));return pbTrap(0xa20d,pb);})));
		def("FSDelete",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));return pbTrap(0xa009,pb);})));
		def("HDelete",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));return pbTrap(0xa209,pb);})));
		def("GetFInfo",(r,c)->ret(withPb(112,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa00c,pb);if(e==0&&arg(2)!=0)memory.copy(pb+32,arg(2),16);return e;})));
		def("SetFInfo",(r,c)->ret(withPb(112,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa00c,pb);if(e!=0)return e;memory.copy(arg(2),pb+32,16);memory.writeWord(pb+22,arg(1));return pbTrap(0xa00d,pb);})));
		// getfinfo/setfinfo(char *fileName, short vRefNum, FInfo *): the C-string GetFInfo/SetFInfo of the CLib glue. Director 7
		// identifies a linked image by the file type getfinfo reports (Last Call's bar backgrounds, bg_picts:bgN.jpg)
		def("getfinfo",(r,c)->ret(withPb(112+256,pb->{memory.writeLong(pb+18,cToPascalAt(arg(0),pb+112));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa00c,pb);if(e==0&&arg(2)!=0)memory.copy(pb+32,arg(2),16);return e;})));
		def("setfinfo",(r,c)->ret(withPb(112+256,pb->{memory.writeLong(pb+18,cToPascalAt(arg(0),pb+112));memory.writeWord(pb+22,arg(1));int e=pbTrap(0xa00c,pb);if(e!=0)return e;memory.copy(arg(2),pb+32,16);memory.writeWord(pb+22,arg(1));memory.writeLong(pb+18,pb+112);return pbTrap(0xa00d,pb);})));
		def("HGetFInfo",(r,c)->ret(withPb(112,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));int e=pbTrap(0xa20c,pb);if(e==0&&arg(3)!=0)memory.copy(pb+32,arg(3),16);return e;})));
		def("HSetFInfo",(r,c)->ret(withPb(112,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));int e=pbTrap(0xa20c,pb);if(e!=0)return e;memory.copy(arg(3),pb+32,16);memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));return pbTrap(0xa20d,pb);})));
		def("Rename",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));memory.writeLong(pb+28,arg(2));return pbTrap(0xa00b,pb);})));
		def("HRename",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));memory.writeLong(pb+28,arg(3));return pbTrap(0xa20b,pb);})));
		def("GetVol",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));int e=pbTrap(0xa014,pb);if(arg(1)!=0)memory.writeWord(arg(1),memory.readWord(pb+22));return e;})));
		def("SetVol",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));return pbTrap(0xa015,pb);})));
		def("HGetVol",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));int e=pbTrap(0xa214,pb);if(arg(1)!=0)memory.writeWord(arg(1),memory.readWord(pb+22));if(arg(2)!=0)memory.writeLong(arg(2),memory.readLong(pb+48));return e;})));
		def("HSetVol",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));memory.writeLong(pb+48,arg(2));return pbTrap(0xa215,pb);})));
		def("FlushVol",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));return pbTrap(0xa013,pb);})));
		def("GetVInfo",(r,c)->ret(withPb(80,pb->{ // (drvNum, volName, *vRefNum, *freeBytes)
			memory.writeLong(pb+18,arg(1));memory.writeWord(pb+22,arg(0));memory.writeWord(pb+28,0);int e=pbTrap(0xa007,pb);
			if(arg(2)!=0)memory.writeWord(arg(2),memory.readWord(pb+22));
			if(arg(3)!=0)memory.writeLong(arg(3),memory.readWord(pb+62)*memory.readLong(pb+48));return e;})));
		def("DirCreate",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));int e=pbTrap(0xa260,6,pb);if(arg(3)!=0)memory.writeLong(arg(3),memory.readLong(pb+48));return e;})));
		def("CatMove",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));memory.writeLong(pb+28,arg(4));memory.writeLong(pb+36,arg(3));return pbTrap(0xa260,5,pb);})));
		def("GetWDInfo",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+22,arg(0));int e=pbTrap(0xa260,7,pb);
			if(arg(1)!=0)memory.writeWord(arg(1),memory.readWord(pb+32));if(arg(2)!=0)memory.writeLong(arg(2),memory.readLong(pb+48));if(arg(3)!=0)memory.writeLong(arg(3),memory.readLong(pb+28));return e;})));
		def("OpenWD",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));memory.writeLong(pb+28,arg(2));int e=pbTrap(0xa260,1,pb);if(arg(3)!=0)memory.writeWord(arg(3),memory.readWord(pb+22));return e;})));
		def("CloseWD",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+22,arg(0));return pbTrap(0xa260,2,pb);})));
		def("GetVRefNum",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));memory.writeWord(pb+28,0);int e=pbTrap(0xa260,8,pb);int vol=memory.readWord(pb+52);
			if(e==-51&&(short)arg(0)==(short)memory.readWord(0xa58)){e=0;vol=-1;} // the System file's resource map (SysMap) lives on the startup volume: the Apple Software Installer's engine quits when this fails
			if(arg(1)!=0)memory.writeWord(arg(1),vol);return e;})));
		def("KillIO",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));return pbTrap(0xa006,pb);})));
		def("Control",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));memory.writeWord(pb+26,arg(1));if(arg(2)!=0)memory.copy(arg(2),pb+28,22);return pbTrap(0xa004,pb);})));
		def("Status",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));memory.writeWord(pb+26,arg(1));int e=pbTrap(0xa005,pb);if(arg(2)!=0)memory.copy(pb+28,arg(2),22);return e;})));
		def("OpenDriver",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));int e=pbTrap(0xa000,pb);if(arg(1)!=0)memory.writeWord(arg(1),memory.readWord(pb+24));return e;})));
		def("CloseDriver",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));return pbTrap(0xa001,pb);})));
	}
	private int hOpen(int word,int selector) throws M68kException { // (vRefNum, dirID, fileName, permission, *refNum)
		return withPb(80,pb->{memory.writeLong(pb+18,arg(2));memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));memory.writeByte(pb+27,arg(3));
			int e=pbTrap(word,selector,pb);if(arg(4)!=0)memory.writeWord(arg(4),memory.readWord(pb+24));return e;});
	}
	private int readWrite(int word) throws M68kException { // (refNum, *count, buffPtr)
		return withPb(80,pb->{memory.writeWord(pb+24,arg(0));memory.writeLong(pb+32,arg(2));memory.writeLong(pb+36,arg(1)==0?0:memory.readLong(arg(1)));memory.writeWord(pb+44,0);
			int e=pbTrap(word,pb);if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(pb+40));return e;});
	}

	/**
	 * AppleTalk's MPW glue (AppleTalk.h), library code with no inline words: each preferred call sets ioCRefNum to the
	 * driver and csCode, then _Control (async: bit 10) - the .MPP/.ATP of {@link AppleTalk68k} serve them as for a 68K
	 * program. The other routines read the low memory the drivers keep (PortBUse, ABusVars) or pack NBP names.
	 * FileMaker Pro 4.0 registers its serial number with PRegisterName to find a second copy on the network.
	 */
	private void defineAppleTalk(){
		def("MPPOpen",(r,c)->ret(openDriver(".MPP",null)));def("MPPClose",(r,c)->ret(0)); // the driver stays open for other programs
		def("ATPLoad",(r,c)->ret(openDriver(".ATP",null)));def("ATPUnload",(r,c)->ret(0));
		def("OpenXPP",(r,c)->ret(openDriver(".XPP",arg(0)==0?null:arg(0))));
		Object[][] calls={
			{"PWriteLAP",-10,243},{"PDetachPH",-10,244},{"PAttachPH",-10,245},{"PWriteDDP",-10,246},{"PCloseSkt",-10,247},{"POpenSkt",-10,248},
			{"PConfirmName",-10,250},{"PLookupName",-10,251},{"PRemoveName",-10,252},{"PRegisterName",-10,253},{"PKillNBP",-10,254},
			{"PSetSelfSend",-10,256},{"PGetAppleTalkInfo",-10,258},{"PATalkClosePrep",-10,259},
			{"PNSendRequest",-11,248},{"PRelRspCB",-11,249},{"PCloseATPSkt",-11,250},{"PAddResponse",-11,251},{"PSendResponse",-11,252},
			{"PGetRequest",-11,253},{"POpenATPSkt",-11,254},{"PSendRequest",-11,255},{"PRelTCB",-11,256},{"PKillGetReq",-11,257},
			{"PKillSendReq",-11,258},{"PKillAllGetReq",-11,259},
			{"GetZoneList",-41,246},{"GetMyZone",-41,246},{"GetLocalZones",-41,246},{"ASPCloseAll",-41,247},{"ASPAbortOS",-41,248},
			{"ASPGetParms",-41,249},{"AFPCommand",-41,250},{"ASPGetStatus",-41,251},{"ASPUserWrite",-41,252},{"ASPUserCommand",-41,253},
			{"ASPCloseSession",-41,254},{"ASPOpenSession",-41,255}};
		for(Object[] e:calls){final int ref=(Integer)e[1],csCode=(Integer)e[2];
			def((String)e[0],(r,c)->{int pb=arg(0);memory.writeWord(pb+24,ref);memory.writeWord(pb+26,csCode);ret(pbTrap((arg(1)&0xff)!=0?0xa404:0xa004,pb));});}
		// PortBUse (low memory $291): bit 7 set = port B free, low nibble 1 = AppleTalk, bit 4 = ATP loaded
		def("IsMPPOpen",(r,c)->{int b=memory.readByte(0x291);ret((b&0x80)==0&&(b&0x0f)==1?1:0);});
		def("IsATPOpen",(r,c)->{int b=memory.readByte(0x291);ret((b&0x80)==0&&(b&0x10)!=0?1:0);});
		// ABusVars ($2D8): sysLAPAddr at 0, sysABridge at $19, sysNetNum at $1A
		def("GetNodeAddress",(r,c)->{int vars=memory.readLong(0x2d8);if(vars==0){ret(-3102);return;} // noMPPErr
			if(arg(0)!=0)memory.writeWord(arg(0),memory.readByte(vars));if(arg(1)!=0)memory.writeWord(arg(1),memory.readWord(vars+0x1a));ret(0);});
		def("GetBridgeAddress",(r,c)->{int vars=memory.readLong(0x2d8);ret(vars==0?0:memory.readByte(vars+0x19));});
		// NBPSetNTE(ntePtr, object, type, zone, socket): NamesTableEntry = qNext, AddrBlock (net, node, socket), entity data
		def("NBPSetNTE",(r,c)->{int nte=arg(0);memory.writeLong(nte,0);memory.writeWord(nte+4,0);memory.writeByte(nte+6,0);memory.writeByte(nte+7,arg(4));
			memory.writeByte(nte+8,0);packEntity(nte+9,arg(1),arg(2),arg(3));});
		def("NBPSetEntity",(r,c)->packEntity(arg(0),arg(1),arg(2),arg(3)));
		// NBPExtract(buffer, numInBuf, whichOne, *EntityName, *AddrBlock): tuples of AddrBlock, enumerator, three strings
		def("NBPExtract",(r,c)->{int p=arg(0),n=(short)arg(1),which=(short)arg(2);
			if(which<1||which>n){ret(-3104);return;} // extractErr
			for(int i=1;i<which;i++){p+=5;for(int k=0;k<3;k++)p+=1+memory.readByte(p);}
			if(arg(4)!=0)memory.copy(p,arg(4),4);p+=5;
			for(int k=0;k<3;k++){int len=memory.readByte(p);if(arg(3)!=0)memory.copy(p,arg(3)+33*k,1+Math.min(len,32));p+=1+len;}
			ret(0);});
	}
	/** _Open of an AppleTalk driver by name; the refNum goes to {@code refNum} when given. */
	private int openDriver(String name,Integer refNum) throws M68kException {
		return withPb(96,pb->{memory.writeByte(pb+80,name.length());for(int i=0;i<name.length();i++)memory.writeByte(pb+81+i,name.charAt(i));
			memory.writeLong(pb+18,pb+80);int e=pbTrap(0xa000,pb);if(refNum!=null)memory.writeWord(refNum,memory.readWord(pb+24));return e;});
	}
	/** Three Pascal strings (object, type, zone; a NIL zone is "*") packed one after another. */
	private void packEntity(int to,int object,int type,int zone) throws M68kCpuException {
		int[] parts={object,type,zone};
		for(int k=0;k<3;k++){int s=parts[k];
			if(s==0){memory.writeByte(to,1);memory.writeByte(to+1,'*');to+=2;continue;}
			int len=Math.min(memory.readByte(s),32);memory.copy(s,to,1+len);memory.writeByte(to,len);to+=1+len;}
	}

	private void defineLowMem(){
		int[][] pointers={{0x14a},{0x160},{0x308},{0x356},{0x360},{0xd92},{0x910},{0xad8},{0x2e0},{0x1e4},{0x9ce},{0xa78}};
		String[] getters={"LMGetEventQueue","LMGetVBLQueue","LMGetDrvQHdr","LMGetVCBQHdr","LMGetFSQHdr","LMGetDTQueue","LMGetCurApName","LMGetSysResName","LMGetFinderName","LMGetScratch20","LMGetToolScratch","LMGetApplScratch"};
		for(int i=0;i<getters.length;i++){final int a=pointers[i][0];def(getters[i],(r,c)->ret(a));}
		Object[][] copies={{"DSAlertRect",0x3f8,8},{"DragPattern",0xa34,8},{"DeskPattern",0xa3c,8},{"HiliteRGB",0xda0,6},{"EventQueue",0x14a,10},{"VBLQueue",0x160,10},{"DrvQHdr",0x308,10},{"VCBQHdr",0x356,10},{"DTQueue",0xd92,10},{"CurApName",0x910,32},{"SysResName",0xad8,16},{"FinderName",0x2e0,16},{"Scratch20",0x1e4,20},{"ToolScratch",0x9ce,8},{"ApplScratch",0xa78,12}};
		for(Object[] e:copies){final int a=(Integer)e[1],n=(Integer)e[2];String name=(String)e[0];
			def("LMSet"+name,(r,c)->memory.copy(arg(0),a,n));
			if(functions.get("LMGet"+name)==null)def("LMGet"+name,(r,c)->memory.copy(a,arg(0),n));}
		def("LMGetExpandMem",(r,c)->ret(memory.readLong(0x2b6))); // PrivateInterfaceLib (LowMemPriv.h): iTunes reads it in its sound deferred task
		// more of LowMemPriv.h (MPW Shell 3.5's PowerPC code: HpChk holds its globals pointer, as $316 does for its 68K code)
		int[][] longs={{0x316},{0x33c},{0x3e2},{0x8e0},{0xbf6}};String[] longNames={"HpChk","IAZNotify","FSQueueHook","JSwapFont","FScaleHFact"};
		for(int i=0;i<longs.length;i++){final int a=longs[i][0];String n=longNames[i];
			if(functions.get("LMGet"+n)==null)def("LMGet"+n,(r,c)->ret(memory.readLong(a)));if(functions.get("LMSet"+n)==null)def("LMSet"+n,(r,c)->memory.writeLong(a,arg(0)));}
		// QuickTime 6's INIT (QTPixMapExtensionPatch) reads SysVersion and saves/restores QDErr around its pixel map patches
		int[][] words={{0x15a},{0xd6e}};String[] wordNames={"SysVersion","QDErr"};
		for(int i=0;i<words.length;i++){final int a=words[i][0];String n=wordNames[i];
			if(functions.get("LMGet"+n)==null)def("LMGet"+n,(r,c)->ret((short)memory.readWord(a)));if(functions.get("LMSet"+n)==null)def("LMSet"+n,(r,c)->memory.writeWord(a,arg(0)));}
		int[][] bytes={{0x8d2},{0x8f3},{0x8f2}};String[] byteNames={"CrsrObscure","QDExist","WWExist"};
		for(int i=0;i<bytes.length;i++){final int a=bytes[i][0];String n=byteNames[i];
			if(functions.get("LMGet"+n)==null)def("LMGet"+n,(r,c)->ret(memory.readByte(a)));if(functions.get("LMSet"+n)==null)def("LMSet"+n,(r,c)->memory.writeByte(a,arg(0)));}
		def("LMGetKeyMap",(r,c)->memory.copy(0x174,arg(0),16));
		def("LMSetKeyMap",(r,c)->memory.copy(arg(0),0x174,16));
		def("LMGetDAStrings",(r,c)->ret(memory.readLong(0xaa0+4*(short)arg(0))));
		def("LMSetDAStrings",(r,c)->memory.writeLong(0xaa0+4*(short)arg(1),arg(0)));
		def("LMGetLvl2DT",(r,c)->ret(memory.readLong(0x1b2+4*(short)arg(0))));
		def("LMSetLvl2DT",(r,c)->memory.writeLong(0x1b2+4*(short)arg(1),arg(0)));
		def("LMGetExtStsDT",(r,c)->ret(memory.readLong(0x2be+4*(short)arg(0))));
		def("LMSetExtStsDT",(r,c)->memory.writeLong(0x2be+4*(short)arg(1),arg(0)));
		def("GetDCtlEntry",(r,c)->{int ref=(short)arg(0);int table=memory.readLong(0x11c);ret(table==0?0:memory.readLong(table+4*(~ref)));});
	}

	private void defineMisc(){
		def("NGetTrapAddress",(r,c)->{int num=arg(0)&0xffff,kind=arg(1)&0xff;r.trap(kind==0?0xa346:0xa746,num,0,0);ret(r.lastA0);});
		def("NSetTrapAddress",(r,c)->{int addr=arg(0),num=arg(1)&0xffff,kind=arg(2)&0xff;r.trap(kind==0?0xa247:0xa647,num,addr,0);});
		def("GetTrapAddress",(r,c)->{r.trap(0xa146,arg(0)&0xffff,0,0);ret(r.lastA0);});
		def("SetTrapAddress",(r,c)->r.trap(0xa047,arg(1)&0xffff,arg(0),0));
		def("TEFromScrap",(r,c)->{int h=memory.readLong(0xab4);if(h==0){ret(0);return;}
			int offset=r.allocate(4,4);int len=r.toolbox("GetScrap",h,0x54455854,offset);memory.free(offset);
			if(len<0){ret(len);return;}memory.writeWord(0xab0,len);ret(0);});
		def("TEToScrap",(r,c)->{int h=memory.readLong(0xab4);r.toolbox("ZeroScrap");if(h==0){ret(0);return;}
			int len=memory.readWord(0xab0);int p=memory.readLong(h);ret(r.toolbox("PutScrap",len,0x54455854,p));});
		def("TEGetScrapLength",(r,c)->ret(memory.readWord(0xab0)));
		def("TESetScrapLength",(r,c)->memory.writeWord(0xab0,arg(0)));
		def("DialogCut",(r,c)->dialogEdit(r,"TECut"));def("DialogCopy",(r,c)->dialogEdit(r,"TECopy"));
		def("DialogPaste",(r,c)->dialogEdit(r,"TEPaste"));def("DialogDelete",(r,c)->dialogEdit(r,"TEDelete"));
		def("StdFilterProc",(r,c)->{ // the Dialog Manager's standard filter, as GetStdFilterProc hands it out
			int slot=r.allocate(4,4);r.toolbox("GetStdFilterProc",slot);int proc=memory.readLong(slot);memory.free(slot);
			if(proc==0){ret(0);return;}
			ret(r.callUniversal(proc,0x00000fd0,new int[]{arg(0),arg(1),arg(2)})&0xff);});
		def("AEObjectInit",(r,c)->ret(0));
		defineObjectSupport();
		def("Fix2X",(r,c)->r.retD(arg(0)/65536.0));
		def("X2Fix",(r,c)->{double v=r.farg(0)*65536.0;ret(v>=2147483647.0?0x7fffffff:v<=-2147483648.0?0x80000000:(int)Math.round(v));});
		def("Frac2X",(r,c)->r.retD(arg(0)/1073741824.0));
		def("X2Frac",(r,c)->{double v=r.farg(0)*1073741824.0;ret(v>=2147483647.0?0x7fffffff:v<=-2147483648.0?0x80000000:(int)Math.round(v));});
		def("Debugger",(r,c)->System.err.println("[ppc "+r.app.name+"] Debugger() called (no debugger: continuing)"));
		def("DebugStr",(r,c)->System.err.println("[ppc "+r.app.name+"] DebugStr: "+r.pString(arg(0))+" (continuing)"));
		def("debugstr",(r,c)->System.err.println("[ppc "+r.app.name+"] debugstr: "+r.cString(arg(0))+" (continuing)"));
		def("SysDebugStr",(r,c)->System.err.println("[ppc "+r.app.name+"] SysDebugStr: "+r.pString(arg(0))));
		defineWide();
		def("ADBOp",(r,c)->{ // (data, compRout, buffer, commandNum): _ADBOp with A0 = {buffer, compRout, data}
			ret(withPb(16,pb->{memory.writeLong(pb,arg(2));memory.writeLong(pb+4,arg(1));memory.writeLong(pb+8,arg(0));return (short)r.trap(0xa07c,arg(3),pb,0);}));});
		def("InstallExceptionHandler",(r,c)->{ // (handler): program exceptions go to it (PpcCpu.deliverException); answers the previous one
			int previous=c.exceptionHandler;c.exceptionHandler=arg(0);ret(previous);});
		def("MaxApplZone",(r,c)->{});
		def("MakeDataExecutable",(r,c)->{if(c.jit!=null)c.jit.invalidate(arg(0),arg(1));}); // (address, length): compiled code from there is dropped (PpcJit)
		def("GetSoundVol",(r,c)->{if(arg(0)!=0)memory.writeWord(arg(0),SoundMixer68k.soundVol());}); // the shared output volume (SdVolume follows it)
		def("SetSoundVol",(r,c)->{SoundMixer68k.setSoundVol(arg(0)&7);memory.writeByte(0x260,(memory.readByte(0x260)&~7)|(arg(0)&7));});
		// InitZone(pgrowZone, cMoreMasters, limitPtr, startPtr) -> _InitZone with A0 = {startPtr, limitPtr, cMoreMasters, pGrowZone}:
		// the zone record and the zone of later blocks (PtrZone) as for 68K programs
		def("InitZone",(r,c)->withPb(14,pb->{memory.writeLong(pb,arg(3));memory.writeLong(pb+4,arg(2));memory.writeWord(pb+8,arg(1));memory.writeLong(pb+10,arg(0));return r.trap(0xa019,0,pb,0);}));
		def("UpTime",(r,c)->{long t=System.nanoTime();int p=arg(0);memory.writeLong(p,(int)(t>>>32));memory.writeLong(p+4,(int)t);ret(p);});
		def("AbsoluteToNanoseconds",(r,c)->{int p=arg(0);memory.writeLong(p,arg(1));memory.writeLong(p+4,arg(2));ret(p);});
		// AbsoluteToDuration/DurationToAbsolute: PpcMultiprocessing (Duration = negative microseconds or positive milliseconds)
	}
	/** The Object Support Library's descriptor builders (68K programs link them statically): records of AE keywords, relabelled. */
	private void defineObjectSupport(){
		def("CreateObjSpecifier",(r,c)->{ // (desiredClass, *container, keyForm, *keyData, disposeInputs, *result)
			ret(buildRecord(r,0x6f626a20,arg(5),arg(4)&0xff,new Object[]{0x77616e74,0x74797065,arg(0)},new Object[]{0x66726f6d,arg(1)},new Object[]{0x666f726d,0x656e756d,arg(2)},new Object[]{0x73656c64,arg(3)}));});
		def("CreateCompDescriptor",(r,c)->{ // (comparisonOperator, *operand1, *operand2, disposeInputs, *result)
			ret(buildRecord(r,0x636d7064,arg(4),arg(3)&0xff,new Object[]{0x72656c6f,0x656e756d,arg(0)},new Object[]{0x6f626a31,arg(1)},new Object[]{0x6f626a32,arg(2)}));});
		def("CreateLogicalDescriptor",(r,c)->{ // (*terms, logicOperator, disposeInputs, *result)
			ret(buildRecord(r,0x6c6f6769,arg(3),arg(2)&0xff,new Object[]{0x6c6f6763,0x656e756d,arg(1)},new Object[]{0x7465726d,arg(0)}));});
		def("CreateRangeDescriptor",(r,c)->{ // (*rangeStart, *rangeStop, disposeInputs, *result)
			ret(buildRecord(r,0x72616e67,arg(3),arg(2)&0xff,new Object[]{0x73746172,arg(0)},new Object[]{0x73746f70,arg(1)}));});
		def("CreateOffsetDescriptor",(r,c)->{ // (offset, *result): a long integer descriptor
			int v=r.allocate(4,4);memory.writeLong(v,arg(0));int e=r.toolbox("AECreateDesc",0x6c6f6e67,v,4,arg(1));memory.free(v);ret(e);});
	}
	/**
	 * An AE record with the given items, relabelled {@code type}, into the AEDesc at {@code result}. An item is {keyword,
	 * AEDesc address} or {keyword, type, 4-byte value}; with {@code dispose} the descriptors given are disposed of.
	 * The record is built aside and stored last: the result may be one of the inputs - CreateObjSpecifier(cls, &obj, form,
	 * &key, true, &obj) nests a specifier in its own variable (WAV), and emptying it first lost the container (Bold on a
	 * word of the second paragraph styled the same characters of the first).
	 */
	private int buildRecord(PpcRuntime r,int type,int result,int dispose,Object[]... items) throws M68kException {
		int record=r.allocate(8,4),v=r.allocate(4,4);
		try{
			int err=r.toolbox("AECreateList",0,0,1,record);if(err!=0)return err;
			for(Object[] item:items){
				int key=(Integer)item[0];
				if(item.length==2){int desc=(Integer)item[1];err=desc==0?r.toolbox("AEPutParamPtr",record,key,0x6e756c6c,0,0):r.toolbox("AEPutParamDesc",record,key,desc);}
				else{memory.writeLong(v,(Integer)item[2]);err=r.toolbox("AEPutParamPtr",record,key,(Integer)item[1],v,4);}
				if(err!=0){r.toolbox("AEDisposeDesc",record);return err;}
			}
			if(dispose!=0)for(Object[] item:items)if(item.length==2&&(Integer)item[1]!=0)r.toolbox("AEDisposeDesc",(Integer)item[1]);
			memory.writeLong(result,type);memory.writeLong(result+4,memory.readLong(record+4));
			return 0;
		}finally{memory.free(v);memory.free(record);}
	}
	/** The 64-bit integer math of FixMath.h (the 68K versions went through QuickDraw GX's $A832 dispatch). */
	private long wide(int p) throws M68kCpuException {return ((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);}
	private void wide(int p,long v) throws M68kCpuException {memory.writeLong(p,(int)(v>>>32));memory.writeLong(p+4,(int)v);}
	private void defineWide(){
		def("WideAdd",(r,c)->{wide(arg(0),wide(arg(0))+wide(arg(1)));ret(arg(0));});
		def("WideSubtract",(r,c)->{wide(arg(0),wide(arg(0))-wide(arg(1)));ret(arg(0));});
		def("WideNegate",(r,c)->{wide(arg(0),-wide(arg(0)));ret(arg(0));});
		def("WideCompare",(r,c)->ret(Long.compare(wide(arg(0)),wide(arg(1)))));
		def("WideMultiply",(r,c)->{wide(arg(2),(long)arg(0)*(long)arg(1));ret(arg(2));});
		def("WideShift",(r,c)->{long v=wide(arg(0));int n=arg(1);if(n>0)v=n>=64?0:(v+(1L<<(n-1)))>>n;else if(n<0)v=-n>=64?0:v<<-n;wide(arg(0),v);ret(arg(0));}); // rounds
		def("WideBitShift",(r,c)->{long v=wide(arg(0));int n=arg(1);if(n>0)v=n>=64?(v<0?-1:0):v>>n;else if(n<0)v=-n>=64?0:v<<-n;wide(arg(0),v);ret(arg(0));});
		def("WideSquareRoot",(r,c)->{long v=wide(arg(0));ret(v<=0?0:(int)(long)Math.floor(Math.sqrt((double)v)));});
		// remainder (long *)-1 = "no remainder wanted" (FixMath): Flash 4 passes it, writing there went past the address space
		def("WideDivide",(r,c)->{long v=wide(arg(0));int d=arg(1);if(d==0){ret(0x80000000);return;}long q=v/d;if(arg(2)!=0&&arg(2)!=-1)memory.writeLong(arg(2),(int)(v%d));ret(q>Integer.MAX_VALUE||q<Integer.MIN_VALUE?0x80000000:(int)q);});
		def("WideWideDivide",(r,c)->{long v=wide(arg(0));int d=arg(1);if(d==0){wide(arg(0),0x8000000000000000L);ret(arg(0));return;}if(arg(2)!=0&&arg(2)!=-1)memory.writeLong(arg(2),(int)(v%d));wide(arg(0),v/d);ret(arg(0));});
	}

	private void dialogEdit(PpcRuntime r,String call) throws M68kException {
		int d=arg(0);if(d==0)return;
		int textH=memory.readLong(d+160),edit=(short)memory.readWord(d+164);
		if(edit>=0&&textH!=0)r.toolbox(call,textH);
	}

	/** The C-string variants of Toolbox calls (lower-case names): C strings become Pascal strings in place around the call. */
	private void defineCStringGlue(){
		// target function, then per argument: c = C string in, o = string out (Pascal after the call -> C), - = as is
		cglue("opendriver","OpenDriver","c-");cglue("drawstring","DrawString","c");cglue("stringwidth","StringWidth","c");
		cglue("getindstring","GetIndString","o--");cglue("paramtext","ParamText","cccc");
		cglue("numtostring","NumToString","-o");cglue("stringtonum","StringToNum","c-");
		cglue("getdialogitemtext","GetDialogItemText","-o");cglue("setdialogitemtext","SetDialogItemText","-c");
		cglue("getditem","GetDialogItem","-----");
		cglue("setwtitle","SetWTitle","-c");cglue("getwtitle","GetWTitle","-o");
		cglue("newwindow","NewWindow","--c-----");cglue("newcwindow","NewCWindow","--c-----");
		cglue("newdialog","NewDialog","--c------");cglue("newcdialog","NewColorDialog","--c------");cglue("newcolordialog","NewColorDialog","--c------");
		cglue("appendmenu","AppendMenu","-c");cglue("insertmenuitem","InsertMenuItem","-c-");cglue("insmenuitem","InsertMenuItem","-c-");
		cglue("setmenuitemtext","SetMenuItemText","--c");cglue("getmenuitemtext","GetMenuItemText","--o");
		cglue("setitem","SetMenuItemText","--c");cglue("getitem","GetMenuItemText","--o");
		cglue("newmenu","NewMenu","-c");cglue("newcontrol","NewControl","--c-----");
		cglue("setcontroltitle","SetControlTitle","-c");cglue("getcontroltitle","GetControlTitle","-o");
		cglue("setctitle","SetControlTitle","-c");cglue("getctitle","GetControlTitle","-o");
		cglue("opendeskacc","OpenDeskAcc","c");cglue("getnamedresource","GetNamedResource","-c");cglue("get1namedresource","Get1NamedResource","-c");
		cglue("getfnum","GetFNum","c-");cglue("getfontname","GetFontName","-o");cglue("newstring","NewString","c");cglue("setstring","SetString","-c");
		cglue("equalstring","EqualString","cc--");cglue("relstring","RelString","cc--");cglue("uprstring","UpperString","o-");cglue("upperstring","UpperString","o-");
		cglue("addresmenu","AppendResMenu","--");
	}
	private void cglue(String name,final String target,final String spec){
		def(name,(r,c)->{
			int n=spec.length();int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);
			for(int i=0;i<n;i++)if(spec.charAt(i)=='c'&&a[i]!=0)c2p(a[i]);
			int result;
			try{
				PpcRuntime.Native direct=function("InterfaceLib",target);
				if(direct!=null){for(int i=0;i<n&&i<8;i++)c.r[3+i]=a[i];direct.call(r,c);result=c.r[3];}
				else result=r.toolbox(target,a);
			}finally{for(int i=0;i<n;i++)if((spec.charAt(i)=='c'||spec.charAt(i)=='o')&&a[i]!=0)p2c(a[i]);}
			ret(result);
		});
	}

	// ------------------------------------------------------------------ Gestalt answers of a Power Macintosh

	void installGestalt(){
		Map<Integer,Integer> g=rt.toolbox.gestaltValues;
		g.put(0x73797361,Integer.getInteger("hzppc.sysa",2)); // sysa: gestaltPowerPC
		g.put(0x63667267,1);            // cfrg: gestaltCFMPresent
		g.put(0x6d697864,1);            // mixd: Mixed Mode present
		g.put(0x63707574,0x0106);       // cput: 603e
		g.put(0x63707566,0x0103);       // cpuf: gestaltNativeCPUfamily - the 603 family (Otto Matic: "VerifySystem: gestaltNativeCPUfamily failed!")
		g.put(0x70636c6b,PpcRuntime.REAL_MIPS*1000000); // pclk: gestaltProcClkSpeed in Hz, the 233 MHz of the "real machine" speed (Otto Matic checks it too)
		g.put(0x62636c6b,66000000);     // bclk: gestaltBusClkSpeed, a 66 MHz bus
		// ram/lram: the PHYSICAL memory of the machine, not the application partition (the VM's memory, 64 MB for a 30 MB SIZE):
		// a 9.2.2 Power Mac with 256 MB, VM off. Otto Matic: "This game needs at least 96MB of real RAM" (-Dhzppc.ram=MB overrides)
		int ram=Math.max(memory.size(),Integer.getInteger("hzppc.ram",256)*1024*1024);
		g.put(0x72616d20,ram);g.put(0x6c72616d,ram);
		g.put(0x70706366,0x0003);       // ppcf: graphics instructions (fsel, fres, frsqrte), stfiwx
		// mach: a Power Macintosh (Performa 5200/6200 class). The Apple Software Installer ('chsk', iMovie 1.0 "Install iMovie") compares
		// it with the machine IDs of the script's 'mid#' list and stops with "This program cannot run on your computer" for any other
		// (310/312 etc. are in that list), so it sees 312 (-Dhzppc.mach=N overrides).
		int machine=108;
		if(rt.app!=null&&rt.app.file!=null&&FinderInfo68k.creator(rt.app.file)==0x6368736b)machine=312;
		g.put(0x6d616368,Integer.getInteger("hzppc.mach",machine));
		g.put(0x74686473,0x0001|0x0002|0x0004); // thds: Thread Manager, specific-match support and the ThreadsLib shared library (iCab refused to start: "the ThreadManager is not installed")
		// PowerPC programs run on Mac OS 9.2.2 (the last classic system): with the Appearance Manager 1.1 and CarbonLib 1.6
		// installed, as on every 9.2.2. Sherlock 2 needs 9, Outlook Express 5 8.1, Audion 3 and PhotoGrid 8. 68K programs see
		// 9.2.2 too; the launcher can give a program System 7.5.5 (Toolbox68k.systemVersionFor). SysVersion ($15A) and SysEnvirons
		// follow Gestalt.
		int sysv=rt.toolbox.baseSystemVersion;
		g.put(0x73797376,sysv);         // sysv
		// appr: the Appearance Manager is present, NOT in compatibility mode (gestaltAppearanceCompatMode, bit 1 = "system-wide
		// appearance turned off", an option of Mac OS 8.0/8.1 only). Alsoft's Directory Optimization Grapher stopped with "requires
		// Appearance Manager 1.0.1 or later ... or turn on System Wide Platinum Appearance" while bit 1 was set
		g.put(0x61707072,0x0001);       // appr: gestaltAppearanceExists
		g.put(0x61707672,0x0114);       // apvr: Appearance 1.1.4
		g.put(0x63626f6e,0x0160);       // cbon: CarbonLib 1.6
		// wind/menu/cntl: the Mac OS 8.5 Window, Menu and Control Managers are there (each answers its "present" mask, bit 0);
		// 'wind' also has bit 2, gestaltHasFloatingWindows (Mac OS 8.6 and later: InitFloatingWindows, ShowFloatingWindows...).
		// Amadeus II 3.2.3 tests 'wind' for bits 0 and 2 right after main: "Amadeus requires at least MacOS 8.6" otherwise
		g.put(0x77696e64,0x0001|0x0004); // wind: gestaltWindowMgrPresent | 1<<gestaltHasFloatingWindows
		g.put(0x6d656e75,1);            // menu: gestaltMenuMgrPresent
		g.put(0x636e746c,1);            // cntl: gestaltControlMgrPresent
		g.put(0x66626376,0x0011);       // fbcv: Find by Content (see defineFindByContent)
		g.put(0x66626369,1);            // fbci: gestaltFBCindexingCritical - searches run at once
		try{memory.writeWord(0x15a,sysv);}catch(M68kCpuException e){}
		if(PpcQuickDraw3D.NATIVE){
			g.put(0x71643364,1);                      // qd3d: gestaltQD3DPresent - QuickDraw 3D is there without the extension
			g.put(0x71337620,PpcQuickDraw3D.RELEASE); // 'q3v ': gestaltQD3DVersion, 1.6 final
			g.put(0x71337663,1);                      // 'q3vc': gestaltQD3DViewer present
		}
	}

	// ------------------------------------------------------------------ StdCLib support

	/** A 257-byte ctype table (index c+1) as MPW's StdCLib keeps it: bits upper 1, lower 2, digit 4, space 8, punct 16, control 32, hex 64, blank 128. */
	private int ctypeTable() throws M68kCpuException {
		int p=rt.allocate(260,4);
		for(int ch=0;ch<256;ch++){
			int b=0;
			if(ch>='A'&&ch<='Z')b|=1;if(ch>='a'&&ch<='z')b|=2;if(ch>='0'&&ch<='9')b|=4;
			if(ch==' '||ch>=9&&ch<=13)b|=8;if(ch>32&&ch<127&&!Character.isLetterOrDigit(ch))b|=16;
			if(ch<32||ch==127)b|=32;if(ch>='0'&&ch<='9'||ch>='a'&&ch<='f'||ch>='A'&&ch<='F')b|=64;if(ch==' ')b|=128;
			memory.writeByte(p+1+ch,b);
		}
		return p+1;
	}
	static byte[] macRoman(String s){return s.getBytes(MAC_ROMAN);}
}

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PowerPC side of a hosted Macintosh process: a Code Fragment Manager, Mixed Mode and the system libraries.
 *
 * A PowerPC application runs in the same process as the 68K emulator ({@link MacApp68k}), sharing its memory, as on a
 * Power Macintosh: the Toolbox is the 68K layer's ({@link Toolbox68k} and the managers), reached the way the real
 * InterfaceLib reached most of it - through the 68K trap. Each imported system-library function becomes a host gate
 * whose native routine takes the PowerPC arguments (r3-r10 and the stack), lays them out as the 68K interface
 * does (pushed Pascal arguments, a result slot, or the registers of #pragma parameter) and runs the function's 68K
 * inline words, as listed in the Universal Interfaces ({@link PpcInterfaceTable}). Toolbox callbacks to PowerPC code
 * go through routine descriptors: the 68K side meets the _MixedModeMagic word ($AAFE) at the start of one and
 * {@link #mixedModeTrap} calls the PowerPC routine with the arguments its procInfo describes. Functions that are glue
 * in the 68K libraries (File Manager shortcuts, C strings, CFM, Mixed Mode, MathLib, StdCLib ...) are implemented in
 * {@link PpcLibraries}.
 *
 * Code fragments are PEF containers ({@link Pef}) from the application's data fork (its 'cfrg' resource says where),
 * from import libraries in the application's own file or folder, or from the Extensions folder.
 */
final class PpcRuntime implements PpcCpu.Natives,PpcCpu.Scheduler {
	/** -Dhzppc.trace=true logs every native call and its result; the snapshot command file toggles it at run time ("ppctrace on|off"). */
	static volatile boolean TRACE=Boolean.getBoolean("hzppc.trace");
	/** Debug aid: -Dhzppc.traceonly=Name|Name limits the -Dhzppc.trace native-call lines to those routines. */
	private static final String TRACE_ONLY=System.getProperty("hzppc.traceonly");
	private boolean traced(int index){return TRACE_ONLY==null||("|"+TRACE_ONLY+"|").contains("|"+nativeNames.get(index)+"|");}
	/** -Dhzppc.strict=true: an imported function that is not implemented stops the program instead of returning 0. */
	static final boolean STRICT=Boolean.getBoolean("hzppc.strict");
	static final int CFRG=ResourceFile68k.fourcc("cfrg");
	static final int ARCH_PPC=0x70777063;
	static final int USAGE_LIBRARY=0,USAGE_APPLICATION=1,USAGE_DROPIN=2;
	static final int NO_ERR=0,PARAM_ERR=-50,CFRAG_NO_SYMBOL=-2802,CFRAG_NO_LIBRARY=-2804,CFRAG_CONNECTION_ID=-2801,CFRAG_FORMAT=-2806,CFRAG_UNRESOLVED=-2807,CFRAG_NO_APPLICATION=-2822;

	final MacApp68k app;
	final Toolbox68k toolbox;
	final M68kMemory memory;
	final M68kCpu cpu68;
	final PpcCpu cpu;
	final PpcLibraries libraries;
	/** The PowerPC stack (r1 starts near its top). */
	int stackBase,stackTop;

	PpcRuntime(MacApp68k app){
		this.app=app;toolbox=app.toolbox;memory=app.memory;cpu68=app.cpu;
		cpu=new PpcCpu(memory,this);cpu.setScheduler(this);
		libraries=new PpcLibraries(this);
	}

	// ------------------------------------------------------------------ application files

	/** A member of a 'cfrg' resource. */
	static final class FragmentEntry {
		int architecture,usage,where,offset,length,stackSize,updateLevel,currentVersion,oldDefVersion,forkKind;String name;
	}
	static List<FragmentEntry> fragmentEntries(ResourceFile68k rf){
		List<FragmentEntry> list=new ArrayList<FragmentEntry>();
		ResourceFile68k.Resource r=rf==null?null:rf.get(CFRG,0);
		if(r==null)return list;
		byte[] c=r.data();
		if(c.length<32)return list;
		int count=u2(c,30),p=32;
		for(int i=0;i<count&&p+43<=c.length;i++){
			FragmentEntry e=new FragmentEntry();
			e.architecture=u4(c,p);e.updateLevel=c[p+7]&0xff;e.currentVersion=u4(c,p+8);e.oldDefVersion=u4(c,p+12);e.stackSize=u4(c,p+16);
			e.usage=c[p+22]&0xff;e.where=c[p+23]&0xff;e.offset=u4(c,p+24);e.length=u4(c,p+28);e.forkKind=u4(c,p+32);
			int size=u2(c,p+40);int n=c[p+42]&0xff;
			e.name=new String(c,p+43,Math.min(n,Math.max(0,c.length-p-43)),java.nio.charset.Charset.forName("MacRoman"));
			list.add(e);
			if(size<=0)break;p+=size;
		}
		return list;
	}
	/** The application member of a 'cfrg' (PowerPC code in the data fork), or null. */
	static FragmentEntry applicationFragment(ResourceFile68k rf){
		for(FragmentEntry e:fragmentEntries(rf))if(e.architecture==ARCH_PPC&&e.usage==USAGE_APPLICATION)return e;
		return null;
	}
	/** The data fork of a file (the one inside a MacBinary container when it is one). */
	static byte[] dataFork(File file) throws IOException {
		ResourceFile68k.MacBinary mb=ResourceFile68k.MacBinary.read(file);
		if(mb!=null)return mb.dataFork;
		return java.nio.file.Files.readAllBytes(file.toPath());
	}
	private static int u2(byte[] b,int o){return (b[o]&0xff)<<8|b[o+1]&0xff;}
	private static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;}

	// ------------------------------------------------------------------ memory

	/** A cleared block of guest memory (a Ptr of the Memory Manager's heap). */
	int allocate(int size,int alignment) throws M68kCpuException {
		if(alignment<=4)return memory.allocate(Math.max(4,size),4); // zeroed by the allocator
		int at=memory.allocate(Math.max(4,size)+alignment,4); // (sections are never freed: the slack before the aligned start stays with the block)
		return (at+alignment-1)&-alignment;
	}
	String cString(int p) throws M68kCpuException {
		if(p==0)return null;
		StringBuilder sb=new StringBuilder();
		for(int i=0;i<65536;i++){int b=memory.readByte(p+i);if(b==0)break;sb.append((char)b);}
		return sb.toString();
	}
	/** Bytes of a C string (no terminator). */
	byte[] cBytes(int p) throws M68kCpuException {int n=0;while(memory.readByte(p+n)!=0&&n<1<<20)n++;return memory.readBytes(p,n);}
	byte[] pBytes(int p) throws M68kCpuException {int n=memory.readByte(p);return memory.readBytes(p+1,n);}
	/** A Pascal string as Mac Roman text (library and symbol names). */
	String pString(int p) throws M68kCpuException {return p==0?null:new String(pBytes(p),java.nio.charset.Charset.forName("MacRoman"));}
	void writePString(int p,String s) throws M68kCpuException {byte[] b=s.getBytes(java.nio.charset.Charset.forName("MacRoman"));int n=Math.min(255,b.length);memory.writeByte(p,n);memory.load(p+1,b,0,n);}

	// ------------------------------------------------------------------ native routines

	/** A routine implemented in Java, reached from PowerPC code through a host gate. */
	interface Native { void call(PpcRuntime rt,PpcCpu cpu) throws M68kException; }
	private final List<Native> natives=new ArrayList<Native>();
	private final List<String> nativeNames=new ArrayList<String>();
	private int gateChunk,gateFree;
	/** Transition vectors made for native routines, by "library/name". */
	private final Map<String,Integer> nativeVectors=new HashMap<String,Integer>();

	/** Makes a transition vector whose code is a host gate calling {@code n}; its TOC word is the native's index. */
	int nativeVector(String name,Native n) throws M68kCpuException {
		int index=natives.size();natives.add(n);nativeNames.add(name);
		if(gateChunk==0||gateFree+12>4096){gateChunk=allocate(4096,16);gateFree=0;}
		int code=gateChunk+gateFree,tv=code+4;gateFree+=12;
		memory.writeLong(code,0x04000000|index);
		memory.writeLong(tv,code);memory.writeLong(tv+4,index);
		return tv;
	}
	private int traceGestaltResponse,traceOut;
	String nativeName(int index){return index>=0&&index<nativeNames.size()?nativeNames.get(index):"?";}
	/** Debug aid: -Dhzppc.watch=hexaddress reports a change of the long there, checked at every native call (before: the
	 *  PowerPC code wrote it since the last call; after: the call did) - the 68K side's -Dhz68k.watch misses PowerPC writes. */
	private static final int WATCH=(int)Long.parseLong(System.getProperty("hzppc.watch","0"),16);private int watchValue;
	/** Debug aid: -Dhzppc.backtrace=NativeName[|Name...] (or *) prints the PowerPC callers (saved LRs along the stack back chain) at each call. */
	private static final String BACKTRACE=System.getProperty("hzppc.backtrace");
	/** For the trace: the rectangle a QuickDraw *Rect/Plot call draws, SetClip's region bounds, RGB colours, an FSp call's FSSpec. */
	private String traceRect(String name,PpcCpu c){
		try{
			if((name.equals("FSMakeFSSpec")&&c.r[5]!=0)||(name.startsWith("PB")&&name.contains("CatInfo")&&c.r[3]!=0&&memory.readLong(c.r[3]+18)!=0)){
				int p=name.equals("FSMakeFSSpec")?c.r[5]:memory.readLong(c.r[3]+18);int n=memory.readByte(p);byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)memory.readByte(p+1+i);
				return String.format(" name %s%s",new String(b,java.nio.charset.Charset.forName("Shift_JIS")),name.startsWith("PB")?String.format(" vol %d dir %d index %d",(short)memory.readWord(c.r[3]+22),memory.readLong(c.r[3]+48),(short)memory.readWord(c.r[3]+28)):"");}
			if((name.equals("PMSetResolution")||name.equals("PMPrinterGetIndexedPrinterResolution"))&&c.r[name.equals("PMSetResolution")?4:5]!=0){int p=c.r[name.equals("PMSetResolution")?4:5];
				return String.format(" res %08X%08X %08X%08X",memory.readLong(p),memory.readLong(p+4),memory.readLong(p+8),memory.readLong(p+12));}
			if(name.equals("PMSessionGeneral")&&c.r[4]!=0){StringBuilder sb=new StringBuilder(" data");for(int i=0;i<32;i+=2)sb.append(String.format(" %04X",memory.readWord(c.r[4]+i)));return sb.toString();}
			if(name.equals("CopyPascalStringToC")&&c.r[3]!=0){int p=c.r[3];int n=memory.readByte(p);byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)memory.readByte(p+1+i);
				return " \""+new String(b,java.nio.charset.Charset.forName("Shift_JIS"))+"\"";}
			if(name.equals("HOpenResFile")&&c.r[5]!=0){int p=c.r[5];int n=memory.readByte(p);byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)memory.readByte(p+1+i);
				return String.format(" name %s",new String(b,java.nio.charset.Charset.forName("Shift_JIS")));}
			if(name.startsWith("FSp")&&c.r[3]!=0){int p=c.r[3];int n=Math.min(63,memory.readByte(p+6));byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)memory.readByte(p+7+i);
				return String.format(" spec %d:%d:%s",(short)memory.readWord(p),memory.readLong(p+2),new String(b,java.nio.charset.Charset.forName("Shift_JIS")));}
			if(name.equals("RGBForeColor")||name.equals("RGBBackColor")){int p=c.r[3];return String.format(" rgb %04X %04X %04X",memory.readWord(p),memory.readWord(p+2),memory.readWord(p+4));}
			if(name.equals("SetClip")){int h=c.r[3],d=h==0?0:memory.readLong(h);if(d==0)return "";return String.format(" rgn %d,%d,%d,%d size %d",(short)memory.readWord(d+2),(short)memory.readWord(d+4),(short)memory.readWord(d+6),(short)memory.readWord(d+8),memory.readWord(d));}
			if(name.equals("SectRect")||name.equals("UnionRect")||name.equals("EqualRect")||name.equals("InsetRect")||name.equals("OffsetRect")||name.equals("RectInRgn")||name.equals("PinRect")){
				StringBuilder sb=new StringBuilder(" rect");int[] ps=name.equals("SectRect")||name.equals("UnionRect")||name.equals("EqualRect")?new int[]{c.r[3],c.r[4]}:new int[]{c.r[3]};
				for(int p:ps)sb.append(String.format(" %d,%d,%d,%d",(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)));return sb.toString();}
			boolean rect=name.endsWith("Rect")&&(name.startsWith("Frame")||name.startsWith("Paint")||name.startsWith("Erase")||name.startsWith("Fill")||name.startsWith("Invert")||name.startsWith("Clip"));
			if(!rect&&!name.equals("PlotCIcon")&&!name.equals("PlotIconID")&&!name.equals("PlotIconRef"))return "";
			int p=c.r[3];return String.format(" rect %d,%d,%d,%d",(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6));}catch(M68kCpuException e){return "";}
	}
	private String backtrace(PpcCpu c){
		StringBuilder sb=new StringBuilder(String.format("%08X",c.lr));int sp=c.r[1];
		try{for(int i=0;i<24&&sp!=0;i++){int next=memory.readLong(sp);if(next==0||next<=sp)break;sb.append(String.format(" %08X",memory.readLong(next+8)));sp=next;}}catch(M68kCpuException e){sb.append(" ?");}
		return sb.toString();
	}
	private void checkWatch(String where,int index,PpcCpu c) throws M68kCpuException {
		int now=memory.readLong(WATCH);if(now==watchValue)return;
		System.err.printf("[ppc watch] $%08X: $%08X -> $%08X %s %s lr=%08X%n",WATCH,watchValue,now,where,nativeNames.get(index),c.lr);watchValue=now;
	}
	@Override public void call(PpcCpu c,int index) throws M68kException {
		if(index>=natives.size())throw new M68kCpuException(c.lastPc(),"host gate "+index+" is not defined");
		if(WATCH!=0)checkWatch("before",index,c);
		boolean trace=TRACE&&traced(index);
		if(trace){String n=nativeNames.get(index);System.err.printf("[ppc] %s(%08X %08X %08X %08X %08X) lr=%08X%s%n",n,c.r[3],c.r[4],c.r[5],c.r[6],c.r[7],c.lr,traceRect(n,c));}
		if(BACKTRACE!=null&&(BACKTRACE.equals("*")||("|"+BACKTRACE+"|").contains("|"+nativeNames.get(index)+"|")))System.err.println("[ppc] "+nativeNames.get(index)+" called from "+backtrace(c));
		cpu68.traps++;
		if(trace){String n=nativeNames.get(index);traceGestaltResponse=n.equals("Gestalt")?c.r[4]:0;traceOut=n.equals("GetPortBounds")||n.startsWith("PMGet")&&n.contains("Rect")||n.equals("PMGetResolution")?c.r[4]:n.equals("SectRect")||n.equals("UnionRect")?c.r[5]:n.equals("InsetRect")||n.equals("OffsetRect")?c.r[3]:0;}
		int shift=stackArgShift;stackArgShift=0; // a native called back from inside another one reads its own arguments
		try{
			if(STATS){statCall(index,c);return;}
			natives.get(index).call(this,c);
		}finally{stackArgShift=shift;}
		if(WATCH!=0)checkWatch("after",index,c);
		if(trace&&traceOut!=0){String n=nativeNames.get(index);StringBuilder sb=new StringBuilder("[ppc]   "+n+" out");
			if(n.equals("GetPortBounds")||n.endsWith("Rect"))for(int i=0;i<8;i+=2)sb.append(' ').append((short)memory.readWord(traceOut+i));
			else for(int i=0;i<(n.equals("PMGetResolution")?16:32);i+=8)sb.append(' ').append(Double.longBitsToDouble(((long)memory.readLong(traceOut)<<32|(memory.readLong(traceOut+4)&0xffffffffL))*0+(((long)memory.readLong(traceOut+i))<<32|(memory.readLong(traceOut+i+4)&0xffffffffL))));
			System.err.println(sb);}
		if(trace)System.err.printf("[ppc]   %s -> %08X%s%n",nativeNames.get(index),c.r[3],nativeNames.get(index).equals("Gestalt")&&traceGestaltResponse!=0?String.format(" (response %08X)",memory.readLong(traceGestaltResponse)):"");
	}

	/** Integer argument {@code i} (0-based) of the native call in progress: r3-r10, then the caller's parameter area. */
	int arg(int i) throws M68kCpuException {return i<8?cpu.r[3+i]:memory.readLong(cpu.r[1]+24+4*(i+stackArgShift));}
	/** Words of the caller's parameter area in front of the arguments: 1 while a native runs for a caller that passed one
	 *  more leading argument (PpcOpenGL's aglMacro.h dispatch table: the context comes first, the registers are moved down). */
	int stackArgShift;
	/** Double argument {@code i} (f1-f13). */
	double farg(int i){return Double.longBitsToDouble(cpu.f[1+i]);}
	void ret(int v){cpu.r[3]=v;}
	void retD(double v){cpu.f[1]=Double.doubleToRawLongBits(v);}

	// ------------------------------------------------------------------ the Toolbox bridge (68K inline words)

	/** One Toolbox function as the Universal Interfaces declare it (see {@link PpcInterfaceTable}). */
	static final class Declaration {
		final String name;final boolean pascal;final String result;final String[] params;
		/** 68K register (0-7 D0-D7, 8-15 A0-A7) of each parameter, -1 = on the stack; null when none are in registers. */
		final int[] paramRegs;final int resultReg;final int[] words;
		Declaration(String line){
			String[] f=line.split(" ");
			name=f[0];pascal=f[1].equals("P");result=f[2];
			params=f[3].equals("-")?new String[0]:f[3].split(",");
			int rr=-1;int[] pr=null;
			if(!f[4].equals("-")){
				int colon=f[4].indexOf(':');String res=f[4].substring(0,colon),args=f[4].substring(colon+1);
				rr=res.isEmpty()?-1:reg(res);
				String[] a=args.isEmpty()?new String[0]:args.split(",");
				pr=new int[params.length];java.util.Arrays.fill(pr,-1);
				for(int i=0;i<a.length&&i<pr.length;i++)pr[i]=reg(a[i]);
			}
			paramRegs=pr;resultReg=rr;
			if(f[5].equals("-"))words=null;else{String[] w=f[5].split(",");words=new int[w.length];for(int i=0;i<w.length;i++)words[i]=Integer.parseInt(w[i],16);}
		}
		static int reg(String r){r=r.trim().toUpperCase();int n=r.charAt(1)-'0';return r.charAt(0)=='A'?8+n:n;}
		boolean bridgeable(){
			if(words==null||words.length==0)return false;
			for(String p:params)if(p.equals("...")||p.equals("f")||p.equals("d")||p.equals("w"))return false;
			return !(result.equals("f")||result.equals("d")||result.equals("w")||result.startsWith("s")&&!result.equals("s4")&&!result.equals("s2")&&!result.equals("s1"));
		}
	}
	private static Map<String,String> declarationLines;
	private static Map<String,Integer> uppProcInfos;
	/** The procInfo of UPP type {@code name} (upp<name>ProcInfo in the headers), or null. */
	static synchronized Integer uppProcInfo(String name){declaration("");return uppProcInfos.get(name);}
	/** The Universal Interfaces declaration of {@code name}, or null. */
	static synchronized Declaration declaration(String name){
		if(declarationLines==null){
			Map<String,String> m=new HashMap<String,String>(16384);Map<String,Integer> u=new HashMap<String,Integer>(2048);
			for(String chunk:PpcInterfaceTable.chunks())for(String line:chunk.split("\n")){int sp=line.indexOf(' ');if(sp<=0)continue;
				if(line.charAt(0)=='@')u.put(line.substring(1,sp),(int)Long.parseLong(line.substring(sp+1).trim(),16));else m.put(line.substring(0,sp),line);}
			uppProcInfos=u;
			for(String line:PpcLibraries.MANUAL_GLUE){int sp=line.indexOf(' ');if(sp>0)m.put(line.substring(0,sp),line);}
			// the Communications Toolbox: each routine's declaration with its _CommToolboxDispatch selector as the glue
			for(Map.Entry<String,Integer> e:CommToolbox68k.SELECTORS.entrySet()){String line=m.get(e.getKey());if(line!=null)m.put(e.getKey(),CommToolbox68k.glueLine(line,e.getValue()));}
			declarationLines=m;
		}
		String line=declarationLines.get(name);
		return line==null?null:new Declaration(line);
	}

	/** A native that runs a Toolbox function's 68K inline words with the arguments laid out as the 68K interface wants them. */
	final class Glue implements Native {
		final Declaration d;int stub;
		Glue(Declaration d){this.d=d;}
		@Override public void call(PpcRuntime rt,PpcCpu c) throws M68kException {c.r[3]=callToolbox(this,null);}
	}
	/** Makes (once) the 68K code of a glue: its inline words, then a jump to the 68K emulator's return sentinel. */
	private int stubFor(Glue g) throws M68kCpuException {
		if(g.stub!=0)return g.stub;
		int[] w=g.d.words;int at=allocate(w.length*2+6,4);
		for(int i=0;i<w.length;i++)memory.writeWord(at+2*i,w[i]);
		memory.writeWord(at+2*w.length,0x4ef9);memory.writeLong(at+2*w.length+2,M68kCpu.RETURN_SENTINEL);
		g.stub=at;return at;
	}
	/** Makes (once) 68K code that JSRs to {@code address} and then ends the run. */
	private int jsrStub(int address) throws M68kCpuException {
		Integer stub=jsrStubs.get(address);if(stub!=null)return stub;
		int at=allocate(12,4);memory.writeWord(at,0x4eb9);memory.writeLong(at+2,address);memory.writeWord(at+6,0x4ef9);memory.writeLong(at+8,M68kCpu.RETURN_SENTINEL);
		jsrStubs.put(address,at);return at;
	}
	private final Map<Integer,Integer> jsrStubs=new HashMap<Integer,Integer>();
	private static Map<Integer,String> trapFunctions;
	/**
	 * The glue of the stack-based Pascal Toolbox function whose whole inline glue is trap {@code word} (no selector), or null
	 * when there is none or its declarations disagree: what the trap's GetTrapAddress address is called as.
	 */
	private Glue trapGlue(int word){
		synchronized(PpcRuntime.class){
			if(trapFunctions==null){
				declaration("");Map<Integer,String> byWord=new HashMap<Integer,String>(),signature=new HashMap<Integer,String>();
				for(Map.Entry<String,String> e:declarationLines.entrySet()){
					Declaration d;try{d=declaration(e.getKey());}catch(RuntimeException ex){continue;}
					if(d.words==null||d.words.length!=1||(d.words[0]&0xf800)!=0xa800||!d.pascal||d.paramRegs!=null||d.resultReg>=0||!d.bridgeable())continue;
					String line=e.getValue(),sig=line.substring(line.indexOf(' '));
					String seen=signature.putIfAbsent(d.words[0],sig);
					if(seen==null)byWord.put(d.words[0],d.name);else if(!seen.equals(sig))byWord.put(d.words[0],null); // two meanings: leave it to procInfo
				}
				trapFunctions=byWord;
			}
		}
		String name=trapFunctions.get(word&0xfbff); // the auto-pop bit does not change the routine
		return name==null?null:glue(name);
	}
	private final Map<String,Glue> glues=new HashMap<String,Glue>();
	/** The bridge for a Toolbox function by name (for Java code calling the Toolbox), or null. */
	Glue glue(String name){
		Glue g=glues.get(name);if(g!=null)return g;
		Declaration d=declaration(name);if(d==null||!d.bridgeable())return null;
		g=new Glue(d);glues.put(name,g);return g;
	}
	/** Calls a Toolbox function from Java with {@code args} in place of the PowerPC argument registers; returns its result. */
	int toolbox(String name,int... args) throws M68kException {
		Glue g=glue(name);if(g==null)throw new M68kCpuException(cpu.pc,"no 68K interface for "+name);
		return callToolbox(g,args);
	}

	private static int stackBytes(String kind){
		switch(kind){case "1":case "B":case "2":case "H":case "s1":case "s2":return 2;case "v":return 0;default:return 4;}
	}
	/**
	 * The bridge proper. {@code given} replaces the PowerPC arguments (a call from Java), else they are read from r3-r10
	 * and the stack. Pascal arguments are pushed left to right below a result slot; C arguments right to left, a long each;
	 * register arguments go to their registers. The 68K stack pointer is back where it was afterwards.
	 */
	/** The Toolbox call PowerPC code is making through callToolbox (null outside one): 68K code it runs is the system's, not the program's. */
	String toolboxCall;
	int callToolbox(Glue g,int[] given) throws M68kException {return callToolbox(g,given,0);}
	/** As above; a nonzero {@code romEntry} is JSRed to in place of the inline words (the trap as it was before any patch). */
	private int callToolbox(Glue g,int[] given,int romEntry) throws M68kException {
		Declaration d=g.d;
		int n=d.params.length;
		int entrySp=cpu68.a[7];
		int[] values=new int[n];
		int[] structCopy=null;
		// a structure result (NumVersion, Point) comes back through a buffer whose address the caller passes first, in r3
		boolean hiddenResult=given==null&&d.result.startsWith("s");
		int slot=hiddenResult?1:0;
		for(int i=0;i<n;i++){
			String k=d.params[i];
			if(k.startsWith("s")&&!k.equals("s1")&&!k.equals("s2")&&!k.equals("s4")){ // a structure larger than a long, passed by value
				int size=Integer.parseInt(k.substring(1));int words=(size+3)/4;
				if(structCopy==null)structCopy=new int[n];
				byte[] b=new byte[words*4];
				for(int w=0;w<words;w++){int v=given!=null?given[slot]:arg(slot);slot++;b[4*w]=(byte)(v>>>24);b[4*w+1]=(byte)(v>>>16);b[4*w+2]=(byte)(v>>>8);b[4*w+3]=(byte)v;}
				int sp=cpu68.a[7]-((size+1)&~1);cpu68.a[7]=sp;memory.load(sp,b,0,size);structCopy[i]=sp;values[i]=sp;
				continue;
			}
			int v=given!=null?(slot<given.length?given[slot]:0):arg(slot);slot++;
			if(k.equals("s1"))v=v>>>24;else if(k.equals("s2"))v=v>>>16;
			values[i]=v;
		}
		int resultSlot=0,argumentBytes=0;
		boolean stackResult=d.resultReg<0&&!d.result.equals("v");
		if(d.pascal){
			if(stackResult){int rs=stackBytes(d.result);cpu68.a[7]-=rs;resultSlot=cpu68.a[7];memory.fill(resultSlot,rs,0);}
			int top=cpu68.a[7];
			for(int i=0;i<n;i++){
				if(d.paramRegs!=null&&d.paramRegs[i]>=0)continue;
				String k=d.params[i];int v=values[i];
				switch(stackBytes(k)){
					case 2:cpu68.a[7]-=2;if(k.equals("1")||k.equals("B")||k.equals("s1"))memory.writeWord(cpu68.a[7],(v&0xff)<<8);else memory.writeWord(cpu68.a[7],v);break;
					default:cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],v);break;
				}
			}
			argumentBytes=top-cpu68.a[7];
		}else{
			for(int i=n-1;i>=0;i--){
				if(d.paramRegs!=null&&d.paramRegs[i]>=0)continue;
				String k=d.params[i];int v=values[i];
				if(k.equals("1"))v=(byte)v;else if(k.equals("B"))v&=0xff;else if(k.equals("2"))v=(short)v;else if(k.equals("H"))v&=0xffff;
				cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],v);
			}
		}
		if(d.paramRegs!=null)for(int i=0;i<n;i++){int r=d.paramRegs[i];if(r<0)continue;if(r<8)cpu68.d[r]=values[i];else cpu68.a[r-8]=values[i];}
		int result=0;
		String outerCall=toolboxCall;toolboxCall=d.name;
		try{
			cpu68.runUntilSentinel(romEntry!=0?jsrStub(romEntry):stubFor(g),d.pascal?argumentBytes:0);
			if(d.resultReg>=0){int r=d.resultReg;result=r<8?cpu68.d[r]:cpu68.a[r-8];}
			else if(!d.pascal)result=cpu68.d[0];
			else if(stackResult){
				switch(stackBytes(d.result)){case 2:result=memory.readWord(resultSlot);break;default:result=memory.readLong(resultSlot);break;}
				if(d.result.equals("1")||d.result.equals("B")||d.result.equals("s1"))result>>>=8;
			}
		}finally{cpu68.a[7]=entrySp;toolboxCall=outerCall;}
		if(hiddenResult){
			int buffer=arg(0);
			switch(d.result){case "s1":memory.writeByte(buffer,result);break;case "s2":memory.writeWord(buffer,result);break;default:memory.writeLong(buffer,result);break;}
			return buffer;
		}
		switch(d.result){
			case "1":return (byte)result;
			case "B":return result&0xff;
			case "2":return (short)result;
			case "H":return result&0xffff;
			case "s2":return result<<16;
			case "s1":return result<<24;
			case "v":return 0;
			default:return result;
		}
	}

	/** Runs one 68K trap word with the registers given ({@code d0}, {@code a0}, {@code a1}) and returns D0 (A0 in {@link #lastA0}). */
	int trap(int word,int d0,int a0,int a1) throws M68kException {
		Integer stub=trapStubs.get(word);
		if(stub==null){int at=allocate(8,4);memory.writeWord(at,word);memory.writeWord(at+2,0x4ef9);memory.writeLong(at+4,M68kCpu.RETURN_SENTINEL);stub=at;trapStubs.put(word,at);}
		int sp=cpu68.a[7];
		cpu68.d[0]=d0;cpu68.a[0]=a0;cpu68.a[1]=a1;
		try{cpu68.runUntilSentinel(stub,0);}finally{syncEmulatorContext();lastA0=cpu68.a[0];lastD1=cpu68.d[1];cpu68.a[7]=sp;}
		return cpu68.d[0];
	}
	int lastA0,lastD1;
	/**
	 * Runs 68K {@code words} (then the sentinel) with {@code block} bytes at {@code source} copied onto the 68K stack below a
	 * {@code resultBytes} result slot, as a trap reached from 68K code finds its arguments; returns the result slot's
	 * contents (a long) or D0 when there is no slot. Stubs are cached by their words.
	 */
	int runOnStack(int[] words,int source,int block,int resultBytes) throws M68kException {
		String key=java.util.Arrays.toString(words);
		Integer stub=wordStubs.get(key);
		if(stub==null){int at=allocate(words.length*2+6,4);for(int i=0;i<words.length;i++)memory.writeWord(at+2*i,words[i]);memory.writeWord(at+2*words.length,0x4ef9);memory.writeLong(at+2*words.length+2,M68kCpu.RETURN_SENTINEL);stub=at;wordStubs.put(key,at);}
		int sp=cpu68.a[7];
		try{
			cpu68.a[7]-=resultBytes;int slot=cpu68.a[7];if(resultBytes>0)memory.fill(slot,resultBytes,0);
			cpu68.a[7]-=(block+1)&~1;if(block>0)memory.copy(source,cpu68.a[7],block);
			cpu68.runUntilSentinel(stub,block);syncEmulatorContext();
			return resultBytes==4?memory.readLong(slot):resultBytes==2?(short)memory.readWord(slot):cpu68.d[0];
		}finally{cpu68.a[7]=sp;}
	}
	private final Map<String,Integer> wordStubs=new HashMap<String,Integer>();
	private final Map<Integer,Integer> trapStubs=new HashMap<Integer,Integer>();

	// ------------------------------------------------------------------ Mixed Mode

	static final int MIXED_MODE_MAGIC=0xaafe;
	static final int ISA_68K=0,ISA_PPC=1;
	static final int RD_HEADER=12,RD_RECORD=20;
	static final int PROC_IS_RELATIVE=0x01,FRAGMENT_NEEDS_PREPARING=0x02;

	boolean isRoutineDescriptor(int p){
		try{return p!=0&&(p&1)==0&&memory.readWord(p)==MIXED_MODE_MAGIC&&memory.readByte(p+2)>=5;}catch(M68kCpuException e){return false;}
	}
	/** A routine descriptor of one routine (NewRoutineDescriptor); {@code isa} 0 = 68K, 1 = PowerPC. */
	int newRoutineDescriptor(int proc,int procInfo,int isa) throws M68kCpuException {
		int rd=allocate(RD_HEADER+RD_RECORD,4);
		memory.writeWord(rd,MIXED_MODE_MAGIC);memory.writeByte(rd+2,7);memory.writeByte(rd+3,0);memory.writeWord(rd+10,0);
		int r=rd+RD_HEADER;
		memory.writeLong(r,procInfo);memory.writeByte(r+5,isa);memory.writeWord(r+6,isa==ISA_PPC?0x04:0);memory.writeLong(r+8,proc);
		return rd;
	}
	/** Whether {@code rd} is a routine descriptor (version 5 or later) with a PowerPC routine. */
	static boolean isPowerPcDescriptor(M68kMemory memory,int rd) throws M68kCpuException {
		if(memory.readWord(rd)!=MIXED_MODE_MAGIC||memory.readByte(rd+2)<5)return false;
		int count=memory.readWord(rd+10)+1;
		for(int i=0;i<count&&i<64;i++){int r=rd+RD_HEADER+i*RD_RECORD;if((memory.readByte(r+5)&0x0f)==ISA_PPC)return true;}
		return false;
	}
	/** Offset of the routine record of the given ISA in a routine descriptor, or -1. */
	int findRecord(int rd,int isa) throws M68kCpuException {
		int count=memory.readWord(rd+10)+1;
		for(int i=0;i<count&&i<64;i++){int r=rd+RD_HEADER+i*RD_RECORD;if((memory.readByte(r+5)&0x0f)==isa)return r;}
		return -1;
	}
	/**
	 * The transition vector a PowerPC routine record calls, preparing the fragment it points at when it is one ('fat' code
	 * resources). The descriptor is left as it is: it lives in a resource Handle that may move. HyperCard 2.4 MoveHHis and
	 * locks its 'xcmd' before every call and unlocks it afterwards; with the prepared TVector written back into the record,
	 * the moved copy kept calling code at the old address, whose block the next resource loaded over (Roundup's AddColor ran
	 * into its own PICT). Prepared fragments are remembered by container address instead and checked against its bytes.
	 */
	int recordTarget(int rd,int record) throws M68kException {
		int flags=memory.readWord(record+6),proc=memory.readLong(record+8);
		if((flags&PROC_IS_RELATIVE)!=0)proc+=rd;
		if((flags&FRAGMENT_NEEDS_PREPARING)!=0){
			Fragment f=preparedCodeResource(proc,rd);
			proc=f.instance.main();
			if(proc==0)throw new M68kCpuException(rd,"code resource fragment without a main routine");
		}
		return proc;
	}
	private static final int CODE_RESOURCE_SIGNATURE=512;
	private static final class PreparedCodeResource{final byte[] signature;final Fragment fragment;PreparedCodeResource(byte[] s,Fragment f){signature=s;fragment=f;}}
	private final Map<Integer,PreparedCodeResource> preparedCodeResources=new HashMap<Integer,PreparedCodeResource>();
	/** The fragment of a routine descriptor's code resource container at {@code container}: the one prepared there before while the bytes are the same (its globals live on, as the CFM keeps one connection per container), else a new one. */
	private Fragment preparedCodeResource(int container,int rd) throws M68kException {
		int n=Math.min(CODE_RESOURCE_SIGNATURE,guessContainerLength(container));
		byte[] signature=memory.readBytes(container,n);
		PreparedCodeResource p=preparedCodeResources.get(container);
		if(p!=null&&Arrays.equals(p.signature,signature))return p.fragment;
		Fragment f=loadMemoryFragment(container,0,"code resource at $"+Integer.toHexString(rd));
		preparedCodeResources.put(container,new PreparedCodeResource(signature,f));
		return f;
	}

	static final int SELECTORS_ARE_INDEXABLE=0x01,DONT_PASS_SELECTOR=0x08,DISPATCHED_DEFAULT_ROUTINE=0x10;
	/**
	 * The record of a dispatched routine descriptor (selectorInfo = the calling convention the selector comes in: 8 D0,
	 * 12 D1, 14 on the stack, 9 D0 with C arguments) for {@code selector}: the record at that index when the selectors are
	 * indexable, else the one whose selector field matches (PowerPC preferred), else the dispatched default routine.
	 * QuickTime 3+ on a Power Mac installs QTMathPPC as one descriptor of 36 routines its 68K code calls with the selector in
	 * D0 - calling the first record for every selector corrupted movie state (MoviePlayer crashed opening a movie).
	 */
	int dispatchedRecord(int rd,int selector) throws M68kCpuException {
		int count=memory.readWord(rd+10)+1;
		if((memory.readByte(rd+3)&SELECTORS_ARE_INDEXABLE)!=0)return selector>=0&&selector<count?rd+RD_HEADER+selector*RD_RECORD:-1;
		int match=-1,fallback=-1;
		for(int pass=0;pass<2&&match<0;pass++)for(int i=0;i<count&&i<1024;i++){
			int r=rd+RD_HEADER+i*RD_RECORD;
			int sel=memory.readLong(r+16);
			boolean hit=pass==0?sel==selector:(short)sel==(short)selector;
			if(hit&&(match<0||(memory.readByte(r+5)&0x0f)==ISA_PPC))match=r;
			if(fallback<0&&(memory.readWord(r+6)&DISPATCHED_DEFAULT_ROUTINE)!=0)fallback=r;
		}
		return match>=0?match:fallback;
	}

	private static int sizeCode(int code){return code==1?1:code==2?2:code==3?4:0;}
	private static int pascalSlot(int code){return code==0?0:code==3?4:2;}
	/** A 68K register or condition code by Mixed Mode location number (0-3 D0-D3, 4-7 A0-A3, 8-11 D4-D7, 12-14 A4-A6). */
	private static int regIndex(int location){return location<4?location:location<8?8+location-4:location<12?location-4:location<15?8+location-8:-1;}
	private int readReg(int location){int r=regIndex(location);if(r<0)return 0;return r<8?cpu68.d[r]:cpu68.a[r-8];}
	private void writeReg(int location,int value,int size){
		if(location>=16){int bit=location==16?M68kCpu.FLAG_C:location==17?M68kCpu.FLAG_V:location==18?M68kCpu.FLAG_Z:location==19?M68kCpu.FLAG_N:M68kCpu.FLAG_X;if(value!=0)cpu68.sr|=bit;else cpu68.sr&=~bit;return;}
		int r=regIndex(location);if(r<0)return;
		int old=r<8?cpu68.d[r]:cpu68.a[r-8];
		int v=size==1?(old&~0xff)|(value&0xff):size==2?(old&~0xffff)|(value&0xffff):value;
		if(r<8)cpu68.d[r]=v;else cpu68.a[r-8]=v;
	}
	private static int extend(int v,int size){return size==1?v&0xff:size==2?(short)v:v;}

	/**
	 * _MixedModeMagic ($AAFE) reached by 68K code at {@code rd}: the 68K code called a routine descriptor. A PowerPC
	 * routine is called with the arguments the procInfo describes (from the 68K stack or registers), its result goes
	 * back where the 68K caller expects it, and execution continues at the caller's return address. A descriptor with only
	 * 68K code continues there.
	 */
	/** D0 after the last register-based 68K call made from a register-based PowerPC routine that 68K code called (-1: none). */
	private long nestedRegisterD0=-1;
	boolean mixedModeTrap(M68kCpu c,int rd) throws M68kException {
		syncEmulatorContext();
		int selectorInfo=memory.readByte(rd+9);
		int record,stackSelectorBytes=0;boolean passSelector=true;
		if(selectorInfo!=0){
			int selector=selectorInfo==12?c.d[1]:selectorInfo==14?(short)memory.readWord(c.a[7]+4):c.d[0];
			record=dispatchedRecord(rd,selector);
			if(record<0)throw new M68kCpuException(rd,String.format("dispatched routine descriptor without a routine for selector %d",selector));
			if((memory.readByte(record+5)&0x0f)!=ISA_PPC){ // 68K code gets the selector where the caller put it
				int flags=memory.readWord(record+6),proc=memory.readLong(record+8);if((flags&PROC_IS_RELATIVE)!=0)proc+=rd;
				c.pc=proc;return true;
			}
			passSelector=(memory.readWord(record+6)&DONT_PASS_SELECTOR)==0;
			// a plain Pascal routine behind a stack-dispatched descriptor: the selector word above the return address is dropped
			if(selectorInfo==14&&(memory.readLong(record)&15)==0)stackSelectorBytes=2;
		}else record=findRecord(rd,ISA_PPC);
		if(record<0){
			int r68=findRecord(rd,ISA_68K);if(r68<0)throw new M68kCpuException(rd,"routine descriptor without 68K or PowerPC code");
			int flags=memory.readWord(r68+6),proc=memory.readLong(r68+8);if((flags&PROC_IS_RELATIVE)!=0)proc+=rd;
			c.pc=proc;return true;
		}
		int procInfo=memory.readLong(record);
		int target=recordTarget(rd,record);
		int conv=procInfo&15;
		if(TRACE)System.err.printf("[ppc] 68K -> PowerPC routine descriptor %08X procInfo %08X TVector %08X SP=%08X return %08X%n",rd,procInfo,target,c.a[7],memory.readLong(c.a[7]));
		int[] saved68=saveRegisters68();
		int sp=c.a[7],returnAddress=memory.readLong(sp);
		List<Integer> args=new ArrayList<Integer>();
		int resultSize=sizeCode((procInfo>>>4)&3);
		switch(conv){
			case 0:case 14:{ // Pascal: arguments pushed left to right, a result slot above them
				int first=conv==14?8:6;
				List<Integer> sizes=params(procInfo,first);
				int total=0;for(int s:sizes)total+=pascalSlot(s);
				int selectorBytes=stackSelectorBytes;
				if(conv==14){int ss=pascalSlot((procInfo>>>6)&3);selectorBytes=ss;}
				int offset=4+selectorBytes+total;
				int at=4+selectorBytes;
				int[] offs=new int[sizes.size()];
				for(int i=sizes.size()-1;i>=0;i--){offs[i]=at;at+=pascalSlot(sizes.get(i));}
				if(conv==14&&passSelector){int ss=pascalSlot((procInfo>>>6)&3);args.add(ss==4?memory.readLong(sp+4):(short)memory.readWord(sp+4));}
				for(int i=0;i<sizes.size();i++)args.add(readStackArg(sp+offs[i],sizes.get(i),true));
				int result=callPpc(target,args);
				if(TRACE&&selectorInfo!=0)System.err.printf("[ppc]   dispatched selector %d procInfo %08X%s -> %08X%n",saved68[0],procInfo,args.stream().map(a->String.format(" %08X",a)).collect(java.util.stream.Collectors.joining()),result);
				restoreRegisters68(saved68);
				int slot=sp+offset;
				if(resultSize==1)memory.writeByte(slot,result);else if(resultSize==2)memory.writeWord(slot,result);else if(resultSize==4)memory.writeLong(slot,result);
				c.a[7]=sp+offset;c.pc=returnAddress;
				return true;}
			case 1:case 5:case 9:{ // C (MPW: a long per argument; THINK C: ints are words), result in D0
				int first=conv==9?8:6;
				List<Integer> sizes=params(procInfo,first);
				int at=sp+4;
				if(conv==9&&passSelector){args.add(extend(c.d[0],sizeCode((procInfo>>>6)&3)));}
				// THINK C pushes a char with MOVE.B: the value is the word's high byte (AppleWorks 5's MDEF passes its message so - the
				// low byte is stack garbage, and its palettes got message $2A instead of mDrawMsg and drew nothing)
				for(int s:sizes){if(conv==5&&s!=3){args.add(extend(s==1?memory.readByte(at):memory.readWord(at),sizeCode(s)));at+=2;}else{int v=memory.readLong(at);args.add(extend(v,sizeCode(s)));at+=4;}}
				int result=callPpc(target,args);
				restoreRegisters68(saved68);
				if(resultSize>0){c.d[0]=result;if(resultSize==4)c.a[0]=result;}
				c.a[7]=sp+4;c.pc=returnAddress;
				return true;}
			case 2:{ // registers
				for(int i=0;i<4;i++){int p=(procInfo>>>(11+5*i))&31;if(p==0)break;int size=sizeCode(p&3),which=(p>>>2)&7;args.add(extend(readReg(which),size));}
				int resultLocation=(procInfo>>>6)&31;
				long outerNested=nestedRegisterD0;nestedRegisterD0=-1;
				int result=callPpc(target,args);
				long nested=nestedRegisterD0;nestedRegisterD0=outerNested;
				restoreRegisters68(saved68);
				// an OS trap patched with PowerPC code (QuickTime patches NewPtr: procInfo "D1 trap word, D0 size -> A0") passes the
				// trap on with CallOSTrapUniversalProc and returns only A0; the 68K caller then reads the result code in D0, which the
				// 68K context shared with the nested call carries - the Speech Manager's INIT saw the size there and stopped (SysError 25)
				if(nested>=0&&resultLocation!=0)cpu68.d[0]=(int)nested;
				if(resultSize>0)writeReg(resultLocation,result,resultSize);
				c.a[7]=sp+4;c.pc=returnAddress;
				return true;}
			case 8:case 12:{ // selector in D0/D1, Pascal arguments
				List<Integer> sizes=params(procInfo,8);
				int total=0;for(int s:sizes)total+=pascalSlot(s);
				int at=4;int[] offs=new int[sizes.size()];
				for(int i=sizes.size()-1;i>=0;i--){offs[i]=at;at+=pascalSlot(sizes.get(i));}
				if(passSelector)args.add(extend(conv==8?c.d[0]:c.d[1],sizeCode((procInfo>>>6)&3)));
				for(int i=0;i<sizes.size();i++)args.add(readStackArg(sp+offs[i],sizes.get(i),true));
				int result=callPpc(target,args);
				restoreRegisters68(saved68);
				int slot=sp+4+total;
				if(resultSize==1)memory.writeByte(slot,result);else if(resultSize==2)memory.writeWord(slot,result);else if(resultSize==4)memory.writeLong(slot,result);
				c.a[7]=slot;c.pc=returnAddress;
				return true;}
			case 15:{ // kSpecialCase: only MBarHook so far (Flash 4's Windows layer) - the menu rectangle's address on the stack, result in D0
				int special=(procInfo>>>4)&0x3f;
				if(special!=12)throw new M68kCpuException(rd,String.format("Mixed Mode special case %d (procInfo $%08X) is not supported",special,procInfo));
				args.add(memory.readLong(sp+4));
				int result=callPpc(target,args);
				restoreRegisters68(saved68);
				c.d[0]=result;c.a[7]=sp+4;c.pc=returnAddress;
				return true;}
			default:
				throw new M68kCpuException(rd,String.format("Mixed Mode calling convention %d (procInfo $%08X) is not supported",conv,procInfo));
		}
	}
	/** The 2-bit size codes of the stack parameters starting at bit {@code first} (up to the first empty one). */
	private static List<Integer> params(int procInfo,int first){
		List<Integer> l=new ArrayList<Integer>();
		for(int bit=first;bit<32;bit+=2){int code=(procInfo>>>bit)&3;if(code==0)break;l.add(code);}
		return l;
	}
	private int readStackArg(int at,int code,boolean pascal) throws M68kCpuException {
		switch(code){case 1:return memory.readByte(at);case 2:return (short)memory.readWord(at);default:return memory.readLong(at);}
	}
	private int[] saveRegisters68(){int[] s=new int[17];System.arraycopy(cpu68.d,0,s,0,8);System.arraycopy(cpu68.a,0,s,8,8);s[16]=cpu68.sr;return s;}
	private void restoreRegisters68(int[] s){System.arraycopy(s,0,cpu68.d,0,8);System.arraycopy(s,8,cpu68.a,0,8);cpu68.sr=s[16];}

	/** Calls a PowerPC transition vector from Java. */
	int callPpc(int tvector,List<Integer> args) throws M68kException {
		int[] a=new int[args.size()];for(int i=0;i<a.length;i++)a[i]=args.get(i);
		return cpu.call(tvector,a);
	}

	/**
	 * CallUniversalProc from PowerPC code: a routine descriptor with PowerPC code is called directly; 68K code (a plain
	 * pointer or a descriptor with only 68K code) runs in the 68K emulator with the arguments laid out per {@code procInfo}.
	 */
	int callUniversal(int upp,int procInfo,int[] args) throws M68kException {
		if(upp==0)throw new M68kCpuException(cpu.lr,"CallUniversalProc with a NIL procedure");
		int target=upp;
		if(isRoutineDescriptor(upp)&&memory.readByte(upp+9)!=0&&args.length>0){ // dispatched: the selector is the first argument
			int record=dispatchedRecord(upp,args[0]);
			if(record<0)throw new M68kCpuException(upp,String.format("dispatched routine descriptor without a routine for selector %d",args[0]));
			if((memory.readByte(record+5)&0x0f)==ISA_PPC){
				int[] a=args;
				if((memory.readWord(record+6)&DONT_PASS_SELECTOR)!=0){a=new int[args.length-1];System.arraycopy(args,1,a,0,a.length);}
				return cpu.call(recordTarget(upp,record),a);
			}
			int flags=memory.readWord(record+6);target=memory.readLong(record+8);if((flags&PROC_IS_RELATIVE)!=0)target+=upp;
			return call68k(target,procInfo,args);
		}
		if(isRoutineDescriptor(upp)){
			int record=findRecord(upp,ISA_PPC);
			if(record>=0)return cpu.call(recordTarget(upp,record),args);
			int r68=findRecord(upp,ISA_68K);if(r68<0)throw new M68kCpuException(upp,"routine descriptor without code");
			int flags=memory.readWord(r68+6);target=memory.readLong(r68+8);if((flags&PROC_IS_RELATIVE)!=0)target+=upp;
		}
		// A Toolbox trap's GetTrapAddress address: on a Power Macintosh the trap table holds a routine descriptor of native code
		// for most of them (QuickDraw, the Window Manager ...), which Mixed Mode calls with the arguments as they are, ignoring
		// procInfo. Canvas 3.5.4 calls FrameOval's address with procInfo 3 (its rectangle in r5): it is called here with the
		// trap's own interface instead ("Mixed Mode calling convention 3 is not supported" when an oval was drawn).
		if(cpu68.isTrapRomAddress(target)){Glue g=trapGlue(cpu68.trapWordForRomAddress(target));if(g!=null)return callToolbox(g,args,target);}
		return call68k(target,procInfo,args);
	}

	/**
	 * Runs 68K code at {@code target} with the arguments laid out per the Mixed Mode {@code procInfo}; returns its result.
	 * The 68K registers stay as the routine left them (only the stack pointer comes back): the emulator keeps one register
	 * file, and it is the 68K-to-PowerPC switch that gives a 68K caller its registers back when PowerPC code returns to it
	 * (see {@link #mixedModeTrap}). Canvas 3.5.4's PowerPC code calls the THINK C "SetA4" routine of its 'aCOD' code resource,
	 * then its 68K callbacks, which use A4 without setting it up, also while one of its 68K tools (A4 = the tool's) waits in
	 * a call to PowerPC: they ran with A4 = 0 at start-up and with the tool's A4 when a sample file was opened.
	 */
	int call68k(int target,int procInfo,int[] args) throws M68kException {
		int conv=procInfo&15,resultSize=sizeCode((procInfo>>>4)&3);
		int sp0=cpu68.a[7];
		try{
			switch(conv){
				case 0:case 14:case 8:case 12:{ // Pascal, maybe with a selector
					int first=conv==0?6:8;int ai=0;
					int selector=0,selectorSize=0;
					if(conv!=0){selector=args.length>0?args[0]:0;selectorSize=sizeCode((procInfo>>>6)&3);ai=1;}
					if(resultSize>0){cpu68.a[7]-=resultSize==4?4:2;memory.fill(cpu68.a[7],resultSize==4?4:2,0);}
					int resultSlot=cpu68.a[7];
					int top=cpu68.a[7];
					for(int s:params(procInfo,first)){int v=ai<args.length?args[ai]:0;ai++;push68(s,v,true);}
					if(conv==14)push68(selectorSize==4?3:2,selector,true);
					if(conv==8)cpu68.d[0]=selector;if(conv==12)cpu68.d[1]=selector;
					jumpTo68k(target,top-cpu68.a[7]);
					return resultSize==0?0:resultSize==4?memory.readLong(resultSlot):resultSize==2?(short)memory.readWord(resultSlot):memory.readByte(resultSlot);}
				case 1:case 5:case 9:{ // C: arguments right to left, result in D0
					int first=conv==9?8:6;List<Integer> sizes=params(procInfo,first);int base=conv==9?1:0;int top=cpu68.a[7];
					for(int i=sizes.size()-1;i>=0;i--){int v=base+i<args.length?args[base+i]:0;if(conv==5&&sizes.get(i)!=3){cpu68.a[7]-=2;if(sizes.get(i)==1){memory.writeWord(cpu68.a[7],0);memory.writeByte(cpu68.a[7],v);}else memory.writeWord(cpu68.a[7],v);}else{cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],v);}}
					if(conv==9)cpu68.d[0]=args.length>0?args[0]:0;
					jumpTo68k(target,top-cpu68.a[7]);
					return resultSize==0?0:extend(cpu68.d[0],resultSize);}
				case 2:{ // registers
					for(int i=0;i<4;i++){int p=(procInfo>>>(11+5*i))&31;if(p==0)break;int v=i<args.length?args[i]:0;int which=(p>>>2)&7;int r=regIndex(which);if(r<8)cpu68.d[r]=v;else cpu68.a[r-8]=v;}
					jumpTo68k(target,0);
					nestedRegisterD0=cpu68.d[0]&0xffffffffL;
					int loc=(procInfo>>>6)&31;
					if(resultSize==0)return 0;
					if(loc>=16){int bit=loc==16?M68kCpu.FLAG_C:loc==17?M68kCpu.FLAG_V:loc==18?M68kCpu.FLAG_Z:loc==19?M68kCpu.FLAG_N:M68kCpu.FLAG_X;return (cpu68.sr&bit)!=0?1:0;}
					return extend(readReg(loc),resultSize);}
				case 15:{ // kSpecialCase: only MBarHook - one long on the stack (the caller pops it), result in D0. Disk Copy 6.3 calls
					// its licence code resource ('infn' 5000) this way
					int special=(procInfo>>>4)&0x3f;
					if(special!=12)throw new M68kCpuException(target,String.format("Mixed Mode special case %d (procInfo $%08X) is not supported",special,procInfo));
					cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],args.length>0?args[0]:0);
					jumpTo68k(target,4);
					return cpu68.d[0];}
				default:throw new M68kCpuException(target,String.format("Mixed Mode calling convention %d (procInfo $%08X) is not supported",conv,procInfo));
			}
		}finally{cpu68.a[7]=sp0;}
	}
	/**
	 * Calls 68K code at {@code target} whose {@code argumentBytes} of arguments are pushed: as a JSR would, with a return
	 * address (into a stub that ends the run) below them, reached through an RTS so that no register is used. The run's frame
	 * spans the arguments, so a trap's ROM entry - which pops that return address - and anything it calls stay inside it.
	 */
	private void jumpTo68k(int target,int argumentBytes) throws M68kException {
		if(rtsStub==0){rtsStub=allocate(8,4);memory.writeWord(rtsStub,0x4e75);memory.writeWord(rtsStub+2,0x4ef9);memory.writeLong(rtsStub+4,M68kCpu.RETURN_SENTINEL);}
		cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],rtsStub+2); // return address: JMP sentinel
		cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],target);
		cpu68.runUntilSentinel(rtsStub,argumentBytes+8);
		syncEmulatorContext();
	}
	private int rtsStub;
	/**
	 * The 68K emulator's register file as MixedMode's data export Emulated68KContext points to it (0 until a program imports
	 * it): D0-D7 at 0, A0-A7 at 32, as the real emulator keeps them while native code runs. Written at every switch from 68K
	 * code to PowerPC code. Macintosh Common Lisp 4 (SK8 1.2 PPC) refuses to start without it and takes the word at 0 (D0) as
	 * the result of the 68K traps it calls.
	 */
	int emulatorContext;
	void syncEmulatorContext() throws M68kCpuException {
		int at=emulatorContext;if(at==0)return;
		for(int i=0;i<8;i++){memory.writeLong(at+4*i,cpu68.d[i]);memory.writeLong(at+32+4*i,cpu68.a[i]);}
	}
	private void push68(int code,int v,boolean pascal) throws M68kCpuException {
		switch(code){
			case 1:cpu68.a[7]-=2;memory.writeWord(cpu68.a[7],(v&0xff)<<8);break;
			case 2:cpu68.a[7]-=2;memory.writeWord(cpu68.a[7],v);break;
			default:cpu68.a[7]-=4;memory.writeLong(cpu68.a[7],v);break;
		}
	}

	// ------------------------------------------------------------------ Code Fragment Manager

	/** A loaded code fragment (the application, an import library, a code resource). */
	final class Fragment {
		final String name;final File file;final Pef pef;Pef.Instance instance;final int connection;
		final Map<String,Integer> exports=new LinkedHashMap<String,Integer>();
		final Map<String,Integer> exportClasses=new HashMap<String,Integer>();
		int initBlock;boolean initialized;
		/** Where the container starts in {@link #file}'s data fork (-1: a resource or memory fragment): with the file, what CFM tells containers apart by. */
		int containerOffset=-1;
		Fragment(String name,File file,Pef pef){this.name=name;this.file=file;this.pef=pef;connection=nextConnection++;}
	}
	private int nextConnection=0x4c49;
	private final List<Fragment> fragments=new ArrayList<Fragment>();
	/** The application's own fragment. */
	Fragment application;

	/** The known libraries the system provides natively (always served by {@link PpcLibraries}, never searched for). */
	static final java.util.Set<String> SYSTEM_LIBRARIES=new java.util.HashSet<String>(java.util.Arrays.asList(
		"InterfaceLib","ColorSync","PrivateInterfaceLib","MathLib","StdCLib","QuickTimeLib","ObjectSupportLib","DragLib","AppearanceLib",
		"ThreadsLib","SoundLib","ControlsLib","DialogsLib","WindowsLib","MenusLib","TranslationLib","AppleScriptLib",
		"Translation", // Easy Open's library under the name AppleWorks 5 imports it by (Gestalt 'xlat' reports it for PowerPC)
		"TextCommon","TextEncodingConverter","UnicodeConverter","ContextualMenu","IconServicesLib","FontManager","DisplayLib",
		"SpeechLib","ControlStripLib","CFMPriv","ColorPickerLib","AppleGuideLib","CollectionsLib","DriverServicesLib","DriverLoaderLib",
		"NameRegistryLib","QuickDrawText","UTCUtils","DateTimeUtils","InternetConfigLib","NavigationLib","FoldersLib",
		"QTStreamLib","InputSprocketLib","DrawSprocketLib","OTUtilityLib","OTClientUtilLib","OTClientLib","OTInetClientLib",
		"OpenTransportLib","OpenTptInternetLib","OpenTptAppleTalkLib","OpenTransportUtilLib","Math64Lib","VideoServicesLib",
		"CodeFragmentMgr","MixedMode","OTNativeClientLib","OTNtvUtilLib","OTConfigLib","OTXTILib","CarbonLib","Apple;Carbon;Networking","Apple;Carbon;Multimedia","Apple;Carbon;Core","Apple;Carbon;Graphics",
		// CarbonLib's parts under their own names (CarbonFrameworkLib = CoreFoundation of the Universal Interfaces 3.4 stub libraries)
		"CarbonFrameworkLib","MPLibrary","Find by Content","URLAccessLib","KeychainLib","UnicodeUtilitiesLib","UnicodeUtilitiesCoreLib","LocalesLib","ATSUnicodeLib"));

	/** Whether library {@code name} is served natively: a system library, QuickDraw 3D / RAVE when {@link PpcRave} and {@link PpcQuickDraw3D} replace them, OpenGL ({@link PpcOpenGL}). */
	static boolean isNativeLibrary(String name){return SYSTEM_LIBRARIES.contains(name)||PpcRave.NATIVE&&PpcRave.LIBRARY.equals(name)||PpcQuickDraw3D.NATIVE&&(PpcQuickDraw3D.LIBRARY.equals(name)||PpcQ3Viewer.LIBRARY.equals(name))||PpcOpenGL.isLibrary(name);}

	/** Something imports can be bound to. */
	private interface Provider { int symbol(String name,int symbolClass,boolean weak) throws IOException,M68kException; }
	private final Map<String,Provider> providers=new HashMap<String,Provider>();

	/** Finds (loading it when needed) the library {@code name}; null when it cannot be found. */
	private Provider provider(String name) throws IOException,M68kException {
		Provider p=providers.get(name);if(p!=null)return p;
		for(Fragment f:fragments)if(f.name.equals(name)){p=fragmentProvider(f);providers.put(name,p);return p;}
		if(isNativeLibrary(name)){p=nativeProvider(name);providers.put(name,p);return p;}
		Fragment f=findLibraryFile(name);
		if(f!=null){p=fragmentProvider(f);providers.put(name,p);return p;}
		return null;
	}
	private Provider fragmentProvider(final Fragment f){
		return new Provider(){@Override public int symbol(String name,int symbolClass,boolean weak) throws IOException,M68kException {
			Integer v=f.exports.get(name);
			if(v!=null)return PpcRave.TRACE&&f.name.equals(PpcRave.LIBRARY)&&symbolClass!=Pef.CLASS_DATA?libraries.rave.traceExport(name,v):v;
			if(weak)return 0;
			System.err.println("[ppc] "+f.name+" has no symbol "+name);
			return missing(f.name,name,symbolClass);
		}};
	}
	private Provider nativeProvider(final String library){
		return new Provider(){@Override public int symbol(String name,int symbolClass,boolean weak) throws IOException,M68kException {return nativeSymbol(library,name,symbolClass,weak);}};
	}

	/** A symbol of a native library: a Java implementation, a Toolbox bridge, a data item, or 0 / a stub when missing. */
	int nativeSymbol(String library,String name,int symbolClass,boolean weak) throws M68kException {
		String key=library+"/"+name;
		Integer cached=nativeVectors.get(key);if(cached!=null)return cached;
		int value;
		if(symbolClass==Pef.CLASS_DATA){
			value=libraries.data(library,name);
			// CFM resolves a name whatever class the importer gives it: Flash 4 imports InsertMenuItem as DATA and calls
			// through the TOC entry, so a function imported that way gets its transition vector (a zeroed block crashed it)
			Native n;if(value==0&&((n=libraries.function(library,name))!=null||(n=glue(name))!=null))value=nativeVector(name,n);
			if(value==0&&!weak){System.err.println("[ppc] "+library+": unknown data symbol "+name+" (zeroed block)");value=allocate(256,8);}
		}else{
			Native n=libraries.function(library,name);
			if(n==null){Glue g=glue(name);if(g!=null)n=g;}
			int data;
			if(n!=null)value=nativeVector(name,n);
			else if((data=libraries.data(library,name))!=0)value=data; // a data item imported under another symbol class (Netscape Communicator's CallComponentUPP)
			else if(weak&&!weakStub(name)){value=0;if(WEAK_LOG)System.err.println("[ppc] weak import "+library+":"+name+" absent (0)");}
			else value=missing(library,name,symbolClass);
		}
		nativeVectors.put(key,value);
		return value;
	}
	/** Debug aid, -Dhzppc.weakstubs=TXN,Nav...: weak imports with these name prefixes get the reporting stand-in instead of
	 *  0, to see how far a program gets past a call it makes without checking the import. */
	private static final String[] WEAK_STUBS=System.getProperty("hzppc.weakstubs","").isEmpty()?new String[0]:System.getProperty("hzppc.weakstubs").split(",");
	/** Debug aid, -Dhzppc.weaklog=true: report each weak import that resolves to 0 (programs test these for NULL). */
	private static final boolean WEAK_LOG=Boolean.getBoolean("hzppc.weaklog");
	private static boolean weakStub(String name){for(String p:WEAK_STUBS)if(name.startsWith(p))return true;return false;}
	/** A stand-in for a missing function: reports its name when called and returns 0 (or stops, -Dhzppc.strict=true). */
	private int missing(final String library,final String name,int symbolClass) throws M68kCpuException {
		if(symbolClass==Pef.CLASS_DATA)return allocate(256,8);
		return nativeVector(name,new Native(){boolean told;@Override public void call(PpcRuntime rt,PpcCpu c) throws M68kException {
			if(STRICT)throw new M68kMacApiException(c.lr,0,library+" function "+name+" is not implemented for PowerPC programs");
			if(!told){told=true;System.err.println("[ppc "+app.name+"] "+library+" function "+name+" is not implemented: returns 0");}
			c.r[3]=0;
		}});
	}

	/** The folders searched for import libraries: the application's own folder (and its "Libraries" style subfolders), then Extensions. */
	/** Files whose 'cfrg' libraries system software registered with the Code Fragment Manager (the private selector -20 an
	 *  extension's INIT calls with its own FSSpec); searched before the library folders. */
	final List<File> registeredLibraryFiles=new ArrayList<File>();
	private List<File> libraryFolders(){
		List<File> l=new ArrayList<File>();
		File dir=app.file.getAbsoluteFile().getParentFile();
		if(dir!=null){l.add(dir);File[] subs=dir.listFiles();if(subs!=null)for(File s:subs)if(s.isDirectory())l.add(s);}
		try{File sys=toolbox.files.systemFolder;
			if(sys!=null){File ext=new File(sys,"Extensions");l.add(ext);
				// CFM also looks one folder deep in Extensions ("OpenDoc Libraries", "Open Transport" style folders)
				File[] subs=ext.listFiles();if(subs!=null){java.util.Arrays.sort(subs);for(File s:subs)if(s.isDirectory()&&!s.getName().startsWith("."))l.add(s);}
				// OpenDoc registers the Editors folder (part editors and their shared libraries, any depth)
				addFoldersDeep(l,new File(sys,"Editors"),3);}
		}catch(RuntimeException ignored){}
		return l;
	}
	private static void addFoldersDeep(List<File> l,File dir,int depth){
		if(depth<0||!dir.isDirectory())return;l.add(dir);
		File[] subs=dir.listFiles();if(subs==null)return;java.util.Arrays.sort(subs);
		for(File s:subs)if(s.isDirectory()&&!s.getName().startsWith("."))addFoldersDeep(l,s,depth-1);
	}
	/** Loads the import library {@code name} from the application file or a library folder; null when it is nowhere. */
	private Fragment findLibraryFile(String name) throws IOException,M68kException {
		List<File> candidates=new ArrayList<File>();candidates.add(app.file);
		for(File f:registeredLibraryFiles)if(!candidates.contains(f))candidates.add(f);
		for(File dir:libraryFolders()){File[] files=dir.listFiles();if(files==null)continue;
			java.util.Arrays.sort(files);
			for(File f:files)if(f.isFile()&&!f.equals(app.file)&&!f.getName().startsWith("."))candidates.add(f);}
		// a file named like the library first (SoundSprocketLib next to the program)
		for(File f:candidates)if(f.getName().equals(name)){Fragment fr=loadLibraryFrom(f,name);if(fr!=null)return fr;}
		for(File f:candidates){if(f.length()<64)continue;Fragment fr=loadLibraryFrom(f,name);if(fr!=null)return fr;}
		return null;
	}
	private Fragment loadLibraryFrom(File file,String name) throws IOException,M68kException {
		ResourceFile68k rf;
		try{rf=ResourceFile68k.open(file);}catch(IOException e){return null;}
		for(FragmentEntry e:fragmentEntries(rf)){
			if(e.architecture!=ARCH_PPC||!e.name.equals(name))continue;
			if(e.usage!=USAGE_LIBRARY&&!(file.equals(app.file)&&e.usage!=USAGE_APPLICATION))continue;
			if(e.where==2){byte[] code=resourceFragment(file,rf,e);if(code==null)continue;
				System.err.println("[ppc] loading library \""+name+"\" from "+file.getName()+" (resource "+ResourceFile68k.typeName(e.offset)+" "+(short)e.length+")");
				return loadFragment(name,file,code,0,code.length,2,e.offset,e.length);}
			if(e.where!=1)continue;
			// several 'cfrg' names for one container are aliases of one fragment: OpenDocLib answers for "OpenDoc Bento",
			// "OpenDoc Shell"... (the OpenDoc 1.0 library names) - a second instance has its own globals and crashes
			Fragment loaded=fragmentByContainer(file,e.offset);
			if(loaded!=null){if(TRACE)System.err.println("[ppc] library \""+name+"\" is the loaded \""+loaded.name+"\"");return loaded;}
			byte[] data=dataFork(file);
			if(TRACE||true)System.err.println("[ppc] loading library \""+name+"\" from "+file.getName());
			return loadFragment(name,file,data,e.offset,e.length);
		}
		return null;
	}

	/**
	 * A fragment the 'cfrg' keeps in a resource (location kind 2, kOnDiskSegmented: the entry's offset is the resource type,
	 * its length the ID), as the Resource Manager hands it out - expanded, through the file's own 'dcmp' if need be
	 * (REALbasic applications keep their STRuntime in a 'cPPC' resource compressed with their 'dcmp' 200). Null if missing.
	 */
	private byte[] resourceFragment(File file,ResourceFile68k rf,FragmentEntry e) throws M68kCpuException {
		if(file.equals(app.file)){ResourceFile68k.Resource r=toolbox.resources.find(e.offset,(short)e.length);return r==null?null:toolbox.resources.data(r);}
		ResourceFile68k.Resource r=rf.get(e.offset,(short)e.length);return r==null?null:r.data();
	}

	/** Instantiates a PEF container, binds its imports (loading and initializing libraries first) and runs its init routine. */
	Fragment loadFragment(String name,File file,byte[] data,int offset,int length) throws IOException,M68kException {
		return loadFragment(name,file,data,offset,length,1,offset,length);
	}
	/** As above, with the location its CFragInitBlock reports: kind 1 (data fork: offset, length) or 2 (resource: type, ID). */
	Fragment loadFragment(String name,File file,byte[] data,int offset,int length,int where,int locatorA,int locatorB) throws IOException,M68kException {
		return loadFragment(name,file,data,offset,length,where,locatorA,locatorB,0);
	}
	/** {@code inPlace}: the guest address of the container when it is in memory (GetMemFragment), for its code to run in place. */
	Fragment loadFragment(String name,File file,byte[] data,int offset,int length,int where,int locatorA,int locatorB,int inPlace) throws IOException,M68kException {
		Pef pef=new Pef(data,offset,length);
		final Fragment f=new Fragment(name,file,pef);if(where==1&&inPlace==0)f.containerOffset=offset;
		fragments.add(f);
		final List<String> unresolved=new ArrayList<String>();
		loadingDepth++;
		try{
		f.instance=pef.instantiate(memory,new Pef.Allocator(){@Override public int allocate(int size,int alignment) throws M68kCpuException {return PpcRuntime.this.allocate(size,alignment);}},
			new Pef.Imports(){
			/** Netscape Communicator's libraries import from the application, and some of them from each other, while it loads (CFM binds cycles). */
			@Override public void placed(Pef.Instance sections){for(Pef.Export e:pef.exports)if(e.section!=-3){f.exports.put(e.name,sections.exportValue(e));f.exportClasses.put(e.name,e.symbolClass);}}
			@Override public int resolve(Pef.Library lib,Pef.Symbol s,int index) throws IOException {
				try{
					Provider p=provider(lib.name);
					if(p==null){
						// natively implemented by name (SoundSprocket...): a weak import of it is present even without the library file
						if((lib.weak()||s.weak)&&libraries.function(lib.name,s.name)!=null){p=nativeProvider(lib.name);providers.put(lib.name,p);}
						else{
							if(lib.weak()||s.weak){if(TRACE)System.err.println("[ppc] weak import "+lib.name+"/"+s.name+" of "+f.name+": library absent (0)");return 0;}
							System.err.println("[ppc] library \""+lib.name+"\" not found (imported by "+f.name+"): its functions answer 0");
							p=nativeProvider(lib.name);providers.put(lib.name,p);
						}
					}
					int v=p.symbol(s.name,s.symbolClass,s.weak||lib.weak());
					if(v==0&&TRACE)System.err.println("[ppc] weak import "+lib.name+"/"+s.name+" of "+f.name+" is unresolved (0)");
					return v;
				}catch(M68kException e){throw new IOException(e.getMessage(),e);}
			}},inPlace==0?0:inPlace+offset);
		}finally{loadingDepth--;}
		for(Pef.Export e:pef.exports){
			int v=f.instance.exportValue(e); // a re-exported import gives what it was bound to
			f.exports.put(e.name,v);f.exportClasses.put(e.name,e.symbolClass);
		}
		// CFM prepares the whole closure of a fragment before any of it runs: init routines wait until the outermost load is
		// done (in the order the loads finished, libraries before their importers) - with an import cycle a library's init
		// would call into a fragment that is not relocated yet (Netscape Communicator's MemoryAllocator into NSRuntime).
		final int w=where,la=locatorA,lb=locatorB;
		pendingInits.add(()->initialize(f,w,la,lb));
		if(loadingDepth==0)runPendingInits();
		return f;
	}
	private interface Init { void run() throws M68kException; }
	private final List<Init> pendingInits=new ArrayList<Init>();
	private int loadingDepth;
	/** Runs the waiting inits as one batch: a fragment an init routine loads (GetMemFragment) runs only its own closure's inits,
	 *  never those of the outer load still waiting behind it - MCL 4's kernel loads its subprims from its init routine and looks
	 *  up their addresses afterwards, and the application's init (which reads them) must not run in between (SK8 1.2 PPC). */
	private void runPendingInits() throws M68kException {
		List<Init> batch=new ArrayList<Init>(pendingInits);pendingInits.clear();
		for(Init i:batch)i.run();
	}
	/** Runs a fragment's init routine with its CFragInitBlock (connection, locator: the file's FSSpec with offset and length, or
	 *  with the resource type and ID for kind 2). */
	private void initialize(Fragment f,int where,int locatorA,int locatorB) throws M68kException {
		if(f.initialized)return;f.initialized=true;
		int init=f.instance.init();
		int block=allocate(36+70+64,4);f.initBlock=block;
		memory.writeLong(block,1);memory.writeLong(block+4,f.connection);memory.writeLong(block+8,f.connection);
		int spec=block+36;
		if(f.file!=null){
			memory.writeLong(block+12,where); // kDataForkCFragLocator 1 / kResourceCFragLocator 2
			memory.writeLong(block+16,spec);memory.writeLong(block+20,locatorA);
			if(where==2){memory.writeWord(block+24,locatorB);memory.writeWord(block+26,0);}else memory.writeLong(block+24,locatorB);
			try{libraries.makeFSSpec(f.file,spec);}catch(RuntimeException e){System.err.println("[ppc] no FSSpec for "+f.file+": "+e);}
		}else{memory.writeLong(block+12,0);memory.writeLong(block+16,locatorA);memory.writeLong(block+20,locatorB);memory.writeByte(block+24,locatorA!=0?1:0);} // kMemoryCFragLocator: address, length, in place
		int libName=block+36+70;writePString(libName,f.name);memory.writeLong(block+28,libName);
		if(init==0)return;
		if(TRACE)System.err.println("[ppc] initializing "+f.name);
		int err=cpu.call(init,block);
		if((short)err!=0)System.err.println("[ppc] init routine of "+f.name+" returned "+(short)err);
	}
	/** A fragment from guest memory (GetMemFragment, 'fat' code resources). */
	Fragment loadMemoryFragment(int address,int length,String name) throws M68kException {
		try{
			int n=length>0?length:guessContainerLength(address);
			byte[] data=memory.readBytes(address,n);
			Fragment f=loadFragment(name,null,data,0,n,0,address,n,address);
			return f;
		}catch(IOException e){throw new M68kCpuException(address,"cannot prepare the code fragment: "+e.getMessage());}
	}
	/** GetMemFragment/FragPrepare: like {@link #loadMemoryFragment}, but a block that is no PEF container (an installer offering a 'fat' code resource, routine descriptor first) is answered with cfragFragmentFormatErr, as the CFM does, instead of stopping the program; null then. */
	Fragment tryLoadMemoryFragment(int address,int length,String name) throws M68kException {
		try{
			int n=length>0?length:guessContainerLength(address);
			byte[] data=memory.readBytes(address,n);
			String dumpDir=System.getProperty("hzppc.dumpfrag"); // debug aid: the container as the program built it (decrypted trial wrappers)
			if(dumpDir!=null)java.nio.file.Files.write(new java.io.File(dumpDir,name.replaceAll("[^A-Za-z0-9._-]","_")+".pef").toPath(),data);
			return loadFragment(name,null,data,0,n,0,address,n,address);
		}catch(IOException e){System.err.println("[ppc] GetMemFragment("+name+" at $"+Integer.toHexString(address)+"): "+e.getMessage()+" -> cfragFragmentFormatErr");return null;}
	}
	/** The extent of a PEF container in memory: the end of its farthest section. */
	private int guessContainerLength(int address) throws M68kCpuException {
		int count=memory.readWord(address+32);int end=40+count*28;
		for(int i=0;i<count;i++){int h=address+40+i*28;int off=memory.readLong(h+20),size=memory.readLong(h+16);end=Math.max(end,off+size);}
		return end;
	}
	Fragment fragmentByConnection(int id){for(Fragment f:fragments)if(f.connection==id)return f;return null;}
	/** The loaded fragments in load order (the private FragGetConnectionInfo walks them). */
	List<Fragment> loadedFragments(){return fragments;}
	Fragment fragmentByName(String name){for(Fragment f:fragments)if(f.name.equals(name))return f;return null;}
	/** The loaded fragment of a data-fork container (the file and the container's offset in it), or null. */
	Fragment fragmentByContainer(File file,int offset){
		try{File canonical=file.getCanonicalFile();
			for(Fragment f:fragments)if(f.file!=null&&f.containerOffset==offset&&f.file.getCanonicalFile().equals(canonical))return f;}
		catch(IOException e){}
		return null;}

	/** GetSharedLibrary: a loaded or loadable import library by name; its connection and main address. */
	int getSharedLibrary(String name,int[] connection,int[] main) throws M68kException {
		try{
			Fragment f=fragmentByName(name);
			if(f==null){
				if(isNativeLibrary(name)){connection[0]=nativeConnection(name);main[0]=0;return NO_ERR;}
				f=findLibraryFile(name);
			}
			if(f==null)return CFRAG_NO_LIBRARY;
			connection[0]=f.connection;main[0]=f.instance.main();return NO_ERR;
		}catch(IOException e){System.err.println("[ppc] GetSharedLibrary "+name+": "+e.getMessage());return CFRAG_FORMAT;}
	}
	/** Connections to native libraries: ids from {@link #NATIVE_CONNECTIONS} by name. Small numbers like the system heap
	 *  addresses the real CFM's ids are: Macintosh Common Lisp 4 boxes them as fixnums (30 bits), and with $4E000000 ids
	 *  they became bignums its library code stored into a bit vector as fixnums, writing over its own heap (SK8 1.2 PPC). */
	private final List<String> nativeConnections=new ArrayList<String>();
	static final int NATIVE_CONNECTIONS=0x004e0000;
	int nativeConnection(String name){int i=nativeConnections.indexOf(name);if(i<0){nativeConnections.add(name);i=nativeConnections.size()-1;}return NATIVE_CONNECTIONS+i;}
	String nativeConnectionName(int id){int i=id-NATIVE_CONNECTIONS;return i>=0&&i<nativeConnections.size()?nativeConnections.get(i):null;}

	// ------------------------------------------------------------------ running the application

	/**
	 * A PowerPC runtime brought up inside a 68K program the first time its 68K code calls PowerPC code (a routine descriptor
	 * with only PowerPC routines: an installer's accelerated code resource), as Mixed Mode switched a Power Macintosh's
	 * emulated 68K program into native code. It gets a stack; the program's Gestalt stays that of a 68K program.
	 */
	static PpcRuntime forCallsFrom68k(MacApp68k app) throws M68kCpuException {
		PpcRuntime rt=new PpcRuntime(app);
		rt.setUpStack(1024*1024);
		System.err.println("[ppc "+app.name+"] 68K code called PowerPC code: PowerPC runtime started");
		return rt;
	}
	/**
	 * Gives the PowerPC CPU a stack of {@code size} bytes. A PowerPC program's runtime exists before the extensions' INITs run,
	 * and their PowerPC code (QuickTime's trap patches) is called from 68K code then: without a stack r1 was 0 and the frames
	 * went to the top of the address space (the 68K stack) - QuickTime then drew MovieMunger's controller in the old style.
	 */
	void setUpStack(int size) throws M68kCpuException {setUpStack(allocate(size,16),size);}
	/** The stack at {@code base} (a PowerPC program's: the bottom of the application's stack area, above ApplLimit). */
	void setUpStack(int base,int size) throws M68kCpuException {
		stackBase=base;stackTop=stackBase+size;
		cpu.r[1]=(stackTop-128)&~15;memory.writeLong(cpu.r[1],0);
	}

	/** Loads the application's fragment and its libraries, then calls its main routine; returns when it returns. */
	void runApplication() throws M68kException,IOException {
		FragmentEntry e=applicationFragment(app.resources);
		if(e==null)throw new IOException("no PowerPC application fragment in the 'cfrg' resource");
		if(e.where!=1&&e.where!=2)throw new IOException("the application's code fragment is neither in its data fork nor in a resource (location kind "+e.where+")");
		int stackSize=Math.max(1024*1024,e.stackSize);
		if(stackTop-stackBase<stackSize)setUpStack(stackSize); // the one the INITs used is free again now: kept when big enough
		else{cpu.r[1]=(stackTop-128)&~15;memory.writeLong(cpu.r[1],0);}
		libraries.installGestalt();
		if(e.where==2){byte[] code=resourceFragment(app.file,app.resources,e);if(code==null)throw new IOException("the application's code fragment resource is missing");
			System.err.println("[ppc "+app.name+"] PowerPC application \""+e.name+"\": "+code.length+" bytes of PEF code (resource "+ResourceFile68k.typeName(e.offset)+" "+(short)e.length+")");
			application=loadFragment(e.name,app.file,code,0,code.length,2,e.offset,e.length);}
		else{
		byte[] data=dataFork(app.file);
		System.err.println("[ppc "+app.name+"] PowerPC application \""+e.name+"\": "+(e.length==0?data.length-e.offset:e.length)+" bytes of PEF code");
		application=loadFragment(e.name,app.file,data,e.offset,e.length);}
		// a Carbon program, or one whose own libraries are Carbon: REALbasic's stub imports only its STRuntime, which imports
		// CarbonLib (むりやりMusic asked for CarbonLib 1.1 because 'cbon' was undefined)
		boolean carbon=false;for(Fragment f:fragments)for(Pef.Library lib:f.pef.libraries)if(lib.name.equals("CarbonLib"))carbon=true;
		if(carbon){ // CarbonLib set the Toolbox up before a Carbon program started
			// cbon (CarbonLib 1.6, the last for Mac OS 9 - Data Rescue asks for 1.5), sysv 9.2.2 and appr: see PpcLibraries.installGestalt
			libraries.carbon.initializeToolbox();
			System.err.println("[ppc "+app.name+"] Carbon program: Toolbox initialized as CarbonLib does");
		}
		int main=application.instance.main();
		if(main==0)throw new IOException("the application fragment has no main routine");
		if(TRACE)System.err.printf("[ppc] main TVector %08X -> code %08X TOC %08X%n",main,memory.readLong(main),memory.readLong(main+4));
		cpu.call(main,new int[0]);
	}

	// ------------------------------------------------------------------ scheduling

	private long polledInstructions;
	/**
	 * The pace of the "real machine" speed for PowerPC programs, in PowerPC instructions a second (millions): the machine most
	 * PowerPC programs met, the iMac (G3 at 233 MHz, about an instruction a clock). The G3 runs the same user instruction set as
	 * the 603 emulated here. The launcher's speed setting and a program's own presets (GAppSettingsDialog.PPC_SPEED_MIPS)
	 * count in these units for PowerPC programs.
	 */
	static final int REAL_MIPS=233;

	/** Timers, deferred work and the speed throttle, as the 68K emulator's poll; PowerPC instructions count one for one (MacApp68k.mips is in PowerPC MIPS here). */
	@Override public void poll(PpcCpu c) throws M68kException {
		if(STATS)statPoll(c);
		if(cpu68.abortRequested())throw new M68kAbortException(c.pc);
		long delta=c.instructions-polledInstructions;polledInstructions=c.instructions;
		// a 68K program's pace counts 68K instructions: PowerPC code it calls (a fat library such as StuffIt Expander's
		// UnStuffEnginePPC) runs natively on the Power Mac: REAL_MIPS PowerPC instructions in the time of DEFAULT_MIPS 68K ones
		cpu68.charge(app!=null&&!app.powerPC?delta*Toolbox68k.DEFAULT_MIPS/REAL_MIPS:delta);
		if(STATS){long t=System.nanoTime();toolbox.poll(cpu68);statPollNanos+=System.nanoTime()-t;}
		else toolbox.poll(cpu68);
	}

	// ------------------------------------------------------------------ statistics (-Dhzppc.stats=true)

	/** Debug aid: every second, the PowerPC instruction rate and the native routines that took the time (count, host ms). */
	static final boolean STATS=Boolean.getBoolean("hzppc.stats");
	private long[] statCount=new long[0],statNanos=new long[0];private long statStart,statInstructions,statInstructions68,statPollNanos;
	private void statCall(int index,PpcCpu c) throws M68kException {
		if(index>=statCount.length){statCount=java.util.Arrays.copyOf(statCount,index+64);statNanos=java.util.Arrays.copyOf(statNanos,index+64);}
		long t=System.nanoTime();
		try{natives.get(index).call(this,c);}finally{statCount[index]++;statNanos[index]+=System.nanoTime()-t;}
	}
	/** PC samples (one per poll, every few thousand instructions) by the fragment whose code holds them; the last slot is "elsewhere". */
	private long[] statFragmentSamples=new long[0];
	private void statSample(int pc){
		int n=fragments.size();
		if(statFragmentSamples.length<n+1)statFragmentSamples=java.util.Arrays.copyOf(statFragmentSamples,n+17);
		for(int i=0;i<n;i++){Fragment f=fragments.get(i);if(f.instance==null)continue;
			for(int s=0;s<f.instance.sectionAddress.length;s++){int a=f.instance.sectionAddress[s];
				if(a!=0&&f.pef.sections[s].kind==Pef.KIND_CODE&&pc-a>=0&&pc-a<f.pef.sections[s].totalSize){statFragmentSamples[i]++;return;}}}
		statFragmentSamples[statFragmentSamples.length-1]++;
	}
	/** -Dhzppc.statsTop=N: how many native calls each -Dhzppc.stats line lists (most time first). */
	private static final int STATS_TOP=Integer.getInteger("hzppc.statsTop",8);
	private void statPoll(PpcCpu c){
		statSample(c.pc);
		long now=System.nanoTime();
		if(statStart==0){statStart=now;statInstructions=c.instructions;statInstructions68=cpu68.instructions;return;}
		if(now-statStart<1_000_000_000L)return;
		double secs=(now-statStart)/1e9;
		StringBuilder sb=new StringBuilder(String.format("[ppc stats] %.1f PPC MIPS, %.1f 68K MIPS, poll+throttle %.0f ms;",(c.instructions-statInstructions)/secs/1e6,(cpu68.instructions-statInstructions68)/secs/1e6,statPollNanos/1e6/secs));
		Integer[] order=new Integer[statCount.length];for(int i=0;i<order.length;i++)order[i]=i;
		java.util.Arrays.sort(order,(a,b)->Long.compare(statNanos[b],statNanos[a]));
		for(int k=0;k<STATS_TOP&&k<order.length&&statCount[order[k]]>0;k++){int i=order[k];sb.append(String.format(" %s %d/%.0fms",nativeName(i),statCount[i],statNanos[i]/1e6/secs));}
		long samples=0;for(long v:statFragmentSamples)samples+=v;
		if(samples>0){sb.append(" | PPC time:");
			Integer[] fo=new Integer[statFragmentSamples.length];for(int i=0;i<fo.length;i++)fo[i]=i;
			java.util.Arrays.sort(fo,(a,b)->Long.compare(statFragmentSamples[b],statFragmentSamples[a]));
			for(int k=0;k<5&&statFragmentSamples[fo[k]]>0;k++){int i=fo[k];sb.append(String.format(" %s %.0f%%",i==statFragmentSamples.length-1?"(other)":fragments.get(i).name,100.0*statFragmentSamples[i]/samples));}
			java.util.Arrays.fill(statFragmentSamples,0);}
		System.err.println(sb);
		java.util.Arrays.fill(statCount,0);java.util.Arrays.fill(statNanos,0);statStart=now;statInstructions=c.instructions;statInstructions68=cpu68.instructions;statPollNanos=0;
	}

	/** The name of the import stored at {@code slot}, or null. */
	String importAt(int slot){for(Fragment f:fragments){String n=f.pef.importSlots.get(slot);if(n!=null)return f.name+" -> "+n;}return null;}
	/** A crash report for the PowerPC side (the host shows the 68K report's first line). */
	/** The program ended: what CarbonLib wrote back at quit (CFPreferences set but never synchronized). */
	void shutDown(){if(libraries!=null&&libraries.coreFoundation!=null)libraries.coreFoundation.flushPreferences();}
	String describe(int at){
		StringBuilder sb=new StringBuilder(cpu.dumpState(at));
		for(Fragment f:fragments){
			if(f.instance==null)continue;
			sb.append(String.format("%nfragment \"%s\":",f.name));
			for(int i=0;i<f.instance.sectionAddress.length;i++)if(f.instance.sectionAddress[i]!=0)sb.append(String.format(" section %d (kind %d) at %08X+%X",i,f.pef.sections[i].kind,f.instance.sectionAddress[i],f.pef.sections[i].totalSize));
		}
		return sb.toString();
	}
}

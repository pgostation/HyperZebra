/**
 * PowerPC 601/603 interpreter for hosted PowerPC Macintosh applications (the user-mode instruction set a Mac program uses).
 *
 * Covered: the 32-bit integer, branch, condition-register, load/store (incl. update, indexed, byte-reversed, multiple and
 * string forms, lwarx/stwcx.), rotate/shift, the floating-point instructions of the 603 (fsel, fres, frsqrte, fsqrt
 * too), FPSCR moves, mftb and the 601 real-time clock, and no-op cache/sync instructions (dcbz clears its block). Left out
 * on purpose: supervisor instructions (MMU, segment registers, rfi, tlb*), the 64-bit and AltiVec extensions of later
 * CPUs and the POWER-only leftovers of the 601 - Mac compilers did not emit them for applications.
 *
 * The CPU shares its address space ({@link M68kMemory}) with the 68K emulator of the same process, as on a real Power
 * Macintosh: the Toolbox is served by the 68K layer through Mixed Mode (see {@link PpcRuntime}). Floating-point
 * registers are kept as raw IEEE bit patterns (long) so that code moving 8-byte data through them (lfd/stfd copies of
 * structures) never has a NaN payload changed by the host.
 *
 * Primary opcode 1, unused by the architecture, is the host gate: {@code 0x04000000|n} calls native routine n (a shared
 * library function implemented in Java) and continues at LR, like a leaf function that ends with blr.
 *
 * With {@link #useJit} on, code that runs often is compiled ({@link PpcJit}) and the interpreter runs the rest; the two
 * compute the same results and can be switched at any scheduler poll.
 */
final class PpcCpu {
	/** The address a call from Java returns to (set as LR); reaching it ends {@link #call}. Never a valid code address. */
	static final int RETURN_SENTINEL=0xfffffff0;
	/** Primary opcode of the host gate instruction. */
	static final int GATE_OPCODE=1;

	/** A native routine reached through the host gate. */
	interface Natives { void call(PpcCpu cpu,int index) throws M68kException; }
	/** Called every {@link #POLL_INTERVAL} instructions (timers, deferred work, the speed throttle, abort). */
	interface Scheduler { void poll(PpcCpu cpu) throws M68kException; }

	final int[] r=new int[32];
	/** Floating-point registers as raw double bit patterns. */
	final long[] f=new long[32];
	int pc,lr,ctr,cr,xer,fpscr;
	/** Executed instructions (statistics, speed throttle). */
	long instructions;
	final M68kMemory memory;
	private final Natives natives;
	private Scheduler scheduler;
	private int nesting;
	/** The lwarx reservation (compiled code reads and clears it too). */
	boolean reservation;
	/** The JIT compiler of this address space, while compiled code runs (null: every instruction is interpreted). */
	PpcJit jit;
	/** For compiled code: the instructions a region may run before it must return for the next scheduler poll (PpcJit). */
	int jitStop;
	/** The indirect branch (bclr, bcctr) by which compiled code last left its region. */
	int jitBranch;
	/** The region running now and its thread (PpcJit: a store of its own that changes its code stops it). */
	PpcJit.Code jitRunning;Thread jitThread;
	/** -Dhzppc.stats: compiled region runs, instructions interpreted in JIT mode, and region entries by address (sampled). */
	static final boolean JIT_STATS=Boolean.getBoolean("hzppc.stats");
	long jitRuns,jitInterpreted;final java.util.HashMap<Integer,int[]> jitEntrySamples=JIT_STATS?new java.util.HashMap<Integer,int[]>():null;
	private static final int POLL_INTERVAL=4096;
	/** Ring buffer of recent instruction addresses for crash dumps (-Dhzppc.pchistory=n, a power of two). */
	private static final int PC_HISTORY=Integer.getInteger("hzppc.pchistory",256);
	private final int[] pcHistory=new int[PC_HISTORY];
	private int pcHistoryPos;
	/** Debug aid: -Dhzppc.pctrace=start-end[,start-end...] (hex) prints every instruction executed in those ranges (and the
	 *  callers at each range's first address). */
	private static final int TRACE_START,TRACE_END;private static final int[] TRACE_RANGES;
	static{String s=System.getProperty("hzppc.pctrace","");String[] parts=s.isEmpty()?new String[0]:s.split(",");TRACE_RANGES=new int[parts.length*2];
		long lo=Long.MAX_VALUE,hi=0;
		for(int i=0;i<parts.length;i++){int d=parts[i].indexOf('-');TRACE_RANGES[2*i]=(int)Long.parseLong(parts[i].substring(0,d),16);TRACE_RANGES[2*i+1]=(int)Long.parseLong(parts[i].substring(d+1),16);
			lo=Math.min(lo,TRACE_RANGES[2*i]&0xffffffffL);hi=Math.max(hi,TRACE_RANGES[2*i+1]&0xffffffffL);}
		if(parts.length>0){TRACE_START=(int)lo;TRACE_END=(int)hi;}else{TRACE_START=1;TRACE_END=0;}}
	/** Debug aid (commands.txt "ppccalls on|off"): prints every bl/bcl/bctrl/bclrl taken with its target and r3-r5. */
	static boolean CALL_TRACE;
	private void traceCall(int at){int tv=0;try{if(r[12]!=0&&(r[12]&3)==0)tv=memory.readLong(r[12]);}catch(M68kCpuException e){}System.err.printf("[ppc] call %08X -> %08X r3=%08X r4=%08X r5=%08X r12=%08X(%08X)%n",at,pc,r[3],r[4],r[5],r[12],tv);}
	private static boolean traced(int at){for(int i=0;i<TRACE_RANGES.length;i+=2)if(at>=TRACE_RANGES[i]&&at<TRACE_RANGES[i+1])return true;return false;}
	private static boolean traceStart(int at){for(int i=0;i<TRACE_RANGES.length;i+=2)if(at==TRACE_RANGES[i])return true;return false;}
	/** Time base origin: mftb/RTC count from here. */
	private final long timeBaseOrigin=System.nanoTime();
	/** The 603 time base runs at a quarter of the bus clock; 25 MHz (a 100 MHz bus) is what code of the time expected. */
	static final long TIME_BASE_HZ=25_000_000L;

	PpcCpu(M68kMemory memory,Natives natives){this.memory=memory;this.natives=natives;}
	/** Debug aid: -Dhzppc.pctracefp=true adds the non-zero FPRs (as doubles) to every pctrace line. */
	private static final boolean TRACE_FP=Boolean.getBoolean("hzppc.pctracefp");
	/** Debug (pctrace): the non-zero registers r13-r30 (and f0-f31 with hzppc.pctracefp). */
	private String highRegisters(){StringBuilder b=new StringBuilder();for(int i=13;i<31;i++)if(r[i]!=0)b.append(String.format(" r%d=%08X",i,r[i]));
		if(TRACE_FP)for(int i=0;i<32;i++)if(f[i]!=0)b.append(" f").append(i).append('=').append(Double.longBitsToDouble(f[i]));
		return b.toString();}
	/** Debug: lr plus the saved LRs along the stack back chain (pctrace prints it at the range's first address). */
	String backChain(){
		StringBuilder sb=new StringBuilder(String.format("%08X",lr));int sp=r[1];
		try{for(int i=0;i<24&&sp!=0;i++){int next=memory.readLong(sp);if(next==0||next<=sp)break;sb.append(String.format(" %08X",memory.readLong(next+8)));sp=next;}}catch(M68kCpuException e){sb.append(" ?");}
		return sb.toString();
	}
	void setScheduler(Scheduler s){scheduler=s;}
	int nestingDepth(){return nesting;}

	// ------------------------------------------------------------------ calls from Java

	/** Everything a nested call from Java must give back to the interrupted PowerPC code. */
	private static final class State { final int[] r=new int[32];final long[] f=new long[32];int pc,lr,ctr,cr,xer,fpscr; }
	private State save(){State s=new State();System.arraycopy(r,0,s.r,0,32);System.arraycopy(f,0,s.f,0,32);s.pc=pc;s.lr=lr;s.ctr=ctr;s.cr=cr;s.xer=xer;s.fpscr=fpscr;return s;}
	private void restore(State s){System.arraycopy(s.r,0,r,0,32);System.arraycopy(s.f,0,f,0,32);pc=s.pc;lr=s.lr;ctr=s.ctr;cr=s.cr;xer=s.xer;fpscr=s.fpscr;}

	/** The registers and the host-call nesting of one Java stack: what a Thread Manager coroutine thread (ThreadManager68k)
	 *  keeps while another runs. */
	static final class Context { private State state;private int nesting,lastR4,exceptionDepth;private long lastF1;private boolean reservation; }
	Context saveContext(){Context c=new Context();c.state=save();c.nesting=nesting;c.lastR4=lastR4;c.lastF1=lastF1;c.reservation=reservation;c.exceptionDepth=exceptionDepth;return c;}
	void restoreContext(Context c){restore(c.state);nesting=c.nesting;lastR4=c.lastR4;lastF1=c.lastF1;reservation=c.reservation;exceptionDepth=c.exceptionDepth;}
	/** A fresh Java stack's context: the registers of {@code template} with its own stack pointer and no host calls below it. */
	static Context startContext(Context template,int stackPointer){
		Context c=new Context();c.state=new State();
		System.arraycopy(template.state.r,0,c.state.r,0,32);System.arraycopy(template.state.f,0,c.state.f,0,32);
		c.state.cr=template.state.cr;c.state.xer=template.state.xer;c.state.fpscr=template.state.fpscr;
		c.state.r[1]=stackPointer;c.state.pc=RETURN_SENTINEL;c.state.lr=RETURN_SENTINEL;return c;
	}

	/**
	 * Calls the function whose transition vector (code address, TOC) is at {@code tvector} with integer arguments in
	 * r3-r10 and the rest on the stack, as the PowerPC runtime does; returns r3. The interrupted code's registers are all
	 * restored afterwards (the caller is Java, or a native routine in the middle of a call from PowerPC code).
	 */
	int call(int tvector,int... args) throws M68kException {return call(tvector,args,null);}
	/** As {@link #call(int,int...)}; {@code fargs} (may be null) are double arguments for f1-f13. The results (r3, r4, f1) are left in {@link #lastResult}. */
	int call(int tvector,int[] args,double[] fargs) throws M68kException {
		int code=memory.readLong(tvector),toc=memory.readLong(tvector+4);
		return callCode(code,toc,args,fargs,tvector);
	}
	/** r3, r4 and f1 as the last call from Java returned them. */
	int lastR4;long lastF1;
	int callCode(int code,int toc,int[] args,double[] fargs) throws M68kException {return callCode(code,toc,args,fargs,0);}
	/** {@code tvector}: r12 holds it on entry, as after the pointer glue of a call through a function pointer (code built at run
	 *  time finds its own data through r12: Warcraft II's trap-patch trampolines). */
	int callCode(int code,int toc,int[] args,double[] fargs,int tvector) throws M68kException {
		if(r[1]==0)throw new M68kCpuException(code,"PowerPC code called without a PowerPC stack (r1 = 0)");
		State saved=save();
		try{
			int n=args==null?0:args.length;
			// a caller frame below the interrupted code's stack: linkage area (24) + parameter area (at least 8 words) + slack
			int sp=(r[1]-256-(24+4*Math.max(8,n)))&~15;
			memory.writeLong(sp,r[1]);
			for(int i=0;i<n;i++){if(i<8)r[3+i]=args[i];memory.writeLong(sp+24+4*i,args[i]);}
			if(fargs!=null)for(int i=0;i<fargs.length&&i<13;i++)f[1+i]=Double.doubleToRawLongBits(fargs[i]);
			// &~3: callers reach a transition vector through mtctr/bctr, which ignores the low two bits; Macintosh Common Lisp
			// (SK8 1.2 PPC) keeps tagged code addresses in its TVectors (init = code section + $3C49E)
			r[1]=sp;r[2]=toc;r[12]=tvector;lr=RETURN_SENTINEL;pc=code&~3;
			nesting++;
			try{executeCatching(nesting);}finally{nesting--;}
			int result=r[3];lastR4=r[4];lastF1=f[1];
			return result;
		}finally{restore(saved);}
	}

	/** Runs from the current state until the code returns to {@link #RETURN_SENTINEL}. */
	void run() throws M68kException {nesting++;try{executeCatching(nesting);}finally{nesting--;}}

	/**
	 * A longjmp to a setjmp made at a shallower host call level: unwinds the Java levels in between (their code will never
	 * return normally) until the execution loop of {@code depth}, which applies the saved registers and goes on.
	 */
	static final class Unwind extends M68kException {
		private static final long serialVersionUID=1L;
		final int depth;final transient Runnable apply;
		Unwind(int depth,Runnable apply){super("PowerPC longjmp past a host call (to level "+depth+")");this.depth=depth;this.apply=apply;}
	}
	private void executeCatching(int depth) throws M68kException {
		for(;;){
			try{while(!execute()){}return;}
			catch(Unwind u){if(u.depth!=depth)throw u;u.apply.run();}
		}
	}

	// ------------------------------------------------------------------ execution

	/**
	 * Runs until the code returns to {@link #RETURN_SENTINEL} (true) or the next scheduler poll (false; the caller loops).
	 * Returning at every poll gives HotSpot a fresh entry into compiled code: a program's main loop never returns, and its
	 * on-stack-replaced frame stayed bound to the interpreted {@link #step} after a recompilation - Woden ran at 18 MIPS
	 * instead of 300 for the rest of its life.
	 */
	private boolean execute() throws M68kException {
		if(jit!=null&&!CALL_TRACE)return executeJit();
		for(;;){
			if(pc==RETURN_SENTINEL)return true;
			int at=pc;
			if((at&3)!=0||at==0)throw new M68kCpuException(at,at==0?"PowerPC code jumped to address 0 ("+glueCallee("a NIL function pointer or an unresolved import")+")":"PowerPC code jumped to an unaligned address"+glueCallee(""));
			byte[] m=memory.raw();int p=memory.physical(at);
			if(p>m.length-4)throw new M68kCpuException(at,"PowerPC code outside the address space");
			int op=(m[p]&0xff)<<24|(m[p+1]&0xff)<<16|(m[p+2]&0xff)<<8|m[p+3]&0xff;
			pcHistory[pcHistoryPos++&(PC_HISTORY-1)]=at;
			if(at>=TRACE_START&&at<TRACE_END&&traced(at))System.err.printf("[ppc] %08X %08X r0=%08X r1=%08X r2=%08X r3=%08X r4=%08X r5=%08X r6=%08X r7=%08X r8=%08X r9=%08X r10=%08X r11=%08X r12=%08X r31=%08X lr=%08X ctr=%08X cr=%08X%s%n",at,op,r[0],r[1],r[2],r[3],r[4],r[5],r[6],r[7],r[8],r[9],r[10],r[11],r[12],r[31],lr,ctr,cr,highRegisters());
			if(at>=TRACE_START&&at<TRACE_END&&traceStart(at))System.err.println("[ppc] callers: "+backChain());
			pc=at+4;
			try{step(op,at);}
			catch(IllegalInstruction e){if(e.level!=nesting||e.pc!=at||!deliverException(ILLEGAL_INSTRUCTION_EXCEPTION,at,0))throw e;} // not one a nested run gave up on
			catch(ProtectionFault e){if(e.level!=nesting||!deliverException(READ_ONLY_MEMORY_EXCEPTION,at,e.address))throw e;} // the handler makes the page writable and the store runs again
			if(CALL_TRACE&&lr==at+4&&pc!=at+4&&(op>>>26==18||op>>>26==16||op>>>26==19))traceCall(at);
			if(WATCH){if(at==WATCH_AT_PC)M68kCpu.WATCH=r[WATCH_AT_REG]+WATCH_AT_OFFSET;checkWatch(at);}
			if((++instructions&(POLL_INTERVAL-1))==0){if(scheduler!=null)scheduler.poll(this);return false;}
		}
	}

	/** Turns the JIT on or off (at the next scheduler poll); the debug traces keep the interpreter. */
	void useJit(boolean on){
		if(on&&(TRACE_RANGES.length>0||WATCH)){if(!jitBlockedTold){jitBlockedTold=true;System.err.println("[ppc] JIT off while -Dhzppc.pctrace / -Dhz68k.watch trace instructions");}on=false;}
		if(!on&&jit!=null)jit.clear(); // the interpreter only from now on: the compiled classes can go
		jit=on?PpcJit.attach(memory,this):null;
	}
	private static boolean jitBlockedTold;

	/**
	 * {@link #execute} with the JIT: compiled regions run where they exist, the rest is interpreted up to its next control
	 * transfer (where a region may start). A region returns when it reaches the next poll, so the poll keeps its pace.
	 */
	private boolean executeJit() throws M68kException {
		PpcJit j=jit;
		int[] keys=j.cacheKey;PpcJit.Code[] codes=j.cacheCode;
		jitThread=Thread.currentThread();
		for(;;){
			int at=pc;
			if(at==RETURN_SENTINEL)return true;
			int slot=(at>>>2)&PpcJit.CACHE_MASK;
			PpcJit.Code code=keys[slot]==at?codes[slot]:j.lookup(at);
			if(code==PpcJit.COLD)code=j.warm(at)?j.lookup(at):null;
			if(code!=null&&code!=PpcJit.NONE){
				long before=instructions;
				jitStop=POLL_INTERVAL-(int)(before&(POLL_INTERVAL-1));
				pcHistory[pcHistoryPos++&(PC_HISTORY-1)]=at;
				jitRunning=code;
				int n;
				if(JIT_STATS&&(++jitRuns&63)==0)jitEntrySamples.computeIfAbsent(at,k->new int[1])[0]++;
				try{n=code.run(this);}finally{jitRunning=null;}
				if(pc==0||(pc&3)!=0)pcHistory[pcHistoryPos++&(PC_HISTORY-1)]=jitBranch; // what glueCallee looks behind
				if(n>=0){
					instructions+=n; // not before+n: natives the region called ran code that counted its own
					if(((before^instructions)&-POLL_INTERVAL)!=0){if(scheduler!=null)scheduler.poll(this);return false;}
					continue;
				}
				if(n<-1){ // stopped before an instruction the interpreter runs (a failed access: it fails again, for real)
					instructions+=-2-n;
					boolean due=((before^instructions)&-POLL_INTERVAL)!=0;
					if(interpretBlock())return false;
					if(due){if(scheduler!=null)scheduler.poll(this);return false;}
					continue;
				}
			}
			if(interpretBlock())return false;
		}
	}
	/**
	 * A host gate that compiled code reached through an indirect branch (glue calling a native routine), with the registers
	 * back in the CPU: runs it as {@link #step} does. False when the code must not go on from where it returns to (a region was
	 * dropped meanwhile, maybe that one: code the native loaded over it; the JIT was turned off).
	 */
	boolean jitGate(int at) throws M68kException {
		PpcJit j=jit;PpcJit.Code running=jitRunning;int stop=jitStop;long drops=j.dropped;
		int op=memory.readLong(at);
		pcHistory[pcHistoryPos++&(PC_HISTORY-1)]=at;
		pc=at+4;jitRunning=null; // PowerPC code the native runs is not the region's own
		try{natives.call(this,op&0x03ffffff);}finally{jitRunning=running;}
		if(pc==at+4)pc=lr&~3;
		jitStop=stop; // a nested run set its own
		return jit==j&&j.dropped==drops;
	}
	/** Interprets from pc up to the next control transfer (JIT mode, code without a region); true when a scheduler poll was made. */
	private boolean interpretBlock() throws M68kException {
		for(;;){
			int at=pc;
			if(at==RETURN_SENTINEL)return false;
			if((at&3)!=0||at==0)throw new M68kCpuException(at,at==0?"PowerPC code jumped to address 0 ("+glueCallee("a NIL function pointer or an unresolved import")+")":"PowerPC code jumped to an unaligned address"+glueCallee(""));
			byte[] m=memory.raw();int p=memory.physical(at);
			if(p>m.length-4)throw new M68kCpuException(at,"PowerPC code outside the address space");
			int op=(m[p]&0xff)<<24|(m[p+1]&0xff)<<16|(m[p+2]&0xff)<<8|m[p+3]&0xff;
			pcHistory[pcHistoryPos++&(PC_HISTORY-1)]=at;
			pc=at+4;
			if(JIT_STATS&&(++jitInterpreted&63)==0)jitEntrySamples.computeIfAbsent(at|1,k->new int[1])[0]++;
			try{step(op,at);}
			catch(IllegalInstruction e){if(e.level!=nesting||e.pc!=at||!deliverException(ILLEGAL_INSTRUCTION_EXCEPTION,at,0))throw e;}
			catch(ProtectionFault e){if(e.level!=nesting||!deliverException(READ_ONLY_MEMORY_EXCEPTION,at,e.address))throw e;}
			if((++instructions&(POLL_INTERVAL-1))==0){if(scheduler!=null)scheduler.poll(this);return true;}
			if(pc!=at+4)return false;
		}
	}

	private M68kCpuException illegal(int at,int op){return new IllegalInstruction(at,nesting,String.format("unsupported PowerPC instruction $%08X (opcode %d, extended %d)",op,op>>>26,(op>>>1)&0x3ff)+(op==0?glueCallee(""):""));}
	/** An instruction the CPU does not execute: an illegal instruction exception for the program's handler, if it has one. */
	private static final class IllegalInstruction extends M68kCpuException {
		private static final long serialVersionUID=1L;
		/** The run ({@link #nesting}) whose instruction it was. */
		final int level;
		IllegalInstruction(int pc,int level,String detail){super(pc,detail);this.level=level;}
	}

	// ------------------------------------------------------------------ machine exceptions

	/**
	 * InstallExceptionHandler's handler (a transition vector, 0 = none). The exceptions are delivered as Mac OS does
	 * (MachineExceptions.h): an illegal instruction, a trap taken (tw, twi) and a store into a page the program protected
	 * (readOnlyMemoryException) call the handler with an ExceptionInformation whose PC is the excepting instruction; when it
	 * answers noErr, execution goes on with the registers as it left them in the records (it steps over the instruction
	 * itself, or makes the page writable and lets the store run again). The records and the handler's frames go on the
	 * excepting code's own stack, below a 224-byte red zone, so a Thread Manager thread may switch away inside its handler.
	 * Without a handler, or when it declines, things go as before (an illegal instruction stops the program, a trap is
	 * ignored). Macintosh Common Lisp 4 (SK8 1.2 PPC) runs on this: its UUOs (opcode 0), allocation/interrupt traps and the
	 * guard pages of its stacks and heap are such exceptions.
	 */
	int exceptionHandler;
	/** Handlers running (nested) in this context: a runaway recursion is cut off at {@link #EXCEPTION_NESTING}. */
	private int exceptionDepth;
	/** Debug: -Dhzppc.exceptiontrace=true logs every exception delivered and the handler's answer. */
	private static final boolean EXCEPTION_TRACE=Boolean.getBoolean("hzppc.exceptiontrace");
	static final int ILLEGAL_INSTRUCTION_EXCEPTION=1,TRAP_EXCEPTION=2,READ_ONLY_MEMORY_EXCEPTION=6;
	/** Bytes of one set of records: ExceptionInformation (32), MachineInformation (64), RegisterInformation (256), FPUInformation (264), MemoryExceptionInformation (16). */
	static final int EXCEPTION_RECORDS=0x280,EXCEPTION_NESTING=16,RED_ZONE=224;
	private boolean deliverException(int kind,int at,int address) throws M68kException {
		int sp=r[1];
		if(exceptionHandler==0||sp==0||exceptionDepth>=EXCEPTION_NESTING)return false;
		int info=(sp-RED_ZONE-EXCEPTION_RECORDS)&~15,machine=info+32,regs=machine+64,fpu=regs+256,mem=fpu+264;
		memory.fill(info,EXCEPTION_RECORDS,0);
		memory.writeLong(info,kind);memory.writeLong(info+4,machine);memory.writeLong(info+8,regs);memory.writeLong(info+12,fpu);memory.writeLong(info+16,mem);
		memory.writeLong(machine+4,ctr);memory.writeLong(machine+12,lr);memory.writeLong(machine+20,at);
		memory.writeLong(machine+24,cr);memory.writeLong(machine+28,xer);
		boolean access=kind==READ_ONLY_MEMORY_EXCEPTION;
		memory.writeLong(machine+32,0x0000d030|(access?0:kind==TRAP_EXCEPTION?0x00020000:0x00080000)); // MSR as SRR1 gives it: the program exception's reason bit
		memory.writeLong(machine+40,access?0x300:0x700); // the vector: data storage / program exception
		if(access){memory.writeLong(machine+44,address);memory.writeLong(machine+48,0x0a000000);} // DAR, DSISR (protection, store)
		for(int i=0;i<32;i++){memory.writeLong(regs+8*i+4,r[i]);memory.writeLong(fpu+8*i,(int)(f[i]>>>32));memory.writeLong(fpu+8*i+4,(int)f[i]);}
		memory.writeLong(fpu+256,fpscr);
		memory.writeLong(mem+4,address); // MemoryExceptionInformation: theAddress (theError 0, theReference 0 = kWriteReference)
		if(EXCEPTION_TRACE)System.err.printf("[ppc] exception %d at %08X ($%08X) address %08X depth %d r1=%08X%n",kind,at,memory.readLong(at),address,exceptionDepth,sp);
		int result;
		exceptionDepth++;r[1]=info; // the handler's frames go below the records
		try{result=call(exceptionHandler,info);}finally{exceptionDepth--;r[1]=sp;}
		if(EXCEPTION_TRACE)System.err.printf("[ppc]   exception %d at %08X -> %d, resumes at %08X%n",kind,at,result,memory.readLong(machine+20));
		if(result!=0)return false;
		ctr=memory.readLong(machine+4);lr=memory.readLong(machine+12);pc=memory.readLong(machine+20);
		cr=memory.readLong(machine+24);xer=memory.readLong(machine+28);
		for(int i=0;i<32;i++){r[i]=memory.readLong(regs+8*i+4);f[i]=(long)memory.readLong(fpu+8*i)<<32|memory.readLong(fpu+8*i+4)&0xffffffffL;}
		fpscr=memory.readLong(fpu+256);
		return true;
	}

	private void step(int op,int at) throws M68kException {
		int rd=(op>>>21)&31,ra=(op>>>16)&31;
		switch(op>>>26){
			case GATE_OPCODE:{natives.call(this,op&0x03ffffff);if(pc==at+4)pc=lr&~3;return;}
			case 3:{if(trap(rd,r[ra],(short)op)&&!deliverException(TRAP_EXCEPTION,at,0))trapTaken(at,op);return;} // twi
			case 7:r[rd]=r[ra]*(short)op;return; // mulli
			case 8:{int a=r[ra],imm=(short)op;int res=~a+imm+1;setCa(Long.compareUnsigned(((long)~a&0xffffffffL)+((long)imm&0xffffffffL)+1,0xffffffffL)>0);r[rd]=res;return;} // subfic
			case 10:{int crf=rd>>>2;compare(crf,Integer.compareUnsigned(r[ra],op&0xffff));return;} // cmpli
			case 11:{int crf=rd>>>2;compare(crf,Integer.compare(r[ra],(short)op));return;} // cmpi
			case 12:case 13:{int a=r[ra],imm=(short)op,res=a+imm;setCa(Integer.compareUnsigned(res,a)<0);r[rd]=res;if((op>>>26)==13)cr0(res);return;} // addic, addic.
			case 14:r[rd]=(ra==0?0:r[ra])+(short)op;return; // addi
			case 15:r[rd]=(ra==0?0:r[ra])+(op<<16);return; // addis
			case 16:{ // bc
				int bo=rd,bi=ra;
				if((bo&4)==0)ctr--;
				boolean ctrOk=(bo&4)!=0||((ctr!=0)^((bo&2)!=0));
				boolean condOk=(bo&16)!=0||(((cr>>>(31-bi))&1)==((bo>>>3)&1));
				if((op&1)!=0)lr=at+4;
				if(ctrOk&&condOk)pc=((op&2)!=0?0:at)+(short)(op&0xfffc);
				return;}
			case 17:System.err.printf("[ppc] sc at %08X ignored%n",at);return;
			case 18:{int disp=(op<<6)>>6&~3;if((op&1)!=0)lr=at+4;pc=((op&2)!=0?0:at)+disp;return;} // b
			case 19:op19(op,at);return;
			case 20:{int sh=(op>>>11)&31,mb=(op>>>6)&31,me=(op>>>1)&31,m=mask(mb,me);int res=(Integer.rotateLeft(r[rd],sh)&m)|(r[ra]&~m);r[ra]=res;if((op&1)!=0)cr0(res);return;} // rlwimi
			case 21:{int sh=(op>>>11)&31,mb=(op>>>6)&31,me=(op>>>1)&31;int res=Integer.rotateLeft(r[rd],sh)&mask(mb,me);r[ra]=res;if((op&1)!=0)cr0(res);return;} // rlwinm
			case 23:{int sh=r[(op>>>11)&31]&31,mb=(op>>>6)&31,me=(op>>>1)&31;int res=Integer.rotateLeft(r[rd],sh)&mask(mb,me);r[ra]=res;if((op&1)!=0)cr0(res);return;} // rlwnm
			case 24:r[ra]=r[rd]|(op&0xffff);return; // ori
			case 25:r[ra]=r[rd]|(op<<16);return; // oris
			case 26:r[ra]=r[rd]^(op&0xffff);return; // xori
			case 27:r[ra]=r[rd]^(op<<16);return; // xoris
			case 28:{int res=r[rd]&(op&0xffff);r[ra]=res;cr0(res);return;} // andi.
			case 29:{int res=r[rd]&(op<<16);r[ra]=res;cr0(res);return;} // andis.
			case 31:op31(op,at);return;
			case 32:r[rd]=memory.readLong(ea(ra,op));return; // lwz
			case 33:{int ea=r[ra]+(short)op;r[rd]=memory.readLong(ea);r[ra]=ea;return;} // lwzu
			case 34:r[rd]=memory.readByte(ea(ra,op));return; // lbz
			case 35:{int ea=r[ra]+(short)op;r[rd]=memory.readByte(ea);r[ra]=ea;return;} // lbzu
			case 36:store32(ea(ra,op),r[rd]);return; // stw
			case 37:{int ea=r[ra]+(short)op;store32(ea,r[rd]);r[ra]=ea;return;} // stwu
			case 38:store8(ea(ra,op),r[rd]);return; // stb
			case 39:{int ea=r[ra]+(short)op;store8(ea,r[rd]);r[ra]=ea;return;} // stbu
			case 40:r[rd]=memory.readWord(ea(ra,op));return; // lhz
			case 41:{int ea=r[ra]+(short)op;r[rd]=memory.readWord(ea);r[ra]=ea;return;} // lhzu
			case 42:r[rd]=(short)memory.readWord(ea(ra,op));return; // lha
			case 43:{int ea=r[ra]+(short)op;r[rd]=(short)memory.readWord(ea);r[ra]=ea;return;} // lhau
			case 44:store16(ea(ra,op),r[rd]);return; // sth
			case 45:{int ea=r[ra]+(short)op;store16(ea,r[rd]);r[ra]=ea;return;} // sthu
			case 46:{int ea=ea(ra,op);for(int i=rd;i<32;i++,ea+=4)r[i]=memory.readLong(ea);return;} // lmw
			case 47:{int ea=ea(ra,op);for(int i=rd;i<32;i++,ea+=4)store32(ea,r[i]);return;} // stmw
			case 48:f[rd]=singleToDouble(memory.readLong(ea(ra,op)));return; // lfs
			case 49:{int ea=r[ra]+(short)op;f[rd]=singleToDouble(memory.readLong(ea));r[ra]=ea;return;} // lfsu
			case 50:f[rd]=readDouble(ea(ra,op));return; // lfd
			case 51:{int ea=r[ra]+(short)op;f[rd]=readDouble(ea);r[ra]=ea;return;} // lfdu
			case 52:store32(ea(ra,op),doubleToSingle(f[rd]));return; // stfs
			case 53:{int ea=r[ra]+(short)op;store32(ea,doubleToSingle(f[rd]));r[ra]=ea;return;} // stfsu
			case 54:writeDouble(ea(ra,op),f[rd]);return; // stfd
			case 55:{int ea=r[ra]+(short)op;writeDouble(ea,f[rd]);r[ra]=ea;return;} // stfdu
			case 59:op59(op,at);return;
			case 63:op63(op,at);return;
			default:throw illegal(at,op);
		}
	}

	private int ea(int ra,int op){return (ra==0?0:r[ra])+(short)op;}
	private int eax(int op){int ra=(op>>>16)&31;return (ra==0?0:r[ra])+r[(op>>>11)&31];}
	private static int mask(int mb,int me){int a=-1>>>mb,b=-1<<(31-me);return mb<=me?a&b:a|b;}
	private void cr0(int v){cr=(cr&0x0fffffff)|(v<0?0x80000000:v>0?0x40000000:0x20000000)|((xer>>>3)&0x10000000);}
	/** Sets CR field {@code crf} from a comparison result (-1, 0, 1) and XER[SO]. */
	private void compare(int crf,int c){int bits=(c<0?8:c>0?4:2)|((xer>>>31)&1);int sh=28-4*crf;cr=(cr&~(15<<sh))|(bits<<sh);}
	private void setCa(boolean ca){if(ca)xer|=0x20000000;else xer&=~0x20000000;}
	private int ca(){return (xer>>>29)&1;}
	private void setOv(boolean ov){if(ov)xer|=0xc0000000;else xer&=~0x40000000;}
	private static boolean trap(int to,int a,int b){
		return (to&16)!=0&&a<b||(to&8)!=0&&a>b||(to&4)!=0&&a==b||(to&2)!=0&&Integer.compareUnsigned(a,b)<0||(to&1)!=0&&Integer.compareUnsigned(a,b)>0;
	}
	/** A trap instruction whose condition held: without a debugger there is nothing to break into; the program goes on. */
	private void trapTaken(int at,int op){if(trapReports++<20)System.err.printf("[ppc] trap $%08X taken at %08X (no debugger: continuing)%n",op,at);}
	private int trapReports;

	private void op19(int op,int at) throws M68kException {
		int bt=(op>>>21)&31,ba=(op>>>16)&31,bb=(op>>>11)&31;
		switch((op>>>1)&0x3ff){
			case 0:{int sf=28-4*(ba>>>2),df=28-4*(bt>>>2);int v=(cr>>>sf)&15;cr=(cr&~(15<<df))|(v<<df);return;} // mcrf
			case 16:case 528:{ // bclr, bcctr
				int bo=bt,bi=ba;boolean toCtr=((op>>>1)&0x3ff)==528;
				if(!toCtr&&(bo&4)==0)ctr--;
				boolean ctrOk=toCtr||(bo&4)!=0||((ctr!=0)^((bo&2)!=0));
				boolean condOk=(bo&16)!=0||(((cr>>>(31-bi))&1)==((bo>>>3)&1));
				int target=(toCtr?ctr:lr)&~3;
				if((op&1)!=0)lr=at+4;
				if(ctrOk&&condOk)pc=target;
				return;}
			case 33:crOp(bt,~(crBit(ba)|crBit(bb)));return; // crnor
			case 129:crOp(bt,crBit(ba)&~crBit(bb));return; // crandc
			case 150:return; // isync
			case 193:crOp(bt,crBit(ba)^crBit(bb));return; // crxor
			case 225:crOp(bt,~(crBit(ba)&crBit(bb)));return; // crnand
			case 257:crOp(bt,crBit(ba)&crBit(bb));return; // crand
			case 289:crOp(bt,~(crBit(ba)^crBit(bb)));return; // creqv
			case 417:crOp(bt,crBit(ba)|~crBit(bb));return; // crorc
			case 449:crOp(bt,crBit(ba)|crBit(bb));return; // cror
			default:throw illegal(at,op);
		}
	}
	private int crBit(int b){return (cr>>>(31-b))&1;}
	private void crOp(int b,int v){if((v&1)!=0)cr|=1<<(31-b);else cr&=~(1<<(31-b));}

	private void op31(int op,int at) throws M68kException {
		int rd=(op>>>21)&31,ra=(op>>>16)&31,rb=(op>>>11)&31;boolean rc=(op&1)!=0;
		int xo=(op>>>1)&0x3ff;
		switch(xo){
			case 0:compare(rd>>>2,Integer.compare(r[ra],r[rb]));return; // cmp
			case 32:compare(rd>>>2,Integer.compareUnsigned(r[ra],r[rb]));return; // cmpl
			case 4:if(trap(rd,r[ra],r[rb])&&!deliverException(TRAP_EXCEPTION,at,0))trapTaken(at,op);return; // tw
			// XO-form arithmetic (OE variants have bit 0x200 set)
			case 8:case 520:{long a=~r[ra]&0xffffffffL,b=r[rb]&0xffffffffL;long s=a+b+1;int res=(int)s;setCa((s>>>32)!=0);if(xo==520)setOv((long)r[rb]-(long)r[ra]!=res);r[rd]=res;if(rc)cr0(res);return;} // subfc
			case 10:case 522:{long s=(r[ra]&0xffffffffL)+(r[rb]&0xffffffffL);int res=(int)s;setCa((s>>>32)!=0);if(xo==522)setOv((long)r[ra]+(long)r[rb]!=res);r[rd]=res;if(rc)cr0(res);return;} // addc
			case 11:{int res=(int)(((r[ra]&0xffffffffL)*(r[rb]&0xffffffffL))>>>32);r[rd]=res;if(rc)cr0(res);return;} // mulhwu
			case 75:{int res=(int)(((long)r[ra]*(long)r[rb])>>>32);r[rd]=res;if(rc)cr0(res);return;} // mulhw
			case 40:case 552:{int res=r[rb]-r[ra];if(xo==552)setOv((long)r[rb]-(long)r[ra]!=res);r[rd]=res;if(rc)cr0(res);return;} // subf
			case 104:case 616:{int a=r[ra];int res=-a;if(xo==616)setOv(a==Integer.MIN_VALUE);r[rd]=res;if(rc)cr0(res);return;} // neg
			case 136:case 648:{int c=ca();long s=(~r[ra]&0xffffffffL)+(r[rb]&0xffffffffL)+c;int res=(int)s;setCa((s>>>32)!=0);if(xo==648)setOv((long)r[rb]-(long)r[ra]-1+c!=res);r[rd]=res;if(rc)cr0(res);return;} // subfe
			case 138:case 650:{int c=ca();long s=(r[ra]&0xffffffffL)+(r[rb]&0xffffffffL)+c;int res=(int)s;setCa((s>>>32)!=0);if(xo==650)setOv((long)r[ra]+(long)r[rb]+c!=res);r[rd]=res;if(rc)cr0(res);return;} // adde
			case 200:case 712:{int c=ca();long s=(~r[ra]&0xffffffffL)+c;int res=(int)s;setCa((s>>>32)!=0);if(xo==712)setOv((long)~r[ra]+c!=res);r[rd]=res;if(rc)cr0(res);return;} // subfze
			case 202:case 714:{int c=ca();long s=(r[ra]&0xffffffffL)+c;int res=(int)s;setCa((s>>>32)!=0);if(xo==714)setOv((long)r[ra]+c!=res);r[rd]=res;if(rc)cr0(res);return;} // addze
			case 232:case 744:{int c=ca();long s=(~r[ra]&0xffffffffL)+c+0xffffffffL;int res=(int)s;setCa((s>>>32)!=0);if(xo==744)setOv((long)~r[ra]+c-1!=res);r[rd]=res;if(rc)cr0(res);return;} // subfme
			case 234:case 746:{int c=ca();long s=(r[ra]&0xffffffffL)+c+0xffffffffL;int res=(int)s;setCa((s>>>32)!=0);if(xo==746)setOv((long)r[ra]+c-1!=res);r[rd]=res;if(rc)cr0(res);return;} // addme
			case 235:case 747:{long p=(long)r[ra]*(long)r[rb];int res=(int)p;if(xo==747)setOv(p!=res);r[rd]=res;if(rc)cr0(res);return;} // mullw
			case 266:case 778:{int res=r[ra]+r[rb];if(xo==778)setOv((long)r[ra]+(long)r[rb]!=res);r[rd]=res;if(rc)cr0(res);return;} // add
			case 459:case 971:{int b=r[rb];int res=b==0?0:Integer.divideUnsigned(r[ra],b);if(xo==971)setOv(b==0);r[rd]=res;if(rc)cr0(res);return;} // divwu
			case 491:case 1003:{int a=r[ra],b=r[rb];boolean bad=b==0||a==Integer.MIN_VALUE&&b==-1;int res=bad?(a<0&&b==0?-1:0):a/b;if(xo==1003)setOv(bad);r[rd]=res;if(rc)cr0(res);return;} // divw
			// POWER (601) instructions, with the MQ register
			case 107:case 619:{long p=(long)r[ra]*(long)r[rb];mq=(int)p;int res=(int)(p>>>32);if(xo==619)setOv(false);r[rd]=res;if(rc)cr0(res);return;} // mul
			case 331:case 843:{int b=r[rb];long n=((long)r[ra]<<32)|(mq&0xffffffffL);boolean bad=b==0||(b==-1&&n==Long.MIN_VALUE);long qq=bad?0:n/b;if(!bad&&qq!=(int)qq)bad=true;int q=bad?0:(int)qq;mq=bad?0:(int)(n%b);if(xo==843)setOv(bad);r[rd]=q;if(rc)cr0(q);return;} // div
			case 363:case 875:{int a=r[ra],b=r[rb];boolean bad=b==0||a==Integer.MIN_VALUE&&b==-1;int q=bad?0:a/b;mq=bad?0:a%b;if(xo==875)setOv(bad);r[rd]=q;if(rc)cr0(q);return;} // divs
			case 264:case 776:{int a=r[ra],b=r[rb];int res=b>a?b-a:0;r[rd]=res;if(rc)cr0(res);return;} // doz
			case 360:case 872:{int a=r[ra];int res=a<0?-a:a;if(xo==872)setOv(a==Integer.MIN_VALUE);r[rd]=res;if(rc)cr0(res);return;} // abs
			case 488:case 1000:{int a=r[ra];int res=a<0?a:-a;if(xo==1000)setOv(false);r[rd]=res;if(rc)cr0(res);return;} // nabs
			// logical
			case 28:{int res=r[rd]&r[rb];r[ra]=res;if(rc)cr0(res);return;} // and
			case 60:{int res=r[rd]&~r[rb];r[ra]=res;if(rc)cr0(res);return;} // andc
			case 124:{int res=~(r[rd]|r[rb]);r[ra]=res;if(rc)cr0(res);return;} // nor
			case 284:{int res=~(r[rd]^r[rb]);r[ra]=res;if(rc)cr0(res);return;} // eqv
			case 316:{int res=r[rd]^r[rb];r[ra]=res;if(rc)cr0(res);return;} // xor
			case 412:{int res=r[rd]|~r[rb];r[ra]=res;if(rc)cr0(res);return;} // orc
			case 444:{int res=r[rd]|r[rb];r[ra]=res;if(rc)cr0(res);return;} // or (mr)
			case 476:{int res=~(r[rd]&r[rb]);r[ra]=res;if(rc)cr0(res);return;} // nand
			case 26:{int res=Integer.numberOfLeadingZeros(r[rd]);r[ra]=res;if(rc)cr0(res);return;} // cntlzw
			case 922:{int res=(short)r[rd];r[ra]=res;if(rc)cr0(res);return;} // extsh
			case 954:{int res=(byte)r[rd];r[ra]=res;if(rc)cr0(res);return;} // extsb
			// shifts
			case 24:{int n=r[rb]&63;int res=n>31?0:r[rd]<<n;r[ra]=res;if(rc)cr0(res);return;} // slw
			case 536:{int n=r[rb]&63;int res=n>31?0:r[rd]>>>n;r[ra]=res;if(rc)cr0(res);return;} // srw
			case 792:{int n=r[rb]&63,s=r[rd];int res;boolean c;if(n>31){res=s>>31;c=s<0;}else{res=s>>n;c=s<0&&n>0&&(s&((1<<n)-1))!=0;}setCa(c);r[ra]=res;if(rc)cr0(res);return;} // sraw
			case 824:{int n=rb,s=r[rd];int res=s>>n;setCa(s<0&&n>0&&(s&((1<<n)-1))!=0);r[ra]=res;if(rc)cr0(res);return;} // srawi
			// condition register and special registers
			case 19:r[rd]=cr;return; // mfcr
			case 144:{int fxm=(op>>>12)&0xff,v=r[rd];for(int i=0;i<8;i++)if((fxm&(0x80>>>i))!=0){int m=0xf0000000>>>(4*i);cr=(cr&~m)|(v&m);}return;} // mtcrf
			case 512:{int crf=rd>>>2,sh=28-4*crf;cr=(cr&~(15<<sh))|(((xer>>>28)&15)<<sh);xer&=0x0fffffff;return;} // mcrxr
			case 339:r[rd]=mfspr(((op>>>16)&31)|(((op>>>11)&31)<<5));return; // mfspr
			case 467:mtspr(((op>>>16)&31)|(((op>>>11)&31)<<5),r[rd]);return; // mtspr
			case 371:{int tbr=((op>>>16)&31)|(((op>>>11)&31)<<5);long tb=timeBase();r[rd]=tbr==269?(int)(tb>>>32):(int)tb;return;} // mftb
			case 83:r[rd]=0x0000f032;return; // mfmsr (supervisor: what an application would see)
			case 146:case 210:case 242:case 306:case 370:case 566:case 470:return; // mtmsr, mtsr, mtsrin, tlbie, tlbia, tlbsync, dcbi
			case 595:case 659:r[rd]=0;return; // mfsr, mfsrin
			// loads and stores, indexed
			case 23:r[rd]=memory.readLong(eax(op));return; // lwzx
			case 55:{int ea=r[ra]+r[rb];r[rd]=memory.readLong(ea);r[ra]=ea;return;} // lwzux
			case 87:r[rd]=memory.readByte(eax(op));return; // lbzx
			case 119:{int ea=r[ra]+r[rb];r[rd]=memory.readByte(ea);r[ra]=ea;return;} // lbzux
			case 279:r[rd]=memory.readWord(eax(op));return; // lhzx
			case 311:{int ea=r[ra]+r[rb];r[rd]=memory.readWord(ea);r[ra]=ea;return;} // lhzux
			case 343:r[rd]=(short)memory.readWord(eax(op));return; // lhax
			case 375:{int ea=r[ra]+r[rb];r[rd]=(short)memory.readWord(ea);r[ra]=ea;return;} // lhaux
			case 151:store32(eax(op),r[rd]);return; // stwx
			case 183:{int ea=r[ra]+r[rb];store32(ea,r[rd]);r[ra]=ea;return;} // stwux
			case 215:store8(eax(op),r[rd]);return; // stbx
			case 247:{int ea=r[ra]+r[rb];store8(ea,r[rd]);r[ra]=ea;return;} // stbux
			case 407:store16(eax(op),r[rd]);return; // sthx
			case 439:{int ea=r[ra]+r[rb];store16(ea,r[rd]);r[ra]=ea;return;} // sthux
			case 534:r[rd]=Integer.reverseBytes(memory.readLong(eax(op)));return; // lwbrx
			case 790:{int v=memory.readWord(eax(op));r[rd]=((v&0xff)<<8)|(v>>>8);return;} // lhbrx
			case 662:store32(eax(op),Integer.reverseBytes(r[rd]));return; // stwbrx
			case 918:{int v=r[rd];store16(eax(op),((v&0xff)<<8)|((v>>>8)&0xff));return;} // sthbrx
			case 20:r[rd]=memory.readLong(eax(op));reservation=true;return; // lwarx
			case 150:{if(reservation){store32(eax(op),r[rd]);cr=(cr&0x0fffffff)|0x20000000|((xer>>>3)&0x10000000);}else cr=(cr&0x0fffffff)|((xer>>>3)&0x10000000);reservation=false;return;} // stwcx.
			case 533:loadString(rd,eax(op),xer&0x7f,ra,rb);return; // lswx
			case 597:loadString(rd,ra==0?0:r[ra],rb==0?32:rb,ra,-1);return; // lswi
			case 661:storeString(rd,eax(op),xer&0x7f);return; // stswx
			case 725:storeString(rd,ra==0?0:r[ra],rb==0?32:rb);return; // stswi
			case 535:f[rd]=singleToDouble(memory.readLong(eax(op)));return; // lfsx
			case 567:{int ea=r[ra]+r[rb];f[rd]=singleToDouble(memory.readLong(ea));r[ra]=ea;return;} // lfsux
			case 599:f[rd]=readDouble(eax(op));return; // lfdx
			case 631:{int ea=r[ra]+r[rb];f[rd]=readDouble(ea);r[ra]=ea;return;} // lfdux
			case 663:store32(eax(op),doubleToSingle(f[rd]));return; // stfsx
			case 695:{int ea=r[ra]+r[rb];store32(ea,doubleToSingle(f[rd]));r[ra]=ea;return;} // stfsux
			case 727:writeDouble(eax(op),f[rd]);return; // stfdx
			case 759:{int ea=r[ra]+r[rb];writeDouble(ea,f[rd]);r[ra]=ea;return;} // stfdux
			case 983:store32(eax(op),(int)f[rd]);return; // stfiwx
			// caches and ordering
			case 1014:{int ea=eax(op)&~31;for(int i=0;i<32;i+=4)store32(ea+i,0);return;} // dcbz
			case 54:case 86:case 246:case 278:case 598:case 854:case 982:return; // dcbst, dcbf, dcbtst, dcbt, sync, eieio, icbi
			case 310:case 438:throw illegal(at,op); // eciwx, ecowx
			default:throw illegal(at,op);
		}
	}

	private void loadString(int rd,int ea,int n,int ra,int rb) throws M68kCpuException {
		int reg=rd-1;
		for(int k=0;k<n;k++){
			if((k&3)==0){reg=(reg+1)&31;r[reg]=0;}
			r[reg]|=memory.readByte(ea+k)<<(24-8*(k&3));
		}
	}
	private void storeString(int rs,int ea,int n) throws M68kCpuException {
		int reg=rs-1;
		for(int k=0;k<n;k++){
			if((k&3)==0)reg=(reg+1)&31;
			store8(ea+k,r[reg]>>>(24-8*(k&3)));
		}
	}

	private int mq; // POWER MQ register (601)
	int mfspr(int spr){
		switch(spr){
			case 1:return xer;
			case 8:return lr;
			case 9:return ctr;
			case 0:return mq; // MQ (601)
			case 4:return (int)(System.nanoTime()-timeBaseOrigin)/1_000_000_000; // RTCU (601): seconds
			case 5:return (int)((System.nanoTime()-timeBaseOrigin)%1_000_000_000L)&~0x7f; // RTCL: nanoseconds
			case 268:return (int)timeBase();
			case 269:return (int)(timeBase()>>>32);
			case 287:return 0x00030001; // PVR: 603
			default:return 0;
		}
	}
	private void mtspr(int spr,int v){
		switch(spr){case 0:mq=v;break;case 1:xer=v;break;case 8:lr=v;break;case 9:ctr=v;break;default:break;}
	}
	private long timeBase(){long ns=System.nanoTime()-timeBaseOrigin;return ns/1000*(TIME_BASE_HZ/1_000_000);}
	/** mftb (compiled code): the time base's lower (268) or upper (269) word. */
	int mftb(int tbr){long tb=timeBase();return tbr==269?(int)(tb>>>32):(int)tb;}
	/** dcbz (compiled code): clears the 32-byte block holding {@code ea}. */
	void dcbz(int ea) throws M68kCpuException {ea&=~31;for(int i=0;i<32;i+=4)store32(ea+i,0);}

	// ------------------------------------------------------------------ floating point

	long readDouble(int ea) throws M68kCpuException {return ((long)memory.readLong(ea)<<32)|(memory.readLong(ea+4)&0xffffffffL);}
	void writeDouble(int ea,long bits) throws M68kCpuException {if(memory.protectedPageCount!=0)checkStore(ea,8);memory.writeLong(ea,(int)(bits>>>32));memory.writeLong(ea+4,(int)bits);}
	void store32(int ea,int v) throws M68kCpuException {if(memory.protectedPageCount!=0)checkStore(ea,4);memory.writeLong(ea,v);}
	void store16(int ea,int v) throws M68kCpuException {if(memory.protectedPageCount!=0)checkStore(ea,2);memory.writeWord(ea,v);}
	void store8(int ea,int v) throws M68kCpuException {if(memory.protectedPageCount!=0)checkStore(ea,1);memory.writeByte(ea,v);}
	/** A store into a page the program protected faults when it has an exception handler to take it (else the page is written). */
	private void checkStore(int ea,int n) throws M68kCpuException {if(exceptionHandler!=0&&memory.writeProtected(ea,n))throw new ProtectionFault(ea,nesting);}
	/** A store into a protected page: a readOnlyMemoryException for the program's handler (see {@link #deliverException}). */
	private static final class ProtectionFault extends M68kCpuException {
		private static final long serialVersionUID=1L;
		final int address,level;
		ProtectionFault(int address,int level){super(address,String.format("PowerPC store into the write-protected page at $%08X",address));this.address=address;this.level=level;}
	}
	/** Single-precision bits to double bits, exactly (a NaN keeps its payload, as the hardware does). */
	static long singleToDouble(int s){
		if((s&0x7f800000)==0x7f800000&&(s&0x007fffff)!=0)return ((long)(s&0x80000000)<<32)|0x7ff0000000000000L|((long)(s&0x007fffff)<<29);
		return Double.doubleToRawLongBits((double)Float.intBitsToFloat(s));
	}
	/** Double bits to single bits, rounded to nearest (a NaN keeps the top of its payload). */
	static int doubleToSingle(long d){
		if((d&0x7ff0000000000000L)==0x7ff0000000000000L&&(d&0x000fffffffffffffL)!=0)return (int)((d>>>32)&0x80000000)|0x7f800000|(int)((d>>>29)&0x007fffff)|0x00400000;
		return Float.floatToRawIntBits((float)Double.longBitsToDouble(d));
	}
	private double fd(int n){return Double.longBitsToDouble(f[n]);}
	private void setF(int n,double v,boolean rc){f[n]=Double.doubleToRawLongBits(v);setFprf(v);if(rc)cr1();}
	private void setS(int n,double v,boolean rc){setF(n,(double)(float)v,rc);}
	private void cr1(){cr=(cr&0xf0ffffff)|((fpscr>>>4)&0x0f000000);}
	/** FPSCR[FPRF]: the result class and sign, which compiled code rarely reads but mffs shows. */
	private void setFprf(double v){
		int c;
		if(Double.isNaN(v))c=0x11;else if(Double.isInfinite(v))c=v<0?0x09:0x05;
		else if(v==0)c=(Double.doubleToRawLongBits(v)<0)?0x12:0x02;
		else if(Math.abs(v)<Double.MIN_NORMAL)c=v<0?0x18:0x14;else c=v<0?0x08:0x04;
		fpscr=(fpscr&~0x0001f000)|(c<<12);
	}

	private void op59(int op,int at) throws M68kException {
		int d=(op>>>21)&31,a=(op>>>16)&31,b=(op>>>11)&31,c=(op>>>6)&31;boolean rc=(op&1)!=0;
		switch((op>>>1)&31){
			case 18:setS(d,fd(a)/fd(b),rc);return; // fdivs
			case 20:setS(d,fd(a)-fd(b),rc);return; // fsubs
			case 21:setS(d,fd(a)+fd(b),rc);return; // fadds
			case 22:setS(d,Math.sqrt(fd(b)),rc);return; // fsqrts
			case 24:setS(d,1.0/fd(b),rc);return; // fres
			case 25:setS(d,fd(a)*fd(c),rc);return; // fmuls
			case 28:setS(d,Math.fma(fd(a),fd(c),-fd(b)),rc);return; // fmsubs
			case 29:setS(d,Math.fma(fd(a),fd(c),fd(b)),rc);return; // fmadds
			case 30:setS(d,-Math.fma(fd(a),fd(c),-fd(b)),rc);return; // fnmsubs
			case 31:setS(d,-Math.fma(fd(a),fd(c),fd(b)),rc);return; // fnmadds
			default:throw illegal(at,op);
		}
	}

	private void op63(int op,int at) throws M68kException {
		int d=(op>>>21)&31,a=(op>>>16)&31,b=(op>>>11)&31,c=(op>>>6)&31;boolean rc=(op&1)!=0;
		switch((op>>>1)&31){ // A-form
			case 18:setF(d,fd(a)/fd(b),rc);return; // fdiv
			case 20:setF(d,fd(a)-fd(b),rc);return; // fsub
			case 21:setF(d,fd(a)+fd(b),rc);return; // fadd
			case 22:setF(d,Math.sqrt(fd(b)),rc);return; // fsqrt
			case 23:{double v=fd(a);f[d]=v>=0.0?f[c]:f[b];if(rc)cr1();return;} // fsel (NaN selects frB)
			case 25:setF(d,fd(a)*fd(c),rc);return; // fmul
			case 26:setF(d,1.0/Math.sqrt(fd(b)),rc);return; // frsqrte
			case 28:setF(d,Math.fma(fd(a),fd(c),-fd(b)),rc);return; // fmsub
			case 29:setF(d,Math.fma(fd(a),fd(c),fd(b)),rc);return; // fmadd
			case 30:setF(d,-Math.fma(fd(a),fd(c),-fd(b)),rc);return; // fnmsub
			case 31:setF(d,-Math.fma(fd(a),fd(c),fd(b)),rc);return; // fnmadd
			default:break;
		}
		switch((op>>>1)&0x3ff){ // X-form
			case 0:case 32:{double x=fd(a),y=fd(b);int bits=Double.isNaN(x)||Double.isNaN(y)?1:x<y?8:x>y?4:2;int sh=28-4*(d>>>2);cr=(cr&~(15<<sh))|(bits<<sh);fpscr=(fpscr&~0x0000f000)|(bits<<12);return;} // fcmpu, fcmpo
			case 12:{f[d]=singleToDouble(doubleToSingle(f[b]));setFprf(fd(d));if(rc)cr1();return;} // frsp
			case 14:case 15:{f[d]=0xfff8000000000000L|(toInt(fd(b),((op>>>1)&0x3ff)==15?1:fpscr&3)&0xffffffffL);if(rc)cr1();return;} // fctiw, fctiwz
			case 38:{int bit=d;fpscr|=1<<(31-bit);if(rc)cr1();return;} // mtfsb1
			case 70:{int bit=d;fpscr&=~(1<<(31-bit));if(rc)cr1();return;} // mtfsb0
			case 40:f[d]=f[b]^0x8000000000000000L;if(rc)cr1();return; // fneg
			case 72:f[d]=f[b];if(rc)cr1();return; // fmr
			case 136:f[d]=f[b]|0x8000000000000000L;if(rc)cr1();return; // fnabs
			case 264:f[d]=f[b]&0x7fffffffffffffffL;if(rc)cr1();return; // fabs
			case 64:{int sf=28-4*(a>>>2),df=28-4*(d>>>2);int v=(fpscr>>>sf)&15;cr=(cr&~(15<<df))|(v<<df);if(sf!=0)fpscr&=~((sf==28?0x9:15)<<sf);return;} // mcrfs
			case 134:{int field=d>>>2,imm=(op>>>12)&15,sh=28-4*field;fpscr=(fpscr&~(15<<sh))|(imm<<sh);if(rc)cr1();return;} // mtfsfi
			case 583:f[d]=0xfff8000000000000L|(fpscr&0xffffffffL);if(rc)cr1();return; // mffs
			case 711:{int fm=(op>>>17)&0xff,v=(int)f[b];for(int i=0;i<8;i++)if((fm&(0x80>>>i))!=0){int m=0xf0000000>>>(4*i);fpscr=(fpscr&~m)|(v&m);}if(rc)cr1();return;} // mtfsf
			default:throw illegal(at,op);
		}
	}
	/** fctiw: rounds by {@code mode} (0 nearest, 1 toward zero, 2 up, 3 down) and saturates; NaN gives 0x80000000. */
	static int toInt(double v,int mode){
		if(Double.isNaN(v))return Integer.MIN_VALUE;
		double t=mode==0?Math.rint(v):mode==1?(v<0?Math.ceil(v):Math.floor(v)):mode==2?Math.ceil(v):Math.floor(v);
		if(t>=2147483647.0)return Integer.MAX_VALUE;if(t<=-2147483648.0)return Integer.MIN_VALUE;
		return (int)t;
	}

	// ------------------------------------------------------------------ diagnostics

	/** Registers, the recent instruction addresses and the code around {@code at}, for crash reports. */
	String dumpState(int at){
		StringBuilder sb=new StringBuilder();
		sb.append(String.format("PowerPC state at %08X: lr=%08X ctr=%08X cr=%08X xer=%08X%n",at,lr,ctr,cr,xer));
		for(int i=0;i<32;i++){sb.append(String.format("r%-2d=%08X%s",i,r[i],(i&7)==7?"\n":" "));}
		sb.append("recent PCs:");
		for(int i=Math.min(PC_HISTORY,32);i>0;i--){sb.append(String.format(" %08X",pcHistory[(pcHistoryPos-i)&(PC_HISTORY-1)]));}
		sb.append('\n');
		try{sb.append("code:");for(int a=at-16;a<at+16;a+=4)sb.append(String.format(a==at?" [%08X]":" %08X",memory.readLong(a)));}catch(Exception e){}
		try{int op=memory.readLong(at);if((op&0xfc000000)==0x04000000&&natives instanceof PpcRuntime)sb.append(" (native ").append(((PpcRuntime)natives).nativeName(op&0x3ffffff)).append(')');}catch(Exception e){}
		try{sb.append("\nback chain:");int sp=r[1];for(int i=0;i<12&&sp!=0&&(sp&3)==0;i++){sb.append(String.format(" %08X",memory.readLong(sp+8)));sp=memory.readLong(sp);}}catch(Exception e){}
		return sb.toString();
	}
	/** For a bad jump: the import the cross-TOC glue came through (lwz r12,d(r2); stw r2,20(r1); ... bctr - the caller's
	 *  TOC is still at 20(r1)), else {@code otherwise}. */
	private String glueCallee(String otherwise){
		try{int bctr=lastPc();if(bctr==pc-4)bctr=pcHistory[(pcHistoryPos-2)&(PC_HISTORY-1)]; // an illegal instruction was recorded already
			for(int a=bctr-4;a>=bctr-24;a-=4){int op=memory.readLong(a);
				if((op&0xffff0000)==0x81820000){int slot=memory.readLong(r[1]+20)+(short)op;
					String name=natives instanceof PpcRuntime?((PpcRuntime)natives).importAt(slot):null;
					if(name!=null){int tv=memory.readLong(slot);String code=tv==0?"":String.format(" -> code %08X",memory.readLong(tv));
						return (otherwise.isEmpty()?" (":"")+"the import "+name+" called through its glue at "+String.format("%08X",a)+String.format(", TOC slot %08X = TVector %08X%s",slot,tv,code)+(otherwise.isEmpty()?")":"");}
					break;}}
		}catch(Exception e){}
		return otherwise;
	}
	/** -Dhz68k.watch=<hex> given at start-up (a constant, so the check costs nothing otherwise): the PowerPC instruction that
	 *  changed the watched long (M68kCpu.WATCH, which commands.txt `watch` can move), with some registers. */
	private static final boolean WATCH=!System.getProperty("hz68k.watch","0").equals("0");
	private int watchValue,watchAddress;
	/** Debug aid, -Dhzppc.watchat=<hexpc>:<reg>[:<hexoffset>] (with -Dhz68k.watch=<any>): each time the PowerPC code reaches
	 *  that address, the watched long moves to register+offset - follows objects whose address changes from run to run. */
	private static final int WATCH_AT_PC,WATCH_AT_REG,WATCH_AT_OFFSET;
	static{String[] w=System.getProperty("hzppc.watchat","").split(":");int pc=-1,reg=0,off=0;
		if(w.length>=2){pc=(int)Long.parseLong(w[0],16);reg=Integer.parseInt(w[1]);if(w.length>=3)off=(int)Long.parseLong(w[2],16);}
		WATCH_AT_PC=pc;WATCH_AT_REG=reg;WATCH_AT_OFFSET=off;}
	private void checkWatch(int at){
		try{int w=M68kCpu.WATCH;int v=memory.readLong(w);if(w!=watchAddress){watchAddress=w;watchValue=v;return;}
			if(v!=watchValue){System.err.printf("[watch ppc] $%X: $%08X -> $%08X at %08X lr=%08X r1=%08X r3=%08X r4=%08X r5=%08X r31=%08X callers %s%n",w,watchValue,v,at,lr,r[1],r[3],r[4],r[5],r[31],backChain());watchValue=v;}}
		catch(Exception e){}
	}
	/** The most recent instruction address. */
	int lastPc(){return pcHistory[(pcHistoryPos-1)&(PC_HISTORY-1)];}
}

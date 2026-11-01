/**
 * Dependency-free Motorola 68000/68020 interpreter.
 *
 * The first version only ran HyperCard externals; this one also runs complete
 * applications: the whole 68000 instruction set, the 68020 addressing modes and
 * the common 68020 instructions (EXTB, long multiply/divide, LINK.L, RTD, bit
 * fields, scaled index, 32-bit branches), re-entrant subroutine calls from Java
 * (callbacks such as dialog filters and completion routines), a "ROM" region
 * whose addresses stand for Toolbox traps (GetTrapAddress/SetTrapAddress
 * patching), host gates (tiny stubs whose execution calls back into Java) and a
 * periodic scheduler hook used for Time Manager tasks and asynchronous I/O.
 */
final class M68kCpu {
	static final int FLAG_C = 1, FLAG_V = 2, FLAG_Z = 4, FLAG_N = 8, FLAG_X = 16;
	static final int RETURN_SENTINEL = 0x00fffff0;
	static final int CALLBACK_SENTINEL = 0x00ffffe0;
	/** Trap-free instructions an external may run before it is taken for an infinite loop: a HyperCard 2.x XCMD did real
	 *  work in tight loops (PlayMOD pre-filters a whole MOD, ~30 M instructions, before its first trap), so the old 2 M
	 *  cut such calls short; -Dhz68k.xcmdlimit=N sets it. */
	static final long DEFAULT_INSTRUCTION_LIMIT = Long.getLong("hz68k.xcmdlimit",100_000_000L);
	/** Instructions between two scheduler polls. */
	private static final int POLL_INTERVAL = 4096;

	/** Called whenever execution reaches a trap ROM address or a host gate. */
	interface RomHandler {
		/** A JSR/JMP reached the ROM address of an A-line trap word. */
		void romTrap(M68kCpu cpu,int trapWord) throws M68kException;
		/** Execution reached host gate number {@code gate}; return normally to RTS. */
		void gate(M68kCpu cpu,int gate) throws M68kException;
		/** An A-line trap word executed in the program's code is handled directly (not through its ROM address). */
		default void inlineTrap(M68kCpu cpu,int trapWord){}
	}

	/** Polled periodically from the execution loop (timers, async completions, abort). */
	interface Scheduler {
		void poll(M68kCpu cpu) throws M68kException;
	}
	/** Interrupt delivery when the program lowers its interrupt mask (MOVE/ANDI/EORI to SR, RTE): a pending interrupt is
	 *  taken at the next instruction, as the processor takes it, not only at the next scheduler poll. MacIo68k: a sequencer
	 *  that waits for its SCC transmit interrupt with the mask at 4 and opens it for a few instructions only never saw it. */
	interface InterruptSource { void maskLowered(M68kCpu cpu) throws M68kException; }
	InterruptSource interruptSource;
	private boolean maskLowered;
	private void srWritten(int old){if(interruptSource!=null&&(sr&0x700)<(old&0x700))maskLowered=true;}

	final int[] d = new int[8];
	final int[] a = new int[8];
	final M68kMemory memory;
	private final long timeBaseOrigin=System.nanoTime();
	/** The MC68881/68882 coprocessor (F-line instructions); null when -Dhz68k.fpu=false. */
	final M68kFpu fpu;
	int pc;
	int sr;
	/** Number of executed instructions, for statistics and the scheduler. */
	long instructions;
	/** Number of traps and host gates taken; the scheduler compares it between polls to spot a program that only spins. */
	long traps;
	/** Virtual work the native Toolbox charges on top of the executed instructions, in 68040 instruction equivalents (a
	 *  trap's dispatch, the bytes a CopyBits moved): the speed throttle (Toolbox68k.throttle) paces {@code instructions+charged}. */
	long charged;
	void charge(long instructionEquivalents){charged+=instructionEquivalents;}
	/** The XCmdBlock the callback sentinel reads its request from: HyperCard hands an external a different block for every call and for every external-window event, so it is switched for the duration of a nested call. */
	private int xcmdBlock;
	private final M68kTrapHandler trapHandler;
	private final M68kCallbackHandler callbackHandler;
	private RomHandler romHandler;
	private Scheduler scheduler;
	private long instructionLimit = DEFAULT_INSTRUCTION_LIMIT;
	/** Instructions since the program last yielded (WaitNextEvent, SystemTask, Delay): the runaway-loop limit counts these,
	 *  so an external that animates frame by frame through WaitNextEvent is not mistaken for an infinite loop. */
	private long sinceYield;
	private volatile boolean abortRequested;
	/** True for an XCMD/XFCN VM: cmd+. in the host (see {@link #hostAbort}) aborts it like a hosted program's kill. */
	boolean external;
	/** Asked at every poll of an external's VM; true means the user aborted the script (TTalk.stop). */
	static volatile java.util.function.BooleanSupplier hostAbort;
	private int nesting;
	/** Patched trap addresses (SetTrapAddress); 0 means "not patched". */
	private int[] trapPatches;
	private final int romBase, trapTable, gateBase;

	// Effective address scratch (avoids allocating per operand).
	private int eaMode, eaReg, eaAddress, eaImmediate;
	/** Ring buffer of the most recent instruction addresses, for crash dumps. */
	private static final int PC_HISTORY=Integer.getInteger("hz68k.pchistory",512);
	private static final boolean PC_ESCAPE=Boolean.getBoolean("hz68k.pcescape");private boolean escapeReported;
	private final int[] pcHistory=new int[PC_HISTORY];
	private int pcHistoryPos;

	M68kCpu(M68kMemory memory, int xcmdBlock) {
		this(memory, xcmdBlock, null, null);
	}

	M68kCpu(M68kMemory memory, int xcmdBlock, M68kTrapHandler trapHandler) {
		this(memory,xcmdBlock,trapHandler,null);
	}

	M68kCpu(M68kMemory memory,int xcmdBlock,M68kTrapHandler trapHandler,
		M68kCallbackHandler callbackHandler) {
		this.memory = memory;
		this.xcmdBlock = xcmdBlock;
		this.trapHandler = trapHandler;
		this.callbackHandler = callbackHandler;
		romBase=memory.romBase();trapTable=romBase+0x10000;gateBase=romBase+0x20000;
		fpu=M68kFpu.ENABLED?new M68kFpu(this):null;
	}

	int romBase(){return romBase;}
	/** ROM address that stands for the A-line trap {@code trapWord}; JSR to it executes the trap. */
	int trapRomAddress(int trapWord){return trapTable+((trapWord&0x0fff)<<2);}
	/** True when {@code pc} is a trap's ROM entry, i.e. the handler was reached by JSR/JMP to its GetTrapAddress address. */
	boolean isTrapRomAddress(int pc){return pc>=trapTable&&pc<trapTable+0x4000;}
	private int romReturnAddress;
	// The trap word a patched OS trap was dispatched with, by trap number. A patch chains to the ROM entry from
	// GetTrapAddress, whose address only carries the number, and the File Manager needs bits 9-10 (HFS, async) of the
	// word the program used: QuickTime patches _GetFileInfo and _HFSDispatch, and HyperCard's _HGetFileInfo reached
	// the handler as _GetFileInfo, looking for Home in the volume root instead of the application folder.
	private final int[] pendingOsTrapWords=new int[0x100];
	/** The return address popped for the ROM trap entry being handled (where the caller's JSR continues). */
	int romReturnAddress(){return romReturnAddress;}
	/** Address of host gate {@code gate}; JSR to it calls {@link RomHandler#gate}. */
	int gateAddress(int gate){return gateBase+gate*4;}
	int trapWordForRomAddress(int address){
		if(address<trapTable||address>=trapTable+0x4000)return -1;
		return 0xa000|((address-trapTable)>>>2);
	}
	void setRomHandler(RomHandler handler){romHandler=handler;}
	RomHandler romHandler(){return romHandler;}
	void setScheduler(Scheduler scheduler){this.scheduler=scheduler;}
	Scheduler scheduler(){return scheduler;}
	void setInstructionLimit(long limit){instructionLimit=limit;}
	/** The program reached a cooperative wait; the runaway-loop budget starts over. */
	void yielded(){sinceYield=0;yieldNanos=System.nanoTime();}
	/** When the external last yielded, and the trap count when the instruction limit last let it go on. */
	private long yieldNanos,limitTraps;
	/** When a limit check last found traps made since the one before. */
	private long trapNanos;
	/** How long an external may compute without a single trap before it is taken for a runaway loop: a long computation makes
	 *  none either (UxUniItem de-duplicating 2000 lines, UxGetFoundLines through megabytes of log took ~5 s and were cut off
	 *  after 100 M instructions), and on a Mac it only takes its time. cmd-. (hostAbort) stops it meanwhile. */
	private static final long TRAP_FREE_LIMIT_NANOS=Long.getLong("hz68k.xcmdtrapfree",30)*1_000_000_000L;
	/** An external that keeps calling traps is working, not looping (PlayMOD decodes a whole MOD between two yields); it is
	 *  cut off only after this long without a yield. cmd-. (hostAbort) still stops it at once. */
	private static final long XCMD_WALL_LIMIT_NANOS=Long.getLong("hz68k.xcmdseconds",120)*1_000_000_000L;
	/** True when the last 64 instructions form a small loop (at most 16 distinct PCs) and one of its instructions carries
	 *  an extension word in {@code first..last}: the scheduler's detector for loops polling a low-memory global such as Ticks
	 *  (MacinTalk 1 compares the low byte, $16D). */
	boolean spinningOn(int operand){return spinningOn(operand,operand);}
	boolean spinningOn(int first,int last){
		int[] pcs=new int[16];int n=0;
		for(int i=1;i<=64;i++){
			int p=pcHistory[(pcHistoryPos-i)&(PC_HISTORY-1)];boolean seen=false;
			for(int j=0;j<n;j++)if(pcs[j]==p){seen=true;break;}
			if(seen)continue;if(n==pcs.length)return false;pcs[n++]=p;}
		try{for(int j=0;j<n;j++)for(int k=2;k<=8;k+=2){int w=memory.readWord(pcs[j]+k);if(w>=first&&w<=last)return true;}}
		catch(M68kCpuException e){}
		return false;
	}
	void requestAbort(){abortRequested=true;}
	boolean abortRequested(){return abortRequested||(external&&hostAbort!=null&&hostAbort.getAsBoolean());}
	int nestingDepth(){return nesting;}

	// The trap dispatch tables live in low memory as on a real Mac - OS traps at $400 (256 longs), Toolbox traps at $E00
	// (1024 longs) - because system extensions read and write them directly (QuickTime calls the Image Compression
	// Manager through the table entry of _CodecDispatch, $E00+4*$2A3 = $188C). A trap is "patched" when its entry differs
	// from the default the table was initialized with (a ROM entry point, see initializeTrapTable).
	static final int OS_TRAP_TABLE=0x400,TOOLBOX_TRAP_TABLE=0xe00;
	private int[] trapDefaults; // by patchIndex
	private static int patchIndex(int trapWord){return (trapWord&0x0800)!=0?0x800|(trapWord&0x3ff):trapWord&0xff;}
	static int trapTableEntry(int trapWord){return (trapWord&0x0800)!=0?TOOLBOX_TRAP_TABLE+((trapWord&0x3ff)<<2):OS_TRAP_TABLE+((trapWord&0xff)<<2);}
	/** Fills both tables with default addresses; {@code defaultAddress} maps a trap word to its ROM entry (or the _Unimplemented entry). */
	void initializeTrapTable(java.util.function.IntUnaryOperator defaultAddress) throws M68kCpuException {
		trapDefaults=new int[0xc00];
		for(int t=0;t<0x100;t++){int a=defaultAddress.applyAsInt(0xa000|t);trapDefaults[t]=a;memory.writeLong(OS_TRAP_TABLE+(t<<2),a);}
		for(int t=0;t<0x400;t++){int a=defaultAddress.applyAsInt(0xa800|t);trapDefaults[0x800|t]=a;memory.writeLong(TOOLBOX_TRAP_TABLE+(t<<2),a);}
	}
	/** The address a program installed for the trap (SetTrapAddress or a direct table write), 0 when the trap is unpatched. */
	int patchedTrap(int trapWord){
		if(trapDefaults==null)return trapPatches==null?0:trapPatches[patchIndex(trapWord)];
		try{int a=memory.readLong(trapTableEntry(trapWord));return a==trapDefaults[patchIndex(trapWord)]?0:a;}catch(M68kCpuException e){return 0;}
	}
	/** Installs {@code address} for the trap (0 restores the default). */
	void patchTrap(int trapWord,int address){
		if(trapDefaults==null){if(trapPatches==null)trapPatches=new int[0x2000];trapPatches[(trapWord&0x0800)!=0?trapWord&0x0fff:0x1000|(trapWord&0xff)]=address;return;}
		try{memory.writeLong(trapTableEntry(trapWord),address==0?trapDefaults[patchIndex(trapWord)]:address);}catch(M68kCpuException ignored){}
	}
	/** The ROM entry of a trap where code the host runs ends instead of running the trap: a trap patch called on its own (see
	 *  {@link LaunchHookBridge68k}) ends by jumping to the original routine it chains to. -1 = none. */
	int stopAtRomEntry=-1;
	/** The default (ROM) address of a trap. */
	int defaultTrapAddress(int trapWord){return trapDefaults==null?trapRomAddress(trapWord):trapDefaults[patchIndex(trapWord)];}

	void run(int entryPoint) throws M68kException {
		pc = entryPoint;
		if(nesting==0)sentinelSlots[0]=a[7]; // the program's own frames lie below its initial stack pointer: no host call returns above it
		execute();
	}

	int xcmdBlock(){return xcmdBlock;}

	/**
	 * Calls an external's entry point with {@code block} as its XCmdBlock, the way HyperCard calls an XCMD (one Pascal
	 * argument): the block also becomes the one the callback sentinel serves until the call returns. Nested calls are
	 * fine - HyperCard sends an external window its xOpenEvt from inside NewXWindow, while the creating call is still on
	 * the stack.
	 */
	void callExternal(int entryPoint,int block) throws M68kException {
		int savedBlock=xcmdBlock;xcmdBlock=block;
		int savedPc=pc,savedSp=a[7];
		a[7]-=4;memory.writeLong(a[7],block);
		a[7]-=4;memory.writeLong(a[7],RETURN_SENTINEL);
		pc=entryPoint;int level=enter();
		try{execute();checkUnwound(level);}
		catch(M68kLongJumpException e){if(passesLevel(level,e))throw e;}
		finally{nesting--;pc=savedPc;a[7]=savedSp;xcmdBlock=savedBlock;}
	}

	/**
	 * Calls a 68K subroutine from Java and returns when it executes RTS to the
	 * sentinel. Registers other than those the callee changes are preserved by
	 * the caller (Java) if needed; the stack pointer is restored.
	 */
	void call(int address) throws M68kException {
		int savedPc=pc,savedSp=a[7];
		a[7]-=4;memory.writeLong(a[7],RETURN_SENTINEL);
		pc=address;int level=enter();
		try{execute();checkUnwound(level);}
		catch(M68kLongJumpException e){if(passesLevel(level,e))throw e;}
		finally{nesting--;pc=savedPc;a[7]=savedSp;}
	}

	/**
	 * What of the CPU belongs to one Java stack: the registers, the FPU and the host-call nesting with its sentinel slots,
	 * which describe the Java frames of the thread running the code. The Thread Manager's coroutine threads
	 * (ThreadManager68k) each keep one while another runs.
	 */
	static final class Context {
		final int[] d=new int[8],a=new int[8];int pc,sr,nesting,xcmdBlock,romReturnAddress,lastReturnSp;int[] sentinelSlots;
		double[] fp;int fpcr,fpsr,fpiar;
	}
	Context saveContext(){
		Context c=new Context();System.arraycopy(d,0,c.d,0,8);System.arraycopy(a,0,c.a,0,8);c.pc=pc;c.sr=sr;
		c.nesting=nesting;c.sentinelSlots=java.util.Arrays.copyOf(sentinelSlots,nesting+1);c.xcmdBlock=xcmdBlock;c.romReturnAddress=romReturnAddress;c.lastReturnSp=lastReturnSp;
		if(fpu!=null){c.fp=fpu.fp.clone();c.fpcr=fpu.fpcr;c.fpsr=fpu.fpsr;c.fpiar=fpu.fpiar;}
		return c;
	}
	void restoreContext(Context c){
		System.arraycopy(c.d,0,d,0,8);System.arraycopy(c.a,0,a,0,8);pc=c.pc;sr=c.sr;
		nesting=c.nesting;if(sentinelSlots.length<c.sentinelSlots.length)sentinelSlots=new int[c.sentinelSlots.length*2];System.arraycopy(c.sentinelSlots,0,sentinelSlots,0,c.sentinelSlots.length);
		xcmdBlock=c.xcmdBlock;romReturnAddress=c.romReturnAddress;lastReturnSp=c.lastReturnSp;
		if(fpu!=null&&c.fp!=null){System.arraycopy(c.fp,0,fpu.fp,0,8);fpu.fpcr=c.fpcr;fpu.fpsr=c.fpsr;fpu.fpiar=c.fpiar;}
	}

	/** Where each nested call's sentinel return address sits on the 68K stack, by nesting level. */
	private int[] sentinelSlots=new int[64];
	private int enter(){int level=nesting++;if(level>=sentinelSlots.length)sentinelSlots=java.util.Arrays.copyOf(sentinelSlots,level*2);sentinelSlots[level]=a[7];return level;}
	/** A return whose stack pointer lies above the enclosing call's sentinel slot did not come from this call's routine: the
	 *  code longjmp'd out of it into an outer frame and ran that frame's epilogue (see M68kLongJumpException). */
	private void checkUnwound(int level) throws M68kLongJumpException {if(level>0&&a[7]>sentinelSlots[level-1]){if(Mac68kApi.TRACE)System.err.println("[68K] longjmp past host call level "+level+" SP=$"+Integer.toHexString(a[7])+"\n"+dumpState(pc));throw new M68kLongJumpException(a[7]);}}
	/** Whether the unwinding still reaches past this level (true) or ends here as this call's own return (false). */
	private boolean passesLevel(int level,M68kLongJumpException e){return level>0&&e.sp>sentinelSlots[level-1];}

	/**
	 * Runs code at {@code address} until it jumps to RETURN_SENTINEL itself, pushing nothing: for glue that must look
	 * exactly like a compiled trap call (result slot, arguments, then the trap word), where an extra return address between
	 * the result and the trap would be taken for the caller's frame (QuickTime's dispatcher stores results through it).
	 */
	void runUntilSentinel(int address) throws M68kException {runUntilSentinel(address,0);}
	/**
	 * As above for a trap word run from host code with its {@code argumentBytes} of arguments already pushed: they belong to
	 * this call's frame. The trap pops them and may call guest code (a WDEF from NewCDialog) above the stack pointer the call
	 * started with; that code's return must not count as a longjmp past this level (Kaleidoscope's WDEF in its control
	 * panel's window: "the control panel window could not be created").
	 */
	void runUntilSentinel(int address,int argumentBytes) throws M68kException {
		int savedPc=pc,savedSp=a[7];
		pc=address;int level=enter();sentinelSlots[level]=a[7]+argumentBytes;
		try{execute();checkUnwound(level);}
		catch(M68kLongJumpException e){if(passesLevel(level,e))throw e;}
		finally{nesting--;pc=savedPc;a[7]=savedSp;}
	}
	/** Calls a subroutine and leaves the stack pointer where the callee left it (for routines that pop a caller-dependent number of arguments, as CallComponentFunction's targets do). */
	void callLeavingStack(int address) throws M68kException {
		int savedPc=pc;
		a[7]-=4;memory.writeLong(a[7],RETURN_SENTINEL);
		pc=address;int level=enter();
		try{execute();checkUnwound(level);}
		catch(M68kLongJumpException e){if(passesLevel(level,e))throw e;}
		finally{nesting--;pc=savedPc;}
	}

	/** Calls a Pascal-style routine: arguments are already pushed by the caller. */
	void callPascal(int address,int argumentBytes) throws M68kException {
		int savedPc=pc,savedSp=a[7]+argumentBytes;
		a[7]-=4;memory.writeLong(a[7],RETURN_SENTINEL);
		pc=address;int level=enter();
		try{execute();checkUnwound(level);}
		catch(M68kLongJumpException e){if(passesLevel(level,e))throw e;}
		finally{nesting--;pc=savedPc;lastReturnSp=a[7];a[7]=savedSp;}
	}
	/** The stack pointer a {@link #callPascal} routine returned with, before it was set back past the arguments: equal to the
	 *  arguments' address when the routine left them on the stack (a C function called as a Pascal one). */
	int lastReturnSp;

	/** Debug aid: -Dhz68k.watch=hexaddress logs every change of the long at that address with the PC that made it. */
	/** Debug aid: -Dhz68k.pctrace=hexstart-hexend prints every instruction executed inside that PC range. */
	/** Debug aid: -Dhz68k.breakpc=hexaddress prints the state (with the recent PCs) the first time execution reaches it. */
	private static int BREAK_PC=(int)Long.parseLong(System.getProperty("hz68k.breakpc","-1").replace("-1","ffffffff"),16);
	private static volatile int[] PC_TRACE_START,PC_TRACE_END; // -Dhz68k.pctrace=start-end[,start-end...]
	static{setPcTrace(System.getProperty("hz68k.pctrace",""));}
	/** Replaces the traced PC ranges ("start-end[,start-end...]" in hex; empty = none) - also reachable at run time through the snapshot command file. */
	static void setPcTrace(String spec){String[] ranges=spec.split(",");int n=0;for(String r:ranges)if(r.indexOf('-')>0)n++;int[] start=new int[n],end=new int[n];int i=0;
		for(String r:ranges){int dash=r.indexOf('-');if(dash<=0)continue;start[i]=(int)Long.parseLong(r.substring(0,dash),16);end[i++]=(int)Long.parseLong(r.substring(dash+1),16);}
		PC_TRACE_START=start;PC_TRACE_END=end;}
	private String argsAt(int at){StringBuilder b=new StringBuilder();try{for(int i=0;i<12;i++)b.append(String.format("%08X ",memory.readLong(at+i*4)));}catch(Exception e){}return b.toString().trim();}
	private boolean pcTraced(int pc){for(int i=0;i<PC_TRACE_START.length;i++)if(pc>=PC_TRACE_START[i]&&pc<PC_TRACE_END[i])return true;return false;}
	static volatile int WATCH=(int)Long.parseLong(System.getProperty("hz68k.watch","0"),16);private int watchValue; // also settable at run time (commands.txt `watch <hexaddr>`)

	private void checkWatch(int watchPc) throws M68kCpuException {int now=memory.readLong(WATCH);if(now!=watchValue){System.err.printf("[watch] $%06X: $%08X -> $%08X (pc=$%06X)%n",WATCH,watchValue,now,watchPc);watchValue=now;if(WATCH_REGISTERS)System.err.println(dumpState(watchPc));}
		if(WATCH_WRITES){long serial=memory.chunkSerial(WATCH,4);if(serial!=watchSerial){if(watchSerial!=0)System.err.printf("[watch] $%06X written (same value $%08X, serial %d) pc=$%06X%n",WATCH,now,serial,watchPc);watchSerial=serial;}}}
	private long watchSerial;
	private static final boolean WATCH_WRITES=Boolean.getBoolean("hz68k.watchwrites"); // -Dhz68k.watchwrites=true: report writes to the watched long even when the value stays
	private static final boolean WATCH_REGISTERS=Boolean.getBoolean("hz68k.watchregs"); // -Dhz68k.watchregs=true: full register dump at every watched change

	private void execute() throws M68kException {
		sinceYield=0;yieldNanos=System.nanoTime();trapNanos=yieldNanos;limitTraps=traps;
		while(!executeSlice()){}
	}
	/**
	 * Runs until the sentinel (true) or the next scheduler poll (false). Returning at every poll gives HotSpot a fresh
	 * entry into compiled code: a program's main loop never returns, and its on-stack-replaced frame could stay bound to the
	 * interpreted {@link #step} after a recompilation (a PowerPC program ran 16x slower for the rest of its life).
	 */
	private boolean executeSlice() throws M68kException {
		long limit=instructionLimit;
		for(;;){
			if(pc == RETURN_SENTINEL) return true;
			if(pc == CALLBACK_SENTINEL) {
				int request = memory.readWord(xcmdBlock + 76);
				if(callbackHandler!=null && callbackHandler.handle(request,xcmdBlock)) {
					pc=memory.readLong(a[7]);a[7]+=4;
					continue;
				}
				if(callbackHandler==null)System.err.println("[68K] jump to the callback sentinel outside an XCMD\n"+dumpState(pcHistory[(pcHistoryPos-1)&(PC_HISTORY-1)])); // a program that jumped there by itself: show how it got there
				throw new M68kMacApiException(pc, request,
					"HyperCard callback request " + request);
			}
			int watchPc=pc;
			if(PC_ESCAPE&&!escapeReported&&(pc<0||pc>=memory.size())&&pc!=CALLBACK_SENTINEL){escapeReported=true;System.err.println("[68K] PC left memory\n"+dumpState(pcHistory[(pcHistoryPos-1)&(PC_HISTORY-1)]));} // -Dhz68k.pcescape=true: the state at the first instruction fetched outside memory (a corrupted return address), before the run through it
			if(pc==BREAK_PC){BREAK_PC=-1;System.err.println("[68K] breakpc reached\n"+dumpState(pc));}
			if(PC_TRACE_END.length!=0&&pcTraced(pc))System.err.printf("[pc] %06X op=%04X %04X %04X %04X D0=%08X D1=%08X D2=%08X D3=%08X D4=%08X D5=%08X D6=%08X D7=%08X A0=%08X A1=%08X A2=%08X A3=%08X A4=%08X A5=%08X A6=%08X SP=%08X (SP)=%08X 4(A6)=%08X (A4)=%08X %08X (A0)=%s prev=%06X args=%s stack=%s%n",pc,memory.readWord(pc),memory.readWord(pc+2),memory.readWord(pc+4),memory.readWord(pc+6),d[0],d[1],d[2],d[3],d[4],d[5],d[6],d[7],a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],memory.readLong(a[7]),memory.readLong(a[6]+4),memory.readLong(a[4]),memory.readLong(a[4]+4),a[0]>0x100&&a[0]+64<memory.size()?argsAt(a[0]):"-",pcHistory[(pcHistoryPos-1)&(PC_HISTORY-1)],argsAt(a[6]+8),argsAt(a[7]));
			if((pc&0xfff00000)==(romBase&0xfff00000)&&pc>=romBase&&romHandler!=null&&romDispatch()){if(WATCH!=0)checkWatch(watchPc);continue;}
			if(pc>=0x40800000&&pc<0x41000000&&romHandler!=null&&foreignRomCall()){if(WATCH!=0)checkWatch(watchPc);continue;}
			if(M68kMemory.WRITE_PROBE!=null||M68kMemory.ADDR_PROBE!=null){memory.probePc=pc;memory.probeSp=a[7];}
			step();
			if(WATCH!=0)checkWatch(watchPc);
			if(maskLowered){maskLowered=false;interruptSource.maskLowered(this);}
			if(++sinceYield>=limit){
				// trap-free for long: a runaway loop; trapping steadily: real work, allowed for a while
				long now=System.nanoTime();
				if(traps!=limitTraps)trapNanos=now;
				else if(now-trapNanos>=TRAP_FREE_LIMIT_NANOS)throw new M68kCpuException(pc, "instruction limit exceeded (possible infinite loop)");
				limitTraps=traps;sinceYield=0;
				if(now-yieldNanos>XCMD_WALL_LIMIT_NANOS)throw new M68kCpuException(pc, "no yield for "+XCMD_WALL_LIMIT_NANOS/1_000_000_000L+" s (possible infinite loop)");
			}
			if((++instructions&(POLL_INTERVAL-1))==0){
				if(abortRequested())throw new M68kAbortException(pc);
				if(scheduler!=null)pollPreserving();
				return false;
			}
		}
	}

	/**
	 * The scheduler poll between two instructions is this machine's interrupt: the work it runs (deferred tasks, Time Manager
	 * tasks, Open Transport notifiers - a PowerPC notifier reaches the Toolbox through 68K glue) must leave the interrupted code's
	 * registers and condition codes as they were, as the real interrupt handlers do. MacSSH died while typing: a T_DATA
	 * notifier ran in the middle of QuickTime's _QuickTimeDispatch, between its compare and its table lookup, and changed D0 -
	 * the dispatcher took the "unknown selector" exit, dropped a garbage number of argument bytes and returned into the stack.
	 */
	private void pollPreserving() throws M68kException {
		int[] savedD=d.clone(),savedA=a.clone();int savedSr=sr,savedPc=pc;
		scheduler.poll(this);
		if(pc!=savedPc)return; // the poll moved this context on deliberately (nothing does today): leave it so
		System.arraycopy(savedD,0,d,0,8);System.arraycopy(savedA,0,a,0,8);sr=savedSr;
	}

	/** -Dhz68k.ostrapframe=false: patched OS traps get only the return address, as before the ROM dispatcher frame. */
	static final boolean OS_TRAP_FRAME=!"false".equals(System.getProperty("hz68k.ostrapframe"));
	/** Where a patched OS trap routine returns to: the dispatcher's exit (A0 restored), and +4 the one without A0. Two words at
	 *  the top of the host-gate space, which the gates never reach. */
	private int osTrapExit(){return gateBase+0xfff8;}
	/** Executes trap ROM entries and host gates; returns false when {@code pc} is neither. */
	/**
	 * Calls into a real Mac's ROM ($40800000-, the 32-bit ROM of the Quadra/Centris/LC III family) that a patcher burned
	 * into code: a SANE optimizer rewrote each "MOVE.W #opcode,-(SP); _FP68K" of the AddColor XCMD shipped with
	 * MacToons-J into a JSR to that ROM's handler for the operation (the same 6 bytes), and HyperCard jumped into
	 * nothing. The entries seen are mapped back to FP68K with their opcode; anything else in that range is reported.
	 */
	private boolean foreignRomCall() throws M68kException {
		int opcode;
		switch(pc){
			case 0x408EAD30:opcode=0x0004;break; // FMULX
			case 0x408EB5EC:opcode=0x280E;break; // FL2X (long -> extended)
			case 0x408EB712:opcode=0x2810;break; // FX2L (extended -> long)
			case 0x408EB8B8:opcode=0x0016;break; // FTINTX
			default:throw new M68kCpuException(pc,String.format("jumped into a real Macintosh ROM at $%08X (code patched for one machine's ROM; the caller is at $%06X)",pc,pcHistory[(pcHistoryPos-1)&(PC_HISTORY-1)]));
		}
		int returnAddress=popLong();
		pushWord(opcode); // as if "MOVE.W #opcode,-(SP); _FP68K" had run: the trap handler pops the opcode and the operands
		int entry=pc;romReturnAddress=returnAddress;traps++;
		romHandler.romTrap(this,0xA9EB);
		if(pc==entry)pc=returnAddress;
		return true;
	}
	private boolean romDispatch() throws M68kException {
		if(pc==osTrapExit()||pc==osTrapExit()+4){ // the ROM dispatcher's exit: restore, TST.W D0, drop the hole, RTS
			if(pc==osTrapExit())a[0]=popLong();
			a[1]=popLong();d[1]=popLong();d[2]=popLong();a[2]=popLong();
			setResultCodeFlags();a[7]+=4;pc=popLong();return true;
		}
		if(pc>=trapTable&&pc<trapTable+0x4000){
			if(pc==stopAtRomEntry){pc=popLong();return true;} // a host-run patch chaining to the original trap: its end
			int entry=pc,word=trapWordForRomAddress(pc);
			if((word&0x0800)==0&&(word&0x0fff)==(word&0xff)){int pending=pendingOsTrapWords[word&0xff];if(pending!=0){pendingOsTrapWords[word&0xff]=0;word=pending;}}
			int returnAddress=popLong();romReturnAddress=returnAddress;traps++;
			romHandler.romTrap(this,word);
			if(pc==entry){pc=returnAddress;if((word&0x0800)==0)setResultCodeFlags();}// handler did not redirect
			return true;
		}
		if(pc>=gateBase&&pc<gateBase+0x10000){
			int gate=(pc-gateBase)>>>2;
			int returnAddress=popLong();traps++;
			romHandler.gate(this,gate);
			pc=returnAddress;
			return true;
		}
		return false;
	}

	void step() throws M68kException {
		int instructionPc = pc;
		pcHistory[pcHistoryPos++&(PC_HISTORY-1)]=instructionPc;
		int op = nextWord();
		switch(op>>>12){
			case 0x0: group0(op,instructionPc); return;
			case 0x1: move(op,1); return;
			case 0x2: move(op,4); return;
			case 0x3: move(op,2); return;
			case 0x4: group4(op,instructionPc); return;
			case 0x5: quickOrCondition(op,instructionPc); return;
			case 0x6: branch(op); return;
			case 0x7: { int value=(byte)op; d[(op>>>9)&7]=value; setLogicFlags(value,4); return; }
			case 0x8: orDivSbcd(op,instructionPc); return;
			case 0x9: addSub(op,false,instructionPc); return;
			case 0xa: aline(op,instructionPc); return;
			case 0xb: compareOrEor(op); return;
			case 0xc: andMulAbcdExg(op,instructionPc); return;
			case 0xd: addSub(op,true,instructionPc); return;
			case 0xe: shift(op,instructionPc); return;
			default:
				if(fpu!=null&&(op&0x0e00)==0x0200){fpu.execute(op,instructionPc);return;} // coprocessor 1 = floating-point unit
				if(op==0xFE09){ // Power Mac emulator ReadTimeBase: UnsignedWide in A0 (high), D0 (low), like Microseconds
					long ticks=(System.nanoTime()-timeBaseOrigin)/1000*(PpcCpu.TIME_BASE_HZ/1_000_000);
					a[0]=(int)(ticks>>>32);d[0]=(int)ticks;
					return;
				}
				if(op==0xFE0A){ // private emulator opcode PowerPC memory testers (Gauge PRO) issue with a selector in D0: the VM has no paging, so logical == physical (selector $13 answers the physical address), everything else (hold/unhold/cache) is accepted
					d[0]=(d[0]&0xFF)==0x13?a[0]:0;
					return;
				}
				if(exception(11,0,instructionPc,instructionPc))return; // a program's own F-line handler (SoftFPU) takes it
				throw new M68kCpuException(instructionPc,String.format("unsupported F-line (%s) instruction $%04X",fpu==null?"FPU disabled, -Dhz68k.fpu=false":"coprocessor "+((op>>>9)&7),op));
		}
	}

	/**
	 * The OS trap dispatcher ends with TST.W D0, so a program may branch on the result code right after an
	 * OS trap (_NewHandle / BNE.S fail) without testing D0 itself.
	 */
	void setResultCodeFlags(){
		sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C);
		short result=(short)d[0];
		if(result==0)sr|=FLAG_Z;else if(result<0)sr|=FLAG_N;
	}

	/** The Resource Manager's Toolbox routines end with MOVE.W D0,ResErr, so the condition codes show the error: `_Get1Resource; BNE.S fail`
	 *  (the FORTRAN 1.1a2 updater took every successful load for a failure and quit at once). */
	private static boolean isResourceTrap(int t){
		switch(t){case 0x9a0:case 0x9a1:case 0x9a2:case 0x9a3:case 0x992:case 0x9aa:case 0x9ab:case 0x9ac:case 0x9b0:case 0x99d:case 0x80e:case 0x81f:case 0x820:case 0x999:case 0x99a:return true;default:return false;}
	}

	private void setResErrFlags(){
		try{
			sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C);
			short result=(short)memory.readWord(0xa60);
			if(result==0)sr|=FLAG_Z;else if(result<0)sr|=FLAG_N;
		}catch(M68kException e){}
	}

	private void aline(int op,int instructionPc) throws M68kException {
		traps++; // a trap taken: work, not a runaway loop (a Director movie playing in MacroMind's PlayMovie XCMD runs for minutes on QuickDraw calls)
		// Toolbox trap with the auto-pop bit ($Axxx | $0400): the trap word sits in a glue routine reached by JSR,
		// so the dispatcher pops that JSR's return address before the routine sees its arguments and returns
		// there afterwards (a Pack6 glue sequence: JSR glue; glue = _Pack6,AutoPop).
		boolean autoPop=(op&0x0c00)==0x0c00;
		if(autoPop){op&=~0x0400;int returnAddress=popLong();
			int patch=patchedTrap(op);
			if(patch!=0){pushLong(returnAddress);pc=patch;return;}
			if(romHandler!=null)romHandler.inlineTrap(this,op);
			if(trapHandler!=null&&trapHandler.handle(this,op,instructionPc)){if(pc==instructionPc+2)pc=returnAddress;return;}
			// a package the program ships itself: entered like the ROM dispatcher does, with the
			// return address above the selector word and the arguments
			int entry=trapHandler==null?0:trapHandler.packageEntry(this,op);
			if(entry!=0){pushLong(returnAddress);pc=entry;return;}
			throw new M68kMacApiException(instructionPc,op,trapHandler==null?String.format("A-line trap $%04X",op):trapHandler.name(op));
		}
		// SetTrapAddress patches: run the patch with the return address of the trap.
		int patch=patchedTrap(op);
		if(patch!=0){
			// The patch usually chains to the old address (the ROM entry), which dispatches for real.
			if(Mac68kApi.TRACE)System.err.printf("[trap] %X %04X patched -> %X D0=%08X A0=%08X SP=%08X%n",instructionPc,op,patch,d[0],a[0],a[7]);
			if((op&0x0800)==0)pendingOsTrapWords[op&0xff]=op;
			if((op&0x0800)==0&&OS_TRAP_FRAME){
				// An OS trap routine is entered the way the ROM's dispatcher calls it: below its return address the dispatcher's
				// frame - A0 (unless bit 8 asks for A0 back), A1, D1, D2, A2, a hole, the caller's PC - and D1.W = the trap word.
				// The order is undocumented but fixed: Word 1.0's GetHandleSize patch calls the ROM routine, then pops these
				// registers itself and returns straight to the caller (it jumped to 0 when only the return address was there).
				boolean saveA0=(op&0x0100)==0;
				pushLong(pc);pushLong(sr<<16);pushLong(a[2]);pushLong(d[2]);pushLong(d[1]);pushLong(a[1]);if(saveA0)pushLong(a[0]);
				d[1]=(d[1]&0xffff0000)|(op&0xffff);
				pushLong(saveA0?osTrapExit():osTrapExit()+4);pc=patch;return;
			}
			pushLong(pc);pc=patch;return;
		}
		// OS traps return with the condition codes set from the result code in D0, and so do the register-based Memory Manager
		// utilities among the Toolbox numbers - _HandToHand $A9E1, _PtrToXHand $A9E2, _PtrToHand $A9E3, _HandAndHand $A9E4,
		// _PtrAndHand $A9EF (CompileIt!'s string runtime does `_HandToHand; BEQ ok` and took every copy for a failure: the
		// UxGetJFontList XFCN returned an empty list, "put x & y" lost x).
		if(romHandler!=null)romHandler.inlineTrap(this,op);
		if(trapHandler!=null && trapHandler.handle(this,op,instructionPc)) {int t=op&0x0fff;if((op&0x0800)==0||t==0x9e1||t==0x9e2||t==0x9e3||t==0x9e4||t==0x9ef)setResultCodeFlags();else if(isResourceTrap(t))setResErrFlags();return;}
		int entry=trapHandler==null?0:trapHandler.packageEntry(this,op);
		if(entry!=0){pushLong(pc);pc=entry;return;}
		String name=trapHandler==null?String.format("A-line trap $%04X",op):trapHandler.name(op);
		throw new M68kMacApiException(instructionPc, op, name);
	}

	// ------------------------------------------------------------------ group 0

	private void group0(int op,int instructionPc) throws M68kException {
		if((op&0x0100)!=0||(op&0x0f00)==0x0800){ // dynamic/static bit ops, MOVEP
			if((op&0x0138)==0x0108){ movep(op); return; }
			bitOperation(op,instructionPc); return;
		}
		int operation=(op>>>9)&7, sizeCode=(op>>>6)&3;
		if(sizeCode==3){
			if(operation==0||operation==1||operation==2){ chk2cmp2(op,instructionPc); return; }
			if(operation==5||operation==6||operation==7){ cas(op,instructionPc); return; }
			throw unsupported(instructionPc,op);
		}
		if(operation==4)throw unsupported(instructionPc,op);
		int size=1<<sizeCode;
		int value=size==4?nextLong():nextWord(); if(size==1)value&=0xff;
		if((op&0x3f)==0x3c) { // CCR/SR immediate forms
			int old=sr;
			if(operation==0) sr|=value; else if(operation==1) sr&=value; else if(operation==5) sr^=value; else throw unsupported(instructionPc,op);
			if(size==1)sr&=0xff|(sr&0xff00);
			srWritten(old);
			return;
		}
		ea(op&0x3f,size,operation!=6);
		int old=eaRead(size), result;
		switch(operation) {
			case 0: result=old|value; setLogicFlags(result,size); break;
			case 1: result=old&value; setLogicFlags(result,size); break;
			case 2: result=sub(old,value,size); break;
			case 3: result=add(old,value,size); break;
			case 5: result=old^value; setLogicFlags(result,size); break;
			case 6: compare(old,value,size); return; // CMPI
			default: throw unsupported(instructionPc,op);
		}
		eaWrite(size,result);
	}

	private void bitOperation(int op,int instructionPc) throws M68kException {
		int bit;
		boolean dynamic=(op&0x0100)!=0;
		if(dynamic)bit=d[(op>>>9)&7];else bit=nextWord()&0xff;
		int kind=(op>>>6)&3; // 0 BTST 1 BCHG 2 BCLR 3 BSET
		int spec=op&0x3f;
		if((spec&0x38)==0){ // data register: 32 bits
			int reg=spec&7;bit&=31;int mask=1<<bit;boolean set=(d[reg]&mask)!=0;setFlag(FLAG_Z,!set);
			if(kind==1)d[reg]^=mask;else if(kind==2)d[reg]&=~mask;else if(kind==3)d[reg]|=mask;return;
		}
		bit&=7;int mask=1<<bit;
		ea(spec,1,kind!=0);
		int value=eaRead(1);setFlag(FLAG_Z,(value&mask)==0);
		if(kind==0)return;
		if(kind==1)value^=mask;else if(kind==2)value&=~mask;else value|=mask;
		eaWrite(1,value);
	}

	private void movep(int op) throws M68kException {
		int dreg=(op>>>9)&7,areg=op&7,address=a[areg]+signedWord(nextWord());
		boolean toMemory=(op&0x80)!=0,longSize=(op&0x40)!=0;
		if(toMemory){
			int value=d[dreg];
			if(longSize){memory.writeByte(address,value>>>24);memory.writeByte(address+2,value>>>16);memory.writeByte(address+4,value>>>8);memory.writeByte(address+6,value);}
			else{memory.writeByte(address,value>>>8);memory.writeByte(address+2,value);}
		}else{
			if(longSize)d[dreg]=memory.readByte(address)<<24|memory.readByte(address+2)<<16|memory.readByte(address+4)<<8|memory.readByte(address+6);
			else d[dreg]=(d[dreg]&0xffff0000)|memory.readByte(address)<<8|memory.readByte(address+2);
		}
	}

	private void chk2cmp2(int op,int instructionPc) throws M68kException {
		int ext=nextWord();int size=1<<((op>>>9)&3);
		int address=effectiveAddress(op&0x3f,size);
		int lower=readSized(address,size),upper=readSized(address+size,size);
		int reg=(ext>>>12)&15;int value=reg<8?d[reg]:a[reg-8];
		if(reg<8){value=signExtend(value,size);lower=signExtend(lower,size);upper=signExtend(upper,size);}
		else if(size<4){lower=signExtend(lower,size);upper=signExtend(upper,size);}
		boolean out=value<lower||value>upper;
		setFlag(FLAG_Z,value==lower||value==upper);setFlag(FLAG_C,out);
		if((ext&0x0800)!=0&&out){if(exception(6,2,instructionPc,pc))return;throw new M68kCpuException(instructionPc,"CHK2 bounds exception");}
	}

	private void cas(int op,int instructionPc) throws M68kException {
		int ext=nextWord();
		if((op&0x3f)==0x3c){cas2(op,ext);return;}
		int size=1<<(((op>>>9)&3)-1);
		int dc=ext&7,du=(ext>>>6)&7;
		ea(op&0x3f,size,true);
		int value=eaRead(size);
		compare(value,d[dc],size);
		if((sr&FLAG_Z)!=0)eaWrite(size,d[du]);
		else d[dc]=merge(d[dc],value,size);
	}

	/** CAS2.W/.L Dc1:Dc2,Du1:Du2,(Rn1):(Rn2): both memory operands compared with Dc1/Dc2; both equal - Du1/Du2 are stored,
	 *  otherwise Dc1/Dc2 are loaded with the memory operands. Flags from the first comparison, or the second when the first matched. */
	private void cas2(int op,int ext1) throws M68kException {
		int ext2=nextWord(),size=(op&0x0600)==0x0600?4:2;
		int rn1=(ext1>>>12)&15,rn2=(ext2>>>12)&15;
		int address1=rn1>=8?a[rn1-8]:d[rn1],address2=rn2>=8?a[rn2-8]:d[rn2];
		int dc1=ext1&7,du1=(ext1>>>6)&7,dc2=ext2&7,du2=(ext2>>>6)&7;
		int v1=size==4?memory.readLong(address1):memory.readWord(address1),v2=size==4?memory.readLong(address2):memory.readWord(address2);
		compare(v1,d[dc1],size);
		if((sr&FLAG_Z)!=0)compare(v2,d[dc2],size);
		if((sr&FLAG_Z)!=0){if(size==4){memory.writeLong(address1,d[du1]);memory.writeLong(address2,d[du2]);}else{memory.writeWord(address1,d[du1]);memory.writeWord(address2,d[du2]);}}
		else{d[dc1]=merge(d[dc1],v1,size);d[dc2]=merge(d[dc2],v2,size);}
	}

	// ------------------------------------------------------------------ MOVE

	private void move(int op, int size) throws M68kException {
		int srcSpec=op&0x3f, dstSpec=((op>>>9)&7)|((op>>>3)&0x38);
		ea(srcSpec,size,false);
		int value=eaRead(size);
		if((dstSpec&0x38)==0x08) { // MOVEA
			a[dstSpec&7]=size==2?(short)value:value; return;
		}
		ea(dstSpec,size,true);
		eaWrite(size,value); setLogicFlags(value,size);
	}

	// ------------------------------------------------------------------ group 4

	private void group4(int op,int instructionPc) throws M68kException {
		if((op&0x0100)!=0){ // LEA, CHK
			if((op&0xfff8)==0x49c0){ int r=op&7; d[r]=(byte)d[r]; setLogicFlags(d[r],4); return; } // EXTB.L (68020+) shares LEA's bits
			if((op&0x01c0)==0x01c0){ a[(op>>>9)&7]=effectiveAddress(op&0x3f,4); return; }
			int size=(op&0x0080)!=0?2:4;
			int bound=signExtend(readEa(op&0x3f,size),size),value=signExtend(d[(op>>>9)&7],size);
			if(value<0||value>bound){setFlag(FLAG_N,value<0);if(exception(6,2,instructionPc,pc))return;throw new M68kCpuException(instructionPc,"CHK exception");}
			return;
		}
		switch(op&0xffc0){
			case 0x40c0: ea(op&0x3f,2,true);eaWrite(2,sr&0xa71f);return; // MOVE from SR
			case 0x42c0: ea(op&0x3f,2,true);eaWrite(2,sr&0xff);return; // MOVE from CCR
			case 0x44c0: sr=(sr&0xff00)|(readEa(op&0x3f,2)&0xff);return; // MOVE to CCR
			case 0x46c0: {int old=sr;sr=readEa(op&0x3f,2)&0xa71f;srWritten(old);return;} // MOVE to SR
			case 0x4800:
				if((op&0x38)==0x08){ // LINK.L
					int reg=op&7;int disp=nextLong();pushLong(a[reg]);a[reg]=a[7];a[7]+=disp;return;
				}
				nbcd(op);return;
			case 0x4840:
				if((op&0x38)==0){ int r=op&7; d[r]=d[r]<<16|d[r]>>>16; setLogicFlags(d[r],4); return; } // SWAP
				if((op&0x38)==0x08){ // BKPT
					throw new M68kCpuException(instructionPc,"BKPT");
				}
				pushLong(effectiveAddress(op&0x3f,4)); return; // PEA
			case 0x4880: case 0x48c0:
				if((op&0x38)==0){ // EXT
					int r=op&7;
					if((op&0x40)==0) d[r]=(d[r]&0xffff0000)|((byte)d[r]&0xffff);
					else d[r]=(short)d[r];
					setLogicFlags(d[r], (op&0x40)==0?2:4); return;
				}
				movem(op);return;
			case 0x4ac0:
				if(op==0x4afc){if(exception(4,0,instructionPc,instructionPc))return;throw new M68kCpuException(instructionPc,"ILLEGAL instruction");}
				{ ea(op&0x3f,1,true);int value=eaRead(1);setLogicFlags(value,1);eaWrite(1,value|0x80);return; } // TAS
			case 0x4c00: mullLong(op); return;
			case 0x4c40: divlLong(op,instructionPc); return;
			case 0x4c80: case 0x4cc0: movem(op); return;
			case 0x4e40: misc4e(op,instructionPc); return;
			case 0x4e80: { int target = effectiveAddress(op & 0x3f, 4); if(CALL_TRACE)traceCall(instructionPc,target); pushLong(pc); pc = target; return; } // JSR
			case 0x4ec0: pc = effectiveAddress(op & 0x3f, 4); return; // JMP
			default: break;
		}
		int family=op&0xff00, sizeCode=(op>>>6)&3;
		if(sizeCode==3)throw unsupported(instructionPc,op);
		int size=1<<sizeCode;
		switch(family){
			case 0x4000: { ea(op&0x3f,size,true);int result=subx(0,eaRead(size),size);eaWrite(size,result);return; } // NEGX
			case 0x4200: ea(op&0x3f,size,true);eaWrite(size,0);setLogicFlags(0,size);return; // CLR
			case 0x4400: { ea(op&0x3f,size,true);int result=sub(0,eaRead(size),size);eaWrite(size,result);return; } // NEG
			case 0x4600: { ea(op&0x3f,size,true);int result=~eaRead(size);eaWrite(size,result);setLogicFlags(result,size);return; } // NOT
			case 0x4a00: { int value=readEa(op&0x3f,size);setLogicFlags(value,size);return; } // TST
			default: throw unsupported(instructionPc,op);
		}
	}

	private void misc4e(int op,int instructionPc) throws M68kException {
		if((op&0xfff0)==0x4e40){ // TRAP #n
			if(exception(32+(op&15),0,instructionPc,pc))return;
			throw new M68kCpuException(instructionPc,"TRAP #"+(op&15));
		}
		if((op&0xfff8)==0x4e50){ pushLong(a[op & 7]); a[op & 7] = a[7]; a[7] += signedWord(nextWord()); return; } // LINK
		if((op&0xfff8)==0x4e58){ a[7] = a[op & 7]; a[op & 7] = popLong(); return; } // UNLK
		if((op&0xfff8)==0x4e60){ usp=a[op&7]; return; } // MOVE An,USP
		if((op&0xfff8)==0x4e68){ a[op&7]=usp; return; } // MOVE USP,An
		switch(op){
			case 0x4e70: throw new Toolbox68k.ExitToShell(); // RESET: a program resetting the machine to quit (PT-109) ends like ExitToShell
			case 0x4e71: return; // NOP
			case 0x4e72: nextWord(); throw new M68kCpuException(instructionPc,"STOP");
			case 0x4e73: { // RTE: a 68020/030 frame - format 0 (8 bytes) or 2 (12) with a vector offset in its format word; anything
				// else is taken as the 6-byte 68000 frame programs build themselves (HyperZebra's RTE never read a format word before)
				int newSr=popWord(),newPc=popLong(),word=memory.readWord(a[7]),format=word>>>12,offset=word&0xfff;
				if((format==0||format==2)&&(offset&3)==0&&offset<0x400){a[7]+=format==2?6:2;}
				int old=sr;sr=newSr;pc=newPc;srWritten(old);return;}
			case 0x4e74: { int disp=signedWord(nextWord()); pc=popLong(); a[7]+=disp; return; } // RTD
			case 0x4e75: pc = popLong(); return; // RTS
			case 0x4e76: if((sr&FLAG_V)!=0){if(exception(7,2,instructionPc,pc))return;throw new M68kCpuException(instructionPc,"TRAPV");} return;
			case 0x4e77: sr=(sr&0xff00)|(popWord()&0xff); pc = popLong(); return; // RTR
			case 0x4e7a: case 0x4e7b: { // MOVEC
				int ext=nextWord();int reg=(ext>>>12)&15,control=ext&0xfff;
				if(op==0x4e7a){int value=control==0x801?vbr:control==0x002?cacr:0;if(reg<8)d[reg]=value;else a[reg-8]=value;}
				else{int value=reg<8?d[reg]:a[reg-8];if(control==0x801)vbr=value;else if(control==0x002)cacr=value;}
				return;
			}
			default: throw unsupported(instructionPc,op);
		}
	}
	private int usp,vbr,cacr;
	/** The exception vectors 0-63 as the system set them up ({@link #snapshotVectors}); one that differs is a program's own. */
	private int[] systemVectors;
	/** Records the vector table after start-up: from then on a changed vector is a handler the program installed. */
	void snapshotVectors() throws M68kCpuException {int[] v=new int[64];for(int i=0;i<64;i++)v[i]=memory.readLong(i*4);systemVectors=v;}
	/**
	 * A 68K exception through the vector table when the program installed a handler there (TRAP #n for THINK Pascal's
	 * debugger, a divide-by-zero handler, SoftFPU's F-line handler): a 68020/030 frame - format 0 (SR, PC, format/vector)
	 * or format 2 (plus the instruction's address) - and the handler runs in supervisor mode. False when the vector is
	 * still the system's, and the caller stops the program as before.
	 */
	private boolean exception(int vector,int format,int instructionPc,int returnPc) throws M68kCpuException {
		int handler=memory.readLong(vbr+vector*4);
		if(handler==0||(handler&1)!=0)return false;
		if(vbr==0&&(systemVectors==null||handler==systemVectors[vector]))return false;
		if(format==2){a[7]-=4;memory.writeLong(a[7],instructionPc);}
		a[7]-=2;memory.writeWord(a[7],format<<12|vector*4);
		a[7]-=4;memory.writeLong(a[7],returnPc);
		a[7]-=2;memory.writeWord(a[7],sr);
		sr=(sr|0x2000)&~0x8000;pc=handler;return true;
	}

	private void nbcd(int op) throws M68kException {
		ea(op&0x3f,1,true);int value=eaRead(1);int x=(sr&FLAG_X)!=0?1:0;
		int low=0-(value&15)-x;int high=0-(value>>>4&15);
		if(low<0){low+=10;high--;}
		boolean carry=false;if(high<0){high+=10;carry=true;}
		int result=(high<<4|low)&0xff;
		if(value==0&&x==0){result=0;carry=false;}
		setFlag(FLAG_C,carry);setFlag(FLAG_X,carry);if(result!=0)setFlag(FLAG_Z,false);
		eaWrite(1,result);
	}

	private void mullLong(int op) throws M68kException {
		int ext=nextWord();int dl=(ext>>>12)&7,dh=ext&7;boolean signed=(ext&0x0800)!=0,quad=(ext&0x0400)!=0;
		int source=readEa(op&0x3f,4);
		long result=signed?(long)source*(long)d[dl]:(source&0xffffffffL)*(d[dl]&0xffffffffL);
		d[dl]=(int)result;
		sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C);
		if(quad){d[dh]=(int)(result>>>32);setFlag(FLAG_Z,result==0);setFlag(FLAG_N,result<0);}
		else{setFlag(FLAG_Z,(int)result==0);setFlag(FLAG_N,(int)result<0);
			boolean overflow=signed?result!=(long)(int)result:(result>>>32)!=0;setFlag(FLAG_V,overflow);}
	}

	private void divlLong(int op,int instructionPc) throws M68kException {
		int ext=nextWord();int dq=(ext>>>12)&7,dr=ext&7;boolean signed=(ext&0x0800)!=0,quad=(ext&0x0400)!=0;
		int divisor=readEa(op&0x3f,4);
		if(divisor==0){if(exception(5,2,instructionPc,pc))return;throw new M68kCpuException(instructionPc,"division by zero");}
		sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C);
		if(quad){
			long dividend=((long)d[dr]<<32)|(d[dq]&0xffffffffL);
			if(signed){long q=dividend/(long)divisor,r=dividend%(long)divisor;if(q!=(long)(int)q){sr|=FLAG_V;return;}d[dq]=(int)q;d[dr]=(int)r;setFlag(FLAG_Z,q==0);setFlag(FLAG_N,q<0);}
			else{long q=Long.divideUnsigned(dividend,divisor&0xffffffffL),r=Long.remainderUnsigned(dividend,divisor&0xffffffffL);if((q>>>32)!=0){sr|=FLAG_V;return;}d[dq]=(int)q;d[dr]=(int)r;setFlag(FLAG_Z,q==0);setFlag(FLAG_N,(int)q<0);}
		}else{
			if(signed){long q=(long)d[dq]/(long)divisor,r=(long)d[dq]%(long)divisor;if(q!=(long)(int)q){sr|=FLAG_V;return;}if(dr!=dq)d[dr]=(int)r;d[dq]=(int)q;setFlag(FLAG_Z,q==0);setFlag(FLAG_N,q<0);}
			else{long q=(d[dq]&0xffffffffL)/(divisor&0xffffffffL),r=(d[dq]&0xffffffffL)%(divisor&0xffffffffL);if(dr!=dq)d[dr]=(int)r;d[dq]=(int)q;setFlag(FLAG_Z,q==0);setFlag(FLAG_N,(int)q<0);}
		}
	}

	// ------------------------------------------------------------------ group 5, 6

	private void quickOrCondition(int op,int instructionPc) throws M68kException {
		int sizeCode=(op>>>6)&3;
		if(sizeCode==3) {
			int cc=(op>>>8)&15;
			if((op&0x38)==0x08) { // DBcc
				int base=pc,displacement=signedWord(nextWord());
				if(!condition(cc)) { int r=op&7, low=(d[r]-1)&0xffff; d[r]=(d[r]&0xffff0000)|low; if(low!=0xffff) pc=base+displacement; }
				return;
			}
			if((op&0x38)==0x38&&(op&7)>=2){ // TRAPcc
				int extra=(op&7)==2?2:(op&7)==3?4:0;pc+=extra;
				if(condition(cc)){if(exception(7,2,instructionPc,pc))return;throw new M68kCpuException(instructionPc,"TRAPcc");}
				return;
			}
			ea(op&0x3f,1,true);eaWrite(1,condition(cc)?0xff:0); // Scc
			return;
		}
		int size=1<<sizeCode, amount=(op>>>9)&7; if(amount==0) amount=8;
		if((op&0x38)==0x08) { // address-register quick arithmetic never changes flags
			int reg=op&7;
			a[reg]+=(op&0x0100)==0?amount:-amount;
			return;
		}
		ea(op&0x3f,size,true); int old=eaRead(size);
		int result=(op&0x0100)==0?add(old,amount,size):sub(old,amount,size);
		eaWrite(size,result);
	}

	private void branch(int op) throws M68kException {
		int cc=(op>>>8)&15, displacement=(byte)op;
		int base=pc;
		if((op&0xff)==0) displacement=signedWord(nextWord());
		else if((op&0xff)==0xff) displacement=nextLong();
		if(cc==1) { if(CALL_TRACE)traceCall(base-2,base+displacement); pushLong(pc); pc=base+displacement; }
		else if(cc==0 || condition(cc)) pc=base+displacement;
	}

	// ------------------------------------------------------------------ group 8, 9, B, C, D

	private void orDivSbcd(int op,int instructionPc) throws M68kException {
		int opmode=(op>>>6)&7, reg=(op>>>9)&7;
		if(opmode==3||opmode==7){ // DIVU/DIVS
			int divisor=readEa(op&0x3f,2);
			if((divisor&0xffff)==0){if(exception(5,2,instructionPc,pc))return;throw new M68kCpuException(instructionPc,"division by zero");}
			sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C);
			if(opmode==3) { long dividend=d[reg]&0xffffffffL, q=dividend/(divisor&0xffff), rem=dividend%(divisor&0xffff); if(q>0xffff){sr|=FLAG_V;return;} d[reg]=(int)(rem<<16|q); setNZ((int)q,2); }
			else { int q=d[reg]/(short)divisor, rem=d[reg]%(short)divisor; if(q<Short.MIN_VALUE||q>Short.MAX_VALUE){sr|=FLAG_V;return;} d[reg]=(rem<<16)|(q&0xffff); setNZ(q,2); }
			return;
		}
		if(opmode>=4&&(op&0x30)==0){ // SBCD, PACK, UNPK
			if(opmode==4){ sbcd(op); return; }
			int adjust=nextWord();
			boolean memoryForm=(op&8)!=0;
			if(opmode==5){ // PACK
				int src;if(memoryForm){a[op&7]-=2;src=memory.readWord(a[op&7]);}else src=d[op&7];
				src+=adjust;int packed=((src>>>4)&0xf0)|(src&15);
				if(memoryForm){a[reg]-=1;memory.writeByte(a[reg],packed);}else d[reg]=(d[reg]&0xffffff00)|packed;
				return;
			}
			// UNPK
			int src;if(memoryForm){a[op&7]-=1;src=memory.readByte(a[op&7]);}else src=d[op&7]&0xff;
			int unpacked=(((src&0xf0)<<4)|(src&15))+adjust;
			if(memoryForm){a[reg]-=2;memory.writeWord(a[reg],unpacked);}else d[reg]=(d[reg]&0xffff0000)|(unpacked&0xffff);
			return;
		}
		int size=1<<(opmode&3), value;
		if(opmode>=4) { ea(op&0x3f,size,true); value=eaRead(size)|d[reg]; eaWrite(size,value); }
		else { value=d[reg]|readEa(op&0x3f,size); d[reg]=merge(d[reg],value,size); }
		setLogicFlags(value,size);
	}

	private void sbcd(int op) throws M68kException {
		int src,dst;boolean memoryForm=(op&8)!=0;int rx=(op>>>9)&7,ry=op&7;
		if(memoryForm){a[ry]-=(ry==7?2:1);src=memory.readByte(a[ry]);a[rx]-=(rx==7?2:1);dst=memory.readByte(a[rx]);}
		else{src=d[ry]&0xff;dst=d[rx]&0xff;}
		int x=(sr&FLAG_X)!=0?1:0;
		int low=(dst&15)-(src&15)-x;int high=(dst>>>4)-(src>>>4);
		if(low<0){low+=10;high--;}
		boolean carry=false;if(high<0){high+=10;carry=true;}
		int result=(high<<4|low)&0xff;
		setFlag(FLAG_C,carry);setFlag(FLAG_X,carry);if(result!=0)setFlag(FLAG_Z,false);
		if(memoryForm)memory.writeByte(a[rx],result);else d[rx]=(d[rx]&0xffffff00)|result;
	}

	private void abcd(int op) throws M68kException {
		int src,dst;boolean memoryForm=(op&8)!=0;int rx=(op>>>9)&7,ry=op&7;
		if(memoryForm){a[ry]-=(ry==7?2:1);src=memory.readByte(a[ry]);a[rx]-=(rx==7?2:1);dst=memory.readByte(a[rx]);}
		else{src=d[ry]&0xff;dst=d[rx]&0xff;}
		int x=(sr&FLAG_X)!=0?1:0;
		int low=(dst&15)+(src&15)+x;int high=(dst>>>4)+(src>>>4);
		if(low>9){low-=10;high++;}
		boolean carry=false;if(high>9){high-=10;carry=true;}
		int result=(high<<4|low)&0xff;
		setFlag(FLAG_C,carry);setFlag(FLAG_X,carry);if(result!=0)setFlag(FLAG_Z,false);
		if(memoryForm)memory.writeByte(a[rx],result);else d[rx]=(d[rx]&0xffffff00)|result;
	}

	private void addSub(int op, boolean addition,int instructionPc) throws M68kException {
		int opmode=(op>>>6)&7, reg=(op>>>9)&7;
		if(opmode==3 || opmode==7) { int size=opmode==3?2:4, value=readEa(op&0x3f,size); int operand=size==2?(short)value:value; a[reg]+=addition?operand:-operand; return; }
		int size=1<<(opmode&3); boolean toEa=opmode>=4;
		if(toEa&&(op&0x30)==0){ // ADDX/SUBX
			boolean memoryForm=(op&8)!=0;int rx=reg,ry=op&7;int src,dst;
			if(memoryForm){a[ry]-=increment(ry,size);src=readSized(a[ry],size);a[rx]-=increment(rx,size);dst=readSized(a[rx],size);}
			else{src=d[ry];dst=d[rx];}
			int result=addition?addx(dst,src,size):subx(dst,src,size);
			if(memoryForm)writeSized(a[rx],size,result);else d[rx]=merge(d[rx],result,size);
			return;
		}
		if(toEa) { ea(op&0x3f,size,true); int old=eaRead(size); eaWrite(size,addition?add(old,d[reg],size):sub(old,d[reg],size)); }
		else { int value=readEa(op&0x3f,size); int result=addition?add(d[reg],value,size):sub(d[reg],value,size); d[reg]=merge(d[reg],result,size); }
	}

	private void compareOrEor(int op) throws M68kException {
		int opmode=(op>>>6)&7, reg=(op>>>9)&7;
		if(opmode==3 || opmode==7) { int size=opmode==3?2:4; int v=readEa(op&0x3f,size); compare(a[reg],size==2?(short)v:v,4); return; }
		int size=1<<(opmode&3);
		if(opmode>=4){
			if((op&0x38)==0x08){ // CMPM
				int ry=op&7,rx=reg;int src=readSized(a[ry],size);a[ry]+=increment(ry,size);int dst=readSized(a[rx],size);a[rx]+=increment(rx,size);
				compare(dst,src,size);return;
			}
			ea(op&0x3f,size,true); int result=eaRead(size)^d[reg]; eaWrite(size,result); setLogicFlags(result,size);
		}
		else { int value=readEa(op&0x3f,size); compare(d[reg],value,size); }
	}

	private void andMulAbcdExg(int op,int instructionPc) throws M68kException {
		int opmode=(op>>>6)&7, reg=(op>>>9)&7;
		if(opmode==3||opmode==7){ // MULU/MULS
			int v=readEa(op&0x3f,2);
			d[reg]=opmode==3?((d[reg]&0xffff)*(v&0xffff)):((short)d[reg]*(short)v);
			setLogicFlags(d[reg],4);return;
		}
		if(opmode>=4&&(op&0x30)==0){
			if(opmode==4){ abcd(op); return; }
			// EXG
			int rx=reg,ry=op&7;int mode=op&0xf8;
			if(mode==0x40){int t=d[rx];d[rx]=d[ry];d[ry]=t;}
			else if(mode==0x48){int t=a[rx];a[rx]=a[ry];a[ry]=t;}
			else if(mode==0x88){int t=d[rx];d[rx]=a[ry];a[ry]=t;}
			else throw unsupported(instructionPc,op);
			return;
		}
		int size=1<<(opmode&3), value;
		if(opmode>=4) { ea(op&0x3f,size,true); value=eaRead(size)&d[reg]; eaWrite(size,value); }
		else { value=d[reg]&readEa(op&0x3f,size); d[reg]=merge(d[reg],value,size); }
		setLogicFlags(value,size);
	}

	// ------------------------------------------------------------------ group E

	private void shift(int op,int instructionPc) throws M68kException {
		if((op&0x00c0)==0x00c0) {
			if((op&0x0800)!=0){ bitField(op,instructionPc); return; }
			// memory shift, one bit
			ea(op&0x3f,2,true); int old=eaRead(2), kind=(op>>>9)&3; boolean left=(op&0x0100)!=0; eaWrite(2,doShift(old,1,2,kind,left)); return;
		}
		int size=1<<((op>>>6)&3);
		int count=(op&0x20)!=0?d[(op>>>9)&7]&63:(op>>>9)&7; if(count==0 && (op&0x20)==0)count=8;
		int reg=op&7, result=doShift(d[reg],count,size,(op>>>3)&3,(op&0x0100)!=0); d[reg]=merge(d[reg],result,size);
	}

	private int doShift(int value,int count,int size,int kind,boolean left) {
		int bits=size*8, mask=mask(size), v=value&mask; boolean carry=false;boolean overflow=false;
		int originalSign=v&(1<<(bits-1));
		for(int i=0;i<count;i++) {
			if(left) { carry=(v&(1<<(bits-1)))!=0; int x=(sr&FLAG_X)!=0?1:0; v=(v<<1)&mask; if(kind==2)v|=x; if(kind==3&&carry)v|=1; if(kind==0&&(v&(1<<(bits-1)))!=originalSign)overflow=true; }
			else { carry=(v&1)!=0; boolean sign=(v&(1<<(bits-1)))!=0; int x=(sr&FLAG_X)!=0?1:0; v>>>=1; if(kind==0&&sign)v|=1<<(bits-1); if(kind==2&&x!=0)v|=1<<(bits-1); if(kind==3&&carry)v|=1<<(bits-1); }
			if(kind==0||kind==1||kind==2) setFlag(FLAG_X,carry);
		}
		setLogicFlags(v,size);
		if(count==0){ if(kind==2)setFlag(FLAG_C,(sr&FLAG_X)!=0); return v; }
		setFlag(FLAG_C,carry);
		if(kind==0&&left)setFlag(FLAG_V,overflow);
		return v;
	}

	private void bitField(int op,int instructionPc) throws M68kException {
		int ext=nextWord();
		int kind=(op>>>8)&7; // 0 BFTST 1 BFEXTU 2 BFCHG 3 BFEXTS 4 BFCLR 5 BFFFO 6 BFSET 7 BFINS
		int offset=(ext&0x0800)!=0?d[(ext>>>6)&7]:(ext>>>6)&31;
		int width=(ext&0x0020)!=0?d[ext&7]&31:ext&31; if(width==0)width=32;
		int reg=(ext>>>12)&7;
		long field;int baseAddress=0;boolean register=(op&0x38)==0;
		int bitStart,fieldOffset=offset; // BFFFO answers the offset as given (a register offset is signed, and may be 32 or more)
		if(register){
			int value=d[op&7];offset&=31;
			long rotated=(((long)value<<32|(value&0xffffffffL))>>>(32-offset))&0xffffffffL; // rotate left by offset
			field=(rotated>>>(32-width))&((1L<<width)-1);
			bitStart=offset;
		}else{
			baseAddress=effectiveAddress(op&0x3f,1);
			// the field starts offset bits from the base byte: a signed byte step (offset>>3, the old code stepped offset>>5 bytes:
			// Prince of Persia 2's SHAP decoder read the wrong bytes) and a bit offset 0-7 in that byte, 5 bytes (40 bits) read
			baseAddress+=offset>>3;int bitOffset=offset&7;
			long bits=0;for(int i=0;i<5;i++)bits=bits<<8|memory.readByte(baseAddress+i);
			field=(bits>>>(40-bitOffset-width))&((1L<<width)-1);
			bitStart=bitOffset;
		}
		int msb=(int)((field>>>(width-1))&1);
		sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C);setFlag(FLAG_N,msb!=0);setFlag(FLAG_Z,field==0);
		long newField=field;boolean write=false;
		switch(kind){
			case 0:return;
			case 1:d[reg]=(int)field;return;
			case 3:d[reg]=(int)(width==32?field:(field|(msb!=0?-1L<<width:0)));return;
			case 5:{int n=0;for(int i=width-1;i>=0;i--){if((field>>>i&1)!=0)break;n++;}d[reg]=fieldOffset+n;return;}
			case 2:newField=~field&((1L<<width)-1);write=true;break;
			case 4:newField=0;write=true;break;
			case 6:newField=(1L<<width)-1;write=true;break;
			case 7:newField=d[reg]&((1L<<width)-1);write=true;
				setFlag(FLAG_N,(newField>>>(width-1)&1)!=0);setFlag(FLAG_Z,newField==0);break;
			default:throw unsupported(instructionPc,op);
		}
		if(!write)return;
		if(register){
			int r=op&7;long m=((1L<<width)-1)<<(32-width);
			long rotated=Integer.rotateLeft(d[r],bitStart)&0xffffffffL;
			rotated=(rotated&~m)|(newField<<(32-width));
			d[r]=Integer.rotateRight((int)rotated,bitStart);
		}else{
			int byteOffset=bitStart>>>3;int bitOffset=bitStart&7;
			long bits=0;for(int i=0;i<5;i++)bits=bits<<8|memory.readByte(baseAddress+byteOffset+i);
			int shift=40-bitOffset-width;long m=((1L<<width)-1)<<shift;
			bits=(bits&~m)|(newField<<shift);
			for(int i=4;i>=0;i--){memory.writeByte(baseAddress+byteOffset+i,(int)(bits&0xff));bits>>>=8;}
		}
	}

	private void movem(int op) throws M68kException {
		int registerMask=nextWord(), size=(op&0x40)!=0?4:2, mode=(op>>>3)&7, reg=op&7; boolean memoryToRegisters=(op&0x0400)!=0;
		if(memoryToRegisters) {
			int address=mode==3?a[reg]:effectiveAddress(op&0x3f,size);
			for(int i=0;i<16;i++) if((registerMask&(1<<i))!=0) { int value=size==4?memory.readLong(address):(short)memory.readWord(address); if(i<8)d[i]=value;else a[i-8]=value; address+=size; }
			if(mode==3)a[reg]=address;
		} else if(mode==4) {
			int address=a[reg];
			for(int bit=0;bit<16;bit++) if((registerMask&(1<<bit))!=0) { int logical=15-bit; address-=size; int value=logical<8?d[logical]:a[logical-8]; if(size==4)memory.writeLong(address,value);else memory.writeWord(address,value); }
			a[reg]=address;
		} else {
			int address=effectiveAddress(op&0x3f,size);
			for(int i=0;i<16;i++) if((registerMask&(1<<i))!=0) { int value=i<8?d[i]:a[i-8]; if(size==4)memory.writeLong(address,value);else memory.writeWord(address,value); address+=size; }
		}
	}

	// ------------------------------------------------------------------ arithmetic helpers

	private int add(int left,int right,int size) {
		long mask=mask(size)&0xffffffffL, l=left&mask, r=right&mask, full=l+r; int result=(int)full;
		int sign=signBit(size); setFlag(FLAG_C,full>mask); setFlag(FLAG_X,full>mask); setFlag(FLAG_V,((~(left^right)&(left^result)&sign)!=0)); setNZ(result,size); return result;
	}

	private int addx(int left,int right,int size) {
		long mask=mask(size)&0xffffffffL, l=left&mask, r=right&mask, full=l+r+((sr&FLAG_X)!=0?1:0); int result=(int)full;
		int sign=signBit(size); setFlag(FLAG_C,full>mask); setFlag(FLAG_X,full>mask); setFlag(FLAG_V,((~(left^right)&(left^result)&sign)!=0));
		setFlag(FLAG_N,(result&sign)!=0); if((result&mask)!=0)setFlag(FLAG_Z,false); return result;
	}

	private int sub(int left,int right,int size) {
		long mask=mask(size)&0xffffffffL, l=left&mask, r=right&mask; int result=(int)(l-r); boolean borrow=r>l;
		setFlag(FLAG_C,borrow); setFlag(FLAG_X,borrow); int sign=signBit(size); setFlag(FLAG_V,(((left^right)&(left^result)&sign)!=0)); setNZ(result,size); return result;
	}

	private int subx(int left,int right,int size) {
		long mask=mask(size)&0xffffffffL, l=left&mask, r=right&mask; long x=(sr&FLAG_X)!=0?1:0; int result=(int)(l-r-x); boolean borrow=r+x>l;
		setFlag(FLAG_C,borrow); setFlag(FLAG_X,borrow); int sign=signBit(size); setFlag(FLAG_V,(((left^right)&(left^result)&sign)!=0));
		setFlag(FLAG_N,(result&sign)!=0); if((result&mask)!=0)setFlag(FLAG_Z,false); return result;
	}

	private void compare(int left,int right,int size) {
		int oldX=sr&FLAG_X;
		sub(left,right,size);
		sr=(sr&~FLAG_X)|oldX;
	}

	private void setLogicFlags(int value,int size) { sr&=~(FLAG_N|FLAG_Z|FLAG_V|FLAG_C); setNZ(value,size); }
	private void setNZ(int value,int size) { int masked=value&mask(size); setFlag(FLAG_Z,masked==0); setFlag(FLAG_N,(masked&signBit(size))!=0); }
	private void setFlag(int flag,boolean value) { if(value)sr|=flag;else sr&=~flag; }
	private boolean condition(int cc) {
		boolean c=(sr&FLAG_C)!=0,v=(sr&FLAG_V)!=0,z=(sr&FLAG_Z)!=0,n=(sr&FLAG_N)!=0;
		switch(cc) { case 0:return true;case 1:return false;case 2:return !c&&!z;case 3:return c||z;case 4:return !c;case 5:return c;case 6:return !z;case 7:return z;case 8:return !v;case 9:return v;case 10:return !n;case 11:return n;case 12:return n==v;case 13:return n!=v;case 14:return !z&&n==v;default:return z||n!=v; }
	}

	// ------------------------------------------------------------------ effective addresses

	private int effectiveAddress(int spec,int size) throws M68kException { ea(spec,size,false); return eaAddressValue(); }
	private int readEa(int spec,int size) throws M68kException { ea(spec,size,false); return eaRead(size); }

	private void ea(int spec,int size,boolean writing) throws M68kException {
		int mode=(spec>>>3)&7, reg=spec&7;
		eaMode=mode;eaReg=reg;
		switch(mode) {
			case 0: case 1: return;
			case 2: eaAddress=a[reg]; return;
			case 3: eaAddress=a[reg]; a[reg]+=increment(reg,size); return;
			case 4: a[reg]-=increment(reg,size); eaAddress=a[reg]; return;
			case 5: eaAddress=a[reg]+signedWord(nextWord()); return;
			case 6: eaAddress=indexed(a[reg]); return;
			case 7:
				switch(reg) {
					case 0: eaAddress=(short)nextWord(); return;
					case 1: eaAddress=nextLong(); return;
					case 2: { int base=pc; eaAddress=base+signedWord(nextWord()); return; }
					case 3: { int base=pc; eaAddress=indexed(base); return; }
					case 4: if(writing)throw new M68kCpuException(pc-2,"immediate destination"); eaMode=8; eaImmediate=size==4?nextLong():nextWord(); if(size==1)eaImmediate&=0xff; return;
					default: throw new M68kCpuException(pc-2,"invalid effective address");
				}
			default: throw new AssertionError();
		}
	}

	/** Brief and full (68020) extension-word index modes. */
	private int indexed(int base) throws M68kException {
		int ext=nextWord();
		int indexValue=(ext&0x8000)!=0?a[(ext>>>12)&7]:d[(ext>>>12)&7];
		if((ext&0x0800)==0)indexValue=(short)indexValue;
		indexValue<<=(ext>>>9)&3;
		if((ext&0x0100)==0)return base+(byte)ext+indexValue;
		// full format
		if((ext&0x80)!=0)base=0; // base suppress
		if((ext&0x40)!=0)indexValue=0; // index suppress
		int bdSize=(ext>>>4)&3;int bd=bdSize==2?signedWord(nextWord()):bdSize==3?nextLong():0;
		int iis=ext&7;
		if(iis==0)return base+bd+indexValue;
		int odSize=iis&3;
		int intermediate;
		if(iis<4){ intermediate=memory.readLong(base+bd+indexValue); } // preindexed
		else { intermediate=memory.readLong(base+bd); } // postindexed
		int od=odSize==2?signedWord(nextWord()):odSize==3?nextLong():0;
		if(iis<4)return intermediate+od;
		return intermediate+indexValue+od;
	}

	private int eaRead(int size) throws M68kCpuException {
		switch(eaMode){
			case 0: return d[eaReg]&mask(size);
			case 1: return size==2?a[eaReg]&0xffff:a[eaReg];
			case 8: return eaImmediate;
			default: return readSized(eaAddress,size);
		}
	}

	private void eaWrite(int size,int value) throws M68kCpuException {
		switch(eaMode){
			case 0: d[eaReg]=merge(d[eaReg],value,size); return;
			case 1: a[eaReg]=size==2?(short)value:value; return;
			case 8: throw new M68kCpuException(pc-2,"immediate destination");
			default: writeSized(eaAddress,size,value);
		}
	}

	private int eaAddressValue() throws M68kCpuException {
		if(eaMode==0||eaMode==1||eaMode==8)throw new M68kCpuException(pc-2,"effective address is not memory");
		return eaAddress;
	}

	/**
	 * Address of a coprocessor operand of {@code size} bytes (1, 2, 4, 8 or 12): postincrement/predecrement step by that size and
	 * an immediate operand is read from the instruction stream. Returns -1 for a register direct mode (eaMode/eaReg tell which).
	 */
	int operandAddress(int spec,int size) throws M68kException {
		int mode=(spec>>>3)&7,reg=spec&7;
		switch(mode){
			case 0:case 1:eaMode=mode;eaReg=reg;return -1;
			case 3:{int address=a[reg];a[reg]+=increment(reg,size);eaMode=mode;return address;}
			case 4:a[reg]-=increment(reg,size);eaMode=mode;return a[reg];
			case 7:if(reg==4){int address=size==1?pc+1:pc;pc+=size==1?2:size;eaMode=8;return address;} // immediate
			default:ea(spec,4,false);return eaAddress;
		}
	}
	int operandMode(){return eaMode;}
	int operandRegister(){return eaReg;}

	private int readSized(int address,int size) throws M68kCpuException {
		return size==1?memory.readByte(address):size==2?memory.readWord(address):memory.readLong(address);
	}
	private void writeSized(int address,int size,int value) throws M68kCpuException {
		if(size==1)memory.writeByte(address,value);else if(size==2)memory.writeWord(address,value);else memory.writeLong(address,value);
	}

	private int increment(int reg,int size){return size==1&&reg==7?2:size;}
	private static int merge(int old,int value,int size){return size==1?(old&0xffffff00)|(value&0xff):size==2?(old&0xffff0000)|(value&0xffff):value;}
	private static int mask(int size){return size==1?0xff:size==2?0xffff:-1;}
	private static int signBit(int size){return size==1?0x80:size==2?0x8000:0x80000000;}
	private static int signExtend(int value,int size){return size==1?(byte)value:size==2?(short)value:value;}
	private static int signedWord(int value){return (short)value;}
	int nextWord() throws M68kCpuException {int value=memory.readWord(pc);pc+=2;return value;}
	int nextLong() throws M68kCpuException {int value=memory.readLong(pc);pc+=4;return value;}
	void pushLong(int value)throws M68kCpuException{a[7]-=4;memory.writeLong(a[7],value);}
	void pushWord(int value)throws M68kCpuException{a[7]-=2;memory.writeWord(a[7],value);}
	int popLong()throws M68kCpuException{int value=memory.readLong(a[7]);a[7]+=4;return value;}
	int popWord()throws M68kCpuException{int value=memory.readWord(a[7]);a[7]+=2;return value;}
	/** -Dhz68k.calls=true logs every JSR/BSR of hosted programs (caller -> target, D0, first stack longs). */
	static final boolean CALL_TRACE=Boolean.getBoolean("hz68k.calls");
	private void traceCall(int from,int target){
		if(nesting>0&&false)return;
		StringBuilder b=new StringBuilder(String.format("[call] %06X -> %06X D0=%08X D4=%08X A0=%08X SP=%08X",from,target,d[0],d[4],a[0],a[7]));
		try{for(int i=0;i<4;i++)b.append(String.format(" %08X",memory.readLong(a[7]+i*4)));}catch(M68kCpuException e){}
		System.err.println(b);
	}
	/** Registers, recent instruction addresses, code bytes at {@code at} and the stack top, for diagnosing crashes. */
	String dumpState(int at){
		StringBuilder b=new StringBuilder();
		for(int i=0;i<8;i++)b.append(String.format("D%d=%08X ",i,d[i]));b.append('\n');
		for(int i=0;i<8;i++)b.append(String.format("A%d=%08X ",i,a[i]));b.append(String.format("PC=%08X SR=%04X\n",pc,sr));
		if(fpu!=null&&fpu.used())b.append(fpu.dumpState()).append('\n');
		b.append("recent PCs:");
		// The position is read once: the `regs` debug command dumps a program that is still running, and re-reading the
		// counter in the condition let the CPU thread outrun the loop - it never ended and the StringBuilder grew until
		// "Requested array size exceeds VM limit" killed the snapshot thread.
		int end=pcHistoryPos,start=Math.max(0,end-PC_HISTORY);int lastPc=-1,repeats=0;
		for(int i=start;i<end;i++){int p=pcHistory[i&(PC_HISTORY-1)];if(p==lastPc){repeats++;continue;}if(repeats>0)b.append(" (x"+(repeats+1)+")");repeats=0;lastPc=p;b.append(String.format(" %06X",p));}if(repeats>0)b.append(" (x"+(repeats+1)+")");
		b.append("\ncode at PC:");
		for(int i=-16;i<16;i+=2){try{b.append(String.format(i==0?" [%04X]":" %04X",memory.readWord(at+i)));}catch(M68kCpuException e){b.append(" ????");}}
		int last=end>0?pcHistory[(end-1)&(PC_HISTORY-1)]:at;
		if(last!=at){ // the fault came from an instruction elsewhere (a jump into data): show where the program last was
			b.append(String.format("\ncode at last PC %06X:",last));
			for(int i=-32;i<32;i+=2){try{b.append(String.format(i==0?" [%04X]":" %04X",memory.readWord(last+i)));}catch(M68kCpuException e){b.append(" ????");}}
		}
		b.append("\nstack:");
		for(int i=0;i<16;i++){try{b.append(String.format(" %08X",memory.readLong(a[7]+i*4)));}catch(M68kCpuException e){break;}}
		return b.toString();
	}

	private M68kCpuException unsupported(int at,int op){return new M68kCpuException(at,String.format("unsupported opcode $%04X",op));}
}

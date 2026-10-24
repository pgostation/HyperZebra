import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BooleanSupplier;

/**
 * Thread Manager (_ThreadDispatch $ABF2) for hosted applications: cooperative threads, each with its own stack in the
 * application heap and a saved register set. A yield swaps the CPU's registers for the next ready thread's - the
 * emulated machine is one 68000, so switching is exactly what the real Thread Manager did. A new thread starts with a
 * Pascal frame (result slot, threadParam, return address) whose return lands in an exit stub, a private selector that
 * ends the thread, runs its terminator and switches away for good. The main thread also yields at WaitNextEvent when
 * other threads are ready, as the real Thread Manager's patch on the event traps did.
 * <p>
 * A register swap only works between contexts of the same emulator nesting depth: a thread that yields from inside a
 * host-made callback (a dialog filter, a WDEF) cannot hand the nested Java execute() loop to another thread. PowerPC
 * programs always yield from deeper: their threads are entered through Mixed Mode (68K interpreter -> PpcCpu.call) and
 * call the Thread Manager through the Toolbox bridge (PpcCpu -> PpcRuntime.callToolbox -> 68K). For them each thread
 * runs as a coroutine instead: it gets a Java thread of its own (a carrier) with its own chain of nested execute() frames,
 * and one baton says which carrier may run the emulator. A switch saves the running thread's machine state (both CPUs'
 * registers and nesting, the trap depth: {@link Context}), hands the baton over and parks; the resumed carrier puts its own
 * state back. Each PowerPC thread has its own PowerPC stack. -Dhz68k.threads=swap|coroutine overrides the choice
 * (default: coroutines for PowerPC programs, register swaps for 68K ones).
 * <p>
 * Multiprocessing Services tasks (PpcMultiprocessing) are coroutine threads too, started from Java ({@link #newTask}). The
 * machine is one processor running the application's task - the "blue" task: whichever Thread Manager thread runs it now
 * ({@link #blue}) - and the MP tasks cooperatively: they run when the blue task waits in an MP call, yields (MPYield) or
 * calls the event traps, and hand the processor on when they wait, yield or end. Thread Manager switches never pick an MP
 * task, and MP scheduling only ever resumes the blue task's current thread, as on Mac OS 9 where every cooperative thread
 * lives inside the one blue task. A waiting thread is {@link #WAITING} with a condition and a deadline; it becomes
 * runnable when the condition holds or the deadline passes (nothing signals it: the scheduler looks). With nothing
 * runnable, real time passes ({@link #idle}).
 */
final class ThreadManager68k {
	static final int NO_ERR=0,ERR_THREAD_TOO_MANY=-617,ERR_THREAD_NOT_FOUND=-618,ERR_THREAD_PROTOCOL=-619,PARAM_ERR=-50;
	static final int NO_THREAD=0,CURRENT_THREAD=1,APPLICATION_THREAD=2;
	static final int READY=0,STOPPED=1,RUNNING=2;
	/** Not a Thread Manager state: parked in a Multiprocessing Services wait ({@link #waitFor}). */
	static final int WAITING=3;
	/** A wait without a deadline. */
	static final long FOREVER=Long.MAX_VALUE;
	static final int DEFAULT_STACK=32768;

	final class ThreadRecord {
		final int id;int state=READY;boolean started,premade,fpu;
		int stack,stackSize,entry,param,resultPtr;int nesting;
		final int[] d=new int[8],a=new int[8];int pc,sr;
		/** FPU data and control registers of a thread created without kFPUNotNeeded. */
		double[] fp;int fpcr,fpsr,fpiar;
		int switchIn,switchInParam,switchOut,switchOutParam,terminator,terminatorParam;
		/** Coroutine mode: the Java thread running this thread's code (its carrier; the program's own thread for the
		 *  application thread), the machine state while it is suspended, what it throws when it resumes, where the baton
		 *  goes when its carrier has unwound, and its PowerPC stack. */
		Thread javaThread;Context context;Throwable pending;ThreadRecord killReturn;boolean carrier,disposeSelf,recycle;int ppcStack,ppcStackSize;
		/** A Multiprocessing Services task: its body, whether it is held (created suspended, stopped at an exception), and
		 *  while {@link #WAITING} what it waits for and until when (System.nanoTime, {@link #FOREVER}). */
		boolean mp,suspended;TaskBody body;BooleanSupplier waitFor;long waitDeadline;
		ThreadRecord(int id){this.id=id;}
	}
	/** The machine state of one suspended coroutine thread. */
	private static final class Context { M68kCpu.Context m68;PpcCpu.Context ppc;int trapDepth; }
	/** What a Multiprocessing Services task runs on its carrier (the task's entry and its end: the termination message). */
	interface TaskBody { void run(M68kCpu cpu) throws M68kException; }
	/** Multiprocessing Services' part in the scheduling (PpcMultiprocessing). */
	interface MpHooks {
		/** Fires the timers that are due (Java state only: queues, semaphores, event flags); returns the next timer's
		 *  deadline (System.nanoTime) or {@link #FOREVER}. */
		long fireTimers();
		/** Whether work waits for the application's task (MPRemoteCall from an MP task). */
		boolean blueWorkPending();
		/** Runs the work queued for the application's task (called on its thread). */
		void runBlueWork(M68kCpu cpu) throws M68kException;
	}
	/** Unwinds a coroutine thread's Java stack (DisposeThread of a thread that has frames of its own, the program's end);
	 *  only the carrier's base catches it. An Error, so that no handler for emulator exceptions on the way takes it. */
	private static final class Killed extends Error {
		private static final long serialVersionUID=1L;
		Killed(){super("Thread Manager thread disposed",null,false,false);}
	}
	private static final Killed KILLED=new Killed();
	/** -Dhz68k.threads=swap|coroutine: how threads switch; anything else picks coroutines for PowerPC programs only. */
	private static final String MODE=System.getProperty("hz68k.threads","auto");
	/** PowerPC frames are larger than 68K ones: a coroutine thread's PowerPC stack is at least this big. */
	static final int PPC_STACK_MIN=256*1024;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Every thread by id, in creation order (the application's main thread is id 2). */
	final Map<Integer,ThreadRecord> threads=new LinkedHashMap<Integer,ThreadRecord>();
	private ThreadRecord current;
	/**
	 * The thread whose switch-out procedure runs now: it has already stopped (or yielded) and is still {@link #current}. As on
	 * the real Thread Manager its state is what the switch made it, and a SetThreadReadyGivenTaskRef readies it: GUSI's
	 * switcher (MacSSH) asks for its own state there and, finding it stopped while a wakeup came in just before it stopped
	 * (an Open Transport notifier inside the thread's last call), readies itself again - else the thread slept for good.
	 */
	private ThreadRecord leaving;
	private int nextId=3,critical,scheduler,exitStub;
	/** The ThreadSchedulerUPP SetThreadScheduler installed (0 = none): Carbon's GetThreadScheduler. */
	int scheduler(){return scheduler;}
	/** Debugger notification procs (SetDebuggerNotificationProcs): kept, never called. */
	private int createProc,disposeProc,scheduleProc;
	/** Coroutine mode (decided at the first Thread Manager call), the baton's holder and the carriers' Java threads. */
	private Boolean coroutines;
	private final Object baton=new Object();
	private ThreadRecord holder;
	private final Set<Thread> carriers=new CopyOnWriteArraySet<Thread>();
	/** The Thread Manager thread running the application's (blue) task: the one MP scheduling resumes. */
	private ThreadRecord blue;
	/** Multiprocessing Services tasks alive, and the library's scheduling hooks (null until it is used). */
	private int mpTasks;
	MpHooks mp;
	/** Where the baton goes back to while {@link #shutDown} unwinds the carriers. */
	private final ThreadRecord shutdownRecord=new ThreadRecord(-1);

	ThreadManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	private boolean coroutines(){
		if(coroutines==null)coroutines="coroutine".equals(MODE)||!"swap".equals(MODE)&&ppcCpu()!=null;
		return coroutines;
	}
	private PpcCpu ppcCpu(){return tb.app!=null&&tb.app.ppc!=null?tb.app.ppc.cpu:null;}
	/** True for the Java thread of one of this program's coroutine threads (it runs the program's emulation in turn). */
	boolean isCarrier(Thread t){return carriers.contains(t);}

	static String name(int selector){
		switch(selector&0xff){
			case 0x03:return "NewThread";case 0x09:return "SetThreadScheduler";case 0x0a:return "SetThreadSwitcher";case 0x11:return "SetThreadTerminator";case 0x0d:return "SetDebuggerNotificationProcs";
			case 0x01:return "CreateThreadPool";case 0x02:return "GetFreeThreadCount";case 0x15:return "GetSpecificFreeThreadCount";case 0x13:return "GetDefaultThreadStackSize";case 0x14:return "ThreadCurrentStackSpace";
			case 0x04:return "DisposeThread";case 0x05:return "YieldToThread";case 0x06:return "GetCurrentThread";case 0x07:return "GetThreadState";case 0x08:return "SetThreadState";case 0x12:return "SetThreadStateEndCritical";
			case 0x0b:return "ThreadBeginCritical";case 0x0c:return "ThreadEndCritical";case 0x0e:return "GetThreadCurrentTaskRef";case 0x0f:return "GetThreadStateGivenTaskRef";case 0x10:return "SetThreadReadyGivenTaskRef";
			case 0xff:return "ThreadExit";default:return null;
		}
	}

	/** The application's main thread, made on first use so GetCurrentThread answers kApplicationThreadID before any NewThread. */
	private ThreadRecord main(M68kCpu cpu){
		if(current==null){ThreadRecord m=new ThreadRecord(APPLICATION_THREAD);m.started=true;m.fpu=true;m.state=RUNNING;m.nesting=cpu.nestingDepth();threads.put(m.id,m);setCurrent(m);}
		return current;
	}
	private void setCurrent(ThreadRecord t){current=t;if(!t.mp)blue=t;}
	private ThreadRecord find(int id){
		if(id==CURRENT_THREAD||id==NO_THREAD)return current;
		return threads.get(id);
	}
	/** Debug aid (the snapshot command "threads"): every thread with its state, the running one marked. */
	String describe(){
		StringBuilder b=new StringBuilder();
		for(ThreadRecord t:new ArrayList<ThreadRecord>(threads.values())){
			b.append(String.format("%n  thread %d%s %s%s%s%s entry $%x",t.id,t==current?" (running)":"",t.state==READY?"ready":t.state==STOPPED?"stopped":t.state==RUNNING?"running":"waiting",
				t.premade?" premade":"",t.mp?" mp":"",t.carrier?" carrier":"",t.entry));
		}
		return b.append(String.format("%n  critical %d, yields itself %b",critical,yieldsItself)).toString();
	}
	/** The state GetThreadState reports: the running thread runs, unless its switch-out procedure runs (see {@link #leaving}). */
	private int stateOf(ThreadRecord t){return t==current&&t!=leaving?RUNNING:t.state;}
	/** True while the program has more than its main thread. */
	boolean active(){return threads.size()>1;}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff,argBytes=(selector>>>8)*2;int sp=cpu.a[7];main(cpu);
		if(Mac68kApi.TRACE)System.err.println("[68K] ThreadDispatch "+name(selector)+" (current thread "+current.id+")");
		int result=NO_ERR;
		if(current.mp){cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],ERR_THREAD_PROTOCOL);return true;} // the Thread Manager is not MP-safe: its threads live in the application's task
		switch(selector&0xff){
			case 0x03:{ // NewThread(style, entry, param, stackSize, options, VAR result, VAR threadMade)
				int madePtr=memory.readLong(sp),resultPtr=memory.readLong(sp+4),options=memory.readLong(sp+8),stackSize=memory.readLong(sp+12),param=memory.readLong(sp+16),entry=memory.readLong(sp+20),style=memory.readLong(sp+24);
				ThreadRecord t=null;
				if((options&2)!=0){t=takePremade(stackSize,(options&16)!=0);if(t==null&&(options&4)==0){result=ERR_THREAD_TOO_MANY;break;}}
				if(t==null){t=new ThreadRecord(nextId++);t.stackSize=stackSize<=0?DEFAULT_STACK:Math.max(1024,stackSize);t.stack=memory.allocate(t.stackSize,4);if(t.stack==0){result=-108;break;}threads.put(t.id,t);}
				t.premade=false;t.entry=entry;t.param=param;t.resultPtr=resultPtr;t.state=(options&1)!=0?STOPPED:READY;t.nesting=cpu.nestingDepth();t.fpu=(options&8)==0;t.started=false;
				if(madePtr!=0)memory.writeLong(madePtr,t.id);
				if(Mac68kApi.TRACE)System.err.println("[68K] NewThread -> id "+t.id+" entry $"+Integer.toHexString(entry)+" stack "+t.stackSize+(t.state==STOPPED?" (suspended)":""));
				break;}
			case 0x09:scheduler=memory.readLong(sp);break;
			case 0x0a:{int inOrOut=memory.readByte(sp),procParam=memory.readLong(sp+2),proc=memory.readLong(sp+6),id=memory.readLong(sp+10);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}
				if(inOrOut!=0){t.switchIn=proc;t.switchInParam=procParam;}else{t.switchOut=proc;t.switchOutParam=procParam;}break;}
			case 0x11:{int procParam=memory.readLong(sp),proc=memory.readLong(sp+4),id=memory.readLong(sp+8);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}t.terminator=proc;t.terminatorParam=procParam;break;}
			case 0x0d:scheduleProc=memory.readLong(sp);disposeProc=memory.readLong(sp+4);createProc=memory.readLong(sp+8);break;
			case 0x01:{int stackSize=memory.readLong(sp),count=(short)memory.readWord(sp+4);
				for(int i=0;i<count;i++){ThreadRecord t=new ThreadRecord(nextId++);t.premade=true;t.stackSize=stackSize<=0?DEFAULT_STACK:Math.max(1024,stackSize);t.stack=memory.allocate(t.stackSize,4);if(t.stack==0){result=-108;break;}t.state=STOPPED;threads.put(t.id,t);}
				break;}
			case 0x02:{int countPtr=memory.readLong(sp);if(countPtr!=0)memory.writeWord(countPtr,premadeCount(-1,false));break;}
			case 0x15:{int countPtr=memory.readLong(sp),stackSize=memory.readLong(sp+4);if(countPtr!=0)memory.writeWord(countPtr,premadeCount(stackSize,true));break;}
			case 0x13:{int sizePtr=memory.readLong(sp);if(sizePtr!=0)memory.writeLong(sizePtr,DEFAULT_STACK);break;}
			case 0x14:{int freePtr=memory.readLong(sp),id=memory.readLong(sp+4);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}
				int spNow=t==current?cpu.a[7]:t.context!=null?t.context.m68.a[7]:t.a[7];int free=t.id==APPLICATION_THREAD?spNow-memory.readLong(0x130):spNow-t.stack;if(freePtr!=0)memory.writeLong(freePtr,Math.max(0,free));break;}
			case 0x04:{boolean recycle=memory.readByte(sp)!=0;int threadResult=memory.readLong(sp+2),id=memory.readLong(sp+6);ThreadRecord t=find(id);
				if(t==null||t.id==APPLICATION_THREAD){result=t==null?ERR_THREAD_NOT_FOUND:ERR_THREAD_PROTOCOL;break;}
				cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],NO_ERR);
				finish(cpu,t,threadResult,recycle);return true;} // may not come back to this thread
			case 0x05:{int suggested=memory.readLong(sp);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],NO_ERR);yieldsItself=true;yieldTo(cpu,suggested,false);return true;}
			case 0x06:{int idPtr=memory.readLong(sp);if(idPtr!=0)memory.writeLong(idPtr,current.id);break;}
			case 0x07:{int statePtr=memory.readLong(sp),id=memory.readLong(sp+4);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}if(statePtr!=0)memory.writeWord(statePtr,stateOf(t));break;}
			case 0x08:case 0x12:{int suggested=memory.readLong(sp),state=memory.readWord(sp+4),id=memory.readLong(sp+6);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}
				if((selector&0xff)==0x12&&critical>0)critical--;
				if(t==current){
					if(state==STOPPED){yieldsItself=true;cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],NO_ERR);if(!yieldTo(cpu,suggested,true)){if(Mac68kApi.TRACE)System.err.println("[68K] SetThreadState: no other ready thread, thread "+t.id+" keeps running");}return true;}
					break;} // ready/running for the running thread: nothing to do
				t.state=state==RUNNING?READY:state;break;}
			case 0x0b:critical++;break;
			case 0x0c:if(critical>0)critical--;break;
			case 0x0e:{int refPtr=memory.readLong(sp);if(refPtr!=0)memory.writeLong(refPtr,1);break;} // one task: the application
			case 0x0f:{int statePtr=memory.readLong(sp),id=memory.readLong(sp+4);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}if(statePtr!=0)memory.writeWord(statePtr,stateOf(t));break;}
			case 0x10:{int id=memory.readLong(sp);ThreadRecord t=find(id);if(t==null){result=ERR_THREAD_NOT_FOUND;break;}if(t!=current||t==leaving)t.state=READY;break;}
			case 0xff:{ // exit stub: the thread's entry returned; its Pascal result sits above the parameter
				int threadResult=memory.readLong(cpu.a[7]);finish(cpu,current,threadResult,false);return true;}
			default:result=PARAM_ERR;break;
		}
		cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],result);return true;
	}

	private int premadeCount(int stackSize,boolean exact){int n=0;for(ThreadRecord t:threads.values())if(t.premade&&(!exact||t.stackSize==stackSize))n++;return n;}
	private ThreadRecord takePremade(int stackSize,boolean exact){
		ThreadRecord best=null;
		for(ThreadRecord t:threads.values()){if(!t.premade)continue;if(exact){if(t.stackSize==stackSize)return t;continue;}if(t.stackSize>=Math.max(stackSize,1024)&&(best==null||t.stackSize<best.stackSize))best=t;}
		return best;
	}

	/** Ends {@code t}: its result goes where NewThread was told, the terminator runs, and the CPU moves on to another thread
	 *  (when the thread ending is the running one) or the record is simply dropped/recycled. */
	private void finish(M68kCpu cpu,ThreadRecord t,int threadResult,boolean recycle) throws M68kException {
		if(Mac68kApi.TRACE)System.err.println("[68K] thread "+t.id+" ends (result $"+Integer.toHexString(threadResult)+(recycle?", recycled":"")+")");
		if(t.resultPtr!=0)memory.writeLong(t.resultPtr,threadResult);
		if(t.terminator!=0){int[] d=cpu.d.clone(),a=cpu.a.clone();try{cpu.pushLong(t.id);cpu.pushLong(t.terminatorParam);cpu.callPascal(t.terminator,8);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}}
		t.terminator=0;t.switchIn=0;t.switchOut=0;t.entry=0;
		if(coroutines()){finishCoroutine(cpu,t,recycle);return;}
		t.started=false;
		if(recycle){t.premade=true;t.state=STOPPED;}else if(t!=current){threads.remove(t.id);if(t.stack!=0)memory.free(t.stack);t.stack=0;}
		if(t==current){
			t.state=STOPPED;
			ThreadRecord next=pick(cpu,NO_THREAD);
			if(next==null){ // nothing else can run at this depth: fall back to the main thread regardless
				next=threads.get(APPLICATION_THREAD);if(next==null||next==t)throw new M68kCpuException(cpu.pc,"the last thread ended");
				if(Mac68kApi.TRACE)System.err.println("[68K] thread "+t.id+" ended with nothing ready: resuming the application thread");}
			switchTo(cpu,next);
			if(!recycle){threads.remove(t.id);if(t.stack!=0)memory.free(t.stack);t.stack=0;}
		}
	}

	/** The thread to run next: the suggested one when ready, else the scheduler proc's choice, else round-robin after the current. */
	private ThreadRecord pick(M68kCpu cpu,int suggested) throws M68kException {
		int depth=coroutines()?-1:cpu.nestingDepth();
		ThreadRecord s=suggested==NO_THREAD||suggested==CURRENT_THREAD?null:threads.get(suggested);
		if(s!=null&&s!=current&&s.state==READY&&!s.premade&&!s.mp&&reachable(s,depth))return s;
		if(scheduler!=0){
			int rec=memory.allocate(16,2);int chosen;
			int[] d=cpu.d.clone(),a=cpu.a.clone();
			try{memory.writeLong(rec,16);memory.writeLong(rec+4,current.id);memory.writeLong(rec+8,suggested);memory.writeLong(rec+12,0);
				cpu.a[7]-=4;cpu.pushLong(rec);cpu.callPascal(scheduler,4);chosen=memory.readLong(cpu.a[7]);cpu.a[7]+=4;}
			finally{memory.free(rec);System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
			ThreadRecord c=threads.get(chosen);
			if(c!=null&&c!=current&&c.state==READY&&!c.premade&&!c.mp&&reachable(c,depth))return c;
		}
		List<ThreadRecord> all=new ArrayList<ThreadRecord>(threads.values());int at=all.indexOf(current);
		for(int i=1;i<=all.size();i++){ThreadRecord t=all.get((at+i)%all.size());if(t!=current&&t.state==READY&&!t.premade&&!t.mp&&reachable(t,depth))return t;}
		return null;
	}

	/** Whether a register swap at {@code depth} can resume {@code t} (-1: coroutines, any thread). */
	private static boolean reachable(ThreadRecord t,int depth){return depth<0||t.nesting==depth;}

	/** YieldToThread: gives the CPU to another ready thread ({@code stop}: the current one stops rather than staying ready).
	 *  False when nothing else could run (the caller continues). */
	boolean yieldTo(M68kCpu cpu,int suggested,boolean stop) throws M68kException {
		main(cpu);
		if(critical>0&&!stop)return false;
		ThreadRecord next=pick(cpu,suggested);
		if(next==null){if(stop)current.state=STOPPED;return false;}
		current.state=stop?STOPPED:READY;
		switchTo(cpu,next);return true;
	}
	/** The main thread's chance to let other threads run: the Thread Manager yielded inside WaitNextEvent and SystemTask.
	 *  Only the main thread: another thread's event call (68K code under 何かon林檎's REALbasic thread calls WaitNextEvent)
	 *  must not switch - REALbasic swaps its stack-check limit (gStackLimit) in its own round robin around YieldToThread,
	 *  and the switch it did not make later ran its thread with the main thread's limit (a StackOverflowException at start-up).
	 *  Not once the program has switched threads itself ({@link #yieldsItself}): the real Thread Manager never switches
	 *  cooperative threads inside an event call, and a program scheduling its threads keeps per-thread state around its own
	 *  yields - Macintosh Common Lisp 4 (SK8 1.2 PPC) swaps its catch chain and stacks with each stack group, and a thread
	 *  resumed from the main thread's WaitNextEvent threw to the main thread's catch frame. */
	void yieldAtEventLoop(M68kCpu cpu) throws M68kException {
		if(mpTasks>0||mp!=null&&mp.blueWorkPending())runTasks(cpu); // MP tasks are preemptive on a real Mac: here they run at the blue task's event calls
		if(!yieldsItself&&threads.size()-mpTasks>1&&critical==0&&(current==null||current.id==APPLICATION_THREAD))yieldTo(cpu,NO_THREAD,false);
	}
	/** The program has yielded on its own (YieldToThread, YieldToAnyThread, SetThreadState stopping itself). */
	private boolean yieldsItself;

	/** Saves the running thread's registers and installs {@code next}'s (its first frame when it has never run). */
	private void switchTo(M68kCpu cpu,ThreadRecord next) throws M68kException {
		if(coroutines()){switchCoroutine(cpu,next);return;}
		ThreadRecord from=current;
		switchOut(cpu,from);
		System.arraycopy(cpu.d,0,from.d,0,8);System.arraycopy(cpu.a,0,from.a,0,8);from.pc=cpu.pc;from.sr=cpu.sr;
		M68kFpu fpu=cpu.fpu;
		if(fpu!=null&&from.fpu){from.fp=fpu.fp.clone();from.fpcr=fpu.fpcr;from.fpsr=fpu.fpsr;from.fpiar=fpu.fpiar;}
		setCurrent(next);next.state=RUNNING;
		if(fpu!=null&&next.fpu&&next.fp!=null){System.arraycopy(next.fp,0,fpu.fp,0,8);fpu.fpcr=next.fpcr;fpu.fpsr=next.fpsr;fpu.fpiar=next.fpiar;}
		if(next.started){System.arraycopy(next.d,0,cpu.d,0,8);System.arraycopy(next.a,0,cpu.a,0,8);cpu.pc=next.pc;cpu.sr=next.sr;}
		else{
			next.started=true;
			for(int i=0;i<8;i++){cpu.d[i]=0;if(i<5||i==6)cpu.a[i]=0;} // A5 (application globals) carries over
			cpu.a[7]=(next.stack+next.stackSize-32)&~3;
			cpu.pushLong(0);cpu.pushLong(next.param);cpu.pushLong(exitStub()); // pascal voidPtr entry(threadParam)
			cpu.pc=next.entry;
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] thread switch "+from.id+" -> "+next.id+" at pc $"+Integer.toHexString(cpu.pc));
		if(next.switchIn!=0)callSwitcher(cpu,next.switchIn,next.id,next.switchInParam);
	}
	/** Runs {@code from}'s switch-out procedure, {@code from} counting as {@link #leaving} meanwhile. */
	private void switchOut(M68kCpu cpu,ThreadRecord from) throws M68kException {
		if(from.switchOut==0)return;
		ThreadRecord outer=leaving;leaving=from;
		try{callSwitcher(cpu,from.switchOut,from.id,from.switchOutParam);}finally{leaving=outer;}
	}
	private void callSwitcher(M68kCpu cpu,int proc,int id,int param) throws M68kException {
		int[] d=cpu.d.clone(),a=cpu.a.clone();int pc=cpu.pc;
		try{cpu.pushLong(id);cpu.pushLong(param);cpu.callPascal(proc,8);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.pc=pc;}
	}
	// ------------------------------------------------------------------ coroutine threads

	/** Coroutine switch to {@code next}: the running thread's carrier parks until some thread hands the baton back to it. */
	private void switchCoroutine(M68kCpu cpu,ThreadRecord next) throws M68kException {
		ThreadRecord from=current;
		switchOut(cpu,from);
		setCurrent(next);next.state=RUNNING;
		if(Mac68kApi.TRACE)System.err.println("[68K] thread switch "+from.id+" -> "+next.id+(next.started?"":" (starts)")+" at depth "+cpu.nestingDepth());
		if(!next.started)startCarrier(cpu,next);
		park(cpu,from,next);
		if(from.switchIn!=0)callSwitcher(cpu,from.switchIn,from.id,from.switchInParam);
	}

	/** Saves {@code self}'s machine state, gives the emulator to {@code to} and waits until {@code self} has it again; then puts the
	 *  state back, or throws what was left for it (another thread's crash for the application thread, {@link Killed}). */
	private void park(M68kCpu cpu,ThreadRecord self,ThreadRecord to) throws M68kException {
		self.context=capture(cpu);self.javaThread=Thread.currentThread();
		handOff(self,to,true);
		install(cpu,self.context);self.context=null;
		Throwable p=self.pending;self.pending=null;
		if(p!=null)rethrow(p);
	}
	private static void rethrow(Throwable p) throws M68kException {
		if(p instanceof M68kException)throw (M68kException)p;
		if(p instanceof RuntimeException)throw (RuntimeException)p;
		if(p instanceof Error)throw (Error)p;
		throw new IllegalStateException(p);
	}
	private Context capture(M68kCpu cpu){
		Context c=new Context();c.m68=cpu.saveContext();PpcCpu p=ppcCpu();if(p!=null)c.ppc=p.saveContext();c.trapDepth=tb.api.trapDepth();return c;
	}
	private void install(M68kCpu cpu,Context c) throws M68kCpuException {
		cpu.restoreContext(c.m68);PpcCpu p=ppcCpu();if(p!=null&&c.ppc!=null)p.restoreContext(c.ppc);tb.api.setTrapDepth(c.trapDepth);
	}

	/** Gives the baton to {@code to} and, with {@code wait}, parks until {@code self} holds it again. */
	private void handOff(ThreadRecord self,ThreadRecord to,boolean wait){
		synchronized(baton){holder=to;baton.notifyAll();if(wait)awaitBaton(self);}
	}
	/** Waits (holding the monitor) for the baton. An interrupt - a force quit of a program whose thread is parked here - aborts
	 *  the emulation and reaches the running carrier: its failure then comes back here as a pending exception. */
	private void awaitBaton(ThreadRecord self){
		boolean interrupted=false;
		while(holder!=self){
			try{baton.wait();}
			catch(InterruptedException e){
				interrupted=true;
				if(tb.cpu!=null)tb.cpu.requestAbort();
				Thread running=holder==null?null:holder.javaThread;if(running!=null&&running!=Thread.currentThread())running.interrupt();
			}
		}
		if(interrupted)Thread.currentThread().interrupt();
	}

	/** Makes the carrier of a thread that has never run; it waits for the baton, then calls the entry on a fresh stack. */
	private void startCarrier(final M68kCpu cpu,final ThreadRecord t) throws M68kException {
		t.started=true;
		final Context start=capture(cpu);
		for(int i=0;i<8;i++){start.m68.d[i]=0;if(i<5||i==6)start.m68.a[i]=0;} // A5 (application globals) carries over
		start.m68.a[7]=(t.stack+t.stackSize-32)&~3;start.m68.nesting=0;start.m68.sentinelSlots=new int[]{start.m68.a[7]};
		if(start.ppc!=null){
			if(t.ppcStack==0){t.ppcStackSize=Math.max(Math.max(PPC_STACK_MIN,t.stackSize),t.ppcStackSize);t.ppcStack=memory.allocate(t.ppcStackSize,16);if(t.ppcStack==0)throw new M68kCpuException(cpu.pc,"no memory for a PowerPC thread stack");}
			int sp=(t.ppcStack+t.ppcStackSize-64)&~15;memory.writeLong(sp,0); // the back chain ends here
			start.ppc=PpcCpu.startContext(start.ppc,sp);
		}
		start.trapDepth=0; // the entry is program code, outside any trap
		Thread j=new Thread(new Runnable(){@Override public void run(){runCarrier(cpu,t,start);}},(t.mp?"MP task ":"Thread Manager thread ")+t.id+(tb.app!=null?": "+tb.app.name:""));
		j.setDaemon(true);t.javaThread=j;t.carrier=true;carriers.add(j);j.start();
	}

	/** A carrier's life: the thread's entry from the bottom of its own stack, then the hand-off to the next thread for good. */
	private void runCarrier(M68kCpu cpu,ThreadRecord t,Context start){
		synchronized(baton){awaitBaton(t);}
		ThreadRecord next;
		try{
			try{
				Throwable p=t.pending;t.pending=null;
				if(p!=null)rethrow(p); // disposed before it ever ran
				install(cpu,start);
				if(t.mp){ // a Multiprocessing Services task: the body runs the entry and reports the end; then another task or the blue task
					t.body.run(cpu);
					if(Mac68kApi.TRACE)System.err.println("[68K] MP task "+t.id+" ends");
					t.state=STOPPED;
					next=pickTask(t);if(next==null)next=blue;
				}else{
					if(t.switchIn!=0)callSwitcher(cpu,t.switchIn,t.id,t.switchInParam);
					cpu.pushLong(0);cpu.pushLong(t.param); // pascal voidPtr entry(threadParam): the result slot, then the parameter
					cpu.callPascal(t.entry,4);
					int result=memory.readLong(cpu.a[7]);
					if(Mac68kApi.TRACE)System.err.println("[68K] thread "+t.id+" ends (result $"+Integer.toHexString(result)+")");
					if(t.resultPtr!=0)memory.writeLong(t.resultPtr,result);
					if(t.terminator!=0){cpu.pushLong(t.id);cpu.pushLong(t.terminatorParam);cpu.callPascal(t.terminator,8);}
					t.terminator=0;t.switchIn=0;t.switchOut=0;t.entry=0;t.state=STOPPED;
					next=pick(cpu,NO_THREAD);
					if(next==null)next=threads.get(APPLICATION_THREAD);
				}
				release(t,false);
				setCurrent(next);next.state=RUNNING;
				if(!next.started)startCarrier(cpu,next);
			}catch(Killed k){
				next=t.killReturn;t.killReturn=null;
				if(t.disposeSelf){t.disposeSelf=false;release(t,t.recycle);}
			}
		}catch(Throwable e){ // a crash, an abort or ExitToShell in this thread: the program's own thread takes it
			if(t.killReturn==shutdownRecord)next=shutdownRecord;
			else{
				ThreadRecord m=threads.get(APPLICATION_THREAD);
				if(Mac68kApi.TRACE)System.err.println("[68K] thread "+t.id+" stopped the program: "+e);
				m.pending=e;setCurrent(m);m.state=RUNNING;next=m;
			}
		}finally{carriers.remove(Thread.currentThread());t.carrier=false;t.javaThread=null;}
		handOff(t,next,false);
	}

	/** Ends {@code t} (its result and terminator are done): a suspended carrier is unwound, the running thread's own is unwound
	 *  from here - the baton then goes to the next thread. */
	private void finishCoroutine(M68kCpu cpu,ThreadRecord t,boolean recycle) throws M68kException {
		if(t!=current){
			if(t.carrier){t.pending=KILLED;t.killReturn=current;park(cpu,current,t);}
			release(t,recycle);
			return;
		}
		t.state=STOPPED;
		ThreadRecord next=pick(cpu,NO_THREAD);
		if(next==null){next=threads.get(APPLICATION_THREAD);if(next==null||next==t)throw new M68kCpuException(cpu.pc,"the last thread ended");}
		setCurrent(next);next.state=RUNNING;
		if(!next.started)startCarrier(cpu,next);
		t.killReturn=next;t.disposeSelf=true;t.recycle=recycle;
		throw KILLED;
	}
	/** Drops (or keeps for reuse) a thread whose carrier is gone. */
	private void release(ThreadRecord t,boolean recycle){
		t.started=false;t.context=null;t.pending=null;t.carrier=false;
		if(recycle){t.premade=true;t.state=STOPPED;return;}
		if(threads.remove(t.id)!=null&&t.mp)mpTasks--;
		if(t.stack!=0)memory.free(t.stack);t.stack=0;
		if(t.ppcStack!=0)memory.free(t.ppcStack);t.ppcStack=0;
	}

	// ------------------------------------------------------------------ Multiprocessing Services tasks

	/** The thread running now (the application's main thread is made on first use). */
	ThreadRecord running(M68kCpu cpu){return main(cpu);}

	/** A new MP task that runs {@code body} on a carrier of its own, with a PowerPC stack of at least {@code ppcStackSize};
	 *  it first runs when the blue task gives the tasks a turn. Null when tasks cannot be made (register-swap mode, no memory). */
	ThreadRecord newTask(M68kCpu cpu,int ppcStackSize,TaskBody body,boolean suspended){
		main(cpu);
		if(!coroutines())return null;
		ThreadRecord t=new ThreadRecord(nextId++);t.mp=true;t.body=body;t.suspended=suspended;t.fpu=true;
		t.stackSize=DEFAULT_STACK; // its 68K stack: Toolbox bridge calls and interrupt-level work that runs while it holds the processor
		try{t.stack=memory.allocate(t.stackSize,4);}catch(M68kCpuException e){return null;}
		if(t.stack==0)return null;
		t.ppcStackSize=Math.max(PPC_STACK_MIN,ppcStackSize);t.state=READY;t.started=false;
		threads.put(t.id,t);mpTasks++;
		if(Mac68kApi.TRACE)System.err.println("[68K] MP task "+t.id+" created"+(suspended?" (suspended)":""));
		return t;
	}
	/** Holds or releases an MP task (created suspended, stopped at an exception): a held task is never picked. */
	void setSuspended(ThreadRecord t,boolean suspended){t.suspended=suspended;}

	/** Whether {@code t} could run now: ready, or waiting and its condition holds, its deadline passed, or (the blue task)
	 *  work is queued for it. */
	private boolean runnable(ThreadRecord t){
		if(t.premade||t.suspended)return false;
		if(t.state==READY)return true;
		if(t.state!=WAITING||t.waitFor==null)return false;
		return t.waitFor.getAsBoolean()||t.waitDeadline!=FOREVER&&System.nanoTime()-t.waitDeadline>=0||t==blue&&mp!=null&&mp.blueWorkPending();
	}
	/** The next thread MP scheduling may run after {@code from}, round-robin: a runnable MP task or the blue task's current thread. */
	private ThreadRecord pickTask(ThreadRecord from){
		if(mp!=null)mp.fireTimers();
		List<ThreadRecord> all=new ArrayList<ThreadRecord>(threads.values());int at=Math.max(0,all.indexOf(from));
		for(int i=1;i<=all.size();i++){ThreadRecord t=all.get((at+i)%all.size());if(t!=from&&(t.mp||t==blue)&&runnable(t))return t;}
		return null;
	}
	/** An MP switch: no Thread Manager switcher procs run (they belong to switches between the blue task's own threads). */
	private void switchTask(M68kCpu cpu,ThreadRecord next) throws M68kException {
		ThreadRecord from=current;
		setCurrent(next);next.state=RUNNING;
		if(Mac68kApi.TRACE)System.err.println("[68K] MP switch "+from.id+" -> "+next.id+(next.started?"":" (starts)")+" at depth "+cpu.nestingDepth());
		if(!next.started)startCarrier(cpu,next);
		park(cpu,from,next);
	}

	/**
	 * A blocking Multiprocessing Services wait of the running thread (an MP task or the blue task): until {@code ready} holds
	 * (true) or {@code deadline} (System.nanoTime; {@link #FOREVER}) passes (false). Meanwhile other runnable tasks run; with
	 * none, real time passes. The blue task runs work queued for it (MPRemoteCall) while it waits. Throws {@link Killed}
	 * when the task is terminated while it waits.
	 */
	boolean waitFor(M68kCpu cpu,BooleanSupplier ready,long deadline) throws M68kException {
		ThreadRecord self=main(cpu);
		for(;;){
			if(mp!=null){mp.fireTimers();if(!self.mp&&mp.blueWorkPending())mp.runBlueWork(cpu);}
			if(ready.getAsBoolean())return true;
			if(deadline!=FOREVER&&System.nanoTime()-deadline>=0)return false;
			self.waitFor=ready;self.waitDeadline=deadline;self.state=WAITING;
			try{
				ThreadRecord next=coroutines()?pickTask(self):null;
				if(next!=null)switchTask(cpu,next);else idle(cpu,self,deadline);
			}finally{self.waitFor=null;self.state=RUNNING;}
		}
	}
	/** Nothing can run: lets real time pass until the earliest deadline (at most 20 ms), running the interrupt-level work
	 *  (Time Manager tasks, deferred tasks - they may signal what the tasks wait for); a force quit aborts. */
	private void idle(M68kCpu cpu,ThreadRecord self,long deadline) throws M68kException {
		long wake=deadline;
		for(ThreadRecord t:threads.values())if(t!=self&&t.state==WAITING&&!t.suspended&&(t.mp||t==blue))wake=Math.min(wake,t.waitDeadline);
		if(mp!=null)wake=Math.min(wake,mp.fireTimers());
		cpu.yielded();
		tb.runDeferred();tb.runTimers();tb.runPeriodic();
		if(cpu.abortRequested())throw new M68kAbortException(cpu.pc);
		long ms=wake==FOREVER?20:Math.min(20,Math.max(0,(wake-System.nanoTime()+999_999L)/1_000_000L));
		if(ms>0)try{Thread.sleep(ms);}catch(InterruptedException e){cpu.requestAbort();throw new M68kAbortException(cpu.pc);}
	}
	/** Whether an MP task could run now (for an event wait that should end early: see {@link #nextTaskDeadline}). */
	boolean taskRunnable(){
		if(mpTasks==0)return false;
		if(mp!=null)mp.fireTimers();
		for(ThreadRecord t:threads.values())if(t.mp&&t!=current&&runnable(t))return true;
		return false;
	}
	/** The earliest deadline of a waiting MP task or an armed timer (System.nanoTime), {@link #FOREVER} when none. */
	long nextTaskDeadline(){
		long wake=mp!=null?mp.fireTimers():FOREVER;
		for(ThreadRecord t:threads.values())if(t.mp&&t.state==WAITING&&!t.suspended)wake=Math.min(wake,t.waitDeadline);
		return wake;
	}

	/** MPYield: an MP task hands the processor to the next runnable task (or the blue task); the blue task gives the tasks a turn. */
	void yieldTask(M68kCpu cpu) throws M68kException {
		ThreadRecord self=main(cpu);
		if(!self.mp){runTasks(cpu);return;}
		if(!coroutines())return;
		ThreadRecord next=pickTask(self);if(next==null)return;
		self.state=READY;switchTask(cpu,next);
	}
	/** The blue task lets the runnable MP tasks run until they have all waited or yielded back to it; work they queued for
	 *  it (MPRemoteCall) runs, and they get another turn (a few rounds at most). */
	void runTasks(M68kCpu cpu) throws M68kException {
		ThreadRecord self=main(cpu);
		if(self.mp||!coroutines())return;
		if(mp!=null&&mp.blueWorkPending())mp.runBlueWork(cpu);
		for(int round=0;round<16&&mpTasks>0;round++){
			ThreadRecord next=pickTask(self);
			if(next==null)return;
			self.state=READY;switchTask(cpu,next);
			if(mp==null||!mp.blueWorkPending())return;
			mp.runBlueWork(cpu);
		}
	}

	/** MPExit (or the end of the running task by MPTerminateTask): the task's carrier unwinds from here, the processor goes on
	 *  to the next runnable task or the blue task. Never returns. */
	void exitTask(M68kCpu cpu) throws M68kException {
		ThreadRecord t=main(cpu);
		if(!t.mp)throw new IllegalStateException("MPExit of the application's task");
		t.state=STOPPED;
		ThreadRecord next=pickTask(t);if(next==null)next=blue;
		setCurrent(next);next.state=RUNNING;
		if(!next.started)startCarrier(cpu,next);
		t.killReturn=next;t.disposeSelf=true;t.recycle=false;
		throw KILLED;
	}
	/** MPTerminateTask of another task: a parked carrier is unwound (its Java stack ends), then the task is dropped. */
	void terminateTask(M68kCpu cpu,ThreadRecord t) throws M68kException {
		if(t==main(cpu)){exitTask(cpu);return;}
		if(!t.mp)throw new IllegalStateException("MPTerminateTask of the application's task");
		if(t.carrier){t.pending=KILLED;t.killReturn=current;park(cpu,current,t);}
		release(t,false);
	}

	/** The program ended: unwinds the carriers still parked (their Java threads end). Skipped when a carrier still runs the
	 *  emulator (a force quit that gave up waiting for it). */
	void shutDown(){
		synchronized(baton){if(holder!=null&&holder.carrier)return;}
		for(ThreadRecord t:new ArrayList<ThreadRecord>(threads.values())){
			if(!t.carrier||t.javaThread==null)continue;
			synchronized(baton){
				t.pending=KILLED;t.killReturn=shutdownRecord;holder=t;baton.notifyAll();
				long deadline=System.currentTimeMillis()+2000;
				try{while(holder==t){long left=deadline-System.currentTimeMillis();if(left<=0)return;baton.wait(left);}}
				catch(InterruptedException e){Thread.currentThread().interrupt();return;}
			}
		}
	}

	/** MOVE.W #$00FF,D0; _ThreadDispatch: where a thread's entry routine returns to. */
	private int exitStub() throws M68kCpuException {
		if(exitStub==0){exitStub=memory.allocate(8,2);memory.writeWord(exitStub,0x303c);memory.writeWord(exitStub+2,0x00ff);memory.writeWord(exitStub+4,0xabf2);memory.writeWord(exitStub+6,0x4e71);}
		return exitStub;
	}
}

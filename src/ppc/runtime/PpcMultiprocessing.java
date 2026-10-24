import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * Multiprocessing Services (Multiprocessing.h, MP API 2.3: MPLibrary, also exported by CarbonLib) for PowerPC programs, as
 * on a single-processor Mac OS 9 machine. Each MP task is a coroutine thread of {@link ThreadManager68k} with its own
 * PowerPC stack; the tasks and the application's own ("blue") task share the one processor cooperatively and
 * deterministically: a task runs when the blue task waits in an MP call, yields (MPYield) or calls the event traps
 * (WaitNextEvent, GetNextEvent, SystemTask), and hands the processor on when it waits, yields or ends. A task that never
 * waits starves the others, which a real preemptive system would not allow - programs written for MP always wait somewhere.
 * <p>
 * Blocking calls (queues, semaphores, event groups, critical regions, MPDelayUntil, MPRemoteCall) park the caller, the main
 * task too, until the condition holds or the timeout passes (Duration: 0 immediate, kDurationForever, positive
 * milliseconds, negative microseconds); timers fire at the scheduling points into a queue, a semaphore, event flags or a
 * notification. AbsoluteTime is UpTime's unit here: nanoseconds of System.nanoTime.
 * Object IDs are opaque numbers, never guest pointers.
 */
final class PpcMultiprocessing implements ThreadManager68k.MpHooks {
	// MacErrors.h
	static final int NO_ERR=0,PARAM_ERR=-50,ITERATION_END_ERR=-29275,PRIVILEGED_ERR=-29276,TASK_BLOCKED_ERR=-29291,DELETED_ERR=-29295,
		TIMEOUT_ERR=-29296,TASK_ABORTED_ERR=-29297,INSUFFICIENT_RESOURCES_ERR=-29298,INVALID_ID_ERR=-29299;
	static final int DURATION_FOREVER=0x7FFFFFFF;
	/** MPCreateTask options, MPArmTimer options, MPDisposeTaskException actions, MPOpaqueIDClass values. */
	static final int CREATE_SUSPENDED=1,CREATE_VALID_OPTIONS=7,PRESERVE_TIMER_ID=1,TIME_IS_DELTA=2,TIME_IS_DURATION=4,TASK_PROPAGATE=1;
	static final int OPAQUE_QUEUE=4,OPAQUE_SEMAPHORE=5,OPAQUE_EVENT=9;
	/** The one processor, the one process and address space (MPGetNextCpuID, MPTaskInfo). */
	static final int CPU_ID=0x4d504331,PROCESS_ID=0x4d505031,SPACE_ID=0x4d504131;
	/** MPLibrary_MajorVersion etc. of the header. */
	static final int MAJOR=2,MINOR=3,RELEASE=1,REVISION=1;

	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcMultiprocessing(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	// ------------------------------------------------------------------ objects

	/** Something an MP ID names. */
	private abstract static class Obj { int id;boolean deleted; }
	private static final class Task extends Obj {
		ThreadManager68k.ThreadRecord rec; // null: the application's task
		int entry,param,queue,term1,term2,weight=100,type,exceptionQueue,exceptionKind,blockedOn;
		boolean inException,ended;long created=System.nanoTime();
		final Map<Integer,Integer> storage=new HashMap<Integer,Integer>();
	}
	private static final class Queue extends Obj { final ArrayDeque<int[]> messages=new ArrayDeque<int[]>();int reserve; }
	private static final class Semaphore extends Obj { int value,max; }
	private static final class Region extends Obj { Task owner;int count; }
	private static final class Event extends Obj { int flags; }
	/** Where a notification or a timer goes: a queue message, a semaphore signal, event flags (and for a timer, a notification). */
	private static final class Target { int queue,p1,p2,p3,semaphore,event,flags,notification; }
	private static final class Notification extends Obj { final Target target=new Target(); }
	private static final class Timer extends Obj { final Target target=new Target();long deadline;boolean armed,preserve; }
	/** An MPRemoteCall waiting for the application's task. */
	private static final class Remote { final int proc,param;int result;boolean done;Remote(int proc,int param){this.proc=proc;this.param=param;} }

	private final Map<Integer,Obj> objects=new LinkedHashMap<Integer,Obj>();
	private final Map<ThreadManager68k.ThreadRecord,Task> byRecord=new HashMap<ThreadManager68k.ThreadRecord,Task>();
	private final List<Timer> timers=new ArrayList<Timer>();
	private final ArrayDeque<Remote> remotes=new ArrayDeque<Remote>();
	private final TreeSet<Integer> storageIndexes=new TreeSet<Integer>();
	/** Opaque IDs: distinct, never kInvalidID. */
	private int nextId=0x4d500010;
	private Task app;
	private int debuggerQueue,versionString;
	/** Aligned blocks of MPAllocateAligned: address -> {the block really allocated, the size asked for}. */
	private final Map<Integer,int[]> aligned=new HashMap<Integer,int[]>();

	private <T extends Obj> T add(T o){o.id=nextId++;objects.put(o.id,o);return o;}
	private <T extends Obj> T get(int id,Class<T> kind){Obj o=objects.get(id);return o!=null&&!o.deleted&&kind.isInstance(o)?kind.cast(o):null;}
	private void delete(Obj o){o.deleted=true;objects.remove(o.id);}

	private ThreadManager68k threads(){ThreadManager68k t=rt.toolbox.threads;if(t.mp==null)t.mp=this;return t;}
	private M68kCpu cpu68(){return rt.toolbox.cpu;}
	private Task app(){if(app==null){app=add(new Task());}return app;}
	/** The task running now: an MP task, or the application's task for any of its Thread Manager threads. */
	private Task current(){ThreadManager68k.ThreadRecord r=threads().running(cpu68());Task t=r.mp?byRecord.get(r):null;return t!=null?t:app();}

	/** The System.nanoTime deadline of a Duration from now. */
	static long deadline(int duration){
		if(duration==DURATION_FOREVER)return ThreadManager68k.FOREVER;
		long now=System.nanoTime();
		return duration>=0?now+duration*1_000_000L:now+(-(long)duration)*1_000L;
	}
	/** A blocking wait of the running task on object {@code id}: true when {@code ready} held before the timeout. */
	private boolean await(int id,BooleanSupplier ready,int duration) throws M68kException {
		Task me=current();me.blockedOn=id;
		try{return threads().waitFor(cpu68(),ready,deadline(duration));}finally{me.blockedOn=0;}
	}
	private long readTime(int p) throws M68kCpuException {return (long)memory.readLong(p)<<32|memory.readLong(p+4)&0xffffffffL;}
	private void writeTime(int p,long t) throws M68kCpuException {if(p!=0){memory.writeLong(p,(int)(t>>>32));memory.writeLong(p+4,(int)t);}}
	private void out(int p,int v) throws M68kCpuException {if(p!=0)memory.writeLong(p,v);}

	// ------------------------------------------------------------------ scheduling hooks

	@Override public long fireTimers(){
		if(timers.isEmpty())return ThreadManager68k.FOREVER;
		long now=System.nanoTime(),next=ThreadManager68k.FOREVER;
		for(Timer t:new ArrayList<Timer>(timers)){
			if(!t.armed)continue;
			if(now-t.deadline>=0){
				t.armed=false;
				if(!t.preserve){delete(t);timers.remove(t);} // without kMPPreserveTimerIDMask a timer is gone once it fired
				fire(t.target,0);
			}else next=Math.min(next,t.deadline);
		}
		return next;
	}
	@Override public boolean blueWorkPending(){return !remotes.isEmpty();}
	@Override public void runBlueWork(M68kCpu cpu) throws M68kException {
		Remote r;
		while((r=remotes.poll())!=null){r.result=rt.cpu.call(r.proc,r.param);r.done=true;}
	}

	/** Sends a notification: the message to its queue, a signal to its semaphore, its flags to its event group. */
	private void fire(Target t,int depth){
		Queue q=get(t.queue,Queue.class);if(q!=null)q.messages.add(new int[]{t.p1,t.p2,t.p3});
		Semaphore s=get(t.semaphore,Semaphore.class);if(s!=null&&Integer.compareUnsigned(s.value,s.max)<0)s.value++;
		Event e=get(t.event,Event.class);if(e!=null)e.flags|=t.flags;
		Notification n=get(t.notification,Notification.class);if(n!=null&&depth<4)fire(n.target,depth+1);
	}
	/** Points a notification target at {@code id} (MPModifyNotification, MPSetTimerNotify); false for an unknown ID. */
	private boolean aim(Target t,int id,int p1,int p2,int p3,boolean notifications){
		Obj o=objects.get(id);
		if(o instanceof Queue){t.queue=id;t.p1=p1;t.p2=p2;t.p3=p3;}
		else if(o instanceof Semaphore)t.semaphore=id;
		else if(o instanceof Event){t.event=id;t.flags=p1;}
		else if(o instanceof Notification&&notifications)t.notification=id;
		else return false;
		return true;
	}

	/** The end of an MP task: the termination message (parameter 1, parameter 2, the status) goes to its notify queue. */
	private void ended(Task t,int status){
		if(t.ended)return;
		t.ended=true;delete(t);byRecord.remove(t.rec);
		Queue q=get(t.queue,Queue.class);if(q!=null)q.messages.add(new int[]{t.term1,t.term2,status});
		if(PpcRuntime.TRACE)System.err.printf("[ppc] MP task %08X ends: status %d%n",t.id,status);
	}

	// ------------------------------------------------------------------ definitions

	void define(PpcLibraries l){
		defineTasks(l);defineSynchronization(l);defineTimers(l);defineMemory(l);defineDebugging(l);
		// library presence (MPLibraryIsLoaded() calls the weak import _MPIsFullyInitialized) and version
		l.register(null,"_MPIsFullyInitialized",(r,c)->r.ret(1));
		l.register(null,"_MPLibraryVersion",(r,c)->{ // (const char **versionCString, UInt32 *major, *minor, *release, *revision)
			if(versionString==0){byte[] b=(MAJOR+"."+MINOR+"\0").getBytes(StandardCharsets.ISO_8859_1);versionString=r.allocate(b.length,4);memory.load(versionString,b);}
			out(r.arg(0),versionString);out(r.arg(1),MAJOR);out(r.arg(2),MINOR);out(r.arg(3),RELEASE);out(r.arg(4),REVISION);});
		l.register(null,"_MPLibraryIsCompatible",(r,c)->{ // (versionCString, major, minor, release, revision): what the caller was built for is at most this version
			int major=r.arg(1),minor=r.arg(2),release=r.arg(3),revision=r.arg(4);
			boolean ok=major==MAJOR&&(minor<MINOR||minor==MINOR&&(release<RELEASE||release==RELEASE&&revision<=REVISION));
			r.ret(ok?1:0);});
		l.register(null,"MPProcessors",(r,c)->r.ret(1));
		l.register(null,"MPProcessorsScheduled",(r,c)->r.ret(1));
		l.register(null,"MPGetNextCpuID",(r,c)->{ // (owningCoherenceID, MPCpuID *cpuID): one processor
			int p=r.arg(1);if(p==0){r.ret(PARAM_ERR);return;}
			int now=memory.readLong(p);
			if(now==0){memory.writeLong(p,CPU_ID);r.ret(NO_ERR);}else r.ret(now==CPU_ID?ITERATION_END_ERR:INVALID_ID_ERR);});
		// DriverServices' time conversions, declared in Multiprocessing.h too (they replace PpcLibraries' AbsoluteToDuration,
		// which answered microseconds as a positive - millisecond - Duration)
		l.register(null,"DurationToAbsolute",(r,c)->{ // AbsoluteTime (Duration): the result by hidden pointer
			int d=r.arg(1);long ns=d==DURATION_FOREVER?Long.MAX_VALUE:d>=0?d*1_000_000L:-(long)d*1_000L;writeTime(r.arg(0),ns);r.ret(r.arg(0));});
		l.register(null,"AbsoluteToDuration",(r,c)->{ // Duration (AbsoluteTime by value: hi, lo): microseconds (negative) while they fit, else milliseconds
			long ns=(long)r.arg(0)<<32|r.arg(1)&0xffffffffL;
			if(ns<=0){r.ret(0);return;}
			long us=ns/1_000L,ms=ns/1_000_000L;
			r.ret(us<=Integer.MAX_VALUE?(int)-us:ms<DURATION_FOREVER?(int)ms:DURATION_FOREVER);});
	}

	private void defineTasks(PpcLibraries l){
		l.register(null,"MPCreateTask",(r,c)->{ // (entryPoint, parameter, stackSize, notifyQueue, terminationParameter1, terminationParameter2, options, MPTaskID *task)
			int entry=r.arg(0),param=r.arg(1),stackSize=r.arg(2),queue=r.arg(3),term1=r.arg(4),term2=r.arg(5),options=r.arg(6),taskPtr=r.arg(7);
			if(entry==0||(options&~CREATE_VALID_OPTIONS)!=0){r.ret(PARAM_ERR);return;}
			if(queue!=0&&get(queue,Queue.class)==null){r.ret(INVALID_ID_ERR);return;}
			app();
			final Task t=new Task();t.entry=entry;t.param=param;t.queue=queue;t.term1=term1;t.term2=term2;
			ThreadManager68k.ThreadRecord rec=threads().newTask(cpu68(),stackSize,cpu->{int result=rt.cpu.call(t.entry,t.param);ended(t,result);},(options&CREATE_SUSPENDED)!=0);
			if(rec==null){r.ret(INSUFFICIENT_RESOURCES_ERR);return;}
			add(t);t.rec=rec;byRecord.put(rec,t);
			if((options&CREATE_SUSPENDED)!=0)t.inException=true; // held until MPDisposeTaskException resumes it
			out(taskPtr,t.id);r.ret(NO_ERR);});
		l.register(null,"MPTerminateTask",(r,c)->{ // (task, terminationStatus)
			Task t=get(r.arg(0),Task.class);int status=r.arg(1);
			if(t==null){r.ret(INVALID_ID_ERR);return;}
			if(t.rec==null){r.ret(PRIVILEGED_ERR);return;} // the application's own task is not terminated this way
			if(t==current()){ended(t,status);threads().exitTask(cpu68());}
			threads().terminateTask(cpu68(),t.rec);ended(t,status);r.ret(NO_ERR);});
		l.register(null,"MPExit",(r,c)->{ // (status): the running task ends here
			Task t=current();
			if(t.rec==null){System.err.println("[ppc "+r.app.name+"] MPExit called by the application's task: ignored");return;}
			ended(t,r.arg(0));threads().exitTask(cpu68());});
		l.register(null,"MPYield",(r,c)->threads().yieldTask(cpu68()));
		l.register(null,"MPCurrentTaskID",(r,c)->r.ret(current().id));
		l.register(null,"MPSetTaskWeight",(r,c)->{Task t=get(r.arg(0),Task.class);if(t==null){r.ret(INVALID_ID_ERR);return;}t.weight=r.arg(1);r.ret(NO_ERR);}); // one processor: weights change nothing
		l.register(null,"MPTaskIsPreemptive",(r,c)->{ // (taskID; kInvalidID = the caller): MP tasks are (they must not call the Toolbox), the application's task is not
			int id=r.arg(0);Task t=id==0?current():get(id,Task.class);r.ret(t!=null&&t.rec!=null?1:0);});
		l.register(null,"_MPTaskIsToolboxSafe",(r,c)->{int id=r.arg(0);Task t=id==0?current():get(id,Task.class);r.ret(t!=null&&t.rec==null?1:0);});
		l.register(null,"MPSetTaskType",(r,c)->{Task t=get(r.arg(0),Task.class);if(t==null){r.ret(INVALID_ID_ERR);return;}t.type=r.arg(1);r.ret(NO_ERR);});
		l.register(null,"MPGetNextTaskID",(r,c)->{ // (owningProcessID, MPTaskID *taskID): the application's task, then the MP tasks
			int p=r.arg(1);if(p==0){r.ret(PARAM_ERR);return;}
			app();List<Integer> ids=new ArrayList<Integer>();for(Obj o:objects.values())if(o instanceof Task)ids.add(o.id);
			int now=memory.readLong(p);
			if(now==0){memory.writeLong(p,ids.get(0));r.ret(NO_ERR);return;}
			int at=ids.indexOf(now);
			if(at<0)r.ret(INVALID_ID_ERR);else if(at+1>=ids.size())r.ret(ITERATION_END_ERR);else{memory.writeLong(p,ids.get(at+1));r.ret(NO_ERR);}});
		// task storage: per-task values of process-wide indexes
		l.register(null,"MPAllocateTaskStorageIndex",(r,c)->{
			int p=r.arg(0);int index=1;while(storageIndexes.contains(index))index++;
			if(index>256){r.ret(INSUFFICIENT_RESOURCES_ERR);return;}
			storageIndexes.add(index);out(p,index);r.ret(NO_ERR);});
		l.register(null,"MPDeallocateTaskStorageIndex",(r,c)->{
			int index=r.arg(0);if(!storageIndexes.remove(index)){r.ret(PARAM_ERR);return;}
			for(Obj o:objects.values())if(o instanceof Task)((Task)o).storage.remove(index);
			r.ret(NO_ERR);});
		l.register(null,"MPSetTaskStorageValue",(r,c)->{int index=r.arg(0);if(!storageIndexes.contains(index)){r.ret(PARAM_ERR);return;}current().storage.put(index,r.arg(1));r.ret(NO_ERR);});
		l.register(null,"MPGetTaskStorageValue",(r,c)->{Integer v=current().storage.get(r.arg(0));r.ret(v==null?0:v);});
		// MPRemoteCall: the procedure runs on the application's task - directly when that is the caller, else the caller waits for it
		PpcRuntime.Native remote=(r,c)->{ // (remoteProc, parameter[, context])
			int proc=r.arg(0),param=r.arg(1);
			if(proc==0){r.ret(0);return;}
			if(current().rec==null){r.ret(rt.cpu.call(proc,param));return;}
			final Remote call=new Remote(proc,param);remotes.add(call);threads();
			threads().waitFor(cpu68(),()->call.done,ThreadManager68k.FOREVER);
			r.ret(call.result);};
		l.register(null,"MPRemoteCall",remote);
		l.register(null,"_MPRPC",remote);
	}

	private void defineSynchronization(PpcLibraries l){
		// queues
		l.register(null,"MPCreateQueue",(r,c)->{int p=r.arg(0);if(p==0){r.ret(PARAM_ERR);return;}out(p,add(new Queue()).id);r.ret(NO_ERR);});
		l.register(null,"MPDeleteQueue",(r,c)->{Queue q=get(r.arg(0),Queue.class);if(q==null){r.ret(INVALID_ID_ERR);return;}delete(q);r.ret(NO_ERR);}); // its waiters get kMPDeletedErr
		l.register(null,"MPNotifyQueue",(r,c)->{Queue q=get(r.arg(0),Queue.class);if(q==null){r.ret(INVALID_ID_ERR);return;}q.messages.add(new int[]{r.arg(1),r.arg(2),r.arg(3)});r.ret(NO_ERR);});
		l.register(null,"MPWaitOnQueue",(r,c)->{ // (queue, void **param1, **param2, **param3, timeout)
			int id=r.arg(0),p1=r.arg(1),p2=r.arg(2),p3=r.arg(3),timeout=r.arg(4);
			Queue q=get(id,Queue.class);if(q==null){r.ret(INVALID_ID_ERR);return;}
			boolean ok=await(id,()->q.deleted||!q.messages.isEmpty(),timeout);
			if(q.deleted){r.ret(DELETED_ERR);return;}
			if(!ok){r.ret(TIMEOUT_ERR);return;}
			int[] m=q.messages.poll();out(p1,m[0]);out(p2,m[1]);out(p3,m[2]);r.ret(NO_ERR);});
		l.register(null,"MPSetQueueReserve",(r,c)->{Queue q=get(r.arg(0),Queue.class);if(q==null){r.ret(INVALID_ID_ERR);return;}q.reserve=r.arg(1);r.ret(NO_ERR);}); // notifications never run out of room here
		// semaphores (MPCreateBinarySemaphore is MPCreateSemaphore(1, 1, s))
		l.register(null,"MPCreateSemaphore",(r,c)->{ // (maximumValue, initialValue, MPSemaphoreID *semaphore)
			int max=r.arg(0),initial=r.arg(1),p=r.arg(2);
			if(p==0||max==0||Integer.compareUnsigned(initial,max)>0){r.ret(PARAM_ERR);return;}
			Semaphore s=add(new Semaphore());s.max=max;s.value=initial;out(p,s.id);r.ret(NO_ERR);});
		l.register(null,"MPDeleteSemaphore",(r,c)->{Semaphore s=get(r.arg(0),Semaphore.class);if(s==null){r.ret(INVALID_ID_ERR);return;}delete(s);r.ret(NO_ERR);});
		l.register(null,"MPSignalSemaphore",(r,c)->{Semaphore s=get(r.arg(0),Semaphore.class);if(s==null){r.ret(INVALID_ID_ERR);return;}
			if(Integer.compareUnsigned(s.value,s.max)>=0){r.ret(INSUFFICIENT_RESOURCES_ERR);return;} // already at its maximum
			s.value++;r.ret(NO_ERR);});
		l.register(null,"MPWaitOnSemaphore",(r,c)->{ // (semaphore, timeout)
			int id=r.arg(0),timeout=r.arg(1);Semaphore s=get(id,Semaphore.class);if(s==null){r.ret(INVALID_ID_ERR);return;}
			boolean ok=await(id,()->s.deleted||s.value!=0,timeout);
			if(s.deleted){r.ret(DELETED_ERR);return;}
			if(!ok){r.ret(TIMEOUT_ERR);return;}
			s.value--;r.ret(NO_ERR);});
		// critical regions: recursive for the task that owns one (every Thread Manager thread is the application's task)
		l.register(null,"MPCreateCriticalRegion",(r,c)->{int p=r.arg(0);if(p==0){r.ret(PARAM_ERR);return;}out(p,add(new Region()).id);r.ret(NO_ERR);});
		l.register(null,"MPDeleteCriticalRegion",(r,c)->{Region g=get(r.arg(0),Region.class);if(g==null){r.ret(INVALID_ID_ERR);return;}delete(g);r.ret(NO_ERR);});
		l.register(null,"MPEnterCriticalRegion",(r,c)->{ // (criticalRegion, timeout)
			int id=r.arg(0),timeout=r.arg(1);Region g=get(id,Region.class);if(g==null){r.ret(INVALID_ID_ERR);return;}
			Task me=current();
			if(g.owner==me){g.count++;r.ret(NO_ERR);return;}
			boolean ok=await(id,()->g.deleted||g.owner==null||g.owner.ended,timeout);
			if(g.deleted){r.ret(DELETED_ERR);return;}
			if(!ok){r.ret(TIMEOUT_ERR);return;}
			g.owner=me;g.count=1;r.ret(NO_ERR);});
		l.register(null,"MPExitCriticalRegion",(r,c)->{
			Region g=get(r.arg(0),Region.class);if(g==null){r.ret(INVALID_ID_ERR);return;}
			if(g.owner!=current()){r.ret(INSUFFICIENT_RESOURCES_ERR);return;} // not the owner
			if(--g.count<=0){g.owner=null;g.count=0;}
			r.ret(NO_ERR);});
		// event groups: 32 flags, a wait takes (and clears) them all
		l.register(null,"MPCreateEvent",(r,c)->{int p=r.arg(0);if(p==0){r.ret(PARAM_ERR);return;}out(p,add(new Event()).id);r.ret(NO_ERR);});
		l.register(null,"MPDeleteEvent",(r,c)->{Event e=get(r.arg(0),Event.class);if(e==null){r.ret(INVALID_ID_ERR);return;}delete(e);r.ret(NO_ERR);});
		l.register(null,"MPSetEvent",(r,c)->{Event e=get(r.arg(0),Event.class);if(e==null){r.ret(INVALID_ID_ERR);return;}e.flags|=r.arg(1);r.ret(NO_ERR);});
		l.register(null,"MPWaitForEvent",(r,c)->{ // (event, MPEventFlags *flags, timeout)
			int id=r.arg(0),p=r.arg(1),timeout=r.arg(2);Event e=get(id,Event.class);if(e==null){r.ret(INVALID_ID_ERR);return;}
			boolean ok=await(id,()->e.deleted||e.flags!=0,timeout);
			if(e.deleted){r.ret(DELETED_ERR);return;}
			if(!ok){out(p,0);r.ret(TIMEOUT_ERR);return;}
			out(p,e.flags);e.flags=0;r.ret(NO_ERR);});
		// notifications: up to one queue (with its message), one semaphore and one event group (with its flags)
		l.register(null,"MPCreateNotification",(r,c)->{int p=r.arg(0);if(p==0){r.ret(PARAM_ERR);return;}out(p,add(new Notification()).id);r.ret(NO_ERR);});
		l.register(null,"MPDeleteNotification",(r,c)->{Notification n=get(r.arg(0),Notification.class);if(n==null){r.ret(INVALID_ID_ERR);return;}delete(n);r.ret(NO_ERR);});
		l.register(null,"MPModifyNotification",(r,c)->{ // (notificationID, anID, notifyParam1, notifyParam2, notifyParam3)
			Notification n=get(r.arg(0),Notification.class);if(n==null){r.ret(INVALID_ID_ERR);return;}
			r.ret(aim(n.target,r.arg(1),r.arg(2),r.arg(3),r.arg(4),false)?NO_ERR:INVALID_ID_ERR);});
		l.register(null,"MPModifyNotificationParameters",(r,c)->{ // (notificationID, kind, notifyParam1, notifyParam2, notifyParam3)
			Notification n=get(r.arg(0),Notification.class);if(n==null){r.ret(INVALID_ID_ERR);return;}
			switch(r.arg(1)){
				case OPAQUE_QUEUE:n.target.p1=r.arg(2);n.target.p2=r.arg(3);n.target.p3=r.arg(4);break;
				case OPAQUE_EVENT:n.target.flags=r.arg(2);break;
				case OPAQUE_SEMAPHORE:break; // a signal has no parameters
				default:r.ret(PARAM_ERR);return;
			}
			r.ret(NO_ERR);});
		l.register(null,"MPCauseNotification",(r,c)->{Notification n=get(r.arg(0),Notification.class);if(n==null){r.ret(INVALID_ID_ERR);return;}fire(n.target,0);r.ret(NO_ERR);});
	}

	private void defineTimers(PpcLibraries l){
		PpcRuntime.Native delay=(r,c)->{ // (AbsoluteTime *expirationTime)
			int p=r.arg(0);if(p==0){r.ret(PARAM_ERR);return;}
			long until=readTime(p);
			threads().waitFor(cpu68(),()->false,until);r.ret(NO_ERR);};
		l.register(null,"MPDelayUntil",delay);
		l.register(null,"MPDelayUntilSys",delay);
		l.register(null,"MPCreateTimer",(r,c)->{int p=r.arg(0);if(p==0){r.ret(PARAM_ERR);return;}Timer t=add(new Timer());timers.add(t);out(p,t.id);threads();r.ret(NO_ERR);});
		l.register(null,"MPDeleteTimer",(r,c)->{Timer t=get(r.arg(0),Timer.class);if(t==null){r.ret(INVALID_ID_ERR);return;}delete(t);timers.remove(t);r.ret(NO_ERR);});
		l.register(null,"MPSetTimerNotify",(r,c)->{ // (timerID, anID, notifyParam1, notifyParam2, notifyParam3)
			Timer t=get(r.arg(0),Timer.class);if(t==null){r.ret(INVALID_ID_ERR);return;}
			r.ret(aim(t.target,r.arg(1),r.arg(2),r.arg(3),r.arg(4),true)?NO_ERR:INVALID_ID_ERR);});
		l.register(null,"MPArmTimer",(r,c)->{ // (timerID, AbsoluteTime *expirationTime, options)
			Timer t=get(r.arg(0),Timer.class);int p=r.arg(1),options=r.arg(2);
			if(t==null){r.ret(INVALID_ID_ERR);return;}
			if(p==0){r.ret(PARAM_ERR);return;}
			long when;
			if((options&TIME_IS_DURATION)!=0){int d=memory.readLong(p);when=d==DURATION_FOREVER?Long.MAX_VALUE/2:d>=0?d*1_000_000L:-(long)d*1_000L;} // a Duration in the time's first word
			else when=readTime(p);
			if((options&TIME_IS_DELTA)!=0)when+=System.nanoTime();
			t.deadline=when;t.preserve=(options&PRESERVE_TIMER_ID)!=0;t.armed=true;
			r.ret(NO_ERR);});
		l.register(null,"MPCancelTimer",(r,c)->{ // (timerID, AbsoluteTime *timeRemaining)
			Timer t=get(r.arg(0),Timer.class);int p=r.arg(1);
			if(t==null){r.ret(INVALID_ID_ERR);return;}
			fireTimers();
			if(!t.armed||t.deleted){writeTime(p,0);r.ret(INSUFFICIENT_RESOURCES_ERR);return;} // it already fired (or was never armed)
			t.armed=false;writeTime(p,Math.max(0,t.deadline-System.nanoTime()));r.ret(NO_ERR);});
	}

	private void defineMemory(PpcLibraries l){
		PpcRuntime.Native allocate=(r,c)->r.ret(allocate(r.arg(0),0));
		l.register(null,"MPAllocate",allocate);
		l.register(null,"_MPAllocateSys",allocate);
		l.register(null,"MPAllocateAligned",(r,c)->r.ret(allocate(r.arg(0),r.arg(1)&0xff))); // (size, alignment, options): blocks are always cleared
		l.register(null,"MPFree",(r,c)->{
			int p=r.arg(0);if(p==0)return;
			int[] a=aligned.remove(p);
			if(a!=null)memory.free(a[0]);else if(memory.isAllocated(p))memory.free(p);});
		l.register(null,"MPGetAllocatedBlockSize",(r,c)->{int p=r.arg(0);int[] a=aligned.get(p);int size=a!=null?a[1]:p!=0?memory.blockSize(p):0;r.ret(Math.max(0,size));});
		l.register(null,"MPBlockCopy",(r,c)->{int n=r.arg(2);if(n>0)memory.copy(r.arg(0),r.arg(1),n);}); // (source, destination, size): overlap is fine
		l.register(null,"MPBlockClear",(r,c)->{int n=r.arg(1);if(n>0)memory.fill(r.arg(0),n,0);});
		l.register(null,"MPDataToCode",(r,c)->{}); // no instruction cache to flush
	}
	/** MPAllocateAligned: {@code alignment} is log2 of the alignment (0 = default, 8 bytes; 254 = the page, 255 = interlocked
	 *  data); 0 when the block cannot be had. */
	private int allocate(int size,int alignment){
		if(size<0||size>1<<30)return 0; // kMPMaxAllocSize
		int log=alignment==0?3:alignment==254?12:alignment==255?5:alignment;
		if(log>16)return 0; // kMPAllocateMaxAlignment
		int align=1<<log;
		try{
			if(align<=4)return memory.allocate(Math.max(4,size),4); // zeroed by the allocator
			int raw=memory.allocate(Math.max(4,size)+align,4);if(raw==0)return 0;
			int p=(raw+align-1)&-align;aligned.put(p,new int[]{raw,size});return p;
		}catch(M68kCpuException e){return 0;}
	}

	private void defineDebugging(PpcLibraries l){
		l.register(null,"MPRegisterDebugger",(r,c)->{int q=r.arg(0);if(get(q,Queue.class)==null){r.ret(INVALID_ID_ERR);return;}debuggerQueue=q;r.ret(NO_ERR);}); // no task exceptions arise by themselves here
		l.register(null,"MPUnregisterDebugger",(r,c)->{int q=r.arg(0);if(q==0||q!=debuggerQueue){r.ret(INVALID_ID_ERR);return;}debuggerQueue=0;r.ret(NO_ERR);});
		l.register(null,"MPSetExceptionHandler",(r,c)->{ // (task, exceptionQ)
			Task t=get(r.arg(0),Task.class);int q=r.arg(1);
			if(t==null||q!=0&&get(q,Queue.class)==null){r.ret(INVALID_ID_ERR);return;}
			t.exceptionQueue=q;r.ret(NO_ERR);});
		l.register(null,"MPThrowException",(r,c)->{ // (task, kind): the task stops and its handler (or the debugger) gets {task, kind, 0}; unhandled, it is aborted
			Task t=get(r.arg(0),Task.class);int kind=r.arg(1);
			if(t==null){r.ret(INVALID_ID_ERR);return;}
			if(t.rec==null){r.ret(PRIVILEGED_ERR);return;}
			if(t.inException){r.ret(TASK_BLOCKED_ERR);return;}
			Queue handler=get(t.exceptionQueue,Queue.class);if(handler==null)handler=get(debuggerQueue,Queue.class);
			if(handler==null){abort(t);r.ret(NO_ERR);return;}
			t.inException=true;t.exceptionKind=kind;threads().setSuspended(t.rec,true);
			handler.messages.add(new int[]{t.id,kind,0});
			if(t==current())threads().waitFor(cpu68(),()->!t.inException,ThreadManager68k.FOREVER); // it stops right here until resumed
			r.ret(NO_ERR);});
		l.register(null,"MPDisposeTaskException",(r,c)->{ // (task, action): resume (step/branch tracing ignored) or propagate
			Task t=get(r.arg(0),Task.class);int action=r.arg(1);
			if(t==null){r.ret(INVALID_ID_ERR);return;}
			if(!t.inException){r.ret(INSUFFICIENT_RESOURCES_ERR);return;} // not stopped at an exception
			if((action&TASK_PROPAGATE)!=0){
				Queue debugger=get(debuggerQueue,Queue.class);
				if(debugger!=null&&t.exceptionQueue!=0&&t.exceptionQueue!=debuggerQueue){t.exceptionQueue=0;debugger.messages.add(new int[]{t.id,t.exceptionKind,0});r.ret(NO_ERR);return;}
				abort(t);r.ret(NO_ERR);return;
			}
			t.inException=false;t.exceptionKind=0;threads().setSuspended(t.rec,false);r.ret(NO_ERR);});
		l.register(null,"MPExtractTaskState",(r,c)->{ // (task, kind, info): only kMPTaskStateTaskInfo - no register sets are kept for a task
			Task t=get(r.arg(0),Task.class);int kind=r.arg(1),info=r.arg(2);
			if(t==null){r.ret(INVALID_ID_ERR);return;}
			if(kind!=5||info==0){r.ret(PARAM_ERR);return;}
			taskInfo(t,info);r.ret(NO_ERR);});
		l.register(null,"MPSetTaskState",(r,c)->{Task t=get(r.arg(0),Task.class);r.ret(t==null?INVALID_ID_ERR:PARAM_ERR);}); // register sets cannot be set; the task info is read-only
	}
	/** An unhandled exception: the task ends with kMPTaskAbortedErr. */
	private void abort(Task t) throws M68kException {
		if(t==current()){ended(t,TASK_ABORTED_ERR);threads().exitTask(cpu68());}
		threads().terminateTask(cpu68(),t.rec);ended(t,TASK_ABORTED_ERR);
	}
	/** MPTaskInfo version 3 (84 bytes). */
	private void taskInfo(Task t,int p) throws M68kCpuException {
		memory.fill(p,84,0);
		ThreadManager68k.ThreadRecord running=threads().running(cpu68());
		boolean isCurrent=t.rec==null?!running.mp:t.rec==running;
		int state=isCurrent?2:t.rec==null||t.inException||t.rec.state==ThreadManager68k.WAITING?0:1; // running, blocked, ready
		memory.writeLong(p,3);memory.writeLong(p+4,t.type);memory.writeWord(p+12,state);memory.writeLong(p+16,t.weight);memory.writeLong(p+20,PROCESS_ID);
		writeTime(p+40,t.created);memory.writeLong(p+60,CPU_ID);memory.writeLong(p+64,t.blockedOn);memory.writeLong(p+68,SPACE_ID);
		if(t.rec!=null&&t.rec.ppcStack!=0){memory.writeLong(p+72,t.rec.ppcStack);memory.writeLong(p+76,t.rec.ppcStack+t.rec.ppcStackSize);}
		if(isCurrent)memory.writeLong(p+80,rt.cpu.r[1]);
	}
}

import java.util.HashMap;
import java.util.Map;

/**
 * Entry point of the classic Macintosh API for both HyperCard externals and
 * hosted applications: the Memory Manager and a few register-based utilities
 * live here, everything else is served by the single shared {@link Toolbox68k}
 * (created automatically for externals, supplied by {@link MacApp68k} for
 * applications). Unsupported calls remain explicit errors.
 */
final class Mac68kApi implements M68kTrapHandler {
	private static final int NO_ERR=0;
	private static final int MEM_FULL_ERR=-108;
	private static final int PARAM_ERR=-50;
	private static final int NIL_HANDLE_ERR=-109,MEM_LOCKED_ERR=-117;
	private static final int SYSTEM_VERSION=0x0755;
	private final M68kMemory memory;
	private OStack stack;
	private final Map<Integer,Integer> pointerSizes=new HashMap<Integer,Integer>();
	/** Logical sizes of the live Handles; each entry made or changed also (re)writes the block header in M68kMemory.blockHeaders(24) mode. */
	private final Map<Integer,Integer> handleSizes=new HashMap<Integer,Integer>(){
		@Override public Integer put(Integer handle,Integer size){
			Integer old=super.put(handle,size);
			if(memory.blockHeaders||memory.blockHeaders24)try{memory.noteHandleBlock(handle,memory.physical(memory.readLong(handle)),size);}catch(M68kCpuException ignored){}
			return old;}
	};
	/** Live Memory Manager blocks (Handles and Ptrs): an external whose count grew over a call left state behind. */
	int liveBlockCount(){return handleSizes.size()+pointerSizes.size();}
	/** A system block made at start-up (ExpandMem) is a Ptr of {@code size} bytes for GetPtrSize/SetPtrSize/DisposePtr, as in the System heap. */
	void adoptPointer(int pointer,int size){pointerSizes.put(pointer,size);}
	/** Drops an adopted Ptr from the books (its owner frees the memory itself); whether it was one. */
	boolean forgetPointer(int pointer){return pointerSizes.remove(pointer)!=null;}
	/**
	 * Ptrs made while a private zone (InitZone) is the current zone are carved out of that zone's own memory, as on a real
	 * Mac: Flash 4's Windows layer keeps its atom tables in 2 KB zones and makes an atom of (Ptr - zone) / 2 | $C000, so a
	 * window class Ptr from the one host heap gave an atom that led nowhere, CreateWindowEx("MDICLIENT") failed and the
	 * program quit at start-up. First fit with an 8-byte header in [zone+52, bkLim); a Ptr that does not fit still comes
	 * from the host heap (the old behaviour). Handles stay in the host heap.
	 */
	private static final class ZoneHeap{final java.util.TreeMap<Integer,Integer> free=new java.util.TreeMap<Integer,Integer>();}
	private final Map<Integer,ZoneHeap> zoneHeaps=new HashMap<Integer,ZoneHeap>();
	/** Ptr in a zone heap -> {zone, block start, block length}. */
	private final Map<Integer,int[]> zonePointers=new HashMap<Integer,int[]>();
	/** InitZone made a fresh zone at {@code zone}: its old Ptrs are gone. */
	void resetZoneHeap(int zone){zoneHeaps.remove(zone);zonePointers.values().removeIf(z->z[0]==zone);}
	private int zoneAllocate(int zone,int size) throws M68kCpuException {
		ZoneHeap heap=zoneHeaps.get(zone);
		if(heap==null){int start=(zone+52+3)&~3,limit=memory.readLong(zone)&~3;if(limit-start<16)return 0;heap=new ZoneHeap();heap.free.put(start,limit-start);zoneHeaps.put(zone,heap);}
		int need=8+Math.max(4,(size+3)&~3);
		for(Map.Entry<Integer,Integer> e:heap.free.entrySet()){int at=e.getKey(),length=e.getValue();if(length<need)continue;
			heap.free.remove(at);int rest=length-need;if(rest>=16)heap.free.put(at+need,rest);else need=length;
			memory.writeLong(at,need);memory.writeLong(at+4,zone);memory.fill(at+8,need-8,0); // a believable header: block size, parent zone
			zonePointers.put(at+8,new int[]{zone,at,need});return at+8;}
		return 0;
	}
	private boolean zoneFree(int pointer){
		int[] z=zonePointers.remove(pointer);if(z==null)return false;ZoneHeap heap=zoneHeaps.get(z[0]);if(heap==null)return true;
		int at=z[1],length=z[2];
		Map.Entry<Integer,Integer> after=heap.free.ceilingEntry(at);if(after!=null&&after.getKey()==at+length){length+=after.getValue();heap.free.remove(after.getKey());}
		Map.Entry<Integer,Integer> before=heap.free.lowerEntry(at);if(before!=null&&before.getKey()+before.getValue()==at){at=before.getKey();length+=before.getValue();}
		heap.free.put(at,length);return true;
	}
	/** SetPtrSize of a zone Ptr: within its block, or into free space right after it; Ptrs do not move. */
	private int zoneResize(int pointer,int newSize) throws M68kCpuException {
		int[] z=zonePointers.get(pointer);ZoneHeap heap=zoneHeaps.get(z[0]);int need=8+Math.max(4,(newSize+3)&~3);
		if(need>z[2]){Integer next=heap==null?null:heap.free.get(z[1]+z[2]);if(next==null||z[2]+next<need){memErr(MEM_FULL_ERR);return MEM_FULL_ERR;}
			heap.free.remove(z[1]+z[2]);int rest=z[2]+next-need;if(rest>=16)heap.free.put(z[1]+need,rest);else need=z[2]+next;
			memory.fill(z[1]+z[2],need-z[2],0);z[2]=need;memory.writeLong(z[1],need);}
		pointerSizes.put(pointer,newSize);memErr(NO_ERR);return NO_ERR;
	}
	private final ToolUtils68k toolUtils;
	private final ScrapManager68k scrapManager;
	private final SoundManager68k soundManager;
	private final CursorManager68k cursorManager;
	private final Sane68k sane;
	private final QuickDraw68k quickDraw;
	private int memoryError=NO_ERR;
	/** The shared Toolbox: created for externals here, replaced by the application's one in {@link #setApplication}. */
	private Toolbox68k app;
	private final Map<Integer,Integer> handleStates=new HashMap<Integer,Integer>();

	Mac68kApi(M68kMemory memory) { this(memory,null); }

	Mac68kApi(M68kMemory memory,OStack stack) {
		this.memory=memory;this.stack=stack;toolUtils=new ToolUtils68k(memory);
		scrapManager=new ScrapManager68k(memory,this);
		soundManager=new SoundManager68k(memory,this);
		cursorManager=new CursorManager68k(memory,this);
		sane=new Sane68k(memory);
		quickDraw=new QuickDraw68k(memory,this);
		app=new Toolbox68k(this,stack);
	}

	void setApplication(Toolbox68k app){this.app=app;try{quickDraw.refreshMainDeviceBounds();}catch(M68kCpuException e){if(TRACE)System.err.println("[68K] main device refresh failed: "+e.getMessage());}}
	Toolbox68k application(){return app;}
	M68kMemory memory(){return memory;}
	OStack stack(){return stack;}
	/** The stack was closed and opened again while this external VM lived on (an external's own "go home"/"go back"). */
	void rebind(OStack reopened){stack=reopened;if(app!=null)app.stack=reopened;}
	/** The VM serves {@code other}, a different stack, from now on: its resources too (see ResourceManager68k.switchStack). */
	void rebindToStack(OStack other) throws M68kCpuException {rebind(other);if(app!=null&&app.resources!=null)app.resources.switchStack(other);}
	FileManager68k fileManager(){return app.files.fileManager;}
	QuickDraw68k quickDraw(){return quickDraw;}
	SoundManager68k soundManager(){return soundManager;}
	ScrapManager68k scrapManager(){return scrapManager;}
	int handleState(int handle){Integer state=handleStates.get(handle);int s=state==null?0:state;
		// the 24-bit Memory Manager keeps the lock bit in the master pointer's top byte, and programs of that time set it themselves.
		// In the 32-bit world bits above the address space are still the program's flags: QuickTime's segment loader marks the code
		// segment it is running with BSET #7 on the master pointer and masks addresses with $FFFFFF - a block a growing neighbour
		// moved out of the way (makeRoomAfter, growLocked) left it executing freed memory (MacSSH died while typing, at $0000000C).
		if(handle!=0)try{int mp=memory.readLong(handle);if(memory.physical(mp)!=0&&(memory.addressing24||memory.physical(mp)!=mp))s|=(mp>>>24)&0x80;}catch(M68kCpuException ignored){}
		return s;}
	void setHandleState(int handle,int state){handleStates.put(handle,state&0xff);}
	/** HSetRBit / HClrRBit (and DetachResource, which makes a resource a plain block): only the resource bit ($20) of the
	 *  handle's flags changes. A FutureBASIC program checks it before DEF DISPOSEH: 二郎の苦悩 stopped with "You cannot use DEF
	 *  DISPOSEH on a resource handle" on its detached 'BooK' resource. */
	void setResourceBit(int handle,boolean on){if(handle==0)return;Integer state=handleStates.get(handle);int s=state==null?0:state;s=on?s|0x20:s&~0x20;if(s==0)handleStates.remove(handle);else handleStates.put(handle,s);}
	/** RecoverHandle: finds the master pointer whose data block is {@code pointer} (flags in a master pointer's top byte aside). */
	int handleForPointer(int pointer){
		int target=memory.physical(pointer);
		for(Map.Entry<Integer,Integer> entry:handleSizes.entrySet()){try{if(memory.physical(memory.readLong(entry.getKey()))==target)return entry.getKey();}catch(M68kCpuException ignored){}}
		return 0;
	}
	/** Points a Handle at its moved block. A master pointer keeps the bits above the address space: programs written for the
	 *  24-bit Memory Manager keep flags there (Word 1.0 marks its own p-code Handles with $01). */
	private void moveMasterPointer(int handle,int data) throws M68kCpuException {
		int old=memory.readLong(handle);memory.writeLong(handle,old==0||data==0?data:(old^memory.physical(old))|data);
	}
	/**
	 * What a real heap does before failing a growing SetPtrSize: relocatable blocks behind the Ptr are moved out of its way. Here
	 * the block right after it is moved when it is the data of an unlocked Handle (its master pointer is updated), repeatedly
	 * while that frees enough room (QuickTime's music media handler grows small pointers 4 bytes at a time).
	 */
	private boolean makeRoomAfter(int pointer,int newSize) throws M68kCpuException {
		for(int moved=0;moved<8;moved++){
			int block=memory.blockSize(pointer);if(block<0)return false;
			int next=pointer+block;Integer free=memory.freeLength(next);if(free!=null)next+=free;
			if(memory.freeLength(pointer+block)!=null&&pointer+block+memory.freeLength(pointer+block)>=pointer+newSize+16)return true;
			int size=memory.blockSize(next);if(size<0)return false;
			int handle=handleForPointer(next);if(handle==0||(handleState(handle)&0x80)!=0||handleSize(handle)>65536||programMasterPointers.contains(handle))return false; // locked, big (pixel buffers) or the program's own: stay
			byte[] contents=memory.readBytes(next,handleSize(handle));
			// the copy must not land in the room being made (best fit likes the gap right behind the Ptr)
			int data=memory.allocateOutside(Math.max(1,contents.length),pointer,pointer+Math.max(4,(newSize+3)&~3)+16);if(data==0)return false;
			memory.load(data,contents);moveMasterPointer(handle,data);memory.free(next);
		}
		return false;
	}
	/**
	 * MoveHHi: an unlocked Handle's block goes to the top of the heap, out of the way of Ptrs that grow and of the blocks
	 * made later - here the highest free room under 16 MB (master pointers of 24-bit programs keep flags above that).
	 * MPW Shell MoveHHis every tool segment before locking it; left in place, a locked CODE block of the Pascal compiler
	 * sat right behind a Ptr the compiler grows, and SetPtrSize failed with "Fatal Memory Error! Error #-108".
	 */
	int moveHandleHigh(int handle) throws M68kCpuException {
		if(handle==0||!handleSizes.containsKey(handle)){memErr(NIL_HANDLE_ERR);return NIL_HANDLE_ERR;}
		if((handleState(handle)&0x80)!=0){memErr(MEM_LOCKED_ERR);return MEM_LOCKED_ERR;}
		int data=memory.physical(memory.readLong(handle)),size=handleSizes.get(handle);
		if(data==0||!memory.isAllocated(data)||programMasterPointers.contains(handle)){memErr(NO_ERR);return NO_ERR;}
		int moved=memory.allocateHighBelow(Math.max(1,size),data,0x1000000);
		if(moved!=0){memory.copy(data,moved,size);moveMasterPointer(handle,moved);memory.free(data);handleSizes.put(handle,size);}
		memErr(NO_ERR);return NO_ERR;
	}
	int setPtrSize(int pointer,int newSize) throws M68kCpuException {
		if(!pointerSizes.containsKey(pointer)||newSize<0){memErr(PARAM_ERR);return PARAM_ERR;}
		if(zonePointers.containsKey(pointer))return zoneResize(pointer,newSize);
		int block=memory.blockSize(pointer);
		if(block>=0&&newSize<=block){pointerSizes.put(pointer,newSize);memErr(NO_ERR);return NO_ERR;}
		int reserve=memory.reservedAfter(pointer);
		if(memory.growInPlace(pointer,newSize)||(makeRoomAfter(pointer,newSize)&&memory.growInPlace(pointer,newSize))){reservedForGrowth-=reserve-memory.reservedAfter(pointer);pointerSizes.put(pointer,newSize);memErr(NO_ERR);return NO_ERR;} // the real heap grows a Ptr into free space after it; some runtimes resize buffers this way
		if(TRACE){StringBuilder sb=new StringBuilder();int at=pointer+block,end=pointer+newSize+16;while(at<end){int bs=memory.blockSize(at);if(bs<0){Integer fr=memory.freeLength(at);if(fr==null){sb.append(String.format(" ?%X",at));break;}sb.append(String.format(" free:%X",fr));at+=fr;continue;}int h=handleForPointer(at);sb.append(String.format(" %X:%X%s",at,bs,h!=0?String.format(" h=%X st=%02X",h,handleState(h)):pointerSizes.containsKey(at)?" NewPtr":handleSizes.containsKey(at)?" master":" host"));at+=bs;}System.err.println("[68K] SetPtrSize $"+Integer.toHexString(pointer)+" "+block+" -> "+newSize+" blocked by"+sb);}
		memErr(MEM_FULL_ERR);return MEM_FULL_ERR;
	}
	/** Disposes a Handle and returns its storage to the heap. */
	void disposeHandleAndFree(int handle){
		adoptProgramHeader(handle); // a carved block is freed on its own
		Integer size=handleSizes.remove(handle);handleStates.remove(handle);programMasterPointers.remove(handle);memErr(NO_ERR);
		if(size==null||handle==0)return;
		if(app!=null){try{app.zoneRelease(handle);}catch(M68kCpuException ignored){}}
		if(app!=null&&app.menus!=null)app.menus.handleDisposed(handle); // a menu Handle the program disposes must not be remembered as a menu
		if(app!=null&&app.resources!=null)app.resources.handleDisposed(handle); // a resource map Handle the program replaced with a copy
		try{int data=memory.readLong(handle);if(data!=0&&memory.isAllocated(data))memory.free(data);}catch(M68kCpuException ignored){}
		releaseMasterPointer(handle);
	}

	/**
	 * Master pointers come from a LIFO free list, as in the real Memory Manager (a disposed master pointer heads the zone's
	 * free list and the next NewHandle/NewEmptyHandle takes it). Some projector-style applications rely on it:
	 * their XObject scan does LoadResource, ReleaseResource, then SetResLoad(false) + Get1IndResource, and keeps using the
	 * FIRST handle - which on a Mac is the empty handle the second lookup just made from the freed master pointer.
	 */
	private final java.util.ArrayDeque<Integer> freeMasterPointers=new java.util.ArrayDeque<Integer>();
	/** Master pointers not handed out yet, from runs of {@link #MASTER_POINTER_RUN} (MoreMasters' 64). */
	private final java.util.ArrayDeque<Integer> freshMasterPointers=new java.util.ArrayDeque<Integer>();
	private static final int MASTER_POINTER_RUN=64;
	private int allocateMasterPointer() throws M68kCpuException {
		while(!freeMasterPointers.isEmpty()){int h=freeMasterPointers.pop();if(memory.isAllocated(h)&&!handleSizes.containsKey(h))return h;}
		if(freshMasterPointers.isEmpty()){int run=memory.allocateRun(MASTER_POINTER_RUN,4);for(int i=MASTER_POINTER_RUN-1;i>=0;i--)freshMasterPointers.push(run+4*i);}
		return freshMasterPointers.pop();
	}
	private void releaseMasterPointer(int handle){
		if(!memory.isAllocated(handle))return;
		try{memory.writeLong(handle,0);}catch(M68kCpuException ignored){}
		if(freeMasterPointers.size()>=4096){memory.free(handle);return;}
		freeMasterPointers.push(handle);
	}

	int createHandle(byte[] contents) throws M68kCpuException {
		int data=memory.allocate(Math.max(1,contents.length),2),handle=allocateMasterPointer();
		memory.load(data,contents);memory.writeLong(handle,data);
		handleSizes.put(handle,contents.length);return handle;
	}

	/** A Handle whose block lies below {@code limit} when the heap has room there (see {@link M68kMemory#allocateBelow}). */
	int createHandleBelow(int size,int limit) throws M68kCpuException {
		int data=memory.allocateBelow(Math.max(1,size),2,limit),handle=allocateMasterPointer();
		memory.writeLong(handle,data);handleSizes.put(handle,Math.max(0,size));return handle;
	}

	int createEmptyHandle(int size) throws M68kCpuException {
		int handle=allocateMasterPointer();memory.writeLong(handle,0);handleSizes.put(handle,size);return handle;
	}

	void loadHandle(int handle,byte[] contents) throws M68kCpuException {
		int old=memory.readLong(handle);
		// The block stays where it is when it does not grow (as the Memory Manager's SetHandleSize keeps a shrinking block):
		// programs keep pointers into a Handle across Munger deletions - the Script Editor walks its CMNU menus, deleting the
		// command number after each item, with the item pointer held across each Munger; a moved block ended its loop after
		// the first item and the 編集 menu lost its items.
		int data=old!=0&&memory.isAllocated(old)?memory.reallocate(old,Math.max(1,contents.length)):memory.allocate(Math.max(1,contents.length),2);
		memory.load(data,contents);
		memory.writeLong(handle,data);handleSizes.put(handle,contents.length);
		if(app!=null&&old!=0&&old!=data&&memory.isAllocated(old))memory.free(old);
	}

	/** Resizes a Handle's block, keeping its contents and (when possible) its address. */
	void resizeHandle(int handle,int newSize) throws M68kCpuException {
		int old=memory.readLong(handle);
		int data=old!=0&&memory.isAllocated(old)?memory.reallocate(old,Math.max(1,newSize)):memory.allocate(Math.max(1,newSize),2);
		if(old!=0&&data!=old&&!memory.isAllocated(old)){}
		memory.writeLong(handle,data);handleSizes.put(handle,newSize);
	}

	/** Registers a Handle allocated by the XCMD host before the VM was created. */
	void registerExistingHandle(int handle,int size) throws M68kCpuException {
		if(handle==0||size<0)throw new M68kCpuException(handle,"invalid host Handle");
		memory.readLong(handle);handleSizes.put(handle,size);
	}

	/** -Dhz68k.trace=true logs every trap of hosted applications (name, pc, D0, A0, stack top); toggled at run time by the snapshot command file ("trace on|off"). */
	static volatile boolean TRACE=Boolean.getBoolean("hz68k.trace");
	/** -Dhz68k.tracexcmd=true also logs every trap of HyperCard externals (one-shot XCMD VMs), which -Dhz68k.trace leaves out. */
	static final boolean TRACE_XCMD=Boolean.getBoolean("hz68k.tracexcmd");

	@Override public int packageEntry(M68kCpu cpu,int opcode) throws M68kException {return app==null?0:app.packageEntry(opcode);}

	/** {@code -Dhz68k.slowtrap=MILLIS}: reports every trap, host gate and poll that takes at least that long, and code stretches
	 *  between traps of that length (where a game freezes; the [68K thread] prefix tells the program's VM from the QuickTime service). */
	static final long SLOW_TRAP_NANOS=Long.getLong("hz68k.slowtrap",0L)*1_000_000L;
	private long lastTrapEnd;
	/** Nesting of traps (a trap may run program code that calls traps): the outermost one switches the classic window records (QuickDraw68k.enterToolbox). */
	private int trapDepth;
	/** The trap nesting of the Java stack now running: each Thread Manager coroutine thread (ThreadManager68k) has its own.
	 *  Setting it enters or leaves the Toolbox state when the new depth is on the other side of zero. */
	int trapDepth(){return trapDepth;}
	void setTrapDepth(int depth) throws M68kCpuException {
		int was=trapDepth;trapDepth=depth;
		if(was==0&&depth>0)quickDraw.enterToolbox();else if(was>0&&depth==0)quickDraw.leaveToolbox();
	}
	@Override public boolean handle(M68kCpu cpu,int opcode,int instructionPc) throws M68kException {
		if(trapDepth++==0)quickDraw.enterToolbox();
		try{return timedTrap(cpu,opcode,instructionPc);}
		finally{if(--trapDepth==0)quickDraw.leaveToolbox();}
	}
	private boolean timedTrap(M68kCpu cpu,int opcode,int instructionPc) throws M68kException {
		if(SLOW_TRAP_NANOS<=0){try{return handleTrap(cpu,opcode,instructionPc);}finally{quickDraw.flushPictureSpool(cpu);}} // picture data recorded by this trap reaches the program's putPicProc
		long started=System.nanoTime();
		if(lastTrapEnd!=0&&started-lastTrapEnd>=SLOW_TRAP_NANOS)System.err.println("[68K "+Thread.currentThread().getName()+"] slow stretch of code before trap $"+Integer.toHexString(opcode)+" "+name(opcode)+" at pc $"+Integer.toHexString(instructionPc)+": "+(started-lastTrapEnd)/1_000_000+" ms");
		try{return handleTrap(cpu,opcode,instructionPc);}
		finally{quickDraw.flushPictureSpool(cpu);
			long end=System.nanoTime();lastTrapEnd=end;if(end-started>=SLOW_TRAP_NANOS)System.err.println("[68K "+Thread.currentThread().getName()+"] slow trap $"+Integer.toHexString(opcode)+" "+name(opcode)+" at pc $"+Integer.toHexString(instructionPc)+": "+(end-started)/1_000_000+" ms");}
	}
	/** Debug aid: -Dhz68k.backtrace=A916[,A914] prints the caller chain (return addresses along the A6 links) at those traps. */
	private static final java.util.Set<Integer> BACKTRACE_TRAPS=new java.util.HashSet<Integer>();
	/** With a trap given as A260:9, only calls with that D0.W; -Dhz68k.backtrace.dirid=n further keeps parameter blocks with ioDirID n. */
	private static final java.util.Map<Integer,Integer> BACKTRACE_D0=new java.util.HashMap<Integer,Integer>();
	private static final int BACKTRACE_DIRID=Integer.getInteger("hz68k.backtrace.dirid",0);
	static{for(String t:System.getProperty("hz68k.backtrace","").split(",")){t=t.trim();if(t.isEmpty())continue;String[] p=t.split(":");int trap=Integer.parseInt(p[0],16);BACKTRACE_TRAPS.add(trap);if(p.length>1)BACKTRACE_D0.put(trap,Integer.parseInt(p[1],16));}}
	private boolean handleTrap(M68kCpu cpu,int opcode,int instructionPc) throws M68kException {
		if(opcode==0xaafe){ // _MixedModeMagic: 68K code called a routine descriptor (PowerPC code of a PowerPC program)
			MacApp68k owner=app==null?null:app.app;
			if(owner!=null&&owner.ppc!=null)return owner.ppc.mixedModeTrap(cpu,instructionPc);
			// an external with PowerPC code (fat, or its 68K routine only answers that it needs a Power Macintosh): the PowerPC
			// code runs, unless the fragment of a fat one cannot be prepared - then its 68K routine below
			PpcRuntime external=owner==null&&app!=null?app.powerPcExternalAt(cpu,instructionPc):null;
			if(external!=null&&(!app.externalHas68kRoutine(instructionPc)||external.preparesExternal(instructionPc)))return external.mixedModeTrap(cpu,instructionPc);
			// a 68K process (or an external): a 'fat' code resource or a descriptor built for 68K code - its 68K routine runs
			// in place of the descriptor, as Mixed Mode on a 68K Mac with the Mixed Mode stub did
			int target=routineDescriptor68k(cpu,instructionPc);
			if(target!=0){cpu.pc=target;return true;}
			// PowerPC code only (68K programs see a Power Macintosh, Toolbox68k.seesPowerPC): the program gets a PowerPC runtime
			if(owner!=null&&PpcRuntime.isPowerPcDescriptor(memory,instructionPc)){owner.ppc=PpcRuntime.forCallsFrom68k(owner);return owner.ppc.mixedModeTrap(cpu,instructionPc);}
		}
		if(!BACKTRACE_TRAPS.isEmpty()&&BACKTRACE_TRAPS.contains(opcode)&&(!BACKTRACE_D0.containsKey(opcode)||(cpu.d[0]&0xffff)==BACKTRACE_D0.get(opcode))
			&&(BACKTRACE_DIRID==0||memory.readLong(cpu.a[0]+48)==BACKTRACE_DIRID)){StringBuilder sb=new StringBuilder();int a6=cpu.a[6];
			// a return address rewritten to a segment loader's trampoline (MOVEA.L d(PC),A1; SUBQ.W #8,A1: the System 7.5 Finder) is
			// replaced by the real one from the trampoline's own return stack, most recent first
			int trampolineStack=-1;
			try{for(int i=0;i<16&&a6!=0&&(a6&1)==0&&a6<memory.size()-8;i++){int ret=memory.readLong(a6+4);
				if(ret>0&&ret<memory.size()-8&&memory.readWord(ret)==0x227a&&memory.readWord(ret+4)==0x5149){
					if(trampolineStack<0)trampolineStack=memory.readLong(ret+2+(short)memory.readWord(ret+2));
					trampolineStack-=8;int real=memory.readLong(trampolineStack);sb.append(String.format(" (%06X)",ret));ret=real;}
				sb.append(String.format(" %06X",ret));if(ret>16&&ret<memory.size())sb.append('[').append(java.util.HexFormat.of().formatHex(memory.readBytes(ret-12,16))).append(']');a6=memory.readLong(a6);}}catch(M68kCpuException ignored){}
			System.err.printf("[backtrace] %04X at %06X:%s%n",opcode,instructionPc,sb);
			if(Boolean.getBoolean("hz68k.backtrace.objects")){a6=cpu.a[6];
				// the handles in A2-A4 of every frame (object-style programs keep their objects in Handles there), with 0xB0 bytes of each
				try{for(int f=0;f<6&&a6!=0&&(a6&1)==0&&a6<memory.size()-8;f++){for(int r=2;r<=4;r++){int h=r==2?cpu.a[2]:r==3?cpu.a[3]:cpu.a[4];if(f>0)break;
						if(h>0&&h<memory.size()-4&&(h&1)==0){int p=memory.readLong(h);if(p>0&&p<memory.size()-0xb0)System.err.printf("[backtrace] A%d=%08X -> %08X: %s%n",r,h,p,java.util.HexFormat.of().formatHex(memory.readBytes(p,0xb0)));}}
					a6=memory.readLong(a6);}}catch(M68kCpuException ignored){}}}
		if(TRACE&&(app.isApplication()||TRACE_XCMD)){System.err.printf("[trap] %06X %04X %s D0=%08X A0=%08X SP=%08X",instructionPc,opcode,name(opcode),cpu.d[0],cpu.a[0],cpu.a[7]);
			try{if((opcode&0x0800)!=0)System.err.printf(" [%08X %08X %08X]",memory.readLong(cpu.a[7]),memory.readLong(cpu.a[7]+4),memory.readLong(cpu.a[7]+8));}catch(M68kCpuException e){}
			boolean handled=app.handle(cpu,opcode,instructionPc);
			if(!handled)handled=handleMemoryManager(cpu,opcode); // results of the shared Memory Manager traps (NewPtr's A0, SetPtrSize's D0) are shown too
			System.err.printf(" -> D0=%08X A0=%08X SP=%08X%s%n",cpu.d[0],cpu.a[0],cpu.a[7],handled?"":" (unhandled)");
			return handled;}
		if(app.handle(cpu,opcode,instructionPc))return true;
		return handleMemoryManager(cpu,opcode);
	}

	/** The 68K routine of the routine descriptor at {@code rd} (ISA 0 record; a relative procDescriptor counts from the descriptor), or 0. */
	private int routineDescriptor68k(M68kCpu cpu,int rd) throws M68kCpuException {
		if(memory.readByte(rd+2)<5)return 0; // version
		int count=memory.readWord(rd+10)+1;
		int selectorInfo=memory.readByte(rd+9); // a dispatched descriptor: the 68K routine for the caller's selector (PpcRuntime.dispatchedRecord)
		int selector=selectorInfo==12?cpu.d[1]:selectorInfo==14?(short)memory.readWord(cpu.a[7]+4):cpu.d[0];
		boolean indexable=(memory.readByte(rd+3)&1)!=0;
		for(int i=0;i<count&&i<1024;i++){int r=rd+12+20*i;
			if((memory.readByte(r+5)&0x0f)!=0)continue;
			if(selectorInfo!=0&&(indexable?i!=selector:(short)memory.readLong(r+16)!=(short)selector)&&(memory.readWord(r+6)&0x10)==0)continue;
			int flags=memory.readWord(r+6),proc=memory.readLong(r+8);
			return (flags&1)!=0?rd+proc:proc;}
		return 0;
	}

	private boolean handleMemoryManager(M68kCpu cpu,int opcode) throws M68kException {
		if((opcode&0x0800)!=0) return handleToolbox(cpu,opcode);
		int trap=opcode&0xff;
		// OS trap option bits (SYS/CLEAR/IMMED) do not change the routine number.
		switch(trap) {
			case 0x1e:return newPtr(cpu,(opcode&0x400)!=0);
			case 0x1f:if(app!=null)app.zoneRelease(cpu.a[0]);if(pointerSizes.remove(cpu.a[0])!=null&&!zoneFree(cpu.a[0])){reservedForGrowth-=memory.reservedAfter(cpu.a[0]);memory.free(cpu.a[0]);if(zoneHeaps.containsKey(cpu.a[0]))resetZoneHeap(cpu.a[0]);}setMemoryResult(cpu,NO_ERR);return true;
			case 0x66:cpu.a[0]=newEmptyHandle();setMemoryResult(cpu,NO_ERR);return true; // NewEmptyHandle ($A166)
			// GetPtrSize/GetHandleSize(NIL): D0 = nilHandleErr, as the trap answers (a size of 0 kept NullSet's NoNulls looping on
			// Munger(h, offset, params[4]^, GetHandleSize(params[4])-1, ...) with its missing 4th parameter)
			case 0x21:if(cpu.a[0]==0){setMemoryResult(cpu,NIL_HANDLE_ERR);return true;}cpu.d[0]=sizeOf(pointerSizes,cpu.a[0]);return true;
			case 0x22:return newHandle(cpu,(opcode&0x400)!=0);
			case 0x23:disposeHandleAndFree(cpu.a[0]);setMemoryResult(cpu,NO_ERR);return true;
			case 0x24:return setHandleSize(cpu);
			case 0x25:if(cpu.a[0]==0){setMemoryResult(cpu,NIL_HANDLE_ERR);return true;}adoptProgramHeader(cpu.a[0]);cpu.d[0]=sizeOf(handleSizes,cpu.a[0]);return true;
			case 0x27:return reallocHandle(cpu);
			case 0x29:setHandleState(cpu.a[0],handleState(cpu.a[0])|0x80);return lockHandle(cpu);
			case 0x2a:setHandleState(cpu.a[0],handleState(cpu.a[0])&~0x80);setMemoryResult(cpu,NO_ERR);return true;
			case 0x49:setHandleState(cpu.a[0],handleState(cpu.a[0])|0x40);setMemoryResult(cpu,NO_ERR);return true;
			case 0x4a:setHandleState(cpu.a[0],handleState(cpu.a[0])&~0x40);setMemoryResult(cpu,NO_ERR);return true;
			case 0x2b:if(cpu.a[0]!=0)memory.writeLong(cpu.a[0],0);setMemoryResult(cpu,NO_ERR);return true;
			case 0x2c:case 0x36:setMemoryResult(cpu,NO_ERR);return true; // InitApplZone / MoreMasters
			case 0x2e:return blockMove(cpu);
			case 0x30:case 0x31: // OSEventAvail/GetOSEvent without an Event Manager: null event (D0 = -1)
				if(cpu.a[0]!=0)memory.fill(cpu.a[0],16,0);cpu.d[0]=-1;return true;
			case 0x40:setMemoryResult(cpu,cpu.d[0]<=guestAvailable(memory.availableBytes())?NO_ERR:MEM_FULL_ERR);return true;
			case 0x4c:cpu.d[0]=guestAvailable(memory.availableBytes());memErr(NO_ERR);return true;
			case 0x4d:setMemoryResult(cpu,NO_ERR);return true;
			case 0x50:return toolUtils.relString(cpu,opcode);
			case 0x55:if(!app.isApplication()||memory.addressing24)cpu.d[0]&=0x00ffffff;return true; // externals and 24-bit programs (MacApp68k.addressing24) run 24-bit, other applications 32-bit clean
			// StackSpace: the room between SP and the top of the application heap (HeapEnd), not the whole memory below SP. Apple
			// Dylan's 68K runtime sets its stack limit to ApplLimit (lowered by its 'mem!' stack size) + StackSpace at start-up;
			// SP - heapStart put that limit far above the stack and every program signalled "Dylan error type 12" (stack overflow).
			case 0x65:{int heapEnd=memory.readLong(0x114),floor=heapEnd>memory.heapStart()&&Integer.compareUnsigned(heapEnd,cpu.a[7])<0?heapEnd:memory.heapStart();
				cpu.d[0]=Math.max(0,cpu.a[7]-floor);return true;}
			default:return false;
		}
	}

	private boolean handleToolbox(M68kCpu cpu,int opcode) throws M68kException {
		if((opcode&0x0fff)==0x85d&&app!=null)app.timerPolled(cpu,memory.readLong(cpu.a[7]+4)); // BitTst(qType, 0): a program waiting for a Time Manager task to expire
		if(toolUtils.handle(cpu,opcode&0x0fff))return true;
		if(scrapManager.handle(cpu,opcode&0x0fff))return true;
		if(soundManager.handle(cpu,opcode&0x0fff))return true;
		if(cursorManager.handle(cpu,opcode&0x0fff))return true;
		if((opcode&0x0fff)==0x9e0)return munger(cpu);
		if((opcode&0x0fff)==0x9e1)return handToHand(cpu);
		if((opcode&0x0fff)==0x9eb)return sane.handle(cpu);
		if(quickDraw.handleWithCallbacks(cpu,opcode&0x0fff))return true;
		if(quickDraw.handle(cpu,opcode&0x0fff))return true;
		if((opcode&0x0fff)==0x9c8){soundManager.sysBeep();cpu.a[7]+=2;return true;}
		return false;
	}

	int tickCount() { return app.tickCount(); }
	boolean cursorHidden() {return cursorManager.hidden();}
	/** The Cursor Manager itself, for the low-memory cursor jump vectors ($800-$81C) that programs call directly. */
	CursorManager68k cursorManager() {return cursorManager;}
	int cursorHideLevel() {return cursorManager.hideLevel();}
	byte[] cursorData() {return cursorManager.cursor();}
	int cursorShieldRect() {return cursorManager.shieldRect();}
	int cursorShieldOffset() {return cursorManager.shieldOffset();}
	int currentQuickDrawPort() {return quickDraw.currentPortAddress();}
	void forgetQuickDrawPort(int port) throws M68kCpuException {quickDraw.forgetExternalPort(port);}

	/** NewPtr blocks of this size and up get growth room reserved after them (see {@link #ptrGrowthReserve}). */
	private static final int BIG_PTR=64*1024;
	/** Growth room to keep after a big nonrelocatable block. A real heap grows a Ptr in place after compaction has moved the
	 *  relocatable blocks behind it out of the way; this heap never moves blocks, so the room is set aside at NewPtr time instead
	 *  (HyperCard's card buffers are one Ptr, allocated for the 512x342 Home card and SetPtrSized when a larger card is shown -
	 *  memFullErr there makes it fall back to a 512x342 window with the Scroll palette). Capped so many big blocks cannot
	 *  reserve more than an eighth of the heap between them. */
	private int ptrGrowthReserve(int size){
		if(size<BIG_PTR)return 0;
		int reserve=(int)Math.min(4L*size,4L*1024*1024),budget=memory.heapEnd()/8-reservedForGrowth;
		if(reserve>budget)reserve=Math.max(0,budget);
		reservedForGrowth+=reserve;return reserve;
	}
	private int reservedForGrowth;

	/** Bytes of the heap kept back from a hosted program's own NewPtr/NewHandle/SetHandleSize: the host makes window pixel
	 *  buffers and other records there that a real Mac keeps outside the application partition (screen memory, system heap).
	 *  Painter 2.0 allocates 57 KB Handles until memFullErr, frees back to ~512 KB of FreeMem and then opens its palettes; their
	 *  window buffers used to find the heap empty ("out of 68K heap memory" in GetNewDialog). The window buffers already made
	 *  come out of the reserve: they are what it is for, and Painter re-trims its tiles to ~512 KB of FreeMem at every event,
	 *  so a document window's buffer made after that took the room its effects allocate from ("Insufficient Memory" for
	 *  Apply Lighting in a new 400x300 picture). */
	private static final int HOST_RESERVE=8*1024*1024;
	int hostReserve(){
		if(app==null||app.app==null||app.app.service)return 0;
		long room=(memory.heapEnd()-memory.heapStart())/8,reserve=Math.min(HOST_RESERVE,room);
		// a full-screen window's buffer comes out of the reserve too: Powershift (FileMaker takes all the rest) on a 2560x1378
		// 16-bit screen needed 7 MB in one block for its first window, with 9 MB left in all
		try{if(quickDraw!=null)reserve+=Math.min(quickDraw.screenBufferBytes(),room);}catch(M68kCpuException e){}
		return (int)Math.max(0,reserve-(quickDraw==null?0:quickDraw.windowBufferBytes()));
	}
	/** Free bytes as the program sees them (without {@link #hostReserve}). */
	int guestAvailable(int bytes){return Math.max(0,bytes-hostReserve());}
	void checkGuestRoom(int size) throws M68kCpuException {
		int reserve=hostReserve();
		if(reserve>0&&size>0&&(long)memory.availableBytes()-size<reserve)throw new M68kCpuException(memory.heapEnd(),"program heap full");
	}

	private boolean newPtr(M68kCpu cpu,boolean system) throws M68kCpuException {
		int size=cpu.d[0];
		if(size<0){negativeSize(cpu,size);return true;}
		try {
			int zone=system||app==null?0:app.currentPrivateZone(),pointer=zone==0?0:zoneAllocate(zone,size);
			if(pointer==0){checkGuestRoom(size);pointer=memory.allocateReserving(Math.max(1,size),2,ptrGrowthReserve(size));}
			pointerSizes.put(pointer,Math.max(0,size));if(app!=null)app.zoneCharge(pointer,size,false);
			cpu.a[0]=pointer; setMemoryResult(cpu,NO_ERR);
		} catch(M68kCpuException e) { cpu.a[0]=0;setMemoryResult(cpu,MEM_FULL_ERR); }
		return true;
	}

	/** {@code system}: NewHandle,SYS - the system heap, which lies low in memory. The zones say heapType 0 (24-bit), and
	 *  QuickTime 4's code loader (QuickTime Effects' 'dslv' dissolve, MetaCard 2.5's visual effects) allocates its code
	 *  Handles this way and calls them through addresses masked with $FFFFFF: a block above 16 MB ran whatever lay at the
	 *  masked address (crashes in MetaCard's own code, or in data). */
	private boolean newHandle(M68kCpu cpu,boolean system) throws M68kCpuException {
		int size=cpu.d[0];
		if(size<0){negativeSize(cpu,size);return true;}
		try {
			checkGuestRoom(size);
			int data=system&&!memory.addressing24?memory.allocateBelow(Math.max(1,size),2,M68kMemory.LOW_LIMIT):memory.allocate(Math.max(1,size),2),handle=allocateMasterPointer();
			memory.writeLong(handle,data);handleSizes.put(handle,Math.max(0,size));if(app!=null)app.zoneCharge(handle,size,true);
			cpu.a[0]=handle;setMemoryResult(cpu,NO_ERR);
		} catch(M68kCpuException e) {cpu.a[0]=0;setMemoryResult(cpu,MEM_FULL_ERR);}
		return true;
	}

	/**
	 * NewPtr/NewHandle of a negative size (above 2 GB unsigned): memFullErr and nil, as the Memory Manager answers. Such a size
	 * is nearly always 16-bit arithmetic that wrapped - Zap'T'Balls sizes its screen-wide offscreen with
	 * muls.w width*pixelSize, which goes negative once that passes 32767 (wider than 1023 pixels at 32 bits, 2047 at 16) -
	 * so on a deep screen the program is restarted at 8 bits (MacApp68k.negativeAllocation).
	 */
	private void negativeSize(M68kCpu cpu,int size) throws M68kCpuException {
		cpu.a[0]=0;setMemoryResult(cpu,MEM_FULL_ERR);
		MacApp68k hosted=app==null?null:app.app;if(hosted!=null)hosted.negativeAllocation(size);
	}

	/** A Handle of {@code size} bytes made by the host (an xSetPropEvt value for an external window). */
	int newHandleBlock(int size) throws M68kCpuException {
		int data=memory.allocate(Math.max(1,size),2),handle=allocateMasterPointer();
		memory.writeLong(handle,data);handleSizes.put(handle,Math.max(0,size));return handle;
	}

	private int newEmptyHandle() throws M68kCpuException {
		int handle=allocateMasterPointer();memory.writeLong(handle,0);handleSizes.put(handle,0);memErr(NO_ERR);return handle;
	}

	/** Native disposal of a Handle: the same as the program's DisposeHandle, storage included. (Until Sept 2026 this only
	 *  forgot the Handle - a leftover from the XCMD days when the heap had no free() and every block died with the XCMD;
	 *  hosted applications run for hours, and a leaked screen buffer per SetDepth ran Speedometer out of heap.) */
	void disposeHandle(int handle) { disposeHandleAndFree(handle); }
	int handleSize(int handle) {adoptProgramHeader(handle);Integer size=handleSizes.get(handle);return size==null?0:size;}
	/**
	 * Handles a program carved out of another Handle's block (only with M68kMemory.blockTags: THINK C 5 / THINK Pascal).
	 * THINK C saves a resource over 32K as 32K pieces by cutting the tail of its Handle into a new one in place:
	 * NewHandle(0) + EmptyHandle, the new master pointer set into the old block by hand, the data moved up 8 bytes and both
	 * 24-bit headers written (physical size, tag $80 + size correction). The Memory Manager's books are on the Java side
	 * here, so the new Handle looked empty (its 'ZONE' piece was saved with 0 bytes and the next compile stopped with
	 * internal error $270A). Such a Handle - empty in the books, yet its master pointer set to an address inside an
	 * allocated block - is taken over from the header the program wrote: size = physical - 8 - correction; the block is
	 * cut in two there, and the Handle owning the first part keeps what lies before the new header.
	 * Only that state is looked at: the bytes before an ordinary block may hold an old header the program wrote.
	 */
	private void adoptProgramHeader(int handle){
		if(!memory.blockTags||handle==0)return;
		Integer known=handleSizes.get(handle);if(known==null||known!=0)return;
		try{
			int pointer=memory.physical(memory.readLong(handle));if(pointer<8||memory.isAllocated(pointer))return;
			int header=memory.readLong(pointer-8),physical=header&0xffffff;
			if((header>>>28)!=8||physical<8)return; // a relocatable block's header (tag %10)
			int owner=memory.blockContaining(pointer);
			int logical=physical-8-((header>>>24)&0xf);if(logical<0)return;
			// the data must lie in the block; the physical size THINK C writes may reach a few bytes past it (its size
			// correction assumes the real heap's rounding)
			if(owner<0||pointer+logical>owner+memory.blockSize(owner))return;
			int ownerHandle=handleForPointer(owner);
			if(!memory.splitBlock(owner,pointer))return;
			int end=(pointer+logical+3)&~3;if(memory.blockSize(pointer)>end-pointer+8)memory.splitBlock(pointer,end+8); // the rest: the next piece carved
			handleSizes.put(handle,logical);
			if(ownerHandle!=0&&handleSizes.containsKey(ownerHandle))handleSizes.put(ownerHandle,Math.max(0,pointer-8-owner));
			// both headers go back to "in use, size 0" (M68kMemory.blockTags): with the sizes the program wrote, its next
			// append of the two (it reloads the pieces right after saving) would merge them in place behind the books' back
			memory.writeLong(pointer-8,header&0xff000000);
			int ownerHeader=memory.readLong(owner-8);if((ownerHeader>>>28)==8)memory.writeLong(owner-8,ownerHeader&0xff000000);
			if(TRACE)System.err.println(String.format("[68K] Handle $%X carved out of $%X's block: %d bytes (that one keeps %d)",handle,ownerHandle,logical,pointer-8-owner));
		}catch(M68kCpuException ignored){}
	}
	/** Whether {@code handle} is a live Handle (allocated and not yet disposed). */
	boolean isHandle(int handle){return handle!=0&&handleSizes.containsKey(handle);}
	byte[] handleContents(int handle) throws M68kCpuException {adoptProgramHeader(handle);Integer size=handleSizes.get(handle);if(size==null||handle==0)return null;int pointer=memory.readLong(handle);if(pointer==0)return null;return memory.readBytes(pointer,size);}
	int detachedResource(int type,int id) throws M68kCpuException {return app.resources.detachedResource(type,id);}

	/** Grows a locked Handle's block in place by moving the unlocked relocatable blocks right after it elsewhere, as the
	 *  Memory Manager does; -1 when a block that cannot move (a pointer, a locked Handle) is in the way. */
	static final boolean DEBUG_GROW=Boolean.getBoolean("hz68k.debuggrow");
	private int growLocked(int address,int newSize) throws M68kCpuException {
		int needed=Math.max(4,(newSize+3)&~3);
		for(int moved=0;moved<256;moved++){
			int grown=memory.reallocate(address,Math.max(1,newSize),false);if(grown>=0)return grown;
			// walk past free space to the first allocated block in the way; a relocatable (unlocked) Handle's block moves
			int next=address+memory.blockSize(address),free;
			while((free=memory.freeBlockSize(next))>0)next+=free;
			if(next-address>=needed+64*1024||!memory.isAllocated(next)){if(DEBUG_GROW)System.err.println("growLocked: nothing to move after $"+Integer.toHexString(address));return -1;}
			int owner=handleForPointer(next);
			if(owner==0||(handleState(owner)&0x80)!=0||programMasterPointers.contains(owner)){if(DEBUG_GROW)System.err.println("growLocked: $"+Integer.toHexString(next)+" size "+memory.blockSize(next)+" owner $"+Integer.toHexString(owner)+" after $"+Integer.toHexString(address)+" moved "+moved);return -1;}
			// the copy must not land in the room being made (best fit likes the gap right behind the block)
			int nextSize=Math.max(1,handleSizes.get(owner)),copy=memory.allocateOutside(nextSize,address,address+needed+8);
			if(copy==0){if(DEBUG_GROW)System.err.println("growLocked: no room outside");return -1;}
			if(DEBUG_GROW)System.err.println("growLocked: moved $"+Integer.toHexString(next)+" -> $"+Integer.toHexString(copy)+" for $"+Integer.toHexString(address)+" size "+memory.blockSize(address)+" free after "+memory.freeBlockSize(address+memory.blockSize(address)));
			memory.copy(next,copy,nextSize);moveMasterPointer(owner,copy);memory.free(next);
		}
		return -1;
	}

	private boolean setHandleSize(M68kCpu cpu) throws M68kCpuException {setMemoryResult(cpu,setHandleSize(cpu.a[0],cpu.d[0]));return true;}
	/** SetHandleSize: noErr, paramErr or memFullErr (also for host code: {@link LaunchHookBridge68k} copying a Handle back). */
	int setHandleSize(int handle,int newSize) throws M68kCpuException {
		adoptProgramHeader(handle);
		if(handle==0||newSize<0||!handleSizes.containsKey(handle))return PARAM_ERR;
		try {
			int oldPointer=memory.readLong(handle),oldSize=handleSizes.get(handle);
			int newPointer;
			// a locked block grows only in place: the Memory Manager answers memFullErr rather than move it (programs keep
			// pointers into locked Handles; THINK Pascal crashed when one moved)
			boolean locked=(handleState(handle)&0x80)!=0;
			if(newSize>oldSize)checkGuestRoom(newSize-oldSize);
			if(oldPointer!=0&&memory.isAllocated(oldPointer)){newPointer=memory.reallocate(oldPointer,Math.max(1,newSize),!locked);if(newPointer<0)newPointer=growLocked(oldPointer,newSize);if(newPointer<0)return MEM_FULL_ERR;}
			else{newPointer=memory.allocate(Math.max(1,newSize),2);if(oldPointer!=0)memory.copy(oldPointer,newPointer,Math.min(oldSize,newSize));}
			moveMasterPointer(handle,newPointer);handleSizes.put(handle,newSize);return NO_ERR;
		}catch(M68kCpuException e){return MEM_FULL_ERR;}
	}

	/**
	 * Handles whose master pointers the program keeps itself (made by {@link #reallocHandle}). Their blocks are not moved to make
	 * room for a growing neighbour: Word 1.0 runs its p-code interpreter inside such unlocked segments and grows the
	 * interpreter's Ptr right before one (SetPtrSize from p-code); a move left the interpreter executing the old place.
	 */
	private final java.util.Set<Integer> programMasterPointers=new java.util.HashSet<Integer>();
	/**
	 * ReallocHandle(h, size): the ROM only needs a longword holding a NIL master pointer - it allocates a block and stores its
	 * address there, whether or not h came from NewHandle (Word 1.0 keeps such handles inside its CODE segment). A longword
	 * not known here becomes a handle that way; the rest behaves like SetHandleSize.
	 */
	private boolean reallocHandle(M68kCpu cpu) throws M68kCpuException {
		int handle=cpu.a[0],newSize=cpu.d[0];
		if(handle!=0&&newSize>=0&&!handleSizes.containsKey(handle)&&memory.readLong(handle)==0){
			try{checkGuestRoom(newSize);int data=memory.allocate(Math.max(1,newSize),2);memory.writeLong(handle,data);handleSizes.put(handle,newSize);programMasterPointers.add(handle);setMemoryResult(cpu,NO_ERR);}
			catch(M68kCpuException e){setMemoryResult(cpu,MEM_FULL_ERR);}
			return true;}
		return setHandleSize(cpu);
	}

	private boolean lockHandle(M68kCpu cpu) throws M68kCpuException {
		int handle=cpu.a[0];if(handle!=0&&memory.readLong(handle)==0&&app.resources.loadIfResource(handle)) {setMemoryResult(cpu,NO_ERR);return true;}
		setMemoryResult(cpu,handleSizes.containsKey(handle)?NO_ERR:handle==0?NIL_HANDLE_ERR:PARAM_ERR);return true; // HLock(NIL): nilHandleErr
	}

	/** Implements Munger's byte-oriented find, replace, insert, delete, and find-only modes. */
	private boolean munger(M68kCpu cpu) throws M68kCpuException {
		// Munger(h, offset, ptr1, len1, ptr2, len2): Pascal, so the Handle was pushed first and len2 sits on top of the stack
		int arguments=cpu.a[7],replacementLength=memory.readLong(arguments),replacement=memory.readLong(arguments+4);
		int targetLength=memory.readLong(arguments+8),target=memory.readLong(arguments+12);
		int offset=memory.readLong(arguments+16),handle=memory.readLong(arguments+20);cpu.a[7]+=24;
		int result=-1;
		if(handle!=0&&handleSizes.containsKey(handle)&&offset>=0&&replacementLength>=0) {
			byte[] contents=handleContents(handle);int size=contents==null?0:contents.length;
			if(contents==null&&handleSizes.get(handle)!=0) {memory.writeLong(cpu.a[7],result);return true;}
			if(contents==null)contents=new byte[0];
			if(offset<=size) {
				int found=offset,replacedLength=targetLength;
				// ptr1 NIL: len1 bytes from offset, clipped at the end of the Handle (List XFCN's replaceListLine replaces past the end
				// of a list it has just grown and expects the new line there: Munger used to refuse and drop it)
				if(target==0) {if(targetLength<0||targetLength>size-offset)replacedLength=size-offset;}
				else if(targetLength==0)replacedLength=0;
				else if(targetLength>0)found=findMungerTarget(contents,offset,target,targetLength);
				else found=-1;
				if(found>=0&&replacedLength>=0&&found<=size&&replacedLength<=size-found) {
					if(replacement==0)result=found;
					else {
						long newSize=(long)size-replacedLength+replacementLength;
						if(newSize<=Integer.MAX_VALUE)try {
							byte[] bytes=new byte[(int)newSize];
							System.arraycopy(contents,0,bytes,0,found);
							for(int i=0;i<replacementLength;i++)bytes[found+i]=(byte)memory.readByte(replacement+i);
							System.arraycopy(contents,found+replacedLength,bytes,found+replacementLength,size-found-replacedLength);
							loadHandle(handle,bytes);memErr(NO_ERR);result=found+replacementLength;
						}catch(M68kCpuException e) {memErr(MEM_FULL_ERR);}
					}
				}
			}
		}
		memory.writeLong(cpu.a[7],result);return true;
	}

	private int findMungerTarget(byte[] contents,int offset,int target,int length) throws M68kCpuException {
		for(int start=offset;start<contents.length;start++) {
			int remaining=contents.length-start,matched=Math.min(length,remaining),i=0;
			while(i<matched&&contents[start+i]==(byte)memory.readByte(target+i))i++;
			if(i==matched&&(matched==length||start+matched==contents.length))return start;
		}
		return -1;
	}

	/** _HandToHand duplicates a relocatable block and returns the new Handle in A0. */
	private boolean handToHand(M68kCpu cpu) throws M68kCpuException {
		// MemErr follows D0, as after the Memory Manager calls the real one ends with: UxReplace checks MemError() right after
		// it and found the -50 an earlier HLock(NIL) had left there
		int sourceHandle=cpu.a[0];
		if(sourceHandle==0||!handleSizes.containsKey(sourceHandle)){
			cpu.a[0]=0;cpu.d[0]=sourceHandle==0?NIL_HANDLE_ERR:PARAM_ERR;memErr(cpu.d[0]);return true;
		}
		byte[] contents=handleContents(sourceHandle);
		if(contents==null){
			if(handleSizes.get(sourceHandle)==0)contents=new byte[0];
			// A purged Handle cannot safely be copied. Resource Handles may still be
			// unloaded, so give the Resource Manager the same load opportunity as HLock.
			else if(memory.readLong(sourceHandle)==0&&app.resources.loadIfResource(sourceHandle))
				contents=handleContents(sourceHandle);
			if(contents==null){cpu.a[0]=0;cpu.d[0]=NIL_HANDLE_ERR;memErr(cpu.d[0]);return true;}
		}
		try {
			cpu.a[0]=createHandle(contents);cpu.d[0]=NO_ERR;
		}catch(M68kCpuException e){
			cpu.a[0]=0;cpu.d[0]=MEM_FULL_ERR;
		}
		memErr(cpu.d[0]);
		return true;
	}

	private boolean blockMove(M68kCpu cpu) throws M68kCpuException {
		// D0 comes back as the result code (noErr), not the byte count: UltraPaint's glue stores D0 into MemErr after every
		// BlockMove and treats a non-zero MemErr as fatal (it quit at startup with "error 22" = the 22 bytes it had just moved).
		if(cpu.d[0]<=0){setMemoryResult(cpu,NO_ERR);return true;} // a count of 0 or less moves nothing and is no error (the ROM's loop just doesn't run)
		memory.copy(cpu.a[0],cpu.a[1],cpu.d[0]);setMemoryResult(cpu,NO_ERR);
		if(ResourceManager68k.tracingSegments()&&cpu.d[0]>=32)ResourceManager68k.followSegments(cpu.a[1],memory.readBytes(cpu.a[1],cpu.d[0]));
		return true;
	}

	private int sizeOf(Map<Integer,Integer> sizes,int address) {
		Integer size=sizes.get(address);if(size==null){memErr(PARAM_ERR);return 0;}memErr(NO_ERR);return size;
	}

	private void setMemoryResult(M68kCpu cpu,int error) throws M68kCpuException {
		memErr(error);
		cpu.d[0]=error;
	}
	/** Every Memory Manager result also lands in MemErr ($220): MemError() is an inline read of that word, and some runtimes'
	 *  SetPtrSize wrapper trusts it rather than D0 (a failed growth it took for success overran the next block). */
	void memErr(int error){
		memoryError=error;
		try{memory.writeWord(0x0220,error);}catch(M68kCpuException ignored){}
	}

	/** Traps implemented by this class itself (the Toolbox reports its own). */
	boolean isImplementedTrap(int word) {
		if((word&0x0800)!=0) {
			int toolbox=word&0x0fff;
			if(ToolUtils68k.name(toolbox)!=null)return true;
			if(ScrapManager68k.name(toolbox)!=null)return true;
			if(SoundManager68k.name(toolbox)!=null)return true;
			if(CursorManager68k.name(toolbox)!=null)return true;
			if(toolbox==0x9e0||toolbox==0x9e1||toolbox==0x9eb||toolbox==0x9c8)return true;
			return QuickDraw68k.name(toolbox)!=null;
		}
		int trap=word&0xff;
		if(FileManager68k.name(trap)!=null)return true;
		switch(trap) {
			case 0x1e:case 0x1f:case 0x20:case 0x21:case 0x22:case 0x23:case 0x24:
			case 0x25:case 0x27:case 0x29:case 0x2a:case 0x2b:case 0x2c:case 0x2e:
			case 0x30:case 0x31:case 0x36:case 0x40:case 0x49:case 0x4a:case 0x4c:case 0x4d:case 0x50:
			case 0x55:case 0x65:case 0x66:return true;
			default:return false;
		}
	}

	private static int fourcc(String value) {
		return value.charAt(0)<<24|value.charAt(1)<<16|value.charAt(2)<<8|value.charAt(3);
	}

	@Override public String name(int opcode) {
		String appName=app.trapName(opcode);if(appName!=null)return appName;
		String fileName=FileManager68k.name(opcode&0xff);
		if((opcode&0x0800)==0&&fileName!=null)return fileName;
		String utilityName=ToolUtils68k.name(opcode&0x0fff);
		if(utilityName!=null)return utilityName;
		String scrapName=ScrapManager68k.name(opcode&0x0fff);
		if(scrapName!=null)return scrapName;
		String soundName=SoundManager68k.name(opcode&0x0fff);
		if(soundName!=null)return soundName;
		String cursorName=CursorManager68k.name(opcode&0x0fff);
		if(cursorName!=null)return cursorName;
		String adbName=(opcode&0x0800)==0?AdbManager68k.name(opcode&0xff):null;
		if(adbName!=null)return adbName;
		if((opcode&0x0fff)==0x9e0)return "Munger";
		if((opcode&0x0fff)==0x9e1)return "HandToHand";
		if((opcode&0x0fff)==0x9eb)return "FP68K (SANE)";
		String quickDrawName=QuickDraw68k.name(opcode&0x0fff);
		if(quickDrawName!=null)return quickDrawName;
		if((opcode&0x0fff)==0x861)return "Random";
		if((opcode&0x0fff)==0x975)return "TickCount";
		if((opcode&0x0fff)==0x976)return "GetKeys";
		if((opcode&0x0fff)==0x9c8)return "SysBeep";
		if((opcode&0x0fff)==0x830)return "Pack14"; // the Help Manager
		// the Memory Manager and Event Manager OS traps below: a Toolbox trap with the same low byte ($A830 Pack14) is not one of them
		if((opcode&0x0800)!=0)return String.format("A-line trap $%04X",opcode);
		switch(opcode&0xff){
			case 0x1e:return "NewPtr";case 0x1f:return "DisposePtr";case 0x20:return "SetPtrSize";case 0x66:return "NewEmptyHandle";
			case 0x21:return "GetPtrSize";case 0x22:return "NewHandle";case 0x23:return "DisposeHandle";
			case 0x24:return "SetHandleSize";case 0x25:return "GetHandleSize";case 0x27:return "ReallocHandle";
			case 0x29:return "HLock";case 0x2a:return "HUnlock";case 0x2b:return "EmptyHandle";case 0x36:return "MoreMasters";
			case 0x49:return "HPurge";case 0x4a:return "HNoPurge";case 0x4c:return "CompactMem";case 0x4d:return "PurgeMem";
			case 0x2e:return "BlockMove";case 0x30:return "OSEventAvail";case 0x31:return "GetOSEvent";
			case 0x50:return "RelString";case 0x55:return "StripAddress";
			default:return String.format("A-line trap $%04X",opcode);
		}
	}
}

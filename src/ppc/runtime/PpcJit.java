import java.lang.invoke.MethodHandles;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Just-in-time compiler for the PowerPC VM ({@link PpcCpu}): code that runs often becomes Java bytecode, which HotSpot then
 * compiles to host code like any other Java method.
 *
 * A compiled region is the code reachable from a start address along direct branches and fall-throughs (calls included,
 * up to a size limit): one hidden class whose run method keeps the registers it touches in Java locals, so HotSpot can give
 * them host registers, and writes them back into the CPU when the region is left. A region is left through an indirect
 * branch to an address outside it, a branch to code it does not hold, an instruction it does not compile (sc, the POWER-only
 * and overflow-enabled instructions, lswx/stswx, an unconditional trap: the interpreter runs those), a taken trap, after
 * icbi, and when the instructions it ran reach the next scheduler poll (checked on every backward branch and indirect jump).
 * An instruction whose memory access fails is not finished: the region stops before it and the interpreter runs it again,
 * so faults, the program's exception handler and crash reports work as they do without the JIT. An indirect branch to a
 * host gate (glue calling a native routine) calls the native from the region and goes on where it returns to.
 *
 * Each piece of code is compiled once: every block start of a region is one of its entries, and a new region stops where
 * code another region enters begins (small leaf routines and glue excepted). HotSpot runs a new class in its bytecode
 * interpreter, many times slower than {@link PpcCpu}'s, until the class has been used enough to be compiled, so copies of
 * the same code in many regions kept programs with lots of lukewarm code (MacBench's Processor test) slow for tens of
 * seconds. For the same reason a region dropped because its code was freed or written over is kept a while: the same code
 * loaded at the same place again gets the class back, already compiled.
 *
 * The CR fields, XER's carry and the FPSCR's result class are kept apart in the locals and put together on the way out
 * (FPRF only from the last floating-point result, when something reads the FPSCR). Every instruction computes exactly what
 * the interpreter's does - the same Java expressions over the same raw bits - so the two can be switched at any poll.
 *
 * Code that changes under a compiled region drops the region: the memory marks the 64-byte lines holding compiled code
 * ({@link M68kMemory#setCodeListener}), and a write into a marked line, a block copy, load or fill over one, freeing or
 * allocating the block, icbi or MakeDataExecutable all end up in {@link #codeWritten}. A store by which a region drops itself
 * stops it at once ({@link CodeChanged}): the interpreter does the store again and goes on with the new code, so code that
 * rewrites its own next instructions runs them as the interpreter does.
 *
 * Off (the interpreter alone) with the launcher's JIT setting in the Mac application settings, a program's own setting
 * ("PowerPC code" in its Application Settings), or -Dhzppc.jit=false; the debug traces (-Dhzppc.pctrace, -Dhz68k.watch,
 * ppccalls) run the interpreter too.
 */
final class PpcJit implements M68kMemory.CodeListener {
	/** A compiled region: runs from cpu.pc (one of its entries) and returns the instructions it ran, with every register
	 *  back in the CPU and pc where it stopped; -2-n when it stopped before an instruction the interpreter must run next
	 *  (a failed memory access, a taken trap: compiled code there would stop at once again); -1 when cpu.pc is not one of
	 *  its entries (nothing changed). */
	abstract static class Code { abstract int run(PpcCpu cpu) throws M68kException; } // a class, not an interface: the call is a virtual one
	/** The cache entry of an address without a region yet ({@link PpcCpu#executeJit} counts its heat). */
	static final Code COLD=new Code(){@Override int run(PpcCpu cpu){return -1;}};
	/** The cache entry of an address no region can start at (interpreted). */
	static final Code NONE=new Code(){@Override int run(PpcCpu cpu){return -1;}};
	/**
	 * Thrown out of a store by which the running region dropped its own code: its fault handler stops it before the store,
	 * and the interpreter does the store again (stores can be repeated) and runs the code as it is now.
	 */
	static final class CodeChanged extends RuntimeException {
		private static final long serialVersionUID=1L;
		static final CodeChanged INSTANCE=new CodeChanged();
		private CodeChanged(){super("compiled code changed under the running region",null,false,false);}
	}

	// ------------------------------------------------------------------ settings

	/** -Dhzppc.jit=true|false overrides the settings. */
	private static final String FORCED=System.getProperty("hzppc.jit");
	/** The launcher's shared setting (PCARD): on unless the user turned it off. */
	static volatile boolean sharedSetting=true;
	/** Whether a program runs with the JIT: -Dhzppc.jit, else its own setting ({@code program}, null = follow), else the shared one. */
	static boolean wanted(Boolean program){
		if(FORCED!=null)return !"false".equalsIgnoreCase(FORCED);
		return program!=null?program:sharedSetting;
	}
	/** Times an address is reached before a region is compiled from it (-Dhzppc.jitthreshold). */
	private static final int THRESHOLD=Math.max(1,Integer.getInteger("hzppc.jitthreshold",40));
	/** Instructions in one region at most (-Dhzppc.jitmax); fewer when its bytecode would be too long for HotSpot. */
	private static final int MAX_INSTRUCTIONS=Math.max(8,Integer.getInteger("hzppc.jitmax",320));
	/** Whether a region takes in the functions it calls (-Dhzppc.jitcalls=false: calls leave the region). */
	private static final boolean FOLLOW_CALLS=!"false".equalsIgnoreCase(System.getProperty("hzppc.jitcalls"));
	/** Whether every block start of a region is an entry (-Dhzppc.jitallentries=false: the start, return sites and loop heads). */
	private static final boolean ALL_ENTRIES=!"false".equalsIgnoreCase(System.getProperty("hzppc.jitallentries"));
	/** Whether a new region leaves code other regions already enter to them (-Dhzppc.jitshare=false: compiles it again). */
	private static final boolean SHARE=!"false".equalsIgnoreCase(System.getProperty("hzppc.jitshare"));
	/** Instructions of a routine a region takes in although another region has it (see Discovery.smallLeaf). */
	private static final int LEAF=Integer.getInteger("hzppc.jitleaf",32);
	/** Whether indirect branches to host gates call the native from the compiled code (-Dhzppc.jitgates=false: they leave it). */
	private static final boolean FOLLOW_GATES=!"false".equalsIgnoreCase(System.getProperty("hzppc.jitgates"));
	/** HotSpot does not compile methods of 8000 bytecode bytes or more (HugeMethodLimit): a region stays below. */
	private static final int CODE_LIMIT=7600;
	/** Regions kept at most; past it all are dropped and compiled again as they run (a program that keeps making code). */
	private static final int MAX_REGIONS=Integer.getInteger("hzppc.jitregions",6000);
	/** -Dhzppc.jittrace=true: every region compiled and dropped. */
	static final boolean TRACE=Boolean.getBoolean("hzppc.jittrace");
	/** Debug: -Dhzppc.jitdump=folder writes each region's class file there (region-XXXXXXXX.class, for javap -c -p). */
	private static final String DUMP=System.getProperty("hzppc.jitdump");

	// ------------------------------------------------------------------ the regions

	/** One compiled region. */
	static final class Region {
		final int start;final int[] entries;
		/** The code it was compiled from: [start, end) pairs. */
		final int[] ranges;final int instructions;
		Code code;
		/** The instruction words of {@link #ranges} as compiled, and the class (kept while the region is retired). */
		int[] ops;Code compiled;int bytecode;boolean isRetired;
		Region(int start,int[] entries,int[] ranges,int instructions){this.start=start;this.entries=entries;this.ranges=ranges;this.instructions=instructions;}
		boolean overlaps(long from,long to){for(int i=0;i<ranges.length;i+=2)if(from<(ranges[i+1]&0xffffffffL)&&to>(ranges[i]&0xffffffffL))return true;return false;}
		/** The instruction word at {@code address} as compiled (an address of its ranges). */
		int opAt(int address){int k=0;for(int i=0;i<ranges.length;i+=2){if(address>=ranges[i]&&address<ranges[i+1])return ops[k+((address-ranges[i])>>>2)];k+=(ranges[i+1]-ranges[i])>>>2;}return 0;}
		/** Its key among the retired regions for the entry {@code address}: the address and the instruction there. */
		long retiredKey(int address){return retiredKey(address,opAt(address));}
		static long retiredKey(int address,int op){return (long)address<<32|(op&0xffffffffL);}
		/** Whether memory holds the code this region was compiled from again. */
		boolean sameCode(M68kMemory memory){
			int k=0;
			try{for(int i=0;i<ranges.length;i+=2)for(int p=ranges[i];p!=ranges[i+1];p+=4){if(memory.physical(p)!=p||p>memory.size()-4||memory.readLong(p)!=ops[k++])return false;}}
			catch(M68kCpuException e){return false;}
			return true;
		}
	}

	static final int CACHE_BITS=16,CACHE_MASK=(1<<CACHE_BITS)-1;
	/** Direct-mapped cache of region entries by (pc>>>2)&CACHE_MASK, read by PpcCpu without a lock: key 1 is empty. */
	final int[] cacheKey=new int[1<<CACHE_BITS];final Code[] cacheCode=new Code[1<<CACHE_BITS];
	private final Map<Integer,Region> entries=new HashMap<Integer,Region>();
	/** How often each address (hashed) was reached before it had a region. */
	private final short[] heat=new short[1<<16];
	/** Regions by the 64-byte lines of their code (see {@link M68kMemory#CODE_SHIFT}). */
	private final Map<Integer,List<Region>> byLine=new HashMap<Integer,List<Region>>();
	private final List<PpcCpu> cpus=new ArrayList<PpcCpu>();
	/**
	 * Regions dropped because their code was written over or freed, by entry and the instruction there ({@link
	 * Region#retiredKey}): a program that loads the same code at the same place again (a code resource read anew for every
	 * run, MacBench's tests) gets the region back, already compiled by HotSpot, instead of a new class that starts in the
	 * bytecode interpreter. At most {@link #RETIRED_BYTECODE} bytes of bytecode are kept (-Dhzppc.jitretired), the least
	 * recently retired go first.
	 */
	private final Map<Long,List<Region>> retired=new HashMap<Long,List<Region>>();
	/** The retired regions, oldest first. */
	private final java.util.LinkedHashSet<Region> retiredOrder=new java.util.LinkedHashSet<Region>();
	private long retiredBytecode;
	private static final long RETIRED_BYTECODE=Long.getLong("hzppc.jitretired",24L<<20);
	private int regionCount,refusedCount;
	long revived;
	final M68kMemory memory;
	// statistics
	long compiled,dropped,compileNanos,bytecodeBytes,failures;

	private PpcJit(M68kMemory memory){this.memory=memory;Arrays.fill(cacheKey,1);memory.setCodeListener(this);}
	/** The JIT of {@code memory}'s address space (one per space: the regions are its code) for {@code cpu}. */
	static synchronized PpcJit attach(M68kMemory memory,PpcCpu cpu){
		PpcJit j=memory.codeListener() instanceof PpcJit?(PpcJit)memory.codeListener():new PpcJit(memory);
		synchronized(j){if(!j.cpus.contains(cpu))j.cpus.add(cpu);}
		return j;
	}

	/** The region code for an entry at {@code pc} missing from the direct cache: compiled once the address got hot (until
	 *  then the cache holds {@link #COLD} for it, and null comes back). */
	synchronized Code lookup(int pc){
		Region r=entries.get(pc);
		if(r!=null){cache(pc,r.code);return r.code;}
		List<Region> old=retired.isEmpty()?null:retired.get(Region.retiredKey(pc,opAt(pc)));
		if(old!=null)for(Region o:old)if(o.sameCode(memory)){unretire(o);o.code=o.compiled;install(o);revived++;cache(pc,o.code);return o.code;}
		int h=heatSlot(pc);
		if(++heat[h]<THRESHOLD){cache(pc,COLD);return null;}
		heat[h]=0;
		r=compile(pc);
		if(r==null){ // interpreted from here: remembered like a region of its one instruction, so that new code there is tried again
			r=new Region(pc,new int[]{pc},new int[]{pc,pc+4},0);r.code=NONE;
			if(memory.physical(pc)==pc&&pc<=memory.size()-4)install(r);
		}
		cache(pc,r.code);
		return r.code;
	}
	private int opAt(int pc){try{return memory.physical(pc)==pc&&pc<=memory.size()-4?memory.readLong(pc):0;}catch(M68kCpuException e){return 0;}}
	int heatSlot(int pc){return (pc>>>2)&(heat.length-1);}
	/** One more visit of {@code pc} (cached {@link #COLD}); true when it is hot enough for {@link #lookup} to compile. Without
	 *  the lock: a lost count only delays a region. */
	boolean warm(int pc){int h=heatSlot(pc);return ++heat[h]>=THRESHOLD;}
	private void cache(int pc,Code code){int slot=(pc>>>2)&CACHE_MASK;cacheCode[slot]=code;cacheKey[slot]=pc;}

	// ------------------------------------------------------------------ invalidation

	/** Drops every region whose code overlaps [address, address+length) (the memory saw a write into a marked line). */
	@Override public synchronized void codeWritten(int address,int length){
		long from=address&0xffffffffL,to=from+Math.max(1,length);
		int first=(int)(from>>>M68kMemory.CODE_SHIFT),last=(int)((to-1)>>>M68kMemory.CODE_SHIFT);
		for(int line=first;line<=last;line++){
			List<Region> list=byLine.get(line);if(list==null)continue;
			for(Region r:list.toArray(new Region[0]))if(r.overlaps(from,to))drop(r);
		}
		if(runningDropped){runningDropped=false;throw CodeChanged.INSTANCE;}
	}
	/** {@link #drop} dropped the region this thread is running (a store of its own changed its code). */
	private boolean runningDropped;
	/** icbi, MakeDataExecutable: the code at [address, address+length) may have changed. */
	void invalidate(int address,int length){if(length>0)codeWritten(memory.physical(address),length);}
	private void drop(Region r){
		if(r.code==null)return;
		Code code=r.code;r.code=null;
		if(code==NONE)refusedCount--;else{regionCount--;dropped++;retire(r);}
		byte[] m=memory.codeMarks();
		forEachLine(r,line->{List<Region> list=byLine.get(line);if(list!=null){list.remove(r);if(list.isEmpty()){byLine.remove(line);if(m!=null&&line<m.length)m[line]=0;}}});
		for(int e:r.entries){entries.remove(e,r);int slot=(e>>>2)&CACHE_MASK;if(cacheCode[slot]==code){cacheKey[slot]=1;cacheCode[slot]=null;}}
		for(PpcCpu c:cpus){
			if(c.jitRunning==code&&c.jitThread==Thread.currentThread())runningDropped=true; // its own store: it stops there
			else c.jitStop=Integer.MIN_VALUE; // running on another thread: it stops at its next backward branch
		}
		if(TRACE&&code!=NONE)System.err.printf("[ppc jit] dropped region %08X (%d instructions)%n",r.start,r.instructions);
	}
	private void retire(Region r){
		if(r.ops==null||RETIRED_BYTECODE<=0)return;
		for(int e:r.entries)retired.computeIfAbsent(r.retiredKey(e),k->new ArrayList<Region>(1)).add(r);
		r.isRetired=true;retiredBytecode+=r.bytecode;retiredOrder.add(r);
		while(retiredBytecode>RETIRED_BYTECODE&&!retiredOrder.isEmpty())unretire(retiredOrder.iterator().next());
	}
	/** Takes {@code r} out of the retired regions (revived, replaced or too old). */
	private void unretire(Region r){
		if(!r.isRetired)return;
		for(int e:r.entries){long k=r.retiredKey(e);List<Region> l=retired.get(k);if(l!=null&&l.remove(r)&&l.isEmpty())retired.remove(k);}
		r.isRetired=false;retiredBytecode-=r.bytecode;retiredOrder.remove(r);
	}
	private void forEachLine(Region r,java.util.function.IntConsumer f){
		for(int i=0;i<r.ranges.length;i+=2){
			int first=Math.max(0,r.ranges[i]-3)>>>M68kMemory.CODE_SHIFT,last=(r.ranges[i+1]-1)>>>M68kMemory.CODE_SHIFT; // -3: a word or long written just before the code reaches into it
			for(int line=first;line<=last;line++)f.accept(line);
		}
	}
	private void install(Region r){
		if(r.code==NONE)refusedCount++;
		else{if(regionCount>=MAX_REGIONS)dropAll();regionCount++;}
		for(int e:r.entries){entries.put(e,r);int slot=(e>>>2)&CACHE_MASK;if(cacheKey[slot]==e)cacheCode[slot]=r.code;}
		byte[] m=memory.codeMarks();
		forEachLine(r,line->{List<Region> list=byLine.computeIfAbsent(line,k->new ArrayList<Region>(2));if(!list.contains(r))list.add(r);if(m!=null&&line<m.length)m[line]=1;});
	}
	/** Drops every region (the region limit was reached). */
	synchronized void dropAll(){
		for(List<Region> list:new ArrayList<List<Region>>(byLine.values()))for(Region r:list.toArray(new Region[0]))drop(r);
		entries.clear();Arrays.fill(cacheKey,1);Arrays.fill(cacheCode,null);
	}
	/** Drops every region and forgets the retired ones (the JIT is turned off). */
	synchronized void clear(){dropAll();for(Region r:retiredOrder)r.isRetired=false;retired.clear();retiredOrder.clear();retiredBytecode=0;}

	/** One line of statistics (-Dhzppc.stats). */
	synchronized String statistics(){
		if(PpcCpu.JIT_STATS){ // compiled instructions in all live regions against the distinct ones they cover
			java.util.BitSet seen=new java.util.BitSet();long total=0;java.util.Set<Region> live=new HashSet<Region>(entries.values());
			for(Region r:live){if(r.code==NONE)continue;total+=r.instructions;for(int i=0;i<r.ranges.length;i+=2)seen.set(r.ranges[i]>>>2,r.ranges[i+1]>>>2);}
			return String.format("JIT %d regions (%d compiled, %d dropped, %d revived, %d failed, %.0f ms, %d KB bytecode, %d instructions compiled over %d distinct)",regionCount,compiled,dropped,revived,failures,compileNanos/1e6,bytecodeBytes/1024,total,seen.cardinality());
		}
		return String.format("JIT %d regions (%d compiled, %d dropped, %d revived, %d addresses interpreted, %d failed, %.0f ms, %d KB bytecode)",regionCount,compiled,dropped,revived,refusedCount,failures,compileNanos/1e6,bytecodeBytes/1024);
	}

	// ------------------------------------------------------------------ compiling

	private static final MethodHandles.Lookup LOOKUP=MethodHandles.lookup();
	private int failureReports;

	private Region compile(int pc){
		if((pc&3)!=0||pc==0||memory.physical(pc)!=pc||pc>memory.size()-4)return null; // 0: the interpreter reports the jump
		long t0=System.nanoTime();
		try{
			int limit=MAX_INSTRUCTIONS;
			for(;;){
				Discovery d=new Discovery(pc,limit);
				if(d.code.isEmpty())return null;
				Gen dry=new Gen(d,null);dry.generate();
				if(dry.c.pos()>CODE_LIMIT&&limit>8){limit=Math.max(8,limit*CODE_LIMIT/dry.c.pos()*9/10);continue;}
				Gen gen=new Gen(d,dry);
				byte[] bytes=gen.generate();
				if(bytes==null){if(limit<=8)return null;limit/=2;continue;}
				if(DUMP!=null)try{java.nio.file.Files.write(java.nio.file.Paths.get(DUMP,String.format("region-%08X.class",pc)),bytes);}catch(java.io.IOException e){}
				Class<?> cls=LOOKUP.defineHiddenClass(bytes,true).lookupClass();
				Code code=(Code)cls.getDeclaredConstructor().newInstance();
				int[] ranges=d.ranges();
				Region r=new Region(pc,d.entryList(),ranges,d.code.size());r.code=r.compiled=code;r.bytecode=bytes.length;
				r.ops=new int[d.code.size()];int k=0;for(int op:d.code.values())r.ops[k++]=op;
				install(r);
				compiled++;bytecodeBytes+=bytes.length;
				if(TRACE)System.err.printf("[ppc jit] region %08X: %d instructions, %d entries, %d bytes of bytecode%n",pc,d.code.size(),r.entries.length,gen.c.pos());
				return r;
			}
		}catch(Throwable t){
			failures++;
			if(failureReports++<10){System.err.println("[ppc jit] cannot compile the code at "+String.format("%08X",pc)+" (interpreted): "+t);if(TRACE)t.printStackTrace();}
			return null;
		}finally{compileNanos+=System.nanoTime()-t0;}
	}

	// ------------------------------------------------------------------ instruction classes

	/** Whether the JIT compiles {@code op}; the others end a region before them (the interpreter runs them). */
	static boolean compilable(int op){
		switch(op>>>26){
			case 3:return ((op>>>21)&31)!=31; // twi (the unconditional trap is the interpreter's)
			case 7:case 8:case 10:case 11:case 12:case 13:case 14:case 15:case 16:case 18:
			case 20:case 21:case 23:case 24:case 25:case 26:case 27:case 28:case 29:return true;
			case 19:switch((op>>>1)&0x3ff){case 0:case 16:case 528:case 33:case 129:case 150:case 193:case 225:case 257:case 289:case 417:case 449:return true;default:return false;}
			case 31:return compilable31(op);
			case 59:switch((op>>>1)&31){case 18:case 20:case 21:case 22:case 24:case 25:case 28:case 29:case 30:case 31:return true;default:return false;}
			case 63:
				switch((op>>>1)&31){case 18:case 20:case 21:case 22:case 23:case 25:case 26:case 28:case 29:case 30:case 31:return true;default:break;}
				switch((op>>>1)&0x3ff){case 0:case 32:case 12:case 14:case 15:case 38:case 70:case 40:case 72:case 136:case 264:case 64:case 134:case 583:case 711:return true;default:return false;}
			default:return (op>>>26)>=32&&(op>>>26)<=55;
		}
	}
	private static boolean compilable31(int op){
		switch((op>>>1)&0x3ff){
			case 4:return ((op>>>21)&31)!=31; // tw
			case 339:case 467:return spr(op)!=0; // mfspr, mtspr (not the 601's MQ)
			case 0:case 32:case 8:case 10:case 11:case 75:case 40:case 104:case 136:case 138:case 200:case 202:case 232:case 234:case 235:case 266:case 459:case 491:
			case 28:case 60:case 124:case 284:case 316:case 412:case 444:case 476:case 26:case 922:case 954:
			case 24:case 536:case 792:case 824:case 19:case 144:case 512:case 371:case 83:case 146:case 210:case 242:case 306:case 370:case 566:case 470:case 595:case 659:
			case 23:case 55:case 87:case 119:case 279:case 311:case 343:case 375:case 151:case 183:case 215:case 247:case 407:case 439:case 534:case 790:case 662:case 918:
			case 20:case 150:case 597:case 725:case 535:case 567:case 599:case 631:case 663:case 695:case 727:case 759:case 983:case 1014:
			case 54:case 86:case 246:case 278:case 598:case 854:case 982:return true;
			default:return false; // overflow-enabled forms, POWER instructions, lswx/stswx, eciwx/ecowx, unknown
		}
	}
	private static int spr(int op){return ((op>>>16)&31)|(((op>>>11)&31)<<5);}
	private static boolean isBranch(int op){int p=op>>>26;return p==16||p==18||p==19&&(((op>>>1)&0x3ff)==16||((op>>>1)&0x3ff)==528);}
	/** Ends a block without being a branch: icbi (the code may have changed: leave the region). */
	private static boolean endsBlock(int op){return isBranch(op)||op>>>26==31&&((op>>>1)&0x3ff)==982;}

	// ------------------------------------------------------------------ region discovery

	/** The instructions of a region: from its start along branches and fall-throughs, the calling code before the called. */
	private final class Discovery {
		final int start;
		final TreeMap<Integer,Integer> code=new TreeMap<Integer,Integer>();
		final TreeSet<Integer> leaders=new TreeSet<Integer>(),returnSites=new TreeSet<Integer>();
		Discovery(int start,int limit){
			this.start=start;
			ArrayDeque<Integer> local=new ArrayDeque<Integer>(),calls=new ArrayDeque<Integer>();
			local.add(start);
			for(;;){
				Integer next=local.poll();
				if(next==null)next=calls.poll();
				if(next==null)break;
				int a=next;
				if(code.containsKey(a)){leaders.add(a);continue;}
				if(code.size()>=limit||!readable(a)||!compilable(word(a))||compiledElsewhere(a))continue;
				leaders.add(a);
				for(int p=a;;p+=4){
					if(p!=a&&code.containsKey(p)){leaders.add(p);break;}
					if(code.size()>=limit||!readable(p)||p!=a&&compiledElsewhere(p))break;
					int op=word(p);
					if(!compilable(op))break;
					code.put(p,op);
					if(!endsBlock(op))continue;
					int primary=op>>>26;
					if(primary==18||primary==16){
						int target=primary==18?((op&2)!=0?0:p)+((op<<6)>>6&~3):((op&2)!=0?0:p)+(short)(op&0xfffc);
						boolean always=primary==18||(((op>>>21)&0x14)==0x14);
						if((op&1)==0){local.add(target);if(!always)local.add(p+4);}
						else{returnSites.add(p+4);local.add(p+4);if(target==p+4)local.add(target);else if(FOLLOW_CALLS){if(SHARE&&target!=start)smallLeaf(target);calls.add(target);}}
					}else if(primary==19){
						boolean always=((op>>>21)&0x14)==0x14||((op>>>1)&0x3ff)==528&&((op>>>21)&0x10)!=0;
						if((op&1)!=0){returnSites.add(p+4);local.add(p+4);}
						else if(!always)local.add(p+4);
					}
					break;
				}
			}
		}
		private boolean readable(int p){return (p&3)==0&&memory.physical(p)==p&&p<=memory.size()-4;}
		/**
		 * Code another region already enters at {@code p}: this one jumps there instead of compiling it again (every region
		 * a program's code ends up in starts in HotSpot's bytecode interpreter and must get hot on its own). Except small
		 * leaf routines this one calls (see {@link #smallLeaf}), which a jump there and back would cost more than they do.
		 */
		private boolean compiledElsewhere(int p){
			if(!SHARE||p==start)return false;
			for(int i=0;i<leafRanges.size();i+=2)if(p>=leafRanges.get(i)&&p<leafRanges.get(i+1))return false;
			Region r=entries.get(p);return r!=null&&r.code!=NONE&&r.code!=null;
		}
		private final List<Integer> leafRanges=new ArrayList<Integer>();
		/**
		 * A routine at {@code a} of at most {@link #LEAF} straight-line instructions (forward branches only) that calls nothing
		 * and ends in blr or bctr: cross-fragment glue, accessors. Taken into the region even when another one has it.
		 */
		private void smallLeaf(int a){
			int end=-1,reach=a; // reach: the furthest a forward branch goes
			for(int p=a;p<a+4*LEAF;p+=4){
				if(!readable(p))return;
				int op=word(p);
				if(!compilable(op))return;
				int primary=op>>>26;
				if(op==0x4e800020||op==0x4e800420){if(reach<=p){end=p+4;break;}continue;} // blr, bctr
				if(primary==18||primary==16){
					if((op&1)!=0||(op&2)!=0)return; // a call, an absolute branch
					int target=p+(primary==18?((op<<6)>>6&~3):(short)(op&0xfffc));
					if(target<=p||target>=a+4*LEAF)return;
					reach=Math.max(reach,target);
				}else if(primary==19&&(((op>>>1)&0x3ff)==16||((op>>>1)&0x3ff)==528))return; // other bclr/bcctr forms
			}
			if(end>0){leafRanges.add(a);leafRanges.add(end);}
		}
		private int word(int p){try{return memory.readLong(p);}catch(M68kCpuException e){return 0;}}
		/** The start, the return sites, and the targets of backward branches (where a region stopped for the poll goes on). */
		int[] entryList(){
			TreeSet<Integer> e=new TreeSet<Integer>();e.add(start);
			for(int s:returnSites)if(code.containsKey(s))e.add(s);
			for(Map.Entry<Integer,Integer> en:code.entrySet()){
				int p=en.getKey(),op=en.getValue();int primary=op>>>26;
				if(primary==18||primary==16){int target=primary==18?((op&2)!=0?0:p)+((op<<6)>>6&~3):((op&2)!=0?0:p)+(short)(op&0xfffc);
					if(target<=p&&code.containsKey(target))e.add(target);}
			}
			if(ALL_ENTRIES){ // every block start: code reached in the middle of a region enters it instead of starting another one over the same code
				Integer prev=null;
				for(Map.Entry<Integer,Integer> en:code.entrySet()){int p=en.getKey();
					if(leaders.contains(p)||prev==null||prev!=p-4||endsBlock(code.get(prev)))e.add(p);prev=p;}
			}
			int[] list=new int[e.size()];int i=0;for(int v:e)list[i++]=v;return list;
		}
		int[] ranges(){
			List<Integer> r=new ArrayList<Integer>();int s=-1,end=-1;
			for(int p:code.keySet()){if(p!=end){if(s>=0){r.add(s);r.add(end);}s=p;}end=p+4;}
			if(s>=0){r.add(s);r.add(end);}
			int[] a=new int[r.size()];for(int i=0;i<a.length;i++)a[i]=r.get(i);return a;
		}
	}

	// ------------------------------------------------------------------ code generation

	private static final String CPU="PpcCpu",MEM="M68kMemory",JIT="PpcJit";
	private static final int CA_BIT=0x20000000;

	/**
	 * Writes one region's class. Run twice: a dry run ({@code usage} null) records which registers the code reads and
	 * writes, the real one loads exactly those into locals at the start and stores the written ones at the exit.
	 */
	private final class Gen {
		final Discovery d;final boolean dry;
		final JitClassFile cf=new JitClassFile("PpcJitRegion","PpcJit$Code");
		final JitClassFile.Code c;
		// what the code uses (filled by the dry run, copied into the real one)
		final boolean[] gprUsed,gprWritten,fprUsed,fprWritten,crUsed,crWritten;
		boolean lrUsed,lrWritten,ctrUsed,ctrWritten,xerUsed,xerWritten,caUsed,caWritten,fpscrUsed,fpscrWritten,fprfUsed;
		// local slots
		final int lCpu=1,lMem,lR,lCount,lNext,lCur,lMode,lT0,lT1,lL0;int lF=-1,lLr,lCtr,lXer,lCa,lFpscr,lFstate,lFres;
		final int[] gpr=new int[32],fpr=new int[32],crf=new int[8];
		// labels
		final Map<Integer,JitClassFile.Label> blocks=new HashMap<Integer,JitClassFile.Label>();
		final TreeMap<Integer,JitClassFile.Label> exits=new TreeMap<Integer,JitClassFile.Label>(),loops=new TreeMap<Integer,JitClassFile.Label>(),interprets=new TreeMap<Integer,JitClassFile.Label>();
		JitClassFile.Label exitLabel,dispatchLabel,dynamicExitLabel;
		// compare-and-branch fusion: what the compare that last set each CR field compared (in this block)
		final int[] fuseKind=new int[8],fuseA=new int[8],fuseB=new int[8];final boolean[] fuseImm=new boolean[8];

		Gen(Discovery d,Gen usage){
			this.d=d;dry=usage==null;
			c=cf.method(0x0001,"run","(LPpcCpu;)I");
			if(dry){gprUsed=new boolean[32];gprWritten=new boolean[32];fprUsed=new boolean[32];fprWritten=new boolean[32];crUsed=new boolean[8];crWritten=new boolean[8];}
			else{gprUsed=usage.gprUsed;gprWritten=usage.gprWritten;fprUsed=usage.fprUsed;fprWritten=usage.fprWritten;crUsed=usage.crUsed;crWritten=usage.crWritten;
				lrUsed=usage.lrUsed;lrWritten=usage.lrWritten;ctrUsed=usage.ctrUsed;ctrWritten=usage.ctrWritten;xerUsed=usage.xerUsed;xerWritten=usage.xerWritten;
				caUsed=usage.caUsed;caWritten=usage.caWritten;fpscrUsed=usage.fpscrUsed;fpscrWritten=usage.fpscrWritten;fprfUsed=usage.fprfUsed;}
			c.objectLocal("java/lang/Object");c.objectLocal(CPU);
			lMem=c.objectLocal(MEM);lR=c.objectLocal("[I");
			lCount=c.intLocal();lNext=c.intLocal();lCur=c.intLocal();lMode=c.intLocal();lT0=c.intLocal();lT1=c.intLocal();lL0=c.longLocal();
			if(dry){lLr=lCtr=lXer=lCa=lFpscr=lFstate=lFres=lT0;Arrays.fill(gpr,lT0);Arrays.fill(crf,lT0);Arrays.fill(fpr,lL0);lF=lR;}
			else{
				boolean anyFpr=false;for(boolean b:fprUsed)anyFpr|=b;
				if(anyFpr)lF=c.objectLocal("[J");
				for(int i=0;i<32;i++)gpr[i]=gprUsed[i]?c.intLocal():-1;
				for(int i=0;i<8;i++)crf[i]=crUsed[i]?c.intLocal():-1;
				lLr=lrUsed?c.intLocal():-1;lCtr=ctrUsed?c.intLocal():-1;lXer=xerUsed?c.intLocal():-1;lCa=caUsed?c.intLocal():-1;
				lFpscr=fpscrUsed?c.intLocal():-1;lFstate=fprfUsed?c.intLocal():-1;lFres=fprfUsed?c.doubleLocal():-1;
				for(int i=0;i<32;i++)fpr[i]=fprUsed[i]?c.doubleLocal():-1;
			}
		}

		// ---- registers
		void ld(int r){gprUsed[r]=true;c.var(JitClassFile.ILOAD,gpr[r]);}
		/** (rA|0) */
		void ld0(int r){if(r==0)c.iconst(0);else ld(r);}
		void st(int r){gprUsed[r]=true;gprWritten[r]=true;c.var(JitClassFile.ISTORE,gpr[r]);
			for(int n=0;n<8;n++)if(fuseKind[n]!=0&&(fuseA[n]==r||!fuseImm[n]&&fuseB[n]==r))fuseKind[n]=0;}
		void ldF(int n){fprUsed[n]=true;c.var(JitClassFile.DLOAD,fpr[n]);}
		void stF(int n){fprUsed[n]=true;fprWritten[n]=true;c.var(JitClassFile.DSTORE,fpr[n]);}
		void ldCr(int n){crUsed[n]=true;c.var(JitClassFile.ILOAD,crf[n]);}
		void stCr(int n){crUsed[n]=true;crWritten[n]=true;c.var(JitClassFile.ISTORE,crf[n]);fuseKind[n]=0;}
		void ldLr(){lrUsed=true;c.var(JitClassFile.ILOAD,lLr);}
		void stLr(){lrUsed=lrWritten=true;c.var(JitClassFile.ISTORE,lLr);}
		void ldCtr(){ctrUsed=true;c.var(JitClassFile.ILOAD,lCtr);}
		void stCtr(){ctrUsed=ctrWritten=true;c.var(JitClassFile.ISTORE,lCtr);}
		void ldXer(){xerUsed=true;c.var(JitClassFile.ILOAD,lXer);}
		void stXer(){xerUsed=xerWritten=true;c.var(JitClassFile.ISTORE,lXer);}
		void ldCa(){caUsed=xerUsed=true;c.var(JitClassFile.ILOAD,lCa);}
		void stCa(){caUsed=caWritten=xerUsed=true;c.var(JitClassFile.ISTORE,lCa);}
		/** XER with its carry: what mfxer reads. */
		void ldXerFull(){ldXer();c.iconst(~CA_BIT);c.op(JitClassFile.IAND);ldCa();c.iconst(29);c.op(JitClassFile.ISHL);c.op(JitClassFile.IOR);}
		void ldFpscr(){fpscrUsed=true;c.var(JitClassFile.ILOAD,lFpscr);}
		void stFpscr(){fpscrUsed=fpscrWritten=true;c.var(JitClassFile.ISTORE,lFpscr);}
		/** The FPSCR's result class from the pending result, before anything reads or changes the FPSCR. */
		void settleFprf(){fprfUsed=true;ldFpscr();c.var(JitClassFile.ILOAD,lFstate);c.var(JitClassFile.DLOAD,lFres);c.invokestatic(JIT,"fprfIf","(IID)I");stFpscr();c.iconst(0);c.var(JitClassFile.ISTORE,lFstate);}
		/** setF: the double on the stack goes into fD and becomes the pending FPRF result; Rc copies FPSCR[0:3] into CR1. */
		void setF(int d,boolean rc){
			fprfUsed=true;fpscrUsed=fpscrWritten=true;
			c.op(JitClassFile.DUP2);stF(d);c.var(JitClassFile.DSTORE,lFres);c.iconst(1);c.var(JitClassFile.ISTORE,lFstate);
			if(rc)cr1();
		}
		void setS(int d,boolean rc){c.op(JitClassFile.D2F);c.op(JitClassFile.F2D);setF(d,rc);}
		void cr1(){ldFpscr();c.iconst(28);c.op(JitClassFile.IUSHR);stCr(1);}
		/** CR0 from the result in rD (Rc=1): a compare with 0, which a following branch can test directly. */
		void cr0(int r){ld(r);ldXer();c.invokestatic(JIT,"cr0","(II)I");stCr(0);fuseKind[0]=1;fuseA[0]=r;fuseImm[0]=true;fuseB[0]=0;}
		void setCur(int at){c.iconst(at);c.var(JitClassFile.ISTORE,lCur);}
		void op(int opcode){c.op(opcode);}
		void ic(int v){c.iconst(v);}

		// ---- memory
		void loadEa(int ra,int disp){ld0(ra);if(disp!=0){ic(disp);op(JitClassFile.IADD);}}
		void loadEaX(int ra,int rb){if(ra==0)ld(rb);else{ld(ra);ld(rb);op(JitClassFile.IADD);}}
		void readMem(String name){c.invokevirtual(MEM,name,"(I)I");}
		void store(String name){c.invokevirtual(CPU,name,"(II)V");}

		// ---- labels and exits
		JitClassFile.Label exitTo(int target){return exits.computeIfAbsent(target,k->c.label());}
		/** An exit before the instruction at {@code at}, which the interpreter runs next (a taken trap). */
		JitClassFile.Label interpretAt(int at){return interprets.computeIfAbsent(at,k->c.label());}
		/** Where a branch at {@code from} to {@code target} goes: the block (forward), a poll check before it (backward), an exit. */
		JitClassFile.Label targetLabel(int target,int from){
			JitClassFile.Label l=blocks.get(target);
			if(l==null)return exitTo(target);
			if(target<=from)return loops.computeIfAbsent(target,k->c.label());
			return l;
		}

		byte[] generate(){
			exitLabel=c.label();dynamicExitLabel=c.label();dispatchLabel=c.label();
			List<int[]> blockList=new ArrayList<int[]>(); // {start, end}
			int s=-1,end=-1;
			for(Map.Entry<Integer,Integer> e:d.code.entrySet()){
				int p=e.getKey();
				if(s<0||p!=end||d.leaders.contains(p)){if(s>=0)blockList.add(new int[]{s,end});s=p;}
				end=p+4;
				if(endsBlock(e.getValue())){blockList.add(new int[]{s,end});s=-1;}
			}
			if(s>=0)blockList.add(new int[]{s,end});
			for(int[] b:blockList)blocks.put(b[0],c.label());
			int[] entryList=d.entryList();
			prologue(entryList);
			JitClassFile.Label bodyStart=c.label();c.bind(bodyStart);
			for(int k=0;k<blockList.size();k++){
				int[] b=blockList.get(k);
				c.bind(blocks.get(b[0]));
				Arrays.fill(fuseKind,0);
				c.iinc(lCount,(b[1]-b[0])>>>2);
				boolean falls=true;
				for(int p=b[0];p<b[1];p+=4)falls=instruction(d.code.get(p),p);
				if(falls){int next=b[1];boolean adjacent=k+1<blockList.size()&&blockList.get(k+1)[0]==next;if(!adjacent)c.jump(JitClassFile.GOTO,targetLabel(next,b[1]-4));}
			}
			// stubs: poll checks before backward branches, exits, the indirect-jump dispatch
			for(Map.Entry<Integer,JitClassFile.Label> e:loops.entrySet()){
				c.bind(e.getValue());
				c.var(JitClassFile.ILOAD,lCount);c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"jitStop","I");c.jump(JitClassFile.IF_ICMPLT,blocks.get(e.getKey()));
				ic(e.getKey());c.var(JitClassFile.ISTORE,lNext);c.jump(JitClassFile.GOTO,exitLabel);
			}
			for(Map.Entry<Integer,JitClassFile.Label> e:new ArrayList<Map.Entry<Integer,JitClassFile.Label>>(exits.entrySet())){
				c.bind(e.getValue());ic(e.getKey());c.var(JitClassFile.ISTORE,lNext);c.jump(JitClassFile.GOTO,exitLabel);
			}
			for(Map.Entry<Integer,JitClassFile.Label> e:interprets.entrySet()){
				c.bind(e.getValue());ic(e.getKey());c.var(JitClassFile.ISTORE,lNext);ic(1);c.var(JitClassFile.ISTORE,lMode);c.jump(JitClassFile.GOTO,exitLabel);
			}
			c.bind(dispatchLabel);
			List<Integer> keys=new ArrayList<Integer>();for(int site:d.returnSites)if(blocks.containsKey(site))keys.add(site);
			if(!keys.isEmpty()){
				c.var(JitClassFile.ILOAD,lCount);c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"jitStop","I");c.jump(JitClassFile.IF_ICMPGE,dynamicExitLabel);
				int[] k=new int[keys.size()];JitClassFile.Label[] t=new JitClassFile.Label[k.length];
				for(int i=0;i<k.length;i++){k[i]=keys.get(i);t[i]=blocks.get(k[i]);}
				c.var(JitClassFile.ILOAD,lT0);c.lookupswitch(k,t,dynamicExitLabel);
			}
			// an indirect branch leaving the region: its address for the crash report of a jump to 0 (PpcCpu.glueCallee)
			c.bind(dynamicExitLabel);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lCur);c.putfield(CPU,"jitBranch","I");
			JitClassFile.Label gate=c.label();
			if(FOLLOW_GATES){c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);c.invokestatic(JIT,"isGate","(LM68kMemory;I)Z");c.jump(JitClassFile.IFNE,gate);}
			c.var(JitClassFile.ILOAD,lT0);c.var(JitClassFile.ISTORE,lNext);c.jump(JitClassFile.GOTO,exitLabel);
			// a failed memory access: the instruction at cur runs again in the interpreter
			JitClassFile.Label handler=c.label();
			c.handler(bodyStart,exitLabel,handler,"M68kCpuException");
			c.handler(bodyStart,exitLabel,handler,"PpcJit$CodeChanged");
			c.bind(exitLabel);
			epilogue();
			c.bindHandler(handler);
			op(JitClassFile.POP);c.var(JitClassFile.ILOAD,lCur);c.var(JitClassFile.ISTORE,lNext);ic(1);c.var(JitClassFile.ISTORE,lMode);c.jump(JitClassFile.GOTO,exitLabel);
			// an indirect branch to a host gate (a call through glue to a native routine): the native runs from here, with the
			// registers back in the CPU, and the code goes on where it returns to (outside the handler: its exceptions are real)
			c.bind(gate);
			storeState();
			c.iinc(lCount,1);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);c.invokevirtual(CPU,"jitGate","(I)Z");c.var(JitClassFile.ISTORE,lT1);
			loadState();
			c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"pc","I");c.var(JitClassFile.ISTORE,lT0);
			c.var(JitClassFile.ILOAD,lT1);c.jump(JitClassFile.IFNE,dispatchLabel);
			c.var(JitClassFile.ILOAD,lT0);c.var(JitClassFile.ISTORE,lNext);c.jump(JitClassFile.GOTO,exitLabel);
			if(dry)return null;
			if(c.pos()>CODE_LIMIT||!c.finish())return null;
			JitClassFile.Code init=cf.method(0x0001,"<init>","()V");
			init.objectLocal("java/lang/Object");init.maxStack=1;
			init.var(JitClassFile.ALOAD,0);init.invokespecial("PpcJit$Code","<init>","()V");init.op(JitClassFile.RETURN);init.finish();
			return cf.toBytes();
		}

		private void prologue(int[] entryList){
			c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"memory","LM68kMemory;");c.var(JitClassFile.ASTORE,lMem);
			c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"r","[I");c.var(JitClassFile.ASTORE,lR);
			if(!dry&&lF>=0){c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"f","[J");c.var(JitClassFile.ASTORE,lF);}
			ic(0);c.var(JitClassFile.ISTORE,lCount);ic(0);c.var(JitClassFile.ISTORE,lNext);ic(0);c.var(JitClassFile.ISTORE,lCur);ic(0);c.var(JitClassFile.ISTORE,lMode);
			ic(0);c.var(JitClassFile.ISTORE,lT0);ic(0);c.var(JitClassFile.ISTORE,lT1);c.lconst(0);c.var(JitClassFile.LSTORE,lL0);
			loadState();
			if(entryList.length==1)c.jump(JitClassFile.GOTO,blocks.get(entryList[0]));
			else{
				JitClassFile.Label miss=c.label();JitClassFile.Label[] t=new JitClassFile.Label[entryList.length];
				for(int i=0;i<t.length;i++)t[i]=blocks.get(entryList[i]);
				c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"pc","I");c.lookupswitch(entryList,t,miss);
				c.bind(miss);ic(-1);op(JitClassFile.IRETURN);
			}
		}
		/** The registers the region uses from the CPU into the locals. */
		private void loadState(){
			if(!dry){
				for(int i=0;i<32;i++)if(gprUsed[i]){c.var(JitClassFile.ALOAD,lR);ic(i);op(JitClassFile.IALOAD);c.var(JitClassFile.ISTORE,gpr[i]);}
				for(int i=0;i<32;i++)if(fprUsed[i]){c.var(JitClassFile.ALOAD,lF);ic(i);op(JitClassFile.LALOAD);c.invokestatic("java/lang/Double","longBitsToDouble","(J)D");c.var(JitClassFile.DSTORE,fpr[i]);}
				boolean anyCr=false;for(boolean b:crUsed)anyCr|=b;
				if(anyCr){c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"cr","I");c.var(JitClassFile.ISTORE,lT0);
					for(int n=0;n<8;n++)if(crUsed[n]){c.var(JitClassFile.ILOAD,lT0);if(n<7){ic(28-4*n);op(JitClassFile.IUSHR);}if(n>0){ic(15);op(JitClassFile.IAND);}c.var(JitClassFile.ISTORE,crf[n]);}}
				if(lrUsed){c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"lr","I");c.var(JitClassFile.ISTORE,lLr);}
				if(ctrUsed){c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"ctr","I");c.var(JitClassFile.ISTORE,lCtr);}
				if(xerUsed){c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"xer","I");c.var(JitClassFile.ISTORE,lXer);}
				if(caUsed){c.var(JitClassFile.ILOAD,lXer);ic(29);op(JitClassFile.IUSHR);ic(1);op(JitClassFile.IAND);c.var(JitClassFile.ISTORE,lCa);}
				if(fpscrUsed){c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"fpscr","I");c.var(JitClassFile.ISTORE,lFpscr);}
				if(fprfUsed){ic(0);c.var(JitClassFile.ISTORE,lFstate);c.dconst(0);c.var(JitClassFile.DSTORE,lFres);}
			}
		}

		private void epilogue(){
			if(dry){op(JitClassFile.ICONST_0);op(JitClassFile.IRETURN);return;}
			storeState();
			c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lNext);c.putfield(CPU,"pc","I");
			JitClassFile.Label interpret=c.label();
			c.var(JitClassFile.ILOAD,lMode);c.jump(JitClassFile.IFNE,interpret);
			c.var(JitClassFile.ILOAD,lCount);op(JitClassFile.IRETURN);
			c.bind(interpret);ic(-2);c.var(JitClassFile.ILOAD,lCount);op(JitClassFile.ISUB);op(JitClassFile.IRETURN);
		}
		/** The registers the region wrote from the locals back into the CPU. */
		private void storeState(){
			if(dry)return;
			if(fprfUsed){ldFpscr();c.var(JitClassFile.ILOAD,lFstate);c.var(JitClassFile.DLOAD,lFres);c.invokestatic(JIT,"fprfIf","(IID)I");c.var(JitClassFile.ISTORE,lFpscr);}
			for(int i=0;i<32;i++)if(gprWritten[i]){c.var(JitClassFile.ALOAD,lR);ic(i);c.var(JitClassFile.ILOAD,gpr[i]);op(JitClassFile.IASTORE);}
			for(int i=0;i<32;i++)if(fprWritten[i]){c.var(JitClassFile.ALOAD,lF);ic(i);c.var(JitClassFile.DLOAD,fpr[i]);c.invokestatic("java/lang/Double","doubleToRawLongBits","(D)J");op(JitClassFile.LASTORE);}
			int written=0;for(int n=0;n<8;n++)if(crWritten[n])written|=15<<(28-4*n);
			if(written!=0){
				c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"cr","I");ic(~written);op(JitClassFile.IAND);
				for(int n=0;n<8;n++)if(crWritten[n]){c.var(JitClassFile.ILOAD,crf[n]);if(n<7){ic(28-4*n);op(JitClassFile.ISHL);}op(JitClassFile.IOR);}
				c.putfield(CPU,"cr","I");
			}
			if(lrWritten){c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lLr);c.putfield(CPU,"lr","I");}
			if(ctrWritten){c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lCtr);c.putfield(CPU,"ctr","I");}
			if(xerWritten||caWritten){c.var(JitClassFile.ALOAD,lCpu);
				if(caUsed){c.var(JitClassFile.ILOAD,lXer);ic(~CA_BIT);op(JitClassFile.IAND);c.var(JitClassFile.ILOAD,lCa);ic(29);op(JitClassFile.ISHL);op(JitClassFile.IOR);}
				else c.var(JitClassFile.ILOAD,lXer);
				c.putfield(CPU,"xer","I");}
			if(fpscrWritten){c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lFpscr);c.putfield(CPU,"fpscr","I");}
		}

		// ---- instructions

		/** Compiles the instruction {@code op} at {@code at}; false when control never goes on to at+4 from it. */
		private boolean instruction(int op,int at){
			int rd=(op>>>21)&31,ra=(op>>>16)&31,rb=(op>>>11)&31;int imm=(short)op;
			switch(op>>>26){
				case 3:{int to=rd;if(to!=0){ic(to);ld(ra);ic(imm);c.invokestatic(JIT,"trap","(III)Z");c.jump(JitClassFile.IFNE,interpretAt(at));}return true;} // twi: a taken trap is the interpreter's
				case 7:ld(ra);ic(imm);op(JitClassFile.IMUL);st(rd);return true; // mulli
				case 8:longSum(ra,true,(imm&0xffffffffL)+1,false);carryResult(rd,false);return true; // subfic
				case 10:ld(ra);ic(op&0xffff);ldXer();c.invokestatic(JIT,"cmpu","(III)I");stCr(rd>>>2);fuse(rd>>>2,2,ra,true,op&0xffff);return true; // cmpli
				case 11:ld(ra);ic(imm);ldXer();c.invokestatic(JIT,"cmps","(III)I");stCr(rd>>>2);fuse(rd>>>2,1,ra,true,imm);return true; // cmpi
				case 12:case 13:longSum(ra,false,imm&0xffffffffL,false);carryResult(rd,(op>>>26)==13);return true; // addic, addic.
				case 14:if(ra==0)ic(imm);else{ld(ra);if(imm!=0){ic(imm);op(JitClassFile.IADD);}}st(rd);return true; // addi
				case 15:if(ra==0)ic(op<<16);else{ld(ra);ic(op<<16);op(JitClassFile.IADD);}st(rd);return true; // addis
				case 16:return bc(op,at);
				case 18:{int target=((op&2)!=0?0:at)+((op<<6)>>6&~3);if((op&1)!=0){ic(at+4);stLr();}c.jump(JitClassFile.GOTO,targetLabel(target,at));return false;} // b
				case 19:return op19(op,at);
				case 20:{int sh=rb,m=mask((op>>>6)&31,(op>>>1)&31);ld(rd);rotate(sh);ic(m);op(JitClassFile.IAND);ld(ra);ic(~m);op(JitClassFile.IAND);op(JitClassFile.IOR);st(ra);if((op&1)!=0)cr0(ra);return true;} // rlwimi
				case 21:{int sh=rb,mb=(op>>>6)&31,me=(op>>>1)&31,m=mask(mb,me);ld(rd); // rlwinm
					if(sh!=0&&mb==0&&me==31-sh){ic(sh);op(JitClassFile.ISHL);} // slwi
					else if(sh!=0&&me==31&&mb==32-sh){ic(mb);op(JitClassFile.IUSHR);} // srwi
					else{rotate(sh);if(m!=-1){ic(m);op(JitClassFile.IAND);}}
					st(ra);if((op&1)!=0)cr0(ra);return true;}
				case 23:{int m=mask((op>>>6)&31,(op>>>1)&31);ld(rd);ld(rb);c.invokestatic("java/lang/Integer","rotateLeft","(II)I");if(m!=-1){ic(m);op(JitClassFile.IAND);}st(ra);if((op&1)!=0)cr0(ra);return true;} // rlwnm
				case 24:ld(rd);if((op&0xffff)!=0){ic(op&0xffff);op(JitClassFile.IOR);}st(ra);return true; // ori (nop)
				case 25:ld(rd);ic(op<<16);op(JitClassFile.IOR);st(ra);return true; // oris
				case 26:ld(rd);ic(op&0xffff);op(JitClassFile.IXOR);st(ra);return true; // xori
				case 27:ld(rd);ic(op<<16);op(JitClassFile.IXOR);st(ra);return true; // xoris
				case 28:ld(rd);ic(op&0xffff);op(JitClassFile.IAND);st(ra);cr0(ra);return true; // andi.
				case 29:ld(rd);ic(op<<16);op(JitClassFile.IAND);st(ra);cr0(ra);return true; // andis.
				case 31:return op31(op,at);
				case 32:setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEa(ra,imm);readMem("readLong");st(rd);return true; // lwz
				case 33:loadUpdate(ra,imm,at,"readLong",rd,false);return true; // lwzu
				case 34:setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEa(ra,imm);readMem("readByte");st(rd);return true; // lbz
				case 35:loadUpdate(ra,imm,at,"readByte",rd,false);return true; // lbzu
				case 36:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEa(ra,imm);ld(rd);store("store32");return true; // stw
				case 37:storeUpdate(ra,imm,at,"store32",rd);return true; // stwu
				case 38:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEa(ra,imm);ld(rd);store("store8");return true; // stb
				case 39:storeUpdate(ra,imm,at,"store8",rd);return true; // stbu
				case 40:setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEa(ra,imm);readMem("readWord");st(rd);return true; // lhz
				case 41:loadUpdate(ra,imm,at,"readWord",rd,false);return true; // lhzu
				case 42:setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEa(ra,imm);readMem("readWord");op(JitClassFile.I2S);st(rd);return true; // lha
				case 43:loadUpdate(ra,imm,at,"readWord",rd,true);return true; // lhau
				case 44:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEa(ra,imm);ld(rd);store("store16");return true; // sth
				case 45:storeUpdate(ra,imm,at,"store16",rd);return true; // sthu
				case 46:{setCur(at);loadEa(ra,imm);c.var(JitClassFile.ISTORE,lT0); // lmw
					for(int i=rd;i<32;i++){c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);if(i>rd){ic(4*(i-rd));op(JitClassFile.IADD);}readMem("readLong");st(i);}
					return true;}
				case 47:{setCur(at);loadEa(ra,imm);c.var(JitClassFile.ISTORE,lT0); // stmw
					for(int i=rd;i<32;i++){c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);if(i>rd){ic(4*(i-rd));op(JitClassFile.IADD);}ld(i);store("store32");}
					return true;}
				case 48:setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEa(ra,imm);readMem("readLong");c.invokestatic(JIT,"lfs","(I)D");stF(rd);return true; // lfs
				case 49:setCur(at);updateEa(ra,imm);c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);readMem("readLong");c.invokestatic(JIT,"lfs","(I)D");stF(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // lfsu
				case 50:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEa(ra,imm);readDouble();stF(rd);return true; // lfd
				case 51:setCur(at);updateEa(ra,imm);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);readDouble();stF(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // lfdu
				case 52:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEa(ra,imm);ldF(rd);c.invokestatic(JIT,"stfs","(D)I");store("store32");return true; // stfs
				case 53:setCur(at);updateEa(ra,imm);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);ldF(rd);c.invokestatic(JIT,"stfs","(D)I");store("store32");c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // stfsu
				case 54:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEa(ra,imm);writeDouble(rd);return true; // stfd
				case 55:setCur(at);updateEa(ra,imm);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);writeDouble(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // stfdu
				case 59:op59(op);return true;
				case 63:op63(op);return true;
				default:throw new IllegalStateException("not compilable: "+Integer.toHexString(op));
			}
		}
		private void rotate(int sh){if(sh!=0){ic(sh);c.invokestatic("java/lang/Integer","rotateLeft","(II)I");}}
		private void readDouble(){c.invokevirtual(CPU,"readDouble","(I)J");c.invokestatic("java/lang/Double","longBitsToDouble","(J)D");}
		private void writeDouble(int fs){ldF(fs);c.invokestatic("java/lang/Double","doubleToRawLongBits","(D)J");c.invokevirtual(CPU,"writeDouble","(IJ)V");}
		/** ea = rA + d into T0 (the update forms use rA itself, also when it is r0). */
		private void updateEa(int ra,int disp){ld(ra);if(disp!=0){ic(disp);op(JitClassFile.IADD);}c.var(JitClassFile.ISTORE,lT0);}
		private void loadUpdate(int ra,int disp,int at,String read,int rd,boolean half){
			setCur(at);updateEa(ra,disp);c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);readMem(read);if(half)op(JitClassFile.I2S);st(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);
		}
		private void storeUpdate(int ra,int disp,int at,String write,int rs){
			setCur(at);updateEa(ra,disp);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);ld(rs);store(write);c.var(JitClassFile.ILOAD,lT0);st(ra);
		}
		private void fuse(int field,int kind,int a,boolean isImm,int b){fuseKind[field]=kind;fuseA[field]=a;fuseImm[field]=isImm;fuseB[field]=b;}

		/** Pushes a 64-bit sum: (rA or ~rA, as unsigned) + {@code constant} (+ CA when {@code carryIn}); see {@link #carryResult}. */
		private void longSum(int ra,boolean complement,long constant,boolean carryIn){
			ld(ra);if(complement){ic(-1);op(JitClassFile.IXOR);}
			op(JitClassFile.I2L);c.lconst(0xffffffffL);op(JitClassFile.LAND);
			if(constant!=0){c.lconst(constant);op(JitClassFile.LADD);}
			if(carryIn){ldCa();op(JitClassFile.I2L);op(JitClassFile.LADD);}
		}
		/** Adds rB as unsigned to the 64-bit sum on the stack. */
		private void addUnsigned(int rb){ld(rb);op(JitClassFile.I2L);c.lconst(0xffffffffL);op(JitClassFile.LAND);op(JitClassFile.LADD);}
		/** The 64-bit sum on the stack: its low word into rD, the carry out into CA. */
		private void carryResult(int rd,boolean rc){
			c.var(JitClassFile.LSTORE,lL0);
			c.var(JitClassFile.LLOAD,lL0);c.iconst(32);op(JitClassFile.LUSHR);op(JitClassFile.L2I);stCa();
			c.var(JitClassFile.LLOAD,lL0);op(JitClassFile.L2I);st(rd);
			if(rc)cr0(rd);
		}

		// ---- branches

		/** Jumps to {@code target} when CR bit {@code bi} equals {@code want} (or does not, with {@code negate}). */
		private void bitCondition(int bi,boolean want,boolean negate,JitClassFile.Label target){
			int field=bi>>>2,bit=bi&3;
			if(bit!=3&&fuseKind[field]!=0){ // the compare that set the field, done again as a branch
				boolean unsignedOrder=fuseKind[field]==2&&bit!=2;int jump;
				ld(fuseA[field]);
				boolean zero=fuseImm[field]&&fuseB[field]==0&&!unsignedOrder;
				if(!zero){if(fuseImm[field])ic(fuseB[field]);else ld(fuseB[field]);}
				if(unsignedOrder){c.invokestatic("java/lang/Integer","compareUnsigned","(II)I");zero=true;}
				// LT: a<b, GT: a>b, EQ: a==b
				int base=bit==0?(want?JitClassFile.IFLT:JitClassFile.IFGE):bit==1?(want?JitClassFile.IFGT:JitClassFile.IFLE):(want?JitClassFile.IFEQ:JitClassFile.IFNE);
				jump=zero?base:base+(JitClassFile.IF_ICMPEQ-JitClassFile.IFEQ);
				c.jump(negate?JitClassFile.negate(jump):jump,target);
				return;
			}
			ldCr(field);ic(8>>>bit);op(JitClassFile.IAND);
			c.jump(want^negate?JitClassFile.IFNE:JitClassFile.IFEQ,target);
		}
		/** Jumps to {@code target} when the branch's condition (BO, BI) holds, or does not with {@code negate}; CTR already decremented. */
		private void condition(int bo,int bi,boolean negate,JitClassFile.Label target){
			boolean useCtr=(bo&4)==0,useCond=(bo&16)==0;
			boolean ctrWantZero=(bo&2)!=0,want=((bo>>>3)&1)!=0;
			if(useCtr&&useCond){
				if(negate){ldCtr();c.jump(ctrWantZero?JitClassFile.IFNE:JitClassFile.IFEQ,target);bitCondition(bi,want,true,target);}
				else{JitClassFile.Label skip=c.label();ldCtr();c.jump(ctrWantZero?JitClassFile.IFNE:JitClassFile.IFEQ,skip);bitCondition(bi,want,false,target);c.bind(skip);}
			}else if(useCtr){ldCtr();c.jump(ctrWantZero^negate?JitClassFile.IFEQ:JitClassFile.IFNE,target);}
			else bitCondition(bi,want,negate,target);
		}
		private boolean bc(int op,int at){
			int bo=(op>>>21)&31,bi=(op>>>16)&31;int target=((op&2)!=0?0:at)+(short)(op&0xfffc);
			if((bo&4)==0){ldCtr();ic(-1);op(JitClassFile.IADD);stCtr();}
			if((op&1)!=0){ic(at+4);stLr();}
			JitClassFile.Label to=targetLabel(target,at);
			if((bo&0x14)==0x14){c.jump(JitClassFile.GOTO,to);return false;}
			condition(bo,bi,false,to);
			return true;
		}
		private boolean op19(int op,int at){
			int bt=(op>>>21)&31,ba=(op>>>16)&31,bb=(op>>>11)&31;
			switch((op>>>1)&0x3ff){
				case 0:ldCr(ba>>>2);stCr(bt>>>2);return true; // mcrf
				case 16:case 528:{ // bclr, bcctr
					int bo=bt;boolean toCtr=((op>>>1)&0x3ff)==528;
					if(!toCtr&&(bo&4)==0){ldCtr();ic(-1);op(JitClassFile.IADD);stCtr();}
					if(toCtr)ldCtr();else ldLr();ic(~3);op(JitClassFile.IAND);c.var(JitClassFile.ISTORE,lT0);setCur(at);
					if((op&1)!=0){ic(at+4);stLr();}
					boolean always=toCtr?(bo&16)!=0:(bo&0x14)==0x14;
					if(always){c.jump(JitClassFile.GOTO,dispatchLabel);return false;}
					if(toCtr)bo|=4; // bcctr never tests CTR
					condition(bo,ba,false,dispatchLabel);
					return true;}
				case 150:return true; // isync
				case 33:case 129:case 193:case 225:case 257:case 289:case 417:case 449:crLogical((op>>>1)&0x3ff,bt,ba,bb);return true;
				default:throw new IllegalStateException();
			}
		}
		private void crBit(int b){ldCr(b>>>2);int sh=3-(b&3);if(sh!=0){ic(sh);op(JitClassFile.IUSHR);}ic(1);op(JitClassFile.IAND);}
		private void crLogical(int xo,int bt,int ba,int bb){
			int field=bt>>>2,m=8>>>(bt&3);
			if(xo==193&&ba==bt&&bb==bt){ldCr(field);ic(~m&15);op(JitClassFile.IAND);stCr(field);return;} // crclr
			if(xo==289&&ba==bt&&bb==bt){ldCr(field);ic(m);op(JitClassFile.IOR);stCr(field);return;} // crset
			crBit(ba);
			if(xo==129||xo==417){crBit(bb);ic(1);op(JitClassFile.IXOR);} // the complement of bB
			else crBit(bb);
			switch(xo){
				case 33:op(JitClassFile.IOR);ic(1);op(JitClassFile.IXOR);break; // crnor
				case 129:op(JitClassFile.IAND);break; // crandc
				case 193:op(JitClassFile.IXOR);break; // crxor
				case 225:op(JitClassFile.IAND);ic(1);op(JitClassFile.IXOR);break; // crnand
				case 257:op(JitClassFile.IAND);break; // crand
				case 289:op(JitClassFile.IXOR);ic(1);op(JitClassFile.IXOR);break; // creqv
				case 417:op(JitClassFile.IOR);break; // crorc
				case 449:op(JitClassFile.IOR);break; // cror
				default:throw new IllegalStateException();
			}
			int sh=3-(bt&3);if(sh!=0){ic(sh);op(JitClassFile.ISHL);}
			ldCr(field);ic(~m&15);op(JitClassFile.IAND);op(JitClassFile.IOR);stCr(field);
		}

		// ---- opcode 31

		private boolean op31(int op,int at){
			int rd=(op>>>21)&31,ra=(op>>>16)&31,rb=(op>>>11)&31;boolean rc=(op&1)!=0;
			switch((op>>>1)&0x3ff){
				case 0:ld(ra);ld(rb);ldXer();c.invokestatic(JIT,"cmps","(III)I");stCr(rd>>>2);fuse(rd>>>2,1,ra,false,rb);return true; // cmp
				case 32:ld(ra);ld(rb);ldXer();c.invokestatic(JIT,"cmpu","(III)I");stCr(rd>>>2);fuse(rd>>>2,2,ra,false,rb);return true; // cmpl
				case 4:{if(rd!=0){ic(rd);ld(ra);ld(rb);c.invokestatic(JIT,"trap","(III)Z");c.jump(JitClassFile.IFNE,interpretAt(at));}return true;} // tw
				case 8:longSum(ra,true,1,false);addUnsigned(rb);carryResult(rd,rc);return true; // subfc
				case 10:longSum(ra,false,0,false);addUnsigned(rb);carryResult(rd,rc);return true; // addc
				case 11:ld(ra);op(JitClassFile.I2L);c.lconst(0xffffffffL);op(JitClassFile.LAND);ld(rb);op(JitClassFile.I2L);c.lconst(0xffffffffL);op(JitClassFile.LAND);op(JitClassFile.LMUL);ic(32);op(JitClassFile.LUSHR);op(JitClassFile.L2I);st(rd);if(rc)cr0(rd);return true; // mulhwu
				case 75:ld(ra);op(JitClassFile.I2L);ld(rb);op(JitClassFile.I2L);op(JitClassFile.LMUL);ic(32);op(JitClassFile.LUSHR);op(JitClassFile.L2I);st(rd);if(rc)cr0(rd);return true; // mulhw
				case 40:ld(rb);ld(ra);op(JitClassFile.ISUB);st(rd);if(rc)cr0(rd);return true; // subf
				case 104:ld(ra);op(JitClassFile.INEG);st(rd);if(rc)cr0(rd);return true; // neg
				case 136:longSum(ra,true,0,true);addUnsigned(rb);carryResult(rd,rc);return true; // subfe
				case 138:longSum(ra,false,0,true);addUnsigned(rb);carryResult(rd,rc);return true; // adde
				case 200:longSum(ra,true,0,true);carryResult(rd,rc);return true; // subfze
				case 202:longSum(ra,false,0,true);carryResult(rd,rc);return true; // addze
				case 232:longSum(ra,true,0xffffffffL,true);carryResult(rd,rc);return true; // subfme
				case 234:longSum(ra,false,0xffffffffL,true);carryResult(rd,rc);return true; // addme
				case 235:ld(ra);ld(rb);op(JitClassFile.IMUL);st(rd);if(rc)cr0(rd);return true; // mullw
				case 266:ld(ra);ld(rb);op(JitClassFile.IADD);st(rd);if(rc)cr0(rd);return true; // add
				case 459:ld(ra);ld(rb);c.invokestatic(JIT,"divwu","(II)I");st(rd);if(rc)cr0(rd);return true; // divwu
				case 491:ld(ra);ld(rb);c.invokestatic(JIT,"divw","(II)I");st(rd);if(rc)cr0(rd);return true; // divw
				// logical (rS is the rd field)
				case 28:ld(rd);ld(rb);op(JitClassFile.IAND);st(ra);if(rc)cr0(ra);return true; // and
				case 60:ld(rd);ld(rb);ic(-1);op(JitClassFile.IXOR);op(JitClassFile.IAND);st(ra);if(rc)cr0(ra);return true; // andc
				case 124:ld(rd);ld(rb);op(JitClassFile.IOR);ic(-1);op(JitClassFile.IXOR);st(ra);if(rc)cr0(ra);return true; // nor
				case 284:ld(rd);ld(rb);op(JitClassFile.IXOR);ic(-1);op(JitClassFile.IXOR);st(ra);if(rc)cr0(ra);return true; // eqv
				case 316:ld(rd);ld(rb);op(JitClassFile.IXOR);st(ra);if(rc)cr0(ra);return true; // xor
				case 412:ld(rd);ld(rb);ic(-1);op(JitClassFile.IXOR);op(JitClassFile.IOR);st(ra);if(rc)cr0(ra);return true; // orc
				case 444:ld(rd);if(rb!=rd){ld(rb);op(JitClassFile.IOR);}st(ra);if(rc)cr0(ra);return true; // or (mr)
				case 476:ld(rd);ld(rb);op(JitClassFile.IAND);ic(-1);op(JitClassFile.IXOR);st(ra);if(rc)cr0(ra);return true; // nand
				case 26:ld(rd);c.invokestatic("java/lang/Integer","numberOfLeadingZeros","(I)I");st(ra);if(rc)cr0(ra);return true; // cntlzw
				case 922:ld(rd);op(JitClassFile.I2S);st(ra);if(rc)cr0(ra);return true; // extsh
				case 954:ld(rd);op(JitClassFile.I2B);st(ra);if(rc)cr0(ra);return true; // extsb
				case 24:ld(rd);ld(rb);c.invokestatic(JIT,"slw","(II)I");st(ra);if(rc)cr0(ra);return true; // slw
				case 536:ld(rd);ld(rb);c.invokestatic(JIT,"srw","(II)I");st(ra);if(rc)cr0(ra);return true; // srw
				case 792:ld(rd);ld(rb);c.invokestatic(JIT,"srawCa","(II)I");c.var(JitClassFile.ISTORE,lT1);ld(rd);ld(rb);c.invokestatic(JIT,"sraw","(II)I");c.var(JitClassFile.ILOAD,lT1);stCa();st(ra);if(rc)cr0(ra);return true; // sraw
				case 824:{int n=rb; // srawi
					if(n==0){ic(0);stCa();ld(rd);st(ra);}
					else{ld(rd);ic((1<<n)-1);c.invokestatic(JIT,"srawiCa","(II)I");stCa();ld(rd);ic(n);op(JitClassFile.ISHR);st(ra);}
					if(rc)cr0(ra);return true;}
				case 19:for(int n=0;n<8;n++){ldCr(n);if(n<7){ic(28-4*n);op(JitClassFile.ISHL);}if(n>0)op(JitClassFile.IOR);}st(rd);return true; // mfcr
				case 144:{int fxm=(op>>>12)&0xff;for(int n=0;n<8;n++)if((fxm&(0x80>>>n))!=0){ld(rd);if(n<7){ic(28-4*n);op(JitClassFile.IUSHR);}if(n>0){ic(15);op(JitClassFile.IAND);}stCr(n);}return true;} // mtcrf
				case 512:ldXerFull();ic(28);op(JitClassFile.IUSHR);stCr(rd>>>2);ldXer();ic(0x0fffffff);op(JitClassFile.IAND);stXer();ic(0);stCa();return true; // mcrxr
				case 339:{int spr=spr(op); // mfspr
					if(spr==1)ldXerFull();else if(spr==8)ldLr();else if(spr==9)ldCtr();
					else{c.var(JitClassFile.ALOAD,lCpu);ic(spr);c.invokevirtual(CPU,"mfspr","(I)I");}
					st(rd);return true;}
				case 467:{int spr=spr(op); // mtspr
					if(spr==1){ld(rd);stXer();ld(rd);ic(29);op(JitClassFile.IUSHR);ic(1);op(JitClassFile.IAND);stCa();}
					else if(spr==8){ld(rd);stLr();}else if(spr==9){ld(rd);stCtr();}
					return true;}
				case 371:c.var(JitClassFile.ALOAD,lCpu);ic(spr(op));c.invokevirtual(CPU,"mftb","(I)I");st(rd);return true; // mftb
				case 83:ic(0x0000f032);st(rd);return true; // mfmsr
				case 146:case 210:case 242:case 306:case 370:case 566:case 470:return true; // supervisor no-ops
				case 595:case 659:ic(0);st(rd);return true; // mfsr, mfsrin
				// loads and stores, indexed
				case 23:loadX(at,ra,rb,"readLong",rd,0);return true; // lwzx
				case 55:loadUX(at,ra,rb,"readLong",rd,false);return true; // lwzux
				case 87:loadX(at,ra,rb,"readByte",rd,0);return true; // lbzx
				case 119:loadUX(at,ra,rb,"readByte",rd,false);return true; // lbzux
				case 279:loadX(at,ra,rb,"readWord",rd,0);return true; // lhzx
				case 311:loadUX(at,ra,rb,"readWord",rd,false);return true; // lhzux
				case 343:loadX(at,ra,rb,"readWord",rd,1);return true; // lhax
				case 375:loadUX(at,ra,rb,"readWord",rd,true);return true; // lhaux
				case 151:storeX(at,ra,rb,"store32",rd,0);return true; // stwx
				case 183:storeUX(at,ra,rb,"store32",rd);return true; // stwux
				case 215:storeX(at,ra,rb,"store8",rd,0);return true; // stbx
				case 247:storeUX(at,ra,rb,"store8",rd);return true; // stbux
				case 407:storeX(at,ra,rb,"store16",rd,0);return true; // sthx
				case 439:storeUX(at,ra,rb,"store16",rd);return true; // sthux
				case 534:loadX(at,ra,rb,"readLong",rd,2);return true; // lwbrx
				case 790:loadX(at,ra,rb,"readWord",rd,3);return true; // lhbrx
				case 662:storeX(at,ra,rb,"store32",rd,2);return true; // stwbrx
				case 918:storeX(at,ra,rb,"store16",rd,3);return true; // sthbrx
				case 20:loadX(at,ra,rb,"readLong",rd,0);c.var(JitClassFile.ALOAD,lCpu);ic(1);c.putfield(CPU,"reservation","Z");return true; // lwarx
				case 150:{ // stwcx.
					JitClassFile.Label none=c.label(),done=c.label();
					c.var(JitClassFile.ALOAD,lCpu);c.getfield(CPU,"reservation","Z");c.jump(JitClassFile.IFEQ,none);
					storeX(at,ra,rb,"store32",rd,0);
					ic(2);ldXer();ic(31);op(JitClassFile.IUSHR);op(JitClassFile.IOR);stCr(0);c.jump(JitClassFile.GOTO,done);
					c.bind(none);ldXer();ic(31);op(JitClassFile.IUSHR);stCr(0);
					c.bind(done);c.var(JitClassFile.ALOAD,lCpu);ic(0);c.putfield(CPU,"reservation","Z");
					return true;}
				case 597:{ // lswi
					int n=rb==0?32:rb;setCur(at);ld0(ra);c.var(JitClassFile.ISTORE,lT0);
					int reg=rd-1;
					for(int k=0;k<n;k+=4){reg=(reg+1)&31;
						for(int j=0;j<4&&k+j<n;j++){c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);ic(k+j);op(JitClassFile.IADD);readMem("readByte");if(j<3){ic(24-8*j);op(JitClassFile.ISHL);}if(j>0)op(JitClassFile.IOR);}
						st(reg);}
					return true;}
				case 725:{ // stswi
					int n=rb==0?32:rb;setCur(at);ld0(ra);c.var(JitClassFile.ISTORE,lT0);
					int reg=rd-1;
					for(int k=0;k<n;k++){if((k&3)==0)reg=(reg+1)&31;
						c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);ic(k);op(JitClassFile.IADD);ld(reg);if((k&3)<3){ic(24-8*(k&3));op(JitClassFile.IUSHR);}store("store8");}
					return true;}
				case 535:setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEaX(ra,rb);readMem("readLong");c.invokestatic(JIT,"lfs","(I)D");stF(rd);return true; // lfsx
				case 567:setCur(at);ld(ra);ld(rb);op(JitClassFile.IADD);c.var(JitClassFile.ISTORE,lT0);c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);readMem("readLong");c.invokestatic(JIT,"lfs","(I)D");stF(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // lfsux
				case 599:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);readDouble();stF(rd);return true; // lfdx
				case 631:setCur(at);ld(ra);ld(rb);op(JitClassFile.IADD);c.var(JitClassFile.ISTORE,lT0);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);readDouble();stF(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // lfdux
				case 663:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);ldF(rd);c.invokestatic(JIT,"stfs","(D)I");store("store32");return true; // stfsx
				case 695:setCur(at);ld(ra);ld(rb);op(JitClassFile.IADD);c.var(JitClassFile.ISTORE,lT0);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);ldF(rd);c.invokestatic(JIT,"stfs","(D)I");store("store32");c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // stfsux
				case 727:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);writeDouble(rd);return true; // stfdx
				case 759:setCur(at);ld(ra);ld(rb);op(JitClassFile.IADD);c.var(JitClassFile.ISTORE,lT0);c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);writeDouble(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);return true; // stfdux
				case 983:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);ldF(rd);c.invokestatic(JIT,"low","(D)I");store("store32");return true; // stfiwx
				case 1014:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);c.invokevirtual(CPU,"dcbz","(I)V");return true; // dcbz
				case 982:setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);c.invokestatic(JIT,"icbi","(LPpcCpu;I)V");c.jump(JitClassFile.GOTO,exitTo(at+4));return false; // icbi: leave (the code may have changed)
				case 54:case 86:case 246:case 278:case 598:case 854:return true; // dcbst, dcbf, dcbtst, dcbt, sync, eieio
				default:throw new IllegalStateException("not compilable: "+Integer.toHexString(op));
			}
		}
		/** Indexed load into rD: {@code form} 0 plain, 1 sign-extended halfword, 2 byte-reversed word, 3 byte-reversed halfword. */
		private void loadX(int at,int ra,int rb,String read,int rd,int form){
			setCur(at);c.var(JitClassFile.ALOAD,lMem);loadEaX(ra,rb);readMem(read);
			if(form==1)op(JitClassFile.I2S);
			else if(form==2)c.invokestatic("java/lang/Integer","reverseBytes","(I)I");
			else if(form==3){c.invokestatic("java/lang/Integer","reverseBytes","(I)I");ic(16);op(JitClassFile.IUSHR);}
			st(rd);
		}
		private void loadUX(int at,int ra,int rb,String read,int rd,boolean half){
			setCur(at);ld(ra);ld(rb);op(JitClassFile.IADD);c.var(JitClassFile.ISTORE,lT0);
			c.var(JitClassFile.ALOAD,lMem);c.var(JitClassFile.ILOAD,lT0);readMem(read);if(half)op(JitClassFile.I2S);st(rd);c.var(JitClassFile.ILOAD,lT0);st(ra);
		}
		/** Indexed store of rS: {@code form} 0 plain, 2 byte-reversed word, 3 byte-reversed halfword. */
		private void storeX(int at,int ra,int rb,String write,int rs,int form){
			setCur(at);c.var(JitClassFile.ALOAD,lCpu);loadEaX(ra,rb);ld(rs);
			if(form==2)c.invokestatic("java/lang/Integer","reverseBytes","(I)I");
			else if(form==3){c.invokestatic("java/lang/Integer","reverseBytes","(I)I");ic(16);op(JitClassFile.IUSHR);}
			store(write);
		}
		private void storeUX(int at,int ra,int rb,String write,int rs){
			setCur(at);ld(ra);ld(rb);op(JitClassFile.IADD);c.var(JitClassFile.ISTORE,lT0);
			c.var(JitClassFile.ALOAD,lCpu);c.var(JitClassFile.ILOAD,lT0);ld(rs);store(write);c.var(JitClassFile.ILOAD,lT0);st(ra);
		}

		// ---- floating point

		private void fma(int a,int cc,int b,boolean negateB,boolean negateResult){
			ldF(a);ldF(cc);ldF(b);if(negateB)op(JitClassFile.DNEG);
			c.invokestatic("java/lang/Math","fma","(DDD)D");
			if(negateResult)op(JitClassFile.DNEG);
		}
		private void op59(int op){
			int d=(op>>>21)&31,a=(op>>>16)&31,b=(op>>>11)&31,cc=(op>>>6)&31;boolean rc=(op&1)!=0;
			switch((op>>>1)&31){
				case 18:ldF(a);ldF(b);op(JitClassFile.DDIV);setS(d,rc);return; // fdivs
				case 20:ldF(a);ldF(b);op(JitClassFile.DSUB);setS(d,rc);return; // fsubs
				case 21:ldF(a);ldF(b);op(JitClassFile.DADD);setS(d,rc);return; // fadds
				case 22:ldF(b);c.invokestatic("java/lang/Math","sqrt","(D)D");setS(d,rc);return; // fsqrts
				case 24:c.dconst(1.0);ldF(b);op(JitClassFile.DDIV);setS(d,rc);return; // fres
				case 25:ldF(a);ldF(cc);op(JitClassFile.DMUL);setS(d,rc);return; // fmuls
				case 28:fma(a,cc,b,true,false);setS(d,rc);return; // fmsubs
				case 29:fma(a,cc,b,false,false);setS(d,rc);return; // fmadds
				case 30:fma(a,cc,b,true,true);setS(d,rc);return; // fnmsubs
				case 31:fma(a,cc,b,false,true);setS(d,rc);return; // fnmadds
				default:throw new IllegalStateException();
			}
		}
		private void op63(int op){
			int d=(op>>>21)&31,a=(op>>>16)&31,b=(op>>>11)&31,cc=(op>>>6)&31;boolean rc=(op&1)!=0;
			switch((op>>>1)&31){ // A-form
				case 18:ldF(a);ldF(b);op(JitClassFile.DDIV);setF(d,rc);return; // fdiv
				case 20:ldF(a);ldF(b);op(JitClassFile.DSUB);setF(d,rc);return; // fsub
				case 21:ldF(a);ldF(b);op(JitClassFile.DADD);setF(d,rc);return; // fadd
				case 22:ldF(b);c.invokestatic("java/lang/Math","sqrt","(D)D");setF(d,rc);return; // fsqrt
				case 23:{JitClassFile.Label other=c.label(),done=c.label(); // fsel (NaN selects frB)
					ldF(a);c.dconst(0);op(JitClassFile.DCMPL);c.jump(JitClassFile.IFLT,other);
					ldF(cc);stF(d);c.jump(JitClassFile.GOTO,done);
					c.bind(other);ldF(b);stF(d);c.bind(done);
					if(rc)cr1();return;}
				case 25:ldF(a);ldF(cc);op(JitClassFile.DMUL);setF(d,rc);return; // fmul
				case 26:c.dconst(1.0);ldF(b);c.invokestatic("java/lang/Math","sqrt","(D)D");op(JitClassFile.DDIV);setF(d,rc);return; // frsqrte
				case 28:fma(a,cc,b,true,false);setF(d,rc);return; // fmsub
				case 29:fma(a,cc,b,false,false);setF(d,rc);return; // fmadd
				case 30:fma(a,cc,b,true,true);setF(d,rc);return; // fnmsub
				case 31:fma(a,cc,b,false,true);setF(d,rc);return; // fnmadd
				default:break;
			}
			switch((op>>>1)&0x3ff){ // X-form
				case 0:case 32:settleFprf();ldF(a);ldF(b);c.invokestatic(JIT,"fcmp","(DD)I");c.var(JitClassFile.ISTORE,lT1); // fcmpu, fcmpo
					c.var(JitClassFile.ILOAD,lT1);stCr(d>>>2);
					ldFpscr();ic(~0x0000f000);op(JitClassFile.IAND);c.var(JitClassFile.ILOAD,lT1);ic(12);op(JitClassFile.ISHL);op(JitClassFile.IOR);stFpscr();return;
				case 12:ldF(b);c.invokestatic(JIT,"frsp","(D)D");setF(d,rc);return; // frsp
				case 14:case 15:ldF(b);if(((op>>>1)&0x3ff)==15)ic(1);else{ldFpscr();ic(3);op(JitClassFile.IAND);}c.invokestatic(JIT,"fctiw","(DI)D");stF(d);if(rc)cr1();return; // fctiw, fctiwz
				case 38:settleFprf();ldFpscr();ic(1<<(31-d));op(JitClassFile.IOR);stFpscr();if(rc)cr1();return; // mtfsb1
				case 70:settleFprf();ldFpscr();ic(~(1<<(31-d)));op(JitClassFile.IAND);stFpscr();if(rc)cr1();return; // mtfsb0
				case 40:ldF(b);c.invokestatic(JIT,"fneg","(D)D");stF(d);if(rc)cr1();return; // fneg
				case 72:ldF(b);stF(d);if(rc)cr1();return; // fmr
				case 136:ldF(b);c.invokestatic(JIT,"fnabs","(D)D");stF(d);if(rc)cr1();return; // fnabs
				case 264:ldF(b);c.invokestatic(JIT,"fabs","(D)D");stF(d);if(rc)cr1();return; // fabs
				case 64:{int sf=28-4*(a>>>2);settleFprf(); // mcrfs
					ldFpscr();if(sf!=0){ic(sf);op(JitClassFile.IUSHR);}ic(15);op(JitClassFile.IAND);stCr(d>>>2);
					if(sf!=0){ldFpscr();ic(~((sf==28?0x9:15)<<sf));op(JitClassFile.IAND);stFpscr();}
					return;}
				case 134:{int sh=28-4*(d>>>2),v=(op>>>12)&15;settleFprf();ldFpscr();ic(~(15<<sh));op(JitClassFile.IAND);ic(v<<sh);op(JitClassFile.IOR);stFpscr();if(rc)cr1();return;} // mtfsfi
				case 583:settleFprf();ldFpscr();c.invokestatic(JIT,"mffs","(I)D");stF(d);if(rc)cr1();return; // mffs
				case 711:settleFprf();ldFpscr();ic((op>>>17)&0xff);ldF(b);c.invokestatic(JIT,"low","(D)I");c.invokestatic(JIT,"mtfsf","(III)I");stFpscr();if(rc)cr1();return; // mtfsf
				default:throw new IllegalStateException();
			}
		}
	}

	// ------------------------------------------------------------------ helpers called by the generated code

	static int mask(int mb,int me){int a=-1>>>mb,b=-1<<(31-me);return mb<=me?a&b:a|b;}
	/** Whether {@code target} holds a host gate (compiled code calls it in place, see {@link PpcCpu#jitGate}). */
	static boolean isGate(M68kMemory m,int target){
		if((target&3)!=0||m.physical(target)!=target||target>m.size()-4)return false;
		try{return m.readLong(target)>>>26==PpcCpu.GATE_OPCODE;}catch(M68kCpuException e){return false;}
	}
	static int cmps(int a,int b,int xer){return (a<b?8:a>b?4:2)|(xer>>>31);}
	static int cmpu(int a,int b,int xer){int c=Integer.compareUnsigned(a,b);return (c<0?8:c>0?4:2)|(xer>>>31);}
	static int cr0(int v,int xer){return (v<0?8:v>0?4:2)|(xer>>>31);}
	static boolean trap(int to,int a,int b){
		return (to&16)!=0&&a<b||(to&8)!=0&&a>b||(to&4)!=0&&a==b||(to&2)!=0&&Integer.compareUnsigned(a,b)<0||(to&1)!=0&&Integer.compareUnsigned(a,b)>0;
	}
	static int divw(int a,int b){boolean bad=b==0||a==Integer.MIN_VALUE&&b==-1;return bad?(a<0&&b==0?-1:0):a/b;}
	static int divwu(int a,int b){return b==0?0:Integer.divideUnsigned(a,b);}
	static int slw(int s,int b){int n=b&63;return n>31?0:s<<n;}
	static int srw(int s,int b){int n=b&63;return n>31?0:s>>>n;}
	static int sraw(int s,int b){int n=b&63;return n>31?s>>31:s>>n;}
	static int srawCa(int s,int b){int n=b&63;return (n>31?s<0:s<0&&n>0&&(s&((1<<n)-1))!=0)?1:0;}
	static int srawiCa(int s,int mask){return s<0&&(s&mask)!=0?1:0;}
	static double lfs(int bits){return Double.longBitsToDouble(PpcCpu.singleToDouble(bits));}
	static int stfs(double v){return PpcCpu.doubleToSingle(Double.doubleToRawLongBits(v));}
	static int low(double v){return (int)Double.doubleToRawLongBits(v);}
	static double frsp(double v){return Double.longBitsToDouble(PpcCpu.singleToDouble(PpcCpu.doubleToSingle(Double.doubleToRawLongBits(v))));}
	static double fneg(double v){return Double.longBitsToDouble(Double.doubleToRawLongBits(v)^0x8000000000000000L);}
	static double fabs(double v){return Double.longBitsToDouble(Double.doubleToRawLongBits(v)&0x7fffffffffffffffL);}
	static double fnabs(double v){return Double.longBitsToDouble(Double.doubleToRawLongBits(v)|0x8000000000000000L);}
	static double fctiw(double v,int mode){return Double.longBitsToDouble(0xfff8000000000000L|(PpcCpu.toInt(v,mode)&0xffffffffL));}
	static double mffs(int fpscr){return Double.longBitsToDouble(0xfff8000000000000L|(fpscr&0xffffffffL));}
	static int fcmp(double x,double y){return Double.isNaN(x)||Double.isNaN(y)?1:x<y?8:x>y?4:2;}
	static int mtfsf(int fpscr,int fm,int v){for(int i=0;i<8;i++)if((fm&(0x80>>>i))!=0){int m=0xf0000000>>>(4*i);fpscr=(fpscr&~m)|(v&m);}return fpscr;}
	/** FPSCR[FPRF] for the result {@code v} (PpcCpu.setFprf), when {@code pending}. */
	static int fprfIf(int fpscr,int pending,double v){
		if(pending==0)return fpscr;
		int c;
		if(Double.isNaN(v))c=0x11;else if(Double.isInfinite(v))c=v<0?0x09:0x05;
		else if(v==0)c=(Double.doubleToRawLongBits(v)<0)?0x12:0x02;
		else if(Math.abs(v)<Double.MIN_NORMAL)c=v<0?0x18:0x14;else c=v<0?0x08:0x04;
		return (fpscr&~0x0001f000)|(c<<12);
	}
	static void icbi(PpcCpu cpu,int ea){PpcJit j=cpu.jit;if(j!=null)j.invalidate(ea&~31,32);}
}

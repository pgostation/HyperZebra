import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Big-endian, bounds-checked address space used by classic 68K code.
 *
 * The default instance is the 16 MB (24-bit) space used by XCMDs; applications
 * may ask for a larger 32-bit-clean space. Block bookkeeping for the heap lives
 * on the Java side (no headers inside emulated memory), so a misbehaving program
 * cannot corrupt the allocator. The last megabyte is the "ROM": trap entry
 * addresses and host gates live there and nothing is ever allocated in it.
 */
final class M68kMemory {
	static final int SIZE = 16 * 1024 * 1024;
	static final int ROM_SIZE = 0x00100000;
	private byte[] bytes;
	private int size, mask;
	/**
	 * Pages (4 KB) the program write-protected with ProtectMemory (_MemoryDispatch selector 6; UnprotectMemory, 7, lifts it).
	 * Only PowerPC stores check them (PpcCpu: a readOnlyMemoryException for the program's exception handler); Toolbox writes
	 * and 68K code are not stopped. Macintosh Common Lisp 4 guards its stacks and heap end this way.
	 */
	private final java.util.BitSet protectedPages=new java.util.BitSet();
	/** How many pages are protected (0 = the stores' fast path). */
	int protectedPageCount;
	synchronized void protect(int address,int length,boolean on){
		if(length<=0)return;
		int first=address>>>12,last=(int)(((address&0xffffffffL)+length-1)>>>12);
		if(on)protectedPages.set(first,last+1);else protectedPages.clear(first,last+1);
		protectedPageCount=protectedPages.cardinality();
	}
	/** Whether a store of {@code length} bytes at {@code address} touches a protected page. */
	boolean writeProtected(int address,int length){
		return protectedPages.get(address>>>12)||protectedPages.get((int)(((address&0xffffffffL)+length-1)>>>12));
	}
	// ------------------------------------------------------------------ compiled PowerPC code
	/** Writes into memory holding compiled PowerPC code are reported to the JIT (PpcJit), which drops that code. */
	interface CodeListener { void codeWritten(int address,int length); }
	/** The lines {@link #codeMarks} has a byte for: 64 bytes. */
	static final int CODE_SHIFT=6;
	private CodeListener codeListener;
	/** Non-zero for each line that holds compiled code (the listener sets and clears them); null while nothing is compiled. */
	private byte[] codeMarks;
	synchronized void setCodeListener(CodeListener l){codeListener=l;if(l!=null&&codeMarks==null)codeMarks=new byte[(size>>>CODE_SHIFT)+1];}
	CodeListener codeListener(){return codeListener;}
	byte[] codeMarks(){return codeMarks;}
	/** A write of {@code length} bytes at {@code address} into a marked line. */
	private void codeWritten(int address,int length){CodeListener l=codeListener;if(l!=null)l.codeWritten(address,length);}
	/** A bulk write (load, copy, fill, a block allocated or freed): reported when any of its lines holds compiled code. */
	private void codeRange(int address,int length){
		byte[] m=codeMarks;if(m==null||length<=0)return;
		int first=address>>>CODE_SHIFT,last=(int)Math.min(m.length-1,((address&0xffffffffL)+length-1)>>>CODE_SHIFT);
		for(int line=first;line<=last;line++)if(m[line]!=0){codeWritten(address,length);return;}
	}

	/** A program run in the 24-bit world (16 MB, the top address byte ignored; MacApp68k.addressing24): StripAddress masks with
	 *  $FFFFFF, and master pointers may carry the program's own flags in their top byte. */
	volatile boolean addressing24;
	private final int romBase;
	private int heapStart, heapEnd;
	/** Largest size the memory may grow to on demand (see {@link #setGrowable}); 0 for a fixed size. */
	private int growLimit;
	/** Free blocks by address (address -> size). */
	private final TreeMap<Integer,Integer> freeByAddress = new TreeMap<Integer,Integer>();
	/** Growth room kept free right after a block (block address -> length), for {@link #growInPlace} only: nothing else is placed there
	 *  until the block is freed. Stands in for the real heap's compaction, which moves relocatable blocks out of a growing Ptr's way. */
	private final Map<Integer,Integer> reservedAfter = new HashMap<Integer,Integer>();
	/** Free blocks by (size, address) for best-fit lookup. */
	private final TreeMap<Long,Boolean> freeBySize = new TreeMap<Long,Boolean>();
	/** The subset of {@link #freeBySize} that starts below {@link #LOW_LIMIT}: small blocks are placed there first. */
	private final TreeMap<Long,Boolean> freeBySizeLow = new TreeMap<Long,Boolean>();
	/** Small blocks stay below this address while there is room: code and records then lie in the first 16 MB, as on a real Mac
	 *  with that much memory - QuickTime's segment loader keeps flags in the top byte of its master pointers and masks the
	 *  addresses of its code Handles with $FFFFFF. */
	static final int LOW_LIMIT=0x01000000;
	/** The first bytes of the heap are a stand-in for the system heap: only {@link #allocateBelow} (QuickTime's code Handles,
	 *  which must lie in the first 16 MB) takes from them while the rest of the heap has room. A program that grabs everything
	 *  FreeMem reports (some audio programs size sample buffers from it) otherwise left no low memory for those Handles,
	 *  and QuickTime's trap patches jumped into garbage the next time they ran. */
	static final int SYSTEM_RESERVE=2*1024*1024;
	private int reserveEnd;
	/** Allocated blocks (address -> size). */
	private final Map<Integer,Integer> allocated = new HashMap<Integer,Integer>();
	/** Allocated blocks by end address (end -> start): where a block's trailer lies, for {@link #tagAt}. */
	private final Map<Integer,Integer> endsAt = new HashMap<Integer,Integer>();
	/** What the program asked for in each block (block -> size), when known: the rest of the block up to its end is spare. */
	private final Map<Integer,Integer> requested = new HashMap<Integer,Integer>();
	private void putBlock(int address,int length){if(FREE_WATCH!=0&&FREE_WATCH>=address&&FREE_WATCH<address+length)new Throwable("[68K] allocating watched block $"+Integer.toHexString(address)+" ("+length+" bytes)").printStackTrace();Integer old=allocated.put(address,length);if(old!=null)endsAt.remove(address+old);endsAt.put(address+length,address);requested.remove(address);}
	private Integer removeBlock(int address){Integer length=allocated.remove(address);if(length!=null)endsAt.remove(address+length);requested.remove(address);blockHandles.remove(address);return length;}
	private long freeBytes;

	M68kMemory() { this(SIZE, 0x00100000); }

	/** @param size power of two, at least 16 MB; @param heapStart first heap address. */
	M68kMemory(int size, int heapStart) {
		if(size<SIZE||Integer.bitCount(size)!=1)throw new IllegalArgumentException("memory size must be a power of two >= 16MB");
		this.size=size;bytes=new byte[size];mask=size-1;romBase=size-ROM_SIZE;spans();
		this.heapStart=heapStart;heapEnd=romBase;
		addFree(heapStart,heapEnd-heapStart);
	}

	// ------------------------------------------------------------------ one address space for several programs
	// Classic Mac OS runs every program in one address space: each in a partition of its own, low memory swapped per process.
	// A program launched into its launcher's space (MacApp68k.sharesAddressSpace) gets a memory of its own here - its own heap
	// books, ROM and low memory - over the SAME bytes: its partition lies above the launcher's memory, and each sees the other's
	// partition at the same addresses (the Apple Dylan IDE reads its Application Nub's runtime globals and stacks directly).
	/** Bytes [0, lowEnd) are this program's own (low memory and the zone header room before the first heap): 0 unless it was
	 *  launched into another program's address space. Accesses there take the slow path of the bounds check below. */
	private int lowEnd;private byte[] low;
	/** size-lowEnd-n for n = 1, 2, 4: an access is inside the shared bytes when (address-lowEnd) is at most this, unsigned. */
	private int span1,span2,span4;
	private void spans(){span1=size-lowEnd-1;span2=size-lowEnd-2;span4=size-lowEnd-4;}
	/** The partitions of one shared address space; the first program's own memory is none of them. */
	private static final class Space {
		final M68kMemory root;final java.util.List<Partition> partitions=new java.util.ArrayList<Partition>();
		Space(M68kMemory root){this.root=root;}
	}
	private static final class Partition {final int base,size;boolean inUse;Partition(int base,int size){this.base=base;this.size=size;}}
	private Space space;private Partition partition;
	/** Room below 16 MB the launcher lent this program (its "system heap" for {@link #allocateBelow}): start, length; 0 for none. */
	private int lentStart,lentLength;
	/** Bytes lent to a program launched into this space ({@link #lentStart}): taken from the launcher's heap below 16 MB. */
	static final int LENT_LOW=2*1024*1024;
	/** Where this program's partition starts (its application zone record), 0 when it has the address space to itself. */
	int partitionBase(){return partition==null?0:partition.base;}
	/** Bytes of this program's partition (MacApp68k sizes the A5 world and stack from it); the whole memory without one. */
	int partitionSize(){return partition==null?romBase+ROM_SIZE:partition.size;}
	/** Whether another program's memory shares these bytes. */
	boolean shared(){return space!=null;}

	/**
	 * A memory for a program this one launches into its own address space: a partition of {@code partitionSize} bytes above
	 * everything allocated so far (or one an earlier such program has left), low memory of its own below {@code privateLow}.
	 * Null when that is not possible now: 24-bit or growable memories, more than 1 GB, or a new partition needed while another
	 * program runs in this space (the bytes would have to be moved under it). Runs on this program's thread, in its _Launch.
	 */
	synchronized M68kMemory launchPartition(int partitionSize,int privateLow){
		if(addressing24||growLimit>0||partitionSize<SIZE||(partitionSize&0xfffff)!=0)return null;
		Space s=space!=null?space:new Space(this);
		Partition p=null;
		synchronized(s){
			for(Partition q:s.partitions)if(!q.inUse&&q.size>=partitionSize){p=q;break;}
			if(p==null){
				for(Partition q:s.partitions)if(q.inUse)return null;
				if(s.root!=this)return null; // only the first program's memory grows the space (it alone runs then)
				long total=Long.highestOneBit((long)size+partitionSize);if(total<(long)size+partitionSize)total<<=1;
				if(total>0x40000000L)return null;
				int base=size;
				try{bytes=Arrays.copyOf(bytes,(int)total);}catch(OutOfMemoryError e){return null;}
				size=(int)total;mask=size-1;spans();
				if(chunkSerial!=null){chunkSerial=Arrays.copyOf(chunkSerial,(size>>>CHUNK_SHIFT)+1);chunkTracked=Arrays.copyOf(chunkTracked,chunkSerial.length);superSerial=Arrays.copyOf(superSerial,(size>>>SUPER_SHIFT)+1);}
				if(codeMarks!=null)codeMarks=Arrays.copyOf(codeMarks,(size>>>CODE_SHIFT)+1);
				p=new Partition(base,partitionSize);s.partitions.add(p);
			}
			p.inUse=true;
		}
		space=s;
		M68kMemory m=new M68kMemory(s,p,s.root.bytes,s.root.size,privateLow);
		// QuickTime's code Handles must lie in the first 16 MB (see LOW_LIMIT): a little of the launcher's room there, if it has any
		try{int lent=allocateBelow(LENT_LOW,4,LOW_LIMIT);if(lent+LENT_LOW+trailer()<=LOW_LIMIT){m.lentStart=lent;m.lentLength=LENT_LOW;}else free(lent);}
		catch(M68kCpuException e){}
		return m;
	}
	private M68kMemory(Space s,Partition p,byte[] bytes,int size,int privateLow){
		space=s;partition=p;this.bytes=bytes;this.size=size;mask=size-1;romBase=p.base+p.size-ROM_SIZE;
		lowEnd=privateLow;low=new byte[privateLow];spans();
		heapStart=p.base;heapEnd=romBase;
		addFree(heapStart,heapEnd-heapStart);
	}
	/** The program launched into another's address space ended: its partition is free for the next one, the lent room goes back. */
	void releasePartition(){
		Space s=space;Partition p=partition;if(s==null||p==null)return;
		synchronized(s){p.inUse=false;}
		if(lentLength>0){M68kMemory root=s.root;synchronized(root){if(root.isAllocated(lentStart))root.free(lentStart);}lentLength=0;}
	}
	/** A byte of the shared bytes or of this program's own low memory. */
	private int byteAt(int address){return address<lowEnd?low[address]&0xff:bytes[address]&0xff;}
	private void putByte(int address,int value){if(address<lowEnd)low[address]=(byte)value;else bytes[address]=(byte)value;}
	/** An access the inline bounds test sent here: into this program's own low memory, else outside the address space. */
	private int readOutside(int address,int length) throws M68kCpuException {
		if(address>=lowEnd)check(address,length);
		if(address+length<=lowEnd){int v=0;for(int i=0;i<length;i++)v=v<<8|low[address+i]&0xff;return v;}
		check(address,length);int v=0;for(int i=0;i<length;i++)v=v<<8|byteAt(address+i);return v;
	}
	private void writeOutside(int address,int length,int value) throws M68kCpuException {
		if(address>=lowEnd||address+length>lowEnd)check(address,length);
		for(int i=0;i<length;i++)putByte(address+i,value>>>8*(length-1-i));
	}

	int size() { return size; }
	/** The RAM the program is told it has: the size the memory may grow to ({@link #setGrowable}), at least its size now. */
	int physicalSize() { return Math.max(size,growLimit); }
	int romBase() { return romBase; }
	/**
	 * Lets the memory grow, up to {@code limit} bytes, when an allocation does not fit: the space above the ROM is added to the
	 * heap. For externals (XCMDs), whose VMs start small because most run once per HyperTalk call: one that opens a screen-sized
	 * full-screen external window needs a 32-bit buffer, which on a large display is more than the
	 * whole 16 MB, and there is no telling beforehand which external will.
	 */
	void setGrowable(int limit) { growLimit=limit; }
	/** Grows the memory so that a free run of {@code needed} bytes lies above the current end; false when the limit is reached.
	 *  The array behind {@link #raw()} is replaced: its callers fetch it per use (the host converts window rows from it each tick). */
	private boolean grow(int needed) {
		if(growLimit<=size||space!=null)return false;
		long newSize=size;while(newSize-size<needed&&newSize<growLimit)newSize*=2;
		if(newSize-size<needed||newSize>growLimit)return false;
		int oldSize=size;bytes=java.util.Arrays.copyOf(bytes,(int)newSize);size=(int)newSize;mask=size-1;spans();
		if(chunkSerial!=null){chunkSerial=java.util.Arrays.copyOf(chunkSerial,(size>>>CHUNK_SHIFT)+1);chunkTracked=java.util.Arrays.copyOf(chunkTracked,chunkSerial.length);superSerial=java.util.Arrays.copyOf(superSerial,(size>>>SUPER_SHIFT)+1);}
		if(codeMarks!=null)codeMarks=java.util.Arrays.copyOf(codeMarks,(size>>>CODE_SHIFT)+1);
		addFree(oldSize,size-oldSize);
		return true;
	}
	int heapStart() { return heapStart; }
	int heapEnd() { return heapEnd; }
	/** Direct access for the host compositor; callers must not resize it. */
	byte[] raw() { return bytes; }

	/** Restricts the heap to [start,end); only valid right after construction (empty heap). */
	void setHeapRange(int start,int end) {
		if(!allocated.isEmpty())throw new IllegalStateException("heap already in use");
		freeByAddress.clear();freeBySize.clear();freeBySizeLow.clear();reservedAfter.clear();freeBytes=0;
		heapStart=start;heapEnd=Math.min(end,romBase);
		reserveEnd=heapEnd-heapStart>8*SYSTEM_RESERVE?heapStart+SYSTEM_RESERVE:0;
		if(lentLength>0){reserveEnd=lentStart+lentLength;addFree(lentStart,lentLength);} // the room lent below 16 MB is this program's system reserve
		addFree(heapStart,heapEnd-heapStart);
	}
	private boolean inReserve(int address,int length){return address+length<=reserveEnd;}

	// ------------------------------------------------------------------ device registers
	/** Registers of an emulated chip (MacIo68k: the VIAs and the SCC) at [ioStart, ioEnd): reads and writes there go through the
	 *  device, a byte at a time; the bytes behind them keep what was written last. */
	interface IoDevice { int read(int offset); void write(int offset,int value); }
	private int ioStart,ioEnd;private IoDevice io;
	void mapIo(int start,int length,IoDevice device){io=device;ioStart=start;ioEnd=device==null?0:start+length;}
	private int ioRead(int address,int length){int v=0;for(int i=0;i<length;i++){int x=address+i;v=v<<8|(x>=ioStart&&x<ioEnd?io.read(x-ioStart):bytes[x]&0xff);}return v;}
	private void ioWrite(int address,int length,int value){for(int i=0;i<length;i++){int x=address+i;if(x>=ioStart&&x<ioEnd)io.write(x-ioStart,value>>>8*(length-1-i)&0xff);}}

	int readByte(int address) throws M68kCpuException {
		address = normalize(address);
		if(Integer.compareUnsigned(address-lowEnd,span1)>0)return readOutside(address,1);
		if(address<ioEnd&&address>=ioStart)return io.read(address-ioStart);
		return bytes[address] & 0xff;
	}

	int readWord(int address) throws M68kCpuException {
		address = normalize(address);
		// the 68020 tolerates odd word accesses (the 68000 does not); programs compiled for 68020+ rely on it
		if(Integer.compareUnsigned(address-lowEnd,span2)>0)return readOutside(address,2);
		if(address<ioEnd&&address+2>ioStart)return ioRead(address,2);
		return (short)BIG_SHORT.get(bytes,address) & 0xffff;
	}

	int readLong(int address) throws M68kCpuException {
		address = normalize(address);
		if(Integer.compareUnsigned(address-lowEnd,span4)>0)return readOutside(address,4);
		if(address<ioEnd&&address+4>ioStart)return ioRead(address,4);
		return (int)BIG_INT.get(bytes,address);
	}
	/** Big-endian words and longs of the byte array: one host load or store each (with a byte swap) where HotSpot compiles them. */
	private static final java.lang.invoke.VarHandle BIG_SHORT=java.lang.invoke.MethodHandles.byteArrayViewVarHandle(short[].class,java.nio.ByteOrder.BIG_ENDIAN),
		BIG_INT=java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class,java.nio.ByteOrder.BIG_ENDIAN);

	/** Debug (-Dhz68k.writeprobe=LO,HI hex PC range): reports the address range of guest writes made by code in that range. */
	static final int[] WRITE_PROBE=parseProbe(System.getProperty("hz68k.writeprobe"));
	private static int[] parseProbe(String s){if(s==null)return null;String[] p=s.split(",");return new int[]{(int)Long.parseLong(p[0],16),(int)Long.parseLong(p[1],16)};}
	/** Current instruction address, maintained by the CPU only while the probe is on. */
	int probePc,probeSp;private long probeCount;private int probeMin=Integer.MAX_VALUE,probeMax;
	/** Debug (-Dhz68k.addrprobe=LO,HI hex): keeps the last writes into that address range (address, pc) for the crash dump. */
	static final int[] ADDR_PROBE=parseProbe(System.getProperty("hz68k.addrprobe"));
	/** -Dhz68k.addrprobe.trace=true: a Java stack trace for every probed write (host code and PowerPC stores included). */
	private static final boolean ADDR_PROBE_TRACE=Boolean.getBoolean("hz68k.addrprobe.trace");
	private final long[] addrRing=ADDR_PROBE==null?null:new long[1024];private int addrRingAt;
	private void addrProbe(int address,int length,long value){if(address+length<=ADDR_PROBE[0]||address>=ADDR_PROBE[1])return;if(ADDR_PROBE_TRACE)new Throwable(String.format("[68K] write $%X <- $%X",address,value)).printStackTrace();addrRing[addrRingAt++&1023]=((long)probePc<<40)|((long)(address&0xffffff)<<16)|(length<<12)|(value&0xfff);addrRingSp[(addrRingAt-1)&1023]=probeSp;}
	private final int[] addrRingSp=ADDR_PROBE==null?null:new int[1024];
	String describeAddrRing(){if(addrRing==null)return "";StringBuilder sb=new StringBuilder("last writes into the probed range (oldest first):");
		for(int i=0;i<1024;i++){int k=(addrRingAt+i)&1023;long e=addrRing[k];if(e==0)continue;sb.append(String.format("%n  $%06X <- pc $%X (%d bytes, low bits $%03X) sp $%X",(e>>>16)&0xffffff,e>>>40,(e>>>12)&15,e&0xfff,addrRingSp[k]));}return sb.toString();}
	private void probe(int address){if(probePc<WRITE_PROBE[0]||probePc>=WRITE_PROBE[1])return;
		if(address<probeMin)probeMin=address;if(address>probeMax)probeMax=address;
		++probeCount;boolean device=address>=deviceStart&&address<deviceEnd;
		if(device){int o=address-deviceStart,row=o/deviceRowBytes,col=o%deviceRowBytes;probeDeviceCount++;
			if(row<pbRow0)pbRow0=row;if(row>pbRow1)pbRow1=row;if(col<pbCol0)pbCol0=col;if(col>pbCol1)pbCol1=col;
			if(probeDeviceCount%20000==0){System.err.printf("[probe] device writes %d..%d: rows %d..%d bytes %d..%d (last pc $%X)%n",probeDeviceCount-19999,probeDeviceCount,pbRow0,pbRow1,pbCol0,pbCol1,probePc);pbRow0=pbCol0=Integer.MAX_VALUE;pbRow1=pbCol1=0;}}
		if(!device&&address<0x3000000&&probeOutside++<40){System.err.printf("[probe] non-device write $%X (pc $%X)%n",address,probePc);}
		if(probeCount%50000==1)System.err.printf("[probe] %d writes from $%X..$%X so far: $%X..$%X (this: $%X at pc $%X)%n",probeCount,WRITE_PROBE[0],WRITE_PROBE[1],probeMin,probeMax,address,probePc);}
	private int probeDeviceCount,probeOutside,pbRow0=Integer.MAX_VALUE,pbRow1,pbCol0=Integer.MAX_VALUE,pbCol1;
	void writeByte(int address, int value) throws M68kCpuException {
		address = normalize(address);if(WRITE_PROBE!=null)probe(address);if(ADDR_PROBE!=null)addrProbe(address,1,value);
		if(Integer.compareUnsigned(address-lowEnd,span1)>0){writeOutside(address,1,value);return;}
		bytes[address] = (byte)value;
		byte[] code=codeMarks;if(code!=null&&code[address>>>CODE_SHIFT]!=0)codeWritten(address,1);
		if(address<trackEnd&&address>=trackStart&&chunkTracked[address>>>CHUNK_SHIFT]!=0)touched(address,1);
		if(address<watchEnd&&address>=watchStart)watched(address,1);
		if(address<ioEnd&&address>=ioStart)io.write(address-ioStart,value&0xff);
	}

	void writeWord(int address, int value) throws M68kCpuException {
		address = normalize(address);if(WRITE_PROBE!=null)probe(address);if(ADDR_PROBE!=null)addrProbe(address,2,value);
		if(Integer.compareUnsigned(address-lowEnd,span2)>0){writeOutside(address,2,value);return;}
		BIG_SHORT.set(bytes,address,(short)value);
		byte[] code=codeMarks;if(code!=null&&code[address>>>CODE_SHIFT]!=0)codeWritten(address,2); // a line is marked from 3 bytes before its code
		if(address<trackEnd&&address+2>trackStart&&chunkTracked[address>>>CHUNK_SHIFT]!=0)touched(address,2);
		if(address<watchEnd&&address+2>watchStart)watched(address,2);
		if(address<ioEnd&&address+2>ioStart)ioWrite(address,2,value);
	}

	void writeLong(int address, int value) throws M68kCpuException {
		address = normalize(address);if(WRITE_PROBE!=null)probe(address);if(ADDR_PROBE!=null)addrProbe(address,4,value);
		if(Integer.compareUnsigned(address-lowEnd,span4)>0){writeOutside(address,4,value);return;}
		BIG_INT.set(bytes,address,value);
		byte[] code=codeMarks;if(code!=null&&code[address>>>CODE_SHIFT]!=0)codeWritten(address,4);
		if(address<trackEnd&&address+4>trackStart&&chunkTracked[address>>>CHUNK_SHIFT]!=0)touched(address,4);
		if(address<watchEnd&&address+4>watchStart)watched(address,4);
		if(address<ioEnd&&address+4>ioStart)ioWrite(address,4,value);
	}

	void load(int address, byte[] data) throws M68kCpuException { load(address, data, 0, data.length); }

	void load(int address, byte[] data, int offset, int length) throws M68kCpuException {
		address = normalize(address);
		check(address, length);
		if(ADDR_PROBE!=null&&address<ADDR_PROBE[1]&&address+length>ADDR_PROBE[0]){if(ADDR_PROBE_TRACE)new Throwable(String.format("[68K] load into probed range $%X +%d",address,length)).printStackTrace();else System.err.printf("[68K] load into probed range: $%X +%d pc $%X%n",address,length,probePc);}
		if(address<lowEnd){for(int i=0;i<length;i++)putByte(address+i,data[offset+i]);if(address+length<=lowEnd)return;}
		else System.arraycopy(data, offset, bytes, address, length);
		codeRange(address,length);
		if(address<trackEnd&&address+length>trackStart)touchedRange(address,length);
		if(address<watchEnd&&address+length>watchStart)watched(address,length);
	}

	/** Copies {@code length} bytes at {@code address} into {@code target[offset..]}. */
	void read(int address, byte[] target, int offset, int length) throws M68kCpuException {
		address = normalize(address);
		check(address, length);
		if(address<lowEnd){for(int i=0;i<length;i++)target[offset+i]=(byte)byteAt(address+i);return;}
		System.arraycopy(bytes, address, target, offset, length);
	}

	// ------------------------------------------------------------------ screen and window write tracking
	// Pixel buffers the host shows (window PixMaps and the main device PixMap) live in guest memory. Every write into the
	// tracked range stamps its 256-byte chunk with a serial number, so the host can tell per window (and per row) whether
	// anything changed since it last converted the pixels, instead of converting and comparing every pixel 30 times a second.
	// The main device PixMap is tracked more finely as well: programs draw straight into it and the host
	// composites those pixels over its windows (MacAppHost.compositeScreen) - but only the bytes the program has actually
	// written, tracked bit per byte, and only over windows shown before the write (a dialog opened after the game view was
	// drawn stays on top, as it would on a real screen). Each write also stamps a 32x32-byte tile with the serial so the
	// device writes and the window's own drawing can be ordered.
	static final int CHUNK_SHIFT=8;
	private int trackStart,trackEnd;
	/** Serial of the last write into each 256-byte chunk of the whole address space (allocated on the first tracking request). */
	private long[] chunkSerial;
	/** The newest of the chunk serials per 64 KB, so {@link #chunkSerial} over a whole window buffer reads a few dozen longs instead
	 *  of thousands (the host asks after every trap once a program has written the screen: publishWindowDrawing). */
	private static final int SUPER_SHIFT=16;
	private long[] superSerial;
	/** Main device PixMap range and its bit-per-byte/tile records. */
	private int deviceStart,deviceEnd,deviceRowBytes=1,deviceTileColumns;private long[] deviceBits;private long[] deviceTiles;
	/** Published (volatile) after every stamp of a write, so a reader that sees serial N also sees every chunk/tile stamp <= N and the pixel bytes behind them. */
	private volatile long serial=1;
	/** Starts tracking writes into the device PixMap pixels at {@code start} (rows of {@code rowBytes}, {@code length} bytes in all). */
	synchronized void trackWrites(int start,int length,int rowBytes){
		if(deviceEnd>deviceStart)untrackBuffer(deviceStart,deviceEnd-deviceStart);
		deviceStart=start;deviceEnd=start+length;deviceRowBytes=Math.max(1,rowBytes);deviceTileColumns=(deviceRowBytes+31)/32;
		deviceBits=new long[(length+63)/64];deviceTiles=new long[deviceTileColumns*((length/deviceRowBytes)/32+2)];deviceCachedRowStart=-1;
		trackBuffer(start,length);
	}
	/** How many tracked buffers cover each chunk; writes into the [trackStart,trackEnd) span but outside every buffer stamp nothing. */
	private short[] chunkTracked;
	/** Adds a pixel buffer (a window's PixMap) to the tracked chunks. */
	synchronized void trackBuffer(int start,int length){
		if(length<=0)return;
		if(chunkSerial==null){chunkSerial=new long[(size>>>CHUNK_SHIFT)+1];chunkTracked=new short[chunkSerial.length];superSerial=new long[(size>>>SUPER_SHIFT)+1];}
		for(int c=start>>>CHUNK_SHIFT,last=(start+length-1)>>>CHUNK_SHIFT;c<=last;c++)if(chunkTracked[c]<Short.MAX_VALUE)chunkTracked[c]++;
		if(trackEnd<=trackStart){trackStart=start;trackEnd=start+length;}
		else{trackStart=Math.min(trackStart,start);trackEnd=Math.max(trackEnd,start+length);}
	}
	/** Whether {@code address} lies in a tracked pixel buffer (a window's PixMap or the device's): what is written there is on screen. */
	boolean tracked(int address){short[] t=chunkTracked;if(t==null||address<trackStart||address>=trackEnd)return false;int c=address>>>CHUNK_SHIFT;return c>=0&&c<t.length&&t[c]>0;}
	/** Removes a buffer added with {@link #trackBuffer} (its window was disposed or got a new PixMap); the span shrinks back to the remaining buffers. */
	synchronized void untrackBuffer(int start,int length){
		if(length<=0||chunkTracked==null)return;
		for(int c=start>>>CHUNK_SHIFT,last=(start+length-1)>>>CHUNK_SHIFT;c<=last;c++)if(chunkTracked[c]>0)chunkTracked[c]--;
		int first=trackStart>>>CHUNK_SHIFT,end=(trackEnd-1)>>>CHUNK_SHIFT;
		while(first<=end&&chunkTracked[first]==0)first++;
		while(end>=first&&chunkTracked[end]==0)end--;
		if(first>end){trackStart=trackEnd=0;}else{trackStart=first<<CHUNK_SHIFT;trackEnd=Math.min(size,(end+1)<<CHUNK_SHIFT);}
	}
	/** A byte, word or long write at {@code address} (already known to overlap the tracked range): at most two chunks. */
	/** A byte, word or long write at {@code address} whose first chunk is tracked (the callers test that inline). */
	private void touched(int address,int length){
		long stamp=serial+1;long[] chunks=chunkSerial;
		chunks[address>>>CHUNK_SHIFT]=stamp;chunks[(address+length-1)>>>CHUNK_SHIFT]=stamp;
		long[] supers=superSerial;supers[address>>>SUPER_SHIFT]=stamp;supers[(address+length-1)>>>SUPER_SHIFT]=stamp;
		if(address<deviceEnd&&address+length>deviceStart&&!deviceMirroring)deviceTouched(Math.max(address,deviceStart)-deviceStart,Math.min(address+length,deviceEnd)-deviceStart,stamp);
		serial=stamp;
	}
	/** A bulk write (load/copy/fill) overlapping the tracked range. */
	private void touchedRange(int address,int length){
		int from=Math.max(address,trackStart),to=Math.min(address+length,trackEnd);if(from>=to)return;
		long stamp=serial+1;long[] chunks=chunkSerial;
		for(int c=from>>>CHUNK_SHIFT,last=(to-1)>>>CHUNK_SHIFT;c<=last;c++)chunks[c]=stamp;
		long[] supers=superSerial;for(int c=from>>>SUPER_SHIFT,last=(to-1)>>>SUPER_SHIFT;c<=last;c++)supers[c]=stamp;
		if(from<deviceEnd&&to>deviceStart&&!deviceMirroring)deviceTouched(Math.max(from,deviceStart)-deviceStart,Math.min(to,deviceEnd)-deviceStart,stamp);
		serial=stamp;
	}
	/** Row of the last device write, so consecutive writes into one row need no division. */
	private int deviceCachedRow,deviceCachedRowStart=-1;
	/** Whether anything was ever written into the device PixMap: the host starts compositing device pixels over its windows then. */
	private volatile boolean deviceWritten;
	boolean deviceWritten(){return deviceWritten;}
	/** Forgets that the device was ever written: the INIT icons drawn at start-up are not the program writing the screen itself,
	 *  and a program taken for one pays for it after every trap (publishWindowDrawing) and every scheduler poll (adoptDeviceWrites)
	 *  for the rest of its run - Speedometer's graphics score fell from 30 to 5 with an extension that draws its icon. */
	void resetDeviceWritten(){deviceWritten=false;}
	/** While set (emulation thread), writes into the device are QuickDraw's copy of pixels it also drew into the window covering
	 *  them (QuickDraw68k.ScreenBitmap): no program screen write, so they set no device bits or tile stamps - otherwise the window
	 *  re-adopted its own drawing before every screen-port call and every trap republished it (MacBench's graphics playback
	 *  draws everything through the screen port and spent two thirds of its time there). */
	private boolean deviceMirroring;
	boolean mirrorDevice(boolean on){boolean was=deviceMirroring;deviceMirroring=on;return was;}
	/** Write serial of the last device write (0 before any): the host compares it with the serial it noted when the program's
	 *  last window went away, to show the screen only for what was drawn on it since. */
	private volatile long deviceSerial;
	long deviceSerial(){return deviceSerial;}
	/** Marks the device bytes [from,to) (relative to the PixMap start) written: bit ranges by word, tiles by row span. */
	private void deviceTouched(int from,int to,long stamp){
		if(!deviceWritten)deviceWritten=true;deviceSerial=stamp;
		long[] bits=deviceBits;long[] tiles=deviceTiles;int rowBytes=deviceRowBytes,columns=deviceTileColumns;
		int rowStart=deviceCachedRowStart,row=deviceCachedRow;
		if(from<rowStart||from>=rowStart+rowBytes){row=from/rowBytes;rowStart=row*rowBytes;deviceCachedRow=row;deviceCachedRowStart=rowStart;}
		if(to-from<=8&&to<=rowStart+rowBytes&&((from^(to-1))&~63)==0){
			// the usual case: a byte, word or long inside one row and one 64-bit word of the bit record
			bits[from>>>6]|=((1L<<(to-from))-1)<<(from&63);
			int t=(row>>>5)*columns+((from-rowStart)>>>5);if(t<tiles.length)tiles[t]=stamp;
			if(((from^(to-1))&~31)!=0&&t+1<tiles.length)tiles[t+1]=stamp; // straddles two tiles
			return;
		}
		for(int o=from;o<to;){int wordEnd=Math.min(to,(o|63)+1);long mask=(wordEnd-o==64)?-1L:((1L<<(wordEnd-o))-1)<<(o&63);bits[o>>>6]|=mask;o=wordEnd;}
		while(rowStart<to){int spanFrom=Math.max(from,rowStart)-rowStart,spanTo=Math.min(to,rowStart+rowBytes)-rowStart;
			int tileRow=(row>>>5)*columns;
			for(int t=tileRow+(spanFrom>>>5),last=tileRow+((spanTo-1)>>>5);t<=last;t++)if(t<tiles.length)tiles[t]=stamp;
			row++;rowStart+=rowBytes;}
	}
	/** Marks {@code length} bytes at {@code address} as written outside the guest write paths (host Java2D drawing over a buffer). */
	/** Debug: a hash of a byte range (to notice writes that bypass the write probes). */
	long rangeHash(int from,int to){long h=0;for(int i=Math.max(0,from);i<Math.min(bytes.length,to);i++)h=h*31+bytes[i];return h;}
	/** Debug (-Dhz68k.touchprobe=LO,HI hex): prints a stack trace when host-side drawing marks bytes of that range as written. */
	private static final int[] TOUCH_PROBE=parseProbe(System.getProperty("hz68k.touchprobe"));
	synchronized void touch(int address,int length){
		if(TOUCH_PROBE!=null&&length>0&&address<TOUCH_PROBE[1]&&address+length>TOUCH_PROBE[0])new Throwable("[68K] host write touches $"+Integer.toHexString(address)+"+"+length).printStackTrace();
		if(length>0&&address<trackEnd&&address+length>trackStart)touchedRange(address,length);
		if(length>0&&address<watchEnd&&address+length>watchStart)watched(address,length);
		codeRange(address,length);}
	/** Debug: how many tracked device bytes were written, and the rows touched. */
	String describeTracking(){if(deviceBits==null)return "no tracking";long n=0;int firstRow=-1,lastRow=-1;for(int i=0;i<deviceBits.length;i++)n+=Long.bitCount(deviceBits[i]);
		for(int o=0;o<deviceEnd-deviceStart;o+=deviceRowBytes){boolean any=false;for(int x=0;x<deviceRowBytes&&!any;x+=8){int b=o+x;if(b<deviceEnd-deviceStart&&(deviceBits[b>>>6]&(1L<<(b&63)))!=0)any=true;}if(any){if(firstRow<0)firstRow=o/deviceRowBytes;lastRow=o/deviceRowBytes;}}
		return "device $"+Integer.toHexString(deviceStart)+" rowBytes "+deviceRowBytes+": "+n+" bytes written, rows "+firstRow+".."+lastRow+", serial "+serial;}
	// ------------------------------------------------------------------ writes into smooth zoom planes' buffers
	// A buffer whose plane holds blocks drawn at hi-res (SmoothZoom.Plane) gets a bit per byte set by every write, whatever its
	// value: a program that paints the colour already there over old text with its own code (HyperCard's blitters wiping its
	// offscreen card picture white for a new stack) must still release the old glyphs' hi-res edges, which comparing the
	// pixels cannot see.
	private static final class Watch {final int start,end;final long[] bits;Watch(int start,int end){this.start=start;this.end=end;bits=new long[(end-start+63)>>>6];}}
	private volatile Watch[] watches=new Watch[0];
	private volatile int watchStart,watchEnd;
	/** Starts setting a bit per written byte of {@code length} bytes at {@code start}; returns the bits (byte offset o is bit o&63 of word o>>>6). */
	synchronized long[] watchBuffer(int start,int length){
		Watch w=new Watch(start,start+Math.max(0,length));Watch[] list=Arrays.copyOf(watches,watches.length+1);list[list.length-1]=w;
		watches=list;watchSpan();return w.bits;
	}
	/** Stops the bits returned by {@link #watchBuffer}. */
	synchronized void unwatchBuffer(long[] bits){
		Watch[] list=watches;int n=0;Watch[] kept=new Watch[list.length];for(Watch w:list)if(w.bits!=bits)kept[n++]=w;
		if(n==list.length)return;watches=Arrays.copyOf(kept,n);watchSpan();
	}
	private void watchSpan(){int from=Integer.MAX_VALUE,to=0;for(Watch w:watches){from=Math.min(from,w.start);to=Math.max(to,w.end);}
		if(to<=from){watchStart=watchEnd=0;}else{watchStart=from;watchEnd=to;}}
	/** A write of {@code length} bytes at {@code address} overlapping the watched span. */
	private void watched(int address,int length){
		for(Watch w:watches){if(address>=w.end||address+length<=w.start)continue;
			int from=Math.max(address,w.start)-w.start,to=Math.min(address+length,w.end)-w.start;long[] bits=w.bits;
			if(to-from==1){bits[from>>>6]|=1L<<(from&63);continue;}
			for(int o=from;o<to;){int wordEnd=Math.min(to,(o|63)+1);bits[o>>>6]|=(wordEnd-o==64)?-1L:((1L<<(wordEnd-o))-1)<<(o&63);o=wordEnd;}}
	}

	/** Current write serial; a window shown now is newer than every tracked write so far. */
	long writeSerial(){return serial;}
	/** Highest write serial over the chunks covering {@code length} bytes at {@code address} (0 when nothing is tracked). */
	long chunkSerial(int address,int length){
		long[] chunks=chunkSerial;if(chunks==null||length<=0)return 0;long best=0;
		int c=address>>>CHUNK_SHIFT,last=Math.min(chunks.length-1,(address+length-1)>>>CHUNK_SHIFT);
		final int perSuper=1<<(SUPER_SHIFT-CHUNK_SHIFT);
		if(last-c>=2*perSuper){ // whole 64 KB blocks in between: their summary
			long[] supers=superSerial;int s0=(c+perSuper-1)/perSuper,s1=(last+1)/perSuper; // blocks [s0,s1) lie entirely inside
			for(;c<s0*perSuper;c++)if(chunks[c]>best)best=chunks[c];
			for(int s=s0;s<s1;s++)if(supers[s]>best)best=supers[s];
			c=s1*perSuper;}
		for(;c<=last;c++)if(chunks[c]>best)best=chunks[c];
		return best;
	}
	/** Serial of the last tracked write in the 32x32-byte tile holding {@code address} (0 when untracked/never). */
	/** Forgets that the device bytes at {@code address} were written: called once a window adopted them, so a later write
	 *  elsewhere in the same 32-row tile (another window's texture just below) cannot make the stale bytes look new again. */
	void forgetDeviceWrites(int address,int length){
		long[] bits=deviceBits;if(bits==null)return;int from=Math.max(address,deviceStart)-deviceStart,to=Math.min(address+length,deviceEnd)-deviceStart;
		for(int o=from;o<to;o++)bits[o>>>6]&=~(1L<<(o&63));
	}
	/** Forgets every device write so far (the INIT icons drawn at startup are nobody's window content): the tile serials stay, only
	 *  bytes written from now on count as the program's screen drawing. */
	void forgetAllDeviceWrites(){long[] bits=deviceBits;if(bits!=null)Arrays.fill(bits,0L);}
	/** First byte of the 32-byte device tile column holding {@code address} in its row (the address itself outside the device). */
	int tileStart(int address){if(deviceBits==null||address<deviceStart||address>=deviceEnd)return address;return address-((address-deviceStart)%deviceRowBytes&31);}
	long tileSerialAt(int address){
		if(deviceBits==null||address<deviceStart||address>=deviceEnd)return 0;int o=address-deviceStart;
		int tile=(o/deviceRowBytes>>>5)*deviceTileColumns+((o%deviceRowBytes)>>>5);return tile<deviceTiles.length?deviceTiles[tile]:serial;
	}
	/** Highest tile serial over the byte range (tiles are 32 bytes wide, so every 32nd byte plus the last one is sampled). */
	long maxTileSerial(int from,int length){
		long[] tiles=deviceTiles;int rowBytes=deviceRowBytes;
		if(deviceBits!=null&&length>0&&from>=deviceStart&&from+length<=deviceEnd){ // within one device row: its tiles in a row, one division
			int o=from-deviceStart,row=o/rowBytes,column=o-row*rowBytes,lastColumn=column+length-1;
			if(lastColumn<rowBytes){long best=0;int t=(row>>>5)*deviceTileColumns,last=t+(lastColumn>>>5);
				for(t+=column>>>5;t<=last;t++){long s=t<tiles.length?tiles[t]:serial;if(s>best)best=s;}
				return best;}}
		long best=0;for(int a=from;a<from+length;a+=32)best=Math.max(best,tileSerialAt(a));return Math.max(best,tileSerialAt(from+length-1));}
	/** True when the program wrote the device byte at {@code address} after serial {@code since}. */
	boolean writtenSince(int address,long since){
		if(address<deviceStart||address>=deviceEnd)return false;int o=address-deviceStart;
		if((deviceBits[o>>>6]&(1L<<(o&63)))==0)return false;
		int tile=(o/deviceRowBytes>>>5)*deviceTileColumns+((o%deviceRowBytes)>>>5);return tile>=deviceTiles.length||deviceTiles[tile]>since;
	}
	/** Bit-per-byte record of device writes and the PixMap start it is relative to (for the host compositor's inner loop). */
	long[] deviceWriteBits(){return deviceBits;}
	int deviceStart(){return deviceStart;}

	byte[] readBytes(int address,int length) throws M68kCpuException {
		address=normalize(address);check(address,length);
		if(address<lowEnd){byte[] out=new byte[length];for(int i=0;i<length;i++)out[i]=(byte)byteAt(address+i);return out;}
		return Arrays.copyOfRange(bytes,address,address+length);
	}

	void copy(int source, int destination, int length) throws M68kCpuException {
		source = normalize(source);
		destination = normalize(destination);
		check(source, length);
		check(destination, length);
		if(ADDR_PROBE!=null&&destination<ADDR_PROBE[1]&&destination+length>ADDR_PROBE[0]&&ADDR_PROBE_TRACE)new Throwable("[68K] block copy into probed range").printStackTrace();
		if(ADDR_PROBE!=null&&destination<ADDR_PROBE[1]&&destination+length>ADDR_PROBE[0])System.err.println("[68K] block copy into probed range: $"+Integer.toHexString(source)+" -> $"+Integer.toHexString(destination)+" +"+length+" pc $"+Integer.toHexString(probePc)+" sp $"+Integer.toHexString(probeSp));
		if(source<lowEnd||destination<lowEnd){ // this program's own low memory on either side: a byte at a time, overlap as memmove
			if(destination>source)for(int i=length-1;i>=0;i--)putByte(destination+i,byteAt(source+i));
			else for(int i=0;i<length;i++)putByte(destination+i,byteAt(source+i));
			if(destination+length<=lowEnd)return;}
		else System.arraycopy(bytes, source, bytes, destination, length);
		codeRange(destination,length);
		if(destination<trackEnd&&destination+length>trackStart)touchedRange(destination,length);
		if(destination<watchEnd&&destination+length>watchStart)watched(destination,length);
		if(destination<ioEnd&&destination+length>ioStart){ // a BlockMove of samples into the sound buffer
			for(int x=Math.max(destination,ioStart),end=Math.min(destination+length,ioEnd);x<end;x++)io.write(x-ioStart,bytes[x]&0xff);}
	}

	void fill(int address,int length,int value) throws M68kCpuException {
		address=normalize(address);check(address,length);
		if(address<lowEnd){for(int i=0;i<length;i++)putByte(address+i,value);if(address+length<=lowEnd)return;}
		else Arrays.fill(bytes,address,address+length,(byte)value);
		codeRange(address,length);
		if(address<trackEnd&&address+length>trackStart)touchedRange(address,length);
		if(address<watchEnd&&address+length>watchStart)watched(address,length);
	}

	/**
	 * Where blocks start: 4 for 68K programs; 16 in a PowerPC program's memory, as the PowerPC Memory Manager of Mac OS 9
	 * aligns heap blocks for AltiVec. iTunes relies on it: it designs its Sound Enhancer filter only into a 16-byte aligned
	 * block and runs the (all-zero) filter anyway - every song played silent from 4-aligned blocks.
	 */
	private volatile int blockAlignment=4;
	void setBlockAlignment(int alignment){blockAlignment=Math.max(4,alignment);}
	int blockAlignment(){return blockAlignment;}
	/** Takes {@code needed} bytes at a {@code align}-aligned start inside the free block just removed; the rest goes back. */
	private int placeAligned(int address,int blockSize,int needed,int align,boolean top){
		int start=top?(address+blockSize-needed)&-align:(address+align-1)&-align;
		if(start>address)addFree(address,start-address);
		int rest=address+blockSize-(start+needed);if(rest>=8)addFree(start+needed,rest);else needed+=rest;
		putBlock(start,needed);Arrays.fill(bytes,start,start+needed,(byte)0);
		return start;
	}

	/**
	 * Block tags for programs that walk the heap themselves (THINK C 5 and THINK Pascal, see MacApp68k.walksHeapHeaders): a real
	 * 24-bit heap keeps an 8-byte header before every block, whose first byte (tag in the high nibble, size correction in the low
	 * one) is never 0 for a block in use. THINK C's project manager appends one Handle to another by reading those headers: it
	 * walks from the first block's header over FREE blocks (tag 0) and merges in place when it reaches the second one, else it
	 * falls back to SetHandleSize + BlockMove. Here the bookkeeping lives on the Java side and the header bytes are the previous
	 * block's trailer, all zero - an endless walk over "free blocks of size 0" (Loading <MacTraps> hung). With this on, each
	 * block's tag byte says "in use" (0x80) with a zero size, so such a walk stops at once and the program takes its Toolbox path.
	 */
	volatile boolean blockTags;
	/**
	 * Real block headers (MPW Shell / ToolServer, MacApp68k.mpwShell): the 12 bytes before each block hold what the 32-bit
	 * Memory Manager keeps there (see tagAt) - the VM says 32-bit addressing, and the application zone says heapType 1
	 * (Toolbox68k.writeApplZoneRecord). MPW Shell checks a Handle's header (and that HandleZone + relative handle gives the
	 * Handle back) before every DisposHandle, skipping it with DebugStr "Attempt to dispose a bad handle" otherwise; 3.0 also
	 * keeps a flag of its own in the tag byte (BSET #4 at block-12, which wrote into the previous block with 8-byte trailers).
	 * In this mode every block keeps a 16-byte trailer (reallocate too), so the next block's header always has its room.
	 */
	volatile boolean blockHeaders;
	/**
	 * Real 24-bit block headers (MacApp68k.readsBlockHeaders24): the 8 bytes before each block hold what the 24-bit Memory
	 * Manager keeps there - tag (bits 7-6: 1 nonrelocatable, 2 relocatable) with the size correction in the low nibble, the
	 * physical size (header included) in the low 3 bytes, then the relative handle or the zone. Uninvited's resource cache
	 * finds the end of each cached Handle's data from its header (move.l Lo3Bytes,Dn; subq #8,An; and.l (An),Dn) and walked
	 * on forever over the zero bytes that stood there. The 8-byte trailer every block keeps is that room.
	 */
	volatile boolean blockHeaders24;
	/** The zone written into block headers ({@link #blockHeaders}): the application zone record. */
	volatile int headerZone;
	/** Handle blocks (block -> Handle), for the relative handle of {@link #blockHeaders}. */
	private final Map<Integer,Integer> blockHandles=new HashMap<Integer,Integer>();
	/** Mac68kApi made {@code handle}'s master pointer point at {@code block} with {@code size} logical bytes: (re)writes its header. */
	synchronized void noteHandleBlock(int handle,int block,int size){
		if(!(blockHeaders||blockHeaders24)||block<=0)return;
		block&=mask;Integer length=allocated.get(block);if(length==null)return;
		blockHandles.put(block,handle);requested.put(block,Math.max(0,size));tagAt(block);
	}
	private void tagBlocks(int address,int size){
		if(address<=0)return;
		Integer length=allocated.get(address);if(length==null)return;
		requested.put(address,Math.max(0,size));
		if(!(blockTags||blockHeaders||blockHeaders24))return;
		tagAt(address);if(allocated.containsKey(address+length))tagAt(address+length); // this block's zero fill cleared the next one's tag
	}
	/**
	 * A new nonrelocatable block: the byte before it is the low byte of the zone in a real block header, never 0 in practice.
	 * Before I Go - Final Wishes (Notebook) reads a Ptr as a Pascal string from one byte before it and cuts
	 * it to the length it wants: with a 0 there the string came out empty and it said "Not enough memory available". The byte
	 * lies in the spare bytes after the previous block; a reallocated block (no spare bytes) or a block with real headers is left alone.
	 */
	synchronized void notePointerBlock(int address){
		if(blockHeaders||blockHeaders24||address<=0)return;
		address&=mask;int at=address-1;
		Integer previous=endsAt.get(address);
		if(previous!=null){Integer used=requested.get(previous);if(used==null||previous+used>at)return;}
		else{Map.Entry<Integer,Integer> free=freeByAddress.floorEntry(at);if(free==null||at>=free.getKey()+free.getValue())return;}
		bytes[at]=(byte)0xFC;
	}
	/** The tag byte of the block at {@code address}: in the previous block's trailer or free space (never another block's data). */
	private void tagAt(int address){
		boolean full=blockHeaders||blockHeaders24;
		int at=address-(blockHeaders?HEADER_SIZE:8);if(at<heapStart||at>=bytes.length||(!full&&bytes[at]!=0))return;
		// only in another block's trailer (a block ends exactly here) or in free space: a Handle the program points into a
		// block by itself (THINK C carves its pieces) may sit where the books see no block, and its data must not change
		Integer previous=endsAt.get(address);
		// a block placed at an aligned address (PowerPC programs: blockAlignment 32) starts a little after the previous one ends,
		// the gap left as free bytes: the header still goes into that block's trailer and the gap. Apple Dylan PPC's MCL found its
		// region Handles' blocks headerless ("Invalid selection region in #<FRED-RECORD>")
		for(int e=address-4;previous==null&&e>at;e-=4)previous=endsAt.get(e);
		if(previous!=null){Integer used=requested.get(previous);if(used==null||previous+used>at)return;} // reallocate leaves no spare bytes
		else{Map.Entry<Integer,Integer> free=freeByAddress.floorEntry(at);if(free==null||at>=free.getKey()+free.getValue())return;}
		if(!full){bytes[at]=(byte)0x80;return;}
		if(!blockHeaders){ // 24-bit: tag + size correction, 24-bit physical size, relative handle or zone
			Integer used=requested.get(address),length=allocated.get(address);
			int logical=used!=null?used:length==null?0:Math.max(0,length-trailer()),physical=8+Math.max(4,(logical+3)&~3);
			Integer handle=blockHandles.get(address);int second=handle!=null?handle-headerZone:headerZone;
			bytes[at]=(byte)((handle!=null?0x80:0x40)|((physical-8-logical)&0x0f));
			bytes[at+1]=(byte)(physical>>16);bytes[at+2]=(byte)(physical>>8);bytes[at+3]=(byte)physical;
			bytes[at+4]=(byte)(second>>24);bytes[at+5]=(byte)(second>>16);bytes[at+6]=(byte)(second>>8);bytes[at+7]=(byte)second;
			return;}
		// the 32-bit Memory Manager's 12-byte header (the VM runs programs with 32-bit addressing: MPW Shell 3.0 finds the header
		// 12 bytes before a block after StripAddress($80000000), 3.1 after the zone's heapType): tag (bits 7-6: 1 nonrelocatable,
		// 2 relocatable), master pointer flags, reserved, size correction; the physical size; the relative handle or the zone
		Integer used=requested.get(address),length=allocated.get(address);
		int logical=used!=null?used:length==null?0:Math.max(0,length-trailer()),physical=HEADER_SIZE+Math.max(4,(logical+3)&~3);
		Integer handle=blockHandles.get(address);
		bytes[at]=(byte)(handle!=null?0x80:0x40);bytes[at+1]=0;bytes[at+2]=0;bytes[at+3]=(byte)(physical-HEADER_SIZE-logical);
		bytes[at+4]=(byte)(physical>>24);bytes[at+5]=(byte)(physical>>16);bytes[at+6]=(byte)(physical>>8);bytes[at+7]=(byte)physical;
		int second=handle!=null?handle-headerZone:headerZone;
		bytes[at+8]=(byte)(second>>24);bytes[at+9]=(byte)(second>>16);bytes[at+10]=(byte)(second>>8);bytes[at+11]=(byte)second;
	}

	// (the zero fill of a new block - and freeing one - replaces whatever compiled code was there: codeRange)
	synchronized int allocate(int size, int alignment) throws M68kCpuException {int a=allocateUntagged(size,alignment);tagBlocks(a,size);codeRange(a,size+HEADER_TRAILER);return a;}
	synchronized int allocateOutside(int size,int lo,int hi) throws M68kCpuException {int a=allocateOutsideUntagged(size,lo,hi);tagBlocks(a,size);codeRange(a,size+HEADER_TRAILER);return a;}
	synchronized int allocateBelow(int size,int alignment,int limit) throws M68kCpuException {int a=allocateBelowUntagged(size,alignment,limit);tagBlocks(a,size);codeRange(a,size+HEADER_TRAILER);return a;}
	synchronized int allocateReserving(int size,int alignment,int reserve) throws M68kCpuException {int a=allocateReservingUntagged(size,alignment,reserve);tagBlocks(a,size);codeRange(a,size+HEADER_TRAILER);return a;}
	synchronized boolean growInPlace(int address,int newSize){boolean grown=growInPlaceUntagged(address,newSize);if(grown){tagBlocks(address&mask,newSize);codeRange(address&mask,newSize+HEADER_TRAILER);}return grown;}
	synchronized int reallocate(int address,int newSize,boolean mayMove) throws M68kCpuException {int a=reallocateUntagged(address,newSize,mayMove);tagBlocks(a,newSize);codeRange(a,newSize+HEADER_TRAILER);return a;}

	/** Allocates a zeroed block, aligned to {@link #blockAlignment} (4 bytes unless a PowerPC program runs). */
	private static final int BLOCK_TRAILER=8;
	/** The spare bytes after each block: 16 with {@link #blockHeaders} (room for the 32-bit Memory Manager's 12-byte header of the next block). */
	private int trailer(){return blockHeaders?HEADER_TRAILER:BLOCK_TRAILER;}
	private static final int HEADER_TRAILER=16,HEADER_SIZE=12;
	/** The message of a failed allocation: how much was asked for and what the heap has left. */
	private String outOfHeap(int size){return "out of 68K heap memory (a block of "+size+" bytes; "+freeBytes+" free, largest "+(freeBySize.isEmpty()?0:(int)(freeBySize.lastKey()>>>32))+")";}
	private int allocateUntagged(int size, int alignment) throws M68kCpuException {
		if(size<0)throw new M68kCpuException(0,"negative allocation");
		int align=blockAlignment;
		if(align>4){int needed=Math.max(4,(size+3)&~3)+trailer(),search=needed+align-4;
			Map.Entry<Long,Boolean> fit=size<HIGH_ALLOCATION?freeBySizeLow.ceilingEntry(((long)search<<32)):null;
			if(fit==null)fit=freeBySize.ceilingEntry(((long)search<<32));
			if(fit==null){int fromReserve=allocateFromReserve(needed);if(fromReserve!=0)return fromReserve; // the last resort need not be aligned
				if(grow(search))return allocate(size,alignment);throw new M68kCpuException(heapEnd,outOfHeap(size));}
			long key=fit.getKey();int blockSize=(int)(key>>>32),address=(int)key;
			removeFree(address,blockSize);
			return placeAligned(address,blockSize,needed,align,size>=HIGH_ALLOCATION);}
		// Every block carries 8 spare bytes at its end, where a real heap has the next block's header: programs that overrun
		// a buffer by a pixel on short spans then damage nothing that matters,
		// instead of the master pointer or block that happens to follow in this packed layout.
		int needed=Math.max(4,(size+3)&~3)+trailer();
		Map.Entry<Long,Boolean> fit=size<HIGH_ALLOCATION?freeBySizeLow.ceilingEntry(((long)needed<<32)):null;
		if(fit==null)fit=freeBySize.ceilingEntry(((long)needed<<32));
		if(fit==null){int fromReserve=allocateFromReserve(needed);if(fromReserve!=0)return fromReserve;
			if(grow(needed))return allocate(size,alignment);
			throw new M68kCpuException(heapEnd,outOfHeap(size));}
		long key=fit.getKey();int blockSize=(int)(key>>>32),address=(int)key;
		removeFree(address,blockSize);
		// Big blocks (screen buffers, GWorlds, file buffers) take the top of their free block, small ones the bottom: the low
		// megabytes then stay available for code and small records, which is where the QuickTime segment loader needs its
		// Handles (it keeps flags in the top byte of their master pointers) and where a real Mac's heap would have them.
		if(blockSize-needed>=8){if(size>=HIGH_ALLOCATION){int top=address+blockSize-needed;addFree(address,blockSize-needed);address=top;}else addFree(address+needed,blockSize-needed);}
		else needed=blockSize;
		putBlock(address,needed);
		Arrays.fill(bytes,address,address+needed,(byte)0);
		return address;
	}
	/**
	 * {@code count} blocks of {@code size} bytes side by side, each a block of its own (freeable one by one), in one
	 * allocation: master pointers come in such runs, as MoreMasters makes them, so they do not end up between the data
	 * blocks of Handles (a locked Handle could never grow in place past its own master pointer). Returns the first one.
	 */
	synchronized int allocateRun(int count,int size) throws M68kCpuException {
		int base=allocate(count*size,2),total=allocated.get(base);
		for(int i=0;i<count;i++)putBlock(base+i*size,i==count-1?total-(count-1)*size:size);
		if(blockHeaders)requested.put(base+(count-1)*size,size); // the last piece keeps the run's trailer: room for the next block's header
		return base;
	}
	/** {@link #allocate} anywhere but in [{@code lo}, {@code hi}) (the room a locked block is being grown into); 0 when no free
	 *  block has the room outside it. */
	private int allocateOutsideUntagged(int size,int lo,int hi) throws M68kCpuException {
		int needed=Math.max(4,(size+3)&~3)+trailer(),align=blockAlignment;
		for(Long key:freeBySize.tailMap((long)needed<<32).keySet()){
			int blockSize=(int)(key>>>32),address=(int)(long)key,start=(address+align-1)&-align;
			if(start<hi&&start+needed>lo)start=(Math.max(start,hi)+align-1)&-align;
			if(start+needed>address+blockSize)continue;
			removeFree(address,blockSize);
			if(start>address)addFree(address,start-address);
			int rest=address+blockSize-(start+needed);if(rest>=8)addFree(start+needed,rest);else needed+=rest;
			putBlock(start,needed);Arrays.fill(bytes,start,start+needed,(byte)0);
			return start;
		}
		return 0;
	}
	/** Allocations of this size and up are placed at the top of their free block. */
	private static final int HIGH_ALLOCATION=64*1024;
	/** First fit inside the system reserve (see {@link #SYSTEM_RESERVE}), or 0. {@code needed} includes the trailer. */
	private int allocateFromReserve(int needed){
		for(Map.Entry<Integer,Integer> free:freeByAddress.entrySet()){
			int address=free.getKey(),blockSize=free.getValue();
			if(address+blockSize>reserveEnd)break;
			if(blockSize<needed)continue;
			removeFree(address,blockSize);
			if(blockSize-needed>=8)addFree(address+needed,blockSize-needed);else needed=blockSize;
			putBlock(address,needed);
			Arrays.fill(bytes,address,address+needed,(byte)0);
			return address;
		}
		return 0;
	}

	/** Like {@link #allocate}, but from the lowest free block that keeps the whole block below {@code limit} (first fit by address);
	 *  falls back to {@link #allocate} when no such block exists. For code that must live in the first 16 MB (QuickTime's segment
	 *  loader keeps flags in the top byte of its master pointers). */
	/**
	 * MoveHHi's destination: the highest room below {@code limit} that is above {@code above}, taken from the top of its free
	 * run; 0 when there is none (the block stays where it is). Nothing is zeroed beyond what a new block gets.
	 */
	synchronized int allocateHighBelow(int size,int above,int limit){
		int needed=Math.max(4,(size+3)&~3)+trailer(),align=Math.max(4,blockAlignment);
		for(Map.Entry<Integer,Integer> free:freeByAddress.headMap(limit,false).descendingMap().entrySet()){
			int address=free.getKey(),blockSize=free.getValue(),end=Math.min(address+blockSize,limit),start=(end-needed)&-align;
			if(start+needed<=above)break;
			if(start<address||start<above)continue;
			removeFree(address,blockSize);
			if(start-address>=8)addFree(address,start-address);else{needed+=start-address;start=address;}
			int rest=address+blockSize-(start+needed);if(rest>=8)addFree(start+needed,rest);else needed+=rest;
			putBlock(start,needed);Arrays.fill(bytes,start,start+needed,(byte)0);tagBlocks(start,size);
			return start;
		}
		return 0;
	}
	private int allocateBelowUntagged(int size,int alignment,int limit) throws M68kCpuException {
		if(size<0)throw new M68kCpuException(0,"negative allocation");
		int needed=Math.max(4,(size+3)&~3)+trailer(),align=blockAlignment;
		if(align>4){for(Map.Entry<Integer,Integer> free:freeByAddress.entrySet()){
				int address=free.getKey(),blockSize=free.getValue(),start=(address+align-1)&-align;
				if(start+needed>limit)break;
				if(start+needed>address+blockSize)continue; // (the system reserve is exactly where these Handles go: a PowerPC program's Handles used to skip it and, once the program had filled the rest of the first 16 MB, got blocks above it)
				removeFree(address,blockSize);return placeAligned(address,blockSize,needed,align,false);}
			return allocate(size,alignment);}
		for(Map.Entry<Integer,Integer> free:freeByAddress.entrySet()){
			int address=free.getKey(),blockSize=free.getValue();
			if(address+needed>limit)break;
			if(blockSize<needed)continue;
			removeFree(address,blockSize);
			if(blockSize-needed>=8)addFree(address+needed,blockSize-needed);else needed=blockSize;
			putBlock(address,needed);
			Arrays.fill(bytes,address,address+needed,(byte)0);
			return address;
		}
		return allocate(size,alignment);
	}

	/** Like {@link #allocate}, with {@code reserve} more bytes kept free after the block for {@link #growInPlace}; the reserve is
	 *  dropped when the heap has no room for it. */
	private int allocateReservingUntagged(int size,int alignment,int reserve) throws M68kCpuException {
		if(reserve<=0)return allocate(size,alignment);
		// aligned blocks (a PowerPC program's) get the reserve too: HyperCard 2.3's PowerPC code grows its 512x342 card buffer
		// Ptr for a larger card, and without the room it fell back to a 512x342 window with the Scroll palette
		int align=blockAlignment,needed=Math.max(4,(size+3)&~3)+trailer(),total=needed+((reserve+3)&~3);
		Map.Entry<Long,Boolean> fit=freeBySize.ceilingEntry(((long)(total+(align>4?align-4:0))<<32));
		if(fit==null)return allocate(size,alignment);
		long key=fit.getKey();int blockSize=(int)(key>>>32),address=(int)key;
		removeFree(address,blockSize);
		int start=align>4?(address+align-1)&-align:address;
		if(start>address)addFree(address,start-address);
		int rest=address+blockSize-(start+total);
		if(rest>=8)addFree(start+total,rest);else total+=rest;
		putBlock(start,needed);reservedAfter.put(start,total-needed);
		Arrays.fill(bytes,start,start+total,(byte)0);
		return start;
	}

	/** Releases a block obtained from {@link #allocate}; unknown addresses are ignored. */
	/** Debug aid: -Dhz68k.freewatch=hexaddr prints a Java stack trace when the block holding that address is allocated or freed. */
	private static final int FREE_WATCH=(int)Long.parseLong(System.getProperty("hz68k.freewatch","0"),16);
	synchronized void free(int address) {
		address&=mask; // a master pointer may carry flags in its top byte (24-bit Memory Manager, see Mac68kApi.moveMasterPointer)
		Integer blockSize=removeBlock(address);
		if(blockSize==null)return;
		codeRange(address,blockSize);
		if(FREE_WATCH!=0&&FREE_WATCH>=address&&FREE_WATCH<address+blockSize)new Throwable("[68K] freeing watched block $"+Integer.toHexString(address)+" ("+blockSize+" bytes)").printStackTrace();
		Integer reserve=reservedAfter.remove(address);
		int start=address,length=blockSize+(reserve==null?0:reserve);
		Map.Entry<Integer,Integer> previous=freeByAddress.lowerEntry(start);
		if(previous!=null&&previous.getKey()+previous.getValue()==start){removeFree(previous.getKey(),previous.getValue());start=previous.getKey();length+=previous.getValue();}
		Integer next=freeByAddress.get(start+length);
		if(next!=null){removeFree(start+length,next);length+=next;}
		addFree(start,length);
	}

	/** The start of the allocated block holding {@code address}, or -1 (a linear search: for rare checks only). */
	synchronized int blockContaining(int address){
		address&=mask;
		for(Map.Entry<Integer,Integer> e:allocated.entrySet()){int start=e.getKey();if(address>=start&&address<start+e.getValue())return start;}
		return -1;
	}
	/** Cuts an allocated block in two at {@code at}; both parts stay allocated (a program carved a block out of another one, see
	 *  Mac68kApi.adoptProgramHeader). Growth room reserved after the block goes with the second part. */
	synchronized boolean splitBlock(int block,int at){
		block&=mask;at&=mask;Integer length=allocated.get(block);
		if(length==null||at<=block||at>=block+length)return false;
		putBlock(block,at-block);putBlock(at,block+length-at);
		Integer reserve=reservedAfter.remove(block);if(reserve!=null)reservedAfter.put(at,reserve);
		return true;
	}

	/** Length of the free run starting exactly at {@code address}, or null. */
	synchronized Integer freeLength(int address){return freeByAddress.get(address);}
	/** Size of an allocated block (rounded), or -1 when the address is not a block start. */
	/** The size of the free block starting at {@code address}, or -1 when none starts there. */
	synchronized int freeBlockSize(int address){Integer size=freeByAddress.get(address);return size==null?-1:size;}
	synchronized int blockSize(int address) { Integer size=allocated.get(address&mask);return size==null?-1:size; }
	synchronized boolean isAllocated(int address) { return allocated.containsKey(address&mask); }

	/** Grows a block in place into the free space that follows it, keeping its address; false when that space is too small (SetPtrSize). */
	private boolean growInPlaceUntagged(int address,int newSize){
		address&=mask;Integer old=allocated.get(address);if(old==null)return false;
		int needed=Math.max(4,(newSize+3)&~3)+trailer();if(needed<=old)return true;
		Integer reserve=reservedAfter.get(address);
		if(reserve!=null){ // growth room reserved with the block: take it first, then any free run behind it
			if(old+reserve>=needed){reservedAfter.put(address,old+reserve-needed);Arrays.fill(bytes,address+old,address+needed,(byte)0);putBlock(address,needed);return true;}
			Integer next=freeByAddress.get(address+old+reserve);if(next==null||old+reserve+next<needed)return false;
			removeFree(address+old+reserve,next);int extra=needed-old-reserve;reservedAfter.remove(address);
			if(next-extra>=8)addFree(address+needed,next-extra);else needed=old+reserve+next;
			Arrays.fill(bytes,address+old,address+needed,(byte)0);putBlock(address,needed);return true;
		}
		Integer next=freeByAddress.get(address+old);if(next==null||old+next<needed)return false;
		removeFree(address+old,next);int extra=needed-old;
		if(next-extra>=8)addFree(address+needed,next-extra);else needed=old+next;
		Arrays.fill(bytes,address+old,address+needed,(byte)0);putBlock(address,needed);return true;
	}
	/** Growth room still reserved after a block (0 when none). */
	synchronized int reservedAfter(int address){Integer r=reservedAfter.get(address&mask);return r==null?0:r;}
	/** Grows or shrinks a block in place when possible; returns the (possibly new) address. */
	synchronized int reallocate(int address,int newSize) throws M68kCpuException {return reallocate(address,newSize,true);}
	/** {@link #reallocate} that returns -1 instead of moving the block when {@code mayMove} is false (a locked Handle's block). */
	private int reallocateUntagged(int address,int newSize,boolean mayMove) throws M68kCpuException {
		address&=mask;Integer old=allocated.get(address);
		if(old==null)return allocate(newSize,4);
		if(reservedAfter.containsKey(address)&&growInPlace(address,newSize))return address;
		int needed=Math.max(4,(newSize+3)&~3)+(blockHeaders||blockHeaders24?trailer():0);
		if(needed<=old){
			if(old-needed>=8){putBlock(address,needed);putBlock(address+needed,old-needed);free(address+needed);}
			return address;
		}
		Integer next=freeByAddress.get(address+old);
		if(next!=null&&old+next>=needed){
			removeFree(address+old,next);int extra=needed-old;
			if(next-extra>=8)addFree(address+needed,next-extra);else needed=old+next;
			Arrays.fill(bytes,address+old,address+needed,(byte)0);putBlock(address,needed);return address;
		}
		if(!mayMove)return -1;
		int replacement=allocate(newSize,4);
		System.arraycopy(bytes,address,bytes,replacement,old);
		free(address);
		return replacement;
	}

	/** What FreeMem/MaxMem report: the room the memory can still grow into counts as free (some external updates refuse to run
	 *  with "Not enough memory" when a growable external VM reports only its initial 16 MB minus the screen-sized buffers). */
	private long growRoom() { return growLimit>size?(long)growLimit-size:0; }
	/** The bytes handed out by the heap allocator and not freed yet (the launcher's program information). */
	synchronized long allocatedBytes() { long total=0; for(int length: allocated.values()) total+=length; return total; }
	synchronized int availableBytes() { return (int)Math.min(Integer.MAX_VALUE,freeBytes+growRoom()); }
	synchronized int largestFreeBlock() { Map.Entry<Long,Boolean> last=freeBySize.lastEntry();long largest=last==null?0:(last.getKey()>>>32);return (int)Math.min(Integer.MAX_VALUE,Math.max(largest,growRoom())); }

	/** The CPU's sentinel addresses (M68kCpu.CALLBACK_SENTINEL, RETURN_SENTINEL) lie in the heap of a VM larger than 16 MB:
	 *  the allocator never hands out [SENTINEL_HOLE, +SENTINEL_HOLE_SIZE). Strata StudioPro 1.1 got a code resource there and
	 *  its routine stopped at $FFFFE0 as if it were an XCMD calling HyperCard back. */
	static final int SENTINEL_HOLE=M68kCpu.CALLBACK_SENTINEL,SENTINEL_HOLE_SIZE=0x01000000-M68kCpu.CALLBACK_SENTINEL;
	private void addFree(int address,int length){
		if(length<=0)return;
		if(address<SENTINEL_HOLE+SENTINEL_HOLE_SIZE&&address+length>SENTINEL_HOLE){ // keep the sentinels out of every free run
			int holeEnd=SENTINEL_HOLE+SENTINEL_HOLE_SIZE,end=address+length;
			if(address<SENTINEL_HOLE)addFree(address,SENTINEL_HOLE-address);
			if(end>holeEnd)addFree(holeEnd,end-holeEnd);
			return;}
		// a run across the end of the system reserve stays two blocks: the reserve's part is never handed to allocate()
		if(address<reserveEnd&&address+length>reserveEnd){addFree(address,reserveEnd-address);addFree(reserveEnd,address+length-reserveEnd);return;}
		long key=((long)length<<32)|(address&0xffffffffL);
		freeByAddress.put(address,length);freeBytes+=length;
		if(inReserve(address,length))return;
		freeBySize.put(key,Boolean.TRUE);if(address<LOW_LIMIT)freeBySizeLow.put(key,Boolean.TRUE);
	}
	private void removeFree(int address,int length){
		long key=((long)length<<32)|(address&0xffffffffL);
		freeByAddress.remove(address);freeBytes-=length;
		if(inReserve(address,length))return;
		freeBySize.remove(key);if(address<LOW_LIMIT)freeBySizeLow.remove(key);
	}

	byte[] readCString(int address, int maximum) throws M68kCpuException {
		address = normalize(address);
		check(address, 1);
		int length = 0;
		while(length < maximum && address + length < size && byteAt(address + length) != 0) length++;
		if(length == maximum) throw new M68kCpuException(address, "unterminated XCMD string");
		return readBytes(address, length);
	}

	private void check(int address, int length) throws M68kCpuException {
		if(address < 0 || length < 0 || address > size - length)
			throw new M68kCpuException(address, "memory access outside the address space");
	}

	private int normalize(int address) { return address & mask; }
	/** The byte a guest address reaches: addresses wrap at the memory size, as normalize does for every access. */
	int physical(int address) { return address & mask; }
}

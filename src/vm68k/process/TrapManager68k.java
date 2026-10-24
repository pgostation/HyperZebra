/** Shared plumbing of the trap-level managers: Pascal arguments and results, program callbacks, nested traps, guest blocks. */
abstract class TrapManager68k {
	final Toolbox68k tb;final M68kMemory memory;
	TrapManager68k(Toolbox68k tb){this.tb=tb;this.memory=tb.memory;}

	/** Reads the Pascal arguments (sizes 2 or 4, left to right) and pops them; the result slot, if any, is then at (SP).
	 *  Words come back sign-extended; a Boolean is a word whose high byte holds the value ({@link #bool}). */
	int[] pop(M68kCpu cpu,int... sizes) throws M68kCpuException {
		int[] v=new int[sizes.length];int at=cpu.a[7];
		for(int i=sizes.length-1;i>=0;i--){if(sizes[i]==4){v[i]=memory.readLong(at);at+=4;}else{v[i]=(short)memory.readWord(at);at+=2;}}
		cpu.a[7]=at;return v;
	}
	/** A Pascal Boolean argument (the value is in the high byte of its stack word). */
	static boolean bool(int word){return (word&0xff00)!=0;}
	/** Stores the function result in the slot at (SP): 4 a long, 2 a word, 1 a Boolean (high byte of the word slot). */
	void result(M68kCpu cpu,int bytes,int value) throws M68kCpuException {
		if(bytes==4)memory.writeLong(cpu.a[7],value);else if(bytes==2)memory.writeWord(cpu.a[7],value);else if(bytes==1)memory.writeWord(cpu.a[7],value!=0?0x0100:0);
	}
	void putLong(int p,int v) throws M68kCpuException {if(p!=0)memory.writeLong(p,v);}
	void putWord(int p,int v) throws M68kCpuException {if(p!=0)memory.writeWord(p,v);}
	/** A C Boolean output (one byte), skipped for a NIL pointer. */
	void putBoolean(int p,boolean v) throws M68kCpuException {if(p!=0)memory.writeByte(p,v?1:0);}
	/** A Pascal string output of at most {@code max} characters (bytes as given). */
	void putPascal(int p,byte[] text,int max) throws M68kCpuException {if(p==0)return;int n=Math.min(Math.min(255,max),text.length);memory.writeByte(p,n);memory.load(p+1,text,0,n);}
	byte[] pascalBytes(int p) throws M68kCpuException {if(p==0)return new byte[0];return memory.readBytes(p+1,memory.readByte(p));}
	static byte[] roman(String s){return s.getBytes(java.nio.charset.Charset.forName("MacRoman"));}
	static String roman(byte[] b){return new String(b,java.nio.charset.Charset.forName("MacRoman"));}
	static int fourcc(String s){return ResourceFile68k.fourcc(s);}

	/**
	 * Calls a program routine - a 68K procedure or a routine descriptor (Mixed Mode takes it to PowerPC code) - the Pascal
	 * way: a result slot of {@code resultBytes} (4, 2 or 1 = Boolean), then (value, size) pairs left to right. The 68K
	 * registers are as before afterwards.
	 */
	int call(M68kCpu cpu,int proc,int resultBytes,int... valueSize) throws M68kException {
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sp=cpu.a[7];
		try{
			if(resultBytes>0){cpu.a[7]-=resultBytes==4?4:2;memory.fill(cpu.a[7],resultBytes==4?4:2,0);}
			int slot=cpu.a[7],bytes=0;
			for(int i=0;i<valueSize.length;i+=2){int v=valueSize[i],size=valueSize[i+1];cpu.a[7]-=size;bytes+=size;if(size==4)memory.writeLong(cpu.a[7],v);else memory.writeWord(cpu.a[7],v);}
			cpu.callPascal(proc,bytes);
			return resultBytes==4?memory.readLong(slot):resultBytes==2?(short)memory.readWord(slot):resultBytes==1?memory.readByte(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp;}
	}
	private int trapStub;
	/** Runs a Toolbox trap from Java with Pascal (value, size) arguments; returns its result of {@code resultBytes}. */
	int trap(int word,int resultBytes,int... valueSize) throws M68kException {
		M68kCpu cpu=tb.cpu;
		if(trapStub==0)trapStub=memory.allocate(64,4);
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sp=cpu.a[7];
		try{
			int at=trapStub+((word&0xff)%8)*8; // a few slots, so a nested call does not overwrite a running stub
			memory.writeWord(at,word);memory.writeWord(at+2,0x4ef9);memory.writeLong(at+4,M68kCpu.RETURN_SENTINEL);
			if(resultBytes>0){cpu.a[7]-=resultBytes;memory.fill(cpu.a[7],resultBytes,0);}
			int slot=cpu.a[7],bytes=0;
			for(int i=0;i<valueSize.length;i+=2){int v=valueSize[i],size=valueSize[i+1];cpu.a[7]-=size;bytes+=size;if(size==4)memory.writeLong(cpu.a[7],v);else memory.writeWord(cpu.a[7],v);}
			cpu.runUntilSentinel(at,bytes);
			return resultBytes==2?(short)memory.readWord(slot):resultBytes==4?memory.readLong(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp;}
	}
	/** A zeroed guest block holding {@code data} (at least 1 byte). */
	int block(byte[] data) throws M68kCpuException {int p=memory.allocate(Math.max(1,data.length),4);memory.fill(p,Math.max(1,data.length),0);memory.load(p,data);return p;}
	/** A Handle's contents (empty for NIL or an empty Handle). */
	byte[] handleBytes(int h) throws M68kCpuException {if(h==0)return new byte[0];byte[] b=tb.api.handleContents(h);return b==null?new byte[0]:b;}
	/** Replaces a Handle's contents (SetHandleSize + copy). */
	void setHandle(int h,byte[] data) throws M68kCpuException {tb.api.loadHandle(h,data);}
	/** The signature (creator) of the running program. */
	int programSignature(){try{return tb.app!=null&&tb.app.file!=null?FinderInfo68k.creator(tb.app.file):0;}catch(Exception e){return 0;}}
	static int be32(byte[] b,int o){return (b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
	static int be16(byte[] b,int o){return (b[o]&255)<<8|(b[o+1]&255);}
	static void put32(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
	static void put16(byte[] b,int o,int v){b[o]=(byte)(v>>>8);b[o+1]=(byte)v;}
}

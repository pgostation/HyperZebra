import java.io.ByteArrayOutputStream;

/**
 * StdCLib, MPW's shared C library for PowerPC programs: memory and string functions, conversions, ctype, the printf and
 * scanf families on strings (console output goes to the host's standard output), file streams ({@link PpcStdio}), malloc on the Memory Manager,
 * setjmp/longjmp, qsort/bsearch, rand, time/clock (Macintosh epoch, 60 clocks a second as in MPW) and exit.
 */
final class PpcStdCLib {
	private PpcStdCLib(){}
	private static final String LIB="StdCLib";

	/** The arguments after a format: successive general-register slots, or a va_list (a pointer into the parameter area). */
	static final class VarArgs {
		final PpcRuntime r;int slot;int pointer;final boolean list;
		VarArgs(PpcRuntime r,int firstSlot){this.r=r;slot=firstSlot;list=false;}
		VarArgs(PpcRuntime r,int vaList,boolean list){this.r=r;pointer=vaList;this.list=list;}
		int nextInt() throws M68kCpuException {if(list){int v=r.memory.readLong(pointer);pointer+=4;return v;}return r.arg(slot++);}
		double nextDouble() throws M68kCpuException {long hi=nextInt()&0xffffffffL,lo=nextInt()&0xffffffffL;return Double.longBitsToDouble((hi<<32)|lo);}
	}

	private static byte[] pascalBytes(PpcRuntime r,int p) throws M68kCpuException {return p==0?new byte[0]:r.memory.readBytes(p+1,r.memory.readByte(p));}
	/**
	 * The object {@code path} names from (volume, directory): its FSSpec, its full pathname as a C string ({@code buffer}, at most
	 * 255 characters; a folder's ends in a colon) and the Booleans isFolder / hadAlias / leafIsAlias. noErr when it exists,
	 * fnfErr when only its folder does (the FSSpec still names it), dirNFErr otherwise.
	 */
	private static int resolve(PpcRuntime r,int volume,int directory,byte[] path,int spec,int buffer,int isFolder,int hadAlias,int leafIsAlias) throws M68kException {
		M68kMemory m=r.memory;Toolbox68k tb=r.toolbox;java.io.File f;
		try{f=tb.files.fileFor(volume,directory,tb.encoding.decode(path));}catch(java.io.IOException e){f=null;}
		boolean exists=f!=null&&f.exists(),parent=exists||f!=null&&f.getAbsoluteFile().getParentFile()!=null&&f.getAbsoluteFile().getParentFile().isDirectory();
		if(isFolder!=0)m.writeByte(isFolder,exists&&f.isDirectory()?1:0);
		if(hadAlias!=0)m.writeByte(hadAlias,0);
		if(leafIsAlias!=0)m.writeByte(leafIsAlias,0);
		if(!parent)return -120;
		try{
			if(spec!=0)tb.files.writeFileSpec(spec,f);
			if(buffer!=0){byte[] b=tb.encoding.encodeReplacing(tb.files.macFullPath(f)+(exists&&f.isDirectory()?":":""));int n=Math.min(b.length,255);m.load(buffer,java.util.Arrays.copyOf(b,n));m.writeByte(buffer+n,0);}
		}catch(java.io.IOException e){return -36;}
		return exists?0:-43;
	}
	static void define(PpcLibraries l){
		// memory
		l.register(LIB,"memcpy",(r,c)->{int n=r.arg(2);if(n>0)r.memory.copy(r.arg(1),r.arg(0),n);r.ret(r.arg(0));});
		l.register(LIB,"memmove",(r,c)->{int n=r.arg(2);if(n>0)r.memory.copy(r.arg(1),r.arg(0),n);r.ret(r.arg(0));});
		l.register(LIB,"memset",(r,c)->{int n=r.arg(2);if(n>0)r.memory.fill(r.arg(0),n,r.arg(1)&0xff);r.ret(r.arg(0));});
		l.register(LIB,"memcmp",(r,c)->{int a=r.arg(0),b=r.arg(1),n=r.arg(2),res=0;for(int i=0;i<n;i++){int x=r.memory.readByte(a+i),y=r.memory.readByte(b+i);if(x!=y){res=x-y;break;}}r.ret(res);});
		l.register(LIB,"memchr",(r,c)->{int a=r.arg(0),ch=r.arg(1)&0xff,n=r.arg(2),res=0;for(int i=0;i<n;i++)if(r.memory.readByte(a+i)==ch){res=a+i;break;}r.ret(res);});
		// strings
		l.register(LIB,"strlen",(r,c)->r.ret(strlen(r,r.arg(0))));
		l.register(LIB,"strcpy",(r,c)->{int d=r.arg(0),s=r.arg(1);int n=strlen(r,s);r.memory.copy(s,d,n+1);r.ret(d);});
		l.register(LIB,"strncpy",(r,c)->{int d=r.arg(0),s=r.arg(1),n=r.arg(2);int i=0;for(;i<n;i++){int b=r.memory.readByte(s+i);r.memory.writeByte(d+i,b);if(b==0)break;}for(;i<n;i++)r.memory.writeByte(d+i,0);r.ret(d);});
		l.register(LIB,"strcat",(r,c)->{int d=r.arg(0),s=r.arg(1);int e=d+strlen(r,d);r.memory.copy(s,e,strlen(r,s)+1);r.ret(d);});
		l.register(LIB,"strncat",(r,c)->{int d=r.arg(0),s=r.arg(1),n=r.arg(2);int e=d+strlen(r,d);int i=0;for(;i<n;i++){int b=r.memory.readByte(s+i);if(b==0)break;r.memory.writeByte(e+i,b);}r.memory.writeByte(e+i,0);r.ret(d);});
		l.register(LIB,"strcmp",(r,c)->r.ret(strncmp(r,r.arg(0),r.arg(1),Integer.MAX_VALUE)));
		l.register(LIB,"strncmp",(r,c)->r.ret(strncmp(r,r.arg(0),r.arg(1),r.arg(2))));
		l.register(LIB,"strcoll",(r,c)->r.ret(strncmp(r,r.arg(0),r.arg(1),Integer.MAX_VALUE)));
		l.register(LIB,"strchr",(r,c)->{int s=r.arg(0),ch=r.arg(1)&0xff;for(int i=0;;i++){int b=r.memory.readByte(s+i);if(b==ch){r.ret(s+i);return;}if(b==0){r.ret(0);return;}}});
		l.register(LIB,"strrchr",(r,c)->{int s=r.arg(0),ch=r.arg(1)&0xff,res=0;for(int i=0;;i++){int b=r.memory.readByte(s+i);if(b==ch)res=s+i;if(b==0)break;}r.ret(res);});
		l.register(LIB,"strstr",(r,c)->{byte[] h=r.cBytes(r.arg(0)),n=r.cBytes(r.arg(1));int at=indexOf(h,n);r.ret(at<0?0:r.arg(0)+at);});
		l.register(LIB,"strpbrk",(r,c)->{byte[] h=r.cBytes(r.arg(0)),set=r.cBytes(r.arg(1));for(int i=0;i<h.length;i++)for(byte b:set)if(h[i]==b){r.ret(r.arg(0)+i);return;}r.ret(0);});
		l.register(LIB,"strspn",(r,c)->{byte[] h=r.cBytes(r.arg(0)),set=r.cBytes(r.arg(1));int i=0;outer:for(;i<h.length;i++){for(byte b:set)if(h[i]==b)continue outer;break;}r.ret(i);});
		l.register(LIB,"strcspn",(r,c)->{byte[] h=r.cBytes(r.arg(0)),set=r.cBytes(r.arg(1));int i=0;outer:for(;i<h.length;i++){for(byte b:set)if(h[i]==b)break outer;}r.ret(i);});
		final int[] tokenState={0};
		l.register(LIB,"strtok",(r,c)->{int s=r.arg(0);if(s==0)s=tokenState[0];if(s==0){r.ret(0);return;}byte[] set=r.cBytes(r.arg(1));
			while(r.memory.readByte(s)!=0&&contains(set,r.memory.readByte(s)))s++;
			if(r.memory.readByte(s)==0){tokenState[0]=0;r.ret(0);return;}
			int start=s;while(r.memory.readByte(s)!=0&&!contains(set,r.memory.readByte(s)))s++;
			if(r.memory.readByte(s)!=0){r.memory.writeByte(s,0);tokenState[0]=s+1;}else tokenState[0]=0;
			r.ret(start);});
		l.register(LIB,"strerror",(r,c)->r.ret(staticString(r,"error "+r.arg(0))));
		// ctype
		l.register(LIB,"toupper",(r,c)->{int ch=r.arg(0);r.ret(ch>='a'&&ch<='z'?ch-32:ch);});
		l.register(LIB,"tolower",(r,c)->{int ch=r.arg(0);r.ret(ch>='A'&&ch<='Z'?ch+32:ch);});
		l.register(LIB,"isalpha",(r,c)->{int ch=r.arg(0);r.ret(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'?1:0);});
		l.register(LIB,"isdigit",(r,c)->{int ch=r.arg(0);r.ret(ch>='0'&&ch<='9'?1:0);});
		l.register(LIB,"isalnum",(r,c)->{int ch=r.arg(0);r.ret(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'||ch>='0'&&ch<='9'?1:0);});
		l.register(LIB,"isspace",(r,c)->{int ch=r.arg(0);r.ret(ch==' '||ch>=9&&ch<=13?1:0);});
		l.register(LIB,"isupper",(r,c)->{int ch=r.arg(0);r.ret(ch>='A'&&ch<='Z'?1:0);});
		l.register(LIB,"islower",(r,c)->{int ch=r.arg(0);r.ret(ch>='a'&&ch<='z'?1:0);});
		l.register(LIB,"isxdigit",(r,c)->{int ch=r.arg(0);r.ret(ch>='0'&&ch<='9'||ch>='a'&&ch<='f'||ch>='A'&&ch<='F'?1:0);});
		l.register(LIB,"isprint",(r,c)->{int ch=r.arg(0);r.ret(ch>=32&&ch<127?1:0);});
		l.register(LIB,"isgraph",(r,c)->{int ch=r.arg(0);r.ret(ch>32&&ch<127?1:0);});
		l.register(LIB,"iscntrl",(r,c)->{int ch=r.arg(0);r.ret(ch>=0&&ch<32||ch==127?1:0);});
		l.register(LIB,"ispunct",(r,c)->{int ch=r.arg(0);r.ret(ch>32&&ch<127&&!Character.isLetterOrDigit(ch)?1:0);});
		// MPW runtime (MacRuntime.p): TrapAvailable(trap: INTEGER): BOOLEAN - NGetTrapAddress(trap) differs from _Unimplemented's.
		// Klik & Play games (動物園) test it for System 7 and stop with "システム 7.0 以上が必要です" when it answers false.
		l.register(LIB,"TrapAvailable",(r,c)->{int word=r.arg(0)&0xffff;
			if((word&0x0800)!=0&&(word&0x07ff)>=0x0400)word=0xa89f; // beyond the Toolbox trap table: _Unimplemented
			r.ret(r.toolbox.cpu.patchedTrap(word)!=0||r.toolbox.implemented(word)?1:0);});
		// IntEnv.h, MPW's path utilities (MPW Shell 3.5's PowerPC code finds its commands with them): the path - Pascal for the
		// first three, C for ResolvePath - taken from (volume, directory) as FSMakeFSSpec takes it; alias files are not followed
		l.register(LIB,"MakeResolvedFSSpec",(r,c)->r.ret(resolve(r,(short)r.arg(0),r.arg(1),pascalBytes(r,r.arg(2)),r.arg(3),0,r.arg(4),r.arg(5),r.arg(6))));
		l.register(LIB,"ResolveFolderAliases",(r,c)->r.ret(resolve(r,(short)r.arg(0),r.arg(1),pascalBytes(r,r.arg(2)),r.arg(4),0,r.arg(5),r.arg(6),r.arg(7))));
		l.register(LIB,"MakeResolvedPath",(r,c)->r.ret(resolve(r,(short)r.arg(0),r.arg(1),pascalBytes(r,r.arg(2)),0,r.arg(4),r.arg(5),r.arg(6),r.arg(7))));
		l.register(LIB,"ResolvePath",(r,c)->r.ret(resolve(r,0,0,r.cBytes(r.arg(0)),0,r.arg(1),r.arg(2),r.arg(3),0)));
		// numbers
		l.register(LIB,"abs",(r,c)->r.ret(Math.abs(r.arg(0))));
		l.register(LIB,"labs",(r,c)->r.ret(Math.abs(r.arg(0))));
		l.register(LIB,"atoi",(r,c)->r.ret((int)parseLong(r,r.arg(0),10,null)));
		l.register(LIB,"atol",(r,c)->r.ret((int)parseLong(r,r.arg(0),10,null)));
		l.register(LIB,"strtol",(r,c)->{int[] end={0};long v=parseLong(r,r.arg(0),r.arg(2),end);if(r.arg(1)!=0)r.memory.writeLong(r.arg(1),end[0]);r.ret((int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,v)));});
		l.register(LIB,"strtoul",(r,c)->{int[] end={0};long v=parseLong(r,r.arg(0),r.arg(2),end);if(r.arg(1)!=0)r.memory.writeLong(r.arg(1),end[0]);r.ret((int)v);});
		l.register(LIB,"atof",(r,c)->r.retD(parseDouble(r,r.arg(0),null)));
		l.register(LIB,"strtod",(r,c)->{int[] end={0};double v=parseDouble(r,r.arg(0),end);if(r.arg(1)!=0)r.memory.writeLong(r.arg(1),end[0]);r.retD(v);});
		l.register(LIB,"div",(r,c)->{int p=r.arg(0),a=r.arg(1),b=r.arg(2);r.memory.writeLong(p,b==0?0:a/b);r.memory.writeLong(p+4,b==0?0:a%b);});
		l.register(LIB,"ldiv",(r,c)->{int p=r.arg(0),a=r.arg(1),b=r.arg(2);r.memory.writeLong(p,b==0?0:a/b);r.memory.writeLong(p+4,b==0?0:a%b);});
		final long[] seed={1};
		l.register(LIB,"rand",(r,c)->{seed[0]=seed[0]*1103515245L+12345L;r.ret((int)((seed[0]>>>16)&0x7fff));});
		l.register(LIB,"srand",(r,c)->seed[0]=r.arg(0)&0xffffffffL);
		// memory allocation on the Memory Manager
		l.register(LIB,"malloc",(r,c)->{r.trap(0xa11e,Math.max(1,r.arg(0)),0,0);r.ret(r.lastA0);});
		l.register(LIB,"calloc",(r,c)->{long n=(long)r.arg(0)*r.arg(1);r.trap(0xa31e,(int)Math.max(1,n),0,0);r.ret(r.lastA0);});
		l.register(LIB,"free",(r,c)->{if(r.arg(0)!=0)r.trap(0xa01f,0,r.arg(0),0);});
		l.register(LIB,"realloc",(r,c)->{
			int p=r.arg(0),n=r.arg(1);
			if(p==0){r.trap(0xa11e,Math.max(1,n),0,0);r.ret(r.lastA0);return;}
			if(n==0){r.trap(0xa01f,0,p,0);r.ret(0);return;}
			if((short)r.trap(0xa020,n,p,0)==0){r.ret(p);return;} // SetPtrSize in place
			int old=r.trap(0xa021,0,p,0);r.trap(0xa11e,n,0,0);int q=r.lastA0;if(q==0){r.ret(0);return;}
			r.memory.copy(p,q,Math.min(old,n));r.trap(0xa01f,0,p,0);r.ret(q);});
		// sorting and searching (the comparison function is PowerPC code)
		l.register(LIB,"qsort",(r,c)->qsort(r,r.arg(0),r.arg(1),r.arg(2),r.arg(3)));
		l.register(LIB,"bsearch",(r,c)->{int key=r.arg(0),base=r.arg(1),n=r.arg(2),size=r.arg(3),cmp=r.arg(4);int lo=0,hi=n-1;
			while(lo<=hi){int mid=(lo+hi)>>>1;int res=r.cpu.call(cmp,key,base+mid*size);if(res==0){r.ret(base+mid*size);return;}if(res<0)hi=mid-1;else lo=mid+1;}r.ret(0);});
		// formatted output and input
		l.register(LIB,"sprintf",(r,c)->{byte[] out=format(r,r.cBytes(r.arg(1)),new VarArgs(r,2));writeC(r,r.arg(0),out,Integer.MAX_VALUE);r.ret(out.length);});
		l.register(LIB,"vsprintf",(r,c)->{byte[] out=format(r,r.cBytes(r.arg(1)),new VarArgs(r,r.arg(2),true));writeC(r,r.arg(0),out,Integer.MAX_VALUE);r.ret(out.length);});
		l.register(LIB,"snprintf",(r,c)->{byte[] out=format(r,r.cBytes(r.arg(2)),new VarArgs(r,3));if(r.arg(1)>0)writeC(r,r.arg(0),out,r.arg(1)-1);r.ret(out.length);});
		l.register(LIB,"vsnprintf",(r,c)->{byte[] out=format(r,r.cBytes(r.arg(2)),new VarArgs(r,r.arg(3),true));if(r.arg(1)>0)writeC(r,r.arg(0),out,r.arg(1)-1);r.ret(out.length);});
		l.register(LIB,"printf",(r,c)->{byte[] out=format(r,r.cBytes(r.arg(0)),new VarArgs(r,1));console(r,out);r.ret(out.length);});
		l.register(LIB,"vprintf",(r,c)->{byte[] out=format(r,r.cBytes(r.arg(0)),new VarArgs(r,r.arg(1),true));console(r,out);r.ret(out.length);});
		l.register(LIB,"puts",(r,c)->{byte[] b=r.cBytes(r.arg(0));console(r,b);console(r,new byte[]{'\n'});r.ret(0);});
		l.register(LIB,"putchar",(r,c)->{console(r,new byte[]{(byte)r.arg(0)});r.ret(r.arg(0)&0xff);});
		l.register(LIB,"sscanf",(r,c)->r.ret(scan(r,r.cBytes(r.arg(0)),r.cBytes(r.arg(1)),new VarArgs(r,2))));
		l.register(LIB,"vsscanf",(r,c)->r.ret(scan(r,r.cBytes(r.arg(0)),r.cBytes(r.arg(1)),new VarArgs(r,r.arg(2),true))));
		// time
		l.register(LIB,"time",(r,c)->{int t=(int)Toolbox68k.macNow();if(r.arg(0)!=0)r.memory.writeLong(r.arg(0),t);r.ret(t);});
		l.register(LIB,"clock",(r,c)->r.ret((int)((System.nanoTime()-START)/(1_000_000_000L/60))));
		l.register(LIB,"difftime",(r,c)->r.retD((double)((r.arg(0)&0xffffffffL)-(r.arg(1)&0xffffffffL))));
		// control
		l.register(LIB,"exit",(r,c)->{System.out.flush();r.trap(0xa9f4,0,0,0);});
		l.register(LIB,"_exit",(r,c)->r.trap(0xa9f4,0,0,0));
		l.register(LIB,"abort",(r,c)->{System.err.println("[ppc "+r.app.name+"] abort()");r.trap(0xa9f4,0,0,0);});
		l.register(LIB,"atexit",(r,c)->r.ret(0));
		l.register(LIB,"getenv",(r,c)->r.ret(0));
		l.register(LIB,"signal",(r,c)->r.ret(0));
		l.register(LIB,"_BreakPoint",(r,c)->{});
		l.register(LIB,"__setjmp",PpcStdCLib::setjmp);
		l.register(LIB,"setjmp",PpcStdCLib::setjmp);
		l.register(LIB,"longjmp",PpcStdCLib::longjmp);
		l.register(LIB,"__longjmp",PpcStdCLib::longjmp);
		PpcStdio.define(l); // file streams
	}

	private static final long START=System.nanoTime();

	// jmp_buf: LR, CR, SP, TOC (the caller's saved one), host call depth, r13-r31, f14-f31, FPSCR
	private static void setjmp(PpcRuntime r,PpcCpu c) throws M68kException {
		int b=r.arg(0);M68kMemory m=r.memory;
		m.writeLong(b,c.lr);m.writeLong(b+4,c.cr);m.writeLong(b+8,c.r[1]);m.writeLong(b+12,m.readLong(c.r[1]+20));m.writeLong(b+16,c.nestingDepth());
		for(int i=13;i<32;i++)m.writeLong(b+20+4*(i-13),c.r[i]);
		for(int i=14;i<32;i++){long v=c.f[i];m.writeLong(b+96+8*(i-14),(int)(v>>>32));m.writeLong(b+100+8*(i-14),(int)v);}
		m.writeLong(b+240,c.fpscr);
		r.ret(0);
	}
	private static void longjmp(PpcRuntime r,PpcCpu c) throws M68kException {
		final int b=r.arg(0);final int value=r.arg(1)==0?1:r.arg(1);final M68kMemory m=r.memory;
		int depth=m.readLong(b+16);
		final int lr=m.readLong(b),cr=m.readLong(b+4),sp=m.readLong(b+8),fpscr=m.readLong(b+240);
		final int[] gr=new int[19];final long[] fr=new long[18];
		for(int i=0;i<19;i++)gr[i]=m.readLong(b+20+4*i);
		for(int i=0;i<18;i++)fr[i]=((long)m.readLong(b+96+8*i)<<32)|(m.readLong(b+100+8*i)&0xffffffffL);
		Runnable apply=()->{c.lr=lr;c.pc=lr;c.cr=cr;c.r[1]=sp;c.fpscr=fpscr;System.arraycopy(gr,0,c.r,13,19);System.arraycopy(fr,0,c.f,14,18);c.r[3]=value;};
		if(depth==c.nestingDepth()){apply.run();return;} // the gate continues at lr (= pc)
		throw new PpcCpu.Unwind(depth,apply);
	}

	static int strlen(PpcRuntime r,int p) throws M68kCpuException {int n=0;while(r.memory.readByte(p+n)!=0)n++;return n;}
	private static int strncmp(PpcRuntime r,int a,int b,int n) throws M68kCpuException {
		for(int i=0;i<n;i++){int x=r.memory.readByte(a+i),y=r.memory.readByte(b+i);if(x!=y)return x-y;if(x==0)return 0;}
		return 0;
	}
	private static boolean contains(byte[] set,int b){for(byte x:set)if((x&0xff)==b)return true;return false;}
	private static int indexOf(byte[] h,byte[] n){outer:for(int i=0;i+n.length<=h.length;i++){for(int j=0;j<n.length;j++)if(h[i+j]!=n[j])continue outer;return i;}return -1;}
	private static final java.util.Map<String,Integer> STATIC_STRINGS=new java.util.HashMap<String,Integer>();
	private static int staticString(PpcRuntime r,String s) throws M68kCpuException {
		Integer p=STATIC_STRINGS.get(s);if(p!=null)return p;byte[] b=PpcLibraries.macRoman(s);int at=r.allocate(b.length+1,4);r.memory.load(at,b);STATIC_STRINGS.put(s,at);return at;
	}
	private static void writeC(PpcRuntime r,int dst,byte[] b,int max) throws M68kCpuException {int n=Math.min(b.length,max);r.memory.load(dst,b,0,n);r.memory.writeByte(dst+n,0);}
	static void console(PpcRuntime r,byte[] b){
		String s=new String(b,java.nio.charset.Charset.forName("MacRoman")).replace('\r','\n');
		System.out.print(s);
	}

	private static long parseLong(PpcRuntime r,int p,int base,int[] end) throws M68kCpuException {
		int i=0;while(isSpace(r.memory.readByte(p+i)))i++;
		boolean neg=false;int ch=r.memory.readByte(p+i);
		if(ch=='-'||ch=='+'){neg=ch=='-';i++;}
		if((base==0||base==16)&&r.memory.readByte(p+i)=='0'&&(r.memory.readByte(p+i+1)|32)=='x'){base=16;i+=2;}
		else if(base==0&&r.memory.readByte(p+i)=='0')base=8;
		if(base==0)base=10;
		long v=0;int start=i;
		for(;;){int d=digit(r.memory.readByte(p+i));if(d<0||d>=base)break;v=v*base+d;i++;if(v>0xffffffffL*4)v=0xffffffffL*4;}
		if(end!=null)end[0]=i==start?p:p+i;
		return neg?-v:v;
	}
	private static int digit(int ch){if(ch>='0'&&ch<='9')return ch-'0';if(ch>='a'&&ch<='z')return ch-'a'+10;if(ch>='A'&&ch<='Z')return ch-'A'+10;return -1;}
	private static boolean isSpace(int ch){return ch==' '||ch>=9&&ch<=13;}
	private static double parseDouble(PpcRuntime r,int p,int[] end) throws M68kCpuException {
		int i=0;while(isSpace(r.memory.readByte(p+i)))i++;
		StringBuilder sb=new StringBuilder();int start=i;
		int ch=r.memory.readByte(p+i);if(ch=='+'||ch=='-'){sb.append((char)ch);i++;}
		boolean digits=false;
		while(Character.isDigit(r.memory.readByte(p+i))){sb.append((char)r.memory.readByte(p+i));i++;digits=true;}
		if(r.memory.readByte(p+i)=='.'){sb.append('.');i++;while(Character.isDigit(r.memory.readByte(p+i))){sb.append((char)r.memory.readByte(p+i));i++;digits=true;}}
		if(digits&&(r.memory.readByte(p+i)|32)=='e'){int save=i;StringBuilder e=new StringBuilder("e");i++;int s=r.memory.readByte(p+i);if(s=='+'||s=='-'){e.append((char)s);i++;}
			boolean ed=false;while(Character.isDigit(r.memory.readByte(p+i))){e.append((char)r.memory.readByte(p+i));i++;ed=true;}if(ed)sb.append(e);else i=save;}
		double v=0;
		if(digits){try{v=Double.parseDouble(sb.toString());}catch(NumberFormatException x){digits=false;}}
		if(end!=null)end[0]=digits?p+i:p;
		if(!digits&&end==null)return 0;
		return digits?v:0;
	}

	private static void qsort(PpcRuntime r,int base,int n,int size,int cmp) throws M68kException {
		if(n<2||size<=0)return;
		byte[][] items=new byte[n][];for(int i=0;i<n;i++)items[i]=r.memory.readBytes(base+i*size,size);
		int a=r.allocate(size,8),b=r.allocate(size,8);
		final M68kException[] failure={null};
		try{
			java.util.Arrays.sort(items,(x,y)->{if(failure[0]!=null)return 0;try{r.memory.load(a,x);r.memory.load(b,y);return r.cpu.call(cmp,a,b);}catch(M68kException e){failure[0]=e;return 0;}});
		}catch(IllegalArgumentException inconsistent){/* a comparison that is not a total order: keep what the sort did */}
		r.memory.free(a);r.memory.free(b);
		if(failure[0]!=null)throw failure[0];
		for(int i=0;i<n;i++)r.memory.load(base+i*size,items[i]);
	}

	// ------------------------------------------------------------------ printf

	static byte[] format(PpcRuntime r,byte[] fmt,VarArgs args) throws M68kCpuException {
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		for(int i=0;i<fmt.length;i++){
			int ch=fmt[i]&0xff;
			if(ch!='%'){out.write(ch);continue;}
			if(++i>=fmt.length)break;
			boolean left=false,plus=false,space=false,alt=false,zero=false;
			for(;i<fmt.length;i++){int f=fmt[i];if(f=='-')left=true;else if(f=='+')plus=true;else if(f==' ')space=true;else if(f=='#')alt=true;else if(f=='0')zero=true;else break;}
			int width=-1,prec=-1;
			if(i<fmt.length&&fmt[i]=='*'){width=args.nextInt();if(width<0){left=true;width=-width;}i++;}
			else{while(i<fmt.length&&fmt[i]>='0'&&fmt[i]<='9'){width=(width<0?0:width*10)+fmt[i]-'0';i++;}}
			if(i<fmt.length&&fmt[i]=='.'){i++;prec=0;if(i<fmt.length&&fmt[i]=='*'){prec=args.nextInt();i++;}else while(i<fmt.length&&fmt[i]>='0'&&fmt[i]<='9'){prec=prec*10+fmt[i]-'0';i++;}}
			boolean h=false,longLong=false;
			while(i<fmt.length&&(fmt[i]=='h'||fmt[i]=='l'||fmt[i]=='L'||fmt[i]=='q')){if(fmt[i]=='h')h=true;if(fmt[i]=='l'&&i+1<fmt.length&&fmt[i+1]=='l'){longLong=true;i++;}if(fmt[i]=='q')longLong=true;i++;}
			if(i>=fmt.length)break;
			char conv=(char)fmt[i];
			String body;boolean numeric=true;String sign="";
			switch(conv){
				case 'd':case 'i':{long v=longLong?((long)args.nextInt()<<32)|(args.nextInt()&0xffffffffL):args.nextInt();if(h)v=(short)v;
					sign=v<0?"-":plus?"+":space?" ":"";String digits=Long.toString(Math.abs(v));if(v==Long.MIN_VALUE)digits="9223372036854775808";
					if(prec>=0){while(digits.length()<prec)digits="0"+digits;if(prec==0&&v==0)digits="";}
					body=digits;break;}
				case 'u':case 'x':case 'X':case 'o':{long v=longLong?((long)args.nextInt()<<32)|(args.nextInt()&0xffffffffL):args.nextInt()&0xffffffffL;if(h)v&=0xffff;
					String digits=conv=='u'?Long.toUnsignedString(v):conv=='o'?Long.toOctalString(v):Long.toHexString(v);if(conv=='X')digits=digits.toUpperCase();
					if(prec>=0){while(digits.length()<prec)digits="0"+digits;if(prec==0&&v==0)digits="";}
					if(alt&&v!=0){if(conv=='x')sign="0x";else if(conv=='X')sign="0X";else if(conv=='o'&&!digits.startsWith("0"))digits="0"+digits;}
					body=digits;break;}
				case 'p':{int v=args.nextInt();body=String.format("%08x",v);sign="0x";break;}
				case 'c':{out.writeBytes(pad(new byte[]{(byte)args.nextInt()},width,left,false));continue;}
				case 's':{int p=args.nextInt();byte[] b=p==0?"(null)".getBytes():r.cBytes(p);if(prec>=0&&b.length>prec)b=java.util.Arrays.copyOf(b,prec);out.writeBytes(pad(b,width,left,false));continue;}
				case 'P':{int p=args.nextInt();byte[] b=p==0?new byte[0]:r.pBytes(p);if(prec>=0&&b.length>prec)b=java.util.Arrays.copyOf(b,prec);out.writeBytes(pad(b,width,left,false));continue;} // MPW: Pascal string
				case 'n':{int p=args.nextInt();if(p!=0)r.memory.writeLong(p,out.size());continue;}
				case 'f':case 'F':case 'e':case 'E':case 'g':case 'G':{
					double v=args.nextDouble();
					if(Double.isNaN(v)||Double.isInfinite(v)){sign=v<0?"-":plus?"+":space?" ":"";body=Double.isNaN(v)?"NAN":"INF";if(Character.isLowerCase(conv))body=body.toLowerCase();zero=false;break;}
					sign=Double.doubleToRawLongBits(v)<0?"-":plus?"+":space?" ":"";v=Math.abs(v);
					if(prec<0)prec=6;
					body=formatDouble(v,conv,prec,alt);break;}
				case '%':out.write('%');continue;
				default:out.write('%');out.write(conv);continue;
			}
			if(numeric){
				int len=sign.length()+body.length();
				if(width>len){
					if(left){out.writeBytes((sign+body).getBytes());for(int k=len;k<width;k++)out.write(' ');}
					else if(zero&&(prec<0||"feEgG".indexOf(conv)>=0)){out.writeBytes(sign.getBytes());for(int k=len;k<width;k++)out.write('0');out.writeBytes(body.getBytes());}
					else{for(int k=len;k<width;k++)out.write(' ');out.writeBytes((sign+body).getBytes());}
				}else out.writeBytes((sign+body).getBytes());
			}
		}
		return out.toByteArray();
	}
	private static byte[] pad(byte[] b,int width,boolean left,boolean zero){
		if(width<=b.length)return b;
		byte[] o=new byte[width];
		if(left){System.arraycopy(b,0,o,0,b.length);java.util.Arrays.fill(o,b.length,width,(byte)' ');}
		else{java.util.Arrays.fill(o,0,width-b.length,(byte)(zero?'0':' '));System.arraycopy(b,0,o,width-b.length,b.length);}
		return o;
	}
	static String formatDouble(double v,char conv,int prec,boolean alt){
		switch(conv){
			case 'f':case 'F':{String s=new java.math.BigDecimal(v).setScale(prec,java.math.RoundingMode.HALF_EVEN).toPlainString();if(alt&&prec==0)s+=".";return s;}
			case 'e':case 'E':{String s=expForm(v,prec,alt);return conv=='E'?s.toUpperCase():s;}
			default:{ // g
				int p=prec==0?1:prec;
				if(v==0){String s=alt?"0."+"0".repeat(Math.max(0,p-1)):"0";return s;}
				String e=expForm(v,p-1,true);int x=Integer.parseInt(e.substring(e.indexOf('e')+1));
				String s;
				if(x<-4||x>=p)s=expForm(v,p-1,alt);
				else s=new java.math.BigDecimal(v).setScale(p-1-x,java.math.RoundingMode.HALF_EVEN).toPlainString();
				if(!alt){ // strip trailing zeros of the fraction
					int ePos=s.indexOf('e');String mant=ePos<0?s:s.substring(0,ePos),exp=ePos<0?"":s.substring(ePos);
					if(mant.indexOf('.')>=0){mant=mant.replaceAll("0+$","");if(mant.endsWith("."))mant=mant.substring(0,mant.length()-1);}
					s=mant+exp;
				}
				return conv=='G'?s.toUpperCase():s;
			}
		}
	}
	private static String expForm(double v,int prec,boolean alt){
		if(v==0){StringBuilder sb=new StringBuilder("0");if(prec>0||alt)sb.append('.');for(int i=0;i<prec;i++)sb.append('0');return sb.append("e+00").toString();}
		java.math.BigDecimal bd=new java.math.BigDecimal(v).round(new java.math.MathContext(prec+1,java.math.RoundingMode.HALF_EVEN));
		String digits=bd.unscaledValue().abs().toString();int exp=digits.length()-1-bd.scale();
		while(digits.length()<prec+1)digits+="0";
		if(digits.length()>prec+1)digits=digits.substring(0,prec+1);
		StringBuilder sb=new StringBuilder();sb.append(digits.charAt(0));
		if(prec>0||alt)sb.append('.');sb.append(digits,1,digits.length());
		sb.append('e').append(exp<0?'-':'+');int a=Math.abs(exp);if(a<10)sb.append('0');sb.append(a);
		return sb.toString();
	}

	// ------------------------------------------------------------------ scanf

	static int scan(PpcRuntime r,byte[] in,byte[] fmt,VarArgs args) throws M68kCpuException {
		int ip=0,assigned=0;
		for(int i=0;i<fmt.length;i++){
			int f=fmt[i]&0xff;
			if(isSpace(f)){while(ip<in.length&&isSpace(in[ip]&0xff))ip++;continue;}
			if(f!='%'){if(ip<in.length&&(in[ip]&0xff)==f){ip++;continue;}break;}
			if(++i>=fmt.length)break;
			boolean suppress=false;if(fmt[i]=='*'){suppress=true;i++;}
			int width=0;while(i<fmt.length&&fmt[i]>='0'&&fmt[i]<='9'){width=width*10+fmt[i]-'0';i++;}
			boolean h=false,l=false;while(i<fmt.length&&(fmt[i]=='h'||fmt[i]=='l'||fmt[i]=='L')){if(fmt[i]=='h')h=true;else l=true;i++;}
			if(i>=fmt.length)break;
			char conv=(char)fmt[i];
			if(conv=='%'){if(ip<in.length&&in[ip]=='%'){ip++;continue;}break;}
			if(conv!='c'&&conv!='['&&conv!='n')while(ip<in.length&&isSpace(in[ip]&0xff))ip++;
			int max=width>0?width:Integer.MAX_VALUE;
			switch(conv){
				case 'd':case 'i':case 'u':case 'x':case 'X':case 'o':{
					int base=conv=='x'||conv=='X'?16:conv=='o'?8:conv=='i'?0:10;
					int start=ip;int end=Math.min(in.length,ip+Math.min(max,64));
					StringBuilder sb=new StringBuilder();int p=ip;
					if(p<end&&(in[p]=='-'||in[p]=='+'))sb.append((char)in[p++]);
					if(base==0){if(p+1<end&&in[p]=='0'&&(in[p+1]|32)=='x'){base=16;p+=2;}else if(p<end&&in[p]=='0')base=8;else base=10;}
					else if(base==16&&p+1<end&&in[p]=='0'&&(in[p+1]|32)=='x')p+=2;
					int digitsStart=p;while(p<end&&digit(in[p]&0xff)>=0&&digit(in[p]&0xff)<base)sb.append((char)in[p++]);
					if(p==digitsStart){return assigned==0&&ip>=in.length?-1:assigned;}
					ip=p;
					long v=new java.math.BigInteger(sb.toString().replace("+",""),base).longValue();
					if(!suppress){int dst=args.nextInt();if(h)r.memory.writeWord(dst,(int)v);else r.memory.writeLong(dst,(int)v);assigned++;}
					if(start<0)break;
					break;}
				case 'f':case 'e':case 'g':case 'E':case 'G':{
					int end=Math.min(in.length,ip+Math.min(max,128));int p=ip;StringBuilder sb=new StringBuilder();
					if(p<end&&(in[p]=='-'||in[p]=='+'))sb.append((char)in[p++]);
					boolean digits=false;while(p<end&&Character.isDigit(in[p])){sb.append((char)in[p++]);digits=true;}
					if(p<end&&in[p]=='.'){sb.append('.');p++;while(p<end&&Character.isDigit(in[p])){sb.append((char)in[p++]);digits=true;}}
					if(digits&&p<end&&(in[p]|32)=='e'){int save=p;StringBuilder e=new StringBuilder("e");p++;if(p<end&&(in[p]=='-'||in[p]=='+'))e.append((char)in[p++]);boolean ed=false;while(p<end&&Character.isDigit(in[p])){e.append((char)in[p++]);ed=true;}if(ed)sb.append(e);else p=save;}
					if(!digits)return assigned==0&&ip>=in.length?-1:assigned;
					ip=p;double v=Double.parseDouble(sb.toString());
					if(!suppress){int dst=args.nextInt();if(l){long b=Double.doubleToRawLongBits(v);r.memory.writeLong(dst,(int)(b>>>32));r.memory.writeLong(dst+4,(int)b);}else r.memory.writeLong(dst,Float.floatToRawIntBits((float)v));assigned++;}
					break;}
				case 's':{
					int start=ip;while(ip<in.length&&!isSpace(in[ip]&0xff)&&ip-start<max)ip++;
					if(ip==start)return assigned==0?-1:assigned;
					if(!suppress){int dst=args.nextInt();r.memory.load(dst,in,start,ip-start);r.memory.writeByte(dst+ip-start,0);assigned++;}
					break;}
				case 'c':{
					int n=width>0?width:1;if(ip+n>in.length)return assigned==0?-1:assigned;
					if(!suppress){int dst=args.nextInt();r.memory.load(dst,in,ip,n);assigned++;}
					ip+=n;break;}
				case '[':{
					i++;boolean negate=false;if(i<fmt.length&&fmt[i]=='^'){negate=true;i++;}
					java.util.BitSet set=new java.util.BitSet(256);
					if(i<fmt.length&&fmt[i]==']'){set.set(']');i++;}
					while(i<fmt.length&&fmt[i]!=']'){if(i+2<fmt.length&&fmt[i+1]=='-'&&fmt[i+2]!=']'){for(int k=fmt[i]&0xff;k<=(fmt[i+2]&0xff);k++)set.set(k);i+=3;}else{set.set(fmt[i]&0xff);i++;}}
					int start=ip;while(ip<in.length&&set.get(in[ip]&0xff)!=negate&&ip-start<max)ip++;
					if(ip==start)return assigned==0?-1:assigned;
					if(!suppress){int dst=args.nextInt();r.memory.load(dst,in,start,ip-start);r.memory.writeByte(dst+ip-start,0);assigned++;}
					break;}
				case 'n':{if(!suppress){int dst=args.nextInt();r.memory.writeLong(dst,ip);}break;}
				default:return assigned;
			}
		}
		return assigned;
	}
}

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.text.BreakIterator;
import java.text.CollationKey;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CarbonLib's library-code utilities: the functions with no 68K trap behind them that CarbonLib (or InterfaceLib's
 * companion libraries) implemented in PowerPC code - Math64 on the {@code wide} structures, MathLib's {@code long double}
 * (double-double) functions, the DriverSynchronization atomics, PLStringFuncs and the C/Pascal string copies, Unicode
 * collation and text breaks (UnicodeUtilities), 'uchr'/KCHR key translation, MacLocales, UTC date-times and the
 * DriverServices time arithmetic, a handful of Memory/File/Power/Component/QuickDraw glue, and the managers a Mac OS 9
 * system could lack (FontSync, Find By Content, Language Analysis, Dictionary Manager, NSL, file signing, the ColorSync
 * scripting plug-in), which answer as a system without those extensions does: an error from their own headers, outputs
 * cleared, a library version of 0.
 * <p>
 * Calling conventions to keep in mind: a {@code wide}/{@code SInt64} argument takes two word slots (high, low); a
 * function returning one gets a hidden result pointer in r3 and its declared arguments start at slot 1. A PowerPC
 * {@code long double} is a double-double: its head and tail arrive in two FPRs (and take four word slots); results go
 * back in f1 (head) and f2 (tail).
 */
final class PpcCarbonUtilities {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	private PpcLibraries libraries;
	PpcCarbonUtilities(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int NO_ERR=0,PARAM_ERR=-50,UNIMP_ERR=-4,USER_CANCELED=-128,FNF_ERR=-43;

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private void retB(boolean b){rt.ret(b?1:0);}

	void define(PpcLibraries l){
		libraries=l;
		defineMath64(l);defineLongDouble(l);defineAtomics(l);defineStrings(l);defineUnicode(l);defineLocales(l);
		defineTime(l);defineSystem(l);defineComponents(l);defineAbsentServices(l);
		defineColorSyncExtras(l);defineImageCapture(l);defineFolderAndResourceFiles(l);defineCarbon16Misc(l);defineSoundConverter(l);defineCarbon16Upps(l);
	}

	// ================================================================== Math64

	/** The 64-bit value in word slots {@code i} (high) and {@code i+1} (low). */
	private long s64(int i) throws M68kCpuException {return ((long)arg(i)<<32)|(arg(i+1)&0xffffffffL);}
	private void put64(int p,long v) throws M68kCpuException {memory.writeLong(p,(int)(v>>>32));memory.writeLong(p+4,(int)v);}
	private long get64(int p) throws M68kCpuException {return ((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);}
	interface L1 { long f(long a); }
	interface L2 { long f(long a,long b); }
	/** A {@code wide f(wide)}: hidden result pointer, then the argument's two slots. */
	private void w1(PpcLibraries l,String name,L1 f){l.register(null,name,(r,c)->{int p=arg(0);put64(p,f.f(s64(1)));ret(p);});}
	private void w2(PpcLibraries l,String name,L2 f){l.register(null,name,(r,c)->{int p=arg(0);put64(p,f.f(s64(1),s64(3)));ret(p);});}

	private void defineMath64(PpcLibraries l){
		for(String s:new String[]{"S","U"}){
			boolean u=s.equals("U");String t=s+"64";
			w2(l,t+"Add",(a,b)->a+b);w2(l,t+"Subtract",(a,b)->a-b);w2(l,t+"Multiply",(a,b)->a*b);
			w2(l,t+"BitwiseAnd",(a,b)->a&b);w2(l,t+"BitwiseOr",(a,b)->a|b);w2(l,t+"BitwiseEor",(a,b)->a^b);w1(l,t+"BitwiseNot",a->~a);
			// the shift count's low 7 bits are used: 64..127 shift everything out
			l.register(null,t+"ShiftLeft",(r,c)->{int p=arg(0);long v=s64(1);int n=arg(3)&0x7f;put64(p,n>=64?0:v<<n);ret(p);});
			l.register(null,t+"ShiftRight",(r,c)->{int p=arg(0);long v=s64(1);int n=arg(3)&0x7f;put64(p,u?(n>=64?0:v>>>n):(n>=64?(v<0?-1:0):v>>n));ret(p);});
			l.register(null,t+"And",(r,c)->retB(s64(0)!=0&&s64(2)!=0));l.register(null,t+"Or",(r,c)->retB(s64(0)!=0||s64(2)!=0));
			l.register(null,t+"Eor",(r,c)->retB((s64(0)!=0)!=(s64(2)!=0)));l.register(null,t+"Not",(r,c)->retB(s64(0)==0));
			l.register(null,t+"Compare",(r,c)->{long a=s64(0),b=s64(2);ret(u?Long.compareUnsigned(a,b):Long.compare(a,b));});
			// dividing by zero answers the largest value (the smallest for a negative signed dividend), the remainder being the dividend
			l.register(null,t+"Divide",(r,c)->{int p=arg(0);long a=s64(1),b=s64(3);int rem=arg(5);long q,m;
				if(b==0){q=u?-1L:a<0?Long.MIN_VALUE:Long.MAX_VALUE;m=a;}
				else if(u){q=Long.divideUnsigned(a,b);m=Long.remainderUnsigned(a,b);}
				else if(a==Long.MIN_VALUE&&b==-1){q=a;m=0;}else{q=a/b;m=a%b;} // Java's remainder takes the dividend's sign, as Math64's does
				if(rem!=0)put64(rem,m);put64(p,q);ret(p);});
			l.register(null,t+"Mod",(r,c)->{int p=arg(0);long a=s64(1),b=s64(3);put64(p,b==0?a:u?Long.remainderUnsigned(a,b):(b==-1?0:a%b));ret(p);});
			l.register(null,t+"Set",(r,c)->{int p=arg(0);put64(p,(long)arg(1));ret(p);});                  // from an SInt32: sign-extended
			l.register(null,t+"SetU",(r,c)->{int p=arg(0);put64(p,arg(1)&0xffffffffL);ret(p);});
			l.register(null,t+"Max",(r,c)->{int p=arg(0);put64(p,u?-1L:Long.MAX_VALUE);ret(p);});
		}
		l.register(null,"S64Min",(r,c)->{int p=arg(0);put64(p,Long.MIN_VALUE);ret(p);});
		w1(l,"S64Negate",a->-a);w1(l,"S64Absolute",a->a<0?-a:a); // S64Negate(S64Min) is S64Min
		l.register(null,"S32Set",(r,c)->ret(arg(1)));l.register(null,"U32SetU",(r,c)->ret(arg(1))); // the low 32 bits
		w1(l,"UInt64ToSInt64",a->a);w1(l,"SInt64ToUInt64",a->a);
		l.register(null,"SInt64ToLongDouble",(r,c)->retLD(c,new BigDecimal(s64(0))));
		l.register(null,"UInt64ToLongDouble",(r,c)->retLD(c,new BigDecimal(new BigInteger(Long.toUnsignedString(s64(0))))));
		// a long double argument after the hidden pointer: FPRs f1/f2, word slots 1-4
		l.register(null,"LongDoubleToSInt64",(r,c)->{int p=arg(0);put64(p,ldToLong(false));ret(p);});
		l.register(null,"LongDoubleToUInt64",(r,c)->{int p=arg(0);put64(p,ldToLong(true));ret(p);});
	}
	/** The long double in f1/f2 truncated to a 64-bit integer, saturating (NaN gives 0). */
	private long ldToLong(boolean unsigned){
		BigDecimal v=ldx(0);double h=rt.farg(0);
		if(v==null)return Double.isNaN(h)?0:unsigned?(h>0?-1L:0):(h>0?Long.MAX_VALUE:Long.MIN_VALUE);
		BigInteger i=v.setScale(0,RoundingMode.DOWN).toBigInteger();
		if(unsigned){if(i.signum()<0)return 0;if(i.bitLength()>64)return -1L;return i.longValue();}
		if(i.bitLength()>63)return i.signum()<0?Long.MIN_VALUE:Long.MAX_VALUE;
		return i.longValue();
	}

	// ================================================================== long double (double-double)

	private static long bits(double d){return Double.doubleToRawLongBits(d);}
	private static double dbl(long b){return Double.longBitsToDouble(b);}
	/** Long double argument {@code k} (counting long doubles only) as its head plus tail. */
	private double ld(int k){return rt.farg(2*k)+rt.farg(2*k+1);}
	/** Long double argument {@code k} exactly, or null when it is not finite. */
	private BigDecimal ldx(int k){double h=rt.farg(2*k),t=rt.farg(2*k+1);if(!Double.isFinite(h)||!Double.isFinite(t))return null;return new BigDecimal(h).add(new BigDecimal(t));}
	private static void retLD(PpcCpu c,double hi,double lo){c.f[1]=bits(hi);c.f[2]=bits(lo);}
	private static void retLD(PpcCpu c,double v){retLD(c,v,0.0);}
	/** An exact value as a normalized double-double (head = the value rounded, tail = the rest). */
	private static void retLD(PpcCpu c,BigDecimal v){double h=v.doubleValue();double t=Double.isFinite(h)?v.subtract(new BigDecimal(h)).doubleValue():0;if(h==0&&v.signum()==0)t=0;retLD(c,h,t);}
	private void writeLD(int p,double hi,double lo) throws M68kCpuException {put64(p,bits(hi));put64(p+8,bits(lo));}
	private final Map<String,PpcRuntime.Native> mathNatives=new HashMap<String,PpcRuntime.Native>();
	/** MathLib's double function {@code name} (PpcMathLib), which reads f1 (f2) and answers in f1. */
	private PpcRuntime.Native mathLib(String name){
		PpcRuntime.Native n=mathNatives.get(name);
		if(n==null){n=libraries.function("MathLib",name);if(n==null)throw new IllegalStateException("MathLib "+name+" missing");mathNatives.put(name,n);}
		return n;
	}
	interface BD1 { BigDecimal f(BigDecimal x); }
	/** A long double function computed exactly on finite values (non-finite values pass through). */
	private void exact1(PpcLibraries l,String name,BD1 f){
		l.register(null,name,(r,c)->{BigDecimal x=ldx(0);if(x==null){retLD(c,rt.farg(0));return;}BigDecimal v=f.f(x);if(v.signum()==0)retLD(c,Math.copySign(0.0,rt.farg(0)));else retLD(c,v);});
	}
	private static boolean negative(double h){return (bits(h)&0x8000000000000000L)!=0;}

	private void defineLongDouble(PpcLibraries l){
		// the transcendental functions: MathLib's double version on head + tail (53 bits of the 106)
		for(String base:new String[]{"acos","asin","atan","cos","sin","tan","cosh","sinh","tanh","acosh","asinh","atanh","exp","expm1","exp2",
				"log","log10","log1p","log2","logb","sqrt","erf","erfc","gamma","lgamma"})
			l.register(null,base+"l",(r,c)->{c.f[1]=bits(ld(0));mathLib(base).call(r,c);retLD(c,dbl(c.f[1]));});
		for(String base:new String[]{"atan2","pow","hypot"})
			l.register(null,base+"l",(r,c)->{double x=ld(0),y=ld(1);c.f[1]=bits(x);c.f[2]=bits(y);mathLib(base).call(r,c);retLD(c,dbl(c.f[1]));});
		// exact ones
		l.register(null,"fabsl",(r,c)->{double h=rt.farg(0),t=rt.farg(1);if(negative(h))retLD(c,-h,-t);else retLD(c,h,t);});
		l.register(null,"copysignl",(r,c)->{double h=rt.farg(0),t=rt.farg(1);if(negative(h)!=negative(rt.farg(2)))retLD(c,-h,-t);else retLD(c,h,t);});
		exact1(l,"floorl",x->x.setScale(0,RoundingMode.FLOOR));exact1(l,"ceill",x->x.setScale(0,RoundingMode.CEILING));
		exact1(l,"truncl",x->x.setScale(0,RoundingMode.DOWN));exact1(l,"roundl",x->x.setScale(0,RoundingMode.HALF_UP)); // halfway cases away from zero
		exact1(l,"rintl",x->x.setScale(0,RoundingMode.HALF_EVEN));exact1(l,"nearbyintl",x->x.setScale(0,RoundingMode.HALF_EVEN));
		l.register(null,"rinttoll",(r,c)->{BigDecimal x=ldx(0);ret(x==null?toLong(rt.farg(0)):toLong(x.setScale(0,RoundingMode.HALF_EVEN)));});
		l.register(null,"roundtoll",(r,c)->{BigDecimal x=ldx(0);ret(x==null?toLong(rt.farg(0)):toLong(x.setScale(0,RoundingMode.HALF_UP)));});
		l.register(null,"fmaxl",(r,c)->{int o=ldOrder();if(o==3)retLD(c,Double.isNaN(rt.farg(0))?rt.farg(2):rt.farg(0),Double.isNaN(rt.farg(0))?rt.farg(3):rt.farg(1));else if(o==1)retLD(c,rt.farg(2),rt.farg(3));else retLD(c,rt.farg(0),rt.farg(1));});
		l.register(null,"fminl",(r,c)->{int o=ldOrder();if(o==3)retLD(c,Double.isNaN(rt.farg(0))?rt.farg(2):rt.farg(0),Double.isNaN(rt.farg(0))?rt.farg(3):rt.farg(1));else if(o==0)retLD(c,rt.farg(2),rt.farg(3));else retLD(c,rt.farg(0),rt.farg(1));});
		l.register(null,"fdiml",(r,c)->{BigDecimal x=ldx(0),y=ldx(1);if(x==null||y==null){double v=ld(0),w=ld(1);retLD(c,Double.isNaN(v)||Double.isNaN(w)?Double.NaN:v>w?v-w:0);}else retLD(c,x.compareTo(y)>0?x.subtract(y):BigDecimal.ZERO);});
		l.register(null,"relationl",(r,c)->ret(ldOrder())); // GREATERTHAN 0, LESSTHAN 1, EQUALTO 2, UNORDERED 3
		// integer arguments after the long double's four slots
		l.register(null,"ldexpl",(r,c)->retLD(c,Math.scalb(rt.farg(0),arg(4)),Math.scalb(rt.farg(1),arg(4))));
		l.register(null,"scalbl",(r,c)->retLD(c,Math.scalb(rt.farg(0),arg(4)),Math.scalb(rt.farg(1),arg(4))));
		l.register(null,"frexpl",(r,c)->{double h=rt.farg(0),t=rt.farg(1);int e=0;
			if(h!=0&&Double.isFinite(h)){e=Math.getExponent(h)+1;if(Math.getExponent(h)<Double.MIN_EXPONENT)e=exponentOf(h);h=Math.scalb(h,-e);t=Math.scalb(t,-e);
				if(Math.abs(h)==0.5&&t!=0&&negative(t)!=negative(h)){h*=2;t*=2;e--;}}
			if(arg(4)!=0)memory.writeLong(arg(4),e);retLD(c,h,t);});
		l.register(null,"modfl",(r,c)->{double h=rt.farg(0);BigDecimal x=ldx(0);int p=arg(4);
			if(x==null){if(p!=0)writeLD(p,h,0);retLD(c,Double.isNaN(h)?h:Math.copySign(0.0,h));return;}
			BigDecimal i=x.setScale(0,RoundingMode.DOWN);BigDecimal f=x.subtract(i);
			if(p!=0){double ih=i.doubleValue();writeLD(p,ih==0?Math.copySign(0.0,h):ih,i.subtract(new BigDecimal(ih)).doubleValue());}
			if(f.signum()==0)retLD(c,Math.copySign(0.0,h));else retLD(c,f);});
		l.register(null,"modff",(r,c)->{float x=(float)rt.farg(0);float i=Float.isInfinite(x)||Float.isNaN(x)?x:(x<0?(float)Math.ceil(x):(float)Math.floor(x));int p=arg(1);
			if(p!=0)memory.writeLong(p,Float.floatToRawIntBits(i));rt.retD(Float.isInfinite(x)?Math.copySign(0.0,x):(double)(x-i));});
		l.register(null,"nanl",(r,c)->retLD(c,Double.NaN,0));
		l.register(null,"nextafterl",(r,c)->{BigDecimal x=ldx(0),y=ldx(1);
			if(x==null||y==null){c.f[1]=bits(ld(0));c.f[2]=bits(ld(1));mathLib("nextafterd").call(r,c);retLD(c,dbl(c.f[1]));return;}
			int cmp=x.compareTo(y);if(cmp==0){retLD(c,rt.farg(2),rt.farg(3));return;}
			double h=rt.farg(0);double step=h==0?Double.MIN_VALUE:Math.max(Double.MIN_VALUE,Math.scalb(Math.ulp(h),-53)); // one unit in the 106th bit
			retLD(c,cmp<0?x.add(new BigDecimal(step)):x.subtract(new BigDecimal(step)));});
		l.register(null,"remainderl",(r,c)->{BigDecimal x=ldx(0),y=ldx(1);if(x==null||y==null||y.signum()==0){c.f[1]=bits(ld(0));c.f[2]=bits(ld(1));mathLib("remainder").call(r,c);retLD(c,dbl(c.f[1]));return;}
			retLD(c,x.subtract(x.divide(y,0,RoundingMode.HALF_EVEN).multiply(y)));});
		// remquol(x, y, int *quo): x in slots 0-3, y 4-7, quo in slot 8 (the parameter area)
		l.register(null,"remquol",(r,c)->{BigDecimal x=ldx(0),y=ldx(1);int p=arg(8);
			if(x==null||y==null||y.signum()==0){if(p!=0)memory.writeLong(p,0);retLD(c,Double.NaN);return;}
			BigInteger q=x.divide(y,0,RoundingMode.HALF_EVEN).toBigInteger();
			if(p!=0){int low=q.abs().and(BigInteger.valueOf(0x7f)).intValue();memory.writeLong(p,q.signum()<0?-low:low);}
			retLD(c,x.subtract(new BigDecimal(q).multiply(y)));});
		l.register(null,"__isnan",(r,c)->retB(Double.isNaN(rt.farg(0))));
		l.register(null,"__isnormal",(r,c)->{double h=rt.farg(0);retB(Double.isFinite(h)&&Math.abs(h)>=Double.MIN_NORMAL);});
		l.register(null,"__isnormalf",(r,c)->{float x=(float)rt.farg(0);retB(Float.isFinite(x)&&Math.abs(x)>=Float.MIN_NORMAL);});
		l.register(null,"__signbit",(r,c)->retB(negative(rt.farg(0))));
		// SANE's financial functions (double_t is double on PowerPC)
		l.register(null,"compound",(r,c)->{double rate=rt.farg(0),n=rt.farg(1);rt.retD(rate<-1?Double.NaN:Math.pow(1+rate,n));});
		l.register(null,"annuity",(r,c)->{double rate=rt.farg(0),n=rt.farg(1);
			rt.retD(rate<-1||Double.isNaN(rate)||Double.isNaN(n)?Double.NaN:rate==0?n:(1-Math.pow(1+rate,-n))/rate);}); // present value of 1 per period
		// num2decl(const decform *f, long double x, decimal *d): x takes slots 1-4
		l.register(null,"num2decl",(r,c)->PpcMathLib.num2dec(r,arg(0),ld(0),arg(5)));
		l.register(null,"dec2numl",(r,c)->retLD(c,PpcMathLib.dec2num(r,arg(0))));
		// x80told(const extended80 *, long double *) stores its result (PpcMathLib's answered it in f1): replaces that registration
		PpcRuntime.Native x80told=(r,c)->{int p=arg(1);if(p!=0)writeLD(p,PpcMathLib.x80ToDouble(r,arg(0)),0);};
		l.register(null,"x80told",x80told);l.register("MathLib","x80told",x80told);
		// fenv.h: fexcept_t is a long holding FPSCR exception bits (FE_ALL_EXCEPT $3E000000)
		l.register(null,"fegetexcept",(r,c)->{int p=arg(0);if(p!=0)memory.writeLong(p,c.fpscr&arg(1)&0x3e000000);});
		l.register(null,"fesetexcept",(r,c)->{int p=arg(0),e=arg(1)&0x3e000000;if(p==0)return;c.fpscr=(c.fpscr&~e)|(memory.readLong(p)&e);if((c.fpscr&0x3e000000)!=0)c.fpscr|=0x80000000;});
	}
	/** relop of long double arguments 0 and 1: GREATERTHAN 0, LESSTHAN 1, EQUALTO 2, UNORDERED 3. */
	private int ldOrder(){
		double xh=rt.farg(0),yh=rt.farg(2);
		if(Double.isNaN(xh)||Double.isNaN(yh))return 3;
		BigDecimal x=ldx(0),y=ldx(1);int cmp=x!=null&&y!=null?x.compareTo(y):Double.compare(xh==0?0.0:xh,yh==0?0.0:yh);
		return cmp>0?0:cmp<0?1:2;
	}
	private static int exponentOf(double h){int e=0;double m=Math.abs(h);while(m<0.5){m*=2;e--;}while(m>=1){m/=2;e++;}return e;}
	private static int toLong(double v){if(Double.isNaN(v))return Integer.MIN_VALUE;if(v>=2147483647.0)return Integer.MAX_VALUE;if(v<=-2147483648.0)return Integer.MIN_VALUE;return (int)Math.rint(v);}
	private static int toLong(BigDecimal v){if(v.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>=0)return Integer.MAX_VALUE;if(v.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE))<=0)return Integer.MIN_VALUE;return v.intValue();}

	// ================================================================== DriverSynchronization

	interface IntOp { int f(int old,int operand); }
	/** An atomic on a guest byte/word/long: {@code (operand, address)} or {@code (address)}; answers the value before. */
	private void atomic(PpcLibraries l,String name,int size,boolean signed,boolean hasOperand,IntOp op){
		l.register(null,name,(r,c)->{
			int operand=hasOperand?arg(0):0,p=hasOperand?arg(1):arg(0);
			int old=size==1?memory.readByte(p):size==2?memory.readWord(p):memory.readLong(p);
			int v=op.f(old,operand);
			if(size==1)memory.writeByte(p,v);else if(size==2)memory.writeWord(p,v);else memory.writeLong(p,v);
			ret(size==1?(signed?(byte)old:old&0xff):size==2?(signed?(short)old:old&0xffff):old);
		});
	}
	private void defineAtomics(PpcLibraries l){
		String[] suffix={"8","16",""};int[] sizes={1,2,4};
		for(int i=0;i<3;i++){
			String s=suffix[i];int n=sizes[i];
			atomic(l,"AddAtomic"+s,n,true,true,(o,a)->o+a);
			atomic(l,"IncrementAtomic"+s,n,true,false,(o,a)->o+1);atomic(l,"DecrementAtomic"+s,n,true,false,(o,a)->o-1);
			atomic(l,"BitAndAtomic"+s,n,false,true,(o,a)->o&a);atomic(l,"BitOrAtomic"+s,n,false,true,(o,a)->o|a);atomic(l,"BitXorAtomic"+s,n,false,true,(o,a)->o^a);
		}
		l.register(null,"CompareAndSwap",(r,c)->{int p=arg(2);if(memory.readLong(p)==arg(0)){memory.writeLong(p,arg(1));retB(true);}else retB(false);});
		// TestAndSet/TestAndClear(bit, startAddress): bit 0 is the high bit of the first byte; answers the bit's old state
		l.register(null,"TestAndSet",(r,c)->{int bit=arg(0),p=arg(1)+(bit>>>3),m=0x80>>>(bit&7);int b=memory.readByte(p);memory.writeByte(p,b|m);retB((b&m)!=0);});
		l.register(null,"TestAndClear",(r,c)->{int bit=arg(0),p=arg(1)+(bit>>>3),m=0x80>>>(bit&7);int b=memory.readByte(p);memory.writeByte(p,b&~m);retB((b&m)!=0);});
	}

	// ================================================================== strings

	private byte[] pBytes(int p) throws M68kCpuException {return p==0?new byte[0]:rt.pBytes(p);}
	/** Index of {@code needle} in {@code hay} from {@code from}, or -1. */
	private static int indexOf(byte[] hay,byte[] needle){
		outer:for(int i=0;i+needle.length<=hay.length;i++){for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;return i;}
		return -1;
	}
	private static boolean contains(byte[] set,byte b){for(byte x:set)if(x==b)return true;return false;}
	private void defineStrings(PpcLibraries l){
		// PLStringFuncs: Pascal strings; pointers answered point into str1's characters
		l.register(null,"PLstrncmp",(r,c)->{byte[] a=pBytes(arg(0)),b=pBytes(arg(1));int n=Math.max(0,(short)arg(2));
			int la=Math.min(a.length,n),lb=Math.min(b.length,n),res=0;for(int i=0;i<Math.min(la,lb)&&res==0;i++)res=(a[i]&0xff)-(b[i]&0xff);
			if(res==0)res=la-lb;ret(Integer.signum(res));});
		l.register(null,"PLstrncat",(r,c)->{int d=arg(0),s=arg(1);int n=Math.max(0,(short)arg(2));int a=memory.readByte(d),b=Math.min(memory.readByte(s),n);
			int k=Math.min(255-a,b);memory.copy(s+1,d+1+a,k);memory.writeByte(d,a+k);ret(d);});
		l.register(null,"PLstrchr",(r,c)->{int s=arg(0);byte[] a=pBytes(s);byte ch=(byte)arg(1);int at=-1;for(int i=0;i<a.length;i++)if(a[i]==ch){at=i;break;}ret(at<0?0:s+1+at);});
		l.register(null,"PLstrrchr",(r,c)->{int s=arg(0);byte[] a=pBytes(s);byte ch=(byte)arg(1);int at=-1;for(int i=a.length-1;i>=0;i--)if(a[i]==ch){at=i;break;}ret(at<0?0:s+1+at);});
		l.register(null,"PLstrpbrk",(r,c)->{int s=arg(0);byte[] a=pBytes(s),set=pBytes(arg(1));int at=-1;for(int i=0;i<a.length;i++)if(contains(set,a[i])){at=i;break;}ret(at<0?0:s+1+at);});
		l.register(null,"PLstrspn",(r,c)->{byte[] a=pBytes(arg(0)),set=pBytes(arg(1));int n=0;while(n<a.length&&contains(set,a[n]))n++;ret(n);});
		l.register(null,"PLstrstr",(r,c)->{int s=arg(0);int at=indexOf(pBytes(s),pBytes(arg(1)));ret(at<0?0:s+1+at);});
		l.register(null,"PLpos",(r,c)->{int at=indexOf(pBytes(arg(0)),pBytes(arg(1)));ret(at<0?0:at+1);}); // 1-based, 0 = not found
		// TextUtils: copies between C and Pascal strings (the source may be the destination)
		l.register(null,"c2pstrcpy",(r,c)->{int d=arg(0);byte[] b=rt.cBytes(arg(1));int n=Math.min(255,b.length);memory.load(d+1,b,0,n);memory.writeByte(d,n);});
		l.register(null,"p2cstrcpy",(r,c)->{int d=arg(0);byte[] b=pBytes(arg(1));memory.load(d,b);memory.writeByte(d+b.length,0);});
		// StringCompare.h: IdenticalString considers case and diacriticals (0 = identical, 1 = different)
		l.register(null,"IdenticalString",(r,c)->{String a=rt.toolbox.encoding.decode(pBytes(arg(0))),b=rt.toolbox.encoding.decode(pBytes(arg(1)));
			ret(Normalizer.normalize(a,Normalizer.Form.NFC).equals(Normalizer.normalize(b,Normalizer.Form.NFC))?0:1);});
		// StringOrder(str1, str2, script1, script2, lang1, lang2) = TextOrder on the strings' characters
		l.register(null,"StringOrder",(r,c)->{int a=arg(0),b=arg(1);
			ret((short)rt.toolbox("TextOrder",a+1,b+1,memory.readByte(a),memory.readByte(b),(short)arg(2),(short)arg(3),(short)arg(4),(short)arg(5)));});
	}

	// ================================================================== Unicode utilities

	static final int COMPOSE_INSENSITIVE=1<<1,WIDTH_INSENSITIVE=1<<2,CASE_INSENSITIVE=1<<3,DIACRITIC_INSENSITIVE=1<<4,PUNCTUATION_SIGNIFICANT=1<<15,DIGITS_AS_NUMBER=1<<17;
	static final int COLLATE_INVALID_REF=-29507,COLLATE_BUFFER_TOO_SMALL=-29506,UC_OUTPUT_BUFFER_TOO_SMALL=-25340,UC_BREAK_MISSING_TYPE=-25341;
	/** A collator: its locale and UCCollateOptions. */
	private static final class CollatorSpec { final Locale locale;final int options;CollatorSpec(Locale locale,int options){this.locale=locale;this.options=options;} }
	private final Map<Integer,CollatorSpec> collators=new HashMap<Integer,CollatorSpec>();
	/** A text break locator: its locale and the break types it was made for. */
	private static final class BreakSpec { final Locale locale;final int types;BreakSpec(Locale locale,int types){this.locale=locale;this.types=types;} }
	private final Map<Integer,BreakSpec> breakLocators=new HashMap<Integer,BreakSpec>();
	private int keyState;

	private String uniText(int p,int n) throws M68kCpuException {
		StringBuilder sb=new StringBuilder(Math.max(0,n));for(int i=0;i<n;i++)sb.append((char)memory.readWord(p+2*i));return sb.toString();
	}
	/** Writes {@code s} as UniChars (at most {@code max}); stores the full length at {@code actual}; kLocalesBufferTooSmallErr-style result. */
	private boolean writeUni(int p,String s,int max,int actual) throws M68kCpuException {
		int n=Math.min(s.length(),Math.max(0,max));if(p!=0)for(int i=0;i<n;i++)memory.writeWord(p+2*i,s.charAt(i));
		if(actual!=0)memory.writeLong(actual,s.length());return s.length()<=Math.max(0,max);
	}
	/** The text as the options have the comparison see it. */
	private static String collationText(String s,int options){
		if((options&WIDTH_INSENSITIVE)!=0){StringBuilder sb=new StringBuilder();
			for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(ch>=0xff00&&ch<=0xffef||ch==0x3000)sb.append(Normalizer.normalize(String.valueOf(ch),Normalizer.Form.NFKC));else sb.append(ch);}
			s=sb.toString();}
		s=Normalizer.normalize(s,Normalizer.Form.NFD);
		if((options&DIACRITIC_INSENSITIVE)!=0)s=s.replaceAll("\\p{Mn}+","");
		if((options&CASE_INSENSITIVE)!=0)s=s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
		if((options&PUNCTUATION_SIGNIFICANT)==0)s=s.replaceAll("\\p{P}+","");
		return s;
	}
	private static Collator collator(Locale locale){Collator c=Collator.getInstance(locale);c.setStrength(Collator.TERTIARY);c.setDecomposition(Collator.CANONICAL_DECOMPOSITION);return c;}
	/** The order (-1, 0, 1) of two texts under a locale's collation and UCCollateOptions. */
	static int collate(Locale locale,int options,String a,String b){
		int type=(options>>>24)&0xff;
		if(type==1){ // kUCCollateTypeHFSExtended: HFS Plus names - case folded UTF-16 units, ignorable characters dropped
			String x=hfsFold(a),y=hfsFold(b);return Integer.signum(x.compareTo(y));}
		String x=collationText(a,options),y=collationText(b,options);
		Collator c=collator(locale);
		if((options&DIGITS_AS_NUMBER)==0)return Integer.signum(c.compare(x,y));
		// digit runs compare as numbers
		int i=0,j=0;
		while(i<x.length()&&j<y.length()){
			boolean dx=Character.isDigit(x.charAt(i)),dy=Character.isDigit(y.charAt(j));
			int ei=i,ej=j;
			while(ei<x.length()&&Character.isDigit(x.charAt(ei))==dx)ei++;
			while(ej<y.length()&&Character.isDigit(y.charAt(ej))==dy)ej++;
			String sx=x.substring(i,ei),sy=y.substring(j,ej);
			int o;
			if(dx&&dy)o=new BigInteger(digits(sx)).compareTo(new BigInteger(digits(sy)));
			else o=c.compare(sx,sy);
			if(o!=0)return Integer.signum(o);
			i=ei;j=ej;
		}
		return Integer.signum((x.length()-i)-(y.length()-j));
	}
	private static String digits(String s){StringBuilder sb=new StringBuilder();for(int i=0;i<s.length();i++)sb.append(Character.digit(s.charAt(i),10));return sb.toString();}
	private static String hfsFold(String s){StringBuilder sb=new StringBuilder();for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(Character.getType(ch)==Character.FORMAT||ch==0)continue;sb.append(Character.toLowerCase(ch));}return sb.toString();}
	private Locale systemLocale(){return rt.toolbox.encoding==StackTextEncoding.JAPANESE?Locale.JAPAN:Locale.US;}
	/** The Java locale of a LocaleRef (0 = the system's). */
	private Locale javaLocale(int ref){Integer i=localeRefs.get(ref);if(i==null)return systemLocale();String[] p=((String)LOCALES[i][2]).split("_");return new Locale(p[0],p[1]);}
	/** UCCompareText's shared part: writes *equivalent and *order. */
	private int compareText(Locale locale,int options,int base) throws M68kCpuException {
		String a=uniText(arg(base),arg(base+1)),b=uniText(arg(base+2),arg(base+3));
		int order=collate(locale,options,a,b);
		if(arg(base+4)!=0)memory.writeByte(arg(base+4),order==0?1:0);
		if(arg(base+5)!=0)memory.writeLong(arg(base+5),order);
		return NO_ERR;
	}

	private void defineUnicode(PpcLibraries l){
		// collation: a CollatorRef is a small guest block standing for the Java collator settings
		l.register(null,"UCCreateCollator",(r,c)->{int out=arg(3);if(out==0){ret(PARAM_ERR);return;}
			int ref=rt.allocate(8,4);collators.put(ref,new CollatorSpec(javaLocale(arg(0)),arg(2)));memory.writeLong(out,ref);ret(NO_ERR);});
		l.register(null,"UCDisposeCollator",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}int ref=memory.readLong(p);
			if(collators.remove(ref)==null){ret(COLLATE_INVALID_REF);return;}memory.free(ref);memory.writeLong(p,0);ret(NO_ERR);});
		l.register(null,"UCCompareText",(r,c)->{CollatorSpec s=collators.get(arg(0));if(s==null){ret(COLLATE_INVALID_REF);return;}ret(compareText(s.locale,s.options,1));});
		l.register(null,"UCCompareTextDefault",(r,c)->ret(compareText(systemLocale(),arg(0),1)));
		l.register(null,"UCCompareTextNoLocale",(r,c)->ret(compareText(Locale.ROOT,arg(0),1)));
		// collation keys: the Java collation key's bytes packed big-endian into UCCollationValues
		l.register(null,"UCGetCollationKey",(r,c)->{CollatorSpec s=collators.get(arg(0));if(s==null){ret(COLLATE_INVALID_REF);return;}
			CollationKey k=collator(s.locale).getCollationKey(collationText(uniText(arg(1),arg(2)),s.options));byte[] b=k.toByteArray();
			int n=(b.length+3)/4,max=arg(3),out=arg(5);if(arg(4)!=0)memory.writeLong(arg(4),n);
			if(n>max){ret(COLLATE_BUFFER_TOO_SMALL);return;}
			for(int i=0;i<n;i++){int v=0;for(int j=0;j<4;j++){int at=4*i+j;v=v<<8|(at<b.length?b[at]&0xff:0);}memory.writeLong(out+4*i,v);}
			ret(NO_ERR);});
		l.register(null,"UCCompareCollationKeys",(r,c)->{int a=arg(0),na=arg(1),b=arg(2),nb=arg(3);int order=0;
			for(int i=0;i<Math.min(na,nb)&&order==0;i++)order=Integer.compareUnsigned(memory.readLong(a+4*i),memory.readLong(b+4*i));
			if(order==0)order=Integer.compare(na,nb);order=Integer.signum(order);
			if(arg(4)!=0)memory.writeByte(arg(4),order==0?1:0);if(arg(5)!=0)memory.writeLong(arg(5),order);ret(NO_ERR);});
		// text breaks (java.text.BreakIterator): char and cluster = grapheme clusters, word, line
		l.register(null,"UCCreateTextBreakLocator",(r,c)->{int out=arg(3),types=arg(2)&0x55;if(out==0||types==0){ret(PARAM_ERR);return;}
			int ref=rt.allocate(8,4);breakLocators.put(ref,new BreakSpec(javaLocale(arg(0)),types));memory.writeLong(out,ref);ret(NO_ERR);});
		l.register(null,"UCDisposeTextBreakLocator",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}int ref=memory.readLong(p);
			if(breakLocators.remove(ref)==null){ret(PARAM_ERR);return;}memory.free(ref);memory.writeLong(p,0);ret(NO_ERR);});
		l.register(null,"UCFindTextBreak",(r,c)->ret(findTextBreak()));
		l.register(null,"UCGetCharProperty",(r,c)->{int p=arg(0),n=arg(1),type=arg(2),out=arg(3);if(p==0||n<=0||out==0){ret(PARAM_ERR);return;}
			int ch=memory.readWord(p);if(Character.isHighSurrogate((char)ch)&&n>1){int lo=memory.readWord(p+2);if(Character.isLowSurrogate((char)lo))ch=Character.toCodePoint((char)ch,(char)lo);}
			int v;switch(type){case 1:v=generalCategory(ch);break;case 2:v=combiningClass(ch);break;case 3:v=bidiCategory(ch);break;default:ret(PARAM_ERR);return;}
			memory.writeLong(out,v);ret(NO_ERR);});
		l.register(null,"UCKeyTranslate",(r,c)->ret(keyTranslate()));
	}

	/** UCFindTextBreak(breakRef, breakType, options, textPtr, textLength, startOffset, *breakOffset). */
	private int findTextBreak() throws M68kException {
		BreakSpec s=breakLocators.get(arg(0));if(s==null)return PARAM_ERR;
		int type=arg(1),options=arg(2),text=arg(3),len=arg(4),start=arg(5),out=arg(6);
		if(out==0||len<0||start<0||start>len||(text==0&&len>0))return PARAM_ERR;
		int use=type&s.types;if(use==0)return UC_BREAK_MISSING_TYPE;
		BreakIterator bi=(use&0x05)!=0?BreakIterator.getCharacterInstance(s.locale):(use&0x10)!=0?BreakIterator.getWordInstance(s.locale):BreakIterator.getLineInstance(s.locale);
		bi.setText(uniText(text,len));
		int at;
		if((options&2)!=0){at=start<=0?0:bi.preceding(start);if(at==BreakIterator.DONE)at=0;} // kUCTextBreakGoBackwardsMask
		else{at=start>=len?len:bi.following(start);if(at==BreakIterator.DONE)at=len;}
		memory.writeLong(out,at);return NO_ERR;
	}
	/** TextCommon's general category values. */
	static int generalCategory(int ch){
		switch(Character.getType(ch)){
			case Character.CONTROL:return 1;case Character.FORMAT:return 2;case Character.SURROGATE:return 3;case Character.PRIVATE_USE:return 4;
			case Character.NON_SPACING_MARK:return 5;case Character.COMBINING_SPACING_MARK:return 6;case Character.ENCLOSING_MARK:return 7;
			case Character.DECIMAL_DIGIT_NUMBER:return 8;case Character.LETTER_NUMBER:return 9;case Character.OTHER_NUMBER:return 10;
			case Character.SPACE_SEPARATOR:return 11;case Character.LINE_SEPARATOR:return 12;case Character.PARAGRAPH_SEPARATOR:return 13;
			case Character.UPPERCASE_LETTER:return 14;case Character.LOWERCASE_LETTER:return 15;case Character.TITLECASE_LETTER:return 16;
			case Character.MODIFIER_LETTER:return 17;case Character.OTHER_LETTER:return 18;
			case Character.CONNECTOR_PUNCTUATION:return 20;case Character.DASH_PUNCTUATION:return 21;case Character.START_PUNCTUATION:return 22;
			case Character.END_PUNCTUATION:return 23;case Character.INITIAL_QUOTE_PUNCTUATION:return 24;case Character.FINAL_QUOTE_PUNCTUATION:return 25;
			case Character.OTHER_PUNCTUATION:return 26;case Character.MATH_SYMBOL:return 28;case Character.CURRENCY_SYMBOL:return 29;
			case Character.MODIFIER_SYMBOL:return 30;case Character.OTHER_SYMBOL:return 31;
			default:return 0; // not assigned
		}
	}
	/** TextCommon's bidirectional category values (no AL or NSM classes then: Arabic letters are R, marks ON). */
	static int bidiCategory(int ch){
		switch(Character.getDirectionality(ch)){
			case Character.DIRECTIONALITY_LEFT_TO_RIGHT:return 1;
			case Character.DIRECTIONALITY_RIGHT_TO_LEFT:case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:return 2;
			case Character.DIRECTIONALITY_EUROPEAN_NUMBER:return 3;case Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR:return 4;
			case Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR:return 5;case Character.DIRECTIONALITY_ARABIC_NUMBER:return 6;
			case Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR:return 7;case Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR:return 8;
			case Character.DIRECTIONALITY_SEGMENT_SEPARATOR:return 9;case Character.DIRECTIONALITY_WHITESPACE:return 10;
			case Character.DIRECTIONALITY_UNDEFINED:return 0;
			default:return 11;
		}
	}
	/** Canonical combining classes (Unicode data) of the combining marks in common use; 0 for everything else. */
	static int combiningClass(int ch){
		if(Character.getType(ch)!=Character.NON_SPACING_MARK&&Character.getType(ch)!=Character.ENCLOSING_MARK&&ch!=0x0345)return 0;
		if(ch>=0x0300&&ch<=0x036f){
			if(ch<=0x0314)return 230;if(ch==0x0315||ch==0x031a||ch==0x0358)return 232;if(ch==0x031b)return 216;
			if(ch>=0x0316&&ch<=0x0319||ch>=0x031c&&ch<=0x0320||ch>=0x0323&&ch<=0x0326||ch>=0x0329&&ch<=0x0333||ch>=0x0339&&ch<=0x033c
				||ch>=0x0347&&ch<=0x0349||ch==0x034d||ch==0x034e||ch>=0x0353&&ch<=0x0356||ch==0x0359||ch==0x035a)return 220;
			if(ch==0x0321||ch==0x0322||ch==0x0327||ch==0x0328)return 202;if(ch>=0x0334&&ch<=0x0338)return 1;if(ch==0x0345)return 240;
			if(ch==0x035c||ch==0x035f||ch==0x0362)return 233;if(ch==0x035d||ch==0x035e||ch==0x0360||ch==0x0361)return 234;if(ch==0x034f)return 0;
			return 230;
		}
		if(ch>=0x05b0&&ch<=0x05bd)return ch==0x05ba?19:10+(ch-0x05b0);
		switch(ch){
			case 0x05bf:return 23;case 0x05c1:return 24;case 0x05c2:return 25;case 0x05c7:return 18;
			case 0x064b:return 27;case 0x064c:return 28;case 0x064d:return 29;case 0x064e:return 30;case 0x064f:return 31;case 0x0650:return 32;case 0x0651:return 33;case 0x0652:return 34;case 0x0670:return 35;
			case 0x093c:case 0x09bc:case 0x0a3c:case 0x0abc:case 0x0b3c:case 0x0cbc:return 7; // nukta
			case 0x094d:case 0x09cd:case 0x0a4d:case 0x0acd:case 0x0b4d:case 0x0bcd:case 0x0c4d:case 0x0ccd:case 0x0d4d:case 0x0dca:case 0x0e3a:case 0x1039:case 0x17d2:return 9; // virama
			case 0x0e38:case 0x0e39:return 103;case 0x0e48:case 0x0e49:case 0x0e4a:case 0x0e4b:return 107;
			case 0x0eb8:case 0x0eb9:return 118;case 0x0ec8:case 0x0ec9:case 0x0eca:case 0x0ecb:return 122;
			case 0x3099:case 0x309a:return 8; // kana voiced sound marks
			case 0x20d2:case 0x20d3:case 0x20d8:case 0x20d9:case 0x20da:case 0x20e5:case 0x20e6:case 0x20ea:case 0x20eb:return 1;
			case 0x20e8:case 0x20ec:case 0x20ed:case 0x20ee:case 0x20ef:return 220;
			default:break;
		}
		if(ch>=0x0591&&ch<=0x05af)return ch==0x0591||ch==0x0596||ch==0x059b||ch>=0x05a2&&ch<=0x05a7||ch==0x05aa?220:ch==0x059a||ch==0x05ad?222:ch==0x05ae?228:230;
		if(ch==0x0653||ch==0x0654)return 230;if(ch==0x0655||ch==0x0656)return 220;
		if(ch>=0x20d0&&ch<=0x20f0||ch>=0x1dc0&&ch<=0x1dff||ch>=0xfe20&&ch<=0xfe2f)return 230;
		return 0;
	}

	/**
	 * UCKeyTranslate(keyLayoutPtr, virtualKeyCode, keyAction, modifierKeyState, keyboardType, options, *deadKeyState,
	 * maxStringLength, *actualStringLength, unicodeString[]): through the 'uchr' the program passes, else through a KCHR
	 * (the pointer, when it is one, or the System's) with the Toolbox's KeyTranslate, its characters converted to Unicode.
	 * modifierKeyState is the EventRecord modifiers shifted right 8 bits.
	 */
	private int keyTranslate() throws M68kException {
		int layout=arg(0),keyCode=arg(1)&0xffff,action=arg(2)&0xffff,mods=arg(3)&0xff,kbdType=arg(4),options=arg(5),statePtr=arg(6),max=arg(7),actualPtr=arg(8),out=arg(9);
		if(statePtr==0||actualPtr==0||(max>0&&out==0)||action>3)return PARAM_ERR;
		StringBuilder sb=new StringBuilder();
		if(action!=1){ // kUCKeyActionUp produces nothing
			boolean display=action==3,noDead=display||(options&1)!=0;
			if(layout!=0&&memory.readWord(layout)==0x1002)uchrTranslate(layout,keyCode,mods,kbdType,noDead,display,statePtr,sb);
			else kchrTranslate(layout,keyCode,mods,noDead,display,statePtr,sb);
		}
		int n=sb.length();
		for(int i=0;i<Math.min(n,max);i++)memory.writeWord(out+2*i,sb.charAt(i));
		memory.writeLong(actualPtr,Math.min(n,max));
		return n>max?UC_OUTPUT_BUFFER_TOO_SMALL:NO_ERR;
	}
	private void kchrTranslate(int kchr,int keyCode,int mods,boolean noDead,boolean display,int statePtr,StringBuilder sb) throws M68kException {
		if(keyState==0)keyState=rt.allocate(4,4);
		int state=statePtr;
		if(noDead){memory.writeLong(keyState,display?0:memory.readLong(statePtr));state=keyState;}
		int result=rt.toolbox("KeyTranslate",kchr,(mods<<8)|(keyCode&0x7f),state);
		if(noDead&&memory.readLong(keyState)!=0)result=rt.toolbox("KeyTranslate",kchr,49,keyState); // a dead key: the space after it gives its accent
		if(noDead&&!display)memory.writeLong(statePtr,0);
		int accent=(result>>>16)&0xff,ch=result&0xff;
		if(accent!=0)sb.append(rt.toolbox.encoding.decode(new byte[]{(byte)accent}));
		if(ch!=0)sb.append(rt.toolbox.encoding.decode(new byte[]{(byte)ch}));
	}
	/** The 'uchr' tables (UnicodeUtilities.h): keyboard type entry, modifiers -> table, key -> output, dead-key states. */
	private void uchrTranslate(int layout,int keyCode,int mods,int kbdType,boolean noDead,boolean display,int statePtr,StringBuilder sb) throws M68kCpuException {
		int count=memory.readLong(layout+8),entry=layout+12;
		for(int i=0;i<count;i++){int e=layout+12+28*i;int first=memory.readLong(e),last=memory.readLong(e+4);if(Integer.compareUnsigned(kbdType,first)>=0&&Integer.compareUnsigned(kbdType,last)<=0){entry=e;break;}}
		int modOff=memory.readLong(entry+8),charOff=memory.readLong(entry+12),stateOff=memory.readLong(entry+16),termOff=memory.readLong(entry+20),seqOff=memory.readLong(entry+24);
		int m=layout+modOff;int modCount=memory.readLong(m+4);int table=mods<modCount?memory.readByte(m+8+mods):memory.readWord(m+2);
		int k=layout+charOff;int tableSize=memory.readWord(k+2),tableCount=memory.readLong(k+4);if(table>=tableCount)table=0;
		int state=display?0:memory.readLong(statePtr);
		if(keyCode>=tableSize)return;
		int output=memory.readWord(layout+memory.readLong(k+8+4*table)+2*keyCode);
		int next=0;
		if((output&0xc000)==0x4000&&stateOff!=0&&(output&0x3fff)<memory.readWord(layout+stateOff+2)){
			int rec=layout+memory.readLong(layout+stateOff+4+4*(output&0x3fff));
			int zeroChar=memory.readWord(rec),zeroNext=memory.readWord(rec+2),entries=memory.readWord(rec+4),format=memory.readWord(rec+6);
			int charData=-1;boolean matched=false;
			if(state!=0)for(int i=0;i<entries&&!matched;i++){
				if(format==1){int e=rec+8+4*i;if(memory.readWord(e)==state){charData=memory.readWord(e+2);next=0;matched=true;}}
				else if(format==2){int e=rec+8+8*i;int start=memory.readWord(e),range=memory.readByte(e+2),mult=memory.readByte(e+3),cd=memory.readWord(e+4),ns=memory.readWord(e+6);
					if(state>=start&&state<=start+range){int delta=(state-start)*mult;charData=cd<0xfffe?cd+delta:cd;next=ns!=0?ns+delta:0;matched=true;}}
			}
			if(!matched){if(state!=0)terminator(layout,termOff,seqOff,state,sb);charData=zeroChar;next=zeroNext;}
			if(noDead&&next!=0){terminator(layout,termOff,seqOff,next,sb);next=0;} // no new dead-key state: the state's own character instead
			charSequence(layout,seqOff,charData,sb);
		}else{
			if(state!=0)terminator(layout,termOff,seqOff,state,sb);
			charSequence(layout,seqOff,output,sb);
		}
		if(!display)memory.writeLong(statePtr,next);
	}
	private void terminator(int layout,int termOff,int seqOff,int state,StringBuilder sb) throws M68kCpuException {
		if(termOff==0)return;int t=layout+termOff;if(state-1<memory.readWord(t+2))charSequence(layout,seqOff,memory.readWord(t+4+2*(state-1)),sb);
	}
	/** A UCKeyCharSeq: a character, a sequence from the sequence table, or nothing ($FFFE/$FFFF). */
	private void charSequence(int layout,int seqOff,int v,StringBuilder sb) throws M68kCpuException {
		if(v<0||v>=0xfffe)return;
		if((v&0xc000)==0x8000&&seqOff!=0){int s=layout+seqOff;int idx=v&0x3fff;
			if(idx<memory.readWord(s+2)){int from=memory.readWord(s+4+2*idx),to=memory.readWord(s+4+2*(idx+1));for(int p=from;p<to;p+=2)sb.append((char)memory.readWord(s+p));return;}}
		sb.append((char)v);
	}

	// ================================================================== MacLocales

	static final int LOCALES_BUFFER_TOO_SMALL=-30001;
	/** Locales known: Mac language code, region code, ISO name. */
	private static final Object[][] LOCALES={
		{0,0,"en_US"},{1,1,"fr_FR"},{0,2,"en_GB"},{2,3,"de_DE"},{3,4,"it_IT"},{4,5,"nl_NL"},{5,7,"sv_SE"},{6,8,"es_ES"},{7,9,"da_DK"},
		{8,10,"pt_PT"},{1,11,"fr_CA"},{9,12,"no_NO"},{11,14,"ja_JP"},{0,15,"en_AU"},{13,17,"fi_FI"},{1,18,"fr_CH"},{2,19,"de_CH"},
		{14,20,"el_GR"},{32,49,"ru_RU"},{23,51,"ko_KR"},{33,52,"zh_CN"},{19,53,"zh_TW"},{8,71,"pt_BR"},{2,92,"de_AT"}};
	private static final int UNICODE_COLLATION=0x75636f6c,UNICODE_TEXT_BREAK=0x7562726b; // 'ucol', 'ubrk'
	/** LocaleRefs: a guest word per table entry, made at first use. */
	private final Map<Integer,Integer> localeRefs=new HashMap<Integer,Integer>();
	private final int[] refOfLocale=new int[LOCALES.length];
	private int localeRef(int index) throws M68kCpuException {
		if(refOfLocale[index]==0){int p=rt.allocate(4,4);memory.writeLong(p,index);refOfLocale[index]=p;localeRefs.put(p,index);}
		return refOfLocale[index];
	}
	private int systemLocaleIndex(){return rt.toolbox.encoding==StackTextEncoding.JAPANESE?12:0;}
	/** The table index of a LocaleRef (0 = the system's locale), or -1. */
	private int localeIndex(int ref){if(ref==0)return systemLocaleIndex();Integer i=localeRefs.get(ref);return i==null?-1:i;}
	private static Locale javaLocaleOf(int index){String[] p=((String)LOCALES[index][2]).split("_");return new Locale(p[0],p[1]);}
	/** The table index for a locale string ("ja_JP", "en-US", "fr"), or -1. */
	static int localeIndexOf(String s){
		if(s==null)return -1;s=s.trim().replace('-','_');
		for(int i=0;i<LOCALES.length;i++)if(((String)LOCALES[i][2]).equalsIgnoreCase(s))return i;
		String lang=s.contains("_")?s.substring(0,s.indexOf('_')):s;
		for(int i=0;i<LOCALES.length;i++)if(((String)LOCALES[i][2]).substring(0,2).equalsIgnoreCase(lang))return i;
		return -1;
	}
	/** Writes a C string of at most {@code max} bytes including the NUL; false when it had to be cut. */
	private boolean writeC(int p,String s,int max) throws M68kCpuException {
		if(p==0||max<=0)return s.isEmpty()&&max>0;
		byte[] b=s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);int n=Math.min(b.length,max-1);memory.load(p,b,0,n);memory.writeByte(p+n,0);return n==b.length;
	}
	/** A charset for a region's script (the Str255 of LocaleGetRegionLanguageName). */
	private byte[] regionEncode(int region,String s){
		try{
			switch(region){
				case 14:return StackTextEncoding.JAPANESE.encodeReplacing(s);
				case 51:return s.getBytes(Charset.forName("EUC-KR"));case 52:return s.getBytes(Charset.forName("GB2312"));case 53:return s.getBytes(Charset.forName("Big5"));
				case 49:return s.getBytes(Charset.forName("x-MacCyrillic"));case 20:return s.getBytes(Charset.forName("x-MacGreek"));
				default:return s.getBytes(Charset.forName("x-MacRoman"));
			}
		}catch(RuntimeException e){return s.getBytes(Charset.forName("x-MacRoman"));}
	}
	private static String operationName(int opClass,boolean japanese){
		if(opClass==UNICODE_COLLATION)return japanese?"Unicode 照合":"Unicode Collation";
		if(opClass==UNICODE_TEXT_BREAK)return japanese?"Unicode テキスト区切り":"Unicode Text Break";
		return null;
	}

	private void defineLocales(PpcLibraries l){
		// LocaleRefFromLangOrRegionCode(lang, region, *locale): -128 = don't care
		l.register(null,"LocaleRefFromLangOrRegionCode",(r,c)->{int lang=(short)arg(0),region=(short)arg(1),out=arg(2);if(out==0){ret(PARAM_ERR);return;}
			int found=-1;
			for(int i=0;i<LOCALES.length&&found<0;i++){boolean lm=lang==-128||(int)LOCALES[i][0]==lang,rm=region==-128||(int)LOCALES[i][1]==region;if(lm&&rm)found=i;}
			if(found<0&&lang!=-128)for(int i=0;i<LOCALES.length&&found<0;i++)if((int)LOCALES[i][0]==lang)found=i;
			if(found<0&&region!=-128)for(int i=0;i<LOCALES.length&&found<0;i++)if((int)LOCALES[i][1]==region)found=i;
			if(found<0||lang==-128&&region==-128){memory.writeLong(out,0);ret(PARAM_ERR);return;}
			memory.writeLong(out,localeRef(found));ret(NO_ERR);});
		l.register(null,"LocaleRefFromLocaleString",(r,c)->{int out=arg(1);if(arg(0)==0||out==0){ret(PARAM_ERR);return;}
			int i=localeIndexOf(rt.cString(arg(0)));if(i<0){memory.writeLong(out,0);ret(PARAM_ERR);return;}memory.writeLong(out,localeRef(i));ret(NO_ERR);});
		// LocaleRefGetPartString(locale, partMask, maxStringLen, partString[]): language (bit 0) and region (bit 4) parts
		l.register(null,"LocaleRefGetPartString",(r,c)->{int i=localeIndex(arg(0)),mask=arg(1),max=arg(2),out=arg(3);if(i<0||out==0){ret(PARAM_ERR);return;}
			String[] p=((String)LOCALES[i][2]).split("_");StringBuilder sb=new StringBuilder();
			if((mask&1)!=0)sb.append(p[0]);if((mask&0x10)!=0){if(sb.length()>0)sb.append('_');sb.append(p[1]);}
			ret(writeC(out,sb.toString(),max)?NO_ERR:LOCALES_BUFFER_TOO_SMALL);});
		l.register(null,"LocaleStringToLangAndRegionCodes",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}int i=localeIndexOf(rt.cString(arg(0)));if(i<0){ret(PARAM_ERR);return;}
			if(arg(1)!=0)memory.writeWord(arg(1),(int)LOCALES[i][0]);if(arg(2)!=0)memory.writeWord(arg(2),(int)LOCALES[i][1]);ret(NO_ERR);});
		l.register(null,"LocaleOperationCountLocales",(r,c)->{int op=arg(0),out=arg(1);if(out==0){ret(PARAM_ERR);return;}
			memory.writeLong(out,operationName(op,false)!=null?LOCALES.length:0);ret(NO_ERR);});
		// LocaleOperationGetLocales(opClass, maxLocaleCount, *actualLocaleCount, LocaleAndVariant list[]): {locale, opVariant 0}
		l.register(null,"LocaleOperationGetLocales",(r,c)->{int op=arg(0),max=arg(1),actual=arg(2),list=arg(3);if(actual==0){ret(PARAM_ERR);return;}
			int n=operationName(op,false)!=null?Math.min(max,LOCALES.length):0;
			for(int i=0;i<n;i++){memory.writeLong(list+8*i,localeRef(i));memory.writeLong(list+8*i+4,0);}
			memory.writeLong(actual,n);ret(NO_ERR);});
		// LocaleGetName(locale, opVariant, nameMask, displayLocale, maxNameLen, *actualNameLen, displayName[])
		l.register(null,"LocaleGetName",(r,c)->{int i=localeIndex(arg(0)),d=localeIndex(arg(3));if(i<0||d<0){ret(PARAM_ERR);return;}
			String name=(arg(2)&1)!=0?javaLocaleOf(i).getDisplayName(javaLocaleOf(d)):""; // no operation variants: their names are empty
			ret(writeUni(arg(6),name,arg(4),arg(5))?NO_ERR:LOCALES_BUFFER_TOO_SMALL);});
		l.register(null,"LocaleCountNames",(r,c)->{if(localeIndex(arg(0))<0||arg(3)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(3),LOCALES.length);ret(NO_ERR);});
		// LocaleGetIndName(locale, opVariant, nameMask, nameIndex, maxNameLen, *actualNameLen, displayName[], *displayLocale): the name in each table locale's language
		l.register(null,"LocaleGetIndName",(r,c)->{int i=localeIndex(arg(0)),index=arg(3);if(i<0||index<0||index>=LOCALES.length){ret(PARAM_ERR);return;}
			String name=(arg(2)&1)!=0?javaLocaleOf(i).getDisplayName(javaLocaleOf(index)):"";
			if(arg(7)!=0)memory.writeLong(arg(7),localeRef(index));
			ret(writeUni(arg(6),name,arg(4),arg(5))?NO_ERR:LOCALES_BUFFER_TOO_SMALL);});
		// LocaleGetRegionLanguageName(region, Str255): the region's language named in that language, in the region's script
		l.register(null,"LocaleGetRegionLanguageName",(r,c)->{int region=(short)arg(0),out=arg(1);int found=-1;
			for(int i=0;i<LOCALES.length&&found<0;i++)if((int)LOCALES[i][1]==region)found=i;
			if(found<0||out==0){if(out!=0)memory.writeByte(out,0);ret(PARAM_ERR);return;}
			Locale jl=javaLocaleOf(found);byte[] b=regionEncode(region,jl.getDisplayLanguage(jl));int n=Math.min(255,b.length);
			memory.writeByte(out,n);memory.load(out+1,b,0,n);ret(NO_ERR);});
		l.register(null,"LocaleOperationGetName",(r,c)->{int d=localeIndex(arg(1));String name=operationName(arg(0),d==12);if(name==null||d<0){ret(PARAM_ERR);return;}
			ret(writeUni(arg(4),name,arg(2),arg(3))?NO_ERR:LOCALES_BUFFER_TOO_SMALL);});
		l.register(null,"LocaleOperationCountNames",(r,c)->{if(operationName(arg(0),false)==null||arg(1)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(1),2);ret(NO_ERR);});
		// LocaleOperationGetIndName(opClass, nameIndex, maxNameLen, *actualNameLen, displayName[], *displayLocale): English, Japanese
		l.register(null,"LocaleOperationGetIndName",(r,c)->{int index=arg(1);String name=operationName(arg(0),index==1);if(name==null||index<0||index>1){ret(PARAM_ERR);return;}
			if(arg(5)!=0)memory.writeLong(arg(5),localeRef(index==1?12:0));
			ret(writeUni(arg(4),name,arg(2),arg(3))?NO_ERR:LOCALES_BUFFER_TOO_SMALL);});
	}

	// ================================================================== time

	static final long EPOCH_1904=2082844800L;
	/** Local time minus UTC, in seconds: the offset the 68K clock (GetDateTime) runs with. */
	private static int localOffset(){return Toolbox68k.localOffsetSeconds();}
	/** UTCDateTime / LocalDateTime: UInt16 highSeconds, UInt32 lowSeconds, UInt16 fraction (1/65536 s), seconds since 1904. */
	private void writeDateTime(int p,long seconds,int fraction) throws M68kCpuException {memory.writeWord(p,(int)(seconds>>>32)&0xffff);memory.writeLong(p+2,(int)seconds);memory.writeWord(p+6,fraction);}
	private long readDateTime(int p) throws M68kCpuException {return ((long)memory.readWord(p)<<32)|(memory.readLong(p+2)&0xffffffffL);}
	/** Now as {seconds since 1904 UTC, fraction}, on the VM clock (Toolbox68k.clockShiftSeconds). */
	private static long[] utcNow(){long ms=System.currentTimeMillis();return new long[]{Math.floorDiv(ms,1000L)+EPOCH_1904+Toolbox68k.clockShiftSeconds(),Math.floorMod(ms,1000L)*65536/1000};}
	/** Duration (positive milliseconds, negative microseconds) to nanoseconds. */
	static long durationToNanos(int d){return d>=0?d*1_000_000L:-(long)d*1000L;}
	/** Nanoseconds to a Duration: microseconds (negative) while they fit, else milliseconds; kDurationForever beyond. */
	static int nanosToDuration(long ns){
		if(ns<=0)return 0;long us=ns/1000;if(us<=Integer.MAX_VALUE)return (int)-us;
		long ms=ns/1_000_000;return ms>=Integer.MAX_VALUE?Integer.MAX_VALUE:(int)ms;
	}

	private void defineTime(PpcLibraries l){
		l.register(null,"GetUTCDateTime",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}long[] now=utcNow();writeDateTime(p,now[0],(int)now[1]);ret(NO_ERR);});
		l.register(null,"GetLocalDateTime",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}long[] now=utcNow();writeDateTime(p,now[0]+localOffset(),(int)now[1]);ret(NO_ERR);});
		// the host's clock is not the program's to set
		l.register(null,"SetUTCDateTime",(r,c)->ret(arg(0)==0?PARAM_ERR:NO_ERR));
		l.register(null,"SetLocalDateTime",(r,c)->ret(arg(0)==0?PARAM_ERR:NO_ERR));
		l.register(null,"ConvertLocalTimeToUTC",(r,c)->{if(arg(1)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(1),arg(0)-localOffset());ret(NO_ERR);});
		l.register(null,"ConvertUTCToLocalTime",(r,c)->{if(arg(1)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(1),arg(0)+localOffset());ret(NO_ERR);});
		l.register(null,"ConvertUTCToLocalDateTime",(r,c)->{int s=arg(0),d=arg(1);if(s==0||d==0){ret(PARAM_ERR);return;}writeDateTime(d,readDateTime(s)+localOffset(),memory.readWord(s+6));ret(NO_ERR);});
		l.register(null,"ConvertLocalToUTCDateTime",(r,c)->{int s=arg(0),d=arg(1);if(s==0||d==0){ret(PARAM_ERR);return;}writeDateTime(d,readDateTime(s)-localOffset(),memory.readWord(s+6));ret(NO_ERR);});
		// DriverServices time arithmetic: AbsoluteTime counts nanoseconds here (UpTime is System.nanoTime)
		l.register(null,"NanosecondsToAbsolute",(r,c)->{int p=arg(0);put64(p,s64(1));ret(p);});
		l.register(null,"DurationToAbsolute",(r,c)->{int p=arg(0);put64(p,durationToNanos(arg(1)));ret(p);});
		l.register(null,"DurationToNanoseconds",(r,c)->{int p=arg(0);put64(p,durationToNanos(arg(1)));ret(p);});
		l.register(null,"AbsoluteToDuration",(r,c)->ret(nanosToDuration(s64(0)))); // replaces PpcLibraries' version, which answered positive microseconds (read as milliseconds)
		l.register(null,"NanosecondsToDuration",(r,c)->ret(nanosToDuration(s64(0))));
		l.register(null,"AddAbsoluteToAbsolute",(r,c)->{int p=arg(0);put64(p,s64(1)+s64(3));ret(p);});
		l.register(null,"SubAbsoluteFromAbsolute",(r,c)->{int p=arg(0);long a=s64(1),b=s64(3);put64(p,Long.compareUnsigned(a,b)>0?a-b:0);ret(p);});
		l.register(null,"AddNanosecondsToAbsolute",(r,c)->{int p=arg(0);put64(p,s64(1)+s64(3));ret(p);});
		l.register(null,"SubNanosecondsFromAbsolute",(r,c)->{int p=arg(0);long ns=s64(1),a=s64(3);put64(p,Long.compareUnsigned(a,ns)>0?a-ns:0);ret(p);});
		l.register(null,"AddDurationToAbsolute",(r,c)->{int p=arg(0);put64(p,s64(2)+durationToNanos(arg(1)));ret(p);});
		l.register(null,"SubDurationFromAbsolute",(r,c)->{int p=arg(0);long a=s64(2),d=durationToNanos(arg(1));put64(p,Long.compareUnsigned(a,d)>0?a-d:0);ret(p);});
		// the delta is 0 when the right time is the later one
		l.register(null,"AbsoluteDeltaToNanoseconds",(r,c)->{int p=arg(0);long a=s64(1),b=s64(3);put64(p,Long.compareUnsigned(a,b)>0?a-b:0);ret(p);});
		l.register(null,"AbsoluteDeltaToDuration",(r,c)->{long a=s64(0),b=s64(2);ret(Long.compareUnsigned(a,b)>0?nanosToDuration(a-b):0);});
		l.register(null,"GetTimeBaseInfo",(r,c)->{for(int i=0;i<5;i++)if(arg(i)!=0)memory.writeLong(arg(i),1);}); // 1 ns resolution, ratios 1/1
	}

	// ================================================================== Memory, Files, Power, OS glue

	private interface PbBody { int run(int pb) throws M68kException; }
	/** A zeroed parameter block carved from the 68K stack for {@code body}. */
	private int withPb(int size,PbBody body) throws M68kException {
		int sp=rt.cpu68.a[7];int pb=(sp-size-16)&~3;rt.cpu68.a[7]=pb-16;memory.fill(pb,size,0);
		try{return body.run(pb);}finally{rt.cpu68.a[7]=sp;}
	}
	private int pbTrap(int word,int pb) throws M68kException {return (short)rt.trap(word,0,pb,0);}
	/** OSAComp's trailer: DescType, version 1, length (12, 13 with a pad byte), $FADEDEAD. */
	private int storageTrailer(int h) throws M68kException {
		if(h==0||!rt.toolbox.api.isHandle(h))return 0;
		int size=rt.toolbox("GetHandleSize",h),data=memory.readLong(h);
		if(size<12||(size&1)!=0||data==0)return 0;
		int t=data+size-12;return memory.readLong(t+8)==0xfadedead&&memory.readWord(t+4)==1?t:0;
	}

	private void defineSystem(PpcLibraries l){
		// Memory Manager checks
		l.register(null,"CheckAllHeaps",(r,c)->retB(true));
		l.register(null,"IsHeapValid",(r,c)->retB(true));
		l.register(null,"IsHandleValid",(r,c)->retB(rt.toolbox.api.isHandle(arg(0))));
		l.register(null,"IsPointerValid",(r,c)->{int p=arg(0);retB(p!=0&&(p&1)==0&&memory.isAllocated(p)&&rt.toolbox.api.handleForPointer(p)==0);});
		l.register(null,"WriteParam",(r,c)->ret(NO_ERR)); // parameter RAM is the host's
		// PBWaitIOComplete(pb, timeout): ioResult (offset 16) at or below 0 ends the wait
		l.register(null,"PBWaitIOComplete",(r,c)->{int pb=arg(0);if(pb==0){ret(PARAM_ERR);return;}
			long limit=System.nanoTime()+Math.min(durationToNanos(arg(1)==0x7fffffff?5000:arg(1)),5_000_000_000L);
			int result=(short)memory.readWord(pb+16);
			while(result>0&&System.nanoTime()<limit){try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}result=(short)memory.readWord(pb+16);}
			ret(result>0?-29296:result);}); // kMPTimeoutErr
		// File Manager shortcuts on parameter blocks
		l.register(null,"AllocContig",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+24,arg(0));int n=arg(1)==0?0:memory.readLong(arg(1));memory.writeLong(pb+36,n);
			int e=pbTrap(0xa210,pb);if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(pb+40));return e;})));
		l.register(null,"Eject",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));return pbTrap(0xa017,pb);})));
		l.register(null,"UnmountVol",(r,c)->ret(withPb(80,pb->{memory.writeLong(pb+18,arg(0));memory.writeWord(pb+22,arg(1));return pbTrap(0xa00e,pb);})));
		l.register(null,"HSetFLock",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));memory.writeLong(pb+18,arg(2));return pbTrap(0xa241,pb);})));
		l.register(null,"HRstFLock",(r,c)->ret(withPb(80,pb->{memory.writeWord(pb+22,arg(0));memory.writeLong(pb+48,arg(1));memory.writeLong(pb+18,arg(2));return pbTrap(0xa242,pb);})));
		// the Alias Manager without user interaction
		l.register(null,"MatchAliasNoUI",(r,c)->ret((short)rt.toolbox("MatchAlias",arg(0),arg(1)|2,arg(2),arg(3),arg(4),arg(5),arg(6),arg(7)))); // kARMNoUI
		l.register(null,"ResolveAliasFileWithMountFlagsNoUI",(r,c)->ret((short)rt.toolbox("ResolveAliasFileWithMountFlags",arg(0),arg(1)&0xff,arg(2),arg(3),arg(4)|1))); // kResolveAliasFileNoUI
		// Power Manager: a desktop Mac has none (Gestalt 'powr' 0)
		l.register(null,"BatteryStatus",(r,c)->{if(arg(0)!=0)memory.writeByte(arg(0),0);if(arg(1)!=0)memory.writeByte(arg(1),0);ret(UNIMP_ERR);});
		l.register(null,"ModemStatus",(r,c)->{if(arg(0)!=0)memory.writeByte(arg(0),0);ret(UNIMP_ERR);});
		l.register(null,"GetWUTime",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),0);if(arg(1)!=0)memory.writeByte(arg(1),0);ret(UNIMP_ERR);});
		l.register(null,"SetWUTime",(r,c)->ret(UNIMP_ERR));
		l.register(null,"DisableWUTime",(r,c)->ret(UNIMP_ERR));
		// QuickDraw glue
		l.register(null,"StandardGlyphs",(r,c)->ret(NO_ERR));
		l.register(null,"stdtext",(r,c)->rt.toolbox("StdText",(short)arg(0),arg(1),memory.readLong(arg(2)),memory.readLong(arg(3))));
		l.register(null,"deltapoint",(r,c)->ret(rt.toolbox("DeltaPoint",memory.readLong(arg(0)),memory.readLong(arg(1)))));
		// ColorSync: no control panel to open
		l.register(null,"CMLaunchControlPanel",(r,c)->ret(FNF_ERR));
		// CMGetColorSyncFolderSpec(vRefNum, createFolder, *foundVRefNum, *foundDirID) = FindFolder for the ColorSync Profiles folder ('prof'); Adobe Illustrator 8's RB2Connection.mac calls it at start-up
		l.register(null,"CMGetColorSyncFolderSpec",(r,c)->ret(rt.toolbox("FindFolder",(short)arg(0),0x70726f66,arg(1)&0xff,arg(2),arg(3))));
		// OSAComp: the storage type trailer of a script data Handle
		l.register(null,"OSAGetStorageType",(r,c)->{int t=storageTrailer(arg(0));if(t==0){ret(-1752);return;}if(arg(1)!=0)memory.writeLong(arg(1),memory.readLong(t));ret(NO_ERR);}); // errOSABadStorageType
		l.register(null,"OSAAddStorageType",(r,c)->{int h=arg(0);if(h==0||!rt.toolbox.api.isHandle(h)){ret(-1752);return;}
			int t=storageTrailer(h);if(t!=0){memory.writeLong(t,arg(1));ret(NO_ERR);return;}
			int size=rt.toolbox("GetHandleSize",h);int pad=size&1;
			rt.toolbox("SetHandleSize",h,size+pad+12);if(rt.toolbox("GetHandleSize",h)!=size+pad+12){ret(-108);return;} // memFullErr
			int p=memory.readLong(h)+size;if(pad!=0)memory.writeByte(p++,0);
			memory.writeLong(p,arg(1));memory.writeWord(p+4,1);memory.writeWord(p+6,12+pad);memory.writeLong(p+8,0xfadedead);ret(NO_ERR);});
		l.register(null,"OSARemoveStorageType",(r,c)->{int h=arg(0);int t=storageTrailer(h);if(t==0){ret(NO_ERR);return;}
			int size=rt.toolbox("GetHandleSize",h),len=memory.readWord(t+6);if(len!=12&&len!=13)len=12;
			rt.toolbox("SetHandleSize",h,size-len);ret(NO_ERR);});
	}

	// ================================================================== Component Manager glue

	/** Parameter sizes (bytes on the 68K stack: 2 for bytes and words) of a stack-based procInfo, in declaration order. */
	private static List<Integer> stackParams(int procInfo){
		List<Integer> sizes=new ArrayList<Integer>();
		for(int bit=6;bit<32;bit+=2){int code=(procInfo>>>bit)&3;if(code==0)break;sizes.add(code);}
		return sizes;
	}
	private void defineComponents(PpcLibraries l){
		// CallComponentDispatch(ComponentParameters *): the block as the 68K stack holds it (flags, paramSize, what, parameters, instance)
		l.register(null,"CallComponentDispatch",(r,c)->{int pb=arg(0);int paramSize=memory.readByte(pb+1);ret(r.runOnStack(new int[]{0x7000,0xa82a},pb,4+paramSize+4,4));});
		// CallComponentFunctionWithStorageProcInfo(storage, params, func, procInfo): func(storage, the call's parameters) - the
		// instance's slot (the first Pascal parameter, highest in the block) is taken by the storage
		l.register(null,"CallComponentFunctionWithStorageProcInfo",(r,c)->{
			int storage=arg(0),pb=arg(1),func=arg(2),procInfo=arg(3);
			List<Integer> codes=stackParams(procInfo);int n=Math.max(1,codes.size());
			int[] a=new int[n];a[0]=storage;
			int top=4+memory.readByte(pb+1); // the instance's offset
			for(int i=1;i<n;i++){int code=codes.get(i);int bytes=code==3?4:2;top-=bytes;
				a[i]=code==3?memory.readLong(pb+top):code==2?memory.readWord(pb+top):memory.readByte(pb+top);}
			ret(r.isRoutineDescriptor(func)?r.callUniversal(func,procInfo,a):r.cpu.call(func,a));});
		l.register(null,"NewComponentFunctionUPP",(r,c)->ret(arg(0)==0?0:r.newRoutineDescriptor(arg(0),arg(1),PpcRuntime.ISA_PPC)));
		l.register(null,"DisposeComponentFunctionUPP",(r,c)->{int rd=arg(0);if(rd!=0&&r.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);});
	}

	// ================================================================== services a system may lack

	/** An output parameter: argument {@code index} points at {@code size} bytes to clear. */
	private static int out(int index,int size){return index<<3|size;}
	/** A function of an extension that is not installed: clears its output parameters and answers {@code err}. */
	private void absent(PpcLibraries l,String name,int err,int... outs){
		l.register(null,name,(r,c)->{
			for(int o:outs){int p=arg(o>>>3),size=o&7;if(p==0)continue;if(size==1)memory.writeByte(p,0);else if(size==2)memory.writeWord(p,0);else memory.writeLong(p,0);}
			ret(err);});
	}
	/** An NSLError-returning function (8-byte structure through the hidden pointer): {theErr, theContext 0}. */
	private void nslError(PpcLibraries l,String name,int err,int... outs){
		l.register(null,name,(r,c)->{
			for(int o:outs){int p=arg(o>>>3);if(p!=0)memory.writeLong(p,0);}
			int p=arg(0);memory.writeLong(p,err);memory.writeLong(p+4,0);ret(p);});
	}
	/** Dictionary Manager iterators over nothing (no dictionaries, no access methods). */
	private final Set<Integer> emptyIterators=new HashSet<Integer>();

	static final int FNS_INVALID_REFERENCE=-29580,FNS_BAD_REFERENCE_VERSION=-29581,FNS_INVALID_PROFILE=-29582,FNS_BAD_PROFILE_VERSION=-29583,FNS_INSUFFICIENT_DATA=-29586;
	static final int FBC_NO_INDEXES=-30501,FBC_INDEX_NOT_FOUND=-30518,FBC_ANALYSIS_NOT_AVAILABLE=-30527,FBC_BAD_SESSION=-30531;
	static final int LA_ENVIRONMENT_NOT_FOUND=-6986;
	static final int DCM_NOT_DICTIONARY=-7101,DCM_BAD_DICTIONARY=-7102,DCM_NOT_OPEN=-7104,DCM_BAD_FIELD_INFO=-7111,DCM_NO_ACCESS_METHOD=-7122,DCM_ITERATION_COMPLETE=-7126;
	static final int NSL_BAD_URL_SYNTAX=-4172,NSL_NULL_PTR=-4176,NSL_NOT_INITIALIZED=-4199,NSL_INITIALIZATION_FAILED=-4200;
	static final int KC_NOT_AVAILABLE=-25291,CM_UNSUPPORTED_DATA_TYPE=-181;

	private void defineAbsentServices(PpcLibraries l){
		// ---- FontSync: FNSSysInfoGet reports no FontSync (versions 0), so no reference or profile version is known
		l.register(null,"FNSSysInfoGet",(r,c)->{int p=arg(0);if(p!=0)memory.fill(p+4,22,0);});
		l.register(null,"FNSMatchDefaultsGet",(r,c)->ret(0));
		l.register(null,"FNSEnabled",(r,c)->retB(false));
		absent(l,"FNSReferenceGetVersion",FNS_INVALID_REFERENCE,out(1,4));
		absent(l,"FNSReferenceDispose",FNS_INVALID_REFERENCE);
		absent(l,"FNSReferenceMatch",FNS_INVALID_REFERENCE,out(3,4));
		absent(l,"FNSReferenceFlattenedSize",FNS_INVALID_REFERENCE,out(1,4));
		absent(l,"FNSReferenceFlatten",FNS_INVALID_REFERENCE,out(2,4));
		absent(l,"FNSReferenceUnflatten",FNS_INSUFFICIENT_DATA,out(2,4));
		absent(l,"FNSReferenceCreate",FNS_BAD_REFERENCE_VERSION,out(2,4));
		absent(l,"FNSReferenceCreateFromFamily",FNS_BAD_REFERENCE_VERSION,out(3,4),out(4,2));
		absent(l,"FNSReferenceMatchFonts",FNS_INVALID_REFERENCE,out(4,4));
		absent(l,"FNSReferenceMatchFamilies",FNS_INVALID_REFERENCE,out(4,4));
		absent(l,"FNSReferenceGetFamilyInfo",FNS_INVALID_REFERENCE,out(1,1),out(2,2),out(3,2));
		absent(l,"FNSReferenceCountNames",FNS_INVALID_REFERENCE,out(1,4));
		absent(l,"FNSReferenceGetIndName",FNS_INVALID_REFERENCE,out(4,4),out(5,4),out(6,4),out(7,4),out(8,4));
		absent(l,"FNSReferenceFindName",FNS_INVALID_REFERENCE,out(7,4),out(8,4));
		absent(l,"FNSProfileCreate",FNS_BAD_PROFILE_VERSION,out(4,4));
		absent(l,"FNSProfileOpen",FNS_BAD_PROFILE_VERSION,out(2,4));
		absent(l,"FNSProfileGetVersion",FNS_INVALID_PROFILE,out(1,4));
		for(String n:new String[]{"FNSProfileCompact","FNSProfileClose","FNSProfileAddReference","FNSProfileRemoveReference","FNSProfileRemoveIndReference","FNSProfileClear"})absent(l,n,FNS_INVALID_PROFILE);
		absent(l,"FNSProfileCountReferences",FNS_INVALID_PROFILE,out(1,4));
		absent(l,"FNSProfileGetIndReference",FNS_INVALID_PROFILE,out(2,4));
		absent(l,"FNSProfileMatchReference",FNS_INVALID_PROFILE,out(5,4));

		// ---- Find By Content: no volume is indexed, no search session can be made
		l.register(null,"FBCSetCallback",(r,c)->{});l.register(null,"FBCSetHeapReservation",(r,c)->{});
		l.register(null,"FBCVolumeIsIndexed",(r,c)->retB(false));l.register(null,"FBCVolumeIsRemote",(r,c)->retB(false));
		absent(l,"FBCVolumeIndexTimeStamp",FBC_INDEX_NOT_FOUND,out(1,4));absent(l,"FBCVolumeIndexPhysicalSize",FBC_INDEX_NOT_FOUND,out(1,4));
		absent(l,"FBCCreateSearchSession",FBC_NO_INDEXES,out(0,4));
		absent(l,"FBCBlindExampleSearch",FBC_NO_INDEXES,out(8,4));
		absent(l,"FBCAddAllVolumesToSession",FBC_BAD_SESSION);absent(l,"FBCSetSessionVolumes",FBC_BAD_SESSION);
		absent(l,"FBCAddVolumeToSession",FBC_BAD_SESSION);absent(l,"FBCRemoveVolumeFromSession",FBC_BAD_SESSION);
		absent(l,"FBCGetSessionVolumeCount",FBC_BAD_SESSION,out(1,2));absent(l,"FBCGetSessionVolumes",FBC_BAD_SESSION,out(2,2));
		absent(l,"FBCCloneSearchSession",FBC_BAD_SESSION,out(1,4));
		absent(l,"FBCDoQuerySearch",FBC_BAD_SESSION);absent(l,"FBCDoExampleSearch",FBC_BAD_SESSION);
		absent(l,"FBCGetHitCount",FBC_BAD_SESSION,out(1,4));absent(l,"FBCGetHitDocument",FBC_BAD_SESSION);
		absent(l,"FBCGetHitScore",FBC_BAD_SESSION,out(2,4));
		absent(l,"FBCGetMatchedWords",FBC_BAD_SESSION,out(2,4),out(3,4));absent(l,"FBCGetTopicWords",FBC_BAD_SESSION,out(2,4),out(3,4));
		absent(l,"FBCSummarize",FBC_ANALYSIS_NOT_AVAILABLE,out(3,4),out(4,4));
		absent(l,"FBCReleaseSessionHits",FBC_BAD_SESSION);absent(l,"FBCDestroySearchSession",FBC_BAD_SESSION);
		absent(l,"FBCDestroyWordList",NO_ERR);absent(l,"FBCIndexItems",FBC_NO_INDEXES);

		// ---- Language Analysis Manager: no analysis environments
		l.register(null,"LALibraryVersion",(r,c)->ret(0));
		l.register(null,"LAGetEnvironmentList",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(NO_ERR);});
		absent(l,"LAGetEnvironmentName",LA_ENVIRONMENT_NOT_FOUND,out(1,1));
		absent(l,"LAGetEnvironmentRef",LA_ENVIRONMENT_NOT_FOUND,out(1,4));
		absent(l,"LACreateCustomEnvironment",LA_ENVIRONMENT_NOT_FOUND,out(3,4));
		absent(l,"LADeleteCustomEnvironment",LA_ENVIRONMENT_NOT_FOUND);
		absent(l,"LAOpenAnalysisContext",LA_ENVIRONMENT_NOT_FOUND,out(1,4));
		absent(l,"LAOpenDictionary",LA_ENVIRONMENT_NOT_FOUND);absent(l,"LACloseDictionary",LA_ENVIRONMENT_NOT_FOUND);
		absent(l,"LAListAvailableDictionaries",LA_ENVIRONMENT_NOT_FOUND,out(2,4));
		absent(l,"LAAddNewWord",LA_ENVIRONMENT_NOT_FOUND);
		for(String n:new String[]{"LACloseAnalysisContext","LAMorphemeAnalysis","LAContinuousMorphemeAnalysis","LAGetMorphemes","LAShiftMorphemes","LAResetAnalysis"})absent(l,n,PARAM_ERR);
		absent(l,"LATextToMorphemes",PARAM_ERR,out(7,4));

		// ---- Dictionary Manager: no access methods, so no dictionaries
		l.register(null,"DCMLibraryVersion",(r,c)->ret(0));
		PpcRuntime.Native newIterator=(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}int it=rt.allocate(4,4);emptyIterators.add(it);memory.writeLong(p,it);ret(NO_ERR);};
		l.register(null,"DCMCreateDictionaryIterator",newIterator);l.register(null,"DCMCreateAccessMethodIterator",newIterator);
		l.register(null,"DCMCountObjectIterator",(r,c)->ret(0));
		l.register(null,"DCMIterateObject",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(emptyIterators.contains(arg(0))?DCM_ITERATION_COMPLETE:PARAM_ERR);});
		l.register(null,"DCMResetObjectIterator",(r,c)->ret(emptyIterators.contains(arg(0))?NO_ERR:PARAM_ERR));
		l.register(null,"DCMDisposeObjectIterator",(r,c)->{int it=arg(0);if(!emptyIterators.remove(it)){ret(PARAM_ERR);return;}memory.free(it);ret(NO_ERR);});
		absent(l,"DCMGetAccessMethodIDFromName",DCM_NO_ACCESS_METHOD,out(1,4));
		absent(l,"DCMNewDictionary",DCM_NO_ACCESS_METHOD,out(6,4));
		absent(l,"DCMDeriveNewDictionary",DCM_BAD_DICTIONARY,out(5,4));
		absent(l,"DCMRegisterDictionaryFile",DCM_NOT_DICTIONARY,out(1,4));
		absent(l,"DCMGetDictionaryIDFromFile",DCM_NOT_DICTIONARY,out(1,4));
		absent(l,"DCMOpenDictionary",DCM_BAD_DICTIONARY,out(3,4));
		absent(l,"DCMCountRecord",DCM_BAD_DICTIONARY,out(1,4));
		absent(l,"DCMGetDictionaryPropertyList",DCM_BAD_DICTIONARY,out(2,4));
		absent(l,"DCMGetDictionaryProperty",DCM_BAD_DICTIONARY,out(3,4));
		for(String n:new String[]{"DCMDeleteDictionary","DCMUnregisterDictionary","DCMReorganizeDictionary","DCMCompactDictionary","DCMGetFileFromDictionaryID",
				"DCMGetDictionaryFieldInfo","DCMSetDictionaryProperty"})absent(l,n,DCM_BAD_DICTIONARY);
		absent(l,"DCMFindRecords",DCM_NOT_OPEN,out(9,4));
		absent(l,"DCMGetRecordSequenceNumber",DCM_NOT_OPEN,out(5,4));
		absent(l,"DCMGetNthRecord",DCM_NOT_OPEN,out(4,4),out(6,4));
		absent(l,"DCMGetNextRecord",DCM_NOT_OPEN,out(6,4),out(8,4));absent(l,"DCMGetPrevRecord",DCM_NOT_OPEN,out(6,4),out(8,4));
		absent(l,"DCMAddRecord",DCM_NOT_OPEN,out(6,4));
		for(String n:new String[]{"DCMCloseDictionary","DCMGetDictionaryWriteAccess","DCMReleaseDictionaryWriteAccess","DCMGetFieldData","DCMSetFieldData","DCMDeleteRecord"})absent(l,n,DCM_NOT_OPEN);
		l.register(null,"DCMCountRecordIterator",(r,c)->ret(0));
		absent(l,"DCMIterateFoundRecord",DCM_ITERATION_COMPLETE,out(2,4),out(4,4));
		absent(l,"DCMDisposeRecordIterator",NO_ERR);
		l.register(null,"DCMGetDictionaryIDFromRef",(r,c)->ret(0));
		absent(l,"DCMCreateFieldInfoRecord",DCM_BAD_FIELD_INFO);
		absent(l,"DCMGetFieldTagAndType",DCM_BAD_FIELD_INFO,out(1,4),out(2,4));
		absent(l,"DCMGetFieldMaxRecordSize",DCM_BAD_FIELD_INFO,out(1,4));
		absent(l,"DCMGetFieldAttributes",DCM_BAD_FIELD_INFO,out(1,4));
		absent(l,"DCMGetFieldDefaultData",DCM_BAD_FIELD_INFO);
		absent(l,"DCMGetFieldFindMethods",DCM_BAD_FIELD_INFO,out(3,4));

		// ---- Network Services Location: the manager cannot be opened; the text utilities still work
		l.register(null,"NSLLibraryVersion",(r,c)->ret(0));
		absent(l,"NSLOpenNavigationAPI",NSL_INITIALIZATION_FAILED,out(0,4));
		l.register(null,"NSLCloseNavigationAPI",(r,c)->{});
		for(String n:new String[]{"NSLStandardRegisterURL","NSLStandardDeregisterURL","NSLAddServiceToServicesList","NSLStartNeighborhoodLookup","NSLStartServicesLookup",
				"NSLContinueLookup","NSLCancelRequest","NSLDeleteRequest"})nslError(l,n,NSL_NOT_INITIALIZED);
		nslError(l,"NSLPrepareRequest",NSL_NOT_INITIALIZED,out(4,4),out(7,4)); // *ref, *infoPtr (after the hidden pointer)
		// NSLGetDefaultDialogOptions: version, flags, four empty Str255s (mac68k: flags at 2, strings from 6)
		l.register(null,"NSLGetDefaultDialogOptions",(r,c)->{int p=arg(0);if(p==0){ret(NSL_NULL_PTR);return;}memory.fill(p,6+4*256,0);ret(NO_ERR);});
		absent(l,"NSLStandardGetURL",USER_CANCELED,out(5,4)); // no NSL dialog: as if cancelled, *userSelectedURL NULL
		l.register(null,"NSLFreeURL",(r,c)->{int p=arg(0);if(p!=0&&memory.isAllocated(p))memory.free(p);ret(0);});
		l.register(null,"NSLMakeNewServicesList",(r,c)->ret(0));l.register(null,"NSLDisposeServicesList",(r,c)->{});
		l.register(null,"NSLServiceIsInServiceList",(r,c)->retB(false));
		l.register(null,"NSLMakeNewNeighborhood",(r,c)->ret(0));l.register(null,"NSLCopyNeighborhood",(r,c)->ret(0));l.register(null,"NSLFreeNeighborhood",(r,c)->ret(0));
		l.register(null,"NSLGetNeighborhoodLength",(r,c)->ret(0));
		l.register(null,"NSLGetNameFromNeighborhood",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);if(arg(2)!=0)memory.writeLong(arg(2),0);});
		l.register(null,"NSLFreeTypedDataPtr",(r,c)->ret(0));
		absent(l,"NSLMakeServicesRequestPB",NSL_NOT_INITIALIZED,out(1,4));
		absent(l,"NSLParseServicesRequestPB",NSL_NOT_INITIALIZED,out(1,4),out(2,2));
		absent(l,"NSLParseServiceRegistrationPB",NSL_NOT_INITIALIZED,out(1,4),out(2,2),out(3,4),out(4,2));
		l.register(null,"NSLGetNextUrl",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);if(arg(2)!=0)memory.writeLong(arg(2),0);retB(false);});
		l.register(null,"NSLGetNextNeighborhood",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);if(arg(2)!=0)memory.writeLong(arg(2),0);retB(false);});
		absent(l,"NSLNewThread",NSL_NOT_INITIALIZED,out(6,4));absent(l,"NSLDisposeThread",NSL_NOT_INITIALIZED);
		// NSLErrorToString(NSLError (2 slots), errorString, solutionString)
		l.register(null,"NSLErrorToString",(r,c)->{writeC(arg(2),"Network Services Location error "+arg(0),256);writeC(arg(3),"The Network Services Location Manager is not installed.",256);ret(NO_ERR);});
		absent(l,"NSLGetErrorStringsFromResource",NSL_NOT_INITIALIZED);
		// NSLGetServiceFromURL(url, char **svcString, UInt16 *svcLen): the scheme before ':'
		l.register(null,"NSLGetServiceFromURL",(r,c)->{int u=arg(0);if(u==0){ret(NSL_NULL_PTR);return;}byte[] b=rt.cBytes(u);int colon=-1;for(int i=0;i<b.length;i++)if(b[i]==':'){colon=i;break;}
			if(colon<=0){ret(NSL_BAD_URL_SYNTAX);return;}if(arg(1)!=0)memory.writeLong(arg(1),u);if(arg(2)!=0)memory.writeWord(arg(2),colon);ret(NO_ERR);});
		l.register(null,"NSLHexEncodeText",(r,c)->ret(hexText(true)));
		l.register(null,"NSLHexDecodeText",(r,c)->ret(hexText(false)));

		// ---- file signing (FileSigning.h) and Cryptographic Message Syntax: the security libraries are not there
		absent(l,"SecSetProgressCallback",KC_NOT_AVAILABLE);absent(l,"SecRemoveProgressCallback",KC_NOT_AVAILABLE);
		l.register(null,"DefaultSecProgressCallbackProc",(r,c)->ret(NO_ERR));
		absent(l,"SecMacSignFile",KC_NOT_AVAILABLE);absent(l,"SecMacSignFileSimple",KC_NOT_AVAILABLE);
		absent(l,"SecMacLoadSigPound",KC_NOT_AVAILABLE,out(1,4));
		absent(l,"SecMacRemoveSignature",KC_NOT_AVAILABLE);absent(l,"SecMacRemoveAllSignatures",KC_NOT_AVAILABLE);
		absent(l,"SecMacVerifyFile",KC_NOT_AVAILABLE,out(7,4));absent(l,"SecMacVerifyFileSimple",KC_NOT_AVAILABLE,out(3,4));
		l.register(null,"SecMacHasSignature",(r,c)->retB(false));
		absent(l,"SecMacFindSignatureToVerify",KC_NOT_AVAILABLE,out(2,2));
		l.register(null,"SecMacGetDefaultPolicyOIDs",(r,c)->ret(0));
		absent(l,"SecMacDisplaySigner",KC_NOT_AVAILABLE);
		absent(l,"SecSignerGetStatus",PARAM_ERR);
		l.register(null,"SecRetain",(r,c)->ret(arg(0)));l.register(null,"SecRelease",(r,c)->{});l.register(null,"SecRetainCount",(r,c)->ret(0));

		// ---- ColorSync scripting plug-in: no image format it could read
		absent(l,"CMValidImage",CM_UNSUPPORTED_DATA_TYPE);
		absent(l,"CMGetImageSpace",CM_UNSUPPORTED_DATA_TYPE,out(1,4));
		absent(l,"CMCountImageProfiles",CM_UNSUPPORTED_DATA_TYPE,out(1,4));
		absent(l,"CMGetIndImageProfile",CM_UNSUPPORTED_DATA_TYPE,out(2,4));
		for(String n:new String[]{"CMEmbedImage","CMUnembedImage","CMMatchImage","CMProofImage","CMLinkImage","CMSetIndImageProfile"})absent(l,n,CM_UNSUPPORTED_DATA_TYPE);
	}
	// ================================================================== CarbonLib 1.6: ColorSync display calls and workflows

	static final int CM_METHOD_ERROR=-171,CM_METHOD_NOT_FOUND=-175,CM_PROFILE_NOT_FOUND=-176;
	/** 'appl': ColorSync's own CMM, the only one installed (ColorSync68k's CMGetPreferredCMM and CMIterateCMMInfo answer it). */
	private static final int CMM_APPLE=0x6170706c;
	/** sizeof(CMProfileLocation): the type word and the largest member (cmPathBasedProfile's 256-byte path), rounded up. */
	private static final int LOCATION_BYTES=260;
	/** The display whose profile is the system profile (CMGet/SetSystemProfileAVID); IDs are the Display Manager's (1 = the main screen). */
	private int systemProfileAvid=1;
	private int preferredCmmGlobal=CMM_APPLE;
	/** CMSetGammaByAVID's CMVideoCardGamma by display, as the program gave it (the emulated screen applies no gamma of its own). */
	private final Map<Integer,byte[]> gammaByAvid=new HashMap<Integer,byte[]>();

	/** A believable guest pointer, for an output of a private call whose signature is inferred: not low memory, even, inside RAM. */
	private boolean guestPointer(int p){return p>=0x10000&&(p&1)==0&&Integer.compareUnsigned(p,memory.size()-4)<0;}
	/** The Display Manager's answer for a display ID: noErr, or kDMDisplayNotFoundErr (-6222). */
	private int displayExists(int avid) throws M68kException {int gd=rt.allocate(4,4);try{return (short)rt.toolbox("DMGetGDeviceByDisplayID",avid,gd,0);}finally{memory.free(gd);}}
	/** An identity CMVideoCardGamma (cmVideoCardGammaTableType: 3 channels, 256 one-byte entries): what the emulated screen does. */
	private static byte[] identityGamma(){byte[] b=new byte[10+3*256];b[5]=3;b[6]=1;b[9]=1;for(int ch=0;ch<3;ch++)for(int i=0;i<256;i++)b[10+ch*256+i]=(byte)i;return b;}
	/** The byte size of the CMVideoCardGamma at {@code p} (tagType, then a table or a formula), or -1 when it is not a valid one. */
	private int videoCardGammaSize(int p) throws M68kCpuException {
		int tag=memory.readLong(p);
		if(tag==1)return 4+36; // cmVideoCardGammaFormulaType: nine Fixed
		if(tag!=0)return -1;   // cmVideoCardGammaTableType
		int channels=memory.readWord(p+4),count=memory.readWord(p+6),size=memory.readWord(p+8);
		if((channels!=1&&channels!=3)||count<1||(size!=1&&size!=2))return -1;
		return 10+channels*count*size;
	}

	private void defineColorSyncExtras(PpcLibraries l){
		// CMGetGammaByAVID(displayID, gamma, *size): gamma NULL asks for the size; a buffer too small gets the size and paramErr
		l.register(null,"CMGetGammaByAVID",(r,c)->{int avid=arg(0),gamma=arg(1),size=arg(2);
			if(size==0){ret(PARAM_ERR);return;}
			int e=displayExists(avid);if(e!=0){memory.writeLong(size,0);ret(e);return;}
			byte[] g=gammaByAvid.get(avid);if(g==null)g=identityGamma();
			if(gamma==0){memory.writeLong(size,g.length);ret(NO_ERR);return;}
			int room=memory.readLong(size);memory.writeLong(size,g.length);
			if(Integer.compareUnsigned(room,g.length)<0){ret(PARAM_ERR);return;}
			memory.load(gamma,g);ret(NO_ERR);});
		l.register(null,"CMSetGammaByAVID",(r,c)->{int avid=arg(0),gamma=arg(1); // kept per display and read back; the screen itself stays linear
			int e=displayExists(avid);if(e!=0){ret(e);return;}
			int n=gamma==0?-1:videoCardGammaSize(gamma);if(n<0){ret(PARAM_ERR);return;}
			gammaByAvid.put(avid,memory.readBytes(gamma,n));ret(NO_ERR);});
		// private: CMGetSystemProfileAVID(CMDisplayIDType *) / CMSetSystemProfileAVID(CMDisplayIDType) - which display the system profile follows
		l.register(null,"CMGetSystemProfileAVID",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),systemProfileAvid);ret(NO_ERR);});
		l.register(null,"CMSetSystemProfileAVID",(r,c)->{int avid=arg(0);int e=displayExists(avid);if(e!=0){ret(e);return;}
			int prof=rt.allocate(4,4),loc=rt.allocate(LOCATION_BYTES,4),size=rt.allocate(4,4);
			try{
				memory.writeLong(prof,0);e=rt.toolbox("CMGetProfileByAVID",avid,prof);if(e!=0){ret(e);return;}
				memory.writeLong(size,LOCATION_BYTES);e=rt.toolbox("NCMGetProfileLocation",memory.readLong(prof),loc,size);
				if(e==0)e=rt.toolbox("NCMSetSystemProfile",loc); // that display's profile becomes the system profile
				rt.toolbox("CMCloseProfile",memory.readLong(prof));
				if(e==0)systemProfileAvid=avid;ret(e);
			}finally{memory.free(prof);memory.free(loc);memory.free(size);}});
		l.register(null,"CMGetSystemProfilePriv",(r,c)->ret(rt.toolbox("CMGetSystemProfile",arg(0)))); // private (CMProfileRef *): the system profile, as CMGetSystemProfile
		// private: CMGetPreferredCMMGlobal(OSType *) / CMSetPreferredCMMGlobal(OSType): the system-wide CMM choice; only Apple's CMM is installed
		l.register(null,"CMGetPreferredCMMGlobal",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),preferredCmmGlobal);ret(NO_ERR);});
		l.register(null,"CMSetPreferredCMMGlobal",(r,c)->{int cmm=arg(0);if(cmm!=0&&cmm!=CMM_APPLE){ret(CM_METHOD_NOT_FOUND);return;}preferredCmmGlobal=CMM_APPLE;ret(NO_ERR);}); // 0 = the default CMM
		l.register(null,"CMNewDisplayProfile",(r,c)->ret(UNIMP_ERR)); // private, signature unknown: making a display profile from the monitor's EDID needs a calibrator this system lacks; nothing written
		// ColorSync workflows (the ColorSync 3.x control panel's private workflow database, signatures inferred): this system has an
		// empty database - initialising, saving and reverting it succeed, every lookup is cmProfileNotFound, nothing is active
		for(String n:new String[]{"CMInitWorkflows","CMTermWorkflows","CMSaveWorkflows","CMRevertWorkflows"})l.register(null,n,(r,c)->ret(NO_ERR));
		l.register(null,"CMCountWorkflows",(r,c)->{if(guestPointer(arg(0)))memory.writeLong(arg(0),0);ret(NO_ERR);}); // (UInt32 *count): 0
		l.register(null,"CMGetIndWorkflow",(r,c)->{if(guestPointer(arg(1)))memory.writeLong(arg(1),0);ret(CM_PROFILE_NOT_FOUND);}); // (index, *workflow): none
		for(String n:new String[]{"CMActivateCurrentWorkflow","CMActivateWorkflow","CMDeleteWorkflow","CMGetWorkflowDates","CMGetWorkflowDesc","CMGetWorkflowEntry",
				"CMGetWorkflowName","CMSetWorkflowDesc","CMSetWorkflowEntry","CMSetWorkflowName"})l.register(null,n,(r,c)->ret(CM_PROFILE_NOT_FOUND));
		for(String n:new String[]{"CMCopyWorkflow","CMExportWorkflow","CMImportWorkflow"})l.register(null,n,(r,c)->ret(CM_METHOD_ERROR)); // no workflow file format to read or write
		l.register(null,"CMWorkflowIsActive",(r,c)->retB(false));l.register(null,"CMWorkflowIsModified",(r,c)->retB(false));
	}

	// ================================================================== CarbonLib 1.6: Image Capture (ICAApplication.h, ICADevice.h)

	static final int ICA_COMMUNICATION_ERR=-9900,ICA_DEVICE_NOT_FOUND=-9901,ICA_INVALID_OBJECT=-9905,ICA_INVALID_PROPERTY=-9906,ICA_INDEX_OUT_OF_RANGE=-9907,ICA_PROPERTY_TYPE_NOT_FOUND=-9908;
	static final int ICA_LIST=0x6f626a6c,ICA_DEVICE=0x69636476,ICA_FILE=0x66696c65; // kICAList 'objl', kICADevice 'icdv', kICAFile 'file'
	/** An Image Capture object: the device list (the root) or one a device library made with ICDNewObject. */
	private static final class IcaObject {
		final int ref,type,subtype;final IcaObject parent;int refCon;final List<IcaObject> children=new ArrayList<IcaObject>();final List<IcaProperty> properties=new ArrayList<IcaProperty>();
		IcaObject(int ref,int type,int subtype,IcaObject parent){this.ref=ref;this.type=type;this.subtype=subtype;this.parent=parent;}
		IcaObject root(){IcaObject o=this;while(o.parent!=null)o=o.parent;return o;}
	}
	/** A property of an object: its ICAPropertyInfo and data. */
	private static final class IcaProperty {
		final int ref,type,dataType,flags;final IcaObject owner;byte[] data;int refCon;
		IcaProperty(int ref,IcaObject owner,int type,int dataType,int flags,byte[] data){this.ref=ref;this.owner=owner;this.type=type;this.dataType=dataType;this.flags=flags;this.data=data;}
	}
	private final Map<Integer,IcaObject> icaObjects=new HashMap<Integer,IcaObject>();
	private final Map<Integer,IcaProperty> icaProperties=new HashMap<Integer,IcaProperty>();
	/** ICARegisterEventNotification's registrations by object: {notifyType, notifyProc}. */
	private final Map<Integer,List<int[]>> icaNotifications=new HashMap<Integer,List<int[]>>();
	private IcaObject icaDeviceList;
	/** The device list: no Image Capture device (camera, scanner) is attached to this system, so it starts with no children. */
	private IcaObject deviceList() throws M68kCpuException {if(icaDeviceList==null)icaDeviceList=newIcaObject(ICA_LIST,ICA_DEVICE,null);return icaDeviceList;}
	private IcaObject newIcaObject(int type,int subtype,IcaObject parent) throws M68kCpuException {
		int ref=rt.allocate(8,4);memory.writeLong(ref,type);memory.writeLong(ref+4,subtype);
		IcaObject o=new IcaObject(ref,type,subtype,parent);icaObjects.put(ref,o);if(parent!=null)parent.children.add(o);return o;
	}
	private void disposeIca(IcaObject o){
		for(IcaObject k:new ArrayList<IcaObject>(o.children))disposeIca(k);
		for(IcaProperty p:o.properties){icaProperties.remove(p.ref);memory.free(p.ref);}o.properties.clear();
		if(o.parent!=null)o.parent.children.remove(o);icaObjects.remove(o.ref);icaNotifications.remove(o.ref);memory.free(o.ref);
	}
	private IcaObject icaObject(int ref){return icaObjects.get(ref);}
	private void writeObjectInfo(int p,IcaObject o) throws M68kCpuException {memory.writeLong(p,o==null?0:o.type);memory.writeLong(p+4,o==null?0:o.subtype);}
	private void writePropertyInfo(int p,IcaProperty q) throws M68kCpuException {
		if(q==null){memory.fill(p,16,0);return;}memory.writeLong(p,q.type);memory.writeLong(p+4,q.dataType);memory.writeLong(p+8,q.data.length);memory.writeLong(p+12,q.flags);
	}
	private interface IcaBody { int run(int pb) throws M68kException; }
	/**
	 * An Image Capture call on its parameter block (mac68k alignment: ICAHeader {OSErr err; UInt32 refcon} takes 6 bytes): the
	 * result goes to header.err and is returned; a completion proc (NULL = synchronous) is called with the block once done.
	 */
	private void ica(PpcLibraries l,String name,IcaBody body){
		l.register(null,name,(r,c)->{int pb=arg(0),completion=arg(1);if(pb==0){ret(PARAM_ERR);return;}
			int e=body.run(pb);memory.writeWord(pb,e);
			if(completion!=0){if(r.isRoutineDescriptor(completion))r.callUniversal(completion,0xc1,new int[]{pb});else r.cpu.call(completion,pb);}
			ret(e);});
	}
	/** An ICAObjectInfo-returning lookup: the object at pb+6, the answer's reference at pb+10 and its info at pb+14. */
	private interface IcaPick { IcaObject pick(IcaObject o); }
	private void icaRelative(PpcLibraries l,String name,boolean ofProperty,IcaPick pick){
		ica(l,name,pb->{IcaObject o;
			if(ofProperty){IcaProperty q=icaProperties.get(memory.readLong(pb+6));o=q==null?null:q.owner;if(q==null){memory.writeLong(pb+10,0);writeObjectInfo(pb+14,null);return ICA_INVALID_PROPERTY;}}
			else{o=icaObject(memory.readLong(pb+6));if(o==null){memory.writeLong(pb+10,0);writeObjectInfo(pb+14,null);return ICA_INVALID_OBJECT;}}
			IcaObject t=pick.pick(o);memory.writeLong(pb+10,t.ref);writeObjectInfo(pb+14,t);return NO_ERR;});
	}

	private void defineImageCapture(PpcLibraries l){
		ica(l,"ICAGetDeviceList",pb->{memory.writeLong(pb+6,deviceList().ref);return NO_ERR;}); // {header, object -->}
		ica(l,"ICAGetChildCount",pb->{IcaObject o=icaObject(memory.readLong(pb+6));memory.writeLong(pb+10,o==null?0:o.children.size());return o==null?ICA_INVALID_OBJECT:NO_ERR;});
		ica(l,"ICAGetNthChild",pb->{IcaObject o=icaObject(memory.readLong(pb+6));int i=memory.readLong(pb+10); // {header, parent, index (0-based), child -->, childInfo -->}
			IcaObject k=o==null||i<0||i>=o.children.size()?null:o.children.get(i);
			memory.writeLong(pb+14,k==null?0:k.ref);writeObjectInfo(pb+18,k);return o==null?ICA_INVALID_OBJECT:k==null?ICA_INDEX_OUT_OF_RANGE:NO_ERR;});
		ica(l,"ICAGetObjectInfo",pb->{IcaObject o=icaObject(memory.readLong(pb+6));writeObjectInfo(pb+10,o);return o==null?ICA_INVALID_OBJECT:NO_ERR;});
		icaRelative(l,"ICAGetParentOfObject",false,o->o.parent==null?o:o.parent); // the device list is its own parent
		icaRelative(l,"ICAGetRootOfObject",false,IcaObject::root);
		icaRelative(l,"ICAGetParentOfProperty",true,o->o);
		icaRelative(l,"ICAGetRootOfProperty",true,IcaObject::root);
		ica(l,"ICAGetObjectRefCon",pb->{IcaObject o=icaObject(memory.readLong(pb+6));memory.writeLong(pb+10,o==null?0:o.refCon);return o==null?ICA_INVALID_OBJECT:NO_ERR;});
		ica(l,"ICASetObjectRefCon",pb->{IcaObject o=icaObject(memory.readLong(pb+6));if(o==null)return ICA_INVALID_OBJECT;o.refCon=memory.readLong(pb+10);return NO_ERR;});
		ica(l,"ICAGetPropertyCount",pb->{IcaObject o=icaObject(memory.readLong(pb+6));memory.writeLong(pb+10,o==null?0:o.properties.size());return o==null?ICA_INVALID_OBJECT:NO_ERR;});
		ica(l,"ICAGetNthProperty",pb->{IcaObject o=icaObject(memory.readLong(pb+6));int i=memory.readLong(pb+10); // {header, object, index, property -->, propertyInfo -->}
			IcaProperty q=o==null||i<0||i>=o.properties.size()?null:o.properties.get(i);
			memory.writeLong(pb+14,q==null?0:q.ref);writePropertyInfo(pb+18,q);return o==null?ICA_INVALID_OBJECT:q==null?ICA_INDEX_OUT_OF_RANGE:NO_ERR;});
		ica(l,"ICAGetPropertyByType",pb->{IcaObject o=icaObject(memory.readLong(pb+6));int type=memory.readLong(pb+10);IcaProperty q=null; // {header, object, propertyType, property -->, propertyInfo -->}
			if(o!=null)for(IcaProperty p:o.properties)if(p.type==type){q=p;break;}
			memory.writeLong(pb+14,q==null?0:q.ref);writePropertyInfo(pb+18,q);return o==null?ICA_INVALID_OBJECT:q==null?ICA_PROPERTY_TYPE_NOT_FOUND:NO_ERR;});
		ica(l,"ICAGetPropertyInfo",pb->{IcaProperty q=icaProperties.get(memory.readLong(pb+6));writePropertyInfo(pb+10,q);return q==null?ICA_INVALID_PROPERTY:NO_ERR;});
		ica(l,"ICAGetPropertyData",pb->{IcaProperty q=icaProperties.get(memory.readLong(pb+6)); // {header, property, startByte, requestedSize, dataPtr, actualSize -->, dataType -->}
			int start=memory.readLong(pb+10),want=memory.readLong(pb+14),dst=memory.readLong(pb+18);
			memory.writeLong(pb+22,0);memory.writeLong(pb+26,q==null?0:q.dataType);
			if(q==null)return ICA_INVALID_PROPERTY;if(dst==0)return PARAM_ERR;
			if(start<0||start>q.data.length)return ICA_INDEX_OUT_OF_RANGE;
			int n=want==-1?q.data.length-start:Math.min(Math.max(0,want),q.data.length-start); // kICAEntireLength
			memory.load(dst,q.data,start,n);memory.writeLong(pb+22,n);return NO_ERR;});
		ica(l,"ICASetPropertyData",pb->{IcaProperty q=icaProperties.get(memory.readLong(pb+6)); // {header, property, startByte, dataPtr, dataSize, dataType}
			if(q==null)return ICA_INVALID_PROPERTY;int start=memory.readLong(pb+10),src=memory.readLong(pb+14),n=memory.readLong(pb+18);
			if(src==0&&n>0||n<0||start<0||start>q.data.length)return PARAM_ERR;
			byte[] d=java.util.Arrays.copyOf(q.data,Math.max(q.data.length,start+n));if(n>0)System.arraycopy(memory.readBytes(src,n),0,d,start,n);q.data=d;return NO_ERR;});
		ica(l,"ICAGetPropertyRefCon",pb->{IcaProperty q=icaProperties.get(memory.readLong(pb+6));memory.writeLong(pb+10,q==null?0:q.refCon);return q==null?ICA_INVALID_PROPERTY:NO_ERR;});
		ica(l,"ICASetPropertyRefCon",pb->{IcaProperty q=icaProperties.get(memory.readLong(pb+6));if(q==null)return ICA_INVALID_PROPERTY;q.refCon=memory.readLong(pb+10);return NO_ERR;});
		// {header, object, ICAMessage (20 bytes), result -->}: messages go to the device's library, and no device library is loaded here
		ica(l,"ICAObjectSendMessage",pb->{IcaObject o=icaObject(memory.readLong(pb+6));memory.writeLong(pb+30,0);return o==null?ICA_INVALID_OBJECT:ICA_DEVICE_NOT_FOUND;});
		// {header, object, notifyType, notifyProc}: kept (devices added or removed would be reported; none come and go here)
		ica(l,"ICARegisterEventNotification",pb->{IcaObject o=icaObject(memory.readLong(pb+6));if(o==null)return ICA_INVALID_OBJECT;
			List<int[]> list=icaNotifications.computeIfAbsent(o.ref,k->new ArrayList<int[]>());int type=memory.readLong(pb+10),proc=memory.readLong(pb+14);
			list.removeIf(e->e[0]==type);if(proc!=0)list.add(new int[]{type,proc});return NO_ERR;});
		// ICACopyObjectPropertyDictionary {header, object, CFDictionaryRef * -->}: the object's properties by their four-character types
		ica(l,"ICACopyObjectPropertyDictionary",pb->{IcaObject o=icaObject(memory.readLong(pb+6));int out=memory.readLong(pb+10);
			if(out!=0)memory.writeLong(out,0);if(o==null)return ICA_INVALID_OBJECT;if(out==0)return PARAM_ERR;
			memory.writeLong(out,propertyDictionary(o));return NO_ERR;});
		// ICADownloadFile {header, object, FSRef *dir, flags, fileType, fileCreator, rotationAngle, FSRef *file -->}: the file's bytes would come from its device
		ica(l,"ICADownloadFile",pb->{IcaObject o=icaObject(memory.readLong(pb+6));int file=memory.readLong(pb+30);if(file!=0)memory.fill(file,80,0);
			if(o==null)return ICA_INVALID_OBJECT;if(o.type!=ICA_FILE||memory.readLong(pb+10)==0)return PARAM_ERR;return ICA_COMMUNICATION_ERR;});
		// device-library side: objects and properties a device library builds (under the device list, a device; under a device, its files)
		ica(l,"ICDNewObject",pb->{IcaObject parent=icaObject(memory.readLong(pb+6));memory.writeLong(pb+18,0); // {header, parentObject, objectInfo, object -->}
			if(parent==null)return ICA_INVALID_OBJECT;IcaObject o=newIcaObject(memory.readLong(pb+10),memory.readLong(pb+14),parent);memory.writeLong(pb+18,o.ref);return NO_ERR;});
		ica(l,"ICDDisposeObject",pb->{IcaObject o=icaObject(memory.readLong(pb+6));if(o==null||o==icaDeviceList)return ICA_INVALID_OBJECT;disposeIca(o);return NO_ERR;});
		ica(l,"ICDNewProperty",pb->{IcaObject o=icaObject(memory.readLong(pb+6));memory.writeLong(pb+26,0); // {header, object, propertyInfo, property -->}: dataSize zero bytes to start with
			if(o==null)return ICA_INVALID_OBJECT;int size=memory.readLong(pb+18);if(size<0||size>1<<24)return PARAM_ERR;
			int ref=rt.allocate(4,4);memory.writeLong(ref,memory.readLong(pb+10));
			IcaProperty q=new IcaProperty(ref,o,memory.readLong(pb+10),memory.readLong(pb+14),memory.readLong(pb+22),new byte[size]);
			o.properties.add(q);icaProperties.put(ref,q);memory.writeLong(pb+26,ref);return NO_ERR;});
		ica(l,"ICDDisposeProperty",pb->{IcaProperty q=icaProperties.remove(memory.readLong(pb+6));if(q==null)return ICA_INVALID_PROPERTY;q.owner.properties.remove(q);memory.free(q.ref);return NO_ERR;});
	}
	/** A new CFDictionary of an object's properties: numbers for the integer, Boolean and float types, strings for 'TEXT', data otherwise. */
	private int propertyDictionary(IcaObject o) throws M68kException {
		PpcCoreFoundation cf=rt.libraries.coreFoundation;int dict=cf.newTypeDictionary(),tmp=rt.allocate(8,4);
		try{
			for(IcaProperty q:o.properties){
				byte[] d=q.data;int v;String t=ResourceFile68k.typeName(q.dataType);
				if(t.equals("TEXT")){int n=0;while(n<d.length&&d[n]!=0)n++;v=cf.newString(rt.toolbox.encoding.decode(d,0,n));}
				else{int type=0;memory.fill(tmp,8,0);int n=Math.min(8,d.length);memory.load(tmp,d,0,n);
					switch(t){case "ui16":case "si16":if(d.length>=2)type=2;break;case "si32":if(d.length>=4)type=3;break;case "ui32":if(d.length>=4){memory.writeLong(tmp+4,memory.readLong(tmp));memory.writeLong(tmp,0);type=4;}break;
						case "ui64":case "si64":if(d.length>=8)type=4;break;case "sing":if(d.length>=4)type=5;break;case "bool":if(d.length>=1)type=7;break;default:break;}
					if(t.equals("ui16")&&type!=0){memory.writeLong(tmp,memory.readWord(tmp));type=3;} // unsigned: widened
					v=type!=0?cf.newNumber(type,tmp):cf.newData(d,false);}
				cf.put(dict,ResourceFile68k.typeName(q.type),v);cf.release(v);
			}
		}finally{memory.free(tmp);}
		return dict;
	}

	// ================================================================== CarbonLib 1.6: Folder Manager, aliases and resource files

	/** A guest FSSpec (80 bytes, to free) for the file an FSRef stands for; 0 when it is not an FSRef made here. */
	private int specFromRef(int ref) throws M68kException {java.io.File f=rt.toolbox.files.fsRefFile(ref);if(f==null)return 0;int spec=rt.allocate(80,2);rt.toolbox.files.fileManager.writeFSSpec(spec,f);return spec;}
	private void refFromSpec(int spec,int ref) throws M68kException {try{java.io.File f=rt.toolbox.files.fileForSpec(spec);if(f!=null)rt.toolbox.files.writeFSRef(ref,f);}catch(java.io.IOException ignored){}}
	private java.io.File specFile(int spec){try{return spec==0?null:rt.toolbox.files.fileForSpec(spec);}catch(java.io.IOException e){return null;}}
	/** FindFolder's folder written as an FSRef at {@code found} (cleared on an error). */
	private int findFolderRef(int vol,int type,int create,int found) throws M68kException {
		if(found==0)return PARAM_ERR;
		int v=rt.allocate(4,4),d=rt.allocate(4,4);
		try{int e=(short)rt.toolbox("FindFolder",vol,type,create,v,d);
			java.io.File f=e!=0?null:rt.toolbox.files.fileFor((short)memory.readWord(v),memory.readLong(d),null);
			if(e==0&&f==null)e=FNF_ERR;
			if(e==0)rt.toolbox.files.writeFSRef(found,f);else memory.fill(found,80,0);
			return e;
		}catch(java.io.IOException x){memory.fill(found,80,0);return FNF_ERR;}finally{memory.free(v);memory.free(d);}
	}
	/** RequestVolumeNotification's registrations (callback, refCon). */
	private final List<int[]> volumeNotifications=new ArrayList<int[]>();
	static final int RES_ERR=0xa60,NIL_HANDLE_ERR=-109,WR_PERM_ERR=-61,IO_ERR=-36;

	private void defineFolderAndResourceFiles(PpcLibraries l){
		// FindFolderExtended(vol, type, create, flags, data, *vRefNum, *dirID) and the private variants with its flags (Folders.h): the 68K
		// Folder Manager has no $2C selector (the bridge stopped the program with "AliasDispatch selector 2860"), and the flags change
		// nothing here - no aliased special folders to leave unresolved, no per-user folders - so they are FindFolder
		for(String n:new String[]{"FindFolderExtended","FindFolderInternalExtended","FindFolderNewInstallerEntryExtended"})
			l.register(null,n,(r,c)->ret((short)rt.toolbox("FindFolder",arg(0),arg(1),arg(2)&0xff,arg(5),arg(6))));
		// FSFindFolderExtended(vol, type, create, flags, data, *foundRef), and FSFindFolder(vol, type, create, *foundRef) with it: PpcCarbon's
		// FSFindFolder handed the FSRef to FindFolder as *foundVRefNum and a stray register as *foundDirID
		l.register(null,"FSFindFolderExtended",(r,c)->ret(findFolderRef(arg(0),arg(1),arg(2)&0xff,arg(5))));
		l.register(null,"FSFindFolder",(r,c)->ret(findFolderRef(arg(0),arg(1),arg(2)&0xff,arg(3))));
		l.register(null,"InitializeFolderManager",(r,c)->ret(NO_ERR)); // private: the Folder Manager here is ready from the start
		l.register(null,"FolderManagerRemoveAllEntriesForProcess",(r,c)->ret(NO_ERR)); // private: no per-process folder routings or descriptors are kept here
		// FSFollowFinderAlias(fromFile, alias, logon, *target, *wasChanged) / FSUpdateAlias(fromFile, target, alias, *wasChanged): the FSSpec calls
		l.register(null,"FSFollowFinderAlias",(r,c)->{int fromRef=arg(0),alias=arg(1),target=arg(3);if(alias==0||target==0){ret(PARAM_ERR);return;}
			int from=fromRef==0?0:specFromRef(fromRef);if(fromRef!=0&&from==0){ret(FNF_ERR);return;}
			int spec=rt.allocate(80,2);
			try{int e=(short)rt.toolbox("FollowFinderAlias",from,alias,arg(2)&0xff,spec,arg(4));if(e==0)refFromSpec(spec,target);ret(e);}
			finally{memory.free(spec);if(from!=0)memory.free(from);}});
		l.register(null,"FSUpdateAlias",(r,c)->{int fromRef=arg(0),alias=arg(2);if(alias==0||arg(1)==0){ret(PARAM_ERR);return;}
			int from=fromRef==0?0:specFromRef(fromRef),target=specFromRef(arg(1));
			try{if(target==0||fromRef!=0&&from==0){ret(FNF_ERR);return;}ret((short)rt.toolbox("UpdateAlias",from,target,alias,arg(3)));}
			finally{if(target!=0)memory.free(target);if(from!=0)memory.free(from);}});
		// FSResourceFileAlreadyOpen(ref, *inChain, *refNum): Boolean - open (searched or detached), and its refNum
		l.register(null,"FSResourceFileAlreadyOpen",(r,c)->{java.io.File f=rt.toolbox.files.fsRefFile(arg(0));int ref=rt.toolbox.resources.refNumOf(f);
			boolean inChain=ref>=0&&rt.toolbox.resources.chainFromTop().contains(ref);
			if(arg(1)!=0)memory.writeByte(arg(1),inChain?1:0);if(arg(2)!=0)memory.writeWord(arg(2),ref<0?0:ref);retB(ref>=0);});
		// private (Mac OS X's data-fork resource files): FSpOpenDataForkAsResourceFile(spec, permission, *refNum) and
		// FSpCreateDataForkAsResourceFile(spec, creator, fileType, scriptTag): OSErr results, ResErr set as the Resource Manager does
		l.register(null,"FSpOpenDataForkAsResourceFile",(r,c)->{java.io.File f=specFile(arg(0));
			int ref=f==null?-1:rt.toolbox.resources.openDataForkFile(f,arg(1)&0xff);
			if(f==null)memory.writeWord(RES_ERR,FNF_ERR);
			if(arg(2)!=0)memory.writeWord(arg(2),ref<0?-1:ref);ret(ref<0?(short)memory.readWord(RES_ERR):NO_ERR);});
		l.register(null,"FSpCreateDataForkAsResourceFile",(r,c)->{int spec=arg(0);if(spec==0){ret(PARAM_ERR);return;}
			int e=(short)rt.toolbox("FSpCreate",spec,arg(1),arg(2),arg(3)); // dupFNErr for an existing file, as FSpCreateResFile's
			if(e==0){java.io.File f=specFile(spec);
				if(f==null)e=FNF_ERR;
				else if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,rt.toolbox.name))e=WR_PERM_ERR;
				else try{ResourceFile68k.openDataFork(f).write();}catch(java.io.IOException x){e=IO_ERR;}} // an empty resource map in the data fork
			memory.writeWord(RES_ERR,e);ret(e);});
		// private (Resources.h's override maps, inferred as (refNum)): an override file's resources come before the others - above every
		// map for InsertOverrideFile, just above the System file for InsertSystemOverrideFile (kRsrcChainAboveAllMaps / kRsrcChainBelowApplicationMap)
		l.register(null,"InsertOverrideFile",(r,c)->ret(rt.toolbox.resources.insertResourceFile((short)arg(0),4)));
		l.register(null,"InsertSystemOverrideFile",(r,c)->ret(rt.toolbox.resources.insertResourceFile((short)arg(0),1)));
		// private: RequestVolumeNotification(callback, refCon) / DeclineVolumeNotification(callback, ...): registrations kept; this system's
		// volumes are mounted before the program starts, so nothing is ever reported
		l.register(null,"RequestVolumeNotification",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}volumeNotifications.add(new int[]{arg(0),arg(1)});ret(NO_ERR);});
		l.register(null,"DeclineVolumeNotification",(r,c)->{int cb=arg(0);ret(volumeNotifications.removeIf(e->e[0]==cb)?NO_ERR:PARAM_ERR);});
		// private, inferred as (Handle) / (Handle, Boolean): a Handle's lock bit (HGetState $A069, HLock $A029, HUnlock $A02A)
		l.register(null,"GetLockedBitState",(r,c)->{int h=arg(0);retB(h!=0&&rt.toolbox.api.isHandle(h)&&(rt.trap(0xa069,0,h,0)&0x80)!=0);});
		l.register(null,"SetLockedBitState",(r,c)->{int h=arg(0);if(h==0||!rt.toolbox.api.isHandle(h)){ret(NIL_HANDLE_ERR);return;}rt.trap((arg(1)&0xff)!=0?0xa029:0xa02a,0,h,0);ret(NO_ERR);});
		// MakeExecutable / FlushToExecute(address, length): PpcCpu decodes every instruction from memory (no instruction cache, no
		// translated code to drop), so freshly written code runs as it is - as MakeDataExecutable
		l.register(null,"MakeExecutable",(r,c)->ret(NO_ERR));l.register(null,"FlushToExecute",(r,c)->ret(NO_ERR));
	}

	// ================================================================== CarbonLib 1.6: odds and ends

	private static final int SCRAP_REF=0x53435250; // PpcCarbon's one ScrapRef (the desk scrap)
	static final int NO_SCRAP_ERR=-100,SI_UNKNOWN_INFO_TYPE=-231,SM_SYS_SCRIPT=18;
	/** SoundManagerSetInfo's values by selector, read back by SoundManagerGetInfo. */
	private final Map<Integer,byte[]> soundManagerInfo=new HashMap<Integer,byte[]>();
	/** The bytes of SoundManagerGetInfo/SetInfo's known selectors: 'volu' 'hvol' 'srat' a long, 'smut' 'hmut' 'ssiz' 'chan' a word, 'hwfm' a SoundComponentData. */
	private static int soundInfoSize(int selector){
		switch(selector){case 0x766f6c75:case 0x68766f6c:case 0x73726174:return 4;case 0x736d7574:case 0x686d7574:case 0x7373697a:case 0x6368616e:return 2;case 0x6877666d:return 28;default:return 0;}
	}
	/** A 'STR ' of the System file (or a program's) as text, or null. */
	private String stringResource(int id) throws M68kException {
		int h=rt.toolbox("GetString",id);int p=h==0?0:memory.readLong(h);if(p==0)return null;
		int n=memory.readByte(p);return n==0?null:rt.toolbox.encoding.decode(memory.readBytes(p+1,n),0,n);
	}

	private void defineCarbon16Misc(PpcLibraries l){
		// Collections: retain counts are the Collection Manager's owner counts (CloneCollection / DisposeCollection)
		l.register(null,"RetainCollection",(r,c)->{if(rt.toolbox.managers.collections.collection(arg(0))==null){ret(PARAM_ERR);return;}rt.toolbox("CloneCollection",arg(0));ret(NO_ERR);});
		l.register(null,"ReleaseCollection",(r,c)->{if(rt.toolbox.managers.collections.collection(arg(0))==null){ret(PARAM_ERR);return;}rt.toolbox("DisposeCollection",arg(0));ret(NO_ERR);});
		l.register(null,"GetCollectionRetainCount",(r,c)->ret(rt.toolbox.managers.collections.collection(arg(0))==null?0:rt.toolbox("CountCollectionOwners",arg(0))));
		l.register(null,"GetThreadScheduler",(r,c)->ret(rt.toolbox.threads.scheduler()));
		l.register(null,"GetGrowZone",(r,c)->ret(rt.toolbox.growZoneProc()));
		// GetComponentPublicIndString(component, Str255, strListID, index): the component's string list, as GetComponentIndString finds it
		l.register(null,"GetComponentPublicIndString",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),0);ret((short)rt.toolbox("GetComponentIndString",arg(0),arg(1),arg(2),arg(3)));});
		// GetScrapByName(CFStringRef name, OptionBits, *scrap): kScrapClipboardScrap is the desk scrap (kScrapClearNamedScrap = 1 empties it);
		// Mac OS 9 keeps no other named scrap (the Find scrap is Mac OS X's), so other names are noScrapErr
		l.register(null,"GetScrapByName",(r,c)->{int out=arg(2);String name=arg(0)==0?null:rt.libraries.coreFoundation.text(arg(0));
			if(out!=0)memory.writeLong(out,0);if(out==0||name==null){ret(PARAM_ERR);return;}
			if(!name.equals("com.apple.scrap.clipboard")){ret(NO_SCRAP_ERR);return;}
			if((arg(1)&1)!=0){int e=rt.toolbox("ZeroScrap");if(e!=0){ret(e);return;}}
			memory.writeLong(out,SCRAP_REF);ret(NO_ERR);});
		// CSCopyMachineName(): the computer's name ('STR ' -16413, the Sharing Setup name) - the owner name ('STR ' -16396) when the System has none
		l.register(null,"CSCopyMachineName",(r,c)->{String name=stringResource(-16413);if(name==null)name=stringResource(-16396);if(name==null)name="Macintosh";
			ret(rt.libraries.coreFoundation.newString(name));});
		// SoundManagerGetInfo / SoundManagerSetInfo(selector, infoPtr): the output's settings (GetSoundOutputInfo's answers) and what was set
		l.register(null,"SoundManagerGetInfo",(r,c)->{int sel=arg(0),info=arg(1);if(info==0){ret(PARAM_ERR);return;}
			byte[] v=soundManagerInfo.get(sel);if(v!=null){memory.load(info,v);ret(NO_ERR);return;}
			if(soundInfoSize(sel)==0){ret(SI_UNKNOWN_INFO_TYPE);return;}
			ret((short)rt.toolbox("GetSoundOutputInfo",0,sel,info));});
		l.register(null,"SoundManagerSetInfo",(r,c)->{int sel=arg(0),info=arg(1),n=soundInfoSize(sel);if(n==0){ret(SI_UNKNOWN_INFO_TYPE);return;}if(info==0){ret(PARAM_ERR);return;}
			soundManagerInfo.put(sel,memory.readBytes(info,n));ret(NO_ERR);});
		l.register(null,"GetApplicationScript",(r,c)->ret((short)rt.toolbox("GetScriptManagerVariable",SM_SYS_SCRIPT))); // the program runs in the system script
		// MinimumProcessorSpeed(): a desktop Mac does not slow its processor down, so the lowest speed is the speed (GetCPUSpeed, IdleState -1)
		l.register(null,"MinimumProcessorSpeed",(r,c)->ret((short)rt.trap(0xa485,-1,0,0)));
		// private CarbonLib debugging aids: no Carbon event log can be opened here; animation timing is not recorded
		l.register(null,"OpenEventLog",(r,c)->ret(UNIMP_ERR));l.register(null,"CloseEventLog",(r,c)->ret(UNIMP_ERR));
		l.register(null,"RecordAnimationTimeout",(r,c)->ret(NO_ERR));
		// Dictionary Manager, private variants: an empty dictionary iterator; no found-record iterator can exist (DCMFindRecords
		// answers dcmNotOpenErr), and the fast form's outputs are not known, so nothing is written
		l.register(null,"DCMCreateDictionaryIteratorInternal",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}int it=rt.allocate(4,4);emptyIterators.add(it);memory.writeLong(p,it);ret(NO_ERR);});
		l.register(null,"DCMIterateFoundRecordFast",(r,c)->ret(DCM_ITERATION_COMPLETE));
	}

	// ================================================================== Sound Converter (Sound.h: Sound Manager 3.2, FillBuffer 3.6)

	static final int BAD_FORMAT=-206,BAD_COMPONENT_INSTANCE=0x80008001;
	private static final int K_EXTENDED_SOUND_DATA=1<<14,K_NO_SAMPLE_RATE_CONVERSION=1<<1,K_DIDNT_FILL_BUFFER=1,K_HAS_LEFT_OVER_DATA=2,K_BUFFER_SIZE_VALID=2;
	/** One SoundConverterOpen: input and output formats, the resampler's position and last frame, MACE state, FillBuffer's left-over output. */
	private static final class Converter {
		int inFormat,inChannels,inSize;double inRate;int outFormat,outChannels,outSize;double outRate;boolean noRate;
		QuickTimeFile.SoundDescription in;MaceDecoder.Stream mace;
		double pos;short[] last;byte[] pending=new byte[0];
		int outFrameBytes(){return outChannels*outBytesPerSample(outFormat,outSize);}
	}
	private final Map<Integer,Converter> converters=new HashMap<Integer,Converter>();
	private static boolean pcmOutput(int format,int size){
		switch(ResourceFile68k.typeName(format)){case "NONE":case "raw ":case "twos":return size==8||size==16||size==24||size==32;case "sowt":return size==16||size==24||size==32;
			case "in24":case "in32":case "fl32":case "fl64":return true;default:return false;}
	}
	private static int outBytesPerSample(int format,int size){
		switch(ResourceFile68k.typeName(format)){case "in24":return 3;case "in32":case "fl32":return 4;case "fl64":return 8;default:return Math.max(1,size/8);}
	}
	/** The input description a buffer's format makes (null when it is not one the decoders read). */
	private static QuickTimeFile.SoundDescription inputDescription(int format,int channels,int size,double rate){
		QuickTimeFile.SoundDescription d=QuickTimeSound.description(ResourceFile68k.typeName(format==0?0x4e4f4e45:format),channels,size,rate);
		return QuickTimeSound.canDecode(d)?d:null;
	}
	private void setInput(Converter v,int format,int channels,int size,double rate){
		v.inFormat=format;v.inChannels=channels;v.inSize=size;v.inRate=rate;v.in=inputDescription(format,channels,size,rate);
		String t=ResourceFile68k.typeName(format);v.mace=t.equals("MAC3")||t.equals("MAC6")?new MaceDecoder.Stream(t.equals("MAC6"),channels):null;
	}
	/** {@code packets} (sample frames for uncompressed formats) of input bytes converted to output bytes. */
	private byte[] convert(Converter v,byte[] b,int packets) throws java.io.IOException {
		int spp=Math.max(1,v.in.samplesPerPacket);long frames=(long)packets*spp;short[] s;
		if(v.mace!=null){byte[] pcm=v.mace.decode(b,0,b.length);s=new short[pcm.length];for(int i=0;i<pcm.length;i++)s[i]=(short)(((pcm[i]&0xff)-128)<<8);frames=pcm.length/v.inChannels;}
		else s=QuickTimeSound.decode(v.in,b,frames);
		int n=(int)frames,ic=v.inChannels,oc=v.outChannels;
		short[][] f=new short[n][oc]; // channels mapped: stereo to mono averages, mono to stereo copies
		for(int i=0;i<n;i++)for(int k=0;k<oc;k++){
			if(ic==2&&oc==1)f[i][0]=(short)((s[i*2]+s[i*2+1])>>1);else f[i][k]=s[i*ic+Math.min(k,ic-1)];}
		List<short[]> out=new ArrayList<short[]>();
		if(v.noRate||v.inRate==v.outRate||n==0){for(short[] x:f)out.add(x);if(n>0)v.last=f[n-1];}
		else{ // linear interpolation; the position runs on across buffers, frame -1 being the previous buffer's last
			double step=v.inRate/v.outRate;
			while(v.pos<n-1){int i=(int)Math.floor(v.pos);double t=v.pos-i;short[] a=i<0?(v.last!=null?v.last:f[0]):f[i],z=f[i+1];short[] o=new short[oc];
				for(int k=0;k<oc;k++)o[k]=(short)Math.round(a[k]+(z[k]-a[k])*t);out.add(o);v.pos+=step;}
			v.pos-=n;v.last=f[n-1];}
		return encode(v,out);
	}
	private static byte[] encode(Converter v,List<short[]> frames){
		int bps=outBytesPerSample(v.outFormat,v.outSize);String t=ResourceFile68k.typeName(v.outFormat);
		byte[] b=new byte[frames.size()*v.outChannels*bps];int at=0;
		for(short[] fr:frames)for(short x:fr){
			switch(t){
				case "fl32":{int bits=Float.floatToRawIntBits(x/32768f);for(int i=3;i>=0;i--)b[at++]=(byte)(bits>>>(8*i));break;}
				case "fl64":{long bits=Double.doubleToRawLongBits(x/32768.0);for(int i=7;i>=0;i--)b[at++]=(byte)(bits>>>(8*i));break;}
				case "sowt":{b[at+bps-1]=(byte)(x>>8);b[at+bps-2]=(byte)x;at+=bps;break;} // little-endian, the low bytes beyond 16 bits zero
				default:{ // big-endian signed ('twos', 'in24', 'in32', 16-bit 'NONE'/'raw '); 8-bit 'NONE'/'raw ' is offset binary
					if(bps==1){b[at++]=(byte)((x>>8)+(t.equals("twos")?0:128));break;}
					b[at]=(byte)(x>>8);b[at+1]=(byte)x;at+=bps;break;}
			}
		}
		return b;
	}
	private Converter converter(int sc){return converters.get(sc);}
	private int writeOutput(byte[] out,int dst,int framesPtr,int bytesPtr,Converter v) throws M68kCpuException {
		if(out.length>0&&dst==0)return PARAM_ERR;if(out.length>0)memory.load(dst,out);
		if(framesPtr!=0)memory.writeLong(framesPtr,out.length/Math.max(1,v.outFrameBytes()));if(bytesPtr!=0)memory.writeLong(bytesPtr,out.length);return NO_ERR;
	}

	private void defineSoundConverter(PpcLibraries l){
		// SoundConverterOpen(*inputFormat, *outputFormat, *sc): SoundComponentData {flags, format, numChannels.w, sampleSize.w, sampleRate, sampleCount,
		// buffer, reserved}. Any format the Sound Manager plays decodes (PCM, ulaw/alaw, ima4, MACE); output is PCM (there are no encoders here).
		// These replace the bridge to the 68K Sound Manager, whose group-$18 stub answered noErr without making a converter.
		l.register(null,"SoundConverterOpen",(r,c)->{int in=arg(0),out=arg(1),scPtr=arg(2);if(scPtr!=0)memory.writeLong(scPtr,0);if(in==0||out==0||scPtr==0){ret(PARAM_ERR);return;}
			Converter v=new Converter();
			setInput(v,memory.readLong(in+4),(short)memory.readWord(in+8),(short)memory.readWord(in+10),Integer.toUnsignedLong(memory.readLong(in+12))/65536.0);
			v.outFormat=memory.readLong(out+4);if(v.outFormat==0)v.outFormat=0x4e4f4e45;v.outChannels=(short)memory.readWord(out+8);v.outSize=(short)memory.readWord(out+10);
			v.outRate=Integer.toUnsignedLong(memory.readLong(out+12))/65536.0;v.noRate=(memory.readLong(out)&K_NO_SAMPLE_RATE_CONVERSION)!=0;
			if(v.in==null||!pcmOutput(v.outFormat,v.outSize)){ret(BAD_FORMAT);return;}
			if(v.inChannels<1||v.inChannels>8||v.outChannels<1||v.outChannels>8||v.inRate<=0||v.outRate<=0){ret(PARAM_ERR);return;}
			int sc=rt.allocate(4,4);memory.writeLong(sc,0x73636f6e);converters.put(sc,v);memory.writeLong(scPtr,sc);ret(NO_ERR);}); // 'scon'
		l.register(null,"SoundConverterClose",(r,c)->{int sc=arg(0);if(converters.remove(sc)==null){ret(BAD_COMPONENT_INSTANCE);return;}memory.free(sc);ret(NO_ERR);});
		// SoundConverterGetBufferSizes(sc, inputBytesTarget, *inputFrames, *inputBytes, *outputBytes): frames are packets for packet formats
		l.register(null,"SoundConverterGetBufferSizes",(r,c)->{Converter v=converter(arg(0));if(v==null){ret(BAD_COMPONENT_INSTANCE);return;}
			int bpf=Math.max(1,v.in.bytesPerFrame),spp=Math.max(1,v.in.samplesPerPacket);long packets=Integer.toUnsignedLong(arg(1))/bpf;
			long outFrames=v.noRate?packets*spp:(long)Math.ceil(packets*spp*v.outRate/v.inRate)+1;
			if(arg(2)!=0)memory.writeLong(arg(2),(int)packets);if(arg(3)!=0)memory.writeLong(arg(3),(int)(packets*bpf));if(arg(4)!=0)memory.writeLong(arg(4),(int)Math.min(Integer.MAX_VALUE,outFrames*v.outFrameBytes()));
			ret(NO_ERR);});
		l.register(null,"SoundConverterBeginConversion",(r,c)->{Converter v=converter(arg(0));if(v==null){ret(BAD_COMPONENT_INSTANCE);return;}
			v.pos=0;v.last=null;v.pending=new byte[0];setInput(v,v.inFormat,v.inChannels,v.inSize,v.inRate);ret(NO_ERR);});
		// SoundConverterConvertBuffer(sc, inputPtr, inputFrames, outputPtr, *outputFrames, *outputBytes)
		l.register(null,"SoundConverterConvertBuffer",(r,c)->{Converter v=converter(arg(0));if(v==null){ret(BAD_COMPONENT_INSTANCE);return;}
			int packets=Math.max(0,arg(2));if(packets>0&&arg(1)==0){ret(PARAM_ERR);return;}
			try{byte[] out=convert(v,packets==0?new byte[0]:memory.readBytes(arg(1),packets*Math.max(1,v.in.bytesPerFrame)),packets);ret(writeOutput(out,arg(3),arg(4),arg(5),v));}
			catch(java.io.IOException e){ret(BAD_FORMAT);}});
		// SoundConverterEndConversion(sc, outputPtr, *outputFrames, *outputBytes): the resampler's held-back frames
		l.register(null,"SoundConverterEndConversion",(r,c)->{Converter v=converter(arg(0));if(v==null){ret(BAD_COMPONENT_INSTANCE);return;}
			List<short[]> tail=new ArrayList<short[]>();
			if(v.last!=null&&!v.noRate&&v.inRate!=v.outRate){double step=v.inRate/v.outRate;while(v.pos<0){tail.add(v.last);v.pos+=step;}}
			v.last=null;v.pos=0;ret(writeOutput(encode(v,tail),arg(1),arg(2),arg(3),v));});
		for(String n:new String[]{"SoundConverterGetInfo","SoundConverterSetInfo"})
			l.register(null,n,(r,c)->ret(converter(arg(0))==null?BAD_COMPONENT_INSTANCE:SI_UNKNOWN_INFO_TYPE)); // no converter settings (compression factors, codec options) here
		// SoundConverterFillBuffer(sc, fillBufferDataUPP, refCon, outputBuffer, outputBufferByteSize, *bytesWritten, *framesWritten, *outputFlags):
		// the program's proc hands buffers (a SoundComponentData, or an ExtendedSoundComponentData with kExtendedSoundData) until the
		// output is full or it returns false; whatever does not fit is kept for the next call (kSoundConverterHasLeftOverData)
		l.register(null,"SoundConverterFillBuffer",(r,c)->{Converter v=converter(arg(0));int upp=arg(1),refCon=arg(2),dst=arg(3),size=arg(4);
			for(int i=5;i<8;i++)if(arg(i)!=0)memory.writeLong(arg(i),0);
			if(v==null){ret(BAD_COMPONENT_INSTANCE);return;}if(upp==0||dst==0&&size>0){ret(PARAM_ERR);return;}
			int fb=v.outFrameBytes(),capacity=Math.max(0,size)/fb*fb,written=0,flags=0,empty=0;int slot=rt.allocate(4,4);
			try{
				while(true){
					if(v.pending.length>0){int n=Math.min(v.pending.length,capacity-written);memory.load(dst+written,v.pending,0,n);v.pending=java.util.Arrays.copyOfRange(v.pending,n,v.pending.length);written+=n;}
					if(written>=capacity)break;
					memory.writeLong(slot,0);
					int more=r.isRoutineDescriptor(upp)?r.callUniversal(upp,0x3d0,new int[]{slot,refCon}):r.cpu.call(upp,slot,refCon);
					if((more&0xff)==0){flags|=K_DIDNT_FILL_BUFFER;break;}
					int d=memory.readLong(slot);int count=d==0?0:memory.readLong(d+16),buffer=d==0?0:memory.readLong(d+20);
					if(d==0||count<=0||buffer==0){if(++empty>64){flags|=K_DIDNT_FILL_BUFFER;break;}continue;}empty=0;
					int format=memory.readLong(d+4),ch=(short)memory.readWord(d+8),bits=(short)memory.readWord(d+10);double rate=Integer.toUnsignedLong(memory.readLong(d+12))/65536.0;
					if(format==0)format=0x4e4f4e45;
					if(format!=v.inFormat||ch!=v.inChannels||bits!=v.inSize||rate!=v.inRate){ // the source changed format: the converter follows it
						if(inputDescription(format,ch,bits,rate)==null||ch<1||ch>8||rate<=0){ret(BAD_FORMAT);return;}setInput(v,format,ch,bits,rate);}
					int bpf=Math.max(1,v.in.bytesPerFrame),spp=Math.max(1,v.in.samplesPerPacket);
					// sampleCount counts sample frames; packet formats may give the byte size instead (kExtendedSoundBufferSizeValid)
					int packets=(memory.readLong(d)&K_EXTENDED_SOUND_DATA)!=0&&(memory.readLong(d+32)&K_BUFFER_SIZE_VALID)!=0?memory.readLong(d+36)/bpf:(count+spp-1)/spp;
					byte[] out=convert(v,memory.readBytes(buffer,Math.max(0,packets)*bpf),Math.max(0,packets));
					byte[] all=java.util.Arrays.copyOf(v.pending,v.pending.length+out.length);System.arraycopy(out,0,all,v.pending.length,out.length);v.pending=all;
				}
				if(v.pending.length>0)flags|=K_HAS_LEFT_OVER_DATA;
				if(arg(5)!=0)memory.writeLong(arg(5),written);if(arg(6)!=0)memory.writeLong(arg(6),written/fb);if(arg(7)!=0)memory.writeLong(arg(7),flags);
				ret(NO_ERR);
			}catch(java.io.IOException e){ret(BAD_FORMAT);}finally{memory.free(slot);}});
	}

	// ================================================================== CarbonLib 1.6: UPPs missing from the UI 3.3 table

	/** Parameter count of a stack-based procInfo (Pascal or C). */
	private static int stackArgCount(int procInfo){int n=0;for(int bit=6;bit<32;bit+=2){if(((procInfo>>>bit)&3)==0)break;n++;}return n;}
	/** New/Dispose/Invoke for a STACK_UPP_TYPE: a routine descriptor around the PowerPC routine; Invoke takes the UPP last. */
	private void stackUpp(PpcLibraries l,String type,int procInfo){
		int n=stackArgCount(procInfo);
		l.register(null,"New"+type+"UPP",(r,c)->ret(arg(0)==0?0:r.newRoutineDescriptor(arg(0),procInfo,PpcRuntime.ISA_PPC)));
		l.register(null,"Dispose"+type+"UPP",(r,c)->{int rd=arg(0);if(rd!=0&&r.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);});
		l.register(null,"Invoke"+type+"UPP",(r,c)->{int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);int upp=arg(n);ret(r.isRoutineDescriptor(upp)?r.callUniversal(upp,procInfo,a):r.cpu.call(upp,a));});
	}
	/** New/Dispose/Invoke for a TVECTOR_UPP_TYPE: on PowerPC the UPP is the routine's own TVector; Invoke's {@code slots} argument words come first. */
	private void tvectorUpp(PpcLibraries l,String type,int slots,int procInfo){
		l.register(null,"New"+type+"UPP",(r,c)->ret(arg(0)));
		l.register(null,"Dispose"+type+"UPP",(r,c)->{});
		l.register(null,"Invoke"+type+"UPP",(r,c)->{int[] a=new int[slots];for(int i=0;i<slots;i++)a[i]=arg(i);int upp=arg(slots);
			ret(r.isRoutineDescriptor(upp)?r.callUniversal(upp,procInfo,a):r.cpu.call(upp,a));}); // FPR arguments (FBCCallback's float) stay in f1
	}
	private void defineCarbon16Upps(PpcLibraries l){
		stackUpp(l,"DMDisplayListIterator",0xfc0);   // uppDMDisplayListIteratorProcInfo: pascal void (void *userData, DMListIndexType, DisplayListEntryPtr)
		stackUpp(l,"DMProfileListIterator",0xfc0);   // uppDMProfileListIteratorProcInfo
		stackUpp(l,"SoundConverterFillBufferData",0x3d0); // uppSoundConverterFillBufferDataProcInfo: pascal Boolean (SoundComponentDataPtr *, void *refCon)
		stackUpp(l,"SRTap",0x3e0);                   // private (speech recognition tap), inferred: pascal OSErr (4 bytes, 4 bytes)
		tvectorUpp(l,"ExceptionHandler",1,0xf1);     // MachineExceptions.h: OSStatus (ExceptionInformation *)
		tvectorUpp(l,"FBCCallback",3,0xf91);         // FindByContent.h: Boolean (UInt16 phase, float percentDone (f1, its word slot skipped), void *data)
		tvectorUpp(l,"FBCIndexFilter",2,0x3d1);      // later FindByContent.h, inferred: Boolean (item, void *refCon)
		tvectorUpp(l,"FBCTaskCompletion",2,0x3c1);   // later FindByContent.h, inferred: void (status, void *refCon)
	}

	/** NSLHexEncodeText / NSLHexDecodeText(text, UInt16 len, buffer, UInt16 *bufferLen (in: size, out: length), Boolean *changed): URL %XX escapes. */
	private int hexText(boolean encode) throws M68kException {
		int src=arg(0),len=arg(1)&0xffff,dst=arg(2),lenPtr=arg(3),changed=arg(4);
		if(src==0||dst==0||lenPtr==0)return NSL_NULL_PTR;
		byte[] in=memory.readBytes(src,len);java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream();boolean ch=false;
		for(int i=0;i<in.length;i++){int b=in[i]&0xff;
			if(encode){if(b<=0x20||b>=0x7f||"%<>\"#{}|\\^[]`;/?:@=&".indexOf(b)>=0){o.write('%');o.write(Character.toUpperCase(Character.forDigit(b>>4,16)));o.write(Character.toUpperCase(Character.forDigit(b&15,16)));ch=true;}else o.write(b);}
			else if(b=='%'&&i+2<in.length&&Character.digit(in[i+1],16)>=0&&Character.digit(in[i+2],16)>=0){o.write(Character.digit(in[i+1],16)<<4|Character.digit(in[i+2],16));i+=2;ch=true;}
			else o.write(b);}
		byte[] res=o.toByteArray();int room=memory.readWord(lenPtr);
		if(res.length>room)return -4187; // kNSLBufferTooSmallForData
		memory.load(dst,res);memory.writeWord(lenPtr,res.length);if(changed!=0)memory.writeByte(changed,ch?1:0);
		return NO_ERR;
	}
}

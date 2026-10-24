/**
 * MathLib, the PowerPC numerics library (fp.h, fenv.h): the C99-style double functions, classification, conversions
 * between double and the 68K 80-bit extended format, and the SANE-compatible decimal records. Double arguments arrive in
 * f1, f2 ... and occupy two general-register slots each; results go back in f1 (r3 for integer results).
 */
final class PpcMathLib {
	private PpcMathLib(){}
	private static final String LIB="MathLib";

	interface D1 { double f(double x); }
	interface D2 { double f(double x,double y); }

	static void define(PpcLibraries l){
		d1(l,"acos",Math::acos);d1(l,"asin",Math::asin);d1(l,"atan",Math::atan);
		d1(l,"cos",Math::cos);d1(l,"sin",Math::sin);d1(l,"tan",Math::tan);
		d1(l,"cosh",Math::cosh);d1(l,"sinh",Math::sinh);d1(l,"tanh",Math::tanh);
		d1(l,"acosh",x->Math.log(x+Math.sqrt(x*x-1)));d1(l,"asinh",x->x==0?x:Math.log(x+Math.sqrt(x*x+1)));d1(l,"atanh",x->0.5*Math.log((1+x)/(1-x)));
		d1(l,"exp",Math::exp);d1(l,"expm1",Math::expm1);d1(l,"exp2",x->Math.pow(2,x));
		d1(l,"log",Math::log);d1(l,"log10",Math::log10);d1(l,"log1p",Math::log1p);d1(l,"log2",x->Math.log(x)/Math.log(2));
		d1(l,"logb",x->x==0?Double.NEGATIVE_INFINITY:Double.isInfinite(x)?Double.POSITIVE_INFINITY:Math.getExponent(x));
		d1(l,"sqrt",Math::sqrt);d1(l,"fabs",Math::abs);d1(l,"ceil",Math::ceil);d1(l,"floor",Math::floor);
		d1(l,"rint",Math::rint);d1(l,"nearbyint",Math::rint);
		d1(l,"round",x->Double.isNaN(x)||Double.isInfinite(x)?x:Math.copySign(Math.floor(Math.abs(x)+0.5),x));
		d1(l,"trunc",x->x<0?Math.ceil(x):Math.floor(x));
		d1(l,"erf",PpcMathLib::erf);d1(l,"erfc",x->1-erf(x));
		d1(l,"gamma",PpcMathLib::gamma);d1(l,"lgamma",x->Math.log(Math.abs(gamma(x))));
		d2(l,"atan2",Math::atan2);d2(l,"pow",Math::pow);d2(l,"fmod",(x,y)->x%y);d2(l,"remainder",Math::IEEEremainder);
		d2(l,"copysign",Math::copySign);d2(l,"fdim",(x,y)->x>y?x-y:0);d2(l,"fmax",(x,y)->Double.isNaN(x)?y:Double.isNaN(y)?x:Math.max(x,y));
		d2(l,"fmin",(x,y)->Double.isNaN(x)?y:Double.isNaN(y)?x:Math.min(x,y));d2(l,"hypot",Math::hypot);d2(l,"nextafterd",Math::nextAfter);
		d2(l,"nextafterf",(x,y)->(double)Math.nextAfter((float)x,y));
		// integer arguments after one double: the double takes r3-r4, the int is r5
		l.register(LIB,"ldexp",(r,c)->r.retD(Math.scalb(r.farg(0),r.arg(2))));
		l.register(LIB,"scalb",(r,c)->r.retD(Math.scalb(r.farg(0),r.arg(2))));
		l.register(LIB,"frexp",(r,c)->{double x=r.farg(0);int e=0;double m=x;
			if(x!=0&&!Double.isNaN(x)&&!Double.isInfinite(x)){e=Math.getExponent(x)+1;m=x/Math.scalb(1.0,e);if(Math.abs(m)<0.5){m*=2;e--;}}
			if(r.arg(2)!=0)r.memory.writeLong(r.arg(2),e);r.retD(m);});
		l.register(LIB,"modf",(r,c)->{double x=r.farg(0);double i=x<0?Math.ceil(x):Math.floor(x);int p=r.arg(2);
			if(p!=0){long b=Double.doubleToRawLongBits(i);r.memory.writeLong(p,(int)(b>>>32));r.memory.writeLong(p+4,(int)b);}
			r.retD(Double.isInfinite(x)?Math.copySign(0,x):x-i);});
		l.register(LIB,"remquo",(r,c)->{double x=r.farg(0),y=r.farg(1);double q=Math.rint(x/y);if(r.arg(4)!=0)r.memory.writeLong(r.arg(4),(int)q&0x7f);r.retD(Math.IEEEremainder(x,y));});
		l.register(LIB,"rinttol",(r,c)->r.ret(toLong(Math.rint(r.farg(0)))));
		l.register(LIB,"roundtol",(r,c)->{double x=r.farg(0);r.ret(toLong(Math.copySign(Math.floor(Math.abs(x)+0.5),x)));});
		l.register(LIB,"__fpclassifyd",(r,c)->r.ret(classify(r.farg(0))));
		l.register(LIB,"__fpclassifyf",(r,c)->r.ret(classify(r.farg(0))));
		l.register(LIB,"__fpclassify",(r,c)->r.ret(classify(r.farg(0))));
		l.register(LIB,"__isfinite",(r,c)->r.ret(Double.isFinite(r.farg(0))?1:0));
		l.register(LIB,"__isfinitef",(r,c)->r.ret(Double.isFinite(r.farg(0))?1:0));
		l.register(LIB,"__isfinited",(r,c)->r.ret(Double.isFinite(r.farg(0))?1:0));
		l.register(LIB,"__isnand",(r,c)->r.ret(Double.isNaN(r.farg(0))?1:0));
		l.register(LIB,"__isnanf",(r,c)->r.ret(Double.isNaN(r.farg(0))?1:0));
		l.register(LIB,"__isnormald",(r,c)->{double x=r.farg(0);r.ret(Double.isFinite(x)&&Math.abs(x)>=Double.MIN_NORMAL?1:0);});
		l.register(LIB,"__signbitd",(r,c)->r.ret(Double.doubleToRawLongBits(r.farg(0))<0?1:0));
		l.register(LIB,"__signbitf",(r,c)->r.ret(Double.doubleToRawLongBits(r.farg(0))<0?1:0));
		l.register(LIB,"__inf",(r,c)->r.retD(Double.POSITIVE_INFINITY));
		l.register(LIB,"nan",(r,c)->r.retD(Double.NaN));
		l.register(LIB,"nanf",(r,c)->r.retD(Double.NaN));
		l.register(LIB,"randomx",(r,c)->{ // SANE random: x = (7^5 * x) mod (2^31 - 1)
			int p=r.arg(0);long b=((long)r.memory.readLong(p)<<32)|(r.memory.readLong(p+4)&0xffffffffL);double x=Double.longBitsToDouble(b);
			double v=(16807.0*x)%2147483647.0;long nb=Double.doubleToRawLongBits(v);r.memory.writeLong(p,(int)(nb>>>32));r.memory.writeLong(p+4,(int)nb);r.retD(v);});
		l.register(LIB,"x80tod",(r,c)->r.retD(x80ToDouble(r,r.arg(0))));
		// x80told (void x80told(const extended80*, long double*)): PpcCarbonUtilities
		l.register(LIB,"dtox80",(r,c)->{int p=r.arg(0);long b=((long)r.memory.readLong(p)<<32)|(r.memory.readLong(p+4)&0xffffffffL);doubleToX80(r,Double.longBitsToDouble(b),r.arg(1));});
		l.register(LIB,"ldtox80",(r,c)->{int p=r.arg(0);long b=((long)r.memory.readLong(p)<<32)|(r.memory.readLong(p+4)&0xffffffffL);doubleToX80(r,Double.longBitsToDouble(b),r.arg(1));});
		l.register(LIB,"num2dec",(r,c)->num2dec(r,r.arg(0),r.farg(0),r.arg(3)));
		l.register(LIB,"dec2num",(r,c)->r.retD(dec2num(r,r.arg(0))));
		l.register(LIB,"dec2str",(r,c)->dec2str(r,r.arg(0),r.arg(1),r.arg(2)));
		l.register(LIB,"str2dec",(r,c)->str2dec(r,r.arg(0),r.arg(1),r.arg(2),r.arg(3)));
		l.register(LIB,"dec2f",(r,c)->r.retD((double)(float)dec2num(r,r.arg(0))));
		l.register(LIB,"dec2s",(r,c)->{double v=dec2num(r,r.arg(0));r.ret((short)Math.max(-32768,Math.min(32767,Math.rint(v))));});
		l.register(LIB,"dec2l",(r,c)->r.ret(toLong(Math.rint(dec2num(r,r.arg(0))))));
		l.register(LIB,"relation",(r,c)->{double x=r.farg(0),y=r.farg(1);r.ret(Double.isNaN(x)||Double.isNaN(y)?3:x>y?0:x<y?1:2);}); // GREATERTHAN, LESSTHAN, EQUALTO, UNORDERED
		l.register(LIB,"fegetround",(r,c)->r.ret(c.fpscr&3));
		l.register(LIB,"fesetround",(r,c)->{c.fpscr=(c.fpscr&~3)|(r.arg(0)&3);r.ret(0);});
		l.register(LIB,"feclearexcept",(r,c)->{c.fpscr&=~(r.arg(0)&0xfff80700);});
		l.register(LIB,"fetestexcept",(r,c)->r.ret(c.fpscr&r.arg(0)));
		l.register(LIB,"feraiseexcept",(r,c)->{c.fpscr|=r.arg(0);});
		l.register(LIB,"fegetenv",(r,c)->r.memory.writeLong(r.arg(0),c.fpscr));
		l.register(LIB,"fesetenv",(r,c)->{c.fpscr=r.memory.readLong(r.arg(0));});
		l.register(LIB,"feholdexcept",(r,c)->{r.memory.writeLong(r.arg(0),c.fpscr);c.fpscr&=~0xfff800f8;r.ret(0);});
		l.register(LIB,"feupdateenv",(r,c)->{c.fpscr=r.memory.readLong(r.arg(0))|(c.fpscr&0xfff80700);});
	}
	private static void d1(PpcLibraries l,String name,D1 f){l.register(LIB,name,(r,c)->r.retD(f.f(r.farg(0))));}
	private static void d2(PpcLibraries l,String name,D2 f){l.register(LIB,name,(r,c)->r.retD(f.f(r.farg(0),r.farg(1))));}
	private static int toLong(double v){if(Double.isNaN(v))return Integer.MIN_VALUE;if(v>=2147483647.0)return Integer.MAX_VALUE;if(v<=-2147483648.0)return Integer.MIN_VALUE;return (int)v;}
	/** fp.h classes: FP_SNAN 0, FP_QNAN 1, FP_INFINITE 2, FP_ZERO 3, FP_NORMAL 4, FP_SUBNORMAL 5. */
	private static int classify(double x){
		if(Double.isNaN(x))return (Double.doubleToRawLongBits(x)&0x0008000000000000L)!=0?1:0;
		if(Double.isInfinite(x))return 2;if(x==0)return 3;return Math.abs(x)<Double.MIN_NORMAL?5:4;
	}
	private static double erf(double x){ // Abramowitz-Stegun 7.1.26 refined with a series near zero
		double ax=Math.abs(x);
		if(ax<0.5){double s=0,t=x,n=0;for(int k=0;k<30;k++){s+=t/(2*k+1);t*=-x*x/(k+1);}return 2/Math.sqrt(Math.PI)*s;}
		double t=1/(1+0.5*ax);
		double y=1-t*Math.exp(-ax*ax-1.26551223+t*(1.00002368+t*(0.37409196+t*(0.09678418+t*(-0.18628806+t*(0.27886807+t*(-1.13520398+t*(1.48851587+t*(-0.82215223+t*0.17087277)))))))));
		return x>=0?y:-y;
	}
	private static double gamma(double x){ // Lanczos
		if(x<0.5)return Math.PI/(Math.sin(Math.PI*x)*gamma(1-x));
		double[] g={0.99999999999980993,676.5203681218851,-1259.1392167224028,771.32342877765313,-176.61502916214059,12.507343278686905,-0.13857109526572012,9.9843695780195716e-6,1.5056327351493116e-7};
		x-=1;double a=g[0];double t=x+7.5;for(int i=1;i<9;i++)a+=g[i]/(x+i);
		return Math.sqrt(2*Math.PI)*Math.pow(t,x+0.5)*Math.exp(-t)*a;
	}
	private static void writeDouble(PpcRuntime r,int p,double v) throws M68kCpuException {long b=Double.doubleToRawLongBits(v);r.memory.writeLong(p,(int)(b>>>32));r.memory.writeLong(p+4,(int)b);}
	/** The 68K 80-bit extended (sign+exponent word, 64-bit mantissa with explicit integer bit) to double. */
	static double x80ToDouble(PpcRuntime r,int p) throws M68kCpuException {
		int se=r.memory.readWord(p);long mant=((long)r.memory.readLong(p+2)<<32)|(r.memory.readLong(p+6)&0xffffffffL);
		boolean neg=(se&0x8000)!=0;int exp=se&0x7fff;
		if(exp==0x7fff)return (mant<<1)!=0?Double.NaN:neg?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
		if(mant==0)return neg?-0.0:0.0;
		double v=Math.scalb((double)(mant>>>11),exp-16383-52);
		return neg?-v:v;
	}
	static void doubleToX80(PpcRuntime r,double v,int p) throws M68kCpuException {
		int sign=Double.doubleToRawLongBits(v)<0?0x8000:0;
		if(Double.isNaN(v)){r.memory.writeWord(p,0x7fff);r.memory.writeLong(p+2,0xc0000000);r.memory.writeLong(p+6,0);return;}
		if(Double.isInfinite(v)){r.memory.writeWord(p,sign|0x7fff);r.memory.writeLong(p+2,0x80000000);r.memory.writeLong(p+6,0);return;}
		if(v==0){r.memory.writeWord(p,sign);r.memory.writeLong(p+2,0);r.memory.writeLong(p+6,0);return;}
		double a=Math.abs(v);int e=Math.getExponent(a);
		if(e<Double.MIN_EXPONENT){e=Double.MIN_EXPONENT;} // subnormal: normalize via scaling below
		long m=(long)(Math.scalb(a,63-e));while(m>0&&(m&0x8000000000000000L)==0){m<<=1;e--;}
		r.memory.writeWord(p,sign|(e+16383));r.memory.writeLong(p+2,(int)(m>>>32));r.memory.writeLong(p+6,(int)m);
	}
	// decimal record: sgn (byte), unused, exp (short), sig: length byte + 36 digits + unused = 42 bytes; decform: style byte, unused, digits short
	static void num2dec(PpcRuntime r,int form,double x,int d) throws M68kCpuException {
		int style=r.memory.readByte(form);int digits=(short)r.memory.readWord(form+2);
		r.memory.writeByte(d,Double.doubleToRawLongBits(x)<0?1:0);r.memory.writeByte(d+1,0);
		String sig;int exp;
		if(Double.isNaN(x)){sig="N";exp=0;}else if(Double.isInfinite(x)){sig="I";exp=0;}
		else if(x==0){sig="0";exp=0;}
		else{
			java.math.BigDecimal bd=new java.math.BigDecimal(Math.abs(x));
			if(style==0){int n=Math.max(1,Math.min(digits,19));bd=bd.round(new java.math.MathContext(n));}
			else{bd=bd.setScale(Math.max(-36,Math.min(digits,36)),java.math.RoundingMode.HALF_EVEN);}
			java.math.BigInteger unscaled=bd.unscaledValue();sig=unscaled.toString();exp=-bd.scale();
			if(sig.length()>36){int drop=sig.length()-36;sig=sig.substring(0,36);exp+=drop;}
			while(sig.length()>1&&sig.endsWith("0")){sig=sig.substring(0,sig.length()-1);exp++;}
			if(sig.equals("0"))exp=0;
		}
		r.memory.writeWord(d+2,exp);r.memory.writeByte(d+4,sig.length());
		for(int i=0;i<sig.length();i++)r.memory.writeByte(d+5+i,sig.charAt(i));
	}
	static double dec2num(PpcRuntime r,int d) throws M68kCpuException {
		boolean neg=r.memory.readByte(d)!=0;int exp=(short)r.memory.readWord(d+2);int n=r.memory.readByte(d+4);
		StringBuilder sb=new StringBuilder();for(int i=0;i<n&&i<36;i++)sb.append((char)r.memory.readByte(d+5+i));
		String s=sb.toString();double v;
		if(s.startsWith("N"))v=Double.NaN;else if(s.startsWith("I"))v=Double.POSITIVE_INFINITY;
		else if(s.isEmpty())v=0;else{try{v=new java.math.BigDecimal(new java.math.BigInteger(s),-exp).doubleValue();}catch(NumberFormatException e){v=0;}}
		return neg?-v:v;
	}
	static void dec2str(PpcRuntime r,int form,int d,int out) throws M68kCpuException {
		int style=r.memory.readByte(form);int digits=(short)r.memory.readWord(form+2);
		double v=dec2num(r,d);
		String s;
		if(Double.isNaN(v))s="NAN";else if(Double.isInfinite(v))s=v<0?"-INF":"INF";
		else if(style==1)s=String.format("%."+Math.max(0,digits)+"f",v);
		else s=String.format("%."+Math.max(0,digits-1)+"e",v).replace("e+0","e+").replace("e-0","e-");
		byte[] b=s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);int n=Math.min(79,b.length);
		r.memory.load(out,b,0,n);r.memory.writeByte(out+n,0);
	}
	static void str2dec(PpcRuntime r,int s,int indexPtr,int d,int validPrefix) throws M68kCpuException {
		int index=(short)r.memory.readWord(indexPtr); // a C string, index from 0
		int i=index;StringBuilder sb=new StringBuilder();
		while(r.memory.readByte(s+i)!=0&&Character.isWhitespace(r.memory.readByte(s+i)))i++;
		int start=i;
		for(;;){int b=r.memory.readByte(s+i);char ch=(char)b;if(b!=0&&(Character.isDigit(ch)||ch=='.'||ch=='-'||ch=='+'||ch=='e'||ch=='E')){sb.append(ch);i++;}else break;}
		double v=0;boolean ok=true;
		try{v=Double.parseDouble(sb.toString());}catch(NumberFormatException e){ok=false;i=start;}
		r.memory.writeWord(indexPtr,i);if(validPrefix!=0)r.memory.writeWord(validPrefix,ok?1:0);
		int form=r.allocate(4,4);r.memory.writeByte(form,0);r.memory.writeWord(form+2,19);num2dec(r,form,v,d);r.memory.free(form);
	}
}

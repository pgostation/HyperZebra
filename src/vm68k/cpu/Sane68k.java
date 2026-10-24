/** Basic implementation of the Standard Apple Numerics Environment FP68K package. */
final class Sane68k {
	private static final int FORMAT_X=0x0000,FORMAT_D=0x0800,FORMAT_S=0x1000;
	private static final int FORMAT_I=0x2000,FORMAT_L=0x2800,FORMAT_C=0x3000;
	private static final int FORMAT_MASK=0x3800;
	private final M68kMemory memory;
	private int environment,haltVector;

	Sane68k(M68kMemory memory) {this.memory=memory;}

	boolean handle(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],opword=memory.readWord(sp),operation=opword&0xff;
		int format=opword&FORMAT_MASK;
		if(Mac68kApi.TRACE){ // -Dhz68k.trace: operands before, result after (the destination is always the last operand pushed)
			int destination=memory.readLong(sp+2),source=memory.readLong(sp+6);boolean binary=operation<=0x0c||operation==0x10||operation==0x18||operation==0x1c||operation==0x0e;
			String before=describe(destination,operation==0x1c?FORMAT_I:FORMAT_X)+(binary?" src "+describe(source,operation==0x10||operation==0x1c?FORMAT_X:operation==0x18?FORMAT_I:format):"");
			boolean handled=dispatch(cpu,operation,format);
			System.err.println("[68K] FP68K op $"+Integer.toHexString(opword)+" dst "+before+" -> "+describe(destination,operation==0x10?format:FORMAT_X));
			return handled;
		}
		return dispatch(cpu,operation,format);
	}

	private String describe(int pointer,int format){try{return Double.toString(read(pointer,format));}catch(M68kCpuException e){return "?";}}

	private boolean dispatch(M68kCpu cpu,int operation,int format) throws M68kCpuException {
		switch(operation) {
			case 0x00:return arithmetic(cpu,format,0);
			case 0x02:return arithmetic(cpu,format,1);
			case 0x04:return arithmetic(cpu,format,2);
			case 0x06:return arithmetic(cpu,format,3);
			case 0x08:case 0x0a:return compare(cpu,format);
			case 0x0c:return remainder(cpu,format);
			case 0x0d:return unary(cpu,operation);
			case 0x0e:return convertToExtended(cpu,format);
			case 0x0f:return unary(cpu,operation);
			case 0x10:return convertFromExtended(cpu,format);
			case 0x12:return unary(cpu,operation);
			case 0x14:case 0x16:return unary(cpu,operation);
			case 0x18:return scale(cpu);
			case 0x1a:return unary(cpu,operation);
			case 0x1c:return classify(cpu,format);
			case 0x11:return copySign(cpu);
			case 0x1e:return nextAfter(cpu,format);
			case 0x09:return decimalToBinary(cpu,format);
			case 0x0b:return binaryToDecimal(cpu,format);
			case 0x01:case 0x03:return environment(cpu,operation);
			case 0x17:case 0x19:return procedureEnvironment(cpu,operation);
			case 0x05:case 0x07:return haltVector(cpu,operation);
			case 0x15:case 0x1b:return exceptionFlags(cpu,operation);
			default:return false;
		}
	}

	private boolean arithmetic(M68kCpu cpu,int format,int operation) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		double a=read(source,format),b=readExtended(destination),result;
		switch(operation) {
			case 0:result=b+a;break;case 1:result=b-a;break;
			case 2:result=b*a;break;default:result=b/a;break;
		}
		writeExtended(destination,result);cpu.a[7]+=10;return true;
	}

	/** FB2D: Num2Dec(f: DecForm; x; VAR d: Decimal). Stack: opword, d, x, DecForm (d pushed last). The Decimal record is
	 *  sgn (byte), unused, exp (word), sig (Pascal string of up to 20 digits); DecForm is style (byte: 0 = FLOATDECIMAL
	 *  significant digits, 1 = FIXEDDECIMAL digits after the point), unused, digits (word). HyperCard turns every
	 *  number into text through this call. */
	private boolean binaryToDecimal(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],decimal=memory.readLong(sp+2),source=memory.readLong(sp+6),form=memory.readLong(sp+10);
		double value=read(source,format);int style=memory.readByte(form),digits=(short)memory.readWord(form+2);cpu.a[7]+=14;
		int sign=value<0||(value==0&&1/value<0)?1:0;String sig;int exp=0;
		if(Double.isNaN(value))sig="N";else if(Double.isInfinite(value))sig="I";
		else if(value==0){sig="0";}
		else{java.math.BigDecimal d=java.math.BigDecimal.valueOf(Math.abs(value)); // shortest decimal form of the double: what an Extended's extra bits would round to
			if(style==0)d=d.round(new java.math.MathContext(Math.max(1,Math.min(19,digits)),java.math.RoundingMode.HALF_EVEN));
			else d=d.setScale(Math.max(0,digits),java.math.RoundingMode.HALF_EVEN);
			if(d.precision()>19)d=d.round(new java.math.MathContext(19,java.math.RoundingMode.HALF_EVEN)); // SANE keeps at most 19 digits
			if(style==0)d=d.stripTrailingZeros();
			sig=d.unscaledValue().toString();exp=-d.scale();
			if(sig.equals("0"))exp=0;}
		memory.writeByte(decimal,sign);memory.writeByte(decimal+1,0);memory.writeWord(decimal+2,exp);
		memory.writeByte(decimal+4,sig.length());for(int i=0;i<sig.length();i++)memory.writeByte(decimal+5+i,sig.charAt(i));
		return true;
	}

	/** FD2B: Dec2Num(d: Decimal; VAR x). Stack: opword, x, d (x pushed last); the result takes the opword's format. */
	private boolean decimalToBinary(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),decimal=memory.readLong(sp+6);cpu.a[7]+=10;
		int sign=memory.readByte(decimal),exp=(short)memory.readWord(decimal+2),n=memory.readByte(decimal+4);
		StringBuilder sig=new StringBuilder();for(int i=0;i<n;i++)sig.append((char)memory.readByte(decimal+5+i));
		double value;String digits=sig.toString();
		if(digits.startsWith("N"))value=Double.NaN;else if(digits.startsWith("I"))value=Double.POSITIVE_INFINITY;
		else{try{value=digits.isEmpty()?0:new java.math.BigDecimal(new java.math.BigInteger(digits),-exp).doubleValue();}catch(NumberFormatException e){value=Double.NaN;}}
		write(destination,format,sign!=0?-value:value);return true;
	}

	private boolean remainder(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		double divisor=read(source,format),dividend=readExtended(destination);
		double quotient=Math.rint(dividend/divisor);
		writeExtended(destination,Math.IEEEremainder(dividend,divisor));
		int info=(int)Math.abs(quotient)&0x7f;if(quotient<0)info=-info;
		cpu.d[0]=(cpu.d[0]&0xffff0000)|(info&0xffff);cpu.a[7]+=10;return true;
	}

	private boolean unary(M68kCpu cpu,int operation) throws M68kCpuException {
		int destination=memory.readLong(cpu.a[7]+2);double value=readExtended(destination),result;
		switch(operation) {
			case 0x0d:result=-value;break;
			case 0x0f:result=Math.abs(value);break;
			case 0x12:result=Math.sqrt(value);break;
			case 0x14:result=roundToInteger(value);break;
			case 0x16:result=value<0?Math.ceil(value):Math.floor(value);break;
			default: // FLOGBX: the unbiased exponent; logb(0) = -INF, logb(INF) = +INF, NaN stays, a denormal counts as normalized
				if(Double.isNaN(value))result=value;else if(Double.isInfinite(value))result=Double.POSITIVE_INFINITY;else if(value==0)result=Double.NEGATIVE_INFINITY;
				else if(Math.abs(value)<Double.MIN_NORMAL)result=Math.getExponent(value*0x1p64)-64;else result=Math.getExponent(value);break;
		}
		writeExtended(destination,result);cpu.a[7]+=6;return true;
	}

	/** FCPYSGNX (src, dst): the source takes the destination's sign (SANE's copysign(x, y) is y with the sign of x, x being
	 *  the destination); the sign is the first bit whatever the floating-point format. */
	private boolean copySign(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		memory.writeByte(source,(memory.readByte(source)&0x7f)|(memory.readByte(destination)&0x80));
		cpu.a[7]+=10;return true;
	}

	/** FNEXTS/FNEXTD/FNEXTX (src, dst): the destination becomes the next value of its format after it toward the source
	 *  (both operands in the opword's format; an extended one steps its 64-bit significand, which a double cannot hold). */
	private boolean nextAfter(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);cpu.a[7]+=10;
		if(format==FORMAT_S){memory.writeLong(destination,Float.floatToRawIntBits(Math.nextAfter(Float.intBitsToFloat(memory.readLong(destination)),Float.intBitsToFloat(memory.readLong(source)))));return true;}
		if(format==FORMAT_D){writeLong64(destination,Double.doubleToRawLongBits(Math.nextAfter(Double.longBitsToDouble(readLong64(destination)),Double.longBitsToDouble(readLong64(source)))));return true;}
		if(format!=FORMAT_X)return true;
		int xs=memory.readWord(destination),ys=memory.readWord(source);long xm=readLong64(destination+2),ym=readLong64(source+2);
		boolean xNan=(xs&0x7fff)==0x7fff&&(xm<<1)!=0,yNan=(ys&0x7fff)==0x7fff&&(ym<<1)!=0;
		if(xNan)return true;
		if(yNan){memory.writeWord(destination,ys);writeLong64(destination+2,ym);return true;}
		int order=compareExtended(xs,xm,ys,ym);
		if(order==0)return true;
		boolean up=order<0,negative=(xs&0x8000)!=0;int exponent=xs&0x7fff;
		if(exponent==0&&xm==0){memory.writeWord(destination,up?0:0x8000);writeLong64(destination+2,1);return true;} // zero: the smallest denormal toward the source
		if(up!=negative){ // away from zero
			if(xm==-1L){xm=Long.MIN_VALUE;exponent++;}
			else{xm++;if(exponent==0&&xm==Long.MIN_VALUE)exponent=1;}
			if(exponent==0x7fff)xm=Long.MIN_VALUE; // past the largest finite value: infinity
		}else{ // toward zero
			if(exponent==0x7fff){exponent=0x7ffe;xm=-1L;}
			else if(xm==Long.MIN_VALUE&&exponent>1){exponent--;xm=-1L;}
			else if(xm==Long.MIN_VALUE&&exponent==1){exponent=0;xm=Long.MAX_VALUE;}
			else xm--;
		}
		memory.writeWord(destination,(xs&0x8000)|exponent);writeLong64(destination+2,xm);return true;
	}
	/** The order of two extended values (sign/exponent word, significand), neither a NaN: -1, 0 or 1. */
	private static int compareExtended(int as,long am,int bs,long bm){
		boolean aZero=(as&0x7fff)==0&&am==0,bZero=(bs&0x7fff)==0&&bm==0;
		if(aZero&&bZero)return 0;
		boolean aNeg=(as&0x8000)!=0&&!aZero,bNeg=(bs&0x8000)!=0&&!bZero;
		if(aNeg!=bNeg)return aNeg?-1:1;
		int magnitude=(as&0x7fff)!=(bs&0x7fff)?Integer.compare(as&0x7fff,bs&0x7fff):Long.compareUnsigned(am,bm);
		return aNeg?-magnitude:magnitude;
	}

	private boolean scale(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		writeExtended(destination,Math.scalb(readExtended(destination),(short)memory.readWord(source)));
		cpu.a[7]+=10;return true;
	}

	private boolean convertToExtended(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		writeExtended(destination,read(source,format));cpu.a[7]+=10;return true;
	}

	private boolean convertFromExtended(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		write(destination,format,readExtended(source));cpu.a[7]+=10;return true;
	}

	private boolean compare(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		double a=read(source,format),b=readExtended(destination);int flags=0;
		if(Double.isNaN(a)||Double.isNaN(b))flags=M68kCpu.FLAG_V;
		else if(b<a)flags=M68kCpu.FLAG_X|M68kCpu.FLAG_N|M68kCpu.FLAG_C;
		else if(b==a)flags=M68kCpu.FLAG_Z;
		cpu.sr=(cpu.sr&~(M68kCpu.FLAG_X|M68kCpu.FLAG_N|M68kCpu.FLAG_Z|
			M68kCpu.FLAG_V|M68kCpu.FLAG_C))|flags;
		cpu.a[7]+=10;return true;
	}

	private boolean classify(M68kCpu cpu,int format) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp+2),source=memory.readLong(sp+6);
		double value=read(source,format);int classification;
		if(Double.isNaN(value))classification=2;
		else if(Double.isInfinite(value))classification=3;
		else if(value==0.0)classification=4;
		else if(isDenormal(source,format))classification=6;
		else classification=5;
		if(Double.doubleToRawLongBits(value)<0)classification=-classification;
		memory.writeWord(destination,classification);cpu.a[7]+=10;return true;
	}

	private boolean environment(M68kCpu cpu,int operation) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]+2);
		if(operation==0x01)environment=memory.readWord(pointer);
		else memory.writeWord(pointer,environment);
		cpu.a[7]+=6;return true;
	}

	/** FPROCENTRY saves the current environment in the caller's word and installs the default one; FPROCEXIT restores it
	 *  (keeping the exception flags raised meanwhile, which are never set here as no exception ever fires). */
	private boolean procedureEnvironment(M68kCpu cpu,int operation) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]+2);
		if(operation==0x17){memory.writeWord(pointer,environment);environment=0;}
		else environment=memory.readWord(pointer);
		cpu.a[7]+=6;return true;
	}

	private boolean haltVector(M68kCpu cpu,int operation) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]+2);
		if(operation==0x05)haltVector=memory.readLong(pointer);else memory.writeLong(pointer,haltVector);
		cpu.a[7]+=6;return true;
	}

	/** FSETXCP raises the exception named by the word (sticky flags live in the high byte of the environment); FTESTXCP answers whether it is set. */
	private boolean exceptionFlags(M68kCpu cpu,int operation) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]+2),exception=memory.readWord(pointer)&0x1f;
		if(operation==0x15)environment|=exception<<8;
		else memory.writeWord(pointer,(environment>>8&exception)!=0?0x0100:0);
		cpu.a[7]+=6;return true;
	}

	private double read(int pointer,int format) throws M68kCpuException {
		switch(format) {
			case FORMAT_X:return readExtended(pointer);
			case FORMAT_D:return Double.longBitsToDouble(readLong64(pointer));
			case FORMAT_S:return Float.intBitsToFloat(memory.readLong(pointer));
			case FORMAT_I:return (short)memory.readWord(pointer);
			case FORMAT_L:return memory.readLong(pointer);
			case FORMAT_C:return (double)readLong64(pointer);
			default:return Double.NaN;
		}
	}

	private void write(int pointer,int format,double value) throws M68kCpuException {
		switch(format) {
			case FORMAT_X:writeExtended(pointer,value);break;
			case FORMAT_D:writeLong64(pointer,Double.doubleToRawLongBits(value));break;
			case FORMAT_S:memory.writeLong(pointer,Float.floatToRawIntBits((float)value));break;
			case FORMAT_I:memory.writeWord(pointer,(int)integerOf(roundToInteger(value),Short.MIN_VALUE,Short.MAX_VALUE,0x8000));break;
			case FORMAT_L:memory.writeLong(pointer,(int)integerOf(roundToInteger(value),Integer.MIN_VALUE,Integer.MAX_VALUE,0x80000000L));break;
			case FORMAT_C:writeLong64(pointer,integerOf(roundToInteger(value),-Long.MAX_VALUE,Long.MAX_VALUE,Long.MIN_VALUE));break;
		}
	}

	/** A NaN, an infinity or a value out of the format's range converts to the format's integer NaN ($8000, $80000000, the comp
	 *  NaN), as SANE does (Java gave 0 for a NaN, and a 16-bit store kept the low bits of a too-large value). */
	private static long integerOf(double rounded,long min,long max,long nan){return Double.isNaN(rounded)||rounded<min||rounded>max?nan:(long)rounded;}

	/** Rounds by the rounding direction of the environment word (bits 13-14: nearest, upward, downward, toward zero);
	 *  HyperCard's trunc() selects "toward zero" with FSETENV and then rounds with FRINTX. */
	private double roundToInteger(double value){
		switch(environment>>13&3){
			case 1:return Math.ceil(value);
			case 2:return Math.floor(value);
			case 3:return value<0?Math.ceil(value):Math.floor(value);
			default:return Math.rint(value);
		}
	}

	static double readExtended(M68kMemory memory,int pointer) throws M68kCpuException {
		int signExponent=memory.readWord(pointer),exponent=signExponent&0x7fff;
		long high=Integer.toUnsignedLong(memory.readLong(pointer+2));
		long low=Integer.toUnsignedLong(memory.readLong(pointer+6));
		if(exponent==0x7fff) {
			if(high==0x80000000L&&low==0)return (signExponent&0x8000)!=0?
				Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
			return Double.NaN;
		}
		if(exponent==0&&high==0&&low==0)return (signExponent&0x8000)!=0?-0.0:0.0;
		double significand=Math.scalb((double)high,-31)+Math.scalb((double)low,-63);
		double value=Math.scalb(significand,exponent==0?1-16383:exponent-16383);
		return (signExponent&0x8000)!=0?-value:value;
	}

	static void writeExtended(M68kMemory memory,int pointer,double value) throws M68kCpuException {
		long bits=Double.doubleToRawLongBits(value);int sign=(bits<0)?0x8000:0;
		int doubleExponent=(int)((bits>>>52)&0x7ff);long fraction=bits&0x000fffffffffffffL;
		if(doubleExponent==0x7ff) {
			memory.writeWord(pointer,sign|0x7fff);
			long mantissa=fraction==0?Long.MIN_VALUE:Long.MIN_VALUE|(fraction<<11)|1;
			writeLong64(memory,pointer+2,mantissa);return;
		}
		if(doubleExponent==0&&fraction==0) {memory.fill(pointer,10,0);memory.writeWord(pointer,sign);return;}
		int exponent;long mantissa;
		if(doubleExponent==0) {
			int highest=63-Long.numberOfLeadingZeros(fraction);
			exponent=highest-1074+16383;mantissa=fraction<<(63-highest);
		} else {
			exponent=doubleExponent-1023+16383;mantissa=Long.MIN_VALUE|(fraction<<11);
		}
		memory.writeWord(pointer,sign|exponent);writeLong64(memory,pointer+2,mantissa);
	}

	private double readExtended(int pointer) throws M68kCpuException {return readExtended(memory,pointer);}
	private void writeExtended(int pointer,double value) throws M68kCpuException {writeExtended(memory,pointer,value);}
	private long readLong64(int pointer) throws M68kCpuException {
		return ((long)memory.readLong(pointer)<<32)|Integer.toUnsignedLong(memory.readLong(pointer+4));
	}
	private void writeLong64(int pointer,long value) throws M68kCpuException {writeLong64(memory,pointer,value);}
	private static void writeLong64(M68kMemory memory,int pointer,long value) throws M68kCpuException {
		memory.writeLong(pointer,(int)(value>>>32));memory.writeLong(pointer+4,(int)value);
	}

	private boolean isDenormal(int pointer,int format) throws M68kCpuException {
		if(format==FORMAT_X)return (memory.readWord(pointer)&0x7fff)==0&&
			(memory.readLong(pointer+2)!=0||memory.readLong(pointer+6)!=0);
		if(format==FORMAT_D)return (memory.readLong(pointer)&0x7ff00000)==0&&
			((memory.readLong(pointer)&0xfffff)!=0||memory.readLong(pointer+4)!=0);
		if(format==FORMAT_S)return (memory.readLong(pointer)&0x7f800000)==0&&
			(memory.readLong(pointer)&0x007fffff)!=0;
		return false;
	}
}

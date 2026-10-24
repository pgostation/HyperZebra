import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * MC68881/MC68882 floating-point coprocessor for the 68K interpreter: the F-line instructions of coprocessor 1
 * (general arithmetic, FMOVE in every data format including packed decimal, FMOVEM of data and control registers,
 * FMOVECR, FBcc/FScc/FDBcc/FTRAPcc/FNOP, FSAVE/FRESTORE) plus the 68040 rounded variants (FSADD, FDMUL ...).
 *
 * The eight data registers are Java doubles: the 64-bit mantissa of the extended format is kept to 53 bits, which
 * no application code depends on (compilers only use extended precision as the register format). Condition codes,
 * the quotient byte and the exception/accrued bytes of FPSR are maintained; FPCR rounding mode and precision are
 * honoured for results, exception traps are never taken (they are disabled after reset and Mac programs leave
 * them so). FSAVE writes the null state frame, so a FSAVE/FRESTORE pair around a context switch reinitializes the
 * unit exactly like the real chip after reset; the Thread Manager saves and restores the data registers itself.
 */
final class M68kFpu {
	/** -Dhz68k.fpu=false removes the coprocessor: F-line instructions fail and Gestalt/SysEnvirons report no FPU. */
	static final boolean ENABLED=!"false".equals(System.getProperty("hz68k.fpu"));
	/** gestaltFPUType: 2 = 68882 (matches the 68030 gestaltProcessorType the Toolbox reports). */
	static final int GESTALT_FPU_TYPE=ENABLED?2:0;

	// FPSR: condition codes (bits 27-24), quotient byte (23-16), exception status (15-8), accrued exceptions (7-3)
	static final int CC_N=1<<27,CC_Z=1<<26,CC_I=1<<25,CC_NAN=1<<24;
	static final int EXC_BSUN=1<<15,EXC_SNAN=1<<14,EXC_OPERR=1<<13,EXC_OVFL=1<<12,EXC_UNFL=1<<11,EXC_DZ=1<<10,EXC_INEX2=1<<9,EXC_INEX1=1<<8;
	static final int AEXC_IOP=1<<7,AEXC_OVFL=1<<6,AEXC_UNFL=1<<5,AEXC_DZ=1<<4,AEXC_INEX=1<<3;
	// FPCR: rounding mode (bits 5-4) and rounding precision (7-6)
	static final int ROUND_NEAREST=0,ROUND_ZERO=1,ROUND_MINUS=2,ROUND_PLUS=3;
	static final int PREC_EXTENDED=0,PREC_SINGLE=1,PREC_DOUBLE=2;

	private static final int FORMAT_LONG=0,FORMAT_SINGLE=1,FORMAT_EXTENDED=2,FORMAT_PACKED=3,FORMAT_WORD=4,FORMAT_DOUBLE=5,FORMAT_BYTE=6,FORMAT_PACKED_DYNAMIC=7;
	private static final int[] FORMAT_SIZE={4,4,12,12,2,8,1,12};
	private static final double LN2=Math.log(2),LN10=Math.log(10);

	private final M68kCpu cpu;
	private final M68kMemory memory;
	final double[] fp=new double[8];
	int fpcr,fpsr,fpiar;
	private boolean used;

	M68kFpu(M68kCpu cpu){this.cpu=cpu;this.memory=cpu.memory;reset();}

	/** The state after RESET or a null-frame FRESTORE: every data register a NaN, control registers zero. */
	void reset(){Arrays.fill(fp,Double.NaN);fpcr=0;fpsr=0;fpiar=0;}
	/** True once the program has executed a coprocessor instruction (crash dumps then include the FPU state). */
	boolean used(){return used;}
	String dumpState(){
		StringBuilder b=new StringBuilder();
		for(int i=0;i<8;i++)b.append(String.format("FP%d=%s ",i,fp[i]));
		b.append(String.format("FPCR=%08X FPSR=%08X FPIAR=%08X",fpcr,fpsr,fpiar));
		return b.toString();
	}

	// ------------------------------------------------------------------ decoding

	void execute(int op,int instructionPc) throws M68kException {
		used=true;
		switch((op>>>6)&7){
			case 0:general(op,instructionPc);return;
			case 1:
				if((op&0x38)==0x08){fdbcc(op,instructionPc);return;}
				if((op&0x38)==0x38&&(op&7)>=2&&(op&7)<=4){ftrapcc(op,instructionPc);return;}
				fscc(op);return;
			case 2:fbcc(op,false);return;
			case 3:fbcc(op,true);return;
			case 4:fsave(op);return;
			case 5:frestore(op);return;
			default:throw new M68kCpuException(instructionPc,String.format("unsupported FPU instruction $%04X",op));
		}
	}

	private void general(int op,int instructionPc) throws M68kException {
		int ext=cpu.nextWord();
		switch(ext>>>13){
			case 0:{ // FPm -> FPn
				fpiar=instructionPc;
				arithmetic(ext,fp[(ext>>>10)&7],instructionPc);return;}
			case 2:{ // <ea> -> FPn, or FMOVECR
				fpiar=instructionPc;
				int format=(ext>>>10)&7;
				if(format==7){int n=(ext>>>7)&7;fp[n]=round(constant(ext&0x7f));setConditionCodes(fp[n]);return;}
				arithmetic(ext,readOperand(op&0x3f,format,instructionPc),instructionPc);return;}
			case 3: // FMOVE FPn -> <ea>
				fpiar=instructionPc;
				writeOperand(op&0x3f,(ext>>>10)&7,fp[(ext>>>7)&7],ext,instructionPc);return;
			case 4:case 5:moveControlRegisters(op,ext,instructionPc);return;
			case 6:case 7:movem(op,ext,instructionPc);return;
			default:throw new M68kCpuException(instructionPc,String.format("unsupported FPU instruction $%04X $%04X",op,ext));
		}
	}

	// ------------------------------------------------------------------ arithmetic

	private void arithmetic(int ext,double src,int instructionPc) throws M68kCpuException {
		int n=(ext>>>7)&7,opmode=ext&0x7f;
		double dst=fp[n],result;
		int precision=(fpcr>>>6)&3;
		fpsr&=~0xff00; // exception status byte is cleared by every arithmetic instruction
		switch(opmode){
			case 0x00:result=src;break;                                   // FMOVE
			case 0x01:result=roundToIntegral(src,(fpcr>>>4)&3);break;     // FINT
			case 0x02:result=Math.sinh(src);break;                        // FSINH
			case 0x03:result=roundToIntegral(src,ROUND_ZERO);break;       // FINTRZ
			case 0x04:result=Math.sqrt(src);break;                        // FSQRT
			case 0x06:result=Math.log1p(src);break;                       // FLOGNP1
			case 0x08:result=Math.expm1(src);break;                       // FETOXM1
			case 0x09:result=Math.tanh(src);break;                        // FTANH
			case 0x0a:result=Math.atan(src);break;                        // FATAN
			case 0x0c:result=Math.asin(src);break;                        // FASIN
			case 0x0d:result=0.5*Math.log((1+src)/(1-src));break;         // FATANH
			case 0x0e:result=Math.sin(src);break;                         // FSIN
			case 0x0f:result=Math.tan(src);break;                         // FTAN
			case 0x10:result=Math.exp(src);break;                         // FETOX
			case 0x11:result=Math.pow(2,src);break;                       // FTWOTOX
			case 0x12:result=Math.pow(10,src);break;                      // FTENTOX
			case 0x14:result=Math.log(src);break;                         // FLOGN
			case 0x15:result=Math.log10(src);break;                       // FLOG10
			case 0x16:result=Math.log(src)/LN2;break;                     // FLOG2
			case 0x18:result=Math.abs(src);break;                         // FABS
			case 0x19:result=Math.cosh(src);break;                        // FCOSH
			case 0x1a:result=-src;break;                                  // FNEG
			case 0x1c:result=Math.acos(src);break;                        // FACOS
			case 0x1d:result=Math.cos(src);break;                         // FCOS
			case 0x1e:result=getExponent(src);break;                      // FGETEXP
			case 0x1f:result=getMantissa(src);break;                      // FGETMAN
			case 0x20:if(src==0&&!Double.isNaN(dst)&&dst!=0&&!Double.isInfinite(dst))fpsr|=EXC_DZ|AEXC_DZ;result=dst/src;break; // FDIV
			case 0x21:result=modulo(dst,src,false);break;                  // FMOD
			case 0x22:result=dst+src;break;                               // FADD
			case 0x23:result=dst*src;break;                               // FMUL
			case 0x24:if(src==0&&dst!=0&&!Double.isNaN(dst)&&!Double.isInfinite(dst))fpsr|=EXC_DZ|AEXC_DZ;result=(float)dst/(float)src;break; // FSGLDIV
			case 0x25:result=modulo(dst,src,true);break;                   // FREM
			case 0x26:result=scale(dst,src);break;                        // FSCALE
			case 0x27:result=(float)dst*(float)src;break;                 // FSGLMUL
			case 0x28:result=dst-src;break;                               // FSUB
			case 0x30:case 0x31:case 0x32:case 0x33:case 0x34:case 0x35:case 0x36:case 0x37: // FSINCOS: cos -> FPc, sin -> FPs
				fp[opmode&7]=round(Math.cos(src));result=Math.sin(src);break;
			case 0x38:compare(dst,src);return;                            // FCMP
			case 0x3a:setConditionCodes(src);return;                      // FTST
			// 68040 rounded variants
			case 0x40:result=src;precision=PREC_SINGLE;break;             // FSMOVE
			case 0x41:result=src;precision=PREC_DOUBLE;break;             // FDMOVE
			case 0x44:result=Math.sqrt(src);precision=PREC_SINGLE;break;  // FSSQRT
			case 0x45:result=Math.sqrt(src);precision=PREC_DOUBLE;break;  // FDSQRT
			case 0x58:result=Math.abs(src);precision=PREC_SINGLE;break;   // FSABS
			case 0x5a:result=-src;precision=PREC_SINGLE;break;            // FSNEG
			case 0x5c:result=Math.abs(src);precision=PREC_DOUBLE;break;   // FDABS
			case 0x5e:result=-src;precision=PREC_DOUBLE;break;            // FDNEG
			case 0x60:result=dst/src;precision=PREC_SINGLE;break;         // FSDIV
			case 0x62:result=dst+src;precision=PREC_SINGLE;break;         // FSADD
			case 0x63:result=dst*src;precision=PREC_SINGLE;break;         // FSMUL
			case 0x64:result=dst/src;precision=PREC_DOUBLE;break;         // FDDIV
			case 0x66:result=dst+src;precision=PREC_DOUBLE;break;         // FDADD
			case 0x67:result=dst*src;precision=PREC_DOUBLE;break;         // FDMUL
			case 0x68:result=dst-src;precision=PREC_SINGLE;break;         // FSSUB
			case 0x6c:result=dst-src;precision=PREC_DOUBLE;break;         // FDSUB
			default:throw new M68kCpuException(instructionPc,String.format("unsupported FPU operation $%02X",opmode));
		}
		if(precision==PREC_SINGLE)result=(float)result;
		if(Double.isNaN(result)&&!Double.isNaN(src)&&!Double.isNaN(dst))fpsr|=EXC_OPERR|AEXC_IOP;
		else if(Double.isInfinite(result)&&!Double.isInfinite(src)&&!Double.isInfinite(dst)&&(fpsr&EXC_DZ)==0)fpsr|=EXC_OVFL|AEXC_OVFL;
		fp[n]=result;
		setConditionCodes(result);
	}

	/** Results are rounded to the FPCR precision (single or double; extended is the double the registers hold). */
	private double round(double value){return ((fpcr>>>6)&3)==PREC_SINGLE?(float)value:value;}

	private static double roundToIntegral(double value,int mode){
		if(Double.isNaN(value)||Double.isInfinite(value))return value;
		double r;
		switch(mode){
			case ROUND_ZERO:r=value<0?Math.ceil(value):Math.floor(value);break;
			case ROUND_MINUS:r=Math.floor(value);break;
			case ROUND_PLUS:r=Math.ceil(value);break;
			default:r=Math.rint(value);
		}
		return r==0?Math.copySign(0.0,value):r; // the sign of a zero result follows the operand
	}

	private double getExponent(double value){
		if(Double.isNaN(value))return value;
		if(Double.isInfinite(value)){fpsr|=EXC_OPERR|AEXC_IOP;return Double.NaN;}
		if(value==0)return value;
		return unbiasedExponent(value);
	}
	private double getMantissa(double value){
		if(Double.isNaN(value))return value;
		if(Double.isInfinite(value)){fpsr|=EXC_OPERR|AEXC_IOP;return Double.NaN;}
		if(value==0)return value;
		return Math.scalb(value,-unbiasedExponent(value));
	}
	private static int unbiasedExponent(double value){
		int e=Math.getExponent(value);
		if(e==Double.MIN_EXPONENT-1)e=Math.getExponent(value*0x1p64)-64; // subnormal
		return e;
	}
	private static double scale(double value,double factor){
		if(Double.isNaN(value)||Double.isNaN(factor))return Double.NaN;
		if(Double.isInfinite(factor))return Double.NaN;
		if(Double.isInfinite(value)||value==0)return value;
		double n=factor<0?Math.ceil(factor):Math.floor(factor);
		return Math.scalb(value,(int)Math.max(-100000,Math.min(100000,n)));
	}
	/** FMOD (quotient truncated toward zero) and FREM (quotient rounded to nearest even); the quotient's low 7 bits and sign go to FPSR. */
	private double modulo(double dst,double src,boolean nearest){
		fpsr&=~0x00ff0000;
		if(Double.isNaN(dst)||Double.isNaN(src)||Double.isInfinite(dst)||src==0){fpsr|=EXC_OPERR|AEXC_IOP;return Double.NaN;}
		if(Double.isInfinite(src))return dst;
		double q=dst/src;
		q=nearest?Math.rint(q):q<0?Math.ceil(q):Math.floor(q);
		double r=nearest?Math.IEEEremainder(dst,src):dst%src;
		if(r==0)r=Math.copySign(0.0,dst);
		long quotient=(long)Math.abs(q);
		fpsr|=(int)((quotient&0x7f)<<16);if(q<0)fpsr|=1<<23;
		return r;
	}

	private void setConditionCodes(double value){
		fpsr&=~(CC_N|CC_Z|CC_I|CC_NAN);
		if(Double.isNaN(value)){fpsr|=CC_NAN;if(Double.doubleToRawLongBits(value)<0)fpsr|=CC_N;return;}
		if(Double.doubleToRawLongBits(value)<0)fpsr|=CC_N;
		if(value==0)fpsr|=CC_Z;
		if(Double.isInfinite(value))fpsr|=CC_I;
	}

	/** FCMP: the condition codes of FPn - source, with equal infinities compared equal. */
	private void compare(double dst,double src){
		if(Double.isNaN(dst)||Double.isNaN(src)){setConditionCodes(Double.NaN);return;}
		if(Double.isInfinite(dst)&&Double.isInfinite(src)&&dst==src){fpsr&=~(CC_N|CC_Z|CC_I|CC_NAN);fpsr|=CC_Z;if(dst<0)fpsr|=CC_N;return;}
		double diff=dst-src; // equal operands give +0 except -0 - (+0) = -0, whose N bit the chip reports too
		setConditionCodes(diff);
	}

	/** The condition predicate of FBcc/FScc/FDBcc/FTRAPcc on the current condition codes (the "signalling" forms 16-31 test the same conditions). */
	private boolean condition(int predicate){
		boolean n=(fpsr&CC_N)!=0,z=(fpsr&CC_Z)!=0,nan=(fpsr&CC_NAN)!=0;
		if(predicate>=16&&nan)fpsr|=EXC_BSUN|AEXC_IOP;
		switch(predicate&15){
			case 0:return false;                     // F / SF
			case 1:return z;                         // EQ / SEQ
			case 2:return !(nan||z||n);              // OGT / GT
			case 3:return z||!(nan||n);              // OGE / GE
			case 4:return n&&!(nan||z);              // OLT / LT
			case 5:return z||(n&&!nan);              // OLE / LE
			case 6:return !(nan||z);                 // OGL / GL
			case 7:return !nan;                      // OR / GLE
			case 8:return nan;                       // UN / NGLE
			case 9:return nan||z;                    // UEQ / NGL
			case 10:return nan||!(n||z);             // UGT / NLE
			case 11:return nan||z||!n;               // UGE / NLT
			case 12:return nan||(n&&!z);             // ULT / NGE
			case 13:return nan||z||n;                // ULE / NGT
			case 14:return !z;                       // NE / SNE
			default:return true;                     // T / ST
		}
	}

	/** FMOVECR constants (offsets of the on-chip ROM). */
	private static double constant(int offset){
		switch(offset){
			case 0x00:return Math.PI;
			case 0x0b:return Math.log10(2);
			case 0x0c:return Math.E;
			case 0x0d:return 1/LN2;              // log2(e)
			case 0x0e:return Math.log10(Math.E);
			case 0x0f:return 0.0;
			case 0x30:return LN2;
			case 0x31:return LN10;
			case 0x32:return 1e0;case 0x33:return 1e1;case 0x34:return 1e2;case 0x35:return 1e4;case 0x36:return 1e8;
			case 0x37:return 1e16;case 0x38:return 1e32;case 0x39:return 1e64;case 0x3a:return 1e128;case 0x3b:return 1e256;
			case 0x3c:case 0x3d:case 0x3e:case 0x3f:return Double.POSITIVE_INFINITY; // 1e512 and beyond exceed the double range
			default:return 0.0;
		}
	}

	// ------------------------------------------------------------------ operands

	private double readOperand(int spec,int format,int instructionPc) throws M68kException {
		int address=cpu.operandAddress(spec,FORMAT_SIZE[format]);
		if(address==-1){ // Dn (An is not allowed as a source): integer, single and (68040) the low bits of the register
			int value=cpu.operandMode()==0?cpu.d[cpu.operandRegister()]:cpu.a[cpu.operandRegister()];
			switch(format){
				case FORMAT_LONG:return value;
				case FORMAT_SINGLE:return Float.intBitsToFloat(value);
				case FORMAT_WORD:return (short)value;
				case FORMAT_BYTE:return (byte)value;
				default:throw new M68kCpuException(instructionPc,"FPU register source of a memory-only format");
			}
		}
		switch(format){
			case FORMAT_LONG:return memory.readLong(address);
			case FORMAT_SINGLE:return Float.intBitsToFloat(memory.readLong(address));
			case FORMAT_EXTENDED:return readExtended(address);
			case FORMAT_PACKED:return readPacked(address);
			case FORMAT_WORD:return (short)memory.readWord(address);
			case FORMAT_DOUBLE:return Double.longBitsToDouble(((long)memory.readLong(address)<<32)|(memory.readLong(address+4)&0xffffffffL));
			case FORMAT_BYTE:return (byte)memory.readByte(address);
			default:throw new M68kCpuException(instructionPc,"invalid FPU source format");
		}
	}

	private void writeOperand(int spec,int format,double value,int ext,int instructionPc) throws M68kException {
		fpsr&=~0xff00;
		int address=cpu.operandAddress(spec,FORMAT_SIZE[format]);
		if(address==-1){
			if(cpu.operandMode()!=0)throw new M68kCpuException(instructionPc,"FPU destination An");
			int reg=cpu.operandRegister();
			switch(format){
				case FORMAT_LONG:cpu.d[reg]=toInteger(value,4);return;
				case FORMAT_SINGLE:cpu.d[reg]=Float.floatToRawIntBits((float)value);return;
				case FORMAT_WORD:cpu.d[reg]=(cpu.d[reg]&0xffff0000)|(toInteger(value,2)&0xffff);return;
				case FORMAT_BYTE:cpu.d[reg]=(cpu.d[reg]&0xffffff00)|(toInteger(value,1)&0xff);return;
				default:throw new M68kCpuException(instructionPc,"FPU register destination of a memory-only format");
			}
		}
		switch(format){
			case FORMAT_LONG:memory.writeLong(address,toInteger(value,4));return;
			case FORMAT_SINGLE:memory.writeLong(address,Float.floatToRawIntBits((float)value));return;
			case FORMAT_EXTENDED:writeExtended(address,value);return;
			case FORMAT_PACKED:writePacked(address,value,(byte)(ext<<1)>>1);return;                  // static k-factor (7-bit signed)
			case FORMAT_PACKED_DYNAMIC:writePacked(address,value,(byte)(cpu.d[(ext>>>4)&7]<<1)>>1);return; // k-factor in Dn
			case FORMAT_WORD:memory.writeWord(address,toInteger(value,2));return;
			case FORMAT_DOUBLE:{long bits=Double.doubleToRawLongBits(value);memory.writeLong(address,(int)(bits>>>32));memory.writeLong(address+4,(int)bits);return;}
			case FORMAT_BYTE:memory.writeByte(address,toInteger(value,1));return;
			default:throw new M68kCpuException(instructionPc,"invalid FPU destination format");
		}
	}

	/** Integer conversion with the FPCR rounding mode; NaNs and out-of-range values set OPERR and saturate. */
	private int toInteger(double value,int size){
		long max=size==4?Integer.MAX_VALUE:size==2?Short.MAX_VALUE:Byte.MAX_VALUE,min=-max-1;
		if(Double.isNaN(value)){fpsr|=EXC_OPERR|AEXC_IOP;return (int)min;}
		double r=roundToIntegral(value,(fpcr>>>4)&3);
		if(r>max){fpsr|=EXC_OPERR|AEXC_IOP;return (int)max;}
		if(r<min){fpsr|=EXC_OPERR|AEXC_IOP;return (int)min;}
		if(r!=value)fpsr|=EXC_INEX2|AEXC_INEX;
		return (int)r;
	}

	// ------------------------------------------------------------------ extended and packed formats

	/** 96-bit extended: sign and 15-bit exponent (bias 16383), 16 unused bits, 64-bit mantissa with explicit integer bit. */
	double readExtended(int address) throws M68kCpuException {
		int se=memory.readWord(address);
		long mantissa=((long)memory.readLong(address+4)<<32)|(memory.readLong(address+8)&0xffffffffL);
		boolean negative=(se&0x8000)!=0;int exponent=se&0x7fff;
		double value;
		if(exponent==0x7fff)value=(mantissa&0x7fffffffffffffffL)==0?Double.POSITIVE_INFINITY:Double.NaN;
		else if(mantissa==0)value=0.0;
		else{
			double m=mantissa<0?(double)(mantissa>>>1)*2:(double)mantissa; // unsigned 64-bit -> double
			value=Math.scalb(m,exponent-16383-63);
		}
		return negative?-value:value;
	}

	void writeExtended(int address,double value) throws M68kCpuException {
		int se;long mantissa;
		if(Double.isNaN(value)){se=Double.doubleToRawLongBits(value)<0?0xffff:0x7fff;mantissa=-1L;}
		else if(Double.isInfinite(value)){se=value<0?0xffff:0x7fff;mantissa=0;}
		else if(value==0){se=Double.doubleToRawLongBits(value)<0?0x8000:0;mantissa=0;}
		else{
			boolean negative=value<0;double m=Math.abs(value);
			int e=Math.getExponent(m);
			if(e==Double.MIN_EXPONENT-1){m*=0x1p64;e=Math.getExponent(m)-64;} // subnormal doubles are normal extended values
			long significand=(Double.doubleToRawLongBits(m)&0xfffffffffffffL)|(1L<<52);
			mantissa=significand<<11;
			se=(e+16383)|(negative?0x8000:0);
		}
		memory.writeWord(address,se);memory.writeWord(address+2,0);
		memory.writeLong(address+4,(int)(mantissa>>>32));memory.writeLong(address+8,(int)mantissa);
	}

	/**
	 * Packed decimal: bit 31 mantissa sign, bit 30 exponent sign, bits 27-16 three BCD exponent digits (a fourth one in
	 * bits 15-12 for out-of-range results), bits 3-0 the integer digit, then 16 fraction digits in the next two longs.
	 */
	double readPacked(int address) throws M68kCpuException {
		int w0=memory.readLong(address),w1=memory.readLong(address+4),w2=memory.readLong(address+8);
		boolean negative=w0<0,negativeExponent=(w0&0x40000000)!=0;
		int exponentField=(w0>>>16)&0xfff;
		if(exponentField==0xfff){double v=w1==0&&w2==0?Double.POSITIVE_INFINITY:Double.NaN;return negative?-v:v;}
		int exponent=bcd(exponentField>>>8)*100+bcd(exponentField>>>4)*10+bcd(exponentField); // the fourth digit is an output-only overflow indicator
		StringBuilder digits=new StringBuilder(24);
		digits.append((char)('0'+bcd(w0)));digits.append('.');
		for(int i=7;i>=0;i--)digits.append((char)('0'+bcd(w1>>>(i*4))));
		for(int i=7;i>=0;i--)digits.append((char)('0'+bcd(w2>>>(i*4))));
		digits.append('E').append(negativeExponent?-exponent:exponent);
		double value;
		try{value=new BigDecimal(digits.toString()).doubleValue();}catch(NumberFormatException e){fpsr|=EXC_OPERR|AEXC_IOP;value=Double.NaN;}
		return negative?-value:value;
	}
	private static int bcd(int nibble){return Math.min(9,nibble&15);}

	/** k-factor: k > 0 = number of significant digits (at most 17); k <= 0 = -k digits after the decimal point. */
	void writePacked(int address,double value,int k) throws M68kCpuException {
		int w0,w1=0,w2=0;
		boolean negative=Double.doubleToRawLongBits(value)<0;
		if(Double.isNaN(value)){w0=0x7fff0000;w1=w2=-1;}
		else if(Double.isInfinite(value)){w0=0x7fff0000;}
		else if(value==0){w0=0;}
		else{
			BigDecimal bd=new BigDecimal(Math.abs(value));
			if(k>0)bd=bd.round(new MathContext(Math.min(k,17),RoundingMode.HALF_EVEN));
			else{
				bd=bd.setScale(Math.min(-k,17),RoundingMode.HALF_EVEN);
				if(bd.precision()>17){bd=bd.round(new MathContext(17,RoundingMode.HALF_EVEN));fpsr|=EXC_OPERR|AEXC_IOP;}
			}
			if(bd.signum()==0)bd=BigDecimal.ZERO;
			BigInteger unscaled=bd.unscaledValue();String digits=unscaled.toString();
			int exponent=digits.length()-1-bd.scale();
			if(bd.signum()==0)exponent=0;
			while(digits.length()<17)digits+="0";
			digits=digits.substring(0,17);
			int magnitude=Math.abs(exponent);
			if(magnitude>9999){fpsr|=EXC_OPERR|AEXC_IOP;magnitude=9999;}
			w0=(exponent<0?0x40000000:0)|((magnitude/100%10)<<24)|((magnitude/10%10)<<20)|((magnitude%10)<<16)|((magnitude/1000)<<12)|(digits.charAt(0)-'0');
			for(int i=0;i<8;i++)w1=(w1<<4)|(digits.charAt(1+i)-'0');
			for(int i=0;i<8;i++)w2=(w2<<4)|(digits.charAt(9+i)-'0');
		}
		if(negative)w0|=0x80000000;
		memory.writeLong(address,w0);memory.writeLong(address+4,w1);memory.writeLong(address+8,w2);
	}

	// ------------------------------------------------------------------ moves of several registers

	/** FMOVE/FMOVEM of FPCR (bit 12), FPSR (11) and FPIAR (10); memory order FPCR, FPSR, FPIAR ascending. */
	private void moveControlRegisters(int op,int ext,int instructionPc) throws M68kException {
		boolean toMemory=(ext&0x2000)!=0;int mask=(ext>>>10)&7;int count=Integer.bitCount(mask);
		int address=cpu.operandAddress(op&0x3f,4*Math.max(1,count));
		if(address==-1){ // Dn (any single register) or An (FPIAR only)
			int reg=cpu.operandRegister();int[] regs=cpu.operandMode()==0?cpu.d:cpu.a;
			if(count!=1)throw new M68kCpuException(instructionPc,"FMOVEM of several control registers with a register operand");
			if(toMemory)regs[reg]=mask==4?fpcr:mask==2?fpsr:fpiar;
			else{int v=regs[reg];if(mask==4)fpcr=v&0xfff0;else if(mask==2)fpsr=v&0x0ffffff8;else fpiar=v;}
			return;
		}
		if((mask&4)!=0){if(toMemory)memory.writeLong(address,fpcr);else fpcr=memory.readLong(address)&0xfff0;address+=4;}
		if((mask&2)!=0){if(toMemory)memory.writeLong(address,fpsr);else fpsr=memory.readLong(address)&0x0ffffff8;address+=4;}
		if((mask&1)!=0){if(toMemory)memory.writeLong(address,fpiar);else fpiar=memory.readLong(address);}
	}

	/**
	 * FMOVEM of data registers in extended format. Bit 13: 1 = registers to memory; bits 12-11: 00 static list with
	 * predecrement, 01 dynamic (list in Dn) predecrement, 10 static postincrement/control, 11 dynamic postincrement.
	 * The static list has FP0 in bit 7 for the postincrement/control forms and in bit 0 for the predecrement forms; the
	 * memory image is FP0 at the lowest address either way.
	 */
	private void movem(int op,int ext,int instructionPc) throws M68kException {
		boolean toMemory=(ext&0x2000)!=0,predecrement=(ext&0x1000)==0,dynamic=(ext&0x0800)!=0;
		int mask=dynamic?cpu.d[(ext>>>4)&7]&0xff:ext&0xff;
		int spec=op&0x3f,mode=(spec>>>3)&7,reg=spec&7;
		if(mode<2||mode==7&&reg==4)throw new M68kCpuException(instructionPc,"FMOVEM with a register or immediate operand");
		if(predecrement){
			if(mode!=4)throw new M68kCpuException(instructionPc,"FMOVEM predecrement form without -(An)");
			if(!toMemory)throw new M68kCpuException(instructionPc,"FMOVEM memory to registers with -(An)");
			for(int i=7;i>=0;i--)if((mask&(1<<i))!=0){cpu.a[reg]-=12;writeExtended(cpu.a[reg],fp[i]);}
			return;
		}
		int address=mode==3?cpu.a[reg]:cpu.operandAddress(spec,12);
		if(mode==3)cpu.a[reg]=address; // operandAddress is not used for postincrement: the step depends on the list
		for(int i=0;i<8;i++)if((mask&(0x80>>>i))!=0){
			if(toMemory)writeExtended(address,fp[i]);else fp[i]=readExtended(address);
			address+=12;
		}
		if(mode==3)cpu.a[reg]=address;
	}

	// ------------------------------------------------------------------ conditionals and state frames

	private void fbcc(int op,boolean longDisplacement) throws M68kCpuException {
		int base=cpu.pc;
		int displacement=longDisplacement?cpu.nextLong():(short)cpu.nextWord();
		if(condition(op&0x3f))cpu.pc=base+displacement;
	}

	private void fscc(int op) throws M68kException {
		int predicate=cpu.nextWord()&0x3f;
		int address=cpu.operandAddress(op&0x3f,1);
		int value=condition(predicate)?0xff:0;
		if(address==-1){int reg=cpu.operandRegister();cpu.d[reg]=(cpu.d[reg]&0xffffff00)|value;}
		else memory.writeByte(address,value);
	}

	/** FDBcc Dn,label: the displacement follows the predicate word and is relative to that word. */
	private void fdbcc(int op,int instructionPc) throws M68kCpuException {
		int predicate=cpu.nextWord()&0x3f;
		int base=instructionPc+2,displacement=(short)cpu.nextWord();
		if(condition(predicate))return;
		int reg=op&7,low=(cpu.d[reg]-1)&0xffff;
		cpu.d[reg]=(cpu.d[reg]&0xffff0000)|low;
		if(low!=0xffff)cpu.pc=base+displacement;
	}

	private void ftrapcc(int op,int instructionPc) throws M68kCpuException {
		int predicate=cpu.nextWord()&0x3f;
		int extra=(op&7)==2?2:(op&7)==3?4:0;cpu.pc+=extra;
		if(condition(predicate))throw new M68kCpuException(instructionPc,"FTRAPcc");
	}

	/** FSAVE writes the 4-byte null frame (version byte 0): the unit has no internal state to preserve. */
	private void fsave(int op) throws M68kException {
		int address=cpu.operandAddress(op&0x3f,4);
		memory.writeLong(address,0);
	}

	/** FRESTORE: a null frame resets the unit; any other frame (an idle frame this unit never writes) is skipped by its size byte. */
	private void frestore(int op) throws M68kException {
		int spec=op&0x3f,mode=(spec>>>3)&7,reg=spec&7;
		int address=mode==3?cpu.a[reg]:cpu.operandAddress(spec,4);
		int header=memory.readLong(address);
		int version=header>>>24,size=(header>>>16)&0xff;
		if(version==0)reset();
		if(mode==3)cpu.a[reg]=address+(version==0?4:4+size);
	}
}

/**
 * The QuickTime math dispatch (_QuickTimeMath $ABC2, D0 = selector 1..$25): the Comp (64-bit "wide")
 * arithmetic, FixMulDiv/FixExp2/FixLog2/FixPow/FracSinCos and the MatrixRecord calls of
 * ImageCompression.h (Pascal calling convention). iTunes' MP3 player and visualizer call these.
 * A MatrixRecord is 3x3 Fixed in row vector form: x' = a*x + c*y + tx, y' = b*x + d*y + ty; the third
 * column (perspective u, v and w) are Fract (2.30), w = $40000000 for an affine matrix.
 */
final class QuickTimeMath68k {
	private static final int ONE=0x10000,FRACT_ONE=0x40000000;
	private final M68kMemory memory;

	QuickTimeMath68k(M68kMemory memory){this.memory=memory;}

	boolean handle(M68kCpu cpu) throws M68kException {
		int sel=cpu.d[0]&0xff,sp=cpu.a[7];
		switch(sel){
			case 0x01:{long s=wide(l(sp+4));int d=l(sp);wide(d,wide(d)+s);return done(cpu,8,0);} // CompAdd(src, dst)
			case 0x02:{long s=wide(l(sp+4));int d=l(sp);wide(d,wide(d)-s);return done(cpu,8,0);} // CompSub
			case 0x03:{int d=l(sp);wide(d,-wide(d));return done(cpu,4,0);} // CompNeg
			case 0x04:{int p=l(sp+2),n=(short)memory.readWord(sp);long v=wide(p); // CompShift: positive = left
				v=n>=0?(n>=64?0:v<<n):(-n>=64?(v<0?-1:0):v>>-n);wide(p,v);return done(cpu,6,0);}
			case 0x05:{long a=l(sp+8),b=l(sp+4);wide(l(sp),a*b);return done(cpu,12,0);} // CompMul(src1, src2, dst)
			case 0x06:{long v=wide(l(sp+8));int d=l(sp+4),rem=l(sp);int q;if(d==0)q=0x7fffffff;else{long qq=v/d;q=(int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,qq));if(rem!=0)memory.writeLong(rem,(int)(v%d));}
				memory.writeLong(sp+12,q);return done(cpu,12,-1);} // CompDiv(numerator, denominator, remainder): long
			// CompFixMul: the 96-bit product, not s*f in 64 bits - a time base's "no stop time" (0x7FFFFFFF_FFFFFFFF) times rate 1.0
			// wrapped to -1 there, so every slave time base was pinned at -1 and QuickTime music played its notes seconds apart
			case 0x07:{long s=wide(l(sp+8));int f=l(sp+4);wide(l(sp),saturate(java.math.BigInteger.valueOf(s).multiply(java.math.BigInteger.valueOf(f)).shiftRight(16)));return done(cpu,12,0);}
			case 0x08:{int co=l(sp+8),mul=l(sp+4),div=l(sp);wide(co,mulDiv(wide(co),mul,div,true));return done(cpu,12,0);} // CompMulDiv
			case 0x0c:{int co=l(sp+12),mul=l(sp+8),div=l(sp+4),rem=l(sp);java.math.BigInteger v=java.math.BigInteger.valueOf(wide(co)).multiply(java.math.BigInteger.valueOf(mul)); // CompMulDivTrunc (exact product, as CompFixMul)
				if(div!=0){java.math.BigInteger[] qr=v.divideAndRemainder(java.math.BigInteger.valueOf(div));if(rem!=0)memory.writeLong(rem,qr[1].intValue());wide(co,saturate(qr[0]));}return done(cpu,16,0);}
			case 0x09:{memory.writeLong(sp+8,Long.compare(wide(l(sp+4)),wide(l(sp))));return done(cpu,8,-1);} // CompCompare
			case 0x11:{long v=wide(l(sp));memory.writeLong(sp+4,v<=0?0:(int)(long)Math.floor(Math.sqrt((double)v)));return done(cpu,4,-1);} // CompSquareRoot
			case 0x0a:{memory.writeLong(sp+12,(int)clamp(mulDiv(l(sp+8),l(sp+4),l(sp),true)));return done(cpu,12,-1);} // FixMulDiv
			case 0x0d:{long a=l(sp+8)&0xffffffffL,b=l(sp+4)&0xffffffffL,d=l(sp)&0xffffffffL;memory.writeLong(sp+12,d==0?-1:(int)Math.min(0xffffffffL,(a*b+d/2)/d));return done(cpu,12,-1);} // UnsignedFixMulDiv
			case 0x0b:{double r=Math.toRadians(l(sp+4)/(double)ONE);memory.writeLong(l(sp),(int)Math.round(Math.cos(r)*FRACT_ONE));memory.writeLong(sp+8,(int)Math.round(Math.sin(r)*FRACT_ONE));return done(cpu,8,-1);} // FracSinCos
			case 0x0e:{double v=Math.pow(2,l(sp)/(double)ONE);memory.writeLong(sp+4,(int)clamp(Math.round(v*ONE)));return done(cpu,4,-1);} // FixExp2
			case 0x0f:{int x=l(sp);memory.writeLong(sp+4,x<=0?0x80000000:(int)Math.round(Math.log(x/(double)ONE)/Math.log(2)*ONE));return done(cpu,4,-1);} // FixLog2
			case 0x10:{double b=l(sp+4)/(double)ONE,e=l(sp)/(double)ONE;double v=Math.pow(b,e);memory.writeLong(sp+8,Double.isNaN(v)?0:(int)clamp(Math.round(v*ONE)));return done(cpu,8,-1);} // FixPow
			case 0x14:{int m=l(sp);memory.writeWord(sp+4,matrixType(m));return done(cpu,4,-1);} // GetMatrixType
			case 0x15:{identity(l(sp));return done(cpu,4,0);} // SetIdentityMatrix
			case 0x16:{int m=l(sp+12);double a=Math.toRadians(l(sp+8)/(double)ONE),ax=l(sp+4)/(double)ONE,ay=l(sp)/(double)ONE;double c=Math.cos(a),s=Math.sin(a); // RotateMatrix(m, degrees, aboutX, aboutY)
				concat(m,about(c,s,-s,c,ax,ay));return done(cpu,16,0);}
			case 0x17:{int m=l(sp+16);double sx=l(sp+12)/(double)ONE,sy=l(sp+8)/(double)ONE,ax=l(sp+4)/(double)ONE,ay=l(sp)/(double)ONE; // ScaleMatrix
				concat(m,about(sx,0,0,sy,ax,ay));return done(cpu,20,0);}
			case 0x18:{int m=l(sp+16);double kx=l(sp+12)/(double)ONE,ky=l(sp+8)/(double)ONE,ax=l(sp+4)/(double)ONE,ay=l(sp)/(double)ONE; // SkewMatrix
				concat(m,about(1,ky,kx,1,ax,ay));return done(cpu,20,0);}
			case 0x19:{int m=l(sp+8);double[] t={1,0,0,0,1,0,l(sp+4)/(double)ONE,l(sp)/(double)ONE,1};concat(m,t);return done(cpu,12,0);} // TranslateMatrix
			case 0x1b:{int a=l(sp+4),b=l(sp);double[] ma=read(a),mb=read(b);write(b,mul(ma,mb));return done(cpu,8,0);} // ConcatMatrix(a, b): b = a x b
			case 0x1c:{int m=l(sp+4),im=l(sp);double[] inv=invert(read(m));if(inv!=null)write(im,inv);memory.writeByte(sp+8,inv!=null?1:0);memory.writeByte(sp+9,0);return done(cpu,8,-1);} // InverseMatrix
			case 0x1d:{int m=l(sp+8);concat(m,mapping(l(sp+4),l(sp)));return done(cpu,12,0);} // MapMatrix(matrix, fromRect, toRect)
			case 0x1e:{write(l(sp+8),mapping(l(sp+4),l(sp)));return done(cpu,12,0);} // RectMatrix(matrix, srcRect, dstRect)
			case 0x20:{int m1=l(sp+4),m2=l(sp);for(int i=0;i<36;i+=4)memory.writeLong(m2+i,memory.readLong(m1+i));return done(cpu,8,0);} // CopyMatrix
			case 0x21:{int m1=l(sp+4),m2=l(sp);boolean eq=true;for(int i=0;i<36;i+=4)if(memory.readLong(m1+i)!=memory.readLong(m2+i))eq=false;
				memory.writeByte(sp+8,eq?1:0);memory.writeByte(sp+9,0);return done(cpu,8,-1);} // EqualMatrix
			case 0x22:case 0x23:{double[] m=read(l(sp+8));int p=l(sp+4),n=l(sp);boolean fixed=sel==0x22; // TransformFixedPoints / TransformPoints(m, pts, count)
				for(int i=0;i<n;i++){
					double x,y;int at=p+i*(fixed?8:4);
					if(fixed){x=memory.readLong(at)/(double)ONE;y=memory.readLong(at+4)/(double)ONE;}else{y=(short)memory.readWord(at);x=(short)memory.readWord(at+2);} // Point is {v, h}
					double[] r=apply(m,x,y);
					if(fixed){memory.writeLong(at,(int)Math.round(r[0]*ONE));memory.writeLong(at+4,(int)Math.round(r[1]*ONE));}else{memory.writeWord(at,(int)Math.round(r[1]));memory.writeWord(at+2,(int)Math.round(r[0]));}
				}
				memory.writeWord(sp+12,0);return done(cpu,12,-1);}
			case 0x24:case 0x25:{double[] m=read(l(sp+8));int r=l(sp+4),fpp=l(sp);boolean fixed=sel==0x24; // TransformFixedRect / TransformRect(m, rect, fixedPoint[4])
				double left,top,right,bottom;
				if(fixed){left=memory.readLong(r)/(double)ONE;top=memory.readLong(r+4)/(double)ONE;right=memory.readLong(r+8)/(double)ONE;bottom=memory.readLong(r+12)/(double)ONE;}
				else{top=(short)memory.readWord(r);left=(short)memory.readWord(r+2);bottom=(short)memory.readWord(r+4);right=(short)memory.readWord(r+6);}
				double[][] c={apply(m,left,top),apply(m,right,top),apply(m,right,bottom),apply(m,left,bottom)};
				double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
				for(int i=0;i<4;i++){minX=Math.min(minX,c[i][0]);maxX=Math.max(maxX,c[i][0]);minY=Math.min(minY,c[i][1]);maxY=Math.max(maxY,c[i][1]);
					if(fpp!=0){memory.writeLong(fpp+i*8,(int)Math.round(c[i][0]*ONE));memory.writeLong(fpp+i*8+4,(int)Math.round(c[i][1]*ONE));}}
				if(fixed){memory.writeLong(r,(int)Math.round(minX*ONE));memory.writeLong(r+4,(int)Math.round(minY*ONE));memory.writeLong(r+8,(int)Math.round(maxX*ONE));memory.writeLong(r+12,(int)Math.round(maxY*ONE));}
				else{memory.writeWord(r,(int)Math.round(minY));memory.writeWord(r+2,(int)Math.round(minX));memory.writeWord(r+4,(int)Math.round(maxY));memory.writeWord(r+6,(int)Math.round(maxX));}
				memory.writeByte(sp+12,1);memory.writeByte(sp+13,0);return done(cpu,12,-1);}
			default:return false;
		}
	}

	/** Pops the arguments; the result slot (if any) is already written above them. */
	private boolean done(M68kCpu cpu,int argBytes,int ignored){cpu.a[7]+=argBytes;return true;}
	private int l(int addr) throws M68kCpuException {return memory.readLong(addr);}
	private long wide(int p) throws M68kCpuException {return ((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);}
	private void wide(int p,long v) throws M68kCpuException {memory.writeLong(p,(int)(v>>>32));memory.writeLong(p+4,(int)v);}
	private static long clamp(long v){return Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,v));}
	private static long mulDiv(long a,long b,long d,boolean round){
		if(d==0)return a*b<0?Long.MIN_VALUE:Long.MAX_VALUE;
		java.math.BigInteger p=java.math.BigInteger.valueOf(a).multiply(java.math.BigInteger.valueOf(b));
		java.math.BigInteger[] qr=p.add(java.math.BigInteger.valueOf(Math.abs(d)/2*(p.signum()<0?-1:1)*(round?1:0))).divideAndRemainder(java.math.BigInteger.valueOf(d));
		return saturate(qr[0]);
	}
	/** A 64-bit result: values beyond the range pin at its ends instead of wrapping. */
	private static long saturate(java.math.BigInteger v){return v.bitLength()<64?v.longValue():v.signum()<0?Long.MIN_VALUE:Long.MAX_VALUE;}

	private int matrixType(int m) throws M68kCpuException {
		int[] v=new int[9];for(int i=0;i<9;i++)v[i]=memory.readLong(m+i*4);
		if(v[2]!=0||v[5]!=0||v[8]!=FRACT_ONE)return 6; // perspective
		int type=0;
		if(v[6]!=0||v[7]!=0)type|=1;
		if(v[1]!=0||v[3]!=0)type|=4;else if(v[0]!=ONE||v[4]!=ONE)type|=2;
		return type==5?5:type==4?4:type;
	}
	private void identity(int m) throws M68kCpuException {for(int i=0;i<9;i++)memory.writeLong(m+i*4,i==0||i==4?ONE:i==8?FRACT_ONE:0);}
	/** The matrix as doubles (the perspective column in plain units). */
	private double[] read(int m) throws M68kCpuException {
		double[] d=new double[9];
		for(int i=0;i<9;i++)d[i]=memory.readLong(m+i*4)/(double)(i%3==2?FRACT_ONE:ONE);
		return d;
	}
	private void write(int m,double[] d) throws M68kCpuException {
		for(int i=0;i<9;i++)memory.writeLong(m+i*4,(int)clamp(Math.round(d[i]*(i%3==2?FRACT_ONE:ONE))));
	}
	private static double[] mul(double[] a,double[] b){
		double[] r=new double[9];
		for(int i=0;i<3;i++)for(int j=0;j<3;j++){double s=0;for(int k=0;k<3;k++)s+=a[i*3+k]*b[k*3+j];r[i*3+j]=s;}
		return r;
	}
	/** m = m x t: t applies after what m already does. */
	private void concat(int m,double[] t) throws M68kCpuException {write(m,mul(read(m),t));}
	/** The matrix [[a b 0] [c d 0] [tx ty 1]] of a linear map about a point (row-vector form: x' = a*x + c*y). */
	private static double[] about(double a,double b,double c,double d,double ax,double ay){
		return new double[]{a,b,0, c,d,0, ax-(a*ax+c*ay),ay-(b*ax+d*ay),1};
	}
	private double[] mapping(int fromRect,int toRect) throws M68kCpuException {
		double ft=(short)memory.readWord(fromRect),fl=(short)memory.readWord(fromRect+2),fb=(short)memory.readWord(fromRect+4),fr=(short)memory.readWord(fromRect+6);
		double tt=(short)memory.readWord(toRect),tl=(short)memory.readWord(toRect+2),tb=(short)memory.readWord(toRect+4),tr=(short)memory.readWord(toRect+6);
		double sx=fr==fl?1:(tr-tl)/(fr-fl),sy=fb==ft?1:(tb-tt)/(fb-ft);
		return new double[]{sx,0,0, 0,sy,0, tl-fl*sx,tt-ft*sy,1};
	}
	private static double[] apply(double[] m,double x,double y){
		double w=m[2]*x+m[5]*y+m[8];if(w==0)w=1;
		return new double[]{(m[0]*x+m[3]*y+m[6])/w,(m[1]*x+m[4]*y+m[7])/w};
	}
	private static double[] invert(double[] m){
		double a=m[0],b=m[1],c=m[2],d=m[3],e=m[4],f=m[5],g=m[6],h=m[7],i=m[8];
		double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
		if(det==0)return null;
		return new double[]{(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det,(f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det,(d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det};
	}
}

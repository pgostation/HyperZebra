import java.awt.image.BufferedImage;

/**
 * The magnifiers of the smooth zoom ({@link SmoothZoom}): every pixel of a packed 0xAARRGGBB picture becomes a whole s x s
 * block worked out from the pixel and its neighbours (neighbours beyond the picture repeat its edge).
 * <ul>
 * <li>{@link #BRESENHAM}: Bresenham's integer stepping, the classic fast scaler (each source pixel repeated as the error term
 *     says): at the whole factors the planes use, every pixel becomes a solid block - the pixels stay as they were drawn.</li>
 * <li>{@link #BICUBIC}: Catmull-Rom bicubic interpolation (a = -1/2) over the 4x4 neighbourhood, separable.</li>
 * <li>{@link #SCALE2X}: an enhanced Scale2x (EPX) written for this program. Scale2x's rule decides which corners of a pixel
 *     E change: a corner takes its two side neighbours' colour when those two are alike, E is not, and neither matches the
 *     neighbour across from it (B = D, B != F, D != H for the top-left corner). On top of that rule: colours are alike by
 *     YUV thresholds (48 luma, 7 and 6 chroma) instead of exact equality; a corner whose edges go on past both side
 *     neighbours is a rectangle's corner and stays sharp; an edge that goes on past just one of them is a 2:1 slope and
 *     is cut along that slope instead of 45 degrees; and the cut is not a whole quarter of the block but the area beyond
 *     a line through the middles of the pixel's sides, each output pixel mixing in the other colour by the share of its
 *     area beyond the line - so a staircase comes out as an anti-aliased straight edge, at 2x, 3x and 4x directly (no
 *     repeated 2x passes). Straight edges, rectangles, single pixels and checkerboards stay sharp; thin diagonal lines
 *     stay connected.</li>
 * <li>{@link #AUTO}: the enhanced Scale2x for pixel art, bicubic where the picture is dithered or photographic. Scale2x only
 *     ever changes the corners of pixels between two flat colours, so a texture of mixed dots (8-bit dithered sprites, scanned
 *     pictures) comes out as plain blocks; such pixels are told apart by their 3x3 neighbourhood: at least 9 of its 12
 *     side-by-side pairs differ (by a finer likeness than Scale2x's) and it holds three different colours, or two close
 *     ones (a black-and-white pattern - a grey checkerboard - is drawn pixel art and stays sharp); the quieter dots inside
 *     a texture follow when two of their four side neighbours are texture.</li>
 * </ul>
 * Pictures are opaque packed RGB (the planes) or premultiplied ARGB (images drawn through Java2D, {@code alpha} true: the alpha
 * byte then counts as a channel and in the likeness test).
 */
final class SmoothScaler {
	private SmoothScaler(){}
	static final int BRESENHAM=1,BICUBIC=2,SCALE2X=3,AUTO=4;

	/** How many source pixels away a block's colours look: a changed pixel makes its neighbours this far scale again. */
	static int reach(int algorithm){return algorithm==BICUBIC||algorithm==AUTO?2:algorithm==SCALE2X?1:0;}

	/**
	 * Scales pixels {@code x0..x1-1} of row {@code y} of the {@code w x h} picture {@code src} (rows of {@code w}) into {@code dst},
	 * whose rows are {@code dstStride} long: the block of pixel (x,y) is the s x s square at (x*s, y*s).
	 */
	static void scaleRun(int algorithm,int[] src,int w,int h,int y,int x0,int x1,int s,int[] dst,int dstStride,boolean alpha){
		if(x1<=x0||y<0||y>=h)return;
		if(algorithm==BICUBIC&&s>1)bicubicRun(src,w,h,y,x0,x1,s,dst,dstStride,alpha?4:3);
		else if(algorithm==SCALE2X&&s>1)scale2xRun(src,w,h,y,x0,x1,s,dst,dstStride,alpha);
		else if(algorithm==AUTO&&s>1)autoRun(src,w,h,y,x0,x1,s,dst,dstStride,alpha);
		else replicateRun(src,w,y,x0,x1,s,dst,dstStride);
	}

	/** Every pixel as a solid block (Bresenham's stepping at a whole factor). */
	private static void replicateRun(int[] src,int w,int y,int x0,int x1,int s,int[] dst,int dstStride){
		int row=y*s*dstStride;
		for(int x=x0;x<x1;x++){int c=src[y*w+x],o=row+x*s;for(int i=0;i<s;i++)dst[o+i]=c;}
		int first=row+x0*s,length=(x1-x0)*s;
		for(int j=1;j<s;j++)System.arraycopy(dst,first,dst,first+j*dstStride,length);
	}

	// ------------------------------------------------------------------ bicubic

	/** Fixed-point (1/1024) Catmull-Rom weights per output sub-position of a factor: {w0..w3} and the first tap's offset. */
	private static final java.util.concurrent.ConcurrentHashMap<Integer,int[][]> CUBIC=new java.util.concurrent.ConcurrentHashMap<Integer,int[][]>();
	private static int[][] cubic(int s){
		return CUBIC.computeIfAbsent(s,k->{
			int[][] t=new int[k][5];
			for(int i=0;i<k;i++){
				double f=(i+0.5)/k-0.5,base=Math.floor(f),u=f-base;
				double w0=(-u*u*u+2*u*u-u)/2,w1=(3*u*u*u-5*u*u+2)/2,w2=(-3*u*u*u+4*u*u+u)/2,w3=(u*u*u-u*u)/2;
				int a=(int)Math.round(w0*1024),b=(int)Math.round(w1*1024),c=(int)Math.round(w2*1024),d=1024-a-b-c;
				t[i]=new int[]{a,b,c,d,(int)base-1};
			}
			return t;});
	}
	private static int clamp(int v,int lo,int hi){return v<lo?lo:v>hi?hi:v;}
	/** @param channels 3 for opaque RGB (the alpha byte stays 0), 4 with alpha */
	private static void bicubicRun(int[] src,int w,int h,int y,int x0,int x1,int s,int[] dst,int dstStride,int channels){
		int[][] t=cubic(s);
		// the five source rows a block can reach, horizontally interpolated per sub-column: [row][sub-column][channel]
		int[] hz=new int[5*s*4];
		int[] rows=new int[5];for(int k=0;k<5;k++)rows[k]=clamp(y-2+k,0,h-1)*w;
		for(int x=x0;x<x1;x++){
			for(int k=0;k<5;k++){int r=rows[k];
				for(int i=0;i<s;i++){int[] wt=t[i];int c0=x+wt[4];
					int p0=src[r+clamp(c0,0,w-1)],p1=src[r+clamp(c0+1,0,w-1)],p2=src[r+clamp(c0+2,0,w-1)],p3=src[r+clamp(c0+3,0,w-1)];
					int o=(k*s+i)*4;
					for(int ch=0,shift=0;ch<channels;ch++,shift+=8)
						hz[o+ch]=wt[0]*(p0>>>shift&255)+wt[1]*(p1>>>shift&255)+wt[2]*(p2>>>shift&255)+wt[3]*(p3>>>shift&255);
				}}
			for(int j=0;j<s;j++){int[] wt=t[j];int k0=wt[4]+2; // row index within rows[] of the first tap (y-2 is index 0)
				int out=(y*s+j)*dstStride+x*s;
				for(int i=0;i<s;i++){int v=0;
					for(int ch=0,shift=0;ch<channels;ch++,shift+=8){
						long sum=(long)wt[0]*hz[((k0)*s+i)*4+ch]+(long)wt[1]*hz[((k0+1)*s+i)*4+ch]+(long)wt[2]*hz[((k0+2)*s+i)*4+ch]+(long)wt[3]*hz[((k0+3)*s+i)*4+ch];
						int c=(int)((sum+(1L<<19))>>20);v|=clamp(c,0,255)<<shift;}
					dst[out+i]=v;}
			}
		}
	}

	// ------------------------------------------------------------------ enhanced Scale2x

	/** The cut regions of a corner, in the top-left corner's frame (x right, y down, the pixel from -1/2 to 1/2). */
	private static final int NONE=0,DIAGONAL=1,SHALLOW=2,STEEP=3;
	/** Share (0..256) of each output pixel lying beyond the cut line, per factor: [type][corner][j*s+i]; corners TL, TR, BL, BR. */
	private static final java.util.concurrent.ConcurrentHashMap<Integer,int[][][]> COVER=new java.util.concurrent.ConcurrentHashMap<Integer,int[][][]>();
	private static int[][][] cover(int s){
		return COVER.computeIfAbsent(s,k->{
			int[][][] table=new int[4][4][k*k];final int n=16;
			for(int type=1;type<4;type++)for(int corner=0;corner<4;corner++)for(int j=0;j<k;j++)for(int i=0;i<k;i++){
				int inside=0;
				for(int sy=0;sy<n;sy++)for(int sx=0;sx<n;sx++){
					double x=(i+(sx+0.5)/n)/k-0.5,y=(j+(sy+0.5)/n)/k-0.5;
					if((corner&1)!=0)x=-x;if((corner&2)!=0)y=-y; // mirrored into the top-left corner's frame
					// 45 degrees: through the middles of the two sides; 2:1: from the middle of the long side's end across the pixel
					boolean cut=type==DIAGONAL?x+y<-0.5:type==SHALLOW?y+0.5*x<-0.25:x+0.5*y<-0.25;
					if(cut)inside++;}
				table[type][corner][j*k+i]=(inside*256+n*n/2)/(n*n);}
			return table;});
	}
	/** Y, U and V (0..255 each) of an RGB value packed as 0x00YYUUVV. */
	private static int yuv(int rgb){
		int r=rgb>>>16&255,g=rgb>>>8&255,b=rgb&255;
		int yy=(306*r+601*g+117*b)>>10,u=((-173*r-339*g+512*b)>>10)+128,v=((512*r-429*g-83*b)>>10)+128; // 0.299/0.587/0.114 etc. in 1/1024
		return clamp(yy,0,255)<<16|clamp(u,0,255)<<8|clamp(v,0,255);
	}
	/** The likeness test: two colours differ when luma differs by more than 48 or a chroma by more than 7 (U) or 6 (V); with
	 *  alpha, also when the alpha differs by more than 48. */
	private static boolean differ(int a,int b,int ya,int yb,boolean alpha){
		if(a==b)return false;
		if(alpha&&Math.abs((a>>>24)-(b>>>24))>48)return true;
		return Math.abs((ya>>>16)-(yb>>>16))>48||Math.abs((ya>>>8&255)-(yb>>>8&255))>7||Math.abs((ya&255)-(yb&255))>6;
	}
	/*
	 * The 3x3 neighbourhood:   a1 a2 a3      (A B C)
	 *                          a4 a5 a6      (D E F)
	 *                          a7 a8 a9      (G H I)
	 */
	private static void scale2xRun(int[] src,int w,int h,int y,int x0,int x1,int s,int[] dst,int dstStride,boolean alpha){
		int[][][] cov=cover(s);int ss=s*s;
		int up=Math.max(0,y-1)*w,mid=y*w,down=Math.min(h-1,y+1)*w;
		int[] weight=new int[ss],other=new int[ss];
		// the neighbours of the previous pixel slide left, so each YUV is worked out once per pixel and row
		int px=Math.max(0,x0-1);int a1=src[up+px],a4=src[mid+px],a7=src[down+px];
		int a2=src[up+x0],a5=src[mid+x0],a8=src[down+x0];
		int y1=yuv(a1),y4=yuv(a4),y7=yuv(a7),y2=yuv(a2),y5=yuv(a5),y8=yuv(a8);
		for(int x=x0;x<x1;x++){
			int nx=Math.min(w-1,x+1);int a3=src[up+nx],a6=src[mid+nx],a9=src[down+nx];
			int y3=yuv(a3),y6=yuv(a6),y9=yuv(a9);
			int c=a5,out=(y*s)*dstStride+x*s;
			if(a2==c&&a4==c&&a6==c&&a8==c)fill(dst,out,dstStride,s,c); // no side differs: no corner can change
			else{
				java.util.Arrays.fill(weight,0);boolean any=false;
				// corner: side A (left/right) with the one across from it, side B (top/bottom) with the one across, FA beyond A along
				// the edge (the next pixel down/up that side's column), FB beyond B (the next one along that side's row)
				any|=corner(cov,0,s,weight,other,c,y5,a4,y4,a6,y6,a2,y2,a8,y8,a7,y7,a3,y3,alpha);
				any|=corner(cov,1,s,weight,other,c,y5,a6,y6,a4,y4,a2,y2,a8,y8,a9,y9,a1,y1,alpha);
				any|=corner(cov,2,s,weight,other,c,y5,a4,y4,a6,y6,a8,y8,a2,y2,a1,y1,a9,y9,alpha);
				any|=corner(cov,3,s,weight,other,c,y5,a6,y6,a4,y4,a8,y8,a2,y2,a3,y3,a7,y7,alpha);
				if(!any)fill(dst,out,dstStride,s,c);
				else for(int j=0;j<s;j++)for(int i=0;i<s;i++){int k=j*s+i,wt=weight[k];dst[out+j*dstStride+i]=wt==0?c:mix(c,other[k],wt);}
			}
			a1=a2;a4=a5;a7=a8;a2=a3;a5=a6;a8=a9;y1=y2;y4=y5;y7=y8;y2=y3;y5=y6;y8=y9;
		}
	}
	/** One corner's cut (see the class comment) merged into the block's weights: the strongest cut wins an output pixel. */
	private static boolean corner(int[][][] cov,int corner,int s,int[] weight,int[] other,int c,int yc,int a,int ya,int oa,int yoa,
			int b,int yb,int ob,int yob,int fa,int yfa,int fb,int yfb,boolean alpha){
		// Scale2x: the two sides alike, the pixel not, and neither side like the neighbour across from it
		if(differ(a,b,ya,yb,alpha)||!differ(c,a,yc,ya,alpha)||!differ(a,ob,ya,yob,alpha)||!differ(b,oa,yb,yoa,alpha))return false;
		boolean contA=!differ(fa,a,yfa,ya,alpha),contB=!differ(fb,b,yfb,yb,alpha);
		int type=contA&&contB?NONE:contB?SHALLOW:contA?STEEP:DIAGONAL;
		if(type==NONE)return false; // a rectangle's corner
		int e=mix(a,b,128);int[] t=cov[type][corner];
		for(int k=0;k<s*s;k++)if(t[k]>weight[k]){weight[k]=t[k];other[k]=e;}
		return true;
	}
	private static void fill(int[] dst,int out,int stride,int s,int c){for(int j=0;j<s;j++){int o=out+j*stride;for(int i=0;i<s;i++)dst[o+i]=c;}}
	/** {@code a} and {@code b} mixed per channel, {@code wt}/256 of {@code b}. */
	static int mix(int a,int b,int wt){
		int r=0;for(int shift=0;shift<32;shift+=8){int x=a>>>shift&255,y=b>>>shift&255;r|=(x+((y-x)*wt+128>>8))<<shift;}return r;
	}

	// ------------------------------------------------------------------ auto: Scale2x+ or bicubic per pixel

	/** Side-by-side pairs of a 3x3 neighbourhood that must differ for it to count as a texture (of 12; a one-pixel diagonal
	 *  line between two other colours has 8). */
	private static final int TEXTURE_PAIRS=9;
	private static void autoRun(int[] src,int w,int h,int y,int x0,int x1,int s,int[] dst,int dstStride,boolean alpha){
		// a pixel goes bicubic when its own neighbourhood is a texture, or those of two of its four side neighbours are (the
		// inner, quieter dots of a texture); index k = x-x0+1
		boolean[] above=textureRow(src,w,h,y-1,x0-1,x1+1,alpha),row=textureRow(src,w,h,y,x0-1,x1+1,alpha),below=textureRow(src,w,h,y+1,x0-1,x1+1,alpha);
		int start=x0;boolean startTexture=false;
		for(int x=x0;x<=x1;x++){
			boolean texture=false;
			if(x<x1){int k=x-x0+1;
				texture=row[k]||(above[k]?1:0)+(below[k]?1:0)+(row[k-1]?1:0)+(row[k+1]?1:0)>=2;}
			if(x==x0){startTexture=texture;continue;}
			if(x<x1&&texture==startTexture)continue;
			if(startTexture)bicubicRun(src,w,h,y,start,x,s,dst,dstStride,alpha?4:3);
			else scale2xRun(src,w,h,y,start,x,s,dst,dstStride,alpha);
			start=x;startTexture=texture;
		}
	}
	/** Per pixel xa..xb-1 of row y (both clamped into the picture): whether its 3x3 neighbourhood is a texture - at least
	 *  TEXTURE_PAIRS of its 12 side-by-side pairs differ, and {@link #textured} says its colours are not two-colour drawn art. */
	private static boolean[] textureRow(int[] src,int w,int h,int y,int xa,int xb,boolean alpha){
		y=clamp(y,0,h-1);int up=Math.max(0,y-1)*w,mid=y*w,down=Math.min(h-1,y+1)*w;
		// per column c = xa-1..xb (index c-xa+1): its pixels and their YUV, the differing vertical pairs in it, and the
		// differing pairs between it and the next column
		int n=xb-xa+2;int[] vert=new int[n],horiz=new int[n];int[] colour=new int[3*(n+1)],luma=new int[3*(n+1)];
		for(int k=0;k<=n;k++){int x=clamp(xa-1+k,0,w-1);
			colour[3*k]=src[up+x];colour[3*k+1]=src[mid+x];colour[3*k+2]=src[down+x];
			for(int r=0;r<3;r++)luma[3*k+r]=yuv(colour[3*k+r]);}
		for(int k=0;k<n;k++){int o=3*k;
			vert[k]=(grain(colour[o],colour[o+1],luma[o],luma[o+1],alpha)?1:0)+(grain(colour[o+1],colour[o+2],luma[o+1],luma[o+2],alpha)?1:0);
			int hz=0;for(int r=0;r<3;r++)if(grain(colour[o+r],colour[o+3+r],luma[o+r],luma[o+3+r],alpha))hz++;horiz[k]=hz;}
		boolean[] flags=new boolean[xb-xa];
		for(int x=xa;x<xb;x++){int k=x-xa+1;
			flags[x-xa]=vert[k-1]+vert[k]+vert[k+1]+horiz[k-1]+horiz[k]>=TEXTURE_PAIRS&&textured(colour,luma,3*(k-1),alpha);}
		return flags;
	}
	/** Whether the 3x3 neighbourhood of columns at {@code o}.. (3 entries per column) looks like a texture rather than drawn
	 *  pixel art: at least three unlike colours, or two close ones (luma within TEXTURE_CONTRAST: the dark dither of a shaded
	 *  sprite, where a black-and-white pattern is drawn art). */
	private static boolean textured(int[] colour,int[] luma,int o,boolean alpha){
		int a=colour[o],ya=luma[o],b=0,yb=0;boolean haveB=false;
		for(int i=o+1;i<o+9;i++){int c=colour[i],yc=luma[i];
			if(!grain(c,a,yc,ya,alpha))continue;
			if(!haveB){b=c;yb=yc;haveB=true;}
			else if(grain(c,b,yc,yb,alpha))return true;}
		return haveB&&Math.abs((ya>>>16)-(yb>>>16))<=TEXTURE_CONTRAST;
	}
	/** The texture test's likeness: finer than {@link #differ}'s (luma by more than TEXTURE_LUMA), since the dots of a dithered
	 *  texture are often shades of one another that Scale2x rightly takes for one colour. */
	private static boolean grain(int a,int b,int ya,int yb,boolean alpha){
		if(a==b)return false;
		if(alpha&&Math.abs((a>>>24)-(b>>>24))>TEXTURE_LUMA)return true;
		return Math.abs((ya>>>16)-(yb>>>16))>TEXTURE_LUMA||Math.abs((ya>>>8&255)-(yb>>>8&255))>7||Math.abs((ya&255)-(yb&255))>6;
	}
	/** The texture test's luma step (finer than Scale2x's 48), and the largest luma gap of a two-colour texture. */
	private static final int TEXTURE_LUMA=16,TEXTURE_CONTRAST=80;

	// ------------------------------------------------------------------ whole pictures

	/** A whole packed picture scaled by a whole factor (several passes of the enhanced Scale2x or auto for factors beyond 4, e.g. 6 = 3 x 2, 8 = 4 x 2). */
	static int[] scale(int algorithm,int[] src,int w,int h,int factor,boolean alpha){
		if(factor<=1)return src.clone();
		if((algorithm==SCALE2X||algorithm==AUTO)&&factor>4){
			for(int f=4;f>=2;f--)if(factor%f==0){int[] first=scale(algorithm,src,w,h,f,alpha);return scale(algorithm,first,w*f,h*f,factor/f,alpha);}
			algorithm=BICUBIC; // 5, 7: no pass divides it
		}
		int[] dst=new int[w*factor*h*factor];
		for(int y=0;y<h;y++)scaleRun(algorithm,src,w,h,y,0,w,factor,dst,w*factor,alpha);
		return dst;
	}

	/** An image (any type) magnified by a whole factor, as TYPE_INT_ARGB_PRE (alpha kept, edges anti-aliased by the algorithm). */
	static BufferedImage scaleImage(BufferedImage image,int factor,int algorithm){
		int w=image.getWidth(),h=image.getHeight();
		int[] argb=image.getRGB(0,0,w,h,null,0,w);boolean alpha=image.getColorModel().hasAlpha();
		if(alpha)for(int i=0;i<argb.length;i++)argb[i]=premultiply(argb[i]);
		int[] up=scale(algorithm,argb,w,h,factor,alpha);
		BufferedImage out=new BufferedImage(w*factor,h*factor,alpha?BufferedImage.TYPE_INT_ARGB_PRE:BufferedImage.TYPE_INT_RGB);
		int[] data=((java.awt.image.DataBufferInt)out.getRaster().getDataBuffer()).getData();
		System.arraycopy(up,0,data,0,data.length);
		return out;
	}
	private static int premultiply(int argb){
		int a=argb>>>24;if(a==255)return argb;if(a==0)return 0;
		return a<<24|((argb>>>16&255)*a+127)/255<<16|((argb>>>8&255)*a+127)/255<<8|((argb&255)*a+127)/255;
	}

	/** A text mask (TYPE_BYTE_BINARY set = ink, or TYPE_BYTE_GRAY coverage) magnified by a whole factor into a TYPE_BYTE_GRAY coverage. */
	static BufferedImage scaleMask(BufferedImage mask,int factor,int algorithm){
		int w=mask.getWidth(),h=mask.getHeight();java.awt.image.Raster raster=mask.getRaster();boolean binary=mask.getType()==BufferedImage.TYPE_BYTE_BINARY;
		int[] grey=new int[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=raster.getSample(x,y,0);if(binary)v=v!=0?255:0;grey[y*w+x]=v<<16|v<<8|v;}
		int[] up=scale(algorithm,grey,w,h,factor,false);
		BufferedImage out=new BufferedImage(w*factor,h*factor,BufferedImage.TYPE_BYTE_GRAY);
		byte[] data=((java.awt.image.DataBufferByte)out.getRaster().getDataBuffer()).getData();
		for(int i=0;i<data.length;i++)data[i]=(byte)(up[i]>>>8);
		return out;
	}
}

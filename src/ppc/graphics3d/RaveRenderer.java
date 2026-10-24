/**
 * The drawing engine of {@link PpcRave}: a RAVE draw context's buffers (colour as ARGB, depth as floats) and the rasterizer
 * for its primitives, after the rules RAVE.h states - Gouraud and texture-mapped triangles (perspective-correct u/v, the
 * kQATextureOp modulate / highlight / decal / blend operations), points, lines of any width, bitmaps, the Z functions,
 * premultiplied and interpolated transparency blending, fog, alpha test, chroma key and channel masks.
 * <p>
 * Vertices come as float arrays in the TQAVGouraud (x y z invW r g b a) and TQAVTexture (x y z invW r g b a uOverW vOverW
 * kd_r kd_g kd_b ks_r ks_g ks_b) layouts. As in QuickDraw 3D's software engine (compared pixel by pixel), pixel (x, y)
 * is covered when the point (x + 0.5, y) - the middle of the pixel's top edge - is inside the triangle, attributes are
 * sampled there, and texture v runs up from the image's bottom row. The depth kept is Z, or -1/w with kQAPerspectiveZ_On (a larger invW is nearer), so that
 * a smaller value is nearer either way and the Z functions read as RAVE states them.
 */
final class RaveRenderer {
	static final int Z_NONE=0,Z_LT=1,Z_EQ=2,Z_LE=3,Z_GT=4,Z_NE=5,Z_GE=6,Z_TRUE=7,Z_FALSE=8;
	static final int BLEND_PREMULTIPLY=0,BLEND_INTERPOLATE=1;
	static final int OP_MODULATE=1,OP_HIGHLIGHT=2,OP_DECAL=4,OP_SHRINK=8,OP_BLEND=16;
	static final int FOG_NONE=0,FOG_ALPHA=1,FOG_LINEAR=2,FOG_EXP=3,FOG_EXP2=4;
	static final int ALPHA_TEST_NONE=0,ALPHA_TEST_TRUE=7;
	/** Floats per vertex of TQAVGouraud and TQAVTexture. */
	static final int GOURAUD=8,TEXTURE=16;
	/** The depth a cleared buffer holds (farther than anything drawn). */
	static final float FAR=Float.MAX_VALUE;

	final int width,height;
	/** ARGB pixels, row by row. */
	final int[] color;
	/** Depth per pixel (smaller is nearer), or null for a context without a Z buffer. */
	final float[] depth;

	// ---- state (the draw context's tags)
	int zFunction,blend=BLEND_PREMULTIPLY,textureFilter,textureOp,fogMode,channelMask=15,alphaTest,bitmapFilter;
	boolean perspectiveZ,zWrite=true,chromakey,wrapU=true,wrapV=true;
	float bgA,bgR,bgG,bgB,lineWidth=1,alphaTestRef;
	float fogA,fogR,fogG,fogB,fogStart,fogEnd=1,fogDensity=1,fogMaxDepth;
	/**
	 * Exponential fog runs from fogStart (none) over to fogEnd (almost all), fogDensity being the unscaled style density: how
	 * the hardware engines Pangea's games were made on drew QuickDraw 3D's exponential fog (Bugdom asks for density 1 from
	 * 2400 to 3000 with yon 3000; taken per unit of distance, as Apple's software engine does, every pixel is fog).
	 */
	boolean fogRange;
	float chromaR,chromaG,chromaB,bitmapScaleX=1,bitmapScaleY=1,mipmapBias;
	float envR,envG,envB;
	Texture texture;
	/** Scissor rectangle (kQATagGL_Scissor*, inclusive-exclusive), the whole buffer by default. */
	int scissorLeft,scissorTop,scissorRight,scissorBottom;

	RaveRenderer(int width,int height,boolean zBuffer){
		this.width=Math.max(1,width);this.height=Math.max(1,height);
		color=new int[this.width*this.height];depth=zBuffer?new float[this.width*this.height]:null;
		zFunction=zBuffer?Z_LT:Z_NONE;
		scissorRight=this.width;scissorBottom=this.height;
	}

	/** The background colour as ARGB (channels truncated, as the software engine did: 0.1 is 25). */
	int background(){return clampTrunc(bgA*255)<<24|clampTrunc(bgR*255)<<16|clampTrunc(bgG*255)<<8|clampTrunc(bgB*255);}
	private static int clamp(float v){return v<=0?0:v>=255?255:(int)(v+0.5f);}
	private static int clampTrunc(float v){return v<=0?0:v>=255?255:(int)v;}

	/** Clears a rectangle (left, top, right, bottom; clipped) of the colour buffer to the background colour. */
	void clearColor(int l,int t,int r,int b){
		l=Math.max(0,l);t=Math.max(0,t);r=Math.min(width,r);b=Math.min(height,b);int bg=background();
		for(int y=t;y<b;y++)java.util.Arrays.fill(color,y*width+l,y*width+r,bg);
	}
	/** Clears a rectangle of the depth buffer to far. */
	void clearDepth(int l,int t,int r,int b){
		if(depth==null)return;
		l=Math.max(0,l);t=Math.max(0,t);r=Math.min(width,r);b=Math.min(height,b);
		for(int y=t;y<b;y++)java.util.Arrays.fill(depth,y*width+l,y*width+r,FAR);
	}
	/** Copies a rectangle of another context's buffers (a cache context passed to QARenderStart). */
	void copyFrom(RaveRenderer o,int l,int t,int r,int b,boolean colour,boolean z){
		l=Math.max(0,l);t=Math.max(0,t);r=Math.min(Math.min(width,o.width),r);b=Math.min(Math.min(height,o.height),b);
		for(int y=t;y<b;y++){
			if(colour)System.arraycopy(o.color,y*o.width+l,color,y*width+l,r-l);
			if(z&&depth!=null){if(o.depth!=null)System.arraycopy(o.depth,y*o.width+l,depth,y*width+l,r-l);else java.util.Arrays.fill(depth,y*width+l,y*width+r,FAR);}
		}
	}

	// ================================================================== textures

	/** A texture or bitmap: ARGB levels (a mipmapped texture has them down to 1x1), or colour-table indices until a table is bound. */
	static final class Texture {
		final int[][] pixels;final int[] widths,heights;
		/** Indices of a CL4/CL8/ACL16 image (alpha in bits 8-15 for ACL16), per level, or null. */
		final int[][] indices;
		/** Whether any texel is not opaque, and whether some are neither opaque nor fully transparent. */
		boolean alpha,partialAlpha;
		/** Whether texels are 1-bit alpha images to be drawn in the vertex colour (kQAPixel_Alpha1 bitmaps). */
		boolean mask;
		Texture(int levels){pixels=new int[levels][];widths=new int[levels];heights=new int[levels];indices=new int[levels][];}
		int levels(){return pixels.length;}
		void updateAlpha(){alpha=partialAlpha=false;for(int[] p:pixels)if(p!=null)for(int v:p){int a=v>>>24;if(a!=255){alpha=true;if(a!=0){partialAlpha=true;return;}}}}
		/** Applies a colour table (CL8/CL4 entries as ARGB; entry 0 transparent when the table says so). */
		void bind(int[] table){
			for(int l=0;l<pixels.length;l++){int[] ix=indices[l];if(ix==null)continue;int[] p=pixels[l];
				for(int i=0;i<ix.length;i++){int v=ix[i];int c=table[(v&0xff)%table.length];
					p[i]=v>=0x100?(v>>>8)<<24|c&0xffffff:c;}}
			updateAlpha();
		}
	}

	private int wrap(int i,int size,boolean repeat){
		if(repeat){if((size&(size-1))==0)return i&(size-1);i%=size;return i<0?i+size:i;}
		return i<0?0:i>=size?size-1:i;
	}
	private static int floor(float v){int i=(int)v;return v<i?i-1:i;}
	/** The texel at (u, v) of a level: nearest, or bilinear with {@code filter}. */
	private int sample(Texture t,int level,float u,float v,boolean filter){
		int w=t.widths[level],h=t.heights[level];int[] p=t.pixels[level];
		float fu=u*w,fv=(1-v)*h;
		if(!filter)return p[wrap(floor(fv),h,wrapV)*w+wrap(floor(fu),w,wrapU)];
		fu-=0.5f;fv-=0.5f;int x0=floor(fu),y0=floor(fv);float ax=fu-x0,ay=fv-y0;
		int x1=wrap(x0+1,w,wrapU),y1=wrap(y0+1,h,wrapV);x0=wrap(x0,w,wrapU);y0=wrap(y0,h,wrapV);
		int c00=p[y0*w+x0],c10=p[y0*w+x1],c01=p[y1*w+x0],c11=p[y1*w+x1];
		return lerp4(c00,c10,c01,c11,ax,ay);
	}
	private static int lerp4(int c00,int c10,int c01,int c11,float ax,float ay){
		float w00=(1-ax)*(1-ay),w10=ax*(1-ay),w01=(1-ax)*ay,w11=ax*ay;
		int r=0;
		for(int s=0;s<32;s+=8){float v=((c00>>>s)&255)*w00+((c10>>>s)&255)*w10+((c01>>>s)&255)*w01+((c11>>>s)&255)*w11;r|=clamp(v)<<s;}
		return r;
	}

	// ================================================================== triangles

	// the attribute planes of the triangle being drawn: value = a + dx*(px - x0) + dy*(py - y0)
	private float px0,py0;
	private final float[] pa=new float[16],pdx=new float[16],pdy=new float[16];
	private int planes;
	/** Mipmap level selection of the textured triangle being drawn: level = ceil(log2(sqrt(lodScale / invW^3))) + bias. */
	private float lodScale;

	/** Sets up the planes of the attributes at offsets {@code which} of three vertices; false for a degenerate triangle. */
	private boolean setUp(float[] v,int a,int b,int c,int[] which){
		float x0=v[a],y0=v[a+1],x1=v[b],y1=v[b+1],x2=v[c],y2=v[c+1];
		float area=(x1-x0)*(y2-y0)-(x2-x0)*(y1-y0);
		if(area==0||area!=area)return false;
		float inv=1/area;px0=x0;py0=y0;planes=which.length;
		for(int k=0;k<which.length;k++){int o=which[k];
			float a0=v[a+o],d1=v[b+o]-a0,d2=v[c+o]-a0;
			pa[k]=a0;pdx[k]=(d1*(y2-y0)-d2*(y1-y0))*inv;pdy[k]=(d2*(x1-x0)-d1*(x2-x0))*inv;}
		return true;
	}

	/** The span callback of the triangle walker. */
	private interface Span { void span(int y,int x0,int x1); }
	private final Span gouraudOpaque=this::spanGouraudOpaque,gouraud=this::spanGouraud,textureOpaque=this::spanTextureOpaque,
		textureModulateOpaque=this::spanTextureModulateOpaque,textureAny=this::spanTexture;

	/** Walks the rows and spans of pixels whose sample points (x + 0.5, y) are inside the triangle, within the buffer and scissor. */
	private void walk(float[] v,int a,int b,int c,Span s){
		// sort by y
		if(v[b+1]<v[a+1]){int t=a;a=b;b=t;}
		if(v[c+1]<v[b+1]){int t=b;b=c;c=t;}
		if(v[b+1]<v[a+1]){int t=a;a=b;b=t;}
		float xa=v[a],ya=v[a+1],xb=v[b],yb=v[b+1],xc=v[c],yc=v[c+1];
		if(!(yc>ya))return;
		int yStart=Math.max(scissorTop,(int)Math.ceil(ya)),yEnd=Math.min(scissorBottom,(int)Math.ceil(yc));
		float longSlope=(xc-xa)/(yc-ya);
		float upperSlope=yb>ya?(xb-xa)/(yb-ya):0,lowerSlope=yc>yb?(xc-xb)/(yc-yb):0;
		for(int y=yStart;y<yEnd;y++){
			float cy=y;
			float xl=xa+(cy-ya)*longSlope;
			float xs=cy<yb?xa+(cy-ya)*upperSlope:xb+(cy-yb)*lowerSlope;
			if(xs<xl){float t=xs;xs=xl;xl=t;}
			int x0=Math.max(scissorLeft,(int)Math.ceil(xl-0.5f)),x1=Math.min(scissorRight,(int)Math.ceil(xs-0.5f));
			if(x0<x1)s.span(y,x0,x1);
		}
	}

	private static final int[] GOURAUD_ATTRS={2,3,4,5,6,7}; // z invW r g b a
	/** A Gouraud-shaded triangle of vertices at offsets a, b, c of {@code v} (TQAVGouraud layout). */
	void triGouraud(float[] v,int a,int b,int c){
		if(!setUp(v,a,b,c,GOURAUD_ATTRS))return;
		boolean opaque=v[a+7]>=1&&v[b+7]>=1&&v[c+7]>=1;
		if(opaque&&simpleWrites())walk(v,a,b,c,gouraudOpaque);
		else walk(v,a,b,c,gouraud);
	}
	/** Whether pixels can be written without the general path: no fog, alpha test, channel mask. */
	private boolean simpleWrites(){return fogMode==FOG_NONE&&alphaTest==ALPHA_TEST_NONE&&channelMask==15;}


	private void spanGouraudOpaque(int y,int x0,int x1){
		float fx=x0+0.5f-px0,fy=y-py0;
		int kz=perspectiveZ?1:0;float ks=perspectiveZ?-1:1;
		float z=(pa[kz]+pdx[kz]*fx+pdy[kz]*fy)*ks,dz=pdx[kz]*ks;
		float r=pa[2]+pdx[2]*fx+pdy[2]*fy,g=pa[3]+pdx[3]*fx+pdy[3]*fy,b=pa[4]+pdx[4]*fx+pdy[4]*fy;
		float dr=pdx[2],dg=pdx[3],db=pdx[4];
		int i=y*width+x0;
		for(int x=x0;x<x1;x++,i++,z+=dz,r+=dr,g+=dg,b+=db){
			if(depth!=null&&zFunction!=Z_NONE){if(!zPass(z,depth[i]))continue;if(zWrite&&zFunction!=Z_NONE)depth[i]=z;}
			color[i]=0xff000000|clampTrunc(r*255)<<16|clampTrunc(g*255)<<8|clampTrunc(b*255);
		}
	}
	private void spanGouraud(int y,int x0,int x1){
		float fx=x0+0.5f-px0,fy=y-py0;
		float z=pa[0]+pdx[0]*fx+pdy[0]*fy,w=pa[1]+pdx[1]*fx+pdy[1]*fy;
		float r=pa[2]+pdx[2]*fx+pdy[2]*fy,g=pa[3]+pdx[3]*fx+pdy[3]*fy,b=pa[4]+pdx[4]*fx+pdy[4]*fy,a=pa[5]+pdx[5]*fx+pdy[5]*fy;
		int i=y*width+x0;
		for(int x=x0;x<x1;x++,i++,z+=pdx[0],w+=pdx[1],r+=pdx[2],g+=pdx[3],b+=pdx[4],a+=pdx[5])
			shade(i,perspectiveZ?-w:z,w,r*255,g*255,b*255,a);
	}

	/** Depth test of {@code key} against the buffer's {@code stored}. */
	private boolean zPass(float key,float stored){
		switch(zFunction){
			case Z_LT:return key<stored;case Z_EQ:return key==stored;case Z_LE:return key<=stored;case Z_GT:return key>stored;
			case Z_NE:return key!=stored;case Z_GE:return key>=stored;case Z_FALSE:return false;default:return true;
		}
	}

	/**
	 * The general pixel path: fog, alpha test, depth test and write, transparency blending and the channel mask, for a
	 * source colour (0-255 channels) and alpha (0-1). Fully transparent pixels change nothing, not even the depth.
	 */
	private void shade(int i,float key,float invW,float r,float g,float b,float a){
		if(a<=0&&alphaTest==ALPHA_TEST_NONE)return;
		if(fogMode!=FOG_NONE){
			float f;
			if(fogMode==FOG_ALPHA){f=a;a=1;}
			else{float d=invW!=0?1/invW:0;if(fogMaxDepth>0)d/=fogMaxDepth;
				if(fogRange&&fogMode!=FOG_LINEAR&&fogEnd>fogStart){ // exponential over the start..end range (fogRange)
					float t=(d-fogStart)/(fogEnd-fogStart);t=t<0?0:t>1?1:t;float e=fogDensity*t;
					f=fogMode==FOG_EXP?(float)Math.exp(-3*e):(float)Math.exp(-4*e*e);}
				else f=fogMode==FOG_LINEAR?(fogEnd!=fogStart?(fogEnd-d)/(fogEnd-fogStart):1):fogMode==FOG_EXP?(float)Math.exp(-fogDensity*d):(float)Math.exp(-(fogDensity*d)*(fogDensity*d));}
			f=f<0?0:f>1?1:f;
			r=f*r+(1-f)*fogR*255;g=f*g+(1-f)*fogG*255;b=f*b+(1-f)*fogB*255;
		}
		if(alphaTest!=ALPHA_TEST_NONE&&!alphaPass(a))return;
		if(depth!=null&&zFunction!=Z_NONE){if(!zPass(key,depth[i]))return;if(zWrite)depth[i]=key;}
		int d=color[i],out;
		if(a>=1)out=0xff000000|clampTrunc(r)<<16|clampTrunc(g)<<8|clampTrunc(b);
		else{
			float ia=1-a;float dr=(d>>>16)&255,dg=(d>>>8)&255,db=d&255,da=(d>>>24)/255f;
			float oa=1-ia*(1-da);
			if(blend==BLEND_PREMULTIPLY)out=clampTrunc(oa*255)<<24|clampTrunc(ia*dr+r)<<16|clampTrunc(ia*dg+g)<<8|clampTrunc(ia*db+b);
			else out=clampTrunc(oa*255)<<24|clampTrunc(ia*dr+a*r)<<16|clampTrunc(ia*dg+a*g)<<8|clampTrunc(ia*db+a*b);
		}
		if(channelMask!=15){int keep=((channelMask&1)!=0?0:0xff0000)|((channelMask&2)!=0?0:0xff00)|((channelMask&4)!=0?0:0xff)|((channelMask&8)!=0?0:0xff000000);out=out&~keep|d&keep;}
		color[i]=out;
	}
	private boolean alphaPass(float a){
		float ref=alphaTestRef;
		switch(alphaTest){case 1:return a<ref;case 2:return a==ref;case 3:return a<=ref;case 4:return a>ref;case 5:return a!=ref;case 6:return a>=ref;default:return true;}
	}

	private static final int[] TEXTURE_ATTRS={2,3,4,5,6,7,8,9,10,11,12,13,14,15}; // z invW r g b a u/w v/w kd rgb ks rgb
	/** A texture-mapped triangle of vertices at offsets a, b, c of {@code v} (TQAVTexture layout), with the current texture. */
	void triTexture(float[] v,int a,int b,int c){
		Texture t=texture;
		if(t==null||t.pixels[0]==null){triGouraudFromTexture(v,a,b,c);return;}
		if(!setUp(v,a,b,c,TEXTURE_ATTRS))return;
		lodScale=0;
		if(t.levels()>1){ // |det J| of (u,v) over the screen is D / invW^3 for affine u/w, v/w, 1/w: D from the planes
			float du=pdx[6],dv=pdx[7],dq=pdx[1],eu=pdy[6],ev=pdy[7],eq=pdy[1];
			float q0=pa[1]-pdx[1]*px0-pdy[1]*py0,u0=pa[6]-pdx[6]*px0-pdy[6]*py0,v0=pa[7]-pdx[7]*px0-pdy[7]*py0; // values at the origin
			float det=q0*(du*ev-dv*eu)-u0*(dq*ev-dv*eq)+v0*(dq*eu-du*eq);
			lodScale=Math.abs(det)*t.widths[0]*t.heights[0];
		}
		boolean opaque=!t.alpha&&v[a+7]>=1&&v[b+7]>=1&&v[c+7]>=1;
		int op=textureOp&(OP_MODULATE|OP_HIGHLIGHT|OP_DECAL|OP_BLEND);
		if(opaque&&(op==OP_MODULATE||op==0)&&textureFilter==0&&t.levels()==1&&!chromakey&&simpleWrites())walk(v,a,b,c,op==0?textureOpaque:textureModulateOpaque);
		else walk(v,a,b,c,textureAny);
	}
	/** Without a texture a textured triangle is drawn in its vertex colours (modulated by kd when modulating). */
	private void triGouraudFromTexture(float[] v,int a,int b,int c){
		float[] g=new float[3*GOURAUD];int[] o={a,b,c};
		for(int k=0;k<3;k++){System.arraycopy(v,o[k],g,k*GOURAUD,GOURAUD);
			if((textureOp&OP_MODULATE)!=0){g[k*GOURAUD+4]=v[o[k]+10];g[k*GOURAUD+5]=v[o[k]+11];g[k*GOURAUD+6]=v[o[k]+12];}}
		triGouraud(g,0,GOURAUD,2*GOURAUD);
	}

	private void spanTextureOpaque(int y,int x0,int x1){spanTextureFast(y,x0,x1,false);}
	private void spanTextureModulateOpaque(int y,int x0,int x1){spanTextureFast(y,x0,x1,true);}
	/** Opaque texel, nearest, one level, optional modulation: the common case (QuickDraw 3D's Interactive Renderer). */
	private void spanTextureFast(int y,int x0,int x1,boolean modulate){
		float fx=x0+0.5f-px0,fy=y-py0;
		float z=pa[0]+pdx[0]*fx+pdy[0]*fy,q=pa[1]+pdx[1]*fx+pdy[1]*fy,su=pa[6]+pdx[6]*fx+pdy[6]*fy,sv=pa[7]+pdx[7]*fx+pdy[7]*fy;
		float kr=pa[8]+pdx[8]*fx+pdy[8]*fy,kg=pa[9]+pdx[9]*fx+pdy[9]*fy,kb=pa[10]+pdx[10]*fx+pdy[10]*fy;
		float dz=pdx[0],dq=pdx[1],dsu=pdx[6],dsv=pdx[7],dkr=pdx[8],dkg=pdx[9],dkb=pdx[10];
		Texture t=texture;int w=t.widths[0],h=t.heights[0];int[] p=t.pixels[0];
		boolean pow2=(w&(w-1))==0&&(h&(h-1))==0&&wrapU&&wrapV;int wm=w-1,hm=h-1;
		boolean zTest=depth!=null&&zFunction!=Z_NONE;
		int i=y*width+x0;
		for(int x=x0;x<x1;x++,i++,z+=dz,q+=dq,su+=dsu,sv+=dsv,kr+=dkr,kg+=dkg,kb+=dkb){
			if(zTest){float key=perspectiveZ?-q:z;if(!zPass(key,depth[i]))continue;if(zWrite)depth[i]=key;}
			float iw=1/q;float fu=su*iw*w,fv=(1-sv*iw)*h;
			int tx=floor(fu),ty=floor(fv);
			if(pow2){tx&=wm;ty&=hm;}else{tx=wrap(tx,w,wrapU);ty=wrap(ty,h,wrapV);}
			int c=p[ty*w+tx];
			if(modulate){
				int r=(int)(((c>>>16)&255)*kr),g=(int)(((c>>>8)&255)*kg),b=(int)((c&255)*kb);
				color[i]=0xff000000|(r>255?255:r<0?0:r)<<16|(g>255?255:g<0?0:g)<<8|(b>255?255:b<0?0:b);
			}else color[i]=0xff000000|c;
		}
	}
	/** Every texture operation, filter and pixel path. */
	private void spanTexture(int y,int x0,int x1){
		float fx=x0+0.5f-px0,fy=y-py0;
		float[] cur=spanValues;
		for(int k=0;k<planes;k++)cur[k]=pa[k]+pdx[k]*fx+pdy[k]*fy;
		Texture t=texture;boolean filter=textureFilter!=0;int op=textureOp;int levels=t.levels();
		int i=y*width+x0;
		for(int x=x0;x<x1;x++,i++){
			float q=cur[1],iw=1/q;float u=cur[6]*iw,v=cur[7]*iw;
			int level=0; // with mipmaps: the level whose texels are no smaller than a pixel (as the software engine chose)
			if(levels>1&&lodScale>0){float rho2=lodScale*iw*iw*iw;level=((Math.getExponent(rho2)+2)>>1)+Math.round(mipmapBias);level=level<0?0:level>=levels?levels-1:level;}
			int c=sample(t,level,u,v,filter);
			if(chromakey&&(((c>>>16)&255)==clamp(chromaR*255)&&((c>>>8)&255)==clamp(chromaG*255)&&(c&255)==clamp(chromaB*255))){for(int k=0;k<planes;k++)cur[k]+=pdx[k];continue;}
			float tr=(c>>>16)&255,tg=(c>>>8)&255,tb=c&255,ta=(c>>>24)/255f;
			float a=cur[5];
			if((op&OP_DECAL)!=0){tr=ta*tr+(1-ta)*cur[2]*255;tg=ta*tg+(1-ta)*cur[3]*255;tb=ta*tb+(1-ta)*cur[4]*255;ta=a;}
			else if((op&OP_BLEND)!=0){tr=(1-tr/255)*cur[2]*255+tr/255*envR*255;tg=(1-tg/255)*cur[3]*255+tg/255*envG*255;tb=(1-tb/255)*cur[4]*255+tb/255*envB*255;ta*=a;}
			else ta*=a;
			if((op&OP_MODULATE)!=0){tr*=cur[8];tg*=cur[9];tb*=cur[10];if(tr>255)tr=255;if(tg>255)tg=255;if(tb>255)tb=255;}
			if((op&OP_HIGHLIGHT)!=0){tr+=cur[11]*255;tg+=cur[12]*255;tb+=cur[13]*255;}
			shade(i,perspectiveZ?-q:cur[0],q,tr,tg,tb,ta);
			for(int k=0;k<planes;k++)cur[k]+=pdx[k];
		}
	}
	private final float[] spanValues=new float[16];

	// ================================================================== points, lines, bitmaps

	/** A point (a square of the line width) in the colour of TQAVGouraud vertex {@code o}. */
	void point(float[] v,int o){
		float half=Math.max(0.5f,lineWidth/2);
		int x0=Math.max(scissorLeft,(int)Math.ceil(v[o]-half)),x1=Math.min(scissorRight,(int)Math.ceil(v[o]+half));
		int y0=Math.max(scissorTop,(int)Math.ceil(v[o+1]-half)),y1=Math.min(scissorBottom,(int)Math.ceil(v[o+1]+half));
		if(x1<=x0&&x0<scissorRight&&x0>=scissorLeft)x1=x0+1;if(y1<=y0&&y0<scissorBottom&&y0>=scissorTop)y1=y0+1;
		float key=perspectiveZ?-v[o+3]:v[o+2];
		for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)shade(y*width+x,key,v[o+3],v[o+4]*255,v[o+5]*255,v[o+6]*255,v[o+7]);
	}
	/** A line between TQAVGouraud vertices {@code a} and {@code b}: stepped pixels for thin lines, a quadrilateral for wider ones. */
	void line(float[] v,int a,int b){
		float x0=v[a],y0=v[a+1],x1=v[b],y1=v[b+1];
		float dx=x1-x0,dy=y1-y0,len=(float)Math.sqrt(dx*dx+dy*dy);
		if(lineWidth>1.5f&&len>0){
			float nx=-dy/len*lineWidth/2,ny=dx/len*lineWidth/2;
			float[] q=new float[4*GOURAUD];
			for(int k=0;k<4;k++){int src=k<2?a:b;System.arraycopy(v,src,q,k*GOURAUD,GOURAUD);float s=(k==0||k==3)?1:-1;q[k*GOURAUD]+=nx*s;q[k*GOURAUD+1]+=ny*s;}
			triGouraud(q,0,GOURAUD,2*GOURAUD);triGouraud(q,0,2*GOURAUD,3*GOURAUD);return;
		}
		int steps=(int)Math.ceil(Math.max(Math.abs(dx),Math.abs(dy)));
		for(int s=0;s<=steps;s++){
			float t=steps==0?0:(float)s/steps;
			int x=floor(x0+dx*t),y=floor(y0+dy*t);
			if(x<scissorLeft||x>=scissorRight||y<scissorTop||y>=scissorBottom)continue;
			float z=v[a+2]+(v[b+2]-v[a+2])*t,q=v[a+3]+(v[b+3]-v[a+3])*t;
			shade(y*width+x,perspectiveZ?-q:z,q,(v[a+4]+(v[b+4]-v[a+4])*t)*255,(v[a+5]+(v[b+5]-v[a+5])*t)*255,(v[a+6]+(v[b+6]-v[a+6])*t)*255,v[a+7]+(v[b+7]-v[a+7])*t);
		}
	}
	/** A bitmap with its upper left corner at TQAVGouraud vertex {@code o}, scaled by the bitmap scale; 1-bit bitmaps take the vertex colour. */
	void bitmap(float[] v,int o,Texture bm){
		if(bm==null||bm.pixels[0]==null)return;
		int w=bm.widths[0],h=bm.heights[0];int[] p=bm.pixels[0];
		float sx=bitmapScaleX>0?bitmapScaleX:1,sy=bitmapScaleY>0?bitmapScaleY:1;
		int x0=Math.round(v[o]),y0=Math.round(v[o+1]);int dw=Math.round(w*sx),dh=Math.round(h*sy);
		float key=perspectiveZ?-v[o+3]:v[o+2];
		for(int y=Math.max(scissorTop,y0);y<Math.min(scissorBottom,y0+dh);y++){
			int ty=Math.min(h-1,(int)((y-y0)/sy));
			for(int x=Math.max(scissorLeft,x0);x<Math.min(scissorRight,x0+dw);x++){
				int c=p[ty*w+Math.min(w-1,(int)((x-x0)/sx))];
				float a=(c>>>24)/255f;
				if(bm.mask)shade(y*width+x,key,v[o+3],v[o+4]*255,v[o+5]*255,v[o+6]*255,a*v[o+7]);
				else shade(y*width+x,key,v[o+3],(c>>>16)&255,(c>>>8)&255,c&255,a*v[o+7]);
			}
		}
	}
}

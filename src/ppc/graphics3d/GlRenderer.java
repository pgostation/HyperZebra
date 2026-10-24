import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenGL 1.2 fixed-function pipeline {@link PpcOpenGL} serves Mac OS 9's OpenGLLibrary with: one rendering context's
 * state, matrix stacks, display lists, texture objects, per-vertex lighting / fog / texture coordinate generation,
 * clipping, and a software rasterizer with the per-fragment operations (texture environments, fog, alpha, stencil and
 * depth tests, blending, masks) over its own colour, depth and stencil buffers.
 * <p>
 * Pure Java, no guest memory: the bridge decodes arguments and pixel data. Window coordinates are OpenGL's (origin at the
 * bottom left, pixel centres at x + 0.5, y + 0.5); {@link #color} is stored top row first, so row {@code height - 1 - y}
 * holds window row y. Texture images are stored as given, first row = t 0.
 */
final class GlRenderer {
	// ---- the enumerants this class interprets (gl.h)
	static final int POINTS=0,LINES=1,LINE_LOOP=2,LINE_STRIP=3,TRIANGLES=4,TRIANGLE_STRIP=5,TRIANGLE_FAN=6,QUADS=7,QUAD_STRIP=8,POLYGON=9;
	static final int NEVER=0x200,LESS=0x201,EQUAL=0x202,LEQUAL=0x203,GREATER=0x204,NOTEQUAL=0x205,GEQUAL=0x206,ALWAYS=0x207;
	static final int ZERO=0,ONE=1,SRC_COLOR=0x300,ONE_MINUS_SRC_COLOR=0x301,SRC_ALPHA=0x302,ONE_MINUS_SRC_ALPHA=0x303,DST_ALPHA=0x304,
		ONE_MINUS_DST_ALPHA=0x305,DST_COLOR=0x306,ONE_MINUS_DST_COLOR=0x307,SRC_ALPHA_SATURATE=0x308,CONSTANT_COLOR=0x8001,
		ONE_MINUS_CONSTANT_COLOR=0x8002,CONSTANT_ALPHA=0x8003,ONE_MINUS_CONSTANT_ALPHA=0x8004;
	static final int FUNC_ADD=0x8006,MIN=0x8007,MAX=0x8008,FUNC_SUBTRACT=0x800A,FUNC_REVERSE_SUBTRACT=0x800B;
	static final int FRONT=0x404,BACK=0x405,FRONT_AND_BACK=0x408,CW=0x900,CCW=0x901;
	static final int POINT=0x1B00,LINE=0x1B01,FILL=0x1B02,FLAT=0x1D00,SMOOTH=0x1D01;
	static final int KEEP=0x1E00,REPLACE=0x1E01,INCR=0x1E02,DECR=0x1E03,INVERT=0x150A,INCR_WRAP=0x8507,DECR_WRAP=0x8508;
	static final int MODELVIEW=0x1700,PROJECTION=0x1701,TEXTURE=0x1702;
	static final int MODULATE=0x2100,DECAL=0x2101,ADD=0x0104,BLEND_ENV=0x0BE2;
	static final int EYE_LINEAR=0x2400,OBJECT_LINEAR=0x2401,SPHERE_MAP=0x2402;
	static final int NEAREST=0x2600,LINEAR=0x2601,NEAREST_MIPMAP_NEAREST=0x2700,LINEAR_MIPMAP_NEAREST=0x2701,NEAREST_MIPMAP_LINEAR=0x2702,LINEAR_MIPMAP_LINEAR=0x2703;
	static final int CLAMP=0x2900,REPEAT=0x2901,CLAMP_TO_EDGE=0x812F;
	static final int EXP=0x800,EXP2=0x801;
	static final int AMBIENT=0x1200,DIFFUSE=0x1201,SPECULAR=0x1202,POSITION=0x1203,SPOT_DIRECTION=0x1204,SPOT_EXPONENT=0x1205,SPOT_CUTOFF=0x1206,
		CONSTANT_ATTENUATION=0x1207,LINEAR_ATTENUATION=0x1208,QUADRATIC_ATTENUATION=0x1209,EMISSION=0x1600,SHININESS=0x1601,AMBIENT_AND_DIFFUSE=0x1602;
	static final int COMPILE=0x1300,COMPILE_AND_EXECUTE=0x1301;
	static final int COLOR_BUFFER_BIT=0x4000,DEPTH_BUFFER_BIT=0x100,STENCIL_BUFFER_BIT=0x400;
	static final int NO_ERROR=0,INVALID_ENUM=0x500,INVALID_VALUE=0x501,INVALID_OPERATION=0x502,STACK_OVERFLOW=0x503,STACK_UNDERFLOW=0x504;
	// capabilities (glEnable)
	static final int CAP_CULL_FACE=0xB44,CAP_LIGHTING=0xB50,CAP_COLOR_MATERIAL=0xB57,CAP_FOG=0xB60,CAP_DEPTH_TEST=0xB71,CAP_STENCIL_TEST=0xB90,
		CAP_NORMALIZE=0xBA1,CAP_ALPHA_TEST=0xBC0,CAP_DITHER=0xBD0,CAP_BLEND=0xBE2,CAP_SCISSOR_TEST=0xC11,CAP_TEXTURE_GEN_S=0xC60,CAP_TEXTURE_GEN_T=0xC61,
		CAP_TEXTURE_GEN_R=0xC62,CAP_TEXTURE_GEN_Q=0xC63,CAP_TEXTURE_1D=0xDE0,CAP_TEXTURE_2D=0xDE1,CAP_POLYGON_OFFSET_FILL=0x8037,CAP_POLYGON_OFFSET_LINE=0x2A02,
		CAP_POLYGON_OFFSET_POINT=0x2A01,CAP_RESCALE_NORMAL=0x803A,CAP_CLIP_PLANE0=0x3000,CAP_LIGHT0=0x4000;
	// base internal formats of textures
	static final int F_ALPHA=0x1906,F_RGB=0x1907,F_RGBA=0x1908,F_LUMINANCE=0x1909,F_LUMINANCE_ALPHA=0x190A,F_INTENSITY=0x8049;

	static final int UNITS=2,LIGHTS=8,CLIP_PLANES=6,MAX_TEXTURE_SIZE=1024,STACK_DEPTH=32,ATTRIB_DEPTH=16;

	// ================================================================== buffers
	int width=1,height=1;
	/** ARGB, top row first. */
	int[] color=new int[1];
	float[] depth=new float[1];
	byte[] stencil=new byte[1];

	/** Sizes the buffers (contents cleared). */
	void resize(int w,int h){
		w=Math.max(1,w);h=Math.max(1,h);
		if(w==width&&h==height)return;
		width=w;height=h;color=new int[w*h];depth=new float[w*h];Arrays.fill(depth,1);stencil=new byte[w*h];
	}

	// ================================================================== state

	/** A texture object. */
	static final class Texture {
		final int name;
		int[][] levels=new int[1][];int[] widths=new int[1],heights=new int[1];
		int format=F_RGBA;
		int minFilter=NEAREST_MIPMAP_LINEAR,magFilter=LINEAR,wrapS=REPEAT,wrapT=REPEAT;
		float[] borderColor={0,0,0,0};
		float priority=1;
		boolean generateMipmap;
		Texture(int name){this.name=name;}
		int width(){return widths[0];}
		int height(){return heights[0];}
		/** Number of levels down to 1x1 a complete mipmap chain has. */
		int chainLength(){int w=widths[0],h=heights[0],n=1;while(w>1||h>1){w=Math.max(1,w>>1);h=Math.max(1,h>>1);n++;}return n;}
		boolean mipmapped(){return minFilter!=NEAREST&&minFilter!=LINEAR;}
		/** Highest mipmap level a sample may use; set by {@link #usable} for the primitive being drawn. */
		int maxLevel;
		/**
		 * Whether it can be used: level 0 present. OpenGL 1.2 3.8.9 also wants every level of the chain for a mipmap filter and
		 * disables texturing otherwise, but the Mac OS 9 drivers sampled the levels that are there: Last Contact sets
		 * GL_TEXTURE_MIN_FILTER only on the default texture and loads its named textures with one level (all white by the spec).
		 * {@link #maxLevel} becomes the last level of the leading run of correctly sized levels.
		 */
		boolean usable(){
			if(levels[0]==null||widths[0]==0||heights[0]==0)return false;
			maxLevel=0;if(!mipmapped())return true;
			int n=Math.min(chainLength(),levels.length),w=widths[0],h=heights[0];
			for(int i=1;i<n;i++){w=Math.max(1,w>>1);h=Math.max(1,h>>1);if(levels[i]==null||widths[i]!=w||heights[i]!=h)break;maxLevel=i;}
			return true;
		}
		void setLevel(int level,int w,int h,int[] argb){
			if(level>=levels.length){levels=Arrays.copyOf(levels,level+1);widths=Arrays.copyOf(widths,level+1);heights=Arrays.copyOf(heights,level+1);}
			levels[level]=argb;widths[level]=w;heights[level]=h;
		}
		/** Fills levels 1.. from level 0 with a 2x2 box filter (GL_GENERATE_MIPMAP_SGIS, gluBuild2DMipmaps). */
		void buildMipmaps(){
			int n=chainLength();
			for(int i=1;i<n;i++){
				int pw=widths[i-1],ph=heights[i-1],w=Math.max(1,pw>>1),h=Math.max(1,ph>>1);int[] p=levels[i-1],d=new int[w*h];
				for(int y=0;y<h;y++)for(int x=0;x<w;x++){
					int x0=Math.min(pw-1,x*2),x1=Math.min(pw-1,x*2+1),y0=Math.min(ph-1,y*2),y1=Math.min(ph-1,y*2+1);
					d[y*w+x]=average(p[y0*pw+x0],p[y0*pw+x1],p[y1*pw+x0],p[y1*pw+x1]);
				}
				setLevel(i,w,h,d);
			}
		}
	}
	static int average(int a,int b,int c,int d){
		int r=0;for(int s=0;s<32;s+=8)r|=((((a>>>s)&255)+((b>>>s)&255)+((c>>>s)&255)+((d>>>s)&255)+2)>>2)<<s;
		return r;
	}

	static final class Light {
		float[] ambient={0,0,0,1},diffuse={0,0,0,1},specular={0,0,0,1},position={0,0,1,0},spotDirection={0,0,-1};
		float spotExponent,spotCutoff=180,constant=1,linear,quadratic;
		Light copy(){Light l=new Light();l.ambient=ambient.clone();l.diffuse=diffuse.clone();l.specular=specular.clone();l.position=position.clone();
			l.spotDirection=spotDirection.clone();l.spotExponent=spotExponent;l.spotCutoff=spotCutoff;l.constant=constant;l.linear=linear;l.quadratic=quadratic;return l;}
	}
	static final class Material {
		float[] ambient={0.2f,0.2f,0.2f,1},diffuse={0.8f,0.8f,0.8f,1},specular={0,0,0,1},emission={0,0,0,1};float shininess;
		Material copy(){Material m=new Material();m.ambient=ambient.clone();m.diffuse=diffuse.clone();m.specular=specular.clone();m.emission=emission.clone();m.shininess=shininess;return m;}
	}
	/** Per texture unit state. */
	static final class Unit {
		boolean enabled2D,enabled1D;boolean[] gen=new boolean[4];int[] genMode={EYE_LINEAR,EYE_LINEAR,EYE_LINEAR,EYE_LINEAR};
		float[][] objectPlane={{1,0,0,0},{0,1,0,0},{0,0,0,0},{0,0,0,0}},eyePlane={{1,0,0,0},{0,1,0,0},{0,0,0,0},{0,0,0,0}};
		int envMode=MODULATE;float[] envColor={0,0,0,0};
		int binding2D,binding1D;
		Unit copy(){Unit u=new Unit();u.enabled2D=enabled2D;u.enabled1D=enabled1D;u.gen=gen.clone();u.genMode=genMode.clone();
			u.objectPlane=deep(objectPlane);u.eyePlane=deep(eyePlane);u.envMode=envMode;u.envColor=envColor.clone();u.binding2D=binding2D;u.binding1D=binding1D;return u;}
	}
	private static float[][] deep(float[][] a){float[][] r=new float[a.length][];for(int i=0;i<a.length;i++)r[i]=a[i].clone();return r;}

	/** The attribute state glPushAttrib saves (all of it, whatever the mask). */
	static final class State implements Cloneable {
		float[] clearColor={0,0,0,0};float clearDepth=1;int clearStencil;
		int vpX,vpY,vpW,vpH;float depthNear=0,depthFar=1;
		boolean cullFace,lighting,colorMaterial,fog,depthTest,stencilTest,normalize,rescaleNormal,alphaTest,blend,scissorTest,dither=true,
			polygonOffsetFill,polygonOffsetLine,polygonOffsetPoint,lightModelLocalViewer,lightModelTwoSide,separateSpecular;
		boolean[] lightEnabled=new boolean[LIGHTS],clipEnabled=new boolean[CLIP_PLANES];
		int cullMode=BACK,frontFace=CCW,shadeModel=SMOOTH,polygonModeFront=FILL,polygonModeBack=FILL;
		int depthFunc=LESS;boolean depthMask=true;boolean[] colorMask={true,true,true,true};
		int blendSrc=ONE,blendDst=ZERO,blendEquation=FUNC_ADD;float[] blendColor={0,0,0,0};
		int alphaFunc=ALWAYS;float alphaRef;
		int stencilFunc=ALWAYS,stencilRef,stencilValueMask=0xff,stencilWriteMask=0xff,stencilFail=KEEP,stencilZFail=KEEP,stencilZPass=KEEP;
		int scX,scY,scW,scH;
		int fogMode=EXP;float fogDensity=1,fogStart=0,fogEnd=1;float[] fogColor={0,0,0,0};
		float[] lightModelAmbient={0.2f,0.2f,0.2f,1};
		int colorMaterialFace=FRONT_AND_BACK,colorMaterialMode=AMBIENT_AND_DIFFUSE;
		Light[] lights=new Light[LIGHTS];Material front=new Material(),back=new Material();
		float[][] clipPlanes=new float[CLIP_PLANES][4];
		float pointSize=1,lineWidth=1,polygonOffsetFactor,polygonOffsetUnits;
		Unit[] units=new Unit[UNITS];int activeUnit;
		float[] color={1,1,1,1},normal={0,0,1};float[][] texCoord=new float[UNITS][];
		float zoomX=1,zoomY=1;
		State(){for(int i=0;i<LIGHTS;i++)lights[i]=new Light();lights[0].diffuse=new float[]{1,1,1,1};lights[0].specular=new float[]{1,1,1,1};
			for(int i=0;i<UNITS;i++){units[i]=new Unit();texCoord[i]=new float[]{0,0,0,1};}}
		State copy(){
			try{State s=(State)super.clone();
				s.clearColor=clearColor.clone();s.lightEnabled=lightEnabled.clone();s.clipEnabled=clipEnabled.clone();s.colorMask=colorMask.clone();
				s.blendColor=blendColor.clone();s.fogColor=fogColor.clone();s.lightModelAmbient=lightModelAmbient.clone();
				s.lights=new Light[LIGHTS];for(int i=0;i<LIGHTS;i++)s.lights[i]=lights[i].copy();s.front=front.copy();s.back=back.copy();
				s.clipPlanes=deep(clipPlanes);s.units=new Unit[UNITS];for(int i=0;i<UNITS;i++)s.units[i]=units[i].copy();
				s.color=color.clone();s.normal=normal.clone();s.texCoord=deep(texCoord);return s;
			}catch(CloneNotSupportedException e){throw new AssertionError(e);}
		}
	}
	State s=new State();
	private final ArrayList<State> attribStack=new ArrayList<State>();
	int error=NO_ERROR;
	void error(int e){if(error==NO_ERROR)error=e;}
	int getError(){int e=error;error=NO_ERROR;return e;}

	// matrices (column-major, as OpenGL)
	int matrixMode=MODELVIEW;
	private final ArrayList<double[]> modelview=new ArrayList<double[]>(),projection=new ArrayList<double[]>();
	private final List<ArrayList<double[]>> textureMatrices=new ArrayList<ArrayList<double[]>>();
	private double[] mv,proj;
	private final double[][] tex=new double[UNITS][];
	private boolean normalMatrixDirty=true;private final double[] normalMatrix=new double[9];
	private final boolean[] texIdentity=new boolean[UNITS];

	final Map<Integer,Texture> textures=new HashMap<Integer,Texture>();
	private final Texture[] defaultTextures=new Texture[UNITS];
	int nextTextureName=1;

	GlRenderer(){
		modelview.add(identity());projection.add(identity());mv=modelview.get(0);proj=projection.get(0);
		for(int i=0;i<UNITS;i++){ArrayList<double[]> t=new ArrayList<double[]>();t.add(identity());textureMatrices.add(t);tex[i]=t.get(0);texIdentity[i]=true;defaultTextures[i]=new Texture(0);}
	}

	/** Sets the viewport and scissor box to a drawable's size (what OpenGL does when a context first gets a drawable). */
	void initialViewport(int w,int h){s.vpX=0;s.vpY=0;s.vpW=w;s.vpH=h;s.scX=0;s.scY=0;s.scW=w;s.scH=h;}

	// ================================================================== matrices

	static double[] identity(){double[] m=new double[16];m[0]=m[5]=m[10]=m[15]=1;return m;}
	private ArrayList<double[]> stack(){return matrixMode==PROJECTION?projection:matrixMode==TEXTURE?textureMatrices.get(s.activeUnit):modelview;}
	private double[] current(){ArrayList<double[]> st=stack();return st.get(st.size()-1);}
	private void matrixChanged(){
		mv=modelview.get(modelview.size()-1);proj=projection.get(projection.size()-1);
		for(int i=0;i<UNITS;i++){ArrayList<double[]> t=textureMatrices.get(i);tex[i]=t.get(t.size()-1);texIdentity[i]=isIdentity(tex[i]);}
		normalMatrixDirty=true;
	}
	private static boolean isIdentity(double[] m){for(int i=0;i<16;i++)if(m[i]!=((i%5)==0?1:0))return false;return true;}
	void matrixMode(int mode){if(mode!=MODELVIEW&&mode!=PROJECTION&&mode!=TEXTURE){error(INVALID_ENUM);return;}matrixMode=mode;}
	void loadIdentity(){System.arraycopy(identity(),0,current(),0,16);matrixChanged();}
	void loadMatrix(double[] m){System.arraycopy(m,0,current(),0,16);matrixChanged();}
	void multMatrix(double[] m){double[] c=current();System.arraycopy(mul(c,m),0,c,0,16);matrixChanged();}
	void pushMatrix(){ArrayList<double[]> st=stack();if(st.size()>=STACK_DEPTH){error(STACK_OVERFLOW);return;}st.add(st.get(st.size()-1).clone());matrixChanged();}
	void popMatrix(){ArrayList<double[]> st=stack();if(st.size()<=1){error(STACK_UNDERFLOW);return;}st.remove(st.size()-1);matrixChanged();}
	void translate(double x,double y,double z){double[] m=identity();m[12]=x;m[13]=y;m[14]=z;multMatrix(m);}
	void scale(double x,double y,double z){double[] m=identity();m[0]=x;m[5]=y;m[10]=z;multMatrix(m);}
	void rotate(double angle,double x,double y,double z){
		double len=Math.sqrt(x*x+y*y+z*z);if(len==0)return;x/=len;y/=len;z/=len;
		double a=Math.toRadians(angle),c=Math.cos(a),sn=Math.sin(a),t=1-c;
		double[] m=identity();
		m[0]=x*x*t+c;m[4]=x*y*t-z*sn;m[8]=x*z*t+y*sn;
		m[1]=y*x*t+z*sn;m[5]=y*y*t+c;m[9]=y*z*t-x*sn;
		m[2]=x*z*t-y*sn;m[6]=y*z*t+x*sn;m[10]=z*z*t+c;
		multMatrix(m);
	}
	void frustum(double l,double r,double b,double t,double n,double f){
		if(n<=0||f<=0||l==r||b==t||n==f){error(INVALID_VALUE);return;}
		double[] m=new double[16];
		m[0]=2*n/(r-l);m[5]=2*n/(t-b);m[8]=(r+l)/(r-l);m[9]=(t+b)/(t-b);m[10]=-(f+n)/(f-n);m[11]=-1;m[14]=-2*f*n/(f-n);
		multMatrix(m);
	}
	void ortho(double l,double r,double b,double t,double n,double f){
		if(l==r||b==t||n==f){error(INVALID_VALUE);return;}
		double[] m=identity();
		m[0]=2/(r-l);m[5]=2/(t-b);m[10]=-2/(f-n);m[12]=-(r+l)/(r-l);m[13]=-(t+b)/(t-b);m[14]=-(f+n)/(f-n);
		multMatrix(m);
	}
	static double[] mul(double[] a,double[] b){
		double[] c=new double[16];
		for(int col=0;col<4;col++)for(int row=0;row<4;row++){double v=0;for(int k=0;k<4;k++)v+=a[k*4+row]*b[col*4+k];c[col*4+row]=v;}
		return c;
	}
	/** The current matrix of a mode (for glGet). */
	double[] matrix(int mode){
		if(mode==PROJECTION)return proj;if(mode==TEXTURE)return tex[s.activeUnit];return mv;
	}
	int stackDepth(int mode){return mode==PROJECTION?projection.size():mode==TEXTURE?textureMatrices.get(s.activeUnit).size():modelview.size();}
	/** The inverse transpose of the modelview's upper 3x3 (row-major 3x3), for normals. */
	private double[] normalMatrix(){
		if(!normalMatrixDirty)return normalMatrix;
		double a=mv[0],b=mv[4],c=mv[8],d=mv[1],e=mv[5],f=mv[9],g=mv[2],h=mv[6],i=mv[10];
		double A=e*i-f*h,B=-(d*i-f*g),C=d*h-e*g,det=a*A+b*B+c*C;
		if(det==0)det=1;double inv=1/det;
		// inverse = adj/det; transpose of inverse: row-major
		normalMatrix[0]=A*inv;normalMatrix[1]=B*inv;normalMatrix[2]=C*inv;
		normalMatrix[3]=-(b*i-c*h)*inv;normalMatrix[4]=(a*i-c*g)*inv;normalMatrix[5]=-(a*h-b*g)*inv;
		normalMatrix[6]=(b*f-c*e)*inv;normalMatrix[7]=-(a*f-c*d)*inv;normalMatrix[8]=(a*e-b*d)*inv;
		normalMatrixDirty=false;return normalMatrix;
	}
	private static double[] invert(double[] m){
		double[] inv=new double[16];
		inv[0]=m[5]*m[10]*m[15]-m[5]*m[11]*m[14]-m[9]*m[6]*m[15]+m[9]*m[7]*m[14]+m[13]*m[6]*m[11]-m[13]*m[7]*m[10];
		inv[4]=-m[4]*m[10]*m[15]+m[4]*m[11]*m[14]+m[8]*m[6]*m[15]-m[8]*m[7]*m[14]-m[12]*m[6]*m[11]+m[12]*m[7]*m[10];
		inv[8]=m[4]*m[9]*m[15]-m[4]*m[11]*m[13]-m[8]*m[5]*m[15]+m[8]*m[7]*m[13]+m[12]*m[5]*m[11]-m[12]*m[7]*m[9];
		inv[12]=-m[4]*m[9]*m[14]+m[4]*m[10]*m[13]+m[8]*m[5]*m[14]-m[8]*m[6]*m[13]-m[12]*m[5]*m[10]+m[12]*m[6]*m[9];
		inv[1]=-m[1]*m[10]*m[15]+m[1]*m[11]*m[14]+m[9]*m[2]*m[15]-m[9]*m[3]*m[14]-m[13]*m[2]*m[11]+m[13]*m[3]*m[10];
		inv[5]=m[0]*m[10]*m[15]-m[0]*m[11]*m[14]-m[8]*m[2]*m[15]+m[8]*m[3]*m[14]+m[12]*m[2]*m[11]-m[12]*m[3]*m[10];
		inv[9]=-m[0]*m[9]*m[15]+m[0]*m[11]*m[13]+m[8]*m[1]*m[15]-m[8]*m[3]*m[13]-m[12]*m[1]*m[11]+m[12]*m[3]*m[9];
		inv[13]=m[0]*m[9]*m[14]-m[0]*m[10]*m[13]-m[8]*m[1]*m[14]+m[8]*m[2]*m[13]+m[12]*m[1]*m[10]-m[12]*m[2]*m[9];
		inv[2]=m[1]*m[6]*m[15]-m[1]*m[7]*m[14]-m[5]*m[2]*m[15]+m[5]*m[3]*m[14]+m[13]*m[2]*m[7]-m[13]*m[3]*m[6];
		inv[6]=-m[0]*m[6]*m[15]+m[0]*m[7]*m[14]+m[4]*m[2]*m[15]-m[4]*m[3]*m[14]-m[12]*m[2]*m[7]+m[12]*m[3]*m[6];
		inv[10]=m[0]*m[5]*m[15]-m[0]*m[7]*m[13]-m[4]*m[1]*m[15]+m[4]*m[3]*m[13]+m[12]*m[1]*m[7]-m[12]*m[3]*m[5];
		inv[14]=-m[0]*m[5]*m[14]+m[0]*m[6]*m[13]+m[4]*m[1]*m[14]-m[4]*m[2]*m[13]-m[12]*m[1]*m[6]+m[12]*m[2]*m[5];
		inv[3]=-m[1]*m[6]*m[11]+m[1]*m[7]*m[10]+m[5]*m[2]*m[11]-m[5]*m[3]*m[10]-m[9]*m[2]*m[7]+m[9]*m[3]*m[6];
		inv[7]=m[0]*m[6]*m[11]-m[0]*m[7]*m[10]-m[4]*m[2]*m[11]+m[4]*m[3]*m[10]+m[8]*m[2]*m[7]-m[8]*m[3]*m[6];
		inv[11]=-m[0]*m[5]*m[11]+m[0]*m[7]*m[9]+m[4]*m[1]*m[11]-m[4]*m[3]*m[9]-m[8]*m[1]*m[7]+m[8]*m[3]*m[5];
		inv[15]=m[0]*m[5]*m[10]-m[0]*m[6]*m[9]-m[4]*m[1]*m[10]+m[4]*m[2]*m[9]+m[8]*m[1]*m[6]-m[8]*m[2]*m[5];
		double det=m[0]*inv[0]+m[1]*inv[4]+m[2]*inv[8]+m[3]*inv[12];
		if(det==0)return identity();
		for(int i=0;i<16;i++)inv[i]/=det;
		return inv;
	}

	// ================================================================== enables and state setters

	boolean setCapability(int cap,boolean on){
		State t=s;
		if(cap>=CAP_LIGHT0&&cap<CAP_LIGHT0+LIGHTS){t.lightEnabled[cap-CAP_LIGHT0]=on;return true;}
		if(cap>=CAP_CLIP_PLANE0&&cap<CAP_CLIP_PLANE0+CLIP_PLANES){t.clipEnabled[cap-CAP_CLIP_PLANE0]=on;return true;}
		Unit u=t.units[t.activeUnit];
		switch(cap){
			case CAP_CULL_FACE:t.cullFace=on;break;
			case CAP_LIGHTING:t.lighting=on;break;
			case CAP_COLOR_MATERIAL:t.colorMaterial=on;if(on)applyColorMaterial();break;
			case CAP_FOG:t.fog=on;break;
			case CAP_DEPTH_TEST:t.depthTest=on;break;
			case CAP_STENCIL_TEST:t.stencilTest=on;break;
			case CAP_NORMALIZE:t.normalize=on;break;
			case CAP_RESCALE_NORMAL:t.rescaleNormal=on;break;
			case CAP_ALPHA_TEST:t.alphaTest=on;break;
			case CAP_DITHER:t.dither=on;break;
			case CAP_BLEND:t.blend=on;break;
			case CAP_SCISSOR_TEST:t.scissorTest=on;break;
			case CAP_TEXTURE_2D:u.enabled2D=on;break;
			case CAP_TEXTURE_1D:u.enabled1D=on;break;
			case CAP_TEXTURE_GEN_S:case CAP_TEXTURE_GEN_T:case CAP_TEXTURE_GEN_R:case CAP_TEXTURE_GEN_Q:u.gen[cap-CAP_TEXTURE_GEN_S]=on;break;
			case CAP_POLYGON_OFFSET_FILL:t.polygonOffsetFill=on;break;
			case CAP_POLYGON_OFFSET_LINE:t.polygonOffsetLine=on;break;
			case CAP_POLYGON_OFFSET_POINT:t.polygonOffsetPoint=on;break;
			// accepted, not drawn differently: smoothing, stipples, logic ops, colour sum, normal maps, multisample
			case 0xB10:case 0xB20:case 0xB24:case 0xB41:case 0xB42:case 0xBF1:case 0xBF2:case 0xC52:case 0x0D90:case 0x0DB0:case 0x0DB1:case 0x0DB2:
			case 0x0DB7:case 0x0DB8:case 0x0D92:case 0x0D93:case 0x0D94:case 0x0D95:case 0x0D96:case 0x0D97:case 0x0D98:case 0x0D91:case 0x809D:
			case 0x8458:case 0x806F:case 0x8513:break;
			default:return false;
		}
		return true;
	}
	/** glIsEnabled; null for an unknown capability. */
	Boolean isEnabled(int cap){
		State t=s;Unit u=t.units[t.activeUnit];
		if(cap>=CAP_LIGHT0&&cap<CAP_LIGHT0+LIGHTS)return t.lightEnabled[cap-CAP_LIGHT0];
		if(cap>=CAP_CLIP_PLANE0&&cap<CAP_CLIP_PLANE0+CLIP_PLANES)return t.clipEnabled[cap-CAP_CLIP_PLANE0];
		switch(cap){
			case CAP_CULL_FACE:return t.cullFace;case CAP_LIGHTING:return t.lighting;case CAP_COLOR_MATERIAL:return t.colorMaterial;case CAP_FOG:return t.fog;
			case CAP_DEPTH_TEST:return t.depthTest;case CAP_STENCIL_TEST:return t.stencilTest;case CAP_NORMALIZE:return t.normalize;case CAP_RESCALE_NORMAL:return t.rescaleNormal;
			case CAP_ALPHA_TEST:return t.alphaTest;case CAP_DITHER:return t.dither;case CAP_BLEND:return t.blend;case CAP_SCISSOR_TEST:return t.scissorTest;
			case CAP_TEXTURE_2D:return u.enabled2D;case CAP_TEXTURE_1D:return u.enabled1D;
			case CAP_TEXTURE_GEN_S:case CAP_TEXTURE_GEN_T:case CAP_TEXTURE_GEN_R:case CAP_TEXTURE_GEN_Q:return u.gen[cap-CAP_TEXTURE_GEN_S];
			case CAP_POLYGON_OFFSET_FILL:return t.polygonOffsetFill;case CAP_POLYGON_OFFSET_LINE:return t.polygonOffsetLine;case CAP_POLYGON_OFFSET_POINT:return t.polygonOffsetPoint;
			default:return null;
		}
	}
	void pushAttrib(){if(attribStack.size()>=ATTRIB_DEPTH){error(STACK_OVERFLOW);return;}attribStack.add(s.copy());}
	void popAttrib(){
		if(attribStack.isEmpty()){error(STACK_UNDERFLOW);return;}
		State old=attribStack.remove(attribStack.size()-1);
		// the current vertex attributes belong to GL_CURRENT_BIT, which programs rarely push: keep them
		old.color=s.color;old.normal=s.normal;old.texCoord=s.texCoord;
		s=old;
	}

	// ---- lights and materials
	void light(int light,int pname,float[] p){
		int i=light-CAP_LIGHT0;if(i<0||i>=LIGHTS){error(INVALID_ENUM);return;}
		Light l=s.lights[i];
		switch(pname){
			case AMBIENT:l.ambient=copy4(p);break;case DIFFUSE:l.diffuse=copy4(p);break;case SPECULAR:l.specular=copy4(p);break;
			case POSITION:{double[] e=transform(mv,p[0],p[1],p[2],p.length>3?p[3]:1);l.position=new float[]{(float)e[0],(float)e[1],(float)e[2],(float)e[3]};break;}
			case SPOT_DIRECTION:{l.spotDirection=new float[]{(float)(mv[0]*p[0]+mv[4]*p[1]+mv[8]*p[2]),(float)(mv[1]*p[0]+mv[5]*p[1]+mv[9]*p[2]),(float)(mv[2]*p[0]+mv[6]*p[1]+mv[10]*p[2])};break;}
			case SPOT_EXPONENT:l.spotExponent=p[0];break;case SPOT_CUTOFF:l.spotCutoff=p[0];break;
			case CONSTANT_ATTENUATION:l.constant=p[0];break;case LINEAR_ATTENUATION:l.linear=p[0];break;case QUADRATIC_ATTENUATION:l.quadratic=p[0];break;
			default:error(INVALID_ENUM);
		}
	}
	float[] getLight(int light,int pname){
		int i=light-CAP_LIGHT0;if(i<0||i>=LIGHTS)return null;Light l=s.lights[i];
		switch(pname){case AMBIENT:return l.ambient;case DIFFUSE:return l.diffuse;case SPECULAR:return l.specular;case POSITION:return l.position;case SPOT_DIRECTION:return l.spotDirection;
			case SPOT_EXPONENT:return new float[]{l.spotExponent};case SPOT_CUTOFF:return new float[]{l.spotCutoff};case CONSTANT_ATTENUATION:return new float[]{l.constant};
			case LINEAR_ATTENUATION:return new float[]{l.linear};case QUADRATIC_ATTENUATION:return new float[]{l.quadratic};default:return null;}
	}
	private static float[] copy4(float[] p){return new float[]{p[0],p.length>1?p[1]:0,p.length>2?p[2]:0,p.length>3?p[3]:1};}
	void material(int face,int pname,float[] p){
		if(face==FRONT||face==FRONT_AND_BACK)material(s.front,pname,p);
		if(face==BACK||face==FRONT_AND_BACK)material(s.back,pname,p);
	}
	private void material(Material m,int pname,float[] p){
		switch(pname){
			case AMBIENT:m.ambient=copy4(p);break;case DIFFUSE:m.diffuse=copy4(p);break;case SPECULAR:m.specular=copy4(p);break;case EMISSION:m.emission=copy4(p);break;
			case SHININESS:m.shininess=Math.max(0,Math.min(128,p[0]));break;
			case AMBIENT_AND_DIFFUSE:m.ambient=copy4(p);m.diffuse=copy4(p);break;
			case 0x1603:break; // GL_COLOR_INDEXES
			default:error(INVALID_ENUM);
		}
	}
	float[] getMaterial(int face,int pname){
		Material m=face==BACK?s.back:s.front;
		switch(pname){case AMBIENT:return m.ambient;case DIFFUSE:return m.diffuse;case SPECULAR:return m.specular;case EMISSION:return m.emission;case SHININESS:return new float[]{m.shininess};default:return null;}
	}
	void lightModel(int pname,float[] p){
		switch(pname){
			case 0xB53:s.lightModelAmbient=copy4(p);break; // GL_LIGHT_MODEL_AMBIENT
			case 0xB51:s.lightModelLocalViewer=p[0]!=0;break;
			case 0xB52:s.lightModelTwoSide=p[0]!=0;break;
			case 0x81F8:s.separateSpecular=(int)p[0]==0x81FA;break; // GL_LIGHT_MODEL_COLOR_CONTROL
			default:error(INVALID_ENUM);
		}
	}
	void colorMaterial(int face,int mode){s.colorMaterialFace=face;s.colorMaterialMode=mode;if(s.colorMaterial)applyColorMaterial();}
	private void applyColorMaterial(){float[] c=s.color;int face=s.colorMaterialFace;
		if(face==FRONT||face==FRONT_AND_BACK)material(s.front,s.colorMaterialMode,c);
		if(face==BACK||face==FRONT_AND_BACK)material(s.back,s.colorMaterialMode,c);}

	// ---- current attributes
	void color(float r,float g,float b,float a){float[] c=s.color;c[0]=r;c[1]=g;c[2]=b;c[3]=a;if(s.colorMaterial)applyColorMaterial();}
	void normal(float x,float y,float z){float[] n=s.normal;n[0]=x;n[1]=y;n[2]=z;}
	void texCoord(int unit,float a,float b,float c,float d){if(unit<0||unit>=UNITS)return;float[] t=s.texCoord[unit];t[0]=a;t[1]=b;t[2]=c;t[3]=d;}
	void activeTexture(int unit){if(unit<0||unit>=UNITS){error(INVALID_ENUM);return;}s.activeUnit=unit;}

	void texGen(int coord,int pname,float[] p){
		int c=coord-0x2000;if(c<0||c>3){error(INVALID_ENUM);return;}Unit u=s.units[s.activeUnit];
		switch(pname){
			case 0x2500:u.genMode[c]=(int)p[0];break; // GL_TEXTURE_GEN_MODE
			case 0x2501:u.objectPlane[c]=copy4(p);break; // GL_OBJECT_PLANE
			case 0x2502:{double[] inv=invert(mv);float[] q=copy4(p);float[] e=new float[4]; // GL_EYE_PLANE: p * M^-1
				for(int j=0;j<4;j++)e[j]=(float)(q[0]*inv[j*4]+q[1]*inv[j*4+1]+q[2]*inv[j*4+2]+q[3]*inv[j*4+3]);
				u.eyePlane[c]=e;break;}
			default:error(INVALID_ENUM);
		}
	}
	void texEnv(int pname,float[] p){
		Unit u=s.units[s.activeUnit];
		if(pname==0x2200)u.envMode=(int)p[0]; // GL_TEXTURE_ENV_MODE
		else if(pname==0x2201)u.envColor=copy4(p); // GL_TEXTURE_ENV_COLOR
	}
	void clipPlane(int plane,double[] eq){
		int i=plane-CAP_CLIP_PLANE0;if(i<0||i>=CLIP_PLANES){error(INVALID_ENUM);return;}
		double[] inv=invert(mv);float[] e=new float[4];
		for(int j=0;j<4;j++)e[j]=(float)(eq[0]*inv[j*4]+eq[1]*inv[j*4+1]+eq[2]*inv[j*4+2]+eq[3]*inv[j*4+3]);
		s.clipPlanes[i]=e;
	}
	void fog(int pname,float[] p){
		switch(pname){
			case 0xB65:s.fogMode=(int)p[0];break;case 0xB62:s.fogDensity=p[0];break;case 0xB63:s.fogStart=p[0];break;case 0xB64:s.fogEnd=p[0];break;
			case 0xB66:s.fogColor=copy4(p);break;case 0xB61:break;case 0x8450:break; // FOG_INDEX, FOG_COORDINATE_SOURCE
			default:error(INVALID_ENUM);
		}
	}

	// ---- textures
	Texture boundTexture(){Unit u=s.units[s.activeUnit];return texture(u.binding2D,s.activeUnit);}
	private Texture texture(int name,int unit){if(name==0)return defaultTextures[unit];Texture t=textures.get(name);if(t==null){t=new Texture(name);textures.put(name,t);}return t;}
	void bindTexture(int target,int name){
		Unit u=s.units[s.activeUnit];
		if(target==0xDE0)u.binding1D=name;else u.binding2D=name;
		if(name!=0)texture(name,s.activeUnit);
		if(name>=nextTextureName)nextTextureName=name+1;
	}
	int[] genTextures(int n){int[] r=new int[n];for(int i=0;i<n;i++){while(textures.containsKey(nextTextureName))nextTextureName++;r[i]=nextTextureName;textures.put(nextTextureName,new Texture(nextTextureName));nextTextureName++;}return r;}
	void deleteTextures(int[] names){for(int n:names){if(n==0)continue;textures.remove(n);for(Unit u:s.units){if(u.binding2D==n)u.binding2D=0;if(u.binding1D==n)u.binding1D=0;}}}
	boolean isTexture(int name){return name!=0&&textures.containsKey(name);}
	void texParameter(int pname,float[] p){
		Texture t=boundTexture();
		switch(pname){
			case 0x2800:t.magFilter=(int)p[0];break;case 0x2801:t.minFilter=(int)p[0];break;
			case 0x2802:t.wrapS=(int)p[0];break;case 0x2803:t.wrapT=(int)p[0];break;
			case 0x1004:t.borderColor=copy4(p);break;case 0x8066:t.priority=p[0];break;
			case 0x8191:t.generateMipmap=p[0]!=0;break; // GL_GENERATE_MIPMAP_SGIS
			case 0x8072:case 0x813A:case 0x813B:case 0x813C:case 0x813D:break; // wrap R, LOD range: accepted
			default:error(INVALID_ENUM);
		}
	}
	/** glTexImage2D with the pixels already decoded to ARGB ({@code argb} null: storage only). */
	void texImage(int level,int baseFormat,int w,int h,int[] argb){
		Texture t=boundTexture();
		if(level==0)t.format=baseFormat;
		t.setLevel(level,w,h,argb!=null?argb:new int[Math.max(1,w*h)]);
		if(level==0&&t.generateMipmap)t.buildMipmaps();
	}
	void texSubImage(int level,int x,int y,int w,int h,int[] argb){
		Texture t=boundTexture();if(level>=t.levels.length||t.levels[level]==null){error(INVALID_OPERATION);return;}
		int tw=t.widths[level],th=t.heights[level];int[] d=t.levels[level];
		for(int j=0;j<h;j++){int ty=y+j;if(ty<0||ty>=th)continue;for(int i=0;i<w;i++){int tx=x+i;if(tx<0||tx>=tw)continue;d[ty*tw+tx]=argb[j*w+i];}}
		if(level==0&&t.generateMipmap)t.buildMipmaps();
	}
	/** Reads a rectangle of the colour buffer in window coordinates, bottom row first (glReadPixels, glCopyTexImage). */
	int[] readColor(int x,int y,int w,int h){
		int[] r=new int[Math.max(0,w*h)];
		for(int j=0;j<h;j++){int wy=y+j;if(wy<0||wy>=height)continue;int row=(height-1-wy)*width;
			for(int i=0;i<w;i++){int wx=x+i;if(wx>=0&&wx<width)r[j*w+i]=color[row+wx];}}
		return r;
	}
	float[] readDepth(int x,int y,int w,int h){
		float[] r=new float[Math.max(0,w*h)];
		for(int j=0;j<h;j++){int wy=y+j;if(wy<0||wy>=height)continue;int row=(height-1-wy)*width;
			for(int i=0;i<w;i++){int wx=x+i;if(wx>=0&&wx<width)r[j*w+i]=depth[row+wx];}}
		return r;
	}

	// ================================================================== display lists

	interface Command { void run(GlRenderer g); }
	final Map<Integer,List<Command>> lists=new HashMap<Integer,List<Command>>();
	private List<Command> compiling;private int compilingName;boolean compileAndExecute;
	int listBase;private int listDepth;
	boolean compiling(){return compiling!=null;}
	int compilingName(){return compilingName;}
	/** Runs a command, or records it while a display list is being compiled. */
	void submit(Command c){
		if(compiling!=null){compiling.add(c);if(!compileAndExecute)return;}
		c.run(this);
	}
	int genLists(int range){
		if(range<=0)return 0;
		int base=1;
		outer:for(;;base++){for(int i=0;i<range;i++)if(lists.containsKey(base+i)){base+=i;continue outer;}break;}
		for(int i=0;i<range;i++)lists.put(base+i,new ArrayList<Command>());
		return base;
	}
	void newList(int name,int mode){
		if(compiling!=null){error(INVALID_OPERATION);return;}
		if(name==0){error(INVALID_VALUE);return;}
		compiling=new ArrayList<Command>();compilingName=name;compileAndExecute=mode==COMPILE_AND_EXECUTE;
	}
	void endList(){if(compiling==null){error(INVALID_OPERATION);return;}lists.put(compilingName,compiling);compiling=null;compilingName=0;}
	void callList(int name){
		List<Command> l=lists.get(name);if(l==null||listDepth>=64)return;
		listDepth++;try{for(Command c:l)c.run(this);}finally{listDepth--;}
	}
	void deleteLists(int list,int range){for(int i=0;i<range;i++)lists.remove(list+i);}
	boolean isList(int name){return lists.containsKey(name);}

	// ================================================================== vertices

	/** Floats per transformed vertex. */
	static final int V=28;
	static final int XC=0,YC=1,ZC=2,WC=3,R=4,G=5,B=6,A=7,BR=8,BG=9,BB=10,BA=11,S0=12,T0=13,Q0=14,S1=15,T1=16,Q1=17,FOGF=18,EX=19,EY=20,EZ=21,EW=22,
		WX=23,WY=24,WZ=25,IW=26;
	private int primitive=-1;
	private final ArrayList<float[]> vertices=new ArrayList<float[]>();
	boolean inBegin(){return primitive>=0;}

	void begin(int mode){
		if(primitive>=0){error(INVALID_OPERATION);return;}
		if(mode<POINTS||mode>POLYGON){error(INVALID_ENUM);return;}
		primitive=mode;vertices.clear();
	}
	void end(){
		if(primitive<0){error(INVALID_OPERATION);return;}
		int mode=primitive;primitive=-1;
		assemble(mode,vertices);
		vertices.clear();
	}
	void vertex(float x,float y,float z,float w){
		if(primitive<0)return; // outside Begin/End: undefined, ignored
		vertices.add(process(x,y,z,w));
	}
	/** A rectangle (glRect): a polygon at z 0. */
	void rect(float x1,float y1,float x2,float y2){begin(POLYGON);vertex(x1,y1,0,1);vertex(x2,y1,0,1);vertex(x2,y2,0,1);vertex(x1,y2,0,1);end();}

	private static double[] transform(double[] m,double x,double y,double z,double w){
		return new double[]{m[0]*x+m[4]*y+m[8]*z+m[12]*w,m[1]*x+m[5]*y+m[9]*z+m[13]*w,m[2]*x+m[6]*y+m[10]*z+m[14]*w,m[3]*x+m[7]*y+m[11]*z+m[15]*w};
	}

	/** Object coordinates to a vertex record: eye and clip coordinates, lit colours, texture coordinates, fog factor. */
	private float[] process(float x,float y,float z,float w){
		float[] v=new float[V];
		double[] m=mv;
		double ex=m[0]*x+m[4]*y+m[8]*z+m[12]*w,ey=m[1]*x+m[5]*y+m[9]*z+m[13]*w,ez=m[2]*x+m[6]*y+m[10]*z+m[14]*w,ew=m[3]*x+m[7]*y+m[11]*z+m[15]*w;
		double[] p=proj;
		v[XC]=(float)(p[0]*ex+p[4]*ey+p[8]*ez+p[12]*ew);v[YC]=(float)(p[1]*ex+p[5]*ey+p[9]*ez+p[13]*ew);
		v[ZC]=(float)(p[2]*ex+p[6]*ey+p[10]*ez+p[14]*ew);v[WC]=(float)(p[3]*ex+p[7]*ey+p[11]*ez+p[15]*ew);
		v[EX]=(float)ex;v[EY]=(float)ey;v[EZ]=(float)ez;v[EW]=(float)ew;
		State t=s;
		boolean needNormal=t.lighting;
		for(int u=0;u<UNITS;u++){Unit un=t.units[u];if(un.enabled2D||un.enabled1D)for(int c=0;c<2;c++)if(un.gen[c]&&un.genMode[c]==SPHERE_MAP)needNormal=true;}
		double nx=0,ny=0,nz=1;
		if(needNormal){
			double[] nm=normalMatrix();float[] n=t.normal;
			nx=nm[0]*n[0]+nm[1]*n[1]+nm[2]*n[2];ny=nm[3]*n[0]+nm[4]*n[1]+nm[5]*n[2];nz=nm[6]*n[0]+nm[7]*n[1]+nm[8]*n[2];
			if(t.normalize||t.rescaleNormal){double l=Math.sqrt(nx*nx+ny*ny+nz*nz);if(l>0){nx/=l;ny/=l;nz/=l;}}
		}
		if(t.lighting){
			double ix=ex,iy=ey,iz=ez;if(ew!=0&&ew!=1){ix/=ew;iy/=ew;iz/=ew;}
			light(v,R,t.front,ix,iy,iz,nx,ny,nz);
			if(t.lightModelTwoSide)light(v,BR,t.back,ix,iy,iz,-nx,-ny,-nz);
			else{v[BR]=v[R];v[BG]=v[G];v[BB]=v[B];v[BA]=v[A];}
		}else{
			float[] c=t.color;
			v[R]=v[BR]=clamp01(c[0]);v[G]=v[BG]=clamp01(c[1]);v[B]=v[BB]=clamp01(c[2]);v[A]=v[BA]=clamp01(c[3]);
		}
		for(int u=0;u<UNITS;u++){
			Unit un=t.units[u];if(!un.enabled2D&&!un.enabled1D)continue;
			float[] tc=t.texCoord[u];double sc=tc[0],tt=tc[1],rr=tc[2],qq=tc[3];
			if(un.gen[0]||un.gen[1]||un.gen[2]||un.gen[3]){
				double[] g=new double[]{sc,tt,rr,qq};
				double sx=0,sy=0;boolean sphere=false;
				for(int c=0;c<4;c++){
					if(!un.gen[c])continue;
					switch(un.genMode[c]){
						case OBJECT_LINEAR:{float[] pl=un.objectPlane[c];g[c]=pl[0]*x+pl[1]*y+pl[2]*z+pl[3]*w;break;}
						case EYE_LINEAR:{float[] pl=un.eyePlane[c];g[c]=pl[0]*ex+pl[1]*ey+pl[2]*ez+pl[3]*ew;break;}
						case SPHERE_MAP:{
							if(!sphere){double ux=ex,uy=ey,uz=ez,l=Math.sqrt(ux*ux+uy*uy+uz*uz);if(l>0){ux/=l;uy/=l;uz/=l;}
								double d=nx*ux+ny*uy+nz*uz,rx=ux-2*nx*d,ry=uy-2*ny*d,rz=uz-2*nz*d;
								double mm=2*Math.sqrt(rx*rx+ry*ry+(rz+1)*(rz+1));if(mm==0)mm=1;
								sx=rx/mm+0.5;sy=ry/mm+0.5;sphere=true;}
							g[c]=c==0?sx:sy;break;}
						default:break;
					}
				}
				sc=g[0];tt=g[1];rr=g[2];qq=g[3];
			}
			if(!texIdentity[u]){double[] tm=tex[u];
				double s2=tm[0]*sc+tm[4]*tt+tm[8]*rr+tm[12]*qq,t2=tm[1]*sc+tm[5]*tt+tm[9]*rr+tm[13]*qq,q2=tm[3]*sc+tm[7]*tt+tm[11]*rr+tm[15]*qq;
				sc=s2;tt=t2;qq=q2;}
			int o=u==0?S0:S1;v[o]=(float)sc;v[o+1]=(float)tt;v[o+2]=(float)qq;
		}
		if(t.fog){
			double d=Math.abs(ez/(ew==0?1:ew));float f;
			switch(t.fogMode){
				case LINEAR:f=t.fogEnd==t.fogStart?1:(float)((t.fogEnd-d)/(t.fogEnd-t.fogStart));break;
				case EXP2:{double e=t.fogDensity*d;f=(float)Math.exp(-e*e);break;}
				default:f=(float)Math.exp(-t.fogDensity*d);
			}
			v[FOGF]=clamp01(f);
		}else v[FOGF]=1;
		return v;
	}
	static float clamp01(float f){return f<0?0:f>1?1:f;}
	private static float clamp01d(double f){return f<0?0:f>1?1:(float)f;}

	/** Per-vertex lighting (OpenGL 1.2 2.13.1) into v[o..o+3]. */
	private void light(float[] v,int o,Material mat,double px,double py,double pz,double nx,double ny,double nz){
		State t=s;
		double r=mat.emission[0]+mat.ambient[0]*t.lightModelAmbient[0],g=mat.emission[1]+mat.ambient[1]*t.lightModelAmbient[1],b=mat.emission[2]+mat.ambient[2]*t.lightModelAmbient[2];
		for(int i=0;i<LIGHTS;i++){
			if(!t.lightEnabled[i])continue;
			Light l=t.lights[i];
			double lx,ly,lz,att=1;
			if(l.position[3]==0){lx=l.position[0];ly=l.position[1];lz=l.position[2];double len=Math.sqrt(lx*lx+ly*ly+lz*lz);if(len>0){lx/=len;ly/=len;lz/=len;}}
			else{
				double qx=l.position[0]/l.position[3],qy=l.position[1]/l.position[3],qz=l.position[2]/l.position[3];
				lx=qx-px;ly=qy-py;lz=qz-pz;double d=Math.sqrt(lx*lx+ly*ly+lz*lz);if(d>0){lx/=d;ly/=d;lz/=d;}
				double k=l.constant+l.linear*d+l.quadratic*d*d;att=k>0?1/k:1;
				if(l.spotCutoff!=180){
					double sx=l.spotDirection[0],sy=l.spotDirection[1],sz=l.spotDirection[2],sl=Math.sqrt(sx*sx+sy*sy+sz*sz);if(sl>0){sx/=sl;sy/=sl;sz/=sl;}
					double cos=-(lx*sx+ly*sy+lz*sz);
					if(cos<Math.cos(Math.toRadians(l.spotCutoff)))att=0;else att*=l.spotExponent==0?1:Math.pow(Math.max(0,cos),l.spotExponent);
				}
			}
			if(att==0)continue;
			double ar=mat.ambient[0]*l.ambient[0],ag=mat.ambient[1]*l.ambient[1],ab=mat.ambient[2]*l.ambient[2];
			double ndl=nx*lx+ny*ly+nz*lz;
			double dr=0,dg=0,db=0,sr=0,sg=0,sb=0;
			if(ndl>0){
				dr=ndl*mat.diffuse[0]*l.diffuse[0];dg=ndl*mat.diffuse[1]*l.diffuse[1];db=ndl*mat.diffuse[2]*l.diffuse[2];
				double hx=lx,hy=ly,hz=lz;
				if(t.lightModelLocalViewer){double vx=-px,vy=-py,vz=-pz,vl=Math.sqrt(vx*vx+vy*vy+vz*vz);if(vl>0){hx+=vx/vl;hy+=vy/vl;hz+=vz/vl;}}else hz+=1;
				double hl=Math.sqrt(hx*hx+hy*hy+hz*hz);
				if(hl>0){double ndh=(nx*hx+ny*hy+nz*hz)/hl;if(ndh>0){double sp=mat.shininess==0?1:Math.pow(ndh,mat.shininess);
					sr=sp*mat.specular[0]*l.specular[0];sg=sp*mat.specular[1]*l.specular[1];sb=sp*mat.specular[2]*l.specular[2];}}
			}
			r+=att*(ar+dr+sr);g+=att*(ag+dg+sg);b+=att*(ab+db+sb);
		}
		v[o]=clamp01d(r);v[o+1]=clamp01d(g);v[o+2]=clamp01d(b);v[o+3]=clamp01(mat.diffuse[3]);
	}

	// ================================================================== primitive assembly and clipping

	private void assemble(int mode,ArrayList<float[]> vs){
		int n=vs.size();
		switch(mode){
			case POINTS:for(float[] v:vs)point(v);break;
			case LINES:for(int i=0;i+1<n;i+=2)line(vs.get(i),vs.get(i+1),vs.get(i+1));break;
			case LINE_STRIP:for(int i=0;i+1<n;i++)line(vs.get(i),vs.get(i+1),vs.get(i+1));break;
			case LINE_LOOP:for(int i=0;i+1<n;i++)line(vs.get(i),vs.get(i+1),vs.get(i+1));if(n>2)line(vs.get(n-1),vs.get(0),vs.get(0));break;
			case TRIANGLES:for(int i=0;i+2<n;i+=3)polygon(new float[][]{vs.get(i),vs.get(i+1),vs.get(i+2)},vs.get(i+2));break;
			case TRIANGLE_STRIP:for(int i=2;i<n;i++){if((i&1)==0)polygon(new float[][]{vs.get(i-2),vs.get(i-1),vs.get(i)},vs.get(i));else polygon(new float[][]{vs.get(i-1),vs.get(i-2),vs.get(i)},vs.get(i));}break;
			case TRIANGLE_FAN:for(int i=2;i<n;i++)polygon(new float[][]{vs.get(0),vs.get(i-1),vs.get(i)},vs.get(i));break;
			case QUADS:for(int i=0;i+3<n;i+=4)polygon(new float[][]{vs.get(i),vs.get(i+1),vs.get(i+2),vs.get(i+3)},vs.get(i+3));break;
			case QUAD_STRIP:for(int i=0;i+3<n;i+=2)polygon(new float[][]{vs.get(i),vs.get(i+1),vs.get(i+3),vs.get(i+2)},vs.get(i+3));break;
			case POLYGON:if(n>=3)polygon(vs.toArray(new float[0][]),vs.get(0));break;
			default:break;
		}
	}

	/** Signed distance of a vertex to clip plane k (0-5 the frustum, 6.. the user planes); inside when >= 0. */
	private float clipDistance(float[] v,int k){
		switch(k){
			case 0:return v[WC]+v[XC];case 1:return v[WC]-v[XC];case 2:return v[WC]+v[YC];case 3:return v[WC]-v[YC];case 4:return v[WC]+v[ZC];case 5:return v[WC]-v[ZC];
			default:{float[] p=s.clipPlanes[k-6];return p[0]*v[EX]+p[1]*v[EY]+p[2]*v[EZ]+p[3]*v[EW];}
		}
	}
	private static float[] lerp(float[] a,float[] b,float t){float[] r=new float[V];for(int i=0;i<V;i++)r[i]=a[i]+(b[i]-a[i])*t;return r;}
	/** Sutherland-Hodgman against every active plane; null when nothing is left. */
	private float[][] clip(float[][] poly){
		int planes=6+CLIP_PLANES;
		for(int k=0;k<planes;k++){
			if(k>=6&&!s.clipEnabled[k-6])continue;
			int n=poly.length;boolean allIn=true,allOut=true;
			float[] d=new float[n];
			for(int i=0;i<n;i++){d[i]=clipDistance(poly[i],k);if(d[i]<0)allIn=false;else allOut=false;}
			if(allIn)continue;if(allOut)return null;
			ArrayList<float[]> out=new ArrayList<float[]>(n+2);
			for(int i=0;i<n;i++){
				int j=(i+1)%n;float[] a=poly[i],b=poly[j];float da=d[i],db=d[j];
				if(da>=0)out.add(a);
				if((da>=0)!=(db>=0))out.add(lerp(a,b,da/(da-db)));
			}
			if(out.size()<3)return null;
			poly=out.toArray(new float[0][]);
		}
		return poly;
	}
	/** Clip coordinates to window coordinates (and 1/w) of a vertex that passed clipping. */
	private void toWindow(float[] v){
		float w=v[WC];if(w==0)w=1e-20f;float iw=1/w;
		State t=s;
		v[WX]=t.vpX+(v[XC]*iw+1)*t.vpW*0.5f;
		v[WY]=t.vpY+(v[YC]*iw+1)*t.vpH*0.5f;
		v[WZ]=t.depthNear+(v[ZC]*iw+1)*0.5f*(t.depthFar-t.depthNear);
		v[IW]=iw;
	}

	private void polygon(float[][] poly,float[] provoking){
		float[][] c=clip(poly);if(c==null)return;
		// toWindow writes into the records; clipped-away vertices may be shared with other primitives, so copy the shared ones
		for(int i=0;i<c.length;i++){c[i]=c[i].clone();toWindow(c[i]);}
		double area=0;for(int i=0;i<c.length;i++){float[] a=c[i],b=c[(i+1)%c.length];area+=a[WX]*b[WY]-b[WX]*a[WY];}
		if(area==0)return;
		boolean front=(area>0)==(s.frontFace==CCW);
		if(s.cullFace&&(s.cullMode==FRONT_AND_BACK||(s.cullMode==FRONT)==front))return;
		int ci=front?R:BR;
		if(s.shadeModel==FLAT){for(float[] v:c){v[ci]=provoking[ci];v[ci+1]=provoking[ci+1];v[ci+2]=provoking[ci+2];v[ci+3]=provoking[ci+3];}}
		int mode=front?s.polygonModeFront:s.polygonModeBack;
		if(mode==LINE){
			lineOffset=s.polygonOffsetLine?polygonOffset(c):0;
			for(int i=0;i<c.length;i++){float[] a=c[i],b=c[(i+1)%c.length];lineWindow(a,b,ci);}
			lineOffset=0;return;
		}
		if(mode==POINT){for(float[] v:c)pointWindow(v,ci);return;}
		float offset=s.polygonOffsetFill?polygonOffset(c):0;
		for(int i=1;i+1<c.length;i++)triangle(c[0],c[i],c[i+1],ci,offset);
	}
	/** The polygon offset of a window-space polygon: factor * max depth slope + units * the smallest depth step. */
	private float polygonOffset(float[][] c){
		if(c.length<3)return s.polygonOffsetUnits/(1<<24);
		float[] a=c[0],b=c[1],d=c[2];
		float x1=b[WX]-a[WX],y1=b[WY]-a[WY],z1=b[WZ]-a[WZ],x2=d[WX]-a[WX],y2=d[WY]-a[WY],z2=d[WZ]-a[WZ];
		float den=x1*y2-x2*y1;float m=0;
		if(den!=0){float dzdx=(z1*y2-z2*y1)/den,dzdy=(z2*x1-z1*x2)/den;m=Math.max(Math.abs(dzdx),Math.abs(dzdy));}
		return s.polygonOffsetFactor*m+s.polygonOffsetUnits/(float)(1<<24);
	}

	private float lineOffset;
	private void line(float[] a,float[] b,float[] provoking){
		// parametric clipping (Liang-Barsky) against the active planes
		float t0=0,t1=1;
		for(int k=0;k<6+CLIP_PLANES;k++){
			if(k>=6&&!s.clipEnabled[k-6])continue;
			float da=clipDistance(a,k),db=clipDistance(b,k);
			if(da<0&&db<0)return;
			if(da<0)t0=Math.max(t0,da/(da-db));else if(db<0)t1=Math.min(t1,da/(da-db));
		}
		if(t0>t1)return;
		float[] p=t0>0?lerp(a,b,t0):a.clone(),q=t1<1?lerp(a,b,t1):b.clone();
		toWindow(p);toWindow(q);
		if(s.shadeModel==FLAT){for(int k=0;k<4;k++){p[R+k]=provoking[R+k];q[R+k]=provoking[R+k];}}
		lineWindow(p,q,R);
	}
	private void point(float[] v){
		for(int k=0;k<6;k++)if(clipDistance(v,k)<0)return;
		for(int k=0;k<CLIP_PLANES;k++)if(s.clipEnabled[k]&&clipDistance(v,6+k)<0)return;
		float[] w=v.clone();toWindow(w);pointWindow(w,R);
	}

	// ================================================================== rasterization

	// per-primitive fragment state
	private Texture[] fragTex=new Texture[UNITS];private int texUnits;
	private boolean anyTexture;
	private float fragFogR,fragFogG,fragFogB;
	private boolean useFog;
	/** Gathers what fragments of this primitive need (textures in use per unit). */
	private void preparePrimitive(){
		State t=s;texUnits=0;anyTexture=false;
		for(int u=0;u<UNITS;u++){
			Unit un=t.units[u];Texture tx=null;
			if(un.enabled2D){tx=texture(un.binding2D,u);}else if(un.enabled1D){tx=texture(un.binding1D,u);}
			if(tx!=null&&!tx.usable())tx=null;
			fragTex[u]=tx;if(tx!=null){anyTexture=true;texUnits=u+1;}
		}
		useFog=t.fog;fragFogR=t.fogColor[0];fragFogG=t.fogColor[1];fragFogB=t.fogColor[2];
	}

	// triangle setup: plane equations (value at the first vertex + gradients) of the interpolated quantities
	private static final int P_Z=0,P_IW=1,P_R=2,P_G=3,P_B=4,P_A=5,P_F=6,P_S0=7,P_T0=8,P_Q0=9,P_S1=10,P_T1=11,P_Q1=12,PN=13;
	private final float[] pa=new float[PN],pdx=new float[PN],pdy=new float[PN];
	private float px0,py0;

	private void triangle(float[] a,float[] b,float[] c,int ci,float zOffset){
		preparePrimitive();
		float x0=a[WX],y0=a[WY],x1=b[WX],y1=b[WY],x2=c[WX],y2=c[WY];
		float area=(x1-x0)*(y2-y0)-(x2-x0)*(y1-y0);
		if(area==0||Float.isNaN(area))return;
		float inv=1/area;px0=x0;py0=y0;
		float[] va=attrs(a,ci),vb=attrs(b,ci),vc=attrs(c,ci);
		int n=anyTexture?PN:P_S0;
		for(int k=0;k<n;k++){
			float v0=va[k],d1=vb[k]-v0,d2=vc[k]-v0;
			pa[k]=v0;pdx[k]=(d1*(y2-y0)-d2*(y1-y0))*inv;pdy[k]=(d2*(x1-x0)-d1*(x2-x0))*inv;
		}
		pa[P_Z]+=zOffset;
		// rows whose centre line y + 0.5 crosses the triangle, then the pixels whose centres are inside
		float[] p=a,q=b,r=c;
		if(q[WY]<p[WY]){float[] t=p;p=q;q=t;}
		if(r[WY]<q[WY]){float[] t=q;q=r;r=t;}
		if(q[WY]<p[WY]){float[] t=p;p=q;q=t;}
		float xa=p[WX],ya=p[WY],xb=q[WX],yb=q[WY],xc=r[WX],yc=r[WY];
		if(!(yc>ya))return;
		int[] box=scissorBox();
		int yStart=Math.max(box[1],(int)Math.ceil(ya-0.5f)),yEnd=Math.min(box[3],(int)Math.ceil(yc-0.5f));
		float longSlope=(xc-xa)/(yc-ya),upper=yb>ya?(xb-xa)/(yb-ya):0,lower=yc>yb?(xc-xb)/(yc-yb):0;
		for(int y=yStart;y<yEnd;y++){
			float cy=y+0.5f;
			float xl=xa+(cy-ya)*longSlope;
			float xs=cy<yb?xa+(cy-ya)*upper:xb+(cy-yb)*lower;
			if(xs<xl){float t=xs;xs=xl;xl=t;}
			int sx0=Math.max(box[0],(int)Math.ceil(xl-0.5f)),sx1=Math.min(box[2],(int)Math.ceil(xs-0.5f));
			if(sx0<sx1)span(y,sx0,sx1);
		}
	}
	/** The interpolated quantities of a window vertex, divided by w where perspective-correct. */
	private float[] attrs(float[] v,int ci){
		float iw=v[IW];
		float[] r=new float[PN];
		r[P_Z]=v[WZ];r[P_IW]=iw;r[P_R]=v[ci]*iw;r[P_G]=v[ci+1]*iw;r[P_B]=v[ci+2]*iw;r[P_A]=v[ci+3]*iw;r[P_F]=v[FOGF]*iw;
		r[P_S0]=v[S0]*iw;r[P_T0]=v[T0]*iw;r[P_Q0]=v[Q0]*iw;r[P_S1]=v[S1]*iw;r[P_T1]=v[T1]*iw;r[P_Q1]=v[Q1]*iw;
		return r;
	}
	/** Left, bottom, right, top (exclusive) of the pixels that may be written. */
	private final int[] boxBuf=new int[4];
	private int[] scissorBox(){
		int[] b=boxBuf;b[0]=0;b[1]=0;b[2]=width;b[3]=height;
		if(s.scissorTest){b[0]=Math.max(0,s.scX);b[1]=Math.max(0,s.scY);b[2]=Math.min(width,s.scX+s.scW);b[3]=Math.min(height,s.scY+s.scH);}
		return b;
	}

	private void span(int y,int x0,int x1){
		float fx=x0+0.5f-px0,fy=y+0.5f-py0;
		float[] a=pa,dx=pdx,dy=pdy;
		float z=a[P_Z]+dx[P_Z]*fx+dy[P_Z]*fy,iw=a[P_IW]+dx[P_IW]*fx+dy[P_IW]*fy;
		float r=a[P_R]+dx[P_R]*fx+dy[P_R]*fy,g=a[P_G]+dx[P_G]*fx+dy[P_G]*fy,b=a[P_B]+dx[P_B]*fx+dy[P_B]*fy,al=a[P_A]+dx[P_A]*fx+dy[P_A]*fy,f=a[P_F]+dx[P_F]*fx+dy[P_F]*fy;
		float s0=0,t0=0,q0=0,s1=0,t1=0,q1=0;
		if(anyTexture){s0=a[P_S0]+dx[P_S0]*fx+dy[P_S0]*fy;t0=a[P_T0]+dx[P_T0]*fx+dy[P_T0]*fy;q0=a[P_Q0]+dx[P_Q0]*fx+dy[P_Q0]*fy;
			s1=a[P_S1]+dx[P_S1]*fx+dy[P_S1]*fy;t1=a[P_T1]+dx[P_T1]*fx+dy[P_T1]*fy;q1=a[P_Q1]+dx[P_Q1]*fx+dy[P_Q1]*fy;}
		int row=(height-1-y)*width;
		for(int x=x0;x<x1;x++){
			float w=1/iw;
			if(anyTexture){
				Texture t0x=fragTex[0],t1x=texUnits>1?fragTex[1]:null;
				if(t0x!=null){float iq=1/q0,ss=s0*iq,tt=t0*iq;texS[0]=ss;texT[0]=tt;
					texLod[0]=needLod(t0x)?lod(t0x,ss,tt,iq,dx[P_S0],dy[P_S0],dx[P_T0],dy[P_T0],dx[P_Q0],dy[P_Q0]):0;}
				if(t1x!=null){float iq=1/q1,ss=s1*iq,tt=t1*iq;texS[1]=ss;texT[1]=tt;
					texLod[1]=needLod(t1x)?lod(t1x,ss,tt,iq,dx[P_S1],dy[P_S1],dx[P_T1],dy[P_T1],dx[P_Q1],dy[P_Q1]):0;}
			}
			fragment(row+x,z,r*w,g*w,b*w,al*w,f*w);
			z+=dx[P_Z];iw+=dx[P_IW];r+=dx[P_R];g+=dx[P_G];b+=dx[P_B];al+=dx[P_A];f+=dx[P_F];
			if(anyTexture){s0+=dx[P_S0];t0+=dx[P_T0];q0+=dx[P_Q0];s1+=dx[P_S1];t1+=dx[P_T1];q1+=dx[P_Q1];}
		}
	}
	private static boolean needLod(Texture t){return t.mipmapped()||(t.minFilter!=t.magFilter);}
	/** log2 of the texel footprint of a pixel (lambda, OpenGL 1.2 3.8.5) from the screen derivatives of s/q, t/q. */
	private static float lod(Texture tx,float s,float t,float iq,float dSx,float dSy,float dTx,float dTy,float dQx,float dQy){
		float dsdx=(dSx-s*dQx)*iq,dsdy=(dSy-s*dQy)*iq,dtdx=(dTx-t*dQx)*iq,dtdy=(dTy-t*dQy)*iq;
		float w=tx.widths[0],h=tx.heights[0];
		float ux=dsdx*w,vx=dtdx*h,uy=dsdy*w,vy=dtdy*h;
		float rho2=Math.max(ux*ux+vx*vx,uy*uy+vy*vy);
		if(!(rho2>0))return -16;
		return 0.5f*log2(rho2);
	}
	/** A fast log2 (exponent plus a linear mantissa term). */
	private static float log2(float v){int bits=Float.floatToRawIntBits(v);int e=((bits>>>23)&255)-127;float m=Float.intBitsToFloat((bits&0x7fffff)|0x3f800000);return e+(m-1);}

	private final float[] texS=new float[UNITS],texT=new float[UNITS],texLod=new float[UNITS];

	// ---- points and lines (attributes interpolated linearly in window space)
	private void pointWindow(float[] v,int ci){
		preparePrimitive();
		float size=Math.max(1,s.pointSize);
		int[] box=scissorBox();
		int x0,y0,x1,y1;
		if(size<=1){x0=(int)Math.floor(v[WX]);y0=(int)Math.floor(v[WY]);x1=x0+1;y1=y0+1;}
		else{float h=size/2;x0=(int)Math.ceil(v[WX]-h-0.5f);x1=(int)Math.ceil(v[WX]+h-0.5f);y0=(int)Math.ceil(v[WY]-h-0.5f);y1=(int)Math.ceil(v[WY]+h-0.5f);}
		x0=Math.max(x0,box[0]);y0=Math.max(y0,box[1]);x1=Math.min(x1,box[2]);y1=Math.min(y1,box[3]);
		float z=v[WZ]+(s.polygonOffsetPoint?s.polygonOffsetUnits/(float)(1<<24):0);
		setTexFromVertex(v);
		for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)fragment((height-1-y)*width+x,z,v[ci],v[ci+1],v[ci+2],v[ci+3],v[FOGF]);
	}
	private void setTexFromVertex(float[] v){
		for(int u=0;u<texUnits;u++){int o=u==0?S0:S1;float q=v[o+2]==0?1:v[o+2];texS[u]=v[o]/q;texT[u]=v[o+1]/q;texLod[u]=0;}
	}
	private void lineWindow(float[] a,float[] b,int ci){
		preparePrimitive();
		float dxw=b[WX]-a[WX],dyw=b[WY]-a[WY];
		boolean xMajor=Math.abs(dxw)>=Math.abs(dyw);
		float len=Math.max(Math.abs(dxw),Math.abs(dyw));
		int steps=Math.max(1,(int)Math.ceil(len));
		int width2=Math.max(1,Math.round(s.lineWidth));
		int[] box=scissorBox();
		for(int i=0;i<steps;i++){
			float t=(i+0.5f)/steps;
			float x=a[WX]+dxw*t,y=a[WY]+dyw*t;
			float z=a[WZ]+(b[WZ]-a[WZ])*t+lineOffset;
			float r=a[ci]+(b[ci]-a[ci])*t,g=a[ci+1]+(b[ci+1]-a[ci+1])*t,bl=a[ci+2]+(b[ci+2]-a[ci+2])*t,al=a[ci+3]+(b[ci+3]-a[ci+3])*t,f=a[FOGF]+(b[FOGF]-a[FOGF])*t;
			for(int u=0;u<texUnits;u++){int o=u==0?S0:S1;
				float qa=a[o+2]==0?1:a[o+2],qb=b[o+2]==0?1:b[o+2];
				texS[u]=a[o]/qa+(b[o]/qb-a[o]/qa)*t;texT[u]=a[o+1]/qa+(b[o+1]/qb-a[o+1]/qa)*t;texLod[u]=0;}
			int px=(int)Math.floor(x),py=(int)Math.floor(y);
			for(int k=0;k<width2;k++){
				int off=k-(width2-1)/2;
				int qx=xMajor?px:px+off,qy=xMajor?py+off:py;
				if(qx<box[0]||qx>=box[2]||qy<box[1]||qy>=box[3])continue;
				fragment((height-1-qy)*width+qx,z,r,g,bl,al,f);
			}
		}
	}

	// ---- the per-fragment operations
	private void fragment(int i,float z,float r,float g,float b,float a,float fog){
		State t=s;
		if(anyTexture){
			for(int u=0;u<texUnits;u++){
				Texture tx=fragTex[u];if(tx==null)continue;
				int texel=sample(tx,texS[u],texT[u],texLod[u]);
				float tr=((texel>>>16)&255)*INV255,tg=((texel>>>8)&255)*INV255,tb=(texel&255)*INV255,ta=(texel>>>24)*INV255;
				Unit un=t.units[u];
				switch(un.envMode){
					case REPLACE:
						switch(tx.format){case F_ALPHA:a=ta;break;case F_LUMINANCE:case F_RGB:r=tr;g=tg;b=tb;break;default:r=tr;g=tg;b=tb;a=ta;}
						break;
					case DECAL:
						if(tx.format==F_RGB){r=tr;g=tg;b=tb;}else if(tx.format==F_RGBA){r+=(tr-r)*ta;g+=(tg-g)*ta;b+=(tb-b)*ta;}
						break;
					case BLEND_ENV:{
						float[] cc=un.envColor;
						if(tx.format!=F_ALPHA){r=r*(1-tr)+cc[0]*tr;g=g*(1-tg)+cc[1]*tg;b=b*(1-tb)+cc[2]*tb;}
						if(tx.format==F_INTENSITY)a=a*(1-ta)+cc[3]*ta;else if(tx.format==F_ALPHA||tx.format==F_LUMINANCE_ALPHA||tx.format==F_RGBA)a*=ta;
						break;}
					case ADD:
						if(tx.format!=F_ALPHA){r=Math.min(1,r+tr);g=Math.min(1,g+tg);b=Math.min(1,b+tb);}
						if(tx.format==F_INTENSITY)a=Math.min(1,a+ta);else if(tx.format==F_ALPHA||tx.format==F_LUMINANCE_ALPHA||tx.format==F_RGBA)a*=ta;
						break;
					default: // MODULATE
						if(tx.format!=F_ALPHA){r*=tr;g*=tg;b*=tb;}
						if(tx.format!=F_RGB&&tx.format!=F_LUMINANCE)a*=ta;
				}
			}
		}
		if(useFog){r=fog*r+(1-fog)*fragFogR;g=fog*g+(1-fog)*fragFogG;b=fog*b+(1-fog)*fragFogB;}
		if(t.alphaTest&&!compare(t.alphaFunc,a,t.alphaRef))return;
		if(t.stencilTest){
			int sv=stencil[i]&255,ref=t.stencilRef&255,mask=t.stencilValueMask&255;
			if(!compare(t.stencilFunc,ref&mask,sv&mask)){stencilOp(i,t.stencilFail);return;}
			if(t.depthTest&&!compare(t.depthFunc,z,depth[i])){stencilOp(i,t.stencilZFail);return;}
			stencilOp(i,t.stencilZPass);
		}else if(t.depthTest&&!compare(t.depthFunc,z,depth[i]))return;
		if(t.depthTest&&t.depthMask)depth[i]=z;
		int dst=color[i];
		if(t.blend){
			float dr=((dst>>>16)&255)*INV255,dg=((dst>>>8)&255)*INV255,db=(dst&255)*INV255,da=(dst>>>24)*INV255;
			float sr=factor(t.blendSrc,0,r,g,b,a,dr,dg,db,da),sg=factor(t.blendSrc,1,r,g,b,a,dr,dg,db,da),sb=factor(t.blendSrc,2,r,g,b,a,dr,dg,db,da),sa=factor(t.blendSrc,3,r,g,b,a,dr,dg,db,da);
			float er=factor(t.blendDst,0,r,g,b,a,dr,dg,db,da),eg=factor(t.blendDst,1,r,g,b,a,dr,dg,db,da),eb=factor(t.blendDst,2,r,g,b,a,dr,dg,db,da),ea=factor(t.blendDst,3,r,g,b,a,dr,dg,db,da);
			switch(t.blendEquation){
				case FUNC_SUBTRACT:r=r*sr-dr*er;g=g*sg-dg*eg;b=b*sb-db*eb;a=a*sa-da*ea;break;
				case FUNC_REVERSE_SUBTRACT:r=dr*er-r*sr;g=dg*eg-g*sg;b=db*eb-b*sb;a=da*ea-a*sa;break;
				case MIN:r=Math.min(r,dr);g=Math.min(g,dg);b=Math.min(b,db);a=Math.min(a,da);break;
				case MAX:r=Math.max(r,dr);g=Math.max(g,dg);b=Math.max(b,db);a=Math.max(a,da);break;
				default:r=r*sr+dr*er;g=g*sg+dg*eg;b=b*sb+db*eb;a=a*sa+da*ea;
			}
		}
		int out=to255(a)<<24|to255(r)<<16|to255(g)<<8|to255(b);
		boolean[] m=t.colorMask;
		if(!(m[0]&&m[1]&&m[2]&&m[3])){
			int keep=(m[3]?0:0xff000000)|(m[0]?0:0xff0000)|(m[1]?0:0xff00)|(m[2]?0:0xff);
			out=(out&~keep)|(dst&keep);
		}
		color[i]=out;
	}
	static final float INV255=1f/255;
	private static int to255(float v){return v<=0?0:v>=1?255:(int)(v*255+0.5f);}
	private static boolean compare(int func,float a,float b){
		switch(func){case NEVER:return false;case LESS:return a<b;case EQUAL:return a==b;case LEQUAL:return a<=b;case GREATER:return a>b;case NOTEQUAL:return a!=b;case GEQUAL:return a>=b;default:return true;}
	}
	private void stencilOp(int i,int op){
		int v=stencil[i]&255,nv;
		switch(op){
			case KEEP:return;case ZERO:nv=0;break;case REPLACE:nv=s.stencilRef&255;break;case INCR:nv=Math.min(255,v+1);break;case DECR:nv=Math.max(0,v-1);break;
			case INVERT:nv=~v&255;break;case INCR_WRAP:nv=(v+1)&255;break;case DECR_WRAP:nv=(v-1)&255;break;default:return;
		}
		int wm=s.stencilWriteMask&255;stencil[i]=(byte)((v&~wm)|(nv&wm));
	}
	private float factor(int f,int ch,float r,float g,float b,float a,float dr,float dg,float db,float da){
		float[] cc=s.blendColor;
		switch(f){
			case ZERO:return 0;case ONE:return 1;
			case SRC_COLOR:return ch==0?r:ch==1?g:ch==2?b:a;case ONE_MINUS_SRC_COLOR:return 1-(ch==0?r:ch==1?g:ch==2?b:a);
			case DST_COLOR:return ch==0?dr:ch==1?dg:ch==2?db:da;case ONE_MINUS_DST_COLOR:return 1-(ch==0?dr:ch==1?dg:ch==2?db:da);
			case SRC_ALPHA:return a;case ONE_MINUS_SRC_ALPHA:return 1-a;case DST_ALPHA:return da;case ONE_MINUS_DST_ALPHA:return 1-da;
			case SRC_ALPHA_SATURATE:return ch==3?1:Math.min(a,1-da);
			case CONSTANT_COLOR:return cc[ch];case ONE_MINUS_CONSTANT_COLOR:return 1-cc[ch];case CONSTANT_ALPHA:return cc[3];case ONE_MINUS_CONSTANT_ALPHA:return 1-cc[3];
			default:return ch==3?1:0;
		}
	}

	// ---- texture sampling
	private int sample(Texture t,float s,float tc,float lambda){
		boolean minify=lambda>0;
		int filter=minify?t.minFilter:t.magFilter;
		if(!minify||filter==NEAREST||filter==LINEAR)return filter==NEAREST?nearest(t,0,s,tc):bilinear(t,0,s,tc);
		int max=t.maxLevel;
		switch(filter){
			case NEAREST_MIPMAP_NEAREST:case LINEAR_MIPMAP_NEAREST:{
				int level=Math.min(max,Math.max(0,(int)Math.ceil(lambda+0.5f)-1));
				return filter==NEAREST_MIPMAP_NEAREST?nearest(t,level,s,tc):bilinear(t,level,s,tc);}
			default:{ // the two nearest levels, weighted
				int l0=Math.min(max,(int)Math.floor(lambda)),l1=Math.min(max,l0+1);float f=Math.min(1,lambda-l0);
				int c0=filter==NEAREST_MIPMAP_LINEAR?nearest(t,l0,s,tc):bilinear(t,l0,s,tc);
				if(l1==l0)return c0;
				int c1=filter==NEAREST_MIPMAP_LINEAR?nearest(t,l1,s,tc):bilinear(t,l1,s,tc);
				return mix(c0,c1,f);}
		}
	}
	private static int wrap(int i,int n,int mode){
		if(mode==REPEAT){i%=n;return i<0?i+n:i;}
		return i<0?0:i>=n?n-1:i;
	}
	private static int nearest(Texture t,int level,float s,float tc){
		int w=t.widths[level],h=t.heights[level];
		int i=wrap((int)Math.floor(s*w),w,t.wrapS),j=wrap((int)Math.floor(tc*h),h,t.wrapT);
		return t.levels[level][j*w+i];
	}
	private static int bilinear(Texture t,int level,float s,float tc){
		int w=t.widths[level],h=t.heights[level];int[] d=t.levels[level];
		float u=s*w-0.5f,v=tc*h-0.5f;
		int i0=(int)Math.floor(u),j0=(int)Math.floor(v);float fu=u-i0,fv=v-j0;
		int i1=wrap(i0+1,w,t.wrapS),j1=wrap(j0+1,h,t.wrapT);i0=wrap(i0,w,t.wrapS);j0=wrap(j0,h,t.wrapT);
		int c00=d[j0*w+i0],c10=d[j0*w+i1],c01=d[j1*w+i0],c11=d[j1*w+i1];
		if(c00==c10&&c00==c01&&c00==c11)return c00;
		return mix(mix(c00,c10,fu),mix(c01,c11,fu),fv);
	}
	private static int mix(int a,int b,float f){
		int k=(int)(f*256),r=0;
		for(int sft=0;sft<32;sft+=8){int ca=(a>>>sft)&255,cb=(b>>>sft)&255;r|=(ca+(((cb-ca)*k)>>8))<<sft;}
		return r;
	}

	// ================================================================== whole-buffer operations

	void clear(int mask){
		int[] box=scissorBox();
		State t=s;
		if((mask&COLOR_BUFFER_BIT)!=0){
			int c=to255(t.clearColor[3])<<24|to255(t.clearColor[0])<<16|to255(t.clearColor[1])<<8|to255(t.clearColor[2]);
			boolean[] m=t.colorMask;int keep=(m[3]?0:0xff000000)|(m[0]?0:0xff0000)|(m[1]?0:0xff00)|(m[2]?0:0xff);
			for(int y=box[1];y<box[3];y++){int row=(height-1-y)*width;
				if(keep==0)Arrays.fill(color,row+box[0],row+box[2],c);
				else for(int x=box[0];x<box[2];x++)color[row+x]=(c&~keep)|(color[row+x]&keep);}
		}
		if((mask&DEPTH_BUFFER_BIT)!=0&&t.depthMask){float d=clamp01(t.clearDepth);for(int y=box[1];y<box[3];y++){int row=(height-1-y)*width;Arrays.fill(depth,row+box[0],row+box[2],d);}}
		if((mask&STENCIL_BUFFER_BIT)!=0){
			int wm=t.stencilWriteMask&255,v=t.clearStencil&255;
			for(int y=box[1];y<box[3];y++){int row=(height-1-y)*width;
				if(wm==255)Arrays.fill(stencil,row+box[0],row+box[2],(byte)v);
				else for(int x=box[0];x<box[2];x++)stencil[row+x]=(byte)((stencil[row+x]&~wm)|(v&wm));}
		}
	}

	// ---- raster position, bitmaps and pixel rectangles
	float rasterX,rasterY,rasterZ;boolean rasterValid=true;float[] rasterColor={1,1,1,1};
	void rasterPos(float x,float y,float z,float w){
		float[] v=process(x,y,z,w);
		for(int k=0;k<6;k++)if(clipDistance(v,k)<0){rasterValid=false;return;}
		toWindow(v);rasterX=v[WX];rasterY=v[WY];rasterZ=v[WZ];rasterValid=true;
		rasterColor=new float[]{v[R],v[G],v[B],v[A]};
	}
	/** glBitmap: {@code bits} rows bottom first, each row's bytes MSB first ({@code rowBytes} per row). */
	void bitmap(int w,int h,float xorig,float yorig,float xmove,float ymove,byte[] bits,int rowBytes){
		if(rasterValid&&bits!=null){
			preparePrimitive();anyTexture=false;texUnits=0;
			int x0=(int)Math.floor(rasterX-xorig),y0=(int)Math.floor(rasterY-yorig);
			int[] box=scissorBox();float[] c=rasterColor;
			for(int j=0;j<h;j++){int y=y0+j;if(y<box[1]||y>=box[3])continue;
				for(int i=0;i<w;i++){int x=x0+i;if(x<box[0]||x>=box[2])continue;
					if((bits[j*rowBytes+(i>>3)]&(0x80>>(i&7)))!=0)fragment((height-1-y)*width+x,rasterZ,c[0],c[1],c[2],c[3],1);}}
		}
		rasterX+=xmove;rasterY+=ymove;
	}
	/** glDrawPixels of ARGB pixels (rows bottom first) at the raster position, zoomed. */
	void drawPixels(int w,int h,int[] argb){
		if(!rasterValid)return;
		preparePrimitive();anyTexture=false;texUnits=0;
		float zx=s.zoomX,zy=s.zoomY;int[] box=scissorBox();
		for(int j=0;j<h;j++){
			int ya=(int)Math.ceil(rasterY+j*zy-0.5f),yb=(int)Math.ceil(rasterY+(j+1)*zy-0.5f);if(ya>yb){int t=ya;ya=yb;yb=t;}
			for(int y=ya;y<yb;y++){if(y<box[1]||y>=box[3])continue;
				for(int i=0;i<w;i++){
					int xa=(int)Math.ceil(rasterX+i*zx-0.5f),xb=(int)Math.ceil(rasterX+(i+1)*zx-0.5f);if(xa>xb){int t=xa;xa=xb;xb=t;}
					int c=argb[j*w+i];
					for(int x=xa;x<xb;x++){if(x<box[0]||x>=box[2])continue;
						fragment((height-1-y)*width+x,rasterZ,((c>>>16)&255)*INV255,((c>>>8)&255)*INV255,(c&255)*INV255,(c>>>24)*INV255,1);}
				}
			}
		}
	}
}

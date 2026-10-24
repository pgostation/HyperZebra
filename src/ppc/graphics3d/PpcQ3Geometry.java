import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The geometries of QuickDraw 3D ({@link PpcQuickDraw3D}): New, Submit, Get/SetData, EmptyData and the field accessors
 * of every kind in QD3DGeometry.h, converting between the public data structures in guest memory and {@link Q3}'s
 * objects - and the Mesh with its parts, iterators and corners. Attribute sets passed in are referenced; the ones
 * handed out (GetData) are new references the program gives back with EmptyData.
 */
final class PpcQ3Geometry {
	private final PpcQuickDraw3D q;private final M68kMemory memory;
	PpcQ3Geometry(PpcQuickDraw3D q){this.q=q;this.memory=q.memory;}

	private int rl(int a) throws M68kCpuException {return memory.readLong(a);}
	private void wl(int a,int v) throws M68kCpuException {memory.writeLong(a,v);}
	private float rf(int a) throws M68kCpuException {return Float.intBitsToFloat(memory.readLong(a));}
	private void wf(int a,float v) throws M68kCpuException {memory.writeLong(a,Float.floatToRawIntBits(v));}

	/** Blocks this class handed out in GetData (EmptyData frees only these). */
	private final Set<Integer> owned=new HashSet<Integer>();
	private int alloc(int size) throws M68kCpuException {int p=q.alloc(size);if(p!=0)owned.add(p);return p;}
	private void free(int p){if(p!=0&&owned.remove(p))q.free(p);}

	/** A referenced attribute set from a handle (null for 0 or anything else). */
	private Q3.AttrSet aset(int h){Q3.Obj o=q.obj(h);return o instanceof Q3.AttrSet?Q3.ref((Q3.AttrSet)o):null;}
	private int setOut(Q3.AttrSet s) throws M68kCpuException {return s==null?0:q.newRef(s);}
	private void releaseAt(int p) throws M68kCpuException {if(p==0)return;int h=rl(p);if(h!=0){Q3.Obj o=q.obj(h);if(o!=null)Q3.release(o);wl(p,0);}}

	// ---- bulk reads (the arrays of big meshes)
	float[] floats(int a,int n) throws M68kCpuException {
		float[] f=new float[Math.max(0,n)];if(n<=0||a==0)return f;
		byte[] m=memory.raw();int p=memory.physical(a);
		if(p<0||p+4L*n>m.length)throw new M68kCpuException(a,"QuickDraw 3D data outside memory");
		for(int i=0;i<n;i++,p+=4)f[i]=Float.intBitsToFloat((m[p]&0xff)<<24|(m[p+1]&0xff)<<16|(m[p+2]&0xff)<<8|m[p+3]&0xff);
		return f;
	}
	int[] ints(int a,int n) throws M68kCpuException {
		int[] f=new int[Math.max(0,n)];if(n<=0||a==0)return f;
		byte[] m=memory.raw();int p=memory.physical(a);
		if(p<0||p+4L*n>m.length)throw new M68kCpuException(a,"QuickDraw 3D data outside memory");
		for(int i=0;i<n;i++,p+=4)f[i]=(m[p]&0xff)<<24|(m[p+1]&0xff)<<16|(m[p+2]&0xff)<<8|m[p+3]&0xff;
		return f;
	}
	void putFloats(int a,float[] f,int n) throws M68kCpuException {
		byte[] b=new byte[4*n];for(int i=0;i<n;i++){int v=Float.floatToRawIntBits(f[i]);b[4*i]=(byte)(v>>>24);b[4*i+1]=(byte)(v>>>16);b[4*i+2]=(byte)(v>>>8);b[4*i+3]=(byte)v;}
		memory.load(a,b);
	}
	void putInts(int a,int[] f,int n) throws M68kCpuException {
		byte[] b=new byte[4*n];for(int i=0;i<n;i++){int v=f[i];b[4*i]=(byte)(v>>>24);b[4*i+1]=(byte)(v>>>16);b[4*i+2]=(byte)(v>>>8);b[4*i+3]=(byte)v;}
		memory.load(a,b);
	}

	// ---- vertices
	private Q3.Vertex vertex(int p) throws M68kCpuException {return new Q3.Vertex(rf(p),rf(p+4),rf(p+8),aset(rl(p+12)));}
	private void putVertex(int p,Q3.Vertex v) throws M68kCpuException {wf(p,v.x);wf(p+4,v.y);wf(p+8,v.z);wl(p+12,setOut(v.attrs));}
	private Q3.Vertex[] vertices(int p,int n) throws M68kCpuException {if(n<0||n>1<<22)throw new M68kCpuException(p,"vertex count "+n);Q3.Vertex[] v=new Q3.Vertex[n];for(int i=0;i<n;i++)v[i]=vertex(p+16*i);return v;}
	private int putVertices(Q3.Vertex[] v) throws M68kCpuException {int p=alloc(16*Math.max(1,v.length));for(int i=0;i<v.length;i++)putVertex(p+16*i,v[i]);return p;}
	private void emptyVertices(int p,int n) throws M68kCpuException {if(p==0)return;for(int i=0;i<n;i++)releaseAt(p+16*i+12);free(p);}
	private void rp(int p,float[] into) throws M68kCpuException {into[0]=rf(p);into[1]=rf(p+4);into[2]=rf(p+8);}
	private void wp(int p,float[] v) throws M68kCpuException {wf(p,v[0]);wf(p+4,v[1]);wf(p+8,v[2]);}

	/** The geometry of a handle if it is of {@code type}; posts an error otherwise. */
	@SuppressWarnings("unchecked")
	private <T extends Q3.Geometry> T geo(int h,int type,Class<T> c){
		Q3.Obj o=q.obj(h);
		if(o==null||!c.isInstance(o)||type!=0&&o.leaf()!=type){q.post(o==null?PpcQuickDraw3D.ERR_INVALID_OBJECT:PpcQuickDraw3D.ERR_INVALID_OBJECT_TYPE);return null;}
		return (T)o;
	}

	// ================================================================== the kinds

	/** Reading, writing and emptying the data structure of one geometry kind. */
	private interface Kind {
		Q3.Geometry read(int p) throws M68kCpuException;
		/** Replaces a geometry's data from the structure (the old references released). */
		void set(Q3.Geometry g,int p) throws M68kCpuException;
		void write(Q3.Geometry g,int p) throws M68kCpuException;
		void empty(int p) throws M68kCpuException;
	}
	/** New, Submit, SetData, GetData, EmptyData of a kind. */
	private void kind(String name,int type,Kind k){
		q.def("Q3"+name+"_New",a->{int p=a.i(0);if(p==0){q.post(PpcQuickDraw3D.ERR_NULL_PARAMETER);a.ret(0);return;}a.ret(q.made(k.read(p)));});
		q.def("Q3"+name+"_Submit",a->{int p=a.i(0);if(p==0){a.ok(false);return;}a.ok(q.submitTemporary(a.i(1),k.read(p)));});
		q.def("Q3"+name+"_SetData",a->{Q3.Geometry g=geo(a.i(0),type,Q3.Geometry.class);if(g==null||a.i(1)==0){a.ok(false);return;}k.set(g,a.i(1));g.edited();a.ok(true);});
		q.def("Q3"+name+"_GetData",a->{Q3.Geometry g=geo(a.i(0),type,Q3.Geometry.class);if(g==null||a.i(1)==0){a.ok(false);return;}k.write(g,a.i(1));a.ok(true);});
		q.def("Q3"+name+"_EmptyData",a->{if(a.i(0)==0){a.ok(false);return;}k.empty(a.i(0));a.ok(true);});
	}

	void define(){
		q.def("Q3Geometry_GetType",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof Q3.Geometry)){q.post(PpcQuickDraw3D.ERR_INVALID_OBJECT);a.ret(0);return;}a.ret(o.typeAt(3));});
		q.def("Q3Geometry_GetAttributeSet",a->{Q3.Geometry g=geo(a.i(0),0,Q3.Geometry.class);if(g==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),setOut(g.attrs));a.ok(true);});
		q.def("Q3Geometry_SetAttributeSet",a->{Q3.Geometry g=geo(a.i(0),0,Q3.Geometry.class);if(g==null){a.ok(false);return;}Q3.Obj s=q.obj(a.i(1));
			g.attrs=Q3.replace(g.attrs,s instanceof Q3.AttrSet?(Q3.AttrSet)s:null);g.edited();a.ok(true);});
		q.def("Q3Geometry_Submit",a->{Q3.Geometry g=geo(a.i(0),0,Q3.Geometry.class);Q3View v=q.get(a.i(1),Q3View.class);a.ok(g!=null&&v!=null&&q.submit(v,g));});
		defineTriangles();defineQuadrics();defineLines();defineMarkers();defineTriMesh();defineGrids();defineNurbs();defineMesh();
	}

	// ================================================================== triangle, polygon, general polygon

	private void defineTriangles(){
		kind("Triangle",Q3.TRIANGLE,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.Triangle t=new Q3.Triangle();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.Triangle t=(Q3.Triangle)g;Q3.releaseAll(t.v);for(int i=0;i<3;i++)t.v[i]=vertex(p+16*i);t.attrs=Q3.replace(t.attrs,null);t.attrs=aset(rl(p+48));}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.Triangle t=(Q3.Triangle)g;for(int i=0;i<3;i++)putVertex(p+16*i,t.v[i]);wl(p+48,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {for(int i=0;i<3;i++)releaseAt(p+16*i+12);releaseAt(p+48);}
		});
		vertexAccess("Triangle",Q3.TRIANGLE,g->((Q3.Triangle)g).v);
		kind("Polygon",Q3.POLYGON,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.Polygon t=new Q3.Polygon();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.Polygon t=(Q3.Polygon)g;Q3.Vertex[] old=t.v;t.v=vertices(rl(p+4),rl(p));Q3.releaseAll(old);Q3.AttrSet s=aset(rl(p+8));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.Polygon t=(Q3.Polygon)g;wl(p,t.v.length);wl(p+4,putVertices(t.v));wl(p+8,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {emptyVertices(rl(p+4),rl(p));wl(p+4,0);wl(p,0);releaseAt(p+8);}
		});
		vertexAccess("Polygon",Q3.POLYGON,g->((Q3.Polygon)g).v);
		kind("GeneralPolygon",Q3.GENERAL_POLYGON,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.GeneralPolygon t=new Q3.GeneralPolygon();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.GeneralPolygon t=(Q3.GeneralPolygon)g;int n=rl(p),c=rl(p+4);Q3.Vertex[][] cs=new Q3.Vertex[Math.max(0,n)][];
				for(int i=0;i<n;i++)cs[i]=vertices(rl(c+8*i+4),rl(c+8*i));
				for(Q3.Vertex[] o:t.contours)Q3.releaseAll(o);t.contours=cs;t.shapeHint=rl(p+8);Q3.AttrSet s=aset(rl(p+12));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.GeneralPolygon t=(Q3.GeneralPolygon)g;int c=alloc(8*Math.max(1,t.contours.length));
				for(int i=0;i<t.contours.length;i++){wl(c+8*i,t.contours[i].length);wl(c+8*i+4,putVertices(t.contours[i]));}
				wl(p,t.contours.length);wl(p+4,c);wl(p+8,t.shapeHint);wl(p+12,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {int n=rl(p),c=rl(p+4);if(c!=0){for(int i=0;i<n;i++)emptyVertices(rl(c+8*i+4),rl(c+8*i));free(c);}wl(p,0);wl(p+4,0);releaseAt(p+12);}
		});
		q.def("Q3GeneralPolygon_GetVertexPosition",a->{Q3.GeneralPolygon t=geo(a.i(0),Q3.GENERAL_POLYGON,Q3.GeneralPolygon.class);Q3.Vertex v=gpv(t,a.i(1),a.i(2));if(v==null||a.i(3)==0){a.ok(false);return;}wp(a.i(3),new float[]{v.x,v.y,v.z});a.ok(true);});
		q.def("Q3GeneralPolygon_SetVertexPosition",a->{Q3.GeneralPolygon t=geo(a.i(0),Q3.GENERAL_POLYGON,Q3.GeneralPolygon.class);Q3.Vertex v=gpv(t,a.i(1),a.i(2));if(v==null||a.i(3)==0){a.ok(false);return;}v.x=rf(a.i(3));v.y=rf(a.i(3)+4);v.z=rf(a.i(3)+8);t.edited();a.ok(true);});
		q.def("Q3GeneralPolygon_GetVertexAttributeSet",a->{Q3.GeneralPolygon t=geo(a.i(0),Q3.GENERAL_POLYGON,Q3.GeneralPolygon.class);Q3.Vertex v=gpv(t,a.i(1),a.i(2));if(v==null||a.i(3)==0){a.ok(false);return;}wl(a.i(3),setOut(v.attrs));a.ok(true);});
		q.def("Q3GeneralPolygon_SetVertexAttributeSet",a->{Q3.GeneralPolygon t=geo(a.i(0),Q3.GENERAL_POLYGON,Q3.GeneralPolygon.class);Q3.Vertex v=gpv(t,a.i(1),a.i(2));if(v==null){a.ok(false);return;}Q3.AttrSet s=aset(a.i(3));Q3.release(v.attrs);v.attrs=s;t.edited();a.ok(true);});
		q.def("Q3GeneralPolygon_SetShapeHint",a->{Q3.GeneralPolygon t=geo(a.i(0),Q3.GENERAL_POLYGON,Q3.GeneralPolygon.class);if(t==null){a.ok(false);return;}t.shapeHint=a.i(1);t.edited();a.ok(true);});
		q.def("Q3GeneralPolygon_GetShapeHint",a->{Q3.GeneralPolygon t=geo(a.i(0),Q3.GENERAL_POLYGON,Q3.GeneralPolygon.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.shapeHint);a.ok(true);});
	}
	private Q3.Vertex gpv(Q3.GeneralPolygon t,int c,int i){if(t==null||c<0||c>=t.contours.length||i<0||i>=t.contours[c].length){if(t!=null)q.post(PpcQuickDraw3D.ERR_PARAMETER_OUT_OF_RANGE);return null;}return t.contours[c][i];}
	interface Verts { Q3.Vertex[] of(Q3.Geometry g); }
	/** Get/SetVertexPosition and Get/SetVertexAttributeSet by index. */
	private void vertexAccess(String name,int type,Verts f){
		q.def("Q3"+name+"_GetVertexPosition",a->{Q3.Geometry g=geo(a.i(0),type,Q3.Geometry.class);Q3.Vertex v=at(g,f,a.i(1));if(v==null||a.i(2)==0){a.ok(false);return;}wp(a.i(2),new float[]{v.x,v.y,v.z});a.ok(true);});
		q.def("Q3"+name+"_SetVertexPosition",a->{Q3.Geometry g=geo(a.i(0),type,Q3.Geometry.class);Q3.Vertex v=at(g,f,a.i(1));if(v==null||a.i(2)==0){a.ok(false);return;}v.x=rf(a.i(2));v.y=rf(a.i(2)+4);v.z=rf(a.i(2)+8);g.edited();a.ok(true);});
		q.def("Q3"+name+"_GetVertexAttributeSet",a->{Q3.Geometry g=geo(a.i(0),type,Q3.Geometry.class);Q3.Vertex v=at(g,f,a.i(1));if(v==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),setOut(v.attrs));a.ok(true);});
		q.def("Q3"+name+"_SetVertexAttributeSet",a->{Q3.Geometry g=geo(a.i(0),type,Q3.Geometry.class);Q3.Vertex v=at(g,f,a.i(1));if(v==null){a.ok(false);return;}Q3.AttrSet s=aset(a.i(2));Q3.release(v.attrs);v.attrs=s;g.edited();a.ok(true);});
	}
	private Q3.Vertex at(Q3.Geometry g,Verts f,int i){if(g==null)return null;Q3.Vertex[] v=f.of(g);if(i<0||i>=v.length){q.post(PpcQuickDraw3D.ERR_PARAMETER_OUT_OF_RANGE);return null;}return v[i];}

	// ================================================================== box, cone, cylinder, disk, ellipse, ellipsoid, torus

	/** Offsets in the data structures: orientation (-1 none), major, minor, uMin (-1 none), ratio, caps, interior, top, face, bottom, attributes. */
	private static final Map<Integer,int[]> QUADRIC=new HashMap<Integer,int[]>();
	static{
		//                                    orient major minor  uv  ratio caps inter top face bottom attrs
		QUADRIC.put(Q3.BOX,      new int[]{   12,   24,   36,   -1,  -1,  -1,  -1,  -1,  -1,  -1,   52});
		QUADRIC.put(Q3.CONE,     new int[]{   12,   24,   36,   48,  -1,  64,  68,  -1,  72,  76,   80});
		QUADRIC.put(Q3.CYLINDER, new int[]{   12,   24,   36,   48,  -1,  64,  68,  72,  76,  80,   84});
		QUADRIC.put(Q3.DISK,     new int[]{   -1,   12,   24,   36,  -1,  -1,  -1,  -1,  -1,  -1,   52});
		QUADRIC.put(Q3.ELLIPSE,  new int[]{   -1,   12,   24,   36,  -1,  -1,  -1,  -1,  -1,  -1,   44});
		QUADRIC.put(Q3.ELLIPSOID,new int[]{   12,   24,   36,   48,  -1,  64,  68,  -1,  -1,  -1,   72});
		QUADRIC.put(Q3.TORUS,    new int[]{   12,   24,   36,   52,  48,  68,  72,  -1,  -1,  -1,   76});
	}
	private void readQuadric(Q3.Quadric g,int p) throws M68kCpuException {
		int[] o=QUADRIC.get(g.leaf());
		rp(p,g.origin);if(o[0]>=0)rp(p+o[0],g.orientation);rp(p+o[1],g.major);rp(p+o[2],g.minor);
		if(o[3]>=0){g.uMin=rf(p+o[3]);g.uMax=rf(p+o[3]+4);if(g.leaf()!=Q3.ELLIPSE){g.vMin=rf(p+o[3]+8);g.vMax=rf(p+o[3]+12);}}
		if(o[4]>=0)g.ratio=rf(p+o[4]);
		if(o[5]>=0)g.caps=rl(p+o[5]);
		Q3.AttrSet in=o[6]>=0?aset(rl(p+o[6])):null,top=o[7]>=0?aset(rl(p+o[7])):null,face=o[8]>=0?aset(rl(p+o[8])):null,bottom=o[9]>=0?aset(rl(p+o[9])):null,attrs=aset(rl(p+o[10]));
		Q3.release(g.interior);Q3.release(g.top);Q3.release(g.face);Q3.release(g.bottom);Q3.release(g.attrs);
		g.interior=in;g.top=top;g.face=face;g.bottom=bottom;g.attrs=attrs;
		if(g.leaf()==Q3.BOX){int f=rl(p+48);Q3.AttrSet[] faces=null;if(f!=0){faces=new Q3.AttrSet[6];for(int i=0;i<6;i++)faces[i]=aset(rl(f+4*i));}Q3.releaseAll(g.faces);g.faces=faces;}
	}
	private void writeQuadric(Q3.Quadric g,int p) throws M68kCpuException {
		int[] o=QUADRIC.get(g.leaf());
		wp(p,g.origin);if(o[0]>=0)wp(p+o[0],g.orientation);wp(p+o[1],g.major);wp(p+o[2],g.minor);
		if(o[3]>=0){wf(p+o[3],g.uMin);wf(p+o[3]+4,g.uMax);if(g.leaf()!=Q3.ELLIPSE){wf(p+o[3]+8,g.vMin);wf(p+o[3]+12,g.vMax);}}
		if(o[4]>=0)wf(p+o[4],g.ratio);
		if(o[5]>=0)wl(p+o[5],g.caps);
		if(o[6]>=0)wl(p+o[6],setOut(g.interior));if(o[7]>=0)wl(p+o[7],setOut(g.top));if(o[8]>=0)wl(p+o[8],setOut(g.face));if(o[9]>=0)wl(p+o[9],setOut(g.bottom));
		wl(p+o[10],setOut(g.attrs));
		if(g.leaf()==Q3.BOX){if(g.faces==null)wl(p+48,0);else{int f=alloc(24);for(int i=0;i<6;i++)wl(f+4*i,setOut(g.faces[i]));wl(p+48,f);}}
	}
	private void emptyQuadric(int type,int p) throws M68kCpuException {
		int[] o=QUADRIC.get(type);
		for(int k=6;k<=10;k++)if(o[k]>=0)releaseAt(p+o[k]);
		if(type==Q3.BOX){int f=rl(p+48);if(f!=0){for(int i=0;i<6;i++)releaseAt(f+4*i);free(f);wl(p+48,0);}}
	}
	private void defineQuadrics(){
		String[][] kinds={{"Box","box "},{"Cone","cone"},{"Cylinder","cyln"},{"Disk","disk"},{"Ellipse","elps"},{"Ellipsoid","elpd"},{"Torus","tors"}};
		for(String[] k:kinds){
			int type=Q3.fc(k[1]);
			kind(k[0],type,new Kind(){
				public Q3.Geometry read(int p) throws M68kCpuException {Q3.Quadric g=new Q3.Quadric(type);readQuadric(g,p);return g;}
				public void set(Q3.Geometry g,int p) throws M68kCpuException {readQuadric((Q3.Quadric)g,p);}
				public void write(Q3.Geometry g,int p) throws M68kCpuException {writeQuadric((Q3.Quadric)g,p);}
				public void empty(int p) throws M68kCpuException {emptyQuadric(type,p);}
			});
			String n=k[0];boolean box=type==Q3.BOX;
			vectorField(n,"Origin",type,g->g.origin);
			if(type!=Q3.DISK&&type!=Q3.ELLIPSE)vectorField(n,"Orientation",type,g->g.orientation);
			vectorField(n,box?"MajorAxis":"MajorRadius",type,g->g.major);
			vectorField(n,box?"MinorAxis":"MinorRadius",type,g->g.minor);
			if(type==Q3.CONE||type==Q3.CYLINDER){
				q.def("Q3"+n+"_SetCaps",a->{Q3.Quadric g=geo(a.i(0),type,Q3.Quadric.class);if(g==null){a.ok(false);return;}g.caps=a.i(1);g.edited();a.ok(true);});
				q.def("Q3"+n+"_GetCaps",a->{Q3.Quadric g=geo(a.i(0),type,Q3.Quadric.class);if(g==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),g.caps);a.ok(true);});
				partSet(n,"Bottom",type,2);partSet(n,"Face",type,1);if(type==Q3.CYLINDER)partSet(n,"Top",type,0);
			}
		}
		q.def("Q3Box_GetFaceAttributeSet",a->{Q3.Quadric g=geo(a.i(0),Q3.BOX,Q3.Quadric.class);int i=a.i(1);if(g==null||a.i(2)==0||i<0||i>5){a.ok(false);return;}
			wl(a.i(2),g.faces==null?0:setOut(g.faces[i]));a.ok(true);});
		q.def("Q3Box_SetFaceAttributeSet",a->{Q3.Quadric g=geo(a.i(0),Q3.BOX,Q3.Quadric.class);int i=a.i(1);if(g==null||i<0||i>5){a.ok(false);return;}
			if(g.faces==null)g.faces=new Q3.AttrSet[6];Q3.AttrSet s=aset(a.i(2));Q3.release(g.faces[i]);g.faces[i]=s;g.edited();a.ok(true);});
		q.def("Q3Torus_SetRatio",a->{Q3.Quadric g=geo(a.i(0),Q3.TORUS,Q3.Quadric.class);if(g==null){a.ok(false);return;}g.ratio=a.f(1);g.edited();a.ok(true);});
		q.def("Q3Torus_GetRatio",a->{Q3.Quadric g=geo(a.i(0),Q3.TORUS,Q3.Quadric.class);if(g==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),g.ratio);a.ok(true);});
	}
	interface Vec { float[] of(Q3.Quadric g); }
	private void vectorField(String n,String field,int type,Vec f){
		q.def("Q3"+n+"_Set"+field,a->{Q3.Quadric g=geo(a.i(0),type,Q3.Quadric.class);if(g==null||a.i(1)==0){a.ok(false);return;}rp(a.i(1),f.of(g));g.edited();a.ok(true);});
		q.def("Q3"+n+"_Get"+field,a->{Q3.Quadric g=geo(a.i(0),type,Q3.Quadric.class);if(g==null||a.i(1)==0){a.ok(false);return;}wp(a.i(1),f.of(g));a.ok(true);});
	}
	/** Top (0), face (1), bottom (2) attribute sets of cones and cylinders. */
	private void partSet(String n,String part,int type,int which){
		q.def("Q3"+n+"_Set"+part+"AttributeSet",a->{Q3.Quadric g=geo(a.i(0),type,Q3.Quadric.class);if(g==null){a.ok(false);return;}Q3.AttrSet s=aset(a.i(1));
			if(which==0){Q3.release(g.top);g.top=s;}else if(which==1){Q3.release(g.face);g.face=s;}else{Q3.release(g.bottom);g.bottom=s;}g.edited();a.ok(true);});
		q.def("Q3"+n+"_Get"+part+"AttributeSet",a->{Q3.Quadric g=geo(a.i(0),type,Q3.Quadric.class);if(g==null||a.i(1)==0){a.ok(false);return;}
			wl(a.i(1),setOut(which==0?g.top:which==1?g.face:g.bottom));a.ok(true);});
	}

	// ================================================================== lines, points

	private void defineLines(){
		kind("Line",Q3.LINE,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.Line t=new Q3.Line();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.Line t=(Q3.Line)g;Q3.releaseAll(t.v);t.v[0]=vertex(p);t.v[1]=vertex(p+16);Q3.AttrSet s=aset(rl(p+32));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.Line t=(Q3.Line)g;putVertex(p,t.v[0]);putVertex(p+16,t.v[1]);wl(p+32,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {releaseAt(p+12);releaseAt(p+28);releaseAt(p+32);}
		});
		vertexAccess("Line",Q3.LINE,g->((Q3.Line)g).v);
		kind("PolyLine",Q3.POLYLINE,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.PolyLine t=new Q3.PolyLine();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.PolyLine t=(Q3.PolyLine)g;int n=rl(p);Q3.Vertex[] v=vertices(rl(p+4),n);int segs=rl(p+8);Q3.AttrSet[] s=null;
				if(segs!=0&&n>1){s=new Q3.AttrSet[n-1];for(int i=0;i<n-1;i++)s[i]=aset(rl(segs+4*i));}
				Q3.releaseAll(t.v);Q3.releaseAll(t.segments);t.v=v;t.segments=s;Q3.AttrSet a=aset(rl(p+12));Q3.release(t.attrs);t.attrs=a;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.PolyLine t=(Q3.PolyLine)g;wl(p,t.v.length);wl(p+4,putVertices(t.v));
				if(t.segments==null)wl(p+8,0);else{int s=alloc(4*Math.max(1,t.segments.length));for(int i=0;i<t.segments.length;i++)wl(s+4*i,setOut(t.segments[i]));wl(p+8,s);}
				wl(p+12,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {int n=rl(p);emptyVertices(rl(p+4),n);int s=rl(p+8);if(s!=0){for(int i=0;i<n-1;i++)releaseAt(s+4*i);free(s);}wl(p,0);wl(p+4,0);wl(p+8,0);releaseAt(p+12);}
		});
		vertexAccess("PolyLine",Q3.POLYLINE,g->((Q3.PolyLine)g).v);
		q.def("Q3PolyLine_GetSegmentAttributeSet",a->{Q3.PolyLine t=geo(a.i(0),Q3.POLYLINE,Q3.PolyLine.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||i>=t.v.length-1){a.ok(false);return;}
			wl(a.i(2),t.segments==null?0:setOut(t.segments[i]));a.ok(true);});
		q.def("Q3PolyLine_SetSegmentAttributeSet",a->{Q3.PolyLine t=geo(a.i(0),Q3.POLYLINE,Q3.PolyLine.class);int i=a.i(1);if(t==null||i<0||i>=t.v.length-1){a.ok(false);return;}
			if(t.segments==null)t.segments=new Q3.AttrSet[t.v.length-1];Q3.AttrSet s=aset(a.i(2));Q3.release(t.segments[i]);t.segments[i]=s;t.edited();a.ok(true);});
		kind("Point",Q3.POINT,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.Point t=new Q3.Point();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.Point t=(Q3.Point)g;t.x=rf(p);t.y=rf(p+4);t.z=rf(p+8);Q3.AttrSet s=aset(rl(p+12));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.Point t=(Q3.Point)g;wf(p,t.x);wf(p+4,t.y);wf(p+8,t.z);wl(p+12,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {releaseAt(p+12);}
		});
		q.def("Q3Point_SetPosition",a->{Q3.Point t=geo(a.i(0),Q3.POINT,Q3.Point.class);int p=a.i(1);if(t==null||p==0){a.ok(false);return;}t.x=rf(p);t.y=rf(p+4);t.z=rf(p+8);t.edited();a.ok(true);});
		q.def("Q3Point_GetPosition",a->{Q3.Point t=geo(a.i(0),Q3.POINT,Q3.Point.class);int p=a.i(1);if(t==null||p==0){a.ok(false);return;}wf(p,t.x);wf(p+4,t.y);wf(p+8,t.z);a.ok(true);});
	}

	// ================================================================== markers

	private void defineMarkers(){
		kind("Marker",Q3.MARKER,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.Marker t=new Q3.Marker();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.Marker t=(Q3.Marker)g;t.x=rf(p);t.y=rf(p+4);t.z=rf(p+8);t.xOffset=rl(p+12);t.yOffset=rl(p+16);
				Q3.Bitmap b=q.readBitmap(p+20);t.bitmap=b!=null?b:new Q3.Bitmap();Q3.AttrSet s=aset(rl(p+40));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.Marker t=(Q3.Marker)g;wf(p,t.x);wf(p+4,t.y);wf(p+8,t.z);wl(p+12,t.xOffset);wl(p+16,t.yOffset);
				q.writeBitmap(p+20,t.bitmap);if(rl(p+20)!=0)owned.add(rl(p+20));wl(p+40,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {int img=rl(p+20);if(img!=0){if(owned.contains(img))free(img);else q.free(img);wl(p+20,0);}releaseAt(p+40);}
		});
		q.def("Q3Marker_GetPosition",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null||a.i(1)==0){a.ok(false);return;}wp(a.i(1),new float[]{t.x,t.y,t.z});a.ok(true);});
		q.def("Q3Marker_SetPosition",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null||a.i(1)==0){a.ok(false);return;}t.x=rf(a.i(1));t.y=rf(a.i(1)+4);t.z=rf(a.i(1)+8);t.edited();a.ok(true);});
		q.def("Q3Marker_GetXOffset",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.xOffset);a.ok(true);});
		q.def("Q3Marker_SetXOffset",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null){a.ok(false);return;}t.xOffset=a.i(1);t.edited();a.ok(true);});
		q.def("Q3Marker_GetYOffset",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.yOffset);a.ok(true);});
		q.def("Q3Marker_SetYOffset",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null){a.ok(false);return;}t.yOffset=a.i(1);t.edited();a.ok(true);});
		q.def("Q3Marker_GetBitmap",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null||a.i(1)==0){a.ok(false);return;}q.writeBitmap(a.i(1),t.bitmap);a.ok(true);});
		q.def("Q3Marker_SetBitmap",a->{Q3.Marker t=geo(a.i(0),Q3.MARKER,Q3.Marker.class);if(t==null||a.i(1)==0){a.ok(false);return;}Q3.Bitmap b=q.readBitmap(a.i(1));t.bitmap=b!=null?b:new Q3.Bitmap();t.edited();a.ok(true);});
		kind("PixmapMarker",Q3.PIXMAP_MARKER,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.PixmapMarker t=new Q3.PixmapMarker();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.PixmapMarker t=(Q3.PixmapMarker)g;t.x=rf(p);t.y=rf(p+4);t.z=rf(p+8);t.xOffset=rl(p+12);t.yOffset=rl(p+16);
				Q3.StoragePixmap old=t.pixmap;t.pixmap=q.readStoragePixmap(p+20);Q3.release(old.image);Q3.AttrSet s=aset(rl(p+52));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.PixmapMarker t=(Q3.PixmapMarker)g;wf(p,t.x);wf(p+4,t.y);wf(p+8,t.z);wl(p+12,t.xOffset);wl(p+16,t.yOffset);
				q.writeStoragePixmap(p+20,t.pixmap);wl(p+52,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {releaseAt(p+20);releaseAt(p+52);}
		});
		q.def("Q3PixmapMarker_GetPosition",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null||a.i(1)==0){a.ok(false);return;}wp(a.i(1),new float[]{t.x,t.y,t.z});a.ok(true);});
		q.def("Q3PixmapMarker_SetPosition",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null||a.i(1)==0){a.ok(false);return;}t.x=rf(a.i(1));t.y=rf(a.i(1)+4);t.z=rf(a.i(1)+8);t.edited();a.ok(true);});
		q.def("Q3PixmapMarker_GetXOffset",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.xOffset);a.ok(true);});
		q.def("Q3PixmapMarker_SetXOffset",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null){a.ok(false);return;}t.xOffset=a.i(1);t.edited();a.ok(true);});
		q.def("Q3PixmapMarker_GetYOffset",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.yOffset);a.ok(true);});
		q.def("Q3PixmapMarker_SetYOffset",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null){a.ok(false);return;}t.yOffset=a.i(1);t.edited();a.ok(true);});
		q.def("Q3PixmapMarker_GetPixmap",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null||a.i(1)==0){a.ok(false);return;}q.writeStoragePixmap(a.i(1),t.pixmap);a.ok(true);});
		q.def("Q3PixmapMarker_SetPixmap",a->{Q3.PixmapMarker t=geo(a.i(0),Q3.PIXMAP_MARKER,Q3.PixmapMarker.class);if(t==null||a.i(1)==0){a.ok(false);return;}Q3.StoragePixmap old=t.pixmap;t.pixmap=q.readStoragePixmap(a.i(1));Q3.release(old.image);t.edited();a.ok(true);});
	}

	// ================================================================== TriMesh

	/** Attribute arrays (TQ3TriMeshAttributeData) for {@code count} elements. */
	private Q3.AttrArray[] attrArrays(int p,int n,int count) throws M68kCpuException {
		if(p==0||n<=0)return new Q3.AttrArray[0];
		List<Q3.AttrArray> l=new ArrayList<Q3.AttrArray>();
		for(int i=0;i<n;i++){
			int e=p+12*i;int type=rl(e),data=rl(e+4),use=rl(e+8);
			if(data==0)continue;
			Q3.AttrArray a=new Q3.AttrArray();a.type=type;
			if(Q3.isStandardAttribute(type)&&Q3.ATTR_FLOATS[type]>0)a.f=floats(data,Q3.ATTR_FLOATS[type]*count);
			else if(type==Q3.ATTR_HIGHLIGHT_STATE)a.i=ints(data,count);
			else if(type==Q3.ATTR_SURFACE_SHADER){int[] h=ints(data,count);a.o=new Q3.Obj[count];for(int k=0;k<count;k++){Q3.Obj o=q.obj(h[k]);a.o[k]=Q3.ref(o);}}
			else{int size=q.elementSize(type);if(size<=0)continue;a.rawSize=size;a.raw=memory.readBytes(data,size*count);}
			if(use!=0)a.use=memory.readBytes(use,count);
			l.add(a);
		}
		return l.toArray(new Q3.AttrArray[0]);
	}
	/** Writes attribute arrays for the program (freshly allocated): the TQ3TriMeshAttributeData array. */
	private int putAttrArrays(Q3.AttrArray[] arrays,int count) throws M68kCpuException {
		if(arrays.length==0)return 0;
		int p=alloc(12*arrays.length);
		for(int i=0;i<arrays.length;i++){
			Q3.AttrArray a=arrays[i];int e=p+12*i;wl(e,a.type);
			int data;
			if(a.f!=null){data=alloc(4*a.f.length);putFloats(data,a.f,a.f.length);}
			else if(a.i!=null){data=alloc(4*a.i.length);putInts(data,a.i,a.i.length);}
			else if(a.o!=null){data=alloc(4*a.o.length);for(int k=0;k<a.o.length;k++)wl(data+4*k,a.o[k]==null?0:q.newRef(a.o[k]));}
			else{data=alloc(a.raw==null?4:a.raw.length);if(a.raw!=null)memory.load(data,a.raw);}
			wl(e+4,data);
			if(a.use!=null){int u=alloc(a.use.length);memory.load(u,a.use);wl(e+8,u);}else wl(e+8,0);
		}
		return p;
	}
	private void emptyAttrArrays(int p,int n,int count) throws M68kCpuException {
		if(p==0)return;
		for(int i=0;i<n;i++){int e=p+12*i;int type=rl(e),data=rl(e+4);
			if(type==Q3.ATTR_SURFACE_SHADER&&data!=0)for(int k=0;k<count;k++)releaseAt(data+4*k);
			free(data);free(rl(e+8));}
		free(p);
	}
	void readTriMesh(Q3.TriMesh t,int p) throws M68kCpuException {
		Q3.AttrSet s=aset(rl(p));
		int nt=rl(p+4),np=rl(p+36),ne=rl(p+20);
		if(nt<0||np<0||ne<0||nt>1<<22||np>1<<22||ne>1<<22){Q3.release(s);throw new M68kCpuException(p,"TriMesh counts "+nt+" "+np+" "+ne);}
		int[] tri=ints(rl(p+8),3*nt);Q3.AttrArray[] ta=attrArrays(rl(p+16),rl(p+12),nt);
		int[] edges=ints(rl(p+24),4*ne);Q3.AttrArray[] ea=attrArrays(rl(p+32),rl(p+28),ne);
		float[] pts=floats(rl(p+40),3*np);Q3.AttrArray[] va=attrArrays(rl(p+48),rl(p+44),np);
		for(Q3.AttrArray a:t.triangleAttrs)a.release();for(Q3.AttrArray a:t.edgeAttrs)a.release();for(Q3.AttrArray a:t.vertexAttrs)a.release();
		Q3.release(t.attrs);t.attrs=s;
		t.triangles=tri;t.triangleAttrs=ta;t.edges=edges;t.edgeAttrs=ea;t.points=pts;t.vertexAttrs=va;
		float[] bb=floats(p+52,6);System.arraycopy(bb,0,t.bbox,0,6);t.bboxEmpty=rl(p+76)!=0;
	}
	void writeTriMesh(Q3.TriMesh t,int p) throws M68kCpuException {
		wl(p,setOut(t.attrs));
		int nt=t.numTriangles(),ne=t.numEdges(),np=t.numPoints();
		wl(p+4,nt);int tri=alloc(4*Math.max(1,t.triangles.length));putInts(tri,t.triangles,t.triangles.length);wl(p+8,tri);
		wl(p+12,t.triangleAttrs.length);wl(p+16,putAttrArrays(t.triangleAttrs,nt));
		wl(p+20,ne);int e=ne==0?0:alloc(4*t.edges.length);if(e!=0)putInts(e,t.edges,t.edges.length);wl(p+24,e);
		wl(p+28,t.edgeAttrs.length);wl(p+32,putAttrArrays(t.edgeAttrs,ne));
		wl(p+36,np);int pts=alloc(4*Math.max(1,t.points.length));putFloats(pts,t.points,t.points.length);wl(p+40,pts);
		wl(p+44,t.vertexAttrs.length);wl(p+48,putAttrArrays(t.vertexAttrs,np));
		putFloats(p+52,t.bbox,6);wl(p+76,t.bboxEmpty?1:0);
	}
	private void defineTriMesh(){
		kind("TriMesh",Q3.TRIMESH,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.TriMesh t=new Q3.TriMesh();readTriMesh(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {readTriMesh((Q3.TriMesh)g,p);}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {writeTriMesh((Q3.TriMesh)g,p);}
			public void empty(int p) throws M68kCpuException {
				releaseAt(p);int nt=rl(p+4),ne=rl(p+20),np=rl(p+36);
				free(rl(p+8));emptyAttrArrays(rl(p+16),rl(p+12),nt);free(rl(p+24));emptyAttrArrays(rl(p+32),rl(p+28),ne);free(rl(p+40));emptyAttrArrays(rl(p+48),rl(p+44),np);
				for(int o=4;o<52;o+=4)wl(p+o,0);
			}
		});
	}

	// ================================================================== TriGrid, Polyhedron

	private void defineGrids(){
		kind("TriGrid",Q3.TRIGRID,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.TriGrid t=new Q3.TriGrid();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.TriGrid t=(Q3.TriGrid)g;int rows=rl(p),cols=rl(p+4);Q3.Vertex[] v=vertices(rl(p+8),rows*cols);
				int f=rl(p+12);int nf=2*Math.max(0,rows-1)*Math.max(0,cols-1);Q3.AttrSet[] facets=null;
				if(f!=0){facets=new Q3.AttrSet[nf];for(int i=0;i<nf;i++)facets[i]=aset(rl(f+4*i));}
				Q3.releaseAll(t.v);Q3.releaseAll(t.facets);t.rows=rows;t.columns=cols;t.v=v;t.facets=facets;Q3.AttrSet s=aset(rl(p+16));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.TriGrid t=(Q3.TriGrid)g;wl(p,t.rows);wl(p+4,t.columns);wl(p+8,putVertices(t.v));
				if(t.facets==null)wl(p+12,0);else{int f=alloc(4*Math.max(1,t.facets.length));for(int i=0;i<t.facets.length;i++)wl(f+4*i,setOut(t.facets[i]));wl(p+12,f);}
				wl(p+16,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {int rows=rl(p),cols=rl(p+4);emptyVertices(rl(p+8),rows*cols);int f=rl(p+12);int nf=2*Math.max(0,rows-1)*Math.max(0,cols-1);
				if(f!=0){for(int i=0;i<nf;i++)releaseAt(f+4*i);free(f);}wl(p+8,0);wl(p+12,0);releaseAt(p+16);}
		});
		q.def("Q3TriGrid_GetVertexPosition",a->{Q3.TriGrid t=geo(a.i(0),Q3.TRIGRID,Q3.TriGrid.class);Q3.Vertex v=gridV(t,a.i(1),a.i(2));if(v==null||a.i(3)==0){a.ok(false);return;}wp(a.i(3),new float[]{v.x,v.y,v.z});a.ok(true);});
		q.def("Q3TriGrid_SetVertexPosition",a->{Q3.TriGrid t=geo(a.i(0),Q3.TRIGRID,Q3.TriGrid.class);Q3.Vertex v=gridV(t,a.i(1),a.i(2));if(v==null||a.i(3)==0){a.ok(false);return;}v.x=rf(a.i(3));v.y=rf(a.i(3)+4);v.z=rf(a.i(3)+8);t.edited();a.ok(true);});
		q.def("Q3TriGrid_GetVertexAttributeSet",a->{Q3.TriGrid t=geo(a.i(0),Q3.TRIGRID,Q3.TriGrid.class);Q3.Vertex v=gridV(t,a.i(1),a.i(2));if(v==null||a.i(3)==0){a.ok(false);return;}wl(a.i(3),setOut(v.attrs));a.ok(true);});
		q.def("Q3TriGrid_SetVertexAttributeSet",a->{Q3.TriGrid t=geo(a.i(0),Q3.TRIGRID,Q3.TriGrid.class);Q3.Vertex v=gridV(t,a.i(1),a.i(2));if(v==null){a.ok(false);return;}Q3.AttrSet s=aset(a.i(3));Q3.release(v.attrs);v.attrs=s;t.edited();a.ok(true);});
		q.def("Q3TriGrid_GetFacetAttributeSet",a->{Q3.TriGrid t=geo(a.i(0),Q3.TRIGRID,Q3.TriGrid.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||i>=2*(t.rows-1)*(t.columns-1)){a.ok(false);return;}
			wl(a.i(2),t.facets==null?0:setOut(t.facets[i]));a.ok(true);});
		q.def("Q3TriGrid_SetFacetAttributeSet",a->{Q3.TriGrid t=geo(a.i(0),Q3.TRIGRID,Q3.TriGrid.class);int i=a.i(1);int nf=t==null?0:2*(t.rows-1)*(t.columns-1);if(t==null||i<0||i>=nf){a.ok(false);return;}
			if(t.facets==null)t.facets=new Q3.AttrSet[nf];Q3.AttrSet s=aset(a.i(2));Q3.release(t.facets[i]);t.facets[i]=s;t.edited();a.ok(true);});
		kind("Polyhedron",Q3.POLYHEDRON,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.Polyhedron t=new Q3.Polyhedron();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.Polyhedron t=(Q3.Polyhedron)g;Q3.Vertex[] v=vertices(rl(p+4),rl(p));
				int ne=rl(p+8),e=rl(p+12),nt=rl(p+16),tr=rl(p+20);
				int[] edges=new int[4*Math.max(0,ne)];Q3.AttrSet[] ea=new Q3.AttrSet[Math.max(0,ne)];
				for(int i=0;i<ne&&e!=0;i++){int o=e+20*i;edges[4*i]=rl(o);edges[4*i+1]=rl(o+4);edges[4*i+2]=rl(o+8);edges[4*i+3]=rl(o+12);ea[i]=aset(rl(o+16));}
				int[] tris=new int[3*Math.max(0,nt)],flags=new int[Math.max(0,nt)];Q3.AttrSet[] ta=new Q3.AttrSet[Math.max(0,nt)];
				for(int i=0;i<nt&&tr!=0;i++){int o=tr+20*i;tris[3*i]=rl(o);tris[3*i+1]=rl(o+4);tris[3*i+2]=rl(o+8);flags[i]=rl(o+12);ta[i]=aset(rl(o+16));}
				Q3.releaseAll(t.v);Q3.releaseAll(t.edgeAttrs);Q3.releaseAll(t.triangleAttrs);
				t.v=v;t.edges=edges;t.edgeAttrs=ea;t.triangles=tris;t.edgeFlags=flags;t.triangleAttrs=ta;Q3.AttrSet s=aset(rl(p+24));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.Polyhedron t=(Q3.Polyhedron)g;wl(p,t.v.length);wl(p+4,putVertices(t.v));
				int ne=t.edges.length/4,nt=t.triangles.length/3;
				int e=ne==0?0:alloc(20*ne);for(int i=0;i<ne;i++){int o=e+20*i;for(int k=0;k<4;k++)wl(o+4*k,t.edges[4*i+k]);wl(o+16,setOut(t.edgeAttrs[i]));}
				int tr=alloc(20*Math.max(1,nt));for(int i=0;i<nt;i++){int o=tr+20*i;for(int k=0;k<3;k++)wl(o+4*k,t.triangles[3*i+k]);wl(o+12,t.edgeFlags[i]);wl(o+16,setOut(t.triangleAttrs[i]));}
				wl(p+8,ne);wl(p+12,e);wl(p+16,nt);wl(p+20,tr);wl(p+24,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {
				emptyVertices(rl(p+4),rl(p));int ne=rl(p+8),e=rl(p+12),nt=rl(p+16),tr=rl(p+20);
				if(e!=0){for(int i=0;i<ne;i++)releaseAt(e+20*i+16);free(e);}if(tr!=0){for(int i=0;i<nt;i++)releaseAt(tr+20*i+16);free(tr);}
				for(int o=0;o<24;o+=4)wl(p+o,0);releaseAt(p+24);}
		});
		vertexAccess("Polyhedron",Q3.POLYHEDRON,g->((Q3.Polyhedron)g).v);
		q.def("Q3Polyhedron_GetTriangleData",a->{Q3.Polyhedron t=geo(a.i(0),Q3.POLYHEDRON,Q3.Polyhedron.class);int i=a.i(1),o=a.i(2);if(t==null||o==0||i<0||i>=t.triangles.length/3){a.ok(false);return;}
			for(int k=0;k<3;k++)wl(o+4*k,t.triangles[3*i+k]);wl(o+12,t.edgeFlags[i]);wl(o+16,setOut(t.triangleAttrs[i]));a.ok(true);});
		q.def("Q3Polyhedron_SetTriangleData",a->{Q3.Polyhedron t=geo(a.i(0),Q3.POLYHEDRON,Q3.Polyhedron.class);int i=a.i(1),o=a.i(2);if(t==null||o==0||i<0||i>=t.triangles.length/3){a.ok(false);return;}
			for(int k=0;k<3;k++)t.triangles[3*i+k]=rl(o+4*k);t.edgeFlags[i]=rl(o+12);Q3.AttrSet s=aset(rl(o+16));Q3.release(t.triangleAttrs[i]);t.triangleAttrs[i]=s;t.edited();a.ok(true);});
		q.def("Q3Polyhedron_GetEdgeData",a->{Q3.Polyhedron t=geo(a.i(0),Q3.POLYHEDRON,Q3.Polyhedron.class);int i=a.i(1),o=a.i(2);if(t==null||o==0||i<0||i>=t.edges.length/4){a.ok(false);return;}
			for(int k=0;k<4;k++)wl(o+4*k,t.edges[4*i+k]);wl(o+16,setOut(t.edgeAttrs[i]));a.ok(true);});
		q.def("Q3Polyhedron_SetEdgeData",a->{Q3.Polyhedron t=geo(a.i(0),Q3.POLYHEDRON,Q3.Polyhedron.class);int i=a.i(1),o=a.i(2);if(t==null||o==0||i<0||i>=t.edges.length/4){a.ok(false);return;}
			for(int k=0;k<4;k++)t.edges[4*i+k]=rl(o+4*k);Q3.AttrSet s=aset(rl(o+16));Q3.release(t.edgeAttrs[i]);t.edgeAttrs[i]=s;t.edited();a.ok(true);});
	}
	private Q3.Vertex gridV(Q3.TriGrid t,int r,int c){if(t==null||r<0||c<0||r>=t.rows||c>=t.columns){if(t!=null)q.post(PpcQuickDraw3D.ERR_PARAMETER_OUT_OF_RANGE);return null;}return t.v[r*t.columns+c];}

	// ================================================================== NURBS

	private void defineNurbs(){
		kind("NURBCurve",Q3.NURB_CURVE,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.NURBCurve t=new Q3.NURBCurve();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {Q3.NURBCurve t=(Q3.NURBCurve)g;t.order=rl(p);int n=rl(p+4);t.points=floats(rl(p+8),4*n);t.knots=floats(rl(p+12),n+t.order);
				Q3.AttrSet s=aset(rl(p+16));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {Q3.NURBCurve t=(Q3.NURBCurve)g;wl(p,t.order);wl(p+4,t.points.length/4);
				int c=alloc(4*Math.max(1,t.points.length));putFloats(c,t.points,t.points.length);wl(p+8,c);int k=alloc(4*Math.max(1,t.knots.length));putFloats(k,t.knots,t.knots.length);wl(p+12,k);wl(p+16,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {free(rl(p+8));free(rl(p+12));wl(p+8,0);wl(p+12,0);releaseAt(p+16);}
		});
		q.def("Q3NURBCurve_SetControlPoint",a->{Q3.NURBCurve t=geo(a.i(0),Q3.NURB_CURVE,Q3.NURBCurve.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||4*i>=t.points.length){a.ok(false);return;}
			float[] v=floats(a.i(2),4);System.arraycopy(v,0,t.points,4*i,4);t.edited();a.ok(true);});
		q.def("Q3NURBCurve_GetControlPoint",a->{Q3.NURBCurve t=geo(a.i(0),Q3.NURB_CURVE,Q3.NURBCurve.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||4*i>=t.points.length){a.ok(false);return;}
			for(int k=0;k<4;k++)wf(a.i(2)+4*k,t.points[4*i+k]);a.ok(true);});
		q.def("Q3NURBCurve_SetKnot",a->{Q3.NURBCurve t=geo(a.i(0),Q3.NURB_CURVE,Q3.NURBCurve.class);int i=a.i(1);if(t==null||i<0||i>=t.knots.length){a.ok(false);return;}t.knots[i]=a.f(2);t.edited();a.ok(true);});
		q.def("Q3NURBCurve_GetKnot",a->{Q3.NURBCurve t=geo(a.i(0),Q3.NURB_CURVE,Q3.NURBCurve.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||i>=t.knots.length){a.ok(false);return;}wf(a.i(2),t.knots[i]);a.ok(true);});
		kind("NURBPatch",Q3.NURB_PATCH,new Kind(){
			public Q3.Geometry read(int p) throws M68kCpuException {Q3.NURBPatch t=new Q3.NURBPatch();set(t,p);return t;}
			public void set(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.NURBPatch t=(Q3.NURBPatch)g;t.uOrder=rl(p);t.vOrder=rl(p+4);t.rows=rl(p+8);t.columns=rl(p+12);
				t.points=floats(rl(p+16),4*t.rows*t.columns);t.uKnots=floats(rl(p+20),t.columns+t.uOrder);t.vKnots=floats(rl(p+24),t.rows+t.vOrder);
				int nl=rl(p+28),loops=rl(p+32);t.trimLoops=new Q3.TrimCurve[Math.max(0,nl)][];
				for(int i=0;i<nl&&loops!=0;i++){int nc=rl(loops+8*i),cs=rl(loops+8*i+4);t.trimLoops[i]=new Q3.TrimCurve[Math.max(0,nc)];
					for(int k=0;k<nc;k++){int c=cs+16*k;Q3.TrimCurve tc=new Q3.TrimCurve();tc.order=rl(c);int np=rl(c+4);tc.points=floats(rl(c+8),3*np);tc.knots=floats(rl(c+12),np+tc.order);t.trimLoops[i][k]=tc;}}
				Q3.AttrSet s=aset(rl(p+36));Q3.release(t.attrs);t.attrs=s;}
			public void write(Q3.Geometry g,int p) throws M68kCpuException {
				Q3.NURBPatch t=(Q3.NURBPatch)g;wl(p,t.uOrder);wl(p+4,t.vOrder);wl(p+8,t.rows);wl(p+12,t.columns);
				int c=alloc(4*Math.max(1,t.points.length));putFloats(c,t.points,t.points.length);wl(p+16,c);
				int u=alloc(4*Math.max(1,t.uKnots.length));putFloats(u,t.uKnots,t.uKnots.length);wl(p+20,u);int v=alloc(4*Math.max(1,t.vKnots.length));putFloats(v,t.vKnots,t.vKnots.length);wl(p+24,v);
				wl(p+28,t.trimLoops.length);
				if(t.trimLoops.length==0)wl(p+32,0);else{int loops=alloc(8*t.trimLoops.length);
					for(int i=0;i<t.trimLoops.length;i++){Q3.TrimCurve[] cs=t.trimLoops[i];int arr=alloc(16*Math.max(1,cs.length));
						for(int k=0;k<cs.length;k++){int o=arr+16*k;wl(o,cs[k].order);wl(o+4,cs[k].points.length/3);int pp=alloc(4*Math.max(1,cs[k].points.length));putFloats(pp,cs[k].points,cs[k].points.length);wl(o+8,pp);
							int kk=alloc(4*Math.max(1,cs[k].knots.length));putFloats(kk,cs[k].knots,cs[k].knots.length);wl(o+12,kk);}
						wl(loops+8*i,cs.length);wl(loops+8*i+4,arr);}
					wl(p+32,loops);}
				wl(p+36,setOut(t.attrs));}
			public void empty(int p) throws M68kCpuException {
				free(rl(p+16));free(rl(p+20));free(rl(p+24));int nl=rl(p+28),loops=rl(p+32);
				if(loops!=0){for(int i=0;i<nl;i++){int nc=rl(loops+8*i),cs=rl(loops+8*i+4);for(int k=0;k<nc;k++){free(rl(cs+16*k+8));free(rl(cs+16*k+12));}free(cs);}free(loops);}
				for(int o=16;o<36;o+=4)wl(p+o,0);releaseAt(p+36);}
		});
		q.def("Q3NURBPatch_SetControlPoint",a->{Q3.NURBPatch t=geo(a.i(0),Q3.NURB_PATCH,Q3.NURBPatch.class);int r=a.i(1),c=a.i(2);if(t==null||a.i(3)==0||r<0||c<0||r>=t.rows||c>=t.columns){a.ok(false);return;}
			float[] v=floats(a.i(3),4);System.arraycopy(v,0,t.points,4*(r*t.columns+c),4);t.edited();a.ok(true);});
		q.def("Q3NURBPatch_GetControlPoint",a->{Q3.NURBPatch t=geo(a.i(0),Q3.NURB_PATCH,Q3.NURBPatch.class);int r=a.i(1),c=a.i(2);if(t==null||a.i(3)==0||r<0||c<0||r>=t.rows||c>=t.columns){a.ok(false);return;}
			for(int k=0;k<4;k++)wf(a.i(3)+4*k,t.points[4*(r*t.columns+c)+k]);a.ok(true);});
		q.def("Q3NURBPatch_SetUKnot",a->{Q3.NURBPatch t=geo(a.i(0),Q3.NURB_PATCH,Q3.NURBPatch.class);int i=a.i(1);if(t==null||i<0||i>=t.uKnots.length){a.ok(false);return;}t.uKnots[i]=a.f(2);t.edited();a.ok(true);});
		q.def("Q3NURBPatch_SetVKnot",a->{Q3.NURBPatch t=geo(a.i(0),Q3.NURB_PATCH,Q3.NURBPatch.class);int i=a.i(1);if(t==null||i<0||i>=t.vKnots.length){a.ok(false);return;}t.vKnots[i]=a.f(2);t.edited();a.ok(true);});
		q.def("Q3NURBPatch_GetUKnot",a->{Q3.NURBPatch t=geo(a.i(0),Q3.NURB_PATCH,Q3.NURBPatch.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||i>=t.uKnots.length){a.ok(false);return;}wf(a.i(2),t.uKnots[i]);a.ok(true);});
		q.def("Q3NURBPatch_GetVKnot",a->{Q3.NURBPatch t=geo(a.i(0),Q3.NURB_PATCH,Q3.NURBPatch.class);int i=a.i(1);if(t==null||a.i(2)==0||i<0||i>=t.vKnots.length){a.ok(false);return;}wf(a.i(2),t.vKnots[i]);a.ok(true);});
	}

	// ================================================================== Mesh

	private int part(Object o) throws M68kCpuException {
		if(o==null)return 0;
		if(o instanceof Q3.Mesh.MVertex){Q3.Mesh.MVertex v=(Q3.Mesh.MVertex)o;return v.handle=q.handleOf(v,v.handle);}
		if(o instanceof Q3.Mesh.MFace){Q3.Mesh.MFace v=(Q3.Mesh.MFace)o;return v.handle=q.handleOf(v,v.handle);}
		if(o instanceof Q3.Mesh.MContour){Q3.Mesh.MContour v=(Q3.Mesh.MContour)o;return v.handle=q.handleOf(v,v.handle);}
		if(o instanceof Q3.Mesh.MEdge){Q3.Mesh.MEdge v=(Q3.Mesh.MEdge)o;return v.handle=q.handleOf(v,v.handle);}
		if(o instanceof Q3.Mesh.MComponent){Q3.Mesh.MComponent v=(Q3.Mesh.MComponent)o;return v.handle=q.handleOf(v,v.handle);}
		return 0;
	}
	private <T> T part(int h,Class<T> c){Object o=q.thing(h);return c.isInstance(o)?c.cast(o):null;}
	/** The mesh a part belongs to (parts are looked up through the meshes that made them). */
	private final Map<Object,Q3.Mesh> meshOf=new java.util.IdentityHashMap<Object,Q3.Mesh>();
	void dropMeshParts(Q3.Mesh m){
		for(Q3.Mesh.MVertex v:m.vertices){q.dropHandle(v.handle);meshOf.remove(v);}
		for(Q3.Mesh.MFace f:m.faces){q.dropHandle(f.handle);meshOf.remove(f);for(Q3.Mesh.MContour c:f.contours){q.dropHandle(c.handle);meshOf.remove(c);}}
		for(Q3.Mesh.MEdge e:m.edges.values())q.dropHandle(e.handle);
		for(Q3.Mesh.MComponent c:m.components)q.dropHandle(c.handle);
	}
	/** Iterators: the parts to walk, by the iterator's address (TQ3MeshIterator: var1 = the list, var2 = the index). */
	private final Map<Integer,List<Object>> iterations=new HashMap<Integer,List<Object>>();
	private int first(int it,List<Object> parts) throws M68kCpuException {
		if(it==0)return 0;iterations.put(it,parts);wl(it+4,0);
		return next(it);
	}
	private int next(int it) throws M68kCpuException {
		List<Object> l=iterations.get(it);if(l==null)return 0;
		int i=rl(it+4);if(i<0||i>=l.size()){iterations.remove(it);return 0;}
		wl(it+4,i+1);return part(l.get(i));
	}
	private List<Object> list(Iterable<?> it){List<Object> l=new ArrayList<Object>();for(Object o:it)l.add(o);return l;}

	private void defineMesh(){
		q.def("Q3Mesh_New",a->a.ret(q.made(new Q3.Mesh())));
		q.def("Q3Mesh_VertexNew",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);int p=a.i(1);if(m==null||p==0){a.ret(0);return;}
			Q3.Mesh.MVertex v=new Q3.Mesh.MVertex();v.x=rf(p);v.y=rf(p+4);v.z=rf(p+8);v.attrs=aset(rl(p+12));v.id=m.nextId++;m.vertices.add(v);meshOf.put(v,m);m.edited();a.ret(part(v));});
		q.def("Q3Mesh_FaceNew",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);int n=a.i(1),vs=a.i(2);if(m==null||n<3||vs==0){a.ret(0);return;}
			Q3.Mesh.MFace f=new Q3.Mesh.MFace();Q3.Mesh.MContour c=new Q3.Mesh.MContour();c.face=f;
			for(int i=0;i<n;i++){Q3.Mesh.MVertex v=part(rl(vs+4*i),Q3.Mesh.MVertex.class);if(v==null){a.ret(0);return;}c.vertices.add(v);}
			f.contours.add(c);f.attrs=aset(a.i(3));m.faces.add(f);meshOf.put(f,m);meshOf.put(c,m);m.edited();a.ret(part(f));});
		q.def("Q3Mesh_VertexDelete",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(m==null||v==null){a.ok(false);return;}
			for(int i=m.faces.size()-1;i>=0;i--){Q3.Mesh.MFace f=m.faces.get(i);boolean uses=false;for(Q3.Mesh.MContour c:f.contours)if(c.vertices.contains(v))uses=true;if(uses)deleteFace(m,f);}
			m.vertices.remove(v);Q3.release(v.attrs);q.dropHandle(v.handle);meshOf.remove(v);m.edited();a.ok(true);});
		q.def("Q3Mesh_FaceDelete",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(m==null||f==null){a.ok(false);return;}deleteFace(m,f);m.edited();a.ok(true);});
		q.def("Q3Mesh_DelayUpdates",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m!=null)m.delayed++;a.ok(m!=null);});
		q.def("Q3Mesh_ResumeUpdates",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m!=null&&m.delayed>0)m.delayed--;if(m!=null)m.edited();a.ok(m!=null);});
		q.def("Q3Mesh_FaceToContour",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MFace into=part(a.i(1),Q3.Mesh.MFace.class),f=part(a.i(2),Q3.Mesh.MFace.class);
			if(m==null||into==null||f==null||into==f){a.ret(0);return;}
			Q3.Mesh.MContour first=null;for(Q3.Mesh.MContour c:f.contours){c.face=into;into.contours.add(c);if(first==null)first=c;}
			f.contours.clear();m.faces.remove(f);Q3.release(f.attrs);q.dropHandle(f.handle);meshOf.remove(f);m.edited();a.ret(part(first));});
		q.def("Q3Mesh_ContourToFace",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MContour c=part(a.i(1),Q3.Mesh.MContour.class);if(m==null||c==null||c.face==null){a.ret(0);return;}
			c.face.contours.remove(c);Q3.Mesh.MFace f=new Q3.Mesh.MFace();c.face=f;f.contours.add(c);m.faces.add(f);meshOf.put(f,m);m.edited();a.ret(part(f));});
		q.def("Q3Mesh_GetNumComponents",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m==null||a.i(1)==0){a.ok(false);return;}m.topology();wl(a.i(1),m.components.size());a.ok(true);});
		q.def("Q3Mesh_GetNumEdges",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m==null||a.i(1)==0){a.ok(false);return;}m.topology();wl(a.i(1),m.edges.size());a.ok(true);});
		q.def("Q3Mesh_GetNumVertices",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),m.vertices.size());a.ok(true);});
		q.def("Q3Mesh_GetNumFaces",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),m.faces.size());a.ok(true);});
		q.def("Q3Mesh_GetNumCorners",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m==null||a.i(1)==0){a.ok(false);return;}int n=0;for(Q3.Mesh.MFace f:m.faces)n+=f.corners.size();wl(a.i(1),n);a.ok(true);});
		q.def("Q3Mesh_GetOrientable",a->{if(a.i(1)!=0)wl(a.i(1),1);a.ok(a.i(1)!=0);});
		q.def("Q3Mesh_GetComponentNumVertices",a->{Q3.Mesh.MComponent c=part(a.i(1),Q3.Mesh.MComponent.class);if(c==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),c.vertices.size());a.ok(true);});
		q.def("Q3Mesh_GetComponentNumEdges",a->{Q3.Mesh.MComponent c=part(a.i(1),Q3.Mesh.MComponent.class);if(c==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),c.edges.size());a.ok(true);});
		q.def("Q3Mesh_GetComponentBoundingBox",a->{Q3.Mesh.MComponent c=part(a.i(1),Q3.Mesh.MComponent.class);int b=a.i(2);if(c==null||b==0){a.ok(false);return;}
			float[] mn={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},mx={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
			for(Q3.Mesh.MVertex v:c.vertices){float[] p={v.x,v.y,v.z};for(int k=0;k<3;k++){mn[k]=Math.min(mn[k],p[k]);mx[k]=Math.max(mx[k],p[k]);}}
			wp(b,mn);wp(b+12,mx);wl(b+24,c.vertices.isEmpty()?1:0);a.ok(true);});
		q.def("Q3Mesh_GetComponentOrientable",a->{if(a.i(2)!=0)wl(a.i(2),1);a.ok(a.i(2)!=0);});
		q.def("Q3Mesh_GetVertexCoordinates",a->{Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(v==null||a.i(2)==0){a.ok(false);return;}wp(a.i(2),new float[]{v.x,v.y,v.z});a.ok(true);});
		q.def("Q3Mesh_GetVertexIndex",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(m==null||v==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),m.vertices.indexOf(v));a.ok(true);});
		q.def("Q3Mesh_GetVertexOnBoundary",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(m==null||v==null||a.i(2)==0){a.ok(false);return;}
			boolean b=false;for(Q3.Mesh.MEdge e:edgesOf(m))if((e.a==v||e.b==v)&&facesOf(m,e).size()<2)b=true;wl(a.i(2),b?1:0);a.ok(true);});
		q.def("Q3Mesh_GetVertexComponent",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(m==null||v==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),part(m.componentOf(v)));a.ok(true);});
		q.def("Q3Mesh_GetVertexAttributeSet",a->{Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(v==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),setOut(v.attrs));a.ok(true);});
		q.def("Q3Mesh_SetVertexCoordinates",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(m==null||v==null||a.i(2)==0){a.ok(false);return;}
			v.x=rf(a.i(2));v.y=rf(a.i(2)+4);v.z=rf(a.i(2)+8);m.edited();a.ok(true);});
		q.def("Q3Mesh_SetVertexAttributeSet",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);if(m==null||v==null){a.ok(false);return;}
			Q3.AttrSet s=aset(a.i(2));Q3.release(v.attrs);v.attrs=s;m.edited();a.ok(true);});
		q.def("Q3Mesh_GetFaceNumVertices",a->{Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(f==null||a.i(2)==0){a.ok(false);return;}int n=0;for(Q3.Mesh.MContour c:f.contours)n+=c.vertices.size();wl(a.i(2),n);a.ok(true);});
		q.def("Q3Mesh_GetFacePlaneEquation",a->{Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);int p=a.i(2);if(f==null||p==0||f.contours.isEmpty()){a.ok(false);return;}
			List<Q3.Mesh.MVertex> vs=f.contours.get(0).vertices;double nx=0,ny=0,nz=0;
			for(int i=0;i<vs.size();i++){Q3.Mesh.MVertex x=vs.get(i),y=vs.get((i+1)%vs.size());nx+=(x.y-y.y)*(x.z+y.z);ny+=(x.z-y.z)*(x.x+y.x);nz+=(x.x-y.x)*(x.y+y.y);}
			double l=Math.sqrt(nx*nx+ny*ny+nz*nz);if(l>0){nx/=l;ny/=l;nz/=l;}Q3.Mesh.MVertex v0=vs.get(0);
			wp(p,new float[]{(float)nx,(float)ny,(float)nz});wf(p+12,(float)-(nx*v0.x+ny*v0.y+nz*v0.z));a.ok(true);});
		q.def("Q3Mesh_GetFaceNumContours",a->{Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(f==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),f.contours.size());a.ok(true);});
		q.def("Q3Mesh_GetFaceIndex",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(m==null||f==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),m.faces.indexOf(f));a.ok(true);});
		q.def("Q3Mesh_GetFaceComponent",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(m==null||f==null||a.i(2)==0||f.contours.isEmpty()){a.ok(false);return;}
			wl(a.i(2),part(m.componentOf(f.contours.get(0).vertices.get(0))));a.ok(true);});
		q.def("Q3Mesh_GetFaceAttributeSet",a->{Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(f==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),setOut(f.attrs));a.ok(true);});
		q.def("Q3Mesh_SetFaceAttributeSet",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MFace f=part(a.i(1),Q3.Mesh.MFace.class);if(m==null||f==null){a.ok(false);return;}Q3.AttrSet s=aset(a.i(2));Q3.release(f.attrs);f.attrs=s;m.edited();a.ok(true);});
		q.def("Q3Mesh_GetEdgeVertices",a->{Q3.Mesh.MEdge e=part(a.i(1),Q3.Mesh.MEdge.class);if(e==null){a.ok(false);return;}if(a.i(2)!=0)wl(a.i(2),part(e.a));if(a.i(3)!=0)wl(a.i(3),part(e.b));a.ok(true);});
		q.def("Q3Mesh_GetEdgeFaces",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MEdge e=part(a.i(1),Q3.Mesh.MEdge.class);if(m==null||e==null){a.ok(false);return;}
			List<Q3.Mesh.MFace> f=facesOf(m,e);if(a.i(2)!=0)wl(a.i(2),f.size()>0?part(f.get(0)):0);if(a.i(3)!=0)wl(a.i(3),f.size()>1?part(f.get(1)):0);a.ok(true);});
		q.def("Q3Mesh_GetEdgeOnBoundary",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MEdge e=part(a.i(1),Q3.Mesh.MEdge.class);if(m==null||e==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),facesOf(m,e).size()<2?1:0);a.ok(true);});
		q.def("Q3Mesh_GetEdgeComponent",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MEdge e=part(a.i(1),Q3.Mesh.MEdge.class);if(m==null||e==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),part(m.componentOf(e.a)));a.ok(true);});
		q.def("Q3Mesh_GetEdgeAttributeSet",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MEdge e=part(a.i(1),Q3.Mesh.MEdge.class);if(m==null||e==null||a.i(2)==0){a.ok(false);return;}
			wl(a.i(2),setOut(m.edgeAttrs.get(Q3.Mesh.key(e.a,e.b))));a.ok(true);});
		q.def("Q3Mesh_SetEdgeAttributeSet",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MEdge e=part(a.i(1),Q3.Mesh.MEdge.class);if(m==null||e==null){a.ok(false);return;}
			Q3.AttrSet s=aset(a.i(2));Q3.AttrSet old=s==null?m.edgeAttrs.remove(Q3.Mesh.key(e.a,e.b)):m.edgeAttrs.put(Q3.Mesh.key(e.a,e.b),s);Q3.release(old);m.edited();a.ok(true);});
		q.def("Q3Mesh_GetContourFace",a->{Q3.Mesh.MContour c=part(a.i(1),Q3.Mesh.MContour.class);if(c==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),part(c.face));a.ok(true);});
		q.def("Q3Mesh_GetContourNumVertices",a->{Q3.Mesh.MContour c=part(a.i(1),Q3.Mesh.MContour.class);if(c==null||a.i(2)==0){a.ok(false);return;}wl(a.i(2),c.vertices.size());a.ok(true);});
		q.def("Q3Mesh_GetCornerAttributeSet",a->{Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);Q3.Mesh.MFace f=part(a.i(2),Q3.Mesh.MFace.class);if(v==null||f==null||a.i(3)==0){a.ok(false);return;}
			wl(a.i(3),setOut(f.corners.get(v)));a.ok(true);});
		q.def("Q3Mesh_SetCornerAttributeSet",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);Q3.Mesh.MVertex v=part(a.i(1),Q3.Mesh.MVertex.class);Q3.Mesh.MFace f=part(a.i(2),Q3.Mesh.MFace.class);
			if(m==null||v==null||f==null){a.ok(false);return;}Q3.AttrSet s=aset(a.i(3));Q3.AttrSet old=s==null?f.corners.remove(v):f.corners.put(v,s);Q3.release(old);m.edited();a.ok(true);});
		// iterators
		q.def("Q3Mesh_FirstMeshComponent",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);if(m==null){a.ret(0);return;}m.topology();a.ret(first(a.i(1),list(m.components)));});
		q.def("Q3Mesh_FirstComponentVertex",a->{Q3.Mesh.MComponent c=part(a.i(0),Q3.Mesh.MComponent.class);a.ret(c==null?0:first(a.i(1),list(c.vertices)));});
		q.def("Q3Mesh_FirstComponentEdge",a->{Q3.Mesh.MComponent c=part(a.i(0),Q3.Mesh.MComponent.class);a.ret(c==null?0:first(a.i(1),list(c.edges)));});
		q.def("Q3Mesh_FirstMeshVertex",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);a.ret(m==null?0:first(a.i(1),list(m.vertices)));});
		q.def("Q3Mesh_FirstMeshFace",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);a.ret(m==null?0:first(a.i(1),list(m.faces)));});
		q.def("Q3Mesh_FirstMeshEdge",a->{Q3.Mesh m=geo(a.i(0),Q3.MESH,Q3.Mesh.class);a.ret(m==null?0:first(a.i(1),list(edgesOf(m))));});
		q.def("Q3Mesh_FirstVertexEdge",a->{Q3.Mesh.MVertex v=part(a.i(0),Q3.Mesh.MVertex.class);Q3.Mesh m=v==null?null:meshOf.get(v);if(m==null){a.ret(0);return;}
			List<Object> l=new ArrayList<Object>();for(Q3.Mesh.MEdge e:edgesOf(m))if(e.a==v||e.b==v)l.add(e);a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstVertexVertex",a->{Q3.Mesh.MVertex v=part(a.i(0),Q3.Mesh.MVertex.class);Q3.Mesh m=v==null?null:meshOf.get(v);if(m==null){a.ret(0);return;}
			List<Object> l=new ArrayList<Object>();for(Q3.Mesh.MEdge e:edgesOf(m)){if(e.a==v)l.add(e.b);else if(e.b==v)l.add(e.a);}a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstVertexFace",a->{Q3.Mesh.MVertex v=part(a.i(0),Q3.Mesh.MVertex.class);Q3.Mesh m=v==null?null:meshOf.get(v);if(m==null){a.ret(0);return;}
			List<Object> l=new ArrayList<Object>();for(Q3.Mesh.MFace f:m.faces)for(Q3.Mesh.MContour c:f.contours)if(c.vertices.contains(v)&&!l.contains(f))l.add(f);a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstFaceEdge",a->{Q3.Mesh.MFace f=part(a.i(0),Q3.Mesh.MFace.class);Q3.Mesh m=f==null?null:meshOf.get(f);if(m==null){a.ret(0);return;}
			m.topology();List<Object> l=new ArrayList<Object>();for(Q3.Mesh.MContour c:f.contours){int n=c.vertices.size();for(int i=0;i<n;i++){Q3.Mesh.MEdge e=m.edges.get(Q3.Mesh.key(c.vertices.get(i),c.vertices.get((i+1)%n)));if(e!=null)l.add(e);}}
			a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstFaceVertex",a->{Q3.Mesh.MFace f=part(a.i(0),Q3.Mesh.MFace.class);if(f==null){a.ret(0);return;}List<Object> l=new ArrayList<Object>();for(Q3.Mesh.MContour c:f.contours)l.addAll(c.vertices);a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstFaceFace",a->{Q3.Mesh.MFace f=part(a.i(0),Q3.Mesh.MFace.class);Q3.Mesh m=f==null?null:meshOf.get(f);if(m==null){a.ret(0);return;}
			m.topology();List<Object> l=new ArrayList<Object>();
			for(Q3.Mesh.MContour c:f.contours){int n=c.vertices.size();for(int i=0;i<n;i++){Q3.Mesh.MEdge e=m.edges.get(Q3.Mesh.key(c.vertices.get(i),c.vertices.get((i+1)%n)));if(e==null)continue;
				for(Q3.Mesh.MFace o:facesOf(m,e))if(o!=f&&!l.contains(o))l.add(o);}}
			a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstFaceContour",a->{Q3.Mesh.MFace f=part(a.i(0),Q3.Mesh.MFace.class);a.ret(f==null?0:first(a.i(1),list(f.contours)));});
		q.def("Q3Mesh_FirstContourEdge",a->{Q3.Mesh.MContour c=part(a.i(0),Q3.Mesh.MContour.class);Q3.Mesh m=c==null?null:meshOf.get(c);if(m==null){a.ret(0);return;}
			m.topology();List<Object> l=new ArrayList<Object>();int n=c.vertices.size();for(int i=0;i<n;i++){Q3.Mesh.MEdge e=m.edges.get(Q3.Mesh.key(c.vertices.get(i),c.vertices.get((i+1)%n)));if(e!=null)l.add(e);}
			a.ret(first(a.i(1),l));});
		q.def("Q3Mesh_FirstContourVertex",a->{Q3.Mesh.MContour c=part(a.i(0),Q3.Mesh.MContour.class);a.ret(c==null?0:first(a.i(1),list(c.vertices)));});
		q.def("Q3Mesh_FirstContourFace",a->{Q3.Mesh.MContour c=part(a.i(0),Q3.Mesh.MContour.class);Q3.Mesh m=c==null?null:meshOf.get(c);if(m==null){a.ret(0);return;}
			m.topology();List<Object> l=new ArrayList<Object>();int n=c.vertices.size();
			for(int i=0;i<n;i++){Q3.Mesh.MEdge e=m.edges.get(Q3.Mesh.key(c.vertices.get(i),c.vertices.get((i+1)%n)));if(e==null)continue;for(Q3.Mesh.MFace o:facesOf(m,e))if(o!=c.face&&!l.contains(o))l.add(o);}
			a.ret(first(a.i(1),l));});
		for(String n:new String[]{"MeshComponent","ComponentVertex","ComponentEdge","MeshVertex","MeshFace","MeshEdge","VertexEdge","VertexVertex","VertexFace",
			"FaceEdge","FaceVertex","FaceFace","FaceContour","ContourEdge","ContourVertex","ContourFace"})
			q.def("Q3Mesh_Next"+n,a->a.ret(next(a.i(0))));
	}
	private void deleteFace(Q3.Mesh m,Q3.Mesh.MFace f){
		m.faces.remove(f);Q3.release(f.attrs);for(Q3.AttrSet s:f.corners.values())Q3.release(s);f.corners.clear();
		q.dropHandle(f.handle);meshOf.remove(f);for(Q3.Mesh.MContour c:f.contours){q.dropHandle(c.handle);meshOf.remove(c);}
	}
	private java.util.Collection<Q3.Mesh.MEdge> edgesOf(Q3.Mesh m){m.topology();return m.edges.values();}
	private List<Q3.Mesh.MFace> facesOf(Q3.Mesh m,Q3.Mesh.MEdge e){
		List<Q3.Mesh.MFace> l=new ArrayList<Q3.Mesh.MFace>();
		for(Q3.Mesh.MFace f:m.faces)for(Q3.Mesh.MContour c:f.contours){int n=c.vertices.size();for(int i=0;i<n;i++){Q3.Mesh.MVertex a=c.vertices.get(i),b=c.vertices.get((i+1)%n);
			if((a==e.a&&b==e.b||a==e.b&&b==e.a)&&!l.contains(f))l.add(f);}}
		return l;
	}
}

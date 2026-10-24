import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QuickDraw 3D's objects in Java, for {@link PpcQuickDraw3D}: the class hierarchy (object types as QD3D.h names them),
 * reference counting of shared objects, sets and attribute sets, groups and their positions, the geometries with their
 * public data, styles, transforms, shaders, textures, lights, cameras, renderers, draw contexts, storage and strings -
 * and the matrix arithmetic of QD3DMath.h (row vectors: a point is transformed as p * M).
 * <p>
 * Nothing here touches guest memory: {@link PpcQuickDraw3D} converts between these objects and the structures of the
 * Universal Interfaces 3.3 headers, {@link Q3View} draws them and {@link Q3Metafile} reads and writes them as 3DMF.
 */
final class Q3 {
	private Q3(){}

	static int fc(String s){return (s.charAt(0)&0xff)<<24|(s.charAt(1)&0xff)<<16|(s.charAt(2)&0xff)<<8|s.charAt(3)&0xff;}
	static String fcName(int t){return new String(new char[]{(char)(t>>>24&0xff),(char)(t>>>16&0xff),(char)(t>>>8&0xff),(char)(t&0xff)});}

	// ================================================================== object types (QD3D.h)

	static final int VIEW=fc("view"),ELEMENT=fc("elmn"),ATTRIBUTE_ELEMENT=fc("eatt"),PICK=fc("pick"),PICK_WINDOW_POINT=fc("pkwp"),PICK_WINDOW_RECT=fc("pkwr"),PICK_WORLD_RAY=fc("pkry"),
		SHARED=fc("shrd"),RENDERER=fc("rddr"),WIREFRAME=fc("wrfr"),GENERIC=fc("gnrr"),INTERACTIVE=fc("ctwn"),
		SHAPE=fc("shap"),GEOMETRY=fc("gmtr"),BOX=fc("box "),GENERAL_POLYGON=fc("gpgn"),LINE=fc("line"),MARKER=fc("mrkr"),PIXMAP_MARKER=fc("mrkp"),
		MESH=fc("mesh"),NURB_CURVE=fc("nrbc"),NURB_PATCH=fc("nrbp"),POINT=fc("pnt "),POLYGON=fc("plyg"),POLYLINE=fc("plyl"),TRIANGLE=fc("trng"),
		TRIGRID=fc("trig"),CONE=fc("cone"),CYLINDER=fc("cyln"),DISK=fc("disk"),ELLIPSE=fc("elps"),ELLIPSOID=fc("elpd"),POLYHEDRON=fc("plhd"),
		TORUS=fc("tors"),TRIMESH=fc("tmsh"),
		SHADER=fc("shdr"),SURFACE_SHADER=fc("sush"),TEXTURE_SHADER=fc("txsu"),ILLUMINATION=fc("ilsh"),PHONG=fc("phil"),LAMBERT=fc("lmil"),NULL_ILLUMINATION=fc("nuil"),
		STYLE=fc("styl"),BACKFACING=fc("bckf"),INTERPOLATION=fc("intp"),FILL=fc("fist"),PICK_ID=fc("pkid"),RECEIVE_SHADOWS=fc("rcsh"),HIGHLIGHT=fc("high"),
		SUBDIVISION=fc("sbdv"),ORIENTATION=fc("ofdr"),PICK_PARTS=fc("pkpt"),ANTIALIAS=fc("anti"),FOG=fc("fogg"),
		TRANSFORM=fc("xfrm"),MATRIX=fc("mtrx"),SCALE=fc("scal"),TRANSLATE=fc("trns"),ROTATE=fc("rott"),ROTATE_ABOUT_POINT=fc("rtap"),ROTATE_ABOUT_AXIS=fc("rtaa"),
		QUATERNION=fc("qtrn"),RESET=fc("rset"),
		LIGHT=fc("lght"),AMBIENT=fc("ambn"),DIRECTIONAL=fc("drct"),POINT_LIGHT=fc("pntl"),SPOT=fc("spot"),
		CAMERA=fc("cmra"),ORTHOGRAPHIC=fc("orth"),VIEW_PLANE=fc("vwpl"),VIEW_ANGLE_ASPECT=fc("vana"),
		STATE_OPERATOR=fc("stop"),PUSH=fc("push"),POP=fc("pop "),
		GROUP=fc("grup"),DISPLAY_GROUP=fc("dspg"),ORDERED_DISPLAY_GROUP=fc("ordg"),IO_PROXY=fc("iopx"),LIGHT_GROUP=fc("lghg"),INFO_GROUP=fc("info"),
		UNKNOWN=fc("unkn"),UNKNOWN_TEXT=fc("uktx"),UNKNOWN_BINARY=fc("ukbn"),REFERENCE=fc("rfrn"),REFERENCE_EXTERNAL=fc("rfex"),
		SET=fc("set "),ATTRIBUTE_SET=fc("attr"),
		DRAW_CONTEXT=fc("dctx"),PIXMAP_DRAW_CONTEXT=fc("dpxp"),MAC_DRAW_CONTEXT=fc("dmac"),
		TEXTURE=fc("txtr"),PIXMAP_TEXTURE=fc("txpm"),MIPMAP_TEXTURE=fc("txmm"),COMPRESSED_TEXTURE=fc("txcp"),
		FILE=fc("file"),STORAGE=fc("strg"),MEMORY_STORAGE=fc("mems"),HANDLE_STORAGE=fc("hndl"),MAC_STORAGE=fc("macn"),FSSPEC_STORAGE=fc("macp"),
		UNIX_STORAGE=fc("uxst"),PATH_STORAGE=fc("unix"),
		STRING=fc("strn"),C_STRING=fc("strc"),
		SHAPE_PART=fc("sprt"),MESH_PART=fc("spmh"),MESH_FACE_PART=fc("mfac"),MESH_EDGE_PART=fc("medg"),MESH_VERTEX_PART=fc("mvtx"),
		CONTROLLER_STATE=fc("ctst"),TRACKER=fc("trkr"),VIEW_HINTS=fc("vwhn"),END_GROUP=fc("endg");

	/** The class chain below TQ3Object of each leaf type (Q3Object_GetType answers the first, Q3Object_GetLeafType the last). */
	private static final Map<Integer,int[]> CHAINS=new java.util.HashMap<Integer,int[]>();
	/** Class names as QD3D registers them (Q3ObjectHierarchy_GetStringFromType, 3DMF text). */
	static final Map<Integer,String> NAMES=new LinkedHashMap<Integer,String>();
	static final Map<String,Integer> TYPES_BY_NAME=new java.util.HashMap<String,Integer>();
	private static void chain(String name,String... types){
		int[] c=new int[types.length];for(int i=0;i<c.length;i++)c[i]=fc(types[i]);
		CHAINS.put(c[c.length-1],c);
		if(name!=null){NAMES.put(c[c.length-1],name);TYPES_BY_NAME.put(name,c[c.length-1]);}
	}
	static{
		chain("View","view");chain("Element","elmn");chain("AttributeElement","elmn","eatt");chain("Pick","pick");
		chain("WindowPointPick","pick","pkwp");chain("WindowRectPick","pick","pkwr");chain("WorldRayPick","pick","pkry");
		chain("Shared","shrd");chain("Renderer","shrd","rddr");chain("WireFrame","shrd","rddr","wrfr");chain("Generic","shrd","rddr","gnrr");
		chain("InteractiveRenderer","shrd","rddr","ctwn");
		chain("Shape","shrd","shap");chain("Geometry","shrd","shap","gmtr");
		String[][] g={{"Box","box "},{"GeneralPolygon","gpgn"},{"Line","line"},{"Marker","mrkr"},{"PixmapMarker","mrkp"},{"Mesh","mesh"},
			{"NURBCurve","nrbc"},{"NURBPatch","nrbp"},{"Point","pnt "},{"Polygon","plyg"},{"PolyLine","plyl"},{"Triangle","trng"},{"TriGrid","trig"},
			{"Cone","cone"},{"Cylinder","cyln"},{"Disk","disk"},{"Ellipse","elps"},{"Ellipsoid","elpd"},{"Polyhedron","plhd"},{"Torus","tors"},{"TriMesh","tmsh"}};
		for(String[] e:g)chain(e[0],"shrd","shap","gmtr",e[1]);
		chain("Shader","shrd","shap","shdr");chain("SurfaceShader","shrd","shap","shdr","sush");chain("TextureShader","shrd","shap","shdr","sush","txsu");
		chain("IlluminationShader","shrd","shap","shdr","ilsh");chain("PhongIllumination","shrd","shap","shdr","ilsh","phil");
		chain("LambertIllumination","shrd","shap","shdr","ilsh","lmil");chain("NULLIllumination","shrd","shap","shdr","ilsh","nuil");
		chain("Style","shrd","shap","styl");
		String[][] st={{"Backfacing","bckf"},{"Interpolation","intp"},{"Fill","fist"},{"PickID","pkid"},{"ReceiveShadows","rcsh"},{"Highlight","high"},
			{"Subdivision","sbdv"},{"OrientationStyle","ofdr"},{"PickParts","pkpt"},{"AntiAlias","anti"},{"Fog","fogg"}};
		for(String[] e:st)chain(e[0],"shrd","shap","styl",e[1]);
		chain("Transform","shrd","shap","xfrm");
		String[][] tr={{"Matrix","mtrx"},{"Scale","scal"},{"Translate","trns"},{"Rotate","rott"},{"RotateAboutPoint","rtap"},{"RotateAboutAxis","rtaa"},
			{"Quaternion","qtrn"},{"Reset","rset"}};
		for(String[] e:tr)chain(e[0],"shrd","shap","xfrm",e[1]);
		chain("Light","shrd","shap","lght");
		String[][] li={{"AmbientLight","ambn"},{"DirectionalLight","drct"},{"PointLight","pntl"},{"SpotLight","spot"}};
		for(String[] e:li)chain(e[0],"shrd","shap","lght",e[1]);
		chain("Camera","shrd","shap","cmra");
		chain("OrthographicCamera","shrd","shap","cmra","orth");chain("ViewPlaneCamera","shrd","shap","cmra","vwpl");chain("ViewAngleAspectCamera","shrd","shap","cmra","vana");
		chain("StateOperator","shrd","shap","stop");chain("Push","shrd","shap","stop","push");chain("Pop","shrd","shap","stop","pop ");
		chain("Group","shrd","shap","grup");chain("DisplayGroup","shrd","shap","grup","dspg");chain("OrderedDisplayGroup","shrd","shap","grup","dspg","ordg");
		chain("IOProxyDisplayGroup","shrd","shap","grup","dspg","iopx");chain("LightGroup","shrd","shap","grup","lghg");chain("InfoGroup","shrd","shap","grup","info");
		chain("Unknown","shrd","shap","unkn");chain("UnknownText","shrd","shap","unkn","uktx");chain("UnknownBinary","shrd","shap","unkn","ukbn");
		chain("Reference","shrd","shap","rfrn");chain("ExternalReference","shrd","shap","rfrn","rfex");
		chain("Set","shrd","set ");chain("AttributeSet","shrd","set ","attr");
		chain("DrawContext","shrd","dctx");chain("PixmapDrawContext","shrd","dctx","dpxp");chain("MacintoshDrawContext","shrd","dctx","dmac");
		chain("Texture","shrd","txtr");chain("PixmapTexture","shrd","txtr","txpm");chain("MipmapTexture","shrd","txtr","txmm");chain("CompressedPixmapTexture","shrd","txtr","txcp");
		chain("File","shrd","file");
		chain("Storage","shrd","strg");chain("MemoryStorage","shrd","strg","mems");chain("HandleStorage","shrd","strg","mems","hndl");
		chain("MacintoshStorage","shrd","strg","macn");chain("FSSpecStorage","shrd","strg","macn","macp");
		chain("UnixStorage","shrd","strg","uxst");chain("UnixPathStorage","shrd","strg","uxst","unix");
		chain("String","shrd","strn");chain("CString","shrd","strn","strc");
		chain("ShapePart","shrd","sprt");chain("MeshPart","shrd","sprt","spmh");chain("MeshFacePart","shrd","sprt","spmh","mfac");
		chain("MeshEdgePart","shrd","sprt","spmh","medg");chain("MeshVertexPart","shrd","sprt","spmh","mvtx");
		chain("ControllerState","shrd","ctst");chain("Tracker","shrd","trkr");chain("ViewHints","shrd","vwhn");chain("EndGroup","shrd","endg");
	}
	static int[] chainOf(int leaf){return CHAINS.get(leaf);}
	/** Registers a class made by a plug-in or program (Q3XObjectHierarchy_RegisterClass): its chain is the parent's plus the new type. */
	static void registerClass(int parent,int type,String name){
		int[] p=parent==0?new int[0]:CHAINS.get(parent);if(p==null)p=new int[]{parent};
		int[] c=java.util.Arrays.copyOf(p,p.length+1);c[p.length]=type;CHAINS.put(type,c);
		if(name!=null){NAMES.put(type,name);TYPES_BY_NAME.put(name,type);}
	}

	// ================================================================== objects

	/** Where objects are known by the handles a program holds (the front end's table); told when one goes away. */
	interface Owner { void forget(Obj o); }

	/** A QuickDraw 3D object. */
	static class Obj {
		/** The class chain below TQ3Object, leaf last. */
		final int[] chain;
		/** The handle the program knows it by (0 until handed out), and the table that handed it out. */
		int handle;Owner owner;
		/** References held (shared objects; others are disposed at once). */
		int refs=1;
		/** Bumped by every change (Q3Shared_GetEditIndex; caches of tessellations and textures compare it). */
		int editIndex=1;
		/** Elements of the object (Q3Shape_AddElement, custom elements such as names), made when first needed. */
		ElementSet elements;
		Obj(int leaf){int[] c=CHAINS.get(leaf);chain=c!=null?c:new int[]{leaf};}
		final int leaf(){return chain[chain.length-1];}
		final int typeAt(int depth){return depth<chain.length?chain[depth]:0;}
		final boolean is(int type){for(int t:chain)if(t==type)return true;return false;}
		boolean shared(){return chain[0]==SHARED;}
		void edited(){editIndex++;}
		/** Releases the references this object holds (it is going away). */
		void dropRefs(){if(elements!=null)release(elements);}
		/** A copy for Q3Object_Duplicate (sub-objects duplicated or shared as QuickDraw 3D does), or null when not supported. */
		Obj duplicate(){return null;}
		/** Copies what every object has into a duplicate. */
		<T extends Obj> T base(T d){if(elements!=null)d.elements=(ElementSet)elements.duplicate();return d;}
		@Override public String toString(){return NAMES.getOrDefault(leaf(),fcName(leaf()));}
	}
	/** Takes a reference (shared objects). */
	static <T extends Obj> T ref(T o){if(o!=null)o.refs++;return o;}
	/** Gives up a reference: the object goes away with the last one (a non-shared object at once). */
	static void release(Obj o){
		if(o==null)return;
		if(o.shared()&&--o.refs>0)return;
		o.refs=0;
		if(o.owner!=null)o.owner.forget(o); // first: the front end still sees what the object holds (a group's positions)
		o.dropRefs();
	}
	/** Replaces a reference held in a field: the new one referenced, the old one released. */
	static <T extends Obj> T replace(T old,T now){if(now!=null)now.refs++;release(old);return now;}

	// ================================================================== sets and attributes

	static final int ATTR_NONE=0,ATTR_SURFACE_UV=1,ATTR_SHADING_UV=2,ATTR_NORMAL=3,ATTR_AMBIENT_COEFFICIENT=4,ATTR_DIFFUSE=5,ATTR_SPECULAR=6,
		ATTR_SPECULAR_CONTROL=7,ATTR_TRANSPARENCY=8,ATTR_SURFACE_TANGENT=9,ATTR_HIGHLIGHT_STATE=10,ATTR_SURFACE_SHADER=11,ATTR_NUM_TYPES=12;
	/** Floats in a standard attribute's value (0: not floats - highlight state is a switch, the surface shader an object). */
	static final int[] ATTR_FLOATS={0,2,2,3,1,3,3,1,3,6,0,0};
	/** Bytes of a standard attribute's value. */
	static final int[] ATTR_SIZE={0,8,8,12,4,12,12,4,12,24,4,4};
	static boolean isStandardAttribute(int t){return t>0&&t<ATTR_NUM_TYPES;}

	/** A custom element or attribute class (Q3XElementClass_Register / Q3XAttributeClass_Register): its size and methods. */
	static final class ElementClass {
		final int type;final String name;final int size;final int metaHandler;final boolean attribute;
		ElementClass(int type,String name,int size,int metaHandler,boolean attribute){this.type=type;this.name=name;this.size=size;this.metaHandler=metaHandler;this.attribute=attribute;}
	}

	/**
	 * A set (TQ3SetObject) or attribute set: elements by type. A standard attribute's value is a float[] (UV, normal,
	 * colours, coefficients, tangent), an Integer (highlight state) or an Obj (surface shader, referenced); a custom
	 * element's is its bytes.
	 */
	static class ElementSet extends Obj {
		final LinkedHashMap<Integer,Object> map=new LinkedHashMap<Integer,Object>();
		ElementSet(int leaf){super(leaf);}
		void put(int type,Object v){
			Object old=map.put(type,v instanceof Obj?ref((Obj)v):v);
			if(old instanceof Obj)release((Obj)old);
			edited();
		}
		Object get(int type){return map.get(type);}
		boolean has(int type){return map.containsKey(type);}
		void clear(int type){Object old=map.remove(type);if(old instanceof Obj)release((Obj)old);edited();}
		void clearAll(){for(Object v:map.values())if(v instanceof Obj)release((Obj)v);map.clear();edited();}
		float[] floats(int type){Object v=map.get(type);return v instanceof float[]?(float[])v:null;}
		@Override void dropRefs(){for(Object v:map.values())if(v instanceof Obj)release((Obj)v);super.dropRefs();}
		@Override Obj duplicate(){ElementSet d=new ElementSet(leaf());copyInto(d);return base(d);}
		void copyInto(ElementSet d){for(Map.Entry<Integer,Object> e:map.entrySet()){Object v=e.getValue();d.map.put(e.getKey(),v instanceof float[]?((float[])v).clone():v instanceof byte[]?((byte[])v).clone():v instanceof Obj?ref((Obj)v):v);}}
		/** The types in insertion order (Q3Set_GetNextElementType walks them). */
		List<Integer> types(){return new ArrayList<Integer>(map.keySet());}
	}
	static final class AttrSet extends ElementSet {
		AttrSet(){super(ATTRIBUTE_SET);}
		@Override Obj duplicate(){AttrSet d=new AttrSet();copyInto(d);return base(d);}
		/** The attributes of {@code over} added to (replacing those of) this set, as Q3AttributeSet_Inherit does. */
		void inherit(AttrSet over){if(over==null)return;for(Map.Entry<Integer,Object> e:over.map.entrySet())put(e.getKey(),copyValue(e.getValue()));}
	}
	static Object copyValue(Object v){return v instanceof float[]?((float[])v).clone():v instanceof byte[]?((byte[])v).clone():v;}
	static AttrSet dupSet(AttrSet s){return s==null?null:(AttrSet)s.duplicate();}

	// ================================================================== shapes and groups

	/** Every shape keeps a set of its own (Q3Shape_GetSet / SetSet); geometries add an attribute set. */
	static class Shape extends Obj {
		ElementSet set;
		Shape(int leaf){super(leaf);}
		@Override void dropRefs(){release(set);super.dropRefs();}
		<T extends Shape> T shape(T d){d.set=set==null?null:(ElementSet)set.duplicate();return base(d);}
	}

	static final int DG_STATE_DRAWN=1,DG_STATE_INLINE=2,DG_STATE_BBOX=4,DG_STATE_BSPHERE=8,DG_STATE_PICKED=16,DG_STATE_WRITTEN=32;

	/** A group: its objects in order (positions stay valid until removed), and a display group's state and bounding box. */
	static final class Group extends Shape {
		final ArrayList<Pos> list=new ArrayList<Pos>();
		int state=DG_STATE_DRAWN|DG_STATE_PICKED|DG_STATE_WRITTEN;
		float[] bbox;
		/** An I/O proxy's representations, or an external reference's name (not used for drawing beyond the first). */
		Group(int leaf){super(leaf);}
		static final class Pos { Obj obj;int handle;Group group; }
		boolean display(){return is(DISPLAY_GROUP);}
		/** Whether the group takes the object: light groups only lights, info groups strings, display groups drawables. */
		boolean accepts(Obj o){
			if(o==null)return false;
			int t=leaf();
			if(t==LIGHT_GROUP)return o.is(LIGHT);
			if(t==INFO_GROUP)return o.is(STRING);
			if(is(DISPLAY_GROUP))return drawable(o);
			return true;
		}
		/** Where an ordered display group files an object: transforms, styles, attribute sets, shaders, geometries, groups, the rest. */
		static int rank(Obj o){
			if(o.is(TRANSFORM))return 0;if(o.is(STYLE))return 1;if(o.is(ATTRIBUTE_SET))return 2;if(o.is(SHADER))return 3;
			if(o.is(GEOMETRY))return 4;if(o.is(GROUP))return 5;return 6;
		}
		Pos add(Obj o){
			Pos p=new Pos();p.obj=ref(o);p.group=this;
			if(leaf()==ORDERED_DISPLAY_GROUP){int r=rank(o),i=list.size();while(i>0&&rank(list.get(i-1).obj)>r)i--;list.add(i,p);}
			else list.add(p);
			edited();return p;
		}
		Pos addAt(int index,Obj o){
			if(leaf()==ORDERED_DISPLAY_GROUP)return add(o); // an ordered group keeps its order whatever position is asked for
			Pos p=new Pos();p.obj=ref(o);p.group=this;list.add(Math.max(0,Math.min(index,list.size())),p);edited();return p;
		}
		Obj remove(Pos p){int i=list.indexOf(p);if(i<0)return null;list.remove(i);p.group=null;edited();return p.obj;} // the reference passes to the caller
		void empty(int ofType){
			for(int i=list.size()-1;i>=0;i--){Pos p=list.get(i);if(ofType==0||p.obj.is(ofType)){list.remove(i);p.group=null;release(p.obj);}}
			edited();
		}
		@Override void dropRefs(){for(Pos p:list){p.group=null;release(p.obj);}list.clear();super.dropRefs();}
		@Override Obj duplicate(){Group d=new Group(leaf());d.state=state;for(Pos p:list){Obj c=p.obj.shared()&&!(p.obj instanceof Geometry)&&!(p.obj instanceof Group)?ref(p.obj):dupOrRef(p.obj);Pos q=new Pos();q.obj=c;q.group=d;d.list.add(q);}
			d.bbox=bbox==null?null:bbox.clone();return shape(d);}
	}
	/** A duplicate of a child (a new reference to it when it cannot be duplicated). */
	static Obj dupOrRef(Obj o){Obj d=o.duplicate();return d!=null?d:ref(o);}
	/** Whether Q3Object_IsDrawable: shapes other than unknown objects and references; sets and view hints are not. */
	static boolean drawable(Obj o){return o!=null&&(o.is(SHAPE)&&!o.is(UNKNOWN)||o.is(ATTRIBUTE_SET)||o.is(STYLE)||o.is(STATE_OPERATOR));}

	// ================================================================== geometries

	/** A vertex of the public data: a point and an optional attribute set. */
	static final class Vertex {
		float x,y,z;AttrSet attrs;
		Vertex(){}
		Vertex(float x,float y,float z,AttrSet a){this.x=x;this.y=y;this.z=z;attrs=a;}
		Vertex copy(){return new Vertex(x,y,z,dupSet(attrs));}
	}
	static Vertex[] copy(Vertex[] v){if(v==null)return null;Vertex[] c=new Vertex[v.length];for(int i=0;i<v.length;i++)c[i]=v[i]==null?null:v[i].copy();return c;}
	static AttrSet[] copySets(AttrSet[] s){if(s==null)return null;AttrSet[] c=new AttrSet[s.length];for(int i=0;i<s.length;i++)c[i]=dupSet(s[i]);return c;}
	static void releaseAll(Vertex[] v){if(v!=null)for(Vertex x:v)if(x!=null)release(x.attrs);}
	static void releaseAll(AttrSet[] s){if(s!=null)for(AttrSet x:s)release(x);}

	/** A geometry: its data (subclasses) and its own attribute set. */
	static abstract class Geometry extends Shape {
		AttrSet attrs;
		/** A cache the renderer keeps (a tessellation), valid while {@link #cacheEdit} matches the edit index. */
		Object cache;int cacheEdit;
		Geometry(int leaf){super(leaf);}
		@Override void dropRefs(){release(attrs);attrs=null;super.dropRefs();}
		<T extends Geometry> T geo(T d){d.attrs=dupSet(attrs);return shape(d);}
	}

	static final class Triangle extends Geometry {
		final Vertex[] v={new Vertex(),new Vertex(),new Vertex()};
		Triangle(){super(TRIANGLE);}
		@Override void dropRefs(){releaseAll(v);super.dropRefs();}
		@Override Obj duplicate(){Triangle d=new Triangle();for(int i=0;i<3;i++)d.v[i]=v[i].copy();return geo(d);}
	}
	static final class Polygon extends Geometry {
		Vertex[] v=new Vertex[0];
		Polygon(){super(POLYGON);}
		@Override void dropRefs(){releaseAll(v);super.dropRefs();}
		@Override Obj duplicate(){Polygon d=new Polygon();d.v=copy(v);return geo(d);}
	}
	static final class GeneralPolygon extends Geometry {
		Vertex[][] contours=new Vertex[0][];int shapeHint;
		GeneralPolygon(){super(GENERAL_POLYGON);}
		@Override void dropRefs(){for(Vertex[] c:contours)releaseAll(c);super.dropRefs();}
		@Override Obj duplicate(){GeneralPolygon d=new GeneralPolygon();d.contours=new Vertex[contours.length][];for(int i=0;i<contours.length;i++)d.contours[i]=copy(contours[i]);d.shapeHint=shapeHint;return geo(d);}
	}
	static final class Line extends Geometry {
		final Vertex[] v={new Vertex(),new Vertex()};
		Line(){super(LINE);}
		@Override void dropRefs(){releaseAll(v);super.dropRefs();}
		@Override Obj duplicate(){Line d=new Line();d.v[0]=v[0].copy();d.v[1]=v[1].copy();return geo(d);}
	}
	static final class PolyLine extends Geometry {
		Vertex[] v=new Vertex[0];AttrSet[] segments;
		PolyLine(){super(POLYLINE);}
		@Override void dropRefs(){releaseAll(v);releaseAll(segments);super.dropRefs();}
		@Override Obj duplicate(){PolyLine d=new PolyLine();d.v=copy(v);d.segments=copySets(segments);return geo(d);}
	}
	static final class Point extends Geometry {
		float x,y,z;
		Point(){super(POINT);}
		@Override Obj duplicate(){Point d=new Point();d.x=x;d.y=y;d.z=z;return geo(d);}
	}
	/** A 1-bit bitmap (TQ3Bitmap): rows of rowBytes, bit order big (bit 7 first) or little. */
	static final class Bitmap {
		byte[] bits;int width,height,rowBytes,bitOrder;
		boolean get(int x,int y){int b=bits[y*rowBytes+x/8]&0xff;return ((bitOrder==0?b>>>(7-(x&7)):b>>>(x&7))&1)!=0;}
		Bitmap copy(){Bitmap c=new Bitmap();c.bits=bits==null?null:bits.clone();c.width=width;c.height=height;c.rowBytes=rowBytes;c.bitOrder=bitOrder;return c;}
	}
	static final class Marker extends Geometry {
		float x,y,z;int xOffset,yOffset;Bitmap bitmap=new Bitmap();
		Marker(){super(MARKER);}
		@Override Obj duplicate(){Marker d=new Marker();d.x=x;d.y=y;d.z=z;d.xOffset=xOffset;d.yOffset=yOffset;d.bitmap=bitmap.copy();return geo(d);}
	}
	/** A pixmap in a storage object (TQ3StoragePixmap). */
	static final class StoragePixmap {
		Storage image;int width,height,rowBytes,pixelSize,pixelType,bitOrder,byteOrder;
		StoragePixmap copy(){StoragePixmap c=new StoragePixmap();c.image=ref(image);c.width=width;c.height=height;c.rowBytes=rowBytes;c.pixelSize=pixelSize;c.pixelType=pixelType;c.bitOrder=bitOrder;c.byteOrder=byteOrder;return c;}
	}
	static final class PixmapMarker extends Geometry {
		float x,y,z;int xOffset,yOffset;StoragePixmap pixmap=new StoragePixmap();
		PixmapMarker(){super(PIXMAP_MARKER);}
		@Override void dropRefs(){release(pixmap.image);pixmap.image=null;super.dropRefs();}
		@Override Obj duplicate(){PixmapMarker d=new PixmapMarker();d.x=x;d.y=y;d.z=z;d.xOffset=xOffset;d.yOffset=yOffset;d.pixmap=pixmap.copy();return geo(d);}
	}
	/** Box, cone, cylinder, disk, ellipse, ellipsoid, torus: origin and axes, the parameter range, end caps, part attribute sets. */
	static final class Quadric extends Geometry {
		final float[] origin=new float[3],orientation=new float[3],major=new float[3],minor=new float[3];
		float ratio=1,uMin,uMax=1,vMin,vMax=1;int caps;
		/** interior, top, face, bottom (whichever the kind has) */
		AttrSet interior,top,face,bottom;
		/** A box's six face attribute sets (may be null). */
		AttrSet[] faces;
		Quadric(int leaf){super(leaf);}
		@Override void dropRefs(){release(interior);release(top);release(face);release(bottom);releaseAll(faces);super.dropRefs();}
		@Override Obj duplicate(){Quadric d=new Quadric(leaf());System.arraycopy(origin,0,d.origin,0,3);System.arraycopy(orientation,0,d.orientation,0,3);
			System.arraycopy(major,0,d.major,0,3);System.arraycopy(minor,0,d.minor,0,3);d.ratio=ratio;d.uMin=uMin;d.uMax=uMax;d.vMin=vMin;d.vMax=vMax;d.caps=caps;
			d.interior=dupSet(interior);d.top=dupSet(top);d.face=dupSet(face);d.bottom=dupSet(bottom);d.faces=copySets(faces);return geo(d);}
	}
	static final class TriGrid extends Geometry {
		int rows,columns;Vertex[] v=new Vertex[0];AttrSet[] facets;
		TriGrid(){super(TRIGRID);}
		@Override void dropRefs(){releaseAll(v);releaseAll(facets);super.dropRefs();}
		@Override Obj duplicate(){TriGrid d=new TriGrid();d.rows=rows;d.columns=columns;d.v=copy(v);d.facets=copySets(facets);return geo(d);}
	}
	/**
	 * Per-element attribute values of a TriMesh (TQ3TriMeshAttributeData): floats for the float-valued standard types,
	 * ints for the highlight state, objects for surface shaders, bytes (size each) for custom attributes; the use array
	 * (one byte an element, 0 = not set) may be null.
	 */
	static final class AttrArray {
		int type;float[] f;int[] i;Obj[] o;byte[] raw;int rawSize;byte[] use;
		AttrArray copy(){AttrArray c=new AttrArray();c.type=type;c.f=f==null?null:f.clone();c.i=i==null?null:i.clone();
			if(o!=null){c.o=o.clone();for(Obj x:c.o)ref(x);}c.raw=raw==null?null:raw.clone();c.rawSize=rawSize;c.use=use==null?null:use.clone();return c;}
		void release(){if(o!=null)for(Obj x:o)Q3.release(x);}
		boolean used(int k){return use==null||use[k]!=0;}
	}
	static AttrArray[] copy(AttrArray[] a){if(a==null)return new AttrArray[0];AttrArray[] c=new AttrArray[a.length];for(int i=0;i<a.length;i++)c[i]=a[i].copy();return c;}
	static AttrArray find(AttrArray[] a,int type){if(a!=null)for(AttrArray x:a)if(x.type==type)return x;return null;}
	static final class TriMesh extends Geometry {
		int[] triangles=new int[0];AttrArray[] triangleAttrs=new AttrArray[0];
		int[] edges=new int[0];AttrArray[] edgeAttrs=new AttrArray[0];
		float[] points=new float[0];AttrArray[] vertexAttrs=new AttrArray[0];
		/** min x y z, max x y z; isEmpty */
		final float[] bbox=new float[6];boolean bboxEmpty=true;
		TriMesh(){super(TRIMESH);}
		int numTriangles(){return triangles.length/3;}int numPoints(){return points.length/3;}int numEdges(){return edges.length/4;}
		@Override void dropRefs(){for(AttrArray a:triangleAttrs)a.release();for(AttrArray a:edgeAttrs)a.release();for(AttrArray a:vertexAttrs)a.release();super.dropRefs();}
		@Override Obj duplicate(){TriMesh d=new TriMesh();d.triangles=triangles.clone();d.triangleAttrs=copy(triangleAttrs);d.edges=edges.clone();d.edgeAttrs=copy(edgeAttrs);
			d.points=points.clone();d.vertexAttrs=copy(vertexAttrs);System.arraycopy(bbox,0,d.bbox,0,6);d.bboxEmpty=bboxEmpty;return geo(d);}
		/** Recomputes the bounding box from the points (Q3TriMesh_New/SetData ignore the one passed). */
		void computeBounds(){
			int n=numPoints();bboxEmpty=n==0;if(n==0){java.util.Arrays.fill(bbox,0);return;}
			bbox[0]=bbox[3]=points[0];bbox[1]=bbox[4]=points[1];bbox[2]=bbox[5]=points[2];
			for(int i=1;i<n;i++)for(int k=0;k<3;k++){float v=points[3*i+k];if(v<bbox[k])bbox[k]=v;if(v>bbox[3+k])bbox[3+k]=v;}
		}
	}
	static final class Polyhedron extends Geometry {
		Vertex[] v=new Vertex[0];
		/** Edges: vertex indices (2) and triangle indices (2) each; triangles: vertex indices (3) each, their edge flags. */
		int[] edges=new int[0];AttrSet[] edgeAttrs=new AttrSet[0];int[] triangles=new int[0];int[] edgeFlags=new int[0];AttrSet[] triangleAttrs=new AttrSet[0];
		Polyhedron(){super(POLYHEDRON);}
		@Override void dropRefs(){releaseAll(v);releaseAll(edgeAttrs);releaseAll(triangleAttrs);super.dropRefs();}
		@Override Obj duplicate(){Polyhedron d=new Polyhedron();d.v=copy(v);d.edges=edges.clone();d.edgeAttrs=copySets(edgeAttrs);d.triangles=triangles.clone();d.edgeFlags=edgeFlags.clone();d.triangleAttrs=copySets(triangleAttrs);return geo(d);}
	}
	static final class NURBCurve extends Geometry {
		int order;float[] points=new float[0];float[] knots=new float[0];
		NURBCurve(){super(NURB_CURVE);}
		@Override Obj duplicate(){NURBCurve d=new NURBCurve();d.order=order;d.points=points.clone();d.knots=knots.clone();return geo(d);}
	}
	/** A trim curve of a NURB patch: order, rational 2D control points (x y w), knots. */
	static final class TrimCurve { int order;float[] points=new float[0];float[] knots=new float[0];
		TrimCurve copy(){TrimCurve c=new TrimCurve();c.order=order;c.points=points.clone();c.knots=knots.clone();return c;} }
	static final class NURBPatch extends Geometry {
		int uOrder,vOrder,rows,columns;float[] points=new float[0];float[] uKnots=new float[0],vKnots=new float[0];TrimCurve[][] trimLoops=new TrimCurve[0][];
		NURBPatch(){super(NURB_PATCH);}
		@Override Obj duplicate(){NURBPatch d=new NURBPatch();d.uOrder=uOrder;d.vOrder=vOrder;d.rows=rows;d.columns=columns;d.points=points.clone();d.uKnots=uKnots.clone();d.vKnots=vKnots.clone();
			d.trimLoops=new TrimCurve[trimLoops.length][];for(int i=0;i<trimLoops.length;i++){d.trimLoops[i]=new TrimCurve[trimLoops[i].length];for(int k=0;k<trimLoops[i].length;k++)d.trimLoops[i][k]=trimLoops[i][k].copy();}return geo(d);}
	}

	/**
	 * A mesh: vertices, faces made of contours (the first the outline, the others holes), corners (face-specific vertex
	 * attributes) and components (connected pieces, computed on demand). Parts are known to programs by handles.
	 */
	static final class Mesh extends Geometry {
		static final class MVertex { float x,y,z;AttrSet attrs;int handle,id; }
		static final class MFace { final ArrayList<MContour> contours=new ArrayList<MContour>();AttrSet attrs;int handle;
			/** corner attribute sets: vertex to set (a set may serve several vertices) */
			final LinkedHashMap<MVertex,AttrSet> corners=new LinkedHashMap<MVertex,AttrSet>(); }
		static final class MContour { final ArrayList<MVertex> vertices=new ArrayList<MVertex>();MFace face;int handle; }
		static final class MEdge { MVertex a,b;AttrSet attrs;int handle; }
		static final class MComponent { final ArrayList<MVertex> vertices=new ArrayList<MVertex>();final ArrayList<MEdge> edges=new ArrayList<MEdge>();int handle; }
		final ArrayList<MVertex> vertices=new ArrayList<MVertex>();
		final ArrayList<MFace> faces=new ArrayList<MFace>();
		/** Edge attribute sets by the vertex pair (a key of the two handles' order-independent pair). */
		final LinkedHashMap<Long,AttrSet> edgeAttrs=new LinkedHashMap<Long,AttrSet>();
		/** The edges (made from the faces' contours; kept so that their handles stay), and the components last computed. */
		final LinkedHashMap<Long,MEdge> edges=new LinkedHashMap<Long,MEdge>();
		final ArrayList<MComponent> components=new ArrayList<MComponent>();
		int delayed,nextId;
		static long key(MVertex a,MVertex b){int x=Math.min(a.id,b.id),y=Math.max(a.id,b.id);return (long)x<<32|y&0xffffffffL;}
		/** Brings the edges and components up to date with the faces. */
		void topology(){
			java.util.HashSet<Long> seen=new java.util.HashSet<Long>();
			for(MFace f:faces)for(MContour c:f.contours){int n=c.vertices.size();for(int i=0;i<n;i++){MVertex a=c.vertices.get(i),b=c.vertices.get((i+1)%n);long k=key(a,b);seen.add(k);
				if(!edges.containsKey(k)){MEdge e=new MEdge();e.a=a;e.b=b;edges.put(k,e);}}}
			edges.keySet().retainAll(seen);
			// components: vertices joined by edges
			Map<MVertex,MVertex> parent=new java.util.IdentityHashMap<MVertex,MVertex>();
			for(MVertex v:vertices)parent.put(v,v);
			for(MEdge e:edges.values()){MVertex ra=root(parent,e.a),rb=root(parent,e.b);if(ra!=rb)parent.put(ra,rb);}
			Map<MVertex,MComponent> byRoot=new java.util.IdentityHashMap<MVertex,MComponent>();
			ArrayList<MComponent> old=new ArrayList<MComponent>(components);components.clear();
			for(MVertex v:vertices){MVertex r=root(parent,v);MComponent c=byRoot.get(r);
				if(c==null){c=old.isEmpty()?new MComponent():old.remove(0);c.vertices.clear();c.edges.clear();byRoot.put(r,c);components.add(c);}c.vertices.add(v);}
			for(MEdge e:edges.values()){MComponent c=byRoot.get(root(parent,e.a));if(c!=null)c.edges.add(e);}
		}
		private static MVertex root(Map<MVertex,MVertex> p,MVertex v){while(p.get(v)!=v)v=p.get(v);return v;}
		MComponent componentOf(MVertex v){topology();for(MComponent c:components)if(c.vertices.contains(v))return c;return null;}
		Mesh(){super(MESH);}
		@Override void dropRefs(){for(MVertex v:vertices)release(v.attrs);for(MFace f:faces){release(f.attrs);for(AttrSet s:f.corners.values())release(s);}for(AttrSet s:edgeAttrs.values())release(s);super.dropRefs();}
		@Override Obj duplicate(){
			Mesh d=new Mesh();Map<MVertex,MVertex> map=new java.util.IdentityHashMap<MVertex,MVertex>();
			for(MVertex v:vertices){MVertex c=new MVertex();c.x=v.x;c.y=v.y;c.z=v.z;c.attrs=dupSet(v.attrs);map.put(v,c);d.vertices.add(c);}
			for(MFace f:faces){MFace c=new MFace();c.attrs=dupSet(f.attrs);for(MContour k:f.contours){MContour n=new MContour();n.face=c;for(MVertex v:k.vertices)n.vertices.add(map.get(v));c.contours.add(n);}
				for(Map.Entry<MVertex,AttrSet> e:f.corners.entrySet())c.corners.put(map.get(e.getKey()),dupSet(e.getValue()));d.faces.add(c);}
			return geo(d);
		}
	}

	// ================================================================== styles, transforms, shaders, textures

	static final class Style extends Shape {
		/** The value of the one-value styles (backfacing, interpolation, fill, orientation, pick ID, pick parts, receive shadows). */
		int value;
		/** Subdivision: method, c1, c2; antialias: state, mode, quality; fog: state, mode, start, end, density, a, r, g, b. */
		float[] data;
		AttrSet highlight;
		Style(int leaf){super(leaf);}
		@Override void dropRefs(){release(highlight);highlight=null;super.dropRefs();}
		@Override Obj duplicate(){Style d=new Style(leaf());d.value=value;d.data=data==null?null:data.clone();d.highlight=dupSet(highlight);return shape(d);}
	}

	static final class Transform extends Shape {
		/** The transform's own values: matrix (16), scale / translate (3), rotate (axis, radians), rotate about point (axis,
		 *  radians, x y z), rotate about axis (origin 3, orientation 3, radians), quaternion (w x y z). */
		float[] v;int axis;
		Transform(int leaf,float[] v){super(leaf);this.v=v;}
		@Override Obj duplicate(){Transform d=new Transform(leaf(),v==null?null:v.clone());d.axis=axis;return shape(d);}
		/** The matrix (row vectors). */
		float[] matrix(){
			int t=leaf();
			if(t==MATRIX)return v.clone();
			if(t==SCALE)return scale(v[0],v[1],v[2]);
			if(t==TRANSLATE)return translate(v[0],v[1],v[2]);
			if(t==ROTATE)return rotateAxis(axis,v[0]);
			if(t==ROTATE_ABOUT_POINT)return mul(mul(translate(-v[1],-v[2],-v[3]),rotateAxis(axis,v[0])),translate(v[1],v[2],v[3]));
			if(t==ROTATE_ABOUT_AXIS)return rotateAboutAxis(v[0],v[1],v[2],v[3],v[4],v[5],v[6]);
			if(t==QUATERNION)return quaternionMatrix(v[0],v[1],v[2],v[3]);
			return identity();
		}
	}

	static final int BOUNDARY_WRAP=0,BOUNDARY_CLAMP=1;
	static final class Shader extends Shape {
		int uBoundary,vBoundary;float[] uvTransform;
		Texture texture;
		Shader(int leaf){super(leaf);}
		@Override void dropRefs(){release(texture);texture=null;super.dropRefs();}
		@Override Obj duplicate(){Shader d=new Shader(leaf());d.uBoundary=uBoundary;d.vBoundary=vBoundary;d.uvTransform=uvTransform==null?null:uvTransform.clone();d.texture=ref(texture);return shape(d);}
	}

	static final int PIXEL_RGB32=0,PIXEL_ARGB32=1,PIXEL_RGB16=2,PIXEL_ARGB16=3,PIXEL_RGB16_565=4,PIXEL_RGB24=5;
	/** A mipmap level of a TQ3Mipmap: size, row bytes, offset into the image. */
	static final class MipLevel { int width,height,rowBytes,offset; }
	static final class Texture extends Obj {
		StoragePixmap pixmap;
		/** Mipmap: image storage, levels (level 0 only when useMipmapping is false). */
		Storage mipImage;boolean useMipmapping;int pixelType,bitOrder,byteOrder,reserved;MipLevel[] levels=new MipLevel[0];
		/** Compressed pixmap: kept, but drawn as its placeholder. */
		Storage compressed,imageDesc;int descByteOrder;boolean makeMipmaps;int width,height,pixelSize;
		/** The converted image the renderer keeps, valid for {@link #cacheKey}. */
		Object cache;long cacheKey;
		Texture(int leaf){super(leaf);}
		@Override void dropRefs(){if(pixmap!=null)release(pixmap.image);release(mipImage);release(compressed);release(imageDesc);super.dropRefs();}
		@Override Obj duplicate(){Texture d=new Texture(leaf());d.pixmap=pixmap==null?null:pixmap.copy();d.mipImage=ref(mipImage);d.useMipmapping=useMipmapping;d.pixelType=pixelType;
			d.bitOrder=bitOrder;d.byteOrder=byteOrder;d.reserved=reserved;d.levels=new MipLevel[levels.length];for(int i=0;i<levels.length;i++){MipLevel l=new MipLevel();l.width=levels[i].width;l.height=levels[i].height;l.rowBytes=levels[i].rowBytes;l.offset=levels[i].offset;d.levels[i]=l;}
			d.compressed=ref(compressed);d.imageDesc=ref(imageDesc);d.descByteOrder=descByteOrder;d.makeMipmaps=makeMipmaps;d.width=width;d.height=height;d.pixelSize=pixelSize;return base(d);}
		int textureWidth(){return pixmap!=null?pixmap.width:levels.length>0?levels[0].width:width;}
		int textureHeight(){return pixmap!=null?pixmap.height:levels.length>0?levels[0].height:height;}
	}

	// ================================================================== lights and cameras

	static final int ATTENUATION_NONE=0,ATTENUATION_INVERSE=1,ATTENUATION_INVERSE_SQUARED=2;
	static final int FALLOFF_NONE=0,FALLOFF_LINEAR=1,FALLOFF_EXPONENTIAL=2,FALLOFF_COSINE=3;
	static final class Light extends Shape {
		boolean on=true;float brightness=1;final float[] color={1,1,1};
		boolean castsShadows;int attenuation;final float[] location=new float[3],direction={0,0,-1};
		float hotAngle,outerAngle;int fallOff;
		Light(int leaf){super(leaf);}
		@Override Obj duplicate(){Light d=new Light(leaf());d.on=on;d.brightness=brightness;System.arraycopy(color,0,d.color,0,3);d.castsShadows=castsShadows;d.attenuation=attenuation;
			System.arraycopy(location,0,d.location,0,3);System.arraycopy(direction,0,d.direction,0,3);d.hotAngle=hotAngle;d.outerAngle=outerAngle;d.fallOff=fallOff;return shape(d);}
	}

	static final class Camera extends Shape {
		final float[] location={0,0,1},pointOfInterest=new float[3],up={0,1,0};
		float hither=0.1f,yon=10;
		/** The view port in frustum coordinates: origin (upper left), width, height. */
		float vpX=-1,vpY=1,vpWidth=2,vpHeight=2;
		/** Orthographic: left top right bottom; view plane: viewPlane, halfWidth, halfHeight, centerX, centerY; view angle: fov, aspect. */
		float left=-1,top=1,right=1,bottom=-1;
		float viewPlane=1,halfWidth=1,halfHeight=1,centerX,centerY;
		float fov=(float)(Math.PI/3),aspect=1;
		Camera(int leaf){super(leaf);}
		@Override Obj duplicate(){Camera d=new Camera(leaf());System.arraycopy(location,0,d.location,0,3);System.arraycopy(pointOfInterest,0,d.pointOfInterest,0,3);System.arraycopy(up,0,d.up,0,3);
			d.hither=hither;d.yon=yon;d.vpX=vpX;d.vpY=vpY;d.vpWidth=vpWidth;d.vpHeight=vpHeight;d.left=left;d.top=top;d.right=right;d.bottom=bottom;
			d.viewPlane=viewPlane;d.halfWidth=halfWidth;d.halfHeight=halfHeight;d.centerX=centerX;d.centerY=centerY;d.fov=fov;d.aspect=aspect;return shape(d);}

		/** World to view: the camera at the origin looking down -z with up along +y. */
		float[] worldToView(){
			float zx=location[0]-pointOfInterest[0],zy=location[1]-pointOfInterest[1],zz=location[2]-pointOfInterest[2];
			float zl=(float)Math.sqrt(zx*zx+zy*zy+zz*zz);if(zl!=0){zx/=zl;zy/=zl;zz/=zl;}
			float xx=up[1]*zz-up[2]*zy,xy=up[2]*zx-up[0]*zz,xz=up[0]*zy-up[1]*zx;
			float xl=(float)Math.sqrt(xx*xx+xy*xy+xz*xz);if(xl!=0){xx/=xl;xy/=xl;xz/=xl;}
			float yx=zy*xz-zz*xy,yy=zz*xx-zx*xz,yz=zx*xy-zy*xx;
			float[] m={xx,yx,zx,0, xy,yy,zy,0, xz,yz,zz,0, 0,0,0,1};
			float lx=location[0],ly=location[1],lz=location[2];
			m[12]=-(lx*xx+ly*xy+lz*xz);m[13]=-(lx*yx+ly*yy+lz*yz);m[14]=-(lx*zx+ly*zy+lz*zz);
			return m;
		}
		/** View to frustum: x, y into -1..1, z from 0 at hither to -1 at yon; then the view port. */
		float[] viewToFrustum(){return viewToFrustum(true);}
		/**
		 * View to frustum. A perspective camera's matrix is divided by yon, as QuickDraw 3D's (so frustum w runs from 0 to 1 at
		 * yon: RAVE fog distances are in these units); Q3Camera_GetViewToFrustum leaves the view port out.
		 */
		float[] viewToFrustum(boolean withViewPort){
			float[] m=new float[16];
			int t=leaf();
			if(t==VIEW_ANGLE_ASPECT){
				float w=(float)(1/Math.tan(fov*0.5)),h;
				if(aspect<=1){h=w*aspect;}else{h=w;w=h/aspect;}
				float q=1/(yon-hither);
				m[0]=w;m[5]=h;m[10]=yon*q;m[11]=-1;m[14]=yon*hither*q;
			}else if(t==ORTHOGRAPHIC){
				float q=1/(yon-hither);
				m[0]=2/(right-left);m[5]=2/(top-bottom);m[10]=q;m[12]=-(right+left)/(right-left);m[13]=-(top+bottom)/(top-bottom);m[14]=hither*q;m[15]=1;
			}else{ // view plane: the plane at viewPlane in front, halfWidth/halfHeight about its centre
				float q=1/(yon-hither);float d=viewPlane;
				m[0]=d/halfWidth;m[5]=d/halfHeight;m[8]=centerX/halfWidth;m[9]=centerY/halfHeight;
				m[10]=yon*q;m[11]=-1;m[14]=yon*hither*q;
			}
			if(t!=ORTHOGRAPHIC&&yon!=0)for(int i=0;i<16;i++)m[i]/=yon;
			if(withViewPort&&(vpX!=-1||vpY!=1||vpWidth!=2||vpHeight!=2)){
				float sx=2/vpWidth,sy=2/vpHeight;
				float[] vp={sx,0,0,0, 0,sy,0,0, 0,0,1,0, -1-vpX*sx,1-vpY*sy,0,1};
				m=mul(m,vp);
			}
			return m;
		}
		float[] worldToFrustum(){return mul(worldToView(),viewToFrustum(true));} // with the view port (only GetViewToFrustum leaves it out)
	}

	// ================================================================== renderers, draw contexts, storage, strings

	static final class Renderer extends Obj {
		int vendor=-1,engine=-1;int csgEquation;boolean doubleBufferBypass;int raveHints;int textureFilter=1;
		/** What the renderer keeps between frames (the Interactive Renderer's RAVE contexts). */
		Object state;
		Renderer(int leaf){super(leaf);}
		@Override Obj duplicate(){Renderer d=new Renderer(leaf());d.vendor=vendor;d.engine=engine;d.csgEquation=csgEquation;d.doubleBufferBypass=doubleBufferBypass;d.raveHints=raveHints;d.textureFilter=textureFilter;return base(d);}
	}

	static final int CLEAR_NONE=0,CLEAR_WITH_COLOR=1;
	static final class DrawContext extends Obj {
		int clearMethod=CLEAR_WITH_COLOR;final float[] clearColor={1,1,1,1};
		final float[] pane=new float[4];boolean paneState;Bitmap mask;boolean maskState;boolean doubleBuffer=true;
		/** Macintosh: window, 2D library, GX view port, graf port. */
		int window,library=1,gxViewPort,grafPort;
		/** Pixmap: the image and its layout. */
		int image,width,height,rowBytes,pixelSize,pixelType,bitOrder,byteOrder;
		DrawContext(int leaf){super(leaf);}
		@Override Obj duplicate(){DrawContext d=new DrawContext(leaf());d.clearMethod=clearMethod;System.arraycopy(clearColor,0,d.clearColor,0,4);System.arraycopy(pane,0,d.pane,0,4);
			d.paneState=paneState;d.mask=mask==null?null:mask.copy();d.maskState=maskState;d.doubleBuffer=doubleBuffer;d.window=window;d.library=library;d.gxViewPort=gxViewPort;d.grafPort=grafPort;
			d.image=image;d.width=width;d.height=height;d.rowBytes=rowBytes;d.pixelSize=pixelSize;d.pixelType=pixelType;d.bitOrder=bitOrder;d.byteOrder=byteOrder;return base(d);}
	}

	/** A storage object: bytes a file reads and writes (the front end keeps the ones that live in guest memory or files). */
	static abstract class Storage extends Obj {
		Storage(int leaf){super(leaf);}
		abstract int size() throws Exception;
		/** Up to {@code length} bytes from {@code offset}: how many were read. */
		abstract int read(int offset,byte[] into,int at,int length) throws Exception;
		abstract int write(int offset,byte[] from,int at,int length) throws Exception;
		/** Open for a file (Q3File_OpenRead/OpenWrite) and closed with it. */
		void open(boolean write) throws Exception {}
		void close() throws Exception {}
		/** Everything, for reading a file. */
		byte[] bytes() throws Exception {int n=size();byte[] b=new byte[Math.max(0,n)];int got=read(0,b,0,n);return got==n?b:java.util.Arrays.copyOf(b,Math.max(0,got));}
	}
	/** Storage in a Java byte array (memory storage with a copied buffer, and the renderer's own). */
	static class ArrayStorage extends Storage {
		byte[] data;int length;
		ArrayStorage(int leaf,byte[] d){super(leaf);data=d==null?new byte[0]:d;length=data.length;}
		@Override int size(){return length;}
		@Override int read(int offset,byte[] into,int at,int n){if(offset>=length)return 0;n=Math.min(n,length-offset);System.arraycopy(data,offset,into,at,n);return n;}
		@Override int write(int offset,byte[] from,int at,int n){
			if(offset+n>data.length)data=java.util.Arrays.copyOf(data,Math.max(offset+n,data.length*2));
			System.arraycopy(from,at,data,offset,n);length=Math.max(length,offset+n);return n;
		}
		@Override Obj duplicate(){ArrayStorage d=new ArrayStorage(leaf(),java.util.Arrays.copyOf(data,length));return base(d);}
	}

	static final class StringObj extends Obj {
		byte[] text;
		StringObj(byte[] t){super(C_STRING);text=t==null?new byte[0]:t;}
		@Override Obj duplicate(){return base(new StringObj(text.clone()));}
	}

	/** An object a 3DMF file held that nothing here understands (kept for writing it back). */
	static final class Unknown extends Shape {
		int objectType;byte[] contents;int byteOrder;String name;
		Unknown(int leaf){super(leaf);}
		@Override Obj duplicate(){Unknown d=new Unknown(leaf());d.objectType=objectType;d.contents=contents==null?null:contents.clone();d.byteOrder=byteOrder;d.name=name;return shape(d);}
	}

	/** Push and pop as objects (Q3Push_New / Q3Pop_New). */
	static final class StateOperator extends Shape {
		StateOperator(int leaf){super(leaf);}
		@Override Obj duplicate(){return shape(new StateOperator(leaf()));}
	}

	/** Objects without data of their own that still need to exist (view hints, controller states, trackers). */
	static final class Plain extends Obj {
		final Map<String,Object> props=new java.util.HashMap<String,Object>();
		Plain(int leaf){super(leaf);}
		@Override Obj duplicate(){Plain d=new Plain(leaf());d.props.putAll(props);return base(d);}
	}

	// ================================================================== matrices (row-major 4x4, row vectors)

	static float[] identity(){return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};}
	static float[] translate(float x,float y,float z){float[] m=identity();m[12]=x;m[13]=y;m[14]=z;return m;}
	static float[] scale(float x,float y,float z){float[] m=identity();m[0]=x;m[5]=y;m[10]=z;return m;}
	/** a * b: a's transform first, then b's. */
	static float[] mul(float[] a,float[] b){
		float[] r=new float[16];
		for(int i=0;i<4;i++)for(int j=0;j<4;j++){float s=0;for(int k=0;k<4;k++)s+=a[4*i+k]*b[4*k+j];r[4*i+j]=s;}
		return r;
	}
	static float[] rotateX(float a){float c=(float)Math.cos(a),s=(float)Math.sin(a);float[] m=identity();m[5]=c;m[6]=s;m[9]=-s;m[10]=c;return m;}
	static float[] rotateY(float a){float c=(float)Math.cos(a),s=(float)Math.sin(a);float[] m=identity();m[0]=c;m[2]=-s;m[8]=s;m[10]=c;return m;}
	static float[] rotateZ(float a){float c=(float)Math.cos(a),s=(float)Math.sin(a);float[] m=identity();m[0]=c;m[1]=s;m[4]=-s;m[5]=c;return m;}
	static float[] rotateAxis(int axis,float a){return axis==0?rotateX(a):axis==1?rotateY(a):rotateZ(a);}
	/** Rotation by {@code angle} about the axis through {@code origin} along the (unit) {@code orientation}. */
	static float[] rotateAboutAxis(float ox,float oy,float oz,float ax,float ay,float az,float angle){
		float c=(float)Math.cos(angle),s=(float)Math.sin(angle),t=1-c;
		float[] m=identity();
		m[0]=t*ax*ax+c;   m[1]=t*ax*ay+s*az;m[2]=t*ax*az-s*ay;
		m[4]=t*ax*ay-s*az;m[5]=t*ay*ay+c;   m[6]=t*ay*az+s*ax;
		m[8]=t*ax*az+s*ay;m[9]=t*ay*az-s*ax;m[10]=t*az*az+c;
		if(ox!=0||oy!=0||oz!=0)m=mul(mul(translate(-ox,-oy,-oz),m),translate(ox,oy,oz));
		return m;
	}
	static float[] quaternionMatrix(float w,float x,float y,float z){
		float xx=x*x,yy=y*y,zz=z*z,xy=x*y,xz=x*z,yz=y*z,wx=w*x,wy=w*y,wz=w*z;
		return new float[]{1-2*(yy+zz),2*(xy+wz),2*(xz-wy),0, 2*(xy-wz),1-2*(xx+zz),2*(yz+wx),0, 2*(xz+wy),2*(yz-wx),1-2*(xx+yy),0, 0,0,0,1};
	}
	/** The inverse by Gauss-Jordan elimination with partial pivoting; null when singular. */
	static float[] invert(float[] src){
		double[] a=new double[16];for(int i=0;i<16;i++)a[i]=src[i];
		double[] inv=new double[16];for(int i=0;i<4;i++)inv[5*i]=1;
		for(int c=0;c<4;c++){
			int p=c;double best=Math.abs(a[4*c+c]);
			for(int r=c+1;r<4;r++){double v=Math.abs(a[4*r+c]);if(v>best){best=v;p=r;}}
			if(best==0)return null;
			if(p!=c)for(int k=0;k<4;k++){double t=a[4*c+k];a[4*c+k]=a[4*p+k];a[4*p+k]=t;t=inv[4*c+k];inv[4*c+k]=inv[4*p+k];inv[4*p+k]=t;}
			double d=1/a[4*c+c];for(int k=0;k<4;k++){a[4*c+k]*=d;inv[4*c+k]*=d;}
			for(int r=0;r<4;r++){if(r==c)continue;double f=a[4*r+c];if(f==0)continue;for(int k=0;k<4;k++){a[4*r+k]-=f*a[4*c+k];inv[4*r+k]-=f*inv[4*c+k];}}
		}
		float[] r=new float[16];for(int i=0;i<16;i++)r[i]=(float)inv[i];return r;
	}
	static float[] transpose(float[] m){float[] r=new float[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)r[4*j+i]=m[4*i+j];return r;}
	static boolean isIdentity(float[] m){for(int i=0;i<16;i++)if(m[i]!=((i%5==0)?1:0))return false;return true;}
	/** Transforms a point in place (with the homogeneous divide when w is not 1). */
	static void transformPoint(float[] m,float[] p,int o){
		float x=p[o],y=p[o+1],z=p[o+2];
		float w=x*m[3]+y*m[7]+z*m[11]+m[15];
		float nx=x*m[0]+y*m[4]+z*m[8]+m[12],ny=x*m[1]+y*m[5]+z*m[9]+m[13],nz=x*m[2]+y*m[6]+z*m[10]+m[14];
		if(w!=1&&w!=0){float iw=1/w;nx*=iw;ny*=iw;nz*=iw;}
		p[o]=nx;p[o+1]=ny;p[o+2]=nz;
	}
	/** Transforms a vector in place (the upper 3x3 only). */
	static void transformVector(float[] m,float[] v,int o){
		float x=v[o],y=v[o+1],z=v[o+2];
		v[o]=x*m[0]+y*m[4]+z*m[8];v[o+1]=x*m[1]+y*m[5]+z*m[9];v[o+2]=x*m[2]+y*m[6]+z*m[10];
	}
	static float[] fl(float... v){return v;}
}

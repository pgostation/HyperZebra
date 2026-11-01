import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QuickDraw 3D 1.6 for PowerPC programs without the extension: the CFM library "QuickDraw™ 3D" served in Java, after the
 * Universal Interfaces 3.3 headers (QD3D*.h). The objects are {@link Q3}'s, drawing is {@link Q3View}'s (through our RAVE
 * engine, {@link PpcRave}), 3DMF files are {@link Q3Metafile}'s; geometries are marshalled by {@link PpcQ3Geometry}, the
 * math of QD3DMath.h is {@link PpcQ3Math}'s, storage and files {@link PpcQ3IO}'s.
 * <p>
 * Objects are known to the program by handles: small blocks of guest memory (never read) that stand for the Java objects.
 * -Dhzppc.qd3d=extension loads the real library from the Extensions folder instead.
 */
final class PpcQuickDraw3D implements Q3.Owner {
	/** The library's name in its 'cfrg' (the extension's file is "QuickDraw™ 3D"). */
	static final String LIBRARY="QuickDraw™ 3D";
	private static final String MODE=System.getProperty("hzppc.qd3d","native");
	/** Whether this class serves the library (else the real one is loaded, when installed). */
	static final boolean NATIVE=MODE.equals("native");
	/** -Dhzppc.qd3d.trace=true: logs every call into the library. */
	static final boolean TRACE=Boolean.getBoolean("hzppc.qd3d.trace");
	/** The version it answers: 1.6 final (Q3GetReleaseVersion, Gestalt 'q3v '). */
	static final int MAJOR=1,MINOR=6,RELEASE=0x01608000;
	static final int SUCCESS=1,FAILURE=0;
	static final java.nio.charset.Charset MAC_ROMAN=java.nio.charset.Charset.forName("MacRoman");

	final PpcRuntime rt;final M68kMemory memory;
	PpcQ3Geometry geometry;PpcQ3Math math;PpcQ3IO io;PpcQ3Viewer viewer;
	PpcLibraries libraries(){return libs;}
	PpcQuickDraw3D(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	// ================================================================== calls

	/** Arguments of a call: parameter k in its register or parameter-area slot as the PowerPC calling convention puts it. */
	final class A {
		final int[] slot,fpr;
		A(String sig){
			int n=sig==null?16:sig.length();slot=new int[n];fpr=new int[n];
			int g=0,f=0;
			for(int k=0;k<n;k++){char c=sig==null?'i':sig.charAt(k);slot[k]=g;fpr[k]=f;
				if(c=='f'){g++;f++;}else if(c=='d'){g+=2;f++;}else if(c=='w'){g+=2;}else g++;}
		}
		int i(int k) throws M68kCpuException {return rt.arg(slot[k]);}
		float f(int k){return (float)rt.farg(fpr[k]);}
		void ret(int v){rt.ret(v);}
		void ok(boolean b){rt.ret(b?SUCCESS:FAILURE);}
		void retF(float v){rt.retD(v);}
	}
	interface Fn { void call(A a) throws Exception; }
	private PpcLibraries libs;
	private int faults;
	/** Parameters that are not all 32-bit integers: f float, d double, w a 64-bit structure (from the headers). */
	private static final Map<String,String> SIGNATURES=new HashMap<String,String>();
	static{
		String s="Q3OrthographicCamera_SetLeft=if;Q3OrthographicCamera_SetTop=if;Q3OrthographicCamera_SetRight=if;Q3OrthographicCamera_SetBottom=if;"
			+"Q3ViewPlaneCamera_SetViewPlane=if;Q3ViewPlaneCamera_SetHalfWidth=if;Q3ViewPlaneCamera_SetHalfHeight=if;Q3ViewPlaneCamera_SetCenterX=if;"
			+"Q3ViewPlaneCamera_SetCenterY=if;Q3ViewAngleAspectCamera_SetFOV=if;Q3ViewAngleAspectCamera_SetAspectRatio=if;Q3Tracker_SetNotifyThresholds=iff;"
			+"Q3NURBCurve_SetKnot=iif;Q3NURBPatch_SetUKnot=iif;Q3NURBPatch_SetVKnot=iif;Q3Torus_SetRatio=if;Q3Uns64_Write=wi;Q3Int64_Write=wi;Q3Float32_Write=fi;"
			+"Q3Float64_Write=di;Q3Light_SetBrightness=if;Q3SpotLight_SetHotAngle=if;Q3SpotLight_SetOuterAngle=if;Q3Point2D_Set=iff;Q3Param2D_Set=iff;"
			+"Q3Point3D_Set=ifff;Q3RationalPoint3D_Set=ifff;Q3RationalPoint4D_Set=iffff;Q3Vector2D_Set=iff;Q3Vector3D_Set=ifff;Q3PolarPoint_Set=iff;"
			+"Q3SphericalPoint_Set=ifff;Q3Point2D_RRatio=iiffi;Q3Param2D_RRatio=iiffi;Q3Point3D_RRatio=iiffi;Q3RationalPoint4D_RRatio=iiffi;"
			+"Q3Vector2D_Scale=ifi;Q3Vector3D_Scale=ifi;Q3Matrix3x3_SetTranslate=iff;Q3Matrix3x3_SetScale=iff;Q3Matrix3x3_SetRotateAboutPoint=iif;"
			+"Q3Matrix4x4_SetTranslate=ifff;Q3Matrix4x4_SetScale=ifff;Q3Matrix4x4_SetRotateAboutPoint=iifff;Q3Matrix4x4_SetRotateAboutAxis=iiif;"
			+"Q3Matrix4x4_SetRotate_X=if;Q3Matrix4x4_SetRotate_Y=if;Q3Matrix4x4_SetRotate_Z=if;Q3Matrix4x4_SetRotate_XYZ=ifff;Q3Quaternion_Set=iffff;"
			+"Q3Quaternion_SetRotateAboutAxis=iif;Q3Quaternion_SetRotate_XYZ=ifff;Q3Quaternion_SetRotate_X=if;Q3Quaternion_SetRotate_Y=if;"
			+"Q3Quaternion_SetRotate_Z=if;Q3Quaternion_InterpolateFast=iifi;Q3Quaternion_InterpolateLinear=iifi;Q3BoundingSphere_Set=iifi;"
			+"Q3RotateTransform_SetAngle=if;Q3RotateAboutPointTransform_SetAngle=if;Q3RotateAboutAxisTransform_SetAngle=if;Q3ColorRGB_Set=ifff;"
			+"Q3ColorARGB_Set=iffff;Q3ColorRGB_Scale=ifi;Q3ColorRGB_Lerp=iifi;Q3ViewerChangeBrightness=if";
		for(String e:s.split(";")){int eq=e.indexOf('=');SIGNATURES.put(e.substring(0,eq),e.substring(eq+1));}
	}
	/** Registers a function of the library. */
	void def(String name,Fn f){defIn(LIBRARY,name,f);}
	/** Registers a function of a library of QuickDraw 3D's (the library itself, the Viewer). */
	void defIn(String library,String name,Fn f){
		final A a=new A(SIGNATURES.get(name));
		libs.register(library,name,(r,c)->{
			if(TRACE)System.err.printf("[qd3d] %s(%08X %08X %08X %08X)%n",name,c.r[3],c.r[4],c.r[5],c.r[6]);
			try{f.call(a);}
			catch(PpcCpu.Unwind e){throw e;}
			catch(M68kCpuException|RuntimeException e){
				// a bad pointer in the program's data (or a gap here): reported, and the call fails as QuickDraw 3D's calls fail
				if(faults++<20){System.err.println("[qd3d] "+name+" failed: "+e);e.printStackTrace();}
				post(ERR_INVALID_PARAMETER);r.ret(0);
			}
			catch(M68kException e){throw e;}
			catch(Exception e){System.err.println("[qd3d] "+name+": "+e);r.ret(0);}
		});
	}

	// ================================================================== guest memory

	int rl(int a) throws M68kCpuException {return memory.readLong(a);}
	void wl(int a,int v) throws M68kCpuException {memory.writeLong(a,v);}
	float rf(int a) throws M68kCpuException {return Float.intBitsToFloat(memory.readLong(a));}
	void wf(int a,float v) throws M68kCpuException {memory.writeLong(a,Float.floatToRawIntBits(v));}
	float[] rfs(int a,int n) throws M68kCpuException {float[] f=new float[n];for(int i=0;i<n;i++)f[i]=rf(a+4*i);return f;}
	void rfs(int a,float[] into,int at,int n) throws M68kCpuException {for(int i=0;i<n;i++)into[at+i]=rf(a+4*i);}
	void wfs(int a,float[] v,int at,int n) throws M68kCpuException {for(int i=0;i<n;i++)wf(a+4*i,v[at+i]);}
	void wfs(int a,float... v) throws M68kCpuException {wfs(a,v,0,v.length);}
	/** A guest block (zeroed), 0 when there is no memory. */
	int alloc(int size) throws M68kCpuException {try{return rt.allocate(Math.max(4,size),4);}catch(M68kCpuException e){post(ERR_OUT_OF_MEMORY);return 0;}}
	void free(int p){try{if(p!=0&&memory.isAllocated(p))memory.free(p);}catch(RuntimeException ignored){}}
	int tb(String name,int... args) throws M68kException {return rt.toolbox(name,args);}
	String cstr(int p) throws M68kCpuException {return p==0?null:new String(rt.cBytes(p),MAC_ROMAN);}

	// ================================================================== handles

	private final HashMap<Integer,Object> byHandle=new HashMap<Integer,Object>();
	private final ArrayDeque<Integer> freeHandles=new ArrayDeque<Integer>();
	private int handleChunk,handleFree;
	private int newHandle() throws M68kCpuException {
		Integer h=freeHandles.poll();if(h!=null)return h;
		if(handleChunk==0||handleFree>=4096){handleChunk=rt.allocate(4096*8,8);handleFree=0;}
		return handleChunk+8*handleFree++;
	}
	/** The handle of an object (made when first handed out); 0 for null. */
	int handle(Q3.Obj o) throws M68kCpuException {
		if(o==null)return 0;
		if(o.handle==0||o.owner!=this){o.handle=newHandle();o.owner=this;byHandle.put(o.handle,o);}
		return o.handle;
	}
	/** A new reference to a shared object, as its handle (the Get functions give the program a reference to dispose). */
	int newRef(Q3.Obj o) throws M68kCpuException {return handle(Q3.ref(o));}
	/** A new object handed to the program (its first reference). */
	int made(Q3.Obj o) throws M68kCpuException {return handle(o);}
	Q3.Obj obj(int h){Object o=byHandle.get(h);return o instanceof Q3.Obj?(Q3.Obj)o:null;}
	/** The object of a handle if it is of class {@code c}; otherwise posts kQ3ErrorInvalidObject and gives null. */
	<T> T get(int h,Class<T> c){
		Object o=byHandle.get(h);
		if(c.isInstance(o))return c.cast(o);
		post(h==0?ERR_NULL_PARAMETER:ERR_INVALID_OBJECT);return null;
	}
	/** A handle for any other thing a program holds (group positions, mesh parts). */
	int handleOf(Object o,int existing) throws M68kCpuException {if(existing!=0&&byHandle.get(existing)==o)return existing;int h=newHandle();byHandle.put(h,o);return h;}
	Object thing(int h){return byHandle.get(h);}
	void dropHandle(int h){if(h!=0&&byHandle.remove(h)!=null)freeHandles.add(h);}
	@Override public void forget(Q3.Obj o){
		if(o.owner!=this||o.handle==0)return;
		if(o instanceof Q3.Group)for(Q3.Group.Pos p:((Q3.Group)o).list)if(p.handle!=0){dropHandle(p.handle);p.handle=0;}
		if(o instanceof Q3.Renderer)dropRenderer((Q3.Renderer)o);
		if(o instanceof Q3.Mesh)geometry.dropMeshParts((Q3.Mesh)o);
		dropHandle(o.handle);o.handle=0;o.owner=null;
	}
	int posHandle(Q3.Group.Pos p) throws M68kCpuException {if(p==null)return 0;if(p.handle==0)p.handle=handleOf(p,0);return p.handle;}
	Q3.Group.Pos pos(int h){Object o=byHandle.get(h);return o instanceof Q3.Group.Pos?(Q3.Group.Pos)o:null;}

	// ================================================================== errors, warnings, notices

	static final int ERR_INTERNAL=-28500,ERR_NOT_INITIALIZED=-28490,ERR_UNIMPLEMENTED=-28488,ERR_OUT_OF_MEMORY=-28482,ERR_NULL_PARAMETER=-28481,
		ERR_PARAMETER_OUT_OF_RANGE=-28480,ERR_INVALID_PARAMETER=-28479,ERR_INVALID_DATA=-28478,ERR_INVALID_OBJECT=-28474,ERR_INVALID_OBJECT_TYPE=-28472,
		ERR_NOT_SUPPORTED=-28464,ERR_DEGENERATE_GEOMETRY=-28460,ERR_NO_STORAGE=-28458,ERR_END_OF_FILE=-28457,ERR_INVALID_METAFILE=-28455,
		ERR_FILE_NOT_OPEN=-28442,ERR_FILE_IS_OPEN=-28441,ERR_FILE_MODE=-28433,ERR_VIEW_NOT_STARTED=-28419,ERR_RENDERING_NOT_STARTED=-28410,
		ERR_NOTHING_TO_POP=-28406,ERR_INVALID_POSITION_FOR_GROUP=-28399,ERR_INVALID_OBJECT_FOR_GROUP=-28398,ERR_ATTRIBUTE_NOT_CONTAINED=-28394,
		ERR_ATTRIBUTE_INVALID_TYPE=-28393,ERR_BAD_DRAW_CONTEXT=-28389;
	static final int NOTICE_DATA_ALREADY_EMPTY=-28100;
	private int errorProc,errorRef,warningProc,warningRef,noticeProc,noticeRef;
	private int firstError,lastError,firstWarning,lastWarning,firstNotice,lastNotice;short firstMacError,lastMacError;
	private int postDepth;
	void post(int error){
		if(firstError==0)firstError=error;lastError=error;
		if(TRACE)System.err.println("[qd3d] error "+error);
		callPoster(errorProc,error,errorRef);
	}
	void warn(int warning){if(firstWarning==0)firstWarning=warning;lastWarning=warning;callPoster(warningProc,warning,warningRef);}
	void notice(int notice){if(firstNotice==0)firstNotice=notice;lastNotice=notice;callPoster(noticeProc,notice,noticeRef);}
	private void callPoster(int proc,int code,int ref){
		if(proc==0||postDepth>0)return;
		postDepth++;
		try{rt.cpu.call(proc,code,code,ref);}catch(Exception e){System.err.println("[qd3d] error handler failed: "+e);}finally{postDepth--;}
	}

	// ================================================================== the library

	boolean initialized;
	/** Defines the exports (called by PpcLibraries). */
	void define(PpcLibraries l){
		libs=l;
		geometry=new PpcQ3Geometry(this);math=new PpcQ3Math(this);io=new PpcQ3IO(this);viewer=new PpcQ3Viewer(this);
		defineCore();defineSets();defineGroups();defineStyles();defineTransforms();defineShaders();defineLights();defineCameras();
		defineDrawContexts();defineRenderers();defineViews();definePicks();defineStrings();defineErrors();defineExtensions();
		geometry.define();math.define();io.define();viewer.define(l);
	}

	private void defineCore(){
		def("Q3Initialize",a->{if(initialized)notice(-28087);initialized=true;a.ok(true);});
		def("Q3Exit",a->{initialized=false;a.ok(true);});
		def("Q3IsInitialized",a->a.ret(initialized?1:0));
		def("Q3GetVersion",a->{int ma=a.i(0),mi=a.i(1);if(ma!=0)wl(ma,MAJOR);if(mi!=0)wl(mi,MINOR);a.ok(ma!=0&&mi!=0);});
		def("Q3GetReleaseVersion",a->{int p=a.i(0);if(p!=0)wl(p,RELEASE);a.ok(p!=0);});
		def("Q3ObjectHierarchy_GetTypeFromString",a->{String n=cstr(a.i(0));Integer t=n==null?null:Q3.TYPES_BY_NAME.get(n);int out=a.i(1);
			if(out!=0)wl(out,t==null?0:t);a.ok(t!=null);});
		def("Q3ObjectHierarchy_GetStringFromType",a->{String n=Q3.NAMES.get(a.i(0));int out=a.i(1);
			if(out!=0){byte[] b=(n==null?"":n).getBytes(MAC_ROMAN);memory.load(out,b);memory.writeByte(out+b.length,0);}a.ok(n!=null);});
		def("Q3ObjectHierarchy_IsTypeRegistered",a->a.ret(Q3.chainOf(a.i(0))!=null?1:0));
		def("Q3ObjectHierarchy_IsNameRegistered",a->{String n=cstr(a.i(0));a.ret(n!=null&&Q3.TYPES_BY_NAME.containsKey(n)?1:0);});
		def("Q3ObjectHierarchy_GetSubClassData",a->{int type=a.i(0),out=a.i(1);if(out==0){a.ok(false);return;}
			List<Integer> subs=new ArrayList<Integer>();
			for(Map.Entry<Integer,String> e:Q3.NAMES.entrySet()){int[] c=Q3.chainOf(e.getKey());if(c!=null&&c.length>=2&&c[c.length-2]==type)subs.add(e.getKey());}
			if(type==0)for(Map.Entry<Integer,String> e:Q3.NAMES.entrySet()){int[] c=Q3.chainOf(e.getKey());if(c!=null&&c.length==1)subs.add(e.getKey());}
			int arr=subs.isEmpty()?0:alloc(4*subs.size());for(int i=0;i<subs.size();i++)wl(arr+4*i,subs.get(i));
			wl(out,subs.size());wl(out+4,arr);a.ok(true);});
		def("Q3ObjectHierarchy_EmptySubClassData",a->{int d=a.i(0);if(d!=0){free(rl(d+4));wl(d,0);wl(d+4,0);}a.ok(d!=0);});
		def("Q3Object_Dispose",a->{Q3.Obj o=obj(a.i(0));if(o==null){post(ERR_INVALID_OBJECT);a.ok(false);return;}Q3.release(o);a.ok(true);});
		def("Q3Object_Duplicate",a->{Q3.Obj o=obj(a.i(0));if(o==null){post(ERR_INVALID_OBJECT);a.ret(0);return;}
			Q3.Obj d=o.duplicate();if(d==null){warn(-28299);a.ret(0);return;}a.ret(made(d));});
		def("Q3Object_Submit",a->{Q3.Obj o=obj(a.i(0));Q3View v=get(a.i(1),Q3View.class);if(o==null||v==null){a.ok(false);return;}a.ok(submit(v,o));});
		def("Q3Object_IsDrawable",a->a.ret(Q3.drawable(obj(a.i(0)))?1:0));
		def("Q3Object_IsWritable",a->{Q3.Obj o=obj(a.i(0));a.ret(o!=null&&(o.is(Q3.SHAPE)||o.is(Q3.SET)||o.is(Q3.TEXTURE)||o.is(Q3.STRING))?1:0);});
		def("Q3Object_GetType",a->{Q3.Obj o=obj(a.i(0));a.ret(o==null?0:o.typeAt(0));});
		def("Q3Object_GetLeafType",a->{Q3.Obj o=obj(a.i(0));a.ret(o==null?0:o.leaf());});
		def("Q3Object_IsType",a->{Q3.Obj o=obj(a.i(0));a.ret(o!=null&&o.is(a.i(1))?1:0);});
		def("Q3Shared_GetType",a->typeAt(a,Q3.SHARED,1));
		def("Q3Shared_GetReference",a->{Q3.Obj o=obj(a.i(0));if(o==null||!o.shared()){post(ERR_INVALID_OBJECT);a.ret(0);return;}a.ret(newRef(o));});
		def("Q3Shared_IsReferenced",a->{Q3.Obj o=obj(a.i(0));a.ret(o!=null&&o.refs>1?1:0);});
		def("Q3Shared_GetEditIndex",a->{Q3.Obj o=obj(a.i(0));a.ret(o==null?0:o.editIndex);});
		def("Q3Shared_Edited",a->{Q3.Obj o=obj(a.i(0));if(o!=null)o.edited();a.ok(o!=null);});
		def("Q3Shape_GetType",a->typeAt(a,Q3.SHAPE,2));
		def("Q3Shape_GetSet",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);int out=a.i(1);if(s==null||out==0){a.ok(false);return;}wl(out,s.set==null?0:newRef(s.set));a.ok(true);});
		def("Q3Shape_SetSet",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);if(s==null){a.ok(false);return;}Q3.Obj set=obj(a.i(1));
			s.set=Q3.replace(s.set,set instanceof Q3.ElementSet?(Q3.ElementSet)set:null);s.edited();a.ok(true);});
		def("Q3Shape_AddElement",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);if(s==null){a.ok(false);return;}if(s.set==null)s.set=new Q3.ElementSet(Q3.SET);
			a.ok(setAdd(s.set,a.i(1),a.i(2)));s.edited();});
		def("Q3Shape_GetElement",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);a.ok(s!=null&&s.set!=null&&setGet(s.set,a.i(1),a.i(2)));});
		def("Q3Shape_ContainsElement",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);a.ret(s!=null&&s.set!=null&&s.set.has(a.i(1))?1:0);});
		def("Q3Shape_GetNextElementType",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);a.ok(s!=null&&nextType(s.set,a.i(1)));});
		def("Q3Shape_EmptyElements",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);if(s!=null&&s.set!=null)s.set.clearAll();a.ok(s!=null);});
		def("Q3Shape_ClearElement",a->{Q3.Shape s=get(a.i(0),Q3.Shape.class);if(s!=null&&s.set!=null)s.set.clear(a.i(1));a.ok(s!=null);});
		def("Q3Bitmap_Empty",a->{int b=a.i(0);if(b==0){a.ok(false);return;}free(rl(b));wl(b,0);a.ok(true);});
		def("Q3Bitmap_GetImageSize",a->{int w=a.i(0),h=a.i(1);a.ret(((w+7)/8)*h);});
	}
	/** The object's type at a depth of its class chain, when it is of {@code parent}. */
	private void typeAt(A a,int parent,int depth) throws M68kCpuException {Q3.Obj o=obj(a.i(0));if(o==null||!o.is(parent)){post(ERR_INVALID_OBJECT);a.ret(0);return;}a.ret(o.typeAt(depth));}

	/** Submits an object to a view in whatever mode the view is in. */
	boolean submit(Q3View v,Q3.Obj o) throws Exception {
		if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);return false;}
		if(v.mode==Q3View.PICKING&&v.submittedRoot==null){v.submittedRoot=o;try{v.submit(o);}finally{v.submittedRoot=null;}return true;}
		v.submit(o);return true;
	}
	/** Submits a temporary object made from immediate-mode data (Q3Box_Submit ...), then lets it go. */
	boolean submitTemporary(int view,Q3.Obj o) throws Exception {
		Q3View v=get(view,Q3View.class);if(v==null||o==null){Q3.release(o);return false;}
		try{return submit(v,o);}finally{Q3.release(o);}
	}

	// ================================================================== sets and attributes

	/** Bytes of an element's value: standard attributes by type, custom ones as registered. */
	int elementSize(int type){
		if(Q3.isStandardAttribute(type))return Q3.ATTR_SIZE[type];
		Q3.ElementClass c=elementClasses.get(type);return c==null?-1:c.size;
	}
	final Map<Integer,Q3.ElementClass> elementClasses=new HashMap<Integer,Q3.ElementClass>();
	private int nextCustomType=-1000;
	/** Reads an element value from guest memory into its Java form. */
	Object readElement(int type,int p) throws M68kCpuException {
		if(Q3.isStandardAttribute(type)){
			if(type==Q3.ATTR_HIGHLIGHT_STATE)return rl(p);
			if(type==Q3.ATTR_SURFACE_SHADER){return obj(rl(p));}
			return rfs(p,Q3.ATTR_FLOATS[type]);
		}
		int size=elementSize(type);if(size<0)return null;
		return memory.readBytes(p,size);
	}
	void writeElement(int type,Object v,int p) throws M68kCpuException {
		if(v instanceof float[]){float[] f=(float[])v;wfs(p,f,0,f.length);}
		else if(v instanceof Integer)wl(p,(Integer)v);
		else if(v instanceof Q3.Obj)wl(p,newRef((Q3.Obj)v));
		else if(v instanceof byte[])memory.load(p,(byte[])v);
	}
	boolean setAdd(Q3.ElementSet s,int type,int data) throws M68kCpuException {
		if(data==0){post(ERR_NULL_PARAMETER);return false;}
		if(type==0||elementSize(type)<0){post(-28465);return false;} // kQ3ErrorUnknownElementType
		Object v=readElement(type,data);
		if(type==Q3.ATTR_SURFACE_SHADER&&!(v instanceof Q3.Obj)){post(ERR_INVALID_OBJECT);return false;}
		s.put(type,v);return true;
	}
	boolean setGet(Q3.ElementSet s,int type,int data) throws M68kCpuException {
		Object v=s.get(type);
		if(v==null){post(ERR_ATTRIBUTE_NOT_CONTAINED);return false;}
		if(data!=0)writeElement(type,v,data);return true;
	}
	/** Q3Set_GetNextElementType: kQ3ElementTypeNone starts, the last one gives kQ3ElementTypeNone. */
	boolean nextType(Q3.ElementSet s,int p) throws M68kCpuException {
		if(p==0)return false;
		int cur=rl(p);List<Integer> types=s==null?new ArrayList<Integer>():s.types();
		int next=0;
		if(cur==0)next=types.isEmpty()?0:types.get(0);
		else{int i=types.indexOf(cur);next=i>=0&&i+1<types.size()?types.get(i+1):0;}
		wl(p,next);return true;
	}
	private void defineSets(){
		def("Q3Set_New",a->a.ret(made(new Q3.ElementSet(Q3.SET))));
		def("Q3Set_GetType",a->typeAt(a,Q3.SET,2));
		def("Q3Set_Add",a->{Q3.ElementSet s=get(a.i(0),Q3.ElementSet.class);a.ok(s!=null&&setAdd(s,a.i(1),a.i(2)));});
		def("Q3Set_Get",a->{Q3.ElementSet s=get(a.i(0),Q3.ElementSet.class);a.ok(s!=null&&setGet(s,a.i(1),a.i(2)));});
		def("Q3Set_Contains",a->{Q3.ElementSet s=get(a.i(0),Q3.ElementSet.class);a.ret(s!=null&&s.has(a.i(1))?1:0);});
		def("Q3Set_Clear",a->{Q3.ElementSet s=get(a.i(0),Q3.ElementSet.class);if(s!=null)s.clear(a.i(1));a.ok(s!=null);});
		def("Q3Set_Empty",a->{Q3.ElementSet s=get(a.i(0),Q3.ElementSet.class);if(s!=null)s.clearAll();a.ok(s!=null);});
		def("Q3Set_GetNextElementType",a->{Q3.ElementSet s=get(a.i(0),Q3.ElementSet.class);a.ok(s!=null&&nextType(s,a.i(1)));});
		def("Q3AttributeSet_New",a->a.ret(made(new Q3.AttrSet())));
		def("Q3AttributeSet_Add",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);a.ok(s!=null&&setAdd(s,a.i(1),a.i(2)));});
		def("Q3AttributeSet_Contains",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);a.ret(s!=null&&s.has(a.i(1))?1:0);});
		def("Q3AttributeSet_Get",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);a.ok(s!=null&&setGet(s,a.i(1),a.i(2)));});
		def("Q3AttributeSet_Clear",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);if(s!=null)s.clear(a.i(1));a.ok(s!=null);});
		def("Q3AttributeSet_Empty",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);if(s!=null)s.clearAll();a.ok(s!=null);});
		def("Q3AttributeSet_GetNextAttributeType",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);a.ok(s!=null&&nextType(s,a.i(1)));});
		def("Q3AttributeSet_Submit",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);Q3View v=get(a.i(1),Q3View.class);a.ok(s!=null&&v!=null&&submit(v,s));});
		def("Q3AttributeSet_Inherit",a->{Q3.AttrSet p=get(a.i(0),Q3.AttrSet.class),c=get(a.i(1),Q3.AttrSet.class),r=get(a.i(2),Q3.AttrSet.class);
			if(p==null||c==null||r==null){a.ok(false);return;}
			Q3.AttrSet tmp=new Q3.AttrSet();tmp.inherit(p);tmp.inherit(c);r.clearAll();r.inherit(tmp);Q3.release(tmp);a.ok(true);});
		def("Q3Attribute_Submit",a->{int type=a.i(0),data=a.i(1);Q3View v=get(a.i(2),Q3View.class);if(v==null||data==0){a.ok(false);return;}
			if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			Object val=readElement(type,data);if(v.mode==Q3View.WRITING){Q3.AttrSet s=new Q3.AttrSet();s.put(type,val);a.ok(submitTemporary(a.i(2),s));return;}
			v.attribute(type,val);a.ok(true);});
		def("Q3XElementClass_Register",a->a.ret(registerElement(a.i(0),cstr(a.i(1)),a.i(2),a.i(3),false)));
		def("Q3XAttributeClass_Register",a->a.ret(registerElement(a.i(0),cstr(a.i(1)),a.i(2),a.i(3),true)));
		def("Q3AttributeClass_Register",a->a.ret(registerElementAs(a.i(0),cstr(a.i(1)),a.i(2),a.i(3),true))); // QuickDraw 3D 1.0's form (3D World 2.0 imports it): the type is passed by value, no out pointer
		def("Q3XElementType_GetElementSize",a->{int n=elementSize(a.i(0));if(a.i(1)!=0)wl(a.i(1),Math.max(0,n));a.ok(n>=0);});
		def("Q3XAttributeSet_GetPointer",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);int type=a.i(1);if(s==null||!s.has(type)){a.ret(0);return;}
			// a copy the program may read (QuickDraw 3D gave its internal element): kept per set and type
			int size=Math.max(4,elementSize(type));int p=attributePointers.computeIfAbsent(s,k->new HashMap<Integer,Integer>()).computeIfAbsent(type,k->{try{return alloc(size);}catch(M68kCpuException e){return 0;}});
			if(p!=0)writeElement(type,s.get(type) instanceof Q3.Obj?null:s.get(type),p);
			if(s.get(type) instanceof Q3.Obj)wl(p,handle((Q3.Obj)s.get(type)));
			a.ret(p);});
		def("Q3XAttributeSet_GetMask",a->{Q3.AttrSet s=get(a.i(0),Q3.AttrSet.class);int m=0;if(s!=null)for(int t:s.types())m|=Q3.isStandardAttribute(t)?1<<(t-1):0x80000000;a.ret(m);});
		def("Q3ColorRGB_Set",a->{int c=a.i(0);wfs(c,a.f(1),a.f(2),a.f(3));a.ret(c);});
		def("Q3ColorARGB_Set",a->{int c=a.i(0);wfs(c,a.f(1),a.f(2),a.f(3),a.f(4));a.ret(c);});
		def("Q3ColorRGB_Add",a->{float[] x=rfs(a.i(0),3),y=rfs(a.i(1),3);int r=a.i(2);wfs(r,x[0]+y[0],x[1]+y[1],x[2]+y[2]);a.ret(r);});
		def("Q3ColorRGB_Subtract",a->{float[] x=rfs(a.i(0),3),y=rfs(a.i(1),3);int r=a.i(2);wfs(r,x[0]-y[0],x[1]-y[1],x[2]-y[2]);a.ret(r);});
		def("Q3ColorRGB_Scale",a->{float[] x=rfs(a.i(0),3);float k=a.f(1);int r=a.i(2);wfs(r,x[0]*k,x[1]*k,x[2]*k);a.ret(r);});
		def("Q3ColorRGB_Clamp",a->{float[] x=rfs(a.i(0),3);int r=a.i(1);wfs(r,cl01(x[0]),cl01(x[1]),cl01(x[2]));a.ret(r);});
		def("Q3ColorRGB_Lerp",a->{float[] x=rfs(a.i(0),3),y=rfs(a.i(1),3);float t=a.f(2);int r=a.i(3);wfs(r,x[0]+(y[0]-x[0])*t,x[1]+(y[1]-x[1])*t,x[2]+(y[2]-x[2])*t);a.ret(r);});
		def("Q3ColorRGB_Accumulate",a->{float[] x=rfs(a.i(0),3);int r=a.i(1);float[] y=rfs(r,3);wfs(r,x[0]+y[0],x[1]+y[1],x[2]+y[2]);a.ret(r);});
		def("Q3ColorRGB_Luminance",a->{float[] x=rfs(a.i(0),3);int r=a.i(1);wf(r,0.30078125f*x[0]+0.58984375f*x[1]+0.109375f*x[2]);a.ret(r);});
	}
	private final Map<Q3.AttrSet,Map<Integer,Integer>> attributePointers=new java.util.WeakHashMap<Q3.AttrSet,Map<Integer,Integer>>();
	static float cl01(float v){return v<0?0:v>1?1:v;}
	private int registerElement(int typeOut,String name,int size,int metaHandler,boolean attribute) throws M68kCpuException {
		if(typeOut==0||name==null)return 0;
		for(Q3.ElementClass c:elementClasses.values())if(c.name.equals(name)){wl(typeOut,c.type);return classHandle(c);}
		int type=nextCustomType--;
		Q3.ElementClass c=new Q3.ElementClass(type,name,size,metaHandler,attribute);
		elementClasses.put(type,c);Q3.registerClass(attribute?Q3.ATTRIBUTE_ELEMENT:Q3.ELEMENT,type,name);
		wl(typeOut,type);return classHandle(c);
	}
	/** An element/attribute class of a type the program chose itself (QuickDraw 3D 1.0's Q3AttributeClass_Register(type, name, size, metaHandler)). */
	private int registerElementAs(int type,String name,int size,int metaHandler,boolean attribute) throws M68kCpuException {
		if(type==0||name==null)return 0;
		Q3.ElementClass c=elementClasses.get(type);
		if(c==null){c=new Q3.ElementClass(type,name,size,metaHandler,attribute);elementClasses.put(type,c);Q3.registerClass(attribute?Q3.ATTRIBUTE_ELEMENT:Q3.ELEMENT,type,name);}
		return classHandle(c);
	}
	private final Map<Object,Integer> classHandles=new java.util.IdentityHashMap<Object,Integer>();
	int classHandle(Object c) throws M68kCpuException {Integer h=classHandles.get(c);if(h!=null)return h;int n=handleOf(c,0);classHandles.put(c,n);return n;}

	// ================================================================== groups

	private void defineGroups(){
		def("Q3Group_New",a->a.ret(made(new Q3.Group(Q3.GROUP))));
		def("Q3LightGroup_New",a->a.ret(made(new Q3.Group(Q3.LIGHT_GROUP))));
		def("Q3InfoGroup_New",a->a.ret(made(new Q3.Group(Q3.INFO_GROUP))));
		def("Q3DisplayGroup_New",a->a.ret(made(new Q3.Group(Q3.DISPLAY_GROUP))));
		def("Q3OrderedDisplayGroup_New",a->a.ret(made(new Q3.Group(Q3.ORDERED_DISPLAY_GROUP))));
		def("Q3IOProxyDisplayGroup_New",a->a.ret(made(new Q3.Group(Q3.IO_PROXY))));
		def("Q3Group_GetType",a->typeAt(a,Q3.GROUP,3));
		def("Q3DisplayGroup_GetType",a->typeAt(a,Q3.DISPLAY_GROUP,4));
		def("Q3Group_AddObject",a->{Q3.Group g=get(a.i(0),Q3.Group.class);Q3.Obj o=obj(a.i(1));
			if(g==null||o==null){if(o==null)post(ERR_INVALID_OBJECT);a.ret(0);return;}
			if(!g.accepts(o)){post(ERR_INVALID_OBJECT_FOR_GROUP);a.ret(0);return;}
			a.ret(posHandle(g.add(o)));});
		def("Q3Group_AddObjectBefore",a->addNear(a,0));
		def("Q3Group_AddObjectAfter",a->addNear(a,1));
		def("Q3Group_GetPositionObject",a->{Q3.Group g=get(a.i(0),Q3.Group.class);Q3.Group.Pos p=pos(a.i(1));int out=a.i(2);
			if(g==null||p==null||p.group!=g||out==0){if(p==null||p.group!=g)post(ERR_INVALID_POSITION_FOR_GROUP);a.ok(false);return;}
			wl(out,newRef(p.obj));a.ok(true);});
		def("Q3Group_SetPositionObject",a->{Q3.Group g=get(a.i(0),Q3.Group.class);Q3.Group.Pos p=pos(a.i(1));Q3.Obj o=obj(a.i(2));
			if(g==null||p==null||p.group!=g||o==null){a.ok(false);return;}p.obj=Q3.replace(p.obj,o);g.edited();a.ok(true);});
		def("Q3Group_RemovePosition",a->{Q3.Group g=get(a.i(0),Q3.Group.class);Q3.Group.Pos p=pos(a.i(1));
			if(g==null||p==null||p.group!=g){post(ERR_INVALID_POSITION_FOR_GROUP);a.ret(0);return;}
			Q3.Obj o=g.remove(p);dropHandle(p.handle);p.handle=0;
			a.ret(handle(o));}); // the group's reference passes to the program
		def("Q3Group_GetFirstPosition",a->walk(a,0,0,false));
		def("Q3Group_GetLastPosition",a->walk(a,0,1,false));
		def("Q3Group_GetNextPosition",a->walk(a,0,2,false));
		def("Q3Group_GetPreviousPosition",a->walk(a,0,3,false));
		def("Q3Group_GetFirstPositionOfType",a->walk(a,a.i(1),0,true));
		def("Q3Group_GetLastPositionOfType",a->walk(a,a.i(1),1,true));
		def("Q3Group_GetNextPositionOfType",a->walk(a,a.i(1),2,true));
		def("Q3Group_GetPreviousPositionOfType",a->walk(a,a.i(1),3,true));
		def("Q3Group_CountObjects",a->{Q3.Group g=get(a.i(0),Q3.Group.class);if(g==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),g.list.size());a.ok(true);});
		def("Q3Group_CountObjectsOfType",a->{Q3.Group g=get(a.i(0),Q3.Group.class);int t=a.i(1),out=a.i(2);if(g==null||out==0){a.ok(false);return;}
			int n=0;for(Q3.Group.Pos p:g.list)if(p.obj.is(t))n++;wl(out,n);a.ok(true);});
		def("Q3Group_EmptyObjects",a->{Q3.Group g=get(a.i(0),Q3.Group.class);if(g==null){a.ok(false);return;}emptyGroup(g,0);a.ok(true);});
		def("Q3Group_EmptyObjectsOfType",a->{Q3.Group g=get(a.i(0),Q3.Group.class);if(g==null){a.ok(false);return;}emptyGroup(g,a.i(1));a.ok(true);});
		def("Q3Group_GetFirstObjectPosition",a->objectPos(a,0));
		def("Q3Group_GetLastObjectPosition",a->objectPos(a,1));
		def("Q3Group_GetNextObjectPosition",a->objectPos(a,2));
		def("Q3Group_GetPreviousObjectPosition",a->objectPos(a,3));
		def("Q3DisplayGroup_GetState",a->{Q3.Group g=get(a.i(0),Q3.Group.class);if(g==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),g.state);a.ok(true);});
		def("Q3DisplayGroup_SetState",a->{Q3.Group g=get(a.i(0),Q3.Group.class);if(g==null){a.ok(false);return;}g.state=a.i(1);g.edited();a.ok(true);});
		def("Q3DisplayGroup_Submit",a->{Q3.Group g=get(a.i(0),Q3.Group.class);Q3View v=get(a.i(1),Q3View.class);a.ok(g!=null&&v!=null&&submit(v,g));});
		def("Q3DisplayGroup_SetAndUseBoundingBox",a->{Q3.Group g=get(a.i(0),Q3.Group.class);int b=a.i(1);if(g==null||b==0){a.ok(false);return;}
			g.bbox=rfs(b,6);if(rl(b+24)!=0)g.bbox=null;g.state|=Q3.DG_STATE_BBOX;a.ok(true);});
		def("Q3DisplayGroup_GetBoundingBox",a->{Q3.Group g=get(a.i(0),Q3.Group.class);int b=a.i(1);if(g==null||b==0||g.bbox==null){a.ok(false);return;}
			wfs(b,g.bbox,0,6);wl(b+24,0);a.ok(true);});
		def("Q3DisplayGroup_RemoveBoundingBox",a->{Q3.Group g=get(a.i(0),Q3.Group.class);if(g==null){a.ok(false);return;}g.bbox=null;g.state&=~Q3.DG_STATE_BBOX;a.ok(true);});
		def("Q3DisplayGroup_CalcAndUseBoundingBox",a->{Q3.Group g=get(a.i(0),Q3.Group.class);Q3View v=get(a.i(2),Q3View.class);if(g==null||v==null){a.ok(false);return;}
			if(!v.startBounds(false,a.i(1)==0)){post(v.error);a.ok(false);return;}
			try{boolean had=(g.state&Q3.DG_STATE_BBOX)!=0;g.state&=~Q3.DG_STATE_BBOX;v.submit(g);if(had)g.state|=Q3.DG_STATE_BBOX;}finally{v.endBounds();}
			if(v.bmin!=null){g.bbox=new float[]{v.bmin[0],v.bmin[1],v.bmin[2],v.bmax[0],v.bmax[1],v.bmax[2]};g.state|=Q3.DG_STATE_BBOX;}a.ok(true);});
		def("Q3XGroup_GetPositionPrivate",a->a.ret(0));
	}
	private void emptyGroup(Q3.Group g,int type){
		for(Q3.Group.Pos p:g.list)if((type==0||p.obj.is(type))&&p.handle!=0){dropHandle(p.handle);p.handle=0;}
		g.empty(type);
	}
	private void addNear(A a,int after) throws M68kCpuException {
		Q3.Group g=get(a.i(0),Q3.Group.class);Q3.Group.Pos p=pos(a.i(1));Q3.Obj o=obj(a.i(2));
		if(g==null||o==null||p==null||p.group!=g){if(p==null||p.group!=g)post(ERR_INVALID_POSITION_FOR_GROUP);a.ret(0);return;}
		if(!g.accepts(o)){post(ERR_INVALID_OBJECT_FOR_GROUP);a.ret(0);return;}
		a.ret(posHandle(g.addAt(g.list.indexOf(p)+after,o)));
	}
	/** First/last/next/previous position (of a type): the position is written, or NULL when there is none (still success). */
	private void walk(A a,int type,int how,boolean typed) throws M68kCpuException {
		Q3.Group g=get(a.i(0),Q3.Group.class);int out=a.i(typed?2:1);
		if(g==null||out==0){a.ok(false);return;}
		List<Q3.Group.Pos> l=g.list;int start,step;
		if(how==0){start=0;step=1;}else if(how==1){start=l.size()-1;step=-1;}
		else{Q3.Group.Pos cur=pos(rl(out));int i=cur==null?-1:l.indexOf(cur);if(i<0){wl(out,0);post(ERR_INVALID_POSITION_FOR_GROUP);a.ok(false);return;}start=how==2?i+1:i-1;step=how==2?1:-1;}
		for(int i=start;i>=0&&i<l.size();i+=step){Q3.Group.Pos p=l.get(i);if(!typed||p.obj.is(type)){wl(out,posHandle(p));a.ok(true);return;}}
		wl(out,0);a.ok(true);
	}
	private void objectPos(A a,int how) throws M68kCpuException {
		Q3.Group g=get(a.i(0),Q3.Group.class);Q3.Obj o=obj(a.i(1));int out=a.i(2);
		if(g==null||out==0){a.ok(false);return;}
		List<Q3.Group.Pos> l=g.list;int start,step;
		if(how==0){start=0;step=1;}else if(how==1){start=l.size()-1;step=-1;}
		else{Q3.Group.Pos cur=pos(rl(out));int i=cur==null?-1:l.indexOf(cur);if(i<0){wl(out,0);a.ok(false);return;}start=how==2?i+1:i-1;step=how==2?1:-1;}
		for(int i=start;i>=0&&i<l.size();i+=step)if(l.get(i).obj==o){wl(out,posHandle(l.get(i)));a.ok(true);return;}
		wl(out,0);a.ok(true);
	}

	// ================================================================== styles

	private Q3.Style style(int type,int value){Q3.Style s=new Q3.Style(type);s.value=value;return s;}
	/** New / Submit / Get / Set of a style with one integer value. */
	private void valueStyle(String name,int type,String get,String set){
		def("Q3"+name+"_New",a->a.ret(made(style(type,a.i(0)))));
		def("Q3"+name+"_Submit",a->{int v=a.i(0);Q3View view=get(a.i(1),Q3View.class);if(view==null){a.ok(false);return;}
			if(view.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			if(view.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(1),style(type,v)));return;}
			view.style(style(type,v));a.ok(true);});
		def("Q3"+name+"_"+get,a->{Q3.Style s=get(a.i(0),Q3.Style.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.value);a.ok(true);});
		def("Q3"+name+"_"+set,a->{Q3.Style s=get(a.i(0),Q3.Style.class);if(s==null){a.ok(false);return;}s.value=a.i(1);s.edited();a.ok(true);});
	}
	/** Styles with a data structure of floats (enum fields as integers where marked by 'i'). */
	private float[] readStyleData(int p,String layout) throws M68kCpuException {float[] f=new float[layout.length()];for(int i=0;i<f.length;i++)f[i]=layout.charAt(i)=='i'?rl(p+4*i):rf(p+4*i);return f;}
	private void writeStyleData(int p,float[] f,String layout) throws M68kCpuException {for(int i=0;i<f.length;i++){if(layout.charAt(i)=='i')wl(p+4*i,(int)f[i]);else wf(p+4*i,f[i]);}}
	private void dataStyle(String name,int type,String layout){
		def("Q3"+name+"_New",a->{if(a.i(0)==0){post(ERR_NULL_PARAMETER);a.ret(0);return;}Q3.Style s=new Q3.Style(type);s.data=readStyleData(a.i(0),layout);a.ret(made(s));});
		def("Q3"+name+"_Submit",a->{int d=a.i(0);Q3View view=get(a.i(1),Q3View.class);if(view==null||d==0){a.ok(false);return;}
			Q3.Style s=new Q3.Style(type);s.data=readStyleData(d,layout);
			if(view.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			if(view.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(1),s));return;}
			view.style(s);a.ok(true);});
		def("Q3"+name+"_GetData",a->{Q3.Style s=get(a.i(0),Q3.Style.class);if(s==null||a.i(1)==0||s.data==null){a.ok(false);return;}writeStyleData(a.i(1),s.data,layout);a.ok(true);});
		def("Q3"+name+"_SetData",a->{Q3.Style s=get(a.i(0),Q3.Style.class);if(s==null||a.i(1)==0){a.ok(false);return;}s.data=readStyleData(a.i(1),layout);s.edited();a.ok(true);});
	}
	static final String SUBDIVISION_LAYOUT="iff",ANTIALIAS_LAYOUT="iif",FOG_LAYOUT="iifffffff";
	private void defineStyles(){
		def("Q3Style_GetType",a->typeAt(a,Q3.STYLE,3));
		def("Q3Style_Submit",a->{Q3.Style s=get(a.i(0),Q3.Style.class);Q3View v=get(a.i(1),Q3View.class);a.ok(s!=null&&v!=null&&submit(v,s));});
		valueStyle("BackfacingStyle",Q3.BACKFACING,"Get","Set");
		valueStyle("InterpolationStyle",Q3.INTERPOLATION,"Get","Set");
		valueStyle("FillStyle",Q3.FILL,"Get","Set");
		valueStyle("OrientationStyle",Q3.ORIENTATION,"Get","Set");
		valueStyle("PickIDStyle",Q3.PICK_ID,"Get","Set");
		valueStyle("PickPartsStyle",Q3.PICK_PARTS,"Get","Set");
		valueStyle("ReceiveShadowsStyle",Q3.RECEIVE_SHADOWS,"Get","Set");
		dataStyle("SubdivisionStyle",Q3.SUBDIVISION,SUBDIVISION_LAYOUT);
		dataStyle("AntiAliasStyle",Q3.ANTIALIAS,ANTIALIAS_LAYOUT);
		dataStyle("FogStyle",Q3.FOG,FOG_LAYOUT);
		def("Q3HighlightStyle_New",a->{Q3.Style s=new Q3.Style(Q3.HIGHLIGHT);Q3.Obj o=obj(a.i(0));s.highlight=o instanceof Q3.AttrSet?Q3.ref((Q3.AttrSet)o):null;a.ret(made(s));});
		def("Q3HighlightStyle_Submit",a->{Q3View v=get(a.i(1),Q3View.class);if(v==null){a.ok(false);return;}Q3.Obj o=obj(a.i(0));
			Q3.Style s=new Q3.Style(Q3.HIGHLIGHT);s.highlight=o instanceof Q3.AttrSet?Q3.ref((Q3.AttrSet)o):null;a.ok(submitTemporary(a.i(1),s));});
		def("Q3HighlightStyle_Get",a->{Q3.Style s=get(a.i(0),Q3.Style.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.highlight==null?0:newRef(s.highlight));a.ok(true);});
		def("Q3HighlightStyle_Set",a->{Q3.Style s=get(a.i(0),Q3.Style.class);if(s==null){a.ok(false);return;}Q3.Obj o=obj(a.i(1));
			s.highlight=Q3.replace(s.highlight,o instanceof Q3.AttrSet?(Q3.AttrSet)o:null);s.edited();a.ok(true);});
	}

	// ================================================================== transforms

	/** The matrix of a transform object (Q3Transform_GetMatrix). */
	private void defineTransforms(){
		def("Q3Transform_GetType",a->typeAt(a,Q3.TRANSFORM,3));
		def("Q3Transform_GetMatrix",a->{Q3.Transform t=get(a.i(0),Q3.Transform.class);int m=a.i(1);if(t==null||m==0){a.ret(0);return;}wfs(m,t.matrix(),0,16);a.ret(m);});
		def("Q3Transform_Submit",a->{Q3.Transform t=get(a.i(0),Q3.Transform.class);Q3View v=get(a.i(1),Q3View.class);a.ok(t!=null&&v!=null&&submit(v,t));});
		transform("MatrixTransform",Q3.MATRIX,16,"",null);
		transform("ScaleTransform",Q3.SCALE,3,"",null);
		transform("TranslateTransform",Q3.TRANSLATE,3,"",null);
		transform("QuaternionTransform",Q3.QUATERNION,4,"",null);
		// data transforms: rotate (axis, radians), rotate about point (axis, radians, about), rotate about axis (origin, orientation, radians)
		def("Q3RotateTransform_New",a->a.ret(a.i(0)==0?0:made(rotate(a.i(0)))));
		def("Q3RotateTransform_Submit",a->submitTransform(a,a.i(0)==0?null:rotate(a.i(0)),1));
		def("Q3RotateTransform_SetData",a->{Q3.Transform t=tr(a,Q3.ROTATE);if(t==null||a.i(1)==0){a.ok(false);return;}Q3.Transform n=rotate(a.i(1));t.axis=n.axis;t.v=n.v;t.edited();a.ok(true);});
		def("Q3RotateTransform_GetData",a->{Q3.Transform t=tr(a,Q3.ROTATE);int p=a.i(1);if(t==null||p==0){a.ok(false);return;}wl(p,t.axis);wf(p+4,t.v[0]);a.ok(true);});
		def("Q3RotateTransform_SetAxis",a->{Q3.Transform t=tr(a,Q3.ROTATE);if(t==null){a.ok(false);return;}t.axis=a.i(1);t.edited();a.ok(true);});
		def("Q3RotateTransform_GetAxis",a->{Q3.Transform t=tr(a,Q3.ROTATE);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.axis);a.ok(true);});
		def("Q3RotateTransform_SetAngle",a->{Q3.Transform t=tr(a,Q3.ROTATE);if(t==null){a.ok(false);return;}t.v[0]=a.f(1);t.edited();a.ok(true);});
		def("Q3RotateTransform_GetAngle",a->{Q3.Transform t=tr(a,Q3.ROTATE);if(t==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),t.v[0]);a.ok(true);});
		def("Q3RotateAboutPointTransform_New",a->a.ret(a.i(0)==0?0:made(rotatePoint(a.i(0)))));
		def("Q3RotateAboutPointTransform_Submit",a->submitTransform(a,a.i(0)==0?null:rotatePoint(a.i(0)),1));
		def("Q3RotateAboutPointTransform_SetData",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null||a.i(1)==0){a.ok(false);return;}Q3.Transform n=rotatePoint(a.i(1));t.axis=n.axis;t.v=n.v;t.edited();a.ok(true);});
		def("Q3RotateAboutPointTransform_GetData",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);int p=a.i(1);if(t==null||p==0){a.ok(false);return;}wl(p,t.axis);wfs(p+4,t.v,0,4);a.ok(true);});
		def("Q3RotateAboutPointTransform_SetAxis",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null){a.ok(false);return;}t.axis=a.i(1);t.edited();a.ok(true);});
		def("Q3RotateAboutPointTransform_GetAxis",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.axis);a.ok(true);});
		def("Q3RotateAboutPointTransform_SetAngle",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null){a.ok(false);return;}t.v[0]=a.f(1);t.edited();a.ok(true);});
		def("Q3RotateAboutPointTransform_GetAngle",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),t.v[0]);a.ok(true);});
		def("Q3RotateAboutPointTransform_SetAboutPoint",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),t.v,1,3);t.edited();a.ok(true);});
		def("Q3RotateAboutPointTransform_GetAboutPoint",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_POINT);if(t==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),t.v,1,3);a.ok(true);});
		def("Q3RotateAboutAxisTransform_New",a->a.ret(a.i(0)==0?0:made(rotateAxis(a.i(0)))));
		def("Q3RotateAboutAxisTransform_Submit",a->submitTransform(a,a.i(0)==0?null:rotateAxis(a.i(0)),1));
		def("Q3RotateAboutAxisTransform_SetData",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null||a.i(1)==0){a.ok(false);return;}t.v=rotateAxis(a.i(1)).v;t.edited();a.ok(true);});
		def("Q3RotateAboutAxisTransform_GetData",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);int p=a.i(1);if(t==null||p==0){a.ok(false);return;}wfs(p,t.v,0,7);a.ok(true);});
		def("Q3RotateAboutAxisTransform_SetOrientation",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),t.v,3,3);t.edited();a.ok(true);});
		def("Q3RotateAboutAxisTransform_GetOrientation",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),t.v,3,3);a.ok(true);});
		def("Q3RotateAboutAxisTransform_SetAngle",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null){a.ok(false);return;}t.v[6]=a.f(1);t.edited();a.ok(true);});
		def("Q3RotateAboutAxisTransform_GetAngle",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),t.v[6]);a.ok(true);});
		def("Q3RotateAboutAxisTransform_SetOrigin",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),t.v,0,3);t.edited();a.ok(true);});
		def("Q3RotateAboutAxisTransform_GetOrigin",a->{Q3.Transform t=tr(a,Q3.ROTATE_ABOUT_AXIS);if(t==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),t.v,0,3);a.ok(true);});
		def("Q3ResetTransform_New",a->a.ret(made(new Q3.Transform(Q3.RESET,null))));
		def("Q3ResetTransform_Submit",a->{Q3View v=get(a.i(0),Q3View.class);if(v==null){a.ok(false);return;}
			if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			if(v.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(0),new Q3.Transform(Q3.RESET,null)));return;}
			v.resetTransform();a.ok(true);});
	}
	private Q3.Transform tr(A a,int type) throws M68kCpuException {Q3.Transform t=get(a.i(0),Q3.Transform.class);if(t!=null&&t.leaf()!=type){post(ERR_INVALID_OBJECT_TYPE);return null;}return t;}
	private Q3.Transform rotate(int p) throws M68kCpuException {Q3.Transform t=new Q3.Transform(Q3.ROTATE,new float[]{rf(p+4)});t.axis=rl(p);return t;}
	private Q3.Transform rotatePoint(int p) throws M68kCpuException {Q3.Transform t=new Q3.Transform(Q3.ROTATE_ABOUT_POINT,new float[]{rf(p+4),rf(p+8),rf(p+12),rf(p+16)});t.axis=rl(p);return t;}
	private Q3.Transform rotateAxis(int p) throws M68kCpuException {return new Q3.Transform(Q3.ROTATE_ABOUT_AXIS,rfs(p,7));}
	private void submitTransform(A a,Q3.Transform t,int viewArg) throws Exception {
		Q3View v=get(a.i(viewArg),Q3View.class);if(v==null||t==null){a.ok(false);return;}
		if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
		if(v.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(viewArg),t));return;}
		v.transform(t.matrix());a.ok(true);
	}
	/** New / Submit / Set / Get of a transform whose data is n floats (matrix, scale, translate, quaternion). */
	private void transform(String name,int type,int n,String unused,Object unused2){
		def("Q3"+name+"_New",a->{if(a.i(0)==0){post(ERR_NULL_PARAMETER);a.ret(0);return;}a.ret(made(new Q3.Transform(type,rfs(a.i(0),n))));});
		def("Q3"+name+"_Submit",a->{if(a.i(0)==0){a.ok(false);return;}Q3View v=get(a.i(1),Q3View.class);if(v==null){a.ok(false);return;}
			if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			Q3.Transform t=new Q3.Transform(type,rfs(a.i(0),n));
			if(v.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(1),t));return;}
			v.transform(t.matrix());a.ok(true);});
		def("Q3"+name+"_Set",a->{Q3.Transform t=tr(a,type);if(t==null||a.i(1)==0){a.ok(false);return;}t.v=rfs(a.i(1),n);t.edited();a.ok(true);});
		def("Q3"+name+"_Get",a->{Q3.Transform t=tr(a,type);if(t==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),t.v,0,n);a.ok(true);});
	}

	// ================================================================== shaders and textures

	private void defineShaders(){
		def("Q3Shader_GetType",a->typeAt(a,Q3.SHADER,3));
		def("Q3IlluminationShader_GetType",a->typeAt(a,Q3.ILLUMINATION,4));
		def("Q3SurfaceShader_GetType",a->typeAt(a,Q3.SURFACE_SHADER,4));
		def("Q3Shader_Submit",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);Q3View v=get(a.i(1),Q3View.class);a.ok(s!=null&&v!=null&&submit(v,s));});
		def("Q3Shader_SetUVTransform",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null||a.i(1)==0){a.ok(false);return;}float[] m=rfs(a.i(1),9);
			s.uvTransform=m[0]==1&&m[1]==0&&m[2]==0&&m[3]==0&&m[4]==1&&m[5]==0&&m[6]==0&&m[7]==0&&m[8]==1?null:m;s.edited();a.ok(true);});
		def("Q3Shader_GetUVTransform",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null||a.i(1)==0){a.ok(false);return;}
			wfs(a.i(1),s.uvTransform!=null?s.uvTransform:new float[]{1,0,0,0,1,0,0,0,1},0,9);a.ok(true);});
		def("Q3Shader_SetUBoundary",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null){a.ok(false);return;}s.uBoundary=a.i(1);s.edited();a.ok(true);});
		def("Q3Shader_SetVBoundary",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null){a.ok(false);return;}s.vBoundary=a.i(1);s.edited();a.ok(true);});
		def("Q3Shader_GetUBoundary",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.uBoundary);a.ok(true);});
		def("Q3Shader_GetVBoundary",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.vBoundary);a.ok(true);});
		def("Q3PhongIllumination_New",a->a.ret(made(new Q3.Shader(Q3.PHONG))));
		def("Q3LambertIllumination_New",a->a.ret(made(new Q3.Shader(Q3.LAMBERT))));
		def("Q3NULLIllumination_New",a->a.ret(made(new Q3.Shader(Q3.NULL_ILLUMINATION))));
		def("Q3TextureShader_New",a->{Q3.Shader s=new Q3.Shader(Q3.TEXTURE_SHADER);Q3.Obj t=obj(a.i(0));s.texture=t instanceof Q3.Texture?Q3.ref((Q3.Texture)t):null;a.ret(made(s));});
		def("Q3TextureShader_GetTexture",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.texture==null?0:newRef(s.texture));a.ok(true);});
		def("Q3TextureShader_SetTexture",a->{Q3.Shader s=get(a.i(0),Q3.Shader.class);if(s==null){a.ok(false);return;}Q3.Obj t=obj(a.i(1));
			s.texture=Q3.replace(s.texture,t instanceof Q3.Texture?(Q3.Texture)t:null);s.edited();a.ok(true);});
		def("Q3Texture_GetType",a->typeAt(a,Q3.TEXTURE,2));
		def("Q3Texture_GetWidth",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.textureWidth());a.ok(true);});
		def("Q3Texture_GetHeight",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),t.textureHeight());a.ok(true);});
		def("Q3PixmapTexture_New",a->{if(a.i(0)==0){a.ret(0);return;}Q3.Texture t=new Q3.Texture(Q3.PIXMAP_TEXTURE);t.pixmap=readStoragePixmap(a.i(0));a.ret(made(t));});
		def("Q3PixmapTexture_GetPixmap",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||t.pixmap==null||a.i(1)==0){a.ok(false);return;}writeStoragePixmap(a.i(1),t.pixmap);a.ok(true);});
		def("Q3PixmapTexture_SetPixmap",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||a.i(1)==0){a.ok(false);return;}
			Q3.StoragePixmap old=t.pixmap;t.pixmap=readStoragePixmap(a.i(1));if(old!=null)Q3.release(old.image);t.edited();a.ok(true);});
		def("Q3MipmapTexture_New",a->{if(a.i(0)==0){a.ret(0);return;}Q3.Texture t=new Q3.Texture(Q3.MIPMAP_TEXTURE);readMipmap(a.i(0),t);a.ret(made(t));});
		def("Q3MipmapTexture_GetMipmap",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||a.i(1)==0){a.ok(false);return;}writeMipmap(a.i(1),t);a.ok(true);});
		def("Q3MipmapTexture_SetMipmap",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||a.i(1)==0){a.ok(false);return;}Q3.Storage old=t.mipImage;readMipmap(a.i(1),t);Q3.release(old);t.edited();a.ok(true);});
		def("Q3CompressedPixmapTexture_New",a->{if(a.i(0)==0){a.ret(0);return;}Q3.Texture t=new Q3.Texture(Q3.COMPRESSED_TEXTURE);readCompressed(a.i(0),t);a.ret(made(t));});
		def("Q3CompressedPixmapTexture_GetCompressedPixmap",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);int p=a.i(1);if(t==null||p==0){a.ok(false);return;}
			wl(p,t.compressed==null?0:newRef(t.compressed));wl(p+4,t.descByteOrder);wl(p+8,t.imageDesc==null?0:newRef(t.imageDesc));wl(p+12,t.makeMipmaps?1:0);
			wl(p+16,t.width);wl(p+20,t.height);wl(p+24,t.pixelSize);wl(p+28,t.pixelType);a.ok(true);});
		def("Q3CompressedPixmapTexture_SetCompressedPixmap",a->{Q3.Texture t=get(a.i(0),Q3.Texture.class);if(t==null||a.i(1)==0){a.ok(false);return;}
			Q3.Storage c=t.compressed,d=t.imageDesc;readCompressed(a.i(1),t);Q3.release(c);Q3.release(d);t.edited();a.ok(true);});
		def("Q3CompressedPixmapTexture_CompressImage",a->{post(ERR_UNIMPLEMENTED);a.ok(false);});
	}
	Q3.StoragePixmap readStoragePixmap(int p) throws M68kCpuException {
		Q3.StoragePixmap s=new Q3.StoragePixmap();Q3.Obj st=obj(rl(p));s.image=st instanceof Q3.Storage?Q3.ref((Q3.Storage)st):null;
		s.width=rl(p+4);s.height=rl(p+8);s.rowBytes=rl(p+12);s.pixelSize=rl(p+16);s.pixelType=rl(p+20);s.bitOrder=rl(p+24);s.byteOrder=rl(p+28);return s;
	}
	void writeStoragePixmap(int p,Q3.StoragePixmap s) throws M68kCpuException {
		wl(p,s.image==null?0:newRef(s.image));wl(p+4,s.width);wl(p+8,s.height);wl(p+12,s.rowBytes);wl(p+16,s.pixelSize);wl(p+20,s.pixelType);wl(p+24,s.bitOrder);wl(p+28,s.byteOrder);
	}
	private void readMipmap(int p,Q3.Texture t) throws M68kCpuException {
		Q3.Obj st=obj(rl(p));t.mipImage=st instanceof Q3.Storage?Q3.ref((Q3.Storage)st):null;
		t.useMipmapping=rl(p+4)!=0;t.pixelType=rl(p+8);t.bitOrder=rl(p+12);t.byteOrder=rl(p+16);t.reserved=rl(p+20);
		List<Q3.MipLevel> levels=new ArrayList<Q3.MipLevel>();
		for(int i=0;i<32;i++){int q=p+24+16*i;Q3.MipLevel l=new Q3.MipLevel();l.width=rl(q);l.height=rl(q+4);l.rowBytes=rl(q+8);l.offset=rl(q+12);
			if(l.width<=0||l.height<=0)break;levels.add(l);if(!t.useMipmapping||l.width==1&&l.height==1)break;}
		t.levels=levels.toArray(new Q3.MipLevel[0]);
	}
	private void writeMipmap(int p,Q3.Texture t) throws M68kCpuException {
		wl(p,t.mipImage==null?0:newRef(t.mipImage));wl(p+4,t.useMipmapping?1:0);wl(p+8,t.pixelType);wl(p+12,t.bitOrder);wl(p+16,t.byteOrder);wl(p+20,t.reserved);
		for(int i=0;i<32;i++){int q=p+24+16*i;Q3.MipLevel l=i<t.levels.length?t.levels[i]:null;wl(q,l==null?0:l.width);wl(q+4,l==null?0:l.height);wl(q+8,l==null?0:l.rowBytes);wl(q+12,l==null?0:l.offset);}
	}
	private void readCompressed(int p,Q3.Texture t) throws M68kCpuException {
		Q3.Obj c=obj(rl(p)),d=obj(rl(p+8));
		t.compressed=c instanceof Q3.Storage?Q3.ref((Q3.Storage)c):null;t.descByteOrder=rl(p+4);t.imageDesc=d instanceof Q3.Storage?Q3.ref((Q3.Storage)d):null;
		t.makeMipmaps=rl(p+12)!=0;t.width=rl(p+16);t.height=rl(p+20);t.pixelSize=rl(p+24);t.pixelType=rl(p+28);
	}

	/** A texture's image as the rasterizer wants it (ARGB levels), converted once per edit. */
	RaveRenderer.Texture texture(Q3.Texture t){
		if(t==null)return null;
		long key=(long)t.editIndex<<32|(t.pixmap!=null&&t.pixmap.image!=null?t.pixmap.image.editIndex:t.mipImage!=null?t.mipImage.editIndex:0);
		if(t.cache!=null&&t.cacheKey==key)return (RaveRenderer.Texture)t.cache;
		RaveRenderer.Texture r=null;
		try{r=convertTexture(t);}catch(Exception e){System.err.println("[qd3d] texture: "+e);}
		t.cache=r;t.cacheKey=key;
		return r;
	}
	private RaveRenderer.Texture convertTexture(Q3.Texture t) throws Exception {
		if(t.pixmap!=null){
			Q3.StoragePixmap pm=t.pixmap;if(pm.image==null||pm.width<=0||pm.height<=0)return null;
			byte[] b=pm.image.bytes();
			RaveRenderer.Texture r=new RaveRenderer.Texture(1);
			r.widths[0]=pm.width;r.heights[0]=pm.height;r.pixels[0]=decode(b,0,pm.width,pm.height,pm.rowBytes,pm.pixelType,pm.byteOrder);
			r.updateAlpha();return r;
		}
		if(t.levels.length>0&&t.mipImage!=null){
			byte[] b=t.mipImage.bytes();
			// mipmap levels as the rasterizer walks them: each half the one before, down to 1x1
			int n=t.levels.length;int w=t.levels[0].width,h=t.levels[0].height;int full=1;{int ww=w,hh=h;while(ww>1||hh>1){ww=Math.max(1,ww/2);hh=Math.max(1,hh/2);full++;}}
			boolean complete=t.useMipmapping&&n>=full;
			RaveRenderer.Texture r=new RaveRenderer.Texture(complete?full:1);
			for(int l=0;l<(complete?full:1);l++){Q3.MipLevel ml=t.levels[l];r.widths[l]=ml.width;r.heights[l]=ml.height;r.pixels[l]=decode(b,ml.offset,ml.width,ml.height,ml.rowBytes,t.pixelType,t.byteOrder);}
			r.updateAlpha();return r;
		}
		return null;
	}
	/** Pixels of a QuickDraw 3D pixmap as ARGB, rows top down. */
	static int[] decode(byte[] b,int offset,int w,int h,int rowBytes,int type,int byteOrder){
		int[] p=new int[w*h];boolean little=byteOrder==1;
		for(int y=0;y<h;y++){int row=offset+y*rowBytes;
			for(int x=0;x<w;x++){int v;
				switch(type){
					case Q3.PIXEL_RGB32:case Q3.PIXEL_ARGB32:{int o=row+4*x;if(o+3>=b.length){v=0;break;}
						v=little?(b[o+3]&0xff)<<24|(b[o+2]&0xff)<<16|(b[o+1]&0xff)<<8|b[o]&0xff:(b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;
						if(type==Q3.PIXEL_RGB32)v|=0xff000000;break;}
					case Q3.PIXEL_RGB24:{int o=row+3*x;if(o+2>=b.length){v=0;break;}v=little?0xff000000|(b[o+2]&0xff)<<16|(b[o+1]&0xff)<<8|b[o]&0xff:0xff000000|(b[o]&0xff)<<16|(b[o+1]&0xff)<<8|b[o+2]&0xff;break;}
					default:{int o=row+2*x;if(o+1>=b.length){v=0;break;}int s=little?(b[o+1]&0xff)<<8|b[o]&0xff:(b[o]&0xff)<<8|b[o+1]&0xff;
						if(type==Q3.PIXEL_RGB16_565){int r=s>>>11,g=(s>>>5)&63,bl=s&31;v=0xff000000|(r<<3|r>>>2)<<16|(g<<2|g>>>4)<<8|(bl<<3|bl>>>2);}
						else{int r=(s>>>10)&31,g=(s>>>5)&31,bl=s&31;v=(type==Q3.PIXEL_ARGB16&&(s&0x8000)==0?0:0xff000000)|(r<<3|r>>>2)<<16|(g<<3|g>>>2)<<8|(bl<<3|bl>>>2);}
						break;}
				}
				p[y*w+x]=v;
			}
		}
		return p;
	}

	// ================================================================== lights

	private Q3.Light light(A a,int type) throws M68kCpuException {Q3.Light l=get(a.i(0),Q3.Light.class);if(l!=null&&type!=0&&l.leaf()!=type){post(ERR_INVALID_OBJECT_TYPE);return null;}return l;}
	private void readLightData(int p,Q3.Light l) throws M68kCpuException {l.on=rl(p)!=0;l.brightness=rf(p+4);rfs(p+8,l.color,0,3);}
	private void writeLightData(int p,Q3.Light l) throws M68kCpuException {wl(p,l.on?1:0);wf(p+4,l.brightness);wfs(p+8,l.color,0,3);}
	void readLight(int p,Q3.Light l) throws M68kCpuException {
		readLightData(p,l);int t=l.leaf();
		if(t==Q3.DIRECTIONAL){l.castsShadows=rl(p+20)!=0;rfs(p+24,l.direction,0,3);}
		else if(t==Q3.POINT_LIGHT){l.castsShadows=rl(p+20)!=0;l.attenuation=rl(p+24);rfs(p+28,l.location,0,3);}
		else if(t==Q3.SPOT){l.castsShadows=rl(p+20)!=0;l.attenuation=rl(p+24);rfs(p+28,l.location,0,3);rfs(p+40,l.direction,0,3);l.hotAngle=rf(p+52);l.outerAngle=rf(p+56);l.fallOff=rl(p+60);}
	}
	void writeLight(int p,Q3.Light l) throws M68kCpuException {
		writeLightData(p,l);int t=l.leaf();
		if(t==Q3.DIRECTIONAL){wl(p+20,l.castsShadows?1:0);wfs(p+24,l.direction,0,3);}
		else if(t==Q3.POINT_LIGHT){wl(p+20,l.castsShadows?1:0);wl(p+24,l.attenuation);wfs(p+28,l.location,0,3);}
		else if(t==Q3.SPOT){wl(p+20,l.castsShadows?1:0);wl(p+24,l.attenuation);wfs(p+28,l.location,0,3);wfs(p+40,l.direction,0,3);wf(p+52,l.hotAngle);wf(p+56,l.outerAngle);wl(p+60,l.fallOff);}
	}
	private void defineLights(){
		def("Q3Light_GetType",a->typeAt(a,Q3.LIGHT,3));
		def("Q3Light_GetState",a->{Q3.Light l=light(a,0);if(l==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),l.on?1:0);a.ok(true);});
		def("Q3Light_GetBrightness",a->{Q3.Light l=light(a,0);if(l==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),l.brightness);a.ok(true);});
		def("Q3Light_GetColor",a->{Q3.Light l=light(a,0);if(l==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),l.color,0,3);a.ok(true);});
		def("Q3Light_SetState",a->{Q3.Light l=light(a,0);if(l==null){a.ok(false);return;}l.on=a.i(1)!=0;l.edited();a.ok(true);});
		def("Q3Light_SetBrightness",a->{Q3.Light l=light(a,0);if(l==null){a.ok(false);return;}l.brightness=a.f(1);if(l.brightness>1)notice(-28089);l.edited();a.ok(true);});
		def("Q3Light_SetColor",a->{Q3.Light l=light(a,0);if(l==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),l.color,0,3);l.edited();a.ok(true);});
		def("Q3Light_GetData",a->{Q3.Light l=light(a,0);if(l==null||a.i(1)==0){a.ok(false);return;}writeLightData(a.i(1),l);a.ok(true);});
		def("Q3Light_SetData",a->{Q3.Light l=light(a,0);if(l==null||a.i(1)==0){a.ok(false);return;}readLightData(a.i(1),l);l.edited();a.ok(true);});
		String[][] kinds={{"AmbientLight","ambn"},{"DirectionalLight","drct"},{"PointLight","pntl"},{"SpotLight","spot"}};
		for(String[] k:kinds){
			int type=Q3.fc(k[1]);
			def("Q3"+k[0]+"_New",a->{if(a.i(0)==0){post(ERR_NULL_PARAMETER);a.ret(0);return;}Q3.Light l=new Q3.Light(type);readLight(a.i(0),l);a.ret(made(l));});
			def("Q3"+k[0]+"_GetData",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}writeLight(a.i(1),l);a.ok(true);});
			def("Q3"+k[0]+"_SetData",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}readLight(a.i(1),l);l.edited();a.ok(true);});
			if(type==Q3.AMBIENT)continue;
			def("Q3"+k[0]+"_GetCastShadowsState",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),l.castsShadows?1:0);a.ok(true);});
			def("Q3"+k[0]+"_SetCastShadowsState",a->{Q3.Light l=light(a,type);if(l==null){a.ok(false);return;}l.castsShadows=a.i(1)!=0;l.edited();a.ok(true);});
			if(type!=Q3.DIRECTIONAL){
				def("Q3"+k[0]+"_GetAttenuation",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),l.attenuation);a.ok(true);});
				def("Q3"+k[0]+"_SetAttenuation",a->{Q3.Light l=light(a,type);if(l==null){a.ok(false);return;}l.attenuation=a.i(1);l.edited();a.ok(true);});
				def("Q3"+k[0]+"_GetLocation",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),l.location,0,3);a.ok(true);});
				def("Q3"+k[0]+"_SetLocation",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),l.location,0,3);l.edited();a.ok(true);});
			}
			if(type!=Q3.POINT_LIGHT){
				def("Q3"+k[0]+"_GetDirection",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),l.direction,0,3);a.ok(true);});
				def("Q3"+k[0]+"_SetDirection",a->{Q3.Light l=light(a,type);if(l==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),l.direction,0,3);l.edited();a.ok(true);});
			}
		}
		def("Q3SpotLight_GetHotAngle",a->{Q3.Light l=light(a,Q3.SPOT);if(l==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),l.hotAngle);a.ok(true);});
		def("Q3SpotLight_GetOuterAngle",a->{Q3.Light l=light(a,Q3.SPOT);if(l==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),l.outerAngle);a.ok(true);});
		def("Q3SpotLight_GetFallOff",a->{Q3.Light l=light(a,Q3.SPOT);if(l==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),l.fallOff);a.ok(true);});
		def("Q3SpotLight_SetHotAngle",a->{Q3.Light l=light(a,Q3.SPOT);if(l==null){a.ok(false);return;}l.hotAngle=a.f(1);l.edited();a.ok(true);});
		def("Q3SpotLight_SetOuterAngle",a->{Q3.Light l=light(a,Q3.SPOT);if(l==null){a.ok(false);return;}l.outerAngle=a.f(1);l.edited();a.ok(true);});
		def("Q3SpotLight_SetFallOff",a->{Q3.Light l=light(a,Q3.SPOT);if(l==null){a.ok(false);return;}l.fallOff=a.i(1);l.edited();a.ok(true);});
	}

	// ================================================================== cameras

	private Q3.Camera cam(A a,int type) throws M68kCpuException {Q3.Camera c=get(a.i(0),Q3.Camera.class);if(c!=null&&type!=0&&c.leaf()!=type){post(ERR_INVALID_OBJECT_TYPE);return null;}return c;}
	void readCameraData(int p,Q3.Camera c) throws M68kCpuException {
		rfs(p,c.location,0,3);rfs(p+12,c.pointOfInterest,0,3);rfs(p+24,c.up,0,3);c.hither=rf(p+36);c.yon=rf(p+40);
		c.vpX=rf(p+44);c.vpY=rf(p+48);c.vpWidth=rf(p+52);c.vpHeight=rf(p+56);
	}
	void writeCameraData(int p,Q3.Camera c) throws M68kCpuException {
		wfs(p,c.location,0,3);wfs(p+12,c.pointOfInterest,0,3);wfs(p+24,c.up,0,3);wf(p+36,c.hither);wf(p+40,c.yon);
		wf(p+44,c.vpX);wf(p+48,c.vpY);wf(p+52,c.vpWidth);wf(p+56,c.vpHeight);
	}
	void readCamera(int p,Q3.Camera c) throws M68kCpuException {
		readCameraData(p,c);int t=c.leaf();
		if(t==Q3.ORTHOGRAPHIC){c.left=rf(p+60);c.top=rf(p+64);c.right=rf(p+68);c.bottom=rf(p+72);}
		else if(t==Q3.VIEW_PLANE){c.viewPlane=rf(p+60);c.halfWidth=rf(p+64);c.halfHeight=rf(p+68);c.centerX=rf(p+72);c.centerY=rf(p+76);}
		else if(t==Q3.VIEW_ANGLE_ASPECT){c.fov=rf(p+60);c.aspect=rf(p+64);}
	}
	void writeCamera(int p,Q3.Camera c) throws M68kCpuException {
		writeCameraData(p,c);int t=c.leaf();
		if(t==Q3.ORTHOGRAPHIC){wf(p+60,c.left);wf(p+64,c.top);wf(p+68,c.right);wf(p+72,c.bottom);}
		else if(t==Q3.VIEW_PLANE){wf(p+60,c.viewPlane);wf(p+64,c.halfWidth);wf(p+68,c.halfHeight);wf(p+72,c.centerX);wf(p+76,c.centerY);}
		else if(t==Q3.VIEW_ANGLE_ASPECT){wf(p+60,c.fov);wf(p+64,c.aspect);}
	}
	/** A float field of a camera kind: Get writes it, Set reads it from the float argument. */
	interface CamField { float get(Q3.Camera c); void set(Q3.Camera c,float v); }
	private void camField(String name,int type,CamField f){
		def("Q3"+name.replace("#","Set"),a->{Q3.Camera c=cam(a,type);if(c==null){a.ok(false);return;}f.set(c,a.f(1));c.edited();a.ok(true);});
		def("Q3"+name.replace("#","Get"),a->{Q3.Camera c=cam(a,type);if(c==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),f.get(c));a.ok(true);});
	}
	private void defineCameras(){
		def("Q3Camera_GetType",a->typeAt(a,Q3.CAMERA,3));
		def("Q3Camera_SetData",a->{Q3.Camera c=cam(a,0);if(c==null||a.i(1)==0){a.ok(false);return;}readCameraData(a.i(1),c);c.edited();a.ok(true);});
		def("Q3Camera_GetData",a->{Q3.Camera c=cam(a,0);if(c==null||a.i(1)==0){a.ok(false);return;}writeCameraData(a.i(1),c);a.ok(true);});
		def("Q3Camera_SetPlacement",a->{Q3.Camera c=cam(a,0);int p=a.i(1);if(c==null||p==0){a.ok(false);return;}rfs(p,c.location,0,3);rfs(p+12,c.pointOfInterest,0,3);rfs(p+24,c.up,0,3);c.edited();a.ok(true);});
		def("Q3Camera_GetPlacement",a->{Q3.Camera c=cam(a,0);int p=a.i(1);if(c==null||p==0){a.ok(false);return;}wfs(p,c.location,0,3);wfs(p+12,c.pointOfInterest,0,3);wfs(p+24,c.up,0,3);a.ok(true);});
		def("Q3Camera_SetRange",a->{Q3.Camera c=cam(a,0);int p=a.i(1);if(c==null||p==0){a.ok(false);return;}c.hither=rf(p);c.yon=rf(p+4);c.edited();a.ok(true);});
		def("Q3Camera_GetRange",a->{Q3.Camera c=cam(a,0);int p=a.i(1);if(c==null||p==0){a.ok(false);return;}wf(p,c.hither);wf(p+4,c.yon);a.ok(true);});
		def("Q3Camera_SetViewPort",a->{Q3.Camera c=cam(a,0);int p=a.i(1);if(c==null||p==0){a.ok(false);return;}c.vpX=rf(p);c.vpY=rf(p+4);c.vpWidth=rf(p+8);c.vpHeight=rf(p+12);c.edited();a.ok(true);});
		def("Q3Camera_GetViewPort",a->{Q3.Camera c=cam(a,0);int p=a.i(1);if(c==null||p==0){a.ok(false);return;}wf(p,c.vpX);wf(p+4,c.vpY);wf(p+8,c.vpWidth);wf(p+12,c.vpHeight);a.ok(true);});
		def("Q3Camera_GetWorldToView",a->{Q3.Camera c=cam(a,0);if(c==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),c.worldToView(),0,16);a.ok(true);});
		def("Q3Camera_GetWorldToFrustum",a->{Q3.Camera c=cam(a,0);if(c==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),c.worldToFrustum(),0,16);a.ok(true);});
		def("Q3Camera_GetViewToFrustum",a->{Q3.Camera c=cam(a,0);if(c==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),c.viewToFrustum(false),0,16);a.ok(true);});
		String[][] kinds={{"OrthographicCamera","orth"},{"ViewPlaneCamera","vwpl"},{"ViewAngleAspectCamera","vana"}};
		for(String[] k:kinds){
			int type=Q3.fc(k[1]);
			def("Q3"+k[0]+"_New",a->{if(a.i(0)==0){post(ERR_NULL_PARAMETER);a.ret(0);return;}Q3.Camera c=new Q3.Camera(type);readCamera(a.i(0),c);a.ret(made(c));});
			def("Q3"+k[0]+"_GetData",a->{Q3.Camera c=cam(a,type);if(c==null||a.i(1)==0){a.ok(false);return;}writeCamera(a.i(1),c);a.ok(true);});
			def("Q3"+k[0]+"_SetData",a->{Q3.Camera c=cam(a,type);if(c==null||a.i(1)==0){a.ok(false);return;}readCamera(a.i(1),c);c.edited();a.ok(true);});
		}
		camField("OrthographicCamera_#Left",Q3.ORTHOGRAPHIC,new CamField(){public float get(Q3.Camera c){return c.left;}public void set(Q3.Camera c,float v){c.left=v;}});
		camField("OrthographicCamera_#Top",Q3.ORTHOGRAPHIC,new CamField(){public float get(Q3.Camera c){return c.top;}public void set(Q3.Camera c,float v){c.top=v;}});
		camField("OrthographicCamera_#Right",Q3.ORTHOGRAPHIC,new CamField(){public float get(Q3.Camera c){return c.right;}public void set(Q3.Camera c,float v){c.right=v;}});
		camField("OrthographicCamera_#Bottom",Q3.ORTHOGRAPHIC,new CamField(){public float get(Q3.Camera c){return c.bottom;}public void set(Q3.Camera c,float v){c.bottom=v;}});
		camField("ViewPlaneCamera_#ViewPlane",Q3.VIEW_PLANE,new CamField(){public float get(Q3.Camera c){return c.viewPlane;}public void set(Q3.Camera c,float v){c.viewPlane=v;}});
		camField("ViewPlaneCamera_#HalfWidth",Q3.VIEW_PLANE,new CamField(){public float get(Q3.Camera c){return c.halfWidth;}public void set(Q3.Camera c,float v){c.halfWidth=v;}});
		camField("ViewPlaneCamera_#HalfHeight",Q3.VIEW_PLANE,new CamField(){public float get(Q3.Camera c){return c.halfHeight;}public void set(Q3.Camera c,float v){c.halfHeight=v;}});
		camField("ViewPlaneCamera_#CenterX",Q3.VIEW_PLANE,new CamField(){public float get(Q3.Camera c){return c.centerX;}public void set(Q3.Camera c,float v){c.centerX=v;}});
		camField("ViewPlaneCamera_#CenterY",Q3.VIEW_PLANE,new CamField(){public float get(Q3.Camera c){return c.centerY;}public void set(Q3.Camera c,float v){c.centerY=v;}});
		camField("ViewAngleAspectCamera_#FOV",Q3.VIEW_ANGLE_ASPECT,new CamField(){public float get(Q3.Camera c){return c.fov;}public void set(Q3.Camera c,float v){c.fov=v;}});
		camField("ViewAngleAspectCamera_#AspectRatio",Q3.VIEW_ANGLE_ASPECT,new CamField(){public float get(Q3.Camera c){return c.aspect;}public void set(Q3.Camera c,float v){c.aspect=v;}});
	}

	// ================================================================== draw contexts

	private Q3.DrawContext dc(A a,int type) throws M68kCpuException {Q3.DrawContext d=get(a.i(0),Q3.DrawContext.class);if(d!=null&&type!=0&&d.leaf()!=type){post(ERR_BAD_DRAW_CONTEXT);return null;}return d;}
	void readDrawContextData(int p,Q3.DrawContext d) throws M68kCpuException {
		d.clearMethod=rl(p);rfs(p+4,d.clearColor,0,4);rfs(p+20,d.pane,0,4);d.paneState=rl(p+36)!=0;
		d.maskState=rl(p+60)!=0;d.doubleBuffer=rl(p+64)!=0;
		d.mask=d.maskState?readBitmap(p+40):null; // programs leave the mask uninitialised when it is off (Nanosaur)
	}
	void writeDrawContextData(int p,Q3.DrawContext d) throws M68kCpuException {
		wl(p,d.clearMethod);wfs(p+4,d.clearColor,0,4);wfs(p+20,d.pane,0,4);wl(p+36,d.paneState?1:0);
		writeBitmap(p+40,d.mask);wl(p+60,d.maskState?1:0);wl(p+64,d.doubleBuffer?1:0);
	}
	/** A TQ3Bitmap (image, width, height, rowBytes, bitOrder) copied out of guest memory; null when it has no image. */
	Q3.Bitmap readBitmap(int p) throws M68kCpuException {
		int image=rl(p);if(image==0)return null;
		Q3.Bitmap b=new Q3.Bitmap();b.width=rl(p+4);b.height=rl(p+8);b.rowBytes=rl(p+12);b.bitOrder=rl(p+16);
		long size=(long)b.rowBytes*b.height;if(b.rowBytes<0||b.height<0||size>1<<24){post(ERR_INVALID_PARAMETER);return null;}
		b.bits=memory.readBytes(image,(int)size);return b;
	}
	/** Writes a TQ3Bitmap with a fresh copy of the image (the caller empties it with Q3Bitmap_Empty). */
	void writeBitmap(int p,Q3.Bitmap b) throws M68kCpuException {
		if(b==null||b.bits==null){for(int i=0;i<20;i+=4)wl(p+i,0);return;}
		int image=alloc(b.bits.length);memory.load(image,b.bits);wl(p,image);wl(p+4,b.width);wl(p+8,b.height);wl(p+12,b.rowBytes);wl(p+16,b.bitOrder);
	}
	private void defineDrawContexts(){
		def("Q3DrawContext_GetType",a->typeAt(a,Q3.DRAW_CONTEXT,2));
		def("Q3DrawContext_SetData",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}readDrawContextData(a.i(1),d);d.edited();a.ok(true);});
		def("Q3DrawContext_GetData",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}writeDrawContextData(a.i(1),d);a.ok(true);});
		def("Q3DrawContext_SetClearImageColor",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),d.clearColor,0,4);d.edited();a.ok(true);});
		def("Q3DrawContext_GetClearImageColor",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),d.clearColor,0,4);a.ok(true);});
		def("Q3DrawContext_SetPane",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),d.pane,0,4);d.paneState=true;d.edited();a.ok(true);});
		def("Q3DrawContext_GetPane",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}
			float[] p=d.paneState?d.pane:defaultPane(d);wfs(a.i(1),p,0,4);a.ok(true);});
		def("Q3DrawContext_SetPaneState",a->{Q3.DrawContext d=dc(a,0);if(d==null){a.ok(false);return;}d.paneState=a.i(1)!=0;d.edited();a.ok(true);});
		def("Q3DrawContext_GetPaneState",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.paneState?1:0);a.ok(true);});
		def("Q3DrawContext_SetClearImageMethod",a->{Q3.DrawContext d=dc(a,0);if(d==null){a.ok(false);return;}d.clearMethod=a.i(1);d.edited();a.ok(true);});
		def("Q3DrawContext_GetClearImageMethod",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.clearMethod);a.ok(true);});
		def("Q3DrawContext_SetMask",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}d.mask=readBitmap(a.i(1));d.edited();a.ok(true);});
		def("Q3DrawContext_GetMask",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}writeBitmap(a.i(1),d.mask);a.ok(true);});
		def("Q3DrawContext_SetMaskState",a->{Q3.DrawContext d=dc(a,0);if(d==null){a.ok(false);return;}d.maskState=a.i(1)!=0;d.edited();a.ok(true);});
		def("Q3DrawContext_GetMaskState",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.maskState?1:0);a.ok(true);});
		def("Q3DrawContext_SetDoubleBufferState",a->{Q3.DrawContext d=dc(a,0);if(d==null){a.ok(false);return;}d.doubleBuffer=a.i(1)!=0;d.edited();a.ok(true);});
		def("Q3DrawContext_GetDoubleBufferState",a->{Q3.DrawContext d=dc(a,0);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.doubleBuffer?1:0);a.ok(true);});
		def("Q3PixmapDrawContext_New",a->{int p=a.i(0);if(p==0){post(ERR_NULL_PARAMETER);a.ret(0);return;}Q3.DrawContext d=new Q3.DrawContext(Q3.PIXMAP_DRAW_CONTEXT);
			readDrawContextData(p,d);readPixmap(p+68,d);a.ret(made(d));});
		def("Q3PixmapDrawContext_SetPixmap",a->{Q3.DrawContext d=dc(a,Q3.PIXMAP_DRAW_CONTEXT);if(d==null||a.i(1)==0){a.ok(false);return;}readPixmap(a.i(1),d);d.edited();a.ok(true);});
		def("Q3PixmapDrawContext_GetPixmap",a->{Q3.DrawContext d=dc(a,Q3.PIXMAP_DRAW_CONTEXT);int p=a.i(1);if(d==null||p==0){a.ok(false);return;}
			wl(p,d.image);wl(p+4,d.width);wl(p+8,d.height);wl(p+12,d.rowBytes);wl(p+16,d.pixelSize);wl(p+20,d.pixelType);wl(p+24,d.bitOrder);wl(p+28,d.byteOrder);a.ok(true);});
		def("Q3MacDrawContext_New",a->{int p=a.i(0);if(p==0){post(ERR_NULL_PARAMETER);a.ret(0);return;}Q3.DrawContext d=new Q3.DrawContext(Q3.MAC_DRAW_CONTEXT);
			readDrawContextData(p,d);d.window=rl(p+68);d.library=rl(p+72);d.gxViewPort=rl(p+76);d.grafPort=rl(p+80);a.ret(made(d));});
		def("Q3MacDrawContext_SetWindow",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null){a.ok(false);return;}d.window=a.i(1);d.edited();a.ok(true);});
		def("Q3MacDrawContext_GetWindow",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.window);a.ok(true);});
		def("Q3MacDrawContext_SetGXViewPort",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null){a.ok(false);return;}d.gxViewPort=a.i(1);d.edited();a.ok(true);});
		def("Q3MacDrawContext_GetGXViewPort",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.gxViewPort);a.ok(true);});
		def("Q3MacDrawContext_SetGrafPort",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null){a.ok(false);return;}d.grafPort=a.i(1);d.edited();a.ok(true);});
		def("Q3MacDrawContext_GetGrafPort",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.grafPort);a.ok(true);});
		def("Q3MacDrawContext_Set2DLibrary",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null){a.ok(false);return;}d.library=a.i(1);d.edited();a.ok(true);});
		def("Q3MacDrawContext_Get2DLibrary",a->{Q3.DrawContext d=dc(a,Q3.MAC_DRAW_CONTEXT);if(d==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),d.library);a.ok(true);});
		def("Q3XDrawContext_GetDrawRegion",a->{post(ERR_UNIMPLEMENTED);a.ok(false);});
		def("Q3XDrawContext_ClearValidationFlags",a->a.ok(true));
		def("Q3XDrawContext_GetValidationFlags",a->{if(a.i(1)!=0)wl(a.i(1),0);a.ok(true);});
	}
	private void readPixmap(int p,Q3.DrawContext d) throws M68kCpuException {
		d.image=rl(p);d.width=rl(p+4);d.height=rl(p+8);d.rowBytes=rl(p+12);d.pixelSize=rl(p+16);d.pixelType=rl(p+20);d.bitOrder=rl(p+24);d.byteOrder=rl(p+28);
	}
	/** The pane a draw context has without one set: its window's port rectangle or its pixmap, in window coordinates. */
	float[] defaultPane(Q3.DrawContext d) throws M68kCpuException {
		if(d.leaf()==Q3.PIXMAP_DRAW_CONTEXT)return new float[]{0,0,d.width,d.height};
		int port=d.window!=0?d.window:d.grafPort;
		if(port==0)return new float[]{0,0,0,0};
		int top=(short)memory.readWord(port+16),left=(short)memory.readWord(port+18),bottom=(short)memory.readWord(port+20),right=(short)memory.readWord(port+22);
		return new float[]{left,top,right,bottom};
	}

	// ================================================================== renderers (the Interactive Renderer's RAVE contexts)

	/** What a renderer keeps: the RAVE context it draws into, its size, and the program's destroy callback. */
	final class RendererState {
		int context,width,height,flags;Q3.DrawContext dc;int destroyCallback;Q3View view;
	}
	private RendererState state(Q3.Renderer r){if(!(r.state instanceof RendererState))r.state=new RendererState();return (RendererState)r.state;}
	private void dropRenderer(Q3.Renderer r){
		if(!(r.state instanceof RendererState))return;
		try{dropContext(r,(RendererState)r.state);}catch(Exception e){System.err.println("[qd3d] renderer: "+e);}
	}
	private void dropContext(Q3.Renderer r,RendererState st) throws M68kException {
		if(st.context==0)return;
		if(st.destroyCallback!=0)rt.cpu.call(st.destroyCallback,r.handle);
		libs.rave.hostDelete(st.context);st.context=0;
	}
	/** The RAVE context a renderer draws a draw context with (made, or made again at a new size). */
	int context(Q3.Renderer r,Q3.DrawContext d,int w,int h,Q3View v) throws M68kException {
		RendererState st=state(r);st.view=v;
		int flags=r.raveHints;
		if(st.context!=0&&(st.width!=w||st.height!=h||st.dc!=d||st.flags!=flags))dropContext(r,st);
		if(st.context==0&&w>0&&h>0){
			final Q3.Renderer rr=r;
			st.context=libs.rave.hostContextNew(w,h,flags,()->present(rr));
			if(TRACE){float[] pane=d.paneState?d.pane:defaultPane(d);System.err.printf("[qd3d] RAVE context %08X %dx%d flags %X pane %s%n",st.context,w,h,flags,java.util.Arrays.toString(pane));}
			st.width=w;st.height=h;st.dc=d;st.flags=flags;
			if(st.context!=0){
				libs.rave.setHostTag(st.context,11,r.textureFilter); // kQATag_TextureFilter
			}
		}
		return st.context;
	}
	/** Shows a renderer's frame in its draw context (the RAVE context's presenter). */
	private void present(Q3.Renderer r) throws M68kException {
		RendererState st=state(r);if(st.context==0||st.dc==null)return;
		Q3.DrawContext d=st.dc;
		if(d.leaf()==Q3.PIXMAP_DRAW_CONTEXT){writePixmap(st,d);return;}
		int port=d.window!=0?d.window:d.grafPort;if(port==0)return;
		int pm=libs.rave.hostPixMap(st.context);if(pm==0)return;
		float[] pane=d.paneState?d.pane:defaultPane(d);
		int save=alloc(8+16+12);
		try{
			tb("GetPort",save);
			tb("SetPort",port);
			int fg=save+24,bg=save+30;tb("GetForeColor",fg);tb("GetBackColor",bg);
			int black=alloc(6),white=alloc(6);memory.writeWord(white,0xffff);memory.writeWord(white+2,0xffff);memory.writeWord(white+4,0xffff);
			tb("RGBForeColor",black);tb("RGBBackColor",white);
			int src=save+8,dst=save+16;
			memory.writeWord(src,0);memory.writeWord(src+2,0);memory.writeWord(src+4,st.height);memory.writeWord(src+6,st.width);
			int l=Math.round(pane[0]),t=Math.round(pane[1]);
			memory.writeWord(dst,t);memory.writeWord(dst+2,l);memory.writeWord(dst+4,t+st.height);memory.writeWord(dst+6,l+st.width);
			tb("CopyBits",memory.readLong(pm),port+2,src,dst,0,0);
			tb("RGBForeColor",fg);tb("RGBBackColor",bg);
			free(black);free(white);
			tb("SetPort",memory.readLong(save));
		}finally{free(save);}
	}
	/** Writes the frame into a pixmap draw context's image in its pixel type. */
	private void writePixmap(RendererState st,Q3.DrawContext d) throws M68kCpuException {
		RaveRenderer r=libs.rave.renderer(st.context);if(r==null||d.image==0)return;
		float[] pane=d.paneState?d.pane:new float[]{0,0,d.width,d.height};
		int ox=Math.round(pane[0]),oy=Math.round(pane[1]);
		int bpp=d.pixelType==Q3.PIXEL_RGB24?3:d.pixelType>=Q3.PIXEL_RGB16?2:4;boolean little=d.byteOrder==1;
		byte[] row=new byte[r.width*bpp];
		for(int y=0;y<r.height;y++){
			int py=oy+y;if(py<0||py>=d.height)continue;
			int x0=Math.max(0,-ox),x1=Math.min(r.width,d.width-ox);if(x1<=x0)continue;
			for(int x=x0;x<x1;x++){int c=r.color[y*r.width+x];int o=(x-x0)*bpp;
				switch(d.pixelType){
					case Q3.PIXEL_RGB32:case Q3.PIXEL_ARGB32:{int v=d.pixelType==Q3.PIXEL_RGB32?c&0xffffff:c;if(little){row[o]=(byte)v;row[o+1]=(byte)(v>>>8);row[o+2]=(byte)(v>>>16);row[o+3]=(byte)(v>>>24);}else{row[o]=(byte)(v>>>24);row[o+1]=(byte)(v>>>16);row[o+2]=(byte)(v>>>8);row[o+3]=(byte)v;}break;}
					case Q3.PIXEL_RGB24:{if(little){row[o]=(byte)c;row[o+1]=(byte)(c>>>8);row[o+2]=(byte)(c>>>16);}else{row[o]=(byte)(c>>>16);row[o+1]=(byte)(c>>>8);row[o+2]=(byte)c;}break;}
					default:{int v;if(d.pixelType==Q3.PIXEL_RGB16_565)v=((c>>>19)&31)<<11|((c>>>10)&63)<<5|(c>>>3)&31;
						else{v=((c>>>19)&31)<<10|((c>>>11)&31)<<5|(c>>>3)&31;if(d.pixelType==Q3.PIXEL_ARGB16&&(c>>>24)>=128)v|=0x8000;}
						if(little){row[o]=(byte)v;row[o+1]=(byte)(v>>>8);}else{row[o]=(byte)(v>>>8);row[o+1]=(byte)v;}break;}
				}
			}
			memory.load(d.image+py*d.rowBytes+(ox+x0)*bpp,row,0,(x1-x0)*bpp);
		}
	}
	/** Reads a pixmap draw context's image into the frame (drawing over what it holds when nothing clears it). */
	private void readPixmap(RendererState st,Q3.DrawContext d) throws M68kCpuException {
		RaveRenderer r=libs.rave.renderer(st.context);if(r==null||d.image==0)return;
		float[] pane=d.paneState?d.pane:new float[]{0,0,d.width,d.height};
		int ox=Math.round(pane[0]),oy=Math.round(pane[1]);
		int bpp=d.pixelType==Q3.PIXEL_RGB24?3:d.pixelType>=Q3.PIXEL_RGB16?2:4;
		for(int y=0;y<r.height;y++){int py=oy+y;if(py<0||py>=d.height)continue;
			int x0=Math.max(0,-ox),x1=Math.min(r.width,d.width-ox);if(x1<=x0)continue;
			byte[] b=memory.readBytes(d.image+py*d.rowBytes+(ox+x0)*bpp,(x1-x0)*bpp);
			int[] px=decode(b,0,x1-x0,1,b.length,d.pixelType,d.byteOrder);
			System.arraycopy(px,0,r.color,y*r.width+x0,x1-x0);}
	}

	private void defineRenderers(){
		def("Q3Renderer_NewFromType",a->{int t=a.i(0);if(t!=Q3.INTERACTIVE&&t!=Q3.WIREFRAME&&t!=Q3.GENERIC){post(-28405);a.ret(0);return;}a.ret(made(new Q3.Renderer(t)));});
		def("Q3Renderer_GetType",a->typeAt(a,Q3.RENDERER,2));
		def("Q3Renderer_IsInteractive",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);a.ret(r!=null&&r.leaf()!=Q3.GENERIC?1:0);});
		def("Q3Renderer_HasModalConfigure",a->a.ret(0));
		def("Q3Renderer_ModalConfigure",a->{if(a.i(2)!=0)wl(a.i(2),1);a.ok(false);});
		def("Q3RendererClass_GetNickNameString",a->{int t=a.i(0),out=a.i(1);String n=t==Q3.INTERACTIVE?"Interactive":t==Q3.WIREFRAME?"WireFrame":t==Q3.GENERIC?"Generic":null;
			if(out!=0&&n!=null){memory.load(out,n.getBytes(MAC_ROMAN));memory.writeByte(out+n.length(),0);}a.ok(n!=null);});
		def("Q3Renderer_Sync",a->a.ok(true)); // QuickDraw 3D 1.0: waits for the renderer to finish; drawing is synchronous here
		def("Q3ObjectClass_Unregister",a->a.ok(true)); // 1.0 name of Q3XObjectClass_Unregister; nothing is kept per class that needs releasing
		def("Q3Renderer_GetConfigurationData",a->{if(a.i(3)!=0)wl(a.i(3),0);a.ok(true);});
		def("Q3Renderer_SetConfigurationData",a->a.ok(true));
		def("Q3InteractiveRenderer_SetCSGEquation",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null){a.ok(false);return;}r.csgEquation=a.i(1);a.ok(true);});
		def("Q3InteractiveRenderer_GetCSGEquation",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),r.csgEquation);a.ok(true);});
		def("Q3InteractiveRenderer_SetPreferences",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null){a.ok(false);return;}r.vendor=a.i(1);r.engine=a.i(2);a.ok(true);});
		def("Q3InteractiveRenderer_GetPreferences",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null){a.ok(false);return;}
			if(a.i(1)!=0)wl(a.i(1),r.vendor==-1?PpcRave.VENDOR:r.vendor);if(a.i(2)!=0)wl(a.i(2),r.engine==-1?PpcRave.ENGINE_ID:r.engine);a.ok(true);});
		def("Q3InteractiveRenderer_SetDoubleBufferBypass",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null){a.ok(false);return;}r.doubleBufferBypass=a.i(1)!=0;a.ok(true);});
		def("Q3InteractiveRenderer_GetDoubleBufferBypass",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),r.doubleBufferBypass?1:0);a.ok(true);});
		def("Q3InteractiveRenderer_SetRAVEContextHints",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null){a.ok(false);return;}r.raveHints=a.i(1)&~1;a.ok(true);}); // the hints never take away the Z buffer here
		def("Q3InteractiveRenderer_GetRAVEContextHints",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),r.raveHints);a.ok(true);});
		def("Q3InteractiveRenderer_SetRAVETextureFilter",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null){a.ok(false);return;}r.textureFilter=a.i(1);
			RendererState st=state(r);if(st.context!=0)libs.rave.setHostTag(st.context,11,r.textureFilter);a.ok(true);});
		def("Q3InteractiveRenderer_GetRAVETextureFilter",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),r.textureFilter);a.ok(true);});
		def("Q3InteractiveRenderer_CountRAVEDrawContexts",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);if(r==null||a.i(1)==0){a.ok(false);return;}
			wl(a.i(1),ensureContext(r)!=0?1:0);a.ok(true);});
		def("Q3InteractiveRenderer_GetRAVEDrawContexts",a->{Q3.Renderer r=get(a.i(0),Q3.Renderer.class);int list=a.i(1),engines=a.i(2),count=a.i(3),callback=a.i(4);
			if(r==null){a.ok(false);return;}
			int ctx=ensureContext(r);
			RendererState st=state(r);if(callback!=0)st.destroyCallback=callback;
			if(ctx==0){if(count!=0)wl(count,0);a.ok(false);return;}
			if(list!=0)wl(list,ctx);if(engines!=0)wl(engines,libs.rave.ownEngine());if(count!=0)wl(count,1);
			a.ok(true);});
	}
	/** The renderer's RAVE context, made now if a view already joins it to a draw context. */
	private int ensureContext(Q3.Renderer r) throws Exception {
		RendererState st=state(r);if(st.context!=0)return st.context;
		Q3View v=st.view;if(v==null||v.drawContext==null)return 0;
		float[] pane=v.drawContext.paneState?v.drawContext.pane:defaultPane(v.drawContext);
		return context(r,v.drawContext,Math.round(pane[2]-pane[0]),Math.round(pane[3]-pane[1]),v);
	}

	// ================================================================== views

	/** The frame surface of a view: its renderer's RAVE context over the draw context's pane. */
	private final class Surface implements Q3View.Surface {
		final Q3View v;float[] pane;
		Surface(Q3View v){this.v=v;}
		@Override public float[] pane(){
			try{Q3.DrawContext d=v.drawContext;if(d==null)return new float[]{0,0,1,1};pane=d.paneState?d.pane.clone():defaultPane(d);return pane;}
			catch(M68kCpuException e){return new float[]{0,0,1,1};}
		}
		@Override public RaveRenderer begin(Q3View view) throws Exception {
			Q3.DrawContext d=v.drawContext;if(d==null||v.renderer==null)return null;
			float[] p=pane();int w=Math.round(p[2]-p[0]),h=Math.round(p[3]-p[1]);
			if(w<=0||h<=0)return null;
			if(d.leaf()==Q3.MAC_DRAW_CONTEXT&&d.window==0&&d.grafPort==0)return null;
			int ctx=context(v.renderer,d,w,h,v);if(ctx==0)return null;
			PpcRave rave=libs.rave;
			// the clear colour is the context's background (kQATag_ColorBG_a..b)
			rave.setHostTag(ctx,1,Float.floatToRawIntBits(d.clearColor[0]));rave.setHostTag(ctx,2,Float.floatToRawIntBits(d.clearColor[1]));
			rave.setHostTag(ctx,3,Float.floatToRawIntBits(d.clearColor[2]));rave.setHostTag(ctx,4,Float.floatToRawIntBits(d.clearColor[3]));
			boolean clear=d.clearMethod==Q3.CLEAR_WITH_COLOR;
			rave.hostStart(ctx,clear);
			if(!clear&&d.leaf()==Q3.PIXMAP_DRAW_CONTEXT)readPixmap(state(v.renderer),d);
			return rave.renderer(ctx);
		}
		@Override public void end(Q3View view) throws Exception {
			RendererState st=state(v.renderer);if(st.context!=0)libs.rave.hostEnd(st.context);
		}
	}
	Q3View view(A a,int k) throws M68kCpuException {return get(a.i(k),Q3View.class);}
	/** A view drawing into its draw context through this library's renderer (Q3View_New, and the Viewer's own). */
	Q3View newView(){Q3View v=new Q3View();v.surface=new Surface(v);v.textures=this::texture;return v;}
	/** Joins a renderer to a view (Q3View_SetRenderer). */
	void setRenderer(Q3View v,Q3.Renderer r){v.renderer=Q3.replace(v.renderer,r);state(r).view=v;}
	private void defineViews(){
		def("Q3View_New",a->a.ret(made(newView())));
		def("Q3View_Cancel",a->{Q3View v=view(a,0);if(v!=null)v.cancel();a.ok(v!=null);});
		def("Q3View_SetRendererByType",a->{Q3View v=view(a,0);int t=a.i(1);if(v==null){a.ok(false);return;}
			if(t!=Q3.INTERACTIVE&&t!=Q3.WIREFRAME&&t!=Q3.GENERIC){post(-28405);a.ok(false);return;}
			Q3.Renderer r=new Q3.Renderer(t);v.renderer=Q3.replace(v.renderer,r);Q3.release(r);state(r).view=v;a.ok(true);});
		def("Q3View_SetRenderer",a->{Q3View v=view(a,0);Q3.Renderer r=get(a.i(1),Q3.Renderer.class);if(v==null||r==null){a.ok(false);return;}
			v.renderer=Q3.replace(v.renderer,r);state(r).view=v;a.ok(true);});
		def("Q3View_GetRenderer",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),v.renderer==null?0:newRef(v.renderer));a.ok(v.renderer!=null);});
		def("Q3View_StartRendering",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}
			if(!v.startRendering()){post(v.error);a.ok(false);return;}a.ok(true);});
		def("Q3View_EndRendering",a->{Q3View v=view(a,0);if(v==null){a.ret(Q3View.STATUS_ERROR);return;}
			int st=v.endRendering();if(st==Q3View.STATUS_ERROR)post(v.error);
			if(v.endFrameMethod!=0)rt.cpu.call(v.endFrameMethod,v.handle,v.endFrameData);
			a.ret(st);});
		def("Q3View_Flush",a->a.ok(view(a,0)!=null));
		def("Q3View_Sync",a->a.ok(view(a,0)!=null));
		def("Q3View_StartBoundingBox",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}if(!v.startBounds(false,a.i(1)==0)){post(v.error);a.ok(false);return;}a.ok(true);});
		def("Q3View_EndBoundingBox",a->{Q3View v=view(a,0);int out=a.i(1);if(v==null){a.ret(Q3View.STATUS_ERROR);return;}
			int st=v.endBounds();if(out!=0){if(v.bmin==null){wfs(out,0,0,0,0,0,0);wl(out+24,1);}else{wfs(out,v.bmin,0,3);wfs(out+12,v.bmax,0,3);wl(out+24,0);}}
			a.ret(st);});
		def("Q3View_StartBoundingSphere",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}if(!v.startBounds(true,a.i(1)==0)){post(v.error);a.ok(false);return;}a.ok(true);});
		def("Q3View_EndBoundingSphere",a->{Q3View v=view(a,0);int out=a.i(1);if(v==null){a.ret(Q3View.STATUS_ERROR);return;}
			float[] sph=v.sphere();int st=v.endBounds();
			if(out!=0){if(sph==null){wfs(out,0,0,0,0);wl(out+16,1);}else{wfs(out,sph,0,4);wl(out+16,0);}}
			a.ret(st);});
		def("Q3View_StartPicking",a->{Q3View v=view(a,0);Q3View.Pick p=get(a.i(1),Q3View.Pick.class);if(v==null||p==null){a.ok(false);return;}
			if(!v.startPicking(p)){post(v.error);a.ok(false);return;}a.ok(true);});
		def("Q3View_EndPicking",a->{Q3View v=view(a,0);if(v==null){a.ret(Q3View.STATUS_ERROR);return;}a.ret(v.endPicking());});
		def("Q3View_GetCamera",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),v.camera==null?0:newRef(v.camera));a.ok(v.camera!=null);});
		def("Q3View_SetCamera",a->{Q3View v=view(a,0);Q3.Obj c=obj(a.i(1));if(v==null){a.ok(false);return;}v.camera=Q3.replace(v.camera,c instanceof Q3.Camera?(Q3.Camera)c:null);a.ok(true);});
		def("Q3View_SetLightGroup",a->{Q3View v=view(a,0);Q3.Obj g=obj(a.i(1));if(v==null){a.ok(false);return;}v.lightGroup=Q3.replace(v.lightGroup,g instanceof Q3.Group?(Q3.Group)g:null);a.ok(true);});
		def("Q3View_GetLightGroup",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),v.lightGroup==null?0:newRef(v.lightGroup));a.ok(v.lightGroup!=null);});
		def("Q3View_SetIdleMethod",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}v.idleMethod=a.i(1);v.idleData=a.i(2);a.ok(true);});
		def("Q3View_SetIdleProgressMethod",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}v.progressMethod=a.i(1);v.progressData=a.i(2);a.ok(true);});
		def("Q3View_SetEndFrameMethod",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}v.endFrameMethod=a.i(1);v.endFrameData=a.i(2);a.ok(true);});
		def("Q3Push_Submit",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			if(v.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(0),new Q3.StateOperator(Q3.PUSH)));return;}v.push();a.ok(true);});
		def("Q3Pop_Submit",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}if(v.mode==Q3View.IDLE){post(ERR_VIEW_NOT_STARTED);a.ok(false);return;}
			if(v.mode==Q3View.WRITING){a.ok(submitTemporary(a.i(0),new Q3.StateOperator(Q3.POP)));return;}if(!v.pop()){post(ERR_NOTHING_TO_POP);a.ok(false);return;}a.ok(true);});
		def("Q3Push_New",a->a.ret(made(new Q3.StateOperator(Q3.PUSH))));
		def("Q3Pop_New",a->a.ret(made(new Q3.StateOperator(Q3.POP))));
		def("Q3StateOperator_Submit",a->{Q3.Obj o=obj(a.i(0));Q3View v=view(a,1);a.ok(o instanceof Q3.StateOperator&&v!=null&&submit(v,o));});
		def("Q3View_IsBoundingBoxVisible",a->{Q3View v=view(a,0);int b=a.i(1);if(v==null||b==0){a.ret(0);return;}if(rl(b+24)!=0){a.ret(0);return;}a.ret(v.boxVisible(rfs(b,6))?1:0);});
		def("Q3View_AllowAllGroupCulling",a->{Q3View v=view(a,0);if(v==null){a.ok(false);return;}v.allowGroupCulling=a.i(1)!=0;a.ok(true);});
		def("Q3View_SetDrawContext",a->{Q3View v=view(a,0);Q3.Obj d=obj(a.i(1));if(v==null){a.ok(false);return;}
			v.drawContext=Q3.replace(v.drawContext,d instanceof Q3.DrawContext?(Q3.DrawContext)d:null);if(v.renderer!=null)state(v.renderer).view=v;a.ok(true);});
		def("Q3View_GetDrawContext",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),v.drawContext==null?0:newRef(v.drawContext));a.ok(v.drawContext!=null);});
		def("Q3View_GetLocalToWorldMatrixState",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),v.s.localToWorld,0,16);a.ok(true);});
		def("Q3View_GetWorldToFrustumMatrixState",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}if(v.mode==Q3View.IDLE)v.frameMatrices();wfs(a.i(1),v.worldToFrustum,0,16);a.ok(true);});
		def("Q3View_GetFrustumToWindowMatrixState",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}if(v.mode==Q3View.IDLE)v.frameMatrices();wfs(a.i(1),v.frustumToWindow,0,16);a.ok(true);});
		def("Q3View_GetBackfacingStyleState",a->viewState(a,v->v.s.backfacing));
		def("Q3View_GetInterpolationStyleState",a->viewState(a,v->v.s.interpolation));
		def("Q3View_GetFillStyleState",a->viewState(a,v->v.s.fill));
		def("Q3View_GetOrientationStyleState",a->viewState(a,v->v.s.orientation));
		def("Q3View_GetReceiveShadowsStyleState",a->viewState(a,v->v.s.receiveShadows?1:0));
		def("Q3View_GetPickIDStyleState",a->viewState(a,v->v.s.pickID));
		def("Q3View_GetPickPartsStyleState",a->viewState(a,v->v.s.pickParts));
		def("Q3View_GetHighlightStyleState",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),v.s.highlight==null?0:newRef(v.s.highlight));a.ok(true);});
		def("Q3View_GetSubdivisionStyleState",a->{Q3View v=view(a,0);int p=a.i(1);if(v==null||p==0){a.ok(false);return;}wl(p,v.s.subMethod);wf(p+4,v.s.subC1);wf(p+8,v.s.subC2);a.ok(true);});
		def("Q3View_GetAntiAliasStyleState",a->{Q3View v=view(a,0);int p=a.i(1);if(v==null||p==0){a.ok(false);return;}writeStyleData(p,v.s.antiAlias!=null?v.s.antiAlias:new float[]{0,0,0},ANTIALIAS_LAYOUT);a.ok(true);});
		def("Q3View_GetDefaultAttributeSet",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),newRef(v.defaults));a.ok(true);});
		def("Q3View_SetDefaultAttributeSet",a->{Q3View v=view(a,0);Q3.AttrSet s=get(a.i(1),Q3.AttrSet.class);if(v==null||s==null){a.ok(false);return;}
			v.defaults.clearAll();v.defaults.inherit(s);a.ok(true);});
		def("Q3View_GetAttributeSetState",a->{Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),made(stateAttributes(v)));a.ok(true);});
		def("Q3View_GetAttributeState",a->{Q3View v=view(a,0);int type=a.i(1),out=a.i(2);if(v==null||out==0){a.ok(false);return;}
			Q3.AttrSet s=stateAttributes(v);Object val=s.get(type);if(val!=null)writeElement(type,val,out);Q3.release(s);a.ok(val!=null);});
		def("Q3XView_IdleProgress",a->a.ok(true));
		def("Q3XView_EndFrame",a->a.ok(true));
	}
	interface ViewInt { int get(Q3View v); }
	private void viewState(A a,ViewInt f) throws M68kCpuException {Q3View v=view(a,0);if(v==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),f.get(v));a.ok(true);}
	private Q3.AttrSet stateAttributes(Q3View v){
		Q3.AttrSet s=new Q3.AttrSet();Q3View.State st=v.s;
		s.put(Q3.ATTR_AMBIENT_COEFFICIENT,new float[]{st.ambient});s.put(Q3.ATTR_DIFFUSE,new float[]{st.dr,st.dg,st.db});
		s.put(Q3.ATTR_SPECULAR,new float[]{st.sr,st.sg,st.sb});s.put(Q3.ATTR_SPECULAR_CONTROL,new float[]{st.specCtl});
		s.put(Q3.ATTR_TRANSPARENCY,new float[]{st.tr,st.tg,st.tb});s.put(Q3.ATTR_HIGHLIGHT_STATE,st.highlightState);
		if(st.surfaceShader!=null)s.put(Q3.ATTR_SURFACE_SHADER,st.surfaceShader);
		return s;
	}

	// ================================================================== picks

	private void readPickData(int p,Q3View.Pick k) throws M68kCpuException {k.sort=rl(p);k.mask=rl(p+4);k.numHits=rl(p+8);}
	private void writePickData(int p,Q3View.Pick k) throws M68kCpuException {wl(p,k.sort);wl(p+4,k.mask);wl(p+8,k.numHits);}
	private void definePicks(){
		def("Q3Pick_GetType",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);a.ret(p==null?0:p.leaf());});
		def("Q3Pick_GetData",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null||a.i(1)==0){a.ok(false);return;}writePickData(a.i(1),p);a.ok(true);});
		def("Q3Pick_SetData",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null||a.i(1)==0){a.ok(false);return;}readPickData(a.i(1),p);a.ok(true);});
		def("Q3Pick_GetVertexTolerance",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),p.leaf()==Q3.PICK_WORLD_RAY?p.data[6]:p.data[2]);a.ok(true);});
		def("Q3Pick_GetEdgeTolerance",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null||a.i(1)==0){a.ok(false);return;}wf(a.i(1),p.leaf()==Q3.PICK_WORLD_RAY?p.data[7]:p.data[3]);a.ok(true);});
		def("Q3Pick_SetVertexTolerance",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null){a.ok(false);return;}p.data[p.leaf()==Q3.PICK_WORLD_RAY?6:2]=a.f(1);a.ok(true);});
		def("Q3Pick_SetEdgeTolerance",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null){a.ok(false);return;}p.data[p.leaf()==Q3.PICK_WORLD_RAY?7:3]=a.f(1);a.ok(true);});
		def("Q3Pick_GetNumHits",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),p.hits.size());a.ok(true);});
		def("Q3Pick_EmptyHitList",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);if(p!=null)p.empty();a.ok(p!=null);});
		def("Q3Pick_GetPickDetailValidMask",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);int i=a.i(1),out=a.i(2);if(p==null||out==0||i<0||i>=p.hits.size()){a.ok(false);return;}
			Q3View.Hit h=p.hits.get(i);int m=p.mask&(1|4|8|16|32|256);if(h.path!=null)m|=p.mask&2;if(h.normal!=null)m|=p.mask&64;if(h.uv!=null)m|=p.mask&512;wl(out,m);a.ok(true);});
		def("Q3Pick_GetPickDetailData",a->{Q3View.Pick p=get(a.i(0),Q3View.Pick.class);int i=a.i(1),detail=a.i(2),out=a.i(3);
			if(p==null||out==0||i<0||i>=p.hits.size()){a.ok(false);return;}
			Q3View.Hit h=p.hits.get(i);
			switch(detail){
				case 1:wl(out,h.pickID);break;
				case 2:{if(h.path==null){a.ok(false);return;}wl(out,newRef(h.root));wl(out+4,h.path.length);int arr=alloc(4*Math.max(1,h.path.length));for(int k=0;k<h.path.length;k++)wl(arr+4*k,posHandle(h.path[k]));wl(out+8,arr);break;}
				case 4:wl(out,newRef(h.object));break;
				case 8:wfs(out,h.localToWorld,0,16);break;
				case 16:wfs(out,h.xyz,0,3);break;
				case 32:wf(out,h.distance);break;
				case 64:if(h.normal==null){a.ok(false);return;}wfs(out,h.normal,0,3);break;
				case 128:wl(out,0);break;
				case 256:wl(out,h.pickParts);break;
				case 512:if(h.uv==null){a.ok(false);return;}wfs(out,h.uv,0,2);break;
				default:a.ok(false);return;
			}
			a.ok(true);});
		def("Q3HitPath_EmptyData",a->{int p=a.i(0);if(p==0){a.ok(false);return;}Q3.Obj root=obj(rl(p));if(root!=null)Q3.release(root);free(rl(p+8));wl(p,0);wl(p+4,0);wl(p+8,0);a.ok(true);});
		def("Q3WindowPointPick_New",a->{int p=a.i(0);if(p==0){a.ret(0);return;}Q3View.Pick k=new Q3View.Pick(Q3.PICK_WINDOW_POINT);readPickData(p,k);rfs(p+12,k.data,0,4);a.ret(made(k));});
		def("Q3WindowPointPick_GetPoint",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);if(k==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),k.data,0,2);a.ok(true);});
		def("Q3WindowPointPick_SetPoint",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);if(k==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),k.data,0,2);a.ok(true);});
		def("Q3WindowPointPick_GetData",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);int p=a.i(1);if(k==null||p==0){a.ok(false);return;}writePickData(p,k);wfs(p+12,k.data,0,4);a.ok(true);});
		def("Q3WindowPointPick_SetData",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);int p=a.i(1);if(k==null||p==0){a.ok(false);return;}readPickData(p,k);rfs(p+12,k.data,0,4);a.ok(true);});
		def("Q3WindowRectPick_New",a->{int p=a.i(0);if(p==0){a.ret(0);return;}Q3View.Pick k=new Q3View.Pick(Q3.PICK_WINDOW_RECT);readPickData(p,k);rfs(p+12,k.data,0,4);a.ret(made(k));});
		def("Q3WindowRectPick_GetRect",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);if(k==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),k.data,0,4);a.ok(true);});
		def("Q3WindowRectPick_SetRect",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);if(k==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),k.data,0,4);a.ok(true);});
		def("Q3WindowRectPick_GetData",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);int p=a.i(1);if(k==null||p==0){a.ok(false);return;}writePickData(p,k);wfs(p+12,k.data,0,4);a.ok(true);});
		def("Q3WindowRectPick_SetData",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);int p=a.i(1);if(k==null||p==0){a.ok(false);return;}readPickData(p,k);rfs(p+12,k.data,0,4);a.ok(true);});
		def("Q3WorldRayPick_New",a->{int p=a.i(0);if(p==0){a.ret(0);return;}Q3View.Pick k=new Q3View.Pick(Q3.PICK_WORLD_RAY);readPickData(p,k);rfs(p+12,k.data,0,8);a.ret(made(k));});
		def("Q3WorldRayPick_GetRay",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);if(k==null||a.i(1)==0){a.ok(false);return;}wfs(a.i(1),k.data,0,6);a.ok(true);});
		def("Q3WorldRayPick_SetRay",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);if(k==null||a.i(1)==0){a.ok(false);return;}rfs(a.i(1),k.data,0,6);a.ok(true);});
		def("Q3WorldRayPick_GetData",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);int p=a.i(1);if(k==null||p==0){a.ok(false);return;}writePickData(p,k);wfs(p+12,k.data,0,8);a.ok(true);});
		def("Q3WorldRayPick_SetData",a->{Q3View.Pick k=get(a.i(0),Q3View.Pick.class);int p=a.i(1);if(k==null||p==0){a.ok(false);return;}readPickData(p,k);rfs(p+12,k.data,0,8);a.ok(true);});
		def("Q3ShapePart_GetType",a->{Q3.Obj o=obj(a.i(0));a.ret(o==null?0:o.typeAt(1));});
		def("Q3MeshPart_GetType",a->{Q3.Obj o=obj(a.i(0));a.ret(o==null?0:o.typeAt(2));});
		def("Q3ShapePart_GetShape",a->{if(a.i(1)!=0)wl(a.i(1),0);a.ok(false);});
		for(String n:new String[]{"Q3MeshPart_GetComponent","Q3MeshFacePart_GetFace","Q3MeshEdgePart_GetEdge","Q3MeshVertexPart_GetVertex"})def(n,a->{if(a.i(1)!=0)wl(a.i(1),0);a.ok(false);});
	}

	// ================================================================== strings

	private void defineStrings(){
		def("Q3String_GetType",a->typeAt(a,Q3.STRING,2));
		def("Q3CString_New",a->{int p=a.i(0);a.ret(made(new Q3.StringObj(p==0?new byte[0]:rt.cBytes(p))));});
		def("Q3CString_GetLength",a->{Q3.StringObj s=get(a.i(0),Q3.StringObj.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.text.length);a.ok(true);});
		def("Q3CString_SetString",a->{Q3.StringObj s=get(a.i(0),Q3.StringObj.class);if(s==null){a.ok(false);return;}s.text=a.i(1)==0?new byte[0]:rt.cBytes(a.i(1));s.edited();a.ok(true);});
		def("Q3CString_GetString",a->{Q3.StringObj s=get(a.i(0),Q3.StringObj.class);int out=a.i(1);if(s==null||out==0){a.ok(false);return;}
			int old=rl(out);if(old!=0)notice(-28098);
			int p=alloc(s.text.length+1);memory.load(p,s.text);wl(out,p);a.ok(true);});
		def("Q3CString_EmptyData",a->{int p=a.i(0);if(p==0){a.ok(false);return;}free(rl(p));wl(p,0);a.ok(true);});
	}

	// ================================================================== errors

	private void defineErrors(){
		def("Q3Error_Register",a->{errorProc=a.i(0);errorRef=a.i(1);a.ok(true);});
		def("Q3Warning_Register",a->{warningProc=a.i(0);warningRef=a.i(1);a.ok(true);});
		def("Q3Notice_Register",a->{noticeProc=a.i(0);noticeRef=a.i(1);a.ok(true);});
		def("Q3Error_Get",a->{int p=a.i(0);if(p!=0)wl(p,firstError);a.ret(lastError);firstError=lastError=0;});
		def("Q3Error_IsFatalError",a->{int e=a.i(0);a.ret(e==ERR_INTERNAL||e==-28499||e==-28498?1:0);});
		def("Q3Warning_Get",a->{int p=a.i(0);if(p!=0)wl(p,firstWarning);a.ret(lastWarning);firstWarning=lastWarning=0;});
		def("Q3Notice_Get",a->{int p=a.i(0);if(p!=0)wl(p,firstNotice);a.ret(lastNotice);firstNotice=lastNotice=0;});
		def("Q3MacintoshError_Get",a->{int p=a.i(0);if(p!=0)memory.writeWord(p,firstMacError);a.ret(lastMacError);firstMacError=lastMacError=0;});
		def("Q3XError_Post",a->post(a.i(0)));
		def("Q3XWarning_Post",a->warn(a.i(0)));
		def("Q3XNotice_Post",a->notice(a.i(0)));
		def("Q3XMacintoshError_Post",a->{short e=(short)a.i(0);if(firstMacError==0)firstMacError=e;lastMacError=e;post(-28485);});
	}

	// ================================================================== the extension interface (custom classes)

	/** A class a program or plug-in registered (Q3XObjectHierarchy_RegisterClass): its type, name and metahandlers. */
	static final class XClass { int type,parent;String name;int metaHandler,virtualMetaHandler,instanceSize; }
	private final Map<Integer,XClass> xClasses=new HashMap<Integer,XClass>();
	/** An instance of a registered class: its private data in guest memory. */
	static final class XObject extends Q3.Shape { final XClass cls;int priv;XObject(XClass c){super(c.type);cls=c;} }
	private int method(XClass c,int methodType) throws M68kException {
		if(c.metaHandler==0)return 0;
		return rt.cpu.call(c.metaHandler,methodType);
	}
	private void defineExtensions(){
		def("Q3XObjectHierarchy_RegisterClass",a->{int parent=a.i(0),typeOut=a.i(1);String name=cstr(a.i(2));
			if(typeOut==0||name==null){post(ERR_NULL_PARAMETER);a.ret(0);return;}
			if(Q3.TYPES_BY_NAME.containsKey(name)){int t=Q3.TYPES_BY_NAME.get(name);wl(typeOut,t);XClass c=xClasses.get(t);a.ret(c==null?0:classHandle(c));warn(-28264);return;}
			XClass c=new XClass();c.type=nextCustomType--;c.parent=parent;c.name=name;c.metaHandler=a.i(3);c.virtualMetaHandler=a.i(4);c.instanceSize=a.i(6);
			xClasses.put(c.type,c);Q3.registerClass(parent,c.type,name);wl(typeOut,c.type);
			int h=classHandle(c);
			int reg=method(c,Q3.fc("rgst"));if(reg!=0)rt.cpu.call(reg,h,0);
			a.ret(h);});
		def("Q3XObjectHierarchy_UnregisterClass",a->a.ok(true));
		def("Q3XObjectClass_GetMethod",a->{Object c=thing(a.i(0));a.ret(c instanceof XClass?method((XClass)c,a.i(1)):0);});
		def("Q3XObjectHierarchy_NewObject",a->{Object c=thing(a.i(0));if(!(c instanceof XClass)){a.ret(0);return;}XClass x=(XClass)c;
			XObject o=new XObject(x);o.priv=x.instanceSize>0?alloc(x.instanceSize):0;int h=made(o);
			int m=method(x,Q3.fc("newo"));if(m!=0&&rt.cpu.call(m,h,o.priv,a.i(1))==0){Q3.release(o);a.ret(0);return;}
			a.ret(h);});
		def("Q3XObjectClass_GetLeafType",a->{Object c=thing(a.i(0));a.ret(c instanceof XClass?((XClass)c).type:c instanceof Q3.ElementClass?((Q3.ElementClass)c).type:0);});
		def("Q3XObjectHierarchy_GetClassVersion",a->{if(a.i(1)!=0)wl(a.i(1),0);a.ok(Q3.chainOf(a.i(0))!=null);});
		def("Q3XObjectClass_GetType",a->{Object c=thing(a.i(0));int out=a.i(1);if(out==0){a.ok(false);return;}
			wl(out,c instanceof XClass?((XClass)c).type:c instanceof Q3.ElementClass?((Q3.ElementClass)c).type:0);a.ok(c!=null);});
		def("Q3XObjectHierarchy_FindClassByType",a->{XClass c=xClasses.get(a.i(0));a.ret(c==null?0:classHandle(c));});
		def("Q3XObjectClass_GetPrivate",a->{Q3.Obj o=obj(a.i(1));a.ret(o instanceof XObject?((XObject)o).priv:0);});
		def("Q3XObject_GetClass",a->{Q3.Obj o=obj(a.i(0));a.ret(o instanceof XObject?classHandle(((XObject)o).cls):0);});
		// plug-ins register themselves when loaded; this library loads none of QuickDraw 3D's own, so a program's call just succeeds
		def("Q3XSharedLibrary_Register",a->{int info=a.i(0);if(info==0){a.ok(false);return;}int f=rl(info);if(f!=0)rt.cpu.call(f);a.ok(true);});
		def("Q3XSharedLibrary_Unregister",a->a.ok(true));
	}
}

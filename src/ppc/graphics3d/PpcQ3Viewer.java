import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * The QuickDraw 3D Viewer 1.6 (the CFM library "QD3DViewerLib", QD3DViewer.h) for {@link PpcQuickDraw3D}: a 3D pane in a
 * program's port with a control strip - camera views, truck, orbit, zoom, dolly and reset - that shows a model from a
 * 3DMF file, data or group, tracks the mouse to move it, and copies, pastes and writes it as 3DMF (and PICT).
 *
 * <p>What Apple's Viewer does was measured by running its 1.6 library next to this one (PpcQuickDraw3DTest): the
 * camera is the model's view hints' one, or the viewer's own at (0, 0, 200) looking at the origin with a 0.8 radian field
 * of view; the model is moved to the camera's point of interest and scaled so its bounding sphere's radius is the
 * distance to it times half the field of view times {@link #FIT}; hither and yon are put 1.2 box radii either side of it
 * when it is drawn. View hints also bring lights, a clear colour and attributes. The pane is the bounds less the 28-pixel control strip, the frame (1) and the drag border (4); the
 * strip's buttons are 39 (camera, with its pop-up) or 32 pixels wide, reset set 5 pixels apart, centred. The button
 * pictures are drawn here, not Apple's.
 */
final class PpcQ3Viewer {
	static final String LIBRARY="QD3DViewerLib";
	private final PpcQuickDraw3D q;private final M68kMemory memory;private final PpcRuntime rt;
	PpcQ3Viewer(PpcQuickDraw3D q){this.q=q;this.memory=q.memory;this.rt=q.rt;}

	static final int SHOW_BADGE=1,ACTIVE=2,CONTROLLER_VISIBLE=4,DRAW_FRAME=8,DRAGGING_OFF=16,BUTTON_CAMERA=32,BUTTON_TRUCK=64,BUTTON_ORBIT=128,
		BUTTON_ZOOM=256,BUTTON_DOLLY=512,BUTTON_RESET=1024,OUTPUT_TEXT=1<<11,DRAG_MODE=1<<12,DRAW_GROW_BOX=1<<13,DRAW_DRAG_BORDER=1<<14,DEFAULT=1<<31;
	/** Flags Q3ViewerNew gives kQ3ViewerDefault, and 0 (Apple's adds bit 15 then). */
	static final int DEFAULT_FLAGS=0x46e6,ZERO_FLAGS=0xc6e6;
	static final int[] BUTTONS={BUTTON_CAMERA,BUTTON_TRUCK,BUTTON_ORBIT,BUTTON_ZOOM,BUTTON_DOLLY,BUTTON_RESET};
	static final int STRIP=28,CAMERA_WIDTH=39,BUTTON_WIDTH=32,RESET_GAP=5,DRAG_BORDER=4,STRIP_MARGIN=7;
	/** The camera distance and field of view of the viewer's own camera, and the radius a model is scaled to. */
	static final float DISTANCE=200,FOV=0.8f,FIT=0.947867f,RANGE_MARGIN=1.2f;
	static final int ERR_NO_MODEL=-41;

	/** A viewer (TQ3ViewerObject). */
	final class Viewer extends Q3.Obj {
		int port,flags;int left,top,right,bottom;
		Q3View view;Q3.Group group;Q3.DrawContext dc;Q3.Camera camera;
		final float[] background={1,1,1,1};
		/** The model's bounds (sphere centre and radius, half the box's diagonal) and placement: moved from centre to anchor, scaled, rotated by orbiting. */
		float[] centre={0,0,0};float radius,halfDiagonal;float[] anchor={0,0,0};float scale=1;float[] rotation=Q3.identity();
		/** What the model's view hints gave: the camera (restored by Q3ViewerRestoreView), lights and attributes; or null. */
		Q3.Camera hintCamera;Q3.Group hintLights;Q3.AttrSet hintAttributes;
		int button=BUTTON_ORBIT;int cameraView;
		Q3.Group undoGroup;int drawingCallback,drawingData,windowResize,windowResizeData,paneResize,paneResizeData;
		float brightness=1;boolean removeBackfaces,phong=true;
		Viewer(){super(Q3.fc("Q3Vw"));}
		@Override void dropRefs(){Q3.release(view);Q3.release(group);Q3.release(dc);Q3.release(camera);Q3.release(undoGroup);Q3.release(hintCamera);Q3.release(hintLights);Q3.release(hintAttributes);super.dropRefs();}
	}
	private Viewer viewer(PpcQuickDraw3D.A a) throws M68kCpuException {Object o=q.obj(a.i(0));return o instanceof Viewer?(Viewer)o:null;}

	void define(PpcLibraries l){
		def("Q3ViewerGetVersion",a->{if(a.i(0)!=0)memory.writeLong(a.i(0),PpcQuickDraw3D.MAJOR);if(a.i(1)!=0)memory.writeLong(a.i(1),PpcQuickDraw3D.MINOR);a.ret(0);});
		def("Q3ViewerGetReleaseVersion",a->{if(a.i(0)!=0)memory.writeLong(a.i(0),PpcQuickDraw3D.RELEASE);a.ret(0);});
		def("Q3ViewerNew",a->{int port=a.i(0),rect=a.i(1),flags=a.i(2);a.ret(q.made(create(port,rect,flags)));});
		def("Q3ViewerDispose",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}Q3.release(v);a.ret(0);});
		def("Q3ViewerUseFile",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}
			FileManager68k fm=rt.toolbox.files.fileManager;int ref=(short)a.i(1);long n=fm.referenceLength(ref);if(n<0){a.ret(-51);return;}
			byte[] b=fm.readReference(ref,0,(int)Math.min(n,64L<<20));a.ret(useData(v,b)?0:-1);});
		def("Q3ViewerUseData",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}a.ret(useData(v,memory.readBytes(a.i(1),a.i(2)))?0:-1);});
		def("Q3ViewerWriteFile",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}byte[] b=write(v,false);
			a.ret(rt.toolbox.files.fileManager.writeReference((short)a.i(1),0,b)?0:-61);});
		def("Q3ViewerWriteData",a->{Viewer v=viewer(a);int h=a.i(1);if(v==null||h==0){a.ret(0);return;}byte[] b=write(v,false);
			q.tb("SetHandleSize",h,b.length);if(q.tb("GetHandleSize",h)<b.length){a.ret(0);return;}memory.load(memory.readLong(h),b);a.ret(b.length);});
		def("Q3ViewerDraw",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}draw(v,true,true);a.ret(0);});
		def("Q3ViewerDrawContent",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}draw(v,true,false);a.ret(0);});
		def("Q3ViewerDrawControlStrip",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}draw(v,false,true);a.ret(0);});
		def("Q3ViewerEvent",a->{Viewer v=viewer(a);int e=a.i(1);a.ret(v!=null&&e!=0&&event(v,e)?1:0);});
		def("Q3ViewerGetPict",a->{Viewer v=viewer(a);a.ret(v==null?0:pict(v));});
		def("Q3ViewerGetButtonRect",a->{Viewer v=viewer(a);int r=a.i(2);if(v==null||r==0){a.ret(-50);return;}int[] b=buttonRect(v,a.i(1));putRect(r,b==null?new int[4]:b);a.ret(0);});
		def("Q3ViewerGetCurrentButton",a->{Viewer v=viewer(a);a.ret(v==null?0:v.button);});
		def("Q3ViewerSetCurrentButton",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.button=a.i(1);a.ret(0);});
		def("Q3ViewerUseGroup",a->{Viewer v=viewer(a);Q3.Obj g=q.obj(a.i(1));if(v==null||!(g instanceof Q3.Group)){a.ret(-50);return;}useGroup(v,Q3.ref((Q3.Group)g),null);a.ret(0);});
		def("Q3ViewerGetGroup",a->{Viewer v=viewer(a);a.ret(v==null||!hasModel(v)?0:q.newRef(v.group));});
		def("Q3ViewerSetBackgroundColor",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}q.rfs(a.i(1),v.background,0,4);background(v);a.ret(0);});
		def("Q3ViewerGetBackgroundColor",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}q.wfs(a.i(1),v.background,0,4);a.ret(0);});
		def("Q3ViewerGetView",a->{Viewer v=viewer(a);a.ret(v==null?0:q.handle(v.view));});
		def("Q3ViewerRestoreView",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}restore(v);a.ret(0);});
		def("Q3ViewerSetFlags",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.flags=flags(a.i(1));layout(v);a.ret(0);});
		def("Q3ViewerGetFlags",a->{Viewer v=viewer(a);a.ret(v==null?0:v.flags);});
		def("Q3ViewerSetBounds",a->{Viewer v=viewer(a);int r=a.i(1);if(v==null||r==0){a.ret(-50);return;}readRect(v,r);layout(v);a.ret(0);});
		def("Q3ViewerGetBounds",a->{Viewer v=viewer(a);int r=a.i(1);if(v==null||r==0){a.ret(-50);return;}putRect(r,new int[]{v.left,v.top,v.right,v.bottom});a.ret(0);});
		def("Q3ViewerSetDimension",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.right=v.left+a.i(1);v.bottom=v.top+a.i(2);layout(v);a.ret(0);});
		def("Q3ViewerGetDimension",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}if(a.i(1)!=0)memory.writeLong(a.i(1),v.right-v.left);if(a.i(2)!=0)memory.writeLong(a.i(2),v.bottom-v.top);a.ret(0);});
		def("Q3ViewerGetMinimumDimension",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}if(a.i(1)!=0)memory.writeLong(a.i(1),stripWidth(v));if(a.i(2)!=0)memory.writeLong(a.i(2),STRIP);a.ret(0);});
		def("Q3ViewerSetPort",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.port=a.i(1);v.dc.grafPort=v.port;v.dc.edited();a.ret(0);});
		def("Q3ViewerGetPort",a->{Viewer v=viewer(a);a.ret(v==null?0:v.port);});
		def("Q3ViewerAdjustCursor",a->a.ret(0));
		def("Q3ViewerCursorChanged",a->a.ret(0));
		def("Q3ViewerGetState",a->{Viewer v=viewer(a);a.ret(v==null?0:(hasModel(v)?1:0)|(v.undoGroup!=null?2:0));});
		def("Q3ViewerClear",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}keepUndo(v);useGroup(v,new Q3.Group(Q3.DISPLAY_GROUP),null);draw(v,true,true);a.ret(0);});
		def("Q3ViewerCut",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}a.ret(copy(v));keepUndo(v);useGroup(v,new Q3.Group(Q3.DISPLAY_GROUP),null);draw(v,true,true);});
		def("Q3ViewerCopy",a->{Viewer v=viewer(a);a.ret(v==null?-50:copy(v));});
		def("Q3ViewerPaste",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}a.ret(paste(v));});
		def("Q3ViewerMouseDown",a->{Viewer v=viewer(a);a.ret(v!=null&&mouseDown(v,a.i(1),a.i(2),false)?1:0);});
		def("Q3ViewerContinueTracking",a->{Viewer v=viewer(a);a.ret(v!=null&&track(v,a.i(1),a.i(2))?1:0);});
		def("Q3ViewerMouseUp",a->{Viewer v=viewer(a);if(v!=null)tracking=false;a.ret(v!=null?1:0);});
		def("Q3ViewerHandleKeyEvent",a->a.ret(0));
		def("Q3ViewerSetDrawingCallbackMethod",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.drawingCallback=a.i(1);v.drawingData=a.i(2);a.ret(0);});
		def("Q3ViewerSetWindowResizeCallback",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.windowResize=a.i(1);v.windowResizeData=a.i(2);a.ret(0);});
		def("Q3ViewerSetPaneResizeNotifyCallback",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.paneResize=a.i(1);v.paneResizeData=a.i(2);a.ret(0);});
		def("Q3ViewerUndo",a->{Viewer v=viewer(a);if(v==null||v.undoGroup==null){a.ret(-50);return;}Q3.Group g=v.undoGroup;v.undoGroup=null;useGroup(v,g,null);draw(v,true,true);a.ret(0);});
		def("Q3ViewerGetUndoString",a->{Viewer v=viewer(a);int s=a.i(1),cnt=a.i(2);if(v==null||v.undoGroup==null){a.ret(0);return;}
			byte[] b="Undo".getBytes(PpcQuickDraw3D.MAC_ROMAN);int max=cnt==0?b.length:memory.readLong(cnt);int n=Math.min(max,b.length);if(s!=0)memory.load(s,b,0,n);if(cnt!=0)memory.writeLong(cnt,n);a.ret(1);});
		def("Q3ViewerGetCameraCount",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}memory.writeLong(a.i(1),v.hintCamera!=null?1:0);a.ret(0);});
		def("Q3ViewerSetCameraByNumber",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}if(v.hintCamera==null||a.i(1)!=1){a.ret(-50);return;}restore(v);a.ret(0);});
		def("Q3ViewerSetCameraByView",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}if(!hasModel(v)){a.ret(ERR_NO_MODEL);return;}cameraView(v,a.i(1));a.ret(0);});
		def("Q3ViewerSetRendererType",a->{Viewer v=viewer(a);int t=a.i(1);if(v==null||t!=Q3.INTERACTIVE&&t!=Q3.WIREFRAME){a.ret(-50);return;}Q3.Renderer r=new Q3.Renderer(t);q.setRenderer(v.view,r);Q3.release(r);a.ret(0);});
		def("Q3ViewerGetRendererType",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}memory.writeLong(a.i(1),v.view.renderer==null?Q3.INTERACTIVE:v.view.renderer.leaf());a.ret(0);});
		def("Q3ViewerChangeBrightness",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.brightness=a.f(1);lights(v);a.ret(0);});
		def("Q3ViewerSetRemoveBackfaces",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.removeBackfaces=a.i(1)!=0;a.ret(0);});
		def("Q3ViewerGetRemoveBackfaces",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}memory.writeLong(a.i(1),v.removeBackfaces?1:0);a.ret(0);});
		def("Q3ViewerSetPhongShading",a->{Viewer v=viewer(a);if(v==null){a.ret(-50);return;}v.phong=a.i(1)!=0;a.ret(0);});
		def("Q3ViewerGetPhongShading",a->{Viewer v=viewer(a);if(v==null||a.i(1)==0){a.ret(-50);return;}memory.writeLong(a.i(1),v.phong?1:0);a.ret(0);});
	}
	private void def(String name,PpcQuickDraw3D.Fn f){q.defIn(LIBRARY,name,f);}

	// ================================================================== making and laying out

	private static int flags(int f){return f==0?ZERO_FLAGS:(f&DEFAULT)!=0?DEFAULT_FLAGS:f;}
	private Viewer create(int port,int rect,int flags) throws Exception {
		Viewer v=new Viewer();v.port=port;v.flags=flags(flags);
		if(rect!=0)readRect(v,rect);else{int[] r=portRect(port);v.left=r[0];v.top=r[1];v.right=r[2];v.bottom=r[3];}
		v.view=q.newView();v.view.refs=1;
		Q3.Renderer r=new Q3.Renderer(Q3.INTERACTIVE);q.setRenderer(v.view,r);Q3.release(r);
		v.dc=new Q3.DrawContext(Q3.MAC_DRAW_CONTEXT);v.dc.grafPort=port;v.dc.paneState=true;v.dc.clearMethod=Q3.CLEAR_WITH_COLOR;
		v.view.drawContext=Q3.ref(v.dc);
		v.camera=new Q3.Camera(Q3.VIEW_ANGLE_ASPECT);v.view.camera=Q3.ref(v.camera);defaultCamera(v.camera,0,0,1);
		v.view.lightGroup=new Q3.Group(Q3.LIGHT_GROUP);lights(v);
		layout(v);
		v.group=new Q3.Group(Q3.DISPLAY_GROUP);
		return v;
	}
	private void readRect(Viewer v,int r) throws M68kCpuException {v.top=(short)memory.readWord(r);v.left=(short)memory.readWord(r+2);v.bottom=(short)memory.readWord(r+4);v.right=(short)memory.readWord(r+6);}
	private void putRect(int r,int[] b) throws M68kCpuException {memory.writeWord(r,b[1]);memory.writeWord(r+2,b[0]);memory.writeWord(r+4,b[3]);memory.writeWord(r+6,b[2]);}
	private int[] portRect(int port) throws M68kCpuException {return new int[]{(short)memory.readWord(port+18),(short)memory.readWord(port+16),(short)memory.readWord(port+22),(short)memory.readWord(port+20)};}
	private static int stripHeight(Viewer v){return (v.flags&CONTROLLER_VISIBLE)!=0?STRIP:0;}
	/** The content area: the bounds above the control strip. */
	private static int[] contentArea(Viewer v){return new int[]{v.left,v.top,v.right,Math.max(v.top,v.bottom-stripHeight(v))};}
	/** The 3D pane: the content area less the frame and the drag border. */
	private static int[] pane(Viewer v){
		int in=((v.flags&DRAW_FRAME)!=0?1:0)+((v.flags&DRAW_DRAG_BORDER)!=0?DRAG_BORDER:0);int[] c=contentArea(v);
		return new int[]{c[0]+in,c[1]+in,Math.max(c[0]+in,c[2]-in),Math.max(c[1]+in,c[3]-in)};
	}
	private void layout(Viewer v){
		int[] p=pane(v);
		v.dc.pane[0]=p[0];v.dc.pane[1]=p[1];v.dc.pane[2]=p[2];v.dc.pane[3]=p[3];v.dc.paneState=true;v.dc.edited();
		aspect(v);
	}
	/** The camera's aspect ratio: the content area's (once there is a model). */
	private static void aspect(Viewer v){
		int[] c=contentArea(v);
		if(hasModel(v)&&c[3]>c[1]&&v.camera.leaf()==Q3.VIEW_ANGLE_ASPECT){v.camera.aspect=(float)(c[2]-c[0])/(c[3]-c[1]);v.camera.edited();}
	}
	private void background(Viewer v){System.arraycopy(v.background,0,v.dc.clearColor,0,4);v.dc.edited();}
	/** The light group: the model's view hints' lights, or the viewer's own three directional lights and a grey ambient one. */
	private void lights(Viewer v){
		Q3.Group g=v.view.lightGroup;g.empty(0);
		if(v.hintLights!=null){for(Q3.Group.Pos p:v.hintLights.list){Q3.Obj o=p.obj.duplicate();if(o instanceof Q3.Light)((Q3.Light)o).brightness*=v.brightness;g.add(o);Q3.release(o);}return;}
		light(g,Q3.DIRECTIONAL,0.9f*v.brightness,1,-0.60822874f,-0.22808579f,-0.7602859f);
		light(g,Q3.DIRECTIONAL,0.8f*v.brightness,1,0,-0.624695f,0.78086877f);
		light(g,Q3.DIRECTIONAL,0.3f*v.brightness,1,0.6666667f,-0.33333334f,-0.6666667f);
		light(g,Q3.AMBIENT,0.3f*v.brightness,0.5f,0,0,0);
	}
	private static void light(Q3.Group g,int type,float brightness,float grey,float dx,float dy,float dz){
		Q3.Light l=new Q3.Light(type);l.brightness=brightness;l.color[0]=l.color[1]=l.color[2]=grey;
		if(type==Q3.DIRECTIONAL){l.direction[0]=dx;l.direction[1]=dy;l.direction[2]=dz;}
		g.add(l);Q3.release(l);
	}

	// ================================================================== the model

	private static boolean hasModel(Viewer v){return v.group!=null&&!v.group.list.isEmpty();}
	/** Shows a group: with a view hints object's camera, lights, clear colour and attributes when {@code hints} has them; placed before the camera (see place). */
	private void useGroup(Viewer v,Q3.Group g,Q3.Plain hints) throws Exception {useGroup(v,g,hints,false);}
	/** {@code fromData}: a model read from 3DMF, which also puts back the lights (a group given keeps the ones the program added). */
	private void useGroup(Viewer v,Q3.Group g,Q3.Plain hints,boolean fromData) throws Exception {
		Q3.release(v.group);v.group=g;v.rotation=Q3.identity();
		Q3.release(v.hintCamera);Q3.release(v.hintLights);Q3.release(v.hintAttributes);v.hintCamera=null;v.hintLights=null;v.hintAttributes=null;
		if(hints!=null){
			Object c=hints.props.get("camera"),l=hints.props.get("lights"),at=hints.props.get("attributes"),cc=hints.props.get("clearColor");
			if(c instanceof Q3.Camera)v.hintCamera=Q3.ref((Q3.Camera)c);
			if(l instanceof Q3.Group)v.hintLights=Q3.ref((Q3.Group)l);
			if(at instanceof Q3.AttrSet)v.hintAttributes=Q3.ref((Q3.AttrSet)at);
			if(cc instanceof float[]){System.arraycopy((float[])cc,0,v.background,0,4);background(v);}
		}
		if(fromData)lights(v);
		String dump=System.getProperty("hzqd3d.viewerdump");
		if(dump!=null){Q3Metafile.Writer w=new Q3Metafile.Writer(true);for(Q3.Group.Pos p:g.list)w.write(p.obj);java.nio.file.Files.write(java.nio.file.Paths.get(dump),w.finish());}
		// the model's bounds: its sphere scales it, its box puts hither and yon
		Q3View b=v.view;float[] sphere=null,box=null;
		if(b.startBounds(true,false)){try{b.submit(g);}finally{b.endBounds();}sphere=b.sphere();if(b.bmin!=null)box=new float[]{b.bmin[0],b.bmin[1],b.bmin[2],b.bmax[0],b.bmax[1],b.bmax[2]};}
		if(sphere==null||box==null||sphere[3]<=0){v.centre=new float[]{0,0,0};v.radius=0;v.halfDiagonal=0;}
		else{
			v.centre=new float[]{sphere[0],sphere[1],sphere[2]};v.radius=sphere[3];
			float dx=box[3]-box[0],dy=box[4]-box[1],dz=box[5]-box[2];v.halfDiagonal=(float)Math.sqrt(dx*dx+dy*dy+dz*dz)/2;
		}
		restore(v);
	}
	private static void defaultCamera(Q3.Camera c,float x,float y,float z){
		c.pointOfInterest[0]=c.pointOfInterest[1]=c.pointOfInterest[2]=0;
		c.location[0]=x*DISTANCE;c.location[1]=y*DISTANCE;c.location[2]=z*DISTANCE;
		c.up[0]=0;c.up[1]=y!=0?0:1;c.up[2]=y>0?-1:y<0?1:0;
		c.fov=FOV;c.hither=10;c.yon=1500;c.edited();
	}
	private static void copyCamera(Q3.Camera to,Q3.Camera from){
		System.arraycopy(from.location,0,to.location,0,3);System.arraycopy(from.pointOfInterest,0,to.pointOfInterest,0,3);System.arraycopy(from.up,0,to.up,0,3);
		to.hither=from.hither;to.yon=from.yon;to.fov=from.fov;to.edited();
	}
	/** The first view: the view hints' camera, or the viewer's own from the front. */
	private void restore(Viewer v){
		v.rotation=Q3.identity();v.cameraView=0;
		if(v.hintCamera!=null&&v.hintCamera.leaf()==Q3.VIEW_ANGLE_ASPECT)copyCamera(v.camera,v.hintCamera);
		else defaultCamera(v.camera,0,0,1);
		place(v);aspect(v);
	}
	/** Puts the model at the camera's point of interest, its bounding sphere as big as the camera's view there makes it. */
	private static void place(Viewer v){
		Q3.Camera c=v.camera;v.anchor=c.pointOfInterest.clone();
		float d=(float)Math.sqrt(sq(c.location[0]-c.pointOfInterest[0])+sq(c.location[1]-c.pointOfInterest[1])+sq(c.location[2]-c.pointOfInterest[2]));
		v.scale=v.radius>0?d*c.fov/2*FIT/v.radius:1;
	}
	/** A camera view (kQ3ViewerCameraFront ... Bottom; Fit shows the front): the viewer's camera around the model, placed again. */
	private void cameraView(Viewer v,int view){
		if(view==0){restore(v);return;}
		v.rotation=Q3.identity();
		switch(view){case 3:defaultCamera(v.camera,0,0,-1);break;case 4:defaultCamera(v.camera,-1,0,0);break;case 5:defaultCamera(v.camera,1,0,0);break;
			case 6:defaultCamera(v.camera,0,1,0);break;case 7:defaultCamera(v.camera,0,-1,0);break;default:defaultCamera(v.camera,0,0,1);break;}
		v.cameraView=view;place(v);aspect(v);
	}
	private boolean useData(Viewer v,byte[] data) throws Exception {
		Q3Metafile.Reader r=Q3Metafile.read(data,t->q.elementSize(t));if(r==null)return false;
		Q3.Group g=new Q3.Group(Q3.DISPLAY_GROUP);Q3.Plain hints=null;
		try{Q3.Obj o;while((o=r.readNext())!=null){
			if(o.leaf()==Q3.VIEW_HINTS&&o instanceof Q3.Plain&&hints==null){hints=(Q3.Plain)o;continue;}
			if(Q3.drawable(o))g.add(o);
			Q3.release(o);}}
		finally{r.close();}
		try{useGroup(v,g,hints,true);}finally{Q3.release(hints);}
		return true;
	}
	/** The model as 3DMF, with view hints: the camera as it sees the model's own coordinates, the lights, the clear colour. */
	private byte[] write(Viewer v,boolean text){
		Q3Metafile.Writer w=new Q3Metafile.Writer(text);
		if(v.group!=null)for(Q3.Group.Pos p:v.group.list)w.write(p.obj);
		Q3.Plain hints=new Q3.Plain(Q3.VIEW_HINTS);
		Q3.Camera c=(Q3.Camera)v.camera.duplicate();
		{float[] inv=Q3.invert(modelMatrix(v));
			if(inv!=null){float[] l=c.location.clone(),p=c.pointOfInterest.clone();Q3.transformPoint(inv,l,0);Q3.transformPoint(inv,p,0);
				float[] u=c.up.clone();Q3.transformVector(inv,u,0);float ul=(float)Math.sqrt(u[0]*u[0]+u[1]*u[1]+u[2]*u[2]);
				System.arraycopy(l,0,c.location,0,3);System.arraycopy(p,0,c.pointOfInterest,0,3);if(ul>0)for(int k=0;k<3;k++)c.up[k]=u[k]/ul;
				range(v);c.hither=v.camera.hither/v.scale;c.yon=v.camera.yon/v.scale;}}
		hints.props.put("renderer",Q3.ref(v.view.renderer));hints.props.put("camera",c);hints.props.put("lights",Q3.ref(v.view.lightGroup));
		hints.props.put("clearColor",v.background.clone());
		int[] cr=contentArea(v);hints.props.put("width",cr[2]-cr[0]);hints.props.put("height",cr[3]-cr[1]);
		w.write(hints);Q3.release(hints);
		return w.finish();
	}
	private void keepUndo(Viewer v){Q3.release(v.undoGroup);v.undoGroup=v.group==null?null:Q3.ref(v.group);}
	/** The model's own transform: from its centre to the anchor, scaled and orbited. */
	private static float[] modelMatrix(Viewer v){
		float[] c=v.centre,a=v.anchor;
		return Q3.mul(Q3.mul(Q3.mul(Q3.translate(-c[0],-c[1],-c[2]),Q3.scale(v.scale,v.scale,v.scale)),v.rotation),Q3.translate(a[0],a[1],a[2]));
	}
	/** Hither and yon around the placed model (1.2 box radii either side, as Apple's puts them when drawing). */
	private static void range(Viewer v){
		Q3.Camera c=v.camera;float[] a=v.anchor;float d=(float)Math.sqrt(sq(c.location[0]-a[0])+sq(c.location[1]-a[1])+sq(c.location[2]-a[2]));
		float r=v.halfDiagonal*v.scale*RANGE_MARGIN;
		if(r<=0){c.hither=d;c.yon=d+1;}else{c.hither=Math.max(d-r,Math.max(0.01f,d*0.001f));c.yon=d+r>c.hither?d+r:c.hither+1;}
		c.edited();
	}

	// ================================================================== drawing

	private void draw(Viewer v,boolean content,boolean strip) throws Exception {
		if(v.port==0)return;
		int save=q.alloc(4);
		try{
			q.tb("GetPort",save);q.tb("SetPort",v.port);
			if(content){render(v);border(v);}
			if(strip)strip(v);
			if(v.drawingCallback!=0)rt.cpu.call(v.drawingCallback,q.handle(v),v.drawingData);
			q.tb("SetPort",memory.readLong(save));
		}finally{q.free(save);}
	}
	private void render(Viewer v) throws Exception {
		Q3View view=v.view;
		range(v);
		if(!view.startRendering())return;
		try{
			Q3.Style bf=new Q3.Style(Q3.BACKFACING);bf.value=v.removeBackfaces?1:0;view.style(bf);
			Q3.Style in=new Q3.Style(Q3.INTERPOLATION);in.value=1;view.style(in); // smooth: the viewer shades per vertex
			view.shader(new Q3.Shader(v.phong?Q3.PHONG:Q3.LAMBERT));
			if(v.hintAttributes!=null)view.submit(v.hintAttributes);
			view.transform(modelMatrix(v));
			if(v.group!=null)view.submit(v.group);
		}finally{view.endRendering();}
	}
	private void colour(String trap,int r,int g,int b) throws M68kException {int p=q.alloc(6);memory.writeWord(p,r);memory.writeWord(p+2,g);memory.writeWord(p+4,b);q.tb(trap,p);q.free(p);}
	private void rect(String op,int l,int t,int r,int b) throws M68kException {int p=q.alloc(8);memory.writeWord(p,t);memory.writeWord(p+2,l);memory.writeWord(p+4,b);memory.writeWord(p+6,r);q.tb(op,p);q.free(p);}
	/** The frame and the drag border (a dotted pattern) around the pane. */
	private void border(Viewer v) throws Exception {
		if((v.flags&(DRAW_FRAME|DRAW_DRAG_BORDER))==0)return;
		int[] c=contentArea(v);
		colour("RGBForeColor",0,0,0);colour("RGBBackColor",0xffff,0xffff,0xffff);
		if((v.flags&DRAW_FRAME)!=0)rect("FrameRect",c[0],c[1],c[2],c[3]);
		if((v.flags&DRAW_DRAG_BORDER)!=0){
			int f=(v.flags&DRAW_FRAME)!=0?1:0;int pat=q.alloc(8);
			for(int i=0;i<8;i++)memory.writeByte(pat+i,(i&1)==0?0xaa:0); // dots on every other row
			q.tb("PenPat",pat);q.tb("PenSize",DRAG_BORDER,DRAG_BORDER);
			rect("FrameRect",c[0]+f,c[1]+f,c[2]-f,c[3]-f);
			q.tb("PenNormal");q.free(pat);
		}
	}
	private static int stripWidth(Viewer v){
		if((v.flags&CONTROLLER_VISIBLE)==0)return 0;
		int w=0;for(int b:BUTTONS)if((v.flags&b)!=0)w+=(b==BUTTON_CAMERA?CAMERA_WIDTH:BUTTON_WIDTH)+(b==BUTTON_RESET&&w>0?RESET_GAP:0);
		return w;
	}
	/** Where a button sits in the strip (left, top, right, bottom), or null when the viewer has none. */
	private static int[] buttonRect(Viewer v,int button){
		if((v.flags&CONTROLLER_VISIBLE)==0||(v.flags&button)==0)return null;
		int start=v.left+Math.max(STRIP_MARGIN,(v.right-v.left-stripWidth(v))/2),x=start;
		for(int b:BUTTONS){
			if((v.flags&b)==0)continue;
			if(b==BUTTON_RESET&&x>start)x+=RESET_GAP;
			int w=b==BUTTON_CAMERA?CAMERA_WIDTH:BUTTON_WIDTH;
			if(b==button)return new int[]{x,v.bottom-STRIP,x+w,v.bottom};
			x+=w;
		}
		return null;
	}
	/** The control strip, drawn in Java and copied into the port. */
	private void strip(Viewer v) throws Exception {
		if((v.flags&CONTROLLER_VISIBLE)==0)return;
		int w=v.right-v.left;if(w<=0)return;
		BufferedImage img=new BufferedImage(w,STRIP,BufferedImage.TYPE_INT_RGB);
		Graphics2D g=img.createGraphics();
		g.setColor(new Color(0xcccccc));g.fillRect(0,0,w,STRIP);
		for(int b:BUTTONS){int[] r=buttonRect(v,b);if(r!=null)button(g,b,r[0]-v.left,0,r[2]-r[0],STRIP,b==v.button);}
		g.dispose();
		blit(v.port,img,v.left,v.bottom-STRIP);
	}
	private static void button(Graphics2D g,int b,int x,int y,int w,int h,boolean down){
		g.setColor(new Color(0x555555));g.drawRect(x,y,w-1,h-1);
		g.setColor(new Color(down?0x999999:0xcccccc));g.fillRect(x+1,y+1,w-2,h-2);
		g.setColor(new Color(down?0x666666:0xffffff));g.drawLine(x+1,y+1,x+w-3,y+1);g.drawLine(x+1,y+1,x+1,y+h-3);
		g.setColor(new Color(down?0xbbbbbb:0x777777));g.drawLine(x+2,y+h-2,x+w-2,y+h-2);g.drawLine(x+w-2,y+2,x+w-2,y+h-2);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		int o=down?1:0;int cx=x+(b==BUTTON_CAMERA?15:w/2)+o,cy=y+h/2+o;
		Color dark=new Color(0x333333),red=new Color(0xcc0000),face=new Color(0xeeeeee),side=new Color(0xaaaaaa);
		g.setStroke(new BasicStroke(1.2f));
		switch(b){
			case BUTTON_CAMERA:{
				g.setColor(new Color(0x666666));g.fillRoundRect(cx-9,cy-5,18,12,3,3);g.fillRect(cx-4,cy-8,8,4);
				g.setColor(dark);g.drawRoundRect(cx-9,cy-5,18,12,3,3);
				g.setColor(new Color(0x3355cc));g.fillOval(cx-4,cy-3,8,8);g.setColor(new Color(0x99bbff));g.fillOval(cx-2,cy-1,3,3);
				// the pop-up mark
				Path2D t=new Path2D.Float();t.moveTo(x+w-10+o,cy+1);t.lineTo(x+w-4+o,cy+1);t.lineTo(x+w-7+o,cy+4);t.closePath();g.setColor(Color.BLACK);g.fill(t);
				break;}
			case BUTTON_TRUCK:
				cube(g,cx,cy,face,side,dark);
				arrow(g,red,cx,cy+6,cx,cy+11,3);arrow(g,red,cx,cy-7,cx,cy-11,3);arrow(g,red,cx+8,cy,cx+12,cy,3);arrow(g,red,cx-7,cy,cx-11,cy,3);
				break;
			case BUTTON_ORBIT:
				cube(g,cx,cy,face,side,dark);
				g.setColor(red);g.setStroke(new BasicStroke(1.6f));g.draw(new Arc2D.Float(cx-12,cy-7,24,14,200,250,Arc2D.OPEN));
				arrow(g,red,cx-10,cy+3,cx-12,cy-1,3);
				break;
			case BUTTON_ZOOM:
				g.setColor(dark);g.setStroke(new BasicStroke(1.6f));g.drawOval(cx-8,cy-8,12,12);g.setStroke(new BasicStroke(3f));g.drawLine(cx+3,cy+3,cx+8,cy+8);
				g.setColor(red);g.setStroke(new BasicStroke(1.4f));g.drawLine(cx-5,cy-2,cx+1,cy-2);g.drawLine(cx-2,cy-5,cx-2,cy+1);
				break;
			case BUTTON_DOLLY:
				cube(g,cx-3,cy-3,face,side,dark);
				arrow(g,red,cx+3,cy+3,cx+10,cy+10,3);
				break;
			case BUTTON_RESET:{
				Path2D house=new Path2D.Float();house.moveTo(cx-8,cy-1);house.lineTo(cx,cy-9);house.lineTo(cx+8,cy-1);house.lineTo(cx+6,cy-1);house.lineTo(cx+6,cy+8);
				house.lineTo(cx-6,cy+8);house.lineTo(cx-6,cy-1);house.closePath();
				g.setColor(face);g.fill(house);g.setColor(dark);g.draw(house);g.setColor(new Color(0x886644));g.fillRect(cx-2,cy+3,4,5);
				break;}
			default:break;
		}
		g.setStroke(new BasicStroke(1f));g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
	}
	/** A small cube in an oblique view centred at (cx, cy). */
	private static void cube(Graphics2D g,int cx,int cy,Color face,Color side,Color edge){
		int s=8,d=3;int x=cx-s/2-1,y=cy-s/2+1;
		Path2D top=new Path2D.Float();top.moveTo(x,y);top.lineTo(x+d,y-d);top.lineTo(x+s+d,y-d);top.lineTo(x+s,y);top.closePath();
		Path2D right=new Path2D.Float();right.moveTo(x+s,y);right.lineTo(x+s+d,y-d);right.lineTo(x+s+d,y+s-d);right.lineTo(x+s,y+s);right.closePath();
		g.setColor(face);g.fillRect(x,y,s,s);g.setColor(side);g.fill(top);g.setColor(side.darker());g.fill(right);
		g.setColor(edge);g.drawRect(x,y,s,s);g.draw(top);g.draw(right);
	}
	private static void arrow(Graphics2D g,Color c,float x0,float y0,float x1,float y1,float head){
		g.setColor(c);g.setStroke(new BasicStroke(1.6f));g.draw(new Line2D.Float(x0,y0,x1,y1));
		double a=Math.atan2(y1-y0,x1-x0);Path2D p=new Path2D.Float();p.moveTo(x1+Math.cos(a)*1.5,y1+Math.sin(a)*1.5);
		p.lineTo(x1-Math.cos(a-0.6)*head*1.4,y1-Math.sin(a-0.6)*head*1.4);p.lineTo(x1-Math.cos(a+0.6)*head*1.4,y1-Math.sin(a+0.6)*head*1.4);p.closePath();g.fill(p);
	}
	/** Copies an image into {@code port} at (x, y) through an offscreen GWorld. */
	private void blit(int port,BufferedImage img,int x,int y) throws Exception {
		int w=img.getWidth(),h=img.getHeight();
		int gwp=q.alloc(4),r=q.alloc(8),dst=q.alloc(8),fore=q.alloc(6),back=q.alloc(6);
		try{
			memory.writeWord(r,0);memory.writeWord(r+2,0);memory.writeWord(r+4,h);memory.writeWord(r+6,w);
			memory.writeWord(dst,y);memory.writeWord(dst+2,x);memory.writeWord(dst+4,y+h);memory.writeWord(dst+6,x+w);
			if(q.tb("NewGWorld",gwp,32,r,0,0,0)!=0)return;
			int gw=memory.readLong(gwp);
			try{
				int pm=q.tb("GetGWorldPixMap",gw);q.tb("LockPixels",pm);int base=q.tb("GetPixBaseAddr",pm);int rb=memory.readWord(memory.readLong(pm)+4)&0x3fff;
				for(int j=0;j<h;j++)for(int i=0;i<w;i++)memory.writeLong(base+j*rb+4*i,img.getRGB(i,j)&0xffffff);
				q.tb("GetForeColor",fore);q.tb("GetBackColor",back);
				colour("RGBForeColor",0,0,0);colour("RGBBackColor",0xffff,0xffff,0xffff);
				q.tb("CopyBits",gw+2,port+2,r,dst,0,0);
				q.tb("RGBForeColor",fore);q.tb("RGBBackColor",back);
				q.tb("UnlockPixels",pm);
			}finally{q.tb("DisposeGWorld",gw);}
		}finally{q.free(gwp);q.free(r);q.free(dst);q.free(fore);q.free(back);}
	}

	// ================================================================== the mouse

	private boolean tracking;private int lastX,lastY;
	/** Q3ViewerEvent: a click in the strip picks a button (camera pops up its views, reset acts at once); in the pane it moves the model until release. */
	private boolean event(Viewer v,int e) throws Exception {
		int what=memory.readWord(e);
		if(what!=1||(v.flags&ACTIVE)==0)return false; // mouseDown only
		int save=q.alloc(8);
		try{
			q.tb("GetPort",save);q.tb("SetPort",v.port);
			memory.writeLong(save+4,memory.readLong(e+10));q.tb("GlobalToLocal",save+4);
			int y=(short)memory.readWord(save+4),x=(short)memory.readWord(save+6);
			boolean handled=mouseDown(v,x,y,true);
			q.tb("SetPort",memory.readLong(save));
			return handled;
		}finally{q.free(save);}
	}
	private boolean mouseDown(Viewer v,int x,int y,boolean loop) throws Exception {
		if(x<v.left||x>=v.right||y<v.top||y>=v.bottom)return false;
		for(int b:BUTTONS){int[] r=buttonRect(v,b);
			if(r!=null&&x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3]){
				if(b==BUTTON_RESET){restore(v);draw(v,true,true);}
				else if(b==BUTTON_CAMERA){int view=cameraMenu(r);if(view>=0&&hasModel(v)){cameraView(v,view);draw(v,true,true);}}
				else{v.button=b;draw(v,false,true);}
				return true;
			}}
		int[] c=pane(v);
		if(x<c[0]||x>=c[2]||y<c[1]||y>=c[3])return true;
		tracking=true;lastX=x;lastY=y;
		if(!loop)return true;
		int pt=q.alloc(4);
		try{
			while(q.tb("StillDown")!=0){
				q.tb("GetMouse",pt);int my=(short)memory.readWord(pt),mx=(short)memory.readWord(pt+2);
				if(mx!=lastX||my!=lastY)track(v,mx,my);
			}
		}finally{q.free(pt);tracking=false;}
		return true;
	}
	private static final String[] CAMERA_ITEMS={"Fit To View","Front View","Back View","Left View","Right View","Top View","Bottom View"};
	private static final int CAMERA_MENU=0x3d3;
	/** The camera button's pop-up menu of views; the view chosen (1 fit ... 7 bottom), or -1. */
	private int cameraMenu(int[] r) throws Exception {
		int title=q.alloc(2);memory.writeByte(title,0);int menu=q.tb("NewMenu",CAMERA_MENU,title);q.free(title);
		if(menu==0)return -1;
		try{
			for(String s:CAMERA_ITEMS){byte[] b=s.getBytes(PpcQuickDraw3D.MAC_ROMAN);int p=q.alloc(b.length+1);memory.writeByte(p,b.length);memory.load(p+1,b);q.tb("AppendMenu",menu,p);q.free(p);}
			q.tb("InsertMenu",menu,-1);
			int pt=q.alloc(4);memory.writeWord(pt,r[3]);memory.writeWord(pt+2,r[0]);q.tb("LocalToGlobal",pt);
			int res=q.tb("PopUpMenuSelect",menu,(short)memory.readWord(pt),(short)memory.readWord(pt+2),1);q.free(pt);
			q.tb("DeleteMenu",CAMERA_MENU);
			int item=res&0xffff;return item<=0?-1:item;
		}finally{q.tb("DisposeMenu",menu);}
	}
	/** Moves by the mouse's travel since the last point, with the current button's motion. */
	private boolean track(Viewer v,int x,int y) throws Exception {
		if(!tracking)return false;
		float dx=x-lastX,dy=y-lastY;lastX=x;lastY=y;
		Q3.Camera c=v.camera;int[] pane=pane(v);
		float dist=(float)Math.sqrt(sq(c.location[0]-c.pointOfInterest[0])+sq(c.location[1]-c.pointOfInterest[1])+sq(c.location[2]-c.pointOfInterest[2]));
		float scale=2*dist*(float)Math.tan(c.fov/2)/Math.max(1,pane[3]-pane[1]);
		float[] wv=c.worldToView();
		// the camera's axes in world coordinates: the rows of the view rotation
		float rx=wv[0],ry=wv[4],rz=wv[8],ux=wv[1],uy=wv[5],uz=wv[9],fx=wv[2],fy=wv[6],fz=wv[10];
		switch(v.button){
			case BUTTON_ORBIT:{
				float[] r1=Q3.rotateAboutAxis(0,0,0,ux,uy,uz,dx*0.01f),r2=Q3.rotateAboutAxis(0,0,0,rx,ry,rz,dy*0.01f);
				v.rotation=Q3.mul(Q3.mul(v.rotation,r1),r2);break;}
			case BUTTON_TRUCK:{float mx=-dx*scale,my=dy*scale;
				for(float[] p:new float[][]{c.location,c.pointOfInterest}){p[0]+=rx*mx+ux*my;p[1]+=ry*mx+uy*my;p[2]+=rz*mx+uz*my;}c.edited();break;}
			case BUTTON_ZOOM:c.fov=(float)Math.max(0.05,Math.min(2.8,c.fov*(1+dy*0.01)));c.edited();break;
			case BUTTON_DOLLY:{float m=Math.min(dy*scale,dist*0.95f);c.location[0]-=fx*m;c.location[1]-=fy*m;c.location[2]-=fz*m;c.edited();break;}
			default:break;
		}
		int save=q.alloc(4);
		try{q.tb("GetPort",save);q.tb("SetPort",v.port);render(v);q.tb("SetPort",memory.readLong(save));}finally{q.free(save);}
		return true;
	}
	private static float sq(float f){return f*f;}

	// ================================================================== clipboard and pictures

	private int copy(Viewer v) throws Exception {
		byte[] b=write(v,false);
		q.tb("ZeroScrap");
		int p=q.alloc(b.length);memory.load(p,b);int err=q.tb("PutScrap",b.length,Q3.fc("3DMF"),p);q.free(p);
		int pic=pict(v);
		if(pic!=0){int size=q.tb("GetHandleSize",pic);q.tb("HLock",pic);q.tb("PutScrap",size,Q3.fc("PICT"),memory.readLong(pic));q.tb("KillPicture",pic);}
		return err;
	}
	private int paste(Viewer v) throws Exception {
		int h=q.tb("NewHandle",0);int off=q.alloc(4);
		try{
			int n=q.tb("GetScrap",h,Q3.fc("3DMF"),off);
			if(n<=0)return -102;
			keepUndo(v);
			byte[] b=memory.readBytes(memory.readLong(h),n);
			boolean ok=useData(v,b);draw(v,true,true);return ok?0:-1;
		}finally{q.free(off);q.tb("DisposeHandle",h);}
	}
	/** A picture of the pane (the model as drawn). */
	private int pict(Viewer v) throws Exception {
		if(v.port==0)return 0;
		int save=q.alloc(4+8);
		try{
			q.tb("GetPort",save);q.tb("SetPort",v.port);
			render(v);
			int[] c=pane(v);int r=save+4;memory.writeWord(r,c[1]);memory.writeWord(r+2,c[0]);memory.writeWord(r+4,c[3]);memory.writeWord(r+6,c[2]);
			int pic=q.tb("OpenPicture",r);
			// the pane's pixels, recorded as a CopyBits of the port onto itself
			q.tb("CopyBits",v.port+2,v.port+2,r,r,0,0);
			q.tb("ClosePicture");
			q.tb("SetPort",memory.readLong(save));
			return pic;
		}finally{q.free(save);}
	}
}

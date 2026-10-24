import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A QuickDraw 3D view (TQ3ViewObject): the renderer, camera, light group and draw context it is given, and the
 * traversal of what a program submits between Q3View_Start... and Q3View_End... - rendering, picking, bounding, writing.
 * <p>
 * Rendering is the Interactive Renderer's pipeline in Java: vertices go from local to world coordinates (where the lights
 * are), are lit per vertex (NULL, Lambert or Phong illumination), go to the camera's frustum, are clipped there and
 * mapped to the draw context's pane, and are drawn by the RAVE rasterizer ({@link RaveRenderer}) - Gouraud or
 * texture-mapped, depth-buffered. Transparent triangles wait until the end of the frame and are drawn back to front.
 */
final class Q3View extends Q3.Obj {
	Q3View(){super(Q3.VIEW);}

	Q3.Renderer renderer;Q3.Camera camera;Q3.Group lightGroup;Q3.DrawContext drawContext;
	final Q3.AttrSet defaults=defaultAttributes();
	/** idle, idle progress and end frame methods of the program (transition vectors) and their data */
	int idleMethod,idleData,progressMethod,progressData,endFrameMethod,endFrameData;
	boolean allowGroupCulling=true;

	static final int IDLE=0,RENDERING=1,PICKING=2,BOUNDING_BOX=3,BOUNDING_SPHERE=4,WRITING=5;
	int mode=IDLE;
	static final int STATUS_DONE=0,STATUS_RETRAVERSE=1,STATUS_ERROR=2,STATUS_CANCELLED=3;

	@Override void dropRefs(){Q3.release(renderer);Q3.release(camera);Q3.release(lightGroup);Q3.release(drawContext);renderer=null;camera=null;lightGroup=null;drawContext=null;super.dropRefs();}

	static Q3.AttrSet defaultAttributes(){
		Q3.AttrSet s=new Q3.AttrSet();
		s.put(Q3.ATTR_AMBIENT_COEFFICIENT,new float[]{1});
		s.put(Q3.ATTR_DIFFUSE,new float[]{1,1,1});
		s.put(Q3.ATTR_SPECULAR,new float[]{0.5f,0.5f,0.5f});
		s.put(Q3.ATTR_SPECULAR_CONTROL,new float[]{4});
		s.put(Q3.ATTR_TRANSPARENCY,new float[]{1,1,1});
		s.put(Q3.ATTR_HIGHLIGHT_STATE,0);
		return s;
	}

	// ================================================================== the drawing surface

	/** Where a frame goes: the front end makes it from the draw context (a window, a pixmap) with a RAVE context behind it. */
	interface Surface {
		/** Starts a frame: the renderer to draw into (its buffer is the pane), cleared or not as the draw context says; null when nothing can be drawn. */
		RaveRenderer begin(Q3View v) throws Exception;
		/** Ends the frame: shows it. */
		void end(Q3View v) throws Exception;
		/** The pane in window coordinates: left, top, right, bottom. */
		float[] pane();
	}
	Surface surface;
	/** The front end's texture maker (reads a texture's storage into ARGB levels). */
	interface Textures { RaveRenderer.Texture get(Q3.Texture t); }
	Textures textures;

	// ================================================================== state

	/** The view state: matrices, attributes, styles, shaders. */
	static final class State {
		float[] localToWorld=Q3.identity();
		/** attribute values: diffuse 3, specular 3, transparency 3, specular control, ambient coefficient */
		float dr=1,dg=1,db=1,sr=.5f,sg=.5f,sb=.5f,tr=1,tg=1,tb=1,specCtl=4,ambient=1;
		int highlightState;
		Q3.Obj surfaceShader;
		int illumination;
		int backfacing=0,interpolation=0,fill=0,orientation=0,pickID,pickParts;boolean receiveShadows=true;
		Q3.AttrSet highlight;
		int subMethod=2;float subC1=20,subC2=20;
		float[] antiAlias;
		/** fog: state, mode, start, end, density, a r g b (null: no fog style submitted) */
		float[] fog;
		State copy(){State c=new State();c.localToWorld=localToWorld.clone();c.dr=dr;c.dg=dg;c.db=db;c.sr=sr;c.sg=sg;c.sb=sb;c.tr=tr;c.tg=tg;c.tb=tb;c.specCtl=specCtl;c.ambient=ambient;
			c.highlightState=highlightState;c.surfaceShader=surfaceShader;c.illumination=illumination;c.backfacing=backfacing;c.interpolation=interpolation;c.fill=fill;c.orientation=orientation;
			c.pickID=pickID;c.pickParts=pickParts;c.receiveShadows=receiveShadows;c.highlight=highlight;c.subMethod=subMethod;c.subC1=subC1;c.subC2=subC2;c.antiAlias=antiAlias;c.fog=fog;return c;}
	}
	State s=new State();
	private final ArrayDeque<State> stack=new ArrayDeque<State>();
	/** Matrices of the frame. */
	float[] worldToView=Q3.identity(),viewToFrustum=Q3.identity(),worldToFrustum=Q3.identity(),frustumToWindow=Q3.identity();
	private float[] localToFrustum,normalMatrix;private float[] cachedFor;
	private float camX,camY,camZ;private boolean orthographic;private float viewDirX,viewDirY,viewDirZ;

	/** The state at the start of a traversal: the view's default attribute set and styles. */
	private void resetState(){
		s=new State();stack.clear();
		applyAttributes(defaults);
		s.illumination=Q3.NULL_ILLUMINATION; // with no illumination shader submitted, the Interactive Renderer draws unlit
	}

	// ================================================================== starting and ending

	RaveRenderer r;
	private float paneW,paneH;
	int error;
	/** The count of frames rendered (to know a texture was made in this frame). */
	int frame;

	/** Q3View_StartRendering: false with an error code when something is missing. */
	boolean startRendering() throws Exception {
		cancelled=false;
		if(mode!=IDLE){error=-28404;return false;} // kQ3ErrorAlreadyRendering
		if(renderer==null){error=-28417;return false;}
		if(camera==null){error=-28413;return false;}
		if(drawContext==null||surface==null){error=-28412;return false;}
		resetState();
		frameMatrices();
		lamps();
		transparent.clear();
		r=renderer.leaf()==Q3.GENERIC?null:surface.begin(this);
		frame++;
		mode=RENDERING;
		return true;
	}
	/** Q3View_EndRendering: draws what waited (transparent triangles) and shows the frame. */
	int endRendering() throws Exception {
		if(mode!=RENDERING){if(cancelled){cancelled=false;return STATUS_CANCELLED;}error=-28410;return STATUS_ERROR;}
		try{
			if(r!=null){drawTransparent();surface.end(this);}
		}finally{mode=IDLE;r=null;stack.clear();}
		return STATUS_DONE;
	}
	/** Q3View_Cancel: the traversal stops; the End call that follows reports kQ3ViewStatusCancelled. */
	void cancel(){if(mode==RENDERING){transparent.clear();r=null;}if(mode!=IDLE)cancelled=true;mode=IDLE;stack.clear();}
	private boolean cancelled;

	/** The frame's matrices from the camera and the pane. */
	void frameMatrices(){
		Q3.Camera c=camera;
		if(c!=null){worldToView=c.worldToView();viewToFrustum=c.viewToFrustum();worldToFrustum=Q3.mul(worldToView,viewToFrustum);
			camX=c.location[0];camY=c.location[1];camZ=c.location[2];orthographic=c.leaf()==Q3.ORTHOGRAPHIC;
			float dx=c.location[0]-c.pointOfInterest[0],dy=c.location[1]-c.pointOfInterest[1],dz=c.location[2]-c.pointOfInterest[2];float l=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);if(l==0)l=1;
			viewDirX=dx/l;viewDirY=dy/l;viewDirZ=dz/l;}
		float[] p=surface!=null?surface.pane():null;
		if(p==null)p=new float[]{0,0,1,1};
		paneW=p[2]-p[0];paneH=p[3]-p[1];
		frustumToWindow=new float[]{paneW/2,0,0,0, 0,-paneH/2,0,0, 0,0,1,0, p[0]+paneW/2,p[1]+paneH/2,0,1};
		cachedFor=null;
	}
	private void matrices(){
		if(cachedFor==s.localToWorld)return;
		localToFrustum=Q3.mul(s.localToWorld,worldToFrustum);
		float[] inv=Q3.invert(s.localToWorld);normalMatrix=inv==null?s.localToWorld:Q3.transpose(inv);
		cachedFor=s.localToWorld;
	}

	// ================================================================== submitting

	/** Q3Push_Submit / Q3Pop_Submit. */
	void push(){stack.push(s.copy());if(mode==PICKING)pathMarks.push(path.size());}
	boolean pop(){if(stack.isEmpty())return false;s=stack.pop();if(mode==PICKING&&!pathMarks.isEmpty())pathMarks.pop();return true;}

	/** Submits an object (Q3Object_Submit and every Q3*_Submit of data). */
	void submit(Q3.Obj o) throws Exception {
		if(o==null||mode==IDLE)return;
		if(mode==WRITING){if(writer!=null)writer.write(o,this);return;}
		if(o instanceof Q3.Geometry){geometry((Q3.Geometry)o);return;}
		if(o instanceof Q3.Group){group((Q3.Group)o);return;}
		if(o instanceof Q3.Transform){transform(((Q3.Transform)o).matrix());if(o.leaf()==Q3.RESET)s.localToWorld=Q3.identity();return;}
		if(o instanceof Q3.AttrSet){applyAttributes((Q3.AttrSet)o);return;}
		if(o instanceof Q3.Style){style((Q3.Style)o);return;}
		if(o instanceof Q3.Shader){shader((Q3.Shader)o);return;}
		if(o instanceof Q3.StateOperator){if(o.leaf()==Q3.PUSH)push();else pop();return;}
		// lights, cameras, strings and unknown objects draw nothing
	}
	void transform(float[] m){s.localToWorld=Q3.mul(m,s.localToWorld);}
	void resetTransform(){s.localToWorld=Q3.identity();}

	/** Attributes of a set become the state's (custom ones are not drawn). */
	void applyAttributes(Q3.AttrSet a){
		if(a==null)return;
		for(java.util.Map.Entry<Integer,Object> e:a.map.entrySet())attribute(e.getKey(),e.getValue());
	}
	void attribute(int type,Object v){
		float[] f=v instanceof float[]?(float[])v:null;
		switch(type){
			case Q3.ATTR_DIFFUSE:if(f!=null){s.dr=f[0];s.dg=f[1];s.db=f[2];}break;
			case Q3.ATTR_SPECULAR:if(f!=null){s.sr=f[0];s.sg=f[1];s.sb=f[2];}break;
			case Q3.ATTR_TRANSPARENCY:if(f!=null){s.tr=f[0];s.tg=f[1];s.tb=f[2];}break;
			case Q3.ATTR_SPECULAR_CONTROL:if(f!=null)s.specCtl=f[0];break;
			case Q3.ATTR_AMBIENT_COEFFICIENT:if(f!=null)s.ambient=f[0];break;
			case Q3.ATTR_HIGHLIGHT_STATE:if(v instanceof Integer)s.highlightState=(Integer)v;break;
			case Q3.ATTR_SURFACE_SHADER:s.surfaceShader=v instanceof Q3.Obj?(Q3.Obj)v:null;break;
			default:break;
		}
	}
	void style(Q3.Style st){
		int t=st.leaf();int v=st.value;
		if(t==Q3.BACKFACING)s.backfacing=v;else if(t==Q3.INTERPOLATION)s.interpolation=v;else if(t==Q3.FILL)s.fill=v;
		else if(t==Q3.ORIENTATION)s.orientation=v;else if(t==Q3.PICK_ID)s.pickID=v;else if(t==Q3.PICK_PARTS)s.pickParts=v;
		else if(t==Q3.RECEIVE_SHADOWS)s.receiveShadows=v!=0;else if(t==Q3.HIGHLIGHT)s.highlight=st.highlight;
		else if(t==Q3.SUBDIVISION&&st.data!=null){s.subMethod=(int)st.data[0];s.subC1=st.data[1];s.subC2=st.data[2];}
		else if(t==Q3.ANTIALIAS)s.antiAlias=st.data;
		else if(t==Q3.FOG){s.fog=st.data;if(FOG_TRACE&&fogTraced++<5)System.err.println("[qd3d] fog "+java.util.Arrays.toString(st.data)+" camera hither "+(camera==null?0:camera.hither)+" yon "+(camera==null?0:camera.yon));}
	}
	void shader(Q3.Shader sh){
		if(sh.is(Q3.ILLUMINATION))s.illumination=sh.leaf();
		else if(sh.is(Q3.SURFACE_SHADER))s.surfaceShader=sh;
	}

	/** A group: a display group pushes the state around its objects unless it is inline, and is skipped when not drawn. */
	private void group(Q3.Group g) throws Exception {
		if(!g.display()){if(g.leaf()==Q3.GROUP)for(Q3.Group.Pos p:new ArrayList<Q3.Group.Pos>(g.list))submit(p.obj);return;}
		int flag=mode==PICKING?Q3.DG_STATE_PICKED:mode==WRITING?Q3.DG_STATE_WRITTEN:mode==RENDERING?Q3.DG_STATE_DRAWN:0;
		if(flag!=0&&(g.state&flag)==0)return;
		if(mode==RENDERING&&allowGroupCulling&&(g.state&Q3.DG_STATE_BBOX)!=0&&g.bbox!=null&&!boxVisible(g.bbox))return;
		boolean inline=(g.state&Q3.DG_STATE_INLINE)!=0;
		if(!inline)push();
		try{
			for(Q3.Group.Pos p:new ArrayList<Q3.Group.Pos>(g.list)){
				if(mode==PICKING){if(path.isEmpty()){pathRoot=g;}path.add(p);}
				try{submit(p.obj);}finally{if(mode==PICKING&&!path.isEmpty())path.remove(path.size()-1);}
			}
		}finally{if(!inline)pop();}
	}

	/** Whether a bounding box (min x y z, max x y z in local coordinates) is at least partly inside the frustum. */
	boolean boxVisible(float[] b){
		matrices();float[] m=localToFrustum;
		int out0=0,out1=0,out2=0,out3=0,out4=0,out5=0;
		for(int i=0;i<8;i++){
			float x=b[(i&1)!=0?3:0],y=b[(i&2)!=0?4:1],z=b[(i&4)!=0?5:2];
			float cx=x*m[0]+y*m[4]+z*m[8]+m[12],cy=x*m[1]+y*m[5]+z*m[9]+m[13],cz=x*m[2]+y*m[6]+z*m[10]+m[14],cw=x*m[3]+y*m[7]+z*m[11]+m[15];
			if(cx<-cw)out0++;if(cx>cw)out1++;if(cy<-cw)out2++;if(cy>cw)out3++;if(cz>0)out4++;if(cz<-cw)out5++;
		}
		return out0<8&&out1<8&&out2<8&&out3<8&&out4<8&&out5<8;
	}

	// ================================================================== geometry

	private final Q3Tess.Subdivision subdivision=(q,around,length)->{
		float c=around?s.subC1:s.subC2;
		if(s.subMethod==0)return Math.max(1,(int)c); // constant
		if(s.subMethod==1)return c<=0?16:(int)(length/c); // world space: c is the largest segment
		// screen space: c pixels a segment
		if(q==null)return 16;
		matrices();float[] m=localToFrustum;
		float ox=q.origin[0],oy=q.origin[1],oz=q.origin[2];
		float w=Math.abs(ox*m[3]+oy*m[7]+oz*m[11]+m[15]);if(w<1e-6f)w=1e-6f;
		float scale=Math.max(Math.abs(m[0]),Math.abs(m[5]))*Math.max(paneW,paneH)/2/w;
		float lengthScale=(float)Math.sqrt(Math.abs(Q3View.det3(s.localToWorld)));
		return c<=0?16:(int)Math.ceil(length*scale*Math.cbrt(lengthScale)/c);
	};
	static float det3(float[] m){return m[0]*(m[5]*m[10]-m[6]*m[9])-m[1]*(m[4]*m[10]-m[6]*m[8])+m[2]*(m[4]*m[9]-m[5]*m[8]);}

	void geometry(Q3.Geometry g) throws Exception {
		if(mode==BOUNDING_BOX||mode==BOUNDING_SPHERE){bound(g);return;}
		Q3Tess.Mesh m=Q3Tess.mesh(g,subdivision);
		if(m==null)return;
		if(mode==PICKING){pick(g,m);return;}
		if(mode!=RENDERING||r==null)return;
		draw(m);
	}

	// ================================================================== rendering

	/** A light in world coordinates. */
	private static final class Lamp { int type;float r,g,b;float dx,dy,dz,px,py,pz;int attenuation;float cosHot,cosOuter;int fallOff; }
	private final List<Lamp> lamps=new ArrayList<Lamp>();
	private float ambR,ambG,ambB;
	private void lamps(){
		lamps.clear();ambR=ambG=ambB=0;
		if(lightGroup==null)return;
		for(Q3.Group.Pos p:lightGroup.list){
			if(!(p.obj instanceof Q3.Light))continue;
			Q3.Light l=(Q3.Light)p.obj;if(!l.on)continue;
			float k=l.brightness;
			if(l.leaf()==Q3.AMBIENT){ambR+=l.color[0]*k;ambG+=l.color[1]*k;ambB+=l.color[2]*k;continue;}
			Lamp a=new Lamp();a.type=l.leaf();a.r=l.color[0]*k;a.g=l.color[1]*k;a.b=l.color[2]*k;
			float dx=l.direction[0],dy=l.direction[1],dz=l.direction[2];float dl=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);if(dl==0)dl=1;
			a.dx=-dx/dl;a.dy=-dy/dl;a.dz=-dz/dl; // toward the light
			a.px=l.location[0];a.py=l.location[1];a.pz=l.location[2];a.attenuation=l.attenuation;
			a.cosHot=(float)Math.cos(l.hotAngle);a.cosOuter=(float)Math.cos(l.outerAngle);a.fallOff=l.fallOff;
			lamps.add(a);
		}
	}

	/** Lights a point (world position and unit normal) for the current illumination: diffuse part into lit[0..2], specular into lit[3..5]. */
	private void light(float px,float py,float pz,float nx,float ny,float nz,float dr,float dg,float db,float sr,float sg,float sb,float specCtl,float ambCoef,float[] lit){
		int ill=s.illumination;
		if(ill==Q3.NULL_ILLUMINATION){lit[0]=dr;lit[1]=dg;lit[2]=db;lit[3]=lit[4]=lit[5]=0;return;}
		float lr=ambR*ambCoef,lg=ambG*ambCoef,lb=ambB*ambCoef,hr=0,hg=0,hb=0;
		boolean phong=ill==Q3.PHONG;
		float vx=0,vy=0,vz=0;
		if(phong){if(orthographic){vx=viewDirX;vy=viewDirY;vz=viewDirZ;}else{vx=camX-px;vy=camY-py;vz=camZ-pz;float l=(float)Math.sqrt(vx*vx+vy*vy+vz*vz);if(l>0){vx/=l;vy/=l;vz/=l;}}}
		for(int i=0,n=lamps.size();i<n;i++){
			Lamp a=lamps.get(i);
			float lx,ly,lz,k=1;
			if(a.type==Q3.DIRECTIONAL){lx=a.dx;ly=a.dy;lz=a.dz;}
			else{
				lx=a.px-px;ly=a.py-py;lz=a.pz-pz;float d=(float)Math.sqrt(lx*lx+ly*ly+lz*lz);if(d>0){lx/=d;ly/=d;lz/=d;}
				if(a.attenuation==Q3.ATTENUATION_INVERSE)k=d>0?1/d:1;else if(a.attenuation==Q3.ATTENUATION_INVERSE_SQUARED)k=d>0?1/(d*d):1;
				if(a.type==Q3.SPOT){
					float c=lx*a.dx+ly*a.dy+lz*a.dz; // cos of the angle between the spot's axis and the way from the spot to the point
					if(c<a.cosOuter)continue;
					if(c<a.cosHot){
						float t=(c-a.cosOuter)/(a.cosHot-a.cosOuter);
						switch(a.fallOff){case Q3.FALLOFF_LINEAR:k*=t;break;case Q3.FALLOFF_EXPONENTIAL:k*=t*t;break;case Q3.FALLOFF_COSINE:k*=(float)(0.5-0.5*Math.cos(Math.PI*t));break;default:break;}
					}
				}
			}
			float nl=nx*lx+ny*ly+nz*lz;
			if(nl<=0)continue;
			lr+=a.r*nl*k;lg+=a.g*nl*k;lb+=a.b*nl*k;
			if(phong){
				// the reflection of the light direction against the view direction
				float rx=2*nl*nx-lx,ry=2*nl*ny-ly,rz=2*nl*nz-lz;float rv=rx*vx+ry*vy+rz*vz;
				if(rv>0){float sp=(float)Math.pow(rv,specCtl)*k;hr+=a.r*sp;hg+=a.g*sp;hb+=a.b*sp;}
			}
		}
		lit[0]=lr*dr;lit[1]=lg*dg;lit[2]=lb*db;
		lit[3]=hr*sr;lit[4]=hg*sg;lit[5]=hb*sb;
	}

	// ---- per-draw buffers
	private float[] clip=new float[0],world=new float[0],wnrm=new float[0],vlit=new float[0];private byte[] vdone=new byte[0];
	private final float[] lit=new float[6];
	/** Corners of one triangle for clipping: x y z w, r g b a, u v, sr sg sb (13 floats), up to 9 vertices after clipping. */
	private static final int CS=13;
	private final float[] poly=new float[CS*12],poly2=new float[CS*12];
	private final float[] rv=new float[3*RaveRenderer.TEXTURE];

	/** The attributes the geometry's own set gives over the state (a temporary state for this geometry). */
	private void geometryAttributes(Q3.AttrSet a){if(a!=null&&!a.map.isEmpty())applyAttributes(a);}

	private void draw(Q3Tess.Mesh m) throws Exception {
		State saved=s;
		if(m.attrs!=null&&!m.attrs.map.isEmpty()){s=s.copy();geometryAttributes(m.attrs);}
		try{
			if(m.marker!=null){marker(m);return;}
			matrices();
			int nv=m.nv;
			if(clip.length<4*nv){clip=new float[4*nv+64];world=new float[3*nv+48];wnrm=new float[3*nv+48];vlit=new float[14*nv+112];vdone=new byte[2*nv+32];}
			float[] L=localToFrustum,W=s.localToWorld,N=normalMatrix;float[] p=m.pos;
			for(int i=0;i<nv;i++){
				float x=p[3*i],y=p[3*i+1],z=p[3*i+2];
				clip[4*i]=x*L[0]+y*L[4]+z*L[8]+L[12];clip[4*i+1]=x*L[1]+y*L[5]+z*L[9]+L[13];clip[4*i+2]=x*L[2]+y*L[6]+z*L[10]+L[14];clip[4*i+3]=x*L[3]+y*L[7]+z*L[11]+L[15];
			}
			boolean needWorld=m.nt>0||m.lines!=null||m.points!=null;
			if(needWorld)for(int i=0;i<nv;i++){
				float x=p[3*i],y=p[3*i+1],z=p[3*i+2];
				world[3*i]=x*W[0]+y*W[4]+z*W[8]+W[12];world[3*i+1]=x*W[1]+y*W[5]+z*W[9]+W[13];world[3*i+2]=x*W[2]+y*W[6]+z*W[10]+W[14];
			}
			if(m.nrm!=null)for(int i=0;i<nv;i++){
				float x=m.nrm[3*i],y=m.nrm[3*i+1],z=m.nrm[3*i+2];
				if(Float.isNaN(x)){wnrm[3*i]=Float.NaN;continue;}
				float nx=x*N[0]+y*N[4]+z*N[8],ny=x*N[1]+y*N[5]+z*N[9],nz=x*N[2]+y*N[6]+z*N[10];float l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(l>0){nx/=l;ny/=l;nz/=l;}
				wnrm[3*i]=nx;wnrm[3*i+1]=ny;wnrm[3*i+2]=nz;
			}
			Arrays.fill(vdone,0,2*nv,(byte)0);
			if(renderer.leaf()==Q3.WIREFRAME){wireframe(m);return;}
			if(m.nt>0){
				if(s.fill==0)triangles(m);
				else if(s.fill==1)edges(m,true);
				else pointsOf(m);
			}
			if(m.lines!=null)lines(m);
			if(m.points!=null)for(int i=0;i<m.points.length;i++)point(m,m.points[i]);
		}finally{s=saved;}
	}

	/** Whether a triangle's resolved attributes are all the vertices' own (its lit colours can be kept per vertex). */
	private static boolean faceFree(Q3Tess.Mesh m){return m.fDiffuse==null&&m.fTransp==null&&m.fSpecular==null&&m.fSpecCtl==null&&m.fAmbient==null&&m.fHighlight==null;}

	private void triangles(Q3Tess.Mesh m) throws Exception {
		boolean faceFree=faceFree(m);
		int nt=m.nt;int[] tri=m.tri;
		for(int t=0;t<nt;t++){
			int a=tri[3*t],b=tri[3*t+1],c=tri[3*t+2];
			if(a<0||b<0||c<0||a>=m.nv||b>=m.nv||c>=m.nv)continue;
			// all three outside one plane: nothing to draw
			float aw=clip[4*a+3],bw=clip[4*b+3],cw=clip[4*c+3];
			if(clip[4*a]<-aw&&clip[4*b]<-bw&&clip[4*c]<-cw||clip[4*a]>aw&&clip[4*b]>bw&&clip[4*c]>cw||clip[4*a+1]<-aw&&clip[4*b+1]<-bw&&clip[4*c+1]<-cw
				||clip[4*a+1]>aw&&clip[4*b+1]>bw&&clip[4*c+1]>cw||clip[4*a+2]>0&&clip[4*b+2]>0&&clip[4*c+2]>0||clip[4*a+2]<-aw&&clip[4*b+2]<-bw&&clip[4*c+2]<-cw)continue;
			// the face normal in world coordinates (by the orientation style), and which side the camera sees
			float ux=world[3*b]-world[3*a],uy=world[3*b+1]-world[3*a+1],uz=world[3*b+2]-world[3*a+2];
			float vx=world[3*c]-world[3*a],vy=world[3*c+1]-world[3*a+1],vz=world[3*c+2]-world[3*a+2];
			float fx=uy*vz-uz*vy,fy=uz*vx-ux*vz,fz=ux*vy-uy*vx;
			if(s.orientation==1){fx=-fx;fy=-fy;fz=-fz;}
			// an explicit face normal decides which side is the front (the Interactive Renderer's rule, compared)
			if(m.fNrm!=null&&!Float.isNaN(m.fNrm[3*t])){float[] N=normalMatrix;float x=m.fNrm[3*t],y=m.fNrm[3*t+1],z=m.fNrm[3*t+2];
				fx=x*N[0]+y*N[4]+z*N[8];fy=x*N[1]+y*N[5]+z*N[9];fz=x*N[2]+y*N[6]+z*N[10];}
			float ex,ey,ez;
			if(orthographic){ex=viewDirX;ey=viewDirY;ez=viewDirZ;}else{ex=camX-world[3*a];ey=camY-world[3*a+1];ez=camZ-world[3*a+2];}
			boolean back=fx*ex+fy*ey+fz*ez<0;
			if(back&&s.backfacing==1&&!NO_CULL)continue;
			float fl=(float)Math.sqrt(fx*fx+fy*fy+fz*fz);if(fl>0){fx/=fl;fy/=fl;fz/=fl;}
			boolean flip=back&&s.backfacing==2;
			Q3.Obj shader=m.fShader!=null&&m.fShader[t]!=null?m.fShader[t]:s.surfaceShader;
			Q3.Shader tex=shader instanceof Q3.Shader&&((Q3.Shader)shader).texture!=null?(Q3.Shader)shader:null;
			boolean flat=s.interpolation==0;
			int[] ix={a,b,c};
			for(int k=0;k<3;k++){
				int v=ix[k];int o=CS*k;
				poly[o]=clip[4*v];poly[o+1]=clip[4*v+1];poly[o+2]=clip[4*v+2];poly[o+3]=clip[4*v+3];
				if(m.uv!=null&&!Float.isNaN(m.uv[2*v])){poly[o+8]=m.uv[2*v];poly[o+9]=m.uv[2*v+1];}else{poly[o+8]=0;poly[o+9]=0;}
				int lv=flat?ix[0]:v;
				boolean vertexNormal=m.nrm!=null&&!Float.isNaN(wnrm[3*lv]);
				if(flat&&k>0){System.arraycopy(poly,4,poly,o+4,4);System.arraycopy(poly,10,poly,o+10,3);continue;}
				if(faceFree&&vertexNormal&&!flat){
					int slot=flip?nvSlot(m,v):v;
					if(vdone[slot]==0){vertexLight(m,v,t,flip,tex!=null,vlit,7*slot);vdone[slot]=1;}
					System.arraycopy(vlit,7*slot,poly,o+4,4);poly[o+10]=vlit[7*slot+4];poly[o+11]=vlit[7*slot+5];poly[o+12]=vlit[7*slot+6];
				}else{
					// flat shading (the renderer's default) lights the triangle as its first vertex: its normal, else the face's
					cornerLight(m,lv,t,flip,tex!=null,vertexNormal&&(!flat||FLAT_VERTEX_NORMAL),fx,fy,fz,flat,ix);
					poly[o+4]=cl[0];poly[o+5]=cl[1];poly[o+6]=cl[2];poly[o+7]=cl[3];poly[o+10]=cl[4];poly[o+11]=cl[5];poly[o+12]=cl[6];
				}
			}
			emitPolygon(3,tex,back);
		}
	}
	private int nvSlot(Q3Tess.Mesh m,int v){return m.nv+v;}
	private final float[] cl=new float[7];

	/** Lit colour of a vertex with its own attributes (else the state's): r g b a sr sg sb at out[o..]. */
	private void vertexLight(Q3Tess.Mesh m,int v,int t,boolean flip,boolean textured,float[] out,int o){
		float nx=wnrm[3*v],ny=wnrm[3*v+1],nz=wnrm[3*v+2];if(flip){nx=-nx;ny=-ny;nz=-nz;}
		resolveAndLight(m,v,t,nx,ny,nz,world[3*v],world[3*v+1],world[3*v+2],textured,out,o);
	}
	/** Lit colour of a corner: the vertex normal, or the face normal (flat shading, or no vertex normal). */
	private void cornerLight(Q3Tess.Mesh m,int v,int t,boolean flip,boolean textured,boolean vertexNormal,float fx,float fy,float fz,boolean flat,int[] ix){
		float nx,ny,nz,px,py,pz;
		if(vertexNormal){nx=wnrm[3*v];ny=wnrm[3*v+1];nz=wnrm[3*v+2];}else{nx=fx;ny=fy;nz=fz;}
		if(flip&&vertexNormal){nx=-nx;ny=-ny;nz=-nz;}
		else if(flip){nx=-nx;ny=-ny;nz=-nz;}
		px=world[3*v];py=world[3*v+1];pz=world[3*v+2];
		resolveAndLight(m,v,t,nx,ny,nz,px,py,pz,textured,cl,0);
	}
	/** The attributes of vertex v of triangle t (vertex, then face, then geometry and view state), highlighted when asked, and lit. */
	private void resolveAndLight(Q3Tess.Mesh m,int v,int t,float nx,float ny,float nz,float px,float py,float pz,boolean textured,float[] out,int o){
		float dr=s.dr,dg=s.dg,db=s.db,sr=s.sr,sg=s.sg,sb=s.sb,tr=s.tr,tg=s.tg,tb=s.tb,sc=s.specCtl,am=s.ambient;int hl=s.highlightState;
		if(m.fDiffuse!=null&&!Float.isNaN(m.fDiffuse[3*t])){dr=m.fDiffuse[3*t];dg=m.fDiffuse[3*t+1];db=m.fDiffuse[3*t+2];}
		if(m.diffuse!=null&&!Float.isNaN(m.diffuse[3*v])){dr=m.diffuse[3*v];dg=m.diffuse[3*v+1];db=m.diffuse[3*v+2];}
		if(m.fTransp!=null&&!Float.isNaN(m.fTransp[3*t])){tr=m.fTransp[3*t];tg=m.fTransp[3*t+1];tb=m.fTransp[3*t+2];}
		if(m.transp!=null&&!Float.isNaN(m.transp[3*v])){tr=m.transp[3*v];tg=m.transp[3*v+1];tb=m.transp[3*v+2];}
		if(m.fSpecular!=null&&!Float.isNaN(m.fSpecular[3*t])){sr=m.fSpecular[3*t];sg=m.fSpecular[3*t+1];sb=m.fSpecular[3*t+2];}
		if(m.specular!=null&&!Float.isNaN(m.specular[3*v])){sr=m.specular[3*v];sg=m.specular[3*v+1];sb=m.specular[3*v+2];}
		if(m.fSpecCtl!=null&&!Float.isNaN(m.fSpecCtl[t]))sc=m.fSpecCtl[t];
		if(m.specCtl!=null&&!Float.isNaN(m.specCtl[v]))sc=m.specCtl[v];
		if(m.fAmbient!=null&&!Float.isNaN(m.fAmbient[t]))am=m.fAmbient[t];
		if(m.ambient!=null&&!Float.isNaN(m.ambient[v]))am=m.ambient[v];
		if(m.fHighlight!=null&&m.fHighlight[t]>=0)hl=m.fHighlight[t];
		if(m.highlight!=null&&m.highlight[v]>=0)hl=m.highlight[v];
		if(hl!=0&&s.highlight!=null){
			float[] f;
			if((f=s.highlight.floats(Q3.ATTR_DIFFUSE))!=null){dr=f[0];dg=f[1];db=f[2];}
			if((f=s.highlight.floats(Q3.ATTR_SPECULAR))!=null){sr=f[0];sg=f[1];sb=f[2];}
			if((f=s.highlight.floats(Q3.ATTR_TRANSPARENCY))!=null){tr=f[0];tg=f[1];tb=f[2];}
			if((f=s.highlight.floats(Q3.ATTR_SPECULAR_CONTROL))!=null)sc=f[0];
			if((f=s.highlight.floats(Q3.ATTR_AMBIENT_COEFFICIENT))!=null)am=f[0];
		}
		if(textured){dr=dg=db=1;}
		light(px,py,pz,nx,ny,nz,dr,dg,db,sr,sg,sb,sc,am,lit);
		out[o]=lit[0];out[o+1]=lit[1];out[o+2]=lit[2];
		out[o+3]=(tr+tg+tb)/3;
		out[o+4]=lit[3];out[o+5]=lit[4];out[o+6]=lit[5];
	}

	/** Clips the polygon in {@link #poly} (n corners) to the frustum and draws it (or keeps it for the transparent pass). */
	private void emitPolygon(int n,Q3.Shader tex,boolean back) throws Exception {
		float[] a=poly,b=poly2;
		// clip against x=-w, x=w, y=-w, y=w, z=0 (hither), z=-w (yon)
		boolean inside=true;
		for(int k=0;k<n;k++){int o=CS*k;float x=a[o],y=a[o+1],z=a[o+2],w=a[o+3];if(x<-w||x>w||y<-w||y>w||z>0||z<-w){inside=false;break;}}
		if(!inside){
			for(int plane=0;plane<6&&n>0;plane++){n=clipPlane(a,n,b,plane);float[] t=a;a=b;b=t;}
			if(n<3)return;
		}
		// to the pane: RAVE vertices x y z invW r g b a (u/w v/w kd ks)
		RaveRenderer.Texture texture=tex==null?null:textures==null?null:textures.get(tex.texture);
		boolean textured=texture!=null;
		int stride=textured?RaveRenderer.TEXTURE:RaveRenderer.GOURAUD;
		float[] out=new float[n*stride];
		boolean transparent=false;
		float[] uvt=tex!=null?tex.uvTransform:null;
		for(int k=0;k<n;k++){
			int o=CS*k,q=stride*k;float w=a[o+3],iw=w!=0?1/w:1,dw=w!=0?w:1;
			// divided rather than multiplied by 1/w: a corner on the frustum's side (x = w) lands exactly on the pane's edge
			out[q]=(a[o]/dw+1)*0.5f*paneW;out[q+1]=(1-a[o+1]/dw)*0.5f*paneH;out[q+2]=-a[o+2]/dw;out[q+3]=iw;
			float alpha=a[o+7];if(alpha<0.999f)transparent=true;
			if(textured){
				float u=a[o+8],v=a[o+9];
				if(uvt!=null){float nu=u*uvt[0]+v*uvt[3]+uvt[6],nvv=u*uvt[1]+v*uvt[4]+uvt[7];u=nu;v=nvv;}
				out[q+4]=clamp(a[o+4]);out[q+5]=clamp(a[o+5]);out[q+6]=clamp(a[o+6]);out[q+7]=alpha;
				out[q+8]=u*iw;out[q+9]=v*iw;
				out[q+10]=clamp(a[o+4]);out[q+11]=clamp(a[o+5]);out[q+12]=clamp(a[o+6]);
				out[q+13]=clamp(a[o+10]);out[q+14]=clamp(a[o+11]);out[q+15]=clamp(a[o+12]);
			}else{
				out[q+4]=clamp(a[o+4]+a[o+10]);out[q+5]=clamp(a[o+5]+a[o+11]);out[q+6]=clamp(a[o+6]+a[o+12]);out[q+7]=alpha;
			}
		}
		if(textured&&texture.alpha&&texture.partialAlpha)transparent=true;
		// The Interactive Renderer draws transparent triangles at once, in the order they come, writing Z, blended as RAVE's
		// premultiplied mode with colours that are not premultiplied (the result is src + (1 - a) dst): compared pixel by pixel
		if(transparent&&DEFER_TRANSPARENT){Deferred d=new Deferred();d.v=out;d.n=n;d.texture=texture;d.shader=tex;d.fog=s.fog;
			float z=0;for(int k=0;k<n;k++)z+=out[stride*k+2];d.z=z/n;transparentList().add(d);return;}
		drawFan(out,n,texture,tex,s.fog,false);
	}
	private List<Deferred> transparentList(){return transparent;}
	/** -Dhzqd3d.sorttransparent=true: transparent triangles wait for the end of the frame and are drawn back to front. */
	static final boolean HIDE_BACK_EDGES=false;
	static final boolean NO_CULL=Boolean.getBoolean("hzqd3d.nocull");
	static final boolean FLAT_VERTEX_NORMAL=!Boolean.getBoolean("hzqd3d.flatface");
	static final boolean FOG_TRACE=Boolean.getBoolean("hzqd3d.fogtrace");private int fogTraced;
	static final boolean DEFER_TRANSPARENT=Boolean.getBoolean("hzqd3d.sorttransparent");
	private static float clamp(float v){return v<0?0:v>1?1:v;}

	/** Clips n corners of {@code in} against a frustum plane into {@code out}; returns the new count. */
	private static int clipPlane(float[] in,int n,float[] out,int plane){
		int m=0;
		for(int i=0;i<n;i++){
			int j=(i+1)%n;int oi=CS*i,oj=CS*j;
			float di=dist(in,oi,plane),dj=dist(in,oj,plane);
			if(di>=0){if(m<11){System.arraycopy(in,oi,out,CS*m,CS);m++;}}
			if((di>=0)!=(dj>=0)){float t=di/(di-dj);int o=CS*m;if(m<11){for(int k=0;k<CS;k++)out[o+k]=in[oi+k]+(in[oj+k]-in[oi+k])*t;
				// exactly on the plane: an edge vertex a rounding error inside moved the pane's first row off the triangle
				// (Nanosaur's top line showed the clear colour, flickering from frame to frame)
				float w=out[o+3];switch(plane){case 0:out[o]=-w;break;case 1:out[o]=w;break;case 2:out[o+1]=-w;break;case 3:out[o+1]=w;break;case 4:out[o+2]=0;break;default:out[o+2]=-w;break;}
				m++;}}
		}
		return m;
	}
	/** Signed distance of a corner inside a plane (>= 0 inside). */
	private static float dist(float[] a,int o,int plane){
		float x=a[o],y=a[o+1],z=a[o+2],w=a[o+3];
		switch(plane){case 0:return x+w;case 1:return w-x;case 2:return y+w;case 3:return w-y;case 4:return -z;default:return z+w;}
	}

	/** Draws a convex polygon of RAVE vertices as a fan. */
	private void drawFan(float[] v,int n,RaveRenderer.Texture texture,Q3.Shader tex,float[] fog,boolean blend){
		RaveRenderer r=this.r;
		int savedFog=r.fogMode;boolean savedRange=r.fogRange;float fs=r.fogStart,fe=r.fogEnd,fd=r.fogDensity,fr=r.fogR,fg=r.fogG,fb=r.fogB,fa=r.fogA;
		if(fog!=null){
			if(fog[0]==0)r.fogMode=RaveRenderer.FOG_NONE;
			else{int mode=(int)fog[1];
				// mode 4 is the plane-based linear fog Pangea's games (Bugdom) ask for: linear in depth, as RAVE's linear fog is.
				// Apple's IR indexes its mode table with it and hands the engine a stray value; an unknown mode draws no fog.
				r.fogMode=mode==0||mode==4?RaveRenderer.FOG_LINEAR:mode==1?RaveRenderer.FOG_EXP:mode==2?RaveRenderer.FOG_EXP2:mode==3?RaveRenderer.FOG_ALPHA:RaveRenderer.FOG_NONE;
				// the style's distances are the camera's; RAVE's depth is w, which runs 0..1 to yon for a perspective camera
				float k=camera!=null&&camera.leaf()!=Q3.ORTHOGRAPHIC&&camera.yon!=0?1/camera.yon:1;
				r.fogStart=fog[2]*k;r.fogEnd=fog[3]*k;r.fogDensity=fog[4]/k;r.fogA=fog[5];r.fogR=fog[6];r.fogG=fog[7];r.fogB=fog[8];r.fogMaxDepth=0;
				// as the ATI engine this RAVE answers as (not Apple's software engine): exponential fog over start..end
				r.fogRange=!PpcRave.APPLE_SOFTWARE&&(mode==1||mode==2)&&fog[3]>fog[2];if(r.fogRange)r.fogDensity=fog[4];}
		}
		int savedBlend=r.blend;if(blend)r.blend=RaveRenderer.BLEND_INTERPOLATE;
		try{
			if(texture!=null){
				RaveRenderer.Texture old=r.texture;int op=r.textureOp;boolean wu=r.wrapU,wv=r.wrapV;int filter=r.textureFilter;
				r.texture=texture;r.textureOp=RaveRenderer.OP_MODULATE|(hasSpecular(v,n)?RaveRenderer.OP_HIGHLIGHT:0);
				r.wrapU=tex==null||tex.uBoundary==Q3.BOUNDARY_WRAP;r.wrapV=tex==null||tex.vBoundary==Q3.BOUNDARY_WRAP;
				r.textureFilter=renderer!=null&&renderer.textureFilter>0?1:0;
				int st=RaveRenderer.TEXTURE;
				for(int k=1;k+1<n;k++)r.triTexture(v,0,st*k,st*(k+1));
				r.texture=old;r.textureOp=op;r.wrapU=wu;r.wrapV=wv;r.textureFilter=filter;
			}else{
				int st=RaveRenderer.GOURAUD;
				for(int k=1;k+1<n;k++)r.triGouraud(v,0,st*k,st*(k+1));
			}
		}finally{
			r.blend=savedBlend;
			if(fog!=null){r.fogMode=savedFog;r.fogRange=savedRange;r.fogStart=fs;r.fogEnd=fe;r.fogDensity=fd;r.fogR=fr;r.fogG=fg;r.fogB=fb;r.fogA=fa;}
		}
	}
	private static boolean hasSpecular(float[] v,int n){int st=RaveRenderer.TEXTURE;for(int k=0;k<n;k++)if(v[st*k+13]>0||v[st*k+14]>0||v[st*k+15]>0)return true;return false;}

	/** A transparent polygon waiting for the end of the frame. */
	private static final class Deferred { float[] v;int n;RaveRenderer.Texture texture;Q3.Shader shader;float[] fog;float z; }
	private final List<Deferred> transparent=new ArrayList<Deferred>();
	private void drawTransparent(){
		if(transparent.isEmpty())return;
		transparent.sort((x,y)->Float.compare(y.z,x.z)); // far to near
		for(Deferred d:transparent)drawFan(d.v,d.n,d.texture,d.shader,d.fog,true);
		transparent.clear();
	}

	// ---- edges, lines, points, markers

	private float[] projected(int v,float[] out,int o,float r,float g,float b,float a){
		float w=clip[4*v+3];if(w<=0)return null;float iw=1/w;
		out[o]=(clip[4*v]*iw+1)*0.5f*paneW;out[o+1]=(1-clip[4*v+1]*iw)*0.5f*paneH;out[o+2]=-clip[4*v+2]*iw;out[o+3]=iw;
		out[o+4]=r;out[o+5]=g;out[o+6]=b;out[o+7]=a;return out;
	}
	private boolean visible(int v){float x=clip[4*v],y=clip[4*v+1],z=clip[4*v+2],w=clip[4*v+3];return !(x<-w||x>w||y<-w||y>w||z>0||z<-w);}
	/** A line between two vertices, clipped to the frustum, in the given colours. */
	private void line3(int a,int b,float[] ca,float[] cb){
		float[] A=new float[CS],B=new float[CS];
		System.arraycopy(clip,4*a,A,0,4);System.arraycopy(clip,4*b,B,0,4);System.arraycopy(ca,0,A,4,4);System.arraycopy(cb,0,B,4,4);
		for(int plane=0;plane<6;plane++){float da=dist(A,0,plane),db=dist(B,0,plane);if(da<0&&db<0)return;
			if(da<0){float t=da/(da-db);for(int k=0;k<CS;k++)A[k]+=(B[k]-A[k])*t;}else if(db<0){float t=db/(db-da);for(int k=0;k<CS;k++)B[k]+=(A[k]-B[k])*t;}}
		float[] v=new float[2*RaveRenderer.GOURAUD];
		for(int k=0;k<2;k++){float[] P=k==0?A:B;int o=k*RaveRenderer.GOURAUD;float iw=1/P[3];
			v[o]=(P[0]*iw+1)*0.5f*paneW;v[o+1]=(1-P[1]*iw)*0.5f*paneH;v[o+2]=-P[2]*iw;v[o+3]=iw;v[o+4]=P[4];v[o+5]=P[5];v[o+6]=P[6];v[o+7]=P[7];}
		r.line(v,0,RaveRenderer.GOURAUD);
	}
	/** The unlit colour of a vertex (lines, points, the wireframe renderer): its diffuse colour, else the state's. */
	private float[] plainColor(Q3Tess.Mesh m,int v){
		float[] c=new float[4];
		c[0]=s.dr;c[1]=s.dg;c[2]=s.db;c[3]=(s.tr+s.tg+s.tb)/3;
		if(m.diffuse!=null&&!Float.isNaN(m.diffuse[3*v])){c[0]=m.diffuse[3*v];c[1]=m.diffuse[3*v+1];c[2]=m.diffuse[3*v+2];}
		if(m.transp!=null&&!Float.isNaN(m.transp[3*v]))c[3]=(m.transp[3*v]+m.transp[3*v+1]+m.transp[3*v+2])/3;
		if(s.highlightState!=0&&s.highlight!=null){float[] f=s.highlight.floats(Q3.ATTR_DIFFUSE);if(f!=null){c[0]=f[0];c[1]=f[1];c[2]=f[2];}}
		return c;
	}
	private void lines(Q3Tess.Mesh m){for(int i=0;i+1<m.lines.length;i+=2)line3(m.lines[i],m.lines[i+1],plainColor(m,m.lines[i]),plainColor(m,m.lines[i+1]));}
	private void edges(Q3Tess.Mesh m,boolean lit){
		int[] e=m.edges;if(e==null)return;
		// (edges of faces turned away could be hidden; the Interactive Renderer draws them all)
		java.util.HashSet<Long> front=null;
		if(m.nt>0&&lit&&HIDE_BACK_EDGES){
			front=new java.util.HashSet<Long>();
			for(int t=0;t<m.nt;t++){int a=m.tri[3*t],b=m.tri[3*t+1],c=m.tri[3*t+2];
				if(a<0||b<0||c<0||a>=m.nv||b>=m.nv||c>=m.nv)continue;
				float ux=world[3*b]-world[3*a],uy=world[3*b+1]-world[3*a+1],uz=world[3*b+2]-world[3*a+2],vx=world[3*c]-world[3*a],vy=world[3*c+1]-world[3*a+1],vz=world[3*c+2]-world[3*a+2];
				float fx=uy*vz-uz*vy,fy=uz*vx-ux*vz,fz=ux*vy-uy*vx;if(s.orientation==1){fx=-fx;fy=-fy;fz=-fz;}
				float ex,ey,ez;if(orthographic){ex=viewDirX;ey=viewDirY;ez=viewDirZ;}else{ex=camX-world[3*a];ey=camY-world[3*a+1];ez=camZ-world[3*a+2];}
				if(fx*ex+fy*ey+fz*ez<0)continue;
				front.add(key(m,a,b));front.add(key(m,b,c));front.add(key(m,c,a));}
		}
		for(int i=0;i+1<e.length;i+=2){if(front!=null&&!front.contains(key(m,e[i],e[i+1]))&&!front.contains(samePlace(m,e[i],e[i+1])))continue;
			line3(e[i],e[i+1],plainColor(m,e[i]),plainColor(m,e[i+1]));}
	}
	private static long key(Q3Tess.Mesh m,int a,int b){return a<b?(long)a<<32|b:(long)b<<32|a;}
	/** The same edge by position (vertices duplicated with other attributes still meet). */
	private long samePlace(Q3Tess.Mesh m,int a,int b){return key(m,a,b);}
	private void wireframe(Q3Tess.Mesh m){
		int savedZ=r.zFunction;r.zFunction=RaveRenderer.Z_NONE;
		try{if(m.nt>0)edges(m,false);if(m.lines!=null)lines(m);if(m.points!=null)for(int p:m.points)point(m,p);}finally{r.zFunction=savedZ;}
	}
	private void pointsOf(Q3Tess.Mesh m){for(int i=0;i<m.nv;i++)point(m,i);}
	private void point(Q3Tess.Mesh m,int v){
		if(!visible(v))return;float[] c=plainColor(m,v);
		float[] o=projected(v,new float[RaveRenderer.GOURAUD],0,c[0],c[1],c[2],c[3]);if(o!=null)r.point(o,0);
	}
	private void marker(Q3Tess.Mesh m){
		matrices();
		float[] p=m.pos;float[] L=localToFrustum;
		float x=p[0],y=p[1],z=p[2];
		float cx=x*L[0]+y*L[4]+z*L[8]+L[12],cy=x*L[1]+y*L[5]+z*L[9]+L[13],cz=x*L[2]+y*L[6]+z*L[10]+L[14],cw=x*L[3]+y*L[7]+z*L[11]+L[15];
		if(cw<=0||cz>0||cz<-cw)return;
		float sx=(cx/cw+1)*0.5f*paneW,sy=(1-cy/cw)*0.5f*paneH,sz=-cz/cw;
		if(m.marker instanceof Q3.Marker){
			Q3.Marker mk=(Q3.Marker)m.marker;Q3.Bitmap bm=mk.bitmap;if(bm.bits==null)return;
			RaveRenderer.Texture t=new RaveRenderer.Texture(1);t.widths[0]=bm.width;t.heights[0]=bm.height;int[] px=new int[bm.width*bm.height];
			for(int yy=0;yy<bm.height;yy++)for(int xx=0;xx<bm.width;xx++)px[yy*bm.width+xx]=bm.get(xx,yy)?0xffffffff:0x00ffffff;
			t.pixels[0]=px;t.mask=true;t.updateAlpha();
			float[] v={sx+mk.xOffset,sy+mk.yOffset,sz,1/cw,s.dr,s.dg,s.db,1};
			if(s.highlightState!=0&&s.highlight!=null){float[] f=s.highlight.floats(Q3.ATTR_DIFFUSE);if(f!=null){v[4]=f[0];v[5]=f[1];v[6]=f[2];}}
			r.bitmap(v,0,t);
		}else if(m.marker instanceof Q3.PixmapMarker&&textures!=null){
			Q3.PixmapMarker mk=(Q3.PixmapMarker)m.marker;
			Q3.Texture holder=new Q3.Texture(Q3.PIXMAP_TEXTURE);holder.pixmap=mk.pixmap;holder.editIndex=mk.editIndex;
			RaveRenderer.Texture t=textures.get(holder);if(t==null)return;
			float[] v={sx+mk.xOffset,sy+mk.yOffset,sz,1/cw,1,1,1,1};
			r.bitmap(v,0,t);
		}
	}

	// ================================================================== bounding

	/** The bounds being made: box (min, max) and the points for a sphere. */
	float[] bmin,bmax;boolean bExact;
	private Q3Tess.FloatList spherePoints;
	boolean startBounds(boolean sphere,boolean exact){
		if(mode!=IDLE){error=-28404;return false;}
		resetState();bmin=null;bmax=null;bExact=exact;spherePoints=sphere?new Q3Tess.FloatList():null;
		mode=sphere?BOUNDING_SPHERE:BOUNDING_BOX;return true;
	}
	int endBounds(){mode=IDLE;stack.clear();return STATUS_DONE;}
	private void bound(Q3.Geometry g){
		Q3Tess.Mesh m;
		if(!bExact&&g instanceof Q3.Quadric){ // the corners of the box around the axes
			Q3.Quadric q=(Q3.Quadric)g;float[] c=new float[24];
			for(int i=0;i<8;i++)for(int k=0;k<3;k++){float ma=(i&1)!=0?1:-1,mi=(i&2)!=0?1:-1,or=(i&4)!=0?1:(q.leaf()==Q3.BOX||q.leaf()==Q3.CYLINDER||q.leaf()==Q3.CONE?0:-1);
				if(q.leaf()==Q3.BOX){ma=(i&1)!=0?1:0;mi=(i&2)!=0?1:0;}
				c[3*i+k]=q.origin[k]+ma*q.major[k]+mi*q.minor[k]+or*q.orientation[k];}
			addBoundPoints(c,8);return;
		}
		m=Q3Tess.mesh(g,subdivision);
		if(m==null)return;
		if(g instanceof Q3.TriMesh&&!bExact&&!((Q3.TriMesh)g).bboxEmpty){float[] b=((Q3.TriMesh)g).bbox;float[] c=new float[24];
			for(int i=0;i<8;i++){c[3*i]=b[(i&1)!=0?3:0];c[3*i+1]=b[(i&2)!=0?4:1];c[3*i+2]=b[(i&4)!=0?5:2];}addBoundPoints(c,8);return;}
		addBoundPoints(m.pos,m.nv);
	}
	private void addBoundPoints(float[] p,int n){
		float[] W=s.localToWorld;float[] t=new float[3];
		for(int i=0;i<n;i++){
			t[0]=p[3*i];t[1]=p[3*i+1];t[2]=p[3*i+2];Q3.transformPoint(W,t,0);
			if(spherePoints!=null){spherePoints.add(t[0]);spherePoints.add(t[1]);spherePoints.add(t[2]);}
			if(bmin==null){bmin=t.clone();bmax=t.clone();}
			else for(int k=0;k<3;k++){if(t[k]<bmin[k])bmin[k]=t[k];if(t[k]>bmax[k])bmax[k]=t[k];}
		}
	}
	/** The bounding sphere of the points submitted: the box's centre and the farthest point from it. */
	float[] sphere(){
		if(spherePoints==null||spherePoints.n==0)return null;
		float[] p=spherePoints.a;int n=spherePoints.n/3;
		return Q3View.sphereOf(p,n,3);
	}
	/** A sphere around n points (stride floats apart): the centre of their box, the farthest one's distance. */
	static float[] sphereOf(float[] p,int n,int stride){
		if(n==0)return null;
		float x0=p[0],y0=p[1],z0=p[2],x1=x0,y1=y0,z1=z0;
		for(int i=1;i<n;i++){float x=p[stride*i],y=p[stride*i+1],z=p[stride*i+2];if(x<x0)x0=x;if(x>x1)x1=x;if(y<y0)y0=y;if(y>y1)y1=y;if(z<z0)z0=z;if(z>z1)z1=z;}
		float cx=(x0+x1)/2,cy=(y0+y1)/2,cz=(z0+z1)/2;double r2=0;
		for(int i=0;i<n;i++){double dx=p[stride*i]-cx,dy=p[stride*i+1]-cy,dz=p[stride*i+2]-cz;r2=Math.max(r2,dx*dx+dy*dy+dz*dz);}
		return new float[]{cx,cy,cz,(float)Math.sqrt(r2)};
	}

	// ================================================================== picking

	/** A pick (TQ3PickObject): what to look for, and the hits found. */
	static final class Pick extends Q3.Obj {
		int sort,mask,numHits;
		/** window point: x y, vertex tolerance, edge tolerance; window rect: left top right bottom; world ray: origin 3, direction 3, tolerances 2 */
		float[] data=new float[8];
		final List<Hit> hits=new ArrayList<Hit>();
		Pick(int leaf){super(leaf);}
		@Override void dropRefs(){for(Hit h:hits){Q3.release(h.object);Q3.release(h.root);}hits.clear();super.dropRefs();}
		void empty(){for(Hit h:hits){Q3.release(h.object);Q3.release(h.root);}hits.clear();}
	}
	/** A hit: its details as TQ3PickDetail asks for them. */
	static final class Hit {
		int pickID;Q3.Group root;Q3.Group.Pos[] path;Q3.Obj object;float[] localToWorld;float[] xyz;float distance;float[] normal;float[] uv;int pickParts;
	}
	Pick pick;
	private final List<Q3.Group.Pos> path=new ArrayList<Q3.Group.Pos>();
	private final ArrayDeque<Integer> pathMarks=new ArrayDeque<Integer>();
	private Q3.Group pathRoot;
	/** The object Q3Object_Submit was given at the top of the traversal (a hit's object when it is a geometry, and the root of the path). */
	Q3.Obj submittedRoot;

	boolean startPicking(Pick p){
		if(mode!=IDLE){error=-28404;return false;}
		if(camera==null){error=-28413;return false;}
		resetState();frameMatrices();pick=p;path.clear();pathRoot=null;mode=PICKING;return true;
	}
	int endPicking(){
		mode=IDLE;stack.clear();
		if(pick!=null){
			if(pick.sort==1)pick.hits.sort((a,b)->Float.compare(a.distance,b.distance));
			else if(pick.sort==2)pick.hits.sort((a,b)->Float.compare(b.distance,a.distance));
			if(pick.numHits>0)while(pick.hits.size()>pick.numHits){Hit h=pick.hits.remove(pick.hits.size()-1);Q3.release(h.object);Q3.release(h.root);}
		}
		return STATUS_DONE;
	}

	private void pick(Q3.Geometry g,Q3Tess.Mesh m){
		if(pick==null)return;
		matrices();
		float[] W=s.localToWorld;int nv=m.nv;float[] p=m.pos;
		float[] wp=new float[3*nv];for(int i=0;i<nv;i++){wp[3*i]=p[3*i];wp[3*i+1]=p[3*i+1];wp[3*i+2]=p[3*i+2];Q3.transformPoint(W,wp,3*i);}
		float best=Float.MAX_VALUE;float[] bestXYZ=null,bestN=null,bestUV=null;
		int t=pick.leaf();
		// window coordinates of the vertices (point and rect picks)
		float[] win=null;
		if(t!=Q3.PICK_WORLD_RAY){
			win=new float[3*nv];float[] L=localToFrustum;float[] pane=surface!=null?surface.pane():new float[]{0,0,paneW,paneH};
			for(int i=0;i<nv;i++){float x=p[3*i],y=p[3*i+1],z=p[3*i+2];
				float cx=x*L[0]+y*L[4]+z*L[8]+L[12],cy=x*L[1]+y*L[5]+z*L[9]+L[13],cz=x*L[2]+y*L[6]+z*L[10]+L[14],cw=x*L[3]+y*L[7]+z*L[11]+L[15];
				if(cw<=1e-9f){win[3*i]=Float.NaN;continue;}
				win[3*i]=pane[0]+(cx/cw+1)*0.5f*paneW;win[3*i+1]=pane[1]+(1-cy/cw)*0.5f*paneH;win[3*i+2]=-cz/cw;}
		}
		float[] ray=null;
		if(t==Q3.PICK_WORLD_RAY)ray=pick.data;
		else if(t==Q3.PICK_WINDOW_POINT){ray=windowRay(pick.data[0],pick.data[1]);}
		for(int k=0;k<m.nt;k++){
			int a=m.tri[3*k],b=m.tri[3*k+1],c=m.tri[3*k+2];
			boolean hit=false;float[] xyz=null;float dist=0;float bu=0,bv=0;
			if(t==Q3.PICK_WINDOW_RECT){
				hit=triangleInRect(win,a,b,c,pick.data);
				if(hit){xyz=new float[]{(wp[3*a]+wp[3*b]+wp[3*c])/3,(wp[3*a+1]+wp[3*b+1]+wp[3*c+1])/3,(wp[3*a+2]+wp[3*b+2]+wp[3*c+2])/3};dist=distanceFromCamera(xyz);}
			}else if(ray!=null){
				float[] h=rayTriangle(ray,wp,a,b,c);
				if(h!=null){hit=true;dist=h[0];bu=h[1];bv=h[2];xyz=new float[]{ray[0]+ray[3]*dist,ray[1]+ray[4]*dist,ray[2]+ray[5]*dist};}
			}
			if(hit&&dist<best){best=dist;bestXYZ=xyz;
				float ux=wp[3*b]-wp[3*a],uy=wp[3*b+1]-wp[3*a+1],uz=wp[3*b+2]-wp[3*a+2],vx=wp[3*c]-wp[3*a],vy=wp[3*c+1]-wp[3*a+1],vz=wp[3*c+2]-wp[3*a+2];
				float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;float l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(l>0){nx/=l;ny/=l;nz/=l;}bestN=new float[]{nx,ny,nz};
				if(m.uv!=null&&!Float.isNaN(m.uv[2*a])&&!Float.isNaN(m.uv[2*b])&&!Float.isNaN(m.uv[2*c])){float w0=1-bu-bv;bestUV=new float[]{w0*m.uv[2*a]+bu*m.uv[2*b]+bv*m.uv[2*c],w0*m.uv[2*a+1]+bu*m.uv[2*b+1]+bv*m.uv[2*c+1]};}}
		}
		// lines and points: within the tolerances in window coordinates
		if(win!=null&&t==Q3.PICK_WINDOW_POINT){
			float px=pick.data[0],py=pick.data[1],vt=Math.max(pick.data[2],0.5f),et=Math.max(pick.data[3],0.5f);
			if(m.lines!=null)for(int i=0;i+1<m.lines.length;i+=2){int a=m.lines[i],b=m.lines[i+1];if(Float.isNaN(win[3*a])||Float.isNaN(win[3*b]))continue;
				float d=segmentDistance(px,py,win[3*a],win[3*a+1],win[3*b],win[3*b+1]);if(d<=et){float[] xyz={wp[3*a],wp[3*a+1],wp[3*a+2]};float dist=distanceFromCamera(xyz);if(dist<best){best=dist;bestXYZ=xyz;}}}
			if(m.points!=null||m.marker!=null){int[] pts=m.points!=null?m.points:new int[]{0};for(int a:pts){if(Float.isNaN(win[3*a]))continue;float dx=win[3*a]-px,dy=win[3*a+1]-py;
				if(dx*dx+dy*dy<=vt*vt||m.marker!=null&&markerHit(m,win[3*a],win[3*a+1],px,py)){float[] xyz={wp[3*a],wp[3*a+1],wp[3*a+2]};float dist=distanceFromCamera(xyz);if(dist<best){best=dist;bestXYZ=xyz;}}}}
		}else if(win!=null&&t==Q3.PICK_WINDOW_RECT){
			if(m.lines!=null||m.points!=null)for(int i=0;i<nv;i++){if(Float.isNaN(win[3*i]))continue;float x=win[3*i],y=win[3*i+1];
				if(x>=pick.data[0]&&x<=pick.data[2]&&y>=pick.data[1]&&y<=pick.data[3]){float[] xyz={wp[3*i],wp[3*i+1],wp[3*i+2]};float dist=distanceFromCamera(xyz);if(dist<best){best=dist;bestXYZ=xyz;}}}
		}
		if(bestXYZ==null)return;
		Hit h=new Hit();h.pickID=s.pickID;h.pickParts=s.pickParts;h.object=Q3.ref(g);h.localToWorld=W.clone();h.xyz=bestXYZ;h.distance=best;h.normal=bestN;h.uv=bestUV;
		if(pathRoot!=null){h.root=Q3.ref(pathRoot);h.path=path.toArray(new Q3.Group.Pos[0]);}
		pick.hits.add(h);
	}
	private boolean markerHit(Q3Tess.Mesh m,float x,float y,float px,float py){
		if(m.marker instanceof Q3.Marker){Q3.Marker k=(Q3.Marker)m.marker;float lx=px-(x+k.xOffset),ly=py-(y+k.yOffset);return lx>=0&&ly>=0&&lx<k.bitmap.width&&ly<k.bitmap.height;}
		if(m.marker instanceof Q3.PixmapMarker){Q3.PixmapMarker k=(Q3.PixmapMarker)m.marker;float lx=px-(x+k.xOffset),ly=py-(y+k.yOffset);return lx>=0&&ly>=0&&lx<k.pixmap.width&&ly<k.pixmap.height;}
		return false;
	}
	private float distanceFromCamera(float[] p){
		if(orthographic){float[] v={p[0],p[1],p[2]};Q3.transformPoint(worldToView,v,0);return -v[2];}
		float dx=p[0]-camX,dy=p[1]-camY,dz=p[2]-camZ;return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
	}
	/** The world ray through a window point: origin and unit direction. */
	float[] windowRay(float wx,float wy){
		float[] pane=surface!=null?surface.pane():new float[]{0,0,paneW,paneH};
		float fx=(wx-pane[0])/paneW*2-1,fy=1-(wy-pane[1])/paneH*2;
		float[] inv=Q3.invert(worldToFrustum);if(inv==null)return null;
		float[] near={fx,fy,0},far={fx,fy,-1};
		Q3.transformPoint(inv,near,0);Q3.transformPoint(inv,far,0);
		float dx=far[0]-near[0],dy=far[1]-near[1],dz=far[2]-near[2];float l=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);if(l==0)return null;
		float ox=orthographic?near[0]:camX,oy=orthographic?near[1]:camY,oz=orthographic?near[2]:camZ;
		return new float[]{ox,oy,oz,dx/l,dy/l,dz/l};
	}
	/** Möller-Trumbore: distance along the ray and the barycentric u, v, or null. */
	private static float[] rayTriangle(float[] ray,float[] p,int a,int b,int c){
		float e1x=p[3*b]-p[3*a],e1y=p[3*b+1]-p[3*a+1],e1z=p[3*b+2]-p[3*a+2],e2x=p[3*c]-p[3*a],e2y=p[3*c+1]-p[3*a+1],e2z=p[3*c+2]-p[3*a+2];
		float dx=ray[3],dy=ray[4],dz=ray[5];
		float px=dy*e2z-dz*e2y,py=dz*e2x-dx*e2z,pz=dx*e2y-dy*e2x;
		float det=e1x*px+e1y*py+e1z*pz;if(Math.abs(det)<1e-12f)return null;float id=1/det;
		float tx=ray[0]-p[3*a],ty=ray[1]-p[3*a+1],tz=ray[2]-p[3*a+2];
		float u=(tx*px+ty*py+tz*pz)*id;if(u<0||u>1)return null;
		float qx=ty*e1z-tz*e1y,qy=tz*e1x-tx*e1z,qz=tx*e1y-ty*e1x;
		float v=(dx*qx+dy*qy+dz*qz)*id;if(v<0||u+v>1)return null;
		float t=(e2x*qx+e2y*qy+e2z*qz)*id;if(t<0)return null;
		return new float[]{t,u,v};
	}
	private static float segmentDistance(float px,float py,float ax,float ay,float bx,float by){
		float dx=bx-ax,dy=by-ay;float l=dx*dx+dy*dy;float t=l==0?0:((px-ax)*dx+(py-ay)*dy)/l;t=t<0?0:t>1?1:t;
		float x=ax+t*dx-px,y=ay+t*dy-py;return (float)Math.sqrt(x*x+y*y);
	}
	private static boolean triangleInRect(float[] w,int a,int b,int c,float[] r){
		if(Float.isNaN(w[3*a])||Float.isNaN(w[3*b])||Float.isNaN(w[3*c]))return false;
		float l=r[0],t=r[1],rr=r[2],bb=r[3];
		for(int v:new int[]{a,b,c})if(w[3*v]>=l&&w[3*v]<=rr&&w[3*v+1]>=t&&w[3*v+1]<=bb)return true;
		// a rectangle corner inside the triangle
		float[][] cs={{l,t},{rr,t},{l,bb},{rr,bb}};
		for(float[] q:cs){float d1=(q[0]-w[3*b])*(w[3*a+1]-w[3*b+1])-(w[3*a]-w[3*b])*(q[1]-w[3*b+1]),d2=(q[0]-w[3*c])*(w[3*b+1]-w[3*c+1])-(w[3*b]-w[3*c])*(q[1]-w[3*c+1]),d3=(q[0]-w[3*a])*(w[3*c+1]-w[3*a+1])-(w[3*c]-w[3*a])*(q[1]-w[3*a+1]);
			boolean neg=d1<0||d2<0||d3<0,pos=d1>0||d2>0||d3>0;if(!(neg&&pos))return true;}
		return false;
	}

	// ================================================================== writing

	/** What Q3View_StartWriting writes to (the front end's 3DMF writer). */
	interface Writer { void write(Q3.Obj o,Q3View v) throws Exception; }
	Writer writer;
	boolean startWriting(Writer w){if(mode!=IDLE){error=-28404;return false;}resetState();writer=w;mode=WRITING;return true;}
	int endWriting(){mode=IDLE;writer=null;stack.clear();return STATUS_DONE;}
}

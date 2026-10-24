import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QuickDraw 3D geometries as triangles, lines and points for {@link Q3View}: one {@link Mesh} per geometry, with the
 * attributes of its vertices and faces resolved into arrays (NaN where a vertex or face does not set one, so that the
 * face, geometry and view values show through). Smooth surfaces are cut by the subdivision style; polygons with
 * concave outlines or holes are triangulated by ear clipping in their plane.
 */
final class Q3Tess {
	private Q3Tess(){}

	/** A geometry ready to draw. */
	static final class Mesh {
		int nv;float[] pos=new float[0];
		/** per vertex, NaN when not set: normal 3, surface UV 2, diffuse 3, transparency 3, specular 3, specular control 1, ambient coefficient 1 */
		float[] nrm,uv,diffuse,transp,specular,specCtl,ambient;int[] highlight;
		int nt;int[] tri=new int[0];
		/** per triangle, NaN when not set */
		float[] fNrm,fDiffuse,fTransp,fSpecular,fSpecCtl,fAmbient;int[] fHighlight;Q3.Obj[] fShader;
		/** line segments (vertex index pairs) and points of line and point geometries */
		int[] lines,points;
		/** outline edges of the faces (vertex index pairs), for the edges fill style and the wireframe renderer */
		int[] edges;
		/** a marker's bitmap or pixmap (drawn at the one vertex) */
		Q3.Obj marker;
		/** the geometry's own attribute set (over the view's) */
		Q3.AttrSet attrs;
		/** the subdivision it was cut with (smooth surfaces), to know when to cut again */
		int su,sv;
		/** the edit indexes of the attribute sets inside the geometry when it was cut */
		long attrEdit;
	}

	private static final float NAN=Float.NaN;

	/** A mesh builder: vertices with attributes from their sets, triangles with their face sets. */
	private static final class Builder {
		final Mesh m=new Mesh();
		float[] pos=new float[48],nrm,uv,dif,trn,spc,sct,amb;int[] hl;
		int[] tri=new int[48];float[] fN,fD,fT,fS,fC,fA;int[] fH;Q3.Obj[] fSh;
		int nv,nt;
		final IntList lines=new IntList(),points=new IntList(),edges=new IntList();
		final float[] scratch=new float[3];
		Builder(Q3.AttrSet attrs){m.attrs=attrs;}
		private float[] grow(float[] a,int need){if(a==null)return null;if(a.length>=need)return a;return Arrays.copyOf(a,Math.max(need,a.length*2));}
		private float[] make(float[] a,int per){if(a!=null)return a;float[] f=new float[Math.max(16,pos.length/3*per)];Arrays.fill(f,NAN);return f;}
		int vertex(float x,float y,float z,Q3.AttrSet s){
			int i=nv++;
			if(pos.length<3*nv){int cap=Math.max(3*nv,pos.length*2);pos=Arrays.copyOf(pos,cap);int n=cap/3;
				nrm=fill(nrm,3*n);uv=fill(uv,2*n);dif=fill(dif,3*n);trn=fill(trn,3*n);spc=fill(spc,3*n);sct=fill(sct,n);amb=fill(amb,n);if(hl!=null)hl=Arrays.copyOf(hl,n);}
			pos[3*i]=x;pos[3*i+1]=y;pos[3*i+2]=z;
			if(s!=null)vertexAttrs(i,s);
			return i;
		}
		private float[] fill(float[] a,int n){if(a==null)return null;int old=a.length;float[] b=Arrays.copyOf(a,n);Arrays.fill(b,old,n,NAN);return b;}
		void vertexAttrs(int i,Q3.AttrSet s){
			for(java.util.Map.Entry<Integer,Object> e:s.map.entrySet()){
				int t=e.getKey();Object v=e.getValue();
				if(v instanceof float[]){float[] f=(float[])v;
					switch(t){
						case Q3.ATTR_NORMAL:nrm=make(nrm,3);put(nrm,3*i,f,3);break;
						case Q3.ATTR_SURFACE_UV:case Q3.ATTR_SHADING_UV:if(t==Q3.ATTR_SURFACE_UV||uv==null||Float.isNaN(uv[2*i])){uv=make(uv,2);put(uv,2*i,f,2);}break;
						case Q3.ATTR_DIFFUSE:dif=make(dif,3);put(dif,3*i,f,3);break;
						case Q3.ATTR_TRANSPARENCY:trn=make(trn,3);put(trn,3*i,f,3);break;
						case Q3.ATTR_SPECULAR:spc=make(spc,3);put(spc,3*i,f,3);break;
						case Q3.ATTR_SPECULAR_CONTROL:sct=make(sct,1);put(sct,i,f,1);break;
						case Q3.ATTR_AMBIENT_COEFFICIENT:amb=make(amb,1);put(amb,i,f,1);break;
						default:break;
					}
				}else if(t==Q3.ATTR_HIGHLIGHT_STATE&&v instanceof Integer){if(hl==null){hl=new int[pos.length/3];Arrays.fill(hl,-1);}hl[i]=(Integer)v;}
			}
		}
		void setNormal(int i,float x,float y,float z){nrm=make(nrm,3);nrm[3*i]=x;nrm[3*i+1]=y;nrm[3*i+2]=z;}
		void setUV(int i,float u,float v){uv=make(uv,2);uv[2*i]=u;uv[2*i+1]=v;}
		boolean hasNormal(int i){return nrm!=null&&!Float.isNaN(nrm[3*i]);}
		boolean hasUV(int i){return uv!=null&&!Float.isNaN(uv[2*i]);}
		int triangle(int a,int b,int c,Q3.AttrSet s){
			int t=nt++;
			if(tri.length<3*nt){int cap=Math.max(3*nt,tri.length*2);tri=Arrays.copyOf(tri,cap);int n=cap/3;
				fN=fill(fN,3*n);fD=fill(fD,3*n);fT=fill(fT,3*n);fS=fill(fS,3*n);fC=fill(fC,n);fA=fill(fA,n);if(fH!=null)fH=Arrays.copyOf(fH,n);if(fSh!=null)fSh=Arrays.copyOf(fSh,n);}
			tri[3*t]=a;tri[3*t+1]=b;tri[3*t+2]=c;
			if(s!=null)faceAttrs(t,s);
			return t;
		}
		private float[] makeF(float[] a,int per){if(a!=null)return a;float[] f=new float[Math.max(16,tri.length/3*per)];Arrays.fill(f,NAN);return f;}
		void faceAttrs(int t,Q3.AttrSet s){
			for(java.util.Map.Entry<Integer,Object> e:s.map.entrySet()){
				int ty=e.getKey();Object v=e.getValue();
				if(v instanceof float[]){float[] f=(float[])v;
					switch(ty){
						case Q3.ATTR_NORMAL:fN=makeF(fN,3);put(fN,3*t,f,3);break;
						case Q3.ATTR_DIFFUSE:fD=makeF(fD,3);put(fD,3*t,f,3);break;
						case Q3.ATTR_TRANSPARENCY:fT=makeF(fT,3);put(fT,3*t,f,3);break;
						case Q3.ATTR_SPECULAR:fS=makeF(fS,3);put(fS,3*t,f,3);break;
						case Q3.ATTR_SPECULAR_CONTROL:fC=makeF(fC,1);put(fC,t,f,1);break;
						case Q3.ATTR_AMBIENT_COEFFICIENT:fA=makeF(fA,1);put(fA,t,f,1);break;
						default:break;
					}
				}else if(ty==Q3.ATTR_HIGHLIGHT_STATE&&v instanceof Integer){if(fH==null){fH=new int[tri.length/3];Arrays.fill(fH,-1);}fH[t]=(Integer)v;}
				else if(ty==Q3.ATTR_SURFACE_SHADER&&v instanceof Q3.Obj){if(fSh==null)fSh=new Q3.Obj[tri.length/3];fSh[t]=(Q3.Obj)v;}
			}
		}
		/** Face attributes copied from another triangle (the triangles one polygon is cut into share its set). */
		void copyFace(int to,int from){
			if(fN!=null)System.arraycopy(fN,3*from,fN,3*to,3);if(fD!=null)System.arraycopy(fD,3*from,fD,3*to,3);if(fT!=null)System.arraycopy(fT,3*from,fT,3*to,3);
			if(fS!=null)System.arraycopy(fS,3*from,fS,3*to,3);if(fC!=null)fC[to]=fC[from];if(fA!=null)fA[to]=fA[from];if(fH!=null)fH[to]=fH[from];if(fSh!=null)fSh[to]=fSh[from];
		}
		void faceNormal(int t,float x,float y,float z){fN=makeF(fN,3);fN[3*t]=x;fN[3*t+1]=y;fN[3*t+2]=z;}
		void edge(int a,int b){edges.add(a);edges.add(b);}
		Mesh build(){
			m.nv=nv;m.pos=Arrays.copyOf(pos,3*nv);
			m.nrm=cut(nrm,3*nv);m.uv=cut(uv,2*nv);m.diffuse=cut(dif,3*nv);m.transp=cut(trn,3*nv);m.specular=cut(spc,3*nv);m.specCtl=cut(sct,nv);m.ambient=cut(amb,nv);
			m.highlight=hl==null?null:Arrays.copyOf(hl,nv);
			m.nt=nt;m.tri=Arrays.copyOf(tri,3*nt);
			m.fNrm=cut(fN,3*nt);m.fDiffuse=cut(fD,3*nt);m.fTransp=cut(fT,3*nt);m.fSpecular=cut(fS,3*nt);m.fSpecCtl=cut(fC,nt);m.fAmbient=cut(fA,nt);
			m.fHighlight=fH==null?null:Arrays.copyOf(fH,nt);m.fShader=fSh==null?null:Arrays.copyOf(fSh,nt);
			m.lines=lines.size()==0?null:lines.toArray();m.points=points.size()==0?null:points.toArray();m.edges=edges.size()==0?null:edges.toArray();
			if(m.edges==null&&nt>0){int[] e=new int[6*nt];for(int i=0;i<nt;i++){int a=tri[3*i],b=tri[3*i+1],c=tri[3*i+2];e[6*i]=a;e[6*i+1]=b;e[6*i+2]=b;e[6*i+3]=c;e[6*i+4]=c;e[6*i+5]=a;}m.edges=e;}
			return m;
		}
		private static float[] cut(float[] a,int n){return a==null?null:Arrays.copyOf(a,n);}
	}
	private static void put(float[] dst,int at,float[] src,int n){for(int k=0;k<n&&k<src.length;k++)dst[at+k]=src[k];}

	static final class FloatList {
		float[] a=new float[48];int n;
		void add(float v){if(n==a.length)a=Arrays.copyOf(a,n*2);a[n++]=v;}
	}
	static final class IntList {
		int[] a=new int[16];int n;
		void add(int v){if(n==a.length)a=Arrays.copyOf(a,n*2);a[n++]=v;}
		int size(){return n;}int get(int i){return a[i];}
		int[] toArray(){return Arrays.copyOf(a,n);}
	}

	// ================================================================== dispatch

	/** Subdivision for smooth surfaces: segments around (u) and along (v), decided by the view from the style. */
	interface Subdivision { int segments(Q3.Quadric q,boolean around,float length); }

	/** The mesh of a geometry (cached on the geometry while it does not change and the subdivision stays). */
	static Mesh mesh(Q3.Geometry g,Subdivision sub){
		int su=0,sv=0;
		if(g instanceof Q3.Quadric){Q3.Quadric q=(Q3.Quadric)g;su=segmentsU(q,sub);sv=segmentsV(q,sub);}
		else if(g instanceof Q3.NURBCurve||g instanceof Q3.NURBPatch){su=sub==null?16:Math.max(4,Math.min(64,sub.segments(null,true,1)));sv=su;}
		Object c=g.cache;
		if(c instanceof Mesh&&g.cacheEdit==g.editIndex&&((Mesh)c).su==su&&((Mesh)c).sv==sv&&attrsFresh((Mesh)c,g))return (Mesh)c;
		Mesh m=build(g,su,sv);
		if(m!=null){m.su=su;m.sv=sv;g.cache=m;g.cacheEdit=g.editIndex;m.attrEdit=attrEditSum(g);}
		return m;
	}
	/** The attribute sets inside a geometry can change without the geometry's edit index moving: the sum of theirs is kept. */
	private static boolean attrsFresh(Mesh m,Q3.Geometry g){return m.attrEdit==attrEditSum(g);}
	private static long attrEditSum(Q3.Geometry g){
		long s=0;
		if(g instanceof Q3.Triangle)for(Q3.Vertex v:((Q3.Triangle)g).v)s+=ed(v.attrs);
		else if(g instanceof Q3.Polygon)for(Q3.Vertex v:((Q3.Polygon)g).v)s+=ed(v.attrs);
		else if(g instanceof Q3.TriGrid){Q3.TriGrid t=(Q3.TriGrid)g;for(Q3.Vertex v:t.v)s+=ed(v.attrs);if(t.facets!=null)for(Q3.AttrSet a:t.facets)s+=ed(a);}
		else if(g instanceof Q3.Quadric){Q3.Quadric q=(Q3.Quadric)g;s+=ed(q.interior)+ed(q.top)+ed(q.face)+ed(q.bottom);if(q.faces!=null)for(Q3.AttrSet a:q.faces)s+=ed(a);}
		else if(g instanceof Q3.GeneralPolygon)for(Q3.Vertex[] c:((Q3.GeneralPolygon)g).contours)for(Q3.Vertex v:c)s+=ed(v.attrs);
		else if(g instanceof Q3.Polyhedron){Q3.Polyhedron p=(Q3.Polyhedron)g;for(Q3.Vertex v:p.v)s+=ed(v.attrs);for(Q3.AttrSet a:p.triangleAttrs)s+=ed(a);}
		return s;
	}
	private static long ed(Q3.AttrSet a){return a==null?0:a.editIndex*31L+System.identityHashCode(a);}

	private static int segmentsU(Q3.Quadric q,Subdivision sub){
		float r=Math.max(len(q.major),len(q.minor));
		int n=sub==null?16:sub.segments(q,true,(float)(2*Math.PI*r*Math.abs(q.uMax-q.uMin)));
		return Math.max(3,Math.min(256,n));
	}
	private static int segmentsV(Q3.Quadric q,Subdivision sub){
		int t=q.leaf();if(t==Q3.CYLINDER||t==Q3.CONE||t==Q3.DISK)return 1; // straight along v: the Interactive Renderer cuts it once
		float l=q.leaf()==Q3.TORUS?(float)(2*Math.PI*len(q.minor)*q.ratio):len(q.orientation)*(q.leaf()==Q3.ELLIPSOID?(float)Math.PI:1);
		int n=sub==null?8:sub.segments(q,false,l*Math.abs(q.vMax-q.vMin));
		return Math.max(1,Math.min(256,n));
	}

	private static Mesh build(Q3.Geometry g,int su,int sv){
		int t=g.leaf();
		if(g instanceof Q3.TriMesh)return triMesh((Q3.TriMesh)g);
		if(g instanceof Q3.Triangle)return triangle((Q3.Triangle)g);
		if(g instanceof Q3.Polygon)return polygon(g.attrs,((Q3.Polygon)g).v);
		if(g instanceof Q3.GeneralPolygon)return generalPolygon((Q3.GeneralPolygon)g);
		if(g instanceof Q3.TriGrid)return triGrid((Q3.TriGrid)g);
		if(g instanceof Q3.Polyhedron)return polyhedron((Q3.Polyhedron)g);
		if(g instanceof Q3.Mesh)return mesh((Q3.Mesh)g);
		if(g instanceof Q3.Line){Q3.Line l=(Q3.Line)g;Builder b=new Builder(g.attrs);b.vertex(l.v[0].x,l.v[0].y,l.v[0].z,l.v[0].attrs);b.vertex(l.v[1].x,l.v[1].y,l.v[1].z,l.v[1].attrs);b.lines.add(0);b.lines.add(1);return b.build();}
		if(g instanceof Q3.PolyLine){Q3.PolyLine l=(Q3.PolyLine)g;Builder b=new Builder(g.attrs);for(Q3.Vertex v:l.v)b.vertex(v.x,v.y,v.z,v.attrs);for(int i=0;i+1<l.v.length;i++){b.lines.add(i);b.lines.add(i+1);}return b.build();}
		if(g instanceof Q3.Point){Q3.Point p=(Q3.Point)g;Builder b=new Builder(g.attrs);b.vertex(p.x,p.y,p.z,null);b.points.add(0);return b.build();}
		if(g instanceof Q3.Marker){Q3.Marker p=(Q3.Marker)g;Builder b=new Builder(g.attrs);b.vertex(p.x,p.y,p.z,null);Mesh m=b.build();m.marker=g;return m;}
		if(g instanceof Q3.PixmapMarker){Q3.PixmapMarker p=(Q3.PixmapMarker)g;Builder b=new Builder(g.attrs);b.vertex(p.x,p.y,p.z,null);Mesh m=b.build();m.marker=g;return m;}
		if(g instanceof Q3.Quadric){
			Q3.Quadric q=(Q3.Quadric)g;
			if(t==Q3.BOX)return box(q);
			if(t==Q3.ELLIPSOID)return ellipsoid(q,su,sv);
			if(t==Q3.CYLINDER)return cylinder(q,su,sv,false);
			if(t==Q3.CONE)return cylinder(q,su,sv,true);
			if(t==Q3.DISK)return disk(q,su,sv);
			if(t==Q3.ELLIPSE)return ellipse(q,su);
			if(t==Q3.TORUS)return torus(q,su,sv);
		}
		if(g instanceof Q3.NURBCurve)return nurbCurve((Q3.NURBCurve)g,su);
		if(g instanceof Q3.NURBPatch)return nurbPatch((Q3.NURBPatch)g,su,sv);
		return null;
	}

	// ================================================================== polygonal geometries

	private static Mesh triMesh(Q3.TriMesh g){
		Mesh m=new Mesh();m.attrs=g.attrs;
		int nv=g.numPoints(),nt=g.numTriangles();
		m.nv=nv;m.pos=g.points;m.nt=nt;m.tri=g.triangles;
		for(Q3.AttrArray a:g.vertexAttrs){
			switch(a.type){
				case Q3.ATTR_NORMAL:m.nrm=floats(a,3,nv);break;
				case Q3.ATTR_SURFACE_UV:m.uv=floats(a,2,nv);break;
				case Q3.ATTR_SHADING_UV:if(Q3.find(g.vertexAttrs,Q3.ATTR_SURFACE_UV)==null)m.uv=floats(a,2,nv);break;
				case Q3.ATTR_DIFFUSE:m.diffuse=floats(a,3,nv);break;
				case Q3.ATTR_TRANSPARENCY:m.transp=floats(a,3,nv);break;
				case Q3.ATTR_SPECULAR:m.specular=floats(a,3,nv);break;
				case Q3.ATTR_SPECULAR_CONTROL:m.specCtl=floats(a,1,nv);break;
				case Q3.ATTR_AMBIENT_COEFFICIENT:m.ambient=floats(a,1,nv);break;
				case Q3.ATTR_HIGHLIGHT_STATE:m.highlight=ints(a,nv);break;
				default:break;
			}
		}
		for(Q3.AttrArray a:g.triangleAttrs){
			switch(a.type){
				case Q3.ATTR_NORMAL:m.fNrm=floats(a,3,nt);break;
				case Q3.ATTR_DIFFUSE:m.fDiffuse=floats(a,3,nt);break;
				case Q3.ATTR_TRANSPARENCY:m.fTransp=floats(a,3,nt);break;
				case Q3.ATTR_SPECULAR:m.fSpecular=floats(a,3,nt);break;
				case Q3.ATTR_SPECULAR_CONTROL:m.fSpecCtl=floats(a,1,nt);break;
				case Q3.ATTR_AMBIENT_COEFFICIENT:m.fAmbient=floats(a,1,nt);break;
				case Q3.ATTR_HIGHLIGHT_STATE:m.fHighlight=ints(a,nt);break;
				case Q3.ATTR_SURFACE_SHADER:if(a.o!=null){m.fShader=new Q3.Obj[nt];for(int i=0;i<nt&&i<a.o.length;i++)if(a.used(i))m.fShader[i]=a.o[i];}break;
				default:break;
			}
		}
		if(g.numEdges()>0){int[] e=new int[2*g.numEdges()];for(int i=0;i<g.numEdges();i++){e[2*i]=g.edges[4*i];e[2*i+1]=g.edges[4*i+1];}m.edges=e;}
		else{int[] e=new int[6*nt];for(int i=0;i<nt;i++){int a=g.triangles[3*i],b=g.triangles[3*i+1],c=g.triangles[3*i+2];e[6*i]=a;e[6*i+1]=b;e[6*i+2]=b;e[6*i+3]=c;e[6*i+4]=c;e[6*i+5]=a;}m.edges=e;}
		return m;
	}
	/** Per-element floats of an attribute array, NaN where its use array leaves an element out. */
	private static float[] floats(Q3.AttrArray a,int per,int n){
		if(a.f==null)return null;
		if(a.use==null&&a.f.length>=per*n)return a.f;
		float[] f=new float[per*n];Arrays.fill(f,NAN);
		for(int i=0;i<n&&per*i+per<=a.f.length;i++)if(a.used(i))System.arraycopy(a.f,per*i,f,per*i,per);
		return f;
	}
	private static int[] ints(Q3.AttrArray a,int n){
		if(a.i==null)return null;int[] r=new int[n];Arrays.fill(r,-1);
		for(int i=0;i<n&&i<a.i.length;i++)if(a.used(i))r[i]=a.i[i];return r;
	}

	private static Mesh triangle(Q3.Triangle g){
		Builder b=new Builder(g.attrs);
		for(Q3.Vertex v:g.v)b.vertex(v.x,v.y,v.z,v.attrs);
		b.triangle(0,1,2,null);b.edge(0,1);b.edge(1,2);b.edge(2,0);
		return b.build();
	}
	private static Mesh polygon(Q3.AttrSet attrs,Q3.Vertex[] v){
		Builder b=new Builder(attrs);
		for(Q3.Vertex x:v)b.vertex(x.x,x.y,x.z,x.attrs);
		int n=v.length;
		if(n>=3){int[] idx=new int[n];for(int i=0;i<n;i++)idx[i]=i;triangulate(b,new int[][]{idx},null,-1);}
		for(int i=0;i<n;i++)b.edge(i,(i+1)%n);
		return b.build();
	}
	private static Mesh generalPolygon(Q3.GeneralPolygon g){
		Builder b=new Builder(g.attrs);
		int[][] contours=new int[g.contours.length][];int k=0;
		for(int c=0;c<g.contours.length;c++){Q3.Vertex[] cv=g.contours[c];contours[c]=new int[cv.length];for(int i=0;i<cv.length;i++){b.vertex(cv[i].x,cv[i].y,cv[i].z,cv[i].attrs);contours[c][i]=k++;}
			for(int i=0;i<cv.length;i++)b.edge(contours[c][i],contours[c][(i+1)%cv.length]);}
		triangulate(b,contours,null,-1); // the shape hint is not trusted: QuickDraw 3D draws a concave "convex" polygon right (3DTexter's letters)
		return b.build();
	}
	private static Mesh triGrid(Q3.TriGrid g){
		Builder b=new Builder(g.attrs);
		for(Q3.Vertex v:g.v)b.vertex(v.x,v.y,v.z,v.attrs);
		int rows=g.rows,cols=g.columns,f=0;
		for(int r=0;r+1<rows;r++)for(int c=0;c+1<cols;c++){
			int a=r*cols+c,bb=a+1,cc=a+cols,d=cc+1;
			// the diagonals alternate from square to square
			boolean flip=((r+c)&1)!=0;
			Q3.AttrSet s0=g.facets!=null&&f<g.facets.length?g.facets[f]:null,s1=g.facets!=null&&f+1<g.facets.length?g.facets[f+1]:null;f+=2;
			if(!flip){b.triangle(a,cc,bb,s0);b.triangle(bb,cc,d,s1);}
			else{b.triangle(a,cc,d,s0);b.triangle(a,d,bb,s1);}
		}
		for(int r=0;r<rows;r++)for(int c=0;c+1<cols;c++)b.edge(r*cols+c,r*cols+c+1);
		for(int c=0;c<cols;c++)for(int r=0;r+1<rows;r++)b.edge(r*cols+c,(r+1)*cols+c);
		return b.build();
	}
	private static Mesh polyhedron(Q3.Polyhedron g){
		Builder b=new Builder(g.attrs);
		for(Q3.Vertex v:g.v)b.vertex(v.x,v.y,v.z,v.attrs);
		int nt=g.triangles.length/3;
		for(int t=0;t<nt;t++){
			int a=g.triangles[3*t],c=g.triangles[3*t+1],d=g.triangles[3*t+2];
			if(a<0||c<0||d<0||a>=g.v.length||c>=g.v.length||d>=g.v.length)continue;
			b.triangle(a,c,d,t<g.triangleAttrs.length?g.triangleAttrs[t]:null);
			int flags=t<g.edgeFlags.length?g.edgeFlags[t]:7;
			if((flags&1)!=0)b.edge(a,c);if((flags&2)!=0)b.edge(c,d);if((flags&4)!=0)b.edge(d,a);
		}
		return b.build();
	}
	private static Mesh mesh(Q3.Mesh g){
		Builder b=new Builder(g.attrs);
		java.util.IdentityHashMap<Q3.Mesh.MVertex,Integer> index=new java.util.IdentityHashMap<Q3.Mesh.MVertex,Integer>();
		for(Q3.Mesh.MVertex v:g.vertices)index.put(v,b.vertex(v.x,v.y,v.z,v.attrs));
		for(Q3.Mesh.MFace f:g.faces){
			// corners: a face's own copy of a vertex with the corner's attributes
			java.util.IdentityHashMap<Q3.Mesh.MVertex,Integer> local=new java.util.IdentityHashMap<Q3.Mesh.MVertex,Integer>();
			for(java.util.Map.Entry<Q3.Mesh.MVertex,Q3.AttrSet> e:f.corners.entrySet()){
				Q3.Mesh.MVertex v=e.getKey();Q3.AttrSet s=new Q3.AttrSet();if(v.attrs!=null)s.inherit(v.attrs);s.inherit(e.getValue());
				local.put(v,b.vertex(v.x,v.y,v.z,s));
			}
			int[][] contours=new int[f.contours.size()][];
			for(int c=0;c<contours.length;c++){List<Q3.Mesh.MVertex> vs=f.contours.get(c).vertices;contours[c]=new int[vs.size()];
				for(int i=0;i<vs.size();i++){Integer li=local.get(vs.get(i));contours[c][i]=li!=null?li:index.get(vs.get(i));}
				for(int i=0;i<vs.size();i++)b.edge(contours[c][i],contours[c][(i+1)%vs.size()]);}
			int first=b.nt;
			triangulate(b,contours,f.attrs,-1);
			if(b.nt>first&&f.attrs!=null)for(int t=first+1;t<b.nt;t++)b.copyFace(t,first);
		}
		return b.build();
	}

	/**
	 * Triangulates contours (the first the outline, the rest holes) of vertices already in the builder, in the plane of
	 * their Newell normal: holes are bridged into the outline, then ears are clipped. The triangles keep the outline's
	 * direction; {@code faceSet} is the set every one of them gets.
	 */
	static void triangulate(Builder b,int[][] contours,Q3.AttrSet faceSet,int unused){
		if(contours.length==0)return;
		if(contours.length==1&&contours[0].length==3){int[] o=contours[0];b.triangle(o[0],o[1],o[2],faceSet);return;}
		float[] p=b.pos;
		// the plane: the Newell normal of the biggest contour
		double nx=0,ny=0,nz=0,best=-1;
		for(int[] o:contours){
			double x=0,y=0,z=0;
			for(int i=0;i<o.length;i++){int a=o[i],c=o[(i+1)%o.length];x+=(p[3*a+1]-p[3*c+1])*(p[3*a+2]+p[3*c+2]);y+=(p[3*a+2]-p[3*c+2])*(p[3*a]+p[3*c]);z+=(p[3*a]-p[3*c])*(p[3*a+1]+p[3*c+1]);}
			double m=x*x+y*y+z*z;if(m>best){best=m;nx=x;ny=y;nz=z;}
		}
		int ax=Math.abs(nx)>Math.abs(ny)?(Math.abs(nx)>Math.abs(nz)?0:2):(Math.abs(ny)>Math.abs(nz)?1:2);
		int u=ax==0?1:0,v=ax==2?1:2;boolean flip=(ax==0?nx:ax==1?ny:nz)<0;
		if(ax==1)flip=!flip; // the (z, x) plane's handedness
		if(contours.length==1){triangulate(b,contours[0],new ArrayList<int[]>(),faceSet,u,v,flip);return;}
		// several contours filled even-odd: a contour inside an odd number of others is a hole of the smallest of them;
		// contours without area (programs pass stale ones) are left out
		int n=contours.length;double[] area=new double[n];double maxArea=0;
		for(int c=0;c<n;c++){int[] o=contours[c];double a=0;for(int i=0;i<o.length;i++){int s=o[i],t=o[(i+1)%o.length];a+=p[3*s+u]*p[3*t+v]-p[3*t+u]*p[3*s+v];}area[c]=a/2;maxArea=Math.max(maxArea,Math.abs(area[c]));}
		boolean[] used=new boolean[n];for(int c=0;c<n;c++)used[c]=contours[c].length>=3&&Math.abs(area[c])>maxArea*1e-6;
		int[] depth=new int[n],parent=new int[n];
		for(int c=0;c<n;c++){parent[c]=-1;if(!used[c])continue;
			for(int d=0;d<n;d++)if(d!=c&&used[d]&&Math.abs(area[d])>Math.abs(area[c])&&contains(p,contours[d],contours[c],u,v)){
				depth[c]++;if(parent[c]<0||Math.abs(area[d])<Math.abs(area[parent[c]]))parent[c]=d;}}
		for(int c=0;c<n;c++){
			if(!used[c]||depth[c]%2!=0)continue;
			List<int[]> holes=new ArrayList<int[]>();
			for(int h=0;h<n;h++)if(used[h]&&depth[h]%2==1&&parent[h]==c){
				int[] hole=contours[h];
				if((area[h]>0)==(area[c]>0)){hole=hole.clone();for(int i=0,j=hole.length-1;i<j;i++,j--){int t=hole[i];hole[i]=hole[j];hole[j]=t;}}
				holes.add(hole);}
			triangulate(b,contours[c],holes,faceSet,u,v,flip);
		}
	}
	/** Whether contour {@code inner} (its first vertex off the boundary) lies inside {@code outer}, projected on (u, v). */
	private static boolean contains(float[] p,int[] outer,int[] inner,int u,int v){
		for(int k=0;k<inner.length;k++){
			double x=p[3*inner[k]+u],y=p[3*inner[k]+v];boolean in=false,edge=false;
			for(int i=0,j=outer.length-1;i<outer.length;j=i++){
				double xi=p[3*outer[i]+u],yi=p[3*outer[i]+v],xj=p[3*outer[j]+u],yj=p[3*outer[j]+v];
				if(xi==x&&yi==y){edge=true;break;}
				if((yi>y)!=(yj>y)&&x<(xj-xi)*(y-yi)/(yj-yi)+xi)in=!in;
			}
			if(!edge)return in;
		}
		return false;
	}
	/** An outline with holes (oriented against it) by ear clipping, the holes bridged into it. */
	private static void triangulate(Builder b,int[] o,List<int[]> holeList,Q3.AttrSet faceSet,int u,int v,boolean flip){
		if(o.length<3)return;
		if(holeList.isEmpty()&&o.length==3){b.triangle(o[0],o[1],o[2],faceSet);return;}
		float[] p=b.pos;
		int[][] contours=new int[holeList.size()+1][];contours[0]=o;for(int i=0;i<holeList.size();i++)contours[i+1]=holeList.get(i);
		List<Integer> ring=new ArrayList<Integer>();for(int x:o)ring.add(x);
		// bridge holes: from each hole's rightmost vertex to a visible outline vertex
		for(int h=1;h<contours.length;h++){
			int[] hole=contours[h];if(hole.length<3)continue;
			int hi=0;for(int i=1;i<hole.length;i++)if(p[3*hole[i]+u]>p[3*hole[hi]+u])hi=i;
			float hx=p[3*hole[hi]+u],hy=p[3*hole[hi]+v];
			int best=-1;double bd=Double.MAX_VALUE;
			for(int i=0;i<ring.size();i++){int r=ring.get(i);double dx=p[3*r+u]-hx,dy=p[3*r+v]-hy;if(dx<0)continue;double d=dx*dx+dy*dy;if(d<bd){bd=d;best=i;}}
			if(best<0)for(int i=0;i<ring.size();i++){int r=ring.get(i);double dx=p[3*r+u]-hx,dy=p[3*r+v]-hy;double d=dx*dx+dy*dy;if(d<bd){bd=d;best=i;}}
			List<Integer> ins=new ArrayList<Integer>();
			for(int k=0;k<=hole.length;k++)ins.add(hole[(hi+k)%hole.length]);
			ins.add(ring.get(best));
			ring.addAll(best+1,ins);
		}
		int n=ring.size();
		int[] idx=new int[n];for(int i=0;i<n;i++)idx[i]=ring.get(i);
		double[] xs=new double[n],ys=new double[n];
		for(int i=0;i<n;i++){xs[i]=p[3*idx[i]+u];ys[i]=flip?-p[3*idx[i]+v]:p[3*idx[i]+v];}
		// orientation of the ring in this projection
		double area=0;for(int i=0;i<n;i++){int j=(i+1)%n;area+=xs[i]*ys[j]-xs[j]*ys[i];}
		boolean ccw=area>=0;
		int[] next=new int[n],prev=new int[n];for(int i=0;i<n;i++){next[i]=(i+1)%n;prev[i]=(i+n-1)%n;}
		int left=n,cur=0,guard=0;
		while(left>3&&guard<n*n+10){
			guard++;
			int a=prev[cur],c=next[cur];
			if(isEar(xs,ys,a,cur,c,next,ccw,idx)){
				b.triangle(idx[a],idx[cur],idx[c],faceSet);
				next[a]=c;prev[c]=a;left--;cur=c;guard=0;
			}else cur=next[cur];
		}
		if(left==3){int a=prev[cur],c=next[cur];b.triangle(idx[a],idx[cur],idx[c],faceSet);}
		else if(left>3){ // degenerate outline: fan what is left
			int s=cur;int x=next[s];while(next[x]!=s){b.triangle(idx[s],idx[x],idx[next[x]],faceSet);x=next[x];}
		}
	}
	private static boolean isEar(double[] xs,double[] ys,int a,int b,int c,int[] next,boolean ccw,int[] idx){
		double cross=(xs[b]-xs[a])*(ys[c]-ys[a])-(ys[b]-ys[a])*(xs[c]-xs[a]);
		if(ccw?cross<=0:cross>=0){return cross==0&&collinearOnly(xs,ys,a,b,c);}
		for(int p=next[c];p!=a;p=next[p]){
			if(idx[p]==idx[a]||idx[p]==idx[b]||idx[p]==idx[c])continue;
			if(inside(xs[p],ys[p],xs[a],ys[a],xs[b],ys[b],xs[c],ys[c]))return false;
		}
		return true;
	}
	private static boolean collinearOnly(double[] xs,double[] ys,int a,int b,int c){return false;}
	private static boolean inside(double px,double py,double ax,double ay,double bx,double by,double cx,double cy){
		double d1=(px-bx)*(ay-by)-(ax-bx)*(py-by),d2=(px-cx)*(by-cy)-(bx-cx)*(py-cy),d3=(px-ax)*(cy-ay)-(cx-ax)*(py-ay);
		boolean neg=d1<0||d2<0||d3<0,pos=d1>0||d2>0||d3>0;
		return !(neg&&pos);
	}

	// ================================================================== smooth surfaces

	private static float len(float[] v){return (float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);}
	/** The point origin + a*major + b*minor + c*orientation. */
	private static void at(Q3.Quadric q,double a,double bb,double c,float[] out){
		for(int k=0;k<3;k++)out[k]=(float)(q.origin[k]+a*q.major[k]+bb*q.minor[k]+c*q.orientation[k]);
	}
	/** A direction a*major' + b*minor' + c*orientation' in the normal frame (the inverse transpose of the axes). */
	private static void normal(Q3.Quadric q,double a,double bb,double c,float[] out){
		float[] M=q.major,N=q.minor,O=q.orientation;
		// columns of the axis matrix: M N O; normals transform with its inverse transpose = cofactor matrix / det
		double c0x=N[1]*O[2]-N[2]*O[1],c0y=N[2]*O[0]-N[0]*O[2],c0z=N[0]*O[1]-N[1]*O[0];
		double c1x=O[1]*M[2]-O[2]*M[1],c1y=O[2]*M[0]-O[0]*M[2],c1z=O[0]*M[1]-O[1]*M[0];
		double c2x=M[1]*N[2]-M[2]*N[1],c2y=M[2]*N[0]-M[0]*N[2],c2z=M[0]*N[1]-M[1]*N[0];
		double det=M[0]*c0x+M[1]*c0y+M[2]*c0z;double s=det<0?-1:1;
		double x=(a*c0x+bb*c1x+c*c2x)*s,y=(a*c0y+bb*c1y+c*c2y)*s,z=(a*c0z+bb*c1z+c*c2z)*s;
		double l=Math.sqrt(x*x+y*y+z*z);if(l==0)l=1;
		out[0]=(float)(x/l);out[1]=(float)(y/l);out[2]=(float)(z/l);
	}
	private static int vtx(Builder b,Q3.Quadric q,double a,double bb,double c,double na,double nb,double nc,float u,float v){
		float[] p=b.scratch;at(q,a,bb,c,p);int i=b.vertex(p[0],p[1],p[2],null);normal(q,na,nb,nc,p);b.setNormal(i,p[0],p[1],p[2]);b.setUV(i,u,v);return i;
	}

	/** The box: six faces (the face attribute sets in QD3D's order: -X... see below), each two triangles with flat normals. */
	private static Mesh box(Q3.Quadric q){
		Builder b=new Builder(q.attrs);
		// faces as (axis corners): QuickDraw 3D orders them left, right, front, back, top, bottom in the box's axes
		// (orientation = y, major = z, minor = x); each face runs counter-clockwise seen from outside
		int[][] faces={{0,4,6,2},{1,3,7,5},{0,1,5,4},{2,6,7,3},{0,2,3,1},{4,5,7,6}};
		float[][] corner=new float[8][3];
		for(int i=0;i<8;i++){double mi=(i&1)!=0?1:0,or=(i&2)!=0?1:0,ma=(i&4)!=0?1:0;at(q,ma,mi,or,corner[i]);}
		for(int f=0;f<6;f++){
			int[] c=faces[f];
			float[] a=corner[c[0]],bb=corner[c[1]],cc=corner[c[2]];
			float ux=bb[0]-a[0],uy=bb[1]-a[1],uz=bb[2]-a[2],vx=cc[0]-a[0],vy=cc[1]-a[1],vz=cc[2]-a[2];
			float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;float l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(l>0){nx/=l;ny/=l;nz/=l;}
			int[] vi=new int[4];float[][] uvs={{0,0},{1,0},{1,1},{0,1}};
			for(int k=0;k<4;k++){float[] p=corner[c[k]];vi[k]=b.vertex(p[0],p[1],p[2],null);b.setNormal(vi[k],nx,ny,nz);b.setUV(vi[k],uvs[k][0],uvs[k][1]);}
			Q3.AttrSet fs=q.faces!=null&&f<q.faces.length?q.faces[f]:null;
			b.triangle(vi[0],vi[1],vi[2],fs);b.triangle(vi[0],vi[2],vi[3],fs);
			for(int k=0;k<4;k++)b.edge(vi[k],vi[(k+1)%4]);
		}
		return b.build();
	}
	private static Mesh ellipsoid(Q3.Quadric q,int su,int sv){
		Builder b=new Builder(q.attrs);
		int rings=Math.max(2,sv);
		double u0=q.uMin*2*Math.PI,u1=q.uMax*2*Math.PI;
		int[][] grid=new int[rings+1][su+1];
		for(int r=0;r<=rings;r++){
			double t=q.vMin+(q.vMax-q.vMin)*r/rings;double phi=Math.PI*(t-0.5); // -pi/2 at the bottom pole
			double cp=Math.cos(phi),sp=Math.sin(phi);
			for(int s=0;s<=su;s++){double th=u0+(u1-u0)*s/su;double a=Math.cos(th)*cp,c=Math.sin(th)*cp;
				grid[r][s]=vtx(b,q,a,c,sp,a,c,sp,(float)((double)s/su),(float)t);}
		}
		for(int r=0;r<rings;r++)for(int s=0;s<su;s++){int a=grid[r][s],bb=grid[r][s+1],c=grid[r+1][s+1],d=grid[r+1][s];
			if(r>0)b.triangle(a,bb,c,q.face);if(r<rings-1)b.triangle(a,c,d,q.face);}
		gridEdges(b,grid);
		return b.build();
	}
	/** The quadrilateral outlines of a parametric grid (the edges fill style draws them, not the diagonals). */
	private static void gridEdges(Builder b,int[][] g){
		for(int r=0;r<g.length;r++)for(int c=0;c+1<g[r].length;c++)b.edge(g[r][c],g[r][c+1]);
		for(int r=0;r+1<g.length;r++)for(int c=0;c<g[r].length;c++)b.edge(g[r][c],g[r+1][c]);
	}
	/** Cylinder (and cone when {@code cone}): side, and the bottom (and top) caps when asked for. */
	private static Mesh cylinder(Q3.Quadric q,int su,int sv,boolean cone){
		Builder b=new Builder(q.attrs);
		double u0=q.uMin*2*Math.PI,u1=q.uMax*2*Math.PI;
		int rings=Math.max(1,sv);
		int[][] grid=new int[rings+1][su+1];
		for(int r=0;r<=rings;r++){double t=q.vMin+(q.vMax-q.vMin)*r/rings;double scale=cone?1-t:1;
			for(int s=0;s<=su;s++){double th=u0+(u1-u0)*s/su;double a=Math.cos(th),c=Math.sin(th);
				// a cone's side normal leans up by its slope (radius 1 over height 1 in the axis frame)
				grid[r][s]=vtx(b,q,a*scale,c*scale,t,a,c,cone?1:0,(float)((double)s/su),(float)t);}}
		for(int r=0;r<rings;r++)for(int s=0;s<su;s++){int a=grid[r][s],bb=grid[r][s+1],c=grid[r+1][s+1],d=grid[r+1][s];
			b.triangle(a,bb,c,q.face);if(!(cone&&r==rings-1&&q.vMax>=1))b.triangle(a,c,d,q.face);}
		gridEdges(b,grid);
		if((q.caps&2)!=0)cap(b,q,u0,u1,su,0,-1,q.bottom,1);
		if(!cone&&(q.caps&1)!=0)cap(b,q,u0,u1,su,1,1,q.top,1);
		return b.build();
	}
	private static void cap(Builder b,Q3.Quadric q,double u0,double u1,int su,double h,int dir,Q3.AttrSet set,double radius){
		int centre=vtx(b,q,0,0,h,0,0,dir,0.5f,0.5f);
		int[] ring=new int[su+1];
		for(int s=0;s<=su;s++){double th=u0+(u1-u0)*s/su;double a=Math.cos(th)*radius,c=Math.sin(th)*radius;ring[s]=vtx(b,q,a,c,h,0,0,dir,(float)(0.5+a/2),(float)(0.5+c/2));}
		for(int s=0;s<su;s++){if(dir>0)b.triangle(centre,ring[s],ring[s+1],set);else b.triangle(centre,ring[s+1],ring[s],set);b.edge(centre,ring[s]);b.edge(ring[s],ring[s+1]);}
	}
	private static Mesh disk(Q3.Quadric q,int su,int sv){
		Builder b=new Builder(q.attrs);
		double u0=q.uMin*2*Math.PI,u1=q.uMax*2*Math.PI;
		int rings=Math.max(1,sv);
		int[][] grid=new int[rings+1][su+1];
		for(int r=0;r<=rings;r++){double rad=1-(q.vMin+(q.vMax-q.vMin)*r/rings);
			for(int s=0;s<=su;s++){double th=u0+(u1-u0)*s/su;double a=Math.cos(th)*rad,c=Math.sin(th)*rad;
				float[] P=b.scratch;at(q,a,c,0,P);int i=b.vertex(P[0],P[1],P[2],null);float[] n=diskNormal(q);b.setNormal(i,n[0],n[1],n[2]);b.setUV(i,(float)(0.5+a/2),(float)(0.5+c/2));grid[r][s]=i;}}
		for(int r=0;r<rings;r++)for(int s=0;s<su;s++){int a=grid[r][s],bb=grid[r][s+1],c=grid[r+1][s+1],d=grid[r+1][s];b.triangle(a,bb,c,null);b.triangle(a,c,d,null);}
		gridEdges(b,grid);
		return b.build();
	}
	private static float[] diskNormal(Q3.Quadric q){
		float[] M=q.major,N=q.minor;float x=M[1]*N[2]-M[2]*N[1],y=M[2]*N[0]-M[0]*N[2],z=M[0]*N[1]-M[1]*N[0];float l=(float)Math.sqrt(x*x+y*y+z*z);if(l==0)l=1;return new float[]{x/l,y/l,z/l};
	}
	private static Mesh ellipse(Q3.Quadric q,int su){
		Builder b=new Builder(q.attrs);
		double u0=q.uMin*2*Math.PI,u1=q.uMax*2*Math.PI;
		float[] P=b.scratch;
		for(int s=0;s<=su;s++){double th=u0+(u1-u0)*s/su;at(q,Math.cos(th),Math.sin(th),0,P);b.vertex(P[0],P[1],P[2],null);if(s>0){b.lines.add(s-1);b.lines.add(s);}}
		return b.build();
	}
	private static Mesh torus(Q3.Quadric q,int su,int sv){
		Builder b=new Builder(q.attrs);
		double u0=q.uMin*2*Math.PI,u1=q.uMax*2*Math.PI,v0=q.vMin*2*Math.PI,v1=q.vMax*2*Math.PI;
		// in the axis frame: the ring of radius 1 about the orientation axis, the tube of radius ratio (in orientation units: 1)
		int[][] grid=new int[sv+1][su+1];
		for(int r=0;r<=sv;r++){double ph=v0+(v1-v0)*r/sv;double cph=Math.cos(ph),sph=Math.sin(ph);
			for(int s=0;s<=su;s++){double th=u0+(u1-u0)*s/su;double ct=Math.cos(th),st=Math.sin(th);
				double rad=1+q.ratio*cph;
				grid[r][s]=vtx(b,q,ct*rad,st*rad,sph,ct*cph,st*cph,sph*q.ratio,(float)((double)s/su),(float)((double)r/sv));}}
		for(int r=0;r<sv;r++)for(int s=0;s<su;s++){int a=grid[r][s],bb=grid[r][s+1],c=grid[r+1][s+1],d=grid[r+1][s];b.triangle(a,bb,c,null);b.triangle(a,c,d,null);}
		gridEdges(b,grid);
		return b.build();
	}

	// ================================================================== NURBS

	/** The value of B-spline basis evaluation: a point of the curve at t (rational: x y z w). */
	private static void deBoor(int order,float[] knots,float[] ctl,int stride,int count,double t,double[] out){
		int k=order-1,span=k;
		int n=count-1;
		if(t>=knots[n+1])span=n;else while(span<n&&knots[span+1]<=t)span++;
		double[][] d=new double[order][stride];
		for(int j=0;j<=k;j++){int i=Math.max(0,Math.min(count-1,j+span-k));for(int c=0;c<stride;c++)d[j][c]=ctl[stride*i+c];}
		for(int r=1;r<=k;r++)for(int j=k;j>=r;j--){
			int i=j+span-k;double den=knots[i+order-r]-knots[i];double a=den==0?0:(t-knots[i])/den;
			for(int c=0;c<stride;c++)d[j][c]=(1-a)*d[j-1][c]+a*d[j][c];
		}
		System.arraycopy(d[k],0,out,0,stride);
	}
	private static Mesh nurbCurve(Q3.NURBCurve g,int segs){
		Builder b=new Builder(g.attrs);
		int n=g.points.length/4;if(n<g.order||g.knots.length<n+g.order)return b.build();
		float t0=g.knots[g.order-1],t1=g.knots[n];double[] o=new double[4];
		int count=segs*Math.max(1,n-g.order+1);
		for(int i=0;i<=count;i++){double t=t0+(t1-t0)*i/count;deBoor(g.order,g.knots,g.points,4,n,Math.min(t,t1-1e-6*(t1-t0)),o);double w=o[3]==0?1:o[3];
			b.vertex((float)(o[0]/w),(float)(o[1]/w),(float)(o[2]/w),null);if(i>0){b.lines.add(i-1);b.lines.add(i);}}
		return b.build();
	}
	private static Mesh nurbPatch(Q3.NURBPatch g,int su,int sv){
		Builder b=new Builder(g.attrs);
		int cols=g.columns,rows=g.rows;
		if(cols<g.uOrder||rows<g.vOrder||g.uKnots.length<cols+g.uOrder||g.vKnots.length<rows+g.vOrder)return b.build();
		int nu=su*Math.max(1,cols-g.uOrder+1),nv=sv*Math.max(1,rows-g.vOrder+1);
		nu=Math.min(nu,96);nv=Math.min(nv,96);
		float u0=g.uKnots[g.uOrder-1],u1=g.uKnots[cols],v0=g.vKnots[g.vOrder-1],v1=g.vKnots[rows];
		float[] rowPts=new float[4*rows];double[] o=new double[4];float[][] pts=new float[(nv+1)*(nu+1)][];
		for(int i=0;i<=nu;i++){
			double u=Math.min(u0+(u1-u0)*i/nu,u1-1e-6*(u1-u0));
			for(int r=0;r<rows;r++){float[] row=Arrays.copyOfRange(g.points,4*cols*r,4*cols*(r+1));deBoor(g.uOrder,g.uKnots,row,4,cols,u,o);for(int c=0;c<4;c++)rowPts[4*r+c]=(float)o[c];}
			for(int j=0;j<=nv;j++){double v=Math.min(v0+(v1-v0)*j/nv,v1-1e-6*(v1-v0));deBoor(g.vOrder,g.vKnots,rowPts,4,rows,v,o);double w=o[3]==0?1:o[3];
				pts[j*(nu+1)+i]=new float[]{(float)(o[0]/w),(float)(o[1]/w),(float)(o[2]/w)};}
		}
		int[] idx=new int[pts.length];
		for(int j=0;j<=nv;j++)for(int i=0;i<=nu;i++){float[] p=pts[j*(nu+1)+i];idx[j*(nu+1)+i]=b.vertex(p[0],p[1],p[2],null);b.setUV(idx[j*(nu+1)+i],(float)i/nu,(float)j/nv);}
		for(int j=0;j<nv;j++)for(int i=0;i<nu;i++){int a=idx[j*(nu+1)+i],bb=idx[j*(nu+1)+i+1],c=idx[(j+1)*(nu+1)+i+1],d=idx[(j+1)*(nu+1)+i];b.triangle(a,bb,c,null);b.triangle(a,c,d,null);}
		// smooth normals from the grid
		Mesh m=b.build();smoothNormals(m);return m;
	}
	/** Vertex normals averaged from the faces around them (surfaces evaluated without analytic normals). */
	static void smoothNormals(Mesh m){
		float[] n=new float[3*m.nv];
		for(int t=0;t<m.nt;t++){int a=m.tri[3*t],b=m.tri[3*t+1],c=m.tri[3*t+2];float[] p=m.pos;
			float ux=p[3*b]-p[3*a],uy=p[3*b+1]-p[3*a+1],uz=p[3*b+2]-p[3*a+2],vx=p[3*c]-p[3*a],vy=p[3*c+1]-p[3*a+1],vz=p[3*c+2]-p[3*a+2];
			float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
			for(int k:new int[]{a,b,c}){n[3*k]+=nx;n[3*k+1]+=ny;n[3*k+2]+=nz;}}
		for(int i=0;i<m.nv;i++){float l=(float)Math.sqrt(n[3*i]*n[3*i]+n[3*i+1]*n[3*i+1]+n[3*i+2]*n[3*i+2]);if(l>0){n[3*i]/=l;n[3*i+1]/=l;n[3*i+2]/=l;}}
		m.nrm=n;
	}
}

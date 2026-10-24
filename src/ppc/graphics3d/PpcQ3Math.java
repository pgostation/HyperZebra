/**
 * The math of QuickDraw 3D (QD3DMath.h) for {@link PpcQuickDraw3D}: points, vectors, matrices (row vectors, p * M),
 * quaternions and bounds on the program's structures. Every function returns its result pointer as the library did
 * (or a float in f1).
 */
final class PpcQ3Math {
	private final PpcQuickDraw3D q;private final M68kMemory memory;
	PpcQ3Math(PpcQuickDraw3D q){this.q=q;this.memory=q.memory;}

	private float rf(int a) throws M68kCpuException {return Float.intBitsToFloat(memory.readLong(a));}
	private void wf(int a,float v) throws M68kCpuException {memory.writeLong(a,Float.floatToRawIntBits(v));}
	private float[] r(int a,int n) throws M68kCpuException {return q.rfs(a,n);}
	private void w(int a,float... v) throws M68kCpuException {q.wfs(a,v,0,v.length);}
	private float[] m4(int a) throws M68kCpuException {return q.rfs(a,16);}
	private float[] m3(int a) throws M68kCpuException {return q.rfs(a,9);}
	private static float sqrt(double v){return (float)Math.sqrt(v);}
	/** The angle of (x, y) in 0..2π, as QuickDraw 3D gives polar and spherical angles. */
	private static float angle(float x,float y){double t=Math.atan2(y,x);if(t<0)t+=2*Math.PI;return (float)t;}

	void define(){
		// ---- setting
		q.def("Q3Point2D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2));a.ret(p);});
		q.def("Q3Param2D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2));a.ret(p);});
		q.def("Q3Point3D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2),a.f(3));a.ret(p);});
		q.def("Q3RationalPoint3D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2),a.f(3));a.ret(p);});
		q.def("Q3RationalPoint4D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2),a.f(3),a.f(4));a.ret(p);});
		q.def("Q3Vector2D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2));a.ret(p);});
		q.def("Q3Vector3D_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2),a.f(3));a.ret(p);});
		q.def("Q3PolarPoint_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2));a.ret(p);});
		q.def("Q3SphericalPoint_Set",a->{int p=a.i(0);w(p,a.f(1),a.f(2),a.f(3));a.ret(p);});
		// ---- dimension conversion
		q.def("Q3Point2D_To3D",a->{float[] p=r(a.i(0),2);int o=a.i(1);w(o,p[0],p[1],1);a.ret(o);});
		q.def("Q3RationalPoint3D_To2D",a->{float[] p=r(a.i(0),3);int o=a.i(1);float iw=p[2]==0?1:1/p[2];w(o,p[0]*iw,p[1]*iw);a.ret(o);});
		q.def("Q3Point3D_To4D",a->{float[] p=r(a.i(0),3);int o=a.i(1);w(o,p[0],p[1],p[2],1);a.ret(o);});
		q.def("Q3RationalPoint4D_To3D",a->{float[] p=r(a.i(0),4);int o=a.i(1);float iw=p[3]==0?1:1/p[3];w(o,p[0]*iw,p[1]*iw,p[2]*iw);a.ret(o);});
		q.def("Q3Vector2D_To3D",a->{float[] p=r(a.i(0),2);int o=a.i(1);w(o,p[0],p[1],1);a.ret(o);});
		q.def("Q3Vector3D_To2D",a->{float[] p=r(a.i(0),3);int o=a.i(1);float iz=p[2]==0?1:1/p[2];w(o,p[0]*iz,p[1]*iz);a.ret(o);});
		// ---- subtraction and distance
		q.def("Q3Point2D_Subtract",a->sub(a,2));q.def("Q3Param2D_Subtract",a->sub(a,2));q.def("Q3Point3D_Subtract",a->sub(a,3));
		q.def("Q3Point2D_Distance",a->a.retF(dist(a,2,2,false)));q.def("Q3Point2D_DistanceSquared",a->a.retF(dist(a,2,2,true)));
		q.def("Q3Param2D_Distance",a->a.retF(dist(a,2,2,false)));q.def("Q3Param2D_DistanceSquared",a->a.retF(dist(a,2,2,true)));
		q.def("Q3RationalPoint3D_Distance",a->a.retF(dist(a,3,2,false)));q.def("Q3RationalPoint3D_DistanceSquared",a->a.retF(dist(a,3,2,true)));
		q.def("Q3Point3D_Distance",a->a.retF(dist(a,3,3,false)));q.def("Q3Point3D_DistanceSquared",a->a.retF(dist(a,3,3,true)));
		q.def("Q3RationalPoint4D_Distance",a->a.retF(dist(a,4,3,false)));q.def("Q3RationalPoint4D_DistanceSquared",a->a.retF(dist(a,4,3,true)));
		// ---- relative ratio: the point dividing p1-p2 in the ratio r1:r2
		q.def("Q3Point2D_RRatio",a->rratio(a,2));q.def("Q3Param2D_RRatio",a->rratio(a,2));q.def("Q3Point3D_RRatio",a->rratio(a,3));q.def("Q3RationalPoint4D_RRatio",a->rratio(a,4));
		// ---- point and vector arithmetic
		q.def("Q3Point2D_Vector2D_Add",a->add(a,2,1));q.def("Q3Param2D_Vector2D_Add",a->add(a,2,1));q.def("Q3Point3D_Vector3D_Add",a->add(a,3,1));
		q.def("Q3Point2D_Vector2D_Subtract",a->add(a,2,-1));q.def("Q3Param2D_Vector2D_Subtract",a->add(a,2,-1));q.def("Q3Point3D_Vector3D_Subtract",a->add(a,3,-1));
		q.def("Q3Vector2D_Scale",a->{float[] v=r(a.i(0),2);float k=a.f(1);int o=a.i(2);w(o,v[0]*k,v[1]*k);a.ret(o);});
		q.def("Q3Vector3D_Scale",a->{float[] v=r(a.i(0),3);float k=a.f(1);int o=a.i(2);w(o,v[0]*k,v[1]*k,v[2]*k);a.ret(o);});
		q.def("Q3Vector2D_Length",a->{float[] v=r(a.i(0),2);a.retF(sqrt(v[0]*v[0]+v[1]*v[1]));});
		q.def("Q3Vector3D_Length",a->{float[] v=r(a.i(0),3);a.retF(sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]));});
		q.def("Q3Vector2D_Normalize",a->{float[] v=r(a.i(0),2);int o=a.i(1);float l=sqrt(v[0]*v[0]+v[1]*v[1]);float k=l==0?0:1/l;w(o,v[0]*k,v[1]*k);a.ret(o);});
		q.def("Q3Vector3D_Normalize",a->{float[] v=r(a.i(0),3);int o=a.i(1);float l=sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);float k=l==0?0:1/l;w(o,v[0]*k,v[1]*k,v[2]*k);a.ret(o);});
		q.def("Q3Vector2D_Add",a->add(a,2,1));q.def("Q3Vector3D_Add",a->add(a,3,1));
		q.def("Q3Vector2D_Subtract",a->add(a,2,-1));q.def("Q3Vector3D_Subtract",a->add(a,3,-1));
		q.def("Q3Vector2D_Cross",a->{float[] x=r(a.i(0),2),y=r(a.i(1),2);a.retF(x[0]*y[1]-x[1]*y[0]);});
		q.def("Q3Vector3D_Cross",a->{float[] x=r(a.i(0),3),y=r(a.i(1),3);int o=a.i(2);w(o,x[1]*y[2]-x[2]*y[1],x[2]*y[0]-x[0]*y[2],x[0]*y[1]-x[1]*y[0]);a.ret(o);});
		q.def("Q3Point3D_CrossProductTri",a->{float[] p1=r(a.i(0),3),p2=r(a.i(1),3),p3=r(a.i(2),3);int o=a.i(3);
			float ux=p2[0]-p1[0],uy=p2[1]-p1[1],uz=p2[2]-p1[2],vx=p3[0]-p1[0],vy=p3[1]-p1[1],vz=p3[2]-p1[2];
			w(o,uy*vz-uz*vy,uz*vx-ux*vz,ux*vy-uy*vx);a.ret(o);});
		q.def("Q3Vector2D_Dot",a->{float[] x=r(a.i(0),2),y=r(a.i(1),2);a.retF(x[0]*y[0]+x[1]*y[1]);});
		q.def("Q3Vector3D_Dot",a->{float[] x=r(a.i(0),3),y=r(a.i(1),3);a.retF(x[0]*y[0]+x[1]*y[1]+x[2]*y[2]);});
		// ---- transforms
		q.def("Q3Vector2D_Transform",a->{float[] v=r(a.i(0),2),m=m3(a.i(1));int o=a.i(2);w(o,v[0]*m[0]+v[1]*m[3],v[0]*m[1]+v[1]*m[4]);a.ret(o);});
		q.def("Q3Vector3D_Transform",a->{float[] v=r(a.i(0),3),m=m4(a.i(1));int o=a.i(2);Q3.transformVector(m,v,0);w(o,v);a.ret(o);});
		q.def("Q3Point2D_Transform",a->point2(a));q.def("Q3Param2D_Transform",a->point2(a));
		q.def("Q3Point3D_Transform",a->{float[] v=r(a.i(0),3),m=m4(a.i(1));int o=a.i(2);point3(m,v);w(o,v);a.ret(o);});
		q.def("Q3RationalPoint4D_Transform",a->{float[] v=r(a.i(0),4),m=m4(a.i(1));int o=a.i(2);w(o,point4(m,v[0],v[1],v[2],v[3]));a.ret(o);});
		q.def("Q3Point3D_To3DTransformArray",a->{int in=a.i(0);float[] m=m4(a.i(1));int out=a.i(2),n=a.i(3),is=a.i(4),os=a.i(5);
			for(int i=0;i<n;i++){float[] v=r(in+i*is,3);point3(m,v);w(out+i*os,v);}a.ok(true);});
		q.def("Q3Point3D_To4DTransformArray",a->{int in=a.i(0);float[] m=m4(a.i(1));int out=a.i(2),n=a.i(3),is=a.i(4),os=a.i(5);
			for(int i=0;i<n;i++){float[] v=r(in+i*is,3);w(out+i*os,point4(m,v[0],v[1],v[2],1));}a.ok(true);});
		q.def("Q3RationalPoint4D_To4DTransformArray",a->{int in=a.i(0);float[] m=m4(a.i(1));int out=a.i(2),n=a.i(3),is=a.i(4),os=a.i(5);
			for(int i=0;i<n;i++){float[] v=r(in+i*is,4);w(out+i*os,point4(m,v[0],v[1],v[2],v[3]));}a.ok(true);});
		q.def("Q3Vector2D_Negate",a->{float[] v=r(a.i(0),2);int o=a.i(1);w(o,-v[0],-v[1]);a.ret(o);});
		q.def("Q3Vector3D_Negate",a->{float[] v=r(a.i(0),3);int o=a.i(1);w(o,-v[0],-v[1],-v[2]);a.ret(o);});
		// ---- polar and spherical
		q.def("Q3Point2D_ToPolar",a->{float[] p=r(a.i(0),2);int o=a.i(1);w(o,sqrt(p[0]*p[0]+p[1]*p[1]),angle(p[0],p[1]));a.ret(o);});
		q.def("Q3PolarPoint_ToPoint2D",a->{float[] p=r(a.i(0),2);int o=a.i(1);w(o,(float)(p[0]*Math.cos(p[1])),(float)(p[0]*Math.sin(p[1])));a.ret(o);});
		q.def("Q3Point3D_ToSpherical",a->{float[] p=r(a.i(0),3);int o=a.i(1);float rho=sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]);
			w(o,rho,angle(p[0],p[1]),rho==0?0:(float)Math.acos(p[2]/rho));a.ret(o);});
		q.def("Q3SphericalPoint_ToPoint3D",a->{float[] p=r(a.i(0),3);int o=a.i(1);double rho=p[0],th=p[1],ph=p[2];
			w(o,(float)(rho*Math.sin(ph)*Math.cos(th)),(float)(rho*Math.sin(ph)*Math.sin(th)),(float)(rho*Math.cos(ph)));a.ret(o);});
		// ---- affine combinations
		q.def("Q3Point2D_AffineComb",a->affine(a,2));q.def("Q3Param2D_AffineComb",a->affine(a,2));q.def("Q3RationalPoint3D_AffineComb",a->affine(a,3));
		q.def("Q3Point3D_AffineComb",a->affine(a,3));q.def("Q3RationalPoint4D_AffineComb",a->affine(a,4));
		// ---- matrices
		q.def("Q3Matrix3x3_Copy",a->{float[] m=m3(a.i(0));int o=a.i(1);w(o,m);a.ret(o);});
		q.def("Q3Matrix4x4_Copy",a->{float[] m=m4(a.i(0));int o=a.i(1);w(o,m);a.ret(o);});
		q.def("Q3Matrix3x3_SetIdentity",a->{int o=a.i(0);w(o,1,0,0,0,1,0,0,0,1);a.ret(o);});
		q.def("Q3Matrix4x4_SetIdentity",a->{int o=a.i(0);w(o,Q3.identity());a.ret(o);});
		q.def("Q3Matrix3x3_Transpose",a->{float[] m=m3(a.i(0));int o=a.i(1);w(o,m[0],m[3],m[6],m[1],m[4],m[7],m[2],m[5],m[8]);a.ret(o);});
		q.def("Q3Matrix4x4_Transpose",a->{float[] m=m4(a.i(0));int o=a.i(1);w(o,Q3.transpose(m));a.ret(o);});
		q.def("Q3Matrix3x3_Invert",a->{float[] m=m3(a.i(0));int o=a.i(1);float[] inv=invert3(m);if(inv==null){q.post(-28411);a.ret(0);return;}w(o,inv);a.ret(o);});
		q.def("Q3Matrix4x4_Invert",a->{float[] m=m4(a.i(0));int o=a.i(1);float[] inv=Q3.invert(m);if(inv==null){q.post(-28411);a.ret(0);return;}w(o,inv);a.ret(o);});
		q.def("Q3Matrix3x3_Adjoint",a->{float[] m=m3(a.i(0));int o=a.i(1);w(o,adjoint3(m));a.ret(o);});
		q.def("Q3Matrix3x3_Multiply",a->{float[] x=m3(a.i(0)),y=m3(a.i(1));int o=a.i(2);float[] r=new float[9];
			for(int i=0;i<3;i++)for(int j=0;j<3;j++){float s=0;for(int k=0;k<3;k++)s+=x[3*i+k]*y[3*k+j];r[3*i+j]=s;}w(o,r);a.ret(o);});
		q.def("Q3Matrix4x4_Multiply",a->{float[] x=m4(a.i(0)),y=m4(a.i(1));int o=a.i(2);w(o,Q3.mul(x,y));a.ret(o);});
		q.def("Q3Matrix3x3_SetTranslate",a->{int o=a.i(0);w(o,1,0,0,0,1,0,a.f(1),a.f(2),1);a.ret(o);});
		q.def("Q3Matrix3x3_SetScale",a->{int o=a.i(0);w(o,a.f(1),0,0,0,a.f(2),0,0,0,1);a.ret(o);});
		q.def("Q3Matrix3x3_SetRotateAboutPoint",a->{int o=a.i(0);float[] p=r(a.i(1),2);float t=a.f(2);float c=(float)Math.cos(t),s=(float)Math.sin(t);
			// translate to the origin, rotate, translate back (row vectors)
			w(o,c,s,0,-s,c,0,p[0]-p[0]*c+p[1]*s,p[1]-p[0]*s-p[1]*c,1);a.ret(o);});
		q.def("Q3Matrix4x4_SetTranslate",a->{int o=a.i(0);w(o,Q3.translate(a.f(1),a.f(2),a.f(3)));a.ret(o);});
		q.def("Q3Matrix4x4_SetScale",a->{int o=a.i(0);w(o,Q3.scale(a.f(1),a.f(2),a.f(3)));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotateAboutPoint",a->{int o=a.i(0);float[] p=r(a.i(1),3);float x=a.f(2),y=a.f(3),z=a.f(4);
			w(o,Q3.mul(Q3.mul(Q3.translate(-p[0],-p[1],-p[2]),rotateXYZ(x,y,z)),Q3.translate(p[0],p[1],p[2])));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotateAboutAxis",a->{int o=a.i(0);float[] p=r(a.i(1),3),v=r(a.i(2),3);float t=a.f(3);
			w(o,Q3.rotateAboutAxis(p[0],p[1],p[2],v[0],v[1],v[2],t));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotate_X",a->{int o=a.i(0);w(o,Q3.rotateX(a.f(1)));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotate_Y",a->{int o=a.i(0);w(o,Q3.rotateY(a.f(1)));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotate_Z",a->{int o=a.i(0);w(o,Q3.rotateZ(a.f(1)));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotate_XYZ",a->{int o=a.i(0);w(o,rotateXYZ(a.f(1),a.f(2),a.f(3)));a.ret(o);});
		q.def("Q3Matrix4x4_SetRotateVectorToVector",a->{int o=a.i(0);float[] v1=r(a.i(1),3),v2=r(a.i(2),3);w(o,vectorToVector(v1,v2));a.ret(o);});
		q.def("Q3Matrix4x4_SetQuaternion",a->{int o=a.i(0);float[] k=r(a.i(1),4);w(o,Q3.quaternionMatrix(k[0],k[1],k[2],k[3]));a.ret(o);});
		q.def("Q3Matrix3x3_Determinant",a->{float[] m=m3(a.i(0));a.retF(det3(m));});
		q.def("Q3Matrix4x4_Determinant",a->{float[] m=m4(a.i(0));a.retF(det4(m));});
		// ---- quaternions (w x y z)
		q.def("Q3Quaternion_Set",a->{int o=a.i(0);w(o,a.f(1),a.f(2),a.f(3),a.f(4));a.ret(o);});
		q.def("Q3Quaternion_SetIdentity",a->{int o=a.i(0);w(o,1,0,0,0);a.ret(o);});
		q.def("Q3Quaternion_Copy",a->{float[] k=r(a.i(0),4);int o=a.i(1);w(o,k);a.ret(o);});
		q.def("Q3Quaternion_IsIdentity",a->{float[] k=r(a.i(0),4);a.ret(k[0]==1&&k[1]==0&&k[2]==0&&k[3]==0?1:0);});
		q.def("Q3Quaternion_Invert",a->{float[] k=r(a.i(0),4);int o=a.i(1);w(o,k[0],-k[1],-k[2],-k[3]);a.ret(o);});
		q.def("Q3Quaternion_Normalize",a->{float[] k=r(a.i(0),4);int o=a.i(1);float l=sqrt(k[0]*k[0]+k[1]*k[1]+k[2]*k[2]+k[3]*k[3]);float f=l==0?0:1/l;w(o,k[0]*f,k[1]*f,k[2]*f,k[3]*f);a.ret(o);});
		q.def("Q3Quaternion_Dot",a->{float[] x=r(a.i(0),4),y=r(a.i(1),4);a.retF(x[0]*y[0]+x[1]*y[1]+x[2]*y[2]+x[3]*y[3]);});
		q.def("Q3Quaternion_Multiply",a->{float[] x=r(a.i(0),4),y=r(a.i(1),4);int o=a.i(2);w(o,qmul(y,x));a.ret(o);});
		q.def("Q3Quaternion_SetRotateAboutAxis",a->{int o=a.i(0);float[] v=r(a.i(1),3);float t=a.f(2);w(o,axisQuat(v[0],v[1],v[2],t));a.ret(o);});
		q.def("Q3Quaternion_SetRotate_XYZ",a->{int o=a.i(0);float x=a.f(1),y=a.f(2),z=a.f(3);
			w(o,qmul(axisQuat(0,0,1,z),qmul(axisQuat(0,1,0,y),axisQuat(1,0,0,x))));a.ret(o);});
		q.def("Q3Quaternion_SetRotate_X",a->{int o=a.i(0);w(o,axisQuat(1,0,0,a.f(1)));a.ret(o);});
		q.def("Q3Quaternion_SetRotate_Y",a->{int o=a.i(0);w(o,axisQuat(0,1,0,a.f(1)));a.ret(o);});
		q.def("Q3Quaternion_SetRotate_Z",a->{int o=a.i(0);w(o,axisQuat(0,0,1,a.f(1)));a.ret(o);});
		q.def("Q3Quaternion_SetMatrix",a->{int o=a.i(0);float[] m=m4(a.i(1));w(o,matrixQuat(m));a.ret(o);});
		q.def("Q3Quaternion_SetRotateVectorToVector",a->{int o=a.i(0);float[] v1=r(a.i(1),3),v2=r(a.i(2),3);w(o,matrixQuat(vectorToVector(v1,v2)));a.ret(o);});
		q.def("Q3Quaternion_MatchReflection",a->{float[] x=r(a.i(0),4),y=r(a.i(1),4);int o=a.i(2);
			float d=x[0]*y[0]+x[1]*y[1]+x[2]*y[2]+x[3]*y[3];if(d<0)w(o,-x[0],-x[1],-x[2],-x[3]);else w(o,x);a.ret(o);});
		q.def("Q3Quaternion_InterpolateFast",a->{float[] x=r(a.i(0),4),y=r(a.i(1),4);float t=a.f(2);int o=a.i(3);
			float[] k=new float[4];for(int i=0;i<4;i++)k[i]=x[i]+(y[i]-x[i])*t; // no shortest-path flip in QuickDraw 3D
			float l=sqrt(k[0]*k[0]+k[1]*k[1]+k[2]*k[2]+k[3]*k[3]);if(l>0)for(int i=0;i<4;i++)k[i]/=l;w(o,k);a.ret(o);});
		q.def("Q3Quaternion_InterpolateLinear",a->{float[] x=r(a.i(0),4),y=r(a.i(1),4);float t=a.f(2);int o=a.i(3);w(o,slerp(x,y,t));a.ret(o);});
		q.def("Q3Vector3D_TransformQuaternion",a->{float[] v=r(a.i(0),3),k=r(a.i(1),4);int o=a.i(2);float[] m=Q3.quaternionMatrix(k[0],k[1],k[2],k[3]);Q3.transformVector(m,v,0);w(o,v);a.ret(o);});
		q.def("Q3Point3D_TransformQuaternion",a->{float[] v=r(a.i(0),3),k=r(a.i(1),4);int o=a.i(2);float[] m=Q3.quaternionMatrix(k[0],k[1],k[2],k[3]);Q3.transformVector(m,v,0);w(o,v);a.ret(o);});
		// ---- bounds (TQ3BoundingBox: min 3, max 3, isEmpty; TQ3BoundingSphere: origin 3, radius, isEmpty)
		q.def("Q3BoundingBox_Copy",a->{int s=a.i(0),d=a.i(1);memory.copy(s,d,28);a.ret(d);});
		q.def("Q3BoundingBox_Union",a->{float[] x=r(a.i(0),6),y=r(a.i(1),6);boolean xe=memory.readLong(a.i(0)+24)!=0,ye=memory.readLong(a.i(1)+24)!=0;int o=a.i(2);
			if(xe&&ye){w(o,0,0,0,0,0,0);memory.writeLong(o+24,1);}else if(xe){w(o,y);memory.writeLong(o+24,0);}else if(ye){w(o,x);memory.writeLong(o+24,0);}
			else{w(o,Math.min(x[0],y[0]),Math.min(x[1],y[1]),Math.min(x[2],y[2]),Math.max(x[3],y[3]),Math.max(x[4],y[4]),Math.max(x[5],y[5]));memory.writeLong(o+24,0);}a.ret(o);});
		q.def("Q3BoundingBox_Set",a->{int o=a.i(0);float[] mn=r(a.i(1),3),mx=r(a.i(2),3);w(o,mn[0],mn[1],mn[2],mx[0],mx[1],mx[2]);memory.writeLong(o+24,a.i(3));a.ret(o);});
		q.def("Q3BoundingBox_UnionPoint3D",a->{float[] b=r(a.i(0),6);boolean e=memory.readLong(a.i(0)+24)!=0;float[] p=r(a.i(1),3);int o=a.i(2);unionPoint(o,b,e,p);a.ret(o);});
		q.def("Q3BoundingBox_UnionRationalPoint4D",a->{float[] b=r(a.i(0),6);boolean e=memory.readLong(a.i(0)+24)!=0;float[] p=r(a.i(1),4);int o=a.i(2);
			float iw=p[3]==0?1:1/p[3];unionPoint(o,b,e,new float[]{p[0]*iw,p[1]*iw,p[2]*iw});a.ret(o);});
		q.def("Q3BoundingBox_SetFromPoints3D",a->{int o=a.i(0),pts=a.i(1),n=a.i(2),st=a.i(3);boxFrom(o,pts,n,st,false);a.ret(o);});
		q.def("Q3BoundingBox_SetFromRationalPoints4D",a->{int o=a.i(0),pts=a.i(1),n=a.i(2),st=a.i(3);boxFrom(o,pts,n,st,true);a.ret(o);});
		q.def("Q3BoundingSphere_Copy",a->{int s=a.i(0),d=a.i(1);memory.copy(s,d,20);a.ret(d);});
		q.def("Q3BoundingSphere_Union",a->{float[] x=r(a.i(0),4),y=r(a.i(1),4);boolean xe=memory.readLong(a.i(0)+16)!=0,ye=memory.readLong(a.i(1)+16)!=0;int o=a.i(2);
			if(xe&&ye){w(o,0,0,0,0);memory.writeLong(o+16,1);}else if(xe){w(o,y);memory.writeLong(o+16,0);}else if(ye){w(o,x);memory.writeLong(o+16,0);}
			else{w(o,sphereUnion(x,y));memory.writeLong(o+16,0);}a.ret(o);});
		q.def("Q3BoundingSphere_Set",a->{int o=a.i(0);float[] p=r(a.i(1),3);w(o,p[0],p[1],p[2],a.f(2));memory.writeLong(o+16,a.i(3));a.ret(o);});
		q.def("Q3BoundingSphere_UnionPoint3D",a->{float[] s=r(a.i(0),4);boolean e=memory.readLong(a.i(0)+16)!=0;float[] p=r(a.i(1),3);int o=a.i(2);
			if(e)w(o,p[0],p[1],p[2],0);else w(o,sphereUnion(s,new float[]{p[0],p[1],p[2],0}));memory.writeLong(o+16,0);a.ret(o);});
		q.def("Q3BoundingSphere_UnionRationalPoint4D",a->{float[] s=r(a.i(0),4);boolean e=memory.readLong(a.i(0)+16)!=0;float[] p=r(a.i(1),4);int o=a.i(2);float iw=p[3]==0?1:1/p[3];
			float[] pt={p[0]*iw,p[1]*iw,p[2]*iw,0};if(e)w(o,pt);else w(o,sphereUnion(s,pt));memory.writeLong(o+16,0);a.ret(o);});
		q.def("Q3BoundingSphere_SetFromPoints3D",a->{int o=a.i(0),pts=a.i(1),n=a.i(2),st=a.i(3);sphereFrom(o,pts,n,st,false);a.ret(o);});
		q.def("Q3BoundingSphere_SetFromRationalPoints4D",a->{int o=a.i(0),pts=a.i(1),n=a.i(2),st=a.i(3);sphereFrom(o,pts,n,st,true);a.ret(o);});
	}

	private void sub(PpcQuickDraw3D.A a,int n) throws M68kCpuException {float[] x=r(a.i(0),n),y=r(a.i(1),n);int o=a.i(2);float[] d=new float[n];for(int i=0;i<n;i++)d[i]=x[i]-y[i];w(o,d);a.ret(o);}
	private void add(PpcQuickDraw3D.A a,int n,int sign) throws M68kCpuException {float[] x=r(a.i(0),n),y=r(a.i(1),n);int o=a.i(2);float[] d=new float[n];for(int i=0;i<n;i++)d[i]=x[i]+sign*y[i];w(o,d);a.ret(o);}
	/** Distance between two points of {@code size} floats, the last divided out when rational ({@code dims} coordinates). */
	private float dist(PpcQuickDraw3D.A a,int size,int dims,boolean squared) throws M68kCpuException {
		float[] x=r(a.i(0),size),y=r(a.i(1),size);
		// rational points are measured as they are, w included (as QuickDraw 3D does)
		double s=0;for(int i=0;i<size;i++){double d=x[i]-y[i];s+=d*d;}
		return squared?(float)s:(float)Math.sqrt(s);
	}
	private void rratio(PpcQuickDraw3D.A a,int n) throws M68kCpuException {
		float[] x=r(a.i(0),n),y=r(a.i(1),n);float r1=a.f(2),r2=a.f(3);int o=a.i(4);
		float t=r1+r2==0?0:r2/(r1+r2); // QuickDraw 3D weighs p2 by r2float[] d=new float[n];for(int i=0;i<n;i++)d[i]=x[i]+(y[i]-x[i])*t;w(o,d);a.ret(o);
	}
	private void affine(PpcQuickDraw3D.A a,int n) throws M68kCpuException {
		int pts=a.i(0),wts=a.i(1),count=a.i(2),o=a.i(3);float[] d=new float[n];
		for(int i=0;i<count;i++){float wt=rf(wts+4*i);float[] p=r(pts+4*n*i,n);for(int k=0;k<n;k++)d[k]+=p[k]*wt;}
		w(o,d);a.ret(o);
	}
	private void point2(PpcQuickDraw3D.A a) throws M68kCpuException {
		float[] p=r(a.i(0),2),m=m3(a.i(1));int o=a.i(2);
		float x=p[0]*m[0]+p[1]*m[3]+m[6],y=p[0]*m[1]+p[1]*m[4]+m[7],ww=p[0]*m[2]+p[1]*m[5]+m[8];
		if(ww!=0&&ww!=1){x/=ww;y/=ww;}w(o,x,y);a.ret(o);
	}
	private static void point3(float[] m,float[] v){Q3.transformPoint(m,v,0);}
	private static float[] point4(float[] m,float x,float y,float z,float ww){
		return new float[]{x*m[0]+y*m[4]+z*m[8]+ww*m[12],x*m[1]+y*m[5]+z*m[9]+ww*m[13],x*m[2]+y*m[6]+z*m[10]+ww*m[14],x*m[3]+y*m[7]+z*m[11]+ww*m[15]};
	}
	/** Rotation about x, then y, then z. */
	static float[] rotateXYZ(float x,float y,float z){return Q3.mul(Q3.mul(Q3.rotateX(x),Q3.rotateY(y)),Q3.rotateZ(z));}
	/** The rotation taking the direction of v1 to that of v2. */
	static float[] vectorToVector(float[] v1,float[] v2){
		float l1=sqrt(v1[0]*v1[0]+v1[1]*v1[1]+v1[2]*v1[2]),l2=sqrt(v2[0]*v2[0]+v2[1]*v2[1]+v2[2]*v2[2]);
		if(l1==0||l2==0)return Q3.identity();
		float ax=v1[0]/l1,ay=v1[1]/l1,az=v1[2]/l1,bx=v2[0]/l2,by=v2[1]/l2,bz=v2[2]/l2;
		float cx=ay*bz-az*by,cy=az*bx-ax*bz,cz=ax*by-ay*bx;float c=ax*bx+ay*by+az*bz;float s=sqrt(cx*cx+cy*cy+cz*cz);
		if(s<1e-7f){
			if(c>0)return Q3.identity();
			// opposite: half a turn about any axis perpendicular to v1
			float px=Math.abs(ax)<0.9f?1:0,py=px==1?0:1;float nx=ay*0-az*py,ny=az*px-ax*0,nz=ax*py-ay*px;float nl=sqrt(nx*nx+ny*ny+nz*nz);
			return Q3.rotateAboutAxis(0,0,0,nx/nl,ny/nl,nz/nl,(float)Math.PI);
		}
		return Q3.rotateAboutAxis(0,0,0,cx/s,cy/s,cz/s,(float)Math.atan2(s,c));
	}
	private static float det3(float[] m){return m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);}
	private static float[] adjoint3(float[] m){
		return new float[]{m[4]*m[8]-m[5]*m[7],m[2]*m[7]-m[1]*m[8],m[1]*m[5]-m[2]*m[4],
			m[5]*m[6]-m[3]*m[8],m[0]*m[8]-m[2]*m[6],m[2]*m[3]-m[0]*m[5],
			m[3]*m[7]-m[4]*m[6],m[1]*m[6]-m[0]*m[7],m[0]*m[4]-m[1]*m[3]};
	}
	private static float[] invert3(float[] m){float d=det3(m);if(d==0)return null;float[] a=adjoint3(m);for(int i=0;i<9;i++)a[i]/=d;return a;}
	private static float det4(float[] m){
		double d=0;
		for(int c=0;c<4;c++){
			float[] s=new float[9];int k=0;
			for(int i=1;i<4;i++)for(int j=0;j<4;j++)if(j!=c)s[k++]=m[4*i+j];
			d+=((c&1)==0?1:-1)*m[c]*det3(s);
		}
		return (float)d;
	}
	/** Quaternion product x*y (w x y z). */
	private static float[] qmul(float[] x,float[] y){
		return new float[]{x[0]*y[0]-x[1]*y[1]-x[2]*y[2]-x[3]*y[3],
			x[0]*y[1]+x[1]*y[0]+x[2]*y[3]-x[3]*y[2],
			x[0]*y[2]-x[1]*y[3]+x[2]*y[0]+x[3]*y[1],
			x[0]*y[3]+x[1]*y[2]-x[2]*y[1]+x[3]*y[0]};
	}
	private static float[] axisQuat(float x,float y,float z,float angle){
		float l=sqrt(x*x+y*y+z*z);if(l==0)l=1;float s=(float)Math.sin(angle/2)/l;
		return new float[]{(float)Math.cos(angle/2),x*s,y*s,z*s};
	}
	/** The quaternion of a rotation matrix (row vectors). */
	private static float[] matrixQuat(float[] m){
		float tr=m[0]+m[5]+m[10];
		if(tr>0){float s=sqrt(tr+1);float w=s/2;s=0.5f/s;return new float[]{w,(m[6]-m[9])*s,(m[8]-m[2])*s,(m[1]-m[4])*s};}
		int i=0;if(m[5]>m[0])i=1;if(m[10]>m[5*i])i=2;
		int j=(i+1)%3,k=(i+2)%3;
		float s=sqrt(m[5*i]-(m[5*j]+m[5*k])+1);float[] v=new float[3];v[i]=s/2;if(s!=0)s=0.5f/s;
		float w=(m[4*j+k]-m[4*k+j])*s;v[j]=(m[4*i+j]+m[4*j+i])*s;v[k]=(m[4*i+k]+m[4*k+i])*s;
		return w<0?new float[]{-w,-v[0],-v[1],-v[2]}:new float[]{w,v[0],v[1],v[2]};
	}
	private static float[] slerp(float[] x,float[] y,float t){
		double d=x[0]*y[0]+x[1]*y[1]+x[2]*y[2]+x[3]*y[3];float s=1;d=Math.max(-1,Math.min(1,d));
		double a,b;
		if(Math.abs(d)>0.9999){a=1-t;b=t;}else{double th=Math.acos(d),st=Math.sin(th);a=Math.sin((1-t)*th)/st;b=Math.sin(t*th)/st;}
		float[] r=new float[4];for(int i=0;i<4;i++)r[i]=(float)(a*x[i]+b*s*y[i]);return r;
	}
	private void unionPoint(int o,float[] b,boolean empty,float[] p) throws M68kCpuException {
		if(empty)w(o,p[0],p[1],p[2],p[0],p[1],p[2]);
		else w(o,Math.min(b[0],p[0]),Math.min(b[1],p[1]),Math.min(b[2],p[2]),Math.max(b[3],p[0]),Math.max(b[4],p[1]),Math.max(b[5],p[2]));
		memory.writeLong(o+24,0);
	}
	private void boxFrom(int o,int pts,int n,int stride,boolean rational) throws M68kCpuException {
		if(n<=0){w(o,0,0,0,0,0,0);memory.writeLong(o+24,1);return;}
		float[] mn={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},mx={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
		for(int i=0;i<n;i++){float[] p=r(pts+i*stride,rational?4:3);if(rational){float iw=p[3]==0?1:1/p[3];p[0]*=iw;p[1]*=iw;p[2]*=iw;}
			for(int k=0;k<3;k++){mn[k]=Math.min(mn[k],p[k]);mx[k]=Math.max(mx[k],p[k]);}}
		w(o,mn[0],mn[1],mn[2],mx[0],mx[1],mx[2]);memory.writeLong(o+24,0);
	}
	private void sphereFrom(int o,int pts,int n,int stride,boolean rational) throws M68kCpuException {
		if(n<=0){w(o,0,0,0,0);memory.writeLong(o+16,1);return;}
		float[] all=new float[3*n];
		for(int i=0;i<n;i++){float[] p=r(pts+i*stride,rational?4:3);if(rational){float iw=p[3]==0?1:1/p[3];p[0]*=iw;p[1]*=iw;p[2]*=iw;}System.arraycopy(p,0,all,3*i,3);}
		w(o,Q3View.sphereOf(all,n,3));memory.writeLong(o+16,0);
	}
	/** The smallest sphere around two spheres (origin 3, radius). */
	private static float[] sphereUnion(float[] x,float[] y){
		float dx=y[0]-x[0],dy=y[1]-x[1],dz=y[2]-x[2];float d=sqrt(dx*dx+dy*dy+dz*dz);
		if(d+y[3]<=x[3])return x.clone();if(d+x[3]<=y[3])return y.clone();
		float r=(d+x[3]+y[3])/2;float t=d==0?0:(r-x[3])/d;
		return new float[]{x[0]+dx*t,x[1]+dy*t,x[2]+dz*t,r};
	}
}

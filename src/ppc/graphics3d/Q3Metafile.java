import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3DMF, QuickDraw 3D's metafile (3D Metafile Reference, version 1.6): reading binary (big- and little-endian) and text
 * files into {@link Q3} objects, and writing objects back as binary or text. The layout of every object was checked
 * against files QuickDraw 3D 1.6 itself wrote.
 * <p>
 * A file is first cut into nodes - an object with its data and sub-objects (the rest of a Container), a group with its
 * members (BeginGroup ... EndGroup), references to the table of contents - then the nodes become objects. Both forms
 * share the decoders: a {@link Cursor} reads the fields of an object from bytes or from text tokens alike.
 */
final class Q3Metafile {
	private Q3Metafile(){}

	// ================================================================== nodes and cursors

	/** An object of the file: type, data, sub-objects (a container's rest), members (a group's), where it starts (for the TOC). */
	static final class Node {
		int type;Cursor data;final List<Node> subs=new ArrayList<Node>();List<Node> members;
		long location;String label;
		/** A reference's id, or -1. */
		int refId=-1;
		Q3.Obj decoded;boolean decoding;
		Node(int type){this.type=type;}
		boolean is(String t){return type==Q3.fc(t);}
	}

	/** The fields of an object. */
	static abstract class Cursor {
		abstract int u32();abstract int u16();abstract int u8();abstract float f32();
		abstract boolean atEnd();
		/** An enumerated value: a number, or in text one of the names (by position). */
		abstract int enumeration(String... names);
		/** A mask: a number, or in text names joined by '|' (name i is bit i, "None" is 0). */
		abstract int flags(String... names);
		abstract byte[] bytes(int n);
		abstract String string();
		/** An index into {@code count} items: 1, 2 or 4 bytes in binary TriMeshes. */
		abstract int index(int count);
		/** A boolean word (True/False in text). */
		boolean bool(){return enumeration("False","True")!=0;}
		float[] floats(int n){float[] f=new float[Math.max(0,n)];for(int i=0;i<f.length;i++)f[i]=f32();return f;}
		int[] ints(int n){int[] f=new int[Math.max(0,n)];for(int i=0;i<f.length;i++)f[i]=u32();return f;}
	}
	static final class BinaryCursor extends Cursor {
		final byte[] b;int p;final int end;final boolean little;
		BinaryCursor(byte[] b,int start,int end,boolean little){this.b=b;p=start;this.end=Math.min(end,b.length);this.little=little;}
		private void need(int n){if(p+n>end)throw new IllegalStateException("3DMF object data ends early");}
		@Override int u32(){need(4);int v=little?(b[p]&0xff)|(b[p+1]&0xff)<<8|(b[p+2]&0xff)<<16|(b[p+3]&0xff)<<24:(b[p]&0xff)<<24|(b[p+1]&0xff)<<16|(b[p+2]&0xff)<<8|b[p+3]&0xff;p+=4;return v;}
		@Override int u16(){need(2);int v=little?(b[p]&0xff)|(b[p+1]&0xff)<<8:(b[p]&0xff)<<8|b[p+1]&0xff;p+=2;return v;}
		@Override int u8(){need(1);return b[p++]&0xff;}
		@Override float f32(){return Float.intBitsToFloat(u32());}
		@Override boolean atEnd(){return p>=end;}
		@Override int enumeration(String... names){return u32();}
		@Override int flags(String... names){return u32();}
		@Override byte[] bytes(int n){need(n);byte[] r=java.util.Arrays.copyOfRange(b,p,p+n);p+=n;return r;}
		@Override String string(){int s=p;while(p<end&&b[p]!=0)p++;String r=new String(b,s,p-s,PpcQuickDraw3D.MAC_ROMAN);p++;while(((p-s)&3)!=0&&p<end)p++;return r;}
		@Override int index(int count){return count<=0xff?u8():count<=0xffff?u16():u32();}
		int remaining(){return end-p;}
		void skip(int n){p=Math.min(end,p+n);}
	}
	static final class TextCursor extends Cursor {
		final List<String> t;int i;
		TextCursor(List<String> tokens){t=tokens;}
		private String next(){if(i>=t.size())throw new IllegalStateException("3DMF text object ends early");return t.get(i++);}
		private long number(String s){
			try{if(s.startsWith("0x")||s.startsWith("0X"))return Long.parseLong(s.substring(2),16);return (long)Double.parseDouble(s);}
			catch(NumberFormatException e){return 0;}
		}
		@Override int u32(){String s=next();if(s.equalsIgnoreCase("True"))return 1;if(s.equalsIgnoreCase("False"))return 0;return (int)number(s);}
		@Override int u16(){return u32();}@Override int u8(){return u32();}
		@Override float f32(){String s=next();try{return Float.parseFloat(s);}catch(NumberFormatException e){if(s.equalsIgnoreCase("True"))return 1;return 0;}}
		@Override boolean atEnd(){return i>=t.size();}
		@Override int enumeration(String... names){String s=next();for(int k=0;k<names.length;k++)if(names[k].equalsIgnoreCase(s))return k;return (int)number(s);}
		@Override int flags(String... names){
			int v=0;
			while(i<t.size()){
				String s=t.get(i);
				boolean known=false;
				for(int k=0;k<names.length;k++)if(names[k].equalsIgnoreCase(s)){v|=1<<k;known=true;}
				if(!known&&!s.equalsIgnoreCase("None")&&!Character.isDigit(s.charAt(0)))break;
				if(!known&&Character.isDigit(s.charAt(0)))v|=(int)number(s);
				i++;
				if(i<t.size()&&t.get(i).equals("|")){i++;continue;}
				break;
			}
			return v;
		}
		@Override byte[] bytes(int n){
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			while(out.size()<n&&i<t.size()){String s=t.get(i);if(!s.startsWith("0x")&&!s.startsWith("0X"))break;i++;
				for(int k=2;k+1<s.length();k+=2)out.write(Integer.parseInt(s.substring(k,k+2),16));}
			byte[] b=out.toByteArray();return b.length>=n?java.util.Arrays.copyOf(b,n):java.util.Arrays.copyOf(b,n);
		}
		@Override String string(){String s=next();if(s.startsWith("\""))s=s.substring(1,s.length()-(s.endsWith("\"")&&s.length()>1?1:0));return s;}
		@Override int index(int count){return u32();}
	}

	// ================================================================== reading

	/** A file being read: its nodes in order, and what references resolve to. */
	static final class Reader {
		final List<Node> top=new ArrayList<Node>();int next;
		final Map<Long,Node> byLocation=new HashMap<Long,Node>();
		final Map<String,Node> byLabel=new HashMap<String,Node>();
		/** TOC: reference id to location (binary) or label (text). */
		final Map<Integer,Long> refLocation=new HashMap<Integer,Long>();final Map<Integer,String> refLabel=new HashMap<Integer,String>();
		int version;boolean text;int mode;
		/** Custom element and attribute sizes (from the front end), for keeping unknown attributes. */
		ElementSizes sizes;
		/** Hook for types this reader does not know (kept as unknown objects). */
		/** Whether an object is left (a trailing table of contents or type declaration is not one). */
		boolean hasMore(){skipBookkeeping();return next<top.size();}
		Node peek(){skipBookkeeping();return next<top.size()?top.get(next):null;}
		private void skipBookkeeping(){while(next<top.size()&&(top.get(next).is("toc ")||top.get(next).is("type")||top.get(next).is("endg")))next++;}
		/** The next object at the top of the file (a group with its members); null at the end. */
		Q3.Obj readNext(){
			while(next<top.size()){
				Node n=top.get(next++);
				if(n.is("toc ")||n.is("type"))continue;
				Q3.Obj o=decode(n);
				if(o!=null)return o;
			}
			return null;
		}
		/** The object a node stands for (references resolved; shared ones decoded once and referenced again). */
		Q3.Obj decode(Node n){
			if(n.refId>=0){
				Node target=resolve(n.refId);
				if(target==null)return null;
				return decode(target);
			}
			if(n.decoded!=null){return Q3.ref(n.decoded);}
			if(n.decoding)return null;
			n.decoding=true;
			Q3.Obj o;
			try{o=Q3Metafile.decode(this,n);}
			catch(RuntimeException e){System.err.println("[qd3d] 3DMF "+Q3.fcName(n.type)+": "+e);o=null;}
			finally{n.decoding=false;}
			if(o!=null&&o.shared()){n.decoded=o;return Q3.ref(o);} // the node keeps a reference while the file is open
			return o;
		}
		Node resolve(int refId){
			if(text){String l=refLabel.get(refId);return l==null?null:byLabel.get(l);}
			Long loc=refLocation.get(refId);return loc==null?null:byLocation.get(loc);
		}
		/** Lets go of the shared objects the nodes kept. */
		void close(){for(Node n:byLocation.values())if(n.decoded!=null){Q3.release(n.decoded);n.decoded=null;}for(Node n:byLabel.values())if(n.decoded!=null){Q3.release(n.decoded);n.decoded=null;}
			releaseAll(top);}
		private void releaseAll(List<Node> l){for(Node n:l){if(n.decoded!=null){Q3.release(n.decoded);n.decoded=null;}releaseAll(n.subs);if(n.members!=null)releaseAll(n.members);}}
	}
	interface ElementSizes { int size(int type); }

	/** Parses a whole file; null when it is not 3DMF. */
	static Reader read(byte[] data,ElementSizes sizes){
		Reader r=new Reader();r.sizes=sizes;
		if(data.length>=24&&(data[0]=='3'&&data[1]=='D'&&data[2]=='M'&&data[3]=='F'||data[0]=='F'&&data[1]=='M'&&data[2]=='D'&&data[3]=='3')){
			boolean little=data[0]=='F';
			BinaryCursor h=new BinaryCursor(data,4,data.length,little);
			int size=h.u32();int major=h.u16(),minor=h.u16();r.version=major<<16|minor;r.mode=h.u32();
			int start=8+size;
			List<Node> flat=new ArrayList<Node>();
			binaryObjects(r,data,start,data.length,little,flat);
			r.top.addAll(groups(flat));
			for(Node n:r.top)if(n.is("toc "))toc(r,n);
			return r;
		}
		String s=new String(data,StandardCharsets.ISO_8859_1);
		if(s.startsWith("3DMetafile")){r.text=true;textFile(r,s);return r;}
		return null;
	}

	/** Objects of a binary sequence (each type, size, data); containers become a node with sub-objects. */
	private static void binaryObjects(Reader r,byte[] d,int from,int to,boolean little,List<Node> out){
		int p=from;
		while(p+8<=to){
			BinaryCursor c=new BinaryCursor(d,p,to,little);
			int type=c.u32(),size=c.u32();
			int dataStart=p+8,end=(int)Math.min((long)dataStart+size,to);
			if(size<0)break;
			if(type==Q3.fc("cntr")){
				List<Node> inner=new ArrayList<Node>();
				binaryObjects(r,d,dataStart,end,little,inner);
				if(!inner.isEmpty()){
					Node head=inner.get(0);head.location=p;
					List<Node> rest=groups(inner.subList(1,inner.size()));
					head.subs.addAll(rest);
					r.byLocation.put((long)p,head);
					out.add(head);
				}
			}else if(type==Q3.fc("bgng")){
				List<Node> inner=new ArrayList<Node>();
				binaryObjects(r,d,dataStart,end,little,inner);
				Node g=new Node(type);g.location=p;
				if(!inner.isEmpty()){Node head=inner.get(0);g.subs.add(head);g.subs.addAll(inner.subList(1,inner.size()));}
				r.byLocation.put((long)p,g);
				out.add(g);
			}else{
				Node n=new Node(type);n.location=p;n.data=new BinaryCursor(d,dataStart,end,little);
				if(type==Q3.fc("rfrn")){n.refId=((BinaryCursor)n.data).u32();}
				r.byLocation.put((long)p,n);
				out.add(n);
			}
			p=dataStart+size;
		}
	}
	/** Gathers BeginGroup ... EndGroup runs of a sequence into group nodes with members. */
	private static List<Node> groups(List<Node> flat){
		List<Node> out=new ArrayList<Node>();
		java.util.ArrayDeque<Node> open=new java.util.ArrayDeque<Node>();
		for(Node n:flat){
			if(n.is("endg")){if(!open.isEmpty())open.pop();continue;}
			if(open.isEmpty())out.add(n);else open.peek().members.add(n);
			if(n.is("bgng")){n.members=new ArrayList<Node>();open.push(n);}
		}
		return out;
	}
	private static void toc(Reader r,Node n){
		Cursor c=n.data;
		if(r.text)return;
		c.u32();c.u32(); // next TOC (Uns64)
		c.u32();c.u32(); // refSeed, typeSeed
		int entryType=c.u32();c.u32();int count=c.u32();
		for(int i=0;i<count&&!c.atEnd();i++){int id=c.u32();long hi=c.u32()&0xffffffffL,lo=c.u32()&0xffffffffL;if(entryType==1)c.u32();r.refLocation.put(id,hi<<32|lo);}
	}

	// ---- text

	/** Tokens of a text file: words, numbers, strings, hex data, labels (word:), label references (word>), parentheses and '|'. */
	private static List<String> tokens(String s){
		List<String> t=new ArrayList<String>();int n=s.length(),i=0;
		while(i<n){
			char c=s.charAt(i);
			if(c=='#'){while(i<n&&s.charAt(i)!='\n'&&s.charAt(i)!='\r')i++;continue;}
			if(Character.isWhitespace(c)){i++;continue;}
			if(c=='('||c==')'||c=='|'){t.add(String.valueOf(c));i++;continue;}
			if(c=='"'){int j=i+1;StringBuilder sb=new StringBuilder("\"");
				while(j<n&&s.charAt(j)!='"'){char x=s.charAt(j);if(x=='\\'&&j+1<n){j++;char e=s.charAt(j);sb.append(e=='n'?'\n':e=='t'?'\t':e=='r'?'\r':e);}else sb.append(x);j++;}
				sb.append('"');t.add(sb.toString());i=j+1;continue;}
			int j=i;while(j<n&&!Character.isWhitespace(s.charAt(j))&&"()|#\"".indexOf(s.charAt(j))<0)j++;
			t.add(s.substring(i,j));i=j;
		}
		return t;
	}
	/** Text objects: [label:] Name ( fields and sub-objects ). */
	private static void textFile(Reader r,String s){
		List<String> t=tokens(s);
		int[] at={0};
		// the header: 3DMetafile ( major minor mode toc> )
		if(at[0]<t.size()&&t.get(at[0]).equals("3DMetafile")){int close=matching(t,at[0]+1);
			try{r.version=Integer.parseInt(t.get(at[0]+2))<<16|Integer.parseInt(t.get(at[0]+3));}catch(RuntimeException e){}
			at[0]=close+1;}
		List<Node> flat=new ArrayList<Node>();
		while(at[0]<t.size()){Node n=textObject(r,t,at);if(n!=null)flat.add(n);}
		r.top.addAll(groups(flat));
	}
	private static int matching(List<String> t,int open){
		int depth=0;
		for(int i=open;i<t.size();i++){String s=t.get(i);if(s.equals("("))depth++;else if(s.equals(")")){depth--;if(depth==0)return i;}}
		return t.size()-1;
	}
	private static Node textObject(Reader r,List<String> t,int[] at){
		String label=null;
		String name=t.get(at[0]);
		if(name.endsWith(":")){label=name.substring(0,name.length()-1);at[0]++;if(at[0]>=t.size())return null;name=t.get(at[0]);}
		if(name.equals(")")||name.equals("(")||name.equals("|")){at[0]++;return null;}
		at[0]++;
		if(at[0]>=t.size()||!t.get(at[0]).equals("(")){return null;}
		int close=matching(t,at[0]);
		int body=at[0]+1;at[0]=close+1;
		if(name.equals("Container")||name.equals("BeginGroup")){
			int[] p={body};List<Node> inner=new ArrayList<Node>();
			while(p[0]<close){Node n=textObject(r,t.subList(0,close),p);if(n!=null)inner.add(n);}
			if(inner.isEmpty())return null;
			Node head=inner.get(0);
			if(name.equals("BeginGroup")){Node g=new Node(Q3.fc("bgng"));g.subs.addAll(inner);g.label=label;if(label!=null)r.byLabel.put(label,g);return g;}
			head.subs.addAll(groups(inner.subList(1,inner.size())));
			if(label!=null){head.label=label;r.byLabel.put(label,head);}
			return head;
		}
		if(name.equals("EndGroup"))return new Node(Q3.fc("endg"));
		List<String> fields=new ArrayList<String>(t.subList(body,close));
		// nested objects inside the parentheses (a TriMesh's attribute arrays are written there by some writers)
		Integer type=typeOfName(name);
		Node n=new Node(type==null?Q3.fc("uktx"):type);
		n.label=label;if(label!=null)r.byLabel.put(label,n);
		if(name.equals("Reference")){n.refId=fields.isEmpty()?-1:(int)Double.parseDouble(fields.get(0));return n;}
		if(name.equals("TableOfContents")){
			// next-TOC> seed typeSeed entryType entrySize count { id label> [type] }
			int k=0;if(k<fields.size()&&fields.get(k).endsWith(">"))k++;
			k+=2;int entryType=k<fields.size()?(int)Double.parseDouble(fields.get(k)):0;k+=2;int count=k<fields.size()?(int)Double.parseDouble(fields.get(k)):0;k++;
			for(int e=0;e<count&&k+1<fields.size();e++){int id=(int)Double.parseDouble(fields.get(k));String l=fields.get(k+1);if(l.endsWith(">"))l=l.substring(0,l.length()-1);r.refLabel.put(id,l);k+=2;if(entryType==1)k++;}
			n.type=Q3.fc("toc ");return n;
		}
		n.data=new TextCursor(fields);
		if(type==null)n.label=label;
		return n;
	}

	/** The type of a text object name (class names, and the names of sub-objects that have no class of their own). */
	static Integer typeOfName(String name){
		Integer t=TEXT_TYPES.get(name);if(t!=null)return t;
		return Q3.TYPES_BY_NAME.get(name);
	}
	private static final Map<String,Integer> TEXT_TYPES=new HashMap<String,Integer>();
	private static final Map<Integer,String> TEXT_NAMES=new HashMap<Integer,String>();
	private static void tn(String name,String type){TEXT_TYPES.put(name,Q3.fc(type));TEXT_NAMES.put(Q3.fc(type),name);}
	static{
		tn("AttributeSet","attr");tn("Set","set ");tn("AttributeArray","atar");tn("VertexAttributeSetList","vasl");tn("FaceAttributeSetList","fasl");
		tn("GeometryAttributeSetList","gasl");tn("Caps","caps");tn("InteriorCapAttributeSet","icas");tn("TopCapAttributeSet","tcas");tn("FaceCapAttributeSet","fcas");
		tn("BottomCapAttributeSet","bcas");tn("GeneralPolygonHint","gplh");tn("MeshCorners","crnr");tn("MeshEdges","medg");
		tn("SurfaceUV","sruv");tn("ShadingUV","shuv");tn("Normal","nrml");tn("AmbientCoefficient","camb");tn("DiffuseColor","kdif");tn("SpecularColor","kspc");
		tn("SpecularControl","cspc");tn("TransparencyColor","kxpr");tn("SurfaceTangent","srtn");tn("HighlightState","hlst");
		tn("DisplayGroupState","dgst");tn("DisplayGroupBBox","dgbb");tn("Light","lght");tn("CameraPlacement","cmpl");tn("CameraRange","cmrg");tn("CameraViewPort","cmvp");
		tn("Shader","shdr");tn("ShaderUVTransform","sduv");tn("ShaderTransform","sdxf");tn("Reference","rfrn");tn("TableOfContents","toc ");
		tn("IR_Preferences","vnid");tn("IR_DoubleBufferBypass","dbbp");tn("IR_CSGEquation","csge");tn("IR_RAVEContextHints","rvhn");tn("IR_TextureFilterMode","rvtx");
		tn("ImageDimensions","imdm");tn("ImageClearColor","imcc");tn("ImageMask","immk");tn("ImageClearMethod","imcl");
		tn("BackfacingStyle","bckf");tn("InterpolationStyle","intp");tn("FillStyle","fist");tn("OrientationStyle","ofdr");tn("PickIDStyle","pkid");
		tn("PickPartsStyle","pkpt");tn("ReceiveShadowsStyle","rcsh");tn("HighlightStyle","high");tn("SubdivisionStyle","sbdv");tn("AntiAliasStyle","anti");tn("FogStyle","fogg");
		tn("RotateAboutPoint","rtap");tn("RotateAboutAxis","rtaa");tn("CString","strc");tn("UnknownText","uktx");tn("UnknownBinary","ukbn");
		tn("EndGroup","endg");tn("BeginGroup","bgng");tn("Container","cntr");tn("Type","type");tn("ViewHints","vwhn");
	}

	// ================================================================== decoding

	private static final String[] BOOL={"False","True"};
	private static final String[] ENDIAN={"BigEndian","LittleEndian"};
	private static final String[] PIXEL={"RGB32","ARGB32","RGB16","ARGB16","RGB16_565","RGB24"};

	/** The object of a node (its sub-objects applied), or null for nothing to return. */
	static Q3.Obj decode(Reader r,Node n){
		int t=n.type;Cursor c=n.data;
		String name=Q3.fcName(t);
		switch(name){
			case "attr":return attributeSet(r,n);
			case "set ":{Q3.ElementSet s=new Q3.ElementSet(Q3.SET);for(Node e:n.subs)element(r,s,e);return s;}
			case "bgng":return group(r,n);
			case "tmsh":return triMesh(r,n);
			case "trng":{Q3.Triangle g=new Q3.Triangle();for(int i=0;i<3;i++)g.v[i]=vtx(c);vertexSets(r,n,g.v);geometrySet(r,n,g);return g;}
			case "plyg":{Q3.Polygon g=new Q3.Polygon();int k=c.u32();g.v=verts(c,k);vertexSets(r,n,g.v);geometrySet(r,n,g);return g;}
			case "gpgn":{Q3.GeneralPolygon g=new Q3.GeneralPolygon();int nc=c.u32();g.contours=new Q3.Vertex[Math.max(0,nc)][];List<Q3.Vertex> all=new ArrayList<Q3.Vertex>();
				for(int i=0;i<nc;i++){int k=c.u32();g.contours[i]=verts(c,k);for(Q3.Vertex v:g.contours[i])all.add(v);}
				for(Node s:n.subs)if(s.is("gplh")&&s.data!=null)g.shapeHint=s.data.enumeration("Complex","Concave","Convex");
				vertexSets(r,n,all.toArray(new Q3.Vertex[0]));geometrySet(r,n,g);return g;}
			case "line":{Q3.Line g=new Q3.Line();g.v[0]=vtx(c);g.v[1]=vtx(c);vertexSets(r,n,g.v);geometrySet(r,n,g);return g;}
			case "plyl":{Q3.PolyLine g=new Q3.PolyLine();int k=c.u32();g.v=verts(c,k);vertexSets(r,n,g.v);
				Q3.AttrSet[] segs=setList(r,n,"gasl",Math.max(0,k-1));if(segs!=null)g.segments=segs;geometrySet(r,n,g);return g;}
			case "pnt ":{Q3.Point g=new Q3.Point();g.x=c.f32();g.y=c.f32();g.z=c.f32();geometrySet(r,n,g);return g;}
			case "box ":{Q3.Quadric g=new Q3.Quadric(Q3.BOX);
				if(c==null||c.atEnd()){g.orientation[1]=1;g.major[2]=1;g.minor[0]=1;}else{read3(c,g.orientation);read3(c,g.major);read3(c,g.minor);read3(c,g.origin);}
				Q3.AttrSet[] f=setList(r,n,"fasl",6);if(f!=null)g.faces=f;geometrySet(r,n,g);return g;}
			case "elpd":case "cyln":case "cone":case "tors":{Q3.Quadric g=new Q3.Quadric(t);
				if(c==null||c.atEnd()){g.orientation[2]=1;g.major[0]=1;g.minor[1]=1;}
				else{read3(c,g.orientation);read3(c,g.major);read3(c,g.minor);read3(c,g.origin);if(t==Q3.TORUS)g.ratio=c.f32();
					if(!c.atEnd()){g.uMin=c.f32();g.uMax=c.f32();g.vMin=c.f32();g.vMax=c.f32();}}
				quadricParts(r,n,g);geometrySet(r,n,g);return g;}
			case "disk":case "elps":{Q3.Quadric g=new Q3.Quadric(t);
				if(c==null||c.atEnd()){g.major[0]=1;g.minor[1]=1;}
				else{read3(c,g.major);read3(c,g.minor);read3(c,g.origin);if(!c.atEnd()){g.uMin=c.f32();g.uMax=c.f32();if(t==Q3.DISK&&!c.atEnd()){g.vMin=c.f32();g.vMax=c.f32();}}}
				geometrySet(r,n,g);return g;}
			case "trig":{Q3.TriGrid g=new Q3.TriGrid();g.rows=c.u32();g.columns=c.u32();g.v=verts(c,g.rows*g.columns);vertexSets(r,n,g.v);
				Q3.AttrSet[] f=setList(r,n,"fasl",2*Math.max(0,g.rows-1)*Math.max(0,g.columns-1));if(f!=null)g.facets=f;geometrySet(r,n,g);return g;}
			case "plhd":{Q3.Polyhedron g=new Q3.Polyhedron();int nv=c.u32(),ne=c.u32(),nt=c.u32();g.v=verts(c,nv);
				g.edges=new int[4*ne];for(int i=0;i<4*ne;i++)g.edges[i]=c.u32();
				g.triangles=new int[3*nt];g.edgeFlags=new int[nt];for(int i=0;i<nt;i++){for(int k=0;k<3;k++)g.triangles[3*i+k]=c.u32();g.edgeFlags[i]=c.flags("Edge01","Edge12","Edge20");}
				vertexSets(r,n,g.v);Q3.AttrSet[] ea=setList(r,n,"gasl",ne);g.edgeAttrs=ea!=null?ea:new Q3.AttrSet[ne];Q3.AttrSet[] ta=setList(r,n,"fasl",nt);g.triangleAttrs=ta!=null?ta:new Q3.AttrSet[nt];
				geometrySet(r,n,g);return g;}
			case "mesh":return mesh(r,n);
			case "nrbc":{Q3.NURBCurve g=new Q3.NURBCurve();g.order=c.u32();int np=c.u32();g.points=c.floats(4*np);g.knots=c.floats(np+g.order);geometrySet(r,n,g);return g;}
			case "nrbp":{Q3.NURBPatch g=new Q3.NURBPatch();g.uOrder=c.u32();g.vOrder=c.u32();g.rows=c.u32();g.columns=c.u32();
				g.points=c.floats(4*g.rows*g.columns);g.uKnots=c.floats(g.columns+g.uOrder);g.vKnots=c.floats(g.rows+g.vOrder);geometrySet(r,n,g);return g;}
			case "mrkr":{Q3.Marker g=new Q3.Marker();g.x=c.f32();g.y=c.f32();g.z=c.f32();g.xOffset=c.u32();g.yOffset=c.u32();
				g.bitmap.width=c.u32();g.bitmap.height=c.u32();g.bitmap.rowBytes=c.u32();g.bitmap.bitOrder=c.enumeration(ENDIAN);g.bitmap.bits=c.bytes(g.bitmap.rowBytes*g.bitmap.height);geometrySet(r,n,g);return g;}
			case "mrkp":{Q3.PixmapMarker g=new Q3.PixmapMarker();g.x=c.f32();g.y=c.f32();g.z=c.f32();g.xOffset=c.u32();g.yOffset=c.u32();
				g.pixmap=storagePixmap(c,true);geometrySet(r,n,g);return g;}
			// styles
			case "bckf":return value(t,c.enumeration("Both","Remove","Flipped"));
			case "intp":return value(t,c.enumeration("None","Vertex","Pixel"));
			case "fist":return value(t,c.enumeration("Filled","Edges","Points"));
			case "ofdr":return value(t,c.enumeration("CounterClockwise","Clockwise"));
			case "pkid":return value(t,c.u32());
			case "pkpt":return value(t,c.flags("Face","Edge","Vertex"));
			case "rcsh":return value(t,c.bool()?1:0);
			case "sbdv":{Q3.Style s=new Q3.Style(t);int m=c.enumeration("Constant","WorldSpace","ScreenSpace");float c1=c.f32();float c2=m==0&&!c.atEnd()?c.f32():0;s.data=new float[]{m,c1,c2};return s;}
			case "anti":{Q3.Style s=new Q3.Style(t);s.data=new float[]{c.enumeration("Off","On"),c.flags("Edges","Filled"),c.f32()};return s;}
			case "fogg":{Q3.Style s=new Q3.Style(t);s.data=new float[]{c.enumeration("Off","On"),c.enumeration("Linear","Exponential","ExponentialSquared","Alpha"),c.f32(),c.f32(),c.f32(),c.f32(),c.f32(),c.f32(),c.f32()};return s;}
			case "high":{Q3.Style s=new Q3.Style(t);for(Node e:n.subs){Q3.Obj o=r.decode(e);if(o instanceof Q3.AttrSet){s.highlight=(Q3.AttrSet)o;break;}Q3.release(o);}return s;}
			// transforms
			case "mtrx":return new Q3.Transform(t,c.floats(16));
			case "scal":case "trns":return new Q3.Transform(t,c.floats(3));
			case "qtrn":return new Q3.Transform(t,c.floats(4));
			case "rott":{int ax=c.enumeration("X","Y","Z");Q3.Transform x=new Q3.Transform(t,new float[]{c.f32()});x.axis=ax;return x;}
			case "rtap":{int ax=c.enumeration("X","Y","Z");float rad=c.f32();Q3.Transform x=new Q3.Transform(t,new float[]{rad,c.f32(),c.f32(),c.f32()});x.axis=ax;return x;}
			case "rtaa":return new Q3.Transform(t,c.floats(7));
			case "rset":return new Q3.Transform(t,null);
			// shaders and textures
			case "phil":case "lmil":case "nuil":return new Q3.Shader(t);
			case "txsu":{Q3.Shader s=new Q3.Shader(t);
				for(Node e:n.subs){
					if(e.is("shdr")&&e.data!=null){String[] b={"Wrap","Clamp"};s.uBoundary=e.data.enumeration(b);s.vBoundary=e.data.enumeration(b);}
					else if(e.is("sduv")&&e.data!=null)s.uvTransform=e.data.floats(9);
					else{Q3.Obj o=r.decode(e);if(o instanceof Q3.Texture&&s.texture==null)s.texture=(Q3.Texture)o;else Q3.release(o);}
				}
				return s;}
			case "txpm":{Q3.Texture x=new Q3.Texture(t);x.pixmap=storagePixmap(c,false);return x;}
			case "txmm":{Q3.Texture x=new Q3.Texture(t);x.useMipmapping=c.bool();x.pixelType=c.enumeration(PIXEL);x.bitOrder=c.enumeration(ENDIAN);x.byteOrder=c.enumeration(ENDIAN);
				Q3.MipLevel l=new Q3.MipLevel();l.width=c.u32();l.height=c.u32();l.rowBytes=c.u32();l.offset=c.u32();
				List<Q3.MipLevel> levels=new ArrayList<Q3.MipLevel>();levels.add(l);
				int total=l.rowBytes*l.height;
				if(x.useMipmapping){int w=l.width,h=l.height,rb=l.rowBytes,off=l.offset+l.rowBytes*l.height;
					while(w>1||h>1){w=Math.max(1,w/2);h=Math.max(1,h/2);rb=Math.max(bpp(x.pixelType),rb/2);Q3.MipLevel m=new Q3.MipLevel();m.width=w;m.height=h;m.rowBytes=rb;m.offset=off;off+=rb*h;levels.add(m);}
					total=off-l.offset;}
				x.levels=levels.toArray(new Q3.MipLevel[0]);
				byte[] img=c.bytes(Math.max(0,l.offset+total));x.mipImage=new Q3.ArrayStorage(Q3.MEMORY_STORAGE,img);return x;}
			case "txcp":{Q3.Texture x=new Q3.Texture(t);return x;}
			// lights
			case "ambn":case "drct":case "pntl":case "spot":return light(r,n);
			// cameras
			case "orth":case "vwpl":case "vana":return camera(r,n);
			// renderers and view hints
			case "ctwn":case "wrfr":case "gnrr":{Q3.Renderer x=new Q3.Renderer(t);
				for(Node e:n.subs){Cursor d=e.data;if(d==null)continue;
					if(e.is("vnid")){x.vendor=d.u32();x.engine=d.u32();}else if(e.is("dbbp"))x.doubleBufferBypass=d.u32()!=0;
					else if(e.is("csge"))x.csgEquation=d.u32();else if(e.is("rvhn"))x.raveHints=d.u32();else if(e.is("rvtx"))x.textureFilter=d.u32();}
				return x;}
			case "vwhn":{Q3.Plain x=new Q3.Plain(Q3.VIEW_HINTS);
				for(Node e:n.subs){
					if(e.data!=null&&e.is("imdm")){x.props.put("width",e.data.u32());x.props.put("height",e.data.u32());continue;}
					if(e.data!=null&&e.is("imcc")){x.props.put("clearColor",e.data.floats(4));continue;}
					Q3.Obj o=r.decode(e);
					if(o instanceof Q3.Renderer)x.props.put("renderer",o);else if(o instanceof Q3.Camera)x.props.put("camera",o);
					else if(o instanceof Q3.Group)x.props.put("lights",o);else if(o instanceof Q3.AttrSet)x.props.put("attributes",o);
					else if(o instanceof Q3.Light){ // lights on their own: gathered into a light group
						Q3.Group g=(Q3.Group)x.props.computeIfAbsent("lights",k->new Q3.Group(Q3.LIGHT_GROUP));g.add(o);Q3.release(o);}
					else Q3.release(o);
				}
				return x;}
			case "strc":return new Q3.StringObj(c.string().getBytes(PpcQuickDraw3D.MAC_ROMAN));
			case "uktx":{Q3.Unknown u=new Q3.Unknown(t);u.name=n.label;return u;}
			case "ukbn":{Q3.Unknown u=new Q3.Unknown(t);if(c!=null){u.objectType=c.u32();int size=c.u32();u.byteOrder=c.enumeration(ENDIAN);u.contents=c.bytes(size);}return u;}
			case "push":case "pop ":return new Q3.StateOperator(t);
			default:{
				// an unknown object: kept as an unknown binary object with its data
				Q3.Unknown u=new Q3.Unknown(Q3.UNKNOWN_BINARY);u.objectType=t;
				if(c instanceof BinaryCursor){BinaryCursor b=(BinaryCursor)c;u.contents=b.bytes(b.remaining());}
				return u;
			}
		}
	}
	private static int bpp(int pixelType){return pixelType==Q3.PIXEL_RGB24?3:pixelType>=Q3.PIXEL_RGB16?2:4;}
	private static Q3.Style value(int t,int v){Q3.Style s=new Q3.Style(t);s.value=v;return s;}
	private static void read3(Cursor c,float[] v){v[0]=c.f32();v[1]=c.f32();v[2]=c.f32();}
	private static Q3.Vertex vtx(Cursor c){return new Q3.Vertex(c.f32(),c.f32(),c.f32(),null);}
	private static Q3.Vertex[] verts(Cursor c,int n){if(n<0||n>1<<22)throw new IllegalStateException("vertex count "+n);Q3.Vertex[] v=new Q3.Vertex[n];for(int i=0;i<n;i++)v[i]=vtx(c);return v;}
	/** A pixmap in the file: width height rowBytes pixelSize pixelType bitOrder byteOrder, then the image (a marker's has position first). */
	private static Q3.StoragePixmap storagePixmap(Cursor c,boolean marker){
		Q3.StoragePixmap p=new Q3.StoragePixmap();
		p.width=c.u32();p.height=c.u32();p.rowBytes=c.u32();p.pixelSize=c.u32();p.pixelType=c.enumeration(PIXEL);p.bitOrder=c.enumeration(ENDIAN);p.byteOrder=c.enumeration(ENDIAN);
		p.image=new Q3.ArrayStorage(Q3.MEMORY_STORAGE,c.bytes(Math.max(0,p.rowBytes*p.height)));
		return p;
	}

	/** An attribute set: its elements (standard attributes, a surface shader, custom ones kept as bytes). */
	private static Q3.AttrSet attributeSet(Reader r,Node n){Q3.AttrSet s=new Q3.AttrSet();for(Node e:n.subs)element(r,s,e);return s;}
	private static void element(Reader r,Q3.ElementSet s,Node e){
		String t=Q3.fcName(e.type);Cursor c=e.data;
		switch(t){
			case "sruv":s.put(Q3.ATTR_SURFACE_UV,c.floats(2));return;
			case "shuv":s.put(Q3.ATTR_SHADING_UV,c.floats(2));return;
			case "nrml":s.put(Q3.ATTR_NORMAL,c.floats(3));return;
			case "camb":s.put(Q3.ATTR_AMBIENT_COEFFICIENT,c.floats(1));return;
			case "kdif":s.put(Q3.ATTR_DIFFUSE,c.floats(3));return;
			case "kspc":s.put(Q3.ATTR_SPECULAR,c.floats(3));return;
			case "cspc":s.put(Q3.ATTR_SPECULAR_CONTROL,c.floats(1));return;
			case "kxpr":s.put(Q3.ATTR_TRANSPARENCY,c.floats(3));return;
			case "srtn":s.put(Q3.ATTR_SURFACE_TANGENT,c.floats(6));return;
			case "hlst":s.put(Q3.ATTR_HIGHLIGHT_STATE,c.enumeration("Off","On"));return;
			default:break;
		}
		if(e.refId>=0||e.is("txsu")){Q3.Obj o=r.decode(e);if(o instanceof Q3.Shader){s.put(Q3.ATTR_SURFACE_SHADER,o);}Q3.release(o);return;}
		// custom elements the program registered keep their bytes; others are dropped
		if(r.sizes!=null&&c instanceof BinaryCursor){int size=r.sizes.size(e.type);if(size>0){BinaryCursor b=(BinaryCursor)c;if(b.remaining()>=size)s.put(e.type,b.bytes(size));}}
	}
	/** The geometry's own attribute set: the attribute set among its sub-objects. */
	private static void geometrySet(Reader r,Node n,Q3.Geometry g){
		for(Node s:n.subs){
			if(s.is("attr")||s.refId>=0&&isAttr(r,s)){Q3.Obj o=r.decode(s);if(o instanceof Q3.AttrSet){Q3.release(g.attrs);g.attrs=(Q3.AttrSet)o;}else Q3.release(o);}
			else if(!isPart(s)&&s.type!=0){ /* custom elements of the shape */
				if(r.sizes!=null&&s.data instanceof BinaryCursor){int size=r.sizes.size(s.type);if(size>0){if(g.set==null)g.set=new Q3.ElementSet(Q3.SET);g.set.put(s.type,((BinaryCursor)s.data).bytes(Math.min(size,((BinaryCursor)s.data).remaining())));}}
			}
		}
	}
	private static boolean isAttr(Reader r,Node ref){Node t=r.resolve(ref.refId);return t!=null&&t.is("attr");}
	private static boolean isPart(Node s){String t=Q3.fcName(s.type);return "vasl fasl gasl caps icas tcas fcas bcas gplh crnr medg atar".contains(t);}
	/** Attribute sets of a list sub-object (vasl / fasl / gasl): the sets in order for the listed (Include) or unlisted (Exclude) elements. */
	private static Q3.AttrSet[] setList(Reader r,Node n,String kind,int count){
		for(Node s:n.subs){
			if(!s.is(kind)||s.data==null)continue;
			Cursor c=s.data;int num=c.u32();int packing=c.enumeration("Include","Exclude");int ni=c.u32();
			boolean[] listed=new boolean[Math.max(num,count)];
			for(int i=0;i<ni;i++){int k=c.u32();if(k>=0&&k<listed.length)listed[k]=true;}
			Q3.AttrSet[] out=new Q3.AttrSet[count];int si=0;
			for(int i=0;i<Math.min(num,count);i++){
				boolean has=packing==0?listed[i]:!listed[i];
				if(!has)continue;
				while(si<s.subs.size()){Node e=s.subs.get(si++);Q3.Obj o=r.decode(e);if(o instanceof Q3.AttrSet){out[i]=(Q3.AttrSet)o;break;}Q3.release(o);}
			}
			return out;
		}
		return null;
	}
	private static void vertexSets(Reader r,Node n,Q3.Vertex[] v){Q3.AttrSet[] s=setList(r,n,"vasl",v.length);if(s!=null)for(int i=0;i<v.length;i++)v[i].attrs=s[i];}
	private static void quadricParts(Reader r,Node n,Q3.Quadric g){
		for(Node s:n.subs){
			if(s.is("caps")&&s.data!=null){g.caps=s.data.flags("Top","Bottom","Interior");continue;}
			String k=Q3.fcName(s.type);
			if(!"icas tcas fcas bcas".contains(k))continue;
			Q3.AttrSet set=null;
			for(Node e:s.subs){Q3.Obj o=r.decode(e);if(o instanceof Q3.AttrSet&&set==null)set=(Q3.AttrSet)o;else Q3.release(o);}
			if(k.equals("icas"))g.interior=set;else if(k.equals("tcas"))g.top=set;else if(k.equals("fcas"))g.face=set;else g.bottom=set;
		}
	}

	private static Q3.Obj triMesh(Reader r,Node n){
		Cursor c=n.data;Q3.TriMesh g=new Q3.TriMesh();
		int nt=c.u32(),nta=c.u32(),ne=c.u32(),nea=c.u32(),np=c.u32(),nva=c.u32();
		if(nt<0||ne<0||np<0||nt>1<<22||np>1<<22||ne>1<<22)throw new IllegalStateException("TriMesh counts");
		g.triangles=new int[3*nt];for(int i=0;i<3*nt;i++)g.triangles[i]=c.index(np);
		g.edges=new int[4*ne];for(int i=0;i<ne;i++){g.edges[4*i]=c.index(np);g.edges[4*i+1]=c.index(np);g.edges[4*i+2]=c.index(nt);g.edges[4*i+3]=c.index(nt);}
		g.points=c.floats(3*np);
		if(!c.atEnd()){float[] b=c.floats(6);System.arraycopy(b,0,g.bbox,0,6);g.bboxEmpty=!c.atEnd()&&c.bool();}else g.computeBounds();
		List<Q3.AttrArray> ta=new ArrayList<Q3.AttrArray>(),ea=new ArrayList<Q3.AttrArray>(),va=new ArrayList<Q3.AttrArray>();
		for(Node s:n.subs){
			if(!s.is("atar")||s.data==null)continue;
			Cursor d=s.data;int type=d.u32();d.u32();int where=d.u32();d.u32();int useFlag=d.u32();
			int count=where==0?nt:where==1?ne:np;
			Q3.AttrArray a=new Q3.AttrArray();a.type=type;
			if(useFlag!=0)a.use=d.bytes(count);
			if(Q3.isStandardAttribute(type)&&Q3.ATTR_FLOATS[type]>0)a.f=d.floats(Q3.ATTR_FLOATS[type]*count);
			else if(type==Q3.ATTR_HIGHLIGHT_STATE)a.i=d.ints(count);
			else if(type==Q3.ATTR_SURFACE_SHADER)continue;
			else{int size=r.sizes==null?-1:r.sizes.size(type);if(size<=0)continue;a.rawSize=size;a.raw=d.bytes(size*count);}
			(where==0?ta:where==1?ea:va).add(a);
		}
		g.triangleAttrs=ta.toArray(new Q3.AttrArray[0]);g.edgeAttrs=ea.toArray(new Q3.AttrArray[0]);g.vertexAttrs=va.toArray(new Q3.AttrArray[0]);
		geometrySet(r,n,g);
		return g;
	}
	private static Q3.Obj mesh(Reader r,Node n){
		Cursor c=n.data;Q3.Mesh g=new Q3.Mesh();
		int nv=c.u32();if(nv<0||nv>1<<22)throw new IllegalStateException("mesh vertices "+nv);
		for(int i=0;i<nv;i++){Q3.Mesh.MVertex v=new Q3.Mesh.MVertex();v.x=c.f32();v.y=c.f32();v.z=c.f32();v.id=g.nextId++;g.vertices.add(v);}
		int nf=c.u32(),nc=c.u32(); // faces, contours (each listed after its face, as a negative count)
		Q3.Mesh.MFace last=null;
		for(int i=0;i<nf+Math.max(0,nc)&&!c.atEnd();i++){
			int k=c.u32();boolean contour=k<0;k=Math.abs(k);
			Q3.Mesh.MContour ct=new Q3.Mesh.MContour();
			for(int j=0;j<k;j++){int vi=c.u32();if(vi>=0&&vi<nv)ct.vertices.add(g.vertices.get(vi));}
			if(contour&&last!=null){ct.face=last;last.contours.add(ct);}
			else{Q3.Mesh.MFace f=new Q3.Mesh.MFace();ct.face=f;f.contours.add(ct);g.faces.add(f);last=f;}
		}
		Q3.AttrSet[] vs=setList(r,n,"vasl",nv);if(vs!=null)for(int i=0;i<nv;i++)g.vertices.get(i).attrs=vs[i];
		Q3.AttrSet[] fs=setList(r,n,"fasl",g.faces.size());if(fs!=null)for(int i=0;i<g.faces.size();i++)g.faces.get(i).attrs=fs[i];
		for(Node s:n.subs){
			if(!s.is("crnr")||s.data==null)continue;
			Cursor d=s.data;int ncorners=d.u32();int si=0;
			for(int i=0;i<ncorners&&!d.atEnd();i++){
				int vi=d.u32(),nfc=d.u32();int[] faces=new int[Math.max(0,nfc)];for(int j=0;j<nfc;j++)faces[j]=d.u32();
				Q3.AttrSet set=null;
				while(si<s.subs.size()){Q3.Obj o=r.decode(s.subs.get(si++));if(o instanceof Q3.AttrSet){set=(Q3.AttrSet)o;break;}Q3.release(o);}
				if(set==null||vi<0||vi>=nv){Q3.release(set);continue;}
				for(int fi:faces)if(fi>=0&&fi<g.faces.size())g.faces.get(fi).corners.put(g.vertices.get(vi),Q3.ref(set));
				Q3.release(set);
			}
		}
		geometrySet(r,n,g);
		return g;
	}
	private static Q3.Obj light(Reader r,Node n){
		Cursor c=n.data;Q3.Light l=new Q3.Light(n.type);
		String t=Q3.fcName(n.type);
		if(t.equals("drct")){read3(c,l.direction);l.castsShadows=c.bool();}
		else if(t.equals("pntl")){read3(c,l.location);l.attenuation=attenuation(c.floats(3));l.castsShadows=c.bool();}
		else if(t.equals("spot")){read3(c,l.location);read3(c,l.direction);l.castsShadows=c.bool();l.attenuation=attenuation(c.floats(3));l.hotAngle=c.f32();l.outerAngle=c.f32();l.fallOff=c.enumeration("None","Linear","Exponential","Cosine");}
		for(Node s:n.subs)if(s.is("lght")&&s.data!=null){Cursor d=s.data;l.on=d.bool();l.brightness=d.f32();read3(d,l.color);}
		return l;
	}
	/** The attenuation type of the file's coefficients (c0 c1 c2 of 1 / (c0 + c1 d + c2 d²)). */
	private static int attenuation(float[] k){return k[2]!=0?Q3.ATTENUATION_INVERSE_SQUARED:k[1]!=0?Q3.ATTENUATION_INVERSE:Q3.ATTENUATION_NONE;}
	private static Q3.Obj camera(Reader r,Node n){
		Cursor c=n.data;Q3.Camera cam=new Q3.Camera(n.type);
		String t=Q3.fcName(n.type);
		if(t.equals("orth")){cam.left=c.f32();cam.top=c.f32();cam.right=c.f32();cam.bottom=c.f32();}
		else if(t.equals("vwpl")){cam.viewPlane=c.f32();cam.halfWidth=c.f32();cam.halfHeight=c.f32();cam.centerX=c.f32();cam.centerY=c.f32();}
		else{cam.fov=c.f32();cam.aspect=c.f32();}
		for(Node s:n.subs){Cursor d=s.data;if(d==null)continue;
			if(s.is("cmpl")){read3(d,cam.location);read3(d,cam.pointOfInterest);read3(d,cam.up);}
			else if(s.is("cmrg")){cam.hither=d.f32();cam.yon=d.f32();}
			else if(s.is("cmvp")){cam.vpX=d.f32();cam.vpY=d.f32();cam.vpWidth=d.f32();cam.vpHeight=d.f32();}}
		return cam;
	}
	/** A group: its kind (the first sub-object), its state and box, then its members. */
	private static Q3.Obj group(Reader r,Node n){
		int kind=n.subs.isEmpty()?Q3.DISPLAY_GROUP:n.subs.get(0).type;
		if(Q3.chainOf(kind)==null)kind=Q3.DISPLAY_GROUP;
		Q3.Group g=new Q3.Group(kind);
		for(Node s:n.subs){Cursor d=s.data;if(d==null)continue;
			if(s.is("dgst")){int f=d.flags("Inline","DoNotDraw","NoBoundingBox","NoBoundingSphere","DoNotPick");
				g.state=(f&1)!=0?Q3.DG_STATE_INLINE:0;if((f&2)==0)g.state|=Q3.DG_STATE_DRAWN;if((f&16)==0)g.state|=Q3.DG_STATE_PICKED;g.state|=Q3.DG_STATE_WRITTEN;}
			else if(s.is("dgbb")){g.bbox=d.floats(6);g.state|=Q3.DG_STATE_BBOX;}}
		if(n.members!=null)for(Node m:n.members){
			Q3.Obj o=r.decode(m);if(o==null)continue;
			if(g.accepts(o))g.add(o);
			Q3.release(o);
		}
		return g;
	}

	// ================================================================== writing

	/** Where an object goes: binary words or text tokens, with objects nested as containers. */
	static abstract class Out {
		abstract void begin(int type);abstract void end();
		abstract void u32(int v);abstract void f32(float v);abstract void u16(int v);abstract void u8(int v);
		void floats(float... f){for(float v:f)f32(v);}
		abstract void enumeration(int v,String... names);
		abstract void flags(int v,String... names);
		abstract void bytes(byte[] b,int n);
		abstract void string(String s);
		/** An index into count items (TriMesh). */
		abstract void index(int v,int count);
		/** A reference to an object written before (by its id in the table of contents). */
		abstract void reference(int id);
		/** Where the next object starts (binary offset) and its label (text), to put it in the table of contents. */
		abstract long location();abstract void label(String l);
		abstract byte[] finish(List<long[]> toc,List<String> tocLabels,List<Integer> tocTypes);
	}

	static final class BinaryOut extends Out {
		final ByteArrayOutputStream b=new ByteArrayOutputStream();
		private final java.util.ArrayDeque<Integer> sizes=new java.util.ArrayDeque<Integer>();
		private byte[] buf(){return null;}
		private final List<byte[]> patches=new ArrayList<byte[]>();
		BinaryOut(int major,int minor,int mode){w32(Q3.fc("3DMF"));w32(16);w16(major);w16(minor);w32(mode);w32(0);w32(0);}
		private void w32(int v){b.write(v>>>24);b.write(v>>>16);b.write(v>>>8);b.write(v);}
		private void w16(int v){b.write(v>>>8);b.write(v);}
		@Override void begin(int type){w32(type);sizes.push(b.size());w32(0);}
		@Override void end(){int at=sizes.pop();int size=b.size()-at-4;patch(at,size);}
		private void patch(int at,int v){byte[] a=b.toByteArray();a[at]=(byte)(v>>>24);a[at+1]=(byte)(v>>>16);a[at+2]=(byte)(v>>>8);a[at+3]=(byte)v;b.reset();b.write(a,0,a.length);}
		@Override void u32(int v){w32(v);}@Override void u16(int v){w16(v);}@Override void u8(int v){b.write(v);}
		@Override void f32(float v){w32(Float.floatToRawIntBits(v));}
		@Override void enumeration(int v,String... names){w32(v);}
		@Override void flags(int v,String... names){w32(v);}
		@Override void bytes(byte[] d,int n){b.write(d,0,Math.min(n,d.length));for(int i=d.length;i<n;i++)b.write(0);while((b.size()&3)!=0)b.write(0);}
		@Override void string(String s){byte[] d=s.getBytes(PpcQuickDraw3D.MAC_ROMAN);b.write(d,0,d.length);b.write(0);while((b.size()&3)!=0)b.write(0);}
		@Override void index(int v,int count){if(count<=0xff)b.write(v);else if(count<=0xffff)w16(v);else w32(v);}
		@Override void reference(int id){begin(Q3.fc("rfrn"));w32(id);end();}
		@Override long location(){return b.size();}
		@Override void label(String l){}
		@Override byte[] finish(List<long[]> toc,List<String> labels,List<Integer> types){
			if(!toc.isEmpty()){
				int at=b.size();
				begin(Q3.fc("toc "));w32(0);w32(0);w32(toc.size()+1);w32(-1);w32(1);w32(16);w32(toc.size());
				for(int i=0;i<toc.size();i++){long[] e=toc.get(i);w32((int)e[0]);w32((int)(e[1]>>>32));w32((int)e[1]);w32(types.get(i));}
				end();
				byte[] a=b.toByteArray();a[16]=0;a[17]=0;a[18]=0;a[19]=0;a[20]=(byte)(at>>>24);a[21]=(byte)(at>>>16);a[22]=(byte)(at>>>8);a[23]=(byte)at;return a; // the header's tocLocation (64 bits)
			}
			return b.toByteArray();
		}
	}

	static final class TextOut extends Out {
		final StringBuilder sb=new StringBuilder();int depth=1;boolean fresh=true;String pendingLabel;int counter;
		private final java.util.ArrayDeque<Boolean> containers=new java.util.ArrayDeque<Boolean>();
		TextOut(int major,int minor){sb.append("3DMetafile ( ").append(major).append(' ').append(minor).append(" Normal tableofcontents0> )\n");}
		private void indent(){for(int i=0;i<depth;i++)sb.append('\t');}
		private void word(String w){if(fresh){indent();fresh=false;}else sb.append(' ');sb.append(w);}
		@Override void begin(int type){
			String name=TEXT_NAMES.containsKey(type)?TEXT_NAMES.get(type):Q3.NAMES.getOrDefault(type,type==Q3.fc("cntr")?"Container":type==Q3.fc("bgng")?"BeginGroup":Q3.fcName(type));
			if(!fresh){sb.append('\n');fresh=true;}
			if(pendingLabel!=null){indent();sb.append(pendingLabel).append(":\n");pendingLabel=null;}
			indent();sb.append(name).append(" (");fresh=false;depth++;
		}
		@Override void end(){depth--;sb.append(" )\n");fresh=true;}
		@Override void u32(int v){word(Integer.toString(v));}@Override void u16(int v){u32(v);}@Override void u8(int v){u32(v);}
		@Override void f32(float v){word(v==(long)v&&Math.abs(v)<1e9?Long.toString((long)v):Float.toString(v));}
		@Override void enumeration(int v,String... names){word(v>=0&&v<names.length?names[v]:Integer.toString(v));}
		@Override void flags(int v,String... names){
			StringBuilder s=new StringBuilder();for(int i=0;i<names.length;i++)if((v&1<<i)!=0){if(s.length()>0)s.append(" | ");s.append(names[i]);}
			word(s.length()==0?"None":s.toString());
		}
		@Override void bytes(byte[] d,int n){
			for(int i=0;i<n;i+=16){StringBuilder h=new StringBuilder("0x");for(int k=i;k<Math.min(n,i+16);k++)h.append(String.format("%02X",k<d.length?d[k]&0xff:0));
				sb.append('\n');fresh=true;word(h.toString());}
		}
		@Override void string(String s){word("\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"");}
		@Override void index(int v,int count){u32(v);}
		@Override void reference(int id){begin(Q3.fc("rfrn"));u32(id);end();}
		@Override long location(){return counter;}
		@Override void label(String l){pendingLabel=l;}
		@Override byte[] finish(List<long[]> toc,List<String> labels,List<Integer> types){
			if(!toc.isEmpty()){
				sb.append("\ttableofcontents0:\n\tTableOfContents (\n\t\ttableofcontents1> # next TOC\n\t\t").append(toc.size()+1).append(" # reference seed\n\t\t-1 # typeSeed\n\t\t1 # tocEntryType\n\t\t16 # tocEntrySize\n\t\t")
					.append(toc.size()).append(" # nEntries\n");
				for(int i=0;i<toc.size();i++)sb.append("\t\t").append(toc.get(i)[0]).append(' ').append(labels.get(i)).append(">\n\t\t").append(Q3.NAMES.getOrDefault(types.get(i),Q3.fcName(types.get(i)))).append('\n');
				sb.append("\t)\n");
			}
			return sb.toString().replace("\n","\r").getBytes(PpcQuickDraw3D.MAC_ROMAN);
		}
	}

	/** Writes objects to a file: shared objects met again become references (with a table of contents). */
	static final class Writer {
		final Out out;
		private final IdentityHashMap<Q3.Obj,Integer> seen=new IdentityHashMap<Q3.Obj,Integer>();
		private final IdentityHashMap<Q3.Obj,long[]> where=new IdentityHashMap<Q3.Obj,long[]>();
		private final IdentityHashMap<Q3.Obj,String> labels=new IdentityHashMap<Q3.Obj,String>();
		final List<long[]> toc=new ArrayList<long[]>();final List<String> tocLabels=new ArrayList<String>();final List<Integer> tocTypes=new ArrayList<Integer>();
		private int labelCount=1;
		Writer(boolean text){out=text?new TextOut(1,6):new BinaryOut(1,6,0);}
		byte[] finish(){return out.finish(toc,tocLabels,tocTypes);}

		/** Writes an object (with the view state it was submitted in not recorded: QuickDraw 3D wrote objects alone too). */
		void write(Q3.Obj o){
			if(o==null)return;
			if(o.shared()&&!(o instanceof Q3.Group)&&where.containsKey(o)){
				Integer id=seen.get(o);
				if(id==null){id=toc.size()+1;seen.put(o,id);long[] w=where.get(o);toc.add(new long[]{id,w[0]});tocLabels.add(labels.get(o));tocTypes.add(o.leaf());}
				out.reference(id);return;
			}
			String label=(Q3.NAMES.getOrDefault(o.leaf(),"object").toLowerCase())+(labelCount++);
			out.label(label);labels.put(o,label);
			where.put(o,new long[]{out.location()});
			writeObject(o);
		}
		private void container(int type,Runnable data,Runnable subs){
			out.begin(Q3.fc("cntr"));out.begin(type);data.run();out.end();subs.run();out.end();
		}
		private void simple(int type,Runnable data){out.begin(type);data.run();out.end();}
		private void attrs(Q3.AttrSet s){if(s!=null&&!s.map.isEmpty())write(s);}
		private void writeObject(Q3.Obj o){
			int t=o.leaf();String n=Q3.fcName(t);
			if(o instanceof Q3.AttrSet){Q3.AttrSet s=(Q3.AttrSet)o;out.begin(Q3.fc("cntr"));out.begin(Q3.fc("attr"));out.end();elements(s);out.end();return;}
			if(o instanceof Q3.Group){group((Q3.Group)o);return;}
			if(o instanceof Q3.Geometry){geometry((Q3.Geometry)o);return;}
			if(o instanceof Q3.Style){style((Q3.Style)o);return;}
			if(o instanceof Q3.Transform){Q3.Transform x=(Q3.Transform)o;
				simple(t,()->{
					if(t==Q3.ROTATE){out.enumeration(x.axis,"X","Y","Z");out.f32(x.v[0]);}
					else if(t==Q3.ROTATE_ABOUT_POINT){out.enumeration(x.axis,"X","Y","Z");out.floats(x.v);}
					else if(x.v!=null)out.floats(x.v);});return;}
			if(o instanceof Q3.Shader){shader((Q3.Shader)o);return;}
			if(o instanceof Q3.Texture){texture((Q3.Texture)o);return;}
			if(o instanceof Q3.Light){light((Q3.Light)o);return;}
			if(o instanceof Q3.Camera){camera((Q3.Camera)o);return;}
			if(o instanceof Q3.StringObj){simple(t,()->out.string(new String(((Q3.StringObj)o).text,PpcQuickDraw3D.MAC_ROMAN)));return;}
			if(o instanceof Q3.StateOperator){simple(t,()->{});return;}
			if(o instanceof Q3.Renderer){Q3.Renderer x=(Q3.Renderer)o;out.begin(Q3.fc("cntr"));simple(t,()->{});
				if(t==Q3.INTERACTIVE){simple(Q3.fc("vnid"),()->{out.u32(x.vendor);out.u32(x.engine);});simple(Q3.fc("dbbp"),()->out.u32(x.doubleBufferBypass?1:0));
					simple(Q3.fc("csge"),()->out.u32(x.csgEquation));simple(Q3.fc("rvhn"),()->out.u32(x.raveHints));simple(Q3.fc("rvtx"),()->out.u32(x.textureFilter));}
				out.end();return;}
			if(o instanceof Q3.Unknown){Q3.Unknown u=(Q3.Unknown)o;if(u.contents!=null&&u.objectType!=0)simple(u.objectType,()->out.bytes(u.contents,u.contents.length));return;}
			if(o instanceof Q3.Plain&&t==Q3.VIEW_HINTS){Q3.Plain x=(Q3.Plain)o;out.begin(Q3.fc("cntr"));simple(t,()->{});
				for(String k:new String[]{"renderer","camera"})if(x.props.get(k) instanceof Q3.Obj)write((Q3.Obj)x.props.get(k));
				// the lights one by one, as QuickDraw 3D wrote them
				if(x.props.get("lights") instanceof Q3.Group)for(Q3.Group.Pos p:((Q3.Group)x.props.get("lights")).list)write(p.obj);
				if(x.props.get("attributes") instanceof Q3.AttrSet)attrs((Q3.AttrSet)x.props.get("attributes"));
				if(x.props.get("clearColor") instanceof float[]){float[] c=(float[])x.props.get("clearColor");simple(Q3.fc("imcc"),()->out.floats(c));}
				if(x.props.get("width") instanceof Integer&&x.props.get("height") instanceof Integer){int w=(Integer)x.props.get("width"),h=(Integer)x.props.get("height");simple(Q3.fc("imdm"),()->{out.u32(w);out.u32(h);});}
				out.end();return;}
			// others (draw contexts ...) are not written
		}
		private void elements(Q3.ElementSet s){
			for(Map.Entry<Integer,Object> e:s.map.entrySet()){
				int t=e.getKey();Object v=e.getValue();
				String code=t==Q3.ATTR_SURFACE_UV?"sruv":t==Q3.ATTR_SHADING_UV?"shuv":t==Q3.ATTR_NORMAL?"nrml":t==Q3.ATTR_AMBIENT_COEFFICIENT?"camb":t==Q3.ATTR_DIFFUSE?"kdif":
					t==Q3.ATTR_SPECULAR?"kspc":t==Q3.ATTR_SPECULAR_CONTROL?"cspc":t==Q3.ATTR_TRANSPARENCY?"kxpr":t==Q3.ATTR_SURFACE_TANGENT?"srtn":t==Q3.ATTR_HIGHLIGHT_STATE?"hlst":null;
				if(v instanceof float[]&&code!=null){float[] f=(float[])v;simple(Q3.fc(code),()->out.floats(f));}
				else if(v instanceof Integer&&code!=null){simple(Q3.fc(code),()->out.u32((Integer)v));}
				else if(v instanceof Q3.Obj)write((Q3.Obj)v);
			}
		}
		private void group(Q3.Group g){
			if((g.state&Q3.DG_STATE_WRITTEN)==0&&g.display())return;
			out.begin(Q3.fc("bgng"));out.begin(g.leaf());out.end();
			if(g.display()){
				int f=((g.state&Q3.DG_STATE_INLINE)!=0?1:0)|((g.state&Q3.DG_STATE_DRAWN)==0?2:0)|(g.bbox==null?4:0)|((g.state&Q3.DG_STATE_PICKED)==0?16:0);
				if(g.bbox!=null)simple(Q3.fc("dgbb"),()->out.floats(g.bbox));
				else simple(Q3.fc("dgst"),()->out.flags(f,"Inline","DoNotDraw","NoBoundingBox","NoBoundingSphere","DoNotPick"));
			}
			out.end();
			for(Q3.Group.Pos p:g.list)write(p.obj);
			out.begin(Q3.END_GROUP);out.end();
		}
		private void vertexList(String kind,Q3.AttrSet[] sets){
			if(sets==null)return;int n=sets.length,have=0;for(Q3.AttrSet s:sets)if(s!=null&&!s.map.isEmpty())have++;
			if(have==0)return;
			boolean exclude=have>n/2;List<Integer> idx=new ArrayList<Integer>();
			for(int i=0;i<n;i++){boolean h=sets[i]!=null&&!sets[i].map.isEmpty();if(exclude!=h)idx.add(i);}
			out.begin(Q3.fc("cntr"));
			simple(Q3.fc(kind),()->{out.u32(n);out.enumeration(exclude?1:0,"Include","Exclude");out.u32(idx.size());for(int i:idx)out.u32(i);});
			for(Q3.AttrSet s:sets)if(s!=null&&!s.map.isEmpty())write(s);
			out.end();
		}
		private static Q3.AttrSet[] setsOf(Q3.Vertex[] v){Q3.AttrSet[] s=new Q3.AttrSet[v.length];for(int i=0;i<v.length;i++)s[i]=v[i].attrs;return s;}
		private void pts(Q3.Vertex[] v){for(Q3.Vertex x:v){out.f32(x.x);out.f32(x.y);out.f32(x.z);}}
		private void geometry(Q3.Geometry g){
			int t=g.leaf();
			boolean sub=g.attrs!=null&&!g.attrs.map.isEmpty();
			out.begin(Q3.fc("cntr"));
			if(g instanceof Q3.Triangle){Q3.Triangle x=(Q3.Triangle)g;simple(t,()->pts(x.v));vertexList("vasl",setsOf(x.v));}
			else if(g instanceof Q3.Polygon){Q3.Polygon x=(Q3.Polygon)g;simple(t,()->{out.u32(x.v.length);pts(x.v);});vertexList("vasl",setsOf(x.v));}
			else if(g instanceof Q3.GeneralPolygon){Q3.GeneralPolygon x=(Q3.GeneralPolygon)g;simple(t,()->{out.u32(x.contours.length);for(Q3.Vertex[] c:x.contours){out.u32(c.length);pts(c);}});
				simple(Q3.fc("gplh"),()->out.enumeration(x.shapeHint,"Complex","Concave","Convex"));
				List<Q3.AttrSet> all=new ArrayList<Q3.AttrSet>();for(Q3.Vertex[] c:x.contours)for(Q3.Vertex v:c)all.add(v.attrs);vertexList("vasl",all.toArray(new Q3.AttrSet[0]));}
			else if(g instanceof Q3.Line){Q3.Line x=(Q3.Line)g;simple(t,()->pts(x.v));vertexList("vasl",setsOf(x.v));}
			else if(g instanceof Q3.PolyLine){Q3.PolyLine x=(Q3.PolyLine)g;simple(t,()->{out.u32(x.v.length);pts(x.v);});vertexList("vasl",setsOf(x.v));vertexList("gasl",x.segments);}
			else if(g instanceof Q3.Point){Q3.Point x=(Q3.Point)g;simple(t,()->out.floats(x.x,x.y,x.z));}
			else if(g instanceof Q3.Quadric){Q3.Quadric x=(Q3.Quadric)g;
				simple(t,()->{
					if(t!=Q3.DISK&&t!=Q3.ELLIPSE)out.floats(x.orientation);out.floats(x.major);out.floats(x.minor);out.floats(x.origin);
					if(t==Q3.TORUS)out.f32(x.ratio);
					if(t!=Q3.BOX){out.f32(x.uMin);out.f32(x.uMax);if(t!=Q3.ELLIPSE){out.f32(x.vMin);out.f32(x.vMax);}}});
				if(t==Q3.BOX)vertexList("fasl",x.faces);
				if(t==Q3.CYLINDER||t==Q3.CONE||t==Q3.ELLIPSOID||t==Q3.TORUS){
					if(x.caps!=0)simple(Q3.fc("caps"),()->out.flags(x.caps,"Top","Bottom","Interior"));
					cap("icas",x.interior);if(t==Q3.CYLINDER)cap("tcas",x.top);if(t==Q3.CYLINDER||t==Q3.CONE){cap("fcas",x.face);cap("bcas",x.bottom);}
				}}
			else if(g instanceof Q3.TriGrid){Q3.TriGrid x=(Q3.TriGrid)g;simple(t,()->{out.u32(x.rows);out.u32(x.columns);pts(x.v);});vertexList("vasl",setsOf(x.v));vertexList("fasl",x.facets);}
			else if(g instanceof Q3.TriMesh){triMesh((Q3.TriMesh)g);}
			else if(g instanceof Q3.Polyhedron){Q3.Polyhedron x=(Q3.Polyhedron)g;
				simple(t,()->{out.u32(x.v.length);out.u32(x.edges.length/4);out.u32(x.triangles.length/3);pts(x.v);for(int e:x.edges)out.u32(e);
					for(int i=0;i<x.triangles.length/3;i++){out.u32(x.triangles[3*i]);out.u32(x.triangles[3*i+1]);out.u32(x.triangles[3*i+2]);out.flags(x.edgeFlags[i],"Edge01","Edge12","Edge20");}});
				vertexList("vasl",setsOf(x.v));vertexList("gasl",x.edgeAttrs);vertexList("fasl",x.triangleAttrs);}
			else if(g instanceof Q3.Mesh){mesh((Q3.Mesh)g);}
			else if(g instanceof Q3.NURBCurve){Q3.NURBCurve x=(Q3.NURBCurve)g;simple(t,()->{out.u32(x.order);out.u32(x.points.length/4);out.floats(x.points);out.floats(x.knots);});}
			else if(g instanceof Q3.NURBPatch){Q3.NURBPatch x=(Q3.NURBPatch)g;simple(t,()->{out.u32(x.uOrder);out.u32(x.vOrder);out.u32(x.rows);out.u32(x.columns);out.floats(x.points);out.floats(x.uKnots);out.floats(x.vKnots);});}
			else if(g instanceof Q3.Marker){Q3.Marker x=(Q3.Marker)g;simple(t,()->{out.floats(x.x,x.y,x.z);out.u32(x.xOffset);out.u32(x.yOffset);out.u32(x.bitmap.width);out.u32(x.bitmap.height);
				out.u32(x.bitmap.rowBytes);out.enumeration(x.bitmap.bitOrder,ENDIAN);out.bytes(x.bitmap.bits==null?new byte[0]:x.bitmap.bits,x.bitmap.rowBytes*x.bitmap.height);});}
			else if(g instanceof Q3.PixmapMarker){Q3.PixmapMarker x=(Q3.PixmapMarker)g;simple(t,()->{out.floats(x.x,x.y,x.z);out.u32(x.xOffset);out.u32(x.yOffset);pixmap(x.pixmap);});}
			if(sub)write(g.attrs);
			out.end();
		}
		private void cap(String kind,Q3.AttrSet s){if(s==null||s.map.isEmpty())return;out.begin(Q3.fc("cntr"));simple(Q3.fc(kind),()->{});write(s);out.end();}
		private void pixmap(Q3.StoragePixmap p){
			out.u32(p.width);out.u32(p.height);out.u32(p.rowBytes);out.u32(p.pixelSize);out.enumeration(p.pixelType,PIXEL);out.enumeration(p.bitOrder,ENDIAN);out.enumeration(p.byteOrder,ENDIAN);
			byte[] img;try{img=p.image==null?new byte[0]:p.image.bytes();}catch(Exception e){img=new byte[0];}
			out.bytes(img,p.rowBytes*p.height);
		}
		private void triMesh(Q3.TriMesh x){
			int nt=x.numTriangles(),ne=x.numEdges(),np=x.numPoints();
			List<Q3.AttrArray> arrays=new ArrayList<Q3.AttrArray>();
			simple(Q3.TRIMESH,()->{
				out.u32(nt);out.u32(count(x.triangleAttrs));out.u32(ne);out.u32(count(x.edgeAttrs));out.u32(np);out.u32(count(x.vertexAttrs));
				for(int v:x.triangles)out.index(v,np);
				for(int i=0;i<ne;i++){out.index(x.edges[4*i],np);out.index(x.edges[4*i+1],np);out.index(x.edges[4*i+2],nt);out.index(x.edges[4*i+3],nt);}
				out.floats(x.points);out.floats(x.bbox);out.enumeration(x.bboxEmpty?1:0,BOOL);});
			attrArrays(x.triangleAttrs,0,nt);attrArrays(x.edgeAttrs,1,ne);attrArrays(x.vertexAttrs,2,np);
		}
		private static int count(Q3.AttrArray[] a){int n=0;for(Q3.AttrArray x:a)if(x.f!=null||x.i!=null)n++;return n;}
		private void attrArrays(Q3.AttrArray[] arrays,int where,int count){
			int k=0;
			for(Q3.AttrArray a:arrays){if(a.f==null&&a.i==null)continue;final int index=k++;
				simple(Q3.fc("atar"),()->{out.u32(a.type);out.u32(0);out.u32(where);out.u32(index);out.u32(a.use!=null?1:0);
					if(a.use!=null)for(int i=0;i<count;i++)out.u8(a.use[i]);
					if(a.f!=null)out.floats(a.f);else for(int v:a.i)out.u32(v);});
			}
		}
		private void mesh(Q3.Mesh x){
			Map<Q3.Mesh.MVertex,Integer> index=new IdentityHashMap<Q3.Mesh.MVertex,Integer>();for(int i=0;i<x.vertices.size();i++)index.put(x.vertices.get(i),i);
			int contours=0;for(Q3.Mesh.MFace f:x.faces)contours+=Math.max(0,f.contours.size()-1);final int nc=contours;
			simple(Q3.MESH,()->{
				out.u32(x.vertices.size());for(Q3.Mesh.MVertex v:x.vertices)out.floats(v.x,v.y,v.z);
				out.u32(x.faces.size());out.u32(nc);
				for(Q3.Mesh.MFace f:x.faces)for(int c=0;c<f.contours.size();c++){List<Q3.Mesh.MVertex> vs=f.contours.get(c).vertices;out.u32(c==0?vs.size():-vs.size());for(Q3.Mesh.MVertex v:vs)out.u32(index.get(v));}});
			Q3.AttrSet[] vs=new Q3.AttrSet[x.vertices.size()];for(int i=0;i<vs.length;i++)vs[i]=x.vertices.get(i).attrs;vertexList("vasl",vs);
			Q3.AttrSet[] fs=new Q3.AttrSet[x.faces.size()];for(int i=0;i<fs.length;i++)fs[i]=x.faces.get(i).attrs;vertexList("fasl",fs);
			List<Object[]> corners=new ArrayList<Object[]>();
			for(int fi=0;fi<x.faces.size();fi++)for(Map.Entry<Q3.Mesh.MVertex,Q3.AttrSet> e:x.faces.get(fi).corners.entrySet())corners.add(new Object[]{index.get(e.getKey()),fi,e.getValue()});
			if(!corners.isEmpty()){
				out.begin(Q3.fc("cntr"));
				simple(Q3.fc("crnr"),()->{out.u32(corners.size());for(Object[] c:corners){out.u32((Integer)c[0]);out.u32(1);out.u32((Integer)c[1]);}});
				for(Object[] c:corners)write((Q3.AttrSet)c[2]);
				out.end();
			}
		}
		private void style(Q3.Style s){
			int t=s.leaf();String n=Q3.fcName(t);
			if(t==Q3.HIGHLIGHT){out.begin(Q3.fc("cntr"));simple(t,()->{});if(s.highlight!=null)write(s.highlight);out.end();return;}
			simple(t,()->{
				switch(n){
					case "bckf":out.enumeration(s.value,"Both","Remove","Flipped");break;
					case "intp":out.enumeration(s.value,"None","Vertex","Pixel");break;
					case "fist":out.enumeration(s.value,"Filled","Edges","Points");break;
					case "ofdr":out.enumeration(s.value,"CounterClockwise","Clockwise");break;
					case "pkpt":out.flags(s.value,"Face","Edge","Vertex");break;
					case "rcsh":out.enumeration(s.value!=0?1:0,BOOL);break;
					case "sbdv":{int m=(int)s.data[0];out.enumeration(m,"Constant","WorldSpace","ScreenSpace");out.f32(s.data[1]);if(m==0)out.f32(s.data[2]);break;}
					case "anti":out.enumeration((int)s.data[0],"Off","On");out.flags((int)s.data[1],"Edges","Filled");out.f32(s.data[2]);break;
					case "fogg":out.enumeration((int)s.data[0],"Off","On");out.enumeration((int)s.data[1],"Linear","Exponential","ExponentialSquared","Alpha");for(int i=2;i<9;i++)out.f32(s.data[i]);break;
					default:out.u32(s.value);break;
				}});
		}
		private void shader(Q3.Shader s){
			int t=s.leaf();
			if(t!=Q3.TEXTURE_SHADER){simple(t,()->{});return;}
			out.begin(Q3.fc("cntr"));simple(t,()->{});
			if(s.texture!=null)write(s.texture);
			if(s.uBoundary!=0||s.vBoundary!=0)simple(Q3.fc("shdr"),()->{out.enumeration(s.uBoundary,"Wrap","Clamp");out.enumeration(s.vBoundary,"Wrap","Clamp");});
			if(s.uvTransform!=null)simple(Q3.fc("sduv"),()->out.floats(s.uvTransform));
			out.end();
		}
		private void texture(Q3.Texture x){
			if(x.pixmap!=null){simple(Q3.PIXMAP_TEXTURE,()->pixmap(x.pixmap));return;}
			if(x.levels.length>0){Q3.MipLevel l=x.levels[0];
				simple(Q3.MIPMAP_TEXTURE,()->{out.enumeration(x.useMipmapping?1:0,BOOL);out.enumeration(x.pixelType,PIXEL);out.enumeration(x.bitOrder,ENDIAN);out.enumeration(x.byteOrder,ENDIAN);
					out.u32(l.width);out.u32(l.height);out.u32(l.rowBytes);out.u32(0);
					byte[] img;try{img=x.mipImage==null?new byte[0]:x.mipImage.bytes();}catch(Exception e){img=new byte[0];}
					int total=0;if(x.useMipmapping)for(Q3.MipLevel m:x.levels)total=Math.max(total,m.offset-l.offset+m.rowBytes*m.height);else total=l.rowBytes*l.height;
					byte[] part=java.util.Arrays.copyOfRange(img,Math.min(img.length,l.offset),Math.min(img.length,l.offset+total));out.bytes(part,total);});}
		}
		private void light(Q3.Light l){
			int t=l.leaf();
			out.begin(Q3.fc("cntr"));
			simple(t,()->{
				if(t==Q3.DIRECTIONAL){out.floats(l.direction);out.enumeration(l.castsShadows?1:0,BOOL);}
				else if(t==Q3.POINT_LIGHT){out.floats(l.location);out.floats(atten(l.attenuation));out.enumeration(l.castsShadows?1:0,BOOL);}
				else if(t==Q3.SPOT){out.floats(l.location);out.floats(l.direction);out.enumeration(l.castsShadows?1:0,BOOL);out.floats(atten(l.attenuation));out.f32(l.hotAngle);out.f32(l.outerAngle);out.enumeration(l.fallOff,"None","Linear","Exponential","Cosine");}});
			simple(Q3.fc("lght"),()->{out.enumeration(l.on?1:0,BOOL);out.f32(l.brightness);out.floats(l.color);});
			out.end();
		}
		private static float[] atten(int a){return a==Q3.ATTENUATION_INVERSE?new float[]{0,1,0}:a==Q3.ATTENUATION_INVERSE_SQUARED?new float[]{0,0,1}:new float[]{1,0,0};}
		private void camera(Q3.Camera c){
			int t=c.leaf();
			out.begin(Q3.fc("cntr"));
			simple(t,()->{if(t==Q3.ORTHOGRAPHIC)out.floats(c.left,c.top,c.right,c.bottom);else if(t==Q3.VIEW_PLANE)out.floats(c.viewPlane,c.halfWidth,c.halfHeight,c.centerX,c.centerY);else out.floats(c.fov,c.aspect);});
			simple(Q3.fc("cmpl"),()->{out.floats(c.location);out.floats(c.pointOfInterest);out.floats(c.up);});
			simple(Q3.fc("cmrg"),()->out.floats(c.hither,c.yon));
			simple(Q3.fc("cmvp"),()->out.floats(c.vpX,c.vpY,c.vpWidth,c.vpHeight));
			out.end();
		}
	}
}

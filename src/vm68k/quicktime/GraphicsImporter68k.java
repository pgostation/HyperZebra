import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The native graphics importer components ('grip' PNGf, JPEG, GIFf, BMPf, TIFF, PICT) of the native Movie Toolbox, present
 * only when no QuickTime extension is installed. Programs that draw their artwork from PNG resources (iTunes: 'PNGf' 5000...)
 * open one with OpenADefaultComponent, give it the data (GraphicsImportSetDataHandle / SetDataFile), ask for the natural
 * bounds and draw it into a GWorld. Decoding is Java's ImageIO; the pixels are copied (srcCopy: the alpha channel included)
 * into a GWorld, composited (srcOver) into anything else. A PICT is played by the program's own QuickDraw (DrawPicture into
 * the importer's port), as QuickTime's PICT importer does. Whatever the file is called or typed, the data decides which
 * importer serves it: MacBeat's skin has PICT files named and typed as PNG (Star.png, 'PNGf') and draws them this way.
 */
final class GraphicsImporter68k implements Components68k.Native {
	static final int GRIP=0x67726970,APPL=0x6170706c;
	/** Subtypes served: the file/data types QuickTime's importers carry. */
	static final int[] SUBTYPES={0x504e4766 /* PNGf */,0x4a504547 /* JPEG */,0x47494666 /* GIFf */,0x424d5066 /* BMPf */,0x54494646 /* TIFF */,0x50494354 /* PICT */};
	static final int PARAM_ERR=-50,NO_DATA_ERR=-2014,CANT_FIND_HANDLER=-2003,BAD_SELECTOR=0x80008002;
	private static final long MAX_FILE=64L<<20;

	private static final class State {
		byte[] data,picture;BufferedImage image;boolean decoded; // picture: the Picture of PICT data (no 512-byte file header)
		int port,device;
		int[] source,bounds,dest;   // {top,left,bottom,right} or null
		int[] matrix;               // nine Fixed/Fract values or null
		int mode=0x40,quality=0x200,flags,imageIndex=1;int[] opColor={0,0,0};
	}
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,State> states=new HashMap<Integer,State>();

	GraphicsImporter68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	static void register(Components68k components,Toolbox68k tb){
		GraphicsImporter68k importer=new GraphicsImporter68k(tb);
		for(int subType:SUBTYPES)components.registerNative(GRIP,subType,APPL,0,importer);
	}

	/** The importer a data block belongs to (GetGraphicsImporterForFile): by its first bytes, else by the file name. */
	static int subTypeOf(byte[] head,String name){
		if(head!=null&&head.length>=8){
			if((head[0]&0xff)==0x89&&head[1]=='P'&&head[2]=='N'&&head[3]=='G')return SUBTYPES[0];
			if((head[0]&0xff)==0xff&&(head[1]&0xff)==0xd8)return SUBTYPES[1];
			if(head[0]=='G'&&head[1]=='I'&&head[2]=='F')return SUBTYPES[2];
			if(head[0]=='B'&&head[1]=='M')return SUBTYPES[3];
			if((head[0]=='I'&&head[1]=='I'&&head[2]==42&&head[3]==0)||(head[0]=='M'&&head[1]=='M'&&head[2]==0&&head[3]==42))return SUBTYPES[4];
			if(pictureOf(head)!=null)return SUBTYPES[5];
		}
		String n=name==null?"":name.toLowerCase();
		if(n.endsWith(".png"))return SUBTYPES[0];
		if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return SUBTYPES[1];
		if(n.endsWith(".gif"))return SUBTYPES[2];
		if(n.endsWith(".bmp"))return SUBTYPES[3];
		if(n.endsWith(".tif")||n.endsWith(".tiff"))return SUBTYPES[4];
		if(n.endsWith(".pict")||n.endsWith(".pct")||n.endsWith(".pic"))return SUBTYPES[5];
		return 0;
	}
	/** The Picture in PICT data: a PICT file (512-byte header) or a bare Picture (a PicHandle's contents); null when it is neither. */
	static byte[] pictureOf(byte[] data){
		if(data==null)return null;
		byte[] picture=PictureFile68k.pictureOf(data);if(picture!=null)return picture;
		if(data.length<14)return null;
		int top=(short)((data[2]&0xff)<<8|data[3]&0xff),left=(short)((data[4]&0xff)<<8|data[5]&0xff),bottom=(short)((data[6]&0xff)<<8|data[7]&0xff),right=(short)((data[8]&0xff)<<8|data[9]&0xff);
		boolean v1=data[10]==0x11&&data[11]==0x01,v2=data[10]==0x00&&data[11]==0x11&&data[12]==0x02&&(data[13]&0xff)==0xff;
		return (v1||v2)&&right>left&&bottom>top?data:null;
	}

	@Override public void closed(int instance){states.remove(instance);}

	@Override public int call(int ci,int what,int p) throws M68kException {
		State s=states.get(ci);if(s==null){s=new State();states.put(ci,s);}
		switch(what){
			case 0x03:{int spec=memory.readLong(p);File f;try{f=tb.files.fileForSpec(spec);}catch(IOException e){f=null;}
				return f==null?-43:setFile(s,f);} // SetDataFile(ci, FSSpec*)
			case 0x01:{Reference r=reference(tb,memory.readLong(p+4),memory.readLong(p));if(r==null)return CANT_FIND_HANDLER;if(r.data==null)return r.err;setData(s,r.data);return 0;} // SetDataReference(ci, dataRef, dataRefType)
			case 0x05:{int h=memory.readLong(p);byte[] b=h==0?null:tb.api.handleContents(h);if(b==null)return PARAM_ERR;setData(s,b);return 0;} // SetDataHandle(ci, Handle)
			case 0x06:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,s.data==null?0:tb.api.createHandle(s.data.clone()));return 0;} // GetDataHandle(ci, Handle*)
			case 0x07:{int out=memory.readLong(p);BufferedImage image=image(s);if(image==null)return NO_DATA_ERR;if(out!=0)memory.writeLong(out,tb.api.createHandle(description(s,image)));return 0;} // GetImageDescription(ci, ImageDescriptionHandle*)
			case 0x08:{int size=memory.readLong(p),offset=memory.readLong(p+4);if(offset!=0)memory.writeLong(offset,0);if(size!=0)memory.writeLong(size,s.data==null?0:s.data.length);return 0;} // GetDataOffsetAndSize
			case 0x0a:return 0; // SetClip(ci, RgnHandle)
			case 0x0b:case 0x33:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,0);return 0;} // GetClip / GetDefaultClip: no clip region
			case 0x0c:s.source=readRect(memory.readLong(p));return 0; // SetSourceRect
			case 0x0d:{int[] n=natural(s);if(n==null)return NO_DATA_ERR;writeRect(memory.readLong(p),s.source!=null?s.source:n);return 0;} // GetSourceRect
			case 0x35:{int[] n=natural(s);if(n==null)return NO_DATA_ERR;writeRect(memory.readLong(p),n);return 0;} // GetDefaultSourceRect
			case 0x0e:{int[] n=natural(s);if(n==null)return NO_DATA_ERR;writeRect(memory.readLong(p),n);return 0;} // GetNaturalBounds(ci, Rect*)
			case 0x0f:return draw(s); // Draw
			case 0x10:s.device=memory.readLong(p);s.port=memory.readLong(p+4);return 0; // SetGWorld(ci, port, gdh)
			case 0x11:{int gd=memory.readLong(p),port=memory.readLong(p+4);if(port!=0)memory.writeLong(port,s.port!=0?s.port:tb.api.currentQuickDrawPort());if(gd!=0)memory.writeLong(gd,s.device);return 0;} // GetGWorld
			case 0x12:{int m=memory.readLong(p);s.matrix=m==0?null:readMatrix(m);s.bounds=null;s.dest=null;return 0;} // SetMatrix
			case 0x13:{int m=memory.readLong(p);if(m!=0)writeMatrix(m,s.matrix!=null?s.matrix:identity());return 0;} // GetMatrix
			case 0x32:{int m=memory.readLong(p);if(m!=0)writeMatrix(m,identity());return 0;} // GetDefaultMatrix
			case 0x14:s.bounds=readRect(memory.readLong(p));return 0; // SetBoundsRect
			case 0x15:{int[] n=natural(s);if(n==null)return NO_DATA_ERR;writeRect(memory.readLong(p),destination(s,n));return 0;} // GetBoundsRect
			case 0x37:s.dest=readRect(memory.readLong(p));return 0; // SetDestRect
			case 0x38:{int[] n=natural(s);if(n==null)return NO_DATA_ERR;writeRect(memory.readLong(p),destination(s,n));return 0;} // GetDestRect
			case 0x17:s.mode=memory.readLong(p+4);{int rgb=memory.readLong(p);if(rgb!=0)s.opColor=new int[]{memory.readWord(rgb),memory.readWord(rgb+2),memory.readWord(rgb+4)};}return 0; // SetGraphicsMode(ci, mode, RGBColor*)
			case 0x18:{int rgb=memory.readLong(p),mode=memory.readLong(p+4);if(mode!=0)memory.writeLong(mode,s.mode);if(rgb!=0){memory.writeWord(rgb,s.opColor[0]);memory.writeWord(rgb+2,s.opColor[1]);memory.writeWord(rgb+4,s.opColor[2]);}return 0;} // GetGraphicsMode
			case 0x34:{int rgb=memory.readLong(p),mode=memory.readLong(p+4);if(mode!=0)memory.writeLong(mode,0x40);if(rgb!=0){memory.writeWord(rgb,0);memory.writeWord(rgb+2,0);memory.writeWord(rgb+4,0);}return 0;} // GetDefaultGraphicsMode
			case 0x19:s.quality=memory.readLong(p);return 0; // SetQuality
			case 0x1a:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,s.quality);return 0;} // GetQuality
			case 0x1f:{int out=memory.readLong(p);if(out!=0)memory.writeByte(out,natural(s)!=null?1:0);return 0;} // Validate(ci, Boolean*)
			case 0x22:{int out=memory.readLong(p);BufferedImage image=s.picture!=null?null:image(s);if(out!=0)memory.writeWord(out,image!=null&&!image.getColorModel().hasAlpha()?1:0);return 0;} // DoesDrawAllPixels(ci, short*): a picture need not cover its frame
			case 0x23:{int out=memory.readLong(p);if(s.picture!=null){if(out!=0)memory.writeLong(out,tb.api.createHandle(s.picture.clone()));return 0;}BufferedImage image=image(s);if(image==null)return NO_DATA_ERR;try{if(out!=0)memory.writeLong(out,tb.api.createHandle(HCResourceExport.pict(image)));}catch(IOException e){return NO_DATA_ERR;}return 0;} // GetAsPicture(ci, PicHandle*)
			case 0x29:return 0; // SetProgressProc
			case 0x2a:{int out=memory.readLong(p);if(out!=0){memory.writeLong(out,0);memory.writeLong(out+4,0);}return 0;} // GetProgressProc(ci, ICMProgressProcRecord*)
			case 0x2b:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,1);return 0;} // GetImageCount
			case 0x2c:s.imageIndex=memory.readLong(p);return s.imageIndex==1?0:PARAM_ERR; // SetImageIndex
			case 0x2d:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,s.imageIndex);return 0;} // GetImageIndex
			case 0x39:s.flags=memory.readLong(p);return 0; // SetFlags
			case 0x3a:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,s.flags);return 0;} // GetFlags
			default:return BAD_SELECTOR;
		}
	}

	// ------------------------------------------------------------------ the image

	/** Gives the importer instance {@code ci} a file's contents (GetGraphicsImporterForFile). */
	int setFile(int ci,File f){State s=states.get(ci);if(s==null){s=new State();states.put(ci,s);}return setFile(s,f);}
	/** Gives the importer instance {@code ci} data read through a data reference (GetGraphicsImporterForDataRef). */
	void setData(int ci,byte[] data){State s=states.get(ci);if(s==null){s=new State();states.put(ci,s);}setData(s,data);}

	static final int HANDLE_REF=0x686e646c /* hndl */,POINTER_REF=0x70747220 /* ptr  */,ALIAS_REF=0x616c6973 /* alis */;
	/** What a data reference leads to: the bytes and a file name to guess the format from, or the OSErr reading failed with. */
	static final class Reference {
		final byte[] data;final String name;final int err;
		Reference(byte[] data,String name,int err){this.data=data;this.name=name;this.err=err;}
	}
	/**
	 * The data of a handle ('hndl': the Handle, then optionally the file name extension as a Pascal string), pointer ('ptr ':
	 * PointerDataRefRecord {data, dataLength}) or alias ('alis') data reference; null for kinds that are not read here (URLs).
	 * OS9Map hands each map tile it downloads to GetGraphicsImporterForDataRef as a bare handle reference without a name.
	 */
	static Reference reference(Toolbox68k tb,int dataRef,int type) throws M68kException {
		byte[] ref=dataRef==0?null:tb.api.handleContents(dataRef);
		if(ref==null)return new Reference(null,null,PARAM_ERR);
		switch(type){
			case HANDLE_REF:{
				if(ref.length<4)return new Reference(null,null,PARAM_ERR);
				int h=(ref[0]&0xff)<<24|(ref[1]&0xff)<<16|(ref[2]&0xff)<<8|ref[3]&0xff;
				byte[] data=h==0?null:tb.api.handleContents(h);
				String name=ref.length>4&&(ref[4]&0xff)>0&&ref.length>=5+(ref[4]&0xff)?new String(ref,5,ref[4]&0xff,java.nio.charset.Charset.forName("x-MacRoman")):null;
				return data==null?new Reference(null,null,PARAM_ERR):new Reference(data,name,0);}
			case POINTER_REF:{
				if(ref.length<8)return new Reference(null,null,PARAM_ERR);
				int p=(ref[0]&0xff)<<24|(ref[1]&0xff)<<16|(ref[2]&0xff)<<8|ref[3]&0xff,n=(ref[4]&0xff)<<24|(ref[5]&0xff)<<16|(ref[6]&0xff)<<8|ref[7]&0xff;
				if(p==0||n<0||n>MAX_FILE)return new Reference(null,null,PARAM_ERR);
				return new Reference(tb.memory.readBytes(p,n),null,0);}
			case ALIAS_REF:{
				File f=tb.files.resolveAlias(ref);
				if(f==null||!f.isFile())return new Reference(null,null,-43);
				try{if(f.length()>MAX_FILE)return new Reference(null,null,-108);return new Reference(Files.readAllBytes(f.toPath()),f.getName(),0);}
				catch(IOException e){return new Reference(null,null,-36);}}
			default:return null;
		}
	}
	/** The picture in a file's data fork (PICT, or a host image such as the PNG a saved PICT becomes) as ARGB, or null. */
	static BufferedImage imageOfFile(Toolbox68k tb,File f){GraphicsImporter68k g=new GraphicsImporter68k(tb);State s=new State();return g.setFile(s,f)!=0?null:g.image(s);}
	private int setFile(State s,File f){
		if(!f.isFile())return -43;
		try{if(f.length()>MAX_FILE)return -108;setData(s,Files.readAllBytes(f.toPath()));}catch(IOException e){return -36;}
		return 0;
	}
	private void setData(State s,byte[] data){s.data=data;s.image=null;s.decoded=false;s.imageIndex=1;s.picture=subTypeOf(data,null)==SUBTYPES[5]?pictureOf(data):null;}
	/** The natural bounds {top,left,bottom,right} at 0,0 (a picture's frame size, an image's size); null when the data is no picture. */
	private int[] natural(State s){
		if(s.picture!=null){byte[] p=s.picture;int top=(short)((p[2]&0xff)<<8|p[3]&0xff),left=(short)((p[4]&0xff)<<8|p[5]&0xff),bottom=(short)((p[6]&0xff)<<8|p[7]&0xff),right=(short)((p[8]&0xff)<<8|p[9]&0xff);return new int[]{0,0,bottom-top,right-left};}
		BufferedImage image=image(s);return image==null?null:natural(image);
	}
	/** The decoded image as ARGB (null when the data is not a picture ImageIO reads; a PICT played into a 32-bit world). */
	private BufferedImage image(State s){
		if(s.decoded)return s.image;
		s.decoded=true;if(s.data==null)return null;
		if(s.picture!=null){
			try{BufferedImage rgb=tb.api.quickDraw().renderPicture(s.picture);if(rgb==null)return null;
				BufferedImage argb=new BufferedImage(rgb.getWidth(),rgb.getHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D g=argb.createGraphics();g.drawImage(rgb,0,0,null);g.dispose();s.image=argb;}
			catch(M68kCpuException|RuntimeException e){s.image=null;}
			return s.image;
		}
		try{
			BufferedImage read=javax.imageio.ImageIO.read(new ByteArrayInputStream(s.data));
			if(read==null)return null;
			BufferedImage argb=new BufferedImage(read.getWidth(),read.getHeight(),BufferedImage.TYPE_INT_ARGB);
			Graphics2D g=argb.createGraphics();g.setComposite(AlphaComposite.Src);g.drawImage(read,0,0,null);g.dispose();
			s.image=argb;
		}catch(IOException|RuntimeException e){s.image=null;}
		return s.image;
	}
	private static int[] natural(BufferedImage image){return new int[]{0,0,image.getHeight(),image.getWidth()};}

	/** Where Draw puts the source rectangle: the bounds/dest rectangle, else the matrix's image of it, else itself. */
	private int[] destination(State s,int[] natural){
		if(s.bounds!=null)return s.bounds;
		if(s.dest!=null)return s.dest;
		int[] src=source(s,natural);
		if(s.matrix==null)return src;
		double a=s.matrix[0]/65536.0,b=s.matrix[1]/65536.0,c=s.matrix[3]/65536.0,d=s.matrix[4]/65536.0,tx=s.matrix[6]/65536.0,ty=s.matrix[7]/65536.0;
		double[] xs=new double[4],ys=new double[4];int[][] corners={{src[1],src[0]},{src[3],src[0]},{src[1],src[2]},{src[3],src[2]}};
		double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
		for(int i=0;i<4;i++){double x=corners[i][0],y=corners[i][1];double nx=x*a+y*c+tx,ny=x*b+y*d+ty;minX=Math.min(minX,nx);minY=Math.min(minY,ny);maxX=Math.max(maxX,nx);maxY=Math.max(maxY,ny);}
		return new int[]{(int)Math.round(minY),(int)Math.round(minX),(int)Math.round(maxY),(int)Math.round(maxX)};
	}
	private int[] source(State s,int[] natural){
		int[] src=s.source!=null?s.source.clone():natural.clone();
		src[0]=Math.max(0,src[0]);src[1]=Math.max(0,src[1]);src[2]=Math.min(natural[2],src[2]);src[3]=Math.min(natural[3],src[3]);
		return src;
	}

	private int draw(State s) throws M68kException {
		int[] natural=natural(s);if(natural==null)return NO_DATA_ERR;
		int port=s.port!=0?s.port:tb.api.currentQuickDrawPort();if(port==0)return PARAM_ERR;
		int[] src=source(s,natural),dest=destination(s,natural);
		if(src[2]<=src[0]||src[3]<=src[1]||dest[2]<=dest[0]||dest[3]<=dest[1])return 0;
		if(s.picture!=null&&java.util.Arrays.equals(src,natural)){tb.api.quickDraw().drawPictureInto(port,s.device,s.picture,dest);return 0;} // the whole picture: DrawPicture, scaled to dest
		BufferedImage image=image(s);if(image==null)return NO_DATA_ERR;
		blit(tb,port,image,src,dest);
		return 0;
	}
	/** Draws the {top,left,bottom,right} part {@code src} of {@code image} into {@code dest} of {@code port}: srcCopy (alpha included) into a GWorld, srcOver elsewhere. */
	static void blit(Toolbox68k tb,int port,BufferedImage image,int[] src,int[] dest) throws M68kException {
		boolean offscreen=tb.api.quickDraw().isGWorld(port);
		Graphics2D g=offscreen?tb.quickDraw.alphaSurface(port,dest):tb.quickDraw.surface(port,dest);
		if(g==null)return;
		try{
			g.setComposite(offscreen?AlphaComposite.Src:AlphaComposite.SrcOver);
			boolean scaled=dest[3]-dest[1]!=src[3]-src[1]||dest[2]-dest[0]!=src[2]-src[0];
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,scaled?RenderingHints.VALUE_INTERPOLATION_BILINEAR:RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.drawImage(image,dest[1],dest[0],dest[3],dest[2],src[1],src[0],src[3],src[2],null);
		}finally{g.dispose();}
	}

	/** An ImageDescription (86 bytes, no extensions) for the decoded image. */
	private byte[] description(State s,BufferedImage image){
		byte[] d=new byte[86];int type=subTypeOfData(s);
		put(d,0,86);put(d,4,type);put(d,12,0);put(d,16,0);put(d,20,0);put(d,24,0x200);put(d,28,0x400);
		putShort(d,32,image.getWidth());putShort(d,34,image.getHeight());put(d,36,0x00480000);put(d,40,0x00480000);put(d,44,s.data==null?0:s.data.length);putShort(d,48,1);
		putShort(d,82,s.picture==null&&image.getColorModel().hasAlpha()?32:24);putShort(d,84,-1);
		return d;
	}
	private int subTypeOfData(State s){int sub=subTypeOf(s.data,null);return sub==0?0x504e4766:sub;}
	private static void put(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}
	private static void putShort(byte[] b,int at,int v){b[at]=(byte)(v>>>8);b[at+1]=(byte)v;}

	// ------------------------------------------------------------------ guest structures

	private int[] readRect(int address) throws M68kCpuException {
		if(address==0)return null;
		return new int[]{(short)memory.readWord(address),(short)memory.readWord(address+2),(short)memory.readWord(address+4),(short)memory.readWord(address+6)};
	}
	private void writeRect(int address,int[] r) throws M68kCpuException {
		if(address==0)return;
		memory.writeWord(address,r[0]);memory.writeWord(address+2,r[1]);memory.writeWord(address+4,r[2]);memory.writeWord(address+6,r[3]);
	}
	private int[] readMatrix(int address) throws M68kCpuException {int[] m=new int[9];for(int i=0;i<9;i++)m[i]=memory.readLong(address+i*4);return m;}
	private void writeMatrix(int address,int[] m) throws M68kCpuException {for(int i=0;i<9;i++)memory.writeLong(address+i*4,m[i]);}
	private static int[] identity(){return new int[]{0x10000,0,0,0,0x10000,0,0,0,0x40000000};}
}

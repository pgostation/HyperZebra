import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The part of the Image Compression Manager that hosts a program's own image decompressor component (type 'imdc'): iTunes
 * registers one for its backdrop textures and draws every window and control through DecompressSequenceBegin / Frame / End.
 * The sequence opens the codec for the image description's type, runs ImageCodecInitialize and Preflight once, and then for each
 * frame the band calls (BeginBand, DrawBand, EndBand) into a 32-bit buffer that is drawn into the sequence's port through the
 * matrix. Components of QuickTime's own codecs are not involved: the base decompressor the codec delegates to is a native
 * component that accepts the calls (see {@link #registerBase}).
 */
final class DecompressSequence68k {
	static final int IMDC=0x696d6463,BASE=0x62617365,APPL=0x6170706c;
	static final int INITIALIZE=0x201,PREFLIGHT=0x200,BEGIN_BAND=0x202,DRAW_BAND=0x203,END_BAND=0x204;
	static final int PARAMS_SIZE=226,CAPABILITIES_SIZE=24,SUB_CAPS_SIZE=16,RECORD_SIZE=44;
	private static final boolean TRACE=Boolean.getBoolean("hz68k.icmtrace");

	private final class Sequence {
		int id,ci,params,caps,desc,port,mask,mode,subRecordSize,frames;
		int[] src;int[] matrix;int width,height,dataSize,wantedDepth=32;
	}
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,Sequence> sequences=new HashMap<Integer,Sequence>();
	private int nextId=0x00510000;

	DecompressSequence68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** The default decompressor a codec delegates to: it accepts every call (the codec does the work). */
	static void registerBase(Components68k components){
		components.registerNative(IMDC,BASE,APPL,0,new Components68k.Native(){
			@Override public int call(int instance,int what,int params){
				if(TRACE)System.err.println(String.format("[icm] base codec selector $%X",what&0xffff));
				return 0;
			}
		});
	}

	// ------------------------------------------------------------------ DecompressSequenceBegin

	int begin(int seqPtr,int desc,int port,int gdh,int srcRect,int matrix,int mode,int mask,int accuracy,int codec) throws M68kException {
		if(desc==0)return -50;
		int dp=memory.readLong(desc);if(dp==0)return -50;
		int cType=memory.readLong(dp+4);
		Components68k.Component component=codec!=0?tb.components.componentById(codec):null;
		if(component==null||component.type!=IMDC)component=tb.components.find(IMDC,cType);
		// only a program's own codec (registered with RegisterComponent): QuickTime's codecs are 68K components of the old calling style
		if(component==null||component.handler!=null||component.file!=null)return ImageCompression68k.CODEC_UNIMP_ERR;
		Sequence s=new Sequence();
		s.desc=desc;s.port=port!=0?port:tb.api.currentQuickDrawPort();s.mask=mask;s.mode=mode;
		s.width=memory.readWord(dp+32);s.height=memory.readWord(dp+34);s.dataSize=memory.readLong(dp+44);
		s.src=srcRect!=0?readRect(srcRect):new int[]{0,0,s.height,s.width};
		s.matrix=matrix!=0?readMatrix(matrix):null;
		s.ci=tb.components.open(component);
		if(s.ci==0)return ImageCompression68k.CODEC_UNIMP_ERR;
		// ImageCodecInitialize(ci, ImageSubCodecDecompressCapabilities *)
		int sub=memory.allocate(SUB_CAPS_SIZE,4);memory.fill(sub,SUB_CAPS_SIZE,0);memory.writeLong(sub,SUB_CAPS_SIZE);memory.writeByte(sub+8,1);
		int result=tb.components.call(s.ci,INITIALIZE,l(sub));
		s.subRecordSize=memory.readLong(sub+4);memory.free(sub);
		if(TRACE)System.err.println(String.format("[icm] codec %s Initialize -> %d, decompress record %d bytes",ResourceFile68k.typeName(cType),result,s.subRecordSize));
		if(result!=0&&result!=Components68k.BAD_COMPONENT_SELECTOR){tb.components.close(s.ci);return (short)result;}
		// the codec's view of the image: description, source rectangle, matrix, capabilities (filled by Preflight)
		s.params=memory.allocate(PARAMS_SIZE,4);memory.fill(s.params,PARAMS_SIZE,0);
		s.caps=memory.allocate(CAPABILITIES_SIZE,4);memory.fill(s.caps,CAPABILITIES_SIZE,0);
		fill(s);
		result=tb.components.call(s.ci,PREFLIGHT,l(s.params));
		if(TRACE)System.err.println(String.format("[icm] codec Preflight -> %d wanted pixel size %d flags $%X",result,(short)memory.readWord(s.caps+4),memory.readLong(s.caps)));
		if(result!=0&&result!=Components68k.BAD_COMPONENT_SELECTOR){free(s);tb.components.close(s.ci);return (short)result;}
		s.id=nextId+=0x10;sequences.put(s.id,s);
		if(seqPtr!=0)memory.writeLong(seqPtr,s.id);
		return 0;
	}

	/** The parts of CodecDecompressParams that do not change from frame to frame. */
	private void fill(Sequence s) throws M68kCpuException {
		int p=s.params;
		memory.writeLong(p,s.id);memory.writeLong(p+4,s.desc);
		memory.writeLong(p+34,s.caps);
		memory.writeLong(p+62,s.port);
		writeRect(p+124,s.src);
		memory.writeLong(p+136,0x200);memory.writeWord(p+140,s.mode);
		writeRect(p+152,s.src);
		memory.writeWord(p+184,32);
		memory.writeLong(p+198,s.port);
	}
	private void free(Sequence s){
		if(s.params!=0)memory.free(s.params);if(s.caps!=0)memory.free(s.caps);s.params=s.caps=0;
	}

	// ------------------------------------------------------------------ DecompressSequenceFrame / CDSequenceEnd

	int frame(int seqId,int data,int inFlags,int outFlagsPtr) throws M68kException {
		Sequence s=sequences.get(seqId);if(s==null)return -2012;
		int w=s.src[3]-s.src[1],h=s.src[2]-s.src[0];
		if(w<=0||h<=0)return 0;
		int rowBytes=w*4,bytes=rowBytes*h;
		int buffer=memory.allocate(bytes,4);memory.fill(buffer,bytes,0);
		int pixMap=s.params+66;
		memory.writeLong(pixMap,buffer);memory.writeWord(pixMap+4,0x8000|rowBytes);writeRect(pixMap+6,s.src);
		memory.writeWord(pixMap+14,0);memory.writeLong(pixMap+22,0x00480000);memory.writeLong(pixMap+26,0x00480000);
		memory.writeWord(pixMap+30,16);memory.writeWord(pixMap+32,32);memory.writeWord(pixMap+34,3);memory.writeWord(pixMap+36,8);
		memory.writeLong(s.params+8,data);memory.writeLong(s.params+12,s.dataSize);memory.writeLong(s.params+16,++s.frames);
		memory.writeLong(s.params+20,0);memory.writeLong(s.params+24,h);memory.writeWord(s.params+32,inFlags);
		int drpSize=RECORD_SIZE+Math.max(0,s.subRecordSize);
		int drp=memory.allocate(drpSize,4);memory.fill(drp,drpSize,0);
		memory.writeLong(drp,buffer);memory.writeLong(drp+4,rowBytes);memory.writeLong(drp+8,data);
		memory.writeLong(drp+28,drp+RECORD_SIZE);memory.writeByte(drp+32,1);
		int result=0;
		try{
			result=tb.components.call(s.ci,BEGIN_BAND,l(s.params),l(drp),l(0));
			if(TRACE)System.err.println(String.format("[icm] BeginBand -> %d",result));
			if(result==0||result==Components68k.BAD_COMPONENT_SELECTOR){
				result=tb.components.call(s.ci,DRAW_BAND,l(drp));
				if(TRACE)System.err.println(String.format("[icm] DrawBand -> %d",result));
				int end=tb.components.call(s.ci,END_BAND,l(drp),w16(result),l(0));
				if(TRACE)System.err.println(String.format("[icm] EndBand -> %d",end));
			}
			if(result==0)draw(s,buffer,rowBytes,w,h);
		}finally{memory.free(drp);memory.free(buffer);}
		if(outFlagsPtr!=0)memory.writeWord(outFlagsPtr,0);
		return result==Components68k.BAD_COMPONENT_SELECTOR?ImageCompression68k.CODEC_UNIMP_ERR:(short)result;
	}

	private void draw(Sequence s,int buffer,int rowBytes,int w,int h) throws M68kException {
		byte[] raw=memory.readBytes(buffer,rowBytes*h);
		BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		boolean anyAlpha=false;
		for(int i=0;i<w*h&&!anyAlpha;i++)anyAlpha=raw[i*4]!=0;
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int o=y*rowBytes+x*4;
			int a=anyAlpha?raw[o]&0xff:0xff;image.setRGB(x,y,a<<24|(raw[o+1]&0xff)<<16|(raw[o+2]&0xff)<<8|raw[o+3]&0xff);}
		int[] dest=destination(s);
		GraphicsImporter68k.blit(tb,s.port,image,new int[]{0,0,h,w},dest);
	}
	/** Where the source rectangle goes: its image under the matrix, or itself. */
	private int[] destination(Sequence s){
		int[] src=s.src;
		if(s.matrix==null)return src.clone();
		double a=s.matrix[0]/65536.0,b=s.matrix[1]/65536.0,c=s.matrix[3]/65536.0,d=s.matrix[4]/65536.0,tx=s.matrix[6]/65536.0,ty=s.matrix[7]/65536.0;
		int[][] corners={{src[1],src[0]},{src[3],src[0]},{src[1],src[2]},{src[3],src[2]}};
		double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
		for(int[] k:corners){double x=k[0],y=k[1];double nx=x*a+y*c+tx,ny=x*b+y*d+ty;minX=Math.min(minX,nx);minY=Math.min(minY,ny);maxX=Math.max(maxX,nx);maxY=Math.max(maxY,ny);}
		return new int[]{(int)Math.round(minY),(int)Math.round(minX),(int)Math.round(maxY),(int)Math.round(maxX)};
	}

	int end(int seqId) throws M68kException {
		Sequence s=sequences.remove(seqId);if(s==null)return -2012;
		free(s);tb.components.close(s.ci);
		return 0;
	}
	boolean has(int seqId){return sequences.containsKey(seqId);}

	// ------------------------------------------------------------------ helpers

	private static int[] l(int v){return new int[]{v,4};}
	private static int[] w16(int v){return new int[]{v,2};}
	private int[] readRect(int a) throws M68kCpuException {return new int[]{(short)memory.readWord(a),(short)memory.readWord(a+2),(short)memory.readWord(a+4),(short)memory.readWord(a+6)};}
	private void writeRect(int a,int[] r) throws M68kCpuException {memory.writeWord(a,r[0]);memory.writeWord(a+2,r[1]);memory.writeWord(a+4,r[2]);memory.writeWord(a+6,r[3]);}
	private int[] readMatrix(int a) throws M68kCpuException {int[] m=new int[9];for(int i=0;i<9;i++)m[i]=memory.readLong(a+i*4);return m;}
}

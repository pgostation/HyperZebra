import java.util.HashMap;
import java.util.Map;

/**
 * The Image Compression Manager dispatch ($AAA3, D0 = selector 0..$46, moveq form). CompressImage, FCompressImage,
 * GetMaxCompressionSize and CompressSequenceBegin/Frame compress with the codecs of {@link ImageCodecs68k} ('smc ', 'rle ',
 * 'raw ', 'jpeg'), CompressPicture(File) with those and Photo JPEG/PNG ({@link PictureCompression68k}), decompression
 * sequences are {@link DecompressSequence68k}'s; other calls return noCodecErr, so a program that asks (iTunes starts its
 * image sequences at launch) gets an error result it can handle instead of stopping with an unsupported trap. The graphics importer lookups
 * (GetGraphicsImporterForFile) are answered by the native importers of {@link GraphicsImporter68k}, and the
 * Standard File preview calls go to the plain Standard File dialogs.
 */
final class ImageCompression68k {
	static final int CODEC_UNIMP_ERR=-8961;
	/** Argument bytes of each selector (Pascal stack), from ImageCompression.h; a negative size is a procedure (no OSErr result). */
	private static final Map<Integer,Integer> ARGS=new HashMap<Integer,Integer>();
	static{
		int[][] t={{0x00,4},{0x01,6},{0x03,12},{0x04,26},{0x05,30},{0x06,24},{0x07,48},{0x08,26},{0x09,54},{0x0a,52},{0x0b,30},{0x0c,-28},{0x0d,40},{0x0e,18},{0x0f,4},
			{0x10,8},{0x11,12},{0x12,8},{0x13,10},{0x14,12},{0x15,8},{0x16,8},{0x17,12},{0x18,12},{0x19,8},{0x1a,4},{0x1b,4},{0x1c,20},{0x1d,20},{0x1e,8},{0x1f,8},
			{0x20,16},{0x21,12},{0x23,16},{0x24,16},{0x25,34},{0x26,12},{0x27,30},{0x28,10},{0x29,10},{0x2a,14},{0x2b,12},{0x2c,18},{0x2d,36},{0x2e,18},{0x2f,16},
			{0x30,38},{0x33,12},{0x34,8},{0x35,8},{0x36,8},{0x37,24},{0x38,24},{0x39,8},{0x41,-26},{0x42,-32},{0x43,-14},{0x44,-40},{0x45,6},{0x46,10}};
		for(int[] e:t)ARGS.put(e[0],e[1]);
	}
	private final Toolbox68k tb;
	private final M68kMemory memory;

	private DecompressSequence68k sequences;
	ImageCompression68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}
	private DecompressSequence68k sequences(){if(sequences==null)sequences=new DecompressSequence68k(tb);return sequences;}

	boolean handle(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xff,sp=cpu.a[7];
		if(Mac68kApi.TRACE||QuickTime68k.QT_TRACE)System.err.println(String.format("[icm] selector $%02X args %08X %08X %08X %08X",selector,memory.readLong(sp),memory.readLong(sp+4),memory.readLong(sp+8),memory.readLong(sp+12)));
		if(selector==0x0d&&(Mac68kApi.TRACE||QuickTime68k.QT_TRACE)){int desc=memory.readLong(sp+32),dp=desc==0?0:memory.readLong(desc);
			if(dp!=0)System.err.println(String.format("[icm]  DecompressSequenceBegin desc cType=%08X (%s) %dx%d depth %d dataSize %d",memory.readLong(dp+4),new String(new byte[]{(byte)memory.readByte(dp+4),(byte)memory.readByte(dp+5),(byte)memory.readByte(dp+6),(byte)memory.readByte(dp+7)}),memory.readWord(dp+32),memory.readWord(dp+34),memory.readWord(dp+82),memory.readLong(dp+44)));}
		// SFGetFilePreview, SFPGetFilePreview, StandardGetFilePreview, CustomGetFilePreview take the arguments of Pack3's SFGetFile,
		// SFPGetFile, StandardGetFile and CustomGetFile in the same order: the Standard File dialog without the preview pane
		// (Strata StudioPro 1.1 opens its models with SFPGetFilePreview; nothing came up before)
		if(selector>=0x41&&selector<=0x44){cpu.a[7]-=2;memory.writeWord(cpu.a[7],new int[]{2,4,6,8}[selector-0x41]);return tb.standardFile.pack3(cpu);}
		Integer size=ARGS.get(selector);
		if(size==null&&selector>=0x80&&(selector<=0x8f)){
			int argBytes=cpu.d[0]>>>16;
			switch(selector){
				case 0x80:{ // QTNewGWorld(VAR gworld, pixelFormat, boundsRect, cTable, gdh, flags) : OSErr
					int format=memory.readLong(sp+16);int depth=pixelFormatDepth(format);
					int frame=cpu.a[7]-22;cpu.a[7]=frame;
					memory.writeLong(frame,memory.readLong(sp));memory.writeLong(frame+4,memory.readLong(sp+4));memory.writeLong(frame+8,memory.readLong(sp+8));
					memory.writeLong(frame+12,memory.readLong(sp+12));memory.writeWord(frame+16,depth);memory.writeLong(frame+18,memory.readLong(sp+20));
					memory.writeWord(frame+22,0);
					tb.api.quickDraw().newGWorld(cpu);
					int err=(short)memory.readWord(cpu.a[7]);cpu.a[7]=sp;
					cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],err);return true;}
				case 0x8d:case 0x8e:{ // QTGetPixMapPtrRowBytes(pixMap) / QTGetPixMapHandleRowBytes(pixMapHandle) : long
					int pm=memory.readLong(sp);if(selector==0x8e&&pm!=0)pm=memory.readLong(pm);
					int rowBytes=pm==0?0:memory.readWord(pm+4)&0x3fff;
					cpu.a[7]+=4;memory.writeLong(cpu.a[7],rowBytes);cpu.d[0]=rowBytes;return true;}
				case 0x86:{int pm=memory.readLong(sp);if(pm!=0)pm=memory.readLong(pm);cpu.a[7]+=4;memory.writeWord(cpu.a[7],pm==0?0:memory.readWord(pm+32));return true;} // QTGetPixelSize(pixMapHandle) : short
				default:break;
			}
		}
		if(size==null){
			int argBytes=cpu.d[0]>>>16; // the selectors added in QuickTime 3 and later come with their argument size in the high word
			if(argBytes==0||(argBytes&1)!=0)throw new M68kMacApiException(cpu.pc,0xaaa3,String.format("Image Compression Manager selector $%02X",selector));
			int err=CODEC_UNIMP_ERR;
			if(selector==0x6e||selector==0x84){ // GetGraphicsImporterForFile(file, VAR gi) / ...WithFlags(file, VAR gi, flags)
				int at=selector==0x84?4:0;err=importerForFile(memory.readLong(sp+at+4),memory.readLong(sp+at));
			}else if(selector==0x77||selector==0x85){ // GetGraphicsImporterForDataRef(dataRef, dataRefType, VAR gi) / ...WithFlags(..., flags)
				int at=selector==0x85?4:0;err=importerForDataRef(memory.readLong(sp+at+8),memory.readLong(sp+at+4),memory.readLong(sp+at));
			}
			cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],err);return true;
		}
		if(selector==0x00){int p=memory.readLong(sp);if(p!=0)memory.writeLong(p,version());cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true;} // CodecManagerVersion(VAR version)
		int err=CODEC_UNIMP_ERR;
		switch(selector){
			case 0x0d:{ // DecompressSequenceBegin(VAR seqID, desc, port, gdh, srcRect, matrix, mode, mask, flags, accuracy, codec)
				int r=sequences().begin(memory.readLong(sp+36),memory.readLong(sp+32),memory.readLong(sp+28),memory.readLong(sp+24),memory.readLong(sp+20),memory.readLong(sp+16),(short)memory.readWord(sp+14),memory.readLong(sp+10),memory.readLong(sp+4),memory.readLong(sp));
				err=r;break;}
			case 0x0e:{ // DecompressSequenceFrame(seqID, data, inFlags, VAR outFlags, completion)
				int id=memory.readLong(sp+14);
				if(sequences().has(id))err=sequences().frame(id,memory.readLong(sp+10),memory.readWord(sp+8),memory.readLong(sp+4));break;}
			case 0x1b:{int id=memory.readLong(sp);if(sequences().has(id))err=sequences().end(id);else if(compressions.remove(id)!=null)err=0;break;} // CDSequenceEnd
			case 0x04:{ // GetMaxCompressionSize(src, srcRect, colorDepth, quality, cType, codec, VAR size)
				int sizePtr=memory.readLong(sp),cType=memory.readLong(sp+8);int[] r=rect(memory.readLong(sp+18));
				if(!ImageCodecs68k.supported(cType))break;
				if(sizePtr!=0)memory.writeLong(sizePtr,ImageCodecs68k.maxSize(cType,r[3]-r[1],r[2]-r[0]));err=0;break;}
			case 0x06:err=compressImage(memory.readLong(sp+20),memory.readLong(sp+16),memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp),null);break; // CompressImage(src, srcRect, quality, cType, desc, data)
			case 0x07:err=compressImage(memory.readLong(sp+44),memory.readLong(sp+40),memory.readLong(sp+34),memory.readLong(sp+30),memory.readLong(sp+4),memory.readLong(sp),null);break; // FCompressImage(src, srcRect, colorDepth, quality, cType, codec, ctable, flags, bufferSize, progress, completion, desc, data)
			case 0x0a:{ // CompressSequenceBegin(VAR seqID, src, prev, srcRect, prevRect, colorDepth, cType, codec, spatialQuality, temporalQuality, keyFrameRate, ctable, flags, desc)
				int cType=memory.readLong(sp+26);if(!ImageCodecs68k.supported(cType))break;
				int id=nextSequence++;compressions.put(id,new int[]{memory.readLong(sp+44),memory.readLong(sp+36),cType,memory.readLong(sp+18),memory.readLong(sp)});
				int idPtr=memory.readLong(sp+48);if(idPtr!=0)memory.writeLong(idPtr,id);err=0;break;}
			case 0x0b:{ // CompressSequenceFrame(seqID, src, srcRect, flags, data, VAR dataSize, VAR similarity, completion): every frame a key frame
				int[] seq=compressions.get(memory.readLong(sp+26));if(seq==null)break;
				int src=memory.readLong(sp+22),srcRect=memory.readLong(sp+18);int[] frameSize={0};
				err=compressImage(src!=0?src:seq[0],srcRect!=0?srcRect:seq[1],seq[3],seq[2],seq[4],memory.readLong(sp+12),frameSize);
				int sizePtr=memory.readLong(sp+8),similarity=memory.readLong(sp+4);
				if(err==0&&sizePtr!=0)memory.writeLong(sizePtr,frameSize[0]);if(similarity!=0)memory.writeByte(similarity,0);break;}
			case 0x1a:if(sequences().has(memory.readLong(sp)))err=0;break; // CDSequenceBusy
			case 0x0c:stdPix(cpu,memory.readLong(sp+24),memory.readLong(sp+20),memory.readLong(sp+16),(short)memory.readWord(sp+14),memory.readLong(sp+10));break; // StdPix(src, srcRect, matrix, mode, mask, matte, matteRect, flags)
			case 0x08:err=decompressImage(cpu,memory.readLong(sp+22),memory.readLong(sp+18),memory.readLong(sp+14),memory.readLong(sp+10),0,memory.readLong(sp+6),(short)memory.readWord(sp+4),memory.readLong(sp));break; // DecompressImage(data, desc, dst, srcRect, dstRect, mode, mask)
			case 0x09:err=decompressImage(cpu,memory.readLong(sp+50),memory.readLong(sp+46),memory.readLong(sp+42),memory.readLong(sp+38),memory.readLong(sp+34),0,(short)memory.readWord(sp+32),memory.readLong(sp+28));break; // FDecompressImage(data, desc, dst, srcRect, matrix, mode, mask, matte, matteRect, accuracy, codec, bufferSize, dataProc, progressProc)
			case 0x45:err=makeFilePreview((short)memory.readWord(sp+4));break; // MakeFilePreview(resRefNum, progress)
			case 0x46:{int data=memory.readLong(sp);byte[] b=data==0?null:tb.api.handleContents(data); // AddFilePreview(resRefNum, previewType, previewData)
				err=b==null?PictureCompression68k.PARAM_ERR:PictureCompression68k.addFilePreview(tb,(short)memory.readWord(sp+8),memory.readLong(sp+4),b);break;}
			case 0x24:err=compressPicture(memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break; // CompressPicture(src, dst, quality, cType)
			case 0x25:err=compressPicture(memory.readLong(sp+30),memory.readLong(sp+26),memory.readLong(sp+16),memory.readLong(sp+4));break; // FCompressPicture(src, dst, colorDepth, ctable, quality, doDither, compressAgain, progressProc, cType, codec)
			case 0x26:err=compressPictureFile((short)memory.readWord(sp+10),(short)memory.readWord(sp+8),memory.readLong(sp+4),memory.readLong(sp));break; // CompressPictureFile(srcRefNum, dstRefNum, quality, cType)
			case 0x27:err=compressPictureFile((short)memory.readWord(sp+28),(short)memory.readWord(sp+26),memory.readLong(sp+16),memory.readLong(sp+4));break; // FCompressPictureFile(src, dst, colorDepth, ctable, quality, doDither, compressAgain, progressProc, cType, codec)
			case 0x2a:{int picture=memory.readLong(sp+10);byte[] b=picture==0?null:tb.api.handleContents(picture); // MakeThumbnailFromPicture(picture, colorDepth, thumbnail, progress)
				err=thumbnailOf(b,memory.readLong(sp+4));break;}
			case 0x28:err=pictureFileHeader((short)memory.readWord(sp+8),memory.readLong(sp+4),memory.readLong(sp));break; // GetPictureFileHeader(refNum, frame, header)
			case 0x29:err=drawPictureFile((short)memory.readWord(sp+8),memory.readLong(sp+4));break; // DrawPictureFile(refNum, frame, progress)
			case 0x2b:err=thumbnailOf(PictureCompression68k.pictureOfFile(tb,(short)memory.readWord(sp+10),null),memory.readLong(sp+4));break; // MakeThumbnailFromPictureFile(refNum, colorDepth, thumbnail, progress)
			default:break;
		}
		cpu.a[7]+=Math.abs(size);
		if(size>0)memory.writeWord(cpu.a[7],err);
		return true;
	}

	/**
	 * The ICM version CodecManagerVersion and Gestalt 'icmp' give. An external sees the QuickTime 4.0.3 of a HyperCard Mac
	 * (19, measured with the installed extension); programs keep the native Movie Toolbox's 2.5 answer.
	 */
	int version(){return tb.app==null?EXTERNAL_ICM_VERSION:QuickTime68k.VERSION;}
	static final int EXTERNAL_ICM_VERSION=19;

	/**
	 * MakeFilePreview: a thumbnail of the picture in the file's data fork (at most 80x80, aspect kept) as a 'PICT' resource of
	 * the open resource file, and the 'pnot' 0 that points at it (PreviewResourceRecord: modDate, version 0, type, ID); a
	 * preview made before is replaced. Infini-D 3.0 calls it on every image it renders to a file and reports a nonzero result
	 * as "the rendered image could not be saved". Files without a picture the importers read get noCodecErr as before.
	 */
	private int makeFilePreview(int refNum) throws M68kException {
		java.io.File file=tb.resources.fileOfRefNum(refNum);
		if(file==null)return ResourceManager68k.RES_FILE_NOT_FOUND;
		byte[] picture=PictureCompression68k.thumbnail(GraphicsImporter68k.imageOfFile(tb,file));
		if(picture==null)return CODEC_UNIMP_ERR;
		return PictureCompression68k.addFilePreview(tb,refNum,PictureCompression68k.PICT,picture);
	}
	/** MakeThumbnailFromPicture(File): the thumbnail of {@code picture} into the Handle {@code thumbnail}. */
	private int thumbnailOf(byte[] picture,int thumbnail) throws M68kException {
		if(picture==null||thumbnail==0)return PictureCompression68k.PARAM_ERR;
		java.awt.image.BufferedImage image;
		try{image=tb.api.quickDraw().renderPicture(GraphicsImporter68k.pictureOf(picture));}catch(RuntimeException e){image=null;}
		byte[] result=PictureCompression68k.thumbnail(image);
		if(result==null)return PictureCompression68k.CODEC_ERR;
		tb.api.loadHandle(thumbnail,result);return 0;
	}
	/**
	 * StdPix: the uncompressed PixMap {@code src} (its {@code srcRect}, or its bounds) drawn into the current port where the
	 * matrix puts it (no matrix: in place), by CopyBits with the mode and mask - the bottleneck QuickTime's picture drawing
	 * goes through. QuickDraw Utilities' StdPix drew nothing (the selector was a no-op).
	 */
	private void stdPix(M68kCpu cpu,int src,int srcRect,int matrix,int mode,int mask) throws M68kException {
		int port=tb.api.currentQuickDrawPort();if(port==0||src==0)return;
		int map=src;if((memory.readWord(src+4)&0xc000)==0xc000){int h=memory.readLong(src);map=h==0?0:memory.readLong(h);if(map==0)return;} // a colour port's portBits (&port->portBits, as CopyBits takes it): its PixMap's bounds
		int[] from=srcRect!=0?rect(srcRect):rect(map+6),to=mapped(from,matrix);
		if(Mac68kApi.TRACE||QuickTime68k.QT_TRACE)System.err.println("[icm] StdPix src $"+Integer.toHexString(src)+" srcRect $"+Integer.toHexString(srcRect)+" matrix $"+Integer.toHexString(matrix)+" mask $"+Integer.toHexString(mask)+" "+java.util.Arrays.toString(from)+" -> "+java.util.Arrays.toString(to)+" port $"+Integer.toHexString(port)+" mode "+mode);
		blit(cpu,src,port+2,from,to,mode,mask);
	}
	/**
	 * DecompressImage / FDecompressImage: the image ({@code data} with its ImageDescription) decoded natively
	 * ({@link QuickTimeImage68k}: raw, rle, smc, jpeg, png...) and drawn into the PixMap {@code dst} - its {@code srcRect} (or the
	 * whole image) at {@code dstRect} or where the matrix puts it - by CopyBits with the mode and mask. QuickDraw Utilities'
	 * FastDither draws a GWorld through FDecompressImage with a 'raw ' description (it answered codecUnimpErr, nothing drawn).
	 */
	private int decompressImage(M68kCpu cpu,int data,int desc,int dst,int srcRect,int matrix,int dstRect,int mode,int mask) throws M68kException {
		byte[] description=desc==0?null:tb.api.handleContents(desc);
		if(description==null||description.length<86||data==0||dst==0||memory.readLong(dst)==0)return PictureCompression68k.PARAM_ERR;
		int width=MacApp68k.u2(description,32),height=MacApp68k.u2(description,34),depth=(short)MacApp68k.u2(description,82),bits=depth>32?depth-32:depth;
		if(width<=0||height<=0||(long)width*height>16L*1024*1024)return PictureCompression68k.PARAM_ERR;
		int size=MacApp68k.u4(description,44);
		if(size<=0)size=(bits>=8?width*((bits+7)/8):(width*Math.max(1,bits)+7)/8)*height; // a description made by hand for 'raw ' rows often leaves dataSize 0
		int[] argb=QuickTimeImage68k.decode(description,memory.readBytes(data,Math.min(size,64*1024*1024)));
		if(Mac68kApi.TRACE||QuickTime68k.QT_TRACE)System.err.println("[icm] DecompressImage '"+QuickTimeImage68k.codecName(description)+"' "+width+"x"+height+" depth "+depth+(argb==null?" not decodable":""));
		if(argb==null)return CODEC_UNIMP_ERR;
		int[] whole={0,0,height,width},from=srcRect!=0?rect(srcRect):whole,to=dstRect!=0?rect(dstRect):mapped(from,matrix);
		int pixels=memory.allocate(width*height*4,4),map=memory.allocate(50,4);
		try{
			for(int i=0;i<argb.length;i++)memory.writeLong(pixels+i*4,argb[i]&0xffffff);
			memory.fill(map,50,0);memory.writeLong(map,pixels);memory.writeWord(map+4,0x8000|width*4);memory.writeWord(map+10,height);memory.writeWord(map+12,width);
			memory.writeLong(map+22,0x00480000);memory.writeLong(map+26,0x00480000);memory.writeWord(map+30,16);memory.writeWord(map+32,32);memory.writeWord(map+34,3);memory.writeWord(map+36,8);
			// the current port's own PixMap goes as the port's portBits, so the blit is clipped and reaches the screen as the port's drawing does
			int port=tb.api.currentQuickDrawPort(),target=memory.readLong(dst);
			if(port!=0&&(memory.readWord(port+6)&0xc000)==0xc000&&memory.readLong(port+2)==dst)target=port+2;
			blit(cpu,map,target,from,to,mode,mask);
		}finally{memory.free(map);memory.free(pixels);}
		return 0;
	}
	/** CopyBits from the PixMap/BitMap {@code src} to {@code dst} with rectangles given as {top,left,bottom,right}. */
	private void blit(M68kCpu cpu,int src,int dst,int[] from,int[] to,int mode,int mask) throws M68kException {
		if(from[2]<=from[0]||from[3]<=from[1]||to[2]<=to[0]||to[3]<=to[1])return;
		int rects=memory.allocate(16,4);
		try{for(int i=0;i<4;i++){memory.writeWord(rects+i*2,from[i]);memory.writeWord(rects+8+i*2,to[i]);}
			tb.api.quickDraw().copyBitsFrom(cpu,src,dst,rects,rects+8,mode,mask);}
		finally{memory.free(rects);}
	}
	/** The bounding box of {@code r} under a QuickTime MatrixRecord (Fixed a b / c d / tx ty; u, v, w ignored), or r itself without one. */
	private int[] mapped(int[] r,int matrix) throws M68kCpuException {
		if(matrix==0)return r.clone();
		double a=memory.readLong(matrix)/65536.0,b=memory.readLong(matrix+4)/65536.0,c=memory.readLong(matrix+12)/65536.0,d=memory.readLong(matrix+16)/65536.0,tx=memory.readLong(matrix+24)/65536.0,ty=memory.readLong(matrix+28)/65536.0;
		double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
		for(int[] k:new int[][]{{r[1],r[0]},{r[3],r[0]},{r[1],r[2]},{r[3],r[2]}}){double x=k[0]*a+k[1]*c+tx,y=k[0]*b+k[1]*d+ty;minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}
		return new int[]{(int)Math.round(minY),(int)Math.round(minX),(int)Math.round(maxY),(int)Math.round(maxX)};
	}
	/** CompressSequenceBegin's sequences: {src PixMapHandle, srcRect, cType, quality, desc Handle} by sequence ID. */
	private final Map<Integer,int[]> compressions=new HashMap<Integer,int[]>();private int nextSequence=0x5100;
	private int[] rect(int p) throws M68kCpuException {return p==0?new int[4]:new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	/**
	 * CompressImage: the pixels of {@code srcRect} of the PixMap {@code src} compressed with {@code cType} (ImageCodecs68k) into
	 * the buffer {@code data}, its ImageDescription into the Handle {@code desc} (dataSize at 44: UxCardToMovie hands it to
	 * AddMediaSample). {@code size[0]}, when given, gets the data's length.
	 */
	private int compressImage(int src,int srcRect,int quality,int cType,int desc,int data,int[] size) throws M68kException {
		if(!ImageCodecs68k.supported(cType))return CODEC_UNIMP_ERR;
		int pixMap=src==0?0:memory.readLong(src);if(pixMap==0||desc==0||data==0)return PictureCompression68k.PARAM_ERR;
		int[] r=rect(srcRect);int w=r[3]-r[1],h=r[2]-r[0];if(w<=0||h<=0)return PictureCompression68k.PARAM_ERR;
		int[] rgb=tb.api.quickDraw().readBitmapRgb(pixMap,r[0],r[1],r[2],r[3]);
		byte[] bytes=ImageCodecs68k.encode(cType,rgb,w,h,quality);if(bytes==null)return PictureCompression68k.CODEC_ERR;
		tb.api.loadHandle(desc,ImageCodecs68k.description(cType,w,h,quality,bytes.length));
		memory.load(data,bytes);if(size!=null)size[0]=bytes.length;
		if(Mac68kApi.TRACE||QuickTime68k.QT_TRACE)System.err.println("[icm] CompressImage '"+QuickTimeFile.typeName(cType)+"' "+w+"x"+h+" -> "+bytes.length+" bytes");
		return 0;
	}

	/** CompressPicture: {@code dst} gets {@code src} compressed with {@code codec}. */
	private int compressPicture(int src,int dst,int quality,int codec) throws M68kException {
		byte[] picture=src==0?null:tb.api.handleContents(src);
		if(picture==null||dst==0)return PictureCompression68k.PARAM_ERR;
		int[] err={0};byte[] result=PictureCompression68k.compress(tb,picture,quality,codec,err);
		if(result==null)return err[0];
		tb.api.loadHandle(dst,result);return 0;
	}
	/** DrawPictureFile: the picture of the PICT file open as {@code refNum} drawn in {@code framePtr} of the current port
	 *  (Caliban's RasterizePict draws it into a GWorld and saves that: unimplemented, it wrote a blank picture over the file). */
	private int drawPictureFile(int refNum,int framePtr) throws M68kException {
		byte[] picture=PictureCompression68k.pictureOfFile(tb,refNum,null);
		if(picture==null)return tb.files.fileManager.referenceLength(refNum)<0?-51:PictureCompression68k.CODEC_ERR; // rfNumErr
		if(framePtr==0)return -50;
		int[] frame={(short)memory.readWord(framePtr),(short)memory.readWord(framePtr+2),(short)memory.readWord(framePtr+4),(short)memory.readWord(framePtr+6)};
		QuickDraw68k qd=tb.api.quickDraw();qd.drawPictureInto(qd.currentPortAddress(),0,picture,frame);return 0;
	}
	/**
	 * GetPictureFileHeader: a PICT file's frame (picFrame) and its OpenCPicParams (srcRect, hRes, vRes, version, 2 reserved):
	 * an extended version 2 header's resolution and source rectangle, otherwise the frame at 72 dpi. AntiAlias 1.01 sizes its
	 * window and the picture it records by the frame; with codecUnimpErr it got an empty one (a grey window).
	 */
	private int pictureFileHeader(int refNum,int framePtr,int headerPtr) throws M68kException {
		byte[] p=PictureCompression68k.pictureOfFile(tb,refNum,null);
		if(p==null)return tb.files.fileManager.referenceLength(refNum)<0?-51:PictureCompression68k.CODEC_ERR; // rfNumErr
		if(p.length<10)return PictureCompression68k.CODEC_ERR;
		int[] frame={s2(p,2),s2(p,4),s2(p,6),s2(p,8)},src=frame.clone();int hRes=72<<16,vRes=72<<16,version=0;
		if(p.length>=40&&s2(p,10)==0x0011&&s2(p,12)==0x02ff&&s2(p,14)==0x0c00){version=s2(p,16);
			if(version==-2){hRes=s4(p,20);vRes=s4(p,24);src=new int[]{s2(p,28),s2(p,30),s2(p,32),s2(p,34)};}}
		if(framePtr!=0)for(int i=0;i<4;i++)memory.writeWord(framePtr+2*i,frame[i]);
		if(headerPtr!=0){for(int i=0;i<4;i++)memory.writeWord(headerPtr+2*i,src[i]);memory.writeLong(headerPtr+8,hRes);memory.writeLong(headerPtr+12,vRes);
			memory.writeWord(headerPtr+16,version);memory.writeWord(headerPtr+18,0);memory.writeLong(headerPtr+20,0);}
		return 0;
	}
	private static int s2(byte[] b,int at){return (short)(((b[at]&255)<<8)|(b[at+1]&255));}
	private static int s4(byte[] b,int at){return ((b[at]&255)<<24)|((b[at+1]&255)<<16)|((b[at+2]&255)<<8)|(b[at+3]&255);}
	/** CompressPictureFile: the PICT file {@code dst} (may be {@code src}) gets the picture of {@code src} compressed; the 512-byte header is kept. */
	private int compressPictureFile(int src,int dst,int quality,int codec) throws M68kException {
		byte[][] header={null};byte[] picture=PictureCompression68k.pictureOfFile(tb,src,header);
		if(picture==null)return tb.files.fileManager.referenceLength(src)<0?-51:PictureCompression68k.CODEC_ERR; // rfNumErr
		int[] err={0};byte[] result=PictureCompression68k.compress(tb,picture,quality,codec,err);
		if(result==null)return err[0];
		return PictureCompression68k.writePictureFile(tb,dst,header[0],result);
	}

	/** The graphics importer for a file's data, opened and given the file; a nonzero OSErr when there is none. */
	private int importerForFile(int spec,int giPtr) throws M68kException {
		if(giPtr!=0)memory.writeLong(giPtr,0);
		java.io.File file;try{file=spec==0?null:tb.files.fileForSpec(spec);}catch(java.io.IOException e){file=null;}
		if(file==null||!file.isFile())return -43;
		byte[] head=new byte[528];int n=0; // enough for a PICT file's header, picSize, frame and version opcode
		try(java.io.InputStream in=new java.io.FileInputStream(file)){n=in.readNBytes(head,0,head.length);}catch(java.io.IOException e){return -36;}
		int subType=GraphicsImporter68k.subTypeOf(n<head.length?java.util.Arrays.copyOf(head,n):head,file.getName());
		Components68k.Component importer=subType==0?null:tb.components.find(GraphicsImporter68k.GRIP,subType);
		if(importer==null||importer.handler==null)return -2014;
		int ci=tb.components.open(importer);if(ci==0)return -3000;
		int err=((GraphicsImporter68k)importer.handler).setFile(ci,file);
		if(err!=0){tb.components.close(ci);return err;}
		if(giPtr!=0)memory.writeLong(giPtr,ci);
		return 0;
	}
	/**
	 * The graphics importer for the data a handle, pointer or alias data reference names, chosen by the data (QuickTime
	 * validates the importers when the reference carries no file type or name); codecUnimpErr for other reference kinds.
	 */
	private int importerForDataRef(int dataRef,int type,int giPtr) throws M68kException {
		if(giPtr!=0)memory.writeLong(giPtr,0);
		GraphicsImporter68k.Reference ref=GraphicsImporter68k.reference(tb,dataRef,type);
		if(ref==null)return CODEC_UNIMP_ERR;
		if(ref.data==null)return ref.err;
		int subType=GraphicsImporter68k.subTypeOf(ref.data,ref.name);
		Components68k.Component importer=subType==0?null:tb.components.find(GraphicsImporter68k.GRIP,subType);
		if(importer==null||importer.handler==null)return -2014;
		int ci=tb.components.open(importer);if(ci==0)return -3000;
		((GraphicsImporter68k)importer.handler).setData(ci,ref.data);
		if(giPtr!=0)memory.writeLong(giPtr,ci);
		return 0;
	}
	/** The bits per pixel of a QuickTime pixel format OSType ('argb' and the like: 32; 'raw ': 24 held as 32; 16-bit formats). */
	private static int pixelFormatDepth(int format){
		switch(format){
			case 0x62313667:case 0x62353535:case 0x35353520:return 16; // b16g, b555
			case 0x70787032:return 8;
			default:return format>=0&&format<=32?(format==0?32:format):32;
		}
	}
}

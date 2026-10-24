import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * The still-picture half of the QuickTime 3/4 Image Compression Manager, natively: CompressPicture / CompressPictureFile and
 * their F- forms (the picture played by the program's own QuickDraw, compressed with Photo JPEG - or PNG or 'raw ' - and
 * stored as a version 2 picture holding one CompressedQuickTime opcode, $8200, which QuickDraw68k and the host picture reader
 * draw), MakeThumbnailFromPicture(File) (at most 80x80, aspect kept), AddFilePreview ('pnot' 0 and the preview resource), and
 * the preview maker component ('pmak': PreviewMakePreview gives a thumbnail of the picture, image or movie poster in a file).
 * Rinaldi's CompressPictFile, CreateThumbnail and the "c=" option of PictToClip/ClipToPict/PictTransform call these.
 */
final class PictureCompression68k implements Components68k.Native {
	static final int PMAK=0x706d616b,APPL=0x6170706c,PICT=0x50494354,PNOT=0x706e6f74;
	static final int JPEG=0x6a706567,PNG=0x706e6720,RAW=0x72617720;
	static final int PARAM_ERR=-50,CODEC_ERR=-8960,NO_CODEC_ERR=-8961,NO_MOVIE_FOUND=-2048,FNF_ERR=-43,BAD_SELECTOR=0x80008002;
	/** CodecQ values (ImageCompression.h). */
	static final int CODEC_NORMAL_QUALITY=0x200,CODEC_MAX_QUALITY=0x3ff,CODEC_LOSSLESS_QUALITY=0x400;
	static final int THUMBNAIL_SIZE=80;
	private static final int FILE_HEADER=512;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private PictureCompression68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** The preview maker 'pmak' (any file kind) and the Standard Image Compression Dialog 'scdi' ({@link StandardCompression68k}), present with the native Movie Toolbox. */
	static void register(Components68k components,Toolbox68k tb){components.registerNative(PMAK,0,APPL,0,new PictureCompression68k(tb));StandardCompression68k.register(components,tb);}

	@Override public int call(int ci,int what,int p) throws M68kException {
		switch(what){
			case 2:{ // PreviewMakePreview(p, OSType *previewType, Handle *previewResult, const FSSpec *sourceFile, ICMProgressProcRecordPtr progress)
				int typeOut=memory.readLong(p+12),resultOut=memory.readLong(p+8),spec=memory.readLong(p+4);
				if(resultOut!=0)memory.writeLong(resultOut,0);
				File file;try{file=spec==0?null:tb.files.fileForSpec(spec);}catch(IOException e){file=null;}
				if(file==null||!file.isFile())return FNF_ERR;
				BufferedImage image=imageOfFile(tb,file);if(image==null)return NO_MOVIE_FOUND; // "no picture data" (Rinaldi's CreateThumbnail says so for -2048)
				byte[] thumbnail=thumbnail(image);if(thumbnail==null)return CODEC_ERR;
				if(typeOut!=0)memory.writeLong(typeOut,PICT);
				if(resultOut!=0)memory.writeLong(resultOut,tb.api.createHandle(thumbnail));
				return 0;}
			default:return BAD_SELECTOR;
		}
	}

	/** What a file shows: a picture or image the graphics importers read, else the first frame of a movie; null for neither. */
	static BufferedImage imageOfFile(Toolbox68k tb,File file){
		BufferedImage image=GraphicsImporter68k.imageOfFile(tb,file);
		if(image!=null)return image;
		if(!QuickTimeFile.isMovie(file))return null;
		try{TQTMovie.MovieAsset asset=TQTMovie.assetFor(file);return asset!=null&&asset.hasVideo()?asset.frameAtMillis(0):null;}catch(RuntimeException e){return null;}
	}

	// ------------------------------------------------------------------ thumbnails and file previews

	/** A thumbnail picture of {@code image}: at most 80x80 with its aspect, on white, as a 32-bit picture; null when it cannot be made. */
	static byte[] thumbnail(BufferedImage image){
		if(image==null||image.getWidth()<=0||image.getHeight()<=0)return null;
		double scale=Math.min(1.0,(double)THUMBNAIL_SIZE/Math.max(image.getWidth(),image.getHeight()));
		int w=Math.max(1,(int)Math.round(image.getWidth()*scale)),h=Math.max(1,(int)Math.round(image.getHeight()*scale));
		BufferedImage thumb=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
		Graphics2D g=thumb.createGraphics();
		g.setColor(Color.WHITE);g.fillRect(0,0,w,h);
		g.drawImage(image.getScaledInstance(w,h,Image.SCALE_AREA_AVERAGING),0,0,null);g.dispose();
		try{return HCResourceExport.pict(thumb);}catch(IOException e){return null;}
	}

	/**
	 * AddFilePreview: {@code data} becomes a resource of {@code type} in the open resource file, and 'pnot' 0 points at it
	 * (PreviewResourceRecord: modDate, version 0, resType, resID); a preview made before is replaced.
	 */
	static int addFilePreview(Toolbox68k tb,int refNum,int type,byte[] data) throws M68kException {
		if(tb.resources.fileOfRefNum(refNum)==null)return ResourceManager68k.RES_FILE_NOT_FOUND;
		byte[] old=tb.resources.resourceData(refNum,PNOT,0);
		if(old!=null&&old.length>=12)tb.resources.removeResource(refNum,(old[6]&0xff)<<24|(old[7]&0xff)<<16|(old[8]&0xff)<<8|old[9]&0xff,(short)((old[10]&0xff)<<8|old[11]&0xff));
		int id=tb.resources.unusedId(refNum,type);
		int err=tb.resources.putResource(refNum,type,id,data);if(err!=0)return err;
		byte[] record=new byte[12];long now=Toolbox68k.macNow();
		for(int i=0;i<4;i++){record[i]=(byte)(now>>>(24-8*i));record[6+i]=(byte)(type>>>(24-8*i));}record[10]=(byte)(id>>8);record[11]=(byte)id;
		return tb.resources.putResource(refNum,PNOT,0,record);
	}

	// ------------------------------------------------------------------ compression

	/** The frame of a Picture (picFrame: top, left, bottom, right), or null when the bytes are too short. */
	static int[] frameOf(byte[] picture){
		if(picture==null||picture.length<10)return null;
		return new int[]{(short)((picture[2]&0xff)<<8|picture[3]&0xff),(short)((picture[4]&0xff)<<8|picture[5]&0xff),(short)((picture[6]&0xff)<<8|picture[7]&0xff),(short)((picture[8]&0xff)<<8|picture[9]&0xff)};
	}

	/**
	 * {@code picture} compressed with {@code codec} at {@code quality} (a CodecQ): played into a 32-bit world by the program's
	 * QuickDraw and stored as one CompressedQuickTime image over the same frame. {@code error[0]} gets the OSErr when the result is null.
	 */
	static byte[] compress(Toolbox68k tb,byte[] picture,int quality,int codec,int[] error){
		int[] frame=frameOf(picture);
		if(frame==null||frame[2]<=frame[0]||frame[3]<=frame[1]){error[0]=PARAM_ERR;return null;}
		if(codec!=JPEG&&codec!=PNG&&codec!=RAW&&!ImageCodecs68k.supported(codec)){error[0]=NO_CODEC_ERR;return null;}
		BufferedImage image;
		try{image=tb.api.quickDraw().renderPicture(picture);}catch(M68kCpuException|RuntimeException e){image=null;}
		if(image==null){error[0]=CODEC_ERR;return null;}
		// Graphics ('smc ') and Animation ('rle '): the image compressors of movies (UxCompressRsrc tries 'smc ' against Photo JPEG
		// and keeps the smaller; without it nothing was ever compressed)
		if(codec==ImageCodecs68k.SMC||codec==ImageCodecs68k.RLE){
			int w=image.getWidth(),h=image.getHeight();int[] rgb=image.getRGB(0,0,w,h,null,0,w);for(int i=0;i<rgb.length;i++)if((rgb[i]>>>24)<128)rgb[i]=0xffffff;
			byte[] data=ImageCodecs68k.encode(codec,rgb,w,h,quality);
			if(data==null){error[0]=CODEC_ERR;return null;}
			return compressedPicture(frame,w,h,ImageCodecs68k.description(codec,w,h,quality,data.length),data);
		}
		byte[] data=encode(image,codec,quality);
		if(data==null){error[0]=CODEC_ERR;return null;}
		return compressedPicture(frame,image.getWidth(),image.getHeight(),codec,quality,data);
	}

	/** The image data of {@code codec}: a JFIF stream ('jpeg'), a PNG file ('png '), or 32-bit xRGB rows ('raw '). */
	static byte[] encode(BufferedImage image,int codec,int quality){
		try{
			if(codec==RAW){
				int w=image.getWidth(),h=image.getHeight();byte[] raw=new byte[w*h*4];
				for(int y=0,i=0;y<h;y++)for(int x=0;x<w;x++,i+=4){int rgb=image.getRGB(x,y);raw[i]=(byte)0xff;raw[i+1]=(byte)(rgb>>16);raw[i+2]=(byte)(rgb>>8);raw[i+3]=(byte)rgb;}
				return raw;
			}
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			if(codec==PNG){if(!ImageIO.write(image,"png",out))return null;return out.toByteArray();}
			BufferedImage rgb=image;
			if(image.getType()!=BufferedImage.TYPE_INT_RGB){rgb=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=rgb.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(image,0,0,null);g.dispose();}
			Iterator<ImageWriter> writers=ImageIO.getImageWritersByFormatName("jpeg");if(!writers.hasNext())return null;
			ImageWriter writer=writers.next();
			try(ImageOutputStream stream=ImageIO.createImageOutputStream(out)){
				writer.setOutput(stream);
				ImageWriteParam param=writer.getDefaultWriteParam();param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);param.setCompressionQuality(jpegQuality(quality));
				writer.write(null,new IIOImage(rgb,null,null),param);
			}finally{writer.dispose();}
			return out.toByteArray();
		}catch(IOException|RuntimeException e){return null;}
	}
	/** A CodecQ (codecMinQuality 0 ... codecMaxQuality $3FF, codecLosslessQuality $400) as an ImageIO JPEG quality. */
	static float jpegQuality(int quality){
		if(quality>=CODEC_LOSSLESS_QUALITY)return 1f;
		return (float)Math.max(0.05,Math.min(1.0,0.05+0.95*Math.max(0,quality)/CODEC_MAX_QUALITY));
	}

	/**
	 * A version 2 Picture over {@code frame} holding one CompressedQuickTime ($8200) image: version 0, a matrix that moves the
	 * image to the frame's corner, no matte or mask, ditherCopy, the whole image as the source rectangle, then the
	 * ImageDescription (86 bytes) and the data.
	 */
	static byte[] compressedPicture(int[] frame,int width,int height,int codec,int quality,byte[] data){
		return compressedPicture(frame,width,height,description(codec,width,height,quality,data.length),data);
	}
	/** The same with the ImageDescription given. */
	static byte[] compressedPicture(int[] frame,int width,int height,byte[] description,byte[] data){
		ByteArrayOutputStream b=new ByteArrayOutputStream();
		word(b,0);rect(b,frame);                                   // picSize (filled in below), picFrame
		word(b,0x0011);word(b,0x02ff);                             // version 2
		word(b,0x0c00);word(b,0xfffe);word(b,0);lng(b,72<<16);lng(b,72<<16);rect(b,frame);lng(b,0); // extended header: 72 dpi, srcRect = frame
		word(b,0x0001);word(b,10);rect(b,frame);                   // clip region: the frame
		int pad=data.length&1,length=2+36+4+8+2+8+4+4+description.length+data.length+pad;
		word(b,0x8200);lng(b,length);
		word(b,0);                                                 // version
		lng(b,0x10000);lng(b,0);lng(b,0);lng(b,0);lng(b,0x10000);lng(b,0);lng(b,frame[1]<<16);lng(b,frame[0]<<16);lng(b,0x40000000); // matrix: identity, then tx/ty
		lng(b,0);rect(b,new int[4]);                               // matteSize, matteRect
		word(b,0x40);                                              // mode: ditherCopy
		rect(b,new int[]{0,0,height,width});                       // srcRect
		lng(b,0);lng(b,0);                                         // accuracy, maskSize
		b.write(description,0,description.length);b.write(data,0,data.length);if(pad!=0)b.write(0);
		word(b,0x00ff);                                            // end of picture
		byte[] picture=b.toByteArray();picture[0]=(byte)(picture.length>>8);picture[1]=(byte)picture.length;
		return picture;
	}
	/** An ImageDescription (86 bytes) for the image data of {@code codec}. */
	static byte[] description(int codec,int width,int height,int quality,int dataSize){
		ByteArrayOutputStream b=new ByteArrayOutputStream();
		lng(b,86);lng(b,codec);lng(b,0);word(b,0);word(b,0);         // idSize, cType, reserved, dataRefIndex
		word(b,1);word(b,1);lng(b,APPL);                           // version, revisionLevel, vendor
		lng(b,0);lng(b,Math.min(quality,CODEC_LOSSLESS_QUALITY));   // temporalQuality, spatialQuality
		word(b,width);word(b,height);lng(b,72<<16);lng(b,72<<16);   // width, height, hRes, vRes
		lng(b,dataSize);word(b,1);                                 // dataSize, frameCount
		String name=codec==JPEG?"Photo - JPEG":codec==PNG?"PNG":"None";
		byte[] n=new byte[32];n[0]=(byte)name.length();System.arraycopy(name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),0,n,1,name.length());b.write(n,0,32);
		word(b,codec==RAW?32:24);word(b,-1);                       // depth, clutID
		return b.toByteArray();
	}
	private static void word(ByteArrayOutputStream b,int v){b.write(v>>8);b.write(v);}
	private static void lng(ByteArrayOutputStream b,int v){word(b,v>>>16);word(b,v&0xffff);}
	private static void rect(ByteArrayOutputStream b,int[] r){for(int v:r)word(b,v);}

	// ------------------------------------------------------------------ PICT files

	/** The picture of the PICT file open as data fork {@code refNum} (512-byte header, then the picture), or null. */
	static byte[] pictureOfFile(Toolbox68k tb,int refNum,byte[][] header){
		FileManager68k files=tb.files.fileManager;
		long length=files.referenceLength(refNum);if(length<FILE_HEADER+10||length>64L<<20)return null;
		byte[] all;try{all=files.readReference(refNum,0,(int)length);}catch(IOException e){return null;}
		if(all==null||all.length<FILE_HEADER+10)return null;
		if(header!=null)header[0]=java.util.Arrays.copyOf(all,FILE_HEADER);
		return GraphicsImporter68k.pictureOf(all);
	}
	/** Writes a PICT file - {@code header} and {@code picture} - over the data fork {@code refNum}; an OSErr. */
	static int writePictureFile(Toolbox68k tb,int refNum,byte[] header,byte[] picture){
		FileManager68k files=tb.files.fileManager;
		byte[] all=new byte[FILE_HEADER+picture.length];if(header!=null)System.arraycopy(header,0,all,0,Math.min(FILE_HEADER,header.length));
		System.arraycopy(picture,0,all,FILE_HEADER,picture.length);
		try{
			if(!files.writeReference(refNum,0,all))return files.referenceLength(refNum)<0?-51:-61; // rfNumErr, wrPermErr
			int err=files.setForkLength(refNum,all.length);return err;
		}catch(IOException e){return -36;}
	}
}

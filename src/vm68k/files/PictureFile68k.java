import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Host picture files as a hosted program sees them, the counterpart of {@link TextFile68k}: a PNG on
 * the host is handed to the program as a 'PICT' file (the 512-byte header and a picture of the image, the same
 * encoding the clipboard uses), and a PICT file the program creates is saved on the host as PNG under the name the
 * program gave it, typed 'PNGf' so the Finder and Preview know it without an extension. Today's programs cannot
 * open a PICT; a 68K program cannot open a PNG. Vector pictures come back to the program as bitmaps. GIFs are
 * left alone: they are usually animations, and a single frame as a PICT is not what the program should see. JPEGs are
 * left alone too: classic programs read them themselves (QuickTime, browsers, image editors) - Adobe PageMill inserts
 * a .jpg as a JPEG, and a PICT behind that name showed as a broken image.
 */
final class PictureFile68k {
	static final int PICT=0x50494354,PNGF=0x504E4766;
	/** The longer side a picture is scaled down to: enough for any classic screen, and a bitmap a 68K heap can hold. */
	static final int MAX_SIDE=2048;
	/** Host pictures larger than this are handed over as they are. */
	static final long LIMIT=64L<<20;
	/** Finder information of a PNG the program saved without a creator of its own: type PNGf, creator prvw (Preview). */
	static final byte[] PNG_FINDER_INFO={'P','N','G','f','p','r','v','w',0,0,0,0,0,0,0,0};
	/**
	 * The host Finder information of a picture file the program saved with {@code program} as its own: the type becomes
	 * {@code hostType} (PNGf, or PICT when the bytes stay a PICT), the creator and the Finder flags stay the program's. Only
	 * the type tells the host what the file is; macOS does not pick applications by creator, and with Kid Pix's own 'Kid2'
	 * gone (it was always prvw) Kid Pix said its saved pictures were "not created by Kid Pix" when it opened them again.
	 * A file without a creator gets {@code noCreator}.
	 */
	static byte[] savedFinderInfo(byte[] program,int hostType,int noCreator){
		byte[] info=program==null?new byte[16]:program.clone();
		info[0]=(byte)(hostType>>>24);info[1]=(byte)(hostType>>>16);info[2]=(byte)(hostType>>>8);info[3]=(byte)hostType;
		int creator=MacApp68k.u4(info,4);
		if(creator==0||creator==0x3F3F3F3F){info[4]=(byte)(noCreator>>>24);info[5]=(byte)(noCreator>>>16);info[6]=(byte)(noCreator>>>8);info[7]=(byte)noCreator;}
		return info;
	}
	private static final int HEADER=512;

	private PictureFile68k(){}

	/** Whether {@code type} is a host picture type the program sees as PICT: PNG only (GIF and JPEG were Mac formats already). */
	static boolean isHostImageType(int type){return type==PNGF;}

	/**
	 * Whether {@code program} reads PNG files itself: its code - the PEF in the data fork, the CODE in the resource fork - holds
	 * the name of PNG's header chunk, "IHDR" (libpng, SDL_image, GameSmith's PNG backgrounds). Such a program is handed host PNG
	 * files as they are: Abbaye des Morts (an SDL port) opened "graphics/intro.png" with fopen, read the PICT copy and its
	 * IMG_Load failed. Programs from before PNG (the ones the PICT copy is for) never carry the name.
	 */
	static boolean readsPng(File program){
		if(program==null||!program.isFile())return false;
		try{
			if(program.length()<=SCAN_LIMIT&&contains(Files.readAllBytes(program.toPath()),IHDR))return true;
			byte[] fork=ResourceFile68k.readForkBytes(program,null);
			return fork!=null&&contains(fork,IHDR);
		}catch(IOException|RuntimeException e){return false;}
	}
	private static final byte[] IHDR={'I','H','D','R'};
	private static final long SCAN_LIMIT=256L<<20;
	private static boolean contains(byte[] data,byte[] what){
		outer:for(int i=0;i+what.length<=data.length;i++){for(int j=0;j<what.length;j++)if(data[i+j]!=what[j])continue outer;return true;}
		return false;
	}

	/** PNG or JPEG by their magic numbers. */
	static boolean looksLikeImage(byte[] b){
		if(b.length<8)return false;
		if((b[0]&0xff)==0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G')return true;
		return (b[0]&0xff)==0xff&&(b[1]&0xff)==0xd8&&(b[2]&0xff)==0xff;
	}

	/** A PICT file (512-byte header, then the picture) showing {@code host}, scaled to at most {@link #MAX_SIDE}; null when the bytes are not an image Java can read. */
	static byte[] toProgram(byte[] host){
		try{
			BufferedImage image=javax.imageio.ImageIO.read(new ByteArrayInputStream(host));
			if(image==null||image.getWidth()<=0||image.getHeight()<=0)return null;
			int width=image.getWidth(),height=image.getHeight();
			double scale=Math.min(1.0,Math.min((double)MAX_SIDE/width,(double)MAX_SIDE/height));
			int w=Math.max(1,(int)Math.round(width*scale)),h=Math.max(1,(int)Math.round(height*scale));
			BufferedImage opaque=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g=opaque.createGraphics();
			g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,w,h); // transparency becomes white, as on paper
			if(scale<1.0){g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,w,h,null);}
			else g.drawImage(image,0,0,null);
			g.dispose();
			byte[] picture=HCResourceExport.pict(opaque);
			byte[] file=new byte[HEADER+picture.length];System.arraycopy(picture,0,file,HEADER,picture.length);
			return file;
		}catch(IOException|RuntimeException e){return null;}
	}

	/**
	 * The picture inside a PICT file, or null when the bytes are not one: after the 512-byte header come picSize,
	 * the frame and the version opcode ($1101 for version 1, $0011 $02FF for version 2).
	 */
	static byte[] pictureOf(byte[] file){
		if(file.length<HEADER+14)return null;
		int at=HEADER;
		int top=(short)((file[at+2]&0xff)<<8|file[at+3]&0xff),left=(short)((file[at+4]&0xff)<<8|file[at+5]&0xff),bottom=(short)((file[at+6]&0xff)<<8|file[at+7]&0xff),right=(short)((file[at+8]&0xff)<<8|file[at+9]&0xff);
		if(right<=left||bottom<=top)return null;
		// NOP opcodes (zero bytes) may come before the version opcode: QuickDraw skips them (Infini-D 1.0.2 leaves 30 there)
		int v=at+10,limit=Math.min(file.length-4,at+10+256);while(v<limit&&file[v]==0&&file[v+1]==0)v++;
		boolean v1=file[v]==0x11&&file[v+1]==0x01||file[v]==0x00&&file[v+1]==0x11&&file[v+2]==0x01,v2=file[v]==0x00&&file[v+1]==0x11&&file[v+2]==0x02&&(file[v+3]&0xff)==0xff;
		if(!v1&&!v2)return null;
		byte[] picture=new byte[file.length-HEADER];System.arraycopy(file,HEADER,picture,0,picture.length);return picture;
	}

	/** {@code image} as PNG bytes. */
	static byte[] png(BufferedImage image) throws IOException {
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		if(!javax.imageio.ImageIO.write(image,"png",out))throw new IOException("PNG not written");
		return out.toByteArray();
	}

	/** The picture of a PICT file played by the host's own decoder (the fallback when the program's QuickDraw cannot be used). */
	static BufferedImage renderWithHost(byte[] file){
		try{
			File temp=TempFiles.createFile("hyperzebra-pict-",".pict");
			try{Files.write(temp.toPath(),file);return PictureFile.loadPICT(temp.getPath());}finally{temp.delete();}
		}catch(IOException|RuntimeException e){return null;}
	}
}

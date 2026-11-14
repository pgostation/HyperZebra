import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Still images stored with a QuickTime ImageDescription (PICT opcodes 0x8200/0x8201): decoded on the host into ARGB
 * pixels. JPEG, PNG, GIF and TIFF go through ImageIO; the codecs written here ('raw ', 'rle ', 'smc ', '8BPS', 'WRLE') are
 * in the core's HzQtImage. Anything else yields null.
 */
final class QuickTimeImage68k {
	private QuickTimeImage68k(){}

	static String codecName(byte[] description){return HzQtImage.codecName(description);}

	/** ARGB rows of the image (width x height from the description), or null when the codec is not decodable. */
	static int[] decode(byte[] description,byte[] data){return HzQtImage.decode(description,data,QuickTimeImage68k::hostDecode);}

	static int[] planarRgb(byte[] data,int width,int height,int depth){return HzQtImage.planarRgb(data,width,height,depth);}
	static int[] windowsBitmap(byte[] description,byte[] data,int width,int height,int depth){return HzQtImage.windowsBitmap(description,data,width,height,depth);}

	private static int[] hostDecode(byte[] data,int width,int height){
		try{
			BufferedImage image=Toolbox68k.PARTS.readImage(data);if(image==null)return null;
			int[] argb=new int[width*height];int w=Math.min(width,image.getWidth()),h=Math.min(height,image.getHeight());
			for(int y=0;y<h;y++)for(int x=0;x<w;x++)argb[y*width+x]=image.getRGB(x,y)|0xff000000;
			for(int y=h;y<height;y++)for(int x=0;x<width;x++)argb[y*width+x]=0xffffffff;
			for(int y=0;y<h;y++)for(int x=w;x<width;x++)argb[y*width+x]=0xffffffff;
			return argb;
		}catch(RuntimeException e){return null;}
	}
}

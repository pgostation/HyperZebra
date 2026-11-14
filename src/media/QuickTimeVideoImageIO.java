import java.awt.image.BufferedImage;
import java.io.IOException;

/** The desktop's JPEG reader for QuickTimeVideo (found by name): a JPEG the own decoder does not read, by Java's ImageIO */
final class QuickTimeVideoImageIO implements QuickTimeVideo.JpegFallback {
	@Override public void decode(byte[] b,int off,int len,int[] frame,int width,int height,int first,int step) throws IOException {
		java.util.Iterator<javax.imageio.ImageReader> readers=javax.imageio.ImageIO.getImageReadersByFormatName("jpeg");
		if(!readers.hasNext())throw new IOException("no JPEG reader");
		javax.imageio.ImageReader reader=readers.next();
		BufferedImage image;
		try(javax.imageio.stream.ImageInputStream in=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(b,off,len))){
			reader.setInput(in,true,true);image=reader.read(0);
		}catch(IOException|RuntimeException e){return;} // a damaged image: the picture stays
		finally{reader.dispose();}
		java.awt.image.Raster r=image.getRaster();int bands=r.getNumBands();int[] px=new int[bands];
		boolean bgr=image.getType()==BufferedImage.TYPE_3BYTE_BGR;
		for(int y=0;y<r.getHeight();y++){
			int line=first+y*step;if(line>=height)break;
			for(int x=0;x<Math.min(width,r.getWidth());x++){
				r.getPixel(x,y,px);
				frame[line*width+x]=bands==1?px[0]*0x010101:bgr||bands>=3?px[0]<<16|px[1]<<8|px[2]:0;
			}
		}
	}
}

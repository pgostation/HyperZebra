import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The compressors behind the native Image Compression Manager (CompressImage, FCompressImage, CompressPicture): an image
 * as RGB pixels becomes the sample data of a QuickTime codec, read back by HyperZebra's own decoders (QuickTimeVideo for
 * movies, QuickTimeImage68k for pictures) and by QuickTime itself.
 * <ul>
 * <li>'smc ' (Graphics): 8-bit pixels of the Mac system palette in 4x4 blocks - repeats of the block before, one-colour
 *     runs, 2/4/8-colour blocks (their colour sets kept in the three 256-entry rings both sides fill) or 16 literal pixels.
 *     UxCardToMovie makes its movies with it, UxCompressRsrc tries it against Photo JPEG;</li>
 * <li>'rle ' (Animation), 32-bit: per line a skip byte, then literal and repeat runs, -1 at the end of the line;</li>
 * <li>'raw ', 32-bit xRGB rows; 'jpeg' (Photo JPEG) through ImageIO (PictureCompression68k.encode).</li>
 * </ul>
 */
final class ImageCodecs68k {
	static final int SMC=0x736d6320,RLE=0x726c6520,RAW=0x72617720,JPEG=0x6a706567;
	private ImageCodecs68k(){}

	/** Whether {@code codec} can be compressed here. */
	static boolean supported(int codec){return codec==SMC||codec==RLE||codec==RAW||codec==JPEG;}

	/** The most bytes {@code codec} gives for an image of that size (GetMaxCompressionSize). */
	static int maxSize(int codec,int width,int height){
		int pixels=Math.max(1,width)*Math.max(1,height);
		switch(codec){
			case SMC:return 4+((width+3)/4)*((height+3)/4)*17+64;
			case RLE:return 6+height*(2+1)+pixels*5+64;
			default:return pixels*4+1024;
		}
	}

	/** The pixel depth {@code codec}'s data has (the ImageDescription's depth). */
	static int depth(int codec){return codec==SMC?8:codec==JPEG?24:32;}
	/** The codec's name as the Image Compression Manager gives it. */
	static String name(int codec){return codec==SMC?"Graphics":codec==RLE?"Animation":codec==JPEG?"Photo - JPEG":"None";}

	/** {@code rgb} (0xRRGGBB, row-major) compressed with {@code codec}; null for a codec not made here. */
	static byte[] encode(int codec,int[] rgb,int width,int height,int quality){
		switch(codec){
			case SMC:return smc(rgb,width,height);
			case RLE:return animation(rgb,width,height);
			case RAW:{byte[] raw=new byte[width*height*4];for(int i=0,o=0;i<width*height;i++,o+=4){raw[o]=(byte)0xff;raw[o+1]=(byte)(rgb[i]>>16);raw[o+2]=(byte)(rgb[i]>>8);raw[o+3]=(byte)rgb[i];}return raw;}
			case JPEG:{java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,width,height,rgb,0,width);
				return PictureCompression68k.encode(image,PictureCompression68k.JPEG,quality);}
			default:return null;
		}
	}

	/** An ImageDescription (86 bytes) for {@code codec}'s data of an image of that size: 8-bit data uses the system palette (clutID 8). */
	static byte[] description(int codec,int width,int height,int quality,int dataSize){
		byte[] d=new byte[86];
		put4(d,0,86);put4(d,4,codec);put2(d,14,1);put2(d,16,1);put2(d,18,1);put4(d,20,0x6170706c); // dataRefIndex 1, version, revision, 'appl'
		put4(d,24,0);put4(d,28,Math.min(quality,0x400));put2(d,32,width);put2(d,34,height);put4(d,36,72<<16);put4(d,40,72<<16);
		put4(d,44,dataSize);put2(d,48,1);
		byte[] n=name(codec).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);d[50]=(byte)n.length;System.arraycopy(n,0,d,51,n.length);
		put2(d,82,depth(codec));put2(d,84,depth(codec)==8?8:-1);
		return d;
	}

	// ------------------------------------------------------------------ 'smc '

	/** The system palette index nearest to each colour, remembered per colour. */
	private static final Map<Integer,Integer> NEAREST=new java.util.concurrent.ConcurrentHashMap<Integer,Integer>();
	static int paletteIndex(int rgb){
		rgb&=0xffffff;Integer known=NEAREST.get(rgb);if(known!=null)return known;
		int r=rgb>>16&255,g=rgb>>8&255,b=rgb&255,best=0;long bestDistance=Long.MAX_VALUE;
		for(int i=0;i<256;i++){int c=MacSystemPalette.rgb(i);int dr=(c>>16&255)-r,dg=(c>>8&255)-g,db=(c&255)-b;long d=(long)dr*dr*30+(long)dg*dg*59+(long)db*db*11;if(d<bestDistance){bestDistance=d;best=i;if(d==0)break;}}
		if(NEAREST.size()<65536)NEAREST.put(rgb,best);
		return best;
	}

	static byte[] smc(int[] rgb,int width,int height){
		int across=(width+3)/4,total=across*((height+3)/4);
		int[][] blocks=new int[total][16];
		for(int b=0;b<total;b++){int x0=(b%across)*4,y0=(b/across)*4;
			for(int k=0;k<16;k++){int x=Math.min(width-1,x0+(k&3)),y=Math.min(height-1,y0+(k>>2));blocks[b][k]=paletteIndex(rgb[y*width+x]);}}
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		out.write(0xe1);out.write(0);out.write(0);out.write(0); // flags, chunk size (below)
		// the colour-set rings of the decoder: the set each entry holds, to point at it again
		String[][] rings={new String[256],new String[256],new String[256]};int[] next=new int[3];
		Map<String,Integer>[] where=new Map[]{new HashMap<String,Integer>(),new HashMap<String,Integer>(),new HashMap<String,Integer>()};
		int i=0;
		while(i<total){
			if(i>0&&java.util.Arrays.equals(blocks[i],blocks[i-1])){ // the block before, again
				int n=1;while(i+n<total&&n<256&&java.util.Arrays.equals(blocks[i+n],blocks[i-1]))n++;
				if(n<=16)out.write(0x20|(n-1));else{out.write(0x30);out.write(n-1);}
				i+=n;continue;
			}
			int[] colors=distinct(blocks[i]);
			if(colors.length==1){ // one colour
				int c=colors[0],n=1;while(i+n<total&&n<256&&uniform(blocks[i+n],c))n++;
				if(n<=16)out.write(0x60|(n-1));else{out.write(0x70);out.write(n-1);}
				out.write(c);i+=n;continue;
			}
			if(colors.length>8){ // sixteen literal pixels
				int n=1;while(i+n<total&&n<16&&distinct(blocks[i+n]).length>8)n++;
				out.write(0xe0|(n-1));for(int k=0;k<n;k++)for(int p:blocks[i+k])out.write(p);
				i+=n;continue;
			}
			int set=colors.length<=2?0:colors.length<=4?1:2,size=2<<set;
			int[] palette=java.util.Arrays.copyOf(colors,size);for(int k=colors.length;k<size;k++)palette[k]=colors[0];
			String key=java.util.Arrays.toString(palette);
			// the blocks after it that these colours paint, not already a repeat or one colour
			int n=1;while(i+n<total&&n<16&&!java.util.Arrays.equals(blocks[i+n],blocks[i+n-1])&&distinct(blocks[i+n]).length>1&&within(blocks[i+n],palette))n++;
			Integer cached=where[set].get(key);
			if(cached!=null&&key.equals(rings[set][cached])){out.write((0x90+0x20*set)|(n-1));out.write(cached);}
			else{
				out.write((0x80+0x20*set)|(n-1));for(int c:palette)out.write(c);
				int slot=next[set];if(rings[set][slot]!=null)where[set].remove(rings[set][slot]);rings[set][slot]=key;where[set].put(key,slot);next[set]=(next[set]+1)&255;
			}
			for(int k=0;k<n;k++)paint(out,blocks[i+k],palette,set);
			i+=n;
		}
		byte[] data=out.toByteArray();data[1]=(byte)(data.length>>16);data[2]=(byte)(data.length>>8);data[3]=(byte)data.length;
		return data;
	}
	private static int[] distinct(int[] block){int[] seen=new int[16];int n=0;outer:for(int p:block){for(int k=0;k<n;k++)if(seen[k]==p)continue outer;seen[n++]=p;}int[] r=java.util.Arrays.copyOf(seen,n);java.util.Arrays.sort(r);return r;}
	private static boolean uniform(int[] block,int c){for(int p:block)if(p!=c)return false;return true;}
	private static boolean within(int[] block,int[] palette){for(int p:block)if(indexOf(palette,p)<0)return false;return true;}
	private static int indexOf(int[] palette,int c){for(int k=0;k<palette.length;k++)if(palette[k]==c)return k;return -1;}
	/** One block's pixels as indexes into its 2, 4 or 8 colours (1, 2 or 3 bits a pixel, the 3-bit ones in the codec's order). */
	private static void paint(ByteArrayOutputStream out,int[] block,int[] palette,int set){
		if(set==0){int flags=0;for(int k=0;k<16;k++)flags|=indexOf(palette,block[k])<<(15-k);out.write(flags>>8);out.write(flags);return;}
		if(set==1){for(int row=0;row<4;row++){int v=0;for(int k=0;k<4;k++)v|=indexOf(palette,block[row*4+k])<<(6-2*k);out.write(v);}return;}
		int top=0,bottom=0;for(int k=0;k<8;k++){top|=indexOf(palette,block[k])<<(21-3*k);bottom|=indexOf(palette,block[8+k])<<(21-3*k);}
		int v1=((top>>8)&0xfff0)|((bottom>>8)&0xf),v2=((top&0xfff)<<4)|((bottom>>4)&0xf),v3=(((bottom>>12)&0xfff)<<4)|(bottom&0xf);
		out.write(v1>>8);out.write(v1);out.write(v2>>8);out.write(v2);out.write(v3>>8);out.write(v3);
	}

	// ------------------------------------------------------------------ 'rle '

	/** Apple Animation at 32 bits: no partial frame, every line from its start (skip 1), runs of up to 127 pixels. */
	static byte[] animation(int[] rgb,int width,int height){
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		out.write(0);out.write(0);out.write(0);out.write(0);out.write(0);out.write(0); // chunk size (below), header 0: the whole frame
		for(int y=0;y<height;y++){
			out.write(1); // skip byte: start at the line's first pixel
			int x=0;
			while(x<width){
				int c=rgb[y*width+x],n=1;while(x+n<width&&n<127&&rgb[y*width+x+n]==c)n++;
				if(n>=2){out.write(-n&0xff);pixel(out,c);x+=n;continue;}
				int start=x;n=0;while(x<width&&n<127&&!(x+1<width&&rgb[y*width+x+1]==rgb[y*width+x])){x++;n++;}
				if(n==0){x++;n=1;}
				out.write(n);for(int k=0;k<n;k++)pixel(out,rgb[y*width+start+k]);
			}
			out.write(0xff); // -1: end of the line
		}
		byte[] data=out.toByteArray();put4(data,0,data.length);
		return data;
	}
	private static void pixel(ByteArrayOutputStream out,int c){out.write(0xff);out.write(c>>16);out.write(c>>8);out.write(c);}

	private static void put2(byte[] d,int at,int v){d[at]=(byte)(v>>8);d[at+1]=(byte)v;}
	private static void put4(byte[] d,int at,int v){put2(d,at,v>>16);put2(d,at+2,v);}
}

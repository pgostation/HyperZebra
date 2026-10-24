import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Still images stored with a QuickTime ImageDescription (PICT opcodes 0x8200/0x8201): decoded on the host into ARGB
 * pixels. JPEG, PNG, GIF and TIFF go through ImageIO; 'raw ' (uncompressed), 'rle ' (Apple Animation) and 'smc ' (Apple Graphics) are decoded
 * here, as is '8BPS' (Planar RGB, PackBits rows per plane), for the depths QuickTime uses (8-bit indexed with the description's colour table or the system palette,
 * 16-bit 1-5-5-5, 24-bit RGB, 32-bit ARGB, 8-bit grey). Anything else yields null.
 */
final class QuickTimeImage68k {
	private QuickTimeImage68k(){}

	static String codecName(byte[] description){return description.length<8?"?":new String(description,4,4,java.nio.charset.StandardCharsets.ISO_8859_1);}

	/** ARGB rows of the image (width x height from the description), or null when the codec is not decodable. */
	static int[] decode(byte[] description,byte[] data){
		if(description.length<86)return null;
		int codec=MacApp68k.u4(description,4),width=MacApp68k.u2(description,32),height=MacApp68k.u2(description,34),depth=(short)MacApp68k.u2(description,82);
		if(width<=0||height<=0||(long)width*height>64L*1024*1024)return null;
		switch(codec){
			case 0x6a706567:case 0x706e6720:case 0x67696620:case 0x74696666:case 0x6d6a7061:case 0x6d6a7062: // jpeg png gif tiff mjpa mjpb
				return hostDecode(data,width,height);
			case 0x72617720:return raw(description,data,width,height,depth);   // raw
			case 0x726c6520:return rle(description,data,width,height,depth);   // rle
			case 0x736d6320:return smc(description,data,width,height,depth);   // smc  (Apple Graphics)
			case 0x38425053:return planarRgb(data,width,height,depth);         // 8BPS (Planar RGB, the Photoshop codec)
			case 0x57524c45:return windowsBitmap(description,data,width,height,depth); // WRLE (QuickTime's BMP codec)
			default:return null;
		}
	}

	/**
	 * '8BPS' (Planar RGB): a table of big-endian 16-bit packed row lengths for every row of every plane (red, green, blue and,
	 * at 32 bits, alpha), then the rows, each PackBits-compressed, plane after plane. Penguin Power's title sprites use it.
	 */
	static int[] planarRgb(byte[] data,int width,int height,int depth){
		int planes=depth==32?4:3;int[] shift={16,8,0,24};
		int[] argb=new int[width*height];if(planes==3)java.util.Arrays.fill(argb,0xff000000);
		int table=0,p=planes*height*2;
		if(p>data.length)return null;
		for(int plane=0;plane<planes;plane++)for(int row=0;row<height;row++){
			int length=MacApp68k.u2(data,table);table+=2;int end=Math.min(data.length,p+length),x=0,base=row*width;
			while(p<end){
				int count=data[p++];
				if(count>=0){for(int i=0;i<=count&&p<end;i++,p++)if(x<width)argb[base+x++]|=(data[p]&0xff)<<shift[plane];}
				else{if(p>=end)break;int v=(data[p++]&0xff)<<shift[plane];for(int i=0;i<1-count;i++)if(x<width)argb[base+x++]|=v;}
			}
			p=end;
		}
		return argb;
	}

	private static int[] hostDecode(byte[] data,int width,int height){
		try{
			BufferedImage image=ImageIO.read(new ByteArrayInputStream(data));if(image==null)return null;
			int[] argb=new int[width*height];int w=Math.min(width,image.getWidth()),h=Math.min(height,image.getHeight());
			for(int y=0;y<h;y++)for(int x=0;x<w;x++)argb[y*width+x]=image.getRGB(x,y)|0xff000000;
			for(int y=h;y<height;y++)for(int x=0;x<width;x++)argb[y*width+x]=0xffffffff;
			for(int y=0;y<h;y++)for(int x=w;x<width;x++)argb[y*width+x]=0xffffffff;
			return argb;
		}catch(IOException|RuntimeException e){return null;}
	}

	/** The colour table for an indexed image: the description's own (stored right after its 86 bytes: clutID 0 with a longer
	 *  description - Myst Preview's 'smc ' pictures - or -1) or the system palette. */
	private static int[] colorTable(byte[] description,int depth){
		int clutId=(short)MacApp68k.u2(description,84),count=1<<Math.min(8,depth&31);int[] colors=new int[count];
		if((clutId==-1||clutId==0)&&description.length>=86+8&&MacApp68k.u4(description,0)>=86+8&&(short)MacApp68k.u2(description,86+6)>=0){int entries=(short)MacApp68k.u2(description,86+6)+1;
			boolean device=(MacApp68k.u2(description,86+4)&0x8000)!=0; // ctFlags: a device table's entries are in index order, their value fields unused
			for(int i=0;i<entries&&86+8+i*8+7<description.length;i++){int o=86+8+i*8,value=device?i&(count-1):MacApp68k.u2(description,o)&0xff;if(value>=count)value=i&(count-1);
				colors[value]=(MacApp68k.u2(description,o+2)>>>8)<<16|(MacApp68k.u2(description,o+4)>>>8)<<8|MacApp68k.u2(description,o+6)>>>8;}
			return colors;}
		if(depth>32){for(int i=0;i<count;i++){int g=count==1?255:255-i*255/(count-1);colors[i]=g<<16|g<<8|g;}return colors;} // greyscale depths 33..40
		if(count==256)for(int i=0;i<256;i++)colors[i]=MacSystemPalette.rgb(i);
		else if(count==2){colors[0]=0xffffff;colors[1]=0;}
		else if(count==4){colors[0]=0xffffff;colors[1]=0xff0000;colors[2]=0x00ff00;colors[3]=0;}
		else {int[] classic={0xffffff,0xffff00,0xff6600,0xdd0907,0xf31584,0x4700a5,0x0000d4,0x02abeb,0x1fb714,0x006512,0x562c05,0x91713a,0xc0c0c0,0x808080,0x404040,0};System.arraycopy(classic,0,colors,0,16);}
		return colors;
	}

	private static int[] raw(byte[] description,byte[] data,int width,int height,int depth){
		int bits=depth>32?depth-32:depth;int[] argb=new int[width*height];java.util.Arrays.fill(argb,0xffffffff);
		int[] clut=bits<=8?colorTable(description,depth):null;
		int rowBytes=bits>=8?width*(bits/8):(width*bits+7)/8;if(bits==32)rowBytes=width*4;if(bits==24)rowBytes=width*3;if(bits==16)rowBytes=width*2;
		for(int y=0;y<height;y++){int row=y*rowBytes;if(row>=data.length)break;
			for(int x=0;x<width;x++){int pixel;
				switch(bits){
					case 32:{int o=row+x*4;if(o+3>=data.length)continue;pixel=0xff000000|(data[o+1]&0xff)<<16|(data[o+2]&0xff)<<8|(data[o+3]&0xff);break;}
					case 24:{int o=row+x*3;if(o+2>=data.length)continue;pixel=0xff000000|(data[o]&0xff)<<16|(data[o+1]&0xff)<<8|(data[o+2]&0xff);break;}
					case 16:{int o=row+x*2;if(o+1>=data.length)continue;int v=(data[o]&0xff)<<8|(data[o+1]&0xff);pixel=rgb555(v);break;}
					case 8:{int o=row+x;if(o>=data.length)continue;pixel=0xff000000|clut[data[o]&0xff];break;}
					default:{int bit=x*bits,o=row+(bit>>>3);if(o>=data.length)continue;int v=(data[o]&0xff)>>>(8-bits-(bit&7))&((1<<bits)-1);pixel=0xff000000|clut[v];break;}
				}
				argb[y*width+x]=pixel;}}
		return argb;
	}
	/**
	 * 'WRLE', QuickTime's codec for Windows bitmaps ("BMP"): the pixels of a BMP without its headers, rows bottom up and padded
	 * to four bytes - stored (BI_RGB: 8- or 4-bit indices into the description's colour table, 24-bit as blue, green, red) or,
	 * when shorter than that, run-length coded as BMP's RLE8/RLE4. PgColorX's "NumFont" picture font is an 8-bit one.
	 */
	static int[] windowsBitmap(byte[] description,byte[] data,int width,int height,int depth){
		int bits=depth>32?depth-32:depth;if(bits!=4&&bits!=8&&bits!=24)return null;
		int rowBytes=(width*bits+31)/32*4;int[] argb=new int[width*height];java.util.Arrays.fill(argb,0xffffffff);
		int[] clut=bits<=8?colorTable(description,depth):null;
		// the description's 'bmp ' extension holds the BMP compression (0 stored, 1 RLE8, 2 RLE4); without one, a stream shorter
		// than the stored rows is run-length coded
		int compression=-1;
		for(int i=90;i+8<=description.length;i++)if(description[i]=='b'&&description[i+1]=='m'&&description[i+2]=='p'&&description[i+3]==' '&&MacApp68k.u4(description,i-4)>=12){compression=MacApp68k.u4(description,i+4);break;}
		if(compression==0||compression<0&&data.length>=rowBytes*height||bits==24){
			for(int y=0;y<height;y++){int row=(height-1-y)*rowBytes;
				for(int x=0;x<width;x++){
					if(bits==24){int o=row+x*3;if(o+2<data.length)argb[y*width+x]=0xff000000|(data[o+2]&0xff)<<16|(data[o+1]&0xff)<<8|(data[o]&0xff);}
					else{int o=row+x*bits/8;if(o<data.length){int v=bits==8?data[o]&0xff:(data[o]&0xff)>>>(x%2==0?4:0)&15;argb[y*width+x]=0xff000000|clut[v];}}}}
			return argb;
		}
		// RLE8/RLE4: count + index (a run), or 0 + 0 end of line, 0 + 1 end of bitmap, 0 + 2 + dx + dy a skip, 0 + n literal indices
		// padded to a word
		int x=0,y=height-1,p=0;
		while(p+1<data.length&&y>=0){int count=data[p]&0xff,value=data[p+1]&0xff;p+=2;
			if(count>0){for(int i=0;i<count;i++,x++){int v=bits==8?value:(i%2==0?value>>>4:value&15);if(x<width)argb[y*width+x]=0xff000000|clut[v];}continue;}
			if(value==0){x=0;y--;continue;}
			if(value==1)break;
			if(value==2){if(p+1>=data.length)break;x+=data[p]&0xff;y-=data[p+1]&0xff;p+=2;continue;}
			int bytes=bits==8?value:(value+1)/2;
			for(int i=0;i<value;i++,x++){int o=p+(bits==8?i:i/2);if(o>=data.length)break;int v=bits==8?data[o]&0xff:(i%2==0?(data[o]&0xff)>>>4:data[o]&15);if(x<width&&y>=0)argb[y*width+x]=0xff000000|clut[v];}
			p+=bytes+(bytes&1);
		}
		return argb;
	}

	private static int rgb555(int v){int r=(v>>>10&31)*255/31,g=(v>>>5&31)*255/31,b=(v&31)*255/31;return 0xff000000|r<<16|g<<8|b;}

	/**
	 * Apple Animation ('rle '): chunk size, a header whose bit 3 announces a starting line and line count (else the whole
	 * frame), then per line a skip count and run-length codes: positive = that many pixels (groups of four at 8 bits)
	 * follow literally, negative = one pixel (group) repeated, 0 = another skip, -1 = end of line. Lines not covered stay
	 * white (a key frame is expected: there is no previous frame to keep).
	 */
	private static int[] rle(byte[] description,byte[] data,int width,int height,int depth){
		int bits=depth>32?depth-32:depth;if(bits!=8&&bits!=16&&bits!=24&&bits!=32)return null;
		int[] argb=new int[width*height];java.util.Arrays.fill(argb,0xffffffff);
		if(data.length<8)return argb;int[] clut=bits==8?colorTable(description,depth):null;
		int p=4;int header=MacApp68k.u2(data,p);p+=2;int startLine=0,lines=height;
		if((header&8)!=0){if(data.length<p+8)return argb;startLine=MacApp68k.u2(data,p);lines=MacApp68k.u2(data,p+4);p+=8;}
		int group=bits==8?4:1;
		try{
			for(int line=0;line<lines;line++){int y=startLine+line;if(y>=height)break;
				int x=((data[p++]&0xff)-1)*group;
				while(true){int code=data[p++];if(code==-1)break;
					if(code==0){x+=((data[p++]&0xff)-1)*group;continue;}
					if(code<0){int repeat=-code;int[] pixel=new int[group];for(int i=0;i<group;i++){pixel[i]=readPixel(data,p,bits,clut);p+=bits/8;}
						while(repeat-->0)for(int i=0;i<group;i++){if(x>=0&&x<width)argb[y*width+x]=pixel[i];x++;}}
					else{int count=code*group;while(count-->0){if(x>=0&&x<width)argb[y*width+x]=readPixel(data,p,bits,clut);p+=bits/8;x++;}}
				}
			}
		}catch(ArrayIndexOutOfBoundsException truncated){/* keep what was decoded */}
		return argb;
	}
	/**
	 * 'smc ' (Apple Graphics): 8-bit indexed pixels in 4x4 blocks, left to right and top to bottom, after a flags byte and a
	 * 24-bit chunk length. Each opcode's high nibble says what follows and its low nibble a block count less one (for
	 * the odd ones 0x10/0x30/0x50/0x70 the count less one is the next byte): skip blocks, repeat the last block or the
	 * last two, fill with one colour, or paint with 2, 4 or 8 colours (1, 2 or 3 bits a pixel) taken either from the data
	 * (and remembered in a 256-entry ring of such sets) or from that ring by index, or 16 raw pixels. QuickMooV's logo.
	 */
	private static int[] smc(byte[] description,byte[] data,int width,int height,int depth){
		if((depth&31)!=8||data.length<4)return null;
		int[] clut=colorTable(description,depth),index=new int[width*height],argb=new int[width*height];
		int end=Math.min(data.length,(data[1]&0xff)<<16|(data[2]&0xff)<<8|(data[3]&0xff));if(end<4)end=data.length;
		int blocksWide=(width+3)/4,total=blocksWide*((height+3)/4),block=0,p=4;
		byte[][] rings={new byte[256*2],new byte[256*4],new byte[256*8]};int[] next=new int[3];
		try{
			while(block<total&&p<end){
				int op=data[p++]&0xff,kind=op&0xf0,n=(op&0x0f)+1;
				if(kind==0x10||kind==0x30||kind==0x50||kind==0x70)n=(data[p++]&0xff)+1;
				switch(kind){
					case 0x00:case 0x10:block+=n;break;                                  // skip
					case 0x20:case 0x30:for(int i=0;i<n&&block<total&&block>0;i++,block++)copyBlock(index,width,height,blocksWide,block-1,block);break; // repeat last
					case 0x40:case 0x50:for(int i=0;i<n*2&&block<total&&block>1;i++,block++)copyBlock(index,width,height,blocksWide,block-2,block);break; // repeat last two
					case 0x60:case 0x70:{int c=data[p++]&0xff;for(int i=0;i<n&&block<total;i++,block++)fillBlock(index,width,height,blocksWide,block,c);break;}
					case 0x80:case 0x90:case 0xa0:case 0xb0:case 0xc0:case 0xd0:{
						int set=(kind-0x80)>>5,size=2<<set;byte[] ring=rings[set];int base;
						if((kind&0x10)==0){base=next[set]*size;for(int i=0;i<size;i++)ring[base+i]=data[p++];next[set]=(next[set]+1)&255;}
						else base=(data[p++]&0xff)*size;
						for(int i=0;i<n&&block<total;i++,block++){
							int[] pixels=new int[16];
							if(set==0){int flags=(data[p]&0xff)<<8|(data[p+1]&0xff);p+=2;for(int k=0;k<16;k++)pixels[k]=ring[base+((flags>>>(15-k))&1)]&0xff;}
							else if(set==1){for(int row=0;row<4;row++){int f=data[p++]&0xff;for(int k=0;k<4;k++)pixels[row*4+k]=ring[base+((f>>>(6-2*k))&3)]&0xff;}}
							else{int a=(data[p]&0xff)<<8|(data[p+1]&0xff),b=(data[p+2]&0xff)<<8|(data[p+3]&0xff),c=(data[p+4]&0xff)<<8|(data[p+5]&0xff);p+=6;
								int top=((a&0xfff0)<<8)|(b>>>4),bottom=((c&0xfff0)<<8)|((a&0x0f)<<8)|((b&0x0f)<<4)|(c&0x0f); // 8 3-bit indexes each for rows 0-1 and 2-3
								for(int k=0;k<16;k++){int bits=k<8?top:bottom;pixels[k]=ring[base+((bits>>>(21-3*(k&7)))&7)]&0xff;}}
							putBlock(index,width,height,blocksWide,block,pixels);
						}
						break;}
					case 0xe0:for(int i=0;i<n&&block<total;i++,block++){int[] pixels=new int[16];for(int k=0;k<16;k++)pixels[k]=data[p++]&0xff;putBlock(index,width,height,blocksWide,block,pixels);}break;
					default:block=total;break; // 0xF0: not defined
				}
			}
		}catch(ArrayIndexOutOfBoundsException truncated){}
		for(int i=0;i<argb.length;i++)argb[i]=0xff000000|clut[index[i]&0xff];
		return argb;
	}
	private static void putBlock(int[] index,int width,int height,int blocksWide,int block,int[] pixels){
		int x0=(block%blocksWide)*4,y0=(block/blocksWide)*4;
		for(int k=0;k<16;k++){int x=x0+(k&3),y=y0+(k>>2);if(x<width&&y<height)index[y*width+x]=pixels[k];}
	}
	private static void fillBlock(int[] index,int width,int height,int blocksWide,int block,int color){int[] pixels=new int[16];java.util.Arrays.fill(pixels,color);putBlock(index,width,height,blocksWide,block,pixels);}
	private static void copyBlock(int[] index,int width,int height,int blocksWide,int from,int to){
		int fx=(from%blocksWide)*4,fy=(from/blocksWide)*4;int[] pixels=new int[16];
		for(int k=0;k<16;k++){int x=fx+(k&3),y=fy+(k>>2);pixels[k]=x<width&&y<height?index[y*width+x]:0;}
		putBlock(index,width,height,blocksWide,to,pixels);
	}

	private static int readPixel(byte[] data,int o,int bits,int[] clut){
		switch(bits){
			case 8:return 0xff000000|clut[data[o]&0xff];
			case 16:return rgb555((data[o]&0xff)<<8|(data[o+1]&0xff));
			case 24:return 0xff000000|(data[o]&0xff)<<16|(data[o+1]&0xff)<<8|(data[o+2]&0xff);
			default:return 0xff000000|(data[o+1]&0xff)<<16|(data[o+2]&0xff)<<8|(data[o+3]&0xff);
		}
	}
}

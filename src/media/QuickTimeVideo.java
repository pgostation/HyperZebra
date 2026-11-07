import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pictures of a QuickTime movie decoded without QuickTime: each enabled video track of a {@link QuickTimeFile} is
 * decoded sample by sample into a frame buffer of its image description's size (frame-differenced samples change the
 * previous picture), and the tracks are drawn into the movie box through their matrices and the movie matrix, back
 * layer first, as QuickTime draws a movie into its GWorld.
 *
 * Codecs ({@link Codec}): 'raw ' (1, 2, 4, 8-bit indexed with the description's or a standard colour table, 1-8-bit grey,
 * 16, 24, 32-bit direct), 'rle ' (Animation), 'smc ' (Graphics), 'rpza' (Video), 'cvid' (Cinepak), 'jpeg' (Photo JPEG) and
 * 'mjpa' (Motion JPEG A) through {@link JpegDecoder} (within a few levels of QuickTime: its IDCT is not reproduced yet).
 * tools/qt/QtVideoOracle compares the frames with the ones the installed QuickTime draws.
 */
final class QuickTimeVideo {
	/** Decodes one sample into the frame buffer ({@code width x height} xRGB), which holds the previous picture. */
	interface Codec {
		void decode(byte[] data,QuickTimeFile.VideoDescription d,int[] frame,int width,int height) throws IOException;
	}

	private static final Map<String,Codec> CODECS=new HashMap<String,Codec>();
	/** Codecs with state of their own between samples (codebooks), made once per track. */
	private static final Map<String,java.util.function.Supplier<Codec>> STATEFUL=new HashMap<String,java.util.function.Supplier<Codec>>();
	static{STATEFUL.put("cvid",Cinepak::new);}
	static{
		CODECS.put("raw ",QuickTimeVideo::raw);
		CODECS.put("rle ",QuickTimeVideo::animation);
		CODECS.put("smc ",new Graphics());
		CODECS.put("rpza",QuickTimeVideo::roadPizza);
		CODECS.put("jpeg",QuickTimeVideo::jpeg);
		CODECS.put("mjpa",QuickTimeVideo::jpeg);
	}
	/** Whether a description's compressor is decoded here. */
	static boolean canDecode(QuickTimeFile.SampleDescription d){String f=QuickTimeFile.typeName(d.format);return d instanceof QuickTimeFile.VideoDescription&&(CODECS.containsKey(f)||STATEFUL.containsKey(f));}
	static boolean canDecode(QuickTimeFile.Track t){
		if(t.media==null||!t.media.video()||t.media.descriptions.isEmpty())return false;
		for(QuickTimeFile.SampleDescription d:t.media.descriptions)if(!canDecode(d))return false;
		return true;
	}
	/** The enabled video tracks with samples. */
	static List<QuickTimeFile.Track> videoTracks(QuickTimeFile q){
		List<QuickTimeFile.Track> list=new ArrayList<QuickTimeFile.Track>();
		for(QuickTimeFile.Track t:q.tracks)if(t.enabled()&&t.media!=null&&t.media.video()&&t.media.samples()>0)list.add(t);
		return list;
	}
	static boolean canDecode(QuickTimeFile q){
		List<QuickTimeFile.Track> tracks=videoTracks(q);if(tracks.isEmpty())return false;
		for(QuickTimeFile.Track t:tracks)if(!canDecode(t))return false;
		return true;
	}

	// ------------------------------------------------------------------ one track

	/** A video track's decoder: the current picture and the sample it shows. */
	static final class TrackDecoder {
		final QuickTimeFile.Track track;final QuickTimeFile.Media media;
		int width,height;int[] frame;long current=-1;
		private final Map<String,Codec> own=new HashMap<String,Codec>();
		/** The sample shown could not be decoded: a frame-differenced sample whose description is not its key frame's -
		 *  QuickTime then draws nothing (the erased movie shows). */
		boolean blank;
		TrackDecoder(QuickTimeFile.Track track){
			this.track=track;media=track.media;
			QuickTimeFile.VideoDescription d=(QuickTimeFile.VideoDescription)media.description(media.chunkCount()>0?media.chunkDescription(0):1);
			width=Math.max(1,d==null?(track.width>>16):d.width);height=Math.max(1,d==null?(track.height>>16):d.height);
			frame=new int[width*height];
		}
		/** Shows sample {@code n}: decoded from the sync sample at or before it (or on from the current one). */
		void show(long n) throws IOException {
			if(n==current||n<0)return;
			long sync=media.syncBefore(n),from=sync;
			int key=media.chunkDescription(media.chunkOf(sync));
			for(long s=sync+1;s<=n;s++)if(media.chunkDescription(media.chunkOf(s))!=key){blank=true;current=n;return;}
			if(!blank&&current>=sync&&current<n)from=current+1; // go on from the picture shown
			blank=false;
			for(long s=from;s<=n;s++)decode(s);
			current=n;
		}
		private void decode(long n) throws IOException {
			int chunk=media.chunkOf(n);
			QuickTimeFile.SampleDescription sd=media.description(media.chunkDescription(chunk));
			if(!(sd instanceof QuickTimeFile.VideoDescription))throw new IOException("sample "+n+" has no image description");
			QuickTimeFile.VideoDescription d=(QuickTimeFile.VideoDescription)sd;
			String f=QuickTimeFile.typeName(d.format);
			Codec codec=CODECS.get(f);
			if(codec==null&&STATEFUL.containsKey(f))codec=own.computeIfAbsent(f,k->STATEFUL.get(k).get());
			if(codec==null)throw new IOException("video format '"+QuickTimeFile.typeName(d.format)+"' is not decoded");
			if(d.width!=width||d.height!=height){width=Math.max(1,d.width);height=Math.max(1,d.height);frame=new int[width*height];}
			byte[] data;
			try{data=media.readSample(n);}catch(IOException e){return;} // past the end of a truncated file: the picture stays
			codec.decode(data,d,frame,width,height);
		}
	}

	// ------------------------------------------------------------------ the movie

	private final QuickTimeFile movie;
	private final List<TrackDecoder> decoders=new ArrayList<TrackDecoder>();
	final int[] box; // top, left, bottom, right

	QuickTimeVideo(QuickTimeFile movie){
		this.movie=movie;box=movie.box();
		List<QuickTimeFile.Track> tracks=videoTracks(movie);
		// back to front: a larger layer number is further back
		tracks.sort((a,b)->Integer.compare(b.layer,a.layer));
		for(QuickTimeFile.Track t:tracks)if(canDecode(t))decoders.add(new TrackDecoder(t));
	}
	boolean decodersEmpty(){return decoders.isEmpty();}
	/** Closes the movie's data forks (the frames are read no more). */
	void close(){movie.close();}
	int width(){return Math.max(1,box[3]-box[1]);}
	int height(){return Math.max(1,box[2]-box[0]);}

	int timeScale(){return movie.timeScale>0?movie.timeScale:600;}
	long durationMillis(){return movie.duration*1000L/timeScale();}
	/** Samples of the first video track (a frame count for players that step by frames). */
	int frameCount(){return decoders.isEmpty()?0:decoders.get(decoders.size()-1).media.samples();}
	private BufferedImage last;private long[] lastShown;
	/** The picture returned last (null before the first). */
	BufferedImage lastFrame(){return last;}

	/**
	 * The movie at movie time {@code t} (units of the movie's time scale), the size of the movie box. While every track
	 * shows the sample it showed before, the previous picture comes back (the same object).
	 */
	BufferedImage frameAt(long t) throws IOException {
		// a movie at its end (time = duration, where no edit plays) keeps showing its last frame, as QuickTime's does: Myst's
		// LibraryStairsDown movie turned white when the next card's update redrew it at the end
		if(movie.duration>0&&t>=movie.duration)t=movie.duration-1;
		long[] shown=new long[decoders.size()];
		for(int i=0;i<shown.length;i++){TrackDecoder dec=decoders.get(i);long mt=dec.track.mediaTimeAt(t);long n=mt<0?-1:dec.media.sampleAt(mt);if(mt>=0&&n<0)n=dec.media.samples()-1;shown[i]=n;}
		if(last!=null&&java.util.Arrays.equals(shown,lastShown))return last;
		lastShown=shown;last=draw(t);return last;
	}
	private BufferedImage draw(long t) throws IOException {
		int w=width(),h=height();
		BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
		int[] out=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
		java.util.Arrays.fill(out,0xffffff); // QuickTime erases the movie's GWorld to white
		for(TrackDecoder dec:decoders){
			long mt=dec.track.mediaTimeAt(t);if(mt<0)continue;
			long n=dec.media.sampleAt(mt);if(n<0)n=dec.media.samples()-1;
			dec.show(n);
			draw(dec,out,w,h);
		}
		return image;
	}

	/** Draws a track's picture through its matrix and the movie matrix (nearest pixel), relative to the movie box. */
	private void draw(TrackDecoder dec,int[] out,int w,int h){
		int[] m=dec.track.matrix,mm=movie.matrix;
		double sx=dec.track.width/65536.0/dec.width,sy=dec.track.height/65536.0/dec.height; // the picture fills the track's dimensions
		if(dec.track.width==0||dec.track.height==0){sx=1;sy=1;}
		// combined affine map of picture pixel -> movie box pixel (no perspective)
		double a=m[0]/65536.0,b=m[1]/65536.0,c=m[3]/65536.0,d=m[4]/65536.0,tx=m[6]/65536.0,ty=m[7]/65536.0;
		double A=mm[0]/65536.0,B=mm[1]/65536.0,C=mm[3]/65536.0,D=mm[4]/65536.0,TX=mm[6]/65536.0,TY=mm[7]/65536.0;
		double a2=a*A+b*C,b2=a*B+b*D,c2=c*A+d*C,d2=c*B+d*D,tx2=tx*A+ty*C+TX-box[1],ty2=tx*B+ty*D+TY-box[0];
		a2*=sx;b2*=sx;c2*=sy;d2*=sy;
		double det=a2*d2-b2*c2;if(det==0)return;
		if(b2==0&&c2==0&&a2>0&&d2>0){
			// scaling and translation: the picture is stretched onto the rounded destination rectangle the way QuickDraw's
			// CopyBits stretches, source = floor(offset x source size / destination size) (SpinProd!: exact, where sampling at
			// pixel centres missed a third of the pixels)
			int left=(int)Math.round(tx2),top=(int)Math.round(ty2),right=(int)Math.round(tx2+a2*dec.width),bottom=(int)Math.round(ty2+d2*dec.height);
			int dw=right-left,dh=bottom-top;if(dw<=0||dh<=0)return;
			for(int y=Math.max(0,top);y<Math.min(h,bottom);y++){
				int py=(int)((long)(y-top)*dec.height/dh);
				for(int x=Math.max(0,left);x<Math.min(w,right);x++){
					int px=(int)((long)(x-left)*dec.width/dw);
					if(dec.blank)out[y*w+x]=0xffffff;else out[y*w+x]=dec.frame[py*dec.width+px];
				}
			}
			return;
		}
		// inverse map for every destination pixel centre
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){
			double X=x+0.5-tx2,Y=y+0.5-ty2;
			double u=(X*d2-Y*c2)/det,v=(Y*a2-X*b2)/det;
			int px=(int)Math.floor(u),py=(int)Math.floor(v);
			if(px<0||py<0||px>=dec.width||py>=dec.height)continue;
			out[y*w+x]=dec.blank?0xffffff:dec.frame[py*dec.width+px];
		}
	}

	/** Enabled tracks that draw but are not decoded here (text, sprites...): the movie's pictures would miss them. */
	static List<QuickTimeFile.Track> otherVisualTracks(QuickTimeFile q){
		List<QuickTimeFile.Track> list=new ArrayList<QuickTimeFile.Track>();
		for(QuickTimeFile.Track t:q.tracks)if(t.enabled()&&t.media!=null&&!t.media.video()&&!t.media.sound()&&t.media.handlerType!=QuickTimeFile.MUSI&&t.visual()&&t.media.samples()>0)list.add(t);
		return list;
	}

	/**
	 * Movie times at which video samples appear: each sample's first movie time through the edits (rounded up), sorted,
	 * at most {@code max} spread evenly (the comparison points of tools/qt/QtVideoOracle and tests/QuickTimeVideoTest).
	 */
	static List<Long> sampleTimes(QuickTimeFile q,int max){
		java.util.TreeSet<Long> set=new java.util.TreeSet<Long>();
		for(QuickTimeFile.Track t:videoTracks(q)){
			QuickTimeFile.Media m=t.media;long start=0;
			for(QuickTimeFile.Edit e:t.edits){
				if(e.mediaTime!=-1&&m.timeScale>0){
					double rate=e.rate==0?1:e.rate/65536.0;
					for(long n=0;n<m.samples();n++){
						long st=Math.max(m.sampleTime(n),e.mediaTime);
						if(st>=m.sampleTime(n)+m.sampleDuration(n))continue;
						long mt=(long)Math.ceil(start+(st-e.mediaTime)/rate*q.timeScale/m.timeScale-1e-9);
						if(mt>=start&&mt<start+e.duration)set.add(mt);
					}
				}
				start+=e.duration;
			}
		}
		List<Long> all=new ArrayList<Long>(set);
		if(all.size()<=max)return all;
		List<Long> some=new ArrayList<Long>();for(int i=0;i<max;i++)some.add(all.get((int)((long)i*all.size()/max)));
		return some;
	}
	/** The RGB bytes of a picture (3 a pixel, row by row): what the reference hashes cover. */
	static byte[] rgb(BufferedImage image){
		int w=image.getWidth(),h=image.getHeight();byte[] out=new byte[w*h*3];int p=0;
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=image.getRGB(x,y);out[p++]=(byte)(c>>16);out[p++]=(byte)(c>>8);out[p++]=(byte)c;}
		return out;
	}

	// ------------------------------------------------------------------ colour tables

	/** Standard 16-colour table (clut 4): white, yellow, orange, red, magenta, purple, blue, cyan, green, dark green, brown, tan, greys, black. */
	static final int[] CLUT4={0xffffff,0xfcf305,0xff6402,0xdd0806,0xf20884,0x4600a5,0x0000d4,0x02abea,0x1fb714,0x006411,0x562c05,0x90713a,0xc0c0c0,0x808080,0x404040,0x000000};

	/** The colours of an indexed image description: its own table, else the standard one of its depth (grey for depths 33-40). */
	static int[] colorTable(QuickTimeFile.VideoDescription d){
		int bits=d.depth>32?d.depth-32:d.depth,n=1<<Math.min(8,Math.max(1,bits));
		int[] t=new int[n];
		if(d.colorTable!=null&&!d.grey()){for(int i=0;i<n&&i<d.colorTable.length;i++)t[i]=d.colorTable[i]&0xffffff;return t;}
		if(d.grey()||bits==2){for(int i=0;i<n;i++){int v=255-i*255/(n-1);t[i]=v<<16|v<<8|v;}return t;}
		if(bits==1){t[0]=0xffffff;t[1]=0;return t;}
		if(bits==4)return CLUT4.clone();
		for(int i=0;i<n;i++)t[i]=MacSystemPalette.rgb(i);
		return t;
	}

	// ------------------------------------------------------------------ 'raw '

	/** Uncompressed pixels; the rows are padded (to 4 bytes in the samples seen), so the row length comes from the sample size. */
	static void raw(byte[] data,QuickTimeFile.VideoDescription d,int[] frame,int width,int height){
		int bits=d.depth>32?d.depth-32:d.depth;
		int rowBytes=Math.max((width*bits+7)/8,data.length/Math.max(1,height));
		if(bits<=8){
			int[] table=colorTable(d);int mask=(1<<bits)-1,perByte=8/bits;
			for(int y=0;y<height;y++){int row=y*rowBytes;
				for(int x=0;x<width;x++){int p=row+x/perByte;if(p>=data.length)return;
					int v=(data[p]>>(8-bits-(x%perByte)*bits))&mask;frame[y*width+x]=table[v];}}
			return;
		}
		for(int y=0;y<height;y++){int row=y*rowBytes;
			for(int x=0;x<width;x++){
				int rgb;
				if(bits==16){int p=row+x*2;if(p+1>=data.length)return;rgb=rgb555((data[p]&0xff)<<8|(data[p+1]&0xff));}
				else if(bits==24){int p=row+x*3;if(p+2>=data.length)return;rgb=(data[p]&0xff)<<16|(data[p+1]&0xff)<<8|(data[p+2]&0xff);}
				else{int p=row+x*4;if(p+3>=data.length)return;rgb=(data[p+1]&0xff)<<16|(data[p+2]&0xff)<<8|(data[p+3]&0xff);}
				frame[y*width+x]=rgb;
			}
		}
	}
	// ------------------------------------------------------------------ 'rle ' (Animation)

	/**
	 * Apple Animation: a changed band of lines, each a skip then runs. Sample: size.l (a sample of fewer than 8 bytes
	 * changes nothing), header.w; with header bit 3 the band's first line.w, a word, its line count.w and a word follow
	 * (else every line). A line starts with a skip byte (skip - 1 units), then codes: a positive count of literal units,
	 * a negative count repeating one unit, 0 followed by another skip byte, -1 ending the line. A unit is 16 pixels at
	 * 1 and 2 bits, 8 at 4 bits, 4 at 8 bits, one pixel at 16, 24 and 32 bits.
	 */
	static void animation(byte[] b,QuickTimeFile.VideoDescription d,int[] frame,int width,int height) throws IOException {
		if(b.length<8)return;
		int bits=d.depth>32?d.depth-32:d.depth;
		int unitPixels=bits==1||bits==2?16:bits==4?8:bits==8?4:1,unitBytes=bits<=8?unitPixels*bits/8:bits/8;
		int[] table=bits<=8?colorTable(d):null;
		int p=4,header=u2(b,p);p+=2;
		int line=0,lines=height;
		if((header&0x0008)!=0){line=u2(b,p);lines=u2(b,p+4);p+=8;}
		if(bits==1){ // 1-bit: a skip byte before every code (units to skip; bit 7 = on the next line), frame ends with 80 00
			int x=0;line--;
			while(p+1<b.length){
				int skip=b[p++]&0xff,code=b[p++];
				if((skip&0x80)!=0){line++;x=0;}
				x+=(skip&0x7f)*unitPixels;
				if(line>=height)return;
				if(code>0){for(int i=0;i<code;i++){unit(b,p,bits,unitPixels,table,frame,width,line,x);p+=unitBytes;x+=unitPixels;}}
				else if(code<0){for(int i=0;i<-code;i++){unit(b,p,bits,unitPixels,table,frame,width,line,x);x+=unitPixels;}p+=unitBytes;}
			}
			return;
		}
		for(int l=0;l<lines&&line<height;l++,line++){
			if(p>=b.length)return;
			int x=((b[p++]&0xff)-1)*unitPixels;
			while(p<b.length){
				int code=b[p++];
				if(code==-1)break;
				if(code==0){if(p>=b.length)return;x+=((b[p++]&0xff)-1)*unitPixels;continue;}
				if(code>0){for(int i=0;i<code;i++){unit(b,p,bits,unitPixels,table,frame,width,line,x);p+=unitBytes;x+=unitPixels;}}
				else{for(int i=0;i<-code;i++){unit(b,p,bits,unitPixels,table,frame,width,line,x);x+=unitPixels;}p+=unitBytes;}
			}
		}
	}
	/** Writes one unit of pixels at (x, line), clipped to the frame. */
	private static void unit(byte[] b,int p,int bits,int pixels,int[] table,int[] frame,int width,int line,int x){
		if(p+Math.max(1,bits<=8?pixels*bits/8:bits/8)>b.length)return;
		int row=line*width;
		if(bits<=8){int mask=(1<<bits)-1;
			for(int i=0;i<pixels;i++){int xx=x+i;if(xx<0||xx>=width)continue;int v=(b[p+i*bits/8]>>(8-bits-(i*bits)%8))&mask;frame[row+xx]=table[v];}
			return;}
		if(x<0||x>=width)return;
		frame[row+x]=bits==16?rgb555(u2(b,p)):bits==24?(b[p]&0xff)<<16|(b[p+1]&0xff)<<8|(b[p+2]&0xff):(b[p+1]&0xff)<<16|(b[p+2]&0xff)<<8|(b[p+3]&0xff);
	}
	private static int u2(byte[] b,int p){return p+1<b.length?(b[p]&0xff)<<8|(b[p+1]&0xff):0;}

	// ------------------------------------------------------------------ 4x4 blocks

	/** Copies block {@code from} to block {@code to} (raster order, {@code across} blocks a row), clipped to the frame. */
	private static void copyBlock(int[] frame,int width,int height,int across,int from,int to){
		if(from<0)return;
		int fx=(from%across)*4,fy=(from/across)*4,tx=(to%across)*4,ty=(to/across)*4;
		for(int y=0;y<4;y++)for(int x=0;x<4;x++){
			if(fx+x>=width||fy+y>=height||tx+x>=width||ty+y>=height)continue;
			frame[(ty+y)*width+tx+x]=frame[(fy+y)*width+fx+x];
		}
	}
	/** Writes a block's 16 pixels (row by row). */
	private static void putBlock(int[] frame,int width,int height,int across,int block,int[] pixels){
		int bx=(block%across)*4,by=(block/across)*4;
		for(int y=0;y<4;y++)for(int x=0;x<4;x++){if(bx+x<width&&by+y<height)frame[(by+y)*width+bx+x]=pixels[y*4+x];}
	}

	/**
	 * Apple Graphics ('smc '), 8-bit: 4x4 blocks in raster order. Sample: flags byte, size (3 bytes), then opcodes whose low
	 * nibble is a count - 1 (with bit 4 set the count - 1 follows in a byte instead): $00/$10 skip blocks, $20/$30 repeat the
	 * block before, $40/$50 repeat the two blocks before, $60/$70 fill with one colour, $80/$90 two colours (1 bit a pixel),
	 * $A0/$B0 four colours (2 bits), $C0/$D0 eight colours (3 bits, in a shuffled order), $E0 sixteen literal pixels. The
	 * odd opcodes of the 2, 4 and 8-colour groups take their colours from a 256-entry cache that every even one fills.
	 */
	static final class Graphics implements Codec {
		@Override public void decode(byte[] b,QuickTimeFile.VideoDescription d,int[] frame,int width,int height){
			int[] table=colorTable(d);
			int across=(width+3)/4,total=across*((height+3)/4),block=0,p=4,end=Math.min(b.length,(b[1]&0xff)<<16|(b[2]&0xff)<<8|(b[3]&0xff));
			int[][] pairs=new int[256][2],quads=new int[256][4],octets=new int[256][8];int next2=0,next4=0,next8=0;
			int[] px=new int[16];
			while(p<end&&block<total){
				int op=b[p++]&0xff,type=op&0xf0,n=(op&0x0f)+1;
				if((type==0x10||type==0x30||type==0x50||type==0x70)&&p<end)n=(b[p++]&0xff)+1;
				switch(type){
					case 0x00:case 0x10:block+=n;break;
					case 0x20:case 0x30:for(int i=0;i<n&&block<total;i++,block++)copyBlock(frame,width,height,across,block-1,block);break;
					case 0x40:case 0x50:{int a=block-2,c=block-1;for(int i=0;i<n&&block<total;i++){copyBlock(frame,width,height,across,a,block++);if(block<total)copyBlock(frame,width,height,across,c,block++);}break;}
					case 0x60:case 0x70:{int color=table[b[p++]&0xff];java.util.Arrays.fill(px,color);for(int i=0;i<n&&block<total;i++)putBlock(frame,width,height,across,block++,px);break;}
					case 0x80:case 0x90:{
						int[] c;if(type==0x80){c=pairs[next2];c[0]=b[p++]&0xff;c[1]=b[p++]&0xff;next2=(next2+1)&255;}else c=pairs[b[p++]&0xff];
						for(int i=0;i<n&&block<total&&p+1<b.length;i++){int flags=(b[p]&0xff)<<8|(b[p+1]&0xff);p+=2;
							for(int k=0;k<16;k++)px[k]=table[c[(flags>>(15-k))&1]];putBlock(frame,width,height,across,block++,px);}
						break;}
					case 0xa0:case 0xb0:{
						int[] c;if(type==0xa0){c=quads[next4];for(int k=0;k<4;k++)c[k]=b[p++]&0xff;next4=(next4+1)&255;}else c=quads[b[p++]&0xff];
						for(int i=0;i<n&&block<total&&p+3<b.length;i++){
							for(int r=0;r<4;r++){int v=b[p++]&0xff;for(int k=0;k<4;k++)px[r*4+k]=table[c[(v>>(6-2*k))&3]];}
							putBlock(frame,width,height,across,block++,px);}
						break;}
					case 0xc0:case 0xd0:{
						int[] c;if(type==0xc0){c=octets[next8];for(int k=0;k<8;k++)c[k]=b[p++]&0xff;next8=(next8+1)&255;}else c=octets[b[p++]&0xff];
						for(int i=0;i<n&&block<total&&p+5<b.length;i++){
							int v1=(b[p]&0xff)<<8|(b[p+1]&0xff),v2=(b[p+2]&0xff)<<8|(b[p+3]&0xff),v3=(b[p+4]&0xff)<<8|(b[p+5]&0xff);p+=6;
							int top=((v1&0xfff0)<<8)|(v2>>4),bottom=((v3&0xfff0)<<8)|((v1&0x0f)<<8)|((v2&0x0f)<<4)|(v3&0x0f);
							for(int k=0;k<8;k++){px[k]=table[c[(top>>(21-3*k))&7]];px[8+k]=table[c[(bottom>>(21-3*k))&7]];}
							putBlock(frame,width,height,across,block++,px);}
						break;}
					case 0xe0:for(int i=0;i<n&&block<total&&p+15<b.length;i++){for(int k=0;k<16;k++)px[k]=table[b[p++]&0xff];putBlock(frame,width,height,across,block++,px);}break;
					default:return; // $F0: not used
				}
			}
		}
	}

	/**
	 * Apple Video ('rpza', "Road Pizza"), 16-bit: 4x4 blocks in raster order. Sample: $E1, size (3 bytes), then opcodes:
	 * $80 + n-1 skip n blocks, $A0 + n-1 fill n blocks with the colour that follows, $C0 + n-1 n four-colour blocks after
	 * two colours (the two between them interpolated), 2 bits a pixel; an opcode below $80
	 * is the high byte of a colour: if the byte after its low byte has its top bit set, a second colour and one
	 * four-colour block follow, else the 15 other pixels of a literal block.
	 */
	static void roadPizza(byte[] b,QuickTimeFile.VideoDescription d,int[] frame,int width,int height){
		int across=(width+3)/4,total=across*((height+3)/4),block=0,p=4,end=Math.min(b.length,(b[1]&0xff)<<16|(b[2]&0xff)<<8|(b[3]&0xff));
		int[] px=new int[16],pal=new int[4];
		while(p<end&&block<total){
			int op=b[p++]&0xff;
			if(op>=0x80){
				int n=(op&0x1f)+1;
				switch(op&0xe0){
					case 0x80:block+=n;break;
					case 0xa0:{int color=rpza8(u2(b,p));p+=2;java.util.Arrays.fill(px,color);for(int i=0;i<n&&block<total;i++)putBlock(frame,width,height,across,block++,px);break;}
					case 0xc0:{int a=u2(b,p),c=u2(b,p+2);p+=4;palette(a,c,pal);for(int i=0;i<n&&block<total;i++){fourColors(b,p,pal,px);p+=4;putBlock(frame,width,height,across,block++,px);}break;}
					default:return; // $E0: not used
				}
				continue;
			}
			int a=op<<8|(b[p++]&0xff);
			if(p<b.length&&(b[p]&0x80)!=0){int c=u2(b,p)&0x7fff;p+=2;palette(a,c,pal);fourColors(b,p,pal,px);p+=4;putBlock(frame,width,height,across,block++,px);}
			else{px[0]=rpza8(a);for(int k=1;k<16;k++){px[k]=rpza8(u2(b,p));p+=2;}putBlock(frame,width,height,across,block++,px);}
		}
	}
	/**
	 * Apple Video's four colours from two: index 3 = a, 0 = b, 1 and 2 in between. QuickTime's decompressor writes 32-bit
	 * pixels itself: a 5-bit component becomes c << 3 (not repeated into the low bits as QuickDraw does), and the colours
	 * in between are (a + 3b) / 4 and (3a + b) / 4 of those 8-bit values (fitted against QuickTime's output).
	 */
	private static void palette(int a,int b,int[] pal){
		pal[3]=rpza8(a);pal[0]=rpza8(b);
		int[] v1=new int[3],v2=new int[3];
		for(int s=0;s<3;s++){int ca=((a>>(s*5))&31)<<3,cb=((b>>(s*5))&31)<<3;v1[s]=(ca+3*cb)>>2;v2[s]=(3*ca+cb)>>2;}
		pal[1]=v1[2]<<16|v1[1]<<8|v1[0];pal[2]=v2[2]<<16|v2[1]<<8|v2[0];
	}
	/** A 16-bit pixel as Apple Video's decompressor writes it to 32 bits: each 5-bit component shifted left by 3. */
	private static int rpza8(int p){return ((p>>10)&31)<<19|((p>>5)&31)<<11|(p&31)<<3;}
	private static void fourColors(byte[] b,int p,int[] pal,int[] px){
		for(int r=0;r<4;r++){int v=p+r<b.length?b[p+r]&0xff:0;for(int k=0;k<4;k++)px[r*4+k]=pal[(v>>(6-2*k))&3];}
	}

	// ------------------------------------------------------------------ 'cvid' (Cinepak)

	/**
	 * Cinepak: a frame of horizontal strips, each drawn from two codebooks of 2x2 pixel entries (4 luma values, and in
	 * colour two chroma values): V4 blocks use four entries (one per 2x2 quarter of a 4x4 block), V1 blocks one entry
	 * doubled to 4x4. Codebooks run on from frame to frame per strip, and - unless the frame's flags have bit 0 set - a
	 * strip starts from the codebooks of the strip above it.
	 * Frame: flags byte, size (3 bytes), width.w, height.w, strips.w. Strip: id.w ($1000 key, $1100 inter), size.w,
	 * top.w, left.w, bottom.w, right.w (top 0: under the previous strip, bottom = its height). Chunks: id.w, size.w -
	 * $20xx/$24xx full V4 codebook (colour / grey), $21xx/$25xx partial (32-bit masks select entries), $22xx..$27xx the same
	 * for V1; $3000 key frame blocks (a mask bit a block: V4 or V1), $3100 inter blocks (a bit: changed, then V4/V1),
	 * $3200 V1 blocks only. Colour: R = Y + 2V, G = Y - U/2 - V, B = Y + 2U.
	 */
	static final class Cinepak implements Codec {
		private int[][][] v4=new int[0][][],v1=new int[0][][]; // per strip: 256 entries of 4 xRGB pixels

		@Override public void decode(byte[] b,QuickTimeFile.VideoDescription d,int[] frame,int width,int height) throws IOException {
			if(b.length<10)return;
			int size=Math.min(b.length,(b[1]&0xff)<<16|(b[2]&0xff)<<8|(b[3]&0xff));
			int strips=u2(b,8),p=10,top=0;
			if(v4.length<strips){int[][][] n4=new int[strips][][],n1=new int[strips][][];
				for(int i=0;i<strips;i++){n4[i]=i<v4.length?v4[i]:new int[256][4];n1[i]=i<v1.length?v1[i]:new int[256][4];}v4=n4;v1=n1;}
			for(int s=0;s<strips&&p+12<=size;s++){
				int id=u2(b,p),stripSize=u2(b,p+2),y0=u2(b,p+4),x0=u2(b,p+6),y1=u2(b,p+8),x1=u2(b,p+10);
				int stripEnd=Math.min(size,p+stripSize);
				if(s>0&&(b[0]&1)==0){ // without frame flag 1 a strip starts from the codebooks the strip before it ended with (else from its own of the frame before)
					for(int i=0;i<256;i++){v4[s][i]=v4[s-1][i].clone();v1[s][i]=v1[s-1][i].clone();}}
				int sy=y0==0?top:y0,sh=y0==0?y1:y1-y0;
				top=sy+sh;
				int q=p+12;
				while(q+4<=stripEnd){
					int cid=u2(b,q),csize=u2(b,q+2);if(csize<4)break;
					int cend=Math.min(stripEnd,q+csize),c=q+4;
					switch(cid&0xff00){
						case 0x2000:case 0x2400:codebook(b,c,cend,v4[s],(cid&0x0400)!=0,false);break;
						case 0x2100:case 0x2500:codebook(b,c,cend,v4[s],(cid&0x0400)!=0,true);break;
						case 0x2200:case 0x2600:codebook(b,c,cend,v1[s],(cid&0x0400)!=0,false);break;
						case 0x2300:case 0x2700:codebook(b,c,cend,v1[s],(cid&0x0400)!=0,true);break;
						case 0x3000:case 0x3100:case 0x3200:blocks(b,c,cend,cid&0xff00,v4[s],v1[s],frame,width,height,x0,sy,x1,sy+sh);break;
						default:break;
					}
					q=cend;
				}
				p=stripEnd;
			}
		}

		/** Reads codebook entries: every entry, or (partial) the ones whose bit in the 32-bit masks is set. */
		private static void codebook(byte[] b,int p,int end,int[][] book,boolean grey,boolean partial){
			int entry=grey?4:6,mask=0,bits=0;
			for(int i=0;i<256;i++){
				if(partial){
					if(bits==0){if(p+4>end)return;mask=(b[p]&0xff)<<24|(b[p+1]&0xff)<<16|(b[p+2]&0xff)<<8|(b[p+3]&0xff);p+=4;bits=32;}
					boolean set=(mask&0x80000000)!=0;mask<<=1;bits--;
					if(!set)continue;
				}
				if(p+entry>end)return;
				int u=grey?0:b[p+4],v=grey?0:b[p+5];
				for(int k=0;k<4;k++){int y=b[p+k]&0xff;book[i][k]=grey?y<<16|y<<8|y:yuv(y,u,v);}
				p+=entry;
			}
		}
		/** QuickTime's Cinepak colour: R = Y + 2V, G = Y - (U >> 1) - V, B = Y + 2U, and one more blue when V is negative
		 *  (measured against QuickTime's output, tools/qt/QtVideoOracle). */
		static int yuv(int y,int u,int v){
			int r=clip(y+2*v),g=clip(y-(u>>1)-v),bl=clip(y+2*u+(v<0?1:0));
			return r<<16|g<<8|bl;
		}
		private static int clip(int x){return x<0?0:x>255?255:x;}

		/** The 4x4 blocks of a strip, left to right, top to bottom. */
		private static void blocks(byte[] b,int p,int end,int kind,int[][] v4,int[][] v1,int[] frame,int width,int height,int x0,int y0,int x1,int y1){
			int mask=0,bits=0;
			for(int by=y0;by<y1;by+=4)for(int bx=x0;bx<x1;bx+=4){
				boolean useV4;
				if(kind==0x3200)useV4=false;
				else{
					if(bits==0){if(p+4>end)return;mask=(b[p]&0xff)<<24|(b[p+1]&0xff)<<16|(b[p+2]&0xff)<<8|(b[p+3]&0xff);p+=4;bits=32;}
					boolean bit=(mask&0x80000000)!=0;mask<<=1;bits--;
					if(kind==0x3100){
						if(!bit)continue; // unchanged
						if(bits==0){if(p+4>end)return;mask=(b[p]&0xff)<<24|(b[p+1]&0xff)<<16|(b[p+2]&0xff)<<8|(b[p+3]&0xff);p+=4;bits=32;}
						bit=(mask&0x80000000)!=0;mask<<=1;bits--;
					}
					useV4=bit;
				}
				if(useV4){
					if(p+4>end)return;
					for(int k=0;k<4;k++){int[] e=v4[b[p+k]&0xff];int ox=bx+(k&1)*2,oy=by+(k>>1)*2;
						put(frame,width,height,ox,oy,e[0]);put(frame,width,height,ox+1,oy,e[1]);put(frame,width,height,ox,oy+1,e[2]);put(frame,width,height,ox+1,oy+1,e[3]);}
					p+=4;
				}else{
					if(p+1>end)return;
					int[] e=v1[b[p++]&0xff];
					for(int k=0;k<4;k++){int ox=bx+(k&1)*2,oy=by+(k>>1)*2;
						put(frame,width,height,ox,oy,e[k]);put(frame,width,height,ox+1,oy,e[k]);put(frame,width,height,ox,oy+1,e[k]);put(frame,width,height,ox+1,oy+1,e[k]);}
				}
			}
		}
		private static void put(int[] frame,int width,int height,int x,int y,int c){if(x>=0&&y>=0&&x<width&&y<height)frame[y*width+x]=c;}
	}

	// ------------------------------------------------------------------ 'jpeg' (Photo JPEG), 'mjpa' (Motion JPEG A)

	/**
	 * A JPEG image, or for Motion JPEG A one or two fields: each field is a whole JPEG image whose APP1 'mjpg' segment
	 * gives the offset of the next field; two fields are interleaved line by line, the first stored on the even lines.
	 */
	static void jpeg(byte[] b,QuickTimeFile.VideoDescription d,int[] frame,int width,int height) throws IOException {
		int next=nextField(b);
		if(next<=0){decodeJpeg(b,0,b.length,frame,width,height,0,1);return;}
		decodeJpeg(b,0,next,frame,width,height,0,2);
		decodeJpeg(b,next,b.length-next,frame,width,height,1,2);
	}
	/** The offset of the second field of a Motion JPEG A sample (APP1 'mjpg' offsetToNextField), or 0. */
	private static int nextField(byte[] b){
		int p=2;
		while(p+4<=b.length&&(b[p]&0xff)==0xff){
			int marker=b[p+1]&0xff,len=u2(b,p+2);
			if(marker==0xda||marker==0xd9)break;
			if(marker==0xe1&&len>=16&&p+16<=b.length&&b[p+8]=='m'&&b[p+9]=='j'&&b[p+10]=='p'&&b[p+11]=='g'){
				int off=(b[p+20]&0xff)<<24|(b[p+21]&0xff)<<16|(b[p+22]&0xff)<<8|(b[p+23]&0xff);
				return off>0&&off<b.length?off:0;
			}
			p+=2+len;
		}
		return 0;
	}
	/** Decodes the JPEG at {@code off} into lines {@code first}, {@code first + step} ... of the frame (Java's JPEG reader). */
	private static void decodeJpeg(byte[] b,int off,int len,int[] frame,int width,int height,int first,int step) throws IOException {
		JpegDecoder j;
		try{j=JpegDecoder.decode(b,off,len);}catch(IOException e){j=null;}
		if(j!=null){
			for(int y=0;y<j.height;y++){int line=first+y*step;if(line>=height)break;System.arraycopy(j.rgb,y*j.width,frame,line*width,Math.min(width,j.width));}
			return;
		}
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

	/** A 16-bit xRRRRRGGGGGBBBBB pixel as 24-bit colour (each 5-bit component repeated into 8 bits). */
	static int rgb555(int p){int r=(p>>10)&31,g=(p>>5)&31,b=p&31;return (r<<3|r>>2)<<16|(g<<3|g>>2)<<8|(b<<3|b>>2);}
}

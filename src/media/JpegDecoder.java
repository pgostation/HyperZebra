import java.io.IOException;

/**
 * A baseline (sequential, Huffman, 8-bit) JPEG decoder written from ITU-T T.81, with the picture-forming steps done the
 * way QuickTime's JPEG decompressors do them where known (probe images drawn by the installed QuickTime,
 * tools/qt/JpegProbe): chroma replicated, QuickTime's fixed-point YCbCr to RGB. The inverse DCT is an exact one;
 * QuickTime's own (a fixed-point one whose DC gain varies with the quantiser) is not reproduced yet, so pictures differ
 * from QuickTime's by up to a few levels.
 */
final class JpegDecoder {
	/** The decoded picture: {@code width x height} xRGB. */
	int width,height;int[] rgb;
	/** Components after the inverse DCT: samples per component, its size in samples, sampling factors. */
	private int components;private final int[] compId=new int[4],hs=new int[4],vs=new int[4],tq=new int[4],td=new int[4],ta=new int[4];
	private int[][] planes=new int[4][];private final int[] planeW=new int[4],planeH=new int[4];
	private final int[][] quant=new int[4][];
	private final Huffman[] dc=new Huffman[4],ac=new Huffman[4];
	private int restartInterval,hmax,vmax,mcusX,mcusY;

	/** Decodes the JPEG image at {@code off}; throws on anything but a baseline/extended 8-bit Huffman image. */
	static JpegDecoder decode(byte[] b,int off,int len) throws IOException {
		JpegDecoder d=new JpegDecoder();d.read(b,off,off+len);return d;
	}

	private void read(byte[] b,int p,int end) throws IOException {
		if(p+2>end||(b[p]&0xff)!=0xff||(b[p+1]&0xff)!=0xd8)throw new IOException("not a JPEG image");
		p+=2;boolean frame=false;
		while(p+4<=end){
			if((b[p]&0xff)!=0xff){p++;continue;}
			int marker=b[p+1]&0xff;
			if(marker==0xff||marker==0x00||marker>=0xd0&&marker<=0xd8){p++;continue;}
			if(marker==0xd9)break;
			int len=u2(b,p+2),seg=p+4,segEnd=p+2+len;
			if(segEnd>end)throw new IOException("truncated JPEG segment");
			switch(marker){
				case 0xdb:{ // DQT
					int q=seg;
					while(q<segEnd){int pq=(b[q]>>4)&15,t=b[q]&3;q++;int[] table=new int[64];
						for(int i=0;i<64;i++){table[ZIGZAG[i]]=pq==0?b[q]&0xff:u2(b,q);q+=pq==0?1:2;}
						quant[t]=table;}
					break;
				}
				case 0xc4:{ // DHT
					int q=seg;
					while(q+17<=segEnd){int tc=(b[q]>>4)&1,th=b[q]&3;Huffman h=new Huffman(b,q+1);q+=17+h.count;if(tc==0)dc[th]=h;else ac[th]=h;}
					break;
				}
				case 0xdd:restartInterval=u2(b,seg);break;
				case 0xc0:case 0xc1:{ // SOF0, SOF1
					if((b[seg]&0xff)!=8)throw new IOException("JPEG precision "+(b[seg]&0xff));
					height=u2(b,seg+1);width=u2(b,seg+3);components=b[seg+5]&0xff;
					if(components<1||components>4||width==0||height==0)throw new IOException("JPEG frame header");
					hmax=1;vmax=1;
					for(int c=0;c<components;c++){int q=seg+6+c*3;compId[c]=b[q]&0xff;hs[c]=Math.max(1,(b[q+1]>>4)&15);vs[c]=Math.max(1,b[q+1]&15);tq[c]=b[q+2]&3;hmax=Math.max(hmax,hs[c]);vmax=Math.max(vmax,vs[c]);}
					mcusX=(width+8*hmax-1)/(8*hmax);mcusY=(height+8*vmax-1)/(8*vmax);
					for(int c=0;c<components;c++){planeW[c]=mcusX*hs[c]*8;planeH[c]=mcusY*vs[c]*8;planes[c]=new int[planeW[c]*planeH[c]];}
					frame=true;break;
				}
				case 0xc2:case 0xc3:case 0xc5:case 0xc6:case 0xc7:case 0xc9:case 0xca:case 0xcb:case 0xcd:case 0xce:case 0xcf:
					throw new IOException(String.format("JPEG process SOF%d",marker-0xc0));
				case 0xda:{ // SOS
					if(!frame)throw new IOException("JPEG scan before the frame header");
					int ns=b[seg]&0xff;int[] scan=new int[ns];
					for(int i=0;i<ns;i++){int id=b[seg+1+i*2]&0xff,tables=b[seg+2+i*2]&0xff;int c=0;while(c<components&&compId[c]!=id)c++;if(c==components)throw new IOException("JPEG scan component "+id);scan[i]=c;td[c]=tables>>4&3;ta[c]=tables&3;}
					p=scan(b,segEnd,end,scan);
					continue;
				}
				default:break; // APPn, COM ...
			}
			p=segEnd;
		}
		if(!frame)throw new IOException("no JPEG frame");
		toRgb();
	}

	// ------------------------------------------------------------------ entropy-coded data

	private byte[] data;private int pos,limit,bitBuf,bitCount;private boolean markerHit;

	private int scan(byte[] b,int p,int end,int[] scan) throws IOException {
		data=b;pos=p;limit=end;bitBuf=0;bitCount=0;markerHit=false;
		int[] pred=new int[components];int[] block=new int[64];
		boolean single=scan.length==1;
		int unitsX=single?(width*hs[scan[0]]/hmax+7)/8:mcusX,unitsY=single?(height*vs[scan[0]]/vmax+7)/8:mcusY;
		int total=unitsX*unitsY,restarts=0;
		for(int n=0;n<total;n++){
			if(restartInterval>0&&n>0&&n%restartInterval==0){ // RSTn: realign, reset the predictions
				bitCount=0;bitBuf=0;markerHit=false;
				while(pos+1<limit&&!((data[pos]&0xff)==0xff&&(data[pos+1]&0xff)>=0xd0&&(data[pos+1]&0xff)<=0xd7))pos++;
				if(pos+1<limit)pos+=2;
				java.util.Arrays.fill(pred,0);restarts++;
			}
			int mx=n%unitsX,my=n/unitsX;
			if(single){int c=scan[0];if(!unit(block,c,pred))return finish();put(block,c,mx*8,my*8);}
			else for(int c:scan)for(int v=0;v<vs[c];v++)for(int h=0;h<hs[c];h++){
				if(!unit(block,c,pred))return finish();
				put(block,c,(mx*hs[c]+h)*8,(my*vs[c]+v)*8);
			}
		}
		return finish();
	}
	/** Where the scan's data ended: the next marker. */
	private int finish(){
		int p=pos;
		while(p+1<limit&&!((data[p]&0xff)==0xff&&data[p+1]!=0&&((data[p+1]&0xff)<0xd0||(data[p+1]&0xff)>0xd7)))p++;
		return p;
	}
	/** One 8x8 data unit into {@code block} (dequantised, natural order); false at the end of the data. */
	private boolean unit(int[] block,int c,int[] pred) throws IOException {
		java.util.Arrays.fill(block,0);
		Huffman hd=dc[td[c]],ha=ac[ta[c]];int[] q=quant[tq[c]];
		if(hd==null||ha==null||q==null)throw new IOException("JPEG table missing");
		int t=hd.decode(this);if(t<0)return false;
		int diff=t==0?0:extend(bits(t),t);
		pred[c]+=diff;block[0]=pred[c]*q[0];
		for(int k=1;k<64;){
			int rs=ha.decode(this);if(rs<0)return false;
			int r=rs>>4,s=rs&15;
			if(s==0){if(r==15){k+=16;continue;}break;}
			k+=r;if(k>63)break;
			block[ZIGZAG[k]]=extend(bits(s),s)*q[ZIGZAG[k]];k++;
		}
		return true;
	}
	int bit(){
		if(bitCount==0){
			if(markerHit||pos>=limit){bitBuf=0;}
			else{int v=data[pos]&0xff;
				if(v==0xff){int n=pos+1<limit?data[pos+1]&0xff:0xd9;if(n==0){pos+=2;}else{markerHit=true;v=0;}}
				else pos++;
				bitBuf=v;}
			bitCount=8;
		}
		bitCount--;return (bitBuf>>bitCount)&1;
	}
	private int bits(int n){int v=0;for(int i=0;i<n;i++)v=v<<1|bit();return v;}
	boolean exhausted(){return bitCount==0&&(markerHit||pos>=limit);}
	private static int extend(int v,int t){return v<(1<<(t-1))?v-(1<<t)+1:v;}

	/** A Huffman table (T.81 Annex C): canonical codes by length. */
	static final class Huffman {
		final int count;final int[] maxCode=new int[18],valPtr=new int[17],minCode=new int[17];final int[] values;
		Huffman(byte[] b,int p){
			int[] lengths=new int[17];int n=0;for(int i=1;i<=16;i++){lengths[i]=b[p+i-1]&0xff;n+=lengths[i];}
			count=n;values=new int[n];for(int i=0;i<n;i++)values[i]=p+16+i<b.length?b[p+16+i]&0xff:0;
			int code=0,k=0;
			for(int l=1;l<=16;l++){
				valPtr[l]=k;minCode[l]=code;code+=lengths[l];k+=lengths[l];
				maxCode[l]=lengths[l]==0?-1:code-1;code<<=1;
			}
			maxCode[17]=Integer.MAX_VALUE;
		}
		int decode(JpegDecoder d){
			int code=0;
			for(int l=1;l<=16;l++){
				if(d.exhausted()&&l==1)return -1;
				code=code<<1|d.bit();
				if(maxCode[l]>=0&&code<=maxCode[l])return values[valPtr[l]+code-minCode[l]];
			}
			return -1;
		}
	}

	// ------------------------------------------------------------------ picture

	/** Inverse DCT of one data unit into component {@code c}'s plane at (x, y). */
	private void put(int[] block,int c,int x,int y){
		int[] out=IDCT.apply(block);
		int[] plane=planes[c];int w=planeW[c];
		if(x+8>w||y+8>planeH[c])return;
		for(int j=0;j<8;j++)System.arraycopy(out,j*8,plane,(y+j)*w+x,8);
	}

	/**
	 * The inverse DCT: 64 dequantised coefficients (natural order) to 64 samples in 1/32 steps, level-shifted (128 = 4096),
	 * not clamped: QuickTime's colour conversion takes the luma at that precision.
	 */
	interface Idct {int[] apply(int[] block);}
	static Idct IDCT=JpegDecoder::exactIdct;

	private static final double[][] COS=new double[8][8];
	static{for(int x=0;x<8;x++)for(int u=0;u<8;u++)COS[x][u]=(u==0?Math.sqrt(0.5):1)*Math.cos((2*x+1)*u*Math.PI/16);}
	/** The inverse DCT of T.81 A.3.3 in double precision. */
	static int[] exactIdct(int[] block){
		int[] out=new int[64];
		boolean dcOnly=true;for(int i=1;i<64&&dcOnly;i++)dcOnly=block[i]==0;
		if(dcOnly){java.util.Arrays.fill(out,(int)Math.round((COS[0][0]*COS[0][0]*block[0]/4+128)*32));return out;}
		double[] tmp=new double[64];boolean[] used=new boolean[8];
		for(int u=0;u<8;u++){
			boolean any=false;for(int v=0;v<8;v++)if(block[v*8+u]!=0){any=true;break;}
			if(!any)continue; // a zero column stays zero
			used[u]=true;
			for(int y=0;y<8;y++){double s=0;for(int v=0;v<8;v++)s+=COS[y][v]*block[v*8+u];tmp[y*8+u]=s;}
		}
		for(int y=0;y<8;y++)for(int x=0;x<8;x++){double s=0;for(int u=0;u<8;u++)if(used[u])s+=COS[x][u]*tmp[y*8+u];out[y*8+x]=(int)Math.round((s/4+128)*32);}
		return out;
	}

	/** Upsampled component {@code c} at picture pixel (x, y), in 1/32 steps: the sample the pixel lies in (QuickTime replicates). */
	private int sample(int c,int x,int y){
		int sx=x*hs[c]/hmax,sy=y*vs[c]/vmax;
		return planes[c][sy*planeW[c]+sx];
	}
	/**
	 * QuickTime's YCbCr to RGB (probe images over every Y x Cb, Y x Cr and Cb x Cr): the luma stays in 1/32 steps, the
	 * chroma is rounded to whole levels, weighted in 1/8 steps with fixed-point constants (Cr: 11485/1024 = 1.402 x 8 for
	 * red, -1463/256 for green; Cb: -1407/512 for green, 3625/256 = 1.77 x 8 for blue), and the sum rounded with its own
	 * offset per channel. A grey image rounds the luma alone. {@code y32}, {@code cb32}, {@code cr32}: samples in 1/32 steps.
	 */
	static int red(int y32,int cb32,int cr32){return range(Math.floorDiv(y32+22+4*Math.floorDiv(11485*chroma(cr32),1024),32));}
	static int green(int y32,int cb32,int cr32){return range(Math.floorDiv(y32+26+4*(Math.floorDiv(-1463*chroma(cr32),256)+Math.floorDiv(-1407*chroma(cb32),512)),32));}
	static int blue(int y32,int cb32,int cr32){return range(Math.floorDiv(y32+22+4*Math.floorDiv(3625*chroma(cb32),256),32));}
	static int grey(int y32){return range(Math.floorDiv(y32+18,32));}
	/** A chroma sample rounded to a whole level, less 128. */
	private static int chroma(int c32){return Math.floorDiv(c32+2,32)-128;}
	/** QuickTime's range limit is a table from -128 to 383; outside it reads other memory (out-of-gamut colours only) - clamped here. */
	private static int range(int v){return v<0?0:v>255?255:v;}
	private void toRgb(){
		rgb=new int[width*height];
		for(int y=0;y<height;y++)for(int x=0;x<width;x++){
			int v;
			if(components<3)v=grey(sample(0,x,y))*0x010101;
			else{int Y=sample(0,x,y),cb=sample(1,x,y),cr=sample(2,x,y);v=red(Y,cb,cr)<<16|green(Y,cb,cr)<<8|blue(Y,cb,cr);}
			rgb[y*width+x]=v;
		}
	}

	static int clamp(int v){return v<0?0:v>255?255:v;}
	private static int u2(byte[] b,int p){return (b[p]&0xff)<<8|(b[p+1]&0xff);}
	/** Zig-zag position k to natural (row-major) index. */
	static final int[] ZIGZAG={0,1,8,16,9,2,3,10,17,24,32,25,18,11,4,5,12,19,26,33,40,48,41,34,27,20,13,6,7,14,21,28,35,42,49,56,57,50,43,36,29,22,15,23,30,37,44,51,58,59,52,45,38,31,39,46,53,60,61,54,47,55,62,63};
}

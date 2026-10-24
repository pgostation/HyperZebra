import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

/**
 * MPEG-1, MPEG-2 and MPEG-2.5 audio Layer III ("MP3") decoding, written from ISO/IEC 11172-3 and 13818-3, so MP3 files play
 * without QuickTime, ffmpeg or a Java Sound provider (Java has none for MP3). The numeric tables of the standard are in
 * {@link Mp3Tables}; everything else is computed here.
 * <p>
 * {@link #scan} finds the frames of a file (an ID3v2 tag skipped, a Xing/Info/VBRI header frame counted out, resyncing over
 * junk); a decoder instance turns frames into interleaved 16-bit samples, keeping the bit reservoir, the IMDCT overlap and
 * the synthesis filter state between frames. {@link Stream#pcm} decodes from any frame on (a few frames earlier first, so the
 * reservoir and filters are primed after a seek). No gapless trimming: the decoder delay stays in, as decoders of the time
 * played it.
 */
final class Mp3Decoder {

	// ------------------------------------------------------------------ frame headers

	private static final int[][] BITRATES={ // kbit/s by [MPEG-1 L1, L2, L3, MPEG-2/2.5 L1, L2/L3][index]
		{0,32,64,96,128,160,192,224,256,288,320,352,384,416,448},
		{0,32,48,56,64,80,96,112,128,160,192,224,256,320,384},
		{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320},
		{0,32,48,56,64,80,96,112,128,144,160,176,192,224,256},
		{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160}};
	private static final int[][] SAMPLE_RATES={{11025,12000,8000},{0,0,0},{22050,24000,16000},{44100,48000,32000}}; // by version bits

	/** A frame header. version: 3 MPEG-1, 2 MPEG-2, 0 MPEG-2.5; layer 1-3. */
	static final class Header {
		final int version,layer,bitrate,sampleRate,mode,modeExtension,length;final boolean crc;
		private Header(int version,int layer,boolean crc,int bitrate,int sampleRate,int mode,int modeExtension,int length){
			this.version=version;this.layer=layer;this.crc=crc;this.bitrate=bitrate;this.sampleRate=sampleRate;this.mode=mode;this.modeExtension=modeExtension;this.length=length;}
		boolean mpeg1(){return version==3;}
		int channels(){return mode==3?1:2;}
		/** Samples per channel in a frame. */
		int samples(){return layer==1?384:layer==2||mpeg1()?1152:576;}
		int sideInfoSize(){return mpeg1()?(mode==3?17:32):(mode==3?9:17);}
		/** Whether another header belongs to the same stream (frames may switch stereo modes and bitrates, nothing else). */
		boolean sameStream(Header h){return h!=null&&h.version==version&&h.layer==layer&&h.sampleRate==sampleRate&&h.channels()==channels();}
		/** The header of the four bytes at {@code off}, or null (free format and reserved values are not taken). */
		static Header at(byte[] b,int off){
			if(off<0||off+4>b.length)return null;
			int h=(b[off]&0xff)<<24|(b[off+1]&0xff)<<16|(b[off+2]&0xff)<<8|b[off+3]&0xff;
			if((h>>>21)!=0x7ff)return null;
			int version=(h>>>19)&3,layer=4-((h>>>17)&3),bri=(h>>>12)&15,sri=(h>>>10)&3;
			if(version==1||layer==4||bri==0||bri==15||sri==3||(h&3)==2)return null; // reserved emphasis too
			int kbps=BITRATES[version==3?layer-1:layer==1?3:4][bri],rate=SAMPLE_RATES[version][sri],pad=(h>>>9)&1;
			int length=layer==1?(12000*kbps/rate+pad)*4:layer==3&&version!=3?72000*kbps/rate+pad:144000*kbps/rate+pad;
			return new Header(version,layer,(h&0x10000)==0,kbps,rate,(h>>>6)&3,(h>>>4)&3,length);
		}
	}

	// ------------------------------------------------------------------ streams

	/** The frames of an MPEG audio file: their offsets, and what they hold. */
	static final class Stream {
		final byte[] data;final int[] offsets;final Header first;
		private Stream(byte[] data,int[] offsets,Header first){this.data=data;this.offsets=offsets;this.first=first;}
		int frames(){return offsets.length;}
		int sampleRate(){return first.sampleRate;}
		int channels(){return first.channels();}
		int samplesPerFrame(){return first.samples();}
		long totalSamples(){return (long)frames()*samplesPerFrame();}
		long millis(){return totalSamples()*1000/sampleRate();}
		AudioFormat format(){return new AudioFormat(sampleRate(),16,channels(),true,true);}
		/** Big-endian 16-bit samples from sample frame {@code startSample} (per channel) on, decoded as they are read. */
		AudioInputStream pcm(long startSample){
			long total=totalSamples(),start=Math.max(0,Math.min(total,startSample));
			return new AudioInputStream(new PcmInput(this,start),format(),total-start);
		}
		/** The whole stream decoded (interleaved). */
		short[] decodeAll(){
			short[] all=new short[(int)Math.min(Integer.MAX_VALUE-16,totalSamples()*channels())];
			Mp3Decoder d=new Mp3Decoder();short[] frame=new short[1152*2];int at=0;
			for(int off:offsets){int n=d.decodeFrame(data,off,frame)*channels();n=Math.min(n,all.length-at);System.arraycopy(frame,0,all,at,n);at+=n;}
			return all;
		}
	}

	/** Whether the file starts like MPEG audio Layer III: an ID3v2 tag, or two frames in a row at its start. */
	static boolean looksLikeMp3(File file){
		byte[] head=new byte[8192];int n;
		try(InputStream in=new java.io.FileInputStream(file)){n=in.readNBytes(head,0,head.length);}catch(IOException e){return false;}
		if(n>=10&&head[0]=='I'&&head[1]=='D'&&head[2]=='3'&&(head[3]&0xff)<5)return true;
		head=Arrays.copyOf(head,n);
		Header h=Header.at(head,0);
		return h!=null&&h.layer==3&&h.sameStream(Header.at(head,h.length));
	}
	static Stream scan(File file) throws IOException {return scan(java.nio.file.Files.readAllBytes(file.toPath()));}
	/** The Layer III frames of {@code data}; IOException when there are none. */
	static Stream scan(byte[] data) throws IOException {
		int pos=0;
		if(data.length>=10&&data[0]=='I'&&data[1]=='D'&&data[2]=='3'){ // ID3v2: a syncsafe size after the 10-byte header (and a footer)
			int size=(data[6]&0x7f)<<21|(data[7]&0x7f)<<14|(data[8]&0x7f)<<7|data[9]&0x7f;pos=10+size+((data[5]&0x10)!=0?10:0);
		}
		int end=data.length;
		if(end>=128&&data[end-128]=='T'&&data[end-127]=='A'&&data[end-126]=='G')end-=128; // ID3v1
		Header first=null;int[] offsets=new int[1024];int count=0;
		while(pos+4<=end){
			Header h=Header.at(data,pos);
			boolean ok=h!=null&&h.layer==3&&pos+h.length<=end&&(first==null?h.sameStream(Header.at(data,pos+h.length))||pos+h.length==end:h.sameStream(first));
			if(!ok){pos++;continue;} // resync
			if(first==null){first=h;if(isInfoFrame(data,pos,h)){pos+=h.length;continue;}}
			if(count==offsets.length)offsets=Arrays.copyOf(offsets,count*2);
			offsets[count++]=pos;pos+=h.length;
		}
		if(first==null||count==0)throw new IOException("no MPEG audio Layer III frames");
		return new Stream(data,Arrays.copyOf(offsets,count),first);
	}
	/** A Xing/Info (LAME) or VBRI (Fraunhofer) header frame: metadata in a silent frame, not part of the sound. */
	private static boolean isInfoFrame(byte[] b,int off,Header h){
		int x=off+4+h.sideInfoSize(); // LAME places it there even in a frame with a CRC
		return tag(b,x,"Xing")||tag(b,x,"Info")||tag(b,x+2,"Xing")||tag(b,x+2,"Info")||tag(b,off+36,"VBRI");
	}
	private static boolean tag(byte[] b,int at,String s){if(at<0||at+4>b.length)return false;for(int i=0;i<4;i++)if(b[at+i]!=s.charAt(i))return false;return true;}

	/** Decoding on demand for {@link Stream#pcm}: starts a few frames early to prime the reservoir and the filters. */
	private static final class PcmInput extends InputStream {
		private final Stream s;private final Mp3Decoder d=new Mp3Decoder();private final short[] frame=new short[1152*2];
		private int next,avail,at;private long skip; // next frame; samples decoded and not yet read (interleaved) and read position; bytes to drop
		private boolean low;private int pending; // half of a sample read
		PcmInput(Stream s,long start){
			this.s=s;int f=(int)(start/s.samplesPerFrame()),primed=Math.max(0,f-PRIME_FRAMES);
			next=primed;skip=((long)(f-primed)*s.samplesPerFrame()+start%s.samplesPerFrame())*s.channels();
		}
		private boolean fill(){
			while(at>=avail){
				if(next>=s.frames())return false;
				avail=d.decodeFrame(s.data,s.offsets[next++],frame)*s.channels();at=0;
				if(skip>0){int drop=(int)Math.min(skip,avail);at=drop;skip-=drop;}
			}
			return true;
		}
		@Override public int read(){
			if(low){low=false;return pending&0xff;}
			if(!fill())return -1;
			short v=frame[at++];pending=v;low=true;return (v>>8)&0xff;
		}
		@Override public int read(byte[] b,int off,int len){
			if(len==0)return 0;int n=0;
			if(low){b[off++]=(byte)pending;low=false;n++;len--;}
			while(len>=2&&fill()){int k=Math.min(len/2,avail-at);for(int i=0;i<k;i++){short v=frame[at++];b[off++]=(byte)(v>>8);b[off++]=(byte)v;}n+=2*k;len-=2*k;}
			if(len==1&&fill()){short v=frame[at++];b[off]=(byte)(v>>8);pending=v;low=true;n++;}
			return n==0?-1:n;
		}
	}
	/** Frames decoded ahead of a seek target: main_data_begin reaches back at most 511 bytes (six 96-byte frames of 32 kbit/s
	 *  at 48 kHz), then a frame refills the IMDCT overlap and the synthesis filter; after that the output is the same. */
	static final int PRIME_FRAMES=8;

	// ------------------------------------------------------------------ decoding

	// scalefactor bands (ISO 11172-3 Table B.8, 13818-3 Table B.2): long band starts, short band starts (x3 for the line index)
	private static final int[][] SFB_LONG={
		{0,4,8,12,16,20,24,30,36,44,52,62,74,90,110,134,162,196,238,288,342,418,576},   // 44100
		{0,4,8,12,16,20,24,30,36,42,50,60,72,88,106,128,156,190,230,276,330,384,576},   // 48000
		{0,4,8,12,16,20,24,30,36,44,54,66,82,102,126,156,194,240,296,364,448,550,576},  // 32000
		{0,6,12,18,24,30,36,44,54,66,80,96,116,140,168,200,238,284,336,396,464,522,576}, // 22050
		{0,6,12,18,24,30,36,44,54,66,80,96,114,136,162,194,232,278,332,394,464,540,576}, // 24000
		{0,6,12,18,24,30,36,44,54,66,80,96,116,140,168,200,238,284,336,396,464,522,576}, // 16000
		{0,6,12,18,24,30,36,44,54,66,80,96,116,140,168,200,238,284,336,396,464,522,576}, // 11025
		{0,6,12,18,24,30,36,44,54,66,80,96,116,140,168,200,238,284,336,396,464,522,576}, // 12000
		{0,12,24,36,48,60,72,88,108,132,160,192,232,280,336,400,476,566,568,570,572,574,576}}; // 8000
	private static final int[][] SFB_SHORT={
		{0,4,8,12,16,22,30,40,52,66,84,106,136,192},
		{0,4,8,12,16,22,28,38,50,64,80,100,126,192},
		{0,4,8,12,16,22,30,42,58,78,104,138,180,192},
		{0,4,8,12,18,24,32,42,56,74,100,132,174,192},
		{0,4,8,12,18,26,36,48,62,80,104,136,180,192},
		{0,4,8,12,18,26,36,48,62,80,104,134,174,192},
		{0,4,8,12,18,26,36,48,62,80,104,134,174,192},
		{0,4,8,12,18,26,36,48,62,80,104,134,174,192},
		{0,8,16,24,36,52,72,96,124,160,162,164,166,192}};
	private static final int[] PRETAB={0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,2,2,3,3,3,2,0};
	private static final int[] SLEN1={0,0,0,0,3,1,1,1,2,2,2,3,3,3,4,4},SLEN2={0,1,2,3,0,1,2,3,1,2,3,1,2,3,2,3};
	/** MPEG-2 scalefactor counts per partition: [scalefac_compress range][long, short, mixed][partition]. */
	private static final int[][][] LSF_PARTITIONS={
		{{6,5,5,5},{9,9,9,9},{6,9,9,9}},{{6,5,7,3},{9,9,12,6},{6,9,12,6}},{{11,10,0,0},{18,18,0,0},{15,18,0,0}},
		{{7,7,7,0},{12,12,12,0},{6,15,12,0}},{{6,6,6,3},{12,9,9,6},{6,12,9,6}},{{8,8,5,0},{15,12,9,0},{6,18,9,0}}};
	private static final int[] LINBITS={0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,2,3,4,6,8,10,13,4,5,6,7,8,9,11,13};

	/** Huffman decoding trees: node pairs, a leaf is ~value. */
	private static final int[][] TREES=new int[34][];
	private static final double[] POW43=new double[8207];
	private static final double[] CS=new double[8],CA=new double[8];
	private static final double[][] IMDCT_LONG=new double[36][18],IMDCT_SHORT=new double[12][6];
	private static final double[][] WINDOWS=new double[4][36];
	private static final double[][] SYNTH_N=new double[64][32];
	private static final double[] SYNTH_D=new double[512];
	static {
		for(int t=0;t<34;t++){int[] codes=Mp3Tables.HUFFMAN[t];if(codes!=null)TREES[t]=tree(codes);}
		for(int t=17;t<24;t++)TREES[t]=TREES[16];
		for(int t=25;t<32;t++)TREES[t]=TREES[24];
		for(int i=0;i<POW43.length;i++)POW43[i]=Math.pow(i,4.0/3);
		double[] ci={-0.6,-0.535,-0.33,-0.185,-0.095,-0.041,-0.0142,-0.0037};
		for(int i=0;i<8;i++){double sq=Math.sqrt(1+ci[i]*ci[i]);CS[i]=1/sq;CA[i]=ci[i]/sq;}
		for(int i=0;i<36;i++)for(int k=0;k<18;k++)IMDCT_LONG[i][k]=Math.cos(Math.PI/72*(2*i+1+18)*(2*k+1));
		for(int i=0;i<12;i++)for(int k=0;k<6;k++)IMDCT_SHORT[i][k]=Math.cos(Math.PI/24*(2*i+1+6)*(2*k+1));
		for(int i=0;i<36;i++)WINDOWS[0][i]=Math.sin(Math.PI/36*(i+0.5));
		for(int i=0;i<18;i++)WINDOWS[1][i]=WINDOWS[0][i];
		for(int i=18;i<24;i++)WINDOWS[1][i]=1;
		for(int i=24;i<30;i++)WINDOWS[1][i]=Math.sin(Math.PI/12*(i-18+0.5));
		for(int i=6;i<12;i++)WINDOWS[3][i]=Math.sin(Math.PI/12*(i-6+0.5));
		for(int i=12;i<18;i++)WINDOWS[3][i]=1;
		for(int i=18;i<36;i++)WINDOWS[3][i]=WINDOWS[0][i];
		for(int i=0;i<12;i++)WINDOWS[2][i]=Math.sin(Math.PI/12*(i+0.5)); // the 12-point window of a short block
		for(int i=0;i<64;i++)for(int k=0;k<32;k++)SYNTH_N[i][k]=Math.cos((16+i)*(2*k+1)*Math.PI/64);
		for(int i=0;i<512;i++)SYNTH_D[i]=Mp3Tables.SYNTH_WINDOW[i]/65536.0;
	}
	private static int[] tree(int[] codes){
		int[] t=new int[2*codes.length];int nodes=1; // node 0 = the root; children at 2n, 2n+1; 0 = no child yet
		for(int c:codes){
			int len=(c>>>8)&31,bits=c>>>13,n=0;
			for(int i=len-1;i>0;i--){int b=(bits>>>i)&1;if(t[2*n+b]==0)t[2*n+b]=nodes++;n=t[2*n+b];}
			t[2*n+(bits&1)]=~(c&0xff);
		}
		return t;
	}

	// per-stream state
	private final byte[] reservoir=new byte[4096];private int reservoirLength;
	private final double[][][] overlap=new double[2][32][18];
	private final double[][] synthV=new double[2][1024];private final int[] synthOffset=new int[2];
	// per-granule work
	private final int[][] is=new int[2][576];
	private final double[][] xr=new double[2][576];
	private final int[][] sfLong=new int[2][22];private final int[][][] sfShort=new int[2][13][3];
	private final int[][] isIllegalLong=new int[2][22];private final int[][] isIllegalShort=new int[2][13]; // LSF: the value marking "no intensity position"
	private final int[] nonZero=new int[2]; // lines up to the last non-zero quantized value
	private final double[] tmp=new double[576];
	// bit reading of the main data
	private byte[] bits;private int bitPos,bitEnd;

	/** Side information of one granule of one channel. */
	private static final class Granule {
		int part23,bigValues,globalGain,scalefacCompress,blockType,region0,region1,preflag,scalefacScale,count1Table;
		boolean windowSwitching,mixed;final int[] tableSelect=new int[3],subblockGain=new int[3];
	}
	private final Granule[][] granules={{new Granule(),new Granule()},{new Granule(),new Granule()}};
	private final int[][] scfsi=new int[2][4];

	/** Forgets the state between frames (after a seek). */
	void reset(){reservoirLength=0;for(double[][] o:overlap)for(double[] a:o)Arrays.fill(a,0);for(double[] v:synthV)Arrays.fill(v,0);}

	/**
	 * Decodes the frame at {@code off} into {@code out} (interleaved, as many channels as the frame has); returns the samples
	 * per channel (silence while the reservoir the frame needs is missing).
	 */
	int decodeFrame(byte[] data,int off,short[] out){
		Header h=Header.at(data,off);
		if(h==null||h.layer!=3)return 0;
		int nch=h.channels(),ngr=h.mpeg1()?2:1;
		int pos=off+4+(h.crc?2:0),end=Math.min(data.length,off+h.length);
		// side information
		bits=data;bitPos=pos*8;bitEnd=end*8;
		int mainDataBegin=read(h.mpeg1()?9:8);
		read(h.mpeg1()?(nch==1?5:3):(nch==1?1:2)); // private bits
		if(h.mpeg1())for(int ch=0;ch<nch;ch++)for(int b=0;b<4;b++)scfsi[ch][b]=read(1);
		for(int gr=0;gr<ngr;gr++)for(int ch=0;ch<nch;ch++){
			Granule g=granules[gr][ch];
			g.part23=read(12);g.bigValues=Math.min(288,read(9));g.globalGain=read(8);g.scalefacCompress=read(h.mpeg1()?4:9);
			g.windowSwitching=read(1)!=0;
			if(g.windowSwitching){
				g.blockType=read(2);g.mixed=read(1)!=0;
				for(int i=0;i<2;i++)g.tableSelect[i]=read(5);g.tableSelect[2]=0;
				for(int i=0;i<3;i++)g.subblockGain[i]=read(3);
				g.region0=g.blockType==2&&!g.mixed?8:7;g.region1=20-g.region0;
			}else{
				g.blockType=0;g.mixed=false;
				for(int i=0;i<3;i++)g.tableSelect[i]=read(5);
				g.region0=read(4);g.region1=read(3);
			}
			g.preflag=h.mpeg1()?read(1):0;g.scalefacScale=read(1);g.count1Table=read(1);
		}
		// main data: the reservoir's last mainDataBegin bytes, then this frame's
		int mainStart=pos+h.sideInfoSize(),mainLength=Math.max(0,end-mainStart);
		int samples=h.samples();
		if(mainDataBegin>reservoirLength){ // the frames it reaches back into are missing (stream start, after a seek)
			keep(data,mainStart,mainLength);Arrays.fill(out,0,samples*nch,(short)0);
			return samples;
		}
		byte[] main=new byte[mainDataBegin+mainLength+4];
		System.arraycopy(reservoir,reservoirLength-mainDataBegin,main,0,mainDataBegin);System.arraycopy(data,mainStart,main,mainDataBegin,mainLength);
		keep(data,mainStart,mainLength);
		bits=main;bitPos=0;bitEnd=(mainDataBegin+mainLength)*8;
		int sfIndex=sampleRateIndex(h);
		for(int gr=0;gr<ngr;gr++){
			for(int ch=0;ch<nch;ch++){
				Granule g=granules[gr][ch];int start=bitPos;
				if(h.mpeg1())scalefactorsMpeg1(g,ch,gr);else scalefactorsLsf(g,ch,h.modeExtension,h.mode);
				huffman(g,ch,start+g.part23,sfIndex);
				bitPos=start+g.part23;
				requantize(g,ch,sfIndex);
			}
			if(h.mode==1&&nch==2)stereo(h,granules[gr],sfIndex);
			for(int ch=0;ch<nch;ch++){
				Granule g=granules[gr][ch];
				reorder(g,ch,sfIndex);antialias(g,ch);hybrid(g,ch);
				synthesize(ch,out,gr*576*nch+ch,nch);
			}
		}
		return samples;
	}
	private void keep(byte[] data,int at,int length){
		if(length>=reservoir.length){System.arraycopy(data,at+length-reservoir.length,reservoir,0,reservoir.length);reservoirLength=reservoir.length;return;}
		if(reservoirLength+length>reservoir.length){int drop=reservoirLength+length-reservoir.length;System.arraycopy(reservoir,drop,reservoir,0,reservoirLength-drop);reservoirLength-=drop;}
		System.arraycopy(data,at,reservoir,reservoirLength,length);reservoirLength+=length;
	}
	private static int sampleRateIndex(Header h){
		int i=h.sampleRate==44100||h.sampleRate==22050||h.sampleRate==11025?0:h.sampleRate==48000||h.sampleRate==24000||h.sampleRate==12000?1:2;
		return (h.version==3?0:h.version==2?3:6)+i;
	}
	private int read(int n){
		int v=0;
		for(int i=0;i<n;i++){v<<=1;if(bitPos<bitEnd)v|=(bits[bitPos>>>3]>>>(7-(bitPos&7)))&1;bitPos++;}
		return v;
	}
	private int bit(){int v=bitPos<bitEnd?(bits[bitPos>>>3]>>>(7-(bitPos&7)))&1:0;bitPos++;return v;}

	// ---- scalefactors

	private void scalefactorsMpeg1(Granule g,int ch,int gr){
		int s1=SLEN1[g.scalefacCompress],s2=SLEN2[g.scalefacCompress];
		if(g.windowSwitching&&g.blockType==2){
			int sfb=0;
			if(g.mixed){for(;sfb<8;sfb++)sfLong[ch][sfb]=read(s1);sfb=3;}
			for(;sfb<12;sfb++){int n=sfb<6?s1:s2;for(int w=0;w<3;w++)sfShort[ch][sfb][w]=read(n);}
			for(int w=0;w<3;w++)sfShort[ch][12][w]=0;
		}else{
			int[] bounds={0,6,11,16,21};
			for(int b=0;b<4;b++){
				if(gr==1&&scfsi[ch][b]!=0)continue; // the first granule's values stay
				for(int sfb=bounds[b];sfb<bounds[b+1];sfb++)sfLong[ch][sfb]=read(b<2?s1:s2);
			}
			sfLong[ch][21]=0;
		}
	}
	private void scalefactorsLsf(Granule g,int ch,int modeExt,int mode){
		int sfc=g.scalefacCompress;int[] slen=new int[4];int row;
		boolean intensityRight=ch==1&&mode==1&&(modeExt&1)!=0;
		if(!intensityRight){
			if(sfc<400){slen[0]=(sfc>>4)/5;slen[1]=(sfc>>4)%5;slen[2]=(sfc&15)>>2;slen[3]=sfc&3;row=0;}
			else if(sfc<500){sfc-=400;slen[0]=(sfc>>2)/5;slen[1]=(sfc>>2)%5;slen[2]=sfc&3;row=1;}
			else{sfc-=500;slen[0]=sfc/3;slen[1]=sfc%3;row=2;g.preflag=1;}
		}else{
			int isc=sfc>>1;
			if(isc<180){slen[0]=isc/36;slen[1]=(isc%36)/6;slen[2]=isc%6;row=3;}
			else if(isc<244){isc-=180;slen[0]=(isc%64)>>4;slen[1]=(isc%16)>>2;slen[2]=isc%4;row=4;}
			else{isc-=244;slen[0]=isc/3;slen[1]=isc%3;row=5;}
		}
		int kind=g.windowSwitching&&g.blockType==2?(g.mixed?2:1):0;
		int[] counts=LSF_PARTITIONS[row][kind];
		Arrays.fill(sfLong[ch],0);for(int[] s:sfShort[ch])Arrays.fill(s,0);
		int p=0;
		for(int part=0;part<4;part++)for(int i=0;i<counts[part];i++,p++){
			int v=read(slen[part]),illegal=(1<<slen[part])-1;
			if(kind==0){if(p<22){sfLong[ch][p]=v;isIllegalLong[ch][p]=illegal;}}
			else if(kind==2&&p<6){sfLong[ch][p]=v;isIllegalLong[ch][p]=illegal;}
			else{int q=kind==2?p-6:p,sfb=(kind==2?3:0)+q/3;if(sfb<13){sfShort[ch][sfb][q%3]=v;isIllegalShort[ch][sfb]=illegal;}}
		}
		// the last band takes its intensity position from the band before it
		isIllegalLong[ch][21]=isIllegalLong[ch][20];sfLong[ch][21]=sfLong[ch][20];
		for(int w=0;w<3;w++)sfShort[ch][12][w]=sfShort[ch][11][w];isIllegalShort[ch][12]=isIllegalShort[ch][11];
	}

	// ---- Huffman decoding

	private void huffman(Granule g,int ch,int end,int sf){
		int[] q=is[ch];Arrays.fill(q,0);
		int r1,r2;
		if(g.windowSwitching){
			r1=g.blockType==2&&!g.mixed?SFB_SHORT[sf][3]*3:SFB_LONG[sf][8];r2=576;
		}else{
			r1=SFB_LONG[sf][Math.min(22,g.region0+1)];r2=SFB_LONG[sf][Math.min(22,g.region0+g.region1+2)];
		}
		int big=g.bigValues*2;r1=Math.min(r1,big);r2=Math.min(r2,big);
		int i=0;
		for(;i<big;i+=2){
			int table=g.tableSelect[i<r1?0:i<r2?1:2];
			int[] t=TREES[table];
			if(t==null){q[i]=q[i+1]=0;continue;}
			int v=decode(t),x=v>>>4,y=v&15,lin=LINBITS[table];
			if(lin>0&&x==15)x+=read(lin);
			if(x!=0&&bit()!=0)x=-x;
			if(lin>0&&y==15)y+=read(lin);
			if(y!=0&&bit()!=0)y=-y;
			q[i]=x;q[i+1]=y;
		}
		int[] t=TREES[32+g.count1Table];
		while(i+4<=576&&bitPos<end){
			int v=decode(t);int[] w={(v>>3)&1,(v>>2)&1,(v>>1)&1,v&1};
			for(int k=0;k<4;k++)if(w[k]!=0&&bit()!=0)w[k]=-w[k];
			if(bitPos>end)break; // the last quadruple ran past the granule: not part of it
			q[i]=w[0];q[i+1]=w[1];q[i+2]=w[2];q[i+3]=w[3];i+=4;
		}
		nonZero[ch]=i;
	}
	private int decode(int[] t){
		int n=0;
		for(int depth=0;depth<24;depth++){int c=t[2*n+bit()];if(c<0)return ~c;if(c==0)return 0;n=c;}
		return 0;
	}

	// ---- requantization

	private void requantize(Granule g,int ch,int sf){
		int[] q=is[ch];double[] x=xr[ch];Arrays.fill(x,0);
		double gain=Math.pow(2,0.25*(g.globalGain-210)),mult=g.scalefacScale!=0?1:0.5;
		int n=nonZero[ch];
		if(g.windowSwitching&&g.blockType==2){
			int line=0;
			if(g.mixed){ // the long bands of the first two subbands
				for(int sfb=0;SFB_LONG[sf][sfb]<36&&line<n;sfb++){double f=gain*Math.pow(2,-mult*(sfLong[ch][sfb]+g.preflag*PRETAB[sfb]));for(int e=SFB_LONG[sf][sfb+1];line<e&&line<n;line++)x[line]=value(q[line])*f;}
				line=Math.max(line,36);
			}
			for(int sfb=g.mixed?3:0;sfb<13&&line<n;sfb++){
				int width=SFB_SHORT[sf][sfb+1]-SFB_SHORT[sf][sfb];
				for(int w=0;w<3;w++){
					double f=gain*Math.pow(2,-2.0*g.subblockGain[w])*Math.pow(2,-mult*sfShort[ch][sfb][w]);
					for(int k=0;k<width&&line<576;k++,line++)if(line<n)x[line]=value(q[line])*f;
				}
			}
		}else{
			int line=0;
			for(int sfb=0;sfb<22&&line<n;sfb++){
				double f=gain*Math.pow(2,-mult*(sfLong[ch][sfb]+g.preflag*PRETAB[sfb]));
				for(int e=SFB_LONG[sf][sfb+1];line<e&&line<n;line++)x[line]=value(q[line])*f;
			}
		}
	}
	private static double value(int v){return v<0?-POW43[Math.min(-v,8206)]:POW43[Math.min(v,8206)];}

	// ---- stereo

	private void stereo(Header h,Granule[] gr,int sf){
		boolean ms=(h.modeExtension&2)!=0,intensity=(h.modeExtension&1)!=0;
		double[] l=xr[0],r=xr[1];
		boolean[] isLine=new boolean[576];double[] kl=new double[576],kr=new double[576];
		if(intensity){
			Granule g=gr[1];
			if(g.windowSwitching&&g.blockType==2){
				int firstShort=g.mixed?3:0;
				for(int w=0;w<3;w++){
					int last=-1; // the last short band of this window with a non-zero right value
					for(int sfb=firstShort;sfb<13;sfb++){int width=SFB_SHORT[sf][sfb+1]-SFB_SHORT[sf][sfb],start=3*SFB_SHORT[sf][sfb]+w*width;for(int k=0;k<width;k++)if(r[start+k]!=0)last=sfb;}
					for(int sfb=Math.max(last+1,firstShort);sfb<13;sfb++){
						int width=SFB_SHORT[sf][sfb+1]-SFB_SHORT[sf][sfb],start=3*SFB_SHORT[sf][sfb]+w*width;
						int pos=sfShort[1][Math.min(sfb,12)][w];if(sfb==12&&h.mpeg1())pos=sfShort[1][11][w];
						double[] k=intensityFactors(h,pos,h.mpeg1()?7:isIllegalShort[1][sfb],g.scalefacCompress);
						if(k==null)continue;
						for(int i=0;i<width;i++){isLine[start+i]=true;kl[start+i]=k[0];kr[start+i]=k[1];}
					}
				}
			}else{
				int last=-1;for(int i=575;i>=0;i--)if(r[i]!=0){last=i;break;}
				int sfb=0;while(sfb<22&&SFB_LONG[sf][sfb+1]<=last)sfb++;
				if(last>=0)sfb++;
				for(;sfb<22;sfb++){
					int pos=sfb==21&&h.mpeg1()?sfLong[1][20]:sfLong[1][sfb];
					double[] k=intensityFactors(h,pos,h.mpeg1()?7:isIllegalLong[1][sfb],g.scalefacCompress);
					if(k==null)continue;
					for(int i=SFB_LONG[sf][sfb];i<SFB_LONG[sf][sfb+1];i++){isLine[i]=true;kl[i]=k[0];kr[i]=k[1];}
				}
			}
		}
		for(int i=0;i<576;i++){
			if(isLine[i]){double v=l[i];l[i]=v*kl[i];r[i]=v*kr[i];}
			else if(ms){double m=l[i],s=r[i];l[i]=(m+s)*SQRT_HALF;r[i]=(m-s)*SQRT_HALF;}
		}
		int n=Math.max(nonZero[0],nonZero[1]);
		for(int ch=0;ch<2;ch++)nonZero[ch]=intensity?576:n;
	}
	private static final double SQRT_HALF=Math.sqrt(0.5);
	/** {left, right} factors of an intensity position, null for the illegal one (the band is decoded as plain or M/S stereo). */
	private static double[] intensityFactors(Header h,int pos,int illegal,int scalefacCompress){
		if(pos==illegal)return null;
		if(h.mpeg1()){
			if(pos==6)return new double[]{1,0};
			double t=Math.tan(pos*Math.PI/12);return new double[]{t/(1+t),1/(1+t)};
		}
		double io=(scalefacCompress&1)!=0?SQRT_HALF:Math.pow(2,-0.25);
		if(pos==0)return new double[]{1,1};
		return (pos&1)!=0?new double[]{Math.pow(io,(pos+1)/2),1}:new double[]{1,Math.pow(io,pos/2)};
	}

	// ---- reordering, alias reduction, IMDCT, synthesis

	private void reorder(Granule g,int ch,int sf){
		if(!(g.windowSwitching&&g.blockType==2))return;
		double[] x=xr[ch];
		for(int sfb=g.mixed?3:0;sfb<13;sfb++){
			int width=SFB_SHORT[sf][sfb+1]-SFB_SHORT[sf][sfb],start=3*SFB_SHORT[sf][sfb];
			for(int w=0;w<3;w++)for(int k=0;k<width;k++)tmp[start+3*k+w]=x[start+w*width+k];
			System.arraycopy(tmp,start,x,start,3*width);
		}
	}
	private void antialias(Granule g,int ch){
		if(g.windowSwitching&&g.blockType==2&&!g.mixed)return;
		int limit=g.windowSwitching&&g.blockType==2?1:31;double[] x=xr[ch];
		for(int sb=1;sb<=limit;sb++)for(int i=0;i<8;i++){
			int lo=18*sb-1-i,hi=18*sb+i;double a=x[lo],b=x[hi];
			x[lo]=a*CS[i]-b*CA[i];x[hi]=b*CS[i]+a*CA[i];
		}
	}
	private final double[] raw=new double[36];
	private void hybrid(Granule g,int ch){
		double[] x=xr[ch];
		for(int sb=0;sb<32;sb++){
			int type=g.windowSwitching?(g.mixed&&sb<2?0:g.blockType):0;
			Arrays.fill(raw,0);
			if(type==2){
				for(int w=0;w<3;w++)for(int i=0;i<12;i++){
					double s=0;for(int k=0;k<6;k++)s+=x[18*sb+3*k+w]*IMDCT_SHORT[i][k];
					raw[6+6*w+i]+=s*WINDOWS[2][i];
				}
			}else{
				double[] win=WINDOWS[type];
				for(int i=0;i<36;i++){double s=0;for(int k=0;k<18;k++)s+=x[18*sb+k]*IMDCT_LONG[i][k];raw[i]=s*win[i];}
			}
			double[] o=overlap[ch][sb];
			for(int i=0;i<18;i++){double v=raw[i]+o[i];o[i]=raw[18+i];x[18*sb+i]=(sb&1)!=0&&(i&1)!=0?-v:v;} // odd subbands: frequency inversion
		}
	}
	private final double[] subband=new double[32];
	/** The polyphase synthesis filterbank: 18 slots of 32 subband samples become 576 samples, written every {@code step}. */
	private void synthesize(int ch,short[] out,int at,int step){
		double[] x=xr[ch],v=synthV[ch];
		for(int t=0;t<18;t++){
			for(int sb=0;sb<32;sb++)subband[sb]=x[18*sb+t];
			int off=synthOffset[ch]=(synthOffset[ch]-64)&1023;
			for(int i=0;i<64;i++){double s=0;double[] n=SYNTH_N[i];for(int k=0;k<32;k++)s+=n[k]*subband[k];v[(off+i)&1023]=s;}
			for(int j=0;j<32;j++){
				double s=0;
				for(int m=0;m<8;m++){s+=SYNTH_D[j+64*m]*v[(off+128*m+j)&1023]+SYNTH_D[j+64*m+32]*v[(off+128*m+96+j)&1023];}
				int pcm=(int)Math.round(s*32768);
				out[at+(t*32+j)*step]=(short)Math.max(-32768,Math.min(32767,pcm));
			}
		}
	}
}

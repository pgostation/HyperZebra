import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The sound of a QuickTime movie decoded without QuickTime: every enabled sound track of a {@link QuickTimeFile} laid
 * out along the movie's timeline (edits, empty edits as silence) and mixed, as 16-bit signed PCM.
 *
 * Formats (the sample description's data format): uncompressed 'raw ' / 'NONE' (8-bit offset binary, 16-bit and wider
 * two's complement - the Sound Manager's rule), 'twos' (signed big-endian, 8-bit signed), 'sowt' (little-endian),
 * 'in24' / 'in32' / 'fl32' / 'fl64'; 'ulaw' / 'alaw' through Java Sound's own G.711 converters; 'MAC3' / 'MAC6' through
 * {@link MaceDecoder}, fed with all consecutive chunks of one description at once so the decoder state runs on across
 * chunk boundaries as it does in the Sound Manager's decompressor. Byte counts come from the sound description
 * ({@link QuickTimeFile.SoundDescription}), never from stsz (see tools/qt/README.md).
 *
 * tools/qt/QtAudioOracle compares the result with the buffers the installed QuickTime hands to the Sound Manager.
 */
final class QuickTimeSound {
	private QuickTimeSound(){}

	/** Interleaved 16-bit samples at {@code rate} with {@code channels} channels. */
	static final class Pcm {
		final short[] samples;final float rate;final int channels;
		Pcm(short[] samples,float rate,int channels){this.samples=samples;this.rate=rate;this.channels=channels;}
		int frames(){return samples.length/channels;}
		long millis(){return rate<=0?0:(long)(frames()*1000.0/rate);}
		AudioFormat format(){return new AudioFormat(rate,16,channels,true,true);}
		/** Big-endian signed 16-bit bytes, as {@link #format()} describes them. */
		byte[] bytes(){byte[] b=new byte[samples.length*2];for(int i=0;i<samples.length;i++){b[i*2]=(byte)(samples[i]>>8);b[i*2+1]=(byte)samples[i];}return b;}
		AudioInputStream stream(){return new AudioInputStream(new ByteArrayInputStream(bytes()),format(),frames());}
	}

	/** Whether this decoder handles a description's format. */
	static boolean canDecode(QuickTimeFile.SampleDescription d){
		if(!(d instanceof QuickTimeFile.SoundDescription))return false;
		QuickTimeFile.SoundDescription s=(QuickTimeFile.SoundDescription)d;
		if(s.channels<1||s.sampleRate<=0)return false;
		if(mp3(s))return true;
		switch(QuickTimeFile.typeName(s.format)){
			case "raw ":case "NONE":case "twos":case "sowt":return s.sampleSize==8||s.sampleSize==16||s.sampleSize==24||s.sampleSize==32;
			case "in24":case "in32":case "fl32":case "fl64":case "ulaw":case "alaw":case "MAC3":case "MAC6":case "ima4":return true;
			default:return false;
		}
	}
	/** MPEG audio Layer III: '.mp3' (QuickTime 4), or 'ms'+0x0055 (the WAVE format tag, movies converted from AVI). */
	static boolean mp3(QuickTimeFile.SoundDescription s){return s.format==0x2e6d7033||s.format==0x6d730055;}
	/** Whether every description of a sound track can be decoded. */
	static boolean canDecode(QuickTimeFile.Track t){
		if(t.media==null||!t.media.sound()||t.media.descriptions.isEmpty())return false;
		for(QuickTimeFile.SampleDescription d:t.media.descriptions)if(!canDecode(d))return false;
		return true;
	}
	/** The enabled sound tracks of a movie. */
	static List<QuickTimeFile.Track> soundTracks(QuickTimeFile q){
		List<QuickTimeFile.Track> list=new ArrayList<QuickTimeFile.Track>();
		for(QuickTimeFile.Track t:q.tracks)if(t.enabled()&&t.media!=null&&t.media.sound()&&t.media.samples()>0)list.add(t);
		return list;
	}
	/** Whether all of the movie's sound can be decoded here (false when it has no sound). */
	static boolean canDecode(QuickTimeFile q){
		List<QuickTimeFile.Track> tracks=soundTracks(q);if(tracks.isEmpty())return false;
		for(QuickTimeFile.Track t:tracks)if(!canDecode(t))return false;
		return true;
	}

	/**
	 * The movie's sound from time 0 to the end of its last sound track: the decodable enabled sound tracks mixed at the
	 * first one's rate and the largest channel count. Null when there is nothing to decode.
	 */
	static Pcm movie(QuickTimeFile q) throws IOException {
		List<Pcm> parts=new ArrayList<Pcm>();
		for(QuickTimeFile.Track t:soundTracks(q))if(canDecode(t))parts.add(track(t));
		if(parts.isEmpty())return null;
		if(parts.size()==1)return parts.get(0);
		float rate=parts.get(0).rate;int channels=1;for(Pcm p:parts)channels=Math.max(channels,p.channels);
		int frames=0;List<Pcm> converted=new ArrayList<Pcm>();
		for(Pcm p:parts){Pcm c=convert(p,rate,channels);converted.add(c);frames=Math.max(frames,c.frames());}
		int[] sum=new int[frames*channels];
		for(Pcm c:converted)for(int i=0;i<c.samples.length;i++)sum[i]+=c.samples[i];
		short[] out=new short[sum.length];for(int i=0;i<sum.length;i++)out[i]=clip16(sum[i]);
		return new Pcm(out,rate,channels);
	}

	/**
	 * A piece of a sound track's timeline: one edit. {@code frames} output frames at the track's rate start at output
	 * frame {@code start}; a non-empty edit plays {@code mediaFrames} decoded media frames from {@code mediaStart} at
	 * {@code rate} x normal speed (QuickTime hands exactly those samples to the Sound Manager, at rate x the sample rate).
	 */
	static final class Segment {
		final long start,frames,mediaStart,mediaFrames;final double rate;
		Segment(long start,long frames,long mediaStart,long mediaFrames,double rate){this.start=start;this.frames=frames;this.mediaStart=mediaStart;this.mediaFrames=mediaFrames;this.rate=rate;}
		boolean empty(){return mediaStart<0;}
	}

	/**
	 * The edits of a sound track as QuickTime plays them: an edit boundary at movie time t falls on output frame
	 * round(t x mediaScale / movieScale) (media units = samples for sound; the track's end is rounded up), so every edit
	 * lasts the difference of its two boundaries; the last one ends with the media and, for compressed sound, on a whole
	 * packet (MACE, version 0: the last whole one; 'ima4', version 1: the one the end falls in). A
	 * scaled edit plays ceil(frames x rate) media samples.
	 */
	static List<Segment> segments(QuickTimeFile.Track t,Pcm media){
		QuickTimeFile.Media m=t.media;
		int movieScale=t.movie().timeScale>0?t.movie().timeScale:600;
		double perUnit=m.timeScale>0?m.timeScale/(double)movieScale/Math.max(1,m.samples()>0?m.sampleDuration(0):1):media.rate/movieScale;
		QuickTimeFile.SampleDescription d=m.description(m.chunkCount()>0?m.chunkDescription(0):1);
		int packet=d instanceof QuickTimeFile.SoundDescription?Math.max(1,((QuickTimeFile.SoundDescription)d).samplesPerPacket):1;
		// MP3: media samples may be whole frames, so media time (not the sample count) places the decoded sound
		boolean byTime=d instanceof QuickTimeFile.SoundDescription&&mp3((QuickTimeFile.SoundDescription)d)&&m.timeScale>0;
		if(byTime){perUnit=media.rate/movieScale;packet=1;}
		// the last packet: 'ima4' plays the packet the end falls in, MACE stops at the last whole packet (also with a version 1
		// description: the Animation-* movies of QTmovie)
		boolean roundUp=d!=null&&QuickTimeFile.typeName(d.format).equals("ima4");
		List<Segment> list=new ArrayList<Segment>();
		long movieTime=0,at=0;int mediaFrames=media.frames();
		for(int i=0;i<t.edits.size();i++){
			QuickTimeFile.Edit e=t.edits.get(i);
			double exactEnd=(movieTime+e.duration)*perUnit;
			long end=i==t.edits.size()-1?(long)Math.ceil(exactEnd-1e-9):Math.round(exactEnd),frames=Math.max(0,end-at);
			movieTime+=e.duration;
			if(e.mediaTime==-1||m.timeScale<=0){list.add(new Segment(at,frames,-1,0,1));at+=frames;continue;}
			double rate=e.rate==0?1.0:e.rate/65536.0;
			double from=byTime?e.mediaTime*(double)media.rate/m.timeScale:framesAt(m,e.mediaTime,media.rate);long first=Math.min(mediaFrames,Math.round(from));
			if(i==t.edits.size()-1){frames=roundUp?(frames+packet-1)/packet*packet:frames-frames%packet;frames=Math.min(frames,(long)Math.ceil((mediaFrames-first)/rate));}
			// an unscaled edit hands over its frames; a scaled one the media it covers, rounded (edits.mov: 65 units at half
			// speed = 602.7 samples -> 603; Minolta's stretched implicit edit 68798.2 -> 68798)
			long used=Math.min(mediaFrames-first,rate==1.0?frames:Math.round(from+e.duration*perUnit*rate)-first);
			list.add(new Segment(at,frames,first,Math.max(0,used),rate));at+=frames;
		}
		return list;
	}

	/** One sound track along the movie timeline ({@link #segments}), at its first description's rate and channel count. */
	static Pcm track(QuickTimeFile.Track t) throws IOException {
		Pcm media=media(t.media);
		int channels=media.channels;
		List<Segment> segments=segments(t,media);
		long total=0;for(Segment g:segments)total+=g.frames;
		if(total>Integer.MAX_VALUE/channels)throw new IOException("sound track too long");
		short[] out=new short[(int)total*channels];
		for(Segment g:segments){
			if(g.empty())continue;
			for(long f=0;f<g.frames;f++){
				long src=g.mediaStart+(long)Math.floor(f*g.rate);
				if(src>=g.mediaStart+g.mediaFrames||src>=media.frames())break;
				System.arraycopy(media.samples,(int)src*channels,out,(int)(g.start+f)*channels,channels);
			}
		}
		return new Pcm(out,media.rate,channels);
	}

	/** The decoded frame at media time {@code t} (the media's samples, not its time scale, count frames). */
	private static double framesAt(QuickTimeFile.Media m,long t,float rate){
		long sample=m.sampleAt(t);
		if(sample<0)return m.samples();
		long start=m.sampleTime(sample),duration=m.sampleDuration(sample);
		return sample+(duration>0?(double)(t-start)/duration:0);
	}

	/**
	 * All samples of a sound media in media order, decoded description run by description run (consecutive chunks with
	 * the same description are decoded as one stream), converted to the first description's rate and channels.
	 */
	static Pcm media(QuickTimeFile.Media m) throws IOException {
		QuickTimeFile.SoundDescription first=null;
		for(int c=0;c<m.chunkCount();c++){QuickTimeFile.SampleDescription d=m.description(m.chunkDescription(c));if(d instanceof QuickTimeFile.SoundDescription){first=(QuickTimeFile.SoundDescription)d;break;}}
		if(first==null)throw new IOException("no sound description");
		float rate=(float)first.sampleRate;int channels=first.channels;
		List<short[]> runs=new ArrayList<short[]>();int total=0;
		int samples=m.samples();
		for(int c=0;c<m.chunkCount();){
			int desc=m.chunkDescription(c);QuickTimeFile.SampleDescription d=m.description(desc);
			if(!canDecode(d))throw new IOException("sound format '"+(d==null?"?":QuickTimeFile.typeName(d.format))+"' in chunk "+(c+1));
			QuickTimeFile.SoundDescription sd=(QuickTimeFile.SoundDescription)d;
			if(mp3(sd)){ // the frames from each chunk's start, as many as its samples need (VBR: the sample table gives no sizes)
				java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();long count=0;
				for(;c<m.chunkCount()&&m.chunkDescription(c)==desc;c++){
					long first0=m.chunkFirstSample(c);int n=(int)Math.max(0,Math.min(m.chunkSamples(c),samples-first0));if(n==0)continue;
					// the chunk's sound in audio samples: from the media time it covers (a media sample may be one audio sample, as
					// QuickTime 4 stores it, or a whole frame of 1152, as other writers do)
					long last=first0+n-1,units=m.sampleTime(last)+m.sampleDuration(last)-m.sampleTime(first0);
					long need=m.timeScale>0?Math.round(units*sd.sampleRate/m.timeScale):n;
					long at=m.chunkOffsets[c];long got=0;
					while(got<need){
						Mp3Decoder.Header h=Mp3Decoder.Header.at(m.track.movie().read(m.dataFile(d),at,4),0);if(h==null||h.layer!=3)break;
						byte[] frame=m.track.movie().read(m.dataFile(d),at,h.length);bytes.write(frame,0,frame.length);at+=h.length;got+=h.samples();
					}
					count+=need;
				}
				Mp3Decoder.Stream stream=Mp3Decoder.scan(bytes.toByteArray());
				short[] pcm=java.util.Arrays.copyOf(stream.decodeAll(),(int)Math.min(Integer.MAX_VALUE-8,count*stream.channels()));
				if(stream.channels()!=channels||stream.sampleRate()!=rate)pcm=convert(new Pcm(pcm,stream.sampleRate(),stream.channels()),rate,channels).samples;
				runs.add(pcm);total+=pcm.length;continue;
			}
			java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();long count=0;
			for(;c<m.chunkCount()&&m.chunkDescription(c)==desc;c++){
				long first0=m.chunkFirstSample(c);int n=(int)Math.max(0,Math.min(m.chunkSamples(c),samples-first0));if(n==0)continue;
				byte[] chunk=m.track.movie().read(m.dataFile(d),m.chunkOffsets[c],(int)m.chunkBytes(c,n));
				bytes.write(chunk,0,chunk.length);count+=n;
				if(sd.samplesPerPacket>1&&n%sd.samplesPerPacket!=0){c++;break;} // a partial packet ends the stream here
				if(QuickTimeFile.typeName(sd.format).equals("ima4")){c++;break;} // one decoding per chunk: QuickTime hands a chunk per buffer
			}
			short[] pcm=decode(sd,bytes.toByteArray(),count);
			if(sd.channels!=channels||sd.sampleRate!=rate)pcm=convert(new Pcm(pcm,(float)sd.sampleRate,sd.channels),rate,channels).samples;
			runs.add(pcm);total+=pcm.length;
		}
		short[] all=new short[total];int at=0;for(short[] r:runs){System.arraycopy(r,0,all,at,r.length);at+=r.length;}
		return new Pcm(all,rate,channels);
	}

	/**
	 * The stored bytes of media samples [first, first+count) of a packet format, whole packets (what QuickTime hands to
	 * the Sound Manager for a compressed track); null when the range is not on packet boundaries or crosses descriptions.
	 */
	static byte[] packetBytes(QuickTimeFile.Media m,long first,long count) throws IOException {
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();long end=first+count;
		for(int c=0;c<m.chunkCount()&&m.chunkFirstSample(c)<end;c++){
			long s0=m.chunkFirstSample(c),s1=s0+m.chunkSamples(c);if(s1<=first)continue;
			QuickTimeFile.SampleDescription d=m.description(m.chunkDescription(c));
			if(!(d instanceof QuickTimeFile.SoundDescription))return null;
			QuickTimeFile.SoundDescription sd=(QuickTimeFile.SoundDescription)d;int spp=Math.max(1,sd.samplesPerPacket);
			long a=Math.max(first,s0)-s0,b=Math.min(end,s1)-s0;
			long pa=a/spp,pb=(b+spp-1)/spp;
			byte[] chunk=m.track.movie().read(m.dataFile(d),m.chunkOffsets[c]+pa*sd.bytesPerFrame,(int)((pb-pa)*sd.bytesPerFrame));
			out.write(chunk,0,chunk.length);
		}
		return out.toByteArray();
	}

	/** {@code frames} sample frames of one description's bytes as interleaved 16-bit samples. */
	static short[] decode(QuickTimeFile.SoundDescription d,byte[] b,long frames) throws IOException {
		int ch=d.channels;String format=QuickTimeFile.typeName(d.format);
		int n=(int)Math.min(frames*ch,Integer.MAX_VALUE-8);
		short[] out=new short[n];
		switch(format){
			case "raw ":case "NONE":case "twos":case "sowt":{
				int bytesPer=Math.max(1,d.sampleSize/8);boolean little=format.equals("sowt");
				boolean offset8=bytesPer==1&&!format.equals("twos")&&!format.equals("sowt"); // 8-bit 'raw '/'NONE' is offset binary; 'twos' is signed
				for(int i=0;i<n&&(i+1)*bytesPer<=b.length;i++){
					int p=i*bytesPer;
					if(bytesPer==1)out[i]=(short)(offset8?((b[p]&0xff)-128)<<8:b[p]<<8);
					else out[i]=(short)(little?(b[p+bytesPer-1]<<8|(b[p+bytesPer-2]&0xff)):(b[p]<<8|(b[p+1]&0xff))); // the top 16 bits
				}
				return out;
			}
			case "in24":for(int i=0;i<n&&i*3+2<b.length;i++)out[i]=(short)(b[i*3]<<8|(b[i*3+1]&0xff));return out;
			case "in32":for(int i=0;i<n&&i*4+3<b.length;i++)out[i]=(short)(b[i*4]<<8|(b[i*4+1]&0xff));return out;
			case "fl32":for(int i=0;i<n&&i*4+3<b.length;i++)out[i]=clip16(Math.round(Float.intBitsToFloat(QuickTimeFile.u4(b,i*4))*32768.0));return out;
			case "fl64":for(int i=0;i<n&&i*8+7<b.length;i++)out[i]=clip16(Math.round(Double.longBitsToDouble(QuickTimeFile.u8(b,i*8))*32768.0));return out;
			case "ulaw":case "alaw":{
				// G.711 through Java Sound's converters
				AudioFormat coded=new AudioFormat(format.equals("ulaw")?AudioFormat.Encoding.ULAW:AudioFormat.Encoding.ALAW,(float)d.sampleRate,8,ch,ch,(float)d.sampleRate,false);
				AudioFormat pcm=new AudioFormat((float)d.sampleRate,16,ch,true,true);
				int len=(int)Math.min(b.length,(long)n);
				try(AudioInputStream in=new AudioInputStream(new ByteArrayInputStream(b,0,len),coded,len/ch);AudioInputStream conv=AudioSystem.getAudioInputStream(pcm,in)){
					byte[] s=conv.readAllBytes();
					for(int i=0;i<n&&i*2+1<s.length;i++)out[i]=(short)(s[i*2]<<8|(s[i*2+1]&0xff));
				}
				return out;
			}
			case "ima4":return ima4(b,ch,n);
			case "MAC3":case "MAC6":{
				// MACE expands 6 samples a packet; the decoder writes 8-bit offset binary
				byte[] pcm=format.equals("MAC3")?MaceDecoder.decode3(b,0,b.length,ch):MaceDecoder.decode6(b,0,b.length,ch);
				for(int i=0;i<n&&i<pcm.length;i++)out[i]=(short)(((pcm[i]&0xff)-128)<<8);
				return out;
			}
			default:throw new IOException("sound format '"+format+"' is not decoded");
		}
	}

	// ------------------------------------------------------------------ IMA 4:1 ('ima4')

	/** IMA ADPCM step sizes and index changes (IMA Digital Audio Focus and Technical Working Groups, Recommended Practices). */
	private static final int[] IMA_STEP={7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,
		157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,
		2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,
		27086,29794,32767};
	private static final int[] IMA_INDEX={-1,-1,-1,-1,2,4,6,8};

	/**
	 * Apple's IMA 4:1: per channel, 34-byte packets of 64 samples (a stereo frame holds one packet of each channel). A
	 * packet starts with a big-endian word: the predictor in its top 9 bits, the step index in its low 7; then 32 bytes
	 * of 4-bit codes, low nibble first.
	 *
	 * Like Apple's 'sdec'/'ima4' decompressor (driven in the VM, tools/mace/MaceOracle --ima4): the state comes from a
	 * packet's header only where a decoding request starts - the start of the buffer and every 1024 samples (16 packets)
	 * after it, the most the decompressor produces per call - and runs on across the packets in between, whose headers are
	 * ignored. For data from a real encoder the headers equal the running state to 7 bits, so this only shows in edge
	 * cases; QuickTime hands a chunk per buffer, so {@link #media} decodes 'ima4' chunk by chunk.
	 */
	static short[] ima4(byte[] b,int channels,int n){
		short[] out=new short[n];int frames=b.length/(34*channels);
		int[] predictors=new int[channels],indexes=new int[channels];
		for(int f=0;f<frames;f++)for(int c=0;c<channels;c++){
			int p=(f*channels+c)*34;
			int predictor=predictors[c],index=indexes[c];
			if(f%16==0){int header=(b[p]&0xff)<<8|(b[p+1]&0xff);predictor=(short)(header&0xff80);index=Math.min(88,header&0x7f);}
			for(int i=0;i<64;i++){
				int code=(b[p+2+i/2]>>((i&1)*4))&0x0f;
				int step=IMA_STEP[index],diff=step>>3;
				if((code&4)!=0)diff+=step;if((code&2)!=0)diff+=step>>1;if((code&1)!=0)diff+=step>>2;
				predictor+=(code&8)!=0?-diff:diff;
				if(predictor>32767)predictor=32767;else if(predictor<-32768)predictor=-32768;
				index+=IMA_INDEX[code&7];if(index<0)index=0;else if(index>88)index=88;
				int at=(f*64+i)*channels+c;if(at<n)out[at]=(short)predictor;
			}
			predictors[c]=predictor;indexes[c]=index;
		}
		return out;
	}

	/** A version-0 sound description of a format (what a Sound Manager header or a buffer says about its samples). */
	static QuickTimeFile.SoundDescription description(String format,int channels,int sampleSize,double rate){
		byte[] d=new byte[36];
		d[3]=36;System.arraycopy(format.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),0,d,4,4);d[15]=1;
		d[24]=(byte)(channels>>8);d[25]=(byte)channels;d[26]=(byte)(sampleSize>>8);d[27]=(byte)sampleSize;
		int fixed=(int)Math.round(rate*65536.0);d[32]=(byte)(fixed>>>24);d[33]=(byte)(fixed>>>16);d[34]=(byte)(fixed>>>8);d[35]=(byte)fixed;
		return new QuickTimeFile.SoundDescription(d);
	}

	/** Nearest-sample rate conversion and channel mapping (mono to both, stereo to mono averaged). */
	static Pcm convert(Pcm p,float rate,int channels){
		if(p.rate==rate&&p.channels==channels)return p;
		int frames=(int)Math.round(p.frames()*(double)rate/p.rate);
		short[] out=new short[frames*channels];
		for(int f=0;f<frames;f++){
			int src=(int)Math.min(p.frames()-1,(long)Math.floor(f*(double)p.rate/rate));if(src<0)continue;
			for(int c=0;c<channels;c++){
				int v;
				if(p.channels==channels)v=p.samples[src*channels+c];
				else if(p.channels==1)v=p.samples[src];
				else if(channels==1){int s=0;for(int k=0;k<p.channels;k++)s+=p.samples[src*p.channels+k];v=s/p.channels;}
				else v=p.samples[src*p.channels+Math.min(c,p.channels-1)];
				out[f*channels+c]=(short)v;
			}
		}
		return new Pcm(out,rate,channels);
	}
	static short clip16(long v){return (short)(v>32767?32767:v<-32768?-32768:v);}
}

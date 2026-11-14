import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

/** MACE sounds as AudioInputStreams for the desktop (the decoding itself is MaceCodec's, which the core uses too). */
final class MaceDecoder {
	private MaceDecoder() {}

	static boolean canDecode(String format){return MaceCodec.canDecode(format);}
	static byte[] decode3(byte[] packets,int offset,int length,int channels){return MaceCodec.decode3(packets,offset,length,channels);}
	static byte[] decode6(byte[] packets,int offset,int length,int channels){return MaceCodec.decode6(packets,offset,length,channels);}

	/**
	 * The MACE sound of a 'snd ' resource (format 1 or 2) decoded to an unsigned 8-bit PCM stream, or null when the
	 * resource is not MACE (uncompressed or malformed). The first bufferCmd/soundCmd with the offset flag names the
	 * header: a compressed sound header (encode $FE) with compressionID 3 or 4, or -1 and format 'MAC3'/'MAC6', holds
	 * the packets at +64 (numChannels.l at +4, sampleRate.l at +8, numFrames.l at +22); an old-style resource whose
	 * sampled synth is opened with initMACE3 ($0300) or initMACE6 ($0400) keeps the packets after a standard header
	 * (samples at +22, length.l at +4).
	 */
	static AudioInputStream streamFromSnd(byte[] snd){
		if(snd==null||snd.length<8)return null;
		int format=u16(snd,0),commandOffset,count;boolean initMace3=false,initMace6=false;
		if(format==1){int synths=u16(snd,2);if(4+synths*6+2>snd.length)return null;
			for(int i=0;i<synths;i++){long init=u32(snd,6+i*6);if((init&0x0700)==0x0400)initMace6=true;else if((init&0x0300)==0x0300)initMace3=true;}
			commandOffset=4+synths*6+2;count=u16(snd,commandOffset-2);}
		else if(format==2){commandOffset=6;count=u16(snd,4);}
		else return null;
		for(int i=0;i<count;i++){int o=commandOffset+i*8;if(o+8>snd.length)return null;int raw=u16(snd,o),kind=raw&0x3fff;
			if((kind!=0x51&&kind!=0x50)||(raw&0x8000)==0)continue;
			long headerOffset=u32(snd,o+4);if(headerOffset+22>snd.length)return null;int header=(int)headerOffset;
			if(u32(snd,header)!=0)return null;
			int encoding=snd[header+20]&255;float rate=(float)(u32(snd,header+8)/65536.0);if(rate<=0)return null;
			if(encoding==0xfe){if(header+64>snd.length)return null;
				int compressionID=(short)u16(snd,header+56);long formatCode=u32(snd,header+40);
				boolean mac3=compressionID==3||(compressionID==-1&&formatCode==0x4d414333L),mac6=compressionID==4||(compressionID==-1&&formatCode==0x4d414336L); // 'MAC3' / 'MAC6'
				if(!mac3&&!mac6)return null;
				int channels=(int)u32(snd,header+4);if(channels<1||channels>2)return null;
				long frames=u32(snd,header+22);int packets=header+64;long bytes=Math.min(frames*(mac3?2:1)*channels,snd.length-packets);
				return mac3?stream3(snd,packets,(int)bytes,channels,rate):stream6(snd,packets,(int)bytes,channels,rate);}
			if(encoding==0&&(initMace3||initMace6)){int samples=header+22;long bytes=Math.min(u32(snd,header+4),snd.length-samples);
				return initMace3?stream3(snd,samples,(int)bytes,1,rate):stream6(snd,samples,(int)bytes,1,rate);}
			return null;}
		return null;
	}
	private static int u16(byte[] b,int o){return (b[o]&0xff)<<8|(b[o+1]&0xff);}
	private static long u32(byte[] b,int o){return ((long)u16(b,o)<<16)|u16(b,o+2);}

	/** The decoded MACE 3:1 sound as an unsigned 8-bit PCM stream. */
	static AudioInputStream stream3(byte[] packets,int offset,int length,int channels,float rate){
		return pcmStream(decode3(packets,offset,length,channels),channels,rate);
	}
	/** The decoded MACE 6:1 sound as an unsigned 8-bit PCM stream. */
	static AudioInputStream stream6(byte[] packets,int offset,int length,int channels,float rate){
		return pcmStream(decode6(packets,offset,length,channels),channels,rate);
	}
	private static AudioInputStream pcmStream(byte[] pcm,int channels,float rate){
		AudioFormat format=new AudioFormat(rate,8,channels,false,true);
		return new AudioInputStream(new ByteArrayInputStream(pcm),format,pcm.length/channels);
	}
}

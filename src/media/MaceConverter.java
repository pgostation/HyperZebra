import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Plays the MACE 3:1 / 6:1 compressed sound of classic snd resources: the native {@link MaceDecoder}
 * expands the AIFF-C's packets, and anything it declines is handed to an installed ffmpeg (the same
 * external converter the QuickTime XCMDs fall back to), which turns the AIFF-C into 16-bit PCM WAV in a
 * temporary file that Java Sound then reads. Returns null when the data is not MACE or ffmpeg is
 * missing/fails, so callers can report the usual "unsupported format".
 */
final class MaceConverter {
	private static Boolean ffmpegAvailable;

	private MaceConverter() {
	}

	static boolean isFfmpegAvailable(){
		if(ffmpegAvailable==null){
			try {
				Process p = new ProcessBuilder("ffmpeg", "-version").start();
				p.getInputStream().readAllBytes();
				p.getErrorStream().readAllBytes();
				ffmpegAvailable = p.waitFor()==0;
			} catch (Exception e) {
				ffmpegAvailable = false;
			}
		}
		return ffmpegAvailable;
	}

	static AudioInputStream open(File file) throws IOException {
		return open(Files.readAllBytes(file.toPath()));
	}

	/** True for an AIFF-C whose COMM chunk names the MAC3 or MAC6 compression. */
	static boolean isMace(byte[] aifc){
		if(aifc==null || aifc.length<12 || !fourCC(aifc, 0).equals("FORM") ||
				!fourCC(aifc, 8).equals("AIFC")) return false;
		for(int offset=12; offset+8<=aifc.length; ){
			String chunk = fourCC(aifc, offset);
			int size = readInt(aifc, offset+4);
			int dataOffset = offset+8;
			if(size<0 || dataOffset+size>aifc.length) return false;
			if(chunk.equals("COMM") && size>=22){
				String compression = fourCC(aifc, dataOffset+18);
				return compression.equals("MAC3") || compression.equals("MAC6");
			}
			offset = dataOffset+size+(size&1);
		}
		return false;
	}

	//snd リソースをその場でAIFF-C相当のバイト列にしたもの(HCResource.decodeSndToAiffBytes)を
	//直接受け取り、ffmpegでPCMへ変換して返す。MACEでなければnull。
	static AudioInputStream open(byte[] aifc) throws IOException {
		if(!isMace(aifc)) return null;
		AudioInputStream decoded = decodeMace(aifc);
		if(decoded!=null) return decoded;
		if(!isFfmpegAvailable()) return null;
		File in = TempFiles.createFile("hyperzebra-mace-", ".aifc");
		File out = new File(in.getPath()+".wav");
		try {
			Files.write(in.toPath(), aifc);
			ArrayList<String> cmd = new ArrayList<String>();
			cmd.add("ffmpeg"); cmd.add("-y"); cmd.add("-i"); cmd.add(in.getPath());
			cmd.add("-acodec"); cmd.add("pcm_s16le"); cmd.add("-f"); cmd.add("wav"); cmd.add(out.getPath());
			Process p = new ProcessBuilder(cmd).start();
			p.getInputStream().readAllBytes();
			p.getErrorStream().readAllBytes();
			int status;
			try { status = p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
			if(status!=0 || !out.isFile()) return null;
			byte[] wav = Files.readAllBytes(out.toPath());
			try {
				return AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
			} catch (UnsupportedAudioFileException e) {
				return null;
			}
		} finally {
			in.delete();
			out.delete();
		}
	}

	/** MACE AIFF-C decoded by the native {@link MaceDecoder} (COMM: numChannels, sampleRate; SSND: the packets); null for other formats. */
	private static AudioInputStream decodeMace(byte[] aifc){
		int channels=1;float rate=0;String compression="";
		for(int offset=12; offset+8<=aifc.length; ){
			String chunk = fourCC(aifc, offset);
			int size = readInt(aifc, offset+4);
			int dataOffset = offset+8;
			if(size<0 || dataOffset+size>aifc.length) return null;
			if(chunk.equals("COMM") && size>=22){
				channels=((aifc[dataOffset]&0xff)<<8|(aifc[dataOffset+1]&0xff));
				rate=(float)ieee80(aifc, dataOffset+8);
				compression=fourCC(aifc, dataOffset+18);
			}
			if(chunk.equals("SSND") && size>=8){
				if(!MaceDecoder.canDecode(compression) || channels<1 || channels>2 || rate<=0) return null;
				int skip=readInt(aifc, dataOffset);
				return compression.equals("MAC3") ? MaceDecoder.stream3(aifc, dataOffset+8+skip, size-8-skip, channels, rate)
					: MaceDecoder.stream6(aifc, dataOffset+8+skip, size-8-skip, channels, rate);
			}
			offset = dataOffset+size+(size&1);
		}
		return null;
	}
	/** An AIFF-C of Apple's IMA 4:1 ('ima4', Nanosaur's songs) as 16-bit PCM through {@link QuickTimeSound#ima4}; null for other formats. */
	static AudioInputStream openIma4(byte[] aifc){
		if(aifc==null||aifc.length<12||!fourCC(aifc,0).equals("FORM")||!fourCC(aifc,8).equals("AIFC"))return null;
		int channels=1;float rate=0;String compression="";
		for(int offset=12; offset+8<=aifc.length; ){
			String chunk=fourCC(aifc,offset);int size=readInt(aifc,offset+4);int dataOffset=offset+8;
			if(size<0||dataOffset+size>aifc.length)return null;
			if(chunk.equals("COMM")&&size>=22){channels=((aifc[dataOffset]&0xff)<<8|(aifc[dataOffset+1]&0xff));rate=(float)ieee80(aifc,dataOffset+8);compression=fourCC(aifc,dataOffset+18);}
			if(chunk.equals("SSND")&&size>=8){
				if(!compression.equals("ima4")||channels<1||channels>2||rate<=0)return null;
				int skip=readInt(aifc,dataOffset);int start=dataOffset+8+skip,length=size-8-skip;if(length<=0)return null;
				byte[] packets=java.util.Arrays.copyOfRange(aifc,start,start+length);
				int frames=packets.length/(34*channels)*64;
				short[] pcm=QuickTimeSound.ima4(packets,channels,frames*channels);
				byte[] bytes=new byte[pcm.length*2];for(int i=0;i<pcm.length;i++){bytes[2*i]=(byte)(pcm[i]>>8);bytes[2*i+1]=(byte)pcm[i];}
				javax.sound.sampled.AudioFormat format=new javax.sound.sampled.AudioFormat(rate,16,channels,true,true);
				return new AudioInputStream(new ByteArrayInputStream(bytes),format,frames);
			}
			offset=dataOffset+size+(size&1);
		}
		return null;
	}
	/** An 80-bit IEEE extended sample rate. */
	private static double ieee80(byte[] b, int o){
		int exponent=((b[o]&0x7f)<<8|(b[o+1]&0xff))-16383;
		long mantissa=0;for(int i=0;i<8;i++)mantissa=mantissa<<8|(b[o+2+i]&0xff);
		double value=(mantissa>>>11)*Math.pow(2, exponent-52);
		return (b[o]&0x80)!=0?-value:value;
	}

	private static String fourCC(byte[] data, int offset) {
		return new String(data, offset, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	private static int readInt(byte[] data, int offset) {
		return ((data[offset]&0xFF)<<24) | ((data[offset+1]&0xFF)<<16) | ((data[offset+2]&0xFF)<<8) | (data[offset+3]&0xFF);
	}
}

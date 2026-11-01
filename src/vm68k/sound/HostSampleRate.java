import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The sample rates HyperZebra hands to the host's audio output. A line opened at any other rate is resampled by CoreAudio
 * (macOS 27, JDK 26), and at the Macintosh's rate11khz (11127.27 Hz, Fixed $2B7745D1) its converter breaks after about 68 s
 * of audio: the IO thread spins at full CPU, the line swallows samples five times faster than they play, and the device
 * mutex it keeps holding stops every other line from opening. Quest for Glory I streams its music at that rate through
 * SndPlayDoubleBuffer; a minute later the Sound Manager mixer's line hung in DirectAudioDevice.nOpen, no sampled sound ever
 * ended, and the callBackCmd its character selection waits for never came. A Clip at that rate breaks the same way, while
 * 11025/22050/44100 Hz lines played on unharmed: anything but a plain rate is therefore converted here, to the mixer's rate.
 */
final class HostSampleRate {
	private HostSampleRate(){}

	/** The rate everything else is converted to: the mixer's. */
	static final float OUTPUT=SoundMixer68k.RATE;
	private static final float[] PLAIN={8000,11025,16000,22050,24000,32000,44100,48000,88200,96000};

	/** Whether the host's audio output can be given this rate as it is. */
	static boolean plain(float rate){for(float p:PLAIN)if(rate==p)return true;return false;}

	/** A stream for a Clip: the same stream at a plain rate, else converted to 16-bit at {@link #OUTPUT} (by the JDK's converter; the Clip reads it to the end). */
	static AudioInputStream forHost(AudioInputStream in){
		AudioFormat f=in.getFormat();
		if(plain(f.getSampleRate())||f.getEncoding()!=AudioFormat.Encoding.PCM_SIGNED&&f.getEncoding()!=AudioFormat.Encoding.PCM_UNSIGNED)return in;
		AudioFormat target=new AudioFormat(OUTPUT,16,f.getChannels(),true,true);
		try{return AudioSystem.getAudioInputStream(target,in);}catch(IllegalArgumentException unsupported){return in;}
	}

	/**
	 * Converts a stream that arrives in buffers (SndPlayDoubleBuffer) from 8- or 16-bit PCM at the program's rate to
	 * 16-bit big-endian signed PCM at {@link #OUTPUT}, interpolating linearly as the mixer does; the position carries over
	 * from one buffer to the next, so the buffers join without a click.
	 */
	static final class Stream {
		private final AudioFormat output;private final int channels,frameBytes;private final boolean wide,signed,bigEndian;
		/** Input frames per output frame. */
		private final double step;
		/** Where the next output frame falls, in input frames from the next buffer's first one (-1..0: between the previous buffer's last frame and it). */
		private double position;
		/** The previous buffer's last frame (silence before the first buffer). */
		private final int[] previous;

		Stream(AudioFormat input){
			channels=input.getChannels();wide=input.getSampleSizeInBits()==16;frameBytes=channels*(wide?2:1);
			signed=input.getEncoding()==AudioFormat.Encoding.PCM_SIGNED;bigEndian=input.isBigEndian();
			step=input.getSampleRate()/OUTPUT;previous=new int[channels];
			output=new AudioFormat(OUTPUT,16,channels,true,true);
		}
		AudioFormat output(){return output;}
		/** About how many output bytes a buffer of this many input bytes becomes. */
		int outputBytes(int inputBytes){return (int)Math.ceil(inputBytes/frameBytes/step)*channels*2;}

		byte[] convert(byte[] data){
			int frames=data.length/frameBytes;if(frames==0)return new byte[0];
			byte[] out=new byte[((int)((frames-position)/step)+2)*channels*2];int o=0;double p=position;
			for(;p<=frames-1;p+=step){
				int i=(int)Math.floor(p);double fraction=p-i;
				for(int c=0;c<channels;c++){
					int s0=i<0?previous[c]:sample(data,i,c),s1=i+1<frames?sample(data,i+1,c):s0;
					int v=(int)Math.round(s0+(s1-s0)*fraction);out[o++]=(byte)(v>>8);out[o++]=(byte)v;}
			}
			position=p-frames;
			for(int c=0;c<channels;c++)previous[c]=sample(data,frames-1,c);
			return o==out.length?out:java.util.Arrays.copyOf(out,o);
		}
		/** One sample as a signed 16-bit value. */
		private int sample(byte[] d,int frame,int channel){
			int at=frame*frameBytes+channel*(wide?2:1);
			if(wide)return (short)(bigEndian?(d[at]<<8|d[at+1]&0xff):(d[at+1]<<8|d[at]&0xff));
			return signed?d[at]<<8:((d[at]&0xff)-128)<<8;
		}
	}
}

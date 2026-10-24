import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * A long sound played through a SourceDataLine while it is decoded (an MP3 movie's), with the start/stop/position/volume a
 * Clip offers but without holding all its samples: a Clip of a four-minute song would take 40 MB, and Airburst opens all of
 * its songs at start-up. The writer thread only writes what the line has room for, so stopping never waits on a blocked write.
 */
final class StreamingSound {
	/** The sound from a sample frame on. */
	interface Source { AudioInputStream open(long frame) throws IOException; }

	private final Source source;private final AudioFormat format;private final long frames;
	private SourceDataLine line;private Thread writer;private volatile boolean running;
	private long position,lineBase; // where the current run started, and the line's frame position then
	private int level=256;private volatile boolean loop;

	StreamingSound(Source source,AudioFormat format,long frames){this.source=source;this.format=format;this.frames=frames;}
	static StreamingSound of(Mp3Decoder.Stream s){return new StreamingSound(s::pcm,s.format(),s.totalSamples());}

	long getMicrosecondLength(){return (long)(frames*1e6/format.getSampleRate());}
	synchronized long getMicrosecondPosition(){return (long)(currentFrame()*1e6/format.getSampleRate());}
	private long currentFrame(){
		if(!running||line==null)return position;
		long f=position+line.getLongFramePosition()-lineBase;return loop&&frames>0?f%frames:Math.min(frames,f);
	}
	boolean isRunning(){return running;}
	/** Plays again from the start at the end, until stopped (Clip.LOOP_CONTINUOUSLY). */
	void setLoop(boolean loop){this.loop=loop;}

	synchronized void setMicrosecondPosition(long micros){
		boolean was=running;stop();
		position=Math.max(0,Math.min(frames,(long)(micros*format.getSampleRate()/1e6)));
		if(was)start();
	}
	synchronized void start(){
		if(running||position>=frames)return;
		try{
			if(line==null){line=AudioSystem.getSourceDataLine(format);line.open(format,(int)format.getSampleRate()/4*format.getFrameSize());applyLevel();} // a quarter second of buffer
		}catch(LineUnavailableException|IllegalArgumentException e){System.err.println("[sound] no audio line: "+e);line=null;return;}
		final AudioInputStream in;
		try{in=source.open(position);}catch(IOException e){System.err.println("[sound] "+e);return;}
		lineBase=line.getLongFramePosition();running=true;line.start();
		final SourceDataLine out=line;
		writer=new Thread(()->{
			byte[] buf=new byte[out.getBufferSize()];int frameSize=format.getFrameSize();
			AudioInputStream s=in;
			try{
				while(running){
					int room=out.available()/frameSize*frameSize;
					if(room<frameSize*256){Thread.sleep(5);continue;}
					int n=s.read(buf,0,Math.min(room,buf.length));
					if(n<=0){if(!loop)break;s.close();s=source.open(0);continue;}
					out.write(buf,0,n/frameSize*frameSize);
				}
			}catch(IOException|InterruptedException e){}
			finally{try{s.close();}catch(IOException e){}}
		},"StreamingSound");
		writer.setDaemon(true);writer.start();
	}
	synchronized void stop(){
		if(!running)return;
		position=currentFrame();running=false;
		try{writer.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
		line.stop();line.flush();
	}
	synchronized void close(){stop();if(line!=null){line.close();line=null;}}
	/** Volume 0-256 (256 = full). */
	synchronized void setLevel(int level){this.level=Math.max(0,Math.min(256,level));applyLevel();}
	private void applyLevel(){
		if(line==null||!line.isControlSupported(FloatControl.Type.MASTER_GAIN))return;
		FloatControl gain=(FloatControl)line.getControl(FloatControl.Type.MASTER_GAIN);
		float dB=level==0?gain.getMinimum():(float)(20*Math.log10(level/256.0));gain.setValue(Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),dB)));
	}
}

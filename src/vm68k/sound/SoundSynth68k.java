import java.util.ArrayDeque;
import java.util.Deque;

import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The square-wave and wave-table synthesizers of a sound channel: freqDurationCmd/restCmd/freqCmd queue notes that a
 * player thread renders into a SourceDataLine, timbreCmd shapes the built-in wave (0 = sine ... 255 = square),
 * waveTableCmd installs one cycle of 8-bit samples to play instead, soundCmd a sampled sound (an {@link Instrument}) that
 * the notes play pitched against its base note and looped while they last. quietCmd/flushCmd cut the queue,
 * pauseCmd/resumeCmd hold the line, ampCmd/volumeCmd scale the output; callBackCmds queued behind notes run when the
 * notes are done. The line is stereo: volumeCmd's left and right levels place the notes (PgSoundX sweeps them).
 */
final class SoundSynth68k implements Runnable {
	private static final int RATE=22050,BLOCK=256;
	private static final class Entry {final double note;final int samples;final Runnable callback;int[] level;Entry(double note,int samples,Runnable callback){this.note=note;this.samples=samples;this.callback=callback;}}

	private final Deque<Entry> queue=new ArrayDeque<Entry>();
	private volatile boolean stopped,paused,cut;
	private volatile int timbre=255;
	private volatile byte[] waveTable;
	private volatile Instrument instrument;

	/** A sampled sound installed by soundCmd: mono 16-bit samples, their rate, the MIDI note they were recorded at and the
	 *  loop (sample frames, loopEnd > loopStart + 1 for a real loop) notes repeat while they last. */
	static final class Instrument {
		final short[] samples;final double rate,baseNote;final int loopStart,loopEnd;
		private Instrument(short[] samples,double rate,double baseNote,int loopStart,int loopEnd){this.samples=samples;this.rate=rate;this.baseNote=baseNote;this.loopStart=loopStart;this.loopEnd=loopEnd;}
		/** Mixes a PCM stream (8-bit unsigned or 16-bit signed, mono or stereo) down to mono; null for other formats or an empty sound. */
		static Instrument of(AudioInputStream stream,double baseNote,long loopStart,long loopEnd) throws IOException {
			AudioFormat f=stream.getFormat();int channels=f.getChannels(),bits=f.getSampleSizeInBits();if((bits!=8&&bits!=16)||channels<1||channels>2)return null;
			byte[] pcm=stream.readAllBytes();int frameBytes=channels*(bits/8),frames=pcm.length/frameBytes;if(frames==0)return null;
			short[] samples=new short[frames];
			for(int i=0;i<frames;i++){int sum=0;for(int c=0;c<channels;c++){int o=i*frameBytes+c*(bits/8);
				sum+=bits==8?((pcm[o]&0xff)-128)<<8:f.isBigEndian()?(short)((pcm[o]&0xff)<<8|(pcm[o+1]&0xff)):(short)((pcm[o+1]&0xff)<<8|(pcm[o]&0xff));}
				samples[i]=(short)(sum/channels);}
			int end=(int)Math.min(loopEnd,frames),start=(int)Math.min(loopStart,frames);
			return new Instrument(samples,f.getSampleRate(),baseNote,start,end>start+1?end:0);
		}
		boolean loops(){return loopEnd>loopStart+1;}
	}
	/** 0..255 (ampCmd) and the left/right Fixed 8.8 volumes (volumeCmd), read live by the player. */
	volatile int amplitude=255,volume=0x01000100;
	/** rateCmd: the sampled notes play this many times faster (and higher), the one sounding now too. */
	volatile double rate=1;
	private volatile boolean playing;
	private Thread thread;

	SoundSynth68k(int amplitude,int volume){this.amplitude=amplitude;this.volume=volume;}

	/** freqDurationCmd: a note (MIDI value, fractions allowed) for a duration in half-milliseconds. */
	void note(double note,int halfMilliseconds){enqueue(new Entry(note,samplesFor(halfMilliseconds),null));}
	/** restCmd: silence for a duration in half-milliseconds. */
	void rest(int halfMilliseconds){enqueue(new Entry(-1,samplesFor(halfMilliseconds),null));}
	/** freqCmd: a note that sounds until the next command. */
	void frequency(double note){synchronized(queue){cut=true;queue.add(new Entry(note,-1,null));queue.notifyAll();}start();}
	/** callBackCmd behind the queued notes. */
	void callback(Runnable r){enqueue(new Entry(-1,0,r));}
	/** An ampCmd/volumeCmd that came through SndDoCommand behind queued notes: the level of the notes after it. */
	void queueLevel(int amplitude,int volume){Entry e=new Entry(-1,0,LEVEL_MARK);e.level=new int[]{amplitude,volume};enqueue(e);}
	/** A quietCmd/flushCmd that came through SndDoCommand: it cuts what is still queued when the notes before it are done. */
	void queueFlush(){enqueue(new Entry(-1,0,FLUSH_MARK));}
	void timbre(int value){timbre=Math.max(0,Math.min(255,value));}
	void waveTable(byte[] table){waveTable=table==null||table.length<2?null:table;}
	/** soundCmd: the sampled sound the notes play from now on (null goes back to the built-in wave). */
	void instrument(Instrument sound){instrument=sound;}
	Instrument instrument(){return instrument;}
	/** quietCmd/flushCmd: everything queued is dropped and the current note ends. */
	void flush(){synchronized(queue){queue.clear();cut=true;queue.notifyAll();}}
	void pause(boolean on){paused=on;}
	void stop(){stopped=true;flush();}
	/** Whether a note is sounding or waiting (SndChannelStatus' scChannelBusy). */
	boolean busy(){synchronized(queue){return playing||!queue.isEmpty();}}

	/** The marker {@link #queueFlush} puts in the queue; the player recognises it by identity. */
	private static final Runnable FLUSH_MARK=new Runnable(){@Override public void run(){}};
	/** The marker of {@link #queueLevel}. */
	private static final Runnable LEVEL_MARK=new Runnable(){@Override public void run(){}};

	private static int samplesFor(int halfMilliseconds){return (int)Math.max(0,Math.min(RATE*60L,(long)halfMilliseconds*RATE/2000));}
	private void enqueue(Entry e){synchronized(queue){queue.add(e);queue.notifyAll();}start();}
	private synchronized void start(){
		if(thread!=null&&thread.isAlive())return;if(!TSound.use){drainSilently();return;}
		// each player is a thread with its own line: past this many at once (all programs together) the notes go unheard, as with no
		// audio, rather than the host running out of threads
		if(PLAYERS.incrementAndGet()>MAX_PLAYERS){PLAYERS.decrementAndGet();drainSilently();return;}
		thread=new Thread(this,"68K synthesizer");thread.setDaemon(true);
		try{thread.start();}catch(OutOfMemoryError|RuntimeException e){PLAYERS.decrementAndGet();thread=null;drainSilently();}
	}
	private static final int MAX_PLAYERS=64;
	private static final java.util.concurrent.atomic.AtomicInteger PLAYERS=new java.util.concurrent.atomic.AtomicInteger();
	/** No audio: callbacks still run, notes take no time. */
	private void drainSilently(){Entry e;while((e=poll())!=null){if(e.level!=null){amplitude=e.level[0];volume=e.level[1];}else if(e.callback!=null)e.callback.run();}}
	private Entry poll(){synchronized(queue){Entry e=queue.poll();playing=e!=null&&e.samples!=0;return e;}}

	@Override public void run(){
		AudioFormat format=new AudioFormat(RATE,16,2,true,true);SourceDataLine line=null;
		try{
			line=AudioSystem.getSourceDataLine(format);line.open(format,RATE/5*4);line.start();
			byte[] block=new byte[BLOCK*4];double phase=0;long idleSince=0;
			while(!stopped){
				Entry e;synchronized(queue){e=queue.poll();playing=e!=null&&e.samples!=0;cut=false;
					if(e==null){if(idleSince==0)idleSince=System.nanoTime();try{queue.wait(20);}catch(InterruptedException x){return;}
						if(System.nanoTime()-idleSince>3_000_000_000L)break;continue;}}
				idleSince=0;
				if(e.callback==FLUSH_MARK){synchronized(queue){queue.clear();}line.flush();phase=0;continue;}
				if(e.callback==LEVEL_MARK){amplitude=e.level[0];volume=e.level[1];continue;}
				if(e.callback!=null){line.drain();e.callback.run();continue;} // a callBackCmd means the notes before it have been heard, not just written
				double frequency=e.note<0?0:440.0*Math.pow(2,(e.note-69)/12.0);int remaining=e.samples;
				Instrument sound=e.note<0?null:instrument;double position=0,step=sound==null?0:sound.rate/RATE*Math.pow(2,(e.note-sound.baseNote)/12.0);
				boolean sampled=sound!=null&&step>0;
				while(!stopped&&!cut&&remaining!=0){
					while(paused&&!stopped&&!cut)try{Thread.sleep(5);}catch(InterruptedException x){return;}
					if(paused)continue;
					int n=remaining<0?BLOCK:Math.min(BLOCK,remaining);
					// ampCmd, volumeCmd and the output volume (SetSoundVol / SetDefaultOutputVolume, SoundMixer68k.outputVolume) - this line is not mixed there
					int levels=volume;double gain=(amplitude/255.0)*(sampled?1:0.5),gainLeft=gain*(levels&0xffff)/256.0*SoundMixer68k.gainLeft(),gainRight=gain*(levels>>>16)/256.0*SoundMixer68k.gainRight();
					byte[] table=waveTable;int shape=timbre;double speed=rate>0?rate:1;
					boolean done=false;
					for(int i=0;i<n;i++){
						double sample;
						if(frequency==0)sample=0;
						else if(sampled){ // the instrument's samples at the note's rate, looped while the note lasts, silent once past their end
							if(sound.loops()&&position>=sound.loopEnd)position-=sound.loopEnd-sound.loopStart;
							int index=(int)position;
							if(index>=sound.samples.length){sample=0;done=true;}
							else{double next=index+1<sound.samples.length?sound.samples[index+1]:sound.samples[index],frac=position-index;sample=(sound.samples[index]+(next-sound.samples[index])*frac)/32768.0;}
							position+=step*speed;}
						else if(table!=null){int index=(int)(phase*table.length)%table.length;sample=((table[index]&0xff)-128)/128.0;}
						else{double sine=Math.sin(phase*2*Math.PI),square=phase<0.5?1:-1;sample=sine+(square-sine)*shape/255.0;}
						// short ramps at both ends of a timed note keep it from clicking
						int played=e.samples<0?BLOCK:e.samples-remaining+i;if(played<64)sample*=played/64.0;if(remaining>=0&&remaining-i<64)sample*=(remaining-i)/64.0;
						int left=(int)Math.round(sample*gainLeft*32767),right=(int)Math.round(sample*gainRight*32767);
						left=Math.max(-32768,Math.min(32767,left));right=Math.max(-32768,Math.min(32767,right));
						block[i*4]=(byte)(left>>8);block[i*4+1]=(byte)left;block[i*4+2]=(byte)(right>>8);block[i*4+3]=(byte)right;
						if(!sampled){phase+=frequency/RATE;if(phase>=1)phase-=Math.floor(phase);}
					}
					line.write(block,0,n*4);
					if(remaining>0)remaining-=n;
					if(done&&remaining<0)break; // freqCmd on a one-shot sound: it played through
				}
				if(cut){line.flush();phase=0;}
			}
			if(!stopped)line.drain();
		}catch(LineUnavailableException|IllegalArgumentException unavailable){if(Mac68kApi.TRACE)System.err.println("[68K] synthesizer: "+unavailable);drainSilently();
		}finally{playing=false;if(line!=null)try{line.close();}catch(RuntimeException ignored){}PLAYERS.decrementAndGet();synchronized(this){thread=null;}if(!queue.isEmpty()&&!stopped)start();}
	}
}

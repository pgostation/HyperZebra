import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Software mixer for the Sound Manager's sampled sounds: every sound is decoded to PCM once and mixed by one thread
 * into a single SourceDataLine that stays open. Nothing here ever blocks the emulation thread: starting a voice is a
 * list insertion, stopping one a flag.
 * <p>
 * The previous design gave each sound a javax.sound Clip. Clip.close() holds the mixer's monitor while it joins the
 * clip's own thread, and that thread, when the sound has just ended, is waiting for the very same monitor to call
 * stop() - so close() sat there until its 2 s join timeout. Woden sends quietCmd before every effect and froze for
 * two seconds each time; an earlier implementation leaked a clip thread per frame for related reasons.
 */
final class SoundMixer68k {
	/** Output rate; sounds at other rates are resampled (linear interpolation) while mixing. */
	static final int RATE=44100;
	private static final int CHUNK_FRAMES=512; // ~12 ms per write: effects start within a frame of the game
	/** Output frames left of a decoded voice when it counts as nearly over ({@link Voice#onNearEnd}): two chunks, ~23 ms. */
	static final int NEAR_END_FRAMES=CHUNK_FRAMES*2;
	private static final SoundMixer68k INSTANCE=new SoundMixer68k();
	static SoundMixer68k get(){return INSTANCE;}

	/**
	 * A voice whose samples are produced as they are needed instead of being decoded up front. The mixer calls
	 * {@link #fill} on its own thread whenever the buffer runs out, so a synthesizer that a program keeps changing
	 * while it plays (the Sound Driver's four-tone record) is read again at every refill.
	 */
	interface Source {
		/** Fills the buffers from frame 0 and returns how many frames are valid; 0 ends the voice. */
		int fill(short[] left,short[] right);
	}

	/** One sound being played: mono or stereo 16-bit samples at its own rate, position in source frames. */
	static final class Voice {
		final short[] left,right;final int frames;final double sourceRate;
		/** rateCmd multiplier applied on top of the source rate. */
		volatile double rateMultiplier;
		/** Non-null for a streaming voice: the mixer refills {@link #left}/{@link #right} through it (see {@link Source}). */
		final Source source;
		/** Valid frames in the buffers: {@link #frames} for a decoded voice, the last fill's count for a streaming one. */
		private int available;
		private double position;
		/** The voice that takes over, in the same output frame, once this one's last frame is mixed (see {@link SoundMixer68k#link}). Guarded by the mixer's lock. */
		private Voice next;
		/** The mixer has run out of this voice and looked for its successor: too late to link one (guarded by the mixer's lock). */
		private boolean ranOut;
		volatile float gainLeft=1f,gainRight=1f;
		/** Output frames of a fade-out asked for by {@link SoundMixer68k#fadeOut}; the mixer takes it over at its next chunk (tests set it directly). */
		volatile int fadeRequest;
		/** Output frames the voice still plays at full gain before that fade begins (a closed stack's grace, see {@link SoundMixer68k#release}). */
		volatile int fadeHoldRequest;
		/** The fade in progress (mixer thread only): its length, the output frames still to go, the full-gain frames before it. */
		private int fadeTotal,fadeLeft,fadeHold;
		/** started: start() was called; ended: the last frame was mixed (or the voice was stopped); paused: keeps its position. */
		volatile boolean started,ended,paused,stopped;
		private final List<Runnable> onEnd=new ArrayList<Runnable>();
		Voice(short[] left,short[] right,double sourceRate,double rateMultiplier){this(left,right,sourceRate,rateMultiplier,null);}
		Voice(short[] left,short[] right,double sourceRate,double rateMultiplier,Source source){
			this.left=left;this.right=right;this.frames=left.length;this.sourceRate=sourceRate;this.rateMultiplier=rateMultiplier;
			this.source=source;this.available=source==null?left.length:0;}
		int frameLength(){return frames;}
		/** Frames played so far (source frames), like Clip.getFramePosition. */
		int framePosition(){return ended&&!stopped?frames:(int)Math.min(frames,position);}
		/** Playing from start() until the last frame has gone out (one frame late for a game polling SndChannelStatus is one frame too late). */
		boolean playing(){return started&&!ended;}
		boolean running(){return started&&!ended&&!paused;}
		/** Runs when the voice ends (last frame mixed, or stopped) - at once when it already has. Called on the mixer thread or the stopper's. */
		void onEnd(Runnable r){synchronized(onEnd){if(!ended){onEnd.add(r);return;}}r.run();}
		private void finish(){List<Runnable> run;synchronized(onEnd){if(ended)return;ended=true;run=new ArrayList<Runnable>(onEnd);onEnd.clear();nearEnd.addAll(0,run);run=new ArrayList<Runnable>(nearEnd);nearEnd.clear();}for(Runnable r:run)try{r.run();}catch(RuntimeException ignored){}}
		/** nearlyEnded: at most {@link SoundMixer68k#NEAR_END_FRAMES} output frames of a decoded voice are left to mix (or it has ended). */
		volatile boolean nearlyEnded;
		private final List<Runnable> nearEnd=new ArrayList<Runnable>();
		/**
		 * Runs when the voice is nearly over (see {@link #nearlyEnded}) - at once when it already is; a voice that ends
		 * (or is stopped) before that runs it then. The Sound Manager's callBackCmd behind a bufferCmd: a Mac calls back
		 * once its mixer has taken the buffer, before the last samples are heard, so the next bufferCmd still follows on.
		 */
		void onNearEnd(Runnable r){synchronized(onEnd){if(!ended&&!nearlyEnded){nearEnd.add(r);return;}}r.run();}
		private void nearlyFinished(){List<Runnable> run;synchronized(onEnd){if(nearlyEnded||ended)return;nearlyEnded=true;run=new ArrayList<Runnable>(nearEnd);nearEnd.clear();}for(Runnable r:run)try{r.run();}catch(RuntimeException ignored){}}
	}

	// ------------------------------------------------------------------ the output volume

	/**
	 * The volume of all sound the VMs make: SetSoundVol (the Sound Driver's csCode 2, SdVolume $260, 0..7), SetDefaultOutputVolume
	 * and SetSoundOutputInfo 'volu'/'hvol' set it, as they set a Mac's one output. It is the same for every VM of this
	 * HyperZebra - SetVolume XCMD and XTernal's SetSoundVolume set it in one external's VM and the sounds play in another, and
	 * HyperTalk's play goes through this mixer too. Applied to the mix (and to the Sound Manager's synthesizer and double-buffer
	 * lines), never to the host's own volume. Right << 16 | left, 0x0100 = full (more is taken as full); full at every start.
	 */
	private static volatile int outputVolume=0x01000100;
	/** The output volume in the Sound Manager's form (right << 16 | left, 0x0100 = full). */
	static int outputVolume(){return outputVolume;}
	static void setOutputVolume(int volume){outputVolume=volume;}
	/** Back to full volume (a host-side reset, e.g. when nothing that set it is open any more). */
	static void resetOutputVolume(){outputVolume=0x01000100;}
	/** The output volume as SdVolume's 0..7 (the louder channel; full = 7). */
	static int soundVol(){int v=outputVolume,m=Math.min(0x100,Math.max(v&0xffff,v>>>16));return Math.min(7,(m*7+128)>>8);}
	/** SetSoundVol(level): 0..7 as a linear share of full volume on both channels (7 = 0x0100, 0 = silent). */
	static void setSoundVol(int level){level=Math.max(0,Math.min(7,level));int c=(level*0x100+3)/7;outputVolume=c<<16|c;}
	/** Linear gains of the output volume (0..1) for code that plays on its own line (HyperTalk, QuickTime). */
	static float gainLeft(){return Math.min(0x100,outputVolume&0xffff)/256f;}
	static float gainRight(){return Math.min(0x100,outputVolume>>>16)/256f;}
	/** Get/SetSysBeepVolume: the alert sound's own level on top of the output volume (0x0100 = full). */
	private static volatile int sysBeepVolume=0x01000100;
	static int sysBeepVolume(){return sysBeepVolume;}
	static void setSysBeepVolume(int volume){sysBeepVolume=volume;}
	/**
	 * The system alert (SysBeep, HyperTalk's beep) at the output volume: false when it is silent (volume 0, where a Mac
	 * flashes the menu bar instead), the host's alert sound at full volume, otherwise a short tone of HyperZebra's own
	 * through this mixer at that level (the host's alert sound cannot be made quieter from here, and the host's volume is
	 * left alone). XTernal's SetSoundVolume TestDrive beeps after each level 1..7.
	 */
	static boolean beep(){
		if(!TSound.use)return true; // sound switched off in HyperZebra
		int b=sysBeepVolume;
		float gl=gainLeft()*Math.min(0x100,b&0xffff)/256f,gr=gainRight()*Math.min(0x100,b>>>16)/256f;
		if(gl<=0&&gr<=0)return false;
		if(gl>=1&&gr>=1){if(!java.awt.GraphicsEnvironment.isHeadless())try{java.awt.Toolkit.getDefaultToolkit().beep();}catch(RuntimeException ignored){}return true;}
		short[] tone=beepTone();Voice voice=new Voice(tone,tone,RATE,1);voice.gainLeft=gl;voice.gainRight=gr;get().start(voice);
		return true;
	}
	private static short[] beepTone;
	/** HyperZebra's own alert tone (not a Mac sound): 0.16 s of a 1046 Hz sine with a third partial, a soft attack, a quick decay and a short release. */
	static synchronized short[] beepTone(){
		if(beepTone==null){int n=(int)(RATE*0.16);short[] t=new short[n];
			for(int i=0;i<n;i++){double x=(double)i/RATE,env=Math.min(1,i/(RATE*0.004))*Math.min(1,(n-i)/(RATE*0.01))*Math.exp(-x*22);
				t[i]=(short)Math.round(24000*env*(0.8*Math.sin(2*Math.PI*1046.5*x)+0.2*Math.sin(2*Math.PI*3139.5*x)));}
			beepTone=t;}
		return beepTone;
	}

	private final List<Voice> voices=new ArrayList<Voice>();
	private final Object lock=new Object();
	private Thread thread;private volatile boolean failed;

	/** True once the output line could not be opened: voices end at once, nothing is played. */
	boolean failed(){return failed;}
	/** Voices being mixed now (paused ones included): a test seam for "nothing is left playing". */
	int voiceCount(){synchronized(lock){return voices.size();}}

	/** Decodes a stream to a voice (16-bit PCM at the stream's own rate); null when the stream cannot be read. */
	Voice decode(AudioInputStream stream,double rateMultiplier){
		try{
			AudioFormat f=stream.getFormat();int channels=Math.max(1,Math.min(2,f.getChannels()));
			AudioFormat pcm=new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,f.getSampleRate(),16,channels,channels*2,f.getSampleRate(),true);
			AudioInputStream in=pcm.matches(f)?stream:AudioSystem.getAudioInputStream(pcm,stream);
			byte[] bytes=in.readAllBytes();in.close();
			int frames=bytes.length/(channels*2);short[] left=new short[frames],right=channels==2?new short[frames]:left;
			for(int i=0,o=0;i<frames;i++){left[i]=(short)((bytes[o]&0xff)<<8|(bytes[o+1]&0xff));o+=2;if(channels==2){right[i]=(short)((bytes[o]&0xff)<<8|(bytes[o+1]&0xff));o+=2;}}
			return new Voice(left,right,f.getSampleRate(),rateMultiplier<=0?1:rateMultiplier);
		}catch(Exception e){return null;}
	}

	/** Starts mixing the voice; the output line is opened on first use (on this thread, once). */
	void start(Voice v){
		synchronized(lock){if(v.started||v.stopped)return;ensureThread();v.started=true;if(!failed){voices.add(v);lock.notifyAll();}}
		if(failed)v.finish();
	}
	/**
	 * Makes {@code next} (not started yet) follow {@code v} without a gap: when v's last frame has been mixed, the mixer
	 * starts next within the same chunk. The Sound Manager's queue otherwise starts the next bufferCmd only after v's end
	 * has gone through the emulation thread - iTunes streams 35 ms buffers and every one of them was followed by a
	 * 5-12 ms hole. False when v has already ended or has a successor; next must then be started the ordinary way.
	 */
	boolean link(Voice v,Voice next){
		synchronized(lock){if(v.ended||v.ranOut||v.stopped||v.next!=null||next.started||next.stopped||failed)return false;v.next=next;return true;}
	}
	/** Starts the voice linked behind {@code v} (on the mixer thread, v having just run out); null when there is none to start. */
	private Voice startSuccessor(Voice v){
		synchronized(lock){v.ranOut=true;Voice n=v.next;v.next=null;if(n==null||n.started||n.stopped)return null;n.started=true;voices.add(n);return n;}
	}
	/** Stops the voice for good: it leaves the mix at the next chunk and reports itself ended. */
	void stop(Voice v){v.stopped=true;v.paused=false;synchronized(lock){voices.remove(v);}v.finish();}
	/**
	 * Stops the voice like {@link #stop} but ramps it down over {@code outputFrames} first, so that cutting a sound off
	 * in the middle of its waveform does not click; nothing linked behind it starts. A voice that is not being mixed
	 * (not started, paused, already over) stops at once.
	 */
	void fadeOut(Voice v,int outputFrames){fadeOut(v,0,outputFrames);}
	/** The same after {@code holdFrames} more output frames at full gain. */
	void fadeOut(Voice v,int holdFrames,int outputFrames){
		synchronized(lock){
			if(outputFrames>0&&v.started&&!v.ended&&!v.stopped&&!v.paused&&!failed&&voices.contains(v)){v.next=null;v.fadeHoldRequest=Math.max(0,holdFrames);v.fadeRequest=outputFrames;return;}
		}
		stop(v);
	}

	/** Grace a closed stack's sounds get (full gain, then the fade): a closeStack "play bye" is heard, BGM ends within a second. */
	static final double RELEASE_HOLD_SECONDS=0.75,RELEASE_FADE_SECONDS=0.25;
	/**
	 * The stack that played this voice closed: it goes on for {@link #RELEASE_HOLD_SECONDS}, fades out over
	 * {@link #RELEASE_FADE_SECONDS} and ends (nothing linked behind it starts). One not being mixed stops at once.
	 */
	void release(Voice v){fadeOut(v,(int)(RELEASE_HOLD_SECONDS*RATE),(int)(RELEASE_FADE_SECONDS*RATE));}

	/**
	 * Mixes one chunk of a voice into {@code mix} (interleaved stereo accumulator) and returns whether the voice has
	 * frames left. A decoded voice plays its buffer once; a streaming one asks its {@link Source} for the next buffer
	 * whenever the position runs past what it last delivered. Also the test seam for the streaming path, which the
	 * mixer thread otherwise only reaches with an audio output open.
	 */
	boolean mixVoice(Voice v,int[] mix,int chunkFrames){return mixRange(v,mix,0,chunkFrames)<0;}
	/**
	 * Mixes the voice into frames {@code from}..{@code to}-1 of the chunk. Returns -1 while the voice has frames left,
	 * else the chunk frame at which it ran out (a linked successor carries on from there).
	 */
	private int mixRange(Voice v,int[] mix,int from,int to){
		double step=v.sourceRate*v.rateMultiplier/RATE,pos=v.position;short[] l=v.left,r=v.right;
		int n=Math.min(v.available,Math.min(l==null?0:l.length,r==null?0:r.length)); // never past the buffers (an empty one ended the mixer thread: AOL Benny Puckett)
		float gl=v.gainLeft,gr=v.gainRight;int end=-1;
		int req=v.fadeRequest;if(req>0){v.fadeRequest=0;if(v.fadeTotal==0){v.fadeHold=v.fadeHoldRequest;v.fadeTotal=v.fadeLeft=req;}}
		for(int i=from;i<to;i++,pos+=step){
			if(v.fadeHold>0)v.fadeHold--; // the grace before the fade: full gain
			else if(v.fadeTotal>0){ // fading out: the gain falls linearly to 0, then the voice is over
				if(v.fadeLeft<=0){end=i;v.stopped=true;break;}
				float f=(float)v.fadeLeft/v.fadeTotal;v.fadeLeft--;
				gl=v.gainLeft*f;gr=v.gainRight*f;}
			if(pos>=n){ // a decoded voice is over; a streaming one asks its source for the next buffer
				if(v.source==null){end=i;break;}
				int k;try{k=v.source.fill(l,r);}catch(RuntimeException e){k=0;}
				k=Math.min(k,Math.min(l.length,r.length));
				if(k<=0){n=0;end=i;break;}
				pos-=n;n=k;}
			int p=(int)pos;double frac=pos-p;int p1=p+1<n?p+1:p;
			int sl=(int)(l[p]+(l[p1]-l[p])*frac),sr=(int)(r[p]+(r[p1]-r[p])*frac);
			mix[i*2]+=(int)(sl*gl);mix[i*2+1]+=(int)(sr*gr);}
		// A streaming voice is over only when its source says so: landing exactly on the end of a buffer just means
		// the next chunk refills it.
		if(end<0&&v.source==null&&pos>=n)end=to;
		if(end<0&&v.fadeTotal>0&&v.fadeHold<=0&&v.fadeLeft<=0){end=to;v.stopped=true;}
		v.position=pos;v.available=n;
		if(end>=0&&v.source==null&&v.next!=null)v.next.position=Math.max(0,(pos-n)/step)*(v.next.sourceRate*v.next.rateMultiplier/RATE); // the overshoot, in the successor's frames
		return end;
	}

	/**
	 * Mixes one chunk of a voice and, when it runs out, of the voices linked behind it from the frame where it ended
	 * (they start then); every voice that ran out is added to {@code done}. The mixer thread's step, and a test seam.
	 */
	void mixChain(Voice v,int[] mix,int chunkFrames,List<Voice> done){
		Voice cur=v;int from=0;
		while(true){
			int end=mixRange(cur,mix,from,chunkFrames);
			if(end<0){
				if(cur.source==null&&!cur.nearlyEnded){double step=cur.sourceRate*cur.rateMultiplier/RATE;if(step>0&&(cur.available-cur.position)/step<=NEAR_END_FRAMES)cur.nearlyFinished();}
				return;}
			done.add(cur);cur=startSuccessor(cur);from=end;
			if(cur==null||cur.paused||from>=chunkFrames)return; // (a successor starting at the chunk's end goes on in the next one)
		}
	}

	private void ensureThread(){
		if(thread!=null||failed)return;
		thread=new Thread(new Runnable(){@Override public void run(){pump();}},"68K sound mixer");thread.setDaemon(true);thread.setPriority(Thread.MAX_PRIORITY-1);thread.start();
	}

	/** Debug: -Dhz68k.soundcapture=FILE appends everything mixed (44.1 kHz 16-bit big-endian stereo, raw) to FILE. */
	private java.io.OutputStream capture;private boolean captureTried;
	private void capture(byte[] out){
		if(!captureTried){captureTried=true;String f=System.getProperty("hz68k.soundcapture");
			if(f!=null)try{capture=new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));Runtime.getRuntime().addShutdownHook(new Thread(()->{try{capture.flush();}catch(java.io.IOException ignored){}}));}catch(java.io.IOException e){System.err.println("[68K] sound capture: "+e);}}
		if(capture!=null)try{capture.write(out);}catch(java.io.IOException e){capture=null;}
	}

	private void pump(){
		AudioFormat format=new AudioFormat(RATE,16,2,true,true);SourceDataLine line=null;
		// the JDK's port mixers are not made safely when another thread opens a line at the same time (an NPE in PortMixer.<init>,
		// 美樹の家計簿): try again before giving up, and never leave the voices waiting for a mixer that died
		for(int attempt=1;line==null;attempt++){
			try{line=AudioSystem.getSourceDataLine(format);line.open(format,CHUNK_FRAMES*4*4);line.start();}
			catch(LineUnavailableException|RuntimeException e){
				line=null;
				if(attempt<3&&!(e instanceof LineUnavailableException)&&!(e instanceof SecurityException)){try{Thread.sleep(50);}catch(InterruptedException ie){}continue;}
				failed=true;List<Voice> pending;synchronized(lock){pending=new ArrayList<Voice>(voices);voices.clear();}for(Voice v:pending)v.finish();return;
			}
		}
		int[] mix=new int[CHUNK_FRAMES*2];byte[] out=new byte[CHUNK_FRAMES*4];List<Voice> done=new ArrayList<Voice>();Voice[] active=new Voice[0];
		long idleSince=0;
		while(true){
			synchronized(lock){active=voices.toArray(active.length==voices.size()?active:new Voice[voices.size()]);}
			java.util.Arrays.fill(mix,0);boolean any=false;
			for(Voice v:active){
				if(v==null)break;
				if(v.paused||v.stopped)continue;
				any=true;
				mixChain(v,mix,CHUNK_FRAMES,done);
			}
			int volume=outputVolume,gl=Math.min(0x100,volume&0xffff),gr=Math.min(0x100,volume>>>16); // the output volume (setSoundVol)
			if(gl<0x100||gr<0x100)for(int i=0;i<mix.length;i+=2){mix[i]=mix[i]*gl>>8;mix[i+1]=mix[i+1]*gr>>8;}
			for(int i=0;i<mix.length;i++){int s=mix[i];if(s>32767)s=32767;else if(s<-32768)s=-32768;out[i*2]=(byte)(s>>8);out[i*2+1]=(byte)s;}
			if(!done.isEmpty()){synchronized(lock){voices.removeAll(done);}for(Voice v:done)v.finish();done.clear();}
			if(any){idleSince=0;line.write(out,0,out.length);capture(out);}
			else{ // silence: keep the line primed a little, then just wait for work (write() would block on a full buffer anyway)
				if(idleSince==0)idleSince=System.nanoTime();
				if(System.nanoTime()-idleSince<200_000_000L)line.write(out,0,out.length);
				else synchronized(lock){try{lock.wait(50);}catch(InterruptedException e){return;}}
			}
		}
	}
}

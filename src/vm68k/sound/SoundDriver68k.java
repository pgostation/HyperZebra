import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The classic Sound Driver (.Sound, refNum -4) of the 128K-ROM Macs, which programs of the 1980s drive through the
 * StartSound/StopSound/SoundDone glue: a synthesizer record is written to the driver (mostly asynchronously, the glue
 * keeps the parameter block in the SoundGlue global $AE8 and polls its ioResult), KillIO stops it. The three synthesizers
 * of Inside Macintosh II-223: free-form (ffMode 0: 8-bit samples at count x 22254 Hz), square-wave (swMode -1: tone
 * triplets of count = 783360/frequency, amplitude, duration in ticks) and four-tone (ftMode 1: four 256-byte waves
 * cycled at their Fixed rates for a duration in ticks). Requests play one after the other, as the driver queued them.
 * Some games play title music with free-form records and wait for each through SoundDone.
 */
final class SoundDriver68k {
	static final int REF_NUM=-4;
	/** Sample rate of the Macintosh sound hardware the driver's counts refer to. */
	static final double RATE=22254.54;
	private static final int ABORT_ERR=-27,READ_ERR=-19,STATUS_ERR=-18,CONTROL_ERR=-17;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Parameter blocks written to the driver and not yet completed, in order; the head is playing. */
	private final Deque<Integer> queue=new ArrayDeque<Integer>();
	private SoundMixer68k.Voice playing;
	private int playingBlock;

	SoundDriver68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** Read/Write/Control/Status/KillIO on the driver; the parameter block's ioResult and completion follow the Device Manager rules. */
	boolean handle(M68kCpu cpu,int pb,int trap,boolean async) throws M68kException {
		tb.api.soundManager().syncSdVolume(); // a level poked into SdVolume since is the output volume now
		switch(trap){
			case 0x03:return write(cpu,pb,async);
			case 0x06:killIo(cpu);tb.files.finish(cpu,pb,0,async);return true;
			case 0x04:{ // csCode 2 sets the speaker volume (SdVolume, 0-7): Myst Preview's xSetSoundVol does PBControl(-4, 2, level) and
				// xGetSoundVol reads SdVolume back for the Volume menu's check mark; other control calls are accepted and ignored.
				// It is the volume of all sound (SoundMixer68k.outputVolume), not only this driver's: SetVolume XCMD's "SetVolume 3; beep"
				if(memory.readWord(pb+26)==2)tb.api.soundManager().setSoundVol(memory.readWord(pb+28)&7);
				tb.files.finish(cpu,pb,0,async);return true;}
			case 0x05:tb.files.finish(cpu,pb,STATUS_ERR,async);return true;
			default:tb.files.finish(cpu,pb,READ_ERR,async);return true;
		}
	}

	private boolean write(M68kCpu cpu,int pb,boolean async) throws M68kException {
		int buffer=memory.readLong(pb+32),count=memory.readLong(pb+36);
		memory.writeLong(pb+40,count);
		if(Mac68kApi.TRACE)System.err.println("[68K] .Sound Write mode "+(buffer==0||count<2?"?":Integer.toString((short)memory.readWord(buffer)))+" bytes "+count+(async?" async":"")+" pb $"+Integer.toHexString(pb)+" buffer $"+Integer.toHexString(buffer));
		if(buffer==0||count<2){tb.files.finish(cpu,pb,CONTROL_ERR,async);return true;}
		memory.writeWord(pb+16,1);cpu.d[0]=cpu.d[0]&0xffff0000; // ioResult 1: in progress
		queue.add(pb);
		if(playing==null)startNext(cpu);
		if(!async){while(queue.contains(pb)&&playing!=null){try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}tb.runDeferred();}
			cpu.d[0]=(cpu.d[0]&0xffff0000)|(memory.readWord(pb+16)&0xffff);}
		return true;
	}

	/** Plays the request at the head of the queue; a record that makes no sound completes at once. */
	private void startNext(M68kCpu cpu) throws M68kException {
		while(!queue.isEmpty()){
			int pb=queue.peekFirst();
			SoundMixer68k.Voice voice=null;
			try{voice=voiceFor(memory.readLong(pb+32),memory.readLong(pb+36));}catch(M68kCpuException e){voice=null;}
			if(voice==null||!TSound.use){queue.pollFirst();complete(cpu,pb,0);continue;}
			playing=voice;playingBlock=pb;
			final int block=pb;final SoundMixer68k.Voice v=voice;
			voice.onEnd(new Runnable(){@Override public void run(){tb.later(new Runnable(){@Override public void run(){try{ended(v,block);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});}});
			SoundMixer68k.get().start(voice);
			return;
		}
	}
	/** The mixer finished (or KillIO stopped) the head request: its completion routine runs, then the next request starts. */
	private void ended(SoundMixer68k.Voice voice,int pb) throws M68kException {
		if(playing!=voice)return; // stopped by KillIO, which completed it already
		playing=null;playingBlock=0;queue.remove(pb);
		complete(tb.cpu,pb,0);
		startNext(tb.cpu);
	}
	private void complete(M68kCpu cpu,int pb,int result) throws M68kException {
		memory.writeWord(pb+16,result);
		tb.files.completion(cpu,pb);
	}
	/** KillIO (StopSound): the playing sound stops and every queued request completes with abortErr. */
	private void killIo(M68kCpu cpu) throws M68kException {
		SoundMixer68k.Voice voice=playing;playing=null;playingBlock=0;
		if(voice!=null)SoundMixer68k.get().stop(voice);
		Integer pb;while((pb=queue.pollFirst())!=null)complete(cpu,pb,ABORT_ERR);
	}
	/** Whether a sound is playing (the driver's ioResult stays 1 while it does). */
	boolean busy(){return playing!=null;}
	/** Application exit: the sound playing stops and the queued requests are dropped (no completion routines run any more). */
	void shutDown(){shutDown(false);}
	/** With release the sound playing gets the closed stack's grace (SoundMixer68k.release). */
	void shutDown(boolean release){SoundMixer68k.Voice voice=playing;playing=null;playingBlock=0;queue.clear();if(voice!=null){if(release&&voice.source==null)SoundMixer68k.get().release(voice);else SoundMixer68k.get().stop(voice);}}

	// ------------------------------------------------------------------ synthesizers


	private SoundMixer68k.Voice voiceFor(int record,int length) throws M68kCpuException {
		int mode=(short)memory.readWord(record);
		short[] pcm;
		switch(mode){
			case 0:{ // ffMode: count (Fixed) x 22254 Hz, then unsigned 8-bit samples
				double count=Integer.toUnsignedLong(memory.readLong(record+2))/65536.0;int samples=length-6;
				if(samples<=0)return null;pcm=new short[samples];
				for(int i=0;i<samples;i++)pcm[i]=(short)((memory.readByte(record+6+i)-128)<<8);
				return voice(pcm,RATE*(count<=0?1:count));}
			case -1:return squareWave(record+2,length-2);
			case 1:return fourTone(memory.readLong(record+2));
			default:return null;
		}
	}
	private SoundMixer68k.Voice voice(short[] pcm,double rate){
		SoundMixer68k.Voice v=new SoundMixer68k.Voice(pcm,pcm,rate,1);
		return v; // SdVolume is the mixer's output volume (SoundMixer68k.setSoundVol), not a gain of each voice
	}
	/** Square-wave synthesizer: Tone triplets (count, amplitude, duration) until a count of 0. */
	private SoundMixer68k.Voice squareWave(int tones,int length) throws M68kCpuException {
		int rate=(int)RATE;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(int at=tones;at+6<=tones+length;at+=6){
			int count=memory.readWord(at),amplitude=memory.readWord(at+2)&0xff,duration=memory.readWord(at+4);
			if(count==0)break;
			int frames=(int)Math.min(rate*60L,duration*rate/60L);double frequency=783360.0/count;int half=Math.max(1,(int)(rate/frequency/2));
			int level=amplitude*32767/255/3;
			for(int i=0;i<frames;i++){int s=(i/half&1)==0?level:-level;out.write(s>>8);out.write(s);}
		}
		byte[] bytes=out.toByteArray();if(bytes.length==0)return null;
		short[] pcm=new short[bytes.length/2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((bytes[i*2]&0xff)<<8|(bytes[i*2+1]&0xff));
		return voice(pcm,rate);
	}
	/**
	 * Four-tone synthesizer: the FTSoundRec's four 256-byte waves, each cycled at its Fixed rate (wave bytes per
	 * 1/22254 s) from its phase (a byte offset into the wave), summed for duration ticks.
	 * <p>
	 * The record is re-read while it plays, as the driver's interrupt task does: a program writes ONE long request and
	 * then changes sound1Rate..sound4Rate in place to play a melody (Space Quest 1 writes a single 52-byte record for the
	 * whole tune). Reading it once gave that one held tone - a long beep instead of music. The running phases stay here,
	 * so a new rate continues the wave where it was, and only the rates, the wave pointers and their tables come from the
	 * record again. The duration is the one the request was written with; StopSound (KillIO) ends it earlier.
	 */
	private SoundMixer68k.Voice fourTone(final int rec) throws M68kCpuException {
		if(rec==0)return null;int duration=memory.readWord(rec);final int rate=(int)RATE;
		final long total=Math.min(rate*600L,(long)duration*rate/60L);if(total<=0)return null;
		final long[] phase=new long[4];boolean any=false;
		for(int t=0;t<4;t++){phase[t]=(memory.readLong(rec+6+t*8)&0xffL)<<16;if(memory.readLong(rec+34+t*4)!=0&&memory.readLong(rec+2+t*8)!=0)any=true;}
		if(!any)return null;
		final short[] pcm=new short[FOUR_TONE_CHUNK];
		SoundMixer68k.Source source=new SoundMixer68k.Source(){
			private long produced;private double dcIn,dcOut;
			private final byte[][] tables=new byte[4][];
			@Override public int fill(short[] left,short[] right){
				int frames=(int)Math.min(pcm.length,total-produced);if(frames<=0)return 0;
				long[] step=new long[4];
				try{
					for(int t=0;t<4;t++){
						step[t]=Integer.toUnsignedLong(memory.readLong(rec+2+t*8));
						int wave=memory.readLong(rec+34+t*4);
						if(wave==0||step[t]==0){tables[t]=null;continue;}
						byte[] table=tables[t]==null?new byte[256]:tables[t];
						for(int i=0;i<256;i++)table[i]=(byte)memory.readByte(wave+i);
						tables[t]=table;}
				}catch(M68kCpuException e){return 0;} // the record went away: the sound stops, as it would on a dead pointer
				for(int i=0;i<frames;i++){int sum=0;
					for(int t=0;t<4;t++){if(tables[t]==null)continue;sum+=(tables[t][(int)(phase[t]>>>16)&0xff]&0xff)-128;phase[t]+=step[t];}
					// The Macintosh drives its speaker through a capacitor, so the direct component of the summed waves never
					// reaches it. Space Quest 1's tone wave is a short spike that falls to 0 and stays there for the rest of
					// the cycle, so its average is far from the middle: rendered as it stands, every sounding voice added
					// three quarters of full scale of silent offset, which buried the tones, clipped the mix and turned each
					// voice starting or stopping into a step. One-pole high pass at ~20 Hz, as the hardware does it.
					double x=sum/512.0;dcOut=x-dcIn+DC_POLE*dcOut;dcIn=x;
					int value=(int)(dcOut*32767);pcm[i]=(short)(value>32767?32767:value<-32768?-32768:value);}
				produced+=frames;return frames;
			}
		};
		SoundMixer68k.Voice v=new SoundMixer68k.Voice(pcm,pcm,rate,1,source);
		return v; // SdVolume is the mixer's output volume (SoundMixer68k.setSoundVol), not a gain of each voice
	}
	/** Pole of the direct-component high pass on the synthesized output (~20 Hz at 22254 Hz), standing in for the
	 *  capacitor between the Macintosh's sound hardware and its speaker. */
	private static final double DC_POLE=1-2*Math.PI*20/RATE;

	/** Frames the four-tone synthesizer produces per refill (~12 ms): short enough that a rate change is heard at once. */
	private static final int FOUR_TONE_CHUNK=256;
}

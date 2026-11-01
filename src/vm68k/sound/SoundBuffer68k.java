/**
 * The compact Macintosh's sound buffer (SoundBase, $266) as a DAC: 370 words, one unsigned 8-bit sample in the high byte of
 * each, read out once per video frame (22254.5 Hz on a Mac Plus), sound off while VIA1 port B bit 7 is set, volume in VIA1
 * port A bits 0-2. MacinTalk 1's .SPEECH driver (shipped inside HyperCard stacks with the MacinTalkSay XCMD) synthesises
 * straight into it: words 250-369, wrap, words 0-119, wait for the next tick (the low byte of Ticks), words 120-369, ...
 *
 * <p>The real hardware reads the buffer on its own clock and relies on the program's timing to stay ahead of it - at host
 * speed that timing is gone (MacinTalk overwrote words it had not yet been played). So the buffer is read in the order the
 * program fills it instead: every slot written since the reader last passed it is "full", and the reader takes full slots
 * in buffer order, waiting at the first empty one. A program writing words in pairs back to front (MacinTalk's 2x path
 * writes w+1 before w) comes out in order. The samples go through a FIFO to a streaming mixer voice at 370 samples per tick
 * (22200 Hz: the program's frame is the emulator's tick, so a writer synchronised to Ticks and the playback agree), and
 * {@link #pace} makes a writer that runs ahead wait at the scheduler poll, so a program that writes ahead plays in real time.
 *
 * <p>Programs that clear or scribble the buffer are unaffected: nothing plays while the sound is off (the ROM turns it off
 * at start-up; set here likewise), and a stream starts only at a sample that differs from the one before it, ending after
 * half a second of one value - a game that writes silence every loop is never paced. Through an AC-coupled output, as on
 * the real machine: a one-pole high-pass filter removes the DC level, so starting, stopping and a buffer left at 0 do not click.
 *
 * <p>Not emulated: the alternate sound buffer (port A bit 3), the disk-speed PWM in the low bytes.
 */
final class SoundBuffer68k {
	/** Samples per frame. */
	static final int WORDS=370;
	/** Bytes taken in the VM (the words plus room up to a round size, as the old dummy buffer had). */
	static final int BYTES=0x400;
	/** Playback rate: one buffer per emulator tick. */
	static final double RATE=WORDS*1e9/Toolbox68k.TICK_NANOS;
	private static final int FIFO=1<<15;
	/** Samples gathered before playing starts (and again after an underrun): two frames, 33 ms of jitter cushion. */
	static final int START_SAMPLES=2*WORDS;
	/** A writer more than this far ahead of the playback waits at the poll until it is back to {@link #TARGET_SAMPLES}. */
	static final int HIGH_SAMPLES=6*WORDS,TARGET_SAMPLES=4*WORDS;
	/** One value for this long ends a stream (nothing audible is being made). */
	static final int QUIET_SAMPLES=(int)(RATE/2);
	/** No write for this long: the next write starts a new stream at its own slot. */
	private static final long RESYNC_NANOS=100_000_000L;
	/** The high-pass filter's pole: about 20 Hz at 22200 Hz. */
	private static final double POLE=1-2*Math.PI*20/RATE;

	/** The memory's bytes, fetched per use: a launch into this program's address space replaces them (M68kMemory.launchPartition). */
	private final java.util.function.Supplier<byte[]> raw;private final int base;
	private final boolean[] full=new boolean[WORDS];
	private int reader;private boolean synced,fresh;
	/** The last sample read, and how many reads in a row had that value. */
	private int last=0x80,constantRun;
	/** The program is making sound: samples go to the FIFO. Written by the emulation thread, read by the mixer's. */
	private volatile boolean streaming;
	private boolean soundOn;private float gain=1f;
	private final byte[] fifo=new byte[FIFO];
	/** Samples produced (emulation thread) and consumed (mixer thread): a single-producer, single-consumer ring. */
	private volatile long head,tail;
	private SoundMixer68k.Voice voice;
	private int writes,writesAtPoll;private long headAtPoll,lastWriteNanos;
	/** The mixer to play through; null for a test that reads the FIFO itself. */
	private final SoundMixer68k mixer;

	SoundBuffer68k(M68kMemory memory,int base,SoundMixer68k mixer){this(memory::raw,base,mixer);}
	SoundBuffer68k(byte[] raw,int base,SoundMixer68k mixer){this(()->raw,base,mixer);}
	private SoundBuffer68k(java.util.function.Supplier<byte[]> raw,int base,SoundMixer68k mixer){this.raw=raw;this.base=base;this.mixer=mixer;}

	/** VIA1 port B (bit 7 set = sound off) and port A (bits 0-2 = volume) as the program last wrote them. */
	void setPorts(int portB,int portA){
		soundOn=(portB&0x80)==0;
		gain=((portA&7)+1)/8f;
		if(!soundOn){streaming=false;preroll=0;}
		SoundMixer68k.Voice v=voice;if(v!=null){v.gainLeft=gain;v.gainRight=gain;}
	}

	/** A byte at {@code offset} into the buffer was written (its new value is in memory already). */
	void written(int offset){
		if((offset&1)!=0||offset>=2*WORDS)return; // the low bytes are the disk motor's
		int i=offset>>1;writes++;
		// a new stream starts at the slot written first - or at the one before it when that comes next: written back to
		// front in pairs, the first slot of the first pair would otherwise be read a whole buffer late, on every pass
		if(!synced){synced=true;fresh=true;reader=i;java.util.Arrays.fill(full,false);full[i]=true;return;}
		if(fresh){fresh=false;if(i==(reader==0?WORDS-1:reader-1))reader=i;}
		full[i]=true;
		while(full[reader]){
			full[reader]=false;sample(raw.get()[base+2*reader]&0xff);
			reader=reader+1==WORDS?0:reader+1;
		}
	}

	private void sample(int u){
		boolean same=u==last;constantRun=same?constantRun+1:0;
		if(!soundOn){last=u;preroll=0;return;}
		if(!streaming){
			// a stream starts at the second change within two buffers: one step is a clear to another value, not sound. The
			// samples from the first change on are kept, and the level before it is where the voice starts from.
			if(preroll==0){if(!same){level=last;prerollSamples[preroll++]=(byte)u;}last=u;return;}
			if(same||preroll+1<prerollSamples.length){prerollSamples[preroll++]=(byte)u;last=u;if(!same)start();else if(preroll==prerollSamples.length)preroll=0;return;}
			preroll=0;level=last;prerollSamples[preroll++]=(byte)u;last=u;return; // changed just at the window's end: a new first change
		}
		if(constantRun>=QUIET_SAMPLES){streaming=false;last=u;return;}
		last=u;push(u);
	}
	private void push(int u){
		long h=head;if(h-tail>=FIFO)return; // (the poll keeps the writer far from this)
		fifo[(int)(h&(FIFO-1))]=(byte)u;head=h+1;
	}
	/** Samples since the first change of a stream that has not started yet, and the level before that change. */
	private final byte[] prerollSamples=new byte[2*WORDS];private int preroll,level=0x80;

	private void start(){
		streaming=true;
		int n=preroll;preroll=0;
		if(voice==null||voice.ended){tail=head;startVoice();} // (a stream still draining carries on)
		for(int i=0;i<n;i++)push(prerollSamples[i]&0xff);
	}
	private void startVoice(){
		voice=null;
		if(mixer==null||mixer.failed())return;
		short[] buffer=new short[256];
		SoundMixer68k.Voice v=new SoundMixer68k.Voice(buffer,buffer,RATE,1,newOutput(level));
		v.gainLeft=gain;v.gainRight=gain;
		voice=v;mixer.start(v);
	}
	/** The mixer's side of a stream that starts from {@code level} (unsigned 8-bit); also the test seam. */
	SoundMixer68k.Source newOutput(int level){return new Output(level-128);}

	/**
	 * From the scheduler poll: keeps a writer that runs ahead of the playback at most {@link #HIGH_SAMPLES} ahead (parks
	 * the thread until the FIFO is down to {@link #TARGET_SAMPLES}) and returns whether samples were made since the last
	 * poll - sound the user hears, so the throttle and the runaway-loop limit count it as activity.
	 */
	boolean pace(){
		long now=System.nanoTime();
		if(writes!=writesAtPoll){writesAtPoll=writes;lastWriteNanos=now;}
		else if(synced&&now-lastWriteNanos>RESYNC_NANOS){synced=false;streaming=false;} // the writer stopped: what is left plays out
		boolean made=head!=headAtPoll;headAtPoll=head;
		SoundMixer68k.Voice v=voice;
		if(v!=null&&v.ended){voice=null;v=null;if(!streaming)tail=head;}
		if(v==null&&streaming){startVoice();v=voice;} // the voice ran out under a stream that goes on (or it started just as the last one ended)
		if(v!=null){
			long limit=now+100_000_000L;
			if(head-tail>HIGH_SAMPLES)
				while(head-tail>TARGET_SAMPLES&&!v.ended&&System.nanoTime()<limit)java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
		}
		else if(mixer!=null&&mixer.failed())tail=head; // no audio output: nothing to wait for
		return made;
	}

	/** Samples waiting to be played (for tests). */
	int backlog(){return (int)(head-tail);}
	boolean streaming(){return streaming;}
	/** Takes the next sample off the FIFO, -1 when empty (for tests without a mixer). */
	int take(){long t=tail;if(t>=head)return -1;int u=fifo[(int)(t&(FIFO-1))]&0xff;tail=t+1;return u;}

	void stop(){streaming=false;SoundMixer68k.Voice v=voice;voice=null;if(v!=null&&mixer!=null)mixer.stop(v);}

	/** The mixer's side: FIFO -> high-pass filter -> 16-bit samples, holding the level through gaps. */
	private final class Output implements SoundMixer68k.Source {
		private boolean buffering=true;
		private int hold;private double x1,y;private int quiet,underruns;
		Output(int level){hold=level;x1=level;}
		@Override public int fill(short[] left,short[] right){
			int n=left.length,i=0;
			for(;i<n;i++){
				long h=head,t=tail;
				if(buffering&&(h-t>=START_SAMPLES||!streaming))buffering=false;
				int x;
				if(!buffering&&t<h){x=(fifo[(int)(t&(FIFO-1))]&0xff)-128;tail=t+1;quiet=0;}
				else{
					x=hold;quiet++;
					if(!buffering&&streaming){buffering=true;underruns++;}                    // ran dry: gather a cushion again
					if(!streaming&&quiet>RATE/20&&Math.abs(y)<1||quiet>RATE/2)break;          // over (the filter has settled), or the writer is gone
				}
				hold=x;
				y=x-x1+POLE*y;x1=x;
				int s=(int)Math.round(y*256);left[i]=(short)Math.max(-32768,Math.min(32767,s));
			}
			if(i==0){if(MacIo68k.TRACE)System.err.println("[io] sound buffer stream played out, "+underruns+" underruns");}
			return i;
		}
	}
}

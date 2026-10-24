import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/**
 * The QuickTime Music Architecture of the native Movie Toolbox: the Note Allocator ('nota') and the Tune Player ('tune')
 * as Java components, playing through the Java synthesizer (General MIDI). An external sees QuickTime 4.0.3 in HyperCard's
 * Mac and opens them: UDI's UxPlayMIDI builds a tune header (one note request per part, the tones from
 * NAStuffToneDescription) and a note sequence, TuneSetHeader/TuneSetTimeScale/TuneQueue it, and its "idle" polls
 * TuneGetStatus until queueTime is 0; UxGetInst names instruments through NAStuffToneDescription (it skips names that are
 * empty or start with "("), UxPickInst asks with NAPickInstrument. Without these components every call answered
 * "Error : Can't open component" (doBGM card 5, slg2pmd's Play).
 * With the real QuickTime extension installed its own components serve instead (these are registered only without it).
 * Event decoding is QTMusicConverter's (the same as for 'musi' movie tracks); selector numbers and record layouts are
 * QuickTimeMusic.h's. The tune plays on a thread of its own, so it goes on while the external has returned; it stops with
 * TuneStop, when the instance closes, and when the VM shuts down (stack closed, program quit).
 */
final class QuickTimeMusic68k {
	static final int NOTA=0x6e6f7461,TUNE=0x74756e65,APPL=0x6170706c,SOFT_SYNTH=0x73732020; // 'ss  '
	static final int BAD_SELECTOR=0x80008002,PARAM_ERR=-50,USER_CANCELED=-128;
	static final int QUEUE_DEPTH=8; // kTuneQueueDepth
	static final int TUNE_DONT_CLIP=2,TUNE_EXCLUDE_EDGE=4,TUNE_LOOP_UNTIL=16; // TuneQueue flags
	static final int FIRST_DRUM_KIT=0x4000;

	/** General MIDI Level 1 instrument names, 1-128 (the GM sound set). */
	static final String[] GM_NAMES={
		"Acoustic Grand Piano","Bright Acoustic Piano","Electric Grand Piano","Honky-tonk Piano","Electric Piano 1","Electric Piano 2","Harpsichord","Clavi",
		"Celesta","Glockenspiel","Music Box","Vibraphone","Marimba","Xylophone","Tubular Bells","Dulcimer",
		"Drawbar Organ","Percussive Organ","Rock Organ","Church Organ","Reed Organ","Accordion","Harmonica","Tango Accordion",
		"Acoustic Guitar (nylon)","Acoustic Guitar (steel)","Electric Guitar (jazz)","Electric Guitar (clean)","Electric Guitar (muted)","Overdriven Guitar","Distortion Guitar","Guitar harmonics",
		"Acoustic Bass","Electric Bass (finger)","Electric Bass (pick)","Fretless Bass","Slap Bass 1","Slap Bass 2","Synth Bass 1","Synth Bass 2",
		"Violin","Viola","Cello","Contrabass","Tremolo Strings","Pizzicato Strings","Orchestral Harp","Timpani",
		"String Ensemble 1","String Ensemble 2","SynthStrings 1","SynthStrings 2","Choir Aahs","Voice Oohs","Synth Voice","Orchestra Hit",
		"Trumpet","Trombone","Tuba","Muted Trumpet","French Horn","Brass Section","SynthBrass 1","SynthBrass 2",
		"Soprano Sax","Alto Sax","Tenor Sax","Baritone Sax","Oboe","English Horn","Bassoon","Clarinet",
		"Piccolo","Flute","Recorder","Pan Flute","Blown Bottle","Shakuhachi","Whistle","Ocarina",
		"Lead 1 (square)","Lead 2 (sawtooth)","Lead 3 (calliope)","Lead 4 (chiff)","Lead 5 (charang)","Lead 6 (voice)","Lead 7 (fifths)","Lead 8 (bass + lead)",
		"Pad 1 (new age)","Pad 2 (warm)","Pad 3 (polysynth)","Pad 4 (choir)","Pad 5 (bowed)","Pad 6 (metallic)","Pad 7 (halo)","Pad 8 (sweep)",
		"FX 1 (rain)","FX 2 (soundtrack)","FX 3 (crystal)","FX 4 (atmosphere)","FX 5 (brightness)","FX 6 (goblins)","FX 7 (echoes)","FX 8 (sci-fi)",
		"Sitar","Banjo","Shamisen","Koto","Kalimba","Bag pipe","Fiddle","Shanai",
		"Tinkle Bell","Agogo","Steel Drums","Woodblock","Taiko Drum","Melodic Tom","Synth Drum","Reverse Cymbal",
		"Guitar Fret Noise","Breath Noise","Seashore","Bird Tweet","Telephone Ring","Helicopter","Applause","Gunshot"};
	/** The drum kits (GS program numbers on the drum channel): instrument 16384 + program + 1. */
	static final int[] DRUM_KIT_PROGRAMS={0,8,16,24,25,32,40,48,56};
	static final String[] DRUM_KIT_NAMES={"Standard Kit","Room Kit","Power Kit","Electronic Kit","Analog Kit","Jazz Kit","Brush Kit","Orchestra Kit","Sound FX Kit"};

	/** The name of an instrument number (1-128 GM, 16385.. drum kits), "" for one this synthesizer does not have. */
	static String instrumentName(int number){
		if(number>=1&&number<=128)return GM_NAMES[number-1];
		if(number>FIRST_DRUM_KIT&&number<=FIRST_DRUM_KIT+128)for(int i=0;i<DRUM_KIT_PROGRAMS.length;i++)if(number==FIRST_DRUM_KIT+1+DRUM_KIT_PROGRAMS[i])return DRUM_KIT_NAMES[i];
		return "";
	}

	// ------------------------------------------------------------------ the MIDI output (shared by every VM: one synthesizer)

	/** Tests: every message goes here instead of the synthesizer. */
	static volatile Receiver testReceiver;
	/** Tests: NAPickInstrument's answer (an instrument number, or -1 for Cancel) instead of the dialog. */
	static volatile Integer scriptedPick;
	private static Receiver synth;private static boolean synthFailed;
	private static synchronized Receiver output(){
		Receiver test=testReceiver;if(test!=null)return test;
		if(synth==null&&!synthFailed){
			try{synth=MidiHost68k.synthesizerReceiver();}
			catch(Exception|LinkageError e){synthFailed=true;if(Mac68kApi.TRACE)System.err.println("[qtma] no synthesizer: "+e);}
		}
		return synth;
	}
	static void send(int status,int channel,int data1,int data2){
		Receiver r=output();if(r==null)return;
		try{ShortMessage m=new ShortMessage();m.setMessage(status,channel&15,data1&0x7f,data2&0x7f);r.send(m,-1);}
		catch(InvalidMidiDataException|RuntimeException e){if(Mac68kApi.TRACE)System.err.println("[qtma] send failed: "+e);}
	}

	/** MIDI channels in use (reference counts): melodic parts share the 15 non-drum channels, drum kits channel 10. */
	private static final int[] channelUsers=new int[16];
	private static synchronized int allocateChannel(boolean drums){
		if(drums){channelUsers[9]++;return 9;}
		int best=-1;for(int c=0;c<16;c++)if(c!=9&&(best<0||channelUsers[c]<channelUsers[best]))best=c;
		channelUsers[best]++;return best;
	}
	private static synchronized void releaseChannel(int c){if(c>=0&&c<16&&channelUsers[c]>0)channelUsers[c]--;}

	// ------------------------------------------------------------------ note channels

	/** A NoteChannel: one part (one instrument) on a MIDI channel. Its value for the program is a small guest block. */
	final class NoteChannel {
		final int address;int channel=-1,instrument=1,partVolume=100; // partVolume: the MIDI volume the part's controllers set
		int volume=0x10000; // NASetNoteChannelVolume / the tune's volume and part mix (Fixed)
		int transpose,velocityShift;
		final int[] controllers=new int[128]; // last QuickTime controller values (NAGetController)
		final boolean[] sounding=new boolean[128];
		final int owner; // the Note Allocator or Tune Player instance that made it
		NoteChannel(int address,int owner){this.address=address;this.owner=owner;}
		void setInstrument(int number){
			boolean drums=number>FIRST_DRUM_KIT;
			if(channel<0||drums!=(channel==9)){if(channel>=0){silence();releaseChannel(channel);}channel=allocateChannel(drums);}
			instrument=number;
			int program=drums?Math.max(0,Math.min(127,number-FIRST_DRUM_KIT-1)):Math.max(0,Math.min(127,(number-1)&127));
			send(ShortMessage.CONTROL_CHANGE,channel,0,0);send(ShortMessage.CONTROL_CHANGE,channel,32,0);send(ShortMessage.PROGRAM_CHANGE,channel,program,0);
			send(ShortMessage.CONTROL_CHANGE,channel,121,0); // controllers back to their defaults, as a fresh QuickTime part
			applyVolume();
		}
		void applyVolume(){if(channel>=0)send(ShortMessage.CONTROL_CHANGE,channel,7,(int)Math.max(0,Math.min(127,Math.round(partVolume*(volume/65536.0)*(SoundMixer68k.gainLeft()+SoundMixer68k.gainRight())/2))));} // also the Mac's sound volume (SetSoundVol)
		void play(int pitch,int velocity){
			if(channel<0)return;
			if(pitch>=256)pitch=(pitch+0x80)>>8; // fractional pitch (8.8)
			pitch+=transpose;if(pitch<0||pitch>127)return;
			if(velocity<=0){if(sounding[pitch]){sounding[pitch]=false;send(ShortMessage.NOTE_OFF,channel,pitch,0);}return;}
			velocity=Math.max(1,Math.min(127,velocity+velocityShift));
			sounding[pitch]=true;send(ShortMessage.NOTE_ON,channel,pitch,velocity);
		}
		void control(int controller,int value){
			if(controller>=0&&controller<128)controllers[controller]=value;
			if(controller==7||controller==42){partVolume=Math.max(0,Math.min(127,value>>8));applyVolume();return;}
			if(controller==40){transpose=value>>8;return;} // part transpose (semitones, 8.8)
			if(channel<0)return;
			for(int[] m:QTMusicConverter.controlMessages(controller,value))send(m[0],channel,m[1],m[2]);
		}
		void silence(){
			if(channel<0)return;
			for(int p=0;p<128;p++)if(sounding[p]){sounding[p]=false;send(ShortMessage.NOTE_OFF,channel,p,0);}
			send(ShortMessage.CONTROL_CHANGE,channel,64,0);
		}
		void release(){silence();releaseChannel(channel);channel=-1;}
	}
	private final Map<Integer,NoteChannel> channels=new HashMap<Integer,NoteChannel>();
	private NoteChannel newChannel(int owner,int instrument) throws M68kException {
		int address=memory.allocate(16,4);memory.fill(address,16,0);
		NoteChannel nc=new NoteChannel(address,owner);channels.put(address,nc);nc.setInstrument(instrument);return nc;
	}
	private void disposeChannel(NoteChannel nc){if(nc==null)return;synchronized(nc){nc.release();}channels.remove(nc.address);memory.free(nc.address);}

	// ------------------------------------------------------------------ tune players

	/** A queued piece of a tune: its events decoded to MIDI at queue time, timed in the player's time units. */
	static final class Piece {
		final int tune,rate,flags,callBack,refCon;final long start,end;
		final List<Timed> events=new ArrayList<Timed>();
		long startNanos;int next; // when it began to sound, and the next event to send
		Piece(int tune,int rate,int flags,int callBack,int refCon,long start,long end){this.tune=tune;this.rate=rate;this.flags=flags;this.callBack=callBack;this.refCon=refCon;this.start=start;this.end=end;}
	}
	/** One timed action of a piece: a note on/off or a controller change of a part, at a tune time. */
	static final class Timed {final long time;final int part,kind,a,b,offset;Timed(long time,int part,int kind,int a,int b,int offset){this.time=time;this.part=part;this.kind=kind;this.a=a;this.b=b;this.offset=offset;}}
	static final int NOTE_ON=1,NOTE_OFF=2,CONTROL=3;

	/** One Tune Player instance: its parts (from the header), time scale, volume and queue, and the thread that plays it. */
	final class Tune implements Runnable {
		final int instance;int timeScale=600,volume=0x10000,softer,balance;
		final Map<Integer,NoteChannel> parts=new HashMap<Integer,NoteChannel>(); // by part number
		final List<NoteChannel> order=new ArrayList<NoteChannel>(); // TuneGetIndexedNoteChannel
		final Map<Integer,int[]> mix=new HashMap<Integer,int[]>(); // TuneSetPartMix: {volume Fixed, balance, flags}
		final ArrayDeque<Piece> queue=new ArrayDeque<Piece>();
		final List<Piece> finished=new ArrayList<Piece>(); // pieces whose callback the VM thread still has to call
		Thread thread;boolean closed;
		int noteAllocator; // TuneGetNoteAllocator's instance
		Tune(int instance){this.instance=instance;}

		/** The tune time a piece has reached (its start position plus the elapsed time at its rate). */
		long position(Piece p,long now){
			if(p.startNanos==0)return p.start;
			double rate=p.rate==0?1:p.rate/65536.0;
			return p.start+(long)((now-p.startNanos)/1e9*timeScale*rate);
		}
		long nanosFor(Piece p,long units){double rate=p.rate==0?1:Math.abs(p.rate/65536.0);return (long)(units*1e9/Math.max(1,timeScale)/rate);}
		synchronized void enqueue(Piece p){
			queue.add(p);
			if(thread==null&&!closed){thread=new Thread(this,"QuickTime music");thread.setDaemon(true);thread.start();}
			else notifyAll();
		}
		@Override public void run(){
			try{
				synchronized(this){
					while(!closed&&!queue.isEmpty()){
						Piece p=queue.peek();long now=System.nanoTime();
						if(p.startNanos==0){p.startNanos=now;p.next=0;}
						long pos=position(p,now);
						while(p.next<p.events.size()&&p.events.get(p.next).time<=pos)perform(p.events.get(p.next++));
						if(pos>=p.end&&p.next>=p.events.size()){
							for(NoteChannel nc:parts.values())synchronized(nc){nc.silence();}
							if((p.flags&TUNE_LOOP_UNTIL)!=0&&queue.size()==1){p.startNanos=0;continue;} // loops while nothing else is queued
							queue.poll();if(p.callBack!=0)finished.add(p);
							continue;
						}
						long nextTime=p.next<p.events.size()?Math.min(p.end,p.events.get(p.next).time):p.end;
						long waitMillis=Math.max(1,Math.min(250,nanosFor(p,nextTime-pos)/1_000_000));
						wait(waitMillis);
					}
					thread=null;
				}
			}catch(InterruptedException e){synchronized(this){thread=null;}}
		}
		private void perform(Timed t){
			NoteChannel nc=parts.get(t.part);if(nc==null)nc=partChannel(t.part);
			synchronized(nc){
				if(t.kind==NOTE_ON)nc.play(t.a,t.b);else if(t.kind==NOTE_OFF)nc.play(t.a,0);else nc.control(t.a,t.b);
			}
		}
		/** A part the header did not announce: a piano of its own, as QuickTime gives an unknown part. */
		private NoteChannel partChannel(int part){
			NoteChannel nc=new NoteChannel(0,instance);nc.setInstrument(1);nc.volume=volume;nc.applyVolume();parts.put(part,nc);return nc;
		}
		synchronized void stop(){
			queue.clear();notifyAll();
			for(NoteChannel nc:parts.values())synchronized(nc){nc.silence();}
		}
		/** TuneGetStatus: the playing piece, where it is, and what is still queued (queueTime 0 = done: UxPlayMIDI's test). */
		synchronized long[] status(){
			Piece p=queue.peek();if(p==null)return new long[]{0,0,0,0,QUEUE_DEPTH,0};
			long now=System.nanoTime(),pos=Math.min(position(p,now),p.end);
			long remaining=Math.max(0,p.end-pos);boolean first=true;
			for(Piece q:queue){if(first){first=false;continue;}remaining+=q.end-q.start;}
			int at=p.next<p.events.size()?p.events.get(p.next).offset:p.events.isEmpty()?0:p.events.get(p.events.size()-1).offset;
			return new long[]{p.tune,p.tune+at,pos,queue.size(),Math.max(0,QUEUE_DEPTH-queue.size()),remaining};
		}
		synchronized void setVolume(int v){volume=v;for(NoteChannel nc:parts.values())synchronized(nc){int[] m=mix.get(partOf(nc));nc.volume=m==null?v:(int)((long)v*m[0]>>16);nc.applyVolume();}}
		int partOf(NoteChannel nc){for(Map.Entry<Integer,NoteChannel> e:parts.entrySet())if(e.getValue()==nc)return e.getKey();return -1;}
	}
	private final Map<Integer,Tune> tunes=new HashMap<Integer,Tune>();
	private final Map<Integer,List<NoteChannel>> allocatorChannels=new HashMap<Integer,List<NoteChannel>>();

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private Components68k.Component noteAllocatorComponent;
	QuickTimeMusic68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** Registers the Note Allocator and the Tune Player (the native Movie Toolbox only: the real extension brings its own). */
	static void register(Components68k components,Toolbox68k tb){
		QuickTimeMusic68k music=new QuickTimeMusic68k(tb);tb.quickTime.music=music;
		music.noteAllocatorComponent=components.registerNative(NOTA,0,APPL,0,new Components68k.Native(){
			@Override public int call(int ci,int what,int p) throws M68kException {return music.noteAllocator(ci,what,p);}
			@Override public void closed(int ci){music.closeAllocator(ci);}});
		components.registerNative(TUNE,0,APPL,0,new Components68k.Native(){
			@Override public int call(int ci,int what,int p) throws M68kException {return music.tunePlayer(ci,what,p);}
			@Override public void closed(int ci){music.closeTune(ci);}});
	}

	/** The VM ends (stack closed, program quit): every tune stops and its notes are silenced. */
	void shutDown(){
		for(Tune t:new ArrayList<Tune>(tunes.values()))closeTune(t.instance);
		for(NoteChannel nc:new ArrayList<NoteChannel>(channels.values()))synchronized(nc){nc.release();}
		channels.clear();allocatorChannels.clear();
	}
	/** Whether a tune is still playing (an external that queued one and returned keeps its VM). */
	boolean sounding(){for(Tune t:tunes.values())synchronized(t){if(!t.queue.isEmpty())return true;}return false;}
	/** Runs the TuneQueue callbacks of finished pieces (on the VM thread: from TuneTask, TuneGetStatus, NATask, MoviesTask). */
	void task() throws M68kException {
		for(Tune t:new ArrayList<Tune>(tunes.values())){
			List<Piece> done;synchronized(t){if(t.finished.isEmpty())continue;done=new ArrayList<Piece>(t.finished);t.finished.clear();}
			for(Piece p:done)callBack(t,p);
		}
	}
	private void callBack(Tune t,Piece p) throws M68kException {
		int status=memory.allocate(32,4);
		try{
			memory.fill(status,32,0);memory.writeWord(status+14,QUEUE_DEPTH);memory.writeLong(status,p.tune);memory.writeLong(status+8,(int)p.end);
			M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
			try{cpu.pushLong(status);cpu.pushLong(p.refCon);cpu.callPascal(p.callBack,8);} // pascal void (const TuneStatus*, long refCon)
			finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		}finally{memory.free(status);}
	}

	// ------------------------------------------------------------------ Note Allocator selectors ({@code p}: the last argument first)

	private NoteChannel channel(int address){return channels.get(address);}
	int noteAllocator(int ci,int what,int p) throws M68kException {
		switch(what){
			case 0x00:case 0x01:case 0x03:return 0; // NARegisterMusicDevice / NAUnregisterMusicDevice / NASaveMusicConfiguration
			case 0x02:{int index=memory.readLong(p+16),type=memory.readLong(p+12),name=memory.readLong(p+8),connections=memory.readLong(p+4),music=memory.readLong(p); // NAGetRegisteredMusicDevice(na, index, synthType*, Str31 name, connections*, MusicComponent*)
				if(index!=1)return PARAM_ERR;
				if(type!=0)memory.writeLong(type,SOFT_SYNTH);if(name!=0)tb.writePascal(name,"Java Synthesizer",31);
				if(connections!=0)memory.fill(connections,16,0);if(music!=0)memory.writeLong(music,0);return 0;}
			case 0x04:{int out=memory.readLong(p),request=memory.readLong(p+4); // NANewNoteChannel(na, NoteRequest*, NoteChannel*)
				if(out==0)return PARAM_ERR;
				NoteChannel nc=newChannel(ci,request==0?1:requestedInstrument(memory.readLong(request+8+72),memory.readLong(request+8+68)));
				allocatorChannels.computeIfAbsent(ci,k->new ArrayList<NoteChannel>()).add(nc);
				memory.writeLong(out,nc.address);return 0;}
			case 0x05:{NoteChannel nc=channel(memory.readLong(p));if(nc==null)return PARAM_ERR; // NADisposeNoteChannel(na, nc)
				List<NoteChannel> list=allocatorChannels.get(nc.owner);if(list!=null)list.remove(nc);disposeChannel(nc);return 0;}
			case 0x06:{int part=memory.readLong(p),index=memory.readLong(p+4);NoteChannel nc=channel(memory.readLong(p+8));if(nc==null)return PARAM_ERR; // NAGetNoteChannelInfo(na, nc, index*, part*)
				if(index!=0)memory.writeLong(index,1);if(part!=0)memory.writeLong(part,nc.channel+1);return 0;}
			case 0x07:case 0x08:return channel(memory.readLong(p))==null?PARAM_ERR:0; // NAPrerollNoteChannel / NAUnrollNoteChannel
			case 0x0b:{NoteChannel nc=channel(memory.readLong(p+4));if(nc==null)return PARAM_ERR;synchronized(nc){nc.volume=memory.readLong(p);nc.applyVolume();}return 0;} // NASetNoteChannelVolume(na, nc, Fixed)
			case 0x0c:{NoteChannel nc=channel(memory.readLong(p));if(nc==null)return PARAM_ERR;synchronized(nc){nc.silence();nc.transpose=0;nc.setInstrument(nc.instrument);}return 0;} // NAResetNoteChannel
			case 0x0d:{NoteChannel nc=channel(memory.readLong(p+8));if(nc==null)return PARAM_ERR;synchronized(nc){nc.play(memory.readLong(p+4),memory.readLong(p));}return 0;} // NAPlayNote(na, nc, pitch, velocity)
			case 0x0e:{NoteChannel nc=channel(memory.readLong(p+8));if(nc==null)return PARAM_ERR;synchronized(nc){nc.control(memory.readLong(p+4),memory.readLong(p));}return 0;} // NASetController(na, nc, controller, value)
			case 0x0f:return 0; // NASetKnob: no synthesizer knobs
			case 0x10:{int number=memory.readLong(p),tone=memory.readLong(p+4);NoteChannel nc=channel(memory.readLong(p+8));if(nc==null)return PARAM_ERR; // NAFindNoteChannelTone(na, nc, ToneDescription*, instrumentNumber*)
				if(tone!=0)stuffTone(tone,nc.instrument);if(number!=0)memory.writeLong(number,nc.instrument);return 0;}
			case 0x11:case 0x2b:{NoteChannel nc=channel(memory.readLong(p+4));if(nc==null)return PARAM_ERR;synchronized(nc){nc.setInstrument(requestedInstrument(memory.readLong(p),memory.readLong(p)));}return 0;} // NASetInstrumentNumber(na, nc, number) (+ interrupt safe)
			case 0x12:return pickInstrument(memory.readLong(p+20),memory.readLong(p+16)); // NAPickInstrument(na, filter, prompt, ToneDescription*, flags, refCon, reserved, reserved)
			case 0x13:return USER_CANCELED; // NAPickArrangement
			case 0x1b:{int tone=memory.readLong(p);if(tone==0)return PARAM_ERR;stuffTone(tone,memory.readLong(p+4));return 0;} // NAStuffToneDescription(na, gmNumber, ToneDescription*)
			case 0x1c:return 0; // NACopyrightDialog
			case 0x1f:{int seed=memory.readLong(p),out=memory.readLong(p+4),index=memory.readLong(p+8); // NAGetIndNoteChannel(na, index, NoteChannel*, seed*)
				List<NoteChannel> list=allocatorChannels.get(ci);NoteChannel nc=list==null||index<1||index>list.size()?null:list.get(index-1);
				if(out!=0)memory.writeLong(out,nc==null?0:nc.address);if(seed!=0)memory.writeLong(seed,list==null?0:list.size());return nc==null?PARAM_ERR:0;}
			case 0x21:{int out=memory.readLong(p),in=memory.readLong(p+4);if(in!=0)memory.writeLong(in,0);if(out!=0)memory.writeLong(out,0);return 0;} // NAGetMIDIPorts(na, in*, out*): none
			case 0x22:{int request=memory.readLong(p);NoteChannel nc=channel(memory.readLong(p+4));if(nc==null||request==0)return PARAM_ERR; // NAGetNoteRequest(na, nc, NoteRequest*)
				memory.fill(request,84,0);memory.writeWord(request+2,16);memory.writeLong(request+4,0x10000);stuffTone(request+8,nc.instrument);return 0;}
			case 0x23:{int packet=memory.readLong(p);NoteChannel nc=channel(memory.readLong(p+4));if(nc==null||packet==0)return PARAM_ERR; // NASendMIDI(na, nc, MusicMIDIPacket*): its channel's messages
				int length=memory.readWord(packet);byte[] data=memory.readBytes(packet+6,Math.min(249,length));
				for(int i=0;i<data.length;){int status=data[i]&0xff;if(status<0x80||status>=0xf0){i++;continue;}int n=status>=0xc0&&status<0xe0?2:3;
					if(i+n>data.length)break;send(status&0xf0,nc.channel,data[i+1],n>2?data[i+2]:0);i+=n;}
				return 0;}
			case 0x29:task();return 0; // NATask
			case 0x2a:{NoteChannel nc=channel(memory.readLong(p+4));if(nc==null)return PARAM_ERR;int balance=memory.readLong(p); // NASetNoteChannelBalance(na, nc, balance -128..127)
				synchronized(nc){if(nc.channel>=0)send(ShortMessage.CONTROL_CHANGE,nc.channel,10,Math.max(0,Math.min(127,64+balance/2)));}return 0;}
			case 0x2c:return 0; // NASetNoteChannelSoundLocalization
			case 0x2d:{int value=memory.readLong(p),controller=memory.readLong(p+4);NoteChannel nc=channel(memory.readLong(p+8));if(nc==null)return PARAM_ERR; // NAGetController(na, nc, controller, long*)
				if(value!=0)memory.writeLong(value,controller>=0&&controller<128?nc.controllers[controller]:0);return 0;}
			default:return BAD_SELECTOR;
		}
	}
	/** The instrument a request names: its GM number (1-128, drum kits), else its instrument number, else a piano. */
	private static int requestedInstrument(int gm,int instrument){
		if(gm>=1&&gm<=128||gm>FIRST_DRUM_KIT&&gm<=FIRST_DRUM_KIT+128)return gm;
		if(instrument>=1&&instrument<=128||instrument>FIRST_DRUM_KIT&&instrument<=FIRST_DRUM_KIT+128)return instrument;
		if(gm>128&&gm<FIRST_DRUM_KIT)return ((gm-1)&127)+1; // a GS variation: its GM capital tone
		return 1;
	}
	/** NAStuffToneDescription: the GM tone for that number, instrumentNumber = gmNumber (UxPickInst answers "number,name"). */
	private void stuffTone(int tone,int number) throws M68kException {
		memory.fill(tone,76,0);
		memory.writeLong(tone,0); // any synthesizer
		tb.writePascal(tone+36,instrumentName(number),31);
		memory.writeLong(tone+68,number);memory.writeLong(tone+72,number);
	}
	/** NAPickInstrument: a host list of the GM instruments and drum kits (numbered as QuickTime numbers them); Cancel = userCanceledErr. */
	private int pickInstrument(int prompt,int tone) throws M68kException {
		if(tone==0)return PARAM_ERR;
		int current=memory.readLong(tone+72);if(current==0)current=memory.readLong(tone+68);
		String message=prompt==0?"":tb.readPascal(prompt);
		Integer chosen=scriptedPick;
		if(chosen==null)chosen=java.awt.GraphicsEnvironment.isHeadless()?Integer.valueOf(current<1?1:current):askInstrument(message,current);
		if(chosen<0)return USER_CANCELED;
		stuffTone(tone,chosen);return 0;
	}
	private int askInstrument(String message,int current){
		List<Integer> numbers=new ArrayList<Integer>();List<String> labels=new ArrayList<String>();
		for(int i=1;i<=128;i++){numbers.add(i);labels.add(i+"  "+GM_NAMES[i-1]);}
		for(int i=0;i<DRUM_KIT_PROGRAMS.length;i++){numbers.add(FIRST_DRUM_KIT+1+DRUM_KIT_PROGRAMS[i]);labels.add((FIRST_DRUM_KIT+1+DRUM_KIT_PROGRAMS[i])+"  "+DRUM_KIT_NAMES[i]);}
		javax.swing.JComboBox<String> box=new javax.swing.JComboBox<String>(labels.toArray(new String[0]));
		int at=numbers.indexOf(current);box.setSelectedIndex(at<0?0:at);box.setMaximumRowCount(20);
		java.awt.Component parent=tb.app==null?PCARD.pc:null; // an external's dialog over the card window
		// the external's prompt is the title; the list holds instrument names only; the buttons are HyperZebra's own Cancel/OK
		// (JOptionPane's OK_CANCEL_OPTION labels them with the JDK's texts, 取消 in Japanese), OK the default as on a Mac
		String cancel=International.t("Cancel"),ok="OK";
		int answer=javax.swing.JOptionPane.showOptionDialog(parent,box,message.isEmpty()?"QuickTime":message,javax.swing.JOptionPane.DEFAULT_OPTION,javax.swing.JOptionPane.PLAIN_MESSAGE,null,new Object[]{cancel,ok},ok);
		if(Mac68kApi.TRACE)System.err.println("[qtma] NAPickInstrument: answer "+answer+", item "+box.getSelectedIndex());
		return answer==1?numbers.get(Math.max(0,box.getSelectedIndex())):-1;
	}
	void closeAllocator(int ci){
		List<NoteChannel> list=allocatorChannels.remove(ci);
		if(list!=null)for(NoteChannel nc:list)disposeChannel(nc);
	}

	// ------------------------------------------------------------------ Tune Player selectors

	private Tune tune(int ci){return tunes.computeIfAbsent(ci,Tune::new);}
	int tunePlayer(int ci,int what,int p) throws M68kException {
		Tune t=tune(ci);
		switch(what){
			case 0x04:return setHeader(t,memory.readLong(p),-1); // TuneSetHeader(tp, header*)
			case 0x1c:return setHeader(t,memory.readLong(p+4),memory.readLong(p)); // TuneSetHeaderWithSize(tp, header*, size)
			case 0x05:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,0);return 0;} // TuneGetTimeBase(tp, TimeBase*): none to share
			case 0x06:{int scale=memory.readLong(p);synchronized(t){t.timeScale=scale<=0?600:scale;}return 0;} // TuneSetTimeScale(tp, scale)
			case 0x07:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,t.timeScale);return 0;} // TuneGetTimeScale(tp, scale*)
			case 0x08:{int out=memory.readLong(p),index=memory.readLong(p+4); // TuneGetIndexedNoteChannel(tp, index, NoteChannel*)
				NoteChannel nc;synchronized(t){nc=index<1||index>t.order.size()?null:t.order.get(index-1);}
				if(out!=0)memory.writeLong(out,nc==null?0:nc.address);return nc==null?PARAM_ERR:0;}
			case 0x0a:return queue(t,memory.readLong(p+24),memory.readLong(p+20),memory.readLong(p+16),memory.readLong(p+12),memory.readLong(p+8),memory.readLong(p+4),memory.readLong(p)); // TuneQueue(tp, tune*, rate, start, stop, flags, callBack, refCon)
			case 0x0b:return 0; // TuneInstant(tp, tune*, position): the controllers there; parts restart from their header state anyway
			case 0x0c:{int out=memory.readLong(p);task(); // TuneGetStatus(tp, TuneStatus*)
				if(out!=0){long[] s=t.status();memory.writeLong(out,(int)s[0]);memory.writeLong(out+4,(int)s[1]);memory.writeLong(out+8,(int)s[2]);memory.writeWord(out+12,(int)s[3]);memory.writeWord(out+14,(int)s[4]);memory.writeLong(out+16,(int)s[5]);
					memory.fill(out+20,12,0);}
				return 0;}
			case 0x0d:t.stop();return 0; // TuneStop(tp, flags)
			case 0x10:t.setVolume(memory.readLong(p));return 0; // TuneSetVolume(tp, Fixed)
			case 0x11:return t.volume; // TuneGetVolume(tp) : Fixed
			case 0x12:case 0x13:return 0; // TunePreroll / TuneUnroll
			case 0x14:return 0; // TuneSetNoteChannels(tp, count, NoteChannel*): the header's channels serve
			case 0x15:{int shift=memory.readLong(p),transpose=memory.readLong(p+4),part=memory.readLong(p+8); // TuneSetPartTranspose(tp, part, transpose, velocityShift)
				synchronized(t){NoteChannel nc=t.parts.get(part);if(nc!=null)synchronized(nc){nc.transpose=transpose;nc.velocityShift=shift;}}return 0;}
			case 0x17:{if(t.noteAllocator==0)t.noteAllocator=tb.components.open(noteAllocatorComponent);return t.noteAllocator;} // TuneGetNoteAllocator(tp) : NoteAllocator
			case 0x18:t.softer=memory.readLong(p);return 0; // TuneSetSofter
			case 0x19:task();return 0; // TuneTask
			case 0x1a:t.balance=memory.readLong(p);return 0; // TuneSetBalance
			case 0x1b:return 0; // TuneSetSoundLocalization
			case 0x1d:{int flags=memory.readLong(p),balance=memory.readLong(p+4),volume=memory.readLong(p+8),part=memory.readLong(p+12); // TuneSetPartMix(tp, part, Fixed volume, balance, flags)
				synchronized(t){t.mix.put(part,new int[]{volume,balance,flags});}t.setVolume(t.volume);return 0;}
			case 0x1e:{int flags=memory.readLong(p),balance=memory.readLong(p+4),volume=memory.readLong(p+8),part=memory.readLong(p+12); // TuneGetPartMix(tp, part, Fixed*, long*, long*)
				int[] m;synchronized(t){m=t.mix.get(part);}if(m==null)m=new int[]{0x10000,0,0};
				if(volume!=0)memory.writeLong(volume,m[0]);if(balance!=0)memory.writeLong(balance,m[1]);if(flags!=0)memory.writeLong(flags,m[2]);return 0;}
			default:return BAD_SELECTOR;
		}
	}
	/** TuneSetHeader: one note channel per note request of the header (its instrument); the old parts go. */
	private int setHeader(Tune t,int header,int size) throws M68kException {
		if(header==0)return PARAM_ERR;
		byte[] data=memory.readBytes(header,size>0?Math.min(size,1<<20):headerLength(header));
		synchronized(t){
			t.stop();
			for(NoteChannel nc:t.parts.values())disposePart(nc);
			t.parts.clear();t.order.clear();
			for(QTMusicConverter.NoteRequest r:QTMusicConverter.noteRequests(data,0,data.length)){
				NoteChannel nc=newChannel(t.instance,requestedInstrument(r.gmNumber,r.instrumentNumber));
				nc.volume=t.volume;nc.applyVolume();
				NoteChannel old=t.parts.put(r.part,nc);if(old!=null){t.order.remove(old);disposePart(old);}
				t.order.add(nc);
			}
		}
		if(Mac68kApi.TRACE)System.err.println("[qtma] TuneSetHeader: "+t.parts.size()+" part(s)");
		return 0;
	}
	private void disposePart(NoteChannel nc){if(nc.address!=0)disposeChannel(nc);else synchronized(nc){nc.release();}}
	/** The bytes of a header up to and including its end marker (general events skipped by their length). */
	private int headerLength(int header) throws M68kException {
		int p=header,limit=header+(1<<20);
		while(p+4<=limit){
			int word=memory.readLong(p);
			if(word==0x60000000)return p+4-header;
			int len=(word>>>30)==3?word&0xffff:(word>>>30)==2?2:1;if(len<1)len=1;
			p+=len*4;
		}
		return p-header;
	}
	/** TuneQueue: the sequence decoded now (its notes become on/off pairs, cut at the piece's end) and played after what is queued. */
	private int queue(Tune t,int tune,int rate,int start,int stop,int flags,int callBack,int refCon) throws M68kException {
		if(tune==0)return PARAM_ERR;
		synchronized(t){if(t.queue.size()>=QUEUE_DEPTH)return -2;} // the queue is full (QuickTime: a negative result)
		byte[] data=memory.readBytes(tune,sequenceLength(tune));
		long[] end=new long[2];
		List<QTMusicConverter.MusicEvent> events=QTMusicConverter.decodeEvents(data,0,data.length,end);
		long from=Integer.toUnsignedLong(start),to=Integer.toUnsignedLong(stop);
		long last=end[0];
		if(to==0||to>last)to=last;if(from>to)from=to;
		Piece piece=new Piece(tune,rate==0?0x10000:rate,flags,callBack,refCon,from,to);
		List<Timed> timed=new ArrayList<Timed>();
		for(QTMusicConverter.MusicEvent e:events){
			if(e.kind==QTMusicConverter.MusicEvent.CONTROL){timed.add(new Timed(Math.max(from,Math.min(to,e.time)),e.part,CONTROL,e.controller,e.value,e.offset));continue;}
			if(e.time<from||e.time>to||e.time==to&&(e.duration>0||(flags&TUNE_EXCLUDE_EDGE)!=0))continue;
			if(e.velocity==0){timed.add(new Timed(e.time,e.part,NOTE_OFF,e.pitch,0,e.offset));continue;}
			long off=e.time+Math.max(1,e.duration);if((flags&TUNE_DONT_CLIP)==0)off=Math.min(off,to);
			timed.add(new Timed(e.time,e.part,NOTE_ON,e.pitch,e.velocity,e.offset));
			timed.add(new Timed(off,e.part,NOTE_OFF,e.pitch,0,e.offset));
		}
		// stable by time; at equal times note-offs first, so a repeated pitch is struck again
		timed.sort((a,b)->a.time!=b.time?Long.compare(a.time,b.time):Integer.compare(a.kind==NOTE_OFF?0:1,b.kind==NOTE_OFF?0:1));
		piece.events.addAll(timed);
		if(Mac68kApi.TRACE)System.err.println("[qtma] TuneQueue "+events.size()+" event(s), "+from+".."+to+" of "+last+" at "+t.timeScale+"/s rate $"+Integer.toHexString(piece.rate)+" flags $"+Integer.toHexString(flags));
		t.enqueue(piece);
		return 0;
	}
	/** The bytes of a sequence up to and including its end marker (at most 4 MB). */
	private int sequenceLength(int tune) throws M68kException {
		int p=tune,limit=tune+(4<<20);
		while(p+4<=limit){
			int word=memory.readLong(p);
			if(word==0x60000000)return p+4-tune;
			int top=word>>>29;
			if(top<4){p+=4;continue;}
			int lengthBits=word>>>30;int len=lengthBits==3?word&0xffff:2;
			if(lengthBits==3&&len<2)len=memory.readLong(p+4);
			if(len<2)len=2;p+=len*4;
		}
		return p-tune;
	}
	void closeTune(int ci){
		Tune t=tunes.remove(ci);if(t==null)return;
		synchronized(t){t.closed=true;t.stop();for(NoteChannel nc:t.parts.values())disposePart(nc);t.parts.clear();t.order.clear();}
		if(t.noteAllocator!=0){try{tb.components.close(t.noteAllocator);}catch(M68kException ignored){}}
	}
}

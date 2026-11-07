import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Basic classic Sound Manager channels, commands, and in-memory 'snd ' playback. */
final class SoundManager68k {
	private static final int NO_ERR=0,NOT_ENOUGH_HARDWARE_ERR=-201,RES_PROBLEM=-204,BAD_CHANNEL=-205,BAD_FORMAT=-206,NOT_ENOUGH_BUFFER_SPACE=-207;
	private static final int CHANNEL_SIZE=36+128*8;
	/** Channels one program may have open at once (Sound Manager 3 on a fast 68K Mac mixes about that many sampled channels). */
	static final int MAX_CHANNELS=32;
	private static final int NULL_CMD=0,QUIET_CMD=3,FLUSH_CMD=4,WAIT_CMD=10,PAUSE_CMD=11,RESUME_CMD=12,CALL_BACK_CMD=13,SYNC_CMD=14;
	private static final int REINIT_CMD=5,FREQ_DURATION_CMD=40,REST_CMD=41,FREQ_CMD=42,AMP_CMD=43,TIMBRE_CMD=44,GET_AMP_CMD=45;
	private static final int SCHEDULED_SOUND_EXTENDED_HEADER=4,EXTENDED_SOUND_BUFFER_SIZE_VALID=2;
	private static final int SCHEDULED_SOUND_CMD=52,SCHEDULED_SOUND_DO_CALL_BACK=2,QUICKTIME_CONTINUATION=-3141;
	private static final int VOLUME_CMD=46,GET_VOLUME_CMD=47,WAVE_TABLE_CMD=60,SOUND_CMD=80,BUFFER_CMD=81,RATE_CMD=82,CONTINUE_CMD=83,GET_RATE_CMD=85,RATE_MULTIPLIER_CMD=86,GET_RATE_MULTIPLIER_CMD=87;
	// Sound.h: dbBufferReady = 1, dbLastBuffer = 4 (it was 2 here: SoundApp's MOD player flags its empty last buffer with 4 and then
	// waits for scChannelBusy to clear before it disposes the channel - the stream ran on with 0-frame buffers and the program hung)
	private static final int DB_BUFFER_READY=1,DB_LAST_BUFFER=4;
	private final M68kMemory memory;
	private final Mac68kApi macApi;
	private final Map<Integer,ChannelState> channels=new HashMap<Integer,ChannelState>();
	/** _SoundDispatch groups an extension installed with group 0 selector 4 (group -> its dispatcher routine): the Speech
	 *  Manager's INIT hands the Sound Manager its group $C dispatcher that way (its linked patch's InstallSpeechDispatch). */
	private final Map<Integer,Integer> installedGroups=new HashMap<Integer,Integer>();
	/** The _SoundDispatch group the Speech Manager installs. */
	private static final int SPEECH_GROUP=0xc;

	SoundManager68k(M68kMemory memory,Mac68kApi macApi){this.memory=memory;this.macApi=macApi;}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		syncSdVolume();
		switch(trap){
			case 0x801:return disposeChannel(cpu);
			case 0x803:return doCommand(cpu,false);
			case 0x804:return doCommand(cpu,true);
			case 0x805:return play(cpu);
			case 0x807:return newChannel(cpu);
			case 0x802:cpu.a[7]+=14;memory.writeWord(cpu.a[7],NO_ERR);return true; // SndAddModifier(chan, modifier, id, init): 14 bytes of arguments;: modifiers are obsolete, accepted and ignored
			case 0x806:return control(cpu);
			case 0x800:return soundDispatch(cpu);
			default:return false;
		}
	}

	private boolean newChannel(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],callback=memory.readLong(sp),init=memory.readLong(sp+4),synth=(short)memory.readWord(sp+8),channelPointer=memory.readLong(sp+10);cpu.a[7]+=14;
		int result=NO_ERR;
		if(channelPointer==0)result=BAD_CHANNEL;
		// A Mac runs out of CPU for more channels and SndNewChannel answers notEnoughHardwareErr (the "Channel Load Test" of the
		// Sound Playback Demo opens channels until it does and reports the count; with no limit it went on until the host could
		// start no more synthesizer threads and the script engine died)
		else if(channels.size()>=MAX_CHANNELS)result=NOT_ENOUGH_HARDWARE_ERR;
		else try {
			// a record the caller passes in is used as it is (SDL sets userInfo and qLength before the call and reads userInfo
			// back in its callBack); the Sound Manager sets up only its own fields there
			int channel=memory.readLong(channelPointer);
			if(channel==0){channel=memory.allocate(CHANNEL_SIZE,2);memory.fill(channel,CHANNEL_SIZE,0);memory.writeWord(channel+30,128);}
			else{memory.fill(channel,12,0);memory.fill(channel+16,14,0);memory.fill(channel+32,4,0);} // all but userInfo and qLength
			memory.writeLong(channel+8,callback);
			channels.put(channel,new ChannelState(channel,synth,init,callback));memory.writeLong(channelPointer,channel);
			if(SND_TRACE)System.err.println("[snd] SndNewChannel $"+Integer.toHexString(channel)+" synth "+synth+" init $"+Integer.toHexString(init)+" callBack $"+Integer.toHexString(callback));
		}catch(M68kCpuException exhausted){memory.writeLong(channelPointer,0);result=NOT_ENOUGH_BUFFER_SPACE;}
		writeResult(cpu,result);return true;
	}

	private boolean disposeChannel(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7];boolean quietNow=memory.readByte(sp)!=0;int channel=memory.readLong(sp+2);cpu.a[7]+=6;
		ChannelState state=channels.remove(channel);int result=state==null?BAD_CHANNEL:NO_ERR;if(SND_TRACE)System.err.println("[snd] SndDisposeChannel $"+Integer.toHexString(channel)+" quietNow="+quietNow);
		if(state!=null){clearPending(state);state.waitToken++;state.waiting=false;stopDoubleBuffer(state);stop(state,!quietNow);if(state.synthesizer!=null)state.synthesizer.stop();if(state.guest!=null){state.guest.close();state.guest=null;}}writeResult(cpu,result);return true;
	}

	/** SndControl(id, VAR cmd): the pre-System 7 way to ask a synthesizer about itself. availableCmd (24) / versionCmd (25)
	 *  answer for the sampled, square-wave and wave-table synthesizers (id 5, 1, 3), which this Sound Manager implements. */
	private boolean control(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],command=memory.readLong(sp),id=(short)memory.readWord(sp+4);cpu.a[7]+=6;
		int result=NO_ERR;
		if(command!=0){int kind=memory.readWord(command)&0x3fff;boolean known=id==5||id==1||id==3;
			// The answers of availableCmd, totalLoadCmd and loadCmd are in param1 (param2 carries the init options asked about; it got
			// the answer too before, kept for programs that may read it there). QuickTime 6's sound media handler sends totalLoadCmd
			// and refuses a sound track with notEnoughHardwareErr (-201) at a load of 75% or more - it read param1's garbage
			if(kind==24){memory.writeWord(command+2,known?1:0);memory.writeLong(command+4,known?1:0);} // availableCmd: 1 when the synthesizer is present
			else if(kind==25)memory.writeLong(command+4,known?0x02000000:0); // versionCmd: 2.0 (param2)
			else if(kind==26){memory.writeWord(command+2,3);memory.writeLong(command+4,3);} // totalLoadCmd: percent of CPU the synthesizers use
			else if(kind==27){memory.writeWord(command+2,1);memory.writeLong(command+4,1);} // loadCmd
			else result=BAD_FORMAT;}
		writeResult(cpu,result);return true;
	}

	private boolean doCommand(M68kCpu cpu,boolean immediate) throws M68kCpuException {
		int sp=cpu.a[7],command,channel;if(immediate){command=memory.readLong(sp);channel=memory.readLong(sp+4);cpu.a[7]+=8;}
		else {command=memory.readLong(sp+2);channel=memory.readLong(sp+6);cpu.a[7]+=10;}
		ChannelState state=channels.get(channel);int result;
		if(state==null)result=BAD_CHANNEL;
		else if(command==0)result=BAD_FORMAT;
		else if(!immediate&&(!state.pending.isEmpty()||state.callBacksDue>0||heldFor(state,memory.readWord(command)&0x3fff))){
			// SndDoCommand behind a sampled sound still playing, a waitCmd still running or a callBack still to run: the command
			// waits its turn in the channel's queue (bufferCmds sent back to back used to play on top of each other)
			if(state.pending.size()>=QUEUE_LENGTH)result=QUEUE_FULL;
			else{state.pending.add(memory.readBytes(command,8));result=NO_ERR;
				if(SND_TRACE)System.err.println("[snd] queued command "+(memory.readWord(command)&0x3fff)+" param2=$"+Integer.toHexString(memory.readLong(command+4))+" channel $"+Integer.toHexString(channel)+" ("+state.pending.size()+" waiting)");
				prelink(state);}
		}
		else{result=execute(state,command,!immediate);drain(state);}
		writeResult(cpu,result);return true;
	}

	/** Commands a channel's queue holds (stdQLength); SndDoCommand answers queueFull beyond. */
	private static final int QUEUE_LENGTH=128,QUEUE_FULL=-203;
	private static final java.util.concurrent.ScheduledExecutorService WAIT_TIMER=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"68K sound waitCmd");t.setDaemon(true);return t;});

	/** Whether the channel's queue is held: by a waitCmd's pause, or by the sampled sound a queued bufferCmd started. */
	private static boolean queueBusy(ChannelState state){
		if(state.waiting||state.paused)return true; // pauseCmd holds the queue until resumeCmd
		// SndPlayDoubleBuffer holds the channel until the dbLastBuffer is played: Shockwave Audio (Director 7) queues a
		// callBackCmd behind it as its end-of-stream notice; run at once, it stopped the music every frame and Last Call's
		// "click anywhere to start your shift" never went on
		DoubleBufferPlayer player=state.doubleBuffer;if(player!=null&&!player.finished)return true;
		SoundMixer68k.Voice last=state.lastQueued;return last!=null&&last.playing();
	}

	/**
	 * Whether a command of {@code kind} has to wait in the queue: as {@link #queueBusy}, except that a callBackCmd behind a
	 * short buffer runs once the mixer has nearly taken it ({@link SoundMixer68k.Voice#onNearEnd}), as a Mac's Sound Manager
	 * calls back when its mixer has the buffer - the next one the callBack sends then follows on without a gap.
	 */
	private static boolean heldFor(ChannelState state,int kind){
		if(!queueBusy(state))return false;
		if(kind!=CALL_BACK_CMD||state.waiting||state.paused||state.doubleBuffer!=null&&!state.doubleBuffer.finished)return true;
		SoundMixer68k.Voice last=state.lastQueued;return !(shortBuffer(last)&&last.nearlyEnded);
	}
	/** A decoded sound of under half a second: a buffer of a stream rather than a sound of its own. */
	static boolean shortBuffer(SoundMixer68k.Voice v){return v!=null&&v.source==null&&v.frames<v.sourceRate*0.5;}

	/** Runs the queued commands that are now free to run (on the emulation thread). */
	private void drain(ChannelState state) throws M68kCpuException {
		if(state.draining)return;state.draining=true;
		try{
			// the Sound Manager takes the next command only once a callBackCmd's routine has run: what the routine puts in front
			// of the waiting commands comes before them. Director's CarbonSndPlayDoubleBuffer callBack queues its next buffer so,
			// ahead of the projector's own callBackCmd (param1 123) behind it; drained first, that one reached the double-buffer
			// routine, which took its param2 for its channel info and called a NIL doubleBack (3pm-slump, Macintosh Garden)
			while(!state.pending.isEmpty()&&state.callBacksDue==0&&!heldFor(state,((state.pending.peek()[0]&255)<<8|(state.pending.peek()[1]&255))&0x3fff)&&channels.get(state.channel)==state){
				byte[] cmd=state.pending.poll();memory.load(state.channel+20,cmd);
				SoundMixer68k.Voice linked=state.prelinked.remove(cmd);
				if(linked!=null){ // a bufferCmd already decoded and linked behind the sound before it: it is (or now starts) playing
					if(linked.stopped)continue;
					state.lastQueued=linked;SoundMixer68k.get().start(linked);continue;}
				int kind=((cmd[0]&255)<<8|(cmd[1]&255))&0x3fff,param1=(short)((cmd[2]&255)<<8|(cmd[3]&255)),param2=(cmd[4]&255)<<24|(cmd[5]&255)<<16|(cmd[6]&255)<<8|(cmd[7]&255);
				execute(state,kind,param1,param2,state.channel+20,true);
			}
		}finally{state.draining=false;}
		prelink(state);
	}

	/**
	 * Decodes the bufferCmds waiting in the channel's queue ahead of time and links each behind the sound before it
	 * ({@link SoundMixer68k#link}), so that the mixer goes from one buffer to the next without a gap; their queue entries
	 * then only mark where they are (drain starts one whose predecessor ended before the link). Only runs of bufferCmds,
	 * callBackCmds and nullCmds qualify: any other command must still act between the sounds, and nothing behind it is
	 * linked. The samples are read when linked, which the Sound Manager's rule allows - a buffer belongs to the Sound
	 * Manager from the bufferCmd until its callBackCmd.
	 */
	private void prelink(ChannelState state) throws M68kCpuException {
		if(!TSound.use||state.rate<=0)return;
		SoundMixer68k.Voice tail=state.tail;
		if(tail==null||tail.ended||tail.stopped)return;
		for(byte[] cmd:state.pending){
			int kind=((cmd[0]&255)<<8|(cmd[1]&255))&0x3fff;
			if(kind==NULL_CMD||kind==CALL_BACK_CMD)continue;
			if(kind!=BUFFER_CMD)return;
			if(state.prelinked.containsKey(cmd))continue;
			int header=(cmd[4]&255)<<24|(cmd[5]&255)<<16|(cmd[6]&255)<<8|(cmd[7]&255);
			tap(state,header);state.mace=null;
			AudioInputStream stream=headerStream(state,header);if(stream==null)return; // badFormat: left to the queue
			SoundMixer68k.Voice voice=voiceFor(state,stream);if(voice==null)return;
			if(!SoundMixer68k.get().link(tail,voice)){synchronized(state.voices){state.voices.remove(voice);}return;}
			state.prelinked.put(cmd,voice);state.tail=tail=voice;
		}
	}
	/** Empties the channel's queue (flushCmd, SndDisposeChannel), bufferCmds already linked for playing included. */
	private void clearPending(ChannelState state){
		state.pending.clear();
		for(SoundMixer68k.Voice v:state.prelinked.values())SoundMixer68k.get().stop(v);
		state.prelinked.clear();
	}
	/** Drains the channel's queue from another thread (a sound ended, a wait ran out): at the emulation thread's next safe point. */
	private void drainLater(final ChannelState state){
		Toolbox68k tb=macApi.application();if(tb==null)return;
		tb.later(new Runnable(){@Override public void run(){try{drain(state);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});
	}

	private int execute(ChannelState state,int command,boolean queued) throws M68kCpuException {
		if(command==0)return BAD_FORMAT;int raw=memory.readWord(command),kind=raw&0x3fff,param1=(short)memory.readWord(command+2),param2=memory.readLong(command+4);
		if(state.channel!=0)memory.copy(command,state.channel+20,8);
		return execute(state,kind,param1,param2,command,queued);
	}
	private int execute(ChannelState state,int kind,int param1,int param2,int command) throws M68kCpuException {return execute(state,kind,param1,param2,command,false);}
	/**
	 * @param queued the command came through SndDoCommand, which appends it to the channel's queue: the Sound Manager runs
	 * it only once the commands before it are done, so a quietCmd or flushCmd behind queued notes waits for them instead of
	 * cutting them (Loom ends every tune with its notes, a quietCmd and a callBackCmd - cut at once, nothing was heard and
	 * the callback told the game the tune was already over).
	 */
	private int execute(ChannelState state,int kind,int param1,int param2,int command,boolean queued) throws M68kCpuException {
		if(Mac68kApi.TRACE||SND_TRACE)System.err.println("[68K] sound command "+kind+" param1="+param1+" param2=$"+Integer.toHexString(param2)+" channel $"+Integer.toHexString(state.channel));
		if(queued&&(kind==QUIET_CMD||kind==FLUSH_CMD)&&state.synthesizer!=null&&state.synthesizer.busy()){
			// Reached only when the notes queued before it have played: quietCmd then stops nothing, flushCmd drops what was queued behind it.
			if(kind==FLUSH_CMD)state.synthesizer.queueFlush();
			return NO_ERR;}
		if(queued&&(kind==VOLUME_CMD||kind==AMP_CMD)&&state.synthesizer!=null&&state.synthesizer.busy()){
			// behind queued notes the level changes when they have played, for the notes after it (PgSoundX's stereo demo steps the
			// volume between its notes; applied at once every note had the last level)
			if(kind==AMP_CMD)state.amplitude=Math.max(0,Math.min(255,param1));else state.volume=param2;
			state.synthesizer.queueLevel(state.amplitude,state.volume);
			return NO_ERR;}
		switch(kind){
			case NULL_CMD:return NO_ERR;
			case SYNC_CMD:return NO_ERR; // syncCmd: channels are not synchronized with each other; the command passes
			case WAIT_CMD:{ // waitCmd: the queue pauses param1 half-milliseconds (behind queued notes, a rest keeps their order)
				int halfMs=param1&0xffff;if(halfMs==0)return NO_ERR;
				if(state.synthesizer!=null&&state.synthesizer.busy()){state.synthesizer.rest(halfMs);return NO_ERR;}
				final int token=++state.waitToken;state.waiting=true;
				WAIT_TIMER.schedule(()->{if(state.waitToken==token){state.waiting=false;drainLater(state);}},halfMs*500L,java.util.concurrent.TimeUnit.MICROSECONDS);
				return NO_ERR;}
			case QUIET_CMD:state.generation++;state.waitToken++;state.waiting=false;stopDoubleBuffer(state);stop(state,false);if(state.synthesizer!=null)state.synthesizer.flush();return NO_ERR;
			case FLUSH_CMD:state.generation++;clearPending(state);state.waitToken++;state.waiting=false;stopDoubleBuffer(state);stop(state,false);if(state.synthesizer!=null)state.synthesizer.flush();memory.writeWord(state.channel+32,0);memory.writeWord(state.channel+34,0);return NO_ERR;
			case PAUSE_CMD:pause(state);return NO_ERR;
			case RESUME_CMD:resume(state);return NO_ERR;
			case AMP_CMD:state.amplitude=Math.max(0,Math.min(255,param1));updateVolume(state);return NO_ERR;
			case GET_AMP_CMD:if(param2!=0)memory.writeWord(param2,state.amplitude);return param2==0?BAD_FORMAT:NO_ERR;
			case VOLUME_CMD:state.volume=param2;updateVolume(state);return NO_ERR;
			case GET_VOLUME_CMD:if(param2!=0)memory.writeLong(param2,state.volume);return param2==0?BAD_FORMAT:NO_ERR;
			case FREQ_DURATION_CMD:synth(state).note(noteOf(param2),param1&0xffff);return NO_ERR;
			case REST_CMD:synth(state).rest(param1&0xffff);return NO_ERR;
			case FREQ_CMD:synth(state).frequency(noteOf(param2));return NO_ERR;
			case TIMBRE_CMD:synth(state).timbre(param1&0xff);return NO_ERR;
			case WAVE_TABLE_CMD:{int length=param1&0xffff;synth(state).waveTable(param2==0||length==0?null:memory.readBytes(param2,length));return NO_ERR;}
			case RATE_CMD:case RATE_MULTIPLIER_CMD:{state.rate=param2==0?0x10000:param2;
				// the sound playing now changes its rate (and pitch) too, sampled notes of the synthesizer as well (PgSoundX's "Rate")
				double rate=state.rate/65536.0;synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)v.rateMultiplier=rate;}
				if(state.synthesizer!=null)state.synthesizer.rate=rate;
				return NO_ERR;}
			case GET_RATE_CMD:case GET_RATE_MULTIPLIER_CMD:if(param2!=0)memory.writeLong(param2,state.rate);return param2==0?BAD_FORMAT:NO_ERR;
			case SOUND_CMD:return installInstrument(state,param2);
			case BUFFER_CMD:return playHeader(state,param2,true,false);
			// continueCmd: the next piece of the same sound - QuickTime plays compressed and 'sowt' sound tracks with it (param1
			// -3141); a decompressor's state runs on from the previous buffer. It used to answer badFormat: those tracks were silent
			case CONTINUE_CMD:return playHeader(state,param2,true,true);
			case REINIT_CMD:return NO_ERR; // reInitCmd: HyperCard sends it before every play; the channel keeps its settings
			case CALL_BACK_CMD:if(command!=0)scheduleCallBack(state,command);return NO_ERR;
			// scheduledSoundCmd (Sound Manager 3.3): a ScheduledSoundHeader - the sound header (a 66-byte union of the three
			// header kinds), then flags.l, reserved.w, callBackParam1.w (72), callBackParam2.l (74), startTime (78). QuickTime 5/6's
			// sound media handler feeds its sound track with it: each plays in queue order as soon as the sound before it ends (the
			// start time against the channel's clock is not kept), and with kScheduledSoundDoCallBack the channel's callback gets
			// a callBackCmd with the header's two parameters once it has played - the handler queues the next buffer then
			case SCHEDULED_SOUND_CMD:{
				if(param2==0)return BAD_FORMAT;
				// an ExtendedScheduledSoundHeader (flags kScheduledSoundExtendedHdr) after the 94 bytes: recordSize, extendedFlags, bufferSize ...
				// QDesign Music's packets come with their byte count (kExtendedSoundBufferSizeValid), which its decompressor reads
				int flags=memory.readLong(param2+66);
				state.bufferSize=(flags&SCHEDULED_SOUND_EXTENDED_HEADER)!=0&&(memory.readLong(param2+98)&EXTENDED_SOUND_BUFFER_SIZE_VALID)!=0?memory.readLong(param2+102):0;
				int result;try{result=playHeader(state,param2,true,param1==QUICKTIME_CONTINUATION);}finally{state.bufferSize=0;}
				if(result==NO_ERR&&(flags&SCHEDULED_SOUND_DO_CALL_BACK)!=0){
					byte[] cmd=new byte[8];putWord(cmd,0,CALL_BACK_CMD);putWord(cmd,2,memory.readWord(param2+72));putLong(cmd,4,memory.readLong(param2+74));
					scheduleCallBack(state,cmd);}
				return result;}
			default:return BAD_FORMAT;
		}
	}

	/** The channel's synthesizer, created by the first note command whatever synthesizer the channel was opened with
	 *  (Sound Manager 3 plays freqDurationCmds on sampled channels too). */
	private SoundSynth68k synth(ChannelState state){
		if(state.synthesizer==null){state.synthesizer=new SoundSynth68k(state.amplitude,state.volume);if(state.paused)state.synthesizer.pause(true);}
		return state.synthesizer;
	}
	/** freqCmd/freqDurationCmd param2: a MIDI note value (0-127) in the low byte; a whole Fixed 16.16 note (integer part below
	 *  128) as Sound Manager 3 allows. Anything else in the upper bytes is ignored (HyperCard's play sends $FF0000nn). */
	private static double noteOf(int param2){if((param2&0xffffff00)==0)return param2;int whole=param2>>>16;return whole<128?(param2&0xffffffffL)/65536.0:param2&0xff;}

	/**
	 * callBackCmd: the channel's userRoutine (SndNewChannel) runs once the commands queued before it are done - i.e. when
	 * the sound playing on the channel ends, or right away when nothing plays. Programs queue it after a bufferCmd to
	 * learn that the sound has finished (some runtimes loop music that way). The routine is Pascal
	 * callBack(chan, VAR cmd), run on the emulation thread with the command copied into the channel's cmdInProgress.
	 */
	private void scheduleCallBack(final ChannelState state,int command) throws M68kCpuException {
		final byte[] cmd=new byte[8];for(int i=0;i<8;i++)cmd[i]=(byte)memory.readByte(command+i);scheduleCallBack(state,cmd);}
	/** As above, for the callBackCmd {@code cmd} (8 bytes). */
	private void scheduleCallBack(final ChannelState state,final byte[] cmd) throws M68kCpuException {
		if(callBackOf(state)==0)return;final int generation=state.generation;
		final Runnable run=new Runnable(){@Override public void run(){try{
			Toolbox68k tb=macApi.application();if(tb==null||state.generation!=generation)return;int callback=callBackOf(state);if(callback==0)return;
			M68kCpu cpu=tb.cpu;memory.load(state.channel+20,cmd);
			if(Mac68kApi.TRACE||SND_TRACE)System.err.println("[68K] sound callback for channel $"+Integer.toHexString(state.channel));
			int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushLong(state.channel);cpu.pushLong(state.channel+20);
			List<byte[]> exposed=exposeQueue(state);
			state.inCallBack=true;
			try{cpu.callPascal(callback,8);}finally{state.inCallBack=false;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
			if(adoptQueue(state,exposed))drain(state);
		}catch(M68kException e){throw Toolbox68k.wrap(e);}}};
		SoundSynth68k synth=state.synthesizer;if(synth!=null&&synth.busy()){synth.callback(()->macApi.application().later(run));return;} // behind the queued notes
		final SoundMixer68k.Voice last=state.lastQueued;
		// the queue waits for the routine (see drain); a stopped sound still runs its end hooks, so the count comes back down
		state.callBacksDue++;
		final Runnable counted=()->{try{run.run();}finally{state.callBacksDue--;
			try{if(state.callBacksDue==0)drain(state);}catch(M68kCpuException e){throw Toolbox68k.wrap(e);}}};
		if(last==null||last.ended){macApi.application().later(counted);return;} // (one linked behind the sound playing has not started yet)
		Runnable due=new Runnable(){@Override public void run(){Toolbox68k tb=macApi.application();if(tb!=null)tb.later(counted);}};
		// a short buffer of a stream: once the mixer has nearly taken it, so the next one the callBack sends follows on (startClip
		// links it); after a longer sound the callBack still says it has been heard to the end
		if(shortBuffer(last))last.onNearEnd(due);else last.onEnd(due);
	}

	/**
	 * The channel record's own queue (qHead at 32, qTail at 34, queue[qLength] at 36) as a callBack sees it: the commands
	 * still waiting, ending at qTail = qLength; qHead -1 when there are none. Apple's CarbonSndPlayDoubleBuffer (Carbon has
	 * no SndPlayDoubleBuffer) queues its next bufferCmd and callBackCmd from the callBack by writing them in front of the
	 * waiting ones there (qHead--, queue[qHead] = cmd) rather than through SndDoCommand; Arnold (Amstrad CPC emulator)
	 * plays its sound that way, and its emulation, paced by the buffers, stopped after the first one. Returns the commands
	 * put there, or null when the record has no room for them.
	 */
	private List<byte[]> exposeQueue(ChannelState state) throws M68kCpuException {
		int length=memory.readWord(state.channel+30);
		if(length<1||length>QUEUE_LENGTH||state.pending.size()>=length)return null;
		List<byte[]> waiting=new ArrayList<byte[]>(state.pending);int head=length-waiting.size();
		for(int i=0;i<waiting.size();i++)memory.load(state.channel+36+(head+i)*8,waiting.get(i));
		memory.writeWord(state.channel+32,waiting.isEmpty()?-1:head);memory.writeWord(state.channel+34,length);
		return waiting;
	}
	/** After the callBack: the commands it wrote in front of {@code exposed} in the record go to the front of the queue. True when there were any. */
	private boolean adoptQueue(ChannelState state,List<byte[]> exposed) throws M68kCpuException {
		if(exposed==null)return false;
		int length=memory.readWord(state.channel+30),head=(short)memory.readWord(state.channel+32),tail=memory.readWord(state.channel+34);
		if(head<0||head>=length||tail>length||head>=tail)return false;
		int added=tail-head-exposed.size();if(added<=0)return false;
		for(int i=0;i<exposed.size();i++)if(!java.util.Arrays.equals(memory.readBytes(state.channel+36+(head+added+i)*8,8),exposed.get(i)))return false;
		for(int i=added-1;i>=0;i--){byte[] cmd=memory.readBytes(state.channel+36+(head+i)*8,8);state.pending.addFirst(cmd);
			if(SND_TRACE)System.err.println("[snd] callBack put command "+(((cmd[0]&255)<<8|(cmd[1]&255))&0x3fff)+" in the channel record's queue");}
		return true;
	}

	/**
	 * The channel's callBack field: SndNewChannel puts its userRoutine there, and a program may set it afterwards - Arnold
	 * (Amstrad CPC emulator) opens its channel with none and stores a NewSndCallBackUPP into chan->callBack; its emulation
	 * waits for that callback after every sound buffer and stopped after a few frames.
	 */
	private int callBackOf(ChannelState state) throws M68kCpuException {return memory.readLong(state.channel+8);}

	private boolean play(M68kCpu cpu) throws M68kCpuException {if(DEBUG_FILE_PLAY)System.err.println("[68K] SndPlay $"+Integer.toHexString(memory.readLong(cpu.a[7]+6)));
		int sp=cpu.a[7];boolean async=memory.readByte(sp)!=0;int handle=memory.readLong(sp+2),channel=memory.readLong(sp+6);cpu.a[7]+=10;
		ChannelState state=channel==0?null:channels.get(channel);int result;
		if(channel!=0&&state==null)result=BAD_CHANNEL;
		else {byte[] sound=macApi.handleContents(handle);
			if(sound==null)result=RES_PROBLEM;
			else if(hasSynthesizerCommands(sound))result=playCommands(state,handle,sound,channel!=0&&async);
			else if(state!=null&&async&&(!state.pending.isEmpty()||queueBusy(state))&&queueBehind(state,handle,sound))result=NO_ERR;
			else result=playBytes(state,sound,channel!=0&&async);}
		writeResult(cpu,result);return true;
	}
	/**
	 * Asynchronous SndPlay on a channel that is still playing: the Sound Manager issues the resource's bufferCmd through
	 * the channel's queue, so the sound starts when the one before it ends. Smacker (Warcraft II's Blizzard logo) plays
	 * its sound track as two SndPlays back to back, each followed by a callBackCmd, and paces the video by those
	 * callbacks; the sounds used to play on top of each other and both callbacks came when the shorter one ended.
	 * False (play at once) when the resource has no sound header in place.
	 */
	private boolean queueBehind(ChannelState state,int handle,byte[] sound) throws M68kCpuException {
		int offset=soundHeaderOffset(sound);if(offset<0||u32(sound,offset)!=0)return false;
		if(state.pending.size()>=QUEUE_LENGTH)return false;
		byte[] cmd=new byte[8];putWord(cmd,0,BUFFER_CMD);putLong(cmd,4,memory.readLong(handle)+offset);
		state.pending.add(cmd);return true;
	}

	/** Whether a format 1/2 'snd ' carries note commands (a tune for the square-wave or wave-table synthesizer) rather than only sampled data. */
	private static boolean hasSynthesizerCommands(byte[] sound){
		if(!validSoundResource(sound))return false;int format=u16(sound,0),commandOffset,count;
		if(format==1){commandOffset=4+u16(sound,2)*6+2;count=u16(sound,commandOffset-2);}else{commandOffset=6;count=u16(sound,4);}
		for(int i=0;i<count;i++){int kind=u16(sound,commandOffset+i*8)&0x3fff;if(kind==FREQ_DURATION_CMD||kind==REST_CMD||kind==FREQ_CMD||kind==TIMBRE_CMD||kind==WAVE_TABLE_CMD)return true;}
		return false;
	}
	/**
	 * SndPlay of a 'snd ' made of commands (old games' tunes: freqDurationCmds with a squareWaveSynth): the commands run on
	 * the channel as SndDoCommand would; bufferCmd/soundCmd with the offset flag point into the resource. Without a channel
	 * a private one plays the tune, synchronously unless asked otherwise.
	 */
	private int playCommands(ChannelState state,int handle,byte[] sound,boolean async) throws M68kCpuException {
		int format=u16(sound,0),commandOffset,count;boolean temporary=state==null;
		if(format==1){commandOffset=4+u16(sound,2)*6+2;count=u16(sound,commandOffset-2);}else{commandOffset=6;count=u16(sound,4);}
		if(temporary)state=new ChannelState(0,1,0,0);
		int data=memory.readLong(handle),result=NO_ERR;
		for(int i=0;i<count;i++){int o=commandOffset+i*8,raw=u16(sound,o),kind=raw&0x3fff,param1=(short)u16(sound,o+2),param2=(int)u32(sound,o+4);
			if((kind==BUFFER_CMD||kind==SOUND_CMD)&&(raw&0x8000)!=0)param2=data+param2;
			int r=execute(state,kind,param1,param2,0);if(r!=NO_ERR)result=r;}
		if(!async||temporary){SoundSynth68k synth=state.synthesizer;long deadline=System.currentTimeMillis()+120_000;
			while(synth!=null&&synth.busy()&&System.currentTimeMillis()<deadline)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			if(temporary){if(synth!=null)synth.stop();stop(state,true);}}
		return result;
	}

	/**
	 * A sound handed to the Sound Manager, as it was in memory (tools/qt/QtAudioOracle records what QuickTime's sound
	 * media handler plays): the header's encoding (0 standard, $FF extended, $FE compressed, -1 a SndPlayDoubleBuffer
	 * buffer), rate, channels, sample size, compressionID and format, the frame count and the sample bytes.
	 */
	static final class Feed {
		final int channel,encoding,channels,sampleSize,compressionId,format,frames;final double rate;final byte[] bytes;
		Feed(int channel,int encoding,double rate,int channels,int sampleSize,int compressionId,int format,int frames,byte[] bytes){this.channel=channel;this.encoding=encoding;this.rate=rate;this.channels=channels;this.sampleSize=sampleSize;this.compressionId=compressionId;this.format=format;this.frames=frames;this.bytes=bytes;}
	}
	/** Tools only: receives every bufferCmd header and double buffer (null: nothing is recorded). */
	static volatile java.util.function.Consumer<Feed> feedTap;
	private void tap(ChannelState state,int header) throws M68kCpuException {
		java.util.function.Consumer<Feed> t=feedTap;if(t==null||header==0)return;
		int encoding=memory.readByte(header+20)&0xff,samplePointer=memory.readLong(header);double rate=Integer.toUnsignedLong(memory.readLong(header+8))/65536.0;
		if(rate<=0)rate=state.formatRate;
		if(encoding==0){int length=memory.readLong(header+4);t.accept(new Feed(state.channel,0,rate,1,8,0,0x72617720,length,memory.readBytes(samplePointer==0?header+22:samplePointer,Math.max(0,length))));return;}
		if(encoding!=0xff&&encoding!=0xfe)return;
		int channels=memory.readLong(header+4),frames=memory.readLong(header+22),samples=samplePointer==0?header+64:samplePointer;
		int sampleSize=memory.readWord(header+(encoding==0xff?48:62)),compressionId=encoding==0xfe?(short)memory.readWord(header+56):0,format=encoding==0xfe?memory.readLong(header+40):0;
		long bytes;
		if(compressionId==0)bytes=(long)frames*channels*(sampleSize/8);
		else bytes=(long)frames*QuickTimeFile.SoundDescription.derived(compressionId==3?0x4d414333:compressionId==4?0x4d414336:format,channels,sampleSize)[2]; // frames = packets
		t.accept(new Feed(state.channel,encoding,rate,channels,sampleSize,compressionId,format,frames,bytes>0&&bytes<64*1024*1024?memory.readBytes(samples,(int)bytes):new byte[0]));
	}

	/** bufferCmd: the sound header's samples play once at the channel's rate. */
	private int playHeader(ChannelState state,int header,boolean async,boolean continuing) throws M68kCpuException {
		tap(state,header);
		if(!continuing){state.mace=null;if(state.guest!=null){state.guest.close();state.guest=null;}} // a new sound: a decompressor starts from its initial state
		AudioInputStream stream=headerStream(state,header);if(stream==null)return BAD_FORMAT;
		if(!TSound.use)return NO_ERR;
		return startClip(state,stream,async);
	}

	/**
	 * soundCmd: the sound header becomes the channel's instrument - it does not play by itself; freqDurationCmd/freqCmd
	 * notes then play its samples pitched against the header's baseFrequency (byte 21, a MIDI note) and looped between
	 * loopStart/loopEnd (longs 12/16, sample frames) while a note lasts. HyperCard's play command works this way (soundCmd,
	 * then one freqDurationCmd per note) - the header used to play once right away and the notes came out as square waves.
	 */
	private int installInstrument(ChannelState state,int header) throws M68kCpuException {
		if(header!=0){long rate=Integer.toUnsignedLong(memory.readLong(header+8));if(rate!=0)state.formatRate=rate/65536.0;}
		AudioInputStream stream=headerStream(state,header);if(stream==null)return BAD_FORMAT;
		int baseNote=memory.readByte(header+21)&0xff;long loopStart=Integer.toUnsignedLong(memory.readLong(header+12)),loopEnd=Integer.toUnsignedLong(memory.readLong(header+16));
		SoundSynth68k.Instrument instrument;
		// a header of no frames only sets the channel's format: iTunes sends 44.1 kHz 16-bit stereo that way, then streams
		// bufferCmds whose headers carry no sampleRate (badFormat there made it close the song again without a sound)
		if(stream.getFrameLength()==0)return NO_ERR;
		try {instrument=SoundSynth68k.Instrument.of(stream,baseNote==0?60:baseNote,loopStart,loopEnd);}catch(IOException e){return BAD_FORMAT;}
		if(instrument==null)return BAD_FORMAT;
		if(Mac68kApi.TRACE)System.err.println("[68K] soundCmd: instrument of "+instrument.samples.length+" samples at "+instrument.rate+" Hz, base note "+instrument.baseNote+(instrument.loops()?", loop "+instrument.loopStart+"-"+instrument.loopEnd:"")+" on channel $"+Integer.toHexString(state.channel));
		synth(state).instrument(instrument);return NO_ERR;
	}

	/**
	 * The samples of a sound header in memory as a PCM stream, or null when the header is not one this Sound Manager
	 * plays. Standard header (encode 0): 8-bit mono, length.l at 4, samples at 22. Extended (extSH, $FF) or compressed
	 * (cmpSH, $FE) header: numChannels.l at 4, numFrames.l at 22, sampleSize.w at 48 (extSH) or 62 (cmpSH, after
	 * compressionID.w 56 / packetSize.w 58 / snthID.w 60), samples at 64 unless samplePtr says otherwise. A cmpSH holds
	 * 8-bit unsigned or 16-bit signed PCM (compressionID 0) or MACE 3:1 / 6:1 packets (compressionID 3 / 4, or -1 with
	 * format 'MAC3' / 'MAC6' at 40), expanded natively; with compressionID -1 / -2 the format at 40 names what QuickTimeSound
	 * decodes (ulaw, alaw, ima4, twos, sowt, …). Other compressionIDs and formats are not played (badFormat).
	 */
	private AudioInputStream headerStream(ChannelState state,int header) throws M68kCpuException {
		if(header==0)return null;int encoding=memory.readByte(header+20);double rate=Integer.toUnsignedLong(memory.readLong(header+8))/65536.0;
		if(rate<=0&&state!=null)rate=state.formatRate; // no rate of its own: the format the channel's soundCmd set
		if(rate<=0)return null;int samplePointer=memory.readLong(header);
		if(encoding==0){long length=Integer.toUnsignedLong(memory.readLong(header+4));if(length>Integer.MAX_VALUE-64)return null;
			int samples=samplePointer==0?header+22:samplePointer;byte[] pcm=memory.readBytes(samples,(int)length);
			return new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat((float)rate,8,1,false,true),length);}
		if(encoding!=0xff&&encoding!=0xfe)return null;
		int channels=memory.readLong(header+4),frames=memory.readLong(header+22);if(channels<1||channels>2||frames<0)return null;
		int samples=samplePointer==0?header+64:samplePointer;
		if(encoding==0xfe){int compressionID=(short)memory.readWord(header+56);long format=Integer.toUnsignedLong(memory.readLong(header+40));
			boolean mac3=compressionID==3||(compressionID==-1&&format==0x4d414333L),mac6=compressionID==4||(compressionID==-1&&format==0x4d414336L); // 'MAC3' / 'MAC6'
			if(mac3||mac6){long bytes=(long)frames*channels*(mac3?2:1);if(bytes>Integer.MAX_VALUE)return null;
				// the channel's decompressor: continueCmd buffers carry on from the state the previous buffer left
				if(state.mace==null||state.mace.six!=mac6||state.mace.channels!=channels)state.mace=new MaceDecoder.Stream(mac6,channels);
				byte[] packets=memory.readBytes(samples,(int)bytes),pcm=state.mace.decode(packets,0,packets.length);
				return new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat((float)rate,8,channels,false,true),pcm.length/channels);}
			// the other formats QuickTime's sound media handler passes on (ulaw, alaw, sowt, in24, in32, fl32, fl64, twos): decoded
			// like a QuickTime sound description of that format ('raw '/'NONE' with compressionID 0 take the plain path below).
			// The format field names the compressor only for notCompressed (0), fixedCompression (-1) and variableCompression
			// (-2); any other compressionID (twoToOne 1, eightToThree 2, …) is one this Sound Manager has no decoder for: badFormat
			if(compressionID!=0&&compressionID!=-1&&compressionID!=-2)return null;
			String name=ResourceFile68k.typeName((int)format);
			if(compressionID!=0||!(format==0||name.equals("raw ")||name.equals("NONE"))){
				int sampleSize=memory.readWord(header+62);
				QuickTimeFile.SoundDescription d=QuickTimeSound.description(name,channels,sampleSize,rate);
				if(!QuickTimeSound.canDecode(d))return guestDecoded(state,(int)format,channels,sampleSize,rate,samples,frames);
				// frames count packets for packet formats (ima4: 64 samples in 34 bytes a channel)
				long bytes=(long)frames*d.bytesPerFrame;if(bytes>64L*1024*1024)return null;
				try{short[] pcm=QuickTimeSound.decode(d,memory.readBytes(samples,(int)bytes),(long)frames*Math.max(1,d.samplesPerPacket));
					return new QuickTimeSound.Pcm(pcm,(float)rate,channels).stream();}
				catch(IOException e){return null;}}
			}
		int sampleSize=memory.readWord(header+(encoding==0xff?48:62));if(sampleSize!=8&&sampleSize!=16)return null;
		long bytes=(long)frames*channels*(sampleSize/8);if(bytes>Integer.MAX_VALUE)return null;
		byte[] pcm=memory.readBytes(samples,(int)bytes);
		return new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat((float)rate,sampleSize,channels,sampleSize==16,true),frames);
	}

	/** A format only the installed system software decodes (QuickTime 6's 'QDM2'): its 'sdec' component expands it (GuestSoundDecoder);
	 *  null (badFormat) without one. {@code frames} counts the header's packets. */
	private AudioInputStream guestDecoded(ChannelState state,int format,int channels,int sampleSize,double rate,int samples,int frames) throws M68kCpuException {
		Toolbox68k tb=macApi.application();if(tb==null||state==null)return null;
		try{
			GuestSoundDecoder g=state.guest;
			if(g==null||g.format!=format||g.channels!=channels){if(g!=null)g.close();state.guest=g=GuestSoundDecoder.open(tb,format,channels,sampleSize,(int)(rate*65536),state.decompression);}
			if(g==null)return null;
			byte[] pcm=g.decode(samples,frames,sampleSize,(int)(rate*65536),state.bufferSize);
			return new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat((float)g.outputRate(rate),16,g.outputChannels,true,true),pcm.length/(2*g.outputChannels));
		}catch(M68kCpuException e){throw e;}catch(M68kException e){System.err.println("[68K] sound decompressor '"+ResourceFile68k.typeName(format)+"': "+e.getMessage());return null;}
	}

	private int playBytes(ChannelState state,byte[] sound,boolean async) {
		if(!validSoundResource(sound))return BAD_FORMAT;
		if(!TSound.use)return NO_ERR;
		AudioInputStream stream=pcmStream(sound);
		if(stream!=null)return stream.getFrameLength()==0?NO_ERR:startClip(state,stream,async);
		byte[] aiff;try {aiff=HCResource.decodeSndToAiffBytes(sound);}catch(RuntimeException invalid){return BAD_FORMAT;}if(aiff==null)return BAD_FORMAT;
		try {
			try {stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(aiff));}
			catch(UnsupportedAudioFileException unsupported){stream=MaceConverter.open(aiff);if(stream==null)return BAD_FORMAT;}
		}catch(IOException invalid){return BAD_FORMAT;}
		return startClip(state,stream,async);
	}

	/**
	 * The sampled data of a 'snd ' straight from the resource bytes (no AIFF round trip through a temporary file, which
	 * some programs paid at every frame): the first bufferCmd/soundCmd with the offset flag, its standard header (encode 0:
	 * 8-bit mono, length.l at 4, samples at 22), extended header ($FF: numChannels.l at 4, numFrames.l at 22, sampleSize.w
	 * at 48, samples at 64. Some mono resources were otherwise read as one-frame stereo sounds that never played,
	 * so the game restarted it at every frame and leaked a Clip thread each time) or an uncompressed compressed-format
	 * header ($FE, compressionID.w 56 = 0, sampleSize.w at 62). Null for MACE (synth init or compressionID) and for
	 * anything else: those go through the AIFF/AIFC converters.
	 */
	/** The offset of the sound header the first soundCmd/bufferCmd (with the data-offset flag) of a format 1/2 'snd ' names; -1 when none. */
	static int soundHeaderOffset(byte[] sound){
		if(sound.length<6)return -1;
		int format=u16(sound,0),commandOffset,count;
		if(format==1){int synths=u16(sound,2);commandOffset=4+synths*6+2;if(commandOffset>sound.length)return -1;count=u16(sound,commandOffset-2);}
		else if(format==2){commandOffset=6;count=u16(sound,4);}
		else return -1;
		for(int i=0;i<count&&commandOffset+i*8+8<=sound.length;i++){int o=commandOffset+i*8,raw=u16(sound,o),kind=raw&0x3fff;
			if((kind==BUFFER_CMD||kind==SOUND_CMD)&&(raw&0x8000)!=0){long at=u32(sound,o+4);return at<sound.length?(int)at:-1;}}
		return -1;
	}
	static AudioInputStream pcmStream(byte[] sound){
		AudioInputStream mace=MaceDecoder.streamFromSnd(sound);if(mace!=null)return mace; // MACE 3:1 / 6:1 expanded natively
		int format=u16(sound,0),commandOffset,count;
		if(format==1){int synths=u16(sound,2);for(int i=0;i<synths;i++)if((u32(sound,6+i*6)&0x0700)!=0)return null; // initMACE3 $0300 / initMACE6 $0400
			commandOffset=4+synths*6+2;count=u16(sound,commandOffset-2);}
		else{commandOffset=6;count=u16(sound,4);}
		for(int i=0;i<count;i++){int o=commandOffset+i*8,raw=u16(sound,o),kind=raw&0x3fff;
			if((kind!=BUFFER_CMD&&kind!=SOUND_CMD)||(raw&0x8000)==0)continue;
			long headerOffset=u32(sound,o+4);if(headerOffset+22>sound.length)return null;int header=(int)headerOffset;
			if(u32(sound,header)!=0)return null; // samplePtr: the data lives outside the resource
			int encoding=sound[header+20]&255,channels=1,sampleSize=8;long frames;int samples;double rate=u32(sound,header+8)/65536.0;
			if(encoding==0){frames=u32(sound,header+4);samples=header+22;}
			else if(encoding==0xff||encoding==0xfe){if(header+64>sound.length)return null;
				if(encoding==0xfe&&(short)u16(sound,header+56)!=0)return null;
				channels=(int)u32(sound,header+4);frames=u32(sound,header+22);sampleSize=u16(sound,header+(encoding==0xff?48:62));samples=header+64;}
			else return null;
			if(channels<1||channels>2||rate<=0||(sampleSize!=8&&sampleSize!=16))return null;
			int frameBytes=channels*(sampleSize/8);frames=Math.min(frames,(sound.length-samples)/frameBytes);
			AudioFormat af=new AudioFormat((float)rate,sampleSize,channels,sampleSize==16,true);
			return new AudioInputStream(new ByteArrayInputStream(sound,samples,(int)(frames*frameBytes)),af,frames);}
		return null;
	}

	/** Plays a decoded stream on the channel (a mixer voice per sound); synchronous play blocks the emulation thread until it ends. */
	private int startClip(ChannelState state,AudioInputStream stream,boolean async) {
		final SoundMixer68k.Voice voice=voiceFor(state,stream);
		if(voice==null)return BAD_FORMAT;
		// a sound started from the callBack of the one still finishing (VocalWriter's synthesizer: SndDoImmediate bufferCmd,
		// then callBackCmd, per 50 ms buffer) follows it without a gap, as on a Mac whose mixer had already taken that one
		SoundMixer68k.Voice previous=state==null?null:state.tail;
		boolean linked=async&&state.inCallBack&&previous!=null&&previous.nearlyEnded&&previous.source==null&&SoundMixer68k.get().link(previous,voice);
		if(SND_TRACE&&state!=null)System.err.println("[snd] buffer "+voice.frames+" frames "+(linked?"linked behind the one finishing":"started")+" (inCallBack "+state.inCallBack+", previous "+(previous==null?"none":previous.ended?"ended":previous.nearlyEnded?"nearly ended":"playing")+")");
		if(state!=null)state.lastQueued=state.tail=voice;
		if(!linked)SoundMixer68k.get().start(voice);
		if(!async){while(voice.playing())try {Thread.sleep(5);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}SoundMixer68k.get().stop(voice);}
		return NO_ERR;
	}

	/** A mixer voice for a sound of the channel (not started): rateCmd's multiplier, the channel's volume, and a drain of the queue when it ends. */
	private SoundMixer68k.Voice voiceFor(ChannelState state,AudioInputStream stream){
		double multiplier=state!=null&&state.rate!=0x10000&&state.rate>0?(state.rate&0xffffffffL)/65536.0:1; // rateCmd: the sound plays faster or slower
		final SoundMixer68k.Voice voice=SoundMixer68k.get().decode(stream,multiplier);
		if(voice==null||state==null)return voice;
		synchronized(state.voices){state.voices.removeIf(old->old.ended);state.voices.add(voice);}applyVolume(state,voice);
		final ChannelState owner=state;voice.onEnd(new Runnable(){@Override public void run(){synchronized(owner.voices){owner.voices.remove(voice);}drainLater(owner);}});
		if(shortBuffer(voice))voice.onNearEnd(()->drainLater(owner)); // a callBackCmd queued behind it may run now (heldFor)
		return voice;
	}

	private void pause(ChannelState state){state.paused=true;synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)v.paused=true;}DoubleBufferPlayer p=state.doubleBuffer;if(p!=null)p.pause(true);if(state.synthesizer!=null)state.synthesizer.pause(true);}
	private void resume(ChannelState state){state.paused=false;synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)v.paused=false;}DoubleBufferPlayer p=state.doubleBuffer;if(p!=null)p.pause(false);if(state.synthesizer!=null)state.synthesizer.pause(false);}
	/** Silences the channel's sampled sounds; {@code drain} lets them finish first (SndDisposeChannel with quietNow false), within reason. */
	private void stop(ChannelState state,boolean drain){
		// (voices linked for queued bufferCmds but not started yet stay: their queue entries still play them)
		List<SoundMixer68k.Voice> playing=new ArrayList<SoundMixer68k.Voice>();
		synchronized(state.voices){for(java.util.Iterator<SoundMixer68k.Voice> i=state.voices.iterator();i.hasNext();){SoundMixer68k.Voice v=i.next();if(v.started||!state.prelinked.containsValue(v)){playing.add(v);i.remove();}}}
		if(drain){long deadline=System.currentTimeMillis()+30_000;for(SoundMixer68k.Voice v:playing)while(v.playing()&&System.currentTimeMillis()<deadline)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
		for(SoundMixer68k.Voice v:playing)SoundMixer68k.get().stop(v);
	}
	private void release(ChannelState state){
		List<SoundMixer68k.Voice> playing=new ArrayList<SoundMixer68k.Voice>();
		synchronized(state.voices){playing.addAll(state.voices);state.voices.clear();}
		SoundMixer68k mixer=SoundMixer68k.get();
		for(SoundMixer68k.Voice v:playing){if(v.source==null)mixer.release(v);else mixer.stop(v);} // a streaming voice reads the VM's memory
	}
	private void updateVolume(ChannelState state){synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)applyVolume(state,v);}DoubleBufferPlayer p=state.doubleBuffer;if(p!=null&&p.line!=null)applyVolume(state,p.line);if(state.synthesizer!=null){state.synthesizer.amplitude=state.amplitude;state.synthesizer.volume=state.volume;}}
	/** volumeCmd's left/right levels (0x0100 = full) scaled by ampCmd's amplitude, as linear gains of a mixer voice. */
	private void applyVolume(ChannelState state,SoundMixer68k.Voice voice){
		int left=state.volume&0xffff,right=state.volume>>>16;double amplitude=state.amplitude/255.0;
		voice.gainLeft=(float)Math.max(0,Math.min(4,left/256.0*amplitude));voice.gainRight=(float)Math.max(0,Math.min(4,right/256.0*amplitude));
	}
	private void applyVolume(ChannelState state,javax.sound.sampled.Line clip){
		if(!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))return;int left=state.volume&0xffff,right=state.volume>>>16;
		double level=(Math.max(left*SoundMixer68k.gainLeft(),right*SoundMixer68k.gainRight())/256.0)*(state.amplitude/255.0);FloatControl control=(FloatControl)clip.getControl(FloatControl.Type.MASTER_GAIN);
		float decibels=level<=0?control.getMinimum():(float)(20.0*Math.log10(level));control.setValue(Math.max(control.getMinimum(),Math.min(control.getMaximum(),decibels)));
	}
	/** SysBeep: the alert at the output volume (SoundMixer68k.beep - silent at SetSoundVol 0, a quieter tone below full). */
	void sysBeep(){syncSdVolume();SoundMixer68k.beep();}
	/** What this VM's SdVolume ($260) held when it was last made to agree with the output volume (-1: not yet). */
	private int knownSdVolume=-1;
	/** Boot: SdVolume starts at the output volume all VMs share (an external's VM made after SetSoundVol in another reads that level). */
	void initSdVolume(){try{int level=SoundMixer68k.soundVol();memory.writeByte(0x260,(memory.readByte(0x260)&~7)|level);knownSdVolume=level;}catch(M68kCpuException ignored){}}
	/**
	 * This VM's SdVolume and the shared output volume agree again: a level the program stored there itself since (programs
	 * poke SdVolume, PowerPC's SetSoundVol writes it) becomes the output volume, otherwise the output volume another VM set
	 * is written there for GetSoundVol, which only reads it. Called at each Sound Manager trap, SysBeep and external call.
	 */
	void syncSdVolume(){
		try{int level=memory.readByte(0x260)&7;
			if(knownSdVolume>=0&&level!=knownSdVolume)SoundMixer68k.setSoundVol(level);
			else{level=SoundMixer68k.soundVol();if((memory.readByte(0x260)&7)!=level)memory.writeByte(0x260,(memory.readByte(0x260)&~7)|level);}
			knownSdVolume=level;}catch(M68kCpuException ignored){}
	}
	/** SetSoundVol through the Sound Driver (csCode 2): SdVolume and the output volume. */
	void setSoundVol(int level){level&=7;SoundMixer68k.setSoundVol(level);try{memory.writeByte(0x260,(memory.readByte(0x260)&~7)|level);}catch(M68kCpuException ignored){}knownSdVolume=level;}
	/** SetDefaultOutputVolume / SetSoundOutputInfo 'volu': the output volume, and SdVolume to match. */
	private void setOutputVolume(int volume){SoundMixer68k.setOutputVolume(volume);knownSdVolume=-1;try{int level=SoundMixer68k.soundVol();memory.writeByte(0x260,(memory.readByte(0x260)&~7)|level);knownSdVolume=level;}catch(M68kCpuException ignored){}}
	private static boolean validSoundResource(byte[] sound){
		if(sound.length<6)return false;int format=u16(sound,0),commandOffset,count;if(format==1){int modifiers=u16(sound,2);long afterModifiers=4L+modifiers*6L;if(afterModifiers+2>sound.length)return false;commandOffset=(int)afterModifiers+2;count=u16(sound,(int)afterModifiers);}
		else if(format==2){commandOffset=6;count=u16(sound,4);}else return false;if((long)commandOffset+count*8L>sound.length)return false;
		for(int i=0;i<count;i++){int offset=commandOffset+i*8,command=u16(sound,offset);if((command&0x3fff)==BUFFER_CMD&&(command&0x8000)!=0){long header=u32(sound,offset+4);if(header+22>sound.length)return false;long sampleLength=u32(sound,(int)header+4),samplePointer=u32(sound,(int)header);if(samplePointer==0&&header+22+sampleLength>sound.length)return false;}}
		return true;
	}
	private void writeResult(M68kCpu cpu,int result) throws M68kCpuException {memory.writeWord(cpu.a[7],result);}
	private static int u16(byte[] data,int offset){return (data[offset]&255)<<8|data[offset+1]&255;}
	private static long u32(byte[] data,int offset){return ((long)u16(data,offset)<<16|u16(data,offset+2))&0xffffffffL;}
	private static void putWord(byte[] data,int offset,int value){data[offset]=(byte)(value>>>8);data[offset+1]=(byte)value;}
	private static void putLong(byte[] data,int offset,int value){putWord(data,offset,value>>>16);putWord(data,offset+2,value);}

	/** _SoundDispatch ($A800): D0 = argument words << 24 | selector << 16 | group (4 = MIDI Manager, 8 = Sound Manager, 0x10 = MACE, 0x14 = input, 0x18 = volume). */
	private boolean soundDispatch(M68kCpu cpu) throws M68kException {
		int d0=cpu.d[0];int argBytes=((d0>>>24)&0xff)*2,selector=(d0>>>16)&0xff,group=d0&0xffff;int sp=cpu.a[7];
		// D0 = 0: the MIDI Manager 1.x glue's convention - its selector is a word on the stack above the arguments and the
		// result space (the MIDI Manager 2.0.1 INIT probes MIDIVersion that way with selector 4, so 1.x numbers run 4 above
		// MIDI.h's: 4 = MIDIVersion, 8 = MIDISignIn...). Popped and served by the same native manager as group 4.
		if(d0==0){int old=memory.readWord(cpu.a[7]);cpu.a[7]+=2;if(midi().dispatch(cpu,old-4))return true;if(Mac68kApi.TRACE)System.err.printf("[68K] SoundDispatch D0=0: unknown 1.x MIDI selector %d%n",old);return true;}
		// A group an extension installed runs in the guest, entered like the real dispatcher does: D0 swapped so that its low
		// word holds the selector (the Speech Manager's routine does ANDI.W #$FF,D0 and jumps through a table of JMPs by it),
		// the arguments and the result space still on the stack; the routine pops its own arguments before it returns.
		Integer installed=installedGroups.get(group);
		// an external's VM loads the Speech Manager at its first call (group $C), as it does at Gestalt('ttsc')
		if(installed==null&&group==SPEECH_GROUP){Toolbox68k tb=macApi.application();if(tb!=null&&tb.app==null&&tb.extensions.loadForExternal(Toolbox68k.SPEECH_MANAGER))installed=installedGroups.get(group);}
		if(installed!=null){if(Mac68kApi.TRACE)System.err.printf("[68K] SoundDispatch $%08X -> installed group $%X routine $%X%n",d0,group,installed);
			cpu.d[0]=(d0<<16)|(d0>>>16);cpu.callLeavingStack(installed);return true;}
		if(group==0){ // the dispatcher's own selectors (Sound Manager 3.0): 4 = InstallDispatch(group, routine) - the Speech Manager
			// extension's INIT used to get siUnknownInfoType written over its routine address, with the arguments left on the
			// stack, and returned into that garbage (unsupported F-line instruction $FF00 at start-up)
			if(selector==4){int routine=memory.readLong(sp),which=memory.readWord(sp+4);cpu.a[7]+=6;
				if(routine!=0)installedGroups.put(which,routine);else installedGroups.remove(which);
				if(Mac68kApi.TRACE)System.err.printf("[68K] SoundDispatch InstallDispatch group $%X routine $%X%n",which,routine);
				memory.writeWord(cpu.a[7],NO_ERR);return true;}
			cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],-220);return true; // siUnknownInfoType
		}
		if(group==8){
			// Apple's Sound.h selectors carry no argument size ($00100008 = SndChannelStatus, $00200008 = SndPlayDoubleBuffer...;
			// only other glue does): the dispatcher's routines pop their own arguments, so the sizes
			// come from here when the high byte is 0. Some SndChannelStatus callers leave their stack 10 bytes short otherwise
			// (it returned into the status record and ran off into unmapped memory on New Game).
			if(argBytes==0)switch(selector){case 0x00:argBytes=26;break;case 0x04:argBytes=4;break;case 0x08:argBytes=6;break;case 0x10:argBytes=10;break;case 0x14:argBytes=6;break;case 0x18:argBytes=4;break;case 0x1c:argBytes=2;break;case 0x20:argBytes=8;break;default:break;}
			switch(selector){
				case 0x0c:{ // SndSoundManagerVersion: 3.6.4 on Mac OS 9 (what QuickTime 4.1 installs; Final Cut Pro requires it), 3.2.1 for System 7.5.5 (Killing Time refuses to run below 3.2.1)
					Toolbox68k tb=macApi.application();cpu.a[7]+=argBytes;memory.writeLong(cpu.a[7],tb!=null&&tb.baseSystemVersion>=0x0900?0x03648000:0x03218000);return true;}
				// SndManagerStatus: SMStatus {smMaxCPULoad, smNumChannels = the channels allocated now, smCurCPULoad}. smNumChannels was
				// always 4: SoundManagerDemo (one channel allowed) refused its first SndNewChannel with "Can only have one channel in the demo."
				case 0x14:{int status=memory.readLong(sp),length=memory.readWord(sp+4);cpu.a[7]+=argBytes;if(status!=0&&length>=6){int n=channels.size();memory.writeWord(status,100);memory.writeWord(status+2,n);memory.writeWord(status+4,Math.min(100,n*10));}memory.writeWord(cpu.a[7],NO_ERR);return true;}
				case 0x10:{int status=memory.readLong(sp),length=memory.readWord(sp+4),channel=memory.readLong(sp+6);cpu.a[7]+=argBytes;ChannelState state=channels.get(channel);
					if(status!=0&&length>=24){memory.fill(status,24,0);boolean busy=false;if(state!=null){synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)if(v.playing())busy=true;}DoubleBufferPlayer p=state.doubleBuffer;if(p!=null&&!p.finished)busy=true;if(state.waiting||!state.pending.isEmpty())busy=true;if(state.synthesizer!=null&&state.synthesizer.busy())busy=true;}memory.writeByte(status+12,busy?1:0);memory.writeByte(status+14,state!=null&&state.paused?1:0);if(Mac68kApi.TRACE)System.err.println("[68K] SndChannelStatus $"+Integer.toHexString(channel)+" busy="+busy+" voices="+(state==null?"-":state.voices.size()));}
					memory.writeWord(cpu.a[7],state==null?BAD_CHANNEL:NO_ERR);return true;} // SndChannelStatus
				case 0x18:{int statePtr=memory.readLong(sp);cpu.a[7]+=argBytes;if(statePtr!=0)memory.writeWord(statePtr,0);memory.writeWord(cpu.a[7],NO_ERR);return true;} // SndGetSysBeepState
				case 0x1c:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],NO_ERR);return true; // SndSetSysBeepState
				// SndPlayDoubleBuffer(chan, header): Apple's Sound.h selector is $00200008 - no argument size in the high byte (some
				// glue carries $0420); the dispatcher itself knows the two pointers, so the caller used to stay 8 bytes off.
				case 0x20:{int header=memory.readLong(sp),channel=memory.readLong(sp+4);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],playDoubleBuffer(channel,header));return true;}
				case 0x00:return startFilePlay(cpu,sp,argBytes);
				case 0x04:{int channel=memory.readLong(sp);cpu.a[7]+=argBytes;ChannelState state=channels.get(channel);if(DEBUG_FILE_PLAY)System.err.println("[68K] SndPauseFilePlay $"+Integer.toHexString(channel));if(state!=null){if(state.paused)resume(state);else pause(state);}memory.writeWord(cpu.a[7],state==null?BAD_CHANNEL:NO_ERR);return true;} // SndPauseFilePlay: toggles
				case 0x08:{boolean quietNow=memory.readByte(sp)!=0;int channel=memory.readLong(sp+2);cpu.a[7]+=argBytes;ChannelState state=channels.get(channel);if(DEBUG_FILE_PLAY)System.err.println("[68K] SndStopFilePlay $"+Integer.toHexString(channel)+" quietNow="+quietNow);
					if(state!=null){state.generation++;stop(state,!quietNow);if(state.synthesizer!=null)state.synthesizer.flush();}memory.writeWord(cpu.a[7],state==null?BAD_CHANNEL:NO_ERR);return true;} // SndStopFilePlay(chan, quietNow)
				default:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],NO_ERR);return true;
			}
		}
		if(group==0x18){ // volumes: the output volume all VMs share (SoundMixer68k.outputVolume), the alert's own level
			switch(selector){
				case 0x24:case 0x2c:{int out=memory.readLong(sp);cpu.a[7]+=argBytes;if(out!=0)memory.writeLong(out,selector==0x24?SoundMixer68k.sysBeepVolume():SoundMixer68k.outputVolume());memory.writeWord(cpu.a[7],NO_ERR);return true;} // GetSysBeepVolume / GetDefaultOutputVolume(*level)
				case 0x28:{int level=memory.readLong(sp);cpu.a[7]+=argBytes;SoundMixer68k.setSysBeepVolume(level);memory.writeWord(cpu.a[7],NO_ERR);return true;} // SetSysBeepVolume(level)
				case 0x30:{int level=memory.readLong(sp);cpu.a[7]+=argBytes;setOutputVolume(level);memory.writeWord(cpu.a[7],NO_ERR);return true;} // SetDefaultOutputVolume(level)
				case 0x48:{int value=memory.readLong(sp),kind=memory.readLong(sp+4);cpu.a[7]+=argBytes; // SetSoundOutputInfo(device, selector, infoPtr): a volume comes as the value itself
					if(kind==0x766f6c75||kind==0x68766f6c)setOutputVolume(value);memory.writeWord(cpu.a[7],NO_ERR);return true;}
				case 0x10:return getCompressionInfo(cpu,sp,argBytes);
				// SndSetInfo(chan, selector, infoPtr): kept for the channel's decompressor - QuickTime 6's sound media handler describes the
				// compressed input with 'dcfg' (an ExtendedSoundComponentData whose extensions are the sound description's 'wave' atoms,
				// which QDesign Music cannot decode without)
				case 0x40:{int info=memory.readLong(sp),kind=memory.readLong(sp+4),channel=memory.readLong(sp+8);cpu.a[7]+=argBytes;
					ChannelState state=channels.get(channel);if(state!=null)state.info.put(kind,info);
					if(state!=null&&kind==GuestSoundDecoder.DCFG&&info!=0)state.decompression=GuestSoundDecoder.Configuration.read(memory,info,state.compressionInfo); // copied: its atoms are QuickTime's
					if(state!=null&&kind==GuestSoundDecoder.CMFA&&info!=0)state.compressionInfo=memory.readBytes(info,20); // the stream's CompressionInfo
					if(SND_TRACE)System.err.println("[snd] SndSetInfo $"+Integer.toHexString(channel)+" '"+ResourceFile68k.typeName(kind)+"' $"+Integer.toHexString(info));
					memory.writeWord(cpu.a[7],state==null?BAD_CHANNEL:NO_ERR);return true;}
				case 0x44:{int info=memory.readLong(sp),kind=memory.readLong(sp+4);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],outputInfo(kind,info));return true;} // GetSoundOutputInfo(device, selector, infoPtr)
				case 0x04:{ // GetSoundHeaderOffset(sndHandle, VAR offset): where the first soundCmd/bufferCmd's header lies in a format 1/2 'snd '
					// (it wrote nothing: Nanosaur added the garbage to *sndHandle and played soundCmd at an unmapped address - badFormat)
					int offsetPtr=memory.readLong(sp),handle=memory.readLong(sp+4);cpu.a[7]+=argBytes;
					byte[] sound=handle==0?null:macApi.handleContents(handle);int offset=sound==null?-1:soundHeaderOffset(sound);
					if(offsetPtr!=0)memory.writeLong(offsetPtr,Math.max(0,offset));memory.writeWord(cpu.a[7],offset<0?BAD_FORMAT:NO_ERR);return true;}
				default:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],NO_ERR);return true;
			}
		}
		if(group==0x10)return mace(cpu,selector,argBytes,sp);
		if(group==0x14){if(input==null)input=new SoundInput68k(memory,macApi);return input.dispatch(cpu,selector,argBytes);} // sound input
		if(group==4){ // the MIDI Manager (MIDI.h: D0 = selector << 16 | 4, no argument size; the manager pops each call's own)
			if(midi().dispatch(cpu,selector))return true;
			if(Mac68kApi.TRACE)System.err.printf("[68K] SoundDispatch: unknown MIDI Manager selector $%X%n",selector);
			cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],MidiManager68k.INVALID_CMD_ERR);return true;}
		// No Speech Manager: its calls that answer a value rather than an OSErr get 0 - SpeechManagerVersion (selector 0, a long)
		// and SpeechBusy / SpeechBusySystemWide ($3C/$40, shorts); -220 in SpeechBusy read as "busy" (SpeakText's isSpeaking
		// was true, a `repeat while` on it never ended)
		if(group==0x0c&&(selector==0||selector==0x3c||selector==0x40)){cpu.a[7]+=argBytes;if(selector==0)memory.writeLong(cpu.a[7],0);else memory.writeWord(cpu.a[7],0);return true;}
		cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],-220);return true; // speech and the rest: siNoSoundInHardware
	}
	private SoundInput68k input;

	/**
	 * GetSoundOutputInfo: the sound output hardware is 16-bit stereo 'twos' at 44.1 kHz, at the output volume, not muted. It wrote
	 * nothing before: iTunes takes the output rate from siHardwareFormat and designed its playback filters for 0 Hz - every
	 * sample came out 0.0 and songs played silent. Other selectors keep answering noErr without data.
	 */
	private int outputInfo(int kind,int info) throws M68kCpuException {
		if(info==0)return NO_ERR;
		switch(kind){
			case 0x6877666d:memory.fill(info,28,0);memory.writeLong(info+4,0x74776f73);memory.writeWord(info+8,2);memory.writeWord(info+10,16); // 'hwfm' SoundComponentData
				memory.writeLong(info+12,0xac440000);return NO_ERR;
			case 0x73726174:memory.writeLong(info,0xac440000);return NO_ERR;  // 'srat' siSampleRate (UnsignedFixed)
			case 0x7373697a:memory.writeWord(info,16);return NO_ERR;          // 'ssiz' siSampleSize
			case 0x6368616e:memory.writeWord(info,2);return NO_ERR;           // 'chan' siNumberChannels
			case 0x766f6c75:case 0x68766f6c:memory.writeLong(info,SoundMixer68k.outputVolume());return NO_ERR; // 'volu' siVolume, 'hvol' siHardwareVolume: the output volume
			case 0x686d7574:case 0x736d7574:memory.writeWord(info,0);return NO_ERR; // 'hmut' siHardwareMute, 'smut' siSpeakerMute
			default:return NO_ERR;
		}
	}

	/**
	 * The MACE group ($10): MACEVersion (0), Comp3to1 (4), Exp1to3 (8), Comp6to1 ($C), Exp1to6 ($10). Sound.h's selectors
	 * carry no argument size, and the four routines take seven longs (inBuffer, outBuffer, cnt, inState, outState,
	 * numChannels, whichChannel; 28 bytes) which the dispatcher pops. They used to stay on the stack: HyperCard's Audio
	 * palette (GCOD "APCompressSound" of the Audio Help stack) then restored its registers from them on Save with
	 * "3分の1" compression and froze. Old glue with INTEGER channel arguments says 12 words in the high byte.
	 * Expansion runs through MaceDecoder, one channel per call (cnt = packets, 6 samples each), the decoder's state kept
	 * from call to call by the outState block's address. There is no MACE encoder (the user's decision): compressing into
	 * the samples of a compressed 'snd ' Handle (HyperCard's Audio palette saving with 3:1 or 6:1) turns that Handle into
	 * a standard uncompressed 'snd ' of the input instead (storeUncompressed); any other output buffer is left alone.
	 */
	private boolean mace(M68kCpu cpu,int selector,int argBytes,int sp) throws M68kCpuException {
		if(selector==0){cpu.a[7]+=argBytes;memory.writeLong(cpu.a[7],0x01000000);return true;} // MACEVersion: NumVersion 1.0
		if(argBytes==0)argBytes=28;
		boolean words=argBytes==24;int p=sp;
		int whichChannel=words?(short)memory.readWord(p):memory.readLong(p);p+=words?2:4;
		int numChannels=words?(short)memory.readWord(p):memory.readLong(p);p+=words?2:4;
		int outState=memory.readLong(p),inState=memory.readLong(p+4),count=memory.readLong(p+8),outBuffer=memory.readLong(p+12),inBuffer=memory.readLong(p+16);
		cpu.a[7]+=argBytes;
		if(Mac68kApi.TRACE)System.err.printf("[68K] MACE selector $%X in=$%X out=$%X cnt=%d inState=$%X outState=$%X channels=%d which=%d%n",selector,inBuffer,outBuffer,count,inState,outState,numChannels,whichChannel);
		if(selector!=8&&selector!=0x10){if(numChannels<=1&&inBuffer!=0&&outBuffer!=0&&count>0&&count<=16*1024*1024)storeUncompressed(inBuffer,outBuffer,count);return true;} // Comp3to1 / Comp6to1
		boolean six=selector==0x10;int channels=Math.max(1,numChannels),channel=Math.max(0,Math.min(channels,whichChannel)-1),packetBytes=six?1:2;
		if(inBuffer==0||outBuffer==0||count<=0||count>16*1024*1024)return true;
		MaceDecoder.Stream stream=inState==0?null:maceStates.remove(inState);
		if(stream==null||stream.six!=six||!continuesState(inState))stream=new MaceDecoder.Stream(six,1);
		byte[] packets=new byte[count*packetBytes];
		for(int i=0;i<count;i++)for(int b=0;b<packetBytes;b++)packets[i*packetBytes+b]=(byte)memory.readByte(inBuffer+(i*channels+channel)*packetBytes+b);
		byte[] samples=stream.decode(packets,0,packets.length);
		if(channels==1)memory.load(outBuffer,samples);else for(int i=0;i<samples.length;i++)memory.writeByte(outBuffer+i*channels+channel,samples[i]);
		if(outState!=0){maceStates.put(outState,stream);memory.writeLong(outState,MACE_STATE_MARK);}
		return true;
	}
	/**
	 * Comp3to1/Comp6to1 into the sample area of a mono compressed sound header ($FE, MACE by compressionID 3/4 or format
	 * 'MAC3'/'MAC6') that a format 1 or 2 'snd ' Handle points at with its first sound/bufferCmd: the Handle is rewritten
	 * as the same resource with a standard sound header (8-bit, the header's rate, base note and loop) and the {@code count}
	 * input samples, the sampled synth's MACE init bits dropped. HyperCard's Audio palette (GCOD APCompressSound) builds
	 * exactly such a Handle, compresses into it and saves it, so its recording is saved uncompressed.
	 */
	private void storeUncompressed(int inBuffer,int outBuffer,int count) throws M68kCpuException {
		for(int headerOffset:new int[]{20,14}){ // format 1 with one synth, format 2
			int start=outBuffer-headerOffset-64,handle=macApi.handleForPointer(start);if(handle==0)continue;
			byte[] snd=macApi.handleContents(handle);if(snd==null||snd.length<headerOffset+64)continue;
			int format=u2(snd,0),commands;
			if(format==1){if(u2(snd,2)!=1)continue;commands=4+6+2;}else if(format==2)commands=6;else continue;
			if(commands!=headerOffset-8||u2(snd,commands-2)<1)continue;
			int command=u2(snd,commands);if((command&0x8000)==0||((command&0x7fff)!=0x50&&(command&0x7fff)!=0x51)||u4(snd,commands+4)!=headerOffset)continue;
			int h=headerOffset;int compressionID=(short)u2(snd,h+56),formatCode=u4(snd,h+40);
			boolean mace=compressionID==3||compressionID==4||compressionID==-1&&(formatCode==0x4d414333||formatCode==0x4d414336);
			if((snd[h+20]&0xff)!=0xfe||!mace||u4(snd,h+4)!=1)continue;
			byte[] out=new byte[h+22+count];System.arraycopy(snd,0,out,0,h);
			if(format==1)putLong(out,6,u4(snd,6)&~0x0700); // initMACE3/initMACE6 off, initMono etc. kept
			putLong(out,h,0);putLong(out,h+4,count);putLong(out,h+8,u4(snd,h+8));putLong(out,h+12,u4(snd,h+12));putLong(out,h+16,u4(snd,h+16));out[h+20]=0;out[h+21]=snd[h+21];
			System.arraycopy(memory.readBytes(inBuffer,count),0,out,h+22,count);
			macApi.resizeHandle(handle,out.length);memory.load(memory.readLong(handle),out);
			if(Mac68kApi.TRACE)System.err.printf("[68K] MACE compression not offered: 'snd ' Handle $%X stored uncompressed (%d samples)%n",handle,count);
			return;
		}
		if(Mac68kApi.TRACE)System.err.printf("[68K] MACE compression not offered: output $%X left alone%n",outBuffer);
	}
	private static int u2(byte[] b,int o){return (b[o]&0xff)<<8|(b[o+1]&0xff);}
	private static int u4(byte[] b,int o){return u2(b,o)<<16|u2(b,o+2);}

	/** Expansion state by StateBlock address; the block's first long marks it as continued (a zeroed block starts afresh). */
	private final Map<Integer,MaceDecoder.Stream> maceStates=new HashMap<Integer,MaceDecoder.Stream>();
	private static final int MACE_STATE_MARK=0x685a4d43; // 'hZMC'
	private boolean continuesState(int block) throws M68kCpuException {return memory.readLong(block)==MACE_STATE_MARK;}
	private MidiManager68k midi;
	/** The MIDI Manager, made at its first use (a program that never touches it costs nothing). */
	MidiManager68k midi(){if(midi==null)midi=new MidiManager68k(memory,macApi);return midi;}
	/** The MIDI Manager when a program has used it, else null. */
	MidiManager68k midiIfAny(){return midi;}

	/**
	 * GetCompressionInfo(compressionID, format, numChannels, sampleSize, CompressionInfo *): the packet geometry of a sound
	 * format - what QuickTime's sound media handler asks before it plays a track. Formats without a decoder here get
	 * siInvalidCompression (-223), as a Sound Manager without the matching 'sdec' component answers; a zero-filled record
	 * with noErr made the handler retry forever instead of giving the track up.
	 */
	private boolean getCompressionInfo(M68kCpu cpu,int sp,int argBytes) throws M68kCpuException {
		int info=memory.readLong(sp),sampleSize=memory.readWord(sp+4),channels=memory.readWord(sp+6),format=memory.readLong(sp+8),compressionID=(short)memory.readWord(sp+12);cpu.a[7]+=argBytes;
		int samplesPerPacket,bytesPerPacket,bytesPerSample=Math.max(1,sampleSize/8);
		switch(compressionID==0?0x4e4f4e45:format){
			case 0x4e4f4e45:case 0x72617720:case 0x74776f73:case 0x736f7774:case 0x666c3332:case 0x666c3634:case 0x696e3234:case 0x696e3332: // NONE raw twos sowt fl32 fl64 in24 in32
				if(format==0x666c3332)bytesPerSample=4;else if(format==0x666c3634)bytesPerSample=8;else if(format==0x696e3234)bytesPerSample=3;else if(format==0x696e3332)bytesPerSample=4;
				samplesPerPacket=1;bytesPerPacket=bytesPerSample;break;
			case 0x4d414333:samplesPerPacket=6;bytesPerPacket=2;bytesPerSample=1;break; // MAC3
			case 0x4d414336:samplesPerPacket=6;bytesPerPacket=1;bytesPerSample=1;break; // MAC6
			case 0x696d6134:samplesPerPacket=64;bytesPerPacket=34;bytesPerSample=2;break; // ima4
			case 0x756c6177:case 0x616c6177:samplesPerPacket=1;bytesPerPacket=1;bytesPerSample=2;break; // ulaw alaw: 16-bit samples once expanded (bytesPerSample is the decompressed size; 1 made SoundApp show twice the length)
			default:memory.writeWord(cpu.a[7],-223);return true; // siInvalidCompression
		}
		// Sound.h: recordSize.l, format.l, compressionID.w, samplesPerPacket.w, bytesPerPacket.w, bytesPerFrame.w, bytesPerSample.w,
		// futureUse1.w (20 bytes). recordSize used to be written as a word, which shifted every field by two bytes: QuickTime
		// read bytesPerPacket as samplesPerPacket and played half of each 16-bit or 8-bit stereo chunk (tools/qt/QtAudioOracle)
		if(info!=0){memory.writeLong(info,20);memory.writeLong(info+4,format);memory.writeWord(info+8,compressionID);memory.writeWord(info+10,samplesPerPacket);
			memory.writeWord(info+12,bytesPerPacket);memory.writeWord(info+14,bytesPerPacket*Math.max(1,channels));memory.writeWord(info+16,bytesPerSample);memory.writeWord(info+18,0);}
		memory.writeWord(cpu.a[7],NO_ERR);return true;
	}

	/**
	 * SndStartFilePlay(chan, fRefNum, resNum, bufferSize, theBuffer, theSelection, theCompletion, async): an AIFF/AIFC file
	 * open under fRefNum plays on the channel (a private one when chan is NIL, then always synchronously). The completion
	 * routine (pascal proc(chan)) runs when the sound ends. fRefNum 0 is badFormat whatever resNum names, as Sound Manager 3.3
	 * answered in Basilisk II (Nov 4 2026, SFPTest): resNum 0, a missing 'snd ' and the System's 'snd ' 1 alike, nothing
	 * played - IM Sound's 'snd ' resource play by resNum is not there in 3.x. Active Lancer Demo's classic music path
	 * passes fRefNum 0 and stops at that error on a real Mac too.
	 */
	private boolean startFilePlay(M68kCpu cpu,int sp,int argBytes) throws M68kCpuException {
		boolean async=memory.readByte(sp)!=0;int completion=memory.readLong(sp+2),resNum=(short)memory.readWord(sp+18),fRefNum=(short)memory.readWord(sp+20),channel=memory.readLong(sp+22);cpu.a[7]+=argBytes;
		ChannelState state=channel==0?null:channels.get(channel);int result;
		if(channel!=0&&state==null)result=BAD_CHANNEL;
		// a channel still playing refuses a new file play with channelBusy (IM Sound): 三國志英傑伝 restarts its BGM from its idle
		// loop and relies on that answer until the sound ends - every call used to decode the 1.3 MB sound again (Java heap OOM)
		// ...but a different file replaces the one playing: Bugdom starts each level's song on its music channel without stopping
		// the menu song (it reopens the files, so the refNum is the same - the file behind it is not)
		else if(state!=null&&channelBusy(state)&&!replacesFilePlay(state,fRefNum)){result=CHANNEL_BUSY;
			if(DEBUG_FILE_PLAY){int n;synchronized(state.voices){n=state.voices.size();}System.err.println("[68K]   busy: voices "+n+" doubleBuffer "+(state.doubleBuffer!=null&&!state.doubleBuffer.finished)+" synthesizer "+(state.synthesizer!=null&&state.synthesizer.busy()));}}
		else if(fRefNum!=0){
			FileManager68k files=macApi.fileManager();long length=files.referenceLength(fRefNum);byte[] data=null;
			try{if(length>0&&length<=64L*1024*1024)data=files.readReference(fRefNum,0,(int)length);}catch(IOException e){data=null;}
			FileManager68k.ForkState fork=files.forkState(fRefNum);if(state!=null){state.filePlaying=fork==null?null:fork.path;state.fileOpen=files.openFile(fRefNum);}
			if(data==null)result=-51; // rfNumErr
			else if(!TSound.use)result=NO_ERR;
			else{AudioInputStream stream=null;
				try{stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));}
				catch(UnsupportedAudioFileException unsupported){try{stream=MaceConverter.open(data);}catch(IOException e){stream=null;}if(stream==null)stream=MaceConverter.openIma4(data);}catch(IOException e){stream=null;}
				result=stream==null?BAD_FORMAT:startClip(state,stream,channel!=0&&async);}
		}else result=BAD_FORMAT;
		if(completion!=0&&result==NO_ERR)fileCompletion(state,channel,completion,channel!=0&&async);
		if(Mac68kApi.TRACE||DEBUG_FILE_PLAY||result!=NO_ERR)System.err.println("[68K] SndStartFilePlay channel $"+Integer.toHexString(channel)+" fRefNum "+fRefNum+" resNum "+resNum+" -> "+result);
		writeResult(cpu,result);return true;
	}
	private static final int CHANNEL_BUSY=-209;
	/** Whether a file play on a busy channel is of another file than the one playing there (the old play then stops). */
	private boolean replacesFilePlay(ChannelState state,int fRefNum){
		FileManager68k.ForkState fork=fRefNum==0?null:macApi.fileManager().forkState(fRefNum);
		if(DEBUG_FILE_PLAY)System.err.println("[68K]   playing "+state.filePlaying+" new "+(fork==null?null:fork.path)+" same open "+(macApi.fileManager().openFile(fRefNum)==state.fileOpen));
		if(fRefNum==0||state.filePlaying==null)return false;
		// the same file still open: busy; another file, or the file closed since (the real Sound Manager streamed from it: its play ended)
		if(fork==null||fork.path==null||fork.path.equals(state.filePlaying)&&macApi.fileManager().openFile(fRefNum)==state.fileOpen)return false;
		state.generation++;stop(state,false);return true;
	}
	/** -Dhz68k.sndtrace=true: channels, commands, double buffers and callbacks only (the full trace is too slow for streaming). */
	private static final boolean SND_TRACE=Boolean.getBoolean("hz68k.sndtrace");
	private static final boolean DEBUG_FILE_PLAY=Boolean.getBoolean("hz68k.fileplaytrace");
	/** Whether the channel is playing anything (the scChannelBusy answer of SndChannelStatus). */
	private boolean channelBusy(ChannelState state){
		synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)if(v.playing())return true;}
		DoubleBufferPlayer p=state.doubleBuffer;if(p!=null&&!p.finished)return true;
		return state.synthesizer!=null&&state.synthesizer.busy();
	}
	/** The file-play completion routine: right away after a synchronous play, else when the channel falls silent. */
	private void fileCompletion(final ChannelState state,final int channel,final int proc,boolean async){
		final Runnable call=new Runnable(){@Override public void run(){try{
			Toolbox68k tb=macApi.application();M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushLong(channel);
			try{cpu.callPascal(proc,4);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
		}catch(M68kException e){throw Toolbox68k.wrap(e);}}};
		if(!async||state==null){call.run();return;} // synchronous: the sound is over (a deferred guest call would need the loop)
		final int generation=state.generation;
		Thread t=new Thread(new Runnable(){@Override public void run(){
			// a voice the mixer has not started yet counts too (it leaves the list when it ends): the completion used to run at once
			// when the mixer was a moment late, and Bugdom, which loops its music from the completion, restarted the song on a busy channel
			while(state.generation==generation){boolean busy=false;synchronized(state.voices){busy=!state.voices.isEmpty();}
				if(state.synthesizer!=null&&state.synthesizer.busy())busy=true;if(!busy)break;try{Thread.sleep(20);}catch(InterruptedException e){return;}}
			// stopped (SndStopFilePlay, another play): no completion - Bugdom loops its music from it and restarted the stopped song
			if(state.generation!=generation)return;
			macApi.application().later(call);}},"68K file play completion");
		t.setDaemon(true);t.start();
	}

	static String name(int trap){switch(trap){case 0x800:return "SoundDispatch";case 0x801:return "SndDisposeChannel";case 0x803:return "SndDoCommand";case 0x804:return "SndDoImmediate";case 0x805:return "SndPlay";case 0x806:return "SndControl";case 0x802:return "SndAddModifier";case 0x807:return "SndNewChannel";default:return null;}}


	// ------------------------------------------------------------------ SndPlayDoubleBuffer

	/**
	 * SndPlayDoubleBuffer(chan, theParams): streams two application-owned buffers through a
	 * SourceDataLine. Each time a buffer has been handed to the line, the doubleback procedure
	 * is run on the emulation thread (like the interrupt-time callback of the real Sound Manager)
	 * to refill it. Tracker players used by many games rely on this.
	 * Header: numChannels.w sampleSize.w compressionID.w packetSize.w sampleRate.l bufferPtr[2].l doubleBack.l (format.l when compressionID = -1).
	 * Buffer: numFrames.l flags.l userInfo[2].l soundData...
	 */
	private int playDoubleBuffer(int channel,int header) throws M68kCpuException {
		ChannelState state=channels.get(channel);if(state==null)return BAD_CHANNEL;if(header==0)return BAD_FORMAT;
		int numChannels=memory.readWord(header),sampleSize=memory.readWord(header+2),compression=(short)memory.readWord(header+4);
		double rate=Integer.toUnsignedLong(memory.readLong(header+8))/65536.0;int buffer0=memory.readLong(header+12),buffer1=memory.readLong(header+16),doubleBack=memory.readLong(header+20);
		boolean signed=sampleSize==16,littleEndian=false;
		if(compression==-1){int format=memory.readLong(header+24);if(format==0x74776f73)signed=true;else if(format==0x736f7774){signed=true;littleEndian=true;}else if(format!=0x72617720&&format!=0x4e4f4e45)return BAD_FORMAT;} // 'twos' 'sowt' 'raw ' 'NONE'
		else if(compression!=0)return BAD_FORMAT;
		if((numChannels!=1&&numChannels!=2)||(sampleSize!=8&&sampleSize!=16)||buffer0==0||buffer1==0||rate<1000||rate>96000)return BAD_FORMAT;
		stopDoubleBuffer(state);
		DoubleBufferPlayer player=new DoubleBufferPlayer(state,new int[]{buffer0,buffer1},doubleBack,new AudioFormat((float)rate,sampleSize,numChannels,signed,!littleEndian));
		state.doubleBuffer=player;if(SND_TRACE)System.err.println("[snd] SndPlayDoubleBuffer $"+Integer.toHexString(channel)+" "+rate+" Hz "+sampleSize+"-bit "+numChannels+" ch compression "+compression+" doubleBack $"+Integer.toHexString(doubleBack)+" buffers $"+Integer.toHexString(buffer0)+" $"+Integer.toHexString(buffer1));
		for(int i=0;i<2;i++)player.copyIfReady(i); // the caller filled both buffers before starting
		if(TSound.use){Thread t=new Thread(player,"68K double buffer");t.setDaemon(true);t.start();}
		else player.finished=true;
		return NO_ERR;
	}

	private void stopDoubleBuffer(ChannelState state){DoubleBufferPlayer p=state.doubleBuffer;if(p!=null){p.stop();state.doubleBuffer=null;}}
	/**
	 * Silences every channel at once (application exit, an external's VM ending): the sampled sounds playing, the ones
	 * linked or queued behind them, double buffers and synthesizers. A program that quits without SndDisposeChannel
	 * (most of them: ExitToShell disposes the channels in the real Sound Manager) used to leave its music playing in the
	 * mixer after its windows had gone - only the streaming players were stopped.
	 */
	void shutDown(){shutDown(false);}
	/** With release (an external's VM ending as its stack closes) the sampled sounds playing get the closed stack's grace
	 *  (SoundMixer68k.release) instead of stopping at once; the rest still stops now. */
	void shutDown(boolean release){
		for(ChannelState state:new ArrayList<ChannelState>(channels.values())){
			clearPending(state);state.waitToken++;state.waiting=false;state.generation++;
			stopDoubleBuffer(state);if(release)release(state);else stop(state,false);if(state.synthesizer!=null)state.synthesizer.stop();}
		channels.clear();
		if(input!=null)input.shutDown();if(midi!=null)midi.shutDown();}
	/** Whether a channel is playing or has commands queued: an external that returns with its sound still going keeps its VM. */
	boolean sounding(){
		for(ChannelState state:new ArrayList<ChannelState>(channels.values())){
			if(!state.pending.isEmpty()||state.doubleBuffer!=null||(state.synthesizer!=null&&state.synthesizer.busy()))return true;
			synchronized(state.voices){for(SoundMixer68k.Voice v:state.voices)if(!v.ended&&!v.stopped)return true;}}
		return false;}

	/** The Sound Manager's hardware interrupt period (512 frames at 22.05 kHz): an empty double buffer is handed back after it. */
	private static final long EMPTY_BUFFER_MS=23;
	/** Debug: -Dhz68k.dbcapture=PREFIX writes every double buffer's samples, as the program made them, to PREFIX-<channel>.raw. */
	private static final String DB_CAPTURE=System.getProperty("hz68k.dbcapture");
	private final class DoubleBufferPlayer implements Runnable {
		private java.io.OutputStream dbCapture;
		final ChannelState state;final int[] buffers;final int doubleBack;final AudioFormat format;final int frameBytes;
		private final byte[][] chunks=new byte[2][];private final boolean[] last=new boolean[2];
		volatile boolean stopped,finished,paused;volatile SourceDataLine line;
		DoubleBufferPlayer(ChannelState state,int[] buffers,int doubleBack,AudioFormat format){this.state=state;this.buffers=buffers;this.doubleBack=doubleBack;this.format=format;frameBytes=format.getFrameSize();}

		/** Emulation thread: takes the buffer's samples when the application has flagged it ready. */
		synchronized boolean copyIfReady(int i) throws M68kCpuException {
			if(chunks[i]!=null)return true;int buffer=buffers[i],flags=memory.readLong(buffer+4);if((flags&DB_BUFFER_READY)==0)return false;
			int frames=memory.readLong(buffer);if(frames<0)frames=0;long bytes=(long)frames*frameBytes;if(bytes>16*1024*1024)bytes=16*1024*1024;
			byte[] data=memory.readBytes(buffer+16,(int)bytes);
			java.util.function.Consumer<Feed> t=feedTap;
			if(t!=null)t.accept(new Feed(state.channel,-1,format.getSampleRate(),format.getChannels(),format.getSampleSizeInBits(),0,format.getEncoding()==AudioFormat.Encoding.PCM_SIGNED?(format.isBigEndian()?0x74776f73:0x736f7774):0x72617720,frames,data));
			memory.writeLong(buffer+4,flags&~DB_BUFFER_READY);
			if(DB_CAPTURE!=null)synchronized(DB_CAPTURE){try{if(dbCapture==null)dbCapture=new java.io.FileOutputStream(DB_CAPTURE+"-"+Integer.toHexString(state.channel)+".raw");dbCapture.write(data);}catch(java.io.IOException e){}}
			chunks[i]=data;last[i]=(flags&DB_LAST_BUFFER)!=0;notifyAll();if(Mac68kApi.TRACE||SND_TRACE)System.err.println("[68K] double buffer $"+Integer.toHexString(state.channel)+" "+i+": "+frames+" frames"+(last[i]?" (last)":"")+(SND_TRACE?" peak "+peak(data,format):""));return true;
		}
		/** -Dhz68k.sndtrace: the largest sample of a buffer (0 = silence), to tell a running stream from a silent one. */
		private int peak(byte[] data,AudioFormat f){int max=0;boolean wide=f.getSampleSizeInBits()==16,signed=f.getEncoding()==AudioFormat.Encoding.PCM_SIGNED;
			for(int k=0;k+(wide?1:0)<data.length;k+=wide?2:1){int v=wide?(short)(f.isBigEndian()?(data[k]<<8|data[k+1]&0xff):(data[k+1]<<8|data[k]&0xff)):signed?data[k]<<8:((data[k]&0xff)-128)<<8;max=Math.max(max,Math.abs(v));}
			return max;}
		/** Emulation thread: the buffer has been consumed; ask the application to refill it (doubleBack(chan, exitingBuffer)). */
		void refill(int i) throws M68kException {
			if(stopped||state.doubleBuffer!=this)return;
			Toolbox68k tb=macApi.application();M68kCpu cpu=tb.cpu;
			if(doubleBack!=0){int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;cpu.a[7]-=Toolbox68k.INTERRUPT_STACK_SLACK;cpu.pushLong(state.channel);cpu.pushLong(buffers[i]);
				try{cpu.callPascal(doubleBack,8);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}}
			copyIfReady(i);
		}
		private void post(final int i,final boolean callBack){
			macApi.application().later(new Runnable(){@Override public void run(){try{if(callBack)refill(i);else copyIfReady(i);}catch(M68kException e){throw Toolbox68k.wrap(e);}}});
		}
		private synchronized byte[] take(int i,boolean[] isLast){
			long lastRequest=0;
			while(chunks[i]==null&&!stopped){
				long now=System.nanoTime();if(now-lastRequest>4_000_000L){post(i,false);lastRequest=now;} // the application may set dbBufferReady outside the callback
				try{wait(4);}catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
			}
			if(stopped)return null;byte[] data=chunks[i];chunks[i]=null;isLast[0]=last[i];return data;
		}
		void pause(boolean on){paused=on;SourceDataLine l=line;if(l==null)return;if(on)l.stop();else l.start();}
		/** Only flags the player: the player thread stops its own line. Stopping it from the emulation thread spun forever inside
		 *  DirectAudioDevice.nStop while the player thread was blocked in write() (Snavely! restarting its stream, macOS JDK 26). */
		void stop(){stopped=true;synchronized(this){notifyAll();}}

		@Override public void run(){
			SourceDataLine l=null;
			try{
				// the line plays the program's format, or 16-bit at the mixer's rate when CoreAudio cannot be given the program's rate
				// (11127.27 Hz broke the host's audio after a minute: HostSampleRate)
				HostSampleRate.Stream resampler=HostSampleRate.plain(format.getSampleRate())?null:new HostSampleRate.Stream(format);
				AudioFormat lineFormat=resampler==null?format:resampler.output();int lineFrameBytes=lineFormat.getFrameSize();
				int chunk=Math.max(chunks[0]==null?0:chunks[0].length,chunks[1]==null?0:chunks[1].length);if(resampler!=null)chunk=resampler.outputBytes(chunk);
				int bufferBytes=Math.max(chunk*2,(int)(lineFormat.getFrameRate()*lineFrameBytes/5)); // two buffers, at least 200 ms
				l=AudioSystem.getSourceDataLine(lineFormat);l.open(lineFormat,bufferBytes);line=l;applyVolume(state,l);if(!paused)l.start();
				if(Mac68kApi.TRACE)System.err.println("[68K] double buffer: "+format+(resampler==null?"":" as "+lineFormat)+" chunk="+chunk+" line="+l.getBufferSize());
				boolean[] isLast=new boolean[1];int underruns=0;long written=0;int outputVolume=SoundMixer68k.outputVolume();
				// the stream's own clock: when the bytes written so far are due to have played. A line that takes samples faster than
				// they play must not run the stream ahead of it: with the audio device stalled (another line's stop hanging in
				// DirectAudioDevice.nStop) every write returned at once, Cythera's doubleBack mixed ~9000 buffers a second and the game
				// behind it barely moved
				double bytesPerNano=format.getFrameRate()*frameBytes/1e9;long lineNanos=(long)(l.getBufferSize()/(lineFormat.getFrameRate()*lineFrameBytes/1e9)),clockStart=System.nanoTime();
				for(int i=0;!stopped;i^=1){
					byte[] data=take(i,isLast);if(data==null)break;
					if(outputVolume!=SoundMixer68k.outputVolume()){outputVolume=SoundMixer68k.outputVolume();applyVolume(state,l);} // SetSoundVol while it streams
					if(written>0&&!paused&&l.available()>=l.getBufferSize()){underruns++;if(Mac68kApi.TRACE||SND_TRACE)System.err.println("[68K] double buffer $"+Integer.toHexString(state.channel)+" underrun #"+underruns+" after "+written+" bytes");}
					written+=data.length;
					byte[] out=resampler==null?data:resampler.convert(data);
					int done=0;while(done<out.length&&!stopped)done+=l.write(out,done,out.length-done);
					if(isLast[0])break;
					long now=System.nanoTime(),due=clockStart+(long)(written/bytesPerNano);
					if(paused||due<now-lineNanos)clockStart=now-(long)(written/bytesPerNano); // paused or fallen behind: the clock goes on from now
					else if(due-lineNanos>now)try{Thread.sleep(Math.max(1,(due-lineNanos-now)/1_000_000L));}catch(InterruptedException e){break;}
					// An empty buffer (Shockwave Audio's decoder has nothing yet) is handed back no faster than the hardware's
					// interrupt period, as the real Sound Manager does; at once, two streams posted ~36000 refills a second
					// and starved the emulation thread the decoder and the movie run on
					if(data.length==0)try{Thread.sleep(EMPTY_BUFFER_MS);}catch(InterruptedException e){break;}
					post(i,true);
				}
				if(!stopped)l.drain();else{try{l.stop();l.flush();}catch(RuntimeException ignored){}}
			}catch(LineUnavailableException|IllegalArgumentException unavailable){if(Mac68kApi.TRACE)System.err.println("[68K] double buffer: "+unavailable);
			}finally{finished=true;if(SND_TRACE)System.err.println("[snd] double buffer $"+Integer.toHexString(state.channel)+" ended"+(stopped?" (stopped)":""));if(l!=null)try{l.close();}catch(RuntimeException ignored){}if(state.doubleBuffer==this)state.doubleBuffer=null;
				if(!state.pending.isEmpty())drainLater(state);} // the commands queued behind the stream (its callBackCmd)
		}
	}

	/** Test hook: the sampled instrument soundCmd installed on a channel, or null. */
	SoundSynth68k.Instrument instrumentOf(int channel){ChannelState state=channels.get(channel);return state==null||state.synthesizer==null?null:state.synthesizer.instrument();}

	private static final class ChannelState {
		final int channel,synth,init,callback;final List<SoundMixer68k.Voice> voices=new ArrayList<SoundMixer68k.Voice>();int amplitude=255,volume=0x01000100;boolean paused;volatile DoubleBufferPlayer doubleBuffer;
		/** The MACE decompressor of the sound playing (continueCmd buffers go on from its state). */
		MaceDecoder.Stream mace;
		/** The installed 'sdec' component expanding the channel's sound (a format no native decoder knows); its state runs on likewise. */
		GuestSoundDecoder guest;
		/** The decompression configuration SndSetInfo 'dcfg' gave the channel (format, rate and the sound description's atoms). */
		GuestSoundDecoder.Configuration decompression;byte[] compressionInfo;
		/** What SndSetInfo gave the channel, by selector. */
		final java.util.Map<Integer,Integer> info=new java.util.HashMap<Integer,Integer>();
		/** The byte count of the scheduled sound being played (its extended header), 0 when it gave none. */
		int bufferSize;
		/** The file of the last SndStartFilePlay on the channel. */
		java.io.File filePlaying;Object fileOpen;
		/** The square-wave/wave-table synthesizer once a note command arrived; the rateCmd multiplier (Fixed) for sampled sounds. */
		SoundSynth68k synthesizer;int rate=0x10000;
		/** The sample rate of the channel's last soundCmd header: bufferCmd headers with a zero sampleRate play at it. */
		double formatRate;
		/** Bumped by quietCmd/flushCmd: callBackCmds queued before are dropped. */
		volatile int generation;
		/** The sound most recently started on the channel: a callBackCmd waits for it. */
		volatile SoundMixer68k.Voice lastQueued;
		/** The channel's callBack is running (emulation thread): a bufferCmd it issues follows the buffer still finishing. */
		boolean inCallBack;
		/** The last sound started or linked on the channel: the next queued bufferCmd is linked behind it. */
		SoundMixer68k.Voice tail;
		/** Queue entries (by identity) of bufferCmds decoded and linked ahead of time, and their voices. */
		final java.util.IdentityHashMap<byte[],SoundMixer68k.Voice> prelinked=new java.util.IdentityHashMap<byte[],SoundMixer68k.Voice>();
		/** SndDoCommand's commands waiting behind a playing buffer or a waitCmd (8 bytes each, copied when queued). */
		final java.util.ArrayDeque<byte[]> pending=new java.util.ArrayDeque<byte[]>();
		/** A waitCmd is running; waitToken tells its timer whether a quietCmd/flushCmd cancelled it. */
		volatile boolean waiting;volatile int waitToken;boolean draining;
		/** callBacks of drained callBackCmds that have not run yet: the queue waits for them (see {@link SoundManager68k#drain}). */
		int callBacksDue;
		ChannelState(int channel,int synth,int init,int callback){this.channel=channel;this.synth=synth;this.init=init;this.callback=callback;}
	}
}

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;


public class TSound {
	/** Fade-out at a note cut short and at play stop: long enough not to click, short enough not to be heard as one. */
	static final double FADE_SECONDS = 0.005;
	/** Voices started or linked behind each other in the mixer, oldest (playing) first, with their sound names. */
	private final ArrayList<SoundMixer68k.Voice> queue = new ArrayList<SoundMixer68k.Voice>();
    private final ArrayList<String> names = new ArrayList<String>();
    public volatile String name = "";
	static boolean use = true;
    
	static int sampleRateForNote(float originalRate, int noteCode) {
		double rate = originalRate * Math.pow(2.0, (noteCode - 48) / 12.0);
		if(rate < 1.0) return 1;
		if(rate > Integer.MAX_VALUE) return Integer.MAX_VALUE;
		return (int)rate;
	}

    /** 直前の Play が false を返した理由(the result 用): "Couldn't load sound" / "Sound is off" */
    public String lastError = "";

    public boolean Play(OStack stack, String soundRsrc, String[] neiro, int tempo, int vol)
	throws xTalkException {
    	lastError = "";
    	if(!use){ lastError = "Sound is off"; return false; }
    	
		//長さ(秒)
		double length = noteSeconds(neiro, tempo);

		AudioInputStream sound = null;
		SoundMixer68k.Voice cached = null;
		String soundPath = null;
			try {
				String path = soundPath(stack, soundRsrc);
				if(path == null) throw new FileNotFoundException(soundRsrc);
				soundPath = path;
				cached = cachedDecode(path);
				if(cached == null) sound = openAudioInputStream(new File(path/*stack.file.getParent()+File.separatorChar+fileName*/));
		} catch (UnsupportedAudioFileException e) {
			//throw new xTalkException("Unsupported audio file for sound \"{0}\"", soundRsrc);
			lastError = "Couldn't load sound";
			return false;
		} catch (Exception e) {
			lastDecodedPath = null; cached = null; //the built-in sound below is not the file looked up above
			/*String path = (PCARDFrame.pc.stack.file.getParent()+File.separatorChar+"resource"+File.separatorChar+soundRsrc);
			try {
	    		sound = AudioSystem.getAudioInputStream(new File(path+".wav"));
			} catch (Exception e2) {
				try {
					sound = AudioSystem.getAudioInputStream(new File(path+".aiff"));
				} catch (Exception e3) {*/
					String cpath = ("resource"+File.separatorChar+soundRsrc);
					soundPath = cpath+".wav";
					try {
						cached = cachedDecode(cpath+".wav"); //HyperCard's own sounds (boing, harpsichord)
						if(cached == null) sound = openAudioInputStream(new File(cpath+".wav"));
					} catch (FileNotFoundException e4) {
						// HyperCard continues without an error but sets the result to "Couldn't load sound".
						lastError = "Couldn't load sound";
						return false;
					} catch (IOException e4) {
						throw new xTalkException("Cannot open sound \"{0}\"", soundRsrc);
					} catch (UnsupportedAudioFileException e4) {
						throw new xTalkException("Unsupported audio file for sound \"{0}\"", soundRsrc);
					}
				//}
			//}
		}
		
		//音符はミキサー(SoundMixer68k)の1本の出力ラインへ隙間なく連結する。以前は音符ごとに Clip を
		//開いて start/stop していた(close もしない)ため、音の終わりや play stop のたびにぷつっと鳴っていた
		SoundMixer68k mixer = SoundMixer68k.get();
		SoundMixer68k.Voice decoded;
		if(cached != null){
			//a fresh voice over the cached samples: each queued note keeps its own play position
			decoded = new SoundMixer68k.Voice(cached.left, cached.right, cached.sourceRate, 1);
		}else{
			decoded = mixer.decode(sound, 1);
			if(decoded == null){ lastError = "Couldn't load sound"; return false; }
			if(lastDecodedPath != null) rememberDecode(lastDecodedPath, decoded);
		}
		lastDecodedPath = null;
		SoundMixer68k.Voice voice;
		if(neiro[1].equals("") && length==0.0){
			voice = decoded;
		}else{
			int code = 0;
			if(neiro[1].equalsIgnoreCase("c")) code = 0;
			else if(neiro[1].equalsIgnoreCase("d")) code = 2;
			else if(neiro[1].equalsIgnoreCase("e")) code = 4;
			else if(neiro[1].equalsIgnoreCase("f")) code = 5;
			else if(neiro[1].equalsIgnoreCase("g")) code = 7;
			else if(neiro[1].equalsIgnoreCase("a")) code = 9;
			else if(neiro[1].equalsIgnoreCase("b")) code = 11;
			if(neiro[2].equals("#")) code += 1;
			if(neiro[2].equals("b")) code -= 1;
			if(neiro[3].equals("")) code += 48;
			else code += 12*Integer.valueOf(neiro[3]);
			//c4 is MIDI note 60, played against the sound's base note as the Sound Manager does (HyperCard J-2.3 measured:
			//c4 → freqDurationCmd 60 on its Harpsichord, base note 67). code+12 is the MIDI note.
			NoteInfo info = noteInfo(soundPath, soundRsrc);
			double pitch = Math.pow(2.0, (code + 12 - info.baseNote) / 12.0);
			boolean rest = neiro[1].equalsIgnoreCase("r");
			int frames = decoded.frameLength();
			//長さ(元のサンプルのフレーム数で。高い音ほど速く読み進む)
			if(length!=0.0 || rest) frames = (int)Math.round(length*decoded.sourceRate*pitch);
			boolean loops = !rest && info.loops(decoded.frameLength());
			short[][] note = noteSamples(decoded.left, decoded.right, rest ? 0 : decoded.frameLength(), frames,
					(int)Math.round(FADE_SECONDS*decoded.sourceRate*pitch), loops ? info.loopStart : 0, loops ? info.loopEnd : 0);
			voice = new SoundMixer68k.Voice(note[0], note[1], decoded.sourceRate, pitch);
		}
		if(vol != 100) voice.gainLeft = voice.gainRight = Math.max(0f, vol/100f);
		enqueue(voice, soundRsrc);
				return true;
    }

	/**
	 * The file of the 'snd ' named {@code name} in the stack or a stack in use; null when there is none. By name, as
	 * HyperCard's GetNamedResource: the ID of a Home sound can belong to another sound of this stack (秘密結社ゲーム's
	 * play "Boing" found Home's snd 129 and then played the stack's own snd 129, "ゲーム").
	 */
	static String soundPath(OStack stack, String name) {
		return stack==null || stack.rsrc==null ? null : stack.rsrc.getFilePathAll(name, "SND");
	}

	/**
	 * Decoded sounds by file (path and modification time): a script that plays the same sound over and over
	 * (WARASIBE's title loops "play loop_1" until a click) decoded the 'snd ' resource for every play — most of a CPU.
	 */
	private static final java.util.LinkedHashMap<String,SoundMixer68k.Voice> decodedCache =
		new java.util.LinkedHashMap<String,SoundMixer68k.Voice>(16, 0.75f, true){
			@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,SoundMixer68k.Voice> e){ return size() > 24; }
		};
	private String lastDecodedPath;

	private static String decodeKey(String path){
		File f = new File(path);
		return f.isFile() ? f.getAbsolutePath()+"|"+f.lastModified()+"|"+f.length() : null;
	}

	private SoundMixer68k.Voice cachedDecode(String path){
		String key = decodeKey(path);
		lastDecodedPath = key;
		if(key == null) return null;
		synchronized(decodedCache){ return decodedCache.get(key); }
	}

	private static void rememberDecode(String key, SoundMixer68k.Voice decoded){
		if(decoded.source != null) return; //streaming voices are not samples in memory
		synchronized(decodedCache){ decodedCache.put(key, decoded); }
	}

	/** The MIDI note a sound was recorded at (its 'snd ' header's baseFrequency) and its loop in sample frames (0, 0: none). */
	static final class NoteInfo {
		final int baseNote, loopStart, loopEnd;
		NoteInfo(int baseNote, int loopStart, int loopEnd){
			this.baseNote = baseNote<=0 || baseNote>127 ? 60 : baseNote; //0 is middle C for the Sound Manager too
			this.loopStart = loopStart;
			this.loopEnd = loopEnd;
		}
		/** loopEnd > loopStart + 1 is a real loop (Harpsichord's 5263-5264 is not) */
		boolean loops(int frames){ return loopStart>=0 && loopEnd > loopStart+1 && loopEnd <= frames; }
	}
	private static final NoteInfo MIDDLE_C = new NoteInfo(60, 0, 0);

	/**
	 * HyperCard's own sounds, kept as AIFF/WAV without their 'snd ' header (Home's SND_128.aiff, resource/harpsichord.wav):
	 * the base notes and loop of its snd 128 Harpsichord, 129 Boing and 146 flute.
	 */
	static NoteInfo builtInNoteInfo(String name){
		if(name==null) return null;
		switch(name.trim().toLowerCase(java.util.Locale.ROOT)){
		case "harpsichord": return new NoteInfo(67, 0, 0);
		case "boing": return new NoteInfo(74, 0, 0);
		case "flute": return new NoteInfo(57, 744, 1300);
		default: return null;
		}
	}

	private static final java.util.LinkedHashMap<String,NoteInfo> noteInfoCache =
		new java.util.LinkedHashMap<String,NoteInfo>(16, 0.75f, true){
			@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,NoteInfo> e){ return size() > 64; }
		};

	/** The base note and loop of the sound file played for {@code name}: from its snd header or AIFF INST chunk, else by name. */
	static NoteInfo noteInfo(String path, String name){
		String key = path==null ? null : decodeKey(path);
		if(key!=null) synchronized(noteInfoCache){ NoteInfo known = noteInfoCache.get(key); if(known!=null) return known; }
		NoteInfo info = null;
		if(key!=null){
			try{
				byte[] data = java.nio.file.Files.readAllBytes(new File(path).toPath());
				info = path.toLowerCase(java.util.Locale.ROOT).endsWith(".data") ? sndNoteInfo(data) : aiffNoteInfo(data);
			}catch(IOException|RuntimeException e){
				info = null;
			}
		}
		if(info==null) info = builtInNoteInfo(name);
		if(info==null) info = MIDDLE_C;
		if(key!=null) synchronized(noteInfoCache){ noteInfoCache.put(key, info); }
		return info;
	}

	private static int u16(byte[] b, int at){ return (b[at]&0xff)<<8 | (b[at+1]&0xff); }
	private static int s32(byte[] b, int at){ return (b[at]&0xff)<<24 | (b[at+1]&0xff)<<16 | (b[at+2]&0xff)<<8 | (b[at+3]&0xff); }

	/** A 'snd ' resource (format 1 or 2): the sound header of its soundCmd/bufferCmd. Null when it has none. */
	static NoteInfo sndNoteInfo(byte[] b){
		try{
			int format = u16(b, 0), p;
			if(format==1) p = 4 + 6*u16(b, 2);
			else if(format==2) p = 4;
			else return null;
			int commands = u16(b, p);
			p += 2;
			for(int i=0; i<commands; i++){
				int cmd = u16(b, p+8*i) & 0x7fff;
				if(cmd!=80 && cmd!=81) continue; //soundCmd, bufferCmd
				int header = s32(b, p+8*i+4);
				int encode = b[header+20]&0xff, base = b[header+21]&0xff;
				//standard (0) and extended (0xff) headers count the loop in sample frames; a compressed one is left unlooped
				if(encode==0xfe) return new NoteInfo(base, 0, 0);
				return new NoteInfo(base, s32(b, header+12), s32(b, header+16));
			}
		}catch(IndexOutOfBoundsException e){
			return null;
		}
		return null;
	}

	/** An AIFF/AIFC file: the INST chunk's base note and sustain loop (MARK positions). Null without an INST chunk. */
	static NoteInfo aiffNoteInfo(byte[] b){
		try{
			if(b.length<12 || b[0]!='F' || b[1]!='O' || b[2]!='R' || b[3]!='M') return null;
			int inst = -1, mark = -1;
			for(int p=12; p+8<=b.length; ){
				String id = new String(b, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
				int length = s32(b, p+4);
				if(length<0) break;
				if(id.equals("INST")) inst = p+8;
				else if(id.equals("MARK")) mark = p+8;
				p += 8 + length + (length&1);
			}
			if(inst<0) return null;
			int base = b[inst]&0xff, loopStart = 0, loopEnd = 0;
			if(u16(b, inst+8)!=0 && mark>=0){ //sustain loop play mode, begin/end marker ids
				int beginId = u16(b, inst+10), endId = u16(b, inst+12);
				int count = u16(b, mark), q = mark+2;
				for(int i=0; i<count; i++){
					int id = u16(b, q), position = s32(b, q+2), nameLength = b[q+6]&0xff;
					if(id==beginId) loopStart = position;
					if(id==endId) loopEnd = position;
					q += 7 + nameLength + ((nameLength+1)&1);
				}
			}
			return new NoteInfo(base, loopStart, loopEnd);
		}catch(IndexOutOfBoundsException e){
			return null;
		}
	}

	/**
	 * A note's length in seconds at {@code tempo} quarter notes a minute: the duration letter (a quarter when only a
	 * note name is given, 0 = the whole sound for no note at all), dots ×1.5 each, a triplet 2/3 (HyperCard J-2.3 plays
	 * eight "zq3" at tempo 300 in 65 ticks, a quarter being 12; it used to play a triplet note three times).
	 */
	static double noteSeconds(String[] neiro, int tempo) {
		double length = 0.0;
		String d = neiro[4];
		if(d.equalsIgnoreCase("w")) length = 240.0/tempo;
		else if(d.equalsIgnoreCase("h")) length = 120.0/tempo;
		else if(d.equalsIgnoreCase("q")) length = 60.0/tempo;
		else if(d.equalsIgnoreCase("e")) length = 30.0/tempo;
		else if(d.equalsIgnoreCase("s")) length = 15.0/tempo;
		else if(d.equalsIgnoreCase("t")) length = 7.5/tempo;
		else if(d.equalsIgnoreCase("x")) length = 3.75/tempo;
		else if(!neiro[1].equals("")) length = 60.0/tempo;
		String dots = neiro[5].replace("3", "");
		for(int i=0; i<dots.length() && i<4; i++) length *= 1.5; //1.75倍じゃないようだ。
		if(neiro[5].contains("3")) length *= 2.0/3.0;
		return length;
	}

	static AudioInputStream openAudioInputStream(File file)
			throws IOException, UnsupportedAudioFileException {
		//ICON/cicn等と同じく、snd リソースはインポート時にAIFFへ変換せず
		//生データ(拡張子.data)のまま保存されている(HCResource.readResourceData
		//参照)。再生・編集のためにAudioInputStreamが必要になったこのタイミングで
		//初めてAIFF相当のバイト列へデコードする。
		if(file.getName().toLowerCase().endsWith(".data")){
			byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
			AudioInputStream mace = MaceDecoder.streamFromSnd(raw); // MACE 3:1 / 6:1 expanded natively
			if(mace!=null) return mace;
			byte[] aiff = HCResource.decodeSndToAiffBytes(raw);
			if(aiff!=null){
				try {
					return AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(aiff));
				} catch (UnsupportedAudioFileException error) {
					//MACE圧縮(AIFC)の場合、標準のAudioSystemでは読めないため
					//ffmpeg経由(MaceConverter)で変換する
					AudioInputStream decoded = MaceConverter.open(aiff);
					if(decoded!=null) return decoded;
					throw error;
				}
			}
		}
		if(Mp3Decoder.looksLikeMp3(file)) return Mp3Decoder.scan(file).pcm(0); //Java Soundには MP3 のデコーダが無い
		try {
			return AudioSystem.getAudioInputStream(file);
		} catch (UnsupportedAudioFileException error) {
			AudioInputStream decoded = MaceConverter.open(file);
			if(decoded!=null) return decoded;
			throw error;
		}
	}

    /** play stop: the playing note fades out over {@link #FADE_SECONDS}, the queued ones are dropped. */
    public void PlayStop() { PlayStop(false); }
    /** With release (the stack closed) the playing note goes on for a moment and fades (SoundMixer68k.release). */
    public void PlayStop(boolean release) {
    	ArrayList<SoundMixer68k.Voice> drop;
    	synchronized(this){
    		drop = new ArrayList<SoundMixer68k.Voice>(queue);
    		queue.clear();
    		names.clear();
    		name = "";
    	}
    	SoundMixer68k mixer = SoundMixer68k.get();
    	for(SoundMixer68k.Voice v: drop){
    		if(release) mixer.release(v);
    		else mixer.fadeOut(v, (int)(FADE_SECONDS*SoundMixer68k.RATE));
    	}
    }

	/** Appends a voice to this channel: it follows the last queued one without a gap, or starts now when nothing plays. */
	/** Sounds waiting at most: HyperCard takes 300 plays in a tick without waiting, but a script that plays in a loop until
	 *  a click (WARASIBE's title: "play loop_1" over and over) queued millions and ran the heap out. Later ones are dropped. */
	static final int MAX_QUEUED = 500;

	private void enqueue(SoundMixer68k.Voice voice, String soundName) {
		SoundMixer68k mixer = SoundMixer68k.get();
		synchronized(this){ if(queue.size() >= MAX_QUEUED) return; }
		voice.onEnd(() -> ended(voice));
		synchronized(this){
			SoundMixer68k.Voice last = queue.isEmpty() ? null : queue.get(queue.size()-1);
			queue.add(voice);
			names.add(soundName);
			//the sound は再生中のサウンド名。キューの先頭の名前を返さないと "if the sound is done then play …"
			//でループ再生するスクリプトが idle のたびに曲を積み増してしまう
			name = names.get(0);
			if(last != null && mixer.link(last, voice)) return;
		}
		mixer.start(voice);
	}

	/** A voice has played out (or was stopped): the next one in the queue is now the sound. */
	private synchronized void ended(SoundMixer68k.Voice voice) {
		int i = queue.indexOf(voice);
		if(i < 0) return;
		queue.remove(i);
		names.remove(i);
		name = names.isEmpty() ? "" : names.get(0);
	}

	/**
	 * The samples of one note: the first {@code available} frames of the sound, cut or padded with silence to
	 * {@code frames}. A cut that falls inside the sound ends with a short linear fade so that the waveform does not
	 * jump to 0 (an audible click).
	 */
	static short[][] noteSamples(short[] left, short[] right, int available, int frames, int fadeFrames) {
		return noteSamples(left, right, available, frames, fadeFrames, 0, 0);
	}

	/**
	 * The same with the sound's loop: a note longer than the sound repeats frames loopStart..loopEnd-1 while it lasts, as a
	 * Sound Manager freqDurationCmd does (HyperCard's flute is 2048 frames looping 744-1300). No loop when loopEnd is 0.
	 */
	static short[][] noteSamples(short[] left, short[] right, int available, int frames, int fadeFrames, int loopStart, int loopEnd) {
		frames = Math.max(0, frames);
		short[] l = new short[frames];
		short[] r = right==left ? l : new short[frames];
		int copy = Math.min(available, frames);
		System.arraycopy(left, 0, l, 0, copy);
		if(r != l) System.arraycopy(right, 0, r, 0, copy);
		if(loopEnd > loopStart+1 && loopEnd <= available && frames > loopEnd) {
			for(int i=loopEnd; i<frames; i++) {
				int k = loopStart + (i-loopStart) % (loopEnd-loopStart);
				l[i] = left[k];
				if(r != l) r[i] = right[k];
			}
			copy = frames;
			available = frames+1; //cut inside the sound: fade the end
		}
		if(copy > 0 && copy < available) { //a note of no frames (Letter Learner's play boing "c" at its tempo) has nothing to fade
			int fade = Math.max(1, Math.min(copy, fadeFrames));
			for(int i=0; i<fade; i++) {
				int k = copy-fade+i;
				double g = (double)(fade-1-i)/fade;
				l[k] = (short)(l[k]*g);
				if(r != l) r[k] = (short)(r[k]*g);
			}
		}
		return new short[][]{l, r};
	}
}

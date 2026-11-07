import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * A sound or movie file of a classic Mac - a QuickTime movie ('MooV'), a System 7 sound file ('sfil', its 'snd '
 * resources), an AIFF/AIFC (MACE, IMA 4:1 included), WAVE or µ-law file, a SoundEdit file ('FSSD': 8-bit samples at
 * 22.254 kHz in the data fork), an MP3 or a Standard MIDI File - for HyperZebra's media player (GMediaPlayerWindow), for a
 * drop that no known Mac program opens: the host plays few of these (a Cinepak or Road Pizza movie, MACE sound, a sound
 * file whose sounds are resources) and none of them from a Mac program's resources. Decoded with HyperZebra's own decoders
 * ({@link QuickTimeFile}, {@link QuickTimeSound}, {@link QuickTimeVideo}, {@link Mp3Decoder}, MACE) through
 * {@link TQTMovie#assetForPlayer}, which falls back to ffmpeg only for codecs they lack.
 */
final class MediaDocument {
	static final int MOOV = ResourceFile68k.fourcc("MooV"), MIDI = ResourceFile68k.fourcc("Midi"), MIDI_UPPER = ResourceFile68k.fourcc("MIDI"),
		AIFF = ResourceFile68k.fourcc("AIFF"), AIFC = ResourceFile68k.fourcc("AIFC"), WAVE = ResourceFile68k.fourcc("WAVE"), WAV = ResourceFile68k.fourcc("WAV "),
		ULAW = ResourceFile68k.fourcc("ULAW"), SFIL = ResourceFile68k.fourcc("sfil"), SND = ResourceFile68k.fourcc("snd "), FSSD = ResourceFile68k.fourcc("FSSD"),
		MPG3 = ResourceFile68k.fourcc("MPG3"), MP3 = ResourceFile68k.fourcc("Mp3 "), MP3_UPPER = ResourceFile68k.fourcc("MP3 "),
		MOOV_RESOURCE = ResourceFile68k.fourcc("moov"), CODE = ResourceFile68k.fourcc("CODE");
	/** The Macintosh's rate22khz, SoundEdit's rate: 22254.54 Hz (Fixed $56EE8BA3). */
	static final float RATE_22KHZ = (float)(0x56EE8BA3L/65536.0);
	/** Larger sampled-sound files are not read whole (a movie or an MP3 is not read whole anyway). */
	static final long MAX_SOUND = 256L<<20;

	enum Kind {
		/** A QuickTime movie (video, sound, music tracks). */ MOVIE,
		/** A Standard MIDI File. */ MIDI,
		/** An MP3, decoded while it plays. */ MP3,
		/** AIFF, AIFC, WAVE, µ-law: what Java Sound (with the MACE and IMA 4:1 decoders) reads. */ SAMPLED,
		/** A SoundEdit file: 8-bit unsigned samples at 22.254 kHz, the data fork alone. */ SOUNDEDIT,
		/** A sound file whose sounds are 'snd ' resources (System 7's 'sfil', a sound suitcase). */ SOUND_RESOURCES
	}

	/** One of a sound file's 'snd ' resources. */
	static final class Sound {
		final int id; final String name;
		Sound(int id, String name){ this.id = id; this.name = name==null ? "" : name; }
		@Override public String toString(){ return name.isEmpty() ? "snd "+id : name; }
	}

	private MediaDocument(){}

	/** Whether a dropped file is played by the media player: a sound or movie file by its type, its name or its content. */
	static boolean isMediaDocument(File f, int type){ return kindOf(f, type)!=null; }

	/** What kind of media the file is, or null when it is none the player plays. */
	static Kind kindOf(File f, int type){
		if(f==null || !f.isFile()) return null;
		if(type==MOOV) return Kind.MOVIE;
		if(type==MIDI || type==MIDI_UPPER) return Kind.MIDI;
		if(type==MPG3 || type==MP3 || type==MP3_UPPER) return Kind.MP3;
		if(type==AIFF || type==AIFC || type==WAVE || type==WAV || type==ULAW) return Kind.SAMPLED;
		if(type==FSSD) return f.length()>0 && f.length()<=MAX_SOUND ? Kind.SOUNDEDIT : null;
		if(type==SFIL || type==SND) return sounds(f).isEmpty() ? null : Kind.SOUND_RESOURCES;
		if(type!=0) return f.length()==0 && soundsOnly(f) ? Kind.SOUND_RESOURCES : null; // a game's sound file ('SFIL'...); other files of a Mac type belong to their program
		String n = f.getName().toLowerCase(Locale.ROOT);
		if(n.endsWith(".mov") || n.endsWith(".moov") || n.endsWith(".qt")) return Kind.MOVIE;
		if(n.endsWith(".mid") || n.endsWith(".midi") || n.endsWith(".smf") || TQTMovie.isStandardMidiFile(f)) return Kind.MIDI;
		if(n.endsWith(".mp3")) return Kind.MP3;
		if(n.endsWith(".aif") || n.endsWith(".aiff") || n.endsWith(".aifc") || n.endsWith(".wav") || n.endsWith(".au") || sampledHeader(f)) return Kind.SAMPLED;
		// a typeless file (unpacked without its Finder info) holding sounds and no program code, or a movie resource
		try{
			ResourceFile68k rf = ResourceFile68k.open(f);
			List<Integer> types = rf.types();
			if(types.contains(MOOV_RESOURCE)) return Kind.MOVIE;
			if(types.contains(SND) && !types.contains(CODE)) return Kind.SOUND_RESOURCES;
		}catch(IOException | RuntimeException e){}
		return null;
	}

	/** The type programs see for a typeless media file ('MooV', 'Midi', 'MPG3', 'AIFF', 'WAVE', 'sfil'), so the ones made for it are offered. */
	static int typeFor(File f, Kind kind){
		if(kind==null) return 0;
		switch(kind){
			case MOVIE: return MOOV;
			case MIDI: return MIDI;
			case MP3: return MPG3;
			case SOUNDEDIT: return FSSD;
			case SOUND_RESOURCES: return SFIL;
			default: return f.getName().toLowerCase(Locale.ROOT).endsWith(".wav") ? WAVE : AIFF;
		}
	}

	/** An AIFF/AIFC ("FORM"...) or WAVE ("RIFF"...) header. */
	private static boolean sampledHeader(File f){
		if(f.length()<12) return false;
		try(InputStream in = Files.newInputStream(f.toPath())){
			byte[] b = in.readNBytes(12);
			String head = new String(b, 0, 4, "ISO-8859-1"), form = new String(b, 8, 4, "ISO-8859-1");
			return head.equals("FORM") && (form.equals("AIFF") || form.equals("AIFC")) || head.equals("RIFF") && form.equals("WAVE");
		}catch(IOException e){ return false; }
	}

	/** Resources with sounds and no program code (a game's own sound file). */
	private static boolean soundsOnly(File f){
		try{ List<Integer> types = ResourceFile68k.open(f).types(); return types.contains(SND) && !types.contains(CODE); }
		catch(IOException | RuntimeException e){ return false; }
	}

	/** A sound file's 'snd ' resources, in file order (empty when it has none or no resource fork). */
	static List<Sound> sounds(File f){
		List<Sound> r = new ArrayList<Sound>();
		try{ for(ResourceFile68k.Resource s : ResourceFile68k.open(f).ofType(SND)) r.add(new Sound(s.id, s.name)); }
		catch(IOException | RuntimeException e){}
		return r;
	}

	/**
	 * The file decoded for playing: {@code sound} is the 'snd ' resource of a sound file to play (ignored for other
	 * kinds). The asset has no video, sound or music when nothing in it could be decoded.
	 */
	static TQTMovie.MovieAsset load(File f, Kind kind, Sound sound) throws IOException {
		switch(kind){
			case MOVIE: case MIDI: case MP3: return TQTMovie.assetForPlayer(f);
			case SOUNDEDIT: {
				byte[] b = Files.readAllBytes(f.toPath());
				short[] s = new short[b.length];
				for(int i=0; i<b.length; i++) s[i] = (short)(((b[i]&0xFF)-128)<<8);
				return soundAsset(new QuickTimeSound.Pcm(s, RATE_22KHZ, 1));
			}
			case SOUND_RESOURCES: {
				if(sound==null) throw new IOException("no sound");
				ResourceFile68k.Resource r = ResourceFile68k.open(f).get(SND, sound.id);
				if(r==null) throw new IOException("no 'snd ' "+sound.id);
				return soundAsset(pcm(sndStream(r.data())));
			}
			default:
				if(f.length()>MAX_SOUND) throw new IOException("too large");
				try{ return soundAsset(pcm(TSound.openAudioInputStream(f))); }
				catch(javax.sound.sampled.UnsupportedAudioFileException e){ throw new IOException(e.getMessage()==null ? "unsupported sound format" : e.getMessage()); }
		}
	}

	/**
	 * A 'snd ' resource as a stream, decoded the way the 68K Sound Manager does: standard, extended (16-bit, stereo) and
	 * compressed headers, MACE natively; a compressed header's other formats (ima4, ulaw, alaw, sowt...) through
	 * {@link QuickTimeSound}; anything else through its AIFF form (TSound's way).
	 */
	static AudioInputStream sndStream(byte[] snd) throws IOException {
		AudioInputStream pcm = SoundManager68k.pcmStream(snd);
		if(pcm!=null) return pcm;
		AudioInputStream compressed = compressedStream(snd);
		if(compressed!=null) return compressed;
		byte[] aiff = HCResource.decodeSndToAiffBytes(snd);
		if(aiff==null) throw new IOException("unsupported 'snd ' resource");
		try{ return AudioSystem.getAudioInputStream(new ByteArrayInputStream(aiff)); }
		catch(javax.sound.sampled.UnsupportedAudioFileException e){
			AudioInputStream decoded = MaceConverter.open(aiff);
			if(decoded==null) throw new IOException("unsupported 'snd ' resource");
			return decoded;
		}
	}

	/** A compressed sound header (cmpSH, encode $FE) of a format QuickTimeSound decodes, with its samples in the resource; null otherwise. */
	private static AudioInputStream compressedStream(byte[] snd) throws IOException {
		int h = SoundManager68k.soundHeaderOffset(snd);
		if(h<0 || h+64>snd.length || (snd[h+20]&0xFF)!=0xFE || u32(snd, h)!=0) return null;
		int channels = (int)u32(snd, h+4), compressionID = (short)u16(snd, h+56), sampleSize = u16(snd, h+62);
		long frames = u32(snd, h+22); double rate = u32(snd, h+8)/65536.0;
		if(channels<1 || channels>2 || rate<=0 || (compressionID!=-1 && compressionID!=-2)) return null;
		QuickTimeFile.SoundDescription d = QuickTimeSound.description(ResourceFile68k.typeName((int)u32(snd, h+40)), channels, sampleSize, rate);
		if(!QuickTimeSound.canDecode(d)) return null;
		long bytes = Math.min(frames*d.bytesPerFrame, snd.length-(h+64L)); // frames count packets for packet formats
		if(bytes<=0) return null;
		short[] samples = QuickTimeSound.decode(d, java.util.Arrays.copyOfRange(snd, h+64, h+64+(int)bytes), frames*Math.max(1, d.samplesPerPacket));
		return new QuickTimeSound.Pcm(samples, (float)rate, channels).stream();
	}
	private static int u16(byte[] b, int at){ return (b[at]&0xFF)<<8 | (b[at+1]&0xFF); }
	private static long u32(byte[] b, int at){ return (long)u16(b, at)<<16 | u16(b, at+2); }

	private static TQTMovie.MovieAsset soundAsset(QuickTimeSound.Pcm pcm){
		TQTMovie.MovieAsset a = new TQTMovie.MovieAsset();
		a.sound = pcm;
		a.timeScale = TQTMovie.DEFAULT_TIME_SCALE;
		a.duration = (int)Math.min(Integer.MAX_VALUE, pcm.millis()*TQTMovie.DEFAULT_TIME_SCALE/1000L);
		a.converted = true;
		return a;
	}

	/** A stream's samples as 16-bit signed big-endian PCM, at its own rate. */
	static QuickTimeSound.Pcm pcm(AudioInputStream in) throws IOException {
		try{
			AudioFormat f = in.getFormat(), target = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, true);
			if(!f.matches(target)) in = AudioSystem.getAudioInputStream(target, in);
			byte[] b = in.readAllBytes();
			short[] s = new short[b.length/2];
			for(int i=0; i<s.length; i++) s[i] = (short)((b[i*2]<<8) | (b[i*2+1]&0xFF));
			return new QuickTimeSound.Pcm(s, f.getSampleRate(), f.getChannels());
		}catch(IllegalArgumentException unsupported){ throw new IOException("unsupported sound format "+in.getFormat()); }
		finally{ in.close(); }
	}

	/**
	 * The samples at a rate the host's audio output takes as it is ({@link HostSampleRate}: CoreAudio's converter breaks on
	 * the Mac's 22254.54 or 11127.27 Hz), interpolated linearly.
	 */
	static QuickTimeSound.Pcm forHost(QuickTimeSound.Pcm p){
		if(p==null || HostSampleRate.plain(p.rate) || p.rate<=0) return p;
		float rate = HostSampleRate.OUTPUT;
		int channels = p.channels, inFrames = p.frames();
		int frames = (int)Math.min(Integer.MAX_VALUE/Math.max(1, channels), (long)Math.floor(inFrames*(double)rate/p.rate));
		short[] out = new short[frames*channels];
		double step = p.rate/(double)rate;
		for(int i=0; i<frames; i++){
			double at = i*step; int a = (int)at; double frac = at-a; int b = Math.min(inFrames-1, a+1);
			for(int c=0; c<channels; c++){
				int x = p.samples[a*channels+c], y = p.samples[b*channels+c];
				out[i*channels+c] = (short)Math.round(x+(y-x)*frac);
			}
		}
		return new QuickTimeSound.Pcm(out, rate, channels);
	}

	// ------------------------------------------------------------------ what the file holds, for the player to show

	/**
	 * How the file's media are stored, a line per track or stream: "Video: Cinepak ('cvid') · 160 × 120 · 24-bit · 15 fps",
	 * "Sound: MACE 3:1 · 22,254.54 Hz · mono", "Sound: MP3 (MPEG-1 Layer III) · 128 kbit/s · 44,100 Hz · joint stereo",
	 * "Music: Standard MIDI File (format 1) · 3 tracks"... {@code sound}: the sound of a sound file shown. Empty when the
	 * headers can't be read.
	 */
	static List<String> formats(File f, Kind kind, Sound sound){
		List<String> r = new ArrayList<String>();
		try{
			switch(kind){
				case MOVIE: movieFormats(f, r); break;
				case MIDI: {
					javax.sound.midi.MidiFileFormat m = javax.sound.midi.MidiSystem.getMidiFileFormat(f);
					javax.sound.midi.Sequence seq = javax.sound.midi.MidiSystem.getSequence(f);
					r.add(line(International.t("Music:"), International.t("Standard MIDI File (format {0})", m.getType()), seq.getTracks().length==1 ? International.t("1 track") : International.t("{0} tracks", seq.getTracks().length)));
					break;
				}
				case MP3: r.add(mp3Format(Mp3Decoder.scan(f))); break;
				case SOUNDEDIT: r.add(line(International.t("Sound:"), "SoundEdit", International.t("{0}-bit uncompressed", 8), rate(RATE_22KHZ), channels(1))); break;
				case SOUND_RESOURCES: {
					ResourceFile68k.Resource res = sound==null ? null : ResourceFile68k.open(f).get(SND, sound.id);
					String d = res==null ? null : sndFormat(res.data());
					if(d!=null) r.add(d);
					break;
				}
				default: { String d = sampledFormat(f); if(d!=null) r.add(d); }
			}
		}catch(Exception e){} // the headers can't be read: nothing to say
		return r;
	}

	private static String line(String label, String... parts){ // label: translated
		StringBuilder b = new StringBuilder(label);
		for(int i=0; i<parts.length; i++) if(parts[i]!=null && !parts[i].isEmpty()) b.append(i==0 ? " " : " · ").append(parts[i]);
		return b.toString();
	}

	private static void movieFormats(File f, List<String> r) throws IOException {
		QuickTimeFile q = QuickTimeFile.open(f);
		try{
			List<String> others = new ArrayList<String>();
			for(QuickTimeFile.Track t : q.tracks){
				if(!t.enabled() || t.media==null) continue;
				QuickTimeFile.Media m = t.media;
				for(QuickTimeFile.SampleDescription d : m.descriptions){
					if(d instanceof QuickTimeFile.VideoDescription){
						QuickTimeFile.VideoDescription v = (QuickTimeFile.VideoDescription)d;
						double seconds = m.timeScale>0 ? m.duration/(double)m.timeScale : 0;
						String fps = seconds>0 && m.samples()>1 ? International.t("{0} fps", String.format(Locale.ROOT, m.samples()/seconds>=10 ? "%.0f" : "%.1f", m.samples()/seconds)) : null;
						r.add(line(International.t("Video:"), videoCodec(v), v.width+" × "+v.height, depth(v.depth), fps));
					}
					else if(d instanceof QuickTimeFile.SoundDescription){
						QuickTimeFile.SoundDescription a = (QuickTimeFile.SoundDescription)d;
						r.add(line(International.t("Sound:"), soundCodec(QuickTimeFile.typeName(a.format), a.sampleSize), rate((float)a.sampleRate), channels(a.channels)));
					}
					else if(m.handlerType==QuickTimeFile.MUSI) r.add(line(International.t("Music:"), International.t("QuickTime Music")+" ('"+QuickTimeFile.typeName(d.format)+"')"));
					else{
						String type = QuickTimeFile.typeName(m.handlerType), name = TRACKS.get(type);
						String shown = name==null ? "'"+type+"'" : name+" ('"+type+"')";
						if(!others.contains(shown)) others.add(shown);
					}
				}
			}
			if(!others.isEmpty()) r.add(line(International.t("Other tracks:"), String.join(", ", others)));
			// tracks stored alike (a movie's two video tracks of one codec) are one line
			List<String> once = new ArrayList<String>();
			for(String line : r) if(!once.contains(line)) once.add(line);
			r.clear(); r.addAll(once);
		}finally{ q.close(); }
	}

	/** Video codecs by their QuickTime type, with the names QuickTime's compressors go by. */
	private static final java.util.Map<String,String> VIDEO = new java.util.HashMap<String,String>();
	/** Sound formats by their type ('snd ' and AIFF-C compression types are the same). */
	private static final java.util.Map<String,String> SOUND = new java.util.HashMap<String,String>();
	/** Other kinds of tracks by their media handler type. */
	private static final java.util.Map<String,String> TRACKS = new java.util.HashMap<String,String>();
	static{
		String[] video = {"raw ","None","rle ","Animation","smc ","Graphics","rpza","Apple Video (Road Pizza)","cvid","Cinepak",
			"jpeg","Photo - JPEG","mjpa","Motion JPEG A","mjpb","Motion JPEG B","SVQ1","Sorenson Video","SVQ3","Sorenson Video 3",
			"IV32","Indeo 3.2","IV41","Indeo 4.1","IV50","Indeo 5","tmot","TrueMotion S","8BPS","Planar RGB","png ","PNG",
			"kpcd","Kodak Photo CD","yuv2","Component Video","h261","H.261","h263","H.263","mp4v","MPEG-4 Video","avc1","H.264",
			"dvc ","DV - NTSC","dvcp","DV - PAL","qdrw","QuickDraw","WRLE","BMP","tiff","TIFF","gif ","GIF","b16g","16-bit gray"};
		for(int i=0; i<video.length; i+=2) VIDEO.put(video[i], video[i+1]);
		String[] sound = {"MAC3","MACE 3:1","MAC6","MACE 6:1","ima4","IMA 4:1","ulaw","µ-law 2:1","alaw","A-law 2:1",
			"QDMC","QDesign Music","QDM2","QDesign Music 2","Qclp","Qualcomm PureVoice","agsm","GSM","dvca","DV audio","mp4a","AAC",
			".mp3","MP3","ms\u0000U","MP3","ms\u0000\u0002","Microsoft ADPCM","ms\u0000\u0011","IMA ADPCM (DVI)"};
		for(int i=0; i<sound.length; i+=2) SOUND.put(sound[i], sound[i+1]);
		String[] tracks = {"text","Text","sprt","Sprite","twen","Tween","tmcd","Timecode","MPEG","MPEG-1","qtvr","QuickTime VR",
			"pano","QuickTime VR","STpn","QuickTime VR","flsh","Flash","strm","Streaming","moov","Movie in movie","clip","Clipping","3dmf","QuickDraw 3D","gnrc","Generic"};
		for(int i=0; i<tracks.length; i+=2) TRACKS.put(tracks[i], tracks[i+1]);
	}
	private static String videoCodec(QuickTimeFile.VideoDescription v){
		String type = QuickTimeFile.typeName(v.format), name = VIDEO.get(type);
		if(name==null) name = v.name.trim().isEmpty() || !v.name.chars().allMatch(c -> c>=0x20 && c<0x7F) ? null : v.name.trim();
		return name==null ? "'"+type+"'" : name+" ('"+type+"')";
	}
	/** 1-32 bits of colour; 33-40 gray of depth-32 bits. */
	private static String depth(int depth){
		if(depth>32 && depth<=40) return International.t("{0}-bit gray", depth-32);
		return depth>0 && depth<=32 ? depth+"-bit" : null;
	}
	/** A sound format: uncompressed with its sample size, else the compressor's name and type. */
	private static String soundCodec(String type, int sampleSize){
		switch(type){
			case "raw ": case "NONE": case "twos": return International.t("{0}-bit uncompressed", sampleSize);
			case "sowt": return International.t("{0}-bit uncompressed", sampleSize)+" (little-endian)";
			case "in24": return International.t("{0}-bit uncompressed", 24);
			case "in32": return International.t("{0}-bit uncompressed", 32);
			case "fl32": return International.t("{0}-bit float", 32);
			case "fl64": return International.t("{0}-bit float", 64);
			default:
				String name = SOUND.get(type);
				String shown = type.chars().allMatch(c -> c>=0x20 && c<0x7F) ? "'"+type+"'" : "";
				return name==null ? shown : shown.isEmpty() || name.startsWith("MP3") || name.equals("Microsoft ADPCM") || name.startsWith("IMA ADPCM") ? name : name+" ("+shown+")";
		}
	}

	/** An MP3's layer, bit rate (average when it varies), rate and stereo mode. */
	private static String mp3Format(Mp3Decoder.Stream s){
		Mp3Decoder.Header h = s.first;
		boolean variable = false;
		for(int i=0; i<s.offsets.length && !variable; i+=Math.max(1, s.offsets.length/200)){
			Mp3Decoder.Header k = Mp3Decoder.Header.at(s.data, s.offsets[i]);
			if(k!=null && k.bitrate!=h.bitrate) variable = true;
		}
		long bytes = 0;
		for(int i=0; i<s.offsets.length; i++) bytes += (i+1<s.offsets.length ? s.offsets[i+1] : s.data.length)-s.offsets[i];
		long kbps = variable && s.millis()>0 ? Math.round(bytes*8.0/s.millis()) : h.bitrate;
		String version = h.version==3 ? "MPEG-1" : h.version==2 ? "MPEG-2" : "MPEG-2.5", layer = h.layer==1 ? "I" : h.layer==2 ? "II" : "III";
		String mode = h.mode==1 ? International.t("joint stereo") : h.mode==2 ? International.t("dual channel") : channels(h.channels());
		return line(International.t("Sound:"), "MP3 ("+version+" Layer "+layer+")", kbps+" kbit/s"+(variable ? " (VBR)" : ""), rate(h.sampleRate), mode);
	}

	/** A 'snd ' resource's sound header: standard (8-bit), extended (its sample size) or compressed (MACE, or the format it names). */
	static String sndFormat(byte[] snd){
		int h = SoundManager68k.soundHeaderOffset(snd);
		if(h<0 || h+22>snd.length) return null;
		int encode = snd[h+20]&0xFF; float rate = (float)(u32(snd, h+8)/65536.0);
		if(encode==0) return line(International.t("Sound:"), International.t("{0}-bit uncompressed", 8), rate(rate), channels(1));
		if((encode!=0xFF && encode!=0xFE) || h+64>snd.length) return null;
		int channels = (int)u32(snd, h+4);
		if(encode==0xFF) return line(International.t("Sound:"), International.t("{0}-bit uncompressed", u16(snd, h+48)), rate(rate), channels(channels));
		int compressionID = (short)u16(snd, h+56);
		String type = compressionID==3 ? "MAC3" : compressionID==4 ? "MAC6" : ResourceFile68k.typeName((int)u32(snd, h+40));
		if(compressionID==0 && (type.equals("\0\0\0\0") || u32(snd, h+40)==0)) type = "NONE";
		return line(International.t("Sound:"), soundCodec(type, u16(snd, h+62)), rate(rate), channels(channels));
	}

	/** An AIFF/AIFF-C file's COMM chunk, a WAVE file's 'fmt ' chunk, a Sun/NeXT .au header. */
	static String sampledFormat(File f) throws IOException {
		byte[] b;
		try(InputStream in = Files.newInputStream(f.toPath())){ b = in.readNBytes(1<<16); }
		if(b.length>=12 && b[0]=='F' && b[1]=='O' && b[2]=='R' && b[3]=='M'){
			boolean aifc = b[8]=='A' && b[9]=='I' && b[10]=='F' && b[11]=='C';
			for(int at=12; at+8<=b.length; ){
				int size = (int)u32(b, at+4);
				if(size<0) break;
				if(b[at]=='C' && b[at+1]=='O' && b[at+2]=='M' && b[at+3]=='M' && at+8+18<=b.length){
					int channels = u16(b, at+8), bits = u16(b, at+14);
					float rate = (float)extended(b, at+16);
					String type = aifc && at+8+22<=b.length ? ResourceFile68k.typeName((int)u32(b, at+26)) : "NONE";
					return line(International.t("Sound:"), aifc ? "AIFF-C" : "AIFF", soundCodec(type, bits), rate(rate), channels(channels));
				}
				at += 8+size+(size&1);
			}
			return null;
		}
		if(b.length>=12 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F'){
			for(int at=12; at+8<=b.length; ){
				int size = le32(b, at+4);
				if(size<0) break;
				if(b[at]=='f' && b[at+1]=='m' && b[at+2]=='t' && b[at+3]==' ' && at+8+16<=b.length){
					int tag = le16(b, at+8), channels = le16(b, at+10), rate = le32(b, at+12), bits = le16(b, at+22);
					String codec = tag==1 ? International.t("{0}-bit uncompressed", bits) : tag==3 ? International.t("{0}-bit float", bits)
						: tag==2 ? "Microsoft ADPCM" : tag==6 ? "A-law 2:1" : tag==7 ? "µ-law 2:1" : tag==0x11 ? "IMA ADPCM (DVI)" : tag==0x31 ? "GSM 6.10"
						: tag==0x55 ? "MP3" : String.format(Locale.ROOT, "format $%04X", tag);
					return line(International.t("Sound:"), "WAVE", codec, rate(rate), channels(channels));
				}
				at += 8+size+(size&1);
			}
			return null;
		}
		if(b.length>=24 && b[0]=='.' && b[1]=='s' && b[2]=='n' && b[3]=='d'){
			int encoding = (int)u32(b, 12), rate = (int)u32(b, 16), channels = (int)u32(b, 20);
			String codec = encoding==1 ? "µ-law 2:1" : encoding>=2 && encoding<=5 ? International.t("{0}-bit uncompressed", 8*(encoding-1))
				: encoding==6 ? International.t("{0}-bit float", 32) : encoding==27 ? "A-law 2:1" : "encoding "+encoding;
			return line(International.t("Sound:"), "Sun/NeXT", codec, rate(rate), channels(channels));
		}
		return null;
	}
	private static int le16(byte[] b, int at){ return (b[at]&0xFF) | (b[at+1]&0xFF)<<8; }
	private static int le32(byte[] b, int at){ return le16(b, at) | le16(b, at+2)<<16; }
	/** An 80-bit IEEE extended number (AIFF's sample rate). */
	private static double extended(byte[] b, int at){
		int exponent = u16(b, at)&0x7FFF; long mantissa = 0;
		for(int i=0; i<8; i++) mantissa = mantissa<<8 | (b[at+2+i]&0xFF);
		if(exponent==0 && mantissa==0) return 0;
		double v = (mantissa>>>11)*Math.pow(2, exponent-16383-52);
		return (u16(b, at)&0x8000)!=0 ? -v : v;
	}

	private static String rate(float hz){ return hz==Math.rint(hz) ? String.format(Locale.ROOT, "%,d Hz", (int)hz) : String.format(Locale.ROOT, "%,.2f Hz", hz); }
	private static String channels(int n){ return n==1 ? International.t("mono") : n==2 ? International.t("stereo") : International.t("{0} channels", n); }

	/** The video formats ('SVQ1', 'IV32'...) of a movie's video tracks that no decoder of HyperZebra's reads (empty when it has none). */
	static List<String> undecodedVideo(File f){ return undecoded(f, true); }
	/** The same for its sound tracks ('Qclp', 'QDMC'...). */
	static List<String> undecodedSound(File f){ return undecoded(f, false); }
	private static List<String> undecoded(File f, boolean video){
		List<String> r = new ArrayList<String>();
		QuickTimeFile q = null;
		try{
			q = QuickTimeFile.open(f);
			for(QuickTimeFile.Track t : q.tracks){
				if(!t.enabled() || t.media==null || (video ? !t.media.video() : !t.media.sound()) || t.media.samples()==0) continue;
				for(QuickTimeFile.SampleDescription d : t.media.descriptions)
					if(!(video ? QuickTimeVideo.canDecode(d) : QuickTimeSound.canDecode(d)) && !r.contains(QuickTimeFile.typeName(d.format))) r.add(QuickTimeFile.typeName(d.format));
			}
		}catch(IOException | RuntimeException e){}
		finally{ if(q!=null) q.close(); }
		return r;
	}

}

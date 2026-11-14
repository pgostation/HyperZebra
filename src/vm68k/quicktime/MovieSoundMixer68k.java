import java.io.IOException;
import javax.sound.sampled.AudioInputStream;

/**
 * A movie's sound as a voice of the 68K mixer (the browser player: no Java Sound lines; SoundMixer68k's driven mode mixes it
 * with the VMs' other sound): its decoded samples (QuickTimeSound) or its MP3, streamed a buffer at a time from the position.
 */
final class MovieSoundMixer68k implements MovieSound68k {
	private static final int CHUNK = 4096;
	private final MovieData data;
	private SoundMixer68k.Voice voice;
	private long from;
	private int level = 256;

	private MovieSoundMixer68k(MovieData data) { this.data = data; }
	/** The sound of a movie (sampled sound, MP3, or QuickTime music through MidiSynth68k), null when it has none */
	static MovieSound68k of(MovieData d) { return d == null || (d.sound == null && d.mp3 == null && d.musicSequence == null) ? null : new MovieSoundMixer68k(d); }

	@Override public void start() {
		stop();
		voice = voiceFrom(from);
		if (voice == null) return;
		gain();
		SoundMixer68k.get().start(voice);
	}
	@Override public void stop() {
		SoundMixer68k.Voice v = voice;
		voice = null;
		if (v != null) SoundMixer68k.get().stop(v);
	}
	@Override public void position(long micros) {
		from = Math.max(0, micros);
		if (voice != null) start(); // playing: on from there
	}
	@Override public void level(int level) { this.level = Math.max(0, Math.min(256, level)); gain(); }
	@Override public void close() { stop(); }
	@Override public long lengthMicros() {
		if (data.sound != null) return data.sound.rate <= 0 ? 0 : (long) (data.sound.frames() * 1_000_000.0 / data.sound.rate);
		if (data.mp3 != null) return data.mp3.millis() * 1000L;
		return synth().frames() * 1_000_000L / MidiSynth68k.RATE;
	}
	private MidiSynth68k synth;
	/** The music's synthesizer (made once: the events are ordered and timed when it is made) */
	private MidiSynth68k synth() { if (synth == null) synth = new MidiSynth68k(data.musicSequence); return synth; }
	private void gain() { SoundMixer68k.Voice v = voice; if (v != null) { v.gainLeft = v.gainRight = level / 256f; } }

	/** A streaming voice from that time on */
	private SoundMixer68k.Voice voiceFrom(long micros) {
		if (data.sound != null) {
			QuickTimeSound.Pcm pcm = data.sound;
			int channels = pcm.channels;
			int[] at = {(int) Math.min(pcm.frames(), (long) (micros / 1_000_000.0 * pcm.rate))};
			short[] left = new short[CHUNK], right = channels >= 2 ? new short[CHUNK] : left;
			return new SoundMixer68k.Voice(left, right, pcm.rate, 1, (l, r) -> {
				int n = Math.min(l.length, pcm.frames() - at[0]);
				for (int i = 0; i < n; i++) {
					int p = (at[0] + i) * channels;
					l[i] = pcm.samples[p];
					if (r != l) r[i] = pcm.samples[p + 1];
				}
				at[0] += Math.max(0, n);
				return Math.max(0, n);
			});
		}
		if (data.mp3 == null) { // QuickTime music: the small synthesizer from the position
			MidiSynth68k synth = synth();
			synth.seek(micros * MidiSynth68k.RATE / 1_000_000L);
			return new SoundMixer68k.Voice(new short[CHUNK], new short[CHUNK], MidiSynth68k.RATE, 1, synth::fill);
		}
		Mp3Decoder.Stream mp3 = data.mp3;
		AudioInputStream in = mp3.pcm(micros * mp3.sampleRate() / 1_000_000L);
		int channels = mp3.channels();
		byte[] bytes = new byte[CHUNK * channels * 2];
		short[] left = new short[CHUNK], right = channels >= 2 ? new short[CHUNK] : left;
		return new SoundMixer68k.Voice(left, right, mp3.sampleRate(), 1, (l, r) -> {
			int got = 0;
			try { for (int k; got < bytes.length && (k = in.read(bytes, got, bytes.length - got)) > 0; ) got += k; } catch (IOException e) { return 0; }
			int n = got / (channels * 2);
			for (int i = 0, o = 0; i < n; i++) {
				l[i] = (short) ((bytes[o] & 0xff) << 8 | (bytes[o + 1] & 0xff)); o += 2;
				if (channels >= 2) { if (r != l) r[i] = (short) ((bytes[o] & 0xff) << 8 | (bytes[o + 1] & 0xff)); o += 2; }
			}
			return n;
		});
	}
}

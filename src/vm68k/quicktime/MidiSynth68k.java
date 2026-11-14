import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * A small General MIDI synthesizer for the browser player, which has no MIDI synthesizer of its own (the desktop plays QuickTime
 * music through Java's): a Sequence (QTMusicConverter's from a 'musi' track) rendered as stereo 16-bit samples at the mixer's
 * rate, a buffer at a time (a SoundMixer68k streaming voice, MovieSoundMixer68k). Each GM instrument family is a wavetable
 * with an envelope (pianos and plucked sounds decay, organs and strings hold), channel 10 is drums made of tones and noise;
 * program, volume, expression, pan, pitch bend (2 semitones), sustain, all notes off. Simple sounds, the music's notes and time.
 */
final class MidiSynth68k {
	static final int RATE = SoundMixer68k.RATE;
	private static final int VOICES = 32, TABLE = 2048;
	/** The events in time order: output frame, status | data1 << 8 | data2 << 16 */
	private final long[] when;
	private final int[] what;
	private final long endFrame;
	private int next;
	private long frame;

	/** One wavetable per timbre */
	private static final float[][] WAVES = new float[8][];
	private static final int SINE = 0, PIANO = 1, ORGAN = 2, SAW = 3, SQUARE = 4, TRIANGLE = 5, FLUTE = 6, REED = 7;
	static {
		WAVES[SINE] = harmonics(new double[] {1});
		WAVES[PIANO] = harmonics(new double[] {1, 0.45, 0.25, 0.12, 0.08, 0.04});
		WAVES[ORGAN] = harmonics(new double[] {1, 0.7, 0.5, 0, 0.3, 0, 0, 0.2});
		WAVES[SAW] = harmonics(new double[] {1, 1 / 2.0, 1 / 3.0, 1 / 4.0, 1 / 5.0, 1 / 6.0, 1 / 7.0, 1 / 8.0, 1 / 9.0, 1 / 10.0, 1 / 11.0, 1 / 12.0});
		WAVES[SQUARE] = harmonics(new double[] {1, 0, 1 / 3.0, 0, 1 / 5.0, 0, 1 / 7.0, 0, 1 / 9.0, 0, 1 / 11.0});
		WAVES[TRIANGLE] = harmonics(new double[] {1, 0, -1 / 9.0, 0, 1 / 25.0, 0, -1 / 49.0});
		WAVES[FLUTE] = harmonics(new double[] {1, 0.25, 0.08});
		WAVES[REED] = harmonics(new double[] {1, 0.2, 0.55, 0.1, 0.35, 0.05, 0.2});
	}
	private static float[] harmonics(double[] amp) {
		float[] t = new float[TABLE];
		double peak = 0;
		double[] v = new double[TABLE];
		for (int i = 0; i < TABLE; i++) {
			double x = 2 * Math.PI * i / TABLE, s = 0;
			for (int h = 0; h < amp.length; h++) s += amp[h] * Math.sin((h + 1) * x);
			v[i] = s; peak = Math.max(peak, Math.abs(s));
		}
		for (int i = 0; i < TABLE; i++) t[i] = (float) (v[i] / peak);
		return t;
	}

	/** An instrument: wavetable, attack, decay (seconds), sustain level, release */
	private static final class Patch {
		final int wave; final float attack, decay, sustain, release;
		Patch(int wave, double attack, double decay, double sustain, double release) { this.wave = wave; this.attack = (float) attack; this.decay = (float) decay; this.sustain = (float) sustain; this.release = (float) release; }
	}
	/** By GM program family (program / 8) */
	private static final Patch[] FAMILIES = {
		new Patch(PIANO, .004, 1.6, 0, .25),    // piano
		new Patch(SINE, .002, .9, 0, .3),       // chromatic percussion
		new Patch(ORGAN, .01, .1, .9, .08),     // organ
		new Patch(TRIANGLE, .003, 1.0, 0, .2),  // guitar
		new Patch(TRIANGLE, .005, .6, .45, .1), // bass
		new Patch(SAW, .08, .3, .8, .25),       // strings
		new Patch(SAW, .12, .3, .8, .35),       // ensemble
		new Patch(SAW, .03, .2, .75, .15),      // brass
		new Patch(REED, .02, .2, .8, .1),       // reed
		new Patch(FLUTE, .04, .2, .85, .12),    // pipe
		new Patch(SQUARE, .005, .2, .8, .1),    // synth lead
		new Patch(SAW, .25, .5, .8, .5),        // synth pad
		new Patch(TRIANGLE, .1, .5, .7, .5),    // synth effects
		new Patch(TRIANGLE, .003, .8, 0, .2),   // ethnic
		new Patch(SINE, .002, .4, 0, .15),      // percussive
		new Patch(SINE, .01, .3, .6, .3),       // sound effects
	};

	private final int[] program = new int[16], volume = new int[16], expression = new int[16], pan = new int[16], bend = new int[16];
	private final boolean[] sustain = new boolean[16];

	private static final class Voice {
		boolean on, held, released; int channel, note; Patch patch; int drum; // drum: 0 a pitched note, else its kind
		double phase, step, bendStep; float gain, level, pitchDrop; long age; int noiseState = 22222; float noiseLow;
		float releaseStart; long releasedAt;
	}
	private final Voice[] voices = new Voice[VOICES];
	private long ages;

	MidiSynth68k(Sequence sequence) {
		for (int i = 0; i < VOICES; i++) voices[i] = new Voice();
		// all the tracks' events in tick order (ties keep the tracks' order), times through the tempo events
		List<long[]> events = new ArrayList<>(); // {tick, order, kind (0 short, 1 tempo), value}
		long order = 0;
		for (Track t : sequence.getTracks()) for (int i = 0; i < t.size(); i++) {
			MidiEvent e = t.get(i);
			MidiMessage m = e.getMessage();
			if (m instanceof ShortMessage) {
				ShortMessage s = (ShortMessage) m;
				events.add(new long[] {e.getTick(), order++, 0, s.getStatus() | s.getData1() << 8 | s.getData2() << 16});
			} else if (m instanceof MetaMessage && ((MetaMessage) m).getType() == 0x51) {
				byte[] d = ((MetaMessage) m).getData();
				if (d.length >= 3) events.add(new long[] {e.getTick(), order++, 1, (d[0] & 0xff) << 16 | (d[1] & 0xff) << 8 | (d[2] & 0xff)});
			}
		}
		events.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
		int resolution = Math.max(1, sequence.getResolution());
		boolean ppq = sequence.getDivisionType() == Sequence.PPQ;
		double framesPerTick = ppq ? 500000.0 / resolution * RATE / 1e6 : RATE / (sequence.getDivisionType() * resolution);
		double at = 0; long lastTick = 0;
		List<long[]> shorts = new ArrayList<>();
		for (long[] e : events) {
			at += (e[0] - lastTick) * framesPerTick; lastTick = e[0];
			if (e[2] == 1) { if (ppq) framesPerTick = e[3] / (double) resolution * RATE / 1e6; }
			else shorts.add(new long[] {(long) at, e[3]});
		}
		at += (sequence.getTickLength() - lastTick) * framesPerTick;
		when = new long[shorts.size()]; what = new int[shorts.size()];
		for (int i = 0; i < shorts.size(); i++) { when[i] = shorts.get(i)[0]; what[i] = (int) shorts.get(i)[1]; }
		endFrame = (long) at;
		reset();
	}

	/** The music's length in output frames (without the last notes' release) */
	long frames() { return endFrame; }

	private void reset() {
		for (int c = 0; c < 16; c++) { program[c] = 0; volume[c] = 100; expression[c] = 127; pan[c] = 64; bend[c] = 0; sustain[c] = false; }
		for (Voice v : voices) v.on = false;
	}

	/** Plays from that output frame on: the channels as the events before it left them, no notes sounding */
	void seek(long to) {
		reset();
		next = 0; frame = Math.max(0, to);
		while (next < when.length && when[next] < frame) {
			int m = what[next++], command = m & 0xF0;
			if (command != 0x90 && command != 0x80) event(m); // (a note before the position does not sound)
		}
	}

	/** Renders the next frames into left and right; the count, 0 at the end once every note has died away */
	int fill(short[] left, short[] right) {
		int n = left.length;
		boolean anyVoice = false;
		for (int i = 0; i < n; i++, frame++) {
			while (next < when.length && when[next] <= frame) event(what[next++]);
			float l = 0, r = 0;
			for (Voice v : voices) {
				if (!v.on) continue;
				float s = sample(v);
				if (!v.on) continue;
				int p = pan[v.channel];
				float pr = p / 127f, pl = 1 - pr;
				l += s * (0.6f + 0.8f * pl) * 0.5f; r += s * (0.6f + 0.8f * pr) * 0.5f;
			}
			left[i] = clip(l); if (right != left) right[i] = clip(r); else left[i] = clip((l + r) / 2);
		}
		for (Voice v : voices) if (v.on) anyVoice = true;
		if (frame >= endFrame && next >= when.length && !anyVoice) return 0;
		return n;
	}
	private static short clip(float x) { int v = Math.round(x * 32767); return (short) Math.max(-32768, Math.min(32767, v)); }

	/** One sample of a voice, its envelope moved on (the voice ends when it has died away) */
	private float sample(Voice v) {
		float env;
		double t = (double) (frame - v.age) / RATE;
		if (v.drum != 0) return drum(v, t);
		Patch p = v.patch;
		if (v.released) {
			double rt = (double) (frame - v.releasedAt) / RATE;
			env = (float) (v.releaseStart * Math.exp(-rt * 5 / Math.max(0.02, p.release)));
			if (env < 0.0005f) { v.on = false; return 0; }
		} else if (t < p.attack) env = (float) (t / p.attack);
		else {
			double d = t - p.attack;
			env = (float) (p.sustain + (1 - p.sustain) * Math.exp(-d * 4 / Math.max(0.02, p.decay)));
			if (p.sustain == 0 && env < 0.0005f) { v.on = false; return 0; }
		}
		v.level = env;
		float[] w = WAVES[p.wave];
		double step = v.step * bendFactor(v.channel);
		v.phase += step; if (v.phase >= 1) v.phase -= Math.floor(v.phase);
		float s = w[(int) (v.phase * TABLE) & (TABLE - 1)];
		return s * env * v.gain * channelGain(v.channel);
	}
	private float drum(Voice v, double t) {
		float g = v.gain * channelGain(9), s;
		switch (v.drum) {
			case 1: { // kick: a falling sine
				double f = 50 + 90 * Math.exp(-t * 30);
				v.phase += f / RATE; s = (float) Math.sin(2 * Math.PI * v.phase) * (float) Math.exp(-t * 9); if (t > .5) v.on = false; return s * g * 1.4f; }
			case 2: { // snare: noise and a tone
				float noise = noise(v); v.phase += 185.0 / RATE;
				s = (float) ((noise * 0.8 + 0.4 * Math.sin(2 * Math.PI * v.phase)) * Math.exp(-t * 18)); if (t > .4) v.on = false; return s * g; }
			case 3: { // closed hi-hat: bright noise, short
				float noise = noise(v), high = noise - v.noiseLow; v.noiseLow = v.noiseLow * 0.7f + noise * 0.3f;
				s = (float) (high * Math.exp(-t * 60)); if (t > .15) v.on = false; return s * g * 0.6f; }
			case 4: { // open hi-hat
				float noise = noise(v), high = noise - v.noiseLow; v.noiseLow = v.noiseLow * 0.7f + noise * 0.3f;
				s = (float) (high * Math.exp(-t * 7)); if (t > .8) v.on = false; return s * g * 0.5f; }
			case 5: { // tom: a falling tone of the note's height
				double f = (80 + (v.note - 41) * 12) * (1 + 0.5 * Math.exp(-t * 20));
				v.phase += f / RATE; s = (float) (Math.sin(2 * Math.PI * v.phase) * Math.exp(-t * 8)); if (t > .6) v.on = false; return s * g; }
			case 6: { // cymbal: long noise
				float noise = noise(v), high = noise - v.noiseLow; v.noiseLow = v.noiseLow * 0.6f + noise * 0.4f;
				s = (float) (high * Math.exp(-t * 2.5)); if (t > 2.5) v.on = false; return s * g * 0.45f; }
			default: { // the rest: a short click of noise
				s = (float) (noise(v) * Math.exp(-t * 30)); if (t > .2) v.on = false; return s * g * 0.5f; }
		}
	}
	private static float noise(Voice v) { v.noiseState = v.noiseState * 1103515245 + 12345; return ((v.noiseState >>> 16) & 0x7fff) / 16384f - 1; }
	private float channelGain(int c) { return volume[c] / 127f * expression[c] / 127f * 0.22f; }
	private double bendFactor(int c) { return bend[c] == 0 ? 1 : Math.pow(2, bend[c] / 8192.0 * 2 / 12); }

	private void event(int m) {
		int command = m & 0xF0, c = m & 0x0F, d1 = (m >> 8) & 0x7F, d2 = (m >> 16) & 0x7F;
		switch (command) {
			case 0x90: if (d2 == 0) noteOff(c, d1); else noteOn(c, d1, d2); break;
			case 0x80: noteOff(c, d1); break;
			case 0xC0: program[c] = d1; break;
			case 0xE0: bend[c] = (d1 | d2 << 7) - 8192; break;
			case 0xB0:
				switch (d1) {
					case 7: volume[c] = d2; break;
					case 11: expression[c] = d2; break;
					case 10: pan[c] = d2; break;
					case 64: sustain[c] = d2 >= 64; if (!sustain[c]) for (Voice v : voices) if (v.on && v.channel == c && v.held) { v.held = false; release(v); } break;
					case 120: case 123: for (Voice v : voices) if (v.on && v.channel == c) { if (d1 == 120) v.on = false; else release(v); } break;
					case 121: volume[c] = 100; expression[c] = 127; pan[c] = 64; bend[c] = 0; sustain[c] = false; break;
					default: break;
				}
				break;
			default: break;
		}
	}
	private void noteOn(int c, int note, int velocity) {
		Voice v = null;
		for (Voice x : voices) if (!x.on) { v = x; break; }
		if (v == null) { // the quietest released voice, else the oldest
			for (Voice x : voices) if (x.released && (v == null || x.level < v.level)) v = x;
			if (v == null) for (Voice x : voices) if (v == null || x.age < v.age) v = x;
		}
		v.on = true; v.held = false; v.released = false; v.channel = c; v.note = note; v.age = frame; v.phase = 0; v.noiseLow = 0;
		v.gain = velocity / 127f;
		v.drum = c == 9 ? drumKind(note) : 0;
		v.patch = FAMILIES[(program[c] >> 3) & 15];
		v.step = 440 * Math.pow(2, (note - 69) / 12.0) / RATE;
	}
	private static int drumKind(int note) {
		switch (note) {
			case 35: case 36: return 1;
			case 37: case 38: case 39: case 40: return 2;
			case 42: case 44: return 3;
			case 46: return 4;
			case 41: case 43: case 45: case 47: case 48: case 50: return 5;
			case 49: case 51: case 52: case 53: case 55: case 57: case 59: return 6;
			default: return 7;
		}
	}
	private void noteOff(int c, int note) {
		for (Voice v : voices) if (v.on && !v.released && v.channel == c && v.note == note) { if (sustain[c]) v.held = true; else release(v); }
	}
	private void release(Voice v) { if (v.drum != 0 || v.released) return; v.released = true; v.releasedAt = frame; v.releaseStart = v.level; }
}

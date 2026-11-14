/**
 * A sampled sound as 16-bit samples without the JDK's sound classes (the browser player): a stack's 'snd ' resource as
 * the Sound Manager reads it (format 1 or 2; the standard, extended and compressed sound headers; MACE 3:1 and 6:1, IMA 4:1
 * and plain PCM), an AIFF or AIFF-C file, a WAV file. With the base note and loop that notes are played from (TSound's
 * rules: the header's baseFrequency and loop, AIFF's INST chunk).
 */
final class HzSound {
	/** the samples of the left and the right channel (the same array for a mono sound) */
	final short[] left, right;
	final double rate;
	/** the MIDI note the sound was recorded at (60 = middle C when unknown) and its loop in frames (0, 0: none) */
	int baseNote = 60, loopStart, loopEnd;
	/** whether the file said its base note (a 'snd ' header, AIFF's INST chunk): HyperCard's own sounds exported as plain
	 *  AIFF (Home's SND_128.aiff Harpsichord) do not, and take their notes from builtIn by name */
	boolean noteKnown;

	HzSound(short[] left, short[] right, double rate) { this.left = left; this.right = right == null ? left : right; this.rate = rate; }

	int frames() { return left.length; }

	/**
	 * HyperCard's own sounds without their 'snd ' header (Home's SND_128.aiff, resource/harpsichord.wav): the base notes and
	 * loop of its snd 128 Harpsichord, 129 Boing and 146 flute, as TSound.builtInNoteInfo (HyperCard J-2.3 measured: c4 is
	 * freqDurationCmd 60 on its Harpsichord, base note 67). Other sounds keep middle C.
	 */
	void builtInNote(String name) {
		if (noteKnown || name == null) return;
		switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "harpsichord": baseNote = 67; loopStart = 0; loopEnd = 0; break;
			case "boing": baseNote = 74; loopStart = 0; loopEnd = 0; break;
			case "flute": baseNote = 57; loopStart = 744; loopEnd = 1300; break;
			default:
		}
	}
	boolean stereo() { return right != left; }
	/** loopEnd > loopStart + 1 is a real loop (Harpsichord's 5263-5264 is not) */
	boolean loops() { return loopStart >= 0 && loopEnd > loopStart + 1 && loopEnd <= frames(); }

	/** The sound in these bytes ('snd ', AIFF/AIFC, WAV), or null when it is none of these or not decodable */
	static HzSound decode(byte[] b) {
		if (b == null || b.length < 12) return null;
		try {
			if (b[0] == 'F' && b[1] == 'O' && b[2] == 'R' && b[3] == 'M') return aiff(b);
			if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return wav(b);
			return snd(b);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- 'snd '

	static HzSound snd(byte[] b) {
		int format = u16(b, 0), commands, at;
		boolean mace3 = false, mace6 = false;
		if (format == 1) {
			int synths = u16(b, 2);
			for (int i = 0; i < synths; i++) { long init = u32(b, 6 + i * 6); if ((init & 0x0700) == 0x0400) mace6 = true; else if ((init & 0x0300) == 0x0300) mace3 = true; }
			at = 4 + synths * 6;
		} else if (format == 2) at = 4;
		else return null;
		commands = u16(b, at);
		at += 2;
		for (int i = 0; i < commands; i++) {
			int o = at + i * 8, cmd = u16(b, o) & 0x7FFF;
			if (cmd != 0x50 && cmd != 0x51) continue; // soundCmd, bufferCmd
			int h = (int) u32(b, o + 4);
			if (h + 22 > b.length) return null;
			double rate = u32(b, h + 8) / 65536.0;
			int encode = b[h + 20] & 0xFF, base = b[h + 21] & 0xFF;
			HzSound s;
			if (encode == 0x00) { // standard: 8-bit offset binary, mono
				int len = (int) Math.min(u32(b, h + 4), b.length - (h + 22L));
				if (mace3 || mace6) s = pcm8(mace3 ? MaceCodec.decode3(b, h + 22, len, 1) : MaceCodec.decode6(b, h + 22, len, 1), 1, rate);
				else s = pcm8(java.util.Arrays.copyOfRange(b, h + 22, h + 22 + len), 1, rate);
				s.loopStart = (int) u32(b, h + 12); s.loopEnd = (int) u32(b, h + 16);
			} else if (encode == 0xFF) { // extended: 8 or 16 bits, 1 or 2 channels
				int channels = (int) u32(b, h + 4), frames = (int) u32(b, h + 22), bits = u16(b, h + 48);
				if (channels < 1 || channels > 2) return null;
				int bytes = (int) Math.min((long) frames * channels * (bits / 8), b.length - (h + 64L));
				s = bits == 16 ? pcm16(b, h + 64, bytes, channels, false, rate) : pcm8(java.util.Arrays.copyOfRange(b, h + 64, h + 64 + bytes), channels, rate);
				s.loopStart = (int) u32(b, h + 12); s.loopEnd = (int) u32(b, h + 16);
			} else if (encode == 0xFE) { // compressed
				int channels = (int) u32(b, h + 4), frames = (int) u32(b, h + 22), id = (short) u16(b, h + 56), bits = u16(b, h + 62);
				String type = fourCC(b, h + 40);
				if (channels < 1 || channels > 2) return null;
				int data = h + 64;
				if (id == 3 || "MAC3".equals(type)) s = pcm8(MaceCodec.decode3(b, data, (int) Math.min((long) frames * 2 * channels, b.length - data), channels), channels, rate);
				else if (id == 4 || "MAC6".equals(type)) s = pcm8(MaceCodec.decode6(b, data, (int) Math.min((long) frames * channels, b.length - data), channels), channels, rate);
				else if ("ima4".equals(type)) s = ima4(b, data, (int) Math.min((long) frames * 34 * channels, b.length - data), channels, rate);
				else if ("twos".equals(type) || "sowt".equals(type) || "NONE".equals(type) && bits == 16) {
					int bytes = (int) Math.min((long) frames * channels * 2, b.length - data);
					s = pcm16(b, data, bytes, channels, "sowt".equals(type), rate);
				} else if ("raw ".equals(type) || "NONE".equals(type)) {
					int bytes = (int) Math.min((long) frames * channels, b.length - data);
					s = pcm8(java.util.Arrays.copyOfRange(b, data, data + bytes), channels, rate);
				} else return null;
			} else return null;
			s.baseNote = base <= 0 || base > 127 ? 60 : base;
			s.noteKnown = true;
			if (encode == 0xFE) { s.loopStart = 0; s.loopEnd = 0; } // a compressed header's loop is left unlooped (TSound)
			return s;
		}
		return null;
	}

	// ---------------------------------------------------------------- AIFF, WAV

	static HzSound aiff(byte[] b) {
		boolean aifc = fourCC(b, 8).equals("AIFC");
		int channels = 0, frames = 0, bits = 16, ssnd = -1, ssndLen = 0, inst = -1, mark = -1;
		double rate = 0;
		String comp = "NONE";
		for (int p = 12; p + 8 <= b.length; ) {
			String id = fourCC(b, p);
			int length = (int) u32(b, p + 4);
			if (length < 0) break;
			int d = p + 8;
			switch (id) {
				case "COMM":
					channels = u16(b, d); frames = (int) u32(b, d + 2); bits = u16(b, d + 6); rate = extended(b, d + 8);
					if (aifc && length >= 22) comp = fourCC(b, d + 18);
					break;
				case "SSND": ssnd = d + 8 + (int) u32(b, d); ssndLen = length - 8 - (int) u32(b, d); break;
				case "INST": inst = d; break;
				case "MARK": mark = d; break;
				default:
			}
			p = d + length + (length & 1);
		}
		if (ssnd < 0 || channels < 1 || channels > 2 || rate <= 0) return null;
		ssndLen = Math.max(0, Math.min(ssndLen, b.length - ssnd));
		HzSound s;
		switch (comp) {
			case "NONE": case "twos": s = bits <= 8 ? pcm8signed(b, ssnd, Math.min(ssndLen, frames * channels), channels, rate) : pcm16(b, ssnd, Math.min(ssndLen, frames * channels * 2), channels, false, rate); break;
			case "sowt": s = pcm16(b, ssnd, Math.min(ssndLen, frames * channels * 2), channels, true, rate); break;
			case "MAC3": s = pcm8(MaceCodec.decode3(b, ssnd, ssndLen, channels), channels, rate); break;
			case "MAC6": s = pcm8(MaceCodec.decode6(b, ssnd, ssndLen, channels), channels, rate); break;
			case "ima4": s = ima4(b, ssnd, ssndLen, channels, rate); break;
			default: return null;
		}
		if (inst >= 0) {
			s.baseNote = (b[inst] & 0xFF) <= 0 || (b[inst] & 0xFF) > 127 ? 60 : b[inst] & 0xFF;
			s.noteKnown = true;
			if (u16(b, inst + 8) != 0 && mark >= 0) { // sustain loop: its begin and end markers
				int begin = u16(b, inst + 10), end = u16(b, inst + 12), count = u16(b, mark), q = mark + 2;
				for (int i = 0; i < count; i++) {
					int id = u16(b, q), pos = (int) u32(b, q + 2), n = b[q + 6] & 0xFF;
					if (id == begin) s.loopStart = pos;
					if (id == end) s.loopEnd = pos;
					q += 7 + n + ((n + 1) & 1);
				}
			}
		}
		return s;
	}

	static HzSound wav(byte[] b) {
		if (!fourCC(b, 8).equals("WAVE")) return null;
		int channels = 0, bits = 0, format = 0, data = -1, dataLen = 0;
		double rate = 0;
		for (int p = 12; p + 8 <= b.length; ) {
			String id = fourCC(b, p);
			int length = le32(b, p + 4), d = p + 8;
			if (length < 0) break;
			if (id.equals("fmt ")) { format = le16(b, d); channels = le16(b, d + 2); rate = le32(b, d + 4); bits = le16(b, d + 14); }
			else if (id.equals("data")) { data = d; dataLen = Math.min(length, b.length - d); }
			p = d + length + (length & 1);
		}
		if (format != 1 || data < 0 || channels < 1 || channels > 2 || rate <= 0) return null;
		if (bits == 8) return pcm8(java.util.Arrays.copyOfRange(b, data, data + dataLen), channels, rate);
		if (bits == 16) return pcm16(b, data, dataLen, channels, true, rate);
		return null;
	}

	// ---------------------------------------------------------------- sample formats

	/** 8-bit offset binary (0x80 = 0) */
	static HzSound pcm8(byte[] u, int channels, double rate) {
		int frames = u.length / channels;
		short[] l = new short[frames], r = channels == 2 ? new short[frames] : l;
		for (int i = 0; i < frames; i++) {
			l[i] = (short) (((u[i * channels] & 0xFF) - 128) << 8);
			if (channels == 2) r[i] = (short) (((u[i * 2 + 1] & 0xFF) - 128) << 8);
		}
		return new HzSound(l, r, rate);
	}

	/** 8-bit two's complement (AIFF) */
	static HzSound pcm8signed(byte[] b, int at, int len, int channels, double rate) {
		int frames = len / channels;
		short[] l = new short[frames], r = channels == 2 ? new short[frames] : l;
		for (int i = 0; i < frames; i++) {
			l[i] = (short) (b[at + i * channels] << 8);
			if (channels == 2) r[i] = (short) (b[at + i * 2 + 1] << 8);
		}
		return new HzSound(l, r, rate);
	}

	static HzSound pcm16(byte[] b, int at, int len, int channels, boolean littleEndian, double rate) {
		int frames = len / (2 * channels);
		short[] l = new short[frames], r = channels == 2 ? new short[frames] : l;
		for (int i = 0; i < frames; i++) {
			int o = at + i * 2 * channels;
			l[i] = littleEndian ? (short) ((b[o] & 0xFF) | b[o + 1] << 8) : (short) (b[o] << 8 | (b[o + 1] & 0xFF));
			if (channels == 2) r[i] = littleEndian ? (short) ((b[o + 2] & 0xFF) | b[o + 3] << 8) : (short) (b[o + 2] << 8 | (b[o + 3] & 0xFF));
		}
		return new HzSound(l, r, rate);
	}

	private static final int[] IMA_STEPS = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
		107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
		1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635,
		13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
	private static final int[] IMA_INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

	/** Apple's IMA 4:1: packets of 34 bytes (a 2-byte predictor and step index, 64 samples in nibbles, low first), one per channel in turn */
	static HzSound ima4(byte[] b, int at, int len, int channels, double rate) {
		int packets = len / 34 / channels;
		short[] l = new short[packets * 64], r = channels == 2 ? new short[packets * 64] : l;
		int o = at;
		for (int p = 0; p < packets; p++) {
			for (int c = 0; c < channels; c++) {
				short[] out = c == 0 ? l : r;
				int head = u16(b, o);
				int predictor = (short) (head & 0xFF80), index = Math.min(88, head & 0x7F);
				for (int i = 0; i < 64; i++) {
					int nib = (b[o + 2 + i / 2] >> ((i & 1) * 4)) & 15, step = IMA_STEPS[index];
					int diff = step >> 3;
					if ((nib & 4) != 0) diff += step;
					if ((nib & 2) != 0) diff += step >> 1;
					if ((nib & 1) != 0) diff += step >> 2;
					predictor += (nib & 8) != 0 ? -diff : diff;
					predictor = Math.max(-32768, Math.min(32767, predictor));
					index = Math.max(0, Math.min(88, index + IMA_INDEX[nib]));
					out[p * 64 + i] = (short) predictor;
				}
				o += 34;
			}
		}
		return new HzSound(l, r, rate);
	}

	// ---------------------------------------------------------------- bytes

	static int u16(byte[] b, int o) { return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }
	static long u32(byte[] b, int o) { return (long) u16(b, o) << 16 | u16(b, o + 2); }
	static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
	static int le32(byte[] b, int o) { return le16(b, o) | le16(b, o + 2) << 16; }
	static String fourCC(byte[] b, int o) { return o + 4 > b.length ? "" : new String(new char[]{(char) (b[o] & 0xFF), (char) (b[o + 1] & 0xFF), (char) (b[o + 2] & 0xFF), (char) (b[o + 3] & 0xFF)}); }

	/** An 80-bit IEEE extended number (AIFF's sample rate) */
	static double extended(byte[] b, int o) {
		int exp = u16(b, o) & 0x7FFF;
		long mant = u32(b, o + 2) << 32 | u32(b, o + 6);
		if (exp == 0 && mant == 0) return 0;
		double m = (mant >>> 11) * Math.pow(2, -52);
		return ((b[o] & 0x80) != 0 ? -1 : 1) * m * Math.pow(2, exp - 16383);
	}
}

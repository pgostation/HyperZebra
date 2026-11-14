import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.HashMap;

/**
 * Charsets of the core for the encodings TeaVM lacks (PortablePlatform.charset): the Mac OS single-byte ones and
 * Shift_JIS, from the JDK's own tables (CharsetData, written by tools/wasm-core/CharsetTables.java). They report what the
 * JDK's (sun.nio.cs SingleByte / DoubleByte SJIS) report: an unmapped byte or character is unmappable, a lead byte with a
 * trail byte out of range or a lone surrogate malformed (tools/wasm-core/CharsetCheck.java compares them).
 */
final class TableCharset extends Charset {
	private static final char NONE = '￿';
	private final String decode;     // single bytes, then (double-byte) 189 per lead byte
	private final String extra;      // char, encoding: what the inverse of decode does not give
	private final boolean doubleByte;
	private volatile HashMap<Character, Integer> encodeMap;

	private TableCharset(String name, String decode, String extra, boolean doubleByte) {
		super(name, new String[0]); // (TeaVM's Charset takes no null aliases)
		this.decode = decode; this.extra = extra; this.doubleByte = doubleByte;
	}

	private static final HashMap<String, TableCharset> made = new HashMap<>();

	/** The core's charset of that name, or null when it has none. */
	static synchronized Charset named(String name) {
		String key = name.toLowerCase(java.util.Locale.ROOT);
		if (key.equals("sjis") || key.equals("shift-jis") || key.equals("ms_kanji") || key.equals("csshiftjis")) key = "shift_jis";
		// Windows' Shift_JIS (the 68K VM's MS932: kana and kanji classes): the same table here
		if (key.equals("ms932") || key.equals("windows-31j") || key.equals("cp932")) key = "shift_jis";
		if (key.equals("macroman")) { key = "x-macroman"; name = "x-MacRoman"; }
		TableCharset c = made.get(key);
		if (c != null) return c;
		if (key.equals("shift_jis")) c = new TableCharset("Shift_JIS", CharsetData.SHIFT_JIS_DECODE, CharsetData.SHIFT_JIS_EXTRA, true);
		else for (int i = 0; i < CharsetData.SINGLE_BYTE.length; i += 2) {
			if (!CharsetData.SINGLE_BYTE[i].equalsIgnoreCase(name)) continue;
			c = new TableCharset(CharsetData.SINGLE_BYTE[i], table(CharsetData.SINGLE_BYTE[i + 1], "_DECODE"), table(CharsetData.SINGLE_BYTE[i + 1], "_EXTRA"), false);
		}
		if (c != null) made.put(key, c);
		return c;
	}

	private static String table(String id, String part) {
		switch (id + part) {
			case "MACROMAN_DECODE": return CharsetData.MACROMAN_DECODE; case "MACROMAN_EXTRA": return CharsetData.MACROMAN_EXTRA;
			case "MACCENTRALEUROPE_DECODE": return CharsetData.MACCENTRALEUROPE_DECODE; case "MACCENTRALEUROPE_EXTRA": return CharsetData.MACCENTRALEUROPE_EXTRA;
			case "MACCROATIAN_DECODE": return CharsetData.MACCROATIAN_DECODE; case "MACCROATIAN_EXTRA": return CharsetData.MACCROATIAN_EXTRA;
			case "MACCYRILLIC_DECODE": return CharsetData.MACCYRILLIC_DECODE; case "MACCYRILLIC_EXTRA": return CharsetData.MACCYRILLIC_EXTRA;
			case "MACGREEK_DECODE": return CharsetData.MACGREEK_DECODE; case "MACGREEK_EXTRA": return CharsetData.MACGREEK_EXTRA;
			case "MACHEBREW_DECODE": return CharsetData.MACHEBREW_DECODE; case "MACHEBREW_EXTRA": return CharsetData.MACHEBREW_EXTRA;
			case "MACICELAND_DECODE": return CharsetData.MACICELAND_DECODE; case "MACICELAND_EXTRA": return CharsetData.MACICELAND_EXTRA;
			case "MACROMANIA_DECODE": return CharsetData.MACROMANIA_DECODE; case "MACROMANIA_EXTRA": return CharsetData.MACROMANIA_EXTRA;
			case "MACTHAI_DECODE": return CharsetData.MACTHAI_DECODE; case "MACTHAI_EXTRA": return CharsetData.MACTHAI_EXTRA;
			case "MACTURKISH_DECODE": return CharsetData.MACTURKISH_DECODE; case "MACTURKISH_EXTRA": return CharsetData.MACTURKISH_EXTRA;
			case "MACUKRAINE_DECODE": return CharsetData.MACUKRAINE_DECODE; case "MACUKRAINE_EXTRA": return CharsetData.MACUKRAINE_EXTRA;
			case "MACARABIC_DECODE": return CharsetData.MACARABIC_DECODE; case "MACARABIC_EXTRA": return CharsetData.MACARABIC_EXTRA;
			default: throw new IllegalArgumentException(id);
		}
	}

	@Override public boolean contains(Charset cs) { return cs == this; }

	/** The decoded char of a byte pair, NONE when nothing; lead must be a lead byte. */
	private char pair(int lead, int trail) {
		if (trail < 0x40 || trail > 0xFC) return NONE;
		int row = lead <= 0x9F ? lead - 0x81 : lead - 0xE0 + 31;
		return decode.charAt(256 + row * 189 + (trail - 0x40));
	}
	private static boolean isLead(int b) { return (b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC); }
	private boolean[] rows;
	/** A lead byte whose row has a character (the JDK's b2c[lead] is a table, not B2C_UNMAPPABLE). */
	private boolean rowDefined(int b) {
		boolean[] r = rows;
		if (r == null) {
			r = new boolean[256];
			for (int lead = 0; lead < 256; lead++) {
				if (!isLead(lead)) continue;
				for (int t = 0x40; t <= 0xFC && !r[lead]; t++) if (pair(lead, t) != NONE) r[lead] = true;
			}
			rows = r;
		}
		return r[b];
	}

	private HashMap<Character, Integer> encodeMap() {
		HashMap<Character, Integer> m = encodeMap;
		if (m != null) return m;
		m = new HashMap<>();
		for (int b = 0; b < 256; b++) { char c = decode.charAt(b); if (c != NONE) m.putIfAbsent(c, b); }
		if (doubleByte) {
			for (int lead = 0x81; lead <= 0xFC; lead++) {
				if (!isLead(lead)) continue;
				for (int t = 0x40; t <= 0xFC; t++) { char c = pair(lead, t); if (c != NONE) m.putIfAbsent(c, lead << 8 | t); }
			}
		}
		for (int i = 0; i + 1 < extra.length(); i += 2) m.put(extra.charAt(i), (int) extra.charAt(i + 1));
		encodeMap = m;
		return m;
	}

	@Override public CharsetDecoder newDecoder() {
		return new CharsetDecoder(this, 1f, 1f) {
			@Override protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
				while (in.hasRemaining()) {
					int p = in.position();
					int b1 = in.get(p) & 0xff;
					char c = decode.charAt(b1);
					int size = 1;
					if (c == NONE && doubleByte && rowDefined(b1)) {
						if (in.remaining() < 2) return CoderResult.UNDERFLOW;
						int b2 = in.get(p + 1) & 0xff;
						c = pair(b1, b2);
						if (c == NONE) {
							// as the JDK's DoubleByte.crMalformedOrUnmappable: a second byte that starts a pair or is a character
							// of its own is malformed (the first byte alone), any other the pair unmappable
							if (rowDefined(b2) || decode.charAt(b2) != NONE) return CoderResult.malformedForLength(1);
							return CoderResult.unmappableForLength(2);
						}
						size = 2;
					}
					else if (c == NONE) return doubleByte ? CoderResult.malformedForLength(1) : CoderResult.unmappableForLength(1);
					if (!out.hasRemaining()) return CoderResult.OVERFLOW;
					out.put(c);
					in.position(p + size);
				}
				return CoderResult.UNDERFLOW;
			}
		};
	}

	@Override public CharsetEncoder newEncoder() {
		final HashMap<Character, Integer> map = encodeMap();
		return new CharsetEncoder(this, doubleByte ? 2f : 1f, doubleByte ? 2f : 1f, new byte[]{(byte) '?'}) {
			@Override protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
				while (in.hasRemaining()) {
					int p = in.position();
					char c = in.get(p);
					Integer v = map.get(c);
					if (v == null) {
						if (Character.isHighSurrogate(c)) {
							if (in.remaining() < 2) return CoderResult.UNDERFLOW;
							return Character.isLowSurrogate(in.get(p + 1)) ? CoderResult.unmappableForLength(2) : CoderResult.malformedForLength(1);
						}
						if (Character.isLowSurrogate(c)) return CoderResult.malformedForLength(1);
						return CoderResult.unmappableForLength(1);
					}
					int b = v;
					if (b > 0xff) {
						if (out.remaining() < 2) return CoderResult.OVERFLOW;
						out.put((byte) (b >> 8)); out.put((byte) b);
					}
					else {
						if (!out.hasRemaining()) return CoderResult.OVERFLOW;
						out.put((byte) b);
					}
					in.position(p + 1);
				}
				return CoderResult.UNDERFLOW;
			}
			@Override public boolean isLegalReplacement(byte[] repl) { return true; }
		};
	}
}

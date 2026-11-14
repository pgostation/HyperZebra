import java.util.HashMap;
import java.util.Map;

/**
 * The player's StackStrikeFont: a font only the stack has, drawn from the glyphs of its own 'FONT'/'NFNT' strike (the
 * stack's FONT_*.png and its fontinfo) for a font name the player shows no family for (HzFonts.shows). Wings -翼の軌跡-
 * and 東海道森林大爆走 draw their maps with the letters of a 32-point "map" font whose glyphs are map tiles; the browser's
 * font in its place showed letters. The desktop builds a TrueType font of the strike; the Gfx here draws each glyph's
 * pixels as a picture in the text colour, the advances from the strike's width table, ascent/descent/leading from its
 * header; at another size than the strike's the pixels are scaled, as the Font Manager scaled a bitmap-only family.
 */
final class HzStrikeFont {
	final int size, strikeSize, ascent, descent, leading, kernMax;
	private final int firstChar, lastChar, height;
	private final int[] from, to, offset, advance;
	private final boolean[][] bits;
	/** code → glyph pictures per colour */
	private final Map<Long, HzImage> glyphs = new HashMap<>();
	private final Map<Character, Integer> codes;

	private HzStrikeFont(Rsrc.FontInfo info, HzImage image, int strikeSize, int size) {
		this.size = size; this.strikeSize = strikeSize;
		firstChar = info.firstChar; lastChar = info.lastChar;
		kernMax = (short) info.kernMax;
		ascent = info.ascent; descent = info.descent; leading = info.leading;
		height = Math.min(image.height, info.fRectHeight > 0 ? info.fRectHeight : image.height);
		int n = lastChar - firstChar + 2;
		from = new int[n]; to = new int[n]; offset = new int[n]; advance = new int[n];
		int[] locs = StrikeWidths.locs(info, image.width), ow = StrikeWidths.owWords(info, image.width);
		for (int i = 0; i < n; i++) {
			from[i] = Math.max(0, Math.min(locs[i], image.width));
			to[i] = Math.max(from[i], Math.min(locs[i + 1], image.width));
			offset[i] = ow[i] >> 8;
			advance[i] = ow[i] == 0xFFFF ? -1 : ow[i] & 0xFF;
		}
		bits = new boolean[height][image.width];
		for (int y = 0; y < height; y++) for (int x = 0; x < image.width; x++) {
			int p = image.get(x, y);
			bits[y][x] = ((p >>> 24) & 255) >= 128 && (p & 0xFFFFFF) < 0x808080;
		}
		codes = codes(firstChar, lastChar);
	}

	/** The characters a strike's byte code stands for (StackStrikeFont.unicodes): itself below $80, else MacRoman, half-width
	 *  katakana for $A1-$DF and the raw byte a Japanese stack's text keeps for a byte Shift-JIS has no character for */
	private static Map<Character, Integer> codes(int first, int last) {
		Map<Character, Integer> m = new HashMap<>();
		String roman = null;
		try {
			byte[] b = new byte[128];
			for (int i = 0; i < 128; i++) b[i] = (byte) (0x80 + i);
			roman = StackTextEncoding.ROMAN.decode(b);
		} catch (RuntimeException e) { }
		for (int c = Math.max(0, first); c <= Math.min(255, last); c++) {
			if (c < 0x80) { m.putIfAbsent((char) c, c); continue; }
			if (roman != null && roman.length() == 128) m.putIfAbsent(roman.charAt(c - 0x80), c);
			if (c >= 0xA1 && c <= 0xDF) m.putIfAbsent((char) (0xFF61 + c - 0xA1), c);
			m.putIfAbsent((char) (MacJapaneseDecoder.RAW_BYTES + c), c);
		}
		return m;
	}

	/** The glyph index of a character, or the missing glyph's; -1 when it has none either */
	private int index(char ch) {
		Integer c = codes.get(ch);
		int i = c == null ? -1 : c - firstChar;
		if (i < 0 || i > lastChar - firstChar || advance[i] < 0) i = lastChar - firstChar + 1;
		return advance[i] < 0 ? -1 : i;
	}

	private int scaled(int v) { return size == strikeSize ? v : (int) Math.round(v * (double) size / strikeSize); }

	int ascent() { return scaled(ascent); }
	int descent() { return scaled(descent); }
	int leading() { return scaled(leading); }

	/** The advance of the text in pixels: the strike's widths (summed at its size, then scaled) */
	int width(String s) {
		int w = 0;
		for (int k = 0; k < s.length(); k++) { int i = index(s.charAt(k)); if (i >= 0) w += advance[i]; }
		return scaled(w);
	}

	/** The text with its baseline at y in the colour */
	void draw(Gfx g, String s, int x, int y, int color) {
		int pen = 0; //strike pixels from x
		for (int k = 0; k < s.length(); k++) {
			int i = index(s.charAt(k));
			if (i < 0) continue;
			HzImage img = glyph(i, color);
			if (img != null) {
				int gx = x + scaled(pen + kernMax + offset[i]), gy = y - scaled(ascent);
				if (size == strikeSize) g.drawImage(img, gx, gy);
				else g.drawImage(img, gx, gy, Math.max(1, scaled(img.width)), Math.max(1, scaled(img.height)), false);
			}
			pen += advance[i];
		}
	}

	private HzImage glyph(int i, int color) {
		long key = (long) i << 32 | (color & 0xFFFFFFFFL);
		if (glyphs.containsKey(key)) return glyphs.get(key);
		int w = to[i] - from[i];
		HzImage img = null;
		if (w > 0 && height > 0) {
			img = new HzImage(w, height);
			boolean any = false;
			for (int y = 0; y < height; y++) for (int x = 0; x < w; x++) if (bits[y][from[i] + x]) { img.set(x, y, color); any = true; }
			if (!any) img = null;
		}
		if (glyphs.size() > 4096) glyphs.clear();
		glyphs.put(key, img);
		return img;
	}

	/** Strikes made so far: stack + "name/size" → font (null = none) */
	private static final Map<String, HzStrikeFont> made = new HashMap<>();
	private static final HzStrikeFont NONE = new HzStrikeFont();
	private HzStrikeFont() { size = strikeSize = ascent = descent = leading = kernMax = firstChar = lastChar = height = 0; from = to = offset = advance = null; bits = null; codes = null; }

	/** Whether the current stack draws text in this font from its own strike */
	static boolean has(String name) { return of(name, 12) != null; }

	/** The current stack's own font {@code name} at {@code size} (card pixels), or null when the player shows a family of that
	 *  name or the stack (and the stacks in use) has no strike of it */
	static HzStrikeFont of(String name, int size) {
		if (name == null || name.isEmpty() || size <= 0 || HzFonts.shows(name)) return null;
		OStack st = HZ.stack();
		if (st == null || st.rsrc == null) return null;
		String key = System.identityHashCode(st) + "/" + name.toLowerCase() + "/" + size;
		HzStrikeFont f;
		synchronized (made) { f = made.get(key); }
		if (f == null) {
			try { f = build(st, name, size); } catch (RuntimeException e) { f = null; }
			if (f == null) f = NONE;
			synchronized (made) { made.put(key, f); }
		}
		return f == NONE ? null : f;
	}

	/** Forgets the strikes made (a stack's fonts changed, or it closed) */
	static void forget() { synchronized (made) { made.clear(); } }

	private static HzStrikeFont build(OStack st, String name, int size) {
		Rsrc rsrc = st.rsrc;
		int id = strikeId(rsrc, name, size);
		for (int d = 1; d <= 127 && id == 0; d++) {
			id = strikeId(rsrc, name, size + d);
			if (id == 0 && size - d > 0) id = strikeId(rsrc, name, size - d);
		}
		if (id == 0) return null;
		Rsrc.rsrcClass r = rsrc.getResourceAll(id, "font");
		if (r == null || !(r.optionInfo instanceof Rsrc.FontInfo info) || info.locs == null || info.lastChar < info.firstChar) return null;
		HzImage image = HzImages.resource(st, id, "font");
		if (image == null || image.width <= 0) return null;
		int strikeSize = 0;
		for (int s = 1; s <= 255 && strikeSize == 0; s++) if (rsrc.fontStrikeIdAll(name, s) == id) strikeSize = s;
		if (strikeSize == 0) strikeSize = Math.max(1, id & 127);
		return new HzStrikeFont(info, image, strikeSize, size);
	}

	private static int strikeId(Rsrc rsrc, String name, int size) {
		int id = rsrc.fontStrikeIdAll(name, size);
		if (id == 0) return 0;
		Rsrc.rsrcClass r = rsrc.getResourceAll(id, "font");
		return r != null && r.optionInfo instanceof Rsrc.FontInfo ? id : 0;
	}
}

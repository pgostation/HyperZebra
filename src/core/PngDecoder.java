import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PNG to ARGB pixels in plain Java (P2: the browser's decoders are asynchronous, the player's worker never yields). All
 * colour types (grey, RGB, palette, grey+alpha, RGBA), bit depths 1 to 16, tRNS transparency, Adam7 interlacing, as
 * javax.imageio reads them into TYPE_INT_ARGB (16-bit samples to their high byte; tools/wasm-core/PngCheck.java compares).
 * Gamma and colour profiles are ignored, as by ImageIO's default reader.
 */
final class PngDecoder {
	private PngDecoder() {}

	static boolean isPng(byte[] b) {
		return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	/** The picture, or null when the bytes are no PNG this decoder reads. */
	static HzImage decode(byte[] b) {
		if (!isPng(b)) return null;
		try { return read(b); } catch (RuntimeException | DataFormatException e) { return null; }
	}

	private static int u32(byte[] b, int i) { return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF); }

	private static HzImage read(byte[] b) throws DataFormatException {
		int w = 0, h = 0, depth = 0, type = 0, interlace = 0;
		int[] palette = null; int[] trns = null; int trnsGrey = -1, trnsR = -1, trnsG = -1, trnsB = -1;
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		int p = 8;
		while (p + 8 <= b.length) {
			int len = u32(b, p);
			String t = new String(new char[]{(char) b[p + 4], (char) b[p + 5], (char) b[p + 6], (char) b[p + 7]});
			int d = p + 8;
			if (len < 0 || d + len > b.length) break;
			switch (t) {
				case "IHDR":
					w = u32(b, d); h = u32(b, d + 4); depth = b[d + 8] & 0xFF; type = b[d + 9] & 0xFF; interlace = b[d + 12] & 0xFF;
					break;
				case "PLTE":
					palette = new int[len / 3];
					for (int i = 0; i < palette.length; i++) palette[i] = 0xFF000000 | ((b[d + 3 * i] & 0xFF) << 16) | ((b[d + 3 * i + 1] & 0xFF) << 8) | (b[d + 3 * i + 2] & 0xFF);
					break;
				case "tRNS":
					if (type == 3) { trns = new int[len]; for (int i = 0; i < len; i++) trns[i] = b[d + i] & 0xFF; }
					else if (type == 0 && len >= 2) trnsGrey = ((b[d] & 0xFF) << 8) | (b[d + 1] & 0xFF);
					else if (type == 2 && len >= 6) { trnsR = ((b[d] & 0xFF) << 8) | (b[d + 1] & 0xFF); trnsG = ((b[d + 2] & 0xFF) << 8) | (b[d + 3] & 0xFF); trnsB = ((b[d + 4] & 0xFF) << 8) | (b[d + 5] & 0xFF); }
					break;
				case "IDAT":
					idat.write(b, d, len);
					break;
				default:
			}
			if (t.equals("IEND")) break;
			p = d + len + 4;
		}
		if (w <= 0 || h <= 0) return null;
		if (palette != null && trns != null) for (int i = 0; i < trns.length && i < palette.length; i++) palette[i] = (palette[i] & 0xFFFFFF) | (trns[i] << 24);
		int channels = switch (type) { case 0 -> 1; case 2 -> 3; case 3 -> 1; case 4 -> 2; case 6 -> 4; default -> 0; };
		if (channels == 0) return null;
		int bitsPerPixel = channels * depth, bpp = Math.max(1, bitsPerPixel / 8);
		Inflater inf = new Inflater();
		inf.setInput(idat.toByteArray());
		// the raw size: every pass's rows, each with its filter byte
		int[][] passes = interlace == 1 ? new int[][]{{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}}
			: new int[][]{{0, 0, 1, 1}};
		long total = 0;
		for (int[] ps : passes) {
			int pw = (w - ps[0] + ps[2] - 1) / ps[2], ph = (h - ps[1] + ps[3] - 1) / ps[3];
			if (pw > 0 && ph > 0) total += (long) ph * (1 + ((long) pw * bitsPerPixel + 7) / 8);
		}
		byte[] raw = new byte[(int) total];
		int got = 0;
		while (got < raw.length) {
			int n = inf.inflate(raw, got, raw.length - got);
			if (n == 0) { if (inf.finished() || inf.needsDictionary() || inf.needsInput()) break; }
			got += n;
		}
		inf.end();
		HzImage img = new HzImage(w, h);
		int at = 0;
		for (int[] ps : passes) {
			int pw = (w - ps[0] + ps[2] - 1) / ps[2], ph = (h - ps[1] + ps[3] - 1) / ps[3];
			if (pw <= 0 || ph <= 0) continue;
			int stride = (pw * bitsPerPixel + 7) / 8;
			byte[] prev = new byte[stride], cur = new byte[stride];
			for (int y = 0; y < ph; y++) {
				int filter = raw[at++] & 0xFF;
				System.arraycopy(raw, at, cur, 0, stride);
				at += stride;
				unfilter(filter, cur, prev, bpp);
				for (int x = 0; x < pw; x++) {
					int c = pixel(cur, x, type, depth, palette, trnsGrey, trnsR, trnsG, trnsB);
					img.argb[(ps[1] + y * ps[3]) * w + ps[0] + x * ps[2]] = c;
				}
				byte[] t = prev; prev = cur; cur = t;
			}
		}
		return img;
	}

	private static void unfilter(int f, byte[] cur, byte[] prev, int bpp) {
		for (int i = 0; i < cur.length; i++) {
			int a = i >= bpp ? cur[i - bpp] & 0xFF : 0, up = prev[i] & 0xFF, c = i >= bpp ? prev[i - bpp] & 0xFF : 0, x = cur[i] & 0xFF;
			switch (f) {
				case 1: x += a; break;
				case 2: x += up; break;
				case 3: x += (a + up) >>> 1; break;
				case 4: {
					int pp = a + up - c, pa = Math.abs(pp - a), pb = Math.abs(pp - up), pc = Math.abs(pp - c);
					x += pa <= pb && pa <= pc ? a : pb <= pc ? up : c;
					break;
				}
				default:
			}
			cur[i] = (byte) x;
		}
	}

	/** sample k of a row (its depth's bits), as an int of that depth */
	private static int sample(byte[] row, int k, int depth) {
		switch (depth) {
			case 8: return row[k] & 0xFF;
			case 16: return ((row[2 * k] & 0xFF) << 8) | (row[2 * k + 1] & 0xFF);
			default: {
				int bit = k * depth, v = row[bit >> 3] & 0xFF;
				return (v >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
			}
		}
	}

	/** a sample of that depth scaled to 0-255 (16-bit: the high byte, as ImageIO's conversion) */
	private static int to8(int v, int depth) {
		switch (depth) {
			case 1: return v * 255;
			case 2: return v * 85;
			case 4: return v * 17;
			case 8: return v;
			default: return v >> 8;
		}
	}

	private static int pixel(byte[] row, int x, int type, int depth, int[] palette, int tg, int tr, int tgg, int tb) {
		switch (type) {
			case 0: {
				int v = sample(row, x, depth), g = to8(v, depth);
				return (v == tg ? 0 : 0xFF000000) | (g << 16) | (g << 8) | g;
			}
			case 2: {
				int r = sample(row, 3 * x, depth), g = sample(row, 3 * x + 1, depth), bl = sample(row, 3 * x + 2, depth);
				boolean clear = r == tr && g == tgg && bl == tb;
				return (clear ? 0 : 0xFF000000) | (to8(r, depth) << 16) | (to8(g, depth) << 8) | to8(bl, depth);
			}
			case 3: {
				int i = sample(row, x, depth);
				return palette != null && i < palette.length ? palette[i] : 0xFF000000;
			}
			case 4: {
				int g = to8(sample(row, 2 * x, depth), depth), a = to8(sample(row, 2 * x + 1, depth), depth);
				return (a << 24) | (g << 16) | (g << 8) | g;
			}
			default: {
				int r = to8(sample(row, 4 * x, depth), depth), g = to8(sample(row, 4 * x + 1, depth), depth),
					bl = to8(sample(row, 4 * x + 2, depth), depth), a = to8(sample(row, 4 * x + 3, depth), depth);
				return (a << 24) | (r << 16) | (g << 8) | bl;
			}
		}
	}
}

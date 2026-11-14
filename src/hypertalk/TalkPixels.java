/**
 * Arrays of rows of pixels (imageToArray's px[y][x] = "r,g,b,a") kept as numbers while a script works on them. A
 * variable's array (LiveNode) whose rows all hold canonical pixels keeps them in one int[] (ARGB, as the image has
 * them) instead of a String per pixel: 4 bytes a pixel instead of ~50, and the browser player's compiled loops
 * (TalkWasmLoops) read and write the pixels directly. The text of every element is unchanged: an element reads as
 * the same "r,g,b,a" the interpreter would have stored, and a value that is no canonical pixel is kept as its text.
 *
 * Canonical = exactly four decimal components 0-255 without sign, spaces or leading zeros, joined by commas (what
 * imageToArray gives and what v & "," & … gives for whole numbers). Anything else ("255,0,0", "007,…", " 1,…",
 * a collection) is an override: that cell keeps its string.
 */
final class TalkPixels {
	private TalkPixels() {}

	/** The pixels of an array of rows (LiveNode.pixels): row y, element x is cells[y*w + x] unless text[y*w + x] is set */
	static final class Grid {
		final int w, h;
		final int[] cells;
		/** Cells whose text is no canonical pixel (null: none so far) */
		String[] text;
		int overrides;
		/** Some row stopped being a row of this grid (it grew, or holds a collection): only the rows' own text is right now */
		boolean broken;
		/** The byte address of the cells in TalkWasm.store while they are there (TalkWasmLoops), else -1; the cells the
		 *  interpreter changed since they went there (java…), and the ones compiled loops wrote there since (wasm…) */
		int base = -1, javaFirst = Integer.MAX_VALUE, javaLast = -1, wasmFirst = Integer.MAX_VALUE, wasmLast = -1;
		Grid(int w, int h, int[] cells) { this.w = w; this.h = h; this.cells = cells; }

		/** What compiled loops wrote comes back into cells (before anything here reads or changes them) */
		void pull() {
			if (wasmLast < wasmFirst) return;
			int first = wasmFirst, last = wasmLast;
			wasmFirst = Integer.MAX_VALUE; wasmLast = -1;
			TalkWasm.store.get(cells, first, last - first + 1, base + first * 4);
		}

		/** The cells, current */
		int[] cells() { pull(); return cells; }

		String cell(int i) {
			if (text != null) { String s = text[i]; if (s != null) return s; }
			if (wasmLast >= 0) pull();
			return pixelText(cells[i]);
		}

		void set(int i, String value) {
			pull();
			if (base >= 0) { if (i < javaFirst) javaFirst = i; if (i > javaLast) javaLast = i; }
			long p = canonical(value);
			if (p >= 0) {
				cells[i] = (int) p;
				if (text != null && text[i] != null) { text[i] = null; overrides--; }
			} else {
				if (text == null) text = new String[cells.length];
				if (text[i] == null) overrides++;
				text[i] = value;
			}
		}

		/** Row y's array text */
		String rowText(int y) { pull(); return TalkPixels.rowText(cells, y * w, w, text); }

		/** Whether a compiled loop may work on the cells: every cell is its number */
		boolean plain() { return !broken && overrides == 0; }
	}

	private static final String[] NAMES = new String[256];
	static { for (int i = 0; i < 256; i++) NAMES[i] = Integer.toString(i); }

	/** "r,g,b,a" of an ARGB pixel */
	static String pixelText(int p) {
		char[] c = new char[15];
		int n = digits(c, 0, (p >> 16) & 255);
		c[n++] = ',';
		n = digits(c, n, (p >> 8) & 255);
		c[n++] = ',';
		n = digits(c, n, p & 255);
		c[n++] = ',';
		n = digits(c, n, p >>> 24);
		return new String(c, 0, n);
	}

	private static int digits(char[] c, int at, int v) {
		if (v >= 100) { c[at++] = (char) ('0' + v / 100); v %= 100; c[at++] = (char) ('0' + v / 10); c[at++] = (char) ('0' + v % 10); }
		else if (v >= 10) { c[at++] = (char) ('0' + v / 10); c[at++] = (char) ('0' + v % 10); }
		else c[at++] = (char) ('0' + v);
		return at;
	}

	/** The ARGB pixel of canonical text, else -1 */
	static long canonical(String s) {
		if (s == null) return -1;
		int n = s.length();
		if (n < 7 || n > 15) return -1;
		int at = 0;
		long packed = 0;
		int[] c = new int[4];
		for (int k = 0; k < 4; k++) {
			if (at >= n) return -1;
			char ch = s.charAt(at);
			if (ch < '0' || ch > '9') return -1;
			int v = ch - '0', len = 1;
			at++;
			while (at < n && (ch = s.charAt(at)) >= '0' && ch <= '9') {
				if (v == 0 || ++len > 3) return -1; // a leading zero, or too many digits
				v = v * 10 + ch - '0';
				at++;
			}
			if (v > 255) return -1;
			c[k] = v;
			if (k < 3) { if (at >= n || s.charAt(at) != ',') return -1; at++; }
		}
		if (at != n) return -1;
		packed = (long) c[3] << 24 | c[0] << 16 | c[1] << 8 | c[2];
		return packed & 0xFFFFFFFFL;
	}

	// ---- decoded images by their array text (imageToArray's result, an array a loop wrote): decoded again without parsing ----
	private static final int CACHE = 4;
	private static final String[] cachedText = new String[CACHE];
	private static final int[][] cachedCells = new int[CACHE][];
	private static final int[] cachedW = new int[CACHE], cachedH = new int[CACHE];
	private static final long[] cachedUse = new long[CACHE];
	private static long uses;

	/** The array text of these pixels (cells is kept as it is: nobody may change it after); the least recently used goes */
	static synchronized void remember(String text, int w, int h, int[] cells) {
		if (text == null || cells == null || cells.length != w * h) return;
		int slot = 0;
		for (int k = 0; k < CACHE; k++) {
			if (cachedText[k] == text) { slot = k; break; }
			if (cachedUse[k] < cachedUse[slot]) slot = k;
		}
		cachedText[slot] = text; cachedCells[slot] = cells; cachedW[slot] = w; cachedH[slot] = h; cachedUse[slot] = ++uses;
		// at most ~100 MB of pixels kept: the least recently used others go first
		while (true) {
			long total = 0; int oldest = -1;
			for (int k = 0; k < CACHE; k++) {
				if (cachedCells[k] == null) continue;
				total += cachedCells[k].length;
				if (k != slot && (oldest < 0 || cachedUse[k] < cachedUse[oldest])) oldest = k;
			}
			if (total <= 25_000_000L || oldest < 0) break;
			cachedText[oldest] = null; cachedCells[oldest] = null; cachedUse[oldest] = 0;
		}
	}

	/** {w, h} and the cells (not to be changed) of array text remembered, or null */
	static synchronized Object[] lookup(String text) {
		if (text == null) return null;
		for (int k = 0; k < CACHE; k++) {
			String t = cachedText[k];
			if (t != null && (t == text || t.length() == text.length() && t.equals(text))) {
				cachedUse[k] = ++uses;
				return new Object[]{cachedW[k], cachedH[k], cachedCells[k]};
			}
		}
		return null;
	}

	/** imageToArray's text of ARGB pixels (rows from the top), remembered with them */
	static String arrayText(int w, int h, int[] argb) {
		java.util.ArrayList<String> rows = new java.util.ArrayList<>(h);
		for (int y = 0; y < h; y++) rows.add(rowText(argb, y * w, w, null));
		String text = TalkCollection.encodeArray(rows);
		remember(text, w, h, argb);
		return text;
	}

	// ---- a row's array text without a String per pixel: TalkCollection's short form ('A', a count, writeUTF of each) ----
	private static final String PREFIX = "\u001fHZC";
	private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

	/** TalkCollection.encodeArray of the texts of cells[from .. from+w-1] (text: overrides by cell index, or null) */
	static String rowText(int[] cells, int from, int w, String[] text) {
		if (text != null) {
			for (int x = 0; x < w; x++) {
				String t = text[from + x];
				if (t == null) continue;
				for (int i = 0; i < t.length(); i++) if (t.charAt(i) == 0 || t.charAt(i) > 127) return slowRow(cells, from, w, text);
				if (t.length() > 1000) return slowRow(cells, from, w, text);
			}
		}
		int size = 5;
		for (int x = 0; x < w; x++) size += 2 + (text != null && text[from + x] != null ? text[from + x].length() : 15);
		byte[] b = new byte[size];
		b[0] = 'A'; b[1] = (byte) (w >>> 24); b[2] = (byte) (w >>> 16); b[3] = (byte) (w >>> 8); b[4] = (byte) w;
		int n = 5;
		for (int x = 0; x < w; x++) {
			String t = text != null ? text[from + x] : null;
			int start = n + 2;
			if (t != null) { for (int i = 0; i < t.length(); i++) b[start + i] = (byte) t.charAt(i); n = start + t.length(); }
			else {
				int p = cells[from + x];
				n = digits(b, start, (p >> 16) & 255); b[n++] = ',';
				n = digits(b, n, (p >> 8) & 255); b[n++] = ',';
				n = digits(b, n, p & 255); b[n++] = ',';
				n = digits(b, n, p >>> 24);
			}
			int len = n - start;
			b[start - 2] = (byte) (len >>> 8); b[start - 1] = (byte) len;
		}
		return PREFIX + base64(b, n);
	}

	private static String slowRow(int[] cells, int from, int w, String[] text) {
		java.util.ArrayList<String> row = new java.util.ArrayList<>(w);
		for (int x = 0; x < w; x++) row.add(text != null && text[from + x] != null ? text[from + x] : pixelText(cells[from + x]));
		return TalkCollection.encodeArray(row);
	}

	private static int digits(byte[] c, int at, int v) {
		if (v >= 100) { c[at++] = (byte) ('0' + v / 100); v %= 100; c[at++] = (byte) ('0' + v / 10); c[at++] = (byte) ('0' + v % 10); }
		else if (v >= 10) { c[at++] = (byte) ('0' + v / 10); c[at++] = (byte) ('0' + v % 10); }
		else c[at++] = (byte) ('0' + v);
		return at;
	}

	private static String base64(byte[] b, int n) {
		char[] c = new char[(n + 2) / 3 * 4];
		int o = 0, i = 0;
		for (; i + 2 < n; i += 3) {
			int v = (b[i] & 255) << 16 | (b[i + 1] & 255) << 8 | b[i + 2] & 255;
			c[o++] = B64[v >>> 18]; c[o++] = B64[(v >>> 12) & 63]; c[o++] = B64[(v >>> 6) & 63]; c[o++] = B64[v & 63];
		}
		if (i < n) {
			int v = (b[i] & 255) << 16 | (i + 1 < n ? (b[i + 1] & 255) << 8 : 0);
			c[o++] = B64[v >>> 18]; c[o++] = B64[(v >>> 12) & 63];
			c[o++] = i + 1 < n ? B64[(v >>> 6) & 63] : '=';
			c[o++] = '=';
		}
		return new String(c);
	}

	/**
	 * The pixels of a row's array text (TalkCollection's encoding) into out[at…], when it is an array of w canonical
	 * pixels in the short form; else -1 (the caller decodes it the general way, which also reports damaged text).
	 */
	static int parseRow(String text, int[] out, int at, int w) {
		int n = text.length();
		if (!text.startsWith(PREFIX) || (n - 4) % 4 != 0 || n - 4 < 8) return -1;
		byte[] b;
		try { b = java.util.Base64.getDecoder().decode(text.substring(4)); } // the class library's decoder: the quickest in the browser
		catch (IllegalArgumentException e) { return -1; }
		int bytes = b.length;
		if (bytes < 5 || b[0] != 'A') return -1;
		int count = (b[1] & 255) << 24 | (b[2] & 255) << 16 | (b[3] & 255) << 8 | b[4] & 255;
		if (count != w) return -1;
		int p = 5;
		for (int x = 0; x < w; x++) {
			if (p + 2 > bytes) return -1;
			int len = (b[p] & 255) << 8 | b[p + 1] & 255;
			p += 2;
			if (len < 7 || len > 15 || p + len > bytes) return -1;
			int end = p + len, packed = 0;
			for (int k = 0; k < 4; k++) {
				if (p >= end) return -1;
				int ch = b[p] - '0';
				if (ch < 0 || ch > 9) return -1;
				int v = ch, digits = 1;
				p++;
				while (p < end && b[p] >= '0' && b[p] <= '9') {
					if (v == 0 || ++digits > 3) return -1;
					v = v * 10 + b[p] - '0';
					p++;
				}
				if (v > 255) return -1;
				packed = k == 0 ? v << 16 : k == 1 ? packed | v << 8 : k == 2 ? packed | v : packed | v << 24;
				if (k < 3) { if (p >= end || b[p] != ',') return -1; p++; }
			}
			if (p != end) return -1;
			out[at + x] = packed;
		}
		return p == bytes ? w : -1;
	}
}

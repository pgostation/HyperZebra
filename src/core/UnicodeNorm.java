import java.util.HashMap;

/**
 * Unicode normalization (UAX #15: NFC, NFD, NFKC, NFKD) without java.text.Normalizer, for the headless core and the browser.
 * The tables (UnicodeNormData) are written from the JDK's own Normalizer by tools/wasm-core/NormTables.java, so the
 * results are the desktop's; tools/wasm-core/NormCheck.java compares the two. Hangul syllables by arithmetic.
 */
final class UnicodeNorm {
	private UnicodeNorm() {}

	enum Form { NFC, NFD, NFKC, NFKD }

	private static int[] cccCodes, cccValues;
	private static HashMap<Integer, int[]> canon, compat;
	private static HashMap<Long, Integer> pairs;

	private static synchronized void load() {
		if (pairs != null) return;
		String[] ccc = records(UnicodeNormData.CCC);
		cccCodes = new int[ccc.length]; cccValues = new int[ccc.length];
		for (int i = 0; i < ccc.length; i++) {
			int colon = ccc[i].indexOf(':');
			cccCodes[i] = Integer.parseInt(ccc[i].substring(0, colon), 36);
			cccValues[i] = Integer.parseInt(ccc[i].substring(colon + 1), 36);
		}
		canon = decompositions(UnicodeNormData.CANON);
		compat = decompositions(UnicodeNormData.COMPAT);
		HashMap<Long, Integer> p = new HashMap<>();
		for (String r : records(UnicodeNormData.PAIRS)) {
			int comma = r.indexOf(','), colon = r.indexOf(':');
			p.put(key(Integer.parseInt(r.substring(0, comma), 36), Integer.parseInt(r.substring(comma + 1, colon), 36)), Integer.parseInt(r.substring(colon + 1), 36));
		}
		pairs = p;
	}
	private static String[] records(String[] pieces) {
		StringBuilder b = new StringBuilder();
		for (String s : pieces) b.append(s);
		return b.length() == 0 ? new String[0] : b.toString().split(";");
	}
	private static HashMap<Integer, int[]> decompositions(String[] pieces) {
		HashMap<Integer, int[]> m = new HashMap<>();
		for (String r : records(pieces)) {
			int colon = r.indexOf(':');
			String[] parts = r.substring(colon + 1).split(",");
			int[] d = new int[parts.length];
			for (int i = 0; i < d.length; i++) d[i] = Integer.parseInt(parts[i], 36);
			m.put(Integer.parseInt(r.substring(0, colon), 36), d);
		}
		return m;
	}
	private static long key(int a, int b) { return ((long) a << 21) | b; }

	static int combiningClass(int cp) {
		if (cp < 0x300) return 0;
		load();
		int lo = 0, hi = cccCodes.length - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (cccCodes[mid] < cp) lo = mid + 1; else if (cccCodes[mid] > cp) hi = mid - 1; else return cccValues[mid];
		}
		return 0;
	}

	private static final int S_BASE = 0xAC00, L_BASE = 0x1100, V_BASE = 0x1161, T_BASE = 0x11A7;
	private static final int L_COUNT = 19, V_COUNT = 21, T_COUNT = 28, N_COUNT = V_COUNT * T_COUNT, S_COUNT = L_COUNT * N_COUNT;

	static String normalize(CharSequence in, Form form) {
		String s = in.toString();
		// below U+00A0 (NO-BREAK SPACE, which NFKC makes a space) every form leaves a text as it is
		int n = s.length(), i = 0;
		while (i < n && s.charAt(i) < 0xA0) i++;
		if (i == n) return s;
		load();
		boolean k = form == Form.NFKC || form == Form.NFKD, compose = form == Form.NFC || form == Form.NFKC;
		int[] buf = new int[n * 2 + 8];
		int len = 0;
		for (int p = 0; p < n; ) {
			int cp = s.codePointAt(p);
			p += Character.charCount(cp);
			int[] d;
			if (cp >= S_BASE && cp < S_BASE + S_COUNT) {
				int si = cp - S_BASE, t = T_BASE + si % T_COUNT;
				d = t == T_BASE ? new int[]{L_BASE + si / N_COUNT, V_BASE + (si % N_COUNT) / T_COUNT}
					: new int[]{L_BASE + si / N_COUNT, V_BASE + (si % N_COUNT) / T_COUNT, t};
			}
			else {
				d = k ? compat.get(cp) : null;
				if (d == null) d = canon.get(cp);
			}
			if (len + (d == null ? 1 : d.length) > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2 + (d == null ? 1 : d.length));
			if (d == null) buf[len++] = cp;
			else for (int x : d) buf[len++] = x;
		}
		// canonical ordering: the marks after a starter sorted by class, stably
		for (int a = 1; a < len; a++) {
			int c = combiningClass(buf[a]);
			if (c == 0) continue;
			int b = a;
			while (b > 0 && combiningClass(buf[b - 1]) > c) { int t = buf[b]; buf[b] = buf[b - 1]; buf[b - 1] = t; b--; }
		}
		if (compose) len = compose(buf, len);
		StringBuilder out = new StringBuilder(len + 4);
		for (int a = 0; a < len; a++) out.appendCodePoint(buf[a]);
		return out.toString();
	}

	/** Canonical composition in place; the new length. */
	private static int compose(int[] b, int len) {
		int out = 0, starter = -1, lastClass = -1;
		for (int a = 0; a < len; a++) {
			int c = b[a], cc = combiningClass(c);
			if (starter >= 0 && (out - 1 == starter || (lastClass != 0 && lastClass < cc))) {
				int composite = composite(b[starter], c);
				if (composite >= 0) { b[starter] = composite; continue; }
			}
			if (cc == 0) starter = out;
			b[out++] = c;
			lastClass = cc;
		}
		return out;
	}

	private static int composite(int first, int second) {
		if (first >= L_BASE && first < L_BASE + L_COUNT && second >= V_BASE && second < V_BASE + V_COUNT)
			return S_BASE + ((first - L_BASE) * V_COUNT + (second - V_BASE)) * T_COUNT;
		if (first >= S_BASE && first < S_BASE + S_COUNT && (first - S_BASE) % T_COUNT == 0 && second > T_BASE && second < T_BASE + T_COUNT)
			return first + (second - T_BASE);
		Integer c = pairs.get(key(first, second));
		return c == null ? -1 : c;
	}
}

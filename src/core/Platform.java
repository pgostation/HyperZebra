/**
 * The few host services the HyperTalk core needs from the runtime it runs in, so the core also compiles for the browser
 * (TeaVM, tools/wasm-core): time, sleeping and waiting, background threads. The desktop's {@code JdkPlatform} (src/app)
 * does what the code did before (parkNanos, Thread.sleep, Object.wait, Thread.start). The browser and headless runs use
 * {@link PortablePlatform}: one thread, a virtual clock that a sleep moves forward at once.
 *
 * The desktop platform is found by name, not referenced, so a build of the core alone never pulls in desktop classes.
 */
abstract class Platform {
	private static volatile Platform current;

	/** The platform in use: the desktop's when its class is there, else the portable one. */
	static Platform get() {
		Platform p = current;
		if (p == null) current = p = byName(System.getProperty("hz.platform", "JdkPlatform"));
		return p;
	}
	/**
	 * {@code in.readNBytes(b, off, len)}: reads until len bytes or the end. TeaVM's InputStream.readNBytes(byte[], int, int)
	 * never returns once it has them all (it keeps reading 0 bytes until read gives -1), which hung the browser player.
	 */
	static int readNBytes(java.io.InputStream in, byte[] b, int off, int len) throws java.io.IOException {
		if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
		int n = 0;
		while (n < len) {
			int r = in.read(b, off + n, len - n);
			if (r < 0) break;
			n += r;
		}
		return n;
	}
	/** Replaces the platform (a headless entry, a test). */
	static void set(Platform p) { current = p; }

	private static Platform byName(String name) {
		try { return (Platform) Class.forName(name).getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return new PortablePlatform(); }
	}

	/** System.nanoTime on the desktop; a headless platform adds the time its sleeps skipped. */
	abstract long nanoTime();
	/** Waits up to nanos (one slice of the script thread's wait; it returns early on an interrupt). */
	abstract void parkNanos(long nanos);
	/** Thread.sleep. */
	abstract void sleep(long millis) throws InterruptedException;
	/** lock.wait(millis), called inside synchronized(lock). */
	abstract void waitOn(Object lock, long millis) throws InterruptedException;
	/** Starts a background thread; a single-threaded platform runs it here and now. */
	abstract void start(Thread t);
	/** start(t) unless t was started already (by anyone, at once from two threads: no IllegalThreadStateException). */
	abstract void startIfNew(Thread t);

	/** Unicode normalization (java.text.Normalizer on the desktop, UnicodeNorm here). */
	abstract String normalize(CharSequence text, UnicodeNorm.Form form);

	/** "sort international": the platform's collation of its language, accents counting but not case (Collator SECONDARY). */
	abstract java.util.Comparator<String> internationalOrder();
	/** A message digest by its JDK name ("MD5", "SHA-256" …), or null when there is no such one. */
	abstract byte[] digest(String algorithm, byte[] data);

	/** Charset.forName (an UnsupportedCharsetException as it throws): the core's own tables first here (TableCharset). */
	abstract java.nio.charset.Charset charset(String name);
	/** charset(name), or null when the platform has none of that name (the headless core has no GBK, Big5, EUC-KR). */
	java.nio.charset.Charset optionalCharset(String name) {
		try { return charset(name); } catch (java.nio.charset.UnsupportedCharsetException | java.nio.charset.IllegalCharsetNameException e) { return null; }
	}
	/** Charset.isSupported. */
	abstract boolean charsetSupported(String name);
	/** The encoder replacing with '?' (replaceWith only when its own replacement is another: TeaVM 0.14 refuses one byte where
	 * a character can take more, its check the wrong way round, and '?' is what the JDK's and TableCharset's have already). */
	static java.nio.charset.CharsetEncoder questionMarks(java.nio.charset.CharsetEncoder e) {
		byte[] r = e.replacement();
		return r.length == 1 && r[0] == '?' ? e : e.replaceWith(new byte[] {(byte) '?'});
	}

	/** A parser of XML that came from outside (no DOCTYPE, no entities): StAX on the desktop, {@link HzPullParser} here. */
	abstract XmlIn xmlIn(java.io.InputStream in) throws XmlException;
	abstract XmlIn xmlIn(java.io.Reader in) throws XmlException;
	/** A writer of XML: StAX on the desktop, {@link PlainXmlOut} (the same text) here. */
	abstract XmlOut xmlOut(java.io.Writer out) throws XmlException;
	abstract XmlOut xmlOut(java.io.OutputStream out) throws XmlException;

	// ---- S10: JDK members TeaVM lacks; these bodies are the portable ones, JdkPlatform keeps the JDK's ----
	byte[] readAllBytes(java.io.File f) throws java.io.IOException { try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { return in.readAllBytes(); } }
	boolean isWritable(java.io.File f) { return f.canWrite(); }
	/** The absolute path with "." and ".." taken out (Path.toAbsolutePath().normalize()). */
	String normalPath(java.io.File f) {
		String abs = f.getAbsolutePath(), sep = java.io.File.separator;
		java.util.ArrayList<String> parts = new java.util.ArrayList<>();
		for (String p : abs.split(java.util.regex.Pattern.quote(sep))) {
			if (p.isEmpty() || p.equals(".")) continue;
			if (p.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); continue; }
			parts.add(p);
		}
		return sep + String.join(sep, parts);
	}
	/** The script engine's thread object (the JVM gives it a big stack). */
	TTalk newScriptThread() { return new TTalk("scriptEngine"); }
	long maxMemory() { return Runtime.getRuntime().totalMemory(); }
	long freeSpace(java.io.File f) { return 1L << 30; }
	/** Base64 that skips anything not of its alphabet (the MIME decoder). */
	byte[] decodeMimeBase64(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/' || c == '=') b.append(c); }
		return java.util.Base64.getDecoder().decode(b.toString());
	}
	/** A BCP 47 tag's parts and its canonical form (Locale.forLanguageTag): language (lower case), script (title case), region. */
	String languageTag(String tag) {
		String l = tagLanguage(tag);
		if (l.isEmpty()) return null;
		String s = tagScript(tag), c = tagCountry(tag);
		return l + (s.isEmpty() ? "" : "-" + s) + (c.isEmpty() ? "" : "-" + c);
	}
	String tagLanguage(String tag) {
		String[] p = tag == null ? new String[0] : tag.split("[-_]");
		return p.length > 0 && p[0].matches("[A-Za-z]{2,8}") ? p[0].toLowerCase(java.util.Locale.ROOT) : "";
	}
	String tagScript(String tag) {
		if (tagLanguage(tag).isEmpty()) return "";
		String[] p = tag.split("[-_]");
		return p.length > 1 && p[1].matches("[A-Za-z]{4}") ? p[1].substring(0, 1).toUpperCase(java.util.Locale.ROOT) + p[1].substring(1).toLowerCase(java.util.Locale.ROOT) : "";
	}
	String tagCountry(String tag) {
		if (tagLanguage(tag).isEmpty()) return "";
		String[] p = tag.split("[-_]");
		int i = p.length > 1 && p[1].matches("[A-Za-z]{4}") ? 2 : 1;
		return p.length > i && (p[i].matches("[A-Za-z]{2}") || p[i].matches("[0-9]{3}")) ? p[i].toUpperCase(java.util.Locale.ROOT) : "";
	}

	// ---- S10: regexes TeaVM's engine refuses: its classes written out as ranges (JdkPlatform: the regex as it is) ----
	private static final java.util.Map<String, String> REGEX_CLASSES = java.util.Map.of(
		"N", "0-9\\u00B2\\u00B3\\u00B9\\u00BC-\\u00BE\\u0660-\\u0669\\u06F0-\\u06F9\\u0966-\\u096F\\u2070\\u2074-\\u2079\\u2080-\\u2089\\u2150-\\u2189\\u2460-\\u249B\\u24EA-\\u24FF\\u2776-\\u2793\\u3007\\u3021-\\u3029\\u3038-\\u303A\\u3192-\\u3195\\u3220-\\u3229\\u3248-\\u324F\\u3251-\\u325F\\u3280-\\u3289\\u32B1-\\u32BF\\uFF10-\\uFF19",
		"IsLatin", "A-Za-z\\u00AA\\u00BA\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02B8\\u02E0-\\u02E4\\u1D00-\\u1D25\\u1D2C-\\u1D5C\\u1D62-\\u1D65\\u1D6B-\\u1D77\\u1D79-\\u1DBE\\u1E00-\\u1EFF\\u2071\\u207F\\u2090-\\u209C\\u212A\\u212B\\u2132\\u214E\\u2160-\\u2188\\u2C60-\\u2C7F\\uA722-\\uA787\\uA78B-\\uA7FF\\uFB00-\\uFB06\\uFF21-\\uFF3A\\uFF41-\\uFF5A",
		"IsHan", "\\u2E80-\\u2E99\\u2E9B-\\u2EF3\\u2F00-\\u2FD5\\u3005\\u3007\\u3021-\\u3029\\u3038-\\u303B\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFA6D\\uFA70-\\uFAD9",
		"IsHiragana", "\\u3041-\\u3096\\u309D-\\u309F",
		"IsKatakana", "\\u30A1-\\u30FA\\u30FD-\\u30FF\\u31F0-\\u31FF\\u32D0-\\u32FE\\u3300-\\u3357\\uFF66-\\uFF6F\\uFF71-\\uFF9D",
		"IsAlphabetic", "\\p{L}\\u2160-\\u2188\\u3007\\u3021-\\u3029");
	String regex(String re) {
		if (re == null || (re.indexOf("\\p{") < 0 && re.indexOf("\\R") < 0)) return re;
		StringBuilder out = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < re.length(); i++) {
			char c = re.charAt(i);
			if (c == '\\' && i + 1 < re.length()) {
				char n = re.charAt(i + 1);
				if (n == 'p' && i + 2 < re.length() && re.charAt(i + 2) == '{') {
					int end = re.indexOf('}', i);
					String ranges = end < 0 ? null : REGEX_CLASSES.get(re.substring(i + 3, end));
					if (ranges != null) { out.append(depth > 0 ? ranges : "[" + ranges + "]"); i = end; continue; }
				}
				if (n == 'R' && depth == 0) { out.append("(?:\\r\\n|[\\n\\u000B\\u000C\\r\\u0085\\u2028\\u2029])"); i++; continue; }
				out.append(c).append(n); i++; continue;
			}
			if (c == '[') depth++;
			else if (c == ']' && depth > 0) depth--;
			out.append(c);
		}
		return out.toString();
	}

	// ---- S10: number to text (TeaVM's Double.toString and DecimalFormat differ from the JDK's; HzNumbers gives the JDK's) ----
	String doubleToString(double v) { return HzNumbers.doubleToString(v); }
	String formatNumber(java.text.DecimalFormat f, double v) { return HzNumbers.format(f, v); }
}

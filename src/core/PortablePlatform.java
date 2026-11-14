/**
 * The platform of the headless core and the browser build: one thread and a virtual clock. A sleep or a wait returns at
 * once and moves the clock on by its length, so `wait 2 seconds` and `wait until the ticks > x` finish without waiting
 * and a run gives the same results every time. Background threads run on the calling thread.
 */
class PortablePlatform extends Platform {
	private long skipped;

	@Override long nanoTime() { return System.nanoTime() + skipped; }
	@Override void parkNanos(long nanos) { if (nanos > 0) skipped += nanos; }
	@Override void sleep(long millis) { if (millis > 0) skipped += millis * 1_000_000L; }
	@Override void waitOn(Object lock, long millis) { if (millis > 0) skipped += millis * 1_000_000L; }
	@Override void start(Thread t) { if (started.add(t)) t.run(); }
	@Override void startIfNew(Thread t) { start(t); }
	@Override String normalize(CharSequence text, UnicodeNorm.Form form) { return UnicodeNorm.normalize(text, form); }
	/** Without the JDK's collation tables: letters with their marks taken off and without case first, then the marks, then the
	 *  text itself (close to Collator's SECONDARY order for Latin text; tools/wasm-core/known_core_differences.txt). */
	@Override java.util.Comparator<String> internationalOrder() {
		return (a, b) -> {
			int c = base(a).compareTo(base(b));
			if (c != 0) return c;
			c = UnicodeNorm.normalize(a, UnicodeNorm.Form.NFD).toLowerCase(java.util.Locale.ROOT).compareTo(UnicodeNorm.normalize(b, UnicodeNorm.Form.NFD).toLowerCase(java.util.Locale.ROOT));
			return c;
		};
	}
	private static String base(String s) {
		String d = UnicodeNorm.normalize(s, UnicodeNorm.Form.NFKD);
		StringBuilder b = new StringBuilder(d.length());
		for (int i = 0; i < d.length(); i++) { char ch = d.charAt(i); if (UnicodeNorm.combiningClass(ch) == 0 && Character.getType(ch) != Character.NON_SPACING_MARK) b.append(Character.toLowerCase(ch)); }
		return b.toString();
	}
	@Override byte[] digest(String algorithm, byte[] data) { return Digests.digest(algorithm, data); }
	/** The core's tables (the Mac OS charsets, Shift_JIS) first, so a run on the JVM converts as the browser does; then the
	 *  runtime's own (UTF-8, ISO-8859-1 …). */
	@Override java.nio.charset.Charset charset(String name) {
		java.nio.charset.Charset c = TableCharset.named(name);
		return c != null ? c : java.nio.charset.Charset.forName(name);
	}
	@Override boolean charsetSupported(String name) {
		if (TableCharset.named(name) != null) return true;
		try { return PortablePlatform.charsetKnown(name); } catch (RuntimeException e) { return false; }
	}
	@Override XmlIn xmlIn(java.io.InputStream in) throws XmlException { return HzPullParser.of(in); }
	@Override XmlIn xmlIn(java.io.Reader in) throws XmlException { return HzPullParser.of(in); }
	@Override XmlOut xmlOut(java.io.Writer out) { return new PlainXmlOut(out); }
	@Override XmlOut xmlOut(java.io.OutputStream out) { return new PlainXmlOut(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8)); }
	private final java.util.Set<Thread> started = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Thread, Boolean>());

	/** Charset.isSupported (TeaVM lacks it): whether forName finds the charset. */
	static boolean charsetKnown(String name) {
		try { java.nio.charset.Charset.forName(name); return true; } catch (RuntimeException e) { return false; }
	}
}

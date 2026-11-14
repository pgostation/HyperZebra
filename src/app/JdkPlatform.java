/** The desktop's {@link Platform}: real threads and the system clock, exactly what the core called directly before. */
final class JdkPlatform extends Platform {
	@Override long nanoTime() { return System.nanoTime(); }
	@Override void parkNanos(long nanos) { java.util.concurrent.locks.LockSupport.parkNanos(nanos); }
	@Override void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
	@Override void waitOn(Object lock, long millis) throws InterruptedException { lock.wait(millis); }
	@Override void start(Thread t) { t.start(); }
	@Override void startIfNew(Thread t) { synchronized (t) { if (t.getState() == Thread.State.NEW) t.start(); } }
	@Override String normalize(CharSequence text, UnicodeNorm.Form form) {
		java.text.Normalizer.Form f = form == UnicodeNorm.Form.NFC ? java.text.Normalizer.Form.NFC : form == UnicodeNorm.Form.NFD ? java.text.Normalizer.Form.NFD
			: form == UnicodeNorm.Form.NFKC ? java.text.Normalizer.Form.NFKC : java.text.Normalizer.Form.NFKD;
		return java.text.Normalizer.normalize(text, f);
	}
	@Override java.util.Comparator<String> internationalOrder() {
		java.text.Collator collator = java.text.Collator.getInstance();
		collator.setStrength(java.text.Collator.SECONDARY);
		return collator::compare;
	}
	@Override byte[] digest(String algorithm, byte[] data) {
		try { return java.security.MessageDigest.getInstance(algorithm).digest(data); }
		catch (java.security.NoSuchAlgorithmException e) { return null; }
	}
	@Override java.nio.charset.Charset charset(String name) { return java.nio.charset.Charset.forName(name); }
	@Override boolean charsetSupported(String name) { return java.nio.charset.Charset.isSupported(name); }
	@Override XmlIn xmlIn(java.io.InputStream in) throws XmlException { return StaxXml.in(in); }
	@Override XmlIn xmlIn(java.io.Reader in) throws XmlException { return StaxXml.in(in); }
	@Override XmlOut xmlOut(java.io.Writer out) throws XmlException { return StaxXml.out(out); }
	@Override XmlOut xmlOut(java.io.OutputStream out) throws XmlException { return StaxXml.out(out); }

	// ---- S10: the JDK's own (Platform's bodies are the portable ones) ----
	@Override byte[] readAllBytes(java.io.File f) throws java.io.IOException { return java.nio.file.Files.readAllBytes(f.toPath()); }
	@Override boolean isWritable(java.io.File f) { return java.nio.file.Files.isWritable(f.toPath()); }
	@Override String normalPath(java.io.File f) { return f.toPath().toAbsolutePath().normalize().toString(); }
	@Override TTalk newScriptThread() { return new TTalk(); }
	@Override long maxMemory() { return Runtime.getRuntime().maxMemory(); }
	@Override long freeSpace(java.io.File f) { return f.getFreeSpace(); }
	@Override byte[] decodeMimeBase64(String s) { return java.util.Base64.getMimeDecoder().decode(s); }
	@Override String languageTag(String tag) { java.util.Locale l = java.util.Locale.forLanguageTag(tag); return l.getLanguage().isEmpty() ? null : l.toLanguageTag(); }
	@Override String tagLanguage(String tag) { return java.util.Locale.forLanguageTag(tag).getLanguage(); }
	@Override String tagScript(String tag) { return java.util.Locale.forLanguageTag(tag).getScript(); }
	@Override String tagCountry(String tag) { return java.util.Locale.forLanguageTag(tag).getCountry(); }
	@Override String regex(String re) { return re; }
	@Override String doubleToString(double v) { return Double.toString(v); }
	@Override String formatNumber(java.text.DecimalFormat f, double v) { return f.format(v); }
}

import java.io.File;
import java.io.IOException;

/**
 * The host file operations the 68K VM used java.nio.file for (P4: TeaVM's java.io.File has no toPath). The desktop's
 * Files68kNio (found by name) makes the same java.nio.file calls as before; without it (the browser player, whose files
 * are in memory) plain java.io does what there is to do: no links, no creation times, no file keys.
 */
class Files68k {
	private static final Files68k IMPL = find();
	private static Files68k find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new Files68k();
		try { return (Files68k) Class.forName("Files68kNio").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new Files68k(); }
	}

	byte[] doRead(File f) throws IOException { try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { return in.readAllBytes(); } }
	void doWrite(File f, byte[] b) throws IOException { try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(b); } }
	void doAppend(File f, byte[] b) throws IOException { try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) { out.write(b); } }
	boolean doIsSymbolicLink(File f) { return false; }
	void doDelete(File f) throws IOException { if (!f.delete()) throw new IOException(f.exists() ? "cannot delete " + f : "no such file " + f); }
	void doMove(File from, File to, boolean replace) throws IOException {
		if (to.exists()) { if (!replace) throw new IOException("exists: " + to); doDelete(to); }
		if (!from.renameTo(to)) { doWrite(to, doRead(from)); doDelete(from); }
	}
	void doCopyWithAttributes(File from, File to) throws IOException { doWrite(to, doRead(from)); to.setLastModified(from.lastModified()); }
	java.io.InputStream doIn(File f) throws IOException { return new java.io.FileInputStream(f); }
	java.util.List<String> doLines(File f, java.nio.charset.Charset cs) throws IOException {
		java.util.List<String> out = new java.util.ArrayList<>();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), cs))) { for (String l; (l = r.readLine()) != null; ) out.add(l); }
		return out;
	}
	long[] doTimes(File f) throws IOException { if (!f.exists()) throw new IOException("no such file " + f); long m = f.lastModified(); return new long[] {m, m}; }
	Object doFileKey(File f) throws IOException { return null; }
	void doSetModified(File f, long millis) throws IOException { f.setLastModified(millis); }
	void doSetCreated(File f, long millis) throws IOException {}
	boolean doSetWritable(File f, boolean writable, boolean ownerOnly) { return f.setWritable(writable); }
	long doTotalSpace(File f) { return 1L << 31; }
	long doUsableSpace(File f) { return 1L << 30; }

	/** Files.readAllBytes */
	static byte[] read(File f) throws IOException { return IMPL.doRead(f); }
	/** Files.write (created or replaced) */
	static void write(File f, byte[] b) throws IOException { IMPL.doWrite(f, b); }
	/** Files.write with CREATE, APPEND */
	static void append(File f, byte[] b) throws IOException { IMPL.doAppend(f, b); }
	static boolean isSymbolicLink(File f) { return IMPL.doIsSymbolicLink(f); }
	/** Files.delete (an IOException when it is not there) */
	static void delete(File f) throws IOException { IMPL.doDelete(f); }
	/** Files.move, REPLACE_EXISTING when replace */
	static void move(File from, File to, boolean replace) throws IOException { IMPL.doMove(from, to, replace); }
	/** Files.copy with COPY_ATTRIBUTES */
	static void copyWithAttributes(File from, File to) throws IOException { IMPL.doCopyWithAttributes(from, to); }
	static java.io.InputStream in(File f) throws IOException { return IMPL.doIn(f); }
	static java.util.List<String> lines(File f, java.nio.charset.Charset cs) throws IOException { return IMPL.doLines(f, cs); }
	/** {creation, last modified} in milliseconds (BasicFileAttributes) */
	static long[] times(File f) throws IOException { return IMPL.doTimes(f); }
	/** BasicFileAttributes.fileKey (an inode), null when the host has none */
	static Object fileKey(File f) throws IOException { return IMPL.doFileKey(f); }
	static void setModified(File f, long millis) throws IOException { IMPL.doSetModified(f, millis); }
	/** basic:creationTime */
	static void setCreated(File f, long millis) throws IOException { IMPL.doSetCreated(f, millis); }
	/** File.setWritable(writable, ownerOnly) */
	static boolean setWritable(File f, boolean writable, boolean ownerOnly) { return IMPL.doSetWritable(f, writable, ownerOnly); }
	static long totalSpace(File f) { return IMPL.doTotalSpace(f); }
	static long usableSpace(File f) { return IMPL.doUsableSpace(f); }
}

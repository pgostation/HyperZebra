import java.io.File;

/**
 * The host's extended file attributes through macOS's xattr tool (P4): the desktop's XattrTool68k, found by name; a VM
 * without it (the browser player's, whose files are in memory) has none and is not on macOS.
 */
class HostXattr68k {
	private static final HostXattr68k IMPL = find();
	private static HostXattr68k find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new HostXattr68k();
		try { return (HostXattr68k) Class.forName("XattrTool68k").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new HostXattr68k(); }
	}
	boolean isMac() { return false; }
	/** the attribute's bytes (the tool's exit code 0), null when it failed */
	byte[] get(File file, String name) { return null; }
	boolean set(File file, String name, byte[] value) { return false; }
	boolean delete(File file, String name) { return false; }

	/** A file of the user's read-only by its permissions alone (no owner write bit). */
	boolean ownerReadOnly(File file) { return false; }
	/** macOS's Locked (chflags uchg / nouchg); false when it did not take. */
	boolean finderLock(File file, boolean locked) { return false; }
	/** The host file's birth time; false where the host keeps none. */
	boolean birthTime(File file, long millis) { return false; }

	/** The host's file attributes in use. */
	static HostXattr68k host() { return IMPL; }
	/** Whether the host is macOS (GMenu.isMacOSX) */
	static boolean macOS() { return IMPL.isMac(); }
	static byte[] read(File file, String name) { return IMPL.get(file, name); }
	static boolean write(File file, String name, byte[] value) { return IMPL.set(file, name, value); }
	static boolean remove(File file, String name) { return IMPL.delete(file, name); }
}

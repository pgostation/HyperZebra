import java.io.File;
import java.util.List;

/**
 * What the 68K VM asks of the sandbox (P4): the desktop's Sandbox (src/app) through Sandbox68kDesktop, found by name; a VM
 * without it (the browser player's, whose files are the stack's own in memory) is allowed everything. The same members as
 * Sandbox, so vm68k's calls read as before.
 */
class Sandbox68k {
	enum Access { READ, WRITE, READ_WRITE, DELETE, LIST, EXECUTE, CREATE }
	enum Level { READ, WRITE, DELETE }
	static final String NET_CONNECT="wants to open a TCP connection to:",NET_LISTEN="wants to accept TCP connections on:",NET_UDP="wants to send and receive UDP packets on:",NET_LOOKUP="wants to look up the host name:",NET_APPLETALK="wants to join the AppleTalk network (LocalTalk over UDP) at:";
	static final String USE_MICROPHONE="wants to record from the microphone.",OPEN_URL="wants to open in the web browser:";

	private static final Sandbox68k IMPL = find();
	private static Sandbox68k find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new Sandbox68k();
		try { return (Sandbox68k) Class.forName("Sandbox68kDesktop").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new Sandbox68k(); }
	}

	// what a VM without the desktop's sandbox answers (overridden by Sandbox68kDesktop)
	boolean doAllowFile(File path, Access access, String requester) { return true; }
	void doCheckFile(File path, Access access, String requester) {}
	boolean doAllowNetwork(String requester, String verb, String host, int port) { return true; }
	String doCanonical(File f) { try { return f.getCanonicalPath(); } catch (java.io.IOException e) { return f.getAbsolutePath(); } }
	void doChosenByUser(String requester, File chosen, boolean saving) {}
	String doScriptRequester() { return "HyperTalk"; }
	void doCreated(String requester, File path) {}
	boolean doAllowedWithoutAsking(File path) { return path != null; }
	boolean doAllowedWithoutAsking(File path, Level level, String requester) { return path != null; }
	boolean doIsCreatedBy(String requester, File path) { return false; }
	void doHostResolved(String name, String[] addresses) {}
	boolean doAllowsEverything() { return true; }
	boolean doAllowOther(String requester, String verb, String detail) { return true; }
	boolean doReadOnlyFor(File path, String requester) { return false; }
	boolean doAllowProgramChange(File target, String requester) { return true; }
	void doTrustStackFolder(File folder) {}
	void doRunsUnattended(String requester, boolean on) {}
	void doProgramLaunched(String requester, File program, List<File> documents) {}
	boolean doIsProgramOf(String requester, File file) { return false; }
	boolean doIsBuiltIn(File path) { return false; }
	void doCheckProgramChange(File target, String requester) {}
	List<File> doAllowedRoots(String requester) { return java.util.Collections.emptyList(); }
	boolean doAllowNewExtensions(String requester, String names) { return true; }
	boolean doAllowInfectedExtension(String extension, String virus) { return false; }

	static boolean allowFile(File path, Access access, String requester) { return IMPL.doAllowFile(path, access, requester); }
	static void checkFile(File path, Access access, String requester) { IMPL.doCheckFile(path, access, requester); }
	static boolean allowNetwork(String requester, String verb, String host, int port) { return IMPL.doAllowNetwork(requester, verb, host, port); }
	static String canonical(File f) { return IMPL.doCanonical(f); }
	static void chosenByUser(String requester, File chosen, boolean saving) { IMPL.doChosenByUser(requester, chosen, saving); }
	static String scriptRequester() { return IMPL.doScriptRequester(); }
	static void created(String requester, File path) { IMPL.doCreated(requester, path); }
	static boolean allowedWithoutAsking(File path) { return IMPL.doAllowedWithoutAsking(path); }
	static boolean allowedWithoutAsking(File path, String requester) { return IMPL.doAllowedWithoutAsking(path, Level.READ, requester); }
	static boolean allowedWithoutAsking(File path, Level level, String requester) { return IMPL.doAllowedWithoutAsking(path, level, requester); }
	static boolean isCreatedBy(String requester, File path) { return IMPL.doIsCreatedBy(requester, path); }
	static void hostResolved(String name, String[] addresses) { IMPL.doHostResolved(name, addresses); }
	static boolean allowsEverything() { return IMPL.doAllowsEverything(); }
	static boolean allowOther(String requester, String verb, String detail) { return IMPL.doAllowOther(requester, verb, detail); }
	static boolean readOnlyFor(File path, String requester) { return IMPL.doReadOnlyFor(path, requester); }
	static boolean allowProgramChange(File target, String requester) { return IMPL.doAllowProgramChange(target, requester); }
	static void trustStackFolder(File folder) { IMPL.doTrustStackFolder(folder); }
	static void runsUnattended(String requester, boolean on) { IMPL.doRunsUnattended(requester, on); }
	static void programLaunched(String requester, File program, List<File> documents) { IMPL.doProgramLaunched(requester, program, documents); }
	static boolean isProgramOf(String requester, File file) { return IMPL.doIsProgramOf(requester, file); }
	static boolean isBuiltIn(File path) { return IMPL.doIsBuiltIn(path); }
	static void checkProgramChange(File target, String requester) { IMPL.doCheckProgramChange(target, requester); }
	static List<File> allowedRoots() { return IMPL.doAllowedRoots(null); }
	static List<File> allowedRoots(String requester) { return IMPL.doAllowedRoots(requester); }
	static boolean allowNewExtensions(String requester, String names) { return IMPL.doAllowNewExtensions(requester, names); }
	static boolean allowInfectedExtension(String extension, String virus) { return IMPL.doAllowInfectedExtension(extension, virus); }
}

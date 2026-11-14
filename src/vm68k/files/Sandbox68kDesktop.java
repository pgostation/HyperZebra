import java.io.File;
import java.util.List;

/** Sandbox68k on the desktop: every question goes to the application's Sandbox, as vm68k asked it before (P4). */
final class Sandbox68kDesktop extends Sandbox68k {
	Sandbox68kDesktop() {}
	private static Sandbox.Access a(Access x) { return Sandbox.Access.valueOf(x.name()); }
	private static Sandbox.Level l(Level x) { return Sandbox.Level.valueOf(x.name()); }
	@Override boolean doAllowFile(File path, Access access, String requester) { return Sandbox.allowFile(path, a(access), requester); }
	@Override void doCheckFile(File path, Access access, String requester) { Sandbox.checkFile(path, a(access), requester); }
	@Override boolean doAllowNetwork(String requester, String verb, String host, int port) { return Sandbox.allowNetwork(requester, verb, host, port); }
	@Override String doCanonical(File f) { return Sandbox.canonical(f); }
	@Override void doChosenByUser(String requester, File chosen, boolean saving) { Sandbox.chosenByUser(requester, chosen, saving); }
	@Override String doScriptRequester() { return Sandbox.scriptRequester(); }
	@Override void doCreated(String requester, File path) { Sandbox.created(requester, path); }
	@Override boolean doAllowedWithoutAsking(File path) { return Sandbox.allowedWithoutAsking(path); }
	@Override boolean doAllowedWithoutAsking(File path, Level level, String requester) { return Sandbox.allowedWithoutAsking(path, l(level), requester); }
	@Override boolean doIsCreatedBy(String requester, File path) { return Sandbox.isCreatedBy(requester, path); }
	@Override void doHostResolved(String name, String[] addresses) { Sandbox.hostResolved(name, addresses); }
	@Override boolean doAllowsEverything() { return Sandbox.allowsEverything(); }
	@Override boolean doAllowOther(String requester, String verb, String detail) { return Sandbox.allowOther(requester, verb, detail); }
	@Override boolean doReadOnlyFor(File path, String requester) { return Sandbox.readOnlyFor(path, requester); }
	@Override boolean doAllowProgramChange(File target, String requester) { return Sandbox.allowProgramChange(target, requester); }
	@Override void doTrustStackFolder(File folder) { Sandbox.trustStackFolder(folder); }
	@Override void doRunsUnattended(String requester, boolean on) { Sandbox.runsUnattended(requester, on); }
	@Override void doProgramLaunched(String requester, File program, List<File> documents) { Sandbox.programLaunched(requester, program, documents); }
	@Override boolean doIsProgramOf(String requester, File file) { return Sandbox.isProgramOf(requester, file); }
	@Override boolean doIsBuiltIn(File path) { return Sandbox.isBuiltIn(path); }
	@Override void doCheckProgramChange(File target, String requester) { Sandbox.checkProgramChange(target, requester); }
	@Override List<File> doAllowedRoots(String requester) { return Sandbox.allowedRoots(requester); }
	@Override boolean doAllowNewExtensions(String requester, String names) { return Sandbox.allowNewExtensions(requester, names); }
	@Override boolean doAllowInfectedExtension(String extension, String virus) { return Sandbox.allowInfectedExtension(extension, virus); }
}

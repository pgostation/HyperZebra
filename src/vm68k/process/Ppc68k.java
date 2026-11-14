import java.io.File;

/**
 * The PowerPC side as the 68K VM reaches it (P4): Mixed Mode descriptors, fat code resources, the runtimes for programs and
 * externals. The desktop's Ppc68kDesktop (found by name) answers with PpcRuntime/PpcCpu; a VM without it (the browser
 * player's 68K one) has no PowerPC: no runtime is ever made, so the calls on one are never reached.
 */
class Ppc68k {
	private static final Ppc68k IMPL = find();
	private static Ppc68k find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new Ppc68k();
		try { return (Ppc68k) Class.forName("Ppc68kDesktop").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new Ppc68k(); }
	}
	boolean doTrace() { return false; }
	boolean doPowerPcOnlyFat(byte[] code) { return false; }
	boolean doPowerPcFat(byte[] code) { return false; }
	byte[] doWithRoutineDescriptor(byte[] code) { return code; }
	boolean doIsPowerPcDescriptor(M68kMemory memory, int rd) throws M68kCpuException { return false; }
	boolean doRunsInLauncher(File target) { return false; }
	PpcRuntime doForCallsFrom68k(MacApp68k app) throws M68kCpuException { throw new M68kCpuException(0, "no PowerPC here"); }
	PpcRuntime doForExternal(Toolbox68k toolbox, M68kCpu cpu, String name, File file) throws M68kCpuException { throw new M68kCpuException(0, "no PowerPC here"); }
	PpcCpu.Context doStartContext(PpcCpu.Context template, int stackPointer) { return null; }

	/** PpcRuntime.TRACE (-Dhzppc.trace) */
	static boolean trace() { return IMPL.doTrace(); }
	static boolean powerPcOnlyFat(byte[] code) { return IMPL.doPowerPcOnlyFat(code); }
	static boolean powerPcFat(byte[] code) { return IMPL.doPowerPcFat(code); }
	static byte[] withRoutineDescriptor(byte[] code) { return IMPL.doWithRoutineDescriptor(code); }
	static boolean isPowerPcDescriptor(M68kMemory memory, int rd) throws M68kCpuException { return IMPL.doIsPowerPcDescriptor(memory, rd); }
	static boolean runsInLauncher(File target) { return IMPL.doRunsInLauncher(target); }
	static PpcRuntime forCallsFrom68k(MacApp68k app) throws M68kCpuException { return IMPL.doForCallsFrom68k(app); }
	static PpcRuntime forExternal(Toolbox68k toolbox, M68kCpu cpu, String name, File file) throws M68kCpuException { return IMPL.doForExternal(toolbox, cpu, name, file); }
	static PpcCpu.Context startContext(PpcCpu.Context template, int stackPointer) { return IMPL.doStartContext(template, stackPointer); }

	// ---- calls on a runtime (P4: the 68K VM's own code never touches PpcRuntime's members; without PowerPC no runtime exists)
	boolean doMixedModeTrap(PpcRuntime rt, M68kCpu cpu, int pc) throws M68kException { return false; }
	boolean doPreparesExternal(PpcRuntime rt, int pc) throws M68kCpuException { return false; }
	int doComponentMain(PpcRuntime rt, int entry, int size, String describe) throws M68kException { return 0; }
	int doCallComponent(PpcRuntime rt, int entry, int procInfo, int[] args) throws M68kException { return 0; }
	boolean doCarbonProgram(PpcRuntime rt) { return false; }
	PpcCpu.Context doSaveContext(PpcRuntime rt) { return null; }
	void doRestoreContext(PpcRuntime rt, PpcCpu.Context c) {}
	String doDescribeLastPc(PpcRuntime rt) { return ""; }
	int doLaunchInProcess(PpcRuntime rt, File target, int serial) throws M68kException { return -1; }
	String doToolboxCall(PpcRuntime rt) { return null; }
	int doNewRoutineDescriptor(PpcRuntime rt, int proc, int procInfo) throws M68kCpuException { return 0; }
	boolean doIsRoutineDescriptor(PpcRuntime rt, int upp) { return false; }
	File doLibraryFile(PpcRuntime rt, int spec) throws M68kCpuException { return null; }
	void doRegisterLibraryFile(PpcRuntime rt, File f) {}
	int doInvokeLibrary(PpcRuntime rt, String name, int[] args) throws M68kException { return -1; }
	String doPString(PpcRuntime rt, int address) throws M68kCpuException { return ""; }
	long doInstructions(PpcRuntime rt) { return 0; }
	String doCfText(PpcRuntime rt, int ref) throws M68kCpuException { return null; }
	/** CFString text of the program's Text Services (rt.libraries.textServices.cfText), null when none */
	static String cfText(PpcRuntime rt, int ref) throws M68kCpuException { return rt == null ? null : IMPL.doCfText(rt, ref); }
	int[][] doModeSizes() { return new int[][] {{512,384},{640,400},{640,480},{800,600},{832,624},{1024,768},{1152,870},{1280,960},{1280,1024},{1600,1200}}; }
	boolean doOpenGlNative() { return false; }

	/** rt.mixedModeTrap(cpu, pc) */
	static boolean mixedModeTrap(PpcRuntime rt, M68kCpu cpu, int pc) throws M68kException { return IMPL.doMixedModeTrap(rt, cpu, pc); }
	static boolean preparesExternal(PpcRuntime rt, int pc) throws M68kCpuException { return IMPL.doPreparesExternal(rt, pc); }
	/** A component's PowerPC code (a PEF container at entry) prepared: its main symbol, 0 when it could not be */
	static int componentMain(PpcRuntime rt, int entry, int size, String describe) throws M68kException { return rt == null ? 0 : IMPL.doComponentMain(rt, entry, size, describe); }
	/** A PowerPC component entry called (a routine descriptor, or plain code's transition vector) */
	static int callComponent(PpcRuntime rt, int entry, int procInfo, int[] args) throws M68kException { return IMPL.doCallComponent(rt, entry, procInfo, args); }
	/** Whether the program links CarbonLib's Carbon (rt.libraries.carbon.carbonProgram()) */
	static boolean carbonProgram(PpcRuntime rt) { return rt != null && IMPL.doCarbonProgram(rt); }
	static PpcCpu.Context saveContext(PpcRuntime rt) { return rt == null ? null : IMPL.doSaveContext(rt); }
	static void restoreContext(PpcRuntime rt, PpcCpu.Context c) { if (rt != null && c != null) IMPL.doRestoreContext(rt, c); }
	static String describeLastPc(PpcRuntime rt) { return IMPL.doDescribeLastPc(rt); }
	static int launchInProcess(PpcRuntime rt, File target, int serial) throws M68kException { return IMPL.doLaunchInProcess(rt, target, serial); }
	/** The Toolbox call the PowerPC code is in (rt.toolboxCall), or null */
	static String toolboxCall(PpcRuntime rt) { return rt == null ? null : IMPL.doToolboxCall(rt); }
	static int newRoutineDescriptor(PpcRuntime rt, int proc, int procInfo) throws M68kCpuException { return IMPL.doNewRoutineDescriptor(rt, proc, procInfo); }
	static boolean isRoutineDescriptor(PpcRuntime rt, int upp) { return rt != null && IMPL.doIsRoutineDescriptor(rt, upp); }
	static File libraryFile(PpcRuntime rt, int spec) throws M68kCpuException { return IMPL.doLibraryFile(rt, spec); }
	/** Keeps a library file the program registered (once) */
	static void registerLibraryFile(PpcRuntime rt, File f) { IMPL.doRegisterLibraryFile(rt, f); }
	static int invokeLibrary(PpcRuntime rt, String name, int[] args) throws M68kException { return IMPL.doInvokeLibrary(rt, name, args); }
	static String pString(PpcRuntime rt, int address) throws M68kCpuException { return IMPL.doPString(rt, address); }
	/** The PowerPC instructions run so far (rt.cpu.instructions), 0 without a runtime */
	static long instructions(PpcRuntime rt) { return rt == null ? 0 : IMPL.doInstructions(rt); }
	/** DrawSprocket's display sizes (PpcGameSprockets.MODE_SIZES) */
	static int[][] modeSizes() { return IMPL.doModeSizes(); }
	/** Whether the native OpenGL is there (PpcOpenGL.NATIVE) */
	static boolean openGlNative() { return IMPL.doOpenGlNative(); }
}

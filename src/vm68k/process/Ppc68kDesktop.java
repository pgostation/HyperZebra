import java.io.File;

/** Ppc68k on the desktop: PpcRuntime and PpcCpu, as the 68K VM called them before P4. */
final class Ppc68kDesktop extends Ppc68k {
	Ppc68kDesktop() {}
	@Override boolean doTrace() { return PpcRuntime.TRACE; }
	@Override boolean doPowerPcOnlyFat(byte[] code) { return PpcRuntime.powerPcOnlyFat(code); }
	@Override boolean doPowerPcFat(byte[] code) { return PpcRuntime.powerPcFat(code); }
	@Override byte[] doWithRoutineDescriptor(byte[] code) { return PpcRuntime.withRoutineDescriptor(code); }
	@Override boolean doIsPowerPcDescriptor(M68kMemory memory, int rd) throws M68kCpuException { return PpcRuntime.isPowerPcDescriptor(memory, rd); }
	@Override boolean doRunsInLauncher(File target) { return PpcRuntime.runsInLauncher(target); }
	@Override PpcRuntime doForCallsFrom68k(MacApp68k app) throws M68kCpuException { return PpcRuntime.forCallsFrom68k(app); }
	@Override PpcRuntime doForExternal(Toolbox68k toolbox, M68kCpu cpu, String name, File file) throws M68kCpuException { return PpcRuntime.forExternal(toolbox, cpu, name, file); }
	@Override PpcCpu.Context doStartContext(PpcCpu.Context template, int stackPointer) { return PpcCpu.startContext(template, stackPointer); }

	@Override boolean doMixedModeTrap(PpcRuntime rt, M68kCpu cpu, int pc) throws M68kException { return rt.mixedModeTrap(cpu, pc); }
	@Override boolean doPreparesExternal(PpcRuntime rt, int pc) throws M68kCpuException { return rt.preparesExternal(pc); }
	@Override int doComponentMain(PpcRuntime rt, int entry, int size, String describe) throws M68kException {
		PpcRuntime.Fragment f = rt.tryLoadMemoryFragment(entry, size, describe);
		return f == null ? 0 : f.instance.main();
	}
	@Override int doCallComponent(PpcRuntime rt, int entry, int procInfo, int[] args) throws M68kException {
		return rt.isRoutineDescriptor(entry) ? rt.callUniversal(entry, procInfo, args) : rt.cpu.call(entry, args);
	}
	@Override boolean doCarbonProgram(PpcRuntime rt) { return rt.libraries.carbon != null && rt.libraries.carbon.carbonProgram(); }
	@Override PpcCpu.Context doSaveContext(PpcRuntime rt) { return rt.cpu.saveContext(); }
	@Override void doRestoreContext(PpcRuntime rt, PpcCpu.Context c) { rt.cpu.restoreContext(c); }
	@Override String doDescribeLastPc(PpcRuntime rt) { return rt.describe(rt.cpu.lastPc()); }
	@Override int doLaunchInProcess(PpcRuntime rt, File target, int serial) throws M68kException { return rt.launchInProcess(target, serial); }
	@Override String doToolboxCall(PpcRuntime rt) { return rt.toolboxCall; }
	@Override int doNewRoutineDescriptor(PpcRuntime rt, int proc, int procInfo) throws M68kCpuException { return rt.newRoutineDescriptor(proc, procInfo, PpcRuntime.ISA_PPC); }
	@Override boolean doIsRoutineDescriptor(PpcRuntime rt, int upp) { return rt.isRoutineDescriptor(upp); }
	@Override File doLibraryFile(PpcRuntime rt, int spec) throws M68kCpuException { return rt.libraries.fileOf(spec); }
	@Override void doRegisterLibraryFile(PpcRuntime rt, File f) { if (!rt.registeredLibraryFiles.contains(f)) rt.registeredLibraryFiles.add(f); }
	@Override int doInvokeLibrary(PpcRuntime rt, String name, int[] args) throws M68kException { return rt.libraries.invoke(name, args); }
	@Override String doPString(PpcRuntime rt, int address) throws M68kCpuException { return rt.pString(address); }
	@Override long doInstructions(PpcRuntime rt) { return rt.cpu.instructions; }
	@Override int[][] doModeSizes() { return PpcGameSprockets.MODE_SIZES; }
	@Override boolean doOpenGlNative() { return PpcOpenGL.NATIVE; }
	@Override String doCfText(PpcRuntime rt, int ref) throws M68kCpuException { return rt.libraries.textServices.cfText(ref); }
}

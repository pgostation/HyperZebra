/** Base class for failures reported by the embedded Motorola 68000 emulator. */
class M68kException extends Exception {
	private static final long serialVersionUID = 1L;

	M68kException(String message) { super(message); }
}

/** An instruction tried to enter the (not yet emulated) Macintosh OS/Toolbox. */
class M68kMacApiException extends M68kException {
	private static final long serialVersionUID = 1L;
	final int pc;
	final int trap;

	M68kMacApiException(int pc, int trap, String detail) {
		super(String.format("Mac API is not supported yet at $%06X: %s", pc, detail));
		this.pc = pc;
		this.trap = trap;
	}
}

/** Invalid code or an instruction which this first emulator version cannot execute. */
class M68kCpuException extends M68kException {
	private static final long serialVersionUID = 1L;
	final int pc;

	M68kCpuException(int pc, String detail) {
		super(String.format("68K VM error at $%06X: %s", pc, detail));
		this.pc = pc;
	}
}

/** Execution was stopped on request (the application was quit or killed). */
class M68kAbortException extends M68kException {
	private static final long serialVersionUID = 1L;
	final int pc;

	M68kAbortException(int pc) {
		super(String.format("68K execution aborted at $%06X", pc));
		this.pc = pc;
	}
}

/**
 * A nested 68K call returned through the sentinel with the stack pointer above an OUTER call's return slot: the code
 * longjmp'd past the host boundary (AppleScript's error handling unwinds from an OSA routine reached through
 * CallComponentFunction back into the component entry's setjmp, whose epilogue then returns). The inner Java caller
 * cannot go on as if its routine had returned; the exception unwinds the Java levels until the one whose frame the
 * 68K code actually returned from, which treats it as its own return.
 */
class M68kLongJumpException extends M68kException {
	private static final long serialVersionUID = 1L;
	final int sp;

	M68kLongJumpException(int sp) {
		super(String.format("68K longjmp past a host call, SP=$%08X", sp));
		this.sp = sp;
	}
}

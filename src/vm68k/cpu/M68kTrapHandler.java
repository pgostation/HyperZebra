interface M68kTrapHandler {
	/** Returns true when the A-line instruction was handled. */
	boolean handle(M68kCpu cpu, int opcode, int instructionPc) throws M68kException;

	/** Human-readable name used when reporting an unsupported trap. */
	default String name(int opcode) { return String.format("A-line trap $%04X", opcode); }

	/** Entry point of guest code that implements a package trap the handler has no native version of (a program's own
	 *  'PACK' resource, which the Package Manager would load for _PackN), or 0. */
	default int packageEntry(M68kCpu cpu, int opcode) throws M68kException { return 0; }
}

/** A window of a VM host as the Toolbox sees it (P4: MacAppHost.HostWindow on the desktop) */
interface VmWindow {
	/** the WindowRecord's address */
	int record();
	int procId();
	/** the host shows a frame of its own (not a bare window) */
	boolean decorated();
	/** a custom WDEF draws the frame into the window's buffer */
	boolean customFrame();
}

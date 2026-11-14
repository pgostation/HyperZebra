/**
 * The stacks' own 68K externals in the browser (P4): the 68K player (player-68k.wasm) sets {@link Runner#current} to the 68K
 * VM's (PlayerExternals68k); the core player has none, and such externals do nothing there (PlayerUiHost).
 * Besides the calls, what HyperCard does to the card window that the externals' drawing on it follows (P4-4, CardWindow68k's
 * rules, as the desktop's UiHost passes them on): the card redrawn or copied into the window again, a part's rectangle copied,
 * the screen held around a change an external redraws, a stack closing.
 */
interface Externals68k {
	/** The one in use, or null. */
	final class Runner { static Externals68k current; private Runner() {} }
	/** Runs owner's external (an XCMD or XFCN of 68K code); false when it is not one this VM runs (PowerPC only…). */
	boolean call(OStack owner, String name, String[] params, boolean isFunc, Result result) throws xTalkException;

	/** Another card (CardWindow68k.cardRedrawn). */
	default void cardRedrawn() {}
	/** The whole card went into the window again: unlock screen, a visual effect's end (CardWindow68k.cardCopied). */
	default void cardCopied() {}
	/** A part's rectangle of the card went into the window (card coordinates). */
	default void partCopied(int top, int left, int bottom, int right) {}
	/** A part on show moved or changed size: its old and new rectangles. */
	default void partMoved(OObject part, int oldTop, int oldLeft, int oldBottom, int oldRight) {}
	default void holdScreen() {}
	/** Go > Next Window (doMenu "next window"): an external's backdrop window round the card goes behind it (Myst Preview's black frame) */
	default void nextWindow() {}
	default void releaseScreen() {}
	/** Externals that draw the card window whenever HyperCard copies the card there redraw it (Xcmd68k.redrawHookedCards). */
	default boolean redrawHookedCards(OStack stack) { return false; }
	default boolean redrawHookedCards(OStack stack, int top, int left, int bottom, int right) { return false; }
	/** Whether an external of the stack draws the card window at every copy of the card (ColorizeHC, HyperTint). */
	default boolean cardDrawingHooked(OStack stack) { return false; }
	/** The message box was hidden: shown again it is drawn afresh, without what externals drew in it. */
	default void messageBoxHidden() {}
	/** The stack closes: its externals' windows and drawing go. */
	default void stackClosing(OStack stack) {}

	// P4-5: the VMs externals keep (an open XWindow, state between calls) get HyperCard's events as its event loop gives them
	/** Whether a VM is kept (Xcmd68k.hasSessions). */
	default boolean hasSessions() { return false; }
	/** Whether a VM has external windows (or is in the first call of one that may make them): Xcmd68k.hasWindowSessions. */
	default boolean hasWindowSessions() { return false; }
	/** An external window by its title, by its id (window "…" / window id … of a script). */
	default Xcmd68k.ExternalWindow findWindow(String name) { return null; }
	default Xcmd68k.ExternalWindow findWindowById(int id) { return null; }
	/** The script's object for an external window (its properties, send, close). */
	default OXWindow newWindow(Xcmd68k.ExternalWindow w) { return null; }
	/** the mouseClick: a click queued for an external window is taken (it reaches no window). */
	default boolean takeWindowClick() { return false; }
	/** At idle: the kept VMs' windows get their events, null events included (Xcmd68k.pump), and timer tasks that are due run. */
	default void pump() {}
	/** Between a running handler's statements, at most every 20 ms (Xcmd68k.pumpIfDue). */
	default void pumpIfDue() {}
	/** During a handler's wait: clicks, and null events only for windows with interrupt code (Xcmd68k.pumpWhileWaiting). */
	default void pumpWhileWaiting() {}
	/** The sound the 68K VMs make, mixed up to a little ahead of now and handed to the host (PlayerHost.audio); called often
	 *  (the player's turns, ticks and waits, the running external's slices). True while it streams. */
	default boolean audio() { return false; }
	/** A press or release on the card at (x, y), page modifiers: true when it fell on an external's window, which gets it. */
	default boolean pressAt(boolean down, int x, int y, int modifiers) { return false; }
	/** The pointer moved to (x, y): true when it drags an external's window. */
	default boolean pointerAt(int x, int y) { return false; }
	/** A file of that name in the folder as the 68K File Manager finds it (the other script's reading, an unarchiver's name), or
	 *  null (GetFilePath: the Movie XCMD finds its movie so) */
	default java.io.File existingFile(java.io.File folder, String name) { java.io.File f = new java.io.File(folder, name); return f.exists() ? f : null; }
	/** A key event of the page ({@link PlayerHost} KEY_DOWN/KEY_UP with its fields): true when an external's window has the
	 *  keyboard and got it. */
	default boolean key(int[] e) { return false; }
}

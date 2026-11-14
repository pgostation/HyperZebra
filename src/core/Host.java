/**
 * What the HyperTalk core asks of the program showing it. The desktop's {@code SwingHost} (src/ui) forwards each call to
 * the code that did it before (PCARD: the EDT, repaints, the zoom); the headless core and the browser build use
 * {@link HeadlessHost}: no event thread, no screen, logical pixels at 1:1.
 *
 * The desktop host is found by name, as {@link Platform} is, so a build of the core alone never pulls in Swing.
 */
abstract class Host {
	private static volatile Host current;

	static Host get() {
		Host h = current;
		if (h == null) current = h = byName(System.getProperty("hz.host", "SwingHost"));
		return h;
	}
	static void set(Host h) { current = h; }

	private static Host byName(String name) {
		try { return (Host) Class.forName(name).getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return new HeadlessHost(); }
	}

	/** The kinds of part update runOnEDTWhenShown merges under lock screen. */
	static final int EDT_VISIBLE = 1, EDT_BOUNDS = 2;

	// ---- the event thread (PCARD.runOnEDT...) ----
	/** Runs a change of the shown parts where the display does it (the EDT), waiting for it. */
	abstract void runOnEDT(Runnable task);
	/** runOnEDT for a change that only shows on the screen: under lock screen it may wait for the unlock. */
	abstract void runOnEDTWhenShown(Runnable task);
	/** The same for one kind of update of one part (at most one queued per part and kind). */
	abstract void runOnEDTWhenShown(OObject part, int kind, Runnable now, Runnable latest);
	/** Runs body here, collecting the event-thread tasks it makes into one. */
	abstract void batchOnEDT(Runnable body);

	// ---- the screen ----
	/** Repaints a rectangle of the card (logical coordinates). */
	abstract void repaintLogicalRect(int left, int top, int width, int height);
	/** The magnification of the card window (1 = card pixels). */
	abstract double scale();
	/** A logical (card) coordinate or length in window pixels. */
	abstract int toPhysical(int logical);
	/** A fractional logical coordinate in window pixels (rounded). */
	abstract int toPhysical(double logical);
	/** The window-pixel rectangle of a logical one. */
	abstract java.awt.Rectangle toPhysical(int left, int top, int width, int height);
	/** A window-pixel coordinate as a card coordinate. */
	abstract int toLogical(int physical);
	// ---- tools ----
	/** A paint tool is chosen (PCARD.pc.tool on the desktop). */
	abstract boolean paintToolActive();
	/** An authoring tool (button, field) is chosen (AuthTool.tool on the desktop). */
	abstract boolean authToolActive();

	/** There is a card window (PCARD.pc on the desktop); the headless core has its card window in the model only: true. */
	abstract boolean hasCardWindow();

	/** A window-pixel length as a logical one (rounded). */
	abstract int toLogicalLength(double physical);
}

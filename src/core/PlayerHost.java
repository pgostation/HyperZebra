/**
 * What a player's page gives the core (P2): input, real time, a canvas, dialogs. The browser's is WebMain's (the shared
 * memory the page writes, Atomics.wait, the worker's OffscreenCanvas, postMessage); a test's is a script of events.
 * Events are int arrays: {type, x, y, modifiers, keyCode, character, extra} with the types below; modifiers are
 * 1 shift, 2 option, 4 command, 8 control, 16 caps lock.
 */
interface PlayerHost {
	int DOWN = 1, UP = 2, KEY_DOWN = 3, KEY_UP = 4, MOVE = 5, MESSAGE_BOX = 6, MENU = 7;
	/** The page is hidden or closing: the stacks' changes are kept now (PlayerSaving.flush) */
	int FLUSH = 8;
	/** The mouse wheel over the card: extra = how far down, in card pixels (up: negative) */
	int WHEEL = 9;
	/** A change made in Button Info / Field Info on the page: the text is "part token\nproperty\tvalue" (PartInfo) */
	int PART_INFO = 10;
	/** The line chosen in the menu of a pop-up button the page showed ("popup"): its number as text, "0" when none was */
	int POPUP = 11;
	int SHIFT = 1, OPTION = 2, COMMAND = 4, CONTROL = 8, CAPS_LOCK = 16;

	/** The next input event, or null. */
	int[] nextEvent();
	int mouseX();
	int mouseY();
	boolean mouseIsDown();
	int modifiers();
	/** Whether Command-period was pressed since the last call. */
	boolean takeAbort();
	/** The text that came with an event (its extra is the length): a message box line, a menu item. */
	String eventText(int length);
	/** an event's text at its place in the text buffer (the event's keyCode word: the page puts each event's text in a place
	 *  of its own, so that two sent close together keep theirs) */
	default String eventText(int offset, int length) { return eventText(length); }

	/** The time, in nanoseconds (real time). */
	long nanoTime();
	/** Sleeps until input comes or nanos pass. */
	void sleep(long nanos);

	/** The card's canvas at that size (made again when the size changes). */
	Gfx canvas(int width, int height);
	/** A canvas of its own, not shown (a picture's text and lines drawn while it is decoded: HzPict). */
	Gfx offscreen(int width, int height);
	/** What was drawn on the canvas, to the screen. */
	void present();
	/** The screen wants the card drawn again though nothing changed (the browser's page shows it at another size) */
	default boolean redrawWanted() { return false; }
	/** How the host draws the card again while a dialog waits (redrawWanted then: the page fitted the card to the window
	 *  behind openStack's answer dialogs - DoodlePaint's card showed at a quarter of its pixels, stretched, until they closed) */
	default void redrawer(Runnable draw) {}
	/** What goes on while a dialog waits for its answer: the 68K VMs' sound and events (a MOD playing behind an answer dialog), called
	 *  every few milliseconds; it returns whether something still needs those turns (false: the host just waits) */
	default void dialogIdler(java.util.function.BooleanSupplier idle) {}

	/** A dialog: answer (its buttons) or ask (with a text field, hidden for a password). Blocks until the user answers:
	 *  the button's number (1-based); reply[0] is what was typed in an ask. */
	int dialog(String kind, String prompt, String[] buttons, String text, String[] reply);
	/** A notice to the page: "msg" (the message box's text), "menus", "beep", "status", "title". */
	void post(String kind, String text);

	/** A sound's samples, given once before notes use it (ids from 1); right is null for a mono sound. */
	void soundData(int id, short[] left, short[] right, double rate);
	/** A note after the ones queued: the sound (0: a rest), the playback rate (the pitch), its length in seconds, the loop in
	 *  the sound's frames (0, 0: none), the gain. A note shorter than its sound ends with a short fade. */
	void playNote(int id, double pitch, double seconds, int loopStart, int loopEnd, double gain);
	/** play stop: the playing note fades out, the queued ones are dropped. */
	void stopSound();
	/** The next stretch of a stream of sound (P4: the 68K VM's mix): frames of interleaved 16-bit stereo at rate, played after
	 *  the stretch before without a gap; a gap in the stream (nothing sent for a while) starts it again. */
	default void audio(short[] interleaved, int frames, int rate) {}
	/** What 68K externals drew in the message box (P4: ARGB, width x height, the box's body; transparent where they drew
	 *  nothing), shown over the page's own box; null: nothing any more */
	default void messageOverlay(int[] argb, int width, int height) {}
	/** Whether the page shows HyperCard's palettes as windows of their own over it, where they may leave the card
	 *  (floatingWindows), instead of on the card */
	default boolean floatsPalettes() { return false; }
	/** The page's window in card coordinates {left, top, right, bottom}: where a floating palette may go; null: the card */
	default int[] screenArea() { return null; }
	/** The floating palettes now, back to front: their window ids, {left, top, width, height} each with the frame (card
	 *  coordinates, 4 per window), and their ARGB pixels (null: as last sent). An empty list: none any more. */
	default void floatingWindows(int[] ids, int[] rects, int[][] argb) {}
	/** Whether the page can record from a microphone (the browser has one to offer; it asks the user when it opens) */
	default boolean microphoneAvailable() { return false; }
	/** Opens (asking the user) or closes the microphone; samples come in from the moment it is open */
	default void microphoneOn(boolean on) {}
	/** The samples recorded since the last call, mono 16-bit at microphoneRate(), into the array: how many; -1 when the
	 *  microphone is refused or gone */
	default int microphone(short[] into) { return -1; }
	/** The microphone's sample rate (Hz), 0 until it is open */
	default int microphoneRate() { return 0; }

	/** A stack's _stack.xml to keep for this page (PlayerSaving): its storage name (HeadlessStacks.storageName), the
	 *  fingerprint of the _stack.xml the page gave (what was kept is used again only for that stack), the bytes */
	default void saveStack(String name, String fingerprint, byte[] xml) {}
	/** saveStack with the stack's other files the player wrote (painted pictures, HzPaint), by path: kept with it */
	default void saveStack(String name, String fingerprint, byte[] xml, java.util.Map<String, byte[]> files) { saveStack(name, fingerprint, xml); }
	/** A file for the user to download (File > Save a Copy…) */
	default void download(String fileName, byte[] bytes) {}
	/** The bytes of the file the user picked from the computer in an answer file dialog (its reply named it and its size),
	 *  null when it could not be read */
	default byte[] upload(int size) { return null; }
	/** A file the stack wrote or the user brought in (its path in the player's files, /stacks/…): the page keeps it for its
	 *  next visit (player.js: IndexedDB, given back at the start) */
	default void keepFile(String path, byte[] bytes) {}
}

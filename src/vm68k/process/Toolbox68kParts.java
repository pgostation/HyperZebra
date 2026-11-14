/**
 * The Toolbox areas a VM gets besides those every one has (P4): networking (Communications Toolbox, MacTCP, AppleTalk),
 * QuickTime and the Image Compression Manager, desk accessories, printing, Standard File, Apple events, the Edition, Help,
 * Drag, ADB, Text Services and Appearance managers, the .Sound driver, extensions, cursor devices. The desktop's
 * Toolbox68kDesktopParts (found by name) makes all of them, in the order Toolbox68k always made them; without it (the
 * browser player's 68K VM) they stay null and their traps are unimplemented.
 */
class Toolbox68kParts {
	CommToolbox68k ctb(Toolbox68k tb) { return null; }
	AppleEvents68k appleEvents(Toolbox68k tb) { return null; }
	StandardFile68k standardFile(Toolbox68k tb) { return null; }
	EditionManager68k editions(Toolbox68k tb) { return null; }
	SoundDriver68k soundDriver(Toolbox68k tb) { return new SoundDriver68k(tb); }
	MacTCP68k macTcp(Toolbox68k tb) { return null; }
	AppleTalk68k appleTalk(Toolbox68k tb) { return null; }
	DeskAccessories68k desk(Toolbox68k tb) { return null; }
	QuickTime68k quickTime(Toolbox68k tb) { return new QuickTime68k(tb); }
	ImageCompression68k imageCompression(Toolbox68k tb) { return null; }
	Extensions68k extensions(Toolbox68k tb) { return null; }
	/** Balloon Help without the desktop's Swing and font parts: the settings and messages work, the host shows the balloons (the player's shows none) */
	HelpManager68k help(Toolbox68k tb) { return new HelpManager68k(tb); }
	PrintManager68k print(Toolbox68k tb) { return null; }
	DragManager68k drag(Toolbox68k tb) { return null; }
	AdbManager68k adb(Toolbox68k tb) { return null; }
	TextServices68k textServices(Toolbox68k tb) { return null; }
	AppearanceManager68k appearance(Toolbox68k tb) { return null; }
	CursorDevices68k cursorDevices(Toolbox68k tb) { return null; }
	/** The scrap in memory (the browser player: no host clipboard); the desktop's mirrors the host clipboard */
	ScrapManager68k scrap(M68kMemory memory, Mac68kApi api) { return new ScrapManager68k(memory, api); }
	SoundManager68k sound(M68kMemory memory, Mac68kApi api) { return new SoundManager68k(memory, api); }
	AppearanceControls68k appearanceControls(Toolbox68k tb, ControlManager68k controls) { return null; }
	OpenTransport openTransport(Toolbox68k tb) { return null; }
	/** Whether the Open Transport glue's low-memory globals are set up */
	boolean openTransportInstalled() { return false; }
	/** The native QuickTime components: here the movie controller (and the decompression sequences it draws with), not the
	 *  desktop's graphics importers, compressors and music */
	void quickTimeComponents(Components68k components, Toolbox68k tb) { components.registerMovieController(); }
	/** A movie file as the Movie Toolbox plays it: the own decoders only here (MovieData; the desktop adds ffmpeg and MIDI) */
	MovieData movieData(java.io.File file) { return MovieData.cached(file); }
	/** A movie's sound: a voice of the 68K mixer here, null when there is none it can play */
	MovieSound68k movieSound(MovieData data) { return MovieSoundMixer68k.of(data); }
	/** ConvertMovieToFile to a MIDI file: an OSErr (no MIDI here: cantFindHandler) */
	int movieToMidiFile(MovieData data, java.io.File movie, java.io.File output) { return -2003; }

	/** The VM's host (the desktop's MacAppHost; null here until the player's is made) */
	VmHost host(VmHostListener listener, M68kMemory memory, String name, boolean headless, boolean external) { return new PlayerVmHost(listener, memory, name, headless, external); }
	/** No display to show windows on (AWT's headless mode on the desktop) */
	boolean headlessDisplay() { return true; }

	/** The VIA/SCC/sound-buffer chips (MacIo68k, which also drives the host's MIDI ports) */
	MacIo68k io(Toolbox68k tb, M68kMemory memory) throws M68kCpuException { return null; }
	/** _Launch: another program started (null: none here, fnfErr) */
	MacApp68k launch(java.io.File target, java.util.List<java.io.File> documents) throws java.io.IOException { return null; }
	/** A desk accessory in a process of its own (null: none here) */
	MacApp68k openDeskAccessory(java.io.File file, String name) throws java.io.IOException { return null; }
	/** Internet Config's component ('ICAp') */
	void internetConfig(Components68k components, Toolbox68k tb) {}

	// ---- the host's files and process (the desktop's code before P4; nothing of the kind where one program runs alone)

	/** A lock on a state file other copies of HyperZebra share (FileChannel.lock). */
	interface FileLock68k extends AutoCloseable { @Override void close() throws java.io.IOException; }
	FileLock68k lock(java.io.RandomAccessFile f) throws java.io.IOException { return () -> {}; }
	/** Whether a RandomAccessFile opened "rw" empties the file (TeaVM's does; the JDK's does not). */
	boolean emptiesReadWriteOpen() { return true; }	/** RandomAccessFile.getFD().sync(). */
	void sync(java.io.RandomAccessFile f) throws java.io.IOException {}
	/** This process's id (ProcessHandle.current().pid()). */
	long pid() { return 1; }
	/** Whether a process of that id is still running. */
	boolean alive(long pid) { return pid == pid(); }
	/** The folder or jar the classes came from (the code source), or null. */
	java.io.File codeLocation() { return null; }
	/**
	 * PBDTGetAPPL's walk of the host folders for a creator's files. The desktop walks on daemon threads and waits until
	 * the deadline (a walk still going answers null, settled false, and is kept for the next call), the program's own
	 * folder walked beside it first; here the walk runs to the end at once. mainWalk walks the queue (and caches its
	 * index), ownWalk walks one root, pick takes the index-th file out of a walk's index.
	 */
	java.io.File creatorWalk(String cacheKey, java.util.ArrayDeque<java.io.File[]> queue, long deadline, boolean[] settled, int index,
			java.util.function.Predicate<java.io.File[]> ownFolder, java.util.function.Supplier<Object> mainWalk,
			java.util.function.Function<java.util.ArrayDeque<java.io.File[]>, Object> ownWalk, java.util.function.BiFunction<Object, Integer, java.io.File> pick) {
		return pick.apply(mainWalk.get(), index);
	}

	/** Runs tasks after a delay on a timer of its own (a daemon ScheduledExecutorService of that thread name on the desktop). */
	interface Timer68k { void schedule(Runnable task, long delay, java.util.concurrent.TimeUnit unit); }
	/** Here the tasks wait in one list for runDue (the player's idle time), in the order they fall due. */
	Timer68k timer(String threadName) {
		return (task, delay, unit) -> {
			long due = System.nanoTime() + unit.toNanos(delay);
			int i = 0;
			while (i < DUE.size() && DUE.get(i).due <= due) i++;
			DUE.add(i, new Due(due, task));
		};
	}
	private static final class Due { final long due; final Runnable task; Due(long due, Runnable task) { this.due = due; this.task = task; } }
	private static final java.util.ArrayList<Due> DUE = new java.util.ArrayList<>();
	/** Runs the timer tasks that are due (the desktop's run on their own threads: nothing to do). */
	void runDue() {
		long now = System.nanoTime();
		while (!DUE.isEmpty() && DUE.get(0).due <= now) DUE.remove(0).task.run();
	}

	// ---- the host's UI (Toolkit, the clipboard, Swing dialogs, the palettes, keys on the card)

	/** SndRecord's dialog (SoundInput68k): 22050 Hz 8-bit mono samples recorded, null when cancelled. Here the page's dialogs:
	 *  Record opens the microphone (the browser asks the user), Stop ends it; what came meanwhile is the recording. */
	byte[] recordWithDialog() {
		PlayerHost h = PlayerInput.host;
		if (h == null || !h.microphoneAvailable()) return null;
		String[] reply = new String[1];
		if (h.dialog("answer", "Record a sound with the microphone.", new String[] {"Cancel", "Record"}, "", reply) != 2) return null;
		h.microphoneOn(true);
		int stop = h.dialog("answer", "Recording... Click Stop when you are done.", new String[] {"Cancel", "Stop"}, "", reply);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		short[] buf = new short[8192]; double[] state = new double[2];
		javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(22050, 8, 1, false, true);
		int rate = h.microphoneRate();
		for (int n; (n = h.microphone(buf)) > 0; ) if (rate > 0) { byte[] b = SoundInput68k.convert(buf, n, rate, format, state); out.write(b, 0, b.length); }
		h.microphoneOn(false);
		return stop == 2 ? out.toByteArray() : null;
	}
	/** The system alert sound (Toolkit.beep): here the player's beep. */
	void beep() { UiHost.get().beep(); }
	/** The host clipboard's text, or null (here there is none to read). */
	String hostClipboardText() { return null; }
	/** Puts text on the host clipboard. */
	void setHostClipboardText(String text) {}
	/** NMInstall's message (a dialog on the desktop, later on the event thread). */
	void notification(String title, String message) {}
	/** One of the names picked from a list (-1: none, or no way to ask). */
	int chooseFromList(String prompt, java.util.List<String> names) { return -1; }
	/** HideHCPalettes/ShowHCPalettes: HyperCard's palettes (the message box, the tools) hidden or brought back. */
	void showHyperCardPalettes(boolean show) {}
	/** Starts watching the card window's key presses (each one to note; a modifier key alone is no press). */
	void watchCardKeys(java.util.function.Consumer<Object> note) {
		PlayerInput.keyWatcher = k -> note.accept(new int[] {KeyCodes68k.macKeyCodeOfDom(k[0], k[3]), KeyCodes68k.macCharOfDom(k[0], k[3], k[1]), KeyCodes68k.macModifiers(k[2], PlayerInput.mouseDown())});
	}
	/** Whether a card key is a modifier pressed alone (Shift, Control, Option, Command, Caps Lock), which goes to no external. */
	boolean modifierKey(Object event) {
		if (!(event instanceof int[] k)) return false;
		switch (k[0]) { case 55: case 56: case 57: case 58: case 59: case 60: case 61: case 62: return true; default: return false; } // Command, Shift, Caps Lock, Option, Control (left and right)
	}
	/** A card key as {Mac key code (-1: none), Mac character (-1: none), event modifiers}: here the player gives these numbers. */
	int[] cardKey(Object event, VmHost host) { return event instanceof int[] k ? k : new int[] {-1, -1, 0}; }
	/** A picture file read (ImageIO on the desktop; PNG and JPEG here), or null. */
	java.awt.image.BufferedImage readImage(byte[] data) {
		if (data == null || data.length < 4) return null;
		int[] argb; int w, h;
		if ((data[0] & 0xff) == 0x89 && data[1] == 'P') {
			HzImage img = PngDecoder.decode(data);
			if (img == null) return null;
			argb = img.argb; w = img.width; h = img.height;
		} else if ((data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8) {
			try { JpegDecoder j = JpegDecoder.decode(data, 0, data.length); w = j.width; h = j.height; argb = new int[w * h]; for (int i = 0; i < argb.length; i++) argb[i] = 0xff000000 | j.rgb[i]; }
			catch (java.io.IOException | RuntimeException e) { return null; }
		} else return null;
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, w, h, argb, 0, w);
		return out;
	}
	java.awt.image.BufferedImage readImage(java.io.File file) throws java.io.IOException {
		return file.isFile() ? readImage(Files68k.read(file)) : null;
	}
	/** Writes a PNG (ImageIO); false when it cannot. */
	boolean writePng(java.awt.image.BufferedImage image, java.io.File file) throws java.io.IOException { return false; }

	/** Runs work for each row from top to bottom (inclusive): in parallel on the desktop (IntStream.parallel), in order here. */
	void parallelRows(int top, int bottom, java.util.function.IntConsumer work) { for (int y = top; y <= bottom; y++) work.accept(y); }
	/** A busy wait's pause (Thread.onSpinWait). */
	void spinWait() {}
	/** Runs when HyperZebra quits (a shutdown hook of that name). */
	void atQuit(Runnable work, String name) {}
	/** A PICT file drawn by the host's picture reader (PictureFile.loadPICT), null when there is none. */
	java.awt.image.BufferedImage loadPict(java.io.File file) { return null; }
	/** Writes a PNG to a stream (ImageIO); false when it cannot. */
	boolean writePng(java.awt.image.BufferedImage image, java.io.OutputStream out) throws java.io.IOException { return false; }

	/** A helper program the VM talks to over its standard input and output (ProcessBuilder, its errors discarded). */
	interface HelperProcess68k { boolean alive(); java.io.OutputStream stdin(); java.io.InputStream stdout(); void destroy(); }
	HelperProcess68k startProcess(java.io.File program) throws java.io.IOException { throw new java.io.IOException("no programs run here"); }
	/** File.canExecute. */
	boolean canExecute(java.io.File f) { return false; }

	static Toolbox68kParts find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new Toolbox68kParts();
		try { return (Toolbox68kParts) Class.forName("Toolbox68kDesktopParts").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new Toolbox68kParts(); }
	}
}

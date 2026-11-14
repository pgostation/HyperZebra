import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HyperCard 2.4's built-in Movie XCMD in the browser player (TQTMovie's Movie XCMD playback on the desktop): Movie file,
 * style, loc, visible, layering and open movie make a movie window over the card (a PictureWindows window whose picture
 * is the movie's frame), stopped at its start; play / pause / rewind / stepFwd / stepRev, close movie, and its properties
 * (currTime, duration, timeScale, rate, loop, soundVolume, movie, loc, rect, visible) as the desktop's. The frames follow the
 * player's clock, the sound is the 68K mixer's (MoviePlayer). Messages: openMovie and closeMovie (name, id),
 * mouseDownInMovie / mouseUpInMovie (name, id, point in the movie, currTime). A movie with sound and no video has no window,
 * but scripts reach it as movie N all the same. Without a MoviePlayer (the core player) the command does nothing.
 */
final class MovieWindows {
	private MovieWindows() {}

	static final int DEFAULT_TIME_SCALE = 600;

	/** One movie of the scripts */
	static final class Playback {
		final MoviePlayer.Movie movie;
		final String handle, name;
		final int id;
		final File file;
		final String style;
		PictureWindows.Pict pict;
		OWindow window;
		boolean loop, playing, finished;
		/** while playing: when it was at 0 (player milliseconds); stopped: where it is */
		long startMs, positionMs;
		int volume = 256;
		Playback(MoviePlayer.Movie movie, String handle, String name, int id, File file, String style) {
			this.movie = movie; this.handle = handle; this.name = name; this.id = id; this.file = file; this.style = style;
		}
		int timeScale() { int t = movie.timeScale(); return t > 0 ? t : DEFAULT_TIME_SCALE; }
		long durationMillis() { return Math.max(0, movie.durationMillis()); }
		long duration() { return units(durationMillis()); }
		/** milliseconds as time units, rounded (set the currTime to n gives n back) */
		long units(long ms) { return Math.round(ms * (double) timeScale() / 1000.0); }
		long currentMillis() {
			long ms = playing ? now() - startMs : positionMs;
			if (finished) ms = durationMillis();
			return Math.max(0, Math.min(ms, durationMillis()));
		}
		long currentTime() { return units(currentMillis()); }
	}

	/** By handle, in the order they were opened */
	private static final Map<String, Playback> active = new LinkedHashMap<>();
	private static int handles, ids;

	static long now() { return Platform.get().nanoTime() / 1_000_000L; }
	static boolean available() { return MoviePlayer.current != null; }

	/** A movie the player could not read: no picture, no sound (the desktop keeps such a window for the scripts too) */
	private static final MoviePlayer.Movie NONE = new MoviePlayer.Movie() {
		@Override public boolean hasVideo() { return false; }
		@Override public int width() { return 0; }
		@Override public int height() { return 0; }
		@Override public HzImage frameAt(long millis) { return null; }
		@Override public long durationMillis() { return 0; }
		@Override public int timeScale() { return DEFAULT_TIME_SCALE; }
		@Override public double frameRate() { return 10; }
		@Override public void play(long fromMillis) {}
		@Override public void stop() {}
		@Override public void volume(int level) {}
		@Override public void close() {}
	};

	/** Movie file[,window style,location,visible,layering]: the result is empty unless the file is not there */
	static String open(String[] params, OStack st) {
		MoviePlayer player = MoviePlayer.current;
		if (player == null || params.length < 1) return "";
		File f = find(params[0], st);
		if (f == null) { System.out.println("Movie XCMD: file not found: " + params[0]); return "ファイル" + params[0] + "がありません"; }
		MoviePlayer.Movie movie = null;
		try { movie = player.open(f); } catch (RuntimeException e) { System.out.println("Movie XCMD: " + f.getName() + ": " + e); }
		if (movie == null) { System.out.println("Movie XCMD: " + f.getName() + " is not a movie the player can show"); movie = NONE; }
		// the location: a point or a rect (its top left), document (20,20), card/largest/deepest/main (centred, as no location)
		String style = params.length >= 2 && !params[1].trim().isEmpty() ? params[1].trim() : "windoid";
		int x = -1, y = -1;
		if (params.length >= 3) {
			String loc = params[2].trim();
			if (loc.equalsIgnoreCase("document")) { x = 20; y = 20; }
			else if (!loc.isEmpty() && (Character.isDigit(loc.charAt(0)) || loc.startsWith("-"))) {
				String[] point = loc.split(",");
				try {
					if (point.length > 0) x = Integer.parseInt(point[0].trim());
					if (point.length > 1) y = Integer.parseInt(point[1].trim());
				} catch (NumberFormatException e) { x = -1; y = -1; }
			}
		}
		boolean visible = true, loop = false;
		for (int i = 1; i < params.length; i++) {
			if (params[i].trim().equalsIgnoreCase("loop")) loop = true;
			if (params[i].trim().equalsIgnoreCase("invisible")) visible = false;
		}
		Playback pb = new Playback(movie, "qtmovie" + (++handles), f.getName(), ++ids, f, style);
		pb.loop = loop;
		pb.volume = 256;
		pb.window = new OWindow(pb.name, pb.handle);
		pb.window.id = pb.id;
		if (movie.hasVideo()) {
			HzImage first = movie.frameAt(0);
			if (first == null) first = blank(movie.width(), movie.height());
			int frame = frame(style);
			PictureWindows.Pict p = new PictureWindows.Pict(pb.name, first, false, frame, frame == CardOverlay.TITLE_BAR || frame == CardOverlay.WINDOID_BAR,
				frame == CardOverlay.TITLE_BAR ? pb.name : "");
			p.movie = pb;
			if (x < 0 || y < 0) {
				int w = st == null ? 512 : st.width, h = st == null ? 342 : st.height;
				p.left = (w - p.outerWidth()) / 2; p.top = (h - p.outerHeight()) / 2;
			} else { p.left = x; p.top = y; }
			p.visible = visible;
			pb.pict = p;
			pb.window.pict = p;
			PictureWindows.addMovie(p, pb.window);
		}
		synchronized (active) { active.put(pb.handle, pb); }
		System.out.println("Movie XCMD: opened " + f.getName() + " as \"" + pb.name + "\" id " + pb.id + (movie.hasVideo() ? " " + movie.width() + "x" + movie.height() : " (no video)"));
		if (st != null && st.curCard != null) TTalk.CallMessage("openMovie", new String[] {pb.name, Integer.toString(pb.id)}, st.curCard);
		return "";
	}

	private static HzImage blank(int w, int h) {
		w = Math.max(1, w); h = Math.max(1, h);
		int[] px = new int[w * h];
		java.util.Arrays.fill(px, 0xFF000000);
		return new HzImage(w, h, px);
	}

	/** The frame of a Movie XCMD window style */
	static int frame(String style) {
		switch (style.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "rect": case "borderless": return CardOverlay.NO_FRAME;
			case "plain": return CardOverlay.LINE;
			case "shadow": return CardOverlay.SHADOW;
			case "dialog": return CardOverlay.DIALOG;
			case "zoom": case "document": return CardOverlay.TITLE_BAR;
			default: return CardOverlay.WINDOID_BAR; // windoid, tallWindoid
		}
	}

	/** The movie's file: as the script names it (a Mac path, a host path), else by its name beside the stack, in its BGMs
	 *  folder, or in HyperCard's search paths (TXcmd.resolveMovieFile) */
	static File find(String supplied, OStack st) {
		if (supplied == null || supplied.isEmpty()) return null;
		String path = TTalk.convertFromMacPath(supplied);
		List<File> candidates = new ArrayList<>();
		File requested = new File(path);
		candidates.add(requested);
		if (supplied.indexOf(':') >= 0 && !requested.isAbsolute()) candidates.add(new File("/" + path));
		String name = requested.getName();
		File folder = st == null || st.file == null ? null : UiHost.get().stackFolderOf(st.file);
		if (folder != null) {
			if (supplied.startsWith(":")) candidates.add(new File(folder, supplied.substring(1).replace(':', '/'))); // a relative Mac path
			candidates.add(new File(folder, name));
			candidates.add(new File(new File(folder, "BGMs"), name));
		}
		for (String global : new String[] {"documents", "stacks"}) for (File f : TTalk.searchPathFolders(global)) candidates.add(new File(f, name));
		for (File c : candidates) {
			if (c.isFile()) return c;
			File unpacked = c.getParentFile() == null ? null : UiHost.get().fileManager68kExistingOrUnarchived(c.getParentFile(), c.getName());
			if (unpacked != null && unpacked.isFile()) return unpacked;
			if (!c.getName().contains(".")) { File mp3 = new File(c.getPath() + ".mp3"); if (mp3.isFile()) return mp3; }
		}
		return null;
	}

	static Playback of(String handle) { synchronized (active) { return handle == null ? null : active.get(handle); } }
	static Playback of(OWindow w) { return w == null ? null : of(w.movieHandle); }

	static void play(Playback pb) {
		if (pb == null) return;
		if (pb.finished || pb.positionMs >= pb.durationMillis()) pb.positionMs = 0;
		pb.finished = false;
		if (!pb.playing) {
			pb.playing = true;
			pb.startMs = now() - pb.positionMs;
			pb.movie.play(pb.positionMs);
		}
		PictureWindows.changed();
	}
	static void pause(Playback pb) {
		if (pb == null) return;
		if (pb.playing) pb.positionMs = pb.currentMillis();
		pb.playing = false;
		pb.movie.stop();
	}
	/** set the currTime (time units): stopped there */
	static void setCurrentTime(Playback pb, long units) {
		if (pb == null) return;
		long ms = Math.max(0, Math.min(Math.round(units * 1000.0 / pb.timeScale()), pb.durationMillis()));
		pb.movie.stop();
		pb.playing = false; pb.finished = false; pb.positionMs = ms;
		show(pb, ms);
	}
	static void step(Playback pb, int frames) {
		if (pb == null) return;
		pause(pb);
		double fps = pb.movie.frameRate() > 0 ? pb.movie.frameRate() : 10;
		setCurrentTime(pb, pb.currentTime() + Math.round(pb.timeScale() / fps) * frames);
	}
	static void setRate(Playback pb, double rate) { if (rate == 0) pause(pb); else play(pb); }
	static void setVolume(Playback pb, int volume) {
		if (pb == null) return;
		pb.volume = Math.max(0, Math.min(256, volume));
		pb.movie.volume(pb.volume);
	}

	/** A movie's property (currTime, duration, …), or null when it is not one of these */
	static String property(Playback pb, String property) {
		if (pb == null) return null;
		switch (property) {
			case "currtime": return Long.toString(pb.currentTime());
			case "duration": case "endtime": return Long.toString(pb.duration());
			case "starttime": return "0";
			case "timescale": return Integer.toString(pb.timeScale());
			case "loop": return Boolean.toString(pb.loop);
			case "loopispalindrome": return "false";
			case "soundvolume": case "volume": return Integer.toString(pb.volume);
			case "movie": return pb.file == null ? "" : pb.file.getAbsolutePath();
			case "windowstyle": return pb.style;
			case "rate": return pb.playing ? "1" : "0";
		}
		return null;
	}

	/** The movie windows in the order they were opened (movie 1, movie 2 …) */
	static ArrayList<OWindow> scriptWindows() {
		ArrayList<OWindow> out = new ArrayList<>();
		for (OWindow w : OWindow.list) if (w.movieHandle != null && of(w.movieHandle) != null) out.add(w);
		return out;
	}
	/** movie "name": the window's name, else the file name or path of its movie */
	static OWindow findScriptWindow(String ref) {
		if (ref == null) return null;
		for (OWindow w : scriptWindows()) if (ref.equalsIgnoreCase(w.name)) return w;
		for (OWindow w : scriptWindows()) {
			Playback pb = of(w);
			if (pb == null || pb.file == null) continue;
			String path = pb.file.getAbsolutePath();
			if (ref.equalsIgnoreCase(pb.file.getName()) || ref.equalsIgnoreCase(path) || TTalk.convertFromMacPath(ref).equalsIgnoreCase(path)) return w;
		}
		return null;
	}

	/** Closes the movie: its sound and window go, then closeMovie */
	static void dispose(Playback pb, boolean message) {
		if (pb == null) return;
		synchronized (active) { if (active.remove(pb.handle) == null) return; }
		pb.playing = false;
		pb.movie.stop();
		pb.movie.close();
		if (pb.pict != null) PictureWindows.remove(pb.pict);
		OWindow.list.remove(pb.window);
		OStack st = HZ.stack();
		if (message && st != null && st.curCard != null) TTalk.CallMessage("closeMovie", new String[] {pb.name, Integer.toString(pb.id)}, st.curCard);
	}
	/** The stack closes: its movies go, without closeMovie */
	static void stackClosing() {
		List<Playback> all;
		synchronized (active) { all = new ArrayList<>(active.values()); }
		for (Playback pb : all) dispose(pb, false);
	}

	/** Whether a movie is playing (the player turns at the frame rate then) */
	static boolean playing() {
		synchronized (active) { for (Playback pb : active.values()) if (pb.playing) return true; }
		return false;
	}

	/** The playing movies move on to now: the frame, the end (a loop starts again, the rest stop there); true when a
	 *  window's picture changed */
	static boolean tick() {
		List<Playback> all;
		synchronized (active) { if (active.isEmpty()) return false; all = new ArrayList<>(active.values()); }
		boolean changed = false;
		long t = now();
		for (Playback pb : all) {
			if (!pb.playing) continue;
			long elapsed = t - pb.startMs, length = pb.durationMillis();
			if (elapsed >= length) {
				if (pb.loop && length > 0) {
					pb.startMs = t; elapsed = 0;
					pb.movie.play(0);
				} else {
					pb.playing = false; pb.finished = true; pb.positionMs = length;
					elapsed = Math.max(0, length - 1);
				}
			}
			changed |= show(pb, elapsed);
		}
		return changed;
	}
	private static boolean show(Playback pb, long ms) {
		if (pb.pict == null) return false;
		HzImage frame = pb.movie.frameAt(ms);
		if (frame == null || frame == pb.pict.image) return false;
		pb.pict.image = frame;
		PictureWindows.changed();
		return true;
	}

	/** A click in the movie: mouseDownInMovie / mouseUpInMovie with the point in the movie and the currTime */
	static void mouse(Playback pb, String message, int x, int y, OCard cd) {
		if (cd == null) return;
		TTalk.CallMessage(message, new String[] {pb.name, Integer.toString(pb.id), x + "," + y, Long.toString(pb.currentTime())}, cd);
	}
}

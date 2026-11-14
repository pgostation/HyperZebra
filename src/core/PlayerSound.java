import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * play in the browser player, as TSound plays it on the desktop: the sound named (a 'snd ' of the stack or a stack in use,
 * decoded once by HzSound and sent to the host), each note at its pitch against the sound's base note and its length at
 * the tempo, one after the other without a gap; play stop; the sound (the playing sound's name, "done" when the queue is
 * empty). The host plays the samples (PlayerHost.soundData, playNote, stopSound: WebAudio in the page).
 */
final class PlayerSound {
	private final PlayerHost host;
	private final Map<String, Integer> ids = new HashMap<>();
	private final Map<Integer, HzSound> sounds = new HashMap<>();
	private int nextId = 1;
	/** the notes queued: when each ends (host nanoTime) and its sound's name */
	private final List<long[]> ends = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private long queueEnd;
	String lastError = "";

	/** Sounds waiting at most (TSound.MAX_QUEUED: a script playing in a loop until a click queued millions) */
	static final int MAX_QUEUED = 500;
	static final double FADE_SECONDS = 0.005;

	PlayerSound(PlayerHost host) { this.host = host; }

	/** One play: a note of a sound (neiro as TTalk's play gives it: [1] the note letter, [2] # or b, [3] the octave,
	 *  [4] the duration letter, [5] dots and triplet); false when the sound cannot be found (the result) */
	boolean play(OStack stack, String name, String[] neiro, int tempo, int volume) {
		lastError = "";
		int id = idOf(stack, name);
		if (id <= 0) { lastError = "Couldn't load sound"; return false; }
		HzSound s = sounds.get(id);
		purge();
		if (ends.size() >= MAX_QUEUED) return true;
		double length = noteSeconds(neiro, tempo);
		double pitch = 1;
		int frames = s.frames();
		boolean rest = neiro[1].equalsIgnoreCase("r"), loop = false;
		if (!(neiro[1].isEmpty() && length == 0.0)) {
			int code = switch (neiro[1].toLowerCase()) { case "d" -> 2; case "e" -> 4; case "f" -> 5; case "g" -> 7; case "a" -> 9; case "b" -> 11; default -> 0; };
			if (neiro[2].equals("#")) code += 1;
			if (neiro[2].equals("b")) code -= 1;
			code += neiro[3].isEmpty() ? 48 : 12 * Integer.parseInt(neiro[3]);
			// c4 is MIDI note 60, played against the sound's base note as the Sound Manager does (TSound)
			pitch = Math.pow(2.0, (code + 12 - s.baseNote) / 12.0);
			if (length != 0.0 || rest) frames = (int) Math.round(length * s.rate * pitch);
			loop = !rest && s.loops();
		}
		double seconds = frames / s.rate / pitch;
		long now = host.nanoTime();
		queueEnd = Math.max(queueEnd, now) + (long) (seconds * 1e9);
		ends.add(new long[]{queueEnd});
		names.add(name);
		host.playNote(rest ? 0 : id, pitch, seconds, loop ? s.loopStart : 0, loop ? s.loopEnd : 0, Math.max(0, volume / 100.0));
		return true;
	}

	/** play stop: the playing note fades, the queued ones are dropped */
	void stop() { ends.clear(); names.clear(); queueEnd = 0; host.stopSound(); }

	/** the sound: the name of the sound playing now, "done" when nothing plays */
	String current() {
		purge();
		return names.isEmpty() ? "done" : names.get(0);
	}

	private void purge() {
		long now = host.nanoTime();
		while (!ends.isEmpty() && ends.get(0)[0] <= now) { ends.remove(0); names.remove(0); }
	}

	/** The id the host knows the sound by (its samples sent the first time); 0 when the stack has no sound of that name */
	private int idOf(OStack stack, String name) {
		if (stack == null || name == null || name.isEmpty()) return 0;
		String key = name.toLowerCase();
		Integer known = ids.get(key);
		if (known != null) return known;
		HzSound s = null;
		List<OStack> places = new ArrayList<>();
		places.add(stack);
		if (stack.usingStacks != null) places.addAll(stack.usingStacks);
		// by name, as GetNamedResource (TSound.soundPath); a number as an ID. Read from the stack that has it
		Rsrc.rsrcClass r = stack.rsrc == null ? null : stack.rsrc.findByNameAll(name, "SND");
		if (r == null && key.matches("-?[0-9]+")) for (OStack st : places) if (st != null && st.rsrc != null && (r = st.rsrc.rsrcIdMap.get("SND" + key)) != null) break;
		if (r != null) for (OStack st : places) {
			if (st == null || st.rsrc == null || st.rsrc.rsrcIdMap.get("SND" + r.id) != r) continue;
			s = HzSound.decode(HzImages.bytes(st, r.filename));
			break;
		}
		if (s == null) return 0;
		s.builtInNote(name);
		int id = nextId++;
		sounds.put(id, s);
		ids.put(key, id);
		host.soundData(id, s.left, s.stereo() ? s.right : null, s.rate);
		return id;
	}

	/**
	 * A note's length in seconds at {@code tempo} quarter notes a minute (TSound.noteSeconds): the duration letter (a quarter
	 * when only a note name is given, 0 = the whole sound for no note at all), dots x1.5 each, a triplet 2/3.
	 */
	static double noteSeconds(String[] neiro, int tempo) {
		double length = 0.0;
		String d = neiro[4];
		if (d.equalsIgnoreCase("w")) length = 240.0 / tempo;
		else if (d.equalsIgnoreCase("h")) length = 120.0 / tempo;
		else if (d.equalsIgnoreCase("q")) length = 60.0 / tempo;
		else if (d.equalsIgnoreCase("e")) length = 30.0 / tempo;
		else if (d.equalsIgnoreCase("s")) length = 15.0 / tempo;
		else if (d.equalsIgnoreCase("t")) length = 7.5 / tempo;
		else if (d.equalsIgnoreCase("x")) length = 3.75 / tempo;
		else if (!neiro[1].isEmpty()) length = 60.0 / tempo;
		String dots = neiro[5].replace("3", "");
		for (int i = 0; i < dots.length() && i < 4; i++) length *= 1.5;
		if (neiro[5].contains("3")) length *= 2.0 / 3.0;
		return length;
	}
}

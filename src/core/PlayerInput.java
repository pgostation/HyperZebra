/**
 * The player's input as a 68K external polls it (P4-3): the keys held now, the presses so far and the keys typed on the
 * card, which an external's EventAvail/GetNextEvent gets as HyperCard's events. PlayerLoop gives it every event it handles
 * (also those that come while a handler or an external runs); the mouse and the modifier keys are the host's, live.
 * Key codes are the page's (DOM keyCode, mostly Java's VK numbers); location is 1 for the numeric keypad.
 */
final class PlayerInput {
	private PlayerInput() {}
	static PlayerHost host;
	/** The keys held: code -> location. */
	private static final java.util.LinkedHashMap<Integer, Integer> held = new java.util.LinkedHashMap<Integer, Integer>();
	/** Presses on the card so far. */
	static int presses;
	/** Gets each key typed on the card as {code, character, modifiers, location} (an external watching the card's keys). */
	static java.util.function.Consumer<int[]> keyWatcher;

	static void reset() { held.clear(); presses = 0; }
	static void note(int[] e) {
		switch (e[0]) {
			case PlayerHost.DOWN: presses++; break;
			case PlayerHost.KEY_DOWN: {
				int code = e[4], location = e.length > 6 ? e[6] & 1 : 0;
				held.put(code, location);
				java.util.function.Consumer<int[]> w = keyWatcher;
				if (w != null) w.accept(new int[] {code, e[5], e[3], location});
				break;
			}
			case PlayerHost.KEY_UP:
				if (e[4] < 0) held.clear(); else held.remove(e[4]); // -1: the page lost the keyboard
				break;
			default: break;
		}
	}
	/** The keys held now as {code, location} pairs. */
	static int[] heldKeys() {
		int[] out = new int[2 * held.size()];
		int i = 0;
		for (java.util.Map.Entry<Integer, Integer> k : held.entrySet()) { out[i++] = k.getKey(); out[i++] = k.getValue(); }
		return out;
	}
	static boolean mouseDown() { PlayerHost h = host; return h != null && h.mouseIsDown(); }
	static int mouseX() { PlayerHost h = host; return h == null ? 0 : h.mouseX(); }
	static int mouseY() { PlayerHost h = host; return h == null ? 0 : h.mouseY(); }
	static int modifiers() { PlayerHost h = host; return h == null ? 0 : h.modifiers(); }
}

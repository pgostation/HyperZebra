import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * What answer and ask do without a screen (the headless core and the browser): the next scripted reply when a run gave
 * some, else the default button (answer: the last button named; ask: OK with the default text). Every dialog is logged,
 * so a run can show what its scripts asked.
 */
final class HeadlessDialogs {
	private HeadlessDialogs() {}

	/** Scripted replies, used in turn: a button name for answer; for ask "OK" (the default text), or "OK|<text>", or "Cancel". */
	static final ArrayDeque<String> replies = new ArrayDeque<>();
	/** The dialogs shown, in order ("answer: <text> [<buttons>] -> <button>"). */
	static final List<String> log = new ArrayList<>();

	static String answer(String text, String b1, String b2, String b3) {
		String button = replies.poll();
		if (button == null) button = b3 != null ? b3 : b2 != null ? b2 : b1;
		log.add("answer: " + text + " [" + join(b1, b2, b3) + "] -> " + button);
		return button;
	}

	/** {the button, the text typed} */
	static String[] ask(String text, String input, String cancel, String ok, boolean hidden) {
		String reply = replies.poll();
		String button = ok, typed = input == null ? "" : input;
		if (reply != null) {
			int bar = reply.indexOf('|');
			button = bar < 0 ? reply : reply.substring(0, bar);
			if (bar >= 0) typed = reply.substring(bar + 1);
		}
		log.add((hidden ? "ask password: " : "ask: ") + text + " [" + typed + "] -> " + button);
		return new String[]{button, typed};
	}

	private static String join(String... buttons) {
		StringBuilder b = new StringBuilder();
		for (String s : buttons) if (s != null) { if (b.length() > 0) b.append(", "); b.append(s); }
		return b.toString();
	}
}

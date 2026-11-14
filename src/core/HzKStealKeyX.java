/**
 * KStealKeyX(keys) in the browser player, by the rules of the desktop's built-in one (TXcmd): one "1" or "0" for each key
 * the parameter names, whether it is held now. "^au" "^ad" "^al" "^ar" are the arrows, "^re" Return, "^sh"/"^sf" Shift,
 * "^op" Option, "^ct" Control, "^cm" Command, "^ca" Caps Lock, "^de" Delete (backspace), "^dl" forward delete, "^es" Escape,
 * "^t0"…"^t9" the keypad's digits; any other character is that key (a letter in either case). The Mosslight RPG Engine
 * sample carries a KStealKeyX with no code (platform "java") and reads its arrow and WASD keys with it.
 *
 * The keys held are the page's (PlayerInput: browser key codes, the keypad's marked), the modifiers the page's modifier bits.
 */
final class HzKStealKeyX {
	private HzKStealKeyX() {}

	static String answer(String keys) {
		int[] held = PlayerInput.heldKeys();
		int mods = PlayerInput.modifiers();
		StringBuilder out = new StringBuilder();
		String p = keys == null ? "" : keys;
		for (int i = 0; i < p.length(); i++) {
			char c = p.charAt(i);
			if (c == '^' && i + 2 < p.length()) {
				String key = p.substring(i + 1, i + 3);
				i += 2;
				int bit = -1;
				switch (key) {
					case "au": bit = held(held, 38) ? 1 : 0; break;
					case "ad": bit = held(held, 40) ? 1 : 0; break;
					case "al": bit = held(held, 37) ? 1 : 0; break;
					case "ar": bit = held(held, 39) ? 1 : 0; break;
					case "re": bit = held(held, 13) ? 1 : 0; break;
					case "sh": case "sf": bit = (mods & PlayerHost.SHIFT) != 0 || held(held, 16) ? 1 : 0; break;
					case "op": bit = (mods & PlayerHost.OPTION) != 0 || held(held, 18) ? 1 : 0; break;
					case "ct": bit = (mods & PlayerHost.CONTROL) != 0 || held(held, 17) ? 1 : 0; break;
					case "cm": bit = (mods & PlayerHost.COMMAND) != 0 || held(held, 91) || held(held, 93) || held(held, 224) ? 1 : 0; break;
					case "ca": bit = (mods & PlayerHost.CAPS_LOCK) != 0 ? 1 : 0; break;
					case "de": bit = held(held, 8) ? 1 : 0; break;
					case "dl": bit = held(held, 46) ? 1 : 0; break;
					case "es": bit = held(held, 27) ? 1 : 0; break;
					default:
						if (key.charAt(0) == 't' && key.charAt(1) >= '0' && key.charAt(1) <= '9') bit = keypadDigit(held, key.charAt(1) - '0') ? 1 : 0;
						break;
				}
				if (bit >= 0) out.append(bit); // an unknown ^xx answers nothing, as the desktop's
			} else if (c >= 32 && c < 128) {
				out.append(held(held, codeOf(Character.toUpperCase(c))) ? '1' : '0');
			}
		}
		return out.toString();
	}

	/** The browser key code of a character's key (letters and digits are their upper-case ASCII codes) */
	static int codeOf(char c) {
		switch (c) {
			case ';': return 186; case '=': return 187; case ',': return 188; case '-': return 189; case '.': return 190;
			case '/': return 191; case '`': return 192; case '[': return 219; case '\\': return 220; case ']': return 221;
			case '\'': return 222; case '+': return 107;
			default: return c;
		}
	}

	private static boolean held(int[] held, int code) {
		for (int i = 0; i < held.length; i += 2) {
			int k = held[i];
			if (k == code) return true;
			if (code == 186 && k == 59 || code == 187 && k == 61 || code == 189 && k == 173) return true; // Firefox's codes
			if (code == 189 && k == 109) return true; // the keypad's minus counts as '-' (the desktop's VK 109)
		}
		return false;
	}

	/** A keypad digit: its own code (96-105), or the digit's code from the keypad */
	private static boolean keypadDigit(int[] held, int digit) {
		for (int i = 0; i < held.length; i += 2) {
			if (held[i] == 96 + digit) return true;
			if (held[i] == '0' + digit && held[i + 1] == 1) return true;
		}
		return false;
	}
}

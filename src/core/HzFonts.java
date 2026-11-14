import java.util.Locale;

/**
 * Classic Mac font names as the browser's fonts (P2): a CSS font-family list per name, the same stand-ins the desktop
 * takes from the host (OObject.hostFamily: Osaka is Hiragino, Times/New York a serif, Courier/Monaco a fixed font...),
 * the name itself first for a computer that has it.
 */
final class HzFonts {
	private HzFonts() {}

	static final String SANS = "Geneva, Helvetica, Arial, sans-serif";
	/** Osaka as the desktop draws it (OObject.hostFamily): a family whose two-byte glyphs all take an em, macOS's own Osaka
	 *  last - it is the proportional face, its full-width space a third of an em (HyperPiece 2 card 93's answers, nine such
	 *  spaces after the question, stayed on the question's line and showed while they should be past the field's edge) */
	static final String JAPANESE = "'Hiragino Kaku Gothic ProN', 'Hiragino Sans', Meiryo, 'MS Gothic', 'Noto Sans JP', 'Noto Sans CJK JP', Osaka, sans-serif";

	/** The family list of a stack font name ("" is Geneva, as a new field's). */
	static String families(String name) {
		if (name == null || name.isEmpty()) return SANS;
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.startsWith("osaka") || lower.contains("ゴシック")) return lower.endsWith("等幅") ? "'Osaka-Mono', " + JAPANESE : JAPANESE;
		if (lower.contains("明朝") || lower.contains("mincho"))
			return "'Hiragino Mincho ProN', 'Hiragino Mincho Pro', 'Yu Mincho', 'MS Mincho', 'Noto Serif CJK JP', serif";
		switch (lower) {
			case "geneva": case "chicago": case "charcoal": return quote(name) + ", " + SANS;
			case "times": case "new york": case "bookman": case "new century schlbk": case "palatino": case "toronto": case "athens":
				return quote(name) + ", 'Times New Roman', Times, Georgia, Palatino, serif";
			case "courier": case "monaco":
				return quote(name) + ", Menlo, Monaco, 'Courier New', Courier, monospace";
			case "helvetica": case "n helvetica narrow": return "Helvetica, Arial, sans-serif";
			case "avant garde": return "'Avant Garde', Avenir, 'Century Gothic', 'Helvetica Neue', Helvetica, sans-serif";
			case "zapf chancery": return "'Zapf Chancery', 'Apple Chancery', Zapfino, cursive";
			default: return quote(name) + ", " + SANS;
		}
	}

	private static final java.util.Set<String> STANDS_IN = java.util.Set.of("geneva", "chicago", "charcoal", "times", "new york", "bookman",
		"new century schlbk", "palatino", "toronto", "athens", "courier", "monaco", "helvetica", "n helvetica narrow", "avant garde", "zapf chancery");
	/** Whether the player shows a font of this name (one of the classic Mac fonts it has a stand-in for, the Japanese ones):
	 *  fontExists (Home's checkHCFont: TantanTarot, MJG1on1 told their fonts were missing) */
	static boolean shows(String name) {
		if (name == null) return false;
		String lower = name.trim().toLowerCase(Locale.ROOT);
		return STANDS_IN.contains(lower) || lower.startsWith("osaka") || lower.contains("ゴシック") || lower.contains("明朝") || lower.contains("mincho");
	}

	/** A CSS font shorthand: style, weight, size in pixels, families. */
	static String css(String name, int size, int style) {
		return ((style & 2) != 0 ? "italic " : "") + ((style & 1) != 0 ? "bold " : "") + Math.max(1, size) + "px " + families(name);
	}

	private static String quote(String s) { return "'" + s.replace("\\", "").replace("'", "") + "'"; }
}

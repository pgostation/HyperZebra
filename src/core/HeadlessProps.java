import java.util.HashMap;
import java.util.Map;

/**
 * The HyperCard properties the desktop keeps in its windows and tools (the paint settings, the user level, the script
 * editor's font): what the headless core and the browser answer for them, and what they remember when a script sets
 * one. The values start as the desktop's do when it starts (PaintTool's fields, PCARD's script font).
 */
final class HeadlessProps {
	private HeadlessProps() {}
	private static final Map<String, String> values = new HashMap<>();
	static {
		String[] defaults = {
			"scripttextfont", "", "scripttextsize", "12", "systemlang", "English",
			"editbkgnd", "false", "filled", "false", "centered", "false", "multiple", "false", "multispace", "1",
			"polysides", "4", "gradient", "off", "filltexture", "none", "gradientangle", "0", "filltolerance", "0",
			"brushsize", "3", "brushhardness", "100", "smudgestrength", "50", "stampsource", "", "brushopacity", "100",
			"brushspacing", "10", "brushstabilization", "0", "brushpressure", "size", "penpressure", "",
			"screenfilter", "", "screenoverlay", "", "zoom", "100", "zoomorigin", "0,0", "powerkeys", "false",
			"gridsize", "8", "paintlayer", "1", "paintlayercount", "1", "grid", "false", "linesize", "1",
			"layername", "", "layeropacity", "100", "layerblend", "Normal", "layervisible", "true", "layeralphalock", "false",
			"layerclip", "false", "symmetry", "none", "symmetryaxes", "6", "symmetrycenter", "", "pattern", "12",
			"brushcolor", "0,0,0", "pencolor", "0,0,0",
			"brushbackcolor", "65280,65280,65280", "penbackcolor", "65280,65280,65280", "messagewatcher", "false",
			"variablewatcher", "false", "debugger", "false", "textfont", "", "textsize", "12", "textstyle", "plain",
			// functions of the screen, the mouse, the keyboard, sound and speech
			"voices", "", "speech", "done", "mouseclick", "false", "mouse", "up", "mouseh", "0", "mousev", "0",
			"mouseloc", "0,0", "clickh", "0", "clickv", "0", "clickloc", "0,0", "clickline", "", "clickchunk", "",
			"clicktext", "", "commandkey", "up", "cmdkey", "up", "optionkey", "up", "shiftkey", "up", "controlkey", "up",
			"clipboard", "", "fontexists", "false", "screenrect", "0,0,640,480", "sound", "done",
		};
		for (int i = 0; i < defaults.length; i += 2) values.put(defaults[i], defaults[i + 1]);
	}

	/** The systemLang of a language (ToolsUI's International.englishName: "Japanese", "English"): the catalogs' languages by
	 *  name here, as the browser's Locale has no names (TeaVM has no Locale.forLanguageTag) */
	static String languageName(String tag) {
		String lang = tag == null ? "en" : tag.split("-")[0].toLowerCase(java.util.Locale.ROOT);
		switch (lang) {
			case "en": return "English"; case "ja": return "Japanese"; case "de": return "German"; case "fr": return "French";
			case "es": return "Spanish"; case "it": return "Italian"; case "ko": return "Korean"; case "pt": return "Portuguese";
			case "zh": return "Chinese";
			default: return tag; // (no Locale names in the browser)
		}
	}

	/** The property's value (name in lower case), or null when it is not one of these. */
	static String get(String name) {
		if (name.equals("userlevel")) return Integer.toString(HZ.userLevel);
		if (name.equals("systemlang") && HZ.intl() != null) return languageName(HZ.intl().tag());
		if (name.equals("brushstabilizer")) name = "brushstabilization";
		name = patternAlias(name);
		return values.get(name);
	}

	/** Remembers a value (any name: the headless core accepts what the desktop's windows and tools take). */
	static boolean set(String name, String value) {
		if (name.equals("userlevel")) {
			try { HZ.userLevel = Math.max(1, Math.min(5, (int) Double.parseDouble(value.trim()))); } catch (NumberFormatException e) { return false; }
			return true;
		}
		if (name.equals("brushstabilizer")) name = "brushstabilization";
		name = patternAlias(name);
		// set the pattern to empty leaves it as it is (the desktop's ToolsUI, HC J-2.3 measured)
		if (name.equals("pattern") && (value == null || value.trim().isEmpty())) return true;
		values.put(name, value);
		return true;
	}

	/** brushPattern/penPattern/fillPattern are the pattern (the desktop's one pattern palette). It starts at 12, black, as
	 *  HyperCard's and the desktop's (GToolBar's PatButton 11): at 1 (white) DoodlePaint's Brush button drew nothing */
	private static String patternAlias(String name) {
		return name.equals("brushpattern") || name.equals("penpattern") || name.equals("fillpattern") ? "pattern" : name;
	}
}

/**
 * Button Info and Field Info in the player (the desktop's AuthDialog): opened by a double click with the button or field tool
 * (HzAuthTool) or from the Objects menu. The page draws the window from a description (posted as "partInfo") and sends each
 * change as it is made (PlayerHost.PART_INFO), which goes into the part at once - as the desktop's info windows apply live,
 * with one Close button. The description, a line each:
 * <pre>
 *   T&lt;tab&gt;title                     W&lt;tab&gt;part token (sent back with each change)
 *   L&lt;tab&gt;text                      a line to read (the part's ID, number)
 *   E&lt;tab&gt;property&lt;tab&gt;label&lt;tab&gt;value                 a text box
 *   P&lt;tab&gt;property&lt;tab&gt;label&lt;tab&gt;value&lt;tab&gt;value|label…  a pop-up menu
 *   C&lt;tab&gt;property&lt;tab&gt;label&lt;tab&gt;1/0                   a check box
 *   B&lt;tab&gt;label                     the Close button
 * </pre>
 * A change comes back as "token\nproperty\tvalue" and is set as HyperTalk's set would (TUtil.SetProperty). Not here: the
 * colours, Font…, Icon…, Effect…, Script… and Content… (the player has none of those editors).
 */
final class PartInfo {
	private PartInfo() {}

	private static final String[][] BUTTON_STYLES = {{"standard", "Standard"}, {"transparent", "Transparent"}, {"opaque", "Opaque"},
		{"rectangle", "Rectangle"}, {"shadow", "Shadow"}, {"roundrect", "RoundRect"}, {"default", "Default"}, {"oval", "Oval"},
		{"popup", "Popup"}, {"checkbox", "CheckBox"}, {"radiobutton", "Radio"}};
	private static final String[][] FIELD_STYLES = {{"transparent", "Transparent"}, {"opaque", "Opaque"}, {"rectangle", "Rectangle"},
		{"shadow", "Shadow"}, {"scrolling", "Scroll"}};
	private static final String[][] BUTTON_CHECKS = {{"showName", "Show Name"}, {"enabled", "Enabled"}, {"visible", "Visible"},
		{"autoHilite", "Auto Hilite"}};
	private static final String[][] FIELD_CHECKS = {{"lockText", "Locked text"}, {"visible", "Visible"}, {"dontWrap", "Don't wrap"},
		{"autoSelect", "Auto select"}, {"multipleLines", "Multiple lines"}, {"wideMargins", "Wide margins"},
		{"fixedLineHeight", "Fixed line height"}, {"showLines", "Show lines"}, {"autoTab", "Auto tab"}, {"dontSearch", "Don't search"}};

	private static String t(String english) {
		International intl = HZ.intl();
		return intl == null ? english : intl.getDialogText(english);
	}
	private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '); }

	/** The part's token: its layer ("cd"/"bg"), the layer's id, its kind and id */
	static String token(OObject part) {
		OCardBase owner = (OCardBase) part.parent;
		return ("background".equals(owner.objectType) ? "bg" : "cd") + " " + owner.id + " " + (part instanceof OButton ? "btn" : "fld") + " " + part.id;
	}
	/** The part a token names, or null when it is gone */
	static OObject find(String token) {
		String[] w = token.trim().split(" ");
		OStack st = HZ.stack();
		if (w.length != 4 || st == null) return null;
		int layerId, id;
		try { layerId = Integer.parseInt(w[1]); id = Integer.parseInt(w[3]); } catch (NumberFormatException e) { return null; }
		OCardBase owner = null;
		if (w[0].equals("bg")) { for (OBackground bg : st.bgCacheList) if (bg.id == layerId) owner = bg; }
		else { for (OCard cd : st.cdCacheList) if (cd.id == layerId) owner = cd; }
		if (st.curCard != null && st.curCard.id == layerId && w[0].equals("cd")) owner = st.curCard;
		if (st.curCard != null && st.curCard.bg != null && st.curCard.bg.id == layerId && w[0].equals("bg")) owner = st.curCard.bg;
		if (owner == null || owner.partsList == null) return null;
		for (OObject p : owner.partsList) if (p.id == id && (w[2].equals("btn") ? p instanceof OButton : p instanceof OField)) return p;
		return null;
	}

	private static String get(OObject part, String prop) {
		try { String v = TUtil.getProperty(prop, part, part, new MemoryData()); return v == null ? "" : v; }
		catch (xTalkException | RuntimeException e) { return ""; }
	}
	private static boolean on(OObject part, String prop) { return "true".equalsIgnoreCase(get(part, prop).trim()); }

	/** The window's description for the part */
	static String describe(OObject part) {
		boolean button = part instanceof OButton;
		OCardBase owner = (OCardBase) part.parent;
		boolean bg = "background".equals(owner.objectType);
		StringBuilder sb = new StringBuilder();
		sb.append("T\t").append(t(button ? "Button Info" : "Field Info")).append('\n');
		sb.append("W\t").append(token(part)).append('\n');
		sb.append("E\tname\t").append(t(button ? "Button Name:" : "Field Name:")).append('\t').append(clean(part.name)).append('\n');
		String kind = t(button ? (bg ? "Background button " : "Card button ") : (bg ? "Background field " : "Card field "));
		int number = button ? owner.GetNumberof((OButton) part) : owner.GetNumberof((OField) part);
		sb.append("L\t").append(clean(kind + t("ID: ") + part.id)).append('\n');
		sb.append("L\t").append(clean(kind + t("Number: ") + number)).append('\n');
		sb.append("L\t").append(clean(kind + t("Part Number: ") + owner.GetNumberofParts(part))).append('\n');
		String style = get(part, "style").trim().toLowerCase(java.util.Locale.ROOT);
		if (style.equals("radio")) style = "radiobutton";
		sb.append("P\tstyle\t").append(t("Style:")).append('\t').append(style);
		for (String[] s : button ? BUTTON_STYLES : FIELD_STYLES) sb.append('\t').append(s[0]).append('|').append(clean(t(s[1])));
		sb.append('\n');
		if (button) {
			sb.append("P\tfamily\t").append(t("Family:")).append('\t').append(((OButton) part).group);
			sb.append("\t0|").append(clean(t("None")));
			for (int i = 1; i <= 15; i++) sb.append('\t').append(i).append('|').append(i);
			sb.append('\n');
		}
		for (String[] c : button ? BUTTON_CHECKS : FIELD_CHECKS) sb.append("C\t").append(c[0]).append('\t').append(clean(t(c[1]))).append('\t').append(on(part, c[0]) ? '1' : '0').append('\n');
		if (bg) {
			String[] c = button ? new String[] {"sharedHilite", "Shared Hilite"} : new String[] {"sharedText", "Shared text"};
			sb.append("C\t").append(c[0]).append('\t').append(clean(t(c[1]))).append('\t').append(on(part, c[0]) ? '1' : '0').append('\n');
		}
		sb.append("B\t").append(t("Close")).append('\n');
		return sb.toString();
	}

	/** Shows the window for the part (posts its description) */
	static void open(PlayerHost host, OObject part) {
		if (host == null || !(part instanceof OButton || part instanceof OField) || !(part.parent instanceof OCardBase)) return;
		host.post("partInfo", describe(part));
	}

	/** A change from the window: "token\nproperty\tvalue", set into the part now; the window is told the part as it is then */
	static void apply(PlayerHost host, String text) {
		int nl = text.indexOf('\n');
		if (nl < 0) return;
		OObject part = find(text.substring(0, nl));
		String[] pv = text.substring(nl + 1).split("\t", 2);
		if (part == null || pv.length != 2) return;
		String prop = pv[0].trim();
		if (!java.util.Set.of("name", "style", "family", "showName", "enabled", "visible", "autoHilite", "sharedHilite", "lockText",
			"dontWrap", "autoSelect", "multipleLines", "wideMargins", "fixedLineHeight", "showLines", "autoTab", "dontSearch", "sharedText").contains(prop)) return;
		try { TUtil.SetProperty(part, prop, pv[1]); }
		catch (xTalkException | RuntimeException e) { System.out.println("Could not set the " + prop + ": " + e.getMessage()); }
		if (part.parent instanceof OCardBase owner) owner.changed = true;
		UiHost.get().repaintCardPane();
		// (a style can change what the others show: the open window's values as the part is now; a closed one stays closed)
		host.post("partInfoUpdate", describe(part));
	}
}

import java.util.ArrayList;
import java.util.List;

/**
 * The standard menu bars of the headless core and the browser: the desktop's real menus at the start (the card window's
 * and the paint tools'), written by tools/wasm-core/MenuDump.java into {@link StandardMenuTable}. The table keeps the
 * English texts; the UI catalog (HZ.intl) translates them as the desktop's GMenu does, which MenuDump checks for each
 * language.
 */
final class StandardMenus {
	private StandardMenus() {}

	static PlainMenus.Bar bar(PlainMenus f, boolean paint) {
		PlainMenus.Bar bar = new PlainMenus.Bar();
		String want = paint ? "#bar paint" : "#bar card";
		boolean in = false;
		List<PlainMenus.PMenu> open = new ArrayList<>(); // the menu at each depth
		String top = ""; // the English title of the menu the line is in
		for (String line : StandardMenuTable.TABLE.split("\n")) {
			if (line.startsWith("#bar")) { in = line.equals(want); continue; }
			if (!in || line.isEmpty()) continue;
			int depth = 0;
			while (depth < line.length() && line.charAt(depth) == '\t') depth++;
			String rest = line.substring(depth);
			String flags = "";
			int tab = rest.indexOf('\t');
			if (tab >= 0) { flags = rest.substring(tab + 1); rest = rest.substring(0, tab); }
			char kind = rest.charAt(0);
			String text = rest.length() > 2 ? rest.substring(2) : "";
			if (kind == 'M' && depth == 0) top = text;
			// what the player does that the desktop's menus have off at the start (Save a Copy…: PlayerSaving.saveACopy; Undo
			// and Redo Paint: HzPaint's undo), and what it cannot do (off)
			if (text.equals("Save a Copy…") || text.equals("Undo Paint") || text.equals("Redo Paint")) flags = flags.replace("d", "");
			if (kind != 'S' && !(kind == 'M' && depth == 0) && !playerDoes(top, text) && flags.indexOf('d') < 0) flags += "d";
			boolean checked = kind == 'C' && text.startsWith("+");
			if (checked) text = text.substring(1);
			text = translate(text);
			MComponent part;
			if (kind == 'M') {
				PlainMenus.PMenu m = new PlainMenus.PMenu(text);
				m.plain = flags.indexOf('x') < 0;
				part = m;
				while (open.size() > depth) open.remove(open.size() - 1);
				open.add(m);
			}
			else if (kind == 'C') part = f.checkItem(text, checked);
			else if (kind == 'I') part = f.item(text);
			else part = new PlainMenus.PSeparator();
			if (part instanceof MMenuItem) {
				MMenuItem mi = (MMenuItem) part;
				if (flags.indexOf('d') >= 0) mi.setEnabled(false);
				int k = flags.indexOf('k');
				if (k >= 0) {
					int e = k + 1;
					while (e < flags.length() && Character.isDigit(flags.charAt(e))) e++;
					mi.setAccelerator(new MKeyStroke(Integer.parseInt(flags.substring(k + 1, e)), null));
				}
			}
			if (flags.indexOf('h') >= 0) part.setVisible(false);
			if (depth == 0) { if (part instanceof MMenu) bar.add((MMenu) part); continue; }
			PlainMenus.PMenu parent = open.size() >= depth ? open.get(depth - 1) : null;
			if (parent == null) continue;
			if (part instanceof MMenuItem) parent.add((MMenuItem) part);
			else parent.parts.add(part);
		}
		return bar;
	}

	/** what the player does of each menu (HeadlessMenus.command; the Tool menu's tools: HzPaint, HzAuthTool; the Paint menu's
	 *  commands: HzPaint.menu; the Options menu's settings HzPaint reads); the rest is greyed (the desktop's editors and
	 *  windows: Icon…, Resource…, the info windows of cards and stacks, the launcher, printing, submenus…) */
	private static final java.util.Set<String> FILE = java.util.Set.of("Save a Copy…", "Close Stack");
	private static final java.util.Set<String> EDIT = java.util.Set.of("Undo", "Redo", "Cut", "Copy", "Paste", "Delete", "New Card",
		"Delete Card", "Copy Card", "Background", "Undo Paint", "Redo Paint", "Cut Picture", "Copy Picture", "Paste Picture", "Clear Selection");
	private static final java.util.Set<String> GO = java.util.Set.of("Back", "Home", "First", "Prev", "Next", "Last", "Find…", "Message");
	private static final java.util.Set<String> OBJECTS = java.util.Set.of("Button Info…", "Field Info…", "Bring Closer", "Send Farther",
		"New Button", "New Field");
	private static final java.util.Set<String> TOOLS = java.util.Set.of("Browse", "Button", "Field", "Select", "Lasso", "Pencil",
		"Brush", "Eraser", "Line", "SprayCan", "Rect", "RoundRect", "PaintBucket", "Oval", "Curve", "Type", "Polygon", "FreePolygon");
	private static final java.util.Set<String> PAINT = java.util.Set.of("Select", "Select All", "Fill", "Invert", "Darken", "Lighten",
		"Trace Edges", "Rotate Left", "Rotate Right", "Flip Horizontal", "Flip Vertical", "Opaque", "Transparent", "Keep", "Revert");
	private static final java.util.Set<String> OPTIONS = java.util.Set.of("Grid Snap", "Draw Centered", "Draw Filled");
	static boolean playerDoes(String menu, String item) {
		switch (menu) {
			case "File": return FILE.contains(item);
			case "Edit": return EDIT.contains(item);
			case "Go": return GO.contains(item);
			case "Objects": return OBJECTS.contains(item);
			case "Tool": return TOOLS.contains(item);
			case "Paint": return PAINT.contains(item);
			case "Options": return OPTIONS.contains(item);
			default: return true;
		}
	}

	private static String translate(String english) {
		International intl = HZ.intl();
		return intl == null || english.isEmpty() ? english : intl.getText(english);
	}
}

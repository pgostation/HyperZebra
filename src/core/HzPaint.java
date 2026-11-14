/**
 * HyperCard's paint tools in the browser player (PaintTool and GPaintMenu on the desktop, which are Swing): what scripts paint
 * with "choose … tool" and "drag from … to …" / "click at", what the user paints with the mouse while a paint tool is chosen,
 * and the Paint menu's commands (doMenu "Select All", "Copy Picture", …).
 *
 * The picture being painted is the card's (or with editBkgnd the background's) as a card-sized ARGB buffer (a Surface): the
 * card's picture keeps its transparency (the eraser makes pixels transparent there, as HyperCard's card layer), the
 * background's is opaque (the eraser makes it white). The card renderer shows the surface in place of the picture file
 * (HzImages.picture), with the floating selection over it. Shapes follow the desktop's drawing (Java2D with a 1-pixel
 * stroke on the rectangle's edge: a rectangle from 10,10 to 20,20 covers 10..20, its inside 10..19 filled with the pattern):
 * rectangle, round rectangle (corners 16), oval, regular polygon (the polySides), line (lineSize, square pen; with optionKey
 * lines and edges in the pattern), curve
 * (freehand line), pencil (black, white where it starts on black), brush (the 32 classic brush shapes, 8 until one is set;
 * with commandKey an eraser of its shape), eraser (16 x 16), spray, bucket (the area of the colour under the point, the
 * pattern in it), select and lasso (a floating selection: moved by a drag from inside it, opaque or transparent, the lasso
 * and the select tool with optionKey tighten it to the painted pixels) and text (click at a point, then typed text in the
 * textFont, textSize and textStyle). Patterns are the stack's (PAT_n files) or HyperCard's 40.
 *
 * Painted pictures are kept: PlayerSaving asks flush before it writes the stack, the surfaces with changes are encoded as
 * PNG (HzPng), the card or background gets its BMAP_<id>.png when it had no picture of its own, and the files go into the
 * stack's files (HeadlessStacks.writeFile) and to the page with the stack (PlayerHost.saveStack).
 */
final class HzPaint {
	private HzPaint() {}

	// ---------------------------------------------------------------- tools

	/** The tool chosen, as a short name ("browse", "rect", "select", …), from "the tool" HeadlessProps keeps */
	static String tool() {
		String t = HeadlessProps.get("tool");
		if (t == null) return "browse";
		t = t.trim().toLowerCase(java.util.Locale.ROOT);
		if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) t = t.substring(1, t.length() - 1).trim();
		if (t.startsWith("the ")) t = t.substring(4).trim();
		if (t.startsWith("tool ")) {
			String n = t.substring(5).trim();
			String[] byNumber = {"browse", "button", "field", "select", "lasso", "pencil", "brush", "eraser", "line", "spray", "rect",
				"roundrect", "bucket", "oval", "curve", "text", "regpoly", "poly"};
			try { int i = (int) Double.parseDouble(n); return i >= 1 && i <= byNumber.length ? byNumber[i - 1] : "browse"; }
			catch (NumberFormatException e) { return "browse"; }
		}
		if (t.endsWith(" tool")) t = t.substring(0, t.length() - 5).trim();
		switch (t) {
			case "btn": return "button";
			case "fld": return "field";
			case "spray can": return "spray";
			case "rectangle": return "rect";
			case "round rect": case "round rectangle": return "roundrect";
			case "paint bucket": return "bucket";
			case "type": return "text";
			case "reg poly": case "regular polygon": case "regular poly": return "regpoly";
			case "polygon": return "poly";
			case "magic wand": case "magicwand": case "wand": return "lasso";
			default: return t;
		}
	}
	private static final java.util.Set<String> PAINT_TOOLS = java.util.Set.of("select", "lasso", "pencil", "brush", "eraser", "line",
		"spray", "rect", "roundrect", "bucket", "oval", "curve", "text", "regpoly", "poly");
	/** Whether a paint tool is chosen */
	static boolean active() { return PAINT_TOOLS.contains(tool()); }
	/** "the tool" in HyperCard's words (ToolsUI.toolFunction's names) */
	static String toolName() {
		switch (tool()) {
			case "rect": return "rectangle tool";
			case "roundrect": return "round rect tool";
			case "regpoly": return "regular polygon tool";
			case "poly": return "polygon tool";
			case "select": return selectWentLasso ? "lasso tool" : "select tool";
			default: return tool() + " tool";
		}
	}
	/** a select tool drag with optionKey: "the tool" says lasso tool until another tool is chosen (HyperCard J-2.3) */
	static boolean selectWentLasso;
	/** the tool in use before the Paint menu's Select or Select All chose the select tool: it comes back when that selection
	 *  ends (Cut, Clear, a click off it, which paints nothing), as HyperCard J-2.3 and the desktop's PaintTool.toolBeforeSelect;
	 *  null when there is none. DoodlePaint's rubber band (drag, Select, Clear Picture while the mouse is down) drew its next
	 *  shape with the select tool without it */
	static String toolBeforeSelect;
	private static void rememberToolBeforeSelect() {
		String t = tool();
		if (toolBeforeSelect == null && !t.equals("select") && !t.equals("lasso")) toolBeforeSelect = HeadlessProps.get("tool");
	}
	private static void backToToolBeforeSelect() {
		String back = toolBeforeSelect;
		toolBeforeSelect = null;
		if (back != null) { selectWentLasso = false; HeadlessProps.set("tool", back); }
	}

	/** Another tool was chosen (choose …): the selection and the text being typed go into the picture */
	static void toolChanged() {
		selectWentLasso = false;
		toolBeforeSelect = null;
		lastBounds = null; // (Select after a tool was chosen closes in on all of the picture)
		polyPoints = null;
		commit();
	}

	// ---------------------------------------------------------------- the pictures being painted

	static final class Surface {
		final OCardBase owner;
		final HzImage img;
		final boolean opaque;
		boolean dirty;
		int[] undo;
		/** the selection there was before the step (Undo brings it back) */
		Floating undoFloating;
		HzImage shown;
		int shownVersion = -1, version;
		Surface(OCardBase owner, HzImage img, boolean opaque) { this.owner = owner; this.img = img; this.opaque = opaque; }
		void touched() { version++; dirty = true; img.repainted(); img.changed(); shown = null; owner.layerVersion++; changedPicture(); }
		int blank() { return opaque ? WHITE : 0; }
	}
	static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
	private static final java.util.WeakHashMap<OCardBase, Surface> surfaces = new java.util.WeakHashMap<>();

	/** The card or background whose picture paint goes to: the background with editBkgnd */
	static OCardBase target() {
		OStack st = HZ.stack();
		if (st == null || st.curCard == null) return null;
		boolean bg = HZ.editBackground || "true".equalsIgnoreCase(String.valueOf(HeadlessProps.get("editbkgnd")).trim());
		return bg && st.curCard.bg != null ? st.curCard.bg : st.curCard;
	}

	/** The surface of a card or background, made from its picture the first time */
	static Surface surface(OCardBase cb) {
		if (cb == null || cb.stack == null) return null;
		Surface s;
		synchronized (surfaces) { s = surfaces.get(cb); }
		if (s != null) return s;
		int w = Math.max(1, cb.stack.width), h = Math.max(1, cb.stack.height);
		boolean opaque = cb instanceof OBackground;
		HzImage img = new HzImage(w, h);
		java.util.Arrays.fill(img.argb, opaque ? WHITE : 0);
		HzImage pict = HzImages.pictureFile(cb);
		if (pict != null) {
			for (int y = 0; y < Math.min(h, pict.height); y++)
				for (int x = 0; x < Math.min(w, pict.width); x++) {
					int c = pict.argb[y * pict.width + x];
					img.argb[y * w + x] = opaque ? over(WHITE, c) : c;
				}
		}
		s = new Surface(cb, img, opaque);
		synchronized (surfaces) { surfaces.put(cb, s); }
		return s;
	}
	/** The picture as the card shows it now, when it is being painted (with the selection over it); else null */
	static HzImage shown(OCardBase cb) {
		Surface s;
		synchronized (surfaces) { s = surfaces.get(cb); }
		if (s == null) return null;
		Floating f = floating;
		boolean typing = text != null && text.on == s;
		if ((f == null || f.on != s) && !typing) return s.img;
		if (s.shown != null && s.shownVersion == s.version) return s.shown;
		HzImage out = new HzImage(s.img.width, s.img.height, s.img.argb.clone());
		if (f != null && f.on == s) f.stampOnto(out, s.opaque);
		s.shown = out; s.shownVersion = s.version;
		return out;
	}
	private static void changedPicture() { PlayerLoop p = PlayerLoop.current; if (p != null) p.markDirty(); }

	/** A copy of the picture before the step, for Undo */
	private static void snapshot(Surface s) {
		if (s == null) return;
		s.undo = s.img.argb.clone();
		s.undoFloating = floating != null && floating.on == s ? floating.copy() : null;
	}

	// ---------------------------------------------------------------- patterns, pen

	static final long[] PATTERNS = {
		0x0000000000000000L, 0x8000000008000000L, 0x8800220088002200L, 0x8888222288882222L, 0x88AA22AA88AA22AAL, 0xCCAA33AACCAA33AAL,
		0xEEAABBAAEEAABBAAL, 0xEEBBBBEEEEBBBBEEL, 0xFFBBFFEEFFBBFFEEL, 0xFFBBFFFFFFBBFFFFL, 0x8010022001084004L, 0xFFFFFFFFFFFFFFFFL,
		0x8822882288228822L, 0x1122448811224488L, 0xC4800C6843023026L, 0xB130031BD8C00C8DL, 0xAA00AA00AA00AA00L, 0x8822552288225522L,
		0x8855225588552255L, 0x77DD77DD77DD77DDL, 0x0000000000000000L, 0xAA55AA55AA55AA55L, 0x038448300C020101L, 0x8244394482010101L,
		0x8814224188412214L, 0x8080413E080814E3L, 0x22048C7422179810L, 0xBE808808EB088880L, 0x25C8328964244C92L, 0xA29C41BE2AC914EBL,
		0x40A00000040A0000L, 0x8040200002040800L, 0xAA00800088008000L, 0xFF80808080808080L, 0x081C22C180010204L, 0xFF808080FF080808L,
		0xF87422478F172271L, 0xBF00BFBFB0B0B0B0L, 0xFF7FBE5DA2418000L, 0xFAF5FAF5A050A050L};

	/** the pattern now: the stack's PAT_n picture, else HyperCard's (n from 1, 12 = black) */
	private static int patternNumber() { return Math.max(1, Math.min(40, intProp("pattern", 12))); }
	private static HzImage patternImage(OStack st, int n) {
		if (st == null || st.Pattern == null || n < 1 || n > st.Pattern.length) return null;
		String name = st.Pattern[n - 1];
		if (name == null || name.isEmpty()) return null;
		HzImage p = HzImages.file(st, name);
		return p != null && p.width > 0 && p.height > 0 ? p : null;
	}
	/** The pattern's colour at a card pixel (patterns are aligned to the card's top left) */
	static int patternAt(OStack st, int n, int x, int y) {
		HzImage p = patternImage(st, n);
		if (p != null) {
			int c = p.argb[Math.floorMod(y, p.height) * p.width + Math.floorMod(x, p.width)];
			return (c >>> 24) < 128 ? WHITE : 0xFF000000 | c;
		}
		long bits = PATTERNS[n - 1];
		int row = (int) (bits >>> (8 * (7 - Math.floorMod(y, 8)))) & 0xFF;
		return (row & (0x80 >> Math.floorMod(x, 8))) != 0 ? BLACK : WHITE;
	}
	private static int lineSize() { return Math.max(1, Math.min(64, intProp("linesize", 1))); }
	private static boolean filled() { return "true".equalsIgnoreCase(prop("filled")); }
	private static boolean centered() { return "true".equalsIgnoreCase(prop("centered")); }
	private static boolean grid() { return "true".equalsIgnoreCase(prop("grid")); }
	private static String prop(String name) { String v = HeadlessProps.get(name); return v == null ? "" : v.trim(); }
	private static int intProp(String name, int dflt) {
		String v = prop(name);
		if (v.isEmpty()) return dflt;
		try { return (int) Math.round(Double.parseDouble(v)); } catch (NumberFormatException e) { return dflt; }
	}

	/** c over the colour under it (alpha) */
	static int over(int under, int c) {
		int a = c >>> 24;
		if (a == 255) return c;
		if (a == 0) return under;
		int ua = under >>> 24, oa = a + ua * (255 - a) / 255;
		if (oa == 0) return 0;
		int r = (((c >> 16) & 255) * a + ((under >> 16) & 255) * ua * (255 - a) / 255) / oa;
		int g = (((c >> 8) & 255) * a + ((under >> 8) & 255) * ua * (255 - a) / 255) / oa;
		int b = ((c & 255) * a + (under & 255) * ua * (255 - a) / 255) / oa;
		return oa << 24 | r << 16 | g << 8 | b;
	}
	private static boolean dark(int c) {
		if ((c >>> 24) < 128) return false;
		return ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255) < 384;
	}

	// ---------------------------------------------------------------- drawing on a surface

	private static Surface drawing;
	private static int minX, minY, maxX, maxY;
	private static void begin(Surface s) { drawing = s; minX = minY = Integer.MAX_VALUE; maxX = maxY = Integer.MIN_VALUE; }
	private static void plot(int x, int y, int c) {
		Surface s = drawing;
		if (s == null || x < 0 || y < 0 || x >= s.img.width || y >= s.img.height) return;
		s.img.argb[y * s.img.width + x] = c;
		if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y;
	}
	private static void plotPattern(int x, int y) { if (drawing != null) plot(x, y, patternAt(drawing.owner.stack, patternNumber(), x, y)); }
	private static void erase(int x, int y) { if (drawing != null) plot(x, y, drawing.blank()); }
	private static void end() {
		Surface s = drawing;
		drawing = null;
		if (s == null) return;
		if (maxX >= minX) lastBounds = new int[] {minX, minY, maxX + 1, maxY + 1};
		s.touched();
	}
	/** what was painted last since a tool was chosen: doMenu "Select" selects it (HC 2.4: of two rectangles only the second) */
	private static int[] lastBounds;

	/** the pen: a square lineSize wide centred on the point (the desktop's stroke); with optionKey the lines and edges are
	 *  drawn in the pattern (HyperCard: an Option-drawn line is the pattern's, a filled shape then has no black edge) */
	private static void pen(int x, int y, int size, int c) {
		int lo = -(size / 2), hi = lo + size;
		for (int dy = lo; dy < hi; dy++) for (int dx = lo; dx < hi; dx++) {
			if (patternPen && c == BLACK) plotPattern(x + dx, y + dy); else plot(x + dx, y + dy, c);
		}
	}
	/** the edges and lines go in the pattern (a shape tool dragged with optionKey) */
	private static boolean patternPen;
	private interface Dot { void at(int x, int y); }
	/** Bresenham from a to b, both ends included */
	private static void line(int x0, int y0, int x1, int y1, Dot d) {
		int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
		while (true) {
			d.at(x0, y0);
			if (x0 == x1 && y0 == y1) break;
			int e2 = 2 * err;
			if (e2 >= dy) { err += dy; x0 += sx; }
			if (e2 <= dx) { err += dx; y0 += sy; }
		}
	}

	/** HyperCard's brush line: the shape at every pixel of the major axis, the other by a 16.16 slope cut towards zero
	 *  (the desktop's PaintTool.classicLine / lineStep, measured on HyperCard J-2.3); the start was put at the press */
	private static void brushLine(int x0, int y0, int x1, int y1) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 1; i <= steps; i++) brushAt(x0 + lineStep(x1 - x0, i, steps), y0 + lineStep(y1 - y0, i, steps));
	}
	private static int lineStep(int d, int i, int steps) { long slope = ((long) d << 16) / steps; return (int) ((0x8000L + i * slope) >> 16); }

	/** A shape: its inside (filled with the pattern, pixel centres in it: the desktop's fill) and its edge, traced with the
	 *  pen (lineSize, black) through the pixels it passes (the desktop's stroke on the edge: a rectangle from 10,10 to 20,20
	 *  has its edge on the pixels 10 and 20, its inside 10..19) */
	private interface Inside { boolean at(double px, double py); }
	private static void fillInside(int l, int t, int r, int b, Inside in) {
		for (int y = t; y <= b; y++) for (int x = l; x <= r; x++) if (in.at(x + 0.5, y + 0.5)) plotPattern(x, y);
	}
	/** the edge as points around it, joined by lines of the pen */
	private static void trace(int[] xs, int[] ys, boolean closed) {
		int n = lineSize();
		for (int i = 0; i + 1 < xs.length || closed && i < xs.length; i++) {
			int j = (i + 1) % xs.length;
			line(xs[i], ys[i], xs[j], ys[j], (px, py) -> pen(px, py, n, BLACK));
			if (!closed && j == 0) break;
		}
		if (xs.length == 1) pen(xs[0], ys[0], n, BLACK);
	}
	private static void rect(int l, int t, int r, int b, boolean fill) {
		if (fill) fillInside(l, t, r, b, (px, py) -> px > l && px < r && py > t && py < b);
		trace(new int[] {l, r, r, l}, new int[] {t, t, b, b}, true);
	}
	/** the points of an arc round cx,cy (radii rx, ry) from angle a0 to a1 */
	private static void arc(java.util.List<int[]> out, double cx, double cy, double rx, double ry, double a0, double a1) {
		int steps = (int) Math.max(4, Math.ceil(Math.max(rx, ry) * Math.abs(a1 - a0)));
		for (int k = 0; k <= steps; k++) {
			double a = a0 + (a1 - a0) * k / steps;
			out.add(new int[] {(int) Math.round(cx + rx * Math.cos(a)), (int) Math.round(cy + ry * Math.sin(a))});
		}
	}
	private static void traceList(java.util.List<int[]> pts) {
		int[] xs = new int[pts.size()], ys = new int[pts.size()];
		for (int i = 0; i < xs.length; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
		trace(xs, ys, true);
	}
	private static void roundRect(int l, int t, int r, int b, boolean fill) {
		double rad = Math.min(8, Math.min(r - l, b - t) / 2.0);
		if (fill) fillInside(l, t, r, b, (px, py) -> {
			if (px <= l || px >= r || py <= t || py >= b) return false;
			double cx = Math.max(l + rad, Math.min(px, r - rad)), cy = Math.max(t + rad, Math.min(py, b - rad));
			return (px - cx) * (px - cx) + (py - cy) * (py - cy) < rad * rad;
		});
		java.util.List<int[]> pts = new java.util.ArrayList<>();
		arc(pts, r - rad, t + rad, rad, rad, -Math.PI / 2, 0);
		arc(pts, r - rad, b - rad, rad, rad, 0, Math.PI / 2);
		arc(pts, l + rad, b - rad, rad, rad, Math.PI / 2, Math.PI);
		arc(pts, l + rad, t + rad, rad, rad, Math.PI, Math.PI * 1.5);
		traceList(pts);
	}
	private static void oval(int l, int t, int r, int b, boolean fill) {
		double cx = (l + r) / 2.0, cy = (t + b) / 2.0, rx = (r - l) / 2.0, ry = (b - t) / 2.0;
		if (fill && rx > 0 && ry > 0) fillInside(l, t, r, b, (px, py) -> {
			double dx = (px - cx) / rx, dy = (py - cy) / ry;
			return dx * dx + dy * dy < 1;
		});
		java.util.List<int[]> pts = new java.util.ArrayList<>();
		arc(pts, cx, cy, rx, ry, 0, Math.PI * 2);
		traceList(pts);
	}
	private static void polygon(double[] xs, double[] ys, boolean fill) {
		int n = lineSize();
		int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
		for (int i = 0; i < xs.length; i++) { l = Math.min(l, (int) Math.floor(xs[i])); r = Math.max(r, (int) Math.ceil(xs[i])); t = Math.min(t, (int) Math.floor(ys[i])); b = Math.max(b, (int) Math.ceil(ys[i])); }
		if (fill) {
			for (int y = t; y <= b; y++) for (int x = l; x <= r; x++) {
				double px = x + 0.5, py = y + 0.5;
				boolean in = false;
				for (int i = 0, j = xs.length - 1; i < xs.length; j = i++)
					if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) in = !in;
				if (in) plotPattern(x, y);
			}
		}
		for (int i = 0; i < xs.length; i++) {
			int j = (i + 1) % xs.length;
			line((int) Math.round(xs[i]), (int) Math.round(ys[i]), (int) Math.round(xs[j]), (int) Math.round(ys[j]), (x, y) -> pen(x, y, n, BLACK));
		}
	}

	/** HyperCard's brushes (PaintTool.CLASSIC_BRUSHES: 16 x 16, a row's bits from the left, placed with (8,8) on the point) */
	static final int[][] BRUSHES = {
		{0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF},
		{0x0000,0x0000,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0FF0,0x0FF0,0x0FF0,0x0FF0,0x0FF0,0x0FF0,0x0FF0,0x0FF0,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x03C0,0x03C0,0x03C0,0x03C0,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x07E0,0x1FF8,0x3FFC,0x7FFE,0x7FFE,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0xFFFF,0x7FFE,0x7FFE,0x3FFC,0x1FF8,0x07E0},
		{0x0000,0x0000,0x03C0,0x0FF0,0x1FF8,0x1FF8,0x3FFC,0x3FFC,0x3FFC,0x3FFC,0x1FF8,0x1FF8,0x0FF0,0x03C0,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x03C0,0x07E0,0x0FF0,0x0FF0,0x0FF0,0x0FF0,0x07E0,0x03C0,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0180,0x03C0,0x03C0,0x0180,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0003,0x0006,0x000C,0x0018,0x0030,0x0060,0x00C0,0x0180,0x0300,0x0600,0x0C00,0x1800,0x3000,0x6000,0xC000},
		{0x0000,0x0000,0x0000,0x000C,0x0018,0x0030,0x0060,0x00C0,0x0180,0x0300,0x0600,0x0C00,0x1800,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0030,0x0060,0x00C0,0x0180,0x0300,0x0600,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x00C0,0x0180,0x0300,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0xC000,0x6000,0x3000,0x1800,0x0C00,0x0600,0x0300,0x0180,0x00C0,0x0060,0x0030,0x0018,0x000C,0x0006,0x0003},
		{0x0000,0x0000,0x0000,0x1800,0x0C00,0x0600,0x0300,0x0180,0x00C0,0x0060,0x0030,0x0018,0x000C,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0600,0x0300,0x0180,0x00C0,0x0060,0x0030,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0300,0x0180,0x00C0,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180},
		{0x0000,0x0000,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0180,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0080,0x0080,0x0080,0x0080,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0xFFFF,0xFFFF,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x3FFC,0x3FFC,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0FF0,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x03C0,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0001,0x0000,0x0004,0x0000,0x0010,0x0000,0x0040,0x0000,0x0100,0x0000,0x0400,0x0000,0x1000,0x0000,0x4000},
		{0x0000,0x0000,0x0000,0x0000,0x0008,0x0000,0x0020,0x0000,0x0080,0x0000,0x0200,0x0000,0x0800,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0020,0x0000,0x0080,0x0000,0x0200,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0080,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0080,0x0000,0x0000,0x0080,0x0000,0x0000,0x0080,0x0000,0x0000,0x0080,0x0000,0x0000,0x0080,0x0000,0x0000,0x0080},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x9249,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000},
		{0x0000,0x0000,0x0100,0x0000,0x1004,0x0000,0x0040,0x0000,0x2000,0x0000,0x0104,0x0000,0x0000,0x0810,0x0000,0x0000},
		{0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0180,0x0180,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000}};
	/** the brush erases in its shape (HyperCard: the brush dragged with commandKey is an eraser of its shape) */
	private static boolean brushErases;
	private static void brushDot(int x, int y) { if (brushErases) erase(x, y); else plotPattern(x, y); }
	private static void brushAt(int x, int y) {
		// (HyperCard's brush 8 until a script sets another: reset paint's, TTalk; a brushSize set picks the desktop's round one)
		int n = intProp("brush", prop("brushsize").isEmpty() ? 8 : 0);
		if (n >= 1 && n <= 32) {
			int[] shape = BRUSHES[n - 1];
			for (int r = 0; r < 16; r++) for (int c = 0; c < 16; c++) if ((shape[r] & (0x8000 >> c)) != 0) brushDot(x - 8 + c, y - 8 + r);
			return;
		}
		// the desktop's round brush of brushSize (3 by default)
		int d = Math.max(1, intProp("brushsize", 3));
		double rad = d / 2.0;
		for (int dy = -d; dy <= d; dy++) for (int dx = -d; dx <= d; dx++) {
			double px = dx + 0.5 - (d % 2 == 1 ? 0.5 : 0), py = dy + 0.5 - (d % 2 == 1 ? 0.5 : 0);
			if (px * px + py * py <= rad * rad) brushDot(x + dx, y + dy);
		}
	}
	private static void eraserAt(int x, int y) { for (int dy = -8; dy < 8; dy++) for (int dx = -8; dx < 8; dx++) erase(x + dx, y + dy); }
	private static java.util.Random spray = new java.util.Random(1);
	private static void sprayAt(int x, int y) {
		for (int i = 0; i < 12; i++) {
			int dx = spray.nextInt(17) - 8, dy = spray.nextInt(17) - 8;
			if (dx * dx + dy * dy <= 64) plotPattern(x + dx, y + dy);
		}
	}
	/** the bucket: the area of the colour under the point (4-connected; the card's transparent pixels count as white) */
	private static void bucket(Surface s, int x, int y) {
		int w = s.img.width, h = s.img.height;
		if (x < 0 || y < 0 || x >= w || y >= h) return;
		int[] px = s.img.argb;
		int seed = key(px[y * w + x]);
		boolean[] seen = new boolean[w * h];
		int[] stack = new int[w * h];
		int sp = 0;
		stack[sp++] = y * w + x;
		seen[y * w + x] = true;
		OStack st = s.owner.stack;
		int pat = patternNumber();
		java.util.List<Integer> area = new java.util.ArrayList<>();
		while (sp > 0) {
			int i = stack[--sp];
			area.add(i);
			int cx = i % w, cy = i / w;
			int[] next = {cx > 0 ? i - 1 : -1, cx < w - 1 ? i + 1 : -1, cy > 0 ? i - w : -1, cy < h - 1 ? i + w : -1};
			for (int j : next) if (j >= 0 && !seen[j] && key(px[j]) == seed) { seen[j] = true; stack[sp++] = j; }
		}
		for (int i : area) plot(i % w, i / w, patternAt(st, pat, i % w, i / w));
	}
	private static int key(int c) { return (c >>> 24) < 128 ? WHITE : c | 0xFF000000; }

	// ---------------------------------------------------------------- the mouse with a paint tool

	private static int downX, downY, lastX, lastY;
	private static boolean pressing, pencilWhite;
	private static int[] pressMods = new int[0];
	/** the drag moves the floating selection (it started inside it) */
	private static boolean moving;
	private static int moveDX, moveDY;

	private static boolean has(int[] mods, int k) { for (int m : mods) if (m == k) return true; return false; }
	private static int snap(int v) { return grid() ? Math.round(v / 8f) * 8 : v; }

	/** A press at x,y with the tool chosen (mods: HyperTalk's key numbers, 11 shift, 12 option, 14 command) */
	static void down(int x, int y, int[] mods) {
		String t = tool();
		OCardBase cb = target();
		Surface s = surface(cb);
		if (s == null) return;
		if (floating != null && floating.on != s) commit();
		if (!t.equals("text") && text != null) commitText();
		pressMods = mods == null ? new int[0] : mods;
		brushErases = has(pressMods, 14);
		downX = lastX = x; downY = lastY = y;
		pressing = true;
		moving = false;
		if (t.equals("select") || t.equals("lasso")) {
			if (floating != null && floating.contains(x, y)) {
				snapshot(s);
				moving = true; moveDX = x - floating.x; moveDY = y - floating.y;
				// optionKey: a copy is dragged away, the original stays
				if (has(pressMods, 12)) floating.stampOnto(s.img, s.opaque);
				return;
			}
			boolean ended = floating != null;
			commit();
			// a click off the Paint menu's selection: the tool before it comes back, and this press paints nothing
			if (ended && toolBeforeSelect != null) { backToToolBeforeSelect(); pressing = false; }
			return;
		}
		if (t.equals("text")) { startText(s, x, y); pressing = false; return; }
		snapshot(s);
		begin(s);
		switch (t) {
			case "pencil": {
				int w = s.img.width;
				pencilWhite = x >= 0 && y >= 0 && x < w && y < s.img.height && dark(s.img.argb[y * w + x]);
				plot(x, y, pencilWhite ? (s.opaque ? WHITE : WHITE) : BLACK);
				break;
			}
			case "brush": brushAt(x, y); break;
			case "eraser": eraserAt(x, y); break;
			case "spray": sprayAt(x, y); break;
			case "curve": pen(x, y, lineSize(), BLACK); break;
			case "bucket": bucket(s, x, y); break;
			default: break; // shapes: at the release
		}
		drawing = null; // (the stroke goes on in move)
		if (!t.equals("bucket")) s.touched(); else end0(s);
	}
	private static void end0(Surface s) { if (maxX >= minX) lastBounds = new int[] {minX, minY, maxX + 1, maxY + 1}; s.touched(); }

	/** The pointer moved to x,y with the button held */
	static void move(int x, int y) {
		if (!pressing) return;
		String t = tool();
		Surface s = surface(target());
		if (s == null) return;
		if (moving) {
			if (floating != null) { floating.x = x - moveDX; floating.y = y - moveDY; floating.pristine = false; s.touched(); }
			lastX = x; lastY = y;
			return;
		}
		int lx = lastX, ly = lastY;
		lastX = x; lastY = y;
		begin(s);
		switch (t) {
			case "pencil": line(lx, ly, x, y, (px, py) -> plot(px, py, pencilWhite ? WHITE : BLACK)); break;
			case "brush": brushLine(lx, ly, x, y); break;
			case "eraser": line(lx, ly, x, y, HzPaint::eraserAt); break;
			case "spray": sprayAt(x, y); break;
			case "curve": { int n = lineSize(); patternPen = has(pressMods, 12); try { line(lx, ly, x, y, (px, py) -> pen(px, py, n, BLACK)); } finally { patternPen = false; } break; }
			default: drawing = null; return; // shapes and selections: at the release
		}
		int a = minX, b = minY, c = maxX, d = maxY;
		drawing = null;
		if (c >= a) s.touched();
		// (the stroke's bounds add up for "Select")
		if (c >= a) lastBounds = union(lastBounds, new int[] {a, b, c + 1, d + 1});
	}
	private static int[] union(int[] r, int[] q) {
		if (r == null) return q;
		return new int[] {Math.min(r[0], q[0]), Math.min(r[1], q[1]), Math.max(r[2], q[2]), Math.max(r[3], q[3])};
	}

	/** The release at x,y */
	static void up(int x, int y) {
		if (!pressing) return;
		move(x, y);
		pressing = false;
		String t = tool();
		Surface s = surface(target());
		if (s == null) return;
		if (moving) { moving = false; s.touched(); return; }
		int sx = snap(downX), sy = snap(downY), ex = snap(x), ey = snap(y);
		boolean square = has(pressMods, 11);
		patternPen = has(pressMods, 12) && !t.equals("select") && !t.equals("lasso");
		try { shapeUp(t, s, sx, sy, ex, ey, square); } finally { patternPen = false; }
	}
	private static void shapeUp(String t, Surface s, int sx, int sy, int ex, int ey, boolean square) {
		int dx = ex - sx, dy = ey - sy;
		// shift: a square (circle) of the drag's smaller side (HC 2.4 measured, the desktop's PaintTool.dragRectangle)
		if (square && !t.equals("line")) { int m = Math.min(Math.abs(dx), Math.abs(dy)); dx = dx < 0 ? -m : m; dy = dy < 0 ? -m : m; }
		int l, tp, r, b;
		if (centered() && !t.equals("line") && !t.equals("select") && !t.equals("lasso")) { l = sx - Math.abs(dx); r = sx + Math.abs(dx); tp = sy - Math.abs(dy); b = sy + Math.abs(dy); }
		else { l = Math.min(sx, sx + dx); r = Math.max(sx, sx + dx); tp = Math.min(sy, sy + dy); b = Math.max(sy, sy + dy); }
		switch (t) {
			case "select": case "lasso": {
				if (r - l < 1 || b - tp < 1) return;
				select(s, l, tp, r, b, t.equals("lasso") || has(pressMods, 12));
				if (t.equals("select") && has(pressMods, 12)) selectWentLasso = true;
				return;
			}
			case "rect": begin(s); rect(l, tp, r, b, filled()); end(); return;
			case "roundrect": begin(s); roundRect(l, tp, r, b, filled()); end(); return;
			case "oval": begin(s); oval(l, tp, r, b, filled()); end(); return;
			case "line": {
				if (square) { // shift: along 15-degree steps (the desktop's HyperCard constraint)
					double ang = Math.atan2(ey - sy, ex - sx), step = Math.PI / 12, len = Math.hypot(ex - sx, ey - sy);
					ang = Math.round(ang / step) * step;
					ex = sx + (int) Math.round(len * Math.cos(ang)); ey = sy + (int) Math.round(len * Math.sin(ang));
				}
				int n = lineSize();
				begin(s); line(sx, sy, ex, ey, (px, py) -> pen(px, py, n, BLACK)); end();
				return;
			}
			case "regpoly": {
				int sides = Math.max(3, Math.min(64, intProp("polysides", 4)));
				double rad = Math.hypot(ex - sx, ey - sy), a0 = Math.atan2(ey - sy, ex - sx);
				double[] xs = new double[sides], ys = new double[sides];
				for (int i = 0; i < sides; i++) { double a = a0 + 2 * Math.PI * i / sides; xs[i] = sx + rad * Math.cos(a); ys[i] = sy + rad * Math.sin(a); }
				begin(s); polygon(xs, ys, filled()); end();
				return;
			}
			case "poly": polygonUp(s, sx, sy, ex, ey); return;
			default: return;
		}
	}

	/** the polygon being drawn (HyperCard's polygon tool): its corners so far; each press goes on from the last corner, a
	 *  release adds a corner with its side drawn; a click on the first corner, on the last one again, or a double click closes
	 *  it (filled inside when filled); another tool leaves it open as drawn */
	private static java.util.List<int[]> polyPoints;
	private static Surface polyOn;
	/** the press's clicks (2: a double click), from the player's mouse */
	static int clickCount = 1;
	private static void polygonUp(Surface s, int sx, int sy, int ex, int ey) {
		if (polyOn != s) { polyPoints = null; polyOn = s; }
		int n = lineSize();
		boolean click = Math.abs(ex - sx) <= 1 && Math.abs(ey - sy) <= 1;
		if (polyPoints == null) {
			if (click) return; // (a corner is made by a drag first)
			polyPoints = new java.util.ArrayList<>();
			polyPoints.add(new int[] {sx, sy});
		}
		int[] first = polyPoints.get(0), last = polyPoints.get(polyPoints.size() - 1);
		boolean closes = click && (near(ex, ey, first) || near(ex, ey, last) || clickCount >= 2);
		if (closes) {
			if (polyPoints.size() >= 3) {
				double[] xs = new double[polyPoints.size()], ys = new double[polyPoints.size()];
				for (int i = 0; i < xs.length; i++) { xs[i] = polyPoints.get(i)[0]; ys[i] = polyPoints.get(i)[1]; }
				begin(s); polygon(xs, ys, filled()); end();
			} else if (polyPoints.size() == 2) { begin(s); line(first[0], first[1], last[0], last[1], (px, py) -> pen(px, py, n, BLACK)); end(); }
			polyPoints = null;
			return;
		}
		begin(s);
		line(last[0], last[1], ex, ey, (px, py) -> pen(px, py, n, BLACK));
		end();
		polyPoints.add(new int[] {ex, ey});
	}
	private static boolean near(int x, int y, int[] p) { return Math.abs(x - p[0]) <= 3 && Math.abs(y - p[1]) <= 3; }

	/** drag from … to … with a paint tool (TTalk.paintToolDrag on the desktop): a press, the pointer along the line, the release */
	static void drag(int sx, int sy, int ex, int ey, int[] mods) {
		clickCount = 1;
		down(sx, sy, mods);
		double d = Math.hypot(ex - sx, ey - sy);
		// the brush: one stroke from start to end (HyperCard J-2.3; the desktop's PaintTool.dragIsOneStroke)
		int steps = "brush".equals(tool()) ? 1 : (int) Math.min(400, Math.max(1, Math.ceil(d / 2)));
		for (int i = 1; i < steps; i++) move(sx + (int) Math.round((ex - sx) * (double) i / steps), sy + (int) Math.round((ey - sy) * (double) i / steps));
		up(ex, ey);
	}
	/** click at with a paint tool */
	static void click(int x, int y, int[] mods) { clickCount = 1; down(x, y, mods); up(x, y); }

	// ---------------------------------------------------------------- the selection

	/** lifted pixels: img at x,y (its size), mask[i]: part of the selection */
	static final class Floating {
		final Surface on;
		HzImage img;
		boolean[] mask;
		int x, y;
		boolean transparent;
		/** lifted and not touched since (not moved, no command on it): it goes back as it was, pixel for pixel (the desktop's
		 *  FloatingSelection.putBack) - a selection made and dropped changes nothing */
		boolean pristine;
		Floating(Surface on, HzImage img, boolean[] mask, int x, int y) { this.on = on; this.img = img; this.mask = mask; this.x = x; this.y = y; }
		Floating copy() {
			Floating f = new Floating(on, new HzImage(img.width, img.height, img.argb.clone()), mask.clone(), x, y);
			f.transparent = transparent; f.pristine = pristine;
			return f;
		}
		boolean contains(int px, int py) {
			int i = px - x, j = py - y;
			return i >= 0 && j >= 0 && i < img.width && j < img.height && mask[j * img.width + i];
		}
		/** onto the picture: opaque, the pixels as they are (the card's transparent ones white); transparent, only the dark ones */
		void stampOnto(HzImage dst, boolean opaque) {
			if (pristine) {
				for (int j = 0; j < img.height; j++) for (int i = 0; i < img.width; i++) {
					int tx = x + i, ty = y + j;
					if (tx >= 0 && ty >= 0 && tx < dst.width && ty < dst.height && mask[j * img.width + i]) dst.argb[ty * dst.width + tx] = img.argb[j * img.width + i];
				}
				return;
			}
			for (int j = 0; j < img.height; j++) {
				int ty = y + j;
				if (ty < 0 || ty >= dst.height) continue;
				for (int i = 0; i < img.width; i++) {
					int tx = x + i;
					if (tx < 0 || tx >= dst.width || !mask[j * img.width + i]) continue;
					int c = img.argb[j * img.width + i];
					if (transparent) { if ((c >>> 24) < 128 || (c | 0xFF000000) == WHITE) continue; }
					else if ((c >>> 24) < 128) c = WHITE;
					dst.argb[ty * dst.width + tx] = c;
				}
			}
		}
	}
	static Floating floating;

	/** The selection of l,t to r,b (r, b not in it) lifted from the picture; tight: only its painted pixels (the lasso) */
	private static void select(Surface s, int l, int t, int r, int b, boolean tight) { select(s, l, t, r, b, tight, null); }
	/** before: the surface before the last thing painted (its undo), or null; then only what that changed is selected */
	private static void select(Surface s, int l, int t, int r, int b, boolean tight, int[] before) {
		commit();
		l = Math.max(0, l); t = Math.max(0, t); r = Math.min(s.img.width, r); b = Math.min(s.img.height, b);
		if (r <= l || b <= t) return;
		int w = r - l, h = b - t;
		HzImage img = new HzImage(w, h);
		boolean[] mask = new boolean[w * h];
		int ml = w, mt = h, mr = -1, mb = -1;
		for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
			int c = s.img.argb[(t + j) * s.img.width + l + i];
			img.argb[j * w + i] = c;
			boolean part = before != null ? c != before[(t + j) * s.img.width + l + i] : !tight || ((c >>> 24) >= 128 && (c | 0xFF000000) != WHITE);
			mask[j * w + i] = part;
			if (part) { ml = Math.min(ml, i); mt = Math.min(mt, j); mr = Math.max(mr, i); mb = Math.max(mb, j); }
		}
		if (mr < 0) return; // a lasso round nothing painted selects nothing
		snapshot(s);
		Floating f = new Floating(s, img, mask, l, t);
		if (tight) f = crop(f, ml, mt, mr + 1, mb + 1);
		f.pristine = true;
		// lifted: the picture under it as the eraser leaves it
		for (int j = 0; j < f.img.height; j++) for (int i = 0; i < f.img.width; i++)
			if (f.mask[j * f.img.width + i]) s.img.argb[(f.y + j) * s.img.width + f.x + i] = s.blank();
		floating = f;
		s.touched();
	}
	private static Floating crop(Floating f, int l, int t, int r, int b) {
		int w = r - l, h = b - t;
		HzImage img = new HzImage(w, h);
		boolean[] mask = new boolean[w * h];
		for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
			img.argb[j * w + i] = f.img.argb[(t + j) * f.img.width + l + i];
			mask[j * w + i] = f.mask[(t + j) * f.img.width + l + i];
		}
		Floating g = new Floating(f.on, img, mask, f.x + l, f.y + t);
		g.transparent = f.transparent;
		return g;
	}
	/** Where the selection is on the card on show (its bounds), while a paint tool is chosen; else null */
	static int[] selectionBounds(OCard cd) {
		Floating f = floating;
		if (f == null || !active() || cd == null || (f.on.owner != cd && f.on.owner != cd.bg)) return null;
		return new int[] {f.x, f.y, f.x + f.img.width, f.y + f.img.height};
	}
	/** The selection (and the text being typed) go into the picture */
	static void commit() {
		commitText();
		Floating f = floating;
		if (f == null) return;
		floating = null;
		f.stampOnto(f.on.img, f.on.opaque);
		lastBounds = new int[] {f.x, f.y, f.x + f.img.width, f.y + f.img.height};
		f.on.touched();
	}

	// ---------------------------------------------------------------- the text tool

	private static final class Typing { Surface on; int[] base; int x, y; StringBuilder text = new StringBuilder(); }
	private static Typing text;
	private static void startText(Surface s, int x, int y) {
		commit();
		snapshot(s);
		Typing t = new Typing();
		t.on = s; t.base = s.img.argb.clone(); t.x = x; t.y = y;
		text = t;
	}
	/** Whether the text tool has an insertion point to type at */
	static boolean typing() { return text != null && tool().equals("text"); }
	/** Text typed with the text tool (type, the keyboard): a backspace takes the last character back, a return starts a line */
	static void type(String s) {
		Typing t = text;
		if (t == null) return;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\b' || c == 127) { if (t.text.length() > 0) t.text.setLength(t.text.length() - 1); }
			else if (c == '\r' || c == '\n') t.text.append('\n');
			else if (c >= ' ') t.text.append(c);
		}
		drawText(t);
	}
	private static void drawText(Typing t) {
		Surface s = t.on;
		System.arraycopy(t.base, 0, s.img.argb, 0, t.base.length);
		String font = prop("textfont"); if (font.isEmpty()) font = "Geneva";
		int size = Math.max(1, intProp("textsize", 12));
		int style = styleBits(prop("textstyle"));
		HzPict.Canvas make = HzImages.pictCanvas;
		if (make == null || t.text.length() == 0) { s.touched(); return; }
		String[] lines = t.text.toString().split("\n", -1);
		int lh = (int) Math.ceil(size * 1.25);
		// the textAlign places each line by the clicked point: left starts there, center is centred on it, right ends there
		// (HyperCard J-2.3: Hyper-Wordle's chart title is centred, its row labels end 3 pixels left of the bars)
		String align = TUtil.compatibilityProperty("textAlign").toLowerCase(java.util.Locale.ROOT);
		for (int li = 0; li < lines.length; li++) {
			String line = lines[li];
			if (line.isEmpty()) continue;
			int w = Math.max(1, (int) Math.ceil(size * line.length() * 1.2) + 8), h = lh + 8;
			Gfx g = make.make(w, h);
			g.setColor(0x00FFFFFF); g.fillRect(0, 0, w, h);
			// the styles as a field's run (the desktop's paint text is a MyTextArea): Geneva's bold is QuickDraw's smear, a font
			// without a bold face of its own (a browser drew Geneva bold no bolder: Hyper-Wordle's chart title)
			CardRenderer.setRunFont(g, font, size, style);
			g.setColor(BLACK);
			int asc = Math.max(1, g.ascent());
			CardRenderer.runText(g, line, 2, 2 + asc, font, style, BLACK);
			int[] px = g.getPixels(0, 0, w, h);
			int lineW = align.equals("center") || align.equals("right") ? CardRenderer.runWidth(g, line, font, style) : 0;
			int ox = t.x - 2 - (align.equals("center") ? lineW / 2 : lineW), oy = t.y + li * lh - asc - 2;
			// a pixel at least 80/255 covered is black (as the desktop, MyTextArea.UNSMOOTHED_COVERAGE): a browser's text is always
			// smoothed, and at 9-10 points a stroke that falls between two pixels covers each by half (with half as the line the
			// letters broke up)
			for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
				int c = px[j * w + i];
				if ((c >>> 24) >= 80 && dark(c | 0xFF000000)) {
					int X = ox + i, Y = oy + j;
					if (X >= 0 && Y >= 0 && X < s.img.width && Y < s.img.height) s.img.argb[Y * s.img.width + X] = BLACK;
				}
			}
		}
		s.touched();
	}
	private static int styleBits(String v) {
		String s = v.toLowerCase(java.util.Locale.ROOT);
		int b = 0;
		if (s.contains("bold")) b |= 1;
		if (s.contains("italic")) b |= 2;
		if (s.contains("underline")) b |= 4;
		if (s.contains("outline")) b |= 8;
		if (s.contains("shadow")) b |= 16;
		return b;
	}
	private static void commitText() {
		Typing t = text;
		text = null;
		if (t != null && t.text.length() > 0) t.on.touched();
	}

	// ---------------------------------------------------------------- the Paint menu

	/** A picture for picture "x","clipboard" and Paste Picture: what Copy Picture copied, and where it came from */
	static HzImage clipboard;
	static int clipX, clipY;

	/** doMenu with a paint item (GPaintMenu's): true when it was one */
	static boolean menu(String item) {
		String it = item == null ? "" : item.trim().toLowerCase(java.util.Locale.ROOT);
		if (it.endsWith("…")) it = it.substring(0, it.length() - 1);
		if (it.endsWith("...")) it = it.substring(0, it.length() - 3);
		OCardBase cb = target();
		Surface s = surface(cb);
		switch (it) {
			case "select all": {
				if (s == null) return true;
				commit();
				rememberToolBeforeSelect();
				HeadlessProps.set("tool", "select tool");
				select(s, 0, 0, s.img.width, s.img.height, false);
				return true;
			}
			// the lasso closes in on the last thing painted, or on all of the picture when nothing was painted since a tool was
			// chosen (HC J-2.3/2.4 measured); the tool is the lasso while it lasts, the select tool with nothing to close in on
			case "select": {
				if (s == null) return true;
				int[] r = lastBounds;
				// what that shape painted, not the picture under it in its bounds (HC 2.4: a line through a rectangle drawn
				// after it stays; DoodlePaint's squares cut the drawing they were drawn over)
				int[] before = floating == null && s.undo != null && s.undo.length == s.img.argb.length ? s.undo : null;
				commit();
				rememberToolBeforeSelect();
				if (r != null) select(s, r[0], r[1], r[2], r[3], true, before);
				if (r != null && floating == null && before != null) select(s, r[0], r[1], r[2], r[3], true);
				if (floating == null) select(s, 0, 0, s.img.width, s.img.height, true);
				HeadlessProps.set("tool", floating != null ? "lasso tool" : "select tool");
				if (floating == null) toolBeforeSelect = null;
				return true;
			}
			case "copy picture": case "cut picture": {
				if (s == null) return true;
				Floating f = floating;
				if (f != null && f.on == s) {
					HzImage c = new HzImage(f.img.width, f.img.height);
					for (int i = 0; i < c.argb.length; i++) c.argb[i] = f.mask[i] ? ((f.img.argb[i] >>> 24) < 128 ? WHITE : f.img.argb[i]) : 0;
					clipboard = c; clipX = f.x; clipY = f.y;
					if (it.equals("cut picture")) { floating = null; s.touched(); backToToolBeforeSelect(); }
				} else if (!active() || it.equals("copy picture")) {
					commit();
					clipboard = new HzImage(s.img.width, s.img.height, s.img.argb.clone()); clipX = 0; clipY = 0;
				}
				return true;
			}
			case "clear picture": {
				if (s == null) return true;
				Floating f = floating;
				if (f != null && f.on == s) { snapshot(s); floating = null; s.touched(); }
				backToToolBeforeSelect();
				return true;
			}
			case "paste picture": {
				if (s == null || clipboard == null) return true;
				commit();
				snapshot(s);
				HzImage c = clipboard;
				boolean[] mask = new boolean[c.argb.length];
				for (int i = 0; i < mask.length; i++) mask[i] = (c.argb[i] >>> 24) >= 128;
				int x = clipX, y = clipY;
				if (x < 0 || y < 0 || x + c.width > s.img.width || y + c.height > s.img.height) { x = (s.img.width - c.width) / 2; y = (s.img.height - c.height) / 2; }
				floating = new Floating(s, new HzImage(c.width, c.height, c.argb.clone()), mask, x, y);
				HeadlessProps.set("tool", "select tool");
				s.touched();
				return true;
			}
			case "invert": return each(s, (c, x, y) -> (c >>> 24) < 128 ? BLACK : (c & 0xFF000000) | (~c & 0xFFFFFF));
			case "fill": { OStack st = cb == null ? null : cb.stack; int n = patternNumber(); return each(s, (c, x, y) -> patternAt(st, n, x, y)); }
			// Darken adds the dots of a light grey (pattern 3), Lighten takes them away from what is dark; again and again
			// the dots move on, so the picture goes on getting darker or lighter (MacPaint's way)
			case "darken": { int k = shade++; return each(s, (c, x, y) -> PATTERN_DOT(x + k * 3, y + k) ? BLACK : c); }
			case "lighten": { int k = shade++; return each(s, (c, x, y) -> PATTERN_DOT(x + k * 3, y + k) && dark(c) ? WHITE : c); }
			case "opaque": if (floating != null) { floating.transparent = false; floating.pristine = false; floating.on.touched(); } return true;
			case "transparent": if (floating != null) { floating.transparent = true; floating.pristine = false; floating.on.touched(); } return true;
			case "flip horizontal": return transform(s, 0);
			case "flip vertical": return transform(s, 1);
			case "rotate left": return transform(s, 2);
			case "rotate right": return transform(s, 3);
			case "trace edges": return traceEdges(s);
			case "revert": {
				if (cb == null) return true;
				floating = null; text = null;
				synchronized (surfaces) { surfaces.remove(cb); }
				cb.layerVersion++;
				changedPicture();
				return true;
			}
			case "undo": {
				if (s == null || s.undo == null) return false;
				text = null;
				// (Undo again redoes: the state now becomes the one to go back to)
				int[] was = s.img.argb.clone();
				Floating wasFloating = floating != null && floating.on == s ? floating.copy() : null;
				System.arraycopy(s.undo, 0, s.img.argb, 0, was.length);
				floating = s.undoFloating;
				s.undo = was; s.undoFloating = wasFloating;
				s.touched();
				return true;
			}
			case "keep": commit(); return true;
			default: return false;
		}
	}
	private interface PixelOp { int at(int c, int x, int y); }
	private static int shade;
	private static boolean PATTERN_DOT(int x, int y) {
		int row = (int) (PATTERNS[2] >>> (8 * (7 - Math.floorMod(y, 8)))) & 0xFF;
		return (row & (0x80 >> Math.floorMod(x, 8))) != 0;
	}
	/** a pixel operation on the selection, or on the whole picture without one */
	private static boolean each(Surface s, PixelOp op) {
		if (s == null) return true;
		Floating f = floating;
		snapshot(s);
		if (f != null && f.on == s) {
			f.pristine = false;
			for (int j = 0; j < f.img.height; j++) for (int i = 0; i < f.img.width; i++) {
				int k = j * f.img.width + i;
				if (f.mask[k]) f.img.argb[k] = op.at(f.img.argb[k], f.x + i, f.y + j);
			}
		} else {
			for (int y = 0; y < s.img.height; y++) for (int x = 0; x < s.img.width; x++) {
				int k = y * s.img.width + x;
				int c = op.at(s.img.argb[k], x, y);
				s.img.argb[k] = s.opaque ? over(WHITE, c) : c;
			}
		}
		s.touched();
		return true;
	}
	/** flip (0 horizontal, 1 vertical) or rotate (2 left, 3 right) the selection about its centre */
	private static boolean transform(Surface s, int kind) {
		Floating f = floating;
		if (s == null || f == null || f.on != s) return true;
		snapshot(s);
		int w = f.img.width, h = f.img.height, nw = kind >= 2 ? h : w, nh = kind >= 2 ? w : h;
		HzImage img = new HzImage(nw, nh);
		boolean[] mask = new boolean[nw * nh];
		for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
			int ni, nj;
			switch (kind) {
				case 0: ni = w - 1 - i; nj = j; break;
				case 1: ni = i; nj = h - 1 - j; break;
				case 2: ni = j; nj = w - 1 - i; break;
				default: ni = h - 1 - j; nj = i; break;
			}
			img.argb[nj * nw + ni] = f.img.argb[j * w + i];
			mask[nj * nw + ni] = f.mask[j * w + i];
		}
		f.img = img; f.mask = mask; f.pristine = false;
		if (kind >= 2) { f.x += (w - nw) / 2; f.y += (h - nh) / 2; }
		s.touched();
		return true;
	}
	/** Trace Edges: each painted area becomes its outline (dark pixels with a light 4-neighbour stay, the inside goes) */
	private static boolean traceEdges(Surface s) {
		if (s == null) return true;
		snapshot(s);
		Floating f = floating;
		HzImage img = f != null && f.on == s ? f.img : s.img;
		if (f != null && f.on == s) f.pristine = false;
		int w = img.width, h = img.height;
		int[] src = img.argb.clone();
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
			int k = y * w + x;
			if (f != null && f.on == s && !f.mask[k]) continue;
			boolean d = dark(src[k]);
			boolean edge = d && (x == 0 || !dark(src[k - 1]) || x == w - 1 || !dark(src[k + 1]) || y == 0 || !dark(src[k - w]) || y == h - 1 || !dark(src[k + w]));
			img.argb[k] = d && !edge ? (s.opaque || f != null ? WHITE : 0) : src[k];
		}
		s.touched();
		return true;
	}

	// ---------------------------------------------------------------- keeping the pictures

	/** Whether a card or background of the stack has a painted picture not yet written */
	static boolean hasUnsaved(OStack st) {
		synchronized (surfaces) { for (Surface s : surfaces.values()) if (s.dirty && s.owner.stack == st) return true; }
		return false;
	}
	/** The painted pictures of the stack written into its files (before PlayerSaving writes it): each as PNG, a card or
	 *  background with no picture file of its own (or one it shares) getting BMAP_<id>.png; a picture painted empty goes */
	static void flush(OStack st) {
		java.util.List<Surface> list = new java.util.ArrayList<>();
		synchronized (surfaces) { for (Surface s : surfaces.values()) if (s.dirty && s.owner.stack == st) list.add(s); }
		for (Surface s : list) {
			s.dirty = false;
			HzImage keep = s.img;
			if (floating != null && floating.on == s || text != null && text.on == s) keep = shown(s.owner);
			OCardBase cb = s.owner;
			boolean empty = true;
			for (int c : keep.argb) if (s.opaque ? c != WHITE : (c >>> 24) != 0) { empty = false; break; }
			if (empty) {
				if (cb.bitmapName != null && !cb.bitmapName.isEmpty()) { cb.bitmapName = ""; cb.changed = true; }
				continue;
			}
			String name = cb.bitmapName;
			if (name == null || name.isEmpty() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".png") || shared(cb, name)) {
				name = "BMAP_" + cb.id + ".png";
				for (int k = 2; shared(cb, name) || namedElsewhere(st, cb, name); k++) name = "BMAP_" + cb.id + "_" + k + ".png";
				cb.bitmapName = name;
				cb.changed = true;
			}
			byte[] png = HzPng.encode(keep);
			HeadlessStacks.writeFile(st, name, png);
			HzImages.forget(st, name);
		}
	}
	/** another card or background shows the picture file too */
	private static boolean shared(OCardBase cb, String name) {
		OStack st = cb.stack;
		if (st == null || name == null) return false;
		if (st.cdCacheList != null) for (OCard c : st.cdCacheList) if (c != cb && name.equals(c.bitmapName)) return true;
		if (st.bgCacheList != null) for (OBackground b : st.bgCacheList) if (b != cb && name.equals(b.bitmapName)) return true;
		return false;
	}
	private static boolean namedElsewhere(OStack st, OCardBase cb, String name) {
		return !name.equals(cb.bitmapName) && HzImages.bytes(st, name) != null;
	}
	/** A stack closed or opened afresh: its painting forgotten */
	static void forget(OStack st) {
		synchronized (surfaces) { surfaces.values().removeIf(s -> s.owner.stack == st); }
		if (floating != null && floating.on.owner.stack == st) floating = null;
		if (text != null && text.on.owner.stack == st) text = null;
	}
}

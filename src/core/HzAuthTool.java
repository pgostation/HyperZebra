/**
 * The button and field tools in the player (the desktop's AuthGUI ButtonGUI / FieldGUI): a press on a button (field) of the
 * card or its background selects it, with no messages; dragging moves it (Shift: along one axis), a press within 8 px of a
 * corner sizes it from there; Command-drag (Control too) makes a new transparent part over the dragged rectangle and sends
 * it newButton / newField; Option-drag drags a copy. The selected part has marching ants round it; transparent and opaque
 * parts show a black frame while the tool is chosen; Delete removes the selected part (HeadlessMenus' Delete Button/Field).
 * The selection is HeadlessMenus.selected, the part select chose and the Objects/Edit menus act on.
 * A double click opens Button Info / Field Info (PartInfo).
 */
final class HzAuthTool {
	private HzAuthTool() {}

	/** the button or field tool is chosen */
	static boolean active() { String t = HzPaint.tool(); return t.equals("button") || t.equals("field"); }
	private static boolean buttons() { return HzPaint.tool().equals("button"); }

	private static OObject target;     // the part the press moves or sizes, or null
	private static boolean making, sizing, sizeLeft, sizeTop, shift;
	private static int startX, startY, offX, offY, lastX, lastY, fromLeft, fromTop;

	/** the part of the tool's kind under the point: the card's over the background's, the top one first */
	static OObject partAt(int x, int y) {
		OStack st = HZ.stack();
		if (st == null || st.curCard == null) return null;
		boolean b = buttons();
		for (OCardBase layer : new OCardBase[] {st.curCard, st.curCard.bg}) {
			if (layer == null || layer.partsList == null) continue;
			for (int i = layer.partsList.size() - 1; i >= 0; i--) {
				OObject p = layer.partsList.get(i);
				if (b ? !(p instanceof OButton) : !(p instanceof OField)) continue;
				if (p.isShown() && x >= p.left && y >= p.top && x < p.left + p.width && y < p.top + p.height) return p;
			}
		}
		return null;
	}

	/** the selected part, when it is of the tool's kind and on the card shown */
	static OObject selection() {
		OObject s = HeadlessMenus.selected;
		OStack st = HZ.stack();
		if (s == null || st == null || st.curCard == null || !active()) return null;
		if (buttons() ? !(s instanceof OButton) : !(s instanceof OField)) return null;
		if (s.parent != st.curCard && s.parent != st.curCard.bg) return null;
		OCardBase owner = (OCardBase) s.parent;
		return owner.partsList != null && owner.partsList.contains(s) && s.isShown() ? s : null;
	}

	/** a double click on a part: Button Info / Field Info at the release (as AuthGUI's mouseReleased) */
	private static boolean info;

	static void down(int x, int y, int mods) { down(x, y, mods, 1); }
	static void down(int x, int y, int mods, int clicks) {
		info = false;
		TTalk.closeOpenFieldByUser();
		startX = lastX = x; startY = lastY = y;
		shift = (mods & PlayerHost.SHIFT) != 0;
		making = false; sizing = false;
		OStack st = HZ.stack();
		if ((mods & (PlayerHost.COMMAND | PlayerHost.CONTROL)) != 0) {
			// a new transparent part, sized by the drag (ButtonGUI.mousePressed with Command)
			OCardBase cdbase = HZ.editBackground || "true".equalsIgnoreCase(String.valueOf(HeadlessProps.get("editbkgnd")).trim()) ? st.curCard.bg : st.curCard;
			int id = cdbase.newPartId();
			OObject part;
			if (buttons()) {
				OButton b = new OButton(cdbase, id);
				cdbase.partsList.add(b); cdbase.btnList.add(b);
				b.style = 1;
				b.setRect(x, y, x + 1, y + 1);
				part = b;
			} else {
				OField f = new OField(cdbase, id);
				cdbase.partsList.add(f); cdbase.fldList.add(f);
				f.style = 1;
				f.enabled = false;
				f.setRect(x, y, x + 1, y + 1);
				part = f;
			}
			cdbase.changed = true;
			making = true;
			target = part;
			HeadlessMenus.selected = part;
			return;
		}
		OObject part = partAt(x, y);
		if (part != null && (mods & PlayerHost.OPTION) != 0) {
			OObject copy = HeadlessMenus.duplicate(part);
			if (copy != null) part = copy;
		}
		target = part;
		HeadlessMenus.selected = part;
		if (part == null) return;
		info = clicks >= 2 && (mods & PlayerHost.OPTION) == 0;
		sizing = (x < part.left + 8 || x >= part.left + part.width - 8) && (y < part.top + 8 || y >= part.top + part.height - 8);
		sizeLeft = x < part.left + 8; sizeTop = y < part.top + 8;
		offX = x - part.left; offY = y - part.top;
		fromLeft = part.left; fromTop = part.top;
	}

	/** the pointer moved with the button held: the part follows */
	static boolean move(int x, int y) {
		OObject p = target;
		if (p == null || x == lastX && y == lastY) return false;
		if (making) {
			setRect(p, Math.min(startX, x), Math.min(startY, y), Math.max(startX, x) + 1, Math.max(startY, y) + 1);
		} else if (sizing) {
			int l = p.left, t = p.top, r = p.left + p.width, b = p.top + p.height;
			if (sizeLeft) l = Math.min(r - 1, l + x - lastX); else r = Math.max(l + 1, r + x - lastX);
			if (sizeTop) t = Math.min(b - 1, t + y - lastY); else b = Math.max(t + 1, b + y - lastY);
			setRect(p, l, t, r, b);
		} else {
			int l = x - offX, t = y - offY;
			if (shift) { if (Math.abs(x - startX) < Math.abs(y - startY)) l = startX - offX; else t = startY - offY; }
			setRect(p, l, t, l + p.width, t + p.height);
		}
		lastX = x; lastY = y;
		return true;
	}

	/** the release: a moved part's panel comes along; a new part gets newButton / newField */
	static void up(int x, int y) {
		move(x, y);
		OObject p = target;
		target = null;
		if (p == null) return;
		OCardBase owner = (OCardBase) p.parent;
		boolean changed = making || p.left != fromLeft || p.top != fromTop || sizing;
		if (!making && !sizing) owner.movePanelPeers(p, p.left - fromLeft, p.top - fromTop);
		if (changed) owner.changed = true;
		if (changed) OCard.reloadCurrentCard();
		if (making) { making = false; TTalk.CallMessage(p instanceof OButton ? "newButton" : "newField", p); }
		else if (info && !changed && PlayerLoop.current != null) PartInfo.open(PlayerLoop.current.host, p);
		info = false;
	}

	/** Delete (Backspace) with a part selected: it goes, as Delete Button / Delete Field */
	static boolean deleteKey() {
		OObject s = selection();
		if (s == null) return false;
		HeadlessMenus.deleteSelected();
		return true;
	}

	private static void setRect(OObject p, int l, int t, int r, int b) {
		if (p instanceof OButton pb) pb.setRect(l, t, r, b);
		else if (p instanceof OField pf) pf.setRect(l, t, r, b);
	}

	/** what the tool shows over the card: transparent and opaque parts of its kind framed in black, the selection's marching
	 *  ants (white and black dashes of 4 that move every 80 ms: AuthGUI.paintAnts) */
	static void draw(Gfx g, OCard cd, long nanos) {
		if (!active() || cd == null) return;
		boolean b = buttons();
		for (OCardBase layer : new OCardBase[] {cd.bg, cd}) {
			if (layer == null || layer.partsList == null) continue;
			for (OObject p : layer.partsList) {
				if (b ? !(p instanceof OButton) : !(p instanceof OField)) continue;
				int style = p instanceof OButton ob ? ob.style : ((OField) p).style;
				if (!p.isShown() || style != 1 && style != 2) continue;
				g.setColor(CardRenderer.BLACK);
				g.drawRect(p.left, p.top, p.width - 1, p.height - 1);
			}
		}
		OObject s = selection();
		if (s == null) return;
		int phase = (int) ((nanos / 80_000_000L) % 8);
		int l = s.left, t = s.top, w = Math.max(1, s.width), h = Math.max(1, s.height);
		// round the edge pixel by pixel: the dash a point is in by its distance along the frame
		int k = 0;
		for (int i = 0; i < w; i++, k++) ant(g, l + i, t, k, phase);
		for (int j = 1; j < h; j++, k++) ant(g, l + w - 1, t + j, k, phase);
		for (int i = w - 2; i >= 0; i--, k++) ant(g, l + i, t + h - 1, k, phase);
		for (int j = h - 2; j >= 1; j--, k++) ant(g, l, t + j, k, phase);
	}
	private static void ant(Gfx g, int x, int y, int k, int phase) {
		g.setColor(((k + 8 - phase) & 4) == 0 ? CardRenderer.BLACK : CardRenderer.WHITE);
		g.fillRect(x, y, 1, 1);
	}
}

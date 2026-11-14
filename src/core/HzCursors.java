import java.util.HashMap;
import java.util.Map;

/**
 * set the cursor in the browser player (P2): HyperCard's cursor names and the stack's own cursors as a CSS cursor for the
 * page (PlayerUiHost.cursorSetters). As the desktop (ToolsUI.cursorSetters): the named cursors are CURS IDs (hand 128,
 * ibeam 1, cross 2, plus 3, watch 4) and the stack's CURS of that ID shows when it has one, else the browser's own cursor
 * like it; busy and arrow are the browser's; none hides it. Another name or ID is the stack's cursor of that name or ID,
 * its colour crsr before its CURS (Rsrc.stackCursor), in this stack or a stack in use (not Home), at the hot spot its
 * resource records; a cursor it does not have changes nothing (null), as HyperCard.
 */
final class HzCursors {
	private HzCursors() {}

	/** The CSS cursor for set the cursor to value, or null to leave the cursor as it is */
	static String css(OStack st, String value) {
		String v = value == null ? "" : value.trim().toLowerCase();
		switch (v) {
			case "hand": case "browse": return orBrowser(st, 128, "pointer");
			case "watch": return orBrowser(st, 4, "wait");
			case "busy": return "wait";
			case "arrow": return "default";
			case "cross": return orBrowser(st, 2, "crosshair");
			case "plus": return orBrowser(st, 3, "cell");
			case "ibeam": return orBrowser(st, 1, "text");
			case "none": return "none";
			default: break;
		}
		if (st == null || st.rsrc == null) return null;
		// by name (its crsr first), else by ID
		Rsrc.rsrcClass named = st.rsrc.findByNameAll(value.trim(), "CURS");
		if (named != null) { String c = image(ownerOf(st, named), named); if (c != null) return c; }
		if (v.matches("[0-9]+")) {
			int id;
			try { id = Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
			String c = stackCursor(st, id);
			if (c != null) return c;
			// classic Mac OS's own cursors (TUtil.CLASSIC_MAC_CURSOR_IDS)
			switch (id) {
				case 0: return "default";
				case 1: return "text";
				case 2: case 3: return "crosshair";
				case 4: return "wait";
				default: return null;
			}
		}
		return null;
	}

	private static String orBrowser(OStack st, int id, String browser) {
		String c = stackCursor(st, id);
		return c != null ? c : browser;
	}

	/** The stack's crsr or CURS of that ID (this stack, then the stacks in use, never Home), as a CSS cursor, or null */
	static String stackCursor(OStack st, int id) {
		if (st == null) return null;
		java.util.List<OStack> stacks = new java.util.ArrayList<>();
		stacks.add(st);
		if (st.usingStacks != null) stacks.addAll(st.usingStacks);
		for (OStack s : stacks) {
			if (s == null || s == HZ.home || s.rsrc == null) continue;
			Rsrc.rsrcClass r = s.rsrc.rsrcIdMap.get("crsr" + id);
			if (r == null) r = s.rsrc.rsrcIdMap.get("CURS" + id);
			if (r == null) continue;
			String c = image(s, r);
			if (c != null) return c;
		}
		return null;
	}

	/** The stack (of st and those in use) whose resources hold r */
	private static OStack ownerOf(OStack st, Rsrc.rsrcClass r) {
		if (st.rsrc.rsrcIdMap.containsValue(r)) return st;
		if (st.usingStacks != null) for (OStack u : st.usingStacks) if (u != null && u.rsrc != null && u.rsrc.rsrcIdMap.containsValue(r)) return u;
		return st;
	}

	private static final Map<String, String> cache = new HashMap<>();

	/** The cursor resource's picture as a CSS cursor at its hot spot, or null when it cannot be read */
	private static String image(OStack s, Rsrc.rsrcClass r) {
		String key = System.identityHashCode(s) + "/" + r.filename;
		synchronized (cache) { if (cache.containsKey(key)) return cache.get(key); }
		byte[] b = HzImages.bytes(s, r.filename);
		HzImage img = null;
		try { img = b == null ? null : r.type.equals("crsr") ? crsr(b) : curs(b); } catch (RuntimeException e) { img = null; }
		String css = null;
		if (img != null) {
			int hx = Math.max(0, Math.min(img.width - 1, r.hotsporleft)), hy = Math.max(0, Math.min(img.height - 1, r.hotsportop));
			css = "url(\"data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(HzPng.encode(img)) + "\") " + hx + " " + hy + ", auto";
		}
		synchronized (cache) { cache.put(key, css); }
		return css;
	}

	/** A CURS: 16 rows of 16 one-bit pixels, then the mask. Black where both are set, white where only the mask is,
	 *  transparent where neither, and black where only the data is (QuickDraw inverts what is under it there). */
	static HzImage curs(byte[] b) {
		if (b.length < 64) return null;
		HzImage img = new HzImage(16, 16);
		for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
			boolean d = bit(b, y * 2, x), m = bit(b, 32 + y * 2, x);
			img.argb[y * 16 + x] = m ? (d ? 0xFF000000 : 0xFFFFFFFF) : d ? 0xFF000000 : 0;
		}
		return img;
	}

	/** A crsr (CCrsr): its colour pixel map (crsrMap, crsrData) where the one-bit mask is set; where it is not, transparent
	 *  or (the one-bit data set) black. Falls back to the one-bit cursor in it when the colour part cannot be read. */
	static HzImage crsr(byte[] b) {
		if (b.length < 86) return null;
		byte[] one = new byte[64];
		System.arraycopy(b, 18, one, 0, 64); // crsr1Data, crsrMask
		HzImage mono = curs(one);
		try {
			int map = s4(b, 2), data = s4(b, 6);
			int rowBytes = u2(b, map + 4) & 0x3FFF;
			int top = (short) u2(b, map + 6), left = (short) u2(b, map + 8), bottom = (short) u2(b, map + 10), right = (short) u2(b, map + 12);
			int pixelSize = u2(b, map + 32), table = s4(b, map + 42);
			int w = right - left, h = bottom - top;
			if (w != 16 || h != 16 || rowBytes == 0 || data + rowBytes * h > b.length || pixelSize < 1 || pixelSize > 8 && pixelSize != 16 && pixelSize != 32) return mono;
			int[] palette = new int[256];
			if (pixelSize <= 8) {
				int n = u2(b, table + 6) + 1;
				for (int i = 0; i < n && table + 8 + i * 8 + 8 <= b.length; i++) {
					int o = table + 8 + i * 8, value = u2(b, o) & 0xFF;
					palette[value] = 0xFF000000 | (b[o + 2] & 0xFF) << 16 | (b[o + 4] & 0xFF) << 8 | (b[o + 6] & 0xFF);
				}
			}
			HzImage img = new HzImage(16, 16);
			for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
				boolean m = bit(b, 50 + y * 2, x), d = bit(b, 18 + y * 2, x);
				int c;
				if (!m) c = d ? 0xFF000000 : 0;
				else if (pixelSize <= 8) {
					int bitAt = x * pixelSize, v = (b[data + y * rowBytes + bitAt / 8] & 0xFF) >> (8 - pixelSize - bitAt % 8) & ((1 << pixelSize) - 1);
					c = palette[v];
				} else if (pixelSize == 16) {
					int v = u2(b, data + y * rowBytes + x * 2);
					c = 0xFF000000 | ((v >> 10) & 31) * 255 / 31 << 16 | ((v >> 5) & 31) * 255 / 31 << 8 | (v & 31) * 255 / 31;
				} else {
					int o = data + y * rowBytes + x * 4;
					c = 0xFF000000 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF);
				}
				img.argb[y * 16 + x] = c;
			}
			return img;
		} catch (RuntimeException e) {
			return mono;
		}
	}

	private static boolean bit(byte[] b, int row, int x) { return ((b[row + x / 8] >> (7 - x % 8)) & 1) != 0; }
	private static int u2(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }
	private static int s4(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }
}

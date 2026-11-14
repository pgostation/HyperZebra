import java.util.ArrayList;
import java.util.List;

/**
 * HyperCard's Picture and Palette XCMD windows in the browser player (GPictWindow and WindowUI's picture/palette commands on
 * the desktop): windows over the card, drawn as the windows of 68K externals are (CardOverlay.Window), with the card's
 * coordinates as their screen. picture shows a PICT resource or a picture file of the stack; palette a PLTE resource's
 * picture with its buttons, which send their messages. Scripts reach them as window "name" (OWindow.pict): show, hide,
 * close, loc, rect, visible, scroll, hilitedButton, commands, buttonCount. The Movie XCMD's windows are these too (MovieWindows).
 */
final class PictureWindows {
	private PictureWindows() {}

	/** One window */
	static final class Pict {
		final String name;
		/** the picture (a movie's: its frame now, MovieWindows) */
		HzImage image;
		/** a movie's window (MovieWindows), else null */
		MovieWindows.Playback movie;
		final boolean palette;
		/** CardOverlay's frame kind */
		final int frame;
		final boolean closeBox;
		final String title;
		/** the window's top-left with its frame, card coordinates */
		int left, top;
		/** the picture's part on show (its content area) */
		int width, height;
		boolean visible;
		int scrollX, scrollY;
		/** the picture's scale: drawn at 2^scale (-5..5) from the content's top-left less the scroll, the window keeps its size
		 *  (GPictWindow's MyLabel3: 秘密結社ゲーム's cover grows from -5 to 2 and back) */
		int scale;
		/** palette: the buttons' rectangles in the picture {x, y, w, h}, their messages; HyperCard's PLTE hilite type (-1: lit
		 *  while pressed) and mode (0 the whole button, 1 its edge); the lit button (1-based, 0 none) and the one held down */
		final List<int[]> buttons = new ArrayList<>();
		final List<String> commands = new ArrayList<>();
		int hiliteType, hiliteMode, hilited, pressed;
		OWindow window;
		private HzImage shown, shownFrom;
		private String shownKey;

		Pict(String name, HzImage image, boolean palette, int frame, boolean closeBox, String title) {
			this.name = name; this.image = image; this.palette = palette; this.frame = frame; this.closeBox = closeBox;
			this.title = title == null ? "" : title;
			width = image.width; height = image.height;
		}
		/** left/top margins of the content in the window, and the window's extra width and height */
		int marginX() { return frame == CardOverlay.NO_FRAME ? 0 : frame == CardOverlay.DIALOG ? CardOverlay.DIALOG_MARGIN : 1; }
		int marginY() { return marginX() + CardOverlay.barHeight(frame); }
		int outerWidth() { return width + marginX() * 2 + (frame == CardOverlay.SHADOW ? 2 : 0); }
		int outerHeight() { return height + marginY() + marginX() + (frame == CardOverlay.SHADOW ? 2 : 0); }
		int contentX() { return left + marginX(); }
		int contentY() { return top + marginY(); }

		/** The window as the card shows it: the picture's part on show over white, the lit button inverted */
		CardOverlay.Window overlay() {
			String key = width + "," + height + "," + scrollX + "," + scrollY + "," + hilited + "," + pressed + "," + scale;
			if (shown == null || !key.equals(shownKey) || shownFrom != image) {
				int[] px = new int[Math.max(1, width) * Math.max(1, height)];
				java.util.Arrays.fill(px, 0xFFFFFFFF);
				// (a picture at a scale: each pixel from its place in the picture, nearest, as Java2D's drawImage does)
				double f = Math.pow(2, scale);
				int drawnW = (int) (image.width * f), drawnH = (int) (image.height * f);
				for (int y = 0; y < height; y++) {
					int dy = y + scrollY;
					if (dy < 0 || dy >= drawnH) continue;
					int sy = scale == 0 ? dy : Math.min(image.height - 1, (int) (dy / f));
					for (int x = 0; x < width; x++) {
						int dx = x + scrollX;
						if (dx < 0 || dx >= drawnW) continue;
						int sx = scale == 0 ? dx : Math.min(image.width - 1, (int) (dx / f));
						int p = image.argb[sy * image.width + sx], a = p >>> 24;
						if (a == 0xFF) { px[y * width + x] = p; continue; }
						int r = ((p >> 16 & 255) * a + 255 * (255 - a)) / 255, g = ((p >> 8 & 255) * a + 255 * (255 - a)) / 255, b = ((p & 255) * a + 255 * (255 - a)) / 255;
						px[y * width + x] = 0xFF000000 | r << 16 | g << 8 | b;
					}
				}
				int lit = pressed > 0 ? pressed : hilited;
				if (palette && lit > 0 && lit <= buttons.size()) invert(px, buttons.get(lit - 1));
				shown = new HzImage(Math.max(1, width), Math.max(1, height), px);
				shownKey = key;
				shownFrom = image;
			}
			return new CardOverlay.Window(contentX(), contentY(), shown, title, frame, closeBox, true);
		}
		private void invert(int[] px, int[] b) {
			int edge = 1;
			for (int y = Math.max(0, b[1] - scrollY); y < Math.min(height, b[1] + b[3] - scrollY); y++)
				for (int x = Math.max(0, b[0] - scrollX); x < Math.min(width, b[0] + b[2] - scrollX); x++) {
					int bx = x + scrollX - b[0], by = y + scrollY - b[1];
					boolean onEdge = bx < edge || by < edge || bx >= b[2] - edge || by >= b[3] - edge;
					if (hiliteMode == 1 && !onEdge) continue;
					int i = y * width + x;
					px[i] = px[i] & 0xFF000000 | ~px[i] & 0xFFFFFF;
				}
		}
		/** The palette button at the card point (x, y), 1-based, or 0 */
		int buttonAt(int x, int y) {
			int px = x - contentX() + scrollX, py = y - contentY() + scrollY;
			for (int i = buttons.size() - 1; i >= 0; i--) { int[] b = buttons.get(i); if (px >= b[0] && py >= b[1] && px < b[0] + b[2] && py < b[1] + b[3]) return i + 1; }
			return 0;
		}
		boolean contains(int x, int y) { return x >= left && y >= top && x < left + outerWidth() && y < top + outerHeight(); }
		boolean inBar(int x, int y) { return contains(x, y) && CardOverlay.barHeight(frame) > 0 && y < top + 1 + CardOverlay.barHeight(frame); }
		boolean inCloseBox(int x, int y) {
			if (!closeBox || !inBar(x, y)) return false;
			boolean small = frame == CardOverlay.WINDOID_BAR;
			int cx = small ? contentX() + 1 : contentX() + 7, cy = small ? top + 2 : top + 4, cs = small ? 7 : 11;
			return x >= cx - 1 && x <= cx + cs + 1 && y >= cy - 1 && y <= cy + cs + 1;
		}
		boolean inContent(int x, int y) { return x >= contentX() && y >= contentY() && x < contentX() + width && y < contentY() + height; }

		void setHilited(int n) { if (palette && hiliteType >= 0) { hilited = n < 0 || n > buttons.size() ? 0 : n; changed(); } }

		/** A script property, or null when it is not one of the window's (TUtil.getProperty; WindowUI.getPictureWindowProperty) */
		String property(String prpty) {
			int r = left + outerWidth(), b = top + outerHeight();
			switch (prpty) {
				case "left": return Integer.toString(left);
				case "top": return Integer.toString(top);
				case "right": return Integer.toString(r);
				case "bottom": return Integer.toString(b);
				case "topleft": case "loc": case "location": case "globalloc": return left + "," + top; // a window's loc is its top-left
				case "botright": case "bottomright": return r + "," + b;
				case "rect": case "rectangle": case "globalrect": return left + "," + top + "," + r + "," + b;
				case "width": return Integer.toString(outerWidth());
				case "height": return Integer.toString(outerHeight());
				case "visible": return Boolean.toString(visible);
				case "dithering": return "false";
				case "picturewidth": return Integer.toString(image.width);
				case "pictureheight": return Integer.toString(image.height);
				case "scroll": return scrollX + "," + scrollY;
				case "scale": return Integer.toString(scale);
			}
			if (palette) switch (prpty) {
				case "hilitedbutton": return Integer.toString(hiliteType < 0 ? -1 : hilited);
				case "commands": return String.join("\n", commands);
				case "buttoncount": return Integer.toString(buttons.size());
			}
			return null;
		}
		void moveTo(int l, int t) { left = l; top = t; keepOnCard(false); changed(); }
		/** set the rect: the window's frame there, the picture's part on show the rest */
		void setRect(int l, int t, int r, int b) {
			left = l; top = t;
			width = Math.max(1, r - l - (outerWidth() - width)); height = Math.max(1, b - t - (outerHeight() - height));
			keepOnCard(false);
			changed();
		}
		/** Where the window may be. Drawn on the card, the card is all the screen it has: one placed past its edge (on the desktop
		 *  a window of its own, beside or below the card window: HyperPiece 2's palette "HyperPieceNav" at 265,300 on a 416x290
		 *  card) is moved in until it is all on the card, on each side it fits; a window larger than the card keeps its place
		 *  that way. A palette the page floats (floating) may be anywhere on the page, as a palette beside the card window on
		 *  the desktop: opened (whole) all on it where it fits, moved so that a grip of its title bar stays on it. */
		void keepOnCard(boolean whole) {
			OStack st = HZ.stack();
			if (st == null || st.width <= 0 || st.height <= 0) return;
			int w = outerWidth(), h = outerHeight();
			if (floats()) {
				int[] a = screenArea(st);
				if (whole) {
					if (w <= a[2] - a[0]) left = Math.max(a[0], Math.min(left, a[2] - w));
					if (h <= a[3] - a[1]) top = Math.max(a[1], Math.min(top, a[3] - h));
				} else {
					int grip = Math.min(w, 40), bar = Math.max(8, CardOverlay.barHeight(frame));
					left = Math.max(a[0] - (w - grip), Math.min(left, a[2] - grip));
					top = Math.max(a[1], Math.min(top, a[3] - bar));
				}
				return;
			}
			if (w <= st.width) left = Math.max(0, Math.min(left, st.width - w));
			if (h <= st.height) top = Math.max(0, Math.min(top, st.height - h));
		}
		/** A palette the page shows over itself, not on the card (PlayerHost.floatsPalettes) */
		boolean floats() { return floating && palette && movie == null && !underModal; }
		/** what the player last sent the page of this floating palette (PlayerLoop.sendFloating): its pixels */
		Object sentPixels;
		void setVisible(boolean v) {
			visible = v;
			if (v) synchronized (list) { list.remove(this); list.add(this); } // shown: in front
			changed();
		}
		void setScroll(int x, int y) { scrollX = x; scrollY = y; changed(); }
		void setScale(int v) { scale = Math.max(-5, Math.min(5, v)); changed(); }
	}

	/** back to front */
	static final List<Pict> list = new ArrayList<>();

	/** The windows on show, back to front, as the card shows them */
	static List<CardOverlay.Window> shown() {
		List<CardOverlay.Window> out = new ArrayList<>();
		synchronized (list) { for (Pict p : list) if (p.visible && !p.floats()) out.add(p.overlay()); }
		return out;
	}
	/** The page shows the palettes over itself (the web player: they may leave the card, as palettes beside the card window on
	 *  the desktop); else they are drawn on the card with the other windows */
	static volatile boolean floating;
	/** An external's modal dialog is up (PlayerLoop.draw): the palettes float no more but show on the card under it, as
	 *  the Mac puts a modal dialog in front of HyperCard's palettes */
	static volatile boolean underModal;
	/** The palettes the page floats, back to front */
	static List<Pict> floatingShown() {
		List<Pict> out = new ArrayList<>();
		synchronized (list) { for (Pict p : list) if (p.visible && p.floats()) out.add(p); }
		return out;
	}
	/** The page's window in card coordinates {left, top, right, bottom} (PlayerHost.screenArea), else the card */
	static int[] screenArea(OStack st) {
		PlayerLoop p = PlayerLoop.current;
		int[] a = p == null ? null : p.host.screenArea();
		return a != null && a[2] > a[0] && a[3] > a[1] ? a : new int[] {0, 0, st.width, st.height};
	}
	static boolean any() { synchronized (list) { for (Pict p : list) if (p.visible) return true; } return false; }

	static void changed() { PlayerLoop p = PlayerLoop.current; if (p != null) p.markDirty(); }

	/** A new window, in front: the script's object for it too */
	static Pict add(Pict p) {
		p.keepOnCard(true);
		synchronized (list) { list.add(p); }
		p.window = OWindow.ofPicture(p);
		changed();
		return p;
	}
	/** A movie's window, in front: its script object is the movie's (MovieWindows) */
	static Pict addMovie(Pict p, OWindow window) {
		synchronized (list) { list.add(p); }
		p.window = window;
		changed();
		return p;
	}
	/** The window goes (closed, or its stack closed) */
	static void remove(Pict p) {
		synchronized (list) { list.remove(p); }
		if (p.window != null) OWindow.list.remove(p.window);
		changed();
	}
	static Pict palette(String name) {
		synchronized (list) { for (int i = list.size() - 1; i >= 0; i--) { Pict p = list.get(i); if (p.palette && p.name.equalsIgnoreCase(name)) return p; } }
		return null;
	}
	/** The stack closes: its windows go, without closePicture/closePalette (GPictWindow.closeStackWindows) */
	static void stackClosing() {
		List<Pict> all;
		synchronized (list) { all = new ArrayList<>(list); }
		for (Pict p : all) remove(p);
	}

	/** The frame of a picture window's style (picture's third parameter) */
	static int pictureFrame(String style) {
		switch (style.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "rect": return CardOverlay.NO_FRAME;
			case "plain": return CardOverlay.LINE;
			case "shadow": return CardOverlay.SHADOW;
			case "dialog": return CardOverlay.DIALOG;
			case "windoid": return CardOverlay.WINDOID_BAR;
			default: return CardOverlay.TITLE_BAR; // zoom, document
		}
	}
	/** A PLTE's window definition as the desktop's GPictWindow draws it: boxes (1-3), titled windoids, plain windoids */
	static Pict newPalette(Rsrc.PlteClass plte, HzImage image) {
		int wdef = plte.windowDef;
		boolean box = wdef >= 1 && wdef <= 3;
		boolean titled = wdef == 2054 || wdef == 4 || wdef == 16 || wdef == 1984 || wdef == 2064 || (wdef >= 2048 && wdef <= 2063 && (wdef & 2) != 0);
		int frame = box ? (wdef == 3 ? CardOverlay.SHADOW : CardOverlay.LINE) : CardOverlay.WINDOID_BAR;
		Pict p = new Pict(plte.name, image, true, frame, !box && wdef != 2064, titled ? plte.name : "");
		p.hiliteType = plte.hiliteType; p.hiliteMode = plte.btnType;
		for (Rsrc.plteBtnObject o : plte.objList) {
			java.awt.Rectangle r = TUtil.paletteButtonBounds(o.rect, plte.pictHV);
			p.buttons.add(new int[] {r.x, r.y, r.width, r.height});
			p.commands.add(o.message);
		}
		if (plte.hiliteType > 0) p.hilited = plte.hiliteType <= p.buttons.size() ? plte.hiliteType : 0;
		return p;
	}

	/** A PICT that keeps its QuickDraw origin as empty space at its top left (WindowUI.normalizePaletteImage) */
	static HzImage normalized(HzImage src, java.awt.Point origin) {
		if (src == null || origin == null || origin.x <= 0 && origin.y <= 0 || origin.x < 0 || origin.y < 0 || origin.x >= src.width || origin.y >= src.height) return src;
		int minX = src.width, minY = src.height;
		for (int y = 0; y < src.height; y++) for (int x = 0; x < src.width; x++) if ((src.argb[y * src.width + x] >>> 24) != 0) { if (x < minX) minX = x; if (y < minY) minY = y; }
		if (minX < origin.x || minY < origin.y) return src;
		int w = src.width - origin.x, h = src.height - origin.y;
		int[] px = new int[w * h];
		for (int y = 0; y < h; y++) System.arraycopy(src.argb, (y + origin.y) * src.width + origin.x, px, y * w, w);
		return new HzImage(w, h, px);
	}

	// ---- the pointer (PlayerLoop.event)
	private static Pict dragging, pressedIn;
	private static int dragX, dragY;

	/** A press or release at (x, y): true when it fell on one of these windows, which takes it. Clicks need the stack's handlers
	 *  idle (whileBusy: the palette's buttons and the close box wait, as a click does on the card) */
	static boolean press(boolean down, int x, int y, OCard cd, boolean whileBusy) {
		if (!down) {
			Pict p = pressedIn;
			pressedIn = null;
			if (dragging != null) { dragging.moveTo(x - dragX, y - dragY); dragging = null; return true; }
			if (p == null) return false;
			if (p.palette) {
				int b = p.pressed;
				p.pressed = 0; changed();
				if (b > 0 && p.buttonAt(x, y) == b && cd != null) {
					if (p.hiliteType >= 0) p.setHilited(b);
					String message = p.commands.get(b - 1);
					if (!message.trim().isEmpty()) TTalk.CallMessage(message, "", cd, false, true);
				}
			} else if (p.movie != null) {
				PlayerUiHost.mouseClicked = true;
				MovieWindows.mouse(p.movie, "mouseUpInMovie", x - p.contentX(), y - p.contentY(), cd);
			} else {
				PlayerUiHost.mouseClicked = true;
				if (cd != null && p.window != null) TTalk.CallMessage("mouseUpInPicture", new String[] {p.name, (x - p.contentX() + p.scrollX) + "," + (y - p.contentY() + p.scrollY)}, cd);
			}
			return true;
		}
		Pict hit = null;
		synchronized (list) { for (int i = list.size() - 1; i >= 0; i--) { Pict p = list.get(i); if (p.visible && p.contains(x, y)) { hit = p; break; } } }
		if (hit == null) return false;
		if (hit.inCloseBox(x, y)) {
			if (!whileBusy) close(hit, cd);
			return true;
		}
		if (hit.inBar(x, y)) { dragging = hit; dragX = x - hit.left; dragY = y - hit.top; return true; }
		if (!hit.inContent(x, y)) return true;
		pressedIn = hit;
		if (hit.palette) { hit.pressed = whileBusy ? 0 : hit.buttonAt(x, y); changed(); }
		else if (hit.movie != null) MovieWindows.mouse(hit.movie, "mouseDownInMovie", x - hit.contentX(), y - hit.contentY(), cd);
		else if (cd != null) TTalk.CallMessage("mouseDownInPicture", new String[] {hit.name, (x - hit.contentX() + hit.scrollX) + "," + (y - hit.contentY() + hit.scrollY)}, cd);
		return true;
	}
	/** The pointer moved: true while it drags a window by its title bar */
	static boolean pointer(int x, int y) {
		if (dragging == null) return false;
		dragging.moveTo(x - dragX, y - dragY);
		return true;
	}

	/** close window, the close box: closePalette goes first, its handler still finding the window (GPictWindow.closePalette);
	 *  a picture's closePicture after */
	static void close(Pict p, OCard cd) {
		if (p.movie != null) { MovieWindows.dispose(p.movie, true); return; }
		if (p.palette && cd != null && p.window != null) {
			TTalk.quietErrors++;
			try { TTalk.runExternalStatement("closePalette " + TTalk.quoteLiteral(p.name) + "," + p.window.id, cd); }
			catch (xTalkException e) { System.out.println("closePalette: " + e.getMessage()); }
			finally { TTalk.quietErrors--; }
		}
		remove(p);
		if (!p.palette && cd != null) pictureMessage("closePicture", p.name, cd);
	}
	/** openPicture / closePicture before the next statement (GPictWindow.sendPictureMessage) */
	static void pictureMessage(String message, String name, OCard cd) {
		TTalk.quietErrors++;
		try { TTalk.runExternalStatement(message + " " + TTalk.quoteLiteral(name), cd); }
		catch (xTalkException e) { System.out.println(message + ": " + e.getMessage()); }
		finally { TTalk.quietErrors--; }
	}
}

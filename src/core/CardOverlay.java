/**
 * The player's card window as 68K externals draw on it (P4-4): what they drew over the card (CardWindow68k's overlay, card
 * pixels, transparent where nothing), drawn last by {@link CardRenderer}, and the screen held while HyperCard makes a change
 * an external redraws (CardWindow68k.holdScreen: the card shows that picture instead). The 68K player sets {@link #source};
 * the core player has none, and nothing is drawn over its cards.
 */
final class CardOverlay {
	private CardOverlay() {}
	/** Gives what the externals drew now (null: nothing) and their windows in front of the card, back to front (P4-5). */
	interface Source {
		HzImage drawn();
		default java.util.List<Window> windows() { return java.util.Collections.emptyList(); }
		/** The video driver's gamma ramps an external set (768 entries: red, green, blue), null for none */
		default int[] gamma() { return null; }
	}
	static volatile Source source;
	/** The card window as it showed when it was held, or null. */
	static volatile HzImage held;

	static HzImage drawn() { Source s = source; return s == null ? null : s.drawn(); }
	static java.util.List<Window> windows() { Source s = source; return s == null ? java.util.Collections.emptyList() : s.windows(); }
	/** An external's modal dialog is on show: it covers the Palette windows (Sedit3.1's About, UxAnswer: the Tool palette
	 *  floated over the page hid the dialog's OK button, so the dialog could not be closed by a click) */
	static boolean modalShown() { for (Window w : windows()) if (w.modal) return true; return false; }
	/** x,y (card coordinates) is on an external's modal dialog: a press there is the dialog's, not a palette's under it */
	static boolean modalAt(int x, int y) {
		for (Window w : windows()) if (w.modal) { int[] b = w.bounds(); if (x >= b[0] && y >= b[1] && x < b[2] && y < b[3]) return true; }
		return false;
	}
	/** The screen as the gamma ramps show it (CardWindow68k.gamma: Best of HC Externals' Fade), in place; false with none */
	static boolean throughGamma(Gfx g, int w, int h) {
		Source s = source; int[] lut = s == null ? null : s.gamma();
		if (lut == null || lut.length < 768) return false;
		int[] px = g.getPixels(0, 0, w, h);
		for (int i = 0; i < px.length; i++) { int p = px[i]; px[i] = p & 0xFF000000 | lut[p >>> 16 & 255] << 16 | lut[256 + (p >>> 8 & 255)] << 8 | lut[512 + (p & 255)]; }
		g.putPixels(px, 0, 0, w, h);
		return true;
	}

	/** The frame the player draws round an external's window: none (a frame the window draws itself), a line, a line with a
	 *  shadow, a document window's title bar, a windoid's (HyperCard's palettes), a modal dialog's (dBoxProc: a picture window
	 *  of style dialog, PictureWindows) */
	static final int NO_FRAME = 0, LINE = 1, SHADOW = 2, TITLE_BAR = 3, WINDOID_BAR = 4, DIALOG = 5;
	/** dBoxProc's frame round its content */
	static final int DIALOG_MARGIN = 8;
	/** The title bar's height of the frame kind */
	static int barHeight(int frame) { return frame == TITLE_BAR ? 18 : frame == WINDOID_BAR ? 10 : 0; }

	/** An external's window over the card: where its pixels go (card coordinates), the pixels, its title and frame */
	static final class Window {
		final int x, y; final HzImage pixels; final String title; final int frame;
		/** closeBox: a close box in its title bar; active: it has the keyboard (its title bar striped, as a front window's) */
		final boolean closeBox, active;
		/** a modal dialog (dBoxProc, plainDBox, altDBoxProc, movableDBoxProc): in front of everything, the Palette windows too */
		final boolean modal;
		Window(int x, int y, HzImage pixels, String title, int frame) { this(x, y, pixels, title, frame, false, true); }
		Window(int x, int y, HzImage pixels, String title, int frame, boolean closeBox, boolean active) { this(x, y, pixels, title, frame, closeBox, active, false); }
		Window(int x, int y, HzImage pixels, String title, int frame, boolean closeBox, boolean active, boolean modal) {
			this.x = x; this.y = y; this.pixels = pixels; this.title = title == null ? "" : title; this.frame = frame; this.closeBox = closeBox; this.active = active || frame == WINDOID_BAR;
			this.modal = modal;
		}
		/** {left, top, right, bottom} with the frame, card coordinates */
		int[] bounds() {
			int bar = barHeight(frame), m = frame == NO_FRAME ? 0 : frame == DIALOG ? DIALOG_MARGIN : 1, sh = frame == SHADOW ? 2 : 0;
			return new int[] {x - m, y - m - bar, x + pixels.width + m + sh, y + pixels.height + m + sh};
		}
	}
}

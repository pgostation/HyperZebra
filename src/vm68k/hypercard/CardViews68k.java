import java.awt.image.BufferedImage;
import java.util.List;

/**
 * HyperCard's windows as the host shows them, for {@link CardWindow68k} (P4): the card, the message box and the picture
 * command's windows - their sizes, whether they show, their pixels - and what showing an external's drawing over them takes.
 * The desktop's CardViews68kSwing (found by name) does it with the Swing components as CardWindow68k did before. The views are
 * the host's objects (a JComponent, a GPictWindow on the desktop), passed back as they came.
 *
 * These defaults are the browser player's (P4-4): the card window is the player's card at 1x (PlayerLoop draws it with
 * CardRenderer), what externals draw over it goes to {@link CardOverlay} and the player draws it last, also through a locked
 * screen when CardWindow68k says so. There is no message box or picture window for externals here.
 */
class CardViews68k {
	static final CardViews68k IMPL = find();
	private static CardViews68k find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new CardViews68k();
		try { return (CardViews68k) Class.forName("CardViews68kSwing").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new CardViews68k(); }
	}

	/** The card window's view here (there is one card window). */
	static final Object CARD = new Object();

	CardViews68k() {
		// the player's (the desktop draws CardWindow68k.overlay itself, and its external windows are frames of their own)
		if (getClass() == CardViews68k.class) CardOverlay.source = new CardOverlay.Source() {
			public HzImage drawn() { return overlayNow(); }
			public java.util.List<CardOverlay.Window> windows() { return PlayerVmHost.shownWindows(); }
			public int[] gamma() { return CardWindow68k.gamma; }
		};
	}

	// ---- the card window and the message box
	/** Whether the card of this stack is on show in a laid out window (an external of it gets the card window). */
	boolean cardOnShow(OStack stack) { return stack != null && HZ.stack() == stack && stack.curCard != null && stack.width > 0 && stack.height > 0; }
	/** The card's view (PCARD.pc.mainPane), null when none. */
	Object cardView() { return HZ.stack() == null ? null : CARD; }
	/** The card's zoom in percent (PCARD.scalePercent). */
	int cardScale() { return 100; }
	/** The colour (RGB) the card draws a list field's selected lines and an open field's selection in (CardRenderer.HIGHLIGHT;
	 *  the desktop: the system's text highlight), -1 when none. */
	int textHighlight() { return CardRenderer.HIGHLIGHT & 0xffffff; }
	/** The message box's view here: HyperCard's box, in the WindowList also while hidden, as on the desktop (the page shows its
	 *  own box; what externals draw in this one is shown over it) */
	static final Object MESSAGE = new Object();
	/** The message box's content (GMsg.msg's content pane), null when there is no message box. */
	Object messageView() { return MESSAGE; }
	/** A view's size in host pixels {width, height}. */
	int[] viewSize(Object view) {
		OStack st = HZ.stack();
		if (view == MESSAGE) return new int[] {GMsg.WIDTH, GMsg.HEIGHT};
		return view == CARD && st != null ? new int[] {st.width, st.height} : new int[] {0, 0};
	}
	/** Whether a view is showing. */
	boolean viewShowing(Object view) { return view == MESSAGE ? UiHost.get().msgVisible() : view == CARD && HZ.stack() != null; }
	/** A view's pixels in logical coordinates (drawn at scale percent of that), {width, height} in size; bare: without the card's overlay. */
	int[] capture(Object view, int scale, int[] size, boolean bare) {
		if (view == MESSAGE) {
			if (!UiHost.get().msgVisible()) return null;
			int[] px = PlayerLoop.messageBoxPixels(GMsg.WIDTH, GMsg.HEIGHT, GMsg.BAR_HEIGHT, GMsg.TEXT_MARGIN);
			if (px != null) { size[0] = GMsg.WIDTH; size[1] = GMsg.HEIGHT; }
			return px;
		}
		return view == CARD ? PlayerLoop.cardPixels(!bare, size) : null;
	}
	/** While the screen is locked with a picture of it, that picture in logical pixels at the view's size; else null. */
	int[] lockedPicture(Object view, int scale, int width, int height) { return view == CARD ? PlayerLoop.lockedPixels(width, height) : null; }

	// ---- the picture command's windows
	/** The picture windows open now, in the window list's order (not movies, ones with a picture, laid out). */
	List<Object> pictureWindows() { return new java.util.ArrayList<Object>(); }
	String pictureName(Object picture) { return ""; }
	/** The view the picture is shown in (its label). */
	Object pictureView(Object picture) { return null; }
	boolean pictureUndecorated(Object picture) { return false; }
	/** An undecorated window with a border round its content (a dialog or plain window: HyperCard's windowKind 20). */
	boolean pictureFramed(Object picture) { return false; }
	boolean pictureDisplayable(Object picture) { return false; }
	boolean pictureVisible(Object picture) { return false; }
	/** The picture's own size {width, height}, null when it has none yet. */
	int[] pictureSize(Object picture) { return null; }
	/** The picture's own pixels (at 1x, before it is scrolled), width x height RGB. */
	int[] picturePixels(Object picture, int width, int height) { return null; }
	/** The picture's view on the host screen {x, y, width, height}, null when it is not showing. */
	int[] pictureOnScreen(Object picture) { return null; }

	// ---- what externals drew, shown
	/** The message box shows CardWindow68k.messageOverlay (from now on). */
	void installMessageGlass() {}
	/** The message box repaints its overlay. */
	void repaintMessage() {
		PlayerHost host = PlayerInput.host; if (host == null) return;
		BufferedImage o = CardWindow68k.messageOverlay;
		if (o == null) { host.messageOverlay(null, 0, 0); return; }
		host.messageOverlay(o.getRGB(0, 0, o.getWidth(), o.getHeight(), null, 0, o.getWidth()), o.getWidth(), o.getHeight());
	}
	/** The picture window shows what externals draw in it: the glass that does (passed to showPictureOverlay). */
	Object installPictureGlass(Object picture) { return null; }
	void showPictureOverlay(Object glass, BufferedImage drawn) {}
	/** The card repaints with CardWindow68k.overlay, also while the screen is locked. */
	void repaintCardThroughLock() { takeOverlay(); PlayerLoop.externalDrew(true); }
	/** The card repaints. */
	void repaintCard() { takeOverlay(); PlayerLoop.externalDrew(false); }
	/** CardWindow68k.held takes the card as shown now (null: holdScreen finds none to hold), and releaseScreen drops it. */
	void holdScreen() { PlayerLoop.holdScreen(); }
	void releaseScreen() { CardWindow68k.held = null; PlayerLoop.releaseScreen(); }

	/** CardWindow68k.overlay as the player draws it: a copy taken when the card repaints (CardWindow68k fills its two images
	 *  in turn), dropped when the overlay is (cardRedrawn sets it to null without a repaint). */
	private BufferedImage overlaySeen;
	private HzImage overlayCopy, overlayKept;
	private void takeOverlay() {
		BufferedImage o = CardWindow68k.overlay;
		overlaySeen = o;
		if (o == null) { overlayCopy = null; return; }
		int w = o.getWidth(), h = o.getHeight();
		// the same picture repainted (CardWindow68k flushes many times a second): its pixels change in place
		HzImage copy = overlayKept != null && overlayKept.width == w && overlayKept.height == h ? overlayKept : (overlayKept = new HzImage(w, h));
		o.getRGB(0, 0, w, h, copy.argb, 0, w);
		copy.repainted();
		overlayCopy = copy;
	}
	private HzImage overlayNow() {
		BufferedImage o = CardWindow68k.overlay;
		if (o == null) return null;
		if (o != overlaySeen) takeOverlay();
		return overlayCopy;
	}

	// ---- the card window itself
	/** SetWTitle: the window of this stack's card takes the title. */
	void setCardTitle(OStack stack, String title) {}
	/** HideWindow/ShowWindow of this stack's card window. */
	void showCardWindow(OStack stack, boolean visible) {}
	/** Logical pixels as the window's (PCARD.toPhysical). */
	int toPhysical(int logical) { return logical; }
	/** The window shaped by an area of the card view's pixels (null: its whole rectangle); shaped is the region it stands for. */
	void shapeCardWindow(java.awt.geom.Area area, QdRegion shaped) {}
}

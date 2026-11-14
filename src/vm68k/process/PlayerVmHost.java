/**
 * The host of an external's VM in the browser player (P4-3): no host windows (NoWindowsVmHost), the player's input -
 * the button, the pointer at the card's place on the screen the external sees (the card window at (0, 0)), the modifier
 * keys and the keys held (PlayerInput, as Mac key codes in GetKeys' KeyMap). A poll (Button, GetMouse, WaitNextEvent's
 * pass) lets the player take the input that came and show the screen, as its ticks do for a running handler.
 *
 * P4-5: the external's windows (XWindows, its dialogs) are drawn over the card by the player ({@link #shownWindows}, the
 * card's coordinates are the screen's here): what the Window Manager tells the host of each - its place, its pixels in the
 * VM's memory, shown or hidden, its order - as MacAppHost keeps it for a Swing frame. One sent behind HyperCard's windows
 * (UxBack's backdrop) is not drawn. A press on one goes to the VM as a host click ({@link #pressAt}), not to the card.
 */
final class PlayerVmHost extends NoWindowsVmHost {
	PlayerVmHost(VmHostListener listener, M68kMemory memory, String name, boolean headless, boolean external) { super(listener, memory, name, headless, external); }

	@Override public boolean isMouseDown() { return PlayerInput.mouseDown(); }
	@Override public java.awt.Point mouseLocation() {
		java.awt.Rectangle card = UiHost.get().cardWindowRect();
		return new java.awt.Point(card.x + PlayerInput.mouseX(), card.y + PlayerInput.mouseY());
	}
	@Override public int modifiers() { return KeyCodes68k.macModifiers(PlayerInput.modifiers(), PlayerInput.mouseDown()); }
	@Override public boolean capsLocked() { return (PlayerInput.modifiers() & PlayerHost.CAPS_LOCK) != 0; }
	@Override public boolean isKeyDown(int keyCode) {
		int[] k = PlayerInput.heldKeys();
		for (int i = 0; i < k.length; i += 2) if (KeyCodes68k.macKeyCodeOfDom(k[i], k[i + 1]) == keyCode) return true;
		return false;
	}
	/** A 16-byte KeyMap (bit n of byte n/8 = key code n), as MacAppHost fills it. */
	@Override public void keyMap(byte[] map) {
		int[] k = PlayerInput.heldKeys();
		for (int i = 0; i < k.length; i += 2) { int c = KeyCodes68k.macKeyCodeOfDom(k[i], k[i + 1]); if (c >= 0 && c < 128) map[c >>> 3] |= (byte) (1 << (c & 7)); }
	}
	@Override public void eventCall() { UiHost.get().externalPoll(); }
	/** The page's colour dialog (Colorizing HyperCard's colorPicker XFCN: there was none, GetColor came back cancelled) */
	@Override public Integer pickColor(String prompt, int rgb) { return UiHost.get().pickColor(prompt, rgb); }

	/** Whether the button was down as HyperTalk last entered an external (MacAppHost's rule for PopUpMenuSelect) */
	private volatile boolean downAtEntry = true;
	@Override public void externalEntered() { downAtEntry = isMouseDown(); }
	/**
	 * PopUpMenuSelect: the page's pop-up list (as for a pop-up button), the item popUpItem at globalTop. As MacAppHost: an
	 * external that calls it with the button up, entered with the button up (UxPop from a mouseUp handler), gets 0 at once.
	 * Colorizing HyperCard's popUpMenu XFCN (Modes Demo) chose nothing: there was no menu.
	 */
	@Override public int popUpMenuSelect(MenuModel68k model, int globalLeft, int globalTop, int initialItem) {
		if (!isMouseDown() && !downAtEntry) return 0;
		java.awt.Rectangle card = UiHost.get().cardWindowRect();
		int x = globalLeft - card.x, y = globalTop - card.y;
		StringBuilder t = new StringBuilder();
		t.append(x).append(',').append(y).append(',').append(x + 1).append(',').append(y).append('\n');
		t.append(initialItem).append('\n');
		t.append("").append('\t').append(model.fontSize > 0 ? model.fontSize : 12).append('\t').append(0);
		for (ItemModel68k it : model.items) {
			t.append('\n');
			if (it.separator) { t.append('-'); continue; }
			t.append(it.enabled && model.enabled ? 'E' : 'D').append(it.checked || it.markText != null ? "c" : "").append('\t').append(it.text == null ? "" : it.text.replace('\n', ' '));
		}
		int n = UiHost.get().popUpMenu68k(t.toString());
		return n > 0 && n <= model.items.size() ? n : 0;
	}
	@Override public void mousePoll() { UiHost.get().externalPoll(); }

	// ---- the windows (P4-5)

	/** A window as the Window Manager gave it to the host: frame or content rectangle (global), pixels, state */
	private static final class Shown {
		final int record, procId; final boolean custom; boolean goAway;
		int left, top, width, height, base, rowBytes, depth; boolean visible, behind, windoid; String title;
		long serial = -1; HzImage image;
		Shown(int record, int procId, boolean custom) { this.record = record; this.procId = procId; this.custom = custom; }
	}
	/** Back to front */
	private final java.util.ArrayList<Shown> order = new java.util.ArrayList<Shown>();
	/** The hosts with windows, in the order they made their first */
	private static final java.util.LinkedHashSet<PlayerVmHost> WITH_WINDOWS = new java.util.LinkedHashSet<PlayerVmHost>();
	private Shown shown(int record) { for (Shown w : order) if (w.record == record) return w; return null; }

	private VmWindow made(VmWindow made, int record, int left, int top, int width, int height, String title, int procId, boolean visible, boolean goAway, int base, int rowBytes, int depth, boolean custom) {
		Shown old = shown(record); if (old != null) forget(old);
		Shown w = new Shown(record, procId, custom);
		w.goAway = goAway;
		w.left = left; w.top = top; w.title = title; w.visible = visible;
		pixels(w, width, height, base, rowBytes, depth);
		order.add(w);
		synchronized (WITH_WINDOWS) { WITH_WINDOWS.add(this); }
		changed();
		return made;
	}
	private void pixels(Shown w, int width, int height, int base, int rowBytes, int depth) {
		if (w.base > 0 && w.rowBytes > 0) memory.untrackBuffer(w.base, w.rowBytes * w.height);
		w.width = width; w.height = height; w.base = base; w.rowBytes = rowBytes; w.depth = depth; w.serial = -1;
		if (base > 0 && rowBytes > 0 && height > 0) memory.trackBuffer(base, rowBytes * height);
	}
	private void forget(Shown w) {
		if (w.base > 0 && w.rowBytes > 0) memory.untrackBuffer(w.base, w.rowBytes * w.height);
		order.remove(w);
		if (order.isEmpty()) synchronized (WITH_WINDOWS) { WITH_WINDOWS.remove(this); }
		if (active == w) { active = null; activeHost = null; }
		if (dragged == w) { dragged = null; draggedHost = null; }
		if (closing == w) { closing = null; closingHost = null; }
		changed();
	}
	@Override public VmWindow createWindow(int record, int left, int top, int width, int height, String title, int procId, boolean visible, boolean goAway, int pixelBase, int rowBytes, int depth) {
		return made(super.createWindow(record, left, top, width, height, title, procId, visible, goAway, pixelBase, rowBytes, depth), record, left, top, width, height, title, procId, visible, goAway, pixelBase, rowBytes, depth, false);
	}
	@Override public VmWindow createWindow(int record, int left, int top, int width, int height, String title, int procId, boolean visible, boolean goAway, int pixelBase, int rowBytes, int depth, boolean customFrame) {
		return made(super.createWindow(record, left, top, width, height, title, procId, visible, goAway, pixelBase, rowBytes, depth, customFrame), record, left, top, width, height, title, procId, visible, goAway, pixelBase, rowBytes, depth, customFrame);
	}
	@Override public void disposeWindow(int record) { super.disposeWindow(record); Shown w = shown(record); if (w != null) forget(w); }
	@Override public void showWindow(int record) { Shown w = shown(record); if (w != null && !w.visible) { w.visible = true; changed(); } }
	@Override public void hideWindow(int record) { Shown w = shown(record); if (w != null && w.visible) { w.visible = false; changed(); } }
	private void moved(int record, int left, int top) { Shown w = shown(record); if (w != null && (w.left != left || w.top != top)) { w.left = left; w.top = top; changed(); } }
	@Override public void moveWindow(int record, int left, int top) { moved(record, left, top); }
	@Override public void moveWindow(int record, int left, int top, boolean byUser) { moved(record, left, top); }
	@Override public void moveWindow(int record, int left, int top, boolean byUser, int dispX, int dispY) { moved(record, left, top); }
	@Override public void moveFrame(int record, int left, int top) { moved(record, left, top); }
	@Override public void resizeWindow(int record, int width, int height, int pixelBase, int rowBytes, int depth) { Shown w = shown(record); if (w != null) { pixels(w, width, height, pixelBase, rowBytes, depth); changed(); } }
	@Override public void setTitle(int record, String title) { Shown w = shown(record); if (w != null) { w.title = title; changed(); } }
	/** MacAppHost.placeBehind: -1 in front; sent to the back, or behind a window that is behind the card, behind the card too */
	@Override public void placeBehind(int record, int behind) {
		Shown w = shown(record); if (w == null) return;
		Shown ref = behind == -1 || behind == 0 ? null : shown(behind);
		order.remove(w);
		if (behind == -1) order.add(w); else if (ref == null) order.add(0, w); else order.add(order.indexOf(ref), w);
		w.behind = behind != -1 && (ref == null || ref.behind);
		changed();
	}
	@Override public void bringToFront(int record, int behind) { placeBehind(record, behind); }
	/** A floating windoid (HyperCard's palettes' layer): it stays over the card window when the card is selected */
	@Override public void setWindoid(int record) { Shown w = shown(record); if (w != null) w.windoid = true; }
	@Override public void selectWindow(int record) {
		Shown w = shown(record);
		if (w != null) { order.remove(w); order.add(w); w.behind = false; changed(); return; }
		// HyperCard's card window selected (The Haunted House's BasicCover makes a 640x480 cover, then SelectWindow on the card
		// window it was called in): the external windows in front of it go behind it, the floating windoids stay over it
		if (record == Xcmd68k.CARD_WINDOW_RECORD) { boolean any = false; for (Shown o : order) if (!o.windoid && !o.behind) { o.behind = true; any = true; } if (any) changed(); }
	}
	/** doMenu "next window": the external windows (not palettes; shown or about to be) that cover the whole card (xWindowFrame's black backdrop,
	 *  Myst Preview) go behind it, for good; true when one did. Windows over a part of the card (movies) stay. */
	public static boolean sendBackdropsBehindCard(int cardWidth, int cardHeight) {
		java.util.List<PlayerVmHost> hosts;
		synchronized (WITH_WINDOWS) { hosts = new java.util.ArrayList<PlayerVmHost>(WITH_WINDOWS); }
		boolean any = false;
		for (PlayerVmHost h : hosts) for (Shown w : h.order)
			if (!w.behind && !w.windoid && w.left <= 0 && w.top <= 0 && w.left + w.width >= cardWidth && w.top + w.height >= cardHeight) { w.behind = true; any = true; }
		if (any) changed();
		return any;
	}
	@Override public boolean behindHyperCard(int record) { Shown w = shown(record); return w != null && w.behind; }
	/** ModalDialog: the dialog has the keyboard while it runs (Return/Enter its default button, as the desktop's focused frame),
	 *  the card again afterwards. Only a click gave it the keyboard: Return in Favorite Story's UxAnswer dialogs went to the card */
	@Override public void beginModalWindow(int record) { Shown w = shown(record); if (w != null && w.visible && !w.behind) activate(this, w); }
	@Override public void endModalWindow(int record) { if (active != null && active.record == record && activeHost == this) activate(null, null); }

	/** Windows were made, shown, moved...: the card is drawn again */
	private static volatile boolean windowsChanged;
	private static void changed() { windowsChanged = true; }

	/** The frame the player draws round a window (CardOverlay): a WDEF 0 document window has a title bar, HyperCard's
	 *  palette WDEF (124) a windoid's, the dialog boxes a line (altDBox a shadow); a frame the window draws itself none */
	private static int frame(Shown w) {
		if (w.custom) return CardOverlay.NO_FRAME;
		int wdef = w.procId >> 4, variant = w.procId & 15;
		if (wdef == 124) return CardOverlay.WINDOID_BAR;
		if (wdef == 0 || wdef == 1) return variant == 2 || variant == 1 ? CardOverlay.LINE : variant == 3 ? CardOverlay.SHADOW : CardOverlay.TITLE_BAR;
		return CardOverlay.LINE;
	}
	/** A modal dialog's window: dBoxProc 1, plainDBox 2, altDBoxProc 3, movableDBoxProc 5 of the system's WDEF 0/1
	 *  (Inside Macintosh: Toolbox Essentials), its frame drawn by the VM or not (Sedit3.1's UxAnswer: dBoxProc, custom) */
	private static boolean modal(Shown w) {
		if (w.windoid) return false;
		int wdef = w.procId >> 4, variant = w.procId & 15;
		return (wdef == 0 || wdef == 1) && (variant == 1 || variant == 2 || variant == 3 || variant == 5);
	}
	/** This host's windows shown over the card, back to front, their pixels read again where the VM wrote them */
	private void addShown(java.util.List<CardOverlay.Window> out) {
		byte[] raw = memory.raw();
		for (Shown w : order) {
			if (!w.visible || w.behind || w.width <= 0 || w.height <= 0 || w.base <= 0 || w.rowBytes <= 0) continue;
			if ((long) w.base + (long) w.rowBytes * w.height > raw.length) continue;
			long serial = memory.chunkSerial(w.base, w.rowBytes * w.height);
			if (w.image == null || w.image.width != w.width || w.image.height != w.height || serial != w.serial) {
				int[] clut = w.depth <= 8 ? devicePalette(w.depth) : null;
				if (w.image == null || w.image.width != w.width || w.image.height != w.height) w.image = new HzImage(w.width, w.height);
				int[] px = w.image.argb;
				for (int y = 0; y < w.height; y++) Pixels68k.readPixels(raw, w.base + y * w.rowBytes, w.depth, clut, w.width, px, y * w.width);
				for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
				w.image.repainted(); // the same picture again (the browser keeps one canvas for it)
				w.serial = serial;
			}
			out.add(new CardOverlay.Window(w.left, w.top, w.image, w.title, frame(w), w.goAway && !w.custom, w == active, modal(w)));
		}
	}
	/** The external windows of every VM shown over the card, back to front (VMs in the order they made windows) */
	static java.util.List<CardOverlay.Window> shownWindows() {
		java.util.List<PlayerVmHost> hosts;
		synchronized (WITH_WINDOWS) { if (WITH_WINDOWS.isEmpty()) return java.util.Collections.emptyList(); hosts = new java.util.ArrayList<PlayerVmHost>(WITH_WINDOWS); }
		java.util.List<CardOverlay.Window> out = new java.util.ArrayList<CardOverlay.Window>();
		for (PlayerVmHost h : hosts) h.addShown(out);
		return out;
	}
	/** Whether a window's pixels or place changed since the last call (the player then draws the card again) */
	static boolean windowsChanged() {
		boolean c = windowsChanged; windowsChanged = false;
		if (c) return true;
		java.util.List<PlayerVmHost> hosts;
		synchronized (WITH_WINDOWS) { if (WITH_WINDOWS.isEmpty()) return false; hosts = new java.util.ArrayList<PlayerVmHost>(WITH_WINDOWS); }
		for (PlayerVmHost h : hosts) for (Shown w : h.order) if (w.visible && !w.behind && w.base > 0 && w.rowBytes > 0 && h.memory.chunkSerial(w.base, w.rowBytes * w.height) != w.serial) return true;
		return false;
	}

	/** The host whose window took the last press in its content: its release goes there too */
	private static PlayerVmHost pressed;
	/** A window dragged by its title bar (where the pointer holds it), one whose close box is held down */
	private static PlayerVmHost draggedHost, closingHost; private static Shown dragged, closing; private static int dragDX, dragDY;
	/** The window that has the keyboard (the last one clicked that is not a palette), and its host; null: the card has it */
	private static PlayerVmHost activeHost; private static Shown active;

	/** The close box of a window's title bar, {left, top, right, bottom} in card coordinates, null when it has none */
	private static int[] closeBox(Shown w) {
		int f = frame(w); if (!w.goAway || (f != CardOverlay.TITLE_BAR && f != CardOverlay.WINDOID_BAR)) return null;
		int bar = CardOverlay.barHeight(f), top = w.top - 1 - bar;
		return f == CardOverlay.TITLE_BAR ? new int[] {w.left + 7, top + 4, w.left + 18, top + 15} : new int[] {w.left + 1, top + 2, w.left + 8, top + 9};
	}
	private static boolean in(int[] r, int x, int y) { return r != null && x >= r[0] && x < r[2] && y >= r[1] && y < r[3]; }

	/** The window under (x, y), its frame included, the front one first: {host, window} or null */
	private static Object[] windowAt(int x, int y) {
		java.util.List<PlayerVmHost> hosts;
		synchronized (WITH_WINDOWS) { if (WITH_WINDOWS.isEmpty()) return null; hosts = new java.util.ArrayList<PlayerVmHost>(WITH_WINDOWS); }
		for (int i = hosts.size() - 1; i >= 0; i--) {
			PlayerVmHost h = hosts.get(i);
			for (int j = h.order.size() - 1; j >= 0; j--) {
				Shown w = h.order.get(j);
				if (!w.visible || w.behind || w.width <= 0 || w.height <= 0) continue;
				int bar = CardOverlay.barHeight(frame(w)), m = frame(w) == CardOverlay.NO_FRAME ? 0 : 1;
				if (x >= w.left - m && x < w.left + w.width + m && y >= w.top - m - bar && y < w.top + w.height + m) return new Object[] {h, w};
			}
		}
		return null;
	}

	/** The keyboard goes to w (null: back to the card): activate events as the host's frames give them */
	private static void activate(PlayerVmHost h, Shown w) {
		if (active == w) return;
		if (active != null && activeHost != null) { activeHost.listener.postActivate(active.record, false); changed(); }
		active = w; activeHost = h;
		if (w != null) { h.listener.postActivate(w.record, true); h.order.remove(w); h.order.add(w); changed(); }
	}

	/**
	 * A press or release on the card at (x, y) (Mac modifiers): when it falls on an external's window (its frame included,
	 * the front one first) it is that window's and not HyperCard's; true then. In the content it is the VM's host click
	 * (postMouse, as MacAppHost's frame passes it); on the title bar it drags the window (the move is posted when it is let go,
	 * as a frame the user moved), on the close box it is the close button (postGoAway, which XWindows68k makes xCloseEvt).
	 * A window that is not a palette (HyperCard's windoids leave the keyboard to the card) gets the keyboard; a press on the
	 * card gives it back. A release goes where its press went.
	 */
	static boolean pressAt(boolean down, int x, int y, int modifiers) {
		if (!down) {
			if (dragged != null) { Shown w = dragged; PlayerVmHost h = draggedHost; dragged = null; draggedHost = null; h.listener.postWindowMoved(w.record, w.left, w.top); return true; }
			if (closing != null) { Shown w = closing; PlayerVmHost h = closingHost; closing = null; closingHost = null; if (in(closeBox(w), x, y)) h.listener.postGoAway(w.record); return true; }
			PlayerVmHost h = pressed; pressed = null;
			if (h == null) return false;
			h.listener.postMouse(false, x, y, modifiers | 0x80, System.currentTimeMillis());
			return true;
		}
		Object[] at = windowAt(x, y);
		if (at == null) { activate(null, null); return false; }
		PlayerVmHost h = (PlayerVmHost) at[0]; Shown w = (Shown) at[1];
		if (frame(w) != CardOverlay.WINDOID_BAR) activate(h, w);
		if (y < w.top && frame(w) != CardOverlay.NO_FRAME) {
			if (in(closeBox(w), x, y)) { closing = w; closingHost = h; }
			else if (y < w.top - 1) { dragged = w; draggedHost = h; dragDX = x - w.left; dragDY = y - w.top; }
			return true;
		}
		pressed = h;
		h.listener.postMouse(true, x, y, modifiers & ~0x80, System.currentTimeMillis());
		return true;
	}
	/** The pointer moved: a window being dragged follows it; true then */
	static boolean pointerAt(int x, int y) {
		Shown w = dragged; if (w == null) return false;
		int left = x - dragDX, top = y - dragDY;
		if (left != w.left || top != w.top) { w.left = left; w.top = top; changed(); }
		return true;
	}
	/** A key (what: keyDown 3, keyUp 4; Mac key code, character, modifiers) while an external's window has the keyboard: its
	 *  VM gets it (postKey, as a focused frame); false when the card has the keyboard */
	static boolean keyTo(int what, int keyCode, int charCode, int modifiers) {
		Shown w = active; PlayerVmHost h = activeHost;
		if (w == null || h == null || !w.visible || w.behind) return false;
		h.listener.postKey(what, keyCode, charCode, modifiers);
		return true;
	}
}

/**
 * The player: a stack run interactively on one thread (P2: the browser's worker; never returns). As the desktop's GUI and
 * PCARD.Pidle do:
 * <ul>
 * <li>a press while no handler runs: the open field closes (or the unlocked field clicked opens), mouseDown to the part
 *     under the pointer (HeadlessPointer.partAt) or the card, mouseStillDown while it is held and nothing runs, at the release
 *     mouseUp (mouseDoubleClick for the second click) when it is inside the part; a button with autoHilite shows hilited while
 *     the pointer is in it, a check box or radio button changes its hilite before mouseUp</li>
 * <li>a press while a handler runs: only the mouseClick (and clickLoc); a release while the press's mouseDown or
 *     mouseStillDown handler runs: mouseUp after it</li>
 * <li>keys: in the open field, the key goes through the message path from it (fieldKeyCommand, keyDown, returnInField,
 *     enterInField, tabKey) and HyperCard types it at the end; elsewhere keyDown/arrowKey/returnKey/enterKey/tabKey/
 *     commandKeyDown to the card</li>
 * <li>idle every 100 ms while nothing runs (1/60 s while an idle handler has run in the last second); mouseEnter and
 *     mouseLeave when the part under the pointer changes, mouseWithin to it with each idle</li>
 * <li>the card drawn after each run, and while a handler runs at most every 16 ms (at its waits and every 256 statements);
 *     not while the screen is locked</li>
 * <li>Command-period: the running handlers stop (TTalk.requestUserAbort)</li>
 * </ul>
 * Waiting is real (PlayerPlatform: parkNanos sleeps in the host until input comes or the time passes).
 */
final class PlayerLoop {
	final PlayerHost host;
	private Gfx gfx;
	private long lastFrame, lastIdle, idleHandlerAt = -1_000_000_000_000L;
	/** The least time between two frames a turn draws without input (step) */
	static final long FRAME_PACE = 16_000_000L;
	private boolean busy;
	private OObject pressed;          // the part the press went to (mouseDown sent), or the card
	private OButton popupOpen;        // the pop-up button whose menu the page shows (mouseUp when a line is chosen)
	private OButton tracking;         // a button with autoHilite being pressed
	private boolean doubleClick;
	private OField selecting;         // an open field whose text the press is selecting (a drag extends it from selectAnchor)
	private int selectAnchor;
	private int selectedFrom, selectedTo; // the selection the press last made (a script's own select ends the drag)
	private long selectScrolledAt;
	private boolean painting;         // the press paints with the paint tool chosen (HzPaint)
	private boolean authoring;        // the press selects, moves or makes a part with the button or field tool (HzAuthTool)
	private long antsAt;              // when the selected part's marching ants last moved
	private final java.util.List<String> infoChanges = new java.util.ArrayList<>(); // info window changes that came while busy
	private OField scrolling;         // a scrolling field whose scroll bar's thumb the press drags (scrollGrab: where it took it)
	private OField listPicking;       // a list field (one line) pressed: its selected line follows the pointer in it (pickListLine)
	private int scrollGrab;
	private boolean dirty = true;
	/** what the last frame drew (FrameKey), when draw drew it and nothing else drew on the screen since (frameKeyed) */
	private long frameKey, lastFullFrame;
	private boolean frameKeyed;
	static PlayerLoop current;
	/** When set (the web player's ?trace), each input event and what it went to. */
	static java.util.function.Consumer<String> trace;

	final PlayerSound sound;
	PlayerLoop(PlayerHost host) {
		this.host = host; this.sound = new PlayerSound(host); host.redrawer(this::draw);
		host.dialogIdler(() -> { // a dialog open: the 68K VMs' sound goes on (the music of MusicBox behind an answer)
			Externals68k x = Externals68k.Runner.current;
			if (x == null) return false;
			boolean kept = x.hasSessions();
			if (kept) x.pumpWhileWaiting();
			return x.audio() || kept;
		});
	}

	/** How AddColor shows its colours here (HzAddColor) */
	final HzAddColor.Screen addColorScreen = new HzAddColor.Screen() {
		public int[] screen() {
			OStack st = HZ.stack();
			if (st == null) return null;
			if (!HZ.lockedScreen) draw(); // what changed before (a go) is on the screen first, as VEffect.setOldOff paints it
			return PlayerLoop.this.screen(Math.max(1, st.width), Math.max(1, st.height));
		}
		public void redraw() { if (!HZ.lockedScreen) draw(); else dirty = true; }
		public void effect(int effect, java.awt.Rectangle area, int[] old, long ms) { effectIn(effect, area, old, ms); }
		public void park(long nanos) { PlayerLoop.this.park(nanos); }
	};

	/** The platform of a player: real time from the host; waiting sleeps there (input wakes it). */
	static final class PlayerPlatform extends PortablePlatform {
		final PlayerHost host;
		PlayerPlatform(PlayerHost host) { this.host = host; }
		@Override long nanoTime() { return host.nanoTime(); }
		@Override void parkNanos(long nanos) { PlayerLoop p = current; if (p != null) p.park(nanos); else if (nanos > 0) host.sleep(nanos); }
		@Override void sleep(long millis) { parkNanos(millis * 1_000_000L); }
		@Override void waitOn(Object lock, long millis) { parkNanos(millis * 1_000_000L); }
	}

	/** Sets the core up for playing (instead of CoreMain.setUp's virtual clock and scripted dialogs). */
	static PlayerLoop setUp(PlayerHost host, String lang) throws Exception {
		Platform.set(new PlayerPlatform(host));
		Host.set(new HeadlessHost());
		PlayerLoop loop = new PlayerLoop(host);
		current = loop;
		PlayerInput.host = host; PlayerInput.reset();
		UiHost.set(new PlayerUiHost(loop));
		PictureWindows.floating = host.floatsPalettes();
		HzImages.pictCanvas = host::offscreen; // a PICT's text and lines
		if (OWindow.cdwindow == null) OWindow.cardWindowWithoutFrame();
		HzAddColor.clear();
		HzShapes.clear();
		MenuNodes.set(new PlainMenus());
		HZ.setIntl(new International(lang));
		TTalk.lockErrorDialogs = false;
		TTalk.statementTick = loop::tick;
		TalkPump.start();
		return loop;
	}

	/** Runs the stack: opening messages, then input, idle and drawing, for ever. */
	void run() {
		draw();
		while (true) { long sleep = step(); if (sleep > 0) host.sleep(sleep); }
	}

	/** One turn: the queued messages, the input that came, idle when it is due, the screen; returns how long to sleep before
	 *  the next turn (0: at once). */
	long step() {
		boolean ran = TalkPump.drain(1_000_000) > 0;
		int[] e;
		boolean input = false;
		while ((e = host.nextEvent()) != null) { event(e, false); input = true; }
		if (host.takeAbort()) TTalk.requestUserAbort();
		int queued = TTalk.messageList.size();
		int done = TalkPump.drain(1_000_000);
		ran |= done > 0;
		if (trace != null && (input || done > 0) ) trace.accept("drain queued=" + queued + " ran=" + done + " left=" + TTalk.messageList.size() + " card=" + (HZ.stack() == null || HZ.stack().curCard == null ? "-" : HZ.stack().curCard.id));
		if (!infoChanges.isEmpty() && TTalk.idle && TTalk.messageList.isEmpty()) {
			for (String c : new java.util.ArrayList<>(infoChanges)) PartInfo.apply(host, c);
			infoChanges.clear();
			ran = true;
		}
		stillDown();
		pointer();
		long now = host.nanoTime();
		// (under a stack's speed limit at most 40 a second, as HyperCard's event loop: StackSpeed.IDLE_INTERVAL_NANOS, PCARD.Pidle)
		long period = now - idleHandlerAt < 1_000_000_000L ? StackSpeed.limited() ? StackSpeed.IDLE_INTERVAL_NANOS : 16_666_667L : 100_000_000L;
		// (no idle while a pop-up button's menu is open on the page: HyperCard tracks the menu then - HyperWrite's idle handler
		// would take the selection its Size pop-up's mouseDown kept for its mouseUp)
		if (TTalk.idle && TTalk.messageList.isEmpty() && popupOpen == null && now - lastIdle >= period && HZ.stack() != null && HZ.stack().curCard != null) {
			lastIdle = now;
			if (within != null && UiHost.get().gButtonOnCurrentCard(within)) TTalk.CallMessage("mouseWithin", "", within, true, false);
			TTalk.CallMessage("idle", "", HZ.stack().curCard, true, false);
			long handled = TTalk.idleHandlerAt, t = host.nanoTime();
			ran |= TalkPump.drain(1_000_000) > 0;
			if (TTalk.idleHandlerAt != handled) idleHandlerAt = t; // an idle handler ran: idle comes every 1/60 s
		}
		// the VMs 68K externals keep get their events while HyperCard is idle, every 15 ms (TTalk's idle loop on the desktop)
		Externals68k x = Externals68k.Runner.current;
		boolean kept = x != null && x.hasSessions();
		if (kept && TTalk.idle && TTalk.messageList.isEmpty()) { x.pump(); ran |= TalkPump.drain(1_000_000) > 0; }
		boolean streaming = x != null && x.audio(); // the 68K VMs' sound, a little ahead of now
		// the stacks' changes to the page, to keep (PlayerSaving: the desktop's autosave)
		if (TTalk.idle && TTalk.messageList.isEmpty()) PlayerSaving.idle(host, now, now - idleHandlerAt < 2_000_000_000L);
		if (busyPosted && TTalk.idle && TTalk.messageList.isEmpty()) { busyPosted = false; host.post("busy", "false"); }
		if (MovieWindows.tick()) dirty = true; // the Movie XCMD's movies: their frames now
		if (host.redrawWanted()) dirty = true; // the page shows the card at another size
		boolean ants = HzAuthTool.selection() != null; // the selected part's marching ants move every 80 ms
		if (ants && now - antsAt >= 80_000_000L) { antsAt = now; dirty = true; }
		// a frame at most every FRAME_PACE unless input came: a message that ran is drawn at the next frame (dirty). A button
		// held down sends mouseStillDown at every turn without a pause, as HyperCard does, and each drew and sent a frame:
		// thousands a second in the browser, whose card canvas then came empty again and again (3DCar: BEGIN held over
		// PgColorX's picture, the card black or gone)
		if (ran || input || dirty) {
			if (input || host.nanoTime() - lastFrame >= FRAME_PACE) draw(); else dirty = true;
		}
		if (!TTalk.messageList.isEmpty()) return 0;
		long next = Math.max(1_000_000L, Math.min(period - (host.nanoTime() - lastIdle), 100_000_000L));
		if (dirty) next = Math.min(next, Math.max(1_000_000L, FRAME_PACE - (host.nanoTime() - lastFrame)));
		if (kept) next = Math.min(next, 15_000_000L);
		if (streaming) next = Math.min(next, 20_000_000L);
		if (MovieWindows.playing()) next = Math.min(next, 16_000_000L);
		if (ants) next = Math.min(next, 80_000_000L);
		return tracking != null || pressed != null || selecting != null || scrolling != null || painting || authoring ? Math.min(next, 16_000_000L) : next;
	}

	/** The page was told a handler is running (busy: it greys its menu bar, whose choices are dropped then) */
	private boolean busyPosted;

	/** Every 256 statements of a running handler: input that arrived, Command-period, the screen when it is due. */
	void tick() {
		busy = true;
		if (!busyPosted) { busyPosted = true; host.post("busy", "true"); } // (a short handler never gets here: no flicker)
		try {
			int[] e;
			while ((e = host.nextEvent()) != null) event(e, true);
			if (host.takeAbort()) TTalk.requestUserAbort();
			Externals68k x = Externals68k.Runner.current;
			if (x != null) x.audio(); // the 68K VMs' sound goes on while a handler runs
			MovieWindows.tick(); // and the movies (wait until the currTime of movie 1 …)
			if (host.nanoTime() - lastFrame >= 16_000_000L) draw();
		} finally { busy = false; }
	}

	/** A wait of a handler (wait, the speed limit…): the screen, input, then sleep in slices so input keeps coming. */
	void park(long nanos) {
		long end = host.nanoTime() + Math.max(0, nanos);
		do {
			tick();
			long left = end - host.nanoTime();
			if (left <= 0 || TTalk.stop) break;
			host.sleep(Math.min(left, 16_000_000L));
		} while (true);
	}

	void markDirty() { dirty = true; }

	/** A handler painted (drag/click at with a paint tool): on the screen now, as HyperCard paints on it, unless it is locked
	 *  (at most a frame every 8 ms). DoodlePaint's rubber band (drag, Select, Clear Picture while the mouse is down) never
	 *  showed its shape: the 16 ms frames between statements always came after the Clear */
	static void scriptPainted() {
		PlayerLoop p = current;
		if (p == null || HZ.lockedScreen) return;
		if (p.host.nanoTime() - p.lastFrame >= 8_000_000L) p.draw();
	}

	/** The card, unless the screen is locked. */
	void draw() {
		OStack st = HZ.stack();
		PictureWindows.underModal = PictureWindows.floating && CardOverlay.modalShown();
		sendFloating(); // (palettes are windows of their own: lock screen leaves them be)
		if (st == null || HZ.lockedScreen) return;
		Gfx was = gfx;
		gfx = host.canvas(Math.max(1, st.width), Math.max(1, st.height));
		// the same card as the last frame (an idle handler's lock, show what shows, unlock): nothing drawn or sent. A new
		// canvas (lost, other size), a frame drawn elsewhere (effects, flash, externals: frameKeyed false) or a second gone
		// by draws it anyway
		long now = host.nanoTime();
		// (68K externals draw on the card outside these calls: always drawn then)
		boolean keyable = CardOverlay.held == null && CardOverlay.source == null;
		long key = 0;
		boolean sure = false;
		if (keyable) {
			FrameKey k = new FrameKey(gfx);
			CardRenderer.screen(k, st);
			key = k.key(); sure = !k.unsure;
			if (sure && gfx == was && frameKeyed && key == frameKey && now - lastFullFrame < 1_000_000_000L) { lastFrame = now; dirty = false; sendMenus(); return; }
		}
		frameKey = key; frameKeyed = keyable;
		lastFullFrame = now;
		HzImage held = CardOverlay.held;
		if (held != null && held.width == gfx.width() && held.height == gfx.height()) gfx.putPixels(held.argb, 0, 0, held.width, held.height);
		else { CardRenderer.screen(gfx, st); CardOverlay.throughGamma(gfx, gfx.width(), gfx.height()); }
		host.present();
		lastFrame = host.nanoTime();
		dirty = false;
		sendMenus();
	}

	/** what was last sent of the floating palettes: ids and places; the palettes in that list */
	private String floatingSent = "";
	private final java.util.Set<PictureWindows.Pict> floatingOnPage = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	/** The palettes the page floats (PictureWindows.floating), when one came, went, moved or changed: drawn each on its own
	 *  with its frame, as on the card */
	private void sendFloating() {
		if (!PictureWindows.floating) return;
		java.util.List<PictureWindows.Pict> ps = PictureWindows.floatingShown();
		// a palette left out of the last list is gone from the page (player.js drops its canvas): shown again (hide window /
		// show window, 秘密結社ゲーム's "MISSON" on each card change) its pixels go again, or the page made an empty canvas
		for (PictureWindows.Pict p : ps) if (!floatingOnPage.contains(p)) p.sentPixels = null;
		floatingOnPage.clear(); floatingOnPage.addAll(ps);
		int n = ps.size();
		int[] ids = new int[n], rects = new int[n * 4];
		int[][] argb = new int[n][];
		StringBuilder key = new StringBuilder();
		boolean drawn = false;
		for (int i = 0; i < n; i++) {
			PictureWindows.Pict p = ps.get(i);
			CardOverlay.Window ov = p.overlay();
			int[] b = ov.bounds();
			int w = Math.max(1, b[2] - b[0]), h = Math.max(1, b[3] - b[1]);
			ids[i] = p.window == null ? i + 1 : p.window.id;
			rects[i * 4] = b[0]; rects[i * 4 + 1] = b[1]; rects[i * 4 + 2] = w; rects[i * 4 + 3] = h;
			key.append(ids[i]).append(':').append(b[0]).append(',').append(b[1]).append(',').append(w).append(',').append(h).append(';');
			if (p.sentPixels != ov.pixels) {
				Gfx g = host.offscreen(w, h);
				g.setColor(0xFFFFFFFF); g.fillRect(0, 0, w, h);
				g.save(); g.translate(-b[0], -b[1]); CardRenderer.window(g, ov); g.restore();
				argb[i] = g.getPixels(0, 0, w, h);
				p.sentPixels = ov.pixels;
				drawn = true;
			}
		}
		String k = key.toString();
		if (!drawn && k.equals(floatingSent)) return;
		floatingSent = k;
		host.floatingWindows(ids, rects, argb);
	}

	// ---------------------------------------------------------------- menus

	/** The menu bar shows (hide/show menubar) */
	boolean menuBarShown = true;
	private String menusSent;

	/** The card's menu bar to the host when it changed: "menus" with menuText */
	void sendMenus() {
		String s = menuText();
		if (s.equals(menusSent)) return;
		menusSent = s;
		host.post("menus", s);
	}

	/**
	 * The menu bar as lines: "hidden" first when it is hidden; "M<tab>title" for each shown menu, then its items as
	 * "I<tab>text<tab>enabled 1/0<tab>checked 1/0<tab>Command key" and "S" for a separator, each with "<tab>label" after
	 * when HyperZebra's language names it otherwise. A submenu is an item that cannot be chosen. Choosing sends MENU with
	 * "title\nitem" (doMenu item, title), by their names.
	 */
	static String menuText() {
		StringBuilder sb = new StringBuilder();
		PlayerLoop p = current;
		if (p != null && !p.menuBarShown) sb.append("hidden\n");
		MMenuBar bar = shownMenuBar();
		if (bar == null) return sb.toString();
		HeadlessMenus.checks(bar);
		for (MMenu m : bar.menus()) {
			if (!m.isVisible() || levelHides(m)) continue;
			sb.append("M\t").append(named(m.getText())).append('\n');
			MPopupMenu pm = m.getPopupMenu();
			if (pm == null) continue;
			for (MComponent c : pm.getComponents()) {
				if (c == null || !c.isVisible()) continue;
				if (c instanceof MSeparator) { sb.append("S\n"); continue; }
				if (!(c instanceof MMenuItem it)) continue;
				boolean sub = it instanceof MMenu;
				boolean checked = it instanceof MCheckBoxMenuItem cb && cb.isSelected();
				MKeyStroke k = it.getAccelerator();
				String key = k == null || k.getKeyCode() <= 32 || k.getKeyCode() > 126 ? "" : String.valueOf((char) k.getKeyCode());
				String[] nl = named(it.getText()).split("\t", -1);
				sb.append("I\t").append(nl[0]).append('\t').append(it.isEnabled() && !sub && !levelOff(m, it) ? '1' : '0').append('\t')
					.append(checked ? '1' : '0').append('\t').append(key).append(nl.length > 1 ? "\t" + nl[1] : "").append('\n');
			}
		}
		return sb.toString();
	}

	/** The menus and items of the user level in effect, as HyperCard shows them and the desktop does (GMenu.applyUserLevel):
	 *  no Tools menu below Painting (3), no Objects menu below Authoring (4); New/Delete/Copy Card from Typing (2),
	 *  Background from Painting, Icon…/Sound…/Resource… and the button, field and shape tools from Authoring. Only what is
	 *  shown and the Command keys: doMenu in scripts is not limited, and the menus scripts edit stay whole (Royal Game of
	 *  Ur sets userLevel 2 after its menus: Objects stayed in the player's bar) */
	static boolean levelHides(MMenu m) {
		String title = english(m.getText());
		int level = UiHost.get().stackProtectionEffectiveUserLevel();
		return title.equals("Tool") && level < 3 || title.equals("Objects") && level < 4;
	}
	private static final Object[][] LEVEL_ITEMS = {
		{"Edit", "New Card", 2}, {"Edit", "Delete Card", 2}, {"Edit", "Copy Card", 2},
		{"Edit", "Background", 3}, {"Edit", "Icon…", 4}, {"Edit", "Sound…", 4}, {"Edit", "Resource…", 4},
		{"Tool", "Button", 4}, {"Tool", "Field", 4}, {"Tool", "Shape", 4},
	};
	static boolean levelOff(MMenu m, MMenuItem it) {
		String title = english(m.getText()), item = english(it.getText());
		for (Object[] e : LEVEL_ITEMS)
			if (e[0].equals(title) && e[1].equals(item)) return UiHost.get().stackProtectionEffectiveUserLevel() < (Integer) e[2];
		return false;
	}
	private static String english(String shown) {
		if (shown == null) return "";
		String e = HZ.intl() == null ? "" : HZ.intl().getEngText(shown);
		return e == null || e.isEmpty() ? shown : e;
	}

	/** The menu bar on show: the paint tools' with a paint tool chosen (GToolBar's menuUpdate(paintMenu.mb) on the desktop),
	 *  else the card window's */
	static MMenuBar shownMenuBar() {
		MMenuBar paint = HzPaint.active() ? MenuNodes.get().paintMenuBar() : null;
		return paint != null ? paint : MenuNodes.get().cardMenuBar();
	}

	private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }
	/** A menu's or item's name as shown (the menus are made in HyperZebra's language): "English<tab>shown" when the language
	 *  shows it by another name (its "menu" catalog), else the name - the page shows the label and chooses by the English
	 *  name (doMenu finds the standard menus by those) */
	private static String named(String shown) {
		String s = clean(shown);
		if (s.isEmpty() || HZ.intl() == null) return s;
		String english = clean(HZ.intl().getEngText(shown));
		return english.isEmpty() || english.equals(s) ? s : english + "\t" + s;
	}

	Gfx gfx() { OStack st = HZ.stack(); return gfx != null ? gfx : host.canvas(st == null ? 512 : st.width, st == null ? 342 : st.height); }

	// ---------------------------------------------------------------- lock screen and visual effects (VEffect's rules)

	/** The picture the next effect starts on (lock screen before an unlock with an effect, go with visual effects, an effect
	 *  that did not finish); null: the screen as it is */
	private int[] oldPicture;
	private int oldW, oldH;

	/** The screen now (the canvas keeps the last frame shown; while the screen is locked that is the picture from the lock) */
	private int[] screen(int w, int h) { return gfx().getPixels(0, 0, w, h); }

	/** lock screen (VEffect.setOldOff / lockLazily): what shows now is finished first, then it stays until the unlock */
	void lockScreen(boolean keepPicture) {
		boolean was = HZ.lockedScreen;
		if (!was) draw();
		HZ.lockedScreen = true;
		OStack st = HZ.stack();
		if (keepPicture && st != null) { oldW = Math.max(1, st.width); oldH = Math.max(1, st.height); oldPicture = screen(oldW, oldH); }
		// with 68K externals: the picture the locked screen shows, which their drawing goes over meanwhile (externalDrew)
		if (!was && st != null && CardOverlay.source != null) {
			lockW = Math.max(1, st.width); lockH = Math.max(1, st.height);
			// the screen shows the card through a gamma table: the picture without it (the table is put on at each frame)
			lockPicture = CardOverlay.source.gamma() != null ? freshCard(st, lockW, lockH) : screen(lockW, lockH);
			lockBare = bareCard(st, lockW, lockH);
		}
	}
	/** The offscreen cardPixels draws the card into */
	private Gfx cardCopy;
	/** The picture of the screen as it was locked (68K player only), null when not locked */
	private int[] lockPicture;
	private int lockW, lockH;
	/** The card as it was locked without what externals drew over it (lockW x lockH RGB, 68K player only): what their drawing
	 *  is told apart from while the screen is locked. lockPicture has that drawing in it, so an external that drew the same
	 *  again (ColorizeHC at a go to the same card) seemed to have drawn nothing and its colours went at the unlock */
	private int[] lockBare;

	// ---------------------------------------------------------------- the card window of 68K externals (P4-4, CardViews68k)

	/** The card as it shows now ({width, height} into size), RGB: with externals, with what 68K externals drew over it;
	 *  null when no card is on show */
	static int[] cardPixels(boolean externals, int[] size) {
		PlayerLoop p = current; OStack st = HZ.stack();
		if (p == null || st == null || st.curCard == null) return null;
		int w = Math.max(1, st.width), h = Math.max(1, st.height);
		// one offscreen kept (an external snapshots the card at each call: PgColorX's "Tiling" 30 times a second made a canvas
		// each time, and the browser ran out of them - the screen's own transferToImageBitmap failed)
		Gfx g = p.cardCopy;
		if (g == null || g.width() != w || g.height() != h) g = p.cardCopy = p.host.offscreen(w, h);
		CardRenderer.draw(g, st, externals);
		int[] px = g.getPixels(0, 0, w, h);
		for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
		size[0] = w; size[1] = h;
		return px;
	}
	/** The card drawn afresh with what externals drew over it, ARGB (the offscreen cardPixels uses) */
	private int[] bareCard(OStack st, int w, int h) {
		Gfx g = cardCopy;
		if (g == null || g.width() != w || g.height() != h) g = cardCopy = host.offscreen(w, h);
		CardRenderer.draw(g, st, false);
		int[] px = g.getPixels(0, 0, w, h);
		for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
		return px;
	}
	private int[] freshCard(OStack st, int w, int h) {
		Gfx g = cardCopy;
		if (g == null || g.width() != w || g.height() != h) g = cardCopy = host.offscreen(w, h);
		CardRenderer.draw(g, st, true);
		return g.getPixels(0, 0, w, h);
	}
	/** The message box as HyperCard draws it, for a 68K external that draws in it (CardViews68k): width x height RGB, the title
	 *  bar (barHeight rows, HyperCard's palette bar) above a white body with the text at HyperCard's left margin, null with no
	 *  player. The page shows its own box; this is the picture the external's drawing is measured against */
	static int[] messageBoxPixels(int width, int height, int barHeight, int margin) {
		PlayerLoop p = current;
		if (p == null) return null;
		Gfx g = p.host.offscreen(width, height);
		g.setAlpha(1);
		g.setColor(0xFFFFFFFF); g.fillRect(0, 0, width, height);
		g.setColor(0xFF000000); g.drawRect(0, 0, width, height);
		for (int y = 3; y < barHeight - 2; y += 2) g.drawLine(2, y, width - 3, y); // the bar's stripes
		g.drawLine(0, barHeight - 1, width - 1, barHeight - 1);
		g.setFont("Geneva", 12, 0);
		String text = UiHost.get().msgText();
		if (text != null && !text.isEmpty()) g.drawText(text, margin, barHeight + (height - barHeight + g.ascent() - g.descent()) / 2);
		int[] px = g.getPixels(0, 0, width, height);
		for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
		return px;
	}
	/** While the screen is locked, the card it shows without the externals' drawing (RGB, width x height), else null */
	static int[] lockedPixels(int width, int height) {
		PlayerLoop p = current;
		if (p == null || !HZ.lockedScreen) return null;
		if (p.lockBare != null && p.lockW == width && p.lockH == height) return p.lockBare.clone();
		int[] pic = p.oldPicture != null && p.oldW == width && p.oldH == height ? p.oldPicture : p.lockPicture != null && p.lockW == width && p.lockH == height ? p.lockPicture : null;
		if (pic == null) return null;
		int[] px = pic.clone();
		for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
		return px;
	}
	/** A 68K external drew on the card (CardOverlay changed): the card shows it; throughLock also while the screen is locked
	 *  (an external draws straight into the window: lock screen stops only HyperCard's own redraw) */
	static void externalDrew(boolean throughLock) {
		PlayerLoop p = current; OStack st = HZ.stack();
		if (p == null || st == null) return;
		if (!HZ.lockedScreen) { p.draw(); return; }
		if (!throughLock) { p.dirty = true; return; }
		int w = Math.max(1, st.width), h = Math.max(1, st.height);
		int[] base = p.oldPicture != null && p.oldW == w && p.oldH == h ? p.oldPicture : p.lockPicture != null && p.lockW == w && p.lockH == h ? p.lockPicture : null;
		if (base == null) return;
		Gfx g = p.host.canvas(w, h);
		p.gfx = g; p.frameKeyed = false;
		g.putPixels(base, 0, 0, w, h);
		HzImage drawn = CardOverlay.drawn();
		if (drawn != null) g.drawImage(drawn, 0, 0);
		for (CardOverlay.Window win : CardOverlay.windows()) CardRenderer.window(g, win);
		for (CardOverlay.Window win : PictureWindows.shown()) CardRenderer.window(g, win);
		CardOverlay.throughGamma(g, w, h);
		p.host.present();
		p.lastFrame = p.host.nanoTime();
	}
	/** The screen keeps what it shows now until releaseScreen (CardWindow68k.holdScreen) */
	static void holdScreen() {
		PlayerLoop p = current; OStack st = HZ.stack();
		if (p == null || st == null) return;
		int w = Math.max(1, st.width), h = Math.max(1, st.height);
		CardOverlay.held = new HzImage(w, h, p.screen(w, h));
	}
	static void releaseScreen() {
		CardOverlay.held = null;
		PlayerLoop p = current;
		if (p != null) { if (HZ.lockedScreen) p.dirty = true; else p.draw(); }
	}

	/** The effects visual effect queued for a go, in turn (VEffect.playEffects); none: the card shows at once */
	void playEffects(java.util.List<int[]> effects) {
		if (effects == null || effects.isEmpty()) { effect(0, 0, 3, true); return; }
		for (int i = 0; i < effects.size(); i++) {
			int[] e = effects.get(i);
			if (e[0] == 0 && e.length > 3 && e[3] != 0) holdPlain(e[2]);
			effect(e[0], e[1], e[2], i == effects.size() - 1);
		}
	}

	/** visual effect plain|cut with a speed: the time passes with nothing drawn */
	void holdPlain(int speed) { park(HzEffects.duration(0, speed) * 1_000_000L); }

	/** One effect from the picture the screen had to the card (or black, white, gray, inverse) in HyperCard's time; with
	 *  finish the screen unlocks on the card, otherwise the end picture stays for the next effect */
	void effect(int effect, int toEffect, int speed, boolean finish) {
		OStack st = HZ.stack();
		// the card goes back into the window (unlock screen, go): what a 68K external drew over it is not in the new picture
		// (VEffect.visualEffect: UxMaruPeke's "unlock screen with dissolve" erases its ○×)
		if (finish && (HZ.lockedScreen || effect != 0)) { Externals68k x = Externals68k.Runner.current; if (x != null) x.cardCopied(); }
		if (finish) { lockPicture = null; lockBare = null; }
		if (st == null) { HZ.lockedScreen = false; oldPicture = null; return; }
		int w = Math.max(1, st.width), h = Math.max(1, st.height);
		if (effect == 0) {
			if (!finish) return;
			HZ.lockedScreen = false; oldPicture = null;
			draw();
			return;
		}
		boolean wasLocked = HZ.lockedScreen;
		int[] old = oldPicture != null ? oldPicture : screen(w, h);
		if (oldPicture != null && (oldW != w || oldH != h)) { // the card's size changed (a go to another stack): the old picture at the top left on white
			int[] resized = new int[w * h];
			java.util.Arrays.fill(resized, HzEffects.WHITE);
			for (int y = 0; y < Math.min(h, oldH); y++) System.arraycopy(oldPicture, y * oldW, resized, y * w, Math.min(w, oldW));
			old = resized;
		}
		// the card as it is now, drawn but not shown
		Gfx g = host.canvas(w, h);
		gfx = g; frameKeyed = false;
		CardRenderer.screen(g, st);
		int[] neu = HzEffects.target(toEffect, g.getPixels(0, 0, w, h), w, h);
		HZ.lockedScreen = true; // nothing else draws meanwhile (tick)
		int[] out = new int[w * h];
		long ms = HzEffects.duration(effect, speed), start = host.nanoTime(), end = start + ms * 1_000_000L;
		int frames = 0;
		for (long now = start; now < end && !TTalk.stop; now = host.nanoTime(), frames++) {
			HzEffects.frame(effect, old, neu, out, w, h, (double) (now - start) / (end - start));
			g.putPixels(out, 0, 0, w, h);
			host.present();
			park(16_666_667L);
		}
		g.putPixels(neu, 0, 0, w, h); // the last frame is the whole end picture
		host.present();
		lastFrame = host.nanoTime();
		if (!finish) {
			oldPicture = neu; oldW = w; oldH = h; HZ.lockedScreen = true;
			if (CardOverlay.source != null) { lockW = w; lockH = h; lockBare = bareCard(st, w, h); } // the card the next effect starts from
			return;
		}
		oldPicture = null;
		HZ.lockedScreen = false;
		if (toEffect == 0) dirty = false; else draw();
		if (trace != null) trace.accept("effect " + effect + " to " + toEffect + " in " + ms + " ms, " + frames + " frames" + (wasLocked ? " (unlock)" : ""));
	}

	/**
	 * An AddColor effect (VEffect.visualEffectIn): from old to the card as it is now, only inside area (null: the whole card),
	 * lasting ms; the screen is unlocked on the card at the end
	 */
	void effectIn(int effect, java.awt.Rectangle area, int[] old, long ms) {
		OStack st = HZ.stack();
		if (st == null) return;
		int w = Math.max(1, st.width), h = Math.max(1, st.height);
		if (old == null || old.length != w * h) { HZ.lockedScreen = false; draw(); return; }
		Gfx g = host.canvas(w, h);
		gfx = g; frameKeyed = false;
		CardRenderer.screen(g, st);
		int[] neu = g.getPixels(0, 0, w, h);
		boolean locked = HZ.lockedScreen;
		HZ.lockedScreen = true;
		int[] out = new int[w * h];
		long start = host.nanoTime(), end = start + ms * 1_000_000L;
		int frames = 0;
		for (long now = start; now < end && !TTalk.stop; now = host.nanoTime(), frames++) {
			double t = (double) (now - start) / (end - start);
			if (area == null) HzEffects.frame(effect, old, neu, out, w, h, t);
			else HzEffects.frameIn(effect, old, neu, out, w, h, area.x, area.y, area.width, area.height, t);
			g.putPixels(out, 0, 0, w, h);
			host.present();
			park(16_666_667L);
		}
		g.putPixels(neu, 0, 0, w, h);
		host.present();
		lastFrame = host.nanoTime();
		oldPicture = null; lockPicture = null; lockBare = null;
		HZ.lockedScreen = false;
		dirty = false;
		if (trace != null) trace.accept("addcolor effect " + effect + (area == null ? "" : " in " + area.x + "," + area.y + "," + area.width + "x" + area.height) +
			" in " + ms + " ms, " + frames + " frames" + (locked ? " (unlock)" : ""));
	}

	/** flash n: the screen inverted and back, n times (50 ms each) */
	void flash(int n) {
		OStack st = HZ.stack();
		if (st == null || n <= 0) return;
		int w = Math.max(1, st.width), h = Math.max(1, st.height);
		if (!HZ.lockedScreen) draw();
		int[] normal = oldPicture != null && oldW == w && oldH == h ? oldPicture : screen(w, h), inverse = HzEffects.inverted(normal);
		Gfx g = gfx();
		frameKeyed = false;
		boolean locked = HZ.lockedScreen;
		HZ.lockedScreen = true;
		try {
			for (int i = 0; i < n && !TTalk.stop; i++) {
				g.putPixels(inverse, 0, 0, w, h); host.present(); park(50_000_000L);
				g.putPixels(normal, 0, 0, w, h); host.present(); park(50_000_000L);
			}
		} finally { HZ.lockedScreen = locked; }
		g.putPixels(normal, 0, 0, w, h); host.present();
	}

	// ---------------------------------------------------------------- input

	private void event(int[] e, boolean whileBusy) {
		int type = e[0], x = e[1], y = e[2];
		PlayerInput.note(e); // what a 68K external polls
		// a key the page's input method took (Japanese input on): held for the externals that poll keys, no keyDown (the text
		// comes when the input method gives it)
		if (type == PlayerHost.KEY_DOWN && e.length > 6 && (e[6] & 16) != 0) return;
		OStack st = HZ.stack();
		OCard cd = st == null ? null : st.curCard;
		if (trace != null) trace.accept("event " + type + " " + x + "," + y + " busy=" + whileBusy + " idle=" + TTalk.idle + " card=" + (cd == null ? "-" : cd.id)
			+ (type == PlayerHost.DOWN ? " part=" + HeadlessPointer.partAt(x, y, new boolean[1]) : type == PlayerHost.UP ? " pressed=" + pressed
			: type == PlayerHost.KEY_DOWN ? " key=" + e[4] + " char=" + e[5] + " open=" + TTalk.openField + " clicked=" + HZ.clickField : ""));
		// a press on a Picture or Palette window, a drag of one by its title bar: that window's
		// (not on an external's modal dialog: it is in front of the palettes)
		if ((type == PlayerHost.DOWN || type == PlayerHost.UP) && !(type == PlayerHost.DOWN && CardOverlay.modalAt(x, y))
				&& PictureWindows.press(type == PlayerHost.DOWN, x, y, cd, whileBusy || !TTalk.idle)) { dirty = true; return; }
		if (type == PlayerHost.MOVE && PictureWindows.pointer(x, y)) { dirty = true; return; }
		// a press on a 68K external's window (and its release) is that window's, not HyperCard's (P4-5)
		Externals68k x68 = Externals68k.Runner.current;
		if (x68 != null) {
			if ((type == PlayerHost.DOWN || type == PlayerHost.UP) && x68.pressAt(type == PlayerHost.DOWN, x, y, e[3])) return;
			if (type == PlayerHost.MOVE && x68.pointerAt(x, y)) return; // dragging an external's window by its title bar
			if ((type == PlayerHost.KEY_DOWN || type == PlayerHost.KEY_UP) && e[4] >= 0 && x68.key(e)) return; // its window has the keyboard
		}
		switch (type) {
			case PlayerHost.DOWN: {
				// off the card (a part sticking out still takes it): nothing, as click at (HeadlessPointer.click); while a handler
				// runs, a click on the screen round the card window is still its mouseClick (HyperPiece 2's カードフラフラ〜: repeat
				// until the mouseClick moves the card window off the page, and a click brings it back)
				if (st == null || (x < 0 || y < 0 || x >= st.width || y >= st.height) && HeadlessPointer.partAt(x, y, new boolean[1]) == null) {
					if (st != null && (whileBusy || !TTalk.idle)) {
						HeadlessProps.set("clickh", Integer.toString(x)); HeadlessProps.set("clickv", Integer.toString(y));
						HeadlessProps.set("clickloc", x + "," + y);
						PlayerUiHost.mouseClicked = true;
					}
					return;
				}
				HeadlessProps.set("clickh", Integer.toString(x)); HeadlessProps.set("clickv", Integer.toString(y));
				HeadlessProps.set("clickloc", x + "," + y);
				PlayerUiHost.mouseClicked = true;
				if (whileBusy || !TTalk.idle || cd == null) return;
				// a paint tool: the press paints (no messages, as HyperCard's paint tools)
				if (HzPaint.active()) { HzPaint.clickCount = Math.max(1, e[6]); HzPaint.down(x, y, keyNumbers(e[3])); painting = true; dirty = true; return; }
				// the button or field tool: the press selects, moves, sizes or makes a part (no messages, as HyperCard's)
				if (HzAuthTool.active()) { HzAuthTool.down(x, y, e[3], e[6]); authoring = true; dirty = true; return; }
				boolean[] absorbed = {false};
				OObject part = HeadlessPointer.partAt(x, y, absorbed);
				if (absorbed[0]) return;
				doubleClick = e[6] >= 2;
				// a scrolling field's scroll bar: its thumb dragged, a page up or down beside it; no messages (PointerUI.scrollBarClick)
				if (part instanceof OField sf && sf.style == 5 && x >= sf.left + sf.width - 17) { scrollBarPress(sf, y); return; }
				noteClick(part, x, y, st);
				listPicking = part instanceof OField lf && lf.isListField() && !lf.multipleLines ? lf : null;
				if (part instanceof OField f && !f.enabled) {
					// an unlocked field: it opens with the insertion point where the click is; a drag selects from there, a
					// double click the word, Shift extends the selection the field has. openField comes after the click has put
					// the insertion point (HyperCard: TextEdit tracks the click first), so what its handler selects stays: Living
					// Labyrinth's Slow down field ("on openField select text of me") had its typing added to the old value
					boolean wasOpen = TTalk.openField == f;
					HZ.clickField = f;
					int at = CardRenderer.hitText(gfx(), f, st, x - f.left, y - f.top);
					int from = at, to = at;
					if (doubleClick) {
						String text = f.getText() == null ? "" : f.getText().replace("\r\n", "\n").replace('\r', '\n');
						int[] r = TUtil.clickedTextRange(f, text, CardRenderer.hitClick(gfx(), f, st, x - f.left, y - f.top), true);
						if (r != null && r.length == 2) { from = r[0]; to = Math.min(r[1], text.length()); }
						selectAnchor = from;
					} else if (wasOpen && (e[3] & PlayerHost.SHIFT) != 0) {
						int s0 = f.view().getSelectionStart(), s1 = f.view().getSelectionEnd();
						selectAnchor = selecting == f || at >= s0 ? s0 : s1;
						from = Math.min(selectAnchor, at); to = Math.max(selectAnchor, at);
					} else selectAnchor = at;
					f.view().setSelectionStart(from); f.view().setSelectionEnd(to);
					selecting = doubleClick ? null : f; selectScrolledAt = 0; // (a double click keeps its word)
					selectedFrom = from; selectedTo = to;
					if (!wasOpen) TTalk.openFieldByUser(f);
					dirty = true;
					return;
				}
				if (part instanceof OButton pb && pb.style == 8) {
					// a pop-up button: mouseDown, then its menu on the page; the chosen line comes back as POPUP (mouseUp then,
					// as the desktop's GPopup; nothing chosen: no mouseUp)
					TTalk.CallMessage("mouseDown", pb);
					popupOpen = pb;
					host.post("popup", popupMenuText(pb));
					return;
				}
				if (part instanceof OButton b && b.getAutoHilite() || part == null) TTalk.closeOpenFieldByUser();
				OObject target = part != null ? part : cd;
				// (a check box or radio button too: CardRenderer draws it held, not hilited; Sticky Buttons' Highlight Grouped Text
				// showed nothing while pressed)
				if (target instanceof OButton b && b.getAutoHilite()) { tracking = b; CardRenderer.pressed = b; dirty = true; }
				if (!doubleClick) TTalk.CallMessage("mouseDown", target);
				pressed = target;
				return;
			}
			case PlayerHost.UP: {
				if (UiHost.get() instanceof PlayerUiHost ui) ui.mouseReleased(); // a cursor set during the press goes now
				OObject target = pressed;
				pressed = null;
				// -1: the page lost the press (player.js cancelPress: pointer cancelled, the window lost the focus): it ends where
				// the pointer was last, without the mouseUp a release there would send (nothing dropped)
				boolean cancelled = e[6] == -1;
				if (listPicking != null && !cancelled) pickListLine(listPicking, x, y);
				selecting = null; scrolling = null; listPicking = null;
				if (painting) { painting = false; HzPaint.up(x, y); dirty = true; return; }
				if (authoring) { authoring = false; HzAuthTool.up(x, y); dirty = true; return; }
				if (tracking != null) { CardRenderer.pressed = null; tracking = null; dirty = true; }
				// a release while the press's own mouseDown/mouseStillDown handler runs: mouseUp after it (queued), as HyperCard
				// (TTalk.callPendingMouseUp on the desktop); a press while busy sent nothing, so its release sends nothing
				if (target == null || cd == null) return;
				// a card press: mouseUp only when let go inside the card window, as the desktop (GUI.releasedInCard; HyperCard);
				// DancingButton4: pressed on the card, dragged to the screen's edge and let go there started the dance again
				boolean inside = target instanceof OCard ? x >= 0 && y >= 0 && x < st.width && y < st.height
					: x >= target.left && y >= target.top && x < target.left + target.width && y < target.top + target.height;
				if (!inside || cancelled) return;
				if (target instanceof OButton b && b.getAutoHilite() && (b.style == 9 || b.style == 10)) {
					b.setHilite(b.style == 10 || !b.getHilite());
				}
				TTalk.CallMessage(doubleClick ? "mouseDoubleClick" : "mouseUp", target);
				PlayerUiHost.mouseClicked = false; // the click went to the message, not to the mouseClick
				return;
			}
			case PlayerHost.MOVE:
				// (also while the press's mouseDown/mouseStillDown handler runs: the desktop's drag does it on the event thread)
				if (listPicking != null && host.mouseIsDown()) pickListLine(listPicking, x, y);
				if (!whileBusy && UiHost.get() instanceof PlayerUiHost ui) ui.pointerMoved();
				return;
			case PlayerHost.WHEEL: {
				// the wheel scrolls the scrolling field under the pointer (the desktop's scroll pane does, also while a handler runs)
				if (HeadlessPointer.partAt(x, y, new boolean[1]) instanceof OField f && f.style == 5) {
					int[] t = CardRenderer.scrollThumb(f);
					if (t != null) scrollTo(f, f.scroll + e[6], t[3]);
				}
				return;
			}
			case PlayerHost.FLUSH: PlayerSaving.flush(host); return; // the page is hidden or closing: its stacks' changes kept now
			case PlayerHost.KEY_DOWN: key(e, cd); return;
			case PlayerHost.KEY_UP: return; // (the page tells releases too, with -1 when it lost the keyboard: nothing reads them now)
			case PlayerHost.MESSAGE_BOX: {
				String line = host.eventText(e[4], e[6]);
				UiHost.get().setMsgText(line);
				// (an expression's value goes into the message box when it has run: TTalk.messageBoxValue)
				if (cd != null && !line.trim().isEmpty()) TTalk.queueMessageBoxLine(line, cd);
				return;
			}
			case PlayerHost.PART_INFO: {
				// a change in Button Info / Field Info: into the part now, or after the handler that runs (not in the middle of it)
				String change = host.eventText(e[4], e[6]);
				if (whileBusy || !TTalk.idle) infoChanges.add(change); else PartInfo.apply(host, change);
				dirty = true;
				return;
			}
			case PlayerHost.POPUP: {
				OButton b = popupOpen;
				popupOpen = null;
				int line;
				try { line = Integer.parseInt(host.eventText(e[4], e[6]).trim()); } catch (NumberFormatException nfe) { line = 0; }
				if (b == null || line < 1 || cd == null || !partOnCard(b, cd)) return;
				b.setSelectedLine(line);
				if (whileBusy || !TTalk.idle) { PlayerUiHost.mouseClicked = true; return; } // (the desktop: GUI.mouseClicked)
				TTalk.CallMessage("mouseUp", b);
				dirty = true;
				return;
			}
			case PlayerHost.MENU: {
				String[] item = host.eventText(e[4], e[6]).split("\n", 2);
				// chosen while a handler runs (a wait, a repeat until the mouseClick): dropped, as the desktop's menus do
				// (GMenuBrowse: nothing while a script runs) - the page greys its menu bar then (busy). Queued for after the
				// handler, a choice made during M.J.DASH's opening (all in openStack) did nothing and then moved the card later
				if (whileBusy || !TTalk.idle) return;
				if (cd != null && item.length == 2) TTalk.CallMessage("doMenu", new String[]{item[1], item[0]}, cd);
				return;
			}
			default:
		}
	}

	/** What the page shows for a pop-up button's menu: "left,top,right,bottom" (card pixels), the chosen line, "font\tsize\tstyle",
	 *  then its lines (the button's contents), one a line */
	static String popupMenuText(OButton b) {
		StringBuilder t = new StringBuilder();
		t.append(b.left).append(',').append(b.top).append(',').append(b.left + b.width).append(',').append(b.top + b.height).append('\n');
		t.append(b.getSelectedLine()).append('\n');
		t.append(b.textFont == null ? "" : b.textFont).append('\t').append(b.textSize).append('\t').append(b.textStyle).append('\n');
		String text = b.getText() == null ? "" : b.getText().replace("\r\n", "\n").replace('\r', '\n');
		if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
		t.append(text);
		return t.toString();
	}
	/** The part is still one of this card's or its background's (a mouseDown handler may have gone to another card) */
	private static boolean partOnCard(OObject part, OCard cd) {
		return cd.btnList.contains(part) || cd.bg != null && cd.bg.btnList.contains(part);
	}

	/** A press on the card: the clickLine, clickChunk and clickText name the clicked field's line and word (empty after a press
	 *  elsewhere), as GUI.noteFieldClick and PointerUI's functions on the desktop; a locked list field (autoSelect) hilites
	 *  the clicked line, below the text its last. HyperPiece 2's index (select clickline() / go card the selectedText) moved
	 *  no card in the browser: these stayed empty. */
	/** HyperTalk's numbers of the modifier keys held (11 shift, 12 option, 13 control, 14 command) */
	private static int[] keyNumbers(int mods) {
		java.util.List<Integer> k = new java.util.ArrayList<>();
		if ((mods & PlayerHost.SHIFT) != 0) k.add(11);
		if ((mods & PlayerHost.OPTION) != 0) k.add(12);
		if ((mods & PlayerHost.CONTROL) != 0) k.add(13);
		if ((mods & PlayerHost.COMMAND) != 0) k.add(14);
		int[] a = new int[k.size()];
		for (int i = 0; i < a.length; i++) a[i] = k.get(i);
		return a;
	}

	/** A press on a scrolling field's scroll bar: on the thumb it is dragged from there (dragThumb), above or below it the
	 *  field scrolls by what it shows */
	private void scrollBarPress(OField f, int y) {
		int[] t = CardRenderer.scrollThumb(f);
		if (t == null) return;
		int ly = y - f.top;
		if (ly >= t[0] && ly < t[0] + t[1]) { scrolling = f; scrollGrab = ly - t[0]; return; }
		int page = Math.max(1, CardRenderer.scrollVisible(f) - Math.max(1, f.lineHeight()));
		scrollTo(f, f.scroll + (ly < t[0] ? -page : page), t[3]);
	}
	/** The thumb follows the pointer: the scroll in proportion to where it is in the bar */
	private void dragThumb(OField f) {
		int[] t = CardRenderer.scrollThumb(f);
		if (t == null) return;
		int room = t[2] - t[1];
		int at = host.mouseY() - f.top - scrollGrab - CardRenderer.SCROLL_TRACK_TOP;
		scrollTo(f, room <= 0 ? 0 : (int) Math.round((double) at * t[3] / room), t[3]);
	}
	private void scrollTo(OField f, int v, int max) {
		v = Math.max(0, Math.min(v, max));
		if (v != f.scroll) { f.setScroll(v); dirty = true; }
	}
	/** A drag in an open field: the selection from where the press was to the pointer; past the top or bottom of a scrolling
	 *  field it scrolls a line at a time (every 50 ms) */
	private void dragSelect(OField f) {
		OStack st = HZ.stack();
		if (st == null || TTalk.openField != f || !f.isShown()) { selecting = null; return; }
		// a handler (openField's select text of me) chose the selection since: it stays, the press selects no more
		if (f.view().getSelectionStart() != selectedFrom || f.view().getSelectionEnd() != selectedTo) { selecting = null; return; }
		int mx = host.mouseX(), my = host.mouseY();
		if (f.style == 5 && (my < f.top || my >= f.top + f.height)) {
			long now = host.nanoTime();
			int[] t = CardRenderer.scrollThumb(f);
			if (t != null && now - selectScrolledAt >= 50_000_000L) {
				selectScrolledAt = now;
				int line = Math.max(1, f.lineHeight());
				scrollTo(f, f.scroll + (my < f.top ? -line : line), t[3]);
			}
		}
		int at = CardRenderer.hitText(gfx(), f, st, mx - f.left, Math.max(0, Math.min(f.height - 1, my - f.top)));
		int from = Math.min(selectAnchor, at), to = Math.max(selectAnchor, at);
		if (from != f.view().getSelectionStart() || to != f.view().getSelectionEnd()) {
			f.view().setSelectionStart(from); f.view().setSelectionEnd(to);
			selectedFrom = from; selectedTo = to;
			dirty = true;
		}
	}

	/** A pressed list field's selected line follows the pointer while it is over the field's text (not its scroll bar or frame);
	 *  dragged off the field the line stays the last one it was on, as GUI's MyTextArea mouseDragged (HC J-2.3: drag from
	 *  line 2 to 6 gives "line 6 to 6"). Drag and Drop 1.1 reads the selectedLine at its mouseUp as where the line is dropped:
	 *  in the browser it stayed the pressed line, so no line ever moved. */
	private void pickListLine(OField f, int x, int y) {
		OStack st = HZ.stack();
		if (st == null || st.curCard == null || !f.isShown() || !f.isListField()
			|| !st.curCard.fldList.contains(f) && (st.curCard.bg == null || !st.curCard.bg.fldList.contains(f))) { listPicking = null; return; }
		int right = f.left + f.width - (f.style == 5 ? 16 : 1);
		if (x < f.left + 1 || x >= right || y < f.top + 1 || y >= f.top + f.height - 1) return;
		String text = f.getText() == null ? "" : f.getText().replace("\r\n", "\n").replace('\r', '\n');
		int lines = OField.textLineCount(text);
		int[] hit = CardRenderer.hitClick(gfx(), f, st, x - f.left, y - f.top);
		if (lines <= 0 || hit[0] < 0) return;
		int n = 1;
		for (int k = 0; k < hit[0] && k < text.length(); k++) if (text.charAt(k) == '\n') n++;
		n = Math.min(lines, Math.max(1, n));
		if (f.getSelectedLine() != n) { f.setSelectedLine(n); dirty = true; }
	}

	/** The card on show at the last press (the clickChunk is empty on another, PlayerUiHost) */
	static volatile OCard clickCard;
	private void noteClick(OObject part, int x, int y, OStack st) {
		String line = "", chunk = "", word = "";
		clickCard = st.curCard;
		if (part instanceof OField f && !f.outlineStyle && !f.tableStyle) {
			String text = f.getText() == null ? "" : f.getText().replace("\r\n", "\n").replace('\r', '\n');
			int[] hit = CardRenderer.hitClick(gfx(), f, st, x - f.left, y - f.top);
			line = TUtil.clickedLineRef(f, text, hit, false, y - f.top);
			int[] r = TUtil.clickedTextRange(f, text, hit, true);
			if (r != null) {
				chunk = "char " + (r[0] + 1) + " to " + r[1] + " of " + TUtil.fieldNumberRef(f);
				if (r.length == 2) word = text.substring(r[0], Math.min(r[1], text.length()));
			}
			if (f.enabled && f.autoSelect && f.dontWrap) {
				int lines = OField.textLineCount(text);
				if (lines > 0 && hit[0] >= 0) {
					int n = 1;
					for (int k = 0; k < hit[0] && k < text.length(); k++) if (text.charAt(k) == '\n') n++;
					f.setSelectedLine(Math.min(lines, Math.max(1, n)));
					dirty = true;
				}
			}
		}
		HeadlessProps.set("clickline", line);
		HeadlessProps.set("clickchunk", chunk);
		HeadlessProps.set("clicktext", word);
	}

	private void key(int[] e, OCard cd) {
		if (cd == null) return;
		int code = e[4], ch = e[5], mods = e[3];
		OField f = TTalk.openField;
		boolean command = (mods & PlayerHost.COMMAND) != 0 || (mods & PlayerHost.CONTROL) != 0 && ch != 0 && ch < 128 && Character.isLetter(ch);
		String c = ch != 0 ? new String(Character.toChars(ch)) : null;
		// as a command line (like keyDown below): when no handler takes it, HyperCard's own action chooses the menu item with that
		// Command key (on the desktop Swing's menu accelerators do that)
		// Control and a key on a Mac (elsewhere Control is Command, as on the desktop): controlKey to the card with the key's
		// control code (Control-B 2), as GUI.keyPressed
		if ((mods & PlayerHost.CONTROL) != 0 && (mods & PlayerHost.COMMAND) == 0 && (e[6] & 8) == 0 && ch >= 32 && ch < 128) {
			TTalk.CallMessage("controlKey", Integer.toString(TUtil.controlKeyNumber(ch)), cd, false, false);
			return;
		}
		if (command && c != null) { TTalk.fieldKeyCommand("commandKeyDown " + (c.equals("\"") ? "quote" : "\"" + c + "\""), cd); return; }
		// Delete with a part the button or field tool selected: the part goes
		if (f == null && (code == 8 || code == 46) && HzAuthTool.deleteKey()) { dirty = true; return; }
		// Delete with a paint selection: the selected picture goes (Clear Picture)
		if (f == null && (code == 8 || code == 46) && HzPaint.active() && HzPaint.floating != null) { HzPaint.menu("clear picture"); dirty = true; return; }
		// the text tool's insertion point: the keys are painted (HzPaint)
		if (f == null && HzPaint.typing() && (code == 8 || code == 13 || c != null && ch >= 32)) {
			HzPaint.type(code == 8 ? "\b" : code == 13 ? "\n" : c);
			dirty = true;
			return;
		}
		switch (code) {
			case 13: {
				// Enter is Return on the keypad. keyDown comes first, with return or numToChar(3), on the card and in the open
				// field; returnKey/enterKey or returnInField/enterInField only when it is passed (HC J-2.3, as GUI.keyPressed)
				boolean enter = (e[6] & 1) != 0;
				if (f != null) TTalk.callKeyDown(enter ? "\u0003" : "\n", enter ? "enterInField" : "returnInField", f);
				else TTalk.callKeyDown(enter ? "\u0003" : "\n", enter ? "enterKey" : "returnKey", cd);
				return;
			}
			case 9:
				// keyDown numToChar(9) first (HC J-2.3: the Keyboard stack's card keyDown kept Tab, no field opened), then the
				// tabKey command, also on the card (GUI's Tab): the message, then HyperCard's own action, the next unlocked
				// field opened with its text selected (HyperPiece 2 card 19 fills its fields as Tab opens them)
				TTalk.callKeyDown("\t", "tabKey", f != null ? f : cd);
				return;
			case 37: case 38: case 39: case 40: {
				String dir = code == 37 ? "left" : code == 38 ? "up" : code == 39 ? "right" : "down";
				int k = code == 37 ? 28 : code == 39 ? 29 : code == 38 ? 30 : 31;
				TTalk.callKeyDown(String.valueOf((char) k), dir, f != null ? f : cd);
				return;
			}
			case 8:
				// Delete: keyDown numToChar(8), also on the card (GUI.keyPressed; HC J-2.3: Hyper-Wordle's card keyDown takes it)
				if (f != null) TTalk.fieldKeyCommand("keyDown numToChar(8)", f);
				else TTalk.callKeyDown(String.valueOf((char) 8), null, cd);
				return;
			case 46:
				// Forward Delete: keyDown numToChar(127), on the card as the desktop; in the open field the character after the
				// insertion point goes when it is passed (HC J-2.3 sends keyDown 127 there too; nothing at all happened here)
				TTalk.callKeyDown(String.valueOf((char) 127), f != null ? "forwardDelete" : null, f != null ? f : cd);
				return;
			case 27:
				TTalk.callKeyDown(String.valueOf((char) 27), "back", f != null ? f : cd); // (from the open field, as GUI.fieldKey)
				return;
			default:
		}
		if (c == null) return;
		String quoted = c.equals("\"") ? "quote" : "\"" + c + "\"";
		if (f != null) TTalk.fieldKeyCommand("keyDown " + quoted, f);
		else TTalk.callKeyDown(c, null, cd);
	}

	/** The part the pointer is over (mouseEnter sent), or null */
	private OObject within;

	/** mouseLeave and mouseEnter when the part under the pointer changed, compared when nothing runs (so the pointer going
	 *  straight from one button to the next while a handler ran sends both, as GUI.flushPointerMessages); not while the
	 *  button is held. mouseWithin goes to it at each idle. */
	private void pointer() {
		OStack st = HZ.stack();
		if (st == null || st.curCard == null || !TTalk.idle || !TTalk.messageList.isEmpty() || host.mouseIsDown() || popupOpen != null) return;
		if (HzAuthTool.active() || HzPaint.active()) return; // (the other tools send no pointer messages)
		int x = host.mouseX(), y = host.mouseY();
		boolean[] absorbed = {false};
		OObject part = x < 0 || y < 0 || x >= st.width || y >= st.height ? null : HeadlessPointer.partAt(x, y, absorbed);
		if (absorbed[0] || part instanceof OCardBase) part = null;
		if (part == within) return;
		OObject left = within;
		within = part;
		if (left != null && UiHost.get().gButtonOnCurrentCard(left)) TTalk.CallMessage("mouseLeave", left);
		if (part != null) TTalk.CallMessage("mouseEnter", part);
	}

	/** mouseStillDown while the button is held and nothing runs (GUI.startStillDown); the tracked button's look follows the
	 *  pointer (in it: hilited). */
	private void stillDown() {
		// a window dragged by its title bar follows the pointer (the page tells moves only as the pointer's place)
		if (host.mouseIsDown() && PictureWindows.pointer(host.mouseX(), host.mouseY())) dirty = true;
		if (!host.mouseIsDown()) { selecting = null; scrolling = null; listPicking = null; }
		if (listPicking != null) pickListLine(listPicking, host.mouseX(), host.mouseY());
		if (painting) { if (host.mouseIsDown()) HzPaint.move(host.mouseX(), host.mouseY()); else { painting = false; HzPaint.up(host.mouseX(), host.mouseY()); } }
		if (authoring) {
			if (host.mouseIsDown()) { if (HzAuthTool.move(host.mouseX(), host.mouseY())) dirty = true; }
			else { authoring = false; HzAuthTool.up(host.mouseX(), host.mouseY()); dirty = true; }
		}
		if (selecting != null) dragSelect(selecting);
		if (scrolling != null) dragThumb(scrolling);
		if (tracking != null) {
			int x = host.mouseX(), y = host.mouseY();
			boolean in = x >= tracking.left && y >= tracking.top && x < tracking.left + tracking.width && y < tracking.top + tracking.height;
			OButton shown = in ? tracking : null;
			if (CardRenderer.pressed != shown) { CardRenderer.pressed = shown; dirty = true; }
		}
		if (pressed != null && host.mouseIsDown() && TTalk.idle && TTalk.messageList.isEmpty() && !doubleClick)
			TTalk.CallMessage("mouseStillDown", pressed);
	}
}

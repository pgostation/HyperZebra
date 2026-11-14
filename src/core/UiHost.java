/**
 * The desktop-only work the core used to do itself: menus, windows, Swing views (moved unchanged into src/ui by
 * tools/wasm-core/rewrites/move_to_host.py, listed in tools/wasm-core/moves.txt). Each method's body here is what the
 * headless core and the browser do; the desktop's {@code SwingUiHost} (src/ui) does what the code did before.
 * Found by name, as {@link Host} is, so a build of the core alone never pulls in the desktop.
 */
class UiHost {
	private static volatile UiHost current;

	static UiHost get() {
		UiHost h = current;
		if (h == null) current = h = byName(System.getProperty("hz.uihost", "SwingUiHost"));
		return h;
	}
	static void set(UiHost h) { current = h; }

	private static UiHost byName(String name) {
		try { return (UiHost) Class.forName(name).getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return new UiHost(); }
	}

	// ---- dialogs ----
	/** answer: the button clicked (b3 is the default). */
	String answer(OStack stack, String text, String b1, String b2, String b3) { return HeadlessDialogs.answer(text, b1, b2, b3); }
	/** ask [password]: {the button, the text typed}. */
	String[] ask(OStack stack, String text, String input, String cancel, String ok, boolean hidden) { return HeadlessDialogs.ask(text, input, cancel, ok, hidden); }

	// ---- type (TTalk.type, by hand: KeysUI) ----
	/** The letters into the paint text, a field's caret or the message box; false: nothing took them. */
	boolean typeIntoViews(String text, int[] modKeys, OField fieldWithCaret) throws xTalkException { return false; }
	/** Letters with modifier keys, through the OS keyboard. */
	void typeWithKeyboard(String text, int[] modKeys) {}

	// ---- menus (S6): what choosing an item does, the user level, the shown menu bar ----
	/** doMenu after no script took it: the item's built-in action or menu message. */
	void doMenu(String item, String menuName) throws xTalkException { HeadlessMenus.doMenu(item, menuName); }
	/** One of HyperCard's own menu commands by name (F1-F4: Undo, Cut, Copy, Paste). */
	void doMenuCommand(String name) throws xTalkException { HeadlessMenus.command(name); }
	/** The doMenu command of a script: with its modifier keys held and the "without dialog" choice. */
	void doMenuFromScript(String item, String menuName, boolean withoutDialog, int[] modKeys) throws xTalkException { HeadlessMenus.doMenu(item, menuName); }
	/** The menus follow the user level (items hidden or disabled below it). */
	void enforceUserLevel() {}
	/** reset menuBar: the standard menus again. */
	void resetMenuBar() { if (MenuNodes.get() instanceof PlainMenus) ((PlainMenus) MenuNodes.get()).reset(); }
	/** The checks of the paint Options menu: Grid, Draw Filled, Draw Centered, Draw Multiple. */
	boolean[] paintOptionChecks() {
		return new boolean[]{"true".equals(HeadlessProps.get("grid")), "true".equals(HeadlessProps.get("filled")),
			"true".equals(HeadlessProps.get("centered")), "true".equals(HeadlessProps.get("multiple"))};
	}
	/** The keyboard script the Keyboards menu has checked (0 Roman, 1 Japanese). */
	int keyScript() { return keyScript; }
	void setKeyScript(int script) { keyScript = script; }
	private int keyScript;
	/** The font families of the computer (the Font menu's items besides HyperCard's classic ones). */
	String[] fontFamilyNames() { return new String[0]; }
	/** Whether the card window shows its menu bar; null when it has none. */
	Boolean menuBarShown() { return null; }

	// ---- the message box (S5): GMsg.msg (the window) and GMsg.area (its text) on the desktop ----
	private String msgText = "";
	private int msgSelStart, msgSelEnd;
	private boolean msgShown, msgTyped;
	/** There is a message box (GMsg.msg exists). */
	boolean msgExists() { return true; }
	/** Its text field (GMsg.area exists). */
	boolean msgHasArea() { return true; }
	/** The card window and its message box's text field are there (PCARD.pc, its msg, GMsg.area). */
	boolean msgInCardWindow() { return true; }
	boolean msgVisible() { return msgShown; }
	void setMsgVisible(boolean shown) { msgShown = shown; }
	/** The window's text (GMsg.msg.getText) and the field's (GMsg.area.getText): the same here. */
	String msgText() { return msgText; }
	String msgAreaText() { return msgText; }
	void setMsgText(String text) { msgText = text == null ? "" : text; msgSelStart = msgSelEnd = msgText.length(); msgTyped = false; }
	int msgSelectionStart() { return Math.min(msgSelStart, msgText.length()); }
	int msgSelectionEnd() { return Math.min(msgSelEnd, msgText.length()); }
	String msgSelectedText() { int s = msgSelectionStart(), e = msgSelectionEnd(); return e > s ? msgText.substring(s, e) : null; }
	void msgSelect(int start, int end) { msgSelStart = Math.max(0, Math.min(start, msgText.length())); msgSelEnd = Math.max(msgSelStart, Math.min(end, msgText.length())); }
	void msgSetCaret(int at) { msgSelStart = msgSelEnd = Math.max(0, Math.min(at, msgText.length())); }
	void msgFocus() {}
	/** Text was typed into it since its text was set (GMsg.msg.typedInto). */
	boolean msgTypedInto() { return msgTyped; }

	/** What a button's view shows as its hilite, null when it has no view (the headless core: the model's hilite). */
	Boolean buttonViewHilite(OButton b) { return null; }

	// ---- reading a stack (S9) ----
	/** The wait cursor while a stack is read; run the result to end it. */
	Runnable beginBusy() { return () -> {}; }
	/** A sealed _stack.xml (Protect Stack's password) unsealed, or null when it could not be (XMLRead.lockedOut then). The
	 *  headless core has no StackProtection: the bytes as they are (a sealed file does not parse: the stack opens read-only). */
	byte[] unsealStack(byte[] data, String xmlfile, OStack stack) { return data; }
	/** After reading: the sealed pictures' key for the readers that only have the files. */
	void registerStackContentKey(OStack stack, String xmlfile) {}
	/** The stack could be read only partly: it opens read-only, with a notice. */
	void readOnlyNotice(OStack stack, String message) { System.err.println(message + " The stack is opened read-only."); }

	// ---- fields (S5) ----
	private final java.util.WeakHashMap<OField, PlainFieldView> fieldViews = new java.util.WeakHashMap<>();
	/** The field's view for its text selection (the headless core: one kept for each field). */
	FieldView fieldView(OField f) {
		synchronized (fieldViews) { return fieldViews.computeIfAbsent(f, PlainFieldView::new); }
	}

	// ---- stacks (S10) ----
	/** A stack object to read a stack into (the desktop's has the card window). */
	OStack newStack() { return new OStack(null); }
	/** Where the card window is on the screen, null when it is not showing. */
	java.awt.Point cardWindowLocation() { return null; }
	/** The card window has no title bar. */
	boolean cardWindowUndecorated() { return false; }
	/** The script editor keeps unsaved scripts of the stack being left (Cancel): the stack stays. */
	boolean scriptsKeptOnLeave(OStack s) { return false; }
	/** The stack being left is saved (or the user let it go unsaved); false: the stack stays. The headless core does
	 *  not write stacks. */
	boolean savedBeforeLeave(OStack s) { return true; }

	// ---- pictures and fonts (S10): the core has no images (P2) and no fonts ----
	/** A field's line height without a fixed one (OField.lineHeight): the classic strike's where known (as the desktop), else
	 *  size + size/4, about a host font's. */
	int fieldLineHeight(String font, int style, int size) {
		int[] m = OField.classicMetrics(font, size);
		return Math.max(1, m != null ? m[0] + m[1] : size + (size + 3) / 4);
	}
	/** The System's ICON 1 (note) and 2 (caution) are there (OButton.systemIcon). */
	boolean hasSystemIcon(int id) { return id == 1 || id == 2; }
	/** imageFilter, imageCombine, imageCreate, imageSize (TalkImage). */
	String talkImage(String function, String[] params) throws xTalkException { throw new xTalkException("Images are not available here: {0}", function); }

	// ---- shapes (S10): their geometry is the desktop's (java.awt.geom); the core has the rectangle ----
	float svgFilterPadding(SvgFilter f) { return 0; }
	/** Whether a shape's filter does anything (the core reads none: readSvgFilter) */
	boolean svgFilterActive(SvgFilter f) { return false; }
	/** The picture through the filter (SvgFilter.apply) */
	java.awt.image.BufferedImage applySvgFilter(SvgFilter f, java.awt.image.BufferedImage source) { return source; }
	/** SvgFilter.writeXML */
	void writeSvgFilter(SvgFilter f, XmlOut w) throws XmlException {}
	String svgFilterText(SvgFilter f) { return ""; }
	SvgFilter svgFilterFromText(String text) throws xTalkException { return null; }
	SvgFilter readSvgFilter(XmlIn r) throws XmlException { return null; }
	/** Whether the bytes are a picture (ImageIO reads them): the headless core knows PNG and JPEG by their first bytes. */
	boolean readableImage(byte[] data) throws java.io.IOException {
		if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return true;
		return data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
	}
	/** A text shape's lettering at its natural size (the glyphs' bounds): about 0.6 em a letter, 1 em high, here. */
	java.awt.Dimension shapeTextSize(OShape s) {
		int size = Math.max(1, s.fontSize);
		return new java.awt.Dimension(Math.max(1, (int) Math.ceil(s.text.length() * size * 0.6)), Math.max(1, (int) Math.ceil(size * 0.75)));
	}
	/** The bounds of a shape's rotated rectangle on the card (OShape.frame().getBounds()). */
	java.awt.Rectangle shapeFrameBounds(OShape s) {
		double r = Math.toRadians(s.rotation), c = Math.cos(r), n = Math.sin(r), cx = s.width / 2.0, cy = s.height / 2.0;
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (double[] p : new double[][]{{0, 0}, {s.width, 0}, {0, s.height}, {s.width, s.height}}) {
			double x = s.left + cx + (p[0] - cx) * c - (p[1] - cy) * n, y = s.top + cy + (p[0] - cx) * n + (p[1] - cy) * c;
			minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
		}
		int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
		return new java.awt.Rectangle(x0, y0, (int) Math.ceil(maxX) - x0, (int) Math.ceil(maxY) - y0);
	}

	// ---- beeps, the JIT, sound, the 68K externals' animation (S10) ----
	void beep() {}
	/** TalkJit: a hot loop, counted loop or handler as compiled code; null: interpret it. The core compiles nothing at run time:
	 *  it runs the code compiled when the stack was exported for the web (TalkJitAot), if any. */
	Result jitLoop(TTalk t, OObject object, OObject target, String message, java.util.ArrayList<String> scriptList, int line, int endRepeat,
			MemoryData memData, String[] parentparams, int mode) throws xTalkException {
		if(!TalkJit.enabled || TalkJit.AOT.isEmpty()) return null;
		return TalkJit.enterLoop(t, object, target, message, scriptList, line, endRepeat, memData, parentparams, mode);
	}
	Result jitCount(TTalk t, OObject object, OObject target, String message, java.util.ArrayList<String> scriptList, int line, int endRepeat, int from, int to,
			MemoryData memData, String[] parentparams, int mode) throws xTalkException {
		if(!TalkJit.enabled || TalkJit.AOT.isEmpty()) return null;
		return TalkJit.enterCount(t, object, target, message, scriptList, line, endRepeat, from, to, memData, parentparams, mode);
	}
	Result jitEnter(TTalk t, OObject object, OObject target, String message, java.util.ArrayList<String> scriptList, int start, int end,
			MemoryData memData, String[] parentparams, int mode) throws xTalkException {
		if(!TalkJit.enabled || TalkJit.AOT.isEmpty()) return null;
		return TalkJit.enter(t, object, target, message, scriptList, start, end, memData, parentparams, mode);
	}
	/** TalkJit's run-time compiler (the desktop's hidden classes); none in the core */
	TalkJit.Compiler talkJitCompiler() { return null; }
	/** debug sound on / off (TSound.use). */
	void setSoundOn(boolean on) {}

	// ---- files (S10) ----
	/** A script may read or write the file (access: READ, WRITE, READ_WRITE): on the desktop the Sandbox asks. */
	boolean allowScriptFile(java.io.File f, String access) { return true; }

	// ---- the rest of the desktop's pieces (S10 part 3) ----
	TSound newSound() { return null; }
	void playStop() {}
	boolean playNotes(OStack s, String sound, String[] notes, int tempo, int volume) throws xTalkException { return true; }
	String soundLastError() { return ""; }
	void focusCardPane() {}
	void repaintCardPane() {}
	boolean hasCardPane() { return false; }
	void setClipboardText(String text) { HeadlessProps.set("clipboard", text); }
	/** ask file's save dialog: the path chosen (as scripts see it), null for Cancel. */
	String askFile(OStack stack, String prompt, String filename) { return null; }
	/** A file a script wrote has been closed (close file): the browser player keeps it (PlayerUiHost) */
	void scriptFileWritten(java.io.File f) {}
	/** select <part>: the button, field or shape tool takes it. */
	void selectPart(OObject obj) {}
	/** A field has a view to select in (the headless core: its FieldView, always). */
	boolean fieldHasView(OField f) { return true; }
	/** click at x,y (card coordinates) into the unlocked field f: its insertion point there (the desktop: PointerUI, the Swing
	 *  view's caret; the player: CardRenderer's layout). Here, without a layout, at the end of its text. */
	void fieldInsertionPointAt(OField f, int x, int y) { int n = f.getText() == null ? 0 : f.getText().length(); f.view().setSelectionStart(n); f.view().setSelectionEnd(n); }
	void keepFieldScroll(OField f, Runnable r) { r.run(); }
	String browseUrl(java.net.URI uri) { return "Could not open the specified URL: no web browser."; }
	boolean isMessageWatcher(Object dialog) { return false; }
	boolean isVariableWatcher(Object dialog) { return false; }
	java.awt.Rectangle dialogBounds(Object dialog) { return new java.awt.Rectangle(); }
	String paintTextFontSetting() { return HeadlessProps.get("textfont"); }
	boolean focusOwner(javax.swing.JComponent c) { return false; }
	java.awt.Point cardScroll() { OStack st = HZ.stack(); return st != null && st.scroll != null ? new java.awt.Point(st.scroll.x, st.scroll.y) : new java.awt.Point(0, 0); }
	String pictureScroll(GPictWindow w) { return "0,0"; }
	java.awt.Rectangle toolPaletteBounds() { return null; }
	boolean isCardWindow(OWindow w) { return w == OWindow.cdwindow; }
	java.awt.Point cardWindowPlace() { return null; }
	void writeStackFile(OStack s, boolean lockChange) {}
	boolean pictureUndecorated(GPictWindow w) { return false; }
	boolean mp3Playing(TMP3 m) { return false; }
	void mp3Volume(TMP3 m, int v) {}
	void mp3Time(TMP3 m, int v) {}
	void pictureSetScroll(GPictWindow w, int x, int y) {}
	void pictureDithering(GPictWindow w, boolean on) {}
	void pictureScale(GPictWindow w, float scale) {}
	boolean pictureIsPalette(GPictWindow w) { return false; }
	String osaLanguage(OsaScript s) { return "AppleScript"; }
	String osaSubtype(OsaScript s) { return "ascr"; }
	java.io.File mountedDiskFolder(String name) { return null; }
	/** Volumes the host has besides its own disks, by name: the web player's page volumes (the CD a stack was exported from:
	 *  "Cosmic Osmo CD", PlayerUiHost). HyperTalk paths (mountedDiskFolder) and the 68K File Manager (FileSystem68k) name them. */
	java.util.Map<String, java.io.File> pageVolumes() { return java.util.Collections.emptyMap(); }
	/** The language HyperZebra starts in (PCARD.language), and the card window's (its lang, else that one). */
	String startLanguage() { return HZ.intl() != null ? HZ.intl().tag() : "en"; }
	String cardWindowLanguage() { return startLanguage(); }
	boolean japaneseCardWindow() { return HZ.intl() != null && International.sameLanguage(HZ.intl().tag(), "ja"); }
	AuthoringUndo newAuthoringUndo() { return null; }
	boolean stackHasPane(OStack s) { return false; }
	PatButton patternButton() { return null; }
	void repaintStackFrame(OStack s) {}
	boolean powerKeys() { return "true".equals(HeadlessProps.get("powerkeys")); }
	boolean onEventThread() { return false; }
	/** A paint layer or an OSA script in the XML: the headless core reads on past its element. */
	/** OsaScript.writeXML (the core reads none) */
	void writeOsaScript(OsaScript s, XmlOut w, String indentAfter) throws XmlException {}
	/** Whether the stack's _stack.xml carries the key its pictures are sealed with (the desktop: only when the XML itself is
	 *  sealed, StackProtection.sealsXml). The browser player keeps it as the export gave it (the XML unsealed, the key in it). */
	boolean writesContentKey(OStack s) { return true; }
	/** A shape's texture (OShape.texturePng: PNG bytes) as a picture; null when it is not one */
	java.awt.image.BufferedImage shapeTexture(byte[] png) {
		HzImage p = PngDecoder.decode(png);
		if (p == null) return null;
		java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(p.width, p.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		b.setRGB(0, 0, p.width, p.height, p.argb, 0, p.width);
		return b;
	}
	/** A <paintLayer/> of a card or background (its picture is read later, by its file name) */
	PaintLayer readPaintLayer(XmlIn r) { return PaintLayer.readXML(r); }
	OsaScript readOsaScript(XmlIn r) throws XmlException { r.skipElement(); return null; }

	// ---- windows (S10 part 4) ----
	OXWindow newXWindow(Xcmd68k.ExternalWindow w) { return null; }
	boolean windowShowing(OWindow w) { return false; }
	boolean toolPaletteVisible() { return false; }
	String pictureWindowName(GPictWindow w) { return ""; }

	// ---- S10 part 5 ----
	int popupSelectedIndex(MyPopup p) { return -1; }
	void selectPopupLine(MyPopup p, int line) {}
	void showFieldFont(OField f, String name, int style, double size) {}
	/** The card window has its menu bar (PCARD.pc.menu) and the paint menus (PCARD.pc.paintMenu): the headless core's
	 *  menus are PlainMenus'. */
	boolean hasCardMenus() { return true; }
	boolean hasPaintMenus() { return true; }
	/** A paint tool is chosen, so the menus are the paint bar's (Edit's Cut/Copy/Clear Picture, Paint, Options): the desktop's
	 *  card window with its paint tool; the player's HzPaint tool (PlayerUiHost) */
	boolean paintMenusShown() { return Host.get().hasCardWindow() && Host.get().paintToolActive(); }
	/** A paint selection is up (HyperCard's Edit menu then says Cut/Copy/Clear Picture) */
	boolean paintSelectionUp() { return false; }

	// ---- S10: the views plain TeaVM still met ----
	void showFieldSelection(OField f) {}
	void showOutlineSelection(OField f) {}
	void expandOutline(OField f) {}
	void shapeViewVisible(OShape s) {}
	void shapeViewFaded(OShape s, boolean visibleBefore) {}
	void shapeViewUpdated(OShape s) {}
	void clearButtonGroups() {}

	// ---- desktop classes' static methods (rewrites/facade.py, facades.txt) ----
	/** TXcmd.has68kExternal */
	boolean tXcmdHas68kExternal(OStack a0, java.lang.String a1, java.lang.String a2) { return false; }
	/** TXcmd.CallExternalCommandOf */
	Result tXcmdCallExternalCommandOf(OStack a0, java.lang.String a1, java.lang.String[] a2, OObject a3, MemoryData a4, boolean a5, Result a6) throws xTalkException { return null; }
	/** TXcmd.runnable68kExternal */
	boolean tXcmdRunnable68kExternal(java.lang.String a0, java.lang.String a1) { return false; }
	/** TXcmd.externalOwner */
	OStack tXcmdExternalOwner(java.lang.String a0, java.lang.String a1) { return null; }
	/** TXcmd.CallExternalCommand */
	Result tXcmdCallExternalCommand(java.lang.String a0, java.lang.String[] a1, OObject a2, MemoryData a3, boolean a4, Result a5) throws xTalkException { throw new xTalkException("Can't understand {0}", a0); }
	/** TXcmd.resourcesOf */
	Rsrc tXcmdResourcesOf(OStack a0) { return null; }
	/** TSound.use: HyperZebra's sound is on (the 68K VM's sound reads it) */
	boolean soundOn() { return true; }
	/** Xcmd68k.hasSessions */
	boolean xcmd68kHasSessions() { return false; }
	/** xcmd68kHasSessions without a lock (Xcmd68k.hasSessionsHint), for compiled HyperTalk */
	boolean xcmd68kHasSessionsHint() { return xcmd68kHasSessions(); }
	/** Xcmd68k.hasWindowSessions */
	boolean xcmd68kHasWindowSessions() { return false; }
	/** Xcmd68k.pumpIfDue */
	void xcmd68kPumpIfDue() {}
	/** Xcmd68k.pumpWhileWaiting */
	void xcmd68kPumpWhileWaiting() {}
	/** Xcmd68k.redrawHookedCards */
	boolean xcmd68kRedrawHookedCards(OStack a0) { return false; }
	/** Xcmd68k.redrawHookedCards */
	boolean xcmd68kRedrawHookedCards(OStack a0, int a1, int a2, int a3, int a4) { return false; }
	/** Xcmd68k.cardDrawingHooked */
	boolean xcmd68kCardDrawingHooked(OStack a0) { return false; }
	/** Xcmd68k.findWindow */
	Xcmd68k.ExternalWindow xcmd68kFindWindow(java.lang.String a0) { return null; }
	/** Xcmd68k.findWindowById */
	Xcmd68k.ExternalWindow xcmd68kFindWindowById(int a0) { return null; }
	/** MacApp68k.isApplication */
	boolean macApp68kIsApplication(java.io.File a0) { return false; }
	/** FileManager68k.programName */
	java.lang.String fileManager68kProgramName(StackTextEncoding a0, java.lang.String a1) { return a1; }
	/** FileManager68k.otherReadings */
	java.util.List<java.lang.String> fileManager68kOtherReadings(java.lang.String a0) { return new java.util.ArrayList<String>(); }
	/** FileManager68k.existingOrUnarchived */
	java.io.File fileManager68kExistingOrUnarchived(java.io.File a0, java.lang.String a1) { return null; }
	/** FileSystem68k.hostVolumeNamed */
	java.io.File fileSystem68kHostVolumeNamed(java.lang.String a0) { return null; }
	/** FileSystem68k.externalsVolume */
	java.io.File fileSystem68kExternalsVolume(OStack a0) { if (a0 == null || a0.path == null) return null; java.io.File f = new java.io.File(a0.path).getAbsoluteFile(); if (f.getName().equals("_stack.xml")) f = f.getParentFile(); return f == null ? null : f.getParentFile(); }
	/** FileSystem68k.macPathOf */
	java.lang.String fileSystem68kMacPathOf(java.io.File a0) { return null; }
	/** FileSystem68k.hyperZebraProgram */
	java.io.File fileSystem68kHyperZebraProgram() { return new java.io.File("/HyperZebra"); }
	/** StackBundle.host */
	java.io.File stackBundleHost(java.io.File a0) { return a0; }
	/** StackBundle.host */
	java.lang.String stackBundleHost(java.lang.String a0) { return a0; }
	/** StackBundle.packedOf */
	java.io.File stackBundlePackedOf(java.io.File a0) { return null; }
	/** StackBundle.isPacked */
	boolean stackBundleIsPacked(java.io.File a0) { return a0 != null && a0.isFile() && a0.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb"); }
	/** StackBundle.folderAround */
	java.io.File stackBundleFolderAround(java.io.File a0) { java.io.File f = a0 == null ? null : a0.getAbsoluteFile(); if (f == null) return null; String n = f.getName(); if (n.equals("_stack.xml") || n.equals("toc.xml")) f = f.getParentFile(); return f == null ? null : f.getParentFile(); }
	/** ScriptWeb.fetch */
	java.lang.String scriptWebFetch(java.lang.String a0) throws xTalkException { throw new xTalkException("Cannot open URL: {0}", a0); }
	/** ScriptWeb.store */
	void scriptWebStore(java.lang.String a0, java.lang.String a1, boolean a2) throws xTalkException { throw new xTalkException("Cannot open URL: {0}", a0); }
	/** Speech.programName */
	java.lang.String speechProgramName() { return null; }
	/** Speech.speak */
	void speechSpeak(java.lang.String a0, java.lang.String a1) {}
	/** Speech.stop */
	void speechStop() {}
	/** Speech.isGenderWord */
	boolean speechIsGenderWord(java.lang.String a0) { return a0.equalsIgnoreCase("male") || a0.equalsIgnoreCase("female") || a0.equalsIgnoreCase("neuter") || a0.equalsIgnoreCase("robotic"); }
	/** Speech.voiceForGender */
	java.lang.String speechVoiceForGender(java.lang.String a0) { return ""; }
	/** ScriptEditor.showRuntimeError */
	void scriptEditorShowRuntimeError(OObject a0) {}
	/** StackGuard.enter */
	OStack stackGuardEnter(OObject a0) { return null; }
	/** StackGuard.leave */
	void stackGuardLeave(OStack a0) {}
	/** StackGuard.runningStack */
	OStack stackGuardRunningStack() { return null; }
	/** StackGuard.allowsSet */
	boolean stackGuardAllowsSet(OObject a0, java.lang.String a1, java.lang.String a2) { return true; }
	/** GUI.dropSelectedRange */
	void gUIDropSelectedRange() {}
	/** GUI.cancelMouseUp */
	void gUICancelMouseUp() {}
	/** GUI.mouseUpCancelled */
	boolean gUIMouseUpCancelled() { return false; }
	/** GUI.forgetClicks */
	void gUIForgetClicks() {}
	/** VEffect.setOldOff */
	void vEffectSetOldOff() {}
	/** VEffect.lockLazily */
	void vEffectLockLazily() {}
	/** VEffect.visualEffect */
	void vEffectVisualEffect(int a0, int a1, int a2) {}
	/** VEffect.visualEffect */
	void vEffectVisualEffect(int a0, int a1, int a2, boolean a3) {}
	/** VEffect.holdPlain */
	void vEffectHoldPlain(int a0) {}
	/** Sandbox.allowOther */
	boolean sandboxAllowOther(java.lang.String a0, java.lang.String a1, java.lang.String a2) { return true; }
	/** Sandbox.scriptRequester */
	java.lang.String sandboxScriptRequester() { return ""; }
	/** PictureTranslation.shown */
	java.awt.image.BufferedImage pictureTranslationShown(OStack a0, java.awt.image.BufferedImage a1) { return a1; }
	/** PictureTranslation.shown */
	java.awt.image.BufferedImage pictureTranslationShown(OCardBase a0, java.awt.image.BufferedImage a1) { return a1; }
	/** PictureTranslation.enabledFor */
	boolean pictureTranslationEnabledFor(java.io.File a0) { return false; }
	/** AppPaths.stateDirectory */
	java.io.File appPathsStateDirectory() { return new java.io.File("/HyperZebra/state"); }
	/** AppPaths.distributionDirectory */
	java.io.File appPathsDistributionDirectory() { return new java.io.File("/HyperZebra"); }
	/** QuickTimeService.systemFolder */
	java.io.File quickTimeServiceSystemFolder() { return null; }
	/** OsaScript.cantUnderstand */
	java.lang.String osaScriptCantUnderstand(java.lang.String a0, OObject a1) { return International.t("Cannot interpret {0}", a0); } // (null: the browser's script error had no text)
	/** TQTMovie.available */
	boolean tQTMovieAvailable() { return false; }
	/** TQTMovie.setCurrentTime */
	void tQTMovieSetCurrentTime(java.lang.String a0, long a1) {}
	/** TQTMovie.rate */
	int tQTMovieRate(java.lang.String a0) { return 0; }
	/** TQTMovie.property */
	java.lang.String tQTMovieProperty(java.lang.String a0, java.lang.String a1) { return null; }
	/** TQTMovie.scriptWindows */
	java.util.ArrayList<OWindow> tQTMovieScriptWindows() { return new java.util.ArrayList<OWindow>(); }
	/** TQTMovie.findScriptWindow */
	OWindow tQTMovieFindScriptWindow(java.lang.String a0) { return null; }
	/** TQTMovie.setVolume */
	void tQTMovieSetVolume(java.lang.String a0, int a1) {}
	/** TQTMovie.dispose */
	void tQTMovieDispose(java.lang.String a0) {}
	/** GMenuBrowse.stackChooserDirectory */
	java.io.File gMenuBrowseStackChooserDirectory(java.io.File a0) { if (a0 == null) return null; java.io.File f = a0.getAbsoluteFile(), d = f.getParentFile(); if (d == null) return null; if (d.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") || f.getName().equalsIgnoreCase("_stack.xml") || f.getName().equalsIgnoreCase("toc.xml")) d = d.getParentFile(); return d; }
	/** GMenuBrowse.createNewStack */
	boolean gMenuBrowseCreateNewStack(java.io.File a0, int a1, int a2, OBackground a3) { return false; }
	/** GMenuBrowse.removeCardDataForBgPart */
	void gMenuBrowseRemoveCardDataForBgPart(OObject a0) {}
	/** GMenuBrowse.selectedPart */
	OObject gMenuBrowseSelectedPart() { return null; }
	/** FinderInfo68k.markNewTextFile */
	void finderInfo68kMarkNewTextFile(java.io.File a0) {}
	/** CardWindow68k.partCopied */
	void cardWindow68kPartCopied(int a0, int a1, int a2, int a3) {}
	/** CardWindow68k.partMoved */
	void cardWindow68kPartMoved(OObject a0, int a1, int a2, int a3, int a4) {}
	/** CardWindow68k.holdScreen */
	void cardWindow68kHoldScreen() {}
	/** CardWindow68k.releaseScreen */
	void cardWindow68kReleaseScreen() {}
	/** PaintTool.setPowerKeys */
	void paintToolSetPowerKeys(boolean a0) {}
	/** LauncherStore.identity */
	java.lang.String launcherStoreIdentity(java.io.File a0) { if (a0 == null) return null; java.io.File f = a0; if (f.getName().equals("_stack.xml") || f.getName().equals("toc.xml")) { java.io.File p = f.getParentFile(); if (p != null) f = p; } return f.getAbsolutePath(); }
	/** Toolbox68k.programClockSeconds */
	long toolbox68kProgramClockSeconds() { return 0; }
	/** StackProtection.passwordBytes */
	byte[] stackProtectionPasswordBytes(java.lang.String a0, StackTextEncoding a1) { String s = a0 == null ? "" : Platform.get().normalize(a0, UnicodeNorm.Form.NFD); StringBuilder b = new StringBuilder(); for (int i = 0; i < s.length(); i++) if (UnicodeNorm.combiningClass(s.charAt(i)) == 0) b.append(s.charAt(i)); s = Platform.get().normalize(b.toString().toLowerCase(java.util.Locale.ROOT), UnicodeNorm.Form.NFC); return (a1 == null ? StackTextEncoding.ROMAN : a1).encodeReplacing(s); }
	/** StackProtection.hash */
	int stackProtectionHash(byte[] a0) { long x = 0; int c0 = a0.length > 0 ? a0[0] & 0xFF : 0; long s = c0 + a0.length; if (s > 0xFF) s &= 0xFF; else if (c0 > 0x80) s |= 0xFFFFFF00L; for (byte b : a0) for (int bit = 7; bit >= 0; bit--) { long h = (s & 0xFFFFFFFFL) * 0x41A7L; h += h >>> 31; s = h & 0x7FFFFFFFL; if (((b >> bit) & 1) != 0) x += s; } if ((int) x == 0) return 0x42696C6C; return (int) x; }
	/** StackProtection.effectiveUserLevel */
	int stackProtectionEffectiveUserLevel() { int level = HZ.userLevel; OStack st = HZ.stack(); if (st != null && st.userLevel >= 1 && st.userLevel <= 5) level = Math.min(level, st.userLevel); return Math.max(1, Math.min(5, level)); }
	/** GStackWindow.isShown */
	boolean gStackWindowIsShown(java.lang.String a0) { return false; }
	/** GStackWindow.setShown */
	void gStackWindowSetShown(java.lang.String a0, boolean a1) {}
	/** GButton.onCurrentCard */
	boolean gButtonOnCurrentCard(OObject a0) { OStack s = HZ.stack(); if (a0 == null || a0.parent == null || s == null || s.curCard == null) return false; return a0.parent == s.curCard || a0.parent instanceof OBackground && ((OBackground) a0.parent).viewCard == s.curCard; }
	/** GUI.noteKeyPoll */
	void gUINoteKeyPoll() {}
	/** StackStrikeFont.font: the stack's own bitmap font when the host has no family of that name (none here: shapes' text in the player is the host's) */
	java.awt.Font stackStrikeFont(String name, int style, double size) { return null; }
	/** StackStrikeFont.forget */
	void stackStrikeFontForget(Rsrc a0) {}
	/** TabletInput.latest */
	float tabletInputLatest() { return 0; }
	/** TabletInput.latestTilt */
	float[] tabletInputLatestTilt() { return null; }
	/** TabletInput.start */
	void tabletInputStart() {}
	/** MacAppHost.screenBounds */
	java.awt.Rectangle macAppHostScreenBounds() { return new java.awt.Rectangle(0, 0, 640, 480); }
	/** MacAppHost.screenBounds */
	java.awt.Rectangle macAppHostScreenBounds(boolean a0) { return new java.awt.Rectangle(0, 0, 640, 480); }
	/** MacAppHost.cursorFromData */
	java.awt.Cursor macAppHostCursorFromData(byte[] a0) { return java.awt.Cursor.getDefaultCursor(); }
	/** MacAppHost.colorCursor */
	java.awt.Cursor macAppHostColorCursor(int[] a0, byte[] a1) { return java.awt.Cursor.getDefaultCursor(); }
	/** GLauncher.showLauncher */
	void gLauncherShowLauncher(boolean a0) {}
	/** XMLwrite.writeOnce */
	void xMLwriteWriteOnce(OStack a0) {}
	/** StackFileIcon.familyOfClassic */
	byte[] stackFileIconFamilyOfClassic(java.util.Map<java.lang.Integer, byte[]> a0) { return null; }
	/** WorkingCopies.copyFile */
	void workingCopiesCopyFile(java.io.File a0, java.io.File a1) throws java.io.IOException { try (java.io.InputStream in = new java.io.FileInputStream(a0); java.io.OutputStream out = new java.io.FileOutputStream(a1)) { in.transferTo(out); } }
	/** TempFiles.createFile */
	java.io.File tempFilesCreateFile(java.lang.String a0, java.lang.String a1) throws java.io.IOException { java.io.File d = new java.io.File("/HyperZebra/tmp"); d.mkdirs(); java.io.File f; int i = 0; do f = new java.io.File(d, a0 + (++i) + (a1 == null ? ".tmp" : a1)); while (f.exists()); f.createNewFile(); return f; }
	/** StackBundle.hasHzbExtension */
	boolean stackBundleHasHzbExtension(java.io.File a0) { return a0 != null && a0.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") && a0.getName().length() > 4; }
	/** AppPaths.bootVolumeDirectory */
	java.io.File appPathsBootVolumeDirectory() { return new java.io.File("/HyperZebra/Boot Volume"); }
	/** AppPaths.moveResourceToTrash */
	java.io.File appPathsMoveResourceToTrash(java.io.File a0) throws java.io.IOException { a0.delete(); return null; }
	/** ExternalMenusUI.hyperCardMenusForExternals */
	java.util.List<ExternalMenu68k> externalMenusUIHyperCardMenusForExternals() { return new java.util.ArrayList<ExternalMenu68k>(); }
	/** ExternalMenusUI.externalChangedHyperCardMenu */
	void externalMenusUIExternalChangedHyperCardMenu(java.lang.Object a0, int a1, java.lang.Boolean a2, java.lang.Boolean a3, java.lang.String a4) {}
	/** ExternalMenusUI.externalChangedHyperCardItem */
	java.lang.Object externalMenusUIExternalChangedHyperCardItem(java.lang.Object a0, java.lang.Object a1, java.lang.Boolean a2, java.lang.String a3, java.lang.String a4, java.lang.Character a5, java.lang.Integer a6) { return null; }
	/** ExternalMenusUI.externalHierarchicalHyperCardItem */
	void externalMenusUIExternalHierarchicalHyperCardItem(java.lang.Object a0, java.lang.Object a1, MenuModel68k a2) {}
	/** ExternalMenusUI.externalRemovedHyperCardMenu */
	void externalMenusUIExternalRemovedHyperCardMenu(java.lang.Object a0) {}
	/** ExternalMenusUI.externalRestoredHyperCardMenu */
	void externalMenusUIExternalRestoredHyperCardMenu(java.lang.Object a0, java.lang.Object a1) {}
	/** ExternalMenusUI.externalArrangedHyperCardMenu */
	java.util.List<java.lang.Object> externalMenusUIExternalArrangedHyperCardMenu(java.lang.Object a0, java.util.List<java.lang.Object> a1, boolean[] a2, boolean[] a3) { return new java.util.ArrayList<Object>(a1); }
	/** ExternalMenusUI.externalArrangedHyperCardMenu */
	java.util.List<java.lang.Object> externalMenusUIExternalArrangedHyperCardMenu(java.lang.Object a0, java.util.List<java.lang.Object> a1, boolean[] a2, boolean[] a3, java.util.Collection<java.lang.Object> a4) { return new java.util.ArrayList<Object>(a1); }
	/** ExternalMenusUI.syncExternalMenus */
	void externalMenusUISyncExternalMenus(java.lang.Object a0, java.util.List<MenuModel68k> a1) {}
	// ---- end of facades ----
	// ---- moved methods (generated) ----
	/** OButton.buildOButton */
	void buildOButton(OButton obtn) {}
	/** OField.buildOField */
	void buildOField(OField ofld) {}
	/** OCard.buildOCard */
	OCard buildOCard(OCard cd) { cd.picture = new OPicture(cd); return cd; }
	/** OBackground.buildOBackground */
	OBackground buildOBackground(OBackground bg) { bg.picture = new OPicture(bg); return bg; }
	/** TTalk.setMenuBarVisible */
	void setMenuBarVisible(boolean visible) {}
	/** TTalk.setTitleBarVisible */
	void setTitleBarVisible(boolean visible) {}
	/** TUtil.getCardOriginOnScreen */
	java.awt.Point getCardOriginOnScreen() { return new java.awt.Point(0, 0); }
	/** TUtil.cardWindowRect */
	java.awt.Rectangle cardWindowRect() { OStack s = HZ.stack(); return new java.awt.Rectangle(0, 0, s == null ? 512 : s.width, s == null ? 342 : s.height); }
	/** TUtil.setCardWindowRect */
	void setCardWindowRect(java.awt.Rectangle r) {}
	/** TUtil.windowEntries */
	java.util.List<Object[]> windowEntries() { return new java.util.ArrayList<Object[]>(); }
	/** TTalk.scrollSelectionIntoView */
	void scrollSelectionIntoView(OField fld, int offset) {}
	/** TTalk.choosePopupItem */
	boolean choosePopupItem(OButton b) { return false; }
	/** TTalk.authToolDrag */
	boolean authToolDrag(TTalk self, int sx, int sy, int ex, int ey, int[] modKeys, MemoryData memData) throws xTalkException { return false; }
	/** TTalk.askWhereIsStack */
	String askWhereIsStack(TTalk self, String ref) { return null; }
	/** OStack.launch68kApplication */
	boolean launch68kApplication(String path) { return false; }
	/** TTalk.openApplication */
	String openApplication(TTalk self, String app, String document) { return "Couldn't open application."; }
	/** TTalk.commandKeyShortcut */
	void commandKeyShortcut(String ch) {}
	/** TUtil.CallSystemFunction3: branches */
	boolean toolFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (0 == message.compareTo("tool")) { String t = HeadlessProps.get("tool"); result.theResult = t == null ? "browse tool" : t; return true; } return false; }
	/** TUtil.CallSystemFunction3: branches */
	boolean selectionFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (message.equals("selectedline") || message.equals("selectedfield") || message.equals("selectedloc")) { result.theResult = ""; return true; } return false; }
	/** TUtil.CallSystemFunction3: branches */
	boolean programsFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (0 == message.compareTo("programs")) { result.theResult = HZ.AppName; return true; } return false; }
	/** TUtil.CallSystemFunction3: branches */
	boolean settingsFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; result.ret = 0; return true; }
	/** TUtil.CallSystemFunction3: branches */
	boolean paintFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; result.ret = 0; return true; }
	/** TUtil.setHyperCardProperty: branches */
	boolean textSettingsSetters(String inProperty, String value, String prpty) throws xTalkException { if (!java.util.Arrays.asList("scripttextfont", "scripttextsize", "textfont", "textstyle", "textsize").contains(prpty)) return false; HeadlessProps.set(prpty, value); return true; }
	/** TUtil.setHyperCardProperty: branches */
	boolean cursorSetters(String inProperty, String value, String prpty) throws xTalkException { if (!java.util.Arrays.asList("powerkeys", "cursor").contains(prpty)) return false; HeadlessProps.set(prpty, value); return true; }
	/** TUtil.setHyperCardProperty: branches */
	boolean paintSetters(String inProperty, String value, String prpty) throws xTalkException { if (!java.util.Arrays.asList("editbkgnd", "filled", "centered", "multiple", "multispace", "polysides", "gradient", "gradientangle", "filltexture", "filltolerance", "brushsize", "brushhardness", "smudgestrength", "stampsource", "brushopacity", "brushspacing", "brushpressure", "penpressure", "brushstabilization", "brushstabilizer", "screenfilter", "screenoverlay", "zoom", "zoomorigin", "grid", "gridsize", "paintlayer", "layername", "layeropacity", "layerblend", "layervisible", "layeralphalock", "layerclip", "symmetry", "symmetryaxes", "symmetrycenter", "linesize", "pattern", "brushpattern", "penpattern", "fillpattern", "brushcolor", "pencolor", "brushbackcolor", "penbackcolor", "messagewatcher", "variablewatcher", "brush").contains(prpty)) return false; HeadlessProps.set(prpty, value); return true; }
	/** TUtil.SetProperty2: branches */
	boolean windowSetLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException { return java.util.Arrays.asList("window", "tool window", "msg", "pattern window").contains(obj.objectType); }
	/** TUtil.SetProperty2: branches */
	boolean windowSetTop(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException { return java.util.Arrays.asList("window", "tool window", "msg", "pattern window").contains(obj.objectType); }
	/** TUtil.SetProperty2: branches */
	boolean windowSetTopLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException { return java.util.Arrays.asList("window", "tool window", "pattern window", "msg").contains(obj.objectType); }
	/** TUtil.SetProperty2: branches */
	boolean windowSetLoc(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException { return java.util.Arrays.asList("window", "msg", "tool window", "pattern window").contains(obj.objectType); }
	/** TUtil.SetProperty2: branches */
	boolean windowSetRect(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2, int v3, int v4) throws xTalkException { return java.util.Arrays.asList("window").contains(obj.objectType); }
	/** TUtil.SetProperty2: branches */
	boolean windowSetZoomed(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException { return java.util.Arrays.asList("window").contains(obj.objectType); }
	/** TTalk.CommandExec8: branches */
	boolean clickAndDrag(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return HeadlessPointer.clickAndDrag(self, stringList, typeAry, object, target, memData, result, str); }
	/** TTalk.CommandExec4: block */
	Result answerProgram(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException { TTalk.setVariable(memData, "it", ""); result.theResult = "Cancel"; return result; }
	/** TTalk.CommandExec4: block */
	Result answerFolder(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException { TTalk.setVariable(memData, "it", ""); result.theResult = "Cancel"; return result; }
	/** TTalk.CommandExec4: branches */
	boolean answerFile(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException { if (!stringList.get(next).equals("file")) return false; TTalk.setVariable(memData, "it", ""); result.theResult = "Cancel"; return true; }
	/** TTalk.CommandExec3: lines */
	void endPaintBeforeHome(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	/** go home with no Home stack at all closes the current stack (the desktop: its launcher shows); the web player stays. */
	boolean closesStackWithoutHome() { return false; }
	void emptyCardWindow(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void endPaintBeforeGo(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void freezeForCardChange(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void dropLeftCardPicture(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OCard cd) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void dropSelectedRange(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void cardRedrawn(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void playVisualEffects(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec3: lines */
	void restartToolAfterGo(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec6: branches */
	boolean editorsAndPictures(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return java.util.Arrays.asList("edit", "export", "feather", "grow", "shrink", "import").contains(str); }
	/** TTalk.CommandExec13: lines */
	void virusScanOpened(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OStack ostack) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void writeStackOnce(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OStack ostack) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void stopSound(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void stopSpeech(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void traceMode(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void aboutDialog(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void beepOnce(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec13: lines */
	void flashScreen(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int cnt) throws xTalkException {}
	/** TUtil.CallSystemFunction2: branches */
	/** the voices, the speech (only these: the input and screen functions come later in TUtil, where a host answers them) */
	boolean speechFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (!message.equals("voices") && !message.equals("speech")) return false; String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; return true; }
	/** TUtil.CallSystemFunction2: branches */
	/** the mouse, the clicks and the modifier keys (only these, as PointerUI.inputFunctions: the sound comes later in TUtil) */
	static final java.util.Set<String> INPUT_FUNCTIONS = java.util.Set.of("mouse", "mouseclick", "mouseh", "mousev", "mouseloc", "clickh", "clickv",
		"clickloc", "clickline", "clickchunk", "clicktext", "commandkey", "cmdkey", "optionkey", "shiftkey", "controlkey");
	boolean inputFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (!INPUT_FUNCTIONS.contains(message)) return false; String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; return true; }
	/** TUtil.CallSystemFunction2: branches */
	/** the clipboard, fontExists, the screenRect (only these, as WindowUI.screenFunctions) */
	boolean screenFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (!message.equals("clipboard") && !message.equals("fontexists") && !message.equals("screenrect")) return false; String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; return true; }
	/** TUtil.CallSystemFunction2: branches */
	boolean soundFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; return true; }
	/** TUtil.CallSystemFunction2: branches */
	/** the stacks: the card window's, then the ones left open in windows of their own (go … in a new window; the core keeps
	 *  them, TTalk.windowStacks) front to back, each line with its return (WindowUI.stacksFunctions on the desktop). The player
	 *  listed only the card window's: Window Tools' "Open stacks" stayed empty */
	static String openStackPaths() {
		StringBuilder b = new StringBuilder();
		if (HZ.stack() != null && HZ.stack().path != null) b.append(HZ.stack().stackPathName()).append('\n');
		for (String other : TTalk.windowStackPaths()) b.append(Platform.get().normalize(TTalk.macStackPath(other), UnicodeNorm.Form.NFC)).append('\n');
		return b.toString();
	}
	boolean stacksFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if (0 == message.compareTo("stacks")) { result.theResult = openStackPaths(); return true; } if (0 == message.compareTo("suspended")) { result.theResult = "false"; return true; } return false; }
	/** TUtil.setPropertyNow: guard */
	boolean cardWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return false; }
	/** TUtil.setPropertyNow: guard */
	boolean msgFontProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return obj instanceof OMsg && (prpty.equals("textsize") || prpty.equals("textfont") || prpty.equals("textstyle")); }
	/** TUtil.setPropertyNow: guard */
	boolean msgPlaceProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return false; }
	/** TUtil.setPropertyNow: guard */
	boolean pictureWindowGlobalProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return false; }
	/** TUtil.setPropertyNow: guard */
	boolean paletteWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return false; }
	/** TUtil.setPropertyNow: guard */
	boolean movieWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return false; }
	/** TUtil.setPropertyNow: branches */
	boolean windowsSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return java.util.Arrays.asList("msg", "tool window", "pattern window").contains(obj.objectType); }
	/** TUtil.setPropertyNow: branches */
	boolean windowSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return 0 == obj.objectType.compareTo("window"); }
	/** OCard.reloadCurrentCard: lines */
	void reattachCardViews() {}
	/** TTalk.CommandExec10: branches */
	boolean appleEventCommands(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { if (str != "request" && str != "reply") return false; result.theResult = ""; return true; }
	/** TTalk.CommandExec10: branches */
	boolean saveCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return str == "save"; }
	/** TTalk.CommandExec10: branches */
	boolean chooseCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { if (str != "choose") return false; StringBuilder b = new StringBuilder(); for (int i = 1; i < stringList.size(); i++) { if (b.length() > 0) b.append(' '); b.append(stringList.get(i)); } HeadlessProps.set("tool", b.toString().toLowerCase(java.util.Locale.ROOT)); return true; }
	/** TTalk.printCommand */
	String printCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, MemoryData memData, OObject object, OObject target) throws xTalkException { return ""; }
	/** TUtil.getPictureWindowProperty */
	String getPictureWindowProperty(GPictWindow gpw, String prpty) { return null; }
	/** TTalk.resetIdleCursor: lines */
	void browseCursorNow() {}
	/** TUtil.CallSystemFunction4: branches */
	boolean paintLayerFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; result.ret = 0; return true; }
	/** TUtil.CallSystemFunction4: branches */
	boolean watcherFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; result.ret = 0; return true; }
	/** TUtil.CallSystemFunction4: branches */
	boolean paintTextFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { String v = HeadlessProps.get(message); if (v == null) return false; result.theResult = v; result.ret = 0; return true; }
	/** TTalk.runAppleScript */
	String runAppleScript(String code) { return "AppleScript is not available"; }
	/** OStack.revealInFileManager */
	boolean revealInFileManager(java.io.File item) { return false; }
	/** TUtil.partChangedOnScreen */
	void partChangedOnScreen(OObject part, java.awt.Rectangle before) {}
	/** TUtil.showOrHidePart: a part of the card is shown or hidden (the player charges its redraw to a speed limit, StackSpeed) */
	void partShownOrHidden(OObject part) {}
	/** TTalk.editSelectedText */
	boolean editSelectedText(String cmd) { return false; }
	/** the open field has text selected (the Edit menu's Cut / Copy / Delete are on) */
	boolean hasSelectedText() { return false; }
	/** Paste has text to put in the open field */
	boolean canPasteText() { return false; }
	/** Go > Find…: the message box with find "" (GMsg.showFind) */
	void showFind() {}
	/** OField.showFoundBox */
	void showFoundBox(OField self, int start, int end) {}
	/** OField.clearFoundBox */
	void clearFoundBox() {}
	/** OButton.setBgColor: lines */
	void setBgColorView849(OButton self, java.awt.Color col) {}
	/** OButton.setColor: lines */
	void setColorView842(OButton self, java.awt.Color col) {}
	/** OButton.setName: lines */
	void setNameView176(OButton self, String in) {}
	/** OButton.setName: lines */
	void setNameView173(OButton self, String in) {}
	/** OButton.setName: lines */
	void setNameView170(OButton self, String in) {}
	/** OButton.setName: lines */
	void setNameView167(OButton self, String in) {}
	/** OField.setBgColor: lines */
	void setBgColorView896(OField self, java.awt.Color col) {}
	/** OField.setColor: lines */
	void setColorView887(OField self, java.awt.Color col) {}
	/** OField.setScroll: lines */
	void setScrollView455(OField self, int in) {}
	/** OCardBase.removeData: lines */
	void removeDataView605(OCardBase self) {}
	/** OButton.setAutoHilite: lines */
	void setAutoHiliteView760(OButton self, boolean in) {}
	/** OButton.setHilite: lines */
	void setHiliteView592(OButton self, boolean in) {}
	/** OButton.setEnabled: lines */
	void setEnabledView562(OButton self, boolean in) {}
	/** TTalk.returnToIdle: lines */
	void gcAtIdle() {}
	/** TTalk.returnToIdle: lines */
	void unlockScreenAtIdle() { HZ.lockedScreen = false; }
	/** TTalk.returnToIdle: lines */
	void pointerAtIdle() {}
	/** OStack.openStackFileInThread */
	boolean openStackFileInThread(OStack self, String inpath, boolean isUsingOnly) { return HeadlessStacks.open(self, inpath, isUsingOnly); }
	/** OStack.buildStackFile: lines */
	void openStackWindow(OStack self, boolean isUsingOnly) { HeadlessStacks.opened(self); }
	/** OStack.makeRecentThumbnail */
	java.awt.image.BufferedImage makeRecentThumbnail(OCard card) { return null; }
	/** OStack.setNewBounds */
	void setNewBounds(OStack self) {}
	/** OStack.OStack: lines */
	void stackViews(OStack self, PCARDFrame pc) {}
	/** OStack.clean: lines */
	void externalsAtStackClose(OStack self) {}
	/** OStack.clean: lines */
	void editorsAtStackClose(OStack self) {}
	/** OStack.setRect: lines */
	void cardWindowToCardSize(OStack self, int v1, int v2, int v3, int v4) {}
	/** OStack.setTopLeft: lines */
	void cardWindowToTopLeft(OStack self, int h, int v) {}
	/** OStack.setScroll: lines */
	void cardWindowScrolled(OStack self, int x, int y) {}
	/** OStack.setName: lines */
	void cardWindowTitled(OStack self, String in) {}
	/** OStack.setResizeable: lines */
	void cardWindowResizable(OStack self, Boolean in) {}
	/** TTalk.goToStack2: lines */
	void endPaintBeforeStack(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target) throws xTalkException {}
	/** TTalk.goToStack2: lines */
	void leaveStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String leftKey, OStack saveStack) throws xTalkException {}
	/** TTalk.goToStack2: lines */
	void virusScanStack(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, boolean res) throws xTalkException {}
	/** TTalk.goToStack2: lines */
	void discardStackWindows(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String leftKey, TTalk.WindowStack resumed, String targetKey) throws xTalkException {}
	/** TTalk.goToStack2: lines */
	void restoreStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String targetKey, TTalk.WindowStack resumed) throws xTalkException {}
	/** TTalk.goToStack2: lines */
	void placeStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String targetKey, TTalk.WindowStack resumed, String leftKey) throws xTalkException {}
	/** OButton.setIcon: lines */
	void iconOnView(OButton self, int in, boolean noupdate) {}
	/** OButton.setIconURI: lines */
	void iconURIOnView(OButton self, java.net.URI in, boolean noupdate) {}
	/** OButton.systemIcon */
	java.awt.image.BufferedImage systemIcon(int id) { return null; }
	/** TUtil.getPatternProperty */
	String getPatternProperty(OPattern op, String prpty) throws xTalkException { throw new xTalkException("No such pattern"); }
	/** TUtil.setPatternProperty */
	void setPatternProperty(OPattern op, String prpty, String value) throws xTalkException { throw new xTalkException("No such pattern"); }
	/** TalkBinary.imageToArray */
	String imageToArray(String data) throws xTalkException { throw new xTalkException("Images are not available here: {0}", "imageToArray"); }
	/** TalkBinary.arrayToImage */
	String arrayToImage(String array, String format) throws xTalkException { throw new xTalkException("Images are not available here: {0}", "arrayToImage"); }
	/** TUtil.loadPictureFile */
	java.awt.image.BufferedImage loadPictureFile(String path) { return null; }
	/** Rsrc.loadImage */
	java.awt.image.BufferedImage loadImage(String path, String type) { return null; }
	/** OShape.hits */
	boolean hits(OShape self, double x,double y) {
		if(self.rotation%360!=0){
			java.awt.geom.Point2D p=new java.awt.geom.Point2D.Double(x,y);
			try{self.rotationTransform().inverseTransform(p,p);}catch(java.awt.geom.NoninvertibleTransformException e){return false;}
			x=p.getX();y=p.getY();
		}
		if(self.kind.equals("text"))return x>=0&&y>=0&&x<self.width&&y<self.height;
		java.awt.Shape shape=self.geometry();
		boolean filled=self.fillColor!=null||self.texturePng!=null||self.gradientStart!=null&&self.gradientEnd!=null;
		if(filled&&shape.contains(x,y))return true;
		float line=self.strokeColor!=null&&self.strokeWidth>0?self.strokeWidth:0;
		for(OShape.Outline o:self.outlines)if(o.color!=null&&o.width>0)line=Math.max(line,o.width*2);
		return line>0&&new java.awt.BasicStroke(Math.max(6,line),java.awt.BasicStroke.CAP_ROUND,java.awt.BasicStroke.JOIN_ROUND).createStrokedShape(shape).contains(x,y);
	}
	/** OShape.convertTextToBezier */
	void convertTextToBezier(OShape self) {}
	/** OShape.fitText */
	void fitText(OShape self) { if(!self.kind.equals("text"))return; java.awt.Dimension d=shapeTextSize(self); self.setSize(d.width,d.height); }
	/** OShape.build */
	void build(OShape self) {}
	/** OShape.num */
	String num(double v) { if (Math.abs(v - HzMath.rint(v)) < 1e-9) return Long.toString(Math.round(v)); return String.format(java.util.Locale.ROOT, "%.4f", v).replaceAll("0+$", ""); }
	/** OShape.setSize: lines */
	void scalePath(OShape self, int w,int h) {}
	/** OShape.setKind: lines */
	void outlineAsPath(OShape self, String value, String k) throws xTalkException {}
	/** OButton.setShowName: lines */
	void setShowNameView529(OButton self, boolean in) {}
	/** OButton.setVisible: lines */
	void setVisibleView371(OButton self, boolean in) {}
	/** OButton.setText: lines */
	void setTextView356(OButton self, String in) {}
	/** OField.setText: lines */
	void setTextView284(OField self, String in) {}
	/** OButton.setTextFont: lines */
	void setTextFontView569(OButton self, String in, javax.swing.JComponent component) {}
	/** OButton.setHilite: lines */
	void setHiliteView402(OButton self, boolean in, OButton sibling) {}
	/** OButton.applyVisible: lines */
	void applyVisibleView375(OButton self, javax.swing.JComponent component) {}
	/** OButton.setRect: lines */
	void setRectView328(OButton self, int v1, int v2, int v3, int v4, int oldLeft, int oldTop, int oldWidth, int oldHeight) {}
	/** OButton.setTopLeft: lines */
	void setTopLeftView286(OButton self, int h, int v, int oldLeft, int oldTop) {}
	/** OField.applyVisible: lines */
	void applyVisibleView376(OField self) {}
	/** OField.applyVisible: lines */
	void applyVisibleView375(OField self) {}
	/** OField.setRect: lines */
	void setRectView213(OField self, int v1, int v2, int v3, int v4, int oldLeft, int oldTop, int oldWidth, int oldHeight) {}
	/** OField.setTopLeft: lines */
	void setTopLeftView181(OField self, int h, int v, int oldLeft, int oldTop) {}
	/** TTalk.sound */
	TSound sound() { return null; }
	/** TTalk.stopAllSounds */
	void stopAllSounds() {}
	/** TTalk.showScriptErrorDialog */
	int showScriptErrorDialog(String errorMessage, String statement, int line, OObject object, boolean hasScript, boolean canDebug) { return TTalk.ERROR_CANCEL; }
	/** TTalk.runOnEventThread */
	void runOnEventThread(Runnable r) { r.run(); }
	/** TTalk.expandShortHfsNames */
	java.io.File expandShortHfsNames(java.io.File path) { return null; }
	/** TTalk.printText */
	String printText(TTalk self, String text, java.awt.Font font) throws xTalkException { return ""; }
	/** TUtil.windowOrigin */
	java.awt.Point windowOrigin() { return new java.awt.Point(0, 0); }
	/** TUtil.setWatcherWindowProperty */
	boolean setWatcherWindowProperty(OWindow w, String prpty, String value) throws xTalkException { return false; }
	/** TUtil.messageBoxRect */
	java.awt.Rectangle messageBoxRect() { return new java.awt.Rectangle(0, 0, 472, 35); }
	/** TUtil.openPaletteWindow */
	void openPaletteWindow(Rsrc.PlteClass plte, java.awt.image.BufferedImage image, String[] params) {}
	/** TUtil.normalizePaletteImage */
	java.awt.image.BufferedImage normalizePaletteImage(java.awt.image.BufferedImage source, java.awt.Point pictOrigin) { return source; }
	/** OCardBase.restackComponents */
	boolean restackComponents(OCardBase self) { return true; }
	/** OObject.findPaletteWindow */
	OWindow findPaletteWindow(String name) { return null; }
	/** OObject.isPicture */
	boolean isPicture(OWindow self) { return false; }
	/** OObject.Command */
	void Command(OWindow self, String cmdStr) throws xTalkException {}
	/** OObject.setZoomed */
	void setZoomed(OWindow self, boolean in) throws xTalkException { throw new xTalkException("Cannot set zoomed on this window"); }
	/** OObject.Close */
	void Close(OWindow self) {}
	/** OObject.image */
	java.awt.image.BufferedImage image(OPattern self) { return null; }
	/** OObject.setImage */
	void setImage(OPattern self, java.awt.image.BufferedImage image) {}
	/** ScriptDebugger.pause */
	void pause(OObject object, int line, MemoryData memData, boolean error, String errorMessage) throws xTalkException {}
	/** ScriptDebugger.scriptEditorVisible */
	boolean scriptEditorVisible() { return true; }
	/** ScriptDebugger.traceSleep */
	void traceSleep() {}
	/** TTalk.handleScriptError: lines */
	void openEditorAtError(TTalk self, xTalkException e, OObject object, java.util.ArrayList<String> scriptList, int line, MemoryData memData, int mode) throws xTalkException {}
	/** TTalk.keyboardCommand: lines */
	void moveCaretInView(TTalk self, String message, String param, MemoryData memData, OField caretField) throws xTalkException { FieldView v = caretField.view(); int len = v.getText().length(), pos = v.getSelectionEnd(); if (param.equals("left")) pos = Math.max(0, pos - 1); else if (param.equals("right")) pos = Math.min(len, pos + 1); v.setSelectionStart(pos); v.setSelectionEnd(pos); }
	/** TTalk.CommandExec11: lines */
	void openPanelInfo(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OPanel panel) throws xTalkException {}
	/** TTalk.CommandExec11: lines */
	void newButtonView(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OButton obtn) throws xTalkException {}
	/** TTalk.CommandExec11: lines */
	void selectNewButton(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OButton obtn) throws xTalkException {}
	/** TTalk.CommandExec11: lines */
	void newFieldView(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OField ofld) throws xTalkException {}
	/** TTalk.CommandExec11: lines */
	void selectNewField(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OField ofld) throws xTalkException {}
	/** TTalk.CommandExec11: lines */
	void openPrinting(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TTalk.CommandExec11: lines */
	void closePrinting(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {}
	/** TUtil.setPropertyNow: lines */
	void popupBoundsView(OObject obj, String inProperty, String value, OButton btn) throws xTalkException {}
	/** TUtil.setPropertyNow: lines */
	void buttonFontSizeView(OObject obj, String inProperty, String value, OButton btn, int size) throws xTalkException {}
	/** TUtil.setPropertyNow: lines */
	void buttonFontStyleView(OObject obj, String inProperty, String value, OButton btn, int style) throws xTalkException {}
	/** TUtil.SetProperty2: lines */
	void centrePictureWindow(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException {}
	/** TUtil.SetProperty2: lines */
	void saveLockNow(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException {}
	/** OCardBase.removeData: lines */
	void clearCardPane(OCardBase self) {}
	/** OCardBase.removeData: lines */
	void dropButtonViews(OCardBase self) {}
	/** OCardBase.removeData: lines */
	void dropFieldViews(OCardBase self) {}
	/** TUtil.CallSystemMessage: block */
	Result pictureCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { result.ret = 0; return result; }
	/** TUtil.CallSystemMessage: block */
	Result paletteCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { if(params.length<1 || params[0].length()==0) throw new xTalkException("palette requires a palette name"); result.ret = 0; return result; }
	/** TUtil.getProperty: block */
	String watcherBarLoc(String inProperty, OObject obj, OObject target, MemoryData memData, String prpty) throws xTalkException { return null; }
	/** TTalk.ReceiveMessage: lines */
	void showInMessageWatcher(TTalk self, String message, String[] params, OObject object, OObject target, MemoryData memData, Result result, boolean isFunc, boolean isDynamic, boolean isBgroot, int usingCnt) throws xTalkException {}
	/** TUtil.partRedrawnByExternal */
	boolean partRedrawnByExternal(OObject part) { return false; }
	/** OField.rebuildComponent */
	void rebuildComponent(OField self) {}
	/** OField.showFont */
	void showFont(OField self, java.awt.Font font) {}
	/** OField.showLineWrap */
	void showLineWrap(OField self, boolean wrap) {}
	/** OField.applyMargin */
	void applyMargin(OField self) {}
	/** OField.updateTextEditable */
	void updateTextEditable(OField self) {}
	/** OObject.availableFamily */
	String availableFamily(String... candidates) { return null; }
	/** OObject.containedFamily */
	String containedFamily(String name) { return null; }
	/** TTalk.deletePartCommand: lines */
	void removeFromCardPane(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, MemoryData memData, OObject object, OObject target, javax.swing.JComponent comp) throws xTalkException {}
	/** OField.table */
	FieldTable table(OField self) { return null; }
	/** OField.outline */
	FieldOutline outline(OField self) { return null; }
	/** OField.refreshTable */
	void refreshTable(OField self) {}
	/** OShape.applyShown */
	void applyShown(OShape self) {}
	/** OShape.moveView */
	void moveView(OShape self) {}
	// ---- what a stack's 68K external asks of the user interface (P4: HyperCard68kCallbacks) ----
	/** ShowHCAlert: the alert with these buttons; the button chosen (0-based), -1 when it was closed */
	int showHCAlert(String prompt, String[] buttons) {
		String r = answer(HZ.stack(), prompt, buttons[0], buttons.length > 1 ? buttons[1] : null, buttons.length > 2 ? buttons[2] : null);
		for (int i = 0; i < buttons.length; i++) if (buttons[i].equals(r)) return i;
		return -1;
	}
	/** GetFilePath with askUser: the user picks the file (named name, starting in folder), null when cancelled or impossible */
	java.io.File chooseExternalFile(String name, java.io.File folder) { return null; }
	/** Notify: the user's attention while HyperCard is in the background */
	void notifyUser() {}
	/** The folder a stack file is in (beside a packed .hzb, around a folder stack's _stack.xml) */
	java.io.File stackFolderOf(java.io.File stackFile) {
		if (stackFile == null) return null;
		java.io.File f = stackFile.getAbsoluteFile(), dir = f.getParentFile();
		if (dir == null) return null;
		if (dir.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") || f.getName().equalsIgnoreCase("_stack.xml") || f.getName().equalsIgnoreCase("toc.xml")) dir = dir.getParentFile();
		return dir;
	}
	/** ChangedMaskAndData: the card's (or background's) picture an external changed, written with the stack and shown */
	void externalChangedPicture(OStack stack, OCardBase owner, java.awt.image.BufferedImage picture) {}
	/** The card window's presses so far and whether the button is down now (GUI.mousePresses, GUI.mouseDown): an external's clicks */
	int cardMousePresses() { return 0; }
	/** An external polled the input (Button, GetMouse, an event call): the host takes what came and shows the screen when due. */
	void externalPoll() {}
	/** A colour chosen in the host's own dialog for a 68K external's Color Picker (VmHost.pickColor): 0xRRGGBB, -1 when cancelled */
	int pickColor(String prompt, int rgb) { return -1; }
	/** A 68K external's pop-up menu (PopUpMenuSelect) in the host's own list: the item chosen (1-based), 0 for none. The text
	 *  is "left,top,right,bottom" (card pixels: the list goes under it), the item to show at top, "font\tsize\tstyle", then a
	 *  line an item: "-" a separator, else E (enabled) or D, "c" when checked, a tab, the item's text */
	int popUpMenu68k(String text) { return 0; }
	boolean cardMouseDown() { return false; }
	/** AbortScript: the script stops when the external returns (TXcmd.abortScriptAfterExternal) */
	void abortScriptAfterExternal() {}
	/** The target of the message whose handler called the external (TXcmd.callTarget), or null */
	OObject externalCallTarget() { return null; }
	/** Whether the card window is open (PCARD.pc): the stacks open in it count for a 68K VM's resource files */
	boolean cardWindowOpen() { return true; }
	/** The pen tablet (TabletInput): there, the pen near it, the eraser end */
	boolean tabletAvailable() { return false; }
	boolean tabletPenNear() { return false; }
	boolean tabletPenNearEraser() { return false; }
	/** A format 1 'snd ' of an audio file (its AIFF base note and loop, else HyperCard's for its sounds by name): empty when it cannot */
	byte[] soundResourceBits(java.io.File audio, String name) { return new byte[0]; }
	/** A card's picture with its paint layers over it, at least w x h (PaintLayers.flatten): the picture when it has none */
	java.awt.image.BufferedImage withPaintLayers(java.awt.image.BufferedImage pict, java.util.List<?> layers, int w, int h) { return pict; }
}

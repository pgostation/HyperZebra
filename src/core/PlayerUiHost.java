/**
 * The player's UiHost (P2): the headless core's, with the live input and the page's dialogs.
 * <ul>
 * <li>the mouse, the mouseLoc/H/V and the modifier keys from the host now; the mouseClick true once after a click (read
 *     clears it); the clickLoc/H/V of the last press</li>
 * <li>answer and ask: the page's dialog, the handler waiting for the reply</li>
 * <li>beep and the message box: to the page</li>
 * </ul>
 */
class PlayerUiHost extends UiHost {
	/** the player's paint tool: there is a menuItem "Clear Picture" in menu "Edit" (DoodlePaint's Square and Circle buttons
	 *  rubber-band a shape by drag, Select, Clear Picture while the mouse is down; without it each drag left its shape
	 *  selected and the last drag only moved the selection) */
	@Override boolean paintMenusShown() { return HzPaint.active(); }
	@Override boolean paintSelectionUp() { return HzPaint.active() && HzPaint.floating != null; }
	final PlayerLoop loop;
	/** A click happened since the mouseClick was last read. */
	static volatile boolean mouseClicked;

	PlayerUiHost(PlayerLoop loop) { this.loop = loop; }

	/** A handler starts from idle: the clicks made before it are not its mouseClick (GUI.forgetClicks). The press on a pop-up
	 *  button stayed the mouseClick: Earth 〜魔法の水晶〜's item menu ran "otalk …" (repeat … if the mouseClick then exit
	 *  repeat) and its first line went by itself after one blink. */
	@Override void gUIForgetClicks() { mouseClicked = false; }

	/** the selectedLine and selectedField of a field's selection (FieldsUI.selectionFunctions on the desktop): the line it starts
	 *  on, the field, also for an insertion point that select put there; the selectedLoc stays empty here */
	@Override boolean selectionFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if (message.equals("selectedloc")) {
			// the left and the bottom of the selection's start (card coordinates; FieldsUI's on the desktop)
			result.theResult = "";
			OField field = TUtil.selectionField();
			OStack st = HZ.stack();
			if (field == null || st == null) return true;
			int start = field.view().getSelectionStart(), end = field.view().getSelectionEnd();
			if (end <= start && field != HZ.clickField) return true;
			int[] at = CardRenderer.charPlace(loop.gfx(), field, st, start);
			result.theResult = (field.left + at[0]) + "," + (field.top + at[1]);
			return true;
		}
		if (!message.equals("selectedline") && !message.equals("selectedfield")) return super.selectionFunctions(message, params, target, memData, isFunc, result);
		result.theResult = "";
		OField field = TUtil.selectionField();
		if (field == null) return true;
		int start = field.view().getSelectionStart(), end = field.view().getSelectionEnd();
		if (end <= start && field != HZ.clickField) return true;
		if (message.equals("selectedfield")) { result.theResult = TUtil.fieldNumberRef(field); return true; }
		String text = field.getText() == null ? "" : field.getText();
		int line = 1;
		for (int i = 0; i < text.length() && i < start; i++) if (text.charAt(i) == '\n') line++;
		result.theResult = "line " + line + " of " + TUtil.fieldNumberRef(field);
		return true;
	}

	/** click at into an unlocked field: the insertion point where a click there puts it (PlayerLoop's press) */
	@Override void fieldInsertionPointAt(OField f, int x, int y) {
		OStack st = HZ.stack();
		if (st == null) { super.fieldInsertionPointAt(f, x, y); return; }
		int at = CardRenderer.hitText(loop.gfx(), f, st, x - f.left, y - f.top);
		f.view().setSelectionStart(at); f.view().setSelectionEnd(at);
		loop.markDirty();
	}

	@Override boolean inputFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		PlayerHost h = loop.host;
		String v;
		switch (message) {
			case "mouse": loop.tick(); v = h.mouseIsDown() ? "down" : "up"; break;
			case "mouseloc": v = h.mouseX() + "," + h.mouseY(); break;
			case "mouseh": v = Integer.toString(h.mouseX()); break;
			case "mousev": v = Integer.toString(h.mouseY()); break;
			case "mouseclick": {
				loop.tick();
				// a click queued for a 68K external's window counts too, and that window does not get it (PointerUI: Myst Preview's
				// PlayLogo loop ends on the mouseClick while the movie window is clicked)
				Externals68k x = Externals68k.Runner.current;
				v = Boolean.toString(mouseClicked || x != null && x.takeWindowClick()); mouseClicked = false; break;
			}
			case "shiftkey": v = (h.modifiers() & PlayerHost.SHIFT) != 0 ? "down" : "up"; break;
			case "optionkey": v = (h.modifiers() & PlayerHost.OPTION) != 0 ? "down" : "up"; break;
			case "commandkey": case "cmdkey": v = (h.modifiers() & PlayerHost.COMMAND) != 0 ? "down" : "up"; break;
			case "controlkey": v = (h.modifiers() & PlayerHost.CONTROL) != 0 ? "down" : "up"; break;
			// on another card than the click's the clickChunk is empty, the clickText stays (HyperCard J-2.3; GUI.clickOnOtherCard)
			case "clickchunk": { OStack st = HZ.stack(); v = st != null && st.curCard != PlayerLoop.clickCard ? "" : HeadlessProps.get("clickchunk"); break; }
			default: return super.inputFunctions(message, params, target, memData, isFunc, result);
		}
		result.theResult = v;
		return true;
	}

	@Override String answer(OStack stack, String text, String b1, String b2, String b3) {
		java.util.List<String> buttons = new java.util.ArrayList<>();
		for (String b : new String[]{b1, b2, b3}) if (b != null && !b.isEmpty()) buttons.add(b);
		if (buttons.isEmpty()) buttons.add("OK");
		loop.draw();
		// in HyperZebra's language when the stack has its own translations (StackShown); a button still answers with its own name
		String[] names = buttons.toArray(new String[0]);
		int n = loop.host.dialog("answer", StackShown.prompt(stack, text == null ? "" : text), StackShown.buttons(stack, names), null, new String[1]);
		// a click made before the dialog is not the mouseClick after it (the desktop's GDialog; HyperCard's dialog takes the
		// queued click): one made in Favorite Story's dissolve went through answer and 7 UxAnswers and ended a later
		// wait until the mouseClick at once
		mouseClicked = false;
		return buttons.get(Math.max(1, Math.min(n, buttons.size())) - 1);
	}

	/** The Color Picker (GetColor) of a 68K external: the page's colour dialog, its text the colour as #rrggbb both ways */
	@Override int pickColor(String prompt, int rgb) {
		loop.draw();
		String hex = Integer.toHexString(0x1000000 | (rgb & 0xFFFFFF)).substring(1);
		String[] reply = new String[1];
		int n = loop.host.dialog("color", prompt == null ? "" : prompt, new String[]{International.t("Cancel"), "OK"}, "#" + hex, reply);
		mouseClicked = false; // as answer: a click made before the dialog is not the mouseClick after it
		String r = reply[0] == null ? "" : reply[0].trim();
		if (n != 2 || r.length() != 7 || r.charAt(0) != '#') return -1;
		try { return Integer.parseInt(r.substring(1), 16); } catch (NumberFormatException e) { return -1; }
	}

	/** A 68K external's pop-up menu: the page's list for pop-up buttons, waited for as a dialog ("popupMenu") */
	@Override int popUpMenu68k(String text) {
		loop.draw();
		int n = loop.host.dialog("popupMenu", "", new String[0], text, new String[1]);
		mouseClicked = false; // the press that opened it is no mouseClick after it (the Menu Manager took it)
		return Math.max(0, n);
	}

	/** Cut / Copy / Paste / Delete on the open field's selection (the desktop's text component's), with a clipboard of the
	 *  player's own: what was cut or copied in the stack, not the browser's */
	private static String textClip;
	/** select text of msg (or a chunk of it) gave the message box the selection: Cut, Copy and Paste act on its text until a
	 *  field is selected or clicked again (HZ.clickField; the desktop: GMsg.area has the focus). HyperPiece 2 card 75's
	 *  ショートカット cuts a field's text, selects the message box's and pastes it there: it went back into the field. */
	private volatile boolean msgFocused;
	@Override void msgFocus() { msgFocused = true; }
	/** set scroll of a scrolling field: no further than its text goes, as HyperCard (家計簿's Enter button scrolls its columns
	 *  "to 32000": HyperCard J-2.3 then gives the scroll 527, the last lines at the bottom; here the columns went blank) */
	@Override void setScrollView455(OField self, int in) {
		if (self.style != 5) return;
		PlayerLoop p = PlayerLoop.current;
		int max = p == null ? Integer.MAX_VALUE : CardRenderer.maxScroll(p.gfx(), self, HZ.stack());
		self.scroll = Math.max(0, Math.min(in, max));
	}
	private boolean msgEdits() { return msgFocused && HZ.clickField == null && msgVisible(); }
	@Override boolean editSelectedText(String cmd) {
		if (msgEdits()) {
			String t = msgText();
			int s = msgSelectionStart(), e = msgSelectionEnd();
			switch (cmd) {
				case "copy": if (e > s) textClip = t.substring(s, e); break;
				case "cut": if (e > s) { textClip = t.substring(s, e); setMsgText(t.substring(0, s) + t.substring(e)); msgSetCaret(s); } break;
				case "delete": case "clear": if (e > s) { setMsgText(t.substring(0, s) + t.substring(e)); msgSetCaret(s); } break;
				case "paste": if (textClip != null) { setMsgText(t.substring(0, s) + textClip + t.substring(e)); msgSetCaret(s + textClip.length()); } break;
				default: return false;
			}
			return true;
		}
		OField f = TTalk.openField;
		if (f == null || f.view() == null) return false;
		FieldView v = f.view();
		String text = v.getText() == null ? "" : v.getText();
		int s = Math.max(0, Math.min(v.getSelectionStart(), text.length())), e = Math.max(s, Math.min(v.getSelectionEnd(), text.length()));
		switch (cmd) {
			case "copy": if (e > s) textClip = text.substring(s, e); break;
			case "cut": if (e > s) { textClip = text.substring(s, e); v.replaceSelection(""); } break;
			case "delete": case "clear": if (e > s) v.replaceSelection(""); break;
			case "paste": if (textClip != null) v.replaceSelection(textClip); break;
			default: return false;
		}
		loop.markDirty();
		return true;
	}
	@Override boolean hasSelectedText() {
		if (msgEdits()) return msgSelectionEnd() > msgSelectionStart();
		OField f = TTalk.openField;
		return f != null && f.view() != null && f.view().getSelectionEnd() > f.view().getSelectionStart();
	}
	@Override boolean canPasteText() { return (TTalk.openField != null || msgEdits()) && textClip != null; }
	/** set the cursor: HyperCard's cursor (HzCursors) on the page while the handlers run; back to the page's own at idle */
	@Override boolean cursorSetters(String inProperty, String value, String prpty) throws xTalkException {
		boolean done = super.cursorSetters(inProperty, value, prpty);
		if (done && "cursor".equals(prpty)) { String css = HzCursors.css(HZ.stack(), value); if (css != null) showCursor(css); }
		return done;
	}
	/** The CSS cursor the page shows ("" its own) */
	private static String cursorShown = "";
	/** A cursor set while the mouse was down stays until it goes up (TTalk.cursorHeldByPress: card 10 of HyperPiece 2 sets it
	 *  in mouseStillDown, idle between each) */
	static volatile boolean cursorHeld;
	void showCursor(String css) {
		synchronized (PlayerUiHost.class) { if (css.equals(cursorShown)) return; cursorShown = css; }
		loop.host.post("cursor", css);
	}
	/** HyperCard idle again (TTalk.returnToIdle): its browse cursor, the page's own here, unless the mouse is still down */
	@Override void pointerAtIdle() {
		if (loop.host.mouseIsDown()) { cursorHeld = !cursorShown.isEmpty() && !cursorShown.equals(idleCursor()); return; }
		cursorHeld = false;
		showCursor(idleCursor());
	}
	/** The mouse went up (PlayerLoop): a cursor held for the press goes when HyperCard is idle */
	void mouseReleased() {
		if (cursorHeld && TTalk.idle) { cursorHeld = false; showCursor(idleCursor()); }
	}
	/** The pointer moved while HyperCard is idle (PlayerLoop): the cursor of what is under it */
	void pointerMoved() {
		if (!cursorHeld && TTalk.idle && !loop.host.mouseIsDown()) showCursor(idleCursor());
	}
	/** The cursor over the card while HyperCard is idle, as the desktop's card window: the browse tool's hand (the stack's
	 *  CURS 128, else the browser's hand: TUtil.browseCursor), the I-beam over an unlocked field's text; with the other tools
	 *  the page's own (the button and field tools' arrow) */
	String idleCursor() {
		OStack st = HZ.stack();
		if (st == null || !"browse".equals(HzPaint.tool())) return "";
		int x = loop.host.mouseX(), y = loop.host.mouseY();
		OObject part = HeadlessPointer.partAt(x, y, new boolean[1]);
		// (OField.enabled false: an unlocked field; a scrolling field's scroll bar keeps the hand)
		if (part instanceof OField f && !f.enabled && !(f.style == 5 && x >= f.left + f.width - 17)) { String c = HzCursors.css(st, "ibeam"); if (c != null) return c; }
		String c = HzCursors.css(st, "hand");
		return c != null ? c : "";
	}

	/** find's box round the found text (the desktop: FieldsUI.showFoundBox's grey highlight); CardRenderer draws it */
	@Override void showFoundBox(OField self, int start, int end) {
		CardRenderer.foundField = self; CardRenderer.foundStart = start; CardRenderer.foundEnd = end;
		loop.markDirty();
	}
	@Override void clearFoundBox() {
		if (CardRenderer.foundField == null) return;
		CardRenderer.foundField = null;
		loop.markDirty();
	}
	@Override void showFind() {
		setMsgText("find \"\"");
		msgSetCaret(6);
		loop.host.post("msgCaret", "6");
	}

	/** select <button|field>: the part Delete Button / Delete Field (doMenu) remove, with its tool chosen (SwingUiHost's) */
	@Override void selectPart(OObject obj) {
		HeadlessMenus.selected = obj;
		try { TTalk.doScriptforMenu(obj instanceof OButton ? "choose button tool" : obj instanceof OField ? "choose field tool" : "choose browse tool"); }
		catch (xTalkException e) { /* the tool stays */ }
	}

	/** FontExists(name[, size]) (HyperCard 2.4 Home's XFCN, answered by the desktop from its Font menu): the fonts the player shows */
	/** save [this] stack: kept now (PlayerSaving: the page keeps it); save … as …: the browser writes no files: the result
	 *  says so (File > Save a Copy… downloads one) */
	@Override boolean saveCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if (str != "save") return false;
		boolean as = false;
		for (int i = 1; i < stringList.size(); i++) if (typeAry[i] != TTalk.wordType.STRING && stringList.get(i).equalsIgnoreCase("as")) as = true;
		int n = stringList.size();
		boolean current = n == 1 || n == 2 && stringList.get(1).equalsIgnoreCase("stack")
			|| n == 3 && stringList.get(1).equalsIgnoreCase("this") && stringList.get(2).equalsIgnoreCase("stack");
		result.theResult = as ? "The browser saves no files: File > Save a Copy… downloads the stack"
			: current ? (PlayerSaving.saveNow(loop.host, HZ.stack()) ? "" : "The stack could not be kept")
			: "Only this stack can be saved here";
		result.ret = 0;
		return true;
	}

	/** The player's files on a disk named as the desktop names the root (FileSystem68k.hostVolumes): the long name of a stack
	 *  is "Macintosh HD:stacks:HyperPiece 2", not the relative ":stacks:HyperPiece 2" (HyperPiece 2 card 50's paths), and such
	 *  a path finds its file again (TTalk.hostPathOfMacPath) */
	static final String VOLUME = "Macintosh HD";
	@Override java.lang.String fileSystem68kMacPathOf(java.io.File f) {
		if (f == null) return null;
		String p = f.getAbsolutePath().replace('\\', '/');
		if (!p.startsWith("/")) return null;
		// on the page volume (the CD the stack came from): "Cosmic Osmo CD:Cosmic Osmo", as on the CD
		String volume = VOLUME;
		String page = pageVolumeName();
		String boot = appPathsBootVolumeDirectory().getAbsolutePath().replace('\\', '/');
		if (page != null && (p.equals(STACKS) || p.startsWith(STACKS + "/"))) { volume = page; p = p.substring(STACKS.length()); }
		// the 68K VM's startup disk, as its File Manager names it (SysDir: "HyperZebra HD:System Folder:")
		else if (p.equals(boot) || p.startsWith(boot + "/")) { volume = FileSystem68k.BOOT_VOLUME_NAME; p = p.substring(boot.length()); }
		StringBuilder out = new StringBuilder(volume);
		for (String part : p.split("/")) if (!part.isEmpty()) out.append(':').append(part.replace(':', '/'));
		if (out.length() == volume.length()) out.append(':');
		return out.toString();
	}
	@Override java.io.File fileSystem68kHostVolumeNamed(java.lang.String name) {
		if (VOLUME.equalsIgnoreCase(name)) return new java.io.File("/");
		// the 68K VM's startup disk (an external's SysDir gives "HyperZebra HD:System Folder:": Cosmic Osmo keeps its saved game
		// there, "Copy of CO.Hard Disk")
		if (FileSystem68k.BOOT_VOLUME_NAME.equalsIgnoreCase(name)) return appPathsBootVolumeDirectory();
		return mountedDiskFolder(name);
	}

	/** The folder the page's stack and the files beside it are in (WebMain) */
	static final String STACKS = "/stacks";
	/**
	 * The page volume: a CD's name the stack's scripts give to the files exported with it ("Cosmic Osmo CD:Cosmic Osmo Files:
	 * CO.Resource"; Export for Web found them: data-volumes, the property hz.player.volumes). The browser has no such disk, so
	 * the stack's folder is that volume: a CD-ROM title that checks for its CD finds it. Null when there is none.
	 */
	static String pageVolumeName() {
		String v = System.getProperty("hz.player.volumes", "");
		int bar = v.indexOf('|');
		String first = (bar >= 0 ? v.substring(0, bar) : v).trim();
		return first.isEmpty() || first.indexOf(':') >= 0 ? null : first;
	}
	private static java.util.Map<String, java.io.File> pageVolumes;
	private static String pageVolumesOf;
	@Override java.util.Map<String, java.io.File> pageVolumes() {
		String names = System.getProperty("hz.player.volumes", "");
		if (pageVolumes == null || !names.equals(pageVolumesOf)) {
			java.util.Map<String, java.io.File> out = new java.util.LinkedHashMap<>();
			for (String v : names.split("\\|")) {
				v = v.trim();
				if (!v.isEmpty() && v.indexOf(':') < 0 && !v.equalsIgnoreCase(VOLUME)) out.put(v, new java.io.File(STACKS));
			}
			pageVolumes = java.util.Collections.unmodifiableMap(out);
			pageVolumesOf = names;
		}
		return pageVolumes;
	}
	@Override java.io.File mountedDiskFolder(String name) {
		for (java.util.Map.Entry<String, java.io.File> v : pageVolumes().entrySet()) if (v.getKey().equalsIgnoreCase(name)) return v.getValue();
		return null;
	}
	/** The stack's folder for relative file names (open file "x", answer file's list): beside the packed .hzb, never the
	 *  68K player's _unpacked folder (Import/Export Tools: answer file of type "STAK" listed only the stack itself, not the
	 *  other stacks beside it, and the files it wrote went into /stacks/_unpacked) */
	@Override java.io.File fileSystem68kExternalsVolume(OStack s) {
		java.io.File packed = s == null || s.path == null ? null : HeadlessStacks.packedOf(new java.io.File(s.path));
		return packed != null ? packed.getAbsoluteFile().getParentFile() : super.fileSystem68kExternalsVolume(s);
	}

	@Override boolean screenFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		// the screenRect: the card's (the browser page shows the card, not a Mac screen round it; cardWindowRect)
		if ("screenrect".equals(message)) { java.awt.Rectangle c = centredCard(); result.theResult = "0,0," + c.width + "," + c.height; return true; }
		if (!"fontexists".equals(message) || !isFunc) return super.screenFunctions(message, params, target, memData, isFunc, result);
		result.theResult = Boolean.toString(params.length > 0 && HzFonts.shows(params[0]));
		return true;
	}

	/** A script error: HyperCard's dialog, with OK only (the page has no script editor or debugger; it was silent before,
	 *  the handler just stopped), and the text to the page's console */
	@Override int showScriptErrorDialog(String errorMessage, String statement, int line, OObject object, boolean hasScript, boolean canDebug) {
		String text = TTalk.scriptErrorText(errorMessage, statement, line, object);
		loop.host.post("scripterror", text);
		loop.draw();
		loop.host.dialog("answer", text, new String[]{"OK"}, null, new String[1]);
		return TTalk.ERROR_CANCEL;
	}

	// ---- ask file / answer file: the player's files (TeaVM's file system), in the stack's folder ----

	/** The folder scripts' files go in: the stack's (open file "x" finds x there) */
	static java.io.File documentsFolder() {
		java.io.File f = new java.io.File(TTalk.resolveFilePath("x")).getAbsoluteFile().getParentFile();
		return f != null ? f : new java.io.File("/stacks");
	}
	/** A name typed for a file: no folders in it (":" and "/" become "-"), at most 31 characters as on HFS */
	static String fileName(String typed) {
		String n = typed == null ? "" : typed.trim().replace(':', '-').replace('/', '-');
		while (n.startsWith(".")) n = n.substring(1);
		return n.length() > 31 ? n.substring(0, 31) : n;
	}
	/** ask file: the name to save as (a dialog with a name field); a name that is there already is replaced only when the user
	 *  says so. The file's path as HyperCard gives it ("Macintosh HD:stacks:Letter"), null for Cancel */
	@Override String askFile(OStack stack, String prompt, String filename) {
		loop.draw();
		java.io.File folder = documentsFolder();
		String[] reply = new String[1];
		String name = filename == null ? "" : fileName(filename);
		while (true) {
			int n = loop.host.dialog("askFile", StackShown.prompt(stack, prompt == null ? "" : prompt),
				new String[]{International.t("Cancel"), International.t("Save")}, name, reply);
			if (n != 2) return null;
			name = fileName(reply[0]);
			if (name.isEmpty()) continue;
			java.io.File f = new java.io.File(folder, name);
			if (f.isDirectory()) continue;
			if (f.exists() && loop.host.dialog("answer", International.t("Replace existing “{0}”?", name),
					new String[]{International.t("Cancel"), International.t("Replace")}, null, new String[1]) != 2) continue;
			return TTalk.scriptPath(f);
		}
	}
	/** answer file prompt [of type T [or T…]]: the player's files in the stack's folder to choose from (stacks for STAK, other
	 *  files for other types), or one the user brings in from the computer (Upload…), which is kept beside them. it is the
	 *  chosen file's path ("Macintosh HD:stacks:Letter"; a stack's without .hzb, as the desktop), Cancel: it empty and the
	 *  result "Cancel" */
	@Override boolean answerFile(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException {
		if (!stringList.get(next).equals("file")) return false;
		next++;
		int j;
		for (j = next; j + 1 < stringList.size(); j++) if (stringList.get(j).equals("of") && stringList.get(j + 1).equals("type")) break;
		if (j + 1 >= stringList.size()) j = stringList.size();
		String prompt = next <= j - 1 ? self.Evalution(stringList, typeAry, next, j - 1, memData, object, target) : "";
		java.util.List<String> types = new java.util.ArrayList<>();
		if (j + 2 < stringList.size()) {
			int from = j + 2;
			for (int k = j + 2; k <= stringList.size(); k++) {
				if (k == stringList.size() || (typeAry[k] != TTalk.wordType.STRING && (stringList.get(k).equalsIgnoreCase("or") || stringList.get(k).equals(",")) && k > from)) {
					types.add(self.Evalution(stringList, typeAry, from, k - 1, memData, object, target));
					from = k + 1;
				}
			}
		}
		boolean stacks = types.isEmpty() || types.stream().anyMatch(t -> t.trim().equalsIgnoreCase("STAK"));
		boolean others = types.isEmpty() || types.stream().anyMatch(t -> !t.trim().equalsIgnoreCase("STAK"));
		java.io.File folder = documentsFolder();
		java.util.List<String> names = new java.util.ArrayList<>();
		listFiles(folder, "", stacks, others, names, 0);
		java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
		loop.draw();
		String[] reply = new String[1];
		int n = loop.host.dialog("answerFile", StackShown.prompt(self.stackOf(object), prompt),
			new String[]{International.t("Cancel"), International.t("Open"), International.t("Upload…")}, String.join("\n", names), reply);
		java.io.File chosen = null;
		if (n == 2 && reply[0] != null && names.contains(reply[0])) chosen = new java.io.File(folder, reply[0]);
		else if (n == 3 && reply[0] != null) {
			// a file from the computer: its name and size, then its bytes from the page
			String[] f = reply[0].split("\n", 2);
			String name = fileName(f[0]);
			int size = -1;
			try { size = f.length > 1 ? Integer.parseInt(f[1].trim()) : -1; } catch (NumberFormatException e) {}
			byte[] bytes = !name.isEmpty() && size >= 0 && size <= UPLOAD_LIMIT ? loop.host.upload(size) : null;
			if (bytes != null) {
				java.io.File into = new java.io.File(folder, name);
				try (java.io.FileOutputStream o = new java.io.FileOutputStream(into)) { o.write(bytes); chosen = into; }
				catch (java.io.IOException e) { chosen = null; }
				if (chosen != null) loop.host.keepFile(chosen.getAbsolutePath().replace('\\', '/'), bytes);
			} else if (size > UPLOAD_LIMIT) {
				loop.host.dialog("answer", International.t("The file is too large to open here."), new String[]{"OK"}, null, new String[1]);
			}
		}
		if (chosen == null) {
			TTalk.setVariable(memData, "it", "");
			result.theResult = "Cancel";
			return true;
		}
		TTalk.setVariable(memData, "it", chosen.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") ? TTalk.macStackPath(chosen.getPath()) : TTalk.scriptPath(chosen));
		result.theResult = "";
		return true;
	}
	/** the largest file Upload… takes (the player keeps it in memory) */
	static final int UPLOAD_LIMIT = 32 << 20;
	/** The files under folder (its folders a few levels down), by their paths from it: stacks (.hzb) and/or other files. The
	 *  player's own folders (lang, _unpacked, a stack's working folder) are left out */
	private static void listFiles(java.io.File folder, String prefix, boolean stacks, boolean others, java.util.List<String> out, int depth) {
		java.io.File[] list = folder.listFiles();
		if (list == null) return;
		for (java.io.File f : list) {
			String name = f.getName();
			if (name.startsWith(".") || name.startsWith("_") || name.equals("lang")) continue;
			boolean stack = name.toLowerCase(java.util.Locale.ROOT).endsWith(".hzb");
			if (f.isDirectory()) { if (!stack && depth < 3) listFiles(f, prefix + name + "/", stacks, others, out, depth + 1); else if (stack && stacks) out.add(prefix + name); continue; }
			if (stack ? stacks : others) out.add(prefix + name);
		}
	}
	/** a file the script or a 68K external wrote: kept by the page (only the player's own files: under /stacks, and on the 68K
	 *  VM's startup disk, where externals keep what they save in the System Folder: Cosmic Osmo's "Copy of CO.Hard Disk").
	 *  Its AppleDouble sidecar ("._name": the Finder info and resource fork the 68K File Manager keeps there) goes with it. */
	@Override void scriptFileWritten(java.io.File f) {
		String p = f.getAbsolutePath().replace('\\', '/');
		if (!keptPath(p) || !f.isFile() || f.length() > UPLOAD_LIMIT) return;
		try { loop.host.keepFile(p, Platform.get().readAllBytes(f)); } catch (java.io.IOException e) {}
		java.io.File sidecar = new java.io.File(f.getAbsoluteFile().getParentFile(), "._" + f.getName());
		if (sidecar.isFile() && sidecar.length() <= UPLOAD_LIMIT)
			try { loop.host.keepFile(sidecar.getAbsolutePath().replace('\\', '/'), Platform.get().readAllBytes(sidecar)); } catch (java.io.IOException e) {}
	}
	/** Whether the page keeps a file at that path for its next visit (WebMain puts such files back) */
	static boolean keptPath(String p) {
		if (p == null || p.contains("/../") || p.endsWith("/")) return false;
		if (p.startsWith(STACKS + "/")) return true;
		String boot = UiHost.get().appPathsBootVolumeDirectory().getAbsolutePath().replace('\\', '/') + "/";
		if (p.startsWith(boot)) return true;
		// (the stack's folder: WebMain asks before a stack is open, where there is none yet)
		if (HZ.stack() == null) return false;
		String docs = documentsFolder().getAbsolutePath().replace('\\', '/') + "/";
		return p.startsWith(docs);
	}

	@Override String[] ask(OStack stack, String text, String input, String cancel, String ok, boolean hidden) {
		String[] reply = new String[1];
		loop.draw();
		int n = loop.host.dialog(hidden ? "password" : "ask", StackShown.prompt(stack, text == null ? "" : text), StackShown.buttons(stack, new String[]{cancel, ok}), input == null ? "" : input, reply);
		mouseClicked = false; // (as answer)
		return new String[]{n == 1 ? cancel : ok, reply[0] == null ? "" : reply[0]};
	}

	// lock screen and visual effects: PlayerLoop plays them on the canvas (VEffect's rules, HzEffects' frames)
	@Override void vEffectSetOldOff() { loop.lockScreen(true); }
	@Override void vEffectLockLazily() { loop.lockScreen(false); }
	@Override void vEffectVisualEffect(int effect, int toEffect, int speed) { speedUnlock(); loop.effect(effect, toEffect, speed, true); }
	@Override void vEffectVisualEffect(int effect, int toEffect, int speed, boolean finish) { speedUnlock(); loop.effect(effect, toEffect, speed, finish); }
	@Override void vEffectHoldPlain(int speed) { loop.holdPlain(speed); }
	@Override void freezeForCardChange(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		cardChangeShown = true; // (the screen was not locked: the new card goes to the window)
		loop.lockScreen(!TTalk.pendingVisuals.isEmpty());
	}
	@Override void playVisualEffects(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		loop.playEffects(TTalk.pendingVisuals);
	}
	@Override void unlockScreenAtIdle() { loop.effect(0, 0, 3, true); }
	@Override void flashScreen(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int cnt) throws xTalkException { loop.flash(cnt); }
	@Override void cardRedrawn(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		Externals68k x = Externals68k.Runner.current; if (x != null) x.cardRedrawn();
		boolean shown = cardChangeShown;
		cardChangeShown = false;
		OStack st = HZ.stack();
		if (StackSpeed.limited() && st != null) StackSpeed.redraw(new java.awt.Rectangle(0, 0, st.width, st.height), 1, !shown); // the whole card: composed, copied to the window unless locked
		loop.markDirty();
	}
	// what HyperCard does to the card window, for what 68K externals drew on it (the 68K player; none in the core player)
	@Override void cardWindow68kPartCopied(int top, int left, int bottom, int right) { Externals68k x = Externals68k.Runner.current; if (x != null) x.partCopied(top, left, bottom, right); }
	@Override void cardWindow68kPartMoved(OObject part, int top, int left, int bottom, int right) { Externals68k x = Externals68k.Runner.current; if (x != null) x.partMoved(part, top, left, bottom, right); }
	@Override void cardWindow68kHoldScreen() { Externals68k x = Externals68k.Runner.current; if (x != null) x.holdScreen(); }
	@Override void cardWindow68kReleaseScreen() { Externals68k x = Externals68k.Runner.current; if (x != null) x.releaseScreen(); }
	@Override boolean xcmd68kRedrawHookedCards(OStack stack) { Externals68k x = Externals68k.Runner.current; return x != null && x.redrawHookedCards(stack); }
	@Override boolean xcmd68kRedrawHookedCards(OStack stack, int top, int left, int bottom, int right) { Externals68k x = Externals68k.Runner.current; return x != null && x.redrawHookedCards(stack, top, left, bottom, right); }
	@Override boolean xcmd68kCardDrawingHooked(OStack stack) { Externals68k x = Externals68k.Runner.current; return x != null && x.cardDrawingHooked(stack); }
	@Override void externalsAtStackClose(OStack stack) { MovieWindows.stackClosing(); PictureWindows.stackClosing(); Externals68k x = Externals68k.Runner.current; if (x != null) x.stackClosing(stack); }
	@Override boolean xcmd68kHasSessions() { Externals68k x = Externals68k.Runner.current; return x != null && x.hasSessions(); }
	@Override boolean xcmd68kHasWindowSessions() { Externals68k x = Externals68k.Runner.current; return x != null && x.hasWindowSessions(); }
	@Override Xcmd68k.ExternalWindow xcmd68kFindWindow(String name) { Externals68k x = Externals68k.Runner.current; return x == null ? null : x.findWindow(name); }
	@Override Xcmd68k.ExternalWindow xcmd68kFindWindowById(int id) { Externals68k x = Externals68k.Runner.current; return x == null ? null : x.findWindowById(id); }
	@Override OXWindow newXWindow(Xcmd68k.ExternalWindow w) { Externals68k x = Externals68k.Runner.current; return x == null ? null : x.newWindow(w); }
	@Override void xcmd68kPumpIfDue() { Externals68k x = Externals68k.Runner.current; if (x != null) x.pumpIfDue(); }
	@Override void xcmd68kPumpWhileWaiting() { Externals68k x = Externals68k.Runner.current; if (x != null) x.pumpWhileWaiting(); }
	/** XWindowsUI.partRedrawnByExternal: a part of the card on show, an external drawing the card window at every copy of the card */
	@Override boolean partRedrawnByExternal(OObject part) {
		OStack st = HZ.stack();
		if (HZ.lockedScreen || st == null || st.curCard == null || part == null) return false;
		if (part.parent != st.curCard && part.parent != st.curCard.bg) return false;
		return xcmd68kCardDrawingHooked(st);
	}
	/** textArrows in the open field (TUtil arrowKey, TTalk.keyboardCommand): the insertion point moves a character left or
	 *  right, a line up or down at the same x (kept over several ups and downs, as Swing's magic caret); a selection collapses
	 *  to its start (left, up) or end (right, down); with Shift the selection grows or shrinks from the end that moves, as
	 *  the desktop's text area. Japanese text moves a whole character (UTF-16; surrogate pairs kept whole). The field scrolls
	 *  so that it shows. */
	private int goalX = -1, goalDot = -1;
	@Override void moveCaretInView(TTalk self, String message, String param, MemoryData memData, OField f) throws xTalkException {
		FieldView v = f.view();
		OStack st = HZ.stack();
		if (v == null || st == null) return;
		String text = v.getText() == null ? "" : v.getText();
		int len = text.length(), s = v.getSelectionStart(), e = v.getSelectionEnd(), dot = v.getCaretPosition();
		if (dot != s && dot != e) dot = e;
		int mark = s == e ? dot : dot == s ? e : s;
		boolean shift = (loop.host.modifiers() & PlayerHost.SHIFT) != 0;
		boolean vertical = param.equals("up") || param.equals("down");
		if (!shift && s != e && !vertical) dot = param.equals("left") ? s : e;
		else if (param.equals("left")) { if (dot > 0) dot -= dot > 1 && Character.isLowSurrogate(text.charAt(dot - 1)) && Character.isHighSurrogate(text.charAt(dot - 2)) ? 2 : 1; }
		else if (param.equals("right")) { if (dot < len) dot += dot + 1 < len && Character.isHighSurrogate(text.charAt(dot)) && Character.isLowSurrogate(text.charAt(dot + 1)) ? 2 : 1; }
		else if (vertical) {
			int from = shift || s == e ? dot : param.equals("up") ? s : e;
			Gfx g = loop.gfx();
			int[] at = CardRenderer.charPlace(g, f, st, from);
			int x = goalDot == from && goalX >= 0 ? goalX : at[0];
			int[] span = CardRenderer.lineSpan(g, f, st, from);
			boolean up = param.equals("up");
			if (up ? span[0] <= (f.wideMargins ? 5 : 1) : span[2] == 1) dot = from; // the first/last line: it stays
			else {
				int yDoc = up ? span[0] - 1 : span[1];
				int y = f.style == 5 ? yDoc - f.scroll + CardRenderer.SCROLL_DY : yDoc;
				dot = CardRenderer.hitText(g, f, st, x, y);
			}
			goalX = x;
			goalDot = dot;
		}
		if (!vertical) goalDot = -1;
		if (shift && v instanceof PlainFieldView) ((PlainFieldView) v).selectFromMark(mark, dot);
		else { v.setSelectionStart(dot); v.setSelectionEnd(dot); }
		f.setSelectedLine(0);
		scrollSelectionIntoView(f, dot);
		loop.markDirty();
	}

	/** WindowUI.scrollSelectionIntoView: select scrolls a scrolling field at once so that the selection's start shows (the
	 *  line wholly; the last line: the field's very end). ColorizeHC Technical Scripts' select line 12 of its list of drawing
	 *  modes left the list at its top, the mode chosen out of sight. */
	@Override void scrollSelectionIntoView(OField fld, int offset) {
		PlayerLoop p = PlayerLoop.current;
		if (fld == null || fld.style != 5 || p == null) return;
		int[] span = CardRenderer.lineSpan(p.gfx(), fld, HZ.stack(), offset);
		// the text shows below the frame (SCROLL_DY) and above the bottom (scrollVisible); the last line: the field's very end
		int margin = fld.wideMargins ? 5 : 1, visible = CardRenderer.scrollVisible(fld), value = fld.scroll;
		if (span[0] - margin < value) value = span[0] - margin;
		else if (span[1] > value + visible) value = span[2] == 1 ? Integer.MAX_VALUE : span[1] - visible;
		value = Math.max(0, value);
		if (value != fld.scroll) fld.setScroll(value);
	}

	/** The card changed without lock screen (freezeForCardChange): its picture goes to the window (cardRedrawn charges it) */
	private boolean cardChangeShown;
	/** A stack's speed limit (StackSpeed) charges the card display as the desktop's CardRepaintManager does: each part redrawn is
	 *  composed in HyperCard's card, then copied to the window unless the screen is locked */
	private static void speedRedraw(java.awt.Rectangle r) {
		OStack st = HZ.stack();
		if (st == null) return;
		r = r.intersection(new java.awt.Rectangle(0, 0, st.width, st.height));
		if (!r.isEmpty()) StackSpeed.redraw(r, 1, HZ.lockedScreen && !HZ.capturingEffect);
	}
	/** unlock screen: the whole card goes to the window (the desktop repaints it) */
	private static void speedUnlock() {
		OStack st = HZ.stack();
		if (StackSpeed.limited() && st != null) StackSpeed.redraw(new java.awt.Rectangle(0, 0, st.width, st.height), 1, false);
	}
	/** Whether a part is on the card on show (its redraws show) */
	private static boolean onCard(OObject part) {
		OStack st = HZ.stack();
		return (part instanceof OButton || part instanceof OField) && st != null && st.curCard != null && (part.parent == st.curCard || part.parent == st.curCard.bg);
	}
	@Override void partShownOrHidden(OObject part) {
		if (StackSpeed.limited() && onCard(part)) speedRedraw(new java.awt.Rectangle(part.left, part.top, part.width, part.height));
	}

	/** XWindowsUI.partChangedOnScreen: HyperCard copies the changed part's rectangle (and where it was) into the window, and the
	 *  external that hooked that copy draws it (here at once: the desktop gathers changes 1/20 s apart into one redraw) */
	@Override void partChangedOnScreen(OObject part, java.awt.Rectangle before) {
		if (StackSpeed.limited() && onCard(part)) { // the speed limit: what HyperCard took to draw it (the desktop: CardRepaintManager)
			speedRedraw(new java.awt.Rectangle(part.left, part.top, part.width, part.height));
			if (before != null) speedRedraw(before);
		}
		Externals68k x = Externals68k.Runner.current;
		if (x == null || !(part instanceof OButton || part instanceof OField) || !partRedrawnByExternal(part)) return;
		java.awt.Rectangle r = new java.awt.Rectangle(part.left, part.top, Math.max(1, part.width), Math.max(1, part.height));
		if (before != null && !before.isEmpty() && !before.equals(r)) r = r.union(before);
		x.holdScreen();
		try { x.redrawHookedCards(HZ.stack(), r.y, r.x, r.y + r.height, r.x + r.width); }
		finally { x.releaseScreen(); }
	}

	/** commandKeyDown's default (ProgramsUI.commandKeyShortcut): Cut, Copy and Paste on the selected text, else the enabled
	 *  item of the card's menu bar with that Command key, chosen through doMenu */
	@Override void commandKeyShortcut(String ch) {
		if (ch == null || ch.isEmpty()) return;
		char c = Character.toUpperCase(ch.charAt(0));
		if ("XCV".indexOf(c) >= 0 && TTalk.editSelectedText(new String[]{"cut", "copy", "paste"}["XCV".indexOf(c)])) return;
		MMenuBar bar = PlayerLoop.shownMenuBar();
		if (bar == null) return;
		for (MMenu m : bar.menus()) {
			if (!m.isVisible() || m.getPopupMenu() == null || PlayerLoop.levelHides(m)) continue;
			for (MComponent part : m.getPopupMenu().getComponents()) {
				if (!(part instanceof MMenuItem it) || part instanceof MMenu || !it.isVisible() || !it.isEnabled() || PlayerLoop.levelOff(m, it)) continue;
				MKeyStroke k = it.getAccelerator();
				if (k == null || k.getKeyCode() != c) continue;
				try { TTalk.doScriptforMenu("doMenu " + quoted(it.getText()) + ", " + quoted(m.getText())); }
				catch (xTalkException e) { System.out.println("commandKeyDown " + ch + ": " + e.getMessage()); }
				return;
			}
		}
	}

	private static String quoted(String s) { return "\"" + (s == null ? "" : s.replace("\"", "\" & quote & \"")) + "\""; }

	@Override void setMenuBarVisible(boolean visible) { loop.menuBarShown = visible; loop.sendMenus(); }

	/** set userLevel: the menus of the new level (PlayerLoop.levelHides) */
	@Override void enforceUserLevel() { loop.sendMenus(); }

	@Override void beep() { loop.host.post("beep", ""); }

	// a stack the 68K player unpacked (HeadlessStacks.unpack: /stacks/_unpacked/X.hzb): where it is is its packed file's place,
	// as StackBundle has it on the desktop (the stacks beside it are found by name: start using stack "DD lib")
	@Override java.io.File stackBundlePackedOf(java.io.File dir) { return HeadlessStacks.packedOf(dir); }
	/** the folder a stack is in: an unpacked one's is its packed file's (the movies beside it: GetFilePath) */
	@Override java.io.File stackFolderOf(java.io.File stackFile) { java.io.File packed = HeadlessStacks.packedOf(stackFile); return packed != null ? packed.getParentFile() : super.stackFolderOf(stackFile); }
	@Override java.io.File fileManager68kExistingOrUnarchived(java.io.File folder, String name) {
		if (folder == null || name == null) return null;
		Externals68k x = Externals68k.Runner.current;
		if (x != null) return x.existingFile(folder, name);
		java.io.File f = new java.io.File(folder, name);
		return f.exists() ? f : null;
	}
	/** a path in an unpacked stack is that path under its packed file, as scripts see it (the destination: FROMINE VR2's
	 *  pathOfMe() & "DATA:fvr2_engine" looked in _unpacked) */
	@Override java.io.File stackBundleHost(java.io.File f) { java.io.File h = HeadlessStacks.hostOf(f); return h != null ? h : super.stackBundleHost(f); }
	@Override String stackBundleHost(String path) { if (path == null) return null; java.io.File h = HeadlessStacks.hostOf(new java.io.File(path)); return h != null ? h.getPath() : super.stackBundleHost(path); }
	@Override java.io.File stackBundleFolderAround(java.io.File f) {
		java.io.File packed = HeadlessStacks.packedOf(f);
		return packed != null ? packed.getParentFile() : super.stackBundleFolderAround(f);
	}

	// ---- imageToArray / arrayToImage (TalkBinary; EditorsUI with ImageIO on the desktop): PNG, JPEG and PICT data, PNG out
	@Override String imageToArray(String data) throws xTalkException {
		HzImage img = data == null || data.isEmpty() ? null : decodeImageData(TalkBinary.bytes(data));
		if (img == null || img.width <= 0 || img.height <= 0) throw new xTalkException("{0}: not image data (PNG, JPEG, GIF, BMP or PICT)", "imageToArray");
		if ((long) img.width * img.height > 50_000_000L) throw new xTalkException("imageToArray: the image is too large");
		return TalkPixels.arrayText(img.width, img.height, img.argb.clone());
	}

	static HzImage decodeImageData(byte[] b) {
		if (b == null || b.length < 8) return null;
		if (PngDecoder.isPng(b)) return PngDecoder.decode(b);
		if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
			try {
				JpegDecoder j = JpegDecoder.decode(b, 0, b.length);
				int[] argb = new int[j.width * j.height];
				for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | j.rgb[i];
				return new HzImage(j.width, j.height, argb);
			} catch (java.io.IOException | RuntimeException e) { return null; }
		}
		HzImage p = b.length > 512 + 10 ? HzPict.decode(b, HzImages.pictCanvas) : null; // a PICT file
		return p != null ? p : b.length > 10 ? HzPict.decodeResource(b, HzImages.pictCanvas) : null; // a PICT resource's data
	}

	@Override String arrayToImage(String array, String format) throws xTalkException {
		if (!TalkCollection.isCollection(array)) throw new xTalkException("arrayToImage requires an array of rows of pixels");
		String f = format == null ? "png" : format.trim().toLowerCase(java.util.Locale.ROOT);
		if (f.isEmpty()) f = "png";
		if (f.equals("jpg")) f = "jpeg";
		if (!f.equals("png") && !f.equals("jpeg") && !f.equals("gif") && !f.equals("bmp"))
			throw new xTalkException("arrayToImage: the format must be png, jpeg, gif or bmp: {0}", format);
		int w, h;
		int[] argb;
		Object[] known = TalkPixels.lookup(array);
		if (known != null) { w = (Integer) known[0]; h = (Integer) known[1]; argb = (int[]) known[2]; }
		else {
			TalkCollection rows = TalkCollection.decode(array);
			if (!rows.isArray()) throw new xTalkException("arrayToImage requires an array of rows of pixels");
			h = rows.items.size(); w = 0;
			java.util.ArrayList<java.util.List<String>> pixels = new java.util.ArrayList<>(h);
			for (String r : rows.items) {
				java.util.List<String> row = TalkCollection.isCollection(r) && TalkCollection.decode(r).isArray()
					? TalkCollection.decode(r).items : java.util.List.of();
				pixels.add(row);
				w = Math.max(w, row.size());
			}
			if (w == 0 || h == 0) throw new xTalkException("arrayToImage: the array has no pixels");
			if ((long) w * h > 50_000_000L) throw new xTalkException("arrayToImage: the image is too large");
			argb = new int[w * h];
			int[] c = new int[4];
			for (int y = 0; y < h; y++) {
				java.util.List<String> row = pixels.get(y);
				for (int x = 0; x < row.size(); x++) {
					TalkBinary.pixel(row.get(x), c);
					argb[y * w + x] = c[3] << 24 | c[0] << 16 | c[1] << 8 | c[2];
				}
			}
		}
		if (!f.equals("png")) throw new xTalkException("arrayToImage: cannot write {0}", f); // the player writes PNG only
		return TalkBinary.string(HzPng.encode(new HzImage(w, h, argb)));
	}

	// ---- HyperCard's Picture and Palette XCMDs (PictureWindows; WindowUI.pictureCommand/paletteCommand on the desktop)
	@Override Result pictureCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		result.ret = 0;
		OStack st = HZ.stack();
		String name = params.length >= 1 ? params[0] : "";
		// no source: a file, then a resource of that name (HyperCard's default is a file)
		String source = params.length >= 2 && params[1].trim().length() > 0 ? params[1].trim() : "";
		String style = params.length >= 3 && params[2].length() > 0 ? params[2] : "rect";
		boolean visible = !(params.length >= 4 && params[3].length() > 0) || params[3].trim().equalsIgnoreCase("true");
		HzImage image = null;
		String windowName = name;
		// picture "x","clipboard": what Copy Picture copied (HzPaint), named as the script says
		if (source.equalsIgnoreCase("clipboard") && HzPaint.clipboard != null) {
			HzImage c = HzPaint.clipboard;
			image = new HzImage(c.width, c.height, c.argb.clone());
			for (int i = 0; i < image.argb.length; i++) if ((image.argb[i] >>> 24) < 128) image.argb[i] = 0xFFFFFFFF;
		}
		if (st != null && image == null && (source.isEmpty() || source.equalsIgnoreCase("file"))) {
			String path = name.replace(':', '/');
			while (path.startsWith("/")) path = path.substring(1);
			byte[] b = HzImages.bytes(st, path);
			// a file beside the stack, as the desktop's WindowUI (Favorite Story's ST button: picture "ミーユ",file,rect,false
			// is a PICT file next to the stack; only the files inside the .hzb were looked at: "no window ミーユ")
			if (b == null && st.file != null) {
				java.io.File around = stackBundleFolderAround(st.file);
				java.io.File beside = around == null ? null : new java.io.File(around, path);
				if (beside != null && !beside.isFile()) { java.io.File other = TTalk.expandShortHfsNames(beside); if (other != null) beside = other; }
				if (beside != null && beside.isFile()) try { b = Platform.get().readAllBytes(beside); } catch (java.io.IOException e) { b = null; }
			}
			if (b != null) image = PngDecoder.isPng(b) ? PngDecoder.decode(b) : HzPict.decode(b, HzImages.pictCanvas);
			String file = path.substring(path.lastIndexOf('/') + 1);
			if (!file.isEmpty()) windowName = file;
			if (image == null && source.isEmpty()) windowName = name;
		}
		if (st != null && st.rsrc != null && image == null && (source.isEmpty() || source.equalsIgnoreCase("resource"))) {
			// by name (the window keeps the name as the script wrote it), else by ID (named after the resource, or PICT ID n)
			Rsrc.rsrcClass named = st.rsrc.findByNameAll(name, "PICT");
			if (named != null) image = HzImages.resource(st, named.id, "PICT");
			else if (name.trim().matches("-?[0-9]+")) {
				int id = Integer.parseInt(name.trim());
				image = HzImages.resource(st, id, "PICT");
				String rsrcName = st.rsrc.getNameAll(id, "PICT");
				windowName = rsrcName != null && !rsrcName.isEmpty() ? rsrcName : "PICT ID " + id;
			}
		}
		if (image == null || image.width <= 0 || image.height <= 0) { result.theResult = "Couldn't display picture."; return result; }
		int frame = PictureWindows.pictureFrame(style);
		boolean bar = frame == CardOverlay.TITLE_BAR || frame == CardOverlay.WINDOID_BAR;
		PictureWindows.Pict p = new PictureWindows.Pict(windowName, image, false, frame, bar, bar ? windowName : "");
		// over the middle of the card window, as the desktop puts it
		p.left = (st.width - p.outerWidth()) / 2; p.top = (st.height - p.outerHeight()) / 2;
		p.visible = visible;
		PictureWindows.add(p);
		// openPicture before the next statement (HyperCard J-2.3)
		if (st.curCard != null) PictureWindows.pictureMessage("openPicture", windowName, st.curCard);
		return result;
	}
	@Override Result paletteCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if (params.length < 1 || params[0].length() == 0) throw new xTalkException("palette requires a palette name");
		result.ret = 0;
		java.awt.Point at = params.length >= 2 ? TUtil.parsePaletteLocation(params[1]) : null;
		PictureWindows.Pict open = PictureWindows.palette(params[0]);
		if (open != null) {
			if (at != null) open.moveTo(at.x, at.y);
			open.setVisible(true);
			return result;
		}
		OStack st = HZ.stack();
		if (st == null || st.rsrc == null) return result;
		for (Rsrc.PlteClass plte : st.rsrc.plteList) {
			if (plte.name == null || !plte.name.equalsIgnoreCase(params[0])) continue;
			HzImage image = HzImages.resource(st, plte.pictId, "PICT");
			if (image == null) image = HzImages.resource(st, plte.id, "PICT");
			if (image == null) { result.theResult = "Error: PICT not found."; return result; }
			PictureWindows.Pict p = PictureWindows.newPalette(plte, PictureWindows.normalized(image, plte.pictHV));
			// card coordinates; HyperCard's 10,20 without
			p.left = at != null ? at.x : 10; p.top = at != null ? at.y : 20;
			p.visible = true;
			PictureWindows.add(p);
			if (st.curCard != null) TTalk.CallMessage("openPalette", new String[] {params[0], Integer.toString(p.window.id)}, st.curCard);
			return result;
		}
		// palette "Navigator": HyperCard's own PLTE 128 for stacks without one (WindowUI; Colorizing HyperCard's Disable and
		// Enable Demo opened nothing here)
		if (params[0].trim().equalsIgnoreCase(HcNavigatorPalette.NAME)) {
			Rsrc.PlteClass plte = HcNavigatorPalette.plte(st.rsrc);
			PictureWindows.Pict p = PictureWindows.newPalette(plte, new HzImage(HcNavigatorPalette.WIDTH, HcNavigatorPalette.HEIGHT, HcNavigatorPalette.argb()));
			p.left = at != null ? at.x : 10; p.top = at != null ? at.y : 20;
			p.visible = true;
			PictureWindows.add(p);
			if (st.curCard != null) TTalk.CallMessage("openPalette", new String[] {HcNavigatorPalette.NAME, Integer.toString(p.window.id)}, st.curCard);
		}
		return result;
	}
	private static PictureWindows.Pict pict(OObject obj) { return obj instanceof OWindow w ? w.pict : null; }
	/** type "…" (KeysUI.typeIntoViews on the desktop): into the open field at its selection, a backspace (numToChar(8)) deleting
	 *  as the Delete key does; with no field open the message box takes it when it shows, over its selected text; else the
	 *  letters are lost (HC J-2.3). With shiftKey in capitals. HyperPiece 2's card 75 typed nothing into its field here. */
	@Override boolean typeIntoViews(String text, int[] modKeys, OField f) throws xTalkException {
		boolean shift = false;
		for (int k : modKeys) if (k == 11) shift = true;
		String typed = shift ? text.toUpperCase() : text;
		// the text tool's insertion point that click at put on the card: the letters are painted (HzPaint)
		if (HzPaint.typing()) { HzPaint.type(typed); return true; }
		// Delete with a paint selection: the selected picture goes, as Clear Picture (HyperPiece 2 card 75's ショートカット
		// selects with the select tool and types numToChar(8); it took a character off the message box)
		if (HzPaint.active() && HzPaint.floating != null && !typed.isEmpty() && typed.chars().allMatch(c -> c == 8 || c == 127)) {
			HzPaint.menu("clear picture");
			loop.markDirty();
			return true;
		}
		if (f != null && !f.enabled) {
			int from = 0;
			for (int i = 0; i <= typed.length(); i++) {
				if (i < typed.length() && typed.charAt(i) != '\b') continue;
				if (i > from) TTalk.insertAtCaret(f, typed.substring(from, i));
				if (i < typed.length()) TTalk.deleteBeforeCaret(f);
				from = i + 1;
			}
			loop.markDirty();
			return true;
		}
		if (msgVisible()) {
			StringBuilder t = new StringBuilder(msgText());
			int s0 = msgSelectionStart(), s1 = msgSelectionEnd();
			for (int i = 0; i < typed.length(); i++) {
				char c = typed.charAt(i);
				if (c == '\b') { if (s1 == s0 && s0 > 0) s0--; t.delete(s0, s1); s1 = s0; continue; }
				t.replace(s0, s1, String.valueOf(c)); s0 = s1 = s0 + 1;
			}
			setMsgText(t.toString());
			msgSetCaret(s0);
			return true;
		}
		return false;
	}

	/** choose … tool: the selection and the text being painted go into the picture first (HzPaint) */
	@Override boolean chooseCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if (str != "choose") return false;
		HzPaint.toolChanged();
		boolean done = super.chooseCommand(self, stringList, typeAry, object, target, memData, result, str);
		loop.markDirty();
		return done;
	}
	/** the tool, in HyperCard's words ("rectangle tool", "select tool"…) */
	@Override boolean toolFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if (!message.equals("tool")) return super.toolFunction(message, params, target, memData, isFunc, result);
		result.theResult = HzPaint.toolName();
		return true;
	}

	/** drag from … to … with the button or field tool (WindowUI.authToolDrag on the desktop): with commandKey a new transparent
	 *  part over that rectangle (the end point's pixel in it) and newButton/newField to it; else the part at the start moves by
	 *  the drag. HyperPiece 2's card 20 makes its rows of buttons this way and then sets their style: "Cannot identify the
	 *  object" here, where the drag made nothing. */
	@Override boolean authToolDrag(TTalk self, int sx, int sy, int ex, int ey, int[] modKeys, MemoryData memData) throws xTalkException {
		OStack st = HZ.stack();
		if (st == null || st.curCard == null) return false;
		String tool = HeadlessProps.get("tool");
		tool = tool == null ? "" : tool.trim();
		boolean buttons = tool.equals("button tool") || tool.equals("button") || tool.equals("btn tool");
		if (!buttons && !tool.equals("field tool") && !tool.equals("field") && !tool.equals("fld tool")) return false;
		if (TTalk.commandKeyOnly(modKeys)) {
			OCardBase cdbase = HZ.editBackground ? st.curCard.bg : st.curCard;
			int newid = cdbase.newPartId();
			int l = Math.min(sx, ex), t = Math.min(sy, ey), r = Math.max(sx, ex) + 1, b = Math.max(sy, ey) + 1;
			OObject part;
			if (buttons) {
				OButton obtn = new OButton(cdbase, newid);
				cdbase.partsList.add(obtn); cdbase.btnList.add(obtn);
				obtn.setRect(l, t, r, b);
				obtn.style = 1; // transparent, as the command-drag of the mouse makes it
				part = obtn;
			} else {
				OField ofld = new OField(cdbase, newid);
				cdbase.partsList.add(ofld); cdbase.fldList.add(ofld);
				ofld.setRect(l, t, r, b);
				ofld.style = 1;
				ofld.enabled = false;
				part = ofld;
			}
			cdbase.changed = true;
			OCard.reloadCurrentCard();
			loop.markDirty();
			self.ReceiveMessage(buttons ? "newButton" : "newField", new String[0], part, part, memData);
			return true;
		}
		OCard cd = st.curCard;
		OObject part = null;
		for (OCardBase layer : new OCardBase[] {cd, cd.bg}) {
			if (layer == null || layer.partsList == null || part != null) continue;
			for (int i = layer.partsList.size() - 1; i >= 0 && part == null; i--) {
				OObject p = layer.partsList.get(i);
				if (buttons ? !(p instanceof OButton) : !(p instanceof OField)) continue;
				if (p.isShown() && sx >= p.left && sy >= p.top && sx < p.left + p.width && sy < p.top + p.height) part = p;
			}
		}
		if (part != null) {
			int l = part.left + ex - sx, t = part.top + ey - sy;
			if (part instanceof OButton pb) pb.setRect(l, t, l + part.width, t + part.height);
			else ((OField) part).setRect(l, t, l + part.width, t + part.height);
			if (part.parent instanceof OCardBase pc) pc.changed = true;
			OCard.reloadCurrentCard();
			loop.markDirty();
		}
		return true;
	}

	/** The card window's place: the card is the screen here (the screenRect is the card's, the window at 0,0 - the 68K VM's
	 *  windows go by it), until a script moves the window; the page then shows the card that far from its place
	 *  (cardWindowOffset), and the card window centred in the screenRect is in its place again */
	private int[] cardLoc;
	private java.awt.Rectangle centredCard() {
		OStack s = HZ.stack();
		return new java.awt.Rectangle(0, 0, s == null ? 512 : s.width, s == null ? 342 : s.height);
	}

	@Override java.awt.Rectangle cardWindowRect() {
		java.awt.Rectangle c = centredCard();
		if (cardLoc != null) c.setLocation(cardLoc[0], cardLoc[1]);
		return c;
	}
	/** set the loc / rect / left … of the card window (WindowUI.cardWindowProperty on the desktop) */
	@Override boolean cardWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		return obj instanceof OWindow w && isCardWindow(w) && TUtil.setCardWindowProperty(prpty, value);
	}
	@Override void setCardWindowRect(java.awt.Rectangle r) {
		java.awt.Rectangle c = centredCard();
		cardLoc = new int[] {r.x, r.y};
		loop.host.post("cardWindowOffset", (r.x - c.x) + "," + (r.y - c.y));
	}

	/** the windows (TUtil.windowEntries; WindowUI's on the desktop): the pictures and movies on show first, the message box,
	 *  the palettes, the hidden pictures, then the card window; front to back in each. HyperPiece 2 opens its palette again
	 *  at every click on the card when "HyperPieceNav" is not in the windows: it was always empty here, so the palette
	 *  jumped back to its first place. */
	@Override java.util.List<Object[]> windowEntries() {
		java.util.List<Object[]> shown = new java.util.ArrayList<>(), palettes = new java.util.ArrayList<>(), hidden = new java.util.ArrayList<>();
		java.util.List<PictureWindows.Pict> all;
		synchronized (PictureWindows.list) { all = new java.util.ArrayList<>(PictureWindows.list); }
		for (int i = all.size() - 1; i >= 0; i--) {
			PictureWindows.Pict p = all.get(i);
			if (p.window == null) continue;
			String name = p.window.name != null && !p.window.name.isEmpty() ? p.window.name : p.name;
			(p.palette ? palettes : p.visible ? shown : hidden).add(new Object[] {name, p.window});
		}
		java.util.List<Object[]> list = new java.util.ArrayList<>(shown);
		list.add(new Object[] {"Message", OMsg.msg});
		list.addAll(palettes);
		list.addAll(hidden);
		OStack st = HZ.stack();
		if (st != null && OWindow.cdwindow != null) list.add(new Object[] {st.getWindowTitle(), OWindow.cdwindow});
		// the stacks left open behind it (go … in a new window), as the desktop lists their windows
		for (String name : TTalk.windowStackNames()) list.add(new Object[] {name, null});
		return list;
	}
	/** The windows of the stacks left open behind the card window: the page shows only the card window, so they are on show
	 *  until a script hides one (hide window "Home"), and come back shown with the stack */
	private final java.util.Set<String> hiddenStackWindows = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
	@Override boolean gStackWindowIsShown(String key) {
		synchronized (TTalk.windowStacks) { if (!TTalk.windowStacks.containsKey(key)) { hiddenStackWindows.remove(key); return false; } }
		return !hiddenStackWindows.contains(key);
	}
	@Override void gStackWindowSetShown(String key, boolean shown) { if (shown) hiddenStackWindows.remove(key); else hiddenStackWindows.add(key); }
	@Override OWindow findPaletteWindow(String name) { PictureWindows.Pict p = PictureWindows.palette(name); return p == null ? null : p.window; }
	@Override boolean isPicture(OWindow self) { return self.pict != null && !self.pict.palette && self.pict.movie == null; }

	// ---- HyperCard 2.4's Movie XCMD windows (MovieWindows; TQTMovie on the desktop)
	@Override boolean tQTMovieAvailable() { return MovieWindows.available(); }
	@Override void tQTMovieSetCurrentTime(String handle, long time) { MovieWindows.setCurrentTime(MovieWindows.of(handle), time); }
	@Override int tQTMovieRate(String handle) { MovieWindows.Playback pb = MovieWindows.of(handle); return pb != null && pb.playing ? 1 : 0; }
	@Override String tQTMovieProperty(String handle, String property) { return MovieWindows.property(MovieWindows.of(handle), property); }
	@Override java.util.ArrayList<OWindow> tQTMovieScriptWindows() { return MovieWindows.scriptWindows(); }
	@Override OWindow tQTMovieFindScriptWindow(String ref) { return MovieWindows.findScriptWindow(ref); }
	@Override void tQTMovieSetVolume(String handle, int volume) { MovieWindows.setVolume(MovieWindows.of(handle), volume); }
	@Override void tQTMovieDispose(String handle) { MovieWindows.dispose(MovieWindows.of(handle), true); }
	/** set the loop / rate / soundVolume of a movie, and the ones accepted with no effect (WindowUI.movieWindowProperty) */
	@Override boolean movieWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		MovieWindows.Playback pb = obj instanceof OWindow w ? MovieWindows.of(w) : null;
		if (pb == null) return super.movieWindowProperty(obj, inProperty, value, prpty);
		switch (prpty) {
			case "loop": pb.loop = TUtil.propertyBoolean("loop", value); return true;
			case "rate":
				double r;
				try { r = Double.parseDouble(value.trim()); } catch (NumberFormatException e) { throw new xTalkException("A number is required here"); }
				MovieWindows.setRate(pb, r); return true;
			case "soundvolume": case "volume": MovieWindows.setVolume(pb, TUtil.propertyInteger("soundVolume", value)); return true;
			case "loopispalindrome": case "timeformat": case "sendmousewithinmsg": case "closeonfinish": case "dragenabled": case "badge":
			case "fastidle": case "callbackmsg": case "passmovieclick": case "donterase": case "controllervisible": case "playselection":
			case "selectionstart": case "selectionend": case "highlight": return true; // accepted, no effect (no controller bar here)
		}
		return false;
	}
	/** send "play" / "pause" / "rewind" / "stepFwd" / "stepRev" / "idle" to movie N */
	@Override void Command(OWindow self, String cmdStr) throws xTalkException {
		MovieWindows.Playback pb = MovieWindows.of(self);
		if (pb == null) { super.Command(self, cmdStr); return; }
		String c = cmdStr.trim();
		int sp = c.indexOf(' ');
		switch ((sp < 0 ? c : c.substring(0, sp)).toLowerCase(java.util.Locale.ROOT)) {
			case "play": MovieWindows.play(pb); break;
			case "pause": MovieWindows.pause(pb); break;
			case "rewind": MovieWindows.setCurrentTime(pb, 0); break;
			case "stepfwd": MovieWindows.step(pb, 1); break;
			case "steprev": MovieWindows.step(pb, -1); break;
			default: break; // idle (the player moves it on), findText, nudge…: the movie shows without them
		}
	}
	/** the stack left is written first (PlayerSaving.beforeLeave; XMLwrite on the desktop) */
	@Override boolean savedBeforeLeave(OStack s) { PlayerSaving.beforeLeave(loop.host, s); return true; }
	@Override boolean windowSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		// hide/show card window (Window Tools' Show/hide windows: window "Window Tools 1.6.1"): the page hides the card, the menu
		// bar and the message box stay; the visible of card window follows (it was always true and the card stayed)
		if (obj instanceof OWindow w && isCardWindow(w)) {
			boolean shown = TUtil.propertyBoolean("visible", value);
			w.setVisible(shown);
			loop.host.post("cardWindowVisible", String.valueOf(shown));
			return true;
		}
		PictureWindows.Pict p = pict(obj);
		if (p == null) return super.windowSetVisible(obj, inProperty, value, prpty);
		p.setVisible(value.trim().equalsIgnoreCase("true"));
		return true;
	}
	@Override boolean windowSetLoc(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null) return super.windowSetLoc(obj, inProperty, value, prpty, btn, fld, cd, v1, v2);
		p.moveTo(v1, v2); // the loc of a window is its top-left (WindowUI.windowSetLoc)
		return true;
	}
	@Override boolean windowSetTopLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null) return super.windowSetTopLeft(obj, inProperty, value, prpty, btn, fld, cd, v1, v2);
		p.moveTo(v1, v2);
		return true;
	}
	@Override boolean windowSetLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null) return super.windowSetLeft(obj, inProperty, value, prpty, btn, fld, cd, v);
		p.moveTo(v, p.top);
		return true;
	}
	@Override boolean windowSetTop(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null) return super.windowSetTop(obj, inProperty, value, prpty, btn, fld, cd, v);
		p.moveTo(p.left, v);
		return true;
	}
	@Override boolean windowSetRect(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2, int v3, int v4) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null) return super.windowSetRect(obj, inProperty, value, prpty, btn, fld, cd, v1, v2, v3, v4);
		p.setRect(v1, v2, v3, v4);
		return true;
	}
	@Override boolean paletteWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null || !p.palette || !prpty.equals("hilitedbutton")) return super.paletteWindowProperty(obj, inProperty, value, prpty);
		p.setHilited(value.trim().isEmpty() ? 0 : TUtil.propertyInteger("hilitedButton", value));
		return true;
	}
	/** set the globalLoc / globalRect of a picture window: the card is the player's screen (a word, as card or mainScreen: in
	 *  its middle, as HyperCard J-2.3 centres it on the screen) */
	@Override boolean pictureWindowGlobalProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		PictureWindows.Pict p = pict(obj);
		if (p == null || !(prpty.equals("globalloc") || prpty.equals("globalrect"))) return super.pictureWindowGlobalProperty(obj, inProperty, value, prpty);
		String word = value.trim().toLowerCase(java.util.Locale.ROOT);
		OStack st = HZ.stack();
		if (prpty.equals("globalloc") && word.matches("[a-z]+")) {
			if (!word.equals("screen") && st != null) p.moveTo((st.width - p.outerWidth()) / 2, (st.height - p.outerHeight()) / 2);
			return true;
		}
		int[] v = TUtil.coordinates(value, prpty.equals("globalloc") ? 2 : 4, prpty.equals("globalloc") ? "Coordinates are required here" : "A rectangle is required here");
		if (prpty.equals("globalloc")) p.moveTo(v[0], v[1]); else p.setRect(v[0], v[1], v[2], v[3]);
		return true;
	}
	@Override void Close(OWindow self) {
		MovieWindows.Playback pb = MovieWindows.of(self);
		if (pb != null) { MovieWindows.dispose(pb, true); return; }
		if (self.pict == null) { super.Close(self); return; }
		OStack st = HZ.stack();
		PictureWindows.close(self.pict, st == null ? null : st.curCard);
	}

	// the externals the player answers itself: AddColor (HzAddColor, as the desktop's built-in one, which it uses even when
	// the stack carries Apple's), and KStealKeyX when the stack's has no code the player runs (HzKStealKeyX, as the desktop's).
	// HyperZebra's other built-in stand-ins are not for the player: externals are the stack's code, HyperCard's own Picture
	// and Palette XCMDs aside (above)
	@Override OStack tXcmdExternalOwner(String name, String kind) {
		if ("addcolor".equalsIgnoreCase(name)) return HZ.stack();
		return carrier(name, kind);
	}

	/** The stack that carries an external of that name (the open stack, a stack in use, Home, the stand-alone application:
	 *  TXcmd.externalOwner), or null */
	static OStack carrier(String name, String kind) {
		OStack st = HZ.stack();
		if (st == null || name == null) return null;
		if (st.standaloneApplication) application = st;
		if (st.rsrc != null && st.rsrc.hasXcmd(name, kind)) return st;
		if (st.usingStacks != null) for (OStack u : st.usingStacks) if (u != null && u.rsrc != null && u.rsrc.hasXcmd(name, kind)) return u;
		if (HZ.home != null && HZ.home.rsrc != null && HZ.home.rsrc.hasXcmd(name, kind)) return HZ.home;
		// the stand-alone application stack the page opened (HyperCard's code with the stack: TXcmd.applicationOpened): it is
		// the application, so its externals stay below Home once its scripts go to other stacks. Cosmic Osmo's PlayMovie,
		// xMemory and SetMode are in "Cosmic Osmo" alone and are called from CO.Intro, CO.1, …
		OStack app = application;
		if (app != null && app != st) {
			Rsrc r = app.rsrc != null ? app.rsrc : app.closedResources;
			if (r != null && r.hasXcmd(name, kind)) return app;
		}
		return null;
	}
	/** The stand-alone application stack (OStack.standaloneApplication) seen as the current stack, or null */
	private static volatile OStack application;

	/** The running handler's own stack, outside the current card's hierarchy (also once it was left): whether it carries that 68K
	 *  external, which answers as the message passes the stack, as on the desktop (TXcmd.has68kExternal). Import/Export Tools'
	 *  background script goes to the stack it imports into and calls its own REPLACE XFCN there ("Cannot interpret replace") */
	@Override boolean tXcmdHas68kExternal(OStack stack, String name, String kind) {
		if (Externals68k.Runner.current == null || stack == null || name == null) return false;
		Rsrc r = stack.rsrc != null ? stack.rsrc : stack.closedResources;
		Rsrc.xcmdClass x = r == null ? null : r.getXcmd(name, kind);
		return x != null && x.platform != null && x.platform.toLowerCase(java.util.Locale.ROOT).contains("68k");
	}
	/** That stack's own external runs (TXcmd.CallExternalCommandOf) */
	@Override Result tXcmdCallExternalCommandOf(OStack stack, String name, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		Externals68k vm = Externals68k.Runner.current;
		result.ret = 0;
		if (vm != null && call68k(vm, stack, name, params, isFunc, result, target, memData)) return result;
		return tXcmdCallExternalCommand(name, params, target, memData, isFunc, result);
	}

	/** The target of the message whose handler called the running external, as TXcmd.callTarget on the desktop: its EvalExpr and
	 *  SendCardMessage see it as "the target" (ScrollControl XCMD reads "the rect of the target" for its scroll bar: the card's
	 *  rect made the bar fill the card and never let go) */
	private static final ThreadLocal<OObject> callTarget = new ThreadLocal<OObject>();
	@Override OObject externalCallTarget() { return callTarget.get(); }
	/** vm.call with the calling handler's target and variables set, as TXcmd.CallExternalCommand does (EvalExpr is evaluated in
	 *  that handler: `me`, its locals) */
	private static boolean call68k(Externals68k vm, OStack owner, String name, String[] params, boolean isFunc, Result result, OObject target, MemoryData memData) throws xTalkException {
		OObject saved = callTarget.get();
		MemoryData savedVariables = HZ.callVariables.get();
		callTarget.set(target);
		HZ.callVariables.set(memData);
		try { return vm.call(owner, name, params, isFunc, result); }
		finally { callTarget.set(saved); HZ.callVariables.set(savedVariables); }
	}

	/** The externals the stacks carry that the browser cannot run (68K and PowerPC code), told once each */
	private final java.util.Set<String> notRun = new java.util.HashSet<>();
	@Override Result tXcmdCallExternalCommand(String name, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if ("addcolor".equalsIgnoreCase(name)) {
			HzAddColor.call(params, target, loop.addColorScreen);
			result.ret = 0;
			return result;
		}
		OStack owner = carrier(name, isFunc ? "function" : "command");
		if (owner == null && !isFunc && "movie".equalsIgnoreCase(name)) {
			// HyperCard 2.4's own Movie XCMD (the stack's own runs as its 68K code, above): the 68K player's movies; the core
			// player has no decoders, so it does nothing there
			if (MovieWindows.available()) result.theResult = MovieWindows.open(params, HZ.stack());
			else { if (notRun.add("movie")) System.out.println("Movie: this player has no QuickTime (the 68K player has)"); result.theResult = ""; }
			result.ret = 0;
			return result;
		}
		if (owner == null) return super.tXcmdCallExternalCommand(name, params, target, memData, isFunc, result);
		Externals68k vm = Externals68k.Runner.current;
		if (vm != null && call68k(vm, owner, name, params, isFunc, result, target, memData)) return result; // the 68K player
		if ("kstealkeyx".equalsIgnoreCase(name)) { // the desktop's built-in one (Mosslight RPG Engine's has no code)
			result.theResult = HzKStealKeyX.answer(params.length > 0 ? params[0] : "");
			result.ret = 0;
			return result;
		}
		// the stack has it, but its code is 68K or PowerPC, which the browser does not run yet: it does nothing and gives empty,
		// so the handler goes on (without it, "can't understand" stopped MacToons-J's openStack at HideDeskTop)
		if (notRun.add(name.toLowerCase())) System.out.println("External " + name + " of the stack is not run in the browser (68K/PowerPC code)");
		result.theResult = "";
		result.ret = 0;
		return result;
	}

	// play, play stop, the sound (PlayerSound: TSound's rules, the samples played by the host)
	@Override boolean playNotes(OStack s, String sound, String[] notes, int tempo, int volume) throws xTalkException { return loop.sound.play(s != null ? s : HZ.stack(), sound, notes, tempo, volume); }
	@Override void playStop() { loop.sound.stop(); }
	@Override String soundLastError() { return loop.sound.lastError; }
	@Override boolean soundFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		// (UiHost's answers any property HeadlessProps keeps: "the tool" is toolFunction's, in HyperCard's words)
		if ("tool".equals(message)) return false;
		if (!"sound".equals(message)) return super.soundFunction(message, params, target, memData, isFunc, result);
		result.theResult = loop.sound.current();
		return true;
	}
	/** put into the message box: the text, and the box shows (GMsg.setText) */
	@Override void setMsgText(String text) {
		super.setMsgText(text); loop.host.post("msg", text == null ? "" : text);
		if (!msgVisible()) setMsgVisible(true);
	}
	/** show/hide msg (TUtil's visible of the message box): the page's box shows or hides (UiHost's default only took the call) */
	@Override boolean windowsSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if ("msg".equals(obj.objectType)) { setMsgVisible(TUtil.propertyBoolean("visible", value)); return true; }
		return super.windowsSetVisible(obj, inProperty, value, prpty);
	}
	@Override void setMsgVisible(boolean shown) {
		boolean was = msgVisible();
		super.setMsgVisible(shown); loop.host.post("msgVisible", Boolean.toString(shown));
		// hidden, it is drawn afresh when it shows again: what 68K externals drew in it goes (GMsg's CardWindow68k.messageBoxHidden)
		if (was && !shown) { Externals68k x = Externals68k.Runner.current; if (x != null) x.messageBoxHidden(); }
	}
	// ---- a 68K external's input (P4-3)
	@Override int cardMousePresses() { return PlayerInput.presses; }
	@Override boolean cardMouseDown() { return PlayerInput.mouseDown(); }
	@Override void externalPoll() { loop.tick(); }
	@Override void abortScriptAfterExternal() { abortAfterExternal = true; }
	/** AbortScript was called by the external running now. */
	static volatile boolean abortAfterExternal;
}

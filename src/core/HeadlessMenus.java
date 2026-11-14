/**
 * doMenu without the desktop (the headless core and the browser): the item is looked up in the core's menus as on the
 * desktop (TTalk.findMenu / findMenuItemIndex over MenuNodes), also by HyperCard J's name (JapaneseMenuNames: the player's
 * menus are English); an item a script made runs its menu message; of HyperCard's own items the Go menu's moves are done,
 * and the authoring items scripts use while they run (New Card, New Field, New Button, Delete Card, Copy/Cut/Paste Card,
 * Delete/Clear Field and Button on the part select chose: GMenu.doMenu's); the others do nothing (the desktop's GMenu.doMenu
 * has them all).
 */
final class HeadlessMenus {
	private HeadlessMenus() {}

	/** the menu doMenu named (the page sends the title it was chosen from): Tool > Select chooses the tool, Paint > Select
	 *  selects the picture */
	private static String menuTitle;

	static void doMenu(String item, String menuName) throws xTalkException {
		if (item == null) return;
		menuTitle = menuName == null ? null : JapaneseMenuNames.english(menuName) != null ? JapaneseMenuNames.english(menuName) : menuName;
		try { doMenuIn(item, menuName); } finally { menuTitle = null; }
	}

	private static void doMenuIn(String item, String menuName) throws xTalkException {
		MMenu menu = menuName != null && !menuName.isEmpty() ? TTalk.findMenu(menuName) : null;
		if (menu == null && menuName != null && !menuName.isEmpty() && !TTalk.isHyperCardOnlyMenu(menuName)) throw new xTalkException("No such menu: {0}", menuName);
		java.util.List<MMenu> menus = new java.util.ArrayList<MMenu>();
		if (menu != null) menus.add(menu);
		else { menus.addAll(TTalk.menuList()); menus.add(TTalk.appleMenu); }
		String english = JapaneseMenuNames.english(item);
		for (String name : english == null ? new String[] {item} : new String[] {item, english}) {
			for (MMenu m : menus) {
				int idx = TTalk.findMenuItemIndex(m, name);
				if (idx < 0) continue;
				MComponent c = m.getPopupMenu().getComponent(idx);
				if (!(c instanceof MMenuItem)) continue;
				MMenuItem mi = (MMenuItem) c;
				Object msg = mi.getClientProperty("menuMessage");
				if (msg != null && msg.toString().length() > 0) { TTalk.doScriptforMenu(msg.toString()); return; }
				// (a disabled item: its paint command still runs with a paint tool, as HyperCard's paint menus have it enabled)
				if (!mi.isEnabled()) {
					String t = mi.getText().trim().replace("...", "…");
					// (the items that are on or off by the state look for it themselves: a script's doMenu "Copy" right after its
					// select comes before the menu's marks follow)
					if (FOLLOWS_STATE.contains((intl() != null ? intl().getEngText(t) : t).trim().replace("...", "…"))) { command(t); return; }
					// (the copy and paste of parts look for the selection themselves: the player's Edit menu does not follow it)
					if (t.toLowerCase(java.util.Locale.ROOT).matches("(copy|cut|paste) (button|field|card)")) command(t); else paintCommand(t);
					return;
				}
				command(mi.getText());
				return;
			}
		}
		command(english != null ? english : item);
	}

	private static International intl() { return HZ.intl(); }
	/** the items checks() turns on and off */
	private static final java.util.Set<String> FOLLOWS_STATE = java.util.Set.of("Cut", "Copy", "Paste", "Delete", "Clear",
		"Bring Closer", "Send Farther", "Button Info…", "Field Info…", "Close Stack", "Next Window"); // (Next Window is greyed until a window is up: HyperCard's doMenu does it anyway)

	/** One of HyperCard's own menu commands by name (doMenu "Next"). */
	static void command(String name) throws xTalkException {
		International intl = HZ.intl();
		String eng = (intl != null ? intl.getEngText(name) : name).trim().replace("...", "…");
		OStack st = HZ.stack();
		// HyperCard's Edit menu names its items Cut Text … Clear Text while text is selected (HyperWrite: domenu "copy text")
		if (eng.toLowerCase(java.util.Locale.ROOT).matches("(cut|copy|paste|clear|delete) text")) eng = eng.substring(0, eng.length() - 5).trim();
		// HyperCard's Style menu on the selected text (doMenu "Bold")
		if (TUtil.styleSelectedText(eng)) { if (PlayerLoop.current != null) PlayerLoop.current.markDirty(); return; }
		switch (eng.toLowerCase(java.util.Locale.ROOT)) {
			case "save a copy…": // the stack with its changes, as an .hzb the page downloads (PlayerSaving)
				if (PlayerLoop.current != null) PlayerSaving.saveACopy(PlayerLoop.current.host, st);
				break;
			// the stack on show closes and the one open behind comes back (close card window; GMenu's Close Stack). Window Tools
			// Option-chooses an open stack: go to stack, doMenu "Close stack", pop card. With no other stack it stays open
			case "close stack": if (TTalk.hasWindowStacks()) TTalk.doScriptforMenu("close card window"); break;
			case "next": TTalk.doScriptforMenu("go next"); break;
			// Go > Next Window: a 68K external's backdrop round the card goes behind it (Myst Preview: xWindowFrame, then doMenu "next window")
			case "next window": { Externals68k x = Externals68k.Runner.current; if (x != null) x.nextWindow(); break; }
			case "prev": case "previous": TTalk.doScriptforMenu("go prev"); break;
			case "first": TTalk.doScriptforMenu("go first"); break;
			case "last": TTalk.doScriptforMenu("go last"); break;
			case "back": TTalk.doScriptforMenu("go back"); break;
			case "home": TTalk.doScriptforMenu("go home"); break;
			// the parts a script makes and removes while it runs (HyperCard J's 新規フィールド / 消去 フィールド: keiba, HyperEmblem)
			case "new field": TTalk.doScriptforMenu("choose field tool"); TTalk.doScriptforMenu("create field"); break;
			case "new button": TTalk.doScriptforMenu("choose button tool"); TTalk.doScriptforMenu("create button"); break;
			case "delete field": case "clear field": deletePart(OField.class); break;
			case "delete button": case "clear button": deletePart(OButton.class); break;
			// the part select chose, copied (GMenu's Copy / Cut / Paste Button and Field: the part's XML, here in memory)
			case "copy button": copyPart(OButton.class, false); break;
			case "cut button": copyPart(OButton.class, true); break;
			case "copy field": copyPart(OField.class, false); break;
			case "cut field": copyPart(OField.class, true); break;
			case "paste button": pastePart(true); break;
			case "paste field": pastePart(false); break;
			case "new card": if (st != null && st.curCard != null) newCard(st); break;
			// the Edit menu's Cut / Copy / Paste / Delete: the picture with a paint tool, the selected part with the button or
			// field tool (HyperCard's Copy Button…), else the open field's text (UiHost.editSelectedText)
			case "cut": case "copy": case "paste": case "delete": case "clear": {
				String c = eng.toLowerCase(java.util.Locale.ROOT);
				if (HzPaint.active()) { paintCommand(c.equals("delete") ? "clear" : c); break; }
				if (HzAuthTool.active()) {
					String kind = HzPaint.tool();
					if (c.equals("paste")) pastePart(kind.equals("button"));
					else if (HzAuthTool.selection() != null) {
						Class<?> k = kind.equals("button") ? OButton.class : OField.class;
						if (c.equals("copy")) copyPart(k, false); else if (c.equals("cut")) copyPart(k, true); else deletePart(k);
					}
					break;
				}
				UiHost.get().editSelectedText(c.equals("clear") ? "delete" : c);
				break;
			}
			// Bring Closer / Send Farther: the selected part one place up or down in the stacking (GMenu's)
			case "bring closer": case "send farther": {
				OObject part = HzAuthTool.selection();
				if (part != null && part.parent instanceof OCardBase owner) {
					owner.stepInStack(part, eng.toLowerCase(java.util.Locale.ROOT).startsWith("bring"));
					owner.changed = true;
					UiHost.get().repaintCardPane();
				}
				break;
			}
			// Go > Message shows or hides the message box; Find… shows it with find "" and the insertion point between the quotes
			case "message": TTalk.doScriptforMenu(UiHost.get().msgVisible() ? "hide msg" : "show msg"); break;
			case "find…": UiHost.get().showFind(); break;
			// Button Info / Field Info on the part selected (PartInfo)
			case "button info…": case "field info…": {
				boolean button = eng.toLowerCase(java.util.Locale.ROOT).startsWith("button");
				OObject part = HzAuthTool.selection();
				if (part == null) part = selected;
				if ((button ? part instanceof OButton : part instanceof OField) && PlayerLoop.current != null) PartInfo.open(PlayerLoop.current.host, part);
				break;
			}
			// the Tool menu (GMenu.doMenu's choose … tool); Select with a paint tool is the Paint menu's (paintCommand)
			case "browse": TTalk.doScriptforMenu("choose browse tool"); break;
			case "button": TTalk.doScriptforMenu("choose button tool"); break;
			case "field": TTalk.doScriptforMenu("choose field tool"); break;
			case "select": if (HzPaint.active() && !"tool".equalsIgnoreCase(menuTitle)) paintCommand(eng); else TTalk.doScriptforMenu("choose select tool"); break;
			case "lasso": TTalk.doScriptforMenu("choose lasso tool"); break;
			case "pencil": TTalk.doScriptforMenu("choose pencil tool"); break;
			case "brush": TTalk.doScriptforMenu("choose brush tool"); break;
			case "eraser": TTalk.doScriptforMenu("choose eraser tool"); break;
			case "line": TTalk.doScriptforMenu("choose line tool"); break;
			case "spraycan": TTalk.doScriptforMenu("choose spray can tool"); break;
			case "rect": TTalk.doScriptforMenu("choose rect tool"); break;
			case "roundrect": TTalk.doScriptforMenu("choose round rect tool"); break;
			case "paintbucket": TTalk.doScriptforMenu("choose bucket tool"); break;
			case "oval": TTalk.doScriptforMenu("choose oval tool"); break;
			case "curve": TTalk.doScriptforMenu("choose curve tool"); break;
			case "type": TTalk.doScriptforMenu("choose text tool"); break;
			case "polygon": TTalk.doScriptforMenu("choose reg poly tool"); break;
			case "freepolygon": TTalk.doScriptforMenu("choose poly tool"); break;
			// the Edit menu's Background (editBkgnd), the paint Options menu's Grid Snap / Draw Centered / Draw Filled (HzPaint)
			case "background": toggle("editbkgnd"); break;
			case "grid snap": toggle("grid"); break;
			case "draw centered": toggle("centered"); break;
			case "draw filled": toggle("filled"); break;
			case "delete card": if (st != null && st.curCard != null) deleteCard(st); break;
			// (Train Set's New Card: Copy Card on its first card, Paste Card after this one, then it clears the pasted card's
			// track; with these doing nothing it cleared the track on show)
			case "copy card": if (st != null && st.curCard != null) copyCard(st.curCard); break;
			case "cut card": if (st != null && st.curCard != null) cutCard(st); break;
			case "paste card": if (st != null && st.curCard != null) pasteCard(st); break;
			default: paintCommand(eng); break;
		}
	}

	/** The Paint menu's commands (HzPaint.menu): the picture ones (Copy Picture, Clear Picture…) always, the rest (Select All,
	 *  Invert, Undo…) and the Edit menu's Copy / Cut / Paste / Clear while a paint tool is chosen, as HyperCard's paint menus */
	private static void paintCommand(String eng) {
		String it = eng.toLowerCase(java.util.Locale.ROOT);
		// the paint menu bar's Edit menu (Undo Paint and Redo Paint: HzPaint's undo, which redoes when chosen again)
		if (it.equals("undo paint") || it.equals("redo paint") || it.equals("redo")) it = "undo";
		if (it.equals("clear selection")) it = "clear picture";
		if (it.endsWith(" picture")) { HzPaint.menu(it); return; }
		// (Undo takes back the last painting also after the browse tool was chosen: the player has no other Undo)
		if (it.equals("undo")) { HzPaint.menu(it); return; }
		if (!HzPaint.active()) return;
		switch (it) {
			case "copy": case "cut": case "paste": case "clear": HzPaint.menu(it + " picture"); return;
			default: HzPaint.menu(it);
		}
	}

	/** The part select chose (UiHost.selectPart) or the button or field tool selected (HzAuthTool), for Delete Field /
	 *  Delete Button; null after they removed it */
	static OObject selected;

	/** set the property to not it (the menus' check items: their marks follow it, checks) */
	private static void toggle(String prop) throws xTalkException {
		boolean on = "true".equalsIgnoreCase(String.valueOf(HeadlessProps.get(prop)).trim()) || prop.equals("editbkgnd") && HZ.editBackground;
		TTalk.doScriptforMenu("set the " + prop + " to " + !on);
	}

	/** The check items' marks as the properties are now (Background, Grid Snap, Draw Centered, Draw Filled) */
	static void checks(MMenuBar bar) {
		if (bar == null) return;
		for (MMenu m : bar.menus()) {
			MPopupMenu pm = m.getPopupMenu();
			if (pm == null) continue;
			for (MComponent c : pm.getComponents()) {
				// Button Info… / Field Info…: on while the button or field tool has that part selected
				if (c instanceof MMenuItem it && !(c instanceof MMenu) && it.getClientProperty("menuMessage") == null) {
					International in = HZ.intl();
					String e = (in != null ? in.getEngText(it.getText()) : it.getText()).trim().replace("...", "…");
					if (e.equals("Button Info…")) it.setEnabled(HzAuthTool.selection() instanceof OButton);
					else if (e.equals("Field Info…")) it.setEnabled(HzAuthTool.selection() instanceof OField);
					else if (e.equals("Bring Closer") || e.equals("Send Farther")) it.setEnabled(HzAuthTool.selection() != null);
					// Close Stack: on while another stack is open behind (go … in a new window); the page has no launcher to go back to
					else if (e.equals("Close Stack")) it.setEnabled(TTalk.hasWindowStacks());
					else if (!m.isVisible() || !"Edit".equals(in != null ? in.getEngText(m.getText()) : m.getText()) || HzPaint.active()) {}
					else if (e.equals("Cut") || e.equals("Copy") || e.equals("Delete")) it.setEnabled(HzAuthTool.active() ? HzAuthTool.selection() != null : UiHost.get().hasSelectedText());
					else if (e.equals("Paste")) it.setEnabled(HzAuthTool.active() ? clipXml != null && clipButton == HzPaint.tool().equals("button") : UiHost.get().canPasteText());
				}
				if (!(c instanceof MCheckBoxMenuItem cb) || cb.getClientProperty("menuMessage") != null) continue;
				International intl = HZ.intl();
				String eng = (intl != null ? intl.getEngText(cb.getText()) : cb.getText()).trim().toLowerCase(java.util.Locale.ROOT);
				String prop = switch (eng) {
					case "background" -> "editbkgnd";
					case "grid snap" -> "grid";
					case "draw centered" -> "centered";
					case "draw filled" -> "filled";
					default -> null;
				};
				if (prop == null) continue;
				// (Background is off with the browse tool, as the desktop's Edit menu)
				if (prop.equals("editbkgnd")) cb.setEnabled(!HzPaint.tool().equals("browse"));
				boolean on = "true".equalsIgnoreCase(String.valueOf(HeadlessProps.get(prop)).trim()) || prop.equals("editbkgnd") && HZ.editBackground;
				if (cb.isSelected() != on) cb.setSelected(on);
			}
		}
	}

	/** Delete (the key) with the button or field tool: the selected part goes */
	static void deleteSelected() { if (selected != null) deletePart(selected.getClass()); }

	/** Option-drag with the button or field tool: a copy of the part on its own layer, over it, selected (pastePart's way) */
	static OObject duplicate(OObject part) {
		String[] xt = partXml(part);
		if (xt == null || !(part.parent instanceof OCardBase owner)) return null;
		OObject copy = makePart(owner, xt[0], xt[1], part instanceof OButton);
		if (copy != null) selected = copy;
		return copy;
	}

	/** GMenu's Delete Field / Delete Button: the selected part goes, and a background part's text on every card */
	private static void deletePart(Class<?> kind) {
		OObject part = selected;
		if (!kind.isInstance(part) || !(part.parent instanceof OCardBase)) return;
		OCardBase owner = (OCardBase) part.parent;
		if ("background".equals(owner.objectType) && HZ.stack() != null) {
			for (OCard cd : HZ.stack().cdCacheList) {
				if (cd.bgid != owner.id) continue;
				boolean removed = part instanceof OButton ? cd.bgbtnList.removeIf(d -> d.id == part.id) : cd.bgfldList.removeIf(d -> d.id == part.id);
				if (removed) cd.changed = true;
			}
		}
		owner.partsList.remove(part);
		if (part instanceof OButton) owner.btnList.remove(part); else owner.fldList.remove(part);
		owner.changed = true;
		selected = null;
		UiHost.get().repaintCardPane();
	}

	/** what Copy Button / Copy Field copied: the part's XML and its text (a background field's on this card), or null */
	private static String clipXml, clipText;
	private static boolean clipButton;
	private static void copyPart(Class<?> kind, boolean cut) {
		OObject part = selected;
		if (!kind.isInstance(part)) return;
		String[] xt = partXml(part);
		if (xt == null) return;
		clipXml = xt[0]; clipText = xt[1];
		clipButton = part instanceof OButton;
		if (cut) deletePart(kind);
	}
	/** a part's XML and its text (a background field's on this card), or null */
	private static String[] partXml(OObject part) {
		try {
			java.io.StringWriter out = new java.io.StringWriter();
			XmlOut w = Platform.get().xmlOut(out);
			String text;
			if (part instanceof OButton b) { b.writeXML(w); text = b.getText(); }
			else { ((OField) part).writeXML(w); text = ((OField) part).getText(); }
			w.close();
			return new String[] {out.toString(), text};
		} catch (XmlException | RuntimeException e) {
			System.out.println("Could not copy the part: " + e);
			return null;
		}
	}
	/** Paste Button / Paste Field with that tool: a copy where it was copied from, on the card (the background with editBkgnd),
	 *  with an id of its own and selected */
	private static void pastePart(boolean button) {
		OStack st = HZ.stack();
		if (st == null || st.curCard == null || clipXml == null || clipButton != button) return;
		if (!HzPaint.tool().equals(button ? "button" : "field")) return;
		OCardBase cdbase = HZ.editBackground || "true".equalsIgnoreCase(String.valueOf(HeadlessProps.get("editbkgnd")).trim()) ? st.curCard.bg : st.curCard;
		OObject part = makePart(cdbase, clipXml, clipText, button);
		if (part != null) selected = part;
	}
	/** a part from its XML on the layer, with an id of its own, out of any panel, at the top */
	private static OObject makePart(OCardBase cdbase, String xml, String text, boolean button) {
		int newid = cdbase.newPartId();
		try {
			XmlIn r = Platform.get().xmlIn(new java.io.StringReader(xml));
			OObject part;
			if (button) {
				OButton b = new OButton(cdbase, newid);
				b.readXML(r);
				b.panelId = 0; b.id = newid;
				b.setText(text);
				cdbase.partsList.add(b); cdbase.btnList.add(b);
				part = b;
			} else {
				OField f = new OField(cdbase, newid);
				f.readXML(r);
				f.panelId = 0; f.id = newid;
				f.setText(text);
				cdbase.partsList.add(f); cdbase.fldList.add(f);
				part = f;
			}
			cdbase.changed = true;
			OCard.reloadCurrentCard();
			UiHost.get().repaintCardPane();
			return part;
		} catch (Exception e) {
			System.out.println("Could not paste the part: " + e);
			return null;
		}
	}

	/** GMenu's New Card: a card of this background after this one, newCard there before openCard, shown at once */
	private static void newCard(OStack st) throws xTalkException {
		OCard cd = new OCard(st);
		cd.id = st.newCardOrBgId();
		cd.bgid = st.curCard.bg.id;
		cd.changed = true;
		st.AddNewCard(st.GetNumberof(st.curCard), cd.id);
		TTalk.sendNewCard = true;
		TTalk.runExternalStatement("go cd id " + cd.id, st.curCard);
	}

	// ---------------------------------------------------------------- Copy Card / Cut Card / Paste Card (CardClipboard's)

	/** what Copy Card copied: the card's XML (with its background fields' text), its background's and that one's name, the
	 *  stack, and the picture and layer files' bytes as they were at the copy (by their names there); null: nothing */
	private static String cardXml, cardBgXml, cardBgName;
	private static OStack cardFrom;
	private static final java.util.Map<String, byte[]> cardFiles = new java.util.HashMap<>();

	/** Copy Card: the card as the stack file holds it, its pictures as they show (a painted one written first) */
	private static void copyCard(OCard cd) throws xTalkException {
		OStack st = cd.stack;
		if (st != null && HzPaint.hasUnsaved(st)) HzPaint.flush(st);
		OBackground bg = st == null ? null : backgroundOf(st, cd.bgid);
		try {
			cardXml = xmlOf(cd::writeXML);
			cardBgXml = bg == null ? null : xmlOf(bg::writeXML);
			cardBgName = bg == null ? null : bg.name;
		} catch (Exception e) {
			throw new xTalkException("Cannot copy card: {0}", e.getMessage());
		}
		cardFrom = st;
		cardFiles.clear();
		keepFiles(cd);
		if (bg != null) keepFiles(bg);
	}
	private static void keepFiles(OCardBase cb) {
		if (cb.bitmapName != null && !cb.bitmapName.isEmpty()) keepFile(cb.stack, cb.bitmapName);
		if (cb.layers != null) for (PaintLayer l : cb.layers) if (l.fileName != null) keepFile(cb.stack, l.fileName);
	}
	private static void keepFile(OStack st, String name) { byte[] b = HzImages.bytes(st, name); if (b != null) cardFiles.put(name, b); }

	private interface XmlPart { void write(XmlOut writer) throws Exception; }
	private static String xmlOf(XmlPart part) throws Exception {
		java.io.StringWriter out = new java.io.StringWriter();
		XmlOut writer = Platform.get().xmlOut(out);
		part.write(writer);
		writer.flush();
		return XmlText.encodeControlChars(out.toString());
	}
	private static OBackground backgroundOf(OStack st, int id) {
		for (OBackground bg : st.bgCacheList) if (bg.id == id) return bg;
		return null;
	}

	/** Cut Card: Copy Card, then Delete Card; nothing on the only card or one that cannot be deleted (the item is disabled) */
	private static void cutCard(OStack st) throws xTalkException {
		OCard cutting = st.curCard;
		if (st.cardIdList.size() <= 1 || cutting.cantDelete || st.cantDelete) return;
		copyCard(cutting);
		deleteCard(st);
	}

	/** Paste Card: the copied card after this one under a new id (its background kept when the stack has it, else this card's;
	 *  a background renamed since the copy is pasted too, as a new one: how a script duplicates a background), its pictures
	 *  under the new ids' names, newCard sent there before openCard and the card shown */
	private static void pasteCard(OStack st) throws xTalkException {
		if (cardXml == null) return;
		OCard after = st.curCard;
		OCard cd = new OCard(st);
		try { readInto(cardXml, "card", cd::readXML); }
		catch (Exception e) { st.cdCacheList.remove(cd); throw new xTalkException("Cannot paste card: {0}", e.getMessage()); }
		cd.id = st.newCardOrBgId();
		OBackground bgHere = backgroundOf(st, cd.bgid);
		if (bgHere == null) {
			// another stack's card: it goes on the background of the card it follows
			cd.bgid = after.bgid;
			cd.bgfldList.clear();
			cd.bgbtnList.clear();
		}
		if (bgHere != null && cardFrom == st && cardBgXml != null && !java.util.Objects.equals(bgHere.name, cardBgName)) {
			OBackground copy = new OBackground(st);
			try { readInto(cardBgXml, "background", copy::readXML); }
			catch (Exception e) { st.cdCacheList.remove(cd); throw new xTalkException("Cannot paste card: {0}", e.getMessage()); }
			copy.id = st.newCardOrBgId();
			copy.name = cardBgName;
			st.AddNewBgAfter(copy, bgHere.id);
			cd.bgid = copy.id;
			pastePictures(st, copy);
			copy.changed = true;
		}
		pastePictures(st, cd);
		cd.changed = true;
		st.AddNewCard(st.GetNumberof(after), cd.id);
		TTalk.sendNewCard = true;
		TTalk.runExternalStatement("go cd id " + cd.id, st.curCard);
	}
	private interface XmlRead { void read(XmlIn reader) throws Exception; }
	private static void readInto(String xml, String element, XmlRead part) throws Exception {
		XmlIn reader = XmlText.decodingReader(Platform.get().xmlIn(new java.io.StringReader(xml)));
		while (reader.hasNext()) if (reader.next() == XmlIn.START_ELEMENT && reader.getLocalName().equals(element)) break;
		part.read(reader);
	}
	/** the copied picture and layer files written under the new id's names (BMAP_<id>…, as the desktop's); none copied: none */
	private static void pastePictures(OStack st, OCardBase cb) {
		if (cb.bitmapName != null && !cb.bitmapName.isEmpty()) {
			String ext = cb.bitmapName.contains(".") ? cb.bitmapName.substring(cb.bitmapName.lastIndexOf('.')) : "";
			cb.bitmapName = pasteFile(st, cb.bitmapName, "BMAP_" + cb.id + ext);
		}
		if (cb.layers != null) {
			int n = 1;
			for (PaintLayer l : cb.layers) if (l.fileName != null) l.fileName = pasteFile(st, l.fileName, "BMAP_" + cb.id + "_L" + (n++) + ".png");
		}
	}
	private static String pasteFile(OStack st, String from, String to) {
		byte[] b = cardFiles.get(from);
		if (b == null) return null;
		HeadlessStacks.writeFile(st, to, b);
		HzImages.forget(st, to);
		return to;
	}

	/** GMenu's Delete Card: not a card that cannot be deleted, nor the last one; deleteCard (and deleteBackground) first, then
	 *  the card after it shows (the first after the last) */
	private static void deleteCard(OStack st) throws xTalkException {
		if (st.cardIdList.size() <= 1) return;
		OCard deleting = st.curCard;
		boolean lastCardOfBg = true;
		for (OCard other : st.cdCacheList) if (other != deleting && other.bgid == deleting.bgid) { lastCardOfBg = false; break; }
		if (deleting.cantDelete || st.cantDelete || (lastCardOfBg && deleting.bg != null && deleting.bg.cantDelete)) return;
		TTalk.doScriptforMenu("send \"deleteCard\" to this card");
		if (lastCardOfBg) TTalk.doScriptforMenu("send \"deleteBackground\" to this background");
		if (st.curCard != deleting) return; // its handler went elsewhere
		int next = 0;
		for (int i = 0; i < st.cardIdList.size(); i++) {
			if (st.cardIdList.get(i) != deleting.id) continue;
			int n = st.cardIdList.size();
			next = st.cardIdList.get(i + 1 < n ? i + 1 : 0);
			if (i == 0) st.firstCard = next;
			st.cardIdList.remove(i);
			break;
		}
		st.cdCacheList.removeIf(c -> c.id == deleting.id);
		if (lastCardOfBg) {
			for (int j = 0; j < st.bgCacheList.size(); j++) {
				if (st.bgCacheList.get(j).id != deleting.bgid) continue;
				st.bgCacheList.remove(j);
				if (!st.bgCacheList.isEmpty()) st.firstBg = st.bgCacheList.get(0).id;
				break;
			}
		}
		st.changed = true;
		if (next != 0) TTalk.runExternalStatement("go cd id " + next, deleting);
	}
}

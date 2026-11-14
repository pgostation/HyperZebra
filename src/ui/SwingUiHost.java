/** The desktop's {@link UiHost}: each method runs the desktop code that moved out of the core (see moves.txt). */
final class SwingUiHost extends UiHost {
	// ---- dialogs: the GDialog the commands showed (it waits for the click) ----
	@Override String answer(OStack stack, String text, String b1, String b2, String b3) {
		new GDialog(PCARD.pc, new StackTranslation.Dialog(stack), text, null, b1, b2, b3);
		return GDialog.clicked;
	}
	@Override String[] ask(OStack stack, String text, String input, String cancel, String ok, boolean hidden) {
		new GDialog(PCARD.pc, new StackTranslation.Dialog(stack), text, input, cancel, ok, hidden);
		return new String[]{GDialog.clicked, GDialog.inputText};
	}

	@Override boolean typeIntoViews(String text, int[] modKeys, OField fieldWithCaret) throws xTalkException { return KeysUI.typeIntoViews(text, modKeys, fieldWithCaret); }
	@Override void typeWithKeyboard(String text, int[] modKeys) { KeysUI.typeWithKeyboard(text, modKeys); }

	// ---- menus: GMenu's code the core called before ----
	@Override void doMenu(String item, String menuName) throws xTalkException { GMenuBrowse.doMenu(item, menuName); }
	@Override void doMenuCommand(String name) throws xTalkException { GMenuBrowse.doMenu(name); }
	@Override void doMenuFromScript(String item, String menuName, boolean withoutDialog, int[] modKeys) throws xTalkException {
		for(int k : modKeys) GUI.key[k] = 1;
		boolean savedNoDialog = GMenuBrowse.withoutDialog;
		boolean savedFromScript = GMenuBrowse.fromScript;
		GMenuBrowse.withoutDialog = withoutDialog;
		GMenuBrowse.fromScript = true;
		try{
			GMenuBrowse.doMenu(item, menuName);
		}finally{
			GMenuBrowse.withoutDialog = savedNoDialog;
			GMenuBrowse.fromScript = savedFromScript;
			for(int k : modKeys) GUI.key[k] = 0;
		}
	}
	@Override void enforceUserLevel() { GMenu.enforceUserLevel(); }
	@Override boolean[] paintOptionChecks() { return new boolean[]{PaintTool.drawingGrid, PCARD.pc!=null && PCARD.pc.fill, PaintTool.drawCentered, PaintTool.drawMultiple}; }
	@Override int keyScript() { return Toolbox68k.keyScript; }
	@Override void setKeyScript(int script) { Toolbox68k.keyScript = script; }
	@Override String[] fontFamilyNames() { return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(); }
	@Override void resetMenuBar() { PCARD.pc.menu = new GMenu(PCARD.pc, 0); }
	@Override Boolean menuBarShown() {
		if(PCARD.pc==null || PCARD.pc.getJMenuBar()==null) return null;
		return PCARD.pc.getJMenuBar().isVisible();
	}

	@Override FieldView fieldView(OField f) { return f.fld; }
	@Override Runnable beginBusy() { StackProgress.Busy busy = StackProgress.busy(); return busy::close; }
	@Override byte[] unsealStack(byte[] data, String xmlfile, OStack inStack) {
		if(!StackProtection.isSealed(data)) return data;
		try {
			if(!StackProtection.needsPassword(data)) return StackProtection.unseal(data, null);
			if(inStack.unlockedPassword!=null){
				try { return StackProtection.unseal(data, inStack.unlockedPassword); }
				catch(java.security.GeneralSecurityException wrong){ inStack.unlockedPassword = null; }
			}
			final byte[][] plain = new byte[1][];
			String name = new java.io.File(xmlfile).getAbsoluteFile().getParentFile().getName().replaceFirst("(?i)\\.hzb$", "");
			String pw = StackProtection.askUntilRight(name, typed -> {
				try { plain[0] = StackProtection.unseal(data, typed); return true; }
				catch(java.security.GeneralSecurityException wrong){ return false; }
			});
			if(pw==null){ XMLRead.lockedOut = true; return null; }
			inStack.unlockedPassword = pw;
			return plain[0];
		} catch(java.security.GeneralSecurityException e){
			System.err.println(xmlfile+" could not be unsealed: "+e);
			XMLRead.lockedOut = true;
			return null;
		}
	}
	@Override void registerStackContentKey(OStack inStack, String xmlfile) {
		if(inStack.contentKey!=null && StackProtection.sealsXml(inStack)){
			StackProtection.registerContentKey(new java.io.File(xmlfile).getAbsoluteFile().getParentFile(), inStack.contentKey);
		}
	}
	@Override void readOnlyNotice(OStack inStack, String message) {
		new GDialog(inStack.pcard, HZ.intl().getDialogText(message)+"\n"+
			HZ.intl().getDialogText("The stack is opened read-only."),null,"OK",null,null);
	}
	@Override Boolean buttonViewHilite(OButton b) {
		if(b.getComponent()!=null)
		{
			if(b.btn!=null){
				return b.btn.getHilite();
			}
			else if (b.radio!=null){
				return b.radio.isSelected();
			}
			else if (b.chkbox!=null){
				return b.chkbox.isSelected();
			}
		}
		return null;
	}
	@Override boolean msgExists() { return GMsg.msg!=null; }
	@Override boolean msgHasArea() { return GMsg.area!=null; }
	@Override boolean msgInCardWindow() { return PCARD.pc!=null && PCARD.pc.msg!=null && GMsg.area!=null; }
	@Override boolean msgVisible() { return GMsg.msg.isVisible(); }
	@Override void setMsgVisible(boolean shown) { GMsg.msg.setVisible(shown); }
	@Override String msgText() { return GMsg.msg.getText(); }
	@Override String msgAreaText() { return GMsg.area.getText(); }
	@Override void setMsgText(String text) { GMsg.msg.setText(text); }
	// the selection read on the event thread: a focus gain there moves the caret and puts it back (GMsg's area), and a
	// script reading in between saw char 1 to 0 for the selectedChunk
	@Override int msgSelectionStart() { int[] at = new int[1]; WindowUI.runOnEventThread(() -> at[0] = GMsg.area.getSelectionStart()); return at[0]; }
	@Override int msgSelectionEnd() { int[] at = new int[1]; WindowUI.runOnEventThread(() -> at[0] = GMsg.area.getSelectionEnd()); return at[0]; }
	@Override String msgSelectedText() { String[] text = new String[1]; WindowUI.runOnEventThread(() -> text[0] = GMsg.area.getSelectedText()); return text[0]; }
	@Override void msgSelect(int start, int end) { GMsg.area.select(start, end); }
	@Override void msgSetCaret(int at) { GMsg.area.setCaretPosition(at); }
	@Override void msgFocus() { GMsg.area.requestFocusInWindow(); }
	@Override boolean msgTypedInto() { return GMsg.msg.typedInto(); }

	// ---- stacks (S10) ----
	@Override OStack newStack() { return new OStack(PCARD.pc); }
	@Override java.awt.Point cardWindowLocation() { return PCARD.pc.isShowing() ? PCARD.pc.getLocation() : null; }
	@Override boolean cardWindowUndecorated() { return PCARD.pc.isUndecorated(); }
	@Override boolean scriptsKeptOnLeave(OStack s) { return !ScriptEditor.stackLeaving(s); }
	@Override boolean savedBeforeLeave(OStack s) { return StackSaving.ensureSaved(s, StackSaving.Purpose.LEAVE); }

	// ---- pictures and fonts (S10) ----
	@Override int fieldLineHeight(String font, int style, int size) {
		java.awt.FontMetrics fm = FieldsUI.METRICS.getFontMetrics(OObject.stackFont(font, style&0x03, size));
		return Math.max(1, OField.naturalLineHeight(font, size, 1, fm));
	}
	@Override boolean hasSystemIcon(int id) { return OButton.systemIcon(id) != null; }
	@Override String talkImage(String function, String[] params) throws xTalkException {
		switch (function) {
			case "imageFilter": return TalkImage.imageFilter(params);
			case "imageCombine": return TalkImage.imageCombine(params);
			case "imageCreate": return TalkImage.imageCreate(params);
			default: return TalkImage.imageSize(params[0]);
		}
	}

	// ---- shapes (S10) ----
	@Override float svgFilterPadding(SvgFilter f) { return f == null ? 0 : f.padding(); }
	@Override boolean svgFilterActive(SvgFilter f) { return f != null && !f.primitives.isEmpty(); }
	@Override java.awt.image.BufferedImage applySvgFilter(SvgFilter f, java.awt.image.BufferedImage source) { return f.apply(source); }
	@Override void writeSvgFilter(SvgFilter f, XmlOut w) throws XmlException { f.writeXML(w); }
	@Override String svgFilterText(SvgFilter f) { return f==null||f.primitives.isEmpty()?"":f.toSvg(); }
	@Override SvgFilter svgFilterFromText(String text) throws xTalkException { return SvgFilter.fromSvgText(text); }
	@Override SvgFilter readSvgFilter(XmlIn r) throws XmlException { return SvgFilter.readXML(r); }
	@Override boolean readableImage(byte[] data) throws java.io.IOException { return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data))!=null; }
	@Override java.awt.Dimension shapeTextSize(OShape s) {
		java.awt.geom.Rectangle2D natural = s.naturalTextBounds();
		return new java.awt.Dimension(Math.max(1,(int)Math.ceil(natural.getWidth())), Math.max(1,(int)Math.ceil(natural.getHeight())));
	}
	@Override java.awt.Rectangle shapeFrameBounds(OShape s) { return s.frame().getBounds(); }

	// ---- beeps, the JIT, sound, the 68K externals' animation (S10) ----
	@Override void beep() { java.awt.Toolkit.getDefaultToolkit().beep(); }
	@Override Result jitLoop(TTalk t, OObject object, OObject target, String message, java.util.ArrayList<String> scriptList, int line, int endRepeat,
			MemoryData memData, String[] parentparams, int mode) throws xTalkException {
		if(!TalkJit.enabled) return null;
		return TalkJit.enterLoop(t, object, target, message, scriptList, line, endRepeat, memData, parentparams, mode);
	}
	@Override Result jitCount(TTalk t, OObject object, OObject target, String message, java.util.ArrayList<String> scriptList, int line, int endRepeat, int from, int to,
			MemoryData memData, String[] parentparams, int mode) throws xTalkException {
		if(!TalkJit.enabled) return null;
		return TalkJit.enterCount(t, object, target, message, scriptList, line, endRepeat, from, to, memData, parentparams, mode);
	}
	@Override Result jitEnter(TTalk t, OObject object, OObject target, String message, java.util.ArrayList<String> scriptList, int start, int end,
			MemoryData memData, String[] parentparams, int mode) throws xTalkException {
		if(!TalkJit.enabled) return null;
		return TalkJit.enter(t, object, target, message, scriptList, start, end, memData, parentparams, mode);
	}
	@Override TalkJit.Compiler talkJitCompiler() { return TalkJit::compileHidden; }
	@Override void setSoundOn(boolean on) { TSound.use = on; }

	// ---- files (S10) ----
	@Override boolean allowScriptFile(java.io.File f, String access) { return Sandbox.allowFile(f, Sandbox.Access.valueOf(access), Sandbox.scriptRequester()); }

	// ---- the rest of the desktop's pieces (S10 part 3) ----
	@Override TSound newSound() { return new TSound(); }
	@Override void playStop() { TTalk.sound().PlayStop(); }
	@Override boolean playNotes(OStack s, String sound, String[] notes, int tempo, int volume) throws xTalkException { return TTalk.sound().Play(s, sound, notes, tempo, volume); }
	@Override String soundLastError() { return TTalk.sound().lastError; }
	@Override void focusCardPane() { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.requestFocusInWindow(); }
	@Override void repaintCardPane() { if(PCARD.pc!=null && PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); }
	@Override boolean hasCardPane() { return PCARD.pc!=null && PCARD.pc.mainPane!=null; }
	@Override void setClipboardText(String value) {
		try{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
				new java.awt.datatransfer.StringSelection(value), null);
		}catch(Exception e){ /* no clipboard (headless): nothing to put into */ }
	}
	@Override String askFile(OStack stack, String answerStr, String filename) {
		javax.swing.JFileChooser chooser;
		java.io.File stackFolder = HZ.stack().file!=null ? TTalk.fileDialogFolder(GMenuBrowse.stackChooserDirectory(HZ.stack().file)) : null;
		if(stackFolder!=null){
			//スタックのあるフォルダ(answer file と同じ)。以前は変換したスタックの .hzb フォルダの中を開いていた
			chooser = new javax.swing.JFileChooser();
			java.io.File parent = stackFolder;
			chooser.setCurrentDirectory(parent);
			chooser.setSelectedFile(new java.io.File(parent.getPath()+java.io.File.separatorChar+filename));
		}
		else{
			chooser = new javax.swing.JFileChooser(new java.io.File(filename));
		}
		chooser.setDialogTitle(StackTranslation.dialogText(stack, answerStr));
		int ret = PCARD.showFileChooser(chooser, PCARD.pc, true);
		if(ret != javax.swing.JFileChooser.APPROVE_OPTION) return null;
		return TTalk.scriptPath(chooser.getSelectedFile());
	}
	@Override void selectPart(OObject obj) {
		if(obj instanceof OButton){
			TBButtonListener.ChangeTool("button", null);
			ButtonGUI.gui.tgtOBtn = (OButton)obj;
			ButtonGUI.gui.target = ((OButton)obj).getComponent();
		}
		else if(obj instanceof OField){
			TBButtonListener.ChangeTool("field", null);
			FieldGUI.gui.tgtOFld = (OField)obj;
			FieldGUI.gui.target = ((OField)obj).getComponent();
		}
		else if(obj instanceof OShape shape){
			Host.get().runOnEDT(()->{TBButtonListener.ChangeTool("Shape",null);ShapeTool.select(shape);});
		}
	}
	@Override boolean fieldHasView(OField f) { return f.fld!=null; }
	@Override void keepFieldScroll(OField f, Runnable r) { f.fld.keepScrollWhile(r); }
	@Override String browseUrl(java.net.URI uri) {
		try{
			if(!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
				return "Could not open the specified URL: no web browser.";
			java.awt.Desktop.getDesktop().browse(uri);
			return "";
		}catch(Exception e){
			return "Could not open the specified URL: "+e.getMessage();
		}
	}
	@Override boolean isMessageWatcher(Object dialog) { return dialog instanceof MessageWatcher; }
	@Override boolean isVariableWatcher(Object dialog) { return dialog instanceof VariableWatcher; }
	@Override java.awt.Rectangle dialogBounds(Object dialog) { return ((java.awt.Component)dialog).getBounds(); }
	@Override String paintTextFontSetting() { return PCARD.pc.textFont; }
	@Override boolean focusOwner(javax.swing.JComponent c) { return c!=null && c.isFocusOwner(); }
	@Override java.awt.Point cardScroll() { return PCARD.pc.cardScroll(); }
	@Override String pictureScroll(GPictWindow gpw) { return gpw.getScrollX()+","+gpw.getScrollY(); }
	@Override java.awt.Rectangle toolPaletteBounds() { return PCARD.pc!=null && PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null ? PCARD.pc.toolbar.tb.getBounds() : null; }
	@Override boolean isCardWindow(OWindow w) { return w.frame!=null && w.frame==PCARD.pc; }
	@Override java.awt.Point cardWindowPlace() { return PCARD.pc!=null ? PCARD.pc.getLocation() : null; }
	@Override void writeStackFile(OStack other, boolean lockChange) {
		if(lockChange) XMLwrite.writeOnce(other);
		else XMLwrite.withoutAutosave(other).saveStackNow();
	}
	@Override boolean pictureUndecorated(GPictWindow w) { return w.isUndecorated(); }
	@Override boolean mp3Playing(TMP3 m) { return m.isPlaying(); }
	@Override void mp3Volume(TMP3 m, int v) { m.setVolume(v); }
	@Override void mp3Time(TMP3 m, int v) { m.setTime(v); }
	@Override void pictureSetScroll(GPictWindow w, int x, int y) { w.setScroll(x, y); }
	@Override void pictureDithering(GPictWindow w, boolean on) { w.dithering = on; }
	@Override void pictureScale(GPictWindow w, float vv) { w.label.scale=vv; w.repaint(); }
	@Override boolean pictureIsPalette(GPictWindow w) { return w.isPaletteWindow(); }
	@Override String osaLanguage(OsaScript s) { return s.languageName(); }
	@Override String osaSubtype(OsaScript s) { return s.subtype(); }
	@Override java.io.File mountedDiskFolder(String name) {
		for(DiskImages.Mounted m : DiskImages.mounted()) if(m.name.equalsIgnoreCase(name) && m.folder.isDirectory()) return m.folder;
		return null;
	}
	@Override String startLanguage() { return PCARD.language; }
	@Override String cardWindowLanguage() { return PCARD.pc!=null && PCARD.pc.lang!=null ? PCARD.pc.lang : PCARD.language; }
	@Override boolean japaneseCardWindow() { return PCARD.pc!=null && International.sameLanguage(PCARD.pc.lang, "ja"); }
	@Override AuthoringUndo newAuthoringUndo() { return new AuthoringUndo(); }
	@Override boolean stackHasPane(OStack s) { return s.pcard!=null && s.pcard.mainPane!=null; }
	@Override PatButton patternButton() { return PCARD.pc==null ? null : PCARD.pc.pat; }
	@Override void repaintStackFrame(OStack s) { s.pcard.repaint(); }
	@Override boolean powerKeys() { return PaintTool.powerKeys; }
	@Override boolean onEventThread() { return javax.swing.SwingUtilities.isEventDispatchThread(); }
	@Override OsaScript readOsaScript(XmlIn r) throws XmlException { return OsaScript.readXML(r); }

	// ---- windows (S10 part 4) ----
	@Override OXWindow newXWindow(Xcmd68k.ExternalWindow w) { return new OXWindow(w); }
	@Override boolean windowShowing(OWindow w) {
		if(w.frame!=null) return w.frame.isVisible();
		if(w.dlog!=null) return w.dlog.isVisible();
		return w.gpw.isVisible();
	}
	@Override boolean toolPaletteVisible() {
		PCARD pc = PCARD.pc;
		return pc!=null && pc.toolbar!=null && pc.toolbar.tb!=null && pc.toolbar.tb.isVisible();
	}
	@Override String pictureWindowName(GPictWindow w) { return w.name; }

	// ---- S10 part 5 ----
	@Override int popupSelectedIndex(MyPopup p) { return p.getSelectedIndex(); }
	@Override void selectPopupLine(MyPopup p, int line) {
		if(line<=p.getItemCount()){
			boolean was = p.settingSelection;
			p.settingSelection = true;
			try{ p.setSelectedIndex(line-1); }
			finally{ p.settingSelection = was; }
		}
	}
	@Override void showFieldFont(OField f, String name, int style, double size) { f.showFont(OObject.stackFont(name, style, size)); }
	@Override boolean hasCardMenus() { return PCARD.pc!=null && PCARD.pc.menu!=null; }
	@Override boolean hasPaintMenus() { return PCARD.pc!=null && PCARD.pc.paintMenu!=null; }
	@Override boolean paintSelectionUp() { return PCARD.pc!=null && PCARD.pc.tool!=null && PaintTool.owner!=null && PaintTool.floating()!=null; }

	// ---- S10: the views plain TeaVM still met ----
	@Override void showFieldSelection(OField f) {
		FieldTable table = f.table();
		if(table!=null) Host.get().runOnEDT(table::showSelection);
		FieldOutline outline = f.outline();
		if(outline!=null) Host.get().runOnEDT(outline::showSelection);
	}
	@Override void showOutlineSelection(OField f) {
		FieldOutline outline = f.outline();
		if(outline!=null) Host.get().runOnEDT(outline::showSelection);
	}
	@Override void expandOutline(OField f) {
		FieldOutline outline = f.outline();
		if(outline!=null) Host.get().runOnEDT(outline::expandToLevel);
	}
	@Override void shapeViewVisible(OShape s) { s.view.setVisible(s.isShown()); }
	@Override void shapeViewFaded(OShape s, boolean visibleBefore) {
		if(visibleBefore!=(s.opacity<1)||!s.view.fadeOnly())s.view.cache=null;
		s.view.repaint();
	}
	@Override void shapeViewUpdated(OShape s) { s.view.cache=null; s.moveView(); s.view.repaint(); }
	@Override void clearButtonGroups() { for(int i=0;i<PartViews.btnGroup.length; i++) PartViews.btnGroup[i] = null; }

	// ---- desktop classes' static methods (rewrites/facade.py, facades.txt) ----
	@Override boolean tXcmdHas68kExternal(OStack a0, java.lang.String a1, java.lang.String a2) { return TXcmd.has68kExternal(a0, a1, a2); }
	@Override Result tXcmdCallExternalCommandOf(OStack a0, java.lang.String a1, java.lang.String[] a2, OObject a3, MemoryData a4, boolean a5, Result a6) throws xTalkException { return TXcmd.CallExternalCommandOf(a0, a1, a2, a3, a4, a5, a6); }
	@Override boolean tXcmdRunnable68kExternal(java.lang.String a0, java.lang.String a1) { return TXcmd.runnable68kExternal(a0, a1); }
	@Override OStack tXcmdExternalOwner(java.lang.String a0, java.lang.String a1) { return TXcmd.externalOwner(a0, a1); }
	@Override Result tXcmdCallExternalCommand(java.lang.String a0, java.lang.String[] a1, OObject a2, MemoryData a3, boolean a4, Result a5) throws xTalkException { return TXcmd.CallExternalCommand(a0, a1, a2, a3, a4, a5); }
	@Override Rsrc tXcmdResourcesOf(OStack a0) { return TXcmd.resourcesOf(a0); }
	@Override boolean soundOn() { return TSound.use; }
	@Override boolean xcmd68kHasSessions() { return Xcmd68k.hasSessions(); }
	@Override boolean xcmd68kHasSessionsHint() { return Xcmd68k.hasSessionsHint(); }
	@Override boolean xcmd68kHasWindowSessions() { return Xcmd68k.hasWindowSessions(); }
	@Override void xcmd68kPumpIfDue() { Xcmd68k.pumpIfDue(); }
	@Override void xcmd68kPumpWhileWaiting() { Xcmd68k.pumpWhileWaiting(); }
	@Override boolean xcmd68kRedrawHookedCards(OStack a0) { return Xcmd68k.redrawHookedCards(a0); }
	@Override boolean xcmd68kRedrawHookedCards(OStack a0, int a1, int a2, int a3, int a4) { return Xcmd68k.redrawHookedCards(a0, a1, a2, a3, a4); }
	@Override boolean xcmd68kCardDrawingHooked(OStack a0) { return Xcmd68k.cardDrawingHooked(a0); }
	@Override Xcmd68k.ExternalWindow xcmd68kFindWindow(java.lang.String a0) { return Xcmd68k.findWindow(a0); }
	@Override Xcmd68k.ExternalWindow xcmd68kFindWindowById(int a0) { return Xcmd68k.findWindowById(a0); }
	@Override boolean macApp68kIsApplication(java.io.File a0) { return MacApp68k.isApplication(a0); }
	@Override java.lang.String fileManager68kProgramName(StackTextEncoding a0, java.lang.String a1) { return FileManager68k.programName(a0, a1); }
	@Override java.util.List<java.lang.String> fileManager68kOtherReadings(java.lang.String a0) { return FileManager68k.otherReadings(a0); }
	@Override java.io.File fileManager68kExistingOrUnarchived(java.io.File a0, java.lang.String a1) { return FileManager68k.existingOrUnarchived(a0, a1); }
	@Override java.io.File fileSystem68kHostVolumeNamed(java.lang.String a0) { return FileSystem68k.hostVolumeNamed(a0); }
	@Override java.io.File fileSystem68kExternalsVolume(OStack a0) { return FileSystem68k.externalsVolume(a0); }
	@Override java.lang.String fileSystem68kMacPathOf(java.io.File a0) { return FileSystem68k.macPathOf(a0); }
	@Override java.io.File fileSystem68kHyperZebraProgram() { return FileSystem68k.hyperZebraProgram(); }
	@Override java.io.File stackBundleHost(java.io.File a0) { return StackBundle.host(a0); }
	@Override java.lang.String stackBundleHost(java.lang.String a0) { return StackBundle.host(a0); }
	@Override java.io.File stackBundlePackedOf(java.io.File a0) { return StackBundle.packedOf(a0); }
	@Override boolean stackBundleIsPacked(java.io.File a0) { return StackBundle.isPacked(a0); }
	@Override java.io.File stackBundleFolderAround(java.io.File a0) { return StackBundle.folderAround(a0); }
	@Override java.lang.String scriptWebFetch(java.lang.String a0) throws xTalkException { return ScriptWeb.fetch(a0); }
	@Override void scriptWebStore(java.lang.String a0, java.lang.String a1, boolean a2) throws xTalkException { ScriptWeb.store(a0, a1, a2); }
	@Override java.lang.String speechProgramName() { return Speech.programName(); }
	@Override void speechSpeak(java.lang.String a0, java.lang.String a1) { Speech.speak(a0, a1); }
	@Override void speechStop() { Speech.stop(); }
	@Override boolean speechIsGenderWord(java.lang.String a0) { return Speech.isGenderWord(a0); }
	@Override java.lang.String speechVoiceForGender(java.lang.String a0) { return Speech.voiceForGender(a0); }
	@Override void scriptEditorShowRuntimeError(OObject a0) { ScriptEditor.showRuntimeError(a0); }
	@Override OStack stackGuardEnter(OObject a0) { return StackGuard.enter(a0); }
	@Override void stackGuardLeave(OStack a0) { StackGuard.leave(a0); }
	@Override OStack stackGuardRunningStack() { return StackGuard.runningStack(); }
	@Override boolean stackGuardAllowsSet(OObject a0, java.lang.String a1, java.lang.String a2) { return StackGuard.allowsSet(a0, a1, a2); }
	@Override void gUIDropSelectedRange() { GUI.dropSelectedRange(); }
	@Override void gUICancelMouseUp() { GUI.cancelMouseUp(); }
	@Override boolean gUIMouseUpCancelled() { return GUI.mouseUpCancelled(); }
	@Override void gUIForgetClicks() { GUI.forgetClicks(); }
	@Override void vEffectSetOldOff() { VEffect.setOldOff(); }
	@Override void vEffectLockLazily() { VEffect.lockLazily(); }
	@Override void vEffectVisualEffect(int a0, int a1, int a2) { VEffect.visualEffect(a0, a1, a2); }
	@Override void vEffectVisualEffect(int a0, int a1, int a2, boolean a3) { VEffect.visualEffect(a0, a1, a2, a3); }
	@Override void vEffectHoldPlain(int a0) { VEffect.holdPlain(a0); }
	@Override boolean sandboxAllowOther(java.lang.String a0, java.lang.String a1, java.lang.String a2) { return Sandbox.allowOther(a0, a1, a2); }
	@Override java.lang.String sandboxScriptRequester() { return Sandbox.scriptRequester(); }
	@Override java.awt.image.BufferedImage pictureTranslationShown(OStack a0, java.awt.image.BufferedImage a1) { return PictureTranslation.shown(a0, a1); }
	@Override java.awt.image.BufferedImage pictureTranslationShown(OCardBase a0, java.awt.image.BufferedImage a1) { return PictureTranslation.shown(a0, a1); }
	@Override boolean pictureTranslationEnabledFor(java.io.File a0) { return PictureTranslation.enabledFor(a0); }
	@Override java.io.File appPathsStateDirectory() { return AppPaths.stateDirectory(); }
	@Override java.io.File appPathsDistributionDirectory() { return AppPaths.distributionDirectory(); }
	@Override java.io.File quickTimeServiceSystemFolder() { return QuickTimeService.systemFolder(); }
	@Override java.lang.String osaScriptCantUnderstand(java.lang.String a0, OObject a1) { return OsaScript.cantUnderstand(a0, a1); }
	@Override boolean tQTMovieAvailable() { return TQTMovie.available(); }
	@Override void tQTMovieSetCurrentTime(java.lang.String a0, long a1) { TQTMovie.setCurrentTime(a0, a1); }
	@Override int tQTMovieRate(java.lang.String a0) { return TQTMovie.rate(a0); }
	@Override java.lang.String tQTMovieProperty(java.lang.String a0, java.lang.String a1) { return TQTMovie.property(a0, a1); }
	@Override java.util.ArrayList<OWindow> tQTMovieScriptWindows() { return TQTMovie.scriptWindows(); }
	@Override OWindow tQTMovieFindScriptWindow(java.lang.String a0) { return TQTMovie.findScriptWindow(a0); }
	@Override void tQTMovieSetVolume(java.lang.String a0, int a1) { TQTMovie.setVolume(a0, a1); }
	@Override void tQTMovieDispose(java.lang.String a0) { TQTMovie.dispose(a0); }
	@Override java.io.File gMenuBrowseStackChooserDirectory(java.io.File a0) { return GMenuBrowse.stackChooserDirectory(a0); }
	@Override boolean gMenuBrowseCreateNewStack(java.io.File a0, int a1, int a2, OBackground a3) { return GMenuBrowse.createNewStack(a0, a1, a2, a3); }
	@Override void gMenuBrowseRemoveCardDataForBgPart(OObject a0) { GMenuBrowse.removeCardDataForBgPart(a0); }
	@Override OObject gMenuBrowseSelectedPart() { return GMenuBrowse.selectedPart(); }
	@Override void finderInfo68kMarkNewTextFile(java.io.File a0) { FinderInfo68k.markNewTextFile(a0); }
	@Override void cardWindow68kPartCopied(int a0, int a1, int a2, int a3) { CardWindow68k.partCopied(a0, a1, a2, a3); }
	@Override void cardWindow68kPartMoved(OObject a0, int a1, int a2, int a3, int a4) { CardWindow68k.partMoved(a0, a1, a2, a3, a4); }
	@Override void cardWindow68kHoldScreen() { CardWindow68k.holdScreen(); }
	@Override void cardWindow68kReleaseScreen() { CardWindow68k.releaseScreen(); }
	@Override void paintToolSetPowerKeys(boolean a0) { PaintTool.setPowerKeys(a0); }
	@Override java.lang.String launcherStoreIdentity(java.io.File a0) { return LauncherStore.identity(a0); }
	@Override long toolbox68kProgramClockSeconds() { return Toolbox68k.programClockSeconds(); }
	@Override byte[] stackProtectionPasswordBytes(java.lang.String a0, StackTextEncoding a1) { return StackProtection.passwordBytes(a0, a1); }
	@Override int stackProtectionHash(byte[] a0) { return StackProtection.hash(a0); }
	@Override int stackProtectionEffectiveUserLevel() { return StackProtection.effectiveUserLevel(); }
	@Override boolean gStackWindowIsShown(java.lang.String a0) { return GStackWindow.isShown(a0); }
	@Override void gStackWindowSetShown(java.lang.String a0, boolean a1) { GStackWindow.setShown(a0, a1); }
	@Override boolean gButtonOnCurrentCard(OObject a0) { return GButton.onCurrentCard(a0); }
	@Override void gUINoteKeyPoll() { GUI.noteKeyPoll(); }
	@Override void stackStrikeFontForget(Rsrc a0) { StackStrikeFont.forget(a0); }
	@Override java.awt.Font stackStrikeFont(String name, int style, double size) { return StackStrikeFont.font(name, style, size); }
	@Override float tabletInputLatest() { return TabletInput.latest(); }
	@Override float[] tabletInputLatestTilt() { return TabletInput.latestTilt(); }
	@Override void tabletInputStart() { TabletInput.start(); }
	@Override java.awt.Rectangle macAppHostScreenBounds() { return MacAppHost.screenBounds(); }
	@Override java.awt.Rectangle macAppHostScreenBounds(boolean a0) { return MacAppHost.screenBounds(a0); }
	@Override java.awt.Cursor macAppHostCursorFromData(byte[] a0) { return MacAppHost.cursorFromData(a0); }
	@Override java.awt.Cursor macAppHostColorCursor(int[] a0, byte[] a1) { return MacAppHost.colorCursor(a0, a1); }
	@Override void gLauncherShowLauncher(boolean a0) { GLauncher.showLauncher(a0); }
	@Override void xMLwriteWriteOnce(OStack a0) { XMLwrite.writeOnce(a0); }
	@Override byte[] stackFileIconFamilyOfClassic(java.util.Map<java.lang.Integer, byte[]> a0) { return StackFileIcon.familyOfClassic(a0); }
	@Override void workingCopiesCopyFile(java.io.File a0, java.io.File a1) throws java.io.IOException { WorkingCopies.copyFile(a0, a1); }
	@Override java.io.File tempFilesCreateFile(java.lang.String a0, java.lang.String a1) throws java.io.IOException { return TempFiles.createFile(a0, a1); }
	@Override boolean stackBundleHasHzbExtension(java.io.File a0) { return StackBundle.hasHzbExtension(a0); }
	@Override java.io.File appPathsBootVolumeDirectory() { return AppPaths.bootVolumeDirectory(); }
	@Override java.io.File appPathsMoveResourceToTrash(java.io.File a0) throws java.io.IOException { return AppPaths.moveResourceToTrash(a0); }
	@Override java.util.List<ExternalMenu68k> externalMenusUIHyperCardMenusForExternals() { return ExternalMenusUI.hyperCardMenusForExternals(); }
	@Override void externalMenusUIExternalChangedHyperCardMenu(java.lang.Object a0, int a1, java.lang.Boolean a2, java.lang.Boolean a3, java.lang.String a4) { ExternalMenusUI.externalChangedHyperCardMenu(a0, a1, a2, a3, a4); }
	@Override java.lang.Object externalMenusUIExternalChangedHyperCardItem(java.lang.Object a0, java.lang.Object a1, java.lang.Boolean a2, java.lang.String a3, java.lang.String a4, java.lang.Character a5, java.lang.Integer a6) { return ExternalMenusUI.externalChangedHyperCardItem(a0, a1, a2, a3, a4, a5, a6); }
	@Override void externalMenusUIExternalHierarchicalHyperCardItem(java.lang.Object a0, java.lang.Object a1, MenuModel68k a2) { ExternalMenusUI.externalHierarchicalHyperCardItem(a0, a1, a2); }
	@Override void externalMenusUIExternalRemovedHyperCardMenu(java.lang.Object a0) { ExternalMenusUI.externalRemovedHyperCardMenu(a0); }
	@Override void externalMenusUIExternalRestoredHyperCardMenu(java.lang.Object a0, java.lang.Object a1) { ExternalMenusUI.externalRestoredHyperCardMenu(a0, a1); }
	@Override java.util.List<java.lang.Object> externalMenusUIExternalArrangedHyperCardMenu(java.lang.Object a0, java.util.List<java.lang.Object> a1, boolean[] a2, boolean[] a3) { return ExternalMenusUI.externalArrangedHyperCardMenu(a0, a1, a2, a3); }
	@Override java.util.List<java.lang.Object> externalMenusUIExternalArrangedHyperCardMenu(java.lang.Object a0, java.util.List<java.lang.Object> a1, boolean[] a2, boolean[] a3, java.util.Collection<java.lang.Object> a4) { return ExternalMenusUI.externalArrangedHyperCardMenu(a0, a1, a2, a3, a4); }
	@Override void externalMenusUISyncExternalMenus(java.lang.Object a0, java.util.List<MenuModel68k> a1) { ExternalMenusUI.syncExternalMenus(a0, a1); }
	// ---- end of facades ----
	// ---- moved methods (generated) ----
	@Override void buildOButton(OButton obtn) { PartViews.buildOButton(obtn); }
	@Override void buildOField(OField ofld) { PartViews.buildOField(ofld); }
	@Override OCard buildOCard(OCard cd) { return PartViews.buildOCard(cd); }
	@Override OBackground buildOBackground(OBackground bg) { return PartViews.buildOBackground(bg); }
	@Override void setMenuBarVisible(boolean visible) { WindowUI.setMenuBarVisible(visible); }
	@Override void setTitleBarVisible(boolean visible) { WindowUI.setTitleBarVisible(visible); }
	@Override java.awt.Point getCardOriginOnScreen() { return WindowUI.getCardOriginOnScreen(); }
	@Override java.awt.Rectangle cardWindowRect() { return WindowUI.cardWindowRect(); }
	@Override void setCardWindowRect(java.awt.Rectangle r) { WindowUI.setCardWindowRect(r); }
	@Override java.util.List<Object[]> windowEntries() { return WindowUI.windowEntries(); }
	@Override void scrollSelectionIntoView(OField fld, int offset) { WindowUI.scrollSelectionIntoView(fld, offset); }
	@Override boolean choosePopupItem(OButton b) { return WindowUI.choosePopupItem(b); }
	@Override boolean authToolDrag(TTalk self, int sx, int sy, int ex, int ey, int[] modKeys, MemoryData memData) throws xTalkException { return WindowUI.authToolDrag(self, sx, sy, ex, ey, modKeys, memData); }
	@Override String askWhereIsStack(TTalk self, String ref) { return WindowUI.askWhereIsStack(self, ref); }
	@Override boolean launch68kApplication(String path) { return ProgramsUI.launch68kApplication(path); }
	@Override String openApplication(TTalk self, String app, String document) { return ProgramsUI.openApplication(self, app, document); }
	@Override void commandKeyShortcut(String ch) { ProgramsUI.commandKeyShortcut(ch); }
	@Override boolean toolFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return ToolsUI.toolFunction(message, params, target, memData, isFunc, result); }
	@Override boolean selectionFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return FieldsUI.selectionFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean programsFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return ProgramsUI.programsFunction(message, params, target, memData, isFunc, result); }
	@Override boolean settingsFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return ToolsUI.settingsFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean paintFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return ToolsUI.paintFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean textSettingsSetters(String inProperty, String value, String prpty) throws xTalkException { return ToolsUI.textSettingsSetters(inProperty, value, prpty); }
	@Override boolean cursorSetters(String inProperty, String value, String prpty) throws xTalkException { return ToolsUI.cursorSetters(inProperty, value, prpty); }
	@Override boolean paintSetters(String inProperty, String value, String prpty) throws xTalkException { return ToolsUI.paintSetters(inProperty, value, prpty); }
	@Override boolean windowSetLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException { return WindowUI.windowSetLeft(obj, inProperty, value, prpty, btn, fld, cd, v); }
	@Override boolean windowSetTop(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException { return WindowUI.windowSetTop(obj, inProperty, value, prpty, btn, fld, cd, v); }
	@Override boolean windowSetTopLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException { return WindowUI.windowSetTopLeft(obj, inProperty, value, prpty, btn, fld, cd, v1, v2); }
	@Override boolean windowSetLoc(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException { return WindowUI.windowSetLoc(obj, inProperty, value, prpty, btn, fld, cd, v1, v2); }
	@Override boolean windowSetRect(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2, int v3, int v4) throws xTalkException { return WindowUI.windowSetRect(obj, inProperty, value, prpty, btn, fld, cd, v1, v2, v3, v4); }
	@Override boolean windowSetZoomed(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException { return WindowUI.windowSetZoomed(obj, inProperty, value, prpty, btn, fld, cd); }
	@Override boolean clickAndDrag(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return PointerUI.clickAndDrag(self, stringList, typeAry, object, target, memData, result, str); }
	@Override Result answerProgram(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException { return DialogsUI.answerProgram(self, stringList, typeAry, object, target, memData, result, str, next); }
	@Override Result answerFolder(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException { return DialogsUI.answerFolder(self, stringList, typeAry, object, target, memData, result, str, next); }
	@Override boolean answerFile(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException { return DialogsUI.answerFile(self, stringList, typeAry, object, target, memData, result, str, next); }
	@Override void endPaintBeforeHome(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.endPaintBeforeHome(self, stringList, typeAry, object, target, memData, result, str); }
	@Override boolean closesStackWithoutHome() { return true; }
	@Override void emptyCardWindow(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.emptyCardWindow(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void endPaintBeforeGo(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.endPaintBeforeGo(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void freezeForCardChange(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.freezeForCardChange(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void dropLeftCardPicture(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OCard cd) throws xTalkException { CardUI.dropLeftCardPicture(self, stringList, typeAry, object, target, memData, result, str, cd); }
	@Override void dropSelectedRange(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.dropSelectedRange(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void cardRedrawn(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.cardRedrawn(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void playVisualEffects(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.playVisualEffects(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void restartToolAfterGo(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { CardUI.restartToolAfterGo(self, stringList, typeAry, object, target, memData, result, str); }
	@Override boolean editorsAndPictures(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return EditorsUI.editorsAndPictures(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void virusScanOpened(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OStack ostack) throws xTalkException { ProgramsUI.virusScanOpened(self, stringList, typeAry, object, target, memData, result, str, ostack); }
	@Override void writeStackOnce(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OStack ostack) throws xTalkException { ProgramsUI.writeStackOnce(self, stringList, typeAry, object, target, memData, result, str, ostack); }
	@Override void stopSound(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { SoundsUI.stopSound(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void stopSpeech(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { SoundsUI.stopSpeech(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void traceMode(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { EditorsUI.traceMode(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void aboutDialog(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { DialogsUI.aboutDialog(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void beepOnce(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { SoundsUI.beepOnce(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void flashScreen(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int cnt) throws xTalkException { CardUI.flashScreen(self, stringList, typeAry, object, target, memData, result, str, cnt); }
	@Override boolean speechFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return SoundsUI.speechFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean inputFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return PointerUI.inputFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean screenFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return WindowUI.screenFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean soundFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return SoundsUI.soundFunction(message, params, target, memData, isFunc, result); }
	@Override boolean stacksFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return WindowUI.stacksFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean cardWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.cardWindowProperty(obj, inProperty, value, prpty); }
	@Override boolean msgFontProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.msgFontProperty(obj, inProperty, value, prpty); }
	@Override boolean msgPlaceProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.msgPlaceProperty(obj, inProperty, value, prpty); }
	@Override boolean pictureWindowGlobalProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.pictureWindowGlobalProperty(obj, inProperty, value, prpty); }
	@Override boolean paletteWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.paletteWindowProperty(obj, inProperty, value, prpty); }
	@Override boolean movieWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.movieWindowProperty(obj, inProperty, value, prpty); }
	@Override boolean windowsSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.windowsSetVisible(obj, inProperty, value, prpty); }
	@Override boolean windowSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException { return WindowUI.windowSetVisible(obj, inProperty, value, prpty); }
	@Override void reattachCardViews() { PartViews.reattachCardViews(); }
	@Override boolean appleEventCommands(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return ProgramsUI.appleEventCommands(self, stringList, typeAry, object, target, memData, result, str); }
	@Override boolean saveCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return FilesUI.saveCommand(self, stringList, typeAry, object, target, memData, result, str); }
	@Override boolean chooseCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { return ToolsUI.chooseCommand(self, stringList, typeAry, object, target, memData, result, str); }
	@Override String printCommand(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, MemoryData memData, OObject object, OObject target) throws xTalkException { return ProgramsUI.printCommand(self, stringList, typeAry, memData, object, target); }
	@Override String getPictureWindowProperty(GPictWindow gpw, String prpty) { return WindowUI.getPictureWindowProperty(gpw, prpty); }
	@Override void browseCursorNow() { ToolsUI.browseCursorNow(); }
	@Override boolean paintLayerFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return ToolsUI.paintLayerFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean watcherFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return EditorsUI.watcherFunctions(message, params, target, memData, isFunc, result); }
	@Override boolean paintTextFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return ToolsUI.paintTextFunctions(message, params, target, memData, isFunc, result); }
	@Override String runAppleScript(String code) { return ProgramsUI.runAppleScript(code); }
	@Override boolean revealInFileManager(java.io.File item) { return ProgramsUI.revealInFileManager(item); }
	@Override void partChangedOnScreen(OObject part, java.awt.Rectangle before) { XWindowsUI.partChangedOnScreen(part, before); }
	@Override boolean editSelectedText(String cmd) { return FieldsUI.editSelectedText(cmd); }
	@Override void showFoundBox(OField self, int start, int end) { FieldsUI.showFoundBox(self, start, end); }
	@Override void clearFoundBox() { FieldsUI.clearFoundBox(); }
	@Override void setBgColorView849(OButton self, java.awt.Color col) { PartViews.setBgColorView849(self, col); }
	@Override void setColorView842(OButton self, java.awt.Color col) { PartViews.setColorView842(self, col); }
	@Override void setNameView176(OButton self, String in) { PartViews.setNameView176(self, in); }
	@Override void setNameView173(OButton self, String in) { PartViews.setNameView173(self, in); }
	@Override void setNameView170(OButton self, String in) { PartViews.setNameView170(self, in); }
	@Override void setNameView167(OButton self, String in) { PartViews.setNameView167(self, in); }
	@Override void setBgColorView896(OField self, java.awt.Color col) { PartViews.setBgColorView896(self, col); }
	@Override void setColorView887(OField self, java.awt.Color col) { PartViews.setColorView887(self, col); }
	@Override void setScrollView455(OField self, int in) { PartViews.setScrollView455(self, in); }
	@Override void removeDataView605(OCardBase self) { PartViews.removeDataView605(self); }
	@Override void setAutoHiliteView760(OButton self, boolean in) { PartViews.setAutoHiliteView760(self, in); }
	@Override void setHiliteView592(OButton self, boolean in) { PartViews.setHiliteView592(self, in); }
	@Override void setEnabledView562(OButton self, boolean in) { PartViews.setEnabledView562(self, in); }
	@Override void gcAtIdle() { ProgramsUI.gcAtIdle(); }
	@Override void unlockScreenAtIdle() { CardUI.unlockScreenAtIdle(); }
	@Override void pointerAtIdle() { PointerUI.pointerAtIdle(); }
	@Override boolean openStackFileInThread(OStack self, String inpath, boolean isUsingOnly) { return StacksUI.openStackFileInThread(self, inpath, isUsingOnly); }
	@Override void openStackWindow(OStack self, boolean isUsingOnly) { StacksUI.openStackWindow(self, isUsingOnly); }
	@Override java.awt.image.BufferedImage makeRecentThumbnail(OCard card) { return CardUI.makeRecentThumbnail(card); }
	@Override void setNewBounds(OStack self) { WindowUI.setNewBounds(self); }
	@Override void stackViews(OStack self, PCARDFrame pc) { PartViews.stackViews(self, pc); }
	@Override void externalsAtStackClose(OStack self) { ProgramsUI.externalsAtStackClose(self); }
	@Override void editorsAtStackClose(OStack self) { EditorsUI.editorsAtStackClose(self); }
	@Override void cardWindowToCardSize(OStack self, int v1, int v2, int v3, int v4) { WindowUI.cardWindowToCardSize(self, v1, v2, v3, v4); }
	@Override void cardWindowToTopLeft(OStack self, int h, int v) { WindowUI.cardWindowToTopLeft(self, h, v); }
	@Override void cardWindowScrolled(OStack self, int x, int y) { WindowUI.cardWindowScrolled(self, x, y); }
	@Override void cardWindowTitled(OStack self, String in) { WindowUI.cardWindowTitled(self, in); }
	@Override void cardWindowResizable(OStack self, Boolean in) { WindowUI.cardWindowResizable(self, in); }
	@Override void endPaintBeforeStack(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target) throws xTalkException { CardUI.endPaintBeforeStack(self, stackPath, cardSpec, memData, object, target); }
	@Override void leaveStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String leftKey, OStack saveStack) throws xTalkException { WindowUI.leaveStackWindow(self, stackPath, cardSpec, memData, object, target, leftKey, saveStack); }
	@Override void virusScanStack(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, boolean res) throws xTalkException { ProgramsUI.virusScanStack(self, stackPath, cardSpec, memData, object, target, res); }
	@Override void discardStackWindows(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String leftKey, TTalk.WindowStack resumed, String targetKey) throws xTalkException { WindowUI.discardStackWindows(self, stackPath, cardSpec, memData, object, target, leftKey, resumed, targetKey); }
	@Override void restoreStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String targetKey, TTalk.WindowStack resumed) throws xTalkException { WindowUI.restoreStackWindow(self, stackPath, cardSpec, memData, object, target, targetKey, resumed); }
	@Override void placeStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String targetKey, TTalk.WindowStack resumed, String leftKey) throws xTalkException { WindowUI.placeStackWindow(self, stackPath, cardSpec, memData, object, target, targetKey, resumed, leftKey); }
	@Override void iconOnView(OButton self, int in, boolean noupdate) { PartViews.iconOnView(self, in, noupdate); }
	@Override void iconURIOnView(OButton self, java.net.URI in, boolean noupdate) { PartViews.iconURIOnView(self, in, noupdate); }
	@Override java.awt.image.BufferedImage systemIcon(int id) { return PartViews.systemIcon(id); }
	@Override String getPatternProperty(OPattern op, String prpty) throws xTalkException { return ToolsUI.getPatternProperty(op, prpty); }
	@Override void setPatternProperty(OPattern op, String prpty, String value) throws xTalkException { ToolsUI.setPatternProperty(op, prpty, value); }
	@Override String imageToArray(String data) throws xTalkException { return EditorsUI.imageToArray(data); }
	@Override String arrayToImage(String array, String format) throws xTalkException { return EditorsUI.arrayToImage(array, format); }
	@Override java.awt.image.BufferedImage loadPictureFile(String path) { return WindowUI.loadPictureFile(path); }
	@Override java.awt.image.BufferedImage loadImage(String path, String type) { return CardUI.loadImage(path, type); }
	@Override void writeOsaScript(OsaScript s, XmlOut w, String indentAfter) throws XmlException { s.writeXML(w, indentAfter); }
	@Override boolean writesContentKey(OStack s) { return StackProtection.sealsXml(s); }
	@Override java.awt.image.BufferedImage shapeTexture(byte[] png) { try { return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)); } catch (java.io.IOException e) { return null; } }
	@Override void convertTextToBezier(OShape self) { ShapesUI.convertTextToBezier(self); }
	@Override void fitText(OShape self) { ShapesUI.fitText(self); }
	@Override void build(OShape self) { PartViews.build(self); }
	@Override String num(double v) { return ShapesUI.num(v); }
	@Override void scalePath(OShape self, int w,int h) { ShapesUI.scalePath(self, w, h); }
	@Override void outlineAsPath(OShape self, String value, String k) throws xTalkException { ShapesUI.outlineAsPath(self, value, k); }
	@Override void setShowNameView529(OButton self, boolean in) { PartViews.setShowNameView529(self, in); }
	@Override void setVisibleView371(OButton self, boolean in) { PartViews.setVisibleView371(self, in); }
	@Override void setTextView356(OButton self, String in) { PartViews.setTextView356(self, in); }
	@Override void setTextView284(OField self, String in) { PartViews.setTextView284(self, in); }
	@Override void setTextFontView569(OButton self, String in, javax.swing.JComponent component) { PartViews.setTextFontView569(self, in, component); }
	@Override void setHiliteView402(OButton self, boolean in, OButton sibling) { PartViews.setHiliteView402(self, in, sibling); }
	@Override void applyVisibleView375(OButton self, javax.swing.JComponent component) { PartViews.applyVisibleView375(self, component); }
	@Override void setRectView328(OButton self, int v1, int v2, int v3, int v4, int oldLeft, int oldTop, int oldWidth, int oldHeight) { PartViews.setRectView328(self, v1, v2, v3, v4, oldLeft, oldTop, oldWidth, oldHeight); }
	@Override void setTopLeftView286(OButton self, int h, int v, int oldLeft, int oldTop) { PartViews.setTopLeftView286(self, h, v, oldLeft, oldTop); }
	@Override void applyVisibleView376(OField self) { PartViews.applyVisibleView376(self); }
	@Override void applyVisibleView375(OField self) { PartViews.applyVisibleView375(self); }
	@Override void setRectView213(OField self, int v1, int v2, int v3, int v4, int oldLeft, int oldTop, int oldWidth, int oldHeight) { PartViews.setRectView213(self, v1, v2, v3, v4, oldLeft, oldTop, oldWidth, oldHeight); }
	@Override void setTopLeftView181(OField self, int h, int v, int oldLeft, int oldTop) { PartViews.setTopLeftView181(self, h, v, oldLeft, oldTop); }
	@Override TSound sound() { return SoundsUI.sound(); }
	@Override void stopAllSounds() { SoundsUI.stopAllSounds(); }
	@Override int showScriptErrorDialog(String errorMessage, String statement, int line, OObject object, boolean hasScript, boolean canDebug) { return DialogsUI.showScriptErrorDialog(errorMessage, statement, line, object, hasScript, canDebug); }
	@Override void runOnEventThread(Runnable r) { WindowUI.runOnEventThread(r); }
	@Override java.io.File expandShortHfsNames(java.io.File path) { return FilesUI.expandShortHfsNames(path); }
	@Override String printText(TTalk self, String text, java.awt.Font font) throws xTalkException { return ProgramsUI.printText(self, text, font); }
	@Override java.awt.Point windowOrigin() { return WindowUI.windowOrigin(); }
	@Override boolean setWatcherWindowProperty(OWindow w, String prpty, String value) throws xTalkException { return EditorsUI.setWatcherWindowProperty(w, prpty, value); }
	@Override java.awt.Rectangle messageBoxRect() { return WindowUI.messageBoxRect(); }
	@Override void openPaletteWindow(Rsrc.PlteClass plte, java.awt.image.BufferedImage image, String[] params) { WindowUI.openPaletteWindow(plte, image, params); }
	@Override java.awt.image.BufferedImage normalizePaletteImage(java.awt.image.BufferedImage source, java.awt.Point pictOrigin) { return WindowUI.normalizePaletteImage(source, pictOrigin); }
	@Override boolean restackComponents(OCardBase self) { return PartViews.restackComponents(self); }
	@Override OWindow findPaletteWindow(String name) { return WindowUI.findPaletteWindow(name); }
	@Override boolean isPicture(OWindow self) { return WindowUI.isPicture(self); }
	@Override void Command(OWindow self, String cmdStr) throws xTalkException { WindowUI.Command(self, cmdStr); }
	@Override void setZoomed(OWindow self, boolean in) throws xTalkException { WindowUI.setZoomed(self, in); }
	@Override void Close(OWindow self) { WindowUI.Close(self); }
	@Override java.awt.image.BufferedImage image(OPattern self) { return ToolsUI.image(self); }
	@Override void setImage(OPattern self, java.awt.image.BufferedImage image) { ToolsUI.setImage(self, image); }
	@Override void pause(OObject object, int line, MemoryData memData, boolean error, String errorMessage) throws xTalkException { EditorsUI.pause(object, line, memData, error, errorMessage); }
	@Override boolean scriptEditorVisible() { return EditorsUI.scriptEditorVisible(); }
	@Override void traceSleep() { EditorsUI.traceSleep(); }
	@Override void openEditorAtError(TTalk self, xTalkException e, OObject object, java.util.ArrayList<String> scriptList, int line, MemoryData memData, int mode) throws xTalkException { EditorsUI.openEditorAtError(self, e, object, scriptList, line, memData, mode); }
	@Override void moveCaretInView(TTalk self, String message, String param, MemoryData memData, OField caretField) throws xTalkException { FieldsUI.moveCaretInView(self, message, param, memData, caretField); }
	@Override void openPanelInfo(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OPanel panel) throws xTalkException { DialogsUI.openPanelInfo(self, stringList, typeAry, object, target, memData, result, str, panel); }
	@Override void newButtonView(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OButton obtn) throws xTalkException { PartViews.newButtonView(self, stringList, typeAry, object, target, memData, result, str, obtn); }
	@Override void selectNewButton(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OButton obtn) throws xTalkException { ToolsUI.selectNewButton(self, stringList, typeAry, object, target, memData, result, str, obtn); }
	@Override void newFieldView(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OField ofld) throws xTalkException { PartViews.newFieldView(self, stringList, typeAry, object, target, memData, result, str, ofld); }
	@Override void selectNewField(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OField ofld) throws xTalkException { ToolsUI.selectNewField(self, stringList, typeAry, object, target, memData, result, str, ofld); }
	@Override void openPrinting(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { ProgramsUI.openPrinting(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void closePrinting(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException { ProgramsUI.closePrinting(self, stringList, typeAry, object, target, memData, result, str); }
	@Override void popupBoundsView(OObject obj, String inProperty, String value, OButton btn) throws xTalkException { PartViews.popupBoundsView(obj, inProperty, value, btn); }
	@Override void buttonFontSizeView(OObject obj, String inProperty, String value, OButton btn, int size) throws xTalkException { PartViews.buttonFontSizeView(obj, inProperty, value, btn, size); }
	@Override void buttonFontStyleView(OObject obj, String inProperty, String value, OButton btn, int style) throws xTalkException { PartViews.buttonFontStyleView(obj, inProperty, value, btn, style); }
	@Override void centrePictureWindow(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException { WindowUI.centrePictureWindow(obj, inProperty, value, prpty, btn, fld, cd); }
	@Override void saveLockNow(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException { FilesUI.saveLockNow(obj, inProperty, value, prpty, btn, fld, cd); }
	@Override void clearCardPane(OCardBase self) { PartViews.clearCardPane(self); }
	@Override void dropButtonViews(OCardBase self) { PartViews.dropButtonViews(self); }
	@Override void dropFieldViews(OCardBase self) { PartViews.dropFieldViews(self); }
	@Override Result pictureCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return WindowUI.pictureCommand(message, params, target, memData, isFunc, result); }
	@Override Result paletteCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException { return WindowUI.paletteCommand(message, params, target, memData, isFunc, result); }
	@Override String watcherBarLoc(String inProperty, OObject obj, OObject target, MemoryData memData, String prpty) throws xTalkException { return EditorsUI.watcherBarLoc(inProperty, obj, target, memData, prpty); }
	@Override void showInMessageWatcher(TTalk self, String message, String[] params, OObject object, OObject target, MemoryData memData, Result result, boolean isFunc, boolean isDynamic, boolean isBgroot, int usingCnt) throws xTalkException { EditorsUI.showInMessageWatcher(self, message, params, object, target, memData, result, isFunc, isDynamic, isBgroot, usingCnt); }
	@Override boolean partRedrawnByExternal(OObject part) { return XWindowsUI.partRedrawnByExternal(part); }
	@Override void rebuildComponent(OField self) { PartViews.rebuildComponent(self); }
	@Override void showFont(OField self, java.awt.Font font) { PartViews.showFont(self, font); }
	@Override void showLineWrap(OField self, boolean wrap) { PartViews.showLineWrap(self, wrap); }
	@Override void applyMargin(OField self) { PartViews.applyMargin(self); }
	@Override void updateTextEditable(OField self) { PartViews.updateTextEditable(self); }
	@Override String availableFamily(String... candidates) { return FieldsUI.availableFamily(candidates); }
	@Override String containedFamily(String name) { return FieldsUI.containedFamily(name); }
	@Override void removeFromCardPane(TTalk self, java.util.ArrayList<String> stringList, TTalk.wordType[] typeAry, MemoryData memData, OObject object, OObject target, javax.swing.JComponent comp) throws xTalkException { PartViews.removeFromCardPane(self, stringList, typeAry, memData, object, target, comp); }
	@Override FieldTable table(OField self) { return PartViews.table(self); }
	@Override FieldOutline outline(OField self) { return PartViews.outline(self); }
	@Override void refreshTable(OField self) { PartViews.refreshTable(self); }
	@Override void applyShown(OShape self) { PartViews.applyShown(self); }
	@Override void moveView(OShape self) { PartViews.moveView(self); }
	// what a stack's 68K external asks of the user interface (HyperCard68kCallbacks, as it did itself before P4)
	@Override int showHCAlert(String prompt, String[] buttons) {
		return javax.swing.JOptionPane.showOptionDialog(PCARD.pc,prompt,"",javax.swing.JOptionPane.DEFAULT_OPTION,javax.swing.JOptionPane.PLAIN_MESSAGE,null,buttons,buttons[0]);
	}
	@Override java.io.File chooseExternalFile(String name, java.io.File folder) {
		javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
		if(folder!=null)chooser.setCurrentDirectory(folder);
		chooser.setDialogTitle(name.isEmpty()?"":"Where is "+name+"?");
		return PCARD.showFileChooser(chooser,PCARD.pc,false)==javax.swing.JFileChooser.APPROVE_OPTION?chooser.getSelectedFile():null;
	}
	@Override void notifyUser() {
		if(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=null)return;
		try{if(java.awt.Taskbar.isTaskbarSupported()&&java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)){java.awt.Taskbar.getTaskbar().requestUserAttention(true,false);return;}}
		catch(RuntimeException ignored){}
		java.awt.Toolkit.getDefaultToolkit().beep();
	}
	@Override java.io.File stackFolderOf(java.io.File stackFile) { return GMenuBrowse.stackChooserDirectory(stackFile); }
	@Override void externalChangedPicture(OStack stack, OCardBase owner, java.awt.image.BufferedImage picture) {
		if(stack!=null&&stack.file!=null){
			if(owner.bitmapName==null||!owner.bitmapName.matches(".*\\.png"))owner.bitmapName="BMAP_"+owner.id+".png";
			java.io.File file=new java.io.File(stack.file.getParent(),owner.bitmapName);
			try{StackProtection.writeImage(stack,picture,file);PictureCache.invalidate(file);}catch(java.io.IOException e){e.printStackTrace();}
		}
		//a card that had no picture has no picture layer on show: the card is built again (the new picture shows at once, as
		//HyperCard redraws the card - MtDrawIcon's icon stayed invisible until the card was left and opened again)
		boolean shown=PCARD.pc!=null&&stack!=null&&PCARD.pc.stack==stack&&stack.curCard!=null&&(owner==stack.curCard||owner==stack.curCard.bg);
		if(shown&&(owner.label==null||owner.label.cd!=owner||owner.label.getParent()==null))OCard.reloadCurrentCard();
		else if(shown&&PCARD.pc.mainPane!=null)PCARD.pc.mainPane.repaint();
	}
	@Override int cardMousePresses() { return GUI.mousePresses; }
	@Override boolean cardMouseDown() { return GUI.mouseDown; }
	@Override void abortScriptAfterExternal() { TXcmd.abortScriptAfterExternal = true; }
	@Override OObject externalCallTarget() { return TXcmd.callTarget.get(); }
	@Override boolean cardWindowOpen() { return PCARD.pc != null; }
	@Override boolean tabletAvailable() { return TabletInput.available; }
	@Override boolean tabletPenNear() { return TabletInput.penNear; }
	@Override boolean tabletPenNearEraser() { return TabletInput.penNearEraser; }
	@Override byte[] soundResourceBits(java.io.File audio, String name) {
		try { TSound.NoteInfo note = TSound.noteInfo(audio.getPath(), name);
			return HCResourceExport.sound(SoundData.read(audio), note.baseNote, note.loopStart, note.loopEnd); }
		catch (Exception e) { System.err.println("[68K] cannot convert sound " + audio.getName() + ": " + e.getMessage()); return new byte[0]; }
	}
	@SuppressWarnings("unchecked") @Override java.awt.image.BufferedImage withPaintLayers(java.awt.image.BufferedImage pict, java.util.List<?> layers, int w, int h) {
		for (PaintLayer l : (java.util.List<PaintLayer>) layers) if (l.image != null) { w = Math.max(w, l.image.getWidth()); h = Math.max(h, l.image.getHeight()); }
		return PaintLayers.flatten(pict, (java.util.List<PaintLayer>) layers, w, h);
	}
}

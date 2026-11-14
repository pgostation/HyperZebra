import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.*;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;
import java.awt.RenderingHints;
import java.io.DataInputStream;
import java.nio.charset.Charset;
import java.io.*;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.Frame;
import javax.swing.JFrame;

/** Desktop code moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class WindowUI {
	private WindowUI(){}

	// from TTalk
	static void setMenuBarVisible(boolean visible) {
		TTalk.menuBarShown = visible;
		if(PCARD.pc==null || PCARD.pc.getJMenuBar()==null) return;
		Host.get().runOnEDT(() -> {
			javax.swing.JMenuBar mb = PCARD.pc.getJMenuBar();
			if(mb==null || mb.isVisible()==visible) return;
			int before = PCARD.pc.menuBarHeight();
			mb.setVisible(visible);
			int delta = PCARD.pc.menuBarHeight()-before;
			if(delta!=0 && (PCARD.pc.getExtendedState()&java.awt.Frame.MAXIMIZED_BOTH)==0){
				java.awt.Rectangle r = PCARD.pc.getBounds();
				PCARD.pc.setBounds(r.x, r.y, r.width, r.height+delta);
			}
			PCARD.pc.validate(); //at once, as hide titleBar: the script reads the card window's rect right after
		});
	}

	// from TTalk
	static void setTitleBarVisible(boolean visible) {
		if(PCARD.pc==null) return;
		Host.get().runOnEDT(() -> {
			if(PCARD.pc.isUndecorated()==!visible) return;
			//飾りの有無でinsetsが変わるので、フレームではなくコンテンツ領域の画面位置と
			//サイズを保つ(フレームのboundsをそのまま戻すと、タイトルバーを隠したときに
			//その高さぶんカードウインドウが縦に伸びていた)。
			java.awt.Rectangle bounds = PCARD.pc.getBounds();
			java.awt.Insets before = PCARD.pc.getInsets();
			java.awt.Rectangle content = new java.awt.Rectangle(bounds.x+before.left, bounds.y+before.top,
				bounds.width-before.left-before.right, bounds.height-before.top-before.bottom);
			boolean shown = PCARD.pc.isVisible();
			//the frame's dispose takes its owned windows (Picture/palette windows) with it: they are no closed windows (no
			//closePicture: アイビジぺったん quits HyperCard on closePicture after hide titlebar in openStack) and come back
			java.util.List<java.awt.Window> owned = new ArrayList<java.awt.Window>();
			for(java.awt.Window w : PCARD.pc.getOwnedWindows()) if(w.isVisible()) owned.add(w);
			TTalk.rebuildingCardWindow = true;
			try{ PCARD.pc.dispose(); }
			finally{ TTalk.rebuildingCardWindow = false; }
			if(visible){ PCARD.pc.setShape(null); CardWindow68k.shapeDropped(); } //a shape an external gave the window (CardWindow68k.applyShape) is only for one without a frame
			PCARD.pc.setUndecorated(!visible);
			PCARD.pc.setBounds(bounds);
			if(shown) PCARD.pc.setVisible(true);
			else PCARD.pc.addNotify(); //hidden (hide card window first: 天気部予報): the native frame for its insets, still hidden
			java.awt.Insets after = PCARD.pc.getInsets();
			//the content keeps its place and size: a hidden window used to keep the frame's bounds, 32 pixels too tall without its title bar
			PCARD.pc.setBounds(content.x-after.left, content.y-after.top,
				content.width+after.left+after.right, content.height+after.top+after.bottom);
			//laid out at once: the script reads the card window's rect right after (GG REMIX hideFinder puts black windows
			//at its top/bottom; the card pane still had the old frame's height, so the bottom one covered the card)
			PCARD.pc.validate();
			for(java.awt.Window w : owned) w.setVisible(true);
			GStackWindow.cardWindowRebuilt(); //the activation handed to another stack's window meanwhile is no click on it
		});
	}

	// from TUtil
	static Point getCardOriginOnScreen() {
		if(PCARD.pc.mainPane==null){ //テスト(カードウインドウなし)
			return new Point(PCARD.pc.getX()+PCARD.pc.getInsets().left, PCARD.pc.getY()+PCARD.pc.getInsets().top);
		}
		if(PCARD.pc.mainPane.isShowing()){
			//from the frame's own bounds: the native window's place (getLocationOnScreen) lags a setBounds just made
			//(set the rect of this card moves the window and the script reads the card window's rect right after)
			Point p = javax.swing.SwingUtilities.convertPoint(PCARD.pc.mainPane, 0, 0, PCARD.pc);
			return new Point(PCARD.pc.getX()+p.x, PCARD.pc.getY()+p.y);
		}
		Point origin = TUtil.windowOrigin();
		return new Point(origin.x+PCARD.pc.mainPane.getX(), origin.y+PCARD.pc.mainPane.getY());
	}

	// from TUtil
	static java.awt.Rectangle cardWindowRect() {
		Point origin = getCardOriginOnScreen();
		if(PCARD.pc.mainPane!=null){
			//a window smaller than the card shows it from its scroll: the window starts that far into the card
			Point s = PCARD.pc.cardScroll();
			origin = new Point(origin.x+Host.get().toPhysical(s.x), origin.y+Host.get().toPhysical(s.y));
		}
		int w, h;
		if(PCARD.pc.mainPane!=null){
			java.awt.Rectangle visible = PCARD.pc.mainPane.getVisibleRect();
			w = visible.width; h = visible.height;
			if(w<=0 || h<=0 || !PCARD.pc.mainPane.isShowing()){
				w = HZ.stack()!=null ? Host.get().toPhysical(HZ.stack().width) : PCARD.pc.mainPane.getWidth();
				h = HZ.stack()!=null ? Host.get().toPhysical(HZ.stack().height) : PCARD.pc.mainPane.getHeight();
			}
		}else{
			w = PCARD.pc.getWidth(); h = PCARD.pc.getHeight();
		}
		return new java.awt.Rectangle(origin.x, origin.y, w, h);
	}

	// from TUtil
	static void setCardWindowRect(java.awt.Rectangle r) {
		java.awt.Rectangle now = cardWindowRect();
		java.awt.Rectangle frame = PCARD.pc.getBounds();
		int chromeW = frame.width-now.width, chromeH = frame.height-now.height;
		int w = Math.max(1, r.width), h = Math.max(1, r.height);
		OStack st = HZ.stack();
		if(st!=null && (w!=now.width || h!=now.height)){
			//HyperCard 2.4: the card window shows at most the card, and its width is a multiple of 16 (200 -> 208, 193 -> 192).
			//That size stays the window's own: a later bigger card is shown in it (OStack.windowNominal)
			int lw = Host.get().toLogical(w), lh = Host.get().toLogical(h);
			lw = lw>=st.width ? st.width : Math.max(16, Math.min(st.width, (lw+8)/16*16));
			lh = Math.max(1, Math.min(lh, st.height));
			st.windowNominal = new java.awt.Dimension(lw, lh);
			w = Host.get().toPhysical(lw);
			h = Host.get().toPhysical(lh);
		}
		//laid out at once: the script reads the rect (and sets the scroll within it) right after
		final java.awt.Rectangle b = new java.awt.Rectangle(r.x-(now.x-frame.x), r.y-(now.y-frame.y), w+chromeW, h+chromeH);
		Host.get().runOnEDT(() -> { PCARD.pc.setBounds(b); PCARD.pc.validate(); });
	}

	// from TUtil
	static java.util.List<Object[]> windowEntries() {
		boolean longTitles = TUtil.compatibilityProperty("longWindowTitles").equalsIgnoreCase("true");
		boolean english = PCARD.pc!=null && HZ.stack()!=null && HZ.stack().legacyTextEncoding!=StackTextEncoding.JAPANESE;
		java.util.List<Object[]> list = new java.util.ArrayList<Object[]>();
		//68K externals' windows (Movie XCMD 1.2 and other XWindows) come first, as HyperCard's XCMD windows do
		if(Xcmd68k.hasWindowSessions()) for(Xcmd68k.ExternalWindow w : Xcmd68k.windows()){
			OXWindow xw = new OXWindow(w);
			if(xw.name!=null && !xw.name.isEmpty()) list.add(new Object[]{xw.name, xw});
		}
		if(GMsg.msg!=null) list.add(new Object[]{"Message", OMsg.msg});
		//picture and movie windows are document windows: a hidden one is behind the windoids and palettes, in front of the card
		//window, and one on show is the first window (HC J-2.3 probe Jhug Window Number: a picture opened hidden is window 8 of
		//9, after show window 8 window 1; JHUG Libra Demo: picture … / set the loc of window 8 / show window 8)
		java.util.List<Object[]> documents = new java.util.ArrayList<Object[]>();
		java.util.List<Object[]> shownDocuments = new java.util.ArrayList<Object[]>();
		for(int i=0; i<OWindow.list.size();i++){
			OWindow w = OWindow.list.get(i);
			if(w==OWindow.msgwindow || w==OWindow.cdwindow || w.name==null) continue;
			if(w.isMovie() || (w.gpw!=null && !w.gpw.isPaletteWindow())){ (w.getVisible() ? shownDocuments : documents).add(new Object[]{w.name, w}); continue; }
			String name = w.name;
			if(english && w.dlog instanceof MessageWatcher) name = "Message Watcher";
			else if(english && w.dlog instanceof VariableWatcher) name = "Variable Watcher";
			//HyperCard J-2.3 names them メッセージ表示 / 変数表示 (the window titles here say ウィンドウ too)
			else if(w.dlog instanceof MessageWatcher) name = "メッセージ表示";
			else if(w.dlog instanceof VariableWatcher) name = "変数表示";
			list.add(new Object[]{name, w});
		}
		list.add(new Object[]{OHcWindow.scroll.name, OHcWindow.scroll}); //HyperCard lists its hidden Scroll and FatBits windoids too
		list.add(new Object[]{OHcWindow.fatbits.name, OHcWindow.fatbits});
		if(OPatternWindow.patternwindow!=null) list.add(new Object[]{"Patterns", OPatternWindow.patternwindow});
		if(OToolWindow.toolwindow!=null) list.add(new Object[]{"Tools", OToolWindow.toolwindow});
		list.addAll(documents);
		list.addAll(0, shownDocuments);
		if(PCARD.pc!=null && HZ.stack()!=null){
			String title = longTitles && HZ.stack().path!=null && HZ.stack().cardWindowTitle==null ? TTalk.stackIdentityPath(HZ.stack().path) : HZ.stack().getWindowTitle();
			list.add(new Object[]{title, OWindow.cdwindow});
		}
		//stacks left open in windows of their own (go … in a new window): HyperCard lists them behind the card window
		for(String name : TTalk.windowStackNames()) list.add(new Object[]{name, null});
		return list;
	}

	// from TTalk
	static void scrollSelectionIntoView(OField fld, int offset) {
		if(fld.scrollPane==null || fld.fld==null || fld.style!=5) return;
		final javax.swing.text.JTextComponent area = fld.fld;
		final javax.swing.JScrollBar bar = fld.scrollPane.getVerticalScrollBar();
		Runnable move = () -> {
			try{
				java.awt.geom.Rectangle2D r = area.modelToView2D(Math.min(offset, area.getDocument().getLength()));
				if(r==null){ TTalk.scrollByLineHeight(fld, offset); return; }
				int visible = fld.scrollPane.getViewport().getExtentSize().height;
				int value = bar.getValue();
				int top = (int)Math.floor(r.getY()), bottom = (int)Math.ceil(r.getY()+r.getHeight());
				if(top<value) value = top;
				else if(bottom>value+visible){
					value = bottom-visible;
					//the last line: the field's very end (HC J-2.3: select after last line gives the largest scroll; HyperPiece 2's MaxScr)
					java.awt.geom.Rectangle2D end = area.modelToView2D(area.getDocument().getLength());
					if(end!=null && end.getY()==r.getY()) value = bar.getMaximum()-bar.getVisibleAmount();
				}
				int wanted = value;
				value = Math.max(bar.getMinimum(), Math.min(value, bar.getMaximum()-bar.getVisibleAmount()));
				if(value!=bar.getValue()) bar.setValue(value);
				fld.scroll = MyScrollPane.toLogicalScroll(bar.getValue());
				//the scroll bar still has the range of the text before (text just put, not laid out again): the scroll from
				//the line height (HC J-2.3 probe C Selection, in a full run after other groups used the field)
				if(value<wanted) TTalk.scrollByLineHeight(fld, offset);
			}catch(Exception e){ TTalk.scrollByLineHeight(fld, offset); } //not laid out yet (style just set to scrolling)
		};
		Host.get().runOnEDT(move);
	}

	// from TTalk
	static boolean choosePopupItem(OButton b) {
		String[] items = b.getText().split("\n");
		int[] chosen = {-1};
		java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
		javax.swing.JPopupMenu[] shown = {null};
		Host.get().runOnEDT(() -> {
			if(b.popup==null || PCARD.pc.mainPane==null || !PCARD.pc.mainPane.isShowing()){ closed.countDown(); return; }
			javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
			menu.setLightWeightPopupEnabled(false); //over the picture of a locked screen
			java.awt.Font font = OObject.stackFont(b.textFont, b.textStyle&0x03, b.textSize*Host.get().scale());
			for(int i=0; i<items.length; i++){
				if(items[i].equals("-")){ menu.addSeparator(); continue; }
				JMenuItem mi = new JMenuItem(StackTranslation.popupItem(b, items[i]));
				mi.setFont(font);
				int line = i;
				mi.addActionListener(e -> chosen[0] = line);
				menu.add(mi);
			}
			menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){
				//an item's action comes after the menu has gone: count down behind it
				public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){ SwingUtilities.invokeLater(closed::countDown); }
				public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){}
				public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}
			});
			shown[0] = menu;
			menu.show(PCARD.pc.mainPane, b.popup.getX(), b.popup.getY());
			if(!menu.isVisible()) closed.countDown();
		});
		try{
			closed.await();
		}catch(InterruptedException e){
			Thread.currentThread().interrupt();
			SwingUtilities.invokeLater(() -> { if(shown[0]!=null) shown[0].setVisible(false); });
			return false;
		}
		if(chosen[0]<0) return false;
		b.setSelectedLine(chosen[0]+1);
		return true;
	}

	// from TTalk (instance)
	static boolean authToolDrag(TTalk self, int sx, int sy, int ex, int ey, int[] modKeys, MemoryData memData) throws xTalkException {
		if(PCARD.pc==null || HZ.stack()==null || HZ.stack().curCard==null) return false;
		if(!(AuthTool.tool instanceof ButtonTool || AuthTool.tool instanceof FieldTool)) return false;
		boolean buttons = AuthTool.tool instanceof ButtonTool;
		OObject part;
		if(self.commandKeyOnly(modKeys)){
			OCardBase cdbase = HZ.editBackground ? HZ.stack().curCard.bg : HZ.stack().curCard;
			int newid = cdbase.newPartId();
			//the rectangle takes in the end point's pixel: drag from 300,200 to 330,230 makes 300,200,331,231 (HC J-2.3)
			int l = Math.min(sx, ex), t = Math.min(sy, ey), r = Math.max(sx, ex)+1, b = Math.max(sy, ey)+1;
			if(buttons){
				OButton obtn = new OButton(cdbase, newid);
				obtn.btn = new MyButton(obtn, "");
				cdbase.partsList.add(obtn);
				cdbase.btnList.add(obtn);
				obtn.setRect(l, t, r, b);
				obtn.style = 1; //transparent, as the command-drag of the mouse makes it
				part = obtn;
			}else{
				OField ofld = new OField(cdbase, newid);
				ofld.fld = new MyTextArea("");
				cdbase.partsList.add(ofld);
				cdbase.fldList.add(ofld);
				ofld.setRect(l, t, r, b);
				ofld.style = 1;
				ofld.enabled = false;
				part = ofld;
			}
			cdbase.changed = true;
			OCard.reloadCurrentCard();
			self.ReceiveMessage(buttons ? "newButton" : "newField", new String[0], part, part, memData);
		}else{
			part = self.authToolPartAt(sx, sy, buttons);
			if(part!=null){
				int l = part.left+ex-sx, t = part.top+ey-sy;
				if(part instanceof OButton) ((OButton)part).setRect(l, t, l+part.width, t+part.height);
				else ((OField)part).setRect(l, t, l+part.width, t+part.height);
				if(part.parent instanceof OCardBase) ((OCardBase)part.parent).changed = true;
				OCard.reloadCurrentCard();
			}
		}
		if(buttons){
			ButtonGUI.gui.tgtOBtn = (OButton)part;
			ButtonGUI.gui.target = part==null ? null : ((OButton)part).getComponent();
		}else{
			FieldGUI.gui.tgtOFld = (OField)part;
			FieldGUI.gui.target = part==null ? null : ((OField)part).getComponent();
		}
		if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
		return true;
	}

	// from TTalk (instance)
	static String askWhereIsStack(TTalk self, String ref) {
		if(ref==null || ref.trim().isEmpty() || java.awt.GraphicsEnvironment.isHeadless() || PCARD.pc==null) return null;
		//unattended runs (tests answer error dialogs themselves) get "No such stack." at once; -Dhz.askwhereis=true asks anyway
		if(self.errorDialogForTest!=null && !Boolean.getBoolean("hz.askwhereis")) return null;
		String name = ref.indexOf(':')>=0 ? ref.substring(ref.lastIndexOf(':')+1) : ref;
		if(name.trim().isEmpty()) return null;
		File folder = self.currentStackFolder();
		File chosen;
		while(true){
			javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(){
				@Override protected javax.swing.JDialog createDialog(java.awt.Component parent){
					javax.swing.JDialog d = super.createDialog(parent);
					//application-modal blocked the launcher, where the next disk's image is dropped (夏休み: disk 2)
					d.setModalityType(java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
					return d;
				}
			};
			if(folder!=null) chooser.setCurrentDirectory(folder);
			chooser.setDialogTitle("Where is "+name+"?");
			self.whereIsName = name;
			self.whereIsChooser = chooser;
			int answer;
			try { answer = PCARD.showFileChooser(chooser, PCARD.pc, false); }
			finally { self.whereIsChooser = null; }
			if(answer!=javax.swing.JFileChooser.APPROVE_OPTION) return null;
			chosen = chooser.getSelectedFile();
			if(chosen==null || !chosen.isFile() || DiskImages.mountedFrom(chosen)==null && !DiskImages.recognizes(chosen)) break;
			//a disk image chosen: mounted, then the stack is looked for on it; not there, the dialog shows the disk
			File volume;
			try { volume = DiskImages.mount(chosen, null).folder; }
			catch(IOException|RuntimeException e){
				new GDialog(PCARD.pc, HZ.intl().getDialogText("Can't mount the disk image.")+"\n"+chosen.getName()+"\n"+e.getMessage(), null, "OK", null, null);
				return null;
			}
			File found = self.stackOnDisk(volume, name);
			if(found!=null){ Sandbox.chosenByUser(Sandbox.scriptRequester(), found, false); chosen = found; break; }
			folder = volume;
		}
		if(chosen==null || !self.isStackCandidate(chosen)) return null;
		File dir = chosen.getAbsoluteFile().getParentFile();
		if(dir!=null && !self.searchPathFolders("stacks").contains(dir)){
			MemoryData g = self.globalData;
			String old = "";
			if(g!=null && g.nameList!=null){ int i = g.indexOfName("stacks"); if(i>=0 && i<g.valueList.size() && g.valueList.get(i)!=null) old = g.valueList.get(i); }
			self.setGlobal("stacks", old.isEmpty() ? dir.getPath() : old+"\r"+dir.getPath());
		}
		return self.preferConverted(chosen).getPath();
	}

	// from TUtil
	/** Branches of TUtil.SetProperty2 (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v), ((OWindow)obj).frame.getY(), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v), ((OWindow)obj).gpw.getY(), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else{
					throw new xTalkException("Cannot set left on this object?");
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				//set left of tool window to max(-(width of tool window+3), -left of card window) (umich Stack Dumper 3.0)
				if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
					PCARD.pc.toolbar.tb.setLocation(TUtil.windowOrigin().x+Host.get().toPhysical(v), PCARD.pc.toolbar.tb.getY());
					TUtil.toolWindowPlaced = true;
				}
			}
			else if(0==obj.objectType.compareTo("msg")){
				//set left of msg to 0 (Stack Dumper 3.0: aligned with the card window), as set topLeft
				if(GMsg.msg!=null) GMsg.msg.setLocation(getCardOriginOnScreen().x+Host.get().toPhysical(v), GMsg.msg.getY());
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//no Patterns palette of its own (as set loc)
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.SetProperty2 (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetTop(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(((OWindow)obj).frame.getX(), TUtil.windowOrigin().y+Host.get().toPhysical(v), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(((OWindow)obj).gpw.getX(), TUtil.windowOrigin().y+Host.get().toPhysical(v), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else{
					throw new xTalkException("Cannot set top on this object?");
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
					PCARD.pc.toolbar.tb.setLocation(PCARD.pc.toolbar.tb.getX(), TUtil.windowOrigin().y+Host.get().toPhysical(v));
					TUtil.toolWindowPlaced = true;
				}
			}
			else if(0==obj.objectType.compareTo("msg")){
				//set top of msg to height of card window+13 (Stack Dumper 3.0)
				if(GMsg.msg!=null) GMsg.msg.setLocation(GMsg.msg.getX(), getCardOriginOnScreen().y+Host.get().toPhysical(v));
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//no Patterns palette of its own (as set loc)
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.SetProperty2 (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetTopLeft(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v1), TUtil.windowOrigin().y+Host.get().toPhysical(v2), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v1), TUtil.windowOrigin().y+Host.get().toPhysical(v2), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else if(((OWindow)obj).dlog!=null) TUtil.placeDialogWindow(((OWindow)obj).dlog, v1, v2);
				else{
					throw new xTalkException("Cannot set topleft on this object?");
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				//set topLeft of window "Tools" (ぺいんと+ puts its palettes back where they were): as its loc
				if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
					PCARD.pc.toolbar.tb.setLocation(TUtil.windowOrigin().x+Host.get().toPhysical(v1), TUtil.windowOrigin().y+Host.get().toPhysical(v2));
					TUtil.toolWindowPlaced = true;
				}
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//no Patterns palette of its own (as set loc)
			}
			else if(0==obj.objectType.compareTo("msg")){
				//set the topleft of msg to 20,355 (AOL ProTech; HC J-2.3 probe Aol Msg TopLeft)
				if(GMsg.msg!=null){
					Point cardOrigin = getCardOriginOnScreen();
					GMsg.msg.setBounds(cardOrigin.x+Host.get().toPhysical(v1), cardOrigin.y+Host.get().toPhysical(v2), GMsg.msg.getWidth(), GMsg.msg.getHeight());
				}
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.SetProperty2 (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetLoc(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame==PCARD.pc){
					((OWindow)obj).frame.setBounds(v1,v2, ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v1), TUtil.windowOrigin().y+Host.get().toPhysical(v2), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v1), TUtil.windowOrigin().y+Host.get().toPhysical(v2), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else if(((OWindow)obj).dlog!=null) TUtil.placeDialogWindow(((OWindow)obj).dlog, v1, v2);
			}
			else if(0==obj.objectType.compareTo("msg")){
				if(GMsg.msg!=null){
					Point cardOrigin = getCardOriginOnScreen();
					GMsg.msg.setBounds(cardOrigin.x+Host.get().toPhysical(v1), cardOrigin.y+Host.get().toPhysical(v2),
						GMsg.msg.getWidth(), GMsg.msg.getHeight());
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				//show tool window at 8,72 (ShowDialog XCMD スタック): パレットの左上を画面座標で置く
				if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
					PCARD.pc.toolbar.tb.setLocation(TUtil.windowOrigin().x+Host.get().toPhysical(v1), TUtil.windowOrigin().y+Host.get().toPhysical(v2));
					TUtil.toolWindowPlaced = true;
				}
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//独立したPatternsパレットは無い(show で何もしないのと同じ)
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.SetProperty2 (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetRect(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd, int v1, int v2, int v3, int v4) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame==PCARD.pc){
					((OWindow)obj).frame.setBounds(v1,v2,v3-v1,v4-v2);
				}
				else if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v1),TUtil.windowOrigin().y+Host.get().toPhysical(v2),Host.get().toPhysical(v3-v1),Host.get().toPhysical(v4-v2));
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(v1),TUtil.windowOrigin().y+Host.get().toPhysical(v2),Host.get().toPhysical(v3-v1),Host.get().toPhysical(v4-v2));
				}
				else{
					throw new xTalkException("Cannot set rect on this object?");
				}
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.SetProperty2 (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetZoomed(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				((OWindow)obj).setZoomed(TUtil.propertyBoolean("zoomed", value));
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction2 (the chain asks this in their place): true when one of them matched. */
	static boolean screenFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("clipboard")){
			//the clipboard (HyperCard 2.4; Hyped's Clipboard accessory: put the clipboard into cd fld 1): the text on the
			//clipboard, empty when it holds no text
			String text = "";
			try{
				java.awt.datatransfer.Transferable t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
				if(t!=null && t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)){
					text = ((String)t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor)).replace("\r\n", "\n").replace('\r', '\n');
				}
			}catch(Exception e){
				text = "";
			}
			result.theResult = text;
		}
		else if(0==message.compareTo("fontexists") && isFunc){
			//FontExists(<font name>[,<point size>]): an XFCN of HyperCard 2.4's Home stack that stacks call without carrying it
			//(Living Labyrinth: if not fontExists("Times"); MailStack: if fontExists("Charcoal")). HyperZebra's Home has none, so
			//it is answered here from the fonts of the Font menu (a stack's or Home's own FontExists runs instead when there is one)
			String name = params.length>0 ? params[0].trim() : "";
			boolean found = false;
			if(!name.isEmpty()){
				for(java.awt.Component c : SwingMenus.unwrap(TTalk.fontMenu()).getMenuComponents()){
					if(c instanceof javax.swing.JMenuItem && name.equalsIgnoreCase(((javax.swing.JMenuItem)c).getText())){ found = true; break; }
				}
			}
			result.theResult = Boolean.toString(found);
		}
		else if(0==message.compareTo("screenrect")){
			//カードウィンドウを表示しているモニタの矩形(複数モニタ)
			java.awt.Rectangle r = null;
			if(PCARD.pc!=null && PCARD.pc.isShowing()){
				java.awt.Rectangle win = PCARD.pc.getBounds();
				long best = -1;
				for(java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
					java.awt.Rectangle b = gd.getDefaultConfiguration().getBounds();
					java.awt.Rectangle in = b.intersection(win);
					long area = in.isEmpty() ? 0 : (long)in.width*in.height;
					if(area>best){ best = area; r = b; }
				}
			}
			if(r==null){
				Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
				r = new java.awt.Rectangle(0, 0, d.width, d.height);
			}
			r = TUtil.toLogicalGlobal(r); //the screen the zoomed card window is on, at the card's scale
			TUtil.ExternalFrame frame = TUtil.currentFrame();
			if(frame!=null) r = frame.screen();
			result.theResult = r.x+","+r.y+","+(r.x+r.width)+","+(r.y+r.height);
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction2 (the chain asks this in their place): true when one of them matched. */
	static boolean stacksFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("stacks")){
			//開いているスタックのパス(カードウインドウのスタック、続いて自分のウインドウにある他のスタックを前から順に)。
			//HyperCardと同じくlong nameと同じコロン区切り(ちんちろり〜〜んは":"で区切って名前を取り出す)
			//one line per stack, each with its return (HC J-2.3 probe Objects: "the stacks" ends with return, like the windows)
			StringBuilder stacks = new StringBuilder();
			if(HZ.stack().path!=null) stacks.append(HZ.stack().stackPathName()).append('\n');
			for(String other : TTalk.windowStackPaths()) stacks.append(java.text.Normalizer.normalize(TTalk.macStackPath(other), java.text.Normalizer.Form.NFC)).append('\n');
			result.theResult = stacks.toString();
		}
		else if(0==message.compareTo("suspended")){
			result.theResult = PCARD.pc.isFocused()?"false":"true";
		}
		else return false;
		return true;
	}

	// from TUtil
	/** An if statement of TUtil.setPropertyNow: true when the method is to end there. */
	static boolean cardWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(obj instanceof OWindow && ((OWindow)obj).frame!=null && ((OWindow)obj).frame==PCARD.pc && TUtil.setCardWindowProperty(prpty, value)) return true;
		return false;
	}

	// from TUtil
	/** An if statement of TUtil.setPropertyNow: true when the method is to end there. */
	static boolean msgFontProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(obj instanceof OMsg && (prpty.equals("textsize") || prpty.equals("textfont") || prpty.equals("textstyle"))){
			if(GMsg.area!=null){
				java.awt.Font f = GMsg.area.getFont();
				if(prpty.equals("textsize")) f = f.deriveFont((float)Math.max(1, TUtil.propertyInteger("textSize", value.trim())*Host.get().scale()));
				else if(prpty.equals("textfont")) f = new java.awt.Font(value.trim(), f.getStyle(), f.getSize());
				else f = f.deriveFont(TUtil.textStyleValue(value) & 0x03);
				GMsg.area.setFont(f);
			}
			return true;
		}
		return false;
	}

	// from TUtil
	/** An if statement of TUtil.setPropertyNow: true when the method is to end there. */
	static boolean msgPlaceProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(obj instanceof OMsg && GMsg.msg!=null && (prpty.equals("left") || prpty.equals("top") || prpty.equals("right") || prpty.equals("bottom"))){
			int v = TUtil.propertyInteger(prpty, value.trim());
			java.awt.Rectangle r = TUtil.messageBoxRect();
			int x = prpty.equals("left") ? v : prpty.equals("right") ? v-r.width : r.x;
			int y = prpty.equals("top") ? v : prpty.equals("bottom") ? v-r.height : r.y;
			Point origin = getCardOriginOnScreen();
			GMsg.msg.setLocation(origin.x+Host.get().toPhysical(x), origin.y+Host.get().toPhysical(y));
			return true;
		}
		return false;
	}

	// from TUtil
	/** An if statement of TUtil.setPropertyNow: true when the method is to end there. */
	static boolean pictureWindowGlobalProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(obj instanceof OWindow && ((OWindow)obj).gpw!=null && (prpty.equals("globalloc") || prpty.equals("globalrect"))){
			GPictWindow gpw = ((OWindow)obj).gpw;
			String word = value.trim().toLowerCase(java.util.Locale.ROOT);
			if(prpty.equals("globalloc") && word.matches("[a-z]+")){
				//set the globalLoc of window "splash" to mainscreen (AOL KwikInvoicer) / to card (Simple Sample Font Printer's
				//splash): HyperCard J-2.3 centres the window on the main screen below the menu bar (probe Aol GlobalLoc Word: a
				//95x65 picture at 352,277 on 800x600) for card, main, deepest, largest and any other word alike, also with the card
				//window moved to 0,40; "screen" leaves it where it is. card here = the screen the card window is on
				if(word.equals("screen")) return true;
				java.awt.Rectangle s = word.equals("card") ? TUtil.cardWindowScreenArea() : null;
				if(s==null) s = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
				gpw.setLocation(s.x+(s.width-gpw.getWidth())/2, s.y+(s.height-gpw.getHeight())/2);
				return true;
			}
			String[] items = value.split(",");
			int[] v = new int[items.length];
			try{ for(int i=0; i<items.length; i++) v[i] = (int)Double.parseDouble(items[i].trim()); }
			catch(NumberFormatException e){ throw new xTalkException("A number is required here"); }
			if(prpty.equals("globalloc")){
				if(v.length!=2) throw new xTalkException("Coordinates are required here");
				gpw.setLocation(Host.get().toPhysical(v[0]), Host.get().toPhysical(v[1]));
			}else{
				if(v.length!=4) throw new xTalkException("A rectangle is required here");
				gpw.setBounds(TUtil.belowHostMenuBar(new java.awt.Rectangle(Host.get().toPhysical(v[0]), Host.get().toPhysical(v[1]),
					Math.max(1,Host.get().toPhysical(v[2]-v[0])), Math.max(1,Host.get().toPhysical(v[3]-v[1])))));
			}
			return true;
		}
		return false;
	}

	// from TUtil
	/** An if statement of TUtil.setPropertyNow: true when the method is to end there. */
	static boolean paletteWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(obj instanceof OWindow && ((OWindow)obj).gpw!=null && ((OWindow)obj).gpw.isPaletteWindow() && prpty.equals("hilitedbutton")){
			//set the hilitedButton of window "X" to n (0でどれも点灯しない)。空も0 (HC 2.4 / J-2.3 probe Hcde Err Hilited Empty:
			//no error; TimeCache 1.6 sets it to palHi before a button was ever clicked)
			((OWindow)obj).gpw.setHilitedButton(value.trim().isEmpty() ? 0 : TUtil.propertyInteger("hilitedButton", value));
			return true;
		}
		return false;
	}

	// from TUtil
	/** An if statement of TUtil.setPropertyNow: true when the method is to end there. */
	static boolean movieWindowProperty(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(obj instanceof OWindow && ((OWindow)obj).movieHandle!=null){
			//set the loop / rate / soundVolume of movie N (HyperCard 2.4); currTime and audioLevel are below
			String h = ((OWindow)obj).movieHandle;
			if(prpty.equals("loop")){ TQTMovie.setLoop(h, TUtil.propertyBoolean("loop", value)); return true; }
			if(prpty.equals("rate")){
				double r;
				try{ r = Double.parseDouble(value.trim()); }catch(NumberFormatException e){ throw new xTalkException("A number is required here"); }
				TQTMovie.setRate(h, r); return true;
			}
			if(prpty.equals("soundvolume") || prpty.equals("volume")){ TQTMovie.setVolume(h, TUtil.propertyInteger("soundVolume", value)); return true; }
			if(prpty.equals("loopispalindrome") || prpty.equals("timeformat") || prpty.equals("sendmousewithinmsg") ||
				prpty.equals("closeonfinish") || prpty.equals("dragenabled") || prpty.equals("badge") || prpty.equals("fastidle") ||
				prpty.equals("callbackmsg") || prpty.equals("passmovieclick") || prpty.equals("dontErase".toLowerCase()) ||
				//the controller bar and play selection (Earth 〜紅玉の森〜: set controllerVisible of window TheMovieName to false):
				//HyperZebra's movie windows show no controller bar
				prpty.equals("controllervisible") || prpty.equals("playselection") || prpty.equals("selectionstart") ||
				prpty.equals("selectionend") || prpty.equals("highlight")) return true; //accepted, no effect here
		}
		return false;
	}

	// from TUtil
	/** Branches of TUtil.setPropertyNow (the chain asks this in their place): true when one of them matched. */
	static boolean windowsSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(0==obj.objectType.compareTo("msg")){
				boolean b = value.equalsIgnoreCase("true");
				GMsg.msg.setVisible(b);
				if(b && GMsg.shownByUser()){ //スクリプト中のshow msgではカードのキー入力を奪わない
					GMsg.msg.requestFocus();
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				if(HZ.stack()!=null && PCARD.pc.toolbar!=null){
					//スクリプトが loc を決めた(show tool window at h,v)ときはその位置のまま
					if(!TUtil.toolWindowPlaced) PCARD.pc.toolbar.tb.setBounds(Math.max(0,PCARD.pc.getX()-GToolBar.WINDOW_WIDTH-2),
						PCARD.pc.getY()+24,GToolBar.WINDOW_WIDTH,GToolBar.WINDOW_HEIGHT);
					TUtil.toolWindowPlaced = false;
					PCARD.pc.toolbar.tb.setVisible(value.equalsIgnoreCase("true"));
				}
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//本物のHyperCardのような独立したPatternsパレットは無いため何もしない
				//(エラーにはしない)
			}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.setPropertyNow (the chain asks this in their place): true when one of them matched. */
	static boolean windowSetVisible(OObject obj, String inProperty, String value, String prpty) throws xTalkException {
		if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					boolean show = value.equalsIgnoreCase("true");
					//hide window (the short name of this stack) while the mouse is down (Dot Block): the hidden window never
					//gets the mouse-up, so "wait until the mouse is up" would never end
					if(!show && ((OWindow)obj).frame==PCARD.pc) GUI.mouseDown = false;
					((OWindow)obj).frame.setVisible(show);
				}
				else if(((OWindow)obj).dlog!=null){
					((OWindow)obj).dlog.setVisible(value.equalsIgnoreCase("true"));
					if(((OWindow)obj).dlog == MessageWatcher.watcherWindow){
						MessageWatcher.watcherWindow.setTable(TTalk.talk.messageRingArray, TTalk.talk.objectRingArray, TTalk.talk.ringCnt);
					}
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setVisible(value.equalsIgnoreCase("true"));
				}
				else if(((OWindow)obj).movieHandle!=null){
					//音だけの Movie(BGM の .mov)には表示するウィンドウが無い: 状態だけ覚える(Earth 〜紅玉の森〜: show window TheMovieName)
					obj.setVisible(TUtil.propertyBoolean("visible", value));
				}
				else{
					throw new xTalkException("Cannot set visible on this object?");
				}
			}
		else return false;
		return true;
	}

	// from TUtil
	static String getPictureWindowProperty(GPictWindow gpw, String prpty){
		java.awt.Rectangle r = TUtil.pictureWindowRect(gpw);
		switch(prpty){
			case "left": return Integer.toString(r.x);
			case "top": return Integer.toString(r.y);
			case "right": return Integer.toString(r.x+r.width);
			case "bottom": return Integer.toString(r.y+r.height);
			case "topleft": return r.x+","+r.y;
			case "botright": case "bottomright": return (r.x+r.width)+","+(r.y+r.height);
			case "loc": case "location": return r.x+","+r.y; //the loc of a window is its top-left corner (HyperTalk Reference), as set loc puts it
			case "rect": case "rectangle": return r.x+","+r.y+","+(r.x+r.width)+","+(r.y+r.height);
			case "width": return Integer.toString(r.width);
			case "height": return Integer.toString(r.height);
			case "visible": return Boolean.toString(gpw.isVisible());
			case "dithering": return Boolean.toString(gpw.dithering);
			//the size of the picture itself, whatever the window's rect (HyperCard J-2.3; G-zone ! sizes its cards by them)
			case "picturewidth": return gpw.label!=null && gpw.label.bi!=null ? Integer.toString(gpw.label.bi.getWidth()) : Integer.toString(r.width);
			case "pictureheight": return gpw.label!=null && gpw.label.bi!=null ? Integer.toString(gpw.label.bi.getHeight()) : Integer.toString(r.height);
			case "globalloc": return Host.get().toLogical(gpw.getX())+","+Host.get().toLogical(gpw.getY()); //screen coordinates (HyperCard 2.2 picture windows), as the screenRect's
			case "globalrect": return Host.get().toLogical(gpw.getX())+","+Host.get().toLogical(gpw.getY())+","+(Host.get().toLogical(gpw.getX())+r.width)+","+(Host.get().toLogical(gpw.getY())+r.height);
		}
		if(gpw.isPaletteWindow()){
			switch(prpty){
				case "hilitedbutton": return Integer.toString(gpw.getHilitedButton());
				case "commands": return gpw.paletteCommands();
				case "buttoncount": return Integer.toString(gpw.paletteButtonCount());
			}
		}
		return null;
	}

	// from OStack (instance)
    static void setNewBounds(OStack self) {
    	Rectangle r = HZ.stack().pcard.getBounds();
		//Windows/Linuxではメニューバーがウインドウ内(カードの上)にあるので、その高さも足す(足りないとカードの下端が欠ける)
		HZ.stack().pcard.setBounds(r.x, r.y, Host.get().toPhysical(self.windowWidth())+PCARD.pc.toolbar.getTWidth()+self.pcard.chromeWidth(), Host.get().toPhysical(self.windowHeight())+PCARD.pc.toolbar.getTHeight()+self.pcard.chromeTop()+self.pcard.chromeBottom());
    }

	// from OStack (instance)
	/** Lines of OStack.setRect (the core calls this in their place). */
	static void cardWindowToCardSize(OStack self, int v1, int v2, int v3, int v4) {
		Host.get().runOnEDT(() -> {
			java.awt.Rectangle r = self.pcard.getBounds();
			int extraW = 0, extraH = 0;
			if(PCARD.pc!=null && PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){ extraW = PCARD.pc.toolbar.getTWidth(); extraH = PCARD.pc.toolbar.getTHeight(); }
			int w = Host.get().toPhysical(self.windowWidth())+extraW+self.pcard.chromeWidth();
			int h = Host.get().toPhysical(self.windowHeight())+extraH+self.pcard.chromeTop()+self.pcard.chromeBottom();
			int x = r.x+r.width/2-w/2, y = r.y+r.height/2-h/2;
			if(self.pcard.isShowing()){
				java.awt.GraphicsConfiguration gc = self.pcard.getGraphicsConfiguration();
				if(gc!=null){
					java.awt.Rectangle sb = gc.getBounds();
					java.awt.Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
					int sl = sb.x+in.left, st = sb.y+in.top, sr = sb.x+sb.width-in.right, sbm = sb.y+sb.height-in.bottom;
					int margin = Host.get().toPhysical(16);
					x = Math.max(sl+margin, Math.min(x, sr-margin-w));
					x = Math.max(sl, x);
					y = Math.max(st, Math.min(y, sbm-h));
					y = Math.max(st, y);
				}
			}
			self.pcard.setBounds(x, y, w, h);
			self.pcard.validate(); //laid out at once: the script reads the card window's rect right after
			if(MyLabel.cdlabel!=null) MyLabel.cdlabel.setBounds(0, 0, self.width, self.height);
			if(MyLabel.bglabel!=null) MyLabel.bglabel.setBounds(0, 0, self.width, self.height);
			if(self.pcard.mainPane!=null) self.pcard.mainPane.repaint();
		});
	}

	// from OStack (instance)
	/** Lines of OStack.setTopLeft (the core calls this in their place). */
	static void cardWindowToTopLeft(OStack self, int h, int v) {
		self.pcard.setBounds(self.left, self.top, Host.get().toPhysical(self.width)+self.pcard.chromeWidth(), Host.get().toPhysical(self.height)+self.pcard.chromeTop()+self.pcard.chromeBottom());
	}

	// from OStack (instance)
	/** Lines of OStack.setScroll (the core calls this in their place). */
	static void cardWindowScrolled(OStack self, int x, int y) {
		Host.get().runOnEDT(() -> {
			java.awt.Container view = self.pcard.mainPane.getParent();
			if(view==null) return;
			view.invalidate();
			view.validate();
			self.pcard.mainPane.repaint();
		});
	}

	// from OStack (instance)
	/** Lines of OStack.setName (the core calls this in their place). */
	static void cardWindowTitled(OStack self, String in) {
		if(self.pcard!=null && self.pcard.stack==self) self.pcard.setTitle(self.name);
	}

	// from OStack (instance)
	/** Lines of OStack.setResizeable (the core calls this in their place). */
	static void cardWindowResizable(OStack self, Boolean in) {
		self.pcard.setResizable(self.resizable); 
	}

	// from TTalk (instance)
	/** Lines of TTalk.goToStack2 (the core calls this in their place). */
	static void leaveStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String leftKey, OStack saveStack) throws xTalkException {
		if(leftKey!=null) GStackWindow.leave(leftKey, saveStack.getTitleName());
	}

	// from TTalk (instance)
	/** Lines of TTalk.goToStack2 (the core calls this in their place). */
	static void discardStackWindows(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String leftKey, TTalk.WindowStack resumed, String targetKey) throws xTalkException {
			GStackWindow.discard(leftKey);
			if(resumed!=null && !new File(resumed.path).exists()){ self.forgetWindowStack(targetKey); GStackWindow.discard(targetKey); } //gone (moved or deleted)
	}

	// from TTalk (instance)
	/** Lines of TTalk.goToStack2 (the core calls this in their place). */
	static void restoreStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String targetKey, TTalk.WindowStack resumed) throws xTalkException {
			java.awt.Point place = GStackWindow.cardWindowPlace(targetKey);
			self.restoreWindowLocation(place!=null ? place : resumed.location);
	}

	// from TTalk (instance)
	/** Lines of TTalk.goToStack2 (the core calls this in their place). */
	static void placeStackWindow(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, String targetKey, TTalk.WindowStack resumed, String leftKey) throws xTalkException {
		if(resumed!=null) GStackWindow.broughtBack(targetKey);
		else if(leftKey!=null) GStackWindow.cascade(); //a new window: not exactly on top of the one left
	}

	// from TUtil
	static BufferedImage loadPictureFile(String path){
		File f = new File(path);
		if(!f.isFile()) return null;
		try{
			BufferedImage bi = ImageIO.read(f);
			if(bi!=null) return bi;
		}catch(IOException | RuntimeException e){ /* not an image Java knows */ }
		try{ return PictureFile.loadPICT(path); }
		catch(RuntimeException e){ return null; } //a file that is not a PICT parses to nonsense sizes
	}

	// from TTalk
	static void runOnEventThread(Runnable r){
		if(javax.swing.SwingUtilities.isEventDispatchThread()){ r.run(); return; }
		try{ javax.swing.SwingUtilities.invokeAndWait(r); }
		catch(InterruptedException e){ Thread.currentThread().interrupt(); }
		catch(java.lang.reflect.InvocationTargetException e){ e.getCause().printStackTrace(); }
	}

	// from TUtil
	static Point windowOrigin() {
		return new Point(PCARD.pc.getX()+PCARD.pc.getInsets().left, PCARD.pc.getY()+PCARD.pc.chromeTop());
	}

	// from TUtil
	static java.awt.Rectangle messageBoxRect(){
		Point origin = getCardOriginOnScreen();
		//the text box without the title bar, as big as HyperCard's for the scripts that lay out around it (HC J-2.3: 472 by 35)
		return new java.awt.Rectangle(Host.get().toLogical(GMsg.msg.getX()-origin.x), Host.get().toLogical(GMsg.msg.getY()-origin.y), 472, 35);
	}

	// from TUtil
	static void openPaletteWindow(Rsrc.PlteClass plte, BufferedImage image, String[] params) {
		//ウィンドウ作成(見た目とボタンはPLTEの内容からGPictWindowが組み立てる)
		GPictWindow gpw = new GPictWindow(PCARD.pc, plte, image);
		OWindow window = new OWindow(gpw);
		//位置はカードの左上基準。省略時はHyperCardと同じく10,20
		Point location = params.length>=2 ? TUtil.parsePaletteLocation(params[1]) : null;
		if(location==null) location = new Point(10,20);
		gpw.setLocation(TUtil.paletteLocationOnScreen(
			getCardOriginOnScreen(), new Point(Host.get().toPhysical(location.x), Host.get().toPhysical(location.y))));
		gpw.setVisible(true);
		if(HZ.stack().curCard!=null){
			TTalk.CallMessage("openPalette", new String[]{params[0], Integer.toString(window.id)}, HZ.stack().curCard);
		}
	}

	// from TUtil
	static BufferedImage normalizePaletteImage(BufferedImage source, Point pictOrigin) {
		if(source==null || pictOrigin==null || pictOrigin.x<=0 && pictOrigin.y<=0 ||
			pictOrigin.x<0 || pictOrigin.y<0 || pictOrigin.x>=source.getWidth() ||
			pictOrigin.y>=source.getHeight()) return source;

		int minX = source.getWidth();
		int minY = source.getHeight();
		for(int y=0; y<source.getHeight(); y++){
			for(int x=0; x<source.getWidth(); x++){
				if((source.getRGB(x, y)>>>24)!=0){
					if(x<minX) minX=x;
					if(y<minY) minY=y;
				}
			}
		}
		// Do not crop an already-normalized PICT merely because its PLTE
		// resource uses absolute coordinates for buttons.
		if(minX<pictOrigin.x || minY<pictOrigin.y) return source;

		int width = source.getWidth()-pictOrigin.x;
		int height = source.getHeight()-pictOrigin.y;
		BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D graphics = normalized.createGraphics();
		graphics.drawImage(source, -pictOrigin.x, -pictOrigin.y, null);
		graphics.dispose();
		return normalized;
	}

	// from OObject
	static OWindow findPaletteWindow(String name) {
		for(int i=OWindow.list.size()-1; i>=0; i--){
			OWindow window = OWindow.list.get(i);
			if(window.gpw==null) continue;
			if(window.gpw.isPaletteWindow() && !window.gpw.isDisplayable()){
				OWindow.list.remove(i);
				continue;
			}
			if(GPictWindow.samePaletteName(window.gpw.isPaletteWindow(), window.name, name)){
				return window;
			}
		}
		return null;
	}

	// from OObject (instance)
	static boolean isPicture(OWindow self) { return self.movieHandle==null && self.gpw!=null && !self.gpw.isPaletteWindow() && self.gpw.isDisplayable(); }

	// from OObject (instance)
	static void Command(OWindow self, String cmdStr) throws xTalkException {
		if(self.movieHandle!=null){
			//send "play" / "pause" / "rewind" / "stepFwd" / "stepRev" / "idle" to movie N (HyperCard 2.4 Movie XCMD commands)
			String c = cmdStr.trim();
			int sp = c.indexOf(' ');
			String verb = (sp<0 ? c : c.substring(0,sp)).toLowerCase(java.util.Locale.ROOT);
			switch(verb){
				case "play": TQTMovie.play(self.movieHandle); break;
				case "pause": TQTMovie.pause(self.movieHandle); break;
				case "rewind": TQTMovie.rewind(self.movieHandle); break;
				case "stepfwd": TQTMovie.step(self.movieHandle, 1); break;
				case "steprev": TQTMovie.step(self.movieHandle, -1); break;
				case "idle": TQTMovie.idle(self.movieHandle); break;
				case "resizetomovie": case "preroll": case "showposter": case "cancelaction": break; //nothing to do here
				default: System.out.println("Movie window: command not handled here: "+cmdStr); break; //findText, nudge, rotate…: the movie shows without them
			}
			return;
		}
		if(self.mp3!=null){
			if(cmdStr.equalsIgnoreCase("Play")){
				self.mp3.mp3play();
			}
		}
	}

	// from OObject (instance)
	static void setZoomed(OWindow self, boolean in) throws xTalkException {
		if(self != self.cdwindow || self.frame == null || self.frame != PCARD.pc){
			throw new xTalkException("Cannot set zoomed on this window");
		}
		if(in == self.zoomed) return;

		if(in){
			self.unzoomedBounds = new Rectangle(self.frame.getBounds());
			self.frame.setExtendedState(Frame.NORMAL);
			if(HZ.stack()!=null) HZ.stack().windowNominal = null; //zoomed: the whole card
			PCARD.pc.setNewBounds();
			self.zoomed = true;
		}
		else{
			self.frame.setExtendedState(Frame.NORMAL);
			if(self.unzoomedBounds != null){
				self.frame.setBounds(self.unzoomedBounds);
			}
			self.zoomed = false;
		}
	}

	// from OObject (instance)
	static void Close(OWindow self) {
		if(self==self.cdwindow){
			//close window "スタック名" / close card window: スタックを閉じる(クローズボックスと同じ経路)
			PCARD.closeStackFromCloseBox();
			return;
		}
		if(self.movieHandle!=null){
			UiHost.get().tQTMovieDispose(self.movieHandle); //closes the picture window too
			self.list.remove(self);
			return;
		}
		if(self.gpw!=null){
			if(self.gpw.isPaletteWindow()) self.gpw.closePalette(); //closePaletteを送る
			else { self.gpw.dispose(); self.list.remove(self); }
			return;
		}
		if(self.mp3!=null){
			self.mp3.mp3stop();
	    	self.list.remove(self);
		}
	}

	// from TUtil
	/** Lines of TUtil.SetProperty2 (the core calls this in their place). */
	static void centrePictureWindow(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException {
					GPictWindow gpw = ((OWindow)obj).gpw;
					java.awt.Rectangle screen = PCARD.pc.getGraphicsConfiguration()!=null ? PCARD.pc.getGraphicsConfiguration().getBounds() : new java.awt.Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
					gpw.setLocation(screen.x+(screen.width-gpw.getWidth())/2, screen.y+(screen.height-gpw.getHeight())/2);
	}

	// from TUtil
	/** A block of TUtil.CallSystemMessage that always returns (the core keeps its condition). */
	static Result pictureCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
			//HomeスタックのXCMD
			//no source (picture "部品1",,rect,false: まさの冒険's ending): HyperCard's default is a file; a resource of that
			//name is looked for after it, as HyperZebra did before
			boolean defaultSource = !(params.length>=2 && params[1].trim().length()>0);
			String typeStr = defaultSource ? "file" : params[1];
			String windowType = "rect";
			if(params.length>=3 && params[2].length()>0){
				windowType = params[2];
			}
			boolean visible = true;
			if(params.length>=4 && params[3].length()>0){
				visible = params[3].equalsIgnoreCase("true");
			}
			BufferedImage bi = null;
			String windowName = params[0];
			if(defaultSource){
				File around = UiHost.get().stackBundleFolderAround(HZ.stack().file);
				File beside = new File(around, params[0]);
				if(!beside.isFile()){ File other = TTalk.expandShortHfsNames(beside); if(other!=null) beside = other; }
				if(!beside.isFile() && !new File(TTalk.convertFromMacPath(params[0])).isFile()) typeStr = "resource";
			}
			if(typeStr.equalsIgnoreCase("resource")){
				//リソースを探す(名前、無ければID)。ウィンドウ名はリソース名(無名ならID)。
				//picture 128,resourceの後でそのウィンドウを操作する場合がある
				//名前で見つけたPICTはそのまま使う(IDで引き直すと、Homeの名前とこのスタックのIDが重なったとき別の絵になる)
				Rsrc rsrc = HZ.stack().rsrc;
				Rsrc.rsrcClass named = rsrc.findByNameAll(params[0],"PICT");
				if(named!=null){
					bi = rsrc.getImage(params[0],"PICT"); //the window keeps the name as the script wrote it (HC J-2.3: openPicture "sutame" for PICT "Sutame")
				}
				else if(params[0].trim().matches("-?[0-9]+")){
					int rsrcid = Integer.parseInt(params[0].trim());
					bi = rsrc.getImage(rsrcid,"PICT");
					String rsrcName = rsrc.getNameAll(rsrcid,"PICT");
					if(rsrcName!=null && rsrcName.length()>0) windowName = rsrcName;
					//a PICT without a name: the window is "PICT ID n" (Power Tools Resource Mover "View")
					else if(bi!=null) windowName = "PICT ID "+rsrcid;
				}
			}
			else if(typeStr.equalsIgnoreCase("clipboard")){
				//picture "Clipboard","clipboard": the picture on the clipboard (Readymade Buttons)
				try{
					java.awt.datatransfer.Transferable t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
					if(t!=null && t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)){
						java.awt.Image img = (java.awt.Image)t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
						if(img instanceof BufferedImage) bi = (BufferedImage)img;
						else if(img!=null && img.getWidth(null)>0){
							bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
							java.awt.Graphics2D g = bi.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose();
						}
					}
				}catch(Exception e){ bi = null; }
			}
			else if(typeStr.equalsIgnoreCase("file")){
				//ファイルを探す(スタックのフォルダ、次に指定されたパスそのもの)。PICT でも PNG/JPEG/GIF でもよい
				File around = UiHost.get().stackBundleFolderAround(HZ.stack().file); //beside the stack (a stack file's folder, not its working folder)
				String path = (around+File.separator+params[0]);
				if(!new File(path).exists()){
					//the name in the other encoding (BRAVE: "picture "レオン",file" for the host file "ÉåÉIÉì", its Shift-JIS bytes read as Mac OS Roman)
					File other = TTalk.expandShortHfsNames(new File(path));
					if(other!=null && other.isFile()) path = other.getPath();
				}
				bi = loadPictureFile(path);
				if(bi==null) bi = loadPictureFile(TTalk.convertFromMacPath(params[0]));
				//":fix:Flower.pic": a partial path from the folder open file uses (UDI UxGetFileIcon demo buttons)
				if(bi==null) bi = loadPictureFile(TTalk.resolveFilePath(params[0]));
				//HyperCard's search path for documents (A-OK!: ":A-OK! Graphics:" in the global documents, picture "Earth Path Indicator",file)
				if(bi==null) for(File folder : TTalk.searchPathFolders("documents")){
					File found = new File(folder, params[0]);
					if(found.isFile()){ bi = loadPictureFile(found.getPath()); if(bi!=null) break; }
				}
				//ウィンドウ名はファイル名(パスは含めない)
				String fileName = new File(TTalk.convertFromMacPath(params[0])).getName();
				if(!fileName.isEmpty()) windowName = fileName;
			}
			if(bi!=null){
				//the text in the picture translated (PictureTranslation) when the stack is
				if(!typeStr.equalsIgnoreCase("clipboard")) bi = UiHost.get().pictureTranslationShown(HZ.stack(), bi);
				GPictWindow gpw = new GPictWindow(PCARD.pc, windowName, bi, windowType, visible);
				gpw.enablePictureMessages();
				new OWindow(gpw);
				//openPicture <window name> before the next statement (HyperCard J-2.3 measured; Express Yourself keeps the name
				//its on openPicture is given and moves that window right after the picture command)
				GPictWindow.sendPictureMessage("openPicture", windowName);
			}
			//Picture XCMD: a picture it cannot show sets the result (Power Tools Resource Mover "View",
			//Readymade Buttons: if "Couldn't display picture" is in the result)
			else result.theResult = "Couldn't display picture.";
			result.ret = 0;
			return result;
		}

	// from TUtil
	/** A block of TUtil.CallSystemMessage that always returns (the core keeps its condition). */
	static Result paletteCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
			//HomeスタックのXCMD
			if(params.length<1 || params[0].length()==0){
				throw new xTalkException("palette requires a palette name");
			}
			OWindow existingWindow = OWindow.findPaletteWindow(params[0]);
			if(existingWindow!=null){
				if(params.length>=2){
					Point location = TUtil.parsePaletteLocation(params[1]);
					//locationはカード基準の論理座標なので、実画面座標に足す前にscale()倍する。
					if(location!=null){
						existingWindow.gpw.setLocation(TUtil.paletteLocationOnScreen(
							getCardOriginOnScreen(), new Point(Host.get().toPhysical(location.x), Host.get().toPhysical(location.y))));
					}
				}
				existingWindow.gpw.setVisible(true);
				existingWindow.gpw.toFront();
				result.ret = 0;
				return result;
			}
			//PLTEリソースを探す
			boolean found = false;
			for(int i=0; i<HZ.stack().rsrc.plteList.size(); i++){
				Rsrc.PlteClass plte = HZ.stack().rsrc.plteList.get(i);
				if(plte.name.equalsIgnoreCase(params[0])){
					BufferedImage bi = HZ.stack().rsrc.getImage(plte.pictId,"PICT");
					if(bi==null){
						bi = HZ.stack().rsrc.getImage(plte.id,"PICT");
					}
					if(bi!=null){
						bi = UiHost.get().pictureTranslationShown(HZ.stack(), bi); //its text translated when the stack is
						// Some PICT resources retain their QuickDraw origin as transparent
						// top/left canvas space, while others are already normalized.
						openPaletteWindow(plte, normalizePaletteImage(bi, plte.pictHV), params);
					}
					else{
						result.theResult = "Error: PICT not found.";
					}
					found = true;
					break;
				}
			}
			//palette "Navigator" (MailReader and ~120 other stacks): HyperCard's own PLTE 128, for stacks without one
			if(!found && params[0].trim().equalsIgnoreCase(NavigatorPalette.NAME))
				openPaletteWindow(NavigatorPalette.plte(HZ.stack().rsrc), NavigatorPalette.image(), params);
			result.ret = 0;
			return result;
		}
}

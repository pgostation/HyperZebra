import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

class PCARDFrame extends JFrame {
	private static final long serialVersionUID = 1L;

	static PCARD pc=null;

	/** The height the menu bar takes inside this frame: 0 with macOS's screen menu bar, the bar's own height
	 *  (its preferred height before the first layout) on Windows/Linux, where it sits above the card. */
	int menuBarHeight(){
		javax.swing.JMenuBar mb = getJMenuBar();
		if(mb==null || !mb.isVisible()) return 0; //hide menuBar
		int h = mb.getHeight();
		if(h==0 && !GMenu.isMacOSX()) h = mb.getPreferredSize().height;
		return h;
	}
	/** Everything above the card in the window: the title bar (insets) and the in-frame menu bar. */
	int chromeTop(){ return getInsets().top+menuBarHeight(); }
	/** The frame's left and right borders: 0 on the Mac, but a Windows frame has (partly invisible) borders of 8 pixels on
	 *  each side and below, which used to cut the right and bottom edges of the card off. */
	int chromeWidth(){ java.awt.Insets i = getInsets(); return i.left+i.right; }
	/** The frame's bottom border (see {@link #chromeWidth}). */
	int chromeBottom(){ return getInsets().bottom; }

	static boolean useGrid = true;
	static int gridSize = 1;
	MyPanel mainPane;
	OStack stack;
	static OStack home;
	//ペイントの表示倍率(FatBits/Zoom)。1が実寸。8がHyperCardのFatBits。整数でなくてもよい
	//(Zoom In/Outは1.5倍や3倍も通る)ので、画面座標との換算はPaintTool.effectiveBit()/
	//viewLeft()/toPhysical()を通す。IconEditorは自分のズーム(setZoom)で整数倍だけ使う。
	float bit = 1;
	float bitLeft;
	float bitTop;
	toolInterface tool;
	CPButton fore;
	CPButton back;
	GradButton grad;
	PatButton pat;
	TransButton trans;
	boolean fill;
	AffineTransform selectaf;
	int blendMode;
	int blendLevel = 100;
	
	//ペイント(役割分担の詳細はPaintTool.javaの冒頭コメント参照)
	BufferedImage mainImg;   //カードピクチャ
	BufferedImage bgImg;     //バックグラウンドピクチャ
	final PaintUndo paintHistory = new PaintUndo(); //複数段のUndo/Redo(差分矩形+浮動選択の複製)
	BufferedImage previewBuf; //ストローク中の一時レイヤー(半透明ブラシ、消しゴム、図形プレビュー)
	BufferedImage emptyPreview; //全面が透明だと分かっているときのpreviewBuf(PaintTool.beginPreviewが消さずに済む)
	boolean previewVisible;   //previewBufを画面に重ねるか
	float previewAlpha = 1.0f; //previewBufを重ねるときの不透明度
	FloatingSelection floating; //浮動選択(矩形選択・投げなわ・スマート選択・ペーストが持ち上げた絵)
	//編集中のレイヤー(0=Card Paint/Background Paintそのもの、n=追加レイヤーのn番目。PaintLayer参照)
	int cardLayer, bgLayer;
	
	//ペイント用バッファをすべて破棄する(ペイントモードを抜けるとき、カード移動時)
	public void releasePaintBuffers(){
		PaintUndo.reset(this);
		mainImg = null;
		bgImg = null;
		previewBuf = null;
		previewVisible = false;
		floating = null;
		cardLayer = bgLayer = 0;
	}

	//編集対象のグループ(カードかバックグラウンド)。スタックが無い(テスト・IconEditor)ならnull
	OCardBase paintGroup(boolean background){
		if(this!=pc || stack==null || stack.curCard==null) return null;
		return background ? stack.curCard.bg : stack.curCard;
	}
	OCardBase paintGroup(){
		return paintGroup(isEditingBackground());
	}

	//編集中のレイヤー番号(0=本来のピクチャ)。範囲外なら0に戻す
	int activeLayer(){
		OCardBase g = paintGroup();
		int n = isEditingBackground() ? bgLayer : cardLayer;
		if(g==null || n<0 || n>g.layers.size()){ n = 0; setActiveLayer(0); }
		return n;
	}
	void setActiveLayer(int n){
		if(isEditingBackground()) bgLayer = n; else cardLayer = n;
	}
	//編集対象が「白が地」の不透明な絵か(バックグラウンドピクチャそのもの)。追加レイヤーは
	//カードピクチャと同じく透明が地
	boolean opaqueSurface(){
		return isEditingBackground() && activeLayer()==0;
	}

	//編集中の追加レイヤー(本来のピクチャを編集中ならnull)
	PaintLayer activePaintLayer(){
		OCardBase g = paintGroup();
		int n = activeLayer();
		return (g==null || n==0) ? null : g.layers.get(n-1);
	}
	
	public void end(){
		PaintUndo.reset(this);
		if(mainImg!=null) mainImg.flush();
		mainImg = null;
		if(bgImg!=null) bgImg.flush();
		bgImg = null;
		if(previewBuf!=null) previewBuf.flush();
		previewBuf = null;
		previewVisible = false;
		floating = null;
		pat = null;
		fore = null;
		back = null;
		grad = null;
		stack = null;
		mainPane = null;
		tool = null;
	}
	
    boolean isEditingBackground(){
		return PaintTool.editBackground;
    }

    public BufferedImage getSurface(){
		BufferedImage base = isEditingBackground() ? bgImg : mainImg;
		if(base==null) return null; //ペイントモードでない
		PaintLayer l = activePaintLayer();
		if(l!=null && l.image!=null) return l.image;
    	return base;
    }
	
    //編集対象の画像を差し替える。バックグラウンド編集中はbgImgだけ、そうでなければ
    //mainImgだけを置き換える(以前は両方に同じ画像を代入していたため、背景へ反転や
    //フィルタを適用するとカードと背景が同じバッファになり絵が混ざっていた)。
    public void setSurface(BufferedImage bi){
		PaintLayer l = activePaintLayer();
		if(l!=null){ l.image = bi; OCardBase g = paintGroup(); if(g!=null) g.invalidateComposite(); return; }
		if(isEditingBackground()) bgImg = bi;
    	else mainImg = bi;
    }

    public void setNewBounds(){
    	Rectangle r = pc.getBounds();
    	if(pc.toolbar==null || pc.toolbar.tb==null) return;
    	int w = PCARD.toPhysical(pc.stack.windowWidth());
    	int h = PCARD.toPhysical(pc.stack.windowHeight());
    	pc.setBounds(r.x, r.y, w+pc.toolbar.getTWidth()+pc.chromeWidth(), h+pc.toolbar.getTHeight()+pc.chromeTop()+pc.chromeBottom());
    	//the card pane's place follows the scroll of the card window (CardViewLayout); a full-size window shows it from 0,0
    	if(pc.mainPane!=null && pc.mainPane.getParent()!=null) pc.mainPane.getParent().revalidate();
    }

    /** The card window's scroll in card (logical) coordinates as it is shown: 0,0 unless the window is smaller than the card. */
    Point cardScroll(){
    	if(stack==null || stack.scroll==null || mainPane==null || mainPane.getParent()==null) return new Point(0,0);
    	java.awt.Container view = mainPane.getParent();
    	int maxX = Math.max(0, PCARD.toPhysical(stack.width)-view.getWidth());
    	int maxY = Math.max(0, PCARD.toPhysical(stack.height)-view.getHeight());
    	int x = Math.max(0, Math.min(PCARD.toPhysical(stack.scroll.x), maxX));
    	int y = Math.max(0, Math.min(PCARD.toPhysical(stack.scroll.y), maxY));
    	return new Point(PCARD.toLogical(x), PCARD.toLogical(y));
    }
}

/**
 * The card window's content: the card pane fills it, and when the window is smaller than the card (RDS experiments:
 * set the rect of cd window to sx,sy,sx+192,sy+160) the pane is moved by the card window's scroll so that the window
 * shows that part of the card (set the scroll of cd window to x*208+3,0). The scroll is kept within the card.
 */
class CardViewLayout extends BorderLayout {
	private static final long serialVersionUID = 1L;
	private final PCARDFrame frame;
	CardViewLayout(PCARDFrame frame){ this.frame = frame; }
	@Override public void layoutContainer(java.awt.Container target){
		super.layoutContainer(target);
		javax.swing.JComponent pane = frame.mainPane;
		if(pane==null || pane.getParent()!=target || frame.stack==null) return;
		Point s = frame.cardScroll();
		if(s.x==0 && s.y==0) return;
		int sx = PCARD.toPhysical(s.x), sy = PCARD.toPhysical(s.y);
		Rectangle r = pane.getBounds();
		pane.setBounds(r.x-sx, r.y-sy, r.width+sx, r.height+sy);
	}
}



public class PCARD extends PCARDFrame /*implements MRJOpenDocumentHandler*/  {
	private static final long serialVersionUID = 1L;

	static String AppName = "HyperZebra";

	/** True once HyperZebra is quitting: the launcher must not come back as the programs finish. */
	static volatile boolean exiting;

	/**
	 * Ends the process (QuitSequence): the open stack is closed and saved, then each running program is asked to quit and
	 * waited for as long as it takes (its "Save changes?" included); a failed save or a program that cancels stops the
	 * quit. Returns at once - the quit runs on its own thread.
	 */
	static void exit() { exit(null); }
	/** {@link #exit()} answering macOS's own quit request (logout, the Dock's Quit) when the quit stops. */
	static void exit(java.awt.desktop.QuitResponse response) {
		QuitSequence.start(response);
	}

	//macOSのCmd-Q(アプリケーションメニューの終了)はJavaの既定ではSystem.exitに直行してしまうので、
	//メニューの「終了」と同じ経路(スタックのスクリプト実行・保存、68Kアプリの終了)を通す。
	//68Kアプリのウインドウが前面にあるときのCmd-Qは、Macと同じく前面のアプリケーション(その68Kアプリ)宛て:
	//キー入力はウインドウに届く前にアプリケーションメニューの「HyperZebraを終了」に取られるので、ここで68Kアプリに渡す。
	private static void installQuitHandler() {
		if(!java.awt.Desktop.isDesktopSupported()) return;
		java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
		if(!desktop.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) return;
		desktop.setQuitHandler(new java.awt.desktop.QuitHandler() {
			public void handleQuitRequestWith(java.awt.desktop.QuitEvent e, java.awt.desktop.QuitResponse response) {
				java.awt.KeyboardFocusManager focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
				java.awt.Window active = focus.getActiveWindow();
				if(active == null) active = focus.getFocusedWindow();
				if(MacApp68k.forwardCommandQ(active)) {
					response.cancelQuit();
					return;
				}
				//スタックのウインドウが前面のときのCmd-Qはそのスタックを閉じてランチャーに戻る(HyperZebraは終了しない)。
				//Commandキーを押しているときだけ: ログアウト・再起動やDockの「終了」の終了要求まで止めないように。
				if(commandKeyDown && isStackWindow(active)) {
					response.cancelQuit();
					closeCurrentStack();
					return;
				}
				quitFromMenu(response);
			}
		});
		trackCommandKey();
		installReopenHandler();
		installAboutHandler();
		installOpenFileHandler();
	}

	//Finderでのダブルクリック・Dockへのドロップ(Open Documents)で届いた書類: 起動の途中ならmainの最後まで待たせ、
	//ドロップと同じ経路(openFiles)で開く。Finderから起動したときは親プロセスが受け取って標準入力で渡してくる(HyperZebra.forwardOpenedFiles)。
	private static final List<File> pendingOpenedFiles = new java.util.ArrayList<File>();
	private static boolean openedFilesReady;
	private static void installOpenFileHandler() {
		if(System.getenv("HYPERZEBRA_RELAUNCHED")!=null) readOpenedFilesFromParent();
		if(!java.awt.Desktop.isDesktopSupported()) return;
		try {
			java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
			if(!desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) return;
			desktop.setOpenFileHandler(new java.awt.desktop.OpenFilesHandler() {
				public void openFiles(java.awt.desktop.OpenFilesEvent e) {
					openFromHost(e.getFiles());
				}
			});
		} catch(Exception ignored) {
		}
	}
	private static void readOpenedFilesFromParent() {
		Thread t = new Thread(() -> {
			try {
				java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
				for(String line; (line = in.readLine())!=null; ) {
					if(line.isEmpty()) continue;
					openFromHost(java.util.Collections.singletonList(new File(line)));
					//macOSが前面にするのは書類を受け取った親(Dockに出ない)なので、本体を自分で前面に出す
					javax.swing.SwingUtilities.invokeLater(() -> {
						try {
							java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
							if(desktop.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true);
						} catch(Exception ignored) {
						}
					});
				}
			} catch(IOException ignored) {
			}
		}, "Open Documents from parent");
		t.setDaemon(true); t.start();
	}
	private static void openFromHost(List<File> files) {
		if(files==null || files.isEmpty()) return;
		synchronized(pendingOpenedFiles) {
			if(!openedFilesReady) { pendingOpenedFiles.addAll(files); return; }
		}
		final List<File> list = new java.util.ArrayList<File>(files);
		javax.swing.SwingUtilities.invokeLater(() -> openFiles(list));
	}
	private static void openedFilesReady() {
		List<File> files;
		synchronized(pendingOpenedFiles) {
			openedFilesReady = true;
			files = new java.util.ArrayList<File>(pendingOpenedFiles);
			pendingOpenedFiles.clear();
		}
		openFromHost(files);
	}

	//macOSのアプリケーションメニューの「HyperZebraについて」: Javaの既定のパネル(Javaのバージョンが出る)ではなく、
	//ランチャーの「このアプリについて」ページを出す(ランチャーを使わない設定のときは名前とバージョンだけのダイアログ)。
	private static void installAboutHandler() {
		if(!java.awt.Desktop.isDesktopSupported()) return;
		try {
			java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
			if(!desktop.isSupported(java.awt.Desktop.Action.APP_ABOUT)) return;
			desktop.setAboutHandler(new java.awt.desktop.AboutHandler() {
				public void handleAbout(java.awt.desktop.AboutEvent e) {
					GLauncher.showAbout();
				}
			});
		} catch(Exception ignored) {
		}
	}

	/** The Command key as the last key event HyperZebra's own windows saw it (Cmd-Q itself is taken by the application menu). */
	private static volatile boolean commandKeyDown;
	private static void trackCommandKey() {
		MacFocusRecovery.install(); // a window clicked to bring HyperZebra forward gets the focus (macOS leaves none focused)
		java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(new java.awt.event.AWTEventListener() {
			public void eventDispatched(java.awt.AWTEvent ev) {
				if(ev instanceof java.awt.event.KeyEvent) {
					java.awt.event.KeyEvent k = (java.awt.event.KeyEvent) ev;
					if(k.getKeyCode()==java.awt.event.KeyEvent.VK_META) commandKeyDown = k.getID()==java.awt.event.KeyEvent.KEY_PRESSED;
					else if(k.getID()==java.awt.event.KeyEvent.KEY_PRESSED) commandKeyDown = k.isMetaDown();
				} else if(ev.getID()==java.awt.event.WindowEvent.WINDOW_DEACTIVATED) {
					commandKeyDown = false;
				}
			}
		}, java.awt.AWTEvent.KEY_EVENT_MASK | java.awt.AWTEvent.WINDOW_EVENT_MASK);
	}

	/** A stack is open and the window in front is not the launcher's (nor one of its dialogs): Quit closes that stack. */
	static boolean isStackWindow(java.awt.Window active) {
		if(active==null || pc==null || !GLauncher.enabled() || !pc.isVisible()) return false;
		if(pc.stack==null || pc.stack.curCard==null) return false;
		for(java.awt.Window w = active; w!=null; w = w.getOwner()) {
			if(w==GLauncher.instance) return false;
		}
		return true;
	}

	/** The stack window's own Quit (Windows/Linux File menu): back to the launcher, like Close Stack; without a launcher it quits. */
	static void quitFromStackWindow() {
		if(GLauncher.enabled() && pc!=null && pc.stack!=null && pc.stack.curCard!=null) closeCurrentStack();
		else quitFromMenu();
	}

	/** The menu's Quit: the stack's close messages and its save, the programs' quit, then the process ends (QuitSequence). */
	static void quitFromMenu() { quitFromMenu(null); }
	private static void quitFromMenu(java.awt.desktop.QuitResponse response) {
		exit(response);
	}

	//macOSのDockアイコンのクリック(ウインドウが1つも無いときの「再オープン」): 閉じてあったランチャーを出す。
	private static void installReopenHandler() {
		if(!java.awt.Desktop.isDesktopSupported()) return;
		try {
			java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
			if(!desktop.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED)) return;
			desktop.addAppEventListener(new java.awt.desktop.AppReopenedListener() {
				public void appReopened(java.awt.desktop.AppReopenedEvent e) {
					if(GLauncher.instance!=null && !GLauncher.instance.isVisible()) GLauncher.showLauncher(true);
				}
			});
		} catch(Exception ignored) {
		}
	}
	
	//アプリのバージョンはここだけに書く。release.sh / jpackage.sh / jpackage.ps1 はこの行を読んで
	//パッケージのバージョンにするので、x.y.z(数字だけ)の形を保つこと。短い版(the short version)は x.y。
	static final String longVersion="3.0.2";
	static final String version=longVersion.substring(0, longVersion.lastIndexOf('.'));

	/**
	 * HyperCard's 8-digit version code, as "the long version" and the items of "the version of stack" report it:
	 * major (2 digits), minor, bug fix, stage (20 development, 40 alpha, 60 beta, 80 final) and release (2 digits).
	 * "2.2.1" → "02218000", "3.0a5" → "03004005", HyperZebra's "3.0.0" → "03008000". A code is returned as it is.
	 */
	static String hcVersionCode(String v) {
		if(v==null) return "00000000";
		v = v.trim();
		if(v.matches("[0-9]{8}")) return v;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?\\s*([dabf])?\\s*(\\d+)?.*").matcher(v);
		if(!m.matches()) return "00000000";
		int major = Integer.parseInt(m.group(1));
		int minor = m.group(2)==null ? 0 : Math.min(9, Integer.parseInt(m.group(2)));
		int fix = m.group(3)==null ? 0 : Math.min(9, Integer.parseInt(m.group(3)));
		String st = m.group(4);
		int stage = st==null ? 0x80 : st.equals("d") ? 0x20 : st.equals("a") ? 0x40 : st.equals("b") ? 0x60 : 0x80;
		int release = m.group(5)==null || st==null ? 0 : Math.min(99, Integer.parseInt(m.group(5)));
		return String.format("%02d%d%d%02X%02d", Math.min(99, major), minor, fix, stage, release);
	}
	GMenu menu;
	GMenu paintMenu;
	GMsg msg;
	International intl;
	String lang;
	static boolean useDoubleBuffer = true;//バッファをオフにしてメモリ節約できる？

	//拡大表示の設定(DisplayZoom: 等倍 / 最大125% / 最大150% / 最大200% / 等倍または2倍)。設定画面のポップアップで
	//変更でき、開いているスタックと実行中のMacアプリにはすぐ反映される(applyZoomLive)。デフォルトは「等倍または2倍」
	//(ピクセルパーフェクト)。以前の2倍表示のチェックボックス(zoomMode)の値はON=等倍または2倍、OFF=等倍として引き継ぐ。
	private static final java.util.prefs.Preferences prefs =
		java.util.prefs.Preferences.userNodeForPackage(PCARD.class);
	//-Dhz.zoom=1|125|150|200|2 で設定を変えずにこの起動だけ選べる(確認・スクリーンショット用)
	static final String ZOOM_OVERRIDE = System.getProperty("hz.zoom");
	static int zoomChoice = DisplayZoom.parse(ZOOM_OVERRIDE, DisplayZoom.parse(prefs.get("zoom", null),
		prefs.getBoolean("zoomMode", true) ? DisplayZoom.ONE_OR_TWO : DisplayZoom.ONE));

	//表示言語(BCP 47 のタグ: "en", "ja"...)。設定画面のメニューで選べ、次回起動時に反映される。
	//訳は lang/<タグ>.po(International)。以前の版が保存した "Japanese"/"English" も読める。保存された言語の
	//訳が無くなっていた場合や未設定の場合は、OSの言語(に一番近い訳のある言語)、それも無ければ英語にする。
	//-Dhz.language=fr で設定を変えずにこの起動だけ選べる(訳の確認・スクリーンショット用)
	static final String LANGUAGE_OVERRIDE = System.getProperty("hz.language");
	static String language = International.chosenLanguage(LANGUAGE_OVERRIDE!=null ? LANGUAGE_OVERRIDE : prefs.get("language", null));
	static void setLanguage(String lang){
		language = lang;
		prefs.put("language", lang);
	}
	//拡大したスタックのウインドウが画面に収まる倍率の上限。スタックを開くたびにupdateZoomFitsScreenで
	//判定し直し、zoomChoice同様「次にスタックを開き直すまでは変わらない」値として保持する。
	//拡大率(scale())は1以上の実数で、整数(1倍・2倍)のときだけピクセルパーフェクトに描く。
	private static double zoomFit = Double.MAX_VALUE;
	static double scale(){ return scalePercent()/100.0; }
	/** The magnification in percent (100, 125, 200...). */
	static int scalePercent(){ return DisplayZoom.percent(stackZoomChoice(), zoomFit); }
	/** The zoom the open stack is shown at: its own (Individual Stack Settings, StackSettings) or the settings' choice. */
	static int stackZoomChoice(){ Integer own = StackSettings.zoom; return own!=null && ZOOM_OVERRIDE==null ? own : zoomChoice; }
	/** Whether the stack is shown at a whole magnification (pixel-perfect, nearest neighbour). */
	static boolean pixelPerfectScale(){ return scalePercent()%100==0; }
	/** The interpolation for pictures drawn at scale(): nearest neighbour when whole (pixel-perfect), else bilinear. */
	static Object scaleInterpolation(){ return DisplayZoom.interpolation(scale()); }
	/** The same for drawing into g: whole on the device counts (150% on a Retina display is 3 device pixels a card pixel,
	 *  and printing at 288 dpi is 4), so such pictures stay pixel-perfect. */
	static Object scaleInterpolation(Graphics2D g){
		double device = g.getTransform().getScaleX();
		return DisplayZoom.interpolation(DisplayZoom.whole(scale()) ? scale() : scale()*device);
	}
	/** A logical (card) coordinate or length in window pixels: rounded, so that the edges of neighbouring objects meet. */
	static int toPhysical(int logical){ return DisplayZoom.scale(logical, scalePercent()); }
	/** A fractional logical coordinate in window pixels (rounded). */
	static int toPhysical(double logical){ return (int)Math.round(logical*scale()); }
	/** The window-pixel length of the logical span start..start+length (its edges rounded like toPhysical). */
	static int toPhysicalLength(int start, int length){ return toPhysical(start+length)-toPhysical(start); }
	/** The window-pixel rectangle of a logical one: its edges rounded, so that neighbouring objects meet. */
	static Rectangle toPhysical(int left, int top, int width, int height){
		int x = toPhysical(left), y = toPhysical(top);
		return new Rectangle(x, y, toPhysical(left+width)-x, toPhysical(top+height)-y);
	}
	/** Draws a picture at logical (x,y) magnified scale() times (nearest neighbour when whole, else bilinear). */
	static void drawScaled(Graphics g, Image img, int x, int y, java.awt.image.ImageObserver observer){
		if(scalePercent()==100){ g.drawImage(img, x, y, observer); return; }
		Graphics2D g2 = (Graphics2D)g;
		Object old = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scaleInterpolation(g2));
		Rectangle r = toPhysical(x, y, img.getWidth(null), img.getHeight(null));
		g2.drawImage(img, r.x, r.y, r.width, r.height, observer);
		if(old!=null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
	}
	/** A window-pixel coordinate as a card coordinate: the card pixel whose window pixels contain it (the inverse of toPhysical). */
	static int toLogical(int physical){ return DisplayZoom.unscale(physical, scalePercent()); }
	/** A window-pixel length as a logical one (rounded to the nearest). */
	static int toLogicalLength(double physical){ return (int)Math.round(physical/scale()); }
	static void setZoomChoice(int choice){
		if(!DisplayZoom.valid(choice)) return;
		zoomChoice = choice;
		prefs.put("zoom", Integer.toString(choice));
		prefs.putBoolean("zoomMode", choice!=DisplayZoom.ONE); //古い版で開いたときの2倍表示
	}
	/**
	 * Shows the open stack at the zoom just chosen, without reopening it: the fit is judged again, the window is resized around
	 * its centre (kept on the display) and the current card is rebuilt at the new scale. Runs on the EDT.
	 */
	static void applyZoomLive(){
		runOnEDT(new Runnable(){ public void run(){
			if(pc==null || pc.stack==null || pc.stack.curCard==null || !pc.isVisible() || pc.toolbar==null || pc.toolbar.tb==null) return;
			updateZoomFitsScreen(pc.stack.width, pc.stack.height);
			if(pc.mainPane.getWidth()==toPhysical(pc.stack.width) && pc.mainPane.getHeight()==toPhysical(pc.stack.height)) return; //already shown at this scale
			Rectangle old = pc.getBounds();
			pc.stack.setNewBounds();
			Rectangle r = pc.getBounds();
			Rectangle usable = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			int x = old.x+old.width/2-r.width/2, y = old.y+old.height/2-r.height/2;
			x = Math.max(usable.x, Math.min(x, usable.x+usable.width-r.width));
			y = Math.max(usable.y, Math.min(y, usable.y+usable.height-r.height));
			pc.setLocation(x, y);
			pc.setNewBounds();
			OCard.reloadCurrentCard();
			if(pc.msg!=null) pc.msg.setBounds(pc.getX()+pc.getWidth()/2-240, pc.getY()+pc.getHeight(), 480, 28+pc.getInsets().top);
			pc.mainPane.revalidate();
			pc.mainPane.repaint();
		}});
	}
	//68K VMで起動したアプリの文字を常にアンチエイリアスで描く設定。設定画面の
	//チェックボックスで変更でき、次に描かれる文字からすぐ反映される。デフォルトはOFF。
	static boolean smoothText68k = prefs.getBoolean("smoothText68k", false);
	static { Fonts68k.smoothText = Fonts68k.smoothText || smoothText68k; }
	static void setSmoothText68k(boolean enabled){
		smoothText68k = enabled;
		Fonts68k.smoothText = enabled;
		prefs.putBoolean("smoothText68k", enabled);
	}
	//68K VMのファイルダイアログ(プログラム自身のDLOGも含む)を広げ、変更日・サイズ・種類の列と
	//並べ替えヘッダを付ける設定。次に開くダイアログから反映される。デフォルトはON。
	//-Dhz68k.standardfile=classic / wide が指定されているときはそちらが優先。
	static boolean wideFileDialogs68k = prefs.getBoolean("wideFileDialogs68k", true);
	static { if(System.getProperty("hz68k.standardfile")==null) StandardFile68k.wide = wideFileDialogs68k; }
	static void setWideFileDialogs68k(boolean enabled){
		wideFileDialogs68k = enabled;
		if(System.getProperty("hz68k.standardfile")==null) StandardFile68k.wide = enabled;
		prefs.putBoolean("wideFileDialogs68k", enabled);
	}
	//macOSでMacアプリのウインドウでControlを押している間、Mission Control(Control+矢印で操作スペース切り替えなど)や
	//Control+数字といったシステムのショートカットを止めて、キーをプログラムに渡すかどうか(Wodenなどのゲーム)。
	//デフォルトはON。アプリ個別設定で上書きできる。-Dhz68k.suspendhotkeys=false で常にOFF。
	static boolean controlKeysToPrograms68k = prefs.getBoolean("controlKeysToPrograms68k", true);
	static void setControlKeysToPrograms68k(boolean enabled){
		controlKeysToPrograms68k = enabled;
		prefs.putBoolean("controlKeysToPrograms68k", enabled);
	}
	//68Kプログラムが新しく作ったテキストファイルをホスト側でUTF-8(改行LF)で保存するかどうか。読むときは
	//ホストのUTF-8テキストをプログラムの文字コード(MacJapanese/MacRoman、改行CR)に変換した写しを見せるので設定に
	//よらない(TextFile68k)。デフォルトはON。-Dhz68k.utf8text=false が指定されているときはそちらが優先。
	static boolean utf8TextFiles68k = prefs.getBoolean("utf8TextFiles68k", true);
	static { if(System.getProperty("hz68k.utf8text")==null) FileManager68k.utf8TextFiles = utf8TextFiles68k; }
	static void setUtf8TextFiles68k(boolean enabled){
		utf8TextFiles68k = enabled;
		if(System.getProperty("hz68k.utf8text")==null) FileManager68k.utf8TextFiles = enabled;
		prefs.putBoolean("utf8TextFiles68k", enabled);
	}
	//68Kプログラムが新しく作ったPICTファイルをホスト側でPNG(同じ名前、タイプPNGf)で保存し、ホストのPNG/JPEG/GIFを
	//プログラムにはPICTファイルとして見せるかどうか(PictureFile68k)。デフォルトはON。-Dhz68k.pngpict=false が優先。
	static boolean pngPictureFiles68k = prefs.getBoolean("pngPictureFiles68k", true);
	static { if(System.getProperty("hz68k.pngpict")==null) FileManager68k.pngPictureFiles = pngPictureFiles68k; }
	static void setPngPictureFiles68k(boolean enabled){
		pngPictureFiles68k = enabled;
		if(System.getProperty("hz68k.pngpict")==null) FileManager68k.pngPictureFiles = enabled;
		prefs.putBoolean("pngPictureFiles68k", enabled);
	}
	//印刷の解像度(dpi)。HyperTalk の print card はカードをこの解像度で描いて紙に置き、68Kアプリには PrGeneral で
	//72dpi からこの解像度までを選べるプリンタとして見せる(選ばないアプリは従来どおり 72dpi)。すぐ反映される。
	static final int[] PRINT_DPI_CHOICES = {72, 144, 216, 288};
	static final int DEFAULT_PRINT_DPI = 288;
	static volatile int printDpi = parsePrintDpi(prefs.get("printDpi", null), DEFAULT_PRINT_DPI);
	static int parsePrintDpi(String text, int fallback){
		if(text==null) return fallback;
		try{ int dpi = Integer.parseInt(text.trim()); for(int c : PRINT_DPI_CHOICES) if(c==dpi) return dpi; }catch(NumberFormatException e){}
		return fallback;
	}
	static void setPrintDpi(int dpi){
		printDpi = parsePrintDpi(Integer.toString(dpi), DEFAULT_PRINT_DPI);
		prefs.put("printDpi", Integer.toString(printDpi));
	}
	//68K VMのMIDI Manager(Apple MIDI Driver相当)のモデムポート/プリンタポートを、ホストのどのMIDI機器につなぐか
	//(MidiHost68k)。出力は "synth"(Javaの内蔵音源)、"none"、または機器名。入力は "auto"(最初の機器)、"none"、または機器名。
	//すぐ反映される。-Dhz68k.midiout / -Dhz68k.midiin が指定されているときはモデムポートについてそちらが優先。
	static String midiOutModem68k = prefs.get("midiOutModem68k", MidiHost68k.SYNTH), midiOutPrinter68k = prefs.get("midiOutPrinter68k", MidiHost68k.SYNTH);
	static String midiInModem68k = prefs.get("midiInModem68k", MidiHost68k.AUTO), midiInPrinter68k = prefs.get("midiInPrinter68k", MidiHost68k.NONE);
	static {
		if(System.getProperty("hz68k.midiout")==null) MidiHost68k.setOutputChoice(MidiHost68k.MODEM, midiOutModem68k);
		MidiHost68k.setOutputChoice(MidiHost68k.PRINTER, midiOutPrinter68k);
		if(System.getProperty("hz68k.midiin")==null) MidiHost68k.setInputChoice(MidiHost68k.MODEM, midiInModem68k);
		MidiHost68k.setInputChoice(MidiHost68k.PRINTER, midiInPrinter68k);
	}
	static void setMidi68k(String outModem, String outPrinter, String inModem, String inPrinter){
		midiOutModem68k = outModem; midiOutPrinter68k = outPrinter; midiInModem68k = inModem; midiInPrinter68k = inPrinter;
		prefs.put("midiOutModem68k", outModem); prefs.put("midiOutPrinter68k", outPrinter); prefs.put("midiInModem68k", inModem); prefs.put("midiInPrinter68k", inPrinter);
		if(System.getProperty("hz68k.midiout")==null) MidiHost68k.setOutputChoice(MidiHost68k.MODEM, outModem);
		MidiHost68k.setOutputChoice(MidiHost68k.PRINTER, outPrinter);
		if(System.getProperty("hz68k.midiin")==null) MidiHost68k.setInputChoice(MidiHost68k.MODEM, inModem);
		MidiHost68k.setInputChoice(MidiHost68k.PRINTER, inPrinter);
	}
	//68K VMのAppleTalk(AppleTalk68k): "udp" = LAN上のLocalTalk over UDP(Mini vMacなどと同じ239.192.76.84:1954)、"local" = この
	//HyperZebraの中だけ、"off" = AppleTalk切。次に起動する68Kアプリから。-Dhz68k.appletalk が指定されているときはそちらが優先。
	static String appleTalk68k = prefs.get("appleTalk68k", AppleTalk68k.UDP);
	static { if(System.getProperty("hz68k.appletalk")==null) AppleTalk68k.mode = appleTalk68k; }
	static void setAppleTalk68k(String mode){
		appleTalk68k = mode;
		if(System.getProperty("hz68k.appletalk")==null) AppleTalk68k.mode = mode;
		prefs.put("appleTalk68k", mode);
	}
	//68K VMのCPUを実機(68LC040 33/66MHzより少し速い程度)の速さに抑えるかどうか。デフォルトはON。
	//実行中のプログラムにもすぐ反映される。-Dhz68k.mips が指定されているときはそちらが優先。
	static boolean realSpeed68k = prefs.getBoolean("realSpeed68k", true);
	static { if(System.getProperty("hz68k.mips")==null) Toolbox68k.mips = realSpeed68k ? Toolbox68k.DEFAULT_MIPS : 0; }
	static void setRealSpeed68k(boolean enabled){
		realSpeed68k = enabled;
		if(System.getProperty("hz68k.mips")==null) Toolbox68k.mips = enabled ? Toolbox68k.DEFAULT_MIPS : 0;
		prefs.putBoolean("realSpeed68k", enabled);
	}
	//PowerPCアプリのコードをJITコンパイラ(PpcJit: よく通るコードをJavaバイトコードに変換)で動かすかどうか。デフォルトはON。
	//OFFにするとインタプリタだけで動く(JITでおかしくなるアプリのため)。実行中のプログラムにも次のポーリングで反映される
	//(アプリ個別の設定があればそちら)。-Dhzppc.jit が指定されているときはそちらが優先。
	static boolean ppcJit = prefs.getBoolean("ppcJit", true);
	static { PpcJit.sharedSetting = ppcJit; }
	static void setPpcJit(boolean on){
		ppcJit = on;
		PpcJit.sharedSetting = on;
		prefs.putBoolean("ppcJit", on);
	}
	//HyperTalkのJITコンパイラ(TalkJit)。-Dhz.talk.jit=false が指定されているときはそちらが優先。
	static boolean talkJit = prefs.getBoolean("talkJit", true);
	static { TalkJit.setEnabled(talkJit); }
	static void setTalkJit(boolean on){
		talkJit = on;
		TalkJit.setEnabled(on);
		prefs.putBoolean("talkJit", on);
	}
	//68K VMで起動したアプリが見る画面の色数(8=256色, 16, 32=フルカラー)。
	//次に起動するアプリから反映される。デフォルトはフルカラー。
	//-Dhz68k.depthpref=32 で設定を変えずにこの起動だけ設定の値を選べる(テスト用: macOSのJava Preferencesはuser.homeに
	//関係なく利用者の設定を読む)。-Dhz68k.depth と違い、コンパクトMacの白黒画面などの色数の自動選択はそのまま働く
	static int screenDepth68k = QuickDraw68k.parseScreenDepth(System.getProperty("hz68k.depthpref", prefs.get("screenDepth68k", null)), 32);
	static { if(System.getProperty("hz68k.depth")==null) QuickDraw68k.screenDepth = screenDepth68k; }
	static void setScreenDepth68k(int depth){
		screenDepth68k = depth;
		QuickDraw68k.screenDepth = depth;
		prefs.put("screenDepth68k", Integer.toString(depth));
	}
	//68K VMで起動したアプリが見る画面の大きさ("640x480"など、空 = 当時の最大(1152x870)まで、"display" = ホストの画面全体)と、
	//その画面の拡大(DisplayZoom の選択肢。スタックの拡大と同じ設定)。ゲームは640x480を前提にレイアウトすることが多い。
	//次に起動するアプリから反映される。-Dhz68k.screen が指定されているときはそちらが優先。
	static String screenSize68k = prefs.get("screenSize68k", "");
	static int screenScale68k = DisplayZoom.valid(prefs.getInt("screenScale68k", 1)) ? prefs.getInt("screenScale68k", 1) : DisplayZoom.ONE;
	static {
		if(System.getProperty("hz68k.screen")==null) MacAppHost.virtualScreen = MacAppHost.parseScreenSize(screenSize68k);
		MacAppHost.wholeDisplayPreferred = MacAppHost.WHOLE_DISPLAY.equals(screenSize68k);
		MacAppHost.screenScale = screenScale68k;
	}
	static void setScreen68k(String size, int scale){
		screenSize68k = size==null ? "" : size;
		screenScale68k = DisplayZoom.valid(scale) ? scale : DisplayZoom.ONE;
		if(System.getProperty("hz68k.screen")==null) MacAppHost.virtualScreen = MacAppHost.parseScreenSize(screenSize68k);
		MacAppHost.wholeDisplayPreferred = MacAppHost.WHOLE_DISPLAY.equals(screenSize68k);
		MacAppHost.screenScale = screenScale68k;
		prefs.put("screenSize68k", screenSize68k);
		prefs.putInt("screenScale68k", screenScale68k);
	}
	//ゲーム画面(ディスプレイより小さい、選んだ大きさの画面)を置く基本位置: false = ディスプレイの中央、true = 左上。
	//起動中のアプリにもすぐ反映される(画面がその位置へ移る)。
	static boolean screenTopLeft68k = prefs.getBoolean("screenTopLeft68k", false);
	static { MacAppHost.screenTopLeft = screenTopLeft68k; }
	static void setScreenTopLeft68k(boolean topLeft){
		screenTopLeft68k = topLeft;
		MacAppHost.screenTopLeft = topLeft;
		prefs.putBoolean("screenTopLeft68k", topLeft);
		MacApp68k.screenPlaceChangedAll();
	}
	//68Kアプリの画面を拡大表示するときのスムーズ拡大(SmoothZoom.OFF/BRESENHAM/BICUBIC/SCALE2X)。文字やオフスクリーンバッファを
	//拡大後の解像度で描き、アイコン・ピクチャなどは選んだアルゴリズムで拡大する。主にゲーム向け。起動中のアプリにもすぐ反映される。
	//-Dhz68k.smooth が指定されているときはそちらが優先。デフォルトはOFF。
	static int smoothZoom68k = SmoothZoom.valid(prefs.getInt("smoothZoom68k", SmoothZoom.OFF)) ? prefs.getInt("smoothZoom68k", SmoothZoom.OFF) : SmoothZoom.OFF;
	static { MacAppHost.smoothZoom = smoothZoom68k; }
	static void setSmoothZoom68k(int choice){
		smoothZoom68k = SmoothZoom.valid(choice) ? choice : SmoothZoom.OFF;
		MacAppHost.smoothZoom = smoothZoom68k;
		prefs.putInt("smoothZoom68k", smoothZoom68k);
		MacApp68k.smoothChangedAll();
	}
	//Macアプリの表示の翻訳(VMTranslation): macOS 26以降に組み込みのオンデバイス翻訳(翻訳フレームワーク/Apple Intelligence)で、
	//メニュー・ダイアログ・ウインドウ名・アプリが描く文章を選んだ言語にする。ユーザーが編集できる文字は訳さない。
	//アプリごとの設定(LauncherStore.AppSettings)が優先。変更は実行中のアプリにもすぐ反映される。デフォルトはオフ。
	static boolean translate68k = prefs.getBoolean("translate68k", false);
	static String translateTo68k = prefs.get("translateTo68k", VMTranslation.defaultTarget());
	static String translateEngine68k = prefs.get("translateEngine68k", VMTranslation.ENGINE_TRANSLATION);
	static void setTranslation68k(boolean on, String to, String engine){
		translate68k = on; translateTo68k = to; translateEngine68k = engine;
		prefs.putBoolean("translate68k", on); prefs.put("translateTo68k", to); prefs.put("translateEngine68k", engine);
		MacApp68k.translationChangedAll();
		StackTranslation.settingsChanged(); // stacks that follow the translation settings show the new language
	}
	//スタックを開く際、そのカードサイズを拡大したウインドウが画面に何倍まで収まるかを
	//判定してキャッシュしておく。タイトルバー・メニューバー分として縦に余裕を持たせて判定する。
	static void updateZoomFitsScreen(int stackWidth, int stackHeight){
		Rectangle screenBounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		int chromeHeight = 80;
		zoomFit = DisplayZoom.fit(stackWidth, stackHeight, 0, chromeHeight, screenBounds);
	}

	//ボタン/フィールドがOObjectの論理座標(left,top,width,height)を使って
	//mainPaneの一部を再描画したいときに使う。拡大表示モードでは実際の
	//Swing座標は論理座標のscale()倍になっているため、ここでまとめて変換する。
	//以前は paintImmediately でその場で描いていたが、スクリプトが部品を動かす・変えるたびに
	//カードを同期的に描き(Metal の描画完了も待つ)、ループの速さが描画の回数で決まっていた。
	//repaint は同じ EDT タスクで行った部品の変更と一緒に、次の描画でまとめて(ダブルバッファで一度に)
	//画面へ出るので変更と描画の間に中途半端な状態は見えない。描く頻度は CardRepaintManager が1フレームに抑える
	static void repaintLogicalRect(int left, int top, int width, int height){
		if(pc==null || pc.mainPane==null || width<=0 || height<=0) return;
		int x = toPhysical(left), y = toPhysical(top);
		pc.mainPane.repaint(x-1, y-1, toPhysical(left+width)-x+2, toPhysical(top+height)-y+2); //拡大率が整数でないときの丸め分も含める
	}

	//HyperTalkスクリプトはTTalkスレッド(EDT以外)で動くため、ボタンの移動や
	//アイコン変更のようにコンポーネントの状態変更と即時再描画をセットで行う
	//処理をそのままTTalkスレッドから呼ぶと、EDTが後追いで発行する非同期の
	//repaintと競合してチラつき(ティアリング)が起きる。両方を1つのEDTタスクに
	//まとめて同期実行することで、変更と再描画の間に他の描画が割り込まない
	//ようにする。
	static void runOnEDT(Runnable task){
		if(SwingUtilities.isEventDispatchThread()){
			VEffect.captureBeforeChange();
			runDeferred();
			task.run();
		}
		else if(edtBatch!=null && edtBatchThread==Thread.currentThread()){
			edtBatch.add(task); //batchOnEDT: 最後にまとめて1つのEDTタスクで実行する
		}
		else{
			try {
				//絵なしの lock screen 中なら、ツリーを変える前にロック開始時の画面を絵にする(VEffect.lazyLock)
				SwingUtilities.invokeAndWait(() -> { VEffect.captureBeforeChange(); runDeferred(); task.run(); });
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/** The unlock (VEffect.visualEffect): it decides itself whether the picture of the locked screen is needed. */
	static void runOnEDTUnlocking(Runnable task){
		try {
			SwingUtilities.invokeAndWait(task);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/** Component updates made while the screen is locked (lock screen) that nobody sees before the unlock. */
	private static final java.util.ArrayList<Runnable> deferredEDT = new java.util.ArrayList<Runnable>();

	/** Like runOnEDT, for a change of a part's Swing component that only shows on the screen (icon, visibility, bounds):
	 *  while the screen is locked a script thread queues it instead of waiting for a synchronous EDT hop per change.
	 *  The next runOnEDT - at the latest the unlock (VEffect.visualEffect) - runs the queue first, in order, so the
	 *  unlocked card shows every change and any other EDT work sees them made before it. Until then the Swing parts
	 *  still show the locked screen, so lock screen needs no picture of it (VEffect.lazyLock). A scrolling map that
	 *  sets a thousand icons under lock screen waited for a hop each. The model (left, icon, visible …) is set by the
	 *  caller first, so scripts read the new values at once. */
	static void runOnEDTWhenShown(Runnable task){
		if(!SwingUtilities.isEventDispatchThread() && lockedScreen && pc!=null && pc.mainPane!=null &&
				!(edtBatch!=null && edtBatchThread==Thread.currentThread())){
			synchronized(deferredEDT){ deferredEDT.add(task); }
			return;
		}
		runOnEDT(task);
	}

	static final int EDT_VISIBLE=1, EDT_BOUNDS=2;

	/** runOnEDTWhenShown for one kind of update of one part. Under lock screen at most one is queued per part and kind,
	 *  and it applies the part's values when it runs ({@code latest}), so hiding and showing a part again in the same
	 *  frame, or moving it several times, costs the EDT one update or none. Unlocked, {@code now} runs as before. */
	static void runOnEDTWhenShown(OObject part, int kind, Runnable now, Runnable latest){
		if(!SwingUtilities.isEventDispatchThread() && lockedScreen && pc!=null && pc.mainPane!=null &&
				!(edtBatch!=null && edtBatchThread==Thread.currentThread())){
			synchronized(deferredEDT){
				if((part.queuedEdt & kind)!=0) return;
				part.queuedEdt |= kind;
				deferredEDT.add(() -> {
					synchronized(deferredEDT){ part.queuedEdt &= ~kind; }
					latest.run();
				});
			}
			return;
		}
		runOnEDT(now);
	}

	private static Runnable[] takeDeferred(){
		synchronized(deferredEDT){
			if(deferredEDT.isEmpty()) return null;
			Runnable[] tasks = deferredEDT.toArray(new Runnable[0]);
			deferredEDT.clear();
			return tasks;
		}
	}

	/** Runs the queued component updates now (on the EDT, or from any thread through runOnEDT). */
	static void runDeferred(){
		if(!SwingUtilities.isEventDispatchThread()){
			synchronized(deferredEDT){ if(deferredEDT.isEmpty()) return; }
			runOnEDT(() -> {});
			return;
		}
		Runnable[] tasks = takeDeferred();
		if(tasks!=null) for(Runnable r : tasks) r.run();
	}

	private static java.util.ArrayList<Runnable> edtBatch;
	private static Thread edtBatchThread;

	/** Runs {@code body} on this thread, collecting the EDT tasks it would run one by one (runOnEDT, each a synchronous
	 *  hop of tens of microseconds) and running them in order as a single EDT task at the end. For changes that only
	 *  update the model and then the Swing component, such as moving every part of a Panel: a 3000-part Panel took
	 *  3000 hops per move. Nested calls join the outer batch. */
	static void batchOnEDT(Runnable body){
		if(SwingUtilities.isEventDispatchThread() || (edtBatch!=null && edtBatchThread==Thread.currentThread())){
			body.run();
			return;
		}
		java.util.ArrayList<Runnable> tasks = new java.util.ArrayList<Runnable>();
		edtBatch = tasks;
		edtBatchThread = Thread.currentThread();
		try{
			body.run();
		}finally{
			edtBatch = null;
			edtBatchThread = null;
			//部品の位置の変更だけなので、ロック中は解除まで保留できる
			if(!tasks.isEmpty()) runOnEDTWhenShown(() -> { for(Runnable t : tasks) t.run(); });
		}
	}

	//JFileChooserはEDT以外のスレッドから表示してはいけない。macOSのAqua版は
	//ディレクトリの内容を別スレッドで読み込み、その結果をEDT上でテーブルへ反映する
	//(モデルのロック→AWTTreeLockの順で取る)。一方、EDT以外のスレッドで
	//showOpenDialog/showSaveDialogを呼ぶとpack()がAWTTreeLockを持ったまま
	//モデルの行数(モデルのロック)を求めるので、TTalkスレッドから開いたときに
	//両者がデッドロックしてアプリ全体が止まる。必ずEDT上で同期的に開く。
	static int showFileChooser(final JFileChooser chooser, final Component parent, final boolean save){
		return showFileChooser(chooser, parent, save, true);
	}
	/** {@code forScripts}: the chosen file is the open stack's to use (answer file, import…); the launcher's own choosers
	 *  pass false - what they open gets its own permissions (a program, a stack: Sandbox). */
	static int showFileChooser(final JFileChooser chooser, final Component parent, final boolean save, final boolean forScripts){
		final int[] result = { JFileChooser.CANCEL_OPTION };
		FileChooserIcons.install(chooser); // classic programs' and custom icons, which the host's own file view leaves blank
		runOnEDT(new Runnable(){
			public void run(){
				result[0] = save ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent);
			}
		});
		if(result[0]==JFileChooser.APPROVE_OPTION && forScripts){
			//ユーザが選んだファイル: 開いているスタックが使える(そのファイルは変更でき、フォルダは読める)。他のスタックやアプリには広げない(Sandbox)
			String requester = Sandbox.scriptRequester();
			if(chooser.getSelectedFile()!=null) Sandbox.chosenByUser(requester, chooser.getSelectedFile(), save);
			if(chooser.isMultiSelectionEnabled()) for(File f: chooser.getSelectedFiles()) Sandbox.chosenByUser(requester, f, save);
		}
		return result[0];
	}

	Pidle pidle;
	PaintIdle paidle;
	DropTarget drop;
	MyDropTargetListener droplistener;
	GToolBar toolbar;
	
	
    //プロパティ
	//HyperTalkは専用スレッド、描画はEDTで行うため、両スレッド間で必ず最新値を
	//共有する必要がある。
	static volatile boolean lockedScreen=false;
	//visual effect用に、画面遷移前後の状態をオフスクリーンへキャプチャしている間だけtrueに
	//する。lockedScreen中はボタン/フィールドの描画を止めて画面を凍結させるが、この
	//キャプチャ自体はlockedScreen==trueのまま行われるため、キャプチャ中はlockedScreenの
	//チェックを無視して普通に描画させる必要がある(そうしないと、視覚効果の後に一部の
	//部品が真っ白のまま描画されないままになってしまう)。
	static volatile boolean capturingEffect=false;
	static boolean showGroups=false;
    static int visual=0;
    static int toVisual=0; /* to black1 white2 grey3 inverse4 */
    static int visSpd=3; /* very fast1 fast2 normal3 slow4 veryslow5 */
    static int editMode = 0; //0:ブラウジング  1:アウトライン強調
    static String scriptFont = "";
    static int scriptFontSize = 12;
    static int userLevel = 5;
    String textFont = "";
    int textSize = 12;
    int textStyle = 0;
    //int textAlign = 0;
    int foundIndex = 0;
    String foundText;
    String foundObject;
    
    public static void main(final String[] args) {
    	if(!"false".equals(System.getProperty("hz.launcher"))) LauncherStore.enabled = true;
    	GLauncher.readIconsAhead();
    	pc = new PCARD();
		AppPaths.resourceTrashDirectory();

		//環境設定で選ばれた言語(未設定ならOSのロケールから推定)
		pc.lang = language;
		//スタックの文字コードの既定値（変換時はスタック内のテキストから推定し、判断できないときにこれを使う）
		HCData.setCurrentEncoding(StackTextEncoding.forLegacyLanguageSetting(pc.lang));

		// ウィンドウアイコンの設定
		defaultIcon = StartupWork.icon();
		if (defaultIcon != null) pc.setIconImage(defaultIcon);
        
		//拡大表示はひとつの設定(「設定」の拡大表示): スタックの拡大と68Kアプリのゲーム画面の拡大を揃えておく(別々だった頃の値の名残)
		if(ZOOM_OVERRIDE!=null) MacAppHost.screenScale = zoomChoice; //この起動だけ(設定には書かない)
		else if(screenScale68k!=zoomChoice) setScreen68k(screenSize68k, zoomChoice);

    	new OWindow(pc, true);
    	pc.rootPane.setDoubleBuffered(useDoubleBuffer);
    	pc.getRootPane().setOpaque(false);
    	pc.setBackground(Color.WHITE);
    	
    	pc.setLayout(new CardViewLayout(pc));
	pc.mainPane = new MyPanel(pc);
	pc.mainPane.setDoubleBuffered(useDoubleBuffer);
	//スクリプトが部品を変えるたびにカード全体を描き直さず、1フレームにまとめて描く(lock screen 中は描かない)
	CardRepaintManager.install();
	//pc.setContentPane(pc.mainPane/*, BorderLayout.CENTER*/);//こうするとツールバーがおかしくなる
	pc.add(pc.mainPane, BorderLayout.CENTER);
	pc.installStackDropTarget();
    	
		Cursor cr = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
		pc.setCursor(cr);

		try {
			pc.intl = new International(pc.lang);
		} catch (Exception e) {
			
		}
		if(args.length==0) GLauncher.showEarly();

    	pc.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				//クローズボックスはスタックを閉じてランチャーに戻る(HyperZebraは終了しない。終了はメニューの「終了」)。
				//「スタックを閉じる」メニューと同じ経路: closeCard等のスクリプト実行と保存待ちを経て閉じる。
				//ランチャーが無い(テスト)ときは従来どおり「終了」と同じ経路で終了する。
				if(GLauncher.enabled()){
					closeStackFromCloseBox();
					return;
				}
				quitFromMenu();
			}
		});
		installQuitHandler();
    	
    	OStack ostack = new OStack(pc);
    	pc.stack = ostack;

		pc.msg = new GMsg(pc, "");
		new VariableWatcher();
		new MessageWatcher();
		
		pc.menu = new GMenu(pc, 0);
		pc.paintMenu = new GMenu(pc, 1);
		JMenuBar menubar = new JMenuBar();
		pc.setJMenuBar(menubar);
		GMenu.menuUpdate(PCARD.pc.menu.mb);

		TTalk.talk = new TTalk(); //TTalkが異常終了したときのためにGUI.javaにスレッド再起動処理あり
		TTalk.talk.start();
		
		//Homeスタックは起動時に表示しない(ランチャーがその役)が、共通スクリプト(start using)としては従来通り読み込む。
		//「Home」メニューや go home は従来どおりHomeスタックへ移動する。
		//配布用Support Files/home.hzbをユーザー領域へ展開し、sampleHomeAppからはリソースだけを抽出する。
		HomeStack.ensure();
		HomeGuard.startUp();
		startUsingHome();
    	
    	String path = null;
    	if(args.length>0) {
    		path = args[0];
    	}
    	
    	//インストール済みのQuickTimeがあれば、XCMDのムービー再生に使うサービスVMを裏で立ち上げておく（最初の再生を待たせないため）
    	QuickTimeService.prewarm();

    	//起動ウインドウ(ランチャー): 何も指定されなければ前面に、アプリやスタックが指定されたらその裏に
    	//(指定対象からフォーカスを奪わない)。-Dhz.launcher=false(テスト)では作らない。
    	DiskImages.purgeStaleInBackground(); // extracted disks a HyperZebra that did not quit normally left behind
    	StackBundle.purgeStaleInBackground(); // working copies of stack files (.hzb ZIP) with nothing left to write back
    	TempFiles.sweepLater(); // run folders of working files (<tmpdir>/HyperZebra) that a killed HyperZebra left behind
    	//起動引数が68K Macアプリならそのアプリを先に起動する(スタックは開かない): ランチャーを作る間(約0.4秒)にVMが走り出す
    	boolean launched68k = path!=null && OStack.launch68kApplication(path);
    	StartupTrace.mark("before launcher");
    	GLauncher.install();
    	GLauncher.showLauncher(path==null);
    	//ゲームコントローラ: 割り当てたキー/マウスとして前面のスタックや68Kアプリに届く(-Dhz.gamepad=false で切る)
    	GamepadHost.start();
    	if(launched68k){
    		path = null;
    	}
    	//コントロールパネル('cdev'ファイル)は、そのパネルのウインドウを開く(Finderで開いたときと同じ)
    	if(path!=null && MacApp68k.isControlPanel(new File(path))){
    		try { openControlPanel(new File(path)); } catch (IOException e) { pc.failureOpenFile(path); }
    		path = null;
    	}

    	//スタックでない書類(アーカイブ、ディスクイメージ、フォント、拡張機能など)は、Finderから開いたときやドロップと同じ振り分けに回す。
    	//WindowsやLinuxのファイルの関連付けでは書類が起動引数で届く: .sit.hqx がスタックとして開かれ、PICTの一覧が出ていた
    	if(path!=null){
    		File document = new File(path);
    		boolean stack = path.toLowerCase(java.util.Locale.ROOT).endsWith(".xml") || OStack.looksLikeHyperCardStack(path) || OStack.stackFileType(path)
    			|| StackBundle.isPacked(document) || ZipExpander.isZip(document);
    		if(document.isFile() && !stack){
    			openFromHost(java.util.Collections.singletonList(document));
    			path = null;
    		}
    	}
    	if(path!=null){
    		if(new File(path).exists()) ostack.openStackFile(path, false);
    		else pc.failureOpenFile(path);
    	}

    	HyperZebra.installMacHandler();
    	openedFilesReady(); //起動中にFinderから届いた書類を開く
    	
    	//idle処理
    	pc.pidle = new Pidle();
    	pc.pidle.start();
    	
    	pc.paidle = new PaintIdle();
		pc.paidle.start();
    }
    
	//Homeスタック(HomeStack.folder())を共通スクリプトとして読み込む(表示はしない)。起動時と、スタックを閉じて空になったとき。
	static void startUsingHome() {
		String homepath = HomeStack.folder().getPath();
		if(!new File(homepath).exists()) return;
		try {
			TTalk.doScriptforMenu("start using stack "+TTalk.quoteLiteral(homepath));
		} catch (xTalkException e1) {
			e1.printStackTrace();
		}
		//home は start using が読み込んだ Home スタック自身(TTalk の start using が設定する)。以前はここで空の
		//プレースホルダのスタックを指していたため、開いたスタックから Home のアイコン(ICON 1011 等)や
		//スクリプトが見えなかった(start using はスクリプトスレッドで後から走ることもある)
		if(home==null) home = pc.stack;
	}

	/**
	 * The card window's close box. HyperCard J-2.3 runs "close card window" for it, not doMenu "Close Stack", so a
	 * stack whose doMenu handler swallows the File menu (Adventures of Sean 2 passes only a list of items and beeps
	 * for the rest, with the menu bar hidden) still closes: closeCard/closeBackground/closeStack are sent, no doMenu.
	 */
	static void closeStackFromCloseBox() {
		if(pc==null) return;
		if(pc.stack!=null && pc.stack.curCard!=null && Thread.currentThread()!=TTalk.talk){
			//the window hides at once (the frame's HIDE_ON_CLOSE), so a handler still running (a loop waiting for a click,
			//an animation, a long wait) ends here as with Command-period: before, the close waited behind it and the
			//invisible stack went on running. The close survives the abort's emptying of the message queue (keep)
			if(!TTalk.idle) TTalk.abortForStackClose();
			TTalk.runOnScriptThread(PCARD::closeCurrentStack, true);
			return;
		}
		closeCurrentStack();
	}

	/**
	 * Closes the stack on show (the File menu's Close Stack, the last card's "go home" without a Home stack):
	 * closeCard/closeBackground/closeStack, the save, then the window hides and the launcher comes back - or, with other
	 * stacks open in windows of their own (GStackWindow), the frontmost of them takes the card window.
	 * Runs on the script thread when a card is open (the close messages run there), so the menu queues it as a
	 * doMenu the stack can catch; nothing else quits. The Quit menu still ends HyperZebra. The close box: above.
	 */
	static void closeCurrentStack() {
		if(pc==null) return;
		if(pc.stack!=null && pc.stack.curCard!=null && Thread.currentThread()!=TTalk.talk){
			//スタックが File メニューから「Close Stack」を消していたら(Blah Blob の cleanMenus)メニュー名なしの doMenu にして、
			//メニューに無い項目として組み込み動作へ渡す(ランチャーの「スタックを閉じる」はHyperZebra自身のメニュー)
			javax.swing.JMenu file = TTalk.findMenu("File");
			boolean inFileMenu = file!=null && TTalk.findMenuItemIndex(file, "Close Stack")>=0;
			TTalk.CallMessage("doMenu", inFileMenu ? new String[]{"Close Stack", "File"} : new String[]{"Close Stack"}, pc.stack.curCard);
			return;
		}
		closeStackHere(false);
	}

	/**
	 * The launcher's "Force Quit…" of the stack on show, for a stack whose scripts no longer answer: the running handlers
	 * end at once (cantAbort or not) and the stack closes without closeCard/closeBackground/closeStack; its changes are
	 * written as on any close (HyperCard keeps each change as it is made). A script thread that does not get back from the
	 * handlers within a second is interrupted, and one still away after three seconds (stuck in a host call) is left
	 * behind: a new script thread closes the stack, and other stacks go on running.
	 */
	public static void forceQuitStack() {
		final PCARD p = pc;
		if(p==null || p.stack==null || p.stack.curCard==null) return;
		final OStack stack = p.stack;
		System.err.println("[stack "+stack.name+"] force quit");
		final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
		TTalk.abortForStackClose();
		TTalk.runOnScriptThread(() -> {
			if(!started.compareAndSet(false, true)) return;
			Thread.interrupted(); //the watchdog's interrupt, if it came just now: the script thread's waits must not end at once from here on
			if(pc!=null && pc.stack==stack) closeStackHere(true);
		}, true);
		Thread watchdog = new Thread(() -> {
			try{
				for(int waited=0; waited<3000 && !started.get(); waited+=50){
					Thread.sleep(50);
					if(waited==1000 && !started.get()) TTalk.interruptScriptThread();
				}
			}catch(InterruptedException e){ return; }
			if(!started.get()) TTalk.replaceStuckThread();
		}, "stack force quit: "+stack.name);
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** Closes the stack on show on the script thread; {@code forced} (a force quit): no close messages, and the handlers
	 *  stopped by it are over (their abort is cleared). */
	private static void closeStackHere(boolean forced) {
		//close window "<this stack>" from the closeCard the closing sends (Pictoids 2.0's cleanupWindows closes every window
		//that was not there at openStack): the stack is closing already; closing it again recursed until "too much recursion".
		//A force quit sends none, and goes ahead even when an earlier close was left unfinished (its thread stuck)
		if(closingStack && !forced) return;
		if(forced) TTalk.stop = false; //the aborted handlers are over: the stack coming back (another window) runs its messages
		//other stacks open in windows of their own: this one closes and the frontmost of them comes back into the card
		//window (as close card window); the launcher comes back only with the last stack
		if(TTalk.closeToOtherWindow(forced)) return;
		closingStack = true;
		try{
			if(!prepareStackClose(StackSaving.Purpose.CLOSE, forced)) return; //保存できず、ユーザーが閉じるのをやめた
			OStack old = pc.stack;
			pc.stack = new OStack(pc);
			TTalk.dropMessagesFor(old);
			//HyperCard を終了したときと同じく、スタックが鳴らしていた音(play の各チャンネル、Movie XCMD のムービー、読み上げ)を止める。
			//以前はランチャーに戻ってもBGMが鳴り続けた。68K externalのサウンドは old.clean() の Xcmd68k.stackClosing で止まる
			stopStackSounds();
			GPictWindow.closeStackWindows();
			if(old!=null) old.clean();
			TTalk.forgetWindowStacks();
			TUtil.resetStackProperties();
			resetMenuBarAfterClose();
			pc.emptyWindow();
		}finally{
			closingStack = false;
		}
	}
	private static boolean closingStack = false;

	/** The sounds a closed stack started: play on every soundChannel, movies of the Movie XCMD, speech. */
	static void stopStackSounds() {
		try{ TTalk.stopAllSounds(); }catch(RuntimeException e){ e.printStackTrace(); }
		try{ TQTMovie.disposeAll(); }catch(RuntimeException e){ e.printStackTrace(); }
		try{ Speech.stopAll(); }catch(RuntimeException e){ e.printStackTrace(); }
	}

	/**
	 * After a stack closed back to the launcher the menu bar is HyperCard's own again, as when HyperCard starts: the menus a
	 * stack made or deleted belonged to that session. ROTARY5's openStack does `create menu "High Score"` without
	 * checking, and opening it a second time from the launcher failed with "a menu with that name already exists".
	 */
	static void resetMenuBarAfterClose() {
		if(pc==null || pc.menu==null) return;
		try{
			pc.menu = new GMenu(pc, 0);
			//ペイントツール用のメニューも作り直す: 言語を切り替えたあと、ペイントのメニューだけ起動時の言語のまま残っていた
			pc.paintMenu = new GMenu(pc, 1);
			TTalk.resetAppleMenu();
			GMenu.menuUpdate(pc.menu.mb);
		}catch(RuntimeException e){
			e.printStackTrace();
		}
	}

	/**
	 * The first half of closing the stack on show: closeCard/closeBackground/closeStack, the paint tool's pictures, the
	 * resource editor's unsaved changes, then the stack's own changes are written. False when the write failed and the user
	 * chose to keep the stack (StackSaving): it is then opened again (openStack/openBackground/openCard) and nothing is
	 * thrown away. Runs on the script thread when a card is open.
	 */
	static boolean prepareStackClose(StackSaving.Purpose purpose) { return prepareStackClose(purpose, false); }
	/** The same; {@code forced} (a force quit) sends no close messages. */
	static boolean prepareStackClose(StackSaving.Purpose purpose, boolean forced) {
		boolean sentClose = false;
		if(!forced && pc.stack!=null && pc.stack.curCard!=null){
			try {
				TTalk.doScriptforMenu("closeCard");
				if(pc.stack.curCard!=null && pc.stack.curCard.bg != null) TTalk.doScriptforMenu("closeBackground");
				TTalk.doScriptforMenu("closeStack");
				sentClose = Thread.currentThread()==TTalk.talk;
			} catch(Exception ignored) {
			}
		}
		if(pc.tool!=null){
			PaintTool.endCardTool();
			pc.tool = null;
			PaintTool.saveCdPictures();
			pc.releasePaintBuffers();
		}
		OStack old = pc.stack;
		if(old!=null && old.curCard!=null) old.recordRecentCard(old.curCard);
		//リソースエディタに未保存の変更があれば、スタックの保存の前に保存するか尋ねる
		if(old!=null) ResSession.stackClosing(old);
		//スクリプトエディタの未保存のスクリプトも保存するか尋ねる。キャンセルなら閉じるのをやめる
		//ディスクに書けたことを確かめてから閉じる。書けなければ再試行・別の場所に保存・閉じるのをやめる、を選ぶ
		if(old==null || (ScriptEditor.stackLeaving(old) && StackSaving.ensureSaved(old, purpose))) return true;
		if(sentClose && old.curCard!=null){
			try {
				TTalk.doScriptforMenu("openStack");
				if(old.curCard!=null && old.curCard.bg!=null) TTalk.doScriptforMenu("openBackground");
				TTalk.doScriptforMenu("openCard");
			} catch(Exception ignored) {
			}
		}
		return false;
	}

	/** The application icon (resource/icon.png) the card window shows while no stack with its own icon is open. */
	static Image defaultIcon;

	/**
	 * Shows the open stack's Finder icon in the card window's frame (the title-bar icon on Windows, the taskbar/dock
	 * entry elsewhere), as a hosted 68K program's windows show its 'icl8': a converted stack keeps the custom icon as
	 * icl8/ics8 (+ ICN#/ics#) files beside _stack.xml, and both sizes are offered so the title bar gets the 16x16 'ics8'.
	 * Home and stacks without an icon fall back to the application icon. EDT only.
	 */
	static void updateWindowIcon() {
		java.util.List<Image> icons = new java.util.ArrayList<Image>();
		File open = GLauncher.openStack();
		if(open!=null && StackBundle.isPacked(open)) open = StackBundle.iconFolder(open);
		if(open!=null && open.isDirectory()){
			try{
				BufferedImage large = FinderIcons68k.ofStackBundle(open, 32), small = FinderIcons68k.ofStackBundle(open, 16);
				if(large!=null) icons.add(large);
				if(small!=null && small!=large) icons.add(small);
			}catch(RuntimeException ignored){}
		}
		if(icons.isEmpty()){
			if(defaultIcon!=null) icons.add(defaultIcon);
			else { pc.setIconImages(null); return; }
		}
		pc.setIconImages(icons);
	}

    void successOpenFile()
    {
		if(!SwingUtilities.isEventDispatchThread()){
			try {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() { successOpenFile(); }
				});
			} catch (Exception e) {
				throw new IllegalStateException("Could not finish opening the stack", e);
			}
			return;
		}

		PCARD.lockedScreen = true;
		
    	PaintTool.owner = PCARDFrame.pc;
    	//変換後のスタック(.hzb)もそのスタック自身のもの(開く前に許可したのは元のファイルとそのフォルダ)
    	if(PCARDFrame.pc.stack.file!=null) Sandbox.stackOpened(PCARDFrame.pc.stack.file);
    	//スタック個別設定(拡大表示・キー・コントローラ)。ウインドウの大きさを決める前に
    	StackSettings.apply(PCARDFrame.pc.stack);
    	PCARDFrame.pc.stack.buildStackFile(false);
    	//翻訳がオンのスタックは、ここで言語を調べ始める(最初の描画を待たない)
    	StackTranslation.state(PCARDFrame.pc.stack);
    	updateWindowIcon();
    	//スタックの「ユーザーレベルの制限」をメニュー・ツール・フィールドへ反映する
    	GMenu.enforceUserLevel();

    	//最近使ったスタックに追加(ランチャーの履歴。Homeは除く)
		if(LauncherStore.identity(new File(PCARDFrame.pc.stack.path)).equals(LauncherStore.identity(HomeStack.folder()))){
    		return;
    	}
    	String path = PCARDFrame.pc.stack.path;
    	if(new File(path).getName().equals("_stack.xml") ||
    			new File(path).getName().equals("toc.xml")){
    		path = new File(path).getParent();
    	}
    	if(LauncherStore.enabled) LauncherStore.get().recordOpened(StackBundle.host(new File(path)), LauncherStore.KIND_STACK);
    	GMenu.BuildRecentMenu((JMenu)PCARD.pc.getJMenuBar().getComponent(0));
	}
    
    void failureOpenFile(String path){
		if(pc.stack.curCard!=null) {
			pc.stack.curCard.removeData();
			if(pc.stack.curCard.bg!=null) pc.stack.curCard.bg.removeData();
		}
		if(GLauncher.enabled()){
			//ランチャーがある: 表示中のスタックがあればそのまま(go stack の失敗)、無ければランチャーを前面にして理由を伝える
			if(!isVisible()) GLauncher.showFailure(path, PCARDFrame.pc.intl.getDialogText("Could't open the file."));
			else new GDialog(pc, PCARDFrame.pc.intl.getDialogText("Could't open the file."), null,"OK",null,null);
			return;
		}
		pc.emptyWindow();
		if(!path.equals("./home")){
			new GDialog(pc, PCARDFrame.pc.intl.getDialogText("Could't open the file."),
					null,"OK",null,null);
		}
    }
	
	/**
	 * The stack window's menu bar and title bar come back when its stack closes: `hide menuBar` / `hide titleBar` belonged to
	 * that stack (HyperCard keeps them hidden across go stack, which HyperZebra keeps too, but a stack opened later from
	 * the launcher got the window still without both). A `show menuBar` waiting for unlock screen is dropped with it.
	 */
	static void restoreWindowChrome() {
		TTalk.showMenuBarOnUnlock = false;
		TTalk.setMenuBarVisible(true);
		TTalk.setTitleBarVisible(true);
	}

	//スタックが無くなった(閉じた、Homeが無い状態での go home): ランチャーがあればスタックのウインドウを隠して
	//ランチャーに戻る。無ければ(テスト)従来の「ここにドロップ」の簡易ウインドウ。
    public void emptyWindow() {
		StackSettings.clear(); //スタックを閉じた: 個別設定はもう効かない
		TTalk.startUpSent = false; //back to the launcher: the next stack opened starts a session of its own (startUp again)
		TTalk.closeAllOpenFiles(); //files a script left open (stacks no longer close them when left)
		TTalk.forgetOpenField();
		if(GLauncher.enabled()){
			if(stack==null || stack.cdCacheList==null) stack = new OStack(this); //閉じた(clean済み)スタックの代わりの空のスタック
			runOnEDT(new Runnable(){ public void run(){
				//the next stack starts like the first one: Browse tool, no message box, palettes or info windows. All go before
				//the card window hides: windows it owns still shown then come back with it (the message box, Button Info, the
				//colour palette), and the tool and layer palettes come back on its next activation when shown at its last one
				TBButtonListener.resetForNoStack();
				if(msg!=null) msg.setVisible(false);
				if(toolbar!=null) toolbar.hideForNoStack();
				GLayerPalette.hideForNoStack();
				if(mainPane!=null) mainPane.removeAll();
				setVisible(false);
				//the hidden window kept the closed stack's last picture (Swing's buffer for the window, the native window):
				//the next stack opened showed it for a moment before its own card was drawn. Disposed, the window starts blank
				//like at start-up (hidden first: owned windows still come back with it)
				dispose();
			}});
			restoreWindowChrome(); //hidden first: putting the title bar back re-creates the frame
			if(Thread.currentThread()==TTalk.talk) startUsingHome();
			else TTalk.CallMessage("start using stack \"./home\"", "", null, false, true);
			GLauncher.showIfNothingOpen();
			return;
		}
		restoreWindowChrome();
		setTitle(PCARDFrame.pc.intl.getDialogText("Drop file here"));
		
		Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
		if(d!=null){
			setBounds(d.width/2-400/2, d.height/2-320/2-20, 400, 320+20);
		}else{
			setBounds(0, 0-320-20, 400, 320+20);
		}
		setVisible(true);
		//setLocationRelativeTo(null);
		installStackDropTarget();
	}

	/**
	 * Opens files the user handed over (a drop on a window, the launcher's history, dock and Open File…): font
	 * suitcases and system extensions are installed in the virtual System Folder, 68K programs launched (one already
	 * running is brought to the front instead of a second copy), documents of 68K programs go to the program that
	 * opens them (or the user picks one: {@link GLauncher#openDocument}), anything else is opened as a stack (or a
	 * picture) in the stack window.
	 */
	/** Shows a control panel's window (the 'cdev' file in the System Folder's Control Panels), or brings it to the front when it is open. */
	/**
	 * A Kaleidoscope scheme was opened: it becomes the current scheme and running programs redraw in it. Nothing else shows
	 * the change when no program runs (HyperZebra's own windows are not themed), so that is when it is told.
	 */
	private static void schemeOpened(File scheme) {
		try { KaleidoscopeScheme68k.select(scheme); }
		catch (IOException ex) {
			new GDialog(pc, pc.intl.getDialogText("Can't switch the Kaleidoscope scheme.")+"\n"+ex.getMessage(), null, "OK", null, null);
			return;
		}
		MacApp68k.schemeChangedAll();
		if(KaleidoscopeScheme68k.controlPanel(QuickTimeService.systemFolder())==null)
			new GDialog(pc, pc.intl.getDialogText("Switched the Kaleidoscope scheme to:")+"\n"+scheme.getName()+"\n"+pc.intl.getDialogText("Put the Kaleidoscope control panel in Control Panels to use them."), null, "OK", null, null);
		else if(MacApp68k.runningApplications().isEmpty())
			new GDialog(pc, pc.intl.getDialogText("Switched the Kaleidoscope scheme to:")+"\n"+scheme.getName(), null, "OK", null, null);
	}
	/** Tells where dropped Kaleidoscope schemes went; the Kaleidoscope control panel, when installed, can be opened to choose one. */
	private static void schemesInstalled(List<String> names) {
		File panel = KaleidoscopeScheme68k.controlPanel(QuickTimeService.systemFolder());
		String open = pc.intl.getDialogText("Open Kaleidoscope");
		new GDialog(pc, pc.intl.getDialogText("Installed Kaleidoscope schemes in Apple Menu Items/Kaleidoscope Schemes:")+"\n"+String.join(", ", names)
			+"\n"+(panel!=null ? pc.intl.getDialogText("Choose one with Select Scheme... in the Kaleidoscope control panel.") : pc.intl.getDialogText("Put the Kaleidoscope control panel in Control Panels to use them.")),
			null, panel!=null ? open : "OK", panel!=null ? "OK" : null, null);
		if(panel!=null && open.equals(GDialog.clicked)){
			try { openControlPanel(panel); }
			catch (IOException ex) { new GDialog(pc, ex.getMessage(), null, "OK", null, null); }
		}
	}

	static void openControlPanel(File cdev) throws IOException {
		MacApp68k running = MacApp68k.runningFrom(cdev);
		if(running!=null){ running.bringToFront(); return; }
		MacApp68k.launchControlPanel(cdev);
		if(LauncherStore.enabled) LauncherStore.get().recordOpened(cdev, LauncherStore.KIND_APP); //ランチャーの履歴: 開けたコントロールパネルもプログラムと同じく残す
	}

	/**
	 * The Finder's Open Documents ('aevt'/'odoc') sent by a Mac program - DragThing's dock opens what is clicked this way:
	 * each item opens as a double-click would. Mac programs and their documents, stacks, pictures, fonts... go the way of
	 * {@link #openFiles}; programs of the host (a Mac OS X application, a Windows program) and documents only the host opens
	 * are opened by the host once the Sandbox allows {@code requester} to run them; a folder opens in the host's file manager.
	 */
	static void openFromFinder(final String requester, final List<File> files) {
		if(files==null || files.isEmpty()) return;
		Thread t = new Thread(() -> { // the Sandbox may ask: neither the program's thread nor the EDT waits for that
			List<File> mine = new java.util.ArrayList<File>();
			for(File f: files){
				boolean folder = f.isDirectory() && MacApp68k.programOf(f)==f && !GLauncher.isHostProgram(f)
					&& !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") && !new File(f, "_stack.xml").isFile() && !new File(f, "toc.xml").isFile();
				if(folder || GLauncher.opensOnHost(f)){
					if(!folder && !Sandbox.allowOther(requester, Sandbox.RUN_PROGRAM, f.getPath())) continue;
					try { openOnHost(f); }
					catch (Exception ex) { System.err.println("[Finder] cannot open "+f+" on the host: "+ex); }
				}
				else mine.add(f);
			}
			if(!mine.isEmpty() && pc!=null) javax.swing.SwingUtilities.invokeLater(() -> openFiles(mine));
		}, "Finder open");
		t.setDaemon(true); t.start();
	}
	/**
	 * Opens a file, folder or program with the host's own shell. Desktop.open fails on Mac OS X application bundles and on
	 * Windows programs ("Unsupported URI content" for steam.exe in DragThing's dock): those go through /usr/bin/open and
	 * ShellExecute (rundll32 ShellExec_RunDLL, which also follows shortcuts and runs .bat/.msc/.appref-ms).
	 */
	static void openOnHost(File f) throws IOException {
		if(GMenu.isMacOSX()) new ProcessBuilder("/usr/bin/open", f.getAbsolutePath()).start();
		else if(ResourceFile68k.WINDOWS && GLauncher.isHostProgram(f)) new ProcessBuilder("rundll32.exe", "shell32.dll,ShellExec_RunDLL", f.getAbsolutePath()).directory(f.getAbsoluteFile().getParentFile()).start();
		else java.awt.Desktop.getDesktop().open(f);
	}

	static void openFiles(List<File> fileList) { openFiles(fileList, true); }
	/** {@code askStacks}: a HyperCard stack that can also run in the 68K VM asks how to open it ({@link GLauncher#openStack}); false imports it. */
	static void openFiles(List<File> fileList, boolean askStacks) {
		if(fileList==null || fileList.isEmpty()) return;
		List<File> others = new java.util.ArrayList<File>();
		List<String> installedFonts = new java.util.ArrayList<String>(), installedExtensions = new java.util.ArrayList<String>();
		List<File> installedSchemes = new java.util.ArrayList<File>();
		for(File dropped: fileList){
			dropped = MacApp68k.programOf(dropped); // a Mac OS 9 package ("X.app" folder) stands for the program inside
			if(DiskImages.openFromUser(dropped)) continue; // a disk image is mounted for Mac programs (a .smi is a program too: not launched)
			if(InstalledFonts68k.isFontFile(dropped)){
				try { installedFonts.addAll(InstalledFonts68k.install(dropped)); }
				catch (IOException ex) {
					new GDialog(pc, pc.intl.getDialogText("Can't install the font.")+"\n"+ex.getMessage(), null, "OK", null, null);
				}
				continue;
			}
			List<File> schemes = KaleidoscopeScheme68k.isSchemeFile(dropped) ? java.util.Collections.singletonList(dropped) : KaleidoscopeScheme68k.schemesIn(dropped);
			if(!schemes.isEmpty()){ // a Kaleidoscope scheme (or a folder holding schemes) goes to Apple Menu Items' Kaleidoscope Schemes
				try { for(File scheme: schemes) installedSchemes.add(KaleidoscopeScheme68k.installed(scheme)); }
				catch (IOException ex) {
					new GDialog(pc, pc.intl.getDialogText("Can't install the Kaleidoscope scheme.")+"\n"+ex.getMessage(), null, "OK", null, null);
				}
				continue;
			}
			if(Extensions68k.isExtensionFile(dropped)){ // before programs: an extension's INIT often lives in CODE segments too
				try {
					File installed = Extensions68k.install(dropped);
					if(MacApp68k.isControlPanel(installed)) openControlPanel(installed); // a control panel opens like a program (its window), from its place in Control Panels
					else installedExtensions.add(installed.getName());
				}
				catch (IOException ex) {
					new GDialog(pc, pc.intl.getDialogText("Can't install the extension.")+"\n"+ex.getMessage(), null, "OK", null, null);
				}
				continue;
			}
			MacApp68k running = MacApp68k.runningFrom(dropped);
			if(running!=null){ running.bringToFront(); continue; }
			if(OStack.launch68kApplication(dropped.getPath())) continue;
			if(askStacks && GLauncher.openStack(dropped)) continue; // a stand-alone application stack, or a stack HyperCard in the VM opens
			if(GLauncher.openDocument(dropped)) continue;
			if(MacApp68k.isMachOPackage(dropped)){ // a Mac OS X application: nothing the VM can run (it was silently opened as a stack)
				new GDialog(pc, pc.intl.getDialogText("Can't launch the Mac application.")+"\n"+pc.intl.getDialogText("This is a Mac OS X (Mach-O) application. HyperZebra runs classic Mac OS programs only.")
					+"\n"+dropped.getName(), null, "OK", null, null);
				continue;
			}
			others.add(dropped);
		}
		if(!installedFonts.isEmpty()){
			new GDialog(pc, pc.intl.getDialogText("Installed fonts for Mac programs:")+"\n"+String.join(", ", installedFonts)
				+"\n"+pc.intl.getDialogText("Programs launched from now on use them."), null, "OK", null, null);
		}
		if(!installedExtensions.isEmpty()){
			new GDialog(pc, pc.intl.getDialogText("Installed extensions for Mac programs:")+"\n"+String.join(", ", installedExtensions)
				+"\n"+pc.intl.getDialogText("Programs launched from now on use them."), null, "OK", null, null);
		}
		if(installedSchemes.size()==1) schemeOpened(installedSchemes.get(0)); // several: nothing says which, the panel chooses
		else if(!installedSchemes.isEmpty()){
			List<String> names = new java.util.ArrayList<String>();
			for(File scheme: installedSchemes) names.add(scheme.getName());
			schemesInstalled(names);
		}
		if(!others.isEmpty()){
			//スタックは "open stack … in a new window" として開く: 表示中のスタックは閉じずに自分のウインドウ(GStackWindow)に残り、
			//複数のスタックを同時に開いておける(HyperCard 2.x の「新規ウインドウで開く」)。goToStack が保存を済ませる
			if(pc.stack==null) pc.stack = new OStack(pc);
			for(int i=0; i<others.size(); i++){
				pc.stack.openStackFile(others.get(i).toString(), false, true);
			}
		}
	}

	void installStackDropTarget() {
		if(mainPane==null) return;
		if(droplistener==null) droplistener = new MyDropTargetListener();
		if(mainPane.getDropTarget()==drop && drop!=null) return;
		drop = new DropTarget(mainPane, droplistener);
	}

	private class MyDropTargetListener extends DropTargetAdapter {
		/*
		 * @see java.awt.dnd.DropTargetListener#drop(java.awt.dnd.DropTargetDropEvent)
		 */
		@Override
		public void drop(DropTargetDropEvent e) {
			boolean accepted = false;
			try {
				Transferable transfer = e.getTransferable();
				if (!transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
					e.rejectDrop();
					return;
				}
				e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
				accepted = true;
				@SuppressWarnings("unchecked")
				List<File> fileList =
					(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));
				if(fileList.isEmpty()){
					e.dropComplete(false);
					return;
				}
				//68Kアプリケーションは現在のスタックを閉じずに起動する
				//フォントスーツケースはSystem FolderのFontsフォルダにインストールする
				openFiles(fileList);
				e.dropComplete(true);
			} catch (Exception ex) {
				if(accepted) e.dropComplete(false);
				ex.printStackTrace();
			}
		}
	}
    
	PCARD() {
        GUI.gui.addMouseListener(this);
        this.addKeyListener(GUI.gui);
        setFocusTraversalPolicy(new CardFocusPolicy());
		GUI.installGlobalKeyDispatcher();
		GUI.installGlobalMouseWatcher();
        //this.addWindowListener(paintGUI.gui);
        this.addWindowListener(new PCARDWindowListener(this));
    }
}


/**
 * The card window's keyboard focus. HyperCard puts an insertion point in an unlocked field only when it is clicked, tabbed
 * into or selected by a script; showing or activating the card window leaves the keys with the card. Swing's own policy
 * gave the first focusable part (an unlocked field) the focus whenever the window was shown or activated without an
 * earlier focus owner, so a caret blinked in that field (Myst Preview's "pict name" over HyperTint's picture). With no
 * initial component the window itself takes the keys (PCARD's key listener), as after a click on the card.
 */
class CardFocusPolicy extends javax.swing.LayoutFocusTraversalPolicy {
	private static final long serialVersionUID = 1L;
	@Override public Component getInitialComponent(java.awt.Window window){ return null; }
	@Override public Component getDefaultComponent(java.awt.Container root){ return root instanceof PCARDFrame ? null : super.getDefaultComponent(root); }
}

class PCARDWindowListener implements WindowListener
{
	PCARD owner;
	
	PCARDWindowListener(PCARD owner){
		this.owner = owner;
	}
	
	@Override
	public void windowActivated(WindowEvent arg0) {
		GUI.cardActivated();
		PaintTool.owner = owner;
		if(owner!=null && owner.toolbar!=null){
			owner.toolbar.activate();
		}
		GLayerPalette.activate();
	}
	@Override
	public void windowClosed(WindowEvent arg0) {
	}
	@Override
	public void windowClosing(WindowEvent arg0) {
	}
	@Override
	public void windowDeactivated(WindowEvent arg0) {
		//離したキーは別のウインドウへ行くので、押されていたキーとピークをここで終える
		GUI.cardDeactivated();
		if(owner!=null && owner.toolbar!=null){
			owner.toolbar.deactivate();
		}
		GLayerPalette.deactivate();
	}
	@Override
	public void windowDeiconified(WindowEvent arg0) {
	}
	@Override
	public void windowIconified(WindowEvent arg0) {
	}
	@Override
	public void windowOpened(WindowEvent arg0) {
	}
}


class Pidle extends Thread {
	/** idle every 100 ms (10 a second) unless an "on idle" handler is running; then at display rate (at HyperCard's pace
	 *  under a stack's speed limit: StackSpeed.IDLE_INTERVAL_NANOS). */
	static final long SLOW_IDLE_NANOS = 100_000_000L;
	static final long FAST_IDLE_NANOS = 1_000_000_000L/60;

	/** HyperCard goes on sending idle while one of its windoids or an external's window is in front of the card window
	 *  (Myst Preview's xWindowFrame backdrop took the focus and the intro never started); not while a script editor or
	 *  another HyperZebra tool window is being used, nor while HyperZebra is in the background. */
	static boolean idleWindowActive() {
		PCARD pc = PCARD.pc;
		if(pc==null) return false;
		if(pc.isActive()) return true;
		java.awt.Window w = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if(w==null || w instanceof ScriptEditor || w instanceof java.awt.Frame) return false;
		//an external's window without an owner of its own: Swing gives it the hidden shared owner frame, not null
		boolean ownerless = w.getOwner()==null || !w.getOwner().isShowing();
		return w==GMsg.msg || w.getOwner()==pc || ownerless && w instanceof javax.swing.JDialog && !((javax.swing.JDialog)w).isModal();
	}

	@Override
	public void run() {
        this.setName("idle thread");
		long lastIdle = 0, nextKeyCheck = 0, nextFocusCheck = 0;
		while(true){
			long now = System.nanoTime();
			boolean fast = TTalk.idleHandlerAt!=0 && now-TTalk.idleHandlerAt < 1_000_000_000L;
			if(PCARDFrame.pc.stack!=null && PCARDFrame.pc.stack.curCard!=null && PCARDFrame.pc.tool==null && AuthTool.tool==null){
				if(TTalk.idle && !TTalk.idlePending && !TTalk.idleMessageRunning && !TTalk.idleMessagesSuppressed && idleWindowActive() &&
						now-lastIdle >= (fast ? StackSpeed.limited() ? StackSpeed.IDLE_INTERVAL_NANOS : FAST_IDLE_NANOS : SLOW_IDLE_NANOS)){
					if(System.currentTimeMillis() >= TTalk.idleResumeTime){
						//前回のidleメッセージがカード移動を伴う場合、処理が終わるまでは
						//新しいidleメッセージを積まない(積むと古いカードの参照が残ってしまう)
						lastIdle = now;
						TTalk.idlePending = true;
						TTalk.CallMessage("idle","",PCARDFrame.pc.stack.curCard, true, false);
					}
				}
				if(TTalk.idle) GUI.flushPointerMessages();
				if(now>=nextKeyCheck){
					nextKeyCheck = now+SLOW_IDLE_NANOS;
					GUI.keyEventCheck();
				}
				//PCARD.pc.stack.pcard.requestFocusInWindow(); //これするとフィールドに文字打てなくなる
			}
			if(now>=nextFocusCheck){
				nextFocusCheck = now+SLOW_IDLE_NANOS;
				GUI.ensureCardFocus();
			}
			else if(PCARDFrame.pc.stack!=null && PCARDFrame.pc.stack.curCard!=null && PCARDFrame.pc.tool==null && AuthTool.tool!=null){
				//ボタン/フィールド/シェイプツール中も opt+cmd のピークを使えるように
				if(now>=nextKeyCheck){
					nextKeyCheck = now+SLOW_IDLE_NANOS;
					GUI.keyEventCheck();
				}
			}
			try{
				sleep(fast ? 2 : 20);
			} catch (InterruptedException e) {
		          Thread.currentThread().interrupt();
			}
			if (Thread.currentThread().isInterrupted()) {
				break;
			}
		}
	}
}

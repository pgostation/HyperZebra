import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.image.BufferedImage;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;

//Pictureウインドウはカードウインドウを親に持つ非モーダルダイアログとして作る。
//独立したJFrameにしていた頃は、answerなどのモーダルダイアログを表示した瞬間にOSが
//オーナー(カードウインドウ)をまとめて最前面へ上げてしまい、Pictureウインドウが
//カードウインドウの背面に隠れていた(しかもモーダル表示中のJFrameはtoFront()を
//無視されるため、後から持ち上げ直すこともできなかった)。
//カードウインドウを親にしておくと、モーダルダイアログと同じウインドウグループに
//入るため「ダイアログ→Pictureウインドウ→カードウインドウ」の順序が保たれる。
//オーナー付きの非モーダルダイアログは他アプリのウインドウより前には出ないので、
//フローティングパレットのように常に最前面へ張り付く心配もない。
public class GPictWindow extends JDialog {
	/** HyperCard 2.2 picture window property: colour pictures shown on fewer colours are dithered. Kept for scripts (the picture is shown in full colour). */
	boolean dithering = false;
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	String name;
	MyLabel3 label;
	private boolean paletteWindow;
	/** paletteコマンドのウインドウ: 絵とボタンを入れる領域(タイトルバーの下)。それ以外はnull */
	private JPanel paletteContent;
	//paletteコマンドのパレットの状態(PLTEリソースから)
	private final java.util.List<AbstractButton> paletteButtons = new java.util.ArrayList<AbstractButton>();
	private final java.util.List<String> paletteCommands = new java.util.ArrayList<String>();
	/** PLTEのType: -1=押している間だけ点灯、0以上=点灯が残る */
	private int paletteHiliteType;
	/** PLTEのHilite Mode: 0=反転、1=枠 */
	private int paletteHiliteMode;
	/** 点灯中のボタン番号(1始まり)。0=なし、-1=押している間だけ点灯するパレット */
	private int hilitedButton;
	private double paletteScale = 1;
	private BufferedImage paletteImage;
	private final PCARD cardWindow;
	private final WindowAdapter cardActivationListener;
	private final AWTEventListener cardClickListener;
	
	GPictWindow(PCARD owner, String name, BufferedImage bi, String windowType, boolean visible) {
		super(owner, name, false);
		this.name = name;
		cardWindow = owner;
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		setAlwaysOnTop(false);
		setAutoRequestFocus(false);
		cardActivationListener = new WindowAdapter() {
			@Override
			public void windowActivated(WindowEvent event) {
				SwingUtilities.invokeLater(() -> {
					if(cardWindow!=null && cardWindow.isActive() && isVisible()) toFront();
				});
			}
		};
		if(cardWindow!=null) cardWindow.addWindowListener(cardActivationListener);
		cardClickListener = event -> {
			if(event.getID()!=MouseEvent.MOUSE_PRESSED || !isVisible() ||
				!(event.getSource() instanceof Component)) return;
			Component source = (Component)event.getSource();
			Window sourceWindow = source instanceof Window ?
				(Window)source : SwingUtilities.getWindowAncestor(source);
			if(sourceWindow!=cardWindow) return;
			SwingUtilities.invokeLater(() -> {
				if(cardWindow!=null && cardWindow.isActive() && isVisible()) toFront();
			});
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(
			cardClickListener, AWTEvent.MOUSE_EVENT_MASK);

		//2倍拡大表示モードでは、絵の実サイズ(bi)はそのまま論理サイズを表すため、
		//ウインドウの初期位置・サイズだけをここでまとめてscale()倍する。
		//枠の太さは拡大率を丸めたピクセル数(最大125%などでも1ピクセル以上)
		int pwScale = Math.max(1, (int)Math.round(PCARD.scale()));
		int scaledW = PCARD.toPhysical(bi.getWidth());
		int scaledH = PCARD.toPhysical(bi.getHeight());
		if(windowType.equalsIgnoreCase("palette")) return; //中身はPLTE用のコンストラクタが作る
		//パネルを追加する
		if(bi!=null){
			label = new MyLabel3(name, bi);
			getContentPane().add(label);
			//HyperCardの「the mouseClick」はどのウインドウで起きたクリックでも真になる。
			//picture "startUp",resource,dialogの後、wait until the mouseClickで
			//絵がクリックされるのを待つ場合がある。
			label.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseReleased(MouseEvent event) {
					GUI.mouseClicked = true;
					GUI.mouseDown = false;
				}
			});
		}
		
		if(windowType.equalsIgnoreCase("rect") || windowType.equalsIgnoreCase("shadow")){
			setUndecorated(true);
			setBounds(owner.getX()+owner.getWidth()/2-scaledW/2,owner.getY()+owner.getHeight()/2-scaledH/2,scaledW,scaledH/*+owner.getInsets().top*/);
			setResizable(false);
		}
		else if(windowType.equalsIgnoreCase("dialog") || windowType.equalsIgnoreCase("plain")){
			//dialog = HyperCardのdBoxProc(タイトルバーなし、外側1ドットの黒線と内側2ドットの黒線の二重枠、
			//内容は8ドット内側)、plain = plainDBox(1ドットの黒枠)。macOSのタイトルバーを付けない。
			setUndecorated(true);
			int frame = windowType.equalsIgnoreCase("dialog") ? DBOX_FRAME*pwScale : pwScale;
			JPanel content = new JPanel(new java.awt.BorderLayout()) {
				private static final long serialVersionUID = 1L;
				@Override
				protected void paintComponent(Graphics g) {
					super.paintComponent(g);
					paintDialogFrame(g, getWidth(), getHeight(), frame, pwScale);
				}
			};
			content.setBackground(Color.WHITE);
			content.setBorder(BorderFactory.createEmptyBorder(frame, frame, frame, frame));
			if(label!=null){ getContentPane().remove(label); content.add(label, java.awt.BorderLayout.CENTER); }
			setContentPane(content);
			int w = scaledW+frame*2, h = scaledH+frame*2;
			setBounds(owner.getX()+owner.getWidth()/2-w/2,owner.getY()+owner.getHeight()/2-h/2,w,h);
			setResizable(false);
		}
		else{
			//タイトルバーの高さはオーナーではなく自分のinsetsから求める。hide titleBarで
			//カードウインドウが飾りなしになっているとowner.getInsets().topは0になり、
			//パレットウインドウの高さが絵の分だけ足りなくなっていた。
			getContentPane().setPreferredSize(new java.awt.Dimension(scaledW, scaledH));
			pack();
			setLocation(owner.getX()+owner.getWidth()/2-scaledW/2,owner.getY()+owner.getHeight()/2-scaledH/2);
			//setResizable(false);
		}
		setVisible(visible);
	}

	/** dBoxProcの枠の太さ(内容矩形は構造矩形の8ドット内側) */
	static final int DBOX_FRAME = 8;

	/** System 7のdBoxProc/plainDBoxの枠を描く: frame=8*scale なら 黒1・白2・黒2・白3、それ以外は黒1ドットの枠 */
	static void paintDialogFrame(Graphics g, int width, int height, int frame, int scale) {
		g.setColor(Color.BLACK);
		if(frame<DBOX_FRAME*scale){
			for(int i=0; i<frame; i++) g.drawRect(i, i, width-1-i*2, height-1-i*2);
			return;
		}
		for(int i=0; i<scale; i++) g.drawRect(i, i, width-1-i*2, height-1-i*2);
		for(int i=3*scale; i<5*scale; i++) g.drawRect(i, i, width-1-i*2, height-1-i*2);
	}

	/** Picture XCMD window: closePicture goes out when it closes */
	private boolean pictureMessages;
	private boolean closePictureSent;

	/** Sends a Picture XCMD message (openPicture / closePicture <window name>) to the current card: at once when the script
	 *  thread is the one opening or closing the window (HyperCard J-2.3: the handler has run before the next statement),
	 *  else queued. A message no handler takes ends quietly (TUtil system messages), and so does a script error in its handlers
	 *  (TTalk.quietErrors). */
	static void sendPictureMessage(String message, String windowName) {
		if(PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
		if(Thread.currentThread()==TTalk.talk){
			TTalk.quietErrors++;
			try{ TTalk.runExternalStatement(message+" "+TTalk.quoteLiteral(windowName), PCARD.pc.stack.curCard); }
			catch(xTalkException e){ System.out.println(message+": "+e.getMessage()); }
			finally{ TTalk.quietErrors--; }
		}
		else TTalk.CallMessage(message, new String[]{windowName}, PCARD.pc.stack.curCard);
	}

	@Override
	public void dispose() {
		//the card frame rebuilt for hide/show titlebar takes its owned windows along: shown again, so no closePicture and the
		//listeners stay
		if(TTalk.rebuildingCardWindow){ super.dispose(); return; }
		if(pictureMessages && !closePictureSent){ closePictureSent = true; sendPictureMessage("closePicture", name); }
		if(cardWindow!=null) cardWindow.removeWindowListener(cardActivationListener);
		Toolkit.getDefaultToolkit().removeAWTEventListener(cardClickListener);
		super.dispose();
	}

	/**
	 * The stack closed (back to the launcher, as when HyperCard quits): its Picture XCMD and palette windows go with it,
	 * without closePicture/closePalette (its scripts are gone). They used to stay on screen and in OWindow.list, so
	 * 秘密結社ゲーム opened again made a second "表紙" and its show/hide window "表紙" reached the old one first.
	 * Movie windows are TQTMovie's.
	 */
	static void closeStackWindows() {
		java.util.List<GPictWindow> closing = new java.util.ArrayList<GPictWindow>();
		for(OWindow w : new java.util.ArrayList<OWindow>(OWindow.list)){
			if(w.gpw==null || w.movieHandle!=null) continue;
			OWindow.list.remove(w);
			w.gpw.closePictureSent = true;
			closing.add(w.gpw);
		}
		if(closing.isEmpty()) return;
		Runnable dispose = () -> { for(GPictWindow gpw : closing) gpw.dispose(); };
		if(SwingUtilities.isEventDispatchThread()) dispose.run();
		else SwingUtilities.invokeLater(dispose);
	}

	/** 表示中のモーダルダイアログ(answer/askなど)。なければnull */
	static Dialog visibleModalDialog() {
		for(Window window : Window.getWindows()){
			if(window instanceof Dialog && ((Dialog)window).isModal() && window.isVisible() &&
				!(window instanceof GPictWindow)) return (Dialog)window;
		}
		return null;
	}

	//answerダイアログが出ている間にPictureウインドウを前へ出さない。カード上のボタンを
	//押した直後の「カードがクリックされたらPictureを前へ」や、idleハンドラが繰り返す
	//palette/show windowによるtoFront()がダイアログの表示と重なると、Pictureウインドウが
	//モーダルダイアログの前に出てしまっていた。ダイアログは常にPictureより前に置く。
	@Override
	public void toFront() {
		if(visibleModalDialog()!=null) return;
		super.toFront();
	}

	@Override
	public void setVisible(boolean visible) {
		super.setVisible(visible);
		if(!visible) return;
		Dialog dialog = visibleModalDialog();
		if(dialog!=null) SwingUtilities.invokeLater(dialog::toFront);
	}

	/**
	 * paletteコマンド(HomeスタックのXCMD)のパレット。PLTEリソースのWDEF番号で見た目を変える:
	 * 2052(名前なし)/2054・4・16・1984(名前つき)はツールパレットと共通のタイトルバー(GWindoidBar)、
	 * 2064はタイトルつきでクローズボックスなし、1993は左辺に縦のバー、1・2・3はバーのない箱(3は影つき)。
	 * ボタンは絵の座標系のまま{@link #paletteContent}に置く。
	 */
	GPictWindow(PCARD owner, Rsrc.PlteClass plte, BufferedImage bi) {
		this(owner, plte.name, bi, "palette", false);
		paletteWindow = true;
		paletteImage = bi;
		paletteHiliteType = plte.hiliteType;
		paletteHiliteMode = plte.btnType;
		hilitedButton = plte.hiliteType<0 ? -1 : 0;
		paletteScale = PCARD.scale();
		int scaledW = PCARD.toPhysical(bi.getWidth());
		int scaledH = PCARD.toPhysical(bi.getHeight());

		int wdef = plte.windowDef;
		boolean box = wdef>=1 && wdef<=3;           //WDEF 0系: ダイアログ/箱/影つきの箱
		boolean shadow = wdef==3;
		boolean titled = wdef==2054 || wdef==4 || wdef==16 || wdef==1984 || wdef==2064 || (wdef>=2048 && wdef<=2063 && (wdef&2)!=0);
		boolean closeBox = wdef!=2064;
		boolean vertical = wdef==1993;
		int barH = box ? 0 : GWindoidBar.HEIGHT;
		int barLeft = vertical ? barH : 0, barTop = vertical ? 0 : barH;

		setUndecorated(true);
		JPanel root = new JPanel(null) {
			private static final long serialVersionUID = 1L;
			@Override protected void paintComponent(Graphics g) {
				java.awt.Graphics2D g2 = (java.awt.Graphics2D)g;
				if(shadow){
					//影つきの箱: 右と下に2ドットの影
					g2.setColor(getBackground()); g2.fillRect(0,0,getWidth(),getHeight());
					g2.setColor(Color.BLACK);
					g2.fillRect(2,getHeight()-2,getWidth()-2,2); g2.fillRect(getWidth()-2,2,2,getHeight()-2);
					g2.setColor(Color.WHITE); g2.fillRect(0,0,getWidth()-2,getHeight()-2);
					g2.setColor(Color.BLACK); g2.drawRect(0,0,getWidth()-3,getHeight()-3);
				}
				else GWindoidBar.paintWindowBody(g2, getWidth(), getHeight(), Color.WHITE);
			}
		};
		root.setOpaque(true);
		setContentPane(root);
		if(!box){
			GWindoidBar bar = new GWindoidBar(this, this::closePalette).setCloseBox(closeBox).setVertical(vertical);
			if(titled) bar.setTitle(plte.name);
			if(vertical) bar.setBounds(0,0,barH,scaledH+2); else bar.setBounds(0,0,scaledW+2,barH);
			root.add(bar);
		}
		paletteContent = new JPanel(null);
		paletteContent.setOpaque(false);
		int inset = shadow ? 1 : 1;
		paletteContent.setBounds(barLeft+inset,barTop+inset,scaledW,scaledH);
		root.add(paletteContent);
		label = new MyLabel3(plte.name, bi);
		label.setBounds(0,0,scaledW,scaledH);
		paletteContent.add(label);
		int totalW = barLeft+scaledW+inset*2+(shadow?2:0), totalH = barTop+scaledH+inset*2+(shadow?2:0);
		setBounds(0,0,totalW,totalH);
		if(!shadow) GWindoidBar.applyShape(this);
		setResizable(false);

		for(Rsrc.plteBtnObject obj : plte.objList) addPaletteButton(obj.rect, plte.pictHV, obj.message);
		if(plte.hiliteType>0) setHilitedButton(plte.hiliteType);
	}

	/** PLTEのボタンをひとつ作る。rectはPLTEの絶対座標(pictHVが絵の左上) */
	private void addPaletteButton(java.awt.Rectangle rect, java.awt.Point pictHV, String message){
		java.awt.Rectangle logical = TUtil.paletteButtonBounds(rect, pictHV);
		double s = paletteScale;
		int x = PCARD.toPhysical(logical.x), y = PCARD.toPhysical(logical.y);
		int w = Math.max(1,PCARD.toPhysical(logical.x+logical.width)-x), h = Math.max(1,PCARD.toPhysical(logical.y+logical.height)-y);
		int edgeWidth = Math.max(1, (int)Math.round(s));
		int index = paletteButtons.size()+1;
		AbstractButton btn = paletteHiliteType<0 ? new JButton() : new JToggleButton();
		btn.setBounds(x, y, w, h);
		btn.setName(message);
		btn.setOpaque(false);
		btn.setContentAreaFilled(false);
		btn.setFocusable(false);
		btn.setBorder(new javax.swing.border.EmptyBorder(0,0,0,0));
		btn.setMargin(new java.awt.Insets(0,0,0,0));
		//絵の該当部分を切り出したアイコンと、その反転(または枠反転)を点灯用にする
		BufferedImage normal = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = normal.createGraphics();
		if(s>1) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, DisplayZoom.interpolation(s));
		g.drawImage(paletteImage, -x, -y, PCARD.toPhysical(paletteImage.getWidth()), PCARD.toPhysical(paletteImage.getHeight()), null);
		g.dispose();
		BufferedImage lit = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for(int yy=0; yy<h; yy++)for(int xx=0; xx<w; xx++){
			int p = normal.getRGB(xx, yy);
			boolean edge = paletteHiliteMode==1 && (xx<edgeWidth || yy<edgeWidth || xx>=w-edgeWidth || yy>=h-edgeWidth);
			lit.setRGB(xx, yy, paletteHiliteMode==1 && !edge ? p : (p & 0xff000000) | (~p & 0xffffff));
		}
		btn.setIcon(new ImageIcon(normal));
		btn.setPressedIcon(new ImageIcon(lit));
		btn.setSelectedIcon(new ImageIcon(lit));
		btn.setRolloverEnabled(false);
		btn.addActionListener(e -> {
			if(paletteHiliteType>=0) setHilitedButton(index);
			TUtil.sendPaletteMessage(message);
		});
		paletteButtons.add(btn);
		paletteCommands.add(message);
		paletteContent.add(btn, 0);
	}

	/** the hilitedButton: 点灯中のボタン番号(0=なし、押している間だけ点灯するパレットは-1) */
	int getHilitedButton(){ return hilitedButton; }
	void setHilitedButton(int number){
		if(paletteHiliteType<0) return;
		hilitedButton = number<0 || number>paletteButtons.size() ? 0 : number;
		for(int i=0; i<paletteButtons.size(); i++) paletteButtons.get(i).setSelected(i+1==hilitedButton);
	}
	/** the commands: 各ボタンが送るコマンド(改行区切り) */
	String paletteCommands(){ return String.join("\n", paletteCommands); }
	/** the buttonCount */
	int paletteButtonCount(){ return paletteButtons.size(); }

	private boolean closingPalette, paletteClosed;

	/** クローズボックス/close window: closePaletteを送ってからウインドウを捨てる。その handler の中ではパレットはまだある
	 *  (HC 2.4 / J-2.3 probe Hcde Close Palette: there is a window x, its visible and topLeft - TimeCache 1.6 keeps the
	 *  topLeft of window "sectNav" in its preferences there) */
	void closePalette(){
		if(paletteWindow && TTalk.talk!=null && Thread.currentThread()!=TTalk.talk){
			//the close box: the handler runs on the script thread, the window still there
			TTalk.runOnScriptThread(this::closePaletteNow, true);
			return;
		}
		closePaletteNow();
	}

	private void closePaletteNow(){
		if(closingPalette || paletteClosed) return; //close window x again in its own closePalette, or a second click on the close box
		boolean message = paletteWindow && PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null;
		closingPalette = true;
		try{
			OWindow window = OWindow.find(this);
			if(message){
				String id = Integer.toString(window==null ? 0 : window.id);
				//close window from a script: the handler runs before the next statement, on the card current then (HyperTutor2's
				//closeCard closes its Keyboard palette and closePalette hides a field of that card - queued, it ran on the next card)
				try{ TTalk.runExternalStatement("closePalette "+TTalk.quoteLiteral(name)+","+id, PCARD.pc.stack.curCard); }
				catch(xTalkException e){ System.out.println("closePalette: "+e.getMessage()); }
			}
			paletteClosed = true;
			dispose();
			if(window!=null) OWindow.list.remove(window);
		}finally{
			closingPalette = false;
		}
	}

	/** paletteコマンドのボタンを置く場所(絵と同じ座標系)。通常のPictureウインドウでは内容ペイン */
	java.awt.Container pictureContainer() {
		return paletteContent!=null ? paletteContent : getContentPane();
	}

	void markAsPaletteWindow() {
		paletteWindow = true;
	}

	boolean isPaletteWindow() {
		return paletteWindow;
	}

	static boolean samePaletteName(boolean paletteWindow, String currentName, String requestedName) {
		return paletteWindow && currentName!=null && requestedName!=null &&
			currentName.equalsIgnoreCase(requestedName);
	}

	static void prepareForModalDialog(Window dialog) {
		//answer/askなどのモーダルダイアログは、Pictureウインドウ(カードウインドウの子)より
		//必ず前に出す。同じカードウインドウを親にした兄弟ウインドウにすると、macOSのJDKが
		//「最後にmainになった時刻」順で兄弟を並べ直したり、Cocoaの子ウインドウ同士の
		//順序が親の並べ替えで巻き戻されたりして、toFront()してもダイアログがPictureの
		//背面に回ることがあった(パレットのボタンを押した直後のanswerで再現)。
		//そこでダイアログはオーナー無しで作り(子ウインドウはレベルを親に揃えられるため)、
		//表示中はalwaysOnTop(NSFloatingWindowLevel)にして通常ウインドウ全体より上に置く。
		//他のアプリに切り替えたときまで浮かせないよう、アプリが非アクティブになったら
		//alwaysOnTopを外し、戻ってきたら付け直す。
		dialog.setAlwaysOnTop(true);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent event) {
				dialog.toFront();
				dialog.requestFocus();
			}
			@Override
			public void windowActivated(WindowEvent event) {
				dialog.setAlwaysOnTop(true);
			}
			@Override
			public void windowDeactivated(WindowEvent event) {
				//oppositeWindowがnullなら別アプリへ切り替わった
				if(event.getOppositeWindow()==null) dialog.setAlwaysOnTop(false);
			}
		});
	}

	static void restorePictureWindows() {
		//モーダルダイアログを閉じると、OS側がオーナー(カードウインドウ)を自動的に最前面へ
		//戻す処理が後追いでキューされることがある。ここで即toFront()すると、その自動処理に
		//追い越されてPictureウインドウがカードウインドウの背面に隠れてしまうため、
		//invokeLaterで1テンポ遅らせて確実に最後に実行されるようにする。
		SwingUtilities.invokeLater(() -> {
			for(Window window : Window.getWindows()){
				if(window instanceof GPictWindow && window.isVisible()) window.toFront();
			}
		});
	}

	void enablePictureMessages() {
		pictureMessages = true;
		if(label==null) return;
		label.addMouseListener(new MouseAdapter() {
			private boolean pressedInside;

			@Override
			public void mousePressed(MouseEvent event) {
				pressedInside = true;
				if(PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
				TTalk.CallMessage("mouseDownInPicture",
					new String[] {name, picturePoint(PCARD.toLogical(event.getX()), PCARD.toLogical(event.getY()))},
					PCARD.pc.stack.curCard);
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				if(!pressedInside) return;
				pressedInside = false;
				if(PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
				TTalk.CallMessage("mouseUpInPicture",
					new String[] {name, picturePoint(PCARD.toLogical(event.getX()), PCARD.toLogical(event.getY()))},
					PCARD.pc.stack.curCard);
			}
		});
	}

	static String picturePoint(int x, int y) {
		return x+","+y;
	}

	//ウィンドウのサイズ(rect)より大きな絵の一部だけを見せるためのスクロール位置。
	//(元の絵の何ドット目を、ウィンドウの左上に表示するか)
	void setScroll(int x, int y) {
		if(label==null) return;
		label.setScroll(x, y);
	}

	int getScrollX() {
		return label==null ? 0 : label.scrollX;
	}

	int getScrollY() {
		return label==null ? 0 : label.scrollY;
	}
}

class MyLabel3 extends JLabel {
	/**
	 *
	 */
	private static final long serialVersionUID = 1L;
	BufferedImage bi;
	float scale;
	int scrollX;
	int scrollY;

	MyLabel3(String fname, BufferedImage bi){
		super();
		this.bi = bi;
		this.scale = 1f;
		//this.setIcon(new ImageIcon(bi));
	}

	void setScroll(int x, int y) {
		scrollX = x;
		scrollY = y;
		repaint();
	}

    @Override
    protected void paintComponent(Graphics g) {
		if(bi==null){
			g.setColor(Color.WHITE);
			Rectangle r = g.getClipBounds();
			g.fillRect(r.x, r.y, r.width, r.height);
			return;
		}
		//scrollX/scrollY・scaleはHyperTalkスクリプトから見える論理値のまま変更せず、
		//2倍拡大表示モードでは実際の描画先座標・サイズだけをまとめてscale()倍する。
		//ドット絵をにじませず、ピクセルパーフェクトに拡大表示する。
		//(整数倍でない拡大率(最大125%など)では滑らかに)
		double pwScale = PCARD.scale();
		if(pwScale>1 && g instanceof java.awt.Graphics2D){
			((java.awt.Graphics2D)g).setRenderingHint(
				java.awt.RenderingHints.KEY_INTERPOLATION, PCARD.scaleInterpolation((java.awt.Graphics2D)g));
		}
		g.drawImage(bi,(int)Math.round(-scrollX*pwScale),(int)Math.round(-scrollY*pwScale),
			(int)(bi.getWidth()*scale*pwScale),(int)(bi.getHeight()*scale*pwScale), this);
    }
}

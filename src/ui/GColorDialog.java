import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;


class GColorDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	//タブはOklab(円の色相環)・RGB・実機のシステム256色パレット
	//(OklabColorChooserPanel.installPanels参照)。標準のプレビューの代わりに
	//ボタンの並びの左に新旧の色の見本(compare)を出す。
	static JColorChooser chooser = OklabColorChooserPanel.createChooser(Color.WHITE);
	static Color selectedColor;
	static CaptureThread captureThread;
	//モードレスで開いている(ペイントしながら使える)ダイアログ。同時に1つだけ。
	static GColorDialog modeless;

	//ダイアログを開いたときの色。Cancel(またはウインドウを閉じた)ときは
	//この色に戻す。
	Color originalColor;
	//色が変わるたびに呼ばれるコールバック(呼び出し側のボタンの色見本や
	//オブジェクトの色をOKを待たずにその場で反映するため)。null可。
	Consumer<Color> liveListener;
	//OK(またはモードレスで別のボタンに付け替えるとき)に、開いたときと違う
	//色で決まった場合だけ呼ばれる。null可。
	Consumer<Color> committedListener;
	//モードレス時、このダイアログが色を反映している先の部品(ツールパレットの
	//色ボタンなど)。部品のウインドウが閉じられたらダイアログも閉じる。
	Component target;
	private final ColorCompareSwatch compare = new ColorCompareSwatch(chooser);
	//ツールパレットと同じ細いタイトルバー(OSのタイトルバーは使わない)
	private GWindoidBar bar;
	private final HierarchyListener targetGone = new HierarchyListener(){
		@Override public void hierarchyChanged(HierarchyEvent e){
			if((e.getChangeFlags()&HierarchyEvent.DISPLAYABILITY_CHANGED)!=0 && target!=null && !target.isDisplayable()){
				dispose();
			}
		}
	};
	
	public static Color getColor(Frame frame, Color color, boolean useCapture){
		return getColor(frame, color, useCapture, null);
	}

	//モーダル版。閉じるまで呼び出し側のスレッドを止めて、決まった色を返す
	//(GPaintDialog.ColorConvertDialogのように戻り値が要る所向け)。
	//liveを渡すと、Oklab/RGB/Macシステムカラーのどのタブで色を
	//変えても、その瞬間にliveが呼ばれる(OKは「閉じる」だけになる)。
	//Cancelやウインドウのクローズボックスでは元の色でliveを呼び戻してから
	//元の色を返す。liveはEDT上で呼ばれる。
	public static Color getColor(Frame frame, Color color, boolean useCapture, Consumer<Color> live){
		//chooserは共有なので、モードレスのものが開いていたら先に閉じる
		//(そちらの色は確定扱い)。
		PCARD.runOnEDT(() -> closeModeless());
		captureThread = null;
		GColorDialog dlg = new GColorDialog(frame, true, null, null, color, useCapture, live, null);
		while(captureThread!=null && captureThread.isAlive()){
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
			}
		}
		dlg.liveListener = null;
		return selectedColor;
	}

	//モードレス版。すぐに戻り、ダイアログを出したままペイントツール等を使える。
	//色を変えるたびにliveで反映し、OKで閉じる(committedに最終色)、Cancel/
	//クローズボックスで元の色に戻して閉じる。既に別のtargetで開いていれば、
	//そちらを確定してから同じウインドウをこのtargetに付け替える(ウインドウの
	//位置はそのまま)。同じtargetなら前面に出すだけ。EDTから呼ぶこと。
	public static void open(Window owner, String title, Component target, Color color, boolean useCapture,
			Consumer<Color> live, Consumer<Color> committed){
		if(modeless!=null && modeless.isDisplayable()){
			if(modeless.target!=target){
				modeless.commit();
				modeless.bind(title, target, color, live, committed);
			}
			modeless.toFront();
			return;
		}
		modeless = new GColorDialog(owner, false, title, target, color, useCapture, live, committed);
	}

	//モードレスのダイアログが開いていれば、色を確定して閉じる。
	static void closeModeless(){
		if(modeless!=null && modeless.isDisplayable()){
			modeless.dispose();
		}
		modeless = null;
	}

	//targetの色が(スポイトツールやクイックパレットなど)ダイアログの外で
	//変わったときに、開いているモードレスダイアログの表示を合わせる。
	//liveは呼ばない(呼び出し側は既にその色になっている)。
	static void syncTarget(Component target, Color color){
		if(modeless==null || !modeless.isDisplayable() || modeless.target!=target || color==null) return;
		if(color.equals(chooser.getColor())) return;
		modeless.setChooserColorQuietly(color);
	}

	void fireLive(Color color){
		if(liveListener!=null && color!=null){
			liveListener.accept(color);
		}
	}

	private void bind(String title, Component target, Color color, Consumer<Color> live, Consumer<Color> committed){
		if(this.target!=null) this.target.removeHierarchyListener(targetGone);
		this.target = target;
		if(target!=null) target.addHierarchyListener(targetGone);
		setTitle(title==null ? "" : title);
		if(bar!=null) bar.setTitle(getTitle());
		liveListener = live;
		committedListener = committed;
		if(color!=null){
			setChooserColorQuietly(color);
		}
		originalColor = chooser.getColor();
		selectedColor = originalColor;
		compare.setOriginal(originalColor);
		cancelled = false;
	}

	//liveを呼ばずにchooserの色を変える(初期色の設定と外部からの同期用)。
	private void setChooserColorQuietly(Color color){
		chooser.getSelectionModel().removeChangeListener(liveChange);
		chooser.setColor(color);
		chooser.getSelectionModel().addChangeListener(liveChange);
	}

	//開いたときと違う色で決まったらcommittedに知らせる。
	private void commit(){
		selectedColor = chooser.getColor();
		if(committedListener!=null && selectedColor!=null && !selectedColor.equals(originalColor)){
			committedListener.accept(selectedColor);
			//同じ色を二度知らせない(付け替え直後にdisposeされた場合など)
			originalColor = selectedColor;
		}
	}

	//trueならこのダイアログはキャンセル扱いで閉じられた(戻す処理済み)。
	boolean cancelled = false;
	private void cancel(){
		if(cancelled) return;
		cancelled = true;
		selectedColor = originalColor;
		//chooserは静的に共有しているので、次回開いたときのために選択色も戻す
		//(この変更でliveが呼ばれ、呼び出し側も元の色に戻る)。
		chooser.setColor(originalColor);
	}

	private final ChangeListener liveChange = new ChangeListener(){
		@Override public void stateChanged(ChangeEvent e){
			fireLive(chooser.getColor());
		}
	};
	
	private GColorDialog(Window owner, boolean modal, String title, Component target, Color color, boolean useCapture,
			Consumer<Color> live, Consumer<Color> committed){
		super(owner, title==null ? "" : title, modal ? ModalityType.APPLICATION_MODAL : ModalityType.MODELESS);
		//画面を広く使えるよう、OSのタイトルバーの代わりにツールパレットと同じ
		//細いバー(GWindoidBar)を付け、角丸に切り抜く
		setUndecorated(true);
		setResizable(false);
		JPanel root = new JPanel(new BorderLayout()){
			private static final long serialVersionUID = 1L;
			@Override protected void paintComponent(java.awt.Graphics g){
				GWindoidBar.paintWindowBody((java.awt.Graphics2D)g, getWidth(), getHeight(), getBackground());
			}
		};
		setContentPane(root);
		bar = new GWindoidBar(this, () -> { cancel(); dispose(); });
		bar.setPreferredSize(new Dimension(100, GWindoidBar.HEIGHT));
		bar.setTitle(getTitle());
		root.add(bar, BorderLayout.NORTH);
		addComponentListener(new java.awt.event.ComponentAdapter(){
			@Override public void componentResized(java.awt.event.ComponentEvent e){ GWindoidBar.applyShape(GColorDialog.this); }
		});
		//escでCancel(閉じるボタンと同じ)
		getRootPane().registerKeyboardAction(e -> { cancel(); dispose(); },
				javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
				javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
		
		chooser.getSelectionModel().addChangeListener(liveChange);
		//初期色の設定はbindがliveを呼ばずに行う
		bind(title, target, color, live, committed);

		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosing(WindowEvent e){
				cancel();
				dispose();
			}
		});
		
		chooser.setOpaque(false);
		JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 0, 8));
		center.add(chooser);
		root.add(center, BorderLayout.CENTER);

		JPanel south = new JPanel(new BorderLayout(8, 0));
		south.setOpaque(false);
		south.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 7, 8));
		JPanel comparePanel = new JPanel(new java.awt.GridBagLayout());
		comparePanel.setOpaque(false);
		compare.setPreferredSize(new Dimension(56, 20));
		comparePanel.add(compare);
		south.add(comparePanel, BorderLayout.WEST);
		JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
		panel.setOpaque(false);
		south.add(panel, BorderLayout.EAST);
		root.add(south, BorderLayout.SOUTH);
		
		if(useCapture){
			JButton btn2 = ColorPaint.small(new JButton(International.t("Choose from Screen")));
			btn2.setName("capture");
			btn2.addActionListener(this);
			panel.add(btn2);
		}

		JButton btn3 = ColorPaint.small(new JButton(International.t("Cancel")));
		btn3.setName("Cancel");
		btn3.addActionListener(this);
		panel.add(btn3);

		JButton btn1 = ColorPaint.small(new JButton("OK"));
		btn1.setName("OK");
		btn1.addActionListener(this);
		panel.add(btn1);
		getRootPane().setDefaultButton(btn1);
		
		/*if(point!=null){
			setBounds(point.x, point.y, chooser.getPreferredSize().width, chooser.getPreferredSize().height+40);
		}else if(frame!=null){
			setBounds(frame.getX()+frame.getWidth()/2-300/2, frame.getY()+frame.getHeight()/2-200/2, chooser.getPreferredSize().width, chooser.getPreferredSize().height+40);
		}else*/{
	        PointerInfo info = MouseInfo.getPointerInfo();
			pack();
			//ポインタの少し左上に出す(画面からはみ出さないように)
			Rectangle screen = info.getDevice().getDefaultConfiguration().getBounds();
			int x = Math.max(screen.x, Math.min(info.getLocation().x-getWidth()/2, screen.x+screen.width-getWidth()));
			int y = Math.max(screen.y+24, Math.min(info.getLocation().y-60, screen.y+screen.height-getHeight()));
			setLocation(x, y);
			GWindoidBar.applyShape(this);
		}

		setVisible(true);
	}
	
	//閉じるときは、Cancel済みでなければ現在の色を確定する(OK、targetの
	//ウインドウが閉じた、親ウインドウごと破棄された、のどれでも同じ)。
	@Override
	public void dispose(){
		if(!cancelled && isDisplayable()) commit();
		chooser.getSelectionModel().removeChangeListener(liveChange);
		if(target!=null) target.removeHierarchyListener(targetGone);
		if(modeless==this) modeless = null;
		super.dispose();
	}

	//「画面の色を取得」で色を拾ったとき(CaptureThreadからEDTで呼ばれる)。
	//モードレスならダイアログは開いたままなので、chooserの変更でliveが呼ばれる。
	//モーダルでは既にダイアログを閉じて(liveChangeも外れて)いるので直接呼ぶ。
	void captured(Color color){
		chooser.setColor(color);
		if(!isDisplayable()) fireLive(color);
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		JButton btn = (JButton)e.getSource();
		if(btn.getName().equals("OK")){
			this.dispose();
		}
		else if(btn.getName().equals("Cancel")){
			cancel();
			this.dispose();
		}
		else if(btn.getName().equals("capture")){
			captureThread = new CaptureThread(this);
			captureThread.start();
			//モーダルだと他のウインドウ(色を拾うためのルーペ)をクリック
			//できないので閉じる。モードレスなら開いたままにする。
			if(getModalityType()!=ModalityType.MODELESS){
				this.dispose();
			}
		}
	}
}


class CaptureThread extends Thread {
	GColorDialog owner;
	
	CaptureThread(GColorDialog owner){
		super();
		this.owner = owner;
	}
	
	
	public void run(){
		Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException ex) {
            ex.printStackTrace();
            return;
        }
        
        
        JWindow window = new JWindow();
        JLabel lbl = new JLabel();
        lbl.addMouseListener(new mouseListener());
		window.getContentPane().add(lbl);

        Color color = Color.WHITE;
        clicked = false;
        PointerInfo lastinfo = null;
		while(!clicked /*&& owner.isVisible()*/){
	        PointerInfo info = MouseInfo.getPointerInfo();
	        if(lastinfo!=null &&
	        	info.getLocation().x == lastinfo.getLocation().x &&
	        	info.getLocation().y == lastinfo.getLocation().y)
	        {
	        	//マウスが動くまで待つ(以前は空回りでCPUを使い切っていた)
	        	try { Thread.sleep(10); } catch (InterruptedException e) {}
	        	continue;
	        }
	        lastinfo = info;
	        
	        window.setVisible(false);
	        
			Rectangle bounds = new Rectangle(info.getLocation().x-10, info.getLocation().y-10, 20, 20);
			BufferedImage image = robot.createScreenCapture(bounds);
			BufferedImage bigimage = new BufferedImage(80,80,BufferedImage.TYPE_INT_ARGB);
			bigimage.createGraphics().drawImage(image, 0, 0, 80, 80, 0, 0, 20, 20, null);
			lbl.setIcon(new ImageIcon(bigimage));
			window.setBounds(info.getLocation().x-40, info.getLocation().y-40, 80, 80);
			window.setVisible(true);
			
			int d = image.getRaster().getDataBuffer().getElem(10+10*20);
			color = new Color((d>>16)&0xFF, (d>>8)&0xFF, (d>>0)&0xFF);
		}
		
		window.dispose();
		
		if(clicked){
			final Color picked = color;
			GColorDialog.selectedColor = picked;
			//chooserはSwing部品なのでEDTで更新する。
			PCARD.runOnEDT(() -> owner.captured(picked));
		}
	}
	
	static boolean clicked = false;
	private class mouseListener implements MouseListener {
		@Override
		public void mouseClicked(MouseEvent arg0) {
		}
		@Override
		public void mouseEntered(MouseEvent arg0) {
		}
		@Override
		public void mouseExited(MouseEvent arg0) {
		}
		@Override
		public void mousePressed(MouseEvent arg0) {
			clicked = true;
		}
		@Override
		public void mouseReleased(MouseEvent arg0) {
		}
	}
}


//実機のColor QuickDrawが使う標準8bit(256色)システムパレットを、
//カラーパレットのOklab/RGBと並ぶ1面(CompactColorChooserPanel)にする。
//他の面(Oklabの円)と同じくらいの大きさになるよう、16列×16行の正方形の
//マス目に並べ、スクロール無しで全部を一度に表示する。
//MacSystemPalette.colors256()が返す並び順のまま並べ、クリックした色を
//そのままJColorChooserの選択色にする。
class MacSystemColorChooserPanel extends AbstractColorChooserPanel {
	private static final long serialVersionUID = 1L;
	private static final int COLUMNS=32;
	private static final int ROWS=256/COLUMNS;
	private static final int CELL=14; //初期(まだ他のタブの大きさが分からない)状態でのマス目の目安の大きさ
	//カラーパレットの面では、他の面(Oklabの円)と同じくらいの大きさの正方形に並べる
	private static final int PAGE_COLUMNS=16, PAGE_CELL=11;

	@Override
	public String getDisplayName(){
		return International.t("Mac System Colors");
	}

	@Override
	public Icon getSmallDisplayIcon(){
		return null;
	}

	@Override
	public Icon getLargeDisplayIcon(){
		return null;
	}

	@Override
	protected void buildChooser(){
		//マス目が面いっぱいに伸びて横長にならないよう、真ん中に置く
		setLayout(new java.awt.GridBagLayout());
		add(createSwatchGrid(PAGE_CELL, PAGE_COLUMNS, color -> getColorSelectionModel().setSelectedColor(color)));
	}

	//256色を32列×8行(columnsを渡せばその列数)に並べたマス目を作る。マスを押す(mousePressed)と
	//onPickにその色が渡される。このタブと、ツールパレットの色ボタンを
	//押したときに出るクイックパレット(GPaintPalette.CPButton)で共用する。
	static JPanel createSwatchGrid(int cell, Consumer<Color> onPick){
		return createSwatchGrid(cell, COLUMNS, onPick);
	}

	static JPanel createSwatchGrid(int cell, int columns, Consumer<Color> onPick){
		JPanel grid = new JPanel(new GridLayout(256/columns,columns,1,1));
		grid.setOpaque(false);
		int[] colors = MacSystemPalette.colors256();
		//MacSystemPalette自体の並び(実機のROMの並びそのもの。QuickDraw68k
		//のColorTableエミュレーションではこのインデックス順が実機との
		//互換性のために重要)は変えず、見た目の表示順だけを逆にする
		//(白から黒の順で見えるように)。
		for(int i=colors.length-1; i>=0; i--){
			grid.add(createSwatch(new Color(colors[i]), cell, onPick));
		}
		return grid;
	}

	//色見本1マス。JButtonのアイコンだと、GridLayoutでマス目が伸縮したときに
	//アイコン自体の大きさは変わらずマスの中に隙間ができてしまう(色見本として
	//見づらい)ため、JPanelの背景色をそのまま塗る形にする(setBackgroundは
	//LAFによってJButtonでは無視されることがあるが、JPanelなら確実に塗られる)。
	//mouseClickedではなくmousePressedで拾う(押してから離すまでに1ピクセルでも
	//動くとclickedが来ないので、小さなマスでは取りこぼしやすい)。
	static JPanel createSwatch(final Color color, int cell, Consumer<Color> onPick){
		JPanel swatch = new JPanel();
		swatch.setOpaque(true);
		swatch.setBackground(color);
		swatch.setPreferredSize(new Dimension(cell,cell));
		swatch.setToolTipText(String.format("#%02X%02X%02X",color.getRed(),color.getGreen(),color.getBlue()));
		swatch.addMouseListener(new MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){
				onPick.accept(color);
			}
		});
		return swatch;
	}

	@Override
	public void updateChooser(){
		//スウォッチを一覧して選ぶだけの用途なので、選択色に合わせた
		//ハイライト表示等は行わない。
	}
}

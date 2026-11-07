import java.awt.Toolkit;
import java.awt.RenderingHints;
import java.awt.Dimension;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
//import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

//import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
//import javax.swing.JToolBar;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
//import javax.swing.plaf.basic.BasicToolBarUI;


public class GToolBar {
	// Icons paint in logical coordinates, retaining sharp outlines on HiDPI displays.
	static final int BUTTON_SIZE = 26;
	static final int GAP = 6;
	static final int MARGIN = 8;
	//ドラッグ用のタイトルバー帯の高さ。フロート型のツールパレット(この
	//クラス自身が作る方)にはあるが、アイコンエディタ内に埋め込まれた
	//ツールパレット(IconEditor.java)はウインドウ自体にタイトルバーがある
	//ので不要。cell()にincludeHeaderを渡して切り替える。
	static final int TOP_BAR_HEIGHT = GWindoidBar.HEIGHT;
	static final int COLUMNS = 4;
	static final int ROWS = 7;
	static final int WINDOW_WIDTH = gridWidth(COLUMNS);
	static final int WINDOW_HEIGHT = gridHeight(ROWS, true);

	static int gridWidth(int columns){
		return MARGIN*2 + columns*BUTTON_SIZE + (columns-1)*GAP;
	}
	static int gridHeight(int rows, boolean includeHeader){
		return (includeHeader ? TOP_BAR_HEIGHT : 0) + MARGIN*2 + rows*BUTTON_SIZE + (rows-1)*GAP;
	}

	//ツールパレット上のボタン用グリッドセルの座標を返す(row,colは0始まり)。
	//戻り値はBUTTON_SIZE四方の正方形で、そのままsetBounds()に使える。
	static java.awt.Rectangle cell(int row, int col, boolean includeHeader){
		int x = MARGIN + col*(BUTTON_SIZE+GAP);
		int y = (includeHeader ? TOP_BAR_HEIGHT : 0) + MARGIN + row*(BUTTON_SIZE+GAP);
		return new java.awt.Rectangle(x, y, BUTTON_SIZE, BUTTON_SIZE);
	}
	//フロート型パレット(タイトルバー帯あり)用の簡略版
	static java.awt.Rectangle cell(int row, int col){
		return cell(row, col, true);
	}

	toolBarListener toolBarListener = new toolBarListener();
	//JToolBar tb;
	JWindow tb;
	//CPButton fore;
	//CPButton back;
	//GradButton grad;
	//PatButton pat;
	TBButton selectToolButton;
	private boolean gvisible;
	
	GToolBar(PCARDFrame owner)  {
		//JPanel panel = new JPanel();
        tb = new JWindow();
        
        //tb.setBounds(PCARD.pc.getX()-72,PCARD.pc.getY(),72,200);
        //淡いグレーの本体を角丸の枠つきで描く(メッセージボックスと同じ見た目)
        JPanel body = new JPanel(null) {
        	private static final long serialVersionUID = 1L;
        	@Override protected void paintComponent(Graphics g) {
        		GWindoidBar.paintWindowBody((Graphics2D)g, getWidth(), getHeight(), new Color(0xE8,0xE8,0xE8));
        	}
        };
        body.setOpaque(true);
        tb.setContentPane(body);
        tb.setLayout(null);
        tb.setAlwaysOnTop(true);
        tb.addMouseMotionListener(toolBarListener);
        tb.addComponentListener(new java.awt.event.ComponentAdapter() {
        	@Override public void componentResized(java.awt.event.ComponentEvent e) { GWindoidBar.applyShape(tb); }
        });

        //panel.setLayout(null);
        //tb.setPreferredSize(new Dimension(80,120));
        //tb.setSize(new Dimension(80,80));
        //tb.add(panel);
        //panel.setMinimumSize(new Dimension(80,120));
        //panel.setMaximumSize(new Dimension(80,120));

        TBButton btn;
        ButtonGroup grp = new ButtonGroup();
        TBButtonListener listener = new TBButtonListener();

        //タイトルバー: メッセージボックスと共通のGWindoidBar(ドラッグで移動、赤丸で閉じる)
        tb.add(new GWindoidBar(tb, () -> tb.setVisible(false)) {
        	private static final long serialVersionUID = 1L;
        	{ setBounds(0,0,WINDOW_WIDTH,TOP_BAR_HEIGHT); }
        });
        
        // The first row selects the card interaction mode; following rows are paint tools.
        String[] tools={"Browse","Button","Field","Shape","Select","Lasso","MagicWand","Pencil",
            "Brush","Eraser","Line","SprayCan","Rect","RoundRect","PaintBucket","Oval",
            "Curve","Type","RegPoly","Poly","Smudge","Stamp","Eyedropper"};
        for(int i=0;i<tools.length;i++){
            tb.add(btn=new TBButton(PCARD.pc.intl.getToolText(tools[i]),i/4,i%4));
            grp.add(btn);btn.addActionListener(listener);btn.addMouseListener(listener);
            if(tools[i].equals("Browse"))btn.setSelected(true);
            if(tools[i].equals("Select"))selectToolButton=btn;
        }
        tb.add(owner.trans=new TransButton(PCARD.pc.intl.getToolText("Transparency"),5,3));
        owner.trans.addActionListener(listener);owner.trans.addMouseListener(listener);
        tb.add(owner.fore=new CPButton(Color.BLACK,6,0,false));
        tb.add(owner.back=new CPButton(Color.WHITE,6,1,true));
        tb.add(owner.pat=new PatButton(11,6,2));owner.pat.frame=owner;
        tb.add(owner.grad=new GradButton(Color.BLACK,Color.WHITE,6,3));

        //panel.setPreferredSize(new Dimension(80,80));
        //panel.setSize(new Dimension(80,80));
        
        tb.addComponentListener(toolBarListener);

        //tb.pack();
        tb.setVisible(false);
        //owner.add(tb, BorderLayout.WEST);
    }

	int getTWidth(){
		return 0;
		/*if(tb==null) return 0;
		if(!tb.isVisible()) return 0;
		if (((BasicToolBarUI)tb.getUI()).isFloating()) return 0;
		if (tb.getOrientation()==JToolBar.HORIZONTAL) return 0;
		Rectangle r = tb.getBounds();
		return r.width;*/
	}
	int getTHeight(){
		return 0;
		/*if(tb==null) return 0;
		if(!tb.isVisible()) return 0;
		if (((BasicToolBarUI)tb.getUI()).isFloating()) return 0;
		if (tb.getOrientation()==JToolBar.VERTICAL) return 0;
		Rectangle r = tb.getBounds();
		return r.height;*/
	}
	
	void activate(){
		tb.setVisible(gvisible);
	}
	void deactivate(){
		gvisible = tb.isVisible();
		tb.setVisible(false);
	}
	/** No stack open any more: hidden, and not brought back when the card window is next activated. */
	void hideForNoStack(){
		gvisible = false;
		if(tb!=null) tb.setVisible(false);
	}
}


class toolBarListener implements ComponentListener, MouseMotionListener {
	public void componentMoved(ComponentEvent e) {
		//PCARD.stack.setNewBounds();
	}
	public void componentResized(ComponentEvent e) {
		//PCARD.pc.setNewBounds();
	}
	public void componentHidden(ComponentEvent e) {
		//PCARD.pc.setNewBounds();
		GMenu.changeMenuName("Tool","Hide ToolBar","Show ToolBar");
	}
	public void componentShown(ComponentEvent e) {
		//PCARD.pc.setNewBounds();
		GMenu.changeMenuName("Tool","Show ToolBar","Hide ToolBar");
	}
	
	boolean isDragStart;
	Point p = new Point(0,0);
	@Override
	public void mouseDragged(MouseEvent e) {
		if(!isDragStart){
			isDragStart = true;
			p.x = e.getX();
			p.y = e.getY();
		}
		else{
			JWindow tb = PCARD.pc.toolbar.tb;
			tb.setLocation(e.getXOnScreen()-p.x, e.getYOnScreen()-p.y);
		}
	}
	@Override
	public void mouseMoved(MouseEvent e) {
		isDragStart = false;
	}
}


class TBButton extends JToggleButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    //Color color;
	
	TBButton(String in_text, int y, int x){
		super();
		String text = PCARD.pc.intl.getToolEngText(in_text);
		setName(text);
		setToolTipText("Shape".equals(text) ? International.tc("shape", "{0} (double-click for shape types)", in_text) : in_text);
		getAccessibleContext().setAccessibleName(in_text);
		this.setBounds(GToolBar.cell(y,x));
        setMargin(new Insets(0,0,0,0));
        this.setFocusable(false);
        
        //ブラシの大きさ・線の太さ・塗りつぶし・自動選択の許容範囲・スプレー・
        //正多角形のオプションは、ダブルクリック/右クリックでGToolOptionsが
        //見た目付きのポップアップとして出す(TBButtonListener.mouseClicked参照)。

        setIcon(ClassicToolArt.icon(text, false));
        setSelectedIcon(ClassicToolArt.icon(text, true));
        setRolloverEnabled(true);
        setContentAreaFilled(false);
        setBorderPainted(false);
    }

	
}


class TBButtonListener implements ActionListener, MouseListener {
	static String lastcmd = "Browse";

	public void mouseClicked(MouseEvent arg0) {
		java.awt.Window window = SwingUtilities.getWindowAncestor(arg0.getComponent());
		if(window instanceof PCARDFrame) PaintTool.owner = (PCARDFrame)window;
		else if(window!=null && window.getOwner() instanceof PCARDFrame)
			PaintTool.owner = (PCARDFrame)window.getOwner();
		String in_cmd = ((JComponent)arg0.getSource()).getName();
		String cmd = PCARD.pc.intl.getToolText(in_cmd);
		if(javax.swing.SwingUtilities.isRightMouseButton(arg0) ||
				arg0.getClickCount()==2){
			//ブラシ/線/四角/楕円/自動選択/スプレー/正多角形/透明度のオプション
			//(見た目付きのマス目かスライダー)をボタンの下に出す
			String eng = PCARD.pc.intl.getToolEngText(in_cmd);
			if("Shape".equals(eng)){ShapeEditor.showKinds(arg0.getComponent());return;}
			//消しゴムのダブルクリックはHyperCardどおり全消去なので、大きさの
			//オプションは右クリックだけで出す
			boolean eraser = "Eraser".equals(eng);
			if(GToolOptions.hasOptions(eng) && (!eraser || javax.swing.SwingUtilities.isRightMouseButton(arg0))){
				GToolOptions.show(arg0.getComponent(), eng);
			}
			if(PCARD.pc.intl.getToolText("Type").equals(cmd)){
				new GFontDialog(PaintTool.owner, PCARD.pc.textFont, PCARD.pc.textSize, PCARD.pc.textStyle, -1);
				PCARD.pc.textFont = GFontDialog.selectedFont;
				// Store image pixels; TypeTool applies the canvas zoom when displaying text.
				PCARD.pc.textSize = GFontDialog.selectedSize;
				PCARD.pc.textStyle = GFontDialog.selectedStyle;
				//PCARD.pc.textAlign = GFontDialog.selectedAlign;
				//入力中の文字があればその場で反映する
				TypeTool.refreshLive();
			}
		}
		
		if(arg0.getClickCount()==2){
			//ダブルクリック
			if(PCARD.pc.intl.getToolText("Eraser").equals(cmd)){
				GMenuPaint.doMenu("Select All");
				GMenuPaint.doMenu("Clear Selection");
			}
			if(PCARD.pc.intl.getToolText("Select").equals(cmd)){
				GMenuPaint.doMenu("Select All");
			}
			if(PCARD.pc.intl.getToolText("Lasso").equals(cmd)){
				GMenuPaint.doMenu("choose lasso tool");
				PaintTool.mouseDown(0,0);
				PaintTool.mouseStillDown(0,1000);
				PaintTool.mouseStillDown(0,10000);
				PaintTool.mouseStillDown(1000,10000);
				PaintTool.mouseStillDown(10000,10000);
				PaintTool.mouseStillDown(10000,1000);
				PaintTool.mouseStillDown(10000,0);
				PaintTool.mouseStillDown(1000,0);
				PaintTool.mouseStillDown(0,0);
				PaintTool.mouseUp(0,0);
			}
			if(PCARD.pc.intl.getToolText("Pencil").equals(cmd)){
				GMenuPaint.doMenu("FatBits");
			}
			/*if(PCARD.pc.intl.getToolText("Button").equals(cmd)){
				try {
					GMenuBrowse.doMenu("New Button");
				} catch (xTalkException e) {
					e.printStackTrace();
				}
			}
			if(PCARD.pc.intl.getToolText("Field").equals(cmd)){
				try {
					GMenuBrowse.doMenu("New Field");
				} catch (xTalkException e) {
					e.printStackTrace();
				}
			}*/
		}
	}

	public void mouseEntered(MouseEvent arg0) {
	}
	public void mouseExited(MouseEvent arg0) {
	}
	public void mousePressed(MouseEvent arg0) {
	}
	public void mouseReleased(MouseEvent arg0) {
	}

	public void actionPerformed (ActionEvent ae) {
		String in_cmd = ((JComponent)ae.getSource()).getName();
		String cmd = PCARD.pc.intl.getToolText(in_cmd);
		ChangeTool(cmd, ae);
		//if(((TBButton)ae.getSource()).getParent()==PCARD.pc.stack.toolbar.tb){
			//PCARD.pc.toFront();
		//}
		if(PCARD.pc.intl.getToolText("Close").equals(cmd)){
			PCARD.pc.toolbar.tb.setVisible(false);
		}
	}
	
	private static boolean scriptChange;

	static boolean isBrowseTool(String cmd){
		return PCARD.pc.intl.getToolText("Browse").equalsIgnoreCase(cmd) || PCARD.pc.intl.getToolEngText("Browse").equalsIgnoreCase(cmd);
	}

	//ブラウズ・ボタン・フィールドツール(ペイントモードを抜けるツール)か
	static private boolean isNonPaintTool(String cmd){
		return PCARD.pc.intl.getToolText("Browse").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Browse").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolText("Button").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Button").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolText("Field").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Field").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolText("Shape").equalsIgnoreCase(cmd) || "Shape".equalsIgnoreCase(cmd);
	}

	static public boolean ChangeTool(String cmd, ActionEvent ae) {
		//スクリプトの"choose ... tool"やカード移動はスクリプトスレッドから
		//ここへ来る。リスナー・DropTarget・部品・メニューを直接組み替えるので、
		//ツールバー操作(EDT)と重なると途中状態でremove/addが割り込み、
		//リスナーが外れたままになる。必ずEDT上で直列に実行する。
		if(!SwingUtilities.isEventDispatchThread()){
			final boolean[] result = new boolean[1];
			try {
				SwingUtilities.invokeAndWait(() -> {
					//lock screen, then choose brush tool: the screen that stays is the card with its parts, taken before the paint view
					//comes (HyperPiece++'s clock: a repaint under the lock showed the card without its fields)
					VEffect.captureBeforeChange();
					scriptChange = true; //スクリプトの choose tool / カード移動: ユーザーの編集ではない
					try{ result[0] = ChangeTool(cmd, ae); }finally{ scriptChange = false; }
				});
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if(cause instanceof RuntimeException) throw (RuntimeException)cause;
				if(cause instanceof Error) throw (Error)cause;
				throw new RuntimeException(cause);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			return result[0];
		}
		
		if(!scriptChange && !isBrowseTool(cmd)){
			OStack.userEdit(); //ユーザーが編集用のツールを選んだ
			TTalk.closeOpenFieldByUser(); //the open field closes (a script's choose closed it already)
		}
		String before = toolInUse();
		try{ return changeTool(cmd, ae); }
		finally{
			//HyperCard redraws the card window when the tool changes: what a 68K external drew over the card goes
			//(UxMaruPeke's ○/× are erased by "choosing another tool", its documentation says)
			if(!before.equals(toolInUse())) CardWindow68k.cardCopied();
		}
	}

	/**
	 * No stack is open any more (closed, back to the launcher): the tool goes back to Browse and the closed stack's editing
	 * windows (Button/Field Info, the colour palette) close. Before, the next stack opened came up with the button tool still
	 * chosen and those windows back on the screen: windows owned by the card window come back when it is shown again. The
	 * close paths have saved the paint pictures already. On the EDT, while the card window is still shown.
	 */
	static void resetForNoStack() {
		if(PCARD.pc.tool!=null){
			PCARD.pc.tool.end();
			PCARD.pc.tool = null;
		}
		//the close paths drop the paint tool without taking its listeners off the card pane: the next stack got paint clicks
		PaintTool.leaveFatBits(PCARD.pc);
		if(PCARD.pc.mainPane!=null){
			PCARD.pc.mainPane.removeMouseListener(paintGUI.gui);
			PCARD.pc.mainPane.removeMouseMotionListener(paintGUI.gui);
		}
		PCARD.pc.releasePaintBuffers();
		GColorDialog.closeModeless();
		scriptChange = true; //not the user's editing
		try{ ChangeTool(PCARD.pc.intl.getToolText("Browse"), null); }finally{ scriptChange = false; }
		if(AuthDialog.authDialog!=null) AuthDialog.authDialog.dispose();
		AuthDialog.authDialog = null;
	}

	/** The tool in use, as a key that changes with it. */
	private static String toolInUse() {
		return PCARD.pc.tool!=null ? PCARD.pc.tool.getClass().getName() : AuthTool.tool!=null ? AuthTool.tool.getClass().getName() : "browse";
	}

	private static boolean changeTool(String cmd, ActionEvent ae) {
		PaintTool.owner = PCARD.pc;
		PaintTool.toolBeforeSelect = null;
		//前のツールの終了処理
		if(PCARD.pc.tool!=null){
			PCARD.pc.tool.end();
			
			if(isNonPaintTool(cmd)){
				//ペイントモードを抜けるときだけファイルへ保存する(変わった絵だけ、別スレッドで)
				PaintTool.saveCdPicturesLater();
			}
			else{
				//ペイントツール同士の切り替えではメモリ上のピクチャへ反映するだけ
				//(以前はツールを切り替えるたびにEDT上でPNGを書き出していた)
				PaintTool.toCdPict();
			}
		}
		if(AuthTool.tool != null){
			ButtonGUI.gui.removeListenerFromParts();
			FieldGUI.gui.removeListenerFromParts();
			
			AuthTool.tool.end();
			AuthTool.tool = null;
		}
		
		GMenu.updateAuthoringMenus();
		//ツールバーのハイライトを変更
		if(PCARD.pc.stack!=null){
			for(int i=0; i<PCARD.pc.toolbar.tb.getContentPane().getComponentCount(); i++){
				JComponent component = (JComponent)PCARD.pc.toolbar.tb.getContentPane().getComponent(i);
				if(component.getClass()==TBButton.class){
					if(component.getName().equalsIgnoreCase(PCARD.pc.intl.getToolEngText(cmd))){
						((TBButton)component).setSelected(true);
					}
					else{
						((TBButton)component).setSelected(false);
					}
				}
			}
		}
		
		if(isNonPaintTool(cmd))
		{
			if(PCARD.pc.tool!=null){
				//ブラウズツールにしたのでペイントモードから戻す
				PCARD.pc.tool = null;
				PaintTool.leaveFatBits(PCARD.pc);
				//ペイント用リスナーはmainPaneに付けている(getRootPaneではない)ので、
				//mainPaneから外さないと外れず、ブラウズモードに戻ってもペイント
				//ツールにマウスイベントが届き続けてしまう
				PCARD.pc.mainPane.removeMouseListener(paintGUI.gui);
				PCARD.pc.mainPane.removeMouseMotionListener(paintGUI.gui);
				paintGUI.gui.removeListenerFromParts();
				
				//PCARD.pc.stack.curCard.pict.getGraphics().drawImage(PCARD.pc.mainImg, 0, 0, PCARD.pc);
				//PCARD.pc.stack.curCard.bg.pict.getGraphics().drawImage(PCARD.pc.bgImg, 0, 0, PCARD.pc);
				PCARD.pc.mainPane.repaint();
	
				//PCARD.pc.paidle.interrupt();
				//PCARD.pc.pidle = new Pidle();
				//PCARD.pc.pidle.start();
				
		    	//ペイント用バッファ
		    	PCARD.pc.releasePaintBuffers();
				
				//これでは切り替わってくれない
				//PCARD.pc.setJMenuBar(PCARD.pc.menu.mb);
		    	
		    	GMenu.menuUpdate(PCARD.pc.menu.mb);
			}
		}
		
		{
			GMenu.changeSelected("Tool",PCARD.pc.intl.getToolText(lastcmd),true);
			GMenu.changeSelected("Tool",PCARD.pc.intl.getToolText(cmd),true);
			lastcmd = cmd;
		}
		
		if(PCARD.pc.intl.getToolText("Button").equalsIgnoreCase(cmd) ||
			PCARD.pc.intl.getToolEngText("Button").equalsIgnoreCase(cmd))
		{
			AuthTool.tool = new ButtonTool();
			TBCursor.changeCursor(PCARD.pc);
			//オーサリングツール中はフィールドのテキストを選択させない
			OField.updateAllTextEditable();

			GUI.removeAllListener();
			ButtonGUI.gui.addListenerToParts();
			PCARD.pc.mainPane.addMouseListener(ButtonGUI.gui);
			PCARD.pc.mainPane.addMouseMotionListener(ButtonGUI.gui);

			//メニュー
			{
				GMenu.changeEnabled("Go","Background",true);
				GMenu.changeEnabled("Objects","Button Info…",true);
				GMenu.changeEnabled("Objects","Field Info…",false);
				GMenu.changeEnabled("Objects","Bring Closer",true);
				GMenu.changeEnabled("Objects","Send Farther",true);
				
				GMenu.changeMenuName("Edit", "Cut", "Cut Button");
				GMenu.changeMenuName("Edit", "Copy", "Copy Button");
				GMenu.changeMenuName("Edit", "Paste", "Paste Button");
				GMenu.changeMenuName("Edit", "Delete", "Delete Button");
				GMenu.changeMenuName("Edit", "Cut Field", "Cut Button");
				GMenu.changeMenuName("Edit", "Copy Field", "Copy Button");
				GMenu.changeMenuName("Edit", "Paste Field", "Paste Button");
				GMenu.changeMenuName("Edit", "Delete Field", "Delete Button");
			}
			
			GMenu.updateAuthoringMenus();
			return true;
		}
		else if(PCARD.pc.intl.getToolText("Field").equalsIgnoreCase(cmd) ||
			PCARD.pc.intl.getToolEngText("Field").equalsIgnoreCase(cmd))
		{
			AuthTool.tool = new FieldTool();
			TBCursor.changeCursor(PCARD.pc);
			//オーサリングツール中はフィールドのテキストを選択させない
			OField.updateAllTextEditable();

			GUI.removeAllListener();
			FieldGUI.gui.addListenerToParts();
			PCARD.pc.mainPane.addMouseListener(FieldGUI.gui);
			PCARD.pc.mainPane.addMouseMotionListener(FieldGUI.gui);

			//メニュー
			{
				GMenu.changeEnabled("Edit","Background",true);
				GMenu.changeEnabled("Objects","Button Info…",false);
				GMenu.changeEnabled("Objects","Field Info…",true);
				GMenu.changeEnabled("Objects","Bring Closer",true);
				GMenu.changeEnabled("Objects","Send Farther",true);
				
				GMenu.changeMenuName("Edit", "Cut", "Cut Field");
				GMenu.changeMenuName("Edit", "Copy", "Copy Field");
				GMenu.changeMenuName("Edit", "Paste", "Paste Field");
				GMenu.changeMenuName("Edit", "Delete", "Delete Field");
				GMenu.changeMenuName("Edit", "Cut Button", "Cut Field");
				GMenu.changeMenuName("Edit", "Copy Button", "Copy Field");
				GMenu.changeMenuName("Edit", "Paste Button", "Paste Field");
				GMenu.changeMenuName("Edit", "Delete Button", "Delete Field");
			}
			
			GMenu.updateAuthoringMenus();
			return true;
		}
		else if("Shape".equalsIgnoreCase(PCARD.pc.intl.getToolEngText(cmd))){
			if(AuthDialog.authDialog!=null)AuthDialog.authDialog.dispose();
			AuthDialog.authDialog=null;
			ShapeTool tool=new ShapeTool();AuthTool.tool=tool;
			GUI.removeAllListener();OField.updateAllTextEditable();
			tool.install();TBCursor.changeCursor(PCARD.pc);
			GMenu.updateAuthoringMenus();
			return true;
		}
		else{
			//ボタン、フィールドツール以外
			if(AuthDialog.authDialog!=null) AuthDialog.authDialog.dispose();
			AuthDialog.authDialog = null;
		}
		
		if(PCARD.pc.intl.getToolText("Browse").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Browse").equalsIgnoreCase(cmd))
		{
			AuthTool.tool = null;
			TBCursor.changeCursor(PCARD.pc);
			//ブラウズツールに戻ったので、ロックされていないフィールドの編集を許可し直す
			OField.updateAllTextEditable();

			//バックグラウンド編集モードを解除
			{
				PaintTool.editBackground = false;
				PCARD.pc.setTitle(PCARD.pc.stack.getWindowTitle());
			}

			OCard.reloadCurrentCard();
			PCARD.pc.installStackDropTarget();
			
			//メニュー
			{
				GMenu.changeEnabled("Edit","Background",false);
				GMenu.changeSelected("Edit","Background",false);
				GMenu.changeEnabled("Objects","Button Info…",false);
				GMenu.changeEnabled("Objects","Field Info…",false);
				GMenu.changeEnabled("Objects","Bring Closer",false);
				GMenu.changeEnabled("Objects","Send Farther",false);
			}
			
			GMenu.updateAuthoringMenus();
			return true;
		}

		//
		// ここから下はペイントツール
		//

		if(PCARD.pc.intl.getToolText("Select").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Select").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new SelectTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Lasso").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Lasso").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new LassoTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("MagicWand").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("MagicWand").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new SmartSelectTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Eyedropper").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Eyedropper").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new EyedropperTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Smudge").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Smudge").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new SmudgeTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Stamp").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Stamp").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new StampTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Brush").equalsIgnoreCase(cmd) ||
			PCARD.pc.intl.getToolEngText("Brush").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new BrushTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("PaintBucket").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("PaintBucket").equalsIgnoreCase(cmd) ||
				"bucket".equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new PaintBucketTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Transparency").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Transparency").equalsIgnoreCase(cmd)){
			if(ae!=null && ae.getSource() instanceof Component){
				GToolOptions.show((Component)ae.getSource(), "Transparency");
			}
		}
		else if(PCARD.pc.intl.getToolText("Type").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Type").equalsIgnoreCase(cmd) ||
				"text".equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new TypeTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Pencil").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Pencil").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new PencilTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Eraser").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Eraser").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new EraserTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Rect").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Rect").equalsIgnoreCase(cmd) ||
				"rectangle".equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new RectTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("RoundRect").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("RoundRect").equalsIgnoreCase(cmd) ||
				"round rect".equalsIgnoreCase(cmd) || "round rectangle".equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new RoundRectTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Line").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Line").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new LineTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Oval").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Oval").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new OvalTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Curve").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Curve").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new CurveTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("SprayCan").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("SprayCan").equalsIgnoreCase(cmd) ||
				"spray can".equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new SprayTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("RegPoly").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("RegPoly").equalsIgnoreCase(cmd) ||
				"reg poly".equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new RegPolyTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(PCARD.pc.intl.getToolText("Poly").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Poly").equalsIgnoreCase(cmd)){
			PCARD.pc.tool = new PolyTool();
			TBCursor.changeCursor(PCARD.pc);
		}
		else if(cmd.equals("DummyPaint")){
			TBCursor.changeCursor(PCARD.pc);
		}
		else{
			return false;//見つからない
		}

		if(PCARD.pc.stack.curCard!=null && PCARD.pc.mainImg==null){
	    	{
	    		//ペイント用バッファ
	    		
	    		PCARD.pc.mainImg = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB );
		
	    		PCARD.pc.bgImg = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB );
				//これをRGBにするとUndo履歴やカードピクチャと互換性がとれない
				Graphics g = PCARD.pc.bgImg.getGraphics();
				g.setColor(Color.WHITE);
				g.fillRect(0,0, PCARD.pc.stack.width, PCARD.pc.stack.height);
				
				//previewBufは必要になったときに作られる
				PaintUndo.reset(PCARD.pc);
				PCARD.pc.previewBuf = null;
				PCARD.pc.previewVisible = false;
				PCARD.pc.floating = null;
	    	}
	    	
			//カードピクチャをペイント用バッファへ移動
			PCARD.pc.mainImg.getGraphics().drawImage(PCARD.pc.stack.curCard.pict, 0, 0, PCARD.pc);
			if(PCARD.pc.stack.curCard.bg!=null && PCARD.pc.stack.curCard.bg.pict!=null){
				PCARD.pc.bgImg.getGraphics().drawImage(PCARD.pc.stack.curCard.bg.pict, 0, 0, PCARD.pc);
			}
			PaintTool.adoptPictures();
			PCARD.pc.mainPane.repaint();
			
			//ペイント用リスナー
			//ペイントツールのままカードを移動すると、TTalk側でツールを選び直して
			//ここへ戻ってくる。そのとき前のカードで付けたリスナーは外れていない
			//ため、付ける前に必ず外す。二重に登録されると同じマウスイベントが
			//ツールへ2回届き、鉛筆では1回目が描いた画素を2回目が「既に前景色だ」
			//と判定してinvert(消しゴム)に切り替わるので、描いた端から消されて
			//線がまったく引けなくなる。
			GUI.removeAllListener();
			paintGUI.gui.removeListenerFromParts();
			paintGUI.gui.addListenerToParts();
			PCARD.pc.mainPane.removeMouseListener(paintGUI.gui);
			PCARD.pc.mainPane.removeMouseMotionListener(paintGUI.gui);
			PCARD.pc.mainPane.addMouseListener(paintGUI.gui);
			PCARD.pc.mainPane.addMouseMotionListener(paintGUI.gui);
			
			//これでは切り替わってくれない
			//PCARD.pc.setJMenuBar(PCARD.pc.paintMenu.mb);

	    	GMenu.menuUpdate(PCARD.pc.paintMenu.mb);
			
			//PCARD.pc.pidle.interrupt();
			/*PCARD.pc.paidle = new PaintIdle();
			PCARD.pc.paidle.start();*/
			
			//メニュー
			{
				GMenu.changeEnabled("Edit","Background",true);
			}
		}

		//フィールドにフォーカスを渡さない
		if(PCARD.pc.stack.curCard!=null){
			for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
				PCARD.pc.stack.curCard.fldList.get(i).fld.setFocusable(false);
				PCARD.pc.stack.curCard.fldList.get(i).fld.setEditable(false);
			}
			for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
				PCARD.pc.stack.curCard.bg.fldList.get(i).fld.setFocusable(false);
				PCARD.pc.stack.curCard.bg.fldList.get(i).fld.setEditable(false);
			}
		}
		
		return true;
	}
}

class TBCursor{
    static Cursor pencilCursor(){ return ClassicToolArt.cursor("Pencil"); }
    static Cursor bucketCursor(){ return ClassicToolArt.cursor("PaintBucket"); }


	static public void changeCursor(PCARDFrame frame){
		if(frame.tool==null){
			if(AuthTool.tool==null){
				//ブラウズツールはカード面に自分のカーソルを持たず、ウインドウのカーソル(set cursor・スタックの CURS 128)を見せる
				frame.mainPane.setCursor(null);
				//while a handler runs the pointer stays as it is (set cursor); returnToIdle puts the browse cursor back
				if(frame==PCARD.pc && TTalk.idle) frame.setCursor(TUtil.browseCursor());
			}
			else if(AuthTool.tool.getClass()==ButtonTool.class){
				frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
			else if(AuthTool.tool.getClass()==FieldTool.class || AuthTool.tool instanceof ShapeTool){
				frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
		else if(frame.tool.getClass()==BrushTool.class){
			BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			int i=PaintTool.brushSize;
			Graphics2D g2 = (Graphics2D) bi.getGraphics();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(new Color(0,0,0));
			if(PaintTool.classicBrush>0){
				//HyperCardのブラシは形そのものがカーソル
				int[] shape = PaintTool.CLASSIC_BRUSHES[PaintTool.classicBrush-1];
				for(int y=0; y<16; y++) for(int x=0; x<16; x++) if((shape[y]&(0x8000>>x))!=0) g2.fillRect(8+x, 8+y, 1, 1);
			}
			else g2.fillOval(16-i/2,16-i/2,i,i);

			Point hotSpot = new Point(16, 16);
			String name  = "brush-cursor";
			Toolkit kit = Toolkit.getDefaultToolkit();
			Cursor cr = kit.createCustomCursor(bi, hotSpot, name);
			frame.mainPane.setCursor(cr);
			((BrushTool)frame.tool).cursor = cr;
		}
		else if(frame.tool.getClass()==PaintBucketTool.class){
			frame.mainPane.setCursor(bucketCursor());
		}
		else if(frame.tool.getClass()==SelectTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else if(frame.tool.getClass()==TypeTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
		}
		else if(frame.tool.getClass()==PencilTool.class){
			frame.mainPane.setCursor(pencilCursor());
		}
		else if(frame.tool.getClass()==LassoTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
		}
		else if(frame.tool.getClass()==SmartSelectTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
		}
		else if(frame.tool.getClass()==EyedropperTool.class){
			frame.mainPane.setCursor(ClassicToolArt.cursor("Eyedropper"));
		}
		else if(frame.tool.getClass()==StampTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else if(frame.tool.getClass()==SmudgeTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		else if(frame.tool.getClass()==EraserTool.class){
			BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			int i=Math.min(32, Math.max(2, PaintTool.eraserSize));
			Graphics2D g2 = (Graphics2D) bi.getGraphics();
			g2.setColor(Color.WHITE);
			g2.fillRect(16-i/2,16-i/2,i,i);
			g2.setColor(Color.BLACK);
			g2.drawRect(16-i/2,16-i/2,i,i);

			Point hotSpot = new Point(16, 16);
			String name  = "eraser-cursor";
			Toolkit kit = Toolkit.getDefaultToolkit();
			Cursor cr = kit.createCustomCursor(bi, hotSpot, name);
			frame.mainPane.setCursor(cr);
			((EraserTool)frame.tool).cursor = cr;
		}
		else if(frame.tool.getClass()==RectTool.class || frame.tool.getClass()==RoundRectTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else if(frame.tool.getClass()==LineTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else if(frame.tool.getClass()==OvalTool.class || frame.tool.getClass()==CurveTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else if(frame.tool.getClass()==SprayTool.class){
			int size = Math.min(32, Math.max(4, PaintTool.sprayRadius*2));
			BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2 = (Graphics2D) bi.getGraphics();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(Color.BLACK);
			g2.drawOval(16-size/2,16-size/2,size,size);

			Point hotSpot = new Point(16, 16);
			Toolkit kit = Toolkit.getDefaultToolkit();
			Cursor cr = kit.createCustomCursor(bi, hotSpot, "spray-cursor");
			frame.mainPane.setCursor(cr);
		}
		else if(frame.tool.getClass()==RegPolyTool.class || frame.tool.getClass()==PolyTool.class){
			frame.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
	}
}

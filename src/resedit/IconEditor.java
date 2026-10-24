import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowEvent;
import javax.swing.WindowConstants;
import java.awt.event.WindowListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;
//import javax.swing.ImageIcon;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;


public class IconEditor extends PCARDFrame {
	private static final long serialVersionUID = 1L;
	IconEditor iconeditor;
	Rsrc rsrc;
	int rsrcid; //現在のid。可変。
	int orgrsrcid; //最初に開いたときに記録。不変。
	JTextField namefld;
	JTextField idfld;
	JFrame owner;
	JScrollPane scrollpane;
	JLabel zoomLabel;
	static final int MIN_ZOOM_BIT = 1;
	static final int MAX_ZOOM_BIT = 64;
	//このエディタの表示倍率(整数)。PCARDFrame.bitはfloatだがIconEditorは整数倍しか使わない
	int zoom(){ return Math.max(1, Math.round(bit)); }
	@Override
	boolean isEditingBackground(){
		// bgImg is the transparency checkerboard, never an editable resource.
		return false;
	}
	JTextField[] textfields = new JTextField[4];
	ICDropListener droplistener = new ICDropListener(this);
	String type;
	//カーソル(CURS/crsr)のホットスポット。編集中はここで保持し、save()で
	//Rsrc.rsrcClass(XML用)とリソースの生データ(.data)の両方へ書き出す。
	//左パネルのX/Y欄(hotXfld/hotYfld)から直接入力するか、「クリックで
	//指定」ボタン(hotSpotPickBtn)を押してからキャンバス上をクリック/
	//ドラッグして決める。キャンバスには常に赤い十字で現在位置を表示する。
	int hotSpotX;
	int hotSpotY;
	JTextField hotXfld;
	JTextField hotYfld;
	JToggleButton hotSpotPickBtn;
	
	public IconEditor(JFrame owner, Rsrc rsrc, String type, int id){
		this(owner, rsrc, type, id, 0, 0);
	}
	
	public IconEditor(JFrame owner, Rsrc rsrc, String type, int id, int width, int height){
		this.rsrc = rsrc;
		this.owner = owner;
		this.type = type;
		rsrcid = id;
		orgrsrcid = id;
		iconeditor = this;
		tool = new SelectTool();

		if(hasHotSpot(type)){
			Rsrc.rsrcClass res = rsrc.getResource1(id, type);
			if(res!=null){
				hotSpotX = res.hotsporleft;
				hotSpotY = res.hotsportop;
			}
		}
		
		bitLeft = 0.0f;
		bitTop = 0.0f;

		setTitle(this.rsrc.getFileName1(this.rsrcid, type));
		
		int w = 640;
		int h = 480;
		//frame
		getContentPane().setLayout(new BorderLayout());

		PaintTool.owner = pc;
		
		//menu
		new IEMenu(this);

		System.gc();
		
		//画像を読み込む
		BufferedImage bi = null;
		BufferedImage srcimg = rsrc.getImage(id, type);
		if(srcimg==null){
			if(width==0 || height == 0){
				width = 32;
				height = 32;
			}
			bi = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
		}
		else{
			if(width == 0 || height == 0){
				width = srcimg.getWidth();
				height = srcimg.getHeight();
			}
			bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			//drawImageは合成(乗算済みアルファ経由)で半透明画素の色を1丸めてしまうので、画素値をそのまま写す
			//(何も変えずに閉じたときに「変更あり」にならないように)
			int cw = Math.min(width, srcimg.getWidth()), ch = Math.min(height, srcimg.getHeight());
			bi.setRGB(0, 0, cw, ch, srcimg.getRGB(0, 0, cw, ch, null, 0, cw), 0, cw);
		}

		if(width<128 && height<128){
			bit = 8;
		}
		else if(width<320 && height<320){
			bit = 4;
		}
		else if(width<640 && height<640){
			bit = 1;
		}
		else {
			bit = 1;
		}

		//画面サイズに余裕があれば、ウインドウをもう少し広げてさらに拡大表示する
		java.awt.Rectangle screenBounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		int maxW = (int)(screenBounds.width*0.9);
		int maxH = (int)(screenBounds.height*0.9);
		while(bit<MAX_ZOOM_BIT && width*(bit*2)+180<=maxW && height*(bit*2)+45<=maxH){
			bit *= 2;
		}

		w = Math.max(w,width*zoom()+180);
		h = Math.max(h,height*zoom()+45);
		w = Math.min(w, screenBounds.width);
		h = Math.min(h, screenBounds.height);
		int windowX = owner.getX()+owner.getWidth()/2-w/2;
		int windowY = owner.getY()+owner.getHeight()/2-h/2;
		windowX = Math.max(screenBounds.x, Math.min(windowX, screenBounds.x+screenBounds.width-w));
		windowY = Math.max(screenBounds.y, Math.min(windowY, screenBounds.y+screenBounds.height-h));
		setBounds(windowX,windowY,w,h);
		
    	{
    		//ペイント用バッファ
    		mainImg = bi;
    		bgImg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_BYTE_GRAY );
			Graphics2D g = (Graphics2D) bgImg.getGraphics();
			BufferedImage txtr = new BufferedImage(4, 4, BufferedImage.TYPE_INT_BGR);
			txtr.getRaster().getDataBuffer().setElem(0,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(1,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(2,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(3,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(4,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(5,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(6,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(7,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(8,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(9,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(10,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(11,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(12,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(13,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(14,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(15,0xFFFFFF);
			Rectangle2D r = new Rectangle2D.Double(0,0,4,4);
			g.setPaint(new TexturePaint(txtr, r));
			g.fillRect(0,0, bgImg.getWidth(), bgImg.getHeight());
			//System.out.println("max:"+Runtime.getRuntime().maxMemory());
			//System.out.println("total:"+Runtime.getRuntime().totalMemory());
			if(Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
					>2*4*bi.getWidth()*bi.getHeight()){
				PaintUndo.reset(this);
			}
			else{
	    		new GDialog(this, "Out of Memory Error.",null,"OK",null,null);
			}
    	}
    	
		//leftside
		{
			JPanel leftPanel = new JPanel();
			leftPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
			leftPanel.setBounds(0, 0, 160, h);
			leftPanel.setPreferredSize(new Dimension(160, h));
			this.add("West", leftPanel);

			JPanel toolPanel = new JPanel();
			toolPanel.setLayout(null/*new GridLayout(14,1)*/);
			//ツールパレット(GToolBar)と同じボタン・間隔を流用しているので、
			//必要なサイズもGToolBarの定数から計算する。ここにはドラッグ用の
			//タイトルバー帯が無い(ウインドウ自体に付いている)ので、
			//gridHeightにはincludeHeader=falseを渡す。以前は78x210のまま
			//固定していたため、GToolBar側でボタンの間隔を広げた際に
			//下のほうの行(グラデーション・透明ボタン等)が隠れてしまっていた。
			toolPanel.setPreferredSize(new Dimension(GToolBar.gridWidth(3), GToolBar.gridHeight(8,false)));
			leftPanel.add(toolPanel);
			
			TBButton jbtn;
	        ButtonGroup grp = new ButtonGroup();
			ActionListener listener = new IEActionListener(this);
	        TBButtonListener listener2 = new TBButtonListener();
	        
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Select"),0,0);
			jbtn.setBounds(GToolBar.cell(0,0,false));
	        grp.add(jbtn);
	        jbtn.setSelected(true);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Lasso"),0,1);
			jbtn.setBounds(GToolBar.cell(0,1,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("MagicWand"),0,2);
			jbtn.setBounds(GToolBar.cell(0,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Pencil"),1,0);
			jbtn.setBounds(GToolBar.cell(1,0,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Brush"),1,1);
			jbtn.setBounds(GToolBar.cell(1,1,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Eraser"),1,2);
			jbtn.setBounds(GToolBar.cell(1,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Line"),2,0);
			jbtn.setBounds(GToolBar.cell(2,0,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("SprayCan"),2,1);
			jbtn.setBounds(GToolBar.cell(2,1,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Rect"),2,2);
			jbtn.setBounds(GToolBar.cell(2,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("RoundRect"),3,0);
			jbtn.setBounds(GToolBar.cell(3,0,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("PaintBucket"),3,1);
			jbtn.setBounds(GToolBar.cell(3,1,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Oval"),3,2);
			jbtn.setBounds(GToolBar.cell(3,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Curve"),4,0);
			jbtn.setBounds(GToolBar.cell(4,0,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Type"),4,1);
			jbtn.setBounds(GToolBar.cell(4,1,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("RegPoly"),4,2);
			jbtn.setBounds(GToolBar.cell(4,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Poly"),5,0);
			jbtn.setBounds(GToolBar.cell(5,0,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			//指先とスタンプ(カードのツールパレットと同じ並び)
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Smudge"),5,1);
			jbtn.setBounds(GToolBar.cell(5,1,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Stamp"),5,2);
			jbtn.setBounds(GToolBar.cell(5,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			this.fore = new CPButton(Color.BLACK,6,0,false);
		this.fore.setBounds(GToolBar.cell(6,0,false));
			this.fore.setFocusable(false);
			toolPanel.add(this.fore);
	        this.back = new CPButton(Color.WHITE,6,1,true);
		this.back.setBounds(GToolBar.cell(6,1,false));
	        this.back.setFocusable(false);
	        toolPanel.add(this.back);
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Eyedropper"),6,2);
			jbtn.setBounds(GToolBar.cell(6,2,false));
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
	        if(isMonochromeType(type)){
	        	//ICON/CURS/PAT#はMac本来のフォーマットが白黒(1bit)なので、
	        	//色選択を白黒だけに制限する(GPaintPalette.CPButton参照)。
	        	this.fore.monochromeOnly = true;
	        	this.back.monochromeOnly = true;
	        }
	        this.pat = new PatButton(11,7,0);
	        this.pat.frame = this;
		this.pat.setBounds(GToolBar.cell(7,0,false));
	        this.pat.setFocusable(false);
	        toolPanel.add(this.pat);
	        this.grad = new GradButton(Color.BLACK, Color.WHITE,7,1);
		this.grad.setBounds(GToolBar.cell(7,1,false));
	        this.grad.setFocusable(false);
	        toolPanel.add(this.grad);

	        this.trans = new TransButton(PCARDFrame.pc.intl.getToolText("Transparency"),7,2);
		this.trans.setBounds(GToolBar.cell(7,2,false));
	        this.trans.setFocusable(false);
	        //trans.addActionListener(listener);
	        this.trans.addMouseListener(listener2);
			toolPanel.add(this.trans);

			leftPanel.add(new MyLabelPanel("name", "Name:", rsrc.getName1(id, type), 160, 120));
			leftPanel.add(new MyLabelPanel("id", "ID:", ""+id, 160, 80));
			if(hasHotSpot(type)){
				leftPanel.add(new HotSpotPanel(160));
			}

			//拡大率を変更するボタン
			{
				JPanel zoomPanel = new JPanel();
				zoomPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 1, 0));
				zoomPanel.setPreferredSize(new Dimension(160, 24));

				JButton zoomOutBtn = new JButton("－");
				zoomOutBtn.setMargin(new Insets(0,0,0,0));
				zoomOutBtn.setPreferredSize(new Dimension(24, 22));
				zoomOutBtn.setFocusable(false);
				zoomOutBtn.addActionListener(new ActionListener(){
					public void actionPerformed(ActionEvent e){
						setZoom(zoom()/2);
					}
				});
				zoomPanel.add(zoomOutBtn);

				zoomLabel = new JLabel((zoom()*100)+"%");
				zoomLabel.setPreferredSize(new Dimension(56, zoomLabel.getPreferredSize().height));
				zoomLabel.setHorizontalAlignment(JLabel.CENTER);
				zoomPanel.add(zoomLabel);

				JButton zoomInBtn = new JButton("＋");
				zoomInBtn.setMargin(new Insets(0,0,0,0));
				zoomInBtn.setPreferredSize(new Dimension(24, 22));
				zoomInBtn.setFocusable(false);
				zoomInBtn.addActionListener(new ActionListener(){
					public void actionPerformed(ActionEvent e){
						setZoom(zoom()*2);
					}
				});
				zoomPanel.add(zoomInBtn);

				leftPanel.add(zoomPanel);
			}

			/*JPanel savePanel = new JPanel();
			leftPanel.add(savePanel);
			jbtn = new JButton(PCARD.pc.intl.getToolText("Save"));
			jbtn.addActionListener(listener);
			savePanel.add(jbtn);*/
		}

		toFront();
		setVisible(true);
		
		//scroll area
		scrollpane = new JScrollPane();
		{
			JPanel panel = new JPanel();
			panel.setLayout(null);
			this.add(panel);
			
			scrollpane.setName("JScrollPane");
			int sw = w-160;
			int sh = h-getInsets().top;
			if(sw > bi.getWidth()*zoom()+20) sw = bi.getWidth()*zoom()+20;
			if(sh > bi.getHeight()*zoom()+20) sh = bi.getHeight()*zoom()+20;

			scrollpane.setBounds(((w-160)-sw)/2, ((h-getInsets().top)-sh)/2, sw, sh);
			//scrollpane.setPreferredSize(new Dimension(sw, sh));
			scrollpane.getVerticalScrollBar().setValue(0);
			scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
			panel.add("Center", scrollpane);
		}
		
		//mainpane
		{
	    	mainPane = new MyPanel(this){
	    		private static final long serialVersionUID = 1L;
	    		@Override
	    		protected void paintComponent(Graphics g){
	    			super.paintComponent(g);
	    			paintHotSpotMarker((Graphics2D)g);
	    		}
	    	};
	    	mainPane.setLayout(null);
	    	mainPane.setPreferredSize(new Dimension(bi.getWidth()*zoom(), bi.getHeight()*zoom()));
	    	mainPane.setBounds(0, 0, bi.getWidth()*zoom(), bi.getHeight()*zoom());
	    	mainPane.setOpaque(false); //隅の画像が消えないのを抑止。
			scrollpane.setViewportView(mainPane);
			
			IconGUI gui = new IconGUI(this);
			mainPane.addMouseListener(gui);
			mainPane.addMouseMotionListener(gui);
			/*DropTarget drop = */new DropTarget(mainPane, droplistener);
		}

		setVisible(true);
		
		//閉じるときは編集内容を作業用コピーへ反映してから閉じる(IEWindowListener参照)
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		ResSession session = ResSession.ofRsrc(rsrc);
		if(session!=null) session.attach(this);
		addWindowListener(new IEWindowListener(this));
        addComponentListener(new IEComponentListener(scrollpane));
        addKeyListener(new IEKeyListener());
		// The initial activation can precede listener registration.
		PaintTool.owner = this;
        
		System.gc();
	}

	//拡大率(bit)を変更し、キャンバスとスクロール領域のサイズを再計算する
	void setZoom(int newBit){
		if(newBit<MIN_ZOOM_BIT) newBit = MIN_ZOOM_BIT;
		if(newBit>MAX_ZOOM_BIT) newBit = MAX_ZOOM_BIT;
		if(newBit==bit) return;
		// Text is a Swing component laid out at the old zoom. Commit it before
		// changing that zoom so its pixels and undo snapshot keep their size.
		if(tool instanceof TypeTool){
			PCARDFrame previous = PaintTool.owner;
			try{
				PaintTool.owner = this;
				tool.end();
			}finally{
				PaintTool.owner = previous;
			}
		}
		bit = newBit;

		Dimension size = getSize();
		int sw = size.width-160;
		int sh = size.height-getInsets().top;
		if(sw > mainImg.getWidth()*zoom()+20) sw = mainImg.getWidth()*zoom()+20;
		if(sh > mainImg.getHeight()*zoom()+20) sh = mainImg.getHeight()*zoom()+20;

		mainPane.setPreferredSize(new Dimension(mainImg.getWidth()*zoom(), mainImg.getHeight()*zoom()));
		mainPane.setBounds(0, 0, mainImg.getWidth()*zoom(), mainImg.getHeight()*zoom());

		scrollpane.setBounds(((size.width-160)-sw)/2, ((size.height-getInsets().top)-sh)/2, sw, sh);
		scrollpane.revalidate();

		if(zoomLabel!=null){
			zoomLabel.setText((zoom()*100)+"%");
		}

		mainPane.repaint();
		if(bit>1) FatBitsPreview.show(this); else FatBitsPreview.hide(this);
	}

	//メニューの「閉じる」やダイアログからのdispose()はwindowClosingを通らない。
	//閉じたエディタがownerのまま残ると、ownerを選び直さない操作(カードへの
	//画像ドロップ、スクリプトからのカード移動など)が閉じたエディタへ描いてしまう。
	@Override
	public void dispose(){
		if(PaintTool.owner == this) PaintTool.owner = PCARDFrame.pc;
		super.dispose();
	}

	void toggleZoom(){
		setZoom(bit>1 ? 1 : (mainImg.getWidth()<128 && mainImg.getHeight()<128 ? 8 : 4));
	}

	//ホットスポットを持つ(カーソルの)リソースタイプ
	static boolean hasHotSpot(String type){
		return type.equals("CURS") || type.equals("crsr");
	}

	//ホットスポットを設定する。Inside Macintosh(Imaging With QuickDraw)の
	//とおり、位置はカーソルの絵の範囲内(16x16なら(0,0)〜(15,15))に収める。
	void setHotSpot(int x, int y){
		if(mainImg!=null){
			x = Math.max(0, Math.min(x, mainImg.getWidth()-1));
			y = Math.max(0, Math.min(y, mainImg.getHeight()-1));
		}
		hotSpotX = x;
		hotSpotY = y;
		if(hotXfld!=null) hotXfld.setText(Integer.toString(x));
		if(hotYfld!=null) hotYfld.setText(Integer.toString(y));
		if(mainPane!=null) mainPane.repaint();
	}

	//左パネルのX/Y欄の入力をhotSpotX/Yへ反映する。数値でない・範囲外の
	//ときはfalseを返し(値は変えない)、欄の表示を現在の値に戻す。
	boolean applyHotSpotFields(){
		if(hotXfld==null || hotYfld==null) return true;
		boolean ok = true;
		try{
			int x = Integer.parseInt(hotXfld.getText().trim());
			int y = Integer.parseInt(hotYfld.getText().trim());
			if(mainImg!=null && (x<0 || y<0 || x>=mainImg.getWidth() || y>=mainImg.getHeight())){
				ok = false;
			}
			else{
				hotSpotX = x;
				hotSpotY = y;
			}
		}catch(NumberFormatException e){
			ok = false;
		}
		setHotSpot(hotSpotX, hotSpotY);
		return ok;
	}

	//「クリックで指定」モード中か(IconGUIがキャンバスのクリックをホット
	//スポットの設定に振り向ける)
	boolean isPickingHotSpot(){
		return hotSpotPickBtn!=null && hotSpotPickBtn.isSelected();
	}

	//キャンバス座標(mainPane上のピクセル)を絵のピクセル座標へ変換して
	//ホットスポットにする(PaintTool.mouseDownと同じ換算)
	void pickHotSpotAt(int paneX, int paneY){
		int b = zoom();
		setHotSpot(paneX/b + (int)bitLeft, paneY/b + (int)bitTop);
	}

	//キャンバスに現在のホットスポットを赤い十字とピクセル枠で重ね描きする
	//(MyPanel.paintComponentの後に呼ばれる)
	void paintHotSpotMarker(Graphics2D g){
		if(!hasHotSpot(type) || mainImg==null) return;
		int b = zoom();
		int sx = (hotSpotX-(int)bitLeft)*b;
		int sy = (hotSpotY-(int)bitTop)*b;
		int w = mainImg.getWidth()*b;
		int h = mainImg.getHeight()*b;
		//白い縁取りを付けて、黒い絵の上でも白い絵の上でも見えるようにする
		g.setColor(Color.WHITE);
		g.drawLine(sx+b/2-1, 0, sx+b/2-1, h);
		g.drawLine(sx+b/2+1, 0, sx+b/2+1, h);
		g.drawLine(0, sy+b/2-1, w, sy+b/2-1);
		g.drawLine(0, sy+b/2+1, w, sy+b/2+1);
		g.setColor(Color.RED);
		g.drawLine(sx+b/2, 0, sx+b/2, h);
		g.drawLine(0, sy+b/2, w, sy+b/2);
		if(b>=4){
			g.drawRect(sx, sy, b-1, b-1);
		}
	}

	boolean quicksave(){
		if(this.rsrc==null) return true;
		
		//this.tool.end();
		
		/*String filepath = this.rsrc.ownerstack.file.getParent()
			+File.separatorChar+"_"+this.rsrc.getFileName(this.rsrcid, "icon");
		try {
			ImageIO.write(this.mainImg, "png", new File(filepath));
		} catch (IOException e1) {
			e1.printStackTrace();
		}*/
		
		return true;
	}
	
	//2枚の画像が同じ大きさで全画素(アルファ込み)一致するか
	private static boolean samePixels(BufferedImage a, BufferedImage b){
		if(a==null || b==null || a.getWidth()!=b.getWidth() || a.getHeight()!=b.getHeight()) return false;
		int w = a.getWidth(), h = a.getHeight();
		int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
		int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
		return java.util.Arrays.equals(pa, pb);
	}

	//種別ごとの保存用拡張子。picture(PICT)は.pict(PNGで持っているものはsave()でPNGのまま)、
	//ICON/cicn/cursor/crsr/PAT#/ppatはMac生データのままの.data(HCResource.readResourceDataが
	//インポート時に使うのと同じ拡張子)、それ以外(未対応の種別)は従来通り.png。
	private static String targetExtensionFor(String type){
		if(type.equals("PICT")) return ".pict";
		if(type.equals("ICON") || type.equals("cicn") || type.equals("CURS") ||
			type.equals("crsr") || type.equals("PAT#") || type.equals("ppat")) return ".data";
		return ".png";
	}

	//ICON/CURS/PAT#は、Mac本来のフォーマットが白黒(1bit)のリソース。
	//IEMenu(別クラス)からも参照するためpackage-privateにしてある。
	static boolean isMonochromeType(String type){
		return type.equals("ICON") || type.equals("CURS") || type.equals("PAT#");
	}

	//上のisMonochromeTypeにある白黒3種それぞれに対応する、Inside Macintoshの
	//言うカラー版のリソースタイプ(cicn/crsr/ppat)。それ以外はnull。
	private static String colorTypeFor(String type){
		if(type.equals("ICON")) return "cicn";
		if(type.equals("CURS")) return "crsr";
		if(type.equals("PAT#")) return "ppat";
		return null;
	}

	private static void writeRawResource(String filepath, byte[] data) throws IOException {
		try(FileOutputStream fos = new FileOutputStream(filepath)){
			fos.write(data);
		}
	}

	boolean save(){
		if(this.rsrc==null) return true;
		PaintTool.owner = this;
		
		this.tool.end();

		int id = 0;
		try{
			id = Integer.valueOf(this.idfld.getText());
		}catch(Exception e2){ }

		if(hasHotSpot(type)){
			applyHotSpotFields();
		}
		
		//名前とidを反映
		{
			Rsrc.rsrcClass iconres;

			if(this.orgrsrcid == id){
				iconres = this.rsrc.getResource1(this.orgrsrcid, type);
			}
			else{
				String prefix;
				if(type.equals("ICON")){
					prefix = "ICON_";
				}
				else if(type.equals("cicn")){
					prefix = "cicn_";
				}
				else if(type.equals("PICT")){
					prefix = "PICT_";
				}
				else if(type.equals("CURS")){
					prefix = "CURS_";
				}
				else if(type.equals("crsr")){
					prefix = "crsr_";
				}
				else if(type.equals("PAT#")){
					prefix = "PAT#_";
				}
				else if(type.equals("ppat")){
					prefix = "ppat_";
				}
				else{
					prefix = "dummy_";
				}
				iconres = this.rsrc.new rsrcClass(0, type, "", prefix+id+targetExtensionFor(type), "0", "0", null);
			}
			
			{
				if(iconres.id!=0 && !iconres.name.equals(this.namefld.getText())){
					this.rsrc.setIdAndName(iconres, iconres.id, this.namefld.getText());
				}
				iconres.name = this.namefld.getText();
				if(hasHotSpot(type)){
					//IDを変えて別のrsrcClassになった場合も含め、ホットスポットを
					//登録情報(XMLに書かれる側)へ反映する
					iconres.hotsporleft = hotSpotX;
					iconres.hotsportop = hotSpotY;
				}
				if(id!=0 && iconres.id != id){
					if(iconres.id != 0){
						this.rsrc.deleteResource(type, iconres.id);
					}
					iconres.id = id;
					this.rsrc.addResource(iconres);
				}
			}
		}
		
		//ICON/cicn/カーソル/crsr/PAT#/ppat/PICTは、編集後もMac本来のリソース
		//バイト列のまま保存する(ImageIO.write(...,"png",...)は使わない)。
		//これらをPNGにしてしまうと、以降のエクスポートのたびに毎回この
		//アプリ独自の変換(白黒2値化・256色パレットへの量子化等)を通すことに
		//なり、せっかく再現できるはずの元の見た目が編集のたびに劣化して
		//いくことになるため。PICTだけ拡張子が.pict、それ以外は.data
		//(HCResource.readResourceDataがインポート時に使うのと同じ拡張子)。
		String targetExt = targetExtensionFor(type);
		String fileName = this.rsrc.getFileName1(id, type);
		String ext = "";
		if(fileName!=null && fileName.lastIndexOf(".")>=0){
			ext = fileName.substring(fileName.lastIndexOf("."));
			//PNGで持っているPICT(ランチャーにドロップした画像など)はPNGのまま保存する。PICTにすると
			//透明度が落ちるうえ、PNGは可逆なので劣化の心配もない(スタックへの書き出し時にPICTへ変換される)。
			//スタックの外の画像(絶対パス)もPNGで: PNGならそのファイル自身へ、JPEG等なら隣に.pngとして保存される
			if(type.equals("PICT") && (ext.equalsIgnoreCase(".png") || Rsrc.isExternalFile(fileName))) targetExt = ".png";
			if(!ext.equals(targetExt)){
				fileName = fileName.substring(0,fileName.lastIndexOf("."))+targetExt;
				Rsrc.rsrcClass res = this.rsrc.getResource1(id, type);
				res.filename = fileName;
			}
		}
		//作業用コピーなら一時フォルダへ(スタックには保存(Cmd-S)で書き戻される)
		String filepath = this.rsrc.fileToWrite(fileName).getPath();


		if(this.mainImg != null){
			//何も変えていないのに閉じただけで「未保存の変更」にならないよう、書き込んだ結果を元のファイルと比べる
			byte[] before = null;
			try {
				String beforePath = this.rsrc.getFilePathAll(id, type);
				if(beforePath!=null && new File(beforePath).isFile()) before = java.nio.file.Files.readAllBytes(new File(beforePath).toPath());
			} catch (IOException ignored) { }
			try {
				Rsrc.rsrcClass res = this.rsrc.getResource1(id, type);
				boolean asPng = !type.equals("ICON") && !type.equals("cicn") && !type.equals("CURS") && !type.equals("crsr")
					&& !type.equals("PAT#") && !type.equals("ppat") && (!type.equals("PICT") || targetExt.equalsIgnoreCase(".png"));
				//PNGはエンコーダやメタデータで同じ絵でもバイト列が変わるので、画素で比べて変わっていなければ書かない
				//(何も変えずに閉じただけで「保存しますか」と聞かれないように)
				boolean unchangedPng = false;
				if(asPng && before!=null){
					BufferedImage beforeImg = null;
					try { beforeImg = ImageIO.read(new java.io.ByteArrayInputStream(before)); } catch (IOException ignored) { }
					unchangedPng = samePixels(beforeImg, this.mainImg);
				}
				if(unchangedPng){
					//何も書かない
				}
				else if(asPng){
					ImageIO.write(this.mainImg, "png", new File(filepath));
				}
				else if(type.equals("PICT")){
					//従来のリソース編集環境が書き出すPICTファイルと同じく、
					//先頭に512バイトの(内容を使わない)ヘッダを付けて保存する
					//(HCResource.convertPICT2PICTfile参照)
					byte[] pictData = HCResourceExport.pict(this.mainImg);
					try(FileOutputStream fos = new FileOutputStream(filepath)){
						fos.write(new byte[512]);
						fos.write(pictData);
					}
				}
				else if(type.equals("ICON")){
					writeRawResource(filepath, HCResourceExport.monoBits(this.mainImg,32,32,false));
				}
				else if(type.equals("cicn")){
					writeRawResource(filepath, HCResourceExport.colorIcon(this.mainImg));
				}
				else if(type.equals("CURS")){
					//ホットスポットもCURSの生データ(offset64、v,hの順)に含めて書き出す
					ByteArrayOutputStream bytes = new ByteArrayOutputStream();
					bytes.write(HCResourceExport.monoBits(this.mainImg,16,16,false));
					bytes.write(HCResourceExport.monoBits(this.mainImg,16,16,true));
					DataOutputStream d = new DataOutputStream(bytes);
					d.writeShort(hotSpotY);
					d.writeShort(hotSpotX);
					writeRawResource(filepath, bytes.toByteArray());
				}
				else if(type.equals("crsr")){
					writeRawResource(filepath, HCResourceExport.crsr(this.mainImg, hotSpotX, hotSpotY));
				}
				else if(type.equals("PAT#")){
					writeRawResource(filepath, HCResourceExport.patSharp(this.mainImg));
				}
				else if(type.equals("ppat")){
					writeRawResource(filepath, HCResourceExport.ppat(this.mainImg));
				}
				//絵が変わった(作業用コピーなら未保存の変更になる)
				if(!unchangedPng){
					byte[] after = java.nio.file.Files.readAllBytes(new File(filepath).toPath());
					if(before==null || !java.util.Arrays.equals(before, after)) this.rsrc.markChanged();
				}
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		
		if(owner.getClass() == IconTypeEditor.class){
			IconTypeEditor typeeditor = (IconTypeEditor)owner;
			/*int id = 0;
			try{
				id = Integer.valueOf(this.idfld.getText());
			}catch(Exception e2){ }*/
			if(this.orgrsrcid!=id){
				//開き直す
				typeeditor.reload();
				typeeditor.selectIds(new int[]{id});
			}
			else{
				typeeditor.updateContent(this.rsrcid);
			}
		}
		
		OCard.reloadCurrentCard();

		return true;
	}

	//現在編集中の白黒リソース(ICON/CURS/PAT#)と同じ絵・同じIDで、対応する
	//カラー版のリソース(cicn/crsr/ppat)を新規作成し、そちらを編集するための
	//IconEditorを新しく開く。Inside Macintoshの説明どおり、同じIDのcicn/crsrが
	//あればカラー表示時にはそちらが優先して使われる(Rsrc.getDisplayImage参照)。
	void createColorCopy(){
		String colorType = colorTypeFor(this.type);
		if(colorType==null || this.mainImg==null) return;

		if(this.rsrc.getResource1(this.rsrcid, colorType)!=null){
			new GDialog(this, PCARD.pc.intl.getDialogText("A color version of this resource already exists."),
				null, "OK", null, null);
			return;
		}

		String name = this.namefld.getText();
		String newFileName = colorType+"_"+this.rsrcid+targetExtensionFor(colorType);
		String filepath = this.rsrc.fileToWrite(newFileName).getPath();

		try{
			if(colorType.equals("crsr")){
				//crsrはCURSと同じくホットスポットを持つので、編集中のCURSの
				//ホットスポット(左パネルの入力も含めた現在の値)をそのまま引き継ぐ
				applyHotSpotFields();
				int hotLeft = hotSpotX;
				int hotTop = hotSpotY;
				writeRawResource(filepath, HCResourceExport.crsr(this.mainImg, hotLeft, hotTop));
				this.rsrc.addResource(this.rsrcid, colorType, name, newFileName,
					Integer.toString(hotLeft), Integer.toString(hotTop));
			}
			else if(colorType.equals("ppat")){
				writeRawResource(filepath, HCResourceExport.ppat(this.mainImg));
				this.rsrc.addResource(this.rsrcid, colorType, name, newFileName);
			}
			else{ //cicn
				writeRawResource(filepath, HCResourceExport.colorIcon(this.mainImg));
				this.rsrc.addResource(this.rsrcid, colorType, name, newFileName);
			}
		}catch(IOException e1){
			e1.printStackTrace();
			return;
		}

		new IconEditor(this.owner, this.rsrc, colorType, this.rsrcid);
	}


	class MyLabelPanel extends JPanel
	{
		private static final long serialVersionUID = 1L;

		MyLabelPanel(String name, String labelStr, String value, int width, int fldWidth)
		{
			super();
			setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
			
			JLabel label = new JLabel(PCARDFrame.pc.intl.getDialogText(labelStr));
			add(label);
			JTextField jfld = makeField(name, value, fldWidth);
			add(jfld);
			if(name.equals("name")){
				IconEditor.this.namefld = jfld;
			}
			if(name.equals("id")){
				IconEditor.this.idfld = jfld;
			}
			
			this.setPreferredSize(new Dimension(width, jfld.getPreferredSize().height));
		}
	}

	//左パネル用の入力欄。マウスが乗るまでフォーカスを取らないようにして、
	//キャンバス操作中のキー入力(IEKeyListener)を横取りしないようにする。
	JTextField makeField(String name, String value, int fldWidth){
		JTextField jfld = new JTextField(value);
		jfld.setMargin(new Insets(0,0,0,0));
		jfld.setPreferredSize(new Dimension(fldWidth, jfld.getPreferredSize().height));
		jfld.setName(name);

		//textfieldに不要なフォーカスを取られないようにする
		jfld.setFocusable(false);
		for(int i=0; i<textfields.length; i++){
			if(textfields[i]==null) {
				textfields[i] = jfld;
				break;
			}
		}
		jfld.addMouseListener(new MouseAdapter() {
		    @Override
			public void mouseEntered(MouseEvent e) {
				((JTextField)e.getSource()).setFocusable(true);
		    }
		});
		return jfld;
	}

	//カーソル(CURS/crsr)のホットスポット欄。X/Yの数値入力欄と、キャンバス上を
	//クリックして位置を決める「クリックで指定」ボタンを縦に並べる。
	class HotSpotPanel extends JPanel
	{
		private static final long serialVersionUID = 1L;

		HotSpotPanel(int width)
		{
			super();
			//日本語のラベルが長く1行に収まらないので、ラベル行・X/Y入力行・
			//ボタン行の3段に分ける
			setLayout(new GridLayout(3,1,0,2));

			JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			labelRow.add(new JLabel(PCARDFrame.pc.intl.getDialogText("Hot Spot:")));
			add(labelRow);

			JPanel fieldRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
			fieldRow.add(new JLabel("X:"));
			hotXfld = makeField("hotx", Integer.toString(hotSpotX), 36);
			fieldRow.add(hotXfld);
			fieldRow.add(new JLabel("Y:"));
			hotYfld = makeField("hoty", Integer.toString(hotSpotY), 36);
			fieldRow.add(hotYfld);
			add(fieldRow);
			ActionListener apply = new ActionListener(){
				public void actionPerformed(ActionEvent e){
					if(!applyHotSpotFields()){
						new GDialog(IconEditor.this, PCARD.pc.intl.getDialogText("Illegal point."),
							null, "OK", null, null);
					}
				}
			};
			hotXfld.addActionListener(apply);
			hotYfld.addActionListener(apply);

			hotSpotPickBtn = new JToggleButton(PCARDFrame.pc.intl.getDialogText("Set by Click"));
			hotSpotPickBtn.setMargin(new Insets(0,4,0,4));
			hotSpotPickBtn.setFocusable(false);
			hotSpotPickBtn.setPreferredSize(new Dimension(width-16, 22));
			JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			btnRow.add(hotSpotPickBtn);
			add(btnRow);

			int rowHeight = Math.max(22, hotXfld.getPreferredSize().height);
			this.setPreferredSize(new Dimension(width, rowHeight*3+4));
		}
	}
}


class IEWindowListener implements WindowListener
{
	IconEditor owner;
	
	IEWindowListener(IconEditor owner){
		this.owner = owner;
	}
	
	@Override
	public void windowActivated(WindowEvent arg0) {
		PaintTool.owner = owner;
	}
	@Override
	public void windowClosed(WindowEvent arg0) {
	}
	@Override
	public void windowClosing(WindowEvent arg0) {
		PaintTool.owner = owner;
		owner.save();
		//セッション最後のウインドウなら、未保存の変更を保存するか尋ねる(キャンセルなら開いたまま)
		ResSession session = ResSession.ofRsrc(owner.rsrc);
		if(session!=null && !session.confirmClose(owner)) return;

		owner.iconeditor.end();
		owner.iconeditor = null;
		owner.rsrc = null;
		owner.namefld = null;
		owner.idfld = null;
		owner.owner = null;
		owner.textfields = null;
		owner.droplistener = null;
		owner.scrollpane = null;
		
		PaintTool.owner = PCARDFrame.pc;
		owner.dispose();
		System.gc();
	}
	@Override
	public void windowDeactivated(WindowEvent arg0) {
		owner.quicksave();
		// Owned dialogs still operate on this editor. The next canvas activation
		// or action selects its own owner instead of guessing the card here.
		System.gc();
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


class IEComponentListener implements ComponentListener {
	JScrollPane scrollpane;
	
	IEComponentListener(JScrollPane scrollpane){
		this.scrollpane = scrollpane;
	}
	@Override
	public void componentMoved(ComponentEvent e) {
	}
	@Override
	public void componentResized(ComponentEvent e) {
		IconEditor owner = (IconEditor)e.getSource();
		Dimension size = owner.getSize();
		int sw = size.width-160;
		int sh = size.height-((JFrame)e.getSource()).getInsets().top;
		int rate = owner.zoom();
		if(sw > owner.mainImg.getWidth()*rate+20) sw = owner.mainImg.getWidth()*rate+20;
		if(sh > owner.mainImg.getHeight()*rate+20) sh = owner.mainImg.getHeight()*rate+20;

		scrollpane.setBounds(((size.width-160)-sw)/2, ((size.height-owner.getInsets().top)-sh)/2, sw, sh);
		owner.setVisible(true);
	}
	@Override
	public void componentHidden(ComponentEvent e) {
	}
	@Override
	public void componentShown(ComponentEvent e) {
	}
}


class IconGUI implements MouseListener, MouseMotionListener
{
	IconEditor owner;
	int clickH;
	int clickV;
	boolean right = false;
	
	//「クリックで指定」でホットスポットを決めるドラッグの最中か。この間は
	//ペイントツールへマウスイベントを渡さない。
	boolean pickingHotSpot = false;
	
	IconGUI(IconEditor owner){
		this.owner = owner;
	}
    @Override
	public void mouseClicked(MouseEvent e) {
    }
    @Override
	public void mousePressed(MouseEvent e) {
		PaintTool.owner = owner;
		right = (javax.swing.SwingUtilities.isRightMouseButton(e));
		GUI.syncModifiers(e);
    	int x = e.getX();
    	int y = e.getY();
    	if(owner.isPickingHotSpot()){
    		pickingHotSpot = true;
    		owner.pickHotSpotAt(x, y);
    		return;
    	}
    	PaintTool.mouseDown(x, y);
    }
    @Override
	public void mouseReleased(MouseEvent e) {
		PaintTool.owner = owner;
    	int x = e.getX();
    	int y = e.getY();
    	if(pickingHotSpot){
    		pickingHotSpot = false;
    		owner.pickHotSpotAt(x, y);
    		//一度決めたらモードを抜けて、そのまま絵の編集に戻れるようにする
    		if(owner.hotSpotPickBtn!=null) owner.hotSpotPickBtn.setSelected(false);
    		return;
    	}
    	PaintTool.mouseUp(x, y);
    	PaintTool.lastTime = 0;
    }
    @Override
	public void mouseEntered(MouseEvent e) {
    	if(owner.textfields == null) return;
    	
		for(int i=0; i<owner.textfields.length; i++){
			if(owner.textfields[i]!=null) {
				owner.textfields[i].setFocusable(false);
			}
		}
    }
    @Override
	public void mouseExited(MouseEvent e) {
    }
	@Override
	public void mouseDragged(MouseEvent e) {
		PaintTool.owner = owner;
    	int x = e.getX();
    	int y = e.getY();
    	if(pickingHotSpot){
    		owner.pickHotSpotAt(x, y);
    		return;
    	}
    	PaintTool.mouseStillDown(x, y);
		PaintTool.lastTime = System.currentTimeMillis();
	}
	@Override
	public void mouseMoved(MouseEvent e) {
		PaintTool.owner = owner;
    	int x = e.getX();
    	int y = e.getY();
    	PaintTool.mouseWithin(x, y);
		PaintTool.lastTime = System.currentTimeMillis();
	}
}


class IEKeyListener implements KeyListener
{

	@Override
	public void keyPressed(KeyEvent e) {
		GUI.gui.keyPressed(e);
	}

	@Override
	public void keyReleased(KeyEvent e) {
		GUI.gui.keyReleased(e);
	}

	@Override
	public void keyTyped(KeyEvent e) {
		GUI.gui.keyTyped(e);
	}
}


class IEActionListener implements ActionListener
{
	IconEditor owner;
	
	IEActionListener(IconEditor frame){
		owner = frame;
	}
	
	@Override
	public void actionPerformed (ActionEvent e) {
		String in_cmd = ((JComponent)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getToolText(in_cmd);
		ChangeTool(cmd);
	}
	
	public boolean ChangeTool(String cmd) {
		PaintTool.owner = owner;
		//前のツールの終了処理
		if(owner.tool!=null){
			owner.tool.end();
		}

		if(PCARD.pc.intl.getToolText("Select").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Select").equalsIgnoreCase(cmd)){
			owner.tool = new SelectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Lasso").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Lasso").equalsIgnoreCase(cmd)){
			owner.tool = new LassoTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("MagicWand").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("MagicWand").equalsIgnoreCase(cmd)){
			owner.tool = new SmartSelectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Eyedropper").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Eyedropper").equalsIgnoreCase(cmd)){
			owner.tool = new EyedropperTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Smudge").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Smudge").equalsIgnoreCase(cmd)){
			owner.tool = new SmudgeTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Stamp").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Stamp").equalsIgnoreCase(cmd)){
			owner.tool = new StampTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARDFrame.pc.intl.getToolText("Brush").equalsIgnoreCase(cmd) ||
			PCARDFrame.pc.intl.getToolEngText("Brush").equalsIgnoreCase(cmd)){
			owner.tool = new BrushTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("PaintBucket").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("PaintBucket").equalsIgnoreCase(cmd)){
			owner.tool = new PaintBucketTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Pencil").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Pencil").equalsIgnoreCase(cmd)){
			owner.tool = new PencilTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Eraser").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Eraser").equalsIgnoreCase(cmd)){
			owner.tool = new EraserTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Line").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Line").equalsIgnoreCase(cmd)){
			owner.tool = new LineTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Rect").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Rect").equalsIgnoreCase(cmd)){
			owner.tool = new RectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("RoundRect").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("RoundRect").equalsIgnoreCase(cmd)){
			owner.tool = new RoundRectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Oval").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Oval").equalsIgnoreCase(cmd)){
			owner.tool = new OvalTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Curve").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Curve").equalsIgnoreCase(cmd)){
			owner.tool = new CurveTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Type").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Type").equalsIgnoreCase(cmd)){
			owner.tool = new TypeTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("SprayCan").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("SprayCan").equalsIgnoreCase(cmd)){
			owner.tool = new SprayTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("RegPoly").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("RegPoly").equalsIgnoreCase(cmd)){
			owner.tool = new RegPolyTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Poly").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Poly").equalsIgnoreCase(cmd)){
			owner.tool = new PolyTool();
			TBCursor.changeCursor(owner);
		}
		/*else if(PCARD.pc.intl.getToolText("OK").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("OK").equalsIgnoreCase(cmd)){
			Rsrc.rsrcClass iconres = owner.rsrc.getResource(owner.rsrcid, "icon");
			
			iconres.name = owner.namefld.getText();
			int id = 0;
			try{
				id = Integer.valueOf(owner.idfld.getText());
			}catch(Exception e2){ }
			if(id!=0 && iconres.id != id){
				owner.rsrc.deleteResource("icon", iconres.id);
				iconres.id = id;
			}
		}*/
		else{
			return false;//見つからない
		}
		
		return true;
	}
}
	

class IEMenu {
	static JMenuItem undoMenu = null;
	static JMenuItem redoMenu = null;
	static IconEditor owner;

	public static boolean changeEnabled(String menu, String item, boolean enabled){
		JMenuItem mi = GMenu.searchMenuItem(owner.getJMenuBar(), menu, item);
		if(mi==null) return false;
		
		mi.setEnabled(enabled);
		return true;
	}
	
	IEMenu(IconEditor in_owner){
		owner = in_owner;
		
		ActionListener listener=null;
		
		listener = new IEMenuListener(in_owner);
		
    	// メニューバーの設定
		JMenuBar mb=new JMenuBar();
		owner.setJMenuBar(mb);
		
		JMenu m;
		JMenuItem mi;
		JCheckBoxMenuItem cb;
		int s=InputEvent.CTRL_DOWN_MASK;
		if(System.getProperty("os.name").toLowerCase().startsWith("mac os x")){
			s = InputEvent.META_DOWN_MASK;
		}
		int s_shift = s | InputEvent.SHIFT_DOWN_MASK;

	    // Fileメニュー
	    m=new JMenu(PCARD.pc.intl.getText("File"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Close")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));mi.addActionListener(listener);

	    // Editメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Edit"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);
	    undoMenu = mi;
	    mi.setEnabled(!owner.paintHistory.past.isEmpty());
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Redo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shift));mi.addActionListener(listener);
	    redoMenu = mi;
	    mi.setEnabled(!owner.paintHistory.future.isEmpty());
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Clear Selection")));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Image Size…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));mi.addActionListener(listener);
	    if(IconEditor.hasHotSpot(owner.type)){
	    	m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Hot Spot…")));mi.addActionListener(listener);
	    }
	    if(IconEditor.isMonochromeType(owner.type)){
	    	//ICON/CURS/PAT#(白黒)を編集しているときだけ、同じ絵・同じIDの
	    	//カラー版(cicn/crsr/ppat)を作成するメニューを出す。
	    	m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Create Color Version…")));mi.addActionListener(listener);
	    }

	    // Paintメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Paint"));
	    mb.add(m);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
	    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("FatBits")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s));cb.addActionListener(listener);
	    {
			JMenu subm=new JMenu(PCARD.pc.intl.getText("Grid"));
			subm.add(mi = new JMenuItem(PCARD.pc.intl.getText("Grid Size 1")));mi.addActionListener(listener);
			subm.add(mi = new JMenuItem(PCARD.pc.intl.getText("Grid Size 16")));mi.addActionListener(listener);
			subm.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Use Grid")));cb.addActionListener(listener);cb.setSelected(IconEditor.useGrid);
			m.add(subm);
	    }
	    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Antialias")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s_shift));cb.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Reverse Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Add 1-Pixel Margin to Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Add to Protect Area")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Discard Protect Area")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    if(!IconEditor.isMonochromeType(owner.type)){
	    	//ICON/CURS/PAT#(白黒)では、任意の色に置き換えられる「色の変更…」は
	    	//色選択の制限(fore/backのCPButton.monochromeOnly)を迂回できて
	    	//しまうため出さない。
	    	m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Color Convert…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    }
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Filter…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Blending Mode…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Scale Selection…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Fill")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Invert")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Pickup")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Darken")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Lighten")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Tint")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Blend")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate Left")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate Right")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Flip Horizontal")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Flip Vertical")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, s));*/mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Opaque")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Transparent")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Keep")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Revert")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Slant")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Distort")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Stretch")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Perspective")));mi.addActionListener(listener);
	    GMenu.addPaintOptionsMenu(mb, listener);

	}
}

//メニュー動作
class IEMenuListener implements ActionListener {
	IconEditor editor;
	
	IEMenuListener(IconEditor editor){
		this.editor = editor;
	}
	
	public void actionPerformed (ActionEvent e) {
		PaintTool.owner = editor;
		String in_cmd = e.getActionCommand();
		String cmd = PCARD.pc.intl.getEngText(in_cmd);
		
		if(cmd.equalsIgnoreCase("Image Size…")){
			editor.save();
			new IconSizeDialog(editor);
		}
		else if(cmd.equalsIgnoreCase("Hot Spot…")){
			new HotSpotDialog(editor);
		}
		else if(cmd.equalsIgnoreCase("Create Color Version…")){
			editor.createColorCopy();
		}
		else if(cmd.equalsIgnoreCase("Save")){
			//編集内容を作業用コピーへ反映し、そのままスタックへ書き戻す
			editor.save();
			ResSession session = ResSession.ofRsrc(editor.rsrc);
			if(session!=null) session.save();
		}
		else if(cmd.equalsIgnoreCase("Close")){
			editor.dispatchEvent(new WindowEvent(editor, WindowEvent.WINDOW_CLOSING));
		}
		else{
			GMenuPaint.doMenu(cmd);
		}
	}
}


class IconSizeDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	JTextField widthField;
	JTextField heightField;
	IconEditor editor;
	JButton defaultButton;

	//ICONはMac本来のフォーマットが常に32x32(それ以外の種別は大きさが可変)。
	//固定サイズがあればそれを、無ければnullを返す。
	private static Dimension fixedSizeFor(String type){
		if(type.equals("ICON")) return new Dimension(32,32);
		return null;
	}

	IconSizeDialog(IconEditor owner) {
		super(owner, true);
		editor = owner;
		Dimension fixedSize = fixedSizeFor(owner.type);
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new GridLayout(2,1));
		topPanel.setPreferredSize(new Dimension(200,80));
		getContentPane().add("Center",topPanel);
		
		{
			JPanel panel = new JPanel();
			panel.setLayout(new FlowLayout());
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Width:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			
			JTextField area1 = new JTextField(fixedSize!=null ? ""+fixedSize.width : ""+owner.mainImg.getWidth());
			area1.setPreferredSize(new Dimension(64, area1.getPreferredSize().height));
			//固定サイズの種別(ICON等)は、大きさを変えられないようにする。
			area1.setEditable(fixedSize==null);
			panel.add(area1);
			widthField = area1;
			topPanel.add(panel);
		}
		{
			JPanel panel = new JPanel();
			//panel.setPreferredSize(new Dimension(320,32));

			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Height:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			JTextField area2 = new JTextField(fixedSize!=null ? ""+fixedSize.height : ""+owner.mainImg.getHeight());
			area2.setPreferredSize(new Dimension(64, area2.getPreferredSize().height));
			area2.setEditable(fixedSize==null);
			panel.add(area2);
			heightField = area2;
			topPanel.add(panel);
		}
		
		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);

		{
			JButton btn1 = new JButton("Cancel");
			btn1.addActionListener(this);
			btmPanel.add(btn1);
		}
		
		{
			JButton btn2 = new JButton("OK");
			btn2.addActionListener(this);
			btmPanel.add(btn2);
			getRootPane().setDefaultButton(btn2);
			defaultButton = btn2;
		}

		setBounds(owner.getX()+owner.getWidth()/2-120,owner.getY()+owner.getHeight()/2-120,240,160);
		setUndecorated(true);//タイトルバー非表示
		
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});
		
		setVisible(true);
		
	}
	
	public void actionPerformed(ActionEvent e) {
		if(e.getActionCommand().equals("OK")){
			int width = 0;
			int height = 0;
			try{
				width = Integer.valueOf(widthField.getText());
				height = Integer.valueOf(heightField.getText());
			}catch(Exception e2){

			}
			//固定サイズの種別(ICON等)は、フィールドは編集不可にしてあるが、
			//念のためここでも強制する。
			Dimension fixedSize = fixedSizeFor(editor.type);
			if(fixedSize!=null){
				width = fixedSize.width;
				height = fixedSize.height;
			}
			if(width<=0 || height <=0 || width*height >= 5000*5000){
	    		new GDialog(editor, PCARD.pc.intl.getDialogText("Illegal size.")
	    				,null,"OK",null,null);
			}
			else
			{
				new IconEditor(editor.owner, editor.rsrc, editor.type, editor.rsrcid, width, height);
				editor.dispose();
			}
		}
		this.dispose();
	}
}


class HotSpotDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	JTextField xField;
	JTextField yField;
	IconEditor editor;
	JButton defaultButton;
	
	HotSpotDialog(IconEditor owner) {
		super(owner, true);
		editor = owner;
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new GridLayout(2,1));
		topPanel.setPreferredSize(new Dimension(200,80));
		getContentPane().add("Center",topPanel);
		
		{
			JPanel panel = new JPanel();
			panel.setLayout(new FlowLayout());
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("X:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			
			owner.applyHotSpotFields();
			JTextField area1 = new JTextField(""+owner.hotSpotX);
			area1.setPreferredSize(new Dimension(64, area1.getPreferredSize().height));
			panel.add(area1);
			xField = area1;
			topPanel.add(panel);
		}
		{
			JPanel panel = new JPanel();
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Y:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			JTextField area2 = new JTextField(""+owner.hotSpotY);
			area2.setPreferredSize(new Dimension(64, area2.getPreferredSize().height));
			panel.add(area2);
			yField = area2;
			topPanel.add(panel);
		}
		
		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);

		{
			JButton btn1 = new JButton("Cancel");
			btn1.addActionListener(this);
			btmPanel.add(btn1);
		}
		
		{
			JButton btn2 = new JButton("OK");
			btn2.addActionListener(this);
			btmPanel.add(btn2);
			getRootPane().setDefaultButton(btn2);
			defaultButton = btn2;
		}

		setBounds(owner.getX()+owner.getWidth()/2-120,owner.getY()+owner.getHeight()/2-120,240,160);
		setUndecorated(true);//タイトルバー非表示
		
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});
		
		setVisible(true);
		
	}
	
	public void actionPerformed(ActionEvent e) {
		if(e.getActionCommand().equals("OK")){
			int x = -1;
			int y = -1;
			try{
				x = Integer.valueOf(xField.getText().trim());
				y = Integer.valueOf(yField.getText().trim());
			}catch(Exception e2){
				
			}
			//ホットスポットは絵の範囲内(16x16なら(0,0)〜(15,15))。(0,0)も有効。
			int w = editor.mainImg!=null ? editor.mainImg.getWidth() : 16;
			int h = editor.mainImg!=null ? editor.mainImg.getHeight() : 16;
			if(x<0 || y<0 || x>=w || y>=h){
	    		new GDialog(editor, PCARD.pc.intl.getDialogText("Illegal point.")
	    				,null,"OK",null,null);
			}
			else
			{
				editor.setHotSpot(x, y);
			}
		}
		this.dispose();
	}
}


class ICDropListener extends DropTargetAdapter {
	IconEditor owner;
	
	ICDropListener(IconEditor owner){
		this.owner = owner;
	}
	
	public void drop(DropTargetDropEvent e) {
		try {
			Transferable transfer = e.getTransferable();
			if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
			{
				e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
				@SuppressWarnings("unchecked")
				List<File> fileList = 
					(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));
				String path = fileList.get(0).toString();
				BufferedImage bi = null;
				try{
					bi = PictureFile.loadPbm(path);
					if(bi==null){
						bi = javax.imageio.ImageIO.read(new File(path));
					}
					if(bi==null){
						bi = PictureFile.loadPICT(path);
					}
				} catch (IOException e2) {
					e2.printStackTrace();
				}
				if(bi!=null){
					//ペーストと同じ処理で浮動選択として置く(ARGBへの変換も行われる)
					PaintTool.owner = owner;
					PaintTool.pasteAsSelection(bi);
					TBCursor.changeCursor(owner);
					owner.mainPane.repaint();
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
}

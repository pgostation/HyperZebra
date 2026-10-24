import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;


class CPButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    static CPButtonListener listener = new CPButtonListener();
	JPopupMenu popup = new JPopupMenu(); // ポップアップメニューを生成
    Color color;
    //ICON/CURS/PAT#はMac本来のフォーマットが白黒(1bit)なので、これらを
    //編集しているIconEditorではtrueにして、色の選択を白黒だけに制限する
    //(IconEditor.java参照)。
    boolean monochromeOnly = false;
    //背景色ボタンか(色選択ダイアログのタイトル用)
    boolean isback;

    //monochromeOnly==trueのボタンをクリックしたときの色選択。フルカラーの
    //色選択ダイアログは開かず、白と黒をクリックのたびに入れ替えるだけにする。
    static Color monochromeToggle(Color current){
    	return current.equals(Color.BLACK) ? Color.WHITE : Color.BLACK;
    }

    //ボタンを押したときに出るクイックパレット。Macシステム256色のマス目と
    //最近使った色の列があり、マスを押すとその場で色が決まってパレットが
    //閉じる(以前のように色選択ダイアログでOKを押す必要がない)。HSB/RGBの
    //数値指定や画面からの色取得が要るときだけ「その他の色…」で従来の
    //ダイアログを開く(そちらも色を変えた瞬間にボタンへ反映される)。
    //fore/back/AuthColorButtonの全部で使うので、初回に押されたときに作る。
    JPopupMenu palettePopup;
    JPanel recentPanel;
    static final int SWATCH = 12;
    static final int RECENT_MAX = 16;
    //最近使った色(新しい順)。fore/backや複数のツールパレットで共有する。
    static final java.util.List<Color> recentColors = new java.util.ArrayList<Color>();

    static void rememberRecent(Color col){
    	if(col==null) return;
    	Color opaque = new Color(col.getRed(), col.getGreen(), col.getBlue());
    	recentColors.remove(opaque);
    	recentColors.add(0, opaque);
    	while(recentColors.size()>RECENT_MAX){
    		recentColors.remove(recentColors.size()-1);
    	}
    }

    //クイックパレットで色を決めたときの処理。色見本を更新して最近使った色に
    //加え、パレットを閉じる(AuthColorButtonではmakeIconがオブジェクトの
    //色も更新する)。
    void pick(Color col){
    	rememberRecent(col);
    	makeIcon(col);
    	if(palettePopup!=null){
    		palettePopup.setVisible(false);
    	}
    }

    void showPalette(){
    	if(palettePopup==null){
    		palettePopup = new JPopupMenu();
    		JPanel body = new JPanel(new BorderLayout(0,4));
    		body.setBorder(javax.swing.BorderFactory.createEmptyBorder(4,4,4,4));
    		body.add(MacSystemColorChooserPanel.createSwatchGrid(SWATCH, this::pick), BorderLayout.CENTER);

    		JPanel south = new JPanel(new BorderLayout(4,0));
    		recentPanel = new JPanel(new GridLayout(1,RECENT_MAX,1,1));
    		south.add(recentPanel, BorderLayout.CENTER);
    		JButton more = new JButton(PCARDFrame.pc.intl.getToolText("Other Colors…"));
    		more.setFocusable(false);
    		more.setMargin(new Insets(0,4,0,4));
    		more.addActionListener(new ActionListener(){
				@Override
				public void actionPerformed(ActionEvent e){
					palettePopup.setVisible(false);
					openColorDialog();
				}
			});
    		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));
    		east.add(more);
    		south.add(east, BorderLayout.EAST);
    		body.add(south, BorderLayout.SOUTH);
    		palettePopup.add(body);
    	}
    	//最近使った色の列は開くたびに作り直す(RECENT_MAX個分の幅は常に確保する)。
    	recentPanel.removeAll();
    	for(int i=0; i<RECENT_MAX; i++){
    		if(i<recentColors.size()){
    			recentPanel.add(MacSystemColorChooserPanel.createSwatch(recentColors.get(i), SWATCH, this::pick));
    		}else{
    			JPanel empty = new JPanel();
    			empty.setOpaque(false);
    			empty.setPreferredSize(new Dimension(SWATCH,SWATCH));
    			recentPanel.add(empty);
    		}
    	}
    	palettePopup.pack();
    	palettePopup.show(this, 0, getHeight());
    }

    //従来の色選択ダイアログ(GColorDialog)をモードレスで開く。ダイアログを
    //出したままペイントツールを使え、色を変えた瞬間にこのボタン(と
    //AuthColorButtonならオブジェクト)へ反映される。Cancelで元の色に戻る。
    //OKで開いたときと違う色に決めたら最近使った色に加える。
    void openColorDialog(){
    	Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
    	//ツールパレット(JWindow)の上のボタンなら、書類ウインドウを親にする
    	if(!(owner instanceof java.awt.Frame) && !(owner instanceof java.awt.Dialog)){
    		owner = PCARD.pc;
    	}
    	GColorDialog.open(owner, dialogTitle(), this, color, true, this::makeIcon, CPButton::rememberRecent);
    }

    String dialogTitle(){
    	return PCARDFrame.pc.intl.getToolText(isback ? "Background Color" : "Foreground Color");
    }

	CPButton(Color in_color, int y, int x, boolean isback){
		super("");
		this.isback = isback;
        this.setFocusable(false);
		BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
		Graphics g = bi.createGraphics();
		g.setColor(in_color);
		g.fillRect(0,0,16,12);
		this.setIcon(new ImageIcon(bi));
		this.color = in_color;
		//this.setForeground(color);
		//this.setBackground(color);
		//this.setBounds((y*3+x)*24,0/*y*20*/,24,20);
		this.setBounds(GToolBar.cell(y,x));
        setMargin(new Insets(0,0,0,0));
        this.addActionListener(listener);
        this.addMouseListener(listener);
        
        if(isback)
        {
        	addPopupMenuItem(PCARDFrame.pc.intl.getToolText("Transparency"), new ActionListener(){
				@Override
				public void actionPerformed(ActionEvent e){
					Color c = PaintTool.owner.back.color;
					int alpha = c.getAlpha()==0?0xFF:0x00;
					PaintTool.owner.back.color = new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
					PaintTool.owner.back.makeIcon(PaintTool.owner.back.color);
				}
			});
        }
	}

	// メニュー項目を追加
	private JMenuItem addPopupMenuItem(String name, ActionListener al){
		JMenuItem item = new JMenuItem(name);
		item.addActionListener(al);
		popup.add(item);
		return item;
	}
	
	public void makeIcon(Color col){
		this.color = col;
		BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
		Graphics g = bi.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0,0,16,12);
		g.setColor(Color.GRAY);
		g.drawRect(0,0,8,12);
		g.drawRect(0,0,16,6);
		g.setColor(Color.BLACK);
		g.drawRect(0,0,16,12);
		g.setColor(col);
		g.fillRect(0,0,16,12);
		this.setIcon(new ImageIcon(bi));
		//モードレスの色選択ダイアログがこのボタンに付いていれば、スポイト等で
		//外から変わった色をダイアログ側にも映す。
		GColorDialog.syncTarget(this, col);
		//パターンボタンの絵は前景色/背景色で色付けしているので作り直す。
		PCARDFrame owner = PaintTool.owner;
		if(owner!=null && owner.pat!=null && (owner.fore==this || owner.back==this)){
			owner.pat.refreshIcon();
		}
		//入力中のペイント文字は前景色で描かれるので、その場で色を変える
		if(owner!=null && owner.fore==this) TypeTool.refreshLive();
	}
	
	//paintComponentをオーバーライドすると表示が崩れる
    /*@Override
    protected void paintComponent(Graphics g) {
    }*/
}


class CPButtonListener implements ActionListener, MouseListener {
	@Override
	public void actionPerformed (ActionEvent e) {
		CPButton btn = (CPButton)e.getSource();
		if(btn.monochromeOnly){
			btn.makeIcon(CPButton.monochromeToggle(btn.color));
		}else{
			//マスを押すだけで色が決まるクイックパレットを出す。従来の色選択
			//ダイアログはその中の「その他の色…」から開く(openColorDialog)。
			btn.showPalette();
		}
	}

	@Override
	public void mouseClicked(MouseEvent arg0) {
		if(javax.swing.SwingUtilities.isRightMouseButton(arg0)){
			((CPButton)arg0.getSource()).popup.show(arg0.getComponent(), arg0.getX(), arg0.getY());
		}
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {
	}
	@Override
	public void mouseExited(MouseEvent arg0) {
	}
	@Override
	public void mousePressed(MouseEvent arg0) {
	}
	@Override
	public void mouseReleased(MouseEvent arg0) {
	}
}





//グラデーションボタン。「塗り」の種類を決める: 切(色/パターンで塗る)・線形・
//放射状。押すとGToolOptions.showGradientOptionsのポップアップが出て、種類の
//マス目と角度のダイヤルで決める。塗りはバケツ・塗りつぶし図形(Draw Filled)・
//選択範囲のFillの全部で同じ規則(ShapeToolBase.fillShape / PaintBucketTool.
//gradfill)。色は常に今の前景色→背景色。
class GradButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    static GradButtonListener listener = new GradButtonListener();
    Color color1;
    Color color2;
    double angle;
    boolean use = false;
    //放射状(中心が前景色、外側が背景色)。falseなら角度に沿った線形。
    boolean radial = false;
    //テクスチャ(PaintTexture.current)を敷き詰める。use && !radial && textureで有効
    boolean texture = false;
	
    GradButton(Color color1, Color color2, int y, int x){
		super("Gradation");
        this.setFocusable(false);
		this.setText(this.use?"":"off");
		this.color1 = color1;
		this.color2 = color2;
		this.angle = 0;
		this.setBounds(GToolBar.cell(y,x));
        setMargin(new Insets(0,0,0,0));
        this.addActionListener(listener);
        this.addMouseListener(listener);
        setToolTipText(PCARD.pc==null ? "Gradient" : PCARD.pc.intl.getToolText("Gradient"));
	}

	//塗りの種類を変える(0=切、1=線形、2=放射状、3=テクスチャ)。メニュー/HyperTalk共通。
	void setKind(int kind){
		use = kind!=0;
		radial = kind==2;
		texture = kind==3;
		if(texture) PaintTexture.ensureSelected();
		setText(use?"":"off");
		makeIcon();
	}
	int kind(){ return !use ? 0 : texture ? 3 : radial ? 2 : 1; }
	
	public void makeIcon(){
		if(this.use && this.texture){
			this.setIcon(new ImageIcon(PaintTexture.thumbnail(PaintTexture.current, 12)));
		}
		else if(this.use && PaintTool.owner!=null && PaintTool.owner.fore!=null && PaintTool.owner.back!=null){
			this.color1 = PaintTool.owner.fore.color;
			this.color2 = PaintTool.owner.back.color;
			BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_ARGB);
			bi.getGraphics().fillRect(0,0,16,12);
			PaintBucketTool.gradfill(bi,this.color1,this.color2,this.angle,this.radial);
			this.setIcon(new ImageIcon(bi));
		}else{
			this.setIcon(null);
		}
	}
}


class GradButtonListener implements ActionListener, MouseListener {
	@Override
	public void actionPerformed (ActionEvent e) {
	}
	
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
		//左右どちらのボタンでも同じポップアップ(種類のマス目+角度ダイヤル)
		GradButton btn = (GradButton)arg0.getSource();
		java.awt.Window window = SwingUtilities.getWindowAncestor(btn);
		if(window instanceof PCARDFrame) PaintTool.owner = (PCARDFrame)window;
		else if(window!=null && window.getOwner() instanceof PCARDFrame) PaintTool.owner = (PCARDFrame)window.getOwner();
		GToolOptions.showGradientOptions(btn);
	}
	@Override
	public void mouseReleased(MouseEvent arg0) {
	}
}


class PatButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    static PatButtonListener listener = new PatButtonListener();
	JPopupMenu popup = new JPopupMenu(); // ポップアップメニューを生成
    int pattern;
	BufferedImage[] patterns = new BufferedImage[40];
	//このボタンが載っているツールパレットの持ち主(前景色/背景色を読む)。
	//GToolBar/IconEditorが作った後に設定する。nullなら黒/白で表示する。
	PCARDFrame frame;
	
    PatButton(int in_pattern, int y, int x){
		super(/*"Pattern"*/);
        this.setFocusable(false);
		this.pattern = in_pattern;
		//this.setBounds((y*3+x)*24,0/*y*20*/,24,20);
		this.setBounds(GToolBar.cell(y,x));
        setMargin(new Insets(0,0,0,0));
        //this.addActionListener(listener);
        this.addMouseListener(listener);

    	for(int i=0; i<patterns.length; i++){
    		String name = PCARDFrame.pc.stack.Pattern[i];
    		if(name!=null && name.length()>0){
    			File f = new File(PCARDFrame.pc.stack.file.getParent()+File.separatorChar+name);
    			patterns[i] = loadPatternFile(f);
				if(patterns[i]==null){
        			patterns[i] = PictureFile.loadPbm("resource"+File.separatorChar+"PAT_"+i+".pbm");
    			}
    			if(patterns[i]==null){
    				patterns[i] = new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
    				Graphics g = patterns[i].getGraphics();
    				g.setColor(Color.BLACK);
    				g.fillRect(0,0,8,8);
    			}
    		}
    	}
    	
        //パターン画像が無ければ./resource/のPAT_n.pngから取ってくる
        for(int i=0; i<patterns.length; i++){
        	if(patterns[i]==null){
				File ifile = new File("."+File.separatorChar+"resource"+File.separatorChar+"PAT_"+(i+1)+".png");
				try {
					patterns[i] = ImageIO.read(ifile);
				} catch (IOException e) {
					e.printStackTrace();
				}
        	}
        }
		refreshIcon();
		popup.add(new PatternGrid());
		popup.addSeparator();
		JMenuItem edit=new JMenuItem(PCARD.pc.intl.getText("Edit Pattern…"));
		edit.addActionListener(e->{ popup.setVisible(false); new PatternEditor(PatButton.this,pattern).showEditor(); });
		popup.add(edit);
		popup.addSeparator();
		JMenuItem copy=new JMenuItem(PCARD.pc.intl.getText("Copy Pattern"));
		copy.addActionListener(e->copyPattern(pattern));
		popup.add(copy);
		JMenuItem paste=new JMenuItem(PCARD.pc.intl.getText("Paste Pattern"));
		paste.addActionListener(e->{ popup.setVisible(false); pastePattern(pattern); });
		popup.add(paste);
		JMenuItem pickup=new JMenuItem(PCARD.pc.intl.getText("Pattern from Selection"));
		pickup.addActionListener(e->{ popup.setVisible(false); pickupPattern(pattern); });
		popup.add(pickup);
		popup.addSeparator();
		JMenuItem imp=new JMenuItem(PCARD.pc.intl.getText("Import Pattern…"));
		imp.addActionListener(e->{ popup.setVisible(false); importPattern(pattern); });
		popup.add(imp);
		JMenuItem exp=new JMenuItem(PCARD.pc.intl.getText("Export Pattern…"));
		exp.addActionListener(e->{ popup.setVisible(false); exportPattern(pattern); });
		popup.add(exp);
		JMenuItem impAll=new JMenuItem(PCARD.pc.intl.getText("Import All Patterns…"));
		impAll.addActionListener(e->{ popup.setVisible(false); importAllPatterns(); });
		popup.add(impAll);
		JMenuItem expAll=new JMenuItem(PCARD.pc.intl.getText("Export All Patterns…"));
		expAll.addActionListener(e->{ popup.setVisible(false); exportAllPatterns(); });
		popup.add(expAll);
		//出すたびに、貼れるもの・取り込めるものがあるかで項目の有効/無効を決める
		popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){
			@Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){
				paste.setEnabled(clipboardPattern()!=null);
				pickup.setEnabled(selectionForPattern()!=null);
			}
			@Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){}
			@Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}
		});
	}

	//--- パターンのコピー/ペースト/選択範囲からの取り込み/書き出し/読み込み ---
	//コピーしたパターンの原画(インク形式のまま)。同じアプリ内(別のスタック、
	//アイコン編集器)へはこれで劣化なく渡り、システムのクリップボードにも画像と
	//して載せるので他のアプリへも貼れる。クリップボードの中身が入れ替わったら
	//(copiedOwnerでなくなったら)原画は使わず、クリップボードの画像を使う。
	static BufferedImage copiedPattern;
	static java.awt.datatransfer.Transferable copiedOwner;

	void copyPattern(int i){
		BufferedImage src=patterns[i];
		if(src==null) return;
		BufferedImage copy=new BufferedImage(src.getWidth(),src.getHeight(),BufferedImage.TYPE_INT_ARGB);
		copy.getGraphics().drawImage(src,0,0,null);
		copiedPattern=copy;
		try{
			GMenuPaint.ImageSelection sel=new GMenuPaint.ImageSelection(colorized(i),src.getWidth(),src.getHeight());
			copiedOwner=sel;
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel,sel);
		}catch(RuntimeException ex){
			//ヘッドレス等でクリップボードが使えなくてもアプリ内のコピーは効く
			copiedOwner=null;
		}
	}

	//貼れるパターン(無ければnull)。
	static BufferedImage clipboardPattern(){
		java.awt.datatransfer.Transferable contents=null;
		try{
			contents=java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
		}catch(RuntimeException ex){
			return copiedPattern;
		}
		if(copiedPattern!=null && (copiedOwner==null || contents==copiedOwner)) return copiedPattern;
		if(contents==null || !contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) return null;
		try{
			Object data=contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
			if(data instanceof java.awt.Image) return fromImage((java.awt.Image)data);
		}catch(Exception ex){
		}
		return null;
	}

	boolean pastePattern(int i){
		BufferedImage img=clipboardPattern();
		if(img==null) return false;
		store(i,fromImage(img));
		select(i);
		return true;
	}

	//「今の選択範囲をパターンにする」(AppleWorksのPickup)。持ち上がっている
	//浮動選択(矩形・投げなわ・自動選択・ペースト)の絵をそのまま使う。
	BufferedImage selectionForPattern(){
		if(frame==null || frame.floating==null) return null;
		return frame.floating.cropped();
	}

	boolean pickupPattern(int i){
		BufferedImage img=selectionForPattern();
		if(img==null) return false;
		store(i,fromImage(img));
		select(i);
		return true;
	}

	//任意の画像をパターンの原画(インク形式)にする。灰色のドットはインク
	//(黒=前景色、白=背景色)、色の付いたドットは固定色、透明なドットは背景。
	//大きすぎる絵は左上の64x64を使い、それ以外はppatの規則(2のべき乗)へ敷き詰める。
	static BufferedImage fromImage(java.awt.Image src){
		int w=src.getWidth(null), h=src.getHeight(null);
		if(w<=0||h<=0) return null;
		int cw=Math.min(w,MAX_SIDE), ch=Math.min(h,MAX_SIDE);
		BufferedImage argb=new BufferedImage(cw,ch,BufferedImage.TYPE_INT_ARGB);
		Graphics g=argb.getGraphics();
		g.drawImage(src,0,0,null);
		g.dispose();
		for(int y=0;y<ch;y++) for(int x=0;x<cw;x++) argb.setRGB(x,y,PatternEditor.normalize(argb.getRGB(x,y)));
		return fitToPpat(argb);
	}

	//ファイル選択の親(ツールパレットはJWindowなのでFrame/Dialogまでたどる)
	Window dialogOwner(){
		Window owner=SwingUtilities.getWindowAncestor(this);
		while(owner!=null && !(owner instanceof java.awt.Frame) && !(owner instanceof java.awt.Dialog)) owner=owner.getOwner();
		return owner==null ? PCARDFrame.pc : owner;
	}

	static final javax.swing.filechooser.FileNameExtensionFilter PATTERN_FILTER=
		new javax.swing.filechooser.FileNameExtensionFilter("Pattern (ppat, png, pbm)","ppat","png","pbm");

	//パターン1つをppat(拡張子.pngならPNG)へ書き出す
	void exportPattern(int i){
		if(patterns[i]==null) return;
		javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getText("Export Pattern…").replace("…",""));
		chooser.setDialogType(javax.swing.JFileChooser.SAVE_DIALOG);
		chooser.setFileFilter(PATTERN_FILTER);
		chooser.setSelectedFile(new File("PAT_"+(i+1)+".ppat"));
		if(PCARD.showFileChooser(chooser,dialogOwner(),true)!=javax.swing.JFileChooser.APPROVE_OPTION) return;
		File f=chooser.getSelectedFile();
		try{
			writePatternFile(patterns[i],f);
		}catch(IOException ex){
			ex.printStackTrace();
		}
	}

	static void writePatternFile(BufferedImage image,File f)throws IOException{
		String name=f.getName().toLowerCase(java.util.Locale.ROOT);
		if(name.endsWith(".png")){
			ImageIO.write(image,"png",f);
		}else{
			if(!name.endsWith(".ppat")) f=new File(f.getParentFile(),f.getName()+".ppat");
			java.nio.file.Files.write(f.toPath(),HCResourceExport.ppat(fitToPpat(image)));
		}
	}

	//ファイル(ppat/png/pbm)をパターンiへ読み込む
	void importPattern(int i){
		javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getText("Import Pattern…").replace("…",""));
		chooser.setDialogType(javax.swing.JFileChooser.OPEN_DIALOG);
		chooser.setFileFilter(PATTERN_FILTER);
		if(PCARD.showFileChooser(chooser,dialogOwner(),false)!=javax.swing.JFileChooser.APPROVE_OPTION) return;
		BufferedImage img=loadPatternFile(chooser.getSelectedFile());
		if(img==null) return;
		store(i,fromImage(img));
		select(i);
	}

	//40個全部をフォルダへPAT_1〜PAT_40.ppatとして書き出す(別のスタックで
	//「すべて読み込む」で受け取れる)
	void exportAllPatterns(){
		javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getText("Export All Patterns…").replace("…",""));
		chooser.setDialogType(javax.swing.JFileChooser.SAVE_DIALOG);
		chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
		chooser.setSelectedFile(new File("Patterns"));
		if(PCARD.showFileChooser(chooser,dialogOwner(),true)!=javax.swing.JFileChooser.APPROVE_OPTION) return;
		File dir=chooser.getSelectedFile();
		try{
			exportAllPatterns(dir);
		}catch(IOException ex){
			ex.printStackTrace();
		}
	}

	void exportAllPatterns(File dir)throws IOException{
		if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create "+dir);
		for(int i=0;i<patterns.length;i++){
			if(patterns[i]==null) continue;
			writePatternFile(patterns[i],new File(dir,"PAT_"+(i+1)+".ppat"));
		}
	}

	//フォルダ(書き出したもの、または別のスタックの.hzbバンドル)からPAT_n.*を
	//全部読み込む。スタックの中のファイルを選んでもその入れ物を見る。
	void importAllPatterns(){
		javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getText("Import All Patterns…").replace("…",""));
		chooser.setDialogType(javax.swing.JFileChooser.OPEN_DIALOG);
		chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);
		if(PCARD.showFileChooser(chooser,dialogOwner(),false)!=javax.swing.JFileChooser.APPROVE_OPTION) return;
		importAllPatterns(chooser.getSelectedFile());
	}

	int importAllPatterns(File from){
		File dir=from.isDirectory() ? from : from.getParentFile();
		if(dir==null) return 0;
		int count=0;
		for(int i=0;i<patterns.length;i++){
			File f=null;
			for(String ext : new String[]{".ppat",".png",".pbm"}){
				File c=new File(dir,"PAT_"+(i+1)+ext);
				if(c.isFile()){ f=c; break; }
			}
			if(f==null) continue;
			BufferedImage img=loadPatternFile(f);
			if(img==null) continue;
			store(i,fromImage(img));
			count++;
		}
		refreshIcon();
		return count;
	}

	//スタック内のパターンファイルを読む。保存形式はMac本来のppat(カラーの
	//ピクセルパターン、拡張子.ppat、HCResourceExport.ppat)。古いスタックの
	//PNG/PBMもそのまま読める。
	static BufferedImage loadPatternFile(File f){
		if(!f.isFile()) return null;
		try{
			if(f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ppat")){
				return HCResource.decodePpat(java.nio.file.Files.readAllBytes(f.toPath()));
			}
			BufferedImage bi = ImageIO.read(f);
			if(bi==null) bi = PictureFile.loadPbm(f.getPath());
			return bi;
		}catch(IOException e){
			e.printStackTrace();
			return null;
		}
	}

	//ppatの大きさの規則: 幅と高さは2のべき乗(8〜64)。それ以外は切り上げて
	//敷き詰める(PixPatの仕様に合わせる)。
	static final int MIN_SIDE=8, MAX_SIDE=64;
	static int ppatSide(int n){
		int side=MIN_SIDE;
		while(side<n && side<MAX_SIDE) side*=2;
		return side;
	}
	static BufferedImage fitToPpat(BufferedImage src){
		int w=ppatSide(src.getWidth()), h=ppatSide(src.getHeight());
		if(w==src.getWidth() && h==src.getHeight()) return src;
		BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<h;y++) for(int x=0;x<w;x++) out.setRGB(x,y,src.getRGB(x%src.getWidth(),y%src.getHeight()));
		return out;
	}

	//パターンiを差し替えて、スタックへppatとして保存する(パターン編集器と
	//HyperTalkのset … of pattern iの両方から使う)。
	void store(int i, BufferedImage image){
		if(i<0 || i>=patterns.length || image==null) return;
		image = fitToPpat(image);
		patterns[i] = image;
		if(i==pattern) refreshIcon();
		popup.repaint();
		try{
			OStack stack=PCARDFrame.pc.stack;
			if(stack!=null && stack.file!=null){
				File base=stack.file.getParentFile().getCanonicalFile();
				String newName="PAT_"+(i+1)+".ppat";
				File target=new File(base,newName).getCanonicalFile();
				if(target.toPath().startsWith(base.toPath())){
					java.nio.file.Files.write(target.toPath(), HCResourceExport.ppat(image));
					//古い形式(PNG等)のファイルは要らなくなる
					String oldName=stack.Pattern[i];
					if(oldName!=null && !oldName.equals(newName)){
						File old=new File(base,oldName).getCanonicalFile();
						if(old.toPath().startsWith(base.toPath()) && old.isFile()) old.delete();
					}
					stack.Pattern[i]=newName;
				}
				stack.changed=true;
			}
		}catch(IOException ex){
			ex.printStackTrace();
		}
	}

	//パターンを選ぶ。ボタンの絵とポップアップの選択枠を更新する
	//(HyperTalkの set pattern からも使う)。
	void select(int in_pattern){
		if(in_pattern<0 || in_pattern>=patterns.length) return;
		pattern = in_pattern;
		refreshIcon();
		popup.repaint();
	}

	Color foreColor(){
		return (frame!=null && frame.fore!=null) ? frame.fore.color : Color.BLACK;
	}
	Color backColor(){
		return (frame!=null && frame.back!=null) ? frame.back.color : Color.WHITE;
	}

	//パターンiを今の前景色/背景色で色付けした絵(PaintTool.colorizePatternの規則)。
	BufferedImage colorized(int i){
		BufferedImage pat = patterns[i];
		if(pat==null) return null;
		return PaintTool.colorizePattern(pat, foreColor(), backColor());
	}

	//透明なドットが分かるように、市松模様の上へ敷き詰めて描く。
	static void paintTiled(Graphics g, BufferedImage pat, int x, int y, int width, int height){
		Graphics c = g.create(x, y, width, height);
		for(int py=0; py<height; py+=4){
			for(int px=0; px<width; px+=4){
				c.setColor((((px/4)+(py/4))&1)==0 ? Color.WHITE : new Color(0xCCCCCC));
				c.fillRect(px, py, 4, 4);
			}
		}
		if(pat!=null){
			for(int py=0; py<height; py+=pat.getHeight()){
				for(int px=0; px<width; px+=pat.getWidth()){
					c.drawImage(pat, px, py, null);
				}
			}
		}
		c.dispose();
	}

	//ボタンの絵を今の前景色/背景色で作り直す(色ボタンが変わったときにも呼ぶ)。
	void refreshIcon(){
		BufferedImage pat = colorized(pattern);
		if(pat==null) return;
		BufferedImage icon = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
		Graphics g = icon.getGraphics();
		paintTiled(g, pat, 0, 0, 16, 16);
		g.dispose();
		setIcon(new ImageIcon(icon));
	}

	//本物のHyperCardのパターンパレットと同じ4列×10段の升目。番号は列ごとに
	//上から下へ(パターン1が左上、11が2列目の一番上、12が黒)。升の中に
	//パターンを敷き詰め、黒い罫線で区切る。選択中の升は太い枠で囲む。
	//升を押すとその場で決まる。右クリック、または下の編集項目で8x8編集を開く。
	static final int COLS = 4, ROWS = 10, CELL = 20, GAP = 1;
	class PatternGrid extends JPanel {
		private static final long serialVersionUID = 1L;
		PatternGrid(){
			setOpaque(true);
			setBackground(Color.WHITE);
			setPreferredSize(new Dimension(COLS*(CELL+GAP)+GAP, ROWS*(CELL+GAP)+GAP));
			addMouseListener(new java.awt.event.MouseAdapter(){
				@Override
				public void mousePressed(MouseEvent e){
					int col = (e.getX()-GAP)/(CELL+GAP);
					int row = (e.getY()-GAP)/(CELL+GAP);
					if(col<0 || col>=COLS || row<0 || row>=ROWS) return;
					int index=col*ROWS+row;
					select(index);
					popup.setVisible(false);
					if(e.getClickCount()>=2 || SwingUtilities.isRightMouseButton(e))
						new PatternEditor(PatButton.this,index).showEditor();
				}
			});
		}
		@Override
		protected void paintComponent(Graphics g){
			super.paintComponent(g);
			for(int col=0; col<COLS; col++){
				for(int row=0; row<ROWS; row++){
					int i = col*ROWS+row;
					int x = GAP+col*(CELL+GAP), y = GAP+row*(CELL+GAP);
					paintTiled(g, colorized(i), x, y, CELL, CELL);
				}
			}
			g.setColor(Color.BLACK);
			for(int col=0; col<=COLS; col++){
				g.fillRect(col*(CELL+GAP), 0, GAP, ROWS*(CELL+GAP)+GAP);
			}
			for(int row=0; row<=ROWS; row++){
				g.fillRect(0, row*(CELL+GAP), COLS*(CELL+GAP)+GAP, GAP);
			}
			//選択中の升: 白い縁取り付きの太い黒枠
			int scol = pattern/ROWS, srow = pattern%ROWS;
			int sx = scol*(CELL+GAP), sy = srow*(CELL+GAP);
			g.setColor(Color.WHITE);
			g.drawRect(sx+1, sy+1, CELL, CELL);
			g.setColor(Color.BLACK);
			g.drawRect(sx, sy, CELL+2, CELL+2);
			g.drawRect(sx-1, sy-1, CELL+4, CELL+4);
		}
	}
}


//HyperCardのパターン編集器のカラー版。1マスを8x8〜64x64(ppatの規則で2の
//べき乗)のドットとして直接編集し、右側へ実寸を敷き詰めて表示する。
//ドットのインクは
//  前景 … 描くときの前景色になる(黒として保存)
//  背景 … 描くときの背景色になる(白として保存)。背景色を透明にすれば抜ける
//  色   … 前景色/背景色に関係なくその色のまま(固定色)
//の3種類(PaintTool.colorizePattern参照)。左ボタンで選んだインクを置き
//(同じインクの上なら背景に戻す)、右ボタン/Optionクリックでそのドットの
//インクを拾う。矢印キーと下のボタンでずらし・反転・回転・全消去・復帰。
//保存はスタック内のppat(PatButton.store)。
class PatternEditor {
	static final int INK_FORE=0xFF000000, INK_BACK=0xFFFFFFFF;
	static final int MAX_SIDE=PatButton.MAX_SIDE;
	int w=8, h=8;
	int cell=24;
	final PatButton button;
	final int index;
	final int[][] cells=new int[MAX_SIDE][MAX_SIDE];
	JDialog dialog;
	JPanel grid;
	JPanel preview;
	int ink=INK_FORE;
	int paintInk;
	//インク選択の見本(前景/背景/色)。色の見本はCPButtonそのもので、
	//押せばクイックパレット、右クリックで色選択ダイアログが開く。
	JPanel inkRow;
	CPButton colorSwatch;

	PatternEditor(PatButton button, int index){
		this.button=button;
		this.index=index;
		for(int y=0;y<MAX_SIDE;y++) for(int x=0;x<MAX_SIDE;x++) cells[y][x]=INK_BACK;
		BufferedImage src=button.patterns[index];
		if(src!=null){
			src=PatButton.fitToPpat(src);
			w=src.getWidth(); h=src.getHeight();
			for(int y=0;y<h;y++) for(int x=0;x<w;x++){
				cells[y][x]=normalize(src.getRGB(x,y));
			}
		}
		cell=cellFor(w,h);
	}

	static int cellFor(int w,int h){
		return Math.max(4, Math.min(24, 256/Math.max(w,h)));
	}

	//読み込んだドットをインクへ丸める: 灰色→黒/白(中間の灰色はそのまま混色と
	//して残す)、色付き→固定色。透明はppatに無いので背景扱い。
	static int normalize(int c){
		int a=(c>>>24)&0xFF;
		if(a<128) return INK_BACK;
		int r=(c>>16)&0xFF, g=(c>>8)&0xFF, b=c&0xFF;
		if(r==g && g==b){
			if(r<8) return INK_FORE;
			if(r>247) return INK_BACK;
		}
		return c|0xFF000000;
	}

	BufferedImage makeImage(){
		BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<h;y++) for(int x=0;x<w;x++)
			image.setRGB(x,y,cells[y][x]);
		return image;
	}

	//今の前景色/背景色で色付けした絵(プレビューと升目の表示用)。
	BufferedImage colorizedImage(){
		return PaintTool.colorizePattern(makeImage(), button.foreColor(), button.backColor());
	}

	//インク→表示色。前景/背景は今の色(透明な背景色なら市松が見える)。
	Color displayColor(int c){
		if(c==INK_FORE) return button.foreColor();
		if(c==INK_BACK) return button.backColor();
		int r=(c>>16)&0xFF, g=(c>>8)&0xFF, b=c&0xFF;
		if(r==g && g==b){
			//中間の灰色: 前景色と背景色の混色
			Color f=button.foreColor(), k=button.backColor();
			return new Color((r*k.getRed()+(255-r)*f.getRed())/255,(r*k.getGreen()+(255-r)*f.getGreen())/255,(r*k.getBlue()+(255-r)*f.getBlue())/255,(r*k.getAlpha()+(255-r)*f.getAlpha())/255);
		}
		return new Color(c, true);
	}

	int currentInk(){
		return ink==INK_FORE||ink==INK_BACK ? ink : (colorSwatch==null ? ink : colorSwatch.color.getRGB());
	}

	void setCell(MouseEvent e, boolean first){
		int x=e.getX()/cell, y=e.getY()/cell;
		if(x<0||x>=w||y<0||y>=h) return;
		if(first){
			if(SwingUtilities.isRightMouseButton(e) || e.isAltDown()){
				pickInk(cells[y][x]);
				return;
			}
			int want=currentInk();
			paintInk = cells[y][x]==want ? INK_BACK : want;
		}else if(SwingUtilities.isRightMouseButton(e) || e.isAltDown()){
			return;
		}
		cells[y][x]=paintInk;
		refresh();
	}

	//ドットからインクを拾う。固定色なら色の見本をその色にする。
	void pickInk(int c){
		if(c==INK_FORE||c==INK_BACK){
			ink=c;
		}else{
			ink=c;
			if(colorSwatch!=null) colorSwatch.makeIcon(new Color(c,true));
		}
		if(inkRow!=null) inkRow.repaint();
	}

	void refresh(){
		if(grid!=null) grid.repaint();
		if(preview!=null) preview.repaint();
	}

	//--- 変形 ---
	void transform(java.util.function.BiFunction<Integer,Integer,Integer> src){
		int[][] copy=new int[h][w];
		for(int y=0;y<h;y++) for(int x=0;x<w;x++) copy[y][x]=src.apply(x,y);
		for(int y=0;y<h;y++) for(int x=0;x<w;x++) cells[y][x]=copy[y][x];
		refresh();
	}
	void shift(int dx,int dy){
		transform((x,y)->cells[((y-dy)%h+h)%h][((x-dx)%w+w)%w]);
	}
	void flipHorizontal(){ transform((x,y)->cells[y][w-1-x]); }
	void flipVertical(){ transform((x,y)->cells[h-1-y][x]); }
	//90度回転(正方形でなければ幅と高さが入れ替わる)
	void rotate(){
		int[][] copy=new int[w][h];
		for(int y=0;y<w;y++) for(int x=0;x<h;x++) copy[y][x]=cells[h-1-x][y];
		int t=w; w=h; h=t;
		for(int y=0;y<h;y++) for(int x=0;x<w;x++) cells[y][x]=copy[y][x];
		relayout();
	}
	//前景と背景を入れ替える(固定色はそのまま)
	void invert(){
		transform((x,y)->{ int c=cells[y][x]; return c==INK_FORE ? INK_BACK : c==INK_BACK ? INK_FORE : c; });
	}
	void clear(){ transform((x,y)->INK_BACK); }
	//最初から付いてくるパターン(resource/PAT_n.png)へ戻す
	void revert(){
		File ifile=new File("."+File.separatorChar+"resource"+File.separatorChar+"PAT_"+(index+1)+".png");
		try{
			BufferedImage src=ImageIO.read(ifile);
			if(src==null) return;
			setSize(src.getWidth(),src.getHeight());
			for(int y=0;y<h;y++) for(int x=0;x<w;x++) cells[y][x]=normalize(src.getRGB(x%src.getWidth(),y%src.getHeight()));
			refresh();
		}catch(IOException ex){
			ex.printStackTrace();
		}
	}
	//大きさを変える(ppatの規則で2のべき乗に丸める)。大きくするときは敷き詰め、
	//小さくするときは左上を残す。
	void setSize(int newW,int newH){
		newW=PatButton.ppatSide(newW); newH=PatButton.ppatSide(newH);
		if(newW==w && newH==h) return;
		for(int y=0;y<newH;y++) for(int x=0;x<newW;x++) if(x>=w||y>=h) cells[y][x]=cells[y%h][x%w];
		w=newW; h=newH;
		relayout();
	}
	void setSide(int side){ setSize(side,side); }
	void relayout(){
		cell=cellFor(w,h);
		if(grid!=null){
			grid.setPreferredSize(new Dimension(w*cell,h*cell));
			if(sizeBox!=null){
				int idx=sizeIndex(w,h);
				if(idx>=0 && sizeBox.getSelectedIndex()!=idx){ sizeBoxUpdating=true; sizeBox.setSelectedIndex(idx); sizeBoxUpdating=false; }
			}
			dialog.pack();
		}
		refresh();
	}
	static final int[] SIDES={8,16,32,64};
	javax.swing.JComboBox<String> sizeBox;
	boolean sizeBoxUpdating;
	static int sizeIndex(int w,int h){
		if(w!=h) return -1;
		for(int i=0;i<SIDES.length;i++) if(SIDES[i]==w) return i;
		return -1;
	}

	void accept(){
		button.store(index, makeImage());
		button.select(index);
		dialog.dispose();
	}

	static void checker(Graphics g,int x,int y,int w,int h){
		for(int py=0;py<h;py+=4) for(int px=0;px<w;px+=4){
			g.setColor((((px/4)+(py/4))&1)==0 ? Color.WHITE : new Color(0xCCCCCC));
			g.fillRect(x+px,y+py,Math.min(4,w-px),Math.min(4,h-py));
		}
	}

	//インクの見本(前景/背景/透明)。押すとそのインクになる。
	class InkSwatch extends JPanel {
		private static final long serialVersionUID=1L;
		final int value;
		InkSwatch(int value,String tip){
			this.value=value;
			setPreferredSize(new Dimension(24,20));
			setToolTipText(PCARD.pc.intl.getText(tip));
			addMouseListener(new java.awt.event.MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){ ink=value; inkRow.repaint(); }
			});
		}
		@Override protected void paintComponent(Graphics g){
			checker(g,1,1,getWidth()-2,getHeight()-2);
			Color c=displayColor(value);
			if(c!=null){ g.setColor(c); g.fillRect(1,1,getWidth()-2,getHeight()-2); }
			g.setColor(Color.BLACK);
			g.drawRect(0,0,getWidth()-1,getHeight()-1);
			if(ink==value){ g.setColor(Color.WHITE); g.drawRect(1,1,getWidth()-3,getHeight()-3); g.setColor(Color.BLACK); g.drawRect(2,2,getWidth()-5,getHeight()-5); }
		}
	}

	JButton smallButton(String text,String tip,Runnable action){
		JButton b=new JButton(text);
		b.setToolTipText(PCARD.pc.intl.getText(tip));
		b.setMargin(new Insets(1,4,1,4));
		b.setFocusable(false);
		b.addActionListener(e->action.run());
		return b;
	}

	void showEditor(){
		//ツールパレットがJWindow(フレームでもダイアログでもない窓)に載っていると
		//JDialogの親にできないので、FrameかDialogが見つかるまで親をたどる。
		Window owner=SwingUtilities.getWindowAncestor(button);
		while(owner!=null && !(owner instanceof java.awt.Frame) && !(owner instanceof java.awt.Dialog)) owner=owner.getOwner();
		if(owner==null) owner=PCARDFrame.pc;
		dialog=new JDialog(owner,PCARD.pc.intl.getText("Edit Pattern"),java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setLayout(new BorderLayout(10,10));
		grid=new JPanel(){
			private static final long serialVersionUID=1L;
			@Override protected void paintComponent(Graphics g){
				super.paintComponent(g);
				for(int y=0;y<h;y++) for(int x=0;x<w;x++){
					checker(g,x*cell,y*cell,cell,cell);
					Color c=displayColor(cells[y][x]);
					if(c!=null){ g.setColor(c); g.fillRect(x*cell,y*cell,cell,cell); }
					if(cell>=8){ g.setColor(Color.GRAY); g.drawRect(x*cell,y*cell,cell,cell); }
				}
			}
		};
		grid.setPreferredSize(new Dimension(w*cell,h*cell));
		java.awt.event.MouseAdapter painter=new java.awt.event.MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){ setCell(e,true); }
			@Override public void mouseDragged(MouseEvent e){ setCell(e,false); }
		};
		grid.addMouseListener(painter); grid.addMouseMotionListener(painter);
		preview=new JPanel(){
			private static final long serialVersionUID=1L;
			@Override protected void paintComponent(Graphics g){
				super.paintComponent(g);
				PatButton.paintTiled(g,colorizedImage(),0,0,getWidth(),getHeight());
			}
		};
		preview.setPreferredSize(new Dimension(72,8*24));
		JPanel center=new JPanel(new BorderLayout(10,0));
		JPanel gridHolder=new JPanel(new FlowLayout(FlowLayout.CENTER,0,0));
		gridHolder.add(grid);
		center.add(gridHolder,BorderLayout.CENTER); center.add(preview,BorderLayout.EAST);
		dialog.add(center,BorderLayout.CENTER);

		//上段: インク(前景/背景/透明/色)と大きさ
		inkRow=new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));
		inkRow.add(new JLabel(PCARD.pc.intl.getText("Ink")+":"));
		inkRow.add(new InkSwatch(INK_FORE,"Foreground Color"));
		inkRow.add(new InkSwatch(INK_BACK,"Background Color"));
		colorSwatch=new CPButton(new Color(0x0090FF),0,0,false){
			private static final long serialVersionUID=1L;
			@Override public void makeIcon(Color col){
				super.makeIcon(col);
				ink=col.getRGB();
				if(inkRow!=null) inkRow.repaint();
			}
			@Override protected void paintComponent(Graphics g){
				super.paintComponent(g);
				boolean on = ink!=INK_FORE && ink!=INK_BACK;
				g.setColor(Color.BLACK);
				g.drawRect(0,0,getWidth()-1,getHeight()-1);
				if(on){ g.setColor(Color.WHITE); g.drawRect(1,1,getWidth()-3,getHeight()-3); g.setColor(Color.BLACK); g.drawRect(2,2,getWidth()-5,getHeight()-5); }
			}
		};
		colorSwatch.setPreferredSize(new Dimension(24,20));
		colorSwatch.setToolTipText(PCARD.pc.intl.getText("Color"));
		colorSwatch.addMouseListener(new java.awt.event.MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){ ink=colorSwatch.color.getRGB(); inkRow.repaint(); }
		});
		inkRow.add(colorSwatch);
		inkRow.add(javax.swing.Box.createHorizontalStrut(12));
		String[] sizeNames=new String[SIDES.length];
		for(int i=0;i<SIDES.length;i++) sizeNames[i]=SIDES[i]+"×"+SIDES[i];
		sizeBox=new javax.swing.JComboBox<String>(sizeNames);
		int sizeIdx=sizeIndex(w,h);
		if(sizeIdx>=0) sizeBox.setSelectedIndex(sizeIdx);
		sizeBox.setFocusable(false);
		sizeBox.addActionListener(e->{ if(!sizeBoxUpdating) setSide(SIDES[sizeBox.getSelectedIndex()]); });
		inkRow.add(sizeBox);
		dialog.add(inkRow,BorderLayout.NORTH);

		//下段: 変形ボタンとOK/キャンセル
		JPanel south=new JPanel(new BorderLayout());
		JPanel tools=new JPanel(new GridLayout(2,5,2,2));
		tools.add(smallButton("←","Shift Left",()->shift(-1,0)));
		tools.add(smallButton("→","Shift Right",()->shift(1,0)));
		tools.add(smallButton("↑","Shift Up",()->shift(0,-1)));
		tools.add(smallButton("↓","Shift Down",()->shift(0,1)));
		tools.add(smallButton("⇄","Flip Horizontal",()->flipHorizontal()));
		tools.add(smallButton("⇅","Flip Vertical",()->flipVertical()));
		tools.add(smallButton("↻","Rotate",()->rotate()));
		tools.add(smallButton(PCARD.pc.intl.getText("Invert"),"Invert",()->invert()));
		tools.add(smallButton(PCARD.pc.intl.getText("Clear"),"Clear",()->clear()));
		tools.add(smallButton(PCARD.pc.intl.getText("Revert"),"Revert",()->revert()));
		JPanel toolHolder=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
		toolHolder.add(tools);
		south.add(toolHolder,BorderLayout.NORTH);
		JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton cancel=new JButton(PCARD.pc.intl.getText("Cancel")); cancel.addActionListener(e->dialog.dispose());
		JButton ok=new JButton(PCARD.pc.intl.getText("OK")); ok.addActionListener(e->accept());
		buttons.add(new JLabel(PCARD.pc.intl.getText("100% Preview"))); buttons.add(cancel); buttons.add(ok);
		south.add(buttons,BorderLayout.SOUTH);
		dialog.add(south,BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(ok);

		//矢印キーでずらす
		javax.swing.InputMap im=dialog.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
		javax.swing.ActionMap am=dialog.getRootPane().getActionMap();
		String[] keys={"LEFT","RIGHT","UP","DOWN"};
		int[][] deltas={{-1,0},{1,0},{0,-1},{0,1}};
		for(int i=0;i<keys.length;i++){
			final int[] d=deltas[i];
			im.put(javax.swing.KeyStroke.getKeyStroke(keys[i]),"shift"+keys[i]);
			am.put("shift"+keys[i],new javax.swing.AbstractAction(){
				private static final long serialVersionUID=1L;
				@Override public void actionPerformed(ActionEvent e){ shift(d[0],d[1]); }
			});
		}
		im.put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"),"cancel");
		am.put("cancel",new javax.swing.AbstractAction(){
			private static final long serialVersionUID=1L;
			@Override public void actionPerformed(ActionEvent e){ dialog.dispose(); }
		});

		dialog.pack(); dialog.setResizable(false); dialog.setLocationRelativeTo(button); dialog.setVisible(true);
	}
}



class PatButtonListener implements MouseListener {
	
	@Override
	public void mouseClicked(MouseEvent arg0) {
		//String cmd = ((PatButton)arg0.getSource()).getText();
		//if(PCARDFrame.pc.intl.getToolText("Pattern").equals(cmd.split(" ")[0])){
			PatButton b = (PatButton)arg0.getSource();
			b.popup.show(b, 0, b.getHeight());
		//}
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {
	}
	@Override
	public void mouseExited(MouseEvent arg0) {
	}
	@Override
	public void mousePressed(MouseEvent arg0) {
	}
	@Override
	public void mouseReleased(MouseEvent arg0) {
	}
}



class TransButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	//透明度の選択はクリック/ダブルクリック/右クリックのどれでもGToolOptionsの
	//スライダーで行う(TBButtonListener・ChangeTool参照)。ボタンの表示は
	//今の透明度(%)。
	TransButton(String in_text, int y, int x){
		super();
        this.setFocusable(false);
		this.setBounds(GToolBar.cell(y,x));
		this.setName(in_text);
        setMargin(new Insets(0,0,0,0));
        setText(100-PaintTool.alpha+"%");
        setFont(new Font("",0,10));
	}
}

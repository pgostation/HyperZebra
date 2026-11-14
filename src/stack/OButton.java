import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.dnd.DropTarget;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.MatteBorder;


public class OButton extends OObject implements Cloneable {
	OCardBase card = null;
	//OBackground bkgd = null;
	MyButton btn = null;
	MyCheck chkbox = null;
	MyRadio radio = null;
	MyPopup popup = null;
	//popup(style==8)がshowName==trueのとき、ボタン全体の矩形のうち左側に
	//titleWidthの幅で名前を表示するためのラベル(MyPopup.setBounds参照)。
	JLabel popupNameLabel = null;
	BufferedImage imageForScale; //アイコン拡大縮小用データ;
	
	//プロパティ
	//Boolean autoHilite=true;
	Color color=Color.BLACK;
	Color bgColor=Color.WHITE;
	//ハイライト時の塗りの色(hiliteColorプロパティ)。nullならHyperCardと同じ反転
	Color hiliteColor=null;
	int group=0;
	Boolean check_hilite=false;//他のカードからの参照用
	boolean autoHilite=true;//作成時のみ参照
	boolean hilite=false;//作成時のみ参照
	int icon=0;
	URI iconURI;
	//number (カードの情報から求める)
	Boolean sharedHilite=false;
	Boolean showName=true;
	int style=0;//0標準 1透明 2不透明 3長方形 4シャドウ 5丸みのある長方形 6省略時設定 7楕円 8ポップアップ 9チェックボックス 10ラジオ
	int textAlign=1;//0左 1中 2右
	String textFont="";
	int textHeight=16;
	int textSize=12;
	int textStyle=0;//
	int titleWidth=0;

	//popup(style==8)ボタンの、名前表示に使う幅。実機のポップアップメニュー
	//コントロールと同様、showNameがオンかつtitleWidthが0より大きいときだけ、
	//全体の矩形のうち左側をこの幅で名前表示に使う(ボタン幅を超えないよう
	//クランプする)。MyPopup.setBoundsから呼ばれる。
	static int popupNameWidthFor(OButton btn, int width) {
		return (btn.showName && btn.titleWidth>0) ? Math.min(btn.titleWidth, width) : 0;
	}

	int selectedLine=0;
	boolean scaleIcon = false;
	private boolean iconAspect = false; // Opt-in: legacy stacks retain stretch scaling.
	int blendMode;
	int blendLevel = 100;
	
	//追加プロパティ
	@SuppressWarnings("unused")
	private String reserved1;
	@SuppressWarnings("unused")
	private String reserved2;
	@SuppressWarnings("unused")
	private String reserved3;
	@SuppressWarnings("unused")
	private String reserved4;
	@SuppressWarnings("unused")
	private String reserved5;
	@SuppressWarnings("unused")
	private String reserved25;
	
	//2倍拡大表示モードでは、アイコン(ドット絵)もピクセルパーフェクトに2倍で
	//表示したい。読み込み済みのImageIconをそのまま使うのではなく、この
	//ScaledButtonIconでラップし、getIconWidth/Height()がscale()倍の値を返す
	//ようにする(JButtonのアイコン用レイアウト計算にもそのまま使われるため)。
	static Icon makeButtonIcon(ImageIcon plain){
		if(plain==null) return null;
		double scale = Host.get().scale();
		int w = plain.getIconWidth();
		int h = plain.getIconHeight();
		//a PICT whose srcXor/notSrcXor bits invert the card under the button (PictureFile.withInversion)
		if(scale<=1 || w<=0 || h<=0) return PictureFile.invertingIcon(plain, plain.getImage());
		return PictureFile.invertingIcon(new ScaledButtonIcon(plain.getImage(), w, h, scale), plain.getImage());
	}

	static class ScaledButtonIcon implements Icon {
		private final Image img;
		private final int baseW, baseH;
		private final double scale;
		ScaledButtonIcon(Image img, int baseW, int baseH, double scale){
			this.img = img;
			this.baseW = baseW;
			this.baseH = baseH;
			this.scale = scale;
		}
		public int getIconWidth(){ return (int)Math.round(baseW*scale); }
		public int getIconHeight(){ return (int)Math.round(baseH*scale); }
		public void paintIcon(java.awt.Component c, Graphics g, int x, int y){
			Graphics2D g2 = (g instanceof Graphics2D) ? (Graphics2D)g : null;
			Object oldHint = null;
			if(g2!=null){
				//整数倍ならドット絵をにじませず、ピクセルパーフェクトに拡大表示する
				oldHint = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, DisplayZoom.interpolation(scale));
			}
			g.drawImage(img, x, y, x+getIconWidth(), y+getIconHeight(), 0, 0, baseW, baseH, c);
			if(g2!=null && oldHint!=null){
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint);
			}
		}
	}
	DropTarget drop;
	
	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}
	
	JComponent getComponent(){
		if(btn!=null) return btn;
		if(popup!=null) return popup;
		if(radio!=null) return radio;
		if(chkbox!=null) return chkbox;
		return null;
	}
	
	//set
	public void setName(String in) {
		name=in;
		UiHost.get().setNameView167(this, in);
		UiHost.get().setNameView170(this, in);
		UiHost.get().setNameView173(this, in);
		UiHost.get().setNameView176(this, in);
		if(icon==-1) {
			//PICTボタンの場合
			setIcon(-1, false);
		}
		else if(!HZ.lockedScreen) {
			Host.get().repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setIcon(int in) {
		setIcon(in, false);
	}

	//"set icon of btn to N"用。setIcon(int)が実際に表示できるアイコンかどうかを、
	//setIconと同じ探索順(cicn/ICONリソース→旧形式のresource/iconN.png/.jpeg)で
	//調べる。0(アイコンなし)と-1(PICTボタン)は常に設定可能。HyperCardでは
	//存在しないIDを指定してもボタンのアイコンは変わらない(メッセージボックス
	//からならエラー)ので、TUtil.SetPropertyが設定前にこれで確認する。
	public boolean iconExists(int id) {
		if(id==0 || id==-1) return true;
		OStack stack = parent instanceof OCardBase ? ((OCardBase)parent).stack : null;
		if(stack==null || stack.rsrc==null) return false;
		if(stack.rsrc.getResourceAll(id, "cicn")!=null) return true;
		if(stack.rsrc.getResourceAll(id, "ICON")!=null) return true;
		if(UiHost.get().hasSystemIcon(id)) return true;
		if(stack.file==null) return false;
		String base = stack.file.getParent()+"/resource/icon"+id;
		return new File(base+".png").exists() || new File(base+".jpeg").exists();
	}
	/** The System file's alert icons that HyperCard finds at the end of its resource chain: ICON 1 (note) and 2 (caution),
	 *  drawn as HyperZebra's own System gives them to 68K programs (Rinaldi's X-Archive "Info" button has icon 1). */
	static BufferedImage systemIcon(int id){ return UiHost.get().systemIcon(id); }
	static final java.util.Map<Integer,BufferedImage> SYSTEM_ICONS = new java.util.HashMap<Integer,BufferedImage>();
	/** The ICON (or cicn) {@code id} as this button shows it: the stack, the stacks in use, the old resource/iconN files, the System's; null when none. */
	BufferedImage findIconImage(int id){
		// a button of a stack closed meanwhile (its card is still painted once) has no stack: only the System's icons then
		OStack st = parent instanceof OCardBase ? ((OCardBase)parent).stack : null;
		BufferedImage image = st==null ? null : st.rsrc.getDisplayImage(id, "ICON");
		if(image==null && card.stack!=null && card.stack.file!=null){
			String base=card.stack.file.getParent()+"/resource/icon"+id;
			File legacy=new File(base+".png");
			if(!legacy.exists()) legacy=new File(base+".jpeg");
			if(legacy.exists()) image=Rsrc.cachedDisplayImageFile(legacy.getPath(), "ICON");
		}
		if(image==null) image = systemIcon(id);
		return image;
	}
	static final long ICON_FOUND = Long.MIN_VALUE;
	/** {@link #usingKey} when the button's icon was not found (it may be in a stack put in use later), else ICON_FOUND. */
	volatile long missingIconUsing = ICON_FOUND;
	/** The stacks in use by this button's stack, as one number (their identities in order). */
	long usingKey(){
		OStack st = parent instanceof OCardBase ? ((OCardBase)parent).stack : null;
		java.util.ArrayList<OStack> using = st==null ? null : st.usingStacks;
		long k = 1;
		if(using!=null) try{ for(int i=0; i<using.size(); i++) k = k*31+System.identityHashCode(using.get(i)); }catch(RuntimeException e){ /* changed meanwhile: the next paint looks again */ }
		return k;
	}
	/**
	 * (EDT, before the button paints) An icon that was not there when the button was built is looked for again once the stacks
	 * in use have changed: go stack builds the new stack's first card before the stacks in use (start using, HyperCard's
	 * stacksInUse belong to HyperCard, not to a stack) are handed over to it, and Content Selector's Home button (icon 18878,
	 * from Start Here) stayed a white box on "intro" until the button was drawn anew.
	 */
	void findMissingIcon(){
		if(missingIconUsing==ICON_FOUND || btn==null || !(icon>0 || icon<-1) || iconURI!=null) return;
		long k = usingKey();
		if(k==missingIconUsing) return;
		missingIconUsing = k;
		BufferedImage image = findIconImage(icon);
		if(image==null) return;
		missingIconUsing = ICON_FOUND;
		if(scaleIcon || blendMode!=0) imageForScale = image;
		MyButton b = btn;
		b.setIcon(makeButtonIcon(new ImageIcon(image)));
		if(btn.btnData.showName){
			b.setHorizontalTextPosition(SwingConstants.CENTER);
			b.setVerticalTextPosition(SwingConstants.BOTTOM);
		}
	}
	void setIcon(int in, boolean noupdate) {
		//if(icon==in) return;
		icon=in;
		iconURI=null;
		missingIconUsing = ICON_FOUND;
		UiHost.get().iconOnView(this, in, noupdate);
		//buildOButton(noupdate=true)からの呼び出しは表示の組み立てであって変更では
		//ないので、changedを立てない。立てていると、アイコン付きボタンのある
		//カードへ移動するたびにスタック全体が保存し直されていた。
		if(!noupdate) ((OCardBase)parent).changed = true;
	}
	public void setIconURI(URI in) {
		setIconURI(in, false);
	}
	void setIconURI(URI in, boolean noupdate) {
		//if(icon==in) return;
		icon=0;
		iconURI = in;
		UiHost.get().iconURIOnView(this, in, noupdate);
		if(!noupdate) ((OCardBase)parent).changed = true;
	}

	/*static NullPaintManager nullPaintManager = new NullPaintManager();
	static class NullPaintManager extends RepaintManager{
		@Override
        public void addDirtyRegion(JComponent c, int x, int y, int w,int h) {
		}
	}*/
	
	public void setTopLeft(int h, int v) {
		final int oldLeft=left;
		final int oldTop=top;
		if(h==oldLeft && v==oldTop) StackSpeed.unchanged(); //HyperCard draws nothing for it (speed limit)
		left=h;
		top=v;
		UiHost.get().setTopLeftView286(this, h, v, oldLeft, oldTop);
		((OCardBase)parent).changed = true;
		((OCardBase)parent).movePanelPeers(this, h-oldLeft, v-oldTop);
		UiHost.get().cardWindow68kPartMoved(this, oldTop, oldLeft, oldTop+height, oldLeft+width); //an external's drawing there goes
	}
	public void setRect(int v1, int v2, int v3, int v4) {
		final int oldLeft=left;
		final int oldTop=top;
		final int oldWidth=width;
		final int oldHeight=height;
		left=v1;
		top=v2;
		width=v3-v1;
		height=v4-v2;
		UiHost.get().setRectView328(this, v1, v2, v3, v4, oldLeft, oldTop, oldWidth, oldHeight);
		((OCardBase)parent).changed = true;
		UiHost.get().cardWindow68kPartMoved(this, oldTop, oldLeft, oldTop+oldHeight, oldLeft+oldWidth); //an external's drawing there goes
	}
	public void setText(String in) {
		super.setText(in);
		UiHost.get().setTextView356(this, in);
		((OCardBase)parent).changed = true;
	}
	public void setVisible(boolean in) {
		if(getVisible()==in) return;
		super.setVisible(in);
		UiHost.get().setVisibleView371(this, in);
	}
	/** Its panel was shown or hidden, or it joined or left one: the component follows isShown. */
	@Override void applyShown(){
		if(getComponent()==null) return;
		Host.get().runOnEDTWhenShown(this, Host.EDT_VISIBLE, () -> {
			applyVisible();
			if(!HZ.lockedScreen) Host.get().repaintLogicalRect(left, top, width, height);
		}, this::applyVisible);
	}
	/** (EDT) The component shows the visibility the button has now (with its panel's). */
	void applyVisible(){
		JComponent component=getComponent();
		UiHost.get().applyVisibleView375(this, component);
	}
	/** (EDT) The component has the rectangle the button has now. */
	void applyBounds(){
		JComponent component=getComponent();
		if(component!=null) component.setBounds(left, top, width, height);
	}
	public void setEnabled(boolean in) {
		enabled = in;
		UiHost.get().setEnabledView562(this, in);
		((OCardBase)parent).changed = true;
	}
	public void setHilite(boolean in) {
		hilite = in;
		check_hilite = in;
		storeCardHilite();
		if(group!=0){
			ArrayList<OButton> btnList = ((OCardBase)parent).btnList;
			for(int i=0; i<btnList.size(); i++){
				if(btnList.get(i).id!=id && btnList.get(i).group==group){
					//a view of another card unhilites the family on that card
					OButton sibling = cardView!=null ? btnList.get(i).forCard(cardView) : btnList.get(i);
					sibling.check_hilite = false;
					sibling.hilite = false;
					sibling.storeCardHilite();
					//its Swing part too: getHilite reads the part (HC J-2.3 probe D Click Hilite: the family's other button
					//read true after click at hilited Check)
					UiHost.get().setHiliteView402(this, in, sibling);
				}
			}
		}
		UiHost.get().setHiliteView592(this, in);
		((OCardBase)parent).changed = true;
	}
	/** The card a forCard view reads and writes the hilite of (null: the background's displayed card) */
	OCard cardView;

	/** bg btn X of card Y for a card Y that is not displayed: a copy without Swing parts whose hilite is card Y's own
	 *  (sharedHilite false). The displayed card, shared buttons and card buttons give this button itself. HC J-2.3 probe
	 *  E BgButtonsRemote: the hilite of bg btn "Flag" of cd "_1" is that card's, set leaves the other cards alone. */
	/** The background button a forCard view shows on another card (null: no view). Only the hilite is that card's own:
	 *  other properties set on the view go to this button (TUtil.SetProperty). */
	OButton viewOf;

	OButton forCard(OCard cd) {
		if(cd==null || card==null || !card.objectType.equals("background") || sharedHilite) return this;
		if(cardView==cd || (cardView==null && ((OBackground)card).viewCard==cd)) return this;
		OButton view;
		try{ view = (OButton)super.clone(); }
		catch(CloneNotSupportedException e){ return this; }
		view.cardView = cd;
		view.viewOf = viewOf!=null ? viewOf : this;
		view.btn = null; view.chkbox = null; view.radio = null; view.popup = null;
		OBgButtonData data = view.cardHiliteData(false);
		view.hilite = data!=null && data.check_hilite;
		view.check_hilite = view.hilite;
		return view;
	}

	//sharedHilite=falseのbgボタンは、ハイライトをカードごとに持つ(HyperCardと同じ)。
	//表示中カードのbgbtnListへ現在のhiliteを書き込む(無ければ作る)。
	//共有(sharedHilite=true)のボタンやカードボタンでは何もしない。
	void storeCardHilite() {
		OBgButtonData data = cardHiliteData(true);
		if(data==null) return;
		data.check_hilite = hilite;
		data.card.changed = true;
	}

	//表示中カード側のハイライトデータ。該当しなければnull。createなら無いとき作る
	OBgButtonData cardHiliteData(boolean create) {
		if(card==null || !card.objectType.equals("background") || sharedHilite) return null;
		OCard viewCard = cardView!=null ? cardView : ((OBackground)card).viewCard;
		if(viewCard==null || viewCard.bgbtnList==null) return null;
		for(int j=0; j<viewCard.bgbtnList.size(); j++){
			if(viewCard.bgbtnList.get(j).id == id) return viewCard.bgbtnList.get(j);
		}
		if(!create) return null;
		OBgButtonData data = new OBgButtonData(viewCard, id);
		data.btn = this;
		data.check_hilite = hilite;
		viewCard.bgbtnList.add(data);
		return data;
	}

	//カード表示の組み立て時に、そのカードのハイライト値を反映する
	//(OBackground.applyCardContentsから)。エントリが無いカードではオフ。
	void applyCardHilite(OBgButtonData data) {
		boolean value = data!=null && data.check_hilite;
		hilite = value;
		check_hilite = value;
	}

	public boolean getHilite() {
		Boolean shown = UiHost.get().buttonViewHilite(this); //the Swing part's (the desktop), when it has one
		if(shown!=null) return shown;
		return hilite;
	}
	public int getSelectedLine() {
		//a pop-up with no text keeps its number (HC J-2.3: line 4 of an empty pop-up)
		if(style==8 && popup!=null && !getText().isEmpty()){
			int index = UiHost.get().popupSelectedIndex(popup)+1;
			return index;
		}
		return selectedLine;
	}
	public void setSelectedLine(int line) {
		if(style==8 && popup!=null) UiHost.get().selectPopupLine(popup, line);
		if(style==8) selectedLine = line;
		((OCardBase)parent).changed = true;
	}
	public String getSelectedText() {
		if(style==8){
			int index = selectedLine-1;
			if(popup!=null){
				index = UiHost.get().popupSelectedIndex(popup);
			}
			String[] ary = getText().split("\n");
			if(index>=0 && index < ary.length){
				return ary[index];
			} else {
				return "";
			}
		}
		return "";
	}
	public void setShowName(boolean in) {
		showName = in;
		UiHost.get().setShowNameView529(this, in);
		((OCardBase)parent).changed = true;
	}
	//Swing部品の選択状態ではなく、記憶しているハイライト値。クリックでSwingが
	//先に反転させた後、autoHilite=falseのボタンを元へ戻すのに使う(GCheckBox.clicked)
	boolean storedHilite(){
		return hilite;
	}
	public boolean getAutoHilite(){
		/*if(btn != null){
			return btn.autoHilite;
		}*/
		return autoHilite;
	}
	public void setAutoHilite(boolean in){
		autoHilite = in;
		UiHost.get().setAutoHiliteView760(this, in);
		((OCardBase)parent).changed = true;
	}
	public boolean getIconAspect(){ return iconAspect; }
	public void setIconAspect(boolean value){
		iconAspect = value;
		((OCardBase)parent).changed = true;
		if(btn!=null) btn.repaint();
	}
	static Rectangle iconFitBounds(int iw,int ih,int w,int h,boolean preserve){
		if(!preserve || iw<=0 || ih<=0) return new Rectangle(0,0,w,h);
		double ratio=Math.min((double)w/iw,(double)h/ih);
		int fw=Math.max(1,(int)Math.round(iw*ratio)),fh=Math.max(1,(int)Math.round(ih*ratio));
		return new Rectangle((w-fw)/2,(h-fh)/2,fw,fh);
	}
	void paintScaledIcon(Graphics g,int w,int h){
		Rectangle r=iconFitBounds(imageForScale.getWidth(),imageForScale.getHeight(),w,h,iconAspect);
		g.drawImage(imageForScale,r.x,r.y,r.width,r.height,null);
	}
	public boolean getScaleIcon(){
		return scaleIcon;
	}
	public void setScaleIcon(boolean in){
		scaleIcon = in;
		if(in){
			if(iconURI!=null){
				setIconURI(iconURI);
			}else{
				setIcon(icon);
			}
		}
		else{
			imageForScale = null;
		}
		if(!HZ.lockedScreen) {
			Host.get().repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setTextFont(String in){
		textFont = in;
		JComponent component=getComponent();
		UiHost.get().setTextFontView569(this, in, component);
		if(!HZ.lockedScreen) {
			Host.get().repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setBlendMode(int in){
		blendMode = in;
		{
			if(iconURI!=null){
				setIconURI(iconURI);
			}else{
				setIcon(icon);
			}
		}
		if(!HZ.lockedScreen) {
			Host.get().repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setBlendLevel(int in) {
		blendLevel = in;
		if(!HZ.lockedScreen) {
			Host.get().repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}

	public void setColor(Color col) {
		color=col;
		UiHost.get().setColorView842(this, col);
	}
	public void setBgColor(Color col) {
		bgColor=col;
		UiHost.get().setBgColorView849(this, col);
	}
	/** The colour a hilited button is filled with; null goes back to HyperCard's inversion. */
	public void setHiliteColor(Color col) {
		hiliteColor=col;
		if(!HZ.lockedScreen) {
			Host.get().repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	/** Whether a hilited button is filled with its hiliteColor instead of being inverted (the push-button styles only). */
	boolean fillsHilite() {
		return hiliteColor!=null && style<=7;
	}
	
	public OButton(OCardBase cd, int btnId){
    	objectType="button";
		card = cd;
		parent = cd;
		scriptList = new ArrayList<String>();

		width=64; height=20;
		id=btnId;
	}
	

	@SuppressWarnings("unchecked")
	public XmlIn readXML(XmlIn reader) throws Exception {
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XmlIn.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("id")){ this.id = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("visible")){ this.setVisible(XMLRead.bool(reader));reader.next(); }
	            	else if(elm.equals("reserved5")){ this.reserved5 = reader.getElementText(); }
	            	else if(elm.equals("reserved4")){ this.reserved4 = reader.getElementText(); }
	            	else if(elm.equals("reserved3")){ this.reserved3 = reader.getElementText(); }
	            	else if(elm.equals("reserved2")){ this.reserved2 = reader.getElementText(); }
	            	else if(elm.equals("reserved1")){ this.reserved1 = reader.getElementText(); }
	            	else if(elm.equals("enabled")){ this.enabled = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("rect")){
	            		int right=0, bottom=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XmlIn.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("left")){ 
	    		            		this.left = Integer.valueOf(reader.getElementText());
	    		            		if(this.left>=32768) this.left -= 65536;
	    		            	}
	    		            	else if(elm2.equals("top")){ 
	    		            		this.top = Integer.valueOf(reader.getElementText()); 
	    		            		if(this.top>=32768) this.top -= 65536;
	    		            	}
	    		            	else if(elm2.equals("right")){ right = Integer.valueOf(reader.getElementText()); }
	    		            	else if(elm2.equals("bottom")){ bottom = Integer.valueOf(reader.getElementText()); }
	    		            	else System.out.println("Part_Type: " + reader.getLocalName());
	    	            	}
	    	            	else if (eventType2 == XmlIn.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("rect")){
	    		            		this.width = right - this.left;
	    		            		this.height = bottom - this.top;
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}
	            	else if(elm.equals("color") || elm.equals("bgColor") || elm.equals("hiliteColor")){
    	            	int red=0, green=0, blue=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XmlIn.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("red")){ red = Integer.valueOf(reader.getElementText());}
	    		            	else if(elm2.equals("green")){ green = Integer.valueOf(reader.getElementText());}
	    		            	else if(elm2.equals("blue")){ blue = Integer.valueOf(reader.getElementText()); }
	    		            	else System.out.println("Part_Type: " + reader.getLocalName());
	    	            	}
	    	            	else if (eventType2 == XmlIn.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("color")){
	    		            		this.color = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            	if(elm2.equals("bgColor")){
	    		            		this.bgColor = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            	if(elm2.equals("hiliteColor")){
	    		            		this.hiliteColor = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}
	            	else if(elm.equals("style")){
	            		String tmpstr = reader.getElementText();
						if(0==tmpstr.compareTo("standard")) this.style=0;
						if(0==tmpstr.compareTo("transparent")) this.style=1;
						if(0==tmpstr.compareTo("opaque")) this.style=2;
						if(0==tmpstr.compareTo("rectangle")) this.style=3;
						if(0==tmpstr.compareTo("shadow")) this.style=4;
						if(0==tmpstr.compareTo("roundrect")) this.style=5;
						if(0==tmpstr.compareTo("default")) this.style=6;
						if(0==tmpstr.compareTo("oval")) this.style=7;
						if(0==tmpstr.compareTo("popup")) this.style=8;
						if(0==tmpstr.compareTo("checkbox")) this.style=9;
						if(0==tmpstr.compareTo("radio")) this.style=10;
	            	}
	            	else if(elm.equals("showName")){ this.showName = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("highlight")){ this.hilite = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("autoHighlight")){ this.autoHilite = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("sharedHighlight")){ this.sharedHilite = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("family")){ this.group = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("titleWidth")){ this.titleWidth = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textAlign")){
	            		String alignStr = reader.getElementText();
	            		if(alignStr.equals("left")) this.textAlign = 0;
	            		else if(alignStr.equals("center")) this.textAlign = 1;
	            		else if(alignStr.equals("right")) this.textAlign = 2;
	            	}
	            	else if(elm.equals("textFontID")){
	            		int textFontID = Integer.valueOf(reader.getElementText());
            			OStack stack = ((OCardBase)this.parent).stack;
            			for(int i=0; i<stack.fontList.size();i++){
            				if(stack.fontList.get(i).id ==textFontID){
            					textFont = stack.fontList.get(i).name;
            					break;
            				}
            			}
	            	}
	            	else if(elm.equals("textSize")){ this.textSize = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("icon")){ this.icon = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("iconURI")){
	            		//空要素(<iconURI/>: 通常のIDアイコンかアイコンなし)は null のままにする。
	            		//以前は空の URI を作っていたため、まだ表示していないカードのボタンの the icon が
		            // (iconURI優先で)空文字になり、別カードのBGMボタンのアイコンを
		            // 読めずBGMを鳴らさなかった(表示済みのカードは
	            		//setIcon が iconURI を null に戻すので、インポート直後は正しく読めていた)。
	            		String uri = reader.getElementText();
	            		this.iconURI = uri.trim().isEmpty() ? null : new URI(uri);
	            	}
	            	else if(elm.equals("selectedLines")){ 
	            		//this.selectedLine = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("integer")){ 
	            		this.selectedLine = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("textStyle")){ 
	            		String tmpstr=reader.getElementText();
						//this.textStyle=0;
						if(tmpstr.contains("plain")) this.textStyle=0;
						if(tmpstr.contains("bold")) this.textStyle+=1;
						if(tmpstr.contains("italic")) this.textStyle+=2;
						if(tmpstr.contains("underline")) this.textStyle+=4;
						if(tmpstr.contains("outline")) this.textStyle+=8;
						if(tmpstr.contains("shadow")) this.textStyle+=16;
						if(tmpstr.contains("condensed")) this.textStyle+=32;
						if(tmpstr.contains("extend")) this.textStyle+=64;
						if(tmpstr.contains("group")) this.textStyle+=128;
	            	}
	            	else if(elm.equals("reserved25")){ reserved25 = reader.getElementText(); }
	            	else if(elm.equals("iconAspect")){ this.iconAspect = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("scaleIcon") || elm.equals("scalingIcon")){ this.scaleIcon = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("blendingMode")){ 
	            		String tmpstr=reader.getElementText();
						if(tmpstr.equals("copy")) this.blendMode=0;
						if(tmpstr.equals("blend")) this.blendMode=1;
						if(tmpstr.equals("add")) this.blendMode=2;
						if(tmpstr.equals("subtract")) this.blendMode=3;
						if(tmpstr.equals("multiply")) this.blendMode=4;
						if(tmpstr.equals("screen")) this.blendMode=5;
						if(tmpstr.equals("darken")) this.blendMode=6;
						if(tmpstr.equals("lighten")) this.blendMode=7;
						if(tmpstr.equals("difference")) this.blendMode=8;
						if(tmpstr.equals("hue")) this.blendMode=9;
						if(tmpstr.equals("color")) this.blendMode=10;
						if(tmpstr.equals("saturation")) this.blendMode=11;
						if(tmpstr.equals("luminosity")) this.blendMode=12;
	            	}
	            	else if(elm.equals("blendingLevel")){ this.blendLevel = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("name")){ 
	            		name = reader.getElementText();
	            	}
				else if(elm.equals("panel")){ panelId = XMLRead.panelId(reader.getElementText()); }
	            	else if(elm.equals("osaScript")) osaScript = UiHost.get().readOsaScript(reader);
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		//末尾の空行を保つため、limit引数に-1を指定する
	            		String[] scriptAry = scrStr.split("\n", -1);
	            		for(int i=0; i<scriptAry.length; i++)
	            		{
	            			scriptList.add(scriptAry[i]);
	            		}
	            		stringList = new ArrayList[scriptList.size()];
	            		typeList = new ArrayList[scriptList.size()];
	            	}
	            	else if(elm.equals("true")); //dummy 
	            	else if(elm.equals("false")); //dummy 
	            	else if(elm.equals("part")); //dummy for clipboard
	            	else if(elm.equals("type")); //dummy for clipboard
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XmlIn.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("part")){
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    XMLRead.readError(ex);
			    break;
	        }
	    }
	    return reader;
	}

	
	public void writeXML(XmlOut writer) throws XmlException {
        writer.writeStartElement("part");
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("id");
        writer.writeCharacters(Integer.toString(id));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("type");
        writer.writeCharacters("button");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("visible");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(getVisible())+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("enabled");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(enabled)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("rect");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("left");
            writer.writeCharacters(Integer.toString(left));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("top");
            writer.writeCharacters(Integer.toString(top));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("right");
            writer.writeCharacters(Integer.toString(left+width));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("bottom");
            writer.writeCharacters(Integer.toString(top+height));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("style");
        switch(style){
        case 0: writer.writeCharacters("standard");break;
        case 1: writer.writeCharacters("transparent");break;
        case 2: writer.writeCharacters("opaque");break;
        case 3: writer.writeCharacters("rectangle");break;
        case 4: writer.writeCharacters("shadow");break;
        case 5: writer.writeCharacters("roundrect");break;
        case 6: writer.writeCharacters("default");break;
        case 7: writer.writeCharacters("oval");break;
        case 8: writer.writeCharacters("popup");break;
        case 9: writer.writeCharacters("checkbox");break;
        case 10: writer.writeCharacters("radio");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("showName");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(showName)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("highlight");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(hilite)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(parent.getClass()==OBackground.class){
            writer.writeStartElement("autoHighlight");
            writer.writeCharacters(" ");
            writer.writeEmptyElement(Boolean.toString(autoHilite)+" ");
            writer.writeCharacters(" ");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
            
	        writer.writeStartElement("sharedHighlight");
	        writer.writeCharacters(" ");
	        writer.writeEmptyElement(Boolean.toString(sharedHilite)+" ");
	        writer.writeCharacters(" ");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");

	        writer.writeStartElement("family");
	        writer.writeCharacters(Integer.toString(group));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }
        else{
            /*writer.writeStartElement("reserved25");
            writer.writeCharacters(" 0 ");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");*/

	        writer.writeStartElement("family");
	        writer.writeCharacters(Integer.toString(group));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");

	        writer.writeStartElement("autoHighlight");
	        writer.writeCharacters(" ");
	        writer.writeEmptyElement(Boolean.toString(autoHilite)+" ");
	        writer.writeCharacters(" ");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

        writer.writeStartElement("titleWidth");
        writer.writeCharacters(Integer.toString(titleWidth));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(style==8){
        	if(selectedLine>0){
		        writer.writeStartElement("selectedLines");
		        writer.writeCharacters("\n\t\t\t\t");
		        {
			        writer.writeStartElement("integer");
			        writer.writeCharacters(Integer.toString(selectedLine));
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t\t");
		        }
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
        	}
        }
        else{
	        writer.writeStartElement("icon");
	        writer.writeCharacters(Integer.toString(icon));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
	        
	        writer.writeStartElement("iconURI");
	        writer.writeCharacters(iconURI!=null?iconURI.toString():"");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

        writer.writeStartElement("textAlign");
        switch(textAlign){
        case 0: writer.writeCharacters("left");break;
        case 1: writer.writeCharacters("center");break;
        case 2: writer.writeCharacters("right");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        int textFontID = 0;
        for(int i=0; i<((OCardBase)parent).stack.fontList.size(); i++){
        	OStack.fontClass fontinfo = ((OCardBase)parent).stack.fontList.get(i);
        	if(fontinfo.name.equals(textFont)){
        		textFontID = fontinfo.id;
        		break;
        	}
        }
        writer.writeStartElement("textFontID");
        writer.writeCharacters(Integer.toString(textFontID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("textSize");
        writer.writeCharacters(Integer.toString(textSize));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(textStyle==0){
            writer.writeStartElement("textStyle");
            writer.writeCharacters("plain");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
        }
        else{
        	if((textStyle&1)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("bold");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&2)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("italic");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&4)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("underline");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&8)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("outline");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&16)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("shadow");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&32)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("condensed");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&64)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("extend");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&128)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("group");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        }

        writer.writeStartElement("color");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("red");
            writer.writeCharacters(Integer.toString(color.getRed()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("green");
            writer.writeCharacters(Integer.toString(color.getGreen()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("blue");
            writer.writeCharacters(Integer.toString(color.getBlue()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("bgColor");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("red");
            writer.writeCharacters(Integer.toString(bgColor.getRed()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("green");
            writer.writeCharacters(Integer.toString(bgColor.getGreen()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("blue");
            writer.writeCharacters(Integer.toString(bgColor.getBlue()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        if(hiliteColor!=null){
	        writer.writeStartElement("hiliteColor");
	        writer.writeCharacters("\n\t\t\t\t");
	        writer.writeStartElement("red");
	        writer.writeCharacters(Integer.toString(hiliteColor.getRed()*256));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t\t");
	        writer.writeStartElement("green");
	        writer.writeCharacters(Integer.toString(hiliteColor.getGreen()*256));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t\t");
	        writer.writeStartElement("blue");
	        writer.writeCharacters(Integer.toString(hiliteColor.getBlue()*256));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t\t");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }
        
        writer.writeStartElement("iconAspect");
        writer.writeEmptyElement(Boolean.toString(iconAspect));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        writer.writeStartElement("scaleIcon");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(scaleIcon)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("blendingMode");
        writer.writeCharacters(" ");
        switch(blendMode){
        case 0: writer.writeCharacters("copy"); break;
        case 1: writer.writeCharacters("blend"); break;
        case 2: writer.writeCharacters("add"); break;
        case 3: writer.writeCharacters("subtract"); break;
        case 4: writer.writeCharacters("multiply"); break;
        case 5: writer.writeCharacters("screen"); break;
        case 6: writer.writeCharacters("darken"); break;
        case 7: writer.writeCharacters("lighten"); break;
        case 8: writer.writeCharacters("difference"); break;
        case 9: writer.writeCharacters("hue"); break;
        case 10: writer.writeCharacters("color"); break;
        case 11: writer.writeCharacters("saturation"); break;
        case 12: writer.writeCharacters("luminosity"); break;
        }
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("blendingLevel");
        writer.writeCharacters(Integer.toString(blendLevel));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("name");
        writer.writeCharacters(name);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
		if(panelId!=0){
			writer.writeStartElement("panel");
			writer.writeCharacters(Integer.toString(panelId));
			writer.writeEndElement();
			writer.writeCharacters("\n\t\t\t");
		}
        
        writer.writeStartElement("script");
        for(int i=0; i<scriptList.size(); i++){
        	writer.writeCharacters(scriptList.get(i));
        	if(i<scriptList.size()-1){
        		writer.writeCharacters("\n");
        	}
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        if(osaScript!=null) UiHost.get().writeOsaScript(osaScript, writer, "\n\t\t"); //HyperCard 2.2+ compiled AppleScript, kept as is (OsaScript)

        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
	}
	

	//HCのスタックを変換
	@SuppressWarnings("unchecked")
	public boolean readButtonBlock(DataInputStream dis, int partSize){
		//System.out.println("====readButtonBlock====");
		//ブロックのデータを順次読み込み
		int flags = HCData.readCode(dis, 1);
		//System.out.println("flags:"+flags);
		setVisible(((flags>>7)&0x01)==0);
		//dontWrap = !( ((flags>>5)&0x01)!=0);
		//dontSearch = ((flags>>4)&0x01)!=0;
		//sharedText = ((flags>>3)&0x01)!=0;
		//fixedLineHeight = ! (((flags>>2)&0x01)!=0);
		//autoTab = ((flags>>1)&0x01)!=0;
		enabled = ! (((flags>>0)&0x01)!=0);
		top = HCData.readCode(dis, 2);
		//System.out.println("top:"+top);
		left = HCData.readCode(dis, 2);
		//System.out.println("left:"+left);
		int bottom = HCData.readCode(dis, 2);
		//System.out.println("bottom:"+bottom);
		height = bottom - top;
		int right = HCData.readCode(dis, 2);
		//System.out.println("right:"+right);
		width = right - left;
		int flags2 = HCData.readCode(dis, 1);
		showName = ((flags2>>7)&0x01)!=0;
		hilite = ((flags2>>6)&0x01)!=0;
		autoHilite = ((flags2>>5)&0x01)!=0;
		sharedHilite = !(((flags2>>4)&0x01)!=0);
		group = (flags2)&0x0F;
		int style = HCData.readCode(dis, 1);
		//0標準 1透明 2不透明 3長方形 4シャドウ 5丸みのある長方形 6省略時設定 7楕円 8ポップアップ 9チェックボックス 10ラジオ
		switch(style){
		case 0: this.style = 1; break;//transparent
		case 1: this.style = 2; break;//opaque
		case 2: this.style = 3; break;//rectangle
		case 3: this.style = 5; break;//roundRect
		case 4: this.style = 4; break;//shadow
		case 5: this.style = 9; break;//checkBox
		case 6: this.style = 10; break;//radioButton
		//case 7: this.style = 0; break;//scrolling
		case 8: this.style = 0; break;//standard
		case 9: this.style = 6; break;//default
		case 10: this.style = 7; break;//oval
		case 11: this.style = 8; break;//popup
		}
		titleWidth = HCData.readCode(dis, 2);
		//System.out.println("titleWidth:"+titleWidth);
		if(this.style == 8){
			selectedLine = HCData.readCode(dis, 2);
			//System.out.println("selectedLine:"+selectedLine);
		}
		else{
			icon = HCData.readCode(dis, 2);
			//System.out.println("icon:"+icon);
		}
		int inTextAlign = HCData.readCode(dis, 2);
		switch(inTextAlign){
		case 0: textAlign = 0; break;
		case 1: textAlign = 1; break;
		case -1: textAlign = 2; break;
		}
		//System.out.println("inTextAlign:"+inTextAlign);
		int textFontID = HCData.readCode(dis, 2);
		//System.out.println("textFontID:"+textFontID);
		textSize = HCData.readCode(dis, 2);
		//System.out.println("textSize:"+textSize);
		textStyle = HCData.readCode(dis, 1);
		//System.out.println("textStyle:"+textStyle);
		/*int filler =*/ HCData.readCode(dis, 1);
		//System.out.println("filler:"+filler);
		textHeight = HCData.readCode(dis, 2);
		//System.out.println("textHeight:"+textHeight);
		resultStr nameResult = HCData.readTextToZero(dis, partSize - 30);
		name = nameResult.str;
		//HCStackDebug.debuginfo("name:"+name);
		//System.out.println("name:"+name);
		/*int filler2 =*/// HCData.readCode(dis, 1);
		//System.out.println("filler2:"+filler2);
		//HyperCard 2.2+: an AppleScript (OSA) script is source text + NUL + a compiled 'WOSA' record after the name's padding byte
		resultStr scriptResult = HCData.readOsaScript(dis, partSize - 30 - nameResult.length_in_src, 1);
		if(scriptResult!=null) osaScript = scriptResult.osa;
		else scriptResult = HCData.readPartScript(dis, partSize - 30 - nameResult.length_in_src);
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);

		//フォント名をテーブルから検索
		OStack stack = ((OCardBase)this.parent).stack;
		for(int i=0; i<stack.fontList.size();i++){
			if(stack.fontList.get(i).id ==textFontID){
				textFont = stack.fontList.get(i).name;
				break;
			}
		}

		int remainLength = partSize - (30+(nameResult.length_in_src)+(scriptResult.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		//HCStackDebug.debuginfo("remainLength:"+remainLength);
		if(remainLength<0 || remainLength > 1000){
			//System.out.println("!");
		}
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
			//HCStackDebug.debuginfo("padding:"+padding);
		}
		/*if((nameResult.length_in_src+1+scriptResult.length_in_src+1)%2 != 0){
			String padding = HCData.readStr(dis, 1);
			System.out.println("padding:"+padding);
		}*/

		//スクリプト
		//末尾の空行を保つため、limit引数に-1を指定する(OCard.javaの
		//readCardBlock参照)
		String[] scriptAry = scriptStr.split("\n", -1);
		for(int i=0; i<scriptAry.length; i++)
		{
			scriptList.add(scriptAry[i]);
		}
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		
		return true;
	}
	
	
	static void buildOButton(OButton obtn)
	{ UiHost.get().buildOButton(obtn); }
    
    void addListener(){
    	if(card==null) return;
		//二重登録を防ぐ。新規作成時はbuildOButtonが、ブラウズツールへ戻ったときは
		//GUI.addAllListenerが、それぞれこのメソッドを呼ぶため、外さずに追加すると
		//作ったばかりのボタンだけリスナーが2つ付き、mouseUpが2回実行されてしまう
		//(カードを移動して戻るとボタンが作り直されて1つに戻るので気づきにくい)。
		removeListener();
		//ツール中に作り直したボタン(カード移動・ボタン情報・Option-ドラッグの複製など)は、ブラウズツールへ戻るときに付け直す
		if(GUI.editingTool()) return;
		if(btn!=null) {
			btn.addMouseListener(card.stack.GUI_btn);
			btn.addMouseMotionListener(card.stack.GUI_btn);
			btn.addActionListener(card.stack.GUI_btn);
		}
		else if(popup!=null) {
			popup.addPopupMenuListener(card.stack.GUI_popup);
			popup.addActionListener(card.stack.GUI_popup);
		}
		else if(chkbox!=null) {
			chkbox.addMouseListener(card.stack.GUI_check);
			chkbox.addMouseMotionListener(card.stack.GUI_check);
			chkbox.addActionListener(card.stack.GUI_check);
		}
		else if(radio!=null) {
			radio.addMouseListener(card.stack.GUI_radio);
			radio.addMouseMotionListener(card.stack.GUI_radio);
			radio.addActionListener(card.stack.GUI_radio);
		}
	}
    
    void removeListener(){
		if(card==null) return;
		if(btn!=null) {
			btn.removeMouseListener(card.stack.GUI_btn);
			btn.removeMouseMotionListener(card.stack.GUI_btn);
			btn.removeActionListener(card.stack.GUI_btn);
		}
		else if(popup!=null) {
			popup.removePopupMenuListener(card.stack.GUI_popup);
			popup.removeActionListener(card.stack.GUI_popup);
		}
		else if(chkbox!=null) {
			chkbox.removeMouseListener(card.stack.GUI_check);
			chkbox.removeMouseMotionListener(card.stack.GUI_check);
			chkbox.removeActionListener(card.stack.GUI_check);
		}
		else if(radio!=null) {
			radio.removeMouseListener(card.stack.GUI_radio);
			radio.removeMouseMotionListener(card.stack.GUI_radio);
			radio.removeActionListener(card.stack.GUI_radio);
		}
	}
    
    void addListener(MouseListener listener){
		if(getComponent()!=null){
			getComponent().addMouseListener(listener);
		}
    }
    void removeListener(MouseListener listener){
		if(getComponent()!=null){
			getComponent().removeMouseListener(listener);
		}
    }
    void addMotionListener(MouseMotionListener listener){
		if(getComponent()!=null){
			getComponent().addMouseMotionListener(listener);
		}
    }
    void removeMotionListener(MouseMotionListener listener){
		if(getComponent()!=null){
			getComponent().removeMouseMotionListener(listener);
		}
    }
}

class btnOutlineListen implements MouseListener {

    public void mouseClicked(MouseEvent e) {
    	//getClass()==MyButton.classだと、MyButtonを継承しているRoundedCornerButton
    	//(角丸)・RectButton(矩形/影付き/不透明/透明)・RoundButton(楕円)が
    	//どれも一致せず、標準スタイル(style 0・6)のボタンでしかスクリプトエディタが
    	//開かなかった。バックグラウンドボタンは透明・矩形で作られることが多いため、
    	//「バックグラウンドボタンだけピークが効かない」ように見えていた。
    	//キーを離したことがスクリプトエディタへ行って key[] が押されたまま残っていても、普通のクリックでは開かない
    	if(!GUI.isPeekClick(e)){
    		GUI.endPeek();
    		return;
    	}
    	if(e.getSource() instanceof MyButton){
    		ScriptEditor.openScriptEditor(HZ.stack().pcard, ((MyButton)e.getSource()).btnData);
    	}
    	else if(e.getSource().getClass()==MyPopup.class){
    		ScriptEditor.openScriptEditor(HZ.stack().pcard, ((MyPopup)e.getSource()).btnData);
    	}
    	else if(e.getSource().getClass()==MyRadio.class){
    		ScriptEditor.openScriptEditor(HZ.stack().pcard, ((MyRadio)e.getSource()).btnData);
    	}
    	else if(e.getSource().getClass()==MyCheck.class){
    		ScriptEditor.openScriptEditor(HZ.stack().pcard, ((MyCheck)e.getSource()).btnData);
    	}
		GUI.endPeek();
    }
	
    public void mousePressed(MouseEvent e) {
    }
    public void mouseReleased(MouseEvent e) {
    }
	public void mouseEntered(MouseEvent arg0) {
	}
	public void mouseExited(MouseEvent arg0) {
	}
}

/**
 * Draws a button name where HyperCard draws it (HyperCard J-2.3's button drawing, read from its code and measured in
 * the 68K VM on Sept 20 2026), which differs from Swing's label layout in three ways:
 * <ul>
 * <li>The baseline is (top+bottom)/2 + max(4, descent+leading) of the name's font: it hangs from the button's centre and
 *     never uses the ascent. Swing centres ascent+descent+leading and puts the baseline at the ascent, which with a font
 *     such as Hiragino (Osaka's stand-in, 6px of leading at 12pt) sat the name 3px too high. A check box or radio button
 *     name is at centre+4 whatever the font, 19px in (after the 12px mark).</li>
 * <li>A left-aligned name starts 8px in, a right-aligned one ends 8px before the right edge; a centred one is centred on
 *     (left+right)/2 ((left+right-1)/2 for the standard and default styles). Swing used the border insets.</li>
 * <li>Under an icon the name is drawn in 9-point Geneva (Osaka in the Japanese HyperCard) regardless of the button's
 *     font, with its baseline 10px below the icon, and the icon is centred 6px above the middle (see
 *     {@link MyButton#getIconTextGap}).</li>
 * <li>The name is drawn in full and the button's rectangle clips it (a centred name that is too wide simply loses both
 *     ends); SwingUtilities.layoutCompoundLabel replaces the tail with "..." as soon as it is wider than the room, so
 *     names that were readable in HyperCard came out as "Button na...".</li>
 * </ul>
 * So whenever the button shows a name, Swing is given a single space in its place (a space, not "", so the icon keeps
 * its place above the text) and the whole name is drawn here, clipped by the component bounds.
 */
final class FullButtonName {
	private FullButtonName(){}

	/** Where the name goes: the font to draw it with, the x of its first glyph and its baseline (physical pixels). */
	static final class Placement {
		final Font font; final int x, baseline;
		/** the name of an icon: HyperCard draws it in srcCopy, on a white box (readable on a black card: ChemStax's "About ChemStax") */
		boolean iconName;
		/** bold in a family without a bold face: QuickDraw's smear (see MyTextArea.needsSmear) */
		boolean smear;
		Placement(Font font, int x, int baseline){ this.font = font; this.x = x; this.baseline = baseline; }
	}

	/** HyperCard's place for the name of {@code data} in component {@code b}, or null when there is no name to draw. */
	static Placement place(AbstractButton b, OButton data, String text, Graphics g){
		if(text==null || text.isEmpty()) return null;
		double scale = Host.get().scale();
		int w = b.getWidth(), h = b.getHeight();
		int centre = (int)Math.round((Host.get().toLogicalLength(h)/2)*scale); //(top+bottom)/2 in HyperCard's coordinates
		Font font = b.getFont();
		Icon icon = b.getIcon();
		boolean toggle = data.style==9 || data.style==10;
		int baseline;
		if(icon!=null && !toggle){
			//the icon's place is Swing's (a space stands in for the name, see MyButton.getIconTextGap); the name hangs 10px under it
			Insets in = b.getInsets();
			Rectangle viewR = new Rectangle(in.left, in.top, w-in.left-in.right, h-in.top-in.bottom), iconR = new Rectangle(), textR = new Rectangle();
			SwingUtilities.layoutCompoundLabel(b, b.getFontMetrics(font), " ", icon, b.getVerticalAlignment(), b.getHorizontalAlignment(),
					b.getVerticalTextPosition(), b.getHorizontalTextPosition(), viewR, iconR, textR, b.getIconTextGap());
			font = OObject.stackFont(iconNameFont(data), Font.PLAIN, 9*scale);
			baseline = iconR.y+iconR.height+(int)Math.round(10*scale);
		}else if(toggle){
			baseline = centre+(int)Math.round(4*scale);
		}else{
			baseline = centre+(int)Math.round(Math.max(4, drop(data.textFont, (int)Math.round(font.getSize2D()/scale), g.getFontMetrics(font)))*scale);
		}
		FontMetrics fm = g.getFontMetrics(font);
		boolean smear = !(icon!=null && !toggle) && MyTextArea.needsSmear(font, data.textStyle);
		int tw = MyTextArea.runWidth(fm, text, smear), x;
		if(!text.equals(ClassicGlyphs.map(data.textFont, data.name)) && !toggle){
			//a translated name (StackTranslation) longer than the button: a smaller size, down to half, rather than cut off
			int room = w-(int)Math.round((icon!=null ? 2 : 8)*scale)*2;
			if(tw>room && room>0){
				float size = Math.max(font.getSize2D()/2, font.getSize2D()*room/tw);
				font = OObject.stackFont(data.textFont, data.textStyle&0x03, (double)size);
				fm = g.getFontMetrics(font);
				tw = MyTextArea.runWidth(fm, text, smear);
			}
		}
		if(toggle){
			x = (int)Math.round((ClassicToggleIcon.LEFT_INSET+ClassicToggleIcon.SIZE+ClassicToggleIcon.TEXT_GAP)*scale);
		}else{
			switch(data.textAlign){
				case 0: x = (int)Math.round(8*scale); break;
				case 2: x = w-(int)Math.round(8*scale)-tw; break;
				default: x = (int)Math.round(((Host.get().toLogicalLength(w)-(data.style==0||data.style==6 ? 1 : 0))/2)*scale)-tw/2; break;
			}
		}
		Placement placement = new Placement(font, x, baseline);
		placement.iconName = icon!=null && !toggle;
		placement.smear = smear;
		return placement;
	}

	/** The 9-point font HyperCard names an icon button with: Geneva, or Osaka for a stack from the Japanese HyperCard. */
	private static String iconNameFont(OButton data){
		OStack stack = data.card!=null ? data.card.stack : null;
		return stack!=null && stack.legacyTextEncoding==StackTextEncoding.JAPANESE ? "Osaka" : "Geneva";
	}

	/** Classic Osaka's descent+leading by point size (its suitcase's strikes, which HyperCard measured); other sizes are interpolated. */

	/**
	 * descent+leading of the classic font a name is drawn with. The host fonts standing in for Chicago, Geneva or Monaco
	 * have the classic proportions, so their own metrics serve; Hiragino, standing in for Osaka, carries a leading the
	 * bitmap Osaka never had (6px at 12pt), so Osaka uses the classic values.
	 */
	static int drop(String fontName, int size, FontMetrics fm){
		if(fontName!=null && fontName.equalsIgnoreCase("Osaka")) return OField.osakaDrop(size);
		return fm.getDescent()+Math.max(0, fm.getLeading());
	}

	/** Draws the name at its placement, with the text hints Swing's own label drawing uses. */
	static void paint(AbstractButton b, Graphics g, String text, Placement p, String uiPrefix){
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			Object hints = b.getToolkit().getDesktopProperty("awt.font.desktophints");
			if(hints instanceof Map) g2.setRenderingHints((Map<?,?>)hints);
			g2.setFont(p.font);
			if(p.iconName){
				//measured in HyperCard J-2.3 (9-point name): white from 2px left of the pen to 2px past the text, ascent 10 above
				//the baseline to descent 2 below it
				double scale = Host.get().scale();
				int tw = g2.getFontMetrics().stringWidth(text);
				//white; a hilited button swaps its colours, so the box of a hilited name is black under white text
				boolean hilited = b instanceof MyButton && ((MyButton)b).getHilite();
				g2.setColor(hilited ? b.getBackground() : Color.WHITE);
				g2.fillRect(p.x-(int)Math.round(2*scale), p.baseline-(int)Math.round(10*scale), tw+(int)Math.round(4*scale), (int)Math.round(12*scale));
			}
			Color disabled = UIManager.getColor(uiPrefix+".disabledText");
			//a dimmed button greys its name with the rest (MyButton.dimDisabled)
			boolean dimmed = b instanceof MyButton && ((MyButton)b).dimsDisabled();
			g2.setColor(b.isEnabled() || dimmed ? b.getForeground() : disabled!=null ? disabled : Color.GRAY);
			if(p.smear){
				FontMetrics fm = g2.getFontMetrics();
				int x = p.x;
				for(int i=0; i<text.length(); i++){
					String c = text.substring(i, i+1);
					int cw = fm.charWidth(text.charAt(i));
					ClassicGlyphs.drawString(g2, c, x, p.baseline, Graphics::drawString);
					if(cw>0){ ClassicGlyphs.drawString(g2, c, x+1, p.baseline, Graphics::drawString); x += cw+1; }
				}
			}
			else ClassicGlyphs.drawString(g2, text, p.x, p.baseline, Graphics::drawString);
		}finally{ g2.dispose(); }
	}
}

class MyButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	boolean autoHilite=true;
	boolean hilite=false;
	OButton btnData=null;
	
	//Swingの標準描画中は名前を空白1文字に見せ、名前はFullButtonNameで自前描画する(下のpaintSwingButton)。
	private boolean hidingName=false;

	public MyButton(OButton btn, String text){
		super(text);
		btnData=btn;
		// A transparent button must be composited with the components behind it
		// every time it moves.  Its own Swing double buffer otherwise retains
		// pixels from the old position, leaving icon trails or erasing custom-font
		// fields until the whole card is repainted.
		this.setDoubleBuffered(HZ.useDoubleBuffer && btn.style != 1);
	}

	@Override
	public void paint(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		if(btnData!=null) btnData.findMissingIcon();
		super.paint(g);
		if(dimsDisabled() && paintsOnCurrentCard()) dimDisabled(g);
	}

	/**
	 * A disabled button in HyperCard J-2.3 (measured in the 68K VM): once the button is drawn, the pixels of a 50% grey inside
	 * its frame are cleared to white (patBic gray). A hilited one turns from black to grey, its name and icon are dimmed, and
	 * a transparent or oval one greys the card picture under it; the frame stays. The grey is aligned to the card, (x+y) even
	 * cleared. FROMINE VR LIKA's message bar is a disabled hilited transparent button under an enabled hilited one: the card
	 * shows through with grey hatching (the two inversions cancelled out here before). The standard and default styles are
	 * drawn by the look-and-feel and keep its own disabled look.
	 */
	boolean dimsDisabled(){
		return btnData!=null && !isEnabled() && btnData.style>=1 && btnData.style<=7 && btnData.style!=6;
	}
	private boolean paintsOnCurrentCard(){
		if(!isVisible() || PCARD.pc.bit != 1) return false;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return false;
		OCard cd = HZ.stack()==null ? null : HZ.stack().curCard;
		return cd!=null && (btnData.card==cd || btnData.card==cd.bg);
	}
	/** 2x2 tile: white where the grey is cleared, clear elsewhere */
	private static final BufferedImage DIM_TILE = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
	static{ DIM_TILE.setRGB(0, 0, 0xffffffff); DIM_TILE.setRGB(1, 1, 0xffffffff); }
	void dimDisabled(Graphics g){
		int w = getWidth(), h = getHeight();
		Shape inside;
		switch(btnData.style){
		case 3: inside = new Rectangle(1, 1, w-2, h-2); break; //rectangle: inside the frame
		case 4: inside = new Rectangle(1, 1, w-3, h-3); break; //shadow: inside the frame, not the shadow
		case 5: inside = new RoundRectangle2D.Float(1, 1, w-2, h-2, 14, 14); break;
		case 7: inside = new Ellipse2D.Float(0, 0, w-1, h-1); break; //the oval that a hilite inverts
		default: inside = new Rectangle(0, 0, w, h); //transparent, opaque
		}
		double s = Host.get().scale();
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setPaintMode();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			double ox = ((btnData.left+btnData.top)&1)!=0 ? s : 0;
			g2.setPaint(new java.awt.TexturePaint(DIM_TILE, new Rectangle2D.Double(ox, 0, 2*s, 2*s)));
			g2.fill(inside);
		}finally{ g2.dispose(); }
	}
	//the dimming greys the icon itself (Swing's disabled icon would be dimmed twice)
	@Override
	public Icon getDisabledIcon(){
		return dimsDisabled() && getIcon()!=null ? getIcon() : super.getDisabledIcon();
	}
	@Override
	public String getText(){
		return hidingName ? " " : super.getText();
	}
	//JButton標準の描画。名前はSwingではなくFullButtonNameがHyperCardの位置に描く
	//(中央+max(4,descent+leading)のベースライン、収まらなくても「...」にせずボタンの矩形で切り取る)。
	private void paintSwingButton(Graphics g){
		String name = StackTranslation.buttonName(btnData, super.getText()); //表示だけ翻訳(StackTranslation)。名前そのものは変えない
		//outlined and shadowed names are drawn by RoundedCornerButton itself; a name set again later by a script (Fading 1.2's
		//"Fade" button) was drawn a second time, plain and elsewhere
		//an icon button's name is HyperCard's plain 9-point name under the icon whatever its style (World Religions QuizBase's
		//bold shadow 24-point "Quiz" icon button, HC J-2.3)
		boolean ownStyledName = this instanceof RoundedCornerButton && (btnData.textStyle&(8|16))!=0 && getIcon()==null;
		FullButtonName.Placement p = btnData.showName && !ownStyledName ? FullButtonName.place(this, btnData, name, g) : null;
		if((btnData.style==0 || btnData.style==6) && !isEnabled()) paintDisabledBackdrop(g);
		if((btnData.style==0 || btnData.style==6) && btnData.fillsHilite() && getHilite()){
			paintTintedBezel(g);
			if(p!=null) FullButtonName.paint(this, g, name, p, "Button");
			return;
		}
		if(p==null){
			hidingName = ownStyledName;
			try{ super.paintComponent(g); } finally{ hidingName = false; }
			return;
		}
		hidingName = true;
		try{ super.paintComponent(g); } finally{ hidingName = false; }
		FullButtonName.paint(this, g, name, p, "Button");
	}
	/**
	 * A disabled standard or default button is opaque white under the look-and-feel's bezel, as HyperCard J-2.3 draws it (a
	 * white rounded rectangle with a grey name). Aqua's disabled bezel is translucent: on a dark card it was a grey block
	 * whose grey name could not be seen (Trash2's 縄張モード button on its black command panel).
	 */
	private void paintDisabledBackdrop(Graphics g){
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(Color.WHITE);
			int w = getWidth(), h = getHeight(), arc = Math.min(h, 8);
			g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w-1f, h-1f, arc, arc));
		}finally{ g2.dispose(); }
	}
	/**
	 * A hilited standard or default button with a hiliteColor: the look-and-feel's bezel (drawn without the name) in that
	 * colour, its lightest part the colour itself and darker parts (the edge) darker in proportion.
	 */
	private void paintTintedBezel(Graphics g){
		java.awt.geom.AffineTransform tx = ((Graphics2D)g).getTransform();
		double sx = Math.max(1, Math.abs(tx.getScaleX())), sy = Math.max(1, Math.abs(tx.getScaleY()));
		int w = getWidth(), h = getHeight(), iw = (int)Math.ceil(w*sx), ih = (int)Math.ceil(h*sy);
		if(iw<=0 || ih<=0) return;
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D ig = img.createGraphics();
		try{
			ig.scale(sx, sy);
			hidingName = true;
			try{ super.paintComponent(ig); } finally{ hidingName = false; }
		}finally{ ig.dispose(); }
		int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
		int max = 1;
		for(int v : px) if((v>>>24)>=128) max = Math.max(max, luminance(v));
		Color c = btnData.hiliteColor;
		for(int i=0; i<px.length; i++){
			int a = px[i]>>>24;
			if(a==0) continue;
			float k = Math.min(1f, luminance(px[i])/(float)max);
			px[i] = a<<24 | Math.round(c.getRed()*k)<<16 | Math.round(c.getGreen()*k)<<8 | Math.round(c.getBlue()*k);
		}
		img.setRGB(0, 0, iw, ih, px, 0, iw);
		g.drawImage(img, 0, 0, w, h, null);
	}
	private static int luminance(int rgb){
		return (((rgb>>16)&0xff)*299 + ((rgb>>8)&0xff)*587 + (rgb&0xff)*114)/1000;
	}
	//アイコン付きで名前も表示するとき、HyperCardはアイコンを中央より6px上(上端=中央-22)に置く。
	//Swingのレイアウトは「アイコン+間隔+名前(空白1文字=フォントの高さ)」を上下中央に置くので、
	//間隔を 12px-フォント高 にするとアイコンがちょうどその位置に来る(名前自体はFullButtonNameが描く)。
	@Override
	public int getIconTextGap(){
		if(btnData!=null && btnData.showName && getIcon()!=null && super.getText()!=null && !super.getText().isEmpty()){
			return Host.get().toPhysical(12)-getFontMetrics(getFont()).getHeight();
		}
		return super.getIconTextGap();
	}
	//HyperCardはアイコンを常に左右中央に置く(textAlignは名前だけに効く)。
	@Override
	public int getHorizontalAlignment(){
		if(btnData!=null && btnData.showName && getIcon()!=null) return SwingConstants.CENTER;
		return super.getHorizontalAlignment();
	}

	//2倍拡大表示モードでは、呼び出し元は今まで通り論理座標(x,y,w,h)を渡してよい。
	//実際のSwing上のサイズ・位置だけをここで拡大率倍する。
	@Override
	public void setBounds(int x, int y, int width, int height){
		Rectangle physical = Host.get().toPhysical(x, y, width, height);
		super.setBounds(physical.x, physical.y, physical.width, physical.height);
	}

    void setAutoHilite(boolean b){
    	autoHilite = b;
    }
    void setHilite(boolean b){
    	hilite = b;
    	btnData.check_hilite = b;
    	getModel().setArmed(hilite);
    }
    boolean getHilite(){
        //a disabled ButtonModel refuses (and drops) armed, but HyperCard still draws a disabled button's hilite
        if(autoHilite && HZ.editMode == 0 && isEnabled()) return getModel().isArmed();
    	return hilite;
    }
    
	@Override
	protected void paintComponent(Graphics g) {
		if(!isVisible() || (HZ.lockedScreen && !HZ.capturingEffect)) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.stack().curCard == null || btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;

		if(btnData.showName && HZ.stack().createdByVersion.charAt(0)=='2'){
			//HyperCard 2.x系から変換されたスタックは、元のMac OSとのフォントメトリクスの
			//違いでボタン名が収まらなくなることがあるため、ボタンの幅に収まらない場合は
			//少しフォントサイズを小さくして描画する（btnData.textSize自体は変更しない。
			//収まるようになれば次の描画で元に戻る。HyperZebraで新規作成したスタックは対象外）。
			//拡大表示モードではgetWidth()は物理サイズなので、フォントも物理サイズで測る。
			int physicalTextSize = Host.get().toPhysical(btnData.textSize);
			Font baseFont = OObject.stackFont(btnData.textFont, btnData.textStyle & 0x03, physicalTextSize);
			FontMetrics fo = g.getFontMetrics(baseFont);
			int fitted = physicalTextSize;
			if(fo.stringWidth(StackTranslation.buttonName(btnData, btnData.name)) > getWidth()-4 && fitted>6){
				fitted--;
			}
			Font fittedFont = fitted==physicalTextSize ? baseFont : OObject.stackFont(btnData.textFont, btnData.textStyle & 0x03, fitted);
			//JComponent.setFont()は新旧フォントを参照比較(font != oldFont)して
			//違えばrevalidate()+repaint()するため、描画のたびにnew Fontを渡すと
			//「描画→再描画要求→描画→…」の無限ループになり、EDTとJava2Dが
			//常時CPUを消費し続ける。内容が同じなら呼ばない。
			if(!fittedFont.equals(getFont())){
				setFont(fittedFont);
			}
		}

		if(btnData.blendMode==1){
			((Graphics2D)g).setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, btnData.blendLevel/100.0f));
		}
		
		if(autoHilite || hilite==getModel().isArmed()) {
        	Icon curIcon = this.getIcon();
        	//アイコンがボタン本体より大きい場合、Swing標準のアイコン+ラベルの
        	//レイアウト(layoutCompoundLabel)はラベル用の余白を確保できず、名前が
        	//描画範囲外に追いやられて見えなくなってしまう。showNameがtrueでも
        	//アイコンがボタンより大きい時は、showName=falseの場合と同じ手動の
        	//中央寄せ描画を使い、その下に名前を手描きすることで両方を表示する。
        	boolean iconLargerThanButton = curIcon!=null && (curIcon.getIconWidth()>getWidth() || curIcon.getIconHeight()>getHeight());
        	//shadow buttons too: HyperCard J-2.3 centres the icon on the whole rectangle, shadow included (measured: a 23x23
        	//shadow button puts a 32x32 icon at -5,-5 as the other styles do); Swing's layout centred it inside the shadow border
        	//(MatteBorder 1,1,3,3), a pixel up and left (Living Labyrinth's editor block buttons)
        	if(btnData.style>=1&&btnData.style<=4 && curIcon!=null && (!btnData.showName || iconLargerThanButton)){
        		if(btnData.style==2 || btnData.style==3){ //(a shadow button is filled by RoundedCornerButton, black when hilited)
        			g.setColor(getHilite() && btnData.fillsHilite() ? btnData.hiliteColor : btnData.bgColor);
        			Rectangle r = g.getClipBounds();
        			g.fillRect(r.x, r.y, r.width, r.height);
        		}
        		if(btnData.style==3){
        			g.setColor(btnData.color);
        			//gはコンポーネント左上が原点なので(left,top)ではなく(0,0)から描く
        			g.drawRect(0, 0, getWidth()-1, getHeight()-1);
        		}
        		Icon ic = curIcon;
        		if(ic!=null){
        			if(btnData.getScaleIcon() && btnData.imageForScale!=null){
        				btnData.paintScaledIcon(g,getWidth(),getHeight());
        			}
        			else{
        				//getIconWidth/Height()は(2倍表示モードでは)物理サイズを返すため、
        				//論理座標のbtnData.width/heightではなく物理サイズのgetWidth/Height()で
        				//中央寄せの計算をする。
        				int w = ic.getIconWidth();
        				int h = ic.getIconHeight();
        				//名前も表示する場合は、アイコンを6px上げて下に名前を描くスペースを空ける(HyperCard J-2.3の実測:
        				//名前付きの31x31ボタンでは32x32アイコンが-7。0にすると上端に揃っていた)。
        				int iy = iconOffset(getHeight(), h, btnData.height) - (btnData.showName ? Host.get().toPhysical(6) : 0);
        				ic.paintIcon(this, g, iconOffset(getWidth(), w, btnData.width), iy);
        				if(btnData.showName && btnData.name!=null && btnData.name.length()>0){
        					String shownName = StackTranslation.buttonName(btnData, btnData.name);
        					FontMetrics fo = g.getFontMetrics();
        					int tw = fo.stringWidth(shownName);
        					g.setColor(getForeground());
        					g.drawString(shownName, Math.max(0,(getWidth()-tw)/2), getHeight()-fo.getDescent());
        				}
        			}
        		}/*else{
        			BufferedImage img = btnData.iconImage;
        			if(img!=null){
        		        //g2.setColor(getBackground());
        				g.drawImage(img, (btnData.width-img.getWidth())/2, (btnData.height-img.getHeight())/2, this);
        			}
        		}*/
        	}else if(btnData.getScaleIcon() && btnData.imageForScale!=null){
				btnData.paintScaledIcon(g,getWidth(),getHeight());
			}
        	else{
        		paintSwingButton(g);
        	}
        }
        else if(btnData.getScaleIcon() && btnData.imageForScale!=null){
			btnData.paintScaledIcon(g,getWidth(),getHeight());
		}
        else {
        	getModel().setArmed(hilite);
            paintSwingButton(g);
        }
		if(invertsIconOnly()) invertIcon(g);
		if(Host.get().authToolActive() && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }

	/**
	 * Where an icon centred in the button starts (component pixels, zoomed): HyperCard adds half the difference rounded
	 * down, so an icon taller than the button by an odd number starts the extra pixel above it (HyperCard J-2.3: the 32-high
	 * second hand of HyperPiece 2's clock in a 31-high button, a pixel higher than (31-32)/2 put it). Worked out at the
	 * card's own size and scaled, so a zoomed card matches.
	 */
	static int iconOffset(int box, int icon, int logicalBox){
		double s = logicalBox>0 ? box/(double)logicalBox : 1;
		int logicalIcon = (int)Math.round(icon/s);
		return (int)Math.round(Math.floorDiv(logicalBox-logicalIcon, 2)*s);
	}

	/**
	 * A hilited transparent button with an icon: HyperCard J-2.3 inverts the icon (its masked pixels) and the box of its
	 * name, not the button's rectangle (HierPopUp's Home button). Other styles, and buttons without an icon, invert the whole.
	 */
	boolean invertsIconOnly(){
		return btnData!=null && btnData.style==1 && getIcon()!=null && getHilite() && !btnData.fillsHilite() && !(btnData.getScaleIcon() && btnData.imageForScale!=null);
	}

	/** Inverts the pixels of the icon that it covers (the opaque ones), where the icon was painted */
	private void invertIcon(Graphics g){
		Icon ic = getIcon();
		int w = ic.getIconWidth(), h = ic.getIconHeight();
		if(w<=0 || h<=0) return;
		java.awt.Rectangle r;
		boolean iconLargerThanButton = w>getWidth() || h>getHeight();
		if(!btnData.showName || iconLargerThanButton) r = new java.awt.Rectangle(iconOffset(getWidth(), w, btnData.width), iconOffset(getHeight(), h, btnData.height) - (btnData.showName ? Host.get().toPhysical(6) : 0), w, h);
		else{
			java.awt.Insets in = getInsets();
			java.awt.Rectangle viewR = new java.awt.Rectangle(in.left, in.top, getWidth()-in.left-in.right, getHeight()-in.top-in.bottom), textR = new java.awt.Rectangle();
			r = new java.awt.Rectangle();
			SwingUtilities.layoutCompoundLabel(this, getFontMetrics(getFont()), " ", ic, getVerticalAlignment(), getHorizontalAlignment(),
				getVerticalTextPosition(), getHorizontalTextPosition(), viewR, r, textR, getIconTextGap());
		}
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics ig = img.createGraphics();
		ic.paintIcon(this, ig, 0, 0);
		ig.dispose();
		PictureFile.invert((Graphics2D)g, PictureFile.inversionImage(img, w, h), r.x, r.y);
	}
}

class RoundedCornerButton extends MyButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final float arcwidth  = 16.0f;
	private static final float archeight = 16.0f;
	protected static final int focusstroke = 2;
	protected final Color fc = new Color(100,150,255,200);
	//protected final Color ac = new Color(0,0,0);
	//protected final Color rc = Color.ORANGE;
	protected Shape shape;
	protected Shape border;
	protected Shape base;

	public RoundedCornerButton(OButton btn, String text) {
	    super(btn, text);
	    //setRolloverEnabled(true);
	    setContentAreaFilled(false);
	    setBackground(new Color(255, 255, 255));
	    initShape();
	}
	
	protected void initShape() {
	    if(!getBounds().equals(base)) {
	    	base = getBounds();
	    	shape = new RoundRectangle2D.Float(0, 0,
	    			getWidth()-1, getHeight()-1,
	    			arcwidth, archeight);
	    	border = new RoundRectangle2D.Float(focusstroke, focusstroke,
	    			getWidth()-1-focusstroke*2,
	    			getHeight()-1-focusstroke*2,
	    			arcwidth, archeight);
		}
	}
	/** What a hilite inverts or fills (the shape stops a pixel short, for the frame drawn on that pixel) */
	protected Shape hiliteShape(){
		return shape;
	}
    /*private void paintFocusAndRollover(Graphics2D g2, Color color) {
        g2.setPaint(new GradientPaint(0, 0, color, getWidth()-1, getHeight()-1, color.brighter(), true));
        g2.fill(shape);
        g2.setColor(getBackground());
        g2.fill(border);
    }*/

    @Override
    protected void paintComponent(Graphics g) {
		if(!isVisible() || (HZ.lockedScreen && !HZ.capturingEffect)) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.stack()==null || HZ.stack().curCard==null) return; //スタック切り替え中の再描画
		if(btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
        initShape();
        Graphics2D g2 = (Graphics2D)g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if(getHilite() && btnData.fillsHilite()) {
        	//hiliteColor: filled with the colour instead of inverted; the name keeps its own colour
        	g2.setPaintMode();
        	g2.setColor(btnData.hiliteColor);
        	g2.fill(hiliteShape());
            setForeground(btnData.color);
			setBackground(btnData.hiliteColor);
        }else if(getHilite()) {
        	if(invertsIconOnly()){
        		//a transparent button with an icon inverts only the icon and its name (MyButton.invertIcon)
        	}else if(btnData.style!=1 && btnData.style!=7){ //透明と楕円はXOR
	            g2.setColor(Color.BLACK);
	            g2.fill(hiliteShape());
        	}else{
            	g2.setXORMode(Color.BLACK);
	            g2.fill(hiliteShape());
	            //HyperCard draws the name and then inverts the button: the name comes out in the inverse of its colour, crisp.
	            //Drawn in XOR mode its anti-aliased letters (bold drawn twice) cancelled out into hollow, fuzzy outlines
	            //(CD Editor's hilited transparent "Time Remaining:" on its black panel)
	            g2.setPaintMode();
        	}
        	boolean inverted = !invertsIconOnly() && (btnData.style==1 || btnData.style==7);
            setForeground(inverted ? new Color(~btnData.color.getRGB() & 0xffffff) : btnData.bgColor);
			setBackground(btnData.color);
        //}else if(isRolloverEnabled() && getModel().isRollover()) {
            //paintFocusAndRollover(g2, rc);
        //}else if(hasFocus() && isFocusPainted()) {
            //paintFocusAndRollover(g2, fc);
        }else{
        	if(btnData.style!=1 && btnData.style!=7){ //透明と楕円は書かない
        		g2.setColor(getBackground());
        		g2.fill(shape);
            }
            setForeground(btnData.color);
			setBackground(btnData.bgColor);
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setColor(getBackground());
        super.paintComponent(g2);
        //an outlined or shadowed name is drawn only when the button shows its name (Fading 1.2: transparent "FOO" buttons over
        //the card's own lettering, showName false)
        if(!btnData.showName || getIcon()!=null) return; //an icon's name: FullButtonName, plain 9 point
        String shownName = StackTranslation.buttonName(btnData, btnData.name);
        if((btnData.textStyle&8) > 0) { //outline
        	int fittedTextSize = getFont().getSize(); //収まらない場合は少し縮めたサイズを使う(拡大表示モードでは既に物理サイズ)
        	int scaledWidth = Host.get().toPhysical(btnData.width);
        	int scaledHeight = Host.get().toPhysical(btnData.height);
        	g2.setFont(OObject.stackFont(btnData.textFont, Font.PLAIN, fittedTextSize));
    		FontMetrics fo = g2.getFontMetrics();
        	int w = fo.stringWidth(shownName);
        	g2.setPaintMode();
        	g2.setColor(Color.BLACK);
        	g2.drawString(shownName,(scaledWidth-w)/2-1,(scaledHeight+fittedTextSize)/2-0);
        	g2.drawString(shownName,(scaledWidth-w)/2-0,(scaledHeight+fittedTextSize)/2-1);
        	g2.drawString(shownName,(scaledWidth-w)/2+1,(scaledHeight+fittedTextSize)/2+0);
        	g2.drawString(shownName,(scaledWidth-w)/2+0,(scaledHeight+fittedTextSize)/2+1);
        	g2.setColor(Color.WHITE);
        	g2.drawString(shownName,(scaledWidth-w)/2,(scaledHeight+fittedTextSize)/2);
        	g2.setColor(getBackground());
        	return;
        }
        if((btnData.textStyle&16) > 0) { //shadow
        	int fittedTextSize = getFont().getSize(); //収まらない場合は少し縮めたサイズを使う(拡大表示モードでは既に物理サイズ)
        	int scaledWidth = Host.get().toPhysical(btnData.width);
        	int scaledHeight = Host.get().toPhysical(btnData.height);
        	g2.setFont(OObject.stackFont(btnData.textFont, Font.PLAIN, fittedTextSize));
    		FontMetrics fo = g2.getFontMetrics();
        	int w = fo.stringWidth(shownName);
        	g2.setColor(Color.BLACK);
        	//the outline all round (QuickDraw's shadow includes it), then the shadow below and to the right
        	g2.drawString(shownName,(scaledWidth-w)/2-1,(scaledHeight+fittedTextSize)/2+0);
        	g2.drawString(shownName,(scaledWidth-w)/2+0,(scaledHeight+fittedTextSize)/2-1);
        	g2.drawString(shownName,(scaledWidth-w)/2+0,(scaledHeight+fittedTextSize)/2+1);
        	g2.drawString(shownName,(scaledWidth-w)/2+1,(scaledHeight+fittedTextSize)/2+0);
        	g2.drawString(shownName,(scaledWidth-w)/2+1,(scaledHeight+fittedTextSize)/2+1);
        	g2.drawString(shownName,(scaledWidth-w)/2+2,(scaledHeight+fittedTextSize)/2+1);
        	g2.drawString(shownName,(scaledWidth-w)/2+1,(scaledHeight+fittedTextSize)/2+2);
        	g2.setColor(Color.WHITE);
        	g2.drawString(shownName,(scaledWidth-w)/2,(scaledHeight+fittedTextSize)/2);
        	g2.setColor(getBackground());
        	return;
        }
    }
    @Override
    protected void paintBorder(Graphics g) {
		if(!isVisible() || (HZ.lockedScreen && !HZ.capturingEffect)) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.stack()==null || HZ.stack().curCard==null) return; //スタック切り替え中の再描画
		if(btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
        if(isBorderPainted()){
	        initShape();
	        paintFrame((Graphics2D)g);
        }
		if(Host.get().authToolActive() && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
		else if((btnData.style==1 || btnData.style==2) && Host.get().authToolActive() && AuthTool.tool.getClass()==ButtonTool.class ){
			g.setColor(Color.BLACK);
			// Component dimensions are already converted to physical pixels by setBounds().
			// Using the logical part dimensions here made the tool outline half-sized at 2x zoom.
			Rectangle outline = ButtonGUI.localOutlineBounds(getWidth(), getHeight());
			g.drawRect(outline.x, outline.y, outline.width, outline.height);
		}
    }
    /** The button's frame in its colour. */
    protected void paintFrame(Graphics2D g2){
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(btnData.color);
        g2.draw(shape);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    }
    /** Fills the logical rectangle x,y,w,h of the button (its own coordinates) in window pixels, edges rounded as the card's. */
    protected void fillLogical(Graphics g, int x, int y, int w, int h){
        int l = Host.get().toPhysical(btnData.left), t = Host.get().toPhysical(btnData.top);
        int px = Host.get().toPhysical(btnData.left+x)-l, py = Host.get().toPhysical(btnData.top+y)-t;
        g.fillRect(px, py, Host.get().toPhysical(btnData.left+x+w)-l-px, Host.get().toPhysical(btnData.top+y+h)-t-py);
    }
    @Override
    public boolean contains(int x, int y) {
        initShape();
        return shape.contains(x, y);
    }
}

class RectButton extends RoundedCornerButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public RectButton(OButton btn, String text) {
	    super(btn, text);
		setFocusPainted(false);
		initShape();
	}

	/** A shadow button's icon (and the name under it) is placed on the whole rectangle, as HyperCard J-2.3 does: its border's
	 *  insets (1,1,3,3, the shadow) moved a named 40x40 button's icon a pixel up and left. Without an icon the name keeps them. */
	@Override
	public java.awt.Insets getInsets(){
		if(btnData!=null && btnData.style==4 && getIcon()!=null) return new java.awt.Insets(0, 0, 0, 0);
		return super.getInsets();
	}
	@Override
	public java.awt.Insets getInsets(java.awt.Insets in){
		if(btnData!=null && btnData.style==4 && getIcon()!=null){
			if(in==null) return new java.awt.Insets(0, 0, 0, 0);
			in.set(0, 0, 0, 0);
			return in;
		}
		return super.getInsets(in);
	}

	protected void initShape() {
		if (!getBounds().equals(base)) {
			base = getBounds();
			shape = new Rectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1);
			border = new Rectangle2D.Float(focusstroke, focusstroke, getWidth()
					- 1 - focusstroke * 2, getHeight() - 1 - focusstroke * 2);
		}
	}
	
	//HyperCard inverts a transparent or opaque button's whole rectangle, its last column and row too (measured in HC J-2.3)
	@Override
	protected Shape hiliteShape(){
		return btnData.style<=2 ? new Rectangle(0, 0, getWidth(), getHeight()) : shape;
	}

	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(HZ.stack().curCard==null || btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
        Graphics2D g2 = (Graphics2D)g;
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        boolean fillsHilite = btnData.fillsHilite() && getHilite(); //hiliteColor: RoundedCornerButton fills it, no inversion
        if(btnData.style==1){ //透明
        	//the first half of the two-stage XOR (RoundedCornerButton does the other): one inversion whatever the drawing colour.
        	//Only for autoHilite before, so a set hilite inverted only once setForeground had made the colour white
        	if(getHilite() && !invertsIconOnly() && !fillsHilite) {
                initShape();
            	g2.setXORMode(Color.WHITE);
	            g2.fill(hiliteShape());
	        }
        	else
        	{
            	//g2.setXORMode(Color.WHITE);
        	}
		}
        if(btnData.style==2){ //不透明
            initShape();
        	if(fillsHilite || getHilite()) {
        		//RoundedCornerButton fills it black (an inversion here let a black card picture show through white)
	        }else{
	            g2.setColor(getBackground());
	            g2.fill(hiliteShape()); //the whole rectangle, like the hilite
	        }
		}
        if(btnData.style==3 || btnData.style==4){ //長方形 シャドウ
            initShape();
        	if(getHilite()) {
	            //g2.setColor(Color.WHITE);
	            //g2.fill(shape);
        		//g2.setColor(getBackground());
	        }else{
	            //g2.setColor(getBackground());
	            //g2.fill(shape);
	        }
		}
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        ////g2.setColor(getBackground());
        super.paintComponent(g2);
    }
    @Override
    protected void paintBorder(Graphics g) {
		if(!isVisible() || (HZ.lockedScreen && !HZ.capturingEffect)) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.stack().curCard==null || btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
        if(btnData.style==4){
	        //in card pixels: zoomed, the frame and the shadow are as thick as one of them (they were one window pixel)
	        g.setColor(btnData.color);
	        int w = btnData.width, h = btnData.height;
	        fillLogical(g, 0, 0, w-1, 1);
	        fillLogical(g, 0, 0, 1, h-1);
	        fillLogical(g, w-1, 2, 1, h-2);
	        fillLogical(g, w-2, 1, 1, h-1);
	        fillLogical(g, 2, h-1, w-2, 1);
	        fillLogical(g, 1, h-2, w-1, 1);
        }
        else{
        	super.paintBorder(g);
        }
    }
    /** A rectangle button's frame is one card pixel wide: drawn one window pixel wide at 2x, the white inside showed half a card
     *  pixel past HyperCard's frame (G'S LAIR's title logo, which ColorizeHC draws inside it, had a white edge left and top). */
    @Override
    protected void paintFrame(Graphics2D g2){
        if(btnData.style!=3){ super.paintFrame(g2); return; }
        g2.setColor(btnData.color);
        int w = btnData.width, h = btnData.height;
        fillLogical(g2, 0, 0, w, 1);
        fillLogical(g2, 0, h-1, w, 1);
        fillLogical(g2, 0, 0, 1, h);
        fillLogical(g2, w-1, 0, 1, h);
    }
}

class RoundButton extends RoundedCornerButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public RoundButton(OButton btn, String text) {
	    super(btn, text);
		setFocusPainted(false);
		initShape();
	}

	protected void initShape() {
		if (!getBounds().equals(base)) {
			base = getBounds();
			shape = new Ellipse2D.Float(0, 0, getWidth() - 1, getHeight() - 1);
			border = new Ellipse2D.Float(focusstroke, focusstroke, getWidth()
					- 1 - focusstroke * 2, getHeight() - 1 - focusstroke * 2);
		}
	}

	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(HZ.stack().curCard==null || btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
        initShape();
        Graphics2D g2 = (Graphics2D)g;
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        if(getHilite() && !btnData.fillsHilite()) {
        	//透明ボタン(RectButton style 1)と同じ二段XOR: ここでWHITE、続く
        	//RoundedCornerButtonでBLACKをXORし、描画色に関係なく楕円内を反転する。
        	//間で描画色を背景色に変えると、フォアグラウンド(黒)^WHITE^背景(白)^BLACKで
        	//元に戻ってしまい、ハイライトが見えなかった。
        	g.setXORMode(Color.WHITE);
            g2.fill(shape);
        }
        super.paintComponent(g2);
		if(Host.get().authToolActive() && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }
    @Override
    protected void paintBorder(Graphics g) {
    }
}


/**
 * HyperCard's mark for a radio button or check box: a 12-pixel circle or square 3 pixels in from the left edge, centred
 * vertically, with the name 4 pixels after it (a dot 3 pixels in when a radio button is on, an X for a check box).
 * The look-and-feel's own marks are bigger and sit further right, so names that fit in HyperCard were cut to
 * "2000..." here; the colours are the button's.
 */
class ClassicToggleIcon implements Icon {
	static final int SIZE=12, LEFT_INSET=3, TEXT_GAP=4;
	private final OButton button; private final boolean radio;
	ClassicToggleIcon(OButton button, boolean radio){ this.button=button; this.radio=radio; }
	/** Gives a radio button or check box this mark and HyperCard's spacing around it. */
	static void install(javax.swing.AbstractButton component, OButton button, boolean radio){
		component.setIcon(new ClassicToggleIcon(button, radio));
		component.setIconTextGap(Host.get().toPhysical(TEXT_GAP));
		component.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, Host.get().toPhysical(LEFT_INSET), 0, 0));
		component.setRolloverEnabled(false);
	}
	public int getIconWidth(){ return Host.get().toPhysical(SIZE); }
	public int getIconHeight(){ return Host.get().toPhysical(SIZE); }
	public void paintIcon(java.awt.Component c, Graphics g, int x, int y){
		//線の太さは拡大率を丸めたピクセル数(最大125%などでも1ピクセル以上)
		int size = Host.get().toPhysical(SIZE), scale = Math.max(1, (int)Math.round(Host.get().scale()));
		int dot = Host.get().toPhysical(3), dotSize = Host.get().toPhysical(6);
		boolean selected = c instanceof javax.swing.AbstractButton && ((javax.swing.AbstractButton)c).isSelected();
		Color fg = button.color, bg = button.bgColor;
		if(!c.isEnabled()) fg = new Color((fg.getRed()+bg.getRed())/2, (fg.getGreen()+bg.getGreen())/2, (fg.getBlue()+bg.getBlue())/2);
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			if(radio){
				g2.setColor(bg); g2.fillOval(x, y, size, size);
				g2.setColor(fg);
				for(int i=0; i<scale; i++) g2.drawOval(x+i, y+i, size-1-2*i, size-1-2*i);
				if(selected) g2.fillOval(x+dot, y+dot, dotSize, dotSize);
			}else{
				g2.setColor(bg); g2.fillRect(x, y, size, size);
				g2.setColor(fg);
				for(int i=0; i<scale; i++) g2.drawRect(x+i, y+i, size-1-2*i, size-1-2*i);
				if(selected){
					for(int i=0; i<scale; i++){
						g2.drawLine(x+i, y, x+size-1, y+size-1-i); g2.drawLine(x, y+i, x+size-1-i, y+size-1);
						g2.drawLine(x+size-1-i, y, x, y+size-1-i); g2.drawLine(x+size-1, y+i, x+i, y+size-1);
					}
				}
			}
		}finally{ g2.dispose(); }
	}
}

//ボタン/フィールド/ペイントツール中のクリックでチェックボックス・ラジオボタンのチェックが
//変わらないようにする。JToggleButtonはリスナーとは別にUI自身がクリックでモデルを反転させるので、
//ツール中はarmed(押下)を受け付けない(押した見た目にもならない)。スクリプトのset hiliteはsetSelectedなので影響しない
class EditToolInertToggleModel extends javax.swing.JToggleButton.ToggleButtonModel {
	private static final long serialVersionUID = 1L;
	@Override
	public void setArmed(boolean b){
		if(b && GUI.editingTool()) b = false;
		super.setArmed(b);
	}
}

class MyRadio extends JRadioButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OButton btnData=null;
	
	//Swingの標準描画中は名前を空白1文字に見せ、名前はFullButtonNameで自前描画する(下のpaintSwingButton)。
	private boolean hidingName=false;

	public MyRadio(OButton btn, String text){
		super(text);
		btnData=btn;
		setModel(new EditToolInertToggleModel());
		setMargin(new Insets(0,0,0,0));
		this.setDoubleBuffered(HZ.useDoubleBuffer);
	}
	@Override
	public void paint(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		super.paint(g);
	}
	@Override
	public String getText(){
		return hidingName ? " " : super.getText();
	}
	//JRadioButton標準の描画。名前はSwingではなくFullButtonNameがHyperCardの位置に描く
	//(マークの後ろ19px、ベースラインは中央+4、収まらなくても「...」にせず矩形で切り取る)。
	private void paintSwingButton(Graphics g){
		String name = StackTranslation.buttonName(btnData, super.getText()); //表示だけ翻訳(StackTranslation)。名前そのものは変えない
		FullButtonName.Placement p = btnData.showName ? FullButtonName.place(this, btnData, name, g) : null;
		if(p==null){ super.paintComponent(g); return; }
		hidingName = true;
		try{ super.paintComponent(g); } finally{ hidingName = false; }
		FullButtonName.paint(this, g, name, p, "RadioButton");
	}
	//2倍拡大表示モードでは、呼び出し元は今まで通り論理座標(x,y,w,h)を渡してよい。
	//実際のSwing上のサイズ・位置だけをここで拡大率倍する。
	@Override
	public void setBounds(int x, int y, int width, int height){
		Rectangle physical = Host.get().toPhysical(x, y, width, height);
		super.setBounds(physical.x, physical.y, physical.width, physical.height);
	}
    @Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(btnData==null||HZ.stack().curCard==null||btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		paintSwingButton(g);
		if((btnData.textStyle&8)!=0&&btnData.showName)
			paintOutlineText(g,StackTranslation.buttonName(btnData, btnData.name),getInsets().left+(getIcon()!=null?getIcon().getIconWidth():getFont().getSize())+getIconTextGap());
		if(Host.get().authToolActive() && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
	}

	private void paintOutlineText(Graphics g,String text,int x) {
		Graphics2D g2=(Graphics2D)g;g2.setFont(getFont());FontMetrics metrics=g2.getFontMetrics();
		int y=(getHeight()-metrics.getHeight())/2+metrics.getAscent();
		g2.setColor(btnData.color);
		g2.drawString(text,x-1,y);g2.drawString(text,x+1,y);g2.drawString(text,x,y-1);g2.drawString(text,x,y+1);
		g2.setColor(btnData.bgColor);g2.drawString(text,x,y);
	}
}

class MyCheck extends JCheckBox {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OButton btnData=null;
	
	//Swingの標準描画中は名前を空白1文字に見せ、名前はFullButtonNameで自前描画する(下のpaintSwingButton)。
	private boolean hidingName=false;

	public MyCheck(OButton btn, String text){
		super(text);
		btnData=btn;
		setModel(new EditToolInertToggleModel());
		setMargin(new Insets(0,0,0,0));
		this.setDoubleBuffered(HZ.useDoubleBuffer);
	}
	@Override
	public void paint(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		super.paint(g);
	}
	@Override
	public String getText(){
		return hidingName ? " " : super.getText();
	}
	//JCheckBox標準の描画。名前はSwingではなくFullButtonNameがHyperCardの位置に描く
	//(マークの後ろ19px、ベースラインは中央+4、収まらなくても「...」にせず矩形で切り取る)。
	private void paintSwingButton(Graphics g){
		String name = StackTranslation.buttonName(btnData, super.getText()); //表示だけ翻訳(StackTranslation)。名前そのものは変えない
		FullButtonName.Placement p = btnData.showName ? FullButtonName.place(this, btnData, name, g) : null;
		if(p==null){ super.paintComponent(g); return; }
		hidingName = true;
		try{ super.paintComponent(g); } finally{ hidingName = false; }
		FullButtonName.paint(this, g, name, p, "CheckBox");
	}
	//2倍拡大表示モードでは、呼び出し元は今まで通り論理座標(x,y,w,h)を渡してよい。
	//実際のSwing上のサイズ・位置だけをここで拡大率倍する。
	@Override
	public void setBounds(int x, int y, int width, int height){
		Rectangle physical = Host.get().toPhysical(x, y, width, height);
		super.setBounds(physical.x, physical.y, physical.width, physical.height);
	}
	@Override
	protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(HZ.stack().curCard==null || btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		paintSwingButton(g);
		if((btnData.textStyle&8)!=0&&btnData.showName)
			paintOutlineText(g,StackTranslation.buttonName(btnData, btnData.name),getInsets().left+(getIcon()!=null?getIcon().getIconWidth():getFont().getSize())+getIconTextGap());
		if(Host.get().authToolActive() && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }

	private void paintOutlineText(Graphics g,String text,int x) {
		Graphics2D g2=(Graphics2D)g;g2.setFont(getFont());FontMetrics metrics=g2.getFontMetrics();
		int y=(getHeight()-metrics.getHeight())/2+metrics.getAscent();
		g2.setColor(btnData.color);
		g2.drawString(text,x-1,y);g2.drawString(text,x+1,y);g2.drawString(text,x,y-1);g2.drawString(text,x,y+1);
		g2.setColor(btnData.bgColor);g2.drawString(text,x,y);
	}
}

class MyPopup extends JComboBox {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OButton btnData=null;
	/** true while HyperZebra itself changes the selection (OButton.setSelectedLine): GPopup ignores the action it fires */
	volatile boolean settingSelection;
	
	@SuppressWarnings("unchecked")
	public MyPopup(OButton btn, String[] text){
		super(text);
		btnData=btn;
		this.setDoubleBuffered(HZ.useDoubleBuffer);
		//選択肢は表示だけ翻訳する(StackTranslation)。選んだ値(selectedText)は元のまま
		//幅の狭いポップアップでは選択中の項目(index -1)をAquaには描かせず、paintで自分で描く
		final javax.swing.ListCellRenderer<Object> base = getRenderer();
		setRenderer((list, value, index, selected, focus) ->
			base.getListCellRendererComponent(list, index==-1 && ownValue ? "" : value instanceof String ? StackTranslation.popupItem(btnData, (String)value) : value, index, selected, focus));
	}

	//Aquaのポップアップが選択中の項目を描く幅は、ボタンの幅より50px狭い
	//(AquaComboBoxButton.doRendererPaint: 矢印ボタンの左右inset 2pxずつ、固定の左10px・
	//矢印24px、さらに右inset 2px、レンダラの左右5pxずつ)。実機HCのポップアップは
	//矢印部分の約20pxと余白数pxだけなので、WAKUREN君の「4月」(62px)や「1997年」(75px)
	//は実機では全部見えるのに、Aquaでは「...」「19...」になっていた。
	//そこで項目がこの幅に収まらないときだけ、小さいAquaの見た目(sizeVariant small:
	//枠は左5px・矢印は右端から20px)にして、項目は枠の左端から矢印の手前まで自分で描く。
	//収まる幅広のポップアップはAquaの描き方のまま変えない。
	static final int AQUA_VALUE_INSETS = 50;
	static final int NARROW_VALUE_LEFT = 8;
	static final int NARROW_VALUE_RIGHT = 22;
	private static final String SIZE_VARIANT = "JComponent.sizeVariant";
	private boolean ownValue;

	/** Whether Aqua's own drawing of the selected item shows text this wide in a pop-up this wide (physical pixels). */
	static boolean fitsAquaValue(int width, int textWidth){
		return textWidth <= width - AQUA_VALUE_INSETS;
	}

	/** The selected item as shown when it is too wide for Aqua's own drawing (drawn by {@link #paint}), else null. */
	String narrowValueText(){
		if(getUI()==null || !getUI().getClass().getName().startsWith("com.apple.laf.") || isEditable()) return null;
		Object selected = getSelectedItem();
		if(selected==null) return null;
		String text = selected instanceof String && btnData!=null ? StackTranslation.popupItem(btnData, (String)selected) : selected.toString();
		return fitsAquaValue(getWidth(), getFontMetrics(getFont()).stringWidth(text)) ? null : text;
	}

	//幅・フォント・選択項目が変わったら、小さいAquaの見た目にするかを決め直す。一度smallに
	//したものは"regular"に戻す(nullで消すとAquaは枠を元の大きさに戻さない)
	private void updateNarrowLook(){
		if(getFont() instanceof javax.swing.plaf.UIResource) return; //HZのフォントを入れる前
		Object variant = getClientProperty(SIZE_VARIANT);
		if(narrowValueText()!=null){
			if(!"small".equals(variant)) putClientProperty(SIZE_VARIANT, "small");
		}
		else if(variant!=null && !"regular".equals(variant)) putClientProperty(SIZE_VARIANT, "regular");
	}

	@Override
	protected void selectedItemChanged(){
		super.selectedItemChanged();
		updateNarrowLook();
	}

	@Override
	public void setFont(Font font){
		super.setFont(font);
		updateNarrowLook();
	}

	@Override
	public void paint(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		String own = narrowValueText();
		ownValue = own!=null;
		try{ super.paint(g); }
		finally{ ownValue = false; }
		if(own!=null) paintNarrowValue(g, own);
	}

	//AquaComboBoxButton.doRendererPaintと同じ高さ(上 高さ/2-8 から19px、レンダラの上下inset 1px)の中央に描く
	private void paintNarrowValue(Graphics g, String text){
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setFont(getFont());
			FontMetrics metrics = g2.getFontMetrics();
			int right = getWidth()-NARROW_VALUE_RIGHT;
			text = javax.swing.plaf.basic.BasicGraphicsUtils.getClippedString(this, metrics, text, right-NARROW_VALUE_LEFT);
			int y = getHeight()/2-8+1+(17-metrics.getHeight())/2+metrics.getAscent();
			g2.clipRect(0, 0, right, getHeight());
			if((btnData.textStyle&8)!=0){ paintOutlineText(g2, text, NARROW_VALUE_LEFT, y); return; }
			g2.setColor(isEnabled() ? getForeground() : javax.swing.UIManager.getColor("ComboBox.disabledForeground"));
			javax.swing.plaf.basic.BasicGraphicsUtils.drawString(this, g2, text, NARROW_VALUE_LEFT, y);
		}
		finally{ g2.dispose(); }
	}
	//2倍拡大表示モードでは、呼び出し元は今まで通り論理座標(x,y,w,h)を渡してよい。
	//実際のSwing上のサイズ・位置だけをここで拡大率倍する。
	//あわせて、実機のポップアップメニューコントロール(titleWidth)と同様、
	//「名前を表示する」がオンでtitleWidthが0より大きいときは、ボタン全体の
	//矩形(x,y,width,height、渡されるのは常に論理座標での全体の矩形)の
	//左側をtitleWidthの幅で名前表示用のラベルに割り当て、ポップアップ本体
	//(このJComboBox自身)は残りの右側だけを使う。
	@Override
	public void setBounds(int x, int y, int width, int height){
		if(height==19) height = 20; //hcのデフォルトでは上が消えてしまう(描くときだけ: the rect stays the button's)
		double scale = Host.get().scale();
		int nameWidth = OButton.popupNameWidthFor(btnData, width);
		if(nameWidth>0){
			if(btnData.popupNameLabel==null){
				MyPopupNameLabel label = new MyPopupNameLabel();
				label.btnData = btnData;
				btnData.popupNameLabel = label;
				btnData.popupNameLabel.setOpaque(true);
				btnData.popupNameLabel.setFocusable(false);
				PCARD.pc.mainPane.add(btnData.popupNameLabel);
			}
			btnData.popupNameLabel.setText(btnData.name);
			btnData.popupNameLabel.setForeground(btnData.color);
			btnData.popupNameLabel.setBackground(btnData.bgColor);
			btnData.popupNameLabel.setFont(OObject.stackFont(btnData.textFont, btnData.textStyle&0x03, btnData.textSize*scale));
			btnData.popupNameLabel.setBounds(Host.get().toPhysical(x, y, nameWidth, height));
			btnData.popupNameLabel.setVisible(isVisible());
		}
		else if(btnData.popupNameLabel!=null){
			PCARD.pc.mainPane.remove(btnData.popupNameLabel);
			btnData.popupNameLabel = null;
		}
		Rectangle physical = Host.get().toPhysical(x+nameWidth, y, Math.max(0,width-nameWidth), height);
		super.setBounds(physical.x, physical.y, physical.width, physical.height);
		updateNarrowLook();
	}
	@Override
	public void setVisible(boolean visible){
		super.setVisible(visible);
		if(btnData.popupNameLabel!=null) btnData.popupNameLabel.setVisible(visible);
	}
	@Override
	public void setEnabled(boolean enabled){
		super.setEnabled(enabled);
		if(btnData.popupNameLabel!=null) btnData.popupNameLabel.setEnabled(enabled);
	}
	boolean flag;
	@Override
	protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		//if(btnData.card!=HZ.stack().curCard && btnData.card!=HZ.stack().curCard.bg) return;
		if(PCARD.pc.bit != 1) return;
		if(HZ.editBackground && btnData.parent.objectType.equals("card")) return;
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		this.validate();
		super.paintComponent(g);
		if((btnData.textStyle&8)!=0 && !ownValue){
			Object selected=getSelectedItem();
			if(selected!=null){
				Graphics2D g2=(Graphics2D)g;g2.setFont(getFont());FontMetrics metrics=g2.getFontMetrics();
				paintOutlineText(g2,selected.toString(),getInsets().left+4,(getHeight()-metrics.getHeight())/2+metrics.getAscent());
			}
		}
		if(Host.get().authToolActive() && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
	}

	private void paintOutlineText(Graphics2D g2,String text,int x,int y) {
		g2.setColor(btnData.color);
		g2.drawString(text,x-1,y);g2.drawString(text,x+1,y);g2.drawString(text,x,y-1);g2.drawString(text,x,y+1);
		g2.setColor(btnData.bgColor);g2.drawString(text,x,y);
	}
}

//popupのタイトル部分はJComboBoxとは別コンポーネントなので、標準JLabelのままだと
//lock screen中にも単独で再描画される。ほかのカード部品と同じ描画条件に揃える。
class MyPopupNameLabel extends JLabel {
	private static final long serialVersionUID = 1L;
	OButton btnData;

	/** The name as shown: translated when the stack's translation is on (StackTranslation). */
	@Override
	public String getText(){
		String t = super.getText();
		return btnData==null ? t : StackTranslation.buttonName(btnData, t);
	}

	@Override
	public void paint(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		super.paint(g);
	}

	@Override
	protected void paintComponent(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		super.paintComponent(g);
	}

	@Override
	protected void paintBorder(Graphics g){
		if(HZ.lockedScreen && !HZ.capturingEffect) return;
		super.paintBorder(g);
	}
}

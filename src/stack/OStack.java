import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import java.nio.charset.Charset;
import java.io.*;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
//import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OStack extends OObject {
	final AuthoringUndo authoringUndo = new AuthoringUndo();
	PCARDFrame pcard;
	
	Charset charset= Charset.forName("UTF-8");//文字コード
	StackTextEncoding legacyTextEncoding = HCData.currentEncoding;
	String path="";//スタックファイルのパス
	File file;
	OCard curCard;
	GButton GUI_btn;
	GRadio GUI_radio;
	GCheckBox GUI_check;
	GPopup GUI_popup;
	GField GUI_fld;
	ScriptEditor scriptEditor;
	JPanel mainPane;
	Rsrc rsrc = new Rsrc(this);
	/** The resources the stack had when it was closed (clean): its externals for a handler of it that is still running
	 *  after a go to another stack (HyperCard keeps that stack's resource fork in the message path). */
	Rsrc closedResources;
	String[] Pattern = new String[40];
	BufferedImage addColor;
	/** heap bytes AddColor install took for its offscreen buffer: the heapSpace is that much less until AddColor remove */
	long addColorHeapBytes;
	boolean changed;
	XMLwrite saveXML;
	ArrayList<OStack> usingStacks = new ArrayList<OStack>();
	/** For a stack read only for "the <property> of stack X": the file's time when it was read (TTalk.resolveStackObject) */
	long referencedModified;

	//進捗ダイアログ
	JDialog barDialog;
	JProgressBar bar;
	int barOffset;
	int totalSize;
	
	//プロパティ
	boolean cantAbort=false;
	boolean cantDelete=false;
	boolean cantModify=false;
	/** The user changed the stack through HyperZebra's authoring UI (a tool, the script editor, an editing menu
	 *  command) since the last write. cantModify keeps the changes scripts make from being written (a game's state),
	 *  but never discards the user's own edits: XMLwrite writes the stack while this is set. */
	volatile boolean userEdited=false;

	/** Records an edit made by the user (see userEdited). */
	static void userEdit(){
		if(PCARD.pc!=null && PCARD.pc.stack!=null) PCARD.pc.stack.userEdited = true;
	}
	boolean cantPeek=false;
	int firstCard;//toc.xml
	int userLevel;//Protect Stackの「ユーザーレベルの制限」(0/5=制限なし)。StackProtection.effectiveUserLevel
	private boolean privateAccess;//toc.xml
	String createdByVersion;//toc.xml
	String lastCompactedVersion;//toc.xml
	String lastEditedVersion;//toc.xml
	String firstEditedVersion;//toc.xml
	private int fontTableID;//toc.xml
	private int styleTableID;//toc.xml
	private int nextStyleID;//toc.xml
	private int listId;//toc.xml
	/** The LIST block id the imported stack's header names (0: none read yet) */
	private int headerListId;
	void setHeaderListId(int id){ headerListId = id; }
	private int passwordHash;//HyperCardと同じパスワードのハッシュ(0=パスワードなし)。StackProtection
	/** The password typed to open this stack or set in Protect Stack (not saved): seals a Private Access stack's XML
	 *  and encrypts its header on export. */
	String unlockedPassword;
	/** Random key that seals the picture files of a stack with a password (kept only inside the sealed _stack.xml). */
	byte[] contentKey;
	int getPasswordHash(){ return passwordHash; }
	boolean getPrivateAccess(){ return privateAccess; }
	/** Sets or clears (null/empty) the password, as Protect Stack's "Set Password…" / "No Password". */
	void setPassword(String password){
		if(password==null || password.isEmpty()){ passwordHash = 0; unlockedPassword = null; privateAccess = false; }
		else { passwordHash = StackProtection.passwordHash(password, legacyTextEncoding); unlockedPassword = password; }
	}
	void setPrivateAccess(boolean on){ privateAccess = on && passwordHash!=0; }
	Rectangle screenRect;
	Rectangle windowRect;
	Point scroll;
	/** The card window size saved in the stack when it is smaller than the card (logical). HyperCard opens such a stack in
	 *  that window, scrolled to {@link #scroll} (RDS experiments: 192x160 of a 1280x160 card). Kept in _stack.xml. */
	Dimension savedWindowSize;
	/** The card window's own size (logical): HyperCard shows this much of the card, never more than the card. Set by
	 *  set the rect of the card window; set the rect of this cd makes the window the whole card again. null = the card. */
	Dimension windowNominal;
	/** Converted from a stand-alone application (a stack with HyperCard's code, Cosmic Osmo): in HyperCard its resource
	 *  fork is the running application's, so its externals answer from any stack (TXcmd.externalOwner). Kept in _stack.xml. */
	boolean standaloneApplication;
	/** The resources (Rsrc) are in the order of the stack's resource map, which _stack.xml keeps ("resourceOrder"). A file
	 *  written before that has them in no particular order: they are put in ID order as read, as externals saw them then. */
	boolean resourceMapOrder = true;
	int firstBg;
	
	//追加プロパティ
	boolean resizable=false;
	
	//カード情報
	ArrayList<OCard> cdCacheList = new ArrayList<OCard>();
	ArrayList<OBackground> bgCacheList = new ArrayList<OBackground>();
	ArrayList<Integer> cardIdList = new ArrayList<Integer>();
	//HyperCard スタックの読み込み中に PAGE 表で印(marked)が付いていたカードの ID。CARD ブロックは PAGE の前にも後にも
	//来るので、全ブロックを読んだ後で HCData がカードへ移す。
	java.util.HashSet<Integer> importMarkedIds = new java.util.HashSet<Integer>();
	ArrayList<fontClass> fontList = new ArrayList<fontClass>();
	ArrayList<styleClass> styleList = new ArrayList<styleClass>();

	//「リーセント」メニュー用の、これまでに移動したカードの履歴（最新のものが末尾）。
	static final int MAX_RECENT_CARDS = 42; //本家HyperCardと同じ上限
	ArrayList<RecentCardEntry> recentCards = new ArrayList<RecentCardEntry>();

	//「戻る」で今どこまで遡っているか（0=一番最新＝現在のカード）。
	//「戻る」以外の普通の移動でrecordRecentCardが呼ばれると0に戻る。
	private int backIndex = 0;
	//「戻る」による移動の最中はtrue。この間はrecordRecentCardを呼んでも履歴を変更しない
	//（順序が変わってしまうと「戻る」を連打したときに正しく1つずつ遡れなくなるため）。
	private boolean navigatingBack = false;

	static class RecentCardEntry {
		int cardId;
		String label;
		BufferedImage thumbnail; //ピクチャが無いカードの場合はnull
		RecentCardEntry(int cardId, String label, BufferedImage thumbnail){
			this.cardId = cardId;
			this.label = label;
			this.thumbnail = thumbnail;
		}
	}

	//カードへの移動を履歴に記録する。既に履歴にあれば、最新として末尾に移動する。
	//サムネイルはこの時点のカードの絵（無ければ背景の絵）から作るので、呼び出すタイミングは
	//そのカードのpictがまだ有効なうち（removeDataでクリアされる前）にする必要がある。
	void recordRecentCard(OCard card){
		recordRecentCard(card, true);
	}

	//arriving: 移動先のカードとして記録する。そのカードのサムネイルが既にあればそれを使い、描き直さない
	//(カードを離れるときに描き直すので、履歴には離れたときの絵が残る。表示中のカードはリーセントの
	//ウインドウを開くときに描き直す: refreshRecentThumbnail)
	void recordRecentCard(OCard card, boolean render){
		if(card==null || navigatingBack) return;
		//lock recent の間はリーセント履歴に記録しない
		if(TUtil.compatibilityProperty("lockRecent").equalsIgnoreCase("true")) return;
		backIndex = 0;
		String label = (card.name!=null && !card.name.isEmpty()) ? card.name : ("id "+card.id);
		BufferedImage thumbnail = null;
		boolean found = false;
		for(int i=recentCards.size()-1; i>=0; i--){
			if(recentCards.get(i).cardId==card.id){ thumbnail = recentCards.remove(i).thumbnail; found = true; break; }
		}
		if(render || !found) thumbnail = makeRecentThumbnail(card);
		recentCards.add(new RecentCardEntry(card.id, label, thumbnail));
		while(recentCards.size()>MAX_RECENT_CARDS) recentCards.remove(0);
	}

	//開いてすぐ別のカードへ行ったとき(go card X of stack Y、戻ってきたウインドウのカード)、読み込みで仮に出した
	//最初のカードを履歴から除く。HyperCard は指定のカードを直接開くので、続く go back は前のスタックへ戻る
	//(Dark Dungeon の「出発」: go cd "eventc" of stack "DD stack2" → go back が DD stack2 の1枚目に行っていた)
	void forgetOpeningCard(int openingId){
		if(curCard==null || curCard.id==openingId || recentCards.size()<2) return;
		if(recentCards.get(0).cardId==openingId){ recentCards.remove(0); backIndex = 0; }
	}

	//表示中のカードの履歴のサムネイルを、今の絵で描き直す(リーセントのウインドウを開くとき)
	void refreshRecentThumbnail(){
		if(curCard==null) return;
		for(int i=recentCards.size()-1; i>=0; i--){
			RecentCardEntry entry = recentCards.get(i);
			if(entry.cardId==curCard.id){ entry.thumbnail = makeRecentThumbnail(curCard); return; }
		}
	}

	//ゴーメニューの「戻る」。履歴を1つずつ遡って移動する。連打するとさらに1つ前へ戻る。
	//これ以上遡れない場合は何もしない。
	boolean goBack(){
		int targetId = beginGoBack();
		if(targetId<0) return false;
		try{
			TTalk.doScriptforMenu("go to card id "+targetId);
		}catch(xTalkException e){
			e.printStackTrace();
		}finally{
			endBackNavigation();
		}
		return true;
	}

	//「戻る」の逆方向。goBackで遡った分だけ、新しい方へ1つずつ戻る。
	//「戻る」を使っていない（一番新しい位置にいる）場合は何もしない。
	boolean goForward(){
		int targetId = beginGoForward();
		if(targetId<0) return false;
		try{
			TTalk.doScriptforMenu("go to card id "+targetId);
		}catch(xTalkException e){
			e.printStackTrace();
		}finally{
			endBackNavigation();
		}
		return true;
	}

	/** マークされたカードの数 */
	int markedCardCount(){
		int n = 0;
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = null;
			try{ cd = OCard.getOCard(this, cardIdList.get(i), true); }catch(xTalkException e){}
			if(cd!=null && cd.marked) n++;
		}
		return n;
	}

	/** next/previous marked card: カレントカードから見て次/前のマークされたカード(端では回り込む)。無ければnull */
	OCard relativeMarkedCard(boolean forward){
		int count = cardIdList.size();
		if(count==0 || curCard==null) return null;
		int start = cardIdList.indexOf(curCard.id);
		for(int step=1; step<=count; step++){
			int idx = forward ? (start+step)%count : (start-step+count*2)%count;
			OCard cd = null;
			try{ cd = OCard.getOCard(this, cardIdList.get(idx), true); }catch(xTalkException e){}
			if(cd!=null && cd.marked) return cd;
		}
		return null;
	}

	//「recent card」(直前に表示していたカード)のid。無ければ-1。履歴は動かさない
	int recentCardId(){
		int targetIndex = recentCards.size()-2-backIndex;
		return targetIndex<0 ? -1 : recentCards.get(targetIndex).cardId;
	}

	//スクリプトの「go back」用。戻り先のカードidを返し(無ければ-1)、履歴を動かさない
	//モードに入る。移動が終わったらendBackNavigationを呼ぶこと。
	int beginGoBack(){
		int targetIndex = recentCards.size()-2-backIndex;
		if(targetIndex<0) return -1;
		backIndex++;
		navigatingBack = true;
		return recentCards.get(targetIndex).cardId;
	}

	//スクリプトの「go forth」用。beginGoBackの逆方向
	int beginGoForward(){
		if(backIndex<=0) return -1;
		int targetIndex = recentCards.size()-backIndex;
		if(targetIndex<0 || targetIndex>=recentCards.size()) return -1;
		backIndex--;
		navigatingBack = true;
		return recentCards.get(targetIndex).cardId;
	}

	void endBackNavigation(){
		navigatingBack = false;
	}

	private static final int RECENT_THUMBNAIL_WIDTH = 64;
	private static final int RECENT_THUMBNAIL_HEIGHT = 48;

	//カード移動のたびに呼ばれるので、カード全体の大きさの画像を作ってから縮めず、サムネイルの大きさに直接描く
	private static BufferedImage makeRecentThumbnail(OCard card){
		if(card==null || card.stack==null || card.stack.width<=0 || card.stack.height<=0) return null;
		BufferedImage thumb = new BufferedImage(RECENT_THUMBNAIL_WIDTH, RECENT_THUMBNAIL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = thumb.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.scale(RECENT_THUMBNAIL_WIDTH/(double)card.stack.width, RECENT_THUMBNAIL_HEIGHT/(double)card.stack.height);
		CardImageExport.paint(g, card);
		g.dispose();
		return thumb;
	}

	//get
	public String getName() {return name;}
	//ウィンドウタイトル用のスタック名(".xstk"を除いたもの)
	public String getTitleName() {
		String titleName = (name==null) ? "" : name;
		if(titleName.length()>5 && titleName.substring(titleName.length()-5).equals(".xstk")){
			titleName = titleName.substring(0,titleName.length()-5);
		}
		return titleName;
	}
	/** The card window's title an external gave it (SetWTitle: Rinaldi's WindName), null = the stack's name. HC 2.2 measured: it
	 *  names the card window (the name of cd window, the windows, window "title") and stays while the stack is open, go included. */
	public String cardWindowTitle;
	/** What the card window's title bar says and the window is called: {@link #cardWindowTitle}, else {@link #getTitleName()}. */
	public String getWindowTitle() {return cardWindowTitle!=null ? cardWindowTitle : getTitleName();}
	public ArrayList<String> getScript() {return scriptList;}
	public int getLeft() {return left;}
	public int getTop() {return top;}
	public int getWidth() {return width;}
	public int getHeight() {return height;}
	public Boolean getCantAbort() {return cantAbort;}
	public Boolean getCantDelete() {return cantDelete;}
	public Boolean getCantModify() {return cantModify;}
	public Boolean getCantPeek() {return cantPeek;}
	public Boolean getResizeable() {return resizable;}

	public void clean(){
		//files opened with open file stay open when the stack is left (HC J-2.3: open file, write, go stack X, write: both
		//writes are in the file; ACM's export goes to Radar chart meker between its writes). They close with the session
		//(PCARD.emptyWindow) or at quit
		Xcmd68k.stackClosing(this); //外部ウインドウを開いたままの68K externalも閉じる(xCloseEvt)
		CardWindow68k.stackClosing(this); //externalがカードに描いたもの(ColorizeHCの絵)を次のスタックに残さない
		TXcmd.stackClosing(this); //Terazzaのスプライトも
		if(curCard!=null){
			if(curCard.bg!=null){
				//curCard.bg.removeData();
				curCard.bg.clean();
			}
			//curCard.removeData();
			curCard.clean();
		}
		pcard = null;
		curCard = null;
		ScriptEditor.stackClosed(this); //the editor's tabs show this stack's objects: it goes with them
		scriptEditor = null;
		mainPane = null;
		Pattern = null;
		addColor = null;
		addColorHeapBytes = 0;
		cdCacheList = null;
		bgCacheList = null;
		cardIdList = null;
		fontList = null;
		styleList = null;
		saveXML = null;
		if(PCARD.pc.stack.usingStacks!=null && PCARD.pc.stack.usingStacks.contains(this)){
			return;
		}
		usingStacks = null;
		closedResources = rsrc; //a handler of this stack may still be running and call one of its externals (TXcmd.resourcesOf)
		rsrc = null;
		super.clean();
	}
	
	//set
	/**
	 * The Mac name of a Roman stack whose host file name was made by reading its Mac OS Roman bytes as Shift-JIS (CD images
	 * copied on a Japanese system: Info-Mac II's "How To Use…" is "How To Useﾉ", "Shakespeare’s" "Shakespeareﾕs"): the
	 * half-width katakana go back to the Roman characters, so scripts that compare the short name of this stack with
	 * "How To Use…" (Psych) match. Any other name is kept.
	 */
	static String macNameOfHostName(String hostName, StackTextEncoding encoding){
		if(hostName==null || encoding!=StackTextEncoding.ROMAN) return hostName;
		boolean halfWidthKana = false;
		for(int i=0; i<hostName.length(); i++){
			char c = hostName.charAt(i);
			if(c>=0xFF61 && c<=0xFF9F) halfWidthKana = true;
			else if(c>=0x3000) return hostName; //real Japanese text: leave it
		}
		if(!halfWidthKana) return hostName;
		for(String reading : FileManager68k.otherReadings(hostName)){
			boolean kana = false;
			for(int i=0; i<reading.length(); i++){ char c = reading.charAt(i); if(c>=0xFF61 && c<=0xFF9F || c>=0x3000 && c<0xFF00) kana = true; }
			if(!kana) return reading;
		}
		return hostName;
	}

	public void setName(String in) {
		name=in;
		//表示しているスタックだけがウィンドウの名前になる(start using で変換したライブラリが "DD lib" と題を書き換えていた)
		if(pcard!=null && pcard.stack==this) pcard.setTitle(name);
	}
	
	public void setTopLeft(int h, int v) {
		left=h;
		top=v;
		pcard.setBounds(left, top, PCARD.toPhysical(width)+pcard.chromeWidth(), PCARD.toPhysical(height)+pcard.chromeTop()+pcard.chromeBottom());
	}
	/** set the rect of this cd to 0,0,384,340 (Year Calendar's Large card): the card size of the whole stack. The card
	 *  window becomes the whole new card, centred where it was and kept on the screen; the card and background pictures are
	 *  laid out at that size. */
	public void setRect(int v1, int v2, int v3, int v4) {
		//HyperCard J-2.3 and 2.4 (68K VM, 2026-10-08): the card is 0,0-based and its width a multiple of 32 (400 -> 416);
		//the window takes the new card size (also a window made smaller with set the rect of cd window), keeps its centre
		//and is pushed back onto the screen (16 px from the sides, below the menu bar). 勇者の石 grows its window from
		//120x72 to 480x290 by setting the card rect four times.
		left=0;
		top=0;
		windowNominal = null;
		width=Math.max(32, (v3-v1+31)/32*32);
		height=Math.max(1, v4-v2);
		if(pcard==null) return;
		PCARD.runOnEDT(() -> {
			java.awt.Rectangle r = pcard.getBounds();
			int extraW = 0, extraH = 0;
			if(PCARD.pc!=null && PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){ extraW = PCARD.pc.toolbar.getTWidth(); extraH = PCARD.pc.toolbar.getTHeight(); }
			int w = PCARD.toPhysical(windowWidth())+extraW+pcard.chromeWidth();
			int h = PCARD.toPhysical(windowHeight())+extraH+pcard.chromeTop()+pcard.chromeBottom();
			int x = r.x+r.width/2-w/2, y = r.y+r.height/2-h/2;
			if(pcard.isShowing()){
				java.awt.GraphicsConfiguration gc = pcard.getGraphicsConfiguration();
				if(gc!=null){
					java.awt.Rectangle sb = gc.getBounds();
					java.awt.Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
					int sl = sb.x+in.left, st = sb.y+in.top, sr = sb.x+sb.width-in.right, sbm = sb.y+sb.height-in.bottom;
					int margin = PCARD.toPhysical(16);
					x = Math.max(sl+margin, Math.min(x, sr-margin-w));
					x = Math.max(sl, x);
					y = Math.max(st, Math.min(y, sbm-h));
					y = Math.max(st, y);
				}
			}
			pcard.setBounds(x, y, w, h);
			pcard.validate(); //laid out at once: the script reads the card window's rect right after
			if(MyLabel.cdlabel!=null) MyLabel.cdlabel.setBounds(0, 0, width, height);
			if(MyLabel.bglabel!=null) MyLabel.bglabel.setBounds(0, 0, width, height);
			if(pcard.mainPane!=null) pcard.mainPane.repaint();
		});
	}
	public void setCantAbort(Boolean in) {cantAbort=in;}
	public void setCantDelete(Boolean in) {cantDelete=in;}
	public void setCantModify(Boolean in) {cantModify=in;}
	public void setCantPeek(Boolean in) {cantPeek=in;}
	public void setResizeable(Boolean in) {
		resizable=in;
		pcard.setResizable(resizable); 
	}
	/** The card window's scroll (h,v in card coordinates): which part of the card a window smaller than the card shows. */
	public void setScroll(int x, int y){
		scroll = new Point(x,y);
		if(pcard==null || pcard.mainPane==null) return;
		PCARD.runOnEDT(() -> {
			java.awt.Container view = pcard.mainPane.getParent();
			if(view==null) return;
			view.invalidate();
			view.validate();
			pcard.mainPane.repaint();
		});
	}
	
	//メイン
	public OStack(PCARDFrame pc) {
    	objectType="stack";
    	
    	pcard = pc;
    	//PCARD.pc.stack = this;
		GUI_btn = new GButton();
		GUI_radio = new GRadio();
		GUI_check = new GCheckBox();
		GUI_popup = new GPopup();
		GUI_fld = new GField();
		mainPane = pc==null ? null : pc.mainPane;

    	if(mainPane!=null){
    		mainPane.setLayout(null);
    		mainPane.setBackground(Color.WHITE);
    	}
		
		try{
			//UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
			//UIManager.setLookAndFeel("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
			//UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
			//UIManager.setLookAndFeel("com.apple.laf.AquaLookAndFeel");
		} catch ( Exception e ) {
			  System.out.println("LookAndFeel：" + e );
		}
		
		
		left=0; top=0;
		width=512; height=384;
		
		fontTableID = (int)(20000*Math.random()+1000);
		styleTableID = (int)(20000*Math.random()+1000);
		listId = (int)(20000*Math.random()+1000);
		
		//mainPane.setBounds(left, top, width, height+pcard.getInsets().top);
    }

	
    @SuppressWarnings("unchecked")
	public void buildStackFile(boolean isUsingOnly)
    {
    	if(scriptList==null){
    		scriptList = new ArrayList<String>();
    	}
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		handlerLineList = null;
    	
		if(isUsingOnly){
			return;
		}
		
		if(PCARD.home!=null){
			usingStacks.add(PCARD.home);
		}
		
		
		if(PCARD.pc.toolbar==null){
			PCARD.pc.toolbar = new GToolBar(PCARD.pc);
		}
		
		String sepStr = ""+File.separatorChar;
		if(File.separatorChar=='\\'){
			sepStr = "\\\\";
		}
    	String[] strArray = path.split(sepStr);
		//name=strArray[strArray.length-1];
    	if(strArray.length-2>=0){
    		//HyperCard のスタック名はファイル名。展開形式のフォルダ "X.hzb" のスタックは "X"(the short name of this stack、
    		//ウインドウ名。以前は "HyperPiece 2.hzb" になり、HyperPiece 2 のパス表示やスタック名の比較が合わなかった)
    		name=strArray[strArray.length-2].replaceFirst("(?i)\\.hzb$", "");
    	}else{
    		name=path;
    	}
		//the host may keep the name decomposed (an unpacked "はいぱあれもん" has ぱ as は + U+309A): a script compares it with
		//what it typed, which is composed (the short name of this stack is "はいぱあれもん vol.4" was false)
		name = java.text.Normalizer.normalize(macNameOfHostName(name, legacyTextEncoding), java.text.Normalizer.Form.NFC);
		pcard.setTitle(getTitleName());

		//2倍表示モードでこのスタックのウインドウが画面に収まるかどうかを判定しておく
		//(以降のPCARD.scale()の結果に反映される)
		PCARD.updateZoomFitsScreen(width, height);

		int scaledW = PCARD.toPhysical(width);
		int scaledH = PCARD.toPhysical(height);
		mainPane.setBounds(left, top, scaledW, scaledH+pcard.getInsets().top);
		mainPane.setVisible(true);
		Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
		if(d!=null){
			pcard.setBounds(d.width/2-scaledW/2, d.height/2-scaledH/2-20, scaledW, scaledH+20);
		}else{
			pcard.setBounds(left, top-scaledH-20, scaledW, scaledH+20);
		}
		pcard.setVisible(true);
		//pcard.setLocationRelativeTo(null);
		pcard.setResizable(resizable); 
		//HyperCard J-2.3 and 2.4 open a stack with the card window as big as the card (on the screen) and not scrolled, whatever
		//window rect and scroll the header saved (measured with script-less stacks; RDS experiments sets its 192x160 window and
		//scroll in its own openCard). Shattered Stone saved a scroll of 64,0 and opened cut off at the left.
		windowNominal = null;
		if(scroll!=null) scroll = new Point(0, 0);
		setNewBounds();
		
		PCARD.pc.msg.setBounds(PCARD.pc.getX()+PCARD.pc.getWidth()/2-240,PCARD.pc.getY()+PCARD.pc.getHeight(),480,28+PCARD.pc.getInsets().top);

		
		//1番目のカードを開く
		if(cardIdList.size()>=1){
			mainPane.removeAll();
			try {
				curCard = OCard.getOCard(this, cardIdList.get(0), false);
				curCard.bg = OBackground.getOBackground(this, curCard, curCard.bgid, false);
				curCard.parent = curCard.bg;
			} catch (xTalkException e) {
				e.printStackTrace();
				curCard = cdCacheList.get(0);
				try {
					curCard.bg = OBackground.getOBackground(this, curCard, curCard.bgid, false);
					curCard.parent = curCard.bg;
				} catch (xTalkException e2) {
					e2.printStackTrace();
				}
			}

	    	PCARD.lockedScreen = false;
			mainPane.repaint();
			//「リーセント」メニュー用に、開いた直後のカードも履歴に記録しておく
			recordRecentCard(curCard);
			//TTalk.talk = new TTalk();
			//TTalk.talk.start();
			TTalk.CallMessage("startUp",curCard);
			TTalk.CallMessage("openStack",curCard);
			TTalk.CallMessage("openBackground",curCard);
			TTalk.CallMessage("openCard",curCard);
		}
		
		//変更監視&XML保存のスレッドを立ち上げておく
		if(!System.getProperty("java.version").startsWith("1.5")){ //JRE1.5.xは非対応
			saveXML = new XMLwrite(this);
		}
    }

    public void setNewBounds(){
    	Rectangle r = PCARD.pc.stack.pcard.getBounds();
		//Windows/Linuxではメニューバーがウインドウ内(カードの上)にあるので、その高さも足す(足りないとカードの下端が欠ける)
		PCARD.pc.stack.pcard.setBounds(r.x, r.y, PCARD.toPhysical(windowWidth())+PCARD.pc.toolbar.getTWidth()+pcard.chromeWidth(), PCARD.toPhysical(windowHeight())+PCARD.pc.toolbar.getTHeight()+pcard.chromeTop()+pcard.chromeBottom());
    }

    /** The part of the card the card window shows (logical): its own size limited to the card. */
    int windowWidth(){ return windowNominal==null ? width : Math.max(1, Math.min(width, windowNominal.width)); }
    int windowHeight(){ return windowNominal==null ? height : Math.max(1, Math.min(height, windowNominal.height)); }

    
	void openStackFile(String inpath, boolean isUsingOnly){ openStackFile(inpath, isUsingOnly, false); }
	/** {@code newWindow}: a stack on show stays open in a window of its own (GStackWindow) instead of closing */
	void openStackFile(String inpath, boolean isUsingOnly, boolean newWindow)
	{
		Sandbox.stackOpened(new File(inpath)); //開いたスタック: 自分のフォルダ(.hzb)は自由に、周りのフォルダは読むだけ(他のファイルの変更は確認)
		/*this.scriptList = new ArrayList<String>();
		
		if(PCARD.pc.stack.curCard!=null){
			if(PCARD.pc.stack.curCard.bg!=null) {
				PCARD.pc.stack.curCard.bg.removeData();
				PCARD.pc.stack.curCard.bg = null;
			}
			PCARD.pc.stack.curCard.removeData();
			PCARD.pc.stack.curCard = null;
		}
		*/

		// ZIP は画像／リソース判定より先に展開する。簡易ウインドウの
		// ドロップはこの同期経路を通るため、ここを逃すと ZIP 自体が
		// アイコンエディタへ渡されてしまう。
		if(!isUsingOnly&&ZipExpander.isZip(new File(inpath))){
			File expanded=expandZipArchive(new File(inpath));
			if(expanded==null)return;
			inpath=expanded.getPath();
		}

		//ディスクイメージ(.img/.dsk/.smiなど)はMacプログラム用のディスクとしてマウントする
		if(!isUsingOnly && DiskImages.openFromUser(new File(inpath))) return;

		//68K Macアプリケーション(リソースフォークにCODE 0を持つファイル)は、スタックとしてではなく
		//内蔵68K VMで起動する(Wineのように、複数同時起動可)。
		if(isUsingOnly==false && launch68kApplication(inpath)) return;

		BufferedImage bi = null;
		//a stack's own _stack.xml / toc.xml is no picture (decoding the XML as a PICT took minutes)
		boolean xml = inpath.toLowerCase(java.util.Locale.ROOT).endsWith(".xml");
		boolean tryAsMedia = isUsingOnly==false && !xml && !looksLikeHyperCardStack(inpath) && !stackFileType(inpath) && !StackBundle.isPacked(new File(inpath));
		if(tryAsMedia){
			//画像ファイルかどうか
			try{
				bi = PictureFile.loadPbm(inpath);
				if(bi==null){
					bi = javax.imageio.ImageIO.read(new File(inpath));
				}
				if(bi==null){
					bi = PictureFile.loadPICT(inpath);
				}
			} catch (Exception e2) {
				//e2.printStackTrace();
			}
    	}

		boolean openedResourceEditor = false;
		if(bi==null && tryAsMedia && new File(inpath).isFile()){
			//データフォークからは画像として読めなくても、リソースフォークにHyperCard用の
			//各種リソース（PICT/ICON/snd等）が入っている場合がある。HyperCardスタックの
			//変換と同様にフォルダへ変換し、リソースエディタで開けるようにする。
			openedResourceEditor = openResourceForkInEditor(inpath);
		}

		if(bi!=null){
			TTalk.CallMessage("edit picture "+TTalk.quoteLiteral(inpath), "", null, false, true);
		}else if(openedResourceEditor){
			//リソースエディタを開けたので、スタックとして開こうとはしない。
		}else if(isUsingOnly==false){
			TTalk.CallMessage("open stack "+TTalk.quoteLiteral(inpath)+(newWindow ? " in a new window" : ""), "", null, false, true);
		}else if(isUsingOnly==true){
			TTalk.CallMessage("start using stack "+TTalk.quoteLiteral(inpath), "", null, false, true);
		}
    }

	//リソースフォークをHyperCardスタック変換と同じ要領でフォルダに変換し、
	//「リソース編集…」メニューと同じリソースエディタで開く。
	//普段開いている本来のスタックには一切手を触れない、独立したウィンドウ/スタックとして開く。
	//変換できるリソースが1つもなければfalseを返す。
	static boolean openResourceForkInEditor(String path) {
		PCARDFrame resFrame = new PCARDFrame();
		if(PCARD.pc!=null) resFrame.setBounds(PCARD.pc.getBounds());
		OStack resStack = new OStack(resFrame);
		resFrame.stack = resStack;
		if(!HCConvert.convertResourceForkToFolder(path, resStack)) return false;
		new ResEditIndex(resFrame);
		return true;
	}

	/**
	 * Expands a ZIP archive beside itself and returns the stack to open (a stack file, a MacBinary stack or a
	 * .hzb folder), or null when nothing is to be opened: the archive could not be read, or it held a 68K
	 * application or other files. Those are not launched; the expanded item is shown in the Finder / Explorer
	 * instead, and where that is impossible a dialog tells where the contents went and offers to launch the
	 * application.
	 */
	static File expandZipArchive(File zip) {
		ZipExpander.Result result;
		try {
			result = ZipExpander.expand(zip);
		} catch (IOException e) {
			new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Could't expand the archive.")+"\n"+e.getMessage(),
					null,"OK",null,null);
			return null;
		}
		File opened = result.opened;
		//スタックと読み物だけのアーカイブ(フォルダに展開される)は、そのただ一つのスタックを開く。
		if(opened.isDirectory()) {
			File single = singleStackIn(opened, 2);
			if(single!=null) opened = single;
		}
		boolean stack = opened.isFile() ? looksLikeHyperCardStack(opened.getPath())
			: opened.isDirectory() && (opened.getName().toLowerCase().endsWith(".hzb") || new File(opened,"_stack.xml").isFile() || new File(opened,"toc.xml").isFile());
		if(stack) return opened;
		//68Kアプリケーションは展開しただけでは起動しない。展開先をFinder/エクスプローラーで見せる。
		if(revealInFileManager(opened)) return null;
		//見せられない環境では展開結果をダイアログで伝え、「アプリケーションを起動」を選んだときだけ起動する。
		boolean application = opened.isFile() && MacApp68k.isApplication(opened);
		String message = PCARD.pc.intl.getDialogText("The archive was expanded to:")+"\n"+opened.getPath();
		if(!application){
			new GDialog(PCARD.pc, message, null,"OK",null,null);
			return null;
		}
		String launch = PCARD.pc.intl.getDialogText("Launch the application");
		new GDialog(PCARD.pc, message, null, launch, null, "OK");
		return launch.equals(GDialog.clicked) ? opened : null;
	}

	/** The one HyperCard stack in a folder or its subfolders (up to {@code depth} levels), or null with none or several. */
	static File singleStackIn(File folder, int depth) {
		java.util.List<File> found = new java.util.ArrayList<File>();
		collectStacks(folder, depth, found);
		return found.size()==1 ? found.get(0) : null;
	}
	private static void collectStacks(File folder, int depth, java.util.List<File> found) {
		File[] children = folder.listFiles();
		if(children==null) return;
		for(File child: children) {
			if(found.size()>1) return;
			if(child.getName().startsWith(".")) continue;
			if(child.isFile() ? looksLikeHyperCardStack(child.getPath()) : child.getName().toLowerCase().endsWith(".hzb")) found.add(child);
			else if(child.isDirectory() && depth>0 && !child.getName().equals("__MACOSX")) collectStacks(child, depth-1, found);
		}
	}

	/**
	 * Shows an item in the platform's file manager: a file selected in its folder (Finder, Explorer) or, when
	 * selection is not available, its folder opened; a folder is opened itself. Returns false when no file
	 * manager could be reached, so the caller can fall back to telling the user the path.
	 */
	static boolean revealInFileManager(File item) {
		File folder = item.isDirectory() ? item : item.getParentFile();
		java.awt.Desktop desktop = null;
		try {
			if(java.awt.Desktop.isDesktopSupported()) desktop = java.awt.Desktop.getDesktop();
		} catch(Exception ignored) {
		}
		if(desktop!=null && item.isFile() && desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)){
			try { desktop.browseFileDirectory(item); return true; } catch(Exception ignored) {}
		}
		String os = System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
		if(os.startsWith("windows") && item.isFile()){ //JavaのBROWSE_FILE_DIRはWindowsでは未対応なのでExplorerに直接頼む
			try { new ProcessBuilder("explorer.exe","/select,"+item.getAbsolutePath()).start(); return true; } catch(Exception ignored) {}
		}
		if(folder==null || !folder.isDirectory()) return false;
		if(desktop!=null && desktop.isSupported(java.awt.Desktop.Action.OPEN)){
			try { desktop.open(folder); return true; } catch(Exception ignored) {}
		}
		return false;
	}

	/**
	 * Shows where an item is kept: selected in its parent folder even when it is itself a folder (a .hzb stack,
	 * a Mac OS 9 "X.app" package), instead of opening its contents. Falls back to {@link #revealInFileManager}.
	 */
	static boolean revealLocationInFileManager(File item) {
		File parent = item.getAbsoluteFile().getParentFile();
		if(!item.isDirectory() || parent==null) return revealInFileManager(item);
		String os = System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
		try {
			if(os.startsWith("mac")){ new ProcessBuilder("open","-R",item.getAbsolutePath()).start(); return true; }
			if(os.startsWith("windows")){ new ProcessBuilder("explorer.exe","/select,"+item.getAbsolutePath()).start(); return true; }
		} catch(Exception ignored) {
		}
		try {
			if(java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)){
				java.awt.Desktop.getDesktop().browseFileDirectory(item); return true;
			}
		} catch(Exception ignored) {
		}
		return revealInFileManager(parent);
	}

	//68K Macアプリなら起動してtrueを返す。
	static boolean launch68kApplication(String path) {
		File file = MacApp68k.programOf(new File(path)); // a Mac OS 9 package ("X.app" folder) runs the program inside
		if(!file.isFile()) return false;
		boolean accessory = false;
		File openDoc = MacApp68k.isOpenDocDocument(file) ? MacApp68k.openDocProgram() : null;
		if(openDoc!=null){ // an OpenDoc document opens in OpenDoc™ as the Finder does it (that runs the document's own process)
			try {
				MacApp68k.launch(openDoc, new java.util.ArrayList<File>(java.util.Collections.singletonList(file))); // the launcher lists it as OpenDoc™'s document
			} catch (IOException e) {
				new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Can't launch the Mac application.")+"\n"+e.getMessage(), null,"OK",null,null);
			}
			return true;
		}
		if(!MacApp68k.isApplication(file)){ // a Finder alias to an application launches the application
			accessory = MacApp68k.isDeskAccessory(file); //デスクアクセサリ('dfil')はそれだけのプロセスで開く(System 7のDA Handler)
			if(!accessory){
				File target=MacApp68k.resolveAliasFile(file);
				if(target==null) return false;
				file=target;
			}
		}
		try {
			if(accessory) MacApp68k.openDeskAccessory(file, null);
			else MacApp68k.launch(file, null);
			if(LauncherStore.enabled) LauncherStore.get().recordOpened(file, LauncherStore.KIND_APP); //ランチャーの履歴(起動できたものだけ)
			return true;
		} catch (IOException e) {
			if(GLauncher.enabled() && !PCARD.pc.isVisible()) GLauncher.showFailure(file.getPath(), e.getMessage()); //ランチャーだけのとき: そこに理由を出す
			else new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Can't launch the Mac application.")+"\n"+e.getMessage(),
					null,"OK",null,null);
			return true;
		}
	}

	/** The Finder type says stack ('STAK'): a damaged data fork is reported as a stack that cannot be read, not tried as a
	 *  picture (11:22:63 EXTRA!, Info-Mac II: its data fork starts in the middle of a script). */
	static boolean stackFileType(String path) {
		byte[] info = FinderInfo68k.stored(new File(path));
		return info!=null && info.length>=4 && info[0]=='S' && info[1]=='T' && info[2]=='A' && info[3]=='K';
	}

	static boolean looksLikeHyperCardStack(String path) {
		byte[] header = new byte[128];
		try (FileInputStream input = new FileInputStream(path)) {
			int length = input.read(header);
			// 通常のデータフォークではブロック長の直後がSTAK。
			if(length>=8 && header[4]=='S' && header[5]=='T' &&
				header[6]=='A' && header[7]=='K') return true;
			// PowerPC 版 HyperCard がコードフラグメントを前置したスタック("HCSApwpcpeff"、HCData.stackOffset)
			if(length>=12 && header[0]=='H' && header[1]=='C' && header[2]=='S' && header[3]=='A' &&
				header[4]=='p' && header[5]=='w' && header[6]=='p' && header[7]=='c') return true;
			// MacBinaryではFinder typeがヘッダの65..68に入る。
			return length>=69 && header[65]=='S' && header[66]=='T' &&
				header[67]=='A' && header[68]=='K';
		} catch (IOException e) {
			return false;
		}
	}
    
	//読み込み完了後の処理。"start using stack"(isUsingOnly)ではカレントスタックを切り替えず、
	//スクリプト表だけ用意する(successOpenFileはカレントスタックのUIとXML保存スレッドを立ち上げる)。
	private void finishOpen(boolean isUsingOnly)
	{
		if(!isUsingOnly && standaloneApplication) TXcmd.applicationOpened(this);
		if(isUsingOnly){
			buildStackFile(true);
			//a HyperCard stack converted only to be used or read (start using, set cantPeek of stack "前編"): the .hzb
			//gets its _stack.xml now, so the next reference uses the conversion instead of converting the file again
			//into "前編 2.hzb", "前編 3.hzb", …
			if(file!=null && file.getName().equals("_stack.xml") && !file.exists() && file.getParentFile()!=null && file.getParentFile().isDirectory())
				XMLwrite.writeOnce(this);
		}
		else PCARD.pc.successOpenFile();
	}

    boolean openStackFileInThread(String inpath, boolean isUsingOnly)
    {
    	
    	path = inpath;
		file = new File(path);
		if(!file.exists()){
			//start using a stack that is not there (Colorize and Pictoid Template: start using stack "Pictoid XCMD"): the
			//current stack stays as it is. failureOpenFile took the open card's parts away (a blank card) and said so.
			if(!isUsingOnly) PCARD.pc.failureOpenFile(inpath);
			return false;
		}
		//スクリプトからの go/open/start using: ユーザが開いた場所とHyperZebra HDの外なら許可を求める(Sandbox)
		if(!Sandbox.allowFile(file, Sandbox.Access.READ, Sandbox.scriptRequester())){
			name="";
			return false;
		}
		//1ファイル形式のスタック(.hzb の ZIP): 作業フォルダに展開して、従来の .hzb フォルダと同じように開く(StackBundle)
		if(StackBundle.isPacked(file)){
			try{
				file = StackBundle.open(file);
			}catch(IOException e){
				System.err.println("Could not open the stack file "+inpath+": "+e.getMessage());
				name="";
				if(!isUsingOnly) PCARD.pc.failureOpenFile(inpath);
				return false;
			}
			inpath = file.getPath();
			path = inpath;
		}
		if(!isUsingOnly && ZipExpander.isZip(file)){
			//zip書庫(Finderの「圧縮」やHyperZebraのポータブルパッケージ)はまず展開する。
			//__MACOSX以下のAppleDoubleは各ファイルの「._名前」サイドカー(macOSでは実フォーク)になる。
			File expanded = expandZipArchive(file);
			if(expanded==null){
				name="";
				return false; //展開だけ済ませたか、失敗をダイアログで伝えた
			}
			inpath = expanded.getPath();
			path = inpath;
			file = expanded;
		}
		if(!isUsingOnly && DiskImages.openFromUser(file)){
			name="";
			return false; //ディスクイメージはマウントした
		}
		if(!isUsingOnly && launch68kApplication(inpath)){
			name="";
			return false; //呼び出し側で元のスタックに戻す
		}

		this.scriptList = new ArrayList<String>();
		
		{
			String STAKstr = null;
			//先頭8バイトだけ見て閉じる(以前はストリームを閉じずに放置していた)
			try (FileInputStream fstream = new FileInputStream(path)) {
				//HCスタックとして読んでみる
				STAKstr = "";
				for(int i=0; i<4; i++){
					fstream.read();
				}
				for(int i=0; i<4; i++){
					STAKstr += (char)fstream.read();
				}
			} catch (FileNotFoundException e) {
			} catch (IOException e) {
				e.printStackTrace();
			}
			//a data fork that starts like a stack but did not convert: openHCStack has already pointed path at the conversion
			//(an .hzb folder that is not there), so the check below must not go by path (UbiQUE™ XFCN from a damaged archive got
			//"error reading the XML data… opened write-protected")
			boolean damagedStack = false;
			if(STAKstr != null){
				if(STAKstr.equals("STAK") || STAKstr.equals("pwpc")){ //"HCSApwpcpeff": PowerPC 版 HyperCard がスタックの前に付けるコードフラグメント(HCData.stackOffset)
					//これはきっとHyperCardスタックファイル
					final File rawStack = new File(path); //openHCStack points path at the conversion
					if(true == HCConvert.openHCStack(path, this)){
						if(this.cdCacheList.size()>0){
							standaloneApplication = MacApp68k.isStandaloneStack(rawStack);
							finishOpen(isUsingOnly);
							return true;
						}
						damagedStack = true;
					}
					else if(HCConvert.cancelled){
						//文字コードの選択をキャンセル: 黙って戻る(以前はXMLとして読みに行って「読み込みに失敗」を出していた)
						name="";
						return false;
					}
					else damagedStack = true;
				}
				else if(true/*path.substring(path.length()-4).equals(".bin")*/){
					//MacBinary形式では？
					if(true == HCConvert.openMacBinaryStack(path, this)){
						if(this.cdCacheList.size()>0){
							finishOpen(isUsingOnly);
							return true;
						}
					}
					else if(HCConvert.cancelled){
						name="";
						return false;
					}
				}
			}
			//Finder の種類が STAK なのにスタックとして読めなかったファイルは壊れたスタック: XML として読みに行くと
			//「XMLデータの読み込み中にエラー…書き込み禁止で開きます」になるので、開けなかったことだけを伝える
			if(damagedStack || (new File(path).isFile() && stackFileType(path))){
				System.err.println("damaged HyperCard stack (the data fork is not a stack): "+inpath);
			}
			//XML形式として読む
			else if(System.getProperty("java.version").startsWith("1.5")){
				new GDialog(PCARD.pc, PCARDFrame.pc.intl.getDialogText("This version of Java Runtime is not supported streaming API for XML."),
						null,"OK",null,null);
			}
			else{
				if(new File(path).isDirectory()){
					path += File.separatorChar+"_stack.xml";
					if(!new File(path).exists()){
						path = inpath + File.separatorChar+"toc.xml";
					}
				}
				
				if(XMLRead.readToc(path, this)){
					//Private Access: パスワードを聞く(封印されたXMLなら読み込み時に聞き済み)
					if(!StackProtection.unlockOnOpen(this)){
						name="";
						return false;
					}
					if(this.cdCacheList.size()>0){
						file = new File(path);
						finishOpen(isUsingOnly);
						return true;
					}
				}
				else if(XMLRead.lockedOut){
					name="";
					return false;
				}
			}
		}

		name="";
		if(!isUsingOnly) PCARD.pc.failureOpenFile(inpath);
		return false;
    }

    
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
		int pattern_id = 0;
		resourceMapOrder = false; //until the file says its resources are in the map's order
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("cardCount")){  }
	            	else if(elm.equals("backgroundCount")){  }
	            	else if(elm.equals("stackID")){  }
	            	else if(elm.equals("format")){  }
				else if(elm.equals("legacyTextEncoding")){
					String encodingName = reader.getElementText();
					try { legacyTextEncoding = StackTextEncoding.valueOf(encodingName); }
					catch(IllegalArgumentException ignored) { legacyTextEncoding = StackTextEncoding.JAPANESE; }
				}
	            	else if(elm.equals("cardID")){ firstCard = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("firstCardID")){ firstCard = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("firstBackgroundID")){ firstBg = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("listID")){ listId = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("password")){ passwordHash = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("contentKey")){ contentKey = java.util.Base64.getDecoder().decode(reader.getElementText().trim()); }
	            	else if(elm.equals("userLevel")){ userLevel = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("cantModify")){ cantModify = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantDelete")){ cantDelete = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("privateAccess")){ privateAccess = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantAbort")){ cantAbort = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantPeek")){ cantPeek = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("createdByVersion")){ createdByVersion = reader.getElementText(); }
	            	else if(elm.equals("lastCompactedVersion")){ lastCompactedVersion = reader.getElementText(); }
	            	else if(elm.equals("lastEditedVersion")){ lastEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("modifyVersion")){ lastEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("firstEditedVersion")){ firstEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("openVersion")){ firstEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("version")){ reader.getElementText(); }
	            	else if(elm.equals("fontTableID")){ fontTableID = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("styleTableID")){ styleTableID = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("cardSize")){  }
	            	else if(elm.equals("standaloneApplication")){ standaloneApplication = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("resourceOrder")){ resourceMapOrder = "map".equals(reader.getElementText().trim()); }
	            	else if(elm.equals("windowSize") || elm.equals("windowScroll")){
	            		String[] v = reader.getElementText().split(",");
	            		if(v.length==2){
	            			try{
	            				int a = Integer.parseInt(v[0].trim()), b = Integer.parseInt(v[1].trim());
	            				if(elm.equals("windowSize")) savedWindowSize = new Dimension(a, b);
	            				else scroll = new Point(a, b);
	            			}catch(NumberFormatException ignored){}
	            		}
	            	}
	            	else if(elm.equals("width")){ width = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("height")){ height = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("patterns")){  }
	            	else if(elm.equals("pattern")){ 
	            		if(pattern_id<Pattern.length){
	            			Pattern[pattern_id] = reader.getElementText();
		            		pattern_id++;
	            		}
	            	}
	            	else if(elm.equals("osaScript")) osaScript = OsaScript.readXML(reader);
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		//末尾の空行を保つため、limit引数に-1を指定する
	            		String[] scriptAry = scrStr.split("\n", -1);
	            		for(int i=0; i<scriptAry.length; i++)
	            		{
	            			scriptList.add(scriptAry[i]);
	            		}
	            	}
	            	else if(elm.equals("true")); //dummy 
	            	else if(elm.equals("false")); //dummy 
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("stack")){
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
	
	
	public XMLStreamReader readFontXML(XMLStreamReader reader) throws Exception {
        String idStr = "0", nameStr = "";
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("id")){ idStr = reader.getElementText(); }
	            	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("font")){
	            		fontList.add(new fontClass(Integer.valueOf(idStr), nameStr));
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
	        }
	    }
	    return reader;
	}
	
	/** HyperCard 1.x のスタック(FTBL 無し)向け: パートのフォントIDは Mac 標準のフォントID */
	void addClassicFontTable(){
		Object[][] classic = {{0,"Chicago"},{1,"Geneva"},{2,"New York"},{3,"Geneva"},{4,"Monaco"},{5,"Venice"},{6,"London"},
			{7,"Athens"},{8,"San Francisco"},{9,"Toronto"},{11,"Cairo"},{12,"Los Angeles"},{13,"Zapf Dingbats"},{14,"Bookman"},
			{15,"Helvetica Narrow"},{16,"Palatino"},{18,"Zapf Chancery"},{20,"Times"},{21,"Helvetica"},{22,"Courier"},{23,"Symbol"},
			{24,"Mobile"},{16384,"Osaka"},{16386,"本明朝−Ｍ"},{16387,"丸ゴシック−Ｍ"},{16436,"Osaka−等幅"}};
		for(Object[] f : classic){
			boolean known = false;
			for(fontClass known1 : fontList) if(known1.id==(Integer)f[0]) known = true;
			if(!known) fontList.add(new fontClass((Integer)f[0], (String)f[1]));
		}
	}

	class fontClass{
		int id;
		String name;
		public fontClass(int id, String name){
			this.id = id;
			this.name = name;
		}
	}
	
	
	public XMLStreamReader readStyleXML(XMLStreamReader reader) throws Exception {
		String idStr = "-1", sizeStr = "-1", fontStr = "-1", textStyle = "";
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("nextStyleID")){ nextStyleID = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textStyle")){ textStyle += reader.getElementText()+" "; }
	            	else if(elm.equals("id")){ idStr = reader.getElementText(); }
	            	else if(elm.equals("font")){ fontStr = reader.getElementText(); }
	            	else if(elm.equals("size")){ sizeStr = reader.getElementText(); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("nextStyleID")){
	            		break;
	            	}
	            	if(elm.equals("styleentry")){
	            		styleList.add(new styleClass(idStr, textStyle, fontStr, sizeStr));
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
	        }
	    }
	    return reader;
	}
	
	class styleClass{
		int id;
		int style;//-1ならチェンジしない
		int font;//-1ならチェンジしない
		int size;//-1ならチェンジしない
		public styleClass(String id, String style, String font, String size){
			this.id = Integer.valueOf(id);
			this.style=0;
			if(style.equals("")) this.style=-1;
			if(style.contains("plain")) this.style=0;
			if(style.contains("bold")) this.style+=1;
			if(style.contains("italic")) this.style+=2;
			if(style.contains("underline")) this.style+=4;
			if(style.contains("outline")) this.style+=8;
			if(style.contains("shadow")) this.style+=16;
			if(style.contains("condensed")) this.style+=32;
			if(style.contains("extend")) this.style+=64;
			if(style.contains("group")) this.style+=128;
			this.font = Integer.valueOf(font);
			this.size = Integer.valueOf(size);
		}
		styleClass(int id, int style, int font, int size){
			this.id=id;
			this.style=style;
			this.font=font;
			this.size=size;
		}
		/** 表示に使う文字サイズ(-1は継承)。STBLのサイズ0はQuickDrawの「システムフォントの
		 *  大きさ」(12)。他のアプリからペーストされたTextEditのスタイルがそのまま残った
		 *  スタックにあり(HCspdmetorの参考データ)、0ptで描くと行が潰れて何も見えなかった。 */
		int displaySize(){
			return size==0 ? 12 : size;
		}
	}

	int ensureFontForStyle(String fontName){
		for(fontClass entry : fontList){
			if(entry.name.equals(fontName)) return entry.id;
		}
		int id = 1;
		for(fontClass entry : fontList) id = Math.max(id, entry.id+1);
		fontList.add(new fontClass(id, fontName));
		return id;
	}

	/** A style-table entry from raw values (-1 = the attribute is not overridden). */
	int addRawTextStyle(int style, int font, int size){
		for(styleClass entry : styleList) if(entry.style==style && entry.font==font && entry.size==size) return entry.id;
		int id = Math.max(1, nextStyleID);
		for(styleClass entry : styleList) id = Math.max(id, entry.id+1);
		nextStyleID = id+1;
		styleList.add(new styleClass(id, style, font, size));
		return id;
	}

	int addCompleteTextStyle(String fontName, int size, int style){
		int id = Math.max(1, nextStyleID);
		for(styleClass entry : styleList) id = Math.max(id, entry.id+1);
		nextStyleID = id+1;
		styleList.add(new styleClass(id, style, ensureFontForStyle(fontName), size));
		return id;
	}

	
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("stack");
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("stackID");
        writer.writeCharacters("-1");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("format");
        writer.writeCharacters("11");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

		writer.writeStartElement("legacyTextEncoding");
		writer.writeCharacters(legacyTextEncoding.name());
		writer.writeEndElement();
		writer.writeCharacters("\n\t\t");

        writer.writeStartElement("backgroundCount");
        writer.writeCharacters(Integer.toString(bgCacheList.size()));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        //the head of the background list (bgCacheList is in that order): bkgnd 1 when the file is read again
        if(!bgCacheList.isEmpty()) firstBg = bgCacheList.get(0).id;
        writer.writeStartElement("firstBackgroundID");
        writer.writeCharacters(Integer.toString(firstBg));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("cardCount");
        writer.writeCharacters(Integer.toString(cardIdList.size()));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("firstCardID");
        writer.writeCharacters(Integer.toString(firstCard));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("listID");
        writer.writeCharacters(Integer.toString(listId));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("password");
        writer.writeCharacters(Integer.toString(passwordHash));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        //絵を封印する鍵。_stack.xml自体が封印されるとき(パスワードあり)だけ書く
        if(contentKey!=null && StackProtection.sealsXml(this)){
            writer.writeStartElement("contentKey");
            writer.writeCharacters(java.util.Base64.getEncoder().encodeToString(contentKey));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t");
        }
        
        writer.writeStartElement("userLevel");
        writer.writeCharacters(Integer.toString(userLevel));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantModify");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantModify)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantDelete");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantDelete)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("privateAccess");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(privateAccess)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantAbort");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantAbort)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantPeek");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantPeek)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("createdByVersion");
        writer.writeCharacters(createdByVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("lastCompactedVersion");
        writer.writeCharacters(lastCompactedVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("modifyVersion");
        writer.writeCharacters(PCARD.longVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("openVersion"); //lastOpenedVersion
        writer.writeCharacters(firstEditedVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("fontTableID");
        writer.writeCharacters(Integer.toString(fontTableID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("styleTableID");
        writer.writeCharacters(Integer.toString(styleTableID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cardSize");
        writer.writeCharacters("\n\t\t\t");
        {
        	writer.writeStartElement("width");
	        writer.writeCharacters(Integer.toString(width));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
	        writer.writeStartElement("height");
	        writer.writeCharacters(Integer.toString(height));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t");
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        writer.writeStartElement("resourceOrder"); //the resources below are in the resource map's order (Get1IndResource)
        writer.writeCharacters("map");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        if(standaloneApplication){
        	writer.writeStartElement("standaloneApplication");
        	writer.writeCharacters("true");
        	writer.writeEndElement();
        	writer.writeCharacters("\n\t\t");
        }
        if(savedWindowSize!=null){
        	//the card window saved smaller than the card (HyperCard opens the stack that way)
        	writer.writeStartElement("windowSize");
        	writer.writeCharacters(savedWindowSize.width+","+savedWindowSize.height);
        	writer.writeEndElement();
        	writer.writeCharacters("\n\t\t");
        	if(scroll!=null){
        		writer.writeStartElement("windowScroll");
        		writer.writeCharacters(scroll.x+","+scroll.y);
        		writer.writeEndElement();
        		writer.writeCharacters("\n\t\t");
        	}
        }

        writer.writeStartElement("patterns");
        writer.writeCharacters("\n\t\t\t");
        for(int i=0; i<40; i++){
	        writer.writeStartElement("pattern");
	        writer.writeCharacters(Pattern[i]);
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("script");
        for(int i=0; i<scriptList.size(); i++){
        	writer.writeCharacters(scriptList.get(i));
        	if(i<scriptList.size()-1){
        		writer.writeCharacters("\n");
        	}
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t");

        if(osaScript!=null) osaScript.writeXML(writer, "\n\t"); //HyperCard 2.2+ compiled AppleScript, kept as is (OsaScript)

        writer.writeEndElement();
        writer.writeCharacters("\n\t");
	}

	
	public void writeFontXML(XMLStreamWriter writer) throws XMLStreamException {
        for(int i=0; i<fontList.size(); i++){
        	writer.writeStartElement("font");
	        writer.writeCharacters("\n\t\t");
        	{
		        writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(fontList.get(i).id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		        writer.writeStartElement("name");
		        writer.writeCharacters(fontList.get(i).name);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t");
        	}
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t");
        }
	}

	
	public void writeStyleXML(XMLStreamWriter writer) throws XMLStreamException {
		//TODO: dummy
        writer.writeStartElement("nextStyleID");
        writer.writeCharacters(Integer.toString(nextStyleID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t");

        for(int i=0; i<styleList.size(); i++){
	        writer.writeStartElement("styleentry");
	        writer.writeCharacters("\n\t\t");
	        {
		        writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(styleList.get(i).id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t");
		        
		        if(styleList.get(i).font!=-1){
			        writer.writeStartElement("font");
			        writer.writeCharacters(Integer.toString(styleList.get(i).font));
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		        
		        if(styleList.get(i).size!=-1){
			        writer.writeStartElement("size");
			        writer.writeCharacters(Integer.toString(styleList.get(i).size));
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		        
		        if(styleList.get(i).style!=-1){
		        	if(styleList.get(i).style==0){
				        writer.writeStartElement("textStyle");
				        writer.writeCharacters("plain");
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t");
		        	}
		        	else{
			        	String[] styleAry = new String[]{
			        			"bold", "italic", "underline", "outline",
			        			"shadow", "condensed", "extend", "group"};
			        	for(int j=0; j<8; j++){
			        		if((styleList.get(i).style&(1<<j))>0){
						        writer.writeStartElement("textStyle");
						        writer.writeCharacters(styleAry[j]);
						        writer.writeEndElement();
						        writer.writeCharacters("\n\t");
			        		}
			        	}
		        	}
		        }
	        }
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t");
        }
	}

	
	//HCのスタックを変換
	@SuppressWarnings("unchecked")
	public boolean readStackBlock(DataInputStream dis, int blockSize){
		////System.out.println("readStackBlock");

		if(blockSize>200000 || blockSize<50){
			return false;
		}
		
		//ブロックのデータを順次読み込み
		id = HCData.readCode(dis, 4);
		//System.out.println("blockId:"+id); //always -1
		/*String tygersStr =*/ HCData.readStr(dis, 4);
		//System.out.println("tygersStr:"+tygersStr);
		/*int format =*/ HCData.readCode(dis, 4);
		//System.out.println("format:"+format);
		totalSize = HCData.readCode(dis, 4);
		//System.out.println("totalSize:"+totalSize);
		/*int stackSize =*/ HCData.readCode(dis, 4);
		//System.out.println("stackSize:"+stackSize);
		/*int something =*/ HCData.readCode(dis, 4);
		//System.out.println("something:"+something); //診断用
		/*int tygers1Str =*/ HCData.readCode(dis, 4);
		//System.out.println("tygersStr:"+tygers1Str); //診断用
		/*int numofBgs =*/ HCData.readCode(dis, 4);
		//System.out.println("numofBgs:"+numofBgs);
		firstBg = HCData.readCode(dis, 4);
		//System.out.println("firstBg:"+firstBg);
		/*int numofCards =*/ HCData.readCode(dis, 4);
		//System.out.println("numofCards:"+numofCards);
		firstCard = HCData.readCode(dis, 4);
		//System.out.println("firstCard:"+firstCard);
		listId = HCData.readCode(dis, 4);
		headerListId = listId;
		//System.out.println("listBlockId:"+listId);
		/*int numofFree =*/ HCData.readCode(dis, 4);
		//System.out.println("numofFree:"+numofFree);
		/*int freeSize =*/ HCData.readCode(dis, 4);
		//System.out.println("freeSize:"+freeSize);
		/*int printId =*/ HCData.readCode(dis, 4);
		//System.out.println("printId:"+printId);
		passwordHash = HCData.readCode(dis, 4);
		//System.out.println("passwordHash:"+passwordHash);
		userLevel = HCData.readCode(dis, 2);
		//System.out.println("userLevel:"+userLevel);
		/*String tygers3Str =*/ HCData.readStr(dis, 2);
		//System.out.println("tygers3Str:"+tygers3Str);
		int flags = HCData.readCode(dis, 2);
		//System.out.println("flags:"+flags);
		cantPeek = ((flags>>10)&0x01)!=0;
		cantAbort = ((flags>>11)&0x01)!=0;
		privateAccess = ((flags>>13)&0x01)!=0;
		cantDelete = ((flags>>14)&0x01)!=0;
		cantModify = ((flags>>15)&0x01)!=0;
		/*String tygers4Str =*/ HCData.readStr(dis, 18);
		//System.out.println("tygers4Str:"+tygers4Str);
		
		int createdByV = HCData.readCode(dis, 4);
		createdByVersion = getVers(createdByV);
		//System.out.println("createdVer:"+createdByVersion);
		
		int compactedV = HCData.readCode(dis, 4);
		lastCompactedVersion = getVers(compactedV);
		//System.out.println("compactedVer:"+lastCompactedVersion);
		
		int lastEditedV = HCData.readCode(dis, 4);
		lastEditedVersion = getVers(lastEditedV);
		//System.out.println("lastEditedVer:"+lastEditedVersion);
		
		int lastOpenedV = HCData.readCode(dis, 4);
		firstEditedVersion = getVers(lastOpenedV);
		//System.out.println("lastOpenedVer:"+firstEditedVersion);
		
		/*int checksum =*/ HCData.readCode(dis, 4);
		//System.out.println("checksum:"+checksum);
		/*String tygers41Str =*/ HCData.readStr(dis, 4);
		//System.out.println("tygers41Str:"+tygers41Str);
		windowRect = new Rectangle();
		windowRect.x = HCData.readCode(dis, 2);
		windowRect.y = HCData.readCode(dis, 2);
		windowRect.width = HCData.readCode(dis, 2) - windowRect.x;
		windowRect.height = HCData.readCode(dis, 2) - windowRect.y;
		screenRect = new Rectangle();
		screenRect.x = HCData.readCode(dis, 2);
		screenRect.y = HCData.readCode(dis, 2);
		screenRect.width = HCData.readCode(dis, 2) - screenRect.x;
		screenRect.height = HCData.readCode(dis, 2) - screenRect.y;
		scroll = new Point();
		scroll.y = HCData.readCode(dis, 2); //a QuickDraw point: v, then h
		scroll.x = HCData.readCode(dis, 2);
		/*String tygers5Str =*/ HCData.readStr(dis, 292);
		//System.out.println("tygers5Str:"+tygers5Str);
		fontTableID = HCData.readCode(dis, 4);
		//System.out.println("fontTableId:"+fontTableID);
		styleTableID = HCData.readCode(dis, 4);
		//System.out.println("styleTableId:"+styleTableID);
		height = HCData.readCode(dis, 2);
		//System.out.println("height:"+height);
		width = HCData.readCode(dis, 2);
		//System.out.println("width:"+width);
		if(height<=0 || width<=0){ height = 342; width = 512; } //HyperCard 1.x はカードサイズを持たない(常に 512x342)
		//windowRect holds the header's rect as read (x=top, y=left, width=bottom-top, height=right-left)
		int savedW = windowRect.height, savedH = windowRect.width;
		savedWindowSize = savedW>0 && savedH>0 && (savedW<width || savedH<height) ?
			new Dimension(Math.min(savedW, width), Math.min(savedH, height)) : null;
		/*String tygers6Str =*/ HCData.readStr(dis, 260);
		//System.out.println("tygers6Str:"+tygers6Str);
		Pattern = HCData.readPatterns(dis, new File(this.path).getParent());
		//System.out.println("Patterns ok");
		/*String tygers7Str =*/ HCData.readStr(dis, 512);
		//System.out.println("tygers7Str:"+tygers7Str);
		int remainLength1 = blockSize - ((1538));
		//HyperCard 2.2+: an AppleScript (OSA) script is source text + NUL + a compiled 'WOSA' record (OsaScript)
		resultStr result = HCData.readOsaScript(dis, HCData.remaining(dis), 0);
		if(result==null) result = HCData.readScriptToZero(dis, remainLength1);
		if(result.osa==null && result.length_in_src<=1){
			int skipped = result.length_in_src;
			result = HCData.readOsaScript(dis, HCData.remaining(dis), 0);
			if(result!=null) result.length_in_src += skipped;
			else result = HCData.readScriptToZero(dis, remainLength1);
		}
		osaScript = result.osa;
		String scriptStr = result.str;
		////System.out.println("scriptStr:"+scriptStr);
		int remainLength = blockSize - ((1538)+(result.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
		}
		/*if((result.length_in_src+1)%2 != 0){
			String padding = HCData.readStr(dis, 1);
			//System.out.println("padding:"+padding);
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
	
	
	private static String getVers(int ver){
		String str;
		str = ""+(0xFF&(ver>>24));
		if((0xFF&(ver>>16))/16>0){
			str += "."+((0xFF&(ver>>16))/16+"."+(0xFF&(ver>>16))%16);
		}else{
			str += "."+((0xFF&(ver>>16))%16);
		}
		if((0xFF&(ver>>8)) == 0x20) str += "d" ;
		if((0xFF&(ver>>8)) == 0x40) str += "a";
		if((0xFF&(ver>>8)) == 0x60) str += "b";
		if((0xFF&(ver>>0))/16>0){
			str += "."+((0xFF&(ver>>0))/16+""+(0xFF&(ver>>0))%16);
		}else if(((0xFF&(ver>>0))%16)>0){
			str += "."+((0xFF&(ver>>0))%16);
		}
		return str;
	}
	

	public boolean readStyleBlock(DataInputStream dis, int blockSize){
		//System.out.println("readStyleBlock");

		if(blockSize>200000 || blockSize<24){
			return false;
		}
		
		int offset = 24;
		
		//ブロックのデータを順次読み込み
		/*int blockId =*/ HCData.readCode(dis, 4);
		//System.out.println("blockId:"+blockId);
		/*int filler =*/ HCData.readCode(dis, 4);
		//System.out.println("filler:"+filler);
		int styleCount = HCData.readCode(dis, 4);
		//System.out.println("styleCount:"+styleCount);
		nextStyleID = HCData.readCode(dis, 4);
		//System.out.println("nextStyleID:"+nextStyleID);
		
		for(int i=0; i<styleCount; i++){
			int styleId = HCData.readCode(dis, 4);
			//System.out.println("styleId:"+styleId);
			/*int something1 =*/ HCData.readCode(dis, 4);
			//System.out.println("something1:"+something1);
			/*int something2 =*/ HCData.readCode(dis, 4);
			//System.out.println("something2:"+something2);
			int textFontId = HCData.readCode(dis, 2);
			//System.out.println("textFontId:"+textFontId);
			int textStyle = HCData.readCode(dis, 1);
			//System.out.println("textStyle:"+textStyle);
			int textStyleChanged = HCData.readCode(dis, 1);
			//System.out.println("textStyleChanged:"+textStyleChanged);
			int textSize = HCData.readCode(dis, 2);
			//System.out.println("textSize:"+textSize);
			/*String filler2 =*/ HCData.readStr(dis, 6);
			//System.out.println("filler2:"+filler2);
			offset += 24;
			
			String idStr = Integer.toString(styleId);
			String fontStr = Integer.toString(textFontId);
			String styleStr = "";
			if(textStyleChanged==255){ //style unchange
				styleStr = "";
			}
			else{
				if(textStyle==0){
					styleStr = "plain";
				}
				else{
					if((textStyle&1)>0) styleStr += "bold ";
					if((textStyle&2)>0) styleStr += "italic ";
					if((textStyle&4)>0) styleStr += "underline ";
					if((textStyle&8)>0) styleStr += "outline ";
					if((textStyle&16)>0) styleStr += "shadow ";
					if((textStyle&32)>0) styleStr += "condensed ";
					if((textStyle&64)>0) styleStr += "extend ";
					if((textStyle&128)>0) styleStr += "group ";
				}
			}
			String sizeStr = Integer.toString(textSize);
    		styleList.add(new styleClass(idStr, styleStr, fontStr, sizeStr));
		}
		int remainLength = blockSize - offset;
		/*String padding =*/ HCData.readStr(dis, remainLength);
		//System.out.println("padding:"+padding);
		
		return true;
	}
	

	public boolean readFontBlock(DataInputStream dis, int blockSize){
		//System.out.println("readFontBlock");

		if(blockSize>200000 || blockSize<24){
			return false;
		}
		//ブロックのデータを順次読み込み
		/*int blockId =*/ HCData.readCode(dis, 4);
		//System.out.println("blockId:"+blockId);
		/*String tygersStr =*/ HCData.readStr(dis, 6);
		//System.out.println("tygersStr:"+tygersStr);
		int numOfFonts = HCData.readCode(dis, 2);
		//System.out.println("numOfFonts:"+numOfFonts);
		/*String tygers2Str =*/ HCData.readStr(dis, 4);
		//System.out.println("tygers2Str:"+tygers2Str);
		int offset = 24;
		
		for(int i=0; i<numOfFonts; i++){
			int fontId = HCData.readCode(dis, 2);
			offset+=2;
			//System.out.println("fontId:"+fontId);
			int nameLen;// = HCData.readCode(dis, 1);
			//offset+=1;
			//System.out.println("nameLen:"+nameLen);
			//if(offset+nameLen>blockSize){
				//break;
				nameLen = blockSize - offset;
				//if(nameLen<0)break;
			//}
			resultStr nameResult = HCData.readTextToZero(dis, nameLen);
			//System.out.println("nameResult.str:"+nameResult.str);
			offset+=nameResult.length_in_src;
			//FTBLのフォント名(NUL終端文字列)は、name自体のバイト数が偶数の
			//ときだけ、直後に1バイトのパディングが入る(name+NULが奇数長に
			//なるときにパディングして偶数長にする、という昔ながらの単純な
			//偶数バイト境界合わせ)。このセッション中に一度「常に1バイト
			//読み飛ばす」「常にパディング無し」の両方を試したが、HyperZebraで
			//書き出したものではない複数のHyperCardスタックで検証した
			//ところ、結局この元々あった条件付きの読み飛ばしが正しかった。
			if((nameResult.length_in_src+1)%2==0){
				HCData.readCode(dis, 1);
				offset+=1;
			}

			//フォントIDと名前を登録
			fontList.add(new fontClass(fontId, nameResult.str));
		}
		
		int remainLength = blockSize - offset;
		/*String padding =*/ HCData.readStr(dis, remainLength);
		//System.out.println("padding:"+padding);
		
		return true;
	}
	

	static int pageIdList[];
	static int pageEntryCountList[];
	static int pageEntrySize;
	
	public boolean readListBlock(DataInputStream dis, int blockSize){
		//System.out.println("readListBlock");

		if(blockSize>200000 || blockSize<12){
			return false;
		}
		
		//ブロックのデータを順次読み込み
		int blockId = HCData.readCode(dis, 4);
		//a LIST the header does not name is a stale copy (AOL Naturist States: LIST 29378 with 52 cards, then two old ones
		//with 12): only the header's one is the card list
		if(headerListId!=0 && blockId!=headerListId) return true;
		listId = blockId;
		//System.out.println("listId:"+listId);
		/*int filler =*/ HCData.readStr(dis, HCData.isV1() ? 0 : 4); //1.x: ブロックIDの後の詰め物が無く 0x0C からページ数(ページ表は 2.x と同じ 0x30 から)
		//System.out.println("filler:"+filler);
		if(HCData.isV1()){
			int pageCount = HCData.readCode(dis, 4);
			/*int pageSize =*/ HCData.readCode(dis, 4);
			/*int pageEntryTotal =*/ HCData.readCode(dis, 4);
			pageEntrySize = HCData.readCode(dis, 2);
			HCData.readStr(dis, 0x30-0x1A);
			if(pageCount<0 || 0x30+pageCount*6>blockSize) return false;
			pageIdList = new int[pageCount];
			pageEntryCountList = new int[pageCount];
			for(int i=0; i<pageCount; i++){
				pageIdList[i] = HCData.readCode(dis, 4);
				pageEntryCountList[i] = HCData.readCode(dis, 2);
			}
			int rest = blockSize-(0x30+pageCount*6);
			if(rest>0) HCData.readStr(dis, rest);
			return true;
		}
		int pageCount = HCData.readCode(dis, 4);
		//System.out.println("pageCount:"+pageCount);
		/*int pageSize =*/ HCData.readCode(dis, 4);
		//System.out.println("pageSize:"+pageSize);
		/*int pageEntryTotal =*/ HCData.readCode(dis, 4);
		//System.out.println("pageEntryTotal:"+pageEntryTotal);
		pageEntrySize = HCData.readCode(dis, 2);
		//System.out.println("pageEntrySize:"+pageEntrySize);
		/*int filler2 =*/ HCData.readCode(dis, 10);
		//System.out.println("filler2:"+filler2);
		/*int pageEntryTotal2 =*/ HCData.readCode(dis, 4);
		//System.out.println("pageEntryTotal2:"+pageEntryTotal2);
		/*int filler3 =*/ HCData.readCode(dis, 4);
		//System.out.println("filler3:"+filler3);
		
		pageIdList = new int[pageCount];
		pageEntryCountList = new int[pageCount];
		for(int i=0; i<pageCount; i++){
			pageIdList[i] = HCData.readCode(dis, 4);
			//System.out.println("pageId:"+pageIdList[i]);
			pageEntryCountList[i] = HCData.readCode(dis, 2);
			//System.out.println("pageEntryCount:"+pageEntryCountList[i]);
		}
		
		int remainLength = blockSize-(48+pageCount*6);
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
		}
		
		return true;
	}
	

	/** The card ids of each PAGE block read, by page id */
	private final java.util.Map<Integer,java.util.List<Integer>> pageCardIds = new java.util.LinkedHashMap<Integer,java.util.List<Integer>>();

	/**
	 * The stack's cards in the order of the LIST block's page table: HyperCard numbers its cards that way. The PAGE blocks
	 * themselves can lie in another order in the file (TuneFinder 1.0: the page with the first card comes second, so the
	 * stack opened on card 65 and every card of the first two pages had the wrong number). Pages the table does not list
	 * follow in file order.
	 */
	void orderCardsByPageList(){
		if(pageCardIds.isEmpty()) return;
		java.util.LinkedHashSet<Integer> ordered = new java.util.LinkedHashSet<Integer>();
		if(pageIdList!=null) for(int pid : pageIdList){ java.util.List<Integer> cards = pageCardIds.remove(pid); if(cards!=null) ordered.addAll(cards); }
		for(java.util.List<Integer> cards : pageCardIds.values()) ordered.addAll(cards);
		ordered.addAll(cardIdList);
		pageCardIds.clear();
		cardIdList.clear();
		cardIdList.addAll(ordered);
	}

	public boolean readPageBlock(DataInputStream dis, int blockSize){
		//System.out.println("readPageBlock");

		if(blockSize>200000 || blockSize<12){
			return false;
		}
		
		if(pageIdList==null || pageEntryCountList==null){
			return false;
		}
		
		int offset = 24;
		
		//ブロックのデータを順次読み込み
		int pageId = HCData.readCode(dis, 4);
		//System.out.println("pageId:"+pageId);
		/*int filler =*/ HCData.readCode(dis, 4);
		//System.out.println("filler:"+filler);
		/*int listId =*/ HCData.readCode(dis, 4);
		//System.out.println("listId:"+listId);
		/*int filler2 =*/ HCData.readCode(dis, 4);
		//System.out.println("filler2:"+filler2);
		
		int pageEntryCount = 0;
		for(int i=0; i<pageIdList.length; i++){
			if(pageIdList[i] == pageId){
				pageEntryCount = pageEntryCountList[i];
				break;
			}
		}
		
		java.util.List<Integer> cards = new ArrayList<Integer>();
		pageCardIds.put(pageId, cards); //カードの順は LIST のページ表の順(orderCardsByPageList)。ファイル上の PAGE の順ではない
		for(int i=0; i<pageEntryCount; i++){
			int cardId = HCData.readCode(dis, 4);
			offset+=4;
			//System.out.println("cardId:"+cardId);
			cards.add(cardId);
			if(GetCardbyId(cardId)==null){
				cardIdList.add(cardId); //読み込み中はファイル上の順(最初のカードの絵を先に書き始めるため)。最後に LIST の順へ並べ直す
			}
			if(pageEntrySize>4){
				//エントリの5バイト目がフラグ(0x10=marked, 0x20=テキストあり, 0x40=背景の先頭, 0x80=名前あり)、残りは検索用ハッシュ
				int flags = HCData.readCode(dis, 1);
				if((flags&0x10)!=0) importMarkedIds.add(cardId);
				if(pageEntrySize>5) HCData.readStr(dis, pageEntrySize-5);
				offset+=pageEntrySize-4;
			}
		}
		
		int remainLength = blockSize-offset;
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
		}
		
		return true;
	}

	

	public boolean readNullBlock(DataInputStream dis, int blockSize){
		//System.out.println("readNullBlock");

		//MAST/FREE/PRNT…: nothing is taken from them, so any size is fine - Mosaïcon's FREE block of 243 KB counted as damaged
		if(blockSize<12){
			return false;
		}
		/*int blockId =*/ HCData.readCode(dis, 4);
		try{ dis.skipBytes(blockSize - 12); }catch(java.io.IOException e){ return false; }
		return true;
	}
	
	
	
	//-------------------
	// Utility function
	//-------------------
	public OCard GetCardbyId(int cardId) {
		for(int i=0; i<cardIdList.size(); i++){
			if(cardIdList.get(i)==cardId)
			{
				try {
					return OCard.getOCard(this, cardId, true);
				} catch (xTalkException e) {
				}
			}
		}
		return null;
	}
	
	public OCard GetCardbyNum(int number) {
		if(number-1>=0 && number-1<cardIdList.size())
		{
			try {
				return OCard.getOCard(this, cardIdList.get(number-1), true);
			} catch (xTalkException e) {
			}
		}
		return null;
	}
	
	public OCard GetCard(String name) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null&&0==cd.name.compareToIgnoreCase(name)){
				return cd;
			}
		}
		return null;
	}

	//マークされているカードだけを数えて、その中でnumber番目(1起点)のカードを返す。
	//「go marked card <番号>」用。
	public OCard GetMarkedCardbyNum(int number) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null && cd.marked){
				number--;
				if(number==0){
					return cd;
				}
			}
		}
		return null;
	}

	public OBackground GetBackgroundbyId(int bgId) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = GetCardbyId(cardIdList.get(i));
			if(cd!=null && cd.bgid==bgId)
			{
				try {
					return OBackground.getOBackground(this, cd, bgId, true);
				} catch (xTalkException e) {
				}
			}
		}
		return null;
	}
	
	//バックグラウンドの番号順 = HyperCard のバックグラウンド一覧(BKGD の連結リスト)の順。bg N / the number of bg /
	//the number of bgs はすべてこの順を使う。カードの並びとは関係が無く、カードを並べ替えても番号は変わらない。
	//bgCacheList 自体をこの順に保つ: HyperCard スタックの読み込みでは STAK の最初の bg から BKGD の次の bg を辿った順
	//(orderBackgroundsByList)、_stack.xml はこの順に書いて読む、New Background の bg は今のカードの bg の直後に入る
	//(AddNewBgAfter)。HyperCard J-2.3 で測った: bg 1 のカードで作ると bg 2 になり、カードを並べ替えても番号は同じ。
	//以前は id の昇順で作成順を近似していたが、HyperCard の一覧は id 順ではない(UxPop2.4: 一覧 6491, 4588, 2803, 3797,
	//9884 の表紙ＢＧが bg 1。id 順では bg 4 になり、"_Hcard" の階層メニューと「前のＢＧのカード」が狂っていた)。
	//next/prev bg は一覧ではなくカード順で決まる(relativeBackground)。
	ArrayList<OBackground> backgroundsInOrder() {
		return new ArrayList<OBackground>(bgCacheList);
	}

	/** The backgrounds in HyperCard's order: bkgnd 1 first (a stack exported as a HyperCard file keeps it). */
	public java.util.List<OBackground> backgroundList() {
		return java.util.Collections.unmodifiableList(backgroundsInOrder());
	}

	/**
	 * A HyperCard stack's background order after its blocks are read: from the STAK's first background along each BKGD's
	 * "next background" ID ({@code next}: background ID -> next ID). Backgrounds the chain does not reach (a damaged list)
	 * follow in the order their blocks came.
	 */
	void orderBackgroundsByList(java.util.Map<Integer,Integer> next) {
		ArrayList<OBackground> ordered = new ArrayList<OBackground>();
		Integer id = firstBg;
		while(id!=null && ordered.size()<bgCacheList.size()){
			OBackground bg = null;
			for(OBackground b : bgCacheList) if(b.id==id){ bg = b; break; }
			if(bg==null || ordered.contains(bg)) break;
			ordered.add(bg);
			id = next.get(id);
		}
		for(OBackground b : bgCacheList) if(!ordered.contains(b)) ordered.add(b);
		bgCacheList = ordered; //a new list, not cleared and refilled: the picture threads of the import read it meanwhile
	}

	/**
	 * After _stack.xml is read: the backgrounds were written in their order, the first one being firstBackgroundID. A file
	 * written before HyperZebra kept the order has them in the HyperCard file's block order; the first background there is
	 * still firstBackgroundID (the STAK's), so the list starts there.
	 */
	void orderBackgroundsAfterXmlRead() {
		int head = -1;
		for(int i=0; i<bgCacheList.size(); i++) if(bgCacheList.get(i).id==firstBg){ head = i; break; }
		if(head<=0) return;
		ArrayList<OBackground> ordered = new ArrayList<OBackground>(bgCacheList);
		java.util.Collections.rotate(ordered, -head);
		bgCacheList = ordered;
	}

	public OBackground GetBackgroundbyNum(int number) {
		ArrayList<OBackground> list = backgroundsInOrder();
		if(number>=1 && number<=list.size()) return list.get(number-1);
		return null;
	}
	
	public OBackground GetBackground(String name) {
		for(OBackground bg : backgroundsInOrder()){
			if(bg.name!=null && 0==bg.name.compareToIgnoreCase(name)) return bg;
		}
		return null;
	}
	
	public OCard GetCardofBg(OCardBase cdbase, String name) {
		if(!cdbase.objectType.equals("background")) return null;
		
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null&&0==cd.name.compareToIgnoreCase(name)&&cd.bgid==cdbase.id){
				return cd;
			}
		}
		return null;
	}
	
	public OCard GetCardofBgbyNum(OCardBase cdbase, int number) {
		if(!cdbase.objectType.equals("background")) return null;
		
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null&&cd.bgid==cdbase.id){
				number--;
				if(number==0){
					return cd;
				}
			}
		}
		return null;
	}

	public OCard GetCardofBackgroundbyId(int bgId) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = GetCardbyId(cardIdList.get(i));
			if(cd.bgid==bgId)
			{
				try {
					cd.bg = OBackground.getOBackground(this, cd, cd.bgid, true);
				} catch (xTalkException e) {
					System.out.println("Error at GetCardofBackgroundbyId("+bgId+")");
				}
				return cd;
			}
		}
		return null;
	}
	
	public int GetNumberof(OCard cd) {
		for(int number=0; number < cardIdList.size(); number++)
		{
			if(cardIdList.get(number)==cd.id){
				return number+1;
			}
		}
		return 0;
	}
	
	public int GetNumberof(OBackground bg) {
		ArrayList<OBackground> list = backgroundsInOrder();
		for(int number=0; number < list.size(); number++)
		{
			if(list.get(number).id==bg.id){
				return number+1;
			}
		}
		return 0;
	}
	
	public int GetMaxCardId() {
		int max = 0;
		for(int i=0; i<cardIdList.size(); i++){
			if(cardIdList.get(i)>max)
			{
				max = cardIdList.get(i);
			}
		}
		for(int i=0; i<bgCacheList.size(); i++){
			if(bgCacheList.get(i).id>max)
			{
				max = bgCacheList.get(i).id;
			}
		}
		return max;
	}
	
	//新しいカード/バックグラウンド用のID。カード・bgのIDより大きく、かつ既存の
	//ピクチャファイル名(BMAP_<id>.png)とも衝突しないものを返す。HyperCardから
	//変換したスタックではビットマップIDがカードIDと同じ番号空間にあり、ペイント
	//時に BMAP_<カードID>.png へ書くため、単にカードIDの最大値+αでは他のカードの
	//絵を上書きしてしまうことがあった。
	public int newCardOrBgId() {
		int id = GetMaxCardId() + 1 + (int)(Math.random()*100);
		while(bitmapIdInUse(id)) id++;
		return id;
	}

	boolean bitmapIdInUse(int id) {
		String name = "BMAP_"+id+".png";
		for(int i=0; i<cdCacheList.size(); i++){
			if(name.equals(cdCacheList.get(i).bitmapName)) return true;
		}
		for(int i=0; i<bgCacheList.size(); i++){
			if(name.equals(bgCacheList.get(i).bitmapName)) return true;
		}
		if(file!=null && file.getParentFile()!=null && new File(file.getParentFile(), name).exists()) return true;
		return false;
	}

	public int GetCardListSize() {
		return cardIdList.size();
	}
	
	public int GetBgListSize() {
		return bgCacheList.size();
	}

	public void AddNewCard(int idx, int id) {
		for(int i=0 ;i<cardIdList.size(); i++){
			if(cardIdList.get(i) == id){
				System.out.println("!");
			}
		}
		cardIdList.add(idx, id);
	}
	
	public void AddNewCard(int id) {

		for(int i=0 ;i<cardIdList.size(); i++){
			if(cardIdList.get(i) == id){
				System.out.println("!");
			}
		}
		cardIdList.add(id);
	}
	
	public void AddNewBg(OBackground bg) {

		for(int i=0 ;i<bgCacheList.size(); i++){
			if(bgCacheList.get(i).id == bg.id){
				System.out.println("!");
			}
		}
		bgCacheList.add(bg);
	}

	/** A background made from a card (New Background): HyperCard puts it right after that card's background in its list. */
	public void AddNewBgAfter(OBackground bg, int afterBgId) {
		int at = bgCacheList.size();
		for(int i=0; i<bgCacheList.size(); i++) if(bgCacheList.get(i).id==afterBgId){ at = i+1; break; }
		bgCacheList.add(at, bg);
	}
	
	public void mouseWithinCheck(){
		if(PCARD.pc.stack.curCard==null) return;
		if(PCARD.pc.mainPane==null || !PCARD.pc.isShowing()) return; //テスト(カードウインドウなし)
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
		if(pointerInfo==null || !PCARD.pc.mainPane.isShowing()) return;
		//the card pane's own place on the screen: its x/y are within the content pane, below the title and menu bars
		java.awt.Point origin = PCARD.pc.mainPane.getLocationOnScreen();
		int x = PCARD.toLogical(pointerInfo.getLocation().x-origin.x);
		int y = PCARD.toLogical(pointerInfo.getLocation().y-origin.y);
		if((x<0)||(y<0)||
				(x>this.width)||
				(y>this.height)){
			return;
		}
		if(this.curCard!=null){
			//カードの部品(シェイプを含む重なり順の手前から)、次にバックグラウンドの部品。
			//受け取ったらその下には送らない
			for(OCardBase owner : new OCardBase[]{this.curCard, this.curCard.bg}){
				if(owner==null) continue;
				java.util.ArrayList<OObject> order = owner.stacking();
				for(int i=order.size()-1;i>=0;i--){
					OObject obj = order.get(i);
					boolean hit = obj instanceof OShape
						? GShape.receivesMouse() && ((OShape)obj).view!=null && ((OShape)obj).view.isVisible() && ((OShape)obj).hitsCard(x, y)
						: x>=obj.left&&x<=obj.left+obj.width&&y>=obj.top&&y<=obj.top+obj.height&&obj.isShown()&&obj.enabled;
					if(hit){
						TTalk.CallMessage("mouseWithin","",obj,true,false);
						return;
					}
				}
			}
		}
	}
	
	/*public void PaintTo(Graphics g, JFrame frame) {
		if(curCard != null) {
			curCard.PaintTo(g,frame);
		}
	}*/
    
	
	
//初期のオリジナルデータファイル形式について：
	
/* テキストファイルにスタックののプロパティ情報を入れる
 * #charset:Shift_JIS
 * #name:xxx(基本的にファイル名と同じ)
 * #left:0
 * #top:0
 * #width:512
 * #height:342
 * #script:on idle
 *  --
 * end idle
 * #cd:12345
 * #cd:67890
 * 
 * #は特殊コード。名前などで#を使いたいときは##にする
 */
	
/*
 * ファイル構成は..idをxxxxとして
 * 
 * スタック  名前.sta
 * BG情報   resource/bgxxxx 
 * CD情報   resource/cdxxxx <-ボタンとフィールドの情報も突っ込むので大変なことになる
 * ICON    resource/iconxxxx.png
 * ICONm   resource/iconxxxx_mask.png ->変換されて消える 
 * PICT    resource/pictxxxx.png
 * PICTm   resource/pictxxxx_mask.png ->変換されて消える 
 * Snd     resource/sndxxxx.wav
 * CD pic  resource/cdpxxxx.png  
 * CD mask resource/cdpxxxx_mask.png ->変換されて消える
 * BG pic  resource/bgpxxxx.png  
 */
}

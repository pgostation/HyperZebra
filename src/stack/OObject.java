import java.io.File;
import java.awt.Frame;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.TreeSet;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;

public class OObject {
	/**
	 * A host font for a stack object's font name. The stacks were laid out with the classic Japanese system fonts, whose
	 * two-byte glyphs all take a full em: macOS's own "Osaka" is the proportional face (kana narrower than an em, full-width
	 * Latin such as "ＯＫ！" proportional), so text measured and drawn with it comes out narrower than on a Mac and the
	 * alignment shifts. It goes to a family whose two-byte glyphs take an em; "Osaka−等幅" (not a family name Java knows)
	 * goes to Osaka-Mono, whose metrics match its classic namesake. Other names are used as they are, unless the host has no
	 * family for them and the stack has the font itself ({@link StackStrikeFont}).
	 */
	static java.awt.Font stackFont(String name,int style,int size){
		java.awt.Font own=StackStrikeFont.font(name,style,size);
		if(own!=null)return own;
		return new java.awt.Font(hostFamily(name),style,size);
	}
	/** A stack font at a size that may be fractional (a point size times a magnification such as 1.25). */
	static java.awt.Font stackFont(String name,int style,double size){
		if(size==Math.rint(size))return stackFont(name,style,(int)size);
		java.awt.Font own=StackStrikeFont.font(name,style,size);
		if(own!=null)return own;
		return new java.awt.Font(hostFamily(name),style,(int)size).deriveFont((float)size);
	}
	private static final java.util.Map<String,String> hostFamilies=new java.util.concurrent.ConcurrentHashMap<String,String>();
	static String hostFamily(String name){
		if(name==null)return "";
		String family=hostFamilies.get(name);
		if(family==null){
			family=name;
			String lower=name.toLowerCase();
			if(lower.equals("osaka")){String f=availableFamily(JAPANESE_GOTHIC);if(f!=null)family=f;}
			else if(lower.startsWith("osaka")&&lower.endsWith("等幅")){String f=availableFamily("Osaka-Mono","Osaka−等幅","Osaka－等幅");if(f==null)f=availableFamily(JAPANESE_GOTHIC);if(f!=null)family=f;}
			else if(availableFamily(name)==null){String f=classicStandIn(lower);if(f==null)f=containedFamily(name);if(f!=null)family=f;}
			hostFamilies.put(name,family);
		}
		return family;
	}
	/** Whether the host has a family for this stack font name (itself or a stand-in, see {@link #hostFamily}) */
	static boolean hostHasFamily(String name){
		return name!=null && !name.isEmpty() && availableFamily(hostFamily(name))!=null;
	}
	/**
	 * A host family whose name is in a screen font's name: PostScript-era screen fonts carry a style prefix and suffix
	 * ("CB Futura CondensedBold", "B Helvetica Bold", "I Times Italic"), the family is the host's Futura (Magellan at Venus's
	 * title was drawn from the stack's FONT strike instead). The longest family found as whole words, 4 letters at least.
	 */
	private static String containedFamily(String name){
		String lower=name.toLowerCase();
		String best=null;
		for(String f:java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()){
			String fl=f.toLowerCase();
			if(fl.length()<4 || (best!=null && fl.length()<=best.length())) continue;
			int i=lower.indexOf(fl);
			if(i<0 || (i>0 && Character.isLetterOrDigit(name.charAt(i-1)))) continue;
			int e=i+fl.length();
			//the word ends there: a space, the end, or a capital starting the style ("FuturaBold")
			if(e<name.length() && Character.isLetterOrDigit(name.charAt(e)) && !Character.isUpperCase(name.charAt(e))) continue;
			best=f;
		}
		return best;
	}
	private static final String[] JAPANESE_GOTHIC={"Hiragino Kaku Gothic ProN","Hiragino Sans","Hiragino Kaku Gothic Pro","Meiryo","MS Gothic","Noto Sans CJK JP","Noto Sans JP"};
	/**
	 * A host family for a classic Mac font the host does not have. Java turned such a name into its sans-serif Dialog: on
	 * this macOS there is no "Times" (only Times New Roman) nor "Courier", so trueHelp's Times 18 "trueColor" came out wider
	 * in Dialog, wrapped below its field and was gone (HC J-2.3 shows it). Null: no stand-in (the name is used as it is).
	 */
	private static String classicStandIn(String lower){
		switch(lower){
			case "times": case "new york": case "bookman": case "new century schlbk": case "palatino": case "toronto": case "athens":
				return availableFamily("Times New Roman","Times","Georgia","Palatino","Hiragino Mincho ProN");
			case "courier": case "monaco":
				return availableFamily("Courier New","Courier","Menlo","Monaco");
			case "helvetica": case "n helvetica narrow":
				return availableFamily("Helvetica","Helvetica Neue","Arial");
			case "avant garde":
				return availableFamily("Avenir","Century Gothic","Helvetica Neue","Helvetica","Arial");
			case "zapf chancery":
				return availableFamily("Apple Chancery","Zapfino");
			case "本明朝−ｍ": case "本明朝－ｍ": case "平成明朝": case "細明朝体": case "等幅明朝":
				return availableFamily("Hiragino Mincho ProN","Hiragino Mincho Pro","Yu Mincho","MS Mincho","Noto Serif CJK JP");
			case "平成角ゴシック": case "中ゴシック体": case "等幅ゴシック":
				return availableFamily(JAPANESE_GOTHIC);
		}
		return null;
	}
	private static String availableFamily(String... candidates){
		String[] families=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		for(String c:candidates)for(String f:families)if(f.equalsIgnoreCase(c))return f;
		return null;
	}
	OObject parent=null;
	String objectType="";
	int id;
	String name="";
	/** The id of the panel (OPanel) of the same card or background a button, field or shape belongs to; 0 = none. */
	int panelId;
	private String text="";
	boolean wrapFlag;
	ArrayList<String> scriptList;
	/** HyperCard 2.2+: the script compiled by an OSA component (AppleScript), kept as it was; null for HyperTalk. Such an
	 *  object has no handlers here (TTalk.ensureHandlerList) and scriptList holds only its stored source text, if any */
	OsaScript osaScript;
	TreeSet<String> handlerList;
	/** See TTalk.handlerEntry */
	HandlerTable handlerTable;
	ArrayList<String>[] stringList;
	/** The compiled blocks of this script (TalkJit); dropped with the script. */
	TalkJit.ScriptCode jit;
	/** {@link hypertalk.TTalk}'s first declaring line of the words of each handler, by script line; for the lines of scopeOf */
	java.util.HashMap<String,Integer>[] scopeLines;
	Object scopeOf;
	ArrayList<TTalk.wordType>[] typeList;
	ArrayList<Integer> handlerLineList;
	/** a handler name with diacritical marks by the name without them, lower case (TTalk.ensureHandlerList); null when none */
	java.util.Map<String,String> markedHandlers;
	/** 実行時の構造キャッシュ(if文の構造、repeatの終わり、ハンドラ行の単語)。各項目は元の行の String と
	 *  同一かを確かめてから使うので、スクリプトが書き換わっても古い内容は使われない(TTalk.ControlCacheEntry) */
	java.util.concurrent.ConcurrentHashMap<Integer,Object> controlCache;
	/** 最後にスクリプトエラーになった行(scriptListの行番号、無ければ-1)とそのメッセージ。スクリプトエディタが赤く示す */
	int lastErrorLine = -1;
	String lastErrorMessage = null;
	/** デバッガのチェックポイント(scriptListの行番号)。無ければnull */
	TreeSet<Integer> checkpoints;
	private boolean visible=true;
	boolean enabled=true;
	
	/** Kinds of a deferred Swing update already queued for this part under lock screen (PCARD.runOnEDTWhenShown). */
	int queuedEdt;
	int left=0; int top=0;
	int width=0; int height=0;

	/** The script this object had when its stack was closed (clean): a handler of it may still be running and call its own
	 *  functions (HyperCard Help's goExtrasStackInContext goes to Help Extras, then calls convertIDsToTitles) */
	ArrayList<String> retiredScriptList;

	public void clean(){
		parent = null;
		//objectType = null;
		if(scriptList!=null) retiredScriptList = scriptList;
		scriptList = null;
		stringList = null;
		typeList = null;
		handlerLineList = null;
		handlerList = null;
		controlCache = null;
		jit = null;
		TalkJit.scriptEpoch++;
	}
	
	public int getLeft(){
		return left;
	}
	public int getTop(){
		return top;
	}
	public int getWidth(){
		return width;
	}
	public int getHeight(){
		return height;
	}
	public boolean getVisible(){
		return visible;
	}
	/** The panel this button, field or shape belongs to, or null. */
	OPanel panel(){
		return panelId!=0 && parent instanceof OCardBase ? ((OCardBase)parent).panelById(panelId) : null;
	}
	/** Puts this button, field or shape into a panel of its own card or background (null takes it out). */
	public void setPanel(OPanel panel){
		if(!objectType.equals("button") && !objectType.equals("field") && !objectType.equals("shape")) return;
		int next = panel==null ? 0 : panel.id;
		if(next==panelId) return;
		panelId = next;
		if(parent instanceof OCardBase) ((OCardBase)parent).changed = true;
		applyShown();
	}
	/** Whether it is drawn: its own visible, and its panel's (a hidden panel hides its members; their visible stays). */
	public boolean isShown(){
		if(!getVisible()) return false;
		OPanel p = panel();
		return p==null || p.getVisible();
	}
	/** Shows or hides the part's component as isShown says (a panel was hidden or shown, the part joined or left one). */
	void applyShown(){}
	void setVisibleInternal(boolean in){
		visible = in;
	}
	public String getText(){
		return text;
	}
	public String getShortShortName(){
		String sName = "";
		if(objectType.equals("stack")) sName += "stack";
		if(objectType.equals("background")) sName += "bg";
		if(objectType.equals("card")) sName += "cd";
		if(objectType.equals("button")) sName += "btn";
		if(objectType.equals("field")) sName += "fld";
		if(objectType.equals("panel")) sName += "panel";
		if(objectType.equals("shape")) sName += "shape";
		if(name.equals("")) sName += " id "+id;
		else if(name.length()>9) sName += " \""+name.substring(0,8)+"...\"";
		else sName += " \""+name+"\"";
		return sName;
	}
	/** HyperTalkでの種類名: card button / bkgnd field / card / bkgnd / stack など */
	public String typeName(){
		String t = objectType.equals("background") ? "bkgnd" : objectType.toLowerCase();
		if((objectType.equals("button") || objectType.equals("field") || objectType.equals("panel") || objectType.equals("shape")) && parent!=null){
			if(parent.objectType.equals("card")) t = "card "+t;
			else if(parent.objectType.equals("background")) t = "bkgnd "+t;
		}
		return t;
	}
	/** the name: card button "OK" / bkgnd field id 7 のように種類名と名前(無ければid) */
	public String getShortName(){
		if(name==null || name.isEmpty()) return getIdName();
		return typeName()+" \""+name+"\"";
	}
	/** 種類名 + id N */
	public String getIdName(){
		return typeName()+" id "+id;
	}
	/** the long name: stackまでの経路を付けた名前 */
	public String getLongName(){
		if(objectType.equals("stack")) return "stack \""+stackPathName()+"\"";
		OObject owner = longNameOwner();
		if(owner!=null) return getShortName()+" of "+owner.getLongName();
		return getShortName();
	}
	/** the long id: stackまでの経路を付けたid */
	public String getLongId(){
		if(objectType.equals("stack")) return getLongName();
		OObject owner = longNameOwner();
		if(owner!=null) return getIdName()+" of "+owner.getLongId();
		return getIdName();
	}
	/** the owner: カードならバックグラウンド、バックグラウンドならスタック、部品なら親。無ければnull */
	public OObject ownerObject(){
		if(objectType.equals("stack")) return null;
		if(!(this instanceof OCardBase) && (objectType.equals("card") || objectType.equals("background"))) return parent;
		if(objectType.equals("card")){
			OBackground bg = ((OCardBase)this).getBg();
			return bg!=null ? bg : ((OCardBase)this).stack;
		}
		if(objectType.equals("background")) return ((OCardBase)this).stack;
		return parent;
	}
	/** long name/long idでの上位オブジェクト。bg部品はカード経由(bkgnd field "x" of card id N of stack ...)で表す */
	private OObject longNameOwner(){
		if(objectType.equals("card") || objectType.equals("background")){
			return ((OCardBase)this).stack;
		}
		if(parent!=null && parent.objectType.equals("background")){
			OCard viewCard = ((OBackground)parent).viewCard;
			if(viewCard==null && PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null &&
				PCARD.pc.stack.curCard.bgid==parent.id){
				viewCard = PCARD.pc.stack.curCard;
			}
			if(viewCard!=null) return viewCard;
		}
		return parent;
	}
	/** スタックのパス: "<volume>:<folder>:…:<stack>" (TTalk.macStackPath) */
	String stackPathName(){
		File file = StackBundle.host(((OStack)this).file); //a stack file (.hzb ZIP) is where its file is, not its working folder
		if(file==null) return name;
		return TTalk.macStackPath(file.getPath());
	}

	public void setVisible(boolean in) {
		visible = in;
		if(parent!=null && 
				(parent.getClass()==OCard.class || parent.getClass()==OBackground.class)){
			((OCardBase)parent).changed = true;
		}
	}
	public void setText(String in){
		text = in;
	}
	public void setTextInternal(String in){
		text = in;
	}
	public boolean hasCheckpoint(int line) {
		TreeSet<Integer> set = checkpoints;
		return set!=null && set.contains(line);
	}

	/** チェックポイントの設定/解除。設定後の状態を返す */
	public boolean toggleCheckpoint(int line) {
		if(checkpoints==null) checkpoints = new TreeSet<Integer>();
		if(checkpoints.remove(line)){
			if(checkpoints.isEmpty()) checkpoints = null;
			return false;
		}
		checkpoints.add(line);
		return true;
	}

	public void clearCheckpoints() {
		checkpoints = null;
	}

	/**
	 * スクリプトを保存し直したときに、チェックポイントを同じ内容の行へ付け替える。
	 * 同じ行番号の内容が変わっていなければそのまま、変わっていれば同じ内容の行のうち
	 * 一番近いものへ移し、見つからなければ捨てる。
	 */
	static TreeSet<Integer> remapCheckpoints(TreeSet<Integer> checkpoints,
			ArrayList<String> oldScript, ArrayList<String> newScript) {
		if(checkpoints==null || checkpoints.isEmpty()) return null;
		TreeSet<Integer> remapped = new TreeSet<Integer>();
		for(int line : checkpoints){
			if(line<0 || line>=oldScript.size()) continue;
			String text = oldScript.get(line).trim();
			if(line<newScript.size() && newScript.get(line).trim().equals(text)){
				remapped.add(line);
				continue;
			}
			if(text.isEmpty()) continue;
			int best = -1;
			for(int i=0; i<newScript.size(); i++){
				if(!newScript.get(i).trim().equals(text)) continue;
				if(best<0 || Math.abs(i-line)<Math.abs(best-line)) best = i;
			}
			if(best>=0) remapped.add(best);
		}
		return remapped.isEmpty() ? null : remapped;
	}

	@SuppressWarnings("unchecked")
	public void setScript(String str) {
		ArrayList<String> oldScript = scriptList==null ? new ArrayList<String>() : scriptList;
		//a new list: a handler of this object that is running keeps its own lines (AOL UserTracker: set script of me to it in
		//the button's mouseDown, whose next line was then read from the new 3-line script)
		scriptList = new ArrayList<String>();
		
		//末尾の空行も残す(HyperCard: 「…end mouseUp」の後に空行のあるフィールドの内容を set the script すると、
		//the script もその空行で終わる)
		String[] strArray = str.split("\n", -1);
		for(int i=0; i<strArray.length; i++){
			scriptList.add(strArray[i]);
		}
		checkpoints = remapCheckpoints(checkpoints, oldScript, scriptList);
		lastErrorLine = -1;
		lastErrorMessage = null;
		
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		handlerList = null;
		handlerLineList = null;
		controlCache = null;
		jit = null;
		TalkJit.scriptEpoch++;
		if(getClass()==OStack.class){
			((OStack)this).changed = true;
		}
		else if((getClass()==OCard.class || getClass()==OBackground.class)){
			((OCardBase)this).changed = true;
		}
		else if(parent!=null && 
				(parent.getClass()==OCard.class || parent.getClass()==OBackground.class)){
			((OCardBase)parent).changed = true;
		}
		
		wrapFlag = false;
	}
}





//------------------------------------------

class OHyperCard extends OObject {
	static OHyperCard hc = new OHyperCard();
	
	//メイン
	public OHyperCard() {
    	objectType="hypercard";
	}
}

class OTitlebar extends OObject {
	static OTitlebar titlebar = new OTitlebar();
	
	//メイン
	public OTitlebar() {
    	objectType="titlebar";
	}
}

class OMenubar extends OObject {
	static OMenubar menubar = new OMenubar();
	
	//メイン
	public OMenubar() {
    	objectType="menubar";
	}

	//the visible of menuBar は実際のメニューバーの表示状態
	@Override
	public boolean getVisible(){
		if(PCARD.pc==null || PCARD.pc.getJMenuBar()==null) return super.getVisible();
		return PCARD.pc.getJMenuBar().isVisible();
	}
}

class OWindow extends OObject {
	static OWindow cdwindow;
	static OWindow msgwindow;
	static ArrayList<OWindow>list = new ArrayList<OWindow>();
	JFrame frame;
	JDialog dlog;
	TMP3 mp3;
	GPictWindow gpw;
	String movieHandle;
	private Rectangle unzoomedBounds;
	private boolean zoomed;
	
	//メイン
	public OWindow(JFrame inframe, boolean isCdWindow) {
    	objectType="window";
    	frame = inframe;
    	if(isCdWindow){
    		cdwindow = this;
    		this.name = "cd";
    	}
    	else this.name = inframe.getTitle();
    	
    	list.add(this);
	}

	//メイン(Msg)
	public OWindow(JDialog inframe) {
    	objectType="window";
    	dlog = inframe;
    	this.name = inframe.getTitle();
    	
    	list.add(this);
	}
	
	//メイン(Movieウィンドウ)
	public OWindow(TMP3 in_mp3) {
    	objectType="window";
    	mp3 = in_mp3;
    	this.name = mp3.name;
    	
    	list.add(this);
	}

	public OWindow(GPictWindow in_gpw) {
    	objectType="window";
    	gpw = in_gpw;
    	this.name = gpw.name;
    	id = nextWindowId++; //window ID n で指せるように(openPalette/closePaletteの第2引数)
    	
		list.add(this);
	}

	private static int nextWindowId = 1;

	/** このGPictWindowを持つOWindow */
	static OWindow find(GPictWindow gpw) {
		for(OWindow w : list) if(w.gpw==gpw) return w;
		return null;
	}

	/** window ID n */
	static OWindow findById(int id) {
		for(OWindow w : list) if((w.gpw!=null || w.movieHandle!=null) && w.id==id) return w;
		return null;
	}

	/** Script-visible entry for a Movie XCMD playback (movie N / window "name"); TQTMovie sets gpw and id after making the window. */
	public OWindow(String in_name,String in_movieHandle) {
		objectType="window";
		name=in_name;
		movieHandle=in_movieHandle;
		list.add(this);
	}

	boolean isMovie() { return movieHandle!=null; }

	/** A window of the Picture XCMD (picture N of HyperCard 2.4): a picture window that is neither a palette nor a movie. */
	boolean isPicture() { return movieHandle==null && gpw!=null && !gpw.isPaletteWindow() && gpw.isDisplayable(); }

	/** the number of movies / movie N: the Movie XCMD's windows in the order they were opened */
	static ArrayList<OWindow> movies() { return TQTMovie.scriptWindows(); }

	/** the number of pictures / picture N: the Picture XCMD's windows in the order they were opened */
	static ArrayList<OWindow> pictures() {
		ArrayList<OWindow> out = new ArrayList<OWindow>();
		for(OWindow w : list) if(w.isPicture()) out.add(w);
		return out;
	}

	/** movie N / movie "name" / picture N / picture "name" (HyperCard 2.4: a movie is also found by its file name). null when there is none. */
	static OWindow findMovieOrPicture(boolean movie, String ref) {
		if(ref==null) return null;
		ref = ref.trim();
		ArrayList<OWindow> all = movie ? movies() : pictures();
		if(ref.matches("[0-9]+")){
			int n = Integer.parseInt(ref);
			return n>=1 && n<=all.size() ? all.get(n-1) : null;
		}
		if(movie) return TQTMovie.findScriptWindow(ref);
		for(OWindow w : all) if(ref.equalsIgnoreCase(w.name)) return w;
		return null;
	}

	/** movie id N / picture id N */
	static OWindow findMovieOrPictureById(boolean movie, int id) {
		for(OWindow w : movie ? movies() : pictures()) if(w.id==id) return w;
		return null;
	}

	static OWindow findPaletteWindow(String name) {
		for(int i=list.size()-1; i>=0; i--){
			OWindow window = list.get(i);
			if(window.gpw==null) continue;
			if(window.gpw.isPaletteWindow() && !window.gpw.isDisplayable()){
				list.remove(i);
				continue;
			}
			if(GPictWindow.samePaletteName(window.gpw.isPaletteWindow(), window.name, name)){
				return window;
			}
		}
		return null;
	}
	
	public void Command(String cmdStr) throws xTalkException {
		if(movieHandle!=null){
			//send "play" / "pause" / "rewind" / "stepFwd" / "stepRev" / "idle" to movie N (HyperCard 2.4 Movie XCMD commands)
			String c = cmdStr.trim();
			int sp = c.indexOf(' ');
			String verb = (sp<0 ? c : c.substring(0,sp)).toLowerCase(java.util.Locale.ROOT);
			switch(verb){
				case "play": TQTMovie.play(movieHandle); break;
				case "pause": TQTMovie.pause(movieHandle); break;
				case "rewind": TQTMovie.rewind(movieHandle); break;
				case "stepfwd": TQTMovie.step(movieHandle, 1); break;
				case "steprev": TQTMovie.step(movieHandle, -1); break;
				case "idle": TQTMovie.idle(movieHandle); break;
				case "resizetomovie": case "preroll": case "showposter": case "cancelaction": break; //nothing to do here
				default: System.out.println("Movie window: command not handled here: "+cmdStr); break; //findText, nudge, rotate…: the movie shows without them
			}
			return;
		}
		if(mp3!=null){
			if(cmdStr.equalsIgnoreCase("Play")){
				mp3.mp3play();
			}
		}
	}

	/** Whether the window is showing (hide/show window set the real window, not the flag). */
	@Override public boolean getVisible(){
		if(frame!=null) return frame.isVisible();
		if(dlog!=null) return dlog.isVisible();
		if(gpw!=null) return gpw.isVisible();
		return super.getVisible();
	}

	public boolean isZoomed() {
		return zoomed;
	}

	public void setZoomed(boolean in) throws xTalkException {
		if(this != cdwindow || frame == null || frame != PCARD.pc){
			throw new xTalkException("Cannot set zoomed on this window");
		}
		if(in == zoomed) return;

		if(in){
			unzoomedBounds = new Rectangle(frame.getBounds());
			frame.setExtendedState(Frame.NORMAL);
			if(PCARD.pc.stack!=null) PCARD.pc.stack.windowNominal = null; //zoomed: the whole card
			PCARD.pc.setNewBounds();
			zoomed = true;
		}
		else{
			frame.setExtendedState(Frame.NORMAL);
			if(unzoomedBounds != null){
				frame.setBounds(unzoomedBounds);
			}
			zoomed = false;
		}
	}
	
	public void Close() {
		if(this==cdwindow){
			//close window "スタック名" / close card window: スタックを閉じる(クローズボックスと同じ経路)
			PCARD.closeStackFromCloseBox();
			return;
		}
		if(movieHandle!=null){
			TQTMovie.dispose(movieHandle); //closes the picture window too
			list.remove(this);
			return;
		}
		if(gpw!=null){
			if(gpw.isPaletteWindow()) gpw.closePalette(); //closePaletteを送る
			else { gpw.dispose(); list.remove(this); }
			return;
		}
		if(mp3!=null){
			mp3.mp3stop();
	    	list.remove(this);
		}
	}

	void removeMovieRegistration(){list.remove(this);}
}

/**
 * window "X" of a 68K external (an XWindoid the external created with NewXWindow). HyperCard keeps a few properties of
 * such a window itself (rect, loc, visible, name, id, owner); every other property goes to the external as an
 * xGetPropEvt/xSetPropEvt, and "send" as an xSendEvt.
 */
class OXWindow extends OObject {
	final Xcmd68k.ExternalWindow external;

	OXWindow(Xcmd68k.ExternalWindow external){
		objectType="window";
		this.external=external;
		WindowManager68k.Window win=external.session.xwindows.record(external.window);
		this.name=win==null?"":win.title;
		this.id=external.window.record;
	}

	/** The external window titled {@code name}, or null (no external keeps one). */
	static OXWindow find(String name){
		// "" too: TrueIdle's window has no title, and its documented way to stop it is close window ""
		if(name==null||!Xcmd68k.hasWindowSessions())return null;
		Xcmd68k.ExternalWindow w=Xcmd68k.findWindow(name);
		return w==null?null:new OXWindow(w);
	}

	/** The external window whose id (WindowPtr) is {@code id}, or null. */
	static OXWindow findById(int id){
		if(!Xcmd68k.hasWindowSessions())return null;
		Xcmd68k.ExternalWindow w=Xcmd68k.findWindowById(id);
		return w==null?null:new OXWindow(w);
	}

	private WindowManager68k.Window record(){return external.session.xwindows.record(external.window);}

	@Override
	public boolean getVisible(){WindowManager68k.Window win=record();return win!=null&&win.visible;}

	@Override
	public void setVisible(boolean in){
		try{
			if(offeredToExternal("visible",Boolean.toString(in)))return;
			external.call((x,w)->{x.show(w,in);return null;});
		}
		catch(M68kException e){throw new RuntimeException(e.getMessage(),e);}
	}

	/**
	 * HyperCard asks the external first about the properties it keeps itself (HC J-2.3 measured, Oct 24 2026: set loc, hide/show
	 * and set visible each send an xSetPropEvt; reading rect/loc/visible sends an xGetPropEvt too, whose answer HyperZebra does
	 * not use - HyperCard's own values stand), and does its own part only when the external passes: ListTable positions its table and marks it ready to draw in its xSetPropEvt handler - without it the
	 * table windows stayed blank. True when the external took the property.
	 */
	private boolean offeredToExternal(String property,String value) throws M68kException {
		Boolean handled=external.call((x,w)->x.setProperty(w,property,value));
		return handled!=null&&handled;
	}


	/** The card window's top left in the external's global coordinates: HyperCard gives an external window's loc and rect
	 *  relative to it (HC J-2.3: UxProgressBar placed at card point 20,30 reads back loc 20,30, set the loc to 100,100 reads 100,100;
	 *  they were the VM's screen coordinates, 112,157 for the card's own corner). */
	private int[] cardOrigin(){
		try{Toolbox68k tb=external.session.macApi.application();java.awt.Rectangle r=tb.host().toGuestRect(TUtil.cardWindowRect());return new int[]{r.x,r.y};}
		catch(RuntimeException e){return new int[]{0,0};}
	}

	/** The property when HyperCard or the external knows it; an error for one neither does. */
	String getProperty(String property) throws xTalkException {
		String p=property.toLowerCase();
		WindowManager68k.Window win=record();
		if(win==null)throw new xTalkException("No such window: {0}", name);
		int[] o=p.equals("rect")||p.equals("rectangle")||p.equals("loc")||p.equals("location")||p.equals("topleft")||p.equals("bottomright")||p.equals("botright")||
			p.equals("left")||p.equals("top")||p.equals("right")||p.equals("bottom")?cardOrigin():null;
		int left=o==null?win.left:win.left-o[0],top=o==null?win.top:win.top-o[1];
		if(p.equals("rect")||p.equals("rectangle"))return left+","+top+","+(left+win.width)+","+(top+win.height);
		if(p.equals("loc")||p.equals("location")||p.equals("topleft"))return left+","+top;
		//topLeft/bottomRight of window wn (Macbeth: is the window on the screen?), and the sides
		if(p.equals("bottomright")||p.equals("botright"))return (left+win.width)+","+(top+win.height);
		if(p.equals("left"))return Integer.toString(left);
		if(p.equals("top"))return Integer.toString(top);
		if(p.equals("right"))return Integer.toString(left+win.width);
		if(p.equals("bottom"))return Integer.toString(top+win.height);
		if(p.equals("width"))return Integer.toString(win.width);
		if(p.equals("height"))return Integer.toString(win.height);
		if(p.equals("visible"))return Boolean.toString(win.visible);
		if(p.equals("name")||p.equals("long name")||p.equals("abbrev name")||p.equals("abbreviated name")||p.equals("abbr name"))return getShortName();
		if(p.equals("short name"))return name;
		if(p.equals("id")||p.equals("long id")||p.equals("short id")||p.equals("abbrev id"))return Integer.toString(id);
		if(p.equals("owner"))return owner();
		if(p.equals("zoomed"))return "false";
		if(p.equals("properties")){
			//the external's own list first (UxProgressBar, UxMovie document theirs); HyperCard's when it passes
			try{String v=external.call((x,w)->x.getProperty(w,property));if(v!=null&&!v.isEmpty())return v;}catch(M68kException e){}
			return "id\nname\nowner\nvisible\nrect\nloc";
		}
		try{
			String v=external.call((x,w)->x.getProperty(w,property));
			if(v==null)throw new xTalkException("Window has no property {0}: {1}", property, name);
			return v;
		}
		catch(M68kException e){throw new xTalkException("Cannot read {1} of window {0}: {2}", name, property, e.getMessage());}
	}

	/** Sets a property HyperCard keeps, else hands it to the external; an error when it passes the event. */
	void setProperty(String property,String value) throws xTalkException {
		String p=property.toLowerCase();
		if(record()==null)throw new xTalkException("No such window: {0}", name);
		try{
			if(p.equals("visible")){setVisible(TUtil.propertyBoolean("visible",value));return;}
			if((p.equals("loc")||p.equals("location")||p.equals("rect")||p.equals("rectangle"))&&offeredToExternal(property,value))return; // the external's first (offeredToExternal)
			int[] o=cardOrigin(); // the values are relative to the card window, as HyperCard reads them (getProperty)
			if(p.equals("loc")||p.equals("location")||p.equals("topleft")){
				int[] v=numbers(value,2,"Coordinates are required here");
				external.call((x,w)->{x.move(w,v[0]+o[0],v[1]+o[1]);return null;});return;
			}
			WindowManager68k.Window win=record();
			if(p.equals("left")||p.equals("top")||p.equals("right")||p.equals("bottom")||p.equals("bottomright")||p.equals("botright")){
				//the window moves by its side or corner, keeping its size (HyperCard)
				int[] v=numbers(value,p.startsWith("bot")?2:1,"A number is required here");
				int left=p.equals("left")?v[0]+o[0]:p.equals("right")?v[0]+o[0]-win.width:p.startsWith("bot")?v[0]+o[0]-win.width:win.left;
				int top=p.equals("top")?v[0]+o[1]:p.equals("bottom")?v[0]+o[1]-win.height:p.startsWith("bot")?v[1]+o[1]-win.height:win.top;
				external.call((x,w)->{x.move(w,left,top);return null;});return;
			}
			if(p.equals("rect")||p.equals("rectangle")){
				int[] v=numbers(value,4,"A rectangle is required here");
				external.call((x,w)->{x.move(w,v[0]+o[0],v[1]+o[1]);x.resize(w,v[2]-v[0],v[3]-v[1]);return null;});return;
			}
			Boolean handled=external.call((x,w)->x.setProperty(w,property,value));
			if(handled==null||!handled)throw new xTalkException("Window has no property {0}: {1}", property, name);
		}
		catch(M68kException e){throw new xTalkException("Cannot set {1} of window {0}: {2}", name, property, e.getMessage());}
	}

	/** the owner: the external's name, derived from its resource file name when needed. */
	private String owner(){
		Rsrc.xcmdClass x=external.session.external;
		if(x!=null&&x.name!=null&&!x.name.isEmpty())return x.name;
		String n=external.session.name.replaceFirst("\\.[^.]*$","");
		java.util.regex.Matcher m=java.util.regex.Pattern.compile("^X(?:CMD|FCN)_[^_]+_-?\\d+_(.+)$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(n);
		return m.matches()?m.group(1):n;
	}

	private static int[] numbers(String value,int count,String error) throws xTalkException {
		String[] parts=value.split(",");
		if(parts.length!=count)throw new xTalkException(error);
		int[] v=new int[count];
		try{for(int i=0;i<count;i++)v[i]=(int)Double.parseDouble(parts[i].trim());}
		catch(NumberFormatException e){throw new xTalkException(error);}
		return v;
	}

	/** send "message" to window "X": the external gets an xSendEvt; its return value is the result. */
	String send(String message) throws xTalkException {
		try{return external.call((x,w)->x.send(w,message));}
		catch(M68kException e){throw new xTalkException("Cannot send a message to window {0}: {1}", name, e.getMessage());}
	}

	/** close window "X": xCloseEvt, then the window closes. */
	void close() throws xTalkException {
		try{external.call((x,w)->{x.closeFromScript(w);return null;});}
		catch(M68kException e){throw new xTalkException("Cannot close window {0}: {1}", name, e.getMessage());}
	}
}

class OMsg extends OObject {
	static OMsg msg = new OMsg();
	
	//メイン
	public OMsg() {
    	objectType="msg";
	}

	/** Whether the message box is shown (the field was never updated, so "the visible of msg" was always true) */
	@Override public boolean getVisible(){
		return GMsg.msg!=null ? GMsg.msg.isVisible() : super.getVisible();
	}
}

class OToolWindow extends OObject {
	static OToolWindow toolwindow = new OToolWindow();

	//メイン
	public OToolWindow() {
    	objectType="tool window";
	}

	/** Whether the Tools palette is shown ("get the visible of tool window": ShowDialog XCMD's PICT Maker card) */
	@Override public boolean getVisible(){
		PCARD pc = PCARD.pc;
		return pc!=null && pc.toolbar!=null && pc.toolbar.tb!=null && pc.toolbar.tb.isVisible();
	}
}

class OPatternWindow extends OObject {
	static OPatternWindow patternwindow = new OPatternWindow();

	//本物のHyperCardのPatternsパレットに相当する独立ウインドウは無いため、
	//show/hideを例外にせず受け付けるだけのオブジェクト
	public OPatternWindow() {
    	objectType="pattern window";
	}
}

/**
 * HyperCard 2.x's own windoids that HyperZebra has no window for: "Scroll" (the card-scrolling palette) and "FatBits".
 * HyperCard lists them in the windows even while they are hidden, and stacks place or hide them at start-up
 * (Myst Preview: set the loc of window "scroll" to "-200,-200" / hide window "scroll"), so they keep the rect and
 * visibility a script gives them without showing anything.
 */
class OHcWindow extends OObject {
	static final OHcWindow scroll = new OHcWindow("Scroll", 0, 0, 120, 112);
	static final OHcWindow fatbits = new OHcWindow("FatBits", 0, 0, 64, 64);
	//the answer and ask dialogs (HC J-2.3 / 2.4 probe Oth25 Answer Window: the topLeft of window "Answer" is a point and a set one
	//is where the next dialog comes up, but there is no window "Answer" and the windows do not list them). HyperCard 10th
	//Anniversary: set the loc of window "answer" to 173,196 before an answer
	static final OHcWindow answer = new OHcWindow("Answer", 0, 0, 400, 120);
	static final OHcWindow ask = new OHcWindow("Ask", 0, 0, 335, 103);
	boolean shown;
	/** answer/ask: a script gave it a place (else the dialog is centred on the card window) */
	boolean placed;
	private OHcWindow(String name, int left, int top, int width, int height) {
		objectType="hc window";
		this.name=name;
		this.left=left;this.top=top;this.width=width;this.height=height;
	}
	static OHcWindow named(String n) {
		if(n==null) return null;
		n=n.trim();
		if(n.equalsIgnoreCase("scroll")) return scroll;
		if(n.equalsIgnoreCase("fatbits") || n.equalsIgnoreCase("fat bits")) return fatbits;
		if(n.equalsIgnoreCase("answer")) return answer;
		if(n.equalsIgnoreCase("ask")) return ask;
		return null;
	}
	/** the answer/ask dialog's window, which "there is a window" does not find */
	boolean isDialog(){ return this==answer || this==ask; }
	/** where a dialog of a stack's script comes up, in the card window's coordinates: the place a script gave it, or centred */
	java.awt.Point dialogTopLeft(int cardWidth, int cardHeight){
		return placed ? new java.awt.Point(left, top) : new java.awt.Point((cardWidth-width)/2, (cardHeight-height)/2);
	}
	@Override public boolean getVisible(){ return shown; }
	@Override public void setVisible(boolean visible){ shown=visible; }
	@Override public String getShortName(){ return "window \""+name+"\""; }
}

/** window "<stack>" of a stack open in another window (TTalk.windowStacks, shown by a GStackWindow): show/hide and visible */
class OStackWindow extends OObject {
	final String key;
	private OStackWindow(String key, String name) {
		objectType="stack window";
		this.key=key;
		this.name=name;
	}
	static OStackWindow named(String n) {
		if(n==null || n.trim().isEmpty()) return null;
		n=java.text.Normalizer.normalize(n.trim(), java.text.Normalizer.Form.NFC);
		if(n.toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) n=n.substring(0, n.length()-4);
		synchronized(TTalk.windowStacks){
			for(String k : TTalk.windowStacks.keySet()){
				String title=TTalk.windowStackTitles.get(k);
				if(title==null) title=k.substring(k.lastIndexOf(':')+1);
				title=java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFC);
				if(title.equalsIgnoreCase(n)) return new OStackWindow(k, title);
			}
		}
		return null;
	}
	@Override public boolean getVisible(){ return GStackWindow.isShown(key); }
	@Override public void setVisible(boolean visible){ GStackWindow.setShown(key, visible); }
	@Override public String getShortName(){ return "window \""+name+"\""; }
}

//"pattern N"(1〜40): ツールパレットのパターンそのもの。HyperTalkから
//中身を読み書きするためのオブジェクト(the imageData / the text / the width /
//the height of pattern 3)。
class OPattern extends OObject {
	final int index; //0始まり
	public OPattern(int index){
		objectType="pattern";
		this.index=index;
	}
	@Override
	public String getShortName(){
		return "pattern "+(index+1);
	}
	PatButton button(){
		return PCARD.pc==null ? null : PCARD.pc.pat;
	}
	java.awt.image.BufferedImage image(){
		PatButton b=button();
		return (b==null || index<0 || index>=b.patterns.length) ? null : b.patterns[index];
	}
	void setImage(java.awt.image.BufferedImage image){
		PatButton b=button();
		if(b!=null) b.store(index, image);
	}
}

class OPicture extends OObject {
	OCardBase parent;
	//メイン
	public OPicture(OCardBase prt) {
    	objectType="picture";
    	parent = prt;
	}
	
	@Override
	public boolean getVisible(){
		return parent.showPict;
	}
	
	@Override
	public void setVisible(boolean in){
		parent.showPict = in;
		if(PCARD.lockedScreen==false){
			parent.stack.pcard.repaint();
		}
	}
}

class OMenu extends OObject {
	JMenu menu;

	//メイン
	public OMenu(JMenu menu) {
    	objectType="menu";
    	this.menu = menu;
    	this.name = TTalk.scriptMenuName(menu);
	}

	//メニューの名前はメニュータイトルそのもの("menu \"X\""のような修飾は付かない)。
	//これが無いと"get the name of menu N"が"Menu id 0"のような値を返してしまい、
	//"create menu \"LOADING\"..repeat..if it = \"LOADING\" then exit repeat.."
	//のようなメニュー再構築の定番処理が永久ループになってしまう
	@Override
	public String getShortName(){
		return TTalk.scriptMenuName(menu);
	}
}


//menuItem N of menu X。実体は PCARD.pc.menu.mb 側のJMenuItem
class OMenuItem extends OObject {
	JMenu menu;
	int index; //ポップアップ内の位置(0始まり、区切り線も数える)

	public OMenuItem(JMenu menu, int index) {
		objectType="menuItem";
		this.menu = menu;
		this.index = index;
		javax.swing.JMenuItem mi = item();
		this.name = mi==null ? "-" : TTalk.scriptMenuItemText(menu, mi);
	}

	javax.swing.JMenuItem item() {
		if(menu==null || index<0 || index>=menu.getPopupMenu().getComponentCount()) return null;
		java.awt.Component c = menu.getPopupMenu().getComponent(index);
		return (c instanceof javax.swing.JMenuItem) ? (javax.swing.JMenuItem)c : null;
	}

	/** The separator at this position ("-" item), or null */
	java.awt.Component separator() {
		if(menu==null || index<0 || index>=menu.getPopupMenu().getComponentCount()) return null;
		java.awt.Component c = menu.getPopupMenu().getComponent(index);
		return (c instanceof javax.swing.JMenuItem) ? null : c;
	}

	@Override
	public String getShortName(){
		javax.swing.JMenuItem mi = item();
		return mi==null ? "-" : TTalk.scriptMenuItemText(menu, mi);
	}
}

class OMonitor extends OObject {
	static OMonitor monitor = new OMonitor();
	
	//メイン
	public OMonitor() {
    	objectType="monitor";
	}
}

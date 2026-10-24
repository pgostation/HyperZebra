import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
//import java.awt.event.WindowEvent;
//import java.awt.event.WindowListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

//import javax.swing.JMenu;
//import javax.swing.JMenuBar;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

//マウス、キーボードを使うものをここに集めています。

public class GUI implements MouseListener, MouseMotionListener, KeyListener {
    public static GUI gui = new GUI();
    static final int SCRIPT_SHORTCUT_NONE = 0;
    static final int SCRIPT_SHORTCUT_ABORT = 1;
    static final int SCRIPT_SHORTCUT_TRACE = 2;
    private static boolean globalKeyDispatcherInstalled;
    private static boolean globalMouseWatcherInstalled;
    
    //マウス
    //スクリプト実行スレッド(the mouseClick等)とEDT(マウスイベント)の両方から
    //読み書きされるため、可視性を保証するためvolatileにしている
    static int mouseH=0;
    static int mouseV=0;
    static volatile int clickH=0;
    static volatile int clickV=0;
    static volatile boolean mouseClicked = false;
    static volatile boolean mouseDown = false;
    /** Mouse presses so far (a 68K external's event queue turns new ones into mouseDown events). */
    static volatile int mousePresses;
    static volatile String clickLine = "";
    static volatile OField clickField;
    //clickField内でクリックされた位置(フィールドコンポーネント自身の左上起点、
    //物理ピクセル=MouseEventの座標そのまま)。clickH/clickVはカード座標系
    //かつ論理座標(scale換算)のため、2倍表示モード等で正確な文字位置の
    //特定に使うにはここの値を用いる(the clickText / the clickChunk用)。
    static volatile int clickFieldLocalX=0;
    static volatile int clickFieldLocalY=0;
    /** The field of the last click (the clickChunk, clickText and clickLine; clickFieldLocalX/Y is where). Kept apart from
     *  clickField, the field the selection functions look at: HyperCard records a click in a locked or unlocked field for
     *  these also while a handler runs (wait until the mouseClick), where the click selects nothing. */
    static volatile OField clickTextField;
    /** The press `the mouse` has taken (mousePresses then): its release is no mouseClick any more. */
    static volatile int consumedPress = -1;
    /** mouseClicked came from the user's mouse, not from a script's `click at` (only such a click makes the mouse "down"). */
    static volatile boolean userClickPending = false;
    
    //キー
    static int[] key = new int[256];

	//修飾キーの状態(key[11]=shift, 12=option, 13=control, 14=command)を、マウスイベントが運んできた
	//実際の修飾キーに合わせる。key[]はキーリスナーで追っているが、フォーカスが子部品やメニューに
	//あるとき(Cmd-+で拡大した直後など)のキー押下/解放は届かず、Cmdが押されたままの状態が残る
	//ことがある。その状態で鉛筆でクリックすると、Cmd+クリック(FatBitsの出入り)と誤認されて
	//拡大表示が解除されてしまっていた。ペイントツールへ渡す前にここで同期する。
	static void syncModifiers(java.awt.event.InputEvent e){
		int m = e.getModifiersEx();
		boolean macOS = System.getProperty("os.name").toLowerCase().startsWith("mac os x");
		syncModifier(11, (m&InputEvent.SHIFT_DOWN_MASK)!=0);
		//スタック個別設定で別のキーにしたCommand/Option/Controlは、そのキーの押下/解放(ownModifierKey)で追う
		if(!StackSettings.hasOwnKey(12)) syncModifier(12, (m&InputEvent.ALT_DOWN_MASK)!=0);
		if(!StackSettings.hasOwnKey(13)) syncModifier(13, (m&InputEvent.CTRL_DOWN_MASK)!=0);
		//OSX以外ではctrlキーもcmdキーとして扱う(keyPressed参照)
		if(!StackSettings.hasOwnKey(14)) syncModifier(14, (m&InputEvent.META_DOWN_MASK)!=0 || (!macOS && (m&InputEvent.CTRL_DOWN_MASK)!=0));
	}

	/**
	 * A key the open stack's own settings made Command, Option or Control (StackSettings): it sets that modifier in key[]
	 * and types nothing itself. Returns whether the event was such a key. While one of them holds Command or Control,
	 * characters typed are shortcuts, not text (ownModifierHeld).
	 */
	static boolean ownModifierKey(KeyEvent e){
		int index = StackSettings.ownModifierIndex(e.getKeyCode(), e.getKeyLocation());
		if(index<0) return false;
		if(e.getID()==KeyEvent.KEY_PRESSED){ if(key[index]==0) key[index]=3; }
		else if(e.getID()==KeyEvent.KEY_RELEASED){ if(key[index]==2) key[index]=0; if(key[index]==3) key[index]=1; }
		return true;
	}
	/** Whether the open stack's own key for Command or Control is held (characters typed then are not text). */
	static boolean ownModifierHeld(){
		return StackSettings.hasOwnKey(14) && key[14]>=2 || StackSettings.hasOwnKey(13) && key[13]>=2;
	}
	/** Command held for this key event: the stack's own Command key, else Command (Ctrl too off macOS). */
	static boolean commandDown(KeyEvent e){
		if(StackSettings.hasOwnKey(14)) return key[14]>=2;
		return e.isMetaDown() || (!GMenu.isMacOSX() && e.isControlDown());
	}
	/** Control held for this key event: the stack's own Control key, else Ctrl. */
	static boolean controlDown(KeyEvent e){
		if(StackSettings.hasOwnKey(13)) return key[13]>=2;
		return e.isControlDown();
	}
	/** The host modifier key's Mac modifier (key[] index 12, 13 or 14) has gone to another key in the open stack: it no longer stands for it. */
	private static int givenAway(int c){
		return (c==12 || c==13 || c==14) && StackSettings.hasOwnKey(c) ? -1 : c;
	}
	private static void syncModifier(int index, boolean down){
		if(down){ if(key[index]==0) key[index]=2; }
		else key[index]=0;
	}

	//key[]はカードウインドウのキーリスナーで追っているので、キーを離したときにフォーカスが別のウインドウ
	//(ピークで開いたスクリプトエディタ、メッセージボックス、他のアプリ)にあると解放が届かず、押されたまま残る。
	//ピークでは Command-Option が押されたままになり、エディタを閉じた後の普通のクリックでもスクリプトが開いていた。
	//そこで解放はどのウインドウで起きても拾い(installGlobalKeyDispatcher)、カードウインドウが非アクティブに
	//なったら全部離したことにする(cardDeactivated)。

	/** クリックそのものが Command-Option 付きか(Windows/Linux では Ctrl も Command)。ピークのクリックは key[] ではなくこれで決める。 */
	static boolean isPeekClick(InputEvent e){
		//スクリプトを開けるのはユーザーレベル5で、スタックがcantPeekでないときだけ
		if(!StackProtection.canOpenScripts()) return false;
		int m = e.getModifiersEx();
		boolean cmd = (m&InputEvent.META_DOWN_MASK)!=0 || (!GMenu.isMacOSX() && (m&InputEvent.CTRL_DOWN_MASK)!=0);
		return cmd && (m&InputEvent.ALT_DOWN_MASK)!=0;
	}
	/** ピークを終える: 輪郭の表示とクリックでスクリプトを開くモードから抜け、修飾キーを離したことにする(この後のキーはスクリプトエディタへ行くため)。 */
	static void endPeek(){
		clearModifierKeys();
		stroke_frag = 0;
		PCARD.editMode = 0;
		addAllListener();
	}
	/** Shift/Option/Control/Command/CapsLock/Windows とその左右(111-122)を離したことにする */
	static void clearModifierKeys(){
		for(int i=11; i<=16; i++){ key[i]=0; key[i+100]=0; key[i+106]=0; }
	}
	/** カードウインドウにフォーカスがあるのにフォーカスを持つ部品が無いと、AWT はキーイベントを先行入力として溜めたまま
	 *  配らない(フォーカスを受けるはずの部品がカード移動で消えた等)。起動によってはそのまま全キーが効かなかった
	 *  (K-0Fighters のバトル)。そのときだけカードウインドウ自身にフォーカスを渡す(フィールドの入力は奪わない)。 */
	static void ensureCardFocus(){
		SwingUtilities.invokeLater(() -> {
			PCARD pc = PCARD.pc;
			if(pc==null || !pc.isShowing() || !pc.isFocused()) return;
			if(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()!=null) return;
			pc.requestFocusInWindow();
		});
	}
	/** カードウインドウがアクティブになった: ダイアログなどで非アクティブの間に離したキー(特にCommand)が押されたまま残らないよう、
	 *  押されていたキーは離したことにする(押し続けているキーはオートリピートの keyPressed で戻る) */
	static void cardActivated(){
		java.util.Arrays.fill(key, 0);
	}
	/** カードウインドウが非アクティブになった: 押されていたキーは全部離したことにし、ピーク中なら終える */
	static void cardDeactivated(){
		java.util.Arrays.fill(key, 0);
		if(StackSettings.controlKeys!=null) MacAppHost.suspendSystemHotKeys(false);
		if(stroke_frag!=0 || PCARD.editMode!=0){
			if(PCARDFrame.pc!=null && PCARDFrame.pc.stack!=null && PCARDFrame.pc.stack.curCard!=null) endPeek();
			else { stroke_frag = 0; PCARD.editMode = 0; }
		}
	}

	//スクリプトがキーの状態を読んでいる間(KStealKeyX などの XCMD、the optionKey など)は、キーはゲームの操作に使われている。
	//Windows では Alt(=Option)を離すとウインドウのシステムメニューに入り、続く上下キーでメニューが開いてキーがゲームに
	//届かなくなっていた(K-0Fighters は Option を攻撃に使う)。その間はシステムメニューに入るキーをOSへ渡さない。
	/** スクリプトが最後にキーの状態を読んでから、まだキーを奪っているとみなす時間(ms)。ラウンドの切り替わりなど読まない間も続ける。 */
	static final long KEY_POLL_HOLD_MS = 3000;
	private static volatile long lastKeyPoll = Long.MIN_VALUE/2;
	/** スクリプトや XCMD がキーの状態を読んだ */
	static void noteKeyPoll(){ lastKeyPoll = System.currentTimeMillis(); }
	static boolean scriptOwnsKeys(){ return System.currentTimeMillis()-lastKeyPoll < KEY_POLL_HOLD_MS; }
	/** Windows/Linux でシステムメニュー・メニューバーの操作になるキーか: Alt 単独、Alt+キー(Alt+F4 は閉じるので残す)、F10 */
	static boolean isSystemMenuKey(KeyEvent e){
		int k = e.getKeyCode();
		if(k==KeyEvent.VK_ALT || k==KeyEvent.VK_F10) return true;
		return (e.getModifiersEx()&InputEvent.ALT_DOWN_MASK)!=0 && k!=KeyEvent.VK_F4;
	}
	/** カードウインドウ(キーを key[] に取る GUI のリスナーを持つ)宛ての、スクリプトがキーを奪っている間のシステムメニューのキーか。
	 *  macOS では Option がメニューを動かさないので対象外。フィールドなどカード上の部品で入力中のキーには触らない。 */
	static boolean isSystemMenuKeyWhileScriptOwnsKeys(KeyEvent e){
		if(GMenu.isMacOSX() || !isSystemMenuKey(e) || !scriptOwnsKeys()) return false;
		return PCARD.pc!=null && e.getComponent()==PCARD.pc;
	}

    static synchronized void installGlobalKeyDispatcher() {
		if(globalKeyDispatcherInstalled) return;
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
			//キーの解放はどのウインドウで起きても key[] に反映する(カードウインドウ自身の分と重なっても結果は同じ)
			//修飾キーの押下/解放はダイアログ表示中などに取りこぼすので、どのキーイベントでも実際の状態に合わせる
			if(e.getID()==KeyEvent.KEY_RELEASED || e.getID()==KeyEvent.KEY_PRESSED) syncModifiers(e);
			if(inCardWindow(e) && stackKeys(e)) return true;
			if(isSystemMenuKeyWhileScriptOwnsKeys(e)){
				//消費したキーイベントは部品のリスナーにもOS(Windows のシステムメニュー)にも渡らないので、カードウインドウの
				//リスナー(key[]、keyDown)へはここで渡す
				if(e.getID()==KeyEvent.KEY_PRESSED) GUI.gui.keyPressed(e);
				else if(e.getID()==KeyEvent.KEY_RELEASED) GUI.gui.keyReleased(e);
				e.consume();
				return true;
			}
			if(e.getID()==KeyEvent.KEY_RELEASED){ GUI.gui.keyReleased(e); return false; }
			if(e.getID()!=KeyEvent.KEY_PRESSED) return false;
			return GUI.gui.handleScriptShortcut(e);
		});
		globalKeyDispatcherInstalled = true;
	}

	//"the mouse"(mouseDown)は各部品のMouseListenerが個別に更新していたため、
	//押した部品がボタンの押下中にカードごと差し替わる等でreleaseイベントの
	//届け先を失うと、mouseDownがtrueのまま固定されてしまうことがあった
	//(例: repeat / if the mouse is down then answer ... が、離した後も
	//永久にtrueと判定されてダイアログを出し続ける)。
	//アプリ内のどのウィンドウ・部品で起きたクリックでも確実に拾えるよう、
	//Toolkitレベルのグローバルリスナーで実際の押下/解放を直接監視する。
	static synchronized void installGlobalMouseWatcher() {
		if(globalMouseWatcherInstalled) return;
		Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
			if(!(e instanceof MouseEvent)) return;
			int id = e.getID();
			if(id==MouseEvent.MOUSE_PRESSED || id==MouseEvent.MOUSE_RELEASED) syncModifiers((MouseEvent)e);
			if(id==MouseEvent.MOUSE_PRESSED){ mouseDown = true; mousePresses++; if(!TTalk.idle) handlerPress = mousePresses; noteClickLoc((MouseEvent)e); }
			else if(id==MouseEvent.MOUSE_RELEASED) mouseDown = false;
		}, AWTEvent.MOUSE_EVENT_MASK);
		globalMouseWatcherInstalled = true;
	}

	/** the clickLoc: where the mouse went down on the card, whichever part took the click (HyperCard keeps it for clicks
	 *  on buttons, fields and the card alike, also while a handler runs). Buttons used to leave it alone (a mouseUp
	 *  handler read the previous click) or set their own top-left while a handler ran; a click that reached the card
	 *  window itself (the card pane has no listener of its own) gave window coordinates, off by the title and menu bars. */
	static void noteClickLoc(MouseEvent e){
		PCARD pc = PCARD.pc;
		Component c = e.getComponent();
		if(pc==null || pc.mainPane==null || c==null || pc.stack==null) return;
		if(c!=pc && !SwingUtilities.isDescendingFrom(c, pc.mainPane)) return;
		Point p = SwingUtilities.convertPoint(c, e.getPoint(), pc.mainPane);
		if(p.x<0 || p.y<0 || p.x>=pc.mainPane.getWidth() || p.y>=pc.mainPane.getHeight()) return;
		clickH = PCARD.toLogical(p.x);
		clickV = PCARD.toLogical(p.y);
	}

	/** The key event is for the card window (its fields and buttons too). */
	private static boolean inCardWindow(KeyEvent e){
		PCARD pc = PCARD.pc;
		Component c = e.getComponent();
		return pc!=null && c!=null && (c==pc || SwingUtilities.getWindowAncestor(c)==pc);
	}
	/** An own modifier key was pressed and its character (KEY_TYPED) is still to come. */
	private static boolean ownKeyTyping;
	/**
	 * The open stack's own keyboard settings (StackSettings), for every key of the card window, a field being typed in too:
	 * its own Command/Option/Control keys set the modifier and type nothing; while its own Command or Control is held the
	 * characters are not typed into fields; Control held lets Control-arrow reach the stack instead of Mission Control
	 * (macOS, when the stack's settings say so). Returns whether the event was used up.
	 */
	private static boolean stackKeys(KeyEvent e){
		if(e.getKeyCode()==KeyEvent.VK_CONTROL && Boolean.TRUE.equals(StackSettings.controlKeys)){
			if(e.getID()==KeyEvent.KEY_PRESSED) MacAppHost.suspendSystemHotKeys(true);
			else if(e.getID()==KeyEvent.KEY_RELEASED) MacAppHost.suspendSystemHotKeys(false);
		}
		if(e.getID()==KeyEvent.KEY_TYPED){
			if(!ownKeyTyping && !ownModifierHeld()) return false;
			ownKeyTyping = false;
			e.consume();
			return true;
		}
		if(!ownModifierKey(e)) return false;
		ownKeyTyping = e.getID()==KeyEvent.KEY_PRESSED;
		e.consume();
		return true;
	}

	static int scriptShortcutFor(int keyCode, int modifiers, boolean macOS) {
		if(keyCode!=KeyEvent.VK_PERIOD) return SCRIPT_SHORTCUT_NONE;
		boolean commandDown = (modifiers&InputEvent.META_DOWN_MASK)!=0 ||
			(!macOS && (modifiers&InputEvent.CTRL_DOWN_MASK)!=0);
		if(!commandDown) return SCRIPT_SHORTCUT_NONE;
		if((modifiers&InputEvent.ALT_DOWN_MASK)!=0) return SCRIPT_SHORTCUT_TRACE;
		return SCRIPT_SHORTCUT_ABORT;
	}

	private boolean handleScriptShortcut(KeyEvent e) {
		boolean macOS = System.getProperty("os.name").toLowerCase().startsWith("mac os x");
		int shortcut = scriptShortcutFor(e.getKeyCode(), e.getModifiersEx(), macOS);
		if(shortcut==SCRIPT_SHORTCUT_NONE) return false;
		if(PCARDFrame.pc==null || PCARDFrame.pc.stack==null) return false;
		if(shortcut==SCRIPT_SHORTCUT_ABORT) {
			if(!PCARDFrame.pc.stack.cantAbort) {
				TTalk.requestUserAbort();
			}
		}
		else {
			TTalk.requestTrace();
			ScriptEditor.setTracemode();
		}
		e.consume();
		return true;
	}

    
    public static void mouseClicked() {
		if(consumedPress!=mousePresses){ mouseClicked = true; userClickPending = true; }
		mouseDown = false;
		
		if(TTalk.talk!=null && !TTalk.talk.isAlive()){
			TTalk.talk = new TTalk();
			TTalk.talk.start();
			
	    	if(!PCARD.pc.pidle.isAlive()){
	    		PCARD.pc.pidle = new Pidle();
	    		PCARD.pc.pidle.start();
	    	}
		}
	}

    /** the mouse: HyperCard J-2.3 / 2.4 answer "down" also for a click made while a handler ran and not taken yet, and
     *  reading it takes that click - the mouseClick is false afterwards, also for a press still held now (its release is
     *  no click): `wait until the mouse is down` and a later `wait until the mouseClick` need two clicks, not one. A
     *  script's own `click at` is no pending click here (HyperPiece 2's pop-ups loop `repeat until the mouse is up` with
     *  `click at the mouseLoc` inside). */
    static boolean takeMouseState() {
		boolean down = mouseDown || (mouseClicked && userClickPending);
		mouseClicked = false;
		userClickPending = false;
		consumedPress = mousePresses;
		return down;
	}

    /** Notes a click in a field for the clickChunk, clickText and clickLine (local = the field component's own pixels). */
    static void noteFieldClick(OField fld, int localX, int localY) {
		clickFieldLocalX = localX;
		clickFieldLocalY = localY;
		clickTextField = fld;
		clickByScript = false;
	}

    /** The last field click was the script's click at (set after noteFieldClick): below a list's text HyperCard J-2.3 counts the
     *  clickLine on for it, while a user's click during a handler stays on the list's last line */
    static volatile boolean clickByScript;

    public static void mouseDowned() {
		if(!mouseDown){
			mousePresses++;
			if(!TTalk.idle) handlerPress = mousePresses;
		}
		mouseDown = true;
	}

    /** The press (mousePresses then) made while a handler ran: only such a press is the mouseClick while still held. */
    static volatile int handlerPress = -1;

    /** A handler starts: clicks made before it are not its mouseClick. */
    public static void forgetClicks() {
		mouseClicked = false;
		handlerPress = -1;
	}

    /** the mouseClick with the button still held from a press made during the handler: HyperCard J-2.3 waits until it is
     *  released and answers true (measured in the 68K VM); the release is no second click and the mouse is "up" afterwards.
     *  はいぱあれもん Vol.3's title loops "if the mouseClick … wait 4 … terazza("move",mouseLoc(),mouse())": answered false
     *  here, the press was taken by the mouse() of the next turn and most clicks did nothing. */
    static boolean takeHeldPress() {
		int press = mousePresses;
		if(!mouseDown || handlerPress!=press || consumedPress==press) return false;
		consumedPress = press;
		long limit = System.currentTimeMillis()+20000; //a release the window never heard of must not hang the script
		while(mouseDown && !TTalk.stop && System.currentTimeMillis()<limit){
			try{ Thread.sleep(10); }catch(InterruptedException e){ Thread.currentThread().interrupt(); break; }
		}
		mouseClicked = false;
		userClickPending = false;
		return true;
	}

    public static void mouseUped() {
		mouseDown = false;
		if(TTalk.cursorHeldByPress && TTalk.idle) TTalk.resetIdleCursor(); //HyperCard is idle again: its browse cursor
	}
	void addMouseListener(PCARD pc){
        pc.addMouseListener(this);
        pc.addMouseMotionListener(this);
	}
	void removeMouseListener(PCARD pc){
        pc.removeMouseListener(this);
        pc.removeMouseMotionListener(this);
	}
	
    //カード上の mouseDown をスクリプトへ送ったか(対になる mouseUp を送るかの判定用)
    private boolean cardMouseDownSent = false;

    public void mouseClicked(MouseEvent e) {
    	if(PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
    	//ボタン/フィールド(GButton等)と同じく、ハンドラ実行中のクリックは mouseDown/mouseUp
    	//メッセージにせず the mouseClick として記録するだけにする(HyperCard: ハンドラ実行中の
    	//クリックは the mouseClick で拾うもので、終了後に mouseUp が届くことはない)。
		//以前はキューに積んでいたため、openStack中の処理を the mouseClick で飛ばした後に、
		//そのクリックの mouseUp が最初のカードへ
    	//届いて softReset が走り、最初のカードが2回表示されていた。
    	//mouseDown を送ったクリックの mouseUp は、その mouseDown ハンドラがまだ実行中でも送る
    	//(HyperCardは mouseDown ハンドラの終了後に mouseUp を送る)。
    	boolean sentMouseUp = cardMouseDownSent;
    	if(cardMouseDownSent){
    		TTalk.CallMessage("mouseUp",PCARD.pc.stack.curCard);
    	}
    	cardMouseDownSent = false;
    	//as for buttons: the click that sends mouseUp is not the mouseClick of its handler (扇風機's card loops
    	//"if mouseClick() = true" and stopped after one turn when the handler had already started)
    	if(!sentMouseUp && !cardClickCounted) mouseClicked();
    	else mouseDown = false;
    	cardClickCounted = false;
    }

    //ハンドラ実行中のカードのクリックを mouseReleased で the mouseClick にしたか(続く mouseClicked で2回数えない)
    private boolean cardClickCounted = false;
    
    static MyTextArea lastFocusArea;
    
    public void mousePressed(MouseEvent e) {
    	if(PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
    	reclaimFocusFromField();
		
    	cardMouseDownSent = TTalk.idle;
    	cardClickCounted = false;
    	if(cardMouseDownSent) TTalk.CallMessage("mouseDown",PCARD.pc.stack.curCard);
    	mouseDowned();
    }
    /** フィールドからフォーカスを取り戻す(カードやシェイプのクリック) */
    static void reclaimFocusFromField() {
    	if(PCARD.pc.getFocusOwner()!=null &&
    			PCARD.pc.getFocusOwner().getClass()==MyTextArea.class)
    	{
    		lastFocusArea = (MyTextArea)PCARD.pc.getFocusOwner();
    		PCARD.pc.requestFocusInWindow();
    		SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					//一度消してやらないと最前面に描画されたまま後始末しない
					lastFocusArea.setVisible(false);
					lastFocusArea.setVisible(true);
				}
			});
    	}
    }
    public void mouseReleased(MouseEvent e) {
    	mouseDown = false;
    	mouseUped();
    	//ハンドラ実行中のクリックは離したときに the mouseClick にする: AWT は押してから少しでも動いたクリックに
    	//mouseClicked を出さないので、トラックパッドの揺れで wait until the mouseClick がクリックを取りこぼしていた
    	if(!cardMouseDownSent && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null){
    		mouseClicked();
    		cardClickCounted = true;
    	}
    }
    public void mouseEntered(MouseEvent e) {
    }
    public void mouseExited(MouseEvent e) {
    }
	public void mouseDragged(MouseEvent e) {
    	mouseH = e.getX();
    	mouseV = e.getY();
	}
	public void mouseMoved(MouseEvent e) {
    	mouseH = e.getX();
    	mouseV = e.getY();
	}
	
	//Key[]について
	//0:押されてないとき
	//3:押された直後
	//2:押された(3の後)
	//1:離された直後
	
	public void keyPressed(KeyEvent e) {
		if(handleScriptShortcut(e)) return;
		//Power Keys(ペイントツール中の1文字コマンド)。実行したらkeyDown等は送らない
		if(PaintTool.handlePowerKey(e)){
			e.consume();
			return;
		}
		//System.out.println("keyPressed:"+e.getKeyCode());
		int k = e.getKeyCode();
		if(k>=32 && k<128){ //数字、記号はそのまま、アルファベットは大文字のcode
			if(k>'a' && k<='z')
				k -= 'a'-'A';
			if(key[k]==0) key[k]=3;
		}
		
		int c = -1;
		switch (e.getKeyCode()) {
			case KeyEvent.VK_UP: c = 0;break;
			case KeyEvent.VK_DOWN: c = 1;break;
			case KeyEvent.VK_LEFT: c = 2;break;
			case KeyEvent.VK_RIGHT: c = 3;break;
			case KeyEvent.VK_ENTER: c = 10;break;
			case KeyEvent.VK_SHIFT: c = 11;break;
			case KeyEvent.VK_ALT: c = 12;break;
			case KeyEvent.VK_CONTROL: c = 13;break;
			case KeyEvent.VK_META: c = 14;break;
			case KeyEvent.VK_CAPS_LOCK: c = 15;break;
			case KeyEvent.VK_WINDOWS: c = 16;break;
			case KeyEvent.VK_BACK_SPACE: c = 20;break;
			case KeyEvent.VK_DELETE: c = 21;break;
			case KeyEvent.VK_ESCAPE: c = 22;break;
			case KeyEvent.VK_NUMPAD0: c = 100;break;
			case KeyEvent.VK_NUMPAD1: c = 101;break;
			case KeyEvent.VK_NUMPAD2: c = 102;break;
			case KeyEvent.VK_NUMPAD3: c = 103;break;
			case KeyEvent.VK_NUMPAD4: c = 104;break;
			case KeyEvent.VK_NUMPAD5: c = 105;break;
			case KeyEvent.VK_NUMPAD6: c = 106;break;
			case KeyEvent.VK_NUMPAD7: c = 107;break;
			case KeyEvent.VK_NUMPAD8: c = 108;break;
			case KeyEvent.VK_NUMPAD9: c = 109;break;
			//cが110-116は左shift,alt,ctrl,meta用
			//cが116-122は右shift,alt,ctrl,meta用
			case 107: c = '+';break;
			case 109: c = '-';break;
		}
		if(c>=11 && c<=14){
			int side = c;
			c = givenAway(c); //スタック個別設定で別のキーにした修飾キーは、元のキーでは押されない
			if(c<0 && side==13 && !GMenu.isMacOSX() && !StackSettings.hasOwnKey(14)){ if(key[14]==0) key[14]=3; } //Ctrlは引き続きCommand
		}
		if(c>=0){
			if(key[c]==0) key[c]=3;
			if(c==13 && !System.getProperty("os.name").toLowerCase().startsWith("mac os x") && !StackSettings.hasOwnKey(14)){
				c=14; //OSX以外ではctrlキーをcmdキーとしても認識
				if(key[c]==0) key[c]=3;
			}
			if(e.getKeyLocation()==KeyEvent.KEY_LOCATION_LEFT && c>=11 && c<= 16){
				c+=100;
				if(key[c]==0) key[c]=3;
			}
			else if(e.getKeyLocation()==KeyEvent.KEY_LOCATION_RIGHT && c>=11 && c<= 16){
				c+=106;
				if(key[c]==0) key[c]=3;
			}
		}
		
		if(AuthTool.tool != null){
			if(AuthTool.tool.getClass() == ButtonTool.class){
				ButtonGUI.gui.keyDown();
			}
			else if(AuthTool.tool.getClass() == FieldTool.class){
				FieldGUI.gui.keyDown();
			}
			else if(AuthTool.tool instanceof ShapeTool tool){tool.keyDown(e);}
		}
		else{
			String keyStr=null;
			if(c==-1 && k>=32&&k<=128){
				keyStr = Character.toString((char)k);
			}
			else if(c==0) keyStr = Character.toString('↑');
			else if(c==1) keyStr = Character.toString('↓');
			else if(c==2) keyStr = Character.toString('←');
			else if(c==3) keyStr = Character.toString('→');
			else if(c==20) keyStr = Character.toString('⌫');
			else if(c==21) keyStr = Character.toString('⌦');
			else if(c==22) keyStr = Character.toString('⎋');
			else if(c>=100&&c<=109){
				keyStr = Character.toString((char)(c-100+'0'));
			}
			//文字キーは実際に入力される文字(大文字/小文字、記号)で通知する
			if(c==-1 && k>=32 && k<=128){
				char typed = e.getKeyChar();
				if(typed!=KeyEvent.CHAR_UNDEFINED && typed>=32 && !e.isControlDown() && !e.isMetaDown() && !ownModifierHeld()) keyStr = Character.toString(typed);
			}
			//挿入ポイントのあるフィールド(HyperCardの keyboard command の最初の受信者)
			OObject keyTarget = PCARD.pc.stack.curCard;
			if(clickField!=null && clickField.fld!=null && clickField.fld.isFocusOwner()) keyTarget = clickField;
			boolean inField = keyTarget instanceof OField;
			if(e.getKeyCode()==KeyEvent.VK_ENTER){
				//Return と Enter(テンキー) は別のコマンド
				boolean enter = e.getKeyLocation()==KeyEvent.KEY_LOCATION_NUMPAD;
				String message = inField ? (enter ? "enterInField" : "returnInField") : (enter ? "enterKey" : "returnKey");
				TTalk.CallMessage(message, "", keyTarget, false, false);
			}
			else if(e.getKeyCode()==KeyEvent.VK_TAB){
				TTalk.CallMessage("tabKey", "", keyTarget, false, false);
			}
			else if(controlDown(e) && keyStr==null && k>=32 && k<128 && !(c>=11 && c<=16)){
				//controlKey <文字番号>
				TTalk.CallMessage("controlKey", Integer.toString(k), PCARD.pc.stack.curCard, false, false);
			}
			else if(keyStr!=null){
				String message = "keyDown";
				//key[14]ではなくこのキーイベントの修飾キーで決める(key[]は離したのが別のウインドウだと残ることがある)
				if(commandDown(e)) message = "commandKeyDown";
				TTalk.CallMessage(message,keyStr,keyTarget,false,false);
			}

		}
	}
	
	public void keyReleased(KeyEvent e) {
		//System.out.println("keyReleased:"+e.getKeyCode());
		int k = e.getKeyCode();
		if(k>=32 && k<128){
			if(k>'a' && k<='z')
				k -= 'a'-'A';
			if(key[k]==2) key[k]=0;
			if(key[k]==3) key[k]=1;
		}
		
		int c = -1;
		switch (e.getKeyCode()) {
		case KeyEvent.VK_UP: c = 0;break;
		case KeyEvent.VK_DOWN: c = 1;break;
		case KeyEvent.VK_LEFT: c = 2;break;
		case KeyEvent.VK_RIGHT: c = 3;break;
		case KeyEvent.VK_ENTER: c = 10;break;
		case KeyEvent.VK_SHIFT: c = 11;break;
		case KeyEvent.VK_ALT: c = 12;break; //opt
		case KeyEvent.VK_CONTROL: c = 13;break;
		case KeyEvent.VK_META: c = 14;break; //cmd
		case KeyEvent.VK_CAPS_LOCK: c = 15;break;
		case KeyEvent.VK_WINDOWS: c = 16;break;
		case KeyEvent.VK_BACK_SPACE: c = 20;break;
		case KeyEvent.VK_DELETE: c = 21;break;
		case KeyEvent.VK_NUMPAD0: c = 100;break;
		case KeyEvent.VK_NUMPAD1: c = 101;break;
		case KeyEvent.VK_NUMPAD2: c = 102;break;
		case KeyEvent.VK_NUMPAD3: c = 103;break;
		case KeyEvent.VK_NUMPAD4: c = 104;break;
		case KeyEvent.VK_NUMPAD5: c = 105;break;
		case KeyEvent.VK_NUMPAD6: c = 106;break;
		case KeyEvent.VK_NUMPAD7: c = 107;break;
		case KeyEvent.VK_NUMPAD8: c = 108;break;
		case KeyEvent.VK_NUMPAD9: c = 109;break;
		//cが110-116は左shift,alt,ctrl,meta用
		//cが116-122は右shift,alt,ctrl,meta用
		case 107: c = '+';break;
		case 109: c = '-';break;
		}
		if(c>=11 && c<=14){
			int side = c;
			c = givenAway(c);
			if(c<0 && side==13 && !GMenu.isMacOSX() && !StackSettings.hasOwnKey(14)){ if(key[14]==2) key[14]=0; if(key[14]==3) key[14]=1; }
		}
		if(c>=0){
			if(key[c]==2) key[c]=0;
			if(key[c]==3) key[c]=1;
			if(c==13 && !System.getProperty("os.name").toLowerCase().startsWith("mac os x") && !StackSettings.hasOwnKey(14)){
				c=14; //OSX以外ではctrlキーをcmdキーとしても認識
				if(key[c]==2) key[c]=0;
				if(key[c]==3) key[c]=1;
			}
			if(e.getKeyLocation()==KeyEvent.KEY_LOCATION_LEFT && c>=11 && c<= 16){
				c+=100;
				if(key[c]==2) key[c]=0;
				if(key[c]==3) key[c]=1;
			}
			else if(e.getKeyLocation()==KeyEvent.KEY_LOCATION_RIGHT && c>=11 && c<= 16){
				c+=106;
				if(key[c]==2) key[c]=0;
				if(key[c]==3) key[c]=1;
			}
		}
	}
	public void keyTyped(KeyEvent e) {
		//文字が入力された場合に来るらしい
		
		/*int k = e.getKeyCode();
		if(k>=32 && k<128){
			if(k>'a' && k<='z')
				k -= 'a'-'A';
			key[k] = 1;
		}*/
	}
	
	public static void keyEventCheck(){
		boolean isOpened = false;
		//if( PCARDFrame.pc.isFocused() ){
			for(int i=0; i<128; i++){
				if(key[i]==1 || key[i]==3){
					if(TTalk.idle && AuthTool.tool==null) {
						//特殊ショートカットキー
						if(i>=0 && i<=3){
							if(i==0) TTalk.CallMessage("arrowKey","up",PCARDFrame.pc.stack.curCard,true,false);
							else if(i==1) TTalk.CallMessage("arrowKey","down",PCARDFrame.pc.stack.curCard,true,false);
							else if(i==2) TTalk.CallMessage("arrowKey","left",PCARDFrame.pc.stack.curCard,true,false);
							else if(i==3) TTalk.CallMessage("arrowKey","right",PCARDFrame.pc.stack.curCard,true,false);
						}
					}
					if(key[12]==2 && key[14]==2 && !isOpened && StackProtection.canOpenScripts()){
						isOpened = true;
						if(i=='C' || i=='1' || i==101){
							ScriptEditor.openScriptEditor(PCARDFrame.pc, PCARDFrame.pc.stack.curCard);
							endPeek();
						}
						if(i=='B' || i=='2' || i==102){
							ScriptEditor.openScriptEditor(PCARDFrame.pc, PCARDFrame.pc.stack.curCard.bg);
							endPeek();
						}
						if(i=='S' || i=='3' || i==103){
							ScriptEditor.openScriptEditor(PCARDFrame.pc, PCARDFrame.pc.stack);
							endPeek();
						}
					}
				}
				if(key[i]==3) key[i]=2;
				if(key[i]==1) key[i]=0;
			}
		//}
		//else{
		//	for(int i=0; i<128; i++){
		//		key[i]=0;
		//	}
		//}

		//optとcmd同時押しでボタンを破線で浮かび上がらせる
		if(TTalk.idle && false==PCARDFrame.pc.stack.cantPeek) outlineStrokeCheck();

		if(AuthTool.tool instanceof ButtonTool &&
				ButtonGUI.gui.target != null) {
			ButtonGUI.drawSelectBorder(ButtonGUI.gui.target);
		}
		else if(AuthTool.tool instanceof FieldTool
				&& FieldGUI.gui.target != null) {
			FieldGUI.drawSelectBorder(FieldGUI.gui.target);
		}
	}
	
	static final private BasicStroke STROKE_DOTTED =
		new BasicStroke(1,
		    BasicStroke.CAP_BUTT,
		    BasicStroke.JOIN_MITER,
		    2.0f,
		    new float[]{2f},
		    0.0f);

	static int stroke_frag = 0;
	
	//ピークで示す部品の種類: 1ボタン 2フィールド 3シェイプ。
	//オーサリングツール選択中は本物のHyperCardと同じく選んでいるツールの部品だけ(ボタンツール→ボタン、
	//フィールドツール→フィールド、シェイプツール→シェイプ)。ブラウズ時は Shift の有無でボタン/フィールド。
	static int peekKind(){
		if(AuthTool.tool instanceof ButtonTool) return 1;
		if(AuthTool.tool instanceof FieldTool) return 2;
		if(AuthTool.tool instanceof ShapeTool) return 3;
		return key[11]>=2 ? 2 : 1;
	}

	public static void outlineStrokeCheck(){
		if(key[12]>=2 && key[14]>=2){
			int want = peekKind();
			if(stroke_frag != want) {
				if(stroke_frag != 0){
					addAllListener();
					PCARD.editMode = 0;
					stroke_frag = 0;
				}
				else {
					Graphics g = PCARDFrame.pc.mainPane.getGraphics();
					if(want==2) fldStroke(g);
					else if(want==3) shapeStroke(g);
					else btnStroke(g);
					stroke_frag = want;
					if(PCARD.editMode==0){
						PCARD.editMode = 1;
						GUI.removeAllListener();
						gui.addOutlineListener();
					}
				}
			}
		} else if(stroke_frag >= 1){
			PCARDFrame.pc.mainPane.repaint();
			stroke_frag = 0;
			PCARD.editMode = 0;
			addAllListener();
		}
	}
	
	static void noListener(){
		//PCARDFrame.pc.removeMouseListener(outlineListen);
		for(int i=0; i<PCARDFrame.pc.stack.curCard.btnList.size(); i++){
			PCARDFrame.pc.stack.curCard.btnList.get(i).removeListener(OButton.btnOutlineListen);
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.fldList.get(i).removeListener(OButton.btnOutlineListen);
		}
		if(PCARDFrame.pc.stack.curCard.bg!=null){
			for(int i=0; i<GUI.bgButtons(PCARDFrame.pc.stack.curCard).size(); i++){
				GUI.bgButtons(PCARDFrame.pc.stack.curCard).get(i).removeListener(OButton.btnOutlineListen);
			}
			for(int i=0; i<GUI.bgFields(PCARDFrame.pc.stack.curCard).size(); i++){
				GUI.bgFields(PCARDFrame.pc.stack.curCard).get(i).removeListener(OButton.btnOutlineListen);
			}
		}
	}
	
	//アウトライン強調モードから通常状態に移行
	static void addAllListener(){
		PCARDFrame.pc.mainPane.repaint();
		if(AuthTool.tool!=null){
			//オーサリングツール中は、ブラウズ用リスナーを戻さずピーク用だけ外す
			removeOutlineListener();
			OField.updateAllTextEditable();
			PCARDFrame.pc.getJMenuBar().setEnabled(true);
			return;
		}
		//PCARDFrame.pc.removeMouseListener(outlineListen);
		PCARDFrame.pc.stack.pcard.addMouseListener(gui);
		PCARDFrame.pc.mainPane.addMouseListener(gui);
		for(int i=0; i<PCARDFrame.pc.stack.curCard.btnList.size(); i++){
			PCARDFrame.pc.stack.curCard.btnList.get(i).removeListener(OButton.btnOutlineListen);
			PCARDFrame.pc.stack.curCard.btnList.get(i).addListener();
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.fldList.get(i).removeListener(OField.fldOutlineListen);
			PCARDFrame.pc.stack.curCard.fldList.get(i).addListener();
		}
		if(PCARDFrame.pc.stack.curCard.bg!=null){
			for(int i=0; i<GUI.bgButtons(PCARDFrame.pc.stack.curCard).size(); i++){
				GUI.bgButtons(PCARDFrame.pc.stack.curCard).get(i).removeListener(OButton.btnOutlineListen);
				GUI.bgButtons(PCARDFrame.pc.stack.curCard).get(i).addListener();
			}
			for(int i=0; i<GUI.bgFields(PCARDFrame.pc.stack.curCard).size(); i++){
				GUI.bgFields(PCARDFrame.pc.stack.curCard).get(i).removeListener(OField.fldOutlineListen);
				GUI.bgFields(PCARDFrame.pc.stack.curCard).get(i).addListener();
			}
		}
		OField.updateAllTextEditable();
		PCARDFrame.pc.getJMenuBar().setEnabled(true);
	}
	
	/** オーサリング(ボタン/フィールド/シェイプ)ツールかペイントツールを選んでいる: 部品にブラウズ用リスナー(mouseUp等)を付けない */
	static boolean editingTool(){
		return AuthTool.tool!=null || (PCARD.pc!=null && PCARD.pc.tool!=null);
	}

	//アウトライン強調モードに移行
	/** The current card's background buttons/fields; none while its background is not loaded yet (a tool chosen in openStack:
	 *  AOL HyperDark modules) */
	static java.util.List<OButton> bgButtons(OCard cd){
		OBackground bg = cd==null ? null : cd.getBg();
		return bg==null ? java.util.Collections.<OButton>emptyList() : bg.btnList;
	}
	static java.util.List<OField> bgFields(OCard cd){
		OBackground bg = cd==null ? null : cd.getBg();
		return bg==null ? java.util.Collections.<OField>emptyList() : bg.fldList;
	}

	static void removeAllListener(){
		//ツール中でも外す。ボタン/フィールドツールへ切り替えるときはAuthTool.toolを設定してからここを呼ぶので、
		//「ツール中は付いていない」として何もしないと、ブラウズ用リスナーが残ってボタンツールでのクリックにmouseUpが送られていた
		PCARDFrame.pc.stack.pcard.removeMouseListener(gui);
		PCARDFrame.pc.mainPane.removeMouseListener(gui);
		for(int i=0; i<PCARDFrame.pc.stack.curCard.btnList.size(); i++){
			PCARDFrame.pc.stack.curCard.btnList.get(i).removeListener();
		}
		for(int i=0; i<GUI.bgButtons(PCARDFrame.pc.stack.curCard).size(); i++){
			GUI.bgButtons(PCARDFrame.pc.stack.curCard).get(i).removeListener();
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.fldList.get(i).removeListener();
		}
		for(int i=0; i<GUI.bgFields(PCARDFrame.pc.stack.curCard).size(); i++){
			GUI.bgFields(PCARDFrame.pc.stack.curCard).get(i).removeListener();
		}
	}
	
	void addOutlineListener(){
		PCARD.pc.removeMouseListener(gui);
		//PCARD.pc.addMouseListener(outlineListen);
		boolean tool = AuthTool.tool!=null;
		if(!tool || stroke_frag==1){
			for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
				PCARD.pc.stack.curCard.btnList.get(i).addListener(OButton.btnOutlineListen);
			}
			for(int i=0; i<GUI.bgButtons(PCARD.pc.stack.curCard).size(); i++){
				GUI.bgButtons(PCARD.pc.stack.curCard).get(i).addListener(OButton.btnOutlineListen);
			}
		}
		if(!tool || stroke_frag==2){
			for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
				PCARD.pc.stack.curCard.fldList.get(i).addListener(OField.fldOutlineListen);
			}
			for(int i=0; i<GUI.bgFields(PCARD.pc.stack.curCard).size(); i++){
				GUI.bgFields(PCARD.pc.stack.curCard).get(i).addListener(OField.fldOutlineListen);
			}
		}
		OField.updateAllTextEditable();
		PCARD.pc.getJMenuBar().setEnabled(false);
	}

	static void removeOutlineListener(){
		OCardBase[] owners = {PCARDFrame.pc.stack.curCard, PCARDFrame.pc.stack.curCard.bg};
		for(OCardBase o : owners){
			if(o==null) continue;
			for(OButton b : o.btnList) b.removeListener(OButton.btnOutlineListen);
			for(OField f : o.fldList) f.removeListener(OField.fldOutlineListen);
		}
	}
	
	public static void btnStroke(Graphics g){
		if(PCARDFrame.pc.stack.curCard==null) return;
	    Graphics2D g2=(Graphics2D)g;
		for(int i=0; i<PCARDFrame.pc.stack.curCard.btnList.size(); i++){
			OObject obj = PCARDFrame.pc.stack.curCard.btnList.get(i);
			dotStroke(g2, obj);
		}
		if(PCARDFrame.pc.stack.curCard.bg!=null){
			for(int i=0; i<GUI.bgButtons(PCARDFrame.pc.stack.curCard).size(); i++){
				OObject obj = GUI.bgButtons(PCARDFrame.pc.stack.curCard).get(i);
				dotStroke(g2, obj);
			}
		}
		//シェイプもボタンと一緒に示す(クリックでそのスクリプトを開く: GShape)。ツール中はボタンだけ
		if(AuthTool.tool==null) shapeStroke(g);
	}

	public static void shapeStroke(Graphics g){
		if(PCARDFrame.pc.stack.curCard==null) return;
		Graphics2D g2=(Graphics2D)g;
		for(OCardBase owner : new OCardBase[]{PCARDFrame.pc.stack.curCard, PCARDFrame.pc.stack.curCard.bg}){
			if(owner==null || owner.shapeList==null) continue;
			for(OShape shape : owner.shapeList) if(shape.isShown()) dotStroke(g2, shape);
		}
	}

	public static void fldStroke(Graphics g){
	    Graphics2D g2=(Graphics2D)g;
		for(int i=0; i<PCARDFrame.pc.stack.curCard.fldList.size(); i++){
			OObject obj = PCARDFrame.pc.stack.curCard.fldList.get(i);
			dotStroke(g2, obj);
		}
		for(int i=0; i<GUI.bgFields(PCARDFrame.pc.stack.curCard).size(); i++){
			OObject obj = GUI.bgFields(PCARDFrame.pc.stack.curCard).get(i);
			dotStroke(g2, obj);
		}
	}

	public static void dotStroke(Graphics2D g2, OObject obj){
		Rectangle2D.Float bounds = scaledOutlineBounds(obj, PCARD.scale());
	    g2.setStroke(new BasicStroke(1.0f));
	    g2.setPaint(Color.WHITE);
	    g2.draw(bounds);
	    g2.setPaint(Color.BLACK);
	    g2.setStroke(STROKE_DOTTED);
	    g2.draw(bounds);
	}

	//obj.left/top/width/heightは論理座標なので、2倍表示モードでは物理座標に
	//変換してから描かないと、実際のボタンの位置・サイズとずれてしまう。
	static Rectangle2D.Float scaledOutlineBounds(OObject obj, double scale){
		int x = (int)Math.round(obj.left*scale), y = (int)Math.round(obj.top*scale);
		return new Rectangle2D.Float(x, y,
			(int)Math.round((obj.left+obj.width)*scale)-x-1, (int)Math.round((obj.top+obj.height)*scale)-y-1);
	}


	//static outlineListen outlineListen = new outlineListen();
}



class GButton implements ActionListener, MouseListener, MouseMotionListener {
	/** The button whose click Swing already reported through actionPerformed. Swing's own listener fires the action
	 *  inside its mouseReleased, before ours runs, so mouseReleased skips that button: one click is one mouseUp
	 *  (sending it from both made one click on an enemy queue an attack for two ready allies). */
	private MyButton actionSent;
	/** Whether that action sent mouseUp (the script was idle) or only noted a click (a handler was running). */
	private boolean actionSentMouseUp;
	/** The button whose press sent mouseDown: its release sends mouseUp even while that mouseDown handler still runs
	 *  (HyperCard sends it after the handler). JokerSystem's transparent JkHide button redraws the colour card in its
	 *  mouseDown, so a quick click on Car Chaser's GameStart!! released before that ended and nothing started. */
	private MyButton mouseDownSent;

	static boolean onCurrentCard(OObject b){
		if(b==null || b.parent==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return false;
		OObject parent = b.parent;
		return parent==PCARD.pc.stack.curCard || parent instanceof OBackground && ((OBackground)parent).viewCard==PCARD.pc.stack.curCard;
	}

	@Override
	public void actionPerformed (ActionEvent e) {
		//String cmd = e.getActionCommand();
		//MyButton btn = (MyButton)e.getSource();
		//OCardBase parent = (OCardBase)btn.btnData.parent;
		if(e.getSource() instanceof MyButton) actionSent = (MyButton)e.getSource();
		if(TTalk.idle==true/*&&parent==PCARD.pc.stack.curCard||parent.objectType.equals("background")&&
				((OBackground)parent).viewCard==PCARD.pc.stack.curCard*/){
			TTalk.CallMessage("mouseUp", ((MyButton)e.getSource()).btnData);
			actionSentMouseUp = true;
		}else if(e.getSource()==mouseDownSent){
			TTalk.callPendingMouseUp(mouseDownSent.btnData);
			actionSentMouseUp = true;
		}else{
			GUI.mouseClicked();
			actionSentMouseUp = false;
		}
	}

	@Override
    public void mouseClicked(MouseEvent e) {
		//mouseUpはactionPerformed、またはactionの出なかったクリックならmouseReleasedで送る
    }

	/** Whether a release lands inside its component. AWT sends MOUSE_CLICKED only when the pointer did not move at all
	 *  between press and release, so a click with a pixel or two of trackpad/mouse jitter used to be dropped
	 *  entirely; HyperCard sends mouseUp whenever the button is released inside the object it was pressed in. */
	static boolean releasedInside(MouseEvent e){
		Component c = e.getComponent();
		return c!=null && e.getX()>=0 && e.getY()>=0 && e.getX()<c.getWidth() && e.getY()<c.getHeight();
	}
	@Override
    public void mousePressed(MouseEvent e) {
		MyButton btn = (MyButton)e.getSource();
		if(btn.btnData==null || btn.btnData.parent==null) return; //a button of a card that was just left (its data already released)
		OCardBase parent = (OCardBase)btn.btnData.parent;
		mouseDownSent = null;
		if(parent==PCARD.pc.stack.curCard||parent.objectType.equals("background")&&
				((OBackground)parent).viewCard==PCARD.pc.stack.curCard){
			if(TTalk.idle==true && ((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
				TTalk.CallMessage("mouseDown", ((MyButton)e.getSource()).btnData);
				mouseDownSent = btn;
	    	}
		}
		GUI.mouseDowned();
    }
	@Override
    public void mouseReleased(MouseEvent e) {
		MyButton source = (MyButton)e.getSource();
		boolean reported = actionSent==source;
		boolean sentMouseUp = reported && actionSentMouseUp;
		actionSent = null;
		actionSentMouseUp = false;
		boolean pressSentMouseDown = mouseDownSent==source;
		mouseDownSent = null;
		//Swingがactionを出さなかったクリック(押してから動いた等)だけをここで送る
		if(!reported && TTalk.idle==true && source.btnData!=null && source.btnData.enabled==true
				&& releasedInside(e)){
			if(e.getClickCount()==2) TTalk.CallMessage("mouseDoubleClick", source.btnData);
			TTalk.CallMessage("mouseUp", source.btnData);
			sentMouseUp = true;
		}
		else if(!reported && pressSentMouseDown && source.btnData!=null && source.btnData.enabled==true && releasedInside(e)){
			TTalk.callPendingMouseUp(source.btnData);
			sentMouseUp = true;
		}
    	GUI.mouseUped();
		//the click that sends this mouseUp is not the mouseClick its handler waits for (HyperCard): らくがきカレンダー's
		//stamps follow the pointer "repeat until mouseClick()" and ended at once when the script thread had already
		//started the handler. A click while a handler runs is the mouseClick as before - once: actionPerformed has
		//already counted a click that fired the action (two counts let one click pass two "wait until the mouseClick")
		if(!sentMouseUp && !reported) GUI.mouseClicked();
		else GUI.mouseDown = false;
    }
	@Override
    public void mouseEntered(MouseEvent e) {
		if(TTalk.idle==true && ((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
			TTalk.CallMessage("mouseEnter", ((MyButton)e.getSource()).btnData);
		}
    }
	@Override
    public void mouseExited(MouseEvent e) {
		if(TTalk.idle==true && ((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
    		TTalk.CallMessage("mouseLeave", ((MyButton)e.getSource()).btnData);
    	}
    }

	@Override
	public void mouseDragged(MouseEvent e) {
		TTalk.CallMessage("mouseStillDown", ((MyButton)e.getSource()).btnData);
	}

	@Override
	public void mouseMoved(MouseEvent e) {
		//mouseEnter/mouseLeaveと同様にidle==trueのときだけ送る。この判定が無いと
		//マウスが乗っている間じゅうmouseMovedのたびに(mouseEnter等と違って)無条件で
		//mouseWithinを積み続けてしまい、スクリプトエンジンが常にidle==false(busy)の
		//ままになる。mousePressedはidle==trueのときしかmouseDownを送らないため、
		//mouseWithinハンドラでwaitするようなスクリプトがあると、ボタンをクリックしても
		//mouseDownが一切発行されなくなっていた。
		if(TTalk.idle==true && ((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
			TTalk.CallMessage("mouseWithin", ((MyButton)e.getSource()).btnData);
		}
	}
}




class GCheckBox implements ActionListener, MouseListener, MouseMotionListener {
	//チェックボックス/ラジオボタンのクリック。Swingは常に選択状態を反転させるが、
	//HyperCardではautoHilite=falseのボタンはクリックしてもハイライトが変わらない
	//(スクリプトがset the hiliteで切り替える)。mouseUpの先頭で
	//「どのラジオボタンが点いていたか」を調べるスクリプトでは、クリックで
	//勝手に点くと自分自身を記憶してしまい、キャンセル時に元へ戻せなかった。
	//ハイライトの反映はmouseUpを積む前に済ませる(スクリプトスレッドとの競合を避ける)。
	static void clicked(OButton btn, AbstractButton source){
		if(TTalk.idle==true){
			if(btn.getAutoHilite()){
				btn.setHilite(source.isSelected());
			}else{
				source.setSelected(btn.storedHilite());
			}
			TTalk.CallMessage("mouseUp", btn);
		}
		else{
			//スクリプト実行中のクリックはmouseClickとして記録するだけで、ボタンは変えない
			source.setSelected(btn.storedHilite());
			GUI.mouseClicked();
		}
	}

	public void actionPerformed (ActionEvent e) {
		clicked(((MyCheck)e.getSource()).btnData, (MyCheck)e.getSource());
	}

    public void mouseClicked(MouseEvent e) {
    	//
    }
    public void mousePressed(MouseEvent e) {
		mouseDownSent = null;
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseDown", ((MyCheck)e.getSource()).btnData);
			mouseDownSent = e.getSource();
			GUI.mouseDowned();
    	}else
    		GUI.mouseDowned();
    }
    public void mouseReleased(MouseEvent e) {
		mouseDownSent = null;
    	GUI.mouseUped();
    }
	/** The check box or radio button whose press sent mouseDown: its drag sends mouseStillDown. */
	Object mouseDownSent;
	/** mouseStillDown while the pointer is dragged with the button down, as for other buttons (UFO's stones are
	 *  radio buttons thrown from "on mouseStillDown"). Not queued while a handler runs, so the queue cannot pile up. */
	static void dragged(GCheckBox owner, MouseEvent e, OButton btn){
		if(owner!=null && owner.mouseDownSent==e.getSource() && TTalk.idle && btn!=null && btn.enabled)
			TTalk.CallMessage("mouseStillDown", btn);
	}
	public void mouseDragged(MouseEvent e) {
		dragged(this, e, ((MyCheck)e.getSource()).btnData);
	}
	public void mouseMoved(MouseEvent e) {
	}
    public void mouseEntered(MouseEvent e) {
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseEnter", ((MyCheck)e.getSource()).btnData);
		}
    }
    public void mouseExited(MouseEvent e) {
		if(TTalk.idle==true){
    		TTalk.CallMessage("mouseLeave", ((MyCheck)e.getSource()).btnData);
    	}
    }
}





class GPopup implements ActionListener, PopupMenuListener {
	static MyPopup hidePopup;
	
	public void actionPerformed (ActionEvent e) {
		//String cmd = e.getActionCommand();
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseUp", ((MyPopup)e.getSource()).btnData);
		}
		else{
			GUI.mouseClicked();
		}

		((MyPopup)e.getSource()).btnData.setSelectedLine(((MyPopup)e.getSource()).getSelectedIndex()+1);
	}
	

	public void popupMenuCanceled(PopupMenuEvent e) {
	}

	public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
	}

	public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
		if(TTalk.idle==true && PCARD.editMode==0){
			TTalk.CallMessage("mouseDown", ((MyPopup)e.getSource()).btnData);
		}else{
			GUI.mouseClicked();
			hidePopup = ((MyPopup)e.getSource());
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					hidePopup.setPopupVisible(false);//スクリプト実行中にメニューが開いたら取り消す
				}
			});
		}
	}
}




class GRadio implements ActionListener, MouseListener, MouseMotionListener {
	private final GCheckBox drag = new GCheckBox();
	public void actionPerformed (ActionEvent e) {
		//autoHiliteの扱いはチェックボックスと同じ(GCheckBox.clicked参照)
		GCheckBox.clicked(((MyRadio)e.getSource()).btnData, (MyRadio)e.getSource());
	}
	
    public void mouseClicked(MouseEvent e) {
    	//
    }
    public void mousePressed(MouseEvent e) {
		drag.mouseDownSent = null;
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseDown", ((MyRadio)e.getSource()).btnData);
			drag.mouseDownSent = e.getSource();
			GUI.mouseDowned();
    	}else
    		GUI.mouseDowned();
    }
    public void mouseReleased(MouseEvent e) {
		drag.mouseDownSent = null;
    	GUI.mouseUped();
    }
	public void mouseDragged(MouseEvent e) {
		GCheckBox.dragged(drag, e, ((MyRadio)e.getSource()).btnData);
	}
	public void mouseMoved(MouseEvent e) {
	}
    public void mouseEntered(MouseEvent e) {
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseEnter", ((MyRadio)e.getSource()).btnData);
		}
    }
    public void mouseExited(MouseEvent e) {
		if(TTalk.idle==true){
    		TTalk.CallMessage("mouseLeave", ((MyRadio)e.getSource()).btnData);
    	}
    }
}



class GField implements MouseListener, MouseMotionListener {
	@Override
    public void mouseClicked(MouseEvent e) {
    	OField fld = ((MyTextArea)e.getSource()).fldData;
    	if(fld!=null && fld.enabled) return; //ロックされたフィールドはmouseReleasedで送る(GButton.releasedInside)
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseUp", fld);
			if(e.getClickCount()==2) TTalk.CallMessage("mouseDoubleClick", fld);
		}
		//ハンドラ実行中のクリックは mouseReleased が the mouseClick にする。ここでも数えると1回のクリックが
		//2回になり、wait until the mouseClick を2つ続けて抜けていた
    }
	@Override
    public void mousePressed(MouseEvent e) {
		OField fld = ((MyTextArea)e.getSource()).fldData;
    	//clickH/clickV(カード座標)は GUI.noteClickLoc が押した位置から設定済み。スクロールフィールドの
    	//e.getY() はスクロール済みのview座標なので、ここで fld.top を足すとスクロール量だけずれていた。
    	//clickChunk/clickTextの文字位置特定は、フィールドが実際に描画した
    	//物理ピクセル位置(hitBoxes)と直接比較するため、scale換算していない
    	//生のクリック座標も別に控えておく。
    	//ロックしていないフィールドでも、ハンドラの実行中(wait until the mouseClick 等)のクリックでも記録する
    	//(HyperCard J-2.3/2.4。勇者の石の［Ｘ］［○］選択は実行中のクリックの clickChunk を見る)
    	GUI.noteFieldClick(fld, e.getX(), e.getY());
		if(TTalk.idle==true){
	    	//ロックしたフィールドのクリックは選択も挿入ポイントも作らない(HyperCard: the selectedChunk/selectedField は空)。
	    	//リストフィールドは下で選択行を決める
	    	GUI.clickField = fld.enabled && !fld.autoSelect ? null : fld;
	    	if(fld.enabled ){
	    		if(fld.textHeight<fld.textSize){
	    			fld.textHeight=fld.textSize;
	    		}
				//スクロールフィールドではMyTextAreaがJViewportのviewなので、e.getY()は
				//既にスクロール済みのview座標(=テキスト全体の先頭起点)。ここへ更に
				//fld.scrollを足すとスクロール量が二重に数えられ、スクロール後の
				//clickLine/selectedLineが下の行を指してしまう。行の先頭は上余白
				//(通常1px、wideMarginsなら8px)の分だけ下がる
				int logicalY = PCARD.toLogical(e.getY());
				int topMargin = OField.textTopMargin(1, fld.wideMargins);
				int line = Math.max(1, (logicalY - topMargin)/fld.lineHeight() + 1);
				//テキストより下の空白部分は、HyperCard同様(クリック位置がテキスト末尾に
				//対応する)最後の行として扱う。以前はどの行にも当たらず、clickLineに
				//前回の値が残っていた
				String text = fld.getText();
				int lineCount = 1;
				for(int i=0; i<text.length(); i++) if(text.charAt(i)=='\n') lineCount++;
				if(line>lineCount) line = lineCount;
				//クリックした行の文字範囲(行末の改行は含めない)
				int lineStart = 0, lineEnd = -1, cnt = 1;
				for(int i=0; i<=text.length(); i++){
					if(i==text.length() || text.charAt(i)=='\n'){
						if(cnt==line){ lineEnd = i; break; }
						cnt++;
						lineStart = i+1;
					}
				}
				if(lineEnd>=0){
					if(fld.autoSelect && fld.dontWrap){
						//Swingの選択範囲もクリックした行そのものにしておく。以前は次の行の
						//末尾までを選択していたため、キャレットが見えている範囲の外へ出て
						//JTextAreaがビューをスクロールさせ、最下行をクリックすると
						//リストが勝手に1行ずれた
						fld.selectedLine=line;
						fld.selectedStart=line;
						fld.selectedEnd=line;
						//キャレット(dot)は行頭側に置き、長い行でも横にスクロールさせない
						final int selFrom = lineStart, selTo = lineEnd;
						fld.fld.keepScrollWhile(() -> {
							fld.fld.setCaretPosition(selTo);
							fld.fld.moveCaretPosition(selFrom);
						});
						PCARD.repaintLogicalRect(fld.left, fld.top, fld.width, fld.height);
					}
				}
			}
			TTalk.CallMessage("mouseDown", fld);
			if(fld.enabled && fld.autoSelect && fld.dontWrap) TTalk.runOnScriptThread(() -> reshowListField(fld));
			GUI.mouseDowned();
		}
		else
			GUI.mouseDowned();
    }
	/** A list field selects the clicked line after its mouseDown handler, and that shows the field again: a mouseDown
	 *  with only "hide me" leaves it visible (HyperCard J-2.3 in the 68K VM: the visible true, the line selected;
	 *  自動選択フィールドの怪). Not when the handler went to another card or made it no list. */
	private static void reshowListField(OField fld){
		if(fld.getVisible() || !fld.autoSelect || !GButton.onCurrentCard(fld)) return;
		fld.setVisible(true);
	}
	@Override
    public void mouseReleased(MouseEvent e) {
    	OField fld = ((MyTextArea)e.getSource()).fldData;
    	//ロックされたフィールド(lockText=enabled)は、押してから少し動いたクリックでもmouseUpにする
    	if(fld!=null && fld.enabled){
    		if(TTalk.idle==true){
    			if(GButton.releasedInside(e)){
    				TTalk.CallMessage("mouseUp", fld);
    				if(e.getClickCount()==2) TTalk.CallMessage("mouseDoubleClick", fld);
    			}
    		}
    	}
    	GUI.mouseUped();
		GUI.mouseClicked();
    }
	@Override
    public void mouseEntered(MouseEvent e) {
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseEnter", ((MyTextArea)e.getSource()).fldData);
    	}
    }
	@Override
    public void mouseExited(MouseEvent e) {
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseLeave", ((MyTextArea)e.getSource()).fldData);
		}
    }

	@Override
	public void mouseDragged(MouseEvent e) {
		TTalk.CallMessage("mouseStillDown", ((MyTextArea)e.getSource()).fldData);
	}

	@Override
	public void mouseMoved(MouseEvent e) {
		//ボタン側(GUI_btn.mouseMoved)と同じ理由で、mouseEnter/mouseLeaveと揃えて
		//idle==trueのときだけ送る(詳細はGUI_btn.mouseMovedのコメント参照)。
		if(TTalk.idle==true){
			TTalk.CallMessage("mouseWithin", ((MyTextArea)e.getSource()).fldData);
		}
	}
}

/**
 * The browse tool's mouse messages for a shape (OShape.ShapeView). A shape is a part like a button: it takes the clicks on what it
 * paints (OShape.hits) and gets mouseDown, mouseStillDown, mouseUp, mouseDoubleClick, mouseEnter and mouseLeave, which go on to the
 * card, the background and the stack when its script does not handle them; mouseWithin comes from the idle check
 * (OStack.mouseWithinCheck). As on the card, a click while a handler runs is only recorded for the mouseClick, and the mouseUp of a
 * mouseDown that was sent follows even while that handler still runs; releasing outside the shape sends no mouseUp. While
 * Command-Option shows the button outlines, clicking a shape opens its script.
 */
final class GShape implements MouseListener, MouseMotionListener {
	static final GShape gui = new GShape();
	private boolean mouseDownSent;
	private GShape(){}

	/** Whether shapes take the mouse: the browse tool, also while Command-Option shows the button outlines (not the field ones). */
	static boolean receivesMouse(){
		return PCARD.pc!=null && PCARD.pc.tool==null && AuthTool.tool==null && (PCARD.editMode==0 || GUI.stroke_frag==1);
	}
	private static OShape.ShapeView view(MouseEvent e){ return (OShape.ShapeView)e.getSource(); }

	@Override
	public void mousePressed(MouseEvent e) {
		mouseDownSent = false;
		GUI.mouseDowned();
		if(PCARD.editMode!=0 || PCARD.pc.stack==null) return;
		GUI.reclaimFocusFromField();
		OShape.ShapeView v = view(e);
		GUI.clickH = PCARD.toLogical(v.getX()+e.getX());
		GUI.clickV = PCARD.toLogical(v.getY()+e.getY());
		mouseDownSent = TTalk.idle;
		if(mouseDownSent) TTalk.CallMessage("mouseDown", v.shape);
	}
	@Override
	public void mouseReleased(MouseEvent e) {
		GUI.mouseUped();
		OShape.ShapeView v = view(e);
		boolean inside = v.contains(e.getX(), e.getY());
		boolean sent = mouseDownSent;
		mouseDownSent = false;
		if(PCARD.editMode!=0){
			//ピーク: Command-Option を押したままのクリックだけがスクリプトを開く。
			//key[]に Command-Option が残っていただけの普通のクリックならピークを終える
			if(!GUI.isPeekClick(e)) GUI.endPeek();
			else if(inside && PCARD.pc.stack!=null){
				ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, v.shape);
				GUI.endPeek();
			}
			return;
		}
		if(!inside) return;
		if(sent){
			TTalk.CallMessage("mouseUp", v.shape);
			if(e.getClickCount()==2) TTalk.CallMessage("mouseDoubleClick", v.shape);
		}
		GUI.mouseClicked();
	}
	@Override
	public void mouseClicked(MouseEvent e) {
	}
	@Override
	public void mouseEntered(MouseEvent e) {
		if(TTalk.idle && PCARD.editMode==0) TTalk.CallMessage("mouseEnter", view(e).shape);
	}
	@Override
	public void mouseExited(MouseEvent e) {
		if(TTalk.idle && PCARD.editMode==0) TTalk.CallMessage("mouseLeave", view(e).shape);
	}
	@Override
	public void mouseDragged(MouseEvent e) {
		//ボタンと同じくドラッグ中に送る。ハンドラの実行中は積まない(キューが溢れないように)
		if(mouseDownSent && TTalk.idle) TTalk.CallMessage("mouseStillDown", view(e).shape);
	}
	@Override
	public void mouseMoved(MouseEvent e) {
	}
}

class GUI_fldDocument implements DocumentListener {
	OField fld;
	
	public GUI_fldDocument(OField fld){
		this.fld = fld;
	}
	
	@Override
	public void changedUpdate(DocumentEvent e) {
		String text = fld.getMyTextArea().getText();
		fld.preserveStylesForTextChange(text);
		fld.setTextByInputarea(text);
	}
	@Override
	public void insertUpdate(DocumentEvent e) {
		changedUpdate(e);
		
	}
	@Override
	public void removeUpdate(DocumentEvent e) {
		changedUpdate(e);
		
	}
}


class paintGUI implements MouseListener, MouseMotionListener/*, WindowListener*/
{
	static paintGUI gui = new paintGUI();
	static int clickH;
	static int clickV;
	static boolean right = false;
	MyPaintDropListener droplistener = new MyPaintDropListener();
	//addListenerToParts()で作り、removeListenerFromParts()でnullに戻す
	DropTarget drop = null;

	public void addListenerToParts()
	{
		for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
			PCARD.pc.stack.curCard.btnList.get(i).addListener(this);
			PCARD.pc.stack.curCard.btnList.get(i).addMotionListener(this);
		}
		for(int i=0; i<GUI.bgButtons(PCARD.pc.stack.curCard).size(); i++){
			GUI.bgButtons(PCARD.pc.stack.curCard).get(i).addListener(this);
			GUI.bgButtons(PCARD.pc.stack.curCard).get(i).addMotionListener(this);
		}
		for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
			PCARD.pc.stack.curCard.fldList.get(i).addListener(this);
			PCARD.pc.stack.curCard.fldList.get(i).addMotionListener(this);
		}
		for(int i=0; i<GUI.bgFields(PCARD.pc.stack.curCard).size(); i++){
			GUI.bgFields(PCARD.pc.stack.curCard).get(i).addListener(this);
			GUI.bgFields(PCARD.pc.stack.curCard).get(i).addMotionListener(this);
		}
		drop = new DropTarget(PCARD.pc.mainPane, droplistener);
	}
	public void removeListenerFromParts()
	{
		if(PCARD.pc.stack.curCard==null) return; //no card open (the stack closed): no parts to take them off
		for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
			PCARD.pc.stack.curCard.btnList.get(i).removeListener(this);
			PCARD.pc.stack.curCard.btnList.get(i).removeMotionListener(this);
		}
		for(int i=0; i<GUI.bgButtons(PCARD.pc.stack.curCard).size(); i++){
			GUI.bgButtons(PCARD.pc.stack.curCard).get(i).removeListener(this);
			GUI.bgButtons(PCARD.pc.stack.curCard).get(i).removeMotionListener(this);
		}
		for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
			PCARD.pc.stack.curCard.fldList.get(i).removeListener(this);
			PCARD.pc.stack.curCard.fldList.get(i).removeMotionListener(this);
		}
		for(int i=0; i<GUI.bgFields(PCARD.pc.stack.curCard).size(); i++){
			GUI.bgFields(PCARD.pc.stack.curCard).get(i).removeListener(this);
			GUI.bgFields(PCARD.pc.stack.curCard).get(i).removeMotionListener(this);
		}
		//ペイントを抜けるときにnullへ戻しているので、次にペイントへ入るときの
		//ChangeTool()から呼ばれると(登録前に外す)dropはnullになっている。
		//無条件にremoveDropTargetListener()するとNullPointerExceptionで
		//ChangeTool()が途中で止まり、GUI.removeAllListener()で外した通常の
		//リスナーもpaintGUIも付かないまま、カード上のマウス操作が全滅する。
		if(drop!=null){
			drop.removeDropTargetListener(paintGUI.gui.droplistener);
			drop = null;
		}
	}
	
    public void mouseClicked(MouseEvent e) {
    	/*clickH = e.getX()-PPaint.ppaint.toolbar.tb.getWidth();
    	clickV = e.getY()-PPaint.ppaint.getInsets().top;
    	PaintTool.mouseUp(clickH, clickV);
    	PaintTool.lastTime = 0;*/
    }
    //イベント元コンポーネント上の座標を、ペイントバッファの論理座標(mainPane
    //ローカル座標を2倍表示モードの倍率で割ったもの)へ変換する。
    //以前はイベント元のbounds.x/yを一段だけ足していたため、スクロールフィールド内の
    //テキスト領域(JViewport→JScrollPane→mainPaneと入れ子)から描いたときや、
    //カード自体がスクロールされているときに別の位置へ描画されていた。
    static Point toImagePoint(Component source, Point p, Component mainPane, int scalePercent){
    	Point q = SwingUtilities.convertPoint(source, p, mainPane);
    	if(scalePercent==100) return q;
    	return new Point(DisplayZoom.unscale(q.x, scalePercent), DisplayZoom.unscale(q.y, scalePercent));
    }
    private static Point imagePoint(MouseEvent e){
		PaintTool.owner = PCARD.pc;
    	return toImagePoint((Component)e.getSource(), e.getPoint(), PCARD.pc.mainPane, PCARD.scalePercent());
    }
    /** The press came while a handler ran. HyperCard's paint tools do not follow the user's mouse then: the click is only
     *  the mouseClick (年表 draws its graph with drag from … to … at dragSpeed 50; a click meanwhile drew a line to the
     *  pointer). Its drag and release are not painted either, also when the handler ends before the release. */
    private boolean busyPress = false;
    public void mousePressed(MouseEvent e) {
		busyPress = !TTalk.idle;
		if(busyPress) return;
		right = (javax.swing.SwingUtilities.isRightMouseButton(e));
		GUI.syncModifiers(e);
    	Point p = imagePoint(e);
    	PaintTool.mouseDown(p.x, p.y);
    }
    public void mouseReleased(MouseEvent e) {
		if(busyPress){ busyPress = false; GUI.mouseClicked(); return; }
    	Point p = imagePoint(e);
    	PaintTool.mouseUp(p.x, p.y);
    }
    public void mouseEntered(MouseEvent e) {
    }
    public void mouseExited(MouseEvent e) {
    }
	public void mouseDragged(MouseEvent e) {
		if(busyPress) return;
    	Point p = imagePoint(e);
    	PaintTool.mouseStillDown(p.x, p.y);
	}
	public void mouseMoved(MouseEvent e) {
		if(!TTalk.idle) return; //the script's own drag is the tool's mouse now
    	Point p = imagePoint(e);
    	PaintTool.mouseWithin(p.x, p.y);
	}
	
	/*public void windowActivated(WindowEvent arg0) {
		//なぜフォーカスがツールバーに移るとmenuがdisabledになったままになる？？
		JMenuBar mb = PCARD.pc.getJMenuBar();
		int count = mb.getMenuCount();
		for(int i=0; i<count; i++){
			JMenu m = mb.getMenu(i);
			m.setEnabled(true);
		}
		
		PaintTool.owner = PCARD.pc;
	}
	public void windowClosed(WindowEvent arg0) {
	}
	public void windowClosing(WindowEvent arg0) {
	}
	public void windowDeactivated(WindowEvent arg0) {
	}
	public void windowDeiconified(WindowEvent arg0) {
	}
	public void windowIconified(WindowEvent arg0) {
	}
	public void windowOpened(WindowEvent arg0) {
	}*/
	
}


class MyPaintDropListener extends DropTargetAdapter {
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
					pastePicture(bi);
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	//ペイントツール中にドロップされた画像は、位置を自由に決められるよう
	//浮動選択として置く。確定するとカードピクチャ(バックグラウンド編集中は
	//バックグラウンドピクチャ)へ反映される。
	//「ペースト」と同じ処理(PaintTool.pasteAsSelection)で浮動選択にする。
	static void pastePicture(BufferedImage bi) {
		if(bi==null || PCARD.pc.tool==null) return;
		//アイコンエディタが最後に使われていても、カードへのドロップはカードへ置く
		PaintTool.owner = PCARD.pc;
		PaintTool.pasteAsSelection(bi);
	}
}

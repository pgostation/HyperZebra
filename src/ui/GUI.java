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
			if(inCardWindow(e) && fieldKey(e)) return true;
			//フォーカス移動(Shift-Tab、Ctrl-Tab)に取られる前に
			if(e.getID()==KeyEvent.KEY_PRESSED && inCardWindow(e) && tabShortcut(e)){ e.consume(); return true; }
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
			if(id==MouseEvent.MOUSE_PRESSED){
				mouseDown = true; mousePresses++; if(!TTalk.idle) handlerPress = mousePresses; noteClickLoc((MouseEvent)e);
				Component on = ((MouseEvent)e).getComponent();
				if(PCARD.pc!=null && (on==PCARD.pc || SwingUtilities.getWindowAncestor(on)==PCARD.pc)) dropSelectedRange();
			}
			else if(id==MouseEvent.MOUSE_RELEASED){ mouseDown = false; releaseOfRebuiltPart((MouseEvent)e); }
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

	/** The key press just used up by {@link #fieldKey}: its character is not typed */
	private static boolean fieldCommandTyping;
	/**
	 * Keys in a field being typed in, which has the focus so that the card window's own listener hears nothing: Tab, Return and
	 * Enter are the commands tabKey, returnInField and enterInField, and a character is keyDown when a handler is there to get
	 * it. They go through the message path from the field, HyperCard's own action comes at its end (TTalk.keyboardCommand).
	 * The text component put a tab or a return into the text and no script heard of them (HyperPiece 2 文字を抜き出す加える,
	 * リライタブルロックフィールド). Returns whether the event was used up.
	 */
	private static boolean fieldKey(KeyEvent e){
		if(e.getID()==KeyEvent.KEY_TYPED && fieldCommandTyping){ fieldCommandTyping = false; e.consume(); return true; }
		if(e.getID()!=KeyEvent.KEY_PRESSED && e.getID()!=KeyEvent.KEY_TYPED) return false;
		if((e.getModifiersEx()&(InputEvent.META_DOWN_MASK|InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK))!=0) return false;
		PCARD pc = PCARD.pc;
		if(pc==null || pc.tool!=null || AuthTool.tool!=null) return false;
		OField fld = e.getComponent() instanceof MyTextArea ? ((MyTextArea)e.getComponent()).fldData : null;
		if(fld==null || fld.enabled){
			//the card window, a locked field or a button that kept the focus after a click: Tab and Return are the card's. Swing
			//takes Tab to go round the parts before any listener hears it (カレンダー's tabKey)
			if(e.getComponent() instanceof javax.swing.text.JTextComponent && fld==null) return false;
			if(e.getID()!=KeyEvent.KEY_PRESSED || e.isShiftDown() || (e.getKeyCode()!=KeyEvent.VK_TAB && e.getKeyCode()!=KeyEvent.VK_ENTER)) return false;
			if(e.getComponent()==pc && e.getKeyCode()==KeyEvent.VK_ENTER) return false; //its own listener has it
			fieldCommandTyping = true;
			gui.keyPressed(e);
			e.consume();
			return true;
		}
		if(fld.tableStyle || fld.outlineStyle) return false;
		if(e.getID()==KeyEvent.KEY_PRESSED && e.getKeyCode()==KeyEvent.VK_ESCAPE){
			//Esc in the open field is no text: keyDown, then go back as on the card (HC J-2.3)
			GUI.clickField = fld;
			TTalk.callKeyDown(Character.toString((char)27), "back", fld);
			e.consume();
			return true;
		}
		String command = null;
		if(e.getID()==KeyEvent.KEY_TYPED){
			char c = e.getKeyChar();
			if(c>=32 && c!=127 && TTalk.handlerInPath(fld, "keydown")) command = "keyDown "+TTalk.quoteLiteral(String.valueOf(c));
			if(command==null) return false;
		}else{
			if(e.isShiftDown()) return false;
			if(e.getKeyCode()==KeyEvent.VK_TAB) command = "tabKey";
			else if(e.getKeyCode()==KeyEvent.VK_ENTER) command = e.getKeyLocation()==KeyEvent.KEY_LOCATION_NUMPAD ? "enterInField" : "returnInField";
			else return false;
			fieldCommandTyping = true;
		}
		GUI.clickField = fld;
		TTalk.fieldKeyCommand(command, fld);
		e.consume();
		return true;
	}

	/** Command-Tab presses in a row: browse, button, field tool (HyperCard's Command-Tab, Command-Tab-Tab, Command-Tab-Tab-Tab) */
	private static int commandTabs;
	private static long lastCommandTab;
	/**
	 * HyperCard's Tab shortcuts in the card window: Option-Tab shows or hides the Tools palette, Shift-Tab with a paint tool
	 * the Patterns, Command-Tab chooses the browse tool, pressed again while Command is held the button tool, then the field
	 * tool (macOS keeps Command-Tab for switching programs; Ctrl-Tab is Command-Tab on Windows/Linux). Returns whether it was one.
	 */
	static boolean tabShortcut(KeyEvent e){
		if(e.getKeyCode()!=KeyEvent.VK_TAB) return false;
		PCARD pc = PCARD.pc;
		if(pc==null || pc.stack==null || pc.stack.curCard==null || pc.toolbar==null || pc.toolbar.tb==null) return false;
		boolean cmd = commandDown(e), opt = StackSettings.hasOwnKey(12) ? key[12]>=2 : e.isAltDown();
		if(cmd && !opt){
			long now = System.currentTimeMillis();
			commandTabs = now-lastCommandTab<1500 ? Math.min(commandTabs+1, 3) : 1;
			lastCommandTab = now;
			String tool = commandTabs==1 ? "browse" : commandTabs==2 ? "button" : "field";
			if(StackProtection.effectiveUserLevel() < StackProtection.levelForTool(tool)) return true;
			try{ TTalk.doScriptforMenu("choose "+tool+" tool"); }catch(xTalkException ex){}
			return true;
		}
		if(cmd || controlDown(e)) return false;
		if(opt && !e.isShiftDown()){
			if(StackProtection.effectiveUserLevel() < 3) return true;
			try{ TTalk.doScriptforMenu(pc.toolbar.tb.isVisible() ? "hide tool window" : "show tool window"); }catch(xTalkException ex){}
			return true;
		}
		if(e.isShiftDown() && !opt && pc.tool!=null && AuthTool.tool==null && pc.pat!=null){
			if(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent) return false;
			PatButton pat = pc.pat;
			if(pat.popup.isVisible()) pat.popup.setVisible(false);
			else{
				if(!pc.toolbar.tb.isVisible()){ try{ TTalk.doScriptforMenu("show tool window"); }catch(xTalkException ex){} }
				if(pat.isShowing()) pat.popup.show(pat, 0, pat.getHeight());
			}
			return true;
		}
		return false;
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
				GDialog.closeForAbort(SwingUtilities.getRoot(e.getComponent()));
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
		//the clickText as it is now: a handler that goes to another card first still has it (HC J-2.3; 裏技通信's terms do
		//go cd info / show cd fld the clickText). The field is then gone or shows another card's text
		clickCard = PCARD.pc!=null && PCARD.pc.stack!=null ? PCARD.pc.stack.curCard : null;
		clickTextAtClick = null;
		if(fld!=null && fld.fld!=null && !fld.outlineStyle && !fld.tableStyle){
			try{
				int[] r = TUtil.clickedTextRange(fld);
				clickTextAtClick = r!=null && r.length==2 ? fld.fld.getText().substring(r[0], r[1]) : "";
			}catch(Exception e){ /* read when asked, as before */ }
		}
	}
    /** The card shown at the last field click, and the clickText then (null: not known) */
    static volatile OCard clickCard;
    static volatile String clickTextAtClick;
    /** Whether the card shown now is not the one of the last field click (HC J-2.3: the clickChunk is then empty) */
    static boolean clickOnOtherCard(){
		OCard now = PCARD.pc!=null && PCARD.pc.stack!=null ? PCARD.pc.stack.curCard : null;
		return clickCard!=null && (now==null || now.id!=clickCard.id || now.stack!=clickCard.stack); //by id: a card comes back as a new object
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

    /** mouseEnter and mouseLeave that fell into a handler: HyperCard compares the part under the pointer when it is idle again, so
     *  the pointer going straight from one button to the next sends both. HyperZebra hears the two in one event-thread turn and the
     *  mouseLeave just queued made the mouseEnter fall away (ボタンのfield活用: a keyboard of adjacent buttons named on entering). */
    private static OObject deferredLeave, deferredEnter;
    static void pointerEntered(OObject part){
    	if(part==null) return;
    	synchronized(GUI.class){
    		if(TTalk.idle){ TTalk.CallMessage("mouseEnter", part); return; }
    		if(part==deferredLeave) deferredLeave = null; else deferredEnter = part;
    	}
    }
    static void pointerLeft(OObject part){
    	if(part==null) return;
    	synchronized(GUI.class){
    		if(TTalk.idle){ TTalk.CallMessage("mouseLeave", part); return; }
    		if(part==deferredEnter) deferredEnter = null; else if(deferredLeave==null) deferredLeave = part;
    	}
    }
    /** From the idle thread: what the handler that just ended kept back. */
    static void flushPointerMessages(){
    	OObject leave, enter;
    	synchronized(GUI.class){
    		if(deferredLeave==null && deferredEnter==null) return;
    		leave = deferredLeave; enter = deferredEnter;
    		deferredLeave = null; deferredEnter = null;
    	}
    	if(leave!=null && GButton.onCurrentCard(leave)) TTalk.CallMessage("mouseLeave", leave);
    	if(enter!=null && GButton.onCurrentCard(enter)) TTalk.CallMessage("mouseEnter", enter);
    }

    /** The part whose press sent mouseDown and its component: a handler can rebuild the card under the press (set the style of a
     *  field does, フィールド属性変更), and the release then reaches no listener: HyperCard still sends the part its mouseUp. */
    private static volatile OObject pressedPart;
    private static volatile Component pressedView;
    static void notePressedPart(OObject part, Component view){ pressedPart = part; pressedView = view; }
    private static void releaseOfRebuiltPart(MouseEvent e){
    	OObject part = pressedPart;
    	Component view = pressedView;
    	pressedPart = null; pressedView = null;
    	if(part==null || view==null || view.getParent()!=null) return;
    	PCARD pc = PCARD.pc;
    	if(pc==null || pc.stack==null || pc.stack.curCard==null || pc.mainPane==null) return;
    	OCardBase base = part.parent instanceof OBackground ? pc.stack.curCard.bg : pc.stack.curCard;
    	if(base==null) return;
    	OObject now = null;
    	for(OButton b : base.btnList) if(part instanceof OButton && b.id==part.id) now = b;
    	for(OField f : base.fldList) if(part instanceof OField && f.id==part.id) now = f;
    	if(now==null) return;
    	Point at = new Point(e.getXOnScreen(), e.getYOnScreen());
    	SwingUtilities.convertPointFromScreen(at, pc.mainPane);
    	int x = PCARD.toLogical(at.x), y = PCARD.toLogical(at.y);
    	if(now instanceof OButton && ((OButton)now).getAutoHilite()){
    		//autoHilite as the release would have done it: a plain button goes dark again wherever the release was, a check box
    		//or radio button changes when it was inside (カレンダー: Time's mouseDown sets a field's style)
    		OButton b = (OButton)now;
    		boolean inside = x>=now.left && y>=now.top && x<now.left+now.width && y<now.top+now.height;
    		if(b.style==9 || b.style==10){ if(inside) b.setHilite(b.style==10 || !b.storedHilite()); }
    		else b.setHilite(false);
    	}
    	if(x<now.left || y<now.top || x>=now.left+now.width || y>=now.top+now.height) return;
    	TTalk.callPendingMouseUp(now);
    }

    /** A selected range is only a hilite on the screen: HC J-2.3 ends it (the selectedText and selectedChunk are empty) when any
     *  field's text or text style changes, at unlock screen, at a click and on leaving the card, in a locked or unlocked field
     *  alike. An insertion point stays, and so does a list field's line. */
    static void dropSelectedRange(){
    	OField f = clickField;
    	if(f==null || f.autoSelect || f.fld==null) return;
    	int start = f.fld.getSelectionStart(), end = f.fld.getSelectionEnd();
    	if(end<=start && !f.enabled) return; //an insertion point in a locked field goes too (HC J-2.3)
    	clickField = null;
    	f.fld.setSelectionStart(end);
    	f.fld.setSelectionEnd(end);
    }

    /** The press (mousePresses then) whose mouseUp does not come: exit to HyperCard in its mouseDown handler. */
    private static volatile int cancelledPress = -1;
    public static void cancelMouseUp() { cancelledPress = mousePresses; }
    public static boolean mouseUpCancelled() { return cancelledPress==mousePresses; }

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

	/** The card, button, shape or locked field whose press sent mouseDown, held: it gets mouseStillDown over and over until the
	 *  release, the pointer moving or not, as in HyperCard (an unlocked field is being edited and gets none; HyperCard J-2.3 in
	 *  the 68K VM sends it to the card too). Swing reports only drags, so 家計簿's scroll arrow ("on mouseStillDown … set scroll
	 *  … wait 4") did nothing when held still. One at a time: the next waits until the handler has ended and nothing else is
	 *  queued. A button stops when disabled, a field when unlocked. */
	private static javax.swing.Timer stillDownTimer;
	private static OObject stillDownTarget;
	public static void startStillDown(OObject obj){
		stillDownTarget = obj;
		if(stillDownTimer==null){
			stillDownTimer = new javax.swing.Timer(16, e -> {
				OObject b = stillDownTarget;
				if(!mouseDown || b==null || !b.enabled || !(GButton.onCurrentCard(b) || PCARD.pc.stack!=null && b==PCARD.pc.stack.curCard)){
					stillDownTarget = null;
					stillDownTimer.stop();
					return;
				}
				if(!TTalk.idle) return;
				synchronized(TTalk.messageList){ if(!TTalk.messageList.isEmpty()) return; }
				TTalk.CallMessage("mouseStillDown", b);
			});
		}
		stillDownTimer.restart();
	}

	/** The object the press before went to ({@link #doublePress}). */
	private static Object lastPressTarget;
	/** Whether a press that the script gets messages for is the second click of a double-click. HyperCard J-2.3 (measured in
	 *  the 68K VM) sends that click only mouseDoubleClick, on the card, a button, a check box or radio button and a locked field
	 *  alike: mouseDown, mouseStillDown…, mouseUp, mouseDoubleClick - no mouseDown, mouseStillDown or mouseUp of its own. A third
	 *  click is a click again; a click on another object is none. AWT counts the clicks (2, 4…); the object must be the one the
	 *  press before went to. HyperZebra sent such a click mouseDown and mouseUp, and a button's mouseDoubleClick not at all. */
	public static boolean doublePress(MouseEvent e, Object target){
		boolean second = target!=null && target==lastPressTarget && e.getClickCount()>1 && e.getClickCount()%2==0;
		lastPressTarget = target;
		return second;
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
    	//mouseReleased has already sent the mouseUp (or knew the release was outside the card)
    	if(cardReleaseHandled){ cardReleaseHandled = false; cardClickCounted = false; return; }
    	boolean sentMouseUp = cardMouseDownSent;
    	if(cardMouseDownSent){
    		TTalk.CallMessage(cardDoublePress ? "mouseDoubleClick" : "mouseUp",PCARD.pc.stack.curCard);
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
    	if(cardMouseDownSent) TTalk.closeOpenFieldByUser(); //a click on the card closes the open field (HC J-2.3)
    	cardClickCounted = false;
    	cardReleaseHandled = false;
    	//the second click of a double-click gets only mouseDoubleClick at its release (doublePress)
    	cardDoublePress = cardMouseDownSent && doublePress(e, PCARD.pc.stack.curCard);
    	mouseDowned();
    	if(cardMouseDownSent && !cardDoublePress){
    		TTalk.CallMessage("mouseDown",PCARD.pc.stack.curCard);
    		startStillDown(PCARD.pc.stack.curCard); //mouseStillDown while held, as HyperCard sends the card
    	}
    }
    //the card press is the second click of a double-click: its release sends mouseDoubleClick instead of mouseUp
    private boolean cardDoublePress = false;
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
    	//the mouseUp of a card press goes out here too: AWT sends no mouseClicked when the pointer moved a pixel or two
    	//between press and release (trackpad jitter), and the card never got its mouseUp (ワイパーカード's mouseUp
    	//starts the wiper). HyperCard sends it when the button is released inside the card.
    	else if(cardMouseDownSent && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null){
    		cardMouseDownSent = false;
    		cardReleaseHandled = true;
    		if(releasedInCard(e)) TTalk.CallMessage(cardDoublePress ? "mouseDoubleClick" : "mouseUp",PCARD.pc.stack.curCard);
    	}
    }
    //mouseReleased sent the card's mouseUp (or dropped it), so the mouseClicked that may follow does nothing
    private boolean cardReleaseHandled = false;
    private static boolean releasedInCard(MouseEvent e){
    	PCARD pc = PCARD.pc;
    	Component c = e.getComponent();
    	if(pc==null || pc.mainPane==null || c==null) return true;
    	Point p = SwingUtilities.convertPoint(c, e.getPoint(), pc.mainPane);
    	return p.x>=0 && p.y>=0 && p.x<pc.mainPane.getWidth() && p.y<pc.mainPane.getHeight();
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
			//the arrows' characters, as on a Mac: keyDown numToChar(28) left, 29 right, 30 up, 31 down (HC J-2.3)
			else if(c==0) keyStr = Character.toString((char)30);
			else if(c==1) keyStr = Character.toString((char)31);
			else if(c==2) keyStr = Character.toString((char)28);
			else if(c==3) keyStr = Character.toString((char)29);
			//Delete 8, Forward Delete 127, Esc 27 (HC J-2.3)
			else if(c==20) keyStr = Character.toString((char)8);
			else if(c==21) keyStr = Character.toString((char)127);
			else if(c==22) keyStr = Character.toString((char)27);
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
			if(clickField!=null && clickField.fld!=null && clickField.fld.isFocusOwner() && !clickField.enabled) keyTarget = clickField;
			boolean inField = keyTarget instanceof OField;
			if(e.getKeyCode()==KeyEvent.VK_ENTER){
				//Return と Enter(テンキー) は別のコマンド
				boolean enter = e.getKeyLocation()==KeyEvent.KEY_LOCATION_NUMPAD;
				String message = inField ? (enter ? "enterInField" : "returnInField") : (enter ? "enterKey" : "returnKey");
				TTalk.CallMessage(message, "", keyTarget, false, false);
			}
			else if(e.getKeyCode()==KeyEvent.VK_TAB){
				TTalk.fieldKeyCommand("tabKey", keyTarget); //HyperCard's own action at the end: the next unlocked field
			}
			else if(controlDown(e) && keyStr==null && k>=32 && k<128 && !(c>=11 && c<=16)){
				//controlKey <文字番号>
				TTalk.CallMessage("controlKey", Integer.toString(k), PCARD.pc.stack.curCard, false, false);
			}
			else if(keyStr!=null){
				String message = "keyDown";
				//key[14]ではなくこのキーイベントの修飾キーで決める(key[]は離したのが別のウインドウだと残ることがある)
				if(commandDown(e)) message = "commandKeyDown";
				if(c>=0 && c<=3 && !commandDown(e)) TTalk.callKeyDown(keyStr, ARROW_NAMES[c], keyTarget);
				else if(c==22 && !commandDown(e)) TTalk.callKeyDown(keyStr, "back", keyTarget);
				else TTalk.CallMessage(message,keyStr,keyTarget,false,false);
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
	
	private static final String[] ARROW_NAMES = {"up", "down", "left", "right"};

	public static void keyEventCheck(){
		boolean isOpened = false;
		//if( PCARDFrame.pc.isFocused() ){
			for(int i=0; i<128; i++){
				if(key[i]==1 || key[i]==3){
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
					//輪郭はカードの描画(MyPanel.paint → paintPeekOutlines)で描く。getGraphics()で一度描くだけだと、
					//カードが描き直されるたびに(フィールドのキャレット、スクリプトが変える部品、ボタンのロールオーバーなど)消えていた
					stroke_frag = want;
					PCARDFrame.pc.mainPane.repaint();
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
	
	/** ピーク中(Command-Option)の部品の輪郭: カードを描くたびに描く(MyPanel.paint) */
	static void paintPeekOutlines(Graphics g){
		int kind = stroke_frag;
		if(kind==0 || PCARDFrame.pc==null || PCARDFrame.pc.stack==null || PCARDFrame.pc.stack.curCard==null) return;
		if(kind==2) fldStroke(g);
		else if(kind==3) shapeStroke(g);
		else btnStroke(g);
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

	/** 黒の破線の隙間を埋める白の破線(黒い背景の上でも見えるよう、白と黒を交互に) */
	static final private BasicStroke STROKE_DOTTED_GAPS =
		new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 2.0f, new float[]{2f}, 2.0f);

	public static void dotStroke(Graphics2D g, OObject obj){
		Rectangle2D.Float bounds = scaledOutlineBounds(obj, PCARD.scale());
		//描画の状態(合成、XOR、アンチエイリアス)を受け継がない: ぼけたり半透明になると白と黒が交互に見えない
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setPaintMode();
			g2.setComposite(java.awt.AlphaComposite.SrcOver);
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
			g2.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_NORMALIZE);
			g2.setPaint(Color.BLACK);
			g2.setStroke(STROKE_DOTTED);
			g2.draw(bounds);
			g2.setPaint(Color.WHITE);
			g2.setStroke(STROKE_DOTTED_GAPS);
			g2.draw(bounds);
		}finally{
			g2.dispose();
		}
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
	/** The button whose press is the second click of a double-click (GUI.doublePress): its release sends mouseDoubleClick, not mouseUp. */
	private MyButton doublePressed;

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
			TTalk.CallMessage(e.getSource()==doublePressed ? "mouseDoubleClick" : "mouseUp", ((MyButton)e.getSource()).btnData);
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
		doublePressed = null;
		if(parent==PCARD.pc.stack.curCard||parent.objectType.equals("background")&&
				((OBackground)parent).viewCard==PCARD.pc.stack.curCard){
			if(TTalk.idle==true && ((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
				if(btn.btnData.getAutoHilite()) TTalk.closeOpenFieldByUser(); //a click on a button with autoHilite closes the open field (HC J-2.3)
				if(GUI.doublePress(e, btn.btnData)) doublePressed = btn; //no mouseDown: mouseDoubleClick at the release
				else{
					TTalk.CallMessage("mouseDown", ((MyButton)e.getSource()).btnData);
					mouseDownSent = btn;
					GUI.notePressedPart(btn.btnData, btn);
					GUI.startStillDown(btn.btnData);
				}
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
		boolean secondClick = doublePressed==source;
		mouseDownSent = null;
		doublePressed = null;
		//Swingがactionを出さなかったクリック(押してから動いた等)だけをここで送る
		if(!reported && TTalk.idle==true && source.btnData!=null && source.btnData.enabled==true
				&& releasedInside(e)){
			TTalk.CallMessage(secondClick ? "mouseDoubleClick" : "mouseUp", source.btnData);
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
		if(((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
			GUI.pointerEntered(((MyButton)e.getSource()).btnData);
		}
    }
	@Override
    public void mouseExited(MouseEvent e) {
		if(((MyButton)e.getSource()).btnData!=null && ((MyButton)e.getSource()).btnData.enabled==true){
			GUI.pointerLeft(((MyButton)e.getSource()).btnData);
    	}
    }

	@Override
	public void mouseDragged(MouseEvent e) {
		//mouseStillDown comes from GUI.startStillDown while held, dragged or not
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
	static void clicked(OButton btn, AbstractButton source, boolean secondClick){
		if(btn==null) return; //its mouseDown rebuilt the card (set the style of a field): the new button is on the screen
		if(TTalk.idle==true){
			if(btn.getAutoHilite()){
				btn.setHilite(source.isSelected());
			}else{
				source.setSelected(btn.storedHilite());
			}
			TTalk.CallMessage(secondClick ? "mouseDoubleClick" : "mouseUp", btn);
		}
		else{
			//スクリプト実行中のクリックはmouseClickとして記録するだけで、ボタンは変えない
			source.setSelected(btn.storedHilite());
			GUI.mouseClicked();
		}
	}

	public void actionPerformed (ActionEvent e) {
		clicked(((MyCheck)e.getSource()).btnData, (MyCheck)e.getSource(), e.getSource()==doublePressed);
	}
	/** The check box whose press is the second click of a double-click (GUI.doublePress): mouseDoubleClick, no mouseDown/mouseUp. */
	private Object doublePressed;

    public void mouseClicked(MouseEvent e) {
    	//
    }
    public void mousePressed(MouseEvent e) {
		doublePressed = null;
		if(TTalk.idle==true){
			if(((MyCheck)e.getSource()).btnData.getAutoHilite()) TTalk.closeOpenFieldByUser();
			if(GUI.doublePress(e, ((MyCheck)e.getSource()).btnData)) doublePressed = e.getSource();
			else{ TTalk.CallMessage("mouseDown", ((MyCheck)e.getSource()).btnData); GUI.notePressedPart(((MyCheck)e.getSource()).btnData, (Component)e.getSource()); }
			GUI.mouseDowned();
			if(doublePressed==null) GUI.startStillDown(((MyCheck)e.getSource()).btnData);
    	}else
    		GUI.mouseDowned();
    }
    public void mouseReleased(MouseEvent e) {
    	doublePressed = null; //Swing's action (actionPerformed) came first
    	GUI.mouseUped();
    }
	//while held, a check box or radio button whose press sent mouseDown gets mouseStillDown from GUI.startStillDown,
	//dragged or not, as other buttons (UFO's stones are radio buttons thrown from "on mouseStillDown")
	public void mouseDragged(MouseEvent e) {
	}
	public void mouseMoved(MouseEvent e) {
	}
    public void mouseEntered(MouseEvent e) {
		GUI.pointerEntered(((MyCheck)e.getSource()).btnData);
    }
    public void mouseExited(MouseEvent e) {
		GUI.pointerLeft(((MyCheck)e.getSource()).btnData);
    }
}





class GPopup implements ActionListener, PopupMenuListener {
	static MyPopup hidePopup;
	
	public void actionPerformed (ActionEvent e) {
		//String cmd = e.getActionCommand();
		//JComboBox fires an action for setSelectedIndex too: a script's "select line 2 of bg btn id 19" (poker.hzb) is no pick
		//(no mouseUp, no click for the mouseClick)
		if(((MyPopup)e.getSource()).settingSelection) return;
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
	public void actionPerformed (ActionEvent e) {
		//autoHiliteの扱いはチェックボックスと同じ(GCheckBox.clicked参照)
		GCheckBox.clicked(((MyRadio)e.getSource()).btnData, (MyRadio)e.getSource(), e.getSource()==doublePressed);
	}
	/** The radio button whose press is the second click of a double-click (as GCheckBox). */
	private Object doublePressed;

    public void mouseClicked(MouseEvent e) {
    	//
    }
    public void mousePressed(MouseEvent e) {
		doublePressed = null;
		if(TTalk.idle==true){
			if(((MyRadio)e.getSource()).btnData.getAutoHilite()) TTalk.closeOpenFieldByUser();
			if(GUI.doublePress(e, ((MyRadio)e.getSource()).btnData)) doublePressed = e.getSource();
			else{ TTalk.CallMessage("mouseDown", ((MyRadio)e.getSource()).btnData); GUI.notePressedPart(((MyRadio)e.getSource()).btnData, (Component)e.getSource()); }
			GUI.mouseDowned();
			if(doublePressed==null) GUI.startStillDown(((MyRadio)e.getSource()).btnData);
    	}else
    		GUI.mouseDowned();
    }
    public void mouseReleased(MouseEvent e) {
    	doublePressed = null; //Swing's action (actionPerformed) came first
    	GUI.mouseUped();
    }
	public void mouseDragged(MouseEvent e) {
	}
	public void mouseMoved(MouseEvent e) {
	}
    public void mouseEntered(MouseEvent e) {
		GUI.pointerEntered(((MyRadio)e.getSource()).btnData);
    }
    public void mouseExited(MouseEvent e) {
		GUI.pointerLeft(((MyRadio)e.getSource()).btnData);
    }
}



class GField implements MouseListener, MouseMotionListener {
	@Override
    public void mouseClicked(MouseEvent e) {
    	//ロックされたフィールドはmouseReleasedで送る(GButton.releasedInside)。ロックしていないフィールドのクリックは
    	//編集で、mouseDown/mouseStillDown/mouseUp/mouseDoubleClick はフィールドにもカードにも届かない(HyperCard J-2.3
    	//を68K VMで実測、Commandクリックでも同じ。mouseEnter/mouseWithin/mouseLeave は届く)。
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
			if(!fld.enabled){
				TTalk.openFieldByUser(fld); //openField (the field open before closes)
				fillToClickedLine(fld, e.getY());
			}
	    	//ロックしたフィールドのクリックは選択も挿入ポイントも作らない(HyperCard: the selectedChunk/selectedField は空)。
	    	//リストフィールドは下で選択行を決める
	    	GUI.clickField = fld.enabled && !fld.autoSelect ? null : fld;
	    	if(fld.enabled ){
	    		if(fld.textHeight<fld.textSize){
	    			fld.textHeight=fld.textSize;
	    		}
	    		pickListLine(fld, e.getY());
			}
			//locked (lockText) only: an unlocked field's click is editing and sends no mouse messages (mouseClicked)
			doublePressed = null;
			mouseDownSent = null;
			if(fld.enabled){
				if(GUI.doublePress(e, fld)) doublePressed = fld; //no mouseDown: mouseDoubleClick at the release
				else{
					TTalk.CallMessage("mouseDown", fld);
					GUI.notePressedPart(fld, (Component)e.getSource());
					mouseDownSent = fld;
					if(fld.autoSelect && fld.dontWrap) TTalk.runOnScriptThread(() -> reshowListField(fld));
					GUI.startStillDown(fld); //mouseStillDown while held
				}
			}
			GUI.mouseDowned();
		}
		else
			GUI.mouseDowned();
    }
	/** A click below the text of an unlocked field: returns fill the lines down to the one clicked, the insertion point at the end
	 *  (HC J-2.3: a press in the lower half of a line counts as the next line; HyperPiece++ 改行させないフィールド, 共有テキストの編集). */
	static void fillToClickedLine(OField fld, int viewY){
		int lineHeight = fld.lineHeight();
		int clicked = Math.floorDiv(2*(PCARD.toLogical(viewY)-OField.textTopMargin(1, fld.wideMargins))-lineHeight, 2*lineHeight);
		String text = fld.getText();
		int textRows;
		try{
			textRows = (int)Math.round(fld.fld.modelToView2D(text.length()).getY()/PCARD.scale()/lineHeight);
		}catch(javax.swing.text.BadLocationException e){ return; }
		if(text.endsWith("\n")) textRows++;
		if(clicked<=textRows) return;
		fld.setText(text+"\n".repeat(clicked-textRows));
		fld.fld.setCaretPosition(fld.fld.getDocument().getLength());
	}

	/** The line of a list field under the pointer becomes the selected one. */
	private static void pickListLine(OField fld, int viewY){
		//スクロールフィールドではMyTextAreaがJViewportのviewなので、viewYは
		//既にスクロール済みのview座標(=テキスト全体の先頭起点)。ここへ更に
		//fld.scrollを足すとスクロール量が二重に数えられ、スクロール後の
		//clickLine/selectedLineが下の行を指してしまう。行の先頭は上余白
		//(通常1px、wideMarginsなら8px)の分だけ下がる
		int logicalY = PCARD.toLogical(viewY);
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
	/** The locked field whose press is the second click of a double-click (GUI.doublePress): mouseDoubleClick, no mouseDown/mouseUp. */
	private OField doublePressed;
	/** The locked field whose press sent mouseDown: its release sends mouseUp even while that mouseDown handler still runs. */
	private OField mouseDownSent;
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
    	boolean secondClick = doublePressed!=null && doublePressed==fld;
    	boolean pressSentMouseDown = mouseDownSent!=null && mouseDownSent==fld;
    	doublePressed = null;
    	mouseDownSent = null;
    	boolean sentMouseUp = false;
    	//ロックされたフィールド(lockText=enabled)は、押してから少し動いたクリックでもmouseUpにする
    	if(fld!=null && fld.enabled && GButton.releasedInside(e)){
    		if(TTalk.idle==true){
    			TTalk.CallMessage(secondClick ? "mouseDoubleClick" : "mouseUp", fld);
    			sentMouseUp = true;
    		}
    		//a quick click whose mouseDown handler still runs: its mouseUp follows that handler, as for buttons (HyperCard)
    		else if(pressSentMouseDown){
    			TTalk.callPendingMouseUp(fld);
    			sentMouseUp = true;
    		}
    	}
    	GUI.mouseUped();
		//as for buttons: the click that sends mouseUp is not the mouseClick its handler waits for (it was when the script
		//thread had started the handler before this line)
		if(!sentMouseUp) GUI.mouseClicked();
		else GUI.mouseDown = false;
    }
	@Override
    public void mouseEntered(MouseEvent e) {
		GUI.pointerEntered(((MyTextArea)e.getSource()).fldData);
    }
	@Override
    public void mouseExited(MouseEvent e) {
		GUI.pointerLeft(((MyTextArea)e.getSource()).fldData);
    }

	@Override
	public void mouseDragged(MouseEvent e) {
		//a locked field's mouseStillDown comes from GUI.startStillDown while held, dragged or not; an unlocked one gets none
		//a list field's selected line follows the pointer inside the field, one line (HC J-2.3: drag from line 2 to 6 gives
		//"line 6 to 6"; HyperPiece++ ドラッグで並べ替え reads it at the mouseUp)
		OField fld = ((MyTextArea)e.getSource()).fldData;
		if(fld!=null && fld.isListField() && !fld.multipleLines && ((MyTextArea)e.getSource()).getVisibleRect().contains(e.getPoint())) pickListLine(fld, e.getY());
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
		if(mouseDownSent) TTalk.closeOpenFieldByUser();
		secondClick = mouseDownSent && GUI.doublePress(e, v.shape); //as a button: mouseDoubleClick at the release, nothing now
		if(mouseDownSent && !secondClick){
			TTalk.CallMessage("mouseDown", v.shape);
			GUI.startStillDown(v.shape); //mouseStillDown while held, dragged or not
		}
	}
	private boolean secondClick;
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
			TTalk.CallMessage(secondClick ? "mouseDoubleClick" : "mouseUp", v.shape);
			GUI.mouseDown = false; //as for buttons: the click that sends mouseUp is not the mouseClick of its handler
		}
		else GUI.mouseClicked();
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
		//mouseStillDown comes from GUI.startStillDown while held, dragged or not (as for buttons)
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
		if(fld.applyingText) return; //a script's text (OField.setText): the field has it and its runs already
		String text = fld.getMyTextArea().getText();
		fld.preserveStylesForTextChange(text);
		fld.setTextByInputarea(text);
	}
	@Override
	public void insertUpdate(DocumentEvent e) {
		edited();
		changedUpdate(e);
	}
	@Override
	public void removeUpdate(DocumentEvent e) {
		edited();
		changedUpdate(e);
	}
	/** Typed into (not a script's text): the open field sends closeField when it closes, also when the text is back as it was */
	private void edited() {
		if(!fld.applyingText && fld==TTalk.openField) TTalk.openFieldEdited = true;
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
		TabletInput.update(e);
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
		TabletInput.update(e);
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

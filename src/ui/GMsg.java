import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * メッセージボックス。HyperCard 2.xのメッセージウインドウ(475x48: 縞模様の細いタイトルバーと左端の
 * クローズボックス、白い本体に点線の下線、表示のたびに全文選択)を下敷きに、ツールパレット(GToolBar)と
 * 共通のタイトルバー(GWindoidBar)で現代風に描き直したもの。OSの飾りは使わない(undecorated)。
 */
public class GMsg extends JDialog {
	private static final long serialVersionUID = 1L;
	static GMsg msg = null;
	static JTextField area;

	//実機は幅475・タイトルバー9px・本体35px
	static final int WIDTH = 480;
	static final int BAR_HEIGHT = GWindoidBar.HEIGHT;
	static final int BODY_HEIGHT = 36;
	static final int HEIGHT = BAR_HEIGHT+BODY_HEIGHT;
	static final int TEXT_MARGIN = 12; //実機は左13ドット

	private static final Color DOTS = new Color(0x40,0x40,0x40);

	private final GWindoidBar bar;
	private boolean selectOnShow = true;
	/** ブラインドタイピングで出してから、入力欄にフォーカスが来るまでの間 */
	private boolean awaitingFocus;

	GMsg(Frame owner, String text) {
		super(owner, "Message");
		OWindow.msgwindow = new OWindow(this);
		setUndecorated(true);
		//角丸はsetShapeで切り抜く。ピクセル単位の半透明ウインドウ(setBackgroundに透明色)にすると
		//macOSで日本語IMEの変換中の文字が描かれない/ずれるので使わない
		setResizable(false);
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

		JPanel root = new JPanel(null) {
			private static final long serialVersionUID = 1L;
			@Override
			protected void paintComponent(Graphics g) {
				GWindoidBar.paintWindowBody((Graphics2D)g, getWidth(), getHeight(), Color.WHITE);
			}
		};
		root.setOpaque(true);
		setContentPane(root);

		bar = new GWindoidBar(this, GMsg::hideMessage);
		bar.setBounds(0,0,WIDTH,BAR_HEIGHT);
		root.add(bar);

		area = new JTextField(text) {
			private static final long serialVersionUID = 1L;
			@Override
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				//HyperCardのメッセージボックスと同じ点線の下線(1ドットおき)
				g.setColor(DOTS);
				int y = getHeight()-7;
				int right = getWidth()-getInsets().right;
				for(int x=getInsets().left; x<right; x+=2) g.fillRect(x, y, 1, 1);
			}
		};
		area.setFont(messageFont());
		area.setMargin(new Insets(0,0,0,0));
		area.setBorder(new EmptyBorder(0,TEXT_MARGIN,0,TEXT_MARGIN));
		area.setBounds(1,BAR_HEIGHT,WIDTH-2,BODY_HEIGHT-1);
		area.setOpaque(false);
		area.setCaretColor(Color.BLACK);
		root.add(area);

		setSize(WIDTH,HEIGHT);
		setLocation(owner.getX()+owner.getWidth()/2-WIDTH/2, owner.getY()+owner.getHeight());

		area.addKeyListener(new GMsgListener());
		area.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override public void focusGained(java.awt.event.FocusEvent e) { awaitingFocus = false; }
		});
		//メッセージボックスにフォーカスがあるときもcmd-Mで閉じられるように(メニューのショートカットは届かない)
		int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_M, menuMask), "hideMessage");
		area.getActionMap().put("hideMessage", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(java.awt.event.ActionEvent e) { hideMessage(); }
		});

		addKeyListener(GUI.gui);//キー入力は共通で使う

		msg = this;
		installKeepBehind();

		//ブラインドタイピング: カードウインドウのどこにフォーカスがあっても、フィールド以外への文字入力を拾う
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher() {
			@Override public boolean dispatchKeyEvent(KeyEvent e) {
				if(e.getID()!=KeyEvent.KEY_TYPED || e.isConsumed()) return false;
				if((e.getModifiersEx() & (KeyEvent.META_DOWN_MASK|KeyEvent.CTRL_DOWN_MASK))!=0) return false;
				Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
				if(focus==null || focus instanceof javax.swing.text.JTextComponent) return false;
				if(SwingUtilities.getWindowAncestor(focus)!=owner && focus!=owner) return false;
				if(!blindType(e.getKeyChar())) return false;
				e.consume();
				return true;
			}
		});

		this.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				//高さは固定。幅はスクリプト(set the rect of msg window)で変えられる
				int w = Math.max(200, msg.getWidth());
				if(w!=msg.getWidth() || msg.getHeight()!=HEIGHT) msg.setSize(w, HEIGHT);
				bar.setBounds(0,0,msg.getWidth(),BAR_HEIGHT);
				area.setBounds(1,BAR_HEIGHT,msg.getWidth()-2,BODY_HEIGHT-1);
				GWindoidBar.applyShape(msg);
			}

			@Override
			public void componentShown(ComponentEvent e) {
				//HyperCardは表示するたびに中身を全部選択する(そのまま打てば置き換わる)。
				//ブラインドタイピングで出たときだけは、打った文字の後ろに続けて打てるようにする
				if(caretOnShow>=0) area.setCaretPosition(Math.min(caretOnShow, area.getText().length()));
				else if(selectOnShow) area.selectAll();
				else area.setCaretPosition(area.getText().length());
				selectOnShow = true;
				if(focusOnShow || caretOnShow>=0){
					caretOnShow = -1;
					area.requestFocus();
					area.requestFocusInWindow();
				}
				else{
					//スクリプトから出たとき: キー入力はカードウインドウのまま(ゲームのキー操作を奪わない)
					Window o = getOwner();
					if(o!=null && o.isShowing()) o.requestFocus();
				}
			}
		});
	}

	/** Go > Find…: the insertion point to put when shown (-1: none) */
	private int caretOnShow = -1;

	/** Go > Find…: find "" with the insertion point between the quotes, typing goes to the message box */
	static void showFind(){
		if(msg==null) return;
		String text = "find \"\"";
		area.setText(text);
		if(!msg.isVisible()){
			msg.caretOnShow = text.length()-1;
			msg.setVisible(true);
		}
		else{
			area.setCaretPosition(text.length()-1);
			msg.toFront();
			area.requestFocus();
			area.requestFocusInWindow();
		}
	}

	/** 表示したときに挿入ポイントをメッセージボックスへ移すか(cmd-M/メニュー/ブラインドタイピングのときだけ) */
	private boolean focusOnShow = true;

	/**
	 * いま出そうとしているのがユーザーの操作(cmd-M、メニュー、ブラインドタイピング、メッセージボックスに打った行)か。
	 * ハンドラの実行中(put into msg / show msg)ならfalse
	 */
	static boolean shownByUser(){
		return ScriptDebugger.depth()==0;
	}

	/** 実機のGeneva/Osaka 12に近い、システムにある本文フォント */
	private static Font messageFont(){
		for(String name : new String[]{"Geneva","Osaka"}){
			Font f = new Font(name, Font.PLAIN, 13);
			if(f.getFamily().equalsIgnoreCase(name)) return f;
		}
		return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
	}

	private static boolean keepBehindInstalled;
	/**
	 * スクリプトエディタなど、カードウインドウ以外のウインドウが前面に来たらメッセージボックスをその後ろにする。
	 * macOSのAWTはカードの子ウインドウ(メッセージボックス)を、カードがキーのあいだフローティングレベルに上げ、
	 * キーでなくなると通常レベルに戻す。ところがカードがモーダルダイアログ(answer、スクリプトエラー)で
	 * ブロックされている間は戻さないので、その後に開いたスクリプトエディタの上にメッセージボックスが残っていた。
	 */
	private static synchronized void installKeepBehind(){
		if(keepBehindInstalled || !GMenu.isMacOSX()) return;
		keepBehindInstalled = true;
		Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
			if(e.getID()==java.awt.event.WindowEvent.WINDOW_GAINED_FOCUS) keepBehind(((java.awt.event.WindowEvent)e).getWindow());
		}, AWTEvent.WINDOW_FOCUS_EVENT_MASK);
	}
	private static void keepBehind(Window front){
		GMsg m = msg;
		if(m==null || !m.isVisible() || front==null || !front.isShowing()) return;
		//カードウインドウとその子(メッセージボックス自身、answerのダイアログ等)が前面のときはそのまま
		for(Window w=front; w!=null; w=w.getOwner()) if(w==m || w==m.getOwner()) return;
		//フローティングから通常レベルへ戻す(alwaysOnTopの切り替えでNSWindowのレベルが設定し直される)
		m.setAlwaysOnTop(true);
		m.setAlwaysOnTop(false);
		front.toFront();
	}

	/** 閉じるボタン/cmd-M: メニューの「メッセージ」と同じく"hide msg"を通す */
	static void hideMessage(){
		try{ TTalk.doScriptforMenu("hide msg"); }
		catch(xTalkException e){ if(msg!=null) msg.setVisible(false); }
	}

	/** 隠したらキー入力はカードウインドウに戻す(飾りなしの子ウインドウなので、OSに任せると宙に浮くことがある) */
	@Override
	public void setVisible(boolean b){
		boolean was = isVisible();
		if(b && !was){
			//ハンドラの実行中に出たメッセージボックスはキー入力を奪わない(表示後にクリックすれば打てる)
			focusOnShow = shownByUser();
			setAutoRequestFocus(focusOnShow);
		}
		super.setVisible(b);
		if(!b && was) CardWindow68k.messageBoxHidden();
		if(!b && was){
			Window o = getOwner();
			if(o!=null && o.isShowing()){ o.toFront(); o.requestFocus(); }
		}
	}

	public void setText(String text){
		String newstr="";
		for(int i=0; i<text.length(); i++){
			String substr = text.substring(i,i+1);
			if(0!=substr.compareTo("\n") && 0!=substr.compareTo("\r")) newstr += substr;
		}
		
		area.setText(newstr);
		area.setCaretPosition(newstr.length()); //続けて打てるように挿入ポイントは末尾
		if(!msg.isVisible()){
			selectOnShow = false; //put/ブラインドタイピングで出たときは選択しない(show msg / cmd-Mのときだけ全選択)
			//カードウインドウは隠れているときだけ先に出す。表示中のウインドウへのsetVisible(true)はtoFrontになり、
			//カードがキーのあいだに出た子ウインドウ(通常レベル)のメッセージボックスがカードの後ろに回っていた(Wingsのチュートリアル)
			Frame card = PCARDFrame.pc.stack.pcard;
			if(card!=null && !card.isVisible()) card.setVisible(true);
			msg.setVisible(true);
		}
	}
	
	public String getText(){
		return area.getText();
	}

	/**
	 * ブラインドタイピング: メッセージボックスが隠れているときにカード上で文字を打つと、
	 * メッセージボックスを出してその文字から入力を始める(HyperCardのblindTypingがtrueのとき)。
	 * 文字を受け付けたらtrue。
	 */
	static boolean blindType(char ch){
		if(msg==null || PCARDFrame.pc==null || PCARDFrame.pc.stack==null) return false;
		if(ch<' ' || ch==KeyEvent.CHAR_UNDEFINED || ch==127) return false;
		if(msg.isVisible()){
			//出した直後、フォーカスが移る前に打たれた文字は後ろに足す(取りこぼさない)
			if(!msg.awaitingFocus) return false;
			area.setText(area.getText()+ch);
			area.setCaretPosition(area.getText().length());
			return true;
		}
		if(!TUtil.compatibilityProperty("blindtyping").equalsIgnoreCase("true")) return false;
		//ハンドラ実行中のキーはkeyDown/the keyの対象で、メッセージボックスは開かない(K-0Fightersのバトルでキー操作を奪っていた)
		if(!TTalk.idle) return false;
		msg.awaitingFocus = true;
		msg.setText(String.valueOf(ch));
		return true;
	}
}

class GMsgListener implements KeyListener {

	@Override
	public void keyPressed(KeyEvent e) {
		
		switch (e.getKeyCode()) {
			case KeyEvent.VK_ENTER:
				String str = GMsg.msg.getText();
				if(str.indexOf("\n")>=0) str = str.substring(0,str.indexOf("\n"));
				String res = null;
				try{
					res = TTalk.doScriptforMenu(str);
				}catch(xTalkException e1){
					e1.printStackTrace();
					Object[] options = { "OK" };
					JOptionPane.showOptionDialog(PCARDFrame.pc.stack.pcard,
							"Script Error: "+e1.getMessage(),
							"Script Error",
							JOptionPane.YES_OPTION,
							JOptionPane.WARNING_MESSAGE, null, options, options[0]);
				}
				if(res!=null && res.length()>0) {
					try { GMsg.msg.setText(TalkCollection.display(res)); }
					catch(xTalkException badCollection) { GMsg.msg.setText(res); }
				}
				else GMsg.msg.setText(str);
				PCARDFrame.pc.stack.pcard.toFront();
				//HyperCardと同じく、実行後も挿入ポイントはメッセージボックスに残る
				if(GMsg.msg.isVisible()){ GMsg.msg.toFront(); GMsg.area.requestFocusInWindow(); }
				break;
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
		switch (e.getKeyCode()) {
			case KeyEvent.VK_ENTER:
				String str = GMsg.msg.getText();
				if(str.indexOf("\n")>=0) str = str.substring(0,str.indexOf("\n"));
				GMsg.msg.setText(str);
				break;
		}
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}
	
}

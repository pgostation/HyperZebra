import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

import javax.swing.*;

public class GDialog extends JDialog implements ActionListener {
	/** The dialog was the active window when it closed (HyperZebra was frontmost). */
	private boolean closedActive;
	/** Like HyperCard after an alert, the card window is active again: with nothing to give the focus back to (the owner is
	 *  Swing's hidden frame; xWindowFrame's backdrop behind the card takes no focus), macOS left HyperZebra in the background
	 *  and the stack got no idle messages (Myst Preview stayed on its black first card). */
	static void returnToCard(java.awt.Window dialog, boolean closedActive){
		if(!closedActive) return;
		PCARD pc = PCARD.pc;
		java.awt.Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if(pc!=null && pc.isShowing() && (active==null || active==dialog)){ pc.toFront(); pc.requestFocus(); }
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String inputText="";
	static JTextField inputArea;
	static JButton defaultButton;
	
	GDialog(Frame owner, String text, String input, String b1, String b2, String b3) {
		this(owner, null, text, input, b1, b2, b3);
	}

	/** The translation of the dialog of a stack's script (answer, ask): what it shows, null for HyperZebra's own dialogs. */
	private StackTranslation.Dialog translation;
	private javax.swing.Timer following;
	private JTextArea promptArea;
	private String prompt;
	private final java.util.List<JButton> named = new java.util.ArrayList<JButton>();

	/** @param tr the translation of what a stack's script shows (null: as it is). The buttons answer with their own
	 *            names (clicked) whatever they show. */
	GDialog(Frame owner, StackTranslation.Dialog tr, String text, String input, String b1, String b2, String b3) {
		super((Frame)null,true);//オーナー無し(理由はGPictWindow.prepareForModalDialog参照)
		translation = tr;
		prompt = text;
		if(input!=null){ buildAsk(owner, text, input, b1, b2); return; }
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		getContentPane().add("North",topPanel);

		JTextArea area = new JTextArea(answerLines(shownPrompt()));
		promptArea = area;
		area.setMargin(new Insets(16,16,2,16));
		area.setLineWrap(true);
		area.setOpaque(false);
		area.setEditable(false);
		area.setFocusable(false);
		topPanel.add(area);

		if(b1!=null){
			JButton btn1 = namedButton(b1);
			btmPanel.add(btn1);
		}
		if(b2!=null){
			JButton btn2 = namedButton(b2);
			btmPanel.add(btn2);
		}
		if(b3!=null){
			JButton btn3 = namedButton(b3);
			btmPanel.add(btn3);
			getRootPane().setDefaultButton(btn3);
			defaultButton = btn3;
		}

		int h = answerHeight();
		java.awt.Rectangle anchor = GLauncher.dialogArea(owner);
		if(anchor!=null){
			setBounds(anchor.x+anchor.width/2-200,anchor.y+anchor.height/2-h/2,400,h+48);
		}
		else{
			setBounds(0,0,400,h+48);
			setLocationRelativeTo(null);
		}
		placeForScript(OHcWindow.answer);
		setUndecorated(true);//タイトルバー非表示

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});

		follow();
		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
		GDialog.returnToCard(this, closedActive);
		
	}

	private String shownPrompt(){ return translation==null ? prompt : translation.prompt(prompt); }

	/** A stack's answer/ask comes up where a script put window "Answer"/"Ask" (HyperCard 10th Anniversary: set the loc of window
	 *  "answer" to 173,196), in the card window's coordinates; HyperZebra's own dialogs stay centred. */
	private void placeForScript(OHcWindow w){
		if(translation==null || !w.placed || PCARD.pc==null) return;
		try{
			Point o = TUtil.getCardOriginOnScreen();
			setLocation(o.x+PCARD.toPhysical(w.left), o.y+PCARD.toPhysical(w.top));
		}catch(RuntimeException e){ /* no card window: centred */ }
	}

	/** A button that answers with {@code name} and shows its translation. */
	private JButton namedButton(String name){
		JButton b = new JButton(translation==null ? name : translation.button(name));
		b.setActionCommand(name);
		b.addActionListener(this);
		named.add(b);
		return b;
	}

	/** answer's prompt as the text area shows it: each line (return) ends with a newline. */
	private static String answerLines(String text){
		String[] texts2 = text.split("\r");
		StringBuilder text2 = new StringBuilder();
		for(int i=0; i<texts2.length; i++){
			text2.append(texts2[i]).append("\n");
		}
		return text2.toString();
	}

	/** The height of answer's prompt (at most 500), the area sized for it. */
	private int answerHeight(){
		String text2 = promptArea.getText();
		String[] texts = text2.split("\n");
		promptArea.setSize(380, 18+18*texts.length);
		int h = promptArea.getPreferredSize().height;
		if(h>500)h=500;
		if(inputArea!=null) h+= inputArea.getPreferredSize().height;
		return h;
	}

	/** Shows the translations that arrive while the dialog is open: the texts change, the dialog keeps its centre. */
	private void follow(){
		if(translation==null) return;
		following = translation.follow(this::retranslate);
	}
	private void retranslate(){
		if(!isDisplayable()) return;
		boolean changed = false;
		for(JButton b : named){
			String label = translation.button(b.getActionCommand());
			if(!label.equals(b.getText())){ b.setText(label); changed = true; }
		}
		String shown = shownPrompt();
		if(askRoot!=null){
			if(!shown.replace("\r","\n").equals(promptArea.getText())){ promptArea.setText(shown.replace("\r","\n")); placeAsk(false); }
			return;
		}
		if(!answerLines(shown).equals(promptArea.getText())){ promptArea.setText(answerLines(shown)); changed = true; }
		if(!changed) return;
		int h = answerHeight();
		java.awt.Rectangle r = getBounds();
		setBounds(r.x, r.y+r.height/2-(h+48)/2, 400, h+48);
		getContentPane().revalidate();
		repaint();
	}

	@Override
	public void dispose(){
		if(following!=null){ following.stop(); following = null; }
		super.dispose();
	}

	private JPanel askRoot;
	private JTextField askField;
	private JButton askOk, askCancel;

	/** askの配置はHyperCard J-2.3の実機(68K VM)で測った値: 335x103、入力欄(13,34)-(317,55)、OK(136,66)/Cancel(238,66)の各80x20、
	 *  プロンプトが1行増えるごとに全体+15。部品の見た目はanswerダイアログ(GDialog)と同じ標準のSwing部品。
	 *  ボタンはHyperZebraのもの(訳さない)。訳すのはプロンプトだけで、入力欄(打ち込む文字)は訳さない */
	private void buildAsk(Frame owner, String text, String input, String cancel, String ok){
		JPanel root = new JPanel(null);
		askRoot = root;
		JTextArea area = new JTextArea(shownPrompt().replace("\r","\n"));
		promptArea = area;
		area.setLineWrap(true); area.setOpaque(false); area.setEditable(false); area.setFocusable(false);
		area.setMargin(new Insets(0,0,0,0));
		root.add(area);
		JTextField field = new JTextField(input);
		askField = field;
		root.add(field);
		inputArea = field;
		String cancelLabel = cancel;
		try{ cancelLabel = PCARD.pc.intl.getDialogText(cancel); }catch(Exception e){}
		JButton okBtn = new JButton(ok), cancelBtn = new JButton(cancelLabel);
		askOk = okBtn; askCancel = cancelBtn;
		cancelBtn.setActionCommand(cancel);
		okBtn.addActionListener(this); cancelBtn.addActionListener(this);
		root.add(okBtn); root.add(cancelBtn);
		getRootPane().setDefaultButton(okBtn);
		getRootPane().registerKeyboardAction(e->cancelBtn.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().registerKeyboardAction(e->cancelBtn.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD,Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_IN_FOCUSED_WINDOW);
		setContentPane(root);
		setUndecorated(true);
		java.awt.Rectangle anchor = GLauncher.dialogArea(owner);
		placeAsk(true);
		int W = getWidth(), H = getHeight();
		if(anchor!=null) setLocation(anchor.x+anchor.width/2-W/2, anchor.y+anchor.height/2-H/2);
		else setLocationRelativeTo(null);
		placeForScript(OHcWindow.ask);
		SwingUtilities.invokeLater(new Runnable(){ public void run(){ field.requestFocusInWindow(); field.selectAll(); }});
		follow();
		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
		GDialog.returnToCard(this, closedActive);
	}

	/** Lays out ask for its prompt's height; {@code first}: sized only, otherwise the dialog keeps its centre. */
	private void placeAsk(boolean first){
		promptArea.setBounds(14, 10, 304, 10000);
		int th = Math.min(promptArea.getPreferredSize().height, 300);
		promptArea.setBounds(14, 10, 304, th);
		final int extra = Math.max(0, th-16);
		final int W = 335, H = 103+extra;
		askField.setBounds(13, 34+extra, 305, 22);
		//ボタンは88幅(HCの80x20+フォーカス枠)。名前が入らないとき(日本語の「キャンセル」)は名前の幅まで広げ、
		//右端(Cancel 322)と間隔(14)はそのままで左へ伸ばす
		int cw = Math.max(88, askCancel.getPreferredSize().width), ow = Math.max(88, askOk.getPreferredSize().width);
		int cx = 238-4+88-cw, ox = Math.max(4, cx-14-ow);
		askOk.setBounds(ox, 66+extra-4, ow, 28);
		askCancel.setBounds(cx, 66+extra-4, cw, 28);
		if(first){ setSize(W, H); return; }
		java.awt.Rectangle r = getBounds();
		setBounds(r.x, r.y+r.height/2-H/2, W, H);
		askRoot.revalidate();
		repaint();
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		if(inputArea != null) inputText = inputArea.getText();
		inputArea = null;
		//ダイアログのボタンを押したクリックは、カードへのクリックではないので
		//「the mouseClick」に残さない（answer/ask直後に古いクリックが誤検出されるのを防ぐ）
		GUI.mouseClicked = false;
		closedActive = isActive();
		this.dispose();
	}
}




class GScrollDialog extends JDialog implements ActionListener {
	private boolean closedActive;
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String inputText="";
	static JTextField inputArea;
	static JButton defaultButton;
	
	GScrollDialog(Frame owner, String text, String input, String b1, String b2, String b3) {
		super((Frame)null,true);//オーナー無し(理由はGPictWindow.prepareForModalDialog参照)
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);
		//JPanel topPanel = new JPanel();
		//topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		

		String[] texts2 = text.split("\r");
		String text2 = "";
		for(int i=0; i<texts2.length; i++){
			text2 += texts2[i]+"\n";
		}
		JTextArea area = new JTextArea(text2);
		String[] texts = text2.split("\n");
		area.setSize(380, 18+18*texts.length);
		//area.setPreferredSize(new Dimension(380, 18+18*texts.length));
		area.setMargin(new Insets(16,16,2,16));
		area.setLineWrap(true);
		area.setOpaque(false);
		area.setEditable(false);
		area.setFocusable(false);
		//topPanel.add(area);
		JScrollPane scrollpane = new JScrollPane(area);
		getContentPane().add("North",scrollpane);
		scrollpane.setSize(new Dimension(380,400));

		if(b1!=null){
			JButton btn1 = new JButton(b1);
			btn1.addActionListener(this);
			btmPanel.add(btn1);
		}
		if(b2!=null){
			JButton btn2 = new JButton(b2);
			btn2.addActionListener(this);
			btmPanel.add(btn2);
		}
		if(b3!=null){
			JButton btn3 = new JButton(b3);
			btn3.addActionListener(this);
			btmPanel.add(btn3);
			getRootPane().setDefaultButton(btn3);
			defaultButton = btn3;
		}

		int h = area.getPreferredSize().height;
		if(h>500)h=500;
		if(inputArea!=null) h+= inputArea.getPreferredSize().height;
		java.awt.Rectangle anchor = GLauncher.dialogArea(owner);
		if(anchor!=null){
			setBounds(anchor.x+anchor.width/2-200,anchor.y+anchor.height/2-h/2,400,h+48);
		}
		else{
			setBounds(0,0,400,h+48);
			setLocationRelativeTo(null);
		}
		setUndecorated(true);//タイトルバー非表示
		
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});
		
		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
		GDialog.returnToCard(this, closedActive);
		
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		if(inputArea != null) inputText = inputArea.getText();
		inputArea = null;
		//ダイアログのボタンを押したクリックは、カードへのクリックではないので
		//「the mouseClick」に残さない（answer/ask直後に古いクリックが誤検出されるのを防ぐ）
		GUI.mouseClicked = false;
		closedActive = isActive();
		this.dispose();
	}
}










class GUxAnswer extends JDialog implements ActionListener {
	private boolean closedActive;
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String inputText="";
	static JTextField inputArea;
	static JButton defaultButton;
	
	GUxAnswer(Frame owner, String text, String input, int[] icons, int iconwait, int time, String[] buttons) {
		this(owner, null, text, input, icons, iconwait, time, buttons);
	}

	/** The translation of what the stack's script shows (null: as it is); the buttons answer with their own names. */
	private final StackTranslation.Dialog translation;
	private javax.swing.Timer following;
	private final String prompt;
	private final java.util.List<JButton> named = new java.util.ArrayList<JButton>();

	GUxAnswer(Frame owner, StackTranslation.Dialog tr, String text, String input, int[] icons, int iconwait, int time, String[] buttons) {
		super((Frame)null,true);//オーナー無し(理由はGPictWindow.prepareForModalDialog参照)
		translation = tr;
		prompt = text;
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);
		JPanel topPanel = new JPanel();
		//topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		getContentPane().add("North",topPanel);

		JPanel topPanel2 = new JPanel();
		topPanel.add(topPanel2);
		
		//アイコン読み込み。ICONは生データ(.data)のまま保存されていることがあるため、
		//ImageIcon(path)ではなくgetImage()経由でデコードする(Rsrc.loadImage参照)。
		//Inside Macintoshの慣習どおり、同じIDのcicn(カラーアイコン)があれば
		//モノクロのICONより優先して表示する(Rsrc.getDisplayImage参照)。
		BufferedImage iconImage = (icons==null || icons.length==0) ? null : PCARD.pc.stack.rsrc.getDisplayImage(icons[0], "ICON");
		if(iconImage!=null){
			JLabel label = new JLabel(new ImageIcon(iconImage));
			topPanel2.add(label);
		}
		
		//テキスト
		String text2 = lines(shownPrompt());
		JTextArea area = new JTextArea(text2);
		String[] texts = text2.split("\n");
		area.setSize(360, 18+18*texts.length);
		//area.setPreferredSize(new Dimension(380, 18+18*texts.length));
		area.setMargin(new Insets(16,16,2,16));
		area.setLineWrap(true);
		area.setOpaque(false);
		area.setEditable(false);
		area.setFocusable(false);
		topPanel2.add(area);

		for(int i=0; i<buttons.length; i++){
			if(buttons[i]!=null){
				JButton btn = new JButton(translation==null ? buttons[i] : translation.button(buttons[i]));
				btn.setActionCommand(buttons[i]);
				btn.addActionListener(this);
				named.add(btn);
				btmPanel.add(btn);
				getRootPane().setDefaultButton(btn);
				defaultButton = btn;
			}
		}
		
		if(input != null){
			JTextField area2 = new JTextField(input);
			area2.setSize(350, 24);
			area2.setMargin(new Insets(16,16,16,16));
			topPanel.add(area2);
			inputArea = area2;
		}

		int h = area.getPreferredSize().height;
		if(h>500)h=500;
		if(inputArea!=null) h+= inputArea.getPreferredSize().height;
		java.awt.Rectangle anchor = GLauncher.dialogArea(owner);
		if(anchor!=null){
			setBounds(anchor.x+anchor.width/2-200,anchor.y+anchor.height/2-h/2,400,h+48);
		}
		else{
			setBounds(0,0,400,h+48);
			setLocationRelativeTo(null);
		}
		setUndecorated(true);//タイトルバー非表示
		
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});
		
		if(translation!=null) following = translation.follow(() -> retranslate(area));
		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
		GDialog.returnToCard(this, closedActive);
		
	}

	private String shownPrompt(){ return translation==null ? prompt : translation.prompt(prompt); }
	private static String lines(String text){
		StringBuilder b = new StringBuilder();
		for(String line : text.split("\r")) b.append(line).append("\n");
		return b.toString();
	}
	/** Translations arrived while the dialog is open: new texts, the dialog as high as they need around its centre. */
	private void retranslate(JTextArea area){
		if(!isDisplayable()) return;
		boolean changed = false;
		for(JButton b : named){
			String label = translation.button(b.getActionCommand());
			if(!label.equals(b.getText())){ b.setText(label); changed = true; }
		}
		String text2 = lines(shownPrompt());
		if(!text2.equals(area.getText())){ area.setText(text2); changed = true; }
		if(!changed) return;
		area.setSize(360, 18+18*text2.split("\n").length);
		int h = area.getPreferredSize().height;
		if(h>500)h=500;
		if(inputArea!=null) h+= inputArea.getPreferredSize().height;
		java.awt.Rectangle r = getBounds();
		setBounds(r.x, r.y+r.height/2-(h+48)/2, 400, h+48);
		getContentPane().revalidate();
		repaint();
	}

	@Override
	public void dispose(){
		if(following!=null){ following.stop(); following = null; }
		super.dispose();
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		if(inputArea != null) inputText = inputArea.getText();
		inputArea = null;
		//ダイアログのボタンを押したクリックは、カードへのクリックではないので
		//「the mouseClick」に残さない（answer/ask直後に古いクリックが誤検出されるのを防ぐ）
		GUI.mouseClicked = false;
		closedActive = isActive();
		this.dispose();
	}
}

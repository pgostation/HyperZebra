import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;

public class TextEditor extends JFrame {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static TextEditor TextEditor;
	TextArea area;
	
	public TextEditor(Frame owner, OObject obj) {

		//frame
		setBounds(owner.getX()+owner.getWidth()/2-160,owner.getY()+owner.getHeight()/2-160,320,320);
		getContentPane().setLayout(new BorderLayout());
		
		//menu
		new TEMenu(this);
		
		JScrollPane scrollpane = new JScrollPane();
		scrollpane.setName("JScrollPane");
		scrollpane.setBounds(0, 0, 320, 320);
		scrollpane.setPreferredSize(new Dimension(320, 320));
		scrollpane.getVerticalScrollBar().setValue(0);
		scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollpane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		this.add(scrollpane);

		String text = obj.getText();
		
		//area
		area = new TextArea(text);
		area.setObject(obj);
		area.setSize(320, 320);
		area.setMargin(new Insets(2,2,2,2));
		area.getPreferredSize();
		area.setOpaque(false);
		area.setCaretPosition(0);
		//scrollpane.add(area);
		scrollpane.setViewportView(area);
		
		area.setFont(new Font(PCARD.scriptFont, 0, PCARD.scriptFontSize));

		toFront();
		setVisible(true);

		area.requestFocus();
		
		TextEditor = this;
	}

	/*static boolean saveAlert(TextArea area){
		if(area == null || area.saved) {
			return true;
		}
		saveScript(area);
		return true;
	}
	
	static void saveScript(TextArea area){
		String allStr = "";
		try {
			allStr = area.getDocument().getText(0, area.getDocument().getLength());
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
		
		OObject obj = area.object;
		
		if(obj != null){
			obj.text = allStr;
			area.savedScript();
		} else {
			System.out.println("テキストの保存に失敗しました");
		}
	}*/
}

class teListener implements DocumentListener
{
	TextArea area;
	
	teListener(TextArea inarea){
		area = inarea;
	}
	
	@Override
	public void changedUpdate(DocumentEvent e) {
		area.changedScript();
		
		OObject obj = area.object;
		if(obj != null){
			obj.setText(area.getText());
		}
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

class TextArea extends JTextArea {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OObject object;
	//
	boolean saved = true;
	UndoManager undo = null;
	teListener teListener = null;
	
	public TextArea(String text) {
		super(text);
		undo = new UndoManager();
		getDocument().addUndoableEditListener(undo);
		teListener = new teListener(this);
		getDocument().addDocumentListener(teListener);
	}
	
	boolean compareObject(OObject obj){
		return (obj==this.object);
	}
	
	void setObject(OObject obj){
		this.object = obj;
	}
	
	void changedScript(){
		saved = false;
		//TextEditor.TextEditor.getRootPane().putClientProperty("windowModified", Boolean.TRUE);
		setCanUndo();
	}
	
	void savedScript(){
		saved = true;
		//TextEditor.TextEditor.getRootPane().putClientProperty("windowModified", Boolean.FALSE);
	}

	void setCanUndo(){
		TEMenu.undoMenu.setEnabled(true);
	}
	void undoStatus(){
		TEMenu.undoMenu.setEnabled(undo.canUndo());
		TEMenu.redoMenu.setEnabled(undo.canRedo());
	}
}


class TEMenu {

	/**
	 * 
	 */
	static JMenuItem undoMenu = null;
	static JMenuItem redoMenu = null;
	
	public TEMenu(TextEditor TextEditor){
		ActionListener listener=null;
		
		listener = new TEMenuListener();
		
    	// メニューバーの設定
		JMenuBar mb=new JMenuBar();
		TextEditor.setJMenuBar(mb);
		
		JMenu m;
		JMenuItem mi;
		int s=InputEvent.CTRL_DOWN_MASK;
		if(System.getProperty("os.name").toLowerCase().startsWith("mac os x")){
			s = InputEvent.META_DOWN_MASK;
		}
		int s_shift = s+InputEvent.SHIFT_MASK;

	    // Fileメニュー

	    // Editメニュー
	    m=new JMenu(PCARDFrame.pc.intl.getText("Edit"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Undo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);
	    undoMenu = mi;
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Redo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shift));mi.addActionListener(listener);
	    redoMenu = mi;
	    m.add(mi = new JMenuItem("-"));
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Cut")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Copy")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Paste")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
	}
}

//メニュー動作
class TEMenuListener implements ActionListener {
	@Override
	public void actionPerformed (ActionEvent e) {
		String in_cmd = e.getActionCommand();
		String cmd = PCARDFrame.pc.intl.getEngText(in_cmd);
		TextArea area = TextEditor.TextEditor.area;
		if(cmd.equals("Cut")){
			area.cut();
		}
		if(cmd.equals("Copy")){
			area.copy();
		}
		if(cmd.equals("Paste")){
			area.paste();
		}
		if(cmd.equals("Select All")){
			area.selectAll();
		}
		if(cmd.equals("Undo")){
			area.undo.undo();
			area.undoStatus();
		}
		if(cmd.equals("Redo")){
			area.undo.redo();
			area.undoStatus();
		}
	}
}

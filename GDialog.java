import java.awt.*;
import java.awt.event.*;
import java.io.File;

import javax.swing.*;

public class GDialog extends JDialog implements ActionListener {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String inputText="";
	static JTextField inputArea;
	static JButton defaultButton;
	
	GDialog(Frame owner, String text, String input, String b1, String b2, String b3) {
		super(owner,true);
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		getContentPane().add("North",topPanel);

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
		topPanel.add(area);

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
		if(owner!=null){
			setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-h/2,400,h+48);
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
		
		setVisible(true);
		
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		if(inputArea != null) inputText = inputArea.getText();
		inputArea = null;
		this.dispose();
	}
}




class GScrollDialog extends JDialog implements ActionListener {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String inputText="";
	static JTextField inputArea;
	static JButton defaultButton;
	
	GScrollDialog(Frame owner, String text, String input, String b1, String b2, String b3) {
		super(owner,true);
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
		if(owner!=null){
			setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-h/2,400,h+48);
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
		
		setVisible(true);
		
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		if(inputArea != null) inputText = inputArea.getText();
		inputArea = null;
		this.dispose();
	}
}










class GUxAnswer extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String inputText="";
	static JTextField inputArea;
	static JButton defaultButton;
	
	GUxAnswer(Frame owner, String text, String input, int[] icons, int iconwait, int time, String[] buttons) {
		super(owner,true);
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);
		JPanel topPanel = new JPanel();
		//topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		getContentPane().add("North",topPanel);

		JPanel topPanel2 = new JPanel();
		topPanel.add(topPanel2);
		
		//アイコン読み込み
		String fileName = PCARD.pc.stack.rsrc.getFilePathAll(icons[0], "icon");
		if(fileName!=null){
			String path = (PCARD.pc.stack.file.getParent()+File.separatorChar+fileName);
		
			JLabel label = new JLabel(new ImageIcon(path));
			topPanel2.add(label);
		}
		
		//テキスト
		String[] texts2 = text.split("\r");
		String text2 = "";
		for(int i=0; i<texts2.length; i++){
			text2 += texts2[i]+"\n";
		}
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
				JButton btn = new JButton(buttons[i]);
				btn.addActionListener(this);
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
		if(owner!=null){
			setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-h/2,400,h+48);
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
		
		setVisible(true);
		
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		if(inputArea != null) inputText = inputArea.getText();
		inputArea = null;
		this.dispose();
	}
}
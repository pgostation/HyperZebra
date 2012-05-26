import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

//情報ダイアログに関するコード

//   ツールそのものの機能はGUI.javaのButtonGUIなどにあります



public class AuthDialog extends JDialog
{
		/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static AuthDialog authDialog;
	static OObject object;

	private AuthDialog(Frame owner, String type, OObject obj) {
		super(owner,false);
		
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				authDialog = null;
			}
		});
	}
	
	static void openAuthDialog(Frame owner, String type, OObject obj) {
		if(PCARD.pc.stack==null || PCARD.pc.stack.curCard==null){
			return;
		}
		
		boolean newOpen = false;
		if(authDialog==null){
			newOpen = true;
			authDialog = new AuthDialog(owner, type, obj);
		}
		else{
			authDialog.getContentPane().removeAll();
		}
		
		object = obj;

		//getContentPane().setLayout(new GridLayout(6, 1));
		authDialog.getContentPane().setLayout(new FlowLayout());

		JLabel label;
		JTextField jfield;
		JCheckBox check;
		JComboBox popup;
		JButton jbtn;

		int w=320;
		int h=440;
		
		//button
		if(type.equals("button")){
			OButton obtn = (OButton)obj;
			AuthButtonListener btnlistener = new AuthButtonListener();
			
			//パネルを追加する
			{
				JPanel namePanel = new JPanel();
				authDialog.getContentPane().add(namePanel);
				
				label = new JLabel(PCARD.pc.intl.getDialogText("Button Name:"));
				namePanel.add(label);
				jfield = new JTextField(obtn.name);
				jfield.setName("name");
				jfield.setPreferredSize(new Dimension(w*2/3, jfield.getPreferredSize().height));
				AuthTextListener listener = new AuthTextListener(jfield);
				jfield.getDocument().addDocumentListener(listener);
				namePanel.add(jfield);
				
				namePanel.setPreferredSize(new Dimension(w, jfield.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel infoPanel = new JPanel();
				infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(infoPanel);
				
				String typeStr = "";
				if(obtn.parent.objectType.equals("card")) typeStr = PCARD.pc.intl.getDialogText("Card button ");
				else typeStr = PCARD.pc.intl.getDialogText("Background button ");
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("ID: ")+obtn.id);
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Number: ")+((OCardBase) obtn.parent).GetNumberof(obtn));
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Part Number: ")+((OCardBase) obtn.parent).GetNumberofParts(obtn));
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				
				infoPanel.setPreferredSize(new Dimension(w, 24*3));
			}
	
			//パネルを追加する
			{
				JPanel stylePanel = new JPanel();
				stylePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(stylePanel);
		
				label = new JLabel(PCARD.pc.intl.getDialogText("Style:"));
				stylePanel.add(label);
				popup = new JComboBox(new String[]{
						PCARD.pc.intl.getDialogText("Standard"),
						PCARD.pc.intl.getDialogText("Transparent"),
						PCARD.pc.intl.getDialogText("Opaque"),
						PCARD.pc.intl.getDialogText("Rectangle"),
						PCARD.pc.intl.getDialogText("Shadow"),
						PCARD.pc.intl.getDialogText("RoundRect"),
						PCARD.pc.intl.getDialogText("Default"),
						PCARD.pc.intl.getDialogText("Oval"),
						PCARD.pc.intl.getDialogText("Popup"),
						PCARD.pc.intl.getDialogText("CheckBox"),
						PCARD.pc.intl.getDialogText("Radio")});
				popup.setSelectedIndex(obtn.style);
				popup.setName("style");
				popup.setMaximumRowCount(17);
				popup.addActionListener(btnlistener);
				stylePanel.add(popup);
				
				stylePanel.setPreferredSize(new Dimension(w, popup.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel famiPanel = new JPanel();
				famiPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(famiPanel);
	
				label = new JLabel(PCARD.pc.intl.getDialogText("Family:"));
				famiPanel.add(label);
				popup = new JComboBox(new String[]{
						PCARD.pc.intl.getDialogText("None"),"1","2","3",
						"4","5","6","7","8","9","10","11","12","13","14","15","16"});
				popup.setSelectedIndex(obtn.group);
				popup.setName("family");
				popup.setMaximumRowCount(17);
				popup.addActionListener(btnlistener);
				famiPanel.add(popup);
				
				famiPanel.setPreferredSize(new Dimension(w, popup.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel propPanel = new JPanel();
				propPanel.setLayout(new GridLayout(3,2));
				authDialog.getContentPane().add(propPanel);
		
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Show Name"));
				check.setSelected(obtn.showName);
				check.addActionListener(btnlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Enabled"));
				check.setSelected(obtn.enabled);
				check.addActionListener(btnlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Visible"));
				check.setSelected(obtn.getVisible());
				check.addActionListener(btnlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Scale Icon"));
				check.setSelected(obtn.getScaleIcon());
				check.addActionListener(btnlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Auto Hilite"));
				check.setSelected(obtn.getAutoHilite());
				check.addActionListener(btnlistener);
				propPanel.add(check);
				if(obtn.parent.objectType.equals("background")){
					check = new JCheckBox(PCARD.pc.intl.getDialogText("Shared Hilite"));
					check.setSelected(obtn.sharedHilite);
					check.addActionListener(btnlistener);
					propPanel.add(check);
				}
				
				propPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*3));
			}
	
			//パネルを追加する
			/*{
				JPanel emptyPanel = new JPanel();
				authDialog.getContentPane().add(emptyPanel);
				
				emptyPanel.setPreferredSize(new Dimension(w, 8));
			}*/
			
			//パネルを追加する
			{
				JPanel colorPanel = new JPanel();
				authDialog.getContentPane().add(colorPanel);


				CPButton fore = new AuthColorButton(obtn.color,obtn,0);
				colorPanel.add(fore);
				CPButton back = new AuthColorButton(obtn.bgColor,obtn,1);
				colorPanel.add(back);
				
				colorPanel.setPreferredSize(new Dimension(w, 32));
			}
			
			//パネルを追加する
			{
				JPanel buttonPanel = new JPanel();
				buttonPanel.setLayout(new GridLayout(3,2));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Font…"));
				jbtn.addActionListener(btnlistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Icon…"));
				jbtn.addActionListener(btnlistener);
				buttonPanel.add(jbtn);
				/*jbtn = new JButton(PCARD.pc.intl.getDialogText("LinkTo…"));
				jbtn.addActionListener(btnlistener);
				jbtn.setEnabled(false);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Effect…"));
				jbtn.addActionListener(btnlistener);
				jbtn.setEnabled(false);
				buttonPanel.add(jbtn);*/
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.addActionListener(btnlistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Content…"));
				jbtn.addActionListener(btnlistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*3+8));
			}
		}

		//field
		if(type.equals("field")){
			OField ofld = (OField)obj;

			JCheckBox multiplelinesbtn;
			JCheckBox fixedlineheightbtn;
			multiplelinesbtn = new JCheckBox(PCARD.pc.intl.getDialogText("Multiple lines"));
			fixedlineheightbtn = new JCheckBox(PCARD.pc.intl.getDialogText("Fixed line height"));
			
			AuthFieldListener fldlistener = new AuthFieldListener(multiplelinesbtn, fixedlineheightbtn);

			//パネルを追加する
			{
				JPanel namePanel = new JPanel();
				authDialog.getContentPane().add(namePanel);
				
				label = new JLabel(PCARD.pc.intl.getDialogText("Field Name:"));
				namePanel.add(label);
				jfield = new JTextField(ofld.name);
				jfield.setName("name");
				jfield.setPreferredSize(new Dimension(w*2/3, jfield.getPreferredSize().height));
				AuthTextListener listener = new AuthTextListener(jfield);
				jfield.getDocument().addDocumentListener(listener);
				namePanel.add(jfield);
				
				namePanel.setPreferredSize(new Dimension(w, jfield.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel infoPanel = new JPanel();
				infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(infoPanel);
				
				String typeStr = "";
				if(ofld.parent.objectType.equals("card")) typeStr = PCARD.pc.intl.getDialogText("Card field ");
				else typeStr = PCARD.pc.intl.getDialogText("Background field ");
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("ID: ")+ofld.id);
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Number: ")+((OCardBase) ofld.parent).GetNumberof(ofld));
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Part Number: ")+((OCardBase) ofld.parent).GetNumberofParts(ofld));
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				
				infoPanel.setPreferredSize(new Dimension(w, 24*3));
			}
	
			//パネルを追加する
			{
				JPanel stylePanel = new JPanel();
				stylePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(stylePanel);
		
				label = new JLabel(PCARD.pc.intl.getDialogText("Style:"));
				stylePanel.add(label);
				popup = new JComboBox(new String[]{
						//PCARD.pc.intl.getDialogText("Standard"),
						PCARD.pc.intl.getDialogText("Transparent"),
						PCARD.pc.intl.getDialogText("Opaque"),
						PCARD.pc.intl.getDialogText("Rectangle"),
						PCARD.pc.intl.getDialogText("Shadow"),
						PCARD.pc.intl.getDialogText("Scroll")});
				popup.setSelectedIndex(ofld.style-1);
				popup.setName("style");
				popup.setMaximumRowCount(17);
				popup.addActionListener(fldlistener);
				stylePanel.add(popup);
				
				stylePanel.setPreferredSize(new Dimension(w, popup.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel propPanel = new JPanel();
				propPanel.setLayout(new GridLayout(6,2));
				authDialog.getContentPane().add(propPanel);
		
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Locked text"));
				check.setSelected(ofld.enabled);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Visible"));
				check.setSelected(ofld.getVisible());
				check.addActionListener(fldlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Don't wrap"));
				check.setSelected(ofld.dontWrap);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Auto select"));
				check.setSelected(ofld.autoSelect);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				//check = new JCheckBox(PCARD.pc.intl.getDialogText("Multiple lines"));
				multiplelinesbtn.setEnabled(ofld.autoSelect);
				multiplelinesbtn.setSelected(ofld.multipleLines);
				multiplelinesbtn.addActionListener(fldlistener);
				propPanel.add(multiplelinesbtn);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Wide margins"));
				check.setSelected(ofld.wideMargins);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				//check = new JCheckBox(PCARD.pc.intl.getDialogText("Fixed line height"));
				fixedlineheightbtn.setSelected(ofld.fixedLineHeight);
				fixedlineheightbtn.addActionListener(fldlistener);
				propPanel.add(fixedlineheightbtn);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Show lines"));
				check.setSelected(ofld.showLines);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Auto tab"));
				check.setSelected(ofld.autoTab);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Don't search"));
				check.setSelected(ofld.dontSearch);
				check.addActionListener(fldlistener);
				propPanel.add(check);
				if(ofld.parent.objectType.equals("background")){
					check = new JCheckBox(PCARD.pc.intl.getDialogText("Shared text"));
					check.setSelected(ofld.sharedText);
					check.addActionListener(fldlistener);
					propPanel.add(check);
				}
				
				propPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*5));
			}
	
			//パネルを追加する
			/*{
				JPanel emptyPanel = new JPanel();
				authDialog.getContentPane().add(emptyPanel);
				
				emptyPanel.setPreferredSize(new Dimension(w, 8));
			}*/
			//パネルを追加する
			{
				JPanel colorPanel = new JPanel();
				authDialog.getContentPane().add(colorPanel);

				
				CPButton fore = new AuthColorButton(ofld.color,ofld,0);
				colorPanel.add(fore);
				CPButton back = new AuthColorButton(ofld.bgColor,ofld,1);
				colorPanel.add(back);
				
				colorPanel.setPreferredSize(new Dimension(w, 32));
			}
			
			//パネルを追加する
			{
				JPanel buttonPanel = new JPanel();
				buttonPanel.setLayout(new GridLayout(1,2));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Font…"));
				jbtn.addActionListener(fldlistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.addActionListener(fldlistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*1+4));
			}
		}

		//card
		if(type.equals("card")){
			OCard ocard = (OCard)obj;
			AuthCardListener cardlistener = new AuthCardListener();
			
			//パネルを追加する
			{
				JPanel namePanel = new JPanel();
				authDialog.getContentPane().add(namePanel);
				
				label = new JLabel(PCARD.pc.intl.getDialogText("Card Name:"));
				namePanel.add(label);
				jfield = new JTextField(ocard.name);
				jfield.setName("name");
				jfield.setPreferredSize(new Dimension(w*2/3, jfield.getPreferredSize().height));
				AuthTextListener listener = new AuthTextListener(jfield);
				jfield.getDocument().addDocumentListener(listener);
				namePanel.add(jfield);
				
				namePanel.setPreferredSize(new Dimension(w, jfield.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel infoPanel = new JPanel();
				infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(infoPanel);
				
				String typeStr = "";
				typeStr = PCARD.pc.intl.getDialogText("Card ");
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("ID: ")+ocard.id);
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Number: ")+ocard.stack.GetNumberof(ocard) 
						+ " / " + (ocard.stack.GetCardListSize()));
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				
				infoPanel.setPreferredSize(new Dimension(w, 24*2));
			}
			
			//パネルを追加する
			{
				JPanel propPanel = new JPanel();
				propPanel.setLayout(new GridLayout(4,1));
				authDialog.getContentPane().add(propPanel);
		
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Show picture"));
				check.setSelected(ocard.showPict);
				check.addActionListener(cardlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Marked"));
				check.setSelected(ocard.marked);
				check.addActionListener(cardlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Don't search"));
				check.setSelected(ocard.dontSearch);
				check.addActionListener(cardlistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Can't delete"));
				check.setSelected(ocard.cantDelete);
				check.addActionListener(cardlistener);
				propPanel.add(check);
				
				propPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*4));
			}
			
			//パネルを追加する
			{
				JPanel buttonPanel = new JPanel();
				//buttonPanel.setLayout(new GridLayout(1,1));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.addActionListener(cardlistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, jbtn.getPreferredSize().height*1+8));
			}
		}

		//background
		if(type.equals("background")){
			OBackground obkgnd = (OBackground)obj;
			AuthBgListener bglistener = new AuthBgListener();
			
			//パネルを追加する
			{
				JPanel namePanel = new JPanel();
				authDialog.getContentPane().add(namePanel);
				
				label = new JLabel(PCARD.pc.intl.getDialogText("Background Name:"));
				namePanel.add(label);
				jfield = new JTextField(obkgnd.name);
				jfield.setName("name");
				jfield.setPreferredSize(new Dimension(w*1/2, jfield.getPreferredSize().height));
				AuthTextListener listener = new AuthTextListener(jfield);
				jfield.getDocument().addDocumentListener(listener);
				namePanel.add(jfield);
				
				namePanel.setPreferredSize(new Dimension(w, jfield.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel infoPanel = new JPanel();
				infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(infoPanel);
				
				String typeStr = "";
				typeStr = PCARD.pc.intl.getDialogText("Background ");
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("ID: ")+obkgnd.id);
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Number: ")+obkgnd.stack.GetNumberof(obkgnd) 
						+ " / " + (obkgnd.stack.GetBgListSize()));
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				
				infoPanel.setPreferredSize(new Dimension(w, 24*2));
			}
			
			//パネルを追加する
			{
				JPanel propPanel = new JPanel();
				propPanel.setLayout(new GridLayout(3,1));
				authDialog.getContentPane().add(propPanel);
		
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Show picture"));
				check.setSelected(obkgnd.showPict);
				check.addActionListener(bglistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Don't search"));
				check.setSelected(obkgnd.dontSearch);
				check.addActionListener(bglistener);
				propPanel.add(check);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Can't delete"));
				check.setSelected(obkgnd.cantDelete);
				check.addActionListener(bglistener);
				propPanel.add(check);
				
				propPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*3));
			}
			
			//パネルを追加する
			{
				JPanel buttonPanel = new JPanel();
				//buttonPanel.setLayout(new GridLayout(1,1));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.addActionListener(bglistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, jbtn.getPreferredSize().height*1+8));
			}
		}

		//stack
		if(type.equals("stack")){
			OStack ostack = (OStack)obj;
			AuthStackListener stacklistener = new AuthStackListener();
			
			//パネルを追加する
			{
				JPanel namePanel = new JPanel();
				authDialog.getContentPane().add(namePanel);
				
				label = new JLabel(PCARD.pc.intl.getDialogText("Stack Name:"));
				namePanel.add(label);
				jfield = new JTextField(ostack.name);
				jfield.setName("name");
				jfield.setPreferredSize(new Dimension(w*2/3, jfield.getPreferredSize().height));
				AuthTextListener listener = new AuthTextListener(jfield);
				jfield.getDocument().addDocumentListener(listener);
				namePanel.add(jfield);
				
				namePanel.setPreferredSize(new Dimension(w, jfield.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel infoPanel = new JPanel();
				infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(infoPanel);
				
				JTextArea area = new JTextArea(PCARD.pc.intl.getDialogText("Stack Path: ")+ostack.file.getPath());
				area.setPreferredSize(new Dimension(w, 64));
				area.setLineWrap(true);
				infoPanel.add(area);
				
				infoPanel.setPreferredSize(new Dimension(w, 64*1));
			}
			
			//パネルを追加する
			{
				JPanel buttonPanel = new JPanel();
				buttonPanel.setLayout(new GridLayout(1,2));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.addActionListener(stacklistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Size…"));
				jbtn.addActionListener(stacklistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Card List…"));
				jbtn.addActionListener(stacklistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, 24+8));
			}
		}

		if(newOpen){
			int left = 0;
			if(obj.left+obj.width/2 > owner.getWidth()/2){
				left = owner.getX() +obj.left +PCARD.pc.toolbar.getTWidth() -w;
			}
			else{
				left = owner.getX() +obj.left +PCARD.pc.toolbar.getTWidth() +obj.width;
			}
			authDialog.setBounds(left, owner.getY()+owner.getHeight()/2-h/2 ,w,h);
		}
		
		authDialog.getContentPane().repaint();
		authDialog.setVisible(true);
	}

	
	static class AuthButtonListener implements ActionListener
	{
		public void actionPerformed(ActionEvent e) {
			String in_cmd = e.getActionCommand();
			if(in_cmd.equals("comboBoxChanged")){
				in_cmd = ((JComboBox)e.getSource()).getName();
			}
			String cmd = PCARD.pc.intl.getDialogEngText(in_cmd);
			
			if(cmd.equals("style")){
				if(object.objectType.equals("button")){
					((OButton)object).style = ((JComboBox)e.getSource()).getSelectedIndex();
					
					OCard.reloadCurrentCard();
				}
			}
			else if(cmd.equals("family")){
				if(object.objectType.equals("button")){
					if(((OButton)object).group >= 1 && ((OButton)object).radio!=null){
						OButton.btnGroup[((OButton)object).group-1].remove(((OButton)object).radio);
					}
					((OButton)object).group = ((JComboBox)e.getSource()).getSelectedIndex();
					if(((OButton)object).group >= 1 && ((OButton)object).radio!=null){
						OButton.btnGroup[((OButton)object).group-1].add(((OButton)object).radio);
					}
				}
			}
			else if(cmd.equals("Show Name")){
				if(object.objectType.equals("button")){
					((OButton)object).setShowName(((JCheckBox)e.getSource()).isSelected());
				}
			}
			else if(cmd.equals("Enabled")){
				if(object.objectType.equals("button")){
					((OButton)object).setEnabled(((JCheckBox)e.getSource()).isSelected());
				}
			}
			else if(cmd.equals("Visible")){
				if(object.objectType.equals("button")){
					((OButton)object).setVisible(((JCheckBox)e.getSource()).isSelected());
				}
			}
			else if(cmd.equals("Auto Hilite")){
				if(object.objectType.equals("button")){
					((OButton)object).setAutoHilite(((JCheckBox)e.getSource()).isSelected());
				}
			}
			else if(cmd.equals("Shared Hilite")){
				if(object.objectType.equals("button")){
					((OButton)object).sharedHilite = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Scale Icon")){
				if(object.objectType.equals("button")){
					((OButton)object).setScaleIcon(((JCheckBox)e.getSource()).isSelected());
					((OButton)object).setIcon(((OButton)object).icon);
				}
			}
			else if(cmd.equals("Font…")){
				new GFontDialog(PCARD.pc, ((OButton)object).textFont, ((OButton)object).textSize, ((OButton)object).textStyle, ((OButton)object).textAlign);
				((OButton)object).textSize = GFontDialog.selectedSize;
				((OButton)object).textStyle = GFontDialog.selectedStyle;
				((OButton)object).textAlign = GFontDialog.selectedAlign;
				((OButton)object).setTextFont(GFontDialog.selectedFont);
			}
			else if(cmd.equals("Icon…")){
				new ResEdit(PCARD.pc, "icon", object);
			}
			else if(cmd.equals("LinkTo…")){
				
			}
			else if(cmd.equals("Effect…")){
				
			}
			else if(cmd.equals("Script…")){
				ScriptEditor.openScriptEditor(PCARD.pc, object);
			}
			else if(cmd.equals("Content…")){
				new TextEditor(PCARD.pc, object);
			}
			else{
				System.out.println("AuthTool AuthButtonListener actionPerformed Error!!");
			}
			
			if(object.getClass()==OStack.class){
				((OStack)object).changed = true;
			}
			else if(object.getClass()==OCard.class || object.getClass()==OBackground.class){
				((OCardBase)object).changed = true;
			}
			else if(object.getClass()==OButton.class || object.getClass()==OField.class){
				((OCardBase)object.parent).changed = true;
			}
		}
	}

	static class AuthFieldListener implements ActionListener
	{
		JCheckBox multiplelinesbtn;
		JCheckBox fixedlineheightbtn;
		
		AuthFieldListener(JCheckBox multiplelinesbtn, JCheckBox fixedlineheightbtn){
			this.multiplelinesbtn = multiplelinesbtn;
			this.fixedlineheightbtn = fixedlineheightbtn;
		}
		
		public void actionPerformed(ActionEvent e) {
			String in_cmd = e.getActionCommand();
			if(in_cmd.equals("comboBoxChanged")){
				in_cmd = ((JComboBox)e.getSource()).getName();
			}
			String cmd = PCARD.pc.intl.getDialogEngText(in_cmd);
			
			if(cmd.equals("style")){
				if(object.objectType.equals("field")){
					((OField)object).style = ((JComboBox)e.getSource()).getSelectedIndex()+1;
					
					OCard.reloadCurrentCard();
				}
			}
			else if(cmd.equals("Visible")){
				if(object.objectType.equals("field")){
					((OField)object).setVisible(((JCheckBox)e.getSource()).isSelected());
				}
			}
			else if(cmd.equals("Locked text")){
				if(object.objectType.equals("field")){
					((OField)object).enabled = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Don't wrap")){
				if(object.objectType.equals("field")){
					((OField)object).dontWrap = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Auto select")){
				if(object.objectType.equals("field")){
					((OField)object).autoSelect = ((JCheckBox)e.getSource()).isSelected();
					multiplelinesbtn.setEnabled(((OField)object).autoSelect);
					if(((OField)object).autoSelect){
						fixedlineheightbtn.setEnabled(false);
						fixedlineheightbtn.setSelected(true);
					}else{
						fixedlineheightbtn.setEnabled(true);
					}
				}
			}
			else if(cmd.equals("Multiple lines")){
				if(object.objectType.equals("field")){
					((OField)object).multipleLines = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Wide margins")){
				if(object.objectType.equals("field")){
					((OField)object).wideMargins = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Fixed line height")){
				if(object.objectType.equals("field")){
					((OField)object).fixedLineHeight = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Show lines")){
				if(object.objectType.equals("field")){
					((OField)object).showLines = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Auto tab")){
				if(object.objectType.equals("field")){
					((OField)object).autoTab = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Don't search")){
				if(object.objectType.equals("field")){
					((OField)object).dontSearch = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Shared Text")){
				if(object.objectType.equals("field")){
					((OField)object).sharedText = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Font…")){
				new GFontDialog(PCARD.pc, ((OField)object).textFont, ((OField)object).textSize, ((OField)object).textStyle, ((OField)object).textAlign);
				((OField)object).textSize = GFontDialog.selectedSize;
				((OField)object).textStyle = GFontDialog.selectedStyle;
				((OField)object).textAlign = GFontDialog.selectedAlign;
				((OField)object).setTextFont(GFontDialog.selectedFont);
			}
			else if(cmd.equals("Script…")){
				ScriptEditor.openScriptEditor(PCARD.pc, object);
			}
			else{
				System.out.println("AuthTool AuthFieldListener actionPerformed Error!!");
			}
			
			if(((OField)object).getComponent()!=null){
				((OField)object).getComponent().paintImmediately(((OField)object).getComponent().getBounds());
			}
		}
	}

	static class AuthCardListener implements ActionListener
	{
		public void actionPerformed(ActionEvent e) {
			String in_cmd = e.getActionCommand();
			if(in_cmd.equals("comboBoxChanged")){
				in_cmd = ((JComboBox)e.getSource()).getName();
			}
			String cmd = PCARD.pc.intl.getDialogEngText(in_cmd);
			
			if(cmd.equals("Show picture")){
				if(object.objectType.equals("card")){
					((OCard)object).showPict = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Don't search")){
				if(object.objectType.equals("card")){
					((OCard)object).dontSearch = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Can't delete")){
				if(object.objectType.equals("card")){
					((OCard)object).cantDelete = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Marked")){
				if(object.objectType.equals("card")){
					((OCard)object).marked = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Script…")){
				ScriptEditor.openScriptEditor(PCARD.pc, object);
			}
			else{
				System.out.println("AuthTool AuthCardListener actionPerformed Error!!");
			}
			
			if(((OCard)object).label!=null){
				((OCard)object).label.paintImmediately(((OCard)object).label.getBounds());
			}
		}
	}

	static class AuthBgListener implements ActionListener
	{
		public void actionPerformed(ActionEvent e) {
			String in_cmd = e.getActionCommand();
			if(in_cmd.equals("comboBoxChanged")){
				in_cmd = ((JComboBox)e.getSource()).getName();
			}
			String cmd = PCARD.pc.intl.getDialogEngText(in_cmd);
			
			if(cmd.equals("Show picture")){
				if(object.objectType.equals("background")){
					((OBackground)object).showPict = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Can't delete")){
				if(object.objectType.equals("background")){
					((OBackground)object).cantDelete = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Don't search")){
				if(object.objectType.equals("card")){
					((OBackground)object).dontSearch = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Script…")){
				ScriptEditor.openScriptEditor(PCARD.pc, object);
			}
			else{
				System.out.println("AuthTool AuthBgListener actionPerformed Error!!");
			}
			
			if(((OBackground)object).label!=null){
				((OBackground)object).label.paintImmediately(((OBackground)object).label.getBounds());
			}
		}
	}
	
	static class AuthStackListener implements ActionListener
	{
		public void actionPerformed(ActionEvent e) {
			String in_cmd = e.getActionCommand();
			if(in_cmd.equals("comboBoxChanged")){
				in_cmd = ((JComboBox)e.getSource()).getName();
			}
			String cmd = PCARD.pc.intl.getDialogEngText(in_cmd);

			if(cmd.equals("Script…")){
				ScriptEditor.openScriptEditor(PCARD.pc, object);
			}
			else if(cmd.equals("Size…")){
				new SizeDialog(AuthDialog.authDialog);
			}
			else if(cmd.equals("Card List…")){
				String str = "";
				for(int i=0;i<PCARD.pc.stack.cardIdList.size();i++){
					int id = PCARD.pc.stack.cardIdList.get(i);
					OCard card = PCARD.pc.stack.GetCardbyId(id);
					str += "card("+ i +") id:"+ id +" name:"+card.name+" btns:"+card.btnList.size()+" flds:"+card.fldList.size()+"\n";
				}
				new GScrollDialog(null, str, null, null, null, "OK");
			}
			else{
				System.out.println("AuthTool AuthStackListener actionPerformed Error!!");
			}
		}
	}
	
	static class AuthTextListener implements DocumentListener
	{
		JTextField jfield;
		
		public AuthTextListener(JTextField fld){
			jfield = fld;
		}
		
		public void changedUpdate(DocumentEvent e) {
			if(jfield.getName().equals("name")){
				if(object.objectType.equals("button")){
					((OButton)object).setName(jfield.getText());
				}else{
					object.name = jfield.getText();
				}
			}
		}

		public void insertUpdate(DocumentEvent e) {
			changedUpdate(e);
		}

		public void removeUpdate(DocumentEvent e) {
			changedUpdate(e);
		}
	}
	

	static class SizeDialog extends JDialog implements ActionListener {
		private static final long serialVersionUID = 1L;
		JTextField widthField;
		JTextField heightField;
		JButton defaultButton;
		
		SizeDialog(JDialog owner) {
			super(owner, true);
			getContentPane().setLayout(new BorderLayout());

			//パネルを追加する
			JPanel topPanel = new JPanel();
			topPanel.setLayout(new GridLayout(2,1));
			topPanel.setPreferredSize(new Dimension(200,80));
			getContentPane().add("Center",topPanel);
			
			{
				JPanel panel = new JPanel();
				panel.setLayout(new FlowLayout());
				//panel.setPreferredSize(new Dimension(320,32));
				
				JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Width:"));
				label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
				panel.add(label);
				
				JTextField area1 = new JTextField(""+PCARD.pc.stack.width);
				area1.setPreferredSize(new Dimension(64, area1.getPreferredSize().height));
				panel.add(area1);
				widthField = area1;
				topPanel.add(panel);
			}
			{
				JPanel panel = new JPanel();
				//panel.setPreferredSize(new Dimension(320,32));
				
				JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Height:"));
				label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
				panel.add(label);
				JTextField area2 = new JTextField(""+PCARD.pc.stack.height);
				area2.setPreferredSize(new Dimension(64, area2.getPreferredSize().height));
				panel.add(area2);
				heightField = area2;
				topPanel.add(panel);
			}
			
			//パネルを追加する
			JPanel btmPanel = new JPanel();
			getContentPane().add("South",btmPanel);

			{
				JButton btn1 = new JButton("Cancel");
				btn1.addActionListener(this);
				btmPanel.add(btn1);
			}
			
			{
				JButton btn2 = new JButton("OK");
				btn2.addActionListener(this);
				btmPanel.add(btn2);
				getRootPane().setDefaultButton(btn2);
				defaultButton = btn2;
			}

			setBounds(owner.getX()+owner.getWidth()/2-120,owner.getY()+owner.getHeight()/2-120,240,160);
			setUndecorated(true);//タイトルバー非表示
			
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					if(defaultButton!=null) defaultButton.requestFocus();
				}
			});
			
			setVisible(true);
			
		}
		
		public void actionPerformed(ActionEvent e) {
			if(e.getActionCommand().equals("OK")){
				int width = 0;
				int height = 0;
				try{
					width = Integer.valueOf(widthField.getText());
					height = Integer.valueOf(heightField.getText());
				}catch(Exception e2){
					
				}
				if(width<=32 || height <=32 || width*height >= 4000*3000){
		    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Illegal size.")
		    				,null,"OK",null,null);
				}
				else
				{
					PCARD.pc.stack.width = width;
					PCARD.pc.stack.height = height;
					Rectangle r = PCARD.pc.getBounds();
					PCARD.pc.setBounds(r.x, r.y, width, height + PCARD.pc.getInsets().top);
					PCARD.pc.mainPane.setBounds(0, 0, width, height);
				}
			}
			this.dispose();
		}
	}
}

class AuthColorButton extends CPButton
{
	private static final long serialVersionUID = 3564756897317769905L;

	int type;
	OObject obj;
	
	AuthColorButton(Color in_color, OObject obj, int type){
		super(in_color,0,0,false);
		this.obj = obj;
		this.type = type;
	}
	
	@Override
	public void makeIcon(Color col){
		super.makeIcon(col);
		
		if(obj.getClass()==OButton.class){
			if(type==0) ((OButton)obj).setColor(col);
			if(type==1) ((OButton)obj).setBgColor(col);
		}
		else if(obj.getClass()==OField.class){
			if(type==0) ((OField)obj).setColor(col);
			if(type==1) ((OField)obj).setBgColor(col);
		}
		OCard.reloadCurrentCard();
	}
}

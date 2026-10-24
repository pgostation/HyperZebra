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

		//2列のチェックボックスが切れない幅にする(「アイコンの縦横比を維持」は320幅の半分に収まらない)
		int w=Math.max(320, type.equals("button") ? twoColumnWidth("Show Name", "Enabled", "Visible", "Scale Icon", "Preserve Icon Aspect", "Auto Hilite", "Shared Hilite")
				: type.equals("field") ? twoColumnWidth("Locked text", "Visible", "Don't wrap", "Auto select", "Multiple lines", "Wide margins", "Fixed line height",
					"Show lines", "Auto tab", "Don't search", "Shared text") : 0);
		int h=480;
		authDialog.setTitle(PCARD.pc.intl.getDialogText(
				type.equals("button") ? "Button Info" : type.equals("field") ? "Field Info" : type.equals("panel") ? "Panel Info" : type.equals("card") ? "Card Info"
				: type.equals("background") ? "Background Info" : "Stack Info"));
		
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
			addPanelEditor(obtn, w);
			
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
				propPanel.setLayout(new GridLayout(0,2));
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
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Preserve Icon Aspect"));
				check.setSelected(obtn.getIconAspect());
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

				//ハイライト色: オフならHyperCardと同じ反転、オンなら色見本の色で塗る(hiliteColorプロパティ)
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Hilite Color"));
				check.setSelected(obtn.hiliteColor!=null);
				AuthColorButton hilite = new AuthColorButton(obtn.hiliteColor!=null ? obtn.hiliteColor : AuthColorButton.DEFAULT_HILITE, obtn, 2);
				hilite.hiliteCheck = check;
				check.putClientProperty(AuthColorButton.class, hilite);
				check.setToolTipText(PCARD.pc.intl.getDialogText("Off: the button is inverted when hilited, as in HyperCard"));
				check.addActionListener(btnlistener);
				colorPanel.add(javax.swing.Box.createHorizontalStrut(12));
				colorPanel.add(check);
				colorPanel.add(hilite);
				
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
				buttonPanel.add(jbtn);*/
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Effect…")); //視覚効果の確認(GVisualEffectWindow)
				jbtn.addActionListener(btnlistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.setEnabled(StackProtection.effectiveUserLevel()>=5); //スクリプトはユーザーレベル5(スクリプト)だけ(cantPeekはピークだけを止める)
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
			addPanelEditor(ofld, w);
			
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
						PCARD.pc.intl.getDialogText("Scroll"),
						//表: テキストの行とタブ区切りの項目を表として表示する(tableStyle)
						PCARD.pc.intl.getDialogText("Table"),
						//アウトライン: JSONまたはタブで字下げしたテキストを開閉できる木として表示する(outlineStyle)
						PCARD.pc.intl.getDialogText("Outline view")});
				popup.setSelectedIndex(ofld.tableStyle ? 5 : ofld.outlineStyle ? 6 : Math.max(0, ofld.style-1));
				popup.setName("style");
				popup.setMaximumRowCount(17);
				popup.addActionListener(fldlistener);
				stylePanel.add(popup);
				
				stylePanel.setPreferredSize(new Dimension(w, popup.getPreferredSize().height+4));
			}
			
			//パネルを追加する
			{
				JPanel propPanel = new JPanel();
				propPanel.setLayout(new GridLayout(0,2));
				authDialog.getContentPane().add(propPanel);
		
				//"Lock text"だと翻訳表(International)にも判定側(actionPerformed)にも
				//無いキーになり、チェックしても何も起きずエラーが出ていた
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
				//入力できる行数ではなく、リスト(自動的に選択)で選べる行数の設定
				multiplelinesbtn.setToolTipText(PCARD.pc.intl.getDialogText("Lets more than one line be selected in a list field (Auto select, table or outline). It does not limit typing."));
				multiplelinesbtn.setEnabled(ofld.autoSelect || ofld.tableStyle || ofld.outlineStyle);
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
				
				propPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height*((propPanel.getComponentCount()+1)/2)));
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
				jbtn.setEnabled(StackProtection.effectiveUserLevel()>=5); //スクリプトはユーザーレベル5(スクリプト)だけ(cantPeekはピークだけを止める)
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
				buttonPanel.setLayout(new GridLayout(2,2,4,4));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.setEnabled(StackProtection.effectiveUserLevel()>=5); //スクリプトはユーザーレベル5(スクリプト)だけ(cantPeekはピークだけを止める)
				jbtn.addActionListener(cardlistener);
				buttonPanel.add(jbtn);
				buttonPanel.add(new JLabel());
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Button List…"));
				jbtn.addActionListener(cardlistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Field List…"));
				jbtn.addActionListener(cardlistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, jbtn.getPreferredSize().height*2+12));
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
				buttonPanel.setLayout(new GridLayout(2,2,4,4));
				authDialog.getContentPane().add(buttonPanel);
	
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.setEnabled(StackProtection.effectiveUserLevel()>=5); //スクリプトはユーザーレベル5(スクリプト)だけ(cantPeekはピークだけを止める)
				jbtn.addActionListener(bglistener);
				buttonPanel.add(jbtn);
				buttonPanel.add(new JLabel());
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Button List…"));
				jbtn.addActionListener(bglistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Field List…"));
				jbtn.addActionListener(bglistener);
				buttonPanel.add(jbtn);
				
				buttonPanel.setPreferredSize(new Dimension(w, jbtn.getPreferredSize().height*2+12));
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
				
				JTextArea area = new JTextArea(PCARD.pc.intl.getDialogText("Stack Path: ")+StackBundle.host(ostack.file).getPath());
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
				jbtn.setEnabled(StackProtection.effectiveUserLevel()>=5); //スクリプトはユーザーレベル5(スクリプト)だけ(cantPeekはピークだけを止める)
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

		//panel: a panel's info, as a button's (name, number and id, visible, contents, data, script)
		if(type.equals("panel")){
			OPanel opanel = (OPanel)obj;
			opanel.getLeft(); //the rectangle round the members, where the window opens beside
			AuthPanelListener panellistener = new AuthPanelListener();
			{
				JPanel namePanel = new JPanel();
				authDialog.getContentPane().add(namePanel);
				label = new JLabel(PCARD.pc.intl.getDialogText("Panel Name:"));
				namePanel.add(label);
				jfield = new JTextField(opanel.name);
				jfield.setName("name");
				jfield.setPreferredSize(new Dimension(w*1/2, jfield.getPreferredSize().height));
				jfield.getDocument().addDocumentListener(new AuthTextListener(jfield));
				namePanel.add(jfield);
				namePanel.setPreferredSize(new Dimension(w, jfield.getPreferredSize().height+4));
			}
			{
				JPanel infoPanel = new JPanel();
				infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
				authDialog.getContentPane().add(infoPanel);
				String typeStr = opanel.parent.objectType.equals("card") ? PCARD.pc.intl.getDialogText("Card panel ") : PCARD.pc.intl.getDialogText("Background panel ");
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("ID: ")+opanel.id);
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				label = new JLabel(typeStr+PCARD.pc.intl.getDialogText("Number: ")+opanel.getNumber());
				label.setPreferredSize(new Dimension(w, label.getPreferredSize().height));
				infoPanel.add(label);
				infoPanel.setPreferredSize(new Dimension(w, 24*2));
			}
			addPanelMembers(opanel, w);
			{
				JPanel propPanel = new JPanel();
				propPanel.setLayout(new GridLayout(1,1));
				authDialog.getContentPane().add(propPanel);
				check = new JCheckBox(PCARD.pc.intl.getDialogText("Visible"));
				check.setSelected(opanel.getVisible());
				check.addActionListener(panellistener);
				propPanel.add(check);
				propPanel.setPreferredSize(new Dimension(w, check.getPreferredSize().height));
			}
			{
				JPanel buttonPanel = new JPanel();
				buttonPanel.setLayout(new GridLayout(2,2,4,4));
				authDialog.getContentPane().add(buttonPanel);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Script…"));
				jbtn.setEnabled(StackProtection.effectiveUserLevel()>=5);
				jbtn.addActionListener(panellistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Content…"));
				jbtn.addActionListener(panellistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Data…"));
				jbtn.addActionListener(panellistener);
				buttonPanel.add(jbtn);
				jbtn = new JButton(PCARD.pc.intl.getDialogText("Delete Panel"));
				jbtn.addActionListener(panellistener);
				buttonPanel.add(jbtn);
				buttonPanel.setPreferredSize(new Dimension(w, jbtn.getPreferredSize().height*2+12));
			}
		}

		//閉じるボタン: 情報ウィンドウの変更はその場でカードに反映され、取り消しは「編集」＞「取り消し」(シェイプ情報と同じ)
		{
			JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			jbtn = new JButton(PCARD.pc.intl.getDialogText("Close"));
			jbtn.addActionListener(e -> closeAuthDialog());
			closePanel.add(jbtn);
			closePanel.setPreferredSize(new Dimension(w, jbtn.getPreferredSize().height+10));
			authDialog.getContentPane().add(closePanel);
			authDialog.getRootPane().setDefaultButton(jbtn);
			authDialog.getRootPane().registerKeyboardAction(e -> closeAuthDialog(),
					javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
		}
		//各パネルは幅wで縦に並ぶ(FlowLayout): その高さの合計に合わせる
		{
			java.awt.FlowLayout flow = (java.awt.FlowLayout)authDialog.getContentPane().getLayout();
			int contentHeight = flow.getVgap();
			for(java.awt.Component c : authDialog.getContentPane().getComponents()) contentHeight += c.getPreferredSize().height + flow.getVgap();
			((JPanel)authDialog.getContentPane()).setPreferredSize(new Dimension(w+2*flow.getHgap(), contentHeight));
			authDialog.pack();
			h = authDialog.getHeight();
		}

		if(newOpen){
			//obj.left/obj.widthは論理座標。owner.getWidth()等は実(物理)座標なので、
			//比較・加算の前にscale()倍して単位を揃える。
			int scaledLeft = PCARD.toPhysical(obj.left);
			int scaledWidth = PCARD.toPhysicalLength(obj.left, obj.width);
			int left = 0;
			if(scaledLeft+scaledWidth/2 > owner.getWidth()/2){
				left = owner.getX() +scaledLeft +PCARD.pc.toolbar.getTWidth() -w;
			}
			else{
				left = owner.getX() +scaledLeft +PCARD.pc.toolbar.getTWidth() +scaledWidth;
			}
			authDialog.setLocation(left, owner.getY()+owner.getHeight()/2-h/2);
		}
		
		authDialog.getContentPane().revalidate();
		authDialog.getContentPane().repaint();
		authDialog.setVisible(true);
	}

	/** The width of the dialog's two check box columns: twice the widest of these labels. */
	private static int twoColumnWidth(String... names){
		int widest = 0;
		for(String name : names) widest = Math.max(widest, new JCheckBox(PCARD.pc.intl.getDialogText(name)).getPreferredSize().width);
		return 2*widest + 8;
	}

	private static void closeAuthDialog(){
		if(authDialog==null) return;
		authDialog.dispose();
		authDialog = null;
	}

	/** Button/Field Info: the panel the part belongs to (a pop-up of the panels of its card or background). */
	private static void addPanelEditor(OObject part, int width){
		JPanel row=new JPanel(new FlowLayout(FlowLayout.LEFT));
		row.add(new JLabel(PCARD.pc.intl.getDialogText("Panel:")));
		JComboBox<PanelChooser.Item> box=PanelChooser.create(part);
		box.addActionListener(e -> {
			AuthoringUndo.Snapshot before=AuthoringUndo.capture();
			try{ part.setPanel(PanelChooser.selected(box)); }
			finally{ AuthoringUndo.commit(before); }
		});
		row.add(box);
		//back to the panel (a member's info opened from the panel's member list replaces Panel Info in this window)
		JButton info=new JButton(International.t("Panel Info…"));
		info.setEnabled(part.panel()!=null);
		box.addActionListener(e -> info.setEnabled(PanelChooser.selected(box)!=null));
		info.addActionListener(e -> {
			OPanel p=part.panel();
			if(p!=null) openAuthDialog(PCARD.pc, "panel", p);
		});
		row.add(info);
		//one line: the pop-up gives up the room the label and the button need
		JLabel title=(JLabel)row.getComponent(0);
		int room=width-title.getPreferredSize().width-info.getPreferredSize().width-4*((FlowLayout)row.getLayout()).getHgap()-4;
		box.setPreferredSize(new Dimension(Math.max(80,Math.min(box.getPreferredSize().width,room)),box.getPreferredSize().height));
		row.setPreferredSize(new Dimension(width,row.getPreferredSize().height+4));
		authDialog.getContentPane().add(row);
	}

	/**
	 * Panel Info: the buttons, fields and shapes in the panel, so that a panel made by Import SVG shows the shapes it holds.
	 * Selecting a shape selects it on the card (with the Shape tool); double-click or Info… opens the member's own info;
	 * Remove from Panel takes it out (it stays on the card).
	 */
	private static void addPanelMembers(OPanel panel, int width){
		java.util.ArrayList<OObject> members=panel.members();
		JPanel box=new JPanel(new BorderLayout(0,4));
		JLabel title=new JLabel(International.t("Members ({0})", members.size()));
		title.setToolTipText(International.t("Choose the panel in Button, Field or Shape Info to put a part into it."));
		box.add(title, BorderLayout.NORTH);
		javax.swing.DefaultListModel<OObject> model=new javax.swing.DefaultListModel<OObject>();
		for(OObject part : members) model.addElement(part);
		javax.swing.JList<OObject> list=new javax.swing.JList<OObject>(model);
		list.setName("members");
		list.setVisibleRowCount(Math.max(3, Math.min(8, members.size())));
		list.setCellRenderer(new javax.swing.DefaultListCellRenderer(){
			private static final long serialVersionUID = 1L;
			@Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> l, Object value, int index, boolean selected, boolean focus){
				super.getListCellRendererComponent(l, PanelChooser.memberLabel((OObject)value), index, selected, focus);
				if(!selected && !((OObject)value).getVisible()) setForeground(Color.GRAY); //hidden on its own
				return this;
			}
		});
		javax.swing.JScrollPane scroll=new javax.swing.JScrollPane(list);
		box.add(scroll, BorderLayout.CENTER);
		JPanel south=new JPanel(new BorderLayout(0,2));
		JLabel hint=new JLabel(International.t("Double-click a member to open its info."));
		hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D()-1f));
		hint.setForeground(Color.GRAY);
		south.add(hint, BorderLayout.NORTH);
		JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
		JButton info=new JButton(International.t("Info…"));
		JButton remove=new JButton(International.t("Remove from Panel"));
		info.setEnabled(false); remove.setEnabled(false);
		buttons.add(info); buttons.add(remove);
		south.add(buttons, BorderLayout.SOUTH);
		box.add(south, BorderLayout.SOUTH);
		list.addListSelectionListener(e -> {
			OObject part=list.getSelectedValue();
			info.setEnabled(part!=null); remove.setEnabled(part!=null);
			if(!e.getValueIsAdjusting() && part instanceof OShape && ShapeTool.active()) ShapeTool.select((OShape)part);
		});
		list.addMouseListener(new java.awt.event.MouseAdapter(){
			@Override public void mouseClicked(java.awt.event.MouseEvent e){
				if(e.getClickCount()==2 && SwingUtilities.isLeftMouseButton(e)){
					int i=list.locationToIndex(e.getPoint());
					if(i>=0 && list.getCellBounds(i,i).contains(e.getPoint())) openMemberInfo(panel, model.get(i));
				}
			}
		});
		info.addActionListener(e -> { if(list.getSelectedValue()!=null) openMemberInfo(panel, list.getSelectedValue()); });
		remove.addActionListener(e -> {
			OObject part=list.getSelectedValue();
			if(part==null) return;
			AuthoringUndo.Snapshot before=AuthoringUndo.capture();
			try{ part.setPanel(null); }
			finally{ AuthoringUndo.commit(before); }
			ShapeTool.refresh();
			openAuthDialog(PCARD.pc, "panel", panel);
		});
		int rowHeight=list.getFixedCellHeight()>0 ? list.getFixedCellHeight() : new JLabel("Xg").getPreferredSize().height+2;
		scroll.setPreferredSize(new Dimension(width, rowHeight*list.getVisibleRowCount()+6));
		box.setPreferredSize(new Dimension(width, box.getPreferredSize().height));
		authDialog.getContentPane().add(box);
	}

	/** A member's own info from Panel Info: Shape Info (then back to Panel Info), or Button or Field Info in this window. */
	static void openMemberInfo(OPanel panel, OObject part){
		if(part instanceof OShape){
			OShape shape=(OShape)part;
			if(ShapeTool.active()) ShapeTool.select(shape);
			if(ShapeEditor.open(shape) && authDialog!=null && object==panel && panel.owner.panelList.contains(panel))
				openAuthDialog(PCARD.pc, "panel", panel); //the member may have been renamed or taken out
		}
		else if(part instanceof OButton) openAuthDialog(PCARD.pc, "button", part);
		else if(part instanceof OField) openAuthDialog(PCARD.pc, "field", part);
	}

	
	static class AuthButtonListener implements ActionListener
	{
		public void actionPerformed(ActionEvent e) {
			AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

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
			else if(cmd.equals("Hilite Color")){
				if(object instanceof OButton){
					JCheckBox box = (JCheckBox)e.getSource();
					AuthColorButton swatch = (AuthColorButton)box.getClientProperty(AuthColorButton.class);
					((OButton)object).setHiliteColor(box.isSelected() && swatch!=null ? swatch.color : null);
				}
			}
			else if(cmd.equals("Shared Hilite")){
				if(object.objectType.equals("button")){
					((OButton)object).sharedHilite = ((JCheckBox)e.getSource()).isSelected();
				}
			}
			else if(cmd.equals("Preserve Icon Aspect")){
				if(object instanceof OButton) ((OButton)object).setIconAspect(((JCheckBox)e.getSource()).isSelected());
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
				new ResEdit(PCARD.pc, "ICON", object);
			}
			else if(cmd.equals("LinkTo…")){
				
			}
			else if(cmd.equals("Effect…")){
				GVisualEffectWindow.open(authDialog);
				return; //見るだけ: スタックは変わらない
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

		} finally { AuthoringUndo.commit(before); }
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
			AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

			String in_cmd = e.getActionCommand();
			if(in_cmd.equals("comboBoxChanged")){
				in_cmd = ((JComboBox)e.getSource()).getName();
			}
			String cmd = PCARD.pc.intl.getDialogEngText(in_cmd);
			
			if(cmd.equals("style")){
				if(object.objectType.equals("field")){
					//透明・不透明・長方形・シャドウ・スクロール・表・アウトライン(表とアウトラインの枠はスクロールと同じ)
					OField f = (OField)object;
					f.setStyleIndex(((JComboBox)e.getSource()).getSelectedIndex());
					multiplelinesbtn.setEnabled(f.autoSelect || f.tableStyle || f.outlineStyle);
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
					//テキストの編集可否へ即座に反映する
					((OField)object).updateTextEditable();
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
					multiplelinesbtn.setEnabled(((OField)object).autoSelect || ((OField)object).tableStyle || ((OField)object).outlineStyle);
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
					((OField)object).refreshTable();
				}
			}
			else if(cmd.equals("Wide margins")){
				if(object.objectType.equals("field")){
					((OField)object).setWideMargins(((JCheckBox)e.getSource()).isSelected());
				}
			}
			else if(cmd.equals("Fixed line height")){
				if(object.objectType.equals("field")){
					((OField)object).fixedLineHeight = ((JCheckBox)e.getSource()).isSelected();
					if(((OField)object).fld!=null) ((OField)object).fld.repaint();
				}
			}
			else if(cmd.equals("Show lines")){
				if(object.objectType.equals("field")){
					((OField)object).showLines = ((JCheckBox)e.getSource()).isSelected();
					if(((OField)object).fld!=null) ((OField)object).fld.repaint();
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
			//チェックボックスのラベルも翻訳表も"Shared text"なので、
			//"Shared Text"(大文字T)では一致せず、こちらも無反応だった
			else if(cmd.equals("Shared text")){
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

		} finally { AuthoringUndo.commit(before); }
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
			else if(cmd.equals("Button List…")){
				showPartList((OCard)object, true);
			}
			else if(cmd.equals("Field List…")){
				showPartList((OCard)object, false);
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
			else if(cmd.equals("Button List…")){
				showPartList((OBackground)object, true);
			}
			else if(cmd.equals("Field List…")){
				showPartList((OBackground)object, false);
			}
			else{
				System.out.println("AuthTool AuthBgListener actionPerformed Error!!");
			}
			
			if(((OBackground)object).label!=null){
				((OBackground)object).label.paintImmediately(((OBackground)object).label.getBounds());
			}
		}
	}

	private static void showPartList(OCardBase owner, boolean buttons) {
		String text = buildPartListText(owner, buttons, PCARD.pc.intl);
		new GScrollDialog(null, text, null, null, null, "OK");
	}

	static String buildPartListText(OCardBase owner, boolean buttons, International intl) {
		StringBuilder text = new StringBuilder();
		int count = buttons ? owner.btnList.size() : owner.fldList.size();
		text.append(intl.getDialogText(buttons ? "Buttons: " : "Fields: "))
			.append(count).append('\n');

		for(int i=0; i<count; i++){
			OObject part = buttons ? owner.btnList.get(i) : owner.fldList.get(i);
			String partName = part.name;
			if(partName == null || partName.length() == 0){
				partName = intl.getDialogText("(no name)");
			}
			else{
				partName = "\"" + partName.replace('\r', ' ').replace('\n', ' ') + "\"";
			}
			text.append(i+1).append(".  ")
				.append(intl.getDialogText("ID: ")).append(part.id).append("  ")
				.append(intl.getDialogText("Name: ")).append(partName).append('\n');
		}
		return text.toString();
	}
	
	static class AuthPanelListener implements ActionListener
	{
		public void actionPerformed(ActionEvent e) {
			if(!(object instanceof OPanel)) return;
			OPanel panel = (OPanel)object;
			String cmd = PCARD.pc.intl.getDialogEngText(e.getActionCommand());
			if(cmd.equals("Script…")){
				ScriptEditor.openScriptEditor(PCARD.pc, panel);
				return;
			}
			if(cmd.equals("Content…")){
				new TextEditor(PCARD.pc, panel);
				return;
			}
			if(cmd.equals("Data…")){
				PanelDataDialog.show(panel);
				return;
			}
			AuthoringUndo.Snapshot before = AuthoringUndo.capture();
			try{
				if(cmd.equals("Visible")) panel.setVisible(((JCheckBox)e.getSource()).isSelected());
				else if(cmd.equals("Delete Panel")){
					panel.owner.deletePanel(panel);
					closeAuthDialog();
				}
			}finally{ AuthoringUndo.commit(before); }
			ShapeTool.refresh();
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
			AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

			if(jfield.getName().equals("name")){
				if(object.objectType.equals("button")){
					((OButton)object).setName(jfield.getText());
				}else{
					object.name = jfield.getText();
				}
				if(object instanceof OPanel){
					((OPanel)object).owner.changed = true;
					ShapeTool.refresh(); //the panel's tag round a selected shape
				}
			}

		} finally { AuthoringUndo.commit(before); }
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
					PCARD.pc.stack.windowNominal = null; //the window shows the whole card at its new size
					//width/heightは論理座標。2倍拡大モード中はウインドウ・mainPaneを
					//scale()倍しないとサイズが合わない。ツールバー幅・メニューバー高さ
					//なども込みで正しく計算してくれる既存のsetNewBounds()を使う
					//(以前は物理座標をそのまま使っていたため、拡大モードで
					//ウインドウが2倍サイズにならなかった)。
					PCARD.pc.setNewBounds();
				}
			}
			this.dispose();
		}
	}
}

class AuthColorButton extends CPButton
{
	private static final long serialVersionUID = 3564756897317769905L;

	/** the swatch's colour before a button has a hiliteColor */
	static final Color DEFAULT_HILITE = new Color(204, 204, 255);

	int type; //0 text, 1 background, 2 hilite (button)
	OObject obj;
	/** type 2: the "Hilite Color" box, ticked when a colour is chosen */
	JCheckBox hiliteCheck;
	
	AuthColorButton(Color in_color, OObject obj, int type){
		super(in_color,0,0,false);
		this.obj = obj;
		this.type = type;
	}
	
	@Override
	String dialogTitle(){
		if(type==2) return PCARDFrame.pc.intl.getDialogText("Hilite Color");
		return PCARDFrame.pc.intl.getToolText(type==0 ? "Text Color" : "Background Color");
	}

	@Override
	public void makeIcon(Color col){
		AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

		super.makeIcon(col);
		
		if(obj.getClass()==OButton.class){
			if(type==0) ((OButton)obj).setColor(col);
			if(type==1) ((OButton)obj).setBgColor(col);
			if(type==2){
				((OButton)obj).setHiliteColor(col);
				if(hiliteCheck!=null) hiliteCheck.setSelected(true);
			}
		}
		else if(obj.getClass()==OField.class){
			if(type==0) ((OField)obj).setColor(col);
			if(type==1) ((OField)obj).setBgColor(col);
		}
		OCard.reloadCurrentCard();

		} finally { AuthoringUndo.commit(before); }
	}
}

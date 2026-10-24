import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;

public class GFontDialog extends JDialog implements ActionListener {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String selectedFont="";
	static int selectedSize;
	static int selectedStyle;
	static int selectedAlign;
	static MyTextArea sampleArea;
	PCARDFrame owner;
	
	GFontDialog(PCARDFrame owner, String defaultFont, int defaultSize, int defaultStyle, int defaultAlign) {
		super(owner,true);
		this.owner = owner;
		getContentPane().setLayout(null);
		setTitle(PCARD.pc.intl.getDialogText("Font"));
		
		//サンプル表示
		if(International.uses("ja")){
			sampleArea = new MyTextArea("Sample Text\nこれはサンプルです");
		}else{
			sampleArea = new MyTextArea("Sample Text");
		}
		sampleArea.fldData = new OField(null, 0);
		sampleArea.fldData.width = 400;
		sampleArea.fldData.dontWrap = true;
		//MyTextArea#setBoundsはカード上のフィールド向けに表示倍率を掛ける。
		//このサンプルは倍率の掛からないダイアログ部品なので、Swing座標を直接指定する。
		//通常のsetBoundsを使うと2倍表示時に高さ192pxとなり、直下のリストへ重なる。
		setSampleBounds(sampleArea);
		sampleArea.setMargin(new Insets(0,0,0,0));
		sampleArea.setEditable(false);
		sampleArea.setFocusable(false);
		setStyleSample(defaultFont, defaultSize, defaultStyle, defaultAlign);
		getContentPane().add(sampleArea);

		//フォント用スクロール
		JScrollPane scrl = new JScrollPane();
		scrl.setBounds(4,96,192,156);
		scrl.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrl.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		getContentPane().add(scrl);

		//フォントリスト
		//以前はMyTextArea(1行1エントリのプレーンテキスト)に自前でクリック位置→
		//行番号の計算・選択ハイライトの手描き・キャレット移動によるスクロール
		//追従を実装していたが、JTextAreaの既定のキャレット追従スクロール
		//(DefaultCaretのadjustVisibility)がJViewportのスクロールと絡んで
		//非同期に発火するため、スクロール後に新しく見える行の再描画が
		//行われず、リストの一部が白いまま残ってしまう不具合があった
		//(フォントサイズのリストで上半分が白くなって見えなくなる不具合)。
		//選択・スクロールを自前で再実装するのではなく、Swing標準のJListに
		//置き換えることで、この種の不具合が起きないようにする。
	    //macOS's hidden system families (".AppleSystemUIFont") are not for documents
	    final String fontFamilyNames[] = java.util.Arrays.stream(GraphicsEnvironment
	    	.getLocalGraphicsEnvironment()
	    	.getAvailableFontFamilyNames()).filter(n -> !n.startsWith(".")).toArray(String[]::new);
		JList<String> fontList = new JList<String>(fontFamilyNames);
		fontList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		//フォーカスが外れているとLook&Feel次第では選択行がほとんど分からない
		//色になってしまうため、常にはっきり見えるようSystemColor.textHighlightを
		//明示しておく(以前の自前描画のリストと同じ見た目に揃える)。
		fontList.setSelectionBackground(SystemColor.textHighlight);
		fontList.setSelectionForeground(SystemColor.textHighlightText);
		fontList.addListSelectionListener(this::fontSelectionChanged);
		scrl.setViewportView(fontList);

		//行選択
	    for(int i=0; i<fontFamilyNames.length;i++) {
	    	if(defaultFont.equals(fontFamilyNames[i])){
                fontList.setSelectedIndex(i);
                fontList.ensureIndexIsVisible(i);
	    	}
	    }

		//サイズ用スクロール
		JScrollPane sizeScrl = new JScrollPane();
		sizeScrl.setBounds(200,96,54,156);
		sizeScrl.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		sizeScrl.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		getContentPane().add(sizeScrl);

		//サイズリスト(フォントリストと同じ理由でJListにしてある)
	    final String sizeStrings[] = new String[127];
	    for(int i=1; i<=127;i++) {
            sizeStrings[i-1] = String.valueOf(i);
	    }
	    JList<String> sizeList = new JList<String>(sizeStrings);
	    sizeList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
	    sizeList.setSelectionBackground(SystemColor.textHighlight);
	    sizeList.setSelectionForeground(SystemColor.textHighlightText);
	    sizeList.addListSelectionListener(this::sizeSelectionChanged);
	    sizeScrl.setViewportView(sizeList);

		//行選択
	    sizeList.setSelectedIndex(defaultSize-1);
	    sizeList.ensureIndexIsVisible(defaultSize-1);

		//パネル
	    JPanel stylePanel = new JPanel();
	    stylePanel.setBounds(254,96,140,156);
	    stylePanel.setLayout(new GridLayout(8,1));
	    getContentPane().add(stylePanel);
		
		//スタイル
	    JCheckBox check = new JCheckBox(PCARD.pc.intl.getDialogText("Bold"));
		check.setSelected((defaultStyle&1)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Italic"));
		check.setSelected((defaultStyle&2)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Underline"));
		check.setSelected((defaultStyle&4)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Outline"));
		check.setSelected((defaultStyle&8)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Shadow"));
		check.setSelected((defaultStyle&16)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Condensed"));
		check.setSelected((defaultStyle&32)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Extend"));
		check.setSelected((defaultStyle&64)>0);
		check.addActionListener(this);
		stylePanel.add(check);

	    check = new JCheckBox(PCARD.pc.intl.getDialogText("Group"));
		check.setSelected((defaultStyle&128)>0);
		check.addActionListener(this);
		stylePanel.add(check);
		
		selectedStyle = defaultStyle;

		if(defaultAlign!=-1){
			//パネル
		    JPanel alignPanel = new JPanel();
		    alignPanel.setBounds(0,250,400,300);
		    alignPanel.setLayout(new FlowLayout());
		    getContentPane().add(alignPanel);
		    
		    ButtonGroup group = new ButtonGroup();
		    
		    JRadioButton radio = new JRadioButton(PCARD.pc.intl.getDialogText("Align Left"));
		    radio.setName("Align Left");
		    radio.setSelected(defaultAlign==0);
		    radio.addActionListener(this);
		    group.add(radio);
		    alignPanel.add(radio);
			
		    radio = new JRadioButton(PCARD.pc.intl.getDialogText("Align Center"));
		    radio.setName("Align Center");
		    radio.setSelected(defaultAlign==1);
		    radio.addActionListener(this);
		    group.add(radio);
		    alignPanel.add(radio);
			
		    radio = new JRadioButton(PCARD.pc.intl.getDialogText("Align Right"));
		    radio.setName("Align Right");
		    radio.setSelected(defaultAlign==2);
		    radio.addActionListener(this);
		    group.add(radio);
		    alignPanel.add(radio);
			
			selectedAlign = defaultAlign;
		}
	    
		setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-160+12,400,320);

		setResizable(false);
		setVisible(true);
	}

	static void setSampleBounds(MyTextArea area) {
		area.setBoundsRaw(0, 0, 400, 96);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		String in_name = e.getActionCommand();
		String name = PCARD.pc.intl.getDialogEngText(in_name);
		if(name.equals("Bold")){
			selectedStyle = (selectedStyle&(~1))|
				(((JCheckBox)e.getSource()).isSelected()?1:0);
		}
		else if(name.equals("Italic")){
			selectedStyle = (selectedStyle&(~2))|
				(((JCheckBox)e.getSource()).isSelected()?2:0);
		}
		else if(name.equals("Underline")){
			selectedStyle = (selectedStyle&(~4))|
				(((JCheckBox)e.getSource()).isSelected()?4:0);
		}
		else if(name.equals("Outline")){
			selectedStyle = (selectedStyle&(~8))|
				(((JCheckBox)e.getSource()).isSelected()?8:0);
		}
		else if(name.equals("Shadow")){
			selectedStyle = (selectedStyle&(~16))|
				(((JCheckBox)e.getSource()).isSelected()?16:0);
		}
		else if(name.equals("Condensed")){
			selectedStyle = (selectedStyle&(~32))|
				(((JCheckBox)e.getSource()).isSelected()?32:0);
		}
		else if(name.equals("Extend")){
			selectedStyle = (selectedStyle&(~64))|
				(((JCheckBox)e.getSource()).isSelected()?64:0);
		}
		else if(name.equals("Group")){
			selectedStyle = (selectedStyle&(~128))|
				(((JCheckBox)e.getSource()).isSelected()?128:0);
		}
		else if(name.equals("Align Left")){
			selectedAlign = 0;
		}
		else if(name.equals("Align Center")){
			selectedAlign = 1;
		}
		else if(name.equals("Align Right")){
			selectedAlign = 2;
		}
		setStyleSample(selectedFont, selectedSize, selectedStyle, selectedAlign);
	}
	
    private void fontSelectionChanged(ListSelectionEvent e) {
        @SuppressWarnings("unchecked")
        JList<String> list = (JList<String>)e.getSource();
        String value = list.getSelectedValue();
        if(value==null) return;
        selectedFont = value;
        setStyleSample(selectedFont, selectedSize, selectedStyle, selectedAlign);
    }

    private void sizeSelectionChanged(ListSelectionEvent e) {
        @SuppressWarnings("unchecked")
        JList<String> list = (JList<String>)e.getSource();
        String value = list.getSelectedValue();
        if(value==null) return;
        selectedSize = Integer.parseInt(value);
        setStyleSample(selectedFont, selectedSize, selectedStyle, selectedAlign);
    }

    private void setStyleSample(String font, int size, int style, int align){

		sampleArea.fldData.textFont = font;
		sampleArea.fldData.textSize = size;
		sampleArea.fldData.textStyle = style;
		sampleArea.fldData.textAlign = align;
		
		int javaStyle = Font.PLAIN;
		if((1&style)>0){
			javaStyle |= Font.BOLD;
		}
		else if((2&style)>0){
			javaStyle |= Font.ITALIC;
		}
		Font ffont = new Font(font, javaStyle, size);
		sampleArea.setFont(ffont);
		
		if(owner.tool!=null && owner.tool.getClass() ==TypeTool.class){
			TypeTool tool = (TypeTool)owner.tool;
			if(tool.area!=null){
				tool.area.fldData.textFont = font;
				tool.area.fldData.textSize = size;
				tool.area.fldData.textStyle = style;
				tool.area.fldData.textAlign = align;
				tool.area.setFont(ffont);
			}
		}
    }

}

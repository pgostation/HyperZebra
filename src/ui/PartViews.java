import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.dnd.DropTarget;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.MatteBorder;
import java.awt.SystemColor;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.border.EmptyBorder;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.awt.image.DataBuffer;
import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.io.FileNotFoundException;
import java.nio.charset.Charset;
import java.io.*;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.BasicStroke;
import java.awt.Paint;
import java.awt.TexturePaint;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.*;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileFilter;
import java.awt.geom.Point2D;
import java.net.URISyntaxException;
import java.util.HashMap;
import javax.swing.JToggleButton;

/** Desktop code moved out of the core's OButton unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class PartViews {
	/** The radio buttons' groups, by family (was OButton's). */
	static javax.swing.ButtonGroup[] btnGroup = new javax.swing.ButtonGroup[16];

	private PartViews(){}

	// from OButton
	static void buildOButton(OButton obtn)
	{
		//古いポップアップの名前ラベル(あれば)は、呼び出し元が事前にmainPaneを
		//removeAll()済みなので画面上は既に消えているはずだが、参照が残って
		//いるとstyleがpopupへ戻ったときに古い(親を失った)ラベルを再利用
		//してしまい表示されなくなるため、ここで必ずリセットする。
		obtn.popupNameLabel = null;

		//swingのボタンを追加
		if(obtn.style==0 || obtn.style==6) {
			obtn.btn = new MyButton(obtn, obtn.name);
			if(obtn.style==6){
				PCARD.pc.getRootPane().setDefaultButton(obtn.btn);
			}
		}
		if(obtn.style==1 || obtn.style==2 || obtn.style==3 || obtn.style==4) obtn.btn = new RectButton(obtn, obtn.name);
		if(obtn.style==5) obtn.btn = new RoundedCornerButton(obtn, obtn.name);
		if(obtn.style==7) obtn.btn = new RoundButton(obtn, obtn.name);
		
		if(obtn.style<=7) {
			obtn.addListener();
			obtn.btn.setFocusPainted(false);
			obtn.btn.setForeground(obtn.color);
			obtn.btn.setBackground(obtn.bgColor);
			obtn.btn.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.btn.setEnabled(obtn.enabled);
			obtn.btn.setAutoHilite(obtn.autoHilite);
			obtn.btn.setHilite(obtn.hilite);
			obtn.btn.setFont(OObject.stackFont(obtn.textFont, obtn.textStyle & 0x03, obtn.textSize*Host.get().scale()));
			if(!obtn.showName) obtn.btn.setText("");
	        if(((obtn.textStyle&8) > 0 || (obtn.textStyle&16) > 0) && obtn.icon==0 && (obtn.iconURI==null || obtn.iconURI.toString().isEmpty())){//outline,shadow
	        	obtn.btn.setText("");
	        }
	        if(obtn.iconURI!=null && obtn.iconURI.toString().length()>=1){
	        	obtn.setIconURI(obtn.iconURI,true);
	        }
	        else{
	        	obtn.setIcon(obtn.icon,true);
	        }
	        /*if(obtn.icon > 0) {
				File file=new File(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+".png");
				if(file.exists()) {
					obtn.btn.setIcon(new ImageIcon(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+".png"));
				}
				else {
					file=new File(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+".jpeg");
					if(file.exists()) {
						obtn.btn.setIcon(new ImageIcon(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+".jpeg"));
					}
					else {
						try {
							BufferedImage pict = ImageIO.read(new File(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+"_img.png"));
							File file2=new File(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+"_mask.png");
							if(file2.exists()) {
								BufferedImage mask = ImageIO.read(file);
								pict = OCard.makeAlphaImage(pict, mask);
								File ofile=new File(obtn.card.stack.file.getParent()+"/resource/icon"+obtn.icon+".png");
								ImageIO.write(pict,"png",ofile);
							}
						}catch (Exception err) {
							//err.printStackTrace();
						}
					}
				}
				obtn.btn.setHorizontalTextPosition(JButton.CENTER);
				obtn.btn.setVerticalTextPosition(JButton.BOTTOM);
			}
			if(obtn.icon == -1) {
				File file=new File(obtn.card.stack.file.getParent()+"/resource/pict"+obtn.name+".png");
				if(!file.exists()) {
					file=new File(obtn.card.stack.file.getParent()+"/resource/pict"+obtn.name+".jpeg");
				}
				if(!file.exists()) {
					try {
						BufferedImage pict = ImageIO.read(new File(obtn.card.stack.file.getParent()+"/resource/pict"+obtn.name+"_img.png"));
						File file2=new File(obtn.card.stack.file.getParent()+"/resource/pict"+obtn.name+"_mask.png");
						if(file2.exists()) {
							BufferedImage mask = ImageIO.read(file);
							pict = OCard.makeAlphaImage(pict, mask);
							File ofile=new File(obtn.card.stack.file.getParent()+"/resource/pict"+obtn.name+".png");
							ImageIO.write(pict,"png",ofile);
						}
					}catch (Exception err) {
						//err.printStackTrace();
					}
				}
				obtn.btn.setIcon(new ImageIcon(obtn.card.stack.file.getParent()+"/resource/pict"+obtn.name+".png"));
				obtn.btn.setText("");
			}*/
			switch(obtn.textAlign){
				case 0:obtn.btn.setHorizontalAlignment(JButton.LEFT);break;
				case 1:obtn.btn.setHorizontalAlignment(JButton.CENTER);break;
				case 2:obtn.btn.setHorizontalAlignment(JButton.RIGHT);break;
			}
			obtn.btn.setVisible(obtn.isShown());
			switch(obtn.style) {
			case 0:
			case 6:
				break;
			case 1:
			case 2:
				obtn.btn.setContentAreaFilled(false);
				obtn.btn.setBorderPainted(false);
				obtn.btn.setBorder(null);
				break;
			case 4:
				obtn.btn.setBorder(new MatteBorder(1,1,3,3,obtn.color));
				break;
			case 3:
			case 5:
			case 7:
				obtn.btn.setBorder(new MatteBorder(1,1,1,1,obtn.color));
				break;
			} 
			obtn.btn.setMargin(new Insets(0,0,0,0));
			obtn.btn.setFocusable(false);
			PCARD.pc.mainPane.add(obtn.btn);
		}
		else if(obtn.style==8) {
			String[] value = obtn.getText().split("\n");
			obtn.popup = new MyPopup(obtn, value);
			obtn.popup.setName(obtn.name);
			//popup.setFocusPainted(false);
			obtn.popup.setForeground(obtn.color);
			obtn.popup.setBackground(obtn.bgColor);
			obtn.popup.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.popup.setEnabled(obtn.enabled);
			//popup(autoHilite);
			//popup.setSelected(hilite);
			try{obtn.popup.setSelectedIndex(obtn.selectedLine-1);}catch(Exception e){System.out.println("err: popup.setSelectedIndex(selectedLine-1)");}
			//JComboBoxの高さはAqua L&Fが矢印ボタン用に描画するネイティブ画像の
			//高さに固定されており(GEncodingDialog参照)、setBoundsで2倍拡大
			//モード用に高さを2倍にしても実際の見た目はほとんど大きくならない。
			//それにもかかわらず文字サイズだけ2倍にすると、変わらない箱に対して
			//文字だけ大きすぎる見た目になってしまうため、popup自体のフォントは
			//拡大率を掛けない。
			obtn.popup.setFont(OObject.stackFont(obtn.textFont, obtn.textStyle&0x03, obtn.textSize));
			//以前はshowNameのときに名前をツールチップとしてだけ表示していたが、
			//実機のポップアップメニューコントロールと同様、titleWidthの幅で
			//左側に実際に描画するようにした(MyPopup.setBounds参照)。
			obtn.popup.setFocusable(false);
			obtn.popup.setMaximumRowCount(20);
			PCARD.pc.mainPane.add(obtn.popup);
			obtn.popup.setVisible(obtn.isShown());
			obtn.addListener();
		}
		else if(obtn.style==9) {
			obtn.chkbox = new MyCheck(obtn, obtn.name);
			ClassicToggleIcon.install(obtn.chkbox, obtn, false);
			obtn.addListener();
			obtn.chkbox.setFocusPainted(false);
			obtn.chkbox.setForeground(obtn.color);
			obtn.chkbox.setBackground(obtn.bgColor);
			obtn.chkbox.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.chkbox.setEnabled(obtn.enabled);
			//chkbox.setAutoHilite(autoHilite);
			obtn.chkbox.setSelected(obtn.hilite);
			obtn.chkbox.setFont(OObject.stackFont(obtn.textFont, obtn.textStyle&0x03, obtn.textSize*Host.get().scale()));
			obtn.chkbox.setVisible(obtn.isShown());
			if(!obtn.showName) obtn.chkbox.setText("");
			obtn.chkbox.setFocusable(false);
			PCARD.pc.mainPane.add(obtn.chkbox);
		}
		else if(obtn.style==10) {
			obtn.radio = new MyRadio(obtn, obtn.name);
			ClassicToggleIcon.install(obtn.radio, obtn, true);
			obtn.addListener();
			obtn.radio.setFocusPainted(false);
			obtn.radio.setForeground(obtn.color);
			obtn.radio.setBackground(obtn.bgColor);
			obtn.radio.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.radio.setEnabled(obtn.enabled);
			//chkbox.setAutoHilite(autoHilite);
			obtn.radio.setSelected(obtn.hilite);
			obtn.radio.setFont(OObject.stackFont(obtn.textFont, obtn.textStyle, obtn.textSize*Host.get().scale()));
			obtn.radio.setVisible(obtn.isShown());
			obtn.radio.setFocusable(false);
			if(!obtn.showName) obtn.radio.setText("");
			PCARD.pc.mainPane.add(obtn.radio);
			//family 0のラジオボタンはHyperCardではチェックボックスと同様に独立して
			//動く。ButtonGroupに入れるとSwingが選択解除を拒むため、スクリプトの
			//"set the hilite ... to false"が効かなくなる
			if(obtn.group>=1){
				if(PartViews.btnGroup[obtn.group]==null) {
					PartViews.btnGroup[obtn.group] = new ButtonGroup();
				}
				PartViews.btnGroup[obtn.group].add(obtn.radio);
			}
		}
	}

	// from OField
	static void buildOField(OField ofld)
	{
		//swingのテキストコンポーネントを追加
		ofld.fld = new MyTextArea(ofld.getText());

		ofld.fld.fldData = ofld;
		ofld.addListener();

		//上のnew MyTextArea(ofld.getText())で既にテキストは設定済みであり、ここでの
		//setTextは本来不要な二重設定だった。しかしこの時点では直前のaddListener()で
		//ドキュメントリスナー(GUI_fldDocument)が既に取り付けられているため、この
		//setTextが「全文削除→全文挿入」のドキュメントイベントを発生させてしまい、
		//GUI_fldDocument.changedUpdate→preserveStylesForTextChangeが呼ばれて
		//フィールドのstyleList(部分的な文字装飾)がカードを開く/表示するたびに
		//消えてしまっていた(シャドウなどのスタイルが表示上だけ消える不具合の原因)。
		ofld.fld.setForeground(ofld.color);
		ofld.fld.setBackground(ofld.bgColor);
		ofld.fld.setBounds(ofld.left, ofld.top, ofld.width, ofld.height); 
		//ofld.fld.setSelectionColor(ofld.selectColor);
		
		//the host's family for the font, or its stand-in (a font only the stack's own FONT strike has, as Uli Kusterer's Sempach,
		//is drawn with the host's default face: card text is never drawn from bitmap strikes)
		ofld.fld.setFont(OObject.stackFont(ofld.textFont, ofld.textStyle, ofld.textSize*Host.get().scale()));
		if(ofld.textHeight==0){
			ofld.textHeight = ofld.fld.getLineHeight();
		}
		ofld.fld.setVisible(ofld.isShown());
		//lockTextだけでなく、現在のツールとピーク状態も考慮して決める
		//(フィールドツール選択中にカードが作り直されても、テキストが
		//選択できる状態に戻ってしまわないようにする)
		ofld.updateTextEditable();
		ofld.fld.setLineWrap(!ofld.dontWrap);
		//while it is edited Swing lays the text out: at word breaks, as the own drawing and HyperCard wrap (Parts of Speech's
		//typed "between, down" broke as "do" / "wn")
		ofld.fld.setWrapStyleWord(true);
		if(ofld.selectedStart>0 && ofld.selectedEnd>0) {
			ofld.fld.setSelectionStart(ofld.selectedStart);
			ofld.fld.setSelectionEnd(ofld.selectedEnd);
		}
		Style s = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
		switch(ofld.textAlign){
			case 0:break;
			case 1:StyleConstants.setAlignment(s, StyleConstants.ALIGN_CENTER);break;
			case 2:StyleConstants.setAlignment(s, StyleConstants.ALIGN_RIGHT);break;
		}
		StyleConstants.setComponent(s, ofld.fld);
		//(0標準) 1透明 2不透明 3長方形 4シャドウ 5スクロール
		switch(ofld.style) {
		case 1:
			ofld.fld.setOpaque(false);
			break;
		case 2:
			ofld.fld.setBorder(new EmptyBorder(1,1,1,1));
			break;
		case 3:
			ofld.fld.setBorder(new RectangleFieldBorder(ofld));
			break;
		case 4:
			//枠と影はHyperCardと同じ形(ShadowFieldBorder)。影の欠けた角からはカードが見えるので不透明にしない
			ofld.fld.setBorder(new ShadowFieldBorder(ofld));
			ofld.fld.setOpaque(false);
			break;
		} 
		if(ofld.autoSelect) ofld.fld.setOpaque(false);
		ofld.applyMargin();

		if(ofld.outlineStyle) {
			//アウトライン: 表と同じく、テキストはMyTextAreaに持たせたまま木を部品として置く
			FieldOutline outline = new FieldOutline(ofld);
			outline.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			outline.setVisible(ofld.isShown());
			PCARD.pc.mainPane.add(outline);
			ofld.card.paneList.add(outline);
			ofld.scrollPane = outline;
			MyScrollPane.followScroll(outline, ofld);
		}
		else if(ofld.tableStyle) {
			//表: テキストはMyTextAreaに持たせたまま(画面には出さない)、表を部品として置く
			FieldTable table = new FieldTable(ofld);
			table.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			table.setVisible(ofld.isShown());
			PCARD.pc.mainPane.add(table);
			ofld.card.paneList.add(table);
			ofld.scrollPane = table;
			MyScrollPane.followScroll(table, ofld);
		}
		else if(ofld.style<=4) {
			PCARD.pc.mainPane.add(ofld.fld);
		}
		else {
			MyScrollPane scrollpane = new MyScrollPane();
			scrollpane.fldData=ofld;
			//the view starts at the viewport's origin, not at the field's place on the card: a field above or left of the card
			//(HCSTG PUGO's -9,-5) was a viewport position of 9,5 - its text 9px left and 5px up, and its scroll 5
			ofld.fld.setLocation(0, 0);
			scrollpane.setViewportView(ofld.fld);
			scrollpane.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			scrollpane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			scrollpane.getVerticalScrollBar().setUnitIncrement(Math.max(1, (int)Math.round(Math.max(1, ofld.textHeight)*Math.max(1, Host.get().scale()))));
			scrollpane.setVisible(ofld.isShown());
			PCARD.pc.mainPane.add(scrollpane);
			ofld.card.paneList.add(scrollpane);
			ofld.scrollPane = scrollpane;
			MyScrollPane.followScroll(scrollpane, ofld);
			
    		ofld.fld.revalidate();
		}
	}

	// from OCard
	static OCard buildOCard(OCard cd)
	{
		cd.picture = new OPicture(cd);
		//追加のペイントレイヤーの画像
		cd.loadLayerImages();
		//カードピクチャが無くてもレイヤーがあれば表示用のラベルが要る。ピクチャの無いカードは<bitmap></bitmap>と
		//保存され、読み直すと空の名前になる(nullではない): 空の名前も「無い」(レイヤーだけに描いたカードを開き直すと
		//レイヤーが見えなかった)
		boolean noPicture = cd.bitmapName==null || cd.bitmapName.isEmpty();
		if(cd.hasLayers() && noPicture){
			cd.label = MyLabel.getMyCdLabel(cd);
			cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
			PCARD.pc.mainPane.add(cd.label);
		}
		
		//ピクチャ読み込み(pbm)
		if(!noPicture){
			String path = (cd.stack.file.getParent()+File.separatorChar+cd.bitmapName);
			cd.pict = PictureFile.loadPbmCached(path);
			if(cd.pict==null){
				//読み込まれるまで待つ
				for(int i=0; HCData.threadList!=null&&i<HCData.threadList.size(); i++){
					Thread p = HCData.threadList.get(i);
					if(p!=null && p.getName().equals(cd.bitmapName)){
						HCData.startIfNew(p);
						long lastLength = -1;
						File file = new File(path);
						for(int j=0; !file.exists() && j<50 || p.isAlive() || lastLength!=file.length(); j++){
							lastLength = file.length();
							try {
								Platform.get().sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				}
				if(new File(path).exists() && !new File(path).isDirectory()){
					try {
						cd.pict = PictureCache.readShared(new File(path), "imageio", StackProtection::readImage);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			if(cd.pict!=null){
				cd.label = MyLabel.getMyCdLabel(cd);
				cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
				PCARD.pc.mainPane.add(cd.label);
				return cd;
			}
		}

		//ピクチャ読み込み
		File file=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".png");
		try {
			if(file.exists()) {
				//System.out.println(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".png");
				cd.pict = PictureCache.readShared(file, "imageio", StackProtection::readImage);
				cd.label = MyLabel.getMyCdLabel(cd/*new ImageIcon(stack.file.getParent()+"/resource/cdp"+this.id+".png")*/);
				cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
				PCARD.pc.mainPane.add(cd.label);
			} else {
				file=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".jpeg");
				if(file.exists()) {
					//System.out.println(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".jpeg");
					cd.pict = PictureCache.readShared(file, "imageio", StackProtection::readImage);
					cd.pict = OCard.makeAlphaImage2(cd.pict);
					cd.label = MyLabel.getMyCdLabel(cd/*new ImageIcon(stack.file.getParent()+"/resource/cdp"+this.id+".jpeg")*/);
					cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
					PCARD.pc.mainPane.add(cd.label);
				}
			}
		} catch (Exception err1) {
			//アルファを使用したピクチャが無い場合はマスク画像を使用して作成
			try {
				cd.pict = StackProtection.readImage(new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+"_img.png"));
				file=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+"_mask.png");
				if(file.exists()) {
					BufferedImage mask = StackProtection.readImage(file);
					cd.pict = OCard.makeAlphaImage(cd.pict, mask);
					File ofile=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".png");
					StackProtection.writeImage(cd.stack, cd.pict, ofile);
					PictureCache.invalidate(ofile);
				}
			}catch (Exception err) {
				//err.printStackTrace();
			}
		}
		
		return cd;
	}

	// from OBackground
	static OBackground buildOBackground(OBackground bg)
	{
		bg.picture = new OPicture(bg);
		//追加のペイントレイヤーの画像
		bg.loadLayerImages();

		//bgの場合はピクチャがなくても白で必ず描画しなくてはいけない
		bg.label = MyLabel.getMyBgLabel(bg);
		bg.label.setBounds(0,0,bg.stack.width,bg.stack.height);
		PCARD.pc.mainPane.add(bg.label);
		
		//ピクチャ読み込み(pbm)
		if(bg.bitmapName!=null){
			String path = (bg.stack.file.getParent()+File.separatorChar+bg.bitmapName);
			bg.pict = PictureFile.loadPbmCached(path);
			if(bg.pict==null){//読み込まれるまで待つ
				for(int i=0; HCData.threadList!=null&&i<HCData.threadList.size(); i++){
					Thread p = HCData.threadList.get(i);
					if(p!=null && p.getName().equals(bg.bitmapName)){
						HCData.startIfNew(p);
						long lastLength = -1;
						File file = new File(path);
						for(int j=0; !file.exists() && j<50 || p.isAlive() || lastLength!=file.length(); j++){
							lastLength = file.length();
							try {
								Platform.get().sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				}
				if(new File(path).exists() && !new File(path).isDirectory()){
					try {
						bg.pict = PictureCache.readShared(new File(path), "imageio", StackProtection::readImage);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			if(bg.pict!=null){
				//bg.showPict = true;
			}
			return bg;
		}
		
		//ピクチャ読み込み
		File file=new File(bg.stack.file.getParent()+"/resource/bgp"+bg.id+".png");
		//bg.showPict = false;
		bg.label = MyLabel.getMyBgLabel(bg);//bgはこれ以上後ろがないのでラベル必須
		bg.label.setBounds(0,0,bg.stack.width,bg.stack.height);
		PCARD.pc.mainPane.add(bg.label);
		try {
			if(file.exists()) {
				bg.pict = PictureCache.readShared(file, "imageio", StackProtection::readImage);
				//bg.showPict = true;
			} else {
				file=new File(bg.stack.file.getParent()+"/resource/bgp"+bg.id+".jpeg");
				bg.pict = PictureCache.readShared(file, "imageio", StackProtection::readImage);
				//bg.showPict = true;
			}
		} catch (Exception err1) {
			//err1.printStackTrace();
		}
		
		return bg;
	}

	// from OCard
	/** Lines of OCard.reloadCurrentCard (the core calls this in their place). */
	static void reattachCardViews() {
		if(HZ.editBackground){
			for(OShape shape:HZ.stack().curCard.shapeList)if(shape.view!=null)PCARD.pc.mainPane.remove(shape.view);
			for(int i=0; i<HZ.stack().curCard.btnList.size(); i++){
				OButton removedButton = HZ.stack().curCard.btnList.get(i);
				PCARD.pc.mainPane.remove(removedButton.getComponent());
				//popup(style==8)の名前ラベルはgetComponent()とは別の部品なので、
				//あわせて外さないとポップアップ本体だけ消えて名前だけ残ってしまう。
				if(removedButton.popupNameLabel!=null) PCARD.pc.mainPane.remove(removedButton.popupNameLabel);
			}
			for(int i=0; i<HZ.stack().curCard.fldList.size(); i++){
				PCARD.pc.mainPane.remove(HZ.stack().curCard.fldList.get(i).getComponent());
			}
		}
		
		PCARD.pc.mainPane.repaint();
		
		//リスナーも付け直し
		if(Host.get().authToolActive()){
			//オーサリングモード
			if(AuthTool.tool.getClass()==ButtonTool.class){
				GUI.removeAllListener();
				ButtonGUI.gui.addListenerToParts();
			}
			else if(AuthTool.tool.getClass()==FieldTool.class){
				GUI.removeAllListener();
				FieldGUI.gui.addListenerToParts();
				FieldGUI.gui.target = null;
			}
			else if(AuthTool.tool instanceof ShapeTool){GUI.removeAllListener();ShapeTool.refresh();}
		}else if(Host.get().paintToolActive()){
			//ペイントモード
			//部品ごと作り直しているので、新しい部品にはpaintGUIが付いていない。
			//付け直さないと背景では描けるのにボタン/フィールドの上だけ
			//マウスが効かなくなる。
			GUI.removeAllListener();
			paintGUI.gui.removeListenerFromParts();
			paintGUI.gui.addListenerToParts();
		}else{
			//ブラウズモード
			GUI.removeAllListener();
			GUI.addAllListener();
			if(HZ.stack().addColor != null){
				TXcmd.AddColor.addToMainPane();
			}
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setBgColor (the core calls this in their place). */
	static void setBgColorView849(OButton self, Color col) {
		if(self.btn!=null)
		{
			self.btn.setBackground(col);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setColor (the core calls this in their place). */
	static void setColorView842(OButton self, Color col) {
		if(self.btn!=null)
		{
			self.btn.setForeground(col);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setName (the core calls this in their place). */
	static void setNameView176(OButton self, String in) {
		if(self.popup!=null && self.showName){
			self.popup.setToolTipText(in);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setName (the core calls this in their place). */
	static void setNameView173(OButton self, String in) {
		if(self.chkbox!=null && self.showName){
			self.chkbox.setName(in);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setName (the core calls this in their place). */
	static void setNameView170(OButton self, String in) {
		if(self.radio!=null && self.showName){
			self.radio.setName(in);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setName (the core calls this in their place). */
	static void setNameView167(OButton self, String in) {
		if(self.btn!=null && self.showName){
			self.btn.setText(in);
		}
	}

	// from OField (instance)
	/** Lines of OField.setBgColor (the core calls this in their place). */
	static void setBgColorView896(OField self, Color col) {
		if(self.fld!=null)
		{
			self.fld.setBackground(col);
			self.view().repaint();
		}
	}

	// from OField (instance)
	/** Lines of OField.setColor (the core calls this in their place). */
	static void setColorView887(OField self, Color col) {
		if(self.fld!=null)
		{
			self.fld.setForeground(col);
			self.view().repaint();
		}
	}

	// from OField (instance)
	/** Lines of OField.setScroll (the core calls this in their place). */
	static void setScrollView455(OField self, int in) {
		if(self.scrollPane!=null) {
			self.scrollPane.getVerticalScrollBar().setValue(MyScrollPane.toViewScroll(in));
			//a pane not laid out with the text yet keeps the scroll for its layout (MyScrollPane.followScroll); else the bar's limit applies
			if(MyScrollPane.textLaidOut(self.scrollPane)) self.scroll=MyScrollPane.toLogicalScroll(self.scrollPane.getVerticalScrollBar().getValue());
			if(self.fld!=null)
			{
				Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
			}
		}
	}

	// from OCardBase (instance)
	/** Lines of OCardBase.removeData (the core calls this in their place). */
	static void removeDataView605(OCardBase self) {
		if(self.label!=null) {
			//PCARD.pc.mainPane.remove(label);
			self.label.cd = null;
			self.label=null;
			self.pict=null;
			self.picture=null;
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setAutoHilite (the core calls this in their place). */
	static void setAutoHiliteView760(OButton self, boolean in) {
		if(self.btn != null){
			self.btn.setAutoHilite(in);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setHilite (the core calls this in their place). */
	static void setHiliteView592(OButton self, boolean in) {
		if(self.getComponent()!=null)
		{
			if(self.btn!=null){
				self.btn.setHilite(in);
			}
			else if (self.radio!=null){
				self.radio.setSelected(in);
			}
			else if (self.chkbox!=null){
				self.chkbox.setSelected(in);
			}
			
			if(!HZ.lockedScreen) {
				Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
			}
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setEnabled (the core calls this in their place). */
	static void setEnabledView562(OButton self, boolean in) {
		if(self.getComponent()!=null)
		{
			self.getComponent().setEnabled(in);
			if(!HZ.lockedScreen) {
				Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
			}
		}
	}

	// from OStack (instance)
	/** Lines of OStack.OStack (the core calls this in their place). */
	static void stackViews(OStack self, PCARDFrame pc) {
		self.GUI_btn = new GButton();
		self.GUI_radio = new GRadio();
		self.GUI_check = new GCheckBox();
		self.GUI_popup = new GPopup();
		self.GUI_fld = new GField();
		self.mainPane = pc==null ? null : pc.mainPane;

    	if(self.mainPane!=null){
    		self.mainPane.setLayout(null);
    		self.mainPane.setBackground(Color.WHITE);
    	}
	}

	// from OButton (instance)
	/** Lines of OButton.setIcon (the core calls this in their place). */
	static void iconOnView(OButton self, int in, boolean noupdate) {
		if(self.btn!=null)
		{
			//アイコンの読み込み(ファイルI/O)は従来通りここでTTalkスレッド上で
			//行い、その結果をbtn.setIcon()へ反映する処理だけを最後にひとつの
			//EDTタスクへまとめて同期実行する。コンポーネントの状態変更(アイコン
			//差し替え)と即時再描画が分断されないため、EDTが後追いで発行する
			//自動repaintと競合してチラつくのを防げる。
			Icon newIcon = null;
			boolean clearText = false;

			//this.iconImage = null;
			//negative IDs other than -1 are ICON resources too (Teka PIM's TCHome: ICON -15743 "person", -16491 "SoundToggle")
			if(self.icon>0 || self.icon<-1){
				//見つからないIDへ変更した場合に、拡大・blend用の旧画像を残さない。
				self.imageForScale = null;
				//表示用画像はRsrcの小さな共有キャッシュから取得する。
				//cicn優先と生データのデコードもここで行う。
				BufferedImage rawIconBi = self.findIconImage(self.icon);
				self.missingIconUsing = rawIconBi==null ? self.usingKey() : self.ICON_FOUND;
				if(rawIconBi!=null){
					if(self.scaleIcon || self.blendMode!=0) self.imageForScale = rawIconBi;
					newIcon = self.makeButtonIcon(new ImageIcon(rawIconBi));
				}
			}
			else if(self.icon==-1){
				//-1 is HyperCard's PICT button convention: resolve the picture by the
				//button name. Resolve by resource identity rather than looking its ID up
				//again, since a using stack may contain the named PICT with an ID that is
				//also occupied by an unrelated PICT in the main stack.
				BufferedImage bi = ((OCardBase)self.parent).stack.rsrc.getDisplayImage(self.name, "PICT");
				if(bi!=null) bi = PictureTranslation.shown(((OCardBase)self.parent).stack, bi); //its text translated when the stack is
				if(bi!=null){
					if(self.scaleIcon || self.blendMode!=0) self.imageForScale = bi;
					newIcon = self.makeButtonIcon(new ImageIcon( bi ));
				}
				else
				{
					//PICTボタン読み込み(jpeg,png)
					File file=new File(self.card.stack.file.getParent()+"/resource/pict"+self.name+".png");
					if(!file.exists()) {
						file=new File(self.card.stack.file.getParent()+"/resource/pict"+self.name+".jpeg");
					}
					if(!file.exists()) {
						try {
							BufferedImage pict = ImageIO.read(new File(self.card.stack.file.getParent()+"/resource/pict"+self.name+"_img.png"));
							File file2=new File(self.card.stack.file.getParent()+"/resource/pict"+self.name+"_mask.png");
							if(pict!=null && file2.exists()) {
								BufferedImage mask = ImageIO.read(file2);
								pict = OCard.makeAlphaImage(pict, mask);
								File ofile=new File(self.card.stack.file.getParent()+"/resource/pict"+self.name+".png");
								ImageIO.write(pict,"png",ofile);
								file = ofile;
							}
						}catch (Exception err) {
							//err.printStackTrace();
						}
					}
					if(file.exists()) {
						try {
							BufferedImage fallbackImage = javax.imageio.ImageIO.read(file);
							if(fallbackImage!=null){
								if(self.scaleIcon || self.blendMode!=0) self.imageForScale = fallbackImage;
								newIcon = self.makeButtonIcon(new ImageIcon( fallbackImage ));
							}
							else{
								newIcon = null;
								self.imageForScale = null;
							}
						} catch (IOException e) {
							newIcon = null;
							self.imageForScale = null;
						}
					}else{
						newIcon = null;
						self.imageForScale = null;
					}
				}
				//btn.setIcon(new ImageIcon(card.stack.file.getParent()+"/resource/pict"+name+".png"));
				clearText = true;
			}
			else if(self.icon==0){
				newIcon = null;
				self.imageForScale = null;
			}

			final Icon fNewIcon = newIcon;
			final boolean fClearText = clearText;
			final boolean fShowName = self.btn.btnData.showName;
			final int fIcon = self.icon;
			Runnable apply = () -> {
				//the card may have been left (and the component dropped) before this runs: the icon number stays in
				//this object and the next card build shows it (New Features "topics" sets an icon and goes away)
				MyButton b = self.btn;
				if(b==null) return;
				b.setIcon(fNewIcon);
				if(fClearText) b.setText("");
				if(fIcon>0 && fShowName){
					b.setHorizontalTextPosition(SwingConstants.CENTER);
					b.setVerticalTextPosition(SwingConstants.BOTTOM);
				}
				if(!HZ.lockedScreen && noupdate==false) {
					Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
				}
			};
			//カードを組み立てる途中(buildOButton、まだカードに置かれていないボタン)は画面に出ていないので、
			//EDT へ往復せずにその場で設定する(カード移動のたびにアイコン付きボタンの数だけ往復していた)
			if(noupdate && self.btn.getParent()==null) apply.run();
			else Host.get().runOnEDTWhenShown(apply);
			//以前はここで2.x系スタック向けにThread.sleep(2*TTalk.wait)していたが、
			//set icon 1回(実処理0.17ms程度)ごとに2.5ms前後のスリープが入り、
			//アイコンを連続で切り替えるスクリプトの実行速度を大きく落としていた
			//ため撤廃した。
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setIconURI (the core calls this in their place). */
	static void iconURIOnView(OButton self, URI in, boolean noupdate) {
		if(self.btn!=null)
		{
			if(self.iconURI!=null){
				//画像の読み込み(ImageIconのコンストラクタが内部でMediaTrackerを
				//使い読み込み完了まで待つ)はここまでTTalkスレッド上で行い、
				//btn.setIcon()の反映と即時再描画だけを1つのEDTタスクにまとめて
				//同期実行する。以前は読み込み完了を待たない別スレッドでアイコンを
				//差し替えていたため、直後のrepaintと競合してチラついていた。
				Icon newIcon = null;
				try {
					Image img = Toolkit.getDefaultToolkit().getImage(self.iconURI.toURL());
					if(self.scaleIcon || self.blendMode!=0) {
						self.imageForScale = new BufferedImage(img.getWidth(self.btn), img.getHeight(self.btn), BufferedImage.TYPE_INT_ARGB);
						self.imageForScale.createGraphics().drawImage(img,0,0,self.btn);
					}
					newIcon = self.makeButtonIcon(new ImageIcon(img));
				} catch (MalformedURLException e) {
					e.printStackTrace();
				} catch(IllegalArgumentException e){

				}
				final Icon fNewIcon = newIcon;
				final boolean fShowName = self.btn.btnData.showName;
				Host.get().runOnEDT(() -> {
					self.btn.setIcon(fNewIcon);
					if(fShowName){
						self.btn.setHorizontalTextPosition(SwingConstants.CENTER);
						self.btn.setVerticalTextPosition(SwingConstants.BOTTOM);
					}
					if(!HZ.lockedScreen && noupdate==false) {
						Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
					}
				});
			}
			else{
				self.imageForScale = null;
				Host.get().runOnEDT(() -> {
					self.btn.setIcon(null);
					if(!HZ.lockedScreen && noupdate==false) {
						Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
					}
				});
			}
		}
	}

	// from OButton
	static BufferedImage systemIcon(int id){
		if(id!=1 && id!=2) return null;
		synchronized(OButton.SYSTEM_ICONS){
			return OButton.SYSTEM_ICONS.computeIfAbsent(id, k -> HCResource.decodeICON(SystemResources68k.icon(k)));
		}
	}

	// from OShape (instance)
	static void build(OShape self) {
		if(PCARD.pc==null||PCARD.pc.mainPane==null)return;
		if(self.view!=null&&self.view.getParent()!=null)self.view.getParent().remove(self.view);
		// OCardBase.buidParts adds the parts front to back, so each one goes to the end (behind those already there).
		self.view=new OShape.ShapeView(self);self.updateView();PCARD.pc.mainPane.add(self.view);self.view.setVisible(self.isShown());
	}

	// from OButton (instance)
	/** Lines of OButton.setShowName (the core calls this in their place). */
	static void setShowNameView529(OButton self, boolean in) {
		if(self.getComponent()!=null)
		{
			if(self.btn!=null){
				if(self.showName) self.btn.setText(self.name);
				else self.btn.setText("");
			}
			else if(self.popup!=null){
				//showNameが変わったので、名前表示用ラベルの有無・幅を
				//(全体の矩形を渡し直して)MyPopup.setBoundsに再計算させる。
				self.popup.setBounds(self.left, self.top, self.width, self.height);
			}
			else if(self.radio!=null){
				if(self.showName) self.radio.setText(self.name);
				else self.radio.setText("");
			}
			else if(self.chkbox!=null){
				if(self.showName) self.chkbox.setText(self.name);
				else self.chkbox.setText("");
			}
			
			if(!HZ.lockedScreen) {
				Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
			}
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setVisible (the core calls this in their place). */
	static void setVisibleView371(OButton self, boolean in) {
		if(self.getComponent()!=null)
		{
			Host.get().runOnEDTWhenShown(self, Host.EDT_VISIBLE, () -> {
				JComponent component=self.getComponent();
				if(component==null) return;
				component.setVisible(self.isShown());
				if(!HZ.lockedScreen)
					Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
			}, self::applyVisible);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setText (the core calls this in their place). */
	static void setTextView356(OButton self, String in) {
		if(self.popup!=null){
			final String[] values = in.split("\n");
			Host.get().runOnEDT(() -> {
				if(self.popup==null) return;
				self.popup.removeAllItems();
				for(String value: values) self.popup.addItem(value);
				if(!HZ.lockedScreen)
					Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
			});
		}
	}

	// from OField (instance)
	/** Lines of OField.setText (the core calls this in their place). */
	static void setTextView284(OField self, String in) {
		if(self.fld!=null) {
			Runnable updateAndRepaint = () -> {
				int scroll = 0;
				if(self.scrollPane!=null) scroll = self.scrollPane.getVerticalScrollBar().getValue();
				String text = self.getText();
				//put keeps the field's scroll (HyperCard J-2.3: DicSGML puts its 332-line index into a scrolling field at
				//scroll 0 and shows its top); the caret's catching up later (invokeLater) scrolled to the end
				self.applyingText = true;
				try{ self.fld.keepScrollWhile(() -> self.fld.setText(text)); }
				finally{ self.applyingText = false; }
				self.shownText = text;
				if(self.scrollPane!=null) self.scrollPane.getVerticalScrollBar().setValue(scroll);
				if(!HZ.lockedScreen) {
					Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
				}
			};
			//スクリプト実行スレッドから直接Swingコンポーネントのテキストを更新して
			//すぐに再描画すると、EDT側の描画処理と競合して文字が描画されない
			//（フィールドが真っ白のまま）ことがあるため、EDT上でまとめて同期実行する。
			//lock screen 中も保留しない: select・選択範囲・行の位置などはスクリプトスレッドから部品を直接読む
			Host.get().runOnEDT(updateAndRepaint);
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setTextFont (the core calls this in their place). */
	static void setTextFontView569(OButton self, String in, JComponent component) {
		if(component!=null)component.setFont(OObject.stackFont(self.textFont,self.textStyle&0x03,self.textSize*Host.get().scale()));
	}

	// from OButton (instance)
	/** Lines of OButton.setHilite (the core calls this in their place). */
	static void setHiliteView402(OButton self, boolean in, OButton sibling) {
					if(sibling.btn!=null) sibling.btn.setHilite(false);
					else if(sibling.radio!=null) sibling.radio.setSelected(false);
					else if(sibling.chkbox!=null) sibling.chkbox.setSelected(false);
	}

	// from OButton (instance)
	/** Lines of OButton.applyVisible (the core calls this in their place). */
	static void applyVisibleView375(OButton self, JComponent component) {
		if(component!=null) component.setVisible(self.isShown());
	}

	// from OButton (instance)
	/** Lines of OButton.setRect (the core calls this in their place). */
	static void setRectView328(OButton self, int v1, int v2, int v3, int v4, int oldLeft, int oldTop, int oldWidth, int oldHeight) {
		if(self.getComponent()!=null){
			final int newLeft=self.left;
			final int newTop=self.top;
			final int newWidth=self.width;
			final int newHeight=self.height;
			Host.get().runOnEDTWhenShown(self, Host.EDT_BOUNDS, () -> {
				JComponent component=self.getComponent();
				if(component==null) return;
				component.setBounds(newLeft, newTop, newWidth, newHeight);
				if(!HZ.lockedScreen) {
					Host.get().repaintLogicalRect(newLeft, newTop, newWidth, newHeight);
					Host.get().repaintLogicalRect(oldLeft, oldTop, oldWidth, oldHeight);
				}
			}, self::applyBounds);
			if(!HZ.lockedScreen) {
				if(TTalk.wait>0 && HZ.stack().createdByVersion.charAt(0)=='2'){
					try {
						Platform.get().sleep(3*TTalk.wait);
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}

	// from OButton (instance)
	/** Lines of OButton.setTopLeft (the core calls this in their place). */
	static void setTopLeftView286(OButton self, int h, int v, int oldLeft, int oldTop) {
		if(self.getComponent()!=null){
			final int newLeft=self.left;
			final int newTop=self.top;
			final int buttonWidth=self.width;
			final int buttonHeight=self.height;
			//lock中もコンポーネントツリーの変更自体はEDT上で行う。画面は
			//MyPanelがロック開始時のスナップショットを描いて凍結する。
			Host.get().runOnEDTWhenShown(self, Host.EDT_BOUNDS, () -> {
				JComponent component=self.getComponent();
				if(component==null) return;
				component.setBounds(newLeft, newTop, buttonWidth, buttonHeight);
				if(!HZ.lockedScreen) {
					if(Math.abs(newLeft-oldLeft)<buttonWidth && Math.abs(newTop-oldTop)<buttonHeight)
						Host.get().repaintLogicalRect(Math.min(newLeft,oldLeft), Math.min(newTop,oldTop), buttonWidth+Math.abs(newLeft-oldLeft), buttonHeight+Math.abs(newTop-oldTop));
					else {
						Host.get().repaintLogicalRect(newLeft, newTop, buttonWidth, buttonHeight);
						Host.get().repaintLogicalRect(oldLeft, oldTop, buttonWidth, buttonHeight);
					}
				}
			}, self::applyBounds);
			if(!HZ.lockedScreen) {
				if(TTalk.wait>0 && HZ.stack().createdByVersion.charAt(0)=='2'){
					try {
						Platform.get().sleep(3*TTalk.wait);
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}

	// from OField (instance)
	/** Lines of OField.applyVisible (the core calls this in their place). */
	static void applyVisibleView376(OField self) {
		if(self.scrollPane!=null) self.scrollPane.setVisible(self.isShown());
	}

	// from OField (instance)
	/** Lines of OField.applyVisible (the core calls this in their place). */
	static void applyVisibleView375(OField self) {
		if(self.fld!=null) self.fld.setVisible(self.isShown());
	}

	// from OField (instance)
	/** Lines of OField.setRect (the core calls this in their place). */
	static void setRectView213(OField self, int v1, int v2, int v3, int v4, int oldLeft, int oldTop, int oldWidth, int oldHeight) {
		if(self.fld!=null){
			final int newLeft=self.left;
			final int newTop=self.top;
			final int newWidth=self.width;
			final int newHeight=self.height;
			Host.get().runOnEDTWhenShown(self, Host.EDT_BOUNDS, () -> {
				JComponent component=self.getComponent();
				if(component==null) return;
				component.setBounds(newLeft, newTop, newWidth, newHeight);
				if(!HZ.lockedScreen) {
					Host.get().repaintLogicalRect(newLeft, newTop, newWidth, newHeight);
					Host.get().repaintLogicalRect(oldLeft, oldTop, oldWidth, oldHeight);
				}
			}, self::applyBounds);
		}
	}

	// from OField (instance)
	/** Lines of OField.setTopLeft (the core calls this in their place). */
	static void setTopLeftView181(OField self, int h, int v, int oldLeft, int oldTop) {
		if(self.fld!=null){
			final int newLeft=self.left;
			final int newTop=self.top;
			final int fieldWidth=self.width;
			final int fieldHeight=self.height;
			Host.get().runOnEDTWhenShown(self, Host.EDT_BOUNDS, () -> {
				JComponent component=self.getComponent();
				if(component==null) return;
				component.setBounds(newLeft, newTop, fieldWidth, fieldHeight);
				if(!HZ.lockedScreen) {
					if(Math.abs(newLeft-oldLeft)<fieldWidth && Math.abs(newTop-oldTop)<fieldHeight)
						Host.get().repaintLogicalRect(Math.min(newLeft,oldLeft), Math.min(newTop,oldTop), fieldWidth+Math.abs(newLeft-oldLeft), fieldHeight+Math.abs(newTop-oldTop));
					else {
						Host.get().repaintLogicalRect(newLeft, newTop, fieldWidth, fieldHeight);
						Host.get().repaintLogicalRect(oldLeft, oldTop, fieldWidth, fieldHeight);
					}
				}
			}, self::applyBounds);
		}
	}

	// from OCardBase (instance)
	static boolean restackComponents(OCardBase self) {
		PCARD pc = PCARD.pc;
		if(pc==null || pc.mainPane==null || pc.stack==null || pc.stack.curCard==null) return true;
		OCard cur = pc.stack.curCard;
		if(self!=cur && self!=cur.bg) return !(self.id==(self instanceof OBackground ? cur.bgid : cur.id)); //見えていないカード
		boolean[] done = {false};
		Runnable run = () -> done[0] = self.restackComponentsOnEDT(pc.mainPane);
		if(SwingUtilities.isEventDispatchThread()) run.run();
		else{
			try{ SwingUtilities.invokeAndWait(run); }
			catch(Exception e){ return false; }
		}
		return done[0];
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void newButtonView(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OButton obtn) throws xTalkException {
					obtn.btn = new MyButton(obtn, "");
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void newFieldView(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OField ofld) throws xTalkException {
					ofld.fld = new MyTextArea("");
	}

	// from TUtil
	/** Lines of TUtil.setPropertyNow (the core calls this in their place). */
	static void popupBoundsView(OObject obj, String inProperty, String value, OButton btn) throws xTalkException {
			if(btn.style==8 && btn.popup!=null) btn.popup.setBounds(btn.left, btn.top, btn.width, btn.height);
	}

	// from TUtil
	/** Lines of TUtil.setPropertyNow (the core calls this in their place). */
	static void buttonFontSizeView(OObject obj, String inProperty, String value, OButton btn, int size) throws xTalkException {
				if(btn.getComponent()!=null) btn.getComponent().setFont(OObject.stackFont(btn.textFont, btn.textStyle & 0x03, size*Host.get().scale()));
	}

	// from TUtil
	/** Lines of TUtil.setPropertyNow (the core calls this in their place). */
	static void buttonFontStyleView(OObject obj, String inProperty, String value, OButton btn, int style) throws xTalkException {
				if(btn.getComponent()!=null) btn.getComponent().setFont(OObject.stackFont(btn.textFont, style & 0x03, btn.textSize*Host.get().scale()));
	}

	// from OCardBase (instance)
	/** Lines of OCardBase.removeData (the core calls this in their place). */
	static void clearCardPane(OCardBase self) {
		if(SwingUtilities.isEventDispatchThread()){
			PCARDFrame.pc.mainPane.removeAll();
		}else{
			try{
				SwingUtilities.invokeAndWait(new Runnable(){
					public void run(){
						PCARDFrame.pc.mainPane.removeAll();
					}
				});
			}catch(Exception e){
				PCARDFrame.pc.mainPane.removeAll();
			}
		}
	}

	// from OCardBase (instance)
	/** Lines of OCardBase.removeData (the core calls this in their place). */
	static void dropButtonViews(OCardBase self) {
		for(int i=0; i<self.btnList.size(); i++) {
			if(self.btnList.get(i).btn!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).btn);
				self.btnList.get(i).btn.btnData = null;
				self.btnList.get(i).btn=null;
			}
			if(self.btnList.get(i).chkbox!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).chkbox);
				self.btnList.get(i).chkbox.btnData = null;
				self.btnList.get(i).chkbox=null;
			}
			if(self.btnList.get(i).radio!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).radio);
				self.btnList.get(i).radio.btnData = null;
				self.btnList.get(i).radio=null;
			}
			if(self.btnList.get(i).popup!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).popup);
				self.btnList.get(i).popup.btnData = null;
				self.btnList.get(i).popup=null;
			}
			//btnList.get(i).card = null;
			//btnList.get(i).bkgd = null;
		}
	}

	// from OCardBase (instance)
	/** Lines of OCardBase.removeData (the core calls this in their place). */
	static void dropFieldViews(OCardBase self) {
		for(int i=0; i<self.fldList.size(); i++) {
			if(self.fldList.get(i).fld!=null){
				//PCARD.pc.mainPane.remove(fldList.get(i).fld);
				self.fldList.get(i).fld.fldData = null;
				self.fldList.get(i).fld=null;
			}
			//fldList.get(i).card = null;
			//fldList.get(i).bkgd = null;
		}
	}

	// from OField (instance)
	static void rebuildComponent(OField self) {
		if(self.fld==null) return;
		Host.get().runOnEDT(() -> {
			JComponent old = self.getComponent();
			if(old==null || PCARD.pc==null || PCARD.pc.mainPane==null) return;
			java.awt.Container pane = old.getParent();
			int z = pane==null ? -1 : pane.getComponentZOrder(old);
			if(pane!=null) pane.remove(old);
			FieldTable table = self.table();
			if(table!=null) table.dispose();
			FieldOutline outline = self.outline();
			if(outline!=null) outline.dispose();
			if(self.scrollPane!=null && self.card!=null && self.card.paneList!=null) self.card.paneList.remove(self.scrollPane);
			self.removeListener();
			self.fld.fldData = null;
			self.fld = null;
			self.scrollPane = null;
			buildOField(self);
			JComponent now = self.getComponent();
			if(now!=null && now.getParent()!=null && z>=0)
				now.getParent().setComponentZOrder(now, Math.min(z, now.getParent().getComponentCount()-1));
			if(!HZ.lockedScreen) Host.get().repaintLogicalRect(self.left, self.top, self.width, self.height);
		});
	}

	// from OField (instance)
	static void showFont(OField self, Font font) {
		MyTextArea c = self.fld;
		if(c!=null) Host.get().runOnEDT(() -> c.setFont(font));
	}

	// from OField (instance)
	static void showLineWrap(OField self, boolean wrap) {
		MyTextArea c = self.fld;
		if(c!=null) Host.get().runOnEDT(() -> c.setLineWrap(wrap));
	}

	// from OField (instance)
	static void applyMargin(OField self) {
		if(self.fld==null) return;
		int margin = self.textMargin(Host.get().scale(), self.wideMargins);
		int topMargin = self.textTopMargin(Host.get().scale(), self.wideMargins);
		self.fld.setMargin(new Insets(topMargin, margin, topMargin, margin));
	}

	// from OField (instance)
    static void updateTextEditable(OField self) {
		if(self.fld==null) return;
		//ユーザーレベル1(ブラウズ)ではロックされていないフィールドにも入力できない(HyperCard)
		boolean editable = !self.enabled && HZ.editMode==0 && !Host.get().authToolActive() && UiHost.get().stackProtectionEffectiveUserLevel()>=2;
		self.fld.setEditable(editable);
		self.fld.setFocusable(editable);
		//表・アウトラインはテキスト欄を画面に出さないので、部品の方にも入力の可否を知らせる
		//(フィールドツールで作り直された部品は、ブラウズツールに戻っても入力できないままだった)
		FieldTable table = self.table();
		if(table!=null) Host.get().runOnEDT(table::editableChanged);
		FieldOutline outline = self.outline();
		if(outline!=null) Host.get().runOnEDT(outline::editableChanged);
	}

	// from TTalk (instance)
	/** Lines of TTalk.deletePartCommand (the core calls this in their place). */
	static void removeFromCardPane(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, MemoryData memData, OObject object, OObject target, javax.swing.JComponent comp) throws xTalkException {
			if(PCARD.pc.mainPane!=null){
				if(comp!=null) PCARD.pc.mainPane.remove(comp);
				OCard.reloadCurrentCard();
			}
	}

	// from OField (instance)
	static FieldTable table(OField self) {
		return self.scrollPane instanceof FieldTable ? (FieldTable)self.scrollPane : null;
	}

	// from OField (instance)
	static FieldOutline outline(OField self) {
		return self.scrollPane instanceof FieldOutline ? (FieldOutline)self.scrollPane : null;
	}

	// from OField (instance)
	static void refreshTable(OField self) {
		FieldTable table = self.table();
		if(table!=null) Host.get().runOnEDT(() -> { table.applyAppearance(); table.reload(); });
		FieldOutline outline = self.outline();
		if(outline!=null) Host.get().runOnEDT(() -> { outline.applyAppearance(); outline.reload(); });
	}

	// from OShape (instance)
	static void applyShown(OShape self) {if(self.view!=null)Host.get().runOnEDTWhenShown(()->{if(self.view!=null){self.view.setVisible(self.isShown());self.view.repaint();}});}

	// from OShape (instance)
	static void moveView(OShape self) {if(self.view==null)return;int pad=self.padding();self.view.setBounds(Host.get().toPhysical(self.left-pad,self.top-pad,self.width+2*pad,self.height+2*pad));}
}

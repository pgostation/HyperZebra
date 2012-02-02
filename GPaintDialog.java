import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog.ModalExclusionType;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.util.ArrayList;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

class ColorConvertDialog extends JFrame implements ActionListener {
	private static final long serialVersionUID = 1L;
	private PCARDFrame owner;
	private Color[] srcColorBest = new Color[16];
	private Color[] dstColorBest = new Color[16];
	private int near = 10;
	private BufferedImage save_bi;
	
	ColorConvertDialog(PCARDFrame owner) {
		super();
		setModalExclusionType(ModalExclusionType.APPLICATION_EXCLUDE);
		this.owner = owner;
		getContentPane().setLayout(null);
		setTitle(PCARD.pc.intl.getDialogText("Color Convert"));

		if(owner.tool instanceof toolSelectInterface &&
				((toolSelectInterface)owner.tool).getSelectedSurface(owner)!=null)
		{
		}else{
			GMenuPaint.setUndo();
		}
		
		makeColors(30); //類似度の初期値30で開く
		
		setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-160+12,400,320);
		
		setResizable(false);
		setVisible(true);
	}

	private void makeColors(int near)
	{
		this.near = near;
		
		this.getContentPane().removeAll();
		this.getContentPane().repaint();

		this.getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		
		BufferedImage bi;
		if(owner.tool instanceof toolSelectInterface &&
				((toolSelectInterface)owner.tool).getSelectedSurface(owner)!=null)
		{
			toolSelectInterface tl = (toolSelectInterface)owner.tool;
			bi = tl.getSelectedSurface(owner);
			save_bi = tl.getSelectedSurface(owner);
		}else{
			bi = owner.getSurface();
		}
		
		if(bi==null){
			return;
		}
		
		//代表色ピックアップ
		ArrayList<Color> srcColors = new ArrayList<Color>();
		ArrayList<Integer> srcColorCount = new ArrayList<Integer>();
		int width = bi.getWidth();
		int height = bi.getHeight();
		DataBuffer db = bi.getRaster().getDataBuffer();
		int lasti = -1;
		
		//含まれる色のリストを作成
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int d = db.getElem(h+v*width);
				if(((d>>24)&0xFF)==0) continue;//透明色は含まない
				Color c = new Color((d>>16)&0xFF, (d>>8)&0xFF, (d>>0)&0xFF);
				boolean isFound = false;
				if(lasti>=0 && isNearHSV(d, srcColors.get(lasti), near)){
					//既に見つけた色
					isFound = true;
					srcColorCount.set(lasti,srcColorCount.get(lasti)+1);
				}
				else{
					for(int i=0; i<srcColors.size(); i++)
					{
						if(isNear(d, srcColors.get(i), near)){
							isFound = true;
							srcColorCount.set(i,srcColorCount.get(i)+1);
							lasti = i;
							break;
						}
					}
					if(!isFound) {
						if(srcColors.size()>1024) break;
						srcColors.add(c);
						srcColorCount.add(1);
					}
				}
			}
		}
	
		//含まれる色を上位順に並べる。このとき上位にあるものと色相の差がある程度なければいけない。
		int[] srcColorCountBest = new int[16];
		for(int j=0; j<srcColorCountBest.length; j++)
		{
			srcColorCountBest[j] = 0;
			srcColorBest[j] = new Color(0,0,0);
			dstColorBest[j] = new Color(0,0,0);
		}

		for(int i=0; i<srcColorCount.size(); i++)
		{
			for(int j=0; j<srcColorCountBest.length; j++)
			{
				if(srcColorCountBest[j]<srcColorCount.get(i)){
					boolean nearColorFound = false;
					for(int m=0; m<j; m++){
						if(isNearHSV(srcColors.get(i), srcColorBest[m], near)){
							nearColorFound = true;
							/*srcColorBest[m] = srcColors.get(i);
							dstColorBest[m] = srcColors.get(i);
							srcColorCountBest[m] = srcColorCount.get(i);*/
							break;
						}
					}
					if(!nearColorFound){
						for(int k=srcColorCountBest.length-1; k>=j+1; k--){
							srcColorBest[k] = srcColorBest[k-1];
							dstColorBest[k] = dstColorBest[k-1];
							srcColorCountBest[k] = srcColorCountBest[k-1];
						}
						srcColorBest[j] = srcColors.get(i);
						dstColorBest[j] = srcColors.get(i);
						srcColorCountBest[j] = srcColorCount.get(i);
					}
					break;
				}
			}
		}
		
		//変更前の色ボタン
		for(int i=0; i<10; i++){
			if(srcColorCountBest[i]==0) break;
			JButton button = new JButton();
			BufferedImage icon_image = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
			Graphics icon_g = icon_image.createGraphics();
			icon_g.setColor(srcColorBest[i]);
			icon_g.fillRect(0,0,16,12);
			button.setIcon(new ImageIcon(icon_image));
			button.setName("srcColor "+i);
			button.setText(""+i);
			button.setBounds(40,10+25*i,60,20);
			button.setMargin(new Insets(0,0,0,0));
			button.addActionListener(this);
			getContentPane().add(button);
		}
	
		//変更後の色ボタン
		for(int i=0; i<10; i++){
			if(srcColorCountBest[i]==0) break;
			JButton button = new JButton();
			BufferedImage icon_image = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
			Graphics icon_g = icon_image.createGraphics();
			icon_g.setColor(srcColorBest[i]);
			icon_g.fillRect(0,0,16,12);
			button.setIcon(new ImageIcon(icon_image));
			button.setName("dstColor "+i);
			button.setText(""+i);
			button.setBounds(280,10+25*i,60,20);
			button.setMargin(new Insets(0,0,0,0));
			button.addActionListener(this);
			getContentPane().add(button);
		}

		//類似色判定を厳しくする、緩くするボタン
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("Unite"));
			button.setBounds(130,100,100,25);
			button.setName("Unite");
			button.addActionListener(this);
			getContentPane().add(button);
		}
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("Divide"));
			button.setBounds(130,150,100,25);
			button.setName("Divide");
			button.addActionListener(this);
			getContentPane().add(button);
		}
		
		//ok, cancel
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("OK"));
			button.setBounds(200,270,100,25);
			button.setName("OK");
			button.addActionListener(this);
			getContentPane().add(button);
		}
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("Cancel"));
			button.setBounds(90,270,100,25);
			button.setName("Cancel");
			button.addActionListener(this);
			getContentPane().add(button);
		}		
		
		this.getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
	}
	

	private final boolean isNear(int argb, Color color, int near){
		if(Math.abs(color.getRed()-((argb>>16)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getGreen()-((argb>>8)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getBlue()-((argb>>0)&0xFF))>near){
			return false;
		}
		return true;
	}

	
	private final boolean isNearHSV(Color color1, Color color2, int near){
		float[] hsb1 = Color.RGBtoHSB(color1.getRed(), color1.getGreen(), color1.getBlue(), null);
		float[] hsb2 = Color.RGBtoHSB(color2.getRed(), color2.getGreen(), color2.getBlue(), null);
		
		float d = 5*Math.abs(hsb1[0] - hsb2[0])*Math.max(0.0f, hsb1[1]+hsb2[1]-0.1f)*Math.max(0.0f, hsb1[2]+hsb2[2]-0.1f)
		+ 3*Math.abs(hsb1[1] - hsb2[1])*Math.max(0.0f, hsb1[2]+hsb2[2]-0.1f)
		+ 2*Math.abs(hsb1[2] - hsb2[2]);
		
		if((int)(d*30)>near) return false;
		return true;
	}

	
	private final boolean isNearHSV(int color1, Color color2, int near){
		float[] hsb1 = Color.RGBtoHSB((color1>>16)&0xFF, (color1>>8)&0xFF, (color1>>0)&0xFF, null);
		float[] hsb2 = Color.RGBtoHSB(color2.getRed(), color2.getGreen(), color2.getBlue(), null);

		float d = 5*Math.abs(hsb1[0] - hsb2[0])*Math.max(0.0f, hsb1[1]+hsb2[1]-0.1f)*Math.max(0.0f, hsb1[2]+hsb2[2]-0.1f)
		+ 3*Math.abs(hsb1[1] - hsb2[1])*Math.max(0.0f, hsb1[2]+hsb2[2]-0.1f)
		+ 2*Math.abs(hsb1[2] - hsb2[2]);
		
		if((int)(d*30)>near) return false;
		return true;
	}


	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JButton)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getDialogEngText(name);
		if(cmd!=null && cmd.equals("Cancel")){
			owner.redoBuf = save_bi;
			this.dispose();
			owner.mainPane.repaint();
			return;
		}
		else if(cmd!=null && cmd.equals("Divide")){
			makeColors(near/2+2);
		}
		else if(cmd!=null && cmd.equals("Unite")){
			makeColors((near-2)*2);
		}
		
		//キャプチャでの色選択を実現するために別スレッド
		ColorConvertThread thread = new ColorConvertThread(this, name, cmd, e);
		thread.start();
	}
	
	class ColorConvertThread extends Thread {
		ColorConvertDialog owner;
		String name;
		String cmd;
		ActionEvent e;
		
		ColorConvertThread(ColorConvertDialog owner, String name, String cmd, ActionEvent e){
			super();
			this.owner = owner;
			this.name = name;
			this.cmd = cmd;
			this.e = e;
		}
		
		public void run(){

			if(name.startsWith("src")){
				int number = Integer.valueOf(name.split(" ")[1]);
				//Color col = JColorChooser.showDialog(PaintTool.owner, "Source Color", srcColorBest[number] );
				Color col = GColorDialog.getColor(null, srcColorBest[number], true);
				if(col != null){
					srcColorBest[number] = col;
					BufferedImage icon_image = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
					Graphics icon_g = icon_image.createGraphics();
					icon_g.setColor(srcColorBest[number]);
					icon_g.fillRect(0,0,16,12);
					((JButton)e.getSource()).setIcon(new ImageIcon(icon_image));
				}
			}
			else if(name.startsWith("dst")){
				int number = Integer.valueOf(name.split(" ")[1]);
				//Color col = JColorChooser.showDialog(PaintTool.owner, "Destination Color", dstColorBest[number] );
				Color col = GColorDialog.getColor(null, dstColorBest[number], true);
				if(col != null){
					dstColorBest[number] = col;
					BufferedImage icon_image = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
					Graphics icon_g = icon_image.createGraphics();
					icon_g.setColor(dstColorBest[number]);
					icon_g.fillRect(0,0,16,12);
					((JButton)e.getSource()).setIcon(new ImageIcon(icon_image));
				}
			}
			
			if(owner.owner.tool instanceof toolSelectInterface &&
					((toolSelectInterface)owner.owner.tool).getSelectedSurface(owner.owner)!=null)
			{
				//まず新しいバッファを作成してクリア
				BufferedImage bi2 = new BufferedImage(save_bi.getWidth(), save_bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D dst_g = bi2.createGraphics();
				dst_g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
				Rectangle2D.Double rect = new Rectangle2D.Double(0,0,save_bi.getWidth(), save_bi.getHeight());
				dst_g.fill(rect);
				dst_g = bi2.createGraphics();
				dst_g.drawImage(save_bi, 0, 0, null);

				//操作
				DataBuffer surbuf = bi2.getRaster().getDataBuffer();
				if(cmd.equals("OK")){
					toolSelectInterface tl = (toolSelectInterface)owner.owner.tool;
					surbuf = tl.getSelectedSurface(owner.owner).getRaster().getDataBuffer();
				}
				int width = bi2.getWidth();
				int height = bi2.getHeight();
				float[] srchsb = new float[3];
				float[] dsthsb = new float[3];
				float[] colhsb = new float[3];
				for(int v=0; v<height; v++){
					for(int h=0; h<width; h++){
						int c = surbuf.getElem(h+v*width);
						//srcColorBestに近い色相があれば色を変換
						for(int i=0; i<srcColorBest.length; i++){
							if(isNearHSV(c, srcColorBest[i], near+2) && !dstColorBest[i].equals(srcColorBest[i])){
								Color.RGBtoHSB(srcColorBest[i].getRed(), srcColorBest[i].getGreen(), srcColorBest[i].getBlue(), srchsb);
								Color.RGBtoHSB(dstColorBest[i].getRed(), dstColorBest[i].getGreen(), dstColorBest[i].getBlue(), dsthsb);
								Color.RGBtoHSB((c>>16)&0xFF, (c>>8)&0xFF, (c>>0)&0xFF, colhsb);
								float cols = colhsb[1]+dsthsb[1]-srchsb[1];
								if(cols<0.0f) cols = 0.0f;
								else if(cols>1.0f) cols = 1.0f;
								float colb = colhsb[2]+dsthsb[2]-srchsb[2];
								if(colb<0.0f) colb = 0.0f;
								else if(colb>1.0f) colb = 1.0f;
								float colh = colhsb[0]+dsthsb[0]-srchsb[0];
								if(srchsb[0] < 0.2){
									colh = dsthsb[0];
								}
								Color newcolor = Color.getHSBColor(colh, cols, colb);
								int d = (c&0xFF000000) + (newcolor.getRed()<<16) + (newcolor.getGreen()<<8) +(newcolor.getBlue());
								surbuf.setElem(h+v*width, d);
								break;
							}
						}
					}
				}
				
				if(cmd.equals("OK")){
					owner.dispose();//this.dispose();
					owner.owner.mainPane.repaint();
					return;
				}

				//選択領域を新しくする
				owner.owner.redoBuf = bi2;
				owner.owner.mainPane.repaint();
			}
			else{
				//サーフェース全体
				
				//まずredoBufにコピー
				BufferedImage bi = owner.owner.redoBuf;
				if(bi==null) {
					bi = new BufferedImage(owner.owner.getSurface().getWidth(), owner.owner.getSurface().getHeight(), BufferedImage.TYPE_INT_ARGB);
				}
				Graphics2D dst_g = bi.createGraphics();
				dst_g.drawImage(owner.owner.getSurface(),0,0,null);
				
				//操作(HSB空間での平行移動)
				DataBuffer surbuf = bi.getRaster().getDataBuffer();
				int width = bi.getWidth();
				int height = bi.getHeight();
				float[] srchsb = new float[3];
				float[] dsthsb = new float[3];
				float[] colhsb = new float[3];
				for(int v=0; v<height; v++){
					for(int h=0; h<width; h++){
						int c = surbuf.getElem(h+v*width);
						//srcColorBestに近い色相があれば色を変換
						for(int i=0; i<srcColorBest.length; i++){
							if(isNearHSV(c, srcColorBest[i], near+2) && !dstColorBest[i].equals(srcColorBest[i])){
								Color.RGBtoHSB(srcColorBest[i].getRed(), srcColorBest[i].getGreen(), srcColorBest[i].getBlue(), srchsb);
								Color.RGBtoHSB(dstColorBest[i].getRed(), dstColorBest[i].getGreen(), dstColorBest[i].getBlue(), dsthsb);
								Color.RGBtoHSB((c>>16)&0xFF, (c>>8)&0xFF, (c>>0)&0xFF, colhsb);
								float cols = colhsb[1]+dsthsb[1]-srchsb[1];
								if(cols<0.0f) cols = 0.0f;
								else if(cols>1.0f) cols = 1.0f;
								float colb = colhsb[2]+dsthsb[2]-srchsb[2];
								if(colb<0.0f) colb = 0.0f;
								else if(colb>1.0f) colb = 1.0f;
								float colh = colhsb[0]+dsthsb[0]-srchsb[0];
								if(srchsb[0] < 0.2){
									colh = dsthsb[0];
								}
								Color newcolor = Color.getHSBColor(colh, cols, colb);
								int d = (c&0xFF000000) + (newcolor.getRed()<<16) + (newcolor.getGreen()<<8) +(newcolor.getBlue());
								surbuf.setElem(h+v*width, d);
								break;
							}
						}
					}
				}
				/*for(int v=0; v<height; v++){ //RGBでの平行移動
					for(int h=0; h<width; h++){
						int c = surbuf.getElem(h+v*width);
						int srcred = (c>>16)&0x00FF;
						int srcgreen = (c>>8)&0x00FF;
						int srcblue = (c>>0)&0x00FF;
						//srcColorBestに近い色相があれば色を変換
						for(int i=0; i<srcColorBest.length; i++){
							if(isNearHSV(c, srcColorBest[i], near+2) && !dstColorBest[i].equals(srcColorBest[i])){
								int dstred = srcred + dstColorBest[i].getRed() - srcColorBest[i].getRed();
								int dstgreen = srcgreen + dstColorBest[i].getGreen() - srcColorBest[i].getGreen();
								int dstblue = srcblue + dstColorBest[i].getBlue() - srcColorBest[i].getBlue();
								if(dstred>0xFF) dstred = 0xFF;
								else if(dstred<0) dstred = 0;
								if(dstgreen>0xFF) dstgreen = 0xFF;
								else if(dstgreen<0) dstgreen = 0;
								if(dstblue>0xFF) dstblue = 0xFF;
								else if(dstblue<0) dstblue = 0;
								int d = (c&0xFF000000) + ((0x00FF&dstred)<<16) + ((0x00FF&dstgreen)<<8) + (0x00FF&dstblue);
								surbuf.setElem(h+v*width, d);
								break;
							}
						}
					}
				}*/
				//redoBufとmainImgを入れ替え
				BufferedImage savebi = owner.owner.getSurface();
				
				//選択領域を新しくする
				owner.owner.mainImg = bi;
				if(cmd.equals("OK")){
					if(savebi!=null){
						owner.owner.redoBuf = savebi;
					}
					owner.dispose();//this.dispose();
					owner.owner.mainPane.repaint();
					return;
				}
				
				owner.owner.mainPane.paintImmediately(owner.owner.mainPane.getBounds());
				
				owner.owner.setSurface(savebi);
				if(bi!=null){
					owner.owner.redoBuf = bi;
				}
			}
			
		}
	}
}


class EmbossDialog extends JFrame implements ActionListener {
	private static final long serialVersionUID = 1L;
	private PCARDFrame owner;
	
	EmbossDialog(PCARDFrame owner) {
		super();
		setModalExclusionType(ModalExclusionType.APPLICATION_EXCLUDE);
		this.owner = owner;
		getContentPane().setLayout(new BorderLayout());
		setTitle(PCARD.pc.intl.getDialogText("Emboss"));
		
		SetDialogContents();
		
		setBounds(owner.getX()+owner.getWidth()/2-240,owner.getY()+owner.getHeight()/2-240-20,480,480);
		
		setResizable(false);
		setVisible(true);
		
		previewImage("");
	}

	private void SetDialogContents()
	{
		this.getContentPane().removeAll();
		
		if(!(owner.tool instanceof toolSelectInterface) ||
				((toolSelectInterface)owner.tool).getSelectedSurface(owner)==null)
		{
			GMenuPaint.setUndo();
			
			GMenuPaint.doMenu("Select All");
		}
		
		JPanel mainpanel = new JPanel();
		mainpanel.setLayout(new GridLayout(2,2));
		getContentPane().add("Center", mainpanel);
		
		//厚み -on/off
		//--明るさの変化(0-128)
		//--端からの距離(0-32)
		//--方向(x,y)
		{
			JPanel panel = new JPanel();
			panel.setLayout(new GridLayout(4,1));
			Border aquaBorder = UIManager.getBorder("TitledBorder.aquaVariant");
			if(aquaBorder==null){
				aquaBorder = new EtchedBorder();
			}
			panel.setBorder( new TitledBorder( aquaBorder, PCARD.pc.intl.getDialogText("Thickness") ) );
			mainpanel.add(panel);

			{
				JCheckBox chkbox = new JCheckBox(PCARD.pc.intl.getDialogText("Use"));
				chkbox.setName("Thick-Use");
				chkbox.setPreferredSize(new Dimension(180,24));
				chkbox.setSelected(useThick);
				chkbox.addActionListener(this);
				panel.add(chkbox);
			}

			{
				String name = PCARD.pc.intl.getDialogText("Brightness ");
				String[] value = new String[]{name+"0",name+"1",name+"2",name+"3",name+"4",name+"5",name+"6",name+"7",name+"8"};
				JComboBox combo = new JComboBox(value);
				combo.setName("Thick-Brightness");
				combo.setSelectedIndex(thickBright);
				combo.setMaximumRowCount(16);
				combo.setEnabled(useThick);
				combo.addActionListener(this);
				panel.add(combo);
			}
			
			{
				String name = PCARD.pc.intl.getDialogText("Width ");
				String[] value = new String[]{name+"0",name+"1",name+"2",name+"3",name+"4",name+"5",name+"6",name+"7",name+"8",name+"9",name+"10"};
				JComboBox combo = new JComboBox(value);
				combo.setName("Thick-Width");
				combo.setSelectedIndex(thickWidth);
				combo.setMaximumRowCount(16);
				combo.setEnabled(useThick);
				combo.addActionListener(this);
				panel.add(combo);
			}

			JPanel arrowpanel = new JPanel();
			arrowpanel.setLayout(new BoxLayout(arrowpanel, BoxLayout.X_AXIS));
			arrowpanel.setOpaque(false);
			panel.add(arrowpanel);
			
			{
				JToggleButton button = new JToggleButton("◀");
				button.setName("Thick-Left");
				button.setPreferredSize(new Dimension(32,25));
				button.setEnabled(useThick);
				button.setSelected(thickLeft);
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JToggleButton button = new JToggleButton("▶");
				button.setName("Thick-Right");
				button.setPreferredSize(new Dimension(32,25));
				button.setEnabled(useThick);
				button.setSelected(thickRight);
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JToggleButton button = new JToggleButton("▲");
				button.setName("Thick-Up");
				button.setPreferredSize(new Dimension(32,25));
				button.setEnabled(useThick);
				button.setSelected(thickUp);
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JToggleButton button = new JToggleButton("▼");
				button.setName("Thick-Down");
				button.setPreferredSize(new Dimension(32,25));
				button.setEnabled(useThick);
				button.setSelected(thickDown);
				button.addActionListener(this);
				arrowpanel.add(button);
			}
		}

		//グラデーション -on/off
		//--明るさの変化(0-128)
		//--方向(0-360)
		{
			JPanel panel = new JPanel();
			panel.setLayout(new GridLayout(4,1));
			Border aquaBorder = UIManager.getBorder("TitledBorder.aquaVariant");
			if(aquaBorder==null){
				aquaBorder = new EtchedBorder();
			}
			panel.setBorder( new TitledBorder( aquaBorder, PCARD.pc.intl.getDialogText("Gradation") ) );
			mainpanel.add(panel);

			{
				JCheckBox chkbox = new JCheckBox(PCARD.pc.intl.getDialogText("Use"));
				chkbox.setName("Gradation-Use");
				chkbox.setPreferredSize(new Dimension(180,24));
				chkbox.setSelected(useGrad);
				chkbox.addActionListener(this);
				panel.add(chkbox);
			}

			{
				String name = PCARD.pc.intl.getDialogText("Brightness ");
				String[] value = new String[]{name+"0",name+"1",name+"2",name+"3",name+"4",name+"5",name+"6",name+"7",name+"8"};
				JComboBox combo = new JComboBox(value);
				combo.setName("Gradation-Brightness");
				combo.setSelectedIndex(gradBright);
				combo.setMaximumRowCount(16);
				combo.setEnabled(useGrad);
				combo.addActionListener(this);
				panel.add(combo);
			}

			JPanel arrowpanel = new JPanel();
			arrowpanel.setLayout(new BoxLayout(arrowpanel, BoxLayout.X_AXIS));
			arrowpanel.setOpaque(false);
			panel.add(arrowpanel);
			
			{
				JButton button = new JButton("◀");
				button.setName("Gradation-Left");
				button.setPreferredSize(new Dimension(32,25));
				button.setEnabled(useGrad);
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▶");
				button.setName("Gradation-Right");
				button.setPreferredSize(new Dimension(32,25));
				button.setEnabled(useGrad);
				button.addActionListener(this);
				arrowpanel.add(button);
			}
		}

		//ハイライト -on/off
		//--検出強度
		//--明るさの変化(0-128)
		//--範囲(0-16)
		//--オフセット(x,y)
		{
			JPanel panel = new JPanel();
			panel.setLayout(new GridLayout(4,1));
			Border aquaBorder = UIManager.getBorder("TitledBorder.aquaVariant");
			if(aquaBorder==null){
				aquaBorder = new EtchedBorder();
			}
			panel.setBorder( new TitledBorder( aquaBorder, PCARD.pc.intl.getDialogText("Highlight") ) );
			mainpanel.add(panel);

			{
				JCheckBox chkbox = new JCheckBox(PCARD.pc.intl.getDialogText("Use"));
				chkbox.setName("Highlight-Use");
				chkbox.setPreferredSize(new Dimension(180,24));
				chkbox.setSelected(useHighlight);
				chkbox.addActionListener(this);
				panel.add(chkbox);
			}

			{
				String name = PCARD.pc.intl.getDialogText("Brightness ");
				String[] value = new String[]{name+"0",name+"1",name+"2",name+"3",name+"4",name+"5",name+"6",name+"7",name+"8"};
				JComboBox combo = new JComboBox(value);
				combo.setName("Highlight-Brightness");
				combo.setSelectedIndex(highlBright);
				combo.setMaximumRowCount(16);
				combo.addActionListener(this);
				panel.add(combo);
			}
			
			{
				String name = PCARD.pc.intl.getDialogText("Area ");
				String[] value = new String[]{name+"0",name+"1",name+"2",name+"3",name+"4",name+"5",name+"6",name+"7",name+"8"};
				JComboBox combo = new JComboBox(value);
				combo.setName("Highlight-Area");
				combo.setSelectedIndex(highlArea);
				combo.setMaximumRowCount(16);
				combo.addActionListener(this);
				panel.add(combo);
			}

			JPanel arrowpanel = new JPanel();
			arrowpanel.setLayout(new BoxLayout(arrowpanel, BoxLayout.X_AXIS));
			arrowpanel.setOpaque(false);
			panel.add(arrowpanel);
			
			{
				JButton button = new JButton("◀");
				button.setName("Highlight-Left");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▶");
				button.setName("Highlight-Right");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▲");
				button.setName("Highlight-Up");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▼");
				button.setName("Highlight-Down");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
		}

		//映り込み -on/off
		//--種類 (直線/弧)
		//--明るさの変化(0-128)
		//--オフセット(x,y)
		//--傾き(0-360)
		{
			JPanel panel = new JPanel();
			panel.setLayout(new GridLayout(4,1));
			Border aquaBorder = UIManager.getBorder("TitledBorder.aquaVariant");
			if(aquaBorder==null){
				aquaBorder = new EtchedBorder();
			}
			panel.setBorder( new TitledBorder( aquaBorder, PCARD.pc.intl.getDialogText("Reflection") ) );
			mainpanel.add(panel);

			{
				String[] value = new String[]{
						PCARD.pc.intl.getDialogText("None"), 
						PCARD.pc.intl.getDialogText("Line"), 
						PCARD.pc.intl.getDialogText("Curve"), 
						PCARD.pc.intl.getDialogText("Fit")};
				JComboBox combo = new JComboBox(value);
				combo.setName("Reflection-Style");
				combo.setSelectedIndex(1);
				combo.addActionListener(this);
				panel.add(combo);
			}

			{
				String name = PCARD.pc.intl.getDialogText("Brightness ");
				String[] value = new String[]{name+"0",name+"1",name+"2",name+"3",name+"4",name+"5",name+"6",name+"7",name+"8"};
				JComboBox combo = new JComboBox(value);
				combo.setName("Reflection-Brightness");
				combo.setSelectedIndex(3);
				combo.setMaximumRowCount(16);
				combo.addActionListener(this);
				panel.add(combo);
			}

			{
				String name = PCARD.pc.intl.getDialogText("Angle ");
				String[] value = new String[36];
				for(int i=0; i<36; i++){
					value[i] = name+Integer.toString(i*10);
				}
				JComboBox combo = new JComboBox(value);
				combo.setName("Reflection-Angle");
				combo.setSelectedIndex(3);
				combo.setMaximumRowCount(16);
				combo.addActionListener(this);
				panel.add(combo);
			}

			JPanel arrowpanel = new JPanel();
			arrowpanel.setLayout(new BoxLayout(arrowpanel, BoxLayout.X_AXIS));
			arrowpanel.setOpaque(false);
			panel.add(arrowpanel);
			
			{
				JButton button = new JButton("◀");
				button.setName("Reflection-Left");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▶");
				button.setName("Reflection-Right");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▲");
				button.setName("Reflection-Up");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
			{
				JButton button = new JButton("▼");
				button.setName("Reflection-Down");
				button.setPreferredSize(new Dimension(32,25));
				button.addActionListener(this);
				arrowpanel.add(button);
			}
		}
		
		//影は別のダイアログで。

		JPanel okpanel = new JPanel();
		getContentPane().add("South", okpanel);
		
		//ok, cancel
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("Cancel"));
			button.setBounds(90,270,100,25);
			button.setName("Cancel");
			button.addActionListener(this);
			okpanel.add(button);
		}
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("OK"));
			button.setBounds(200,270,100,25);
			button.setName("OK");
			button.addActionListener(this);
			okpanel.add(button);
		}
		
		this.getContentPane().repaint();
		this.setVisible(true);
	}
	
	private BufferedImage saveImage;
	
	private boolean useThick = true;
	private int thickBright = 3;
	private int thickWidth = 2;
	private boolean thickUp = true;
	private boolean thickDown = true;
	private boolean thickLeft = true;
	private boolean thickRight = true;

	private boolean useGrad = true;
	private int gradBright = 3;
	private float gradAngle = 0.0f;

	private boolean useHighlight = true;
	private int highlBright = 3;
	private int highlArea = 3;
	private Point highlOffset = new Point(0,0);
	
	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JComponent)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getDialogEngText(name);

		if(cmd==null) return;
		
		if(cmd.equals("Cancel")){
			if(saveImage!=null){
				owner.redoBuf = saveImage;
				owner.mainPane.repaint();
			}
			this.dispose();
			owner.mainPane.repaint();
			return;
		}
		else if(cmd.equals("Thick-Use")){
			useThick = ((JCheckBox)e.getSource()).isSelected();
			SetDialogContents();
		}
		else if(cmd.equals("Thick-Brightness")){
			thickBright = ((JComboBox)e.getSource()).getSelectedIndex();
			SetDialogContents();
		}
		else if(cmd.equals("Thick-Width")){
			thickWidth = ((JComboBox)e.getSource()).getSelectedIndex();
			SetDialogContents();
		}
		else if(cmd.equals("Thick-Up")){
			thickUp = ((JToggleButton)e.getSource()).isSelected();
		}
		else if(cmd.equals("Thick-Down")){
			thickDown = ((JToggleButton)e.getSource()).isSelected();
		}
		else if(cmd.equals("Thick-Left")){
			thickLeft = ((JToggleButton)e.getSource()).isSelected();
		}
		else if(cmd.equals("Thick-Right")){
			thickRight = ((JToggleButton)e.getSource()).isSelected();
		}
		else if(cmd.equals("Gradation-Use")){
			useGrad = ((JCheckBox)e.getSource()).isSelected();
			SetDialogContents();
		}
		else if(cmd.equals("Gradation-Brightness")){
			gradBright = ((JComboBox)e.getSource()).getSelectedIndex();
			SetDialogContents();
		}
		else if(cmd.equals("Gradation-Left")){
			gradAngle+=Math.PI/18.0;
		}
		else if(cmd.equals("Gradation-Right")){
			gradAngle-=Math.PI/18.0;
		}
		else if(cmd.equals("Highlight-Use")){
			useHighlight = ((JCheckBox)e.getSource()).isSelected();
			SetDialogContents();
		}
		else if(cmd.equals("Highlight-Brightness")){
			highlBright = ((JComboBox)e.getSource()).getSelectedIndex();
			SetDialogContents();
		}
		else if(cmd.equals("Highlight-Area")){
			highlArea = ((JComboBox)e.getSource()).getSelectedIndex();
			SetDialogContents();
		}
		else if(cmd.equals("Highlight-Up")){
			highlOffset.y--;
		}
		else if(cmd.equals("Highlight-Down")){
			highlOffset.y++;
		}
		else if(cmd.equals("Highlight-Left")){
			highlOffset.x--;
		}
		else if(cmd.equals("Highlight-Right")){
			highlOffset.x++;
		}
		
		this.getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

		previewImage(cmd);
	}
	
	
	private void previewImage(String cmd)
	{
		toolSelectInterface tl = (toolSelectInterface)owner.tool;
		BufferedImage srcimg = saveImage;
		if(saveImage==null){
			srcimg = tl.getSelectedSurface(owner);
			saveImage = srcimg;
		}
		Rectangle srcRect = tl.getSelectedRect();
		
		BufferedImage newimg = new BufferedImage(srcimg.getWidth(), srcimg.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newimg.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newimg.getWidth(), newimg.getHeight());

		DataBuffer srcdb = srcimg.getRaster().getDataBuffer();
		int width = srcimg.getWidth();
		//int height = srcimg.getHeight();
		
		/*if(useThick){
			//Thickness
			//輪郭に近い部分は暗く、離れたところは明るい
			int realThickWidth = new int[]{1,2,3,4,6,8,10,14,20,30,50}[thickWidth];
			for(int i=0; i<realThickWidth; i++){
				for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
					for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
						boolean src = (srcdb.getElem(x+y*width)&0xFF000000)!=0;
						if(src && i==0){
							//1回目は輪郭部分を塗る
							boolean srcup = false;
							if((y-1)>=srcRect.y) srcup = (srcdb.getElem(x+(y-1)*width)&0xFF000000)!=0;
							boolean srcdown = false;
							if((y+1)<srcRect.y+srcRect.height) srcdown = (srcdb.getElem(x+(y+1)*width)&0xFF000000)!=0;
							boolean srcleft = false;
							if((x-1)>=srcRect.x) srcleft = (srcdb.getElem(x-1+y*width)&0xFF000000)!=0;
							boolean srcright = false;
							if((x+1)<srcRect.x+srcRect.width) srcright = (srcdb.getElem(x+1+y*width)&0xFF000000)!=0;
							
							if(!srcup&thickDown || !srcdown&thickUp ||
									!srcleft&thickRight || !srcright&thickLeft){
								int v = srcdb.getElem(x+y*width);
								int red = (v>>16)&0xFF;
								int green = (v>>8)&0xFF;
								int blue = (v>>0)&0xFF;
								float[] hsb = Color.RGBtoHSB(red, green, blue, null);
								hsb[2] -= (double)thickBright*(realThickWidth-i)/realThickWidth/20.0f;
								if(hsb[2]<0.0f) hsb[2] = 0.0f;
								Color col = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
								int c = (0xFF000000&v)+(col.getRed()<<16)+(col.getGreen()<<8)+(col.getBlue());
								newdb.setElem(x+y*width, c);
							}
						}
						else if(src){
							//2回目以降はすでに塗った部分のすぐ隣を順次塗っていく
							if((0xFF000000&newdb.getElem(x+y*width))==0){
								boolean dstup = false;
								if((y-1)>=srcRect.y) dstup = (newdb.getElem(x+(y-1)*width)&0xFF000000)!=0;
								boolean dstdown = false;
								if((y+1)<srcRect.y+srcRect.height) dstdown = (newdb.getElem(x+(y+1)*width)&0xFF000000)!=0;
								boolean dstleft = false;
								if((x-1)>=srcRect.x) dstleft = (newdb.getElem(x-1+y*width)&0xFF000000)!=0;
								boolean dstright = false;
								if((x+1)<srcRect.x+srcRect.width) dstright = (newdb.getElem(x+1+y*width)&0xFF000000)!=0;
								
								//幅    :←外側,内側→
								//width1:-10*thickBright
								//width3:-10*thickBright,-5*thickBright,-0
								
								if(dstup&thickDown || dstdown&thickUp ||
										dstleft&thickRight || dstright&thickLeft){
									newdb.setElem(x+y*width, 0x00adbeef);
								}
							}
						}
					}
				}
				for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
					for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
						if(newdb.getElem(x+y*width)==0x00adbeef){
							int v = srcdb.getElem(x+y*width);
							int red = (v>>16)&0xFF;
							int green = (v>>8)&0xFF;
							int blue = (v>>0)&0xFF;

							float[] hsb = Color.RGBtoHSB(red, green, blue, null);
							hsb[2] -= (double)thickBright*(realThickWidth-i)/realThickWidth/20.0f;
							if(hsb[2]<0.0f) hsb[2] = 0.0f;
							Color col = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
							int c = (0xFF000000&v)+(col.getRed()<<16)+(col.getGreen()<<8)+(col.getBlue());
							newdb.setElem(x+y*width, c);
						}
					}
				}
			}
		}*/
		
		/* ぼかしでどうにかできないか？
		if(useThick){
			//Thickness
			//輪郭に近い部分は暗く、離れたところは明るい
			
			//輪郭を取得
			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
				for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
					boolean src = (srcdb.getElem(x+y*width)&0xFF000000)!=0;
					if(src){
						//輪郭部分を塗る
						boolean srcup = false;
						if((y-1)>=srcRect.y) srcup = (srcdb.getElem(x+(y-1)*width)&0xFF000000)!=0;
						boolean srcdown = false;
						if((y+1)<srcRect.y+srcRect.height) srcdown = (srcdb.getElem(x+(y+1)*width)&0xFF000000)!=0;
						boolean srcleft = false;
						if((x-1)>=srcRect.x) srcleft = (srcdb.getElem(x-1+y*width)&0xFF000000)!=0;
						boolean srcright = false;
						if((x+1)<srcRect.x+srcRect.width) srcright = (srcdb.getElem(x+1+y*width)&0xFF000000)!=0;
						
						if(!srcup&thickDown || !srcdown&thickUp ||
								!srcleft&thickRight || !srcright&thickLeft){
							newdb.setElem(x+y*width, 0xFF000000);
						}
					}
				}
			}
			
			//ぼかし効果
			int realThickWidth = new int[]{1,2,3,4,5,7,10,15,20,30,50}[thickWidth];
			for(int i=0; i<realThickWidth/5; i++){
				//平滑化(濃いめ)
				final float[] operator={
						0.00f, 0.02f, 0.05f, 0.05f, 0.05f, 0.02f, 0.00f,
						0.02f, 0.05f, 0.05f, 0.07f, 0.05f, 0.05f, 0.02f,
						0.05f, 0.05f, 0.07f, 0.08f, 0.07f, 0.05f, 0.05f,
						0.05f, 0.07f, 0.08f, 0.10f, 0.08f, 0.07f, 0.05f,
						0.05f, 0.05f, 0.07f, 0.08f, 0.07f, 0.05f, 0.05f,
						0.02f, 0.05f, 0.05f, 0.07f, 0.05f, 0.05f, 0.02f,
						0.00f, 0.02f, 0.05f, 0.05f, 0.05f, 0.02f, 0.00f,
					};
				if(!thickUp){
					for(int y=0; y<2; y++){
						for(int x=0; x<7; x++){
							operator[7*y+x] = 0.0f;
						}
					}
				}
				if(!thickDown){
					for(int y=5; y<7; y++){
						for(int x=0; x<7; x++){
							operator[7*y+x] = 0.0f;
						}
					}
				}
				if(!thickLeft){
					for(int y=0; y<7; y++){
						for(int x=0; x<2; x++){
							operator[7*y+x] = 0.0f;
						}
					}
				}
				if(!thickRight){
					for(int y=0; y<7; y++){
						for(int x=5; x<7; x++){
							operator[7*y+x] = 0.0f;
						}
					}
				}
				Kernel blur=new Kernel(7,7,operator);
				ConvolveOp convop=new ConvolveOp(blur,ConvolveOp.EDGE_NO_OP,null);
				newimg=convop.filter(newimg,null);
			}
			for(int i=0; i<realThickWidth%5; i++){
				//平滑化(濃いめ)
				final float[] operator={
						0.04f, 0.06f, 0.04f,
						0.06f, 0.90f, 0.06f,
						0.04f, 0.06f, 0.04f
					};
				Kernel blur=new Kernel(3,3,operator);
				ConvolveOp convop=new ConvolveOp(blur,ConvolveOp.EDGE_NO_OP,null);
				newimg=convop.filter(newimg,null);
			}
			
			//内部シャドウを塗る
			newdb = newimg.getRaster().getDataBuffer();
			for(int y=srcRect.y-realThickWidth; y<srcRect.y+srcRect.height+realThickWidth; y++){
				for(int x=srcRect.x-realThickWidth; x<srcRect.x+srcRect.width+realThickWidth; x++){
					if(x<0 || y<0 ||x>=width||y>=height) continue;
					boolean src = (srcdb.getElem(x+y*width)&0xFF000000)!=0;
					if(src){
						int newc = newdb.getElem(x+y*width);
						if((0xFF000000&newc)==0){
							newdb.setElem(x+y*width, srcdb.getElem(x+y*width));
						}
						else{
							int v = srcdb.getElem(x+y*width);
							int red = (v>>16)&0xFF;
							int green = (v>>8)&0xFF;
							int blue = (v>>0)&0xFF;

							float[] hsb = Color.RGBtoHSB(red, green, blue, null);
							hsb[2] += (float)(thickBright-3.5f)*((newc>>24)&0xFF)/realThickWidth/300.0f;
							if(hsb[2]>1.0f) hsb[2] = 1.0f;
							else if(hsb[2]<0.0f) hsb[2] = 0.0f;
							Color col = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
							int c = (0xFF000000&v)+(col.getRed()<<16)+(col.getGreen()<<8)+(col.getBlue());
							newdb.setElem(x+y*width, c);
						}
					}
					else{
						newdb.setElem(x+y*width, 0x00000000);
					}
				}
			}
		}*/
		

		if(useThick){
			//Thickness
			//輪郭に近い部分は暗く、離れたところは明るい
			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			
			int realThickWidth = new int[]{1,2,3,4,6,8,10,14,20,30,50}[thickWidth];
			for(int i=0; i<realThickWidth; i++){
				for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
					for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
						boolean src = (srcdb.getElem(x+y*width)&0xFF000000)!=0;
						if(src && i==0){
							//1回目は輪郭部分を塗る
							boolean srcup = false;
							if((y-1)>=srcRect.y) srcup = (srcdb.getElem(x+(y-1)*width)&0xFF000000)!=0;
							boolean srcdown = false;
							if((y+1)<srcRect.y+srcRect.height) srcdown = (srcdb.getElem(x+(y+1)*width)&0xFF000000)!=0;
							boolean srcleft = false;
							if((x-1)>=srcRect.x) srcleft = (srcdb.getElem(x-1+y*width)&0xFF000000)!=0;
							boolean srcright = false;
							if((x+1)<srcRect.x+srcRect.width) srcright = (srcdb.getElem(x+1+y*width)&0xFF000000)!=0;
							
							if(!srcup&thickDown || !srcdown&thickUp ||
									!srcleft&thickRight || !srcright&thickLeft){
								int v = srcdb.getElem(x+y*width);
								int red = (v>>16)&0xFF;
								int green = (v>>8)&0xFF;
								int blue = (v>>0)&0xFF;
								float[] hsb = Color.RGBtoHSB(red, green, blue, null);
								hsb[2] -= (double)thickBright*(realThickWidth-i)/realThickWidth/20.0f;
								if(hsb[2]<0.0f) hsb[2] = 0.0f;
								Color col = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
								int c = (0xFF000000&v)+(col.getRed()<<16)+(col.getGreen()<<8)+(col.getBlue());
								newdb.setElem(x+y*width, c);
							}
						}
						else if(src){
							//2回目以降はすでに塗った部分のすぐ隣を順次塗っていく
							if((0xFF000000&newdb.getElem(x+y*width))==0){
								boolean dstup = false;
								if((y-1)>=srcRect.y) dstup = (newdb.getElem(x+(y-1)*width)&0xFF000000)!=0;
								boolean dstdown = false;
								if((y+1)<srcRect.y+srcRect.height) dstdown = (newdb.getElem(x+(y+1)*width)&0xFF000000)!=0;
								boolean dstleft = false;
								if((x-1)>=srcRect.x) dstleft = (newdb.getElem(x-1+y*width)&0xFF000000)!=0;
								boolean dstright = false;
								if((x+1)<srcRect.x+srcRect.width) dstright = (newdb.getElem(x+1+y*width)&0xFF000000)!=0;

								//幅    :←外側,内側→
								//width1:-10*thickBright
								//width3:-10*thickBright,-5*thickBright,-0
								
								if(dstup&thickDown || dstdown&thickUp ||
										dstleft&thickRight || dstright&thickLeft){
									newdb.setElem(x+y*width, 0x00adbeef);
								}
								else if(i%3==0){
									//斜め方向の隣り合ったピクセルも調べる
									boolean dstul = false;
									if((y-1)>=srcRect.y && (x-1)>=srcRect.x) 
										dstul = (newdb.getElem(x-1+(y-1)*width)&0xFF000000)!=0;
									boolean dstur = false;
									if((y-1)>=srcRect.y && (x+1)<srcRect.x+srcRect.width) 
										dstur = (newdb.getElem(x+1+(y-1)*width)&0xFF000000)!=0;
									boolean dstdl = false;
									if((y+1)<srcRect.y+srcRect.height && (x-1)>=srcRect.x) 
										dstdl = (newdb.getElem(x-1+(y+1)*width)&0xFF000000)!=0;
									boolean dstdr = false;
									if((y+1)<srcRect.y+srcRect.height && (x+1)<srcRect.x+srcRect.width)
										dstdr = (newdb.getElem(x+1+(y+1)*width)&0xFF000000)!=0;
									
									if(dstul&(thickDown||thickRight) || dstur&(thickDown||thickLeft) ||
											dstdl&(thickUp||thickRight) || dstdr&(thickUp||thickLeft)){
										newdb.setElem(x+y*width, 0x00adbeef);
									}
								}
								
							}
						}
					}
				}
				for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
					for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
						if(newdb.getElem(x+y*width)==0x00adbeef){
							int v = srcdb.getElem(x+y*width);
							int red = (v>>16)&0xFF;
							int green = (v>>8)&0xFF;
							int blue = (v>>0)&0xFF;

							float[] hsb = Color.RGBtoHSB(red, green, blue, null);
							hsb[2] -= (double)thickBright*(realThickWidth-i)/realThickWidth/20.0f;
							hsb[1] += 2*(double)thickBright*(realThickWidth-i)/realThickWidth/20.0f;
							if(hsb[2]<0.0f) hsb[2] = 0.0f;
							if(hsb[1]>1.0f) hsb[1] = 1.0f;
							Color col = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
							int c = (0xFF000000&v)+(col.getRed()<<16)+(col.getGreen()<<8)+(col.getBlue());
							newdb.setElem(x+y*width, c);
						}
					}
				}
			}
		}

		//塗り残しを塗る
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
			for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
				int v = srcdb.getElem(x+y*width);
				if((v&0xff000000)!=0){
					if((newdb.getElem(x+y*width)&0xFF000000)==0){
						newdb.setElem(x+y*width, v);
					}
				}
			}
		}
		
		if(useGrad){
			//Gradation
			//一方向への単純なグラデーション

			Point topPoint = null;
			Point bottomPoint = null;
			for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
				for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
					if((0xFF000000&srcdb.getElem(0, x+y*width))!=0){
						if(topPoint==null){
							topPoint = new Point(x,y);
							bottomPoint = new Point(x,y);
						}
						if(x*Math.sin(gradAngle)+y*Math.cos(gradAngle) < topPoint.x*Math.sin(gradAngle)+topPoint.y*Math.cos(gradAngle)){
							topPoint.x = x;
							topPoint.y = y;
						}
						if(x*Math.sin(gradAngle)+y*Math.cos(gradAngle) > bottomPoint.x*Math.sin(gradAngle)+bottomPoint.y*Math.cos(gradAngle)){
							bottomPoint.x = x;
							bottomPoint.y = y;
						}
					}
				}
			}

			newdb = newimg.getRaster().getDataBuffer();
			for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
				for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
					int newc = newdb.getElem(x+y*width);
					if((newc&0xFF000000)!=0){
						int c = 0;
						double percent;
						percent = (x*Math.sin(gradAngle)+y*Math.cos(gradAngle) - (topPoint.x*Math.sin(gradAngle)+topPoint.y*Math.cos(gradAngle)))
							/ (bottomPoint.x*Math.sin(gradAngle)+bottomPoint.y*Math.cos(gradAngle) - (topPoint.x*Math.sin(gradAngle)+topPoint.y*Math.cos(gradAngle)));
						percent=1.0-(1.0-percent)/(10-gradBright);
						if(percent>1.0) percent = 1.0;
						c = ((int)(((newc>>16)&0xFF)*percent+255*(1.0-percent)))<<16;
						c += ((int)(((newc>>8)&0xFF)*percent+255*(1.0-percent)))<<8;
						c += ((int)(((newc>>0)&0xFF)*percent+255*(1.0-percent)));
						newdb.setElem(0, x+y*width, 0xFF000000+c);
					}
				}
			}
		}
		
		if(useHighlight){
			//Highlight
			//てかり

			Point topPoint = null;
			Point bottomPoint = null;
			for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
				for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
					if((0xFF000000&srcdb.getElem(0, x+y*width))!=0){
						if(topPoint==null){
							topPoint = new Point(x,y);
							bottomPoint = new Point(x,y);
						}
						if(x*Math.sin(gradAngle)+y*Math.cos(gradAngle) < topPoint.x*Math.sin(gradAngle)+topPoint.y*Math.cos(gradAngle)){
							topPoint.x = x;
							topPoint.y = y;
						}
						if(x*Math.sin(gradAngle)+y*Math.cos(gradAngle) > bottomPoint.x*Math.sin(gradAngle)+bottomPoint.y*Math.cos(gradAngle)){
							bottomPoint.x = x;
							bottomPoint.y = y;
						}
					}
				}
			}

			newdb = newimg.getRaster().getDataBuffer();
			for(int y=srcRect.y; y<srcRect.y+srcRect.height; y++){
				for(int x=srcRect.x; x<srcRect.x+srcRect.width; x++){
					int newc = newdb.getElem(x+y*width);
					if((newc&0xFF000000)!=0){
						int c = 0;
						double percent;
						percent = (x*Math.sin(gradAngle)+y*Math.cos(gradAngle) - (topPoint.x*Math.sin(gradAngle)+topPoint.y*Math.cos(gradAngle)))
							/ (bottomPoint.x*Math.sin(gradAngle)+bottomPoint.y*Math.cos(gradAngle) - (topPoint.x*Math.sin(gradAngle)+topPoint.y*Math.cos(gradAngle)));
						percent=1.0-(1.0-percent)/(10-gradBright);
						if(percent>1.0) percent = 1.0;
						c = ((int)(((newc>>16)&0xFF)*percent+255*(1.0-percent)))<<16;
						c += ((int)(((newc>>8)&0xFF)*percent+255*(1.0-percent)))<<8;
						c += ((int)(((newc>>0)&0xFF)*percent+255*(1.0-percent)));
						newdb.setElem(0, x+y*width, 0xFF000000+c);
					}
				}
			}
		}
		
		//選択領域を新しくする
		owner.redoBuf = newimg;
		owner.mainPane.repaint();
		
		if(cmd.equals("OK")){
			if(tl.getClass()==SmartSelectTool.class){
				((SmartSelectTool)tl).srcbits = newimg;
			}
			this.dispose();
			owner.mainPane.repaint();
			return;
		}
		
		this.getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

	}
	
}


class PaintScaleDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	private PCARDFrame owner;
	private AffineTransform save_af;
	private JTextField widthfield;
	private JTextField heightfield;
	private JCheckBox keepRatio;
	
	PaintScaleDialog(PCARDFrame owner) {
		super(owner, true);
		setModalExclusionType(ModalExclusionType.APPLICATION_EXCLUDE);
		this.owner = owner;
		save_af = owner.selectaf;
		getContentPane().setLayout(new BorderLayout());
		setTitle(PCARD.pc.intl.getDialogText("Scale Selection"));
		
		SetDialogContents();
		
		setBounds(owner.getX()+owner.getWidth()/2-100,owner.getY()+owner.getHeight()/2-100-20,200,200);
		
		setResizable(false);
		setVisible(true);
	}

	private void SetDialogContents()
	{
		this.getContentPane().removeAll();
		
		JPanel mainpanel = new JPanel();
		mainpanel.setLayout(new GridLayout(3,1));
		getContentPane().add("Center", mainpanel);

		toolSelectInterface tl = (toolSelectInterface)owner.tool;
		Rectangle rect = tl.getSelectedRect();
		
		{
			JPanel panel = new JPanel();
			mainpanel.add(panel);
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Width:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			
			widthfield = new JTextField(""+rect.width);
			widthfield.setName("width");
			widthfield.setPreferredSize(new Dimension(64, widthfield.getPreferredSize().height));
			widthfield.getDocument().addDocumentListener(new DocListener(widthfield));
			panel.add(widthfield);
		}
		{
			JPanel panel = new JPanel();
			mainpanel.add(panel);
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Height:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			
			heightfield = new JTextField(""+rect.height);
			heightfield.setName("height");
			heightfield.setPreferredSize(new Dimension(64, heightfield.getPreferredSize().height));
			heightfield.getDocument().addDocumentListener(new DocListener(heightfield));
			panel.add(heightfield);
		}
		{
			keepRatio = new JCheckBox(PCARD.pc.intl.getDialogText("Keep aspect ratio"));
			keepRatio.setName("Keep aspect ratio");
			keepRatio.setSelected(true);
			mainpanel.add(keepRatio);
		}

		JPanel okpanel = new JPanel();
		getContentPane().add("South", okpanel);
		
		//ok, cancel
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("Cancel"));
			button.setName("Cancel");
			button.addActionListener(this);
			okpanel.add(button);
		}
		{
			JButton button = new JButton(PCARD.pc.intl.getDialogText("OK"));
			button.setName("OK");
			button.addActionListener(this);
			okpanel.add(button);
		}
		
		this.getContentPane().repaint();
		//this.setVisible(true);
	}

	static boolean flag = false;
	
	class DocListener implements DocumentListener
	{
		JTextField jfield;
		
		public DocListener(JTextField fld){
			jfield = fld;
		}
		
		public void changedUpdate(DocumentEvent e) {
			toolSelectInterface tl = (toolSelectInterface)owner.tool;
			Rectangle rect = tl.getSelectedRect();

			String widthstr = widthfield.getText();
			if(!widthstr.matches("^[0-9]{1,5}$")) return;
			String heightstr = heightfield.getText();
			if(!heightstr.matches("^[0-9]{1,5}$")) return;
			int newwidth = Integer.valueOf(widthstr);
			int newheight = Integer.valueOf(heightstr);
			if(newwidth==0&&newheight==0) return;

			if(flag) return;
			flag = true;
			
			if(jfield.getName().equals("width")){
				if(keepRatio.isSelected()){
					heightfield.setText(""+newwidth*rect.height/rect.width);
				}
			}
			else if(jfield.getName().equals("height")){
				if(keepRatio.isSelected()){
					widthfield.setText(""+newheight*rect.width/rect.height);
				}
			}
			
			flag = false;

			widthstr = widthfield.getText();
			heightstr = heightfield.getText();
			newwidth = Integer.valueOf(widthstr);
			newheight = Integer.valueOf(heightstr);
			if(newwidth==0||newheight==0) return;
			
			double xrate, yrate;
			if(keepRatio.isSelected()){
				if(newwidth>newheight){
					xrate = (double)newwidth/rect.width;
					yrate = xrate;
				}else{
					yrate = (double)newheight/rect.height;
					xrate = yrate;
				}
			}
			else{
				xrate = (double)newwidth/rect.width;
				yrate = (double)newheight/rect.height;
			}


		    AffineTransform af = new AffineTransform();
		    af.scale(xrate, yrate);
			Rectangle moverect = tl.getMoveRect();
		    af.translate(moverect.x/xrate-moverect.x, moverect.y/yrate-moverect.y);
			owner.selectaf = af;
			owner.mainPane.repaint();
		}

		public void insertUpdate(DocumentEvent e) {
			changedUpdate(e);
		}

		public void removeUpdate(DocumentEvent e) {
			changedUpdate(e);
		}
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JComponent)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getDialogEngText(name);

		if(cmd==null) return;
		
		if(cmd.equals("Cancel")){
			owner.selectaf = save_af;
			this.dispose();
			owner.mainPane.repaint();
			return;
		}
		else if(cmd.equals("OK")){
			//拡大縮小を反映
			String widthstr = widthfield.getText();
			if(!widthstr.matches("^[0-9]{1,5}$")) return;
			String heightstr = heightfield.getText();
			if(!heightstr.matches("^[0-9]{1,5}$")) return;
			int newwidth = Integer.valueOf(widthstr);
			int newheight = Integer.valueOf(heightstr);
			if(newwidth==0||newheight==0) return;
			
			toolSelectInterface tl = (toolSelectInterface)owner.tool;
			if(tl.getClass()==SelectTool.class){
				Image img = owner.redoBuf
					.getScaledInstance(newwidth, newheight, Image.SCALE_SMOOTH );
				BufferedImage newimg = new BufferedImage(newwidth, newheight, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g2 = (Graphics2D)newimg.getGraphics();
				g2.drawImage(img, 0, 0, newwidth, newheight, null);
				
				owner.redoBuf = newimg;

				((SelectTool)tl).srcRect.width = newwidth;
				((SelectTool)tl).srcRect.height = newheight;
				((SelectTool)tl).moveRect.width = newwidth;
				((SelectTool)tl).moveRect.height = newheight;
				if(((SelectTool)tl).moveRect.x<0) ((SelectTool)tl).moveRect.x=0;
				if(((SelectTool)tl).moveRect.y<0) ((SelectTool)tl).moveRect.y=0;
			}
			else{
				if(tl.getClass()==LassoTool.class){
					Rectangle rect = tl.getSelectedRect();
					int newwidth2 = owner.redoBuf.getWidth()*newwidth/rect.width;
					int newheight2 = owner.redoBuf.getHeight()*newheight/rect.height;
					
					Image selimg = owner.redoBuf
						.getScaledInstance(newwidth2, newheight2, Image.SCALE_SMOOTH );
					BufferedImage newimg = new BufferedImage(newwidth, newheight, BufferedImage.TYPE_INT_ARGB);
					Graphics2D newg = newimg.createGraphics();
					newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
					newg.fillRect(0,0,newimg.getWidth(), newimg.getHeight());
					newg = newimg.createGraphics();
					newg.drawImage(selimg, 0, 0, newwidth, newheight, 
							rect.x*newwidth/rect.width, rect.y*newheight/rect.height,
							rect.x*newwidth/rect.width+newwidth, rect.y*newheight/rect.height+newheight, null);
					
					owner.redoBuf = newimg;
					
					Image mskimg = ((LassoTool)tl).srcbits
						.getScaledInstance(newwidth2, newheight2, Image.SCALE_SMOOTH );
					BufferedImage newimg2 = new BufferedImage(newwidth, newheight, BufferedImage.TYPE_INT_ARGB);
					Graphics2D newg2 = newimg2.createGraphics();
					newg2.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
					newg2.fillRect(0,0,newimg2.getWidth(), newimg2.getHeight());
					newg2 = newimg2.createGraphics();
					newg2.drawImage(mskimg, 0, 0, newwidth, newheight, 
							rect.x*newwidth/rect.width, rect.y*newheight/rect.height,
							rect.x*newwidth/rect.width+newwidth, rect.y*newheight/rect.height+newheight, null);
					
					((LassoTool)tl).srcbits = newimg2;

					((LassoTool)tl).movePoint.x+=rect.x;
					((LassoTool)tl).movePoint.y+=rect.y;
				}
				else if(tl.getClass()==SmartSelectTool.class){
					Rectangle rect = tl.getSelectedRect();
					int newwidth2 = owner.redoBuf.getWidth()*newwidth/rect.width;
					int newheight2 = owner.redoBuf.getHeight()*newheight/rect.height;
					
					Image selimg = owner.redoBuf
						.getScaledInstance(newwidth2, newheight2, Image.SCALE_SMOOTH );
					BufferedImage newimg = new BufferedImage(newwidth, newheight, BufferedImage.TYPE_INT_ARGB);
					Graphics2D newg = newimg.createGraphics();
					newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
					newg.fillRect(0,0,newimg.getWidth(), newimg.getHeight());
					newg = newimg.createGraphics();
					newg.drawImage(selimg, 0, 0, newwidth, newheight, 
							rect.x*newwidth/rect.width, rect.y*newheight/rect.height,
							rect.x*newwidth/rect.width+newwidth, rect.y*newheight/rect.height+newheight, null);
					
					owner.redoBuf = newimg;
					
					Image mskimg = ((SmartSelectTool)tl).srcbits
						.getScaledInstance(newwidth2, newheight2, Image.SCALE_SMOOTH );
					BufferedImage newimg2 = new BufferedImage(newwidth, newheight, BufferedImage.TYPE_INT_ARGB);
					Graphics2D newg2 = newimg2.createGraphics();
					newg2.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
					newg2.fillRect(0,0,newimg2.getWidth(), newimg2.getHeight());
					newg2 = newimg2.createGraphics();
					newg2.drawImage(mskimg, 0, 0, newwidth, newheight, 
							rect.x*newwidth/rect.width, rect.y*newheight/rect.height,
							rect.x*newwidth/rect.width+newwidth, rect.y*newheight/rect.height+newheight, null);
					
					((SmartSelectTool)tl).srcbits = newimg2;

					((SmartSelectTool)tl).movePoint.x+=rect.x;
					((SmartSelectTool)tl).movePoint.y+=rect.y;
				}
				
				
			}

			owner.selectaf = save_af;
			this.dispose();
			owner.mainPane.repaint();
			return;
		}
	}
}


class PaintBlendDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	private PCARDFrame owner;
	static PaintBlendDialog dialog;

	static void showPaintBlendDialog(PCARDFrame owner) {
		if(dialog!=null && dialog.owner == owner){
			dialog.SetDialogContents();
			dialog.setBounds(owner.getX()+owner.getWidth()/2-150,owner.getY()+20,300,150);
			dialog.setVisible(true);
			return;
		}
		
		if(dialog!=null){
			dialog.dispose();
		}
		
		dialog = new PaintBlendDialog(owner);
	}
	
	private PaintBlendDialog(PCARDFrame owner) {
		super(owner, false);
		this.owner = owner;
		getContentPane().setLayout(new BorderLayout());
		setTitle(PCARD.pc.intl.getDialogText("Blending Mode"));
		
		SetDialogContents();
		
		setBounds(owner.getX()+owner.getWidth()/2-150,owner.getY()+20,300,150);
		
		setResizable(false);
		setVisible(true);
	}

	private void SetDialogContents()
	{
		this.getContentPane().removeAll();
		
		JPanel mainpanel = new JPanel();
		getContentPane().add("Center", mainpanel);

		{
			JPanel aqpanel = new JPanel();
			Border aquaBorder = UIManager.getBorder("TitledBorder.aquaVariant");
			if(aquaBorder==null){
				aquaBorder = new EtchedBorder();
			}
			aqpanel.setBorder( aquaBorder );
			getContentPane().add("Center", aqpanel);

			JPanel panel = new JPanel();
			panel.setOpaque(false);
			aqpanel.add(panel);
			
			String[] value = new String[]{
					PCARD.pc.intl.getDialogText("Copy"),
					PCARD.pc.intl.getDialogText("Blend"), //指定の強さでアルファブレンド
					PCARD.pc.intl.getDialogText("Add"), //(指定の強さで加算)発光
					PCARD.pc.intl.getDialogText("Subtract"), //(指定の強さで減算)焼きこみ
					PCARD.pc.intl.getDialogText("Multiply"), //乗算
					PCARD.pc.intl.getDialogText("Screen"), //指定の輝度から引いた色で掛け算
					PCARD.pc.intl.getDialogText("Darken"), //暗い色を残す
					PCARD.pc.intl.getDialogText("Lighten"), //明るい色を残す
					PCARD.pc.intl.getDialogText("Difference"), //差の絶対値
					PCARD.pc.intl.getDialogText("Hue"), //色相
					PCARD.pc.intl.getDialogText("Color"), //色相と彩度
					PCARD.pc.intl.getDialogText("Saturation"), //彩度
					PCARD.pc.intl.getDialogText("Luminosity"), //輝度
					PCARD.pc.intl.getDialogText("Alpha Channel") //輝度をアルファチャンネルに
				};
			JComboBox combo = new JComboBox(value);
			combo.setName("Mode");
			combo.setSelectedIndex(owner.blendMode);
			combo.setMaximumRowCount(32);
			combo.addActionListener(this);
			panel.add(combo);
			
			panel = new JPanel();
			panel.setOpaque(false);
			aqpanel.add(panel);
			
			value = new String[21];
			for(int i=0; i<value.length; i++){
				value[i] = i*5+"%";
			}
			combo = new JComboBox(value);
			combo.setName("Level");
			combo.setSelectedIndex(owner.blendLevel/5);
			combo.setMaximumRowCount(32);
			combo.addActionListener(this);
			panel.add(combo);
		}
		
		this.getContentPane().repaint();
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JComponent)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getDialogEngText(name);

		if(cmd==null) return;
		
		if(cmd!=null && cmd.equals("Mode")){
			String str = (String)((JComboBox)e.getSource()).getSelectedItem();
			String selectedMode = PCARD.pc.intl.getDialogEngText(str);
			owner.blendMode = owner.mainPane.getBlendMode(selectedMode);
		}
		if(cmd!=null && cmd.equals("Level")){
			int index = ((JComboBox)e.getSource()).getSelectedIndex();
			owner.blendLevel = index*5;
		}
		
		owner.mainPane.repaint();
	}
}
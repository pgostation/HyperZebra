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

import javax.imageio.ImageIO;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.border.MatteBorder;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OButton extends OObject {
	OCardBase card = null;
	//OBackground bkgd = null;
	MyButton btn = null;
	MyCheck chkbox = null;
	MyRadio radio = null;
	MyPopup popup = null;
	BufferedImage imageForScale; //アイコン拡大縮小用データ;
	
	//プロパティ
	//Boolean autoHilite=true;
	Color color=Color.BLACK;
	Color bgColor=Color.WHITE;
	int group=0;
	Boolean check_hilite=false;//他のカードからの参照用
	private boolean autoHilite=true;//作成時のみ参照
	private boolean hilite=false;//作成時のみ参照
	int icon=0;
	URI iconURI;
	//number (カードの情報から求める)
	Boolean sharedHilite=false;
	Boolean showName=true;
	int style=0;//0標準 1透明 2不透明 3長方形 4シャドウ 5丸みのある長方形 6省略時設定 7楕円 8ポップアップ 9チェックボックス 10ラジオ
	int textAlign=1;//0左 1中 2右
	String textFont="";
	int textHeight=16;
	int textSize=12;
	int textStyle=0;//
	int titleWidth=0;
	private int selectedLine=0;
	private boolean scaleIcon = false;
	int blendMode;
	int blendLevel = 100;
	
	//追加プロパティ
	@SuppressWarnings("unused")
	private String reserved1;
	@SuppressWarnings("unused")
	private String reserved2;
	@SuppressWarnings("unused")
	private String reserved3;
	@SuppressWarnings("unused")
	private String reserved4;
	@SuppressWarnings("unused")
	private String reserved5;
	@SuppressWarnings("unused")
	private String reserved25;
	
	//他
	static BufferedImage cache_bi;
	static ImageIcon cache_icon;
	static int cache_id = -99;
	static ButtonGroup[] btnGroup = new ButtonGroup[16];
	DropTarget drop;
	
	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}
	
	JComponent getComponent(){
		if(btn!=null) return btn;
		if(popup!=null) return popup;
		if(radio!=null) return radio;
		if(chkbox!=null) return chkbox;
		return null;
	}
	
	//set
	public void setName(String in) {
		name=in;
		if(btn!=null && showName){
			btn.setText(in);
		}
		if(radio!=null && showName){
			radio.setName(in);
		}
		if(chkbox!=null && showName){
			chkbox.setName(in);
		}
		if(popup!=null && showName){
			popup.setToolTipText(in);
		}
		if(icon==-1) {
			//PICTボタンの場合
			setIcon(-1, false);
		}
		else if(!PCARD.lockedScreen) {
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setIcon(int in) {
		setIcon(in, false);
	}
	private void setIcon(int in, boolean noupdate) {
		//if(icon==in) return;
		icon=in;
		iconURI=null;
		if(btn!=null)
		{
			//RepaintManager rm = null;
			if(!PCARD.lockedScreen) {
				//rm = RepaintManager.currentManager(getComponent());
				//RepaintManager.setCurrentManager(nullPaintManager);
			}
			
			//this.iconImage = null;
			if(icon>0){
				if(in == cache_id){
					/*if((autoHilite || hilite==btn.getModel().isArmed())&&!showName&&style>=1&&style<=3){
						this.iconImage = cache_bi;
					}else*/{
						if(scaleIcon || blendMode!=0) imageForScale = cache_bi;
						btn.setIcon(new ImageIcon( cache_bi ));
					}
					//btn.setIcon(cache_icon); //こんなことしても別に早くなっていない様子
				}
				else
				{
					//アイコン読み込み
					String path = ((OCardBase)parent).stack.rsrc.getFilePathAll(icon, "icon");
					if(path!=null){
						//String path = (((OCardBase)parent).stack.file.getParent()+File.separatorChar+fileName);
						BufferedImage bi = PictureFile.loadPbm(path);
						if(bi==null){
							try {
								//bi = javax.imageio.ImageIO.read(new File(path));
								btn.setIcon(new ImageIcon( path ));
								if(scaleIcon || blendMode!=0) {
									bi = javax.imageio.ImageIO.read(new File(path));
									imageForScale = bi;
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
						if(bi!=null){
							cache_bi = bi;
							cache_id = in;
							/*if((autoHilite || hilite==btn.getModel().isArmed())&&!showName&&style>=1&&style<=3){
								this.iconImage = bi;
							}else*/{
								if(scaleIcon || blendMode!=0) imageForScale = bi;
								btn.setIcon(new ImageIcon( bi ));
							}
						}
					}
					else{
						//アイコン読み込み(png,jpeg)
						File file=new File(card.stack.file.getParent()+"/resource/icon"+icon+".png");
						if(file.exists()) {
							try {
								BufferedImage bi = javax.imageio.ImageIO.read(file);
								cache_bi = bi;
								cache_icon = new ImageIcon( bi );
								cache_id = in;
								if(scaleIcon || blendMode!=0) imageForScale = bi;
								btn.setIcon(cache_icon);
							} catch (IOException e) {
								e.printStackTrace();
							}
							//btn.setIcon(new ImageIcon(card.stack.file.getParent()+"/resource/icon"+icon+".png"));
						}
						else{
							try {
								file=new File(card.stack.file.getParent()+"/resource/icon"+icon+".jpeg");
								BufferedImage bi = javax.imageio.ImageIO.read(file);
								cache_bi = bi;
								cache_icon = new ImageIcon( bi );
								cache_id = in;
								if(scaleIcon || blendMode!=0) imageForScale = bi;
								btn.setIcon(cache_icon);
							} catch (Exception e) {
								//e.printStackTrace();
							}
						}
					}
				}
				if(btn.btnData.showName){
					btn.setHorizontalTextPosition(SwingConstants.CENTER);
					btn.setVerticalTextPosition(SwingConstants.BOTTOM);
				}
				if(!PCARD.lockedScreen && noupdate==false) {
					/*if(cache_bi!=null){
						//もうちょっとちゃんとしないといけないが高速化
						Graphics paneg = PCARD.pc.mainPane.getGraphics();
						paneg.drawImage(cache_bi,left,top,left+width,top+height,0,0,width,height,PCARD.pc.mainPane);
					}else*/{
						PCARD.pc.mainPane.paintImmediately(left, top, width, height);
						if(TTalk.wait>0 && PCARD.pc.stack.createdByVersion.charAt(0)=='2'){
							try {
								Thread.sleep(2*TTalk.wait);
							} catch (InterruptedException e) {
							}
						}
					}
				}
			}
			else if(icon==-1){
				//PICTボタン読み込み(pbm)
				int rsrcid = ((OCardBase)parent).stack.rsrc.getRsrcIdAll(name, "picture");
				String path = ((OCardBase)parent).stack.rsrc.getFilePathAll(rsrcid, "picture");
				if(path!=null){
					//String path = (((OCardBase)parent).stack.file.getParent()+File.separatorChar+fileName);
					BufferedImage bi = PictureFile.loadPICT(path);
					if(bi==null){
						try {
							bi = javax.imageio.ImageIO.read(new File(path));
						}catch(IOException e){
							
						}
					}
					if(bi!=null){
						cache_bi = bi;
						cache_id = in;
						if(scaleIcon || blendMode!=0) imageForScale = bi;
						btn.setIcon(new ImageIcon( bi ));
					}
				}
				else
				{
					//PICTボタン読み込み(jpeg,png)
					File file=new File(card.stack.file.getParent()+"/resource/pict"+name+".png");
					if(!file.exists()) {
						file=new File(card.stack.file.getParent()+"/resource/pict"+name+".jpeg");
					}
					if(!file.exists()) {
						try {
							BufferedImage pict = ImageIO.read(new File(card.stack.file.getParent()+"/resource/pict"+name+"_img.png"));
							File file2=new File(card.stack.file.getParent()+"/resource/pict"+name+"_mask.png");
							if(file2.exists()) {
								BufferedImage mask = ImageIO.read(file);
								pict = OCard.makeAlphaImage(pict, mask);
								File ofile=new File(card.stack.file.getParent()+"/resource/pict"+name+".png");
								ImageIO.write(pict,"png",ofile);
							}
						}catch (Exception err) {
							//err.printStackTrace();
						}
					}
					if(file.exists()) {
						try {
							BufferedImage bi = javax.imageio.ImageIO.read(file);
							if(scaleIcon || blendMode!=0) imageForScale = bi;
							btn.setIcon(new ImageIcon( bi ));
						} catch (IOException e) {
							//e.printStackTrace();
						}
					}else{
						btn.setIcon(null);
						imageForScale = null;
					}
				}
				//btn.setIcon(new ImageIcon(card.stack.file.getParent()+"/resource/pict"+name+".png"));
				btn.setText("");
				if(!PCARD.lockedScreen && noupdate==false) {
					PCARD.pc.mainPane.paintImmediately(left, top, width, height);
					if(TTalk.wait>0 && PCARD.pc.stack.createdByVersion.charAt(0)=='2'){
						try {
							Thread.sleep(4*TTalk.wait);
						} catch (InterruptedException e) {
						}
					}
				}
			}
			else if(icon==0){
				btn.setIcon(null);
				imageForScale = null;
				if(!PCARD.lockedScreen && noupdate==false) {
					PCARD.pc.mainPane.paintImmediately(left, top, width, height);
				}
			}
			
			//if(!PCARD.lockedScreen) {
				//RepaintManager.setCurrentManager(rm);
			//}
		}
		((OCardBase)parent).changed = true;
	}
	public void setIconURI(URI in) {
		setIconURI(in, false);
	}
	private void setIconURI(URI in, boolean noupdate) {
		//if(icon==in) return;
		icon=0;
		iconURI = in;
		if(btn!=null)
		{
			if(iconURI!=null){
				try {
					Image img = Toolkit.getDefaultToolkit().getImage(iconURI.toURL());
					if(scaleIcon || blendMode!=0) {
						imageForScale = new BufferedImage(img.getWidth(btn), img.getHeight(btn), BufferedImage.TYPE_INT_ARGB);
						imageForScale.createGraphics().drawImage(img,0,0,btn);
					}
					new setIconThread(img).start();
				} catch (MalformedURLException e) {
					e.printStackTrace();
				} catch(IllegalArgumentException e){
					
				}
				if(btn.btnData.showName){
					btn.setHorizontalTextPosition(SwingConstants.CENTER);
					btn.setVerticalTextPosition(SwingConstants.BOTTOM);
				}
			}
			else{
				btn.setIcon(null);
				imageForScale = null;
			}
			
			if(!PCARD.lockedScreen && noupdate==false) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}

	class setIconThread extends Thread{
		Image img;
		setIconThread(Image img){
			this.img = img;
		}
		public void run(){
			btn.setIcon(new ImageIcon(img));
		}
	}
	
	/*static NullPaintManager nullPaintManager = new NullPaintManager();
	static class NullPaintManager extends RepaintManager{
		@Override
        public void addDirtyRegion(JComponent c, int x, int y, int w,int h) {
		}
	}*/
	
	public void setTopLeft(int h, int v) {
		int oldLeft=left;
		int oldTop=top;
		left=h;
		top=v;
		if(getComponent()!=null){
			/*RepaintManager rm = null;
			if(!PCARD.lockedScreen) {
				rm = RepaintManager.currentManager(getComponent());
				RepaintManager.setCurrentManager(nullPaintManager);
			}*/
			getComponent().setBounds(left, top, width, height);
			/*if(!PCARD.lockedScreen) {
				RepaintManager.setCurrentManager(rm);
			}*/

			if(!PCARD.lockedScreen) {
				if(Math.abs(left-oldLeft)<width && Math.abs(top-oldTop)<height )
					PCARD.pc.mainPane.paintImmediately(Math.min(left,oldLeft), Math.min(top,oldTop), width+Math.abs(left-oldLeft), height+Math.abs(top-oldTop));
				else {
					PCARD.pc.mainPane.paintImmediately(left, top, width, height);
					PCARD.pc.mainPane.paintImmediately(oldLeft, oldTop, width, height);
				}
				if(TTalk.wait>0 && PCARD.pc.stack.createdByVersion.charAt(0)=='2'){
					try {
						Thread.sleep(3*TTalk.wait);
					} catch (InterruptedException e) {
					}
				}
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setRect(int v1, int v2, int v3, int v4) {
		int oldLeft=left;
		int oldTop=top;
		int oldWidth=width;
		int oldHeight=height;
		left=v1;
		top=v2;
		width=v3-v1;
		height=v4-v2;
		if(getComponent()!=null){
			getComponent().setBounds(left, top, width, height);

			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
				PCARD.pc.mainPane.paintImmediately(oldLeft, oldTop, oldWidth, oldHeight);
				if(TTalk.wait>0 && PCARD.pc.stack.createdByVersion.charAt(0)=='2'){
					try {
						Thread.sleep(3*TTalk.wait);
					} catch (InterruptedException e) {
					}
				}
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setText(String in) {
		super.setText(in);
		if(popup!=null){
			popup.removeAllItems();
			String[] value = in.split("\n");
			for(int i=0;i<value.length;i++){
				popup.addItem(value[i]);
			}
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setVisible(boolean in) {
		if(getVisible()==in) return;
		super.setVisible(in);
		if(getComponent()!=null)
		{
			getComponent().setVisible(in);
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
	}
	public void setEnabled(boolean in) {
		enabled = in;
		if(getComponent()!=null)
		{
			getComponent().setEnabled(in);
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setHilite(boolean in) {
		hilite = in;
		check_hilite = in;
		/*if(sharedHilite && card.getClass()==OCard.class){
			((OCard)card).bg.GetBgBtnbyId(id).hilite = in;
		}*/
		if(group!=0){
			ArrayList<OButton> btnList = ((OCardBase)parent).btnList;
			for(int i=0; i<btnList.size(); i++){
				if(btnList.get(i)!=this && btnList.get(i).group==group){
					btnList.get(i).check_hilite = false;
					btnList.get(i).hilite = false;
				}
			}
		}
		if(getComponent()!=null)
		{
			if(btn!=null){
				btn.setHilite(in);
			}
			else if (radio!=null){
				radio.setSelected(in);
			}
			else if (chkbox!=null){
				chkbox.setSelected(in);
			}
			
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public boolean getHilite() {
		if(getComponent()!=null)
		{
			if(btn!=null){
				return btn.getHilite();
			}
			else if (radio!=null){
				return radio.isSelected();
			}
			else if (chkbox!=null){
				return chkbox.isSelected();
			}
		}
		
		return hilite;
	}
	public int getSelectedLine() {
		if(style==8 && popup!=null){
			int index = popup.getSelectedIndex()+1;
			return index;
		}
		return selectedLine;
	}
	public void setSelectedLine(int line) {
		if(style==8 && popup!=null){
			popup.setSelectedIndex(line-1);
		}
		if(style==8) selectedLine = line;
		((OCardBase)parent).changed = true;
	}
	public String getSelectedText() {
		if(style==8){
			int index = selectedLine-1;
			if(popup!=null){
				index = popup.getSelectedIndex();
			}
			String[] ary = getText().split("\n");
			if(index>=0 && index < ary.length){
				return ary[index];
			} else {
				return "";
			}
		}
		return "";
	}
	public void setShowName(boolean in) {
		showName = in;
		if(getComponent()!=null)
		{
			if(btn!=null){
				if(showName) btn.setText(name);
				else btn.setText("");
			}
			else if(popup!=null){
				if(showName) popup.setToolTipText(name);
				else popup.setToolTipText(null);
			}
			else if(radio!=null){
				if(showName) radio.setText(name);
				else radio.setText("");
			}
			else if(chkbox!=null){
				if(showName) chkbox.setText(name);
				else chkbox.setText("");
			}
			
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public boolean getAutoHilite(){
		/*if(btn != null){
			return btn.autoHilite;
		}*/
		return autoHilite;
	}
	public void setAutoHilite(boolean in){
		autoHilite = in;
		if(btn != null){
			btn.setAutoHilite(in);
		}
		((OCardBase)parent).changed = true;
	}
	public boolean getScaleIcon(){
		return scaleIcon;
	}
	public void setScaleIcon(boolean in){
		scaleIcon = in;
		if(in){
			if(iconURI!=null){
				setIconURI(iconURI);
			}else{
				setIcon(icon);
			}
		}
		else{
			imageForScale = null;
		}
		if(!PCARD.lockedScreen) {
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setTextFont(String in){
		textFont = in;
		if(btn != null){
			btn.setFont(new Font(textFont, textStyle & 0x03, textSize));
		}
		if(!PCARD.lockedScreen) {
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setBlendMode(int in){
		blendMode = in;
		{
			if(iconURI!=null){
				setIconURI(iconURI);
			}else{
				setIcon(icon);
			}
		}
		if(!PCARD.lockedScreen) {
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setBlendLevel(int in) {
		blendLevel = in;
		if(!PCARD.lockedScreen) {
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}

	public void setColor(Color col) {
		color=col;
		if(btn!=null)
		{
			btn.setForeground(col);
		}
	}
	public void setBgColor(Color col) {
		bgColor=col;
		if(btn!=null)
		{
			btn.setBackground(col);
		}
	}
	
	public OButton(OCardBase cd, int btnId){
    	objectType="button";
		card = cd;
		parent = cd;
		scriptList = new ArrayList<String>();

		width=64; height=20;
		id=btnId;
	}
	

	@SuppressWarnings("unchecked")
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("id")){ this.id = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("visible")){ this.setVisible(XMLRead.bool(reader));reader.next(); }
	            	else if(elm.equals("reserved5")){ this.reserved5 = reader.getElementText(); }
	            	else if(elm.equals("reserved4")){ this.reserved4 = reader.getElementText(); }
	            	else if(elm.equals("reserved3")){ this.reserved3 = reader.getElementText(); }
	            	else if(elm.equals("reserved2")){ this.reserved2 = reader.getElementText(); }
	            	else if(elm.equals("reserved1")){ this.reserved1 = reader.getElementText(); }
	            	else if(elm.equals("enabled")){ this.enabled = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("rect")){
	            		int right=0, bottom=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("left")){ 
	    		            		this.left = Integer.valueOf(reader.getElementText());
	    		            		if(this.left>=32768) this.left -= 65536;
	    		            	}
	    		            	else if(elm2.equals("top")){ 
	    		            		this.top = Integer.valueOf(reader.getElementText()); 
	    		            		if(this.top>=32768) this.top -= 65536;
	    		            	}
	    		            	else if(elm2.equals("right")){ right = Integer.valueOf(reader.getElementText()); }
	    		            	else if(elm2.equals("bottom")){ bottom = Integer.valueOf(reader.getElementText()); }
	    		            	else System.out.println("Part_Type: " + reader.getLocalName());
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("rect")){
	    		            		this.width = right - this.left;
	    		            		this.height = bottom - this.top;
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}
	            	else if(elm.equals("color") || elm.equals("bgColor")){
    	            	int red=0, green=0, blue=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("red")){ red = Integer.valueOf(reader.getElementText());}
	    		            	else if(elm2.equals("green")){ green = Integer.valueOf(reader.getElementText());}
	    		            	else if(elm2.equals("blue")){ blue = Integer.valueOf(reader.getElementText()); }
	    		            	else System.out.println("Part_Type: " + reader.getLocalName());
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("color")){
	    		            		this.color = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            	if(elm2.equals("bgColor")){
	    		            		this.bgColor = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}
	            	else if(elm.equals("style")){
	            		String tmpstr = reader.getElementText();
						if(0==tmpstr.compareTo("standard")) this.style=0;
						if(0==tmpstr.compareTo("transparent")) this.style=1;
						if(0==tmpstr.compareTo("opaque")) this.style=2;
						if(0==tmpstr.compareTo("rectangle")) this.style=3;
						if(0==tmpstr.compareTo("shadow")) this.style=4;
						if(0==tmpstr.compareTo("roundrect")) this.style=5;
						if(0==tmpstr.compareTo("default")) this.style=6;
						if(0==tmpstr.compareTo("oval")) this.style=7;
						if(0==tmpstr.compareTo("popup")) this.style=8;
						if(0==tmpstr.compareTo("checkbox")) this.style=9;
						if(0==tmpstr.compareTo("radio")) this.style=10;
	            	}
	            	else if(elm.equals("showName")){ this.showName = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("highlight")){ this.hilite = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("autoHighlight")){ this.autoHilite = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("sharedHighlight")){ this.sharedHilite = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("family")){ this.group = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("titleWidth")){ this.titleWidth = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textAlign")){
	            		String alignStr = reader.getElementText();
	            		if(alignStr.equals("left")) this.textAlign = 0;
	            		else if(alignStr.equals("center")) this.textAlign = 1;
	            		else if(alignStr.equals("right")) this.textAlign = 2;
	            	}
	            	else if(elm.equals("textFontID")){
	            		int textFontID = Integer.valueOf(reader.getElementText());
            			OStack stack = ((OCardBase)this.parent).stack;
            			for(int i=0; i<stack.fontList.size();i++){
            				if(stack.fontList.get(i).id ==textFontID){
            					textFont = stack.fontList.get(i).name;
            					break;
            				}
            			}
	            	}
	            	else if(elm.equals("textSize")){ this.textSize = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("icon")){ this.icon = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("iconURI")){ this.iconURI = new URI(reader.getElementText()); }
	            	else if(elm.equals("selectedLines")){ 
	            		//this.selectedLine = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("integer")){ 
	            		this.selectedLine = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("textStyle")){ 
	            		String tmpstr=reader.getElementText();
						//this.textStyle=0;
						if(tmpstr.contains("plain")) this.textStyle=0;
						if(tmpstr.contains("bold")) this.textStyle+=1;
						if(tmpstr.contains("italic")) this.textStyle+=2;
						if(tmpstr.contains("underline")) this.textStyle+=4;
						if(tmpstr.contains("outline")) this.textStyle+=8;
						if(tmpstr.contains("shadow")) this.textStyle+=16;
						if(tmpstr.contains("condensed")) this.textStyle+=32;
						if(tmpstr.contains("extend")) this.textStyle+=64;
						if(tmpstr.contains("group")) this.textStyle+=128;
	            	}
	            	else if(elm.equals("reserved25")){ reserved25 = reader.getElementText(); }
	            	else if(elm.equals("scaleIcon") || elm.equals("scalingIcon")){ this.scaleIcon = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("blendingMode")){ 
	            		String tmpstr=reader.getElementText();
						if(tmpstr.equals("copy")) this.blendMode=0;
						if(tmpstr.equals("blend")) this.blendMode=1;
						if(tmpstr.equals("add")) this.blendMode=2;
						if(tmpstr.equals("subtract")) this.blendMode=3;
						if(tmpstr.equals("multiply")) this.blendMode=4;
						if(tmpstr.equals("screen")) this.blendMode=5;
						if(tmpstr.equals("darken")) this.blendMode=6;
						if(tmpstr.equals("lighten")) this.blendMode=7;
						if(tmpstr.equals("difference")) this.blendMode=8;
						if(tmpstr.equals("hue")) this.blendMode=9;
						if(tmpstr.equals("color")) this.blendMode=10;
						if(tmpstr.equals("saturation")) this.blendMode=11;
						if(tmpstr.equals("luminosity")) this.blendMode=12;
	            	}
	            	else if(elm.equals("blendingLevel")){ this.blendLevel = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("name")){ 
	            		name = reader.getElementText();
	            	}
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		String[] scriptAry = scrStr.split("\n");
	            		for(int i=0; i<scriptAry.length; i++)
	            		{
	            			scriptList.add(scriptAry[i]);
	            		}
	            		stringList = new ArrayList[scriptList.size()];
	            		typeList = new ArrayList[scriptList.size()];
	            	}
	            	else if(elm.equals("true")); //dummy 
	            	else if(elm.equals("false")); //dummy 
	            	else if(elm.equals("part")); //dummy for clipboard
	            	else if(elm.equals("type")); //dummy for clipboard
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("part")){
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    System.err.println(ex.getMessage());
			    break;
	        }
	    }
	    return reader;
	}

	
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("part");
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("id");
        writer.writeCharacters(Integer.toString(id));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("type");
        writer.writeCharacters("button");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("visible");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(getVisible())+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("enabled");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(enabled)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("rect");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("left");
            writer.writeCharacters(Integer.toString(left));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("top");
            writer.writeCharacters(Integer.toString(top));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("right");
            writer.writeCharacters(Integer.toString(left+width));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("bottom");
            writer.writeCharacters(Integer.toString(top+height));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("style");
        switch(style){
        case 0: writer.writeCharacters("standard");break;
        case 1: writer.writeCharacters("transparent");break;
        case 2: writer.writeCharacters("opaque");break;
        case 3: writer.writeCharacters("rectangle");break;
        case 4: writer.writeCharacters("shadow");break;
        case 5: writer.writeCharacters("roundrect");break;
        case 6: writer.writeCharacters("default");break;
        case 7: writer.writeCharacters("oval");break;
        case 8: writer.writeCharacters("popup");break;
        case 9: writer.writeCharacters("checkbox");break;
        case 10: writer.writeCharacters("radio");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("showName");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(showName)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("highlight");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(hilite)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(parent.getClass()==OBackground.class){
            writer.writeStartElement("autoHighlight");
            writer.writeCharacters(" ");
            writer.writeEmptyElement(Boolean.toString(autoHilite)+" ");
            writer.writeCharacters(" ");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
            
	        writer.writeStartElement("sharedHighlight");
	        writer.writeCharacters(" ");
	        writer.writeEmptyElement(Boolean.toString(sharedHilite)+" ");
	        writer.writeCharacters(" ");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");

	        writer.writeStartElement("family");
	        writer.writeCharacters(Integer.toString(group));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }
        else{
            /*writer.writeStartElement("reserved25");
            writer.writeCharacters(" 0 ");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");*/

	        writer.writeStartElement("family");
	        writer.writeCharacters(Integer.toString(group));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");

	        writer.writeStartElement("autoHighlight");
	        writer.writeCharacters(" ");
	        writer.writeEmptyElement(Boolean.toString(autoHilite)+" ");
	        writer.writeCharacters(" ");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

        writer.writeStartElement("titleWidth");
        writer.writeCharacters(Integer.toString(titleWidth));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(style==8){
        	if(selectedLine>0){
		        writer.writeStartElement("selectedLines");
		        writer.writeCharacters("\n\t\t\t\t");
		        {
			        writer.writeStartElement("integer");
			        writer.writeCharacters(Integer.toString(selectedLine));
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t\t");
		        }
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
        	}
        }
        else{
	        writer.writeStartElement("icon");
	        writer.writeCharacters(Integer.toString(icon));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
	        
	        writer.writeStartElement("iconURI");
	        writer.writeCharacters(iconURI!=null?iconURI.toString():"");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

        writer.writeStartElement("textAlign");
        switch(textAlign){
        case 0: writer.writeCharacters("left");break;
        case 1: writer.writeCharacters("center");break;
        case 2: writer.writeCharacters("right");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        int textFontID = 0;
        for(int i=0; i<((OCardBase)parent).stack.fontList.size(); i++){
        	OStack.fontClass fontinfo = ((OCardBase)parent).stack.fontList.get(i);
        	if(fontinfo.name.equals(textFont)){
        		textFontID = fontinfo.id;
        		break;
        	}
        }
        writer.writeStartElement("textFontID");
        writer.writeCharacters(Integer.toString(textFontID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("textSize");
        writer.writeCharacters(Integer.toString(textSize));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(textStyle==0){
            writer.writeStartElement("textStyle");
            writer.writeCharacters("plain");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
        }
        else{
        	if((textStyle&1)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("bold");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&2)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("italic");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&4)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("underline");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&8)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("outline");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&16)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("shadow");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&32)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("condensed");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&64)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("extend");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&128)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("group");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        }

        writer.writeStartElement("color");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("red");
            writer.writeCharacters(Integer.toString(color.getRed()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("green");
            writer.writeCharacters(Integer.toString(color.getGreen()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("blue");
            writer.writeCharacters(Integer.toString(color.getBlue()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("bgColor");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("red");
            writer.writeCharacters(Integer.toString(bgColor.getRed()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("green");
            writer.writeCharacters(Integer.toString(bgColor.getGreen()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("blue");
            writer.writeCharacters(Integer.toString(bgColor.getBlue()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("scaleIcon");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(scaleIcon)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("blendingMode");
        writer.writeCharacters(" ");
        switch(blendMode){
        case 0: writer.writeCharacters("copy"); break;
        case 1: writer.writeCharacters("blend"); break;
        case 2: writer.writeCharacters("add"); break;
        case 3: writer.writeCharacters("subtract"); break;
        case 4: writer.writeCharacters("multiply"); break;
        case 5: writer.writeCharacters("screen"); break;
        case 6: writer.writeCharacters("darken"); break;
        case 7: writer.writeCharacters("lighten"); break;
        case 8: writer.writeCharacters("difference"); break;
        case 9: writer.writeCharacters("hue"); break;
        case 10: writer.writeCharacters("color"); break;
        case 11: writer.writeCharacters("saturation"); break;
        case 12: writer.writeCharacters("luminosity"); break;
        }
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("blendingLevel");
        writer.writeCharacters(Integer.toString(blendLevel));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("name");
        writer.writeCharacters(name);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("script");
        for(int i=0; i<scriptList.size(); i++){
        	writer.writeCharacters(scriptList.get(i));
        	if(i<scriptList.size()-1){
        		writer.writeCharacters("\n");
        	}
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
	}
	

	//HCのスタックを変換
	@SuppressWarnings("unchecked")
	public boolean readButtonBlock(DataInputStream dis, int partSize){
		//System.out.println("====readButtonBlock====");
		//ブロックのデータを順次読み込み
		int flags = HCData.readCode(dis, 1);
		//System.out.println("flags:"+flags);
		setVisible(((flags>>7)&0x01)==0);
		//dontWrap = !( ((flags>>5)&0x01)!=0);
		//dontSearch = ((flags>>4)&0x01)!=0;
		//sharedText = ((flags>>3)&0x01)!=0;
		//fixedLineHeight = ! (((flags>>2)&0x01)!=0);
		//autoTab = ((flags>>1)&0x01)!=0;
		enabled = ! (((flags>>0)&0x01)!=0);
		top = HCData.readCode(dis, 2);
		//System.out.println("top:"+top);
		left = HCData.readCode(dis, 2);
		//System.out.println("left:"+left);
		int bottom = HCData.readCode(dis, 2);
		//System.out.println("bottom:"+bottom);
		height = bottom - top;
		int right = HCData.readCode(dis, 2);
		//System.out.println("right:"+right);
		width = right - left;
		int flags2 = HCData.readCode(dis, 1);
		showName = ((flags2>>7)&0x01)!=0;
		hilite = ((flags2>>6)&0x01)!=0;
		autoHilite = ((flags2>>5)&0x01)!=0;
		sharedHilite = !(((flags2>>4)&0x01)!=0);
		group = (flags2)&0x0F;
		int style = HCData.readCode(dis, 1);
		//0標準 1透明 2不透明 3長方形 4シャドウ 5丸みのある長方形 6省略時設定 7楕円 8ポップアップ 9チェックボックス 10ラジオ
		switch(style){
		case 0: this.style = 1; break;//transparent
		case 1: this.style = 2; break;//opaque
		case 2: this.style = 3; break;//rectangle
		case 3: this.style = 5; break;//roundRect
		case 4: this.style = 4; break;//shadow
		case 5: this.style = 9; break;//checkBox
		case 6: this.style = 10; break;//radioButton
		//case 7: this.style = 0; break;//scrolling
		case 8: this.style = 0; break;//standard
		case 9: this.style = 6; break;//default
		case 10: this.style = 7; break;//oval
		case 11: this.style = 8; break;//popup
		}
		titleWidth = HCData.readCode(dis, 2);
		//System.out.println("titleWidth:"+titleWidth);
		if(this.style == 8){
			selectedLine = HCData.readCode(dis, 2);
			//System.out.println("selectedLine:"+selectedLine);
		}
		else{
			icon = HCData.readCode(dis, 2);
			//System.out.println("icon:"+icon);
		}
		int inTextAlign = HCData.readCode(dis, 2);
		switch(inTextAlign){
		case 0: textAlign = 0; break;
		case 1: textAlign = 1; break;
		case -1: textAlign = 2; break;
		}
		//System.out.println("inTextAlign:"+inTextAlign);
		int textFontID = HCData.readCode(dis, 2);
		//System.out.println("textFontID:"+textFontID);
		textSize = HCData.readCode(dis, 2);
		//System.out.println("textSize:"+textSize);
		textStyle = HCData.readCode(dis, 1);
		//System.out.println("textStyle:"+textStyle);
		/*int filler =*/ HCData.readCode(dis, 1);
		//System.out.println("filler:"+filler);
		textHeight = HCData.readCode(dis, 2);
		//System.out.println("textHeight:"+textHeight);
		resultStr nameResult = HCData.readTextToZero(dis, partSize - 30);
		name = nameResult.str;
		//HCStackDebug.debuginfo("name:"+name);
		//System.out.println("name:"+name);
		/*int filler2 =*/// HCData.readCode(dis, 1);
		//System.out.println("filler2:"+filler2);
		resultStr scriptResult = HCData.readText(dis, partSize - 30 - nameResult.length_in_src);
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);

		//フォント名をテーブルから検索
		OStack stack = ((OCardBase)this.parent).stack;
		for(int i=0; i<stack.fontList.size();i++){
			if(stack.fontList.get(i).id ==textFontID){
				textFont = stack.fontList.get(i).name;
				break;
			}
		}

		int remainLength = partSize - (30+(nameResult.length_in_src)+(scriptResult.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		//HCStackDebug.debuginfo("remainLength:"+remainLength);
		if(remainLength<0 || remainLength > 1000){
			//System.out.println("!");
		}
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
			//HCStackDebug.debuginfo("padding:"+padding);
		}
		/*if((nameResult.length_in_src+1+scriptResult.length_in_src+1)%2 != 0){
			String padding = HCData.readStr(dis, 1);
			System.out.println("padding:"+padding);
		}*/

		//スクリプト
		String[] scriptAry = scriptStr.split("\n");
		for(int i=0; i<scriptAry.length; i++)
		{
			scriptList.add(scriptAry[i]);
		}
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		
		return true;
	}
	
	
	static void buildOButton(OButton obtn)
	{
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
			obtn.btn.setFont(new Font(obtn.textFont, obtn.textStyle & 0x03, obtn.textSize));
			if(!obtn.showName) obtn.btn.setText("");
	        if((obtn.textStyle&8) > 0 || (obtn.textStyle&16) > 0){//outline,shadow
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
			obtn.btn.setVisible(obtn.getVisible());
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
			if(obtn.height>=19 && obtn.height<=20){
				obtn.height = 20; //hcのデフォルトでは上が消えてしまう
			}
			obtn.popup.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.popup.setEnabled(obtn.enabled);
			//popup(autoHilite);
			//popup.setSelected(hilite);
			try{obtn.popup.setSelectedIndex(obtn.selectedLine-1);}catch(Exception e){System.out.println("err: popup.setSelectedIndex(selectedLine-1)");}
			obtn.popup.setFont(new Font(obtn.textFont, obtn.textStyle, obtn.textSize));
			if(obtn.showName) obtn.popup.setToolTipText(obtn.name);
			obtn.popup.setFocusable(false);
			obtn.popup.setMaximumRowCount(20);
			PCARD.pc.mainPane.add(obtn.popup);
			obtn.popup.setVisible(obtn.getVisible());
			obtn.addListener();
		}
		else if(obtn.style==9) {
			obtn.chkbox = new MyCheck(obtn, obtn.name);
			obtn.addListener();
			obtn.chkbox.setFocusPainted(false);
			obtn.chkbox.setForeground(obtn.color);
			obtn.chkbox.setBackground(obtn.bgColor);
			obtn.chkbox.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.chkbox.setEnabled(obtn.enabled);
			//chkbox.setAutoHilite(autoHilite);
			obtn.chkbox.setSelected(obtn.hilite);
			obtn.chkbox.setFont(new Font(obtn.textFont, obtn.textStyle, obtn.textSize));
			obtn.chkbox.setVisible(obtn.getVisible());
			if(!obtn.showName) obtn.chkbox.setText("");
			obtn.chkbox.setFocusable(false);
			PCARD.pc.mainPane.add(obtn.chkbox);
		}
		else if(obtn.style==10) {
			obtn.radio = new MyRadio(obtn, obtn.name);
			obtn.addListener();
			obtn.radio.setFocusPainted(false);
			obtn.radio.setForeground(obtn.color);
			obtn.radio.setBackground(obtn.bgColor);
			obtn.radio.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
			obtn.radio.setEnabled(obtn.enabled);
			//chkbox.setAutoHilite(autoHilite);
			obtn.radio.setSelected(obtn.hilite);
			obtn.radio.setFont(new Font(obtn.textFont, obtn.textStyle, obtn.textSize));
			obtn.radio.setVisible(obtn.getVisible());
			obtn.radio.setFocusable(false);
			if(!obtn.showName) obtn.radio.setText("");
			PCARD.pc.mainPane.add(obtn.radio);
			if(btnGroup[obtn.group]==null) {
				btnGroup[obtn.group] = new ButtonGroup();
			}
			btnGroup[obtn.group].add(obtn.radio);
		}
	}
    
    void addListener(){
    	if(card==null) return;
		if(btn!=null) {
			btn.addMouseListener(card.stack.GUI_btn);
			btn.addMouseMotionListener(card.stack.GUI_btn);
			btn.addActionListener(card.stack.GUI_btn);
		}
		else if(popup!=null) {
			popup.addPopupMenuListener(card.stack.GUI_popup);
			popup.addActionListener(card.stack.GUI_popup);
		}
		else if(chkbox!=null) {
			chkbox.addMouseListener(card.stack.GUI_check);
			chkbox.addActionListener(card.stack.GUI_check);
		}
		else if(radio!=null) {
			radio.addMouseListener(card.stack.GUI_radio);
			radio.addActionListener(card.stack.GUI_radio);
		}
	}
    
    void removeListener(){
		if(btn!=null) {
			btn.removeMouseListener(card.stack.GUI_btn);
			btn.removeMouseMotionListener(card.stack.GUI_btn);
			btn.removeActionListener(card.stack.GUI_btn);
		}
		else if(popup!=null) {
			popup.removePopupMenuListener(card.stack.GUI_popup);
			popup.removeActionListener(card.stack.GUI_popup);
		}
		else if(chkbox!=null) {
			chkbox.removeMouseListener(card.stack.GUI_check);
			chkbox.removeActionListener(card.stack.GUI_check);
		}
		else if(radio!=null) {
			radio.removeMouseListener(card.stack.GUI_radio);
			radio.removeActionListener(card.stack.GUI_radio);
		}
	}
    
    static btnOutlineListen btnOutlineListen = new btnOutlineListen();
    void addListener(MouseListener listener){
		if(getComponent()!=null){
			getComponent().addMouseListener(listener);
		}
    }
    void removeListener(MouseListener listener){
		if(getComponent()!=null){
			getComponent().removeMouseListener(listener);
		}
    }
    void addMotionListener(MouseMotionListener listener){
		if(getComponent()!=null){
			getComponent().addMouseMotionListener(listener);
		}
    }
    void removeMotionListener(MouseMotionListener listener){
		if(getComponent()!=null){
			getComponent().removeMouseMotionListener(listener);
		}
    }
}

class btnOutlineListen implements MouseListener {

    public void mouseClicked(MouseEvent e) {
    	if(e.getSource().getClass()==MyButton.class){
    		ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, ((MyButton)e.getSource()).btnData);
    	}
    	else if(e.getSource().getClass()==MyPopup.class){
    		ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, ((MyPopup)e.getSource()).btnData);
    	}
    	else if(e.getSource().getClass()==MyRadio.class){
    		ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, ((MyRadio)e.getSource()).btnData);
    	}
    	else if(e.getSource().getClass()==MyCheck.class){
    		ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, ((MyCheck)e.getSource()).btnData);
    	}
		GUI.addAllListener();
    }
	
    public void mousePressed(MouseEvent e) {
    }
    public void mouseReleased(MouseEvent e) {
    }
	public void mouseEntered(MouseEvent arg0) {
	}
	public void mouseExited(MouseEvent arg0) {
	}
}

class MyButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	boolean autoHilite=true;
	boolean hilite=false;
	OButton btnData=null;
	
	public MyButton(OButton btn, String text){
		super(text);
		btnData=btn;
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
	}
    void setAutoHilite(boolean b){
    	autoHilite = b;
    }
    void setHilite(boolean b){
    	hilite = b;
    	btnData.check_hilite = b;
    	getModel().setArmed(hilite);
    }
    boolean getHilite(){
        if(autoHilite && PCARD.editMode == 0) return getModel().isArmed();
    	return hilite;
    }
    
	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible() || PCARD.lockedScreen) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		if(PCARD.pc.stack.curCard == null || btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;

		if(btnData.blendMode==1){
			((Graphics2D)g).setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, btnData.blendLevel/100.0f));
		}
		
		if(autoHilite || hilite==getModel().isArmed()) {
        	if(!btnData.showName&&btnData.style>=1&&btnData.style<=3){
        		if(btnData.style>=2){
        			g.setColor(btnData.bgColor);
        			Rectangle r = g.getClipBounds();
        			g.fillRect(r.x, r.y, r.width, r.height);
        		}
        		if(btnData.style==3){
        			g.setColor(btnData.color);
        			g.drawRect(btnData.left, btnData.top, btnData.width, btnData.height);
        		}
        		Icon ic = this.getIcon();
        		if(ic!=null){
        			if(btnData.getScaleIcon() && btnData.imageForScale!=null){
        				g.drawImage(btnData.imageForScale, 0, 0, getWidth(), getHeight(), 
        						0, 0, btnData.imageForScale.getWidth(), btnData.imageForScale.getHeight(), null);
        			}
        			else{
        				int w = ic.getIconWidth();
        				int h = ic.getIconHeight();
        				ic.paintIcon(this, g, (btnData.width-w)/2, (btnData.height-h)/2);
        			}
        		}/*else{
        			BufferedImage img = btnData.iconImage;
        			if(img!=null){
        		        //g2.setColor(getBackground());
        				g.drawImage(img, (btnData.width-img.getWidth())/2, (btnData.height-img.getHeight())/2, this);
        			}
        		}*/
        	}else if(btnData.getScaleIcon() && btnData.imageForScale!=null){
				g.drawImage(btnData.imageForScale, 0, 0, getWidth(), getHeight(), 
						0, 0, btnData.imageForScale.getWidth(), btnData.imageForScale.getHeight(), null);
			}
        	else{
        		super.paintComponent(g);
        	}
        }
        else if(btnData.getScaleIcon() && btnData.imageForScale!=null){
			g.drawImage(btnData.imageForScale, 0, 0, getWidth(), getHeight(), 
					0, 0, btnData.imageForScale.getWidth(), btnData.imageForScale.getHeight(), null);
		}
        else {
        	getModel().setArmed(hilite);
            super.paintComponent(g);
        }
		if(AuthTool.tool!=null && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }
}

class RoundedCornerButton extends MyButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final float arcwidth  = 16.0f;
	private static final float archeight = 16.0f;
	protected static final int focusstroke = 2;
	protected final Color fc = new Color(100,150,255,200);
	//protected final Color ac = new Color(0,0,0);
	//protected final Color rc = Color.ORANGE;
	protected Shape shape;
	protected Shape border;
	protected Shape base;

	public RoundedCornerButton(OButton btn, String text) {
	    super(btn, text);
	    //setRolloverEnabled(true);
	    setContentAreaFilled(false);
	    setBackground(new Color(255, 255, 255));
	    initShape();
	}
	
	protected void initShape() {
	    if(!getBounds().equals(base)) {
	    	base = getBounds();
	    	shape = new RoundRectangle2D.Float(0, 0,
	    			getWidth()-1, getHeight()-1,
	    			arcwidth, archeight);
	    	border = new RoundRectangle2D.Float(focusstroke, focusstroke,
	    			getWidth()-1-focusstroke*2,
	    			getHeight()-1-focusstroke*2,
	    			arcwidth, archeight);
		}
	}
    /*private void paintFocusAndRollover(Graphics2D g2, Color color) {
        g2.setPaint(new GradientPaint(0, 0, color, getWidth()-1, getHeight()-1, color.brighter(), true));
        g2.fill(shape);
        g2.setColor(getBackground());
        g2.fill(border);
    }*/

    @Override
    protected void paintComponent(Graphics g) {
		if(!isVisible() || PCARD.lockedScreen) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		if(btnData.card!=PCARD.pc.stack.curCard) return;
        initShape();
        Graphics2D g2 = (Graphics2D)g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if(getHilite()) {
        	if(btnData.style!=1 && btnData.style!=7){ //透明と楕円はXOR
	            g2.setColor(Color.BLACK);
	            g2.fill(shape);
        	}else{
            	g2.setXORMode(Color.BLACK);
	            g2.fill(shape);
        	}
            setForeground(btnData.bgColor);
			setBackground(btnData.color);
        //}else if(isRolloverEnabled() && getModel().isRollover()) {
            //paintFocusAndRollover(g2, rc);
        //}else if(hasFocus() && isFocusPainted()) {
            //paintFocusAndRollover(g2, fc);
        }else{
        	if(btnData.style!=1 && btnData.style!=7){ //透明と楕円は書かない
        		g2.setColor(getBackground());
        		g2.fill(shape);
            }
            setForeground(btnData.color);
			setBackground(btnData.bgColor);
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setColor(getBackground());
        super.paintComponent(g2);
        if((btnData.textStyle&8) > 0) { //outline
        	g2.setFont(new Font(btnData.textFont, Font.PLAIN, btnData.textSize));
    		FontMetrics fo = g2.getFontMetrics();
        	int w = fo.stringWidth(btnData.name);
        	g2.setPaintMode();
        	g2.setColor(Color.BLACK);
        	g2.drawString(btnData.name,(btnData.width-w)/2-1,(btnData.height+btnData.textSize)/2-0);
        	g2.drawString(btnData.name,(btnData.width-w)/2-0,(btnData.height+btnData.textSize)/2-1);
        	g2.drawString(btnData.name,(btnData.width-w)/2+1,(btnData.height+btnData.textSize)/2+0);
        	g2.drawString(btnData.name,(btnData.width-w)/2+0,(btnData.height+btnData.textSize)/2+1);
        	g2.setColor(Color.WHITE);
        	g2.drawString(btnData.name,(btnData.width-w)/2,(btnData.height+btnData.textSize)/2);
        	g2.setColor(getBackground());
        	return;
        }
        if((btnData.textStyle&16) > 0) { //shadow
        	g2.setFont(new Font(btnData.textFont, Font.PLAIN, btnData.textSize));
    		FontMetrics fo = g2.getFontMetrics();
        	int w = fo.stringWidth(btnData.name);
        	g2.setColor(Color.BLACK);
        	g2.drawString(btnData.name,(btnData.width-w)/2+0,(btnData.height+btnData.textSize)/2+1);
        	g2.drawString(btnData.name,(btnData.width-w)/2+1,(btnData.height+btnData.textSize)/2+0);
        	g2.drawString(btnData.name,(btnData.width-w)/2+1,(btnData.height+btnData.textSize)/2+1);
        	g2.setColor(Color.WHITE);
        	g2.drawString(btnData.name,(btnData.width-w)/2,(btnData.height+btnData.textSize)/2);
        	g2.setColor(getBackground());
        	return;
        }
    }
    @Override
    protected void paintBorder(Graphics g) {
		if(!isVisible() || PCARD.lockedScreen) return;
		if(PCARD.pc.bit > 1) return;
		if(btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
        if(isBorderPainted()){
	        initShape();
	        Graphics2D g2 = (Graphics2D)g;
	        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	        g2.setColor(btnData.color);
	        g2.draw(shape);
	        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }
		if(AuthTool.tool!=null && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
		else if((btnData.style==1 || btnData.style==2) && AuthTool.tool!=null && AuthTool.tool.getClass()==ButtonTool.class ){
			g.setColor(Color.BLACK);
			g.drawRect(0,0,btnData.width-1,btnData.height-1);
		}
    }
    @Override
    public boolean contains(int x, int y) {
        initShape();
        return shape.contains(x, y);
    }
}

class RectButton extends RoundedCornerButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public RectButton(OButton btn, String text) {
	    super(btn, text);
		setFocusPainted(false);
		initShape();
	}

	protected void initShape() {
		if (!getBounds().equals(base)) {
			base = getBounds();
			shape = new Rectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1);
			border = new Rectangle2D.Float(focusstroke, focusstroke, getWidth()
					- 1 - focusstroke * 2, getHeight() - 1 - focusstroke * 2);
		}
	}
	
	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(PCARD.pc.stack.curCard==null || btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		if(PCARD.pc.bit > 1) return;
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.lockedScreen&&paneg==g) return;
        Graphics2D g2 = (Graphics2D)g;
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        if(btnData.style==1){ //透明
        	if(autoHilite && getHilite()) {
                initShape();
            	g2.setXORMode(Color.WHITE);
	            g2.fill(shape);
	        }
        	else
        	{
            	//g2.setXORMode(Color.WHITE);
        	}
		}
        if(btnData.style==2){ //不透明
            initShape();
        	if(autoHilite && getHilite()) {
            	g2.setXORMode(Color.WHITE);
	            g2.fill(shape);
	            g2.setColor(getBackground());
	        }else{
	            g2.setColor(getBackground());
	            g2.fill(shape);
	        }
		}
        if(btnData.style==3 || btnData.style==4){ //長方形 シャドウ
            initShape();
        	if(getHilite()) {
	            //g2.setColor(Color.WHITE);
	            //g2.fill(shape);
        		//g2.setColor(getBackground());
	        }else{
	            //g2.setColor(getBackground());
	            //g2.fill(shape);
	        }
		}
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        ////g2.setColor(getBackground());
        super.paintComponent(g2);
    }
    @Override
    protected void paintBorder(Graphics g) {
		if(!isVisible() || PCARD.lockedScreen) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		if(PCARD.pc.stack.curCard==null || btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
        if(btnData.style==4){
	        g.setColor(btnData.color);
	        Rectangle b = getBounds();
	        g.drawLine(0, 0, b.width-2, 0);
	        g.drawLine(0, 0, 0, b.height-2);
	        g.drawLine(b.width-1, 2, b.width-1, b.height);
	        g.drawLine(b.width-2, 1, b.width-2, b.height);
	        g.drawLine(2, b.height-1, b.width, b.height-1);
	        g.drawLine(1, b.height-2, b.width, b.height-2);
        }
        else{
        	super.paintBorder(g);
        }
    }
}

class RoundButton extends RoundedCornerButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public RoundButton(OButton btn, String text) {
	    super(btn, text);
		setFocusPainted(false);
		initShape();
	}

	protected void initShape() {
		if (!getBounds().equals(base)) {
			base = getBounds();
			shape = new Ellipse2D.Float(0, 0, getWidth() - 1, getHeight() - 1);
			border = new Ellipse2D.Float(focusstroke, focusstroke, getWidth()
					- 1 - focusstroke * 2, getHeight() - 1 - focusstroke * 2);
		}
	}

	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.lockedScreen&&paneg==g) return;
        initShape();
        Graphics2D g2 = (Graphics2D)g;
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        if(getHilite()) {
        	g.setXORMode(Color.WHITE);
            g2.fill(shape);
            g2.setColor(getBackground());
        }else{
        }
        //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        super.paintComponent(g2);
        
        //super.paintComponent(g2);
		if(AuthTool.tool!=null && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }
    @Override
    protected void paintBorder(Graphics g) {
    }
}


class MyRadio extends JRadioButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OButton btnData=null;
	
	public MyRadio(OButton btn, String text){
		super(text);
		btnData=btn;
		setMargin(new Insets(0,0,0,0));
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
	}
    @Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(btnData==null||PCARD.pc.stack.curCard==null||btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.lockedScreen&&paneg==g) return;
		super.paintComponent(g);
		if(AuthTool.tool!=null && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }
}

class MyCheck extends JCheckBox {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OButton btnData=null;
	
	public MyCheck(OButton btn, String text){
		super(text);
		btnData=btn;
		setMargin(new Insets(0,0,0,0));
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
	}
    @Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.lockedScreen&&paneg==g) return;
		super.paintComponent(g);
		if(AuthTool.tool!=null && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }
}

class MyPopup extends JComboBox {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OButton btnData=null;
	
	public MyPopup(OButton btn, String[] text){
		super(text);
		btnData=btn;
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
	}
	boolean flag;
    @Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		//if(btnData.card!=PCARD.pc.stack.curCard && btnData.card!=PCARD.pc.stack.curCard.bg) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && btnData.parent.objectType.equals("card")) return;
		//Graphics paneg = PCARD.pc.mainPane.getGraphics();
		//if(PCARD.lockedScreen&&paneg==g) return;
		this.validate();
		super.paintComponent(g);
		if(AuthTool.tool!=null && ButtonGUI.gui.target == this){
			ButtonGUI.drawSelectBorder(this);
		}
    }
}


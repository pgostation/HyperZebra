import java.awt.Color;
import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import java.nio.charset.Charset;
import java.io.*;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
//import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OStack extends OObject {
	PCARDFrame pcard;
	
	Charset charset= Charset.forName("UTF-8");//文字コード
	String path="";//スタックファイルのパス
	File file;
	OCard curCard;
	GButton GUI_btn;
	GRadio GUI_radio;
	GCheckBox GUI_check;
	GPopup GUI_popup;
	GField GUI_fld;
	ScriptEditor scriptEditor;
	JPanel mainPane;
	Rsrc rsrc = new Rsrc(this);
	String[] Pattern = new String[40];
	BufferedImage addColor;
	boolean changed;
	XMLwrite saveXML;
	ArrayList<OStack> usingStacks = new ArrayList<OStack>();

	//進捗ダイアログ
	JDialog barDialog;
	JProgressBar bar;
	int barOffset;
	int totalSize;
	
	//プロパティ
	boolean cantAbort=false;
	boolean cantDelete=false;
	boolean cantModify=false;
	boolean cantPeek=false;
	int firstCard;//toc.xml
	int userLevel;//TODO:このレベル以下に制限
	private boolean privateAccess;//toc.xml
	String createdByVersion;//toc.xml
	String lastCompactedVersion;//toc.xml
	String lastEditedVersion;//toc.xml
	String firstEditedVersion;//toc.xml
	private int fontTableID;//toc.xml
	private int styleTableID;//toc.xml
	private int nextStyleID;//toc.xml
	private int listId;//toc.xml
	private int passwordHash;
	Rectangle screenRect;
	Rectangle windowRect;
	Point scroll;
	int firstBg;
	
	//追加プロパティ
	boolean resizable=false;
	
	//カード情報
	ArrayList<OCard> cdCacheList = new ArrayList<OCard>();
	ArrayList<OBackground> bgCacheList = new ArrayList<OBackground>();
	ArrayList<Integer> cardIdList = new ArrayList<Integer>();
	ArrayList<fontClass> fontList = new ArrayList<fontClass>();
	ArrayList<styleClass> styleList = new ArrayList<styleClass>();

	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}
	public int getLeft() {return left;}
	public int getTop() {return top;}
	public int getWidth() {return width;}
	public int getHeight() {return height;}
	public Boolean getCantAbort() {return cantAbort;}
	public Boolean getCantDelete() {return cantDelete;}
	public Boolean getCantModify() {return cantModify;}
	public Boolean getCantPeek() {return cantPeek;}
	public Boolean getResizeable() {return resizable;}

	public void clean(){
		if(curCard!=null){
			if(curCard.bg!=null){
				//curCard.bg.removeData();
				curCard.bg.clean();
			}
			//curCard.removeData();
			curCard.clean();
		}
		pcard = null;
		curCard = null;
		scriptEditor = null;
		mainPane = null;
		Pattern = null;
		addColor = null;
		cdCacheList = null;
		bgCacheList = null;
		cardIdList = null;
		fontList = null;
		styleList = null;
		saveXML = null;
		if(PCARD.pc.stack.usingStacks!=null && PCARD.pc.stack.usingStacks.contains(this)){
			return;
		}
		usingStacks = null;
		rsrc = null;
		super.clean();
	}
	
	//set
	public void setName(String in) {
		name=in;
		pcard.setTitle(name);
	}
	
	public void setTopLeft(int h, int v) {
		left=h;
		top=v;
		pcard.setBounds(left, top, width, height+pcard.getInsets().top);
	}
	public void setRect(int v1, int v2, int v3, int v4) {
		left=v1;
		top=v2;
		width=v3-v1;
		height=v4-v2;
		pcard.setBounds(left, top, width, height+pcard.getInsets().top);
	}
	public void setCantAbort(Boolean in) {cantAbort=in;}
	public void setCantDelete(Boolean in) {cantDelete=in;}
	public void setCantModify(Boolean in) {cantDelete=in;}
	public void setCantPeek(Boolean in) {cantPeek=in;}
	public void setResizeable(Boolean in) {
		resizable=in;
		pcard.setResizable(resizable); 
	}
	public void setScroll(int x, int y){
		scroll = new Point(x,y);
		pcard.setNewBounds();
	}
	
	//メイン
	public OStack(PCARDFrame pc) {
    	objectType="stack";
    	
    	pcard = pc;
    	//PCARD.pc.stack = this;
		GUI_btn = new GButton();
		GUI_radio = new GRadio();
		GUI_check = new GCheckBox();
		GUI_popup = new GPopup();
		GUI_fld = new GField();
		mainPane = pc.mainPane;

    	if(mainPane!=null){
    		mainPane.setLayout(null);
    		mainPane.setBackground(Color.WHITE);
    	}
		
		try{
			//UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
			//UIManager.setLookAndFeel("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
			//UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
			//UIManager.setLookAndFeel("com.apple.laf.AquaLookAndFeel");
		} catch ( Exception e ) {
			  System.out.println("LookAndFeel：" + e );
		}
		
		
		left=0; top=0;
		width=512; height=384;
		
		fontTableID = (int)(20000*Math.random()+1000);
		styleTableID = (int)(20000*Math.random()+1000);
		listId = (int)(20000*Math.random()+1000);
		
		//mainPane.setBounds(left, top, width, height+pcard.getInsets().top);
    }

	
    @SuppressWarnings("unchecked")
	public void buildStackFile(boolean isUsingOnly)
    {
    	if(scriptList==null){
    		scriptList = new ArrayList<String>();
    	}
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		handlerLineList = null;
    	
		if(isUsingOnly){
			return;
		}
		
		if(PCARD.home!=null){
			usingStacks.add(PCARD.home);
		}
		
		
		if(PCARD.pc.toolbar==null){
			PCARD.pc.toolbar = new GToolBar(PCARD.pc);
		}
		
		String sepStr = ""+File.separatorChar;
		if(File.separatorChar=='\\'){
			sepStr = "\\\\";
		}
    	String[] strArray = path.split(sepStr);
		//name=strArray[strArray.length-1];
    	if(strArray.length-2>=0){
    		name=strArray[strArray.length-2];
    	}else{
    		name=path;
    	}
		String titleName = name;
		if(titleName.length()>5 && titleName.substring(titleName.length()-5).equals(".xstk")){
			titleName = titleName.substring(0,titleName.length()-5);
		}
		pcard.setTitle(titleName);
		
		mainPane.setBounds(left, top, width, height+pcard.getInsets().top);
		mainPane.setVisible(true);
		Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
		if(d!=null){
			pcard.setBounds(d.width/2-width/2, d.height/2-height/2-20, width, height+20);
		}else{
			pcard.setBounds(left, top-height-20, width, height+20);
		}
		pcard.setVisible(true);
		//pcard.setLocationRelativeTo(null);
		pcard.setResizable(resizable); 
		setNewBounds();
		
		PCARD.pc.msg.setBounds(PCARD.pc.getX()+PCARD.pc.getWidth()/2-240,PCARD.pc.getY()+PCARD.pc.getHeight(),480,28+PCARD.pc.getInsets().top);

		
		//1番目のカードを開く
		if(cardIdList.size()>=1){
			mainPane.removeAll();
			try {
				curCard = OCard.getOCard(this, cardIdList.get(0), false);
				curCard.bg = OBackground.getOBackground(this, curCard, curCard.bgid, false);
				curCard.parent = curCard.bg;
			} catch (xTalkException e) {
				e.printStackTrace();
				curCard = cdCacheList.get(0);
				try {
					curCard.bg = OBackground.getOBackground(this, curCard, curCard.bgid, false);
					curCard.parent = curCard.bg;
				} catch (xTalkException e2) {
					e2.printStackTrace();
				}
			}

	    	PCARD.lockedScreen = false;
			mainPane.repaint();
			//TTalk.talk = new TTalk();
			//TTalk.talk.start();
			TTalk.CallMessage("startUp",curCard);
			TTalk.CallMessage("openStack",curCard);
			TTalk.CallMessage("openBackground",curCard);
			TTalk.CallMessage("openCard",curCard);
		}
		
		//変更監視&XML保存のスレッドを立ち上げておく
		if(!System.getProperty("java.version").startsWith("1.5")){ //JRE1.5.xは非対応
			saveXML = new XMLwrite(this);
		}
    }

    public void setNewBounds(){
    	Rectangle r = PCARD.pc.stack.pcard.getBounds();
		PCARD.pc.stack.pcard.setBounds(r.x, r.y, width+PCARD.pc.toolbar.getTWidth(), height+PCARD.pc.toolbar.getTHeight()+pcard.getInsets().top);
    }

    
    void openStackFile(String inpath, boolean isUsingOnly)
    {
		/*this.scriptList = new ArrayList<String>();
		
		if(PCARD.pc.stack.curCard!=null){
			if(PCARD.pc.stack.curCard.bg!=null) {
				PCARD.pc.stack.curCard.bg.removeData();
				PCARD.pc.stack.curCard.bg = null;
			}
			PCARD.pc.stack.curCard.removeData();
			PCARD.pc.stack.curCard = null;
		}
		*/

    	BufferedImage bi = null;
    	if(isUsingOnly==false){
    		//画像ファイルかどうか
			try{
				bi = PictureFile.loadPbm(inpath);
				if(bi==null){
					bi = javax.imageio.ImageIO.read(new File(inpath));
				}
				if(bi==null){
					bi = PictureFile.loadPICT(path);
				}
			} catch (Exception e2) {
				//e2.printStackTrace();
			}
    	}
		
		if(bi!=null){
			TTalk.CallMessage("edit picture \""+inpath+"\"", "", null, false, true);
		}else if(isUsingOnly==false){
			TTalk.CallMessage("open stack \""+inpath+"\"", "", null, false, true);
		}else if(isUsingOnly==true){
			TTalk.CallMessage("start using stack \""+inpath+"\"", "", null, false, true);
		}
    }
    
    boolean openStackFileInThread(String inpath, boolean isUsingOnly)
    {
    	
    	path = inpath;
		file = new File(path);
		if(!file.exists()){
			PCARD.pc.failureOpenFile(inpath);
			return false;
		}

		this.scriptList = new ArrayList<String>();
		
		{
			FileInputStream fstream = null;
			try {
				fstream = new FileInputStream(path);
			} catch (FileNotFoundException e) {
			}
			if(fstream != null){
				//HCスタックとして読んでみる
				int size = 0;
				String STAKstr = "";
				try {
					for(int i=0; i<4; i++){
						size = size<<8;
						size += (byte)fstream.read();
					}
					for(int i=0; i<4; i++){
						STAKstr += (char)fstream.read();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				if(STAKstr.equals("STAK")){
					//これはきっとHyperCardスタックファイル
					if(true == HCConvert.openHCStack(path, this)){
						if(PCARD.pc.stack.cdCacheList.size()>0){
							PCARD.pc.successOpenFile();
							return true;
						}
					}
				}
				else if(true/*path.substring(path.length()-4).equals(".bin")*/){
					//MacBinary形式では？
					if(true == HCConvert.openMacBinaryStack(path, this)){
						if(this.cdCacheList.size()>0){
							PCARD.pc.successOpenFile();
							return true;
						}
					}
				}
			}
			//XML形式として読む
			if(System.getProperty("java.version").startsWith("1.5")){
				new GDialog(PCARD.pc, PCARDFrame.pc.intl.getDialogText("This version of Java Runtime is not supported streaming API for XML."),
						null,"OK",null,null);
			}
			else{
				if(new File(path).isDirectory()){
					path += File.separatorChar+"_stack.xml";
					if(!new File(path).exists()){
						path = inpath + File.separatorChar+"toc.xml";
					}
				}
				
				if(XMLRead.readToc(path, this)){
					if(this.cdCacheList.size()>0){
						file = new File(path);
						PCARD.pc.successOpenFile();
						return true;
					}
				}
			}
		}

		name="";
		PCARD.pc.failureOpenFile(inpath);
		return false;
    }

    
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
		int pattern_id = 0;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("cardCount")){  }
	            	else if(elm.equals("backgroundCount")){  }
	            	else if(elm.equals("stackID")){  }
	            	else if(elm.equals("format")){  }
	            	else if(elm.equals("cardID")){ firstCard = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("firstCardID")){ firstCard = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("firstBackgroundID")){ firstBg = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("listID")){ listId = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("password")){ passwordHash = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("userLevel")){ userLevel = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("cantModify")){ cantModify = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantDelete")){ cantDelete = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("privateAccess")){ privateAccess = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantAbort")){ cantAbort = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantPeek")){ cantPeek = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("createdByVersion")){ createdByVersion = reader.getElementText(); }
	            	else if(elm.equals("lastCompactedVersion")){ lastCompactedVersion = reader.getElementText(); }
	            	else if(elm.equals("lastEditedVersion")){ lastEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("modifyVersion")){ lastEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("firstEditedVersion")){ firstEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("openVersion")){ firstEditedVersion = reader.getElementText(); }
	            	else if(elm.equals("version")){ reader.getElementText(); }
	            	else if(elm.equals("fontTableID")){ fontTableID = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("styleTableID")){ styleTableID = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("cardSize")){  }
	            	else if(elm.equals("width")){ width = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("height")){ height = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("patterns")){  }
	            	else if(elm.equals("pattern")){ 
	            		if(pattern_id<Pattern.length){
	            			Pattern[pattern_id] = reader.getElementText();
		            		pattern_id++;
	            		}
	            	}
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		String[] scriptAry = scrStr.split("\n");
	            		for(int i=0; i<scriptAry.length; i++)
	            		{
	            			scriptList.add(scriptAry[i]);
	            		}
	            	}
	            	else if(elm.equals("true")); //dummy 
	            	else if(elm.equals("false")); //dummy 
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("stack")){
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
	
	
	public XMLStreamReader readFontXML(XMLStreamReader reader) throws Exception {
        String idStr = "0", nameStr = "";
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("id")){ idStr = reader.getElementText(); }
	            	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("font")){
	            		fontList.add(new fontClass(Integer.valueOf(idStr), nameStr));
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    System.err.println(ex.getMessage());
	        }
	    }
	    return reader;
	}
	
	class fontClass{
		int id;
		String name;
		public fontClass(int id, String name){
			this.id = id;
			this.name = name;
		}
	}
	
	
	public XMLStreamReader readStyleXML(XMLStreamReader reader) throws Exception {
		String idStr = "-1", sizeStr = "-1", fontStr = "-1", textStyle = "";
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("nextStyleID")){ nextStyleID = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textStyle")){ textStyle += reader.getElementText()+" "; }
	            	else if(elm.equals("id")){ idStr = reader.getElementText(); }
	            	else if(elm.equals("font")){ fontStr = reader.getElementText(); }
	            	else if(elm.equals("size")){ sizeStr = reader.getElementText(); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("nextStyleID")){
	            		break;
	            	}
	            	if(elm.equals("styleentry")){
	            		styleList.add(new styleClass(idStr, textStyle, fontStr, sizeStr));
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    System.err.println(ex.getMessage());
	        }
	    }
	    return reader;
	}
	
	class styleClass{
		int id;
		int style;//-1ならチェンジしない
		int font;//-1ならチェンジしない
		int size;//-1ならチェンジしない
		public styleClass(String id, String style, String font, String size){
			this.id = Integer.valueOf(id);
			this.style=0;
			if(style.equals("")) this.style=-1;
			if(style.contains("plain")) this.style=0;
			if(style.contains("bold")) this.style+=1;
			if(style.contains("italic")) this.style+=2;
			if(style.contains("underline")) this.style+=4;
			if(style.contains("outline")) this.style+=8;
			if(style.contains("shadow")) this.style+=16;
			if(style.contains("condensed")) this.style+=32;
			if(style.contains("extend")) this.style+=64;
			if(style.contains("group")) this.style+=128;
			this.font = Integer.valueOf(font);
			this.size = Integer.valueOf(size);
		}
	}

	
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("stack");
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("stackID");
        writer.writeCharacters("-1");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("format");
        writer.writeCharacters("11");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("backgroundCount");
        writer.writeCharacters(Integer.toString(bgCacheList.size()));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("firstBackgroundID");
        writer.writeCharacters(Integer.toString(firstBg));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("cardCount");
        writer.writeCharacters(Integer.toString(cardIdList.size()));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("firstCardID");
        writer.writeCharacters(Integer.toString(firstCard));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("listID");
        writer.writeCharacters(Integer.toString(listId));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("password");
        writer.writeCharacters(Integer.toString(passwordHash));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("userLevel");
        writer.writeCharacters(Integer.toString(userLevel));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantModify");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantModify)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantDelete");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantDelete)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("privateAccess");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(privateAccess)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantAbort");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantAbort)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantPeek");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(cantPeek)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("createdByVersion");
        writer.writeCharacters(createdByVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("lastCompactedVersion");
        writer.writeCharacters(lastCompactedVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("modifyVersion");
        writer.writeCharacters(PCARD.longVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("openVersion"); //lastOpenedVersion
        writer.writeCharacters(firstEditedVersion);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("fontTableID");
        writer.writeCharacters(Integer.toString(fontTableID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("styleTableID");
        writer.writeCharacters(Integer.toString(styleTableID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cardSize");
        writer.writeCharacters("\n\t\t\t");
        {
        	writer.writeStartElement("width");
	        writer.writeCharacters(Integer.toString(width));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
	        writer.writeStartElement("height");
	        writer.writeCharacters(Integer.toString(height));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t");
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("patterns");
        writer.writeCharacters("\n\t\t\t");
        for(int i=0; i<40; i++){
	        writer.writeStartElement("pattern");
	        writer.writeCharacters(Pattern[i]);
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("script");
        for(int i=0; i<scriptList.size(); i++){
        	writer.writeCharacters(scriptList.get(i));
        	if(i<scriptList.size()-1){
        		writer.writeCharacters("\n");
        	}
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t");

        writer.writeEndElement();
        writer.writeCharacters("\n\t");
	}

	
	public void writeFontXML(XMLStreamWriter writer) throws XMLStreamException {
        for(int i=0; i<fontList.size(); i++){
        	writer.writeStartElement("font");
	        writer.writeCharacters("\n\t\t");
        	{
		        writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(fontList.get(i).id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		        writer.writeStartElement("name");
		        writer.writeCharacters(fontList.get(i).name);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t");
        	}
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t");
        }
	}

	
	public void writeStyleXML(XMLStreamWriter writer) throws XMLStreamException {
		//TODO: dummy
        writer.writeStartElement("nextStyleID");
        writer.writeCharacters(Integer.toString(nextStyleID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t");

        for(int i=0; i<styleList.size(); i++){
	        writer.writeStartElement("styleentry");
	        writer.writeCharacters("\n\t\t");
	        {
		        writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(styleList.get(i).id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t");
		        
		        if(styleList.get(i).font!=-1){
			        writer.writeStartElement("font");
			        writer.writeCharacters(Integer.toString(styleList.get(i).font));
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		        
		        if(styleList.get(i).size!=-1){
			        writer.writeStartElement("size");
			        writer.writeCharacters(Integer.toString(styleList.get(i).size));
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		        
		        if(styleList.get(i).style!=-1){
		        	if(styleList.get(i).style==0){
				        writer.writeStartElement("textStyle");
				        writer.writeCharacters("plain");
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t");
		        	}
		        	else{
			        	String[] styleAry = new String[]{
			        			"bold", "italic", "underline", "outline",
			        			"shadow", "condensed", "extend", "group"};
			        	for(int j=0; j<8; j++){
			        		if((styleList.get(i).style&(1<<j))>0){
						        writer.writeStartElement("textStyle");
						        writer.writeCharacters(styleAry[j]);
						        writer.writeEndElement();
						        writer.writeCharacters("\n\t");
			        		}
			        	}
		        	}
		        }
	        }
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t");
        }
	}

	
	//HCのスタックを変換
	@SuppressWarnings("unchecked")
	public boolean readStackBlock(DataInputStream dis, int blockSize){
		////System.out.println("readStackBlock");

		if(blockSize>200000 || blockSize<50){
			return false;
		}
		
		//ブロックのデータを順次読み込み
		id = HCData.readCode(dis, 4);
		//System.out.println("blockId:"+id); //always -1
		/*String tygersStr =*/ HCData.readStr(dis, 4);
		//System.out.println("tygersStr:"+tygersStr);
		/*int format =*/ HCData.readCode(dis, 4);
		//System.out.println("format:"+format);
		totalSize = HCData.readCode(dis, 4);
		//System.out.println("totalSize:"+totalSize);
		/*int stackSize =*/ HCData.readCode(dis, 4);
		//System.out.println("stackSize:"+stackSize);
		/*int something =*/ HCData.readCode(dis, 4);
		//System.out.println("something:"+something); //wingsでは2、南方では0、鳥でも0、うにょでも0
		/*int tygers1Str =*/ HCData.readCode(dis, 4);
		//System.out.println("tygersStr:"+tygers1Str); //鳥では0、うにょでも0
		/*int numofBgs =*/ HCData.readCode(dis, 4);
		//System.out.println("numofBgs:"+numofBgs);
		firstBg = HCData.readCode(dis, 4);
		//System.out.println("firstBg:"+firstBg);
		/*int numofCards =*/ HCData.readCode(dis, 4);
		//System.out.println("numofCards:"+numofCards);
		firstCard = HCData.readCode(dis, 4);
		//System.out.println("firstCard:"+firstCard);
		listId = HCData.readCode(dis, 4);
		//System.out.println("listBlockId:"+listId);
		/*int numofFree =*/ HCData.readCode(dis, 4);
		//System.out.println("numofFree:"+numofFree);
		/*int freeSize =*/ HCData.readCode(dis, 4);
		//System.out.println("freeSize:"+freeSize);
		/*int printId =*/ HCData.readCode(dis, 4);
		//System.out.println("printId:"+printId);
		passwordHash = HCData.readCode(dis, 4);
		//System.out.println("passwordHash:"+passwordHash);
		userLevel = HCData.readCode(dis, 2);
		//System.out.println("userLevel:"+userLevel);
		/*String tygers3Str =*/ HCData.readStr(dis, 2);
		//System.out.println("tygers3Str:"+tygers3Str);
		int flags = HCData.readCode(dis, 2);
		//System.out.println("flags:"+flags);
		cantPeek = ((flags>>10)&0x01)!=0;
		cantAbort = ((flags>>11)&0x01)!=0;
		privateAccess = ((flags>>13)&0x01)!=0;
		cantDelete = ((flags>>14)&0x01)!=0;
		cantModify = ((flags>>15)&0x01)!=0;
		/*String tygers4Str =*/ HCData.readStr(dis, 18);
		//System.out.println("tygers4Str:"+tygers4Str);
		
		int createdByV = HCData.readCode(dis, 4);
		createdByVersion = getVers(createdByV);
		//System.out.println("createdVer:"+createdByVersion);
		
		int compactedV = HCData.readCode(dis, 4);
		lastCompactedVersion = getVers(compactedV);
		//System.out.println("compactedVer:"+lastCompactedVersion);
		
		int lastEditedV = HCData.readCode(dis, 4);
		lastEditedVersion = getVers(lastEditedV);
		//System.out.println("lastEditedVer:"+lastEditedVersion);
		
		int lastOpenedV = HCData.readCode(dis, 4);
		firstEditedVersion = getVers(lastOpenedV);
		//System.out.println("lastOpenedVer:"+firstEditedVersion);
		
		/*int checksum =*/ HCData.readCode(dis, 4);
		//System.out.println("checksum:"+checksum);
		/*String tygers41Str =*/ HCData.readStr(dis, 4);
		//System.out.println("tygers41Str:"+tygers41Str);
		windowRect = new Rectangle();
		windowRect.x = HCData.readCode(dis, 2);
		windowRect.y = HCData.readCode(dis, 2);
		windowRect.width = HCData.readCode(dis, 2) - windowRect.x;
		windowRect.height = HCData.readCode(dis, 2) - windowRect.y;
		screenRect = new Rectangle();
		screenRect.x = HCData.readCode(dis, 2);
		screenRect.y = HCData.readCode(dis, 2);
		screenRect.width = HCData.readCode(dis, 2) - screenRect.x;
		screenRect.height = HCData.readCode(dis, 2) - screenRect.y;
		scroll = new Point();
		scroll.x = HCData.readCode(dis, 2);
		scroll.y = HCData.readCode(dis, 2);
		/*String tygers5Str =*/ HCData.readStr(dis, 292);
		//System.out.println("tygers5Str:"+tygers5Str);
		fontTableID = HCData.readCode(dis, 4);
		//System.out.println("fontTableId:"+fontTableID);
		styleTableID = HCData.readCode(dis, 4);
		//System.out.println("styleTableId:"+styleTableID);
		height = HCData.readCode(dis, 2);
		//System.out.println("height:"+height);
		width = HCData.readCode(dis, 2);
		//System.out.println("width:"+width);
		/*String tygers6Str =*/ HCData.readStr(dis, 260);
		//System.out.println("tygers6Str:"+tygers6Str);
		Pattern = HCData.readPatterns(dis, new File(this.path).getParent());
		//System.out.println("Patterns ok");
		/*String tygers7Str =*/ HCData.readStr(dis, 512);
		//System.out.println("tygers7Str:"+tygers7Str);
		int remainLength1 = blockSize - ((1538));
		resultStr result = HCData.readTextToZero(dis, remainLength1);
		if(result.length_in_src<=1){
			result = HCData.readTextToZero(dis, remainLength1);
		}
		String scriptStr = result.str;
		////System.out.println("scriptStr:"+scriptStr);
		int remainLength = blockSize - ((1538)+(result.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
		}
		/*if((result.length_in_src+1)%2 != 0){
			String padding = HCData.readStr(dis, 1);
			//System.out.println("padding:"+padding);
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
	
	
	private static String getVers(int ver){
		String str;
		str = ""+(0xFF&(ver>>24));
		if((0xFF&(ver>>16))/16>0){
			str += "."+((0xFF&(ver>>16))/16+"."+(0xFF&(ver>>16))%16);
		}else{
			str += "."+((0xFF&(ver>>16))%16);
		}
		if((0xFF&(ver>>8)) == 0x20) str += "d" ;
		if((0xFF&(ver>>8)) == 0x40) str += "a";
		if((0xFF&(ver>>8)) == 0x60) str += "b";
		if((0xFF&(ver>>0))/16>0){
			str += "."+((0xFF&(ver>>0))/16+""+(0xFF&(ver>>0))%16);
		}else if(((0xFF&(ver>>0))%16)>0){
			str += "."+((0xFF&(ver>>0))%16);
		}
		return str;
	}
	

	public boolean readStyleBlock(DataInputStream dis, int blockSize){
		//System.out.println("readStyleBlock");

		if(blockSize>200000 || blockSize<24){
			return false;
		}
		
		int offset = 24;
		
		//ブロックのデータを順次読み込み
		/*int blockId =*/ HCData.readCode(dis, 4);
		//System.out.println("blockId:"+blockId);
		/*int filler =*/ HCData.readCode(dis, 4);
		//System.out.println("filler:"+filler);
		int styleCount = HCData.readCode(dis, 4);
		//System.out.println("styleCount:"+styleCount);
		nextStyleID = HCData.readCode(dis, 4);
		//System.out.println("nextStyleID:"+nextStyleID);
		
		for(int i=0; i<styleCount; i++){
			int styleId = HCData.readCode(dis, 4);
			//System.out.println("styleId:"+styleId);
			/*int something1 =*/ HCData.readCode(dis, 4);
			//System.out.println("something1:"+something1);
			/*int something2 =*/ HCData.readCode(dis, 4);
			//System.out.println("something2:"+something2);
			int textFontId = HCData.readCode(dis, 2);
			//System.out.println("textFontId:"+textFontId);
			int textStyle = HCData.readCode(dis, 1);
			//System.out.println("textStyle:"+textStyle);
			int textStyleChanged = HCData.readCode(dis, 1);
			//System.out.println("textStyleChanged:"+textStyleChanged);
			int textSize = HCData.readCode(dis, 2);
			//System.out.println("textSize:"+textSize);
			/*String filler2 =*/ HCData.readStr(dis, 6);
			//System.out.println("filler2:"+filler2);
			offset += 24;
			
			String idStr = Integer.toString(styleId);
			String fontStr = Integer.toString(textFontId);
			String styleStr = "";
			if(textStyleChanged==255){ //style unchange
				styleStr = "";
			}
			else{
				if(textStyle==0){
					styleStr = "plain";
				}
				else{
					if((textStyle&1)>0) styleStr += "bold ";
					if((textStyle&2)>0) styleStr += "italic ";
					if((textStyle&4)>0) styleStr += "underline ";
					if((textStyle&8)>0) styleStr += "outline ";
					if((textStyle&16)>0) styleStr += "shadow ";
					if((textStyle&32)>0) styleStr += "condensed ";
					if((textStyle&64)>0) styleStr += "extend ";
					if((textStyle&128)>0) styleStr += "group ";
				}
			}
			String sizeStr = Integer.toString(textSize);
    		styleList.add(new styleClass(idStr, styleStr, fontStr, sizeStr));
		}
		int remainLength = blockSize - offset;
		/*String padding =*/ HCData.readStr(dis, remainLength);
		//System.out.println("padding:"+padding);
		
		return true;
	}
	

	public boolean readFontBlock(DataInputStream dis, int blockSize){
		//System.out.println("readFontBlock");

		if(blockSize>200000 || blockSize<24){
			return false;
		}
		//ブロックのデータを順次読み込み
		/*int blockId =*/ HCData.readCode(dis, 4);
		//System.out.println("blockId:"+blockId);
		/*String tygersStr =*/ HCData.readStr(dis, 6);
		//System.out.println("tygersStr:"+tygersStr);
		int numOfFonts = HCData.readCode(dis, 2);
		//System.out.println("numOfFonts:"+numOfFonts);
		/*String tygers2Str =*/ HCData.readStr(dis, 4);
		//System.out.println("tygers2Str:"+tygers2Str);
		int offset = 24;
		
		for(int i=0; i<numOfFonts; i++){
			int fontId = HCData.readCode(dis, 2);
			offset+=2;
			//System.out.println("fontId:"+fontId);
			int nameLen;// = HCData.readCode(dis, 1);
			//offset+=1;
			//System.out.println("nameLen:"+nameLen);
			//if(offset+nameLen>blockSize){
				//break;
				nameLen = blockSize - offset;
				//if(nameLen<0)break;
			//}
			resultStr nameResult = HCData.readTextToZero(dis, nameLen);
			//System.out.println("nameResult.str:"+nameResult.str);
			offset+=nameResult.length_in_src;
			if((nameResult.length_in_src+1)%2==0){
				HCData.readCode(dis, 1);
				offset+=1;
			}
			
			//フォントIDと名前を登録
			fontList.add(new fontClass(fontId, nameResult.str));
		}
		
		int remainLength = blockSize - offset;
		/*String padding =*/ HCData.readStr(dis, remainLength);
		//System.out.println("padding:"+padding);
		
		return true;
	}
	

	static int pageIdList[];
	static int pageEntryCountList[];
	static int pageEntrySize;
	
	public boolean readListBlock(DataInputStream dis, int blockSize){
		//System.out.println("readListBlock");

		if(blockSize>200000 || blockSize<12){
			return false;
		}
		
		//ブロックのデータを順次読み込み
		listId = HCData.readCode(dis, 4);
		//System.out.println("listId:"+listId);
		/*int filler =*/ HCData.readCode(dis, 4);
		//System.out.println("filler:"+filler);
		int pageCount = HCData.readCode(dis, 4);
		//System.out.println("pageCount:"+pageCount);
		/*int pageSize =*/ HCData.readCode(dis, 4);
		//System.out.println("pageSize:"+pageSize);
		/*int pageEntryTotal =*/ HCData.readCode(dis, 4);
		//System.out.println("pageEntryTotal:"+pageEntryTotal);
		pageEntrySize = HCData.readCode(dis, 2);
		//System.out.println("pageEntrySize:"+pageEntrySize);
		/*int filler2 =*/ HCData.readCode(dis, 10);
		//System.out.println("filler2:"+filler2);
		/*int pageEntryTotal2 =*/ HCData.readCode(dis, 4);
		//System.out.println("pageEntryTotal2:"+pageEntryTotal2);
		/*int filler3 =*/ HCData.readCode(dis, 4);
		//System.out.println("filler3:"+filler3);
		
		pageIdList = new int[pageCount];
		pageEntryCountList = new int[pageCount];
		for(int i=0; i<pageCount; i++){
			pageIdList[i] = HCData.readCode(dis, 4);
			//System.out.println("pageId:"+pageIdList[i]);
			pageEntryCountList[i] = HCData.readCode(dis, 2);
			//System.out.println("pageEntryCount:"+pageEntryCountList[i]);
		}
		
		int remainLength = blockSize-(48+pageCount*6);
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
		}
		
		return true;
	}
	

	public boolean readPageBlock(DataInputStream dis, int blockSize){
		//System.out.println("readPageBlock");

		if(blockSize>200000 || blockSize<12){
			return false;
		}
		
		if(pageIdList==null || pageEntryCountList==null){
			return false;
		}
		
		int offset = 24;
		
		//ブロックのデータを順次読み込み
		int pageId = HCData.readCode(dis, 4);
		//System.out.println("pageId:"+pageId);
		/*int filler =*/ HCData.readCode(dis, 4);
		//System.out.println("filler:"+filler);
		/*int listId =*/ HCData.readCode(dis, 4);
		//System.out.println("listId:"+listId);
		/*int filler2 =*/ HCData.readCode(dis, 4);
		//System.out.println("filler2:"+filler2);
		
		int pageEntryCount = 0;
		for(int i=0; i<pageIdList.length; i++){
			if(pageIdList[i] == pageId){
				pageEntryCount = pageEntryCountList[i];
				break;
			}
		}
		
		for(int i=0; i<pageEntryCount; i++){
			int cardId = HCData.readCode(dis, 4);
			offset+=4;
			//System.out.println("cardId:"+cardId);
			if(GetCardbyId(cardId)==null){
				cardIdList.add(cardId); //cardのidリストに追加
			}
			if(pageEntrySize>4){
				/*String something =*/ HCData.readStr(dis, pageEntrySize-4);
				offset+=pageEntrySize-4;
				//System.out.println("something:"+something);
			}
		}
		
		int remainLength = blockSize-offset;
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
		}
		
		return true;
	}

	

	public boolean readNullBlock(DataInputStream dis, int blockSize){
		//System.out.println("readNullBlock");

		if(blockSize>200000 || blockSize<12){
			return false;
		}
		//ブロックのデータを順次読み込み
		/*int blockId =*/ HCData.readCode(dis, 4);
		//System.out.println("blockId:"+blockId);
		int remainLength = blockSize - 12;
		/*String padding =*/ HCData.readStr(dis, remainLength);
		//System.out.println("padding:"+padding);
		
		return true;
	}
	
	
	
	//-------------------
	// Utility function
	//-------------------
	public OCard GetCardbyId(int cardId) {
		for(int i=0; i<cardIdList.size(); i++){
			if(cardIdList.get(i)==cardId)
			{
				try {
					return OCard.getOCard(this, cardId, true);
				} catch (xTalkException e) {
				}
			}
		}
		return null;
	}
	
	public OCard GetCardbyNum(int number) {
		if(number-1>=0 && number-1<cardIdList.size())
		{
			try {
				return OCard.getOCard(this, cardIdList.get(number-1), true);
			} catch (xTalkException e) {
			}
		}
		return null;
	}
	
	public OCard GetCard(String name) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null&&0==cd.name.compareToIgnoreCase(name)){
				return cd;
			}
		}
		return null;
	}

	public OBackground GetBackgroundbyId(int bgId) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = GetCardbyId(cardIdList.get(i));
			if(cd!=null && cd.bgid==bgId)
			{
				try {
					return OBackground.getOBackground(this, cd, bgId, true);
				} catch (xTalkException e) {
				}
			}
		}
		return null;
	}
	
	public OBackground GetBackgroundbyNum(int number) {
		ArrayList<Integer> list = new ArrayList<Integer>();
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = GetCardbyId(cardIdList.get(i));
			int j;
			for(j=0; j<list.size(); j++){
				if(cd.bgid == list.get(j)){
					break;
				}
			}
			if(j>=list.size()) continue;
			list.add(cd.bgid);
			if(number == list.size()){
				try {
					return OBackground.getOBackground(this, cd, cd.bgid, true);
				} catch (xTalkException e) {
				}
			}
		}
		return null;
	}
	
	public OBackground GetBackground(String name) {
		ArrayList<Integer> list = new ArrayList<Integer>();
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = GetCardbyId(cardIdList.get(i));
			int j;
			for(j=0; j<list.size(); j++){
				if(cd.bgid == list.get(j)){
					break;//既に探したbgのid
				}
			}
			if(j<list.size()) continue;
			list.add(cd.bgid);
			OBackground bg=null;
			try {
				bg = OBackground.getOBackground(this, cd, cd.bgid, true);
			} catch (xTalkException e) {
			}
			if(bg!=null&&0==bg.name.compareToIgnoreCase(name)){
				return bg;
			}
		}
		return null;
	}
	
	public OCard GetCardofBg(OCardBase cdbase, String name) {
		if(!cdbase.objectType.equals("background")) return null;
		
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null&&0==cd.name.compareToIgnoreCase(name)&&cd.bgid==cdbase.id){
				return cd;
			}
		}
		return null;
	}
	
	public OCard GetCardofBgbyNum(OCardBase cdbase, int number) {
		if(!cdbase.objectType.equals("background")) return null;
		
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd=null;
			try {
				cd = OCard.getOCard(this, cardIdList.get(i), true);
			} catch (xTalkException e) {
			}
			if(cd!=null&&cd.bgid==cdbase.id){
				number--;
				if(number==0){
					return cd;
				}
			}
		}
		return null;
	}

	public OCard GetCardofBackgroundbyId(int bgId) {
		for(int i=0; i<cardIdList.size(); i++){
			OCard cd = GetCardbyId(cardIdList.get(i));
			if(cd.bgid==bgId)
			{
				try {
					cd.bg = OBackground.getOBackground(this, cd, cd.bgid, true);
				} catch (xTalkException e) {
					System.out.println("Error at GetCardofBackgroundbyId("+bgId+")");
				}
				return cd;
			}
		}
		return null;
	}
	
	public int GetNumberof(OCard cd) {
		for(int number=0; number < cardIdList.size(); number++)
		{
			if(cardIdList.get(number)==cd.id){
				return number+1;
			}
		}
		return 0;
	}
	
	public int GetNumberof(OBackground bg) {
		for(int number=0; number < bgCacheList.size(); number++)
		{
			if(bgCacheList.get(number).id==bg.id){
				return number+1;
			}
		}
		return 0;
	}
	
	public int GetMaxCardId() {
		int max = 0;
		for(int i=0; i<cardIdList.size(); i++){
			if(cardIdList.get(i)>max)
			{
				max = cardIdList.get(i);
			}
		}
		for(int i=0; i<bgCacheList.size(); i++){
			if(bgCacheList.get(i).id>max)
			{
				max = bgCacheList.get(i).id;
			}
		}
		return max;
	}
	
	public int GetCardListSize() {
		return cardIdList.size();
	}
	
	public int GetBgListSize() {
		return bgCacheList.size();
	}

	public void AddNewCard(int idx, int id) {
		for(int i=0 ;i<cardIdList.size(); i++){
			if(cardIdList.get(i) == id){
				System.out.println("!");
			}
		}
		cardIdList.add(idx, id);
	}
	
	public void AddNewCard(int id) {

		for(int i=0 ;i<cardIdList.size(); i++){
			if(cardIdList.get(i) == id){
				System.out.println("!");
			}
		}
		cardIdList.add(id);
	}
	
	public void AddNewBg(OBackground bg) {

		for(int i=0 ;i<bgCacheList.size(); i++){
			if(bgCacheList.get(i).id == bg.id){
				System.out.println("!");
			}
		}
		bgCacheList.add(bg);
	}
	
	public void mouseWithinCheck(){
		if(PCARD.pc.stack.curCard==null) return;
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
		int x = pointerInfo.getLocation().x-PCARD.pc.mainPane.getX()-PCARD.pc.getLocationOnScreen().x;
		int y = pointerInfo.getLocation().y-PCARD.pc.mainPane.getY()-PCARD.pc.getLocationOnScreen().y;
		if((x<0)||(y<0)||
				(x>PCARD.pc.mainPane.getWidth())||
				(y>PCARD.pc.mainPane.getHeight())){
			return;
		}
		if(this.curCard!=null){
			for(int i=this.curCard.partsList.size()-1;i>=0;i--){
				OObject obj = this.curCard.partsList.get(i);
				if(x>=obj.left&&x<=obj.left+obj.width&&
					y>=obj.top&&y<=obj.top+obj.height)
				{
					if(obj.getVisible()&&obj.enabled){
						TTalk.CallMessage("mouseWithin","",obj,true,false);
						break;
					}
				}
			}
			if(this.curCard.bg!=null){
				for(int i=this.curCard.bg.partsList.size()-1;i>=0;i--){
					OObject obj = this.curCard.bg.partsList.get(i);
					if(x>=obj.left&&x<=obj.left+obj.width&&
						y>=obj.top&&y<=obj.top+obj.height)
					{
						if(obj.getVisible()&&obj.enabled){
							TTalk.CallMessage("mouseWithin","",obj,true,false);
							break;
						}
					}
				}
			}
		}
	}
	
	/*public void PaintTo(Graphics g, JFrame frame) {
		if(curCard != null) {
			curCard.PaintTo(g,frame);
		}
	}*/
    
	
	
//初期のオリジナルデータファイル形式について：
	
/* テキストファイルにスタックののプロパティ情報を入れる
 * #charset:Shift_JIS
 * #name:xxx(基本的にファイル名と同じ)
 * #left:0
 * #top:0
 * #width:512
 * #height:342
 * #script:on idle
 *  --
 * end idle
 * #cd:12345
 * #cd:67890
 * 
 * #は特殊コード。名前などで#を使いたいときは##にする
 */
	
/*
 * ファイル構成は..idをxxxxとして
 * 
 * スタック  名前.sta
 * BG情報   resource/bgxxxx 
 * CD情報   resource/cdxxxx <-ボタンとフィールドの情報も突っ込むので大変なことになる
 * ICON    resource/iconxxxx.png
 * ICONm   resource/iconxxxx_mask.png ->変換されて消える 
 * PICT    resource/pictxxxx.png
 * PICTm   resource/pictxxxx_mask.png ->変換されて消える 
 * Snd     resource/sndxxxx.wav
 * CD pic  resource/cdpxxxx.png  
 * CD mask resource/cdpxxxx_mask.png ->変換されて消える
 * BG pic  resource/bgpxxxx.png  
 */
}
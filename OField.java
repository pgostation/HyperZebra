import java.awt.Font;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.SystemColor;
import java.awt.dnd.DropTarget;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.border.SoftBevelBorder;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OField extends OObject {
	OCardBase card = null;
	OBackground bkgd = null;
	MyTextArea fld = null;
	MyScrollPane scrollPane = null;
	BufferedImage fontPict2;
	
	//プロパティ
	Boolean autoTab=false;
	Boolean autoSelect=false;
	Boolean dontSearch=false;
	Boolean dontWrap=false;
	Color color=Color.BLACK;
	Color bgColor=Color.WHITE;
	//Color selectColor=Color.ORANGE;
	//Color textColor=Color.BLACK;
	Boolean fixedLineHeight=false;
	// lockTextはenabledで代用
	//number (カードの情報から求める)
	int scroll=0;
	Boolean sharedText=false;
	Boolean showLines=false;
	int style=0;//(0標準) 1透明 2不透明 3長方形 4シャドウ 5スクロール
	int textAlign=0;//0左 1中 2右
	String textFont="";
	int textHeight=16;
	int textSize=12;
	int textStyle=0;//0 plain
	boolean wideMargins=false;
	int selectedLine=0;
	int selectedStart=0;
	int selectedEnd=0;
	boolean multipleLines;
	boolean hilite=false;//
	
	ArrayList<styleClass> styleList;
	
	//追加プロパティ
	@SuppressWarnings("unused")
	private String reservedFamily;
	@SuppressWarnings("unused")
	private int reservedTitleWidth;
	@SuppressWarnings("unused")
	private int reservedIcon;
	@SuppressWarnings("unused")
	private String reserved35;
	DropTarget drop;
	GUI_fldDocument docListener;
	
	boolean useMyDraw = false;
	
	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}
	public int getSelectedLine() {return selectedLine;}
	public String getSelectedText() {
		if(selectedLine>0){
			String[] strary = getText().split("\n");
			if(selectedLine-1 < strary.length){
				String str = strary[selectedLine-1];
				if(str.charAt(str.length()-1)=='\r')
					str = str.substring(0, str.length()-1);
				return str;
			}
		}
		String str=getText().substring(selectedStart, selectedEnd);
		if(str!=null)
			return str;

		return "";
	}

	JComponent getComponent(){
		if(scrollPane!=null) return scrollPane;
		if(fld!=null) return fld;
		return null;
	}

	MyTextArea getMyTextArea(){
		if(fld!=null) return fld;
		return null;
	}
	
	//set
	public void setName(String in) {
		name=in;
		((OCardBase)parent).changed = true;
	}
	public void setTopLeft(int h, int v) {
		int oldLeft=left;
		int oldTop=top;
		left=h;
		top=v;
		if(fld!=null){
			fld.setBounds(left, top, width, height);

			if(!PCARD.lockedScreen) {
				if(Math.abs(left-oldLeft)<width && Math.abs(top-oldTop)<height )
					PCARD.pc.mainPane.paintImmediately(Math.min(left,oldLeft), Math.min(top,oldTop), width+Math.abs(left-oldLeft), height+Math.abs(top-oldTop));
				else {
					PCARD.pc.mainPane.paintImmediately(left, top, width, height);
					PCARD.pc.mainPane.paintImmediately(left, oldTop, width, height);
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
		if(fld!=null){
			fld.setBounds(left, top, width, height);

			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
				PCARD.pc.mainPane.paintImmediately(oldLeft, oldTop, oldWidth, oldHeight);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setTextByInputarea(String in) {
		setText2(in);
		((OCardBase)parent).changed = true;
	}
	public void setText(String in) {
		setText2(in);
		if(fld!=null) {
			int scroll = 0;
			if(scrollPane!=null) scroll = scrollPane.getVerticalScrollBar().getValue();
			fld.setText(getText());
			if(scrollPane!=null) scrollPane.getVerticalScrollBar().setValue(scroll);
		}
		if(fld!=null){
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setText2(String in) {
		if(card.objectType.equals("background") && !sharedText && ((OBackground)card).viewCard!=null){
			//共有でないbg fldのテキストは別の場所に保存
			for(int j=0; j<((OBackground)card).viewCard.bgfldList.size(); j++){
				if(((OBackground)card).viewCard.bgfldList.get(j).id == id){
					((OBackground)card).viewCard.bgfldList.get(j).text = in;
					return;
				}
			}
			//新しいデータを作成
			{
				OBgFieldData bgflddata = new OBgFieldData(((OBackground)card).viewCard, id);
				bgflddata.fld = this;
				bgflddata.text = in;
				((OBackground)card).viewCard.bgfldList.add(bgflddata);
			}
		}
		else{
			super.setText(in);
		}
	}
	public void setVisible(boolean in) {
		super.setVisible(in);
		if(fld!=null)
		{
			fld.setVisible(in);
			if(scrollPane!=null) scrollPane.setVisible(in);
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
	}
	public void setEnabled(boolean in) {
		enabled=in;
		if(fld!=null)
		{
			fld.setEnabled(in);
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setSelectedLine(int in) {
		selectedLine=in;
		selectedStart=0;
		selectedEnd=0;
		if(fld!=null)
		{
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setScroll(int in) {
		if(scrollPane!=null) {
			scrollPane.getVerticalScrollBar().setValue(scroll);
			scroll=scrollPane.getVerticalScrollBar().getValue();
			if(fld!=null)
			{
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
	}
	public void setTextSize(int in) {
		if(in<1) return;
		textSize = in;
		Font font = new Font(textFont, textStyle, textSize);
		fld.setFont(font);
		if(fld!=null)
		{
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setTextHeight(int in) {
		textHeight = in;
		//fld.getRowHeight();
		((OCardBase)parent).changed = true;
	}
	public void setHilite(boolean in) {
		hilite=in;
		if(fld!=null)
		{
			if(hilite){
				fld.setSelectionStart(0);
				fld.setSelectionEnd(fld.getText().length()-1);
			}
			else{
				fld.setSelectionStart(0);
				fld.setSelectionEnd(0);
			}
			if(!PCARD.lockedScreen) {
				PCARD.pc.mainPane.paintImmediately(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setTextFont(String in) {
		textFont = in;
		if(fld != null){
			fld.setFont(new Font(textFont, textStyle & 0x03, textSize));
		}
		if(!PCARD.lockedScreen) {
			PCARD.pc.mainPane.paintImmediately(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}

	public void setColor(Color col) {
		color=col;
		if(fld!=null)
		{
			fld.setForeground(col);
			fld.repaint();
		}
	}
	public void setBgColor(Color col) {
		bgColor=col;
		if(fld!=null)
		{
			fld.setBackground(col);
			fld.repaint();
		}
	}
	

	public OField(OCardBase cd, int fldId) {
    	objectType="field";
		card = cd;
		parent = cd;
		scriptList = new ArrayList<String>();

		width=64; height=64;
		id=fldId;
	}
	

	@SuppressWarnings("unchecked")
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
		boolean firstSelectedInt = true;
		this.textStyle=0;
		
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("id")){ this.id = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("visible")){ this.setVisible(XMLRead.bool(reader));reader.next(); }
	            	else if(elm.equals("dontWrap")){ this.dontWrap = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("dontSearch")){ this.dontSearch = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("sharedText")){ this.sharedText = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("fixedLineHeight")){ this.fixedLineHeight = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("autoTab")){ this.autoTab = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("lockText")){ this.enabled = XMLRead.bool(reader);reader.next(); }
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
						if(0==tmpstr.compareTo("scrolling")) this.style=5;
	            	}
	            	else if(elm.equals("autoSelect")){ this.autoSelect = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("showLines")){ this.showLines = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("wideMargins")){ this.wideMargins = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("multipleLines")){ this.multipleLines = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("reservedFamily")){ this.reservedFamily = reader.getElementText(); }
	            	else if(elm.equals("titleWidth")){ this.reservedTitleWidth = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("icon")){ this.reservedIcon = Integer.valueOf(reader.getElementText()); }
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
	            	else if(elm.equals("textHeight")){ this.textHeight = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textStyle")){ 
	            		String tmpstr=reader.getElementText();
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
	            	else if(elm.equals("selectedLines")){ 
	            		//this.selectedLine = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("integer")){ 
	            		if(firstSelectedInt){
	            			this.selectedStart = Integer.valueOf(reader.getElementText());
		            		this.selectedEnd = this.selectedStart;
	            			firstSelectedInt = false;
	            		}
	            		else{
	            			this.selectedEnd = Integer.valueOf(reader.getElementText());
	            		}
	            	}
	            	else if(elm.equals("reserved35")){ reserved35 = reader.getElementText(); }
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
	            	else if(elm.equals("autoHighlight")){ XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("lastSelectedLine")){  }
	            	else if(elm.equals("selectedLine")){ 
	            		this.selectedLine = Integer.valueOf(reader.getElementText()); }
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
        writer.writeCharacters("field");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("visible");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(getVisible())+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("dontWrap");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(dontWrap)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("dontSearch");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(dontSearch)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("sharedText");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(sharedText)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("fixedLineHeight");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(fixedLineHeight)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("autoTab");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(autoTab)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("lockText");
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
        case 5: writer.writeCharacters("scrolling");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("autoSelect");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(autoSelect)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("showLines");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(showLines)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("wideMargins");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(wideMargins)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("multipleLines");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(multipleLines)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

       /* writer.writeStartElement("reservedFamily");
        writer.writeCharacters(" 0 ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");*/

    	if(selectedLine>0){
	        writer.writeStartElement("selectedLines");
	        writer.writeCharacters("\n\t\t\t");
	        {
		        writer.writeCharacters("\t");
		        writer.writeStartElement("integer");
		        writer.writeCharacters(Integer.toString(selectedLine));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
	        }
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

        writer.writeStartElement("textHeight");
        writer.writeCharacters(Integer.toString(textHeight));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

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
	public boolean readFieldBlock(DataInputStream dis, int partSize){
		//System.out.println("====readFieldBlock====");
		//ブロックのデータを順次読み込み
		int flags = HCData.readCode(dis, 1);
		//System.out.println("flags:"+flags);
		setVisible(((flags>>7)&0x01)==0);
		dontWrap = ((flags>>5)&0x01)!=0;
		dontSearch = ((flags>>4)&0x01)!=0;
		sharedText = ((flags>>3)&0x01)!=0;
		fixedLineHeight = ! (((flags>>2)&0x01)!=0);
		autoTab = ((flags>>1)&0x01)!=0;
		enabled = (((flags>>0)&0x01)!=0);
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
		autoSelect = ((flags2>>7)&0x01)!=0;
		showLines = ((flags2>>6)&0x01)!=0;
		wideMargins = ((flags2>>5)&0x01)!=0;
		multipleLines = ((flags2>>4)&0x01)!=0;
		//group = (flags2)&0x0F;
		int style = HCData.readCode(dis, 1);
		//0標準 1透明 2不透明 3長方形 4シャドウ 5丸みのある長方形 6省略時設定 7楕円 8ポップアップ 9チェックボックス 10ラジオ
		switch(style){
		case 0: this.style = 1; break;//transparent
		case 1: this.style = 2; break;//opaque
		case 2: this.style = 3; break;//rectangle
		//case 3: this.style = 5; break;//roundRect
		case 4: this.style = 4; break;//shadow
		//case 5: this.style = 9; break;//checkBox
		//case 6: this.style = 10; break;//radioButton
		case 7: this.style = 5; break;//scrolling
		//case 8: this.style = 0; break;//standard
		//case 9: this.style = 6; break;//default
		//case 10: this.style = 7; break;//oval
		//case 11: this.style = 8; break;//popup
		}
		selectedEnd = HCData.readCode(dis, 2);
		//System.out.println("selectedEnd:"+selectedEnd);
		selectedStart = HCData.readCode(dis, 2);
		//System.out.println("selectedStart:"+selectedStart);
		selectedLine = selectedStart;
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
		//System.out.println("name:"+name);
		//HCStackDebug.debuginfo("name:"+name);
		/*int filler2 =*/ //HCData.readCode(dis, 1);
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
		if(remainLength<0 || remainLength > 10){
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
	
	
	static void buildOField(OField ofld)
	{
		//swingのテキストコンポーネントを追加
		ofld.fld = new MyTextArea(ofld.getText());

		ofld.fld.fldData = ofld;
		ofld.addListener();
		
		ofld.fld.setText(ofld.getText());
		ofld.fld.setForeground(ofld.color);
		ofld.fld.setBackground(ofld.bgColor);
		ofld.fld.setBounds(ofld.left, ofld.top, ofld.width, ofld.height); 
		//ofld.fld.setSelectionColor(ofld.selectColor);
		
		if(Font.decode(ofld.textFont).getFontName().equals(ofld.textFont)){
			Font font = new Font(ofld.textFont, ofld.textStyle, ofld.textSize);
			ofld.fld.setFont(font);
		}else{
			Font font = new Font(Font.MONOSPACED, ofld.textStyle, ofld.textSize);
			ofld.fld.setFont(font);
			//File file=new File(ofld.card.stack.file.getParent()+"/resource/FONT"+ofld.textFont+".jpeg");
			BufferedImage bi = null;
			/*try {
				bi = javax.imageio.ImageIO.read(file);
			}catch (IOException e){
			}*/
			if(bi == null){
				int rsrcid = ofld.card.stack.rsrc.getRsrcIdAll(ofld.textFont, "font");
				rsrcid += ofld.textSize;//ベースID+テキストサイズ=ID
				String path = ofld.card.stack.rsrc.getFilePathAll(rsrcid, "font");
				if(path!=null){
					File file = new File(path/*ofld.card.stack.file.getParent()+File.separatorChar+rsrcName*/);
					try {
						bi = javax.imageio.ImageIO.read(file);
					}catch (IOException e){
					}
					
					ofld.fontPict2 = bi;
				}
			}
			/*else{
				ofld.fld.setFont(new Font(ofld.textFont, ofld.textStyle, ofld.textSize));
			}*/
		}
		if(ofld.textHeight==0){
			ofld.textHeight = ofld.fld.getLineHeight();
		}
		ofld.fld.setVisible(ofld.getVisible());
		ofld.fld.setEditable(!ofld.enabled);
		ofld.fld.setFocusable(!ofld.enabled);
		ofld.fld.setLineWrap(!ofld.dontWrap);
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
			ofld.fld.setBorder(new SoftBevelBorder(1,Color.GRAY,Color.GRAY));
			//ofld.fld.setBorder(new MatteBorder(1,1,1,1,ofld.color));
			break;
		case 4:
			ofld.fld.setBorder(new CompoundBorder(new MatteBorder(0,0,3,3,Color.GRAY),new MatteBorder(1,1,1,1,ofld.color)));
			break;
		} 
		if(ofld.autoSelect) ofld.fld.setOpaque(false);
		if(ofld.wideMargins) ofld.fld.setMargin(new Insets(8,8,8,8));

		if(ofld.style<=4) {
			PCARD.pc.mainPane.add(ofld.fld);
		}
		else {
			MyScrollPane scrollpane = new MyScrollPane();
			scrollpane.fldData=ofld;
			scrollpane.setViewportView(ofld.fld);
			scrollpane.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			scrollpane.getVerticalScrollBar().setValue(ofld.scroll);
			scrollpane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			scrollpane.setVisible(ofld.getVisible());
			PCARD.pc.mainPane.add(scrollpane);
			ofld.card.paneList.add(scrollpane);
			ofld.scrollPane = scrollpane;
			
    		ofld.fld.revalidate();
		}
	}
    
	
    void addListener(){
		fld.addMouseListener(card.stack.GUI_fld);
		fld.addMouseMotionListener(card.stack.GUI_fld);
		docListener = new GUI_fldDocument(this);
		fld.getDocument().addDocumentListener(docListener);
	}
    
    void removeListener(){
		fld.removeMouseListener(card.stack.GUI_fld);
		fld.addMouseMotionListener(card.stack.GUI_fld);
		fld.getDocument().removeDocumentListener(docListener);
		docListener = null;
	}
    
    static fldOutlineListen fldOutlineListen = new fldOutlineListen();
    void addListener(MouseListener listener){
		fld.addMouseListener(listener);
    }
    void removeListener(MouseListener listener){
    	fld.removeMouseListener(listener);
    }
    void addMotionListener(MouseMotionListener listener){
		fld.addMouseMotionListener(listener);
    }
    void removeMotionListener(MouseMotionListener listener){
    	fld.removeMouseMotionListener(listener);
    }
}

class fldOutlineListen implements MouseListener {

    public void mouseClicked(MouseEvent e) {
    	ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, ((MyTextArea)e.getSource()).fldData);
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

class MyScrollPane extends JScrollPane {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OField fldData = null;
	
	MyScrollPane(){
		super();
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
	}

	@Override
    protected void paintComponent(Graphics g) {
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && fldData.parent.objectType.equals("card")) return;
		if(!isVisible()||
			fldData.card!=PCARD.pc.stack.curCard||
			PCARD.lockedScreen&&paneg==g)
		{
			/*for(int i=0; i<getComponentCount(); i++){
				getComponent(i).setVisible(false);
			}*/
			return;
		}
		/*for(int i=0; i<getComponentCount(); i++){
			if(getComponent(i).getClass() == JScrollPane.ScrollBar.class) continue;
			//setVisible(true)をやったときに書き換え命令が発生するのでこっそりやりたい
			Class<JScrollPane.ScrollBar> c = JScrollPane.ScrollBar.class;
			Field m;
			try {
				m = c.getDeclaredField("visible");
				m.setAccessible(true);
				m.set(getComponent(i), true); // .visible = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}*/
        super.paintComponent(g);
		if(AuthTool.tool!=null && FieldGUI.gui.target == this){
			FieldGUI.drawSelectBorder(this);
		}
    }
}


class MyTextArea extends JTextArea {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OField fldData = null;
	JScrollPane pr_scrl;
	int pr_selLine;//showLine xfcn用
	boolean smallfont;
	
	MyTextArea(String str){
		super(str);
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
	}

	public int getLineHeight(){
		  return getRowHeight();
	}
	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(PCARD.pc.bit > 1 && fldData.card!=null) return;
		if(PaintTool.editBackground && fldData.parent!=null && fldData.parent.objectType.equals("card")) return;
		if(fldData!=null && fldData.card!=null && fldData.card.objectType.equals("cd") && fldData.card!=PCARD.pc.stack.curCard) return;
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.lockedScreen&&paneg==g) return;
        //super.paintComponent(g);
    	if(fldData!=null){
    		//通常フィールド
    		if(fldData.fontPict2!=null){
    			if(fldData.style!=1){
	    			g.setColor(fldData.bgColor);
	    			Rectangle r = g.getClipBounds();
	    			g.fillRect(r.x,r.y,r.width,r.height);
    			}
    			
    			//ピクチャによる独自フォント
				int rsrcid = fldData.card.stack.rsrc.getRsrcIdAll(fldData.textFont, "font");
				rsrcid += fldData.textSize;//ベースID+テキストサイズ=ID
				Rsrc.rsrcClass fontrsrc = fldData.card.stack.rsrc.getResourceAll(rsrcid, "font");
				Rsrc.FontInfo fontinfo = (Rsrc.FontInfo) fontrsrc.optionInfo;
				
				if(fontinfo==null){
					fontinfo = fldData.card.stack.rsrc.new FontInfo();
				}
    			int h=0, v=0;
				int sz = fldData.textSize;
				char start = fontinfo.firstChar;
				char end = fontinfo.lastChar;
    			for(int i=0; i<getText().length(); i++){
    				char c = getText().charAt(i);
    				if(c=='\r'||c=='\n'){
    					v++;
    					h=0;
    					if(c=='\r'&&i+1<getText().length()&&getText().charAt(i+1)=='\n') i++;
    				}
    				else if(c>=start && c<=end){
    					//System.out.println("fontpict:h:"+h+" v:"+v+" "+fontinfo.locs[c-start]+" - "+fontinfo.locs[c-start+1]);
    					g.drawImage(fldData.fontPict2,sz*h,sz*v,sz*h+sz,sz*v+sz,fontinfo.locs[c-start],0,fontinfo.locs[c-start+1],32,PCARD.pc.stack.pcard);
    					h++;
    				}
    			}
    			return;
    		}else if(isFocusOwner()==false || fldData.useMyDraw){
    			if(fldData.style!=1){
	    			g.setColor(fldData.bgColor);
	    			Rectangle r = g.getClipBounds();
	    			g.fillRect(r.x,r.y,r.width,r.height);
    			}
    			
    			if(fldData.enabled && fldData.autoSelect && fldData.dontWrap){
        			//ライン選択
    	    		if(fldData.selectedLine>0){
    	    			g.setColor(SystemColor.textHighlight);
    	    			g.fillRect(1,(fldData.selectedLine-1)*fldData.textHeight,getWidth()-1,fldData.textHeight);
    	    		}
    			}

    			String textFont = fldData.textFont;
    			int textStyle = fldData.textStyle;
    			int textSize = fldData.textSize;
    			if(smallfont && textSize>6) textSize--;
    			Color textColor = fldData.color;
    			int nextStyle = 0;
    			int nextTextPosition = -1;
    			if(fldData.styleList!=null && fldData.styleList.size()>nextStyle){
    				nextTextPosition = fldData.styleList.get(nextStyle).textPosition;
    			}
				int textHeight = fldData.textHeight;
				if(textHeight<textSize) textHeight = textSize;
				StringBuilder readingStr = new StringBuilder();
    			int v=0;
				g.setFont(new Font(textFont, textStyle&0x03, textSize));
	        	FontMetrics fo = g.getFontMetrics();
	        	int nihongo_i = 0;
	        	int lft = 0;
	        	int top = fo.getAscent();
	        	int fldWidth = fldData.width;
	        	if(fldData.style==5) fldWidth-=16;
	        	else if(fldData.style==4) fldWidth-=4;
	        	
    			for(int i=0; i<=getText().length(); i++){
    				char c = ' ';
    				char nextc = ' ';
    				if(i<getText().length()){
    					try{
    						c = getText().charAt(i);
    	    				if(i+1<getText().length()){
    	    					nextc = getText().charAt(i+1);
    	    				}
    					}catch(StringIndexOutOfBoundsException e){}
    				}else{
    					c = '\n';
    				}

    				if(c!='\r' && c!='\n'){
    					readingStr.append(c);
    				}
    				boolean isStyleChange = false;
    				if(nextTextPosition!=-1 && nextTextPosition<=nihongo_i){
    	    			isStyleChange = true;
    				}

    				if(c<=255){
    					nihongo_i++;
    				}else{
    					nihongo_i+=2;
    				}
    				
		    		String drawStr = readingStr.toString();
		        	int w = 0;
		        	if(fldData.dontWrap==false) {
		        		w = fo.stringWidth(drawStr);
		        	}
					
		        	int add = textSize;
		        	if(nextc=='\n') add=0;
    				if(c=='\r'||c=='\n'||w>fldWidth-add+1||(isStyleChange && i!=0)){ //この行を描画する

    					if(!isStyleChange && w>fldWidth-add+1){
    						//行末処理
	    					{
		    					int len = drawStr.length();
		        				if(i+1<getText().length()){
		        					char nextchar = getText().charAt(i+1);
		        					if(nextchar=='.' || nextchar==',' || nextchar=='、' || nextchar=='。'){
		        						len--;
		        					}
		        				}
		    					while(len>0){
			    					char c2 = drawStr.charAt(len-1);
			    					if(c2>='A'&&c2<='z'||c2>='0'&&c2<='9'||c2>='!'&&c2<='+'){
			    						len--;
			    					}
			    					else break;
		    					}
		    					if(len>0){
		    						drawStr = drawStr.substring(0,len);
		    					}
	    					}
    					}
    					readingStr.delete(0,drawStr.length());

    		        	int drawWidth = fo.stringWidth(drawStr);
    		        	
    		        	//右寄せかセンタリング
    		        	if(lft==0){
	    		        	if(fldData.textAlign==0) {
	    		        		lft = 0;
	    			        	if(fldData.style>=3) lft = 1;
	    		        	}
	    		        	if(fldData.textAlign==1) lft = (fldWidth-drawWidth)/2;
	    		        	if(fldData.textAlign==2) lft = fldWidth-drawWidth-1;
    		        	}
    		        	
    		        	//outline or shadow
    		        	g.setColor(textColor);
    		        	if((textStyle&8)>0 || (textStyle&16)>0){
	    		        	if(/*(textStyle&8) > 0 && */ (textStyle&16)>0){
		    		        	g.drawString(drawStr,lft+2,top+1);
		    		        	g.drawString(drawStr,lft+1,top+2);
	    		        	}
	    		        	if((textStyle&8)>0 ){
		    		        	g.drawString(drawStr,lft-1,top+0);
		    		        	g.drawString(drawStr,lft+0,top-1);
	    		        	}
	    		        	g.drawString(drawStr,lft+1,top+0);
	    		        	g.drawString(drawStr,lft+0,top+1);
	    		        	g.setColor(fldData.bgColor);
        				}
        				
        				//文字描画
    		        	g.drawString(drawStr,lft,top);
    		        	
    		        	//underline
    		        	if((textStyle&4)>0){
    		        		g.drawLine(lft,top,lft+drawWidth,top);
        		        	if((textStyle&8)>0 || (textStyle&16)>0){
		    		        	g.setColor(textColor);
        		        		g.drawLine(lft,top+1,lft+drawWidth,top+1);
        		        	}
    		        	}
    					
    					if(!isStyleChange){
    						v++; //改行
    						lft = 0;
    						top += textHeight;
    						/*//OS9とのフォントサイズの違いを吸収
    						if(PCARD.pc.stack.createdByVersion.charAt(0)=='2' &&
    								fldData.style!=5 && !smallfont && top+textHeight>fldData.height+4){
    							smallfont = true;
    							repaint();
    						}*/
    					}
    					
    					if(c=='\r'&&i+1<getText().length()&&getText().charAt(i+1)=='\n'){
    						i++;
    					}
    				}
    				
    				if(isStyleChange){
						//次のスタイルを適用
						int styleId = fldData.styleList.get(nextStyle).styleId;
						for(int j=0; j<fldData.card.stack.styleList.size(); j++){
							OStack.styleClass styleClass = fldData.card.stack.styleList.get(j);
							if(styleClass.id==styleId){
								if(styleClass.font!=-1){
									for(int k=0; k<fldData.card.stack.fontList.size(); k++){
										OStack.fontClass fontClass = fldData.card.stack.fontList.get(k);
										if(fontClass.id==styleClass.font){
							    			textFont = fontClass.name;
							    			break;
										}
									}
								}
								if(styleClass.style!=-1){
									textStyle = styleClass.style;
								}
								if(styleClass.size!=-1){
									textSize = styleClass.size;
								}
								if(!fldData.fixedLineHeight){
									textHeight = textSize+1;
									if((textStyle&8)>0 || (textStyle&16)>0){
										textHeight+=1;
									}
								}
								//Color textColor = styleClass;
								break;
							}
						}
						
						//新しいスタイルを適用
						g.setFont(new Font(textFont, textStyle&0x03, textSize));
			        	fo = g.getFontMetrics();
			        	
						nextStyle++;
						if(fldData.styleList.size()>nextStyle){
							nextTextPosition = fldData.styleList.get(nextStyle).textPosition;
						}else{
							nextTextPosition = -1;
						}
	    			}
    			}/*else if(fldData.textAlign!=0){ //右寄せかセンタリング
    				if(fldData.style!=1){
    					g.setColor(Color.WHITE);
    					g.fillRect(1,1,fldData.width-2,fldData.height-2);
    				}
    				g.setFont(new Font(fldData.textFont, fldData.textStyle&0x03, fldData.textSize));
		        	FontMetrics fo = g.getFontMetrics();
	    			int v=0;
					int sz = fldData.textHeight;
					StringBuilder str = new StringBuilder();
	    			for(int i=0; i<getText().length(); i++){
	    				char c = getText().charAt(i);
	    				if(c=='\r'||c=='\n'||i==getText().length()){
	    					//System.out.println("fontpict:"+h+","+v+"  "+c%8+","+c/8+""+(char)(c+32));
	    		    		String str2 = str.toString();
	    		        	int w = fo.stringWidth(str2);
	    		        	int lft = 0;
	    		        	if(fldData.textAlign==0) lft = 2;
	    		        	if(fldData.textAlign==1) lft = (fldData.width-w)/2;
	    		        	if(fldData.textAlign==2) lft = fldData.width-w;
	    		        	g.setColor(Color.BLACK);
	    		        	g.drawString(str2,lft,fldData.textSize+v*sz);
	    					
	    		        	str.delete(0,str.length());
	    					v++;
	    					if(c=='\r'&&i+1<getText().length()&&getText().charAt(i+1)=='\n') i++;
	    				}
	    				else{
	    					str.append(c);
	    				}
		    		}
    			}*/
    			
    			if(AuthTool.tool!=null && FieldGUI.gui.target == this){
    				FieldGUI.drawSelectBorder(this);
    			}
    			return;
    		}
    	}else if(fldData==null){
    		//showList用
			//ライン選択
			g.setColor(Color.WHITE);
			Rectangle r = g.getClipBounds();
			g.fillRect(r.x,r.y,r.width,r.height);
    		if(pr_selLine>0){
    			//g.setColor(Color.WHITE);
    			//g.setXORMode(SystemColor.textHighlight);
    			g.setColor(SystemColor.textHighlight);
    			g.fillRect(1,(pr_selLine-1)*getRowHeight(),getWidth()-1,getRowHeight());
    			//g.setXORMode(Color.WHITE);
    		}
    	}
		g.setPaintMode();
    	super.paintComponent(g);
		if(AuthTool.tool!=null && FieldGUI.gui.target == this){
			FieldGUI.drawSelectBorder(this);
		}
    }
	
	@Override
	protected void paintBorder(Graphics g){
		if(!isVisible()) return;
		if(PCARD.pc.bit > 1) return;
		if(PaintTool.editBackground && fldData.parent!=null && fldData.parent.objectType.equals("card")) return;
		if(fldData!=null && fldData.card!=null && fldData.card.objectType.equals("cd") && fldData.card!=PCARD.pc.stack.curCard) return;
		Graphics paneg = PCARD.pc.mainPane.getGraphics();
		if(PCARD.lockedScreen&&paneg==g) return;
		super.paintBorder(g);
	}
}


class styleClass{
	int textPosition;
	int styleId;
}
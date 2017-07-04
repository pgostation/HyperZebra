import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
//import java.lang.reflect.Field;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OCard extends OCardBase {
	OBackground bg = null;
	int bgid;

	ArrayList<OBgButtonData> bgbtnList;
	ArrayList<OBgFieldData> bgfldList;
	
	//プロパティ
	Boolean cantDelete=false;
	Boolean cantModify=false;
	Boolean dontSearch=false;
	Boolean marked=false;
	//number (スタックの情報から求める)
	//rect (スタック情報)
	
	//追加プロパティ
	
	//toc.xml
	@SuppressWarnings("unused")
	private String filler1;
	
	public void clean(){
		super.clean();
		bg = null;
	}
	
	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}

	//set
	public void setName(String in) {
		name=in;
		changed = true;
	}
	
	//メイン
	public static OCard getOCard(OStack st, int cardId, boolean dataonly) throws xTalkException {
		for(int i=0; i<st.cdCacheList.size(); i++){
			OCard cd = st.cdCacheList.get(i);
			if(st==cd.stack && cardId==cd.id){
				if(dataonly) return cd;
				else {
					buidParts(cd);
					return buildOCard(cd);
				}
			}
		}
		
		return new OCard(st, cardId, dataonly);
	}
	
	private OCard(OStack st, int cardId, boolean dataonly) throws xTalkException {
		super(st);
		
		id = cardId;
    	objectType="card";
		this.btnList = new ArrayList<OButton>();
		this.fldList = new ArrayList<OField>();
		this.partsList = new ArrayList<OObject>();
		this.scriptList = new ArrayList<String>();
		
		this.bgbtnList = new ArrayList<OBgButtonData>();
		this.bgfldList = new ArrayList<OBgFieldData>();
		
		{
			throw new xTalkException("そのidのカードのデータファイルがありません");
		}
	}
	
	
	public OCard(OStack st){
		super(st);
		
    	objectType="card";
		this.btnList = new ArrayList<OButton>();
		this.fldList = new ArrayList<OField>();
		this.partsList = new ArrayList<OObject>();
		this.scriptList = new ArrayList<String>();
		
		this.bgbtnList = new ArrayList<OBgButtonData>();
		this.bgfldList = new ArrayList<OBgFieldData>();
		
		st.cdCacheList.add(this);
	}
	

	@SuppressWarnings("unchecked")
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("id")){ id = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("filler1")){ filler1 = reader.getElementText(); } //markedかな？
	            	else if(elm.equals("bitmap")){ bitmapName = reader.getElementText(); }
	            	else if(elm.equals("cantDelete")){ cantDelete = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantModify")){ cantModify = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("showPict")){ showPict = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("dontSearch")){ dontSearch = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("owner") || elm.equals("background")){
	            		bgid = Integer.valueOf(reader.getElementText());
	            	}
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
	            	}else if(elm.equals("part")){
	            		int partid=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("id")){ partid = Integer.valueOf(reader.getElementText()); }
	    		            	else if(elm2.equals("type")){ 
	    		            		String type = reader.getElementText();
	    		            		if(type.equals("button")){
	    		            			OButton btn = new OButton(this, partid);
	    		            			reader = btn.readXML(reader);
	    		            			btnList.add(btn);
	    		            			partsList.add(btn);
	    		            		}
	    		            		else if(type.equals("field")){
	    		            			OField fld = new OField(this, partid);
	    		            			reader = fld.readXML(reader);
	    		            			fldList.add(fld);
	    		            			partsList.add(fld);
	    		            		}
	    		            		else System.out.println("Part_Type: " + type);
    		    	            	break;
	    		            	}
	    		            	else
	    		            	{
	    		            		System.out.println("Local Name: " + reader.getLocalName());
	    		            		System.out.println("Element Text: " + reader.getElementText());
	    		            	}
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("part")){
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}else if(elm.equals("content")){
	            		int partid=0;
	            		String layer = "";
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("layer")){ layer = reader.getElementText(); }
	    		            	else if(elm2.equals("id")){ partid = Integer.valueOf(reader.getElementText()); }
	    		            	else if(elm2.equals("text")){ 
	    		            		if(layer.equals("card")){
	    		            			OObject part = this.GetPartbyId(partid);
	    		            			if(part!=null){ 
	    		            				part.setTextInternal(reader.getElementText());
	    		            			}else{
	    		            				System.out.println("No Part: "+layer + " part id " + partid);
	    		            			}
	    		            		}else{
	    		            			OBgFieldData bgfld = new OBgFieldData(this, partid);
	    		            			bgfld.text = reader.getElementText();
	    		            			this.bgfldList.add(bgfld);
	    		            		}
	    		            	}
	    		            	else if(elm.equals("highlight")){ 
	    		            		OBgButtonData bgbtn = new OBgButtonData(this, partid);
    		            			bgbtn.check_hilite = XMLRead.bool(reader);reader.next();
    		            			this.bgbtnList.add(bgbtn);
	    		            	}
	    		            	else if(elm2.equals("stylerun")){
	    		            		int styleid = 0;
	    		            		//int offset = 0;
	    		    	            while(reader.hasNext()){
	    		    	            	int eventType3 = reader.next();
	    		    	            	if (eventType3 == XMLStreamReader.START_ELEMENT) {
	    		    		            	String elm3 = reader.getLocalName();
	    		    		            	if(elm3.equals("offset")){ /*offset = Integer.valueOf(reader.getElementText());*/ }
	    		    		            	else if(elm3.equals("id")){ 
	    		    		            		styleid = Integer.valueOf(reader.getElementText());
	    		    		            		//textStyle
	    		    		            		if(layer.equals("card")){
	    		    		            			int style = 0;
	    		    		            			for(int i=0; i<this.stack.styleList.size();i++){
	    		    		            				if(this.stack.styleList.get(i).id == styleid){
	    		    		            					style = this.stack.styleList.get(i).style;
	    		    		            					break;
	    		    		            				}
	    		    		            			}
	    		    		            			if(style!=-1){
		    		    		            			OObject part = this.GetPartbyId(partid);
		    		    		            			if(part!=null&&part.objectType.equals("button")){
		    		    		            				OButton btn = (OButton)part;
		    		    		            				btn.textStyle = style;
		    		    		            			}
		    		    		            			else if(part!=null&&part.objectType.equals("field")){
		    		    		            				OField fld = (OField)part;
		    		    		            				fld.textStyle = style;
		    		    		            			}
	    		    		            			}
	    		    		            		}
	    		    		            	}
	    		    		            	else
	    		    		            	{
	    		    		            		System.out.println("Local Name: " + reader.getLocalName());
	    		    		            		System.out.println("Element Text: " + reader.getElementText());
	    		    		            	}
	    		    	            	}
	    		    	            	else if (eventType3 == XMLStreamReader.END_ELEMENT) {
	    		    		            	String elm3 = reader.getLocalName();
	    		    		            	if(elm3.equals("stylerun")){
	    		    		            		break;
	    		    		            	}
	    		    		            }
	    		    	            }
	    		            	}
	    		            	else
	    		            	{
	    		            		System.out.println("Local Name: " + reader.getLocalName());
	    		            		System.out.println("Element Text: " + reader.getElementText());
	    		            	}
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("content")){
	    		            		break;
	    		            	}
	    		            }
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
	            	if(elm.equals("card")){
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
        writer.writeStartElement("card");
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("id");
        writer.writeCharacters(Integer.toString(id));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        writer.writeStartElement("bitmap");
        writer.writeCharacters(bitmapName);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("cantDelete");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(cantDelete.toString()+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("showPict");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(showPict.toString()+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("dontSearch");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(dontSearch.toString()+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        writer.writeStartElement("background");
        writer.writeCharacters(Integer.toString(bgid));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
        
        for(int i=0; i<partsList.size(); i++){
            OObject part = partsList.get(i);
            if(part.getClass()==OButton.class){
            	((OButton)part).writeXML(writer);
            }
            else if(part.getClass()==OField.class){
            	((OField)part).writeXML(writer);
            }
        }

        for(int i=0; i<partsList.size(); i++){
            OObject part = partsList.get(i);
        	if(part.getText().length()>0){
                writer.writeStartElement("content");
                writer.writeCharacters("\n\t\t\t");
                {
                    writer.writeStartElement("layer");
                    writer.writeCharacters("card");
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t\t");
                    
                    writer.writeStartElement("id");
                    writer.writeCharacters(Integer.toString(part.id));
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t\t");
                    
                    writer.writeStartElement("text");
                    writer.writeCharacters(part.getText());
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t");
                    
                    if(part.objectType.equals("field") && 
                    	((OField)part).styleList!=null)
                    {
                    	for(int j=0; j<((OField)part).styleList.size(); j++){
	                        writer.writeStartElement("stylerun");
	                        writer.writeCharacters("\n\t\t\t");
	                        {
		                        writer.writeStartElement("offset");
		                        writer.writeCharacters(Integer.toString(((OField)part).styleList.get(j).textPosition));
		                        writer.writeEndElement();
		                        writer.writeCharacters("\n\t\t\t");
	                        }
	                        {
		                        writer.writeStartElement("id");
		                        writer.writeCharacters(Integer.toString(((OField)part).styleList.get(j).styleId));
		                        writer.writeEndElement();
		                        writer.writeCharacters("\n\t\t");
	                        }
	                        writer.writeEndElement();
	                        writer.writeCharacters("\n\t\t");
                    	}
                    }
                }
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t");
        	}
        }

        for(int i=0; i<bgfldList.size(); i++){
            OBgFieldData part = bgfldList.get(i);
        	if(part.text.length()>0){
                writer.writeStartElement("content");
                writer.writeCharacters("\n\t\t\t");
                {
                    writer.writeStartElement("layer");
                    writer.writeCharacters("background");
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t\t");
                    
                    writer.writeStartElement("id");
                    writer.writeCharacters(Integer.toString(part.id));
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t\t");
                    
                    writer.writeStartElement("text");
                    writer.writeCharacters(part.text);
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t");
                    
                    if(part.styleList!=null)
                        {
                        	for(int j=0; j<part.styleList.size(); j++){
    	                        writer.writeStartElement("stylerun");
    	                        writer.writeCharacters("\n\t\t\t");
    	                        {
    		                        writer.writeStartElement("offset");
    		                        writer.writeCharacters(Integer.toString(part.styleList.get(j).textPosition));
    		                        writer.writeEndElement();
    		                        writer.writeCharacters("\n\t\t\t");
    	                        }
    	                        {
    		                        writer.writeStartElement("id");
    		                        writer.writeCharacters(Integer.toString(part.styleList.get(j).styleId));
    		                        writer.writeEndElement();
    		                        writer.writeCharacters("\n\t\t");
    	                        }
    	                        writer.writeEndElement();
    	                        writer.writeCharacters("\n\t\t");
                        	}
                        }
                }
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t");
        	}
        }

        for(int i=0; i<bgbtnList.size(); i++){
            OBgButtonData part = bgbtnList.get(i);
        	if(GetBgBtnbyId(part.id).sharedHilite==false){
                writer.writeStartElement("content");
                writer.writeCharacters("\n\t\t\t");
                {
                    writer.writeStartElement("layer");
                    writer.writeCharacters("background");
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t\t");
                    
                    writer.writeStartElement("id");
                    writer.writeCharacters(Integer.toString(part.id));
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t\t");
                    
                    writer.writeStartElement("highlight");
                    writer.writeCharacters(" ");
                    writer.writeEmptyElement(Boolean.toString(part.check_hilite)+" ");
                    writer.writeCharacters(" ");
                    writer.writeEndElement();
                    writer.writeCharacters("\n\t\t");
                }
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t");
        	}
        }
        
        writer.writeStartElement("name");
        writer.writeCharacters(name);
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


	//HCのスタックを変換
	@SuppressWarnings("unchecked")
	public boolean readCardBlock(DataInputStream dis, int blockSize){
		//System.out.println("====readCardBlock====");

		if(blockSize>2000000 || blockSize<50){
			return false;
		}
		
		int offset = 54;
		
		//ブロックのデータを順次読み込み
		id = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("id:"+Integer.toString(id));
		//System.out.println("id:"+id);
		if(id<0 || id >= 2265535){
			//System.out.println("!");
		}
		if(id==12332){
			//debug
			System.out.println("12332");
		}
		/*String tygersStr =*/ HCData.readStr(dis, 4);
		//HCStackDebug.debuginfo("tygersStr:"+tygersStr);
		//System.out.println("tygersStr:"+tygersStr);
		int bitmapId = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("bitmapId:"+Integer.toString(bitmapId));
		//System.out.println("bitmapId:"+bitmapId);
		if(bitmapId>0){
			bitmapName = "BMAP_"+bitmapId+".png";
		}
		int flags = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("flags:0x"+Integer.toHexString(flags));
		//System.out.println("flags:"+flags);
		dontSearch = ((flags>>11)&0x01)!=0;
		showPict = !( ((flags>>13)&0x01)!=0);
		cantDelete = ((flags>>14)&0x01)!=0;
		/*String tygers2Str =*/ HCData.readStr(dis, 10);
		//System.out.println("tygers2Str:"+tygers2Str);
		/*int pageId =*/ HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("pageId:"+Integer.toString(pageId));
		//System.out.println("pageId:"+pageId);
		bgid = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("bgid:"+Integer.toString(bgid));
		//System.out.println("bgid:"+bgid);
		int numofParts = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("numofParts:"+Integer.toString(numofParts));
		//System.out.println("numofParts:"+numofParts);
		/*String tygers3Str =*/ HCData.readStr(dis, 6);
		//System.out.println("tygers3Str:"+tygers3Str);
		int numofContents = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("numofContents:"+Integer.toString(numofContents));
		//System.out.println("numofContents:"+numofContents);
		/*int scriptType =*/ HCData.readCode(dis, 4);
		//System.out.println("scriptType:"+scriptType);
		
		for(int i=0; i<numofParts; i++){
			//System.out.println("==part "+i+"==");
			//HCStackDebug.debuginfo("==part "+i+"==");
			int dataLen = HCData.readCode(dis, 2);
			//System.out.println("dataLen:"+dataLen);
			//HCStackDebug.debuginfo("dataLen:"+Integer.toString(dataLen));
			if(dataLen<30){
				//System.out.println("!");
				/*if(dataLen>=0){
					dataLen = (dataLen<<8) + HCData.readCode(dis, 1);
				}*/
			}
			int pid = HCData.readCode(dis, 2);
			//System.out.println("part id:"+pid);
			//HCStackDebug.debuginfo("partid:"+Integer.toString(pid));
			offset += dataLen;
			if(offset > blockSize){
				//System.out.println("!");
			}
			if(pid<0 || pid >= 32768){
				//System.out.println("!");
			}
			int partType = HCData.readCode(dis, 1);
			//System.out.println("partType:"+partType);
			//HCStackDebug.debuginfo("partType:"+Integer.toString(partType));
			if(partType==1){
				OButton btn = new OButton(this, pid);
				btn.readButtonBlock(dis, dataLen);
				btnList.add(btn);
				partsList.add(btn);
			}
			else if(partType==2){
				OField fld = new OField(this, pid);
				fld.readFieldBlock(dis, dataLen);
				fldList.add(fld);
				partsList.add(fld);
			}
			else return false;
			
			//System.out.println("==end of part==");
		}

		for(int i=0; i<numofContents; i++){
			if(blockSize - offset<0){
				break;
			}
			//System.out.println("==cd content "+i+"==");
			//HCStackDebug.debuginfo("==cd content "+i+"==");
			
			int pid;
			/*{//アライメント調整
				pid = HCData.readCode(dis, 1);
				while(pid<=0 || (pid==255 && i<255) || (pid==254 && i<255)){
					pid = (pid<<8) + HCData.readCode(dis, 1);
				}
			}*/
			{
				pid = (int)(0x0000FFFF&HCData.readCode(dis, 2));
			}
			//System.out.println("pid:"+pid);
			//HCStackDebug.debuginfo("partid:"+Integer.toString(pid));
			if((pid<0 || pid >= 32768) && pid < 6500){
				//System.out.println("!");
			}
			OBgFieldData bgfld = null;
			if(pid<32768){
				//bg part
				bgfld = new OBgFieldData(this, pid);
			}
			int contLen = (int)(0x0000FFFF & HCData.readCode(dis, 2));
			int orgcontLen = contLen;
			//System.out.println("contLen:"+contLen);
			//HCStackDebug.debuginfo("contLen:"+Integer.toString(contLen));
			if(offset+contLen+4 > blockSize){
				//HCStackDebug.debuginfo("!!!");
				//HCStackDebug.debuginfo("(offset:"+Integer.toString(offset));
				//HCStackDebug.debuginfo("+contLen:"+Integer.toString(contLen));
				//HCStackDebug.debuginfo(">blockSize):"+Integer.toString(blockSize));
				//System.out.println("!");
				contLen=blockSize-offset-4-2;
				if(contLen<0) contLen = 0;
				//break;
			}
			int isStyledText = (int)(0x0000FF&HCData.readCode(dis, 1));
			if(isStyledText<128){
				offset += contLen+5;
				contLen-=1;
			}
			else if(isStyledText>=128){
				int formattingLength = (int)((0x007F&isStyledText)<<8)+HCData.readCode(dis, 1);
				//System.out.println("formattingLength:"+formattingLength);
				//HCStackDebug.debuginfo("formattingLength:"+Integer.toString(formattingLength));
				if(formattingLength>100){
					System.out.println("!");
				}
				for(int j=0; j<formattingLength/4; j++){
					styleClass styleC = new styleClass();
					styleC.textPosition = HCData.readCode(dis, 2);
					styleC.styleId = HCData.readCode(dis, 2);
					if(pid>32768){
						//cd part
						int inpid = 65536-pid;
						for(int k=0; k<partsList.size(); k++){
							if(partsList.get(k).id == inpid){
								if(partsList.get(k).objectType.equals("field")){
									OField fld = (OField)partsList.get(k);
									if(fld.styleList==null) fld.styleList = new ArrayList<styleClass>();
									fld.styleList.add(styleC);
									break;
								}
							}
						}
					}
					else{
						//bg part
						if(bgfld.styleList==null) bgfld.styleList = new ArrayList<styleClass>();
						bgfld.styleList.add(styleC);
					}
				}
				offset += contLen+4;
				contLen -= formattingLength;
			}
			
			//テキスト
			resultStr contentResult;
			if(orgcontLen%2==1){
				//System.out.println("readText(contLen+1="+(contLen+1)+")");
				contentResult = HCData.readText(dis, contLen+1);
			}
			else
			if(contLen>0){
				//System.out.println("readText(contLen="+(contLen)+")");
				contentResult = HCData.readText(dis, contLen);
			}else{
				contentResult = new resultStr();
				contentResult.str = "";
				contentResult.length_in_src = 0;
			}
			//System.out.println("contentResult:"+contentResult.str);
			//HCStackDebug.debuginfo("contentResult:"+contentResult.str);
			if(pid>=32768){
				//cd part
				pid = 65536-pid;
				boolean isFound = false;
				for(int k=0; k<partsList.size(); k++){
					if(partsList.get(k).id == pid){
						partsList.get(k).setText(contentResult.str);
						isFound = true;
						break;
					}
				}
				if(!isFound){
					//System.out.println("cd part "+pid+" not found.");
				}
			}
			else{
    			bgfld.text = contentResult.str;
    			bgfldList.add(bgfld);
			}

			int remainLength = contLen - ((contentResult.length_in_src));
			//System.out.println("contentResult.length_in_src:"+contentResult.length_in_src);
			//System.out.println("content-remainLength:"+remainLength);
			//HCStackDebug.debuginfo("content-remainLength:"+remainLength);
			if(remainLength<0 || remainLength > 32){
				//System.out.println("!");
			}
			if(remainLength-1>0){
				/*String padding =*/ HCData.readStr(dis, remainLength-1);
				//System.out.println("padding:"+padding);
				//HCStackDebug.debuginfo("padding:"+padding);
			}
		}

		/*if(offset%2==0){
			String paddingx = HCData.readStr(dis, 1);
			offset++;
			System.out.println("paddingx:"+paddingx);
			HCStackDebug.debuginfo("paddingx:"+paddingx);
		}*/
		
		resultStr nameResult = HCData.readTextToZero(dis, blockSize-offset);
		name = nameResult.str;
		//System.out.println("name:"+name);
		//HCStackDebug.debuginfo("name:"+name);
		
		resultStr scriptResult = HCData.readTextToZero(dis, blockSize-offset-nameResult.length_in_src);
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);
		//HCStackDebug.debuginfo("scriptStr:"+scriptStr);
		
		int remainLength = blockSize - (offset+(nameResult.length_in_src)+(scriptResult.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		//HCStackDebug.debuginfo("remainLength:"+remainLength);
		if(remainLength > 100){
			//System.out.println("!");
		}
		if(remainLength<0){
			//System.out.println("!");
		}
		if(blockSize==800){
			//System.out.println("!");
		}
		if(remainLength-1>0){
			if(remainLength-1>30 && nameResult.length_in_src == 0){
				resultStr scriptResult2 = HCData.readText(dis, remainLength-1);
				scriptStr = scriptResult2.str;
				//HCStackDebug.debuginfo("set to script.");
			}else{
				/*String padding =*/ HCData.readStr(dis, remainLength-1);
				//System.out.println("padding:"+padding);
				//HCStackDebug.debuginfo("padding:"+padding);
			}
			if(nameResult.length_in_src == 0 && scriptResult.length_in_src<100){
				name = scriptResult.str;
			}
		}
		
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
	
	
	private static OCard buildOCard(OCard cd)
	{
		cd.picture = new OPicture(cd);
		
		//ピクチャ読み込み(pbm)
		if(cd.bitmapName!=null){
			String path = (cd.stack.file.getParent()+File.separatorChar+cd.bitmapName);
			cd.pict = PictureFile.loadPbm(path);
			if(cd.pict==null){
				//読み込まれるまで待つ
				for(int i=0; HCData.threadList!=null&&i<HCData.threadList.size(); i++){
					Thread p = HCData.threadList.get(i);
					if(p!=null && p.getName().equals(cd.bitmapName)){
						if(!p.isAlive()){
							p.start();
						}
						long lastLength = -1;
						File file = new File(path);
						for(int j=0; !file.exists() && j<50 || p.isAlive() || lastLength!=file.length(); j++){
							lastLength = file.length();
							try {
								Thread.sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				}
				if(new File(path).exists() && !new File(path).isDirectory()){
					try {
						cd.pict = ImageIO.read(new File(path));
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
				cd.pict = ImageIO.read(file);
				cd.label = MyLabel.getMyCdLabel(cd/*new ImageIcon(stack.file.getParent()+"/resource/cdp"+this.id+".png")*/);
				cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
				PCARD.pc.mainPane.add(cd.label);
			} else {
				file=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".jpeg");
				if(file.exists()) {
					//System.out.println(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".jpeg");
					cd.pict = ImageIO.read(file);
					cd.pict = makeAlphaImage2(cd.pict);
					cd.label = MyLabel.getMyCdLabel(cd/*new ImageIcon(stack.file.getParent()+"/resource/cdp"+this.id+".jpeg")*/);
					cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
					PCARD.pc.mainPane.add(cd.label);
				}
			}
		} catch (Exception err1) {
			//アルファを使用したピクチャが無い場合はマスク画像を使用して作成
			try {
				cd.pict = ImageIO.read(new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+"_img.png"));
				file=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+"_mask.png");
				if(file.exists()) {
					BufferedImage mask = ImageIO.read(file);
					cd.pict = makeAlphaImage(cd.pict, mask);
					File ofile=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".png");
					ImageIO.write(cd.pict,"png",ofile);
				}
			}catch (Exception err) {
				//err.printStackTrace();
			}
		}
		
		return cd;
	}

	
	public static BufferedImage makeAlphaImage(BufferedImage src, BufferedImage mask) {
		int width = src.getWidth();
		int height = src.getHeight();
		
		if(mask.getWidth() != width) return null;
		if(mask.getHeight() != height) return null;
		
		BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
	
		DataBuffer srcb = src.getRaster().getDataBuffer();
		DataBuffer maskb = mask.getRaster().getDataBuffer();
		DataBuffer dstb = dst.getRaster().getDataBuffer();
		
		for(int y=0; y<height; y++) {
			for(int x=0; x<width; x++) {
				int a = maskb.getElem(0, y * width + x);
				int rgb = srcb.getElem(0, y * width + x);
				dstb.setElem(0, y * width + x, rgb | (a << 24));
			}
		}
		
		return dst;
	}
	
	public static BufferedImage makeAlphaImage2(BufferedImage in) {
		int width = in.getWidth();
		int height = in.getHeight();
		
		BufferedImage src = new BufferedImage(width,height, BufferedImage.TYPE_INT_RGB);
		BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
	
		Graphics2D g=src.createGraphics();
		g.drawImage(in, 0, 0, PCARD.pc.mainPane);
		
		DataBuffer srcb = src.getRaster().getDataBuffer();
		DataBuffer dstb = dst.getRaster().getDataBuffer();
		
		for(int y=0; y<height; y++) {
			for(int x=0; x<width; x++) {
				int rgb = srcb.getElem(0, y * width + x);
				int mask = 0xFF000000;
				if((rgb&0xFF) > 0xE0) mask = 0x00000000;
				dstb.setElem(0, y * width + x, rgb | mask);
			}
		}
		
		return dst;
	}

	static void reloadCurrentCard(){
		if(PCARD.pc.stack.curCard==null) return;
		
		//画面作り直し
		int id = PCARD.pc.stack.curCard.id;
		PCARD.pc.stack.curCard.removeData();
		if(PCARD.pc.stack.curCard.bg!=null) PCARD.pc.stack.curCard.bg.removeData();
		PCARD.pc.stack.curCard.bg = null;
		try {
			PCARD.pc.stack.curCard = OCard.getOCard(PCARD.pc.stack, id, false);
			PCARD.pc.stack.curCard.bg = OBackground.getOBackground(PCARD.pc.stack, PCARD.pc.stack.curCard, PCARD.pc.stack.curCard.bgid, false);
			PCARD.pc.stack.curCard.parent = PCARD.pc.stack.curCard.bg;
		} catch (xTalkException e1) {
			e1.printStackTrace();
		}
		
		//バックグラウンド編集の場合、カードのコンポーネントを外す
		if(PaintTool.editBackground){
			for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
				PCARD.pc.mainPane.remove(PCARD.pc.stack.curCard.btnList.get(i).getComponent());
			}
			for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
				PCARD.pc.mainPane.remove(PCARD.pc.stack.curCard.fldList.get(i).getComponent());
			}
		}
		
		PCARD.pc.mainPane.repaint();
		
		//リスナーも付け直し
		if(AuthTool.tool!=null){
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
		}else if(PCARD.pc.tool!=null){
			//ペイントモード
		}else{
			//ブラウズモード
			GUI.removeAllListener();
			GUI.addAllListener();
			if(PCARD.pc.stack.addColor != null){
				TXcmd.AddColor.addToMainPane();
			}
		}
	}
}

class MyLabel extends JLabel {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	static MyLabel cdlabel = null;
	static MyLabel bglabel = null;
	OCardBase cd;
	
	static MyLabel getMyCdLabel(OCardBase in_cd){
		if(cdlabel==null) cdlabel = new MyLabel();
		cdlabel.cd = in_cd;
		cdlabel.setDoubleBuffered(PCARD.useDoubleBuffer);
		return cdlabel;
	}
	
	static MyLabel getMyBgLabel(OCardBase in_cd){
		if(bglabel==null) bglabel = new MyLabel();
		bglabel.cd = in_cd;
		bglabel.setDoubleBuffered(PCARD.useDoubleBuffer);
		return bglabel;
	}
	
	
	
    //@SuppressWarnings("restriction")
	@Override
    protected void paintComponent(Graphics g) {
    	if(PCARD.pc.tool!=null)return;
    	if(cd.objectType.equals("card")&&PaintTool.editBackground)return;
		//Graphics mainpaneg = PCARD.pc.mainPane.getGraphics();
		//Graphics paneg = PCARD.pc.getContentPane().getGraphics();
		//Field f = null;
		try {
			//f = sun.java2d.SunGraphics2D.class.getDeclaredField("surfaceData");
			//f.setAccessible(true);
		} catch (SecurityException e) {
			e.printStackTrace();
		//} catch (NoSuchFieldException e) {
		//	e.printStackTrace();
		}
		try {
		//	if(PCARD.lockedScreen&&(f.get(paneg)==f.get(g)||f.get(mainpaneg)==f.get(g))) return;//g.drawImage(VEffect.oldoff,0,0,PCARD.pc.mainPane);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		//} catch (IllegalAccessException e) {
		//	e.printStackTrace();
		}
    	
		if(!cd.showPict||cd.pict==null){
			if(cd.objectType.equals("background")){
				g.setColor(Color.WHITE);
				Rectangle r = g.getClipBounds();
				g.fillRect(r.x, r.y, r.width, r.height);
			}
			return;
		}
		if(cd.objectType.equals("card")&& cd!=PCARD.pc.stack.curCard) return;
		
		g.drawImage(cd.pict,0,0,PCARD.pc.mainPane);

		//addColor用
		/*if(PCARD.pc.stack.addColor != null){
			Graphics2D g2 = (Graphics2D) g;
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
			g2.drawImage(PCARD.pc.stack.addColor,0,0,null);
		}*/
    }
}

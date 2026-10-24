import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
			throw new xTalkException("No data file for the card with that id");
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
	            	else if(elm.equals("paintLayer")){ layers.add(PaintLayer.readXML(reader)); }
				else if(elm.equals("shape")){ shapeList.add(OShape.readXML(reader,this)); }
				else if(elm.equals("panel")){ readPanel(reader); }
	            	else if(elm.equals("cantDelete")){ cantDelete = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantModify")){ cantModify = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("showPict")){ showPict = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("dontSearch")){ dontSearch = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("nextPartId")){ try{ nextPartId = Integer.parseInt(reader.getElementText().trim()); }catch(NumberFormatException e){} }
	            	else if(elm.equals("marked")){ marked = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("owner") || elm.equals("background")){
	            		bgid = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("name")){ 
	            		name = reader.getElementText();
	            	}
	            	else if(elm.equals("osaScript")) osaScript = OsaScript.readXML(reader);
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		//末尾の空行を保つため、limit引数に-1を指定する(下記参照)
	            		String[] scriptAry = scrStr.split("\n", -1);
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
			OField contentField = null;
			OBgFieldData contentBgField = null;
			ArrayList<styleClass> contentStyles = new ArrayList<styleClass>();
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
							if(part instanceof OField) contentField = (OField)part;
						}else{
							System.out.println("No Part: "+layer + " part id " + partid);
						}
					}else{
						OBgFieldData bgfld = new OBgFieldData(this, partid);
						bgfld.text = reader.getElementText();
						this.bgfldList.add(bgfld);
						contentBgField = bgfld;
					}
				}
			else if(elm2.equals("highlight")){
					OBgButtonData bgbtn = new OBgButtonData(this, partid);
					bgbtn.check_hilite = XMLRead.bool(reader);reader.next();
					this.bgbtnList.add(bgbtn);
				}
				else if(elm2.equals("stylerun")){
					int styleid = 0;
					int offset = 0;
				            while(reader.hasNext()){
					int eventType3 = reader.next();
					if (eventType3 == XMLStreamReader.START_ELEMENT) {
						String elm3 = reader.getLocalName();
						if(elm3.equals("offset")){ offset = Integer.valueOf(reader.getElementText()); }
						else if(elm3.equals("id")){
							styleid = Integer.valueOf(reader.getElementText());
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
							contentStyles.add(new styleClass(offset, styleid));
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
				if(contentField!=null && !contentStyles.isEmpty())
					contentField.styleList = new ArrayList<styleClass>(contentStyles);
				if(contentBgField!=null && !contentStyles.isEmpty())
					contentBgField.styleList = new ArrayList<styleClass>(contentStyles);
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
			    XMLRead.readError(ex);
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
        //追加のペイントレイヤー(pictの上、下から順)
        if(layers!=null) for(PaintLayer l : layers) l.writeXML(writer);
        
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
        writeNextPartId(writer);
        
        if(marked){
            writer.writeStartElement("marked");
            writer.writeCharacters(" ");
            writer.writeEmptyElement("true ");
            writer.writeCharacters(" ");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t");
        }
        
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
		if(shapeList!=null) for(OShape shape:shapeList){shape.writeXML(writer);writer.writeCharacters("\n\t\t");}
		writePanels(writer);

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
            //bgボタンが削除済み(またはbgが見つからない)ならそのカードのハイライト
            //情報は書かない。以前はここでNPEになり、自動保存スレッドが止まっていた。
            OButton bgBtn = GetBgBtnbyId(part.id);
        	if(bgBtn!=null && bgBtn.sharedHilite==false){
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
        
        if(osaScript!=null) osaScript.writeXML(writer, "\n\t"); //HyperCard 2.2+ compiled AppleScript, kept as is (OsaScript)

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
		
		int offset = HCData.isV1() ? 50 : 54;
		
		//ブロックのデータを順次読み込み
		id = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("id:"+Integer.toString(id));
		//System.out.println("id:"+id);
		if(id<0 || id >= 2265535){
			//System.out.println("!");
		}
		/*String tygersStr =*/ HCData.readStr(dis, HCData.isV1() ? 0 : 4); //1.x: ブロックIDの後の詰め物が無く、以降の項目が4バイト前にある
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
		nextPartId = HCData.readCode(dis, 2); //the id the next new part gets (newPartId)
		/*int partListSize =*/ HCData.readCode(dis, 4);
		//System.out.println("tygers3Str:"+tygers3Str);
		int numofContents = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("numofContents:"+Integer.toString(numofContents));
		//System.out.println("numofContents:"+numofContents);
		//the total size of the content records - not a script type: a script in AppleScript is told by its 'WOSA'
		//record after the script text (OsaScript)
		int contentsSize = HCData.readCode(dis, 4);
		
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

		for(HCData.PartContent content : HCData.readContents(dis, numofContents, contentsSize)){
			int rawPartId = content.partId;
			int encodedPartId = rawPartId;
			// A negative part ID denotes a card part; a positive ID denotes a
			// background part. readPartContent normalizes the numeric ID.
			// Recover the layer from the first two bytes already represented by
			// whether a matching card part exists.
			OObject cardPart = null;
			if(content.cardPart){
				for(int k=0; k<partsList.size(); k++){
					if(partsList.get(k).id == encodedPartId){
						cardPart = partsList.get(k);
						break;
					}
				}
			}
			if(content.cardPart && cardPart!=null){
				cardPart.setText(content.text);
				if(cardPart.objectType.equals("field") && !content.styles.isEmpty()){
					OField fld = (OField)cardPart;
					if(fld.styleList==null) fld.styleList = new ArrayList<styleClass>();
					fld.styleList.addAll(content.styles);
				}
			}else if(!content.cardPart){
				OObject backgroundPart = null;
				for(OBackground background: stack.bgCacheList){
					if(background.id==bgid){
						backgroundPart = background.GetPartbyId(encodedPartId);
						break;
					}
				}
				if(backgroundPart instanceof OButton){
					OBgButtonData bgbtn = new OBgButtonData(this, encodedPartId);
					bgbtn.check_hilite = content.text.equals("1") ||
						content.text.equalsIgnoreCase("true");
					bgbtnList.add(bgbtn);
				}else{
					OBgFieldData bgfld = new OBgFieldData(this, encodedPartId);
					bgfld.text = content.text;
					if(!content.styles.isEmpty()) bgfld.styleList = content.styles;
					bgfldList.add(bgfld);
				}
			}
		}

		// 宣言サイズで切り分けられたCARDストリームの残量を基準にする。
		// 旧offset計算にはcontentのパディング差によるずれがあり、次の
		// ブロックまで読み越すことがあった。
		resultStr nameResult = HCData.readTextToZero(dis, HCData.remaining(dis));
		name = nameResult.str;
		//System.out.println("name:"+name);
		//HCStackDebug.debuginfo("name:"+name);

		//CARDレベルのname(NUL終端文字列)の直後にパディングは無い。
		//以前はここに(条件付き、さらには無条件の)パディング読み飛ばしを
		//入れていたが、どちらも誤りだった。HyperZebraで書き出したものではない
		//複数のHyperCardスタックを読み込んで確認したところ、CARDレベルのnameの
		//直後にはパディングが一切無く、scriptがNULの直後に直接続いていた。
		//誤って1バイト読み飛ばしていたせいで、カードスクリプトの先頭1文字が
		//失われていた(例:「on opencard」が「n opencard」に化ける)。
		//なお、PART(ボタン/フィールド)レベルのname・scriptには実際に
		//無条件で1バイトパディングが入る(HCExport.writeCString参照)。
		//これはPARTレコード固有の仕様であり、CARD/BACKGROUNDレベルの
		//nameには当てはまらない。

		//HyperCard 2.2+: an AppleScript (OSA) script is source text + NUL + a compiled 'WOSA' record (OsaScript)
		resultStr scriptResult = HCData.readOsaScript(dis, HCData.remaining(dis), 0);
		if(scriptResult!=null) osaScript = scriptResult.osa;
		else scriptResult = HCData.readScriptToZero(dis, HCData.remaining(dis));
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);
		//HCStackDebug.debuginfo("scriptStr:"+scriptStr);
		
		int remainLength = HCData.remaining(dis);
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
		if(remainLength>0) HCData.readStr(dis, remainLength);
		
		//スクリプト
		//limit引数を-1にして、末尾の空行(空文字列)が切り捨てられないようにする
		//(デフォルトのsplit("\n")は末尾の空文字列要素を削除してしまうため、
		//スクリプト末尾の空行が書き出すたびに失われてしまっていた)。
		String[] scriptAry = scriptStr.split("\n", -1);
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
		//追加のペイントレイヤーの画像
		cd.loadLayerImages();
		//カードピクチャが無くてもレイヤーがあれば表示用のラベルが要る
		if(cd.hasLayers() && cd.bitmapName==null){
			cd.label = MyLabel.getMyCdLabel(cd);
			cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
			PCARD.pc.mainPane.add(cd.label);
		}
		
		//ピクチャ読み込み(pbm)
		if(cd.bitmapName!=null){
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
								Thread.sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				}
				if(new File(path).exists() && !new File(path).isDirectory()){
					try {
						cd.pict = PictureCache.read(new File(path), "imageio", StackProtection::readImage);
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
				cd.pict = PictureCache.read(file, "imageio", StackProtection::readImage);
				cd.label = MyLabel.getMyCdLabel(cd/*new ImageIcon(stack.file.getParent()+"/resource/cdp"+this.id+".png")*/);
				cd.label.setBounds(0,0,cd.stack.width,cd.stack.height);
				PCARD.pc.mainPane.add(cd.label);
			} else {
				file=new File(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".jpeg");
				if(file.exists()) {
					//System.out.println(cd.stack.file.getParent()+"/resource/cdp"+cd.id+".jpeg");
					cd.pict = PictureCache.read(file, "imageio", StackProtection::readImage);
					cd.pict = makeAlphaImage2(cd.pict);
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
					cd.pict = makeAlphaImage(cd.pict, mask);
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
				// Replace, rather than OR, the source alpha. Color-table images are
				// initially opaque, and OR would make transparent cicn pixels opaque.
				dstb.setElem(0, y * width + x, (rgb & 0x00FFFFFF) | ((a & 0xFF) << 24));
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
		//a tool chosen in openStack runs later on the event thread; when the stack was replaced meanwhile (an imported stack
		//reopened from its .hzb, cards still loading: AOL HyperDark modules) its card is not there yet - keep the screen as it is
		boolean cached = false;
		for(OCard cd : PCARD.pc.stack.cdCacheList) if(cd.stack==PCARD.pc.stack && cd.id==id){ cached = true; break; }
		if(!cached) return;
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
			for(OShape shape:PCARD.pc.stack.curCard.shapeList)if(shape.view!=null)PCARD.pc.mainPane.remove(shape.view);
			for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
				OButton removedButton = PCARD.pc.stack.curCard.btnList.get(i);
				PCARD.pc.mainPane.remove(removedButton.getComponent());
				//popup(style==8)の名前ラベルはgetComponent()とは別の部品なので、
				//あわせて外さないとポップアップ本体だけ消えて名前だけ残ってしまう。
				if(removedButton.popupNameLabel!=null) PCARD.pc.mainPane.remove(removedButton.popupNameLabel);
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
			else if(AuthTool.tool instanceof ShapeTool){GUI.removeAllListener();ShapeTool.refresh();}
		}else if(PCARD.pc.tool!=null){
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

	//2倍拡大表示モードでは、呼び出し元は今まで通り論理座標(x,y,w,h)を渡してよい。
	//実際のSwing上のサイズ・位置だけをここで拡大率倍する。
	@Override
	public void setBounds(int x, int y, int width, int height){
		Rectangle physical = PCARD.toPhysical(x, y, width, height);
		super.setBounds(physical.x, physical.y, physical.width, physical.height);
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
    	
		//追加のペイントレイヤーがあれば平らにした絵(無ければpictそのもの)
		BufferedImage pict = cd.composite();
		if(!cd.showPict||pict==null){
			if(cd.objectType.equals("background")){
				g.setColor(Color.WHITE);
				Rectangle r = g.getClipBounds();
				g.fillRect(r.x, r.y, r.width, r.height);
			}
			return;
		}
		if(cd.objectType.equals("card")&& cd!=PCARD.pc.stack.curCard) return;
		//a background picture narrower or shorter than the card (truePrint: 128 wide on a 160-wide card) leaves the rest
		//white in HyperCard; here the window's black showed through
		if(cd.objectType.equals("background")){
			int pw = PCARD.toPhysical(pict.getWidth()), ph = PCARD.toPhysical(pict.getHeight());
			if(pw<getWidth() || ph<getHeight()){
				g.setColor(Color.WHITE);
				g.fillRect(pw, 0, Math.max(0, getWidth()-pw), getHeight());
				g.fillRect(0, ph, Math.min(pw, getWidth()), Math.max(0, getHeight()-ph));
			}
		}

		//整数倍ならドット絵をにじませず、ピクセルパーフェクトに拡大表示する(最大125%などは滑らかに)
		PCARD.drawScaled(g, pict, 0, 0, PCARD.pc.mainPane);

		//addColor用
		/*if(PCARD.pc.stack.addColor != null){
			Graphics2D g2 = (Graphics2D) g;
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
			g2.drawImage(PCARD.pc.stack.addColor,0,0,null);
		}*/
    }
}

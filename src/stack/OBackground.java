import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.imageio.ImageIO;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OBackground extends OCardBase {
	//プロパティ
	Boolean cantDelete=false;
	Boolean cantModify=false;
	Boolean dontSearch=false;
	//number (スタックの情報から求める)
	
	//追加プロパティ
	OCard viewCard;
	/** While a HyperCard stack is read: the BKGD block's next background ID (HCData orders the backgrounds by it). */
	int importNextBg;
	
	//toc.xml
	@SuppressWarnings("unused")
	private String filler1;

	public void clean(){
		super.clean();
		viewCard = null;
	}
	
	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}

	//set
	public void setName(String in) {
		name=in;
	}
	
	//メイン
	public static OBackground getOBackground(OStack st, OCard cd, int bgId, boolean dataonly) throws xTalkException {
		//System.out.println("bgid="+bgId);
		
		for(int i=0; i<st.bgCacheList.size(); i++){
			OBackground bg = st.bgCacheList.get(i);
			//System.out.println("bg="+bg.id);
			if(st==cd.stack && bgId==bg.id){
				if(dataonly) return bg;
				else {
					//このbgを表示しているカード。OField.setText2は非共有フィールドの
					//テキストをここに記録されたカードのbgfldListへ保存する。
					//以前はどこでも代入されていなかったため、非共有フィールドの編集が
					//bg側の共有テキストにしか書かれず、保存後に消えていた。
					bg.viewCard = cd;
					applyCardContents(bg, cd);

					buidParts(bg);
					return buildOBackground(bg);
				}
			}
		}
		
		if(st.firstBg>0 && bgId!=st.firstBg){
			//HCスタックをコンバートするとbgが存在しないことがあるため、最初のbgで代用
			return getOBackground(st, cd, st.firstBg, dataonly);
		}
		
		return new OBackground(st, cd, bgId, dataonly);
	}
	
	//非共有(sharedText=false)のbgフィールドに、カードごとのテキストを反映する。
	//カードにcontentが無いフィールドは空にする。以前はcontentのあるフィールド
	//しか差し替えなかったため、content の無いカード(新規カード等)へ移動すると
	//直前のカードのテキストがそのまま残って表示・保存されていた。
	//sharedHilite=falseのbgボタンも同様に、カードごとのハイライトを反映する。
	//以前はbgbtnListを読み書きするだけで表示に使っておらず、全カードで同じ
	//ハイライトになっていた。
	static void applyCardContents(OBackground bg, OCard cd){
		applyCardFieldContents(bg, cd);
		for(int i=0; i<bg.btnList.size(); i++){
			OButton btn = bg.btnList.get(i);
			if(btn.sharedHilite) continue;
			OBgButtonData data = null;
			for(int j=0; j<cd.bgbtnList.size(); j++){
				if(cd.bgbtnList.get(j).id == btn.id){ data = cd.bgbtnList.get(j); break; }
			}
			if(data!=null) data.btn = btn;
			btn.applyCardHilite(data);
		}
	}

	static void applyCardFieldContents(OBackground bg, OCard cd){
		for(int i=0; i<bg.fldList.size(); i++){
			OField fld = bg.fldList.get(i);
			if(fld.sharedText) continue;
			OBgFieldData data = null;
			for(int j=0; j<cd.bgfldList.size(); j++){
				if(cd.bgfldList.get(j).id == fld.id){ data = cd.bgfldList.get(j); break; }
			}
			if(data != null){
				data.fld = fld;
				fld.setTextInternal(data.text==null ? "" : data.text);
				fld.styleList = data.styleList;
			}else{
				fld.setTextInternal("");
				fld.styleList = null;
			}
		}
	}

	//カードを離れるときに、表示中カードの参照も外す(古いカードのbgfldListへ
	//書き込んでしまわないように)。次に表示するときgetOBackgroundで付け直す。
	@Override
	public void removeData(){
		super.removeData();
		viewCard = null;
	}

	OBackground(OStack st, OCard cd, int bgId, boolean dataonly) throws xTalkException {
		super(st);
    	objectType="background";
		parent = st;
		viewCard = cd;
		this.btnList = new ArrayList<OButton>();
		this.fldList = new ArrayList<OField>();
		this.partsList = new ArrayList<OObject>();
		this.scriptList = new ArrayList<String>();

		id=bgId;
		{
			throw new xTalkException("No data file for the background with that id");
		}
	}
	
	
	
	public OBackground(OStack st){
		super(st);
		
		objectType="background";
		parent = st;
		this.btnList = new ArrayList<OButton>();
		this.fldList = new ArrayList<OField>();
		this.partsList = new ArrayList<OObject>();
		this.scriptList = new ArrayList<String>();
	}
	

	@SuppressWarnings("unchecked")
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("id")){ id = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("filler1")){ filler1 = reader.getElementText(); }
	            	else if(elm.equals("bitmap")){ bitmapName = reader.getElementText(); }
	            	else if(elm.equals("paintLayer")){ layers.add(PaintLayer.readXML(reader)); }
				else if(elm.equals("shape")){ shapeList.add(OShape.readXML(reader,this)); }
				else if(elm.equals("panel")){ readPanel(reader); }
	            	else if(elm.equals("cantDelete")){ cantDelete = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantModify")){ cantModify = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("showPict")){ showPict = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("dontSearch")){ dontSearch = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("nextPartId")){ try{ nextPartId = Integer.parseInt(reader.getElementText().trim()); }catch(NumberFormatException e){} }
	            	//else if(elm.equals("owner")){ bgid = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("name")){ 
	            		name = reader.getElementText();
	            	}
	            	else if(elm.equals("osaScript")) osaScript = OsaScript.readXML(reader);
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		//末尾の空行を保つため、limit引数に-1を指定する
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
			ArrayList<styleClass> contentStyles = new ArrayList<styleClass>();
		            while(reader.hasNext()){
			int eventType2 = reader.next();
			if (eventType2 == XMLStreamReader.START_ELEMENT) {
				String elm2 = reader.getLocalName();
				if(elm2.equals("layer")){ layer = reader.getElementText(); }
				else if(elm2.equals("id")){ partid = Integer.valueOf(reader.getElementText()); }
				else if(elm2.equals("text")){
					if(layer.equals("background")){
						OObject part = this.GetPartbyId(partid);
						if(part!=null){
							part.setTextInternal(reader.getElementText());
							if(part instanceof OField) contentField = (OField)part;
						}else{
							System.out.println("No Part: "+layer + " part id " + partid);
						}
					}else{
						//
						System.out.println("Local Name: " + reader.getLocalName());
					}
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
	            	if(elm.equals("background")){
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
	        }
	    }
	    return reader;
	}
	
	
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("background");
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
        	//非共有フィールドの現在のテキストは表示中カードのものなので、
        	//bg側のcontentには書かない(カード側のcontentとして保存される)
        	if(part instanceof OField && !((OField)part).sharedText) continue;
        	if(part.getText().length()>0){
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

	
	@SuppressWarnings("unchecked")
	public boolean readBackgroundBlock(DataInputStream dis, int blockSize){
		//System.out.println("====readBackgroundBlock====");

		if(blockSize>2000000 || blockSize<50){
			return false;
		}
		
		int offset = HCData.isV1() ? 46 : 50;
		
		//ブロックのデータを順次読み込み
		id = HCData.readCode(dis, 4);
		//System.out.println("id:"+id);
		if(id<0 || id >= 2265535){
			//System.out.println("!");
		}
		/*String tygersStr =*/ HCData.readStr(dis, HCData.isV1() ? 0 : 4); //1.x: ブロックIDの後の詰め物が無く、以降の項目が4バイト前にある
		//System.out.println("tygersStr:"+tygersStr);
		int bitmapId = HCData.readCode(dis, 4);
		//System.out.println("bitmapId:"+bitmapId);
		if(bitmapId>0){
			bitmapName = "BMAP_"+bitmapId+".png";
		}
		int flags = HCData.readCode(dis, 2);
		//System.out.println("flags:"+flags);
		dontSearch = ((flags>>11)&0x01)!=0;
		showPict = !( ((flags>>13)&0x01)!=0);
		cantDelete = ((flags>>14)&0x01)!=0;
		/*String tygers2Str =*/ HCData.readStr(dis, 6);
		//System.out.println("tygers2Str:"+tygers2Str);
		importNextBg = HCData.readCode(dis, 4); //the background list: HCData orders the stack's backgrounds by it
		/*int prevBkgndId =*/ HCData.readCode(dis, 4);
		//System.out.println("prevBkgndId:"+prevBkgndId);
		int numofParts = HCData.readCode(dis, 2);
		//System.out.println("numofParts:"+numofParts);
		nextPartId = HCData.readCode(dis, 2); //the id the next new part gets (newPartId)
		/*int partListSize =*/ HCData.readCode(dis, 4);
		//System.out.println("tygers3Str:"+tygers3Str);
		int numofContents = HCData.readCode(dis, 2);
		//System.out.println("numofContents:"+numofContents);
		//the total size of the content records - not a script type: a script in AppleScript is told by its 'WOSA'
		//record after the script text (OsaScript)
		int contentsSize = HCData.readCode(dis, 4);
		
		for(int i=0; i<numofParts; i++){
			//System.out.println("==part "+i+"==");
			int dataLen = HCData.readCode(dis, 2);
			//System.out.println("dataLen:"+dataLen);
			if(dataLen<30){
				System.out.println("!");
				/*if(dataLen>=0){
					dataLen = (dataLen<<8) + HCData.readCode(dis, 1);
				}*/
			}
			offset += dataLen;
			int pid = HCData.readCode(dis, 2);
			//System.out.println("part id:"+pid);
			if((pid<0 || pid >= 32768) && pid < 6500){
				System.out.println("!");
			}
			int partType = HCData.readCode(dis, 1);
			//System.out.println("partType:"+partType);
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
			for(int k=0; k<partsList.size(); k++){
				if(partsList.get(k).id == content.partId){
					OObject part = partsList.get(k);
					part.setText(content.text);
					if(part.objectType.equals("field") && !content.styles.isEmpty()){
						OField fld = (OField)part;
						if(fld.styleList==null) fld.styleList = new ArrayList<styleClass>();
						fld.styleList.addAll(content.styles);
					}
					break;
				}
			}
		}

		// BKGDもCARDと同様に、実際のブロック残量から名前とスクリプトを読む。
		resultStr nameResult = HCData.readTextToZero(dis, HCData.remaining(dis));
		name = nameResult.str;
		//System.out.println("name:"+name);

		//BACKGROUNDレベルのname(NUL終端文字列)の直後にパディングは無い。
		//以前はここに(条件付き、さらには無条件の)パディング読み飛ばしを
		//入れていたが、どちらも誤りだった。実在の(HyperZebraで書き出した
		//のではない)本物のHyperCardスタックを読み込んで確認したところ、
		//BACKGROUNDレベルのnameの直後にはパディングが一切無く、scriptが
		//NULの直後に直接続いていた(CARDレベルと同様。OCard.javaのコメント
		//参照)。なお、PART(ボタン/フィールド)レベルのname・scriptには
		//実際に無条件で1バイトパディングが入る(HCExport.writeCString参照)。
		//これはPARTレコード固有の仕様であり、CARD/BACKGROUNDレベルの
		//nameには当てはまらない。

		//HyperCard 2.2+: an AppleScript (OSA) script is source text + NUL + a compiled 'WOSA' record (OsaScript)
		resultStr scriptResult = HCData.readOsaScript(dis, HCData.remaining(dis), 0);
		if(scriptResult!=null) osaScript = scriptResult.osa;
		else scriptResult = HCData.readScriptToZero(dis, HCData.remaining(dis));
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);
		
		int remainLength = HCData.remaining(dis);
		//System.out.println("remainLength:"+remainLength);
		if(remainLength > 100){
			//System.out.println("!");
		}
		if(remainLength<0){
			//System.out.println("!");
		}
		if(remainLength>0) HCData.readStr(dis, remainLength);
		/*if((nameResult.length_in_src+1+scriptResult.length_in_src+1)%2 != 0){
			String padding = HCData.readStr(dis, 1);
			System.out.println("padding:"+padding);
		}*/
		
		//スクリプト
		//末尾の空行を保つため、limit引数に-1を指定する(OCard.javaの
		//readCardBlock参照)
		String[] scriptAry = scriptStr.split("\n", -1);
		for(int i=0; i<scriptAry.length; i++)
		{
			scriptList.add(scriptAry[i]);
		}
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		
		return true;
	}
	
	
	private static OBackground buildOBackground(OBackground bg)
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
								Thread.sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				}
				if(new File(path).exists() && !new File(path).isDirectory()){
					try {
						bg.pict = PictureCache.read(new File(path), "imageio", StackProtection::readImage);
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
				bg.pict = PictureCache.read(file, "imageio", StackProtection::readImage);
				//bg.showPict = true;
			} else {
				file=new File(bg.stack.file.getParent()+"/resource/bgp"+bg.id+".jpeg");
				bg.pict = PictureCache.read(file, "imageio", StackProtection::readImage);
				//bg.showPict = true;
			}
		} catch (Exception err1) {
			//err1.printStackTrace();
		}
		
		return bg;
	}
}



class OBgButtonData {

	OCardBase card;
	OButton btn;
	boolean check_hilite = false; //ハイライト情報をカードごとに持つ
	int id;
	

	public OBgButtonData(OCardBase cd, int btnId) {
		card = cd;
		
		id=btnId;
	}
	
	public OBgButtonData(OCardBase cd, String data, int btnId) {
		card = cd;
		
		readButtonData(data);
		id=btnId;
	}
	
	public void readButtonData(String indata) {
		String[] data = indata.split("\n");
		boolean hilite=false;
		
		for(int line=0; line<data.length; line++){
			String str=data[line];
		
			if(str.length()>=2 && str.charAt(0)=='#' && str.charAt(1)!='#') {
				String istr;
				
				istr="#hilite:";
				if(str.startsWith(istr)) {
					String tmpstr=str.substring(istr.length());
					hilite=(tmpstr.compareTo("true")==0);
					check_hilite = hilite;
				}
			}
		}
	}
}


class OBgFieldData {

	OCardBase card;
	OField fld;
	String text;
	int id;
	ArrayList<styleClass> styleList;
	
	public OBgFieldData(OCardBase cd, String data, int fldId) {
		card = cd;
		text="";
		
		readFieldData(data);
		id=fldId;
	}
	public OBgFieldData(OCardBase cd, int fldId) {
		card = cd;
		text="";
		
		id=fldId;
	}
	
	public void readFieldData(String indata) {
		String[] data = indata.split("\n");
		int isText = 0;
		
		for(int line=0; line<data.length; line++){
			String str=data[line];

			if(str.length()>=2 && str.charAt(0)=='#' && str.charAt(1)!='#') {
				isText = 0;
				String istr;
				
				istr="#text:";
				if(str.startsWith(istr)) {
					str=str.substring(istr.length());
					isText=1;
				}
			}
			
			if(isText>=1) {
				if(isText==2) text+="\r\n";
				text+=str;
				isText=2;
			}
		}
	}
}

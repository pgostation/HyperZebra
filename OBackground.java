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
					//シェアードしてないテキスト
					for(int j=0; j<cd.bgfldList.size(); j++){
						OField fld = bg.GetBgFldbyId(cd.bgfldList.get(j).id);
						if(fld != null && fld.sharedText == false){
							fld.setTextInternal(cd.bgfldList.get(j).text);
						}
					}
					
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
			throw new xTalkException("そのidのバックグラウンドのデータファイルがありません");
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
	            	else if(elm.equals("cantDelete")){ cantDelete = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("cantModify")){ cantModify = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("showPict")){ showPict = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("dontSearch")){ dontSearch = XMLRead.bool(reader);reader.next(); }
	            	//else if(elm.equals("owner")){ bgid = Integer.valueOf(reader.getElementText()); }
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
	    		            		if(layer.equals("background")){
	    		            			OObject part = this.GetPartbyId(partid);
	    		            			if(part!=null){
	    		            				part.setTextInternal(reader.getElementText());
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
		    		    		            			if(part.objectType.equals("button")){
		    		    		            				OButton btn = (OButton)part;
		    		    		            				btn.textStyle = style;
		    		    		            			}
		    		    		            			else if(part.objectType.equals("field")){
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
	            	if(elm.equals("background")){
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

        writer.writeEndElement();
        writer.writeCharacters("\n\t");
	}

	
	@SuppressWarnings("unchecked")
	public boolean readBackgroundBlock(DataInputStream dis, int blockSize){
		//System.out.println("====readBackgroundBlock====");

		if(blockSize>2000000 || blockSize<50){
			return false;
		}
		
		int offset = 50;
		
		//ブロックのデータを順次読み込み
		id = HCData.readCode(dis, 4);
		//System.out.println("id:"+id);
		if(id<0 || id >= 2265535){
			//System.out.println("!");
		}
		/*String tygersStr =*/ HCData.readStr(dis, 4);
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
		/*int nextBkgndId =*/ HCData.readCode(dis, 4);
		//System.out.println("nextBkgndId:"+nextBkgndId);
		/*int prevBkgndId =*/ HCData.readCode(dis, 4);
		//System.out.println("prevBkgndId:"+prevBkgndId);
		int numofParts = HCData.readCode(dis, 2);
		//System.out.println("numofParts:"+numofParts);
		/*String tygers3Str =*/ HCData.readStr(dis, 6);
		//System.out.println("tygers3Str:"+tygers3Str);
		int numofContents = HCData.readCode(dis, 2);
		//System.out.println("numofContents:"+numofContents);
		/*int scriptType =*/ HCData.readCode(dis, 4);
		//System.out.println("scriptType:"+scriptType);
		
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

		for(int i=0; i<numofContents; i++){
			//System.out.println("==content "+i+"==");
			
			int pid;
			{//アライメント調整
				pid = HCData.readCode(dis, 1);
				while(pid<=i){
					pid = (pid<<8) + HCData.readCode(dis, 1);
				}
			}
			//{
			//	pid = HCData.readCode(dis, 2);
			//}
			//System.out.println("pid:"+pid);
			int contLen = HCData.readCode(dis, 2);
			int orgcontLen = contLen;
			//System.out.println("contLen:"+contLen);
			//offset += contLen+4;
			if(pid >= 32768){
				/*String padding =*/ HCData.readStr(dis, contLen);
				continue;
			}
			int isStyledText = (int)(0x0000FF&HCData.readCode(dis, 1));
			if(isStyledText<128){
				offset += contLen+5;
				contLen-=1;
			}
			else if(isStyledText>=128){
				int formattingLength = (int)((0x007F&isStyledText)<<8)+HCData.readCode(dis, 1);
				//offset += 2;
				//System.out.println("formattingLength:"+formattingLength);
				if(formattingLength>100){
					//System.out.println("!");
				}
				for(int j=0; j<formattingLength/4; j++){
					styleClass styleC = new styleClass();
					styleC.textPosition = HCData.readCode(dis, 2);
					styleC.styleId = HCData.readCode(dis, 2);
					for(int k=0; k<partsList.size(); k++){
						if(partsList.get(k).id == pid){
							if(partsList.get(k).objectType.equals("field")){
								OField fld = (OField)partsList.get(k);
								if(fld.styleList==null) fld.styleList = new ArrayList<styleClass>();
								fld.styleList.add(styleC);
								break;
							}
						}
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
			for(int k=0; k<partsList.size(); k++){
				if(partsList.get(k).id == pid){
					partsList.get(k).setText(contentResult.str);
					break;
				}
			}

			int remainLength = contLen - ((contentResult.length_in_src));
			//System.out.println("content-remainLength:"+remainLength);
			if(remainLength<0 || remainLength > 16){
				//System.out.println("!");
			}
			if(remainLength-1>0){
				/*String padding =*/ HCData.readStr(dis, remainLength-1);
				//System.out.println("padding:"+padding);
			}
		}

		resultStr nameResult = HCData.readTextToZero(dis, blockSize-offset);
		name = nameResult.str;
		//System.out.println("name:"+name);
		
		resultStr scriptResult = HCData.readTextToZero(dis, blockSize-offset-nameResult.length_in_src);
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);
		
		int remainLength = blockSize - (offset+(nameResult.length_in_src)+(scriptResult.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		if(remainLength > 100){
			//System.out.println("!");
		}
		if(remainLength<0){
			//System.out.println("!");
		}
		if(remainLength-1>0){
			if(remainLength-1>30 && nameResult.length_in_src == 0){
				resultStr scriptResult2 = HCData.readText(dis, remainLength-1);
				scriptStr = scriptResult2.str;
			}else{
				/*String padding =*/ HCData.readStr(dis, remainLength-1);
				//System.out.println("padding:"+padding);
			}
			if(nameResult.length_in_src == 0 && scriptResult.length_in_src<200){
				name = scriptResult.str;
			}
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
	
	
	private static OBackground buildOBackground(OBackground bg)
	{
		bg.picture = new OPicture(bg);

		//bgの場合はピクチャがなくても白で必ず描画しなくてはいけない
		bg.label = MyLabel.getMyBgLabel(bg);
		bg.label.setBounds(0,0,bg.stack.width,bg.stack.height);
		PCARD.pc.mainPane.add(bg.label);
		
		//ピクチャ読み込み(pbm)
		if(bg.bitmapName!=null){
			String path = (bg.stack.file.getParent()+File.separatorChar+bg.bitmapName);
			bg.pict = PictureFile.loadPbm(path);
			if(bg.pict==null){//読み込まれるまで待つ
				for(int i=0; HCData.threadList!=null&&i<HCData.threadList.size(); i++){
					Thread p = HCData.threadList.get(i);
					if(p!=null && p.getName().equals(bg.bitmapName)){
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
						bg.pict = ImageIO.read(new File(path));
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
				bg.pict = ImageIO.read(file);
				//bg.showPict = true;
			} else {
				file=new File(bg.stack.file.getParent()+"/resource/bgp"+bg.id+".jpeg");
				bg.pict = ImageIO.read(file);
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

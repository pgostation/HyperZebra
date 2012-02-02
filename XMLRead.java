import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.swing.JDialog;
import javax.swing.JProgressBar;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;


public class XMLRead {
	//XMLファイル読み込み開始
	public static boolean readToc(String xmlfile, OStack inStack) {
    	//進捗表示を開始
		inStack.barDialog = new JDialog(inStack.pcard);
		inStack.barDialog.setUndecorated(true);
		inStack.bar = new JProgressBar();
		inStack.bar.setStringPainted(true);
		inStack.bar.setString("");
		inStack.bar.setValue(0);
		inStack.barOffset = 0;
		inStack.barDialog.add(inStack.bar);
		inStack.barDialog.setBounds(inStack.pcard.getBounds());
		inStack.barDialog.setVisible(true);
		inStack.bar.paintImmediately(inStack.bar.getBounds());
		inStack.bar.setString("Reading XML...");
		
	    XMLInputFactory factory = XMLInputFactory.newInstance();
	    
	    XMLStreamReader reader = null;
	    BufferedInputStream stream = null;
	    boolean result = false;
	    boolean completeFlag = false;
	    
	    try {
	        stream = new BufferedInputStream(new FileInputStream(xmlfile));
	        
	        //DOCTYPEでエラーになるため読み飛ばす
	        {
	        	StringBuffer s = new StringBuffer(128);
	        	int i=0;
	        	for(; i<256; i++){
		        	int ib = stream.read();
		        	s.append((char)ib);
		        	int off = s.toString().indexOf("DOCTYPE");
		        	if(off>=0&&s.toString().substring(off).indexOf(">")>=0){
		        		break;
		        	}
	        	}
	        	if(i==256){
	                stream.close();
	    	        stream = new BufferedInputStream(new FileInputStream(xmlfile));
	        	}
	        }
	        
	        reader = factory.createXMLStreamReader(stream);
	        System.setProperty("disallowDoctypeDecl", "true");
	        
	        int total = stream.available();
	        
	        while (reader.hasNext()) {
	    		inStack.bar.setValue(100*(total-stream.available())/(total+1));
	    		inStack.bar.paintImmediately(inStack.bar.getBounds());
	    		
	    	    try {
		            int eventType = reader.next();
		            if (eventType == XMLStreamConstants.START_ELEMENT) {
		            	String elm = reader.getLocalName().intern();
		            	if(elm.equals("stackfile") || elm.equals(PCARD.AppName+"stack")){
		            	}else if(elm.equals("stack")){
		            		reader = inStack.readXML(reader);
		            	}else if(elm.equals("background")){
		            		OBackground bg = new OBackground(inStack);
		            		reader = bg.readXML(reader);
		            		inStack.AddNewBg(bg);
		            	}else if(elm.equals("card")){
		            		OCard cd = new OCard(inStack);
		            		reader = cd.readXML(reader);
		            		inStack.AddNewCard(cd.id);
		            		//inStack.cdCacheList.add(cd); //new OCard()の時点で登録されている
		            	}else if(elm.equals("font")){
		            		reader = inStack.readFontXML(reader);
		            	}else if(elm.equals("nextStyleID")){
		            		reader = inStack.readStyleXML(reader);
		            	}else if(elm.equals("styleentry")){
		            		reader = inStack.readStyleXML(reader);
		            	}else if(elm.equals("media") || elm.equals("resource")){
		            		reader = inStack.rsrc.readXML(reader);
		            	}else if(elm.equals("externalcommand") || elm.equals("xcmd")){
		            		reader = inStack.rsrc.readXCMDXML(reader);
		            	}else if(elm.equals("addcolorbackground")){
		            		reader = inStack.rsrc.readAddColorXML(reader, true);
		            	}else if(elm.equals("addcolorcard")){
		            		reader = inStack.rsrc.readAddColorXML(reader, false);
		            	}else if(elm.equals("palette")){
		            		reader = inStack.rsrc.readPlteXML(reader);
		            	}
		            	else{
		            		System.out.println("Local Name: " + reader.getLocalName());
			                System.out.println("Element Text: " + reader.getElementText());
		            	}
		            }
		            if (eventType == XMLStreamConstants.END_ELEMENT) {
				    	String elm2 = reader.getLocalName();
				    	if(elm2.equals("stackfile") || elm2.equals(PCARD.AppName+"stack")){
				    		completeFlag = true;
				    		break;
				    	}
		            }
			    } catch (XMLStreamException ex) {
				    System.err.println(ex.getMessage());
				    throw new XMLStreamException();
		        }
    	    }
	        result = true;
	    } catch (FileNotFoundException ex) {
	        System.err.println(xmlfile + " が見つかりません");
	    } catch (XMLStreamException ex) {
	        System.err.println(xmlfile + " の読み込みに失敗しました");
	        inStack.cantModify = true; //壊れたデータの書き込み禁止
			ex.printStackTrace();
	    } catch (IOException e) {
			e.printStackTrace();
	    } catch (Exception e) {
			e.printStackTrace();
		} finally {
	        if (reader != null) {
	            try {
	                reader.close();
	            } catch (XMLStreamException ex) {}
	        }
	        if (stream != null) {
	            try {
	                stream.close();
	            } catch (IOException ex) {}
	        }
	    }
		//チェック
		if(inStack.firstCard!=0 && inStack.GetCardbyId(inStack.firstCard)==null){
	        System.err.println("Error: First card is not found!");
	        inStack.firstCard = inStack.GetCardbyNum(1).id;
		}else{
			if(result==false){
	    		new GDialog(inStack.pcard, PCARDFrame.pc.intl.getDialogText("Error occured at reading XML file."),null,"OK",null,null);
	    		result = true;//途中までしか読めなくても強制的に開いてみる
			}
			else if(completeFlag==false){
	    		new GDialog(inStack.pcard, PCARDFrame.pc.intl.getDialogText("XML end tag is not found."),null,"OK",null,null);
	    		result = true;//途中までしか読めなくても強制的に開いてみる
			}
		}
		
		inStack.barDialog.remove(inStack.bar);
		inStack.barDialog.dispose();
		
	    return result;
	}

	public static boolean bool(XMLStreamReader reader) throws XMLStreamException {
        while(true){
        	int eventType = reader.next();
	        if (eventType == XMLStreamConstants.START_ELEMENT) {
				if(reader.getLocalName().equals("true")) return true;
				else if(reader.getLocalName().equals("false")) return false;
				else throw new XMLStreamException("not bool.");
	        }
	        if (eventType == XMLStreamConstants.END_ELEMENT) {
	        	throw new XMLStreamException("bool element not foound.");
	        }
        }
	}

	/* StackSmith日本語文字化け対応
	final static char[] macroman = {
			0x00C4,0x00C5,0x00C7,0x00C9,0x00D1,0x00D6,0x00DC,0x00E1,// 80- 87 
			0x00E0,0x00E2,0x00E4,0x00E3,0x00E5,0x00E7,0x00E9,0x00E8,// 88- 8F 
			0x00EA,0x00EB,0x00ED,0x00EC,0x00EE,0x00EF,0x00F1,0x00F3,// 90- 97 
			0x00F2,0x00F4,0x00F6,0x00F5,0x00FA,0x00F9,0x00FB,0x00FC,// 98- 9F 
			0x2020,0x00B0,0x00A2,0x00A3,0x00A7,0x2022,0x00B6,0x00DF,// A0- A7 
			0x00AE,0x00A9,0x2122,0x00B4,0x00A8,0x2260,0x00C6,0x00D8,// A8- AF 
			0x221E,0x00B1,0x2264,0x2265,0x00A5,0x00B5,0x2202,0x2211,// B0- B7 
			0x220F,0x03C0,0x222B,0x00AA,0x00BA,0x03A9,0x00E6,0x00F8,// B8- BF 
			0x00BF,0x00A1,0x00AC,0x221A,0x0192,0x2248,0x2206,0x00AB,// C0- C7 
			0x00BB,0x2026,0x00A0,0x00C0,0x00C3,0x00D5,0x0152,0x0153,// C8- CF 
			0x2013,0x2014,0x201C,0x201D,0x2018,0x2019,0x00F7,0x25CA,// D0- D7 
			0x00FF,0x0178,0x2044,0x20AC,0x2039,0x203A,0xFB01,0xFB02,// D8- DF 
			0x2021,0x00B7,0x201A,0x201E,0x2030,0x00C2,0x00CA,0x00C1,// E0- E7 
			0x00CB,0x00C8,0x00CD,0x00CE,0x00CF,0x00CC,0x00D3,0x00D4,// E8- EF 
			0xF8FF,0x00D2,0x00DA,0x00DB,0x00D9,0x0131,0x02C6,0x02DC,// F0- F7 
			0x00AF,0x02D8,0x02D9,0x02DA,0x00B8,0x02DD,0x02DB,0x02C7,// F8- FF 
	};
	static ByteBuffer toAscii(String src){
		ByteBuffer bb = ByteBuffer.allocate(src.length());
		
		for(int i=0; i<src.length(); i++){
			char c = src.charAt(i);
			if(c>=0&&c<128){
				bb.put((byte)c);
			}
			else
			{
				int j=128;
				for(; j<256; j++){
					if(c == macroman[j-128]){
						byte roman = (byte)j;
						//System.out.println("cnv:("+j+") roman="+Integer.valueOf(roman)+" c="+Integer.valueOf(c));
						bb.put(roman);
						break;
					}
				}
				if(j>=256) bb.put((byte)c);
			}
		}
		return bb;
	}
	
	static String fromSJIS(ByteBuffer bb){
		bb.flip();
		Charset cs = Charset.forName("Shift_JIS");
		CharsetDecoder decoder = cs.newDecoder();
		
		CharBuffer decBuf = null;
		try {
			decBuf = decoder.decode(bb);
		} catch (CharacterCodingException e) {
			//e.printStackTrace();
			//System.out.println(e.getMessage());
			return bb.asCharBuffer().toString();
		}
		
		return decBuf.toString();
	}*/
}

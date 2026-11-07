import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;


public class XMLRead {
	//readToc中に、各オブジェクトのreadXMLが例外を握りつぶして途中で切り上げたか
	static boolean partialRead;

	//各readXMLのcatchから呼ぶ。読み飛ばしたことを記録し、パーサ自体が壊れた
	//(位置情報付きのXMLStreamException)ならtrueを返すので呼び出し側はループを
	//抜ける。以前は例外後もreader.next()を繰り返していたため、壊れたXMLでは
	//無限ループになり得た。
	static boolean readError(Exception ex) {
		partialRead = true;
		System.err.println(ex.getMessage());
		return ex instanceof XMLStreamException && ((XMLStreamException)ex).getLocation()!=null;
	}

	//XMLファイル読み込み開始
	public static boolean readToc(String xmlfile, OStack inStack) {
		partialRead = false;
		//HyperZebraスタックは速く読めるので進捗ウインドウは出さず、待ちカーソルだけにする
		try(StackProgress.Busy busy = StackProgress.busy()){
			return readTocBody(xmlfile, inStack);
		}
	}

	private static boolean readTocBody(String xmlfile, OStack inStack) {
	    XMLInputFactory factory = XmlSafe.input();
	    
	    XMLStreamReader reader = null;
	    BufferedInputStream stream = null;
	    boolean result = false;
	    boolean completeFlag = false;
	    boolean oldWriter = false; //the file has the stackfile DOCTYPE of the first HyperZebra's writer
	    
	    try {
	        byte[] fileBytes = readXmlBytes(xmlfile, inStack);
	        if(fileBytes==null){
	        	//パスワードが要るスタックでキャンセルした/開けなかった: 何も読まずに失敗(書き込み禁止の警告は出さない)
	        	return false;
	        }
	        fileBytes = encodeRawControlBytes(fileBytes);
	        stream = new BufferedInputStream(new java.io.ByteArrayInputStream(fileBytes));
	        
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
	    	        stream = new BufferedInputStream(new java.io.ByteArrayInputStream(fileBytes));
	        	}
	        	else oldWriter = s.indexOf("DTD stackfile")>=0;
	        }
	        
	        reader = XmlText.decodingReader(factory.createXMLStreamReader(stream));
	        
	        while (reader.hasNext()) {
	    		
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
		//封印した絵の鍵を、絵を読む側(ファイルしか持たない)に知らせる
		if(inStack.contentKey!=null && StackProtection.sealsXml(inStack)){
			StackProtection.registerContentKey(new java.io.File(xmlfile).getAbsoluteFile().getParentFile(), inStack.contentKey);
		}
		//The first HyperZebra (2012, the files with the stackfile DOCTYPE) wrote the cards from the second one on and the first
		//card last (HamStar 1.1.hzb: firstCardID 2853 is the last card element; the stack's LIST order is 2853, 3807, …): the first
		//card comes first again, so "cd 1" is the card HyperCard has there
		int cards = inStack.cardIdList.size();
		if(oldWriter && cards>1 && inStack.firstCard!=0 && inStack.cardIdList.get(cards-1)==inStack.firstCard){
			inStack.cardIdList.add(0, inStack.cardIdList.remove(cards-1));
		}
		//チェック
		if(inStack.firstCard!=0 && inStack.GetCardbyId(inStack.firstCard)==null){
	        System.err.println("Error: First card is not found!");
	        if(inStack.GetCardbyNum(1)!=null){
	        	inStack.firstCard = inStack.GetCardbyNum(1).id;
	        }
		}
		inStack.orderBackgroundsAfterXmlRead(); //bkgnd 1 = firstBackgroundID (files from before the order was kept)
		if(!inStack.resourceMapOrder){ inStack.rsrc.sortById(); inStack.resourceMapOrder = true; } //resources of an older file: by ID, as before
		//Home: HyperCard の "User Preferences" と検索パスのカードが無ければ足す(スタックの getHomeInfo が読みに来る)
		if(result && completeFlag && !partialRead && HomeStack.isHomeFile(xmlfile)) HomeStack.addPreferenceCards(inStack);
		//途中までしか読めなかった場合は開くが、読めなかった分を上書き保存して
		//失わないよう書き込み禁止にする(以前はXMLStreamExceptionのときしか
		//cantModifyを立てておらず、欠けた内容で1秒後に保存し直されていた)
		if(result==false || completeFlag==false || partialRead){
			String message = result==false ? "Error occured at reading XML file." :
				completeFlag==false ? "XML end tag is not found." : "Error occured at reading XML file.";
    		//ダイアログはOKまでこのスレッドを止めるので、先にフラグを立てておく
    		inStack.cantModify = true;
    		result = true;//途中までしか読めなくても強制的に開いてみる
    		new GDialog(inStack.pcard, PCARDFrame.pc.intl.getDialogText(message)+"\n"+
    			PCARDFrame.pc.intl.getDialogText("The stack is opened read-only."),null,"OK",null,null);
		}
		
	    return result;
	}

	/** Set while a sealed _stack.xml could not be opened (wrong password / cancelled / damaged). */
	static boolean lockedOut;

	/**
	 * The bytes of a _stack.xml, unsealed when the stack has a password (StackProtection). A Private Access stack asks
	 * for its password here; null when it was not given.
	 */
	//古い HyperZebra(2011年頃)は制御文字(矢印キーの 0x1C-0x1F など)をそのまま XML に書いていた。
	//XML 1.0 では読めないので、今の書き出しと同じ私用領域 U+E000+コード(UTF-8 で EE 80 80+c)へ置き換える。
	//UTF-8 の多バイト列に 0x20 未満のバイトは現れないのでバイト単位で置き換えてよい
	static byte[] encodeRawControlBytes(byte[] data){
		int count = 0;
		for(byte b : data) if(b>=0 && b<0x20 && b!='\t' && b!='\n' && b!='\r') count++;
		if(count==0 || !declaresUtf8(data)) return data;
		byte[] out = new byte[data.length+count*2];
		int o = 0;
		for(byte b : data){
			if(b>=0 && b<0x20 && b!='\t' && b!='\n' && b!='\r'){
				out[o++] = (byte)0xEE; out[o++] = (byte)0x80; out[o++] = (byte)(0x80+b);
			}
			else out[o++] = b;
		}
		return out;
	}

	private static boolean declaresUtf8(byte[] data){
		String head = new String(data, 0, Math.min(data.length, 100), java.nio.charset.StandardCharsets.ISO_8859_1);
		if(!head.startsWith("<?xml")) return true;
		int end = head.indexOf("?>");
		String decl = (end>=0 ? head.substring(0, end) : head).toLowerCase();
		int enc = decl.indexOf("encoding");
		return enc<0 || decl.indexOf("utf-8", enc)>=0;
	}

	static byte[] readXmlBytes(String xmlfile, OStack inStack) throws IOException {
		lockedOut = false;
		byte[] data = java.nio.file.Files.readAllBytes(new java.io.File(xmlfile).toPath());
		if(!StackProtection.isSealed(data)) return data;
		try {
			if(!StackProtection.needsPassword(data)) return StackProtection.unseal(data, null);
			if(inStack.unlockedPassword!=null){
				try { return StackProtection.unseal(data, inStack.unlockedPassword); }
				catch(java.security.GeneralSecurityException wrong){ inStack.unlockedPassword = null; }
			}
			final byte[][] plain = new byte[1][];
			String name = new java.io.File(xmlfile).getAbsoluteFile().getParentFile().getName().replaceFirst("(?i)\\.hzb$", "");
			String pw = StackProtection.askUntilRight(name, typed -> {
				try { plain[0] = StackProtection.unseal(data, typed); return true; }
				catch(java.security.GeneralSecurityException wrong){ return false; }
			});
			if(pw==null){ lockedOut = true; return null; }
			inStack.unlockedPassword = pw;
			return plain[0];
		} catch(java.security.GeneralSecurityException e){
			System.err.println(xmlfile+" could not be unsealed: "+e);
			lockedOut = true;
			return null;
		}
	}

	//<x> <true /> </x> 形式が基本だが、<x>true</x> のようにテキストで書かれた
	//もの(AddColorのtransparent等、以前の書き出し形式)も受け付ける
	/** A part's panel element: the id of its panel (0 for none, or anything that is not an id). */
	static int panelId(String text){
		try{ return Math.max(0, Integer.parseInt(text.trim())); }
		catch(NumberFormatException | NullPointerException e){ return 0; }
	}

	public static boolean bool(XMLStreamReader reader) throws XMLStreamException {
		StringBuilder text = new StringBuilder();
        while(true){
        	int eventType = reader.next();
	        if (eventType == XMLStreamConstants.START_ELEMENT) {
				if(reader.getLocalName().equals("true")) return true;
				else if(reader.getLocalName().equals("false")) return false;
				else throw new XMLStreamException("not bool.");
	        }
	        if (eventType == XMLStreamConstants.CHARACTERS || eventType == XMLStreamConstants.CDATA) {
	        	text.append(reader.getText());
	        }
	        if (eventType == XMLStreamConstants.END_ELEMENT) {
	        	String value = text.toString().trim();
	        	if(value.equalsIgnoreCase("true")) return true;
	        	if(value.equalsIgnoreCase("false")) return false;
	        	throw new XMLStreamException("bool element not found.");
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

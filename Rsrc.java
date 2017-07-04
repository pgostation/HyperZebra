import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;



public class Rsrc {
	HashMap<String,rsrcClass> rsrcIdMap = new HashMap<String,rsrcClass>();
	private HashMap<String,rsrcClass> rsrcNameMap = new HashMap<String,rsrcClass>();
	private HashMap<String,xcmdClass> xcmdNameMap = new HashMap<String,xcmdClass>();
	ArrayList<addcolorClass> addcolorList  = new ArrayList<addcolorClass>();
	ArrayList<PlteClass> plteList  = new ArrayList<PlteClass>();
	OStack ownerstack;
	
	
	public Rsrc(OStack owner){
		this.ownerstack = owner;
	}

	//このスタック内のリソースを検索
	public int getRsrcId1(String name, String type){
		rsrcClass r = rsrcNameMap.get(type+name.toLowerCase());
		if(r!=null) return r.id;
		return 0;
	}

	public String getFileName1(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r.filename;
		return null;
	}

	public String getName1(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r.name;
		return null;
	}
	
	public Point getHotSpot1(int id){
		rsrcClass r = rsrcIdMap.get("cursor"+Integer.toString(id));
		Point p = new Point(r.hotsporleft, r.hotsportop);
		return p;
	}
	
	public rsrcClass getResource1(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		return r;
	}
	
	
	

	//usingしているスタックすべてのリソースを検索
	public int getRsrcIdAll(String name, String type){
		rsrcClass r = rsrcNameMap.get(type+name.toLowerCase());
		if(r!=null) return r.id;
		for(int i=ownerstack.usingStacks.size()-1; i>=0; i--){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcNameMap.get(type+name.toLowerCase());
			if(r!=null) return r.id;
		}
		return 0;
	}

	public String getFilePathAll(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) {
			String parentpath = "";
			if(this.ownerstack.file!=null){
				parentpath = (ownerstack.file.getParent()+File.separatorChar);
			}
			return parentpath+r.filename;
		}
		for(int i=ownerstack.usingStacks.size()-1; i>=0; i--){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get(type+Integer.toString(id));
			if(r!=null) {
				String parentpath = (ownerstack.usingStacks.get(i).file.getParent()+File.separatorChar);
				return parentpath+r.filename;
			}
		}
		return null;
	}

	public String getNameAll(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r.name;
		for(int i=ownerstack.usingStacks.size()-1; i>=0; i--){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcNameMap.get(type+Integer.toString(id));
			if(r!=null) return r.name;
		}
		return null;
	}
	
	public Point getHotSpotAll(int id){
		rsrcClass r = rsrcIdMap.get("cursor"+Integer.toString(id));
		if(r!=null){
			Point p = new Point(r.hotsporleft, r.hotsportop);
			return p;
		}
		for(int i=ownerstack.usingStacks.size()-1; i>=0; i--){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcNameMap.get("cursor"+Integer.toString(id));
			if(r!=null) return new Point(r.hotsporleft, r.hotsportop);
		}
		return null;
	}
	
	public rsrcClass getResourceAll(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r;
		for(int i=ownerstack.usingStacks.size()-1; i>=0; i--){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get(type+Integer.toString(id));
			if(r!=null) return r;
		}
		return null;
	}
	
	
	
	
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
        String idStr = "0", typeStr = "", nameStr = "", fnameStr = "", leftStr = "0", topStr = "0";
        OptionInfo info = null;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("id")){ idStr = reader.getElementText(); }
	            	else if(elm.equals("type")){ typeStr = reader.getElementText(); }
	            	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
	            	else if(elm.equals("file")){ fnameStr = reader.getElementText(); }
	            	else if(elm.equals("left")){ leftStr = reader.getElementText(); }
	            	else if(elm.equals("top")){ topStr = reader.getElementText(); }
	            	else if(elm.equals("hotspot")){  }
	            	else if(elm.equals("fontinfo")){ 
	            		info = new FontInfo();
	            		reader = readFontInfoXML(reader, (FontInfo)info); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamConstants.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("media") || elm.equals("resource")){
	            		addResource(Integer.valueOf(idStr), typeStr, nameStr, fnameStr, leftStr, topStr, info);
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    System.err.println(ex.getMessage());
	        }
	    }
	    return reader;
	}
	
	public XMLStreamReader readFontInfoXML(XMLStreamReader reader, FontInfo info) throws Exception {
        int locCount = 0;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("firstChar")){ info.firstChar = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("lastChar")){ info.lastChar = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("fontType")){ info.fontType = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("widMax")){ info.widMax = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("kernMax")){ info.kernMax = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("nDescent")){ info.nDescent = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("fRectWidth")){ info.fRectWidth = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("fRectHeight")){ info.fRectHeight = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("owTLoc")){ info.owTLoc = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("ascent")){ info.ascent = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("descent")){ info.descent = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("leading")){ info.leading = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("loc")){ 
	            		if(info.locs==null) {
	            			info.locs = new int[info.lastChar-info.firstChar+2];
	            			locCount = 0;
	            		}
	            		info.locs[locCount] = Integer.valueOf(reader.getElementText());
	            		locCount++;
	            	}
	            	else if(elm.equals("offset")){ 
	            		if(info.offsets==null) {
	            			info.offsets = new int[info.lastChar-info.firstChar+2];
	            			locCount = 0;
	            		}
	            		info.offsets[locCount] = Integer.valueOf(reader.getElementText());
	            		locCount++;
	            	}
	            	else if(elm.equals("width")){ 
	            		if(info.widthes==null) {
	            			info.widthes = new int[info.lastChar-info.firstChar+2];
	            			locCount = 0;
	            		}
	            		info.widthes[locCount] = Integer.valueOf(reader.getElementText());
	            		locCount++;
	            	}
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamConstants.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("fontinfo")){
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    System.err.println(ex.getMessage());
	        }
	    }
	    return reader;
	}

	
	// XML保存
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		
		while(it.hasNext()){
        	writer.writeCharacters("\t");
        	rsrcClass rsrc = it.next();
        	
        	rsrc.writeXMLOneRsrc(writer);
		}
	}
	
	class rsrcClass{
		int id;
		String type;
		String name;
		String filename;
		int hotsporleft;
		int hotsportop;
		OptionInfo optionInfo;
		public rsrcClass(int id, String type, String name, String filename, String left, String top, OptionInfo optionInfo){
			this.id = id;
			this.type = type;
			this.name = name;
			this.filename = filename;
			this.hotsporleft = Integer.valueOf(left);
			this.hotsportop = Integer.valueOf(top);
			this.optionInfo = optionInfo;
		}
		

		public void writeXMLOneRsrc(XMLStreamWriter writer) throws XMLStreamException
		{
			writer.writeStartElement("media");
		    writer.writeCharacters("\n\t\t");
		    {
		    	writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(this.id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	writer.writeStartElement("type");
		        writer.writeCharacters(this.type);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	writer.writeStartElement("name");
		        writer.writeCharacters(this.name);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	writer.writeStartElement("file");
		        writer.writeCharacters(this.filename);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t");
		        
		        if(this.type.equals("cursor")){
		        	writer.writeCharacters("\t");
		        	writer.writeStartElement("hotspot");
			        writer.writeCharacters("\n\t\t\t");
			        {
			        	writer.writeStartElement("left");
				        writer.writeCharacters(Integer.toString(this.hotsporleft));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
				        
			        	writer.writeStartElement("top");
				        writer.writeCharacters(Integer.toString(this.hotsportop));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
			        }
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		
		    	FontInfo fontinfo = (FontInfo)this.optionInfo;
		        if(this.type.equals("font") && fontinfo!=null){
		        	writer.writeCharacters("\t");
		        	writer.writeStartElement("fontinfo");
			        writer.writeCharacters("\n\t\t\t");
			        {
			        	
			        	writer.writeStartElement("fontType");
				        writer.writeCharacters(Integer.toString(fontinfo.fontType));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("firstChar");
				        writer.writeCharacters(Integer.toString(fontinfo.firstChar));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("lastChar");
				        writer.writeCharacters(Integer.toString(fontinfo.lastChar));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("widMax");
				        writer.writeCharacters(Integer.toString(fontinfo.widMax));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("kernMax");
				        writer.writeCharacters(Integer.toString(fontinfo.kernMax));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("nDescent");
				        writer.writeCharacters(Integer.toString(fontinfo.nDescent));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("fRectWidth");
				        writer.writeCharacters(Integer.toString(fontinfo.fRectWidth));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("fRectHeight");
				        writer.writeCharacters(Integer.toString(fontinfo.fRectHeight));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("owTLoc");
				        writer.writeCharacters(Integer.toString(fontinfo.owTLoc));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("ascent");
				        writer.writeCharacters(Integer.toString(fontinfo.ascent));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("descent");
				        writer.writeCharacters(Integer.toString(fontinfo.descent));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("leading");
				        writer.writeCharacters(Integer.toString(fontinfo.leading));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
				        for(int j=0; fontinfo.locs!=null&&j<fontinfo.locs.length; j++){
				        	writer.writeStartElement("loc");
					        writer.writeCharacters(Integer.toString(fontinfo.locs[j]));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t");
				        }
				        
				        for(int j=0; fontinfo.offsets!=null&&j<fontinfo.offsets.length; j++){
				        	writer.writeStartElement("offset");
					        writer.writeCharacters(Integer.toString(fontinfo.offsets[j]));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t");
				        }
				        
				        for(int j=0; fontinfo.widthes!=null&&j<fontinfo.widthes.length; j++){
				        	writer.writeStartElement("width");
					        writer.writeCharacters(Integer.toString(fontinfo.widthes[j]));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t");
				        }
			        }
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		    }
		    writer.writeEndElement();
		    writer.writeCharacters("\n");
		}
		
	}
	
	class OptionInfo{
		
	}
	

	public int getNewResourceId(String type){
		return getNewResourceId(type, 1000);
	}
	
	public int getNewResourceId(String type, int baseid){
		int id = baseid;
		while(true){
			if(null == getFilePathAll(id, type) && id>=-32768 && id <= Integer.MAX_VALUE){
				break;
			}
			id++;
			if(id<-32768 || id>=32768) id = 1;;
		}
		return id;
	}
	
	public void addResource(int id, String type, String name, String path){
		rsrcClass rsrc = new rsrcClass(id, type, name, path, "0", "0", null);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
		ownerstack.changed = true;
	}
	
	public void addResource(int id, String type, String name, String filename, String leftStr, String topStr){
		rsrcClass rsrc = new rsrcClass(id, type, name, filename, leftStr, topStr, null);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
		ownerstack.changed = true;
	}
	
	public void addResource(int id, String type, String name, String filename, String leftStr, String topStr, OptionInfo info){
		rsrcClass rsrc = new rsrcClass(id, type, name, filename, leftStr, topStr, info);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
		ownerstack.changed = true;
	}
	
	public void addResource(rsrcClass r){
		rsrcIdMap.put(r.type+r.id, r);
		rsrcNameMap.put(r.type+r.name.toLowerCase(), r);
		ownerstack.changed = true;
	}
	
	public boolean deleteResource(String type, int id){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r==null) return false;
		String FilePath = this.ownerstack.file.getParent()+File.separatorChar+r.filename;
		//(new File(FilePath)).delete();
		(new File(FilePath)).renameTo(new File("resource_trash"+File.separatorChar+r.filename));
		if(null==rsrcIdMap.remove(type+r.id)){
			System.out.println("delete resource error");
		}
		if(null==rsrcNameMap.remove(type+r.name.toLowerCase())){
			System.out.println("delete resource error");
		}
		ownerstack.changed = true;
		return true;
	}

	
	
	public int getRsrcCount(String type){
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		int i=0;
		rsrcClass rsrc;
		while(it.hasNext()){
			rsrc = it.next();
			if(rsrc.type.equals(type)){
				i++;
			}
		}
		return i;
	}
	
	public int getRsrcCountAll(String type){
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		int i=0;
		rsrcClass rsrc;
		while(it.hasNext()){
			rsrc = it.next();
			if(rsrc.type.equals(type)){
				i++;
			}
		}
		for(int j=ownerstack.usingStacks.size()-1; j>=0; j--){
			OStack rsrcstack = ownerstack.usingStacks.get(j);
			it = rsrcstack.rsrc.rsrcIdMap.values().iterator();
			while(it.hasNext()){
				rsrc = it.next();
				if(rsrc.type.equals(type)){
					if((rsrcstack.file.getParent()+File.separatorChar+rsrc.filename)
							.equals(getFilePathAll(rsrc.id, type)))
					{
						i++;
					}
				}
			}
		}
		return i;
	}

	public rsrcClass getRsrcByIndex(String type, int index){
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		int i=0;
		rsrcClass rsrc;
		while(it.hasNext()){
			rsrc = it.next();
			if(rsrc.type.equals(type)){
				if(i==index){
					return rsrc;
				}
				i++;
			}
		}
		return null;
	}
	

	//----------------
	// for icon
	//----------------
	public BufferedImage getImage(int id){
		BufferedImage bi = null;
		String path = this.getFilePathAll(id, "icon");
		/*String path = "";
		if(this.ownerstack.file!=null){
			path = this.ownerstack.file.getParent()+File.separatorChar+filename;
		}else{
			path = filename;
		}*/
		try{
			bi = PictureFile.loadPbm(path);
			if(bi==null){
				bi = javax.imageio.ImageIO.read(new File(path));
			}
			if(bi==null){
				bi = PictureFile.loadPICT(path);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return bi;
	}
	

	//----------------
	// for PICT etc
	//----------------
	public BufferedImage getImage(int id, String type){
		BufferedImage bi = null;
		String path = this.getFilePathAll(id, type);
		/*String path = "";
		path = this.ownerstack.file.getParent()+File.separatorChar+filename;*/
		try{
			bi = PictureFile.loadPbm(path);
			if(bi==null){
				bi = javax.imageio.ImageIO.read(new File(path));
			}
			if(bi==null){
				bi = PictureFile.loadPICT(path);
			}
		} catch (IOException e) {
			//e.printStackTrace();
		}
		
		return bi;
	}
	
	//----------------
	// for FONT
	//----------------
	class FontInfo extends OptionInfo{
		int fontType;
		char firstChar;
		char lastChar;
		int widMax;
		int kernMax;
		int nDescent;
		int fRectWidth;
		int fRectHeight;
		int owTLoc;
		int ascent;
		int descent;
		int leading;
		int[] locs;
		int[] offsets;
		int[] widthes;
	}
	
	public void addFontResource(int id, String type, String name, String path, FontInfo info){
		rsrcClass rsrc = new rsrcClass(id, type, name, path, "0", "0", info);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
	}
	
	//----------------
	// XCMD
	//----------------
	public int getxcmdId(String name, String type){
		xcmdClass r = xcmdNameMap.get(type+name.toLowerCase());
		if(r!=null) return r.id;
		return 0;
	}

	public void addXcmd(xcmdClass xcmd){
		xcmdNameMap.put(xcmd.type+xcmd.name.toLowerCase(), xcmd);
	}
	
	public XMLStreamReader readXCMDXML(XMLStreamReader reader) throws Exception {
        String idStr = "0", typeStr = "", nameStr = "", pathStr = "", platformStr = "", sizeStr = "";
        int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
        	String attr = reader.getAttributeLocalName(i);
        	if(attr.equals("id")){ idStr = reader.getAttributeValue(i); }
        	else if(attr.equals("type")){ typeStr = reader.getAttributeValue(i); }
        	else if(attr.equals("platform")){ platformStr = reader.getAttributeValue(i); }
        	else if(attr.equals("name")){ nameStr = reader.getAttributeValue(i); }
        	else if(attr.equals("size")){ sizeStr = reader.getAttributeValue(i); }
        	else if(attr.equals("file")){ pathStr = reader.getAttributeValue(i); }
        }
        //xcmdList.add(new xcmdClass(idStr, typeStr, nameStr, pathStr, platformStr, sizeStr));
        xcmdClass xcmd = new xcmdClass(idStr, typeStr, nameStr, pathStr, platformStr, sizeStr);
        xcmdNameMap.put(typeStr+nameStr.toLowerCase(), xcmd);
        return reader;
	}

	
	public void writeXcmdXML(XMLStreamWriter writer) throws XMLStreamException {
		Iterator<xcmdClass> it = xcmdNameMap.values().iterator();
		xcmdClass xcmd;
		while(it.hasNext()){
        	writer.writeCharacters("\t");
			xcmd = it.next();
        	writer.writeEmptyElement("externalcommand");
        	writer.writeAttribute("type", xcmd.type);
        	writer.writeAttribute("platform", xcmd.platform);
        	writer.writeAttribute("id", Integer.toString(xcmd.id));
        	writer.writeAttribute("size", Integer.toString(xcmd.size));
        	writer.writeAttribute("name", xcmd.name);
        	writer.writeAttribute("file", xcmd.filename);
	        writer.writeCharacters("\n");
		}
	}
	
	
	static class xcmdClass{
		int id;
		String type; //command or function
		String name;
		String filename;
		String platform;
		int size;
		public xcmdClass(String id, String type, String name, String filename, String platform, String size){
			this.id = Integer.valueOf(id);
			this.type = type;
			this.name = name;
			this.filename = filename;
			this.platform = platform;
			this.size = Integer.valueOf(size);
		}
	}
	

	//-------------
	//AddColor関連
	//-------------
	public XMLStreamReader readAddColorXML(XMLStreamReader reader, boolean isBg) throws Exception {
        String idStr = "0";
        addcolorClass addColorOwner = null;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
		        	if(elm.equals("id")){ 
		        		idStr = reader.getElementText(); 
		                addColorOwner = new addcolorClass(idStr, isBg);
		        	}
		        	else if(elm.equals("addcolorobject")){ reader = readAddColorObjXML(reader, addColorOwner); }
			    	else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("addcolorbackground")){
			    		break;
			    	}
			    	if(elm2.equals("addcolorcard")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    System.err.println(ex.getMessage());
			}
		}
	    addcolorList.add(addColorOwner);
	    return reader;
	}
	

	public XMLStreamReader readAddColorObjXML(XMLStreamReader reader, addcolorClass parent) throws Exception {
        String typeStr = "", nameStr = "";
        boolean transparent = false;
        boolean visible = true;
        Rectangle rect = new Rectangle();
        int id=0,bevel=0,red=0,green=0,blue=0; 
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
		        	if(elm.equals("type")){ typeStr = reader.getElementText(); }
		        	else if(elm.equals("id")){ id = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("top")){ rect.y = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("left")){ rect.x = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("bottom")){ rect.height = Integer.valueOf(reader.getElementText()) - rect.y; }
		        	else if(elm.equals("right")){ rect.width = Integer.valueOf(reader.getElementText()) - rect.x; }
		        	else if(elm.equals("transparent")){ transparent = XMLRead.bool(reader);reader.next(); }
		        	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
		        	else if(elm.equals("visible")){ visible = XMLRead.bool(reader);reader.next(); }
		        	else if(elm.equals("rect")){  }
		        	else if(elm.equals("bevel")){ bevel = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("color")){  }
		        	else if(elm.equals("red")){ red = Integer.valueOf(reader.getElementText())/256; }
		        	else if(elm.equals("green")){ green = Integer.valueOf(reader.getElementText())/256; }
		        	else if(elm.equals("blue")){ blue = Integer.valueOf(reader.getElementText())/256; }
		            else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("addcolorobject")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    System.err.println(ex.getMessage());
			}
		}
        if(typeStr.equals("picture")){
        	parent.addPictObject(nameStr, rect, transparent, visible);
        }
        if(typeStr.equals("rectangle")){
        	parent.addRectObject(rect, bevel, new Color(red, green, blue), visible);
        }
        if(typeStr.equals("button")){
        	parent.addBtnObject(id, bevel, new Color(red,green, blue), visible);
        }
        if(typeStr.equals("field")){
        	parent.addFldObject(id, bevel, new Color(red,green, blue), visible);
        }
	    return reader;
	}
	
	
	public static void setEnabledACObj(int number, boolean enabled){
		//addcolorClassを探す
		ArrayList<addcolorClass> cdList = PCARD.pc.stack.rsrc.addcolorList;
		addcolorClass accd = null;
		for(int i=0; i<cdList.size(); i++){
			if(PCARD.pc.stack.curCard.id == cdList.get(i).id){
				accd = cdList.get(i);
				break;
			}
		}
		if(accd==null) return;
		
		if(number<accd.objList.size()){
			accd.objList.get(number).visible = enabled;
		}
	}
	
	class addcolorClass{
		int id;
		boolean isBg;
		ArrayList<addcolorObjClass> objList  = new ArrayList<addcolorObjClass>();
		
		public addcolorClass(String id, boolean isBg){
			this.id = Integer.valueOf(id);
			this.isBg = isBg;
		}
		
		public void addPictObject(String name, Rectangle rect, boolean transparent, boolean visible){
			objList.add(new PictObject(name, rect, transparent, visible));
		}
		public void addRectObject(Rectangle rect, int bevel, Color color, boolean visible){
			objList.add(new RectObject(rect, bevel, color, visible));
		}
		public void addBtnObject(int id, int bevel, Color color, boolean visible){
			objList.add(new BtnObject(id, bevel, color, visible));
		}
		public void addFldObject(int id, int bevel, Color color, boolean visible){
			objList.add(new FldObject(id, bevel, color, visible));
		}

		public void writeAddColorXMLOne(XMLStreamWriter writer) throws XMLStreamException {
			if(isBg){
				writer.writeStartElement("addcolorbackground");
			}else{
				writer.writeStartElement("addcolorcard");
			}
		    writer.writeCharacters("\n\t\t");
		    {
		    	writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(this.id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	for(int i=0; i<objList.size(); i++){
			    	writer.writeStartElement("addcolorobject");

			    	addcolorObjClass obj = objList.get(i);
			    	PictObject picto = null;
			    	RectObject recto = null;
			    	BtnObject btno = null;
			    	FldObject fldo = null;;
			    	
			    	writer.writeStartElement("type");
			    	if(obj.getClass()==PictObject.class){
			    		writer.writeCharacters("picture");
			    		picto = (PictObject)obj;
			    	}
			    	else if(obj.getClass()==RectObject.class){
			    		writer.writeCharacters("rectangle");
			    		recto = (RectObject)obj;
			    	}
			    	else if(obj.getClass()==BtnObject.class){
			    		writer.writeCharacters("button");
			    		btno = (BtnObject)obj;
			    	}
			    	else if(obj.getClass()==FldObject.class){
			    		writer.writeCharacters("field");
			    		fldo = (FldObject)obj;
			    	}
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t\t");

			        int id = 0;
			        if(btno!=null) id = btno.id;
			        else if(fldo!=null) id = fldo.id;
			        if(id>0){
				    	writer.writeStartElement("id");
				        writer.writeCharacters(Integer.toString(id));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			    	
			        Rectangle rect = null;
			        if(picto!=null) rect = picto.rect;
			        else if(recto!=null) rect = recto.rect;
			        if(rect!=null){
				    	writer.writeStartElement("rect");
				        writer.writeCharacters("\n\t\t\t\t");
				    	{
					    	writer.writeStartElement("left");
					        writer.writeCharacters(Integer.toString(rect.x));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("top");
					        writer.writeCharacters(Integer.toString(rect.y));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("right");
					        writer.writeCharacters(Integer.toString(rect.x+rect.width));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("bottom");
					        writer.writeCharacters(Integer.toString(rect.y+rect.height));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				    	}
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }

			        if(picto!=null){
				    	writer.writeStartElement("name");
				        writer.writeCharacters(picto.name);
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
				        
				    	writer.writeStartElement("transparent");
				        writer.writeCharacters(picto.transparent?"true":"false");
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			        if(picto==null) {
				    	writer.writeStartElement("bevel");
				        writer.writeCharacters(Integer.toString(obj.bevel));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			    	writer.writeStartElement("visible");
			        writer.writeCharacters(" ");
			        writer.writeEmptyElement(Boolean.toString(obj.visible)+" ");
			        writer.writeCharacters(" ");
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t\t");
			        
			        if(picto==null) {
				    	writer.writeStartElement("color");
				        writer.writeCharacters("\n\t\t\t\t");
				    	{
					    	writer.writeStartElement("red");
					        writer.writeCharacters(Integer.toString(obj.color.getRed()*256));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("green");
					        writer.writeCharacters(Integer.toString(obj.color.getGreen()*256));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("blue");
					        writer.writeCharacters(Integer.toString(obj.color.getBlue()*256));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				    	}
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t");
		    	}
		    }
		    writer.writeEndElement();
		    writer.writeCharacters("\n");
		}
	}
	
	class addcolorObjClass{
		int bevel;
		Color color;
		boolean visible;
		
		public addcolorObjClass(int bevel, Color color, boolean visible){
			this.bevel = bevel;
			this.color = color;
			this.visible = visible;
		}
	}
	
	class PictObject extends addcolorObjClass {
		String name;
		boolean transparent;
		Rectangle rect;

		public PictObject(String name, Rectangle rect, boolean transparent, boolean visible) {
			super(0, null, visible);
			this.name = name;
			this.rect = rect;
			this.transparent = transparent;
		}
	}
	
	class RectObject extends addcolorObjClass {
		Rectangle rect;

		public RectObject(Rectangle rect, int bevel, Color color, boolean visible) {
			super(bevel, color, visible);
			this.rect = rect;
		}
	}
	
	class BtnObject extends addcolorObjClass {
		int id;

		public BtnObject(int id, int bevel, Color color, boolean visible) {
			super(bevel, color, visible);
			this.id = id;
		}
	}
	
	class FldObject extends addcolorObjClass {
		int id;

		public FldObject(int id, int bevel, Color color, boolean visible) {
			super(bevel, color, visible);
			this.id = id;
		}
	}
	

	// XML保存
	public void writeAddColorXML(XMLStreamWriter writer) throws XMLStreamException {
		ArrayList<addcolorClass> cdList = PCARD.pc.stack.rsrc.addcolorList;
		for(int i=0; i<cdList.size(); i++){
			cdList.get(i).writeAddColorXMLOne(writer);
		}
	}
	
	
	
	//-------------
	//PLTE関連
	//-------------
	public XMLStreamReader readPlteXML(XMLStreamReader reader) throws Exception {
        int id = 0;
        String name="";
        int WindowDef = 0;
        boolean clearHilite = true;
        int btnType=0;
        int pictId=0;
        Point pictHV = new Point(0,0);
        PlteClass plteOwner = null;
        
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("id")){ 
		        		id = Integer.valueOf(reader.getElementText()); 
		        	}
		        	else if(elm.equals("name")){ 
		        		name = reader.getElementText(); 
		        	}
		        	else if(elm.equals("windowdefinition")){ 
		        		WindowDef = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("clearhilite")){
		        		clearHilite = XMLRead.bool(reader);reader.next();
		        	}
		        	else if(elm.equals("buttontype")){ 
		        		String typeStr = reader.getElementText();
		        		if(typeStr.equals("solid")){
		        			btnType = 0;
		        		}else if(typeStr.equals("frame")){
		        			btnType = 1;
		        		}
		        	}
		        	else if(elm.equals("pictureid")){ 
		        		pictId = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("horizontaloffset")){ 
		        		pictHV.x = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("verticaloffset")){ 
		        		pictHV.y = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("paletteobject")){
		                if(plteOwner==null){
		                	plteOwner = new PlteClass(id, name, WindowDef, clearHilite, btnType, pictId, pictHV);
		                }
		        		reader = readPlteObjXML(reader, plteOwner); }
			    	else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("palette")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    System.err.println(ex.getMessage());
			}
		}
	    plteList.add(plteOwner);
	    return reader;
	}
	

	public XMLStreamReader readPlteObjXML(XMLStreamReader reader, PlteClass parent) throws Exception {
        String message = "";
        Rectangle rect = new Rectangle();
        //int id=0; 
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
		        	if(elm.equals("id")){ /*id =*/ Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("top")){ rect.y = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("left")){ rect.x = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("bottom")){ rect.height = Integer.valueOf(reader.getElementText()) - rect.y; }
		        	else if(elm.equals("right")){ rect.width = Integer.valueOf(reader.getElementText()) - rect.x; }
		        	else if(elm.equals("message")){ message = reader.getElementText(); }
		        	else if(elm.equals("rect")){  }else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("paletteobject")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    System.err.println(ex.getMessage());
			}
		}

        parent.addRectBtn(rect, message);
        	
	    return reader;
	}
	
	
	class PlteClass{
		int id;
		String name;
		int windowDef;
		boolean clearHilite;
		int btnType;
		int pictId;
		Point pictHV;
		
		ArrayList<plteBtnObject> objList  = new ArrayList<plteBtnObject>();
		
		public PlteClass(int id, String name, int windowDef, boolean clearHilite, int btnType, int pictId, Point pictHV){
			this.id = id;
			this.name = name;
			this.windowDef = windowDef;
			this.clearHilite = clearHilite;
			this.btnType = btnType;
			this.pictId = pictId;
			this.pictHV = pictHV;
		}
		
		public void addRectBtn(Rectangle rect, String message){
			objList.add(new plteBtnObject(rect, message));
		}

		public void writePlteXMLOne(XMLStreamWriter writer) throws XMLStreamException {
			writer.writeStartElement("palette");
		    writer.writeCharacters("\n\t\t");
		    {
		    	writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(this.id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("name");
		        writer.writeCharacters(this.name);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("windowdefinition");
		        writer.writeCharacters(Integer.toString(this.windowDef));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("clearhilite");
		        writer.writeCharacters(" ");
		        writer.writeEmptyElement(Boolean.toString(this.clearHilite)+" ");
		        writer.writeCharacters(" ");
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("pictureid");
		        writer.writeCharacters(Integer.toString(this.pictId));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("verticaloffset");
		        writer.writeCharacters(Integer.toString(this.pictHV.y));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("horizontaloffset");
		        writer.writeCharacters(Integer.toString(this.pictHV.x));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	for(int i=0; i<objList.size(); i++){
			    	writer.writeStartElement("paletteobject");
			        writer.writeCharacters("\n\t\t\t");
			        
			    	plteBtnObject obj = objList.get(i);

			        {
				    	writer.writeStartElement("id");
				        writer.writeCharacters(Integer.toString(i+1));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			    	
			        Rectangle rect = obj.rect;
			        
			        {
				    	writer.writeStartElement("rect");
				        writer.writeCharacters("\n\t\t\t\t");
				    	{
					    	writer.writeStartElement("left");
					        writer.writeCharacters(Integer.toString(rect.x));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("top");
					        writer.writeCharacters(Integer.toString(rect.y));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("right");
					        writer.writeCharacters(Integer.toString(rect.x+rect.width));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("bottom");
					        writer.writeCharacters(Integer.toString(rect.y+rect.height));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				    	}
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }

			        {
				    	writer.writeStartElement("message");
				        writer.writeCharacters(obj.message);
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t");
		    	}
		    }
		    writer.writeEndElement();
		    writer.writeCharacters("\n");
		}
	}
	

	// XML保存
	public void writePLTEXML(XMLStreamWriter writer) throws XMLStreamException {
		ArrayList<PlteClass> plteList = PCARD.pc.stack.rsrc.plteList;
		for(int i=0; i<plteList.size(); i++){
			plteList.get(i).writePlteXMLOne(writer);
		}
	}
	
	
	class plteBtnObject {
		Rectangle rect;
		String message;

		public plteBtnObject(Rectangle rect, String message) {
			this.rect = rect;
			this.message = message;
		}
	}
}

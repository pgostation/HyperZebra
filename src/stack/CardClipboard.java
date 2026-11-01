import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * HyperCard's Copy Card / Paste Card: the card (its parts, script, picture, paint layers, shapes and the text of its
 * background fields) is kept as the same XML the stack file holds and pasted after the current card under a new id, its
 * picture files copied to the new card's names. Scripts duplicate template cards this way (If Monks Had Macs "Journal":
 * go last cd of this bg / doMenu "Copy Card" / doMenu "Paste Card"). The picture files are read at the copy: Cut Card deletes
 * the card and its files before the paste, and a card painted on after Copy Card pastes as it was copied.
 */
final class CardClipboard {
	private static String xml;
	private static File sourceFolder;
	private static OStack sourceStack;
	/** The copied card's picture and layer files by their names in the source stack's folder. */
	private static final java.util.Map<String,byte[]> files = new java.util.HashMap<String,byte[]>();

	private CardClipboard(){}

	static boolean hasCard(){ return xml!=null; }

	static void copy(OCard cd) throws xTalkException {
		if(cd==null) throw new xTalkException("No card to copy");
		try{
			StringWriter out = new StringWriter();
			XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
			cd.writeXML(writer);
			writer.flush();
			xml = XmlText.encodeControlChars(out.toString()); // binary in a field (MACBOY Organiser's cards): not valid XML 1.0
		}catch(Exception e){
			throw new xTalkException("Cannot copy card: {0}", e.getMessage());
		}
		sourceStack = cd.stack;
		sourceFolder = cd.stack!=null && cd.stack.file!=null ? cd.stack.file.getParentFile() : null;
		files.clear();
		if(sourceFolder==null) return;
		if(cd.bitmapName!=null && !cd.bitmapName.isEmpty()) keep(cd.bitmapName);
		if(cd.layers!=null) for(PaintLayer l : cd.layers) if(l.fileName!=null) keep(l.fileName);
	}

	private static void keep(String name){
		File f = new File(sourceFolder, name);
		if(!f.isFile()) return;
		try{ files.put(name, Files.readAllBytes(f.toPath())); }catch(IOException e){}
	}

	/** Pastes the copied card after {@code after} in its stack; answers the new card (not yet shown). */
	static OCard paste(OCard after) throws xTalkException {
		if(xml==null) throw new xTalkException("No card to paste");
		if(after==null || after.stack==null) throw new xTalkException("No destination to paste into");
		OStack st = after.stack;
		OCard cd = new OCard(st);
		try{
			XMLStreamReader reader = XmlText.decodingReader(XMLInputFactory.newInstance().createXMLStreamReader(new StringReader(xml)));
			while(reader.hasNext()){
				if(reader.next()==XMLStreamReader.START_ELEMENT && reader.getLocalName().equals("card")) break;
			}
			cd.readXML(reader);
		}catch(Exception e){
			st.cdCacheList.remove(cd);
			throw new xTalkException("Cannot paste card: {0}", e.getMessage());
		}
		cd.id = st.newCardOrBgId();
		boolean bgHere = false;
		for(OBackground bg : st.bgCacheList) if(bg.id==cd.bgid) bgHere = true;
		if(!bgHere){
			//another stack's card: it goes on the background of the card it follows (HyperCard would copy the background too)
			cd.bgid = after.bgid;
			cd.bgfldList.clear();
			cd.bgbtnList.clear();
		}
		File folder = st.file!=null ? st.file.getParentFile() : null;
		//pictures of a protected stack are sealed with its own key: they are copied only within the same stack
		boolean copyFiles = folder!=null && sourceFolder!=null && (sourceStack==st ||
			(!StackProtection.sealsXml(st) && (sourceStack==null || !StackProtection.sealsXml(sourceStack))));
		if(cd.bitmapName!=null && !cd.bitmapName.isEmpty()){
			String ext = cd.bitmapName.contains(".") ? cd.bitmapName.substring(cd.bitmapName.lastIndexOf('.')) : "";
			String name = "BMAP_"+cd.id+ext;
			cd.bitmapName = copyFiles && copyFile(cd.bitmapName, new File(folder, name)) ? name : null;
		}
		if(cd.layers!=null){
			int n = 1;
			for(PaintLayer l : cd.layers){
				if(l.fileName==null) continue;
				String name = "BMAP_"+cd.id+"_L"+(n++)+".png";
				l.fileName = copyFiles && copyFile(l.fileName, new File(folder, name)) ? name : null;
			}
		}
		cd.changed = true;
		st.AddNewCard(st.GetNumberof(after), cd.id);
		return cd;
	}

	private static boolean copyFile(String from, File to){
		byte[] data = files.get(from);
		if(data==null) return false;
		try{
			Files.write(to.toPath(), data);
			PictureCache.invalidate(to);
			return true;
		}catch(IOException e){
			return false;
		}
	}
}

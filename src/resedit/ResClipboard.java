import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * Copy and Paste of resources between the resource editor's windows, also of different stacks (as resources go from one file
 * window to another in a resource editor): Copy puts a {@code <resourceclips>} list on the system clipboard and copies the
 * resources' files into the clipboard folder of the state directory, Paste adds them to a session's working copy (written to
 * the stack at Save). XCMD/XFCN (and the PowerPC xcmd/xfcn) go as {@code <external>} entries.
 */
class ResClipboard {
	/** One resource on the clipboard: its type (the editor's: ICON, SND, XCMD…), ID, name and the copy of its file. */
	static final class Clip {
		String type, name = "", file = "", left = "0", top = "0";
		int id, attributes;
		boolean external;
		Rsrc.OptionInfo info;
	}

	/** A pasted resource: the type and the ID it got. */
	record Pasted(String type, int id) {}

	static File directory(){
		File dir = new File(AppPaths.stateDirectory(), "resource_clipboard");
		dir.mkdirs();
		return dir;
	}

	/** Copies resources ({@code resources}) and externals ({@code externals}) of {@code rsrc} to the clipboard. */
	static void copy(Rsrc rsrc, List<Rsrc.rsrcClass> resources, List<Rsrc.xcmdClass> externals){
		String text = write(rsrc, resources, externals);
		if(text==null) return;
		StringSelection ss = new StringSelection(text);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(ss, ss);
	}

	/** The clipboard text for those resources, their files copied into the clipboard folder; null when none has a file. */
	static String write(Rsrc rsrc, List<Rsrc.rsrcClass> resources, List<Rsrc.xcmdClass> externals){
		if(resources.isEmpty() && externals.isEmpty()) return null;
		File dir = directory();
		File[] old = dir.listFiles();
		if(old!=null) for(File f : old) f.delete();
		try {
			StringWriter out = new StringWriter();
			XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
			writer.writeStartElement("resourceclips");
			writer.writeCharacters("\n\t");
			int n = 0;
			for(Rsrc.rsrcClass r : resources){
				String path = rsrc.getFilePathAll(r.id, r.type);
				if(path==null || !new File(path).isFile()) continue;
				String clipName = (n++)+"_"+new File(r.filename).getName();
				ResEdit.copyFile(new File(path), new File(dir, clipName));
				Rsrc.rsrcClass clip = rsrc.new rsrcClass(r.id, r.type, r.name, clipName, Integer.toString(r.hotsporleft),
					Integer.toString(r.hotsportop), r.optionInfo);
				//the Resource Manager attributes (sysHeap, locked...) go with the copy: SendAppleEvent's PROC needs its sysHeap
				clip.attributes = r.attributes;
				clip.writeXMLOneRsrc(writer);
				writer.writeCharacters("\n\t");
			}
			for(Rsrc.xcmdClass x : externals){
				String path = rsrc.externalPath(x);
				if(path==null || !new File(path).isFile()) continue;
				String clipName = (n++)+"_"+new File(x.filename).getName();
				ResEdit.copyFile(new File(path), new File(dir, clipName));
				writer.writeStartElement("external");
				element(writer, "type", Rsrc.externalMacType(x));
				element(writer, "id", Integer.toString(x.id));
				element(writer, "name", x.name);
				element(writer, "file", clipName);
				writer.writeEndElement();
				writer.writeCharacters("\n\t");
			}
			writer.writeEndElement();
			writer.close();
			return n==0 ? null : out.toString();
		} catch(Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private static void element(XMLStreamWriter writer, String name, String text) throws Exception {
		writer.writeStartElement(name);
		writer.writeCharacters(text);
		writer.writeEndElement();
	}

	/** Whether the clipboard holds resources copied in the resource editor. */
	static boolean hasResources(){
		String s = clipboardText();
		return s!=null && s.startsWith("<resourceclips>");
	}

	private static String clipboardText(){
		Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
		try {
			if(!clip.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
			return (String)clip.getData(DataFlavor.stringFlavor);
		} catch(Exception e) {
			return null;
		}
	}

	/**
	 * Pastes the clipboard's resources into {@code rsrc} (a working copy). Resources whose ID (or, for an external, name) a
	 * resource of the stack already has: asks once whether to replace those or to give the pasted ones unused IDs (an
	 * external of a name already there is then left out: HyperCard calls externals by name). Returns what was pasted; empty
	 * when nothing was on the clipboard or the user cancelled.
	 */
	static List<Pasted> paste(Frame owner, Rsrc rsrc){
		ArrayList<Pasted> pasted = new ArrayList<Pasted>();
		String text = clipboardText();
		if(text==null || !text.startsWith("<resourceclips>")) return pasted;
		List<Clip> clips = read(text, rsrc);
		if(clips.isEmpty()) return pasted;

		int conflicts = conflicts(rsrc, clips);
		boolean replace = false;
		if(conflicts>0){
			String cancel = ResEdit.dialogText("Cancel");
			String unique = ResEdit.dialogText("Unique ID");
			String replaceText = ResEdit.dialogText("Replace");
			String message = ResEdit.dialogText("%1 of the pasted resources have the same ID or name as resources already in this stack.")
				.replace("%1", Integer.toString(conflicts));
			new GDialog(owner, message, null, cancel, unique, replaceText);
			if(GDialog.clicked.equals(cancel) || GDialog.clicked.isEmpty()) return pasted;
			replace = GDialog.clicked.equals(replaceText);
		}
		return paste(rsrc, clips, replace);
	}

	/** How many of the clipboard text's resources clash with resources of {@code rsrc} (by ID, an external also by name). */
	static int conflicts(Rsrc rsrc, String text){
		return conflicts(rsrc, read(text, rsrc));
	}

	private static int conflicts(Rsrc rsrc, List<Clip> clips){
		int n = 0;
		for(Clip c : clips) if(conflicts(rsrc, c)) n++;
		return n;
	}

	/** Pastes the clipboard text's resources: those that clash replace the stack's or (not {@code replace}) get unused IDs. */
	static List<Pasted> paste(Rsrc rsrc, String text, boolean replace){
		return paste(rsrc, read(text, rsrc), replace);
	}

	private static List<Pasted> paste(Rsrc rsrc, List<Clip> clips, boolean replace){
		ArrayList<Pasted> pasted = new ArrayList<Pasted>();
		File dir = directory();
		for(Clip c : clips){
			File source = new File(dir, c.file);
			if(!source.isFile()) source = AppPaths.resourceTrashFile(c.file); //copied by an earlier version (into the resource trash)
			if(!source.isFile()) continue;
			try {
				Pasted p = c.external ? pasteExternal(rsrc, c, source, replace) : pasteResource(rsrc, c, source, replace);
				if(p!=null) pasted.add(p);
			} catch(IOException e) {
				e.printStackTrace();
			}
		}
		return pasted;
	}

	private static boolean conflicts(Rsrc rsrc, Clip c){
		if(c.external) return rsrc.getExternal(c.type, c.id)!=null || rsrc.getExternalNamed(c.type, c.name)!=null;
		return rsrc.getResource1(c.id, c.type)!=null;
	}

	private static Pasted pasteResource(Rsrc rsrc, Clip c, File source, boolean replace) throws IOException {
		int id = c.id;
		if(rsrc.getResource1(id, c.type)!=null){
			if(replace) rsrc.deleteResource(c.type, id);
			else id = rsrc.getNewResourceId(c.type, id);
		}
		String ext = extensionOf(c.file);
		String filename = rsrc.freeFileName(safeFileName(c.type)+"_"+id+ext, null);
		ResEdit.copyFile(source, rsrc.fileToWrite(filename));
		rsrc.addResource(id, c.type, c.name, filename, c.left, c.top, c.info);
		Rsrc.rsrcClass added = rsrc.getResource1(id, c.type);
		if(added!=null) added.attributes = c.attributes;
		return new Pasted(c.type, id);
	}

	private static Pasted pasteExternal(Rsrc rsrc, Clip c, File source, boolean replace) throws IOException {
		Rsrc.xcmdClass sameName = rsrc.getExternalNamed(c.type, c.name);
		if(sameName!=null){
			if(!replace) return null;
			rsrc.deleteExternal(sameName);
		}
		int id = c.id;
		Rsrc.xcmdClass sameId = rsrc.getExternal(c.type, id);
		if(sameId!=null){
			if(replace) rsrc.deleteExternal(sameId);
			else id = newExternalId(rsrc, c.type, id);
		}
		String platform = Character.isUpperCase(c.type.charAt(0)) ? "68k" : "ppc";
		//the name the stack import gives an external's file (HCResource): XCMD_68k_<id>_<name>.data
		String filename = rsrc.freeFileName(c.type+"_"+platform+"_"+id+"_"+safeFileName(c.name)+".data", null);
		ResEdit.copyFile(source, rsrc.fileToWrite(filename));
		rsrc.putExternal(c.type, id, c.name, filename, (int)source.length());
		return new Pasted(c.type, id);
	}

	/** An ID no external of that type has, from {@code base} up (as a resource editor's Unique ID). */
	static int newExternalId(Rsrc rsrc, String macType, int base){
		int id = base;
		while(rsrc.getExternal(macType, id)!=null){
			id++;
			if(id>=32768) id = 128;
		}
		return id;
	}

	private static String extensionOf(String filename){
		int dot = filename.lastIndexOf('.');
		return dot>=0 ? filename.substring(dot) : "";
	}

	/** A resource name or type as part of a file name: no path separators or characters file systems refuse. */
	static String safeFileName(String s){
		return s.replaceAll("[/\\\\:*?\"<>|\\x00-\\x1F]", "_");
	}

	private static List<Clip> read(String text, Rsrc rsrc){
		ArrayList<Clip> clips = new ArrayList<Clip>();
		try {
			XMLStreamReader reader = XmlSafe.input().createXMLStreamReader(
				new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "UTF-8");
			Clip c = null;
			while(reader.hasNext()){
				int event = reader.next();
				if(event==XMLStreamConstants.START_ELEMENT){
					String elm = reader.getLocalName();
					if(elm.equals("media") || elm.equals("external")){
						c = new Clip();
						c.external = elm.equals("external");
					}
					else if(c==null) continue;
					else if(elm.equals("id")) c.id = Integer.parseInt(reader.getElementText().trim());
					else if(elm.equals("type")) c.type = reader.getElementText();
					else if(elm.equals("name")) c.name = reader.getElementText();
					else if(elm.equals("file")) c.file = reader.getElementText();
					else if(elm.equals("left")) c.left = reader.getElementText();
					else if(elm.equals("top")) c.top = reader.getElementText();
					else if(elm.equals("attrs")) c.attributes = Integer.parseInt(reader.getElementText().trim());
					else if(elm.equals("fontinfo")){
						Rsrc.FontInfo info = rsrc.new FontInfo();
						reader = rsrc.readFontInfoXML(reader, info);
						c.info = info;
					}
				}
				else if(event==XMLStreamConstants.END_ELEMENT){
					String elm = reader.getLocalName();
					if(c!=null && (elm.equals("media") || elm.equals("external"))){
						if(c.type!=null && !c.file.isEmpty() && (!c.external || Rsrc.isExternalMacType(c.type))) clips.add(c);
						c = null;
					}
				}
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		return clips;
	}
}

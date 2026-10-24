import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * An object script compiled by an OSA scripting component: HyperCard 2.2 and later let an object's scripting language be
 * AppleScript. In the stack file such a script is its source text (empty for the scripts HyperCard compiled), a NUL, then
 * the compiled record: 'WOSA', the header size (32), the data size, the component subtype ('ascr' = AppleScript), the
 * storage type ('scpt'), 12 reserved bytes and the compiled script ("FasdUAS 1.101.10…"). For a part record the source
 * text follows the one padding byte after the name, as a HyperTalk script does.
 *
 * <p>The record is kept byte for byte (in the .hzb as base64, so copy/paste and Save As carry it) and written back on a
 * HyperCard export. HyperZebra never runs it: the scripts are Apple events for a classic Finder (restart, shut down, erase
 * disk…), and there is no AppleScript to run them in. Real HyperCard J-2.3 without the AppleScript extension behaves
 * the same way (checked in the 68K VM): the object has no handlers, so a message passes on through it ({@code send
 * "GetVolumes (0)" to cd btn "AppleScript"} gives 「“GetVolumes”がわかりません。」, a {@code send "mouseUp"} does
 * nothing), {@code the script} is empty, {@code the scriptingLanguage} is the component's subtype ("ascr"; "AppleScript"
 * when AppleScript is installed), {@code set the script} keeps the language, and {@code set the scriptingLanguage to
 * "HyperTalk"} makes it a HyperTalk script again. Its script editor refuses to open (「スクリプトを編集できませんでした。
 * AppleScript が見当たりません。」); HyperZebra shows a read-only description instead.
 */
final class OsaScript {
	static final byte[] MAGIC = {'W','O','S','A'};
	static final int HEADER_SIZE = 32;

	private final byte[] data;

	OsaScript(byte[] data){
		this.data = data.clone();
	}

	/** The record exactly as in the stack file, from 'WOSA' to the end of the compiled data. */
	byte[] bytes(){ return data.clone(); }
	int length(){ return data.length; }

	/** The component subtype, "ascr" for AppleScript. */
	String subtype(){ return fourCC(12); }

	/** The value of {@code the scriptingLanguage}: the component's name when HyperZebra knows it, else its subtype. */
	String languageName(){
		String subtype = subtype();
		return subtype.equals("ascr") ? "AppleScript" : subtype.trim();
	}

	/** The size of the compiled script after the header. */
	int compiledSize(){
		int header = readInt(data, 4);
		return header>=16 && header<=data.length ? data.length-header : data.length;
	}

	private String fourCC(int offset){
		if(offset+4>data.length) return "";
		return new String(data, offset, 4, StandardCharsets.ISO_8859_1);
	}

	private static int readInt(byte[] b, int offset){
		return (b[offset]&0xFF)<<24 | (b[offset+1]&0xFF)<<16 | (b[offset+2]&0xFF)<<8 | (b[offset+3]&0xFF);
	}

	/** Whether {@code b[from..end)} starts with the 'WOSA' record tag. */
	static boolean startsAt(byte[] b, int from, int end){
		if(from<0 || from+MAGIC.length>end) return false;
		for(int i=0; i<MAGIC.length; i++) if(b[from+i]!=MAGIC[i]) return false;
		return true;
	}

	/**
	 * The record at {@code b[from..end)}, or null when there is none. Its length is the header size plus the data size
	 * when they fit (what follows is the record's or block's padding), else everything up to {@code end}.
	 */
	static OsaScript parse(byte[] b, int from, int end){
		if(!startsAt(b, from, end)) return null;
		int available = end-from;
		int length = available;
		if(available>=12){
			long header = readInt(b, from+4)&0xFFFFFFFFL, size = readInt(b, from+8)&0xFFFFFFFFL;
			if(header>=16 && header+size<=available) length = (int)(header+size);
		}
		return new OsaScript(java.util.Arrays.copyOfRange(b, from, from+length));
	}

	/** {@code <osaScript language="ascr">base64</osaScript>}, written after the object's {@code <script>}. */
	void writeXML(XMLStreamWriter writer, String indentAfter) throws XMLStreamException {
		writer.writeStartElement("osaScript");
		writer.writeAttribute("language", subtype());
		writer.writeCharacters(Base64.getEncoder().encodeToString(data));
		writer.writeEndElement();
		writer.writeCharacters(indentAfter);
	}

	/** Reads the element written by {@link #writeXML}; null when its content is not a WOSA record. */
	static OsaScript readXML(XMLStreamReader reader) throws XMLStreamException {
		String text = reader.getElementText();
		byte[] b;
		try{
			b = Base64.getMimeDecoder().decode(text.trim());
		}catch(IllegalArgumentException e){
			return null;
		}
		return startsAt(b, 0, b.length) ? new OsaScript(b) : null;
	}

	/** The read-only text the script editor shows for an object with a compiled OSA script. */
	static String placeholder(OObject obj){
		OsaScript osa = obj.osaScript;
		StringBuilder text = new StringBuilder();
		text.append(International.t("-- This object's script is compiled {0} (OSA '{1}', {2} bytes).\n"
			+"-- HyperZebra does not run {0} object scripts: the object is treated as having\n"
			+"-- no handlers and messages pass on through it (as in HyperCard without AppleScript).\n"
			+"-- The compiled data is kept unchanged and is written back when the stack is exported to HyperCard.\n"
			+"-- To rewrite it in HyperTalk, run this in the message box, then edit the script:\n"
			+"--   set the scriptingLanguage of {3} to \"HyperTalk\"",
			osa.languageName(), osa.subtype(), osa.compiledSize(), obj.getShortName()));
		String source = obj.scriptList==null ? "" : String.join("\n", obj.scriptList);
		if(!source.isEmpty()){
			text.append("\n--\n").append(International.t("-- Stored source text:")).append('\n').append(source);
		}
		return text.toString();
	}

	/** The object on the message path from {@code target} (the target and the objects behind it) with an OSA script. */
	static OObject onPath(OObject target){
		OObject o = target;
		for(int guard=0; o!=null && guard<16; guard++){
			if(o.osaScript!=null) return o;
			o = TTalk.nextInPath(o);
		}
		OObject card = PCARD.pc==null || PCARD.pc.stack==null ? null : PCARD.pc.stack.curCard;
		for(int guard=0; card!=null && guard<16; guard++){
			if(card.osaScript!=null) return card;
			card = TTalk.nextInPath(card);
		}
		return null;
	}

	/**
	 * The error for a message no handler took: HyperCard's 「“X”がわかりません」, plus why when an object on the path
	 * has an OSA script that may have been meant to handle it.
	 */
	static String cantUnderstand(String message, OObject target){
		OObject osaObject = onPath(target);
		if(osaObject==null) return International.t("Cannot interpret {0}", message);
		return International.t("Cannot interpret {0} (the script of {1} is {2}. HyperZebra does not run AppleScript object scripts / AppleScript object scripts are not supported)",
			message, osaObject.getShortName(), osaObject.osaScript.languageName());
	}
}

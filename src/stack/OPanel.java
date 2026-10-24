import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * A panel: an object of a card or background, like a button, that groups buttons, fields and shapes of the same card or
 * background. It has an id (never reused on its card or background), a number (its place among the panels there), a name, its
 * own visible, a script, contents (put … into panel "P") and named values (the data "score" of panel "P"). It is made with
 * Objects > New Panel or create panel and stays, even with no members, until deleted. A part belongs to it by the panel's id
 * (OObject.panelId), so renaming the panel leaves the members alone.
 * <p>
 * A hidden panel hides its members without changing their own visible (OObject.isShown). Messages a member does not handle go
 * to its panel before the card: button (field, shape) → panel → card → background → stack.
 */
public class OPanel extends OObject {
	final OCardBase owner;
	/** The named values (the data "k" of panel): keys ignore case and keep their first spelling; an empty value is no key. */
	LinkedHashMap<String,String> values = new LinkedHashMap<String,String>();

	OPanel(OCardBase owner, int id, String name){
		this.owner = owner;
		this.parent = owner;
		this.id = id;
		this.name = name==null ? "" : name;
		this.objectType = "panel";
		this.scriptList = new ArrayList<String>();
	}

	private void changed(){ owner.changed = true; }

	/** Its place among the panels of its card or background (panel 1 is the first made). */
	int getNumber(){ return owner.panelList.indexOf(this)+1; }

	@Override public void setText(String value){
		super.setText(value==null ? "" : value);
		changed();
	}
	@Override public void setScript(String str){
		super.setScript(str==null ? "" : str);
		changed();
	}
	String getScriptText(){ return String.join("\n", scriptList); }

	/** set the visible of panel: the panel's own; its members keep theirs and are shown only while the panel is too. */
	@Override public void setVisible(boolean visible){
		if(getVisible()==visible) return;
		super.setVisible(visible);
		ArrayList<OObject> parts = members();
		PCARD.batchOnEDT(() -> { for(OObject part : parts) part.applyShown(); });
	}

	private String key(String key){
		for(String k : values.keySet()) if(k.equalsIgnoreCase(key)) return k;
		return null;
	}
	String getData(String key){
		String k = key(key);
		return k==null ? "" : values.get(k);
	}
	/** Setting a value to empty removes the key. */
	void setData(String key, String value) throws xTalkException {
		if(key==null || key.isEmpty()) throw new xTalkException("Panel Data key is empty");
		putData(key, value);
		changed();
	}
	private void putData(String key, String value){
		String k = key(key);
		if(value==null || value.isEmpty()){ if(k!=null) values.remove(k); return; }
		values.put(k==null ? key : k, value);
	}
	/** the data of panel: all the named values as a dictionary (empty when there are none). */
	String allData() throws xTalkException {
		if(values.isEmpty()) return "";
		TalkCollection dictionary = TalkCollection.dictionary();
		dictionary.entries.putAll(values);
		return dictionary.encode();
	}
	/** set the data of panel to a dictionary, a JSON object or empty: replaces all the named values. */
	void setAllData(String value) throws xTalkException {
		LinkedHashMap<String,String> next = new LinkedHashMap<String,String>();
		if(value!=null && !value.trim().isEmpty()){
			if(TalkCollection.isCollection(value)){
				TalkCollection c = TalkCollection.decode(value);
				if(c.isArray()) throw new xTalkException("the data requires a dictionary");
				next.putAll(c.entries);
			}
			else if(value.trim().startsWith("{")) next.putAll(TalkJson.object(value));
			else throw new xTalkException("the data requires a dictionary (such as {score: 100}), a JSON object, or empty");
		}
		values = new LinkedHashMap<String,String>();
		for(Map.Entry<String,String> e : next.entrySet()) putData(e.getKey(), e.getValue());
		changed();
	}
	/** the dataKeys of panel: the keys, one per line. */
	String dataKeys(){ return String.join("\n", values.keySet()); }

	/** The buttons, fields and shapes in this panel, buttons and fields in their order, then shapes. */
	ArrayList<OObject> members(){
		ArrayList<OObject> result = new ArrayList<OObject>();
		if(owner.partsList!=null) for(OObject part : owner.partsList) if(part.panelId==id) result.add(part);
		if(owner.shapeList!=null) for(OShape shape : owner.shapeList) if(shape.panelId==id) result.add(shape);
		return result;
	}

	/** The rectangle round the members (0,0,0,0 while it has none). */
	private void updateBounds(){
		ArrayList<OObject> parts = members();
		if(parts.isEmpty()){ left=top=width=height=0; return; }
		int x1=Integer.MAX_VALUE, y1=Integer.MAX_VALUE, x2=Integer.MIN_VALUE, y2=Integer.MIN_VALUE;
		for(OObject part : parts){
			x1=Math.min(x1,part.left); y1=Math.min(y1,part.top);
			x2=Math.max(x2,part.left+part.width); y2=Math.max(y2,part.top+part.height);
		}
		left=x1; top=y1; width=x2-x1; height=y2-y1;
	}
	@Override public int getLeft(){ updateBounds(); return left; }
	@Override public int getTop(){ updateBounds(); return top; }
	@Override public int getWidth(){ updateBounds(); return width; }
	@Override public int getHeight(){ updateBounds(); return height; }

	/** Moves the members together so that the rectangle round them starts at x,y. */
	void setTopLeft(int x, int y){
		int dx=x-getLeft(), dy=y-getTop();
		ArrayList<OObject> parts=members();
		if(parts.isEmpty()) return;
		OObject anchor=parts.get(0);
		if(anchor instanceof OButton) ((OButton)anchor).setTopLeft(anchor.left+dx,anchor.top+dy);
		else if(anchor instanceof OField) ((OField)anchor).setTopLeft(anchor.left+dx,anchor.top+dy);
		else if(anchor instanceof OShape) ((OShape)anchor).setTopLeft(anchor.left+dx,anchor.top+dy);
	}

	/** A copy for another card or background (New Background from a template): the same id, name and contents. */
	OPanel copyFor(OCardBase other){
		OPanel p = new OPanel(other, id, name);
		p.setVisibleInternal(getVisible());
		p.setTextInternal(getText());
		p.values.putAll(values);
		p.scriptList = new ArrayList<String>(scriptList);
		return p;
	}

	void writeXML(XMLStreamWriter w) throws XMLStreamException {
		w.writeStartElement("panel");
		w.writeAttribute("id", Integer.toString(id));
		w.writeAttribute("name", name);
		if(!getVisible()) w.writeAttribute("visible", "false");
		if(!getText().isEmpty()){
			w.writeStartElement("text");
			w.writeCharacters(getText());
			w.writeEndElement();
		}
		String script = getScriptText();
		if(!script.trim().isEmpty()){
			w.writeStartElement("script");
			w.writeCharacters(script);
			w.writeEndElement();
		}
		for(Map.Entry<String,String> e : values.entrySet()){
			w.writeStartElement("value");
			w.writeAttribute("key", e.getKey());
			w.writeCharacters(e.getValue());
			w.writeEndElement();
		}
		w.writeEndElement();
	}

	/** Reads a panel element (the reader is on its start tag) up to its end tag; the stack reader decodes control characters. */
	static OPanel readXML(XMLStreamReader r, OCardBase owner) throws XMLStreamException {
		int id = 0;
		try{ id = Integer.parseInt(r.getAttributeValue(null, "id")); }catch(NumberFormatException | NullPointerException e){ /* given one below */ }
		OPanel p = new OPanel(owner, id, r.getAttributeValue(null, "name"));
		p.setVisibleInternal(!"false".equals(r.getAttributeValue(null, "visible")));
		while(r.hasNext()){
			int event = r.next();
			if(event==XMLStreamConstants.START_ELEMENT){
				if(r.getLocalName().equals("text")) p.setTextInternal(r.getElementText());
				else if(r.getLocalName().equals("script")){
					String script = r.getElementText();
					if(!script.isEmpty()) for(String line : script.split("\n", -1)) p.scriptList.add(line);
				}
				else if(r.getLocalName().equals("value")){
					String key = r.getAttributeValue(null, "key");
					String value = r.getElementText();
					if(key!=null && value!=null && !value.isEmpty()) p.values.put(key, value);
				}
			}
			else if(event==XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("panel")) break;
		}
		return p;
	}
}

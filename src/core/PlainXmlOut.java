import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;

/**
 * {@link XmlOut} without StAX (the core and the browser). It writes the text the JDK's StAX writer writes
 * (com.sun.xml.internal.stream.writers.XMLStreamWriterImpl, checked by tools/wasm-core/XmlCheck.java): a start element stays
 * open for attributes until the next item, which closes it with '>' ('/>' for an empty element, '></name>' for an end
 * element right after it); text escapes &lt; &gt; &amp;, attribute values also '"'; nothing else is escaped.
 */
final class PlainXmlOut extends XmlOut {
	private final Writer out;
	private final ArrayList<String> open = new ArrayList<>();
	private int pending; // 0: none, 1: a start element is open, 2: an empty element is open

	PlainXmlOut(Writer out) { this.out = out; }

	private void put(String s) throws XmlException {
		try { out.write(s); } catch (IOException e) { throw new XmlException(e.getMessage(), false, e); }
	}
	private void closePending() throws XmlException {
		if (pending == 1) put(">");
		else if (pending == 2) put("/>");
		pending = 0;
	}
	private void escaped(String s, boolean attribute) throws XmlException {
		int from = 0, n = s.length();
		StringBuilder b = null;
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			String rep = c == '<' ? "&lt;" : c == '>' ? "&gt;" : c == '&' ? "&amp;" : attribute && c == '"' ? "&quot;" : null;
			if (rep == null) continue;
			if (b == null) b = new StringBuilder(n + 16);
			b.append(s, from, i).append(rep);
			from = i + 1;
		}
		if (b == null) put(s);
		else { b.append(s, from, n); put(b.toString()); }
	}

	@Override void writeStartDocument(String encoding, String version) throws XmlException {
		put("<?xml version=\"" + version + "\" encoding=\"" + encoding + "\"?>");
	}
	@Override void writeEndDocument() throws XmlException {
		closePending();
		for (int i = open.size() - 1; i >= 0; i--) put("</" + open.remove(i) + ">");
	}
	@Override void writeStartElement(String name) throws XmlException {
		closePending();
		put("<" + name);
		open.add(name);
		pending = 1;
	}
	@Override void writeEmptyElement(String name) throws XmlException {
		closePending();
		put("<" + name);
		pending = 2;
	}
	@Override void writeEndElement() throws XmlException {
		if (open.isEmpty()) throw new XmlException("No element was found to write");
		closePending();
		put("</" + open.remove(open.size() - 1) + ">");
	}
	@Override void writeAttribute(String name, String value) throws XmlException {
		if (pending == 0) throw new XmlException("Attribute not associated with any element");
		put(" " + name + "=\"");
		escaped(value, true);
		put("\"");
	}
	@Override void writeCharacters(String text) throws XmlException {
		closePending();
		escaped(text, false);
	}
	@Override void flush() throws XmlException {
		try { out.flush(); } catch (IOException e) { throw new XmlException(e.getMessage(), false, e); }
	}
	@Override void close() throws XmlException { flush(); }
}

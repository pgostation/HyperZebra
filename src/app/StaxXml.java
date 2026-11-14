import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/** The desktop's {@link XmlIn} and {@link XmlOut}: the JDK's StAX parser and writer, exactly what the readers used before. */
final class StaxXml {
	private StaxXml() {}

	static XmlException wrap(XMLStreamException e) { return new XmlException(e.getMessage(), e.getLocation() != null, e); }

	static XmlIn in(final XMLStreamReader r) {
		return new XmlIn() {
			@Override boolean hasNext() throws XmlException { try { return r.hasNext(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override int next() throws XmlException { try { return r.next(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override int getEventType() { return r.getEventType(); }
			@Override String getLocalName() { return r.getLocalName(); }
			@Override String getText() { return r.getText(); }
			@Override boolean hasText() { return r.hasText(); }
			@Override String getElementText() throws XmlException { try { return r.getElementText(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override int getAttributeCount() { return r.getAttributeCount(); }
			@Override String getAttributeLocalName(int index) { return r.getAttributeLocalName(index); }
			@Override String getAttributeValue(int index) { return r.getAttributeValue(index); }
			@Override String getAttributeValue(String namespace, String localName) { return r.getAttributeValue(namespace, localName); }
			@Override int nextTag() throws XmlException { try { return r.nextTag(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void close() throws XmlException { try { r.close(); } catch (XMLStreamException e) { throw wrap(e); } }
		};
	}

	static XmlOut out(final XMLStreamWriter w) {
		return new XmlOut() {
			@Override void writeStartDocument(String encoding, String version) throws XmlException { try { w.writeStartDocument(encoding, version); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void writeEndDocument() throws XmlException { try { w.writeEndDocument(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void writeStartElement(String name) throws XmlException { try { w.writeStartElement(name); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void writeEmptyElement(String name) throws XmlException { try { w.writeEmptyElement(name); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void writeEndElement() throws XmlException { try { w.writeEndElement(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void writeAttribute(String name, String value) throws XmlException { try { w.writeAttribute(name, value); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void writeCharacters(String text) throws XmlException { try { w.writeCharacters(text); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void flush() throws XmlException { try { w.flush(); } catch (XMLStreamException e) { throw wrap(e); } }
			@Override void close() throws XmlException { try { w.close(); } catch (XMLStreamException e) { throw wrap(e); } }
		};
	}

	/** A reader of XML that came from outside (XmlSafe: no DOCTYPE, no entities). */
	static XmlIn in(java.io.InputStream in) throws XmlException {
		try { return in(XmlSafe.input().createXMLStreamReader(in)); } catch (XMLStreamException e) { throw wrap(e); }
	}
	static XmlIn in(java.io.Reader in) throws XmlException {
		try { return in(XmlSafe.input().createXMLStreamReader(in)); } catch (XMLStreamException e) { throw wrap(e); }
	}
	static XmlOut out(java.io.Writer out) throws XmlException {
		try { return out(javax.xml.stream.XMLOutputFactory.newInstance().createXMLStreamWriter(out)); } catch (XMLStreamException e) { throw wrap(e); }
	}
	static XmlOut out(java.io.OutputStream out) throws XmlException {
		try { return out(javax.xml.stream.XMLOutputFactory.newInstance().createXMLStreamWriter(out)); } catch (XMLStreamException e) { throw wrap(e); }
	}
}

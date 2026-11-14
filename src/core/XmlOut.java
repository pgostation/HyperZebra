/**
 * The part of a StAX writer (XmlOut) that the stack writers use, so they also compile for the
 * browser. The desktop writes through the JDK's writer ({@code StaxXml}, src/app); the core and the browser through
 * {@link PlainXmlOut}, which writes the same text. {@link Platform#xmlOut} makes one.
 */
abstract class XmlOut {
	abstract void writeStartDocument(String encoding, String version) throws XmlException;
	abstract void writeEndDocument() throws XmlException;
	abstract void writeStartElement(String name) throws XmlException;
	abstract void writeEmptyElement(String name) throws XmlException;
	abstract void writeEndElement() throws XmlException;
	abstract void writeAttribute(String name, String value) throws XmlException;
	abstract void writeCharacters(String text) throws XmlException;
	abstract void flush() throws XmlException;
	abstract void close() throws XmlException;
}

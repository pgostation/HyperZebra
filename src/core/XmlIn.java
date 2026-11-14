/**
 * The part of a StAX pull parser (XmlIn) that the stack readers use, so they also compile for
 * the browser. The constants are StAX's. The desktop reads through the JDK's parser ({@code StaxXml}, src/app); the core and
 * the browser through {@link HzPullParser}. {@link Platform#xmlIn} makes one.
 */
abstract class XmlIn {
	static final int START_ELEMENT = 1, END_ELEMENT = 2, PROCESSING_INSTRUCTION = 3, CHARACTERS = 4, COMMENT = 5,
		SPACE = 6, START_DOCUMENT = 7, END_DOCUMENT = 8, ENTITY_REFERENCE = 9, ATTRIBUTE = 10, DTD = 11, CDATA = 12;

	abstract boolean hasNext() throws XmlException;
	abstract int next() throws XmlException;
	/** Reads on past the end of the element whose start was just read. */
	void skipElement() throws XmlException {
		for (int depth = 1; depth > 0 && hasNext(); ) { int e = next(); if (e == START_ELEMENT) depth++; else if (e == END_ELEMENT) depth--; }
	}
	abstract int getEventType();
	/** The element's name without a prefix (a start or end element). */
	abstract String getLocalName();
	/** The text of a CHARACTERS, CDATA, SPACE, COMMENT or DTD event. */
	abstract String getText();
	/** The event has text (getText): CHARACTERS, CDATA, SPACE, COMMENT, DTD or ENTITY_REFERENCE. */
	abstract boolean hasText();
	/** At a start element: its text up to its end element (where it leaves the parser); an error at a child element. */
	abstract String getElementText() throws XmlException;
	abstract int getAttributeCount();
	abstract String getAttributeLocalName(int index);
	abstract String getAttributeValue(int index);
	/** The value of the attribute named localName (namespace null: any), or null. */
	abstract String getAttributeValue(String namespace, String localName);
	abstract void close() throws XmlException;

	boolean isStartElement() { return getEventType() == START_ELEMENT; }

	/** StAX's nextTag: past white space, comments and processing instructions to the next start or end element. */
	int nextTag() throws XmlException {
		int e = next();
		while (((e == CHARACTERS || e == CDATA || e == SPACE) && getText().trim().isEmpty()) || e == COMMENT || e == PROCESSING_INSTRUCTION) e = next();
		if (e != START_ELEMENT && e != END_ELEMENT) throw new XmlException("found: " + e + ", expected START_ELEMENT or END_ELEMENT", true, null);
		return e;
	}

	/** A reader whose getText, getElementText and getAttributeValue go through {@code decode} (XmlText's control characters). */
	static XmlIn decoding(final XmlIn in, final java.util.function.UnaryOperator<String> decode) {
		return new XmlIn() {
			@Override boolean hasNext() throws XmlException { return in.hasNext(); }
			@Override int next() throws XmlException { return in.next(); }
			@Override int getEventType() { return in.getEventType(); }
			@Override String getLocalName() { return in.getLocalName(); }
			@Override String getText() { return decode.apply(in.getText()); }
			@Override boolean hasText() { return in.hasText(); }
			@Override String getElementText() throws XmlException { return decode.apply(in.getElementText()); }
			@Override int getAttributeCount() { return in.getAttributeCount(); }
			@Override String getAttributeLocalName(int index) { return in.getAttributeLocalName(index); }
			@Override String getAttributeValue(int index) { return decode.apply(in.getAttributeValue(index)); }
			@Override String getAttributeValue(String namespace, String localName) { return decode.apply(in.getAttributeValue(namespace, localName)); }
			@Override void close() throws XmlException { in.close(); }
		};
	}
}

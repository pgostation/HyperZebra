/**
 * A failure while reading or writing XML (StAX's XmlException on the desktop, which it wraps). located: the parser
 * itself failed at a place of the text (the reader cannot go on), not a reader's own check of what it read.
 */
class XmlException extends Exception {
	private static final long serialVersionUID = 1L;
	final boolean located;
	XmlException() { this(null, false, null); }
	XmlException(String message) { this(message, false, null); }
	XmlException(String message, boolean located, Throwable cause) { super(message, cause); this.located = located; }
}

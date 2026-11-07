import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;

/**
 * Parsers for XML that came from outside: a stack file, the clipboard, a program's property list or nib. A stack is a stranger's
 * file, and the JDK's default parsers follow a DOCTYPE's external entities, so that an entity in a stack's _stack.xml read any
 * file of this computer into a script with no question from the {@link Sandbox} (or fetched a URL). These do not.
 */
final class XmlSafe {
	private XmlSafe(){}

	/** A pull parser that ignores a DOCTYPE: no external entities, no entities at all. */
	static XMLInputFactory input(){
		XMLInputFactory factory = XMLInputFactory.newInstance();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		return factory;
	}

	/** A DOM parser that takes a DOCTYPE (Apple's property lists have one) and reads nothing it names. */
	static DocumentBuilderFactory dom() throws ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setXIncludeAware(false);
		return factory;
	}
}

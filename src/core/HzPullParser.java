import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;

/**
 * {@link XmlIn} without StAX (the core and the browser): a pull parser for the XML HyperZebra reads, giving the events the
 * JDK's parser gives with XmlSafe's settings (tools/wasm-core/XmlCheck.java compares the two on every stack it finds).
 * Elements, attributes, text, CDATA, comments and processing instructions; the predefined entities and character
 * references. A DOCTYPE is one DTD event and declares nothing (as with SUPPORT_DTD off), so any other entity is an error.
 * Line ends become \n, and tabs and line ends in attribute values spaces, as XML 1.0 says.
 */
final class HzPullParser extends XmlIn {
	private final String s;
	private int p;
	private int event = START_DOCUMENT;
	private String name;          // of the current start or end element (with its prefix)
	private String text;          // of the current text, comment, PI or DTD event
	private final ArrayList<String> attrNames = new ArrayList<>(), attrValues = new ArrayList<>();
	private final ArrayList<String> open = new ArrayList<>();
	private boolean selfClosing;  // the current start element was <a/>: its end element comes next

	HzPullParser(String text) {
		int start = text.length() > 0 && text.charAt(0) == '﻿' ? 1 : 0;
		this.s = text;
		this.p = start;
		if (s.startsWith("<?xml", p) && p + 5 < s.length() && isSpace(s.charAt(p + 5))) {
			int end = s.indexOf("?>", p);
			if (end >= 0) p = end + 2;
		}
	}

	static HzPullParser of(Reader in) throws XmlException {
		StringBuilder b = new StringBuilder();
		char[] buf = new char[8192];
		try { for (int n; (n = in.read(buf)) > 0; ) b.append(buf, 0, n); }
		catch (IOException e) { throw new XmlException(e.getMessage(), false, e); }
		return new HzPullParser(b.toString());
	}

	static HzPullParser of(InputStream in) throws XmlException {
		byte[] bytes;
		try { bytes = in.readAllBytes(); }
		catch (IOException e) { throw new XmlException(e.getMessage(), false, e); }
		return new HzPullParser(decode(bytes));
	}

	/** The bytes as text: a byte order mark, else the declaration's encoding, else UTF-8. */
	static String decode(byte[] b) throws XmlException {
		int n = b.length;
		if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
			return new String(b, 3, n - 3, java.nio.charset.StandardCharsets.UTF_8);
		if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, n - 2, java.nio.charset.StandardCharsets.UTF_16BE);
		if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, n - 2, java.nio.charset.StandardCharsets.UTF_16LE);
		String charset = "UTF-8";
		int head = Math.min(n, 200);
		StringBuilder h = new StringBuilder(head);
		for (int i = 0; i < head; i++) h.append((char) (b[i] & 0xFF));
		String decl = h.toString();
		if (decl.startsWith("<?xml")) {
			int end = decl.indexOf("?>");
			if (end > 0) decl = decl.substring(0, end);
			int e = decl.indexOf("encoding");
			if (e > 0) {
				int q = e + 8;
				while (q < decl.length() && (isSpace(decl.charAt(q)) || decl.charAt(q) == '=')) q++;
				if (q < decl.length() && (decl.charAt(q) == '"' || decl.charAt(q) == '\'')) {
					int close = decl.indexOf(decl.charAt(q), q + 1);
					if (close > q) charset = decl.substring(q + 1, close);
				}
			}
		}
		try { return new String(b, Platform.get().charset(charset)); }
		catch (RuntimeException e) { throw new XmlException("Invalid encoding name \"" + charset + "\".", true, e); }
	}

	private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
	private static boolean isNameChar(char c) {
		return c > 0x7F || Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '.';
	}

	private XmlException error(String message) {
		int line = 1, col = 1;
		for (int i = 0; i < p && i < s.length(); i++) { if (s.charAt(i) == '\n') { line++; col = 1; } else col++; }
		return new XmlException("ParseError at [row,col]:[" + line + "," + col + "]\nMessage: " + message, true, null);
	}

	@Override boolean hasNext() { return event != END_DOCUMENT; }

	@Override int next() throws XmlException {
		if (event == END_DOCUMENT) throw new java.util.NoSuchElementException("END_DOCUMENT reached: no more elements on the stream.");
		attrNames.clear(); attrValues.clear();
		text = null;
		if (selfClosing) {
			selfClosing = false;
			open.remove(open.size() - 1);
			return event = END_ELEMENT;
		}
		while (true) {
			if (p >= s.length()) {
				if (!open.isEmpty()) throw error("XML document structures must start and end within the same entity.");
				name = null;
				return event = END_DOCUMENT;
			}
			char c = s.charAt(p);
			if (c != '<') {
				int start = p;
				String t = readText();
				if (open.isEmpty()) {
					if (t.trim().isEmpty()) continue; // white space round the root element is no event
					p = start;
					throw error("Content is not allowed in " + (seenRoot ? "trailing section." : "prolog."));
				}
				text = t;
				return event = CHARACTERS;
			}
			if (s.startsWith("<!--", p)) {
				int end = s.indexOf("-->", p + 4);
				if (end < 0) throw error("XML document structures must start and end within the same entity.");
				text = s.substring(p + 4, end);
				p = end + 3;
				return event = COMMENT;
			}
			if (s.startsWith("<![CDATA[", p)) {
				if (open.isEmpty()) throw error("Content is not allowed in prolog.");
				int end = s.indexOf("]]>", p + 9);
				if (end < 0) throw error("The CDATA section must end with \"]]>\".");
				text = lineEnds(s.substring(p + 9, end));
				p = end + 3;
				return event = CDATA;
			}
			if (s.startsWith("<!DOCTYPE", p)) {
				int start = p, depth = 0;
				p += 9;
				while (p < s.length()) {
					char d = s.charAt(p++);
					if (d == '[') depth++;
					else if (d == ']') depth--;
					else if (d == '>' && depth <= 0) break;
				}
				text = s.substring(start, p);
				return event = DTD;
			}
			if (s.startsWith("<?", p)) {
				int end = s.indexOf("?>", p + 2);
				if (end < 0) throw error("XML document structures must start and end within the same entity.");
				text = s.substring(p + 2, end);
				p = end + 2;
				return event = PROCESSING_INSTRUCTION;
			}
			if (s.startsWith("</", p)) {
				p += 2;
				String n = readName();
				skipSpace();
				if (p >= s.length() || s.charAt(p) != '>') throw error("The end-tag for element type \"" + n + "\" must end with a '>' delimiter.");
				p++;
				if (open.isEmpty() || !open.get(open.size() - 1).equals(n)) {
					String expected = open.isEmpty() ? n : open.get(open.size() - 1);
					throw error("The element type \"" + expected + "\" must be terminated by the matching end-tag \"</" + expected + ">\".");
				}
				open.remove(open.size() - 1);
				name = n;
				return event = END_ELEMENT;
			}
			p++;
			if (open.isEmpty() && seenRoot) throw error("The markup in the document following the root element must be well-formed.");
			String n = readName();
			while (true) {
				boolean spaced = p < s.length() && isSpace(s.charAt(p));
				skipSpace();
				if (p >= s.length()) throw error("XML document structures must start and end within the same entity.");
				char d = s.charAt(p);
				if (d == '>') { p++; break; }
				if (d == '/' && p + 1 < s.length() && s.charAt(p + 1) == '>') { p += 2; selfClosing = true; break; }
				if (!spaced) throw error("Element type \"" + n + "\" must be followed by either attribute specifications, \">\" or \"/>\".");
				String an = readName();
				skipSpace();
				if (p >= s.length() || s.charAt(p) != '=') throw error("Attribute name \"" + an + "\" associated with an element type \"" + n + "\" must be followed by the ' = ' character.");
				p++;
				skipSpace();
				if (p >= s.length() || (s.charAt(p) != '"' && s.charAt(p) != '\'')) throw error("Open quote is expected for attribute \"" + an + "\" associated with an  element type  \"" + n + "\".");
				if (attrNames.contains(an)) throw error("Attribute \"" + an + "\" was already specified for element \"" + n + "\".");
				attrNames.add(an);
				attrValues.add(readAttributeValue(s.charAt(p++)));
			}
			open.add(n);
			seenRoot = true;
			name = n;
			return event = START_ELEMENT;
		}
	}
	private boolean seenRoot;

	private void skipSpace() { while (p < s.length() && isSpace(s.charAt(p))) p++; }

	private String readName() throws XmlException {
		int start = p;
		while (p < s.length() && isNameChar(s.charAt(p))) p++;
		if (p == start) throw error("The markup in the document must be well-formed.");
		return s.substring(start, p);
	}

	/** Text up to the next '<', with its references replaced and its line ends made \n. */
	private String readText() throws XmlException {
		StringBuilder b = null;
		int from = p;
		while (p < s.length()) {
			char c = s.charAt(p);
			if (c == '<') break;
			if (c == '&' || c == '\r') {
				if (b == null) b = new StringBuilder();
				b.append(s, from, p);
				if (c == '&') reference(b);
				else { p++; if (p < s.length() && s.charAt(p) == '\n') p++; b.append('\n'); }
				from = p;
				continue;
			}
			p++;
		}
		if (b == null) return s.substring(from, p);
		return b.append(s, from, p).toString();
	}

	private String readAttributeValue(char quote) throws XmlException {
		StringBuilder b = new StringBuilder();
		while (true) {
			if (p >= s.length()) throw error("XML document structures must start and end within the same entity.");
			char c = s.charAt(p);
			if (c == quote) { p++; return b.toString(); }
			if (c == '<') throw error("The value of attribute must not contain the '<' character.");
			if (c == '&') { reference(b); continue; }
			if (c == '\r') { p++; if (p < s.length() && s.charAt(p) == '\n') p++; b.append(' '); continue; }
			b.append(c == '\t' || c == '\n' ? ' ' : c);
			p++;
		}
	}

	/** At '&': the predefined entity or the character it names, appended. */
	private void reference(StringBuilder b) throws XmlException {
		int semi = s.indexOf(';', p);
		if (semi < 0 || semi - p > 12) throw error("The entity name must immediately follow the '&' in the entity reference.");
		String r = s.substring(p + 1, semi);
		if (r.startsWith("#")) {
			int code;
			try { code = r.startsWith("#x") ? Integer.parseInt(r.substring(2), 16) : Integer.parseInt(r.substring(1)); }
			catch (NumberFormatException e) { throw error("A decimal representation must immediately follow the \"&#\" in a character reference."); }
			if (code == 0 || code > 0x10FFFF || (code < 0x20 && code != 9 && code != 10 && code != 13) || (code >= 0xD800 && code <= 0xDFFF) || code == 0xFFFE || code == 0xFFFF)
				throw error("Character reference \"&" + r + "\" is an invalid XML character.");
			b.appendCodePoint(code);
		}
		else if (r.equals("lt")) b.append('<');
		else if (r.equals("gt")) b.append('>');
		else if (r.equals("amp")) b.append('&');
		else if (r.equals("quot")) b.append('"');
		else if (r.equals("apos")) b.append('\'');
		else throw error("The entity \"" + r + "\" was referenced, but not declared.");
		p = semi + 1;
	}

	private static String lineEnds(String t) {
		if (t.indexOf('\r') < 0) return t;
		return t.replace("\r\n", "\n").replace('\r', '\n');
	}

	@Override int getEventType() { return event; }

	@Override String getLocalName() {
		if (event != START_ELEMENT && event != END_ELEMENT) throw new IllegalStateException("Method getLocalName() cannot be called for " + eventName() + " event.");
		int colon = name.indexOf(':');
		return colon < 0 ? name : name.substring(colon + 1);
	}

	@Override String getText() {
		if (event != CHARACTERS && event != CDATA && event != SPACE && event != COMMENT && event != DTD && event != ENTITY_REFERENCE)
			throw new IllegalStateException("Current state " + eventName() + " is not among the states CHARACTERS, COMMENT, CDATA, SPACE, ENTITY_REFERENCE, DTD valid for getText() ");
		return text;
	}

	@Override boolean hasText() {
		return event == CHARACTERS || event == CDATA || event == SPACE || event == COMMENT || event == DTD || event == ENTITY_REFERENCE;
	}

	@Override String getElementText() throws XmlException {
		if (event != START_ELEMENT) throw error("parser must be on START_ELEMENT to read next text");
		StringBuilder b = new StringBuilder();
		while (true) {
			int e = next();
			if (e == CHARACTERS || e == CDATA || e == SPACE || e == ENTITY_REFERENCE) b.append(text);
			else if (e == END_ELEMENT) return b.toString();
			else if (e == START_ELEMENT) throw error("elementGetText() function expects text only elment but START_ELEMENT was encountered.");
			else if (e == END_DOCUMENT) throw error("unexpected end of document when reading element text content");
		}
	}

	@Override int getAttributeCount() { return attrNames.size(); }
	@Override String getAttributeLocalName(int index) {
		String n = attrNames.get(index);
		int colon = n.indexOf(':');
		return colon < 0 ? n : n.substring(colon + 1);
	}
	@Override String getAttributeValue(int index) { return attrValues.get(index); }
	@Override String getAttributeValue(String namespace, String localName) {
		for (int i = 0; i < attrNames.size(); i++) if (getAttributeLocalName(i).equals(localName)) return attrValues.get(i);
		return null;
	}
	@Override void close() {}

	private String eventName() {
		switch (event) {
			case START_ELEMENT: return "START_ELEMENT";
			case END_ELEMENT: return "END_ELEMENT";
			case CHARACTERS: return "CHARACTERS";
			case COMMENT: return "COMMENT";
			case CDATA: return "CDATA";
			case DTD: return "DTD";
			case PROCESSING_INSTRUCTION: return "PROCESSING_INSTRUCTION";
			case START_DOCUMENT: return "START_DOCUMENT";
			case END_DOCUMENT: return "END_DOCUMENT";
			default: return String.valueOf(event);
		}
	}
}

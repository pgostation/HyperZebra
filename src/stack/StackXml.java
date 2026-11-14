import java.util.List;

/**
 * A stack as its _stack.xml (UTF-8, HyperZebra's control characters encoded: XmlText): the stack, its fonts and styles, the
 * backgrounds, the cards in the stack's order, the resources. The one serializer of the desktop's save (XMLwrite: the
 * file, sealing, the bundle) and of the browser player's (PlayerSaving: the page keeps it). The change flags are cleared as
 * each part is written (an edit made meanwhile marks it again); the parts cleared go into {@code cleared}, so that a caller
 * whose write did not get through can mark them again.
 *
 * The text goes straight into UTF-8 bytes as it is written (Utf8Out), the control characters encoded on the way
 * (XmlText.encodeControlChars' rule), into a buffer the size of the stack's last write: a stack of N MB takes about 2N MB
 * while it is written (it took about 10N through a String, its copy with the characters encoded and its bytes: 100 MB for
 * Mosslight RPG Engine's 9.5 MB, where the browser player can run out of memory).
 */
final class StackXml {
	private StackXml() {}

	/** The size of each stack's last write: the next one's buffer starts there (an eighth more for what was added) */
	private static final java.util.Map<OStack, Integer> lastSize = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	static byte[] write(OStack stack, List<OCardBase> cleared) throws XmlException {
		Integer last = lastSize.get(stack);
		Utf8Out bytes = new Utf8Out(last == null ? 64 * 1024 : last + last / 8 + 1024);
		XmlOut writer = Platform.get().xmlOut(bytes);
		writer.writeStartDocument("utf-8", "1.0");
		writer.writeCharacters("\n");
		//writer.writeDTD("<!DOCTYPE stackfile PUBLIC \"-//Apple, Inc.//DTD stackfile V 2.0//EN\" \"\" >");
		writer.writeCharacters("\n");
		writer.writeStartElement("stackfile");
		writer.writeCharacters("\n\t");

		stack.changed = false; //書き出す前に消す: 書き出し中の変更は再びtrueにするので次回に残る
		stack.writeXML(writer);
		stack.writeFontXML(writer);
		stack.writeStyleXML(writer);
		for(int i=0 ;i<stack.bgCacheList.size(); i++){
			OBackground bg = stack.bgCacheList.get(i);
			if(bg.changed){ bg.changed = false; cleared.add(bg); }
			bg.writeXML(writer);
		}
		if(stack.cardIdList.size()>=stack.cdCacheList.size()){
			for(int i=0 ;i<stack.cardIdList.size(); i++){
				int id = stack.cardIdList.get(i);
				for(int j=0 ;j<stack.cdCacheList.size(); j++){
					OCard cd = stack.cdCacheList.get(j);
					if(cd.id == id){
						if(cd.changed){ cd.changed = false; cleared.add(cd); }
						cd.writeXML(writer);
						break;
					}
				}
			}
		}else{
			for(int i=0 ;i<stack.cdCacheList.size(); i++){
				OCard cd = stack.cdCacheList.get(i);
				if(cd.changed){ cd.changed = false; cleared.add(cd); }
				cd.writeXML(writer);
			} //これでは順番が狂ってしまう
		}
		stack.rsrc.writeXML(writer);
		stack.rsrc.writePLTEXML(writer);
		stack.rsrc.writeAddColorXML(writer);
		stack.rsrc.writeXcmdXML(writer);
		writer.writeEndDocument();
		writer.close();
		byte[] out = bytes.toByteArray();
		lastSize.put(stack, out.length);
		return out;
	}

	/**
	 * A Writer of UTF-8 bytes into a buffer that grows by half: the characters below U+0020 other than tab, line feed and
	 * return become U+E000 + c (XmlText.encodeControlChars), a surrogate pair one 4-byte character, a lone surrogate '?'
	 * (as String.getBytes(UTF_8) writes it).
	 */
	static final class Utf8Out extends java.io.Writer {
		private byte[] buf;
		private int n;
		/** a high surrogate waiting for its low half (0: none) */
		private char high;
		Utf8Out(int capacity) { buf = new byte[Math.max(16, capacity)]; }
		private void room(int more) {
			if (n + more <= buf.length) return;
			buf = java.util.Arrays.copyOf(buf, Math.max(n + more, buf.length + (buf.length >> 1)));
		}
		@Override public void write(int c) { put((char) c); }
		@Override public void write(char[] cs, int off, int len) { room(len); for (int i = 0; i < len; i++) put(cs[off + i]); }
		@Override public void write(String s, int off, int len) { room(len); for (int i = 0; i < len; i++) put(s.charAt(off + i)); }
		private void put(char c) {
			if (high != 0) {
				char h = high;
				high = 0;
				if (Character.isLowSurrogate(c)) { code(Character.toCodePoint(h, c)); return; }
				code('?');
			}
			if (Character.isHighSurrogate(c)) { high = c; return; }
			if (Character.isLowSurrogate(c)) { code('?'); return; }
			if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') c = (char) (XmlText.BASE + c);
			code(c);
		}
		private void code(int cp) {
			room(4);
			if (cp < 0x80) buf[n++] = (byte) cp;
			else if (cp < 0x800) { buf[n++] = (byte) (0xC0 | cp >> 6); buf[n++] = (byte) (0x80 | cp & 0x3F); }
			else if (cp < 0x10000) { buf[n++] = (byte) (0xE0 | cp >> 12); buf[n++] = (byte) (0x80 | cp >> 6 & 0x3F); buf[n++] = (byte) (0x80 | cp & 0x3F); }
			else { buf[n++] = (byte) (0xF0 | cp >> 18); buf[n++] = (byte) (0x80 | cp >> 12 & 0x3F); buf[n++] = (byte) (0x80 | cp >> 6 & 0x3F); buf[n++] = (byte) (0x80 | cp & 0x3F); }
		}
		@Override public void flush() {}
		/** (a high surrogate left at the end is a lone one) */
		@Override public void close() { if (high != 0) { high = 0; code('?'); } }
		byte[] toByteArray() { close(); return n == buf.length ? buf : java.util.Arrays.copyOf(buf, n); }
	}

	/** Whether the stack has changes not written yet (the stack itself, a card or a background) */
	static boolean needsSave(OStack stack){
		if(stack.changed) return true;
		if(stack.cdCacheList==null || stack.bgCacheList==null) return false;
		for(OCard cd : stack.cdCacheList) if(cd.changed) return true;
		for(OBackground bg : stack.bgCacheList) if(bg.changed) return true;
		return false;
	}
}

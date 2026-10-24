import java.nio.charset.CharacterCodingException;

/** Loss-tolerant encoder shared by one HyperCard export operation. */
final class StackTextEncoder {
	final StackTextEncoding encoding;
	private boolean replaced;

	StackTextEncoder(StackTextEncoding encoding) {
		this.encoding = encoding == null ? StackTextEncoding.JAPANESE : encoding;
	}

	byte[] encode(String text) {
		String value = text == null ? "" : text;
		try {
			return encoding.encode(value);
		} catch(CharacterCodingException e) {
			replaced = true;
			return encoding.encodeReplacing(value);
		}
	}

	/**
	 * A script. In the two-byte encodings, HyperTalk's ¬ ≠ ≤ ≥ are written as their Mac OS Roman bytes (HyperCard reads the
	 * script's operators that way; GBK's own ≠ would be a two-byte character to it). Japanese too: HyperCard J-2.3 does not
	 * compile Shift-JIS's two-byte ¬ (line continuation) or ≠ (probe stack, compile_check.py, Oct 13 2026), and its
	 * stacks have the Roman bytes (HCData.restoreMacRomanScriptOperators reads them back). The one-byte encodings keep
	 * these characters as they are.
	 */
	byte[] encodeScript(String text) {
		String value = text == null ? "" : text;
		if(encoding != StackTextEncoding.SIMPLIFIED_CHINESE && encoding != StackTextEncoding.TRADITIONAL_CHINESE &&
				encoding != StackTextEncoding.KOREAN && encoding != StackTextEncoding.JAPANESE) return encode(value);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(value.length()*2);
		int start = 0;
		for(int i=0; i<value.length(); i++){
			int k = "¬≠≤≥".indexOf(value.charAt(i));
			if(k<0) continue;
			out.writeBytes(encode(value.substring(start, i)));
			out.write(new int[]{0xC2, 0xAD, 0xB2, 0xB3}[k]);
			start = i+1;
		}
		out.writeBytes(encode(value.substring(start)));
		return out.toByteArray();
	}

	boolean replacementOccurred() {
		return replaced;
	}

	String replacementWarning() {
		return "Some characters could not be represented by "+encoding+
			" and were replaced with '?'.";
	}
}

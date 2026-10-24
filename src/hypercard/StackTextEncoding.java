import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;

/**
 * The legacy Mac OS text encoding used to decode a HyperCard stack's text
 * (field and button content, scripts, names...).
 *
 * The HyperCard stack file format does not record which encoding was active
 * on the Script Manager when the stack was authored (see
 * HYPERCARD_STACK_FORMAT.md), so importing a stack guesses it from the
 * stack's own text ({@link StackEncodingDetector}), falling back to a
 * locale-based guess (see {@link #forLegacyLanguageSetting}) when the text is
 * plain ASCII.
 *
 * Only encodings actually distributed with the JDK are supported for the
 * single-byte scripts; the two-byte Japanese script keeps using
 * {@link MacJapaneseDecoder}, which already refines plain Shift-JIS with
 * Apple's additions and mapping differences.
 */
enum StackTextEncoding {
	JAPANESE("Japanese (Shift-JIS)") {
		@Override String decode(byte[] data, int offset, int length) {
			return MacJapaneseDecoder.decode(data, offset, length);
		}
		//MacJapaneseDecoderが復元する♥♦♣♠等のApple独自マッピング文字は、
		//JDK標準のx-MacJapaneseエンコーダには対応する逆変換がなく'?'に
		//置き換えられてしまう(スクリプト中の記号がインポート後に消えて
		//見える不具合の原因)。MacJapaneseDecoderと同じ変換表を使う
		//MacJapaneseEncoderで、デコードと対称な変換にする。
		@Override byte[] encode(String text) throws CharacterCodingException {
			return MacJapaneseEncoder.encode(text);
		}
		@Override byte[] encodeReplacing(String text) {
			return MacJapaneseEncoder.encodeReplacing(text);
		}
	},
	ROMAN("Western (Mac OS Roman)", "x-MacRoman"),
	CENTRAL_EUROPE("Central European (Mac OS)", "x-MacCentralEurope"),
	CROATIAN("Croatian (Mac OS)", "x-MacCroatian"),
	CYRILLIC("Cyrillic (Mac OS)", "x-MacCyrillic"),
	GREEK("Greek (Mac OS)", "x-MacGreek"),
	HEBREW("Hebrew (Mac OS)", "x-MacHebrew"),
	ICELANDIC("Icelandic (Mac OS)", "x-MacIceland"),
	ROMANIAN("Romanian (Mac OS)", "x-MacRomania"),
	THAI("Thai (Mac OS)", "x-MacThai"),
	TURKISH("Turkish (Mac OS)", "x-MacTurkish"),
	UKRAINIAN("Ukrainian (Mac OS)", "x-MacUkraine"),
	ARABIC("Arabic (Mac OS)", "x-MacArabic"),
	//二バイト系: Mac OS の簡体字中国語は EUC-CN(GB 2312)、繁体字中国語は Big5、韓国語は EUC-KR(KS X 1001)が元。
	//JDK に Mac 版そのものは無いので、それを含む GBK / Big5 / EUC-KR で読む(中国語版 HyperCard Player 2.4 の付属スタック)
	SIMPLIFIED_CHINESE("Chinese Simplified (Mac OS)", "GBK"),
	TRADITIONAL_CHINESE("Chinese Traditional (Mac OS)", "Big5"),
	KOREAN("Korean (Mac OS)", "EUC-KR");

	final String displayName;
	private final Charset charset;

	StackTextEncoding(String displayName) {
		this(displayName, null);
	}

	StackTextEncoding(String displayName, String charsetName) {
		this.displayName = displayName;
		this.charset = charsetName == null ? null : Charset.forName(charsetName);
	}

	String decode(byte[] data, int offset, int length) {
		return new String(data, offset, length, charset);
	}

	final String decode(byte[] data) {
		return decode(data, 0, data.length);
	}

	byte[] encode(String text) throws CharacterCodingException {
		Charset outputCharset = outputCharset();
		ByteBuffer bytes = outputCharset.newEncoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.encode(java.nio.CharBuffer.wrap(text == null ? "" : text));
		byte[] result = new byte[bytes.remaining()];
		bytes.get(result);
		return result;
	}

	byte[] encodeReplacing(String text) {
		try {
			ByteBuffer bytes = outputCharset().newEncoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE)
				.replaceWith(new byte[]{(byte)'?'})
				.encode(java.nio.CharBuffer.wrap(text == null ? "" : text));
			byte[] result = new byte[bytes.remaining()];
			bytes.get(result);
			return result;
		} catch(CharacterCodingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private Charset outputCharset() {
		if(this == JAPANESE) {
			return Charset.isSupported("x-MacJapanese")
				? Charset.forName("x-MacJapanese") : Charset.forName("windows-31j");
		}
		return charset;
	}

	@Override
	public String toString() {
		return displayName;
	}

	/**
	 * Backward-compatible default: HyperZebra used to only distinguish
	 * "Japanese" from everything else (decoded with the JVM's platform
	 * default charset, which silently mangled accented Mac OS Roman text).
	 * Non-Japanese stacks now default to the correct Mac OS Roman decoding
	 * instead, and can still be overridden explicitly.
	 */
	static StackTextEncoding forLegacyLanguageSetting(String lang) {
		return International.sameLanguage(lang, "ja") ? JAPANESE : ROMAN;
	}
}

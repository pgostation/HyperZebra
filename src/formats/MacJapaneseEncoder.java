import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Encodes text back into the legacy Mac OS Japanese character encoding,
 * mirroring {@link MacJapaneseDecoder}'s Apple-specific additions (e.g. the
 * card suit symbols ♥♦♣♠, circled numbers, Roman numerals...) so that text
 * decoded by MacJapaneseDecoder round-trips back to the same bytes on export
 * instead of falling back to '?' for characters the JDK's own x-MacJapanese
 * encoder does not know how to re-encode.
 */
final class MacJapaneseEncoder {
	private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
	private static final Map<String, Integer> OVERRIDES = buildReverseOverrides();
	/**
	 * Characters the standard encoder would get wrong or lose, checked first:
	 * - the backslash: Shift-JIS writes it as 0x5C, which Mac OS Japanese (and the decoder) reads as the yen sign - its
	 *   backslash is 0x80. A "\" handed to a 68K external came back as "¥" (UxReplace, UxDeHTML), so `"\" is "¥"`-style
	 *   comparisons of their results failed. Encoded as 0x80 it round-trips.
	 * - the full-width forms of Windows / modern input (CP932) that Mac OS Japanese has under their JIS names: they were "?".
	 */
	private static final Map<Integer, Integer> PREFERRED = Map.of(
		0x5C, 0x80, 0xFF5E, 0x8160, 0xFF0D, 0x817C, 0x2225, 0x8161, 0xFFE2, 0x81CA, 0xFFE0, 0x8191, 0xFFE1, 0x8192);
	private static final int MAX_OVERRIDE_LENGTH = maxOverrideLength();

	private MacJapaneseEncoder() {
	}

	//変換できない文字があれば例外を投げる(呼び出し側は置換版にフォールバックする)
	static byte[] encode(String text) throws CharacterCodingException {
		return encode(text, false);
	}

	//変換できない文字は'?'に置き換える
	static byte[] encodeReplacing(String text) {
		try {
			return encode(text, true);
		} catch(CharacterCodingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	//常に標準のShift-JISを優先し、Apple独自の変換表は標準では表現できない
	//文字(♥等の記号類)の時だけ使う。
	//
	//変換表には、たとえば長音記号"ー"のように、標準のShift-JISでも普通に
	//表現できる文字が(縦書き時の別コードとして)重複して載っていることがある。
	//そうした文字を先に変換表と照合してしまうと、ごく普通のテキストが、
	//本物のMac/HyperCardでは正しく解釈されない縦書き用の稀なバイト列に
	//化けてしまう(実際に本物のHyperCardでボタンのスクリプトが消える不具合の
	//原因になった)。標準のエンコーダで表現できない文字にだけ変換表へ
	//フォールバックすることで、この事故を避ける。
	private static byte[] encode(String text, boolean replace) throws CharacterCodingException {
		String value = text == null ? "" : text;
		ByteArrayOutputStream out = new ByteArrayOutputStream(value.length()*2);
		java.nio.charset.CharsetEncoder shiftJisEncoder = SHIFT_JIS.newEncoder();
		int runStart = 0;
		int i = 0;
		while(i < value.length()) {
			int codePoint = value.codePointAt(i);
			int codePointLength = Character.charCount(codePoint);
			//サロゲートペア(補助文字面)はどのみちShift-JISで表現できないため、
			//1コードポイント分まとめて「変換できない」扱いにする。文字単位
			//(char単位)でcanEncodeすると、代用対の上位・下位を別々に「変換
			//できない」と判定してしまい、絵文字1文字のはずが'?'が2個出力される。
			//a kana and its combining (han)dakuten, as macOS file names and pasted text spell them (NFD): one Shift-JIS character
			//(Miko's jcharToNum of a decomposed "ガ" was カ's code)
			if(i+codePointLength < value.length() && isCombiningMark(value.charAt(i+codePointLength))) {
				String composed = java.text.Normalizer.normalize(value.substring(i, i+codePointLength+1), java.text.Normalizer.Form.NFC);
				if(composed.length() == 1 && shiftJisEncoder.canEncode(composed.charAt(0))) {
					if(runStart < i) writeRun(out, value, runStart, i, replace);
					writeRun(out, composed, 0, 1, replace);
					i += codePointLength+1;
					runStart = i;
					continue;
				}
			}
			Integer preferred = PREFERRED.get(codePoint);
			if(preferred != null) {
				if(runStart < i) writeRun(out, value, runStart, i, replace);
				if(preferred > 0xFF) out.write(preferred>>8 & 0xFF);
				out.write(preferred & 0xFF);
				i += codePointLength;
				runStart = i;
				continue;
			}
			if(codePointLength==1 && shiftJisEncoder.canEncode((char)codePoint)) {
				i += 1;
				continue;
			}
			if(runStart < i) writeRun(out, value, runStart, i, replace);//ここまでは標準変換できる区間

			int matchLength = 0;
			int matchCode = -1;
			int maxLength = Math.min(MAX_OVERRIDE_LENGTH, value.length()-i);
			for(int length = maxLength; length >= 1; length--) {
				Integer code = OVERRIDES.get(value.substring(i, i+length));
				if(code != null) {
					matchLength = length;
					matchCode = code;
					break;
				}
			}
			if(matchCode == -1 && codePoint >= 0xE000 && codePoint < 0xE000 + 13*188) {
				//the user-defined characters (外字) F040-FCFC, which MacJapaneseDecoder reads as the private use area
				int index = codePoint - 0xE000, trail = index % 188;
				matchCode = (0xF0 + index/188) << 8 | (trail < 63 ? 0x40 + trail : 0x80 + trail - 63);
				matchLength = 1;
			}
			if(matchCode == -1 && codePoint >= MacJapaneseDecoder.RAW_BYTES && codePoint <= MacJapaneseDecoder.RAW_BYTES + 0xFF) {
				//a byte that was no character, as MacJapaneseDecoder's rawBytes decoding keeps it (text of 68K externals)
				matchCode = codePoint - MacJapaneseDecoder.RAW_BYTES;
				matchLength = 1;
			}
			if(matchCode != -1) {
				if(matchCode > 0xFF) {
					out.write((matchCode>>8) & 0xFF);
					out.write(matchCode & 0xFF);
				} else {
					out.write(matchCode);
				}
				i += matchLength;
			} else {
				//変換表にもない: 標準エンコーダにこの1コードポイント分を処理させ、
				//REPORT/REPLACEに従う
				writeRun(out, value, i, i+codePointLength, replace);
				i += codePointLength;
			}
			runStart = i;
		}
		if(runStart < value.length()) writeRun(out, value, runStart, value.length(), replace);
		return out.toByteArray();
	}

	private static boolean isCombiningMark(char c) {
		return c == '゙' || c == '゚' || c >= '̀' && c <= 'ͯ';
	}

	private static void writeRun(ByteArrayOutputStream out, String value, int start, int end,
			boolean replace) throws CharacterCodingException {
		CharBuffer input = CharBuffer.wrap(value, start, end);
		java.nio.charset.CharsetEncoder encoder = SHIFT_JIS.newEncoder();
		if(replace) {
			encoder.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE)
				.replaceWith(new byte[]{(byte)'?'});
		} else {
			encoder.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		}
		ByteBuffer bytes = encoder.encode(input);
		byte[] result = new byte[bytes.remaining()];
		bytes.get(result);
		out.write(result, 0, result.length);
	}

	private static Map<String, Integer> buildReverseOverrides() {
		Map<String, Integer> reverse = new HashMap<String, Integer>();
		int[] data = MacJapaneseDecoder.OVERRIDE_DATA;
		for(int index = 0; index < data.length;) {
			int code = data[index++];
			int count = data[index++];
			StringBuilder value = new StringBuilder(count);
			for(int i = 0; i < count; i++) {
				value.appendCodePoint(data[index++]);
			}
			//同じ文字列に複数のバイト列が対応する場合は最初に見つかったものを使う
			//(OVERRIDE_DATAの登場順=Apple公式表の記載順を優先する)
			if(!reverse.containsKey(value.toString())) {
				reverse.put(value.toString(), code);
			}
		}
		return reverse;
	}

	private static int maxOverrideLength() {
		int max = 1;
		for(String key: OVERRIDES.keySet()) {
			max = Math.max(max, key.length());
		}
		return max;
	}
}

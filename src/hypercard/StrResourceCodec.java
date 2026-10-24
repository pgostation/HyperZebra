import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

//STR('STR ')/STR#/TEXTリソースの生バイト列と文字列(または文字列のリスト)を
//相互変換する。実機のフォーマットは次のとおり:
//  STR (単一文字列): 1バイトの長さ + それだけのバイト列(Pascal文字列)
//  STR#(文字列リスト): 2バイトの個数(ビッグエンディアン) + Pascal文字列がその数だけ
//  TEXT(生テキスト): 長さプレフィックスなしで、バイト列がそのままテキスト本体
//文字コードはスタックファイル自体には記録されていない(StackTextEncoding参照)ため、
//呼び出し側(StrEditor)が選んだStackTextEncodingで変換する。
final class StrResourceCodec {
	private StrResourceCodec(){}

	//Pascal文字列は1バイトの長さしか持てないため255バイトまで
	static final int MAX_PASCAL_STRING_BYTES = 255;

	static String decodeString(byte[] b, StackTextEncoding encoding){
		if(b==null || b.length<1) return "";
		int len = b[0]&0xFF;
		int end = Math.min(b.length, 1+len);
		return encoding.decode(b, 1, end-1);
	}

	static byte[] encodeString(String text, StackTextEncoding encoding){
		byte[] bytes = clampToPascalLength(encoding.encodeReplacing(text));
		byte[] result = new byte[1+bytes.length];
		result[0] = (byte)bytes.length;
		System.arraycopy(bytes, 0, result, 1, bytes.length);
		return result;
	}

	static String[] decodeStringList(byte[] b, StackTextEncoding encoding){
		if(b==null || b.length<2) return new String[0];
		int count = ((b[0]&0xFF)<<8)|(b[1]&0xFF);
		List<String> result = new ArrayList<String>(Math.max(0,count));
		int offset = 2;
		for(int i=0; i<count; i++){
			if(offset>=b.length){ result.add(""); continue; }
			int len = b[offset]&0xFF;
			offset++;
			int end = Math.min(b.length, offset+len);
			result.add(encoding.decode(b, offset, end-offset));
			offset = end;
		}
		return result.toArray(new String[0]);
	}

	static byte[] encodeStringList(String[] strings, StackTextEncoding encoding){
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int count = strings==null ? 0 : strings.length;
		out.write((count>>>8)&0xFF);
		out.write(count&0xFF);
		if(strings!=null){
			for(String s : strings){
				byte[] bytes = clampToPascalLength(encoding.encodeReplacing(s));
				out.write(bytes.length&0xFF);
				out.write(bytes, 0, bytes.length);
			}
		}
		return out.toByteArray();
	}

	static String decodeText(byte[] b, StackTextEncoding encoding){
		return b==null ? "" : encoding.decode(b);
	}

	static byte[] encodeText(String text, StackTextEncoding encoding){
		return encoding.encodeReplacing(text);
	}

	private static byte[] clampToPascalLength(byte[] bytes){
		if(bytes.length<=MAX_PASCAL_STRING_BYTES) return bytes;
		byte[] clamped = new byte[MAX_PASCAL_STRING_BYTES];
		System.arraycopy(bytes, 0, clamped, 0, MAX_PASCAL_STRING_BYTES);
		return clamped;
	}
}

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Binary data in HyperTalk, with LiveCode's functions (byteToNum, numToByte, binaryEncode, binaryDecode, base64Encode,
 * compress, md5Digest, textEncode …) and HyperZebra's imageToArray / arrayToImage.
 *
 * <p>Binary data is text with one character per byte: byte b is the character U+00bb, except that bytes 13 and 10 are
 * HyperZebra's return and linefeed characters (inside, return is \n and linefeed \r, see numToChar), so
 * {@code byte 1 of data is return} and numToByte(13) = numToChar(13) as in HyperCard. Characters past U+00FF in data that
 * is written as bytes (Japanese text, numToChar(177) on a Japanese stack) become the bytes of the stack's text encoding;
 * the stack encoding's own raw bytes (U+EF00+byte) are those bytes.
 */
final class TalkBinary {
	private TalkBinary() {}

	/** No more than this comes out of decompress or a file read as a whole */
	static final int MAX_BYTES = 512 << 20;

	// ---- byte strings ----

	/** The bytes that binary data (or text) stands for */
	static byte[] bytes(String s) {
		int n = s.length();
		byte[] out = new byte[n];
		int o = 0;
		for(int i=0; i<n; i++) {
			char c = s.charAt(i);
			if(c == '\n') out[o++] = 13;
			else if(c == '\r') out[o++] = 10;
			else if(c <= 0xFF) out[o++] = (byte)c;
			else if(c >= MacJapaneseDecoder.RAW_BYTES && c <= MacJapaneseDecoder.RAW_BYTES+0xFF) out[o++] = (byte)(c - MacJapaneseDecoder.RAW_BYTES);
			else {
				//a run of text characters: the stack's encoding
				int j = i;
				while(j < n && s.charAt(j) > 0xFF && !(s.charAt(j) >= MacJapaneseDecoder.RAW_BYTES && s.charAt(j) <= MacJapaneseDecoder.RAW_BYTES+0xFF)) j++;
				byte[] t = TTalk.fileEncoding().encodeReplacing(s.substring(i, j));
				if(o + t.length + (n - j) > out.length) out = java.util.Arrays.copyOf(out, o + t.length + (n - j));
				System.arraycopy(t, 0, out, o, t.length);
				o += t.length;
				i = j - 1;
			}
		}
		return o == out.length ? out : java.util.Arrays.copyOf(out, o);
	}

	static String string(byte[] b) { return string(b, 0, b.length); }

	/** Binary data for these bytes */
	static String string(byte[] b, int offset, int length) {
		char[] c = new char[length];
		for(int i=0; i<length; i++) {
			int v = b[offset+i] & 0xFF;
			c[i] = v == 13 ? '\n' : v == 10 ? '\r' : (char)v;
		}
		return new String(c);
	}

	static int byteToNum(String s) {
		if(s.isEmpty()) return 0;
		byte[] b = bytes(s.substring(0, Character.charCount(s.codePointAt(0))));
		return b.length == 0 ? 0 : b[0] & 0xFF;
	}

	static String numToByte(String n) throws xTalkException {
		long v = wholeNumber(n);
		return string(new byte[]{ (byte)v });
	}

	private static long wholeNumber(String n) throws xTalkException {
		try { return (long)Math.floor(Double.parseDouble(n.trim())); }
		catch(NumberFormatException e){ throw new xTalkException("A number is required here: {0}", n); }
	}

	/** byteOffset(needle, data [, bytesToSkip]) */
	static int byteOffset(String needle, String data, int skip) {
		if(skip < 0) skip = 0;
		if(skip > data.length()) return 0;
		int i = data.indexOf(needle, skip);
		return i < 0 ? 0 : i - skip + 1;
	}

	// ---- binaryEncode / binaryDecode (LiveCode; Perl's pack) ----

	private static final String TYPES = "aAbBhHcCsSiInNmMfdx";

	/** binaryEncode(formats, value1, value2, …) */
	static String binaryEncode(String[] params) throws xTalkException {
		if(params.length < 1) throw new xTalkException("Wrong number of arguments for {0}: {1}", "binaryEncode", "binaryEncode(format, value1, …)");
		String format = params[0];
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int arg = 1;
		for(int p=0; p<format.length(); ) {
			char type = format.charAt(p++);
			if(Character.isWhitespace(type)) continue;
			if(TYPES.indexOf(type) < 0) throw new xTalkException("binaryEncode: unknown data type {0}", String.valueOf(type));
			int amount = 1;
			boolean all = false;
			if(p < format.length() && format.charAt(p) == '*') { all = true; p++; }
			else if(p < format.length() && Character.isDigit(format.charAt(p))) {
				int q = p;
				while(q < format.length() && Character.isDigit(format.charAt(q))) q++;
				amount = Integer.parseInt(format.substring(p, q));
				p = q;
			}
			if(type == 'x') {
				for(int k=0; k<amount; k++) out.write(0);
				if(arg < params.length) arg++;//its value is not used, but LiveCode asks for one
				continue;
			}
			if("aAbBhH".indexOf(type) >= 0) {
				if(arg >= params.length) throw new xTalkException("binaryEncode: not enough values for the format");
				String v = params[arg++];
				if(type == 'a' || type == 'A') {
					byte[] b = bytes(v);
					int len = all ? b.length : amount;
					for(int k=0; k<len; k++) out.write(k < b.length ? b[k] : type == 'a' ? 0 : ' ');
				}else if(type == 'b' || type == 'B') {
					int len = all ? v.length() : Math.min(amount, v.length());
					int acc = 0, bits = 0;
					for(int k=0; k<len; k++) {
						int bit = v.charAt(k) == '1' ? 1 : 0;
						if(type == 'b') acc |= bit << bits; else acc |= bit << (7 - bits);
						if(++bits == 8) { out.write(acc); acc = 0; bits = 0; }
					}
					if(bits > 0) out.write(acc);
				}else {
					int len = all ? v.length() : Math.min(amount, Math.max(1, v.length()));
					int acc = 0, digits = 0;
					for(int k=0; k<len; k++) {
						int d = k < v.length() ? Character.digit(v.charAt(k), 16) : 0;
						if(d < 0) throw new xTalkException("binaryEncode: not a hexadecimal digit: {0}", String.valueOf(v.charAt(k)));
						if(type == 'h') acc |= d << (digits * 4); else acc |= d << (4 - digits * 4);
						if(++digits == 2) { out.write(acc); acc = 0; digits = 0; }
					}
					if(digits > 0) out.write(acc);
				}
				continue;
			}
			int count = all ? params.length - arg : amount;
			if(arg + count > params.length) throw new xTalkException("binaryEncode: not enough values for the format");
			for(int k=0; k<count; k++) {
				String v = params[arg++].trim();
				double d;
				try { d = Double.parseDouble(v); }
				catch(NumberFormatException e){ throw new xTalkException("binaryEncode: a number is required here: {0}", v); }
				long l = (long)d;
				switch(type) {
					case 'c': case 'C': out.write((int)l); break;
					case 's': case 'S': out.write((int)l); out.write((int)(l >> 8)); break;
					case 'n': case 'm': out.write((int)(l >> 8)); out.write((int)l); break;
					case 'i': case 'I': for(int s=0; s<32; s+=8) out.write((int)(l >> s)); break;
					case 'N': case 'M': for(int s=24; s>=0; s-=8) out.write((int)(l >> s)); break;
					case 'f': { int f = Float.floatToIntBits((float)d); for(int s=0; s<32; s+=8) out.write(f >> s); break; }
					default: { long f = Double.doubleToLongBits(d); for(int s=0; s<64; s+=8) out.write((int)(f >> s)); break; }
				}
			}
		}
		return string(out.toByteArray());
	}

	/**
	 * binaryDecode(formats, data, var1, var2, …): the values for the variables (null: leave that variable, an x), and in
	 * [0] of the second array the function's value (how many values were converted).
	 */
	static String[] binaryDecode(String format, String data, int variables, int[] converted) throws xTalkException {
		byte[] b = bytes(data);
		String[] values = new String[variables];
		int pos = 0, var = 0, count = 0;
		parse:
		for(int p=0; p<format.length() && var<variables; ) {
			char type = format.charAt(p++);
			if(Character.isWhitespace(type)) continue;
			if(TYPES.indexOf(type) < 0) throw new xTalkException("binaryDecode: unknown data type {0}", String.valueOf(type));
			int amount = 1;
			boolean all = false;
			if(p < format.length() && format.charAt(p) == '*') { all = true; p++; }
			else if(p < format.length() && Character.isDigit(format.charAt(p))) {
				int q = p;
				while(q < format.length() && Character.isDigit(format.charAt(q))) q++;
				amount = Integer.parseInt(format.substring(p, q));
				p = q;
			}
			int left = b.length - pos;
			switch(type) {
				case 'x': {
					pos += all ? left : Math.min(amount, left);
					var++;//the variable stays as it is
					continue;
				}
				case 'a': case 'A': {
					int len = all ? left : amount;
					if(len > left) break parse;
					String s = string(b, pos, len);
					if(type == 'A') { int e = s.length(); while(e > 0 && (s.charAt(e-1) == ' ' || s.charAt(e-1) == 0)) e--; s = s.substring(0, e); }
					values[var++] = s; pos += len; count++;
					continue;
				}
				case 'b': case 'B': {
					int bits = all ? left * 8 : amount;
					if((bits + 7) / 8 > left) break parse;
					StringBuilder s = new StringBuilder(bits);
					for(int k=0; k<bits; k++) {
						int v = b[pos + k/8] & 0xFF;
						s.append((type == 'b' ? (v >> (k % 8)) : (v >> (7 - k % 8))) & 1);
					}
					values[var++] = s.toString(); pos += (bits + 7) / 8; count++;
					continue;
				}
				case 'h': case 'H': {
					int digits = all ? left * 2 : amount;
					if((digits + 1) / 2 > left) break parse;
					StringBuilder s = new StringBuilder(digits);
					for(int k=0; k<digits; k++) {
						int v = b[pos + k/2] & 0xFF;
						int d = type == 'h' ? (k % 2 == 0 ? v & 15 : v >> 4) : (k % 2 == 0 ? v >> 4 : v & 15);
						s.append(Character.forDigit(d, 16));
					}
					values[var++] = s.toString(); pos += (digits + 1) / 2; count++;
					continue;
				}
				default: {
					int size = type == 'c' || type == 'C' ? 1 : "sSnm".indexOf(type) >= 0 ? 2 : type == 'd' ? 8 : 4;
					int n = all ? Math.min(left / size, variables - var) : amount;
					for(int k=0; k<n && var<variables; k++) {
						if(b.length - pos < size) break parse;
						values[var++] = number(type, b, pos); pos += size; count++;
					}
				}
			}
		}
		converted[0] = count;
		return values;
	}

	private static String number(char type, byte[] b, int p) {
		long le = 0, be = 0;
		int size = type == 'c' || type == 'C' ? 1 : "sSnm".indexOf(type) >= 0 ? 2 : type == 'd' ? 8 : 4;
		for(int k=0; k<size; k++) { le |= (long)(b[p+k] & 0xFF) << (8*k); be = (be << 8) | (b[p+k] & 0xFF); }
		switch(type) {
			case 'c': return Integer.toString(b[p]);
			case 'C': return Integer.toString(b[p] & 0xFF);
			case 's': return Integer.toString((short)le);
			case 'S': return Long.toString(le);
			case 'n': return Integer.toString((short)be);
			case 'm': return Long.toString(be);
			case 'i': return Integer.toString((int)le);
			case 'I': return Long.toString(le);
			case 'N': return Integer.toString((int)be);
			case 'M': return Long.toString(be);
			case 'f': return TTalk.formatRawNumber(Float.intBitsToFloat((int)le));
			default: return TTalk.formatRawNumber(Double.longBitsToDouble(le));
		}
	}

	// ---- base64, compression, digests ----

	/** As LiveCode: lines of 72 characters */
	static String base64Encode(String data) {
		String s = Base64.getEncoder().encodeToString(bytes(data));
		if(s.length() <= 72) return s;
		StringBuilder out = new StringBuilder(s.length() + s.length()/72);
		for(int i=0; i<s.length(); i+=72) {
			if(i > 0) out.append('\n');
			out.append(s, i, Math.min(s.length(), i+72));
		}
		return out.toString();
	}

	/** Spaces and line breaks are left out; the URL-safe alphabet (- and _) is read too */
	static String base64Decode(String text) throws xTalkException {
		StringBuilder s = new StringBuilder(text.length());
		for(int i=0; i<text.length(); i++) {
			char c = text.charAt(i);
			if(c == '-') s.append('+'); else if(c == '_') s.append('/');
			else if(c > ' ') s.append(c);
		}
		while(s.length() % 4 != 0 && s.charAt(s.length()-1) != '=') s.append('=');
		try { return string(Base64.getDecoder().decode(s.toString())); }
		catch(IllegalArgumentException e){ throw new xTalkException("base64Decode: not base 64 data"); }
	}

	/** gzip, as LiveCode: the bytes GZIPOutputStream writes, with one Deflater reset for each call (a new one made its window
	 *  and hash tables every time: half of the web player's time in a loop of compress(…), Nov 14 2026) */
	static String compress(String data) {
		byte[] in = bytes(data);
		synchronized(GZIP_LOCK) {
			try {
				if(gzipHeader == null) {
					// the header of this Java's GZIPOutputStream (TeaVM's and the JDK's differ in their OS byte)
					ByteArrayOutputStream empty = new ByteArrayOutputStream();
					try(GZIPOutputStream gz = new GZIPOutputStream(empty)) { /* nothing */ }
					gzipHeader = java.util.Arrays.copyOf(empty.toByteArray(), 10);
					gzipDeflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true); // GZIPOutputStream's
					gzipBuffer = new byte[512];
				}
				java.util.zip.Deflater def = gzipDeflater;
				def.reset();
				ByteArrayOutputStream out = new ByteArrayOutputStream(64 + in.length / 2);
				out.write(gzipHeader);
				// as DeflaterOutputStream.write and finish drive it
				def.setInput(in, 0, in.length);
				while(!def.needsInput()) out.write(gzipBuffer, 0, def.deflate(gzipBuffer));
				def.finish();
				while(!def.finished()) out.write(gzipBuffer, 0, def.deflate(gzipBuffer));
				java.util.zip.CRC32 crc = new java.util.zip.CRC32();
				crc.update(in, 0, in.length);
				littleEndian(out, (int) crc.getValue());
				littleEndian(out, in.length);
				return string(out.toByteArray());
			}catch(IOException | RuntimeException e){
				gzipHeader = null; // made again next time
				if(e instanceof IOException io) throw new IllegalStateException(io);
				throw (RuntimeException) e;
			}
		}
	}
	private static final Object GZIP_LOCK = new Object();
	private static byte[] gzipHeader, gzipBuffer;
	private static java.util.zip.Deflater gzipDeflater;

	private static void littleEndian(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF); out.write((v >>> 8) & 0xFF); out.write((v >>> 16) & 0xFF); out.write((v >>> 24) & 0xFF);
	}

	/** gzip data (also zlib) */
	static String decompress(String data) throws xTalkException {
		byte[] b = bytes(data);
		boolean gzip = b.length >= 2 && (b[0] & 0xFF) == 0x1f && (b[1] & 0xFF) == 0x8b;
		try(InputStream in = gzip ? new GZIPInputStream(new ByteArrayInputStream(b)) : new InflaterInputStream(new ByteArrayInputStream(b))) {
			return string(readAll(in));
		}catch(IOException e){
			throw new xTalkException("decompress: not compressed data");
		}
	}

	static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		for(int n; (n = in.read(buf)) > 0; ) {
			out.write(buf, 0, n);
			if(out.size() > MAX_BYTES) throw new IOException("too large");
		}
		return out.toByteArray();
	}

	/** messageDigest(data, "SHA-256"), md5Digest, sha1Digest: binary (binaryDecode("H*", …) gives hexadecimal) */
	static String digest(String data, String algorithm) throws xTalkException {
		String a = algorithm.trim().toUpperCase(Locale.ROOT).replace("_", "-");
		if(a.matches("SHA\\d+")) a = a.equals("SHA1") ? "SHA-1" : "SHA-" + a.substring(3);
		if(a.matches("SHA3\\d+")) a = "SHA3-" + a.substring(4);
		byte[] d = Platform.get().digest(a, bytes(data));
		if(d==null) throw new xTalkException("messageDigest: unknown digest {0}", algorithm);
		return string(d);
	}

	// ---- text encodings ----

	private static final class Encoding {
		StackTextEncoding stack;
		Charset charset;
		boolean macLines;//return is CR in the bytes (Mac encodings), else LF
	}

	private static Encoding encoding(String name) throws xTalkException {
		String n = name.trim().toLowerCase(Locale.ROOT).replace("_", "-");
		Encoding e = new Encoding();
		switch(n) {
			case "": case "native": e.stack = TTalk.fileEncoding(); e.macLines = true; return e;
			case "macroman": case "mac": case "x-macroman": e.stack = StackTextEncoding.ROMAN; e.macLines = true; return e;
			case "shift-jis": case "sjis": case "macjapanese": case "x-macjapanese": case "japanese":
				e.stack = StackTextEncoding.JAPANESE; e.macLines = true; return e;
			case "utf8": n = "utf-8"; break;
			case "utf-16": case "utf16": n = "utf-16le"; break;//LiveCode: the host's byte order
			case "utf-32": case "utf32": n = "utf-32le"; break;
			case "ascii": n = "us-ascii"; break;
			case "cp1252": n = "windows-1252"; break;
			case "latin1": n = "iso-8859-1"; break;
			default:
		}
		try { e.charset = Platform.get().charset(n); }
		catch(IllegalArgumentException ex){ throw new xTalkException("Unknown text encoding: {0}", name); }
		e.macLines = n.startsWith("x-mac");
		return e;
	}

	/** textEncode(text, encoding): return becomes CR for Mac encodings ("native", MacRoman, Shift-JIS) and LF for others */
	static String textEncode(String text, String encodingName) throws xTalkException {
		Encoding e = encoding(encodingName);
		StringBuilder t = new StringBuilder(text.length());
		for(int i=0; i<text.length(); i++) {
			char c = text.charAt(i);
			if(c == '\n') t.append(e.macLines ? '\r' : '\n');
			else if(c == '\r') t.append('\n');
			else t.append(c);
		}
		byte[] b = e.stack != null ? e.stack.encodeReplacing(t.toString()) : t.toString().getBytes(e.charset);
		return string(b);
	}

	/** textDecode(data, encoding): CR, LF and CR LF all become return; a byte-order mark is left out */
	static String textDecode(String data, String encodingName) throws xTalkException {
		Encoding e = encoding(encodingName);
		byte[] b = bytes(data);
		String s = e.stack != null ? e.stack.decode(b, 0, b.length) : new String(b, e.charset);
		if(!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
		return s.replace("\r\n", "\n").replace('\r', '\n');
	}

	// ---- images ----

	/** The picture in image file data (PNG, JPEG, GIF, BMP, TIFF, PICT), or null */
	static BufferedImage image(byte[] b) {
		try {
			BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(b));
			if(img != null) return img;
		}catch(IOException | RuntimeException e){ /* try PICT */ }
		if(b.length > 512 + 10) {
			BufferedImage img = PictureFile.loadPICT(java.util.Arrays.copyOfRange(b, 512, b.length));//a PICT file
			if(img != null) return img;
		}
		return b.length > 10 ? PictureFile.loadPICT(b) : null;//a PICT resource's data
	}

	/** imageToArray(data): rows from the top, each a list of "r,g,b,a" pixels from the left (0-255), so px[y][x] */
	static String imageToArray(String data) throws xTalkException { return UiHost.get().imageToArray(data); }

	/** arrayToImage(px [, "png"|"jpeg"|"gif"|"bmp"]): pixels "r,g,b" or "r,g,b,a" (or [r, g, b, a]); missing ones are clear */
	static String arrayToImage(String array, String format) throws xTalkException { return UiHost.get().arrayToImage(array, format); }

	/** "r,g,b[,a]" or a collection [r, g, b, a] into c (0-255); empty is clear */
	static void pixel(String p, int[] c) throws xTalkException {
		c[0] = c[1] = c[2] = 0; c[3] = 255;
		if(p.isEmpty()) { c[3] = 0; return; }
		if(TalkCollection.isCollection(p)) {
			TalkCollection t = TalkCollection.decode(p);
			if(t.isArray()) for(int k=0; k<4 && k<t.items.size(); k++) c[k] = component(t.items.get(k));
			return;
		}
		int k = 0, start = 0;
		for(int i=0; i<=p.length() && k<4; i++) {
			if(i == p.length() || p.charAt(i) == ',') { c[k++] = component(p.substring(start, i)); start = i+1; }
		}
	}

	private static int component(String s) throws xTalkException {
		s = s.trim();
		int v = 0, i = 0;
		boolean plain = !s.isEmpty() && s.length() <= 3;
		for(; plain && i<s.length(); i++) { char ch = s.charAt(i); if(ch < '0' || ch > '9') plain = false; else v = v*10 + ch - '0'; }
		if(!plain) {
			try { v = (int)Math.round(Double.parseDouble(s)); }
			catch(NumberFormatException e){ throw new xTalkException("arrayToImage: a pixel needs numbers like 255,0,0,255: {0}", s); }
		}
		return Math.max(0, Math.min(255, v));
	}
}

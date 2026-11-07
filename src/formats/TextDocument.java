import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A text document of a classic Mac - a ReadMe, a SimpleText/TeachText 'TEXT' or read-only 'ttro' file, a .txt - read with
 * the encoding it was written in. A modern host reads such a file as UTF-8 and shows Shift-JIS or Mac OS Roman as garbage;
 * HyperZebra's text viewer (GTextDocumentWindow) shows it with this class instead.
 *
 * The encoding is guessed from the bytes ({@link #detect}): a byte order mark, ISO-2022-JP's escapes, valid UTF-8, EUC-JP
 * with kana, then the scoring a HyperCard stack's import uses ({@link StackEncodingDetector#fromText}: Shift-JIS, Mac OS
 * Roman, the Chinese and Korean encodings, the one-byte scripts), else the encoding of the user's language. A SimpleText
 * document's 'styl' 128 resource gives its style runs (font, size, face, colour) and its PICT 1000, 1001... resources are
 * the pictures shown at its option-space characters. A text clipping (a 'clpt' file the Finder makes from dragged text) has
 * an empty data fork: its text is the 'TEXT' 256 resource with the 'styl' 256 runs, or a Mac OS X clipping's 'utf8' or
 * 'utxt' 256. A picture clipping ('clpp') is shown in the viewer as well: no text, its 'PICT' 256 as the one picture.
 */
final class TextDocument {
	/** Larger files are not read as text documents (the drop goes on to the usual ways). */
	static final long MAX_SIZE = 32L<<20;
	/** A text up to this long is too short for {@link #detect(byte[])} alone: the file's name helps. */
	private static final int SHORT = 256;
	/** The first bytes looked at by {@link #looksLikeText} and {@link #detect}. */
	private static final int SAMPLE = 1<<20;

	static final int TEXT = ResourceFile68k.fourcc("TEXT"), TTRO = ResourceFile68k.fourcc("ttro"), STYL = ResourceFile68k.fourcc("styl"), PICT = ResourceFile68k.fourcc("PICT"),
		CLPT = ResourceFile68k.fourcc("clpt"), CLPP = ResourceFile68k.fourcc("clpp"), UTF8 = ResourceFile68k.fourcc("utf8"), UTXT = ResourceFile68k.fourcc("utxt");
	/** The resource ID of a clipping's flavours. */
	static final int CLIPPING_ID = 256;
	/** SimpleText puts its pictures (PICT 1000 on) where the text has an option-space character. */
	static final int FIRST_PICTURE = 1000;

	/** An encoding the viewer offers: a name to show (English, translated by the caller) and how it decodes. */
	static final class Encoding {
		final String name;
		private final StackTextEncoding mac;
		private final Charset charset;
		private Encoding(String name, StackTextEncoding mac, String charset){ this.name = name; this.mac = mac; this.charset = charset==null ? null : Charset.forName(charset); }
		String decode(byte[] b, int from, int to){
			if(to<=from) return "";
			return mac!=null ? mac.decode(b, from, to-from) : new String(b, from, to-from, charset);
		}
		/** Whether one style run can be decoded alone (ISO-2022-JP's shift state crosses runs; UTF-16 pairs bytes). */
		boolean byteOriented(){ return mac!=null || charset.name().equals("UTF-8") || charset.name().equals("EUC-JP"); }
		/** Whether a lone byte $CA is a character of its own (Mac OS Roman's option-space; Shift-JIS half-width ﾊ). */
		boolean macScript(){ return mac!=null; }
		@Override public String toString(){ return name; }
	}
	/** Every encoding the viewer offers, in the order of its menu. */
	static final List<Encoding> ENCODINGS = new ArrayList<Encoding>();
	static final Encoding SHIFT_JIS = new Encoding("Japanese (Shift-JIS)", StackTextEncoding.JAPANESE, null),
		EUC_JP = new Encoding("Japanese (EUC-JP)", null, "EUC-JP"),
		ISO_2022_JP = new Encoding("Japanese (ISO-2022-JP)", null, "ISO-2022-JP"),
		UTF_8 = new Encoding("Unicode (UTF-8)", null, "UTF-8"),
		UTF_16 = new Encoding("Unicode (UTF-16)", null, "UTF-16"),
		UTF_16LE = new Encoding("Unicode (UTF-16 Little-Endian)", null, "UTF-16LE"),
		ROMAN = of(StackTextEncoding.ROMAN);
	static{
		ENCODINGS.add(SHIFT_JIS); ENCODINGS.add(EUC_JP); ENCODINGS.add(ISO_2022_JP); ENCODINGS.add(ROMAN);
		ENCODINGS.add(UTF_8); ENCODINGS.add(UTF_16); ENCODINGS.add(UTF_16LE);
		for(StackTextEncoding e : StackTextEncoding.values()) if(e!=StackTextEncoding.JAPANESE && e!=StackTextEncoding.ROMAN) ENCODINGS.add(of(e));
	}
	private static Encoding of(StackTextEncoding e){
		for(Encoding known : ENCODINGS) if(known.mac==e) return known;
		return new Encoding(e.displayName, e, null);
	}

	/** A style run of a 'styl' resource: from this byte of the text on, until the next run. */
	static final class Run {
		final int start, font, face, size; final java.awt.Color color;
		Run(int start, int font, int face, int size, java.awt.Color color){ this.start = start; this.font = font; this.face = face; this.size = size; this.color = color; }
	}

	final File file;
	final byte[] data;
	/** The 'styl' 128 runs, empty for plain text. */
	final List<Run> runs;
	/** PICT 1000, 1001... as the resources hold them (no 512-byte header), in order. */
	final List<byte[]> pictures;
	final Encoding detected;

	private TextDocument(File file, byte[] data, List<Run> runs, List<byte[]> pictures){
		this.file = file; this.data = data; this.runs = runs; this.pictures = pictures;
		this.detected = detect(data, file.getName());
	}

	static TextDocument read(File f) throws IOException {
		byte[] data = Files.readAllBytes(f.toPath());
		List<Run> runs = new ArrayList<Run>();
		List<byte[]> pictures = new ArrayList<byte[]>();
		try{
			ResourceFile68k rf = ResourceFile68k.open(f);
			int stylId = 128;
			byte[] clipping = data.length==0 ? clippingText(rf) : null;
			if(clipping!=null){ data = clipping; stylId = CLIPPING_ID; }
			else if(data.length==0 && rf.get(PICT, CLIPPING_ID)!=null){ pictures.add(rf.get(PICT, CLIPPING_ID).data()); return new TextDocument(f, data, runs, pictures); }
			ResourceFile68k.Resource styl = rf.get(STYL, stylId);
			if(styl!=null && (clipping==null || rf.get(TEXT, CLIPPING_ID)!=null)) runs = runs(styl.data(), data.length);
			for(int id=FIRST_PICTURE; ; id++){
				ResourceFile68k.Resource p = rf.get(PICT, id);
				if(p==null) break;
				pictures.add(p.data());
			}
		}catch(IOException | RuntimeException e){ /* no resource fork, or one that cannot be read: plain text */ }
		return new TextDocument(f, data, runs, pictures);
	}

	/**
	 * A text clipping's text: the 'TEXT' 256 resource (in the system's script, with 'styl' 256 for it), else a Mac OS X
	 * clipping's 'utf8' 256, else its 'utxt' 256 (UTF-16, given a byte order mark so it is read as such); null without any.
	 */
	private static byte[] clippingText(ResourceFile68k rf){
		ResourceFile68k.Resource r = rf.get(TEXT, CLIPPING_ID);
		if(r==null) r = rf.get(UTF8, CLIPPING_ID);
		if(r!=null) return r.data();
		r = rf.get(UTXT, CLIPPING_ID);
		if(r==null) return null;
		byte[] u = r.data();
		if(u.length>=2 && ((u[0]==(byte)0xFE && u[1]==(byte)0xFF) || (u[0]==(byte)0xFF && u[1]==(byte)0xFE))) return u;
		byte[] b = new byte[u.length+2];
		b[0] = (byte)0xFE; b[1] = (byte)0xFF;
		System.arraycopy(u, 0, b, 2, u.length);
		return b;
	}
	/** Whether the file's resources hold a clipping's text ({@link #clippingText}). */
	private static boolean hasClippingText(File f){
		try{ return clippingText(ResourceFile68k.open(f))!=null; }
		catch(IOException | RuntimeException e){ return false; }
	}

	/** Whether the file's resources hold a picture clipping's 'PICT' 256. */
	private static boolean hasClippingPicture(File f){
		try{ return ResourceFile68k.open(f).get(PICT, CLIPPING_ID)!=null; }
		catch(IOException | RuntimeException e){ return false; }
	}
	/** A picture clipping: no text, one picture. */
	boolean pictureOnly(){ return data.length==0 && !pictures.isEmpty(); }

	/** The runs of a TextEdit style scrap (StScrpRec: count, then 20-byte ScrpSTElements), in order and inside the text. */
	static List<Run> runs(byte[] styl, int textLength){
		List<Run> r = new ArrayList<Run>();
		if(styl==null || styl.length<2) return r;
		int n = u16(styl, 0);
		for(int i=0; i<n && 2+i*20+20<=styl.length; i++){
			int at = 2+i*20, start = (int)Math.min(textLength, Math.max(0, u32(styl, at)));
			if(!r.isEmpty() && start<=r.get(r.size()-1).start){ if(start<r.get(r.size()-1).start) continue; r.remove(r.size()-1); }
			java.awt.Color color = new java.awt.Color(u16(styl, at+14)>>8, u16(styl, at+16)>>8, u16(styl, at+18)>>8);
			r.add(new Run(start, (short)u16(styl, at+8), styl[at+10]&0xFF, u16(styl, at+12), color));
		}
		return r;
	}
	private static int u16(byte[] b, int at){ return (b[at]&0xFF)<<8 | (b[at+1]&0xFF); }
	private static long u32(byte[] b, int at){ return ((long)u16(b, at)<<16) | u16(b, at+2); }

	/**
	 * Whether a dropped file is shown as a text document: a 'TEXT' or 'ttro' file (by its Finder info, or the type its
	 * name gives - .txt), or a file without a type that reads as text and is named like one (no extension, a text
	 * extension, or "Read Me"-like), or a text clipping.
	 */
	static boolean isTextDocument(File f, int type){
		if(f==null || !f.isFile() || f.length()>MAX_SIZE) return false;
		if(type==TEXT || type==TTRO) return true;
		if(type==CLPT || type==InternetLocation.URL_CLIPPING) return f.length()==0 && hasClippingText(f); // 'clpT' when its text is no URL
		if(type==CLPP) return f.length()==0 && hasClippingPicture(f);
		if(type!=0 || f.length()==0 || !textName(f.getName())) return false;
		try(java.io.InputStream in = Files.newInputStream(f.toPath())){ return looksLikeText(in.readNBytes(SAMPLE)); }
		catch(IOException e){ return false; }
	}
	private static final String[] TEXT_EXTENSIONS = {"txt", "text", "me", "1st", "doc", "readme", "nfo", "diz", "asc", "log", "md", "jp", "j", "e", "en", "ja", "sjis", "euc", "jis", "utf8"};
	private static boolean textName(String name){
		String n = name.toLowerCase(Locale.ROOT);
		if(n.contains("read") || name.contains("お読み") || name.contains("はじめに") || name.contains("説明") || name.contains("注意")) return true;
		int dot = n.lastIndexOf('.');
		if(dot<=0 || dot>=n.length()-1 || n.length()-dot>8) return true; // no extension (a Mac file name), or a dot inside a phrase
		String ext = n.substring(dot+1);
		for(String e : TEXT_EXTENSIONS) if(e.equals(ext)) return true;
		return false;
	}
	/** No NUL bytes (but UTF-16 with its mark) and hardly any other control characters than tab, line ends, form feed and ESC. */
	static boolean looksLikeText(byte[] b){
		if(b.length>=2 && ((b[0]==(byte)0xFE && b[1]==(byte)0xFF) || (b[0]==(byte)0xFF && b[1]==(byte)0xFE))) return true;
		int odd = 0;
		for(int i=0; i<b.length; i++){
			int c = b[i]&0xFF;
			if(c==0) return false;
			if(c<0x20 && c!='\t' && c!='\n' && c!='\r' && c!='\f' && c!=0x1B && !(c==0x1A && i==b.length-1)) odd++;
		}
		return odd<=Math.max(1, b.length/200);
	}

	/** The encoding the bytes were most likely written in. */
	static Encoding detect(byte[] data){
		int n = Math.min(data.length, SAMPLE);
		if(n>=3 && (data[0]&0xFF)==0xEF && (data[1]&0xFF)==0xBB && (data[2]&0xFF)==0xBF) return UTF_8;
		if(n>=2 && (data[0]&0xFF)==0xFE && (data[1]&0xFF)==0xFF) return UTF_16;
		if(n>=2 && (data[0]&0xFF)==0xFF && (data[1]&0xFF)==0xFE) return UTF_16LE;
		boolean high = false;
		for(int i=0; i<n && !high; i++) if(data[i]<0) high = true;
		if(!high) return iso2022(data, n) ? ISO_2022_JP : ROMAN;
		if(strict(data, n, "UTF-8")) return UTF_8;
		if(eucJapanese(data, n)) return EUC_JP;
		List<byte[]> lines = byteLines(data, n);
		StackTextEncoding guess = StackEncodingDetector.fromText(lines);
		if(guess==null && StackEncodingDetector.validShiftJis(lines) && japanese(SHIFT_JIS.decode(data, 0, n))>=2) guess = StackTextEncoding.JAPANESE;
		if(guess==null) guess = StackTextEncoding.forLegacyLanguageSetting(PCARD.language);
		return of(guess);
	}
	/**
	 * The encoding, with the file's name as a hint: a text too short to tell (a clipping of a few characters - "き,(`_')")
	 * reads as Shift-JIS when it is valid Shift-JIS with Japanese in it and the file has a Japanese name.
	 */
	static Encoding detect(byte[] data, String name){
		Encoding e = detect(data);
		if(e==SHIFT_JIS || e.mac==null || data.length>SHORT || name==null || !japaneseName(name)) return e;
		int n = Math.min(data.length, SAMPLE);
		boolean high = false;
		for(int i=0; i<n && !high; i++) if(data[i]<0) high = true;
		if(!high || !StackEncodingDetector.validShiftJis(byteLines(data, n))) return e;
		String sjis = SHIFT_JIS.decode(data, 0, n);
		for(int i=0; i<sjis.length(); i++){ char c = sjis.charAt(i); if(c>=0x3041 && c<=0x30FE || c>=0x4E00 && c<=0x9FFF) return SHIFT_JIS; }
		return e;
	}
	/** A name with kana or kanji in it. */
	private static boolean japaneseName(String name){
		for(int i=0; i<name.length(); i++){ char c = name.charAt(i); if(c>=0x3041 && c<=0x30FE || c>=0x4E00 && c<=0x9FFF) return true; }
		return false;
	}
	private static List<byte[]> byteLines(byte[] data, int n){
		List<byte[]> lines = new ArrayList<byte[]>();
		for(int i=0, start=0; i<=n; i++){
			if(i==n || data[i]=='\r' || data[i]=='\n'){ if(i>start) lines.add(java.util.Arrays.copyOfRange(data, start, i)); start = i+1; }
		}
		return lines;
	}
	/** 7-bit text that switches to JIS X 0208 (ESC $ B, ESC $ @) - ISO-2022-JP, the encoding of Japanese mail and news. */
	private static boolean iso2022(byte[] b, int n){
		for(int i=0; i+2<n; i++) if(b[i]==0x1B && b[i+1]=='$' && (b[i+2]=='B' || b[i+2]=='@')) return true;
		return false;
	}
	private static boolean strict(byte[] b, int n, String charset){
		try{
			Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(b, 0, cutAtCharacter(b, n, charset)));
			return true;
		}catch(CharacterCodingException e){ return false; }
	}
	/** The sample ends inside a character when the file is longer: cut before the last few high bytes. */
	private static int cutAtCharacter(byte[] b, int n, String charset){
		if(n==b.length) return n;
		int end = n;
		while(end>0 && n-end<4 && b[end-1]<0) end--;
		return end;
	}
	/**
	 * EUC-JP: valid as such, with full-width kana among its characters (Japanese writes particles in hiragana; the
	 * Chinese and Korean EUC encodings hardly use the rows that are kana in EUC-JP), and more of them than the same bytes
	 * give in Shift-JIS (there EUC-JP's bytes are mostly half-width katakana).
	 */
	private static boolean eucJapanese(byte[] b, int n){
		if(!strict(b, n, "EUC-JP")) return false;
		String euc = EUC_JP.decode(b, 0, cutAtCharacter(b, n, "EUC-JP"));
		int kana = japanese(euc), wide = 0;
		for(int i=0; i<euc.length(); i++) if(euc.charAt(i)>=0x3000) wide++;
		return kana>=2 && kana*10>=wide && kana>japanese(SHIFT_JIS.decode(b, 0, n));
	}
	/** The full-width hiragana and katakana in the text. */
	private static int japanese(String s){
		int k = 0;
		for(int i=0; i<s.length(); i++){ char c = s.charAt(i); if(c>=0x3041 && c<=0x30FE) k++; }
		return k;
	}

	/** The text with the line ends of the host (a Mac's CR, DOS's CR LF and Unix's LF all become LF). */
	static String lines(String s){ return s.indexOf('\r')<0 ? s : s.replace("\r\n", "\n").replace('\r', '\n'); }

	/** The whole text in this encoding, with LF line ends (a byte order mark is left out). */
	String text(Encoding e){
		String s = lines(e.decode(data, 0, data.length));
		return s.startsWith("﻿") ? s.substring(1) : s;
	}
}

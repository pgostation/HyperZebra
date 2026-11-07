import java.nio.charset.StandardCharsets;

/**
 * Host text files as a hosted program sees them. A 68K program reads and writes text as bytes of its own
 * encoding (MacJapanese, Mac OS Roman) with CR line ends; today's editors save UTF-8 with LF. The File Manager
 * hands such a file to the program as a converted temp copy (like the data fork of a MacBinary container) and,
 * when the program wrote it, converts it back into the form the host file had: UTF-8 stays UTF-8 and its line
 * ends stay what they were, a Shift-JIS file stays Shift-JIS. A text file the program created is written as
 * UTF-8 with LF when {@link FileManager68k#utf8TextFiles} says so, so the host's own programs can read it.
 */
final class TextFile68k {
	/** Larger files are handed over as they are: text this size is not what the conversion is for. */
	static final long LIMIT=16L<<20;
	private static final byte[] BOM={(byte)0xef,(byte)0xbb,(byte)0xbf};

	/** The form of the host file, to restore on write-back: UTF-8 (with or without a byte-order mark) or the legacy encoding, and its line end. */
	static final class Form {
		final boolean utf8,bom;final String newline;
		Form(boolean utf8,boolean bom,String newline){this.utf8=utf8;this.bom=bom;this.newline=newline;}
	}

	private TextFile68k(){}

	/**
	 * The form of {@code host} when the program needs a converted copy, else null: the bytes are not text (a NUL,
	 * an empty file), or are already in the program's form (no UTF-8, CR line ends). {@code typed}: the file is
	 * known to be text ('TEXT'); an untyped file converts only on the strong signal of multi-byte UTF-8, so a
	 * plain ASCII data file with LF line ends is left alone.
	 */
	static Form sniff(byte[] host,boolean typed){
		if(host.length==0)return null;
		for(byte b:host)if(b==0)return null;
		boolean bom=host.length>=3&&host[0]==BOM[0]&&host[1]==BOM[1]&&host[2]==BOM[2];
		boolean utf8=bom||utf8MultiByte(host);
		if(!typed&&!utf8)return null;
		String newline=newlineOf(host);
		if(newline==null)newline=utf8?"\n":"\r"; // no line end yet: a UTF-8 file gets today's, a legacy file the Mac's
		if(!utf8&&newline.equals("\r"))return null;
		if(!utf8&&mostlyNotText(host))return null;
		return new Form(utf8,bom,newline);
	}

	/**
	 * Data in a file typed 'TEXT': control bytes in more than one byte of a hundred (ESC and ^Z do not count: ISO-2022-JP text, DOS
	 * ends). Al Unser Jr. keeps its pictures in TEXT files; two without a NUL got their $0A pixels turned into $0D.
	 */
	private static boolean mostlyNotText(byte[] b){
		int control=0;
		for(byte x:b){int c=x&0xff;if(c<0x20&&c!='\t'&&c!='\n'&&c!='\r'&&c!=0x0c&&c!=0x1a&&c!=0x1b)control++;}
		return control>b.length/100;
	}

	/** Well-formed UTF-8 containing at least one multi-byte sequence (MacJapanese or Mac OS Roman text is practically never that). */
	static boolean utf8MultiByte(byte[] b){
		boolean multi=false;
		for(int i=0;i<b.length;){
			int c=b[i]&0xff;
			if(c<0x80){i++;continue;}
			int n=(c&0xe0)==0xc0?1:(c&0xf0)==0xe0?2:(c&0xf8)==0xf0?3:-1;
			if(n<0||i+n>=b.length)return false;
			for(int k=1;k<=n;k++)if((b[i+k]&0xc0)!=0x80)return false;
			i+=n+1;multi=true;
		}
		return multi;
	}

	/** The first line end's form (CRLF, LF or CR), or null when the file has none. */
	private static String newlineOf(byte[] b){
		for(int i=0;i<b.length;i++){
			if(b[i]=='\n')return "\n";
			if(b[i]=='\r')return i+1<b.length&&b[i+1]=='\n'?"\r\n":"\r";
		}
		return null;
	}

	/** {@code host} converted to the program's encoding with CR line ends. */
	static byte[] toProgram(byte[] host,Form form,StackTextEncoding encoding){
		if(form.utf8){
			int start=form.bom?BOM.length:0;
			String text=new String(host,start,host.length-start,StandardCharsets.UTF_8);
			return encoding.encodeReplacing(text.replace("\r\n","\r").replace('\n','\r'));
		}
		return lineEndsToCr(host); // legacy bytes: LF and CR are never trail bytes, so the swap is safe
	}

	/**
	 * {@code program} bytes (the program's encoding, CR line ends) converted back into the host file's form. Bytes the
	 * encoding cannot decode are not text the program meant as such: they go to the host unchanged.
	 */
	static byte[] toHost(byte[] program,Form form,StackTextEncoding encoding){
		if(form.utf8){
			String text=encoding.decode(program);
			if(text.indexOf('\uFFFD')>=0)return program;
			byte[] out=text.replace("\r\n","\r").replace("\r",form.newline).getBytes(StandardCharsets.UTF_8);
			if(!form.bom)return out;
			byte[] withBom=new byte[out.length+BOM.length];System.arraycopy(BOM,0,withBom,0,BOM.length);System.arraycopy(out,0,withBom,BOM.length,out.length);return withBom;
		}
		return crToLineEnds(program,form.newline);
	}

	/**
	 * A file the program created, as UTF-8 with LF line ends; null when the bytes are not text worth converting, or when
	 * the program would not read its own bytes back: \u4F55\u304Bon\u6797\u6A8E decrypts its dictionaries into a TEXT temp file with CRLF
	 * line ends and parses what it reads back - CR-only lines made every entry lookup fail. Japanese text a Roman program
	 * writes (an English expander unpacking a Japanese ReadMe) is no Mac OS Roman text either: it keeps its Shift-JIS bytes.
	 */
	static byte[] toHostNew(byte[] program,StackTextEncoding encoding){
		if(!looksLikeText(program))return null;
		if(encoding==StackTextEncoding.ROMAN&&StackEncodingDetector.fromText(java.util.Collections.singletonList(program))==StackTextEncoding.JAPANESE)return null;
		String text=encoding.decode(program);
		if(text.indexOf('\uFFFD')>=0)return null;
		byte[] host=text.replace("\r\n","\n").replace('\r','\n').getBytes(StandardCharsets.UTF_8);
		Form form=sniff(host,true);
		if(!java.util.Arrays.equals(form==null?host:toProgram(host,form,encoding),program))return null;
		return host;
	}

	/** Text as a program writes it: no control characters but tab, line ends and form feed. */
	static boolean looksLikeText(byte[] b){
		if(b.length==0)return false;
		for(byte x:b){int c=x&0xff;if(c<0x20&&c!='\t'&&c!='\n'&&c!='\r'&&c!=0x0c)return false;}
		return true;
	}

	/** Every line end (CR, LF or CRLF) as CR. */
	private static byte[] lineEndsToCr(byte[] in){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(in.length);
		for(int i=0;i<in.length;i++){
			if(in[i]=='\r'){out.write('\r');if(i+1<in.length&&in[i+1]=='\n')i++;}
			else if(in[i]=='\n')out.write('\r');
			else out.write(in[i]);
		}
		return out.toByteArray();
	}
	/** Every CR (or CRLF) as {@code newline}. */
	private static byte[] crToLineEnds(byte[] in,String newline){
		byte[] t=newline.getBytes(StandardCharsets.US_ASCII);
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(in.length+in.length/16);
		for(int i=0;i<in.length;i++){
			if(in[i]=='\r'){out.write(t,0,t.length);if(i+1<in.length&&in[i+1]=='\n')i++;}
			else out.write(in[i]);
		}
		return out.toByteArray();
	}
}

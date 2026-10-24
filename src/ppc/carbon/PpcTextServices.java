import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Text Encoding Converter, the Unicode Converter and TextCommon for PowerPC programs: a CarbonLib program may take
 * them for granted, since every system CarbonLib ran on had them, and REALbasic's Carbon runtime (STRuntime) calls them
 * without checking its weak imports (コプリン, むりやりMusic jumped to address 0 at start-up).
 * <p>
 * Text encodings are Java charsets: the Mac OS encodings through {@link StackTextEncoding} (MacJapanese with the host's
 * own tables), the Internet, ISO, DOS and Windows ones through the JDK. Every conversion goes through Unicode, so any
 * encoding of the table converts to any other. The sniffers score candidate encodings by how well the bytes parse in
 * each (errors, then the bytes of well-formed multi-byte or high characters as evidence). ATSUI lives in
 * {@link PpcAtsui}, which registers itself as {@link #atsui}.
 */
final class PpcTextServices {
	private final PpcRuntime rt;private final M68kMemory memory;
	PpcTextServices(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	/** ATSUI and the Font Manager's font access (set by {@link PpcAtsui#define}). */
	PpcAtsui atsui;

	static final int NO_ERR=0,PARAM_ERR=-50,OUTPUT_BUFFER_FULL=-8785,UNSUPPORTED_ENCODING=-8738,ARRAY_FULL=-8751,ITEM_UNAVAILABLE=-8771;
	static final int UNICODE_DEFAULT=0x100,UTF8=0x08000100,UTF32=0x0c000100;

	// ------------------------------------------------------------------ text encodings

	private boolean japaneseSystem(){return rt.toolbox.encoding==StackTextEncoding.JAPANESE;}
	private static boolean unicode(int encoding){int base=encoding&0xffff;return base>=0x100&&base<=0x1ff;}
	private static int format(int encoding){return (encoding>>>26)&0x3f;}
	private static Charset unicodeCharset(int encoding){int f=format(encoding);return f==2?StandardCharsets.UTF_8:f==3?Charset.forName("UTF-32BE"):StandardCharsets.UTF_16BE;}
	/** The text of {@code bytes} in {@code encoding}. */
	String decode(int encoding,byte[] bytes){
		int base=encoding&0xffff;
		if(unicode(encoding))return new String(bytes,unicodeCharset(encoding));
		if(base==0x0a01&&japaneseSystem())return StackTextEncoding.JAPANESE.decode(bytes);
		StackTextEncoding mac=macEncoding(base);if(mac!=null)return mac.decode(bytes);
		Charset c=charset(base);return c!=null?new String(bytes,c):rt.toolbox.encoding.decode(bytes);
	}
	/** {@code text} in {@code encoding} (characters it lacks become '?'). */
	byte[] encode(int encoding,String text){
		int base=encoding&0xffff;
		if(unicode(encoding))return text.getBytes(unicodeCharset(encoding));
		if(base==0x0a01&&japaneseSystem())return StackTextEncoding.JAPANESE.encodeReplacing(text);
		StackTextEncoding mac=macEncoding(base);if(mac!=null)return mac.encodeReplacing(text);
		Charset c=charset(base);return c!=null?text.getBytes(c):rt.toolbox.encoding.encodeReplacing(text);
	}
	/** Whether {@code encoding} has every character of {@code text}. */
	boolean canEncode(int encoding,String text){
		if(unicode(encoding))return true;
		int base=encoding&0xffff;StackTextEncoding mac=base==0x0a01&&japaneseSystem()?StackTextEncoding.JAPANESE:macEncoding(base);
		try{if(mac!=null){mac.encode(text);return true;}}catch(CharacterCodingException e){return false;}
		Charset c=charset(base);return c!=null&&c.canEncode()&&c.newEncoder().canEncode(text);
	}
	/** The Mac OS encodings (TextEncodingBase = script code, plus the Roman variants), or null. */
	private static StackTextEncoding macEncoding(int base){
		switch(base){
			case 0:return StackTextEncoding.ROMAN;case 1:return StackTextEncoding.JAPANESE;case 2:return StackTextEncoding.TRADITIONAL_CHINESE;
			case 3:return StackTextEncoding.KOREAN;case 4:return StackTextEncoding.ARABIC;case 5:return StackTextEncoding.HEBREW;case 6:return StackTextEncoding.GREEK;
			case 7:return StackTextEncoding.CYRILLIC;case 21:return StackTextEncoding.THAI;case 25:return StackTextEncoding.SIMPLIFIED_CHINESE;
			case 29:return StackTextEncoding.CENTRAL_EUROPE;case 35:return StackTextEncoding.TURKISH;case 36:return StackTextEncoding.CROATIAN;
			case 37:return StackTextEncoding.ICELANDIC;case 38:return StackTextEncoding.ROMANIAN;case 0x98:return StackTextEncoding.UKRAINIAN;
			default:return null;
		}
	}
	/**
	 * The encodings of this converter: {TextEncodingBase, JDK charset (null: a Mac OS encoding of {@link #macEncoding}),
	 * the name GetTextEncodingName gives}. Unicode (UTF-16, UTF-8, UTF-32) comes on top.
	 */
	private static final Object[][] TABLE={
		{0x0000,null,"Mac OS Roman"},{0x0001,null,"Mac OS Japanese"},{0x0002,null,"Mac OS Chinese Traditional"},{0x0003,null,"Mac OS Korean"},
		{0x0004,null,"Mac OS Arabic"},{0x0005,null,"Mac OS Hebrew"},{0x0006,null,"Mac OS Greek"},{0x0007,null,"Mac OS Cyrillic"},
		{0x0015,null,"Mac OS Thai"},{0x0019,null,"Mac OS Chinese Simplified"},{0x001d,null,"Mac OS Central European"},
		{0x0021,"x-MacSymbol","Mac OS Symbol"},{0x0022,"x-MacDingbat","Mac OS Dingbats"},{0x0023,null,"Mac OS Turkish"},{0x0024,null,"Mac OS Croatian"},
		{0x0025,null,"Mac OS Icelandic"},{0x0026,null,"Mac OS Romanian"},{0x0098,null,"Mac OS Ukrainian"},
		{0x0201,"ISO-8859-1","ISO Latin 1"},{0x0202,"ISO-8859-2","ISO Latin 2"},{0x0203,"ISO-8859-3","ISO Latin 3"},{0x0204,"ISO-8859-4","ISO Latin 4"},
		{0x0205,"ISO-8859-5","ISO Cyrillic"},{0x0206,"ISO-8859-6","ISO Arabic"},{0x0207,"ISO-8859-7","ISO Greek"},{0x0208,"ISO-8859-8","ISO Hebrew"},
		{0x0209,"ISO-8859-9","ISO Latin 5"},{0x020d,"ISO-8859-13","ISO Latin 7"},{0x020f,"ISO-8859-15","ISO Latin 9"},
		{0x0400,"IBM437","DOS Latin US"},{0x0405,"x-IBM737","DOS Greek"},{0x0406,"IBM775","DOS Baltic Rim"},{0x0410,"IBM850","DOS Latin 1"},
		{0x0412,"IBM852","DOS Latin 2"},{0x0413,"IBM855","DOS Cyrillic"},{0x0414,"IBM857","DOS Turkish"},{0x0415,"IBM860","DOS Portuguese"},
		{0x0416,"IBM861","DOS Icelandic"},{0x0417,"IBM862","DOS Hebrew"},{0x0418,"IBM863","DOS Canadian French"},{0x0419,"IBM864","DOS Arabic"},
		{0x041a,"IBM865","DOS Nordic"},{0x041b,"IBM866","DOS Russian"},{0x041c,"IBM869","DOS Greek 2"},{0x041d,"x-IBM874","DOS Thai"},
		{0x0420,"windows-31j","DOS Japanese"},{0x0421,"GBK","DOS Chinese Simplified"},{0x0422,"x-windows-949","DOS Korean"},{0x0423,"x-windows-950","DOS Chinese Traditional"},
		{0x0500,"windows-1252","Windows Latin 1"},{0x0501,"windows-1250","Windows Latin 2"},{0x0502,"windows-1251","Windows Cyrillic"},
		{0x0503,"windows-1253","Windows Greek"},{0x0504,"windows-1254","Windows Latin 5"},{0x0505,"windows-1255","Windows Hebrew"},
		{0x0506,"windows-1256","Windows Arabic"},{0x0507,"windows-1257","Windows Baltic Rim"},{0x0508,"windows-1258","Windows Vietnamese"},
		{0x0600,"US-ASCII","US ASCII"},{0x0631,"GBK","GBK"},
		{0x0820,"ISO-2022-JP","ISO-2022-JP"},{0x0821,"ISO-2022-JP-2","ISO-2022-JP-2"},{0x0840,"ISO-2022-KR","ISO-2022-KR"},
		{0x0920,"EUC-JP","EUC-JP"},{0x0930,"GB2312","EUC-CN"},{0x0931,"x-EUC-TW","EUC-TW"},{0x0940,"EUC-KR","EUC-KR"},
		{0x0a01,"Shift_JIS","Shift-JIS"},{0x0a02,"KOI8-R","KOI8-R"},{0x0a03,"Big5","Big-5"},{0x0c02,"IBM037","EBCDIC Latin Core"},
	};
	private static final Map<Integer,Charset> CHARSETS=new HashMap<Integer,Charset>();
	private static final List<Integer> AVAILABLE=new ArrayList<Integer>();
	private static final Map<Integer,String> NAMES=new HashMap<Integer,String>();
	static{
		for(Object[] e:TABLE){
			int base=(Integer)e[0];String name=(String)e[1];
			if(name!=null)try{Charset c=Charset.forName(name);if(!c.canEncode())continue;CHARSETS.put(base,c);}catch(RuntimeException ex){continue;}
			else if(macEncoding(base)==null)continue;
			AVAILABLE.add(base);NAMES.put(base,(String)e[2]);
		}
		AVAILABLE.add(UNICODE_DEFAULT);AVAILABLE.add(UTF8);AVAILABLE.add(UTF32);NAMES.put(UNICODE_DEFAULT,"Unicode");
	}
	/** The JDK charset of a non-Mac encoding, or null. */
	private static Charset charset(int base){return CHARSETS.get(base);}
	/** Whether the converter handles {@code encoding} (any Unicode version and the formats UTF-16/8/32). */
	private static boolean supported(int encoding){
		if(unicode(encoding))return format(encoding)!=1; // UTF-7 is not supported
		return AVAILABLE.contains(encoding&0xffff);
	}
	private static final String[][] INTERNET_NAMES={{"0","macintosh"},{"1","x-mac-japanese"},{"a01","Shift_JIS"},{"201","ISO-8859-1"},
		{"500","windows-1252"},{"600","US-ASCII"},{"920","EUC-JP"},{"820","ISO-2022-JP"},{"8000100","UTF-8"},{"100","UTF-16"},
		{"202","ISO-8859-2"},{"205","ISO-8859-5"},{"207","ISO-8859-7"},{"209","ISO-8859-9"},{"20f","ISO-8859-15"},{"501","windows-1250"},
		{"502","windows-1251"},{"a02","KOI8-R"},{"a03","Big5"},{"930","GB2312"},{"940","EUC-KR"},{"840","ISO-2022-KR"},{"420","Windows-31J"},
		{"7","x-mac-cyrillic"},{"1d","x-mac-ce"},{"2","x-mac-trad-chinese"},{"19","x-mac-simp-chinese"},{"3","x-mac-korean"}};
	/** The program's own encoding: MacJapanese on a Japanese system, else Mac OS Roman. */
	int systemEncoding(){return japaneseSystem()?1:0;}
	/** The script code of an encoding (RevertTextEncodingToScriptInfo): Mac encodings are their scripts, others the script of their Mac cousin. */
	static int scriptOf(int encoding){
		int base=encoding&0xffff;
		if(base<=32)return base;
		if(base==0x98)return 7;
		if(base>=33&&base<=41||base>=0x0201&&base<=0x0204||base==0x0209||base>=0x020d&&base<=0x020f||base==0x0500||base==0x0501||base==0x0504||base==0x0507||base==0x0600||base==0x0400||base==0x0410||base==0x0412||base==0x0414||base==0x0415||base==0x0416||base==0x0418||base==0x041a||base==0x0c02)return 0;
		switch(base){
			case 0x0205:case 0x0502:case 0x0413:case 0x041b:case 0x0a02:return 7;
			case 0x0206:case 0x0506:case 0x0419:return 4;
			case 0x0207:case 0x0503:case 0x0405:case 0x041c:return 6;
			case 0x0208:case 0x0505:case 0x0417:return 5;
			case 0x041d:return 21;
			case 0x0420:case 0x0820:case 0x0821:case 0x0920:case 0x0a01:return 1;
			case 0x0422:case 0x0840:case 0x0940:return 3;
			case 0x0423:case 0x0931:case 0x0a03:return 2;
			case 0x0421:case 0x0631:case 0x0930:return 25;
			default:return -1;
		}
	}

	/** An object of this layer: a guest block, so the program holds a real pointer, keyed to its host side. */
	private final Map<Integer,Object> objects=new HashMap<Integer,Object>();
	private int newObject(Object o) throws M68kCpuException {int p=rt.allocate(8,4);objects.put(p,o);return p;}
	/** A converter: {@code from} to {@code to}; a Unicode-to-text run converter tries each of {@code targets} in turn. */
	private static final class Converter {
		final int from,to;final int[] targets;
		/** TECConvertText's context, as the real converter keeps it between calls: the bytes of a character the input ended in
		 *  the middle of (MacSSH feeds its terminal one byte at a time), and the last ISO-2022 escape sequence (the shift state). */
		byte[] pending=new byte[0],shift=new byte[0];
		Converter(int from,int to){this(from,new int[]{to});}
		Converter(int from,int[] targets){this.from=from;this.targets=targets.length==0?new int[]{0}:targets;this.to=this.targets[0];}
	}
	/** A sniffer: the encodings it was created for. */
	private static final class Sniffer {final int[] encodings;Sniffer(int[] e){encodings=e;}}
	/** The text of a CFString, or null (the CFString calls live in {@link PpcCoreFoundation}). */
	String cfText(int ref){return rt.libraries.coreFoundation.text(ref);}
	/** Replaces a CFString's text (the CFMutableString calls, TruncateThemeText). */
	void setCFText(int ref,String text) throws M68kCpuException {rt.libraries.coreFoundation.setText(ref,text);}
	/** A new CFString (one reference) holding {@code text}. */
	int newCFString(String text) throws M68kCpuException {return rt.libraries.coreFoundation.newString(text);}
	/** {@code text} in the program's own encoding (MacJapanese or Mac OS Roman). */
	byte[] systemBytes(String text){return encode(systemEncoding(),text);}
	private Converter converter(int ref){Object o=objects.get(ref);return o instanceof Converter?(Converter)o:null;}

	/**
	 * Converts {@code length} bytes at {@code source} from one encoding to another into an output buffer of
	 * {@code capacity} bytes: {read, written, status}. What does not fit stays unread (kTECOutputBufferFullStatus).
	 */
	private int[] convert(int from,int to,int source,int length,int output,int capacity) throws M68kCpuException {
		String text=decode(from,memory.readBytes(source,Math.max(0,length)));
		byte[] out=encode(to,text);int read=length,status=NO_ERR;
		if(out.length>capacity){ // the characters that fit, and the source bytes they came from
			int n=fitting(to,text,capacity);
			out=encode(to,text.substring(0,n));read=Math.min(length,encode(from,text.substring(0,n)).length);status=OUTPUT_BUFFER_FULL;
		}
		if(output!=0)memory.load(output,out);
		return new int[]{read,out.length,status};
	}
	/**
	 * {@link #convert} with the converter's context: the bytes of a character left incomplete by the last call come first, and an
	 * incomplete character at the end of this input is kept for the next one (reported as read, as the real converter does) -
	 * a Shift-JIS lead byte alone became '?' and its trail byte a character of its own. The ISO-2022 shift state carries over.
	 * Output that does not fit falls back to the plain conversion of this call's bytes.
	 */
	private int[] convertKeeping(Converter k,int source,int length,int output,int capacity) throws M68kCpuException {
		byte[] fresh=memory.readBytes(source,Math.max(0,length)),in=new byte[k.pending.length+fresh.length];
		System.arraycopy(k.pending,0,in,0,k.pending.length);System.arraycopy(fresh,0,in,k.pending.length,fresh.length);
		int tail=incompleteTail(k.from,in,k.shift);byte[] whole=java.util.Arrays.copyOf(in,in.length-tail);
		boolean iso2022=iso2022(k.from);byte[] decoded=whole;
		if(iso2022&&k.shift.length>0){decoded=new byte[k.shift.length+whole.length];System.arraycopy(k.shift,0,decoded,0,k.shift.length);System.arraycopy(whole,0,decoded,k.shift.length,whole.length);}
		byte[] out=encode(k.to,decode(k.from,decoded));
		if(out.length>capacity){k.pending=new byte[0];return convert(k.from,k.to,source,length,output,capacity);}
		if(output!=0)memory.load(output,out);
		if(iso2022){byte[] last=lastEscape(whole);if(last!=null)k.shift=last;}
		k.pending=java.util.Arrays.copyOfRange(in,in.length-tail,in.length);
		return new int[]{length,out.length,NO_ERR};
	}
	private static boolean iso2022(int encoding){int base=encoding&0xffff;return base==0x0820||base==0x0821||base==0x0840;}
	/** The last escape sequence in {@code b} (ISO-2022: ESC, then intermediate bytes $ ( ) . and a final byte), or null. */
	private static byte[] lastEscape(byte[] b){
		byte[] found=null;
		for(int i=0;i<b.length;i++){if(b[i]!=0x1b)continue;int j=i+1;while(j<b.length&&b[j]>=0x20&&b[j]<=0x2f)j++;if(j<b.length&&j>i+1){found=java.util.Arrays.copyOfRange(b,i,j+1);i=j;}}
		return found;
	}
	/** How many bytes at the end of {@code b} begin a character of {@code encoding} that the next bytes complete. */
	private int incompleteTail(int encoding,byte[] b,byte[] shift){
		int n=b.length,base=encoding&0xffff;if(n==0)return 0;
		if(unicode(encoding)){
			int f=format(encoding);
			if(f==2){for(int back=1;back<=Math.min(3,n);back++){int c=b[n-back]&0xff;if((c&0xc0)==0x80)continue;int need=c>=0xf0?4:c>=0xe0?3:c>=0xc0?2:1;return need>back?back:0;}return 0;}
			if(f==3)return n%4;
			if(n%2==1)return 1;
			int last=(b[n-2]&0xff)<<8|b[n-1]&0xff;return last>=0xd800&&last<=0xdbff?2:0; // a high surrogate waits for its pair
		}
		if(base==0x0001||base==0x0a01||base==0x0420){ // Shift-JIS and its relatives: lead bytes $81-$9F and $E0-$FC
			int i=0;while(i<n){int c=b[i]&0xff;if(c>=0x81&&c<=0x9f||c>=0xe0&&c<=0xfc){if(i+1>=n)return 1;i+=2;}else i++;}
			return 0;
		}
		if(iso2022(encoding)){
			int esc=-1;for(int i=Math.max(0,n-4);i<n;i++)if(b[i]==0x1b)esc=i; // an escape sequence cut short
			if(esc>=0){int j=esc+1;while(j<n&&b[j]>=0x20&&b[j]<=0x2f)j++;if(j>=n)return n-esc;}
			byte[] mode=lastEscape(b);int from=0;if(mode==null&&shift.length>0)mode=shift; // still in the mode an earlier call set
			if(mode!=null){for(int i=n-mode.length;i>=0;i--)if(java.util.Arrays.equals(java.util.Arrays.copyOfRange(b,i,i+mode.length),mode)){from=i+mode.length;break;}}
			boolean twoBytes=mode!=null&&mode.length>=3&&mode[1]=='$';
			int count=0;for(int i=from;i<n;i++)if(b[i]!=0x0a&&b[i]!=0x0d)count++;else count=0;
			return twoBytes&&count%2==1?1:0;
		}
		Charset c=charset(base);if(c==null)return 0;
		java.nio.ByteBuffer in=java.nio.ByteBuffer.wrap(b);java.nio.CharBuffer out=java.nio.CharBuffer.allocate(n+1);
		c.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE).decode(in,out,false);
		return in.remaining()<=4?in.remaining():0; // the decoder stops before a character it has not got all of
	}
	/** How many characters of {@code text} fit in {@code capacity} bytes of {@code encoding} (never splitting a surrogate pair). */
	private int fitting(int encoding,String text,int capacity){
		int n=text.length();while(n>0&&encode(encoding,text.substring(0,n)).length>capacity)n--;
		while(n>0&&Character.isHighSurrogate(text.charAt(n-1)))n--;
		return n;
	}
	private void writeLongIf(int address,int value) throws M68kCpuException {if(address!=0)memory.writeLong(address,value);}
	/** Writes up to {@code max} of {@code values} to the array at {@code array} (longs); returns the number available. */
	private int writeList(List<Integer> values,int array,int max) throws M68kCpuException {
		if(array!=0)for(int i=0;i<Math.min(max,values.size());i++)memory.writeLong(array+4*i,values.get(i));
		return values.size();
	}
	private static List<Integer> list(int... values){List<Integer> l=new ArrayList<Integer>();for(int v:values)l.add(v);return l;}

	// ------------------------------------------------------------------ Unicode to text runs

	/**
	 * Unicode text as runs of the target encodings: each character goes to the first target that has it (with
	 * {@code keepSame}, the current run's encoding while it has the character); one no target has stays in the run it is
	 * in and becomes '?'. Returns {charStart, targetIndex} per run.
	 */
	private List<int[]> textRuns(String text,int[] targets,boolean keepSame){
		List<int[]> runs=new ArrayList<int[]>();int current=-1;
		for(int i=0;i<text.length();){
			int n=Character.charCount(text.codePointAt(i));String ch=text.substring(i,i+n);int pick=-1;
			if(keepSame&&current>=0&&canEncode(targets[current],ch))pick=current;
			for(int t=0;pick<0&&t<targets.length;t++)if(canEncode(targets[t],ch))pick=t;
			if(pick<0)pick=current>=0?current:0;
			if(pick!=current){runs.add(new int[]{i,pick});current=pick;}
			i+=n;
		}
		return runs;
	}
	/**
	 * ConvertFromUnicodeToTextRun / ConvertFromUnicodeToScriptCodeRun: converts into runs of the converter's targets;
	 * writes TextEncodingRun {offset, encoding} or ScriptCodeRun {offset, script} entries. Returns {read, written, status, runs}.
	 */
	private int[] convertRuns(Converter k,int source,int length,int flags,int output,int capacity,int runs,int runCapacity,boolean scriptRuns) throws M68kCpuException {
		boolean utf16=format(k.from)!=2&&format(k.from)!=3;String text; // UTF-8 / UTF-32 sources: the info's mapping said so
		if(utf16){StringBuilder sb=new StringBuilder();for(int i=0;i<length/2;i++)sb.append((char)memory.readWord(source+2*i));text=sb.toString();}
		else text=decode(k.from,memory.readBytes(source,Math.max(0,length)));
		List<int[]> parts=textRuns(text,k.targets,(flags&(1<<8))!=0); // kUnicodeKeepSameEncodingMask
		int written=0,read=0,count=0,status=NO_ERR;int entry=scriptRuns?6:8;
		for(int r=0;r<parts.size();r++){
			int start=parts.get(r)[0],end=r+1<parts.size()?parts.get(r+1)[0]:text.length();int encoding=k.targets[parts.get(r)[1]];
			String piece=text.substring(start,end);byte[] b=encode(encoding,piece);
			if(count>=runCapacity&&runs!=0){status=ARRAY_FULL;break;}
			if(written+b.length>capacity){int n=fitting(encoding,piece,capacity-written);b=encode(encoding,piece.substring(0,n));end=start+n;status=OUTPUT_BUFFER_FULL;}
			if(b.length>0||status==NO_ERR){
				if(runs!=0&&count<runCapacity){memory.writeLong(runs+entry*count,written);if(scriptRuns)memory.writeWord(runs+entry*count+4,scriptOf(encoding));else memory.writeLong(runs+entry*count+4,encoding);}
				count++;
			}
			if(output!=0)memory.load(output+written,b);written+=b.length;read=utf16?end*2:encode(k.from,text.substring(0,end)).length;
			if(status!=NO_ERR)break;
		}
		return new int[]{read,written,status,count};
	}

	// ------------------------------------------------------------------ sniffing

	/** Encodings that have a sniffer here (the Japanese family, Unicode, the common 8-bit ones and the Asian EUCs). */
	private static final int[] SNIFFERS={0x0a01,0x0920,0x0820,0x0001,0x0420,UTF8,UNICODE_DEFAULT,0x0000,0x0201,0x0500,0x0600,
		0x0940,0x0930,0x0a03,0x0840,0x0a02,0x0502,0x0202,0x0501};
	/**
	 * How {@code bytes} read in {@code encoding}: {errors, features}. Errors are malformed sequences and stray control
	 * bytes; features are the bytes of well-formed characters beyond ASCII (UTF-8's multi-byte sequences count double:
	 * a chance match is far less likely than for the double-byte encodings, whose lead/trail ranges overlap UTF-8's).
	 */
	int[] sniff(int encoding,byte[] b){
		int base=encoding&0xffff;
		if(unicode(encoding))return format(encoding)==2?sniffUtf8(b):sniffUtf16(b);
		switch(base){
			case 0x0a01:return sniffShiftJis(b,false,false);
			case 0x0001:return sniffShiftJis(b,true,false);
			case 0x0420:return sniffShiftJis(b,false,true);
			case 0x0920:return sniffEucJp(b);
			case 0x0820:case 0x0821:case 0x0840:return sniffIso2022(b,base==0x0840);
			case 0x0600:{int e=0;for(byte x:b)if(x<0||control(x&255))e++;return new int[]{e,0};}
			default:break;
		}
		int errors=0,features=0;
		if(macEncoding(base)!=null){ // a Mac encoding; the Chinese and Korean ones read as the JDK cousins StackTextEncoding uses
			if(base==2||base==3||base==25)return sniffCharset(charset(base==2?0x0a03:base==3?0x0940:0x0930),b);
			for(byte x:b)if(control(x&255))errors++;
			for(char ch:decode(encoding,b).toCharArray())if(ch>=0x80&&Character.isLetter(ch))features++;
			return new int[]{errors,features};
		}
		Charset c=charset(base);if(c==null)return new int[]{b.length,0};
		if(base==0x0201){for(byte x:b){int v=x&255;if(v>=0x80&&v<0xa0||control(v))errors++;else if(v>=0xa0)features++;}return new int[]{errors,features};}
		return sniffCharset(c,b);
	}
	private static boolean control(int v){return v<0x20&&v!=9&&v!=10&&v!=13&&v!=12&&v!=0x1b;}
	/** A JDK decoder's view: malformed and unmappable sequences are errors, the bytes of decoded non-ASCII letters features. */
	private static int[] sniffCharset(Charset c,byte[] b){
		CharsetDecoder d=c.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
		ByteBuffer in=ByteBuffer.wrap(b);CharBuffer out=CharBuffer.allocate(2);int errors=0,features=0;
		while(in.hasRemaining()){
			int before=in.position();out.clear();CoderResult r=d.decode(in,out,true);
			if(r.isError()){errors++;in.position(Math.min(b.length,in.position()+Math.max(1,r.length())));continue;}
			out.flip();int used=in.position()-before;boolean evidence=false;
			while(out.hasRemaining()){char ch=out.get();if(ch>=0x80&&Character.isLetterOrDigit(ch))evidence=true;else if(control(ch))errors++;}
			if(evidence)features+=used;
			if(used==0)break;
		}
		return new int[]{errors,features};
	}
	private static int[] sniffUtf8(byte[] b){
		int errors=0,features=0;
		for(int i=0;i<b.length;){
			int v=b[i]&255;
			if(v<0x80){if(control(v))errors++;i++;continue;}
			int n=v>=0xf0&&v<=0xf4?4:v>=0xe0?3:v>=0xc2&&v<0xe0?2:0;
			if(n==0||i+n>b.length){errors++;i++;continue;}
			boolean ok=true;for(int k=1;k<n;k++)if((b[i+k]&0xc0)!=0x80)ok=false;
			if(ok&&n==3){int cp=(v&15)<<12|(b[i+1]&0x3f)<<6|(b[i+2]&0x3f);if(cp<0x800||cp>=0xd800&&cp<0xe000)ok=false;}
			if(ok&&n==4){int cp=(v&7)<<18|(b[i+1]&0x3f)<<12;if(cp<0x10000||cp>0x10ffff)ok=false;}
			if(!ok){errors++;i++;continue;}
			features+=2*n;i+=n;
		}
		return new int[]{errors,features};
	}
	private static int[] sniffUtf16(byte[] b){
		int errors=b.length%2,features=0,i=0;boolean little=false;
		if(b.length>=2&&(b[0]&255)==0xfe&&(b[1]&255)==0xff){features+=8;i=2;}
		else if(b.length>=2&&(b[0]&255)==0xff&&(b[1]&255)==0xfe){features+=8;i=2;little=true;}
		for(;i+1<b.length;i+=2){
			int u=little?(b[i]&255|(b[i+1]&255)<<8):((b[i]&255)<<8|(b[i+1]&255));
			if(u>=0xd800&&u<0xdc00){int w=i+3<b.length?(little?(b[i+2]&255|(b[i+3]&255)<<8):((b[i+2]&255)<<8|(b[i+3]&255))):0;if(w>=0xdc00&&w<0xe000){features+=4;i+=2;}else errors++;continue;}
			if(u>=0xdc00&&u<0xe000||u==0xfffe||u==0xffff||!Character.isDefined(u)||u<0x20&&control(u))errors++;
			else if(u<0x80||u>=0x3000&&u<0x3100||u>=0xff00&&u<0xfff0)features+=2;
		}
		return new int[]{errors,features};
	}
	/** Shift JIS: lead bytes $81-$9F/$E0-$EF (to $FC for MacJapanese's and DOS's extensions), trail bytes $40-$7E/$80-$FC. */
	private static int[] sniffShiftJis(byte[] b,boolean mac,boolean dos){
		int errors=0,features=0;
		for(int i=0;i<b.length;){
			int v=b[i]&255;
			if(v<0x80){if(control(v))errors++;i++;continue;}
			if(v>=0xa1&&v<=0xdf){i++;continue;} // one-byte katakana: valid, no evidence
			boolean lead=v>=0x81&&v<=0x9f||v>=0xe0&&v<=0xef||(mac||dos)&&v>=0xf0&&v<=0xfc;
			if(!lead){if(!(mac&&(v==0x80||v==0xa0||v>=0xfd)))errors++;i++;continue;}
			if(i+1>=b.length){errors++;i++;continue;}
			int t=b[i+1]&255;
			if(t<0x40||t==0x7f||t>0xfc){errors++;i++;continue;}
			features+=2;i+=2;
		}
		return new int[]{errors,features};
	}
	/** EUC-JP: two bytes $A1-$FE, SS2 ($8E) one-byte katakana, SS3 ($8F) JIS X 0212. */
	private static int[] sniffEucJp(byte[] b){
		int errors=0,features=0;
		for(int i=0;i<b.length;){
			int v=b[i]&255;
			if(v<0x80){if(control(v))errors++;i++;continue;}
			int n=v==0x8f?3:v==0x8e||v>=0xa1&&v<=0xfe?2:0;
			if(n==0||i+n>b.length){errors++;i++;continue;}
			boolean ok=true;for(int k=1;k<n;k++){int t=b[i+k]&255;if(t<0xa1||t>0xfe||v==0x8e&&t>0xdf)ok=false;}
			if(!ok){errors++;i++;continue;}
			features+=n;i+=n;
		}
		return new int[]{errors,features};
	}
	/** ISO-2022-JP/KR: seven bits, the escape sequences the standard names; the bytes of two-byte characters are the evidence. */
	private static int[] sniffIso2022(byte[] b,boolean korean){
		int errors=0,features=0;boolean twoByte=false;
		for(int i=0;i<b.length;){
			int v=b[i]&255;
			if(v>=0x80){errors++;i++;continue;}
			if(v==0x1b){
				String s=new String(b,i,Math.min(4,b.length-i),StandardCharsets.ISO_8859_1);
				if(!korean&&(s.startsWith("\u001b$@")||s.startsWith("\u001b$B"))){twoByte=true;features+=3;i+=3;continue;}
				if(!korean&&(s.startsWith("\u001b(B")||s.startsWith("\u001b(J")||s.startsWith("\u001b(I"))){twoByte=false;features+=3;i+=3;continue;}
				if(!korean&&s.startsWith("\u001b$(D")){twoByte=true;features+=4;i+=4;continue;}
				if(korean&&s.startsWith("\u001b$)C")){features+=4;i+=4;continue;}
				errors++;i++;continue;
			}
			if(korean&&(v==0x0e||v==0x0f)){twoByte=v==0x0e;i++;continue;}
			if(control(v)){errors++;i++;continue;}
			if(twoByte&&v>=0x21&&v<=0x7e){if(i+1<b.length&&(b[i+1]&255)>=0x21&&(b[i+1]&255)<=0x7e){features+=2;i+=2;}else{errors++;i++;}continue;}
			i++;
		}
		return new int[]{errors,features};
	}

	// ------------------------------------------------------------------ encoding lists

	/** The encodings a web browser offers for a region (TECGetWebTextEncodings), most likely first. */
	private static List<Integer> webEncodings(int region){
		switch(region){
			case 14:return list(0x0a01,0x0820,0x0920,UTF8,0x0201);                     // verJapan
			case 51:return list(0x0940,0x0840,UTF8);                                  // verKorea
			case 52:return list(0x0930,0x0631,UTF8);                                  // verChina
			case 53:case 75:return list(0x0a03,0x0931,UTF8);                          // verTaiwan, verHongKong? (Big-5 regions)
			case 49:case 62:case 72:return list(0x0a02,0x0502,0x0205,0x0007,0x041b,UTF8); // Russia, Ukraine, Bulgaria
			case 42:case 43:case 56:case 57:case 66:case 68:return list(0x0202,0x0501,0x001d,UTF8); // central Europe
			case 20:return list(0x0207,0x0503,0x0006,UTF8);                           // verGreece
			case 24:return list(0x0209,0x0504,0x0023,UTF8);                           // verTurkey
			case 13:return list(0x0208,0x0505,0x0005,UTF8);                           // verIsrael
			case 16:return list(0x0206,0x0506,0x0004,UTF8);                           // verArabic
			case 54:return list(0x041d,0x0015,UTF8);                                  // verThailand
			default:return list(0x0201,0x0500,0x0000,UTF8);
		}
	}
	/** The encodings mail is sent in for a region (TECGetMailTextEncodings). */
	private static List<Integer> mailEncodings(int region){
		switch(region){
			case 14:return list(0x0820);case 51:return list(0x0840);case 52:return list(0x0930);case 53:case 75:return list(0x0a03);
			case 49:case 62:case 72:return list(0x0a02);case 42:case 43:case 56:case 57:case 66:case 68:return list(0x0202);
			case 20:return list(0x0207);case 24:return list(0x0209);case 13:return list(0x0208);case 16:return list(0x0206);case 54:return list(0x041d);
			default:return list(0x0201,0x0600);
		}
	}
	/** Sub-encodings: the character sets an encoding is built of (TECGetSubTextEncodings). */
	private static List<Integer> subEncodings(int encoding){
		switch(encoding&0xffff){
			case 0x0820:return list(0x0600,0x0620,0x0621,0x0622);
			case 0x0821:return list(0x0600,0x0620,0x0621,0x0622,0x0623,0x0201,0x0207);
			case 0x0920:return list(0x0600,0x0620,0x0622,0x0623);
			case 0x0a01:case 0x0001:case 0x0420:return list(0x0620,0x0622);
			case 0x0930:return list(0x0600,0x0630);case 0x0940:case 0x0840:return list(0x0600,0x0640);case 0x0931:return list(0x0600,0x0651,0x0652);
			default:return new ArrayList<Integer>();
		}
	}
	/** Every direct conversion: each encoding to and from UTF-16, and between the Unicode formats. */
	private static List<int[]> directConversions(){
		List<int[]> l=new ArrayList<int[]>();
		for(int e:AVAILABLE){if(unicode(e))continue;l.add(new int[]{e,UNICODE_DEFAULT});l.add(new int[]{UNICODE_DEFAULT,e});}
		l.add(new int[]{UNICODE_DEFAULT,UTF8});l.add(new int[]{UTF8,UNICODE_DEFAULT});l.add(new int[]{UNICODE_DEFAULT,UTF32});l.add(new int[]{UTF32,UNICODE_DEFAULT});
		return l;
	}
	/** The encoding's base name (GetTextEncodingName), or null. */
	private static String baseName(int encoding){return unicode(encoding)?"Unicode":NAMES.get(encoding&0xffff);}
	private static String formatName(int encoding){if(!unicode(encoding))return null;switch(format(encoding)){case 1:return "UTF-7";case 2:return "UTF-8";case 3:return "UTF-32";default:return "UTF-16";}}
	private static String variantName(int encoding){
		int base=encoding&0xffff,variant=(encoding>>>16)&0x3ff;
		if(base==0&&variant==1)return "Currency Sign";if(base==0&&variant==2)return "Euro Sign";
		if(base==1)switch(variant){case 1:return "Standard, No Verticals";case 2:return "Basic";case 3:return "PostScript Screen";case 4:return "PostScript Print";case 5:return "Verticals at Ku+10";default:return null;}
		if(unicode(encoding)&&variant==2)return "Canonical Decomposition";
		return null;
	}
	/** The Mac OS encoding closest to {@code encoding} (NearestMacTextEncodings), or -1. */
	private int nearestMac(int encoding){
		int base=encoding&0xffff;
		if(unicode(encoding))return systemEncoding();
		if(base<=41||base==0x98||base==0x8c||base==0xec||base==0xfc)return base;
		if(base==0x0600)return 0;
		if(base==0x0021||base==0x0022)return base;
		int script=scriptOf(encoding);if(script<0)return -1;
		if(script==0){switch(base){case 0x0202:case 0x0204:case 0x0501:case 0x0412:case 0x020d:case 0x0507:case 0x0406:return 29;case 0x0209:case 0x0504:case 0x0414:return 35;case 0x0416:return 37;default:return 0;}}
		return script;
	}

	void define(PpcLibraries l){
		// ---- text encodings
		l.register(null,"GetApplicationTextEncoding",(r,c)->ret(systemEncoding())); // Acrobat Reader 5.0 at start-up
		l.register(null,"CreateTextEncoding",(r,c)->ret((arg(0)&0xffff)|(arg(1)&0x3ff)<<16|(arg(2)&0x3f)<<26));
		l.register(null,"GetTextEncodingBase",(r,c)->ret(arg(0)&0xffff));
		l.register(null,"GetTextEncodingVariant",(r,c)->ret((arg(0)>>>16)&0x3ff));
		l.register(null,"GetTextEncodingFormat",(r,c)->ret((arg(0)>>>26)&0x3f));
		l.register(null,"UpgradeScriptInfoToTextEncoding",(r,c)->{ // (script, language, region, fontName, *encoding): kTextScriptDontCare takes the font's script
			int script=(short)arg(0);
			if(script==-128&&arg(3)!=0){String font=rt.toolbox.readPascal(arg(3));int id=Fonts68k.numberOf(font);script=id>=16384?(id-16384)/512+1:0;}
			writeLongIf(arg(4),script>=0&&script<=32?script:systemEncoding());ret(NO_ERR);});
		l.register(null,"RevertTextEncodingToScriptInfo",(r,c)->{int script=scriptOf(arg(0));if(script<0)script=systemEncoding();
			if(arg(1)!=0)memory.writeWord(arg(1),script);if(arg(2)!=0)memory.writeWord(arg(2),script==1?11:0);if(arg(3)!=0)memory.writeByte(arg(3),0);ret(NO_ERR);});
		l.register(null,"TECGetTextEncodingInternetName",(r,c)->{int e=arg(0);String key=Integer.toHexString(unicode(e)&&format(e)==2?0x8000100:unicode(e)?0x100:e&0xffff);
			for(String[] n:INTERNET_NAMES)if(n[0].equals(key)){rt.toolbox.writePascal(arg(1),n[1],255);ret(NO_ERR);return;}
			ret(UNSUPPORTED_ENCODING);});
		l.register(null,"TECGetTextEncodingFromInternetName",(r,c)->{String name=rt.toolbox.readPascal(arg(1)).trim();
			for(String[] n:INTERNET_NAMES)if(n[1].equalsIgnoreCase(name)){int v=(int)Long.parseLong(n[0],16);memory.writeLong(arg(0),v);ret(NO_ERR);return;}
			if(name.equalsIgnoreCase("x-sjis")||name.equalsIgnoreCase("MS_Kanji")){memory.writeLong(arg(0),0x0a01);ret(NO_ERR);return;}
			if(name.equalsIgnoreCase("x-euc-jp")){memory.writeLong(arg(0),0x0920);ret(NO_ERR);return;}
			if(name.equalsIgnoreCase("utf8")){memory.writeLong(arg(0),UTF8);ret(NO_ERR);return;}
			ret(UNSUPPORTED_ENCODING);});
		// ---- TextCommon
		l.register(null,"ResolveDefaultTextEncoding",(r,c)->{int e=arg(0),base=e&0xffff,variant=(e>>>16)&0x3ff,rest=e&~0x03ffffff;
			if(base==0xff)ret(systemEncoding());                                              // kTextEncodingMacHFS
			else if(base==UNICODE_DEFAULT)ret(rest|variant<<16|0x0104);                         // Unicode 3.0 (TEC 1.6)
			else if(variant==0&&(base==0||base==36||base==38||base==0xfc))ret(rest|2<<16|base); // the Euro sign variants of Mac OS 8.5 and later
			else if(variant==0&&base==37)ret(rest|4<<16|base);
			else if(variant==0&&base==7)ret(rest|3<<16|base);
			else ret(e);});
		l.register(null,"NearestMacTextEncodings",(r,c)->{int best=nearestMac(arg(0));if(best<0){ret(UNSUPPORTED_ENCODING);return;}
			writeLongIf(arg(1),best);writeLongIf(arg(2),best==0?systemEncoding():0);ret(NO_ERR);});
		// GetTextEncodingName(encoding, selector, preferredRegion, preferredEncoding, bufLen, *nameLength, *actualRegion, *actualEncoding, name): English names in Mac OS Roman
		l.register(null,"GetTextEncodingName",(r,c)->{int e=arg(0),selector=arg(1);String name;
			switch(selector){
				case 0:{String b=baseName(e);if(b==null){name=null;break;}String v=variantName(e),f=formatName(e);name=f!=null?b+" ("+f+")":v!=null?b+" ("+v+")":b;break;}
				case 1:name=baseName(e);break;case 2:name=variantName(e);break;case 3:name=formatName(e);break;default:ret(PARAM_ERR);return;
			}
			if(name==null){ret(baseName(e)==null?UNSUPPORTED_ENCODING:ITEM_UNAVAILABLE);return;}
			byte[] b=name.getBytes(Charset.forName("x-MacRoman"));int n=Math.min(b.length,Math.max(0,arg(4)));
			if(arg(8)!=0)memory.load(arg(8),java.util.Arrays.copyOf(b,n));
			writeLongIf(arg(5),n);if(arg(6)!=0)memory.writeWord(arg(6),0);writeLongIf(arg(7),0);ret(NO_ERR);});
		// ---- Unicode Converter: TextToUnicodeInfo / UnicodeToTextInfo / UnicodeToTextRunInfo hold {from, to}
		l.register(null,"CreateTextToUnicodeInfo",(r,c)->{int map=arg(0);memory.writeLong(arg(1),newObject(new Converter(memory.readLong(map+4),memory.readLong(map))));ret(NO_ERR);});
		l.register(null,"CreateTextToUnicodeInfoByEncoding",(r,c)->{memory.writeLong(arg(1),newObject(new Converter(arg(0),UNICODE_DEFAULT)));ret(NO_ERR);});
		l.register(null,"CreateUnicodeToTextInfo",(r,c)->{int map=arg(0);memory.writeLong(arg(1),newObject(new Converter(memory.readLong(map),memory.readLong(map+4))));ret(NO_ERR);});
		l.register(null,"CreateUnicodeToTextInfoByEncoding",(r,c)->{memory.writeLong(arg(1),newObject(new Converter(UNICODE_DEFAULT,arg(0))));ret(NO_ERR);});
		// run infos: the target encodings in order; none given means the installed scripts, the system script first. The
		// mappings' unicodeEncoding is the source's Unicode form; -1 mappings = one UnicodeMapping that gives only that form,
		// the targets being the installed scripts (Internet Explorer 5 lays pages out in UTF-8 and asks for {UTF-8, 0, -1})
		l.register(null,"CreateUnicodeToTextRunInfo",(r,c)->{int n=arg(0),maps=arg(1);int[] t=new int[Math.max(0,maps==0?0:n)];for(int i=0;i<t.length;i++)t[i]=memory.readLong(maps+12*i+4);
			int from=maps!=0&&n!=0?memory.readLong(maps):UNICODE_DEFAULT;if(!unicode(from))from=UNICODE_DEFAULT;
			memory.writeLong(arg(2),newObject(new Converter(from,t.length>0?t:defaultTargets())));ret(NO_ERR);});
		l.register(null,"CreateUnicodeToTextRunInfoByEncoding",(r,c)->{int n=arg(0),encodings=arg(1);int[] t=new int[Math.max(0,encodings==0?0:n)];for(int i=0;i<t.length;i++)t[i]=memory.readLong(encodings+4*i);
			memory.writeLong(arg(2),newObject(new Converter(UNICODE_DEFAULT,t.length>0?t:defaultTargets())));ret(NO_ERR);});
		l.register(null,"CreateUnicodeToTextRunInfoByScriptCode",(r,c)->{int n=arg(0),scripts=arg(1);int[] t=new int[Math.max(0,scripts==0?0:n)];for(int i=0;i<t.length;i++)t[i]=(short)memory.readWord(scripts+2*i);
			memory.writeLong(arg(2),newObject(new Converter(UNICODE_DEFAULT,t.length>0?t:defaultTargets())));ret(NO_ERR);});
		for(String name:new String[]{"DisposeTextToUnicodeInfo","DisposeUnicodeToTextInfo","DisposeUnicodeToTextRunInfo"})
			l.register(null,name,(r,c)->{int p=arg(0),ref=p==0?0:memory.readLong(p);if(objects.remove(ref)!=null)memory.free(ref);if(p!=0)memory.writeLong(p,0);ret(NO_ERR);});
		l.register(null,"ChangeTextToUnicodeInfo",(r,c)->{if(converter(arg(0))==null||arg(1)==0){ret(PARAM_ERR);return;}objects.put(arg(0),new Converter(memory.readLong(arg(1)+4),memory.readLong(arg(1))));ret(NO_ERR);});
		l.register(null,"ChangeUnicodeToTextInfo",(r,c)->{if(converter(arg(0))==null||arg(1)==0){ret(PARAM_ERR);return;}objects.put(arg(0),new Converter(memory.readLong(arg(1)),memory.readLong(arg(1)+4)));ret(NO_ERR);});
		// fallback handlers are kept by nobody: characters a target lacks become '?', as the default fallback does
		for(String name:new String[]{"SetFallbackUnicodeToText","SetFallbackUnicodeToTextRun","ResetTextToUnicodeInfo","ResetUnicodeToTextInfo","ResetUnicodeToTextRunInfo"})
			l.register(null,name,(r,c)->ret(NO_ERR));
		// ConvertFromTextToUnicode / ConvertFromUnicodeToText (info, inLen, in, flags, offsetCount, offsets, *outOffsetCount, outOffsets, outCapacity, *read, *written, out)
		PpcRuntime.Native unicodeConversion=(r,c)->{Converter k=converter(arg(0));if(k==null){ret(PARAM_ERR);return;}
			int[] res=convert(k.from,k.to,arg(2),arg(1),arg(11),arg(8));
			if(PpcRuntime.TRACE)System.err.println("[ppc]   converted \""+decode(k.to,memory.readBytes(arg(11),Math.max(0,Math.min(res[1],400))))+"\"");
			writeLongIf(arg(6),0);writeLongIf(arg(9),res[0]);writeLongIf(arg(10),res[1]);ret(res[2]);};
		l.register(null,"ConvertFromTextToUnicode",unicodeConversion);
		l.register(null,"ConvertFromUnicodeToText",unicodeConversion);
		// ConvertFromUnicodeToTextRun: the same, then (runCapacity, *runCount, TextEncodingRun runs[]): runs of the info's target encodings
		l.register(null,"ConvertFromUnicodeToTextRun",(r,c)->{Converter k=converter(arg(0));if(k==null){ret(PARAM_ERR);return;}
			int[] res=convertRuns(k,arg(2),arg(1),arg(3),arg(11),arg(8),arg(14),arg(12),false);
			writeLongIf(arg(6),0);writeLongIf(arg(9),res[0]);writeLongIf(arg(10),res[1]);writeLongIf(arg(13),res[3]);ret(res[2]);});
		l.register(null,"ConvertFromUnicodeToScriptCodeRun",(r,c)->{Converter k=converter(arg(0));if(k==null){ret(PARAM_ERR);return;} // ScriptCodeRun {offset, script}
			int[] res=convertRuns(k,arg(2),arg(1),arg(3),arg(11),arg(8),arg(14),arg(12),true);
			writeLongIf(arg(6),0);writeLongIf(arg(9),res[0]);writeLongIf(arg(10),res[1]);writeLongIf(arg(13),res[3]);ret(res[2]);});
		l.register(null,"ConvertFromPStringToUnicode",(r,c)->{Converter k=converter(arg(0));if(k==null||arg(1)==0){ret(PARAM_ERR);return;} // (info, Str255, outLen, *unicodeLen, out)
			int[] res=convert(k.from,UNICODE_DEFAULT,arg(1)+1,memory.readByte(arg(1)),arg(4),arg(2));writeLongIf(arg(3),res[1]);ret(res[2]);});
		l.register(null,"ConvertFromUnicodeToPString",(r,c)->{Converter k=converter(arg(0));if(k==null||arg(3)==0){ret(PARAM_ERR);return;} // (info, unicodeLen, unicode, Str255)
			int[] res=convert(UNICODE_DEFAULT,k.to,arg(2),arg(1),arg(3)+1,255);memory.writeByte(arg(3),res[1]);ret(res[2]);});
		// the mapping tables: each encoding to and from UTF-16; the filter matches UnicodeMapping fields per kUnicodeMatch... bits
		l.register(null,"CountUnicodeMappings",(r,c)->{writeLongIf(arg(2),mappings(arg(0),arg(1)).size());ret(NO_ERR);});
		l.register(null,"QueryUnicodeMappings",(r,c)->{List<Integer> m=mappings(arg(0),arg(1));int max=arg(2),out=arg(4);
			for(int i=0;i<Math.min(max,m.size())&&out!=0;i++){memory.writeLong(out+12*i,UNICODE_DEFAULT);memory.writeLong(out+12*i+4,m.get(i));memory.writeLong(out+12*i+8,-1);} // kUnicodeUseLatestMapping
			writeLongIf(arg(3),Math.min(max,m.size()));ret(m.size()>max?ARRAY_FULL:NO_ERR);});
		// ---- TEC converters: TECCreateConverter(*ref, in, out), TECConvertText(ref, in, inLen, *read, out, outLen, *written)
		l.register(null,"TECCreateConverter",(r,c)->{memory.writeLong(arg(0),newObject(new Converter(arg(1),arg(2))));ret(NO_ERR);});
		l.register(null,"TECCreateConverterFromPath",(r,c)->{int path=arg(1),n=arg(2);if(n<2||path==0){ret(PARAM_ERR);return;} // the path's ends; every step goes through Unicode anyway
			int from=memory.readLong(path),to=memory.readLong(path+4*(n-1));
			memory.writeLong(arg(0),newObject(new Converter(from,to)));ret(NO_ERR);});
		l.register(null,"TECDisposeConverter",(r,c)->{if(objects.remove(arg(0))!=null)memory.free(arg(0));ret(NO_ERR);});
		l.register(null,"TECClearConverterContextInfo",(r,c)->{Converter k=converter(arg(0));if(k!=null){k.pending=new byte[0];k.shift=new byte[0];}ret(NO_ERR);});
		PpcRuntime.Native convertText=(r,c)->{Converter k=converter(arg(0));if(k==null){ret(PARAM_ERR);return;}
			int[] res=convertKeeping(k,arg(1),arg(2),arg(4),arg(5));writeLongIf(arg(3),res[0]);writeLongIf(arg(6),res[1]);ret(res[2]);};
		l.register(null,"TECConvertText",convertText);
		l.register(null,"TECConvertTextBuffer",convertText); // TEC 1.0's earlier name, same arguments (Cyberdog 2.0 decodes web pages with it)
		l.register(null,"TECFlushText",(r,c)->{Converter k=converter(arg(0)); // (converter, out, outLen, *written): what an incomplete character left, as it is
			byte[] out=k==null||k.pending.length==0?new byte[0]:encode(k.to,decode(k.from,k.pending));
			if(out.length>arg(2))out=java.util.Arrays.copyOf(out,Math.max(0,arg(2)));
			if(arg(1)!=0)memory.load(arg(1),out);writeLongIf(arg(3),out.length);
			if(k!=null){k.pending=new byte[0];k.shift=new byte[0];}ret(NO_ERR);});
		l.register(null,"TECGetEncodingList",(r,c)->{Converter k=converter(arg(0));if(k==null){ret(PARAM_ERR);return;} // (converter, *count, *Handle): the conversion path
			List<Integer> path=list(k.from);if(!unicode(k.from)&&!unicode(k.to))path.add(UNICODE_DEFAULT);path.add(k.to);
			byte[] b=new byte[4*path.size()];for(int i=0;i<path.size();i++){int v=path.get(i);b[4*i]=(byte)(v>>>24);b[4*i+1]=(byte)(v>>>16);b[4*i+2]=(byte)(v>>>8);b[4*i+3]=(byte)v;}
			writeLongIf(arg(1),path.size());if(arg(2)!=0)memory.writeLong(arg(2),rt.toolbox.api.createHandle(b));ret(NO_ERR);});
		// one-to-many converters (iCab sniffs page encodings with one): converted into the first output encoding, reported as one run
		l.register(null,"TECCreateOneToManyConverter",(r,c)->{int n=arg(2),outs=arg(3);if(n<1||outs==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),newObject(new Converter(arg(1),memory.readLong(outs))));ret(NO_ERR);});
		l.register(null,"TECConvertTextToMultipleEncodings",(r,c)->{Converter k=converter(arg(0));if(k==null){ret(PARAM_ERR);return;}
			int[] res=convert(k.from,k.to,arg(1),arg(2),arg(4),arg(5));writeLongIf(arg(3),res[0]);writeLongIf(arg(6),res[1]);
			int runs=arg(7);if(arg(8)>0&&runs!=0&&res[1]>0){memory.writeLong(runs,0);memory.writeLong(runs+4,k.to);writeLongIf(arg(9),1);}else writeLongIf(arg(9),0);
			ret(res[2]);});
		l.register(null,"TECFlushMultipleEncodings",(r,c)->{writeLongIf(arg(3),0);writeLongIf(arg(6),0);ret(NO_ERR);});
		l.register(null,"TECGetInfo",(r,c)->{byte[] info=new byte[64];info[1]=2;info[2]=0x01;info[3]=0x50; // kTECInfoCurrentFormat 2, version 1.5
			memory.writeLong(arg(0),rt.toolbox.api.createHandle(info));ret(NO_ERR);});
		// ---- TEC encoding lists: (Count: *count) / (Get: array, max, *actual)
		l.register(null,"TECCountAvailableTextEncodings",(r,c)->{writeLongIf(arg(0),AVAILABLE.size());ret(NO_ERR);});
		l.register(null,"TECGetAvailableTextEncodings",(r,c)->{int n=writeList(AVAILABLE,arg(0),arg(1));writeLongIf(arg(2),Math.min(n,arg(1)));ret(n>arg(1)?ARRAY_FULL:NO_ERR);});
		l.register(null,"TECCountDirectTextEncodingConversions",(r,c)->{writeLongIf(arg(0),directConversions().size());ret(NO_ERR);});
		l.register(null,"TECGetDirectTextEncodingConversions",(r,c)->{List<int[]> d=directConversions();int out=arg(0),max=arg(1); // TECConversionInfo {source, destination, reserved1, reserved2}: 12 bytes
			for(int i=0;i<Math.min(max,d.size())&&out!=0;i++){memory.writeLong(out+12*i,d.get(i)[0]);memory.writeLong(out+12*i+4,d.get(i)[1]);memory.writeLong(out+12*i+8,0);}
			writeLongIf(arg(2),Math.min(max,d.size()));ret(d.size()>max?ARRAY_FULL:NO_ERR);});
		l.register(null,"TECCountDestinationTextEncodings",(r,c)->{if(!supported(arg(0))){ret(UNSUPPORTED_ENCODING);return;}writeLongIf(arg(1),destinations(arg(0)).size());ret(NO_ERR);});
		l.register(null,"TECGetDestinationTextEncodings",(r,c)->{if(!supported(arg(0))){ret(UNSUPPORTED_ENCODING);return;}
			int n=writeList(destinations(arg(0)),arg(1),arg(2));writeLongIf(arg(3),Math.min(n,arg(2)));ret(n>arg(2)?ARRAY_FULL:NO_ERR);});
		l.register(null,"TECCountSubTextEncodings",(r,c)->{writeLongIf(arg(1),subEncodings(arg(0)).size());ret(NO_ERR);});
		l.register(null,"TECGetSubTextEncodings",(r,c)->{int n=writeList(subEncodings(arg(0)),arg(1),arg(2));writeLongIf(arg(3),Math.min(n,arg(2)));ret(n>arg(2)?ARRAY_FULL:NO_ERR);});
		l.register(null,"TECCountWebTextEncodings",(r,c)->{writeLongIf(arg(1),available(webEncodings((short)arg(0))).size());ret(NO_ERR);});
		l.register(null,"TECGetWebTextEncodings",(r,c)->{int n=writeList(available(webEncodings((short)arg(0))),arg(1),arg(2));writeLongIf(arg(3),Math.min(n,arg(2)));ret(n>arg(2)?ARRAY_FULL:NO_ERR);});
		l.register(null,"TECCountMailTextEncodings",(r,c)->{writeLongIf(arg(1),available(mailEncodings((short)arg(0))).size());ret(NO_ERR);});
		l.register(null,"TECGetMailTextEncodings",(r,c)->{int n=writeList(available(mailEncodings((short)arg(0))),arg(1),arg(2));writeLongIf(arg(3),Math.min(n,arg(2)));ret(n>arg(2)?ARRAY_FULL:NO_ERR);});
		// ---- sniffers (NuEdit guesses a document's encoding with them)
		l.register(null,"TECCountAvailableSniffers",(r,c)->{writeLongIf(arg(0),sniffers().size());ret(NO_ERR);});
		l.register(null,"TECGetAvailableSniffers",(r,c)->{int n=writeList(sniffers(),arg(0),arg(1));writeLongIf(arg(2),Math.min(n,arg(1)));ret(n>arg(1)?ARRAY_FULL:NO_ERR);});
		l.register(null,"TECCreateSniffer",(r,c)->{int list=arg(1),n=arg(2);if(arg(0)==0||n<0||n>0&&list==0){ret(PARAM_ERR);return;} // (*sniffer, encodings[], count)
			int[] e=new int[n];for(int i=0;i<n;i++){e[i]=memory.readLong(list+4*i);if(!supported(e[i])){ret(UNSUPPORTED_ENCODING);return;}}
			memory.writeLong(arg(0),newObject(new Sniffer(e)));ret(NO_ERR);});
		l.register(null,"TECDisposeSniffer",(r,c)->{if(objects.get(arg(0)) instanceof Sniffer){objects.remove(arg(0));memory.free(arg(0));ret(NO_ERR);}else ret(PARAM_ERR);});
		l.register(null,"TECClearSnifferContextInfo",(r,c)->ret(objects.get(arg(0)) instanceof Sniffer?NO_ERR:PARAM_ERR));
		// TECSniffTextEncoding(sniffer, text, length, encodings[], count, numErrs[], maxErrs, numFeatures[], maxFeatures): the three
		// arrays are sorted together, fewest errors first, then most features (a stable sort keeps the caller's order on ties)
		l.register(null,"TECSniffTextEncoding",(r,c)->{
			if(!(objects.get(arg(0)) instanceof Sniffer)){ret(PARAM_ERR);return;}
			byte[] text=memory.readBytes(arg(1),Math.max(0,arg(2)));int encodings=arg(3),n=arg(4),errs=arg(5),maxErrs=arg(6),features=arg(7),maxFeatures=arg(8);
			if(encodings==0||n<=0){ret(PARAM_ERR);return;}
			List<int[]> scored=new ArrayList<int[]>();
			for(int i=0;i<n;i++){int e=memory.readLong(encodings+4*i);int[] s=supported(e)?sniff(e,text):new int[]{text.length+1,0};
				if(maxErrs>0)s[0]=Math.min(s[0],maxErrs);if(maxFeatures>0)s[1]=Math.min(s[1],maxFeatures);scored.add(new int[]{e,s[0],s[1]});}
			scored.sort((a,b)->a[1]!=b[1]?Integer.compare(a[1],b[1]):Integer.compare(b[2],a[2]));
			for(int i=0;i<n;i++){memory.writeLong(encodings+4*i,scored.get(i)[0]);writeLongIf(errs==0?0:errs+4*i,scored.get(i)[1]);writeLongIf(features==0?0:features+4*i,scored.get(i)[2]);}
			ret(NO_ERR);});

		// safe truncation lengths: the longest prefix of the source whose conversion fits (Unicode -> text) or that does
		// not split a character (text -> Unicode); Acrobat Reader 5.0 cuts its menu and window titles with them
		l.register(null,"TruncateForUnicodeToText",(r,c)->{ // (info, sourceLen bytes, UniChar*, controlFlags, maxLen, *truncatedLen)
			Converter k=converter(arg(0));int to=k==null?systemEncoding():k.to;int chars=arg(1)/2,source=arg(2),max=arg(4);
			StringBuilder sb=new StringBuilder();int fit=0;
			for(int i=0;i<chars;i++){sb.append((char)memory.readWord(source+2*i));if(Character.isHighSurrogate(sb.charAt(sb.length()-1))&&i+1<chars)continue;
				if(encode(to,sb.toString()).length>max)break;fit=i+1;}
			writeLongIf(arg(5),fit*2);ret(NO_ERR);});
		l.register(null,"TruncateForTextToUnicode",(r,c)->{ // (info, sourceLen, source, maxLen, *truncatedLen)
			Converter k=converter(arg(0));int from=k==null?systemEncoding():k.from;int len=arg(1),source=arg(2),max=Math.min(arg(3),len);
			int n=max;if(n<len&&((from&0xffff)==1||(from&0xffff)==0x0a01)){int i=0;while(i<n){int b=memory.readByte(source+i);int w=(b>=0x81&&b<=0x9f)||(b>=0xe0&&b<=0xfc)?2:1;if(i+w>n)break;i+=w;}n=i;}
			writeLongIf(arg(4),n);ret(NO_ERR);});

		l.register(null,"KBGetLayoutType",(r,c)->ret(japaneseSystem()?0x4a495320:0x414e5349)); // kKeyboardJIS on a Japanese system, else kKeyboardANSI
		// GetTextAndEncodingFromCFString(string, outText, maxLength, *outLength, *outEncoding): the one- or two-byte Mac
		// encoding that has every character (the program's own first), else the program's with '?' for what it lacks
		l.register(null,"GetTextAndEncodingFromCFString",(r,c)->{String s=cfText(arg(0));if(s==null){ret(PARAM_ERR);return;}
			int pick=systemEncoding();
			for(int e:new int[]{systemEncoding(),0,1,29,7,6,35,5,4,21,3,2,25})if(canEncode(e,s)){pick=e;break;}
			byte[] b=encode(pick,s);int n=Math.min(b.length,Math.max(0,arg(2)));
			if(n<b.length){n=encode(pick,s.substring(0,fitting(pick,s,n))).length;}
			if(arg(1)!=0)memory.load(arg(1),java.util.Arrays.copyOf(b,n));writeLongIf(arg(3),n);writeLongIf(arg(4),pick);ret(NO_ERR);});
		defineServices(l);
	}

	static final int TSM_INVALID_DOC_ID=-2505,BAD_COMPONENT_INSTANCE=0x80008001,UNIMP_ERR=-4;
	/** TSMSetInlineInputRegion's regions (copies, owned here) by TSM document. */
	private final Map<Integer,Integer> inlineRegions=new HashMap<Integer,Integer>();

	/** Text Services Manager calls of CarbonLib 1.1-1.6, the private TEC/locale entry points and the Language Analysis Manager's private calls. */
	private void defineServices(PpcLibraries l){
		// TSM documents live in the 68K Text Services Manager (TextServices68k): the program's active one, or NULL
		l.register(null,"TSMGetActiveDocument",(r,c)->ret(rt.toolbox.textServices.activeDocumentId()));
		// TSMSetInlineInputRegion(document, window, region): kept as a copy (NULL forgets it); the host input method composes from
		// keys, so no mouse events need forwarding to it
		l.register(null,"TSMSetInlineInputRegion",(r,c)->{int doc=arg(0),rgn=arg(2);if(!rt.toolbox.textServices.isDocument(doc)){ret(TSM_INVALID_DOC_ID);return;}
			Integer old=inlineRegions.remove(doc);if(old!=null)rt.toolbox("DisposeRgn",old);
			if(rgn!=0){int copy=rt.toolbox("NewRgn");rt.toolbox("CopyRgn",rgn,copy);inlineRegions.put(doc,copy);}
			ret(NO_ERR);});
		// TextServiceEventRef(ComponentInstance, EventRef): no text service component can be opened here (OpenTextService fails), so no
		// instance is valid
		l.register(null,"TextServiceEventRef",(r,c)->ret(BAD_COMPONENT_INSTANCE));
		// dead keys are composed by the host before keys reach the program: the emulated dead-key state is always "none"
		l.register(null,"TSMGetDeadKeyState",(r,c)->ret(0));
		l.register(null,"TSMSetDeadKeyState",(r,c)->ret(NO_ERR));
		// locking converters and mapping tables in memory: the tables are the host's and always resident; a converter must be one of ours
		l.register(null,"LockMappingTable",(r,c)->ret(NO_ERR));
		l.register(null,"LockTextToUnicodeInfo",(r,c)->ret(converter(arg(0))!=null?NO_ERR:PARAM_ERR));
		l.register(null,"LockUnicodeToTextInfo",(r,c)->ret(converter(arg(0))!=null?NO_ERR:PARAM_ERR));
		// the Unicode properties table behind UCGetCharProperty is the host's and always loaded
		for(String name:new String[]{"UCLoadCharPropsTablePriv","UCUnloadCharPropsTablePriv","UCUseCharPropsTablePriv"})l.register(null,name,(r,c)->ret(NO_ERR));
		// CarbonLib-private TEC/locale entry points whose parameters are not published: unimpErr, nothing written
		for(String name:new String[]{"UnicodeValidConversionLength","MapScriptInfoAndISOCodes","LocaleOperationLoadData","LocaleOperationUnloadData"})l.register(null,name,(r,c)->ret(UNIMP_ERR));
		// Language Analysis Manager (private calls): no morpheme analysis environment is installed (LAGetEnvironmentList lists none), so
		// environment calls answer laEnvironmentNotFoundErr and calls on a context (none can be opened) paramErr; nothing is written
		for(String name:new String[]{"LAOpenContext","LAOpenDictionaryPriv","LAGetEnvironmentProperty","LASetEnvironmentProperty"})
			l.register(null,name,(r,c)->ret(PpcCarbonUtilities.LA_ENVIRONMENT_NOT_FOUND));
		for(String name:new String[]{"LACloseContext","LAConfirmMorpheme","LAContinuousMorphemeAnalysisPriv","LAFeedbackPath","LAGetMorphemesPriv","LAMorphemeAnalysisPriv","LAReanalyzePartially"})
			l.register(null,name,(r,c)->ret(PARAM_ERR));
	}

	/** The encodings a run converter tries when the program names none: the system script's, then Mac OS Roman. */
	private int[] defaultTargets(){return systemEncoding()==0?new int[]{0}:new int[]{systemEncoding(),0};}
	/** What {@code encoding} converts to: every other encoding (through Unicode). */
	private static List<Integer> destinations(int encoding){List<Integer> l=new ArrayList<Integer>();for(int e:AVAILABLE)if(e!=encoding&&!(unicode(e)&&unicode(encoding)&&format(e)==format(encoding)))l.add(e);return l;}
	private static List<Integer> available(List<Integer> encodings){List<Integer> l=new ArrayList<Integer>();for(int e:encodings)if(supported(e))l.add(e);return l;}
	private static List<Integer> sniffers(){return available(list(SNIFFERS));}
	/** The mappings of CountUnicodeMappings / QueryUnicodeMappings: the other encodings whose UnicodeMapping matches the filter. */
	private List<Integer> mappings(int filter,int find) throws M68kCpuException {
		List<Integer> result=new ArrayList<Integer>();int u=find==0?0:memory.readLong(find),o=find==0?0:memory.readLong(find+4);
		if(find!=0&&(filter&7)!=0){ // the Unicode side: UTF-16 of any version
			if((filter&1)!=0&&!unicode(u))return result;if((filter&4)!=0&&format(u)!=0)return result;if((filter&2)!=0&&((u>>>16)&0x3ff)!=0)return result;}
		for(int e:AVAILABLE){
			if(unicode(e))continue;
			if(find!=0&&(filter&8)!=0&&(o&0xffff)!=e)continue;
			if(find!=0&&(filter&16)!=0&&((o>>>16)&0x3ff)!=0)continue;
			if(find!=0&&(filter&32)!=0&&format(o)!=0)continue;
			result.add(e);
		}
		return result;
	}
}

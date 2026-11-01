import java.lang.Character.UnicodeScript;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guesses the Mac OS text encoding of a HyperCard stack from its own text, so that importing a stack needs no encoding
 * chooser.
 *
 * The stack file does not record the Script Manager's script (see HYPERCARD_STACK_FORMAT.md), but its text gives it away:
 * the stack script, the part, card and background names and scripts, the field texts and the font names are taken out of
 * the data fork ({@link #textSamples}) and read in each candidate encoding. Every non-ASCII character scores by how
 * plausible it is there - hiragana and katakana in Shift-JIS, an accented letter inside a Latin word or typographic
 * punctuation in Mac OS Roman, frequent hanzi/hangul in the Chinese and Korean encodings, letter runs in the one-byte
 * scripts - and impossible byte sequences cost a lot. The encoding with the best average wins.
 *
 * A stack whose text is plain ASCII reads the same in all of them. What else the stack holds decides then: its font table
 * (Osaka, Seoul, Taipei...), the text and names in its resource fork, the file name, the region of its 'vers' resource,
 * and a font table of Roman fonts only (an English stack: HyperCard J puts Osaka into the table of nearly every stack made
 * with it); else the caller's fallback (the encoding of the user's language).
 *
 * The Latin variants of Mac OS Roman (Central European, Croatian, Icelandic, Romanian, Turkish) and Ukrainian share too
 * many byte values with Roman and Cyrillic to be told apart from a stack's text, so they are never guessed.
 */
final class StackEncodingDetector {
	private StackEncodingDetector() {}

	private static final StackTextEncoding[] CANDIDATES = {
		StackTextEncoding.JAPANESE, StackTextEncoding.ROMAN,
		StackTextEncoding.SIMPLIFIED_CHINESE, StackTextEncoding.TRADITIONAL_CHINESE, StackTextEncoding.KOREAN,
		StackTextEncoding.CYRILLIC, StackTextEncoding.GREEK, StackTextEncoding.HEBREW,
		StackTextEncoding.ARABIC, StackTextEncoding.THAI,
	};

	/** At most this many bytes of text with non-ASCII bytes are scored (a big stack says the same in its first ones). */
	private static final int SAMPLE_LIMIT = 1<<20;
	/** Below this average score (or with less than one solid character) the text does not decide. */
	private static final double MIN_AVERAGE = 0.8, MIN_TOTAL = 2.0;

	/**
	 * The encoding of a stack's data fork. {@code extraText} are more strings in the stack's encoding (the file name
	 * stored in a MacBinary header) and may be null; {@code fileName} is the host file name, used only when the text
	 * does not decide.
	 */
	static StackTextEncoding detect(byte[] dataFork, List<byte[]> extraText, String fileName, StackTextEncoding fallback) {
		return detect(dataFork, null, extraText, fileName, fallback);
	}

	/**
	 * {@link #detect(byte[], List, String, StackTextEncoding)} that also reads the stack's resource fork (may be null) when the
	 * data fork's text does not decide.
	 */
	static StackTextEncoding detect(byte[] dataFork, byte[] resourceFork, List<byte[]> extraText, String fileName, StackTextEncoding fallback) {
		//-Dhz.stackencoding=ROMAN|JAPANESE|…: pins the encoding (stack sweeps, or a stack the guess gets wrong)
		String forced = System.getProperty("hz.stackencoding");
		if(forced!=null) return StackTextEncoding.valueOf(forced.trim().toUpperCase(Locale.ROOT));
		List<String> fontNames = new ArrayList<String>();
		List<Integer> fontIds = new ArrayList<Integer>();
		List<byte[]> samples = dataFork==null ? new ArrayList<byte[]>() : textSamples(dataFork, fontNames, fontIds);
		if(extraText!=null) samples.addAll(extraText);
		StackTextEncoding guess = fromText(samples);
		if(guess==null) guess = fromFontNames(fontNames);
		if(guess==null && !validShiftJis(samples)) guess = StackTextEncoding.ROMAN; //too little text to score, but not Japanese
		if(guess==null && leansRoman(samples)) guess = StackTextEncoding.ROMAN;
		if(guess!=null) return guess;
		//the data fork is plain ASCII: the resource fork's strings, dialogs, menus and resource names were written on the
		//author's system too (a Japanese stack's PICT and snd names; "©" in an English one's 'vers')
		List<String> resourceFonts = new ArrayList<String>();
		int[] regions = new int[]{-1, -1};
		List<byte[]> resourceText = resourceFork==null ? new ArrayList<byte[]>() : resourceSamples(resourceFork, resourceFonts, regions);
		guess = fromText(resourceText);
		if(guess==null) guess = fromFontNames(resourceFonts);
		if(guess==null) guess = fromFileName(fileName);
		//the region of 'vers' 1 before the little text there is: FROMINE VR2's engine stack has only "Ver. 0.1 ©2003 ..." in
		//its Japanese (14) 'vers', where © is the one byte $FD
		if(guess==null) guess = fromRegion(regions[0]);
		if(guess==null && !validShiftJis(resourceText)) guess = StackTextEncoding.ROMAN;
		if(guess==null && leansRoman(resourceText)) guess = StackTextEncoding.ROMAN;
		//a font table with only Roman fonts: English stacks (HyperCard RTF: New York, Helvetica, Courier); HyperCard J puts
		//Osaka into the table of almost every stack it makes (2061 of the 2103 Japanese sample stacks, and 5 of the other 10 were
		//English ones read wrong), so a Japanese stack with no Japanese text has some other sign above
		if(guess==null && romanFontsOnly(fontIds)) guess = StackTextEncoding.ROMAN;
		if(guess==null) guess = fromHyperCardVersion(dataFork);
		if(guess==null) guess = fromRegion(regions[1]);
		return guess!=null ? guess : fallback;
	}

	// ---- text taken from the data fork ----

	/** The strings of a data fork (scripts, names, field texts, font names) that hold a non-ASCII byte. */
	static List<byte[]> textSamples(byte[] data, List<String> fontNames) {
		return textSamples(data, fontNames, new ArrayList<Integer>());
	}

	/** {@link #textSamples(byte[], List)}, also giving the font IDs of the font table. */
	static List<byte[]> textSamples(byte[] data, List<String> fontNames, List<Integer> fontIds) {
		List<byte[]> out = new ArrayList<byte[]>();
		int skip = HCData.stackOffset(data);
		if(skip>0) data = Arrays.copyOfRange(data, skip, data.length);
		if(data.length<0x20 || data[4]!='S' || data[5]!='T' || data[6]!='A' || data[7]!='K') return out;
		boolean v1 = int32(data, 0x10)==8;
		Collector c = new Collector(out);
		for(HCData.BlockSpan span : HCData.blockSpans(data, null)){
			if(c.full()) break;
			int at = span.offset, end = Math.min(data.length, at+span.size);
			try {
				switch(span.type){
				case "STAK": c.cstring(data, at+0x600, end); break;
				case "FTBL": fonts(data, at, end, c, fontNames, fontIds); break;
				case "CARD": owner(data, at, end, v1 ? 0x24 : 0x28, v1 ? 0x2C : 0x30, v1 ? 0x32 : 0x36, v1, c); break;
				case "BKGD": owner(data, at, end, v1 ? 0x20 : 0x24, v1 ? 0x28 : 0x2C, v1 ? 0x2E : 0x32, v1, c); break;
				default: break;
				}
			} catch (RuntimeException damaged) {
				//a damaged block: what was taken from it stays, the rest is left out
			}
			if(span.type.equals("TAIL")) break;
		}
		return out;
	}

	private static final class Collector {
		final List<byte[]> out;
		/** Stacks repeat the same script in many buttons and cards; each one counts once. */
		final Set<String> seen = new HashSet<String>();
		int bytes;
		Collector(List<byte[]> out){ this.out = out; }
		boolean full(){ return bytes>=SAMPLE_LIMIT; }
		/** Takes the NUL-terminated string at {@code from} (ending at {@code end} at the latest); the index of its NUL. */
		int cstring(byte[] d, int from, int end) {
			if(from<0 || from>=end) return end;
			int nul = from;
			while(nul<end && d[nul]!=0) nul++;
			add(d, from, nul);
			return nul;
		}
		void add(byte[] d, int from, int to) {
			if(full()) return;
			for(int i=from; i<to; i++){
				if(d[i]<0){
					byte[] s = withoutRepeats(d, from, to);
					if(!seen.add(new String(s, java.nio.charset.StandardCharsets.ISO_8859_1))) return;
					out.add(s);
					bytes += s.length;
					return;
				}
			}
		}
	}

	/**
	 * The bytes with every run of three or more equal non-ASCII bytes (decorative lines: ∞∞∞∞, ••••, ────), of four or
	 * more consecutive ones or six or more in rising order (character tables: ÄÅÇÉÑ…, õúü°§•ß), runs with letters next
	 * to their other case (äÄöÖüÜ, and the rest of that line) cut to a space: they would read as long words in some scripts.
	 */
	static byte[] withoutRepeats(byte[] d, int from, int to) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(to-from);
		boolean tableLine = false;
		for(int i=from; i<to; ){
			int j = i+1;
			if(d[i]=='\r' || d[i]=='\n') tableLine = false;
			if(d[i]<0) while(j<to && d[j]==d[i]) j++;
			//three Shift-JIS lead bytes before a trail byte are two characters: キャラ is 83 4C 83 83 83 89 (ColorDemo)
			if(j-i==3 && shiftJisLead(d[i]&0xFF) && j<to && shiftJisTrail(d[j]&0xFF)){ out.write(d, i, 3); i = j; continue; }
			if(j-i<3 && d[i]<0 && tableLine && (i==from || d[i-1]>=0)){
				//the rest of a line with a character table: the table's other half ("äÄöÖ…","‰ƒˆ÷…")
				int k = i+1;
				while(k<to && d[k]<0) k++;
				if(k-i>=4) j = k;
			}
			if(j-i<3 && d[i]<0){
				int k = i+1;
				while(k<to && d[k]<0 && (d[k]&0xFF)==(d[k-1]&0xFF)+1) k++;
				if(k-i>=4) j = k;
				else{
					k = i+1;
					while(k<to && d[k]<0 && (d[k]&0xFF)>(d[k-1]&0xFF)) k++;
					if(k-i>=6) j = k;
					else if(i==from || d[i-1]>=0){
						k = i+1;
						while(k<to && d[k]<0) k++;
						if(casePairs(d, i, k)>=2){ j = k; tableLine = true; }
					}
				}
			}
			if(j-i>=3 && (d[i]==d[i+1] || j-i>=4)) out.write(' ');
			else out.write(d, i, j-i);
			i = j;
		}
		return out.toByteArray();
	}

	private static boolean shiftJisLead(int c) {
		return c>=0x81 && c<=0x9F || c>=0xE0 && c<=0xFC;
	}

	private static boolean shiftJisTrail(int c) {
		return c>=0x40 && c<=0x7E || c>=0x80 && c<=0xFC;
	}

	/**
	 * Mac OS Roman letters next to their other case in a run of non-ASCII bytes ("äÄöÖüÜ"): a converter's character table
	 * (MagiCards' ReplaceCharSet), whose bytes read as kanji in Shift-JIS.
	 */
	private static int casePairs(byte[] d, int from, int to) {
		int pairs = 0;
		for(int i=from; i+1<to; i++){
			char a = MAC_ROMAN[(d[i]&0xFF)-0x80], b = MAC_ROMAN[(d[i+1]&0xFF)-0x80];
			if(a!=b && Character.isLetter(a) && Character.toLowerCase(a)==Character.toLowerCase(b)){ pairs++; i++; }
		}
		return pairs;
	}

	private static void fonts(byte[] d, int at, int end, Collector c, List<String> fontNames, List<Integer> fontIds) {
		int count = int32(d, at+0x10);
		int p = at+0x18;
		for(int i=0; i<count && p+2<end; i++){
			fontIds.add(u16(d, p));
			int from = p+2;
			int nul = c.cstring(d, from, end);
			int length = nul-from;
			StringBuilder ascii = new StringBuilder(length);
			for(int k=from; k<nul; k++) ascii.append(d[k]>=0x20 ? (char)d[k] : '?');
			fontNames.add(ascii.toString());
			p = nul+1+((length&1)==0 ? 1 : 0);
		}
	}

	/** Parts (name, script), contents (field text) and then the name and script of a card or background. */
	private static void owner(byte[] d, int at, int end, int partCountAt, int contentCountAt, int recordsAt, boolean v1, Collector c) {
		int parts = u16(d, at+partCountAt), contents = u16(d, at+contentCountAt);
		int contentSize = int32(d, at+contentCountAt+2);
		int p = at+recordsAt;
		for(int i=0; i<parts; i++){
			int size = u16(d, p);
			if(size<0x1E || p+size>end) return;
			int partEnd = p+size;
			int nameEnd = c.cstring(d, p+0x1E, partEnd);
			c.cstring(d, nameEnd+2, partEnd); //the part name is always followed by one padding byte
			p = partEnd;
		}
		int contentStart = p;
		if(v1){
			if(contentSize<0 || contentStart+contentSize>end) return;
			if(!v1Contents(d, contentStart, contentStart+contentSize, contents, false, null))
				if(!v1Contents(d, contentStart, contentStart+contentSize, contents, true, null)) return;
			if(!v1Contents(d, contentStart, contentStart+contentSize, contents, false, c))
				v1Contents(d, contentStart, contentStart+contentSize, contents, true, c);
			p = contentStart+contentSize;
		}
		else{
			for(int i=0; i<contents; i++){
				if(p+4>end) return;
				int length = u16(d, p+2);
				int from = p+4, to = from+length;
				if(to>end) return;
				if(length>0){
					if((d[from]&0x80)==0) from += 1; //plain text after one zero byte
					else from += ((d[from]&0x7F)<<8)|(d[from+1]&0xFF); //styled: the header gives where the text starts
					if(from<to) c.add(d, from, to);
				}
				p = to+(length&1);
			}
		}
		//the name and the script, with no padding between them
		int nameEnd = c.cstring(d, p, end);
		c.cstring(d, nameEnd+1, end);
	}

	/** HyperCard 1.x content records (part ID, NUL-terminated text), split as {@link HCData#readContents} does. */
	private static boolean v1Contents(byte[] d, int from, int to, int count, boolean pad, Collector c) {
		int pos = from;
		for(int i=0; i<count; i++){
			if(pos+2>to) return false;
			int start = pos+2, nul = start;
			while(nul<to && d[nul]!=0) nul++;
			if(nul>=to) return false;
			if(c!=null) c.add(d, start, nul);
			pos = nul+1;
			if(pad && ((2+nul-start+1)&1)!=0) pos++;
		}
		return pos==to || (pad && pos==to+1);
	}

	private static int u16(byte[] d, int at) {
		return ((d[at]&0xFF)<<8)|(d[at+1]&0xFF);
	}

	private static int int32(byte[] d, int at) {
		return ((d[at]&0xFF)<<24)|((d[at+1]&0xFF)<<16)|((d[at+2]&0xFF)<<8)|(d[at+3]&0xFF);
	}

	// ---- scoring ----

	/** The best-scoring encoding of these strings, or null when they do not decide (no non-ASCII text, or none fits). */
	static StackTextEncoding fromText(List<byte[]> samples) {
		StackTextEncoding best = null;
		double bestAverage = 0;
		for(StackTextEncoding encoding : CANDIDATES){
			Score s = new Score();
			for(byte[] sample : samples) score(encoding, sample, s);
			if(s.chars==0 || s.total<MIN_TOTAL) continue;
			if(!enoughText(encoding, s)) continue;
			double average = s.total/s.chars;
			if(average>bestAverage){ bestAverage = average; best = encoding; }
		}
		if(best==StackTextEncoding.ROMAN && readsAsJapanese(samples)) return null;
		return bestAverage>=MIN_AVERAGE ? best : null;
	}

	/**
	 * Too little text for Japanese, but all of it reads as Shift-JIS with nothing against it: BlackPictDemo's only non-ASCII
	 * text is "GO NEXT→" (81 A8), "GO NEXTÅ®" in Mac OS Roman. The font table and the other signs decide then.
	 */
	private static boolean readsAsJapanese(List<byte[]> samples) {
		Score s = new Score();
		for(byte[] sample : samples) shiftJis(sample, s);
		return s.chars>0 && s.against==0;
	}

	/**
	 * Too little text to decide, but what there is reads better as Mac OS Roman than as Japanese: "WWWΩ" (MacHTTP's event
	 * class, ｽ after a letter in Shift-JIS), CreyFile's "ƒmgr" card and "≈" menu line. Japanese has to show some
	 * full-width text against it.
	 */
	private static boolean leansRoman(List<byte[]> samples) {
		//¬ ≠ ≤ ≥ alone say nothing (they keep their Roman bytes in every script system)
		boolean other = false;
		for(byte[] sample : samples) for(byte c : sample) if(c<0 && !scriptOperator(c&0xFF)) other = true;
		if(!other) return false;
		Score roman = new Score(), japanese = new Score();
		for(byte[] sample : samples){ score(StackTextEncoding.ROMAN, sample, roman); score(StackTextEncoding.JAPANESE, sample, japanese); }
		return roman.total>0 && (japanese.total<roman.total || !enoughText(StackTextEncoding.JAPANESE, japanese));
	}

	/**
	 * A one-byte script needs real words: several different ones, made of its frequent letters (a table of accented
	 * letters in a converter's script, or a font sample line, reads as one long "word" in any of them). A two-byte one
	 * needs a few different plausible characters (an ASCII letter after a high byte makes a valid Big5 pair), Japanese
	 * some full-width text.
	 */
	private static boolean enoughText(StackTextEncoding encoding, Score s) {
		switch(encoding){
		case ROMAN: return true;
		//half-width kana alone are no proof: a converter's table of accented letters is a run of them
		case JAPANESE: return s.fullWidth.size()>=2;
		case SIMPLIFIED_CHINESE: case TRADITIONAL_CHINESE: case KOREAN: return s.uses.size()>=3;
		//(a few short "words" are no text: CreyWord's scrambled strings read as Arabic)
		default: return s.words.size()>=5 && s.longWords>=4 && s.wordLetters>=20 && s.frequentLetters>=0.35*s.wordLetters;
		}
	}

	/** How many times one character can count: a symbol used all over a stack (a comment marker, ¬) is still one sign. */
	private static final int REPEAT_CAP = 6;

	private static final class Score {
		final java.util.Map<Integer,Integer> uses = new java.util.HashMap<Integer,Integer>();
		/** One-byte scripts: the distinct words (runs of two or more letters), all letters in words and the frequent ones. */
		final Set<String> words = new HashSet<String>();
		int wordLetters, frequentLetters, longWords;
		/** Shift-JIS: the distinct full-width kana, common kanji and Japanese punctuation. */
		final Set<Integer> fullWidth = new HashSet<Integer>();
		double total;
		int chars;
		/** {@code code}: the byte or byte pair of the character. Only signs for the encoding are capped, not the ones against it. */
		/** How many signs against the encoding there were. */
		int against;
		void add(int code, double weight){
			if(weight<0) against++;
			if(weight>0 && uses.merge(code, 1, Integer::sum)>REPEAT_CAP) return;
			total += weight;
			chars++;
		}
	}

	private static void score(StackTextEncoding encoding, byte[] b, Score s) {
		switch(encoding){
		case JAPANESE: shiftJis(b, s); break;
		case ROMAN: roman(b, s); break;
		case SIMPLIFIED_CHINESE: gb2312(b, s); break;
		case TRADITIONAL_CHINESE: big5(b, s); break;
		case KOREAN: eucKr(b, s); break;
		case CYRILLIC: oneByteScript(b, s, CYRILLIC); break;
		case GREEK: oneByteScript(b, s, GREEK); break;
		case HEBREW: oneByteScript(b, s, HEBREW); break;
		case ARABIC: oneByteScript(b, s, ARABIC); break;
		case THAI: oneByteScript(b, s, THAI); break;
		default: break;
		}
	}

	private static boolean asciiLetter(int c) {
		return c>='A' && c<='Z' || c>='a' && c<='z';
	}

	private static int at(byte[] b, int i) {
		return i>=0 && i<b.length ? b[i]&0xFF : 0x20;
	}

	/** HyperTalk's ¬ ≠ ≤ ≥ keep their Mac OS Roman bytes in the scripts of stacks in every script system (HCData.decodeScript). */
	private static boolean scriptOperator(int c) {
		return c==0xC2 || c==0xAD || c==0xB2 || c==0xB3;
	}

	/** Shift-JIS with Apple's additions: kana are the strongest sign; a lone half-width kana between Latin letters is not Japanese. */
	private static void shiftJis(byte[] b, Score s) {
		final int HALF_KANA = 1, JAPANESE = 2;
		int n = b.length;
		int[] kind = new int[n];
		double[] weight = new double[n];
		int[] start = new int[n], code = new int[n];
		int tokens = 0;
		for(int i=0; i<n; ){
			int c = b[i]&0xFF;
			start[tokens] = i;
			if(c<0x80){ kind[tokens] = 0; weight[tokens] = Double.NaN; i++; }
			else if(c>=0xA1 && c<=0xDF){ kind[tokens] = HALF_KANA; i++; }
			else if(c==0x80 || c==0xA0 || c>=0xFD){ kind[tokens] = 0; weight[tokens] = 0; i++; } //\ nbsp © ™ …
			else{
				int t = at(b, i+1);
				if(i+1<n && (t>=0x40 && t<=0x7E || t>=0x80 && t<=0xFC)){
					code[tokens] = (c<<8)|t;
					double w = shiftJisWeight(code[tokens]);
					//kana (leads 0x82, 0x83) often have an ASCII letter as second byte: ス is 83 58
					boolean afterAscii = tokens==0 || Double.isNaN(weight[tokens-1]);
					if(w>0 && c>0x83 && afterAscii && strayPair(b, i)) w = -1;
					kind[tokens] = w>0 ? JAPANESE : 0;
					weight[tokens] = w;
					i += 2;
				}
				else{ kind[tokens] = 0; weight[tokens] = -5; i++; }
			}
			tokens++;
		}
		for(int k=0; k<tokens; k++){
			if(kind[k]==HALF_KANA){
				int c = b[start[k]]&0xFF;
				int prev = k>0 ? kind[k-1] : 0, next = k+1<tokens ? kind[k+1] : 0;
				//inside a run of three or more ("ﾏｯｷﾝﾄｯｼｭ"): JapanEase's words read as Greek letter runs otherwise
				if(prev!=0 && next!=0) s.add(c, 2.5);
				else if(prev!=0 || next!=0) s.add(c, 1.5);
				else if(scriptOperator(c)) continue;
				else if(asciiLetter(at(b, start[k]-1)) || asciiLetter(at(b, start[k]+1))) s.add(c, -2);
				else s.add(c, -0.5);
			}
			else if(!Double.isNaN(weight[k]) && weight[k]!=0){
				s.add(code[k], weight[k]);
				if(weight[k]>=1.5) s.fullWidth.add(code[k]);
			}
		}
	}

	/**
	 * A "two-byte character" at {@code i}, after ASCII text, whose second byte is an ASCII letter that more letters
	 * follow: a Mac OS Roman accented letter or bullet at the start of a word ("•Just", "Bébé") rather than Shift-JIS or Big5.
	 */
	private static boolean strayPair(byte[] b, int i) {
		return asciiLetter(at(b, i+1)) && asciiLetter(at(b, i+2));
	}

	private static double shiftJisWeight(int code) {
		if(code>=0x829F && code<=0x82F1) return 3; //hiragana
		if(code>=0x8340 && code<=0x8396) return 3; //katakana
		if(code>=0x8140 && code<=0x81AC) return 2; //、。・ー「」 and the like
		if(code>=0x81AD && code<=0x81FC) return 1;
		if(code>=0x824F && code<=0x829A) return 1; //full-width digits and letters
		if(code>=0x889F && code<=0x9872) return 1.5; //JIS level 1 kanji
		if(code>=0x839F && code<=0x889E) return 0.5; //Greek, Cyrillic, box drawing, Apple's circled numbers...
		if(code>=0x989F && code<=0xEAA4) return 0.3; //JIS level 2 kanji
		if(code>=0xEB40 && code<=0xEDFC) return 0.3; //Apple's vertical forms
		return -2;
	}

	private static final char[] MAC_ROMAN = highHalf("x-MacRoman");
	private static final String ROMAN_PUNCTUATION = "“”‘’–—…•©®™°·„‚«»¨´¸¶§†‡¢£¥¿¡";
	/** Symbols of the Mac OS Roman keyboard that English stacks use on their own (comment markers, "Folder ƒ", ¬ ≠ ≤ ≥). */
	private static final String ROMAN_SYMBOLS = "∆√∞πΩµƒ≈¬≠≤≥÷±◊∑∏∫‹›ﬁﬂ‰ˆ˜¯˘˙˚˝˛ˇıŸ¤";

	/** Mac OS Roman: an accented letter inside a word, or typographic punctuation; letters amid other high bytes are not Latin text. */
	private static void roman(byte[] b, Score s) {
		for(int i=0; i<b.length; i++){
			int c = b[i]&0xFF;
			if(c<0x80) continue;
			int prev = at(b, i-1), next = at(b, i+1);
			if(c==0xCA){ if(prev<0x80 || next<0x80) s.add(c, 2); continue; } //no-break space (" — ")
			boolean run = prev>=0x80 && prev!=0xCA && next>=0x80 && next!=0xCA || alternating(b, i);
			char ch = MAC_ROMAN[c-0x80];
			if(ROMAN_SYMBOLS.indexOf(ch)>=0){ if(!run) s.add(c, 1); }
			else if(ROMAN_PUNCTUATION.indexOf(ch)>=0){ if(!run) s.add(c, 2); }
			else if(Character.isLetter(ch)) s.add(c, run ? -0.5 : asciiLetter(prev) || asciiLetter(next) ? 3 : 1);
			else if(run) s.add(c, -0.5);
		}
	}

	/**
	 * The same high byte every other byte, three times or more ("ÉXÉ^ÉbÉN"): Shift-JIS kana or full-width letters whose
	 * second byte is ASCII, not Latin words (été has it only twice).
	 */
	private static boolean alternating(byte[] b, int i) {
		int c = b[i];
		int before = 0, after = 0;
		for(int k=i-2; k>=0 && b[k]==c && b[k+1]>=0x40; k-=2) before++; //second bytes are 0x40 and up
		for(int k=i+2; k<b.length && b[k]==c && b[k-1]>=0x40; k+=2) after++;
		return before+after>=2;
	}

	private static final Charset GBK = Charset.forName("GBK"), BIG5 = Charset.forName("Big5"), EUC_KR = Charset.forName("EUC-KR");

	/** Mac OS Simplified Chinese is EUC-CN: GB 2312 rows only. */
	private static void gb2312(byte[] b, Score s) {
		for(int i=0; i<b.length; i++){
			int c = b[i]&0xFF;
			if(c<0x80) continue;
			int t = at(b, i+1);
			if(c>=0xA1 && c<=0xF7 && i+1<b.length && t>=0xA1 && t<=0xFE){
				int code = (c<<8)|t;
				double w;
				if(c==0xA1) w = 1.5;
				else if(c==0xA3) w = 1;
				else if(c==0xA2 || c>=0xA4 && c<=0xA9) w = 0.3;
				else if(code>=0xB0A1 && code<=0xD7F9) w = 2+(FREQUENT_SIMPLIFIED.contains(decode(b, i, GBK)) ? 3 : 0);
				else if(c>=0xD8) w = 0.3;
				else w = -5;
				s.add(code, w);
				i++;
			}
			else if(c==0x80 || c==0xA0 || c>=0xFD || scriptOperator(c) && !(t>=0xA1 && t<=0xFE)) continue;
			else s.add(c, -5);
		}
	}

	/** Mac OS Traditional Chinese is Big5. */
	private static void big5(byte[] b, Score s) {
		boolean afterAscii = true;
		for(int i=0; i<b.length; i++){
			int c = b[i]&0xFF;
			boolean ascii = afterAscii;
			afterAscii = c<0x80;
			if(c<0x80) continue;
			int t = at(b, i+1);
			boolean trail = t>=0x40 && t<=0x7E || t>=0xA1 && t<=0xFE;
			//"if X≥K then" (Weird Effects): ≥ between ASCII is the operator, not a hanzi with the variable as trail byte
			if(scriptOperator(c) && ascii && t<0x80) continue;
			if(c>=0xA1 && c<=0xF9 && i+1<b.length && trail){
				int code = (c<<8)|t;
				double w;
				if(code<=0xA3BF) w = 1.5;
				else if(code>=0xA440 && code<=0xC67E) w = 2+(FREQUENT_TRADITIONAL.contains(decode(b, i, BIG5)) ? 3 : 0);
				else if(code>=0xC940 && code<=0xF9D5) w = 0.3;
				else w = -1;
				if(w>0 && ascii && strayPair(b, i)) w = -1;
				s.add(code, w);
				i++;
			}
			else if(c==0x80 || c==0xA0 || c>=0xFD || scriptOperator(c) && !trail) continue;
			else s.add(c, -5);
		}
	}

	/** Mac OS Korean is EUC-KR (KS X 1001). */
	private static void eucKr(byte[] b, Score s) {
		for(int i=0; i<b.length; i++){
			int c = b[i]&0xFF;
			if(c<0x80) continue;
			int t = at(b, i+1);
			if(c>=0xA1 && c<=0xFD && i+1<b.length && t>=0xA1 && t<=0xFE){
				double w;
				if(c==0xA1) w = 1.5;
				else if(c==0xA3) w = 1;
				else if(c>=0xB0 && c<=0xC8) w = 2+(FREQUENT_HANGUL.contains(decode(b, i, EUC_KR)) ? 3 : 0);
				else if(c>=0xCA) w = 0.3;
				else if(c<=0xAC) w = 0.3;
				else w = -5;
				s.add((c<<8)|t, w);
				i++;
			}
			else if(c==0xA0 || c>=0xFE || c<=0x84 || scriptOperator(c) && !(t>=0xA1 && t<=0xFE)) continue;
			else s.add(c, -5);
		}
	}

	private static String decode(byte[] b, int i, Charset charset) {
		return new String(b, i, 2, charset);
	}

	/** A one-byte Mac OS script: words are runs of its letters, and its most frequent letters show up often. */
	private static final class OneByte {
		final char[] table;
		final UnicodeScript script;
		final String frequent;
		OneByte(String charset, UnicodeScript script, String frequent) {
			this.table = highHalf(charset);
			this.script = script;
			this.frequent = frequent;
		}
	}

	private static final OneByte CYRILLIC = new OneByte("x-MacCyrillic", UnicodeScript.CYRILLIC, "оеаинтсрвлОЕАИНТСРВЛ");
	private static final OneByte GREEK = new OneByte("x-MacGreek", UnicodeScript.GREEK, "αοιετσνηΑΟΙΕΤΣΝΗάέίόή");
	private static final OneByte HEBREW = new OneByte("x-MacHebrew", UnicodeScript.HEBREW, "יוהאלרמתבנ");
	private static final OneByte ARABIC = new OneByte("x-MacArabic", UnicodeScript.ARABIC, "اليمونهرتب");
	private static final OneByte THAI = new OneByte("x-MacThai", UnicodeScript.THAI, "านรอกเงมยลวด่้ี");

	private static void oneByteScript(byte[] b, Score s, OneByte o) {
		StringBuilder word = new StringBuilder();
		for(int i=0; i<=b.length; i++){
			int c = i<b.length ? b[i]&0xFF : 0;
			char ch = c>=0x80 ? o.table[c-0x80] : 0;
			//Shift-JIS kana ("ÇÕÇÒÇø" = はんち, "ÉVÉXÉe" = システ) have the same lead byte every other byte: no letters of a word
			boolean shiftJisPairs = c>=0x80 && (alternating(b, i) || i>0 && b[i-1]<0 && alternating(b, i-1));
			if(c>=0x80 && UnicodeScript.of(ch)==o.script && Character.isLetter(ch) && !shiftJisPairs){
				word.append(ch);
			}
			else if(word.length()>0){
				if(word.length()>=2 && !mixedCase(word) && s.words.add(word.toString())){
					s.wordLetters += word.length();
					if(word.length()>=3) s.longWords++;
					for(int k=0; k<word.length(); k++) if(o.frequent.indexOf(word.charAt(k))>=0) s.frequentLetters++;
				}
				word.setLength(0);
			}
			if(c<0x80) continue;
			if(shiftJisPairs) s.add(c, -0.5);
			else if(UnicodeScript.of(ch)==o.script){
				boolean run = inScript(b, i-1, o) || inScript(b, i+1, o);
				if(run) s.add(c, 2+(o.frequent.indexOf(ch)>=0 ? 1.5 : 0));
				else if(asciiLetter(at(b, i-1)) || asciiLetter(at(b, i+1))) s.add(c, -1);
				else s.add(c, -0.5);
			}
			else if(scriptOperator(c)) continue;
			else if(Character.getType(ch)==Character.INITIAL_QUOTE_PUNCTUATION || Character.getType(ch)==Character.FINAL_QUOTE_PUNCTUATION ||
					Character.getType(ch)==Character.DASH_PUNCTUATION || Character.getType(ch)==Character.OTHER_PUNCTUATION) s.add(c, 0.5);
			else s.add(c, -1);
		}
	}

	/** A capital after a small letter ("иЙКъ"): Shift-JIS kanji read as Cyrillic or Greek, not a word. */
	private static boolean mixedCase(CharSequence word) {
		for(int k=1; k<word.length(); k++)
			if(Character.isUpperCase(word.charAt(k)) && Character.isLowerCase(word.charAt(k-1))) return true;
		return false;
	}

	private static boolean inScript(byte[] b, int i, OneByte o) {
		if(i<0 || i>=b.length || b[i]>=0) return false;
		return UnicodeScript.of(o.table[(b[i]&0xFF)-0x80])==o.script;
	}

	private static char[] highHalf(String charset) {
		byte[] bytes = new byte[128];
		for(int i=0; i<128; i++) bytes[i] = (byte)(0x80+i);
		String text = new String(bytes, Charset.forName(charset));
		char[] table = new char[128];
		for(int i=0; i<128; i++) table[i] = i<text.length() ? text.charAt(i) : '�';
		return table;
	}

	// ---- hints when the text does not decide ----

	/**
	 * False when a sample holds a Shift-JIS lead byte without a valid trail byte (a space, a control character, the end):
	 * such text is not Japanese. Varisco's cExportXtndFile has only "c'\u008E " (é, then a space) - undecided, it fell back to
	 * the Japanese UI's encoding and showed its accents as ﾒ/ﾓ.
	 */
	static boolean validShiftJis(List<byte[]> samples) {
		for(byte[] t : samples){
			for(int i=0; i<t.length; i++){
				int c = t[i]&0xff;
				if(c<0x81 || c>=0xa0 && c<0xe0 || c>0xfc) continue;
				int d = i+1<t.length ? t[i+1]&0xff : -1;
				if(d<0x40 || d==0x7f || d>0xfc) return false;
				i++;
			}
		}
		return true;
	}

	static StackTextEncoding fromFontNames(List<String> fontNames) {
		for(String raw : fontNames){
			String name = raw.toLowerCase(Locale.ROOT).trim();
			if(name.startsWith("osaka") || name.startsWith("heisei") || name.startsWith("hiragino") ||
				name.equals("chu gothic") || name.equals("saimincho") || name.equals("honmincho")) return StackTextEncoding.JAPANESE;
			if(name.equals("seoul") || name.equals("pusan") || name.equals("taegu") || name.equals("inchon") ||
				name.startsWith("applegothic") || name.startsWith("applemyungjo")) return StackTextEncoding.KOREAN;
			if(name.equals("taipei") || name.contains("lisung") || name.contains("ligothic")) return StackTextEncoding.TRADITIONAL_CHINESE;
			if(name.equals("beijing") || name.equals("song") || name.equals("hei") || name.equals("kai") ||
				name.equals("fang song")) return StackTextEncoding.SIMPLIFIED_CHINESE;
			if(name.endsWith(" cy")) return StackTextEncoding.CYRILLIC;
		}
		return null;
	}

	/**
	 * The HyperCard that last changed the stack (the STAK header's version at 0x6C): 2.2.1 was a Japanese release (995 of the
	 * sample stacks made with it are Japanese, 14 Roman); HyperCard 1.x stacks are nearly all English (about 300 Roman to 10
	 * Japanese among those whose text tells) - AOL's SysTask, TrapReturn, reg_exp.XFCNs have plain ASCII text and no font table.
	 * A 1.x file (format 8) with no version at all counts as 1.x too: 26 Roman to no Japanese whose text tells, and the 19
	 * plain-ASCII ones (Inigo Takes a Bath, Macsody in Blue, K'Kai Adventure) are English. Null for the others.
	 */
	static StackTextEncoding fromHyperCardVersion(byte[] data) {
		if(data==null) return null;
		int skip = HCData.stackOffset(data);
		if(skip+0x70>data.length || data[skip+4]!='S' || data[skip+5]!='T' || data[skip+6]!='A' || data[skip+7]!='K') return null;
		int version = int32(data, skip+0x6C);
		if((version>>>16)==0x0221) return StackTextEncoding.JAPANESE;
		if((version>>>24)==0x01) return StackTextEncoding.ROMAN;
		int format = int32(data, skip+0x10);
		if(version==0 && format>=1 && format<=8) return StackTextEncoding.ROMAN;
		return null;
	}

	/** A font table of fonts with IDs below 16384, the Roman script's range (Inside Macintosh: Text, 6-62; Osaka is 16384). */
	static boolean romanFontsOnly(List<Integer> fontIds) {
		if(fontIds.isEmpty()) return false;
		for(int id : fontIds) if(id>=0x4000) return false;
		return true;
	}

	/**
	 * The encoding of a 'vers' region code (-1: none): Japan 14, Korea 51, China 52, Taiwan 53; the western European, American
	 * and Australian regions are Roman. Null for the others.
	 */
	static StackTextEncoding fromRegion(int region) {
		switch(region){
		case 14: return StackTextEncoding.JAPANESE;
		case 51: return StackTextEncoding.KOREAN;
		case 52: return StackTextEncoding.SIMPLIFIED_CHINESE;
		case 53: return StackTextEncoding.TRADITIONAL_CHINESE;
		case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8: case 9: case 10: case 11: case 12:
		case 15: case 17: case 18: case 19: return StackTextEncoding.ROMAN;
		default: return null;
		}
	}

	// ---- the resource fork ----

	/**
	 * The text of a resource fork that holds a non-ASCII byte: resource names, 'STR ', 'STR#', 'TEXT', menu titles and items,
	 * dialog item texts, window and dialog titles and the 'vers' strings. {@code fontNames} gets the 'FOND' families;
	 * {@code regions} the region code of 'vers' 1 (or the first one) and of any other 'vers' (-1: none) - 'vers' 2
	 * often comes with an external from elsewhere.
	 */
	static List<byte[]> resourceSamples(byte[] fork, List<String> fontNames, int[] regions) {
		List<byte[]> out = new ArrayList<byte[]>();
		if(fork==null || fork.length<16) return out;
		Collector c = new Collector(out);
		try {
			int dataOffset = int32(fork, 0), mapOffset = int32(fork, 4);
			int typeList = mapOffset+u16(fork, mapOffset+24), nameList = mapOffset+u16(fork, mapOffset+26);
			int types = u16(fork, typeList)+1;
			for(int t=0; t<types && !c.full(); t++){
				int entry = typeList+2+t*8;
				String type = new String(fork, entry, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
				int count = u16(fork, entry+4)+1, refs = typeList+u16(fork, entry+6);
				for(int i=0; i<count && !c.full(); i++){
					int ref = refs+i*12;
					int id = (short)u16(fork, ref), nameAt = (short)u16(fork, ref+2);
					int at = dataOffset+((fork[ref+5]&0xFF)<<16|(fork[ref+6]&0xFF)<<8|(fork[ref+7]&0xFF));
					if(nameAt>=0){
						int n = nameList+nameAt;
						c.add(fork, n+1, Math.min(fork.length, n+1+(fork[n]&0xFF)));
						if(type.equals("FOND")) fontNames.add(new String(fork, n+1, fork[n]&0xFF, java.nio.charset.StandardCharsets.ISO_8859_1));
					}
					if((fork[ref+4]&1)!=0) continue; //compressed: its bytes are no text
					int length = int32(fork, at), from = at+4, end = Math.min(fork.length, from+Math.max(0, length));
					try { resourceText(type, id, fork, from, end, c, regions); }
					catch(RuntimeException damaged) { /* what was taken stays */ }
				}
			}
		} catch(RuntimeException damaged) {
			//a damaged map: what was taken from it stays
		}
		return out;
	}

	private static void resourceText(String type, int id, byte[] d, int from, int end, Collector c, int[] regions) {
		switch(type){
		case "STR ": pascal(d, from, end, c); break;
		case "STR#": { int n = u16(d, from), p = from+2; for(int i=0; i<n && p<end; i++) p = pascal(d, p, end, c); break; }
		case "TEXT": c.add(d, from, end); break;
		case "MENU": {
			int p = pascal(d, from+14, end, c);
			while(p<end && d[p]!=0){ p = pascal(d, p, end, c); p += 4; }
			break;
		}
		case "DITL": {
			int n = u16(d, from)+1, p = from+2;
			for(int i=0; i<n && p+14<=end; i++){
				int kind = d[p+12]&0x7F, length = d[p+13]&0xFF;
				if(kind==4 || kind==5 || kind==6 || kind==8 || kind==16) c.add(d, p+14, Math.min(end, p+14+length));
				p += 14+length+(length&1);
			}
			break;
		}
		case "DLOG": pascal(d, from+20, end, c); break;
		case "WIND": pascal(d, from+18, end, c); break;
		case "vers": {
			int region = u16(d, from+4);
			if(id==1 || regions[0]<0 && id!=2) regions[0] = region; else regions[1] = region;
			pascal(d, pascal(d, from+6, end, c), end, c);
			break;
		}
		default: break;
		}
	}

	/** Takes the Pascal string at {@code at}; where the next thing starts. */
	private static int pascal(byte[] d, int at, int end, Collector c) {
		if(at<0 || at>=end) return end;
		int to = Math.min(end, at+1+(d[at]&0xFF));
		c.add(d, at+1, to);
		return to;
	}

	static StackTextEncoding fromFileName(String fileName) {
		if(fileName==null) return null;
		for(int i=0; i<fileName.length(); ){
			int cp = fileName.codePointAt(i);
			i += Character.charCount(cp);
			if(cp<0x80) continue;
			switch(UnicodeScript.of(cp)){
			case HIRAGANA: case KATAKANA: return StackTextEncoding.JAPANESE;
			case HANGUL: return StackTextEncoding.KOREAN;
			case CYRILLIC: return StackTextEncoding.CYRILLIC;
			case GREEK: return StackTextEncoding.GREEK;
			case HEBREW: return StackTextEncoding.HEBREW;
			case ARABIC: return StackTextEncoding.ARABIC;
			case THAI: return StackTextEncoding.THAI;
			default: break;
			}
		}
		return null;
	}

	// ---- frequent characters ----

	private static Set<String> chars(String text) {
		Set<String> set = new HashSet<String>();
		for(int i=0; i<text.length(); i++) set.add(text.substring(i, i+1));
		return set;
	}

	/** The most frequent hanzi of modern Chinese text. */
	private static final Set<String> FREQUENT_SIMPLIFIED = chars(
		"的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里用道行所然家种事成方多经么去法学如"+
		"都同现当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明性知全三又关点正业外将两高"+
		"间由问很最重并物手应战向头文体政美相见被利什二等产或新己制身果加西斯月话合回特代内信表化老给世位次度门任常先海通教儿原东声提立及比员解"+
		"水名真论处走义各入几口认条平系气题活尔更别打女变四神总何电数安少报才结反受目太量再感建务做接必场件计管期市直德资命山金指克许统区保至队"+
		"形社便空决治展马科司五基眼书非则听白却界达光放强即像难且权思王象完设式色路记南品住告类求据程北边死张该交规万取拉格望觉术领共确传师观清"+
		"今切院让识候带导争运笑飞风步改收根干造言联持组每济车亲极林服快办议往元英士证近失转夫令准布始怎呢存未远叫台单影具罗字爱击流备兵连调深商"+
		"算质团集百需价花党华城石级整府离况亚请技际约示复病息究线似官火断精满支视消越器容照须九增研写称企八功吗包片史委乎查轻易早曾除农找装广显");

	/** The same characters in their traditional forms. */
	private static final Set<String> FREQUENT_TRADITIONAL = chars(
		"的一是不了在人有我他這個們中來上大為和國地到以說時要就出會可也你對生能而子那得於著下自之年過發後作裡用道行所然家種事成方多經麼去法學如"+
		"都同現當沒動面起看定天分還進好小部其些主樣理心她本前開但因只從想實日軍者意無力它與長把機十民第公此已工使情明性知全三又關點正業外將兩高"+
		"間由問很最重並物手應戰向頭文體政美相見被利什二等產或新己制身果加西斯月話合回特代內信表化老給世位次度門任常先海通教兒原東聲提立及比員解"+
		"水名真論處走義各入幾口認條平系氣題活爾更別打女變四神總何電數安少報才結反受目太量再感建務做接必場件計管期市直德資命山金指克許統區保至隊"+
		"形社便空決治展馬科司五基眼書非則聽白卻界達光放強即像難且權思王象完設式色路記南品住告類求據程北邊死張該交規萬取拉格望覺術領共確傳師觀清"+
		"今切院讓識候帶導爭運笑飛風步改收根乾造言聯持組每濟車親極林服快辦議往元英士證近失轉夫令準布始怎呢存未遠叫台單影具羅字愛擊流備兵連調深商"+
		"算質團集百需價花黨華城石級整府離況亞請技際約示復病息究線似官火斷精滿支視消越器容照須九增研寫稱企八功嗎包片史委乎查輕易早曾除農找裝廣顯");

	/** The most frequent hangul syllables of Korean text. */
	private static final Set<String> FREQUENT_HANGUL = chars(
		"이다의는에하고을를가한지서기로사습니대자리수도해시그있나인아어정전상일보게우부내주제거만들요구것면오소과화적와장원성국문경무동여위개생비"+
		"마안저신할되드세선연회실계터트스크러라음업본히미모및용학분통관명발공당물중방법결때금던없났했왔봤줄좀네까입감좋더잘또잡바운행품집건민형진"+
		"처럼은들에서으로부터까지에게께도만요죠네며면서고서려면한데지만니까므로위해대해통해따라관한같은다른모든어떤이런그런저런무엇누구어디언제왜어떻"+
		"게하나둘셋넷다섯번째개월년날시간분초오늘내일어제지금나중다음전후앞뒤위아래안밖옆사이중간처음마지막시작끝");
}

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The regular-expression functions of HyperTalk, with LiveCode's names and arguments:
 * matchText(text, pattern [, var1, var2 …]) is true when the pattern occurs in the text and puts the text of each
 * parenthesised group into the variables; matchChunk(text, pattern [, start1, end1, …]) puts the character positions of the
 * groups (of the whole match when the pattern has none) instead; replaceText(text, pattern, replacement) replaces every match.
 * Patterns are Java's (close to PCRE: (?i) ignores case, (?<name>…) names a group). In a replacement \1 or $1 is group 1,
 * ${name} a named group and \\ a backslash; a $ not followed by a group that exists stays as it is ("$5.00").
 * A script stopped with Command-period also stops a match that runs away (catastrophic backtracking).
 */
final class TalkRegex {
	private TalkRegex(){}

	private static final int CACHE_SIZE = 64;
	private static final Map<String,Pattern> cache = new LinkedHashMap<String,Pattern>(CACHE_SIZE, 0.75f, true){
		private static final long serialVersionUID = 1L;
		@Override protected boolean removeEldestEntry(Map.Entry<String,Pattern> eldest){ return size()>CACHE_SIZE; }
	};

	static Pattern compile(String pattern) throws xTalkException {
		if(pattern==null) pattern = "";
		synchronized(cache){
			Pattern p = cache.get(pattern);
			if(p!=null) return p;
			try{ p = Pattern.compile(pattern, Pattern.UNICODE_CASE); }
			catch(PatternSyntaxException e){
				throw new xTalkException(e.getIndex()>=0
					? "Invalid regular expression: {0} (position {1})" : "Invalid regular expression: {0}",
					e.getDescription(), e.getIndex()+1);
			}
			cache.put(pattern, p);
			return p;
		}
	}

	/** The groups of the first match (element 0 is the whole match; a group that took no part is empty), or null. */
	static String[] matchText(String text, String pattern) throws xTalkException {
		Matcher m = compile(pattern).matcher(new Interruptible(text));
		if(!find(m)) return null;
		String[] groups = new String[m.groupCount()+1];
		for(int g=0; g<groups.length; g++){ String s = m.group(g); groups[g] = s==null ? "" : s; }
		return groups;
	}

	/**
	 * The character positions (1-based, the end inclusive) of the first match: {start, end} of each group, or of the whole
	 * match when the pattern has no groups; a group that took no part gets empty positions. null when nothing matches.
	 */
	static String[] matchChunk(String text, String pattern) throws xTalkException {
		Matcher m = compile(pattern).matcher(new Interruptible(text));
		if(!find(m)) return null;
		int first = m.groupCount()==0 ? 0 : 1;
		String[] positions = new String[(m.groupCount()-first+1)*2];
		for(int g=first; g<=m.groupCount(); g++){
			int k = (g-first)*2;
			if(m.start(g)<0){ positions[k] = ""; positions[k+1] = ""; }
			else{ positions[k] = Integer.toString(m.start(g)+1); positions[k+1] = Integer.toString(m.end(g)); }
		}
		return positions;
	}

	static String replaceText(String text, String pattern, String replacement) throws xTalkException {
		if(text==null) text = "";
		Pattern p = compile(pattern);
		Matcher m = p.matcher(new Interruptible(text));
		StringBuilder out = new StringBuilder(text.length());
		int last = 0;
		//find() itself steps past an empty match, as Matcher.replaceAll does
		while(find(m)){
			out.append(text, last, m.start());
			appendReplacement(out, m, replacement==null ? "" : replacement);
			last = m.end();
		}
		out.append(text, last, text.length());
		return out.toString();
	}

	private static void appendReplacement(StringBuilder out, Matcher m, String replacement){
		int groups = m.groupCount();
		for(int i=0; i<replacement.length(); i++){
			char c = replacement.charAt(i);
			if(c=='\\' && i+1<replacement.length()){
				char d = replacement.charAt(i+1);
				if(d=='\\'){ out.append('\\'); i++; continue; }
				if(d>='0' && d<='9' && d-'0'<=groups){ append(out, m, d-'0'); i++; continue; }
			}
			else if(c=='$' && i+1<replacement.length()){
				char d = replacement.charAt(i+1);
				if(d>='0' && d<='9'){
					//the longest group number that exists ($12 is group 12 when there are 12 groups, else group 1 and "2")
					int n = d-'0', j = i+2;
					while(j<replacement.length() && Character.isDigit(replacement.charAt(j))){
						int longer = n*10+(replacement.charAt(j)-'0');
						if(longer>groups) break;
						n = longer; j++;
					}
					if(n<=groups){ append(out, m, n); i = j-1; continue; }
				}
				else if(d=='{'){
					int close = replacement.indexOf('}', i+2);
					if(close>0){
						String name = replacement.substring(i+2, close);
						try{
							String s = m.group(name);
							out.append(s==null ? "" : s);
							i = close;
							continue;
						}catch(IllegalArgumentException e){ /* no such group: literal */ }
					}
				}
			}
			out.append(c);
		}
	}

	private static void append(StringBuilder out, Matcher m, int group){
		String s = m.group(group);
		if(s!=null) out.append(s);
	}

	private static boolean find(Matcher m) throws xTalkException {
		try{ return m.find(); }
		catch(Stopped e){ throw new xTalkException("Regular expression matching was interrupted"); }
		catch(StackOverflowError e){ throw new xTalkException("Regular expression is too complex"); }
	}

	private static final class Stopped extends RuntimeException {
		private static final long serialVersionUID = 1L;
		Stopped(){ super(null, null, false, false); }
	}

	/** The text as the matcher reads it, checking now and then whether the script was stopped. */
	private static final class Interruptible implements CharSequence {
		private final String text;
		private int reads;
		Interruptible(String text){ this.text = text==null ? "" : text; }
		public int length(){ return text.length(); }
		public char charAt(int index){
			if((++reads & 0xFFFF)==0 && TTalk.stop) throw new Stopped();
			return text.charAt(index);
		}
		public CharSequence subSequence(int start, int end){ return text.substring(start, end); }
		@Override public String toString(){ return text; }
	}
}

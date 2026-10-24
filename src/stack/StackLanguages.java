import java.io.File;
import java.util.*;

/**
 * A stack's own translations: gettext catalogs in the Languages folder of the stack ({@code Languages/<tag>.po} beside
 * _stack.xml, also inside a .hzb file), whose msgid is a text as the stack has it (its names, pop-up choices, lines of
 * fields, column names, dialog texts of its scripts) and whose msgstr is that text in the language of the tag. When there
 * is a catalog for HyperZebra's language (Preferences > Language, "pt-BR" also finds pt.po), the stack is shown in it the
 * way StackTranslation shows a machine translation: only the drawing changes, so scripts still find a button by its own
 * name, a field holds and saves its own text and the authoring tools edit the originals. A stack without such a catalog,
 * or with none for the language, is shown as it is.
 * <p>
 * A line or name is looked up as it is, then without the spaces and tabs around it (an indented line keeps its indent).
 * Scripts get a text in the language with {@code localizedText(text, value1, value2...)}, which also puts the values in for
 * {1}, {2}... where the language has them (messages put together at run time).
 */
final class StackLanguages {
	private StackLanguages(){}

	static final String FOLDER = "Languages";

	/** The translations of one stack into the language in use: an empty map when it has none. */
	private static final class Loaded {
		final File folder;final String language;final long stamp;
		final Map<String,String> entries;
		long checked;
		Loaded(File folder,String language,long stamp,Map<String,String> entries){
			this.folder=folder;this.language=language;this.stamp=stamp;this.entries=entries;checked=System.currentTimeMillis();
		}
	}
	private static final Map<OStack,Loaded> loaded=Collections.synchronizedMap(new WeakHashMap<OStack,Loaded>());
	/** How often a stack's catalogs are looked at again (a catalog edited while the stack is open). */
	private static final long CHECK_MS=2000;

	/** The stack's folder: the one of its _stack.xml (the working folder of a .hzb file); null for a stack not saved yet. */
	static File folderOf(OStack st){
		if(st==null)return null;
		File f=st.file!=null?st.file:st.path==null||st.path.isEmpty()?null:new File(st.path);
		if(f==null)return null;
		String n=f.getName();
		return n.equals("_stack.xml")||n.equals("toc.xml")?f.getParentFile():f.isDirectory()?f:null;
	}

	/** The catalog files of a language in the stack's Languages folder, the most specific first ("pt-BR", "pt"). */
	private static List<File> files(File folder,String language){
		List<File> out=new ArrayList<File>();
		File dir=new File(folder,FOLDER);
		if(!dir.isDirectory())return out;
		for(String tag:International.fallbackChain(language)){
			File f=new File(dir,tag+".po");
			if(f.isFile())out.add(f);
		}
		return out;
	}
	private static long stampOf(File folder,String language){
		long s=new File(folder,FOLDER).lastModified();
		for(File f:files(folder,language))s=s*31+f.lastModified()+f.length();
		return s;
	}

	/** msgid → msgstr of the stack's catalogs for the language in use (empty: none). */
	static Map<String,String> entries(OStack st){
		File folder=folderOf(st);
		if(folder==null)return Collections.emptyMap();
		String language=International.language();
		Loaded l=loaded.get(st);
		long now=System.currentTimeMillis();
		if(l!=null&&l.folder.equals(folder)&&l.language.equals(language)){
			if(now-l.checked<CHECK_MS)return l.entries;
			l.checked=now;
			if(l.stamp==stampOf(folder,language))return l.entries;
		}
		long stamp=stampOf(folder,language);
		Map<String,String> entries=new HashMap<String,String>();
		List<File> files=files(folder,language);
		for(int i=files.size()-1;i>=0;i--){ // the most specific catalog wins
			try(java.io.Reader r=new java.io.InputStreamReader(new java.io.FileInputStream(files.get(i)),java.nio.charset.StandardCharsets.UTF_8)){
				for(Map.Entry<String,String> e:International.Catalog.read(r).entries.entrySet())
					if(e.getKey().indexOf(International.Catalog.CONTEXT_SEPARATOR)<0)entries.put(e.getKey(),e.getValue());
			}catch(java.io.IOException e){System.err.println("Could not read the stack's catalog "+files.get(i)+": "+e.getMessage());}
		}
		Map<String,String> result=entries.isEmpty()?Collections.<String,String>emptyMap():Collections.unmodifiableMap(entries);
		loaded.put(st,new Loaded(folder,language,stamp,result));
		return result;
	}

	/** Whether the stack has translations into the language in use. */
	static boolean has(OStack st){return !entries(st).isEmpty();}

	/** A text in the language in use: its translation (an indented line keeps its indent), or null when there is none. */
	static String lookup(OStack st,String text){
		if(text==null||text.isEmpty())return null;
		Map<String,String> e=entries(st);
		if(e.isEmpty())return null;
		return lookup(e,text);
	}
	static String lookup(Map<String,String> e,String text){
		String s=e.get(text);
		if(s!=null)return s;
		int a=0,b=text.length();
		while(a<b&&(text.charAt(a)==' '||text.charAt(a)=='\t'))a++;
		while(b>a&&(text.charAt(b-1)==' '||text.charAt(b-1)=='\t'))b--;
		if(a==0&&b==text.length()||a==b)return null;
		s=e.get(text.substring(a,b));
		return s==null?null:text.substring(0,a)+s+text.substring(b);
	}

	/**
	 * localizedText(text, value1, value2...): the text in the language in use (itself when the stack has no translation of
	 * it), with {1}, {2}... replaced by the values, so that each language puts them in its own order. A text with returns
	 * is translated line by line.
	 */
	static String localizedText(OStack st,String text,String[] values){
		if(text==null)text="";
		Map<String,String> e=entries(st);
		String s=e.isEmpty()?null:lookup(e,text);
		if(s==null&&!e.isEmpty()&&(text.indexOf('\r')>=0||text.indexOf('\n')>=0)){
			StringBuilder b=new StringBuilder();
			java.util.regex.Matcher m=java.util.regex.Pattern.compile("\r\n|\r|\n").matcher(text);
			int at=0;
			while(true){
				boolean more=m.find();
				String line=text.substring(at,more?m.start():text.length());
				String t=lookup(e,line);
				b.append(t==null?line:t);
				if(!more)break;
				b.append(m.group());at=m.end();
			}
			s=b.toString();
		}
		if(s==null)s=text;
		if(values==null||values.length==0||s.indexOf('{')<0)return s;
		Object[] args=new Object[values.length+1];
		args[0]="{0}";
		System.arraycopy(values,0,args,1,values.length);
		return International.format(s,args);
	}
}

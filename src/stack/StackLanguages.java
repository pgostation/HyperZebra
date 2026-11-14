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
		if(off())return Collections.emptyMap();
		File folder=folderOf(st);
		// a stack the player read from its .hzb as it is (the browser: no working folder): the catalogs inside it
		StackSource source=HeadlessStacks.sourceOf(st);
		if(source!=null&&(folder==null||!new File(folder,FOLDER).isDirectory()))return packedEntries(st,source);
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

	/** The stacks are shown as they are, without their own translations (-Dhz.stack.languages=off: the browser page's "Original") */
	static boolean off(){return "off".equals(System.getProperty("hz.stack.languages"));}

	/** The catalogs of a stack read from its .hzb as it is (HeadlessStacks), by language: they do not change while it is open */
	private static final Map<OStack,Object[]> packed=Collections.synchronizedMap(new WeakHashMap<OStack,Object[]>());
	private static Map<String,String> packedEntries(OStack st,StackSource source){
		String language=International.language();
		Object[] p=packed.get(st);
		if(p!=null&&p[0].equals(language)){
			@SuppressWarnings("unchecked") Map<String,String> m=(Map<String,String>)p[1];
			return m;
		}
		Map<String,String> entries=new HashMap<String,String>();
		List<String> chain=International.fallbackChain(language);
		for(int i=chain.size()-1;i>=0;i--){ // the most specific catalog wins
			byte[] b;
			try{b=source.read(FOLDER+"/"+chain.get(i)+".po");}catch(java.io.IOException e){b=null;}
			if(b==null)continue;
			try(java.io.Reader r=new java.io.InputStreamReader(new java.io.ByteArrayInputStream(b),java.nio.charset.StandardCharsets.UTF_8)){
				for(Map.Entry<String,String> e:International.Catalog.read(r).entries.entrySet())
					if(e.getKey().indexOf(International.Catalog.CONTEXT_SEPARATOR)<0)entries.put(e.getKey(),e.getValue());
			}catch(java.io.IOException e){System.err.println("Could not read the stack's catalog "+FOLDER+"/"+chain.get(i)+".po: "+e.getMessage());}
		}
		Map<String,String> result=entries.isEmpty()?Collections.<String,String>emptyMap():Collections.unmodifiableMap(entries);
		packed.put(st,new Object[]{language,result});
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
		if(a==b)return null;
		String core=text.substring(a,b);
		if(a>0||b<text.length())s=e.get(core);
		if(s==null)s=Template.fill(e,core);
		return s==null?null:text.substring(0,a)+s+text.substring(b);
	}

	/**
	 * An entry whose text has values in it, {1}, {2}... ("{1}位です！" → "You are number {1}!"): what a script puts
	 * together at run time (answer JUNI & "位です！") is shown with its own values put into the translation.
	 */
	static final class Template {
		/** The written parts (parts.length = slots.length + 1) and the value number of each slot between them */
		private final String[] parts;
		private final int[] slots;
		final String translation;
		private Template(String[] parts,int[] slots,String translation){this.parts=parts;this.slots=slots;this.translation=translation;}
		/** null: no {n} in the text, or two values side by side (where one ends cannot be told) */
		static Template of(String text,String translation){
			List<String> parts=new ArrayList<String>();List<Integer> slots=new ArrayList<Integer>();
			int at=0;StringBuilder part=new StringBuilder();
			while(at<text.length()){
				int n=-1,end=-1;
				if(text.charAt(at)=='{'){
					int j=at+1;
					while(j<text.length()&&j-at<4&&Character.isDigit(text.charAt(j)))j++;
					if(j>at+1&&j<text.length()&&text.charAt(j)=='}'){n=Integer.parseInt(text.substring(at+1,j));end=j+1;}
				}
				if(n<1){part.append(text.charAt(at++));continue;}
				if(!slots.isEmpty()&&part.length()==0)return null;
				parts.add(part.toString());part.setLength(0);slots.add(n);at=end;
			}
			if(slots.isEmpty())return null;
			parts.add(part.toString());
			if(String.join("",parts).trim().length()<2)return null; // ("{1}" would make every text)
			int[] s=new int[slots.size()];for(int i=0;i<s.length;i++)s[i]=slots.get(i);
			return new Template(parts.toArray(new String[0]),s,translation);
		}
		/** The values of a text this template makes (value n at index n-1), or null when it does not make it */
		String[] values(String text){
			if(!text.startsWith(parts[0])||!text.endsWith(parts[parts.length-1])||text.length()<parts[0].length()+parts[parts.length-1].length()+slots.length)return null;
			int max=0;for(int n:slots)max=Math.max(max,n);
			String[] v=new String[max];
			return match(text,parts[0].length(),0,v)?v:null;
		}
		private boolean match(String text,int at,int slot,String[] v){
			String next=parts[slot+1];
			boolean last=slot==slots.length-1;
			for(int i=last?text.length()-next.length():text.indexOf(next,at+1);i>at&&i>=0;i=last?-1:text.indexOf(next,i+1)){
				if(last&&!text.startsWith(next,i))break;
				String value=text.substring(at,i),old=v[slots[slot]-1];
				if(old!=null&&!old.equals(value))continue;
				v[slots[slot]-1]=value;
				if(last||match(text,i+next.length(),slot+1,v))return true;
				v[slots[slot]-1]=old;
			}
			return false;
		}
		String fill(String[] values){
			StringBuilder b=new StringBuilder();
			for(int i=0;i<translation.length();){
				char c=translation.charAt(i);
				int j=i+1;
				if(c=='{'){while(j<translation.length()&&j-i<4&&Character.isDigit(translation.charAt(j)))j++;}
				if(c=='{'&&j>i+1&&j<translation.length()&&translation.charAt(j)=='}'){
					int n=Integer.parseInt(translation.substring(i+1,j));
					if(n>=1&&n<=values.length&&values[n-1]!=null){b.append(values[n-1]);i=j+1;continue;}
				}
				b.append(c);i++;
			}
			return b.toString();
		}
		/** Whether a translation of a template still has each of its values (translators can drop or change them) */
		static boolean keepsValues(String text,String translation){
			Template t=of(text,translation);
			if(t==null)return true;
			for(int n:t.slots)if(!translation.contains("{"+n+"}"))return false;
			String rest=translation.replaceAll("\\{[0-9]{1,3}\\}","");
			return rest.indexOf('{')<0&&rest.indexOf('}')<0; // a value broken up ("{ If you press 1}")
		}
		private static final Map<Map<String,String>,List<Template>> all=Collections.synchronizedMap(new WeakHashMap<Map<String,String>,List<Template>>());
		/** The text made by one of the catalog's templates, with its values in the translation; null: none makes it */
		static String fill(Map<String,String> e,String text){
			List<Template> list=all.get(e);
			if(list==null){
				list=new ArrayList<Template>();
				for(Map.Entry<String,String> x:e.entrySet())if(x.getKey().indexOf('{')>=0){Template t=of(x.getKey(),x.getValue());if(t!=null)list.add(t);}
				all.put(e,list);
			}
			for(Template t:list){String[] v=t.values(text);if(v!=null)return t.fill(v);}
			return null;
		}
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

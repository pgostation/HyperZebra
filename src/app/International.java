import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * HyperZebra's user interface language. The program is written in English: every text shown to the user is the English
 * text itself, and a language is a gettext catalog ({@code lang/<tag>.po}) that maps the English texts (msgid) to their
 * translations (msgstr). A text that a catalog does not translate is shown in English.
 *
 * <p>Adding a language needs no code: put {@code <tag>.po} (a BCP 47 tag: "fr", "de", "zh-Hans", "pt-BR"...) in the
 * project's {@code lang} folder (built into the jar), or in the {@code Languages} folder of HyperZebra's state folder
 * (AppPaths.stateDirectory(), read at start-up, also to try a translation without building). The Settings pop-up lists
 * every catalog found. {@code lang/HyperZebra.pot} is the template with every text to translate and {@code tools/i18n}
 * keeps it and the catalogs up to date; see {@code lang/README.md}.
 *
 * <p>Contexts (msgctxt) keep apart the same English word used in two places: "menu" for menu items and tool names
 * ({@link #getText}), none for dialogs, panels and messages ({@link #getDialogText}, {@link #t}), and others for larger
 * texts that have their own place (for example "help" for the help topics, {@link #tc}).
 *
 * <p>The language is kept as a BCP 47 tag ({@link PCARD#language}, "en", "ja"...). Settings saved by older versions
 * ("English", "Japanese") and the names that scripts see ({@code the systemLang}, {@link #englishName}) are the English
 * names of the languages.
 */
class International {
	/** The language every text is written in. */
	static final String SOURCE_LANGUAGE = "en";
	/** The context of menu items and tool names; dialogs and messages have none. */
	static final String MENU = "menu";

	private final String language;
	private final Map<String,String> translations;  // key(context, English) -> translation
	private final Map<String,String> reverse;       // key(context, translation) -> English

	public International(String lang) throws Exception{
		language = normalize(lang);
		if(language==null) throw new Exception("Unsupported Language \""+lang+"\"");
		translations = new LinkedHashMap<String,String>(); //ファイルの順(逆引きは同じ訳の後の項目が勝つ)
		reverse = new HashMap<String,String>();
		//"pt"の上に"pt-BR"を重ねる(地域ごとの訳は言語の訳を一部だけ置き換えればよい)
		for(String tag: fallbackChain(language)){
			if(tag.equals(SOURCE_LANGUAGE)) continue;
			for(Catalog c: Catalog.sources(tag)) translations.putAll(c.entries);
		}
		for(Map.Entry<String,String> e: translations.entrySet()){
			int sep = e.getKey().indexOf(Catalog.CONTEXT_SEPARATOR);
			String context = sep<0 ? "" : e.getKey().substring(0, sep);
			String english = sep<0 ? e.getKey() : e.getKey().substring(sep+1);
			reverse.put(Catalog.key(context, e.getValue()), english);
		}
	}

	/** This catalog's language (a BCP 47 tag). */
	String tag(){ return language; }

	/** The text in this language with a context (null or "" for none); the English text when it is not translated. */
	String translate(String context, String english){
		if(english==null) return null;
		String ret = translations.get(Catalog.key(context, english));
		return ret==null ? english : ret;
	}
	/** The English text of a translated one (a menu item or a pop-up choice as shown); the text itself when it is not a translation. */
	String english(String context, String text){
		if(text==null) return null;
		String ret = reverse.get(Catalog.key(context, text));
		return ret==null ? text : ret;
	}

	//メニュー項目・ツール名(文脈"menu")
	public String getText(String name){ return translate(MENU, name); }
	public String getEngText(String name){ return english(MENU, name); }
	public String getToolText(String name){ return getText(name); }
	public String getToolEngText(String name){ return getEngText(name); }
	//ダイアログ・パネル・メッセージ(文脈なし)
	public String getDialogText(String name){ return translate(null, name); }
	public String getDialogEngText(String name){ return english(null, name); }

	//---------------------------------------------------------------- どこからでも使える形

	private static International beforeStart;

	/**
	 * The catalog of the language in use: HyperZebra's ({@code PCARD.pc.intl}); before it is made (or without a stack window,
	 * as in tests) the one of {@link PCARD#language}.
	 */
	static International current(){
		if(PCARD.pc!=null && PCARD.pc.intl!=null) return PCARD.pc.intl;
		String tag = normalize(PCARD.language);
		if(tag==null) tag = SOURCE_LANGUAGE;
		synchronized(International.class){
			if(beforeStart==null || !beforeStart.language.equals(tag)){
				try{ beforeStart = new International(tag); }
				catch(Exception e){ throw new IllegalStateException(e); }
			}
			return beforeStart;
		}
	}
	/** A dialog or message text in the language in use. */
	static String t(String english){ return current().translate(null, english); }
	/** The same with {0}, {1}... in the (translated) text replaced by the arguments, so that a language can put them in its own order. */
	static String t(String english, Object... args){ return format(t(english), args); }
	/** A text with its own context (for example "help"). */
	static String tc(String context, String english){ return current().translate(context, english); }
	/** The same with {0}, {1}... replaced. */
	static String tc(String context, String english, Object... args){ return format(tc(context, english), args); }

	/** Replaces {0}, {1}... with the arguments (only those: a brace elsewhere stays as it is, unlike MessageFormat). */
	static String format(String text, Object... args){
		if(args==null || args.length==0 || text.indexOf('{')<0) return text;
		StringBuilder sb = new StringBuilder(text.length()+16);
		for(int i=0; i<text.length(); i++){
			char c = text.charAt(i);
			if(c=='{'){
				int close = text.indexOf('}', i+1);
				if(close>i+1 && close-i<=4){
					String n = text.substring(i+1, close);
					if(n.chars().allMatch(Character::isDigit)){
						int index = Integer.parseInt(n);
						if(index<args.length){ sb.append(args[index]); i = close; continue; }
					}
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	//---------------------------------------------------------------- 言語の名前

	/** The language in use (a BCP 47 tag). */
	static String language(){
		String lang = PCARD.pc!=null && PCARD.pc.lang!=null ? PCARD.pc.lang : PCARD.language;
		String tag = normalize(lang);
		return tag==null ? SOURCE_LANGUAGE : tag;
	}
	/** Whether the language in use is {@code tag} or one of its regional forms ("pt" is true for "pt-BR"). */
	static boolean uses(String tag){ return sameLanguage(language(), tag); }
	/** Whether a language setting (a tag or an older name) is the language {@code tag}. */
	static boolean sameLanguage(String lang, String tag){
		String a = normalize(lang);
		if(a==null || tag==null) return false;
		return Locale.forLanguageTag(a).getLanguage().equals(Locale.forLanguageTag(tag).getLanguage());
	}

	/**
	 * The BCP 47 tag of a language setting: a tag ("ja", "pt_BR", "zh-Hans") or the English or native name a setting of an
	 * older version or a test gives ("Japanese", "English", "日本語"); null when it is not a language.
	 */
	static String normalize(String lang){
		if(lang==null) return null;
		String s = lang.trim();
		if(s.isEmpty()) return null;
		String byName = NAMES.get(s.toLowerCase(Locale.ROOT));
		if(byName!=null) return byName;
		Locale l = Locale.forLanguageTag(s.replace('_', '-'));
		if(l.getLanguage().isEmpty()) return null;
		return l.toLanguageTag();
	}
	private static final Map<String,String> NAMES = new HashMap<String,String>();
	static {
		NAMES.put("english", "en"); NAMES.put("japanese", "ja"); NAMES.put("日本語", "ja");
	}

	/** The name shown in the language pop-up: the language's own name ("日本語", "Français"), so that its readers find it. */
	static String displayName(String lang){
		String tag = normalize(lang);
		if(tag==null) return String.valueOf(lang);
		String header = Catalog.headerValue(tag, "X-Language-Name");
		if(header!=null && !header.isEmpty()) return header;
		Locale l = Locale.forLanguageTag(tag);
		String name = l.getDisplayName(l);
		if(name.isEmpty()) return tag;
		return name.substring(0, 1).toUpperCase(l)+name.substring(1);
	}
	/** The English name of a language ("Japanese", "English", "French"): what {@code the systemLang} gives scripts. */
	static String englishName(String lang){
		String tag = normalize(lang);
		if(tag==null) return String.valueOf(lang);
		String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
		return name.isEmpty() ? tag : name;
	}

	/** "pt-BR" -> pt-BR, pt; "zh-Hant-TW" -> zh-Hant-TW, zh-Hant, zh (the most specific first). */
	static List<String> fallbackChain(String tag){
		List<String> chain = new ArrayList<String>();
		String t = tag;
		while(t!=null && !t.isEmpty()){
			chain.add(t);
			int dash = t.lastIndexOf('-');
			t = dash<0 ? null : t.substring(0, dash);
		}
		if(tag!=null){
			Locale locale = Locale.forLanguageTag(tag);
			if(locale.getLanguage().equals("zh") && locale.getScript().isEmpty()){
				String country = locale.getCountry();
				String script = country.equals("TW") || country.equals("HK") || country.equals("MO") ? "zh-Hant" : "zh-Hans";
				// A regional catalog wins; infer its writing system before falling back to generic Chinese.
				int generic = chain.indexOf("zh");
				chain.add(tag.equals("zh") ? chain.size() : generic, script);
			}
		}
		return chain;
	}

	/** Every language there is a catalog for, English first. */
	static List<String> availableLanguages(){
		TreeSet<String> tags = new TreeSet<String>(Catalog.availableTags());
		tags.remove(SOURCE_LANGUAGE);
		List<String> list = new ArrayList<String>();
		list.add(SOURCE_LANGUAGE);
		list.addAll(tags);
		return list;
	}

	/**
	 * The language to use when none is chosen: the one of the OS (or the closest there is a catalog for: "pt" for a
	 * Brazilian system without "pt-BR"), else English.
	 */
	static String defaultLanguage(){
		Locale os = Locale.getDefault();
		List<String> available = availableLanguages();
		for(String candidate: fallbackChain(os.toLanguageTag())){
			for(String tag: available) if(tag.equalsIgnoreCase(candidate)) return tag;
		}
		// Other languages may have only a regional catalog.
		for(String tag: available) if(Locale.forLanguageTag(tag).getLanguage().equals(os.getLanguage())) return tag;
		return SOURCE_LANGUAGE;
	}

	/** The language of a saved setting when there is still a catalog for it, else {@link #defaultLanguage()}. */
	static String chosenLanguage(String saved){
		String tag = normalize(saved);
		if(tag!=null){
			if(tag.equals(SOURCE_LANGUAGE)) return tag;
			for(String candidate: fallbackChain(tag)){
				for(String available: availableLanguages()) if(available.equalsIgnoreCase(candidate)) return available;
			}
		}
		return defaultLanguage();
	}

	//---------------------------------------------------------------- カタログ(.po)

	/** One gettext catalog: the translated entries of a .po file (untranslated and fuzzy ones are left out). */
	static final class Catalog {
		static final char CONTEXT_SEPARATOR = '\u0004'; //gettextの.moと同じ区切り
		static String key(String context, String english){
			return context==null || context.isEmpty() ? english : context+CONTEXT_SEPARATOR+english;
		}

		final Map<String,String> entries = new LinkedHashMap<String,String>();
		final Map<String,String> header = new LinkedHashMap<String,String>();

		/** The folder of extra or replacement catalogs of the user: Languages in HyperZebra's state folder. */
		static File userFolder(){ return new File(AppPaths.stateDirectory(), "Languages"); }
		/** The project's lang folder, when HyperZebra runs from the project (an IDE that does not copy it into the classes). */
		private static File projectFolder(){ return new File("lang"); }

		private static final Map<String,List<Catalog>> loaded = new HashMap<String,List<Catalog>>();

		/** The catalogs of one tag: the built-in one, then the user's (whose entries win). */
		static synchronized List<Catalog> sources(String tag){
			List<Catalog> list = loaded.get(tag);
			if(list!=null) return list;
			list = new ArrayList<Catalog>();
			String file = tag+".po";
			Catalog builtIn = null;
			try(InputStream in = International.class.getResourceAsStream("/lang/"+file)){
				if(in!=null) builtIn = read(new InputStreamReader(in, StandardCharsets.UTF_8));
			}catch(IOException e){ System.err.println("Could not read the catalog lang/"+file+": "+e.getMessage()); }
			if(builtIn==null) builtIn = readFile(new File(projectFolder(), file));
			if(builtIn!=null) list.add(builtIn);
			Catalog user = readFile(new File(userFolder(), file));
			if(user!=null) list.add(user);
			loaded.put(tag, list);
			return list;
		}
		static synchronized void forgetLoaded(){ loaded.clear(); }

		private static Catalog readFile(File f){
			if(!f.isFile()) return null;
			try(Reader r = new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8)){
				return read(r);
			}catch(IOException e){
				System.err.println("Could not read the catalog "+f+": "+e.getMessage());
				return null;
			}
		}

		static String headerValue(String tag, String name){
			String value = null;
			for(Catalog c: sources(tag)){ String v = c.header.get(name); if(v!=null) value = v; }
			return value;
		}

		/** The tags of the catalogs in the jar (or the classes folder), the project's lang folder and the user's folder. */
		static Set<String> availableTags(){
			Set<String> tags = new java.util.HashSet<String>();
			try{
				Enumeration<URL> roots = International.class.getClassLoader().getResources("lang");
				while(roots.hasMoreElements()) addTags(roots.nextElement(), tags);
			}catch(IOException e){ /* 一覧が取れなければ、フォルダの分だけ */ }
			try{
				URL code = International.class.getProtectionDomain().getCodeSource().getLocation();
				if(code!=null && code.getProtocol().equals("file")){
					File f = new File(code.toURI());
					if(f.isFile()) addJarTags(f, tags);
				}
			}catch(Exception e){ /* 同上 */ }
			addFolderTags(projectFolder(), tags);
			addFolderTags(userFolder(), tags);
			return tags;
		}
		private static void addTags(URL url, Set<String> tags){
			try{
				if(url.getProtocol().equals("file")) addFolderTags(new File(url.toURI()), tags);
			}catch(Exception e){ /* jarの中は addJarTags で */ }
		}
		private static void addJarTags(File jar, Set<String> tags) throws IOException{
			try(JarFile jf = new JarFile(jar)){
				Enumeration<JarEntry> en = jf.entries();
				while(en.hasMoreElements()){
					String name = en.nextElement().getName();
					if(name.startsWith("lang/") && name.endsWith(".po") && name.indexOf('/', 5)<0) addTag(name.substring(5, name.length()-3), tags);
				}
			}
		}
		private static void addFolderTags(File dir, Set<String> tags){
			String[] names = dir.list();
			if(names==null) return;
			for(String name: names) if(name.endsWith(".po")) addTag(name.substring(0, name.length()-3), tags);
		}
		private static void addTag(String name, Set<String> tags){
			String tag = normalize(name);
			if(tag!=null && tag.equalsIgnoreCase(name.replace('_', '-'))) tags.add(tag);
		}

		/**
		 * Reads a .po file: msgctxt / msgid / msgstr with continued "..." lines and the C escapes (\n \t \" \\), # comments,
		 * "#, fuzzy" entries (skipped, as gettext does). Plural forms are not used by HyperZebra (msgstr[0] is taken).
		 */
		static Catalog read(Reader reader) throws IOException{
			Catalog c = new Catalog();
			BufferedReader in = new BufferedReader(reader);
			String context = null, id = null, str = null, field = null;
			boolean fuzzy = false;
			StringBuilder value = new StringBuilder();
			for(int lineNumber=1; ; lineNumber++){
				String line = in.readLine();
				String s = line==null ? "" : line.trim();
				if(lineNumber==1 && s.startsWith("\uFEFF")) s = s.substring(1).trim();
				if(line!=null && s.startsWith("\"")){
					if(field==null) throw new IOException("line "+lineNumber+": a string outside an entry");
					value.append(unquote(s, lineNumber));
					continue;
				}
				//ここで続きの行が終わる
				if("msgctxt".equals(field)) context = value.toString();
				else if("msgid".equals(field)) id = value.toString();
				else if("msgstr".equals(field)) str = value.toString();
				field = null;
				//空行・コメント・次の項目の始まりで、前の項目を登録する
				boolean next = line==null || s.isEmpty() || s.startsWith("#") || s.startsWith("msgctxt") || (s.startsWith("msgid") && !s.startsWith("msgid_plural"));
				if(next && str!=null){
					if(id!=null) c.add(context, id, str, fuzzy);
					context = null; id = null; str = null; fuzzy = false;
				}
				if(line==null) break;
				if(s.isEmpty()) continue;
				if(s.startsWith("#")){
					if(s.startsWith("#,") && s.contains("fuzzy")) fuzzy = true;
					continue;
				}
				int space = s.indexOf(' ');
				if(space<0) throw new IOException("line "+lineNumber+": "+s);
				String keyword = s.substring(0, space);
				if(keyword.equals("msgstr[0]")) keyword = "msgstr";
				else if(keyword.startsWith("msgstr[") || keyword.equals("msgid_plural")) keyword = "ignored";
				else if(!keyword.equals("msgctxt") && !keyword.equals("msgid") && !keyword.equals("msgstr")) throw new IOException("line "+lineNumber+": unknown keyword "+keyword);
				field = keyword;
				value.setLength(0);
				value.append(unquote(s.substring(space+1).trim(), lineNumber));
			}
			return c;
		}

		private void add(String context, String id, String str, boolean fuzzy){
			if(id.isEmpty() && (context==null || context.isEmpty())){
				for(String h: str.split("\n")){
					int colon = h.indexOf(':');
					if(colon>0) header.put(h.substring(0, colon).trim(), h.substring(colon+1).trim());
				}
				return;
			}
			if(fuzzy || str.isEmpty()) return;
			entries.put(key(context, id), str);
		}

		private static String unquote(String s, int lineNumber) throws IOException{
			if(s.length()<2 || s.charAt(0)!='"' || s.charAt(s.length()-1)!='"') throw new IOException("line "+lineNumber+": not a quoted string: "+s);
			StringBuilder sb = new StringBuilder(s.length());
			for(int i=1; i<s.length()-1; i++){
				char ch = s.charAt(i);
				if(ch=='\\' && i+1<s.length()-1){
					char n = s.charAt(++i);
					switch(n){
						case 'n': sb.append('\n'); break;
						case 't': sb.append('\t'); break;
						case 'r': sb.append('\r'); break;
						case '"': sb.append('"'); break;
						case '\\': sb.append('\\'); break;
						default: sb.append('\\').append(n);
					}
				}
				else sb.append(ch);
			}
			return sb.toString();
		}

		/** A text as a .po string (the C escapes; a text with line breaks as one "..." line for each). */
		static String quote(String text){
			String s = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t").replace("\r", "\\r");
			if(s.indexOf('\n')<0) return "\""+s+"\"";
			StringBuilder sb = new StringBuilder("\"\"");
			int start = 0;
			while(start<s.length()){
				int nl = s.indexOf('\n', start);
				int end = nl<0 ? s.length() : nl+1;
				sb.append("\n\"").append(s, start, nl<0 ? end : nl).append(nl<0 ? "" : "\\n").append('"');
				start = end;
			}
			return sb.toString();
		}
	}
}

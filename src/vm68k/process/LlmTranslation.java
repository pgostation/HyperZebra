import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Resource Translation through an AI chat (ChatGPT, Claude, Gemini…) the user pastes into: the texts a translation ahead
 * would send to macOS's translators (VMTranslation.Ahead / StackTranslation.ahead, collecting instead of sending) are
 * written out as a request and a JSON list; the user copies that into the chat, pastes the JSON it answers back, and
 * {@link #apply} keeps its translations in the cache as if the translators had given them. A program's translated
 * resources are then written from the cache ({@link #finish}) as by "Resource Translation…"; a stack is shown translated.
 * Needs no translator on this computer (any system, any macOS).
 *
 * A long program goes in parts ({@link #parts}) small enough for a chat to answer whole; each text has its own id,
 * so the answers of the parts can come back in any order, also several at once.
 */
final class LlmTranslation {
	/** One text to translate: its id in the request, what it is, the text. */
	static final class Item {
		final int id;final VMTranslation.Kind kind;final String text;
		/** Lines joined into one sentence (a hand-wrapped story): shared out over them, so no length limit of its own. */
		final boolean joined;
		Item(int id,VMTranslation.Kind kind,String text,boolean joined){this.id=id;this.kind=kind;this.text=text;this.joined=joined;}
	}

	/** Part sizes: characters of the request's JSON (about what the chat writes back). */
	static final int SMALL=4000,MEDIUM=12000,LARGE=40000;

	final File file;
	final boolean stack;
	final VMTranslation.Ahead ahead;
	final List<Item> items;
	private final Map<Integer,Item> byId=new HashMap<Integer,Item>();
	/** Ids whose translations were kept. */
	private final Set<Integer> done=new HashSet<Integer>();
	/** Why an answer was not kept (id -> reason, in English for the chat): told to the chat when the text is asked again. */
	private final Map<Integer,String> problems=new HashMap<Integer,String>();
	/** How the translations are written into the program's resources (null for a stack). */
	private final StackTextEncoding encoding;

	private LlmTranslation(File file,boolean stack,VMTranslation.Ahead ahead){
		this.file=file;this.stack=stack;this.ahead=ahead;
		List<Item> list=new ArrayList<Item>();
		List<Object[]> collected=ahead.translator.collecting;
		if(collected!=null)synchronized(collected){
			for(Object[] c:collected){
				String text=(String)c[1];
				Item it=new Item(list.size()+1,(VMTranslation.Kind)c[0],text,ahead.translator.joined(text));
				list.add(it);byId.put(it.id,it);
			}
		}
		items=Collections.unmodifiableList(list);
		encoding=stack||ahead.encoding==null?null:ResourceTranslation.encodingFor(ahead.to,ahead.encoding);
	}

	/**
	 * Collects the texts of a program or a stack still to translate (with {@code again}, all of them), into the language
	 * of its translation settings. Reads its resources or the stack and finds its language: not on the event thread.
	 */
	static LlmTranslation start(File file,boolean again) throws IOException{
		boolean stack=StackTranslation.isStackFile(file)||StackTranslation.openStack(file)!=null;
		VMTranslation.Ahead a=stack?StackTranslation.ahead(file,true,again):VMTranslation.Ahead.start(file,true,again);
		return new LlmTranslation(file,stack,a);
	}

	synchronized int doneCount(){return done.size();}
	synchronized boolean isDone(Item it){return done.contains(it.id);}
	synchronized boolean allDone(){return done.size()>=items.size();}

	/**
	 * The texts in parts of at most about {@code budget} characters of JSON (and a number of texts a chat keeps count
	 * of), in the order of the program's resources; a part keeps its texts when some are done.
	 */
	List<List<Item>> parts(int budget){
		int most=Math.max(20,budget/40);
		List<List<Item>> out=new ArrayList<List<Item>>();
		List<Item> part=new ArrayList<Item>();int size=0;
		for(Item it:items){
			int n=line(it,null).length()+2;
			if(!part.isEmpty()&&(size+n>budget||part.size()>=most)){out.add(part);part=new ArrayList<Item>();size=0;}
			part.add(it);size+=n;
		}
		if(!part.isEmpty())out.add(part);
		return out;
	}

	/** The texts of a part still to translate. */
	synchronized List<Item> left(List<Item> part){
		List<Item> out=new ArrayList<Item>();
		for(Item it:part)if(!done.contains(it.id))out.add(it);
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The request

	/** What is copied into the chat for a part ({@code index} from 1 of {@code count}): the instructions and the texts left. */
	String request(List<Item> part,int index,int count){
		List<Item> left=left(part);
		String from=englishName(ahead.from),to=englishName(ahead.to);
		String name=LauncherStore.displayName(file);
		StringBuilder b=new StringBuilder();
		b.append(stack
			? "Translate the texts of the HyperCard stack \""+name+"\" from "+from+" into "+to+".\n"
			: "Translate the user interface texts of the classic Mac OS program \""+name+"\" from "+from+" into "+to+".\n");
		if(count>1)b.append("This is part ").append(index).append(" of ").append(count).append(".\n");
		b.append("\nRules:\n");
		b.append("- Reply with only one JSON object in a code block, mapping each id to its translation: {\"1\": \"...\", \"2\": \"...\"}. Translate every text; leave none out.\n");
		b.append(stack
			? "- \"kind\": \"ui\" = a button name or a dialog text, \"menu\" = a pop-up menu choice, \"text\" = a line of a text field.\n"
			: "- \"kind\": \"menu\" = a menu title or menu item, \"ui\" = a dialog, alert, button or window text, \"text\" = a message or other text the program shows.\n");
		b.append("- Keep each translation about as short as the original: menu items, buttons and dialog texts must fit where the original was. Use the usual short wording of software menus and buttons.\n");
		b.append("- Keep exactly as they are: ^0 ^1 ^2 ^3, printf codes such as %d %s %ld, semicolons (;), line breaks (\\n), a trailing \"…\" or \"...\", numbers, and names of files, products, companies and people.\n");
		String terms=terms();
		if(!stack)b.append("- Use the standard terms of the Mac OS in ").append(to).append(terms.isEmpty()?"":" ("+terms+")").append(".\n");
		if(encoding==StackTextEncoding.JAPANESE)b.append("- Use only characters of Shift_JIS (JIS X 0208): no emoji, no characters outside it. At most 255 bytes per text (about 120 Japanese characters).\n");
		else if(encoding!=null)b.append("- Use only characters of the Mac OS Roman character set: no emoji. At most 255 characters per text.\n");
		b.append("- A text that is a code, a file name or not in ").append(from).append(" comes back unchanged.\n");
		b.append("- \"note\", where there is one, says what was wrong with an earlier answer for that text.\n");
		b.append("\n```json\n").append(json(left)).append("```\n");
		return b.toString();
	}

	/** The texts as the request's JSON, one per line. */
	synchronized String json(List<Item> left){
		StringBuilder b=new StringBuilder("{\"from\": ");
		quote(b,ahead.from);b.append(", \"to\": ");quote(b,ahead.to);b.append(", \"texts\": [\n");
		for(int i=0;i<left.size();i++){
			Item it=left.get(i);
			b.append(line(it,problems.get(it.id))).append(i<left.size()-1?",\n":"\n");
		}
		return b.append("]}\n").toString();
	}

	private static String line(Item it,String note){
		StringBuilder b=new StringBuilder("{\"id\": \"").append(it.id).append("\", \"kind\": \"").append(it.kind.wire()).append("\", \"text\": ");
		quote(b,it.text);
		if(note!=null){b.append(", \"note\": ");quote(b,note);}
		return b.append('}').toString();
	}

	/** A few standard Mac OS terms in the target language, from the glossary ("File = ファイル, Quit = 終了"). */
	private String terms(){
		if(ahead.to.startsWith("en"))return "";
		StringBuilder b=new StringBuilder();
		for(String w:new String[]{"File","Edit","Open…","Save As…","Quit","Cancel","Undo","Preferences…"}){
			String t=VMTranslation.Glossary.lookup("en",ahead.to,w,VMTranslation.Kind.MENU);
			if(t!=null)b.append(b.length()>0?", ":"").append(w).append(" = ").append(t);
		}
		return b.toString();
	}

	/** A language in English, for the chat ("Japanese", "Simplified Chinese"). */
	static String englishName(String code){
		if(code==null)return "the original language";
		if(code.startsWith("zh-"))return code.endsWith("Hant")?"Traditional Chinese":"Simplified Chinese";
		String n=Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
		return n==null||n.isEmpty()?code:n;
	}

	private static void quote(StringBuilder b,String s){
		b.append('"');
		for(int i=0;i<s.length();i++){
			char c=s.charAt(i);
			switch(c){
				case '"':b.append("\\\"");break;
				case '\\':b.append("\\\\");break;
				case '\n':b.append("\\n");break;
				case '\r':b.append("\\n");break;
				case '\t':b.append("\\t");break;
				default:if(c<0x20)b.append(String.format("\\u%04x",(int)c));else b.append(c);
			}
		}
		b.append('"');
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The answer

	/** What pasting an answer did. */
	static final class Result {
		/** Translations kept now, ids the request did not have, and texts whose answers were not kept (with why). */
		int kept,unknown;
		final List<String> problems=new ArrayList<String>();
		/** No JSON with translations was found in what was pasted. */
		boolean noJson;
	}

	/** Keeps the translations of an answer (one or more parts' JSON, with or without the chat's words around it). */
	Result apply(String answer){
		Result r=new Result();
		Map<String,String> got=parseAnswer(answer);
		if(got.isEmpty()){r.noJson=true;return r;}
		Map<VMTranslation.Kind,List<String>> texts=new EnumMap<VMTranslation.Kind,List<String>>(VMTranslation.Kind.class),
			translations=new EnumMap<VMTranslation.Kind,List<String>>(VMTranslation.Kind.class);
		synchronized(this){
			for(Map.Entry<String,String> e:got.entrySet()){
				Item it;
				try{it=byId.get(Integer.valueOf(e.getKey().trim().replaceFirst("^#","")));}catch(NumberFormatException ex){it=null;}
				if(it==null){r.unknown++;continue;}
				String t=e.getValue()==null?"":e.getValue().replace("\r\n","\n").strip();
				if(t.isEmpty())continue; // asked again with the rest
				String why=check(it,t);
				if(why!=null){if(!done.contains(it.id)){problems.put(it.id,why);r.problems.add("#"+it.id+": "+why);}continue;} // a text kept before keeps that
				problems.remove(it.id);
				if(done.add(it.id))r.kept++;
				texts.computeIfAbsent(it.kind,k->new ArrayList<String>()).add(it.text);
				translations.computeIfAbsent(it.kind,k->new ArrayList<String>()).add(t);
			}
		}
		for(VMTranslation.Kind k:texts.keySet())ahead.translator.accept(k,texts.get(k),translations.get(k));
		VMTranslation.saveNow();
		return r;
	}

	/** Why a translation cannot go into the program's resources (in English, told to the chat), or null. */
	private String check(Item it,String t){
		if(stack)return null;
		if(!ResourceTranslation.keepsPlaceholders(it.text,t))return "keep ^0-^3, printf codes such as %d and semicolons exactly as in the original";
		if(encoding==null||it.joined)return null;
		byte[] bytes=ResourceTranslation.encode(VMTranslation.cleanup(it.text,t),encoding);
		if(bytes==null)return encoding==StackTextEncoding.JAPANESE?"use only characters of Shift_JIS":"use only characters of Mac OS Roman";
		if(bytes.length>255)return "too long: at most 255 bytes";
		return null;
	}

	/**
	 * The id -> translation pairs of the JSON in a pasted answer: every JSON object or array in it (code blocks, the chat's
	 * words around them, several parts pasted together), as {"1": "..."}, {"translations": {...}}, or a list of
	 * {"id": .., "translation"|"text": ..}. Trailing commas are forgiven.
	 */
	static Map<String,String> parseAnswer(String answer){
		Map<String,String> out=new LinkedHashMap<String,String>();
		if(answer==null)return out;
		for(String json:jsonParts(answer)){
			Object root;
			try{root=TalkJson.parse(json);}
			catch(xTalkException|RuntimeException e){
				try{root=TalkJson.parse(json.replaceAll(",(\\s*[}\\]])","$1"));}catch(xTalkException|RuntimeException e2){continue;}
			}
			collect(root,out);
		}
		return out;
	}

	private static void collect(Object node,Map<String,String> out){
		if(node instanceof Map<?,?>){
			Map<?,?> m=(Map<?,?>)node;
			Object nested=m.containsKey("translations")?m.get("translations"):m.containsKey("texts")&&!m.containsKey("id")?m.get("texts"):null;
			if(nested!=null){collect(nested,out);return;}
			Object id=m.get("id");
			if(id!=null&&!(id instanceof Map<?,?>)&&!(id instanceof List<?>)){
				Object t=m.containsKey("translation")?m.get("translation"):m.containsKey("translated")?m.get("translated"):m.get("text");
				if(t instanceof String)out.put(String.valueOf(id),(String)t);
				return;
			}
			for(Map.Entry<?,?> e:m.entrySet()){
				Object v=e.getValue();
				if(v instanceof String)out.put(String.valueOf(e.getKey()),(String)v);
				else if(v instanceof Map<?,?>){
					Map<?,?> vm=(Map<?,?>)v;Object t=vm.containsKey("translation")?vm.get("translation"):vm.get("text");
					if(t instanceof String)out.put(String.valueOf(e.getKey()),(String)t);
				}
			}
		}else if(node instanceof List<?>)for(Object o:(List<?>)node)collect(o,out);
	}

	/** The outermost {...} and [...] in a text (strings inside them followed, so braces in translations do not count). */
	static List<String> jsonParts(String s){
		List<String> out=new ArrayList<String>();
		int depth=0,start=-1;boolean inString=false;
		for(int i=0;i<s.length();i++){
			char c=s.charAt(i);
			if(depth>0&&inString){
				if(c=='\\')i++;
				else if(c=='"')inString=false;
				continue;
			}
			if(c=='{'||c=='['){if(depth++==0)start=i;}
			else if(depth>0&&(c=='}'||c==']')){if(--depth==0){out.add(s.substring(start,i+1));start=-1;}}
			else if(depth>0&&c=='"')inString=true;
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Finishing

	/** What finishing gave: a program's translated resources (null for a stack). */
	static final class Finished {
		ResourceTranslation.Built built;
	}

	/**
	 * Writes a program's translated resources from the cache and sets it to run with them, or turns a stack's translation
	 * on. Not on the event thread (a program's dialogs are laid out anew).
	 */
	Finished finish() throws IOException{
		Finished f=new Finished();
		VMTranslation.saveNow();
		if(stack){
			if(VMTranslation.unavailableReason()!=null&&LauncherStore.enabled){ // no detector here to find the same language again when the stack opens
				LauncherStore.AppSettings s=LauncherStore.get().settingsFor(file);
				if(s==null)s=new LauncherStore.AppSettings();
				if(s.translateFrom==null||VMTranslation.AUTO.equals(s.translateFrom)){s.translateFrom=ahead.from;LauncherStore.get().putSettings(file,s);}
			}
			StackTranslation.setOn(file,true);
			return f;
		}
		f.built=ahead.makeResources();
		if(f.built.file!=null)ResourceTranslation.use(file);
		MacApp68k running=MacApp68k.runningFrom(file);
		if(running!=null)running.translationChanged();
		return f;
	}
}

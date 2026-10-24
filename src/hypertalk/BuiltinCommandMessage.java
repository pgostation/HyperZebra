import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.swing.JMenu;

/**
 * A built-in command caught by a same-named script handler (on answer, on go, on set, on show…). HyperCard parses the
 * command first and sends the message with the parts it parsed as params, and "the params" spells the command out in its
 * own way. Measured in real HyperCard J-2.3 with the probe stack (Oct 14 2026):
 * answer "Q" with "A" → normal,Q,with,A (the params: answer normal "Q" with "A"); find "x" in cd fld "Log" →
 * normal international,x,in card field "Log"; doMenu "Copy" → Copy,Edit; go to card 2 → the card's long id;
 * show cd btn 1 at 10,20 → Run All,at,10,20; set the loc of cd btn 1 to 10,20 → loc,of,Run All,to,10,20;
 * choose button tool → tool,2; get, play, wait, add, visual, sort, select… → no params (the params: the command name).
 * A handler that passes the message still gets the built-in command run with the words as written (TTalk.CommandExec).
 */
final class BuiltinCommandMessage {
	private BuiltinCommandMessage() {}

	/** the params of each command message, by the identity of its params array (passed unchanged up the message path) */
	private static final Map<String[],String> TEXTS = Collections.synchronizedMap(new WeakHashMap<String[],String>());

	/** "the params" of a built-in command message, null for any other message */
	static String paramsText(String[] params) {
		return params==null ? null : TEXTS.get(params);
	}

	/** HyperCard's spelling of the command name in the params */
	private static final String[] NAMES = {"answer", "ask", "find", "doMenu", "close", "open", "get", "play", "print", "type",
		"speak", "reply", "go", "visual", "show", "hide", "lock", "unlock", "push", "pop", "wait", "add", "subtract",
		"multiply", "divide", "sort", "select", "click", "drag", "choose", "create", "delete", "copy", "edit", "enable",
		"disable", "export", "import", "mark", "unmark", "read", "write", "reset", "request", "save", "start", "stop",
		"convert", "arrowKey", "set", "beep", "flash", "dial", "debug", "functionKey", "controlKey", "commandKeyDown",
		"tabKey", "returnKey", "enterKey", "help"}; //palette and picture are externals in HyperCard: comma-separated values
	private static final Map<String,String> NAME_OF = new java.util.HashMap<String,String>();
	static {
		for(String n : NAMES) NAME_OF.put(n.toLowerCase(Locale.ROOT), n);
	}
	/** commands HyperCard sends without params */
	private static final Set<String> NO_PARAMS = Set.of("get", "play", "print", "type", "speak", "reply", "visual", "unlock",
		"push", "pop", "wait", "add", "subtract", "multiply", "divide", "sort", "select", "click", "drag", "create", "delete",
		"copy", "edit", "enable", "disable", "export", "import", "mark", "unmark", "read", "write", "request", "save",
		"convert", "tabkey", "returnkey", "enterkey", "help");
	/** HyperCard's tool numbers (choose button tool → tool,2) */
	private static final String[] TOOLS = {"browse", "button", "field", "select", "lasso", "pencil", "brush", "eraser",
		"line", "spray", "rectangle", "round rect", "bucket", "oval", "curve", "text", "regular polygon", "polygon"};

	/**
	 * How HyperCard writes an object in these params: the form of the last name or id asked for (the short name of x /
	 * the short id → Run All, the long name → card button "Run All" of card "Probe" of stack "…"); reading any other
	 * property, or sending one of these messages, makes it the plain name again (card button "Run All"). Measured in
	 * HyperCard J-2.3: show cd btn 1 after get the short name of cd btn 2 → Run All, after get the hilite of cd btn 2 →
	 * card button "Run All"; get cd fld 1 leaves it as it was.
	 */
	private static final int PLAIN = 0, SHORT = 1, LONG = 2;
	private static volatile int nameForm = PLAIN;

	/** a property was read (TUtil.getProperty) */
	static void noteProperty(String property){
		if(property.startsWith("short ")) nameForm = SHORT;
		else if(property.startsWith("long ")) nameForm = LONG;
		else nameForm = PLAIN;
	}

	private static String describe(OObject obj){
		switch(nameForm){
		case SHORT: return obj.name==null || obj.name.isEmpty() ? obj.getShortName() : obj.name;
		case LONG: return obj.getLongName();
		default: return obj.getShortName();
		}
	}

	static boolean handles(String command) {
		return command!=null && NAME_OF.containsKey(command.toLowerCase(Locale.ROOT));
	}

	/** one param and whether "the params" quotes it */
	private static final class Part {
		final String value;
		final boolean quoted;
		Part(String value, boolean quoted){ this.value = value; this.quoted = quoted; }
	}

	private final ArrayList<String> words = new ArrayList<String>();
	private TTalk.wordType[] types;
	private MemoryData memData;
	private OObject object, target;
	private final ArrayList<Part> parts = new ArrayList<Part>();

	/**
	 * The params HyperCard gives a script handler for this built-in command (words[0] is the command), registered with their
	 * "the params"; null when the command is none of these (its params stay the comma-separated values).
	 */
	static String[] params(ArrayList<String> words, TTalk.wordType[] types, MemoryData memData, OObject object, OObject target) {
		if(words.isEmpty() || !handles(words.get(0))) return null;
		BuiltinCommandMessage m = new BuiltinCommandMessage();
		m.words.addAll(words);
		m.types = types.clone();
		m.memData = memData;
		m.object = object;
		m.target = target;
		String cmd = words.get(0).toLowerCase(Locale.ROOT);
		//not the built-in command's syntax (convert t,"d->s" / copy a,b / answer 1,2 / help "x"): HyperCard sends a plain
		//message with the comma-separated values
		if(m.plainMessage(cmd)) return null;
		try{
			if(!m.parse(cmd)) return null;
		}catch(xTalkException e){
			return null; //HyperCard would not compile it: the values as before
		}
		String[] params = new String[m.parts.size()];
		//the command as the statement spells it (doMenu / domenu)
		String name = m.written(0, 0);
		StringBuilder text = new StringBuilder(name.equalsIgnoreCase(cmd) ? name : NAME_OF.get(cmd));
		for(int i=0; i<params.length; i++){
			Part p = m.parts.get(i);
			params[i] = p.value;
			//quoted values next to each other are a list ("Copy","Edit" / "A","B","C"), everything else is words
			text.append(i>0 && p.quoted && m.parts.get(i-1).quoted ? "," : " ");
			text.append(p.quoted ? "\""+p.value+"\"" : p.value);
		}
		TEXTS.put(params, text.toString());
		nameForm = PLAIN;
		return params;
	}

	private int end(){ return words.size()-1; }

	/** commands whose syntax has no comma of its own, and the word each one needs */
	private static final Set<String> NO_COMMA = Set.of("answer", "ask", "find", "go", "lock", "unlock", "choose", "reset",
		"convert", "start", "stop", "arrowkey", "beep", "dial", "add", "subtract", "multiply", "divide", "copy", "help", "push",
		"pop", "wait", "edit", "save", "create", "delete", "mark", "unmark", "export", "import", "read", "write", "request");
	private static final Map<String,String> NEEDS = Map.of("add", "to", "subtract", "from", "multiply", "by", "divide", "by",
		"convert", "to", "copy", "to");

	private boolean plainMessage(String cmd){
		int n = end();
		if(cmd.equals("help")) return n>=1;
		if(NO_COMMA.contains(cmd) && findComma(1, n)>0) return true;
		//unlock withjDissolve (JokerSystem's own visual effects): not lock/unlock screen|messages|recent|error dialogs, so the
		//words go as they are. JokerSystem's on unlock tells its visual from "the params" being more than "unlock"
		if((cmd.equals("lock") || cmd.equals("unlock")) && n>=1 && !(isWord(1, "screen") || isWord(1, "messages") ||
				isWord(1, "recent") || isWord(1, "error"))) return true;
		String need = NEEDS.get(cmd);
		return need!=null && find(1, n, need)<0;
	}

	private boolean isWord(int i, String w){
		return i>=1 && i<words.size() && types[i]!=TTalk.wordType.STRING && words.get(i).equalsIgnoreCase(w);
	}

	/** the first word w at parenthesis depth 0 in from..to, else -1 */
	private int find(int from, int to, String w){
		int depth = 0;
		for(int i=from; i<=to && i<words.size(); i++){
			TTalk.wordType t = types[i];
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) depth++;
			else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) depth--;
			else if(depth==0 && isWord(i, w)) return i;
		}
		return -1;
	}

	private int findLast(int from, int to, String w){
		int depth = 0, found = -1;
		for(int i=from; i<=to && i<words.size(); i++){
			TTalk.wordType t = types[i];
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) depth++;
			else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) depth--;
			else if(depth==0 && isWord(i, w)) found = i;
		}
		return found;
	}

	private int findComma(int from, int to){
		int depth = 0;
		for(int i=from; i<=to && i<words.size(); i++){
			TTalk.wordType t = types[i];
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) depth++;
			else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) depth--;
			else if(depth==0 && (t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC)) return i;
		}
		return -1;
	}

	private String source(int from, int to){
		return from>to ? "" : TTalk.serializeWords(words, types, from, to);
	}

	/** the value of from..to; a list (10,20 / x,x+1) is each item's value, joined by commas */
	private String value(int from, int to) throws xTalkException {
		if(from>to) return "";
		int c = findComma(from, to);
		if(c<0) return TTalk.Evalution(source(from, to), memData, object, target);
		StringBuilder sb = new StringBuilder();
		while(true){
			sb.append(c<0 ? value(from, to) : value(from, c-1));
			if(c<0) break;
			sb.append(',');
			from = c+1;
			c = findComma(from, to);
		}
		return sb.toString();
	}

	/** the words as the statement has them (the tokenizer lowers them): set itemDel → itemDel */
	private String written(int from, int to){
		String plain = source(from, to);
		String line = TTalk.currentStatementSource();
		if(line==null || plain.isEmpty()) return plain;
		int at = line.toLowerCase(Locale.ROOT).indexOf(plain.toLowerCase(Locale.ROOT));
		return at>=0 ? line.substring(at, at+plain.length()) : plain;
	}

	private void kw(String v){ parts.add(new Part(v, false)); }
	private void q(String v){ parts.add(new Part(v, true)); }

	private boolean parse(String cmd) throws xTalkException {
		int n = end();
		if(NO_PARAMS.contains(cmd)) return true;
		switch(cmd){
		case "answer": answer(); return true;
		case "ask": ask(); return true;
		case "find": find(); return true;
		case "domenu": doMenu(); return true;
		case "open": openClose(true); return true;
		case "close": openClose(false); return true;
		case "go": go(); return true;
		case "show": case "hide": showHide(cmd.equals("show")); return true;
		case "lock":
			if(n>=1) kw(canonical(source(1, n)));
			return true;
		case "choose": choose(); return true;
		case "reset":
			if(n>=1) kw(canonical(source(1, n)));
			return true;
		case "start": case "stop":
			if(!isWord(1, "using")) return false;
			kw("using");
			int s = isWord(2, "stack") ? 3 : 2;
			kw("stack");
			q(value(s, n));
			return true;
		case "arrowkey":
			if(n>=1) kw(value(1, n));
			return true;
		case "set": set(); return true;
		case "beep":
			if(n>=1) kw(value(1, n));
			return true;
		case "dial": {
			int w = find(1, n, "with");
			kw(value(1, w<0 ? n : w-1));
			if(w>0) kw("with "+written(w+1, n));
			return true;
		}
		case "debug": {
			String line = TTalk.currentStatementSource();
			String rest = line!=null && line.trim().length()>5 ? line.trim().substring(5).trim() : source(1, n);
			if(n>=1) q(rest);
			return true;
		}
		default: {
			//flash 2 / functionKey 3 / commandKeyDown "x": the comma-separated values, quoted in the params
			int from = 1;
			while(from<=n){
				int c = findComma(from, n);
				int to = c<0 ? n : c-1;
				q(value(from, to));
				if(c<0) break;
				from = c+1;
			}
			return true;
		}
		}
	}

	/** answer "Q" [with b1 [or b2 [or b3]]] / answer file|program "P" [of type t1 [or t2…]] / answer folder "F" */
	private void answer() throws xTalkException {
		int n = end();
		int i = 1;
		String style = "normal";
		if(isWord(1, "file") || isWord(1, "folder") || isWord(1, "program")){ style = words.get(1).toLowerCase(Locale.ROOT); i = 2; }
		kw(style);
		if(style.equals("normal")){
			int w = find(i, n, "with");
			q(value(i, w<0 ? n : w-1));
			kw("with");
			if(w<0) q("OK");
			else orList(w+1, n);
		}else{
			int o = find(i, n, "of");
			if(o>0 && !isWord(o+1, "type")) o = -1;
			q(value(i, o<0 ? n : o-1));
			if(o>0){
				kw("of type");
				orList(o+2, n);
			}
		}
	}

	private void orList(int from, int to) throws xTalkException {
		while(from<=to){
			int o = find(from, to, "or");
			q(value(from, o<0 ? to : o-1));
			if(o<0) break;
			from = o+1;
		}
	}

	/** ask [password [clear]|file] "Q" [with "D"] */
	private void ask() throws xTalkException {
		int n = end();
		int i = 1;
		String style = "normal";
		if(isWord(1, "password")){
			style = "password"; i = 2;
			if(isWord(2, "clear")){ style = "password clear"; i = 3; }
		}
		else if(isWord(1, "file")){ style = "file"; i = 2; }
		kw(style);
		int w = find(i, n, "with");
		q(value(i, w<0 ? n : w-1));
		kw("with");
		q(w<0 ? "" : value(w+1, n));
	}

	/** find [whole|chars|word|string|normal] [international] text [in field] */
	private void find() throws xTalkException {
		int n = end();
		int i = 1;
		String mode = "normal";
		for(String m : new String[]{"whole", "chars", "characters", "word", "words", "string", "normal"}){
			if(isWord(i, m)){
				mode = m.equals("characters") ? "chars" : m.equals("words") ? "word" : m;
				i++;
				break;
			}
		}
		if(isWord(i, "international")) i++;
		kw(mode+" international");
		int in = find(i, n, "in");
		q(value(i, in<0 ? n : in-1));
		if(in>0) kw("in "+objectWords(in+1, n));
	}

	/** in bg fld 1 → field 1, in cd fld "Log" → card field "Log" (HyperCard spells the reference out, unresolved) */
	private String objectWords(int from, int to){
		StringBuilder sb = new StringBuilder();
		for(int k=from; k<=to; k++){
			String w = words.get(k);
			String out;
			if(types[k]==TTalk.wordType.STRING) out = w.matches("-?[0-9]+(\\.[0-9]+)?") && !TTalk.isQuotedNumberLiteral(w) ? w : "\""+w+"\"";
			else{
				String lw = w.toLowerCase(Locale.ROOT);
				if(lw.equals("cd")) out = "card";
				else if(lw.equals("bg") || lw.equals("bkgnd") || lw.equals("background")) out = null; //a field is a bkgnd field anyway
				else if(lw.equals("fld")) out = "field";
				else if(lw.equals("btn")) out = "button";
				else out = w;
			}
			if(out==null) continue;
			if(sb.length()>0) sb.append(' ');
			sb.append(out);
		}
		return sb.toString();
	}

	/** doMenu item [, menu] [without dialog]: the menu is the one that has the item when not given */
	private void doMenu() throws xTalkException {
		int n = end();
		int without = find(1, n, "without");
		int last = without<0 ? n : without-1;
		int c = findComma(1, last);
		String item = value(1, c<0 ? last : c-1);
		q(item);
		q(c<0 ? menuOf(item) : value(c+1, last));
		if(without>0){
			for(int k=without; k<=n; k++) kw(words.get(k).toLowerCase(Locale.ROOT));
		}
	}

	private static String menuOf(String item){
		if(PCARD.pc==null) return "";
		ArrayList<JMenu> menus = new ArrayList<JMenu>(TTalk.menuList());
		if(TTalk.appleMenu!=null) menus.add(TTalk.appleMenu);
		for(JMenu m : menus){
			if(m==null || TTalk.findMenuItemIndex(m, item)<0) continue;
			String name = m.getText();
			return PCARD.pc.intl!=null ? PCARD.pc.intl.getEngText(name) : name;
		}
		//HyperZebra's own item for it is named differently
		if(item.trim().equalsIgnoreCase("Quit HyperCard")) return "File";
		return "";
	}

	/** open|close "x" [with "y"] / open|close file "x" / open|close printing [with dialog] / open report printing with
	 *  template "x" / open|close application "x" / close window "x" / close card window */
	private void openClose(boolean open) throws xTalkException {
		int n = end();
		if(isWord(1, "printing")){
			kw("printing");
			for(int k=2; k<=n; k++) kw(words.get(k).toLowerCase(Locale.ROOT));
			return;
		}
		if(open && isWord(1, "report") && isWord(2, "printing")){
			kw("report"); kw("printing");
			int t = find(3, n, "template");
			if(t>0){ kw("with"); kw("template"); q(value(t+1, n)); }
			return;
		}
		if(isWord(1, "file")){
			kw("file");
			String v = value(2, n);
			if(open) q(v); else kw(v);
			return;
		}
		if(isWord(1, "application")){
			q("application \""+value(2, n)+"\"");
			return;
		}
		if(!open && isWord(1, "card") && isWord(2, "window") && n==2){ kw("card window"); return; }
		if(!open && isWord(1, "window")){ kw(windowName(value(2, n))); return; }
		int w = find(1, n, "with");
		q(value(1, w<0 ? n : w-1));
		if(w>0){ kw("with"); q(value(w+1, n)); }
	}

	private static String windowName(String name){
		String w = name.trim().toLowerCase(Locale.ROOT);
		if(w.equals("message") || w.equals("msg") || w.equals("message box") || w.equals("message window") || w.equals("msg box")) return "message window";
		if(w.equals("tool") || w.equals("tools")) return "tool window";
		if(w.equals("pattern") || w.equals("patterns")) return "pattern window";
		return name;
	}

	/** go [to] <destination> [in a new window] [without dialog]: the destination card's long id, a relative card, or
	 *  "unknown destination" */
	private void go() throws xTalkException {
		int n = end();
		ArrayList<String> extra = new ArrayList<String>();
		if(n>=2 && isWord(n, "dialog") && isWord(n-1, "without")){ extra.add(0, "without dialog"); n -= 2; }
		if(n>=4 && isWord(n, "window") && isWord(n-1, "new") && isWord(n-2, "a") && isWord(n-3, "in")){ extra.add(0, "in a new window"); n -= 4; }
		else if(n>=3 && isWord(n, "window") && isWord(n-1, "new") && isWord(n-2, "in")){ extra.add(0, "in a new window"); n -= 3; }
		int i = isWord(1, "to") ? 2 : 1;
		kw(destination(i, n));
		for(String e : extra) kw(e);
	}

	private String destination(int from, int to) throws xTalkException {
		if(from>to) return "unknown destination";
		int count = to-from+1;
		boolean cardAfter = count==2 && (isWord(to, "card") || isWord(to, "cd"));
		if(count==1 || cardAfter){
			if(isWord(from, "next")) return "next card";
			if(isWord(from, "prev") || isWord(from, "previous")) return "previous card";
			if(isWord(from, "last")) return "last card";
			if(isWord(from, "recent") || isWord(from, "back")) return "back";
			if(isWord(from, "forth")) return "forth";
		}
		if(count==1 && isWord(from, "home")){
			OStack home = TTalk.homeStackObject();
			if(home==null || home.cardIdList.isEmpty()) return "unknown destination";
			return "card id "+home.cardIdList.get(0)+" of "+home.getLongName();
		}
		if(isWord(from, "stack") || find(from, to, "stack")>0) return "unknown destination";
		if(find(from, to, "marked")>0) return "unknown destination";
		String ref = source(from, to);
		if(count==1 && types[from]!=TTalk.wordType.STRING && !isWord(from, "first") && !isWord(from, "this")){
			ref = "card "+ref; //go 3 / go n: a card
		}
		try{
			String id = TTalk.Evalution("the long id of "+ref, memData, object, target);
			return id.startsWith("card id") ? id : "unknown destination";
		}catch(xTalkException e){
			return "unknown destination";
		}
	}

	/** show|hide <object> [at loc] / menuBar / titleBar / msg / tool window / picture / bkgnd picture / [all|marked|N] cards */
	private void showHide(boolean show) throws xTalkException {
		int n = end();
		if(n<1) return;
		if(n==1 && isWord(1, "menubar")){ kw("menuBar"); return; }
		if(n==1 && isWord(1, "titlebar")){ kw("titleBar"); return; }
		if(isWord(1, "msg") || isWord(1, "message")){ kw("message window"); return; }
		if(n==2 && isWord(2, "window") && (isWord(1, "tool") || isWord(1, "pattern") || isWord(1, "card"))){
			kw(words.get(1).toLowerCase(Locale.ROOT)+" window");
			return;
		}
		if(isWord(1, "window")){ kw(windowName(value(2, n))); return; }
		if(isWord(1, "picture")){
			if(n==1){ if(show) q("picture"); else kw("picture"); return; }
			if(isWord(2, "of")){ kw("picture"); kw("of"); kw(shortName(3, n)); return; }
		}
		if(n==2 && isWord(2, "picture")){
			if(isWord(1, "bg") || isWord(1, "bkgnd") || isWord(1, "background")){ kw("bkgnd"); kw("picture"); return; }
			if(isWord(1, "cd") || isWord(1, "card")){ kw("card"); kw("picture"); return; }
		}
		if(isWord(n, "cards") || isWord(n, "cds")){
			if(n==1){ kw("cards"); return; }
			if(n==2 && (isWord(1, "all") || isWord(1, "marked"))){ kw(words.get(1).toLowerCase(Locale.ROOT)); kw("cards"); return; }
			kw(value(1, n-1)); kw("cards");
			return;
		}
		int at = find(1, n, "at");
		String loc = at>0 ? value(at+1, n) : null; //the values first, then the object (its form may change on the way)
		kw(shortName(1, at<0 ? n : at-1));
		if(at>0){ kw("at"); kw(loc); }
	}

	private String shortName(int from, int to) throws xTalkException {
		OObject obj = from==to && isWord(from, "me") && object!=null ? object : TTalk.getObject(source(from, to), memData, target);
		if(obj==null) throw new xTalkException("No such object");
		return describe(obj);
	}

	private void choose() throws xTalkException {
		int n = end();
		if(isWord(1, "tool")){ kw("tool"); kw(value(2, n)); return; }
		int t = findLast(1, n, "tool");
		String name = (t<0 ? source(1, n) : source(1, t-1)).toLowerCase(Locale.ROOT);
		int k = toolNumber(name);
		if(k<0 && t>1){
			//choose item n of toolList tool: the words before tool are an expression
			try{
				String v = value(1, t-1).trim().toLowerCase(Locale.ROOT);
				if(v.matches("[0-9]+")){ kw("tool"); kw(v); return; }
				if((k = toolNumber(v))>=0) name = v;
			}catch(xTalkException e){ /* the words as written */ }
		}
		kw("tool"); kw(k>=0 ? Integer.toString(k+1) : name);
	}

	private static int toolNumber(String name){
		if(name.equals("rect")) name = "rectangle";
		if(name.equals("round rectangle")) name = "round rect";
		if(name.equals("reg poly") || name.equals("regular poly")) name = "regular polygon";
		if(name.equals("poly")) name = "polygon";
		for(int k=0; k<TOOLS.length; k++) if(TOOLS[k].equals(name)) return k;
		return -1;
	}

	private static String canonical(String words){
		String w = words.trim().toLowerCase(Locale.ROOT);
		if(w.equals("menubar")) return "menuBar";
		if(w.equals("titlebar")) return "titleBar";
		return w;
	}

	/** set [the] prop [of object] to value: prop,of,object,to,value (the object by its short name, me by its name, a chunk of
	 *  a field as char a to b of card field n) */
	private void set() throws xTalkException {
		int n = end();
		int to = findLast(1, n, "to");
		if(to<0) throw new xTalkException("set without to");
		int i = isWord(1, "the") ? 2 : 1;
		int of = find(i, to-1, "of");
		String v = value(to+1, n); //HyperCard evaluates the value first: set the hilite of me to not the hilite of me
		kw(written(i, (of<0 ? to : of)-1));
		if(of>0){
			kw("of");
			kw(setObject(of+1, to-1));
		}
		kw("to");
		kw(v);
	}

	private String setObject(int from, int to) throws xTalkException {
		if(isWord(from, "window")) return windowName(value(from+1, to));
		if(isWord(from, "msg") || isWord(from, "message")) return "message window";
		if(isWord(from, "char") || isWord(from, "character") || isWord(from, "word") || isWord(from, "line") || isWord(from, "item")){
			String chunk = fieldChunk(from, to);
			if(chunk!=null) return chunk;
		}
		return shortName(from, to);
	}

	/** word 1 of cd fld 1 → char 1 to 9 of card field 1; null when it is not one chunk of a field */
	private String fieldChunk(int from, int to) throws xTalkException {
		int of = find(from, to, "of");
		if(of<0) return null;
		OObject obj;
		try{ obj = TTalk.getObject(source(of+1, to), memData, target); }
		catch(xTalkException e){ return null; }
		if(!(obj instanceof OField)) return null;
		OField field = (OField)obj;
		String text = field.getText();
		if(text==null) text = "";
		String mode = words.get(from).toLowerCase(Locale.ROOT);
		if(mode.equals("character")) mode = "char";
		int toWord = find(from+1, of-1, "to");
		int a, b;
		try{
			a = (int)Math.round(Double.parseDouble(value(from+1, toWord<0 ? of-1 : toWord-1).trim()));
			b = toWord<0 ? a : (int)Math.round(Double.parseDouble(value(toWord+1, of-1).trim()));
		}catch(NumberFormatException e){ return null; } //set the textStyle of word x of fld 1 with x empty (merryxmas Vaccine)
		int[] range = charRange(mode, text, a, b);
		if(range==null) return null;
		return "char "+range[0]+" to "+range[1]+" of "+TUtil.fieldNumberRef(field);
	}

	/** the 1-based first and last characters of chunks a..b */
	private static int[] charRange(String mode, String text, int a, int b){
		if(mode.equals("char")) return new int[]{a, b};
		ArrayList<int[]> spans = new ArrayList<int[]>();
		if(mode.equals("word")){
			int k = 0;
			while(k<text.length()){
				while(k<text.length() && Character.isWhitespace(text.charAt(k))) k++;
				if(k>=text.length()) break;
				int s = k;
				while(k<text.length() && !Character.isWhitespace(text.charAt(k))) k++;
				spans.add(new int[]{s, k});
			}
		}else{
			String delim = mode.equals("line") ? "\n" : TTalk.itemDelimiter;
			int s = 0;
			while(true){
				int e = text.indexOf(delim, s);
				if(e<0){ spans.add(new int[]{s, text.length()}); break; }
				spans.add(new int[]{s, e});
				s = e+delim.length();
			}
		}
		if(a<1 || b<a || b>spans.size()) return null;
		return new int[]{spans.get(a-1)[0]+1, spans.get(b-1)[1]};
	}
}

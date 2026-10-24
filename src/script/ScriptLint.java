import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * スクリプトエディタの文単位の静的検査。ScriptArea.analyzeScript(ブロック構造の検査)から呼ばれ、
 * ハンドラの中の文を1つずつ見て、実行すれば必ずエラーになる書き方(引数やキーワードの欠落、
 * 前後に式の無い演算子、exit/pass の名前違い、どの if にも属さない else …)をエラーとして、
 * スタックのどこにも定義の見つからないコマンド・関数(打ち間違い)と同名ハンドラの重複を警告として報告する。
 * HyperTalk ではメッセージの行き先が実行時まで決まらないので、正しいスクリプトを誤ってエラーにしないことを優先し、
 * 判断のつかない文(コレクションの [] {} を含む文など)は式の検査を省く。
 */
final class ScriptLint {
	private ScriptLint(){}

	/** message: the English text with {0}, {1}... for the args (translated when it is shown, International.t) */
	interface Sink {
		void report(int line, boolean warning, String message, Object... args);
	}

	/** 編集中のスクリプトの外(スタック・使用中のスタック・Home)で定義されている名前。すべて小文字。 */
	static final class Names {
		/** on ハンドラと XCMD */
		final Set<String> commands = new HashSet<String>();
		/** function ハンドラと XFCN */
		final Set<String> functions = new HashSet<String>();
	}

	/** スタックごとのハンドラ名の一覧。入力のたびに全カードを読み直さないよう少しの間使い回す */
	private static final class StackNames {
		final long time;
		/** オブジェクトごとの on / function の名前(編集中のオブジェクトの保存前の分を外せるように) */
		final HashMap<OObject,Set<String>[]> handlers;
		final Names externals;
		StackNames(long time, HashMap<OObject,Set<String>[]> handlers, Names externals){
			this.time = time;
			this.handlers = handlers;
			this.externals = externals;
		}
	}

	private static final long NAMES_REFRESH_MILLIS = 3000;
	private static final java.util.Map<OStack,StackNames> stackNames = new java.util.WeakHashMap<OStack,StackNames>();

	/**
	 * obj のスクリプトから呼べるスクリプト外の名前: 同じスタックのすべてのスクリプト、使用中のスタック(start using)、
	 * Home のハンドラと、それらの XCMD/XFCN。obj 自身の保存済みスクリプトは含めない(編集中の内容で判断する)。
	 * スタックに属さないスクリプト(メッセージボックスなど)や、読めなかったときは null(警告を出さない)。
	 */
	static Names namesFor(OObject obj){
		OStack stack = null;
		for(OObject o = obj; o!=null; o = o.ownerObject()){
			if(o instanceof OStack){ stack = (OStack)o; break; }
			if(o instanceof OCardBase){ stack = ((OCardBase)o).stack; break; }
		}
		if(stack==null) return null;
		ArrayList<OStack> stacks = new ArrayList<OStack>();
		stacks.add(stack);
		try{
			if(stack.usingStacks!=null) for(OStack s : new ArrayList<OStack>(stack.usingStacks)) if(s!=null && !stacks.contains(s)) stacks.add(s);
			if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.usingStacks!=null)
				for(OStack s : new ArrayList<OStack>(PCARD.pc.stack.usingStacks)) if(s!=null && !stacks.contains(s)) stacks.add(s);
			if(PCARD.home!=null && !stacks.contains(PCARD.home)) stacks.add(PCARD.home);
			Names names = new Names();
			for(OStack s : stacks){
				StackNames sn = stackNames(s);
				names.commands.addAll(sn.externals.commands);
				names.functions.addAll(sn.externals.functions);
				for(java.util.Map.Entry<OObject,Set<String>[]> e : sn.handlers.entrySet()){
					if(e.getKey()==obj) continue;
					names.commands.addAll(e.getValue()[0]);
					names.functions.addAll(e.getValue()[1]);
				}
			}
			return names;
		}catch(RuntimeException e){
			//スクリプトの実行中にカードが増減したなど。この回は警告を出さない
			return null;
		}
	}

	private static StackNames stackNames(OStack stack){
		long now = System.currentTimeMillis();
		synchronized(stackNames){
			StackNames cached = stackNames.get(stack);
			if(cached!=null && now-cached.time<NAMES_REFRESH_MILLIS) return cached;
		}
		HashMap<OObject,Set<String>[]> handlers = new HashMap<OObject,Set<String>[]>();
		ArrayList<OObject> objects = new ArrayList<OObject>();
		objects.add(stack);
		for(OBackground bg : stack.backgroundsInOrder()){ objects.add(bg); addParts(objects, bg); }
		for(OCard cd : new ArrayList<OCard>(stack.cdCacheList)){ if(cd.stack==stack){ objects.add(cd); addParts(objects, cd); } }
		for(OObject o : objects){
			ArrayList<String> script = o.scriptList;
			if(script==null || script.isEmpty()) continue;
			@SuppressWarnings("unchecked")
			Set<String>[] sets = new Set[]{ new HashSet<String>(), new HashSet<String>() };
			for(String line : new ArrayList<String>(script)) addHandlerName(line, sets);
			if(!sets[0].isEmpty() || !sets[1].isEmpty()) handlers.put(o, sets);
		}
		Names externals = new Names();
		if(stack.rsrc!=null){
			for(Rsrc.xcmdClass x : stack.rsrc.getXcmdResources()){
				if(x.name==null) continue;
				String name = x.name.toLowerCase(Locale.ROOT);
				if("function".equals(x.type)) externals.functions.add(name);
				else externals.commands.add(name);
			}
		}
		StackNames sn = new StackNames(now, handlers, externals);
		synchronized(stackNames){ stackNames.put(stack, sn); }
		return sn;
	}

	private static void addParts(ArrayList<OObject> list, OCardBase owner){
		if(owner.btnList!=null) list.addAll(owner.btnList);
		if(owner.fldList!=null) list.addAll(owner.fldList);
		if(owner.shapeList!=null) list.addAll(owner.shapeList);
		if(owner.panelList!=null) for(OPanel p : owner.panelList) if(!p.getScriptText().trim().isEmpty()) list.add(p);
	}

	private static void addHandlerName(String line, Set<String>[] sets){
		String s = line.strip();
		boolean on = s.regionMatches(true, 0, "on ", 0, 3) || s.regionMatches(true, 0, "on\t", 0, 3);
		boolean fn = !on && (s.regionMatches(true, 0, "function ", 0, 9) || s.regionMatches(true, 0, "function\t", 0, 9));
		if(!on && !fn) return;
		String rest = s.substring(on ? 3 : 9).strip();
		int end = 0;
		while(end<rest.length() && isWordChar(rest.charAt(end))) end++;
		if(end>0) sets[on ? 0 : 1].add(rest.substring(0, end).toLowerCase(Locale.ROOT));
	}

	//---------------------------------------------------------------- 字句

	static final char WORD='w', NUMBER='n', STRING='s', OPERATOR='o', OPEN='(', CLOSE=')', COMMA=',', OTHER='?';

	static final class Tok {
		final String text;
		final char kind;
		/** 空白を挟まずに ( が続く語(関数呼び出しの形) */
		boolean call;
		Tok(String text, char kind){ this.text = text; this.kind = kind; }
		boolean is(String word){ return kind==WORD && text.equals(word); }
		@Override public String toString(){ return text; }
	}

	private static boolean isWordChar(char c){
		if(c>=0x80) return !(c=='≠' || c=='≤' || c=='≥' || c=='¬' || c=='÷' || c=='　' || c=='“' || c=='”');
		return Character.isLetterOrDigit(c) || c=='_';
	}

	/** 1行(継続記号で結合済み)を字句に分ける。コメント以降は捨てる。閉じていない文字列は行末まで。 */
	static ArrayList<Tok> tokenize(String line){
		ArrayList<Tok> toks = new ArrayList<Tok>();
		int n = line.length();
		int i = 0;
		while(i<n){
			char c = line.charAt(i);
			if(c==' ' || c=='\t' || c=='　'){ i++; continue; }
			if(c=='-' && i+1<n && line.charAt(i+1)=='-') break;
			if(c=='"'){
				int end = line.indexOf('"', i+1);
				if(end<0) end = n;
				toks.add(new Tok(line.substring(Math.min(n, i+1), end), STRING));
				i = end+1;
				continue;
			}
			if(Character.isDigit(c) || (c=='.' && i+1<n && Character.isDigit(line.charAt(i+1)))){
				//実行系と同じく、数字の直後の文字は別の語(「1to」は 1 と to)
				int j = i;
				while(j<n && (Character.isDigit(line.charAt(j)) || line.charAt(j)=='.')) j++;
				toks.add(new Tok(line.substring(i, j), NUMBER));
				i = j;
				continue;
			}
			if(isWordChar(c)){
				int j = i;
				while(j<n && isWordChar(line.charAt(j))) j++;
				Tok t = new Tok(line.substring(i, j).toLowerCase(Locale.ROOT), WORD);
				t.call = j<n && line.charAt(j)=='(';
				toks.add(t);
				i = j;
				continue;
			}
			switch(c){
			case '(': toks.add(new Tok("(", OPEN)); i++; continue;
			case ')': toks.add(new Tok(")", CLOSE)); i++; continue;
			case ',': toks.add(new Tok(",", COMMA)); i++; continue;
			case '&':
				if(i+1<n && line.charAt(i+1)=='&'){ toks.add(new Tok("&&", OPERATOR)); i+=2; }
				else{ toks.add(new Tok("&", OPERATOR)); i++; }
				continue;
			case '<':
				if(i+1<n && (line.charAt(i+1)=='=' || line.charAt(i+1)=='>')){ toks.add(new Tok(line.substring(i, i+2), OPERATOR)); i+=2; }
				else{ toks.add(new Tok("<", OPERATOR)); i++; }
				continue;
			case '>':
				if(i+1<n && line.charAt(i+1)=='='){ toks.add(new Tok(">=", OPERATOR)); i+=2; }
				else{ toks.add(new Tok(">", OPERATOR)); i++; }
				continue;
			case '+': case '-': case '*': case '/': case '^': case '=': case '≠': case '≤': case '≥': case '÷':
				toks.add(new Tok(String.valueOf(c), OPERATOR)); i++; continue;
			default:
				toks.add(new Tok(String.valueOf(c), OTHER)); i++;
			}
		}
		//実行系は < = や < > を空白を挟んでも1つの比較演算子として扱う
		for(int k=toks.size()-2; k>=0; k--){
			Tok a = toks.get(k), b = toks.get(k+1);
			if(a.kind!=OPERATOR || b.kind!=OPERATOR) continue;
			if((a.text.equals("<") && (b.text.equals("=") || b.text.equals(">"))) || (a.text.equals(">") && b.text.equals("="))){
				toks.set(k, new Tok(a.text+b.text, OPERATOR));
				toks.remove(k+1);
			}
		}
		return toks;
	}

	//---------------------------------------------------------------- 名前の表

	/** 関数呼び出しの形でも関数ではない語(オブジェクト・チャンクの指定、キーワード)。TTalk.fullresolution と同じ。 */
	private static final Set<String> NOT_CALLS = new HashSet<String>(Arrays.asList(
		"cd", "card", "bg", "bkgnd", "background", "btn", "button", "fld", "field", "stack", "char", "character",
		"item", "word", "line", "window", "id", "of", "the", "and", "or", "not", "is", "in", "to", "into", "after",
		"before", "then", "else", "with", "from", "by", "at", "for", "if", "return", "put", "get", "set", "div", "mod",
		"contains", "within", "part", "shape", "menu", "menuitem", "chars", "characters", "items", "words", "lines",
		"number", "me", "this", "url", "file", "picture", "movie", "panel", "while", "until", "times", "repeat",
		"a", "an", "there", "no", "exit", "pass", "next", "send", "do", "go"));

	/** ここで式が区切られるキーワード: 直前・直後に演算子があれば式が欠けている。
	 *  from・to・by なども区切りだが、古いスタックでは変数名にも使われている(シムレストランの from)ので含めない */
	private static final Set<String> BOUNDARIES = new HashSet<String>(Arrays.asList("into", "then", "else", "of"));

	/** 左右に式が要る語の演算子(単項にならない) */
	private static final Set<String> WORD_BINARY = new HashSet<String>(Arrays.asList("and", "or", "mod", "div"));

	private static final Set<String> CONTROL_WORDS = new HashSet<String>(Arrays.asList(
		"on", "function", "end", "if", "then", "else", "repeat", "exit", "next", "pass", "return", "global"));

	/** 引数なしでは実行時に「…が分かりません」になるコマンド */
	private static final Set<String> NEEDS_ARGUMENT = new HashSet<String>(Arrays.asList(
		"add", "answer", "ask", "choose", "click", "close", "convert", "create", "delete", "disable", "divide", "do",
		"domenu", "drag", "edit", "enable", "find", "get", "go", "hide", "lock", "multiply", "open", "play", "pop",
		"put", "read", "select", "send", "set", "show", "sort", "subtract", "type", "unlock", "visual", "wait", "write",
		"global"));

	/** ハンドラの無いときに HyperCard(HyperZebra)自身が受け取るメッセージ・コマンド(TUtil.CallSystemMessage) */
	private static final Set<String> SYSTEM_COMMANDS = new HashSet<String>(Arrays.asList(
		"arrowkey", "closebackground", "closecard", "closefield", "closemovie", "closepalette", "closestack",
		"commandkeydown", "controlkey", "deletebackground", "deletebutton", "deletecard", "deletefield", "domenu",
		"enterinfield", "enterkey", "functionkey", "idle", "keydown", "mousedoubleclick", "mousedown",
		"mousedowninmovie", "mousedowninpicture", "mouseenter", "mouseleave", "mousestilldown", "mouseup",
		"mouseupinmovie", "mouseupinpicture", "mousewithin", "newbackground", "newbutton", "newcard", "newfield", "newpanel", "deletepanel",
		"newstack", "openbackground", "opencard", "openmovie", "openpalette", "openstack", "palette", "picture",
		"returninfield", "returnkey", "sorttable", "startup", "tabkey", "resumestack", "suspendstack", "resume",
		"suspend", "quit", "openfield", "exitfield", "addcolor", "movie", "help", "dial", "flash", "about", "beep",
		"push", "pop", "reset", "print", "request", "reply", "mark", "unmark", "import", "export", "feather", "grow",
		"shrink", "rewind", "pause", "start", "stop", "debug", "save", "copy", "pop", "exitfield"));

	/** TUtil.CallSystemFunction が受け付ける名前のうち TTalk.funcSet に無いもの(関数の形で呼べる) */
	private static final Set<String> EXTRA_FUNCTIONS = new HashSet<String>(Arrays.asList(
		"tan", "jsonvalue", "matchtext", "matchchunk", "replacetext", "checkhcfont", "stacksinuse", "version",
		"numberformat", "itemdelimiter", "textfont", "textsize", "textstyle", "userlevel", "language", "lockscreen",
		"lockmessages"));

	private static volatile Set<String> knownFunctions;
	private static volatile Set<String> knownCommands;

	/** 組み込みの関数名。TTalk の表とリファレンスから作る(TTalk.init の前なら表の分は無い) */
	static Set<String> builtinFunctions(){
		Set<String> set = knownFunctions;
		if(set!=null) return set;
		set = new HashSet<String>(EXTRA_FUNCTIONS);
		if(TTalk.funcSet!=null) set.addAll(TTalk.funcSet);
		if(TTalk.propertySet!=null) set.addAll(TTalk.propertySet);
		for(HyperTalkReference.Entry e : HyperTalkReference.entries()){
			String s = e.syntax;
			for(int i=s.indexOf('('); i>0; i=s.indexOf('(', i+1)){
				int j = i;
				while(j>0 && isWordChar(s.charAt(j-1))) j--;
				if(j<i) set.add(s.substring(j, i).toLowerCase(Locale.ROOT));
			}
			String key = e.key.toLowerCase(Locale.ROOT);
			if(key.startsWith("the ")) key = key.substring(4);
			if(key.indexOf(' ')<0) set.add(key);
		}
		if(TTalk.funcSet!=null) knownFunctions = set;
		return set;
	}

	/** 組み込みのコマンド名 */
	static Set<String> builtinCommands(){
		Set<String> set = knownCommands;
		if(set!=null) return set;
		set = new HashSet<String>(SYSTEM_COMMANDS);
		set.addAll(NEEDS_ARGUMENT);
		if(TTalk.commandSet!=null) set.addAll(TTalk.commandSet);
		for(HyperTalkReference.Entry e : HyperTalkReference.entries()){
			if(!e.in("cmd") && !e.in("msg")) continue;
			String key = e.key.toLowerCase(Locale.ROOT);
			int sp = key.indexOf(' ');
			set.add(sp<0 ? key : key.substring(0, sp));
		}
		if(TTalk.commandSet!=null) knownCommands = set;
		return set;
	}

	//---------------------------------------------------------------- 検査

	/**
	 * @param lines 継続記号を結合した行(TTalk.joinContinuationLines の結果)
	 * @param names スクリプトの外の名前。null なら未知のコマンド・関数は報告しない
	 */
	static void check(List<String> lines, Names names, Sink sink){
		ArrayList<ArrayList<Tok>> toks = new ArrayList<ArrayList<Tok>>(lines.size());
		for(String line : lines) toks.add(tokenize(line==null ? "" : line));

		//このスクリプトのハンドラ(未知の名前の判定と重複の検査)
		Set<String> ownCommands = new HashSet<String>();
		Set<String> ownFunctions = new HashSet<String>();
		HashMap<String,Integer> firstDefinition = new HashMap<String,Integer>();
		for(int line=0; line<toks.size(); line++){
			ArrayList<Tok> t = toks.get(line);
			if(t.size()<2 || t.get(1).kind!=WORD) continue;
			boolean on = t.get(0).is("on"), fn = t.get(0).is("function");
			if(!on && !fn) continue;
			String name = t.get(1).text;
			(on ? ownCommands : ownFunctions).add(name);
			String key = (on ? "on " : "function ")+name;
			Integer first = firstDefinition.putIfAbsent(key, line);
			if(first!=null){
				sink.report(line, true, "Handler \"{0}\" is already defined on line {1}; this one is never called.", name, first+1);
			}
		}

		String handler = null;
		for(int line=0; line<toks.size(); line++){
			ArrayList<Tok> t = toks.get(line);
			if(t.isEmpty()) continue;
			Tok first = t.get(0);
			if(first.is("on") || first.is("function")){
				handler = t.size()>1 ? t.get(1).text : null;
				continue;
			}
			if(first.is("end")){
				if(t.size()>1 && handler!=null && t.get(1).text.equals(handler)) handler = null;
				continue;
			}
			//ハンドラの外の行は HyperCard では実行されない(メモ書きに使われる)ので見ない
			if(handler==null) continue;
			Context cx = new Context(line, handler, names, ownCommands, ownFunctions, sink);
			cx.controlLine(t);
		}
	}

	private static final class Context {
		final int line;
		final String handler;
		final Names names;
		final Set<String> ownCommands, ownFunctions;
		final Sink sink;
		boolean reported;

		Context(int line, String handler, Names names, Set<String> ownCommands, Set<String> ownFunctions, Sink sink){
			this.line = line;
			this.handler = handler;
			this.names = names;
			this.ownCommands = ownCommands;
			this.ownFunctions = ownFunctions;
			this.sink = sink;
		}

		void error(String message, Object... args){
			if(reported) return; //1行に1つ(最初の原因だけ)
			reported = true;
			sink.report(line, false, message, args);
		}

		void warning(String message, Object... args){
			sink.report(line, true, message, args);
		}

		/** if/else/repeat の行を文と式に分ける */
		void controlLine(List<Tok> t){
			if(t.isEmpty()) return;
			Tok first = t.get(0);
			if(first.is("if")){
				int then = findTop(t, 1, "then");
				List<Tok> condition = t.subList(1, then<0 ? t.size() : then);
				if(!condition.isEmpty()) expression(condition);
				if(then>=0) branch(t.subList(then+1, t.size()));
				return;
			}
			if(first.is("else")){
				List<Tok> rest = t.subList(1, t.size());
				if(!rest.isEmpty() && rest.get(0).is("if")) controlLine(rest);
				else statement(rest);
				return;
			}
			if(first.is("then")){
				branch(t.subList(1, t.size()));
				return;
			}
			if(first.is("repeat")){
				repeat(t);
				return;
			}
			statement(t);
		}

		/** then の後: 「文 [else 文]」 */
		private void branch(List<Tok> t){
			if(t.isEmpty()) return;
			if(t.get(0).is("if")){ controlLine(t); return; }
			int elseAt = findTop(t, 0, "else");
			statement(elseAt<0 ? t : t.subList(0, elseAt));
			if(elseAt>=0) controlLine(t.subList(elseAt, t.size()));
		}

		private void repeat(List<Tok> t){
			if(t.size()<2) return;
			Tok kw = t.get(1);
			if(kw.is("while") || kw.is("until")){
				if(t.size()>2) expression(t.subList(2, t.size()));
			}
			else if(kw.is("with")){
				int eq = -1;
				for(int i=2; i<t.size(); i++) if(t.get(i).text.equals("=")){ eq = i; break; }
				int to = findTop(t, eq<0 ? 2 : eq+1, "to");
				if(eq>0 && to>eq+1){
					int startEnd = to;
					if(t.get(to-1).is("down")) startEnd = to-1;
					if(startEnd>eq+1) expression(t.subList(eq+1, startEnd));
					if(to+1<t.size()) expression(t.subList(to+1, t.size()));
					else error("repeat with has no end value after to.");
				}
			}
			else if(kw.is("for") && t.size()>2 && t.get(2).is("each")){
				//repeat for each item x in … (HyperZebra)
			}
			else if(!kw.is("forever")){
				int from = kw.is("for") ? 2 : 1;
				int end = t.size();
				if(t.get(end-1).is("times")) end--;
				if(end>from) expression(t.subList(from, end));
				else error("repeat needs a count.");
			}
		}

		void statement(List<Tok> t){
			if(t.isEmpty()) return;
			Tok cmd = t.get(0);
			if(cmd.kind!=WORD){
				warning("A statement must start with a command; this line is only a value.");
				return;
			}
			String c = cmd.text;
			int n = t.size();
			if(n==1 && NEEDS_ARGUMENT.contains(c) && !ownCommands.contains(c)){
				error("{0} needs more words.", c);
				return;
			}
			switch(c){
			case "if": case "else": case "then":
				controlLine(t);
				return;
			case "repeat":
				error("repeat must start its own line.");
				return;
			case "exit":
				if(n>=2 && t.get(1).is("repeat")) return;
				if(n>=3 && t.get(1).is("to") && t.get(2).is("hypercard")) return;
				if(n>=2 && t.get(1).text.equals(handler)) return;
				error("exit must be followed by repeat, {0} or to HyperCard.", handler);
				return;
			case "pass":
				if(n>=2 && t.get(1).text.equals(handler)) return;
				error("pass must name this handler: pass {0}.", handler);
				return;
			case "next":
				if(n>=2 && t.get(1).is("repeat")) return;
				error("next must be next repeat.");
				return;
			case "global":
				for(int i=1; i<n; i++){
					Tok w = t.get(i);
					boolean nameSlot = (i%2)==1;
					//global "name" も実行系では通る
					if(nameSlot ? (w.kind!=WORD && w.kind!=STRING) : w.kind!=COMMA){
						error("global expects variable names separated by commas.");
						return;
					}
				}
				return;
			case "end": case "on": case "function":
				return;
			}

			boolean builtin = builtinCommands().contains(c) && !ownCommands.contains(c);
			if(builtin) grammar(c, t);
			else if(names!=null && !isKnownCommand(c)){
				warning("No handler, XCMD or built-in command named \"{0}\" was found (typo?).", c);
			}
			if(!reported) expression(t.subList(1, n));
		}

		private boolean isKnownCommand(String c){
			return ownCommands.contains(c) || names.commands.contains(c) || builtinCommands().contains(c) || CONTROL_WORDS.contains(c);
		}

		private boolean isKnownFunction(String f){
			return ownFunctions.contains(f) || (names!=null && names.functions.contains(f)) || builtinFunctions().contains(f);
		}

		/** 組み込みコマンドごとの必須キーワード */
		private void grammar(String c, List<Tok> t){
			int n = t.size();
			switch(c){
			case "put": {
				int dest = findTopAny(t, 1, "into", "after", "before");
				//into/after/before ごとに別の文(日本語の文面はその語を含まないので {0} にしない)
				if(dest==1) error(t.get(1).is("into") ? "put needs a value before into." :
						t.get(1).is("after") ? "put needs a value before after." : "put needs a value before before.");
				else if(dest>0 && dest==n-1) error("put … {0} needs a container.", t.get(dest).text);
				break;
			}
			case "set": {
				int to = findTop(t, 1, "to");
				if(to<0) error("set needs to: set [the] property [of object] to value.");
				else if(to==1 || (to==2 && t.get(1).is("the"))) error("set needs a property name.");
				else if(to==n-1) error("set … to needs a value.");
				break;
			}
			case "add": keyword(t, "to", "add needs the form: add value to container."); break;
			case "subtract": keyword(t, "from", "subtract needs the form: subtract value from container."); break;
			case "multiply": keyword(t, "by", "multiply needs the form: multiply container by value."); break;
			case "divide": keyword(t, "by", "divide needs the form: divide container by value."); break;
			case "wait":
				if((t.get(1).is("until") || t.get(1).is("while")) && n==2)
					error("wait {0} needs a condition.", t.get(1).text);
				break;
			case "send": {
				int to = findTop(t, 1, "to");
				if(to==1) error("send needs a message.");
				else if(to==n-1) error("send … to needs an object.");
				break;
			}
			}
		}

		/** message: the whole message of the command (one for each command, so that a language can word the form its own way) */
		private void keyword(List<Tok> t, String kw, String message){
			int at = findTop(t, 1, kw);
			if(at<0 || at==1 || at==t.size()-1) error(message);
		}

		/** 演算子の前後の式・括弧の対応・関数名を見る。t はコマンド語を除いた部分 */
		void expression(List<Tok> t){
			for(Tok x : t) if(x.kind==OTHER) return; //[] {} : などを含む式(コレクション)は見ない
			int depth = 0;
			for(int i=0; i<t.size() && !reported; i++){
				Tok x = t.get(i);
				Tok prev = i>0 ? t.get(i-1) : null;
				Tok next = i+1<t.size() ? t.get(i+1) : null;
				if(x.kind==OPEN){
					depth++;
					//"reslist ()": a function called with a space before its empty parentheses (ResGenie) runs as reslist()
					if(next!=null && next.kind==CLOSE && (prev==null || !prev.call && prev.kind!=WORD)){
						error("Empty parentheses.");
					}
					continue;
				}
				if(x.kind==CLOSE){ depth--; continue; }
				if(x.kind==WORD && x.call && !NOT_CALLS.contains(x.text)){
					if(names!=null && !isKnownFunction(x.text)){
						warning("No function handler, XFCN or built-in function named \"{0}\" was found (typo?).", x.text);
					}
					continue;
				}
				boolean symbol = x.kind==OPERATOR;
				boolean wordOp = x.kind==WORD && WORD_BINARY.contains(x.text);
				if(!symbol && !wordOp) continue;
				boolean unary = x.text.equals("-") || x.text.equals("+");
				if(!unary && !endsOperand(prev)){
					error("Operator {0} has no value before it.", x.text);
					break;
				}
				if(!startsOperand(next)){
					error("Operator {0} has no value after it.", x.text);
					break;
				}
			}
		}

		private static boolean endsOperand(Tok t){
			if(t==null) return false;
			switch(t.kind){
			case NUMBER: case STRING: case CLOSE: return true;
			case WORD: return !BOUNDARIES.contains(t.text) && !WORD_BINARY.contains(t.text) && !t.text.equals("not")
					&& !t.text.equals("the") && !t.text.equals("is");
			default: return false;
			}
		}

		private static boolean startsOperand(Tok t){
			if(t==null) return false;
			switch(t.kind){
			case NUMBER: case STRING: case OPEN: return true;
			case OPERATOR: return t.text.equals("-") || t.text.equals("+");
			case WORD: return !BOUNDARIES.contains(t.text) && !WORD_BINARY.contains(t.text);
			default: return false;
			}
		}
	}

	/** 括弧の外にある最初の語 word の位置。無ければ -1 */
	static int findTop(List<Tok> t, int from, String word){
		int depth = 0;
		for(int i=Math.max(0, from); i<t.size(); i++){
			Tok x = t.get(i);
			if(x.kind==OPEN) depth++;
			else if(x.kind==CLOSE) depth--;
			else if(depth<=0 && x.is(word)) return i;
		}
		return -1;
	}

	static int findTopAny(List<Tok> t, int from, String... words){
		int best = -1;
		for(String w : words){
			int at = findTop(t, from, w);
			if(at>=0 && (best<0 || at<best)) best = at;
		}
		return best;
	}
}

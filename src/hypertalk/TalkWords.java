import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The HyperTalk tokenizer: a script line into words and their kinds (TTalk.resolution, the script editor's colouring
 * and lint). Moved unchanged out of the script editor's ScriptArea for the headless core (P1 S10).
 */
final class TalkWords {
	private TalkWords() {}

	//"1e" / "2.5E" のように、数値リテラルの指数部の直前まで読み込んだ状態か
	private static boolean isExponentPrefix(CharSequence str){
		int n = str.length();
		if(n<2) return false;
		char e = str.charAt(n-1);
		if(e!='e' && e!='E') return false;
		boolean digit = false;
		boolean dot = false;
		for(int i=0; i<n-1; i++){
			char c = str.charAt(i);
			if(Character.isDigit(c)) digit = true;
			else if(c=='.' && !dot) dot = true;
			else return false;
		}
		return digit;
	}

	//"id (" の直前がこの語なら id はオブジェクト指定（card id (x)）、それ以外の ID(x) は関数呼び出し
	private static final java.util.Set<String> ID_QUALIFIED_WORDS = java.util.Set.of(
		"the", "cd", "card", "bg", "bkgnd", "background", "btn", "button", "fld", "field", "stack",
		"window", "part", "menu", "menuitem", "program", "shape", "panel", "movie", "picture", "pict");

	static void checkWordsLine(String script, ArrayList<String> stringList, ArrayList<TTalk.wordType> typeList, boolean isCmd, boolean isEditor)
	{
		StringBuilder str = new StringBuilder(16);
		boolean inFunc = false;
		int dictionaryDepth = 0;
		ArrayList<TTalk.wordType> Brackets = new ArrayList<TTalk.wordType>();
		
		//単語分割する。演算子、括弧、コメント、文字列の分別。
		for(int i=0; i<script.length(); i++) {
			char code = script.charAt(i);
			//≠ ≤ ≥ は Mac Roman の $AD $B2 $B3。MacJapanese として読んだスタックでは半角カナ ｭ ｲ ｳ に化ける
			//(継続記号 ¬→ﾂ と同じ扱い)ので、引用符の外ではその演算子とみなす
			if(code=='ｭ') code='≠'; else if(code=='ｲ') code='≤'; else if(code=='ｳ') code='≥';
			if(code=='+' || code=='-' || code=='*' || code=='/' || code=='^' || code=='&'
				|| code=='=' || code=='<' || code=='>' || code=='≠' || code=='≤' || code=='≥') {
				if(code=='-' && i>0 && script.codePointAt(i-1)=='-')
				{
					//コメント
					typeList.remove(typeList.size()-1);
					stringList.remove(stringList.size()-1);
					//行の終わりまでコメント
					typeList.add(TTalk.wordType.COMMENT);
					stringList.add(script.substring(i-1));
					break;
				}
				if((code=='-' || code=='+') && isExponentPrefix(str)
						&& i+1<script.length() && Character.isDigit(script.charAt(i+1))){
					//1e-7 / 2.5E+3: 指数の符号は演算子ではなく数値リテラルの一部
					str.append(code);
					continue;
				}
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				typeList.add(TTalk.wordType.OPERATOR);
				stringList.add(String.valueOf((char)code).intern());
			} else if(code=='[' || code==']' || code=='{' || code=='}' || (code==':' && dictionaryDepth>0)) {
				if(str.length()>0){
					typeList.add(Character.isDigit(str.charAt(0)) ? TTalk.wordType.STRING : TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				TTalk.wordType kind = code=='[' ? TTalk.wordType.LCOLLECTION :
					code==']' ? TTalk.wordType.RCOLLECTION :
					code=='{' ? TTalk.wordType.LDICTIONARY :
					code=='}' ? TTalk.wordType.RDICTIONARY : TTalk.wordType.COLON;
				typeList.add(kind);
				stringList.add(String.valueOf(code));
				if(code=='{') dictionaryDepth++;
				else if(code=='}') dictionaryDepth--;
			} else if(code=='(') {
				if(str.length()>0 || typeList.size()>0 && typeList.get(typeList.size()-1) == TTalk.wordType.X) {
					if(str.length()>0){
						if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
						else typeList.add(TTalk.wordType.X);
						stringList.add(str.toString().toLowerCase().intern());
						str.setLength(0);
					}
					String funcstr = stringList.get(stringList.size()-1);
					if(funcstr=="cd" || funcstr=="card" || 
							funcstr=="bg" || funcstr=="bkgnd" ||funcstr=="background" ||
							funcstr=="btn" || funcstr=="button" ||
							funcstr=="fld" || funcstr=="field" ||
							funcstr=="stack"||
							funcstr=="char" || funcstr=="character"||
							funcstr=="item" || funcstr=="word" ||
							funcstr=="line" ||
							funcstr=="window" ||
							funcstr=="menu" || funcstr=="part" || funcstr=="menuitem" ||
							//card id (x) は括弧の式、TwinkleSord の put 100*ID(name) はユーザー関数 ID の呼び出し
							funcstr=="id" && stringList.size()>=2 && ID_QUALIFIED_WORDS.contains(stringList.get(stringList.size()-2)) ||
							funcstr=="or" || funcstr=="and" || funcstr=="not" ||
							funcstr=="until" || funcstr=="while" ||
							funcstr=="to" || funcstr=="from" || funcstr=="by" ||
							funcstr=="at" || funcstr=="with" || funcstr=="for" ||
							funcstr=="into" || funcstr=="after" || funcstr=="before" ||
							funcstr=="div" || funcstr=="mod" || funcstr=="is" || funcstr=="in" || funcstr=="within" || funcstr=="a" || funcstr=="an" ||
							//answer file ("Install " & x) / open file (p) / read from file (p): 関数 file() の呼び出しではなく括弧の式
							funcstr=="file" && stringList.size()>=2 && (stringList.get(stringList.size()-2)=="answer" ||
								stringList.get(stringList.size()-2)=="ask" || stringList.get(stringList.size()-2)=="open" ||
								stringList.get(stringList.size()-2)=="close" || stringList.get(stringList.size()-2)=="from" ||
								stringList.get(stringList.size()-2)=="to") ||
							//there is a file (SysDir() & "x") / there is no folder (p) (Cosmic Osmo)
							(funcstr=="file" || funcstr=="folder" || funcstr=="disk" || funcstr=="document" || funcstr=="application") &&
								stringList.size()>=2 && (stringList.get(stringList.size()-2)=="a" || stringList.get(stringList.size()-2)=="an" ||
								stringList.get(stringList.size()-2)=="no"))
					{
						typeList.add(TTalk.wordType.LBRACKET);
						Brackets.add(TTalk.wordType.LBRACKET);
						inFunc = false;
					}
					else if(funcstr=="of" && stringList.size() >= 2 && (
							/*stringList.get(stringList.size()-2).matches("^[0-9]*$") ||
							stringList.get(stringList.size()-2)=="char" ||
							stringList.get(stringList.size()-2)=="character" ||
							stringList.get(stringList.size()-2)=="item" ||
							stringList.get(stringList.size()-2)=="word" ||
							stringList.get(stringList.size()-2)=="line" ||*/
							!TTalk.funcSet.contains(stringList.get(stringList.size()-2)) ||
							//number of (x & "s"): the objects the value names (1行ショーブだっ！)
							stringList.get(stringList.size()-2)=="number") )
					{
						typeList.add(TTalk.wordType.LBRACKET);
						Brackets.add(TTalk.wordType.LBRACKET);
						inFunc = false;
					}
					else if(isCmd && typeList.size()==1)
					{
						//左側がコマンドとして認識される場合
						typeList.add(TTalk.wordType.LBRACKET);
						Brackets.add(TTalk.wordType.LBRACKET);
						inFunc = false;
					}
					else {
						typeList.add(TTalk.wordType.LFUNC);
						Brackets.add(TTalk.wordType.LFUNC);
						inFunc = true;
					}
					stringList.add("(");
				}
				else {
					typeList.add(TTalk.wordType.LBRACKET);
					Brackets.add(TTalk.wordType.LBRACKET);
					inFunc = false;
					stringList.add("(");
				}
			} else if(code==')') {
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				if(Brackets.size()==0 || Brackets.get(Brackets.size()-1)==TTalk.wordType.LBRACKET){
					typeList.add(TTalk.wordType.RBRACKET);
					if(Brackets.size()>=1) Brackets.remove(Brackets.size()-1);
				}
				else if(Brackets.get(Brackets.size()-1)==TTalk.wordType.LFUNC){
					typeList.add(TTalk.wordType.RFUNC);
					if(Brackets.size()>=1) Brackets.remove(Brackets.size()-1);
				}
				if(Brackets.size()==0 || Brackets.get(Brackets.size()-1)==TTalk.wordType.LBRACKET){
					inFunc = false;
				}
				else{
					inFunc = true;
				}
				stringList.add(")");
			} else if(code==',') {
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				if(inFunc){
					typeList.add(TTalk.wordType.COMMA_FUNC);//関数の引数指定
				}
				else{
					typeList.add(TTalk.wordType.COMMA);//loc/rectあるいはglobal、はたまたハンドラの引数
				}
				stringList.add(",");
			} else if(code=='"') {
				if(str.length()>0) {
					typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				i++;
				while(i<script.length()) {
					code = script.charAt(i);
					if(code=='"') break;
					str.append(code);
					i++;
				}
				if(isEditor){
					typeList.add(TTalk.wordType.QUOTE);
					stringList.add("\"");
				}
				
				typeList.add(TTalk.wordType.STRING);
				stringList.add(TTalk.quotedLiteralToken(str.toString()));
				str.setLength(0);

				if(isEditor){
					typeList.add(TTalk.wordType.QUOTE);
					stringList.add("\"");
				}
			} else if (code==' ' || code=='\t' || code=='\u3000') {
				//全角スペースも単語の区切りとして扱う(日本語入力で誤って入りやすい)
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
			}
			else if(i==script.length()-1) {
				if(str.length()>0 && isPlainNumber(str) && Character.isLetter(code)){
					//数字の直後に空白なしで単語が続いても区切る（例:"1to"→"1"と"to"）
					typeList.add(TTalk.wordType.STRING);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				str.append(code);
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
			} else {
				if(str.length()>0 && isPlainNumber(str) && Character.isLetter(code) &&
						!isExponentMarker(script, i)){
					//数字の直後に空白なしで単語が続いても区切る（例:"1to"→"1"と"to"）
					typeList.add(TTalk.wordType.STRING);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				str.append(code);
			}
		}
		functionFormOfKeywords(stringList, typeList, isCmd);
		bareLayerField(stringList, typeList);
		if(!isEditor) byteChunks(stringList, typeList);
	}

	/**
	 * LiveCode's byte chunk for binary data, one character per byte: byte i of v, byte 1 to 4 of v, last byte of v and
	 * the number of bytes in v become char … (a byte is a character of binary data, see TalkBinary). Only where "byte"
	 * starts a chunk ("of" or "in" after its number), which HyperCard does not compile, so a variable named byte stays one.
	 */
	private static final java.util.Set<String> BYTE_CHUNK_STOPS = java.util.Set.of("into", "after", "before", "then", "else",
		"is", "contains", "and", "or", "with", "by", "&", "&&", "=", "<", ">", "<>", "<=", ">=", "≠", "≤", "≥");
	private static void byteChunks(ArrayList<String> words, ArrayList<TTalk.wordType> types){
		for(int k=0; k+1<words.size(); k++){
			if(types.get(k)!=TTalk.wordType.X) continue;
			String w = words.get(k);
			if(w.equals("bytes")){
				if(k>=2 && words.get(k-1).equals("of") && words.get(k-2).equals("number") && types.get(k-1)!=TTalk.wordType.STRING &&
						(words.get(k+1).equals("of") || words.get(k+1).equals("in")) && types.get(k+1)!=TTalk.wordType.STRING) words.set(k, "chars");
				continue;
			}
			if(!w.equals("byte")) continue;
			String next = words.get(k+1);
			TTalk.wordType nextType = types.get(k+1);
			boolean ofNext = nextType!=TTalk.wordType.STRING && (next.equals("of") || next.equals("in"));
			if(ofNext){
				//last byte of v / the second byte of v
				if(!(k>0 && types.get(k-1)!=TTalk.wordType.STRING && TTalk.isRelativeChunkSpecifier(words.get(k-1)))) continue;
			}else{
				if(nextType==TTalk.wordType.OPERATOR || nextType==TTalk.wordType.RBRACKET || nextType==TTalk.wordType.RFUNC ||
						nextType==TTalk.wordType.COMMA || nextType==TTalk.wordType.COMMA_FUNC) continue;
				if(nextType!=TTalk.wordType.STRING && BYTE_CHUNK_STOPS.contains(next)) continue;
				int depth = 0, tos = 0;
				boolean chunk = false;
				for(int m=k+1; m<words.size(); m++){
					TTalk.wordType t = types.get(m);
					if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC){ depth++; continue; }
					if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC){ if(--depth<0) break; continue; }
					if(t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC){ if(depth<=1 && m>k+1 && types.get(k+1)==TTalk.wordType.LFUNC || depth==0) break; continue; }
					if(depth>0 || t==TTalk.wordType.STRING) continue;
					String x = words.get(m);
					if(x.equals("of") || x.equals("in")){ chunk = m>k+1; break; }
					if(x.equals("to")){ if(++tos>1) break; continue; }
					if(BYTE_CHUNK_STOPS.contains(x)) break;
				}
				if(!chunk) continue;
			}
			words.set(k, "char");
			if(nextType==TTalk.wordType.LFUNC){
				//byte (i*4) of v: the parentheses are the number's, as after char
				int depth = 0;
				for(int m=k+1; m<words.size(); m++){
					TTalk.wordType t = types.get(m);
					if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) depth++;
					else if((t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) && --depth==0){
						types.set(m, TTalk.wordType.RBRACKET);
						break;
					}
				}
				types.set(k+1, TTalk.wordType.LBRACKET);
			}
		}
	}

	//put x into background field / card field with nothing after "field": HyperCard J-2.3 reads the variable "field" (probes
	//Jhug Bare Field Var / Bare Bg Field: no error, no field changes; JHUG ラデアック・7's openCard on a background without fields)
	private static final java.util.Set<String> LAYER_WORDS = java.util.Set.of("card", "cd", "background", "bkgnd", "bg");
	private static final java.util.Set<String> VALUE_END_WORDS = java.util.Set.of("then", "else", "into", "after", "before", "and",
		"or", "is", "contains", "mod", "div", "&", "&&", "+", "-", "*", "/", "=", "<", ">", "<>", "<=", ">=", "≠", "≤", "≥", "^");
	private static void bareLayerField(ArrayList<String> words, ArrayList<TTalk.wordType> types){
		for(int k=0; k+1<words.size(); k++){
			if(types.get(k)!=TTalk.wordType.X || types.get(k+1)!=TTalk.wordType.X) continue;
			if(!LAYER_WORDS.contains(words.get(k)) || words.get(k+1)!="field") continue;
			if(k>0 && types.get(k-1)==TTalk.wordType.X){
				String p = words.get(k-1);
				if(p=="a" || p=="an" || p=="this" || p=="number" || p=="next" || p=="prev" || p=="last" ||
						p=="first" || p=="any" || p=="mid" || p=="middle" || TTalk.isRelativeChunkSpecifier(p)) continue;
			}
			int n = k+2;
			if(n<words.size()){
				TTalk.wordType t = types.get(n);
				boolean end = t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC || t==TTalk.wordType.COMMA ||
					t==TTalk.wordType.COMMA_FUNC || (t!=TTalk.wordType.STRING && VALUE_END_WORDS.contains(words.get(n)));
				if(!end) continue;
			}
			words.remove(k);
			types.remove(k);
		}
	}

	//HyperCard J-2.3 で確かめた語: 値を置く位置の card(7) / cd (7) / of(7) / to(7) / char(7) などは同名のユーザー関数の呼び出し
	//(function card x があれば呼ばれ、無ければ「card がわかりません」)。オブジェクトやキーワードを置く位置
	//(the short name of cd (2)、there is a cd (2)、go cd (2)、add 1 to (v)、x is in (y))と、括弧の後ろに of/to/in が続く
	//chunk(char (2) of x、line (n) to (m) of x)は今までどおり。btn/fld は値の位置でもオブジェクト(HC もボタンの内容を返す)
	private static final java.util.Set<String> VALUE_FUNCTION_WORDS = java.util.Set.of(
		"a", "an", "at", "by", "for", "from", "in", "of", "to", "until", "while", "with", "within",
		"card", "cd", "bg", "bkgnd", "background", "stack", "window", "menu", "menuitem",
		"char", "character", "item", "line", "word");
	//part(a, b, c) / btn(…, …): with a comma in the brackets the words cannot name an object, so they call the user function of
	//that name (JHUG W.H.A.T. Kakkuro "function part total, N, outer", written before HyperCard 2.2 made "part" a keyword;
	//HC J-2.3 itself no longer compiles it - probe Jhug Part Function). part(7) stays the object, as in HC J-2.3
	private static final java.util.Set<String> OBJECT_FUNCTION_WORDS = java.util.Set.of("part", "btn", "button", "fld", "field");
	private static boolean hasTopLevelComma(ArrayList<TTalk.wordType> types, int from, int close){
		for(int d=0, m=from; m<close; m++){
			TTalk.wordType t = types.get(m);
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) d++;
			else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) d--;
			else if(d==0 && (t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC)) return true;
		}
		return false;
	}
	private static final java.util.Set<String> CHUNK_WORDS = java.util.Set.of("char", "character", "item", "word", "line");
	private static final java.util.Set<String> PREPOSITION_WORDS = java.util.Set.of(
		"a", "an", "at", "by", "for", "from", "in", "of", "to", "until", "while", "with", "within");
	//この語の直後は式(値)の位置
	private static final java.util.Set<String> VALUE_POSITION_WORDS = java.util.Set.of(
		"put", "get", "return", "if", "is", "and", "or", "not", "div", "mod", "contains", "until", "while",
		"char", "character", "item", "word", "line"); //chunk の番号: char cd(0) of x

	private static void functionFormOfKeywords(ArrayList<String> words, ArrayList<TTalk.wordType> types, boolean isCmd){
		for(int k=1; k<types.size(); k++){
			if(types.get(k)!=TTalk.wordType.LBRACKET || types.get(k-1)!=TTalk.wordType.X) continue;
			String w = words.get(k-1);
			boolean objectWord = OBJECT_FUNCTION_WORDS.contains(w);
			if(!(VALUE_FUNCTION_WORDS.contains(w) || objectWord) || !valuePosition(words, types, k-2, w, isCmd)) continue;
			int close = -1;
			for(int d=0, m=k; m<types.size(); m++){
				TTalk.wordType t = types.get(m);
				if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) d++;
				else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC){ if(--d==0){ close = m; break; } }
			}
			if(close<0) continue;
			if(objectWord && !hasTopLevelComma(types, k+1, close)) continue;
			//char (2) of x / item (n-1)*2+1 of x / line (1) to (2) of x / card (2) of stack "x" は chunk・オブジェクト:
			//括弧の後ろ、式の区切りまでに of/to/in がある。chunk の番号の中(char cd(0) of x)の of は外側の chunk のもの
			boolean indexOfChunk = k>=2 && types.get(k-2)==TTalk.wordType.X && CHUNK_WORDS.contains(words.get(k-2));
			if(!indexOfChunk && !PREPOSITION_WORDS.contains(w) && chunkOfFollows(words, types, close+1)) continue;
			types.set(k, TTalk.wordType.LFUNC);
			types.set(close, TTalk.wordType.RFUNC);
			for(int d=0, m=k+1; m<close; m++){
				TTalk.wordType t = types.get(m);
				if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) d++;
				else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) d--;
				else if(d==0 && t==TTalk.wordType.COMMA) types.set(m, TTalk.wordType.COMMA_FUNC);
			}
		}
	}

	/** char/character/item/word/line at i with nothing a chunk could take after it (the end, an operator, then/into…): a variable or
	 *  parameter of that name, as in HyperCard J-2.3 (itch.io ElfBalls: on selectCharacter character / if character = "Rogue") */
	private static boolean chunkWordEndsValue(ArrayList<String> words, int i){
		if(i+1>=words.size()) return true;
		String x = words.get(i+1);
		switch(x){
			case "=": case "<": case ">": case "<>": case "≠": case "<=": case ">=": case "≤": case "≥": case "&": case "&&":
			case "+": case "*": case "/": case "^": case ",": case ")":
			case "then": case "else": case "into": case "after": case "before": case "is": case "contains": case "and": case "or":
			case "mod": case "div": return true;
			default: return false;
		}
	}

	//from から式の区切り(深さ0のコンマ、閉じ括弧、then/else/into/after/before)までに深さ0の of/to/in があるか
	private static boolean chunkOfFollows(ArrayList<String> words, ArrayList<TTalk.wordType> types, int from){
		for(int d=0, m=from; m<types.size(); m++){
			TTalk.wordType t = types.get(m);
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC){ d++; continue; }
			if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC){ if(--d<0) return false; continue; }
			if(d>0 || t==TTalk.wordType.STRING) continue;
			if(t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC) return false;
			String x = words.get(m);
			if(x=="then" || x=="else" || x=="into" || x=="after" || x=="before") return false;
			if(x=="of" || x=="to" || x=="in") return true;
		}
		return false;
	}

	private static boolean valuePosition(ArrayList<String> words, ArrayList<TTalk.wordType> types, int p, String w, boolean isCmd){
		if(p<0) return !isCmd; //式の先頭(文の先頭はコマンド)
		TTalk.wordType t = types.get(p);
		if(t==TTalk.wordType.OPERATOR || t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC ||
				t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC) return true;
		if(t!=TTalk.wordType.X) return false;
		String prev = words.get(p);
		//x is in (y) / x is not within (r) / x is a (y): 演算子の一部。last item of (x) / char 1 to (n) の of/to は chunk のもの
		//(not alone is the operator: if not within("0,0",fRect) calls the user function - JHUG GraphTrainer)
		if((prev=="is" || (prev=="not" && p>0 && types.get(p-1)==TTalk.wordType.X && words.get(p-1)=="is")) &&
				(w=="in" || w=="within" || w=="a" || w=="an")) return false;
		if(CHUNK_WORDS.contains(prev) && PREPOSITION_WORDS.contains(w)){
			//last item of (x): 序数の後ろの chunk には番号が無い。char of(0) of x の of(0) は番号(ユーザー関数)
			if(p==0 || types.get(p-1)==TTalk.wordType.STRING) return false;
			return !TTalk.isRelativeChunkSpecifier(words.get(p-1)) && words.get(p-1)!="mid";
		}
		return VALUE_POSITION_WORDS.contains(prev);
	}

	//数字に続く e/E が指数表記("1e3"/"1e-7"など)の一部か。直後に数字、または
	//符号と数字が続く場合だけ指数とみなす
	private static boolean isExponentMarker(String script, int i) {
		char c = script.charAt(i);
		if(c!='e' && c!='E') return false;
		if(i+1<script.length() && Character.isDigit(script.charAt(i+1))) return true;
		return i+2<script.length() && (script.charAt(i+1)=='-' || script.charAt(i+1)=='+')
			&& Character.isDigit(script.charAt(i+2));
	}

	//バッファが数(数字と小数点1つまで)だけで構成されているか（数字の直後に単語が続く場合の区切り判定に使う。
	//Dumbit Sound.1 の "put trunc(…)-.2into offset" も ".2" と "into" に分かれる）
	private static boolean isPlainNumber(StringBuilder str) {
		boolean digit = false, dot = false;
		for(int i=0; i<str.length(); i++){
			char c = str.charAt(i);
			if(Character.isDigit(c)) digit = true;
			else if(c=='.' && !dot) dot = true;
			else return false;
		}
		return digit;
	}
	
	
	/** a word that names the kind of object before its name: card field <name>, bg btn <name>, stack <name>… */
	private static boolean isObjectKindWord(String w){
		switch(w.toLowerCase(java.util.Locale.ROOT)){
			case "field": case "fld": case "button": case "btn": case "part": case "card": case "cd": case "bg": case "bkgnd":
			case "background": case "stack": case "window": return true;
			default: return false;
		}
	}

	/** the word comes right after "field"/"button" (unquoted): a part's name, also titlebar, menubar, msg or hypercard
	 *  (ExtractIt! for Stacks: put "Volumes" into cd fld "TitleBar" / put theItem into cd fld TitleBar) */
	private static boolean afterPartWord(ArrayList<String> words, ArrayList<TTalk.wordType> types, int i){
		if(i<1 || types.get(i-1)==TTalk.wordType.STRING) return false;
		String w = words.get(i-1);
		return w=="field" || w=="fld" || w=="button" || w=="btn";
	}

	static void checkWordsLine2(String script, ArrayList<String> stringList, ArrayList<TTalk.wordType> typeList, boolean isCmd, StringBuilder handler, TreeSet<String> varSet)
	{
		String command = "";

		

		//演算子を特定
		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType theType = typeList.get(i);
			
			if((theType==TTalk.wordType.X || theType==TTalk.wordType.OPERATOR) &&
				TTalk.operatorSet.contains(stringList.get(i)/*.toLowerCase()*/)) {
				if(stringList.get(i)=="div") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="mod") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="not") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="and") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="or") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="contains") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="within") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i).equals("there")) {
					if(i + 1 < typeList.size() && stringList.get(i+1).equals("is")) {
						if(i + 2 < typeList.size() && (stringList.get(i+2).equals("a") ||
								stringList.get(i+2).equals("an") || stringList.get(i+2).equals("no"))) {
							typeList.set(i,TTalk.wordType.OPERATOR);
							typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
							typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
						}
						else if(i + 3 < typeList.size() && stringList.get(i+2).equalsIgnoreCase("not")) {
							if(0==stringList.get(i+3).compareToIgnoreCase("a") || 0==stringList.get(i+3).compareToIgnoreCase("an")) {
								typeList.set(i,TTalk.wordType.OPERATOR);
								typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
								typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
								typeList.set(i+3,TTalk.wordType.OPERATOR_SUB);
							}
							//else throw new xTalkException("there is not requires a/an");
						}
						//else throw new xTalkException("there is requires a/an");
					}
				}
				else if(stringList.get(i).equals("is")) {
					//"a"/"in"/"not"がクォート付き文字列(x is "a")なら演算子の一部ではなく比較対象
					boolean next1IsWord = i + 1 < typeList.size() && typeList.get(i+1)!=TTalk.wordType.STRING;
					boolean next2IsWord = i + 2 < typeList.size() && typeList.get(i+2)!=TTalk.wordType.STRING;
					if(next1IsWord && (stringList.get(i+1).equals("in") || stringList.get(i+1).equals("a") || stringList.get(i+1).equals("an"))) {
						typeList.set(i,TTalk.wordType.OPERATOR);
						typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
						//is a number/date などの型名も評価対象ではない(dateが関数として評価されるのを防ぐ)
						if(!stringList.get(i+1).equals("in") && next2IsWord && TTalk.isTypeNameForIsA(stringList.get(i+2))){
							typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
						}
					}
					else if(next1IsWord && stringList.get(i+1).equals("not") ) {
						if(next2IsWord && (stringList.get(i+2).equalsIgnoreCase("in") || stringList.get(i+2).equalsIgnoreCase("a") || stringList.get(i+2).equalsIgnoreCase("an"))) {
							typeList.set(i,TTalk.wordType.OPERATOR);
							typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
							typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
							if(!stringList.get(i+2).equalsIgnoreCase("in") && i + 3 < typeList.size() &&
								typeList.get(i+3)!=TTalk.wordType.STRING && TTalk.isTypeNameForIsA(stringList.get(i+3))){
								typeList.set(i+3,TTalk.wordType.OPERATOR_SUB);
							}
						}
						else { //is not
							typeList.set(i,TTalk.wordType.OPERATOR);
							typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
						}
					}
					else typeList.set(i,TTalk.wordType.OPERATOR);
				}
				else if(stringList.get(i)=="<") {
					if(i<typeList.size()-1 && (stringList.get(i+1)=="=" || stringList.get(i+1)==">") ) {
						typeList.set(i,TTalk.wordType.OPERATOR);
						typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
					}
				}
				else if(stringList.get(i)==">") {
					if(i<typeList.size()-1 && stringList.get(i+1)=="=" ) {
						typeList.set(i,TTalk.wordType.OPERATOR);
						typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
					}
				}
			}
			/*else if(theType==wordType.LFUNC) {
				if(i>=start+1 && (typeAry[i-1]==wordType.OPERATOR ||
						stringList.get(i-1)=="to"))
					typeAry[i]=wordType.LBRACKET;
			}*/
		}
		//An operator word is no function name: x contains (y & comma) groups its right side (Myst Preview's go handler)
		for(int i=1; i<typeList.size(); i++){
			if(typeList.get(i)==TTalk.wordType.LFUNC && stringList.get(i-1)!="within" && //値の位置の within(x) はユーザー関数
					(typeList.get(i-1)==TTalk.wordType.OPERATOR || typeList.get(i-1)==TTalk.wordType.OPERATOR_SUB))
				typeList.set(i, TTalk.wordType.LBRACKET);
		}
		
		//各単語の分別
		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType type = typeList.get(i);
			if(type==TTalk.wordType.X){
				String str = stringList.get(i);
				if(i==0 && isCmd){
					//行の最初の単語
					if(str=="repeat"){
						typeList.set(i, TTalk.wordType.REPEAT);
						command = "repeat";
						continue;
					}
					else if(str=="if"){
						typeList.set(i, TTalk.wordType.IF);
						continue;
					}
					else if(str=="else"){
						typeList.set(i, TTalk.wordType.ELSE);
						continue;
					}
					else if(str=="end"){
						if(typeList.size()>i+1){
							if(stringList.get(i+1)=="if"){
								typeList.set(i, TTalk.wordType.ENDIF);
								i++;
								typeList.set(i, TTalk.wordType.ENDIF);
								continue;
							}
							else if(stringList.get(i+1)=="repeat"){
								typeList.set(i, TTalk.wordType.END_REPEAT);
								i++;
								typeList.set(i, TTalk.wordType.END_REPEAT);
								continue;
							}
							else if(stringList.get(i+1).equalsIgnoreCase(handler.toString())){
								typeList.set(i, TTalk.wordType.END_HAND);
								i++;
								typeList.set(i, TTalk.wordType.END_HAND);
								varSet.clear();
								handler.delete(0, handler.length());
								continue;
							}
						}
					}
					else if(str=="on"){
						if(typeList.size()>i+1){
							typeList.set(i, TTalk.wordType.ON_HAND);
							i++;
							typeList.set(i, TTalk.wordType.ON_HAND);
							handler.append(stringList.get(i));
							continue;
						}
					}
					else if(str=="function"){
						if(typeList.size()>i+1){
							typeList.set(i, TTalk.wordType.ON_FUNC);
							i++;
							typeList.set(i, TTalk.wordType.ON_FUNC);
							handler.append(stringList.get(i));
							continue;
						}
					}
				}
				if((i==0 && isCmd) || i>=1 && (typeList.get(i-1)==TTalk.wordType.THEN || typeList.get(i-1)==TTalk.wordType.ELSE)){
					//コマンドをかけるところの最初の単語
					if(Host.get().hasCardWindow() && HZ.stack()!=null && HZ.stack().rsrc!=null &&
							HZ.stack().rsrc.hasXcmd(str, "command")){
						typeList.set(i, TTalk.wordType.XCMD);
					}
					else if(str=="global"){
						typeList.set(i, TTalk.wordType.GLOBAL);
						continue;
					}
					else if(TTalk.commandSet.contains(str)){
						typeList.set(i, TTalk.wordType.CMD);
						command = str;
						continue;
					}
					else if(str=="return"){
						typeList.set(i, TTalk.wordType.RETURN);
						continue;
					}
					else if(str=="pass"){
						if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase(handler.toString())){
							typeList.set(i, TTalk.wordType.PASS);
							i++;
							typeList.set(i, TTalk.wordType.PASS);
							continue;
						}
					}
					else if(str=="exit"){
						if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase(handler.toString())){
							typeList.set(i, TTalk.wordType.EXIT);
							i++;
							typeList.set(i, TTalk.wordType.EXIT);
							continue;
						}
						else if(typeList.size()>i+2 && stringList.get(i+1).equalsIgnoreCase("to") && stringList.get(i+2).equalsIgnoreCase("hypercard")){
							typeList.set(i, TTalk.wordType.EXIT);
							i++;
							typeList.set(i, TTalk.wordType.EXIT);
							i++;
							typeList.set(i, TTalk.wordType.EXIT);
							continue;
						}
						else if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase("repeat")){
							typeList.set(i, TTalk.wordType.EXIT_REP);
							i++;
							typeList.set(i, TTalk.wordType.EXIT_REP);
							continue;
						}
					}
					else if(str=="next"){
						if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase("repeat")){
							typeList.set(i, TTalk.wordType.NEXT_REP);
							i++;
							typeList.set(i, TTalk.wordType.NEXT_REP);
							continue;
						}
					}
					else if(str=="if"){
						typeList.set(i, TTalk.wordType.IF);
						continue;
					}
					else if(str=="else"){
						typeList.set(i, TTalk.wordType.ELSE);
						continue;
					}
					else if(str=="then"){
						typeList.set(i, TTalk.wordType.THEN);
						continue;
					}
					else{
						typeList.set(i, TTalk.wordType.USER_CMD);
					}
				}
				else{
					//コマンドが書けないところ
					if(str=="else"){
						typeList.set(i, TTalk.wordType.ELSE);
						continue;
					}
					else if(str=="then"){
						typeList.set(i, TTalk.wordType.THEN);
						continue;
					}
					else if(Host.get().hasCardWindow() && HZ.stack()!=null && HZ.stack().rsrc!=null &&
							//an XFCN is called only as name(...): "the userLevel" stays the property even when the stack
							//has a UserLevel XFCN (Rinaldi's X-Archive), and a variable of that name stays a variable
							i+1<stringList.size() && stringList.get(i+1).equals("(") && (i==0 || !"the".equals(stringList.get(i-1))) &&
							HZ.stack().rsrc.hasXcmd(str, "function")){
						typeList.set(i, TTalk.wordType.XFCN);
						continue;
					}
					else if(typeList.get(0)==TTalk.wordType.ON_HAND ||
							typeList.get(0)==TTalk.wordType.ON_FUNC)
					{
						//引数
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(str=="it" || (i>0 && stringList.get(i-1)!="the")&&(varSet!=null&&varSet.contains(str))&&
							!propertyOfObject(stringList, i) && !layerWordBeforePart(stringList, i) ||
							//an expression that starts with a variable named like a chunk: "if item is "Quit HyperCard" then" in
							//"on doMenu item" (HyperExtend's プラグインマネージャ)
							i==0 && !isCmd && varSet!=null && varSet.contains(str) &&
							(stringList.size()==1 || TTalk.operatorSet.contains(stringList.get(1)) || stringList.get(1)=="then"))
					{
						typeList.set(i, TTalk.wordType.VARIABLE);
						continue;
					}
					else if(TTalk.constantSet.contains(str)){
						typeList.set(i, TTalk.wordType.CONST);
						continue;
					}
					else if(str=="in" && i>=1 && typeList.get(i-1)==TTalk.wordType.FUNC && stringList.get(i-1)!="number" &&
							//not a function that takes no argument after "the": find the value of the clickline in bkgnd field "x" (AOL MOVIES)
							!(i>=2 && stringList.get(i-2)=="the" && TTalk.NO_ARGUMENT_FUNCTIONS.contains(stringList.get(i-1).toLowerCase(java.util.Locale.ROOT))))
					{
						//value in bkgnd field id 4 / length in x (The Ultimate Warrior): HyperCard J-2.3 takes "in" for "of" here
						stringList.set(i, "of");
						typeList.set(i, TTalk.wordType.OF_FUNC);
						continue;
					}
					else if(str=="of" && i>=1)
					{
						if(typeList.get(i-1)==TTalk.wordType.OBJECT){
							typeList.set(i, TTalk.wordType.OF_OBJ);
							continue;
						}
						else if(typeList.get(i-1)==TTalk.wordType.CHUNK){
							typeList.set(i, TTalk.wordType.OF_CHUNK);
							continue;
						}
						else if(typeList.get(i-1)==TTalk.wordType.FUNC){
							typeList.set(i, TTalk.wordType.OF_FUNC);
							continue;
						}
						else if(typeList.get(i-1)==TTalk.wordType.PROPERTY){
							typeList.set(i, TTalk.wordType.OF_PROP);
							continue;
						}
						if(i>=2){
							if(typeList.get(i-2)==TTalk.wordType.OBJECT){
								typeList.set(i, TTalk.wordType.OF_OBJ);
								continue;
							}
							else if(typeList.get(i-2)==TTalk.wordType.CHUNK){
								typeList.set(i, TTalk.wordType.OF_CHUNK);
								continue;
							}
						}
					}
					else if(typeList.get(0)==TTalk.wordType.GLOBAL)
					{
						//グローバル変数
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(i>=1 && typeList.get(i-1)==TTalk.wordType.REPEAT && (str=="with" || str=="until" || str=="while")){
						typeList.set(i, TTalk.wordType.REPEAT);
						continue;
					}
					else if(i>=1 && typeList.get(i-1)==TTalk.wordType.REPEAT && stringList.get(i-1)=="with"){
						//repeat変数
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(command=="put" && (str=="into" || str=="after" || str=="before")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="add" && str=="to"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="subtract" && str=="from"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="divide" && str=="by"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="multiply" && str=="by"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="show" && str=="at"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="answer" && (str=="with" || str=="or")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="ask" && str=="with"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="set" && str=="to"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="send" && str=="to"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="visual" && str=="effect"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="play" && str=="stop"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="pop" && str=="card"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="push" && str=="card"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="drag" && (str=="from" || str=="to" || str=="with")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="click" && (str=="at" || str=="with")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if((command=="lock" || command=="unlock") &&
							(str=="screen" || str=="with" || str=="messages" || str=="recent" || str=="errordialogs")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="wait" && (str=="until" || str=="while" || str=="for")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="convert" && (str=="to")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="open" && (str=="file")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="read" && (str=="from" || str=="file" || str=="at" || str=="for" || str=="until")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="repeat" && (str=="to" || str=="down" )){
						typeList.set(i, TTalk.wordType.REPEAT);
						continue;
					}
					else if(str=="cd" || str=="card"
						|| str=="bg" || str=="bkgnd" || str=="background"
						|| str=="btn" || str=="button"
						|| str=="fld" || str=="field"
						|| str=="stack"
						|| str=="window"
						|| str=="menu" || str=="part" || str=="menuitem"
						|| (str=="panel" && !(i+1<stringList.size() && stringList.get(i+1)=="of"))
						|| str=="shape"
						|| ((str=="menubar" || str=="titlebar" || str=="msg" || str=="message" || str=="hypercard") && !afterPartWord(stringList, typeList, i))
						|| str=="me"
						|| (str=="id" && i+1<stringList.size() && stringList.get(i+1)!="of"))
					{
						typeList.set(i, TTalk.wordType.OBJECT);
						continue;
					}
					else if((str=="char" || str=="character"
						|| str=="item" || str=="word"
						|| str=="line") && !chunkWordEndsValue(stringList, i))
					{
						typeList.set(i, TTalk.wordType.CHUNK);
						continue;
					}
					else if(i>=1 && typeList.get(i-1)==TTalk.wordType.CMD_SUB && str!="the" &&
							(command=="put" || command=="add" || command=="subtract") )
					{
						//変数("put x into the selection" の the は変数ではない)
						//multiply/divide の by の後ろは式で、変数の宣言にならない(HC J-2.3 probe TKV ByOf: multiply n by abs of -2)
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(TTalk.propertySet.contains(str) || TTalk.isAdjective(str))
					{
						//HyperZebra 独自のプロパティは the/set の後ろだけ。HyperCard のスクリプトでは変数名・部品名
						//(bg fld DATA of cd X、char DATA of C_DR、words in path of path)
						//(… of window: an external window's own property, PrefsCleaner's Listoid: put text of window "orfan" into allfiles)
						if((i>=1 && stringList.get(i-1)=="the") ||
								(i>=1 && stringList.get(i-1)=="set") ||
								(i+1<stringList.size() && stringList.get(i+1)=="of" && (!TTalk.EXTENSION_PROPERTIES.contains(str) ||
								 i+2<stringList.size() && stringList.get(i+2)=="window")))
						{
							typeList.set(i, TTalk.wordType.PROPERTY);
							continue;
						}
					}
					else if(!TTalk.funcSet.contains(str) && (i>=1 ? stringList.get(i-1)=="the" || !TTalk.commandSet.contains(str) : !isCmd) &&
							i+2<stringList.size() && stringList.get(i+1)=="of" && stringList.get(i+2)=="window")
					{
						//外部ウィンドウ(XCMD の windoid)の独自プロパティ: the buttonClick of window "Progress"(UxProgressBar)。
						//HyperCard と同じく the は無くてもよい: get displayMonth of window "Calendario"(wCalendar の説明どおりの書き方)。
						//式の先頭でも(Chartoid 2.7: if chartType of window wName = "pie")
						typeList.set(i, TTalk.wordType.PROPERTY);
						continue;
					}
					if(TTalk.funcSet.contains(str) && i>0 && (i+1>=stringList.size() || typeList.get(i+1)!=TTalk.wordType.LFUNC) &&
							isObjectKindWord(stringList.get(i-1)) && typeList.get(i-1)!=TTalk.wordType.STRING){
						//card field offset / bg btn length: オブジェクトの種類の後ろの関数名は(括弧が無ければ)名前(Canary)
						continue;
					}
					if(TTalk.funcSet.contains(str)){
						// roundは引数なしでは成立しない。&round や round = 1 のような
						// 位置では、同名の変数として後段で解決できるようにしておく。
						if("round".equals(str) && (i+1>=stringList.size() ||
								typeList.get(i+1)==TTalk.wordType.OPERATOR ||
								typeList.get(i+1)==TTalk.wordType.THEN ||
								typeList.get(i+1)==TTalk.wordType.ELSE)){
							continue;
						}
						if(str=="number" && i+2<stringList.size() && stringList.get(i+1)=="of" &&
								!stringList.get(i+2).matches("s$")){
							//number of xxsは関数だが、そうでない場合はプロパティ
							typeList.set(i, TTalk.wordType.PROPERTY);
							continue;
						}
						else if(str=="number" && i-1>0 && stringList.get(i-1)=="a"){
							typeList.set(i, TTalk.wordType.X);
							continue;
						}
						else {
							typeList.set(i, TTalk.wordType.FUNC);
							continue;
						}
					}
					else if(i+1<stringList.size() && stringList.get(i)!="of" && typeList.get(i+1)==TTalk.wordType.LFUNC){
						typeList.set(i, TTalk.wordType.USER_FUNC);
						continue;
					}
				}
			}
		}

		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType type = typeList.get(i);
			if(type==TTalk.wordType.X){
				String str = stringList.get(i);
				//second…tenth / middle / any as first and last: get second cd fld and (middle cd btn) were "Cannot understand a
				//field" / "No field named ")"" (HC J-2.3 probes group_sim_second_fld_*)
				if((str=="first"||str=="last"||str=="prev"||str=="previous"||str=="next"||TTalk.isRelativeChunkSpecifier(str)) &&
						i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.OBJECT){
					typeList.set(i, TTalk.wordType.OBJECT);
				}
				if(TTalk.isRelativeChunkSpecifier(str) && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.CHUNK){
					typeList.set(i, TTalk.wordType.CHUNK);
				}
			}
		}
		
		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType type = typeList.get(i);
			if(type==TTalk.wordType.X){
				String str = stringList.get(i);
				//"the time of the date": a function that takes no argument is called even when "of" follows (Mosaïcon)
				if(str=="the" && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.FUNC && (i+2>=stringList.size() || stringList.get(i+2)!="of" ||
						TTalk.NO_ARGUMENT_FUNCTIONS.contains(stringList.get(i+1).toLowerCase(java.util.Locale.ROOT)))){
					typeList.set(i, TTalk.wordType.THE_FUNC);
				}
				if(str=="the" && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.PROPERTY){
					typeList.set(i, TTalk.wordType.PROPERTY);
				}
				if(str=="this" && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.OBJECT){
					typeList.set(i, TTalk.wordType.OBJECT);
				}
			}
		}
	}
	
	
	/**
	 * A property name used as a local variable still reads the property after short/long/abbr: "if short name of this bg
	 * = "Edit"" in a handler with a parameter called name (HTML TagWriter's setPalette). HyperCard 2.4 does that, but takes
	 * a bare "name of this card" there as the variable (Can't understand arguments).
	 */
	/** cd/bg before fld/btn/part is the object word even when a variable has that name (戦闘シーン: put "128" into CD,
	 *  then word 6 of cd fld MD) */
	static boolean layerWordBeforePart(ArrayList<String> words, int i){
		if(i+1>=words.size()) return false;
		switch(words.get(i)){
			case "cd": case "card": case "bg": case "bkgnd": case "background": break;
			default: return false;
		}
		switch(words.get(i+1)){
			case "fld": case "field": case "btn": case "button": case "part":
			case "flds": case "fields": case "btns": case "buttons": case "parts": return true;
			default: return false;
		}
	}

	static boolean propertyOfObject(ArrayList<String> words, int i){
		String str = words.get(i);
		if(!TTalk.propertySet.contains(str) || i<1 || i+2>=words.size() || words.get(i+1)!="of") return false;
		return TTalk.isAdjective(words.get(i-1));
	}
}

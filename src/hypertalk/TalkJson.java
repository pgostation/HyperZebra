import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict JSON reader for the JSONValue(json, key) HyperTalk function. */
final class TalkJson {
	private final String source;
	private int position;

	private TalkJson(String source) { this.source=source; }

	/**
	 * JSONValue(json, key). A key the object does not have, written as a path ("weather.temperature", "items.2.name": array
	 * indexes start at 1), walks down the nested objects and arrays.
	 */
	static String value(String json, String key) throws xTalkException {
		if(TalkCollection.isCollection(json)) return collectionValue(json, key);
		Object root=parse(json);
		if(!(root instanceof Map<?,?>) && !(root instanceof ArrayList<?>))
			throw new xTalkException("The first argument to JSONValue must be an object or array");
		return result(select(root, key));
	}

	/** The JSON text parsed: Map (objects, in order), ArrayList, String, NumberText, Boolean or null. */
	static Object parse(String json) throws xTalkException {
		if(json==null || json.length()>16*1024*1024) throw new xTalkException("JSON is too large");
		TalkJson reader=new TalkJson(json);
		Object root=reader.readValue(0);
		reader.space();
		if(reader.position!=json.length()) throw reader.error(International.t("Extra characters after the value"));
		return root;
	}

	/** A JSON object's members as HyperTalk values (nested objects and arrays become collections). */
	static LinkedHashMap<String,String> object(String json) throws xTalkException {
		Object root=parse(json);
		if(!(root instanceof Map<?,?>)) throw new xTalkException("A JSON object is required");
		LinkedHashMap<String,String> values=new LinkedHashMap<>();
		for(Map.Entry<?,?> entry:((Map<?,?>)root).entrySet()) values.put((String)entry.getKey(), result(entry.getValue()));
		return values;
	}

	private static Object select(Object node, String key) throws xTalkException {
		if(node instanceof Map<?,?> && ((Map<?,?>)node).containsKey(key)) return ((Map<?,?>)node).get(key);
		if(node instanceof ArrayList<?> && !key.contains(".")) return element((ArrayList<?>)node, key);
		if(!key.contains(".")) return null;
		for(String step:key.split("\\.", -1)) {
			if(node instanceof Map<?,?>) node=((Map<?,?>)node).get(step);
			else if(node instanceof ArrayList<?>) {
				try{ node=element((ArrayList<?>)node, step); }
				catch(xTalkException e){ return null; }
			}
			else return null;
		}
		return node;
	}

	private static Object element(ArrayList<?> array, String key) throws xTalkException {
		int index=TalkCollection.arrayIndex(key);
		return index<=array.size() ? array.get(index-1) : null;
	}

	/** The same path walk through HyperTalk collections (JSONValue of a value that JSONValue returned). */
	private static String collectionValue(String value, String key) throws xTalkException {
		TalkCollection collection=TalkCollection.decode(value);
		if(!key.contains(".") || (!collection.isArray() && collection.entries.containsKey(key))) return collection.get(key);
		String node=value;
		for(String step:key.split("\\.", -1)) {
			if(!TalkCollection.isCollection(node)) return "";
			TalkCollection c=TalkCollection.decode(node);
			if(c.isArray()) {
				try{ TalkCollection.arrayIndex(step); }catch(xTalkException e){ return ""; }
			}
			node=c.get(step);
		}
		return node;
	}

	/** A parsed JSON value as a HyperTalk value: text for scalars (null is empty), collections for objects and arrays. */
	static String result(Object value) throws xTalkException {
		if(value==null) return "";
		if(value instanceof String) return (String)value;
		if(value instanceof NumberText) return ((NumberText)value).text;
		if(value instanceof Boolean) return value.toString();
		if(value instanceof ArrayList<?>) {
			TalkCollection array=TalkCollection.array();
			for(Object item:(ArrayList<?>)value) array.items.add(result(item));
			return array.encode();
		}
		TalkCollection dictionary=TalkCollection.dictionary();
		for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())
			dictionary.entries.put((String)entry.getKey(), result(entry.getValue()));
		return dictionary.encode();
	}

	private static final class NumberText {
		final String text;
		NumberText(String text) { this.text=text; }
		/** The number as written (VMTranslation reads its helper's request ids with String.valueOf). */
		@Override public String toString() { return text; }
	}

	private Object readValue(int depth) throws xTalkException {
		if(depth>128) throw error(International.t("Nesting is too deep"));
		space();
		if(position>=source.length()) throw error(International.t("Missing value"));
		char c=source.charAt(position);
		if(c=='"') return readString();
		if(c=='{') {
			position++;
			LinkedHashMap<String,Object> result=new LinkedHashMap<>();
			space();
			if(take('}')) return result;
			while(true) {
				space();
				if(position>=source.length() || source.charAt(position)!='"') throw error(International.t("A key must be a string"));
				String key=readString();
				space();
				if(!take(':')) throw error(International.t(": is required"));
				result.put(key, readValue(depth+1));
				space();
				if(take('}')) return result;
				if(!take(',')) throw error(International.t(", or } is required"));
			}
		}
		if(c=='[') {
			position++;
			ArrayList<Object> result=new ArrayList<>();
			space();
			if(take(']')) return result;
			while(true) {
				result.add(readValue(depth+1));
				space();
				if(take(']')) return result;
				if(!take(',')) throw error(International.t(", or ] is required"));
			}
		}
		if(source.startsWith("true", position)) { position+=4; return Boolean.TRUE; }
		if(source.startsWith("false", position)) { position+=5; return Boolean.FALSE; }
		if(source.startsWith("null", position)) { position+=4; return null; }
		if(c=='-' || c>='0' && c<='9') return readNumber();
		throw error(International.t("Invalid JSON value"));
	}

	private NumberText readNumber() throws xTalkException {
		int start=position;
		take('-');
		if(take('0')) {
			if(digit()) throw error(International.t("Extra leading zero in number"));
		}else {
			if(!digit()) throw error(International.t("Invalid number"));
			while(digit()) position++;
		}
		if(take('.')) {
			if(!digit()) throw error(International.t("Missing fractional digits"));
			while(digit()) position++;
		}
		if(take('e') || take('E')) {
			if(!take('+')) take('-');
			if(!digit()) throw error(International.t("Missing exponent digits"));
			while(digit()) position++;
		}
		return new NumberText(source.substring(start, position));
	}

	private String readString() throws xTalkException {
		position++;
		StringBuilder text=new StringBuilder();
		while(position<source.length()) {
			char c=source.charAt(position++);
			if(c=='"') return text.toString();
			if(c<0x20) throw error(International.t("Control character in string"));
			if(c!='\\') {
				if(Character.isHighSurrogate(c)) {
					if(position>=source.length() || !Character.isLowSurrogate(source.charAt(position)))
						throw error(International.t("Invalid Unicode surrogate pair"));
					text.append(c).append(source.charAt(position++));
				}else if(Character.isLowSurrogate(c)) throw error(International.t("Invalid Unicode surrogate pair"));
				else text.append(c);
				continue;
			}
			if(position>=source.length()) throw error(International.t("Incomplete escape sequence"));
			char escape=source.charAt(position++);
			switch(escape) {
				case '"': text.append('"'); break;
				case '\\': text.append('\\'); break;
				case '/': text.append('/'); break;
				case 'b': text.append('\b'); break;
				case 'f': text.append('\f'); break;
				case 'n': text.append('\n'); break;
				case 'r': text.append('\r'); break;
				case 't': text.append('\t'); break;
				case 'u': {
					char unicode=(char)hex4();
					if(Character.isHighSurrogate(unicode)) {
						if(position+2>source.length() || source.charAt(position)!='\\' || source.charAt(position+1)!='u')
							throw error(International.t("Invalid Unicode surrogate pair"));
						position+=2;
						char low=(char)hex4();
						if(!Character.isLowSurrogate(low)) throw error(International.t("Invalid Unicode surrogate pair"));
						text.append(unicode).append(low);
					}else if(Character.isLowSurrogate(unicode)) throw error(International.t("Invalid Unicode surrogate pair"));
					else text.append(unicode);
					break;
				}
				default: throw error(International.t("Invalid escape sequence"));
			}
		}
		throw error(International.t("Missing closing quote in string"));
	}

	private int hex4() throws xTalkException {
		if(position+4>source.length()) throw error(International.t("Incomplete Unicode escape"));
		int value=0;
		for(int i=0;i<4;i++) {
			char c=source.charAt(position++);
			int digit=c>='0'&&c<='9' ? c-'0' : c>='a'&&c<='f' ? c-'a'+10 : c>='A'&&c<='F' ? c-'A'+10 : -1;
			if(digit<0) throw error(International.t("Invalid Unicode escape"));
			value=(value<<4)|digit;
		}
		return value;
	}

	private boolean digit() { return position<source.length() && source.charAt(position)>='0' && source.charAt(position)<='9'; }
	private boolean take(char c) { if(position<source.length() && source.charAt(position)==c) { position++; return true; } return false; }
	private void space() { while(position<source.length()) { char c=source.charAt(position); if(c==' '||c=='\n'||c=='\r'||c=='\t') position++; else break; } }
	private xTalkException error(String detail) { return new xTalkException("Invalid JSON (position {0}): {1}", (position+1), detail); }
}

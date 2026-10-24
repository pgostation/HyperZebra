import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Value representation for HyperTalk arrays and dictionaries. Values are immutable strings;
 * changing an element re-encodes the collection and updates its owning variable. */
final class TalkCollection {
	private static final String PREFIX = "\u001fHZC";
	final ArrayList<String> items;
	final LinkedHashMap<String,String> entries;

	private TalkCollection(ArrayList<String> items, LinkedHashMap<String,String> entries) {
		this.items = items;
		this.entries = entries;
	}

	static TalkCollection array() { return new TalkCollection(new ArrayList<>(), null); }
	static TalkCollection dictionary() { return new TalkCollection(null, new LinkedHashMap<>()); }
	boolean isArray() { return items != null; }
	static boolean isCollection(String value) { return value != null && value.startsWith(PREFIX); }

	/** Recently decoded or encoded collections by their encoded text: reading ex[i] in a loop would otherwise decode the whole array every time. */
	private static final int CACHE_ENTRIES = 256, CACHE_MAX_SIZE = 4096;
	private static final LinkedHashMap<String,TalkCollection> CACHE = new LinkedHashMap<>(512, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String,TalkCollection> eldest) { return size() > CACHE_ENTRIES; }
	};

	private TalkCollection copy() {
		return new TalkCollection(items == null ? null : new ArrayList<>(items), entries == null ? null : new LinkedHashMap<>(entries));
	}

	private static synchronized TalkCollection cached(String value) { return CACHE.get(value); }

	private static synchronized void remember(String encoded, TalkCollection collection) {
		if((collection.isArray() ? collection.items.size() : collection.entries.size()) <= CACHE_MAX_SIZE) CACHE.put(encoded, collection);
	}

	/** The collection for reading only: it is shared, so use {@link #decodeForUpdate} before changing it. */
	static TalkCollection decode(String value) throws xTalkException {
		TalkCollection hit = cached(value);
		if(hit != null) return hit;
		TalkCollection result = decodeText(value);
		remember(value, result);
		return result;
	}

	/** A private copy that may be changed and then {@link #encode}d. */
	static TalkCollection decodeForUpdate(String value) throws xTalkException { return decode(value).copy(); }

	private static TalkCollection decodeText(String value) throws xTalkException {
		if(!isCollection(value)) throw new xTalkException("An array or a dictionary is required");
		try {
			byte[] bytes = Base64.getDecoder().decode(value.substring(PREFIX.length()));
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
			int kind = in.readUnsignedByte();
			if(kind != 'A' && kind != 'D') throw new IOException("invalid collection kind");
			int size = in.readInt();
			if(size < 0 || size > 100000) throw new IOException("invalid collection size");
			TalkCollection result = kind == 'A' ? array() : dictionary();
			for(int i=0; i<size; i++) {
				if(kind == 'A') result.items.add(in.readUTF());
				else result.entries.put(in.readUTF(), in.readUTF());
			}
			if(in.available()!=0) throw new IOException("trailing collection data");
			return result;
		}catch(IllegalArgumentException | IOException e){
			throw new xTalkException("Invalid collection data");
		}
	}

	String encode() throws xTalkException {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeByte(isArray() ? 'A' : 'D');
			out.writeInt(isArray() ? items.size() : entries.size());
			if(isArray()) for(String value : items) out.writeUTF(value);
			else for(Map.Entry<String,String> entry : entries.entrySet()) {
				out.writeUTF(entry.getKey());
				out.writeUTF(entry.getValue());
			}
			out.close();
			String encoded = PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
			remember(encoded, copy());
			return encoded;
		}catch(IOException e){ throw new xTalkException("Collection is too large"); }
	}

	String get(String key) throws xTalkException {
		if(!isArray()) return entries.getOrDefault(key, "");
		int index = arrayIndex(key);
		return index <= items.size() ? items.get(index-1) : "";
	}

	void put(String key, String value) throws xTalkException {
		if(!isArray()) { entries.put(key, value); return; }
		int index = arrayIndex(key);
		if(index > 100000) throw new xTalkException("Array index is too large");
		while(items.size() < index) items.add("");
		items.set(index-1, value);
	}

	void delete(String key) throws xTalkException {
		if(!isArray()) { entries.remove(key); return; }
		int index = arrayIndex(key);
		if(index <= items.size()) items.remove(index-1);
	}

	static int arrayIndex(String key) throws xTalkException {
		try {
			int index = TTalk.wholeNumber(key);
			if(index > 0) return index;
		}catch(NumberFormatException e){}
		throw new xTalkException("Array index must be an integer of at least 1: {0}", key);
	}

	static String display(String value) throws xTalkException {
		if(!isCollection(value)) return value;
		TalkCollection collection = decode(value);
		StringBuilder out = new StringBuilder(collection.isArray() ? "[" : "{");
		if(collection.isArray()) {
			for(String item : collection.items) {
				if(out.length()>1) out.append(", ");
				out.append(displayElement(item));
			}
			out.append(']');
		}else {
			for(Map.Entry<String,String> entry : collection.entries.entrySet()) {
				if(out.length()>1) out.append(", ");
				out.append(entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") ? entry.getKey() : quote(entry.getKey()));
				out.append(": ").append(displayElement(entry.getValue()));
			}
			out.append('}');
		}
		return out.toString();
	}

	private static String displayElement(String value) throws xTalkException {
		if(isCollection(value)) return display(value);
		return value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)") ? value : quote(value);
	}

	private static String quote(String value) { return "\"" + value.replace("\"", "\\\"") + "\""; }
}

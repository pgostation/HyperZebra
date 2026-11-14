import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
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

	/** Caches a copy of the collection (a copy only when it is small enough to cache) */
	private static synchronized void remember(String encoded, TalkCollection collection) {
		if((collection.isArray() ? collection.items.size() : collection.entries.size()) <= CACHE_MAX_SIZE) CACHE.put(encoded, collection.copy());
	}

	/** The collection for reading only: it is shared, so use {@link #decodeForUpdate} before changing it. */
	static TalkCollection decode(String value) throws xTalkException {
		TalkCollection hit = cached(value);
		if(hit != null) return hit;
		TalkCollection result = decodeText(value);
		synchronized(TalkCollection.class) {
			if((result.isArray() ? result.items.size() : result.entries.size()) <= CACHE_MAX_SIZE) CACHE.put(value, result);
		}
		return result;
	}

	/** A private copy that may be changed and then {@link #encode}d. */
	static TalkCollection decodeForUpdate(String value) throws xTalkException { return decode(value).copy(); }

	/** Elements an array or a dictionary may have (an image of 3000 x 3000 pixels has 3000 rows of 3000) */
	static final int MAX_SIZE = 10_000_000;

	private static TalkCollection decodeText(String value) throws xTalkException {
		if(!isCollection(value)) throw new xTalkException("An array or a dictionary is required");
		try {
			byte[] bytes = Base64.getDecoder().decode(value.substring(PREFIX.length()));
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
			int kind = in.readUnsignedByte();
			// 'A'/'D': elements in writeUTF form (up to 64 KB each); 'a'/'d': an int length and UTF-8, for longer ones
			boolean longForm = kind == 'a' || kind == 'd';
			if(kind != 'A' && kind != 'D' && !longForm) throw new IOException("invalid collection kind");
			int size = in.readInt();
			if(size < 0 || size > MAX_SIZE) throw new IOException("invalid collection size");
			TalkCollection result = kind == 'A' || kind == 'a' ? array() : dictionary();
			for(int i=0; i<size; i++) {
				if(result.isArray()) result.items.add(longForm ? readLong(in) : in.readUTF());
				else result.entries.put(longForm ? readLong(in) : in.readUTF(), longForm ? readLong(in) : in.readUTF());
			}
			if(in.available()!=0) throw new IOException("trailing collection data");
			return result;
		}catch(IllegalArgumentException | IOException e){
			throw new xTalkException("Invalid collection data");
		}
	}

	private static String readLong(DataInputStream in) throws IOException {
		int length = in.readInt();
		if(length < 0 || length > in.available()) throw new IOException("invalid element length");
		byte[] b = new byte[length];
		in.readFully(b);
		return new String(b, StandardCharsets.UTF_8);
	}

	String encode() throws xTalkException {
		String encoded = isArray() ? encodeArray(items) : encodeDictionary(entries);
		remember(encoded, this);
		return encoded;
	}

	/** The encoded form of an array of these elements (the list is not kept) */
	static String encodeArray(java.util.List<String> values) { return encodeParts(true, values, null); }
	static String encodeDictionary(Map<String,String> values) { return encodeParts(false, null, values); }

	private static String encodeParts(boolean array, java.util.List<String> list, Map<String,String> map) {
		try {
			return encodeParts(array, list, map, false);
		}catch(java.io.UTFDataFormatException e){
			try { return encodeParts(array, list, map, true); }
			catch(IOException e2){ throw new IllegalStateException(e2); }
		}catch(IOException e){ throw new IllegalStateException(e); }
	}

	private static String encodeParts(boolean array, java.util.List<String> list, Map<String,String> map, boolean longForm) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(array ? 16+list.size()*8 : 64);
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(array ? (longForm ? 'a' : 'A') : (longForm ? 'd' : 'D'));
		out.writeInt(array ? list.size() : map.size());
		if(array) for(String value : list) write(out, value, longForm);
		else for(Map.Entry<String,String> entry : map.entrySet()) {
			write(out, entry.getKey(), longForm);
			write(out, entry.getValue(), longForm);
		}
		out.close();
		return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	private static void write(DataOutputStream out, String value, boolean longForm) throws IOException {
		if(!longForm){ out.writeUTF(value); return; }
		byte[] b = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(b.length);
		out.write(b);
	}

	String get(String key) throws xTalkException {
		if(!isArray()) return entries.getOrDefault(key, "");
		int index = arrayIndex(key);
		return index <= items.size() ? items.get(index-1) : "";
	}

	void put(String key, String value) throws xTalkException {
		if(!isArray()) { entries.put(key, value); return; }
		int index = arrayIndex(key);
		if(index > MAX_SIZE) throw new xTalkException("Array index is too large");
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A collection held decoded in a variable while a script reads and changes its elements. Values stay strings
 * ({@link TalkCollection}'s encoding); a variable whose elements are changed one by one would otherwise be decoded and
 * encoded whole for every {@code put … into v[i][j]} (an image as a 2-D array of pixels took minutes). A node keeps the
 * encoded text it was made from or last gave out, and drops it when it, or an element below it, changes.
 */
final class LiveNode {
	/** String, or LiveNode for an element that is a collection being worked on; null for a dictionary and a row of pixels */
	ArrayList<Object> items;
	final LinkedHashMap<String,Object> entries;
	private String encoded;
	/** An array whose rows are the rows of these pixels (TalkPixels): items holds the row nodes */
	TalkPixels.Grid pixels;
	/** A row of pixels: element x is rowGrid's cell row*w + x-1 */
	private TalkPixels.Grid rowGrid;
	private int row;
	private boolean pixelsTried;

	private LiveNode(boolean array) {
		items = array ? new ArrayList<>() : null;
		entries = array ? null : new LinkedHashMap<>();
	}

	private LiveNode(TalkPixels.Grid grid, int row) {
		items = null; entries = null; rowGrid = grid; this.row = row;
	}

	static LiveNode decode(String value) throws xTalkException {
		Object[] known = TalkPixels.lookup(value);
		if(known != null) {
			int w = (Integer)known[0], h = (Integer)known[1];
			LiveNode node = pixelArray(new TalkPixels.Grid(w, h, ((int[])known[2]).clone()));
			node.encoded = value;
			return node;
		}
		TalkCollection c = TalkCollection.decode(value);
		LiveNode node = new LiveNode(c.isArray());
		if(c.isArray()) node.items.addAll(c.items);
		else node.entries.putAll(c.entries);
		node.encoded = value;
		return node;
	}

	private static LiveNode pixelArray(TalkPixels.Grid grid) {
		LiveNode node = new LiveNode(true);
		node.items.ensureCapacity(grid.h);
		for(int y=0; y<grid.h; y++) node.items.add(new LiveNode(grid, y));
		node.pixels = grid;
		return node;
	}

	boolean isArray() { return items != null || rowGrid != null; }
	boolean changed() { return encoded == null; }
	void touch() { encoded = null; }
	int size() { return rowGrid != null ? rowGrid.w : isArray() ? items.size() : entries.size(); }

	/** A row of pixels becomes a list of its texts (it grows, or an element becomes a collection being worked on) */
	private void ownRow() {
		if(rowGrid == null) return;
		ArrayList<Object> list = new ArrayList<>(rowGrid.w + 1);
		for(int x=0; x<rowGrid.w; x++) list.add(rowGrid.cell(row*rowGrid.w + x));
		rowGrid.broken = true;
		rowGrid = null;
		items = list;
	}

	private Object raw(String key) throws xTalkException {
		if(!isArray()) { Object v = entries.get(key); return v == null ? "" : v; }
		int index = TalkCollection.arrayIndex(key);
		if(rowGrid != null) return index <= rowGrid.w ? rowGrid.cell(row*rowGrid.w + index-1) : "";
		return index <= items.size() ? items.get(index-1) : "";
	}

	private void store(String key, Object value) throws xTalkException {
		if(!isArray()) { entries.put(key, value); return; }
		int index = TalkCollection.arrayIndex(key);
		if(index > TalkCollection.MAX_SIZE) throw new xTalkException("Array index is too large");
		if(rowGrid != null) {
			if(index <= rowGrid.w && value instanceof String) { rowGrid.set(row*rowGrid.w + index-1, (String)value); return; }
			ownRow();
		}
		if(pixels != null) { pixels.broken = true; pixels = null; } // a row replaced
		while(items.size() < index) items.add("");
		items.set(index-1, value);
	}

	/** The element as text */
	String get(String key) throws xTalkException {
		Object v = raw(key);
		return v instanceof LiveNode ? ((LiveNode)v).encode() : (String)v;
	}

	/** The element that is itself a collection, decoded for reading or changing; null when it is no collection */
	LiveNode child(String key) throws xTalkException {
		Object v = raw(key);
		if(v instanceof LiveNode) return (LiveNode)v;
		String text = (String)v;
		if(!TalkCollection.isCollection(text)) return null;
		LiveNode node = decode(text);
		store(key, node);//the same value, so this node's encoding still holds
		return node;
	}

	void put(String key, String value) throws xTalkException { store(key, value); encoded = null; }

	void add(String value) { ownRow(); if(pixels != null) { pixels.broken = true; pixels = null; } items.add(value); encoded = null; }
	void addFirst(String value) { ownRow(); if(pixels != null) { pixels.broken = true; pixels = null; } items.add(0, value); encoded = null; }

	/**
	 * The pixels of this array when every row is a row of canonical pixels of one length (TalkPixels), turning it into
	 * that form the first time; null when it is no such array (asked once for an array decoded from text).
	 */
	TalkPixels.Grid pixelGrid() {
		if(pixels != null) return pixels.broken ? null : pixels;
		if(items == null || rowGrid != null || pixelsTried) return null;
		pixelsTried = true;
		int h = items.size(), w = -1;
		if(h == 0 || h > TalkCollection.MAX_SIZE) return null;
		// rows that are array text read straight from their encoding (no String per pixel), rows decoded already cell by cell
		try {
			Object r0 = items.get(0);
			if(r0 instanceof LiveNode) { LiveNode n = (LiveNode)r0; if(n.rowGrid == null && n.items != null) w = n.items.size(); }
			else if(TalkCollection.isCollection((String)r0) && TalkCollection.decode((String)r0).isArray()) w = TalkCollection.decode((String)r0).items.size();
		} catch(xTalkException e) { return null; }
		if(w <= 0 || (long)w * h > 50_000_000L) return null;
		int[] argb = new int[w * h];
		for(int y=0; y<h; y++) {
			Object r = items.get(y);
			if(r instanceof String) {
				if(TalkPixels.parseRow((String)r, argb, y*w, w) == w) continue;
				java.util.List<String> cells;
				try {
					if(!TalkCollection.isCollection((String)r)) return null;
					TalkCollection c = TalkCollection.decode((String)r);
					if(!c.isArray()) return null;
					cells = c.items;
				} catch(xTalkException e) { return null; }
				if(!fill(cells, argb, y*w, w)) return null;
			} else {
				LiveNode n = (LiveNode)r;
				if(n.rowGrid != null || n.items == null || n.items.size() != w) return null;
				for(int x=0; x<w; x++) {
					Object c = n.items.get(x);
					long p = c instanceof String ? TalkPixels.canonical((String)c) : -1;
					if(p < 0) return null;
					argb[y*w + x] = (int)p;
				}
			}
		}
		TalkPixels.Grid grid = new TalkPixels.Grid(w, h, argb);
		for(int y=0; y<h; y++) items.set(y, new LiveNode(grid, y)); // the same text: the encoding still holds
		pixels = grid;
		// the same text decoded again (the array passed to a handler again, put into another variable) needs no reading
		if(encoded != null) TalkPixels.remember(encoded, w, h, argb.clone());
		return grid;
	}

	private static boolean fill(java.util.List<String> cells, int[] argb, int at, int w) {
		if(cells.size() != w) return false;
		for(int x=0; x<w; x++) {
			long p = TalkPixels.canonical(cells.get(x));
			if(p < 0) return false;
			argb[at + x] = (int)p;
		}
		return true;
	}

	/** After a compiled loop wrote cells from..to (indices into the grid): the rows' and this array's text are made again */
	void pixelsWritten(int from, int to) {
		TalkPixels.Grid g = pixels;
		if(g == null || from > to) return;
		for(int y = from / g.w, last = to / g.w; y <= last; y++) ((LiveNode)items.get(y)).encoded = null;
		encoded = null;
	}

	String encode() {
		if(encoded != null) return encoded;
		if(rowGrid != null) {
			encoded = rowGrid.rowText(row);
		}else if(isArray()) {
			ArrayList<String> values = new ArrayList<>(items.size());
			for(Object v : items) values.add(v instanceof LiveNode ? ((LiveNode)v).encode() : (String)v);
			encoded = TalkCollection.encodeArray(values);
			TalkPixels.Grid g = pixels;
			if(g != null && g.plain()) TalkPixels.remember(encoded, g.w, g.h, g.cells().clone()); // arrayToImage / decoded again
		}else {
			LinkedHashMap<String,String> values = new LinkedHashMap<>(entries.size()*2);
			for(Map.Entry<String,Object> e : entries.entrySet())
				values.put(e.getKey(), e.getValue() instanceof LiveNode ? ((LiveNode)e.getValue()).encode() : (String)e.getValue());
			encoded = TalkCollection.encodeDictionary(values);
		}
		return encoded;
	}
}

/**
 * The values of a handler's variables (MemoryData.valueList). A slot can hold a {@link LiveNode}: then the string in
 * the list may be older than the node, and every read brings it up to date first, so code that reads the list sees
 * the variable as it is. Writing a slot, or moving slots, lets the node go.
 */
final class ValueList extends ArrayList<String> {
	private static final long serialVersionUID = 1L;
	/** The slots that hold a node, and their nodes: a handler has only a few (one array of pixels), so a short list is
	 *  quicker than a map for the reads of every other variable. Not synchronized, like the list itself. */
	private int[] liveIndex = new int[0];
	private LiveNode[] liveNode = new LiveNode[0];
	private int liveCount;

	private int find(int index) {
		for(int k=0; k<liveCount; k++) if(liveIndex[k]==index) return k;
		return -1;
	}

	/** The variable at index as a collection being worked on; null when it holds no collection */
	LiveNode live(int index) throws xTalkException {
		int k = find(index);
		if(k >= 0) return liveNode[k];
		String value = super.get(index);
		if(!TalkCollection.isCollection(value)) return null;
		LiveNode node = LiveNode.decode(value);
		if(liveCount == liveIndex.length) {
			liveIndex = java.util.Arrays.copyOf(liveIndex, liveCount*2+2);
			liveNode = java.util.Arrays.copyOf(liveNode, liveCount*2+2);
		}
		liveIndex[liveCount] = index;
		liveNode[liveCount++] = node;
		return node;
	}

	/** The text in the slot, perhaps older than its node: only to tell whether it holds a collection (a slot with a node always does) */
	String peek(int index) { return super.get(index); }

	private void sync(int k) {
		LiveNode node = liveNode[k];
		if(!node.changed()) return;
		try { super.set(liveIndex[k], node.encode()); }
		catch(RuntimeException e) { /* being changed on another thread (the variable watcher): the older text */ }
	}

	private void syncAll() { for(int k=0; k<liveCount; k++) sync(k); }

	/** Slots move: the strings are brought up to date and the nodes let go */
	private void release() {
		syncAll();
		java.util.Arrays.fill(liveNode, 0, liveCount, null);
		liveCount = 0;
	}

	@Override public String get(int index) {
		if(liveCount != 0) { int k = find(index); if(k >= 0) sync(k); }
		return super.get(index);
	}
	@Override public String set(int index, String value) {
		if(liveCount != 0) {
			int k = find(index);
			if(k >= 0) {
				sync(k);//the old value set returns
				liveCount--;
				liveIndex[k] = liveIndex[liveCount];
				liveNode[k] = liveNode[liveCount];
				liveNode[liveCount] = null;
			}
		}
		return super.set(index, value);
	}

	@Override public void add(int index, String value) { release(); super.add(index, value); }
	@Override public String remove(int index) { release(); return super.remove(index); }
	@Override public boolean remove(Object o) { release(); return super.remove(o); }
	@Override public void clear() { release(); super.clear(); }
	@Override public boolean addAll(int index, Collection<? extends String> c) { release(); return super.addAll(index, c); }
	@Override public boolean removeAll(Collection<?> c) { release(); return super.removeAll(c); }
	@Override public boolean retainAll(Collection<?> c) { release(); return super.retainAll(c); }
	@Override public boolean removeIf(Predicate<? super String> filter) { release(); return super.removeIf(filter); }
	@Override public void replaceAll(UnaryOperator<String> operator) { release(); super.replaceAll(operator); }
	@Override public void sort(Comparator<? super String> c) { release(); super.sort(c); }
	@Override protected void removeRange(int from, int to) { release(); super.removeRange(from, to); }

	@Override public Object[] toArray() { syncAll(); return super.toArray(); }
	@Override public <T> T[] toArray(T[] a) { syncAll(); return super.toArray(a); }
	@Override public Iterator<String> iterator() { syncAll(); return super.iterator(); }
	@Override public ListIterator<String> listIterator() { release(); return super.listIterator(); }
	@Override public ListIterator<String> listIterator(int index) { release(); return super.listIterator(index); }
	@Override public List<String> subList(int from, int to) { release(); return super.subList(from, to); }
	@Override public void forEach(Consumer<? super String> action) { syncAll(); super.forEach(action); }
	@Override public Spliterator<String> spliterator() { syncAll(); return super.spliterator(); }
	@Override public int indexOf(Object o) { syncAll(); return super.indexOf(o); }
	@Override public int lastIndexOf(Object o) { syncAll(); return super.lastIndexOf(o); }
	@Override public boolean contains(Object o) { syncAll(); return super.contains(o); }
	@Override public boolean equals(Object o) { syncAll(); return super.equals(o); }
	@Override public int hashCode() { syncAll(); return super.hashCode(); }
	@Override public String toString() { syncAll(); return super.toString(); }
	@Override public Object clone() { syncAll(); ValueList copy = new ValueList(); copy.addAll(this); return copy; }
}

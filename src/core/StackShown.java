import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The browser player's view of a stack in HyperZebra's language (StackTranslation on the desktop, without its machine
 * translator): the stack's own translations (StackLanguages: Languages/<tag>.po in the stack, also inside its .hzb) for the
 * names buttons show, pop-up choices, the lines of fields and the texts and buttons of answer and ask. Only the drawing
 * changes: a button keeps its name for the scripts, a field holds and saves its own text, the open field (being typed in)
 * and table and outline fields show their own text, a dialog's button answers with its own name.
 */
final class StackShown {
	private StackShown() {}

	/** The stack's translations into HyperZebra's language, or null: none (or an authoring tool chosen: the originals are edited) */
	static Map<String, String> own(OStack st) {
		if (st == null || Host.get().authToolActive()) return null;
		Map<String, String> e = StackLanguages.entries(st);
		return e.isEmpty() ? null : e;
	}
	private static OStack stackOf(OCardBase base, OObject parent) {
		if (base != null && base.stack != null) return base.stack;
		if (parent instanceof OCardBase b && b.stack != null) return b.stack;
		return HZ.stack();
	}

	/** The name a button shows */
	static String name(OButton b, String name) {
		if (b == null || name == null || name.isEmpty()) return name;
		Map<String, String> own = own(stackOf(b.card, b.parent));
		String s = own == null ? null : StackLanguages.lookup(own, name);
		return s == null ? name : s;
	}
	/** A pop-up's choice as shown (its selected value stays the original) */
	static String item(OButton b, String item) { return name(b, item); }

	/** The last text worked out for each field: {text, styles, catalog, TranslatedLines or NOTHING}. Fields are drawn often. */
	private static final Map<OField, Object[]> memo = Collections.synchronizedMap(new WeakHashMap<OField, Object[]>());
	private static final Object NOTHING = new Object();

	/** The field's text as shown, or null when nothing in it is translated (or it shows its own: open, a table, an outline) */
	static TranslatedLines field(OField f, String text) {
		if (f == null || text == null || text.isEmpty() || f.tableStyle || f.outlineStyle || TTalk.openField == f) return null;
		Map<String, String> own = own(stackOf(f.card, f.parent));
		if (own == null) return null;
		Object[] m = memo.get(f);
		if (m != null && m[0].equals(text) && m[1] == f.styleList && m[2] == own) return m[3] == NOTHING ? null : (TranslatedLines) m[3];
		TranslatedLines shown = TranslatedLines.of(line -> StackLanguages.lookup(own, line), text, f.styleList);
		memo.put(f, new Object[]{text, f.styleList, own, shown == null ? NOTHING : shown});
		return shown;
	}

	/** A dialog's prompt as shown (line by line, as a field's text) */
	static String prompt(OStack st, String text) {
		Map<String, String> own = own(st != null ? st : HZ.stack());
		if (own == null || text == null || text.isEmpty()) return text;
		TranslatedLines t = TranslatedLines.of(line -> StackLanguages.lookup(own, line), text, null);
		return t == null ? text : t.text;
	}
	/** A dialog's button names as shown (it answers with its own name) */
	static String[] buttons(OStack st, String[] names) {
		Map<String, String> own = own(st != null ? st : HZ.stack());
		if (own == null) return names;
		String[] out = names.clone();
		for (int i = 0; i < out.length; i++) {
			if (out[i] == null || out[i].isEmpty()) continue;
			String s = StackLanguages.lookup(own, out[i]);
			if (s != null) out[i] = s;
		}
		return out;
	}
	/** (for a list of style runs the layout reads instead of the field's) */
	static ArrayList<styleClass> styles(OField f, TranslatedLines t) { return t != null ? t.styles : f.styleList; }
}

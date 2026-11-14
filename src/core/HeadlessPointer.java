import java.util.ArrayList;

/**
 * click at / drag without a screen (the headless core and the browser; the desktop's PointerUI finds the part among the
 * Swing views). The part under the point is found on the model as HyperCard's layers stack them: the card's parts over the
 * background's, a later part over an earlier one; a shape only where it is drawn. The messages are the desktop's: a
 * disabled button takes the click and sends nothing; an unlocked field opens for typing; a pop-up button gets mouseDown;
 * any other part, or the card, gets mouseDown, mouseStillDown and mouseUp (a button its click hilite between them).
 */
final class HeadlessPointer {
	private HeadlessPointer() {}

	static boolean clickAndDrag(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target,
			MemoryData memData, Result result, String str) throws xTalkException {
		if (str == "click") {
			int next = 1;
			if (next < stringList.size() && stringList.get(next).equalsIgnoreCase("at")) {
				TTalk.resetFindResume();
				next++;
				if (next >= stringList.size()) throw new xTalkException("Missing coordinates for click at");
				int[] stop = new int[1];
				String param = TTalk.evalPointList(stringList, typeAry, next, "with", stop, memData, object, target);
				next = stop[0];
				int[] at = TTalk.commandPoint(param, "Cannot interpret the coordinates for click at", memData, object, target);
				int[] modKeys = TTalk.clickModifierKeys(stringList, typeAry, next, memData, object, target);
				// a paint tool takes the click (HzPaint: the text tool's insertion point, a bucket fill, a dot…)
				if (HzPaint.active()) { HzPaint.click(at[0], at[1], modKeys); PlayerLoop.scriptPainted(); return true; }
				String[] held = hold(modKeys);
				try { click(at[0], at[1]); }
				finally { release(held); }
				return true;
			}
			throw new xTalkException("Cannot understand click");
		}
		if (str == "drag") {
			// drag from p to q [with keys] (PointerUI's on the desktop): the button and field tools make or move parts
			// (UiHost.authToolDrag); no drag of the browse or paint tools without a screen yet
			int next = 1;
			if (next >= stringList.size() || !stringList.get(next).equalsIgnoreCase("from")) throw new xTalkException("Missing from");
			next++;
			if (next >= stringList.size()) throw new xTalkException("Missing coordinates for drag from");
			int[] stop = new int[1];
			String param = TTalk.evalPointList(stringList, typeAry, next, "to", stop, memData, object, target);
			next = stop[0];
			int[] from = TTalk.commandPoint(param, "Cannot interpret the coordinates for drag from", memData, object, target);
			if (next >= stringList.size() || !stringList.get(next).equalsIgnoreCase("to")) throw new xTalkException("Missing to");
			next++;
			if (next >= stringList.size()) throw new xTalkException("Missing coordinates for drag to");
			param = TTalk.evalPointList(stringList, typeAry, next, "with", stop, memData, object, target);
			next = stop[0];
			int[] to = TTalk.commandPoint(param, "Cannot interpret the coordinates for drag to", memData, object, target);
			int[] modKeys = TTalk.clickModifierKeys(stringList, typeAry, next, memData, object, target);
			if (HzPaint.active()) { HzPaint.drag(from[0], from[1], to[0], to[1], modKeys); PlayerLoop.scriptPainted(); return true; }
			self.authToolDrag(from[0], from[1], to[0], to[1], modKeys, memData);
			return true;
		}
		return false;
	}

	/** The part a click at (x, y) of the card goes to, null for the card itself; absorbed[0]: a disabled button took it. */
	static OObject partAt(int x, int y, boolean[] absorbed) {
		OStack stack = HZ.stack();
		if (stack == null || stack.curCard == null) return null;
		OCard cd = stack.curCard;
		for (OCardBase layer : new OCardBase[]{cd, cd.bg}) {
			if (layer == null || layer.partsList == null) continue;
			// the parts and the shapes as they lie (a shape is not in partsList: OCardBase.stacking), the front one first
			java.util.List<OObject> parts = layer.stacking();
			for (int i = parts.size() - 1; i >= 0; i--) {
				OObject p = parts.get(i);
				if (!p.isShown()) continue;
				if (p instanceof OShape) { if (((OShape) p).hitsCard(x, y)) return p; continue; }
				if (!(p instanceof OButton) && !(p instanceof OField)) continue;
				if (x < p.left || y < p.top || x >= p.left + p.width || y >= p.top + p.height) continue;
				if (p instanceof OButton && !((OButton) p).enabled) { absorbed[0] = true; return null; }
				return p;
			}
		}
		return null;
	}

	static void click(int x, int y) throws xTalkException {
		OStack stack = HZ.stack();
		boolean[] absorbed = {false};
		OObject obj = partAt(x, y, absorbed);
		if (absorbed[0]) return;
		if (obj == null && (stack == null || x < 0 || y < 0 || x >= stack.width || y >= stack.height)) return;
		HeadlessProps.set("clickh", Integer.toString(x));
		HeadlessProps.set("clickv", Integer.toString(y));
		HeadlessProps.set("clickloc", x + "," + y);
		HeadlessProps.set("mouseclick", "true");
		TTalk talk = TTalk.talk;
		if (obj == null) {
			TTalk.closeOpenField(true, true);
			talk.ReceiveMessage("mouseDown", "", stack.curCard, null, false);
			talk.ReceiveMessage("mouseStillDown", "", stack.curCard, null, false);
			talk.ReceiveMessage("mouseUp", "", stack.curCard, null, false);
			return;
		}
		if (obj instanceof OField && !((OField) obj).enabled) {
			// an unlocked field: the insertion point goes there, no messages (the field opens), as PointerUI: it is the field the
			// selection functions name (HZ.clickField). Without it typing went nowhere: DiskLibrary's FInfo (set lockText of me
			// to false, click at the clickLoc) lost the selection to its background's idle, which selects the file list then
			OField f = (OField) obj;
			HZ.clickField = f;
			UiHost.get().fieldInsertionPointAt(f, x, y);
			TTalk.openFieldNow(f, true);
			return;
		}
		if (obj instanceof OButton && ((OButton) obj).style == 8) {
			// a pop-up button pulls its menu down; without a screen nothing is chosen, so no mouseUp
			talk.ReceiveMessage("mouseDown", "", obj, null, false);
			return;
		}
		if (!(obj instanceof OField) && !(obj instanceof OButton && !((OButton) obj).getAutoHilite())) TTalk.closeOpenField(true, true);
		talk.ReceiveMessage("mouseDown", "", obj, null, false);
		talk.ReceiveMessage("mouseStillDown", "", obj, null, false);
		if (obj instanceof OButton) TTalk.clickHilite((OButton) obj);
		talk.ReceiveMessage("mouseUp", "", obj, null, false);
	}

	// the modifier keys of "with shiftKey, …" are down while the click goes on (the shiftKey function says so)
	private static final String[] KEY_NAMES = new String[15];
	static { KEY_NAMES[11] = "shiftkey"; KEY_NAMES[12] = "optionkey"; KEY_NAMES[13] = "controlkey"; KEY_NAMES[14] = "commandkey"; }
	private static String[] hold(int[] keys) {
		String[] was = new String[15];
		for (int k : keys) {
			if (k < 0 || k >= KEY_NAMES.length || KEY_NAMES[k] == null) continue;
			was[k] = HeadlessProps.get(KEY_NAMES[k]);
			HeadlessProps.set(KEY_NAMES[k], "down");
			if (k == 14) HeadlessProps.set("cmdkey", "down");
		}
		return was;
	}
	private static void release(String[] was) {
		for (int k = 0; k < was.length; k++) {
			if (was[k] == null) continue;
			HeadlessProps.set(KEY_NAMES[k], was[k]);
			if (k == 14) HeadlessProps.set("cmdkey", was[k]);
		}
	}
}

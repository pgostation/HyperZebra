import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Part editing history. Stores model values, never Swing components or compiled scripts.
 * Changes are applied to the original objects so open inspectors and background data keep
 * their references. Only values changed by the edit are restored. */
final class AuthoringUndo {
    private static final int LIMIT = 100;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>();
    private final ArrayDeque<Edit> redo = new ArrayDeque<>();
    private OCard context;
    private boolean restoring;
    private OObject restoredSelection;

    private static AuthoringUndo current() {
        if (PCARD.pc == null || PCARD.pc.stack == null) return null;
        AuthoringUndo history = PCARD.pc.stack.authoringUndo;
        history.context(PCARD.pc.stack.curCard);
        return history;
    }

    void context(OCard card) {
        if (context != card) {
            undo.clear();
            redo.clear();
            context = card;
            restoredSelection = null;
        }
    }

    static Snapshot capture() {
        AuthoringUndo h = current();
        return h == null || h.restoring || h.context == null ? null : h.snapshot();
    }

    Snapshot snapshot() { return new Snapshot(this, context); }

    static void commit(Snapshot before) {
        if (before == null || before.history != current() || before.history.context != before.card) return;
        before.history.record(before);
        updateMenus();
    }

    void record(Snapshot before) {
        if (restoring || context != before.card) return;
        Snapshot after = snapshot();
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<Key, Object> entry : before.values.entrySet()) {
            Key key = entry.getKey();
            if (after.values.containsKey(key) && !Objects.equals(entry.getValue(), after.values.get(key)))
                changes.add(new Change(key, entry.getValue(), after.values.get(key)));
        }
        if (changes.isEmpty()) return; // Clicking, cancelled dialogs and boundary moves are not edits.
        undo.push(new Edit(changes, before.selection, after.selection));
        while (undo.size() > LIMIT) undo.removeLast();
        redo.clear();
        markChanged(changes);
    }

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    boolean restore(boolean forward) {
        ArrayDeque<Edit> from = forward ? redo : undo;
        if (from.isEmpty()) return false;
        Edit edit = from.peek();
        restoring = true;
        try {
            for (Change c : edit.changes) c.key.set(forward ? c.after : c.before);
            markChanged(edit.changes);
            restoredSelection = forward ? edit.afterSelection : edit.beforeSelection;
            from.pop();
            (forward ? undo : redo).push(edit);
        } finally { restoring = false; }
        return true;
    }

    private static void markChanged(List<Change> changes) {
        for (Change c : changes) {
            Object target = c.key.target;
            if (target instanceof OCardBase) ((OCardBase) target).changed = true;
            else if (target instanceof OObject && ((OObject) target).parent instanceof OCardBase)
                ((OCardBase) ((OObject) target).parent).changed = true;
            else if (target instanceof OBgFieldData) ((OBgFieldData) target).card.changed = true;
            else if (target instanceof OBgButtonData) ((OBgButtonData) target).card.changed = true;
        }
    }

    static void perform(boolean forward) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            try { javax.swing.SwingUtilities.invokeAndWait(() -> perform(forward)); }
            catch (Exception e) { throw new IllegalStateException(e); }
            return;
        }
        AuthoringUndo h = current();
        if (h == null || ButtonGUI.gui.isMouseDown || FieldGUI.gui.isMouseDown
            || (AuthTool.tool instanceof ShapeTool tool && tool.dragging())) return;
        if (h.restore(forward)) {
            // Rebuild widgets and reconnect selection to the surviving model objects.
            OButton button = h.restoredSelection instanceof OButton ? (OButton) h.restoredSelection : ButtonGUI.gui.tgtOBtn;
            OField field = h.restoredSelection instanceof OField ? (OField) h.restoredSelection : FieldGUI.gui.tgtOFld;
            OCard.reloadCurrentCard();
            ButtonGUI.gui.tgtOBtn = attached(button) ? button : null;
            ButtonGUI.gui.target = attached(button) ? button.getComponent() : null;
            FieldGUI.gui.tgtOFld = attached(field) ? field : null;
            FieldGUI.gui.target = attached(field) ? field.getComponent() : null;
			if(h.restoredSelection instanceof OShape && attached(h.restoredSelection))ShapeEditor.selected=(OShape)h.restoredSelection;
			else if(!attached(ShapeEditor.selected))ShapeEditor.selected=null;
			ShapeTool.refresh();
            if (AuthDialog.authDialog != null) {
                OObject object = AuthDialog.object;
                if (attached(object)) AuthDialog.openAuthDialog(PCARD.pc, object.objectType, object);
                else { AuthDialog.authDialog.dispose(); AuthDialog.authDialog = null; }
            }
            PCARD.pc.mainPane.repaint();
        }
        updateMenus();
    }

    private static boolean attached(OObject object) {
        return object != null && object.parent instanceof OCardBase
            && (((OCardBase) object.parent).partsList.contains(object)
				|| (object instanceof OShape && ((OCardBase)object.parent).shapeList.contains(object))
				|| (object instanceof OPanel && ((OCardBase)object.parent).panelList.contains(object)));
    }

    static void updateMenus() {
        AuthoringUndo h = current();
        if (PCARD.pc == null || PCARD.pc.getJMenuBar() == null) return;
        GMenu.changeEnabled("Edit", "Undo", h != null && h.canUndo());
        GMenu.changeEnabled("Edit", "Redo", h != null && h.canRedo());
        if (PCARD.pc.menu != null) {
            javax.swing.JMenuItem undoItem = GMenu.searchMenuItem(PCARD.pc.menu.mb, "Edit", "Undo");
            javax.swing.JMenuItem redoItem = GMenu.searchMenuItem(PCARD.pc.menu.mb, "Edit", "Redo");
            if (undoItem != null) undoItem.setEnabled(h != null && h.canUndo());
            if (redoItem != null) redoItem.setEnabled(h != null && h.canRedo());
        }
    }

    static boolean editKeyDown() {
        return GUI.key[20] > 0 || GUI.key[21] > 0 || GUI.key[0] > 0 || GUI.key[1] > 0
            || GUI.key[2] > 0 || GUI.key[3] > 0
            || (GUI.key[14] > 0 && (GUI.key['+'] > 0 || GUI.key['-'] > 0));
    }

    static boolean editsParts(String command) {
        return Arrays.asList("new button", "new field", "new panel", "delete button", "delete field",
            "cut button", "cut field", "paste button", "paste field", "bring closer", "send farther")
            .contains(command.toLowerCase(java.util.Locale.ROOT));
    }

    static final class Snapshot {
        final AuthoringUndo history;
        final OCard card;
        final OObject selection;
        final Map<Key, Object> values = new LinkedHashMap<>();
        Snapshot(AuthoringUndo history, OCard card) {
            this.history = history;
            this.card = card;
            selection = ShapeTool.active() ? ShapeEditor.selected : AuthTool.tool instanceof ButtonTool ? ButtonGUI.gui.tgtOBtn
                : AuthTool.tool instanceof FieldTool ? FieldGUI.gui.tgtOFld : null;
            if (card == null) return;
            layer(card);
            if (card.bg != null) layer(card.bg);
            List<OCard> cards = new ArrayList<>(card.stack.cdCacheList);
            if (!cards.contains(card)) cards.add(card);
            for (OCard cd : cards) {
                if (cd.bgid != card.bgid) continue;
                fields(cd, OCard.class, "bgbtnList bgfldList");
                for (OBgButtonData b : cd.bgbtnList) fields(b, OBgButtonData.class, "check_hilite");
                for (OBgFieldData f : cd.bgfldList) fields(f, OBgFieldData.class, "text styleList");
            }
        }
        private void layer(OCardBase layer) {
            layer.normalizeLayers();
            fields(layer, OCardBase.class, "partsList btnList fldList shapeList layeredParts panelList");
            for (OObject part : layer.partsList) {
                fields(part, OObject.class, "name panelId text visible enabled left top width height scriptList checkpoints");
                if (part instanceof OButton)
                    fields(part, OButton.class, "color bgColor hiliteColor group check_hilite autoHilite hilite icon iconURI sharedHilite showName style textAlign textFont textHeight textSize textStyle titleWidth selectedLine scaleIcon iconAspect blendMode blendLevel");
                else if (part instanceof OField)
                    fields(part, OField.class, "autoTab autoSelect dontSearch dontWrap color bgColor fixedLineHeight scroll sharedText showLines style textAlign textFont textHeight textSize textStyle wideMargins selectedLine selectedStart selectedEnd multipleLines hilite styleList");
            }
            for (OPanel panel : layer.panelList) {
                fields(panel, OObject.class, "name text visible scriptList checkpoints");
                fields(panel, OPanel.class, "values");
            }
            if(layer.shapeList!=null) for(OShape shape:layer.shapeList){
                fields(shape,OObject.class,"name panelId visible left top width height scriptList checkpoints");
                fields(shape,OShape.class,"layer opacity rotation kind pathData text fontFamily fontSize bold sides fillColor strokeColor strokeWidth gradientStart gradientEnd radialGradient gradientX1 gradientY1 gradientX2 gradientY2 texturePng stretchTexture outlines shadows svgFilter");
            }
        }
        private void fields(Object target, Class<?> type, String names) {
            for (String name : names.split(" ")) {
                Key key = new Key(target, type, name);
                values.put(key, key.get());
            }
        }
    }

    // The field list above is explicit: adding a runtime cache or widget to a model can
    // never accidentally make it part of history. Mutable collections are copied twice,
    // on capture and on restore, so later edits cannot mutate an older snapshot.
    private static Object copy(Object value) {
        if (value instanceof ArrayList<?>) {
            ArrayList<Object> result = new ArrayList<>();
            for (Object item : (ArrayList<?>) value) result.add(copy(item));
            return result;
        }
        if (value instanceof java.util.TreeSet<?>) return new java.util.TreeSet<>((java.util.TreeSet<?>) value);
        if (value instanceof byte[]) return new BinaryValue(Base64.getEncoder().encodeToString((byte[])value));
        if (value instanceof OShape.Outline o) return new OShape.Outline(o.width,o.color);
        if (value instanceof java.util.LinkedHashMap<?,?> m) return new java.util.LinkedHashMap<>(m);
        if (value instanceof OShape.Shadow s) return new OShape.Shadow(s.dx,s.dy,s.blur,s.spread,s.color);
        if (value instanceof styleClass) {
            styleClass s = (styleClass) value;
            return new StyleValue(s.textPosition, s.styleId);
        }
        return value;
    }
    private static Object live(Object value) {
        if (value instanceof List<?>) {
            ArrayList<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(live(item));
            return result;
        }
        if (value instanceof StyleValue) {
            StyleValue s = (StyleValue) value;
            return new styleClass(s.position, s.id);
        }
        if (value instanceof BinaryValue b) return Base64.getDecoder().decode(b.data);
        return copy(value);
    }
    private record StyleValue(int position, int id) {}
    private record BinaryValue(String data) {}
    private static final class Key {
        final Object target;
        final Field field;
        Key(Object target, Class<?> type, String name) {
            this.target = target;
            try { field = type.getDeclaredField(name); field.setAccessible(true); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        Object get() {
            try { return copy(field.get(target)); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        void set(Object value) {
            try {
				if(target instanceof OShape && (field.getName().equals("outlines")||field.getName().equals("shadows"))){
					@SuppressWarnings("unchecked") List<Object> current=(List<Object>)field.get(target);
					current.clear();current.addAll((List<?>)live(value));return;
				}
                field.set(target, live(value));
                if (field.getName().equals("scriptList")) {
                    OObject part = (OObject) target;
                    part.handlerList = null; part.handlerLineList = null; part.jit = null; TalkJit.scriptEpoch++;
                    part.stringList = null; part.typeList = null;
                }
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).target == target && ((Key) o).field.equals(field);
        }
        public int hashCode() { return System.identityHashCode(target) * 31 + field.hashCode(); }
    }
    private record Change(Key key, Object before, Object after) {}
    private record Edit(List<Change> changes, OObject beforeSelection, OObject afterSelection) {}
}

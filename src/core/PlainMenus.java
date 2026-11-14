import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The menus of the headless core and the browser: plain objects that do what the Swing menus do for the core's menu code
 * (the items and separators of a menu in order, text, enabled, check mark, Command key, client properties). The card
 * window's menu bar is made from the standard menu table ({@link StandardMenus}, written from the desktop's real menus).
 */
final class PlainMenus extends MenuNodes {
	private Bar card, paint;

	@Override MMenu menu(String text) { return new PMenu(text); }
	@Override MMenuItem item(String text) { return new PItem(text); }
	@Override MCheckBoxMenuItem checkItem(String text, boolean selected) { PCheck c = new PCheck(text); c.selected = selected; return c; }
	@Override MMenuBar cardMenuBar() { if (card == null) card = StandardMenus.bar(this, false); return card; }
	@Override MMenuBar paintMenuBar() { if (paint == null) paint = StandardMenus.bar(this, true); return paint; }
	@Override void menuBarChanged() {}
	@Override MKeyStroke menuKey(char c) { return new MKeyStroke(Character.toUpperCase(c), null); }
	/** Back to the standard menus (a new run). */
	void reset() { card = null; paint = null; }

	private static final class Props {
		Map<Object, Object> map;
		Object get(Object k) { return map == null ? null : map.get(k); }
		void put(Object k, Object v) { if (v == null) { if (map != null) map.remove(k); } else { if (map == null) map = new HashMap<>(); map.put(k, v); } }
	}

	static class PItem extends MMenuItem {
		final Props props = new Props();
		String text;
		boolean enabled = true, visible = true;
		MKeyStroke key;
		final List<Runnable> actions = new ArrayList<>();
		int style;
		PItem(String text) { this.text = text; }
		@Override Object getClientProperty(Object key) { return props.get(key); }
		@Override void putClientProperty(Object key, Object value) { props.put(key, value); }
		@Override boolean isVisible() { return visible; }
		@Override void setVisible(boolean v) { visible = v; }
		@Override String getText() { return text; }
		@Override void setText(String t) { text = t; }
		@Override boolean isEnabled() { return enabled; }
		@Override void setEnabled(boolean e) { enabled = e; }
		@Override MKeyStroke getAccelerator() { return key; }
		@Override void setAccelerator(MKeyStroke k) { key = k; }
		@Override void addAction(Runnable a) { actions.add(a); }
		@Override void copyActionsFrom(MMenuItem other) { if (other instanceof PItem) actions.addAll(((PItem) other).actions); }
		@Override void copyFontFrom(MMenuItem other) { if (other instanceof PItem) style = ((PItem) other).style; }
		@Override void applyTextStyle(int s) { style = s; }
		@Override void doClick() { if (enabled) for (Runnable a : new ArrayList<>(actions)) a.run(); }
	}

	static final class PCheck extends MCheckBoxMenuItem {
		final PItem base;
		boolean selected;
		PCheck(String text) { base = new PItem(text); }
		@Override Object getClientProperty(Object key) { return base.getClientProperty(key); }
		@Override void putClientProperty(Object key, Object value) { base.putClientProperty(key, value); }
		@Override boolean isVisible() { return base.isVisible(); }
		@Override void setVisible(boolean v) { base.setVisible(v); }
		@Override String getText() { return base.getText(); }
		@Override void setText(String t) { base.setText(t); }
		@Override boolean isEnabled() { return base.isEnabled(); }
		@Override void setEnabled(boolean e) { base.setEnabled(e); }
		@Override MKeyStroke getAccelerator() { return base.getAccelerator(); }
		@Override void setAccelerator(MKeyStroke k) { base.setAccelerator(k); }
		@Override void addAction(Runnable a) { base.addAction(a); }
		@Override void copyActionsFrom(MMenuItem other) { base.copyActionsFrom(other instanceof PCheck ? ((PCheck) other).base : other); }
		@Override void copyFontFrom(MMenuItem other) { base.copyFontFrom(other instanceof PCheck ? ((PCheck) other).base : other); }
		@Override void applyTextStyle(int s) { base.applyTextStyle(s); }
		@Override void doClick() { if (base.enabled) { selected = !selected; base.doClick(); } }
		@Override boolean isSelected() { return selected; }
		@Override void setSelected(boolean s) { selected = s; }
	}

	static final class PSeparator extends MSeparator {
		final Props props = new Props();
		boolean visible = true;
		@Override Object getClientProperty(Object key) { return props.get(key); }
		@Override void putClientProperty(Object key, Object value) { props.put(key, value); }
		@Override boolean isVisible() { return visible; }
		@Override void setVisible(boolean v) { visible = v; }
	}

	static final class PMenu extends MMenu {
		final PItem base;
		final List<MComponent> parts = new ArrayList<>();
		/** False for a menu the desktop makes of its own class (the table's x). */
		boolean plain = true;
		PMenu(String text) { base = new PItem(text); }
		@Override Object getClientProperty(Object key) { return base.getClientProperty(key); }
		@Override void putClientProperty(Object key, Object value) { base.putClientProperty(key, value); }
		@Override boolean isVisible() { return base.isVisible(); }
		@Override void setVisible(boolean v) { base.setVisible(v); }
		@Override String getText() { return base.getText(); }
		@Override void setText(String t) { base.setText(t); }
		@Override boolean isEnabled() { return base.isEnabled(); }
		@Override void setEnabled(boolean e) { base.setEnabled(e); }
		@Override MKeyStroke getAccelerator() { return base.getAccelerator(); }
		@Override void setAccelerator(MKeyStroke k) { base.setAccelerator(k); }
		@Override void addAction(Runnable a) { base.addAction(a); }
		@Override void copyActionsFrom(MMenuItem other) { base.copyActionsFrom(other); }
		@Override void copyFontFrom(MMenuItem other) { base.copyFontFrom(other); }
		@Override void applyTextStyle(int s) { base.applyTextStyle(s); }
		@Override void doClick() {}
		@Override MPopupMenu getPopupMenu() {
			return new MPopupMenu() {
				@Override int getComponentCount() { return parts.size(); }
				@Override MComponent getComponent(int index) { return parts.get(index); }
				@Override void remove(int index) { parts.remove(index); }
			};
		}
		@Override MMenuItem add(MMenuItem item) { parts.remove(item); parts.add(item); return item; }
		@Override void addSeparator() { parts.add(new PSeparator()); }
		@Override void insert(MMenuItem item, int index) { parts.remove(item); parts.add(Math.max(0, Math.min(index, parts.size())), item); }
		@Override void insertSeparator(int index) { parts.add(Math.max(0, Math.min(index, parts.size())), new PSeparator()); }
		@Override void remove(int index) { parts.remove(index); }
		@Override void remove(MComponent part) { parts.remove(part); }
		@Override void removeAll() { parts.clear(); }
		@Override boolean isPlainMenu() { return plain; }
	}

	static final class Bar extends MMenuBar {
		final List<MComponent> parts = new ArrayList<>();
		boolean visible = true;
		@Override int getComponentCount() { return parts.size(); }
		@Override MComponent getComponent(int index) { return parts.get(index); }
		@Override void add(MMenu menu) { parts.remove(menu); parts.add(menu); }
		@Override void remove(MComponent part) { parts.remove(part); }
		@Override boolean isVisible() { return visible; }
	}
}

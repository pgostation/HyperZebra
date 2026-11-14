import java.awt.Component;
import java.awt.event.ActionListener;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;

/**
 * The desktop's {@link MenuNodes}: the card window's real Swing menus, wrapped. A component has one wrapper (kept in its
 * client property "hz.mnode"), so the core's == on menus means what it did on the JMenus; each call is the Swing call the
 * core's menu code made itself before.
 */
final class SwingMenus extends MenuNodes {
	private static final String NODE = "hz.mnode";

	@Override MMenu menu(String text) { return wrap(new JMenu(text)); }
	@Override MMenuItem item(String text) { return wrap(new JMenuItem(text)); }
	@Override MCheckBoxMenuItem checkItem(String text, boolean selected) { return (MCheckBoxMenuItem) wrapComponent(new JCheckBoxMenuItem(text, selected)); }
	@Override MMenuBar cardMenuBar() { return PCARD.pc != null && PCARD.pc.menu != null && PCARD.pc.menu.mb != null ? new Bar(PCARD.pc.menu.mb) : null; }
	@Override MMenuBar paintMenuBar() { return PCARD.pc != null && PCARD.pc.paintMenu != null && PCARD.pc.paintMenu.mb != null ? new Bar(PCARD.pc.paintMenu.mb) : null; }
	@Override void menuBarChanged() { if (PCARD.pc != null && PCARD.pc.menu != null) GMenu.menuUpdate(PCARD.pc.menu.mb); }
	@Override MKeyStroke menuKey(char c) {
		int code = Character.toUpperCase(c);
		return new MKeyStroke(code, KeyStroke.getKeyStroke(code, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
	}

	// ---- wrapping ----
	static MMenu wrap(JMenu m) { return m == null ? null : (MMenu) wrapComponent(m); }
	static MMenuItem wrap(JMenuItem m) { return m == null ? null : (MMenuItem) wrapComponent(m); }
	static synchronized MComponent wrapComponent(Component c) { //(one wrapper per component, also when two threads ask at once)
		if (c == null) return null;
		if (c instanceof JComponent) {
			Object w = ((JComponent) c).getClientProperty(NODE);
			if (w instanceof MComponent) return (MComponent) w;
		}
		MComponent w = c instanceof JMenu ? new Menu((JMenu) c) : c instanceof JCheckBoxMenuItem ? new Check((JCheckBoxMenuItem) c)
			: c instanceof JMenuItem ? new Item((JMenuItem) c) : c instanceof JSeparator ? new Separator((JComponent) c) : new Other(c);
		if (c instanceof JComponent) ((JComponent) c).putClientProperty(NODE, w);
		return w;
	}
	static Component unwrap(MComponent w) {
		if (w == null) return null;
		if (w instanceof Menu) return ((Menu) w).m;
		if (w instanceof Check) return ((Check) w).m;
		if (w instanceof Item) return ((Item) w).m;
		if (w instanceof Separator) return ((Separator) w).c;
		return ((Other) w).c;
	}
	static JMenu unwrap(MMenu w) { return w == null ? null : ((Menu) w).m; }
	static JMenuItem unwrap(MMenuItem w) { return (JMenuItem) unwrap((MComponent) w); }
	static java.util.ArrayList<JMenu> unwrapAll(java.util.List<MMenu> menus) {
		java.util.ArrayList<JMenu> out = new java.util.ArrayList<JMenu>();
		for (MMenu m : menus) out.add(unwrap(m));
		return out;
	}
	static MMenuBar wrap(JMenuBar mb) { return mb == null ? null : new Bar(mb); }

	private static Object property(Component c, Object key) { return c instanceof JComponent ? ((JComponent) c).getClientProperty(key) : null; }
	private static void property(Component c, Object key, Object value) { if (c instanceof JComponent) ((JComponent) c).putClientProperty(key, value); }

	// ---- the menu items' shared calls ----
	private static MKeyStroke accelerator(JMenuItem m) { KeyStroke k = m.getAccelerator(); return k == null ? null : new MKeyStroke(k.getKeyCode(), k); }
	private static void accelerator(JMenuItem m, MKeyStroke k) { m.setAccelerator(k == null ? null : (KeyStroke) k.nativeStroke); }
	private static void copyActions(JMenuItem to, MMenuItem from) { for (ActionListener l : unwrap(from).getActionListeners()) to.addActionListener(l); }
	/** The item's font for a textStyle: bold/italic/underline show, outline/shadow/condense/extend are only kept (was TUtil.applyMenuItemStyle). */
	static void applyTextStyle(JMenuItem mi, int style) {
		java.awt.Font base = javax.swing.UIManager.getFont("MenuItem.font");
		if (base == null) base = mi.getFont();
		if (base == null) return;
		int awt = ((style & 1) != 0 ? java.awt.Font.BOLD : 0) | ((style & 2) != 0 ? java.awt.Font.ITALIC : 0);
		java.awt.Font f = base.deriveFont(awt);
		if ((style & 4) != 0) {
			java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
			attrs.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
			f = f.deriveFont(attrs);
		}
		mi.setFont(f);
	}

	static final class Item extends MMenuItem {
		final JMenuItem m;
		Item(JMenuItem m) { this.m = m; }
		@Override Object getClientProperty(Object key) { return m.getClientProperty(key); }
		@Override void putClientProperty(Object key, Object value) { m.putClientProperty(key, value); }
		@Override boolean isVisible() { return m.isVisible(); }
		@Override void setVisible(boolean v) { m.setVisible(v); }
		@Override String getText() { return m.getText(); }
		@Override void setText(String t) { m.setText(t); }
		@Override boolean isEnabled() { return m.isEnabled(); }
		@Override void setEnabled(boolean e) { m.setEnabled(e); }
		@Override MKeyStroke getAccelerator() { return accelerator(m); }
		@Override void setAccelerator(MKeyStroke k) { accelerator(m, k); }
		@Override void addAction(Runnable a) { m.addActionListener(e -> a.run()); }
		@Override void copyActionsFrom(MMenuItem other) { copyActions(m, other); }
		@Override void copyFontFrom(MMenuItem other) { m.setFont(unwrap(other).getFont()); }
		@Override void applyTextStyle(int style) { SwingMenus.applyTextStyle(m, style); }
		@Override void doClick() { m.doClick(); }
	}

	static final class Check extends MCheckBoxMenuItem {
		final JCheckBoxMenuItem m;
		Check(JCheckBoxMenuItem m) { this.m = m; }
		@Override Object getClientProperty(Object key) { return m.getClientProperty(key); }
		@Override void putClientProperty(Object key, Object value) { m.putClientProperty(key, value); }
		@Override boolean isVisible() { return m.isVisible(); }
		@Override void setVisible(boolean v) { m.setVisible(v); }
		@Override String getText() { return m.getText(); }
		@Override void setText(String t) { m.setText(t); }
		@Override boolean isEnabled() { return m.isEnabled(); }
		@Override void setEnabled(boolean e) { m.setEnabled(e); }
		@Override MKeyStroke getAccelerator() { return accelerator(m); }
		@Override void setAccelerator(MKeyStroke k) { accelerator(m, k); }
		@Override void addAction(Runnable a) { m.addActionListener(e -> a.run()); }
		@Override void copyActionsFrom(MMenuItem other) { copyActions(m, other); }
		@Override void copyFontFrom(MMenuItem other) { m.setFont(unwrap(other).getFont()); }
		@Override void applyTextStyle(int style) { SwingMenus.applyTextStyle(m, style); }
		@Override void doClick() { m.doClick(); }
		@Override boolean isSelected() { return m.isSelected(); }
		@Override void setSelected(boolean s) { m.setSelected(s); }
	}

	static final class Menu extends MMenu {
		final JMenu m;
		Menu(JMenu m) { this.m = m; }
		@Override Object getClientProperty(Object key) { return m.getClientProperty(key); }
		@Override void putClientProperty(Object key, Object value) { m.putClientProperty(key, value); }
		@Override boolean isVisible() { return m.isVisible(); }
		@Override void setVisible(boolean v) { m.setVisible(v); }
		@Override String getText() { return m.getText(); }
		@Override void setText(String t) { m.setText(t); }
		@Override boolean isEnabled() { return m.isEnabled(); }
		@Override void setEnabled(boolean e) { m.setEnabled(e); }
		@Override MKeyStroke getAccelerator() { return accelerator(m); }
		@Override void setAccelerator(MKeyStroke k) { accelerator(m, k); }
		@Override void addAction(Runnable a) { m.addActionListener(e -> a.run()); }
		@Override void copyActionsFrom(MMenuItem other) { copyActions(m, other); }
		@Override void copyFontFrom(MMenuItem other) { m.setFont(unwrap(other).getFont()); }
		@Override void applyTextStyle(int style) { SwingMenus.applyTextStyle(m, style); }
		@Override void doClick() { m.doClick(); }
		@Override MPopupMenu getPopupMenu() { return new Popup(m.getPopupMenu()); }
		@Override MMenuItem add(MMenuItem item) { m.add(unwrap(item)); return item; }
		@Override void addSeparator() { m.addSeparator(); }
		@Override void insert(MMenuItem item, int index) { m.insert(unwrap(item), index); }
		@Override void insertSeparator(int index) { m.insertSeparator(index); }
		@Override void remove(int index) { m.remove(index); }
		@Override void remove(MComponent part) { m.remove(unwrap(part)); }
		@Override void removeAll() { m.removeAll(); }
		@Override boolean isPlainMenu() { return m.getClass() == JMenu.class; }
	}

	static final class Separator extends MSeparator {
		final JComponent c;
		Separator(JComponent c) { this.c = c; }
		@Override Object getClientProperty(Object key) { return c.getClientProperty(key); }
		@Override void putClientProperty(Object key, Object value) { c.putClientProperty(key, value); }
		@Override boolean isVisible() { return c.isVisible(); }
		@Override void setVisible(boolean v) { c.setVisible(v); }
	}

	static final class Other extends MOtherComponent {
		final Component c;
		Other(Component c) { this.c = c; }
		@Override Object getClientProperty(Object key) { return property(c, key); }
		@Override void putClientProperty(Object key, Object value) { property(c, key, value); }
		@Override boolean isVisible() { return c.isVisible(); }
		@Override void setVisible(boolean v) { c.setVisible(v); }
	}

	static final class Popup extends MPopupMenu {
		final JPopupMenu p;
		Popup(JPopupMenu p) { this.p = p; }
		@Override int getComponentCount() { return p.getComponentCount(); }
		@Override MComponent getComponent(int index) { return wrapComponent(p.getComponent(index)); }
		@Override void remove(int index) { p.remove(index); }
	}

	static final class Bar extends MMenuBar {
		final JMenuBar mb;
		Bar(JMenuBar mb) { this.mb = mb; }
		@Override int getComponentCount() { return mb.getComponentCount(); }
		@Override MComponent getComponent(int index) { return wrapComponent(mb.getComponent(index)); }
		@Override void add(MMenu menu) { mb.add(unwrap(menu)); }
		@Override void remove(MComponent part) { mb.remove(unwrap(part)); }
		@Override boolean isVisible() { return mb.isVisible(); }
	}
}

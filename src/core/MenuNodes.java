import java.util.ArrayList;
import java.util.List;

/**
 * The menus as the HyperTalk core sees them (P1 S6): the parts of Swing's menus the core's menu code uses, under the same
 * names (MMenu for JMenu, MMenuItem for JMenuItem, …), so that code reads as it did. The desktop's {@code SwingMenus} (src/ui)
 * wraps the card window's real menus (the same wrapper for the same component every time, so == still compares menus);
 * the headless core and the browser use {@link PlainMenus}, menus of their own built from the standard menu table.
 *
 * Found by name like {@link Host}: a build of the core alone never pulls in Swing.
 */
abstract class MenuNodes {
	private static volatile MenuNodes current;

	static MenuNodes get() {
		MenuNodes m = current;
		if (m == null) current = m = byName(System.getProperty("hz.menus", "SwingMenus"));
		return m;
	}
	static void set(MenuNodes m) { current = m; }

	private static MenuNodes byName(String name) {
		try { return (MenuNodes) Class.forName(name).getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return new PlainMenus(); }
	}

	abstract MMenu menu(String text);
	abstract MMenuItem item(String text);
	abstract MCheckBoxMenuItem checkItem(String text, boolean selected);
	/** The card window's menu bar (PCARD.pc.menu.mb), or null when there is none. */
	abstract MMenuBar cardMenuBar();
	/** The menu bar of the paint tools (PCARD.pc.paintMenu.mb), or null. */
	abstract MMenuBar paintMenuBar();
	/** The menus changed: the shown menu bar follows (GMenu.menuUpdate on the desktop). */
	abstract void menuBarChanged();
	/** A Command-key shortcut for a letter (Character.toUpperCase of it as the key code, the platform's menu modifier). */
	abstract MKeyStroke menuKey(char c);
}

/** A part of a menu (JComponent). */
abstract class MComponent {
	abstract Object getClientProperty(Object key);
	abstract void putClientProperty(Object key, Object value);
	abstract boolean isVisible();
	abstract void setVisible(boolean visible);
}

/** A separator line in a menu (JSeparator). */
abstract class MSeparator extends MComponent {}

/** A part of a menu that is neither an item nor a separator. */
abstract class MOtherComponent extends MComponent {}

/** A Command-key shortcut (KeyStroke); native is the platform's own object when it came from there. */
final class MKeyStroke {
	final int keyCode;
	final Object nativeStroke;
	MKeyStroke(int keyCode, Object nativeStroke) { this.keyCode = keyCode; this.nativeStroke = nativeStroke; }
	int getKeyCode() { return keyCode; }
}

/** A menu item (JMenuItem). */
abstract class MMenuItem extends MComponent {
	static MMenuItem create(String text) { return MenuNodes.get().item(text); }
	abstract String getText();
	abstract void setText(String text);
	abstract boolean isEnabled();
	abstract void setEnabled(boolean enabled);
	abstract MKeyStroke getAccelerator();
	abstract void setAccelerator(MKeyStroke key);
	/** What choosing the item does (an ActionListener on the desktop). */
	abstract void addAction(Runnable action);
	/** The other item's actions (its ActionListeners) on this one too. */
	abstract void copyActionsFrom(MMenuItem other);
	/** The other item's font. */
	abstract void copyFontFrom(MMenuItem other);
	/** Shows a QuickDraw text style (bold, italic, underline; the others are only kept). */
	abstract void applyTextStyle(int style);
	/** Chooses the item (doClick). */
	abstract void doClick();
}

/** A menu item with a check mark (JCheckBoxMenuItem). */
abstract class MCheckBoxMenuItem extends MMenuItem {
	static MCheckBoxMenuItem create(String text) { return MenuNodes.get().checkItem(text, false); }
	static MCheckBoxMenuItem create(String text, boolean selected) { return MenuNodes.get().checkItem(text, selected); }
	abstract boolean isSelected();
	abstract void setSelected(boolean selected);
}

/** A menu (JMenu). */
abstract class MMenu extends MMenuItem {
	static MMenu create(String text) { return MenuNodes.get().menu(text); }
	abstract MPopupMenu getPopupMenu();
	abstract MMenuItem add(MMenuItem item);
	abstract void addSeparator();
	abstract void insert(MMenuItem item, int index);
	abstract void insertSeparator(int index);
	abstract void remove(int index);
	abstract void remove(MComponent part);
	abstract void removeAll();
	/** A menu of the menu class itself (m.getClass()==JMenu.class), not one of the desktop's own kinds. */
	abstract boolean isPlainMenu();
}

/** A menu's list of parts (JPopupMenu). */
abstract class MPopupMenu {
	abstract int getComponentCount();
	abstract MComponent getComponent(int index);
	abstract void remove(int index);
	MComponent[] getComponents() {
		MComponent[] out = new MComponent[getComponentCount()];
		for (int i = 0; i < out.length; i++) out[i] = getComponent(i);
		return out;
	}
}

/** A menu bar (JMenuBar). */
abstract class MMenuBar {
	abstract int getComponentCount();
	abstract MComponent getComponent(int index);
	abstract void add(MMenu menu);
	abstract void remove(MComponent part);
	abstract boolean isVisible();
	List<MMenu> menus() {
		List<MMenu> out = new ArrayList<>();
		for (int i = 0; i < getComponentCount(); i++) if (getComponent(i) instanceof MMenu) out.add((MMenu) getComponent(i));
		return out;
	}
}

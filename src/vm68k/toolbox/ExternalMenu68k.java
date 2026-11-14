/** One of HyperCard's own menus as an external's Menu Manager is given it (see MenuManager68k.syncHyperCardMenus; P4: out of ExternalMenusUI). */
final class ExternalMenu68k {
	/** The host menu (a JMenu on the desktop). */
	final Object menu; final String title; final int id; final boolean enabled;
	final java.util.List<String> items = new java.util.ArrayList<String>(); //"-" for a separator
	/** The menu's components the items stand for (a separator's too), in the same order. */
	final java.util.List<Object> components = new java.util.ArrayList<Object>();
	final java.util.List<Boolean> enabledItems = new java.util.ArrayList<Boolean>(), checkedItems = new java.util.ArrayList<Boolean>();
	/** Each item's Command key (0 none), textStyle (QuickDraw style bits) and markChar when it is not the check mark (else null). */
	final java.util.List<Character> commands = new java.util.ArrayList<Character>();
	final java.util.List<Integer> styles = new java.util.ArrayList<Integer>();
	final java.util.List<String> marks = new java.util.ArrayList<String>();
	/** The ID of the submenu an external hung on the item (see externalHierarchicalHyperCardItem), else 0. */
	final java.util.List<Integer> submenus = new java.util.ArrayList<Integer>();
	ExternalMenu68k(Object menu, String title, int id, boolean enabled){ this.menu=menu; this.title=title; this.id=id; this.enabled=enabled; }
	void add(String text, Object component, boolean enabled, boolean checked, char command, int style, String mark){
		items.add(text); components.add(component); enabledItems.add(enabled); checkedItems.add(checked); commands.add(command); styles.add(style); marks.add(mark); submenus.add(0);
	}
}

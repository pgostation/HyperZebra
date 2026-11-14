import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.*;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

/**
 * The menus a 68K external (XCMD/XFCN) puts into HyperCard's menu bar, and HyperCard's menus as an external sees them: desktop
 * code (the 68K VM's MenuManager68k calls it), moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt, by hand).
 */
final class ExternalMenusUI {
	private ExternalMenusUI(){}

	// from TTalk
	/**
	 * The menus an external (XCMD/XFCN) put into the menu bar with InsertMenu, as menus of HyperCard's menu bar: scripts find
	 * them by name and give their items menu messages, and choosing an item sends doMenu like any script-made menu.
	 * A menu that leaves the external's menu bar (DeleteMenu) leaves this bar; one already there keeps what scripts set.
	 */
	static void syncExternalMenus(Object owner, java.util.List<MenuModel68k> models){
		if(PCARD.pc==null || PCARD.pc.menu==null || PCARD.pc.menu.mb==null) return;
		Host.get().runOnEDT(() -> {
			javax.swing.JMenuBar mb = PCARD.pc.menu.mb;
			java.util.Set<Integer> live = new java.util.HashSet<Integer>();
			boolean changed = false;
			for(MenuModel68k model : models){
				live.add(model.id);
				if(model.apple || model.title==null || model.title.isEmpty()) continue;
				JMenu present = null;
				for(int i=0; i<mb.getMenuCount() && present==null; i++){
					JMenu jm = mb.getMenu(i);
					if(jm!=null && jm.getClientProperty("hz.externalMenu")==owner && Integer.valueOf(model.id).equals(jm.getClientProperty("hz.externalMenuId"))) present = jm;
				}
				//already in the bar: it takes what the external did to it since (MenuHandler XCMD's Mark, Enable, Disable)
				if(present!=null){ if(updateExternalMenu(present, model)) changed = true; continue; }
				if(SwingMenus.unwrap(TTalk.findMenu(model.title))!=null) continue;
				final JMenu menu = new JMenu(model.title);
				menu.putClientProperty("hz.scriptMenu", Boolean.TRUE);
				menu.putClientProperty("hz.externalMenu", owner);
				menu.putClientProperty("hz.externalMenuId", model.id);
				menu.setEnabled(model.enabled);
				//a click in the menu bar: the external's windows that registered menus (RegisterXWMenu) get xMBarClickedEvt (WindowsMenu fills its menu then)
				menu.addMenuListener(new javax.swing.event.MenuListener(){
					public void menuSelected(javax.swing.event.MenuEvent e){ Xcmd68k.menuBarClicked(owner); }
					public void menuDeselected(javax.swing.event.MenuEvent e){}
					public void menuCanceled(javax.swing.event.MenuEvent e){}
				});
				for(ItemModel68k item : model.items){
					if(item.separator){ menu.addSeparator(); continue; }
					menu.add(externalMenuItem(menu, item));
				}
				//its place among HyperCard's menus, after the one before it in the external's bar (Menu "Switch": its File menu
				//between Apple and Edit), else at the end
				int at = mb.getComponentCount(), p = -2;
				if(model.after==SwingMenus.unwrap(TTalk.appleMenu)) p = -1;
				else if(model.after!=null) for(int i=0; i<mb.getComponentCount(); i++) if(mb.getComponent(i)==model.after) p = i;
				if(p>=-1){
					at = p+1;
					while(at<mb.getComponentCount() && mb.getComponent(at) instanceof JMenu && ((JMenu)mb.getComponent(at)).getClientProperty("hz.externalMenu")==owner) at++;
				}
				mb.add(menu, at);
				changed = true;
			}
			for(int i=mb.getMenuCount()-1; i>=0; i--){
				JMenu jm = mb.getMenu(i);
				if(jm!=null && jm.getClientProperty("hz.externalMenu")==owner && !live.contains(jm.getClientProperty("hz.externalMenuId"))){
					mb.remove(i);
					changed = true;
				}
			}
			if(changed) TTalk.menuChanged();
		});
	}

	/** An item of HyperCard's standard menu that HyperZebra's menu does not have (Print Card, Scroll …), or one of its dividing
	 *  lines: an external sees it where HyperCard has it, but nothing in HyperZebra's bar stands for it. */
	static final class MissingMenuItem {
		final String text; final char command;
		MissingMenuItem(String text, char command){ this.text=text; this.command=command; }
		@Override public String toString(){ return text; }
	}
	/**
	 * HyperCard 2.4's standard menus at userLevel 5, item by item (measured in the 68K VM, Oct 24 2026: `menu "File"` …), by MENU ID;
	 * "-" a dividing line, after "/" the Command key of an item HyperZebra lacks. Externals number HyperCard's items: PowerMenus'
	 * Properties hangs submenus on Objects items 10 and 11 (New Button, New Field; HyperZebra's Shape/Panel Info moved them to 14
	 * and 15 and it checked Bring Closer instead), GetMenuKey reads Edit item 3's key (Cut). HyperZebra's own items HyperCard does
	 * not have stay out of the externals' sight.
	 */
	static final java.util.Map<Integer,String[]> HYPERCARD_MENU_ITEMS = java.util.Map.of(
		2, new String[]{"New Stack…","Open Stack…","Close Stack","Save a Copy…","-","Compact Stack","Protect Stack…","Delete Stack…","-",
			"Page Setup…","Print Field…","Print Card/P","Print Stack…","Print Report…","-","Quit HyperCard/Q"},
		3, new String[]{"Undo","-","Cut","Copy","Paste","Clear","-","New Card","Delete Card","Cut Card","Copy Card","-","Text Style…/T","Background","Icon…"},
		4, new String[]{"Back","Home","Help","Recent","-","First","Prev","Next","Last","-","Find…","Message","Scroll/E","Next Window"},
		6, new String[]{"Button Info…","Field Info…","Card Info…","Bkgnd Info…","Stack Info…","-","Bring Closer","Send Farther","-","New Button","New Field","New Background"});
	/** HyperCard's names of items HyperZebra names otherwise. */
	static final java.util.Map<String,String> HYPERCARD_ITEM_ALIASES = java.util.Map.of("Bkgnd Info…","Background Info…", "Quit HyperCard","Quit", "Clear","Delete");
	/** The items of those menus HyperZebra never has (a script that deleted one of the others took it out of HyperCard's menu too). */
	static final java.util.Set<String> HYPERCARD_ONLY_ITEMS = java.util.Set.of("Page Setup…","Print Field…","Print Card","Print Stack…","Print Report…","Quit HyperCard","Cut Card","Text Style…","Scroll");
	static String looseItemName(String t){ return t==null ? "" : t.trim().replace("...", "…"); }
	/** A component of a HyperCard menu as the external's view has it: the item a hierarchical stand-in replaced (see externalHierarchicalHyperCardItem). */
	static Object viewComponent(Component c){
		Object o = c instanceof javax.swing.JComponent ? ((javax.swing.JComponent)c).getClientProperty(HIERARCHICAL_STAND_IN_FOR) : null;
		return o!=null ? o : c;
	}
	static final String HIERARCHICAL_STAND_IN_FOR = "hz.hierarchicalStandInFor";
	/**
	 * The items of HyperCard's menu {@code m} (HyperCard's MENU {@code id}) as an external sees them: HyperCard's own items in
	 * HyperCard's order (HYPERCARD_MENU_ITEMS) with the items scripts and externals added after the item before them; HyperZebra's
	 * components, or MissingMenuItem. A menu that no longer looks like HyperZebra's standard one (paint mode, rebuilt by a script)
	 * is shown as it is.
	 */
	static java.util.List<Object> hyperCardMenuView(JMenu m, int id){
		Component[] cs = m.getPopupMenu().getComponents();
		java.util.List<Object> own = new java.util.ArrayList<Object>();
		for(Component c : cs) own.add(viewComponent(c));
		String[] layout = id>0 && !Boolean.TRUE.equals(m.getClientProperty("hz.scriptMenu")) ? HYPERCARD_MENU_ITEMS.get(id) : null;
		if(layout==null) return own;
		boolean[] used = new boolean[cs.length];
		java.util.List<Object> view = new java.util.ArrayList<Object>();
		int matched = 0, wanted = 0;
		for(String entry : layout){
			int slash = entry.length()>2 ? entry.indexOf('/') : -1;
			String name = slash>0 ? entry.substring(0, slash) : entry;
			char key = slash>0 ? entry.charAt(slash+1) : 0;
			if(name.equals("-")){ view.add(new MissingMenuItem("-", (char)0)); continue; }
			boolean onlyHyperCard = HYPERCARD_ONLY_ITEMS.contains(name);
			if(!onlyHyperCard) wanted++;
			String alias = HYPERCARD_ITEM_ALIASES.get(name);
			int found = -1;
			for(int j=0; j<cs.length && found<0; j++){
				Object o = own.get(j);
				if(used[j] || !(o instanceof JMenuItem) || o instanceof JMenu && o==cs[j]) continue; //a submenu of HyperZebra's (Open Recent Stack) is not HyperCard's
				String text = ((JMenuItem)o).getText(), english = PCARD.pc!=null && HZ.intl()!=null ? HZ.intl().getEngText(text) : text;
				for(String t : new String[]{text, english}) if(looseItemName(t).equalsIgnoreCase(name) || alias!=null && looseItemName(t).equalsIgnoreCase(alias)) found = j;
			}
			if(found>=0){ used[found] = true; view.add(own.get(found)); matched++; }
			else if(onlyHyperCard) view.add(new MissingMenuItem(name.replace("…", "..."), key));
		}
		if(matched*2<wanted) return own;
		for(int j=0; j<cs.length; j++){
			if(used[j] || !(cs[j] instanceof javax.swing.JComponent) || ((javax.swing.JComponent)cs[j]).getClientProperty("menuMessage")==null) continue;
			int at = 0;
			for(int b=j-1; b>=0 && at==0; b--){ int v = view.indexOf(own.get(b)); if(v>=0) at = v+1; }
			view.add(at, own.get(j));
		}
		return view;
	}
	/** HyperCard's MENU resource IDs of its standard menus (HyperCard 2.4: Apple 1, File 2 … Patterns 11), by English title. */

	/** HyperCard's menus (the Apple menu, then the bar's own menus and the ones scripts made, not externals' menus) for an external:
	 *  the titles scripts use (`the menus`), the standard menus with HyperCard's IDs, the others 0 (the Menu Manager numbers them). */
	static java.util.List<ExternalMenu68k> hyperCardMenusForExternals() {
		java.util.List<ExternalMenu68k> out = new java.util.ArrayList<ExternalMenu68k>();
		if(PCARD.pc==null) return out;
		java.util.List<JMenu> menus = new java.util.ArrayList<JMenu>();
		menus.add(SwingMenus.unwrap(TTalk.appleMenu));
		int after = -1; boolean japanese = false, hasFont = false, hasStyle = false;
		for(JMenu m : SwingMenus.unwrapAll(TTalk.menuList())) if(m.getClass()==JMenu.class && m.getClientProperty("hz.externalMenu")==null){
			menus.add(m);
			String n = TTalk.scriptMenuName(SwingMenus.wrap(m)), e = HZ.intl()!=null ? HZ.intl().getEngText(m.getText()) : m.getText();
			if(n.equals("ｺﾞｰ")) japanese = true;
			if(e.equalsIgnoreCase("Go") || e.equalsIgnoreCase("Tool") || e.equalsIgnoreCase("Tools") || e.equalsIgnoreCase("Objects")) after = menus.size();
			hasFont |= e.equalsIgnoreCase("Font"); hasStyle |= e.equalsIgnoreCase("Style");
		}
		//the Font and Style menus HyperZebra keeps out of its bar are where `the menus` names them (MENU 7 and 8: Menu "Switch" deletes them)
		if(after<0) after = menus.size();
		if(!TTalk.styleMenuDeleted && !hasStyle) menus.add(after, SwingMenus.unwrap(TTalk.styleMenu()));
		if(!TTalk.fontMenuDeleted && !hasFont) menus.add(after, SwingMenus.unwrap(TTalk.fontMenu()));
		for(JMenu m : menus){
			String title = m==SwingMenus.unwrap(TTalk.appleMenu) ? "\u0014" : m==SwingMenus.unwrap(TTalk.fontMenuCache) ? (japanese ? "ﾌｫﾝﾄ" : "Font") : m==SwingMenus.unwrap(TTalk.styleMenuCache) ? (japanese ? "ｽﾀｲﾙ" : "Style") : TTalk.scriptMenuName(SwingMenus.wrap(m));
			String english = m==SwingMenus.unwrap(TTalk.appleMenu) ? "Apple" : HZ.intl()!=null ? HZ.intl().getEngText(m.getText()) : m.getText();
			if("Tool".equalsIgnoreCase(english)) english = "Tools";
			int id = 0;
			if(!Boolean.TRUE.equals(m.getClientProperty("hz.scriptMenu")))
				for(int i=0; i<TTalk.HYPERCARD_MENU_IDS.length; i++) if(TTalk.HYPERCARD_MENU_IDS[i].equalsIgnoreCase(english)) id = i+1;
			ExternalMenu68k em = new ExternalMenu68k(m, title, id, m.isEnabled());
			for(Object o : hyperCardMenuView(m, id)){
				if(o instanceof MissingMenuItem){
					MissingMenuItem missing = (MissingMenuItem)o;
					em.add(missing.text, o, !missing.text.equals("-"), false, missing.command, 0, null);
					continue;
				}
				JMenuItem mi = o instanceof JMenuItem ? (JMenuItem)o : null;
				boolean checked = mi instanceof javax.swing.JCheckBoxMenuItem && ((javax.swing.JCheckBoxMenuItem)mi).isSelected();
				Object style = mi==null ? null : mi.getClientProperty("hzTextStyle"), mark = mi==null ? null : mi.getClientProperty("hzMarkChar");
				em.add(mi==null ? "-" : TTalk.scriptMenuItemText(SwingMenus.wrap(m), SwingMenus.wrap(mi)), o, mi!=null && mi.isEnabled(), checked, mi==null ? 0 : acceleratorChar(mi.getAccelerator()),
					style instanceof Integer ? (Integer)style : 0, checked && mark!=null ? mark.toString() : null);
				//an item an external made hierarchical stays so for the next external (HyperCard keeps the $1B in its menu)
				for(Component c : m.getPopupMenu().getComponents())
					if(c!=o && viewComponent(c)==o && c instanceof javax.swing.JComponent && ((javax.swing.JComponent)c).getClientProperty("hz.submenuId") instanceof Integer)
						em.submenus.set(em.submenus.size()-1, (Integer)((javax.swing.JComponent)c).getClientProperty("hz.submenuId"));
			}
			out.add(em);
		}
		return out;
	}

	/** An external changed HyperCard's menu {@code menu}: the menu itself ({@code item} 0) or item {@code item} (1-based, separators
	 *  counted) is enabled/disabled, checked/unchecked or renamed as a script's `set` of that property would do it; null = unchanged. */
	static void externalChangedHyperCardMenu(Object menu, int item, Boolean enabled, Boolean checked, String text) {
		if(!(menu instanceof JMenu)) return;
		JMenu m = (JMenu)menu;
		if(item==0){ if(enabled!=null) m.setEnabled(enabled); return; }
		OMenuItem omi = new OMenuItem(SwingMenus.wrap(m), item-1);
		try{
			if(text!=null) TUtil.setMenuItemProperty(omi, "name", text);
			if(enabled!=null) TUtil.setMenuItemProperty(omi, "enabled", enabled.toString());
			if(checked!=null) TUtil.setMenuItemProperty(omi, "checkmark", checked.toString());
		}catch(xTalkException e){
			System.err.println("[xcmd] menu "+m.getText()+" item "+item+": "+e.getMessage());
		}
	}

	/** An accelerator's key as a menu item's Command character (HyperZebra's Back is Command-~, Bring Closer Command-+), 0 for none. */
	static char acceleratorChar(javax.swing.KeyStroke ks){
		if(ks==null) return 0;
		int code = ks.getKeyCode();
		if(code>='A' && code<='Z' || code>='0' && code<='9') return (char)code;
		switch(code){
			case KeyEvent.VK_DEAD_TILDE: case KeyEvent.VK_BACK_QUOTE: return '~';
			case KeyEvent.VK_PLUS: case KeyEvent.VK_EQUALS: return '+';
			case KeyEvent.VK_MINUS: return '-';
			case KeyEvent.VK_SLASH: return (ks.getModifiers() & InputEvent.SHIFT_DOWN_MASK)!=0 ? '?' : '/';
			case KeyEvent.VK_PERIOD: return '.';
			case KeyEvent.VK_COMMA: return ',';
			default: return 0;
		}
	}
	/** The popup index of the component an external's item stands for (or of the hierarchical stand-in put in its place), -1 if gone. */
	static int hyperCardItemIndex(JMenu m, Object item){
		Component[] cs = m.getPopupMenu().getComponents();
		for(int j=0; j<cs.length; j++) if(cs[j]==item || viewComponent(cs[j])==item) return j;
		return -1;
	}
	/** A mark as HyperTalk's markChar has it: HyperCard's $11/$13/$14 for the command, diamond and apple glyphs, null for the check mark. */
	static String hyperTalkMark(String mark){
		if(mark==null || mark.isEmpty()) return mark;
		switch(mark.charAt(0)){
			case '✓': case '\u0012': return null;
			case '⌘': return "\u0011";
			case '◆': return "\u0013";
			case '': return "\u0014";
			default: return mark.substring(0, 1);
		}
	}
	/**
	 * An external changed item {@code item} (a component of the menu's external view, see hyperCardMenuView) of HyperCard's menu
	 * {@code menu}, as a script's `set` of the property does it; null = unchanged. {@code mark}: "" none, the check mark or another
	 * character (SetItemMark), {@code command} 0 for none (SetItemCmd), {@code style} the QuickDraw style (SetItemStyle). Returns the
	 * component now in the item's place (checking an item makes a check-box item of it); an item HyperZebra lacks changes nothing.
	 */
	static Object externalChangedHyperCardItem(Object menu, Object item, Boolean enabled, String text, String mark, Character command, Integer style) {
		if(!(menu instanceof JMenu) || !(item instanceof Component)) return item;
		JMenu m = (JMenu)menu;
		final Object[] placed = {item};
		Host.get().runOnEDT(() -> {
			int at = hyperCardItemIndex(m, item);
			if(at<0) return;
			Component now = m.getPopupMenu().getComponent(at);
			if(now!=item){ //a hierarchical stand-in: it and the item it stands for take the name and the state
				for(Component c : new Component[]{now, (Component)item}) if(c instanceof JMenuItem){
					if(text!=null) ((JMenuItem)c).setText(text);
					if(enabled!=null) ((JMenuItem)c).setEnabled(enabled);
				}
				TTalk.menuChanged();
				return;
			}
			OMenuItem omi = new OMenuItem(SwingMenus.wrap(m), at);
			try{
				if(text!=null) TUtil.setMenuItemProperty(omi, "name", text);
				if(enabled!=null) TUtil.setMenuItemProperty(omi, "enabled", enabled.toString());
				if(mark!=null){
					String hc = hyperTalkMark(mark);
					if(mark.isEmpty()) TUtil.setMenuItemProperty(omi, "checkmark", "false");
					else if(hc==null) TUtil.setMenuItemProperty(omi, "checkmark", "true");
					else TUtil.setMenuItemProperty(omi, "markchar", hc);
				}
				if(command!=null) TUtil.setMenuItemProperty(omi, "commandchar", command==0 ? "" : String.valueOf(command));
				if(style!=null) TUtil.setMenuItemProperty(omi, "textstyle", TUtil.textStyleName(style));
			}catch(xTalkException e){
				System.err.println("[xcmd] menu "+m.getText()+" item "+(at+1)+": "+e.getMessage());
			}
			placed[0] = m.getPopupMenu().getComponent(at);
		});
		return placed[0];
	}
	/**
	 * An external made item {@code item} of HyperCard's menu {@code menu} hierarchical (SetItemCmd $1B and the submenu's ID as its
	 * mark): a stand-in submenu takes the item's place, its items send doMenu with their text and the submenu's title (PowerMenus'
	 * Properties hangs its button and field styles on New Button and New Field; its doMenu handler makes the object chosen).
	 * {@code submenu} null puts the item back.
	 */
	static void externalHierarchicalHyperCardItem(Object menu, Object item, MenuModel68k submenu) {
		if(!(menu instanceof JMenu) || !(item instanceof JMenuItem)) return;
		JMenu m = (JMenu)menu; JMenuItem original = (JMenuItem)item;
		Host.get().runOnEDT(() -> {
			int at = hyperCardItemIndex(m, item);
			if(at<0) return;
			Component put = original;
			if(submenu!=null){
				JMenu standIn = new JMenu(original.getText());
				standIn.putClientProperty(HIERARCHICAL_STAND_IN_FOR, original);
				standIn.putClientProperty("hz.submenuId", submenu.id);
				standIn.setEnabled(original.isEnabled());
				fillExternalSubmenu(standIn, submenu);
				put = standIn;
			}
			m.getPopupMenu().remove(at);
			m.getPopupMenu().insert(put, at);
			TTalk.menuChanged();
		});
	}

	/** HyperCard's menus an external took out of the menu bar (DeleteMenu), with the bar they were in: they can come back. */
	static final java.util.Map<JMenu,javax.swing.JMenuBar> menusOutOfBar = new java.util.IdentityHashMap<JMenu,javax.swing.JMenuBar>();

	/**
	 * An external took one of HyperCard's menus out of the menu bar (DeleteMenu: Rinaldi's Menu "Switch" leaves only Apple, File and
	 * Edit): it leaves HyperZebra's bar too, so `the menus` no longer names it (HC 2.2 measured), until the external puts it back
	 * ({@link #externalRestoredHyperCardMenu}) or `reset menuBar` makes the bar again.
	 */
	static void externalRemovedHyperCardMenu(Object menu) {
		if(!(menu instanceof JMenu) || menu==SwingMenus.unwrap(TTalk.appleMenu) || PCARD.pc==null || PCARD.pc.menu==null || PCARD.pc.menu.mb==null) return;
		if(menu==SwingMenus.unwrap(TTalk.fontMenuCache)){ TTalk.fontMenuDeleted = true; return; }
		if(menu==SwingMenus.unwrap(TTalk.styleMenuCache)){ TTalk.styleMenuDeleted = true; return; }
		JMenu m = (JMenu)menu;
		Host.get().runOnEDT(() -> {
			javax.swing.JMenuBar mb = PCARD.pc.menu.mb;
			for(int i=0; i<mb.getComponentCount(); i++) if(mb.getComponent(i)==m){ mb.remove(i); menusOutOfBar.put(m, mb); TTalk.menuChanged(); return; }
		});
	}
	/** The menu comes back into the bar (SetMenuBar with the list saved before), after {@code previous} (a menu of the bar, null = first). */
	static void externalRestoredHyperCardMenu(Object menu, Object previous) {
		if(!(menu instanceof JMenu) || PCARD.pc==null || PCARD.pc.menu==null) return;
		if(menu==SwingMenus.unwrap(TTalk.fontMenuCache)){ TTalk.fontMenuDeleted = false; return; }
		if(menu==SwingMenus.unwrap(TTalk.styleMenuCache)){ TTalk.styleMenuDeleted = false; return; }
		JMenu m = (JMenu)menu;
		Host.get().runOnEDT(() -> {
			javax.swing.JMenuBar mb = PCARD.pc.menu.mb;
			if(menusOutOfBar.remove(m)!=mb || mb==null) return; //reset menuBar has made the bar again since
			int at = 0;
			for(int i=0; i<mb.getComponentCount(); i++) if(mb.getComponent(i)==previous) at = i+1;
			mb.add(m, at);
			TTalk.menuChanged();
		});
	}
	/**
	 * An external deleted, inserted or brought back items of HyperCard's menu {@code menu} (Menu "Switch" cuts Edit down to its
	 * clipboard items and later restores the menu from a copy of its handle): {@code arrangement} is the menu's items as the
	 * external left them, each a component the menu had or a String, the text of a new item (choosing it sends doMenu, as HyperCard
	 * does for an item it does not know); {@code enabled}/{@code checked} are their states. Returns the components now in the menu.
	 */
	static java.util.List<Object> externalArrangedHyperCardMenu(Object menu, java.util.List<Object> arrangement, boolean[] enabled, boolean[] checked) {
		return externalArrangedHyperCardMenu(menu, arrangement, enabled, checked, null);
	}
	/** As above; {@code seen} the items the external was ever given (see hyperCardMenuView): HyperZebra's own items it never saw
	 *  (Shape Info…) stay after the item they followed, the items HyperZebra lacks (MissingMenuItem) keep their places in the list only. */
	static java.util.List<Object> externalArrangedHyperCardMenu(Object menu, java.util.List<Object> arrangement, boolean[] enabled, boolean[] checked, java.util.Collection<Object> seen) {
		java.util.List<Object> out = new java.util.ArrayList<Object>();
		if(!(menu instanceof JMenu)) return out;
		JMenu m = (JMenu)menu;
		Host.get().runOnEDT(() -> {
			javax.swing.JPopupMenu popup = m.getPopupMenu();
			Component[] before = popup.getComponents();
			popup.removeAll();
			for(int i=0; i<arrangement.size(); i++){
				Object o = arrangement.get(i);
				if(o instanceof MissingMenuItem){ out.add(o); continue; }
				Component c;
				if(o instanceof Component){
					c = (Component)o;
					for(Component b : before) if(b!=o && viewComponent(b)==o) c = b; //its hierarchical stand-in
				}
				else if("-".equals(o) || ((String)o).startsWith("-")) c = new javax.swing.JPopupMenu.Separator();
				else{
					ItemModel68k item = new ItemModel68k();item.text = (String)o;item.enabled = enabled[i];
					c = externalMenuItem(m, item);
				}
				if(c instanceof javax.swing.JMenuItem){
					javax.swing.JMenuItem mi = (javax.swing.JMenuItem)c;
					mi.setEnabled(enabled[i]);
					if(mi instanceof javax.swing.JCheckBoxMenuItem) ((javax.swing.JCheckBoxMenuItem)mi).setSelected(checked[i]);
				}
				popup.add(c);
				out.add(o instanceof Component ? o : c);
			}
			if(seen!=null){
				java.util.Set<Object> known = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
				known.addAll(seen);
				for(int j=0; j<before.length; j++){
					if(known.contains(viewComponent(before[j]))) continue;
					int at = 0;
					for(int b=j-1; b>=0 && at==0; b--){ int k = popup.getComponentIndex(before[b]); if(k>=0) at = k+1; }
					popup.insert(before[j], at);
				}
			}
			TTalk.menuChanged();
		});
		return out;
	}

	/** The menu name doMenu gets for an item of {@code menu}: a submenu's own title (HyperCard Menus' "CreateIndex"), else the menu's. */
	static String externalMenuTitle(JMenu menu){
		Object t = menu.getClientProperty("hz.externalMenuTitle");
		return t!=null ? t.toString() : menu.getText();
	}
	/** The items of an external's submenu. */
	static void fillExternalSubmenu(JMenu sub, MenuModel68k model){
		sub.putClientProperty("hz.externalMenuTitle", model.title);
		for(ItemModel68k item : model.items){
			if(item.separator) sub.addSeparator();
			else sub.add(externalMenuItem(sub, item));
		}
	}
	/**
	 * An item of an external's menu in HyperCard's bar: choosing it sends doMenu with its text as it is then. A checked item
	 * (CheckItem/SetItemMark) is a check-box item, as `set the checkMark` makes one; a hierarchical item (InsertSubMenu: HyperCard
	 * Menus' "Index", the stack's only way to its 14 help cards) is a submenu of the submenu's items. The item's text style,
	 * mark character and icon go with it.
	 */
	static JMenuItem externalMenuItem(JMenu menu, ItemModel68k item){
		if(item.submenu!=null){
			JMenu sub = new JMenu(item.text);
			sub.setEnabled(item.enabled && item.submenu.enabled);
			fillExternalSubmenu(sub, item.submenu);
			decorateExternalItem(sub, item);
			return sub;
		}
		JMenuItem mi = externalMarked(item) ? new javax.swing.JCheckBoxMenuItem(item.text, true) : new JMenuItem(item.text);
		mi.setEnabled(item.enabled);
		setExternalCommandKey(mi, item.command);
		decorateExternalItem(mi, item);
		mi.putClientProperty("menuMessage", "");
		mi.addActionListener(e -> {
			//a menu an external window registered (RegisterXWMenu): the choice is that window's xMenuEvt, not a doMenu
			Object owner = menu.getClientProperty("hz.externalMenu"), id = menu.getClientProperty("hz.externalMenuId");
			if(owner!=null && id instanceof Integer && Xcmd68k.menuChosen(owner, (Integer)id, menu.getPopupMenu().getComponentIndex(mi)+1)) return;
			if(!TTalk.idle) return;
			TTalk.CallMessage("doMenu", new String[]{mi.getText(), externalMenuTitle(menu)}, HZ.stack().curCard);
		});
		return mi;
	}
	/** Whether the item has a mark: the check mark or another character (SetItemMark "#"). */
	static boolean externalMarked(ItemModel68k item){ return item.checked || item.markText!=null && !item.markText.isEmpty(); }
	/** The item's text style (the textStyle a script reads), mark character (its markChar) and icon as the external set them; true when one changed. */
	static boolean decorateExternalItem(JMenuItem mi, ItemModel68k item){
		boolean changed = false;
		Object style = mi.getClientProperty("hzTextStyle");
		if((style instanceof Integer ? (Integer)style : 0)!=item.style){
			java.awt.Font base = javax.swing.UIManager.getFont(mi instanceof JMenu ? "Menu.font" : "MenuItem.font");
			if(base!=null) mi.setFont(base);
			MacAppHost.applyItemStyle(mi, item.style);
			mi.putClientProperty("hzTextStyle", item.style==0 ? null : (Object)item.style);
			changed = true;
		}
		String mark = externalMarked(item) && !item.checked ? hyperTalkMark(item.markText) : null;
		if(!java.util.Objects.equals(mark, mi.getClientProperty("hzMarkChar"))){ mi.putClientProperty("hzMarkChar", mark); changed = true; }
		Object icon = mi.getClientProperty("hz.iconKey");
		int key = item.icon!=null ? item.iconKey : 0;
		if((icon instanceof Integer ? (Integer)icon : 0)!=key){
			mi.setIcon(item.icon!=null ? new javax.swing.ImageIcon(item.icon) : null);
			mi.putClientProperty("hz.iconKey", key==0 ? null : (Object)key);
			changed = true;
		}
		return changed;
	}

	/** The item's Command key as SetItemCmd gave it (the commandChar a script reads: Rinaldi's Menu "SetCmd", HC 2.2 measured); true when it changed. */
	static boolean setExternalCommandKey(JMenuItem mi, char command){
		javax.swing.KeyStroke now = mi.getAccelerator();
		int code = command>' ' && command<0x7f ? Character.toUpperCase(command) : 0;
		if(now==null ? code==0 : now.getKeyCode()==code) return false;
		mi.setAccelerator(code==0 ? null : javax.swing.KeyStroke.getKeyStroke(code, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
		return true;
	}

	/** Brings an external's menu in the bar up to its MenuHandle's state; true when anything changed. Items stay (with any menu
	 *  message a script gave them) as long as the same texts are in the same places; otherwise the items are made again. */
	static boolean updateExternalMenu(JMenu menu, MenuModel68k model){
		boolean changed = menu.isEnabled()!=model.enabled;
		menu.setEnabled(model.enabled);
		return updateExternalItems(menu, model) || changed;
	}
	static boolean updateExternalItems(JMenu menu, MenuModel68k model){
		javax.swing.JPopupMenu popup = menu.getPopupMenu();
		boolean same = popup.getComponentCount()==model.items.size();
		for(int i=0; same && i<model.items.size(); i++){
			java.awt.Component c = popup.getComponent(i);
			ItemModel68k item = model.items.get(i);
			same = item.separator ? !(c instanceof JMenuItem) : c instanceof JMenuItem && (c instanceof JMenu)==(item.submenu!=null) && java.util.Objects.equals(((JMenuItem)c).getText(), item.text);
		}
		if(!same){
			menu.removeAll();
			for(ItemModel68k item : model.items){
				if(item.separator) menu.addSeparator();
				else menu.add(externalMenuItem(menu, item));
			}
			if(popup.isVisible()){ popup.pack(); popup.revalidate(); popup.repaint(); } //filled while it is open (xMBarClickedEvt)
			return true;
		}
		boolean changed = false;
		for(int i=0; i<model.items.size(); i++){
			ItemModel68k item = model.items.get(i);
			if(item.separator) continue;
			JMenuItem mi = (JMenuItem)popup.getComponent(i);
			if(item.submenu!=null){
				boolean on = item.enabled && item.submenu.enabled;
				if(mi.isEnabled()!=on){ mi.setEnabled(on); changed = true; }
				mi.putClientProperty("hz.externalMenuTitle", item.submenu.title);
				if(updateExternalItems((JMenu)mi, item.submenu)) changed = true;
				if(decorateExternalItem(mi, item)) changed = true;
				continue;
			}
			if(mi.isEnabled()!=item.enabled){ mi.setEnabled(item.enabled); changed = true; }
			if(setExternalCommandKey(mi, item.command)) changed = true;
			boolean checked = mi instanceof javax.swing.JCheckBoxMenuItem && ((javax.swing.JCheckBoxMenuItem)mi).isSelected(), marked = externalMarked(item);
			if(checked!=marked){
				if(mi instanceof javax.swing.JCheckBoxMenuItem) ((javax.swing.JCheckBoxMenuItem)mi).setSelected(marked);
				else{
					JMenuItem replacement = externalMenuItem(menu, item);
					replacement.putClientProperty("menuMessage", mi.getClientProperty("menuMessage"));
					menu.remove(i); menu.insert(replacement, i);
					mi = replacement;
				}
				changed = true;
			}
			if(decorateExternalItem(mi, item)) changed = true;
		}
		return changed;
	}

}

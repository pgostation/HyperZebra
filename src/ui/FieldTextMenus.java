import java.awt.Color;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.util.function.Consumer;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * HyperCard's Font and Style menus for the text being edited in a field: the font, size and style of the selected
 * characters, and HyperZebra's own text colour of them. Like the Help menu (GMenu.balloonHelpMenu) they live only in the
 * bar on the screen: the menus scripts see (the menus, menu numbers, doMenu "Bold", "Style") keep HyperCard's stand-ins
 * in TTalk, which have no place in the bar. A stack that deletes menu "Font" or "Style", or makes its own of that name,
 * has no menu of HyperZebra's there.
 */
class FieldTextMenus {
	private static final String[] STYLES = {"Plain","Bold","Italic","Underline","Outline","Shadow","Condense","Extend","Group"};
	private static final int[] SIZES = {9,10,12,14,18,24};

	/** Puts the menus into the bar on the screen (bar), after the menus of the browse tool's model bar (model). */
	static void addTo(JMenuBar bar, JMenuBar model){
		if(PCARD.pc==null || PCARD.pc.menu==null || model!=PCARD.pc.menu.mb) return; //not with a paint tool's menus
		boolean hasFont = TTalk.fontMenuDeleted, hasStyle = TTalk.styleMenuDeleted;
		for(int i=0; i<model.getComponentCount(); i++){
			if(!(model.getComponent(i) instanceof JMenu)) continue;
			JMenu m = (JMenu)model.getComponent(i);
			String n = java.text.Normalizer.normalize(PCARD.pc.intl!=null ? PCARD.pc.intl.getEngText(m.getText()) : m.getText(),
				java.text.Normalizer.Form.NFKC).trim();
			if(n.equalsIgnoreCase("Font") || n.equals("フォント")) hasFont = true;
			if(n.equalsIgnoreCase("Style") || n.equals("スタイル")) hasStyle = true;
		}
		if(!hasFont) bar.add(fontMenu());
		if(!hasStyle) bar.add(styleMenu());
	}

	private static String text(String english){
		return PCARD.pc.intl==null ? english : PCARD.pc.intl.getText(english);
	}

	/** The selection the menus act on: the field being typed into and its selected characters. */
	private static final class Target {
		final MyTextArea area;
		final OField field;
		final int start, end;
		Target(MyTextArea area){
			this.area = area;
			field = area.fldData;
			int length = area.getText().length();
			start = Math.max(0, Math.min(area.getSelectionStart(), length));
			end = Math.max(start, Math.min(area.getSelectionEnd(), length));
		}
	}
	/** The card's field with the keyboard focus (also while a menu is open), with characters selected; null when none */
	private static Target target(){
		Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
		if(!(c instanceof MyTextArea)) return null;
		MyTextArea area = (MyTextArea)c;
		if(area.fldData==null || area.fldData.card==null || !area.isEditable() || !area.isShowing()) return null;
		if(PCARD.pc.stack==null || area.fldData.card.stack!=PCARD.pc.stack) return null;
		Target t = new Target(area);
		return t.end>t.start ? t : null;
	}

	/** The style of every selected character, or null when there is none (for the check marks) */
	private static java.util.List<OField.TextStyleSpec> specs(Target t){
		if(t==null) return null;
		java.util.ArrayList<OField.TextStyleSpec> list = new java.util.ArrayList<OField.TextStyleSpec>();
		for(int pos=t.start; pos<t.end; pos++) list.add(t.field.textStyleAt(pos));
		return list;
	}

	/** Changes each selected run with change; the selection stays */
	private static void restyle(Target t, Consumer<OField.TextStyleSpec> change){
		if(t==null){ Toolkit.getDefaultToolkit().beep(); return; }
		t.field.restyleText(t.start, t.end, change);
		t.area.repaint();
	}

	private static JMenu fontMenu(){
		JMenu menu = new JMenu(text("Font"));
		menu.addMenuListener(new MenuListener(){
			public void menuSelected(MenuEvent e){
				//the families are put in at the first opening (several hundred items on a Mac: not at every rebuild of the bar)
				if(menu.getItemCount()==0){
					for(int i=0; i<TTalk.fontMenu().getItemCount(); i++){
						JMenuItem source = TTalk.fontMenu().getItem(i);
						if(source==null) continue;
						String family = source.getText();
						JCheckBoxMenuItem item = new JCheckBoxMenuItem(family);
						item.addActionListener(ev -> {
							Target t = target();
							restyle(t, spec -> spec.font = family);
							item.setSelected(t!=null);
						});
						menu.add(item);
					}
				}
				Target t = target();
				java.util.List<OField.TextStyleSpec> specs = specs(t);
				String common = null;
				if(specs!=null){
					for(OField.TextStyleSpec s : specs){
						if(common==null) common = s.font;
						else if(!common.equalsIgnoreCase(s.font)){ common = null; break; }
					}
				}
				for(int i=0; i<menu.getItemCount(); i++){
					JMenuItem item = menu.getItem(i);
					if(item==null) continue;
					boolean check = common!=null && common.equalsIgnoreCase(item.getText());
					if(item.isSelected()!=check) item.setSelected(check);
					if(item.isEnabled()!=(t!=null)) item.setEnabled(t!=null);
				}
			}
			public void menuDeselected(MenuEvent e){}
			public void menuCanceled(MenuEvent e){}
		});
		return menu;
	}

	private static JMenu styleMenu(){
		JMenu menu = new JMenu(text("Style"));
		java.util.ArrayList<JCheckBoxMenuItem> styleItems = new java.util.ArrayList<JCheckBoxMenuItem>();
		for(int k=0; k<STYLES.length; k++){
			final int bit = k==0 ? 0 : 1<<(k-1);
			JCheckBoxMenuItem item = new JCheckBoxMenuItem(text(STYLES[k]));
			item.addActionListener(e -> {
				Target t = target();
				if(bit==0){ restyle(t, spec -> spec.style = 0); return; }
				//as HyperCard: the style comes off when every selected character has it, else every one gets it
				java.util.List<OField.TextStyleSpec> specs = specs(t);
				boolean all = specs!=null && specs.stream().allMatch(s -> (s.style&bit)!=0);
				restyle(t, spec -> spec.style = all ? spec.style&~bit : spec.style|bit);
			});
			styleItems.add(item);
			menu.add(item);
		}
		menu.addSeparator();
		java.util.ArrayList<JCheckBoxMenuItem> sizeItems = new java.util.ArrayList<JCheckBoxMenuItem>();
		for(int size : SIZES){
			JCheckBoxMenuItem item = new JCheckBoxMenuItem(Integer.toString(size));
			item.addActionListener(e -> restyle(target(), spec -> spec.size = size));
			sizeItems.add(item);
			menu.add(item);
		}
		JCheckBoxMenuItem other = new JCheckBoxMenuItem(text("Other…"));
		other.addActionListener(e -> {
			Target t = target();
			if(t==null){ Toolkit.getDefaultToolkit().beep(); return; }
			Object answer = JOptionPane.showInputDialog(PCARD.pc, International.t("Text size:"), text("Style"),
				JOptionPane.PLAIN_MESSAGE, null, null, Integer.toString(t.field.textStyleAt(t.start).size));
			if(answer==null) return;
			int size;
			try{ size = Integer.parseInt(answer.toString().trim()); }catch(NumberFormatException ex){ Toolkit.getDefaultToolkit().beep(); return; }
			if(size<1 || size>127){ Toolkit.getDefaultToolkit().beep(); return; }
			restyle(t, spec -> spec.size = size);
			t.area.requestFocusInWindow();
		});
		menu.add(other);
		menu.addSeparator();
		JMenuItem color = new JMenuItem(text("Text Color…"));
		color.addActionListener(e -> {
			Target t = target();
			if(t==null){ Toolkit.getDefaultToolkit().beep(); return; }
			int now = t.field.textStyleAt(t.start).color;
			Color before = now!=-1 ? new Color(now) : t.field.color!=null ? t.field.color : Color.BLACK;
			Color chosen = GColorDialog.getColor(PCARD.pc, before, false);
			if(chosen!=null && !chosen.equals(before)) restyle(t, spec -> spec.color = chosen.getRGB()&0xFFFFFF);
			t.area.requestFocusInWindow();
		});
		menu.add(color);
		menu.addMenuListener(new MenuListener(){
			public void menuSelected(MenuEvent e){
				Target t = target();
				java.util.List<OField.TextStyleSpec> specs = specs(t);
				for(int k=0; k<styleItems.size(); k++){
					final int bit = k==0 ? 0 : 1<<(k-1);
					boolean check = specs!=null && (bit==0 ? specs.stream().allMatch(s -> s.style==0) : specs.stream().allMatch(s -> (s.style&bit)!=0));
					styleItems.get(k).setSelected(check);
					styleItems.get(k).setEnabled(t!=null);
				}
				int size = -1;
				if(specs!=null) for(OField.TextStyleSpec s : specs){ if(size==-1) size = s.size; else if(size!=s.size){ size = -2; break; } }
				boolean listed = false;
				for(int k=0; k<sizeItems.size(); k++){
					boolean check = size==SIZES[k];
					listed |= check;
					sizeItems.get(k).setSelected(check);
					sizeItems.get(k).setEnabled(t!=null);
				}
				//as HyperCard: Other… is checked for a size the menu does not list, and says it
				other.setSelected(size>0 && !listed);
				other.setText(size>0 && !listed ? text("Other…").replace("…", "")+" ("+size+")…" : text("Other…"));
				other.setEnabled(t!=null);
				color.setEnabled(t!=null);
			}
			public void menuDeselected(MenuEvent e){}
			public void menuCanceled(MenuEvent e){}
		});
		return menu;
	}
}

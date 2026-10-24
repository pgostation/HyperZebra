import javax.swing.JComboBox;

/**
 * The Panel pop-up of Button, Field and Shape Info: "None", then the panels of the part's card or background by name (a
 * button belongs to a panel of its own card or background, as a card button to its card). Panels are made with
 * Objects > New Panel and edited in Panel Info, like other objects; the pop-up only chooses one.
 */
final class PanelChooser {
	private PanelChooser(){}

	/** One entry: a panel, or none. */
	static final class Item {
		final OPanel panel;
		Item(OPanel panel){ this.panel = panel; }
		@Override public String toString(){
			if(panel==null) return International.t("None");
			return panel.name.isEmpty() ? "panel id "+panel.id : panel.name;
		}
	}

	static JComboBox<Item> create(OObject part){
		JComboBox<Item> box = new JComboBox<Item>();
		box.addItem(new Item(null));
		Item selected = null;
		if(part.parent instanceof OCardBase) for(OPanel p : ((OCardBase)part.parent).panelList){
			Item item = new Item(p);
			box.addItem(item);
			if(p.id==part.panelId) selected = item;
		}
		box.setSelectedIndex(0);
		if(selected!=null) box.setSelectedItem(selected);
		box.setToolTipText(International.t("The panel it belongs to. Make panels with Objects > New Panel."));
		return box;
	}

	/** A member as Panel Info lists it: its kind, id and name (Shape  id 3  · Bézier  "leaf"). */
	static String memberLabel(OObject part){
		String kind = part instanceof OShape ? "Shape" : part instanceof OField ? "Field" : "Button";
		String label = PCARD.pc.intl.getText(kind)+"  id "+part.id;
		if(part instanceof OShape) label += "  \u00B7 "+ShapeInfoPanel.kindName(((OShape)part).kind); //Oval, Bézier, Text…
		String name = part.name==null ? "" : part.name.replace('\r',' ').replace('\n',' ').trim();
		return name.isEmpty() ? label : label+"  “"+name+"”";
	}

	/** Panel "name" (or panel id N) and its number of members, as the Shape tool labels the panel round a selected shape. */
	static String panelLabel(OPanel panel){
		String name = panel.name.isEmpty() ? "id "+panel.id : panel.name;
		return International.t("Panel “{0}” · {1} parts", name, panel.members().size());
	}

	/** The chosen panel, or null for none. */
	static OPanel selected(JComboBox<Item> box){
		Object item = box.getSelectedItem();
		return item instanceof Item ? ((Item)item).panel : null;
	}
}

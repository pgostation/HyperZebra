final class ItemModel68k {
	String text;boolean enabled=true,checked;char command;int mark;int submenuId;int style;boolean separator;
	/** The mark character as the menu font shows it (a check mark, a diamond, √, #...), or null for none or a submenu's ID. */
	String markText;
	/** SetMenuItemModifiers bits: shift 1, option 2, control 4, no Command 8. */
	int modifiers;
	/** The item's icon (ICON/cicn/SICN 256+n), or null. */
	java.awt.image.BufferedImage icon;int iconKey;
	/** Balloon Help text of the item (null: none, or balloons off) shown as the host item's tooltip. */
	String help;
	MenuModel68k submenu;
	boolean sameAs(ItemModel68k o){
		return o!=null&&java.util.Objects.equals(text,o.text)&&enabled==o.enabled&&checked==o.checked&&command==o.command&&mark==o.mark&&java.util.Objects.equals(markText,o.markText)&&submenuId==o.submenuId&&style==o.style&&separator==o.separator&&iconKey==o.iconKey
			&&java.util.Objects.equals(help,o.help)&&(submenu==null?o.submenu==null:submenu.sameAs(o.submenu));
	}
}

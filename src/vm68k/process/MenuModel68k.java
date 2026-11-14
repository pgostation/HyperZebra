import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One menu of the application's menu bar (built from MENU handles by the Menu Manager). */
final class MenuModel68k {
	final int id;final String title;final boolean enabled;final List<ItemModel68k> items=new ArrayList<ItemModel68k>();boolean apple;
	/** An external's menu in HyperCard's bar: the HyperCard menu it stands after (null: before them all, or none in the bar). */
	Object after;
	/** How many items of the menu are left out before the first one here (the Help menu's system items): item 1 is number itemBase+1. */
	int itemBase;
	MenuModel68k(int id,String title,boolean enabled){this.id=id;this.title=title;this.enabled=enabled;}
	/** A menu drawn by the program's own MDEF (HyperCard's Tools and Patterns menus): its rendering, and the items the MDEF
	 *  reports in it as {item, top, left, bottom, right}; each becomes a host item showing its slice of the picture. */
	java.awt.image.BufferedImage image;List<int[]> pictureItems;
	/** The text size the program's MDEF set before handing the menu to the standard definition (Infini-D 2.0.1's view
	 *  pop-up: 9), or 0 for the menu font. Only pop-ups use it; the menu bar keeps the host's menu font. */
	int fontSize;
	/** Text of a host item added under a picture menu that shows the menu as a palette (HyperCard's tear-off menus cannot be
	 *  dragged off a host menu), or null. */
	String tearOff;
	/** Whether the host menu built from {@code other} would look and behave the same as one built from this model. */
	/**
	 * Whether a host menu built from {@code other} can be brought up to this model in place: the same menus, items, separators,
	 * submenus, command keys, text styles and icons, so only titles, item texts, marks, enabled states and help texts differ.
	 */
	boolean sameLayout(MenuModel68k other){
		if(other==null||id!=other.id||apple!=other.apple||fontSize!=other.fontSize||itemBase!=other.itemBase||image!=null||other.image!=null||!java.util.Objects.equals(tearOff,other.tearOff)||items.size()!=other.items.size())return false;
		for(int i=0;i<items.size();i++){ItemModel68k a=items.get(i),b=other.items.get(i);
			if(a.separator!=b.separator||a.command!=b.command||a.modifiers!=b.modifiers||a.iconKey!=b.iconKey||a.style!=b.style||a.submenuId!=b.submenuId||(a.submenu==null)!=(b.submenu==null))return false; // a new text style is a new font: built anew
			if(a.submenu!=null&&!a.submenu.sameLayout(b.submenu))return false;}
		return true;
	}
	boolean sameAs(MenuModel68k other){
		if(other==null||id!=other.id||enabled!=other.enabled||apple!=other.apple||fontSize!=other.fontSize||!java.util.Objects.equals(title,other.title)||!java.util.Objects.equals(tearOff,other.tearOff)||items.size()!=other.items.size())return false;
		for(int i=0;i<items.size();i++)if(!items.get(i).sameAs(other.items.get(i)))return false;
		if((image==null)!=(other.image==null))return false;
		if(image!=null){
			if(image.getWidth()!=other.image.getWidth()||image.getHeight()!=other.image.getHeight())return false;
			if((pictureItems==null)!=(other.pictureItems==null))return false;
			if(pictureItems!=null){if(pictureItems.size()!=other.pictureItems.size())return false;for(int i=0;i<pictureItems.size();i++)if(!java.util.Arrays.equals(pictureItems.get(i),other.pictureItems.get(i)))return false;}
			int w=image.getWidth(),h=image.getHeight();int[] a=image.getRGB(0,0,w,h,null,0,w),b=other.image.getRGB(0,0,w,h,null,0,w);if(!java.util.Arrays.equals(a,b))return false;
		}
		return true;
	}
}

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu Manager for hosted applications. MenuInfo records live in VM memory in
 * their classic layout (programs may inspect them); the host menu bar is
 * rebuilt from them, and selections come back through synthetic menu-bar
 * events answered by MenuSelect/MenuKey.
 */
final class MenuManager68k {
	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Menu bar, in order (menu Handles). */
	private final List<Integer> menuBar=new ArrayList<Integer>();
	/** Hierarchical (submenu) menus registered with InsertMenu(-1). */
	private final List<Integer> hierarchical=new ArrayList<Integer>();
	private final Map<Integer,Integer> menusById=new LinkedHashMap<Integer,Integer>();
	private boolean dirty;
	/** Menu ID and item of the Quit command (Cmd-Q) in the current menu bar, or null; updated by {@link #flush}. */
	private volatile int[] quitItem;
	private int pendingKeyMenu,pendingKeyItem;
	private int helpMenu;

	MenuManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x930:return "InitMenus";case 0x931:return "NewMenu";case 0x9bf:return "GetMenu";case 0x932:return "DisposeMenu";case 0x933:return "AppendMenu";case 0x94d:return "AppendResMenu";
			case 0x951:return "InsertResMenu";case 0x825:return "MenuDispatch";case 0x826:return "InsertMenuItem";case 0x952:return "DeleteMenuItem";case 0x935:return "InsertMenu";case 0x936:return "DeleteMenu";case 0x937:return "DrawMenuBar";
			case 0x81d:return "InvalMenuBar";case 0x934:return "ClearMenuBar";case 0x9c0:return "GetNewMBar";case 0x93b:return "GetMenuBar";case 0x93c:return "SetMenuBar";case 0x938:return "HiliteMenu";
			case 0x93d:return "MenuSelect";case 0x93e:return "MenuKey";case 0x94c:return "FlashMenuBar";case 0x94a:return "SetMenuFlash";case 0x947:return "SetMenuItemText";case 0x946:return "GetMenuItemText";
			case 0x93a:return "DisableItem";case 0x939:return "EnableItem";case 0x945:return "CheckItem";case 0x944:return "SetItemMark";case 0x943:return "GetItemMark";case 0x940:return "SetItemIcon";
			case 0x93f:return "GetItemIcon";case 0x942:return "SetItemStyle";case 0x941:return "GetItemStyle";case 0x950:return "CountMItems";case 0x949:return "GetMenuHandle";case 0x948:return "CalcMenuSize";
			case 0x808:return "InitProcMenu";case 0xa66:return "MenuChoice";case 0x84e:return "GetItemCmd";case 0x84f:return "SetItemCmd";case 0x80b:return "PopUpMenuSelect";
			case 0xa60:return "DeleteMCEntries";case 0xa61:return "GetMCInfo";case 0xa62:return "SetMCInfo";case 0xa63:return "DisposeMCInfo";case 0xa64:return "GetMCEntry";case 0xa65:return "SetMCEntries";
			case 0x9b5:return "SystemMenu";
			default:return null;
		}
	}

	static final class Item {String text="";int icon,cmd,mark,style;}
	static final class Menu {int id;String title="";List<Item> items=new ArrayList<Item>();int enableFlags=-1;}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		if(hyperCardMenusStale&&tb.app==null&&tb.stack!=null)syncHyperCardMenus();
		switch(trap){
			case 0x930:menuBar.clear();hierarchical.clear();loadMenuColors(0);menuBarSaveData();changed();return true; // InitMenus: the menu bar's colours from 'mctb' 0
			case 0x931:{int title=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;Menu m=new Menu();m.id=id;m.title=tb.readPascal(title);memory.writeLong(cpu.a[7],store(m,0));return true;}
			case 0x9bf:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;int menu=getMenu(id);if(menu!=0)loadMenuColors(id);memory.writeLong(cpu.a[7],menu);return true;} // GetMenu adds the 'mctb' of the same ID
			case 0x932:{int h=pop(cpu);remove(h);if(!tb.resources.release(h))tb.api.disposeHandleAndFree(h);changed();return true;}
			case 0x933:{int data=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;Menu m=read(h);if(m!=null){appendItems(m,pascalBytes(data),m.items.size(),false);write(h,m);}return true;}
			case 0x94d:{int type=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;appendResources(h,type,-1);return true;}
			case 0x951:{int after=(short)memory.readWord(cpu.a[7]),type=memory.readLong(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;appendResources(h,type,after);return true;}
			case 0x825:return menuDispatch(cpu);
			case 0x826:{int after=(short)memory.readWord(cpu.a[7]),data=memory.readLong(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Menu m=read(h);if(m!=null){int before=m.items.size();int at=Math.max(0,Math.min(m.items.size(),after));appendItems(m,pascalBytes(data),at,true);shiftItemData(h,at+1,m.items.size()-before);write(h,m);}return true;}
			case 0x952:{int item=(short)memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Menu m=read(h);if(m!=null&&item>=1&&item<=m.items.size()){m.items.remove(item-1);shiftItemData(h,item,-1);if(item<31){int below=m.enableFlags&((1<<item)-1),above=m.enableFlags&~((1<<(item+1))-1);m.enableFlags=below|(above>>>1)|(1<<31);}else if(item==31)m.enableFlags|=1<<31;write(h,m);}return true;} // DeleteMenuItem: the flags of the items after it move up
			case 0x935:{int before=(short)memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;insertMenu(h,before);return true;}
			case 0x936:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;int h=menuHandle(id);if(h!=0){menuBar.remove((Integer)h);hierarchical.remove((Integer)h);changed();}return true;} // DeleteMenu
			case 0x937:menuBarDrawn=true;menuBarHatched=false;dirty=true;flush();return true; // DrawMenuBar: HyperCard's patch hatches the bar after it in background mode
			case 0x81d:dirty=true;flush();return true;
			case 0x934:menuBar.clear();hierarchical.clear();changed();return true;
			case 0x9c0:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getNewMenuBar(id));return true;}
			case 0x93b:memory.writeLong(cpu.a[7],tb.api.createHandle(menuListBytes(menuBar,hierarchical)));return true; // GetMenuBar: a copy of MenuList
			case 0x93c:setMenuBar(pop(cpu));return true;
			case 0x938:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;hiliteTitle(id);return true;} // HiliteMenu
			case 0x93d:{cpu.a[7]+=4;EventManager68k.Event e=tb.events.pendingMenuEvent;tb.events.clickTracked();int result=0;if(e!=null&&programMenu(e.menuId)){result=(e.menuId<<16)|(e.menuItem&0xffff);}tb.events.pendingMenuEvent=null;
				if(e!=null&&e.probe){tb.events.endMenuProbe();memory.writeLong(cpu.a[7],0);if(Mac68kApi.TRACE)System.err.println("[68K] MenuSelect for the host menu "+e.menuId+" being opened -> 0");return true;}
				if(e!=null&&e.menuId>0)noteMenuSelect();
				if(result!=0&&!tb.desk.ownsMenu(e.menuId)&&!pullDown(e.menuId,e.menuItem))result=0; // the menu "pulled down": the program's MBDF and MBarHook see it
				if(e!=null&&e.menuId!=-1&&tb.desk.ownsMenu(e.menuId)){tb.desk.systemMenu(cpu,(e.menuId<<16)|(e.menuItem&0xffff));result=0;} // a desk accessory's menu: MenuSelect calls SystemMenu and returns 0
				if(Mac68kApi.TRACE)System.err.println("[68K] MenuSelect event "+(e==null?"none":e.menuId+"/"+e.menuItem)+" -> $"+Integer.toHexString(result));
				memory.writeLong(MENU_DISABLE,e!=null&&programMenu(e.menuId)?(e.menuId<<16)|(e.menuItem&0xffff):0); // a disabled item too (MenuChoice)
				memory.writeLong(cpu.a[7],result);return true;}
			case 0x93e:{int ch=memory.readWord(cpu.a[7])&0xff;cpu.a[7]+=2;int result=menuKey(ch);memory.writeLong(MENU_DISABLE,result);
				if(result!=0&&tb.desk.ownsMenu((short)(result>>>16))){tb.desk.systemMenu(cpu,result);result=0;}
				memory.writeLong(cpu.a[7],result);return true;}
			case 0x94c:cpu.a[7]+=2;return true;
			// SetMenuFlash: the host menu bar does not blink chosen items, but the count goes into the MenuFlash global ($A24) the ROM keeps it in, for a program that reads it back
			case 0x94a:memory.writeWord(0xa24,memory.readWord(cpu.a[7]));cpu.a[7]+=2;return true;
			case 0x947:{int text=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Menu m=read(h);if(m!=null&&item>=1&&item<=m.items.size()){m.items.get(item-1).text=tb.readPascal(text);write(h,m);}return true;}
			case 0x946:{int text=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Menu m=read(h);tb.writePascal(text,m!=null&&item>=1&&item<=m.items.size()?m.items.get(item-1).text:"",255);return true;}
			case 0x93a:case 0x939:{int item=(short)memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Menu m=read(h);if(m!=null&&item>=0&&item<=31){if(trap==0x939)m.enableFlags|=1<<item;else m.enableFlags&=~(1<<item);write(h,m);}return true;}
			case 0x945:{boolean checked=memory.readByte(cpu.a[7])!=0;int item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setField(h,item,2,checked?0x12:0);return true;}
			case 0x944:{int mark=memory.readWord(cpu.a[7])&0xff,item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setField(h,item,2,mark);return true;}
			case 0x943:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;memory.writeWord(out,getField(h,item,2));return true;}
			case 0x940:{int icon=memory.readWord(cpu.a[7])&0xff,item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setField(h,item,0,icon);return true;}
			case 0x93f:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;memory.writeWord(out,getField(h,item,0));return true;}
			case 0x942:{int style=memory.readWord(cpu.a[7])&0xff,item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setField(h,item,3,style);return true;}
			case 0x941:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;memory.writeWord(out,getField(h,item,3)&0xff);return true;} // GetItemStyle: the ROM stores a word with the Style in its low byte; C glue may narrow it and custom CDEFs may read the word
			case 0x84f:{int cmd=memory.readWord(cpu.a[7])&0xff,item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setField(h,item,1,cmd);return true;}
			case 0x84e:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;memory.writeWord(out,getField(h,item,1));return true;}
			case 0x950:{int h=pop(cpu);Menu m=read(h);memory.writeWord(cpu.a[7],m==null?0:m.items.size());return true;}
			case 0x949:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2; // GetMenuHandle
				// System 7 keeps the application menu in MenuList itself (at the right end of the bar): HyperCard's externals see System 7.5.5
				// and getApplicationMenuItems got NIL. (A program's own VM keeps the Mac OS 8.5 rule.)
				boolean system7=tb.systemVersion()<0x0850&&tb.app==null&&tb.stack!=null;
				boolean applicationMenu=id==APPLICATION_MENU_ID&&(system7||systemMenuList!=0&&memory.readLong(0xa1c)==systemMenuList);
				memory.writeLong(cpu.a[7],applicationMenu?applicationMenu():menuHandle(id));return true;}
			case 0x948:{int h=pop(cpu);if(customMdef(h)){sizeInWMgrPort(h);return true;}Menu m=read(h);if(m!=null){write(h,m);int p=memory.readLong(h);if(p!=0){memory.writeWord(p+2,0);memory.writeWord(p+4,0);}calcMenuSize(h,m);}return true;} // CalcMenuSize: menuWidth/menuHeight from the current items, or a custom MDEF's mSizeMsg
			case 0x808:{mbdfResId=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;mbdfHandle=0;changed();return true;} // InitProcMenu
			case 0xa66:memory.writeLong(cpu.a[7],memory.readLong(MENU_DISABLE));return true; // MenuChoice: the menu and item last chosen (MenuDisable)
			case 0x80b:return popUpMenuSelect(cpu);
			case 0xa60:{int item=(short)memory.readWord(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;deleteMcEntries(id,item);return true;} // DeleteMCEntries
			case 0xa61:memory.writeLong(cpu.a[7],getMcInfo());return true;                        // GetMCInfo: a copy of the table
			case 0xa62:{int h=pop(cpu);setMcInfo(h);return true;}                                 // SetMCInfo
			case 0xa63:{int h=pop(cpu);if(h!=0)tb.api.disposeHandle(h);return true;}             // DisposeMCInfo
			case 0xa64:{int item=(short)memory.readWord(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;memory.writeLong(cpu.a[7],getMcEntry(id,item));return true;} // GetMCEntry
			case 0xa65:{int table=memory.readLong(cpu.a[7]),count=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;for(int i=0;i<count;i++)putMcEntry(memory.readBytes(table+i*MC_ENTRY,MC_ENTRY));writeMcTable();return true;} // SetMCEntries(numEntries, menuCEntries)
			case 0x9b5:return tb.desk.handle(cpu,0x9b5); // SystemMenu
			default:return false;
		}
	}

	// ------------------------------------------------------------------ menu colour table (IM V-231)

	/** MCEntry: mctID, mctItem, four RGBColors, mctReserved; the table ends with an entry whose mctID is mctLastIDIndic (-99). */
	private static final int MC_ENTRY=30,MC_LAST=-99,MC_ALL_ITEMS=-98;
	/** The program's entries in their order (the terminator is not kept here). */
	private final List<byte[]> mcEntries=new ArrayList<byte[]>();
	/** A 'mctb' resource (entry count, then MCEntry records) into the menu colour table, as InitMenus (ID 0) and GetMenu do. */
	private void loadMenuColors(int id) throws M68kCpuException {
		ResourceFile68k.Resource r=tb.resources.find(0x6d637462,id);if(r==null)return;byte[] d=tb.resources.data(r);if(d==null||d.length<2)return;
		int count=(d[0]&0xff)<<8|(d[1]&0xff);
		for(int i=0;i<count&&2+(i+1)*MC_ENTRY<=d.length;i++)putMcEntry(java.util.Arrays.copyOfRange(d,2+i*MC_ENTRY,2+(i+1)*MC_ENTRY));
		writeMcTable();
	}
	/** The Handle MenuCInfo ($D50) holds, rewritten after each change (GetMCEntry pointers point into it). */
	private int mcTable;
	private static int mcId(byte[] e){return (short)(((e[0]&0xff)<<8)|(e[1]&0xff));}
	private static int mcItem(byte[] e){return (short)(((e[2]&0xff)<<8)|(e[3]&0xff));}
	private byte[] mcBytes(){byte[] out=new byte[(mcEntries.size()+1)*MC_ENTRY];int o=0;for(byte[] e:mcEntries){System.arraycopy(e,0,out,o,MC_ENTRY);o+=MC_ENTRY;}out[o]=(byte)(MC_LAST>>8);out[o+1]=(byte)MC_LAST;return out;}
	private void writeMcTable() throws M68kCpuException {
		byte[] bytes=mcBytes();
		if(mcTable!=0)tb.api.disposeHandle(mcTable);
		mcTable=tb.api.newHandleBlock(bytes.length);memory.load(memory.readLong(mcTable),bytes);memory.writeLong(0xd50,mcTable);
	}
	/** GetMCInfo: a new Handle with a copy of the table (Ultima III stops with "error while attempting to GetMCInfo" on nil). */
	private int getMcInfo() throws M68kCpuException {byte[] bytes=mcBytes();int h=tb.api.newHandleBlock(bytes.length);memory.load(memory.readLong(h),bytes);return h;}
	private void setMcInfo(int h) throws M68kCpuException {
		mcEntries.clear();
		byte[] data=h==0?null:tb.api.handleContents(h);
		if(data!=null)for(int o=0;o+MC_ENTRY<=data.length;o+=MC_ENTRY){byte[] e=java.util.Arrays.copyOfRange(data,o,o+MC_ENTRY);if(mcId(e)==MC_LAST)break;mcEntries.add(e);}
		writeMcTable();
	}
	private void putMcEntry(byte[] e){
		if(mcId(e)==MC_LAST)return;
		for(int i=0;i<mcEntries.size();i++)if(mcId(mcEntries.get(i))==mcId(e)&&mcItem(mcEntries.get(i))==mcItem(e)){mcEntries.set(i,e);return;}
		mcEntries.add(e);
	}
	private void deleteMcEntries(int id,int item) throws M68kCpuException {
		mcEntries.removeIf(e->mcId(e)==id&&(item==MC_ALL_ITEMS||mcItem(e)==item));
		writeMcTable();
	}
	/** GetMCEntry: the entry in the live table, or nil (valid until the table changes, as documented). */
	private int getMcEntry(int id,int item) throws M68kCpuException {
		for(int i=0;i<mcEntries.size();i++)if(mcId(mcEntries.get(i))==id&&mcItem(mcEntries.get(i))==item){if(mcTable==0)writeMcTable();return memory.readLong(mcTable)+i*MC_ENTRY;}
		return 0;
	}

	private int pop(M68kCpu cpu) throws M68kCpuException {int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return h;}

	// ------------------------------------------------------------------ MenuInfo records

	/** Serializes a menu into a Handle in the classic MenuInfo layout. */
	private int store(Menu m,int handle) throws M68kCpuException {
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		out.write(m.id>>>8);out.write(m.id);out.write(0);out.write(0);out.write(0);out.write(0);out.write(0);out.write(0);out.write(0);out.write(0);
		out.write(m.enableFlags>>>24);out.write(m.enableFlags>>>16);out.write(m.enableFlags>>>8);out.write(m.enableFlags);
		writePascal(out,m.title);
		for(Item item:m.items){writePascal(out,item.text);out.write(item.icon);out.write(item.cmd);out.write(item.mark);out.write(item.style);}
		out.write(0);
		byte[] bytes=out.toByteArray();
		int width=Fonts68k.width(Fonts68k.font(0,12,0),m.title)+16,height=m.items.size()*19;
		bytes[2]=(byte)(width>>8);bytes[3]=(byte)width;bytes[4]=(byte)(height>>8);bytes[5]=(byte)height;
		// An existing record keeps its menuProc, a custom one and also ZERO: only NewMenu/GetMenu fill it in, SetMenuItemText/EnableItem
		// leave it alone. HyperCard takes a zero menuProc as "this standard menu is not live" - after `delete menu "File"` it reloads
		// the bare MENU resource into its table, and `create menu "File"` is allowed only
		// while that copy's menuProc is still zero; its idle-time SetMenuItemText/EnableItem on the copy used to make it "exist" again.
		int mdef=handle==0?standardMdef():memory.readLong(memory.readLong(handle)+6);
		bytes[6]=(byte)(mdef>>24);bytes[7]=(byte)(mdef>>16);bytes[8]=(byte)(mdef>>8);bytes[9]=(byte)mdef;
		if(handle!=0){ // a custom MDEF keeps its own sizes, and whatever the program stored after the item list (some colour
			// pop-up keeps the icon's colour table there, like the ROM, which inserts items in place and moves the rest along)
			int p=memory.readLong(handle),size=tb.api.handleSize(handle),tail=itemListEnd(p,size);
			if(mdef!=standardMdef()){System.arraycopy(memory.readBytes(p+2,4),0,bytes,2,4);}
			if(tail>0&&tail<size){byte[] rest=memory.readBytes(p+tail,size-tail),all=new byte[bytes.length+rest.length];System.arraycopy(bytes,0,all,0,bytes.length);System.arraycopy(rest,0,all,bytes.length,rest.length);bytes=all;}}
		if(handle==0)handle=tb.api.createHandle(bytes);else tb.api.loadHandle(handle,bytes);
		menusById.put(m.id,handle);changed();
		return handle;
	}
	private void writePascal(ByteArrayOutputStream out,String s){byte[] b=tb.encoding.encodeReplacing(s==null?"":s);int n=Math.min(255,b.length);out.write(n);out.write(b,0,n);}

	/** Offset just past the item list's terminating zero byte (0 when the record is malformed). */
	private int itemListEnd(int p,int size) throws M68kCpuException {
		if(p==0||size<15)return 0;int at=p+14,end=p+size;int n=memory.readByte(at);at+=1+n;
		while(at<end){int len=memory.readByte(at);if(len==0)return at+1-p;if(at+1+len+4>end)return 0;at+=1+len+4;}
		return 0;
	}
	/** Parses the MenuInfo record behind a Handle. */
	Menu read(int handle) throws M68kCpuException {
		if(handle==0)return null;int p=memory.readLong(handle);if(p==0)return null;int size=tb.api.handleSize(handle);if(size<15)return null;
		Menu m=new Menu();m.id=(short)memory.readWord(p);m.enableFlags=memory.readLong(p+10);
		int at=p+14;int n=memory.readByte(at);m.title=tb.decode(memory.readBytes(at+1,n));at+=1+n;
		int end=p+size;
		while(at<end){int len=memory.readByte(at);if(len==0)break;if(at+1+len+4>end)break;Item item=new Item();item.text=tb.decode(memory.readBytes(at+1,len));at+=1+len;item.icon=memory.readByte(at);item.cmd=memory.readByte(at+1);item.mark=memory.readByte(at+2);item.style=memory.readByte(at+3);at+=4;m.items.add(item);}
		return m;
	}
	private void write(int handle,Menu m) throws M68kCpuException {store(m,handle);}
	/** Carbon's SetMenuTitle / SetMenuTitleWithCFString: the title in the record (which grows or shrinks with it). */
	void setTitle(int handle,String title) throws M68kCpuException {Menu m=read(handle);if(m!=null){m.title=title;write(handle,m);}}

	/** GetMenu: like the ROM, returns the (shared) 'MENU' resource Handle itself, so a second GetMenu
	 *  yields the same menu (with whatever the program appended) until it is released with
	 *  ReleaseResource/DisposeMenu, after which the menu starts over from the resource data. */
	/** GetMenuHandle: the menu with that ID in the current menu list - the bar and the hierarchical portion - or NIL, as the
	 *  ROM searches (HyperCard inserts private copies of its 'MENU' resources and expects the copy back, not the resource it
	 *  got from GetMenu). Menus made but never inserted are NOT found: Photoshop 3 builds its menus with NewMenu, clears the
	 *  bar and inserts each menu whose GetMenuHandle is NIL, so answering the made menu left it with an empty menu bar. */
	int menuHandle(int id){
		// XCMD mode: an external's own menu comes before HyperCard's menu of the same ID. BagOtricks' HPopupMenu numbers its submenus
		// 1, 2 … and after the pop-up GetMenuHandle + DeleteMenu + DisposeMenu's them - it took HyperCard's File menu (ID 2) out of
		// the bar for the rest of the session. The demo works in HyperCard, so the external must get its own menu back.
		try{int hyperCards=0;
			for(int h:menuBar){Menu m=read(h);if(m!=null&&m.id==id){if(!hyperCardMenus.containsKey(h))return h;if(hyperCards==0)hyperCards=h;}}
			for(int h:hierarchical){Menu m=read(h);if(m!=null&&m.id==id)return h;}
			if(hyperCards!=0)return hyperCards;}
		catch(M68kCpuException e){}
		return 0;
	}
	/** As {@link #menuHandle}, then any menu made with that ID (NewMenu/GetMenu) even if not inserted: the pop-up control's own lookup. */
	int anyMenuHandle(int id){int h=menuHandle(id);if(h!=0)return h;Integer made=menusById.get(id);return made==null?0:made;}

	/** Menu definitions registered by ID (CarbonLib's RegisterMenuDefinition): a Handle whose master pointer is the definition's entry. */
	private final java.util.Map<Integer,Integer> registeredDefinitions=new java.util.HashMap<Integer,Integer>();
	void registerDefinition(int id,int handle){if(handle==0)registeredDefinitions.remove(id);else registeredDefinitions.put(id,handle);}
	private int getMenu(int id) throws M68kCpuException {
		ResourceFile68k.Resource res=tb.resources.find(0x4d454e55,id);
		if(res==null)return 0;
		int handle=tb.resources.handleFor(res);
		if(handle==0||memory.readLong(handle)==0||tb.api.handleSize(handle)<14)return 0;
		int p=memory.readLong(handle);
		if(mdefResolved.add(handle)){ // menuProc: the resource carries the MDEF's resource ID (word), the ROM replaces it with the MDEF's Handle
			int mdefId=(short)memory.readWord(p+6);Integer registered=mdefId==0?null:registeredDefinitions.get(mdefId);
			ResourceFile68k.Resource mdefRes=mdefId==0||registered!=null?null:tb.resources.find(0x4d444546,mdefId);
			int mdef=registered!=null?registered:mdefRes==null?0:tb.resources.handleFor(mdefRes);
			if(Mac68kApi.TRACE&&mdefId!=0)System.err.println("[68K] GetMenu "+id+": MDEF "+mdefId+(mdef==0?" not found":" at $"+Integer.toHexString(mdef)));
			memory.writeLong(p+6,mdef!=0?mdef:standardMdef());}
		Menu m=read(handle);if(m==null)return 0;
		if(customMdef(handle))customSize(handle);else calcMenuSize(handle,m); // the ROM's GetMenu sizes the menu; resources carry 0 (HyperCard only inserts menus with a size)
		menusById.putIfAbsent(m.id,handle);changed(); // an inserted menu with this ID stays the one GetMenuHandle/DeleteMenu find
		return handle;
	}

	/** The standard MDEF's Handle (menuProc of every menu): the System file's 'MDEF' 0 stub, so HomeResFile and
	 *  GetResInfo work on it as on a real Mac. Programs treat a zero menuProc as an unusable menu (HyperCard skips
	 *  such menus when it rebuilds its menu bar), so a bare RTS stands in should the resource be missing. */
	private int standardMdef;
	private int standardMdef() throws M68kCpuException {
		if(standardMdef==0){ResourceFile68k.Resource res=tb.resources.system.get(0x4d444546,0);if(res!=null)standardMdef=tb.resources.handleFor(res);}
		if(standardMdef==0)standardMdef=tb.api.createHandle(new byte[]{0x4e,0x75});
		return standardMdef;
	}

	/** CalcMenuSize: menuWidth/menuHeight of the record (the standard MDEF's sizes; a resource's own non-zero values are kept). */
	private void calcMenuSize(int handle,Menu m) throws M68kCpuException {
		int p=memory.readLong(handle);if(p==0)return;
		if(memory.readWord(p+2)==0){int width=Fonts68k.width(Fonts68k.font(0,12,0),m.title)+16;
			for(Item item:m.items)width=Math.max(width,Fonts68k.width(Fonts68k.font(0,12,0),item.text)+40);memory.writeWord(p+2,width);}
		if(memory.readWord(p+4)==0)memory.writeWord(p+4,m.items.size()*19);
	}

	/** ReleaseResource on a menu Handle: forget it so GetMenu reloads the resource next time. */
	void resourceReleased(int handle){if(menusById.containsValue(handle)){remove(handle);changed();}}
	/**
	 * A Handle the program disposed (DisposeHandle, also DisposeMenu's): whatever this manager remembered about it is
	 * stale. HyperCard's "reset menubar" deletes each menu, detaches and disposes its Handle and calls GetMenu again;
	 * the fresh Handle often gets the old address back, and with the old bookkeeping still in place GetMenu skipped
	 * resolving its menuProc (a zero menuProc makes HyperCard leave that menu out - the Go menu vanished with its
	 * Message item) and GetMenuHandle answered the dead Handle.
	 */
	void handleDisposed(int handle){
		if(menusById.containsValue(handle)||mdefResolved.contains(handle)||pictureCache.containsKey(handle)||menuBar.contains(handle)||hierarchical.contains(handle)){
			boolean shown=menuBar.contains(handle)||hierarchical.contains(handle);remove(handle);if(shown)changed();}
	}

	private void remove(int handle){mdefTextSize.remove(handle);menuBar.remove((Integer)handle);hierarchical.remove((Integer)handle);menusById.values().remove(handle);mdefResolved.remove(handle);pictureCache.remove(handle);}
	/** Menu Handles whose menuProc already holds an MDEF Handle (GetMenu hands out the resource Handle itself, so a second call must not reread the field as an ID). */
	private final java.util.Set<Integer> mdefResolved=new java.util.HashSet<Integer>();

	private void insertMenu(int handle,int before) throws M68kCpuException {
		Menu m=read(handle);if(m==null)return;menusById.put(m.id,handle);
		if(before==-1){if(!hierarchical.contains(handle))hierarchical.add(handle);changed();return;}
		menuBar.remove((Integer)handle);
		Integer beforeHandle=before==0?null:menusById.get(before);
		if(beforeHandle==null||!menuBar.contains(beforeHandle))menuBar.add(handle);else menuBar.add(menuBar.indexOf(beforeHandle),handle);
		changed();
	}

	private int getNewMenuBar(int id) throws M68kCpuException {
		ResourceFile68k.Resource res=tb.resources.find(0x4d424152,id);
		if(res==null||res.length()<2)return 0;byte[] rd=tb.resources.data(res);
		int count=MacApp68k.u2(rd,0);List<Integer> bar=new ArrayList<Integer>();
		for(int i=0;i<count&&2+i*2+1<rd.length;i++){int menuId=(short)MacApp68k.u2(rd,2+i*2);int h=getMenu(menuId);if(h!=0)bar.add(h);}
		return tb.api.createHandle(menuListBytes(bar,new ArrayList<Integer>()));
	}
	/** SetMenuBar: installs the menus listed in a MenuList copy (from GetMenuBar/GetNewMBar, or built by the program). */
	private void setMenuBar(int h) throws M68kCpuException {
		int p=h==0?0:memory.readLong(h);if(p==0)return;
		int size=tb.api.handleSize(h),last=memory.readWord(p);if(last<0||last+6>size)return;
		List<Integer> bar=new ArrayList<Integer>();
		for(int at=6;at<=last;at+=6){int mh=memory.readLong(p+at);if(mh!=0&&read(mh)!=null&&!bar.contains(mh))bar.add(mh);}
		List<Integer> hier=new ArrayList<Integer>();
		int hp=p+last+6;
		if(hp+6<=size){int lastH=memory.readWord(hp);
			for(int at=6;at<=lastH&&hp+at+4<=size;at+=6){int mh=memory.readLong(hp+at);if(mh!=0&&read(mh)!=null&&!hier.contains(mh))hier.add(mh);}}
		menuBar.clear();menuBar.addAll(bar);hierarchical.clear();hierarchical.addAll(hier);
		for(int mh:bar){Menu m=read(mh);if(m!=null)menusById.put(m.id,mh);}
		for(int mh:hier){Menu m=read(mh);if(m!=null)menusById.put(m.id,mh);}
		changed();
	}

	private void appendItems(Menu m,String data,int at){appendItems(m,tb.encoding.encodeReplacing(data),at,false);}
	private byte[] pascalBytes(int p) throws M68kCpuException {return p==0?new byte[0]:memory.readBytes(p+1,memory.readByte(p));}
	/**
	 * {@code eachAfter}: InsertMenuItem puts every item of a "one;two" string right after the same item, so they end up
	 * in reverse order - Inside Macintosh says so, and some Save As format pop-ups rely on it by inserting
	 * "PICT;(-" after the built-in formats to get the separator first and PICT after it, which is where its code
	 * expects the translator formats to start (in the other order PICT was taken for a native save).
	 * The metacharacters are read from the bytes, the item text decoded afterwards: a metacharacter's argument is a byte, not a
	 * character (a submenu ID "!"+$80 decoded under KanjiTalk as "\" became $5C, one from $81 merged with the next byte into
	 * a kanji - UxPop's and UxPopDir's hierarchical menus never got their submenus); a Japanese character's trail byte is
	 * never taken for one ('^' is a possible trail byte).
	 */
	private void appendItems(Menu m,byte[] data,int at,boolean eachAfter){
		if(data.length==0)return;
		boolean japanese=tb.encoding==StackTextEncoding.JAPANESE;
		java.util.List<byte[]> parts=new ArrayList<byte[]>();
		for(int i=0,from=0;i<=data.length;i++){
			if(i==data.length||data[i]==';'||data[i]=='\r'){parts.add(java.util.Arrays.copyOfRange(data,from,i));from=i+1;continue;}
			if(japanese&&Toolbox68k.isJapaneseLead(data[i]&0xff)&&i+1<data.length)i++;
		}
		for(byte[] text:parts){
			Item item=new Item();boolean disabled=false;java.io.ByteArrayOutputStream plain=new java.io.ByteArrayOutputStream();
			for(int i=0;i<text.length;i++){
				int c=text[i]&0xff;
				if(japanese&&Toolbox68k.isJapaneseLead(c)&&i+1<text.length){plain.write(c);plain.write(text[++i]);continue;}
				if(c=='^'&&i+1<text.length){item.icon=(text[++i]&0xff)-'0';continue;}
				if(c=='!'&&i+1<text.length){item.mark=text[++i]&0xff;continue;}
				if(c=='<'&&i+1<text.length){char st=(char)(text[++i]&0xff);item.style|=st=='B'?1:st=='I'?2:st=='U'?4:st=='O'?8:st=='S'?16:0;continue;}
				if(c=='/'&&i+1<text.length){item.cmd=text[++i]&0xff;continue;}
				if(c=='('&&plain.size()==0){disabled=true;continue;}
				plain.write(c);
			}
			item.text=tb.decode(plain.toByteArray());
			if(item.text.startsWith("-")&&plain.size()>=1&&item.text.trim().equals("-")){item.text="-";disabled=true;}
			m.items.add(Math.min(at,m.items.size()),item);
			int index=Math.min(at,m.items.size()-1)+1;
			if(index<=31){int below=m.enableFlags&((1<<index)-1),above=m.enableFlags&~((1<<index)-1);m.enableFlags=below|(above<<1)|(1<<index);} // the items after it keep their flags, the new one is enabled
			if(disabled&&index<=31)m.enableFlags&=~(1<<index);
			if(!eachAfter)at++;
		}
	}

	/**
	 * MenuDispatch ($A825, selector word in D0: argument words in the high byte). InsertFontResMenu(menu, afterItem,
	 * scriptFilter) and InsertIntlResMenu(menu, type, afterItem, scriptFilter) are InsertResMenu without the resources of
	 * other scripts (the filter is ignored: the installed fonts are the fonts of this System). MenuEvent (12) is MenuKey with
	 * the item modifiers of Mac OS 8.5 (REALbasic's IDE takes every command key through it).
	 */
	private boolean menuDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff,args=(selector>>>8)*2;
		switch(selector&0xff){
			case 0:{int after=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=args;appendResources(h,0x464f4e44,after);return true;}
			case 1:{int after=(short)memory.readWord(cpu.a[7]+2),type=memory.readLong(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+8);cpu.a[7]+=args;appendResources(h,type,after);return true;}
			case 12:{int event=memory.readLong(cpu.a[7]);cpu.a[7]+=args;int result=menuEvent(event); // MenuEvent(const EventRecord*): the item for a key-down
				if(result!=0&&tb.desk.ownsMenu((short)(result>>>16))){tb.desk.systemMenu(cpu,result);result=0;}
				memory.writeLong(cpu.a[7],result);return true;}
			// Mac OS 8.5 item data (refCons, command IDs, modifiers, encodings, fonts, glyphs), kept beside the MenuInfo as the
			// real Menu Manager keeps it. Acrobat Reader 5.0 finds its command objects through the item refCons: with the
			// VAR left unwritten it read a stale pointer off its stack and called through it.
			case 0x0a:case 0x11:case 0x02:case 0x08:{int v=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=args;
				itemData(h,item,true)[slot(selector&0xff)]=v;memory.writeWord(cpu.a[7],0);return true;} // Set...RefCon/RefCon2/CommandID/TextEncoding
			case 0x0b:case 0x12:case 0x03:case 0x09:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=args;
				int[] d=itemData(h,item,false);if(out!=0)memory.writeLong(out,d==null?0:d[slot(selector&0xff)]);memory.writeWord(cpu.a[7],0);return true;} // Get...
			case 0x04:case 0x0f:case 0x13:{int v=memory.readWord(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=args;
				if((selector&0xff)==0x04)v>>>=8; // SetMenuItemModifiers: a Boolean-sized UInt8 in the high byte
				int[] d=itemData(h,item,true);boolean shown=(selector&0xff)==0x04&&d[slot(0x04)]!=v;d[slot(selector&0xff)]=v;if(shown)changed(); // the host menu shows the modifiers
				memory.writeWord(cpu.a[7],0);return true;} // SetMenuItemModifiers/FontID/KeyGlyph
			case 0x05:case 0x10:case 0x14:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=args;
				int[] d=itemData(h,item,false);int v=d==null?0:d[slot(selector&0xff)];if(out!=0){if((selector&0xff)==0x05)memory.writeByte(out,v);else memory.writeWord(out,v);}memory.writeWord(cpu.a[7],0);return true;} // Get...
			case 0x0d:{int id=(short)memory.readWord(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+2),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=args; // SetMenuItemHierarchicalID: cmd $1B, mark = the menu ID
				setField(h,item,1,id==0?0:0x1b);setField(h,item,2,id);memory.writeWord(cpu.a[7],0);return true;}
			case 0x0e:{int out=memory.readLong(cpu.a[7]),item=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=args;
				if(out!=0)memory.writeWord(out,getField(h,item,1)==0x1b?getField(h,item,2):0);memory.writeWord(cpu.a[7],0);return true;}
			case 0x07:{int out=memory.readLong(cpu.a[7]),type=memory.readLong(cpu.a[7]+4);cpu.a[7]+=args;if(out!=0)memory.writeLong(out,0);if(type!=0)memory.writeByte(type,0);memory.writeWord(cpu.a[7],0);return true;} // GetMenuItemIconHandle: none
			default:cpu.a[7]+=args;memory.writeWord(cpu.a[7],0);return true;
		}
	}
	/** Per menu item: refCon, refCon2, command ID, text encoding, modifiers, font ID, key glyph. */
	private final java.util.Map<Long,int[]> itemData=new java.util.HashMap<Long,int[]>();
	private static int slot(int selector){switch(selector){case 0x0a:case 0x0b:return 0;case 0x11:case 0x12:return 1;case 0x02:case 0x03:return 2;case 0x08:case 0x09:return 3;case 0x04:case 0x05:return 4;case 0x0f:case 0x10:return 5;default:return 6;}}
	private int[] itemData(int menu,int item,boolean create){long k=(long)menu<<16|(item&0xffff);int[] d=itemData.get(k);if(d==null&&create)itemData.put(k,d=new int[7]);return d;}
	/** InsertMenuItem/DeleteMenuItem: the data of the items after {@code item} moves with them. */
	private void shiftItemData(int menu,int item,int delta){
		if(itemData.isEmpty())return;java.util.Map<Long,int[]> moved=new java.util.HashMap<Long,int[]>();
		for(java.util.Iterator<java.util.Map.Entry<Long,int[]>> i=itemData.entrySet().iterator();i.hasNext();){java.util.Map.Entry<Long,int[]> e=i.next();
			long k=e.getKey();if((int)(k>>>16)!=menu)continue;int it=(int)(k&0xffff);if(it<item)continue;i.remove();if(delta<0&&it==item)continue;moved.put((long)menu<<16|((it+delta)&0xffff),e.getValue());}
		itemData.putAll(moved);
	}
	/** AppendResMenu for the popup CDEF's popupUseAddResMenu variation. */
	void appendResourceNames(int handle,int type) throws M68kCpuException {appendResources(handle,type,-1);}
	private void appendResources(int handle,int type,int after) throws M68kCpuException {
		Menu m=read(handle);if(m==null)return;
		List<String> names=new ArrayList<String>();
		if(type==0x464f4e44||type==0x464f4e54)names.addAll(Fonts68k.allNames());
		else if(type==0x73666e74)names.addAll(Fonts68k.outlineNames()); // 'sfnt': the System's TrueType fonts (the System file here holds none)
		else for(String n:tb.resources.resourceNames(type))if(!n.startsWith(".")&&!n.startsWith("%"))names.add(n); // '.'/'%' names are hidden (drivers, private resources)
		java.util.Collections.sort(names,String.CASE_INSENSITIVE_ORDER);
		int at=after<0?m.items.size():Math.min(m.items.size(),after);
		for(String n:names){Item item=new Item();item.text=n;m.items.add(at++,item);}
		write(handle,m);
	}

	private void setField(int handle,int item,int field,int value) throws M68kCpuException {
		Menu m=read(handle);if(m==null||item<1||item>m.items.size())return;Item it=m.items.get(item-1);
		if(field==0)it.icon=value;else if(field==1)it.cmd=value;else if(field==2)it.mark=value;else it.style=value;
		write(handle,m);
	}
	private int getField(int handle,int item,int field) throws M68kCpuException {
		Menu m=read(handle);if(m==null||item<1||item>m.items.size())return 0;Item it=m.items.get(item-1);
		return field==0?it.icon:field==1?it.cmd:field==2?it.mark:it.style;
	}

	// ------------------------------------------------------------------ the program's menu bar definition and MBarHook

	/**
	 * InitProcMenu's resource ID: a menu bar definition of the program's own. The host draws the menus, but a program can depend
	 * on the MBDF and MBarHook calls MenuSelect makes while a menu is pulled down. Flash 4's Windows layer installs 'MBDF' 512
	 * (handed out by its GetResource patch): the title hilite message tells it which menu is open, and its MBarHook then sends
	 * WM_INITMENUPOPUP - only there are the Libraries items made from the Libraries folder (the menu showed the WMNU template's
	 * "libraries" placeholders) and the items enabled or disabled for the moment.
	 */
	private int mbdfResId,mbdfHandle;
	/** MenuList offset of the title the MBDF was last told to hilite (0 none). */
	private int hilitedOffset;
	/** MenuSelect for a choice the host made: the title is hilited, MBarHook is called with the menu's rectangle (a nonzero
	 *  answer cancels), and the item must still be enabled after the program updated the menu. */
	private boolean pullDown(int menuId,int item) throws M68kException {
		hiliteTitle(menuId);
		if(memory.readLong(0xa2c)!=0)dirty=true; // what the hook changed reaches the host bar at the next event call
		if(callMBarHook(menuId)!=0)return false;
		int h=menuHandle(menuId);Menu m=h==0?null:read(h);
		return m==null||item<1||item>m.items.size()||item>31||((m.enableFlags&1)!=0&&(m.enableFlags&(1<<item))!=0);
	}
	/** MBarHook(menuRect) for a menu about to be drawn, as MenuSelect calls it; its answer (0 = go on), 0 without a hook. */
	private int callMBarHook(int menuId) throws M68kException {
		int hook=memory.readLong(0xa2c),h=menuHandle(menuId);if(hook==0||h==0)return 0;
		Menu m=read(h);int index=menuBar.indexOf(h),left=0;int list=memory.readLong(0xa1c);
		if(index>=0&&list!=0&&memory.readLong(list)!=0)left=(short)memory.readWord(memory.readLong(list)+6+6*index+4);
		int rect=memory.allocate(8,2);M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int answer;
		try{int top=MacAppHost.MENU_BAR_HEIGHT,count=m==null?0:m.items.size();
			memory.writeWord(rect,top);memory.writeWord(rect+2,left);memory.writeWord(rect+4,top+16*count+2);memory.writeWord(rect+6,left+200);
			cpu.pushLong(rect);cpu.callPascal(hook,4);answer=cpu.d[0];}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);memory.free(rect);}
		if(Mac68kApi.TRACE)System.err.println("[68K] MBarHook menu "+menuId+" -> "+answer);
		return answer;
	}
	/** Whether the program changes its menus while one is pulled down (MBarHook or a menu bar definition of its own): the host
	 *  then lets it do so when the user opens a menu of the host bar ({@link #prepareOpening}). */
	boolean updatesMenusOnOpening() throws M68kCpuException {return memory.readLong(0xa2c)!=0||mbdfResId!=0;}
	/** The program changed its menus between a host menu choice's mouse-down and MenuSelect: every menu of the host bar it opens
	 *  gets that click first (EventManager68k.postMenuProbe). */
	private boolean adjustsOnClick;
	/** MenuSelect for a choice of the host bar: whether the menus changed since its mouse-down reached the program. */
	private void noteMenuSelect() throws M68kCpuException {
		int[] before=tb.events.menusAtClick;tb.events.menusAtClick=null;
		if(adjustsOnClick||before==null||java.util.Arrays.equals(before,enableState()))return;
		adjustsOnClick=true;dirty=true;
		if(Mac68kApi.TRACE)System.err.println("[68K] the program sets up its menus when the menu bar is clicked");
	}
	/** Each menu's enableFlags in menu bar order, then its check marks (as {@link #noteMenuSelect} compares them). */
	int[] enableState(){
		List<Integer> all=new ArrayList<Integer>(menuBar);int[] state=new int[all.size()*2];
		try{for(int i=0;i<all.size();i++){Menu m=read(all.get(i));if(m==null)continue;state[i*2]=m.enableFlags;int marks=0;
			for(int n=0;n<m.items.size()&&n<31;n++)if(m.items.get(n).mark!=0)marks|=1<<n;state[i*2+1]=marks;}}
		catch(M68kCpuException e){}
		return state;
	}
	boolean hasMenu(int menuId){return menuHandle(menuId)!=0;}
	/** The user opened a menu of the host bar (the VM thread, waiting in WaitNextEvent): the title is hilited and MBarHook
	 *  called as when MenuSelect pulls the menu down, then the title goes back; the changed menus reach the host right away. */
	void prepareOpening(int menuId) throws M68kException {
		if(menuHandle(menuId)==0)return;
		hiliteTitle(menuId);callMBarHook(menuId);hiliteTitle(0);
		dirty=true;flush(); // item changes alone do not mark the bar (the host keeps an unchanged bar as it is)
	}
	/** HiliteMenu (and MenuSelect's title): TheMenu, and the hilite message to the program's MBDF when it has one. */
	private void hiliteTitle(int menuId) throws M68kException {
		memory.writeWord(0xa26,menuId);
		int index=menuId==0?-1:menuBar.indexOf(menuHandle(menuId)),offset=index<0?0:6*(index+1);
		if(offset==hilitedOffset||programMbdf()==0){hilitedOffset=offset;return;}
		if(hilitedOffset!=0)callMbdf(5,0,hilitedOffset);
		if(offset!=0)callMbdf(5,0,1<<16|offset);
		hilitedOffset=offset;
	}
	/** The program's MBDF Handle, fetched with GetResource from 68K code so that a patch of GetResource is honoured. */
	private int programMbdf() throws M68kException {
		if(mbdfResId==0)return 0;
		if(mbdfHandle!=0&&memory.readLong(mbdfHandle)!=0)return mbdfHandle;
		int code=memory.allocate(18,2);int[] words={0x42a7,0x2f3c,0x4d42,0x4446,0x3f3c,mbdfResId&0xffff,0xa9a0,0x201f,0x4e75}; // CLR.L -(SP); MOVE.L #'MBDF',-(SP); MOVE.W #id,-(SP); _GetResource; MOVE.L (SP)+,D0; RTS
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int h;
		try{for(int i=0;i<words.length;i++)memory.writeWord(code+2*i,words[i]);cpu.callPascal(code,0);h=cpu.d[0];}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);memory.free(code);}
		mbdfHandle=h!=0&&memory.readLong(h)!=0?h:0;return mbdfHandle;
	}
	/** pascal long MBDF(short selector, short message, short parameter1, long parameter2), in the Window Manager port. */
	private int callMbdf(int message,int parameter1,int parameter2) throws M68kException {
		int h=programMbdf();if(h==0)return 0;int entry=memory.readLong(h);if(entry==0)return 0;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{cpu.a[7]-=4;cpu.pushWord(mbdfResId&7);cpu.pushWord(message);cpu.pushWord(parameter1);cpu.pushLong(parameter2);
			cpu.callPascal(entry,10);int result=memory.readLong(cpu.a[7]);
			if(Mac68kApi.TRACE)System.err.printf("[68K] MBDF %d message %d param $%x -> %d%n",mbdfResId,message,parameter2,result);
			return result;}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	// ------------------------------------------------------------------ host menu bar

	// ------------------------------------------------------------------ HyperCard's own menus (XCMD mode)

	/** The first menu ID HyperCard gives a menu a script created. */
	static final int SCRIPT_MENU_IDS=2000;
	/** XCMD mode: HyperCard's menus in this VM's menu bar, by MenuHandle (see {@link #syncHyperCardMenus}). */
	private final Map<Integer,HyperCardMenu> hyperCardMenus=new LinkedHashMap<Integer,HyperCardMenu>();
	/**
	 * One of them: HyperZebra's menu, its title, and the record as the external was last given it or last left it; the menu's host
	 * items the record's items stand for ({@link #items}), every host item the menu had while the external had it ({@link #known},
	 * in order, the ones it deleted too) and their texts, so items deleted and later brought back are found again; whether the
	 * external took the menu out of the bar.
	 */
	private static final class HyperCardMenu {
		final Object menu;final String title;Menu shown;
		List<Object> items=new ArrayList<Object>(),known=new ArrayList<Object>();
		final Map<Object,String> texts=new java.util.IdentityHashMap<Object,String>();
		/** The submenus the external hung on items of the menu (cmd $1B), as last shown. */
		final Map<Object,MacAppHost.MenuModel> submenus=new java.util.IdentityHashMap<Object,MacAppHost.MenuModel>();
		boolean outOfBar;
		HyperCardMenu(Object menu,String title){this.menu=menu;this.title=title;}
		/** HyperZebra put another component in an item's place (checking an item makes a check-box item of it). */
		void replaced(Object was,Object now){
			java.util.Collections.replaceAll(items,was,now);java.util.Collections.replaceAll(known,was,now);
			String t=texts.remove(was);if(t!=null)texts.put(now,t);MacAppHost.MenuModel sub=submenus.remove(was);if(sub!=null)submenus.put(now,sub);
		}
		/** The host items as HyperZebra has the menu now: {@link #known} goes on if they are still in its order (the deleted ones in between). */
		void hostItems(List<Object> now,List<String> texts){
			for(int i=0;i<now.size();i++)this.texts.put(now.get(i),texts.get(i));
			int k=0;for(Object o:known)if(k<now.size()&&now.get(k)==o)k++;
			if(k<now.size())known=new ArrayList<Object>(now);
			items=new ArrayList<Object>(now);
		}
	}
	/** Set at the start of each external call: the call's first Menu Manager trap brings HyperCard's menus up to date. */
	private boolean hyperCardMenusStale=true;
	void externalCallStarts(){hyperCardMenusStale=true;}

	/**
	 * XCMD mode: HyperCard's menus (Apple, File, Edit, Go … and the menus scripts made) head this VM's menu bar with their items and
	 * states, as they are in HyperCard's heap: externals find them by title in GetMenuBar's list or by HyperCard's IDs (Apple 1 …
	 * Style 8) and change them with EnableItem/DisableItem/CheckItem/SetItem, which {@link #flush} gives back to HyperZebra's
	 * menus. Dartmouth's MenuHandler XCMD (MenuHandler "Disable","Go","Back") found no menu "Go" before. The menus scripts made
	 * have the IDs HyperCard gives them, from 2000 on (HC 2.2 measured: create menu "Zork", "Frob" = 2000, 2001; after both are
	 * deleted the next one is 2000 again): Rinaldi's MenuItemsList and CreateMenuHelp look menus up in 0..266 and 2000..2101.
	 */
	private void syncHyperCardMenus() throws M68kCpuException {
		hyperCardMenusStale=false;
		List<TTalk.ExternalMenu> menus=TTalk.hyperCardMenusForExternals();
		boolean wasDirty=dirty;
		List<Integer> handles=new ArrayList<Integer>();int nextId=SCRIPT_MENU_IDS;
		for(TTalk.ExternalMenu hm:menus){
			Menu m=new Menu();m.id=hm.id>0?hm.id:nextId++;m.title=hm.title;m.enableFlags=hm.enabled?1:0;
			for(Integer taken=menusById.get(m.id);taken!=null&&!hyperCardMenus.containsKey(taken);taken=menusById.get(m.id))m.id=nextId++; // the external's own menu has that ID: it keeps it
			// with their Command keys, marks and styles: GetMenuKey's GetItemCmd of Edit and Go gave nothing
			for(int i=0;i<hm.items.size();i++){Item item=new Item();item.text=hm.items.get(i);item.mark=hm.checkedItems.get(i)?markByte(hm.marks.get(i)):0;
				char key=hm.commands.get(i);item.cmd=key>=0x20&&key<0x7f?key:0;item.style=hm.styles.get(i)&0xff;
				int sub=hm.submenus.get(i);if(sub!=0){item.cmd=0x1b;item.mark=sub&0xff;} // hierarchical since an earlier external made it so
				m.items.add(item);
				if(hm.enabledItems.get(i)||i>=31)m.enableFlags|=i<31?1<<(i+1):0;}
			Integer existing=null;
			for(Map.Entry<Integer,HyperCardMenu> e:hyperCardMenus.entrySet())if(e.getValue().menu==hm.menu)existing=e.getKey();
			if(existing!=null&&(tb.api.handleSize(existing)<15||memory.readLong(existing)==0)){hyperCardMenus.remove(existing);existing=null;} // the external disposed of it
			int h=store(m,existing==null?0:existing);
			HyperCardMenu record=existing==null?new HyperCardMenu(hm.menu,hm.title):hyperCardMenus.get(existing);
			record.shown=read(h);record.outOfBar=false;record.hostItems(hm.components,hm.items);hyperCardMenus.put(h,record);handles.add(h);
		}
		// a menu the external took out of the bar stays in its record (Menu "Switch" puts the bar back with SetMenuBar later)
		for(Integer h:new ArrayList<Integer>(hyperCardMenus.keySet()))if(!handles.contains(h)){HyperCardMenu r=hyperCardMenus.get(h);
			if(r.outOfBar&&tb.api.handleSize(h)>=15&&memory.readLong(h)!=0)continue;
			hyperCardMenus.remove(h);remove(h);}
		List<Integer> own=new ArrayList<Integer>();for(int h:menuBar)if(!hyperCardMenus.containsKey(h))own.add(h);
		menuBar.clear();menuBar.addAll(handles);menuBar.addAll(own);
		writeMenuList();
		dirty=wasDirty;
	}
	/** What the external did to one of HyperCard's menus since it was given it goes to HyperZebra's menu. */
	private void hyperCardMenuChanged(int handle,Menu now){
		HyperCardMenu record=hyperCardMenus.get(handle);Menu was=record==null?null:record.shown;if(was==null)return;
		if(((now.enableFlags^was.enableFlags)&1)!=0)TTalk.externalChangedHyperCardMenu(record.menu,0,(now.enableFlags&1)!=0,null,null);
		if(now.items.size()!=was.items.size()){rearranged(record,now);record.shown=now;return;}
		for(int i=1;i<=Math.min(now.items.size(),was.items.size())&&i<=record.items.size();i++){
			Item a=was.items.get(i-1),b=now.items.get(i-1);
			Boolean enabled=null;String text=null,mark=null;Character command=null;Integer style=null;
			if(i<=31&&((now.enableFlags^was.enableFlags)&(1<<i))!=0)enabled=(now.enableFlags&(1<<i))!=0;
			// a hierarchical item's mark is its submenu's ID, not a mark (PowerMenus' Properties checked "Bring Closer")
			String markWas=a.cmd==0x1b?"":markText(a.mark),markNow=b.cmd==0x1b?"":markText(b.mark);
			if(!markWas.equals(markNow))mark=markNow;
			if(!a.text.equals(b.text)&&!b.text.startsWith("-"))text=b.text;
			int cmdWas=a.cmd>=0x20?a.cmd:0,cmdNow=b.cmd>=0x20?b.cmd:0;if(cmdWas!=cmdNow)command=(char)cmdNow;
			if(a.style!=b.style)style=b.style;
			Object item=record.items.get(i-1),placed=item;
			if(enabled!=null||mark!=null||text!=null||command!=null||style!=null)placed=TTalk.externalChangedHyperCardItem(record.menu,item,enabled,text,mark,command,style);
			// an item the external made hierarchical (SetItemCmd $1B, the submenu's ID as its mark) gets that submenu: Properties
			// hangs its button and field styles on New Button and New Field, and its doMenu handler makes the object chosen
			MacAppHost.MenuModel submenu=null;
			if(b.cmd==0x1b){Integer sub=menusById.get(b.mark);
				try{Menu subMenu=sub==null||hyperCardMenus.containsKey(sub)?null:read(sub);if(subMenu!=null)submenu=model(subMenu,1);}catch(M68kCpuException e){}}
			MacAppHost.MenuModel shownSub=record.submenus.get(placed);
			if(submenu==null?shownSub!=null:!submenu.sameAs(shownSub)){
				TTalk.externalHierarchicalHyperCardItem(record.menu,placed,submenu);
				if(submenu==null)record.submenus.remove(placed);else record.submenus.put(placed,submenu);}
			if(placed!=item)record.replaced(item,placed);
		}
		record.shown=now;
	}
	/**
	 * Items were deleted from or inserted into one of HyperCard's menus (DeleteMenuItem, InsertMenuItem, or the record put back from a
	 * copy): each item is matched by its text to the host items the menu had, in order, so deleted ones leave HyperZebra's menu and
	 * come back when the external restores them; an item HyperCard never had is a new item that sends doMenu.
	 */
	private void rearranged(HyperCardMenu record,Menu now){
		List<Object> arrangement=new ArrayList<Object>();int from=0;
		boolean[] enabled=new boolean[now.items.size()],checked=new boolean[now.items.size()];
		for(int i=0;i<now.items.size();i++){
			Item item=now.items.get(i);Object found=null;
			for(int k=from;k<record.known.size()&&found==null;k++){Object o=record.known.get(k);String t=record.texts.get(o);
				if(t!=null&&(t.equals(item.text)||t.startsWith("-")&&item.text.startsWith("-"))){found=o;from=k+1;}}
			arrangement.add(found!=null?found:item.text);
			enabled[i]=i>=31||(now.enableFlags&(1<<(i+1)))!=0;checked[i]=item.mark!=0&&item.cmd!=0x1b;
		}
		List<Object> placed=TTalk.externalArrangedHyperCardMenu(record.menu,arrangement,enabled,checked,record.known); // HyperZebra's own items it never saw stay
		if(placed.size()!=arrangement.size())return;
		for(int i=0;i<placed.size();i++){Object o=placed.get(i);
			if(!record.texts.containsKey(o)){record.texts.put(o,now.items.get(i).text);int at=record.known.indexOf(i>0?placed.get(i-1):null);record.known.add(at+1,o);}}
		record.items=placed;
	}
	/** XCMD mode: HyperCard's menus the external took out of the bar (DeleteMenu) leave HyperZebra's, and come back when they are in it again. */
	private void hyperCardMenusInBar(){
		Object previous=null;
		for(int h:menuBar){HyperCardMenu r=hyperCardMenus.get(h);if(r==null)continue;
			if(r.outOfBar){r.outOfBar=false;TTalk.externalRestoredHyperCardMenu(r.menu,previous);}
			previous=r.menu;}
		for(Map.Entry<Integer,HyperCardMenu> e:hyperCardMenus.entrySet())
			if(!e.getValue().outOfBar&&!menuBar.contains(e.getKey())){e.getValue().outOfBar=true;TTalk.externalRemovedHyperCardMenu(e.getValue().menu);}
	}
	/** The HyperCard menu after which an external's own menu stands in the bar (its place in HyperZebra's bar), null when none is before it. */
	private Object hyperCardMenuBefore(int handle){
		Object previous=null;
		for(int h:menuBar){if(h==handle)return previous;HyperCardMenu r=hyperCardMenus.get(h);if(r!=null)previous=r.menu;}
		return previous;
	}

	/** Rebuilds the host menu bar when something changed (called from DrawMenuBar and the event loop). */
	/** kApplicationMenuID: the Process Manager's application menu (the list of programs at the right end of the menu bar). */
	static final int APPLICATION_MENU_ID=-16489;
	/** SystemMenuList (low-memory global $286, Mac OS 8.5 and later): a MenuList of the system's own menus, here the
	 *  application menu alone, and that menu's Handle (0 until first asked for). */
	private int systemMenuList,applicationMenu;
	/**
	 * Since Mac OS 8.5 the system menus (Help, Keyboard, application menu) live in their own menu list at $286, not in MenuList:
	 * GetMenuHandle(kApplicationMenuID) finds the application menu only while a program has made that list the current one.
	 * A-Dock does exactly that (LMSetMenuList(LMGetSystemMenuList()), GetMenuHandle(-16489), LMSetMenuList back) and reads the
	 * running programs from the menu's items 5 on - their names, the front one checked - to show them in its dock.
	 */
	void initializeLowMemory() throws M68kCpuException {
		if(systemMenuList!=0)return;
		byte[] b=new byte[6+6+6];putWord(b,0,6);putWord(b,2,0);putWord(b,4,0);putWord(b,12,0);
		systemMenuList=tb.api.createHandle(b);memory.writeLong(0x286,systemMenuList);
	}
	/** The application menu, its items brought up to date: Hide <front>, Hide Others, Show All, a line, then the programs (see Toolbox68k.applicationMenuEntries). */
	private int applicationMenu() throws M68kCpuException {
		Menu m=new Menu();m.id=APPLICATION_MENU_ID;
		java.util.List<String[]> entries=tb.applicationMenuEntries();
		if(tb.app==null&&tb.stack!=null){ // an external's VM: HyperCard is the program in front
			for(String[] e:entries)e[1]="";entries.add(new String[]{"HyperCard","1"});entries.sort((x,y)->x[0].compareToIgnoreCase(y[0]));}
		String front="";for(String[] e:entries)if(!e[1].isEmpty())front=e[0];
		boolean japanese=tb.encoding==StackTextEncoding.JAPANESE;
		String[] head=japanese?new String[]{front+"\u3092\u96a0\u3059","\u307b\u304b\u3092\u96a0\u3059","\u3059\u3079\u3066\u3092\u8868\u793a","-"}:new String[]{"Hide "+front,"Hide Others","Show All","-"};
		for(String t:head){Item it=new Item();it.text=t;m.items.add(it);}
		for(String[] e:entries){Item it=new Item();it.text=pascalSized(e[0]);it.mark=e[1].isEmpty()?0:0x12;m.items.add(it);}
		m.enableFlags=-1&~(1<<4); // the line is disabled
		applicationMenu=store(m,applicationMenu);
		int p=systemMenuList==0?0:memory.readLong(systemMenuList);if(p!=0)memory.writeLong(p+6,applicationMenu);
		return applicationMenu;
	}
	/** A name cut as a process name is (31 bytes in the system encoding, whole characters only): GetProcessInformation's processName. */
	private String pascalSized(String name){
		byte[] b=tb.encoding.encodeReplacing(name);if(b.length<=31)return name;
		for(int n=name.length()-1;n>0;n--){String cut=name.substring(0,n);if(tb.encoding.encodeReplacing(cut).length<=31)return cut;}
		return "";
	}
	/** Handle of the MenuList structure (low-memory global $A1C), rebuilt at every change. */
	private int menuList;
	/** The menu bar changed: the host bar is rebuilt at the next flush, MenuList right away (HyperCard walks
	 *  MenuList itself to enumerate the menus it created with `create menu`). */
	private void changed(){
		dirty=true;
		try{writeMenuList();}catch(M68kCpuException e){}
	}
	/** MenuList (IM Toolbox Essentials 3-155): lastMenu (offset of the last regular entry), lastRight, mbResID, then
	 *  {menuOH, menuLeft} per menu; behind them lastHMenu, menuTitleSave and {menuHOH, reserved} per submenu. */
	private void writeMenuList() throws M68kCpuException {
		byte[] bytes=menuListBytes(menuBar,hierarchical);int size=bytes.length;
		if(menuList==0){menuList=tb.api.createHandle(bytes);memory.writeLong(0xa1c,menuList);return;}
		if(tb.api.handleSize(menuList)!=size)tb.api.resizeHandle(menuList,size);
		int p=memory.readLong(menuList);if(p==0)return;
		for(int i=0;i<size;i++)memory.writeByte(p+i,bytes[i]);
	}
	/** The MenuList layout for a menu bar (HyperCard's `reset menubar` walks the copy GetMenuBar returns to find the
	 *  menus it created, so the copy must be a real MenuList, not a token). */
	private byte[] menuListBytes(List<Integer> bar,List<Integer> hier) throws M68kCpuException {
		int n=bar.size(),m=hier.size();byte[] b=new byte[6+6*n+6+6*m];
		int left=10;putWord(b,0,6*n);putWord(b,4,mbdfResId);
		for(int i=0;i<n;i++){int h=bar.get(i);putLong(b,6+6*i,h);putWord(b,6+6*i+4,left);
			Menu menu=read(h);left+=(menu==null?2:menu.title.length())*7+13;}
		putWord(b,2,left);
		int hp=6+6*n;putWord(b,hp,6*m);putLong(b,hp+2,0);
		for(int i=0;i<m;i++){putLong(b,hp+6+6*i,hier.get(i));putWord(b,hp+6+6*i+4,0);}
		return b;
	}
	private static void putWord(byte[] b,int o,int v){b[o]=(byte)(v>>>8);b[o+1]=(byte)v;}
	private static void putLong(byte[] b,int o,int v){putWord(b,o,v>>>16);putWord(b,o+2,v);}

	void flush(){
		if(!dirty)return;dirty=false;
		List<MacAppHost.MenuModel> models=new ArrayList<MacAppHost.MenuModel>();
		boolean externals=tb.app==null&&tb.stack!=null;
		if(externals)hyperCardMenusInBar();
		try{for(int h:menuBar){Menu m=read(h);if(m==null)continue;if(hyperCardMenus.containsKey(h)){hyperCardMenuChanged(h,m);continue;}
			MacAppHost.MenuModel model=model(m,0);if(externals)model.after=hyperCardMenuBefore(h);
			// the Help menu shows the program's own items (the system's balloon items have no host counterpart), and only when it has some
			if(h==helpMenu&&helpSystemItems>0){int n=Math.min(helpSystemItems,model.items.size());model.items.subList(0,n).clear();model.itemBase=n;}
			if(h==helpMenu&&model.items.isEmpty())continue;
			models.add(model);}}
		catch(M68kCpuException e){System.err.println("[68K] menu bar not rebuilt: "+e);return;}
		// The Quit item: the one with Cmd-Q, else one named Quit (MacWrite 2.2 predates the shortcut).
		int[] quit=null,named=null;
		for(MacAppHost.MenuModel m:models)for(int i=0;i<m.items.size();i++){MacAppHost.ItemModel im=m.items.get(i);
			if(quit==null&&(im.command=='Q'||im.command=='q'))quit=new int[]{m.id,i+1};
			if(named==null&&!im.separator&&isQuitName(im.text))named=new int[]{m.id,i+1};}
		quitItem=quit!=null?quit:named;
		// an external (XCMD/XFCN) puts its menus into HyperCard's menu bar, where scripts use them like any other menu
		// (The Yennie eXternals: toolsMenu inserts "ToolsMenu", then set the menuMsg of menuItem 1 of menu "ToolsMenu")
		if(tb.app==null&&tb.stack!=null){TTalk.syncExternalMenus(tb,models);return;}
		try{tb.host().menusUpdateOnOpening=updatesMenusOnOpening()||adjustsOnClick;}catch(M68kCpuException e){}
		tb.host().setMenuBar(models);
	}
	/**
	 * HyperCard's Tools (MENU 5) and Patterns (MENU 11) menus are tear-off menus: dragging past the menu makes a palette of it.
	 * A host menu cannot be dragged off, so the host menu gets an item that shows the palette instead: the HyperTalk
	 * {@link #tearOffScript} sent to HyperCard as a do script ('misc'/'dosc') Apple event.
	 */
	private String tearOffLabel(int menuId){
		if(tearOffScript(menuId)==null)return null;
		String label=menuId==HC_TOOLS_MENU?"Show Tool Palette":"Show Patterns Palette";
		return PCARD.pc==null||PCARD.pc.intl==null?label:PCARD.pc.intl.getDialogText(label);
	}
	static final int HC_TOOLS_MENU=5,HC_PATTERNS_MENU=11;
	String tearOffScript(int menuId){
		if(menuId!=HC_TOOLS_MENU&&menuId!=HC_PATTERNS_MENU||!isHyperCard())return null;
		return menuId==HC_TOOLS_MENU?"show tool window":"show pattern window";
	}
	/**
	 * HyperCard's background mode (Edit > Background): its DrawMenuBar patch (J-2.3 CODE 38, the drawing at CODE 49 $4A6A) calls
	 * the real DrawMenuBar and then, while the background is edited, hatches the bar's top and bottom edges in the Window Manager
	 * port (PenPat + LineTo). The host's menu bar stands in for that strip, so the hatching never shows; the front window's host
	 * title says it instead ("/// Home ///"). DrawMenuBar forgets what was seen, a patterned line over the bar marks it, and the
	 * next event call ({@link #showMenuBarHatching}) passes the result on - the title does not flicker while the bar is redrawn.
	 */
	private boolean menuBarDrawn,menuBarHatched;
	void menuBarPatternDrawn(){if(isHyperCard())menuBarHatched=true;}
	void showMenuBarHatching(){
		if(!menuBarDrawn&&!menuBarHatched||!tb.hosted())return; // hatched: the front window may have changed
		menuBarDrawn=false;tb.host().markFrontTitle(menuBarHatched);
	}
	private Boolean hyperCard;
	private boolean isHyperCard(){if(hyperCard==null)hyperCard=tb.app!=null&&tb.app.file!=null&&tb.files.fileCreator(tb.app.file)==Toolbox68k.HYPERCARD_CREATOR;return hyperCard;}
	private static boolean isQuitName(String text){
		if(text==null)return false;
		String t=text.trim();
		return t.regionMatches(true,0,"Quit",0,4)||t.startsWith("終了")||t.equals("Exit");
	}
	void flushIfDirty(){flush();}
	/** The host menus must be rebuilt (Balloon Help toggled: the items' tooltips change). */
	void markDirty(){dirty=true;}
	boolean hasMenuBar(){return !menuBar.isEmpty();}
	int[] quitItem(){return quitItem;}

	private MacAppHost.MenuModel model(Menu m,int depth) throws M68kCpuException {
		boolean apple=m.title.length()==1&&m.title.charAt(0)==0x14;
		MacAppHost.MenuModel model=new MacAppHost.MenuModel(m.id,apple?(tb.app==null?"":tb.app.name):shown(m.title),(m.enableFlags&1)!=0);
		model.apple=apple;
		// a custom MDEF is run for its picture only when the record carries no text items (HyperCard's Tools/Patterns palettes);
		// menus with real items behind a custom MDEF (HyperCard's card-size pop-up in New Stack) are shown as text like any other
		Integer handle=menusById.get(m.id);
		// A custom MDEF draws the menu itself; use its picture when the record has no text items (HyperCard's Tools/
		// Patterns palettes) or when the items are not real text (some colour pop-ups store colour data, not text,
		// in its one item and paints swatches). Menus with genuine text behind a custom MDEF stay text.
		if(handle!=null&&customMdef(handle)){PictureMenu picture=renderCustom(handle,m.items.size());
			// Use the MDEF's own picture when it draws more clickable regions than the record has text items - a graphical
			// menu (a colour swatch grid can use one record item for many swatches). A custom MDEF over genuine text items
			// (HyperCard's card-size pop-up: as many regions as items) stays text.
			if(picture!=null&&picture.items.size()>m.items.size()){model.image=picture.image;model.pictureItems=picture.items;model.tearOff=tearOffLabel(m.id);return model;}}
		int n=0;
		for(Item item:m.items){
			n++;
			MacAppHost.ItemModel im=new MacAppHost.ItemModel();
			im.text=item.text.startsWith("-")?item.text:shown(item.text);im.enabled=n>31||(m.enableFlags&(1<<n))!=0;im.checked=item.mark==0x12&&item.cmd!=0x1b;im.mark=item.mark;im.style=item.style;
			if(item.cmd!=0x1b&&item.mark!=0)im.markText=markText(item.mark);im.help=tb.help.menuHelp(m.id,n,im.enabled);
			im.separator=item.text.equals("-")||item.text.startsWith("-");
			if(item.cmd==0x1b&&depth<3){Integer sub=menusById.get(item.mark);Menu subMenu=sub==null?null:read(sub);if(subMenu!=null)im.submenu=model(subMenu,depth+1);}
			else if(item.cmd>=0x20){im.command=(char)item.cmd;Integer menuHandle=menusById.get(m.id);int[] d=menuHandle==null?null:itemData(menuHandle,n,false);if(d!=null)im.modifiers=d[slot(0x04)];}
			if(item.icon!=0&&item.cmd!=0x1b){im.icon=itemIcon(item);im.iconKey=item.icon<<8|item.cmd&0xff;}
			model.items.add(im);
		}
		return model;
	}

	/**
	 * A menu title or item as the host shows it: translated (VMTranslation), except what is the user's or a name - a font
	 * (the Font menu), an open window (the Windows menu lists documents), a file name.
	 */
	private String shown(String text){
		if(text==null||text.length()<2||tb.translator()==null)return text;
		if(!tb.translationKnows(text)&&(isFontName(text)||tb.windows.hasWindowTitled(text)||text.matches(".*\\.[A-Za-z0-9]{1,4}")))return text;
		String t=tb.translated(VMTranslation.Kind.MENU,text);
		return t==null?text:t;
	}
	/**
	 * A mark byte as the menu font draws it: Chicago's command, check, diamond and apple glyphs ($11-$14), ASCII, and the System's
	 * one-byte characters ($C3 is √ in Mac OS Roman, a half-width katakana under KanjiTalk; a lone two-byte lead byte reads as Roman).
	 * Empty for none. SimplePop's "!√I'm marked" showed a bullet; HyperCard Menus' SetMenuItemMark "#" showed nothing in HyperCard's bar.
	 */
	String markText(int mark){
		mark&=0xff;
		switch(mark){case 0:return "";case 0x11:return "\u2318";case 0x12:return "\u2713";case 0x13:return "\u25c6";case 0x14:return "\uf8ff";default:break;}
		if(mark<0x80)return mark<0x20?"\u2022":String.valueOf((char)mark);
		String t=tb.encoding==StackTextEncoding.JAPANESE&&Toolbox68k.isJapaneseLead(mark)?"":tb.decode(new byte[]{(byte)mark});
		return t.length()==1&&t.charAt(0)!='\ufffd'?t:StackTextEncoding.ROMAN.decode(new byte[]{(byte)mark});
	}
	/** The other way: a HyperTalk markChar (HyperCard's $11-$14 or the glyphs shown for them, any character of the System) as a mark byte. */
	int markByte(String mark){
		if(mark==null||mark.isEmpty())return 0x12;
		char c=mark.charAt(0);
		switch(c){case '\u2318':case 0x11:return 0x11;case '\u2713':case 0x12:return 0x12;case '\u25c6':case 0x13:return 0x13;case '\uf8ff':case 0x14:return 0x14;default:break;}
		if(c<0x80)return c;
		byte[] b=tb.encoding.encodeReplacing(String.valueOf(c));
		if(b.length!=1||b[0]=='?'){b=StackTextEncoding.ROMAN.encodeReplacing(String.valueOf(c));}
		return b.length==1&&b[0]!='?'?b[0]&0xff:0x12;
	}
	private static boolean isFontName(String text){
		String t=text.trim();
		return Fonts68k.numberOf(t)!=0||t.equalsIgnoreCase(Fonts68k.nameOf(0));
	}

	/** A menu item's icon, as the System 7 MDEF finds it: resource ID 256 + the icon number, a 'SICN' when the command
	 *  byte is $1E, else a 'cicn' before an 'ICON' ($1D: an ICON shrunk to 16x16). Snood 2 puts cicns in its menus. */
	private java.awt.image.BufferedImage itemIcon(Item item){
		try{int id=256+item.icon;
			if(item.cmd==0x1e){ResourceFile68k.Resource r=tb.resources.find(0x5349434e,id);byte[] d=r==null?null:tb.resources.data(r);
				if(d==null||d.length<32)return null;java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB);
				for(int y=0;y<16;y++)for(int x=0;x<16;x++)if((d[y*2+x/8]>>(7-x%8)&1)!=0)image.setRGB(x,y,0xff000000);return image;}
			java.awt.image.BufferedImage image=null;
			ResourceFile68k.Resource c=tb.resources.find(0x6369636e,id);if(c!=null)image=decodeCicn(tb.resources.data(c));
			if(image==null){ResourceFile68k.Resource r=tb.resources.find(0x49434f4e,id);byte[] d=r==null?null:tb.resources.data(r);
				if(d!=null&&d.length>=128){image=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
					for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((d[y*4+x/8]>>(7-x%8)&1)!=0)image.setRGB(x,y,0xff000000);}}
			if(image!=null&&item.cmd==0x1d&&image.getWidth()>16){java.awt.image.BufferedImage small=new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D g=small.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,16,16,null);g.dispose();image=small;}
			return image;
		}catch(Exception e){return null;}
	}
	/** A 'cicn' resource as an image: IconPMap (50) + maskBMap (14) + iconBMap (14) + iconData (4), then the mask, the 1-bit
	 *  image, the colour table and the pixels; the mask decides what is drawn. */
	static java.awt.image.BufferedImage decodeCicn(byte[] d){
		if(d==null||d.length<82)return null;
		int pmRow=u2(d,4)&0x3fff,top=(short)u2(d,6),left=(short)u2(d,8),bottom=(short)u2(d,10),right=(short)u2(d,12),depth=u2(d,32);
		int maskRow=u2(d,54),bmapRow=u2(d,68),h=bottom-top,w=right-left;
		if(h<=0||w<=0||h>256||w>256||(depth!=1&&depth!=2&&depth!=4&&depth!=8))return null;
		int mask=82,bmap=mask+maskRow*h,table=bmap+bmapRow*h;if(table+8>d.length)return null;
		int entries=u2(d,table+6)+1,pixels=table+8+entries*8;if(pixels+pmRow*h>d.length)return null;
		int[] rgb=new int[256];for(int i=0;i<entries;i++){int e=table+8+i*8;int v=u2(d,e)&0xff;rgb[v]=(d[e+2]&0xff)<<16|(d[e+4]&0xff)<<8|(d[e+6]&0xff);}
		java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){
			if(maskRow>0&&(d[mask+y*maskRow+x/8]>>(7-x%8)&1)==0)continue;
			int bit=x*depth,index=(d[pixels+y*pmRow+bit/8]>>(8-depth-bit%8))&((1<<depth)-1);
			image.setRGB(x,y,0xff000000|rgb[index]);}
		return image;
	}
	private static int u2(byte[] d,int at){return (d[at]&0xff)<<8|(d[at+1]&0xff);}

	void pendingMenuKey(int menuId,int item){pendingKeyMenu=menuId;pendingKeyItem=item;}

	// ------------------------------------------------------------------ custom menu definition procedures

	/** Whether the menu's menuProc is code of the program's own (HyperCard stores a Handle to a JMP into its Tools/Patterns MDEF). */
	private boolean customMdef(int handle) throws M68kCpuException {
		int p=memory.readLong(handle);if(p==0)return false;int mdef=memory.readLong(p+6);
		if(mdef==0||mdef==standardMdef())return false;
		int code=memory.readLong(mdef);boolean custom=code!=0&&memory.readWord(code)!=0x4e75;
		return custom;
	}
	private static final int M_DRAW=0,M_CHOOSE=1,M_SIZE=2,M_POPUP=3;
	/** Space the standard MDEF leaves left of an item's text (the mark column) and right of the widest text. */
	static final int MDEF_TEXT_LEFT=14,MDEF_TEXT_RIGHT=10;
	/**
	 * The System's MDEF 0 when a program calls it itself (a custom MDEF that sets a font and hands the work on): the item
	 * layout in the current port's font, one line (ascent + descent + leading) per item. mSizeMsg sets menuWidth/menuHeight,
	 * mChooseMsg answers the enabled item under hitPt, mPopUpMsg places the menu with item *whichItem at hitPt (swapped) and answers its
	 * top. mDrawMsg draws nothing: menus on the screen are drawn natively. Infini-D 2.0.1's view pop-up frames its label
	 * by menuWidth after TextFont(ApFontID) TextSize(9); the no-op stub left menuWidth 0 and the label ran over the frame.
	 */
	void standardMdef(int message,int handle,int rect,int hitPt,int whichItem) throws M68kCpuException {
		Menu m=read(handle);int p=handle==0?0:memory.readLong(handle);if(m==null||p==0)return;
		int[] port=tb.api.quickDraw().textState();java.awt.Font font=Fonts68k.font(port[0],port[3],port[1]);
		if(Mac68kApi.TRACE)System.err.println("[68K] MDEF 0 message "+message+" in port $"+Integer.toHexString(tb.api.quickDraw().currentPortAddress())+" font "+port[0]+"/"+port[3]);
		mdefTextSize.put(handle,port[3]<=0?12:port[3]);int[] info=Fonts68k.fontInfo(font);int line=Math.max(1,info[0]+info[1]+info[3]);
		switch(message){
			case M_SIZE:{
				int text=0,cmd=0;
				for(Item item:m.items){
					text=Math.max(text,Fonts68k.width(Fonts68k.font(port[0],port[3],item.style),item.text));
					if(item.cmd==0x1b)cmd=Math.max(cmd,16); // hierarchical arrow
					else if(item.cmd>0x1f)cmd=Math.max(cmd,Fonts68k.width(font,"⌘"+(char)item.cmd)+8);
				}
				memory.writeWord(p+2,MDEF_TEXT_LEFT+text+MDEF_TEXT_RIGHT+cmd);memory.writeWord(p+4,line*m.items.size());return;}
			case M_CHOOSE:{
				if(rect==0||whichItem==0)return;
				int v=(short)(hitPt>>>16),h=(short)hitPt,top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
				int item=0;
				if(v>=top&&v<bottom&&h>=left&&h<right){item=(v-top)/line+1;if(item>m.items.size()||!itemEnabled(m,item))item=0;}
				memory.writeWord(whichItem,item);return;}
			case M_POPUP:{
				if(rect==0||whichItem==0)return;
				// hitPt the wrong way round, as the ROM passes it for this message: hitPt.v is the left, hitPt.h the top
				int h=(short)(hitPt>>>16),v=(short)hitPt,item=Math.max(1,(short)memory.readWord(whichItem));
				int top=v-(item-1)*line;
				memory.writeWord(rect,top);memory.writeWord(rect+2,h);memory.writeWord(rect+4,top+memory.readWord(p+4));memory.writeWord(rect+6,h+memory.readWord(p+2));
				memory.writeWord(whichItem,top);return;}
			default:return;
		}
	}
	/** The text size of the port when the standard definition last ran for a menu: what a custom MDEF that hands its work to
	 *  MDEF 0 draws in (the pop-up it shows as a host text menu uses it). */
	private final Map<Integer,Integer> mdefTextSize=new HashMap<Integer,Integer>();
	/** The port's text size when MDEF 0 last ran for {@code handle}, or 0 (tests). */
	int standardMdefTextSize(int handle){Integer size=mdefTextSize.get(handle);return size==null?0:size;}
	private static boolean itemEnabled(Menu m,int item){
		if((m.enableFlags&1)==0)return false;
		if(item<=31&&(m.enableFlags&(1<<item))==0)return false;
		String t=m.items.get(item-1).text;return !(t.startsWith("-"));
	}
	/** pascal void MDEF(short message, MenuHandle theMenu, Rect *menuRect, Point hitPt, short *whichItem), in the current port. */
	private void callMdef(int handle,int message,int rect,int hitH,int hitV,int whichItem) throws M68kCpuException {
		int mdef=memory.readLong(memory.readLong(handle)+6);int entry=memory.readLong(mdef);if(entry==0)return;
		menuBarSaveData();
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();if(message==M_CHOOSE)chooseCalls++;
		try{cpu.pushWord(message);cpu.pushLong(handle);cpu.pushLong(rect);cpu.pushLong((hitV&0xffff)<<16|(hitH&0xffff));cpu.pushLong(whichItem);cpu.callPascal(entry,18);}
		catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	/** What a custom MDEF draws, and where its items are. */
	static final class PictureMenu {java.awt.image.BufferedImage image;final List<int[]> items=new ArrayList<int[]>();}
	private final Map<Integer,PictureMenu> pictureCache=new HashMap<Integer,PictureMenu>();
	/** -Dhz68k.livemdef=false: custom-MDEF pop-ups learn their whole layout first, as before the live pop-up. */
	private static final boolean LIVE_MDEF=!"false".equals(System.getProperty("hz68k.livemdef"));
	private int chooseCalls;private static final boolean MDEF_TIMING=Boolean.getBoolean("hz68k.mdeftiming");
	/** Runs the MDEF offscreen: mSizeMsg for the size, mDrawMsg for the picture, then mChooseMsg over a grid of points to learn
	 *  which item each part of the picture belongs to (the MDEF is the only thing that knows its layout). The Window Manager port,
	 *  where MDEFs draw, is the offscreen port meanwhile. Null when the MDEF drew nothing usable. */
	private PictureMenu renderCustom(int handle,int textItems) throws M68kCpuException {
		tb.offscreenMdef++;try{return renderCustomOffscreen(handle,textItems);}finally{tb.offscreenMdef--;}
	}
	private PictureMenu renderCustomOffscreen(int handle,int textItems) throws M68kCpuException {
		long started=System.nanoTime(),startInstructions=tb.cpu.instructions;chooseCalls=0;
		int[] size=customSize(handle);if(size==null)return null;
		int port=tb.api.quickDraw().newScratchPort(size[0],size[1]),rect=memory.allocate(8,2),which=memory.allocate(2,2);
		int savedPort=tb.api.currentQuickDrawPort();
		try{tb.windows.overrideWMgrPort(port);setPort(port);writeRect(rect,0,0,size[1],size[0]);clipTo(rect);memory.writeWord(which,0);callMdef(handle,M_DRAW,rect,0,0,which);
			PictureMenu picture=new PictureMenu();picture.image=tb.api.quickDraw().scratchPortImage(port);if(picture.image==null)return null;
			PictureMenu cached=pictureCache.get(handle);
			if(cached!=null&&sameImage(cached.image,picture.image)){picture.items.addAll(cached.items);return picture;} // the layout is the MDEF's: unchanged picture, unchanged items
			// The same definition drawing the same menu at the same size lays its items out the same way, whatever it marks:
			// ResEdit's colour pop-ups mark the current colour and are rebuilt for every click, and relearning the layout
			// (2000 MDEF calls) held each pop-up back for most of a second.
			String layoutKey=memory.readLong(memory.readLong(memory.readLong(handle)+6))+"/"+(short)memory.readWord(memory.readLong(handle))+"/"+size[0]+"x"+size[1]+"/"+textItems;
			List<int[]> known=layouts.get(layoutKey);
			if(known!=null){for(int[] b:known)picture.items.add(b.clone());pictureCache.put(handle,picture);return picture;}
			Map<Integer,int[]> bounds=new LinkedHashMap<Integer,int[]>();final int step=3;
			// A text menu drawn by its own MDEF (Netscape 2.02: every menu) stacks its items, so a few columns find them all;
			// the full grid - thousands of MDEF calls, seconds per menu at every InvalMenuBar - is only for menus that may hold
			// more regions than text items (palettes with no items, swatch grids behind one item: an item number past the text).
			boolean grid=textItems==0;
			for(int column=1;column<=3&&!grid;column++){int h=size[0]*column/4;
				for(int v=1;v<size[1];v+=step){int item=choose(handle,rect,which,h,v);if(item<=0)continue;
					if(item>textItems){grid=true;break;}
					int[] b=bounds.get(item);if(b==null)bounds.put(item,new int[]{item,v,0,v+1,size[0]});else{b[1]=Math.min(b[1],v);b[3]=Math.max(b[3],v+1);}}}
			if(grid)bounds.clear();
			// The grid, row by row: a row whose runs of equal items still hold at the probes of sameRuns is taken as the row
			// above; only a row that differs is sampled across (swatch grids change every dozen rows).
			if(grid){List<int[]> previous=null;
				for(int v=1;v<size[1];v+=step){
					List<int[]> runs=previous!=null&&sameRuns(handle,rect,which,previous,v,step)?previous:scanRow(handle,rect,which,v,size[0],step);
					for(int[] run:runs){int item=run[0];if(item<=0)continue;
						int[] b=bounds.get(item);if(b==null)bounds.put(item,new int[]{item,v,run[1],v+1,run[2]+1});else{b[1]=Math.min(b[1],v);b[2]=Math.min(b[2],run[1]);b[3]=Math.max(b[3],v+1);b[4]=Math.max(b[4],run[2]+1);}}
					previous=runs;}}
			callMdef(handle,M_CHOOSE,rect,-1,-1,which); // leaves nothing hilited (whichItem names the highlighted item)
			for(int[] b:bounds.values()){b[1]=Math.max(0,b[1]-step+1);b[2]=Math.max(0,b[2]-step+1);b[3]=Math.min(size[1],b[3]+step-1);b[4]=Math.min(size[0],b[4]+step-1);picture.items.add(b);}
			java.util.Collections.sort(picture.items,(x,y)->x[0]-y[0]);
			pictureCache.put(handle,picture);
			List<int[]> copy=new ArrayList<int[]>();for(int[] b:picture.items)copy.add(b.clone());layouts.put(layoutKey,copy);
			if(Mac68kApi.TRACE||MDEF_TIMING)System.err.println("[68K] custom MDEF menu $"+Integer.toHexString(handle)+": "+size[0]+"x"+size[1]+", "+picture.items.size()+" items, "+chooseCalls+" choose calls, "+(tb.cpu.instructions-startInstructions)+" instructions, "+(System.nanoTime()-started)/1000000+" ms");
			return picture;}
		finally{tb.windows.overrideWMgrPort(0);if(savedPort!=0)setPort(savedPort);tb.api.quickDraw().disposeScratchPort(port);memory.free(rect);memory.free(which);}
	}
	/** Item layouts learnt from custom MDEFs, by definition entry, menu ID, size and text item count. */
	private final Map<String,List<int[]>> layouts=new HashMap<String,List<int[]>>();
	/** The runs of equal items along row {@code v}: {item, first h, last h} at the sampled points. */
	private List<int[]> scanRow(int handle,int rect,int which,int v,int width,int step) throws M68kCpuException {
		List<int[]> runs=new ArrayList<int[]>();int[] run=null;
		for(int h=1;h<width;h+=step){int item=choose(handle,rect,which,h,v);
			if(run!=null&&run[0]==item)run[2]=h;else{run=new int[]{item,h,h};runs.add(run);}}
		return runs;
	}
	/** Whether row {@code v} still has the runs of the row above: both ends of every run and every fourth sample inside one
	 *  (a new item narrower than that could hide between the probes; palette cells are wider). */
	private boolean sameRuns(int handle,int rect,int which,List<int[]> runs,int v,int step) throws M68kCpuException {
		for(int[] run:runs){
			if(choose(handle,rect,which,run[1],v)!=run[0])return false;
			for(int h=run[1]+4*step;h<run[2];h+=4*step)if(choose(handle,rect,which,h,v)!=run[0])return false;
			if(run[2]!=run[1]&&choose(handle,rect,which,run[2],v)!=run[0])return false;}
		return true;
	}
	/** mChooseMsg at (h, v). whichItem keeps the item the previous probe found: it is the item the MDEF has highlighted, and
	 *  an MDEF redraws only when the item under the point differs (IM: whichItem holds the currently highlighted item), so
	 *  probes inside one item cost no drawing. */
	private int choose(int handle,int rect,int which,int h,int v) throws M68kCpuException {callMdef(handle,M_CHOOSE,rect,h,v,which);int item=(short)memory.readWord(which);return item<0?0:item;}
	private static boolean sameImage(java.awt.image.BufferedImage a,java.awt.image.BufferedImage b){
		if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())return false;
		for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++)if(a.getRGB(x,y)!=b.getRGB(x,y))return false;return true;
	}
	/**
	 * A pop-up whose MDEF draws a picture (a palette, a swatch grid - more regions than text items): shown at once from its
	 * mDrawMsg, the MDEF itself answering mChooseMsg for the point under the pointer while the pop-up is up. Returns the
	 * item chosen, or -1 when the MDEF draws a text menu (shown as text, as before) or nothing usable.
	 */
	private int popUpLive(int handle,int textItems,int left,int top,int popUpItem) throws M68kCpuException {
		long started=System.nanoTime();
		int[] size=customSize(handle);if(size==null)return -1;
		// a custom MDEF over genuine text items (HyperCard's card-size pop-up) stays a text menu: a few columns of probes in a
		// scratch port find an item number past the text only in a graphical one
		if(textItems>0&&!graphicalMdef(handle,textItems,size))return -1;
		final int screen=tb.windows.screenPort();int[] frame=tb.api.quickDraw().mainDeviceFrame();
		if(screen==0||frame==null||frame[3]!=0||frame[4]!=0)return -1;
		final int screenWidth=frame[5],screenHeight=frame[6];
		// mPopUpMsg: the MDEF places the menu (a global rect) itself, as the ROM asks it to - a palette may put the current
		// item's cell at the point. The ROM passes that point the wrong way round, top in hitPt.h and left in hitPt.v, and
		// MDEFs read it so (ClarisWorks' colour palettes came up at the top of the screen, as far right as the button was down)
		final int[] where={top,left,top+size[1],left+size[0]};
		final int rect=memory.allocate(8,2),which=memory.allocate(2,2);
		int savedPort=tb.api.currentQuickDrawPort();
		try{memory.fill(rect,8,0);memory.writeWord(which,popUpItem);callMdef(handle,M_POPUP,rect,top,left,which);
			int t=(short)memory.readWord(rect),l=(short)memory.readWord(rect+2),b=(short)memory.readWord(rect+4),r=(short)memory.readWord(rect+6);
			if(b-t==size[1]&&r-l==size[0]){where[0]=t;where[1]=l;where[2]=b;where[3]=r;}
			writeRect(rect,where[0],where[1],where[2],where[3]);memory.writeWord(which,0);
		}finally{if(savedPort!=0)setPort(savedPort);}
		// On a Mac the MDEF draws in the Window Manager port - the whole screen - clipped to the menu's rectangle. Besides the
		// menu and its highlight, a palette may paint a preview of the item under the pointer into the control that opened it
		// (ResEdit's pattern pop-up fills the tool window's pattern swatch, and the editor never redraws it itself). So the MDEF
		// draws into a scratch port as large as the screen, with the same global coordinates; the pop-up shows its menu
		// rectangle, and whatever it drew outside that rectangle is copied onto the screen (CopyBits into the Window Manager port).
		final int port=tb.api.quickDraw().newScratchPort(screenWidth,screenHeight);
		final int[] pixels=tb.api.quickDraw().colorPortPixels(port);
		try{
			if(pixels==null||pixels[6]!=32)return -1;
			final int rowBytes=pixels[1];final byte[] before=memory.readBytes(pixels[0],rowBytes*screenHeight);
			final int copyRect=memory.allocate(8,2);
			MacAppHost.LiveMenu live=new MacAppHost.LiveMenu(){
				java.awt.image.BufferedImage current;
				/** Runs {@code message} in the scratch port; true when the menu's picture changed. */
				boolean run(int message,int h,int v) throws M68kException {
					int saved=tb.api.currentQuickDrawPort();int item0=(short)memory.readWord(which);
					try{tb.windows.overrideWMgrPort(port);setPort(port);clipTo(rect);callMdef(handle,message,rect,h,v,which);}
					finally{tb.windows.overrideWMgrPort(0);if(saved!=0)setPort(saved);}
					showOutside();
					return message==M_DRAW||(short)memory.readWord(which)!=item0;
				}
				/** Copies what changed outside the menu rectangle onto the screen, row band by row band. */
				void showOutside() throws M68kException {
					byte[] raw=memory.raw();int base=memory.physical(pixels[0]);int bandTop=-1,bandLeft=Integer.MAX_VALUE,bandRight=-1;
					for(int y=0;y<=screenHeight;y++){
						int changedLeft=Integer.MAX_VALUE,changedRight=-1;
						if(y<screenHeight){int row=base+y*rowBytes,old=y*rowBytes;
							for(int x=0;x<screenWidth;x++){int o=x*4;if(y>=where[0]&&y<where[2]&&x>=where[1]&&x<where[3])continue;
								if(raw[row+o+1]!=before[old+o+1]||raw[row+o+2]!=before[old+o+2]||raw[row+o+3]!=before[old+o+3]){changedLeft=Math.min(changedLeft,x);changedRight=x+1;}}}
						if(changedRight>0){if(bandTop<0)bandTop=y;bandLeft=Math.min(bandLeft,changedLeft);bandRight=Math.max(bandRight,changedRight);continue;}
						if(bandTop<0)continue;
						copyToScreen(bandTop,bandLeft,y,bandRight);bandTop=-1;bandLeft=Integer.MAX_VALUE;bandRight=-1;
					}
				}
				void copyToScreen(int t,int l,int b,int r) throws M68kException {
					for(int y=t;y<b;y++)System.arraycopy(memory.raw(),memory.physical(pixels[0])+y*rowBytes+l*4,before,y*rowBytes+l*4,(r-l)*4);
					writeRect(copyRect,t,l,b,r);int saved=tb.api.currentQuickDrawPort();
					try{setPort(screen);int clip=qdFunction(0x8d8);qdProc(0x87a,clip);clipTo(copyRect);
						try{M68kCpu cpu=tb.cpu;int sp=cpu.a[7];cpu.a[7]-=22;memory.writeLong(cpu.a[7],0);memory.writeWord(cpu.a[7]+4,0);memory.writeLong(cpu.a[7]+6,copyRect);memory.writeLong(cpu.a[7]+10,copyRect);
							memory.writeLong(cpu.a[7]+14,screen+2);memory.writeLong(cpu.a[7]+18,port+2);tb.api.quickDraw().handle(cpu,0x8ec);cpu.a[7]=sp;} // CopyBits(src, dst, srcRect, dstRect, srcCopy, nil)
						finally{qdProc(0x879,clip);qdProc(0x8d9,clip);}}
					finally{if(saved!=0)setPort(saved);}
					tb.host().adoptDeviceWrites(); // shown now: the program's event loop, which would pick the screen writes up, waits for the pop-up
				}
				java.awt.image.BufferedImage picture() throws M68kCpuException {return tb.api.quickDraw().scratchPortImage(port,where[0],where[1],where[2],where[3]);}
				@Override public int choose(int x,int y) throws M68kException {
					if(run(M_CHOOSE,x<0?-1:where[1]+x,y<0?-1:where[0]+y)){java.awt.image.BufferedImage now=picture();if(now!=null)current=now;}
					int item=(short)memory.readWord(which);return item<0?0:item;
				}
				@Override public java.awt.image.BufferedImage image(){return current;}
				MacAppHost.LiveMenu open() throws M68kException {run(M_DRAW,0,0);current=picture();return current==null?null:this;}
			}.open();
			if(live==null)return -1;
			if(MDEF_TIMING)System.err.println("[68K] live MDEF pop-up $"+Integer.toHexString(handle)+" "+size[0]+"x"+size[1]+" at "+where[1]+","+where[0]+" ready in "+(System.nanoTime()-started)/100000/10.0+" ms");
			int item=tb.host().popUpLiveMenu(live.image(),live,where[1],where[0]);
			memory.free(copyRect);
			if(Mac68kApi.TRACE)System.err.println("[68K] live MDEF pop-up $"+Integer.toHexString(handle)+" "+size[0]+"x"+size[1]+" -> item "+item);
			return item;
		}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
		finally{tb.api.quickDraw().disposeScratchPort(port);memory.free(rect);memory.free(which);}
	}

	/** Whether a custom MDEF over text items draws a graphical menu: in a scratch port (never on the screen) its mDrawMsg draws
	 *  pictures or icons (DrawPicture, PlotIcon/PlotCIcon/icon suites), or probes find an item number past the text items. The
	 *  Yennie pictMDEF draws one PICT per item (the items are PICT IDs), so the probes alone never found more regions than items
	 *  and its menu came up as a text list of the IDs; HyperCard's card-size pop-up draws text only and stays a text menu. */
	private boolean graphicalMdef(int handle,int textItems,int[] size) throws M68kCpuException {
		int port=tb.api.quickDraw().newScratchPort(size[0],size[1]),rect=memory.allocate(8,2),which=memory.allocate(2,2);
		int savedPort=tb.api.currentQuickDrawPort();tb.offscreenMdef++;
		try{tb.windows.overrideWMgrPort(port);setPort(port);writeRect(rect,0,0,size[1],size[0]);clipTo(rect);memory.writeWord(which,0);
			int images=tb.api.quickDraw().imageDraws();callMdef(handle,M_DRAW,rect,0,0,which);
			boolean graphical=tb.api.quickDraw().imageDraws()!=images;
			for(int column=1;column<=3&&!graphical;column++)for(int v=1;v<size[1]&&!graphical;v+=3)graphical=choose(handle,rect,which,size[0]*column/4,v)>textItems;
			callMdef(handle,M_CHOOSE,rect,-1,-1,which);
			return graphical;}
		finally{tb.offscreenMdef--;tb.windows.overrideWMgrPort(0);if(savedPort!=0)setPort(savedPort);tb.api.quickDraw().disposeScratchPort(port);memory.free(rect);memory.free(which);}
	}


	/**
	 * CalcMenuSize: the Menu Manager runs the MDEF's mSizeMsg in the Window Manager port, so the items are measured in the
	 * system font the menu is shown in - not in whatever port the program has current. MacWeb's form SELECT menus have a
	 * small MDEF of MacWeb's own that hands every message to the System's MDEF 0; MacWeb then draws the chosen item in the
	 * system font in a box as wide as menuWidth. Measured in its document window (Monaco 9 at that moment) the item Green showed
	 * cut to Gree. A custom MDEF that wants another font sets it itself (Infini-D's view pop-up).
	 */
	private void sizeInWMgrPort(int handle) throws M68kCpuException {
		int saved=tb.api.quickDraw().currentPortAddress(),wmgr=memory.readLong(0xd2c);
		if(wmgr==0||saved==wmgr){customSize(handle);return;}
		setPort(wmgr);
		try{customSize(handle);}finally{if(saved!=0)setPort(saved);}
	}

	/** {width, height} after the MDEF's mSizeMsg (which stores them in the record). */
	private int[] customSize(int handle) throws M68kCpuException {
		int rect=memory.allocate(8,2),which=memory.allocate(2,2);
		try{callMdef(handle,M_SIZE,rect,0,0,which);}finally{memory.free(rect);memory.free(which);}
		int p=memory.readLong(handle);if(p==0)return null;int width=(short)memory.readWord(p+2),height=(short)memory.readWord(p+4);
		if(width<=0||height<=0||width>2048||height>2048)return null;return new int[]{width,height};
	}
	/** The Menu Manager calls an MDEF with its port clipped to the menu's rectangle. A wide-open clip does not survive the
	 *  MDEF's own OffsetRgn (the coordinates wrap at 16 bits): ResEdit's pattern palette clipped every swatch away. */
	private void clipTo(int rect) throws M68kCpuException {M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],rect);tb.api.quickDraw().handle(cpu,0x87b);cpu.a[7]=saved;}
	/** A QuickDraw procedure with one long argument, and a function without arguments returning a long, called directly. */
	private void qdProc(int trap,int arg) throws M68kCpuException {M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],arg);tb.api.quickDraw().handle(cpu,trap);cpu.a[7]=saved;}
	private int qdFunction(int trap) throws M68kCpuException {M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],0);tb.api.quickDraw().handle(cpu,trap);int v=memory.readLong(cpu.a[7]);cpu.a[7]=saved;return v;}
	private void setOrigin(int h,int v) throws M68kCpuException {M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeWord(cpu.a[7],v);memory.writeWord(cpu.a[7]+2,h);tb.api.quickDraw().handle(cpu,0x878);cpu.a[7]=saved;}
	private void setPort(int port) throws M68kCpuException {M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}
	private void writeRect(int at,int top,int left,int bottom,int right) throws M68kCpuException {memory.writeWord(at,top);memory.writeWord(at+2,left);memory.writeWord(at+4,bottom);memory.writeWord(at+6,right);}

	/** Debug aid (commands.txt `menus`): the menu bar and hierarchical menus with their items, command keys and enable state. */
	String describeMenus() throws M68kCpuException {
		StringBuilder sb=new StringBuilder();
		List<Integer> all=new ArrayList<Integer>(menuBar);all.addAll(hierarchical);
		for(int h:all){Menu m=read(h);if(m==null)continue;sb.append(String.format("%nmenu %d \"%s\"%s $%X",m.id,m.title,(m.enableFlags&1)==0?" (disabled)":"",h));int n=0;
			for(Item item:m.items){n++;boolean enabled=n>31||(m.enableFlags&(1<<n))!=0;sb.append(String.format("%n  %2d %s\"%s\"%s%s",n,enabled?"":"- ",item.text,item.cmd>=0x20?" cmd-"+(char)item.cmd:"",item.cmd==0x1b?" submenu "+item.mark:item.mark==0x12?" (checked)":item.mark!=0?" mark $"+Integer.toHexString(item.mark):""));}}
		return sb.toString();
	}
	/**
	 * MenuEvent: a key-down's command key as MenuKey finds it, except that the item's modifiers (SetMenuItemModifiers:
	 * shift 1, option 2, control 4, no command 8) must be the event's own - REALbasic has Save as cmd-S and Save As as
	 * shift-cmd-S. Other events give 0.
	 */
	private int menuEvent(int event) throws M68kCpuException {
		if(event==0)return 0;int what=memory.readWord(event),message=memory.readLong(event+2),modifiers=memory.readWord(event+14);
		if(what!=EventManager68k.KEY_DOWN&&what!=EventManager68k.AUTO_KEY)return 0;
		if(pendingKeyMenu!=0)return menuKey(0);
		int wanted=((modifiers&0x200)!=0?1:0)|((modifiers&0x800)!=0?2:0)|((modifiers&0x1000)!=0?4:0)|((modifiers&0x100)==0?8:0);
		char lower=Character.toLowerCase((char)(message&0xff));
		List<Integer> all=new ArrayList<Integer>(menuBar);all.addAll(hierarchical);
		for(int h:all){Menu m=read(h);if(m==null||(m.enableFlags&1)==0)continue;int n=0;
			for(Item item:m.items){n++;boolean enabled=n>31||(m.enableFlags&(1<<n))!=0;if(!enabled||item.cmd<0x20||Character.toLowerCase((char)item.cmd)!=lower)continue;
				int[] d=itemData(h,n,false);if((d==null?0:d[slot(0x04)])==wanted)return (m.id<<16)|n;}}
		return 0;
	}
	private int menuKey(int ch) throws M68kCpuException {
		if(pendingKeyMenu!=0){int r=(pendingKeyMenu<<16)|(pendingKeyItem&0xffff);pendingKeyMenu=0;pendingKeyItem=0;return r;}
		char lower=Character.toLowerCase((char)ch);
		List<Integer> all=new ArrayList<Integer>(menuBar);all.addAll(hierarchical);
		for(int h:all){Menu m=read(h);if(m==null||(m.enableFlags&1)==0)continue;int n=0;for(Item item:m.items){n++;boolean enabled=n>31||(m.enableFlags&(1<<n))!=0;if(enabled&&item.cmd>=0x20&&Character.toLowerCase((char)item.cmd)==lower)return (m.id<<16)|n;}}
		return 0;
	}

	private boolean popUpMenuSelect(M68kCpu cpu) throws M68kCpuException {
		int popUpItem=(short)memory.readWord(cpu.a[7]),left=(short)memory.readWord(cpu.a[7]+2),top=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
		Menu m=read(h);
		int result=0;
		if(m!=null){int item=-1;
			if(LIVE_MDEF&&customMdef(h)&&tb.host()!=null)item=popUpLive(h,m.items.size(),left,top,popUpItem);
			if(item<0){MacAppHost.MenuModel model=model(m,0);
				// a custom MDEF over text items that hands them to MDEF 0 after TextSize: the host menu in that size
				Integer size=customMdef(h)?mdefTextSize.get(h):null;if(size!=null&&size!=12&&model.image==null)model.fontSize=size;
				item=tb.host().popUpMenuSelect(model,left,top,popUpItem);}
			if(item>0)result=item>0xffff?item:(m.id<<16)|item;} // above $FFFF: a submenu's (ID << 16) | item
		// the choice in MenuDisable too: BagOtricks' HPopupMenu takes only the item from the result and reads which menu (the pop-up
		// or one of its submenus) from MenuDisable - it always answered 0
		memory.writeLong(MENU_DISABLE,result);
		memory.writeLong(cpu.a[7],result);return true;
	}
	/**
	 * MBSaveLoc ($B5C): the menu bar definition's data, which the MBDF allocates at InitMenus - lastMBSave (+0),
	 * mbCustomStorage (+2), mbItemRect (+6), mbMenuDelay/mbMenuDrag (+14/+15), mbUglyScroll (+16), mbIconState (+18), then the
	 * saved menus. Menu definitions read and write it: the Mercutio MDEF (Audiodeck 2.0.6) keeps the item rectangle and its
	 * scroll flag there, behind nil checks that stopped it with TRAP #0 on a NIL handle.
	 */
	static final int MB_SAVE_LOC=0xb5c;
	private void menuBarSaveData() throws M68kCpuException {
		if(memory.readLong(MB_SAVE_LOC)!=0)return;
		int h=tb.api.createHandle(new byte[256]);if(h!=0)memory.writeLong(MB_SAVE_LOC,h);
	}
	/** MenuDisable ($B54): the menu ID and item of the last choice, a disabled item's too, as MenuChoice returns it. */
	static final int MENU_DISABLE=0xb54;

	/** Pops menu {@code h} up with its top left at the global point: (menu ID << 16) | item, 0 when nothing was chosen. */
	int popUp(int h,int left,int top) throws M68kCpuException {
		Menu m=read(h);if(m==null||tb.host()==null)return 0;
		int item=tb.host().popUpMenuSelect(model(m,0),left,top,0);
		return item>0xffff?item:item>0?(m.id<<16)|item:0;
	}

	/** A menu Handle for the given id (used by popup controls). */

	/** The Help menu's ID (kHMHelpMenuID): the system's menu, but the items a program appends to it are the program's. */
	static final int HELP_MENU_ID=-16490;
	/**
	 * Whether a choice in menu {@code id} goes back to the program from MenuSelect: its own menus (positive IDs) and the
	 * items it appended to the Help menu (PageMill's "Contents…" came back as 0, so Help did nothing). Other system menus and
	 * desk accessory menus keep their own handling.
	 */
	static boolean programMenu(int id){return id>0||id==HELP_MENU_ID;}

	int helpMenuHandle() throws M68kCpuException {
		if(helpMenu!=0)return helpMenu;
		Menu m=new Menu();m.id=HELP_MENU_ID;m.title="Help";
		// System 7's Help menu starts with the Help Manager's own items (getHelpMenuItems listed none); an external's go after them.
		// Only HyperCard's externals for now: a program's own VM keeps its items from 1 on (no 68K program here relies on them)
		if(tb.systemVersion()<0x0800&&tb.app==null&&tb.stack!=null){for(String t:new String[]{"About Balloon Help…","-",tb.help.balloons()?"Hide Balloons":"Show Balloons"}){Item it=new Item();it.text=t;m.items.add(it);}
			m.enableFlags=-1&~(1<<2);helpSystemItems=m.items.size();}
		helpMenu=store(m,0);menuBar.add(helpMenu);changed();return helpMenu;
	}
	/** How many of the Help menu's first items are the system's (shown by the Help Manager, never chosen through the program). */
	private int helpSystemItems;
}

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Data Browser control (CarbonLib 1.1, ControlDefinitions.h of Universal Interfaces 3.4): a list view (columns with
 * header buttons, hierarchical rows under a disclosure column, sorting, selection, scrolling) and a basic column view.
 * <p>
 * A browser is an ordinary ControlHandle in its window's control list whose definition is implemented in Java
 * ({@link ControlManager68k.NativeDefinition}), so the Control Manager calls work on it - bounds, visibility, highlighting,
 * Draw1Control/UpdateControls, FindControl/TrackControl/HandleControlClick, DisposeControl/KillControls. The browser keeps
 * only the item hierarchy (IDs, parents, open containers, selection); everything it shows is pulled from the program's
 * item-data callback each time it draws, through a DataBrowserItemDataRef the Set/GetDataBrowserItemData... calls
 * understand, and the program hears of changes through its item-notification callback, as with CarbonLib.
 * <p>
 * Drawing is Java2D in Platinum colours (the way the Control Manager draws its standard controls), with the program's own
 * DrawItem callback called afterwards, in the window's port clipped to the cell, for custom-type columns. Left out: drag
 * and drop (the drag callbacks are kept, never called), in-place text editing, help tags, icons (IconRefs have no Icon
 * Services behind them here: the space is kept).
 */
final class PpcDataBrowser {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcDataBrowser(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int LIST_VIEW=0x6c737476,COLUMN_VIEW=0x636c6d76; // 'lstv', 'clmv'
	/** The procID of the Data Browser's controls: a CDEF ID (2047) no System or program uses. */
	static final int PROC_ID=2047<<4;
	/** kControlDataBrowserPart: what the browser's hit test and tracking answer. */
	static final int PART=24;
	private static final int NO_ERR=0,PARAM_ERR=-50,NOT_CONFIGURED=-4970,ITEM_NOT_FOUND=-4971,PROPERTY_NOT_FOUND=-4972,INVALID_DATA=-4974,NOT_ADDED=-4975;
	// DataBrowserItemNotification
	private static final int ADDED=1,REMOVED=2,SELECTED=5,DESELECTED=6,DOUBLE_CLICKED=7,OPENED=8,CLOSING=9,CLOSED=10,SORTING=11,SORTED=12,SELECTION_CHANGED=14,TARGET_CHANGED=15;
	// DataBrowserPropertyType
	private static final int T_CUSTOM=0x3f3f3f3f,T_ICON=0x69636e72,T_TEXT=0x74657874,T_DATE=0x64617465,T_SLIDER=0x736c6472,T_CHECK=0x63686278,T_PROGRESS=0x70726f67,T_RANK=0x72616e6b,T_MENU=0x6d656e75,T_ICON_TEXT=0x7469636e;
	// predefined properties
	private static final int P_SELECTABLE=2,P_CONTAINER=4,P_OPENABLE=5,P_CLOSABLE=6,P_SELF=8;
	// DataBrowserSelectionFlags
	private static final int ONLY_ONE=2,RESET=4,NO_DISJOINT=16,ALWAYS_EXTEND=32,NEVER_EMPTY=64;
	private static final int MUTABLE=1,SORTABLE_COLUMN=1<<18,DEFAULT_COLUMN_FLAGS=(1<<17)|(1<<18);
	// slots of DataBrowserCallbacks.u.v1 / DataBrowserCustomCallbacks.u.v1
	private static final int CB_DATA=0,CB_COMPARE=1,CB_NOTIFY=2,CB_GET_MENU=8,CB_SELECT_MENU=9,CC_DRAW=0,CC_HIT=2,CC_TRACK=3;
	private static final int SB=16,INDENT=16,ICON_SPACE=20;
	private static final int CMD=0x100,SHIFT=0x200,CONTROL=0x1000;

	/** A Mixed Mode procInfo (Pascal convention): size codes 1 = byte, 2 = word, 3 = long. */
	private static int procInfo(int result,int... params){int p=result<<4;for(int i=0;i<params.length;i++)p|=params[i]<<(6+2*i);return p;}
	private static final int PI_DATA=procInfo(3,3,3,3,3,1),PI_COMPARE=procInfo(1,3,3,3,3),PI_NOTIFY=procInfo(0,3,3,3),PI_ITEM=procInfo(0,3,3,3),
		PI_DRAW=procInfo(0,3,3,3,3,3,2,1),PI_HIT=procInfo(1,3,3,3,3,3),PI_TRACK=procInfo(2,3,3,3,3,3,2),PI_GET_MENU=procInfo(0,3,3,3,3,3),PI_SELECT_MENU=procInfo(0,3,3,3,2,2);
	/** Every DataBrowser...UPP type with its procInfo (New/Dispose/Invoke). */
	private static final Object[][] UPPS={{"ItemData",PI_DATA},{"ItemCompare",PI_COMPARE},{"ItemNotification",PI_NOTIFY},{"AddDragItem",procInfo(1,3,3,3,3)},
		{"AcceptDrag",procInfo(1,3,3,3)},{"ReceiveDrag",procInfo(1,3,3,3)},{"PostProcessDrag",procInfo(0,3,3,3)},{"GetContextualMenu",PI_GET_MENU},
		{"SelectContextualMenu",PI_SELECT_MENU},{"ItemHelpContent",procInfo(0,3,3,3,2,3,3)},{"Item",PI_ITEM},{"DrawItem",PI_DRAW},{"EditItem",procInfo(1,3,3,3,3,3,3)},
		{"HitTest",PI_HIT},{"Tracking",PI_TRACK},{"ItemDragRgn",procInfo(0,3,3,3,3,3)},{"ItemAcceptDrag",procInfo(3,3,3,3,3,3)},{"ItemReceiveDrag",procInfo(1,3,3,3,3,3)},
		// CarbonLib 1.6 also exports the pre-release names of three of them (same callbacks) and the notification callback that gets the item's data:
		// DataBrowserItemNotificationWithItemProcPtr(browser, item, message, DataBrowserItemDataRef itemData)
		{"Compare",PI_COMPARE},{"GetSetItemData",PI_DATA},{"GetHelpContent",procInfo(0,3,3,3,2,3,3)},{"ItemNotificationWithItem",procInfo(0,3,3,3,3)}};

	private static final Color FRAME=new Color(0x77,0x77,0x77),FRAME_OFF=new Color(0xaa,0xaa,0xaa),HEADER=new Color(0xdd,0xdd,0xdd),HEADER_SORTED=new Color(0xbb,0xbb,0xbb),
		SORT_TINT=new Color(0xf0,0xf0,0xf0),SHADOW=new Color(0x99,0x99,0x99),INACTIVE_SELECTION=new Color(0xdd,0xdd,0xdd),GREY_TEXT=new Color(0x88,0x88,0x88),BAR=new Color(0x66,0x66,0xcc);

	// ------------------------------------------------------------------ model

	/** An item: its ID, parent container, children while it is an open container. */
	static final class Item {
		final int id;Item parent;List<Item> children;boolean selected;Boolean container;int rowHeight;
		Item(int id){this.id=id;}
		int state(){return (selected?1:0)|(children!=null?2:0);} // kDataBrowserItemIsSelected, kDataBrowserContainerIsOpen
	}
	/** A list-view column: its DataBrowserPropertyDesc and header description. */
	static final class Column {int property,type,flags,min,max=0xffff,titleOffset,initialOrder=1,width;String title="";int fontFlags,font,size,face,mode,just,contentType,content;byte[] colors=new byte[12];}
	/** One displayed row of the list view. */
	static final class Row {final Item item;final int depth,top,height;Row(Item item,int depth,int top,int height){this.item=item;this.depth=depth;this.top=top;this.height=height;}}
	/** What the item-data callback handed back for one (item, property), or what a set request hands it. */
	static final class Cell {
		static final int TEXT=1,ICON=2,VALUE=4,MIN=8,MAX=16,BOOL=32,MENU=64,RGB=128,DRAW_STATE=256,BUTTON=512,TRANSFORM=1024,DATE=2048,ITEM_ID=4096;
		int item,property,type,status,mask;String text;int icon,value,min,max,bool,menu,drawState,button,transform,itemId;int[] rgb;long date;
		boolean has(int bit){return (mask&bit)!=0;}
	}
	/** The on-screen geometry of a browser (window-local). */
	static final class Layout {
		int top,left,bottom,right,viewTop,viewLeft,viewBottom,viewRight;int[] vbar,hbar;int contentHeight,contentWidth;
		int maxTop(){return Math.max(0,contentHeight-(viewBottom-viewTop));}
		int maxLeft(){return Math.max(0,contentWidth-(viewRight-viewLeft));}
	}

	private final Map<Integer,Browser> browsers=new HashMap<Integer,Browser>();
	/** The browser holding the keyboard focus of each window (SetKeyboardFocus). */
	private final Map<Integer,Integer> focus=new HashMap<Integer,Integer>();
	/** The DataBrowserItemDataRefs of the item-data calls in progress (a small guest block each, recycled). */
	private final Map<Integer,Cell> cells=new HashMap<Integer,Cell>();
	private final ArrayDeque<Integer> freeRefs=new ArrayDeque<Integer>();

	/** One Data Browser control. */
	final class Browser implements ControlManager68k.NativeDefinition {
		int control,style;
		final int[] callbacks=new int[10],custom=new int[7];
		final Map<Integer,Item> items=new HashMap<Integer,Item>();
		Item root=newRoot(0);
		final List<Column> columns=new ArrayList<Column>();
		int sortProperty,sortOrder=1,selectionFlags,rowHeight=16,headerHeight=16,columnWidth=100,hiliteStyle,disclosure,scrollTop,scrollLeft,editItem,editProperty;
		boolean hasH=true,hasV=true,expandable=true,activeItems=true,plainBackground,variableWidth=true,variableHeight;
		final int[] scrollInset=new int[4];
		Item anchor;int lastClickItem,lastClickTicks;
		String typed="";int typedTicks;
		/** Column view: the containers shown, the target first. */
		final List<Item> path=new ArrayList<Item>();int displayType=T_ICON_TEXT;
		List<Row> rows;
		Browser(int style){this.style=style;path.add(root);}

		@Override public void draw(int handle) throws M68kException {if(control==0)control=handle;paint(this);}
		@Override public int test(int handle,int h,int v){return PART;}
		@Override public int track(int handle,int h,int v,int modifiers) throws M68kException {if(control==0)control=handle;return click(this,h,v,modifiers);}
		@Override public int key(int handle,int keyCode,int charCode,int modifiers) throws M68kException {return PpcDataBrowser.this.key(this,charCode&0xff,modifiers);}
		@Override public void dispose(int handle){browsers.remove(handle);focus.values().removeIf(c->c==handle);}
	}
	private static Item newRoot(int id){Item r=new Item(id);r.children=new ArrayList<Item>();r.container=true;return r;}

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private boolean japanese(){return rt.toolbox.encoding==StackTextEncoding.JAPANESE;}
	private int ticks() throws M68kCpuException {return memory.readLong(0x16a);}
	private Column column(Browser b,int property){for(Column c:b.columns)if(c.property==property)return c;return null;}

	// ------------------------------------------------------------------ callbacks

	/** Calls a program callback (a routine descriptor, or a bare transition vector) and narrows its result. */
	private int invoke(int upp,int procInfo,int... args) throws M68kException {
		if(upp==0)return 0;
		int r=rt.isRoutineDescriptor(upp)?rt.callUniversal(upp,procInfo,args):rt.cpu.call(upp,args);
		switch((procInfo>>>4)&3){case 1:return r&0xff;case 2:return (short)r;default:return r;}
	}
	private void notify(Browser b,int item,int message) throws M68kException {if(b.callbacks[CB_NOTIFY]!=0)invoke(b.callbacks[CB_NOTIFY],PI_NOTIFY,b.control,item,message);}
	/** The item-data callback for (item, property): a get request fills a new cell; a set request ({@code set} non-null) hands it the cell. */
	private Cell itemData(Browser b,int item,int property,Cell set) throws M68kException {
		Cell cell=set!=null?set:new Cell();cell.item=item;cell.property=property;Column col=column(b,property);cell.type=col!=null?col.type:property>=1&&property<=7?T_CHECK:property==P_SELF?T_ICON_TEXT:0; // the predefined Boolean properties read as check boxes
		if(b.callbacks[CB_DATA]==0){cell.status=NOT_CONFIGURED;return cell;}
		int ref=freeRefs.isEmpty()?rt.allocate(16,4):freeRefs.pop();memory.writeLong(ref,0x687a4442);memory.writeLong(ref+4,item);memory.writeLong(ref+8,property); // 'hzDB'
		cells.put(ref,cell);
		try{cell.status=invoke(b.callbacks[CB_DATA],PI_DATA,b.control,item,property,ref,set!=null?1:0);}
		finally{cells.remove(ref);freeRefs.push(ref);}
		return cell;
	}
	/** A Boolean-typed property of an item (IsContainer, IsSelectable ...), {@code otherwise} when the program does not give it. */
	private boolean flag(Browser b,Item item,int property,boolean otherwise) throws M68kException {
		Cell c=itemData(b,item.id,property,null);return c.status==NO_ERR&&c.has(Cell.BOOL)?c.bool!=0:otherwise;
	}
	private boolean isContainer(Browser b,Item item) throws M68kException {if(item.container==null)item.container=flag(b,item,P_CONTAINER,false);return item.container;}

	// ------------------------------------------------------------------ structure

	private Item container(Browser b,int id){return id==0||id==b.root.id?b.root:b.items.get(id);}
	/** The displayed rows of the list view: the target's items, the items of open containers under their container. */
	private List<Row> rows(Browser b){
		if(b.rows!=null)return b.rows;
		List<Row> out=new ArrayList<Row>();int[] top={0};addRows(b,b.root,0,out,top);b.rows=out;return out;
	}
	private void addRows(Browser b,Item parent,int depth,List<Row> out,int[] top){
		if(parent.children==null)return;
		for(Item it:parent.children){int h=it.rowHeight>0?it.rowHeight:b.rowHeight;out.add(new Row(it,depth,top[0],h));top[0]+=h;if(it.children!=null)addRows(b,it,depth+1,out,top);}
	}
	private int rowIndex(Browser b,Item it){List<Row> rows=rows(b);for(int i=0;i<rows.size();i++)if(rows.get(i).item==it)return i;return -1;}
	/** Items of a container in display order ({@code recurse}: with the items of open containers below them). */
	private void collect(Item parent,boolean recurse,int state,List<Item> out){
		if(parent.children==null)return;
		for(Item it:parent.children){if(state==-1||(it.state()&state)==state)out.add(it);if(recurse&&it.children!=null)collect(it,true,state,out);}
	}
	private List<Item> selection(Browser b){List<Item> out=new ArrayList<Item>();for(Row r:rows(b))if(r.item.selected)out.add(r.item);
		if(b.style==COLUMN_VIEW||out.isEmpty())for(Item it:b.items.values())if(it.selected&&!out.contains(it))out.add(it);return out;}

	private int addItems(Browser b,int containerId,int n,int list,int preSort) throws M68kException {
		Item parent=container(b,containerId);if(parent==null||parent.children==null)return NOT_ADDED;
		List<Item> added=new ArrayList<Item>();
		for(int i=0;i<n;i++){
			int id=list==0?i+1:memory.readLong(list+4*i); // NIL items: the IDs 1..numItems
			if(id==0||b.items.containsKey(id)||id==b.root.id)continue;
			Item it=new Item(id);it.parent=parent;parent.children.add(it);b.items.put(id,it);added.add(it);
		}
		b.rows=null;
		for(Item it:added)notify(b,it.id,ADDED);
		if(!added.isEmpty()&&b.sortProperty!=0)sortContainer(b,parent,false);
		redraw(b);return NO_ERR;
	}
	private int removeItems(Browser b,int containerId,int n,int list) throws M68kException {
		Item parent=container(b,containerId);if(parent==null)return ITEM_NOT_FOUND;
		List<Item> victims=new ArrayList<Item>();
		if(list==0){if(parent.children!=null)victims.addAll(parent.children);}
		else for(int i=0;i<n;i++){Item it=b.items.get(memory.readLong(list+4*i));if(it!=null&&(containerId==0||it.parent==parent))victims.add(it);}
		remove(b,victims);redraw(b);return NO_ERR;
	}
	/** Takes items (and whatever is below them) out of the browser: deselected first, then removed. */
	private void remove(Browser b,List<Item> victims) throws M68kException {
		List<Item> gone=new ArrayList<Item>();for(Item it:victims)subtree(it,gone);
		List<Item> wasSelected=new ArrayList<Item>();for(Item it:gone)if(it.selected){it.selected=false;wasSelected.add(it);}
		for(Item it:victims)if(it.parent!=null&&it.parent.children!=null)it.parent.children.remove(it);
		for(Item it:gone){b.items.remove(it.id);if(b.anchor==it)b.anchor=null;int at=b.path.indexOf(it);if(at>0)while(b.path.size()>at)b.path.remove(b.path.size()-1);}
		b.rows=null;
		for(Item it:wasSelected)notify(b,it.id,DESELECTED);
		for(Item it:gone)notify(b,it.id,REMOVED);
		if(!wasSelected.isEmpty())notify(b,0,SELECTION_CHANGED);
	}
	private static void subtree(Item it,List<Item> out){if(it.children!=null)for(Item c:it.children)subtree(c,out);out.add(it);}

	private int openContainer(Browser b,Item it) throws M68kException {
		if(it.children!=null)return NO_ERR;
		it.children=new ArrayList<Item>();it.container=true;b.rows=null;
		notify(b,it.id,OPENED); // the program adds the container's items now
		if(b.sortProperty!=0)sortContainer(b,it,false);
		b.rows=null;return NO_ERR;
	}
	private int closeContainer(Browser b,Item it) throws M68kException {
		if(it.children==null||it==b.root)return NO_ERR;
		notify(b,it.id,CLOSING);
		remove(b,new ArrayList<Item>(it.children));it.children=null;b.rows=null;
		notify(b,it.id,CLOSED);return NO_ERR;
	}

	// ------------------------------------------------------------------ sorting

	/** Sorts a container's items by the sort property and order (a stable merge sort: the compare callback, else the item data). */
	private void sortContainer(Browser b,Item container,boolean children) throws M68kException {
		if(container.children==null)return;
		if(b.sortProperty!=0&&container.children.size()>1){
			notify(b,container.id,SORTING);
			Map<Item,SortKey> keys=null;
			if(b.callbacks[CB_COMPARE]==0){keys=new HashMap<Item,SortKey>();for(Item it:container.children)keys.put(it,sortKey(b,it));}
			List<Item> sorted=mergeSort(b,new ArrayList<Item>(container.children),keys);
			container.children.clear();container.children.addAll(sorted);b.rows=null;
			notify(b,container.id,SORTED);
		}
		if(children)for(Item it:new ArrayList<Item>(container.children))if(it.children!=null)sortContainer(b,it,true);
	}
	private List<Item> mergeSort(Browser b,List<Item> list,Map<Item,SortKey> keys) throws M68kException {
		if(list.size()<2)return list;
		int mid=list.size()/2;List<Item> l=mergeSort(b,new ArrayList<Item>(list.subList(0,mid)),keys),r=mergeSort(b,new ArrayList<Item>(list.subList(mid,list.size())),keys);
		List<Item> out=new ArrayList<Item>(list.size());int i=0,j=0;
		while(i<l.size()&&j<r.size()){if(before(b,r.get(j),l.get(i),keys))out.add(r.get(j++));else out.add(l.get(i++));}
		while(i<l.size())out.add(l.get(i++));while(j<r.size())out.add(r.get(j++));
		return out;
	}
	/** Whether {@code x} goes strictly before {@code y} in the browser's sort order. */
	private boolean before(Browser b,Item x,Item y,Map<Item,SortKey> keys) throws M68kException {
		if(b.sortOrder==2){Item t=x;x=y;y=t;} // kDataBrowserOrderDecreasing
		if(keys==null)return invoke(b.callbacks[CB_COMPARE],PI_COMPARE,b.control,x.id,y.id,b.sortProperty)!=0;
		return keys.get(x).compareTo(keys.get(y))<0; // equal keys keep their order
	}
	/** The default sort key of an item: its data for the sort property as the column's type has it. */
	private SortKey sortKey(Browser b,Item it) throws M68kException {
		Column col=column(b,b.sortProperty);Cell c=itemData(b,it.id,b.sortProperty,null);int type=col==null?T_TEXT:col.type;
		if(type==T_DATE)return new SortKey(null,c.has(Cell.DATE)?c.date:0L);
		if(type==T_CHECK)return new SortKey(null,c.button);
		if(type==T_SLIDER||type==T_PROGRESS||type==T_RANK||type==T_MENU)return new SortKey(null,c.value);
		if(c.has(Cell.TEXT))return new SortKey(c.text.toLowerCase(),0);
		if(c.has(Cell.VALUE))return new SortKey(null,c.value);
		return new SortKey("",0);
	}
	/** A default sort key: a text (compared case-insensitively) or a number; numbers before texts. */
	private static final class SortKey implements Comparable<SortKey> {
		final String text;final long number;
		SortKey(String text,long number){this.text=text;this.number=number;}
		@Override public int compareTo(SortKey o){if(text==null&&o.text==null)return Long.compare(number,o.number);if(text==null)return -1;if(o.text==null)return 1;return text.compareTo(o.text);}
	}

	// ------------------------------------------------------------------ selection

	/** Applies a DataBrowserSetOption to the selection; notifies the changes (deselections first, then selections, then the set). */
	private void select(Browser b,List<Item> items,int operation) throws M68kException {
		Set<Item> after=new LinkedHashSet<Item>(selection(b));
		switch(operation){
			case 1:after.clear();after.addAll(items);break;                                         // kDataBrowserItemsAssign
			case 2:for(Item it:items)if(!after.remove(it))after.add(it);break;                          // kDataBrowserItemsToggle
			case 3:after.removeAll(items);break;                                                      // kDataBrowserItemsRemove
			default:after.addAll(items);break;                                                        // kDataBrowserItemsAdd
		}
		commitSelection(b,after);
	}
	private void commitSelection(Browser b,Set<Item> after) throws M68kException {
		List<Item> off=new ArrayList<Item>(),on=new ArrayList<Item>();
		for(Item it:b.items.values())if(it.selected&&!after.contains(it)){it.selected=false;off.add(it);}
		for(Item it:after)if(!it.selected&&b.items.get(it.id)==it){it.selected=true;on.add(it);}
		if(off.isEmpty()&&on.isEmpty())return;
		for(Item it:off)notify(b,it.id,DESELECTED);
		for(Item it:on)notify(b,it.id,SELECTED);
		notify(b,0,SELECTION_CHANGED);
		redraw(b);
	}
	private List<Item> itemList(Browser b,int n,int list) throws M68kCpuException {
		List<Item> out=new ArrayList<Item>();if(list==0)return out;
		for(int i=0;i<n;i++){Item it=b.items.get(memory.readLong(list+4*i));if(it!=null)out.add(it);}
		return out;
	}

	// ------------------------------------------------------------------ geometry

	private Layout layout(Browser b) throws M68kCpuException {
		int d=memory.readLong(b.control);Layout L=new Layout();
		L.top=(short)memory.readWord(d+8);L.left=(short)memory.readWord(d+10);L.bottom=(short)memory.readWord(d+12);L.right=(short)memory.readWord(d+14);
		L.viewRight=b.hasV?L.right-SB:L.right-1;L.viewBottom=b.hasH?L.bottom-SB:L.bottom-1;
		if(b.hasV)L.vbar=new int[]{L.top+b.scrollInset[0],L.right-SB,(b.hasH?L.bottom-SB+1:L.bottom)-b.scrollInset[2],L.right};
		if(b.hasH)L.hbar=new int[]{L.bottom-SB,L.left+b.scrollInset[1],L.bottom,(b.hasV?L.right-SB+1:L.right)-b.scrollInset[3]};
		L.viewLeft=L.left+1;L.viewTop=L.top+1+(b.style==LIST_VIEW?b.headerHeight:0);
		if(b.style==LIST_VIEW){
			List<Row> rows=rows(b);L.contentHeight=rows.isEmpty()?0:rows.get(rows.size()-1).top+rows.get(rows.size()-1).height;
			for(Column c:b.columns)L.contentWidth+=c.width;
		}else{
			int most=0;for(Item p:b.path)if(p.children!=null)most=Math.max(most,p.children.size());
			L.contentHeight=most*b.rowHeight;L.contentWidth=b.path.size()*b.columnWidth;
		}
		b.scrollTop=Math.max(0,Math.min(b.scrollTop,L.maxTop()));b.scrollLeft=Math.max(0,Math.min(b.scrollLeft,L.maxLeft()));
		return L;
	}
	/** Left edge of a column (window-local), -1 when the property is no column. */
	private int columnX(Browser b,Layout L,int property){int x=L.viewLeft-b.scrollLeft;for(Column c:b.columns){if(c.property==property)return x;x+=c.width;}return Integer.MIN_VALUE;}
	private Column columnAt(Browser b,Layout L,int h){int x=L.viewLeft-b.scrollLeft;for(Column c:b.columns){if(h>=x&&h<x+c.width)return c;x+=c.width;}return null;}
	private Row rowAt(Browser b,Layout L,int v){int y=v-L.viewTop+b.scrollTop;for(Row r:rows(b))if(y>=r.top&&y<r.top+r.height)return r;return null;}
	/** Where the content of a cell starts: after the indentation and the disclosure triangle in the disclosure column. */
	private int contentLeft(Browser b,Column c,Row r,int x){
		boolean disclosureColumn=b.disclosure!=0?c.property==b.disclosure:!b.columns.isEmpty()&&b.columns.get(0)==c;
		if(!disclosureColumn||(b.disclosure==0&&r.depth==0))return x+c.titleOffset;
		return x+r.depth*INDENT+(b.disclosure!=0&&b.expandable?INDENT:0)+c.titleOffset;
	}
	/** {top, left, bottom, right} of an item's part (window-local), null when the item is not displayed. */
	private int[] partBounds(Browser b,Item it,int property,int part) throws M68kException {
		Layout L=layout(b);
		if(b.style==COLUMN_VIEW){
			for(int i=0;i<b.path.size();i++){Item p=b.path.get(i);if(p.children==null)continue;int at=p.children.indexOf(it);if(at<0)continue;
				int x=L.viewLeft-b.scrollLeft+i*b.columnWidth,y=L.viewTop-b.scrollTop+at*b.rowHeight;return new int[]{y,x,y+b.rowHeight,x+b.columnWidth};}
			return null;
		}
		int index=rowIndex(b,it);if(index<0)return null;Row r=rows(b).get(index);
		int y=L.viewTop-b.scrollTop+r.top;
		if(property==0)return new int[]{y,L.viewLeft,y+r.height,L.viewRight}; // the whole row
		Column c=column(b,property);if(c==null)return new int[0];
		int x=columnX(b,L,property),cl=contentLeft(b,c,r,x);
		switch(part){
			case 0x64697363:return new int[]{y,cl-INDENT,y+r.height,cl};                                  // kDataBrowserPropertyDisclosurePart
			case T_ICON:return new int[]{y+(r.height-16)/2,cl+2,y+(r.height-16)/2+16,cl+18};
			case T_TEXT:{int tl=cl+(c.type==T_ICON_TEXT?ICON_SPACE:0)+4;Cell cell=itemData(b,it.id,property,null);String t=cellText(b,c,cell);
				return new int[]{y,tl,y+r.height,Math.min(x+c.width,tl+Fonts68k.width(rowFont(),t))};}
			case 0x2d2d2d2d:return new int[]{y,cl,y+r.height,x+c.width};                                 // kDataBrowserPropertyContentPart
			case 0:return new int[]{y,x,y+r.height,x+c.width};                                            // kDataBrowserPropertyEnclosingPart
			default:return new int[]{y,cl,y+r.height,x+c.width};
		}
	}

	/** Scrolls so that an item (and the column of {@code property}) shows. */
	private void reveal(Browser b,Item it,int property,boolean centre) throws M68kException {
		Layout L=layout(b);int[] r=partBounds(b,it,property,0);if(r==null||r.length==0)r=partBounds(b,it,0,0);if(r==null)return;
		int height=L.viewBottom-L.viewTop,top=r[0]-L.viewTop+b.scrollTop,bottom=r[2]-L.viewTop+b.scrollTop;
		if(centre)b.scrollTop=top-(height-(bottom-top))/2;else if(top<b.scrollTop)b.scrollTop=top;else if(bottom>b.scrollTop+height)b.scrollTop=bottom-height;
		if(property!=0||b.style==COLUMN_VIEW){int width=L.viewRight-L.viewLeft,left=r[1]-L.viewLeft+b.scrollLeft,right=r[3]-L.viewLeft+b.scrollLeft;
			if(left<b.scrollLeft)b.scrollLeft=left;else if(right>b.scrollLeft+width)b.scrollLeft=Math.min(left,right-width);}
		layout(b);
	}

	// ------------------------------------------------------------------ drawing

	private void redraw(Browser b) throws M68kCpuException {if(b.control!=0&&browsers.containsKey(b.control))rt.toolbox.controls.draw(b.control);}
	private Font rowFont(){return japanese()?Fonts68k.font(Fonts68k.JAPANESE_SYSTEM_FONT,12,0):Fonts68k.font(1,10,0);} // kThemeViewsFont
	private Font headerFont(Column c){
		int id=japanese()?Fonts68k.JAPANESE_SYSTEM_FONT:1,size=10,face=0;                               // kThemeSmallSystemFont
		if((c.fontFlags&1)!=0&&c.font>0)id=c.font;if((c.fontFlags&4)!=0&&c.size>0)size=c.size;if((c.fontFlags&2)!=0)face=c.face&0x7f; // kControlUseFont/Size/FaceMask
		return Fonts68k.font(id,size,face);
	}
	/** The highlight colour (HiliteRGB, $DA0); a light blue when it was never set. */
	private Color hilite() throws M68kCpuException {int r=memory.readWord(0xda0)>>>8,g=memory.readWord(0xda2)>>>8,bl=memory.readWord(0xda4)>>>8;return r+g+bl==0?new Color(0xcc,0xcc,0xff):new Color(r,g,bl);}
	/** A custom-type cell whose drawing the program's DrawItem callback does after the browser's own drawing. */
	private static final class CustomCell {final int item,property,state;final int[] rect,clip;CustomCell(int item,int property,int state,int[] rect,int[] clip){this.item=item;this.property=property;this.state=state;this.rect=rect;this.clip=clip;}}

	/** Draws a browser: its rows (from the item-data callback), header, frame and scroll bars, then the custom cells. */
	private void paint(Browser b) throws M68kException {
		int window=rt.toolbox.controls.ownerWindow(b.control);if(window==0)return;
		Layout L=layout(b);int w=L.right-L.left,h=L.bottom-L.top;if(w<=0||h<=0||(long)w*h>16_000_000L)return;
		int d=memory.readLong(b.control);boolean active=memory.readByte(d+17)!=255&&memory.readByte(window+111)!=0; // control active, window hilited
		boolean focused=Integer.valueOf(b.control).equals(focus.get(window));
		BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D g=img.createGraphics();
		List<CustomCell> customs=new ArrayList<CustomCell>();
		try{
			g.translate(-L.left,-L.top);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
			g.setColor(Color.WHITE);g.fillRect(L.left,L.top,w,h);
			if(b.style==COLUMN_VIEW)paintColumnView(g,b,L,active);else paintListView(g,b,L,active,customs);
			g.setClip(null);
			g.setColor(focused&&active?hilite().darker():active?FRAME:FRAME_OFF);g.drawRect(L.left,L.top,w-1,h-1);
			if(focused&&active)g.drawRect(L.left+1,L.top+1,w-3,h-3);
			if(L.vbar!=null)ControlManager68k.drawScrollBar(g,L.vbar[1],L.vbar[0],L.vbar[3],L.vbar[2],b.scrollTop,0,L.maxTop(),0,!active);
			if(L.hbar!=null)ControlManager68k.drawScrollBar(g,L.hbar[1],L.hbar[0],L.hbar[3],L.hbar[2],b.scrollLeft,0,L.maxLeft(),0,!active);
			if(L.vbar!=null&&L.hbar!=null){g.setColor(HEADER);g.fillRect(L.right-SB+1,L.bottom-SB+1,SB-2,SB-2);}
		}finally{g.dispose();}
		blit(window,img,L.top,L.left,L.bottom,L.right);
		if(!customs.isEmpty()&&b.custom[CC_DRAW]!=0)drawCustom(b,window,customs);
	}
	private void paintListView(Graphics2D g,Browser b,Layout L,boolean active,List<CustomCell> customs) throws M68kException {
		Font font=rowFont();FontMetrics fm=Fonts68k.metrics(font);g.setFont(font);Color hl=active?hilite():INACTIVE_SELECTION;
		int viewW=L.viewRight-L.viewLeft,viewH=L.viewBottom-L.viewTop;
		if(viewW>0&&viewH>0){
			g.setClip(L.viewLeft,L.viewTop,viewW,viewH);
			int sx=columnX(b,L,b.sortProperty);Column sc=column(b,b.sortProperty);
			if(!b.plainBackground&&sc!=null){g.setColor(SORT_TINT);g.fillRect(sx,L.viewTop,sc.width,viewH);} // the sort column's tint (Platinum list views)
			for(Row r:rows(b)){
				int y=L.viewTop-b.scrollTop+r.top;if(y+r.height<=L.viewTop)continue;if(y>=L.viewBottom)break;
				boolean sel=r.item.selected;
				if(sel&&b.hiliteStyle==1){g.setColor(hl);g.fillRect(L.viewLeft,y,Math.max(viewW,L.contentWidth),r.height);} // kDataBrowserTableViewFillHilite
				int x=L.viewLeft-b.scrollLeft;
				for(Column c:b.columns){
					if(x<L.viewRight&&x+c.width>L.viewLeft){
						g.setClip(null);g.clipRect(L.viewLeft,L.viewTop,viewW,viewH);g.clipRect(x,y,c.width,r.height);
						paintCell(g,b,c,r,x,y,font,fm,sel&&b.hiliteStyle!=1?hl:null,active,customs,L);
					}
					x+=c.width;
				}
			}
			g.setClip(null);
		}
		if(b.headerHeight>0){ // header buttons
			int ht=L.top+1,hb=ht+b.headerHeight,x=L.viewLeft-b.scrollLeft;g.setClip(L.viewLeft,ht,Math.max(0,viewW),b.headerHeight);
			for(Column c:b.columns){
				boolean sorted=c.property==b.sortProperty&&b.sortProperty!=0;
				g.setColor(sorted?HEADER_SORTED:HEADER);g.fillRect(x,ht,c.width,b.headerHeight);
				g.setColor(Color.WHITE);g.drawLine(x,ht,x+c.width-2,ht);g.drawLine(x,ht,x,hb-2);
				g.setColor(SHADOW);g.drawLine(x+c.width-1,ht,x+c.width-1,hb-1);g.drawLine(x,hb-1,x+c.width-1,hb-1);
				Font hf=headerFont(c);FontMetrics hm=Fonts68k.metrics(hf);int room=c.width-8-(sorted?12:0);String t=fit(hf,c.title,room,2);
				int tw=Fonts68k.width(hf,t),tx=c.just==1?x+(c.width-tw)/2:c.just==-1?x+c.width-4-(sorted?12:0)-tw:x+4+c.titleOffset;
				g.setColor(active?Color.BLACK:GREY_TEXT);Fonts68k.drawString(g,hf,t,tx,ht+(b.headerHeight-hm.getHeight())/2+hm.getAscent());
				if(sorted){int ax=x+c.width-10,ay=ht+b.headerHeight/2;
					if(b.sortOrder==2)g.fillPolygon(new int[]{ax-3,ax+4,ax},new int[]{ay-2,ay-2,ay+2},3);else g.fillPolygon(new int[]{ax-3,ax+4,ax},new int[]{ay+2,ay+2,ay-2},3);}
				x+=c.width;
			}
			if(x<L.viewRight){g.setColor(HEADER);g.fillRect(x,ht,L.viewRight-x,b.headerHeight);g.setColor(SHADOW);g.drawLine(x,hb-1,L.viewRight,hb-1);}
			g.setClip(null);
		}
	}
	/** One list-view cell: triangle, content by the column's type, the minimal highlight. */
	private void paintCell(Graphics2D g,Browser b,Column c,Row r,int x,int y,Font font,FontMetrics fm,Color minimal,boolean active,List<CustomCell> customs,Layout L) throws M68kException {
		int cl=contentLeft(b,c,r,x),right=x+c.width;
		boolean disclosureColumn=b.disclosure!=0&&c.property==b.disclosure&&b.expandable;
		if(disclosureColumn&&isContainer(b,r.item)){ // the disclosure triangle: right when closed, down when open
			int tx=cl-INDENT+4,ty=y+r.height/2;g.setColor(active?Color.BLACK:GREY_TEXT);
			if(r.item.children!=null)g.fillPolygon(new int[]{tx,tx+9,tx+4},new int[]{ty-2,ty-2,ty+3},3);else g.fillPolygon(new int[]{tx+2,tx+2,tx+7},new int[]{ty-4,ty+5,ty},3);
		}
		if(c.type==T_CUSTOM){
			int[] rect={y,cl,y+r.height,right};int[] clip={Math.max(y,L.viewTop),Math.max(cl,L.viewLeft),Math.min(y+r.height,L.viewBottom),Math.min(right,L.viewRight)};
			if(minimal!=null){g.setColor(minimal);g.fillRect(cl,y,right-cl,r.height);}
			customs.add(new CustomCell(r.item.id,c.property,r.item.state(),rect,clip));return;
		}
		Cell cell=itemData(b,r.item.id,c.property,null);
		if(cell.status!=NO_ERR&&cell.mask==0)return;
		Color text=active&&b.activeItems?Color.BLACK:GREY_TEXT;int baseline=y+(r.height-fm.getHeight())/2+fm.getAscent();
		switch(c.type){
			case T_CHECK:{int bx=c.just==-2?cl+4:cl+(right-cl-12)/2,by=y+(r.height-12)/2;if(minimal!=null){g.setColor(minimal);g.fillRect(bx-2,by-2,16,16);}drawCheck(g,bx,by,cell.button,!active);return;}
			case T_PROGRESS:case T_RANK:case T_SLIDER:{
				int min=cell.has(Cell.MIN)?cell.min:0,max=cell.has(Cell.MAX)?cell.max:100;int bx=cl+4,bw=right-cl-8,bh=c.type==T_SLIDER?4:10,by=y+(r.height-bh)/2;if(bw<=2)return;
				int filled=max>min?(int)((long)(bw-2)*(Math.max(min,Math.min(max,cell.value))-min)/(max-min)):0;
				g.setColor(Color.WHITE);g.fillRect(bx,by,bw,bh);
				if(c.type!=T_SLIDER){g.setColor(c.type==T_RANK?SHADOW:BAR);g.fillRect(bx+1,by+1,filled,bh-2);}
				g.setColor(FRAME);g.drawRect(bx,by,bw-1,bh-1);
				if(c.type==T_SLIDER){g.setColor(HEADER_SORTED);g.fillRect(bx+filled-3,by-4,8,12);g.setColor(FRAME);g.drawRect(bx+filled-3,by-4,7,11);}
				return;}
			default:break;
		}
		String t=cellText(b,c,cell);int tl=cl+4;
		if(c.type==T_ICON||c.type==T_ICON_TEXT){if(minimal!=null&&c.type==T_ICON){g.setColor(minimal);g.fillRect(cl+1,y+(r.height-18)/2,18,18);}if(c.type==T_ICON)return;tl+=ICON_SPACE;} // IconRefs are not drawn here: their space is kept
		int room=right-tl-4-(c.type==T_MENU?12:0);
		t=fit(font,t,room,c.type==T_TEXT||c.type==T_ICON_TEXT?(c.flags>>>8)&3:2);int tw=Fonts68k.width(font,t); // the truncation flags are the text types' (a date's mean relative/date-only)
		int tx=c.just==1?tl+(room-tw)/2:c.just==-1?tl+room-tw:tl;
		if(minimal!=null&&!t.isEmpty()){g.setColor(minimal);g.fillRect(tx-2,y+1,tw+4,r.height-2);}
		g.setColor(cell.has(Cell.RGB)&&active?new Color(cell.rgb[0]>>>8,cell.rgb[1]>>>8,cell.rgb[2]>>>8):text);
		Fonts68k.drawString(g,font,t,tx,baseline);
		if(c.type==T_MENU){int ax=right-12,ay=y+r.height/2;g.setColor(text);g.fillPolygon(new int[]{ax-4,ax+4,ax},new int[]{ay-2,ay-2,ay+3},3);}
	}
	private static void drawCheck(Graphics2D g,int x,int y,int value,boolean disabled){
		g.setColor(Color.WHITE);g.fillRect(x,y,12,12);g.setColor(disabled?FRAME_OFF:FRAME);g.drawRect(x,y,11,11);
		g.setColor(disabled?GREY_TEXT:Color.BLACK);
		if(value==1){g.setStroke(new BasicStroke(2f));g.drawLine(x+2,y+6,x+5,y+9);g.drawLine(x+5,y+9,x+10,y+2);g.setStroke(new BasicStroke(1f));} // kThemeButtonOn
		else if(value==2)g.fillRect(x+3,y+5,6,2);                                                                                          // kThemeButtonMixed
	}
	/** The text a cell shows: its text, a date, a value, a menu item. */
	private String cellText(Browser b,Column c,Cell cell) throws M68kException {
		int type=c==null?T_TEXT:c.type;
		if(type==T_DATE)return cell.has(Cell.DATE)?formatDate(cell.date,c.flags):"";
		if(type==T_MENU&&cell.has(Cell.MENU)&&cell.menu!=0){int p=rt.allocate(256,2);try{rt.toolbox("GetMenuItemText",cell.menu,cell.value,p);return rt.toolbox.readPascal(p);}finally{memory.free(p);}}
		if(cell.has(Cell.TEXT))return cell.text;
		if(type==T_TEXT&&cell.has(Cell.VALUE))return Integer.toString(cell.value);
		return "";
	}
	/** A DateTime / LongDateTime (seconds since 1904, local) as the Finder's list view has it; relative, date-only, time-only, seconds flags. */
	private String formatDate(long seconds,int flags){
		java.time.LocalDateTime t=java.time.LocalDateTime.of(1904,1,1,0,0).plusSeconds(seconds);boolean jp=japanese();
		boolean secs=(flags&(1<<11))!=0; // kDataBrowserDateTimeSecondsToo
		String time=(jp?t.getHour():(t.getHour()+11)%12+1)+String.format(":%02d",t.getMinute())+(secs?String.format(":%02d",t.getSecond()):"")+(jp?"":t.getHour()<12?" AM":" PM");
		String date=jp?String.format("%d/%02d/%02d",t.getYear(),t.getMonthValue(),t.getDayOfMonth()):String.format("%d/%d/%02d",t.getMonthValue(),t.getDayOfMonth(),t.getYear()%100);
		if((flags&(1<<8))!=0){java.time.LocalDate today=java.time.LocalDate.now(); // kDataBrowserDateTimeRelative
			if(t.toLocalDate().equals(today))date=jp?"今日":"Today";else if(t.toLocalDate().equals(today.minusDays(1)))date=jp?"昨日":"Yesterday";}
		if((flags&(1<<9))!=0)return date;if((flags&(1<<10))!=0)return time;
		return date+" "+time;
	}
	/** Text shortened with an ellipsis to {@code width}: truncation 0 middle, 1 start, 2 end, 3 none. */
	private static String fit(Font font,String text,int width,int truncation){
		if(text==null)return "";if(truncation==3||Fonts68k.width(font,text)<=width)return text;
		String dots="…";if(width<=Fonts68k.width(font,dots))return "";
		if(truncation==1){int from=0;while(from<text.length()&&Fonts68k.width(font,dots+text.substring(from))>width)from++;return dots+text.substring(from);}
		if(truncation==0){int head=text.length()/2,tail=text.length()-head;
			while(head+tail>0&&Fonts68k.width(font,text.substring(0,head)+dots+text.substring(text.length()-tail))>width){if(head>=tail)head--;else tail--;}
			return text.substring(0,head)+dots+text.substring(text.length()-tail);}
		int n=text.length();while(n>0&&Fonts68k.width(font,text.substring(0,n)+dots)>width)n--;return text.substring(0,n)+dots;
	}
	private void paintColumnView(Graphics2D g,Browser b,Layout L,boolean active) throws M68kException {
		Font font=rowFont();FontMetrics fm=Fonts68k.metrics(font);Color hl=active?hilite():INACTIVE_SELECTION;
		int viewW=L.viewRight-L.viewLeft,viewH=L.viewBottom-L.viewTop;if(viewW<=0||viewH<=0)return;
		for(int i=0;i<b.path.size();i++){
			Item p=b.path.get(i);int x=L.viewLeft-b.scrollLeft+i*b.columnWidth;if(x>=L.viewRight)break;if(x+b.columnWidth<=L.viewLeft||p.children==null)continue;
			Item next=i+1<b.path.size()?b.path.get(i+1):null;
			for(int k=0;k<p.children.size();k++){
				Item it=p.children.get(k);int y=L.viewTop-b.scrollTop+k*b.rowHeight;if(y+b.rowHeight<=L.viewTop)continue;if(y>=L.viewBottom)break;
				g.setClip(null);g.clipRect(L.viewLeft,L.viewTop,viewW,viewH);g.clipRect(x,y,b.columnWidth-1,b.rowHeight);
				if(it.selected||it==next){g.setColor(it==next&&!it.selected?INACTIVE_SELECTION:hl);g.fillRect(x,y,b.columnWidth,b.rowHeight);}
				Cell cell=itemData(b,it.id,P_SELF,null);String t=cell.has(Cell.TEXT)?cell.text:"";
				int tl=x+4+(b.displayType==T_ICON_TEXT?ICON_SPACE:0);boolean container=isContainer(b,it);
				t=fit(font,t,x+b.columnWidth-tl-(container?14:4),0);
				g.setColor(active?Color.BLACK:GREY_TEXT);Fonts68k.drawString(g,font,t,tl,y+(b.rowHeight-fm.getHeight())/2+fm.getAscent());
				if(container){int ax=x+b.columnWidth-10,ay=y+b.rowHeight/2;g.fillPolygon(new int[]{ax,ax,ax+4},new int[]{ay-4,ay+4,ay},3);}
			}
			g.setClip(null);g.setColor(HEADER);g.drawLine(x+b.columnWidth-1,L.viewTop,x+b.columnWidth-1,L.viewBottom-1);
		}
	}
	/** Copies the browser's image into its window: straight into a direct surface, else through QuickDraw. */
	private void blit(int window,BufferedImage img,int top,int left,int bottom,int right) throws M68kException {
		Graphics2D wg=rt.toolbox.quickDraw.windowSurface(window,top,left,bottom,right);
		if(wg!=null){try{wg.drawImage(img,left,top,null);}finally{wg.dispose();}return;}
		inPort(window,()->rt.toolbox.quickDraw.drawImage(img,top,left,bottom,right));
	}
	private interface Body {void run() throws M68kException;}
	/** Runs {@code body} with the window's port current. */
	private void inPort(int window,Body body) throws M68kException {
		int slot=rt.allocate(4,4);
		try{rt.toolbox("GetPort",slot);int saved=memory.readLong(slot);if(saved!=window)rt.toolbox("SetPort",window);
			try{body.run();}finally{if(saved!=window&&saved!=0)rt.toolbox("SetPort",saved);}}
		finally{memory.free(slot);}
	}
	/** The program's DrawItem callback for each custom cell, in the window's port clipped to the visible part of the cell. */
	private void drawCustom(Browser b,int window,List<CustomCell> customs) throws M68kException {
		inPort(window,()->{
			int saved=rt.toolbox("NewRgn"),rect=rt.allocate(16,4);int textFont=memory.readWord(window+68),textFace=memory.readWord(window+70),textSize=memory.readWord(window+74);
			try{
				rt.toolbox("GetClip",saved);
				for(CustomCell cc:customs){
					if(cc.clip[2]<=cc.clip[0]||cc.clip[3]<=cc.clip[1])continue;
					writeRect(rect+8,cc.clip);rt.toolbox("ClipRect",rect+8);writeRect(rect,cc.rect);
					rt.toolbox("PenNormal");rt.toolbox("ForeColor",33);rt.toolbox("BackColor",30);rt.toolbox("TextFont",japanese()?Fonts68k.JAPANESE_SYSTEM_FONT:1);rt.toolbox("TextSize",japanese()?12:10);rt.toolbox("TextFace",0);
					invoke(b.custom[CC_DRAW],PI_DRAW,b.control,cc.item,cc.property,cc.state,rect,32,1);
				}
			}finally{rt.toolbox("SetClip",saved);rt.toolbox("DisposeRgn",saved);memory.free(rect);rt.toolbox("TextFont",(short)textFont);rt.toolbox("TextFace",textFace&0xff);rt.toolbox("TextSize",(short)textSize);}
		});
	}
	private void writeRect(int p,int[] r) throws M68kCpuException {memory.writeWord(p,r[0]);memory.writeWord(p+2,r[1]);memory.writeWord(p+4,r[2]);memory.writeWord(p+6,r[3]);}

	// ------------------------------------------------------------------ mouse

	private boolean mouseDown(){return rt.toolbox.host().isMouseDown();}
	/** A click in a browser (HandleControlClick / TrackControl): scroll bars, header buttons, disclosure triangles, check boxes, selection. */
	private int click(Browser b,int h,int v,int modifiers) throws M68kException {
		int window=rt.toolbox.controls.ownerWindow(b.control);final int[] result={PART};
		inPort(window,()->result[0]=clickInPort(b,h,v,modifiers));
		return result[0];
	}
	private int clickInPort(Browser b,int h,int v,int modifiers) throws M68kException {
		Layout L=layout(b);
		if(L.vbar!=null&&inside(L.vbar,h,v)){trackScroll(b,true,h,v);return PART;}
		if(L.hbar!=null&&inside(L.hbar,h,v)){trackScroll(b,false,h,v);return PART;}
		if(b.style==COLUMN_VIEW)return clickColumnView(b,L,h,v,modifiers);
		if(b.headerHeight>0&&v>L.top&&v<L.viewTop&&h<L.viewRight){clickHeader(b,L,h);return PART;}
		if(v<L.viewTop||v>=L.viewBottom||h<L.viewLeft||h>=L.viewRight)return PART;
		Row r=rowAt(b,L,v);Column c=columnAt(b,L,h);
		if(r==null){ // empty space: the selection goes, unless it may never be empty
			if((b.selectionFlags&NEVER_EMPTY)==0&&(modifiers&(CMD|SHIFT))==0)commitSelection(b,new LinkedHashSet<Item>());
			waitForRelease();return PART;}
		Item it=r.item;int y=L.viewTop-b.scrollTop+r.top;
		if(c!=null){
			int x=columnX(b,L,c.property),cl=contentLeft(b,c,r,x);
			if(b.disclosure!=0&&c.property==b.disclosure&&b.expandable&&h>=cl-INDENT&&h<cl&&isContainer(b,it)){ // the disclosure triangle
				waitForRelease();
				if(it.children!=null){if(flag(b,it,P_CLOSABLE,true))closeContainer(b,it);}else if(flag(b,it,P_OPENABLE,true))openContainer(b,it);
				redraw(b);return PART;}
			if(c.type==T_CHECK&&(c.flags&MUTABLE)!=0){ // an editable check box: the program gets the new value through a set request
				int bx=c.just==-2?cl+4:cl+(x+c.width-cl-12)/2,by=y+(r.height-12)/2;
				if(h>=bx-2&&h<bx+14&&v>=by-2&&v<by+14){waitForRelease();Cell now=itemData(b,it.id,c.property,null);Cell set=new Cell();set.button=now.button==1?0:1;set.mask=Cell.BUTTON;itemData(b,it.id,c.property,set);redraw(b);return PART;}
			}
			if(c.type==T_CUSTOM&&b.custom[CC_TRACK]!=0){ // the program's own hit testing and tracking of a custom cell
				int rect=rt.allocate(16,4);
				try{writeRect(rect,new int[]{y,cl,y+r.height,x+c.width});writeRect(rect+8,new int[]{v,h,v+1,h+1});
					boolean hit=b.custom[CC_HIT]==0||invoke(b.custom[CC_HIT],PI_HIT,b.control,it.id,c.property,rect,rect+8)!=0;
					if(hit){int t=invoke(b.custom[CC_TRACK],PI_TRACK,b.control,it.id,c.property,rect,(v<<16)|(h&0xffff),modifiers);if(t!=1){redraw(b);return PART;}}}
				finally{memory.free(rect);}
			}
		}
		if((modifiers&CONTROL)!=0&&b.callbacks[CB_GET_MENU]!=0){if(!it.selected)select(b,List.of(it),1);contextualMenu(b,h,v);return PART;}
		clickItem(b,it,modifiers);
		int now=ticks(),doubleTime=memory.readLong(0x2f0);if(doubleTime<=0||doubleTime>600)doubleTime=30; // DoubleTime
		boolean doubleClick=b.lastClickItem==it.id&&now-b.lastClickTicks<=doubleTime&&(modifiers&(CMD|SHIFT))==0;
		b.lastClickItem=doubleClick?0:it.id;b.lastClickTicks=now;
		dragSelect(b,it);
		if(doubleClick&&it.selected)notify(b,it.id,DOUBLE_CLICKED);
		return PART;
	}
	private static boolean inside(int[] r,int h,int v){return v>=r[0]&&v<r[2]&&h>=r[1]&&h<r[3];}
	/** Selection by a click: plain = only this item, Shift = extend from the anchor, Command = toggle - within the selection flags. */
	private void clickItem(Browser b,Item it,int modifiers) throws M68kException {
		int f=b.selectionFlags;boolean single=(f&ONLY_ONE)!=0||(f&RESET)!=0;
		boolean shift=!single&&(modifiers&SHIFT)!=0,cmd=!single&&((modifiers&CMD)!=0||((f&ALWAYS_EXTEND)!=0&&!shift));
		if(cmd&&(f&NO_DISJOINT)!=0){cmd=false;shift=true;}
		if(!flag(b,it,P_SELECTABLE,true)){if(!shift&&!cmd&&(f&NEVER_EMPTY)==0)commitSelection(b,new LinkedHashSet<Item>());return;}
		Set<Item> now=new LinkedHashSet<Item>(selection(b));
		if(cmd){if(it.selected){if((f&NEVER_EMPTY)!=0&&now.size()==1)return;now.remove(it);}else now.add(it);b.anchor=it;}
		else if(shift&&b.anchor!=null&&rowIndex(b,b.anchor)>=0){int a=rowIndex(b,b.anchor),z=rowIndex(b,it);List<Row> rows=rows(b);for(int i=Math.min(a,z);i<=Math.max(a,z);i++)now.add(rows.get(i).item);}
		else{if(!it.selected||now.size()>1){now.clear();now.add(it);}b.anchor=it;}
		commitSelection(b,now);
	}
	/** While the button stays down the selection follows the mouse (a range from the anchor when several items may be selected). */
	private void dragSelect(Browser b,Item start) throws M68kException {
		Item last=start;Set<Item> base=new LinkedHashSet<Item>(selection(b));boolean single=(b.selectionFlags&ONLY_ONE)!=0;
		while(mouseDown()){
			int[] m=rt.toolbox.events.localMouse();Layout L=layout(b);Row r=rowAt(b,L,Math.max(L.viewTop,Math.min(L.viewBottom-1,m[1])));
			if(r!=null&&r.item!=last&&start.selected){last=r.item;
				Set<Item> now=new LinkedHashSet<Item>(single?new ArrayList<Item>():base);
				if(single)now.add(last);else{int a=rowIndex(b,start),z=rowIndex(b,last);List<Row> rows=rows(b);for(int i=Math.min(a,z);i<=Math.max(a,z);i++)now.add(rows.get(i).item);}
				commitSelection(b,now);}
			if(m[1]<L.viewTop&&b.scrollTop>0){b.scrollTop-=b.rowHeight;redraw(b);}else if(m[1]>=L.viewBottom&&b.scrollTop<L.maxTop()){b.scrollTop+=b.rowHeight;redraw(b);}
			rt.toolbox.events.idle(1);
		}
	}
	private void waitForRelease() throws M68kException {while(mouseDown())rt.toolbox.events.idle(1);}
	/** A header button: its right edge resizes the column, elsewhere it sorts by the column (again: the other order). */
	private void clickHeader(Browser b,Layout L,int h) throws M68kException {
		int x=L.viewLeft-b.scrollLeft;
		for(Column c:b.columns){
			if(Math.abs(h-(x+c.width))<=2&&b.variableWidth){ // resize
				while(mouseDown()){int[] m=rt.toolbox.events.localMouse();int w=Math.max(c.min,Math.min(c.max>0?c.max:0xffff,m[0]-x));if(w!=c.width){c.width=w;redraw(b);}rt.toolbox.events.idle(1);}
				return;}
			if(h>=x&&h<x+c.width){
				waitForRelease();
				if((c.flags&SORTABLE_COLUMN)==0)return;
				if(b.sortProperty==c.property)b.sortOrder=b.sortOrder==2?1:2;else{b.sortProperty=c.property;b.sortOrder=c.initialOrder==2?2:1;}
				sortContainer(b,b.root,true);redraw(b);return;}
			x+=c.width;
		}
		waitForRelease();
	}
	/** Scroll bar tracking: arrows and page areas repeat while pressed, the thumb follows the mouse. */
	private void trackScroll(Browser b,boolean vertical,int h,int v) throws M68kException {
		Layout L=layout(b);int[] bar=vertical?L.vbar:L.hbar;int max=vertical?L.maxTop():L.maxLeft();if(max<=0){waitForRelease();return;}
		int length=vertical?bar[2]-bar[0]:bar[3]-bar[1],arrow=Math.min(16,length/2),pos=vertical?v-bar[0]:h-bar[1];
		int value=vertical?b.scrollTop:b.scrollLeft;int[] thumb=ControlManager68k.thumbRange(length,arrow,0,max,value);
		int page=Math.max(1,(vertical?L.viewBottom-L.viewTop:L.viewRight-L.viewLeft)-b.rowHeight),step=vertical?b.rowHeight:16;
		if(pos>=thumb[0]&&pos<thumb[1]&&pos>=arrow&&pos<length-arrow){ // the thumb
			int travel=Math.max(1,length-2*arrow-(thumb[1]-thumb[0])),start=vertical?v:h;
			while(mouseDown()){int[] m=rt.toolbox.events.localMouse();int nv=Math.max(0,Math.min(max,value+(int)Math.round(((vertical?m[1]:m[0])-start)*(double)max/travel)));
				if(nv!=(vertical?b.scrollTop:b.scrollLeft)){if(vertical)b.scrollTop=nv;else b.scrollLeft=nv;redraw(b);}rt.toolbox.events.idle(1);}
			return;}
		int delta=pos<arrow?-step:pos>=length-arrow?step:pos<thumb[0]?-page:page;
		do{
			if(vertical)b.scrollTop=Math.max(0,Math.min(max,b.scrollTop+delta));else b.scrollLeft=Math.max(0,Math.min(max,b.scrollLeft+delta));
			redraw(b);long until=System.nanoTime()+60_000_000L;
			while(mouseDown()&&System.nanoTime()<until)rt.toolbox.events.idle(1);
		}while(mouseDown());
	}
	/** Control-click: the program's contextual menu, popped up at the mouse, and its choice handed back. */
	private void contextualMenu(Browser b,int h,int v) throws M68kException {
		int block=rt.allocate(24,4);
		try{
			invoke(b.callbacks[CB_GET_MENU],PI_GET_MENU,b.control,block,block+4,block+8,block+12);
			int menu=memory.readLong(block);if(menu==0)return;
			int[] g=rt.toolbox.windows.localToGlobal(h,v);
			rt.toolbox("InsertMenu",menu,-1);int choice=rt.toolbox("PopUpMenuSelect",menu,g[1],g[0],0);rt.toolbox("DeleteMenu",(short)memory.readWord(memory.readLong(menu)));
			if(b.callbacks[CB_SELECT_MENU]!=0)invoke(b.callbacks[CB_SELECT_MENU],PI_SELECT_MENU,b.control,menu,(choice&0xffff)!=0?1:0,choice>>>16,choice&0xffff); // kCMMenuItemSelected / kCMNothingSelected
		}finally{memory.free(block);}
	}
	private int clickColumnView(Browser b,Layout L,int h,int v,int modifiers) throws M68kException {
		waitForRelease();
		if(v<L.viewTop||v>=L.viewBottom||h<L.viewLeft||h>=L.viewRight)return PART;
		int col=(h-L.viewLeft+b.scrollLeft)/b.columnWidth,row=(v-L.viewTop+b.scrollTop)/b.rowHeight;
		if(col>=b.path.size())return PART;Item p=b.path.get(col);
		if(p.children==null||row>=p.children.size()){truncatePath(b,col+1);commitSelection(b,new LinkedHashSet<Item>());redraw(b);return PART;}
		Item it=p.children.get(row);
		int now=ticks();boolean doubleClick=b.lastClickItem==it.id&&now-b.lastClickTicks<=30;b.lastClickItem=doubleClick?0:it.id;b.lastClickTicks=now;
		columnSelect(b,col,it);
		if(doubleClick)notify(b,it.id,DOUBLE_CLICKED);
		return PART;
	}
	/** Column view: selects an item of column {@code col}; a container opens as the next column. */
	private void columnSelect(Browser b,int col,Item it) throws M68kException {
		truncatePath(b,col+1);
		Set<Item> one=new LinkedHashSet<Item>();one.add(it);commitSelection(b,one);b.anchor=it;
		if(isContainer(b,it)&&flag(b,it,P_OPENABLE,true)){openContainer(b,it);b.path.add(it);}
		Layout L=layout(b);int right=b.path.size()*b.columnWidth,width=L.viewRight-L.viewLeft;if(right-b.scrollLeft>width)b.scrollLeft=right-width;
		redraw(b);
	}
	private void truncatePath(Browser b,int size) throws M68kException {
		while(b.path.size()>Math.max(1,size)){Item last=b.path.remove(b.path.size()-1);closeContainer(b,last);}
	}

	// ------------------------------------------------------------------ keys

	/** HandleControlKey: arrows move the selection (Shift extends), Home/End/Page scroll, Left/Right close/open containers, letters type-select. */
	private int key(Browser b,int ch,int modifiers) throws M68kException {
		if(b.style==COLUMN_VIEW)return columnKey(b,ch);
		List<Row> rows=rows(b);Layout L=layout(b);int page=Math.max(b.rowHeight,L.viewBottom-L.viewTop-b.rowHeight);
		switch(ch){
			case 0x1e:case 0x1f:moveSelection(b,ch==0x1f?1:-1,(modifiers&SHIFT)!=0);return PART;
			case 0x01:b.scrollTop=0;redraw(b);return PART;
			case 0x04:b.scrollTop=L.maxTop();redraw(b);return PART;
			case 0x0b:b.scrollTop=Math.max(0,b.scrollTop-page);redraw(b);return PART;
			case 0x0c:b.scrollTop=Math.min(L.maxTop(),b.scrollTop+page);redraw(b);return PART;
			case 0x1c:case 0x1d:{
				if(b.disclosure==0)return 0;
				for(Item it:selection(b)){if(!isContainer(b,it))continue;if(ch==0x1d&&it.children==null&&flag(b,it,P_OPENABLE,true))openContainer(b,it);else if(ch==0x1c&&it.children!=null&&flag(b,it,P_CLOSABLE,true))closeContainer(b,it);}
				redraw(b);return PART;}
			default:
				if(ch<0x20||ch==0x7f||(modifiers&CMD)!=0||rows.isEmpty())return 0;
				return typeSelect(b,ch);
		}
	}
	private void moveSelection(Browser b,int direction,boolean extend) throws M68kException {
		List<Row> rows=rows(b);if(rows.isEmpty())return;
		List<Item> sel=selection(b);int at=b.anchor!=null&&b.anchor.selected?rowIndex(b,b.anchor):sel.isEmpty()?-1:rowIndex(b,direction>0?sel.get(sel.size()-1):sel.get(0));
		int next=at<0?(direction>0?0:rows.size()-1):Math.max(0,Math.min(rows.size()-1,at+direction));
		Item it=rows.get(next).item;
		if(extend&&(b.selectionFlags&ONLY_ONE)==0){Set<Item> now=new LinkedHashSet<Item>(sel);now.add(it);commitSelection(b,now);}
		else{Set<Item> one=new LinkedHashSet<Item>();one.add(it);commitSelection(b,one);}
		b.anchor=it;reveal(b,it,0,false);redraw(b);
	}
	/** Type-select: the first row whose text (sort column, else the first text column) starts with what was typed in the last second. */
	private int typeSelect(Browser b,int ch) throws M68kException {
		int now=ticks();if(now-b.typedTicks>60)b.typed="";b.typedTicks=now;
		b.typed+=rt.toolbox.decode(new byte[]{(byte)ch}).toLowerCase();
		Column c=column(b,b.sortProperty);if(c==null)for(Column k:b.columns)if(k.type==T_TEXT||k.type==T_ICON_TEXT){c=k;break;}
		if(c==null)return 0;
		for(Row r:rows(b)){String t=cellText(b,c,itemData(b,r.item.id,c.property,null)).toLowerCase();
			if(t.startsWith(b.typed)){Set<Item> one=new LinkedHashSet<Item>();one.add(r.item);commitSelection(b,one);b.anchor=r.item;reveal(b,r.item,0,false);redraw(b);return PART;}}
		return PART;
	}
	private int columnKey(Browser b,int ch) throws M68kException {
		int col=-1;
		for(int i=0;i<b.path.size();i++){Item p=b.path.get(i);if(p.children!=null&&b.anchor!=null&&p.children.contains(b.anchor))col=i;}
		switch(ch){
			case 0x1e:case 0x1f:{if(col<0){Item p=b.path.get(0);if(p.children!=null&&!p.children.isEmpty())columnSelect(b,0,p.children.get(0));return PART;}
				List<Item> list=b.path.get(col).children;int at=list.indexOf(b.anchor)+(ch==0x1f?1:-1);if(at>=0&&at<list.size())columnSelect(b,col,list.get(at));return PART;}
			case 0x1c:if(col>0){Item parent=b.path.get(col);columnSelect(b,col-1,parent);}return PART;
			case 0x1d:if(col>=0&&col+1<b.path.size()){Item p=b.path.get(col+1);if(p.children!=null&&!p.children.isEmpty())columnSelect(b,col+1,p.children.get(0));}return PART;
			default:return 0;
		}
	}

	// ------------------------------------------------------------------ state

	/** DataBrowser user state as CFData: 'hzDB', sort property/order, then (property, width) per column in order. */
	private int userState(Browser b) throws M68kCpuException {
		java.nio.ByteBuffer buf=java.nio.ByteBuffer.allocate(16+8*b.columns.size());buf.putInt(0x687a4442).putInt(b.sortProperty).putInt(b.sortOrder).putInt(b.columns.size());
		for(Column c:b.columns)buf.putInt(c.property).putInt(c.width);
		return rt.libraries.coreFoundation.newData(buf.array(),false);
	}
	private int setUserState(Browser b,int data) throws M68kException {
		PpcCoreFoundation.CFData d=rt.libraries.coreFoundation.object(data,PpcCoreFoundation.CFData.class);if(d==null)return PARAM_ERR;
		java.nio.ByteBuffer buf=java.nio.ByteBuffer.wrap(d.bytes(rt.libraries.coreFoundation));
		if(buf.remaining()<16||buf.getInt()!=0x687a4442)return PARAM_ERR;
		int sp=buf.getInt(),so=buf.getInt(),n=buf.getInt();List<Column> order=new ArrayList<Column>();
		for(int i=0;i<n&&buf.remaining()>=8;i++){Column c=column(b,buf.getInt());int w=buf.getInt();if(c!=null){c.width=w;order.add(c);}}
		for(Column c:b.columns)if(!order.contains(c))order.add(c);b.columns.clear();b.columns.addAll(order);
		if(column(b,sp)!=null){b.sortProperty=sp;b.sortOrder=so==2?2:1;sortContainer(b,b.root,true);}
		redraw(b);return NO_ERR;
	}
	private int setTarget(Browser b,int target) throws M68kException {
		if(target==b.root.id&&b.root.children!=null&&!b.root.children.isEmpty())return NO_ERR;
		remove(b,new ArrayList<Item>(b.root.children));
		b.items.remove(target);b.root=newRoot(target);b.path.clear();b.path.add(b.root);b.scrollTop=b.scrollLeft=0;b.rows=null;
		notify(b,target,TARGET_CHANGED);
		if(target!=0){notify(b,target,OPENED);if(b.sortProperty!=0)sortContainer(b,b.root,false);} // the program fills the new target
		redraw(b);return NO_ERR;
	}
	private void copySelection(Browser b) throws M68kException {
		StringBuilder sb=new StringBuilder();
		for(Item it:selection(b)){
			if(sb.length()>0)sb.append('\r');
			if(b.style==COLUMN_VIEW){Cell c=itemData(b,it.id,P_SELF,null);sb.append(c.has(Cell.TEXT)?c.text:"");continue;}
			boolean first=true;for(Column c:b.columns){if(c.type==T_CUSTOM||c.type==T_ICON||c.type==T_CHECK)continue;if(!first)sb.append('\t');first=false;sb.append(cellText(b,c,itemData(b,it.id,c.property,null)));}
		}
		byte[] bytes=rt.libraries.textServices.systemBytes(sb.toString());int p=rt.allocate(bytes.length+1,2);
		try{memory.load(p,bytes);rt.toolbox("ZeroScrap");rt.toolbox("PutScrap",bytes.length,0x54455854,p);}finally{memory.free(p);}
	}
	private void autoSize(Browser b) throws M68kException {
		if(b.columns.isEmpty())return;Layout L=layout(b);int room=L.viewRight-L.viewLeft,total=0;for(Column c:b.columns)total+=c.width;
		for(int pass=0;pass<4&&total!=room;pass++){ // spread the difference over the columns that can still give or take
			int movable=0;for(Column c:b.columns)if(total<room?c.width<(c.max>0?c.max:0xffff):c.width>c.min)movable++;
			if(movable==0)break;int share=(room-total)/movable;if(share==0)share=room>total?1:-1;
			for(Column c:b.columns){if(total==room)break;int w=Math.max(c.min,Math.min(c.max>0?c.max:0xffff,c.width+share));total+=w-c.width;c.width=w;}
		}
		redraw(b);
	}

	// ------------------------------------------------------------------ the CarbonLib functions

	private interface BrowserCall {int run(Browser b) throws M68kException;}
	/** A function taking the browser first: paramErr for anything that is not one. */
	private void fn(PpcLibraries l,String name,BrowserCall body){l.register(null,name,(r,c)->{Browser b=browsers.get(arg(0));ret(b==null?PARAM_ERR:body.run(b));});}
	/** A function returning a Boolean: FALSE for anything that is not a browser. */
	private void bool(PpcLibraries l,String name,BrowserCall body){l.register(null,name,(r,c)->{Browser b=browsers.get(arg(0));ret(b==null?0:body.run(b));});}
	private interface CellCall {void run(Cell cell,int arg) throws M68kException;}
	/** Set/GetDataBrowserItemData...: on the cell of the item-data call in progress. */
	private void data(PpcLibraries l,String name,CellCall body){l.register(null,name,(r,c)->{Cell cell=cells.get(arg(0));if(cell==null){ret(PARAM_ERR);return;}body.run(cell,arg(1));ret(NO_ERR);});}
	private void put16(int p,int v) throws M68kCpuException {if(p!=0)memory.writeWord(p,v);}
	/** A DataBrowserListViewHeaderDesc (mac68k alignment: version, min, max, titleOffset, titleString, initialOrder, ControlFontStyleRec at 16, ControlButtonContentInfo at 40). */
	private void readHeader(Column c,int hd) throws M68kCpuException {
		c.min=memory.readWord(hd+4);c.max=memory.readWord(hd+6);c.titleOffset=(short)memory.readWord(hd+8);
		String t=rt.libraries.textServices.cfText(memory.readLong(hd+10));c.title=t==null?"":t;c.initialOrder=memory.readWord(hd+14);
		c.fontFlags=memory.readWord(hd+16);c.font=(short)memory.readWord(hd+18);c.size=(short)memory.readWord(hd+20);c.face=memory.readWord(hd+22);c.mode=memory.readWord(hd+24);c.just=(short)memory.readWord(hd+26);
		c.colors=memory.readBytes(hd+28,12);c.contentType=(short)memory.readWord(hd+40);c.content=memory.readLong(hd+42);
		if(c.max==0)c.max=0xffff;
	}
	private void put32(int p,int v) throws M68kCpuException {if(p!=0)memory.writeLong(p,v);}

	void define(PpcLibraries l){
		// ---- UPPs (the 3.3 headers have none of these types)
		for(Object[] u:UPPS){
			String n=(String)u[0];final int pi=(Integer)u[1];int count=0;for(int s=6;s<32&&((pi>>>s)&3)!=0;s+=2)count++;final int nargs=count;
			l.register(null,"NewDataBrowser"+n+"UPP",(r,c)->ret(arg(0)==0?0:rt.newRoutineDescriptor(arg(0),pi,PpcRuntime.ISA_PPC)));
			l.register(null,"DisposeDataBrowser"+n+"UPP",(r,c)->{int rd=arg(0);if(rd!=0&&rt.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);});
			l.register(null,"InvokeDataBrowser"+n+"UPP",(r,c)->{int[] a=new int[nargs];for(int i=0;i<nargs;i++){int s=(pi>>>(6+2*i))&3;a[i]=s==1?arg(i)&0xff:s==2?(short)arg(i):arg(i);}ret(invoke(arg(nargs),pi,a));});
		}
		// ---- creation, callbacks
		l.register(null,"CreateDataBrowserControl",(r,c)->{ // (window, bounds, style, *outControl)
			int window=arg(0),bounds=arg(1),style=arg(2),out=arg(3);
			if(window==0||bounds==0||(style!=LIST_VIEW&&style!=COLUMN_VIEW)){if(out!=0)memory.writeLong(out,0);ret(PARAM_ERR);return;}
			Browser b=new Browser(style);
			int h=rt.toolbox.controls.create(window,(short)memory.readWord(bounds),(short)memory.readWord(bounds+2),(short)memory.readWord(bounds+4),(short)memory.readWord(bounds+6),new byte[1],true,0,0,0,PROC_ID,0,b);
			b.control=h;browsers.put(h,b);if(out!=0)memory.writeLong(out,h);ret(h==0?-108:NO_ERR);});
		fn(l,"GetDataBrowserViewStyle",b->{put32(arg(1),b.style);return NO_ERR;});
		fn(l,"SetDataBrowserViewStyle",b->{int s=arg(1);if(s!=LIST_VIEW&&s!=COLUMN_VIEW)return PARAM_ERR;if(s!=b.style){b.style=s;b.scrollLeft=b.scrollTop=0;b.rows=null;redraw(b);}return NO_ERR;});
		l.register(null,"InitDataBrowserCallbacks",(r,c)->{int p=arg(0);if(p==0||memory.readLong(p)!=0){ret(PARAM_ERR);return;}memory.fill(p+4,40,0);ret(NO_ERR);}); // version kDataBrowserLatestCallbacks, the UPPs NIL (the defaults)
		l.register(null,"InitDataBrowserCustomCallbacks",(r,c)->{int p=arg(0);if(p==0||memory.readLong(p)!=0){ret(PARAM_ERR);return;}memory.fill(p+4,28,0);ret(NO_ERR);});
		fn(l,"SetDataBrowserCallbacks",b->{int p=arg(1);if(p==0||memory.readLong(p)!=0)return PARAM_ERR;for(int i=0;i<10;i++)b.callbacks[i]=memory.readLong(p+4+4*i);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserCallbacks",b->{int p=arg(1);if(p==0)return PARAM_ERR;memory.writeLong(p,0);for(int i=0;i<10;i++)memory.writeLong(p+4+4*i,b.callbacks[i]);return NO_ERR;});
		fn(l,"SetDataBrowserCustomCallbacks",b->{int p=arg(1);if(p==0||memory.readLong(p)!=0)return PARAM_ERR;for(int i=0;i<7;i++)b.custom[i]=memory.readLong(p+4+4*i);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserCustomCallbacks",b->{int p=arg(1);if(p==0)return PARAM_ERR;memory.writeLong(p,0);for(int i=0;i<7;i++)memory.writeLong(p+4+4*i,b.custom[i]);return NO_ERR;});
		// ---- items
		fn(l,"AddDataBrowserItems",b->addItems(b,arg(1),arg(2),arg(3),arg(4)));                  // (browser, container, numItems, items, preSortProperty)
		fn(l,"RemoveDataBrowserItems",b->removeItems(b,arg(1),arg(2),arg(3)));
		fn(l,"UpdateDataBrowserItems",b->{ // (browser, container, numItems, items, preSortProperty, propertyID): the data changed - drawn again (pulled anew), re-sorted
			Item parent=container(b,arg(1));if(parent==null)return ITEM_NOT_FOUND;int property=arg(5);
			List<Item> list=arg(3)==0?(parent.children==null?new ArrayList<Item>():new ArrayList<Item>(parent.children)):itemList(b,arg(2),arg(3));
			for(Item it:list)it.container=null;
			if(b.sortProperty!=0&&(property==0||property==b.sortProperty)){Set<Item> parents=new LinkedHashSet<Item>();for(Item it:list)if(it.parent!=null)parents.add(it.parent);for(Item p:parents)sortContainer(b,p,false);}
			redraw(b);return NO_ERR;});
		fn(l,"OpenDataBrowserContainer",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;
			if(b.style==COLUMN_VIEW){int col=-1;for(int i=0;i<b.path.size();i++){Item p=b.path.get(i);if(p.children!=null&&p.children.contains(it))col=i;}if(col<0)return ITEM_NOT_FOUND;truncatePath(b,col+1);openContainer(b,it);b.path.add(it);}
			else openContainer(b,it);
			redraw(b);return NO_ERR;});
		fn(l,"CloseDataBrowserContainer",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;int at=b.path.indexOf(it);if(at>0)truncatePath(b,at);else closeContainer(b,it);redraw(b);return NO_ERR;});
		fn(l,"SortDataBrowserContainer",b->{Item it=container(b,arg(1));if(it==null)return ITEM_NOT_FOUND;sortContainer(b,it,(arg(2)&0xff)!=0);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserItems",b->{ // (browser, container, recurse, state, Handle items)
			Item parent=container(b,arg(1));if(parent==null)return ITEM_NOT_FOUND;int h=arg(4);if(h==0)return PARAM_ERR;
			List<Item> out=new ArrayList<Item>();collect(parent,(arg(2)&0xff)!=0,arg(3),out);
			rt.toolbox("SetHandleSize",h,out.size()*4);int p=memory.readLong(h);if(p==0&&!out.isEmpty())return -108;
			for(int i=0;i<out.size();i++)memory.writeLong(p+4*i,out.get(i).id);return NO_ERR;});
		fn(l,"GetDataBrowserItemCount",b->{Item parent=container(b,arg(1));if(parent==null)return ITEM_NOT_FOUND;List<Item> out=new ArrayList<Item>();collect(parent,(arg(2)&0xff)!=0,arg(3),out);put32(arg(4),out.size());return NO_ERR;});
		fn(l,"ForEachDataBrowserItem",b->{ // (browser, container, recurse, state, DataBrowserItemUPP, clientData)
			Item parent=container(b,arg(1));if(parent==null)return ITEM_NOT_FOUND;int upp=arg(4),data=arg(5);if(upp==0)return PARAM_ERR;
			List<Item> out=new ArrayList<Item>();collect(parent,(arg(2)&0xff)!=0,arg(3),out);
			for(Item it:out)if(b.items.get(it.id)==it)invoke(upp,PI_ITEM,it.id,it.state(),data);
			return NO_ERR;});
		bool(l,"IsDataBrowserItemSelected",b->{Item it=b.items.get(arg(1));return it!=null&&it.selected?1:0;});
		fn(l,"GetDataBrowserItemState",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;put32(arg(2),it.state());return NO_ERR;});
		fn(l,"RevealDataBrowserItem",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;int property=arg(2),options=arg(3)&0xff;
			if(b.style==COLUMN_VIEW&&(options&2)==0){for(int i=0;i<b.path.size();i++){Item p=b.path.get(i);if(p.children!=null&&p.children.contains(it)){columnSelect(b,i,it);break;}}}
			reveal(b,it,column(b,property)!=null?property:0,(options&1)!=0);redraw(b);return NO_ERR;});
		fn(l,"SetDataBrowserSelectedItems",b->{select(b,itemList(b,arg(1),arg(2)),arg(3));return NO_ERR;}); // (browser, numItems, items, operation)
		fn(l,"GetDataBrowserSelectionAnchor",b->{List<Item> sel=selection(b);put32(arg(1),sel.isEmpty()?0:sel.get(0).id);put32(arg(2),sel.isEmpty()?0:sel.get(sel.size()-1).id);return NO_ERR;});
		fn(l,"MoveDataBrowserSelectionAnchor",b->{int dir=arg(1);boolean extend=(arg(2)&0xff)!=0;
			if(b.style==COLUMN_VIEW)columnKey(b,dir==0?0x1e:dir==1?0x1f:dir==2?0x1c:0x1d);else if(dir<=1)moveSelection(b,dir==1?1:-1,extend);return NO_ERR;});
		bool(l,"EnableDataBrowserEditCommand",b->{int cmd=arg(1);
			if(cmd==0x73616c6c)return (b.selectionFlags&ONLY_ONE)==0&&!b.items.isEmpty()?1:0; // kHICommandSelectAll
			if(cmd==0x636f7079)return selection(b).isEmpty()?0:1;                             // kHICommandCopy
			return 0;});
		fn(l,"ExecuteDataBrowserEditCommand",b->{int cmd=arg(1);
			if(cmd==0x73616c6c){if((b.selectionFlags&ONLY_ONE)!=0)return PARAM_ERR;List<Item> all=new ArrayList<Item>();if(b.style==COLUMN_VIEW){Item p=b.path.get(b.path.size()-1);if(p.children!=null)all.addAll(p.children);}else for(Row r:rows(b))all.add(r.item);select(b,all,1);return NO_ERR;}
			if(cmd==0x636f7079){copySelection(b);return NO_ERR;}
			return PARAM_ERR;});
		// ---- attributes
		fn(l,"SetDataBrowserUserState",b->setUserState(b,arg(1)));
		fn(l,"GetDataBrowserUserState",b->{put32(arg(1),userState(b));return NO_ERR;});
		fn(l,"SetDataBrowserActiveItems",b->{b.activeItems=(arg(1)&0xff)!=0;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserActiveItems",b->{if(arg(1)!=0)memory.writeByte(arg(1),b.activeItems?1:0);return NO_ERR;});
		fn(l,"SetDataBrowserScrollBarInset",b->{int p=arg(1);if(p!=0)for(int i=0;i<4;i++)b.scrollInset[i]=(short)memory.readWord(p+2*i);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserScrollBarInset",b->{int p=arg(1);if(p!=0)for(int i=0;i<4;i++)memory.writeWord(p+2*i,b.scrollInset[i]);return NO_ERR;});
		fn(l,"SetDataBrowserTarget",b->setTarget(b,arg(1)));
		fn(l,"GetDataBrowserTarget",b->{put32(arg(1),b.root.id);return NO_ERR;});
		fn(l,"SetDataBrowserSortOrder",b->{int o=arg(1)&0xffff;if(o!=1&&o!=2)return PARAM_ERR;if(o!=b.sortOrder){b.sortOrder=o;sortContainer(b,b.root,true);redraw(b);}return NO_ERR;});
		fn(l,"GetDataBrowserSortOrder",b->{put16(arg(1),b.sortOrder);return NO_ERR;});
		fn(l,"SetDataBrowserScrollPosition",b->{b.scrollTop=Math.max(0,arg(1));b.scrollLeft=Math.max(0,arg(2));layout(b);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserScrollPosition",b->{layout(b);put32(arg(1),b.scrollTop);put32(arg(2),b.scrollLeft);return NO_ERR;});
		fn(l,"SetDataBrowserHasScrollBars",b->{b.hasH=(arg(1)&0xff)!=0;b.hasV=(arg(2)&0xff)!=0;rt.toolbox.controls.draw(b.control);return NO_ERR;});
		fn(l,"GetDataBrowserHasScrollBars",b->{if(arg(1)!=0)memory.writeByte(arg(1),b.hasH?1:0);if(arg(2)!=0)memory.writeByte(arg(2),b.hasV?1:0);return NO_ERR;});
		fn(l,"SetDataBrowserSortProperty",b->{int p=arg(1);if(p!=b.sortProperty){b.sortProperty=p;sortContainer(b,b.root,true);redraw(b);}return NO_ERR;});
		fn(l,"GetDataBrowserSortProperty",b->{put32(arg(1),b.sortProperty);return NO_ERR;});
		fn(l,"SetDataBrowserSelectionFlags",b->{b.selectionFlags=arg(1);return NO_ERR;});
		fn(l,"GetDataBrowserSelectionFlags",b->{put32(arg(1),b.selectionFlags);return NO_ERR;});
		fn(l,"SetDataBrowserPropertyFlags",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;c.flags=arg(2);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserPropertyFlags",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;put32(arg(2),c.flags);return NO_ERR;});
		// in-place editing is not offered: no edit session ever exists
		fn(l,"SetDataBrowserEditText",b->NO_ERR);
		fn(l,"CopyDataBrowserEditText",b->{put32(arg(1),0);return NOT_CONFIGURED;});
		fn(l,"GetDataBrowserEditText",b->NOT_CONFIGURED);
		fn(l,"SetDataBrowserEditItem",b->{b.editItem=0;b.editProperty=0;return NO_ERR;});
		fn(l,"GetDataBrowserEditItem",b->{put32(arg(1),b.editItem);put32(arg(2),b.editProperty);return NO_ERR;});
		fn(l,"GetDataBrowserItemPartBounds",b->{ // (browser, item, property, part, Rect*)
			Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;int[] r=partBounds(b,it,arg(2),arg(3));
			if(r==null)return ITEM_NOT_FOUND;if(r.length==0)return PROPERTY_NOT_FOUND;if(arg(4)!=0)writeRect(arg(4),r);return NO_ERR;});
		// ---- item data accessors (inside the item-data callback)
		data(l,"SetDataBrowserItemDataIcon",(cell,v)->{cell.icon=v;cell.mask|=Cell.ICON;});
		data(l,"GetDataBrowserItemDataIcon",(cell,p)->put32(p,cell.icon));
		l.register(null,"SetDataBrowserItemDataText",(r,c)->{Cell cell=cells.get(arg(0));if(cell==null){ret(PARAM_ERR);return;}String t=rt.libraries.textServices.cfText(arg(1));if(t==null){ret(INVALID_DATA);return;}cell.text=t;cell.mask|=Cell.TEXT;ret(NO_ERR);});
		data(l,"GetDataBrowserItemDataText",(cell,p)->put32(p,rt.libraries.textServices.newCFString(cell.text==null?"":cell.text))); // a new string the caller releases
		data(l,"SetDataBrowserItemDataValue",(cell,v)->{cell.value=v;cell.mask|=Cell.VALUE;});
		data(l,"GetDataBrowserItemDataValue",(cell,p)->put32(p,cell.value));
		data(l,"SetDataBrowserItemDataMinimum",(cell,v)->{cell.min=v;cell.mask|=Cell.MIN;});
		data(l,"GetDataBrowserItemDataMinimum",(cell,p)->put32(p,cell.min));
		data(l,"SetDataBrowserItemDataMaximum",(cell,v)->{cell.max=v;cell.mask|=Cell.MAX;});
		data(l,"GetDataBrowserItemDataMaximum",(cell,p)->put32(p,cell.max));
		data(l,"SetDataBrowserItemDataBooleanValue",(cell,v)->{cell.bool=v&0xff;cell.mask|=Cell.BOOL;});
		data(l,"GetDataBrowserItemDataBooleanValue",(cell,p)->{if(p!=0)memory.writeByte(p,cell.bool!=0?1:0);});
		data(l,"SetDataBrowserItemDataMenuRef",(cell,v)->{cell.menu=v;cell.mask|=Cell.MENU;});
		data(l,"GetDataBrowserItemDataMenuRef",(cell,p)->put32(p,cell.menu));
		data(l,"SetDataBrowserItemDataRGBColor",(cell,p)->{if(p!=0){cell.rgb=new int[]{memory.readWord(p),memory.readWord(p+2),memory.readWord(p+4)};cell.mask|=Cell.RGB;}});
		data(l,"GetDataBrowserItemDataRGBColor",(cell,p)->{if(p!=0){int[] c=cell.rgb==null?new int[3]:cell.rgb;memory.writeWord(p,c[0]);memory.writeWord(p+2,c[1]);memory.writeWord(p+4,c[2]);}});
		data(l,"SetDataBrowserItemDataDrawState",(cell,v)->{cell.drawState=v;cell.mask|=Cell.DRAW_STATE;});
		data(l,"GetDataBrowserItemDataDrawState",(cell,p)->put32(p,cell.drawState));
		data(l,"SetDataBrowserItemDataButtonValue",(cell,v)->{cell.button=v&0xffff;cell.mask|=Cell.BUTTON;});
		data(l,"GetDataBrowserItemDataButtonValue",(cell,p)->put16(p,cell.button));
		data(l,"SetDataBrowserItemDataIconTransform",(cell,v)->{cell.transform=(short)v;cell.mask|=Cell.TRANSFORM;});
		data(l,"GetDataBrowserItemDataIconTransform",(cell,p)->put16(p,cell.transform));
		data(l,"SetDataBrowserItemDataDateTime",(cell,v)->{cell.date=v&0xffffffffL;cell.mask|=Cell.DATE;});
		data(l,"GetDataBrowserItemDataDateTime",(cell,p)->put32(p,(int)cell.date));
		data(l,"SetDataBrowserItemDataLongDateTime",(cell,p)->{if(p!=0){cell.date=((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);cell.mask|=Cell.DATE;}});
		data(l,"GetDataBrowserItemDataLongDateTime",(cell,p)->{if(p!=0){memory.writeLong(p,(int)(cell.date>>>32));memory.writeLong(p+4,(int)cell.date);}});
		data(l,"SetDataBrowserItemDataItemID",(cell,v)->{cell.itemId=v;cell.mask|=Cell.ITEM_ID;});
		data(l,"GetDataBrowserItemDataItemID",(cell,p)->put32(p,cell.itemId));
		data(l,"GetDataBrowserItemDataProperty",(cell,p)->put32(p,cell.property));
		// (itemData, DataBrowserPropertyType*): the type of the property asked for - its column's, check box for the predefined Boolean properties
		l.register(null,"GetDataBrowserItemDataType",(r,c)->{Cell cell=cells.get(arg(0));if(cell==null){ret(PARAM_ERR);return;}put32(arg(1),cell.type);ret(cell.type==0?PROPERTY_NOT_FOUND:NO_ERR);});
		// ---- table view (list and column view)
		fn(l,"RemoveDataBrowserTableViewColumn",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;b.columns.remove(c);if(b.sortProperty==c.property)b.sortProperty=0;if(b.disclosure==c.property)b.disclosure=0;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewColumnCount",b->{put32(arg(1),b.columns.size());return NO_ERR;});
		fn(l,"SetDataBrowserTableViewHiliteStyle",b->{b.hiliteStyle=arg(1);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewHiliteStyle",b->{put32(arg(1),b.hiliteStyle);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewRowHeight",b->{b.rowHeight=Math.max(1,arg(1)&0xffff);b.rows=null;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewRowHeight",b->{put16(arg(1),b.rowHeight);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewColumnWidth",b->{b.columnWidth=Math.max(1,arg(1)&0xffff);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewColumnWidth",b->{put16(arg(1),b.columnWidth);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewItemRowHeight",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;it.rowHeight=arg(2)&0xffff;b.rows=null;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewItemRowHeight",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;put16(arg(2),it.rowHeight>0?it.rowHeight:b.rowHeight);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewNamedColumnWidth",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;c.width=arg(2)&0xffff;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewNamedColumnWidth",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;put16(arg(2),c.width);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewGeometry",b->{b.variableWidth=(arg(1)&0xff)!=0;b.variableHeight=(arg(2)&0xff)!=0;return NO_ERR;});
		fn(l,"GetDataBrowserTableViewGeometry",b->{if(arg(1)!=0)memory.writeByte(arg(1),b.variableWidth?1:0);if(arg(2)!=0)memory.writeByte(arg(2),b.variableHeight?1:0);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewItemID",b->{List<Row> rows=rows(b);int row=arg(1);if(row<0||row>=rows.size())return ITEM_NOT_FOUND;put32(arg(2),rows.get(row).item.id);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewItemRow",b->{ // (browser, item, row): moves the item among its container's items
			Item it=b.items.get(arg(1));if(it==null||it.parent==null)return ITEM_NOT_FOUND;List<Row> rows=rows(b);int row=arg(2);
			List<Item> siblings=it.parent.children;int to=row>=0&&row<rows.size()&&rows.get(row).item.parent==it.parent?siblings.indexOf(rows.get(row).item):Math.max(0,Math.min(siblings.size()-1,row));
			siblings.remove(it);siblings.add(Math.max(0,Math.min(siblings.size(),to)),it);b.rows=null;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewItemRow",b->{Item it=b.items.get(arg(1));if(it==null)return ITEM_NOT_FOUND;int row=rowIndex(b,it);if(row<0)return ITEM_NOT_FOUND;put32(arg(2),row);return NO_ERR;});
		fn(l,"SetDataBrowserTableViewColumnPosition",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;b.columns.remove(c);b.columns.add(Math.max(0,Math.min(b.columns.size(),arg(2)==-1?b.columns.size():arg(2))),c);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserTableViewColumnPosition",b->{Column c=column(b,arg(1));if(c==null)return PROPERTY_NOT_FOUND;put32(arg(2),b.columns.indexOf(c));return NO_ERR;});
		fn(l,"GetDataBrowserTableViewColumnProperty",b->{int i=arg(1);if(i<0||i>=b.columns.size())return PROPERTY_NOT_FOUND;put32(arg(2),b.columns.get(i).property);return NO_ERR;});
		// ---- list view
		fn(l,"AutoSizeDataBrowserListViewColumns",b->{autoSize(b);return NO_ERR;});
		fn(l,"AddDataBrowserListViewColumn",b->{ // (browser, DataBrowserListViewColumnDesc*, position)
			int p=arg(1),position=arg(2);if(p==0)return PARAM_ERR;
			Column c=new Column();c.property=memory.readLong(p);c.type=memory.readLong(p+4);c.flags=memory.readLong(p+8);
			int hd=p+12; // DataBrowserListViewHeaderDesc (mac68k alignment)
			if(memory.readLong(hd)!=0)return PARAM_ERR;
			readHeader(c,hd);c.width=Math.max(c.min,Math.min(c.max,b.columnWidth));
			Column old=column(b,c.property);if(old!=null)b.columns.remove(old);
			b.columns.add(position<0||position>b.columns.size()?b.columns.size():position,c);
			redraw(b);return NO_ERR;});
		fn(l,"SetDataBrowserListViewHeaderDesc",b->{ // (browser, column, DataBrowserListViewHeaderDesc*): version kDataBrowserListViewLatestHeaderDesc (0) only
			Column c=column(b,arg(1));int hd=arg(2);if(c==null)return PROPERTY_NOT_FOUND;if(hd==0||memory.readLong(hd)!=0)return PARAM_ERR;
			readHeader(c,hd);c.width=Math.max(c.min,Math.min(c.max,c.width));redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserListViewHeaderDesc",b->{ // the title is a new CFString the caller releases
			Column c=column(b,arg(1));int hd=arg(2);if(c==null)return PROPERTY_NOT_FOUND;if(hd==0||memory.readLong(hd)!=0)return PARAM_ERR;
			memory.writeWord(hd+4,c.min);memory.writeWord(hd+6,c.max);memory.writeWord(hd+8,c.titleOffset);memory.writeLong(hd+10,rt.libraries.textServices.newCFString(c.title));memory.writeWord(hd+14,c.initialOrder);
			memory.writeWord(hd+16,c.fontFlags);memory.writeWord(hd+18,c.font);memory.writeWord(hd+20,c.size);memory.writeWord(hd+22,c.face);memory.writeWord(hd+24,c.mode);memory.writeWord(hd+26,c.just);
			memory.load(hd+28,c.colors,0,12);memory.writeWord(hd+40,c.contentType);memory.writeLong(hd+42,c.content);return NO_ERR;});
		fn(l,"SetDataBrowserListViewHeaderBtnHeight",b->{b.headerHeight=arg(1)&0xffff;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserListViewHeaderBtnHeight",b->{put16(arg(1),b.headerHeight);return NO_ERR;});
		fn(l,"SetDataBrowserListViewUsePlainBackground",b->{b.plainBackground=(arg(1)&0xff)!=0;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserListViewUsePlainBackground",b->{if(arg(1)!=0)memory.writeByte(arg(1),b.plainBackground?1:0);return NO_ERR;});
		fn(l,"SetDataBrowserListViewDisclosureColumn",b->{int col=arg(1);if(col!=0&&column(b,col)==null)return PROPERTY_NOT_FOUND;b.disclosure=col;b.expandable=(arg(2)&0xff)!=0;redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserListViewDisclosureColumn",b->{put32(arg(1),b.disclosure);if(arg(2)!=0)memory.writeByte(arg(2),b.expandable?1:0);return NO_ERR;});
		// ---- column view
		fn(l,"GetDataBrowserColumnViewPath",b->{int h=arg(1);if(h==0)return PARAM_ERR;int n=b.path.size()-1;rt.toolbox("SetHandleSize",h,n*4);int p=memory.readLong(h);for(int i=0;i<n;i++)memory.writeLong(p+4*i,b.path.get(i+1).id);return NO_ERR;});
		fn(l,"GetDataBrowserColumnViewPathLength",b->{put32(arg(1),b.path.size()-1);return NO_ERR;});
		fn(l,"SetDataBrowserColumnViewPath",b->{ // (browser, length, path): the containers opened one after another from the target
			int n=arg(1),p=arg(2);truncatePath(b,1);
			for(int i=0;i<n&&p!=0;i++){int id=memory.readLong(p+4*i);if(id==b.root.id)continue;Item last=b.path.get(b.path.size()-1);Item it=b.items.get(id);if(it==null||it.parent!=last)return ITEM_NOT_FOUND;openContainer(b,it);b.path.add(it);}
			redraw(b);return NO_ERR;});
		fn(l,"SetDataBrowserColumnViewDisplayType",b->{b.displayType=arg(1);redraw(b);return NO_ERR;});
		fn(l,"GetDataBrowserColumnViewDisplayType",b->{put32(arg(1),b.displayType);return NO_ERR;});
		// ---- the Control Manager calls that reach a browser with more than the 68K Appearance Manager knows (keys, focus, modifiers)
		l.register(null,"HandleControlClick",(r,c)->{ // (control, Point, modifiers, action): the click's own modifiers reach the browser
			int ctl=arg(0),pt=arg(1),mods=arg(2)&0xffff,action=arg(3);Browser b=browsers.get(ctl);
			int part=b==null?(short)rt.toolbox("HandleControlClick",ctl,pt,mods,action):rt.toolbox.controls.partAt(ctl,(short)pt,pt>>16);
			if(b!=null&&part!=0)part=b.track(ctl,(short)pt,pt>>16,mods);
			rt.libraries.carbon.tracked(ctl,part,mods);ret(part);});
		l.register(null,"HandleControlKey",(r,c)->{ // (control, keyCode, charCode, modifiers)
			int ctl=arg(0),code=arg(1)&0xffff,ch=arg(2)&0xffff,mods=arg(3)&0xffff;
			ret(browsers.containsKey(ctl)?(short)rt.toolbox.controls.key(ctl,code,ch,mods):rt.toolbox("HandleControlKey",ctl,code,ch,mods));});
		l.register(null,"SetKeyboardFocus",(r,c)->{ // (window, control, part): a browser takes the focus itself; anything else loses it
			int window=arg(0),ctl=arg(1),part=(short)arg(2);Integer had=focus.get(window);
			if(browsers.containsKey(ctl)&&part!=0){rt.toolbox.controls.setKeyboardFocus(window,0,0);focus.put(window,ctl);if(had==null||had!=ctl)redraw(browsers.get(ctl));if(had!=null&&had!=ctl&&browsers.containsKey(had))redraw(browsers.get(had));ret(NO_ERR);return;}
			focus.remove(window);if(had!=null&&browsers.containsKey(had))redraw(browsers.get(had));
			ret(rt.toolbox("SetKeyboardFocus",window,ctl,part));});
		l.register(null,"GetKeyboardFocus",(r,c)->{Integer had=focus.get(arg(0));if(had!=null&&browsers.containsKey(had)){put32(arg(1),had);ret(NO_ERR);return;}ret(rt.toolbox("GetKeyboardFocus",arg(0),arg(1)));});
		l.register(null,"ClearKeyboardFocus",(r,c)->{Integer had=focus.remove(arg(0));if(had!=null&&browsers.containsKey(had))redraw(browsers.get(had));ret(rt.toolbox("ClearKeyboardFocus",arg(0)));});
	}
}

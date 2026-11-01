import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** List Manager (Pack 0): text cell lists drawn natively, with selection, scrolling and clicks. A list created with a
 *  non-zero procID runs the program's own 'LDEF' resource as guest code for every cell (for example, an archive listing). */
final class ListManager68k {
	final class ListState {
		int handle,window,rows,columns,cellWidth,cellHeight,scrollRow,scrollColumn,vScroll;
		int[] view;boolean drawIt=true,active=true,hasVScroll;
		/** The scroll bar LNew made (its handle alone is not enough: see {@link ListManager68k#scrollBar}). */
		ControlManager68k.Control vScrollControl;
		/** What LNew stored in ListRec.vScroll: a different value later means the program put its own bar there. */
		int recordVScroll;
		int ldef,cellsHandle;boolean cellsDirty=true;
		/** Drawn by the Communications Toolbox's LDEF (an icon above a name per cell): a tool's own LDEF chains to it at lInitMsg. */
		boolean ctbIcons;int[] cellOffsets=new int[]{0}; // custom LDEF and the guest copy of the cell data it may read
		byte[] exportedCells;int[] exportedOffsets; // what was last written to the guest copy, to notice the program editing it directly
		final Map<Long,byte[]> cells=new HashMap<Long,byte[]>();final List<Long> selected=new ArrayList<Long>();
		int[] icons; // host-side lists: an ICON_* per row drawn before the text (Standard File's folders and documents)
		java.awt.image.BufferedImage[] iconImages; // a real Finder icon per row (custom folder icons, 'icl8' of files) that replaces the drawn ICON_*
		String[][] details;int[] detailWidths; // host-side lists: extra columns per row (Standard File's date, size and kind), laid out from the cell's right edge; a negative width right-aligns its text
		long lastClickTime;int lastClickRow=-1,lastClickColumn=-1;
	}
	private boolean noNilHilite(ListState s){try{int rec=memory.readLong(s.handle);return rec!=0&&(memory.readByte(rec+36)&L_NO_NIL_HILITE)!=0;}catch(M68kCpuException e){return false;}}
	private static final Color SELECTION=new Color(0xb3,0xd4,0xff);
	private static final int L_ONLY_ONE=0x80,L_EXTEND_DRAG=0x40,L_NO_DISJOINT=0x20,L_NO_EXTEND=0x10,L_NO_RECT=0x08,L_USE_SENSE=0x04,L_NO_NIL_HILITE=0x02; // ListRec.selFlags
	static final int ICON_NONE=0,ICON_FOLDER=1,ICON_DOCUMENT=2,ICON_APPLICATION=3,ICON_DISK=4,ICON_DIMMED=0x100; // ICON_DIMMED: shown grey, not selectable (files in a Save dialog)
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,ListState> lists=new HashMap<Integer,ListState>();

	ListManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean pack0(M68kCpu cpu) throws M68kException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;int sp=cpu.a[7];
		// Every routine but LNew takes lHandle as its last (topmost) argument: adopt what the program wrote into the guest cell
		// data since the last export before acting on it. Some pickers re-lay their cells by hand after LAddRow/LDelColumn
		// and then counts them back with LGetCell at the next resize; reading the stale Java copy lost cells at every resize until
		// the count loop found none and spun forever (white window).
		if(selector!=0x44){ListState s=lists.get(memory.readLong(sp));if(s!=null){adoptRecord(s);if(s.exportedOffsets!=null)importCells(s);}}
		switch(selector){
			case 0x44:{int scrollVert=memory.readByte(sp),scrollHoriz=memory.readByte(sp+2),hasGrow=memory.readByte(sp+4),drawIt=memory.readByte(sp+6),window=memory.readLong(sp+8),proc=(short)memory.readWord(sp+12),cSize=memory.readLong(sp+14),dataBounds=memory.readLong(sp+18),rView=memory.readLong(sp+22);cpu.a[7]+=26;
				memory.writeLong(cpu.a[7],create(rView,dataBounds,cSize,window,proc,drawIt!=0,scrollVert!=0));return true;}
			case STD_LDEF_SELECTOR:stdLdef(sp);return true; // private: the stub patched into LDEF 0 (see patchStdLdef)
			case 0x28:{int h=memory.readLong(sp);cpu.a[7]+=4;ListState s=lists.remove(h);if(s!=null)dispose(s);return true;}
			case 0x04:case 0x08:{int h=memory.readLong(sp),at=(short)memory.readWord(sp+4),count=(short)memory.readWord(sp+6);cpu.a[7]+=8;ListState s=lists.get(h);int result=0;
				if(s!=null){if(selector==0x08){if(at<0||at>s.rows)at=s.rows;shift(s,true,at,count);s.rows+=count;result=at;}else{if(at<0||at>s.columns)at=s.columns;shift(s,false,at,count);s.columns+=count;result=at;}sync(s);draw(s);}
				memory.writeWord(cpu.a[7],result);return true;}
			case 0x20:case 0x24:{int h=memory.readLong(sp),at=(short)memory.readWord(sp+4),count=(short)memory.readWord(sp+6);cpu.a[7]+=8;ListState s=lists.get(h);
				if(s!=null){if(selector==0x24){if(count==0){s.rows=0;s.cells.clear();s.selected.clear();}else{shift(s,true,at+count,-count);s.rows=Math.max(0,s.rows-count);}}else{if(count==0){s.columns=0;s.cells.clear();s.selected.clear();}else{shift(s,false,at+count,-count);s.columns=Math.max(0,s.columns-count);}}sync(s);draw(s);}return true;}
			case 0x3c:{int h=memory.readLong(sp),cellPtr=memory.readLong(sp+4);boolean next=memory.readByte(sp+8)!=0;cpu.a[7]+=10;ListState s=lists.get(h);boolean found=false;
				if(s!=null&&cellPtr!=0){int row=(short)memory.readWord(cellPtr),col=(short)memory.readWord(cellPtr+2);
					for(int r=row;r<s.rows&&!found;r++)for(int c=(r==row?col:0);c<s.columns;c++){if(s.selected.contains(key(r,c))&&(next||(r==row&&c==col))){memory.writeWord(cellPtr,r);memory.writeWord(cellPtr+2,c);found=true;break;}if(!next)break;}}
				if(Mac68kApi.TRACE)System.err.println("[68K] LGetSelect next="+next+" from "+(cellPtr==0?"-":memory.readWord(cellPtr)+","+memory.readWord(cellPtr+2))+" rows="+(s==null?-1:s.rows)+" cols="+(s==null?-1:s.columns)+" selected="+(s==null?"-":s.selected)+" -> "+found);
				tb.writeBoolean(cpu.a[7],found);return true;}
			case 0x40:{int h=memory.readLong(sp);cpu.a[7]+=4;ListState s=lists.get(h);memory.writeLong(cpu.a[7],s==null?0:((s.lastClickRow&0xffff)<<16)|(s.lastClickColumn&0xffff));return true;}
			case 0x48:{int h=memory.readLong(sp),cellPtr=memory.readLong(sp+4);boolean vNext=memory.readByte(sp+8)!=0,hNext=memory.readByte(sp+10)!=0;cpu.a[7]+=12;ListState s=lists.get(h);boolean ok=false;
				// past the last cell the cell still moves on (out of the list) and the result is FALSE: AppleWorks 5 counts selected
				// cells with "while (LGetSelect(true, &c, l)) { n++; LNextCell(true, true, &c, l); }" - its Define Fields dialog
				// hung after Create when the cell stayed on the last one, which LGetSelect found again and again
				if(s!=null&&cellPtr!=0){int row=(short)memory.readWord(cellPtr),col=(short)memory.readWord(cellPtr+2);
					if(hNext&&col+1<s.columns){col++;ok=true;}else if(vNext){row++;if(hNext)col=0;ok=row<s.rows;}else if(hNext)col++;
					if(hNext||vNext){memory.writeWord(cellPtr,row);memory.writeWord(cellPtr+2,col);}}
				if(Mac68kApi.TRACE)System.err.println("[68K] LNextCell h="+hNext+" v="+vNext+" rows="+(s==null?-1:s.rows)+" cols="+(s==null?-1:s.columns)+" -> "+ok);
				tb.writeBoolean(cpu.a[7],ok);return true;}
			case 0x54:{int h=memory.readLong(sp),cellPtr=memory.readLong(sp+4),proc=memory.readLong(sp+8),len=(short)memory.readWord(sp+12),data=memory.readLong(sp+14);cpu.a[7]+=18;ListState s=lists.get(h);boolean found=false;
				if(s!=null&&cellPtr!=0){byte[] target=len>0?memory.readBytes(data,len):new byte[0];int row=(short)memory.readWord(cellPtr),col=(short)memory.readWord(cellPtr+2);
					outer:for(int r=row;r<s.rows;r++)for(int c=(r==row?col:0);c<s.columns;c++){byte[] v=s.cells.get(key(r,c));if(v!=null&&startsWith(v,target)){memory.writeWord(cellPtr,r);memory.writeWord(cellPtr+2,c);found=true;break outer;}}}
				tb.writeBoolean(cpu.a[7],found);return true;}
			case 0x60:{int h=memory.readLong(sp),height=(short)memory.readWord(sp+4),width=(short)memory.readWord(sp+6);cpu.a[7]+=8;ListState s=lists.get(h);if(s!=null){s.view[2]=s.view[0]+height;s.view[3]=s.view[1]+width;
				// the scroll bar follows the view's right edge and height, where LNew put it (CodeWarrior 5's project window lists are
				// made 235 wide, then the window is sized to the project's saved width: the bar stayed in the middle of the window)
				if(scrollBar(s)!=0)tb.controls.setBounds(s.vScroll,s.view[0],s.view[3],s.view[2],s.view[3]+16);
				sync(s);draw(s);}return true;}
			case 0x30:{int h=memory.readLong(sp);cpu.a[7]+=8;ListState s=lists.get(h);if(s!=null)draw(s);return true;}
			case 0x2c:{int h=memory.readLong(sp);boolean drawIt=memory.readByte(sp+4)!=0;cpu.a[7]+=6;ListState s=lists.get(h);if(s!=null){s.drawIt=drawIt;if(drawIt)drawnLater(s);}return true;}
			case 0x50:{int h=memory.readLong(sp),dRows=(short)memory.readWord(sp+4),dCols=(short)memory.readWord(sp+6);cpu.a[7]+=8;ListState s=lists.get(h);if(s!=null){s.scrollRow=Math.max(0,Math.min(Math.max(0,s.rows-visibleRows(s)),s.scrollRow+dRows));s.scrollColumn=Math.max(0,s.scrollColumn+dCols);syncScroll(s);draw(s);}return true;}
			case 0x10:{int h=memory.readLong(sp);cpu.a[7]+=4;ListState s=lists.get(h);if(s!=null&&!s.selected.isEmpty()){int row=(int)(s.selected.get(0)>>>32);if(row<s.scrollRow||row>=s.scrollRow+visibleRows(s)){s.scrollRow=Math.max(0,Math.min(row,Math.max(0,s.rows-visibleRows(s))));syncScroll(s);draw(s);}}return true;}
			// LUpdate redraws the scroll bar as well as the cells (an update handler may use BeginUpdate / LUpdate / DrawGrowIcon /
			// EndUpdate with no DrawControls; the bar was drawn only at LNew, inside the clip narrowed to the cells)
			// LUpdate(theRgn, lHandle) draws only the cells whose rectangles meet theRgn: none when it is empty, as the visRgn of a
			// window not shown yet is. Cythera fills its To-Do window's list before its game model has emptied the entries (zeros
			// then, not its "empty" marker) and calls LUpdate on the hidden window: drawn anyway, its LDEF met an object it does
			// not know and the game quit right after its opening cutscene.
			case 0x64:{int h=memory.readLong(sp),rgn=memory.readLong(sp+4);cpu.a[7]+=8;ListState s=lists.get(h);if(s!=null){
				int data=rgn==0?0:memory.readLong(rgn);updateArea=data==0?null:QdRegion.decode(memory,data);
				try{if(updateArea==null||!updateArea.isEmpty()){draw(s);drawScrollBar(s);}}finally{updateArea=null;}}return true;}
			case 0x00:{int h=memory.readLong(sp);boolean act=memory.readByte(sp+4)!=0;cpu.a[7]+=6;ListState s=lists.get(h);if(s!=null){s.active=act;memory.writeByte(memory.readLong(h)+37,act?1:0);draw(s);
				if(scrollBar(s)!=0){int d=tb.controls.data(s.vScroll);if(d!=0)memory.writeByte(d+17,act?0:255);drawScrollBar(s);}}return true;} // LActivate: an inactive list's scroll bar is dimmed
			case 0x14:{int h=memory.readLong(sp),cSize=memory.readLong(sp+4);cpu.a[7]+=8;ListState s=lists.get(h);if(s!=null){s.cellHeight=Math.max(1,(short)(cSize>>16));s.cellWidth=Math.max(1,(short)cSize);sync(s);draw(s);}return true;}
			case 0x18:{int h=memory.readLong(sp),modifiers=memory.readWord(sp+4),pt=memory.readLong(sp+6);cpu.a[7]+=10;ListState s=lists.get(h);boolean dbl=s!=null&&click(s,(short)pt,(short)(pt>>16),modifiers);tb.writeBoolean(cpu.a[7],dbl);return true;}
			case 0x0c:case 0x58:{int h=memory.readLong(sp),cell=memory.readLong(sp+4),len=(short)memory.readWord(sp+8),data=memory.readLong(sp+10);cpu.a[7]+=14;ListState s=lists.get(h);
				if(s!=null){long k=key((short)(cell>>16),(short)cell);byte[] bytes=len>0?memory.readBytes(data,len):new byte[0];if(selector==0x0c){byte[] old=s.cells.get(k);if(old!=null){byte[] j=new byte[old.length+bytes.length];System.arraycopy(old,0,j,0,old.length);System.arraycopy(bytes,0,j,old.length,bytes.length);bytes=j;}}s.cells.put(k,bytes);cellsChanged(s);drawCell(s,(short)(cell>>16),(short)cell);}return true;}
			case 0x1c:{int h=memory.readLong(sp),cell=memory.readLong(sp+4);cpu.a[7]+=8;ListState s=lists.get(h);if(s!=null){s.cells.remove(key((short)(cell>>16),(short)cell));cellsChanged(s);drawCell(s,(short)(cell>>16),(short)cell);}return true;}
			case 0x38:{int h=memory.readLong(sp),cell=memory.readLong(sp+4),lenPtr=memory.readLong(sp+8),data=memory.readLong(sp+12);cpu.a[7]+=16;ListState s=lists.get(h);
				byte[] v=s==null?null:s.cells.get(key((short)(cell>>16),(short)cell));int max=lenPtr==0?0:(short)memory.readWord(lenPtr);int n=v==null?0:Math.min(max,v.length);if(n>0&&data!=0)memory.load(data,java.util.Arrays.copyOf(v,n));if(lenPtr!=0)memory.writeWord(lenPtr,n);
				if(Mac68kApi.TRACE)System.err.println("[68K] LGetCell $"+Integer.toHexString(h)+" ("+(short)(cell>>16)+","+(short)cell+") max "+max+" -> "+n+(s==null?" (no list)":" of "+s.cells.size()+" cells "+s.rows+"x"+s.columns));return true;}
			case 0x4c:{int h=memory.readLong(sp),cell=memory.readLong(sp+4),rectPtr=memory.readLong(sp+8);cpu.a[7]+=12;ListState s=lists.get(h);if(s!=null&&rectPtr!=0){int[] r=cellRect(s,(short)(cell>>16),(short)cell);memory.writeWord(rectPtr,r[0]);memory.writeWord(rectPtr+2,r[1]);memory.writeWord(rectPtr+4,r[2]);memory.writeWord(rectPtr+6,r[3]);}return true;}
			case 0x5c:{int h=memory.readLong(sp),cell=memory.readLong(sp+4);boolean set=memory.readByte(sp+8)!=0;cpu.a[7]+=10;ListState s=lists.get(h);if(s!=null){long k=key((short)(cell>>16),(short)cell);if(set){if(!s.selected.contains(k))s.selected.add(k);}else s.selected.remove(k);writeSelectionBits(s);drawCell(s,(short)(cell>>16),(short)cell);}return true;}
			case 0x34:{int h=memory.readLong(sp),cell=memory.readLong(sp+4),lenPtr=memory.readLong(sp+8),offsetPtr=memory.readLong(sp+12);cpu.a[7]+=16;ListState s=lists.get(h);byte[] v=s==null?null:s.cells.get(key((short)(cell>>16),(short)cell));if(lenPtr!=0)memory.writeWord(lenPtr,v==null?0:v.length);if(offsetPtr!=0)memory.writeWord(offsetPtr,0);return true;}
			default:throw new M68kMacApiException(cpu.pc,0xa9e7,"List Manager selector "+selector);
		}
	}

	// ------------------------------------------------------------------ host-side lists (Standard File's file list)

	/** LNew for a one-column text list with a vertical scroll bar, drawn into {@code window}. */
	int newList(int window,int[] view,int cellHeight) throws M68kException {
		int rView=memory.allocate(8,2),dataBounds=memory.allocate(8,2);
		try{for(int i=0;i<4;i++)memory.writeWord(rView+i*2,view[i]);memory.writeWord(dataBounds,0);memory.writeWord(dataBounds+2,0);memory.writeWord(dataBounds+4,0);memory.writeWord(dataBounds+6,1);
			return create(rView,dataBounds,(cellHeight<<16)|((view[3]-view[1])&0xffff),window,0,true,true);}
		finally{memory.free(rView);memory.free(dataBounds);}
	}
	/** Replaces every row of a host-side list (selection cleared, scrolled to the top). */
	void setRows(int handle,List<byte[]> rows) throws M68kCpuException {setRows(handle,rows,null);}
	/** As above, with an {@code ICON_*} per row drawn in front of its text. */
	void setRows(int handle,List<byte[]> rows,int[] icons) throws M68kCpuException {setRows(handle,rows,icons,null);}
	/** As above; a non-null image in {@code images} (32x32, shown at 16x16) is drawn instead of the row's {@code ICON_*}. */
	void setRows(int handle,List<byte[]> rows,int[] icons,java.awt.image.BufferedImage[] images) throws M68kCpuException {setRows(handle,rows,icons,images,null,null);}
	/** As above; {@code details} adds text columns after the name, {@code detailWidths[k]} pixels each from the right edge of the cell
	 *  (a negative width right-aligns the column's text). Null details: a plain one-column list. */
	void setRows(int handle,List<byte[]> rows,int[] icons,java.awt.image.BufferedImage[] images,String[][] details,int[] detailWidths) throws M68kCpuException {
		ListState s=lists.get(handle);if(s==null)return;
		s.cells.clear();s.selected.clear();s.rows=rows.size();s.columns=1;s.scrollRow=0;s.lastClickRow=-1;s.cellsDirty=true;s.icons=icons;s.iconImages=images;s.details=details;s.detailWidths=detailWidths;
		for(int i=0;i<rows.size();i++)s.cells.put(key(i,0),rows.get(i));
		sync(s);draw(s);
	}
	/** LClick; true on a double click of the same cell. */
	boolean clickList(int handle,int h,int v,int modifiers) throws M68kException {ListState s=lists.get(handle);return s!=null&&click(s,h,v,modifiers);}
	/** True when the point is inside the list's view or its scroll bar. */
	boolean hitsList(int handle,int h,int v){ListState s=lists.get(handle);if(s==null)return false;int right=s.view[3]+(s.hasVScroll?16:0);return h>=s.view[1]&&h<right&&v>=s.view[0]&&v<s.view[2];}
	int selectedRow(int handle){ListState s=lists.get(handle);return s==null||s.selected.isEmpty()?-1:(int)(s.selected.get(0)>>>32);}
	void selectRow(int handle,int row) throws M68kCpuException {ListState s=lists.get(handle);if(s==null)return;s.selected.clear();if(row>=0&&row<s.rows){s.selected.add(key(row,0));if(row<s.scrollRow||row>=s.scrollRow+visibleRows(s))s.scrollRow=Math.max(0,Math.min(row,Math.max(0,s.rows-visibleRows(s))));}syncScroll(s);draw(s);}
	int rowCount(int handle){ListState s=lists.get(handle);return s==null?0:s.rows;}
	/** Scrolls by whole rows (arrow keys / page keys). */
	void scrollRows(int handle,int delta) throws M68kCpuException {ListState s=lists.get(handle);if(s==null)return;s.scrollRow=Math.max(0,Math.min(Math.max(0,s.rows-visibleRows(s)),s.scrollRow+delta));syncScroll(s);draw(s);}
	/** Redraws the cells and the scroll bar (after the dialog erased its window). */
	void drawList(int handle) throws M68kCpuException {ListState s=lists.get(handle);if(s==null)return;draw(s);syncScroll(s);if(scrollBar(s)!=0){Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g!=null){try{g.setColor(Color.BLACK);g.drawRect(s.view[1]-1,s.view[0]-1,s.view[3]-s.view[1]+1+(s.hasVScroll?16:0),s.view[2]-s.view[0]+1);}finally{g.dispose();}}}}
	void disposeList(int handle) throws M68kCpuException {ListState s=lists.remove(handle);if(s!=null)dispose(s);}

	/**
	 * The ListRec fields programs set themselves: port and rView. Coloring Book 3.2 makes its voice and artist lists once in its
	 * main window and, each time it opens the "Select a Voice and an Artist" window, stores that window in (**list).port
	 * before LUpdate/LDoDraw - the names were drawn over the main window's colour palette.
	 */
	/** The port a list draws in (tests). */
	int portOf(int handle){ListState s=lists.get(handle);return s==null?0:s.window;}
	private void adoptRecord(ListState s) throws M68kCpuException {
		int r=memory.readLong(s.handle);if(r==0)return;
		int port=memory.readLong(r+8);if(port!=0&&port!=s.window)s.window=port;
		for(int i=0;i<4;i++)s.view[i]=(short)memory.readWord(r+i*2);
	}
	private static long key(int row,int col){return ((long)row<<32)|(col&0xffffffffL);}
	private static boolean startsWith(byte[] v,byte[] t){if(t.length>v.length)return false;for(int i=0;i<t.length;i++)if(Character.toLowerCase((char)(v[i]&0xff))!=Character.toLowerCase((char)(t[i]&0xff)))return false;return true;}

	/** List definitions registered by ID (CarbonLib's RegisterListDefinition): a Handle whose master pointer is the definition's entry. */
	private final java.util.Map<Integer,Integer> registeredDefinitions=new java.util.HashMap<Integer,Integer>();
	void registerDefinition(int id,int handle){if(handle==0)registeredDefinitions.remove(id);else registeredDefinitions.put(id,handle);}
	private int create(int rView,int dataBounds,int cSize,int window,int proc,boolean drawIt,boolean scrollVert) throws M68kException {
		byte[] record=new byte[88];int handle=tb.api.createHandle(record);int r=memory.readLong(handle);
		ListState s=new ListState();s.handle=handle;s.window=window;s.drawIt=drawIt;
		s.view=new int[]{(short)memory.readWord(rView),(short)memory.readWord(rView+2),(short)memory.readWord(rView+4),(short)memory.readWord(rView+6)};
		s.rows=(short)memory.readWord(dataBounds+4)-(short)memory.readWord(dataBounds);s.columns=(short)memory.readWord(dataBounds+6)-(short)memory.readWord(dataBounds+2);
		int ch=(short)(cSize>>16),cw=(short)cSize;
		s.cellHeight=ch>0?ch:16;s.cellWidth=cw>0?cw:Math.max(1,(s.view[3]-s.view[1])/Math.max(1,s.columns));
		memory.copy(rView,r,8);memory.writeLong(r+8,window);memory.writeWord(r+16,s.cellHeight);memory.writeWord(r+18,s.cellWidth);memory.writeByte(r+37,1);memory.writeLong(r+60,0);
		if(scrollVert){s.hasVScroll=true;s.vScroll=tb.controls.create(window,s.view[0],s.view[3],s.view[2],s.view[3]+16,new byte[]{0},true,0,0,0,16,0);memory.writeLong(r+28,s.vScroll);s.recordVScroll=s.vScroll;s.vScrollControl=tb.controls.controls.get(s.vScroll);}
		// procID 0 is GetResource('LDEF',0) on a Mac too: an LDEF 0 in a file above the System's is the program's own (the Yennie
		// eXternals' showListPatch renumbers its stack's LDEF 300 "cicnText" to 0 around ShowList's LNew(0) to get colour icon cells)
		ResourceFile68k.Resource own0=proc==0?tb.resources.find(0x4c444546,0):null;
		if(proc!=0&&proc!=CTB_LDEF||own0!=null&&!tb.resources.isSystem(own0)){ // custom list definition: one registered for the ID (RegisterListDefinition), else the program's 'LDEF' resource, kept locked and stored in listDefProc
			Integer registered=registeredDefinitions.get(proc);
			ResourceFile68k.Resource res=registered!=null?null:tb.resources.find(0x4c444546,proc);int h=registered!=null?registered:res==null?0:tb.resources.handleFor(res);
			if(h!=0&&memory.readLong(h)!=0){tb.api.setHandleState(h,tb.api.handleState(h)|0x80);s.ldef=h;memory.writeLong(memory.readLong(handle)+64,h);}
		}
		lists.put(handle,s);sync(s);
		if(s.ldef!=0){callDefProc(s,L_INIT,false,null,0,0);followDefProc(s);}
		if(drawIt)draw(s);
		return handle;
	}
	private void dispose(ListState s) throws M68kCpuException {
		if(s.ldef!=0)try{callDefProc(s,L_CLOSE,false,null,0,0);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
		if(scrollBar(s)!=0)tb.controls.dispose(s.vScroll,true);
		int guest=guestCellsHandle(s);if(tb.api.isHandle(guest))s.cellsHandle=guest;
		if(tb.api.isHandle(s.cellsHandle))tb.api.disposeHandleAndFree(s.cellsHandle);
		tb.api.disposeHandleAndFree(s.handle);
	}

	// ------------------------------------------------------------------ custom LDEFs (guest code)

	private static final int L_INIT=0,L_DRAW=1,L_HILITE=2,L_CLOSE=3;

	/** Rebuilds the guest copy of the cell data ((**lHandle).cells and cellArray) that an LDEF may read through lDataOffset/lDataLen. */
	private void syncCells(ListState s) throws M68kCpuException {
		if(s.ldef==0)return;
		if(s.exportedOffsets!=null)importCells(s);
		if(!s.cellsDirty)return;
		int n=Math.max(0,s.rows)*Math.max(0,s.columns);int[] offsets=new int[n+1];java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
		for(int row=0;row<s.rows;row++)for(int col=0;col<s.columns;col++){offsets[row*s.columns+col]=data.size();byte[] v=s.cells.get(key(row,col));if(v!=null)data.write(v,0,v.length);}
		offsets[n]=data.size();s.cellOffsets=offsets;
		byte[] bytes=data.toByteArray();
		// The program may have replaced the cells Handle (a picker may save a HandToHand copy across LAddRow/LDelColumn,
		// disposes the List Manager's and stores its copy in the record): write into the record's current Handle, never a disposed one.
		int guest=guestCellsHandle(s);if(tb.api.isHandle(guest))s.cellsHandle=guest;
		if(!tb.api.isHandle(s.cellsHandle))s.cellsHandle=tb.api.createHandle(bytes);else tb.api.loadHandle(s.cellsHandle,bytes);
		tb.api.resizeHandle(s.handle,88+2*(n+1));int r=memory.readLong(s.handle);
		memory.writeLong(r+80,s.cellsHandle);memory.writeWord(r+84,2*n);
		for(int i=0;i<=n;i++)memory.writeWord(r+86+2*i,offsets[i]);
		s.cellsDirty=false;s.exportedCells=bytes;s.exportedOffsets=offsets;writeSelectionBits(s); // the new offsets first: writeSelectionBits copies s.exportedOffsets into cellArray (FreeHand 4's tool palette exports twice)
		if(Mac68kApi.TRACE)System.err.println("[68K] list $"+Integer.toHexString(s.handle)+" exported "+s.cells.size()+" cells ("+s.rows+"x"+s.columns+", "+bytes.length+" bytes)");
	}
	/**
	 * LSetCell/LAddToCell/LClrCell: the ROM writes the cell into (**lHandle).cells at once, and a list with its own LDEF is one whose
	 * program may read the cells there itself - so the guest copy follows now, not at the next drawing. ResCopy 2.0 sets 20 cells
	 * with drawing off and sorts them in cells/cellArray by hand: the last LSetCell never reached the guest copy, which was then
	 * adopted as the truth (a resource lost, a garbage row "IC 20302").
	 */
	private void cellsChanged(ListState s) throws M68kCpuException {s.cellsDirty=true;if(s.ldef!=0)syncCells(s);}
	/** Bit 15 of each cellArray word marks a selected cell, as the ROM keeps it: a program with its own LDEF may test it
	 *  directly instead of calling LGetSelect. */
	private void writeSelectionBits(ListState s) throws M68kCpuException {
		if(s.ldef==0||s.exportedOffsets==null)return;
		int n=Math.max(0,s.rows)*Math.max(0,s.columns);if(s.exportedOffsets.length!=n+1||tb.api.handleSize(s.handle)<88+2*(n+1))return;
		int r=memory.readLong(s.handle);if(r==0)return;
		for(int row=0;row<s.rows;row++)for(int col=0;col<s.columns;col++){int i=row*s.columns+col;memory.writeWord(r+86+2*i,s.exportedOffsets[i]|(s.selected.contains(key(row,col))?0x8000:0));}
	}
	/** (**lHandle).cells as the record holds it now. */
	private int guestCellsHandle(ListState s) throws M68kCpuException {int r=memory.readLong(s.handle);return r==0?0:memory.readLong(r+80);}
	/** Programs may fill (**lHandle).cells and cellArray themselves instead of calling LSetCell (some resize the cells
	 *  Handle and stores a two-byte index per row right after LAddRow); when the guest copy no longer matches what was
	 *  exported, it becomes the truth. */
	private void importCells(ListState s) throws M68kCpuException {
		int n=Math.max(0,s.rows)*Math.max(0,s.columns);
		if(tb.api.handleSize(s.handle)<88+2*(n+1))return;
		// The list was resized (LAddRow/LDelRow...) since the guest copy was written: its cellArray has the old shape, so it holds no
		// edits to adopt - and the cells set by LSetCell since then (QuarkXPress 3.31's installer fills 10 rows, then deletes the rest)
		// must not be replaced by it
		if(s.exportedOffsets!=null&&s.exportedOffsets.length!=n+1)return;
		int r=memory.readLong(s.handle),cellsHandle=memory.readLong(r+80);if(r==0||cellsHandle==0)return;
		int[] offsets=new int[n+1];for(int i=0;i<=n;i++)offsets[i]=memory.readWord(r+86+2*i)&0x7fff; // bit 15: the cell is selected
		int data=memory.readLong(cellsHandle),size=tb.api.handleSize(cellsHandle);
		byte[] bytes=data==0||size<=0?new byte[0]:memory.readBytes(data,Math.min(size,Math.max(0,offsets[n])));
		if(cellsHandle==s.cellsHandle&&java.util.Arrays.equals(offsets,s.exportedOffsets)&&java.util.Arrays.equals(bytes,s.exportedCells))return;
		s.cells.clear();
		for(int row=0;row<s.rows;row++)for(int col=0;col<s.columns;col++){int i=row*s.columns+col,from=Math.max(0,Math.min(bytes.length,offsets[i])),to=Math.max(from,Math.min(bytes.length,offsets[i+1]));if(to>from)s.cells.put(key(row,col),java.util.Arrays.copyOfRange(bytes,from,to));}
		s.cellOffsets=offsets;s.cellsHandle=cellsHandle;s.exportedCells=bytes;s.exportedOffsets=offsets;s.cellsDirty=false;
		if(Mac68kApi.TRACE)System.err.println("[68K] list $"+Integer.toHexString(s.handle)+" adopted "+s.cells.size()+" guest cells ("+s.rows+"x"+s.columns+", "+bytes.length+" bytes)");
	}

	private static final int STD_LDEF_SELECTOR=0x7ffe;
	/** The Communications Toolbox's LDEF in the System file (ID -32512): its lists are drawn as standard text rows. */
	static final int CTB_LDEF=-32512;
	/** The System file's LDEF 0 is a no-op stub (pops the 20 bytes of arguments and returns), but installers with their own LDEF chain
	 *  to it by GetResource('LDEF',0) for their text rows (InstallerMaker's custom-install list: rows stayed blank). Its code in memory
	 *  becomes `move.w #STD_LDEF_SELECTOR,-(sp); _Pack0` ahead of the stub, so the standard text cell is drawn by {@link #stdLdef}. */
	private void patchStdLdef() throws M68kException {
		ResourceFile68k.Resource res=tb.resources.find(0x4c444546,0);if(res==null)return;
		int h=tb.resources.handleFor(res);if(h==0||memory.readLong(h)==0||tb.api.handleSize(h)!=8)return;
		int p=memory.readLong(h);if(memory.readWord(p)!=0x205f||memory.readWord(p+2)!=0xdefc)return; // the stub: movea.l (sp)+,a0; adda.w #20,sp; jmp (a0)
		tb.api.resizeHandle(h,14);p=memory.readLong(h);
		for(int i=6;i>=3;i--)memory.writeWord(p+i*2,memory.readWord(p+(i-3)*2));
		memory.writeWord(p,0x3f3c);memory.writeWord(p+2,STD_LDEF_SELECTOR);memory.writeWord(p+4,0xa9e7);
	}
	/** The standard text LDEF, called from a program's own LDEF; sp is the return address, then the LDEF's arguments. */
	private void stdLdef(int sp) throws M68kCpuException {
		int list=memory.readLong(sp+4),length=memory.readWord(sp+8),offset=memory.readWord(sp+10),rect=memory.readLong(sp+16),message=memory.readWord(sp+22);boolean selected=memory.readByte(sp+20)!=0; // Pascal: the last argument sits nearest the return address
		ListState s=lists.get(list);if(s==null||rect==0||(message!=L_DRAW&&message!=L_HILITE))return;
		int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
		int cells=guestCellsHandle(s),data=cells==0?0:memory.readLong(cells);
		byte[] text=data==0||length==0?new byte[0]:memory.readBytes(data+offset,Math.min(length,Math.max(0,tb.api.handleSize(cells)-offset)));
		Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
		try{g.setClip(Math.max(left,s.view[1]),Math.max(top,s.view[0]),Math.min(right,s.view[3])-Math.max(left,s.view[1]),Math.min(bottom,s.view[2])-Math.max(top,s.view[0]));
			g.setColor(selected?(s.active?SELECTION:new Color(0xe0,0xe0,0xe0)):Color.WHITE);g.fillRect(left,top,right-left,bottom-top);
			Font font=cellFont(s);g.setFont(font);FontMetrics fm=Fonts68k.metrics(font);g.setColor(Color.BLACK);
			Fonts68k.drawString(g,fm,tb.decode(text),left+4,top+(bottom-top-fm.getHeight())/2+fm.getAscent());
		}finally{g.dispose();}
	}

	/** pascal void LDEF(short lMessage, Boolean lSelect, Rect *lRect, Cell lCell, short lDataOffset, short lDataLen, ListHandle lHandle),
	 *  run in the list's port with the clip narrowed to rView. */
	private void callDefProc(ListState s,int message,boolean selected,int[] rect,int row,int col) throws M68kException {
		int entry=memory.readLong(s.ldef);if(entry==0)return;
		patchStdLdef();
		syncCells(s);
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		int savedPort=tb.api.quickDraw().currentPortAddress(),savedClip=0;
		try{
			if(s.window!=0&&savedPort!=s.window)setPort(cpu,s.window);
			int rectPtr=0;
			if(rect!=null){
				int[] c=tb.api.quickDraw().clipRect();savedClip=saveClip(cpu);
				clip(cpu,new int[]{Math.max(c[0],s.view[0]),Math.max(c[1],s.view[1]),Math.min(c[2],s.view[2]),Math.min(c[3],s.view[3])});
				cpu.a[7]-=8;rectPtr=cpu.a[7];for(int i=0;i<4;i++)memory.writeWord(rectPtr+i*2,rect[i]);
			}
			int index=row*Math.max(0,s.columns)+col,offset=0,length=0;
			if(index>=0&&index+1<s.cellOffsets.length){offset=s.cellOffsets[index];length=s.cellOffsets[index+1]-offset;}
			cpu.pushWord(message);cpu.pushWord(selected?0x100:0);cpu.pushLong(rectPtr);cpu.pushLong(((row&0xffff)<<16)|(col&0xffff));cpu.pushWord(offset);cpu.pushWord(length);cpu.pushLong(s.handle);
			cpu.callPascal(entry,20);
		}finally{
			if(savedClip!=0)restoreClip(cpu,savedClip);
			if(s.window!=0&&savedPort!=s.window&&savedPort!=0)setPort(cpu,savedPort);
			System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);
		}
	}
	// SetPort/ClipRect/GetClip/SetClip are core QuickDraw (tb.api.quickDraw()): the host drawing layer (tb.quickDraw) does not take them
	// and answered false - an LDEF ran in whatever port was current (Fetch 4's shortcut list drew into the Window Manager port).
	private void setPort(M68kCpu cpu,int port) throws M68kException {int saved=cpu.a[7];cpu.pushLong(port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}
	private void clip(M68kCpu cpu,int[] rect) throws M68kException {int saved=cpu.a[7];cpu.a[7]-=8;for(int i=0;i<4;i++)memory.writeWord(cpu.a[7]+i*2,rect[i]);cpu.pushLong(cpu.a[7]);tb.api.quickDraw().handle(cpu,0x87b);cpu.a[7]=saved;}
	/** The port's clip region copied into a new region (GetClip), put back whole by restoreClip (not only its bounding box). */
	private int saveClip(M68kCpu cpu) throws M68kException {
		QuickDraw68k qd=tb.api.quickDraw();int saved=cpu.a[7];cpu.a[7]-=4;qd.handle(cpu,0x8d8);int rgn=memory.readLong(cpu.a[7]);cpu.a[7]=saved;
		if(rgn!=0){cpu.pushLong(rgn);qd.handle(cpu,0x87a);cpu.a[7]=saved;}return rgn;
	}
	private void restoreClip(M68kCpu cpu,int rgn) throws M68kException {
		QuickDraw68k qd=tb.api.quickDraw();int saved=cpu.a[7];cpu.pushLong(rgn);qd.handle(cpu,0x879);cpu.a[7]=saved;cpu.pushLong(rgn);qd.handle(cpu,0x8d9);cpu.a[7]=saved;
	}

	/** Redraws every visible cell through the LDEF (lDrawMsg carries the selection state, so no separate hilite pass is needed)
	 *  and clears the part of rView beyond the last row and column. */
	/** The region an LUpdate in progress draws in (null: the whole list). */
	private QdRegion updateArea;
	private boolean inUpdateArea(int[] r){
		if(updateArea==null)return true;
		for(int[] a:updateArea.rects())if(a[0]<r[2]&&r[0]<a[2]&&a[1]<r[3]&&r[1]<a[3])return true;
		return false;
	}
	private void drawGuest(ListState s) throws M68kCpuException {
		try{
			int bottom=s.view[0],right=s.view[1];
			for(int row=s.scrollRow;row<s.rows&&(row-s.scrollRow)*s.cellHeight<s.view[2]-s.view[0];row++)for(int col=s.scrollColumn;col<s.columns&&(col-s.scrollColumn)*s.cellWidth<s.view[3]-s.view[1];col++){
				int[] r=cellRect(s,row,col);if(inUpdateArea(r))callDefProc(s,L_DRAW,s.selected.contains(key(row,col)),r,row,col);bottom=Math.max(bottom,r[2]);right=Math.max(right,r[3]);}
			Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
			try{g.setClip(s.view[1],s.view[0],s.view[3]-s.view[1],s.view[2]-s.view[0]);g.setColor(Color.WHITE);
				if(bottom<s.view[2])g.fillRect(s.view[1],bottom,s.view[3]-s.view[1],s.view[2]-bottom);
				if(right<s.view[3])g.fillRect(right,s.view[0],s.view[3]-right,s.view[2]-s.view[0]);
			}finally{g.dispose();}
		}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
	}
	private void drawGuestCell(ListState s,int row,int col) throws M68kCpuException {
		if(row<s.scrollRow||col<s.scrollColumn||row>=s.rows||col>=s.columns)return;
		int[] r=cellRect(s,row,col);if(r[0]>=s.view[2]||r[1]>=s.view[3])return;
		try{callDefProc(s,L_DRAW,s.selected.contains(key(row,col)),r,row,col);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw Toolbox68k.wrap(e);}
	}

	private void shift(ListState s,boolean rows,int at,int count){
		Map<Long,byte[]> moved=new HashMap<Long,byte[]>();List<Long> sel=new ArrayList<Long>();
		for(Map.Entry<Long,byte[]> e:s.cells.entrySet()){int row=(int)(e.getKey()>>>32),col=(int)(long)e.getKey();int idx=rows?row:col;
			if(count<0&&idx>=at+count&&idx<at)continue;if(idx>=at){if(rows)row+=count;else col+=count;}moved.put(key(row,col),e.getValue());}
		for(long k:s.selected){int row=(int)(k>>>32),col=(int)k;int idx=rows?row:col;if(count<0&&idx>=at+count&&idx<at)continue;if(idx>=at){if(rows)row+=count;else col+=count;}sel.add(key(row,col));}
		s.cells.clear();s.cells.putAll(moved);s.selected.clear();s.selected.addAll(sel);s.cellsDirty=true;
	}
	private int visibleRows(ListState s){return Math.max(1,(s.view[2]-s.view[0])/Math.max(1,s.cellHeight));}
	private void sync(ListState s) throws M68kCpuException {
		syncCells(s);int r=memory.readLong(s.handle);memory.writeWord(r+72,0);memory.writeWord(r+74,0);memory.writeWord(r+76,s.rows);memory.writeWord(r+78,s.columns);
		memory.writeWord(r,s.view[0]);memory.writeWord(r+2,s.view[1]);memory.writeWord(r+4,s.view[2]);memory.writeWord(r+6,s.view[3]);
		syncScroll(s);
	}
	/** The record's visible rect (the cells in view) follows every scroll: applications may hit-test by walking visible.top..bottom
	 *  with LRect, so a stale visible.top of 0 after scrolling made clicks on the lower rows select nothing. */
	private void drawScrollBar(ListState s) throws M68kCpuException {if(scrollBar(s)!=0&&s.drawIt)tb.controls.draw(s.vScroll);}
	/**
	 * The list's scroll bar, or 0 once it is gone: a program may dispose of it (KillControls, DisposeControl) and the
	 * Handle then goes to the next control made. iTunes' LActivate redrew its search field that way - unclipped, with the
	 * edit text frame its own drawing clips away.
	 */
	private int scrollBar(ListState s){
		if(s.vScroll!=0&&(s.vScrollControl==null||tb.controls.controls.get(s.vScroll)!=s.vScrollControl)){s.vScroll=0;s.vScrollControl=null;}
		if(s.hasVScroll){ // a program may replace the bar in ListRec.vScroll by its own (iTunes' 'Aqua Scroll Bar' CDEF): the List Manager then drives that one
			int v;try{int r=memory.readLong(s.handle);v=r==0?0:memory.readLong(r+28);}catch(M68kCpuException e){return s.vScroll;}
			if(v!=s.recordVScroll){
				ControlManager68k.Control c=v==0?null:tb.controls.controls.get(v);
				s.recordVScroll=v;if(c!=null){s.vScroll=v;s.vScrollControl=c;}
			}
		}
		return s.vScroll;
	}
	private void syncScroll(ListState s) throws M68kCpuException {
		int r=memory.readLong(s.handle);
		if(r!=0){memory.writeWord(r+20,s.scrollRow);memory.writeWord(r+22,s.scrollColumn);memory.writeWord(r+24,s.scrollRow+visibleRows(s));memory.writeWord(r+26,s.scrollColumn+Math.max(1,(s.view[3]-s.view[1])/Math.max(1,s.cellWidth)));}
		if(scrollBar(s)==0)return;int d=tb.controls.data(s.vScroll);if(d==0)return;
		int max=Math.max(0,s.rows-visibleRows(s));memory.writeWord(d+20,0);memory.writeWord(d+22,max);memory.writeWord(d+18,Math.min(max,s.scrollRow));
		tb.controls.draw(s.vScroll);
	}
	private int[] cellRect(ListState s,int row,int col){int top=s.view[0]+(row-s.scrollRow)*s.cellHeight,left=s.view[1]+(col-s.scrollColumn)*s.cellWidth;return new int[]{top,left,top+s.cellHeight,left+s.cellWidth};}

	/** The List Manager calls (**lHandle).listDefProc each time: a definition that replaced itself there (the CTB tools' LDEF
	 *  stub puts the System's CTB LDEF in its place) is the one used from then on. */
	private void followDefProc(ListState s) throws M68kCpuException {
		int rec=memory.readLong(s.handle);if(rec==0)return;int current=memory.readLong(rec+64);
		if(current==s.ldef||current==0)return;
		ResourceFile68k.Resource ctb=tb.resources.find(0x4c444546,CTB_LDEF);
		if(ctb!=null&&current==tb.resources.handleFor(ctb)){s.ldef=0;s.ctbIcons=true;}else s.ldef=current;
	}
	/**
	 * LDoDraw(TRUE) only turns drawing back on - the ROM does not redraw the list there. A program's LDEF is not called
	 * then: the view is invalidated and drawn at the next update. MacWeb's hotlist editor does LDoDraw(FALSE), LAddRow,
	 * LDoDraw(TRUE), LSetCell for every entry; its LDEF reads the entry pointer from the cell data whatever the cell's length,
	 * and the drawing right at LDoDraw read 4 bytes past the cells of the empty new row and followed them (a crash, now and
	 * then). The native drawing (no LDEF) keeps drawing at once, as before.
	 */
	private void drawnLater(ListState s) throws M68kCpuException {
		followDefProc(s);
		if(s.ldef==0){draw(s);return;}
		syncCells(s); // the cells are in the guest record as they always are on a Mac (FreeHand 4's palette reads cellArray)
		if(tb.windows.isWindow(s.window))tb.windows.invalidate(s.window,s.view[0],s.view[1],s.view[2],s.view[3]);
	}
	private void draw(ListState s) throws M68kCpuException {
		if(!s.drawIt)return;
		followDefProc(s);
		if(s.ldef!=0){drawGuest(s);return;}
		Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
		try{g.setClip(s.view[1],s.view[0],s.view[3]-s.view[1],s.view[2]-s.view[0]);g.setColor(Color.WHITE);g.fillRect(s.view[1],s.view[0],s.view[3]-s.view[1],s.view[2]-s.view[0]);
			Font font=cellFont(s);g.setFont(font);FontMetrics fm=Fonts68k.metrics(font);
			for(int row=s.scrollRow;row<s.rows&&(row-s.scrollRow)*s.cellHeight<s.view[2]-s.view[0];row++)for(int col=s.scrollColumn;col<s.columns;col++)paintCell(g,s,row,col,fm);
		}finally{g.dispose();}
	}
	private void drawCell(ListState s,int row,int col) throws M68kCpuException {
		if(!s.drawIt)return;
		followDefProc(s);
		if(s.ldef!=0){drawGuestCell(s,row,col);return;}
		Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
		try{g.setClip(s.view[1],s.view[0],s.view[3]-s.view[1],s.view[2]-s.view[0]);Font font=cellFont(s);g.setFont(font);paintCell(g,s,row,col,Fonts68k.metrics(font));}finally{g.dispose();}
	}
	private Font cellFont(ListState s) throws M68kCpuException {return tb.windows.isWindow(s.window)?Fonts68k.font(memory.readWord(s.window+68),Math.max(9,memory.readWord(s.window+74)),memory.readWord(s.window+70)):Fonts68k.font(0,12,0);}
	private void paintCell(Graphics2D g,ListState s,int row,int col,FontMetrics fm){
		int[] r=cellRect(s,row,col);boolean sel=s.selected.contains(key(row,col));
		if(s.ctbIcons){paintCtbCell(g,s,r,sel,s.cells.get(key(row,col)),fm);return;}
		if(sel&&noNilHilite(s)){byte[] v=s.cells.get(key(row,col));if(v==null||v.length==0)sel=false;} // lNoNilHilite: an empty cell is not highlighted
		g.setColor(sel?(s.active?SELECTION:new Color(0xe0,0xe0,0xe0)):Color.WHITE);g.fillRect(r[1],r[0],r[3]-r[1],r[2]-r[0]);
		int x=r[1]+4;boolean dimmed=isDimmed(s,row);
		java.awt.image.BufferedImage image=s.iconImages!=null&&col==0&&row<s.iconImages.length?s.iconImages[row]:null;
		if(image!=null){int size=Math.min(16,Math.max(8,s.cellHeight));paintImage(g,image,r[1]+2,r[0]+(s.cellHeight-size)/2,size,dimmed);x=r[1]+22;}
		else if(s.icons!=null&&col==0&&row<s.icons.length&&(s.icons[row]&0xff)!=ICON_NONE){paintIcon(g,s.icons[row]&0xff,r[1]+3,r[0]+(s.cellHeight-12)/2);x=r[1]+22;}
		byte[] v=s.cells.get(key(row,col));if(v==null)return;g.setColor(dimmed?new Color(0x90,0x90,0x90):Color.BLACK);
		int baseline=r[0]+(s.cellHeight-fm.getHeight())/2+fm.getAscent();
		String[] details=s.details!=null&&col==0&&row<s.details.length?s.details[row]:null;
		if(details==null||s.detailWidths==null){Fonts68k.drawString(g,fm,tb.decode(v),x,baseline);return;}
		// detail columns from the right edge; the name gets what is left and is clipped to it
		int total=0;for(int w:s.detailWidths)total+=Math.abs(w);
		java.awt.Shape clip=g.getClip();
		g.clipRect(r[1],r[0],Math.max(0,r[3]-total-r[1]-4),r[2]-r[0]);Fonts68k.drawString(g,fm,tb.decode(v),x,baseline);g.setClip(clip);
		int left=r[3]-total;
		for(int k=0;k<s.detailWidths.length&&k<details.length;k++){
			int w=Math.abs(s.detailWidths[k]);String text=details[k]==null?"":details[k];
			g.clipRect(left,r[0],w,r[2]-r[0]);
			int tx=s.detailWidths[k]<0?left+w-6-fm.stringWidth(text):left+6;
			Fonts68k.drawString(g,fm,text,tx,baseline);g.setClip(clip);left+=w;
		}
	}

	/** A cell of the Communications Toolbox's LDEF: 'ICN#' data (icon and mask, 256 bytes) and a Pascal name after it; the icon
	 *  centred at the top, the name centred below it, highlighted when selected (the icon darkened). Other data: a text row. */
	private void paintCtbCell(Graphics2D g,ListState s,int[] r,boolean sel,byte[] v,FontMetrics fm){
		g.setColor(Color.WHITE);g.fillRect(r[1],r[0],r[3]-r[1],r[2]-r[0]);if(v==null)return;
		if(v.length<257){g.setColor(sel?SELECTION:Color.WHITE);g.fillRect(r[1],r[0],r[3]-r[1],r[2]-r[0]);g.setColor(Color.BLACK);Fonts68k.drawString(g,fm,tb.decode(v),r[1]+4,r[0]+(r[2]-r[0]-fm.getHeight())/2+fm.getAscent());return;}
		java.awt.image.BufferedImage icon=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<32;y++)for(int x=0;x<32;x++){int bit=0x80>>(x&7);boolean ink=(v[y*4+x/8]&bit)!=0,mask=(v[128+y*4+x/8]&bit)!=0;
			if(ink)icon.setRGB(x,y,0xff000000);else if(mask)icon.setRGB(x,y,sel?0xff808080:0xffffffff);}
		int cx=(r[1]+r[3])/2;g.drawImage(icon,cx-16,r[0]+2,null);
		int n=Math.min(v[256]&0xff,v.length-257);String name=tb.decode(java.util.Arrays.copyOfRange(v,257,257+n));
		int w=fm.stringWidth(name),base=r[0]+2+32+2+fm.getAscent();
		if(sel){g.setColor(s.active?SELECTION:new Color(0xe0,0xe0,0xe0));g.fillRect(cx-w/2-2,base-fm.getAscent(),w+4,fm.getHeight());}
		g.setColor(Color.BLACK);Fonts68k.drawString(g,fm,name,cx-w/2,base);
	}

	private static boolean isDimmed(ListState s,int row){return s.icons!=null&&row>=0&&row<s.icons.length&&(s.icons[row]&ICON_DIMMED)!=0;}

	// ------------------------------------------------------------------ small icons (12x12, drawn at x,y)

	private static final Color FOLDER_FILL=new Color(0x9f,0xc4,0xf0),FOLDER_EDGE=new Color(0x3c,0x6b,0xa8),FOLDER_TAB=new Color(0x7f,0xab,0xe0);
	private static final Color DOC_EDGE=new Color(0x70,0x70,0x70),DOC_LINE=new Color(0xa8,0xa8,0xa8),APP_FILL=new Color(0xf2,0xb6,0x5c),APP_EDGE=new Color(0x9c,0x64,0x1c);
	private static final Color DISK_FILL=new Color(0xd4,0xd4,0xd4),DISK_EDGE=new Color(0x60,0x60,0x60),DISK_LIGHT=new Color(0x3c,0xb0,0x50);
	/** Paints a Finder icon scaled to {@code size} pixels square (the 32x32 'icl8' shown as the small icon a System 7 list would use). */
	static void paintImage(Graphics2D g,java.awt.image.BufferedImage image,int x,int y,int size,boolean dimmed){
		Object interpolation=g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);java.awt.Composite composite=g.getComposite();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		if(dimmed)g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER,0.4f));
		try{g.drawImage(image,x,y,size,size,null);}
		finally{g.setComposite(composite);g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,interpolation==null?java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR:interpolation);}
	}
	/** Paints one of the {@code ICON_*} pictograms with its top-left corner at (x,y); 12 pixels square. */
	static void paintIcon(Graphics2D g,int icon,int x,int y){
		Object aa=g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		try{
			switch(icon){
				case ICON_FOLDER:
					g.setColor(FOLDER_TAB);g.fillRoundRect(x,y+1,6,4,2,2);
					g.setColor(FOLDER_FILL);g.fillRoundRect(x,y+3,12,8,2,2);
					g.setColor(FOLDER_EDGE);g.drawRoundRect(x,y+3,11,7,2,2);g.drawLine(x+1,y+1,x+5,y+1);g.drawLine(x,y+2,x,y+3);g.drawLine(x+6,y+2,x+6,y+3);
					break;
				case ICON_DOCUMENT:
					g.setColor(Color.WHITE);g.fillRect(x+1,y,9,12);
					g.setColor(DOC_EDGE);g.drawLine(x+1,y,x+7,y);g.drawLine(x+1,y,x+1,y+11);g.drawLine(x+1,y+11,x+10,y+11);g.drawLine(x+10,y+3,x+10,y+11);g.drawLine(x+7,y,x+10,y+3);g.drawLine(x+7,y,x+7,y+3);g.drawLine(x+7,y+3,x+10,y+3);
					g.setColor(DOC_LINE);for(int i=0;i<3;i++)g.drawLine(x+3,y+5+i*2,x+8,y+5+i*2);
					break;
				case ICON_APPLICATION:
					g.setColor(APP_FILL);g.fillRoundRect(x,y,12,12,4,4);
					g.setColor(APP_EDGE);g.drawRoundRect(x,y,11,11,4,4);
					g.setColor(Color.WHITE);g.fillPolygon(new int[]{x+3,x+6,x+9,x+6},new int[]{y+6,y+2,y+6,y+10},4);
					break;
				case ICON_DISK:
					g.setColor(DISK_FILL);g.fillRoundRect(x,y+2,12,8,2,2);
					g.setColor(DISK_EDGE);g.drawRoundRect(x,y+2,11,7,2,2);g.drawLine(x+2,y+7,x+7,y+7);
					g.setColor(DISK_LIGHT);g.fillRect(x+9,y+7,2,1);
					break;
				default:break;
			}
		}finally{g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,aa==null?java.awt.RenderingHints.VALUE_ANTIALIAS_DEFAULT:aa);}
	}

	/**
	 * Calls (**lHandle).lClikLoop; true when there is none. The ROM tests only the condition codes it returns with ("JSR (A0)" /
	 * "BEQ clickExit", ListMgrPACK.a): CodeWarrior 5's clikLoop is a pascal Boolean function that stores its answer above its
	 * return address (in the ROM's frame) and leaves D0 as it happened to be - read from D0, every click "aborted" and a double
	 * click never opened a file. A routine that sets D0 sets Z with it, so Z clear means go on tracking.
	 */
	private boolean clikLoop(int listRec) throws M68kException {
		int loop=listRec==0?0:memory.readLong(listRec+52);if(loop==0)return true;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;boolean more;
		try{cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.callPascal(loop,0);more=(cpu.sr&M68kCpu.FLAG_Z)==0;} // the word a Pascal function writes its result into
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
		return more;
	}
	private boolean click(ListState s,int h,int v,int modifiers) throws M68kException {
		if(scrollBar(s)!=0&&tb.controls.partAt(s.vScroll,h,v)!=0){
			int part=tb.controls.track(s.vScroll,h,v,0);
			if(part!=0){int max=Math.max(0,s.rows-visibleRows(s));int delta=part==ControlManager68k.IN_UP?-1:part==ControlManager68k.IN_DOWN?1:part==ControlManager68k.IN_PAGE_UP?-visibleRows(s):part==ControlManager68k.IN_PAGE_DOWN?visibleRows(s):0;
				if(part==ControlManager68k.IN_THUMB)s.scrollRow=tb.controls.value(s.vScroll);else s.scrollRow=Math.max(0,Math.min(max,s.scrollRow+delta));syncScroll(s);draw(s);}
			return false;
		}
		if(h<s.view[1]||h>=s.view[3]||v<s.view[0]||v>=s.view[2])return false;
		int row=s.scrollRow+(v-s.view[0])/Math.max(1,s.cellHeight),col=s.scrollColumn+(h-s.view[1])/Math.max(1,s.cellWidth);
		if(row>=s.rows||col>=s.columns||isDimmed(s,row)){return false;}
		long now=System.currentTimeMillis();boolean dbl=now-s.lastClickTime<500&&row==s.lastClickRow&&col==s.lastClickColumn;
		// The record's clikTime, clikLoc, mouseLoc and lastClick: installers with their own LDEF read the clicked cell and
		// point straight from the record (VISE's Custom Install check boxes toggle on lastClick + clikLoc).
		int listRec=memory.readLong(s.handle);
		if(listRec!=0){memory.writeLong(listRec+40,tb.tickCount());memory.writeWord(listRec+44,v);memory.writeWord(listRec+46,h);memory.writeWord(listRec+48,v);memory.writeWord(listRec+50,h);memory.writeWord(listRec+56,row);memory.writeWord(listRec+58,col);}
		int anchorRow=s.lastClickRow,anchorColumn=s.lastClickColumn;
		s.lastClickTime=now;s.lastClickRow=row;s.lastClickColumn=col;
		long k=key(row,col);
		int rec=memory.readLong(s.handle),selFlags=rec==0?0:memory.readByte(rec+36);
		boolean onlyOne=(selFlags&L_ONLY_ONE)!=0,shift=!onlyOne&&(modifiers&0x200)!=0,command=!onlyOne&&!shift&&(modifiers&0x100)!=0&&(selFlags&L_NO_DISJOINT)==0; // lNoDisjoint: Command-click is a plain click
		boolean deselect=shift&&(selFlags&L_USE_SENSE)!=0&&s.selected.contains(k); // lUseSense: Shift-click on a selected cell takes the range out
		if(shift&&(selFlags&L_NO_EXTEND)!=0&&!s.selected.contains(key(anchorRow,anchorColumn)))shift=false;
		if(!shift&&!command){s.selected.clear();s.selected.add(k);}
		else if(command){if(s.selected.contains(k))s.selected.remove(k);else s.selected.add(k);}
		else if(s.selected.isEmpty()){s.selected.add(k);}
		else{
			// Shift-click (IM More Macintosh Toolbox 4-26): the selection grows to the smallest rectangle holding the selected
			// cells and the clicked one, so selecting a cell and Shift-clicking two to its right selects three; lNoExtend uses
			// only the last clicked cell as the other corner. Before this the clicked cell was just added.
			int top=row,left=col,bottom=row,right=col;
			if((selFlags&L_NO_EXTEND)!=0){top=Math.min(top,anchorRow);bottom=Math.max(bottom,anchorRow);left=Math.min(left,anchorColumn);right=Math.max(right,anchorColumn);}
			else for(long c:s.selected){int r=(int)(c>>32),cl=(int)c;top=Math.min(top,r);bottom=Math.max(bottom,r);left=Math.min(left,cl);right=Math.max(right,cl);}
			if((selFlags&L_NO_RECT)!=0&&s.columns>1){ // in reading order rather than a rectangle
				int from=top*s.columns+left,to=bottom*s.columns+right;
				for(int i=from;i<=to;i++){long c=key(i/s.columns,i%s.columns);if(!s.selected.contains(c))s.selected.add(c);}
			}else if(deselect){int aTop=Math.min(row,anchorRow<0?row:anchorRow),aBottom=Math.max(row,anchorRow<0?row:anchorRow),aLeft=Math.min(col,anchorColumn<0?col:anchorColumn),aRight=Math.max(col,anchorColumn<0?col:anchorColumn);
				for(int r=aTop;r<=aBottom;r++)for(int cl=aLeft;cl<=aRight;cl++)s.selected.remove(key(r,cl));
			}else for(int r=top;r<=bottom;r++)for(int cl=left;cl<=right;cl++){long c=key(r,cl);if(!s.selected.contains(c))s.selected.add(c);}
			s.selected.removeIf(c->isDimmed(s,(int)(c>>32)));
		}
		writeSelectionBits(s);draw(s);final int anchorDrag=row; // lExtendDrag: dragging selects every cell from the clicked one
		// The record's lClikLoop runs at least once and then while the button is down, as the ROM's tracking loop calls it;
		// FALSE stops LClick. VISE installers toggle their Custom Install check boxes in it (it hit-tests clikLoc itself).
		if(!clikLoop(listRec))return false;
		// a clikLoop answering false ends the tracking, but LClick still returns the double click it found (ROM ListMgrPACK.a:
		// "BEQ clickExit" keeps the result) - CodeWarrior 5's project window opens a file on that, its clikLoop returns false
		while(tb.host().isMouseDown()){if(!clikLoop(listRec))return dbl;int[] m=tb.events.localMouse();int r2=s.scrollRow+(m[1]-s.view[0])/Math.max(1,s.cellHeight);if(r2>=0&&r2<s.rows&&r2!=row&&(modifiers&0x300)==0){row=r2;if((selFlags&L_EXTEND_DRAG)!=0&&(selFlags&L_ONLY_ONE)==0){int from=Math.min(r2,anchorDrag),to=Math.max(r2,anchorDrag);s.selected.clear();for(int rr=from;rr<=to;rr++)s.selected.add(key(rr,col));}else{s.selected.clear();s.selected.add(key(row,col));}s.lastClickRow=row;if(listRec!=0)memory.writeWord(listRec+56,row);writeSelectionBits(s);draw(s);}tb.events.idle(1);}
		return dbl;
	}
}

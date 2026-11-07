import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog Manager for hosted applications: DLOG/DITL/ALRT resources, modal and
 * modeless dialogs, alerts, item text, edit fields (TextEdit), filter
 * procedures and user items, all drawn natively into the dialog window.
 */
final class DialogManager68k {
	static final int DIALOG_SIZE=170;
	private static final int PN_VIS=66,ITEMS=156,TEXT_H=160,EDIT_FIELD=164,EDIT_OPEN=166,DEFAULT_ITEM=168;
	private static final int USER_ITEM=0,CTRL_ITEM=4,BTN_CTRL=4,CHK_CTRL=5,RAD_CTRL=6,RES_CTRL=7,STAT_TEXT=8,EDIT_TEXT=16,ICON_ITEM=32,PIC_ITEM=64,ITEM_DISABLE=128;

	final class Item {int address,type,length;int[] rect;int handle;int teHandle;boolean hidden;boolean colorIcon;
		/** The item's 'ictb' text style: {flags, font, face, size, rgb}, or null. */
		int[] textStyle;
		/** TextBox justification of a static text item ('dftb': teFlushDefault 0, teCenter 1, teFlushRight -1, teFlushLeft -2). */
		int justification;
		/** The control GetDialogItemAsControl made for this static text, edit text, icon, picture or user item of an embedding dialog, or 0 ({@link ItemControl}; a user pane for a user item). */
		int control;}
	interface NativeEvents {int unhandled(EventManager68k.Event e,int localH,int localV) throws M68kException;}
	interface NullEvents {int idle() throws M68kException;}
	final class Dialog {
		final int window;int items;final List<Item> list=new ArrayList<Item>();int editItem=-1;int defaultItem=1,cancelItem=0;
		/** The default button gets the bold ring: alerts, SetDialogDefaultItem, Standard File. A plain dialog's item 1 answers Return
		 *  without one (MacPaint's About box on a Mac shows a plain OK button). */
		boolean ringDefault;boolean tracksCursor,cursorOverText;int alertKind=-1;
		/** Host-drawn user items (Standard File's file list and volume name), by item number. */
		final Map<Integer,Runnable> nativeItems=new HashMap<Integer,Runnable>();
		/** Host drawing that comes after every item, where DrawControls draws a control that is no item (Standard File's folder
		 *  pop-up in the old dialogs covers the "ignored" prompt, item 10, that the package sets above the file list). */
		Runnable afterItems;
		/** Sees mouse-downs outside every item and key-downs before the edit field (Standard File's folder pop-up and its
		 *  keyboard navigation); a positive result is returned from {@link #runModal} as a pseudo item. */
		NativeEvents nativeEvents;
		/** Runs on every null event of {@link #runModal} (the Standard File Package's sfHookNullEvent call to the program's dialog
		 *  hook); a positive result other than the hook's own null-event code ends ModalDialog with that pseudo item. */
		NullEvents nullEvents;
		/** kDialogFlagsUseControlHierarchy ('dlgx' or NewFeaturesDialog): later items inside an item's rect are embedded in it. */
		boolean embedding;
		/** The item list Handle's data address and size when {@link #list} last matched it (see {@link DialogManager68k#sync}). */
		int syncedData,syncedSize;
		/** While {@link DialogManager68k#parseItems} makes the items: a control's CDEF may call back into the Dialog Manager then
		 *  (PageMaker 6's pop-up CDEF does), and a {@link DialogManager68k#sync} of the half-made list kept the records after it
		 *  with no control Handle. */
		boolean parsing;
		/** Items whose records left the list (their controls stay in the window): a record that comes back finds them again. */
		final List<Item> detached=new ArrayList<Item>();
		/** The item an unattended program's dialog ends with instead of Return (0: Return, its default button). */
		int unattendedItem;
		Dialog(int window){this.window=window;}
	}
	/**
	 * The first enabled push button inside the dialog's port that is none of {@code standard}: the action a program added to a
	 * Standard File dialog (Compact Pro's "Extract" in its destination folder chooser), or 0.
	 */
	int addedButton(Dialog dialog,java.util.Set<Integer> standard) throws M68kCpuException {
		int top=(short)memory.readWord(dialog.window+16),left=(short)memory.readWord(dialog.window+18),bottom=(short)memory.readWord(dialog.window+20),right=(short)memory.readWord(dialog.window+22);
		for(int i=0;i<dialog.list.size();i++){Item item=dialog.list.get(i);
			if((item.type&0x7f)!=BTN_CTRL||(item.type&ITEM_DISABLE)!=0||!shows(item)||standard.contains(i+1))continue;
			if(item.rect[0]>=top&&item.rect[1]>=left&&item.rect[2]<=bottom&&item.rect[3]<=right)return i+1;
		}
		return 0;
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	final Map<Integer,Dialog> dialogs=new HashMap<Integer,Dialog>();
	private final String[] paramText={"","","",""};
	/** DAStrings ($AA0): the four ParamText strings as StringHandles, which programs (HyperCard) read to substitute ^0-^3 themselves. */
	static final int DA_STRINGS=0xaa0;
	private final int[] paramTextHandles=new int[4];
	private int scratchEvent,scratchItem;
	/** The event that produced the last item hit returned by {@link #runModal} (mouse position for list clicks). */
	EventManager68k.Event lastEvent;
	private int stdFilterGate;
	private int alertStage;

	DialogManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x97b:return "InitDialogs";case 0x97d:return "NewDialog";case 0x97c:return "GetNewDialog";case 0xa4b:return "NewColorDialog";case 0x982:return "CloseDialog";case 0x983:return "DisposeDialog";
			case 0x991:return "ModalDialog";case 0x97f:return "IsDialogEvent";case 0x980:return "DialogSelect";case 0x981:return "DrawDialog";case 0x978:return "UpdateDialog";case 0x984:return "FindDialogItem";
			case 0x985:return "Alert";case 0x986:return "StopAlert";case 0x987:return "NoteAlert";case 0x988:return "CautionAlert";case 0x989:return "CouldAlert";case 0x98a:return "FreeAlert";case 0x979:return "CouldDialog";case 0x97a:return "FreeDialog";
			case 0x98c:return "ErrorSound";case 0x98b:return "ParamText";case 0x98d:return "GetDialogItem";case 0x98e:return "SetDialogItem";case 0x990:return "GetDialogItemText";case 0x98f:return "SetDialogItemText";
			case 0x97e:return "SelectDialogItemText";case 0x827:return "HideDialogItem";case 0x828:return "ShowDialogItem";case 0xa68:return "DialogDispatch";
			default:return null;
		}
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		for(Dialog d:dialogs.values())sync(d);
		switch(trap){
			case 0x97b:{int proc=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return true;}
			case 0x97d:case 0xa4b:return newDialog(cpu,trap==0xa4b);
			case 0x97c:return getNewDialog(cpu);
			case 0x982:case 0x983:{int d=pop(cpu);dispose(d);return true;}
			case 0x991:return modalDialog(cpu);
			case 0x97f:{int event=pop(cpu);tb.writeBoolean(cpu.a[7],isDialogEvent(event));return true;}
			case 0x980:return dialogSelect(cpu);
			case 0x981:{int d=pop(cpu);draw(d);return true;}
			case 0x978:{int rgn=memory.readLong(cpu.a[7]),d=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;draw(d);return true;}
			case 0x984:{int pt=memory.readLong(cpu.a[7]),d=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeWord(cpu.a[7],findItem(d,(short)pt,(short)(pt>>16),false)-1);return true;}
			case 0x985:case 0x986:case 0x987:case 0x988:{int filter=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;memory.writeWord(cpu.a[7],alert(cpu,id,filter,trap-0x985));return true;}
			case 0x989:case 0x98a:case 0x979:case 0x97a:cpu.a[7]+=2;return true;
			case 0x98c:{beeper=pop(cpu);beeperSet=true;memory.writeLong(DA_BEEPER,beeper);return true;} // ErrorSound(soundProc): what alerts sound with (NIL = silent); it makes no sound itself
			// ParamText: a NIL string leaves that ^n text as it was (PageMaker's installer sets ^0, ^1, ^2 by three calls with NILs for the rest)
			case 0x98b:{for(int i=3;i>=0;i--){int s=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(s==0)continue;paramText[i]=tb.readPascal(s);
					byte[] bytes=tb.readPascalBytes(s);if(paramTextHandles[i]==0)paramTextHandles[i]=tb.api.createHandle(bytes);else tb.api.loadHandle(paramTextHandles[i],bytes);
					memory.writeLong(DA_STRINGS+4*i,paramTextHandles[i]);}
				return true;}
			case 0x98d:{int box=memory.readLong(cpu.a[7]),itemOut=memory.readLong(cpu.a[7]+4),typeOut=memory.readLong(cpu.a[7]+8),number=(short)memory.readWord(cpu.a[7]+12),d=memory.readLong(cpu.a[7]+14);cpu.a[7]+=18;
				Item item=item(d,number);if(item!=null){if(typeOut!=0)memory.writeWord(typeOut,item.type);if(itemOut!=0)memory.writeLong(itemOut,item.handle);if(box!=0)writeRect(box,item.rect);}return true;}
			case 0x98e:{int box=memory.readLong(cpu.a[7]),handle=memory.readLong(cpu.a[7]+4),type=(short)memory.readWord(cpu.a[7]+8),number=(short)memory.readWord(cpu.a[7]+10),d=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;
				Item item=item(d,number);if(item!=null){if(handle!=item.handle){int size=handle==0?0:tb.api.handleSize(handle);item.colorIcon=size!=128&&size>=82;} // an 'ICON' is 128 bytes; a CIcon record is otherwise
					item.handle=handle;item.type=type&0xff;memory.writeLong(item.address,handle);memory.writeByte(item.address+12,type);if(box!=0){item.rect=readRect(box);memory.copy(box,item.address+4,8);
					// the item being edited keeps its TE where it is: SetDItem only writes the item list, textH takes an item's rect when that
					// item becomes current. HyperCard offsets textH's destRect and viewRect itself after it (ask with a long prompt: moved
					// twice, the default text below its box). The others have a TE of their own here, standing in for the drawn text
					if(item.teHandle!=0&&!beingEdited(d,number))tb.textEdit.setRects(item.teHandle,item.rect);
					item.hidden=item.rect[1]>HIDDEN_LEFT;}}return true;} // hidden is where the rect is, as for the Dialog Manager (see ShowDialogItem)
			case 0x990:{int text=memory.readLong(cpu.a[7]),handle=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(text!=0){byte[] data=handle==0?null:tb.api.handleContents(handle);if(data==null)data=new byte[0];int n=Math.min(255,data.length);memory.writeByte(text,n);memory.load(text+1,java.util.Arrays.copyOf(data,n));}return true;}
			case 0x98f:{int text=memory.readLong(cpu.a[7]),handle=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;setItemText(handle,text);return true;}
			case 0x97e:{int end=(short)memory.readWord(cpu.a[7]),start=(short)memory.readWord(cpu.a[7]+2),number=(short)memory.readWord(cpu.a[7]+4),d=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;selectText(d,number,start,end);return true;}
			case 0x827:case 0x828:{int number=(short)memory.readWord(cpu.a[7]),d=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Item item=item(d,number);boolean hide=trap==0x827;
				// an item made a control is shown and hidden as one (HideControl may have hidden it while the item stayed shown)
				if(item!=null&&item.control!=0){int cd=tb.controls.data(item.control);boolean was=cd!=0&&memory.readByte(cd+16)!=0;if(cd!=0)memory.writeByte(cd+16,hide?0:0xff);
					if(!hide&&!was&&!item.hidden){WindowManager68k.Window shown=tb.windows.window(d);if(shown!=null&&shown.visible)drawItem(dialogs.get(d),item);return true;}}
				if(item==null||item.hidden==hide)return true;
				int kind=item.type&0x7f;boolean control=kind>=CTRL_ITEM&&kind<=RES_CTRL&&item.handle!=0;
				// HideDialogItem erases the item and invalidates its rectangle: the items it covered are drawn again at the next
				// update (System's Twilight shows its buttons, then hides the title picture laid over them)
				if(hide){eraseItem(d,item);tb.windows.invalidate(d,item.rect[0]-4,item.rect[1]-4,item.rect[2]+4,item.rect[3]+4);if(control){int cd=tb.controls.data(item.handle);if(cd!=0)memory.writeByte(cd+16,0);}}
				item.hidden=hide;
				// Like the real Dialog Manager, hidden items are moved 16384 pixels to the right (and back) - by where the rect is, not
				// by a flag: HideDItem moves it only when its left is below 8192, ShowDItem only when it is above. CreditsDisplay hides
				// its picture item, SetDItems the next PICT with the rect it saved (on screen) and shows it: shifted once more it went
				// to -16384, the dialog blank and every click "item 0", so the XFCN never ended.
				int shift=hide?(item.rect[1]<HIDDEN_LEFT?16384:0):(item.rect[1]>HIDDEN_LEFT?-16384:0);item.rect[1]+=shift;item.rect[3]+=shift;memory.writeWord(item.address+6,item.rect[1]);memory.writeWord(item.address+10,item.rect[3]);
				if(item.teHandle!=0)tb.textEdit.setRects(item.teHandle,item.rect);
				if(!hide){if(control){int cd=tb.controls.data(item.handle);if(cd!=0)memory.writeByte(cd+16,0xff);}drawItem(dialogs.get(d),item);}
				return true;}
			case 0xa68:return dialogDispatch(cpu);
			default:return false;
		}
	}

	private int pop(M68kCpu cpu) throws M68kCpuException {int v=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return v;}
	/** An item whose rect's left lies beyond this is hidden (HideDItem adds 16384 to it). */
	private static final int HIDDEN_LEFT=8192;

	// ------------------------------------------------------------------ creation

	/** NewDialog/GetNewDialog leave the caller's port current (NewWindow inside them does not): Apple's ShowList XFCN 1.1 does
	 *  GetPort right after NewDialog and centres the dialog on that port as HyperCard's card window - with thePort = the dialog
	 *  it centred on itself and went to the top left of the screen (Wings -翼の軌跡- item list). */
	private boolean newDialog(M68kCpu cpu,boolean color) throws M68kException {
		int sp=cpu.a[7],items=memory.readLong(sp),refCon=memory.readLong(sp+4);boolean goAway=memory.readByte(sp+8)!=0;int behind=memory.readLong(sp+10),procId=(short)memory.readWord(sp+14);
		boolean visible=memory.readByte(sp+16)!=0;int title=memory.readLong(sp+18),bounds=memory.readLong(sp+22),storage=memory.readLong(sp+26);cpu.a[7]+=30;
		int[] b=readRect(bounds);
		if(Mac68kApi.TRACE)System.err.println("[68K] NewDialog bounds "+java.util.Arrays.toString(b)+" procID "+procId+" visible "+visible);
		int savedPort=tb.api.currentQuickDrawPort();
		// a NIL item list is a dialog with no items: Apple's PrintContainer XCMD opens its "Now printing…" window that way and draws
		// the text itself (a NIL dialog made it report "out of memory" and never print)
		if(items==0)items=tb.api.createHandle(new byte[]{(byte)0xff,(byte)0xff});
		memory.writeLong(cpu.a[7],create(storage,b,tb.readPascal(title),visible,procId,behind,goAway,refCon,items,true,null,color));setPort(savedPort);return true;
	}

	private boolean getNewDialog(M68kCpu cpu) throws M68kException {
		int behind=memory.readLong(cpu.a[7]),storage=memory.readLong(cpu.a[7]+4),id=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;
		int savedPort=tb.api.currentQuickDrawPort();
		memory.writeLong(cpu.a[7],fromResource(id,storage,behind,false));setPort(savedPort);return true;
	}

	/** Rewrites a DITL (and the dialog's bounds) before the dialog is built: the Standard File Package's wide layout. */
	interface Layout {byte[] rewrite(byte[] ditl,int[] bounds);}

	/** GetNewDialog: builds a dialog from DLOG/DITL resources (0 when missing); {@code forceInvisible} keeps it hidden. */
	int fromResource(int id,int storage,int behind,boolean forceInvisible) throws M68kException {return fromResource(id,storage,behind,forceInvisible,null);}
	/** As above; a non-null {@code layout} rewrites the item list and bounds before the dialog is positioned. */
	int fromResource(int id,int storage,int behind,boolean forceInvisible,Layout layout) throws M68kException {
		ResourceFile68k.Resource dlog=tb.resources.find(0x444c4f47,id);
		if(dlog==null)return 0;
		byte[] d=tb.resources.currentData(dlog);
		if(d.length<21)return 0;
		int[] bounds={(short)MacApp68k.u2(d,0),(short)MacApp68k.u2(d,2),(short)MacApp68k.u2(d,4),(short)MacApp68k.u2(d,6)};
		int procId=(short)MacApp68k.u2(d,8);boolean visible=MacApp68k.u2(d,10)!=0,goAway=MacApp68k.u2(d,12)!=0;int refCon=MacApp68k.u4(d,14);int ditlId=(short)MacApp68k.u2(d,18);
		int n=Math.min(d[20]&0xff,d.length-21);String title=tb.decode(java.util.Arrays.copyOfRange(d,21,21+n));
		int positioning=21+n+((21+n)&1)+1<d.length?MacApp68k.u2(d,21+n+((n+1)&1)):0;
		ResourceFile68k.Resource ditl=tb.resources.find(0x4449544c,ditlId);
		byte[] itemList=ditl==null?new byte[]{(byte)0xff,(byte)0xff}:tb.resources.currentData(ditl);
		if(layout!=null&&ditl!=null)itemList=layout.rewrite(itemList,bounds);
		int items=tb.api.createHandle(itemList);
		// An invisible full-classic-screen dialog (0,0)-(342,512) is a game screen the program places itself relative to
		// its main window (MoveWindow to mainWindow + rect); centring it here would double the offset.
		boolean fullScreen=!visible&&bounds[0]==0&&bounds[1]==0&&bounds[2]>=342&&bounds[3]>=512;
		if(!fullScreen)bounds=position(bounds,positioning);
		// A dctb of the same id colours the dialog like a wctb colours a window (part 0 = content colour).
		ResourceFile68k.Resource dctb=tb.resources.find(0x64637462,id);
		int window=create(storage,bounds,title,visible&&!forceInvisible,procId,behind,goAway,refCon,items,false,dctb==null?null:tb.resources.data(dctb),dctb!=null);
		if(window!=0){applyFontTable(window,ditlId,0);applyItemStyles(window,ditlId);
			ResourceFile68k.Resource dlgx=tb.resources.find(0x646c6778,id);byte[] x=dlgx==null?null:tb.resources.data(dlgx); // 'dlgx': version.w, flags.l
			if(x!=null&&x.length>=6&&(MacApp68k.u4(x,2)&2)!=0){dialogs.get(window).embedding=true;tb.appearance.ensureRootControl(window);}}
		return window;
	}
	/**
	 * An 'ictb' of the item list's ID: per item {itemData, itemOffset}; a text item's itemData is the TextEdit style flags
	 * (doFont 1, doFace 2, doSize 4, doColor 8; doBColor $2000, doMode $4000, doFontName $8000) and itemOffset points at its
	 * style record - font (or the offset of the font's name), face, size, colour, background colour, mode. Static text is
	 * drawn with it (F/A-18 Hornet's briefing dialogs).
	 */
	private void applyItemStyles(int window,int ditlId) throws M68kCpuException {
		ResourceFile68k.Resource ictb=tb.resources.find(0x69637462,ditlId);Dialog dialog=dialogs.get(window);if(ictb==null||dialog==null)return;
		byte[] d=tb.resources.data(ictb);if(d==null)return;
		for(int i=0;i<dialog.list.size()&&i*4+4<=d.length;i++){
			Item item=dialog.list.get(i);int kind=item.type&0x7f;if(kind!=STAT_TEXT&&kind!=EDIT_TEXT)continue;
			int flags=MacApp68k.u2(d,i*4),offset=MacApp68k.u2(d,i*4+2);if(flags==0||offset+18>d.length)continue;
			int font=(short)MacApp68k.u2(d,offset);
			if((flags&0x8000)!=0){int at=font&0xffff;font=at<d.length?Fonts68k.numberOf(tb.decode(java.util.Arrays.copyOfRange(d,at+1,Math.min(d.length,at+1+(d[at]&0xff))))):0;}
			int rgb=(d[offset+6]&0xff)<<16|(d[offset+8]&0xff)<<8|(d[offset+10]&0xff);
			item.textStyle=new int[]{flags,font,MacApp68k.u2(d,offset+2)&0xff,(short)MacApp68k.u2(d,offset+4),rgb};
		}
	}

	/**
	 * A 'dftb' of the item list's ID (Appearance dialog font table, the Mac OS 8 successor of 'ictb'): version 0, a count, then
	 * per item either 0 (nothing) or 1 and a ControlFontStyleRec - flags, font, size, style, mode, just, fore and back RGB - and
	 * the font's name as a Pascal string. Static text is drawn with it: iTunes' Setup Assistant pages are small system font
	 * (-2, Geneva 10) and small bold (-3); in the 12-point system font their texts ran out of their items.
	 * {@code first}: the number of items before the list's first (AppendDialogItemList).
	 */
	private void applyFontTable(int window,int ditlId,int first) throws M68kCpuException {
		ResourceFile68k.Resource dftb=tb.resources.find(0x64667462,ditlId);Dialog dialog=dialogs.get(window);if(dftb==null||dialog==null)return;
		byte[] d=tb.resources.data(dftb);if(d==null||d.length<4||MacApp68k.u2(d,0)!=0)return;
		int count=MacApp68k.u2(d,2),at=4;
		for(int i=0;i<count&&at+2<=d.length;i++){
			int type=MacApp68k.u2(d,at);at+=2;if(type!=1)continue;if(at+25>d.length)break;
			int flags=MacApp68k.u2(d,at),font=(short)MacApp68k.u2(d,at+2),size=(short)MacApp68k.u2(d,at+4),face=MacApp68k.u2(d,at+6),just=(short)MacApp68k.u2(d,at+10);
			int rgb=(d[at+12]&0xff)<<16|(d[at+14]&0xff)<<8|(d[at+16]&0xff);int nameLength=d[at+24]&0xff;
			String name=nameLength==0?null:tb.decode(java.util.Arrays.copyOfRange(d,at+25,Math.min(d.length,at+25+nameLength)));at+=25+nameLength;
			int number=first+i;if(number>=dialog.list.size())break;Item item=dialog.list.get(number);if((item.type&0x7f)!=STAT_TEXT||item.textStyle!=null)continue;
			applyControlFontStyle(item,flags,font,size,face,just,rgb,name);
		}
	}
	/** A ControlFontStyleRec (a 'dftb' entry, SetControlFontStyle) as a static text item's style: flags, font (or a meta font),
	 *  size, face, justification, foreground colour; {@code name} the 'dftb' font name, or null. */
	private void applyControlFontStyle(Item item,int flags,int font,int size,int face,int just,int rgb,String name){
		boolean japanese=tb.encoding==StackTextEncoding.JAPANESE;int small=Fonts68k.numberOf(japanese?"Osaka":"Geneva");
		int[] f={0,12,0};boolean meta=false;
		if((flags&0x80)!=0){meta=true;f=font==1||font==6?new int[]{small,10,0}:font==2?new int[]{small,10,1}:font==3?new int[]{small,japanese?12:10,0}:font==4?new int[]{0,12,1}:font==5?new int[]{small,12,0}:f;} // kControlUseThemeFontIDMask
		else if((flags&1)!=0){if(font<0){meta=true;f=font==-2?new int[]{small,10,0}:font==-3?new int[]{small,10,1}:font==-4?new int[]{small,japanese?12:10,0}:f;}else f[0]=font;} // kControlUseFontMask: -1 big system, -2 small, -3 small bold, -4 views
		if(name!=null&&(flags&1)!=0&&!meta)f[0]=Fonts68k.numberOf(name);
		if(!meta||(flags&0x200)!=0){if((flags&0x100)!=0)f[1]+=size;else if((flags&4)!=0&&size>0)f[1]=size;if((flags&2)!=0)f[2]=face&0x7f;} // kControlAddToMetaFontMask
		item.textStyle=new int[]{1|2|4|((flags&8)!=0?8:0),f[0],f[2],f[1],rgb};
		if((flags&0x40)!=0)item.justification=just; // kControlUseJustMask
	}

	/** Builds an invisible dialog from an in-memory DITL (used for the built-in Standard File layout). */
	int fromItems(int[] bounds,int procId,byte[] ditl) throws M68kException {return fromItems(bounds,procId,ditl,null);}
	int fromItems(int[] bounds,int procId,byte[] ditl,Layout layout) throws M68kException {
		if(layout!=null){bounds=bounds.clone();ditl=layout.rewrite(ditl,bounds);}
		return create(0,position(bounds,1),"",false,procId,-1,false,0,tb.api.createHandle(ditl),false);
	}

	/** Centers dialogs that would appear off-screen or on top of the menu bar; a positioning on the parent window ($A80A, $B00A,
	 *  $B80A) places it on the frontmost window as GetNewWindow does. */
	private int[] position(int[] bounds,int positioning) throws M68kCpuException {
		if((positioning&0x80ff)==0x800a&&tb.windows.frontWindow()!=0){int[] p=tb.windows.positionTemplate(bounds,positioning);if(p!=bounds)return p;}
		int[] screen=tb.api.quickDraw().screenBounds();int w=bounds[3]-bounds[1],h=bounds[2]-bounds[0]; // the screen programs see
		boolean center=positioning!=0||bounds[0]<screen[0]+MacAppHost.MENU_BAR_HEIGHT+22||bounds[2]>screen[2]||bounds[3]>screen[3]||bounds[1]<screen[1];
		if(!center)return bounds;
		int left=screen[1]+(screen[3]-screen[1]-w)/2,top=screen[0]+(screen[2]-screen[0]-h)/3;
		return new int[]{top,left,top+h,left+w};
	}

	private int create(int storage,int[] bounds,String title,boolean visible,int procId,int behind,boolean goAway,int refCon,int items,boolean fromPointer) throws M68kException {
		return create(storage,bounds,title,visible,procId,behind,goAway,refCon,items,fromPointer,null);
	}
	private int create(int storage,int[] bounds,String title,boolean visible,int procId,int behind,boolean goAway,int refCon,int items,boolean fromPointer,byte[] colorTable) throws M68kException {
		return create(storage,bounds,title,visible,procId,behind,goAway,refCon,items,fromPointer,colorTable,colorTable!=null);
	}
	/** {@code color}: NewColorDialog, or a DLOG with a dctb - otherwise the record is a classic GrafPort to the program (portBits.bounds at
	 *  port+8), as the ROM makes it; programs position dialogs from those bounds and read the grafVars Handle there. */
	private int create(int storage,int[] bounds,String title,boolean visible,int procId,int behind,boolean goAway,int refCon,int items,boolean fromPointer,byte[] colorTable,boolean color) throws M68kException {
		if(items==0)return 0;
		int window=tb.windows.create(storage,DIALOG_SIZE,bounds,title,false,procId,behind,goAway,refCon,color);
		if(window==0)return 0;
		if(colorTable!=null)tb.windows.applyWindowColors(window,colorTable);
		else schemeBackground(window,false);
		memory.writeWord(window+108,2); // dialogKind
		memory.fill(window+ITEMS,DIALOG_SIZE-ITEMS,0);memory.writeLong(window+ITEMS,items);
		Dialog dialog=new Dialog(window);dialog.items=items;dialogs.put(window,dialog);
		parseItems(dialog);
		memory.writeWord(window+EDIT_FIELD,dialog.editItem>0?dialog.editItem-1:-1);memory.writeWord(window+EDIT_OPEN,0);memory.writeWord(window+DEFAULT_ITEM,1);
		if(dialog.editItem>0){Item e=item(window,dialog.editItem);memory.writeLong(window+TEXT_H,e.teHandle);tb.textEdit.activate(e.teHandle,true);}
		if(visible){tb.windows.show(window,true);draw(window);}
		return window;
	}

	/** A Kaleidoscope scheme colours the dialogs and alerts that bring no colours of their own. */
	private void schemeBackground(int window,boolean alert) throws M68kException {
		KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);int back=scheme==null?-1:scheme.dialogBackground(alert);
		if(back>=0){byte[] table=new byte[16];table[7]=0; // ctSeed, ctFlags, ctSize 0 (one entry): part 0 (wContentColor)
			table[10]=(byte)(back>>16);table[11]=(byte)(back>>16);table[12]=(byte)(back>>8);table[13]=(byte)(back>>8);table[14]=(byte)back;table[15]=(byte)back;
			tb.windows.applyWindowColors(window,table);
			byte[] pattern=scheme.dialogBackgroundPattern(alert);if(pattern!=null)tb.windows.setContentPattern(window,pattern);}
	}
	private void parseItems(Dialog dialog) throws M68kException {parseItems(dialog,0);}
	/** Parses the items from number {@code skip}+1 on (AppendDITL adds to a list whose first items already exist). */
	private void parseItems(Dialog dialog,int skip) throws M68kException {
		int data=memory.readLong(dialog.items);if(data==0)return;int size=tb.api.handleSize(dialog.items);
		int count=(short)memory.readWord(data)+1;int at=data+2;
		dialog.parsing=true;
		try{
			for(int i=1;i<=count&&at+14<=data+size;i++){
				if(i<=skip){int length=memory.readByte(at+13);at+=14+length+(length&1);continue;}
				Item item=parseItem(dialog,at,i);
				dialog.list.add(item);
				at=at+14+item.length+(item.length&1);
			}
		}finally{dialog.parsing=false;}
		dialog.syncedData=data;dialog.syncedSize=size;
	}
	/** The item whose record is at {@code at} (number {@code i}): its control, text Handle, TextEdit record or picture made. */
	private Item parseItem(Dialog dialog,int at,int i) throws M68kException {
		Item item=new Item();item.address=at;item.handle=memory.readLong(at);item.rect=readRect(at+4);item.type=memory.readByte(at+12);item.length=memory.readByte(at+13);
		int kind=item.type&0x7f;int textAddr=at+14;
		switch(kind){
			case BTN_CTRL:case CHK_CTRL:case RAD_CTRL:{byte[] t=memory.readBytes(textAddr-1,item.length+1);t[0]=(byte)item.length;
				item.handle=tb.controls.create(dialog.window,item.rect[0],item.rect[1],item.rect[2],item.rect[3],t,true,0,0,1,kind-4,0);memory.writeLong(at,item.handle);break;}
			case RES_CTRL:{if(item.length>=2){item.handle=tb.controls.getNewControl((short)memory.readWord(textAddr),dialog.window);memory.writeLong(at,item.handle);
				// The Dialog Manager places a CNTL-defined control at the item's display rectangle (keeping the CNTL size).
				if(item.handle!=0)tb.controls.moveTo(item.handle,item.rect[1],item.rect[0]);}break;}
			case STAT_TEXT:case EDIT_TEXT:{byte[] t=memory.readBytes(textAddr,item.length);item.handle=tb.api.createHandle(t);memory.writeLong(at,item.handle);
				if(kind==EDIT_TEXT){int rectPtr=at+4;item.teHandle=tb.textEdit.createForDialog(rectPtr,item.handle,dialog.window);if(dialog.editItem<0)dialog.editItem=i;}break;}
			case ICON_ITEM:case PIC_ITEM:{if(item.length>=2){int id=(short)memory.readWord(textAddr);
				// an icon item takes a 'cicn' of its ID first, as the Color Dialog Manager does (items with only a cicn drew nothing)
				int res=kind==ICON_ITEM?tb.quickDraw.getCIcon(id):0;if(res!=0)item.colorIcon=true;
				else res=tb.resources.detachedResourceOrNil(kind==ICON_ITEM?0x49434f4e:0x50494354,id);
				if(res!=0){item.handle=res;memory.writeLong(at,res);}}break;}
			default:break;
		}
		return item;
	}
	/**
	 * Brings the parsed items in line with the item list Handle after the program changed it itself: the real Dialog Manager
	 * reads the list through the Handle every time, so programs may grow, move or rebuild it. FileMaker Pro 4.0 creates its
	 * Open dialog, then shrinks the list to its count and appends the records it keeps with PtrAndHand - the stale addresses
	 * had HideDialogItem write into freed memory. Records keep their items by their Handle (controls, texts), others by kind
	 * and rectangle; a new record is taken as it stands - the real Dialog Manager makes controls and text Handles only when
	 * the dialog is made (or AppendDITL), and FileMaker's records, half built between two Dialog Manager calls, had their
	 * Handle field overwritten by a re-parse. Items whose records went stay made, as their controls do in the window.
	 */
	private void sync(Dialog dialog) throws M68kException {
		if(dialog.items==0||dialog.parsing)return;
		int data=memory.readLong(dialog.items);int size=data==0?0:tb.api.handleSize(dialog.items);
		if(data==dialog.syncedData&&size==dialog.syncedSize){
			// the records are the truth: programs write a user item's procedure (or a rectangle) straight into them
			for(Item item:dialog.list){int h=memory.readLong(item.address);if(h!=item.handle&&(item.type&0x7f)==USER_ITEM)item.handle=h;
				if(memory.readLong(item.address+4)!=(item.rect[0]<<16|item.rect[1]&0xffff)||memory.readLong(item.address+8)!=(item.rect[2]<<16|item.rect[3]&0xffff))item.rect=readRect(item.address+4);}
			return;}
		dialog.syncedData=data;dialog.syncedSize=size;
		if(data==0||size<2)return;
		Item edit=dialog.editItem>0&&dialog.editItem<=dialog.list.size()?dialog.list.get(dialog.editItem-1):null;
		List<Item> old=new ArrayList<Item>(dialog.list);old.addAll(dialog.detached);
		List<Item> fresh=new ArrayList<Item>();
		int count=(short)memory.readWord(data)+1;int at=data+2;
		for(int i=1;i<=count&&at+14<=data+size;i++){
			int handle=memory.readLong(at),type=memory.readByte(at+12);int[] rect=readRect(at+4);
			Item match=null;
			if(handle!=0)for(Item o:old)if(o.handle==handle){match=o;break;}
			// else the same kind at the same place: FileMaker puts its own data in an edit item's Handle field, and the item
			// keeps its TextEdit record (and its text Handle, which GetDialogItem hands out) as on a real Mac
			if(match==null)for(Item o:old)if((o.type&0x7f)==(type&0x7f)&&java.util.Arrays.equals(o.rect,rect)){match=o;if((type&0x7f)==USER_ITEM)match.handle=handle;break;}
			if(match!=null){old.remove(match);match.address=at;match.type=type;match.length=memory.readByte(at+13);match.rect=rect;}
			else{match=new Item();match.address=at;match.handle=handle;match.type=type;match.length=memory.readByte(at+13);match.rect=rect;}
			fresh.add(match);
			at+=14+match.length+(match.length&1);
		}
		dialog.list.clear();dialog.list.addAll(fresh);dialog.detached.clear();dialog.detached.addAll(old);
		int e=edit==null?-1:fresh.indexOf(edit);
		if(e<0){for(int i=0;i<fresh.size();i++)if(fresh.get(i).teHandle!=0){e=i;break;}}
		dialog.editItem=e<0?-1:e+1;
		memory.writeWord(dialog.window+EDIT_FIELD,e);memory.writeLong(dialog.window+TEXT_H,e<0?0:fresh.get(e).teHandle);
	}

	/**
	 * The DITL utilities AppendDITL ($0402), CountDITL ($0403) and ShortenDITL ($0404), which the Communications Toolbox
	 * dispatches (_CommToolboxDispatch $A08B, A0 pointing at the selector word with the Pascal parameters above it, the
	 * last pushed first). Programs can count and append items to their dialogs. False for other selectors.
	 */
	boolean commToolboxDispatch(M68kCpu cpu) throws M68kException {
		int a0=cpu.a[0];int selector=memory.readWord(a0);
		switch(selector){
			case 0x402:{int method=(short)memory.readWord(a0+2),handle=memory.readLong(a0+4),window=memory.readLong(a0+8);appendItems(window,handle,method);return true;}
			case 0x403:{Dialog dialog=dialogs.get(memory.readLong(a0+2));cpu.d[0]=dialog==null?0:dialog.list.size();return true;}
			case 0x404:{int count=(short)memory.readWord(a0+2),window=memory.readLong(a0+4);shorten(window,count);return true;}
			default:return false;
		}
	}
	/** AppendDITL, CountDITL and ShortenDITL for the Communications Toolbox's tool-settings dialogs (CommToolbox68k). */
	void appendDITL(int window,int handle,int method) throws M68kException {appendItems(window,handle,method);}
	int countDITL(int window){Dialog dialog=dialogs.get(window);return dialog==null?0:dialog.list.size();}
	void shortenDITL(int window,int count) throws M68kCpuException {shorten(window,count);}
	/** AppendDITL: the items of a DITL Handle join the dialog's item list, placed as-is (overlayDITL 0), to the right (1) or
	 *  below (2) the existing items - the dialog grows to hold them - or relative to item -method's rectangle (negative). */
	private void appendItems(int window,int handle,int method) throws M68kException {appendItems(window,handle,method,Integer.MIN_VALUE);}
	/** {@code ditlId}: the resource the items came from, whose 'dftb'/'ictb' style them (MIN_VALUE: none). */
	private void appendItems(int window,int handle,int method,int ditlId) throws M68kException {
		Dialog dialog=dialogs.get(window);if(dialog==null||handle==0)return;
		if(Mac68kApi.TRACE)System.err.printf("[68K] AppendDITL dialog $%x DITL $%x method %d items $%x data $%x%n",window,handle,method,dialog.items,memory.readLong(dialog.items));
		byte[] added=tb.api.handleContents(handle);if(added==null||added.length<2)return;
		int addedCount=((added[0]&0xff)<<8|(added[1]&0xff))+1;
		int[] offset={0,0};WindowManager68k.Window w=tb.windows.window(window);
		if(method!=0){
			int[] used=null;for(Item item:dialog.list){if(item.rect[2]<=item.rect[0]||item.rect[3]<=item.rect[1])continue;used=used==null?item.rect.clone():new int[]{Math.min(used[0],item.rect[0]),Math.min(used[1],item.rect[1]),Math.max(used[2],item.rect[2]),Math.max(used[3],item.rect[3])};}
			int[] extent={32767,32767,-32768,-32768};for(int i=0,at=2;i<addedCount&&at+14<=added.length;i++){int top=(short)((added[at+4]&0xff)<<8|added[at+5]&0xff),left=(short)((added[at+6]&0xff)<<8|added[at+7]&0xff),bottom=(short)((added[at+8]&0xff)<<8|added[at+9]&0xff),right=(short)((added[at+10]&0xff)<<8|added[at+11]&0xff);
				extent[0]=Math.min(extent[0],top);extent[1]=Math.min(extent[1],left);extent[2]=Math.max(extent[2],bottom);extent[3]=Math.max(extent[3],right);int length=added[at+13]&0xff;at+=14+length+(length&1);}
			if(method<0){Item relative=-method<=dialog.list.size()?dialog.list.get(-method-1):null;if(relative!=null){offset[0]=relative.rect[0];offset[1]=relative.rect[1];}}
			else if(w!=null){if(method==1)offset[1]=(used!=null?used[3]:w.width)+8;else offset[0]=(used!=null?used[2]:w.height)+8;
				int width=Math.max(w.width,offset[1]+extent[3]+8),height=Math.max(w.height,offset[0]+extent[2]+8);
				if(width!=w.width||height!=w.height)tb.windows.resize(window,width,height,true,true);}
		}
		// the item data joins the dialog's own DITL Handle (rectangles offset), and the new items are parsed like the original ones
		int data=memory.readLong(dialog.items);int size=tb.api.handleSize(dialog.items);byte[] existing=memory.readBytes(data,size);
		int oldCount=((existing[0]&0xff)<<8|(existing[1]&0xff))+1;
		byte[] joined=new byte[size+added.length-2];System.arraycopy(existing,0,joined,0,size);System.arraycopy(added,2,joined,size,added.length-2);
		for(int i=0,at=size;i<addedCount&&at+14<=joined.length;i++){int top=(short)((joined[at+4]&0xff)<<8|joined[at+5]&0xff)+offset[0],left=(short)((joined[at+6]&0xff)<<8|joined[at+7]&0xff)+offset[1],bottom=(short)((joined[at+8]&0xff)<<8|joined[at+9]&0xff)+offset[0],right=(short)((joined[at+10]&0xff)<<8|joined[at+11]&0xff)+offset[1];
			joined[at+4]=(byte)(top>>8);joined[at+5]=(byte)top;joined[at+6]=(byte)(left>>8);joined[at+7]=(byte)left;joined[at+8]=(byte)(bottom>>8);joined[at+9]=(byte)bottom;joined[at+10]=(byte)(right>>8);joined[at+11]=(byte)right;
			int length=joined[at+13]&0xff;at+=14+length+(length&1);}
		int total=oldCount+addedCount;joined[0]=(byte)((total-1)>>8);joined[1]=(byte)(total-1);
		tb.api.loadHandle(dialog.items,joined);int moved=memory.readLong(dialog.items);
		if(Mac68kApi.TRACE)System.err.printf("[68K] AppendDITL items now at $%x (%d bytes)%n",moved,joined.length);
		for(Item item:dialog.list)item.address=moved+(item.address-data); // the Handle may have moved
		// parseItem takes the first new edit item as the dialog's own when it had none, so remember that before: the record's
		// editField/textH must follow too (BBEdit's Find dialog appends both its edit fields; with editField left at -1 its key
		// handler dropped every keystroke and SelectDialogItemText found the item already current)
		int before=dialog.list.size();boolean hadEdit=dialog.editItem>0;parseItems(dialog,before); // (records the Handle as in line with the items)
		if(ditlId!=Integer.MIN_VALUE)applyFontTable(window,ditlId,before);
		if(!hadEdit){dialog.editItem=-1;for(int i=before;i<dialog.list.size();i++){Item e=dialog.list.get(i);if((e.type&0x7f)==EDIT_TEXT){dialog.editItem=i+1;memory.writeWord(window+EDIT_FIELD,i);memory.writeLong(window+TEXT_H,e.teHandle);tb.textEdit.activate(e.teHandle,true);break;}}}
		if(w!=null&&w.visible)draw(window);
	}
	/** Brings the dialog's items in line with its item list Handle (a program may have appended records to it itself). */
	void syncItems(int window) throws M68kException {Dialog d=dialogs.get(window);if(d!=null)sync(d);}
	/** Gives item {@code itemNo} of the dialog {@code window} a new rectangle, as MoveDialogItem/SizeDialogItem do. */
	void moveItem(int window,int itemNo,int[] rect) throws M68kCpuException {Dialog d=dialogs.get(window);Item it=item(window,itemNo);if(d!=null&&it!=null)setItemRect(d,it,rect);}
	/** MoveDialogItem/SizeDialogItem: the item gets a new rectangle - in the DITL, its control and its edit record - and the old
	 *  and new places are erased/invalidated so the next update draws it there. */
	private void setItemRect(Dialog dialog,Item item,int[] rect) throws M68kCpuException {
		int[] old=item.rect;WindowManager68k.Window w=tb.windows.window(dialog.window);boolean shown=w!=null&&w.visible&&!item.hidden;
		if(shown){eraseItem(dialog.window,item);tb.windows.invalidate(dialog.window,old[0]-4,old[1]-4,old[2]+4,old[3]+4);}
		item.rect=rect;writeRect(item.address+4,rect);
		int kind=item.type&0x7f;
		if(kind>=CTRL_ITEM&&kind<=RES_CTRL&&item.handle!=0){int cd=tb.controls.data(item.handle);if(cd!=0){
			// the control follows (MoveControl/SizeControl), drawn at the update rather than now
			int ct=(short)memory.readWord(cd+8),cl=(short)memory.readWord(cd+10);int ch=(short)memory.readWord(cd+12)-ct,cw=(short)memory.readWord(cd+14)-cl;
			boolean sized=rect[2]-rect[0]!=old[2]-old[0]||rect[3]-rect[1]!=old[3]-old[1];
			if(sized&&kind!=RES_CTRL){ch=rect[2]-rect[0];cw=rect[3]-rect[1];}
			writeRect(cd+8,new int[]{rect[0],rect[1],rect[0]+ch,rect[1]+cw});}}
		if(item.control!=0){int cd=tb.controls.data(item.control),shift=item.hidden?16384:0;if(cd!=0)writeRect(cd+8,new int[]{rect[0],rect[1]-shift,rect[2],rect[3]-shift});}
		if(item.teHandle!=0)tb.textEdit.setRects(item.teHandle,rect);
		if(shown)tb.windows.invalidate(dialog.window,rect[0]-4,rect[1]-4,rect[2]+4,rect[3]+4);
	}
	/** AutoSizeDialog: every visible static text item gets the height its text needs in the dialog's font; the items below it
	 *  move by the difference and the window grows or shrinks with them. */
	private int autoSize(int window) throws M68kCpuException {
		Dialog dialog=dialogs.get(window);WindowManager68k.Window w=tb.windows.window(window);if(w==null)return -50;
		int grow=0;
		for(Item item:dialog.list){int kind=item.type&0x7f;if(kind!=STAT_TEXT||item.hidden||item.handle==0)continue;
			byte[] text=substitute(tb.api.handleContents(item.handle));int[] r=item.rect;
			java.awt.Font itemFont=itemFont(window,item);int[] fi=Fonts68k.fontInfo(itemFont);int itemLine=fi[0]+fi[1]+fi[3];
			int height=text.length==0?0:tb.textEdit.lineCount(text,itemFont,r[3]-r[1])*itemLine;int delta=height-(r[2]-r[0]);if(delta==0)continue;
			for(Item other:dialog.list)if(other!=item&&other.rect[0]>=r[2]){int[] o=other.rect;setItemRect(dialog,other,new int[]{o[0]+delta,o[1],o[2]+delta,o[3]});}
			setItemRect(dialog,item,new int[]{r[0],r[1],r[0]+height,r[3]});grow+=delta;}
		if(grow!=0)tb.windows.resize(window,w.width,Math.max(1,w.height+grow),true,true);
		return 0;
	}
	/** The font a static text item is drawn in: the dialog's, or what its 'ictb'/'dftb' style (SetControlFontStyle) sets. */
	private java.awt.Font itemFont(int window,Item item) throws M68kCpuException {
		int font=(short)memory.readWord(window+68),size=Math.max(1,(short)memory.readWord(window+74)),face=memory.readByte(window+70);int[] st=item.textStyle;
		return st==null?Fonts68k.font(font,size,face):Fonts68k.font((st[0]&1)!=0?st[1]:font,(st[0]&4)!=0?st[3]:size,(st[0]&2)!=0?st[2]:face);
	}
	/**
	 * ShortenDITL: the last {@code count} items leave the dialog (their controls and edit records with them), and the places
	 * they took are erased and invalidated - MacWeb's Preferences swaps its panels by ShortenDITL + AppendDITL without
	 * erasing anything itself; the old panel's texts, frames and edit fields stayed under the new one.
	 */
	private void shorten(int window,int count) throws M68kCpuException {
		Dialog dialog=dialogs.get(window);if(dialog==null||count<=0)return;
		int keep=Math.max(0,dialog.list.size()-count);
		WindowManager68k.Window shownWindow=tb.windows.window(window);boolean shown=shownWindow!=null&&shownWindow.visible;
		while(dialog.list.size()>keep){Item item=dialog.list.remove(dialog.list.size()-1);int kind=item.type&0x7f;
			if(shown&&!item.hidden&&item.rect[2]>item.rect[0]&&item.rect[3]>item.rect[1]){int[] r=item.rect;eraseItem(window,item);tb.windows.invalidate(window,r[0]-4,r[1]-4,r[2]+4,r[3]+4);}
			if(item.control!=0)tb.controls.dispose(item.control,false);
			if(item.teHandle!=0){if(dialog.editItem==dialog.list.size()+1){dialog.editItem=-1;memory.writeWord(window+EDIT_FIELD,-1);memory.writeLong(window+TEXT_H,0);}tb.textEdit.dispose(item.teHandle);}
			if((kind==BTN_CTRL||kind==CHK_CTRL||kind==RAD_CTRL||kind==RES_CTRL)&&item.handle!=0)tb.controls.dispose(item.handle,true);
			else if((kind==STAT_TEXT||kind==EDIT_TEXT)&&item.handle!=0)tb.api.disposeHandleAndFree(item.handle);}
		int data=memory.readLong(dialog.items);if(data!=0){int end=dialog.list.isEmpty()?data+2:dialog.list.get(dialog.list.size()-1).address;
			if(!dialog.list.isEmpty()){Item last=dialog.list.get(dialog.list.size()-1);end=last.address+14+last.length+(last.length&1);}
			byte[] kept=memory.readBytes(data,end-data);kept[0]=(byte)((keep-1)>>8);kept[1]=(byte)(keep-1);tb.api.loadHandle(dialog.items,kept);int moved=memory.readLong(dialog.items);
			for(Item item:dialog.list)item.address=moved+(item.address-data);
			dialog.syncedData=moved;dialog.syncedSize=tb.api.handleSize(dialog.items);}
		WindowManager68k.Window w=tb.windows.window(window);if(w!=null&&w.visible)draw(window);
	}

	void dispose(int window) throws M68kCpuException {
		Dialog dialog=dialogs.remove(window);if(dialog==null)return;
		for(Item item:dialog.list){if(item.teHandle!=0)tb.textEdit.dispose(item.teHandle);int kind=item.type&0x7f;if((kind==STAT_TEXT||kind==EDIT_TEXT)&&item.handle!=0)tb.api.disposeHandleAndFree(item.handle);}
		for(Item item:dialog.detached)if(item.teHandle!=0)tb.textEdit.dispose(item.teHandle);
		tb.api.disposeHandleAndFree(dialog.items);
		tb.windows.disposeWindow(window);
	}
	void windowDisposed(int window){Dialog dialog=dialogs.remove(window);if(dialog==null)return;
		List<Item> all=new ArrayList<Item>(dialog.list);all.addAll(dialog.detached);
		for(Item item:all)if(item.teHandle!=0)try{tb.textEdit.dispose(item.teHandle);}catch(M68kCpuException ignored){}}
	void windowResized(int window){}

	Item item(int window,int number){Dialog d=dialogs.get(window);if(d==null)return null;
		try{sync(d);}catch(M68kException e){throw Toolbox68k.wrap(e);}
		if(number<1||number>d.list.size())return null;return d.list.get(number-1);}
	boolean isDialog(int window){return dialogs.containsKey(window);}

	// ------------------------------------------------------------------ drawing

	/** An update of the dialog: the update region emptied as BeginUpdate does, then the items drawn. What the program invalidates
	 *  while they draw stays for the next update, as it does past EndUpdate: 3D Hearts Deluxe's installer hides its picture item
	 *  from that item's draw procedure, widens the description item over it and InvalRects it - cleared after the drawing, the
	 *  description was never drawn again. */
	void drawUpdate(int window) throws M68kCpuException {tb.windows.clearUpdate(window);draw(window);}
	void draw(int window) throws M68kCpuException {
		Dialog dialog=dialogs.get(window);if(dialog==null)return;
		WindowManager68k.Window w=tb.windows.window(window);if(w==null)return;
		// The real DrawDialog draws the items into the dialog's port whether or not the window is shown: editors may create
		// an invisible dialog and record DrawDialog into a picture to build a preview.
		boolean recording=tb.api.quickDraw().pictureIsOpen();
		if(!w.visible&&!recording)return;
		int savedPort=tb.api.currentQuickDrawPort();setPort(window);
		// While a picture records, the items are captured as a snapshot of the port (below), so what user items draw with
		// QuickDraw must land in the port even though OpenPicture hid the pen: shown for the drawing, restored after.
		int hiddenPen=recording?(short)memory.readWord(window+PN_VIS):0;if(hiddenPen<0)memory.writeWord(window+PN_VIS,0);
		try{
			// Like the real DrawDialog, only the items are drawn: the window content is not erased, so
			// whatever the program drew into the dialog itself (status pictures etc.) survives updates.
			// A recorded offscreen dialog is not cleared for us, so start its content on white (a shown dialog
			// keeps whatever the program drew into it, as the real DrawDialog does).
			if(recording){Graphics2D g=tb.quickDraw.windowSurface(window);if(g!=null){try{g.setColor(Color.WHITE);g.fillRect(0,0,w.width,w.height);}finally{g.dispose();}}}
			MacAppHost.HostWindow hw=tb.host().window(window);
			if(!recording&&hw!=null&&!hw.decorated&&!hw.customFrame){Graphics2D g=tb.quickDraw.windowSurface(window);
				if(g!=null){try{g.setColor(Color.BLACK);g.drawRect(0,0,w.width-1,w.height-1);
					// plainDBox is a single black line on a Mac: no inner grey line (it ran through Factory 1.1's black status bar)
					if((hw.procId&15)!=2){g.setColor(new Color(0x60,0x60,0x60));g.drawRect(1,1,w.width-3,w.height-3);}}finally{g.dispose();}}}
			// The controls come last, as DrawDialog draws them with DrawControls after the other items: MacPaint's About box has
			// a static text rectangle running into its OK button, and the text box's erase must not cut the button on a Mac
			for(Item item:dialog.list)if(!isControl(item))drawItem(dialog,item);
			for(Item item:dialog.list)if(isControl(item))drawItem(dialog,item);
			// with the embedding hierarchy a later item inside a control item is embedded in it and drawn after it: Iconographer's
			// Adjust Colors labels ("色相：", "彩度：") lie in its tab control's pane, which covered them
			if(dialog.embedding)for(int i=0;i<dialog.list.size();i++){Item item=dialog.list.get(i);if(!isControl(item)&&(item.type&0x7f)!=USER_ITEM&&embeddedInControl(dialog,i))drawItem(dialog,item);}
			if(dialog.afterItems!=null)dialog.afterItems.run();
			// DrawDialog ends with DrawControls: every control in the window's list is drawn, not only the items' -
			// Some installers add pop-up controls with NewControl behind disabled user items and draw them once
			// while the dialog is still hidden, then relies on the update to show them
			for(int handle:tb.controls.controlsOf(window)){boolean isItem=false;for(Item item:dialog.list)if(item.handle==handle&&!item.hidden||item.control==handle&&(item.type&0x7f)!=USER_ITEM){isItem=true;break;}if(!isItem)tb.controls.draw(handle);}
			if(dialog.alertKind>=0)drawAlertIcon(window,dialog.alertKind);
			// The items were rendered with the host's Java2D, which QuickDraw picture recording does not see; when a
			// program records DrawDialog into a picture to draw a preview,
			// capture what was drawn so the recorded picture is not empty. A user item may have left the port changed,
			// so point it back at the dialog first.
			if(recording){setPort(window);tb.api.quickDraw().recordPortSnapshot();}
		}finally{if(hiddenPen<0)memory.writeWord(window+PN_VIS,hiddenPen);setPort(savedPort);}
		// Java2D drawing for an indexed window buffer is staged until the current trap returns; a draw from inside ModalDialog's
		// loop (its entry draw, an update event) would otherwise reach the buffer only when the dialog is already gone -
		// Without this, a notice can keep "^0 time(s)" instead of the count ParamText supplied.
		tb.quickDraw.flushSurfaces();
		tb.host().repaintNow(window);
	}

	private static boolean isControl(Item item){int kind=item.type&0x7f;return kind>=CTRL_ITEM&&kind<=RES_CTRL;}
	/** Whether item {@code index} lies inside an earlier shown control item (its embedder in an embedding dialog, as {@link #findItem} takes it). */
	private boolean embeddedInControl(Dialog dialog,int index){
		int[] r=dialog.list.get(index).rect;
		for(int j=0;j<index;j++){Item c=dialog.list.get(j);if(!isControl(c)||!shows(c))continue;int[] o=c.rect;
			if(r[0]>=o[0]&&r[1]>=o[1]&&r[2]<=o[2]&&r[3]<=o[3])return true;}
		return false;
	}

	private void setPort(int port) throws M68kCpuException {if(port==0)return;M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}

	private void drawItem(Dialog dialog,Item item) throws M68kCpuException {
		if(!shows(item))return;followControl(item);int kind=item.type&0x7f;int number=dialog.list.indexOf(item)+1;
		Runnable host=dialog.nativeItems.get(number);if(host!=null){host.run();return;}
		switch(kind){
			case BTN_CTRL:case CHK_CTRL:case RAD_CTRL:case RES_CTRL:
				if(item.handle!=0){tb.controls.draw(item.handle);
					// The default ring follows the control, not the DITL rect: Dark Castle's "two colours" notice moves its
					// Restart button out of the window (MoveControl to v=400) and an empty ring stayed behind.
					int ctl=memory.readLong(item.handle);
					if(dialog.ringDefault&&number==dialog.defaultItem&&kind==BTN_CTRL&&ctl!=0&&memory.readByte(ctl+16)!=0){Graphics2D g=tb.quickDraw.windowSurface(dialog.window);if(g!=null){try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,tb.quickDraw.antialiasHint(dialog.window));int l=(short)memory.readWord(ctl+10),t=(short)memory.readWord(ctl+8),r=(short)memory.readWord(ctl+14),b=(short)memory.readWord(ctl+12);
						KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb); // a Kaleidoscope scheme's default ring
						if(scheme==null||!scheme.drawDefaultRing(g,l,t,r,b,memory.readByte(ctl+17)==255))ControlManager68k.drawDefaultOutline(g,l,t,r,b);}finally{g.dispose();}}}}
				break;
			case STAT_TEXT:{byte[] text=item.handle==0?new byte[0]:tb.api.handleContents(item.handle);if(text==null)text=new byte[0];String translation=translatedStatic(text);text=substitute(text);if(tb.app!=null)tb.app.textShown(text);
				if(Mac68kApi.TRACE)System.err.println("[dialog] static text item "+number+" at "+item.rect[1]+","+item.rect[0]+"-"+item.rect[3]+","+item.rect[2]+": \""+tb.decode(text)+"\" fore "+Integer.toHexString(tb.api.quickDraw().foregroundRgb())+" back "+Integer.toHexString(tb.api.quickDraw().backgroundRgb())+" text "+java.util.Arrays.toString(tb.api.quickDraw().textState()));
				int scratch=memory.allocate(8,2);writeRect(scratch,item.rect);
				int[] style=item.textStyle;int port=tb.api.currentQuickDrawPort();int savedFont=0,savedFace=0,savedSize=0;Object[] savedFore=null;
				if(style!=null&&port!=0){savedFont=memory.readWord(port+68);savedFace=memory.readByte(port+70);savedSize=memory.readWord(port+74);savedFore=tb.api.quickDraw().saveForeground();
					if((style[0]&1)!=0||(style[0]&0x8000)!=0)memory.writeWord(port+68,style[1]);if((style[0]&2)!=0)memory.writeByte(port+70,style[2]);if((style[0]&4)!=0)memory.writeWord(port+74,style[3]);
					if((style[0]&8)!=0){int c=style[4];tb.api.quickDraw().setForegroundRgb16((c>>>16&255)*257,(c>>>8&255)*257,(c&255)*257);}}
				try{tb.textEdit.textBox(text,scratch,item.justification,translation);}
				finally{memory.free(scratch);if(style!=null&&port!=0){memory.writeWord(port+68,savedFont);memory.writeByte(port+70,savedFace);memory.writeWord(port+74,savedSize);tb.api.quickDraw().restoreForeground(savedFore);}}
				break;}
			case EDIT_TEXT:{Graphics2D g=tb.quickDraw.windowSurface(dialog.window);if(g!=null){try{
					// the 3-pixel margin inside the frame shows the dialog's background, as the text's own area does (TextEdit erases it
					// with the port's background colour): a white ring around a coloured field (Etoile's lavender dialog) otherwise
					g.setColor(new Color(tb.api.quickDraw().colorsOf(dialog.window)[1]));g.fillRect(item.rect[1]-3,item.rect[0]-3,item.rect[3]-item.rect[1]+6,item.rect[2]-item.rect[0]+6);g.setColor(Color.BLACK);g.drawRect(item.rect[1]-3,item.rect[0]-3,item.rect[3]-item.rect[1]+5,item.rect[2]-item.rect[0]+5);}finally{g.dispose();}}
				if(item.teHandle!=0){tb.textEdit.shareDialogStyle(memory.readLong(dialog.window+TEXT_H),item.teHandle);tb.textEdit.redraw(item.teHandle);}break;}
			case ICON_ITEM:{if(item.handle!=0){int scratch=memory.allocate(8,2);writeRect(scratch,item.rect);tb.quickDraw.plotIcon(item.handle,scratch,item.colorIcon);memory.free(scratch);}break;}
			case PIC_ITEM:{if(item.handle!=0){int scratch=memory.allocate(8,2);writeRect(scratch,item.rect);M68kCpu cpu=tb.cpu;int saved=cpu.a[7];
				// DrawPicture(picture, dstRect): Pascal, so the picture is pushed first and the rectangle pointer ends up on top
				cpu.a[7]-=4;memory.writeLong(cpu.a[7],item.handle);cpu.a[7]-=4;memory.writeLong(cpu.a[7],scratch);
				try{tb.api.quickDraw().handle(cpu,0x8f6);}catch(M68kCpuException e){if(Mac68kApi.TRACE)System.err.println("[dialog] picture item "+number+" not drawn: "+e.getMessage());}cpu.a[7]=saved;memory.free(scratch);}break;}
			case USER_ITEM:{if(item.handle!=0){try{callUserItem(item.handle,dialog.window,number);}catch(M68kException e){}}break;}
			default:break;
		}
	}

	private void callUserItem(int proc,int window,int number) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		cpu.pushLong(window);cpu.pushWord(number);
		try{cpu.callPascal(proc,6);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	private void eraseItem(int window,Item item) throws M68kCpuException {
		Graphics2D g=tb.quickDraw.windowSurface(window);if(g==null)return;
		WindowManager68k.Window w=tb.windows.window(window);
		try{g.setColor(new Color(w==null?0xffffff:w.contentColor));g.fillRect(item.rect[1]-4,item.rect[0]-4,item.rect[3]-item.rect[1]+8,item.rect[2]-item.rect[0]+8);}finally{g.dispose();}
	}

	private void drawAlertIcon(int window,int kind) throws M68kCpuException {
		Graphics2D g=tb.quickDraw.windowSurface(window);if(g==null)return;
		try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			int x=20,y=10;int size=32;
			if(kind==1){g.setColor(new Color(0xd9,0x3b,0x3b));g.fillOval(x,y,size,size);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,22));g.drawString("!",x+12,y+24);}
			else if(kind==2){g.setColor(new Color(0x3b,0x7d,0xd9));g.fillOval(x,y,size,size);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));g.drawString("i",x+13,y+23);}
			else if(kind==3){g.setColor(new Color(0xf0,0xb4,0x2f));g.fillPolygon(new int[]{x+16,x,x+size},new int[]{y,y+size,y+size},3);g.setColor(Color.BLACK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.drawString("!",x+12,y+27);}
		}finally{g.dispose();}
	}

	/** ^n text: the StringHandle DAStrings holds, as the Dialog Manager reads it at draw time - THINK C stores its own handles
	 *  there (GetString + DetachResource + Munger) instead of calling ParamText. */
	private String paramString(int n){
		try{int h=memory.readLong(DA_STRINGS+4*n);int p=h==0?0:memory.readLong(h);if(p!=0)return tb.readPascal(p);}catch(M68kCpuException|RuntimeException ignored){}
		return paramText[n];
	}

	/** ^0-^3 replaced by the ParamText strings, as static text items show them (HyperCard's ShowHCAlert prompt) */
	String substituteParamText(String s){
		if(s==null||s.indexOf('^')<0)return s;
		StringBuilder out=new StringBuilder();
		for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='^'&&i+1<s.length()&&s.charAt(i+1)>='0'&&s.charAt(i+1)<='3'){out.append(paramString(s.charAt(i+1)-'0'));i++;}else out.append(c);}
		return out.toString();
	}

	/**
	 * A static text item in the user's language (VMTranslation), or null to show it as it is. The text with its ^0-^3 is
	 * translated, and the ParamText strings put in afterwards - translated only when they are the program's own resource
	 * strings, so a document's or the user's name in an alert stays as it is.
	 */
	private String translatedStatic(byte[] raw){
		if(tb.translator()==null)return null;
		String template=tb.decode(raw),t=tb.translated(VMTranslation.Kind.UI,template);
		if(t==null&&template.indexOf('^')<0)return null;
		String base=t==null?template:t;
		StringBuilder out=new StringBuilder();boolean changed=t!=null;
		for(int i=0;i<base.length();i++){char c=base.charAt(i);
			if(c=='^'&&i+1<base.length()&&base.charAt(i+1)>='0'&&base.charAt(i+1)<='3'){
				String p=paramString(base.charAt(i+1)-'0'),pt=p!=null&&tb.translationKnows(p)?tb.translated(VMTranslation.Kind.UI,p):null;
				if(pt!=null)changed=true;
				out.append(pt!=null?pt:p==null?"":p);i++;}
			else out.append(c);}
		return changed?out.toString():null;
	}

	private byte[] substitute(byte[] text){
		String s=tb.decode(text);
		if(s.indexOf('^')<0)return text;
		StringBuilder out=new StringBuilder();
		for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='^'&&i+1<s.length()&&s.charAt(i+1)>='0'&&s.charAt(i+1)<='3'){out.append(paramString(s.charAt(i+1)-'0'));i++;}else out.append(c);}
		return tb.encoding.encodeReplacing(out.toString());
	}

	// ------------------------------------------------------------------ items

	/** Whether an item is shown: not hidden, nor its {@link ItemControl} (HideControl) or a control it was embedded in (a tab's pane). */
	private boolean shows(Item item){
		if(item.hidden)return false;if(item.control==0)return true;
		try{int cd=tb.controls.data(item.control);return cd==0||memory.readByte(cd+16)!=0&&(tb.appearance==null||!tb.appearance.hiddenByEmbedder(item.control));}catch(M68kCpuException e){return true;}
	}
	/** An item made a control takes the control's rectangle (MoveControl, SizeControl, or written into the record: Iconographer
	 *  makes its alert's message as tall as the text that way). */
	private void followControl(Item item) throws M68kCpuException {
		if(item.control==0||item.hidden)return;int cd=tb.controls.data(item.control);if(cd==0)return;
		int[] r={(short)memory.readWord(cd+8),(short)memory.readWord(cd+10),(short)memory.readWord(cd+12),(short)memory.readWord(cd+14)};
		if(!java.util.Arrays.equals(r,item.rect)){item.rect=r;writeRect(item.address+4,r);if(item.teHandle!=0)tb.textEdit.setRects(item.teHandle,r);}
	}
	/**
	 * kDialogFlagsUseControlHierarchy makes every item of a dialog a control, so GetDialogItemAsControl hands out one for a
	 * static text, edit text, icon, picture or user item too (0 without the hierarchy). Iconographer's alerts
	 * (DITL 5000) put their message in with SetControlData('text') on the static text and hide the icons they do not need with
	 * HideControl: the message stayed "Static text" and all three icons showed on top of each other; its Icon Info dialog
	 * fills the ID and name fields the same way and embeds the items of each tab in a pane it hides and shows; its About box
	 * draws its picture and credits into the rectangles of its user items' controls (user panes, drawn by their own 'draw'
	 * procedures as any user pane).
	 */
	private int itemControl(Dialog dialog,Item item) throws M68kException {
		if(item.control!=0||dialog==null||!dialog.embedding)return item.control;
		int kind=item.type&0x7f,procId=kind==STAT_TEXT?288:kind==EDIT_TEXT?272:kind==ICON_ITEM?320:kind==PIC_ITEM?304:0; // kControlStaticTextProc, kControlEditTextProc, kControlIconProc, kControlPictureProc
		int shift=item.hidden?16384:0;int[] r=item.rect;
		if(kind==USER_ITEM){item.control=tb.controls.create(dialog.window,r[0],r[1]-shift,r[2],r[3]-shift,null,!item.hidden,0,0,0,256,0);return item.control;} // kControlUserPaneProc
		if(procId==0)return 0;
		item.control=tb.controls.create(dialog.window,r[0],r[1]-shift,r[2],r[3]-shift,null,!item.hidden,0,0,0,procId,0,new ItemControl(dialog,item));
		return item.control;
	}
	/** The control of an item that is no control ({@link #itemControl}): shown, hidden, moved and sized as a control, its 'text'
	 *  the item's text (an edit text's TextEdit record, its selection and 'than' too) and a static text's 'font'
	 *  (SetControlFontStyle) the item's style; it draws as the item does, and focusing it makes the item the edit field. */
	private final class ItemControl implements ControlManager68k.NativeDefinition {
		private final Dialog dialog;private final Item item;
		ItemControl(Dialog dialog,Item item){this.dialog=dialog;this.item=item;}
		private boolean text(){return (item.type&0x7f)==STAT_TEXT;}
		private boolean edit(){return (item.type&0x7f)==EDIT_TEXT&&item.teHandle!=0;}
		@Override public void draw(int h) throws M68kException {
			if(dialogs.get(dialog.window)!=dialog)return;followControl(item);
			int savedPort=tb.api.currentQuickDrawPort();setPort(dialog.window);try{drawItem(dialog,item);}finally{setPort(savedPort);}
		}
		@Override public int test(int h,int x,int y){return 0;} // clicks stay the Dialog Manager's (item hits)
		@Override public int track(int h,int x,int y,int modifiers){return 0;}
		@Override public int key(int h,int keyCode,int charCode,int modifiers){return 0;}
		@Override public void dispose(int h){if(item.control==h)item.control=0;}
		@Override public int features(int h){return text()?1<<6|1<<9:edit()?1<<2|1<<6:0;} // kControlSupportsDataAccess, kControlSupportsGetBestRect, kControlSupportsFocus
		@Override public int focus(int h,int part) throws M68kException { // the keys stay the Dialog Manager's: the item becomes the edit field
			if(!edit()||part==0)return 0;
			int number=dialog.list.indexOf(item)+1;if(number<1)return 0;switchEditField(dialog,number);return AppearanceControls68k.PART_EDIT_TEXT;
		}
		@Override public int setData(int h,int part,int tag,int size,int data) throws M68kException {
			if(edit()&&tag==AppearanceControls68k.T_SELECTION){if(data==0||size<4)return -50;
				int n=tb.textEdit.text(item.teHandle).length,start=Math.max(0,Math.min(n,(short)memory.readWord(data))),end=Math.max(start,Math.min(n,(short)memory.readWord(data+2)));
				tb.textEdit.setSelection(item.teHandle,start,end,dialog.editItem==dialog.list.indexOf(item)+1);return 0;}
			if(!text()&&!edit())return UNHANDLED;
			if(tag==AppearanceControls68k.T_TEXT||tag==AppearanceControls68k.T_CFSTRING){
				byte[] bytes;
				if(tag==AppearanceControls68k.T_TEXT)bytes=data==0?new byte[0]:memory.readBytes(data,Math.max(0,Math.min(size,32767)));
				else{String s=size<4||data==0||tb.app==null||tb.app.ppc==null?null:tb.app.ppc.libraries.textServices.cfText(memory.readLong(data));if(s==null)return -50;bytes=tb.encoding.encodeReplacing(s);}
				if(item.handle==0){item.handle=tb.api.createHandle(bytes);memory.writeLong(item.address,item.handle);}
				setItemText(item.handle,bytes);return 0;}
			if(tag==AppearanceControls68k.T_FONT&&text()){ // ControlFontStyleRec: flags, font, size, style, mode, just, foreColor, backColor
				if(data==0||size<24)return -50;int flags=memory.readWord(data);
				if(flags==0){item.textStyle=null;item.justification=0;}
				else applyControlFontStyle(item,flags,(short)memory.readWord(data+2),(short)memory.readWord(data+4),memory.readWord(data+6),(short)memory.readWord(data+10),
					memory.readByte(data+12)<<16|memory.readByte(data+14)<<8|memory.readByte(data+16),null);
				return 0;}
			return UNHANDLED;
		}
		@Override public int getData(int h,int part,int tag,int bufferSize,int buffer,int actualSize) throws M68kException {
			if(!text()&&!edit())return UNHANDLED;byte[] v;
			if(tag==AppearanceControls68k.T_TEXT&&edit())v=tb.textEdit.text(item.teHandle);
			else if(tag==AppearanceControls68k.T_SELECTION&&edit()){int t=memory.readLong(item.teHandle);v=memory.readBytes(t+32,4);} // selStart, selEnd
			else if(tag==AppearanceControls68k.T_TE&&edit())v=new byte[]{(byte)(item.teHandle>>>24),(byte)(item.teHandle>>>16),(byte)(item.teHandle>>>8),(byte)item.teHandle};
			else if(!text())return UNHANDLED;
			else if(tag==AppearanceControls68k.T_TEXT){byte[] t=item.handle==0?null:tb.api.handleContents(item.handle);v=t==null?new byte[0]:t;}
			else if(tag==AppearanceControls68k.T_TEXT_HEIGHT){int[] b=bestRect(h);int height=b[2]-b[0];v=new byte[]{(byte)(height>>8),(byte)height};}
			else return UNHANDLED;
			if(actualSize!=0)memory.writeLong(actualSize,v.length);
			if(buffer!=0&&bufferSize>0)memory.load(buffer,java.util.Arrays.copyOf(v,Math.min(v.length,bufferSize))); // as the Appearance kinds answer
			return 0;
		}
		@Override public int[] bestRect(int h) throws M68kException {
			if(!text())return null;followControl(item);
			java.awt.Font f=itemFont(dialog.window,item);java.awt.FontMetrics fm=Fonts68k.metrics(f);int[] r=item.rect;
			byte[] t=item.handle==0?null:tb.api.handleContents(item.handle);String s=tb.decode(substitute(t==null?new byte[0]:t));
			java.util.List<String> lines=AppearanceControls68k.wrap(s,f,r[3]-r[1]);int w=0;for(String l:lines)w=Math.max(w,Fonts68k.width(f,l));
			return new int[]{r[0],r[1],r[0]+Math.max(1,lines.size())*fm.getHeight(),r[1]+w,-fm.getDescent()};
		}
	}

	/** SetDialogItemText from the host (Standard File's default name / prompt). */
	void setText(int window,int number,byte[] text) throws M68kCpuException {
		Item item=item(window,number);if(item==null||item.handle==0)return;
		int scratch=memory.allocate(256,2);try{int n=Math.min(255,text.length);memory.writeByte(scratch,n);memory.load(scratch+1,java.util.Arrays.copyOf(text,n));setItemText(item.handle,scratch);}finally{memory.free(scratch);}
	}
	/** Current text of an edit-text item. */
	byte[] text(int window,int number) throws M68kCpuException {
		Item item=item(window,number);if(item==null)return new byte[0];
		if(item.teHandle!=0)return tb.textEdit.text(item.teHandle);byte[] t=item.handle==0?null:tb.api.handleContents(item.handle);return t==null?new byte[0]:t;
	}

	private void setItemText(int handle,int text) throws M68kCpuException {
		if(handle==0)return;setItemText(handle,text==0?new byte[0]:memory.readBytes(text+1,memory.readByte(text)));
	}
	private void setItemText(int handle,byte[] bytes) throws M68kCpuException {
		tb.api.loadHandle(handle,bytes);
		for(Dialog dialog:dialogs.values())for(Item item:dialog.list)if(item.handle==handle){
			if(item.teHandle!=0)tb.textEdit.setText(item.teHandle,bytes); // like TESetText: an insertion point at the end, nothing selected (Installer VISE's alerts put their message in a disabled editText)
			WindowManager68k.Window w=tb.windows.window(dialog.window);
			if(w!=null&&w.visible&&!item.hidden){int savedPort=tb.api.currentQuickDrawPort();setPort(dialog.window);try{if((item.type&0x7f)!=STAT_TEXT)eraseItem(dialog.window,item);drawItem(dialog,item);}finally{setPort(savedPort);}} // a static text's TextBox erases its own rect with the port's background pattern (white margins around Factory's white-on-black status texts)
		}
	}

	void selectText(int window,int number,int start,int end) throws M68kCpuException {
		Dialog dialog=dialogs.get(window);Item item=item(window,number);if(dialog==null||item==null||item.teHandle==0)return;
		switchEditField(dialog,number);
		tb.textEdit.setSelection(item.teHandle,start,end,true);
	}

	private boolean beingEdited(int window,int number){Dialog dialog=dialogs.get(window);return dialog!=null&&dialog.editItem==number;}

	private void switchEditField(Dialog dialog,int number) throws M68kCpuException {
		if(dialog.editItem==number)return;
		Item old=item(dialog.window,dialog.editItem);if(old!=null&&old.teHandle!=0){tb.textEdit.activate(old.teHandle,false);tb.textEdit.setRects(old.teHandle,old.rect);} // a SetDItem while it was being edited
		dialog.editItem=number;Item item=item(dialog.window,number);
		memory.writeWord(dialog.window+EDIT_FIELD,number-1);
		if(item!=null&&item.teHandle!=0){tb.textEdit.shareDialogStyle(memory.readLong(dialog.window+TEXT_H),item.teHandle);memory.writeLong(dialog.window+TEXT_H,item.teHandle);tb.textEdit.activate(item.teHandle,true);}
	}

	/**
	 * 1-based item under a local point (enabled items only when {@code enabledOnly}), or 0: the first item that contains
	 * it. With the embedding hierarchy the deepest control is hit instead (FindControlUnderMouse): a later item lying inside
	 * the one found is embedded in it. iTunes' Preferences append each pane's check boxes and sliders inside the tab
	 * control (item 3) - every click tracked the tabs and the check boxes never changed.
	 */
	int findItem(int window,int h,int v,boolean enabledOnly){
		Dialog dialog=dialogs.get(window);if(dialog==null)return 0;int found=0;int[] outer=null;
		for(int i=0;i<dialog.list.size();i++){Item item=dialog.list.get(i);if(!shows(item))continue;if(enabledOnly&&(item.type&ITEM_DISABLE)!=0)continue;
			int[] r=hitRect(dialog,item);
			if(!(h>=r[1]&&h<r[3]&&v>=r[0]&&v<r[2]))continue;
			if(found==0){found=i+1;outer=r;if(!dialog.embedding)break;continue;}
			if(r[0]>=outer[0]&&r[1]>=outer[1]&&r[2]<=outer[2]&&r[3]<=outer[3]){found=i+1;outer=r;}}
		return found;
	}
	/** Where a click hits an item: with the embedding hierarchy a control item is its control (FindControlUnderMouse), wherever
	 *  the program put it - Iconographer moves its alert buttons below the message by writing their contrlRect. */
	private int[] hitRect(Dialog dialog,Item item){
		try{followControl(item);
			if(dialog.embedding&&isControl(item)&&item.handle!=0){int cd=tb.controls.data(item.handle);
				if(cd!=0)return new int[]{(short)memory.readWord(cd+8),(short)memory.readWord(cd+10),(short)memory.readWord(cd+12),(short)memory.readWord(cd+14)};}}
		catch(M68kCpuException e){}
		return item.rect;
	}

	// ------------------------------------------------------------------ events

	/** IsDialogEvent looks at windowKind like the ROM: a dialog whose program changed its kind is the program's to handle. MPW's
	 *  Commando sets 256 and handles its own controls after IsDialogEvent says no; DialogSelect used to take the clicks. */
	private boolean hasDialogKind(int window) throws M68kCpuException {return dialogs.containsKey(window)&&(short)memory.readWord(window+108)==2;}
	private boolean isDialogEvent(int eventPtr) throws M68kCpuException {
		EventManager68k.Event e=tb.events.readEvent(eventPtr);
		int front=tb.windows.frontWindow();
		if(e.what==EventManager68k.ACTIVATE_EVT||e.what==EventManager68k.UPDATE_EVT)return hasDialogKind(e.message);
		if(!hasDialogKind(front))return false;
		if(e.what==EventManager68k.MOUSE_DOWN){int[] l=tb.windows.globalToContent(front,e.whereH,e.whereV);WindowManager68k.Window w=tb.windows.window(front);return l[0]>=0&&l[1]>=0&&l[0]<w.width&&l[1]<w.height;}
		return true;
	}

	private boolean dialogSelect(M68kCpu cpu) throws M68kException {
		int itemHitPtr=memory.readLong(cpu.a[7]),dialogPtr=memory.readLong(cpu.a[7]+4),eventPtr=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
		EventManager68k.Event e=tb.events.readEvent(eventPtr);
		int window=(e.what==EventManager68k.ACTIVATE_EVT||e.what==EventManager68k.UPDATE_EVT)?e.message:tb.windows.frontWindow();
		Dialog dialog=dialogs.get(window);
		if(dialogPtr!=0)memory.writeLong(dialogPtr,dialog==null?0:window);
		int hit=dialog==null?0:handleEvent(dialog,e,true);
		if(itemHitPtr!=0)memory.writeWord(itemHitPtr,hit);
		tb.writeBoolean(cpu.a[7],hit>0);return true;
	}

	/** Standard dialog event handling; returns the enabled item hit, or 0 (negative: consumed). */
	private int handleEvent(Dialog dialog,EventManager68k.Event e,boolean modeless) throws M68kException {
		int window=dialog.window;
		if(Boolean.getBoolean("hz68k.bbdebug")&&(e.what==1||e.what==3))System.err.println("[bbdebug] dialog event "+e.what+" msg="+e.message+" edit="+dialog.editItem+" te="+memory.readLong(window+TEXT_H));
		switch(e.what){
			case EventManager68k.UPDATE_EVT:if(e.message==window)drawUpdate(window);return 0; // BeginUpdate/DrawDialog/EndUpdate, as the real Dialog Manager does
			// the open edit field blinks only while it is an edit text: an item the program made a static text (Myth II's installer
			// sizes its alert message in an edit text, then SetDialogItems it static) is drawn as one here, so its caret would stand
			// alone (on a Mac it stays at the end of the text, outside the record's old view rectangle)
			case EventManager68k.ACTIVATE_EVT:{Item edit=item(window,dialog.editItem);if(edit!=null&&edit.teHandle!=0&&(edit.type&0x7f)==EDIT_TEXT)tb.textEdit.activate(edit.teHandle,(e.modifiers&1)!=0);return 0;}
			case EventManager68k.NULL_EVENT:{if(focusedControlItem(dialog)>0){tb.controls.idleControls(window);return 0;}Item edit=item(window,dialog.editItem);if(edit!=null&&edit.teHandle!=0&&(edit.type&0x7f)==EDIT_TEXT)tb.textEdit.idleFromDialog(edit.teHandle);return 0;}
			case EventManager68k.MOUSE_DOWN:{
				if(e.menuId!=0)return 0; // a made-up menu-bar or close-box click
				if(tb.windows.frontWindow()!=window&&modeless)return 0;
				int[] l=tb.windows.globalToLocal(window,e.whereH,e.whereV);
				int number=findItem(window,l[0],l[1],false);
				if(Mac68kApi.TRACE)System.err.println("[68K] dialog click at "+l[0]+","+l[1]+" item "+number);
				if(number==0)return dialog.nativeEvents!=null?Math.max(0,dialog.nativeEvents.unhandled(e,l[0],l[1])):0;
				Item item=item(window,number);int kind=item.type&0x7f;boolean enabled=(item.type&ITEM_DISABLE)==0;
				// a disabled static/user/picture item may still mean something to the host (Standard File's disk name goes up a folder)
				if(!enabled&&(kind<CTRL_ITEM||kind>RES_CTRL)&&kind!=EDIT_TEXT&&dialog.nativeEvents!=null){int r=dialog.nativeEvents.unhandled(e,l[0],l[1]);if(r>0)return r;}
				int savedPort=tb.api.currentQuickDrawPort();setPort(window);
				try{
					// ModalDialog/DialogSelect only track the control: toggling a check box or setting a radio button is the program's job
					// (HyperCard's Open Stack hook flips "新しいウインドウに表示" itself, so a toggle here undid every click).
					// The action is -1 as in the ROM: the control's own contrlAction - a program's pop-up CDEF that sets it to -1
					// shows its menu from the autoTrack message (SoftWindows' PC Hard Disk Drives pop-ups did nothing)
					if(kind>=CTRL_ITEM&&kind<=RES_CTRL&&item.handle!=0){int part=tb.controls.track(item.handle,l[0],l[1],-1);
						if(focusedControlItem(dialog)==number){Item edit=item(window,dialog.editItem);if(edit!=null&&edit.teHandle!=0)tb.textEdit.activate(edit.teHandle,false);} // an edit text control took the keys
						if(part==0)return 0;return enabled?number:0;}
					if(kind==EDIT_TEXT&&item.teHandle!=0){focusClassicField(dialog,number);tb.textEdit.clickFromDialog(item.teHandle,l[0],l[1],(e.modifiers&0x200)!=0);return enabled?number:0;}
				}finally{setPort(savedPort);}
				return enabled?number:0;
			}
			case EventManager68k.KEY_DOWN:case EventManager68k.AUTO_KEY:{
				int ch=e.message&0xff;
				if(ch==0x0d||ch==0x03){if(dialog.defaultItem>0){Item d=item(window,dialog.defaultItem);if(d!=null&&(d.type&0x7f)>=CTRL_ITEM&&(d.type&0x7f)<=RES_CTRL)flash(d);return dialog.defaultItem;}return 0;}
				if(ch==0x1b||((e.modifiers&0x100)!=0&&ch=='.')){if(dialog.cancelItem>0){Item c=item(window,dialog.cancelItem);if(c!=null)flash(c);return dialog.cancelItem;}return 0;}
				if((e.modifiers&0x100)!=0&&modeless)return 0;
				if(dialog.nativeEvents!=null){int r=dialog.nativeEvents.unhandled(e,0,0);if(r>0)return r;}
				if((e.modifiers&0x100)!=0){ // a command key is no character for the field: the edit commands work on it, others are ignored
					Item field=item(window,dialog.editItem);
					if(field!=null&&field.teHandle!=0){int savedPort=tb.api.currentQuickDrawPort();setPort(window);try{if(tb.textEdit.editCommand(field.teHandle,ch))return (field.type&ITEM_DISABLE)==0?dialog.editItem:0;}finally{setPort(savedPort);}}
					return 0;}
				Item edit=item(window,dialog.editItem);int focused=focusedControlItem(dialog);
				if(ch==0x09){int next=nextTextItem(dialog,focused>0?focused:dialog.editItem,(e.modifiers&0x200)!=0);
					if(next>0){Item n=item(window,next);
						if((n.type&0x7f)==EDIT_TEXT){focusClassicField(dialog,next);tb.textEdit.selectAll(n.teHandle);}
						else{if(edit!=null&&edit.teHandle!=0)tb.textEdit.activate(edit.teHandle,false);int savedPort=tb.api.currentQuickDrawPort();setPort(window);try{tb.controls.setKeyboardFocus(window,n.handle,AppearanceControls68k.PART_EDIT_TEXT);}finally{setPort(savedPort);}}}
					return 0;}
				if(focused>0){ // embedding dialogs: keys go to the control with the keyboard focus (HandleControlKey), e.g. a password edit text control
					Item f=item(window,focused);int savedPort=tb.api.currentQuickDrawPort();setPort(window);
					try{tb.controls.key(f.handle,(e.message>>8)&0xff,ch,e.modifiers);}finally{setPort(savedPort);}
					return (f.type&ITEM_DISABLE)==0?focused:0;}
				if(edit!=null&&edit.teHandle!=0){int savedPort=tb.api.currentQuickDrawPort();setPort(window);try{tb.textEdit.keyFromDialog(edit.teHandle,ch);}finally{setPort(savedPort);}return (edit.type&ITEM_DISABLE)==0?dialog.editItem:0;}
				return 0;
			}
			default:return 0;
		}
	}

	/** The 1-based control item holding the window's keyboard focus (an Appearance edit text control in an embedding dialog), or 0. */
	private int focusedControlItem(Dialog dialog){
		int focus=tb.controls.keyboardFocus(dialog.window);if(focus==0)return 0;
		for(int i=0;i<dialog.list.size();i++){Item it=dialog.list.get(i);int kind=it.type&0x7f;if(kind>=CTRL_ITEM&&kind<=RES_CTRL&&it.handle==focus)return i+1;}
		return 0;
	}
	/** A click in a classic edit text item takes the keys back from a focused control. */
	private void focusClassicField(Dialog dialog,int number) throws M68kException {
		boolean hadControl=focusedControlItem(dialog)>0;
		if(hadControl){int savedPort=tb.api.currentQuickDrawPort();setPort(dialog.window);try{tb.controls.setKeyboardFocus(dialog.window,0,0);}finally{setPort(savedPort);}}
		if(hadControl&&dialog.editItem==number){Item item=item(dialog.window,number);if(item!=null&&item.teHandle!=0)tb.textEdit.activate(item.teHandle,true);}
		else switchEditField(dialog,number);
	}
	/** Tab order over the text items: classic edit text items and edit text controls (kControlSupportsFocus), in item order. */
	private int nextTextItem(Dialog dialog,int from,boolean reverse) throws M68kException {
		int n=dialog.list.size();
		if(from<1||from>n)from=reverse?n+1:0; // no edit text (editItem -1, ShowModal's alerts): Tab starts at the first/last item instead of indexing item -1
		for(int k=1;k<=n;k++){int i=reverse?((from-1-k)%n+n)%n+1:((from-1+k)%n)+1;Item item=dialog.list.get(i-1);int kind=item.type&0x7f;if(item.hidden)continue;
			if(kind==EDIT_TEXT)return i;
			if(kind>=CTRL_ITEM&&kind<=RES_CTRL&&item.handle!=0&&tb.controls.nativeDefinition(item.handle) instanceof AppearanceControls68k.Def&&((AppearanceControls68k.Def)tb.controls.nativeDefinition(item.handle)).takesText())return i;}
		return 0;
	}
	private int nextEditItem(Dialog dialog){
		int n=dialog.list.size();for(int k=1;k<=n;k++){int i=((dialog.editItem-1+k)%n)+1;Item item=dialog.list.get(i-1);if((item.type&0x7f)==EDIT_TEXT&&!item.hidden)return i;}return 0;
	}

	private void flash(Item item) throws M68kException {
		if(item.handle==0)return;int d=tb.controls.data(item.handle);if(d==0)return;
		memory.writeByte(d+17,10);tb.controls.draw(item.handle);tb.host().repaintNow(tb.controls.controls.get(item.handle).window);
		try{Thread.sleep(80);}catch(InterruptedException ignored){}
		memory.writeByte(d+17,0);tb.controls.draw(item.handle);
	}

	/** ModalDialog's event mask: everything but OS events and high-level events (see the fetch below). */
	private static final int MODAL_MASK=0xffff&~(1<<EventManager68k.OS_EVT)&~EventManager68k.HIGH_LEVEL_MASK;
	private boolean modalDialog(M68kCpu cpu) throws M68kException {
		int itemHitPtr=memory.readLong(cpu.a[7]),filter=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(!tb.hosted()){memory.writeWord(itemHitPtr,0);return true;} // headless: nothing can be clicked
		int window=tb.windows.frontWindow();Dialog dialog=dialogs.get(window);
		if(dialog==null){for(Dialog d:dialogs.values()){WindowManager68k.Window w=tb.windows.window(d.window);if(w!=null&&w.visible){dialog=d;window=d.window;break;}}}
		if(dialog==null){memory.writeWord(itemHitPtr,0);return true;}
		tb.menus.flushIfDirty();
		// ModalDialog makes the dialog the current port (and leaves it so): filters GlobalToLocal the event's point without a
		// SetPort of their own - PageMaker 6's installer tested list clicks against the main window's coordinates.
		setPort(window);
		int hit=runModal(dialog,filter,false,0,itemHitPtr);
		memory.writeWord(itemHitPtr,hit);return true;
	}

	/** Runs the modal loop for a dialog until an enabled item is hit. */
	/** SetDialogTracksCursor: ModalDialog shows the I-beam over an edit field of the dialog and the arrow elsewhere. */
	private void trackCursor(Dialog dialog) throws M68kCpuException {
		int savedPort=tb.api.currentQuickDrawPort();setPort(dialog.window);boolean overText=false;
		try{int[] m=tb.events.localMouse();
			for(Item item:dialog.list)if(!item.hidden&&(item.type&0x7f)==EDIT_TEXT&&m[0]>=item.rect[1]&&m[0]<item.rect[3]&&m[1]>=item.rect[0]&&m[1]<item.rect[2]){overText=true;break;}}
		finally{setPort(savedPort);}
		if(overText!=dialog.cursorOverText){dialog.cursorOverText=overText;
			tb.host().setCursor(overText?java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR):java.awt.Cursor.getDefaultCursor());}
	}
	/** How long a dialog of an unattended program ({@link MacApp68k#unattended}) stays up before Return is pressed in it. */
	private static final long AUTO_RETURN_NANOS=Long.getLong("hz68k.autoreturn",500)*1_000_000L;
	int runModal(Dialog dialog,int filter) throws M68kException {return runModal(dialog,filter,false,0);}
	int runModal(Dialog dialog,int filter,boolean filterTakesData,int filterData) throws M68kException {return runModal(dialog,filter,filterTakesData,filterData,0);}
	/** As above; with {@code filterTakesData} the filter is a ModalFilterYDProc and {@code filterData} (yourDataPtr, possibly NULL)
	 *  is always passed as its fourth argument — Standard File does so whatever the pointer's value. */
	/** {@code itemHitPtr}: ModalDialog's own VAR itemHit, handed to the filter as on a real Mac (0 = a private word reset to 0 per event). */
	int runModal(Dialog dialog,int filter,boolean filterTakesData,int filterData,int itemHitPtr) throws M68kException {
		int window=dialog.window;
		if(scratchEvent==0){scratchEvent=memory.allocate(16,4);scratchItem=memory.allocate(2,2);}
		int itemPtr=itemHitPtr!=0?itemHitPtr:scratchItem;
		WindowManager68k.Window w=tb.windows.window(window);
		if(w!=null&&!w.visible){tb.windows.show(window,true);}
		tb.windows.select(window);
		// A real ModalDialog draws nothing on entry: the items are drawn for the update event ShowWindow generated (handled
		// below, or here when it is already queued), and a dialog drawn earlier keeps what the program did to it since.
		// Realmz colours its party list by SetIText under changing ForeColors between two ModalDialog calls - an
		// unconditional redraw at entry painted every name black again.
		// With a filter the update event goes through the loop: the filter sees it first (MandleAcid draws its whole
		// black-backed dialog - pictures in user items - there, and stayed black when the items were drawn here).
		if(filter!=0){if(tb.windows.updatePending(window))tb.events.postUpdate(window);}
		// The update region decides, not a queued event: Factory 1.1 draws its calendar's day cells after DrawDialog and empties the
		// region with BeginUpdate/EndUpdate; redrawing for the stale event painted the calendar picture over the cells.
		else{tb.events.takeUpdate(window);if(tb.windows.updatePending(window))drawUpdate(window);}
		// Updates for the application's own windows stay pending until ModalDialog returns (a real ModalDialog ignores them and
		// the update region simply stays dirty); re-posting them at once would spin without ever idling.
		java.util.Set<Integer> deferredUpdates=new java.util.LinkedHashSet<Integer>();
		tb.host().beginModalWindow(window);
		boolean unattended=tb.app!=null&&tb.app.unattended;long autoReturnAt=System.nanoTime()+AUTO_RETURN_NANOS;
		try{
		while(true){
			if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			tb.runEventWork();tb.updateTicks();tb.runTimers();tb.runPeriodic();
				// High-level events stay queued for the program's own event loop; handlers may be installed only after an alert.
			EventManager68k.Event e=tb.events.takeForDialog(MODAL_MASK);
			if(e==null){tb.events.idle(2,MODAL_MASK);e=tb.events.takeForDialog(MODAL_MASK);}
			if(e==null)e=new EventManager68k.Event(EventManager68k.NULL_EVENT);
			// a program run without the user (an expander's installer): once the dialog has been up a moment with nothing to do,
			// Return is pressed - its default button, through the filter as a real key would go
			if(unattended&&e.what==EventManager68k.NULL_EVENT&&System.nanoTime()-autoReturnAt>=0){
				autoReturnAt=System.nanoTime()+AUTO_RETURN_NANOS;
				if(Mac68kApi.TRACE)System.err.println("[68K] unattended: "+(dialog.unattendedItem>0?"item "+dialog.unattendedItem:"Return key")+" in dialog $"+Integer.toHexString(window));
				if(dialog.unattendedItem>0){lastEvent=e;return dialog.unattendedItem;}
				e=new EventManager68k.Event(EventManager68k.KEY_DOWN);e.message=0x24<<8|0x0d;
			}
			if(dialog.tracksCursor)trackCursor(dialog);
			if(e.what==EventManager68k.UPDATE_EVT&&e.message!=window){if(dialogs.containsKey(e.message))draw(e.message);else deferredUpdates.add(e.message);continue;}
			if(e.what==EventManager68k.MOUSE_DOWN&&e.menuId!=0){tb.events.clickTracked();if(e.menuId==-1&&e.window==window&&dialog.cancelItem>0)return dialog.cancelItem;java.awt.Toolkit.getDefaultToolkit().beep();tb.events.pendingMenuEvent=null;if(e.menuId==-1)tb.events.goAwayRefused();continue;}
			if(filter!=0){
				tb.events.writeEventRecord(scratchEvent,e);if(itemHitPtr==0)memory.writeWord(scratchItem,0);
				M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
				// pascal Boolean filter(DialogPtr, EventRecord*, short* item[, void* yourData]): pushed left to right
				cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushLong(window);cpu.pushLong(scratchEvent);cpu.pushLong(itemPtr);if(filterTakesData)cpu.pushLong(filterData);
					// Clear the result slot because filters are not required to write it on every path.
				boolean handled;
				try{cpu.callPascal(filter,filterTakesData?16:12);handled=memory.readByte(cpu.a[7])!=0;cpu.a[7]+=2;}
				finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
					// A TRUE filter result ends ModalDialog whatever itemHit holds (IM I-415); a filter may return TRUE on a
					// click/key without ever touching the item.
				if(Boolean.getBoolean("hz68k.bbdebug")&&(e.what==1||e.what==3))System.err.println("[bbdebug] filter event "+e.what+" msg="+e.message+" handled="+handled+" item="+memory.readWord(itemPtr));
				if(handled){lastEvent=e;return (short)memory.readWord(itemPtr);}
				e=tb.events.readEvent(scratchEvent);
				// The filter answered the update itself (BeginUpdate ... EndUpdate): DrawDialog inside the Dialog Manager's own
				// BeginUpdate is then clipped to an empty region and draws nothing. Drawing the items anyway erased MacBrickout's
				// TextEdit help text under an empty static text item of the same rectangle.
				if(e.what==EventManager68k.UPDATE_EVT&&e.message==window&&!tb.windows.updatePending(window)){lastEvent=e;continue;}
			}
			lastEvent=e;
			int hit=handleEvent(dialog,e,false);
			if(hit>0)return hit;
			if(e.what==EventManager68k.NULL_EVENT&&dialog.nullEvents!=null){hit=dialog.nullEvents.idle();if(hit>0)return hit;}
		}
		}finally{
			tb.host().endModalWindow(window);
			for(int other:deferredUpdates)if(tb.windows.window(other)!=null)tb.events.postUpdate(other);
		}
	}

	// ------------------------------------------------------------------ alerts

	/** DABeeper: the alerts' sound procedure, set by ErrorSound (until then alerts beep through the host). */
	private static final int DA_BEEPER=0xa9c,A_NUMBER=0xa98,A_COUNT=0xa9a;
	private int beeper;private boolean beeperSet;private int standardBeeper;
	/**
	 * DABeeper before any ErrorSound: the standard sound procedure (pascal, the sound number on the stack, that many SysBeeps).
	 * Programs call it themselves - Anarchie 2.0 beeps with `MOVE.L DABeeper,A0; JSR (A0)` - and jumped to 0 while it was NIL.
	 */
	void initializeLowMemory() throws M68kCpuException {
		if(standardBeeper==0)standardBeeper=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu cpu) throws M68kException {
			int n=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;for(int i=0;i<Math.min(3,n);i++)tb.api.soundManager().sysBeep();}});
		if(!beeperSet)memory.writeLong(DA_BEEPER,standardBeeper);
	}

	/** An alert stage's sound (1-3 beeps): through the program's ErrorSound procedure, called with the sound number. */
	private void alertSound(M68kCpu cpu,int sound) throws M68kException {
		if(sound==0)return;
		if(!beeperSet){java.awt.Toolkit.getDefaultToolkit().beep();return;}
		if(beeper==0)return;
		cpu.a[7]-=2;memory.writeWord(cpu.a[7],sound);cpu.callPascal(beeper,2);
	}

	private int alert(M68kCpu cpu,int id,int filter,int kind) throws M68kException {
		ResourceFile68k.Resource alrt=tb.resources.find(0x414c5254,id);
		if(Mac68kApi.TRACE)System.err.println("[dialog] ALRT "+id+(alrt==null?" not found":" from file "+tb.resources.describeFile(alrt)));
		if(alrt==null)return -1;
		byte[] d=tb.resources.currentData(alrt);
		if(d.length<12)return -1;
		int[] bounds={(short)MacApp68k.u2(d,0),(short)MacApp68k.u2(d,2),(short)MacApp68k.u2(d,4),(short)MacApp68k.u2(d,6)};
		int ditlId=(short)MacApp68k.u2(d,8);
		int positioning=d.length>=14?MacApp68k.u2(d,12):0;
		ResourceFile68k.Resource ditl=tb.resources.find(0x4449544c,ditlId);
		if(ditl==null)return -1;
		// alert stages: the same alert again goes one stage further (up to the 4th), ACount/ANumber say where it is; each stage's
		// nibble (stage 1 lowest) is boldItm (bit 3: item 2 is the default), boxDrwn (bit 2: shown at all) and the sound (bits 0-1)
		int stages=d.length>=12?MacApp68k.u2(d,10):0x5555;
		int count=id==(short)memory.readWord(A_NUMBER)?Math.min(3,(short)memory.readWord(A_COUNT)+1):0;
		memory.writeWord(A_NUMBER,id);memory.writeWord(A_COUNT,count);
		int stage=stages>>>(4*count)&15;
		alertSound(cpu,stage&3);
		if((stage&4)==0)return -1;
		int items=tb.api.createHandle(tb.resources.currentData(ditl));
		ResourceFile68k.Resource actb=tb.resources.find(0x61637462,id); // an 'actb' colours the alert as a 'dctb' does a dialog
		int window=create(0,position(bounds,positioning==0?0x300a:positioning),"",false,1,-1,false,0,items,false,actb==null?null:tb.resources.data(actb),actb!=null);
		if(window==0)return -1;
		tb.windows.themeAsAlert(window); // the scheme's alert frame and colours
		if(actb==null)schemeBackground(window,true);
		applyItemStyles(window,ditlId);
		Dialog dialog=dialogs.get(window);dialog.alertKind=kind;dialog.ringDefault=true;
		if((stage&8)!=0){dialog.defaultItem=2;memory.writeWord(window+DEFAULT_ITEM,2);}
		int hit;
		try{hit=runModal(dialog,filter);}
		finally{dispose(window);}
		return hit;
	}

	// ------------------------------------------------------------------ DialogDispatch

	private boolean dialogDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;
		switch(selector){
			case 0x0203:{int procPtr=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(stdFilterGate==0)stdFilterGate=tb.registerGate(new Toolbox68k.Gate(){@Override public void call(M68kCpu c) throws M68kException {standardFilter(c);}});memory.writeLong(procPtr,stdFilterGate);memory.writeWord(cpu.a[7],0);return true;}
			case 0x0304:{int item=(short)memory.readWord(cpu.a[7]),d=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Dialog dialog=dialogs.get(d);if(dialog!=null){dialog.defaultItem=item;dialog.ringDefault=true;memory.writeWord(d+DEFAULT_ITEM,item);}memory.writeWord(cpu.a[7],0);return true;}
			case 0x0305:{int item=(short)memory.readWord(cpu.a[7]),d=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Dialog dialog=dialogs.get(d);if(dialog!=null)dialog.cancelItem=item;memory.writeWord(cpu.a[7],0);return true;}
			case 0x0306:{boolean tracks=memory.readByte(cpu.a[7])!=0;int d=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;Dialog dialog=dialogs.get(d);if(dialog!=null)dialog.tracksCursor=tracks;memory.writeWord(cpu.a[7],0);return true;} // SetDialogTracksCursor(dialog, tracks)
			// private MOVEQ selectors of the System 7.5 Finder, both (long) : Boolean. 8 (with 0) asks whether the user cancelled - true
			// makes it abandon the operation with userCanceledErr, so it answers a queued Command-period only; 7 takes a DialogPtr.
			case 0x0008:{cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],tb.events.commandPeriodQueued(true));return true;}
			case 0x0007:{cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],false);return true;}
			case 0x090e:return standardAlert(cpu);
			// the Mac OS 8 (Appearance) item calls iTunes' Setup Assistant builds its pages with (AppendDialogItemList for the texts)
			case 0x0412:{int method=(short)memory.readWord(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+2),d=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
				ResourceFile68k.Resource ditl=tb.resources.find(0x4449544c,id);int err=0;
				if(ditl==null)err=-192; // resNotFound
				else if(!dialogs.containsKey(d))err=-50;
				else{int h=tb.api.createHandle(tb.resources.currentData(ditl));try{appendItems(d,h,method,id);}finally{tb.api.disposeHandleAndFree(h);}}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x050f:{int out=memory.readLong(cpu.a[7]),number=(short)memory.readWord(cpu.a[7]+4),d=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
				Item item=item(d,number);int kind=item==null?-1:item.type&0x7f;boolean control=kind>=CTRL_ITEM&&kind<=RES_CTRL&&item.handle!=0;
				int handle=control?item.handle:item==null?0:itemControl(dialogs.get(d),item);
				if(out!=0)memory.writeLong(out,handle);
				memory.writeWord(cpu.a[7],item==null?-50:handle!=0?0:-30596);return true;} // errItemNotControl: without the embedding hierarchy only control items are controls
			case 0x0510:case 0x0511:{int b=(short)memory.readWord(cpu.a[7]),a=(short)memory.readWord(cpu.a[7]+2),number=(short)memory.readWord(cpu.a[7]+4),d=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
				Item item=item(d,number);if(item==null){memory.writeWord(cpu.a[7],-50);return true;}
				int[] r=item.rect;int top=r[0],left=r[1],bottom=r[2],right=r[3];
				// a hidden item keeps its 16384-pixel shift, so MoveDialogItem's h is taken relative to the item's visible place
				if(selector==0x0510){int shift=item.hidden?16384:0;right=a+shift+(right-left);bottom=b+(bottom-top);left=a+shift;top=b;}else{right=left+a;bottom=top+b;}
				setItemRect(dialogs.get(d),item,new int[]{top,left,bottom,right});
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x020d:{int d=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeWord(cpu.a[7],dialogs.containsKey(d)?autoSize(d):-50);return true;}
			case 0x110c:{int sp=cpu.a[7];int items=memory.readLong(sp+4),refCon=memory.readLong(sp+8);boolean goAway=memory.readByte(sp+12)!=0;int behind=memory.readLong(sp+14),procId=(short)memory.readWord(sp+18);
				boolean visible=memory.readByte(sp+20)!=0;int title=memory.readLong(sp+22),bounds=memory.readLong(sp+26),storage=memory.readLong(sp+30);cpu.a[7]+=34;
				int window=create(storage,readRect(bounds),tb.readPascal(title),visible,procId,behind,goAway,refCon,items,true,null,true);
				if(window!=0&&(memory.readLong(sp)&2)!=0){dialogs.get(window).embedding=true;tb.appearance.ensureRootControl(window);} // kDialogFlagsUseControlHierarchy
				memory.writeLong(cpu.a[7],window);return true;} // NewFeaturesDialog (otherwise the flags only choose the Appearance look)
			default:cpu.a[7]+=(selector>>>8)*2;memory.writeWord(cpu.a[7],-4);return true;
		}
	}

	/**
	 * StandardAlert(alertType, error, explanation, AlertStdAlertParamPtr, VAR itemHit) : OSErr - the Appearance Manager's alert
	 * built from strings (Dialogs.h, AppearanceLib/CarbonLib): the icon of the alert type (stop 0, note 1, caution 2, plain 3),
	 * the error text in the emphasized system font, the explanation in the small system font, buttons OK (item 1), Cancel (2),
	 * Other (3) and Help (4) - the item numbers stay fixed whichever buttons exist. The parameter record (NIL = an OK button
	 * only): movable, helpButton, filterProc, defaultText/cancelText/otherText (NIL = no button, -1 = the standard title),
	 * defaultButton, cancelButton, position. Sherlock 2 and Carbon programs report their start-up problems with it.
	 */
	private boolean standardAlert(M68kCpu cpu) throws M68kException {
		int hitPtr=memory.readLong(cpu.a[7]),param=memory.readLong(cpu.a[7]+4),explanation=memory.readLong(cpu.a[7]+8),error=memory.readLong(cpu.a[7]+12);
		int type=(short)memory.readWord(cpu.a[7]+16);cpu.a[7]+=18;
		boolean movable=false,help=false;int filter=0,defaultText=-1,cancelText=0,otherText=0,defaultButton=1,cancelButton=0;
		if(param!=0){movable=memory.readByte(param)!=0;help=memory.readByte(param+1)!=0;filter=memory.readLong(param+2);
			defaultText=memory.readLong(param+6);cancelText=memory.readLong(param+10);otherText=memory.readLong(param+14);
			defaultButton=(short)memory.readWord(param+18);cancelButton=(short)memory.readWord(param+20);}
		boolean japanese=tb.encoding==StackTextEncoding.JAPANESE;
		String[] standardTitles=japanese?new String[]{"OK","キャンセル","保存しない","?"}:new String[]{"OK","Cancel","Don't Save","?"};
		if(defaultText==0&&cancelText==0&&otherText==0)defaultText=-1; // never an alert without a way out
		int[] texts={defaultText,cancelText,otherText,help?-1:0};
		byte[][] titles=new byte[4][];
		for(int i=0;i<4;i++){int t=texts[i];if(t==0)continue;titles[i]=t==-1?tb.encoding.encodeReplacing(standardTitles[i]):tb.readPascalBytes(t);
			if(titles[i].length>0&&t!=-1)titles[i]=java.util.Arrays.copyOfRange(titles[i],1,titles[i].length);}
		byte[] errorText=error==0?new byte[0]:java.util.Arrays.copyOfRange(tb.readPascalBytes(error),1,memory.readByte(error)+1);
		byte[] explanationText=explanation==0?new byte[0]:java.util.Arrays.copyOfRange(tb.readPascalBytes(explanation),1,memory.readByte(explanation)+1);
		// layout of the Mac OS 8 alerts: 32-pixel icon at (10,20), text from x=74, buttons 20 high along the bottom right
		int width=400,textLeft=type==3?20:74,textWidth=width-textLeft-20;
		int errorSize=12,explanationSize=japanese?12:10;
		java.awt.Font errorFont=Fonts68k.font(0,errorSize,1),explanationFont=Fonts68k.font(1,explanationSize,0);
		int[] ef=Fonts68k.fontInfo(errorFont),xf=Fonts68k.fontInfo(explanationFont);
		int errorHeight=errorText.length==0?0:tb.textEdit.lineCount(errorText,errorFont,textWidth)*(ef[0]+ef[1]+ef[3]);
		int explanationHeight=explanationText.length==0?0:tb.textEdit.lineCount(explanationText,explanationFont,textWidth)*(xf[0]+xf[1]+xf[3]);
		int top=10,errorTop=top,explanationTop=errorTop+errorHeight+(errorHeight>0&&explanationHeight>0?8:0);
		int textBottom=Math.max(type==3?0:top+32,explanationTop+explanationHeight);
		int buttonTop=textBottom+16,height=buttonTop+20+14;
		java.awt.Font buttonFont=Fonts68k.font(0,12,0);
		int[][] rects=new int[4][];int right=width-16;
		for(int i:new int[]{0,1}){if(titles[i]==null)continue;int w=Math.max(69,Fonts68k.width(buttonFont,tb.decode(titles[i]))+20);rects[i]=new int[]{buttonTop,right-w,buttonTop+20,right};right-=w+12;}
		int left=help?54:textLeft;
		if(titles[2]!=null){int w=Math.max(69,Fonts68k.width(buttonFont,tb.decode(titles[2]))+20);
			if(left+w>right)left=Math.max(10,right-w);rects[2]=new int[]{buttonTop,left,buttonTop+20,left+w};}
		if(help)rects[3]=new int[]{buttonTop,16,buttonTop+20,36};
		java.io.ByteArrayOutputStream ditl=new java.io.ByteArrayOutputStream();
		ditl.write(0);ditl.write(5); // 6 items
		for(int i=0;i<4;i++){
			if(rects[i]==null){ditlItem(ditl,new int[]{0,0,0,0},USER_ITEM|ITEM_DISABLE,new byte[0]);continue;}
			ditlItem(ditl,rects[i],BTN_CTRL,titles[i]);}
		ditlItem(ditl,new int[]{errorTop,textLeft,errorTop+errorHeight,textLeft+textWidth},STAT_TEXT|ITEM_DISABLE,errorText);
		ditlItem(ditl,new int[]{explanationTop,textLeft,explanationTop+explanationHeight,textLeft+textWidth},STAT_TEXT|ITEM_DISABLE,explanationText);
		int items=tb.api.createHandle(ditl.toByteArray());
		int window=create(0,position(new int[]{0,0,height,width},0x300a),"",false,movable?5:1,-1,false,0,items,false,null,true);
		if(window==0){memory.writeWord(cpu.a[7],-108);return true;} // memFullErr
		Dialog dialog=dialogs.get(window);
		dialog.list.get(4).textStyle=new int[]{7,0,1,errorSize,0};
		dialog.list.get(5).textStyle=new int[]{7,1,0,explanationSize,0};
		dialog.alertKind=type==0?1:type==1?2:type==2?3:-1;dialog.ringDefault=true;
		dialog.defaultItem=defaultButton>=1&&defaultButton<=4&&rects[defaultButton-1]!=null?defaultButton:0;memory.writeWord(window+DEFAULT_ITEM,Math.max(1,dialog.defaultItem));
		dialog.cancelItem=cancelButton>=1&&cancelButton<=4&&rects[cancelButton-1]!=null?cancelButton:0;
		int hit;
		try{do hit=runModal(dialog,filter);while(hit<1||hit>4||rects[hit-1]==null);}
		finally{dispose(window);}
		if(hitPtr!=0)memory.writeWord(hitPtr,hit);
		memory.writeWord(cpu.a[7],0);return true;
	}
	private static void ditlItem(java.io.ByteArrayOutputStream out,int[] rect,int type,byte[] data){
		for(int i=0;i<4;i++)out.write(0);
		for(int v:rect){out.write(v>>>8&0xff);out.write(v&0xff);}
		int n=Math.min(255,data.length);out.write(type);out.write(n);out.write(data,0,n);if((n&1)!=0)out.write(0);
	}

	/** The standard filter procedure handed out by GetStdFilterProc: pascal Boolean(DialogPtr, EventRecord*, short* item). */
	private void standardFilter(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7];int itemPtr=memory.readLong(sp),eventPtr=memory.readLong(sp+4),window=memory.readLong(sp+8);
		Dialog dialog=dialogs.get(window);boolean handled=false;
		if(dialog!=null){EventManager68k.Event e=tb.events.readEvent(eventPtr);
			if(e.what==EventManager68k.KEY_DOWN||e.what==EventManager68k.AUTO_KEY){int ch=e.message&0xff;
				if((ch==0x0d||ch==0x03)&&dialog.defaultItem>0){Item d=item(window,dialog.defaultItem);if(d!=null)flash(d);memory.writeWord(itemPtr,dialog.defaultItem);handled=true;}
				else if((ch==0x1b||((e.modifiers&0x100)!=0&&ch=='.'))&&dialog.cancelItem>0){Item c=item(window,dialog.cancelItem);if(c!=null)flash(c);memory.writeWord(itemPtr,dialog.cancelItem);handled=true;}}}
		cpu.a[7]+=12;tb.writeBoolean(cpu.a[7],handled);
	}

	private int[] readRect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	private void writeRect(int p,int[] r) throws M68kCpuException {memory.writeWord(p,r[0]);memory.writeWord(p+2,r[1]);memory.writeWord(p+4,r[2]);memory.writeWord(p+6,r[3]);}
}

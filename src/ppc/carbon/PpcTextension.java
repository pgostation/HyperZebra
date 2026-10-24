import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multilingual Text Engine (Textension, MacTextEditor.h) for PowerPC programs: each TXNObject is a TextEdit record in its
 * window, so the text draws, edits and scrolls with the same TextEdit the 68K layer gives every program. Offsets are
 * MLTE's (UniChars); the record holds the text in the program's encoding and the two are converted at the calls. Styles
 * set with TXNSetTypeAttributes apply to the whole object (a monostyled record), undo is not kept, and there are no
 * scroll bars. Acrobat Reader 5.0's ADM Plugin builds its edit fields with it and called TXNInitTextension without
 * checking the weak import.
 * <p>
 * Documents load from and save to open files as plain text (UTF-16 for 'utxt'), print through the classic Printing
 * Manager (the text wrapped to the page), and font menu objects are the program's menu filled with the installed
 * families (AppendResMenu), checked and chosen like MLTE's. Text boxes (TXNDrawUnicodeTextBox) are ATSUI's
 * ({@link PpcAtsui#drawTextBox}). Lines, offsets and points, view and destination rectangles map onto the record's own
 * line starts and TEGetPoint/TEGetOffset.
 */
final class PpcTextension {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcTextension(PpcRuntime rt){this.rt=rt;memory=rt.memory;}

	private static final int NO_ERR=0,PARAM_ERR=-50,EOF_ERR=-39,USER_CANCELED=-22004,RUN_INDEX_OUT_OF_BOUNDS=-22007,NO_MATCH=-22008,TAG_INVALID_FOR_RUN=-22009,
		DATA_TYPE_NOT_ALLOWED=-22015,DRAG_NOT_ACCEPTED=-1857;
	private static final int TEXT='T'<<24|'E'<<16|'X'<<8|'T',UTXT='u'<<24|'t'<<16|'x'<<8|'t',RTF='R'<<24|'T'<<16|'F'<<8|' ';
	private static final int USE_CURRENT_SELECTION=0xffffffff,END_OFFSET=0x7fffffff;
	/** TERec fields: destRect, viewRect, selStart, selEnd, teLength, hText, txFont, txFace, txSize. */
	private static final int TE_DEST=0,TE_VIEW=8,TE_SEL_START=32,TE_SEL_END=34,TE_LENGTH=60,TE_TEXT=62,TE_FONT=74,TE_FACE=76,TE_SIZE=80;

	private static final class Txn {
		int window,te,changes;boolean readOnly,focused;
		/** The vertical scroll bar control (kTXNWantVScrollBarMask), 0 without one. */
		int vScroll;
		/** TXNSetBackground's colour (6 bytes RGBColor), null: the port's background. */
		byte[] background;final Map<Integer,Integer> controls=new HashMap<Integer,Integer>();
		/** Action counts since cleared: text input (typing, cut, paste, clear, data set) and run changes (type attributes). */
		int inputCount,runCount;
		/** The text as last saved or loaded, for TXNRevert. */
		String saved="";
		/** TXNSetFontDefaults' TXNMacOSPreferredFontDescription entries (14 bytes each). */
		byte[] fontDefaults=new byte[0];
		/** The TPrint record TXNPageSetup and TXNPrint share. */
		int print;
		/** Whether the last change was typing (TXNIsTyping): a key went in and nothing else changed the text or selection since. */
		boolean typing;
		/** TXNSetDataFilter's filter UPP and refCon (kept and handed back; see there). */
		int dataFilter,dataFilterRefCon;
	}
	/** A font menu object: the program's menu, filled with the font families. */
	private static final class FontMenu {final int menu,menuID;FontMenu(int menu,int menuID){this.menu=menu;this.menuID=menuID;}}
	private final Map<Integer,FontMenu> fontMenus=new HashMap<Integer,FontMenu>();
	/** Objects by the guest block the program holds as its TXNObject. */
	private final Map<Integer,Txn> objects=new HashMap<Integer,Txn>();
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private Txn txn(int ref){return objects.get(ref);}
	/** A key event typed while {@code window} has a focused, editable object: TXNKeyDown on it; false when there is none. */
	boolean keyToFocused(int window,int event) throws M68kException {
		for(Txn t:objects.values())if(t.window==window&&t.focused&&!t.readOnly){
			inPort(t,()->{rt.toolbox("TEKey",memory.readLong(event+2)&0xff,t.te);t.changes++;t.inputCount++;t.typing=true;});return true;}
		return false;
	}

	void define(PpcLibraries l){
		l.register(null,"TXNInitTextension",(r,c)->ret(NO_ERR));
		l.register(null,"TXNTerminateTextension",(r,c)->{});
		l.register(null,"TXNVersionInformation",(r,c)->{if(arg(0)!=0)memory.writeLong(arg(0),0);ret(0x01308000);}); // 1.3, no feature bits
		l.register(null,"TXNNewObject",(r,c)->{ // (fileSpec, window, frame, frameOptions, frameType, fileType, encoding, *object, *frameID, refCon)
			int window=arg(1),frame=arg(2);if(window==0){ret(PARAM_ERR);return;}
			int rect=rt.allocate(8,2);
			if(frame!=0)memory.copy(frame,rect,8);else memory.copy(window+16,rect,8); // the window's port rect
			int saved=port();rt.toolbox("SetPort",window);
			int te=rt.toolbox("TENew",rect,rect);
			rt.toolbox("SetPort",saved);memory.free(rect);
			if(te==0){ret(-108);return;}
			Txn t=new Txn();t.window=window;t.te=te;t.readOnly=(arg(3)&(1<<5))!=0;
			if((arg(3)&WANT_V_SCROLL_BAR)!=0)addScrollBar(t);
			int ref=rt.allocate(16,4);objects.put(ref,t);
			if(arg(7)!=0)memory.writeLong(arg(7),ref);if(arg(8)!=0)memory.writeLong(arg(8),1);ret(NO_ERR);});
		l.register(null,"TXNDeleteObject",(r,c)->{Txn t=objects.remove(arg(0));if(t==null)return;if(t.vScroll!=0)rt.toolbox("DisposeControl",t.vScroll);rt.toolbox("TEDispose",t.te);memory.free(arg(0));});
		l.register(null,"TXNSetFrameBounds",(r,c)->{Txn t=txn(arg(0));if(t==null)return; // (object, top, left, bottom, right, frameID)
			int p=memory.readLong(t.te);for(int at:new int[]{TE_DEST,TE_VIEW}){memory.writeWord(p+at,arg(1));memory.writeWord(p+at+2,arg(2));memory.writeWord(p+at+4,arg(3));memory.writeWord(p+at+6,arg(4));}
			if(t.vScroll!=0)layoutScrollBar(t);else inPort(t,()->rt.toolbox("TECalText",t.te));});
		l.register(null,"TXNGetViewRect",(r,c)->{Txn t=txn(arg(0));if(t!=null&&arg(1)!=0)memory.copy(memory.readLong(t.te)+TE_VIEW,arg(1),8);});
		l.register(null,"TXNDraw",(r,c)->{Txn t=txn(arg(0));if(t==null)return;inPort(t,()->{int view=memory.readLong(t.te)+TE_VIEW;int rect=rt.allocate(8,2);memory.copy(view,rect,8);rt.toolbox("EraseRect",rect);rt.toolbox("TEUpdate",rect,t.te);memory.free(rect);});
			if(t.vScroll!=0){syncScroll(t);inPort(t,()->rt.toolbox("Draw1Control",t.vScroll));}});
		l.register(null,"TXNUpdate",(r,c)->{Txn t=txn(arg(0));if(t==null)return;inPort(t,()->{rt.toolbox("BeginUpdate",t.window);int rect=rt.allocate(8,2);memory.copy(memory.readLong(t.te)+TE_VIEW,rect,8);rt.toolbox("TEUpdate",rect,t.te);memory.free(rect);rt.toolbox("EndUpdate",t.window);});});
		l.register(null,"TXNForceUpdate",(r,c)->{Txn t=txn(arg(0));if(t!=null)inPort(t,()->{int rect=rt.allocate(8,2);memory.copy(memory.readLong(t.te)+TE_VIEW,rect,8);rt.toolbox("InvalRect",rect);memory.free(rect);});});
		l.register(null,"TXNClick",(r,c)->{Txn t=txn(arg(0));int e=arg(1);if(t==null||e==0)return; // (object, EventRecord*): where is global
			inPort(t,()->{int pt=rt.allocate(4,2);memory.writeLong(pt,memory.readLong(e+10));rt.toolbox("GlobalToLocal",pt);int where=memory.readLong(pt);memory.free(pt);
				if(inScrollBar(t,where))scrollClick(t,where);else rt.toolbox("TEClick",where,(memory.readWord(e+14)&0x200)!=0?1:0,t.te);});
			t.typing=false;syncScroll(t);});
		l.register(null,"TXNKeyDown",(r,c)->{Txn t=txn(arg(0));int e=arg(1);if(t==null||e==0||t.readOnly)return;
			inPort(t,()->{rt.toolbox("TEKey",memory.readLong(e+2)&0xff,t.te);t.changes++;t.inputCount++;t.typing=true;});syncScroll(t);});
		l.register(null,"TXNIdle",(r,c)->{Txn t=txn(arg(0));if(t!=null&&t.focused)inPort(t,()->rt.toolbox("TEIdle",t.te));});
		l.register(null,"TXNAdjustCursor",(r,c)->{});
		l.register(null,"TXNActivate",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}ret(NO_ERR);}); // scroll bar state: there are none
		l.register(null,"TXNFocus",(r,c)->{Txn t=txn(arg(0));if(t==null)return;t.focused=(arg(1)&0xff)!=0;inPort(t,()->rt.toolbox(t.focused?"TEActivate":"TEDeactivate",t.te));});
		l.register(null,"TXNSetData",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;} // (object, dataType, data, size, start, end)
			int type=arg(1),data=arg(2),size=arg(3);
			String text=data==0||size<=0?"":type==UTXT?new String(memory.readBytes(data,size&~1),StandardCharsets.UTF_16BE):type==RTF?rtfText(memory.readBytes(data,size)):rt.toolbox.decode(memory.readBytes(data,size));
			setData(t,text,arg(4),arg(5));ret(NO_ERR);});
		l.register(null,"TXNGetDataEncoded",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(3)==0){ret(PARAM_ERR);return;} // (object, start, end, *handle, encoding)
			String s=text(t);int[] cr=charRange(t,s,arg(1),arg(2));String part=s.substring(cr[0],cr[1]);
			byte[] b=arg(4)==UTXT?part.getBytes(java.nio.charset.StandardCharsets.UTF_16BE):rt.toolbox.encoding.encodeReplacing(part);
			memory.writeLong(arg(3),rt.toolbox.api.createHandle(b));ret(NO_ERR);});
		l.register(null,"TXNGetData",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(3)==0){ret(PARAM_ERR);return;} // (object, start, end, *handle): Unicode
			String s=text(t);int[] cr=charRange(t,s,arg(1),arg(2));memory.writeLong(arg(3),rt.toolbox.api.createHandle(s.substring(cr[0],cr[1]).getBytes(java.nio.charset.StandardCharsets.UTF_16BE)));ret(NO_ERR);});
		l.register(null,"TXNDataSize",(r,c)->{Txn t=txn(arg(0));ret(t==null?0:text(t).length()*2);});
		l.register(null,"TXNGetSelection",(r,c)->{Txn t=txn(arg(0));if(t==null)return;int p=memory.readLong(t.te);String s=text(t);
			if(arg(1)!=0)memory.writeLong(arg(1),charOffset(t,s,memory.readWord(p+TE_SEL_START)));if(arg(2)!=0)memory.writeLong(arg(2),charOffset(t,s,memory.readWord(p+TE_SEL_END)));});
		l.register(null,"TXNSetSelection",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}int[] range=byteRange(t,arg(1),arg(2));inPort(t,()->rt.toolbox("TESetSelect",range[0],range[1],t.te));t.typing=false;ret(NO_ERR);});
		l.register(null,"TXNSelectAll",(r,c)->{Txn t=txn(arg(0));if(t!=null)inPort(t,()->rt.toolbox("TESetSelect",0,32767,t.te));});
		l.register(null,"TXNShowSelection",(r,c)->{Txn t=txn(arg(0));if(t!=null){inPort(t,()->rt.toolbox("TESelView",t.te));syncScroll(t);}});
		l.register(null,"TXNIsSelectionEmpty",(r,c)->{Txn t=txn(arg(0));int p=t==null?0:memory.readLong(t.te);ret(p==0||memory.readWord(p+TE_SEL_START)==memory.readWord(p+TE_SEL_END)?1:0);});
		l.register(null,"TXNCut",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if(!t.readOnly){inPort(t,()->rt.toolbox("TECut",t.te));t.changes++;t.inputCount++;t.typing=false;}ret(NO_ERR);});
		l.register(null,"TXNCopy",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}inPort(t,()->rt.toolbox("TECopy",t.te));ret(NO_ERR);});
		l.register(null,"TXNPaste",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if(t.readOnly){ret(NO_ERR);return;}
			// text that is an RTF document goes in as its text, as MLTE 1.3 reads it: Painter 8's VISE installer puts its 64 KB RTF
			// licence on the scrap as 'TEXT' and pastes it (TEScrpLength, a word, also cut it to its first 620 bytes)
			String rtf=scrapRtf();if(rtf!=null){setData(t,rtf,USE_CURRENT_SELECTION,0);ret(NO_ERR);return;}
			inPort(t,()->{rt.libraries.invoke("TEFromScrap");rt.toolbox("TEPaste",t.te);});t.changes++;t.inputCount++;t.typing=false;syncScroll(t);ret(NO_ERR);});
		l.register(null,"TXNClear",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if(!t.readOnly){inPort(t,()->rt.toolbox("TEDelete",t.te));t.changes++;t.inputCount++;t.typing=false;}ret(NO_ERR);});
		// TEToScrap/TEFromScrap are interface glue, not traps: the natives in PpcLibraries (Painter 8's VISE installer stopped with
		// "no 68K interface for TEFromScrap" on its first activate)
		l.register(null,"TXNConvertToPublicScrap",(r,c)->ret(rt.libraries.invoke("TEToScrap")));
		l.register(null,"TXNConvertFromPublicScrap",(r,c)->ret(rt.libraries.invoke("TEFromScrap")));
		l.register(null,"TXNIsScrapPastable",(r,c)->{int size=rt.allocate(4,4);int n=rt.toolbox("GetScrap",0,TEXT,size);memory.free(size);ret(n>0?1:0);});
		l.register(null,"TXNCanUndo",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(0);});
		l.register(null,"TXNCanRedo",(r,c)->{if(arg(1)!=0)memory.writeLong(arg(1),0);ret(0);});
		l.register(null,"TXNUndo",(r,c)->{});
		l.register(null,"TXNRedo",(r,c)->{});
		l.register(null,"TXNGetChangeCount",(r,c)->{Txn t=txn(arg(0));ret(t==null?0:t.changes);});
		l.register(null,"TXNSetTXNObjectControls",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;} // (object, clearAll, count, tags[], data[])
			if((arg(1)&0xff)!=0)t.controls.clear();
			for(int i=0;i<arg(2);i++){int tag=memory.readLong(arg(3)+4*i),v=memory.readLong(arg(4)+4*i);t.controls.put(tag,v);
				if(tag==('n'<<24|'u'<<16|'i'<<8|'o')||tag==('i'<<24|'o'<<16|'p'<<8|'v'))t.readOnly=v!=0;}                     // kTXNNoUserIOTag, kTXNIOPrivilegesTag
			ret(NO_ERR);});
		l.register(null,"TXNGetTXNObjectControls",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}
			for(int i=0;i<arg(1);i++){Integer v=t.controls.get(memory.readLong(arg(2)+4*i));memory.writeLong(arg(3)+4*i,v==null?0:v);}ret(NO_ERR);});
		l.register(null,"TXNSetBackground",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;} // (object, TXNBackground* {bgType, RGBColor})
			int b=arg(1);t.background=b!=0&&memory.readLong(b)==1?memory.readBytes(b+4,6):null;ret(NO_ERR);}); // kTXNBackgroundTypeRGB: the text is drawn on that colour (ADM's white fields on a grey dialog)
		l.register(null,"TXNSetTypeAttributes",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;} // (object, count, TXNTypeAttributes[] {tag, size, data}, start, end): the whole object
			int p=memory.readLong(t.te);
			for(int i=0;i<arg(1);i++){int a=arg(2)+12*i,tag=memory.readLong(a),data=memory.readLong(a+8);
				if(PpcRuntime.TRACE)System.err.printf("[ppc]   type attribute '%s' size %d data %08X%n",AppleEvents68k.fourcc(tag),memory.readLong(a+4),data);
				if(tag==SIZE)memory.writeWord(p+TE_SIZE,Math.max(1,data>>>16));                                  // Fixed
				else if(tag==FONT)memory.writeWord(p+TE_FONT,data&0xffff);
				else if(tag==FACE)memory.writeByte(p+TE_FACE,data&0xff);
				else if(tag==FONT_NAME&&data!=0){int num=rt.allocate(2,2);rt.toolbox("GetFNum",data,num);memory.writeWord(p+TE_FONT,memory.readWord(num));memory.free(num);}}
			restyle(t);t.runCount++;t.changes++;t.typing=false;ret(NO_ERR);});
		l.register(null,"TXNSetFontDefaults",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}t.fontDefaults=arg(2)==0?new byte[0]:memory.readBytes(arg(2),14*Math.max(0,arg(1)));ret(NO_ERR);});
		l.register(null,"TXNGetFontDefaults",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(1)==0){ret(PARAM_ERR);return;} // (object, *ioCount, defaults[] or NULL for the count)
			int n=t.fontDefaults.length/14;if(arg(2)!=0){int k=Math.min(n,memory.readLong(arg(1)));memory.load(arg(2),java.util.Arrays.copyOf(t.fontDefaults,14*k));n=k;}
			memory.writeLong(arg(1),n);ret(NO_ERR);});
		l.register(null,"TXNEchoMode",(r,c)->ret(NO_ERR));
		l.register(null,"TXNResizeFrame",(r,c)->{Txn t=txn(arg(0));if(t==null)return;int p=memory.readLong(t.te);
			for(int at:new int[]{TE_DEST,TE_VIEW}){memory.writeWord(p+at+4,(short)memory.readWord(p+at)+arg(2));memory.writeWord(p+at+6,(short)memory.readWord(p+at+2)+arg(1));}
			if(t.vScroll!=0)layoutScrollBar(t);else inPort(t,()->rt.toolbox("TECalText",t.te));});
		defineExtras(l);
	}

	private static final int FONT='f'<<24|'o'<<16|'n'<<8|'t',SIZE='s'<<24|'i'<<16|'z'<<8|'e',FACE='f'<<24|'a'<<16|'c'<<8|'e',FONT_NAME='f'<<24|'n'<<16|'t'<<8|'n',
		COLOR='k'<<24|'l'<<16|'o'<<8|'r',ENCODING='e'<<24|'n'<<16|'c'<<8|'d';

	/** MLTE beyond the basics: windows, runs, find, files, printing, font menus, action counts, text boxes. */
	private void defineExtras(PpcLibraries l){
		// windows
		l.register(null,"TXNAttachObjectToWindow",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(1)==0){ret(PARAM_ERR);return;} // (object, GWorldPtr window, isActualWindow)
			t.window=arg(1);memory.writeLong(memory.readLong(t.te)+TE_IN_PORT,arg(1));ret(NO_ERR);});
		l.register(null,"TXNIsObjectAttachedToWindow",(r,c)->{Txn t=txn(arg(0));ret(t!=null&&t.window!=0?1:0);});
		l.register(null,"TXNIsObjectAttachedToSpecificWindow",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(2)==0){ret(PARAM_ERR);return;}memory.writeByte(arg(2),t.window!=0&&t.window==arg(1)?1:0);ret(NO_ERR);});
		l.register(null,"TXNGrowWindow",(r,c)->{Txn t=txn(arg(0));int e=arg(1);if(t==null||e==0)return; // (object, EventRecord*): the size box, then the frame fills the window
			int limits=rt.allocate(8,2);memory.writeWord(limits,64);memory.writeWord(limits+2,64);memory.writeWord(limits+4,32767);memory.writeWord(limits+6,32767);
			int size=rt.toolbox("GrowWindow",t.window,memory.readLong(e+10),limits);memory.free(limits);
			if(size!=0){rt.toolbox("SizeWindow",t.window,size&0xffff,size>>>16,1);fillWindow(t);}});
		l.register(null,"TXNZoomWindow",(r,c)->{Txn t=txn(arg(0));if(t==null)return; // (object, part): after the program's TrackBox
			inPort(t,()->{int rect=rt.allocate(8,2);memory.copy(t.window+16,rect,8);rt.toolbox("EraseRect",rect);memory.free(rect);rt.toolbox("ZoomWindow",t.window,(short)arg(1),0);});fillWindow(t);});
		l.register(null,"TXNGetSleepTicks",(r,c)->{Txn t=txn(arg(0));int p=t==null?0:memory.readLong(t.te); // the caret blink while focused with an insertion point
			ret(t!=null&&t.focused&&memory.readWord(p+TE_SEL_START)==memory.readWord(p+TE_SEL_END)?memory.readLong(0x2f4):0xffffffff);});
		l.register(null,"TXNTSMCheck",(r,c)->ret(0)); // Carbon delivers input methods' text through events: nothing to hand over here
		l.register(null,"TXNDragTracker",(r,c)->ret(DRAG_NOT_ACCEPTED)); // no drag and drop into the text
		l.register(null,"TXNDragReceiver",(r,c)->ret(DRAG_NOT_ACCEPTED));
		// runs: the object is monostyled, so one run covers any range
		l.register(null,"TXNCountRunsInRange",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if(arg(3)!=0)memory.writeLong(arg(3),text(t).isEmpty()?0:1);ret(NO_ERR);});
		// TXNGetIndexedRunInfoFromRange(object, index, start, end, *runStart, *runEnd, *dataType, attributeCount, TXNTypeAttributes[])
		l.register(null,"TXNGetIndexedRunInfoFromRange",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if(arg(1)!=0){ret(RUN_INDEX_OUT_OF_BOUNDS);return;}
			String s=text(t);int[] cr=charRange(t,s,arg(2),arg(3));if(arg(4)!=0)memory.writeLong(arg(4),cr[0]);if(arg(5)!=0)memory.writeLong(arg(5),cr[1]);if(arg(6)!=0)memory.writeLong(arg(6),TEXT);
			ret(typeAttributes(t,arg(7),arg(8)));});
		l.register(null,"TXNGetContinuousTypeAttributes",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;} // (object, *continuousFlags, count, attributes[])
			if(arg(1)!=0)memory.writeLong(arg(1),0xf);ret(arg(3)==0?NO_ERR:typeAttributes(t,arg(2),arg(3)));}); // font, size, style and colour are continuous everywhere
		// TXNFind(object, TXNMatchTextRecord*, dataType, options, start, end, findProc, refCon, *matchStart, *matchEnd)
		l.register(null,"TXNFind",(r,c)->{Txn t=txn(arg(0));int match=arg(1);if(t==null||match==0){ret(PARAM_ERR);return;}
			int dataType=arg(2);if(dataType!=TEXT&&dataType!=UTXT){ret(DATA_TYPE_NOT_ALLOWED);return;}
			byte[] want=memory.readBytes(memory.readLong(match),Math.max(0,memory.readLong(match+4)));int encoding=memory.readLong(match+8);
			String needle=rt.libraries.textServices.decode(encoding,want),s=text(t);int[] cr=charRange(t,s,arg(4),arg(5));int options=arg(3);
			if(arg(6)!=0){ // the program's own matcher gets the text as UTF-16
				byte[] b=s.getBytes(StandardCharsets.UTF_16BE);int buf=rt.allocate(b.length+2,2),out=rt.allocate(12,4);memory.load(buf,b);
				int err=rt.callUniversal(arg(6),0x0ffffff0,new int[]{match,dataType,options,buf,0x100,cr[0],b.length,out,out+4,out+8,arg(7)});
				boolean found=memory.readByte(out+8)!=0;if(found){if(arg(8)!=0)memory.writeLong(arg(8),memory.readLong(out));if(arg(9)!=0)memory.writeLong(arg(9),memory.readLong(out+4));}
				memory.free(buf);memory.free(out);ret(err!=0?err:found?NO_ERR:NO_MATCH);return;}
			int at=find(s.substring(0,cr[1]),needle,cr[0],(options&1)!=0,(options&2)!=0);
			if(at<0){ret(NO_MATCH);return;}
			if(arg(8)!=0)memory.writeLong(arg(8),at);if(arg(9)!=0)memory.writeLong(arg(9),at+needle.length());ret(NO_ERR);});
		// files: TXNSetDataFromFile(object, refNum, fileType, length, start, end) reads from the file's mark; TXNSave(object,
		// type, resType, encoding, FSSpec*, dataRefNum, resRefNum) writes the text from the start of the data fork
		l.register(null,"TXNSetDataFromFile",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}int type=arg(2);if(type!=TEXT&&type!=UTXT&&type!=RTF){ret(DATA_TYPE_NOT_ALLOWED);return;}
			int[] mark=fileIo(0xa018,arg(1),0,0,0,0),eof=fileIo(0xa011,arg(1),0,0,0,0);if(mark[0]!=0||eof[0]!=0){ret(mark[0]!=0?mark[0]:eof[0]);return;} // _GetFPos, _GetEOF
			int n=Math.max(0,eof[2]-mark[3]);if(arg(3)!=END_OFFSET)n=Math.min(n,arg(3));
			int buf=rt.allocate(n+2,2);int[] read=fileIo(0xa002,arg(1),buf,n,0,0);byte[] b=memory.readBytes(buf,read[1]);memory.free(buf); // _Read from the mark
			if(read[0]!=0&&read[0]!=EOF_ERR){ret(read[0]);return;}
			String s;if(type==UTXT){int from=b.length>=2&&(b[0]&255)==0xfe&&(b[1]&255)==0xff?2:0;s=new String(b,from,(b.length-from)&~1,StandardCharsets.UTF_16BE);}else s=type==RTF?rtfText(b):rt.toolbox.decode(b);
			setData(t,s,arg(4),arg(5));t.saved=text(t);t.changes=0;ret(NO_ERR);});
		l.register(null,"TXNSave",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}int ref=(short)arg(5);
			String s=text(t);byte[] b=arg(1)==UTXT||arg(3)==2?s.getBytes(StandardCharsets.UTF_16BE):rt.toolbox.encoding.encodeReplacing(s); // 'utxt' or kTXNUnicodeEncoding
			int buf=rt.allocate(b.length+2,2);memory.load(buf,b);int[] w=fileIo(0xa003,ref,buf,b.length,1,0);memory.free(buf); // _Write from the start (fsFromStart)
			if(w[0]!=0){ret(w[0]);return;}int[] e=fileIo(0xa012,ref,0,0,0,0,b.length);if(e[0]!=0){ret(e[0]);return;} // _SetEOF
			t.saved=text(t);t.changes=0;ret(NO_ERR);});
		l.register(null,"TXNRevert",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}setData(t,t.saved,0,END_OFFSET);t.changes=0;ret(NO_ERR);});
		// printing through the Printing Manager: the text wrapped to the page, in the object's font
		l.register(null,"TXNPageSetup",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}
			rt.toolbox("PrOpen");int err=(short)rt.toolbox("PrError");if(err!=0){ret(err);return;}
			preparePrint(t);boolean ok=rt.toolbox("PrStlDialog",t.print)!=0;rt.toolbox("PrClose");ret(ok?NO_ERR:USER_CANCELED);});
		l.register(null,"TXNPrint",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}ret(print(t));});
		// font menus: the program's menu gets the families; the object's font is checked; choosing one sets it
		l.register(null,"TXNNewFontMenuObject",(r,c)->{int menu=arg(0);if(menu==0||arg(3)==0){ret(PARAM_ERR);return;} // (MenuRef, menuID, startHierMenuID, *object)
			rt.toolbox("AppendResMenu",menu,FONT_RES);int ref=rt.allocate(8,4);fontMenus.put(ref,new FontMenu(menu,(short)arg(1)));memory.writeLong(arg(3),ref);ret(NO_ERR);});
		l.register(null,"TXNGetFontMenuHandle",(r,c)->{FontMenu m=fontMenus.get(arg(0));if(m==null||arg(1)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(1),m.menu);ret(NO_ERR);});
		l.register(null,"TXNDisposeFontMenuObject",(r,c)->{if(fontMenus.remove(arg(0))==null){ret(PARAM_ERR);return;}memory.free(arg(0));ret(NO_ERR);});
		l.register(null,"TXNPrepareFontMenu",(r,c)->{FontMenu m=fontMenus.get(arg(1));if(m==null){ret(PARAM_ERR);return;}Txn t=txn(arg(0)); // (object or NULL, menu object)
			String current=t==null?null:Fonts68k.nameOf((short)memory.readWord(memory.readLong(t.te)+TE_FONT));
			int n=(short)rt.toolbox("CountMenuItems",m.menu),str=rt.allocate(256,2);
			for(int i=1;i<=n;i++){rt.toolbox("GetMenuItemText",m.menu,i,str);rt.toolbox("CheckMenuItem",m.menu,i,current!=null&&rt.toolbox.readPascal(str).equalsIgnoreCase(current)?1:0);}
			memory.free(str);ret(NO_ERR);});
		l.register(null,"TXNDoFontMenuSelection",(r,c)->{Txn t=txn(arg(0));FontMenu m=fontMenus.get(arg(1));if(t==null||m==null){ret(PARAM_ERR);return;} // (object, menu object, menuID, item)
			if((short)arg(2)!=m.menuID){ret(PARAM_ERR);return;}
			int str=rt.allocate(256,2),num=rt.allocate(2,2);rt.toolbox("GetMenuItemText",m.menu,(short)arg(3),str);rt.toolbox("GetFNum",str,num);
			memory.writeWord(memory.readLong(t.te)+TE_FONT,memory.readWord(num));memory.free(str);memory.free(num);restyle(t);t.runCount++;t.changes++;ret(NO_ERR);});
		// action counts: (object, TXNCountOptions, *count) and (object, options)
		l.register(null,"TXNGetActionChangeCount",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(2)==0){ret(PARAM_ERR);return;}
			memory.writeLong(arg(2),((arg(1)&1)!=0?t.inputCount:0)+((arg(1)&2)!=0?t.runCount:0));ret(NO_ERR);});
		l.register(null,"TXNClearActionChangeCount",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if((arg(1)&1)!=0)t.inputCount=0;if((arg(1)&2)!=0)t.runCount=0;ret(NO_ERR);});
		// text boxes, drawn by ATSUI: (UniChar text[], length, Rect* box, ATSUStyle, TXNTextBoxOptionsData*) and (CFString, box, style, options)
		l.register(null,"TXNDrawUnicodeTextBox",(r,c)->{int n=Math.max(0,arg(1));StringBuilder sb=new StringBuilder();for(int i=0;i<n&&arg(0)!=0;i++)sb.append((char)memory.readWord(arg(0)+2*i));
			ret(rt.libraries.textServices.atsui.drawTextBox(sb.toString(),arg(2),arg(3),arg(4)));});
		l.register(null,"TXNDrawCFStringTextBox",(r,c)->{String s=rt.libraries.textServices.cfText(arg(0));if(s==null){ret(PARAM_ERR);return;}
			ret(rt.libraries.textServices.atsui.drawTextBox(s,arg(1),arg(2),arg(3)));});
		defineLayout(l);
	}

	private static final int OUTSIDE_OF_FRAME=-22018,UNIMP_ERR=-4;
	/** MLTE's lines, geometry, typing state, filters and the CarbonLib-private entry points: CarbonLib 1.1-1.6 additions. */
	private void defineLayout(PpcLibraries l){
		// lines: TXNGetLineCount(object, *count); TXNGetLineMetrics(object, line (0-based), *Fixed width, *Fixed height) - the record's lines
		l.register(null,"TXNGetLineCount",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(1)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(1),lineCount(t));ret(NO_ERR);});
		l.register(null,"TXNGetLineMetrics",(r,c)->{Txn t=txn(arg(0));int line=arg(1);if(t==null||line<0||line>=lineCount(t)){ret(PARAM_ERR);return;}
			int q=memory.readLong(t.te);int start=memory.readWord(q+TE_LINE_STARTS+2*line),end=memory.readWord(q+TE_LINE_STARTS+2*line+2);
			byte[] b=lineBytes(t,start,end);int[] w={0};
			inPort(t,()->{int saved=savedText(t);if(b.length>0){int p=rt.allocate(b.length+2,2);memory.load(p,b);w[0]=(short)rt.toolbox("TextWidth",p,0,b.length);memory.free(p);}restoreText(saved);});
			int height=rt.toolbox("TEGetHeight",line+1,line+1,t.te);
			if(arg(2)!=0)memory.writeLong(arg(2),w[0]<<16);if(arg(3)!=0)memory.writeLong(arg(3),height<<16);ret(NO_ERR);});
		// TXNOffsetToPoint(object, offset, *Point): the caret's baseline point in the window (local); TXNPointToOffset(object, Point, *offset):
		// the offset nearest a local point inside the view rectangle (kTXNOutsideOfFrameErr outside it)
		l.register(null,"TXNOffsetToPoint",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(2)==0){ret(PARAM_ERR);return;}String s=text(t);if(arg(1)<0||arg(1)>s.length()){ret(PARAM_ERR);return;}
			int b=rt.toolbox.encoding.encodeReplacing(s.substring(0,arg(1))).length;int pt=rt.toolbox("TEGetPoint",b,t.te);int q=memory.readLong(t.te);
			int v=(short)(pt>>16)-(short)memory.readWord(q+24)+(short)memory.readWord(q+26); // TEGetPoint gives the line's bottom: lineHeight up, fontAscent down
			memory.writeWord(arg(2),v);memory.writeWord(arg(2)+2,(short)pt);ret(NO_ERR);});
		l.register(null,"TXNPointToOffset",(r,c)->{Txn t=txn(arg(0));if(t==null||arg(2)==0){ret(PARAM_ERR);return;}int pt=arg(1),v=(short)(pt>>16),h=(short)pt;
			int q=memory.readLong(t.te);int top=(short)memory.readWord(q+TE_VIEW),left=(short)memory.readWord(q+TE_VIEW+2),bottom=(short)memory.readWord(q+TE_VIEW+4),right=(short)memory.readWord(q+TE_VIEW+6);
			if(v<top||v>=bottom||h<left||h>=right){memory.writeLong(arg(2),0);ret(OUTSIDE_OF_FRAME);return;}
			// unsigned: a record past 32 KB (Painter 8's 49,000-character licence) has offsets above 32767
			int b=rt.toolbox("TEGetOffset",pt,t.te)&0xffff;memory.writeLong(arg(2),charOffset(t,text(t),b));ret(NO_ERR);});
		// view and destination: TXNSetViewRect(object, Rect*) moves the view only (wrapping follows the frame);
		// TXNGetRectBounds(object, Rect* view, TXNLongRect* destination, TXNLongRect* text) - each NULL-able, TXNLongRect = 4 SInt32s;
		// TXNSetRectBounds(object, Rect* view, TXNLongRect* destination, Boolean update)
		l.register(null,"TXNSetViewRect",(r,c)->{Txn t=txn(arg(0));if(t!=null&&arg(1)!=0)memory.copy(arg(1),memory.readLong(t.te)+TE_VIEW,8);});
		l.register(null,"TXNGetRectBounds",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}int q=memory.readLong(t.te);
			if(arg(1)!=0)memory.copy(q+TE_VIEW,arg(1),8);
			int top=(short)memory.readWord(q+TE_DEST),left=(short)memory.readWord(q+TE_DEST+2);
			if(arg(2)!=0){for(int i=0;i<4;i++)memory.writeLong(arg(2)+4*i,(short)memory.readWord(q+TE_DEST+2*i));}
			if(arg(3)!=0){int[] w={0};int n=lineCount(t);
				for(int i=0;i<n;i++){byte[] b=lineBytes(t,memory.readWord(q+TE_LINE_STARTS+2*i),memory.readWord(q+TE_LINE_STARTS+2*i+2));if(b.length==0)continue;
					inPort(t,()->{int saved=savedText(t);int p=rt.allocate(b.length+2,2);memory.load(p,b);w[0]=Math.max(w[0],(short)rt.toolbox("TextWidth",p,0,b.length));memory.free(p);restoreText(saved);});}
				int height=text(t).isEmpty()?0:rt.toolbox("TEGetHeight",n,1,t.te);
				memory.writeLong(arg(3),top);memory.writeLong(arg(3)+4,left);memory.writeLong(arg(3)+8,top+height);memory.writeLong(arg(3)+12,left+w[0]);}
			ret(NO_ERR);});
		l.register(null,"TXNSetRectBounds",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}int q=memory.readLong(t.te);
			if(arg(1)!=0)memory.copy(arg(1),q+TE_VIEW,8);
			if(arg(2)!=0){for(int i=0;i<4;i++)memory.writeWord(q+TE_DEST+2*i,Math.max(-32768,Math.min(32767,memory.readLong(arg(2)+4*i))));inPort(t,()->rt.toolbox("TECalText",t.te));}
			if((arg(3)&0xff)!=0)draw(t,q+TE_VIEW);ret(NO_ERR);});
		l.register(null,"TXNRecalcTextLayout",(r,c)->{Txn t=txn(arg(0));if(t!=null)inPort(t,()->rt.toolbox("TECalText",t.te));});
		// CarbonLib-private, parameters inferred: TXNDrawInRectangle(object, Rect*) draws the object clipped to the rectangle;
		// TXNTextBox is MLTE 1.1's name of TXNDrawUnicodeTextBox (same parameters)
		l.register(null,"TXNDrawInRectangle",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}draw(t,arg(1)!=0?arg(1):memory.readLong(t.te)+TE_VIEW);ret(NO_ERR);});
		l.register(null,"TXNTextBox",(r,c)->{int n=Math.max(0,arg(1));StringBuilder sb=new StringBuilder();for(int i=0;i<n&&arg(0)!=0;i++)sb.append((char)memory.readWord(arg(0)+2*i));
			ret(rt.libraries.textServices.atsui.drawTextBox(sb.toString(),arg(2),arg(3),arg(4)));});
		// typing and undo: TXNIsTyping(object) - a Boolean; there is no undo stack to empty (TXNCanUndo is always false here)
		l.register(null,"TXNIsTyping",(r,c)->{Txn t=txn(arg(0));ret(t!=null&&t.typing?1:0);});
		l.register(null,"TXNEmptyUndoStack",(r,c)->{Txn t=txn(arg(0));if(t!=null)t.typing=false;});
		// embedded objects (pictures, sounds, movies): objects here hold plain text only, so there are none to count, find or reference
		l.register(null,"TXNCountElements",(r,c)->ret(0));
		l.register(null,"TXNFindElementByIndex",(r,c)->ret(RUN_INDEX_OUT_OF_BOUNDS));
		l.register(null,"TXNGetReferencedData",(r,c)->ret(PARAM_ERR));
		// TXNSetDataFilter(object, TXNIncomingDataFilterUPP, refCon) / TXNGetDataFilter(object, *upp, *refCon): CarbonLib-private; the filter
		// is kept and handed back, but its prototype is not public, so incoming text is not run through it
		l.register(null,"TXNSetDataFilter",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}t.dataFilter=arg(1);t.dataFilterRefCon=arg(2);ret(NO_ERR);});
		l.register(null,"TXNGetDataFilter",(r,c)->{Txn t=txn(arg(0));if(t==null){ret(PARAM_ERR);return;}if(arg(1)!=0)memory.writeLong(arg(1),t.dataFilter);if(arg(2)!=0)memory.writeLong(arg(2),t.dataFilterRefCon);ret(NO_ERR);});
		// its UPP (prototype unknown): New hands back the routine itself; Invoke finds that UPP among its arguments (it comes last) and
		// calls it with the ones before
		l.register(null,"NewTXNIncomingDataFilterUPP",(r,c)->{if(arg(0)!=0)dataFilterUpps.add(arg(0));ret(arg(0));});
		l.register(null,"DisposeTXNIncomingDataFilterUPP",(r,c)->dataFilterUpps.remove(arg(0)));
		l.register(null,"InvokeTXNIncomingDataFilterUPP",(r,c)->{for(int n=0;n<8;n++)if(dataFilterUpps.contains(arg(n))){int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);ret(rt.cpu.call(arg(n),a));return;}ret(PARAM_ERR);});
		// TXNActionKeyMapperUPP: CFStringRef (TXNActionKey, UInt32 commandID), uppTXNActionKeyMapperProcInfo $3F0
		l.register(null,"NewTXNActionKeyMapperUPP",(r,c)->ret(r.newRoutineDescriptor(arg(0),ACTION_KEY_MAPPER_PROC_INFO,PpcRuntime.ISA_PPC)));
		l.register(null,"DisposeTXNActionKeyMapperUPP",(r,c)->{int rd=arg(0);if(rd!=0&&r.isRoutineDescriptor(rd)&&memory.isAllocated(rd))memory.free(rd);});
		l.register(null,"InvokeTXNActionKeyMapperUPP",(r,c)->ret(arg(2)==0?0:r.isRoutineDescriptor(arg(2))?r.callUniversal(arg(2),ACTION_KEY_MAPPER_PROC_INFO,new int[]{arg(0),arg(1)}):r.cpu.call(arg(2),arg(0),arg(1))));
		// TXNPsuedoUnicodeKeyDown: CarbonLib-private, taken as (object, EventRecord*) like TXNKeyDown; an argument that is no key event is ignored
		l.register(null,"TXNPsuedoUnicodeKeyDown",(r,c)->{Txn t=txn(arg(0));int e=arg(1);if(t==null||e==0||t.readOnly)return;int what=memory.readWord(e);if(what!=3&&what!=5)return;
			inPort(t,()->{rt.toolbox("TEKey",memory.readLong(e+2)&0xff,t.te);t.changes++;t.inputCount++;t.typing=true;});});
		// page-by-page printing hooks (TXNBeginPrinting, TXNCountLinesToPrint, TXNPrintPage, TXNEndPrinting) are CarbonLib-private and
		// their parameters are not published: unimpErr with nothing written (TXNPrint prints a whole object)
		for(String name:new String[]{"TXNBeginPrinting","TXNCountLinesToPrint","TXNPrintPage","TXNEndPrinting"})l.register(null,name,(r,c)->ret(UNIMP_ERR));
	}
	private static final int ACTION_KEY_MAPPER_PROC_INFO=0x3f0,TE_LINE_STARTS=96,TE_N_LINES=94;
	private final java.util.Set<Integer> dataFilterUpps=new java.util.HashSet<Integer>();
	/** The record's line count (at least 1: an empty object has one empty line). */
	private int lineCount(Txn t) throws M68kCpuException {return Math.max(1,memory.readWord(memory.readLong(t.te)+TE_N_LINES));}
	/** The bytes [start,end) of the record's text without a line's ending carriage return. */
	private byte[] lineBytes(Txn t,int start,int end) throws M68kCpuException {
		int q=memory.readLong(t.te);int h=memory.readLong(q+TE_TEXT),n=memory.readWord(q+TE_LENGTH);end=Math.min(end,n);if(h==0||end<=start)return new byte[0];
		byte[] b=memory.readBytes(memory.readLong(h)+start,end-start);int k=b.length;while(k>0&&b[k-1]==13)k--;return java.util.Arrays.copyOf(b,k);
	}
	/** Erases {@code rect} and draws the object's text inside it. */
	private void draw(Txn t,int rect) throws M68kException {
		inPort(t,()->{int box=rt.allocate(8,2);memory.copy(rect,box,8);rt.toolbox("EraseRect",box);rt.toolbox("TEUpdate",box,t.te);memory.free(box);});
	}
	private static final int FONT_RES='F'<<24|'O'<<16|'N'<<8|'T';
	private static final int TE_IN_PORT=82;

	/** Replaces the MLTE range [start,end) with {@code text} (CR line ends, the program's encoding). */
	// ------------------------------------------------------------------ the vertical scroll bar (kTXNWantVScrollBarMask)

	private static final int WANT_V_SCROLL_BAR=1<<3,SCROLL_BAR_WIDTH=16,TE_LINE_HEIGHT=24,
		IN_UP_BUTTON=20,IN_DOWN_BUTTON=21,IN_PAGE_UP=22,IN_PAGE_DOWN=23,IN_THUMB=129;
	/**
	 * A scroll bar control along the right edge of the frame, as MLTE makes for kTXNWantVScrollBarMask; the text view and
	 * destination give up its width. TXNClick tracks it and scrolls the record (its value is the view's distance below the
	 * top of the text), and it follows the text whenever that changes, scrolls or is drawn. Painter 8's VISE installer enables
	 * "Accept" only once the end of its licence has been scrolled into view, and there was no way to scroll without it.
	 */
	private void addScrollBar(Txn t) throws M68kException {
		int rect=rt.allocate(8,2),title=rt.allocate(2,2);memory.fill(rect,8,0);memory.writeByte(title,0);
		int saved=port();rt.toolbox("SetPort",t.window);
		try{t.vScroll=rt.toolbox("NewControl",t.window,rect,title,1,0,0,0,16,0);}finally{rt.toolbox("SetPort",saved);memory.free(rect);memory.free(title);}
		if(t.vScroll!=0)layoutScrollBar(t);
	}
	/** Puts the scroll bar along the right edge of the frame that the view rect now holds and narrows the view and destination by it. */
	private void layoutScrollBar(Txn t) throws M68kException {
		int p=memory.readLong(t.te);int top=(short)memory.readWord(p+TE_VIEW),bottom=(short)memory.readWord(p+TE_VIEW+4),right=(short)memory.readWord(p+TE_VIEW+6);
		for(int at:new int[]{TE_DEST,TE_VIEW})memory.writeWord(p+at+6,right-SCROLL_BAR_WIDTH+1);
		inPort(t,()->{rt.toolbox("MoveControl",t.vScroll,right-SCROLL_BAR_WIDTH,top);rt.toolbox("SizeControl",t.vScroll,SCROLL_BAR_WIDTH,bottom-top);rt.toolbox("TECalText",t.te);});
		syncScroll(t);
	}
	/** The scroll bar's range and value from the record: the text's height beyond the view, and how far the view is down it. */
	private void syncScroll(Txn t) throws M68kException {
		if(t.vScroll==0)return;
		int p=memory.readLong(t.te);int viewTop=(short)memory.readWord(p+TE_VIEW),viewHeight=(short)memory.readWord(p+TE_VIEW+4)-viewTop;
		int height=lineCount(t)*Math.max(1,(short)memory.readWord(p+TE_LINE_HEIGHT));
		int max=Math.max(0,Math.min(32767,height-viewHeight)),value=Math.max(0,Math.min(max,viewTop-(short)memory.readWord(p+TE_DEST)));
		inPort(t,()->{rt.toolbox("SetControlMaximum",t.vScroll,max);rt.toolbox("SetControlValue",t.vScroll,value);});
	}
	private boolean inScrollBar(Txn t,int where) throws M68kException {
		if(t.vScroll==0)return false;int r=memory.readLong(t.vScroll)+8,v=(short)(where>>16),h=(short)where; // contrlRect
		return v>=(short)memory.readWord(r)&&h>=(short)memory.readWord(r+2)&&v<(short)memory.readWord(r+4)&&h<(short)memory.readWord(r+6);
	}
	/** A click in the scroll bar: the thumb is dragged to a new position, an arrow moves a line and the grey area a page. */
	private void scrollClick(Txn t,int where) throws M68kException {
		int part=(short)rt.toolbox("TestControl",t.vScroll,where);if(part==0)return;
		int p=memory.readLong(t.te);int lineHeight=Math.max(1,(short)memory.readWord(p+TE_LINE_HEIGHT));
		int viewHeight=(short)memory.readWord(p+TE_VIEW+4)-(short)memory.readWord(p+TE_VIEW),current=(short)memory.readWord(p+TE_VIEW)-(short)memory.readWord(p+TE_DEST);
		if((short)rt.toolbox("TrackControl",t.vScroll,where,0)==0)return;
		int target;
		switch(part){
			case IN_THUMB:target=(short)rt.toolbox("GetControlValue",t.vScroll);break;
			case IN_UP_BUTTON:target=current-lineHeight;break;
			case IN_DOWN_BUTTON:target=current+lineHeight;break;
			case IN_PAGE_UP:target=current-Math.max(lineHeight,viewHeight-lineHeight);break;
			case IN_PAGE_DOWN:target=current+Math.max(lineHeight,viewHeight-lineHeight);break;
			default:return;
		}
		int max=Math.max(0,lineCount(t)*lineHeight-viewHeight);target=Math.max(0,Math.min(max,target));
		if(target!=current){int dv=current-target;inPort(t,()->rt.toolbox("TEScroll",0,dv,t.te));}
		syncScroll(t);
	}

	/** The text of the desk scrap's 'TEXT' when it is an RTF document, else null. */
	private String scrapRtf() throws M68kException {
		int h=rt.toolbox("NewHandle",0);if(h==0)return null;
		try{int offset=rt.allocate(4,4);int n=rt.toolbox("GetScrap",h,TEXT,offset);memory.free(offset);
			if(n<6)return null;byte[] head=memory.readBytes(memory.readLong(h),6);
			if(!new String(head,StandardCharsets.ISO_8859_1).equals("{\\rtf1"))return null;
			return rtfText(memory.readBytes(memory.readLong(h),n));}
		finally{rt.toolbox("DisposeHandle",h);}
	}
	private void setData(Txn t,String text,int start,int end) throws M68kException {
		int[] range=byteRange(t,start,end);byte[] b=rt.toolbox.encoding.encodeReplacing(text.replace("\r\n","\r").replace('\n','\r'));
		inPort(t,()->{rt.toolbox("TESetSelect",range[0],range[1],t.te);rt.toolbox("TEDelete",t.te);
			if(b.length>0){int p=rt.allocate(b.length,2);memory.load(p,b);rt.toolbox("TEInsert",p,b.length,t.te);memory.free(p);}});
		t.changes++;t.inputCount++;t.typing=false;syncScroll(t);
	}
	/** After a font change: the record's line height and ascent from the new font, and its lines recalculated. */
	private void restyle(Txn t) throws M68kException {
		inPort(t,()->{int info=rt.allocate(8,2);int saved=savedText(t);rt.toolbox("GetFontInfo",info);restoreText(saved);
			int q=memory.readLong(t.te);memory.writeWord(q+24,(short)memory.readWord(info)+(short)memory.readWord(info+2)+(short)memory.readWord(info+6));memory.writeWord(q+26,(short)memory.readWord(info));memory.free(info); // lineHeight, fontAscent
			rt.toolbox("TECalText",t.te);});
	}
	/** The frame takes the window's port rectangle (after the window grew or zoomed) and is redrawn. */
	private void fillWindow(Txn t) throws M68kException {
		int p=memory.readLong(t.te);for(int at:new int[]{TE_DEST,TE_VIEW})memory.copy(t.window+16,p+at,8);
		inPort(t,()->{rt.toolbox("TECalText",t.te);int rect=rt.allocate(8,2);memory.copy(t.window+16,rect,8);rt.toolbox("InvalRect",rect);memory.free(rect);});
	}
	/** TXNTypeAttributes {tag, size, data} filled with the object's font, size, style, colour (black), name and encoding. */
	private int typeAttributes(Txn t,int count,int attributes) throws M68kException {
		int p=memory.readLong(t.te),status=NO_ERR;int font=(short)memory.readWord(p+TE_FONT);
		for(int i=0;i<count&&attributes!=0;i++){int a=attributes+12*i,tag=memory.readLong(a),data=memory.readLong(a+8);
			if(tag==FONT)memory.writeLong(a+8,font);
			else if(tag==SIZE)memory.writeLong(a+8,Math.max(1,(short)memory.readWord(p+TE_SIZE))<<16);
			else if(tag==FACE)memory.writeLong(a+8,memory.readByte(p+TE_FACE));
			else if(tag==ENCODING)memory.writeLong(a+8,rt.libraries.textServices.systemEncoding());
			else if(tag==COLOR&&data!=0)memory.fill(data,6,0);
			else if(tag==FONT_NAME&&data!=0)rt.toolbox.writePascal(data,Fonts68k.nameOf(font),255);
			else{memory.writeLong(a+8,TAG_INVALID_FOR_RUN);status=-22010;} // kTXNSomeOrAllTagsInvalidForRunErr
		}
		return status;
	}
	/** The first match of {@code needle} in {@code s} from {@code from}, or -1. */
	private static int find(String s,String needle,int from,boolean ignoreCase,boolean wholeWord){
		if(needle.isEmpty())return -1;
		for(int i=from;i+needle.length()<=s.length();i++){
			if(!s.regionMatches(ignoreCase,i,needle,0,needle.length()))continue;
			if(wholeWord&&(i>0&&Character.isLetterOrDigit(s.charAt(i-1))||i+needle.length()<s.length()&&Character.isLetterOrDigit(s.charAt(i+needle.length()))))continue;
			return i;
		}
		return -1;
	}
	/** A File Manager call on an open file ({@code trap} with an ioParam block): {result, ioActCount, ioMisc (EOF), ioPosOffset}. */
	private int[] fileIo(int trap,int refNum,int buffer,int count,int posMode,int posOffset,int... misc) throws M68kException {
		int pb=rt.allocate(80,4);memory.writeWord(pb+24,refNum);memory.writeLong(pb+32,buffer);memory.writeLong(pb+36,count);memory.writeWord(pb+44,posMode);memory.writeLong(pb+46,posOffset);
		if(misc.length>0)memory.writeLong(pb+28,misc[0]);
		int err=(short)rt.trap(trap,0,pb,0);int[] result={err,memory.readLong(pb+40),memory.readLong(pb+28),memory.readLong(pb+46)};memory.free(pb);return result;
	}
	/** The object's TPrint record: made and defaulted on first use, validated after. */
	private void preparePrint(Txn t) throws M68kException {
		if(t.print==0){t.print=rt.trap(0xa122,120,0,0)==0?rt.lastA0:0;if(t.print!=0)rt.toolbox("PrintDefault",t.print);}else rt.toolbox("PrValidate",t.print); // _NewHandle
	}
	/** TXNPrint: the job dialog, then the text wrapped to the page rectangle, page by page. */
	private int print(Txn t) throws M68kException {
		rt.toolbox("PrOpen");int err=(short)rt.toolbox("PrError");if(err!=0)return err;
		preparePrint(t);if(t.print==0){rt.toolbox("PrClose");return -108;}
		if(rt.toolbox("PrJobDialog",t.print)==0){rt.toolbox("PrClose");return USER_CANCELED;}
		int saved=port();int doc=rt.toolbox("PrOpenDoc",t.print,0,0);
		try{
			if((short)rt.toolbox("PrError")==0){
				int page=memory.readLong(t.print)+8;int top=(short)memory.readWord(page),left=(short)memory.readWord(page+2),bottom=(short)memory.readWord(page+4),right=(short)memory.readWord(page+6);
				int p=memory.readLong(t.te);
				rt.toolbox("SetPort",doc);rt.toolbox("TextFont",(short)memory.readWord(p+TE_FONT));rt.toolbox("TextSize",(short)memory.readWord(p+TE_SIZE));rt.toolbox("TextFace",memory.readByte(p+TE_FACE));
				int info=rt.allocate(8,2);rt.toolbox("GetFontInfo",info);int ascent=(short)memory.readWord(info),height=ascent+(short)memory.readWord(info+2)+(short)memory.readWord(info+6);memory.free(info);
				List<byte[]> lines=new ArrayList<byte[]>();for(String para:text(t).split("\r",-1))lines.addAll(wrap(para,right-left));
				int i=0;
				do{
					rt.toolbox("PrOpenPage",doc,0);rt.toolbox("SetPort",doc);int y=top+ascent;
					while(i<lines.size()&&(y+height-ascent<=bottom||y==top+ascent)){byte[] b=lines.get(i++);if(b.length>0){int buf=rt.allocate(b.length+2,2);memory.load(buf,b);rt.toolbox("MoveTo",left,y);rt.toolbox("DrawText",buf,0,b.length);memory.free(buf);}y+=height;}
					rt.toolbox("PrClosePage",doc);
				}while(i<lines.size()&&(short)rt.toolbox("PrError")==0);
			}
		}finally{rt.toolbox("PrCloseDoc",doc);err=(short)rt.toolbox("PrError");rt.toolbox("PrClose");if(saved!=0)rt.toolbox("SetPort",saved);}
		return err;
	}
	/** A paragraph broken into lines no wider than {@code width} in the current port's font (at line-break opportunities, else anywhere). */
	private List<byte[]> wrap(String para,int width) throws M68kException {
		List<byte[]> lines=new ArrayList<byte[]>();if(para.isEmpty()){lines.add(new byte[0]);return lines;}
		BreakIterator it=BreakIterator.getLineInstance();it.setText(para);int start=0,last=0;
		for(int b=it.next();b!=BreakIterator.DONE;b=it.next()){
			if(width(para.substring(start,b).stripTrailing())<=width){last=b;continue;}
			if(last>start){lines.add(bytes(para.substring(start,last).stripTrailing()));start=last;}
			while(width(para.substring(start,b).stripTrailing())>width){int n=start+1;while(n<b&&width(para.substring(start,n+1))<=width)n++;lines.add(bytes(para.substring(start,n)));start=n;}
			last=b;
		}
		if(start<para.length())lines.add(bytes(para.substring(start).stripTrailing()));
		return lines;
	}
	private byte[] bytes(String s){return rt.toolbox.encoding.encodeReplacing(s);}
	private int width(String s) throws M68kException {byte[] b=bytes(s);if(b.length==0)return 0;int p=rt.allocate(b.length+2,2);memory.load(p,b);int w=(short)rt.toolbox("TextWidth",p,0,b.length);memory.free(p);return w;}

	private interface Body { void run() throws M68kException; }
	/** Runs {@code body} with the object's window as the current port (TextEdit draws in the current port). */
	private void inPort(Txn t,Body body) throws M68kException {
		int saved=port();rt.toolbox("SetPort",t.window);int back=0;
		if(t.background!=null){back=rt.allocate(12,2);rt.toolbox("GetBackColor",back);memory.load(back+6,t.background);rt.toolbox("RGBBackColor",back+6);}
		try{body.run();}finally{if(back!=0){rt.toolbox("RGBBackColor",back);memory.free(back);}rt.toolbox("SetPort",saved);}
	}
	private int port() throws M68kException {int p=rt.allocate(4,4);rt.toolbox("GetPort",p);int v=memory.readLong(p);memory.free(p);return v;}
	/** GetFontInfo measures the port's font: the record's font goes into the port meanwhile (6 bytes saved). */
	private int savedText(Txn t) throws M68kException {int p=memory.readLong(t.te);int s=rt.allocate(6,2);int port=port();
		memory.writeWord(s,memory.readWord(port+68));memory.writeWord(s+2,memory.readWord(port+74));memory.writeWord(s+4,memory.readByte(port+70));
		rt.toolbox("TextFont",(short)memory.readWord(p+TE_FONT));rt.toolbox("TextSize",(short)memory.readWord(p+TE_SIZE));rt.toolbox("TextFace",memory.readByte(p+TE_FACE));return s;}
	private void restoreText(int s) throws M68kException {rt.toolbox("TextFont",(short)memory.readWord(s));rt.toolbox("TextSize",(short)memory.readWord(s+2));rt.toolbox("TextFace",memory.readWord(s+4));memory.free(s);}

	/** The record's text as characters. */
	private String text(Txn t) throws M68kCpuException {int p=memory.readLong(t.te);int n=memory.readWord(p+TE_LENGTH),h=memory.readLong(p+TE_TEXT);if(n==0||h==0)return "";return rt.toolbox.decode(memory.readBytes(memory.readLong(h),n));}
	/** The character offset of byte offset {@code b} of the record's text. */
	private int charOffset(Txn t,String s,int b) throws M68kCpuException {int p=memory.readLong(t.te);int h=memory.readLong(p+TE_TEXT);int n=Math.min(b,memory.readWord(p+TE_LENGTH));if(n<=0||h==0)return 0;return rt.toolbox.decode(memory.readBytes(memory.readLong(h),n)).length();}
	/** MLTE offsets (UniChars; kTXNUseCurrentSelection, kTXNEndOffset) as a character range of {@code s}. */
	private int[] charRange(Txn t,String s,int start,int end) throws M68kCpuException {
		if(start==USE_CURRENT_SELECTION){int p=memory.readLong(t.te);return new int[]{charOffset(t,s,memory.readWord(p+TE_SEL_START)),charOffset(t,s,memory.readWord(p+TE_SEL_END))};}
		int a=Math.max(0,Math.min(start,s.length())),b=end==END_OFFSET?s.length():Math.max(a,Math.min(end,s.length()));return new int[]{a,b};
	}
	/** MLTE offsets as a byte range of the record's text. */
	private int[] byteRange(Txn t,int start,int end) throws M68kCpuException {
		if(start==USE_CURRENT_SELECTION){int p=memory.readLong(t.te);return new int[]{memory.readWord(p+TE_SEL_START),memory.readWord(p+TE_SEL_END)};}
		String s=text(t);int[] cr=charRange(t,s,start,end);
		return new int[]{rt.toolbox.encoding.encodeReplacing(s.substring(0,cr[0])).length,rt.toolbox.encoding.encodeReplacing(s.substring(0,cr[1])).length};
	}

	/**
	 * The text of an RTF document ('RTF ', kTXNRichTextFormatData - MLTE 1.3, the version answered, reads it), without its
	 * styles: the record is monostyled. Painter 8's VISE installer hands its licence over this way. Destinations that are not
	 * body text (font and colour tables, style sheet, info, pictures, any \* group) are skipped; \par and \line end lines,
	 * \'hh bytes are decoded with the document's code page (\mac or \ansicpg10000: Mac Roman; else Windows-1252) and a
	 * Unicode control word (u, then the code) gives the character itself, dropping the uc fallback characters after it.
	 */
	static String rtfText(byte[] rtf){
		StringBuilder out=new StringBuilder();java.nio.charset.Charset cs=java.nio.charset.Charset.forName("windows-1252");
		java.util.ArrayDeque<int[]> stack=new java.util.ArrayDeque<int[]>(); // {skip, uc} of the enclosing groups
		boolean skip=false;int uc=1,pendingSkip=0;java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
		int i=0,n=rtf.length;
		while(i<n){
			int ch=rtf[i]&255;
			if(ch!='\\'||i+1>=n||rtf[i+1]!='\''){if(bytes.size()>0){if(!skip)out.append(new String(bytes.toByteArray(),cs));bytes.reset();}}
			if(ch=='{'){stack.push(new int[]{skip?1:0,uc});i++;
				if(i+1<n&&rtf[i]=='\\'&&rtf[i+1]=='*')skip=true;
				else if(i<n&&rtf[i]=='\\'){int e=i+1;while(e<n&&Character.isLetter(rtf[e]))e++;
					String w=new String(rtf,i+1,e-i-1,StandardCharsets.ISO_8859_1);
					if(w.equals("fonttbl")||w.equals("colortbl")||w.equals("stylesheet")||w.equals("info")||w.equals("pict")||w.equals("listtable")
						||w.equals("listoverridetable")||w.equals("rsidtbl")||w.equals("header")||w.equals("footer")||w.equals("object")||w.equals("themedata"))skip=true;}
				continue;}
			if(ch=='}'){if(!stack.isEmpty()){int[] g=stack.pop();skip=g[0]!=0;uc=g[1];}i++;continue;}
			if(ch=='\r'||ch=='\n'){i++;continue;}
			if(ch!='\\'){if(pendingSkip>0)pendingSkip--;else if(!skip)bytes.write(ch);i++;continue;}
			if(i+1>=n)break;
			int next=rtf[i+1]&255;
			if(next=='\''&&i+3<n){int v=Character.digit(rtf[i+2],16)<<4|Character.digit(rtf[i+3],16);i+=4;if(pendingSkip>0)pendingSkip--;else if(!skip&&v>=0)bytes.write(v);continue;}
			if(!Character.isLetter(next)){i+=2;if(skip)continue; // control symbols
				if(next=='\\'||next=='{'||next=='}')out.append((char)next);else if(next=='~')out.append('\u00a0');else if(next=='_')out.append('-');
				continue;}
			int e=i+1;while(e<n&&Character.isLetter(rtf[e]))e++;
			String word=new String(rtf,i+1,e-i-1,StandardCharsets.ISO_8859_1);int p=e;
			if(p<n&&(rtf[p]=='-'||Character.isDigit(rtf[p]))){p++;while(p<n&&Character.isDigit(rtf[p]))p++;}
			Integer param=p>e?Integer.valueOf(new String(rtf,e,p-e,StandardCharsets.ISO_8859_1)):null;
			if(p<n&&rtf[p]==' ')p++;
			i=p;
			switch(word){
				case "mac":cs=java.nio.charset.Charset.forName("x-MacRoman");break;
				case "ansicpg":if(param!=null&&param==10000)cs=java.nio.charset.Charset.forName("x-MacRoman");else if(param!=null){try{cs=java.nio.charset.Charset.forName("windows-"+param);}catch(RuntimeException ignored){}}break;
				case "uc":if(param!=null)uc=param;break;
				case "u":if(param!=null&&!skip){out.append((char)(param<0?param+65536:param));pendingSkip=uc;}break;
				case "par":case "line":case "row":if(!skip)out.append('\r');break;
				case "tab":case "cell":if(!skip)out.append('\t');break;
				case "emdash":if(!skip)out.append('\u2014');break;
				case "endash":if(!skip)out.append('\u2013');break;
				case "bullet":if(!skip)out.append('\u2022');break;
				case "lquote":if(!skip)out.append('\u2018');break;
				case "rquote":if(!skip)out.append('\u2019');break;
				case "ldblquote":if(!skip)out.append('\u201c');break;
				case "rdblquote":if(!skip)out.append('\u201d');break;
				default:break;
			}
		}
		if(bytes.size()>0&&!skip)out.append(new String(bytes.toByteArray(),cs));
		return out.toString();
	}
}

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TextEdit for hosted applications: TERecs in the classic layout with host-font rendering, word wrapping, selection,
 * caret, mouse tracking and keyboard editing. A styled record (TEStyleNew) carries a TEStyleRec with real style runs,
 * style and line-height tables in guest memory; see the "styled records" section.
 */
final class TextEdit68k {
	private static final int DEST=0,VIEW=8,SEL_RECT=16,LINE_HEIGHT=24,FONT_ASCENT=26,SEL_POINT=28,SEL_START=32,SEL_END=34,ACTIVE=36,WORD_BREAK=38,CLICK_LOOP=42,CLICK_TIME=46,CLICK_LOC=50,CARET_TIME=52,CARET_STATE=56,JUST=58,LENGTH=60,H_TEXT=62,RECAL_BACK=66,RECAL_LINES=68,CLICK_STUFF=70,CR_ONLY=72,TX_FONT=74,TX_FACE=76,TX_MODE=78,TX_SIZE=80,IN_PORT=82,HIGH_HOOK=86,CARET_HOOK=90,N_LINES=94,LINE_STARTS=96;
	private static final Color SELECTION=new Color(0xb3,0xd4,0xff);
	private static final int TE_F_AUTO_SCROLL=0,TE_F_OUTLINE_HILITE=2;

	final class State {boolean caretVisible;long lastBlink;int anchor;boolean styled;boolean ownsText=true;
		/** Where the insertion point was last drawn (port coordinates), for the host input method's candidate window. */
		int caretX,caretY,caretHeight;
		/** Inline input (TSMTE): hilite ranges of the text being composed as {start, end, style} triples (styles: 2/3 raw, 4/5 converted; 5 = the selected clause), or null. */
		int[] inlineRanges;
		/** TEFeatureFlag bits (teFAutoScroll 0, teFTextBuffering 1, teFOutlineHilite 2, teFInlineInput 3, teFUseTextServices 4); all clear by default. */
		int features;
		/** The user can edit the text (a dialog's edit text, a field, or a record the program activated, clicked or typed into): never translated. */
		boolean editable;
		/** Last drawn as a translation. */
		boolean translated;
		/** A HyperCard field's styled record (GetFieldTE): TESetText keeps the style its text starts with, as TextEdit does,
		 *  instead of taking the port's font (Varisco's cReadSimpleTextFile turned a Palatino field into Chicago 12). */
		boolean keepsStyle;
		/** TESetStyle at an insertion point of a styled record (the null style): its mode and TextStyle (12 bytes), given to the
		 *  next text inserted there; a new selection drops it. Claris XTND's import (cImportXtndFile) sets each run's style so
		 *  and TEInserts its text - it all came in plain. */
		int nullMode;byte[] nullStyle;}
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,State> records=new HashMap<Integer,State>();
	private byte[] scrap=new byte[0];
	/** The styles of the last TECut/TECopy from a styled record (a StScrpRec), for TEPaste/TEStylePaste of that same text; null after a plain copy. */
	private byte[] scrapStyles;
	/** TEScrpLength (a word) / TEScrpHandle: the TextEdit scrap lives in low memory, where the TEToScrap/TEFromScrap glue
	 *  (PutScrap/GetScrap on that handle) and programs that read it directly expect it. */
	private static final int TE_SCRP_LENGTH=0xab0,TE_SCRP_HANDLE=0xab4;

	TextEdit68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x9cc:return "TEInit";case 0x9d2:return "TENew";case 0x83e:return "TEStyleNew";case 0x9cd:return "TEDispose";case 0x9cf:return "TESetText";case 0x9cb:return "TEGetText";
			case 0x9d0:return "TECalText";case 0x9d1:return "TESetSelect";case 0x9d3:return "TEUpdate";case 0x9d4:return "TEClick";case 0x9da:return "TEIdle";case 0x9dc:return "TEKey";
			case 0x9de:return "TEInsert";case 0x9d7:return "TEDelete";case 0x9d6:return "TECut";case 0x9d5:return "TECopy";case 0x9db:return "TEPaste";case 0x9dd:return "TEScroll";case 0x812:return "TEPinScroll";
			case 0x811:return "TESelView";case 0x813:return "TEAutoView";case 0x9df:return "TESetJust";case 0x9d8:return "TEActivate";case 0x9d9:return "TEDeactivate";case 0x9ce:return "TETextBox";
			case 0x83c:return "TEGetOffset";case 0x83d:return "TEDispatch";
			default:return null;
		}
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		switch(trap){
			case 0x9cc:{ensureScrap();return true;}
			// A record made without TEInit still finds a scrap: Prometheus prints to its console by PtrToXHand into TEScrpHandle and
			// TEPaste, never calling TEInit - with a NIL handle it pasted the host clipboard and then crashed.
			case 0x9d2:case 0x83e:{ensureScrap();int view=memory.readLong(cpu.a[7]),dest=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeLong(cpu.a[7],create(dest,view,trap==0x83e));return true;}
			case 0x9cd:{int h=pop(cpu);dispose(h);return true;}
			case 0x9cf:{int h=memory.readLong(cpu.a[7]),length=memory.readLong(cpu.a[7]+4),text=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;if(known(h)){setText(h,length>0?memory.readBytes(text,length):new byte[0]);}return true;}
			case 0x9cb:{int h=pop(cpu);memory.writeLong(cpu.a[7],known(h)?memory.readLong(rec(h)+H_TEXT):0);return true;}
			// TECalText takes teLength from the size of hText first (as Executor does): programs that fill hText themselves call it
			// without setting teLength - VISE Installer measures an alert message through the dialog's textH that way.
			case 0x9d0:{int h=pop(cpu);if(known(h)){syncLengthToText(h);recalc(h);}return true;}
			case 0x9d1:{int h=memory.readLong(cpu.a[7]),end=memory.readLong(cpu.a[7]+4),start=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;if(known(h))setSelection(h,start,end,true);return true;}
			case 0x9d3:{int h=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(known(h))update(h,0,TO_BOTTOM,false);return true;} // TEUpdate draws over what is there; erasing is the caller's
			case 0x9d4:{int h=memory.readLong(cpu.a[7]);boolean extend=memory.readByte(cpu.a[7]+4)!=0;int v=(short)memory.readWord(cpu.a[7]+6),ph=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;if(known(h))click(cpu,h,ph,v,extend);return true;}
			case 0x9da:{int h=pop(cpu);cpu.d[0]=known(h)?idle(h):0;return true;} // D0: see idle
			case 0x9dc:{int h=memory.readLong(cpu.a[7]),ch=memory.readWord(cpu.a[7]+4)&0xff;cpu.a[7]+=6;if(known(h)){key(h,ch);autoView(h);}return true;}
			case 0x9de:{int h=memory.readLong(cpu.a[7]),length=memory.readLong(cpu.a[7]+4),text=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;if(known(h)&&length>0)insertAtSelection(h,memory.readBytes(text,length),false);return true;}
			case 0x9d7:{int h=pop(cpu);if(known(h))deleteSelection(h);return true;}
			case 0x9d6:{int h=pop(cpu);if(known(h)){copy(h);deleteSelection(h);}return true;}
			case 0x9d5:{int h=pop(cpu);if(known(h))copy(h);return true;}
			case 0x9db:{int h=pop(cpu);if(known(h))paste(h);return true;}
			case 0x9dd:{int h=memory.readLong(cpu.a[7]),dv=(short)memory.readWord(cpu.a[7]+4),dh=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;if(known(h))scroll(h,dh,dv);return true;}
			case 0x812:{int h=memory.readLong(cpu.a[7]),dv=(short)memory.readWord(cpu.a[7]+4),dh=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;if(known(h))pinScroll(h,dh,dv);return true;}
			case 0x811:{int h=pop(cpu);if(known(h))selView(h);return true;}
			case 0x813:{int h=memory.readLong(cpu.a[7]);boolean on=memory.readByte(cpu.a[7]+4)!=0;cpu.a[7]+=6; // TEAutoView(fAuto, hTE): teFAutoScroll
				State s=known(h)?records.get(h):null;if(s!=null){if(on)s.features|=1<<TE_F_AUTO_SCROLL;else s.features&=~(1<<TE_F_AUTO_SCROLL);}return true;}
			case 0x9df:{int h=memory.readLong(cpu.a[7]),just=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;if(known(h)){memory.writeWord(rec(h)+JUST,just);}return true;}
			case 0x9d8:case 0x9d9:{int h=pop(cpu);activate(h,trap==0x9d8);return true;}
			case 0x9ce:{int just=(short)memory.readWord(cpu.a[7]),box=memory.readLong(cpu.a[7]+2),length=memory.readLong(cpu.a[7]+6),text=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;textBox(length>0?memory.readBytes(text,length):new byte[0],box,just);return true;}
			case 0x83c:{int h=memory.readLong(cpu.a[7]),pt=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeWord(cpu.a[7],known(h)?offsetAt(h,(short)pt,(short)(pt>>16)):0);return true;}
			case 0x83d:return dispatch(cpu);
			default:return false;
		}
	}

	private int pop(M68kCpu cpu) throws M68kCpuException {int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return h;}
	private int rec(int h) throws M68kCpuException {return memory.readLong(h);}
	/**
	 * Whether a Handle is a TextEdit record of ours - including copies a program made itself (some text editors print a
	 * HandToHand copy of its document's record with inPort changed to the printing port): a Handle of a TERec's size whose
	 * hText is a Handle is adopted on first sight, sharing its text with the original.
	 */
	private boolean known(int h) throws M68kCpuException {
		if(records.containsKey(h))return true;if(h==0)return false;
		int size=tb.api.handleSize(h);if(size<LINE_STARTS+4)return false;int r=memory.readLong(h);if(r==0)return false;
		int text=memory.readLong(r+H_TEXT);if(text==0||tb.api.handleContents(text)==null)return false;
		int lines=memory.readWord(r+N_LINES);if(lines<0||LINE_STARTS+(lines+1)*2>size+2)return false;
		State s=new State();s.ownsText=false;records.put(h,s);
		if(Mac68kApi.TRACE)System.err.println("[68K] TextEdit adopts a copied record $"+Integer.toHexString(h));
		return true;
	}

	// ------------------------------------------------------------------ records

	int create(int destRect,int viewRect,boolean styled) throws M68kCpuException {
		byte[] record=new byte[LINE_STARTS+2*64]; // room for 63 lines before the record has to grow
		int handle=tb.api.createHandle(record);int r=memory.readLong(handle);
		if(destRect!=0)memory.copy(destRect,r+DEST,8);if(viewRect!=0)memory.copy(viewRect,r+VIEW,8);
		int port=tb.api.currentQuickDrawPort();
		int[] t=tb.api.quickDraw().textState();
		memory.writeWord(r+TX_FONT,t[0]);setFace(r,t[1]);memory.writeWord(r+TX_MODE,t[2]);memory.writeWord(r+TX_SIZE,t[3]);
		memory.writeLong(r+IN_PORT,port);
		int[] fi=Fonts68k.fontInfo(Fonts68k.font(t[0],t[3],t[1]));
		memory.writeWord(r+LINE_HEIGHT,fi[0]+fi[1]+fi[3]);memory.writeWord(r+FONT_ASCENT,fi[0]);
		memory.writeWord(r+WORD_BREAK,0);memory.writeLong(r+CARET_TIME,32);memory.writeWord(r+CR_ONLY,0);
		memory.writeLong(r+H_TEXT,tb.api.createHandle(new byte[0]));
		memory.writeWord(r+N_LINES,1);memory.writeWord(r+LINE_STARTS,0);memory.writeWord(r+LINE_STARTS+2,0);
		if(styled){int st=createStyleRec(t[0],t[1],t[3],fi[0]+fi[1]+fi[3],fi[0],tb.api.quickDraw().foregroundRgb48());memory.writeLong(r+TX_FONT,st);memory.writeWord(r+TX_SIZE,-1); // txSize -1: txFont/txFace hold the TEStyleHandle
			memory.writeWord(r+LINE_HEIGHT,-1);memory.writeWord(r+FONT_ASCENT,-1);} // TEStyleNew: -1 = each line as tall as its styles (see fixedMetrics)
		State s=new State();s.styled=styled;records.put(handle,s);
		return handle;
	}

	/**
	 * A record for a HyperCard field (GetFieldTE): the field's rectangle in card coordinates, its font, size and style,
	 * and its text. The external owns the record and disposes it.
	 */
	int createForField(int top,int left,int bottom,int right,int fontId,int size,int face,byte[] text) throws M68kCpuException {
		int h=create(0,0,false);int r=rec(h);
		writeRect(r+DEST,top+1,left+3,bottom-1,right-3);writeRect(r+VIEW,top+1,left+3,bottom-1,right-3); // HyperCard's field insets
		memory.writeWord(r+TX_FONT,fontId);memory.writeWord(r+TX_SIZE,size);setFace(r,face);
		int[] fi=Fonts68k.fontInfo(Fonts68k.font(fontId,size,face));
		memory.writeWord(r+LINE_HEIGHT,fi[0]+fi[1]+fi[3]);memory.writeWord(r+FONT_ASCENT,fi[0]);
		setText(h,text);
		records.get(h).editable=true; // a HyperCard field: the stack's text
		return h;
	}

	/**
	 * A styled record for a HyperCard field whose text has styles (GetFieldTE): the field's text in style runs, each {startByte,
	 * font, face, size}. HyperCard 2.x hands externals a TEStyleNew record for such a field and SetFieldTE takes the runs back
	 * ({@link #styleRunsOf}): Dartmouth's GetFieldText/SetFieldText copy a field's styled text this way (it came out plain).
	 */
	int createForStyledField(int top,int left,int bottom,int right,List<int[]> styleRuns,byte[] text) throws M68kCpuException {
		int h=create(0,0,true);int r=rec(h);
		writeRect(r+DEST,top+1,left+3,bottom-1,right-3);writeRect(r+VIEW,top+1,left+3,bottom-1,right-3); // HyperCard's field insets
		setText(h,text);
		r=rec(h); // setText's recalc grows the record past 63 lines and it may move: the runs of a long field went to the old place (all plain)
		List<int[]> styles=new ArrayList<int[]>(),runs=new ArrayList<int[]>(); // styles {font, face, size, stCount}; runs {startChar, styleIndex}
		for(int[] run:styleRuns){
			int index=-1;for(int i=0;i<styles.size();i++){int[] e=styles.get(i);if(e[0]==run[1]&&e[1]==run[2]&&e[2]==run[3]){index=i;break;}}
			if(index<0){index=styles.size();styles.add(new int[]{run[1],run[2],run[3],0});}
			int start=Math.max(0,Math.min(text.length,run[0]));
			if(!runs.isEmpty()&&runs.get(runs.size()-1)[0]>=start)runs.remove(runs.size()-1);
			if(!runs.isEmpty()&&runs.get(runs.size()-1)[1]==index)continue;
			runs.add(new int[]{start,index});
		}
		if(runs.isEmpty()){records.get(h).editable=true;records.get(h).keepsStyle=true;return h;}
		runs.get(0)[0]=0;
		for(int[] run:runs)styles.get(run[1])[3]++;
		byte[] table=new byte[styles.size()*STE_SIZE];
		for(int i=0;i<styles.size();i++){int[] e=styles.get(i);int[] fi=Fonts68k.fontInfo(Fonts68k.font(e[0],e[2],e[1]));int at=i*STE_SIZE;
			write16(table,at,e[3]);write16(table,at+2,fi[0]+fi[1]+fi[3]);write16(table,at+4,fi[0]);write16(table,at+6,e[0]);table[at+8]=(byte)e[1];write16(table,at+10,e[2]);}
		int tab=memory.readLong(styleRec(r)+ST_STYLE_TAB);tb.api.resizeHandle(tab,table.length);memory.load(memory.readLong(tab),table);
		memory.writeWord(styleRec(r)+ST_N_STYLES,styles.size());
		runs.add(new int[]{text.length,-1});writeRuns(r,runs);
		recalc(h);
		records.get(h).editable=true; // a HyperCard field: the stack's text
		records.get(h).keepsStyle=true;
		return h;
	}

	/** A StScrpRec of all of a styled record's text (a balloon of it), null for a monostyled record. */
	byte[] styleScrapOf(int handle) throws M68kCpuException {int r=rec(handle);return styleHandle(r)==0?null:styleScrap(r,0,memory.readWord(r+LENGTH));}
	/** The style runs of a styled record as {startByte, font, face, size} (SetFieldTE); null for a monostyled record. */
	List<int[]> styleRunsOf(int handle) throws M68kCpuException {
		int r=rec(handle);int[][] runs=runs(r);if(runs==null)return null;
		List<int[]> out=new ArrayList<int[]>();
		for(int i=0;i+1<runs.length;i++){int e=styleElement(r,runs[i][1]);if(e!=0)out.add(new int[]{runs[i][0],(short)memory.readWord(e+6),memory.readByte(e+8),(short)memory.readWord(e+10)});}
		return out;
	}

	/**
	 * The record becomes one the user edits: its text is no longer translated (VMTranslation). When it was shown translated,
	 * the program's windows are drawn again so the original comes back.
	 */
	private void markEditable(int handle){
		State s=records.get(handle);if(s==null||s.editable)return;
		s.editable=true;
		if(s.translated&&tb.app!=null){
			VMTranslation.Translator t=tb.translator();
			try{if(t!=null)t.eraseOnRedraw(memory.readLong(rec(handle)+IN_PORT));}catch(M68kCpuException ignored){}
			tb.app.redrawForTranslation();}
	}

	void dispose(int handle) throws M68kCpuException {
		State s=records.remove(handle);if(s==null)return;
		int r=rec(handle);int text=memory.readLong(r+H_TEXT);if(text!=0&&s.ownsText)tb.api.disposeHandleAndFree(text);
		int st=styleHandle(r);if(st!=0){int p=memory.readLong(st);if(p!=0)for(int off:new int[]{ST_STYLE_TAB,ST_LH_TAB,ST_NULL_STYLE}){int h=memory.readLong(p+off);if(h!=0)tb.api.disposeHandleAndFree(h);}tb.api.disposeHandleAndFree(st);}
		tb.api.disposeHandleAndFree(handle);
	}

	/** Shares a record with the Dialog Manager (edit text items). */
	int createForDialog(int rectPointer,int textHandle,int window) throws M68kCpuException {
		int h=create(rectPointer,rectPointer,false);int r=rec(h);memory.writeLong(r+IN_PORT,window);
		// The dialog item's Handle is the record's hText, as on a real Mac (the dialog owns it).
		if(textHandle!=0){int own=memory.readLong(r+H_TEXT);if(own!=0)tb.api.disposeHandleAndFree(own);memory.writeLong(r+H_TEXT,textHandle);records.get(h).ownsText=false;}
		int[] fi=Fonts68k.fontInfo(Fonts68k.font(0,12,0));memory.writeWord(r+TX_FONT,0);memory.writeWord(r+TX_SIZE,12);setFace(r,0);memory.writeWord(r+LINE_HEIGHT,fi[0]+fi[1]+fi[3]);memory.writeWord(r+FONT_ASCENT,fi[0]);
		// destRect = viewRect = the item's rectangle, as the Dialog Manager sets them: its frame is drawn 3 pixels OUTSIDE the item.
		// (An extra inset of 2 clipped the descenders of 12-point text in a standard 16-pixel field, and the bottom of the digits
		// in Fractal Wizard's 13-pixel Geneva 9 fields.)
		if(textHandle!=0){byte[] bytes=tb.api.handleContents(textHandle);setText(h,bytes==null?new byte[0]:bytes);}
		records.get(h).editable=true;
		return h;
	}

	byte[] text(int handle) throws M68kCpuException {int r=rec(handle);byte[] t=tb.api.handleContents(memory.readLong(r+H_TEXT));int n=memory.readWord(r+LENGTH);if(t==null)return new byte[0];return t.length==n?t:java.util.Arrays.copyOf(t,Math.min(n,t.length));}

	void setText(int handle,byte[] bytes) throws M68kCpuException {
		int r=rec(handle);tb.api.loadHandle(memory.readLong(r+H_TEXT),bytes);memory.writeWord(r+LENGTH,bytes.length);
		if(styleHandle(r)!=0)resetStyles(r,records.get(handle));
		State state=records.get(handle);if(state!=null)state.nullStyle=null;
		recalc(handle);memory.writeWord(r+SEL_START,bytes.length);memory.writeWord(r+SEL_END,bytes.length);
	}
	/**
	 * TESetText on a styled record: the old runs belong to the old text, so the new text becomes one run in the font the
	 * record's port currently has. HyperCard 2.3 (the Japanese J-2.3) draws every field through one TEStyleNew record:
	 * TextFont/TextSize on the card port, TESetText, TESetStyle only where the field has style runs, TEUpdate - its
	 * TECustomHook drawing hooks (not run here) took the font from the port, so without this every field kept the runs
	 * of the last styled one. The style table is rebuilt from scratch rather than appended to (one record serves every
	 * field of every card).
	 */
	private void resetStyles(int r,State state) throws M68kCpuException {
		int st=styleRec(r);if(st==0)return;int tab=memory.readLong(st+ST_STYLE_TAB);if(tab==0)return;
		int port=memory.readLong(r+IN_PORT);int font=0,face=0,size=0;
		int first=state!=null&&state.keepsStyle&&(short)memory.readWord(st+ST_N_STYLES)>0?Math.max(0,(short)memory.readWord(st+ST_RUNS+2)):-1;
		if(first>=0&&tb.api.handleSize(tab)>=(first+1)*STE_SIZE){int e=memory.readLong(tab)+first*STE_SIZE;font=(short)memory.readWord(e+6);face=memory.readByte(e+8);size=(short)memory.readWord(e+10);}
		else if(port!=0){font=(short)memory.readWord(port+68);face=memory.readByte(port+70);size=(short)memory.readWord(port+74);} // GrafPort txFont 68, txFace 70 (a Style byte), txSize 74
		int[] fi=Fonts68k.fontInfo(Fonts68k.font(font,size,face));
		byte[] e=new byte[STE_SIZE];write16(e,0,1);write16(e,2,fi[0]+fi[1]+fi[3]);write16(e,4,fi[0]);write16(e,6,font);e[8]=(byte)face;write16(e,10,size);
		tb.api.resizeHandle(tab,STE_SIZE);memory.load(memory.readLong(tab),e);
		st=styleRec(r);memory.writeWord(st+ST_N_STYLES,1);
		List<int[]> runs=new ArrayList<int[]>();runs.add(new int[]{0,0});runs.add(new int[]{memory.readWord(r+LENGTH),-1});writeRuns(r,runs);
	}

	void setSelection(int handle,int start,int end,boolean redraw) throws M68kCpuException {
		int r=rec(handle);int length=memory.readWord(r+LENGTH);
		start=Math.max(0,Math.min(length,start));end=Math.max(start,Math.min(length,end));
		int oldStart=memory.readWord(r+SEL_START),oldEnd=memory.readWord(r+SEL_END);
		memory.writeWord(r+SEL_START,start);memory.writeWord(r+SEL_END,end);
		if(start!=oldStart||end!=oldEnd){State s=records.get(handle);if(s!=null)s.nullStyle=null;} // a new selection drops the null style
		if(!redraw||memory.readWord(r+ACTIVE)==0)return; // an inactive record shows no highlight or caret: TESetSelect draws nothing there (奈美江ちゃんとの世界's runtime selects all in text blocks it keeps invisible)
		// only the lines whose highlight (or caret) changes are redrawn: the old selection's lines and the new one's
		int a0=lineOf(r,oldStart),a1=lineOf(r,oldEnd),b0=lineOf(r,start),b1=lineOf(r,end);
		if(Math.max(a0,b0)<=Math.min(a1,b1)+1)update(handle,Math.min(a0,b0),Math.max(a1,b1));
		else{update(handle,a0,a1);update(handle,b0,b1);}
	}

	// ------------------------------------------------------------------ layout

	private Font font(int r) throws M68kCpuException {
		if(styleHandle(r)!=0){int[][] runs=runs(r);return styleFont(r,runs!=null&&runs.length>0?runs[0][1]:0);}
		return Fonts68k.font((short)memory.readWord(r+TX_FONT),(short)memory.readWord(r+TX_SIZE),face(r));}
	/** TERec.txFace is a Style byte (the byte after it is padding, as in a GrafPort). */
	private int face(int r) throws M68kCpuException {return memory.readByte(r+TX_FACE);}
	private void setFace(int r,int face) throws M68kCpuException {memory.writeByte(r+TX_FACE,face&0xff);memory.writeByte(r+TX_FACE+1,0);}

	private void syncLengthToText(int handle) throws M68kCpuException {
		int r=rec(handle);int text=memory.readLong(r+H_TEXT);if(text==0)return;
		int n=Math.min(Math.max(0,tb.api.handleSize(text)),32767);memory.writeWord(r+LENGTH,n);
		if(memory.readWord(r+SEL_END)>n)memory.writeWord(r+SEL_END,n);if(memory.readWord(r+SEL_START)>n)memory.writeWord(r+SEL_START,n);
	}

	void recalc(int handle) throws M68kCpuException {
		int r=rec(handle);byte[] text=text(handle);Font font=font(r);boolean styled=styleHandle(r)!=0;
		int[] dest=rect(r+DEST);int width=Math.max(8,dest[3]-dest[1]);boolean crOnly=(short)memory.readWord(r+CR_ONLY)<0;
		List<Integer> starts=lineStarts(text,styled?r:0,font,width,crOnly);
		if(!styled){int[] fi=Fonts68k.fontInfo(font);memory.writeWord(r+LINE_HEIGHT,fi[0]+fi[1]+fi[3]);memory.writeWord(r+FONT_ASCENT,fi[0]);} // a styled record's are the program's (fixedMetrics)
		int size=tb.api.handleSize(handle);int needed=LINE_STARTS+(starts.size()+1)*2;
		if(size<needed){tb.api.resizeHandle(handle,needed+128);r=rec(handle);}
		memory.writeWord(r+N_LINES,starts.size());
		for(int i=0;i<starts.size();i++)memory.writeWord(r+LINE_STARTS+i*2,starts.get(i));
		memory.writeWord(r+LINE_STARTS+starts.size()*2,text.length);
		if(styled)writeLineHeights(r);
	}

	/** Line starts of {@code text} wrapped to {@code width}; a styled record ({@code r} non-zero) measures through its style runs. */
	private List<Integer> lineStarts(byte[] text,int r,Font font,int width,boolean crOnly) throws M68kCpuException {
		List<Integer> starts=new ArrayList<Integer>();starts.add(0);
		int lineStart=0;int i=0;int lastBreak=-1;
		while(i<text.length){
			int c=text[i]&0xff;int charLen=(tb.encoding==StackTextEncoding.JAPANESE&&Toolbox68k.isJapaneseLead(c)&&i+1<text.length)?2:1;
			if(c=='\r'){i+=1;lineStart=i;starts.add(lineStart);lastBreak=-1;continue;}
			if(!crOnly){
				int w=r!=0?measure(r,text,lineStart,i+charLen):Fonts68k.width(font,tb.decodeFor(font,java.util.Arrays.copyOfRange(text,lineStart,i+charLen)));
				if(w>width&&i>lineStart){
					int breakAt=lastBreak>lineStart?lastBreak:i;
					lineStart=breakAt;starts.add(lineStart);i=breakAt;lastBreak=-1;continue;
				}
			}
			if(c==' ')lastBreak=i+1;else if(charLen==2)lastBreak=i+2;
			i+=charLen;
		}
		return starts;
	}

	private int lineCount(int r) throws M68kCpuException {return Math.max(1,memory.readWord(r+N_LINES));}
	private int lineStart(int r,int line) throws M68kCpuException {return memory.readWord(r+LINE_STARTS+line*2);}
	private int lineEnd(int r,int line) throws M68kCpuException {return memory.readWord(r+LINE_STARTS+(line+1)*2);}
	private int lineOf(int r,int offset) throws M68kCpuException {int n=lineCount(r);for(int i=0;i<n;i++)if(offset<lineEnd(r,i)||(i==n-1))return i;return n-1;}

	/** Horizontal pixel of an offset within its line, including justification. */
	private int xOf(int r,byte[] text,Font font,int line,int offset) throws M68kCpuException {
		int start=lineStart(r,line),end=lineEnd(r,line);int[] dest=rect(r+DEST);boolean styled=styleHandle(r)!=0;
		int lineWidth=styled?measure(r,text,start,trimmedEnd(text,start,end)):Fonts68k.width(font,tb.decodeFor(font,trimCr(text,start,end)));
		int just=(short)memory.readWord(r+JUST);int base=dest[1];
		if(just==1)base=dest[1]+(dest[3]-dest[1]-lineWidth)/2;else if(just==-1)base=dest[3]-lineWidth;
		offset=Math.max(start,Math.min(end,offset));
		return base+(styled?measure(r,text,start,trimmedEnd(text,start,offset)):Fonts68k.width(font,tb.decodeFor(font,trimCr(text,start,offset))));
	}
	private int trimmedEnd(byte[] text,int start,int end){end=Math.min(end,text.length);start=Math.min(start,end);if(end>start&&text[end-1]=='\r')end--;return end;}
	private byte[] trimCr(byte[] text,int start,int end){end=Math.min(end,text.length);start=Math.min(start,end);if(end>start&&text[end-1]=='\r')end--;return java.util.Arrays.copyOfRange(text,start,end);}

	// ------------------------------------------------------------------ styled records
	/*
	 * A styled record (TEStyleNew) carries a TEStyleRec (IM Text 2-73) in guest memory as the ROM's does: txSize is -1 and
	 * txFont/txFace hold its Handle. HyperCard 2.4 lays its fields out itself - it resizes the record and the style
	 * tables with SetHandleSize, writes the line starts, the line-height table and the style runs, and calls TEUpdate -
	 * so the tables are real Memory Manager blocks and drawing reads them back rather than a host-side model.
	 *   TEStyleRec: nRuns 0, nStyles 2, styleTab 4, lhTab 8, teRefCon 12, nullStyle 16, runs 20
	 *     (StyleRun: startChar 0, styleIndex 2; nRuns+1 entries, the last one starting at teLength)
	 *   STElement (18 bytes): stCount 0, stHeight 2, stAscent 4, stFont 6, stFace 8, stSize 10, stColor 12
	 *   LHElement (4 bytes): lhHeight 0, lhAscent 2
	 * The record's own lineHeight/fontAscent are -1 after TEStyleNew (variable line heights); positive values the program
	 * writes there give every line that height and ascent, and TECalText (TESetText) leaves them alone: HyperCard J-2.3
	 * writes textHeight and its ascent (20/15 for Osaka 12 with textHeight 20) for a fixedLineHeight field, -1/-1 for
	 * the others, and then calls TESetText (masaruquiz: rows of a fixed 20-pixel field were drawn 16 apart).
	 */
	private static final int ST_N_RUNS=0,ST_N_STYLES=2,ST_STYLE_TAB=4,ST_LH_TAB=8,ST_NULL_STYLE=16,ST_RUNS=20,RUN_SIZE=4,STE_SIZE=18,LHE_SIZE=4;
	private static void write16(byte[] b,int at,int v){b[at]=(byte)(v>>8);b[at+1]=(byte)v;}
	private static void write32(byte[] b,int at,int v){b[at]=(byte)(v>>24);b[at+1]=(byte)(v>>16);b[at+2]=(byte)(v>>8);b[at+3]=(byte)v;}
	/** The record's TEStyleHandle, or 0 for a monostyled record. */
	private int styleHandle(int r) throws M68kCpuException {return (short)memory.readWord(r+TX_SIZE)==-1?memory.readLong(r+TX_FONT):0;}
	private int styleRec(int r) throws M68kCpuException {int h=styleHandle(r);return h==0?0:memory.readLong(h);}
	/** The first style takes the port's txFont/txFace/txSize AND its foreground colour (IM Text 2-81, TEStyleNew): Textoid sets
	 *  RGBForeColor(white or red) before TEStyleNew, and its text came out black ({@link #segmentColor} draws stColor). */
	private int createStyleRec(int font,int face,int size,int height,int ascent,int[] rgb) throws M68kCpuException {
		byte[] ste=new byte[STE_SIZE];write16(ste,0,1);write16(ste,2,height);write16(ste,4,ascent);write16(ste,6,font);ste[8]=(byte)face;write16(ste,10,size);
		for(int i=0;i<3;i++)write16(ste,12+2*i,rgb[i]);
		byte[] lhe=new byte[LHE_SIZE];write16(lhe,0,height);write16(lhe,2,ascent);
		int styleTab=tb.api.createHandle(ste),lhTab=tb.api.createHandle(lhe),nullStyle=tb.api.createHandle(new byte[8]);
		byte[] st=new byte[ST_RUNS+2*RUN_SIZE];write16(st,ST_N_RUNS,1);write16(st,ST_N_STYLES,1);write32(st,ST_STYLE_TAB,styleTab);write32(st,ST_LH_TAB,lhTab);write32(st,ST_NULL_STYLE,nullStyle);
		return tb.api.createHandle(st);
	}
	/** The style runs as {startChar, styleIndex} pairs, the sentinel last; null for a monostyled record. */
	private int[][] runs(int r) throws M68kCpuException {
		int h=styleHandle(r);int st=h==0?0:memory.readLong(h);if(st==0)return null;
		int n=Math.max(0,(short)memory.readWord(st+ST_N_RUNS));n=Math.min(n,Math.max(0,(tb.api.handleSize(h)-ST_RUNS)/RUN_SIZE-1));
		int[][] runs=new int[n+1][];for(int i=0;i<=n;i++)runs[i]=new int[]{memory.readWord(st+ST_RUNS+i*RUN_SIZE),(short)memory.readWord(st+ST_RUNS+i*RUN_SIZE+2)};
		return runs;
	}
	private void writeRuns(int r,List<int[]> runs) throws M68kCpuException {
		int h=styleHandle(r);if(h==0)return;int needed=ST_RUNS+runs.size()*RUN_SIZE;if(tb.api.handleSize(h)<needed)tb.api.resizeHandle(h,needed);
		int st=memory.readLong(h);memory.writeWord(st+ST_N_RUNS,runs.size()-1);
		for(int i=0;i<runs.size();i++){memory.writeWord(st+ST_RUNS+i*RUN_SIZE,runs.get(i)[0]);memory.writeWord(st+ST_RUNS+i*RUN_SIZE+2,runs.get(i)[1]);}
	}
	/** Address of style element {@code index} of the record's style table, or 0. */
	private int styleElement(int r,int index) throws M68kCpuException {
		int st=styleRec(r);int tab=st==0?0:memory.readLong(st+ST_STYLE_TAB);int p=tab==0?0:memory.readLong(tab);
		if(p==0||index<0||(index+1)*STE_SIZE>tb.api.handleSize(tab))return 0;return p+index*STE_SIZE;
	}
	private int styleIndexAt(int r,int offset) throws M68kCpuException {
		int[][] runs=runs(r);if(runs==null||runs.length<2)return 0;int index=runs[0][1];
		for(int i=0;i+1<runs.length;i++)if(offset>=runs[i][0])index=runs[i][1];return index;
	}
	private Font styleFont(int r,int index) throws M68kCpuException {
		int e=styleElement(r,index);if(e==0)return Fonts68k.font(0,12,0);
		return Fonts68k.font((short)memory.readWord(e+6),(short)memory.readWord(e+10),memory.readByte(e+8));
	}
	/** {height, ascent} of a style: its element's values when set, else those of its font. */
	private int[] styleMetrics(int r,int index) throws M68kCpuException {
		if(index<0)return new int[]{(short)memory.readWord(r+LINE_HEIGHT),(short)memory.readWord(r+FONT_ASCENT)};
		int e=styleElement(r,index);int h=e==0?0:(short)memory.readWord(e+2),a=e==0?0:(short)memory.readWord(e+4);
		if(h>0)return new int[]{h,a};int[] fi=Fonts68k.fontInfo(styleFont(r,index));return new int[]{fi[0]+fi[1]+fi[3],fi[0]};
	}
	/** text[start..end) cut at the style runs: {segmentStart, segmentEnd, styleIndex}; one segment of index -1 for a monostyled record. */
	private List<int[]> segments(int r,int start,int end) throws M68kCpuException {
		List<int[]> out=new ArrayList<int[]>();int[][] runs=runs(r);
		if(runs==null||runs.length<2||end<=start){out.add(new int[]{start,Math.max(start,end),runs==null?-1:runs[0][1]});return out;}
		int n=runs.length-1;
		for(int i=0;i<n;i++){int a=Math.max(start,runs[i][0]),b=Math.min(end,i+1<n?runs[i+1][0]:Integer.MAX_VALUE);if(b>a)out.add(new int[]{a,b,runs[i][1]});}
		if(out.isEmpty())out.add(new int[]{start,end,runs[n-1][1]});
		return out;
	}
	private Font segmentFont(int r,int styleIndex,Font base) throws M68kCpuException {return styleIndex<0?base:styleFont(r,styleIndex);}
	private int segmentFace(int r,int styleIndex) throws M68kCpuException {int e=styleIndex<0?0:styleElement(r,styleIndex);return e==0?face(r):memory.readByte(e+8);}
	/** Width of text[start..end) through the style runs. */
	private int measure(int r,byte[] text,int start,int end) throws M68kCpuException {
		start=Math.max(0,Math.min(text.length,start));end=Math.max(start,Math.min(text.length,end));int w=0;Font base=font(r);
		for(int[] seg:segments(r,start,end)){Font f=segmentFont(r,seg[2],base);w+=Fonts68k.width(f,tb.decodeFor(f,java.util.Arrays.copyOfRange(text,seg[0],seg[1])));}
		return w;
	}
	/** {height, ascent} of line {@code i}: the line-height table's entry when the record is styled and has one, else the tallest style on the line. */
	private int[] lineMetrics(int r,int i) throws M68kCpuException {
		int st=styleRec(r);if(st==0)return new int[]{Math.max(0,(short)memory.readWord(r+LINE_HEIGHT)),(short)memory.readWord(r+FONT_ASCENT)};
		int[] fixed=fixedMetrics(r);if(fixed!=null)return fixed;
		int lh=memory.readLong(st+ST_LH_TAB);int p=lh==0?0:memory.readLong(lh);
		if(p!=0&&(i+1)*LHE_SIZE<=tb.api.handleSize(lh)){int h=(short)memory.readWord(p+i*LHE_SIZE),a=(short)memory.readWord(p+i*LHE_SIZE+2);if(h>0)return new int[]{h,a};}
		return computedLineMetrics(r,i);
	}
	/** {lineHeight, fontAscent} of a styled record whose program set a fixed line height (positive lineHeight), else null. */
	private int[] fixedMetrics(int r) throws M68kCpuException {
		int h=(short)memory.readWord(r+LINE_HEIGHT),a=(short)memory.readWord(r+FONT_ASCENT);return h>0?new int[]{h,a>0?a:h}:null;
	}
	private int[] computedLineMetrics(int r,int i) throws M68kCpuException {
		int start=lineStart(r,i),end=Math.max(start+1,lineEnd(r,i));int height=0,ascent=0;
		for(int[] seg:segments(r,start,end)){int[] m=styleMetrics(r,seg[2]);height=Math.max(height,m[0]);ascent=Math.max(ascent,m[1]);}
		return new int[]{height,ascent};
	}
	/** Tops of the lines relative to the destination rectangle's top, with the text's height after the last (line i spans tops[i]..tops[i+1]). */
	private int[] lineTops(int r) throws M68kCpuException {
		int n=lineCount(r);int[] tops=new int[n+1];
		if(styleHandle(r)==0){int lh=Math.max(0,(short)memory.readWord(r+LINE_HEIGHT));for(int i=0;i<=n;i++)tops[i]=i*lh;return tops;}
		int y=0;for(int i=0;i<n;i++){tops[i]=y;y+=lineMetrics(r,i)[0];}tops[n]=y;return tops;
	}
	/** The line at a vertical offset from the destination's top (clamped to the first and last lines). */
	private int lineAt(int r,int dy) throws M68kCpuException {int[] tops=lineTops(r);int n=tops.length-1;for(int i=0;i<n;i++)if(dy<tops[i+1])return Math.max(0,i);return Math.max(0,n-1);}
	/** TECalText of a styled record: the line-height table gets one entry per line. */
	private void writeLineHeights(int r) throws M68kCpuException {
		int st=styleRec(r);if(st==0)return;int lh=memory.readLong(st+ST_LH_TAB);if(lh==0)return;int n=lineCount(r);
		if(tb.api.handleSize(lh)<n*LHE_SIZE)tb.api.resizeHandle(lh,n*LHE_SIZE);int p=memory.readLong(lh);
		for(int i=0;i<n;i++){int[] m=computedLineMetrics(r,i);memory.writeWord(p+i*LHE_SIZE,m[0]);memory.writeWord(p+i*LHE_SIZE+2,m[1]);}
	}
	/** After text[start..end) became {@code inserted} bytes: the runs after the edit move, emptied ones go. */
	private void shiftRuns(int r,int start,int end,int inserted) throws M68kCpuException {
		int[][] runs=runs(r);if(runs==null||runs.length<2)return;int delta=inserted-(end-start);int length=memory.readWord(r+LENGTH);
		List<int[]> out=new ArrayList<int[]>();
		for(int i=0;i+1<runs.length;i++){int a=runs[i][0];if(a>start)a=Math.max(start,a+delta);if(!out.isEmpty()&&out.get(out.size()-1)[0]==a)out.remove(out.size()-1);out.add(new int[]{a,runs[i][1]});}
		if(out.isEmpty())out.add(new int[]{0,runs[0][1]});out.add(new int[]{length,-1});
		writeRuns(r,out);
	}
	/** TESetStyle(mode, style) over text[a..b): a new style element (the run's, changed as the mode says) and runs split around the range. */
	private void applyStyle(int r,int a,int b,int mode,int style) throws M68kCpuException {
		int st=styleRec(r);if(st==0)return;int tab=memory.readLong(st+ST_STYLE_TAB);if(tab==0)return;
		int from=styleElement(r,styleIndexAt(r,a));byte[] e=new byte[STE_SIZE];if(from!=0)e=memory.readBytes(from,STE_SIZE);write16(e,0,1);
		if((mode&1)!=0)write16(e,6,memory.readWord(style));
		if((mode&2)!=0){int f=memory.readByte(style+2);if((mode&32)!=0){int current=e[8]&0xff;f=(current&f)==f?current&~f:current|f;}e[8]=(byte)f;}
		if((mode&4)!=0&&(short)memory.readWord(style+4)!=0){int size=(short)memory.readWord(style+4);if((mode&16)!=0)size+=(short)memory.readWord(e[10]<<8|e[11]&0xff);write16(e,10,Math.max(1,size));} // doAddSize
		if((mode&8)!=0)for(int i=0;i<6;i++)e[12+i]=(byte)memory.readByte(style+6+i);
		int[] fi=Fonts68k.fontInfo(Fonts68k.font((short)(e[6]<<8|e[7]&0xff),(short)(e[10]<<8|e[11]&0xff),e[8]));write16(e,2,fi[0]+fi[1]+fi[3]);write16(e,4,fi[0]);
		// an element with the same attributes is shared (its stCount goes up), as the ROM does: appending one per call grew the
		// table without end - HyperCard restyles its one field record on every field it loads, and nStyles is a word
		int count=Math.max(0,tb.api.handleSize(tab)/STE_SIZE),added=-1;
		for(int i=0;i<count&&added<0;i++){int p=memory.readLong(tab)+i*STE_SIZE;boolean same=true;for(int k=2;k<STE_SIZE&&same;k++)same=(byte)memory.readByte(p+k)==e[k];
			if(same){added=i;memory.writeWord(p,memory.readWord(p)+1);}}
		if(added<0){added=count;tb.api.resizeHandle(tab,(count+1)*STE_SIZE);memory.load(memory.readLong(tab)+count*STE_SIZE,e);st=styleRec(r);memory.writeWord(st+ST_N_STYLES,count+1);}
		int[][] runs=runs(r);List<int[]> out=new ArrayList<int[]>();int n=runs.length-1;int length=memory.readWord(r+LENGTH);
		for(int i=0;i<n;i++){int s0=runs[i][0],s1=i+1<n?runs[i+1][0]:length,index=runs[i][1];
			if(s1<=a||s0>=b){out.add(new int[]{s0,index});continue;}
			if(s0<a)out.add(new int[]{s0,index});out.add(new int[]{Math.max(s0,a),added});if(s1>b)out.add(new int[]{b,index});}
		List<int[]> merged=new ArrayList<int[]>();for(int[] run:out){if(!merged.isEmpty()&&merged.get(merged.size()-1)[1]==run[1])continue;if(!merged.isEmpty()&&merged.get(merged.size()-1)[0]==run[0])merged.remove(merged.size()-1);merged.add(run);}
		merged.add(new int[]{length,-1});writeRuns(r,merged);
	}
	/** The end of the style run holding {@code offset} (the text's length for the last run). */
	private int runEndAt(int r,int offset) throws M68kCpuException {
		int[][] runs=runs(r);int length=memory.readWord(r+LENGTH);if(runs==null)return length;
		int n=runs.length-1;for(int i=0;i<n;i++){int s1=i+1<n?runs[i+1][0]:length;if(offset>=runs[i][0]&&offset<s1)return s1;}
		return length;
	}
	/** Whether the run at {@code offset} has the attributes {@code mode} names (doFont 1, doFace 2, doSize 4, doColor 8) of TextStyle {@code old}. */
	private boolean runMatches(int r,int offset,int mode,int old) throws M68kCpuException {
		int e=styleElement(r,styleIndexAt(r,offset));if(e==0)return false;
		if((mode&1)!=0&&memory.readWord(e+6)!=memory.readWord(old))return false;
		if((mode&2)!=0&&(memory.readByte(e+8)&0xff)!=(memory.readByte(old+2)&0xff))return false;
		if((mode&4)!=0&&memory.readWord(e+10)!=memory.readWord(old+4))return false;
		if((mode&8)!=0)for(int i=0;i<6;i++)if(memory.readByte(e+12+i)!=memory.readByte(old+6+i))return false;
		return true;
	}
	/**
	 * TESetStyle / TEReplaceStyle over [a,b) of a styled record, one run at a time: each run keeps what {@code mode} leaves alone
	 * (one style for the whole range took the first run's font and size everywhere). With {@code oldStyle}, only the runs that
	 * match it change. doToggle looks at the whole range: the face goes when every run has it, else every run gets it.
	 */
	private void applyStyleByRun(int r,int a,int b,int mode,int style,int oldStyle) throws M68kCpuException {
		// only the text: HyperCard styles a whole field with selEnd 32767, and every offset past the end was a "run" of its own
		b=Math.min(b,memory.readWord(r+LENGTH));if(b<=a)return;
		boolean toggle=(mode&32)!=0&&(mode&2)!=0;boolean remove=false;int face=memory.readByte(style+2)&0xff;
		if(toggle){remove=true;for(int pos=a;pos<b;pos=Math.min(b,Math.max(pos+1,runEndAt(r,pos)))){int e=styleElement(r,styleIndexAt(r,pos));if(e==0||(memory.readByte(e+8)&face)!=face){remove=false;break;}}}
		int tmp=toggle?memory.allocate(12,2):0;
		try{
			for(int pos=a;pos<b;){int end=Math.min(b,Math.max(pos+1,runEndAt(r,pos)));
				if(oldStyle==0||runMatches(r,pos,mode,oldStyle)){
					if(toggle){int e=styleElement(r,styleIndexAt(r,pos));int current=e==0?0:memory.readByte(e+8)&0xff;
						memory.load(tmp,memory.readBytes(style,12));memory.writeByte(tmp+2,remove?current&~face:current|face);applyStyle(r,pos,end,mode&~32,tmp);}
					else applyStyle(r,pos,end,mode,style);}
				pos=end;}
		}finally{if(tmp!=0)memory.free(tmp);}
	}
	/** TEStylInsert: the scrap's elements style the inserted text (StScrpRec: scrpNStyles, then 20-byte ScrpSTElements: startChar 0, height 4, ascent 6, font 8, face 10, size 12, color 14). */
	private void applyScrapStyles(int handle,int scrap,int at,int length) throws M68kCpuException {
		int r=rec(handle);int n=memory.readWord(scrap);
		for(int k=0;k<n;k++){int e=scrap+2+k*20;int a=at+memory.readLong(e),b=k+1<n?at+memory.readLong(e+20):at+length;if(b<=a)continue;
			byte[] style=new byte[12];write16(style,0,memory.readWord(e+8));style[2]=(byte)memory.readByte(e+10);write16(style,4,memory.readWord(e+12));for(int i=0;i<6;i++)style[6+i]=(byte)memory.readByte(e+14+i);
			int tmp=memory.allocate(12,2);try{memory.load(tmp,style);applyStyle(r,a,b,1|2|4|8,tmp);}finally{memory.free(tmp);}}
		recalc(handle);
	}

	// ------------------------------------------------------------------ drawing

	/** The whole line range: a partial {@link #update} down to the bottom of the view. */
	private static final int TO_BOTTOM=Integer.MAX_VALUE;

	void update(int handle) throws M68kCpuException {update(handle,0,TO_BOTTOM,true);}
	void update(int handle,int firstLine,int lastLine) throws M68kCpuException {update(handle,firstLine,lastLine,true);}

	/** The text colour of a style run: its element's RGB colour in a styled record (QuickTime's karaoke text is dark red), else the port's. */
	private Color segmentColor(int r,int styleIndex,Color ink) throws M68kCpuException {
		int e=styleIndex<0||styleHandle(r)==0?0:styleElement(r,styleIndex);if(e==0)return ink;
		return new Color(memory.readWord(e+12)>>8,memory.readWord(e+14)>>8,memory.readWord(e+16)>>8);
	}
	/** Trace aid: the style runs and their elements of a styled record. */
	private String describeStyles(int r) throws M68kCpuException {
		int[][] runs=runs(r);if(runs==null)return "";StringBuilder sb=new StringBuilder(" runs");
		for(int[] run:runs){sb.append(' ').append(run[0]).append(':').append(run[1]);int e=run[1]<0?0:styleElement(r,run[1]);
			if(e!=0)sb.append("(font ").append((short)memory.readWord(e+6)).append(" size ").append((short)memory.readWord(e+10)).append(" face ").append(memory.readByte(e+8)).append(" h ").append((short)memory.readWord(e+2)).append(" a ").append((short)memory.readWord(e+4)).append(')');}
		return sb.toString();
	}
	/**
	 * Erases and redraws the lines {@code firstLine..lastLine} (inclusive; {@link #TO_BOTTOM} = down to the bottom of the
	 * view) within the view rectangle. Editing and selection changes redraw only their own lines, as TextEdit does: a
	 * full redraw of a long script at every keystroke was caught half-drawn by the host refresh and flickered.
	 * {@code erase} is false for TEUpdate: like the ROM's, it paints the text over whatever the view holds and leaves
	 * erasing to the caller (HyperCard draws its transparent fields onto the card picture this way, erasing only the
	 * rectangle-style ones itself; fields whose rectangles overlap must not wipe each other's text). Editing redraws erase.
	 */
	void update(int handle,int firstLine,int lastLine,boolean erase) throws M68kCpuException {
		int r=rec(handle);int port=memory.readLong(r+IN_PORT);
		byte[] text=text(handle);Font font=font(r);FontMetrics fm=Fonts68k.metrics(font);
		int[] view=rect(r+VIEW),dest=rect(r+DEST);int[] tops=lineTops(r);int n=lineCount(r);
		int selStart=memory.readWord(r+SEL_START),selEnd=memory.readWord(r+SEL_END);boolean active=memory.readWord(r+ACTIVE)!=0;
		State s=records.get(handle);
		if(s!=null){int caretLine=lineOf(r,selEnd);s.caretX=xOf(r,text,font,caretLine,selEnd);s.caretY=dest[0]+tops[caretLine];s.caretHeight=tops[caretLine+1]-tops[caretLine];}
		if(Mac68kApi.TRACE)System.err.println("[68K] TE update $"+Integer.toHexString(handle)+" port $"+Integer.toHexString(port)+" view "+java.util.Arrays.toString(view)+" dest "+java.util.Arrays.toString(dest)+" lines "+firstLine+".."+lastLine+" length "+text.length+" current $"+Integer.toHexString(tb.api.currentQuickDrawPort())+describeStyles(r));
		if(port!=0&&(short)memory.readWord(port+66)<0)return; // pnVis < 0 (HidePen): TextEdit draws through QuickDraw, which draws nothing then - 奈美江ちゃんとの世界's runtime fills its invisible text blocks between HidePen/ShowPen
		if(s!=null&&!s.editable&&firstLine==0&&lastLine==TO_BOTTOM&&drawTranslated(r,s,text,view,dest,erase))return; // a read-only record in the user's language (VMTranslation)
		if(s!=null)s.translated=false;
		int top=Math.max(view[0],dest[0]+tops[Math.min(n,Math.max(0,firstLine))]);
		int bottom=lastLine==TO_BOTTOM?view[2]:Math.min(view[2],dest[0]+tops[Math.min(n,lastLine+1)]);
		int left=view[1],right=view[3];
		// only where QuickDraw would draw: portRect, visRgn and clipRgn (Rinaldi's ReplaceAttributes points inPort at a port of its
		// own with empty regions; the text it redrew landed on the card)
		int[] drawable=portDrawable(port);
		if(drawable!=null){top=Math.max(top,drawable[0]);left=Math.max(left,drawable[1]);bottom=Math.min(bottom,drawable[2]);right=Math.min(right,drawable[3]);}
		if(bottom<=top||right<=left)return;
		// TextEdit draws with DrawText, so through the port's own textProc when it has one (drawn here, it was never called)
		int textProc=port!=0&&tb.api.currentQuickDrawPort()==port?tb.api.quickDraw().customBottleneck(0):0;
		Graphics2D g=textProc!=0?null:tb.quickDraw.surface(port,new int[]{top,left,bottom,right});
		if(g==null){ // non-direct port: draw text only through QuickDraw, kept inside the view rectangle (a field's text is often longer than its view)
			boolean own=tb.api.currentQuickDrawPort()==port;
			Object savedClip=tb.api.quickDraw().setHostClip(top,left,bottom,right);
			try{
				if(erase)tb.api.quickDraw().fillRgb(top,left,bottom,right,own?tb.api.quickDraw().backgroundRgb():0xffffff);
				for(int i=0;i<n;i++){int y=dest[0]+tops[i],lineHeight=tops[i+1]-tops[i],ascent=lineMetrics(r,i)[1];if(y+lineHeight<=top||y>=bottom)continue;
					int start=lineStart(r,i),x=xOf(r,text,font,i,start);
					for(int[] seg:segments(r,start,trimmedEnd(text,start,lineEnd(r,i)))){Font f=segmentFont(r,seg[2],font);byte[] part=java.util.Arrays.copyOfRange(text,seg[0],seg[1]);
						if(x>=view[3])break; // the rest of the line lies right of the view
						int e=own&&seg[2]>=0&&styleHandle(r)!=0?styleElement(r,seg[2]):0;Object[] saved=e!=0?tb.api.quickDraw().saveForeground():null; // a styled run draws in its own colour (TESetStyle doColor)
						if(saved!=null)tb.api.quickDraw().setForegroundRgb16(memory.readWord(e+12),memory.readWord(e+14),memory.readWord(e+16));
						try{x+=textProc!=0?drawThroughTextProc(textProc,r,port,seg,x,y+ascent,f,part):tb.quickDraw.drawText(part,x,y+ascent,f,1,f instanceof BitmapFont68k?((BitmapFont68k)f).face:segmentFace(r,seg[2]));}finally{tb.api.quickDraw().restoreForeground(saved);}}}
			}finally{tb.api.quickDraw().restoreHostClip(savedClip);}
			return;
		}
		// the colours of the record's own port, current or not (TextEdit draws in inPort with them: a coloured dialog's fields had a
		// white text area when the Dialog Manager redrew them with another port current); a styled record's runs carry their own text colour
		int[] portColors=tb.api.quickDraw().colorsOf(port);Color ink=new Color(portColors[0]),paper=new Color(portColors[1]);
		try{
			g.setClip(left,top,Math.max(0,right-left),bottom-top);
			if(erase){g.setColor(paper);g.fillRect(left,top,right-left,bottom-top);}
			g.setFont(font);
			for(int i=0;i<n;i++){
				int y=dest[0]+tops[i],lineHeight=tops[i+1]-tops[i],ascent=lineMetrics(r,i)[1];if(y+lineHeight<=top||y>=bottom)continue;
				int start=lineStart(r,i),end=lineEnd(r,i);
				if(active&&selEnd>selStart&&selEnd>start&&selStart<end){int a=Math.max(start,selStart),b=Math.min(end,selEnd);int x1=xOf(r,text,font,i,a),x2=b>=end&&end>start&&text[end-1]=='\r'?dest[3]:xOf(r,text,font,i,b);g.setColor(SELECTION);g.fillRect(x1,y,Math.max(1,x2-x1),lineHeight);}
				else if(!active&&s!=null&&(s.features&1<<TE_F_OUTLINE_HILITE)!=0&&selEnd>selStart&&selEnd>start&&selStart<end){/* an inactive record shows its selection only with outline highlighting turned on */int a=Math.max(start,selStart),b=Math.min(end,selEnd);int x1=xOf(r,text,font,i,a),x2=xOf(r,text,font,i,b);g.setColor(SELECTION);g.drawRect(x1,y,Math.max(1,x2-x1),lineHeight-1);}
				int penX=xOf(r,text,font,i,start);
				for(int[] seg:segments(r,start,trimmedEnd(text,start,end))){Font f=segmentFont(r,seg[2],font);String part=tb.decodeFor(f,java.util.Arrays.copyOfRange(text,seg[0],seg[1]));
					if(part.isEmpty())continue;g.setColor(segmentColor(r,seg[2],ink));if(Mac68kApi.TRACE)System.err.println("[68K] TE segment "+seg[0]+".."+seg[1]+" style "+seg[2]+" txFont "+memory.readWord(r+74)+" size "+memory.readWord(r+80)+" font "+f.getFontName()+"/"+f.getSize()+" at "+penX+","+(y+ascent)+" \""+part+"\"");Fonts68k.drawString(g,f,part,penX,y+ascent);penX+=Fonts68k.width(f,part);}
				if(s!=null&&s.inlineRanges!=null)for(int k=0;k+2<s.inlineRanges.length;k+=3){ // inline input: the composed text is underlined the way TSMTE does it
					int a=Math.max(start,s.inlineRanges[k]),b=Math.min(end,s.inlineRanges[k+1]),style=s.inlineRanges[k+2];if(b<=a||style==1)continue;
					int x1=xOf(r,text,font,i,a),x2=xOf(r,text,font,i,b),uy=y+ascent+1;
					if(style==2||style==3){g.setColor(Color.GRAY);for(int x=x1;x<x2;x+=2)g.fillRect(x,uy,1,1);}
					else{g.setColor(Color.BLACK);g.fillRect(x1,uy,Math.max(1,x2-x1),style==5||style==8?2:1);}}
			}
			if(active&&selStart==selEnd&&s!=null&&s.caretVisible&&shows(port)){int line=lineOf(r,selStart);int x=xOf(r,text,font,line,selStart);int y=dest[0]+tops[line];g.setXORMode(Color.WHITE);g.setColor(Color.BLACK);g.fillRect(x,y,1,tops[line+1]-tops[line]);} // inverted, as toggleCaret blinks it: a solid caret over the last glyph's right edge (an 'o' at Geneva 10) came back white and left a 'c'
		}finally{g.dispose();}
	}

	/**
	 * One run of text drawn as TextEdit's DrawText does it: the pen at the run's start on the baseline, the port in the run's
	 * font, and the port's own textProc called with the text in the record's Handle; the run's width is the pen's advance.
	 * HyperCard J-2.3 swaps in such a textProc for every TEUpdate of a field: where the pen lies in a list field's selected
	 * line, which its rectProc has just painted black, it draws in srcXor - white letters. Drawn directly, they were black on
	 * black (自動選択フィールドの怪).
	 */
	private int drawThroughTextProc(int proc,int r,int port,int[] seg,int x,int baseline,Font f,byte[] part) throws M68kCpuException {
		int e=seg[2]>=0&&styleHandle(r)!=0?styleElement(r,seg[2]):0;
		int font=e!=0?memory.readWord(e+6):memory.readWord(r+TX_FONT),face=e!=0?memory.readByte(e+8):face(r),size=e!=0?memory.readWord(e+10):memory.readWord(r+TX_SIZE);
		int savedFont=memory.readWord(port+68),savedFace=memory.readWord(port+70),savedSize=memory.readWord(port+74);
		memory.writeWord(port+68,font);memory.writeWord(port+70,face<<8);memory.writeWord(port+74,size);
		memory.writeWord(port+48,baseline);memory.writeWord(port+50,x);
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		try{cpu.pushWord(part.length);cpu.pushLong(memory.readLong(memory.readLong(r+H_TEXT))+seg[0]);cpu.pushLong(0x00010001);cpu.pushLong(0x00010001);
			cpu.callPascal(proc,14);}
		catch(M68kCpuException ex){throw ex;}catch(M68kException ex){throw new M68kCpuException(proc,"textProc failed: "+ex.getMessage());}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;
			memory.writeWord(port+68,savedFont);memory.writeWord(port+70,savedFace);memory.writeWord(port+74,savedSize);}
		int advance=(short)memory.readWord(port+50)-x;
		return advance>0?advance:Fonts68k.width(f,tb.decodeFor(f,part));
	}

	/** Where QuickDraw draws in {@code port}: its portRect cut by the boxes of its visRgn and clipRgn ({top, left, bottom, right}, local
	 *  coordinates); null for no port. */
	private int[] portDrawable(int port) throws M68kCpuException {
		if(port==0)return null;
		int[] box=rect(port+16);
		for(int off:new int[]{24,28}){int h=memory.readLong(port+off),p=h==0?0:memory.readLong(h);if(p==0)continue;int[] rgn=rect(p+2);
			box[0]=Math.max(box[0],rgn[0]);box[1]=Math.max(box[1],rgn[1]);box[2]=Math.min(box[2],rgn[2]);box[3]=Math.min(box[3],rgn[3]);}
		return box;
	}

	/**
	 * Blinks the caret when its time is up. Returns what the ROM leaves in D0: the ticks since the last blink (TEIdle subtracts
	 * the blink time from Ticks there), so a small number with a clear upper word. QuickBASIC's INPUT loop calls TEIdle and
	 * then builds a long count from a word it moves into D0 - with a stale pointer left in D0 (Coleseum) the count came out
	 * as $260002 and the next INPUT read megabytes of memory as typed text.
	 */
	private int idle(int handle) throws M68kCpuException {
		int r=rec(handle);State s=records.get(handle);if(s==null||memory.readWord(r+ACTIVE)==0)return 0;
		long now=System.currentTimeMillis();long caretTime=memory.readLong(r+CARET_TIME)*1000L/60L;if(caretTime<=0)caretTime=500;
		if(now-s.lastBlink>=caretTime){s.lastBlink=now;s.caretVisible=!s.caretVisible;if(memory.readWord(r+SEL_START)==memory.readWord(r+SEL_END))toggleCaret(handle);}
		return (int)Math.min(0x7fff,(now-s.lastBlink)*60/1000);
	}

	/**
	 * Blinks the caret the way TextEdit does: the caret column is inverted in place and the text around it is left alone.
	 * {@link #update} used to be called instead, which erased and redrew the whole view twice a second; the host refresh
	 * (a timer on another thread) kept catching it half-drawn, so the lower part of a long script flickered.
	 */
	private void toggleCaret(int handle) throws M68kCpuException {
		int r=rec(handle);State s=records.get(handle);
		if(s==null||s.caretHeight<=0){update(handle);return;}
		int[] view=rect(r+VIEW);int x=s.caretX,top=Math.max(view[0],s.caretY),bottom=Math.min(view[2],s.caretY+s.caretHeight);
		if(bottom<=top||x<view[1]||x>=view[3])return;
		int port=memory.readLong(r+IN_PORT);if(!shows(port))return;
		Graphics2D g=tb.quickDraw.surface(port,new int[]{top,x,bottom,x+1});
		if(g==null){tb.api.quickDraw().invertRect(top,x,bottom,x+1);return;}
		try{g.setXORMode(Color.WHITE);g.setColor(Color.BLACK);g.fillRect(x,top,1,bottom-top);}finally{g.dispose();}
	}

	/**
	 * Whether the caret can show in {@code port}: not while it is the port of an invisible window, whose visRgn is empty. The
	 * Dialog Manager activates a dialog's first edit text item while the dialog is still hidden; that caret was drawn into the
	 * buffer and stayed there after the program hid the item (AppleScript's display dialog without a default answer).
	 */
	private boolean shows(int port){WindowManager68k.Window w=tb.windows.window(port);return w==null||w.visible;}

	/** How many lines TextBox would draw {@code text} in at {@code width} pixels with {@code font}. */
	int lineCount(byte[] text,Font font,int width) throws M68kCpuException {return lineStarts(text,0,font,Math.max(8,width),false).size();}

	/** EraseRect of a rectangle in guest memory through QuickDraw (the port's background colour and pattern); false if it failed. */
	private boolean eraseBox(int box){
		M68kCpu cpu=tb.cpu;int sp=cpu.a[7];
		try{cpu.pushLong(box);return tb.api.quickDraw().handle(cpu,0x8a3);}catch(M68kCpuException e){return false;}finally{cpu.a[7]=sp;}
	}
	/** TETextBox: the text, translated when the program shows its own text that way (VMTranslation). */
	void textBox(byte[] text,int box,int just) throws M68kCpuException {
		textBox(text,box,just,tb.translator()==null?null:tb.translated(VMTranslation.Kind.TEXT,tb.decode(text)));
	}
	/** @param translation what is shown instead of the text (wrapped in the box, smaller when it does not fit), or null */
	void textBox(byte[] text,int box,int just,String translation) throws M68kCpuException {
		int[] b=readRect(box);int port=tb.api.currentQuickDrawPort();
		int[] t=tb.api.quickDraw().textState();Font font=Fonts68k.font(t[0],t[3],t[1]);int[] fi=Fonts68k.fontInfo(font);int lineHeight=fi[0]+fi[1]+fi[3];
		List<Integer> starts=lineStarts(text,0,font,Math.max(8,b[3]-b[1]),false);
		// TETextBox erases the box with EraseRect (the port's background PATTERN) and draws in the port's text mode: Factory 1.1 sets
		// BackPat(black) and TextMode(srcXor) on its status bar, white text on black on a Mac (black on white boxes here before)
		int back=tb.api.quickDraw().eraseRgb(b[1],b[0]),fore=tb.api.quickDraw().foregroundRgb(),mode=t[2]&0x3f;
		if(mode==2)fore=~back&0xffffff;else if(mode==3)fore=back;
		// the erase is EraseRect itself, so a background PixPat shows (a Kaleidoscope scheme's pin-striped dialogs). It comes
		// BEFORE the surface is taken: QuickDraw flushes staged surfaces when it touches the buffer, so a surface staged first
		// (a 1-bit or indexed window) was written back before the text went into it and every static text item came out blank.
		boolean erased=eraseBox(box);
		if(translation!=null){
			if(!erased)tb.api.quickDraw().fillRgb(b[0],b[1],b[2],b[3],back);
			Object[] saved=fore!=tb.api.quickDraw().foregroundRgb()?tb.api.quickDraw().saveForeground():null;
			if(saved!=null)tb.api.quickDraw().setForegroundRgb16((fore>>>16&255)*257,(fore>>>8&255)*257,(fore&255)*257);
			try{drawWrapped(translation,b,just,t[0],t[3],t[1],1,true);}finally{tb.api.quickDraw().restoreForeground(saved);}
			return;}
		Graphics2D g=tb.quickDraw.surface(port);
		// clipRect, not setClip: the surface is already clipped to what the port may draw (an update's visRgn) - Disk Copy's
		// labels outside the update region were drawn again over themselves and their anti-aliased edges came out bold
		if(g!=null){try{g.clipRect(b[1],b[0],Math.max(0,b[3]-b[1]),Math.max(0,b[2]-b[0]));if(!erased){g.setColor(new Color(back));g.fillRect(b[1],b[0],b[3]-b[1],b[2]-b[0]);}g.setColor(new Color(fore));g.setFont(font);
			for(int i=0;i<starts.size();i++){int start=starts.get(i),end=i+1<starts.size()?starts.get(i+1):text.length;String line=tb.decodeFor(font,trimCr(text,start,end));int w=Fonts68k.width(font,line);int x=b[1];if(just==1)x=b[1]+(b[3]-b[1]-w)/2;else if(just==-1)x=b[3]-w;Fonts68k.drawString(g,font,line,x,b[0]+i*lineHeight+fi[0]);}}finally{g.dispose();}return;}
		if(!erased)tb.api.quickDraw().fillRgb(b[0],b[1],b[2],b[3],back);
		for(int i=0;i<starts.size();i++){int start=starts.get(i),end=i+1<starts.size()?starts.get(i+1):text.length;byte[] line=trimCr(text,start,end);int w=Fonts68k.width(font,tb.decodeFor(font,line));int x=b[1];if(just==1)x=b[1]+(b[3]-b[1]-w)/2;else if(just==-1)x=b[3]-w;tb.quickDraw.drawText(line,x,b[0]+i*lineHeight+fi[0],font,mode==2||mode==3?mode:1,t[1]);}
	}

	/**
	 * A read-only record (never activated, clicked or typed into) drawn in the user's language: its whole text translated,
	 * wrapped in the destination rectangle and kept inside the view. Only the current port, and only a full update.
	 * False when the record is drawn as it is (no translation yet, another port).
	 */
	private boolean drawTranslated(int r,State s,byte[] text,int[] view,int[] dest,boolean erase) throws M68kCpuException {
		if(tb.translator()==null||text.length<2)return false;
		int port=memory.readLong(r+IN_PORT);
		if(port!=tb.api.currentQuickDrawPort()||tb.translator().editable(port))return false;
		String translation=tb.translated(VMTranslation.Kind.TEXT,tb.decode(text));
		if(translation==null)return false;
		Font font=font(r);int id=memory.readWord(r+TX_FONT),size=memory.readWord(r+TX_SIZE),face=memory.readByte(r+TX_FACE)&0x7f;
		if(styleHandle(r)!=0){int e=styleElement(r,0);if(e!=0){id=memory.readWord(e+6);face=memory.readByte(e+8)&0x7f;size=memory.readWord(e+10);}}
		if(size<=0)size=font.getSize();
		Object savedClip=tb.api.quickDraw().setHostClip(view[0],view[1],view[2],view[3]);
		try{
			if(erase)tb.api.quickDraw().fillRgb(view[0],view[1],view[2],view[3],tb.api.quickDraw().backgroundRgb());
			int just=(short)memory.readWord(r+JUST);
			drawWrapped(translation,new int[]{dest[0],dest[1],Math.max(dest[2],view[2]),dest[3]},just==1?1:just==-1?-1:0,id,size,face,1,false);
		}finally{tb.api.quickDraw().restoreHostClip(savedClip);}
		s.translated=true;
		return true;
	}

	/**
	 * A translation wrapped in a box (local {top, left, bottom, right}) of the current port, in a host font of the text's family,
	 * size and style: with {@code shrink}, smaller sizes (down to 60%, and not below 7 points) until the lines fit the box.
	 */
	private void drawWrapped(String translation,int[] b,int just,int fontId,int size,int face,int mode,boolean shrink) throws M68kCpuException {
		if(size<=0)size=12;
		int width=Math.max(8,b[3]-b[1]),height=b[2]-b[0],least=Math.max(7,(int)Math.ceil(size*0.6));
		Font font=Fonts68k.hostFont(fontId,size,face);List<String> lines=VMTranslation.wrap(translation,font,width);
		for(int sz=size-1;shrink&&sz>=least&&lines.size()*lineHeight(font)>height;sz--){font=Fonts68k.hostFont(fontId,sz,face);lines=VMTranslation.wrap(translation,font,width);}
		FontMetrics m=Fonts68k.metrics(font);int lineHeight=lineHeight(font);
		for(int i=0;i<lines.size();i++){
			String line=lines.get(i);int y=b[0]+i*lineHeight+m.getAscent();if(y-m.getAscent()>=b[2])break;
			int w=Math.min(width,Fonts68k.width(font,line)),x=just==1?b[1]+(width-w)/2:just==-1?b[3]-w:b[1];
			tb.quickDraw.drawTranslation(line,x,y,font,mode,width);
		}
	}
	private static int lineHeight(Font font){FontMetrics m=Fonts68k.metrics(font);return m.getAscent()+m.getDescent()+m.getLeading();}

	// ------------------------------------------------------------------ editing

	private int offsetAt(int handle,int h,int v) throws M68kCpuException {
		int r=rec(handle);byte[] text=text(handle);Font font=font(r);int[] dest=rect(r+DEST);boolean styled=styleHandle(r)!=0;
		int line=lineAt(r,v-dest[0]);
		int start=lineStart(r,line),end=lineEnd(r,line);byte[] lineBytes=trimCr(text,start,end);
		int x0=xOf(r,text,font,line,start);
		int best=start;int prev=0;
		for(int i=1;i<=lineBytes.length;i++){
			int c=lineBytes[i-1]&0xff;if(tb.encoding==StackTextEncoding.JAPANESE&&Toolbox68k.isJapaneseLead(c)&&i<lineBytes.length){i++;}
			int w=styled?measure(r,text,start,start+i):Fonts68k.width(font,tb.decodeFor(font,java.util.Arrays.copyOf(lineBytes,i)));
			if(h-x0<(prev+w)/2){return best;}
			best=start+i;prev=w;
		}
		return best;
	}

	/** The TERec's clikLoop, called over and over while TEClick tracks the mouse (editors keep their scroll bars in step and
	 *  scroll the text with it); it answers in D0 whether to go on. Registers are the program's again afterwards. */
	private boolean clikLoop(M68kCpu cpu,int handle) throws M68kException {
		int loop=memory.readLong(rec(handle)+CLICK_LOOP);if(loop==0)return true;
		int[] d=cpu.d.clone(),a=cpu.a.clone();boolean more;
		try{cpu.callPascal(loop,0);more=(cpu.d[0]&0xff)!=0;}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		return more;
	}
	private void click(M68kCpu cpu,int handle,int h,int v,boolean extend) throws M68kException {
		markEditable(handle);
		int r=rec(handle);State s=records.get(handle);byte[] text=text(handle);
		int offset=offsetAt(handle,h,v);
		long now=System.currentTimeMillis();boolean doubleClick=now-memory.readLong(r+CLICK_TIME)*1000L/60L<500&&Math.abs(memory.readWord(r+CLICK_LOC)-offset)<=1&&!extend;
		memory.writeLong(r+CLICK_TIME,tb.tickCount());memory.writeWord(r+CLICK_LOC,offset);
		int anchor=extend?s.anchor:offset;
		if(doubleClick){int[] w=wordAt(text,offset);setSelection(handle,w[0],w[1],true);s.anchor=w[0];
			while(tb.host().isMouseDown()){if(!clikLoop(cpu,handle))break;tb.events.idle(1);}return;}
		s.anchor=anchor;setSelection(handle,Math.min(anchor,offset),Math.max(anchor,offset),true);s.caretVisible=true;
		while(tb.host().isMouseDown()){
			if(!clikLoop(cpu,handle))break;
			int[] m=tb.events.localMouse();int o=offsetAt(handle,m[0],m[1]);
			if(o!=offset){offset=o;setSelection(handle,Math.min(anchor,offset),Math.max(anchor,offset),true);}
			tb.events.idle(1);
		}
	}
	private int[] wordAt(byte[] text,int offset){
		int start=Math.min(offset,text.length),end=start;
		while(start>0&&isWord(text[start-1]))start--;while(end<text.length&&isWord(text[end]))end++;
		if(start==end&&end<text.length)end++;
		return new int[]{start,end};
	}
	private static boolean isWord(byte b){int c=b&0xff;return Character.isLetterOrDigit((char)c)||c>=0x80||c=='_';}

	private void key(int handle,int ch) throws M68kCpuException {
		markEditable(handle);
		int r=rec(handle);int selStart=memory.readWord(r+SEL_START),selEnd=memory.readWord(r+SEL_END);byte[] text=text(handle);
		State s=records.get(handle);if(s!=null){s.caretVisible=true;s.lastBlink=System.currentTimeMillis();}
		switch(ch){
			case 8:if(selEnd>selStart)deleteSelection(handle);else if(selStart>0){int p=selStart-1;if(tb.encoding==StackTextEncoding.JAPANESE&&p>0&&isSecondByte(text,p))p--;replace(handle,p,selStart,new byte[0]);}return;
			case 0x7f:if(selEnd>selStart)deleteSelection(handle);else if(selStart<text.length){int n=(tb.encoding==StackTextEncoding.JAPANESE&&Toolbox68k.isJapaneseLead(text[selStart]&0xff))?2:1;replace(handle,selStart,Math.min(text.length,selStart+n),new byte[0]);}return;
			case 0x1c:{int p=selEnd>selStart?selStart:Math.max(0,selStart-1);if(tb.encoding==StackTextEncoding.JAPANESE&&p>0&&isSecondByte(text,p))p--;setSelection(handle,p,p,true);return;}
			case 0x1d:{int p=selEnd>selStart?selEnd:Math.min(text.length,selEnd+1);if(tb.encoding==StackTextEncoding.JAPANESE&&p<text.length&&isSecondByte(text,p))p++;setSelection(handle,p,p,true);return;}
			case 0x1e:case 0x1f:{int line=lineOf(r,selStart);int target=ch==0x1e?line-1:line+1;if(target<0||target>=lineCount(r)){int p=ch==0x1e?0:text.length;setSelection(handle,p,p,true);return;}
				Font font=font(r);int x=xOf(r,text,font,line,selStart);int[] tops=lineTops(r);int[] dest=rect(r+DEST);int p=offsetAt(handle,x,dest[0]+(tops[target]+tops[target+1])/2);setSelection(handle,p,p,true);return;}
			default:break;
		}
		if(ch<0x20&&ch!=0x0d&&ch!=0x09&&ch!=0x03)return;
		if(ch==0x03)ch=0x0d;
		insertAtSelection(handle,new byte[]{(byte)ch},true);
	}
	private boolean isSecondByte(byte[] text,int p){int i=0;while(i<p){int c=text[i]&0xff;if(Toolbox68k.isJapaneseLead(c))i+=2;else i++;}return i>p;}

	void insertAtSelection(int handle,byte[] bytes,boolean advance) throws M68kCpuException {
		int r=rec(handle);int selStart=memory.readWord(r+SEL_START),selEnd=memory.readWord(r+SEL_END);
		State s=records.get(handle);byte[] nullStyle=s==null||styleHandle(r)==0||bytes.length==0?null:s.nullStyle;
		boolean outerSuppress=suppressUpdate;
		if(nullStyle!=null){s.nullStyle=null;suppressUpdate=true;}
		try{
			if(advance){replace(handle,selStart,selEnd,bytes);}
			else replaceRange(handle,selStart,selStart,bytes,selStart+bytes.length,selEnd+bytes.length); // TEInsert: inserts before the selection without changing it (selection moves right)
		}finally{suppressUpdate=outerSuppress;}
		if(nullStyle!=null){ // the null style is the inserted text's; text inserted after it follows from that run
			int style=memory.allocate(12,2);
			try{memory.load(style,nullStyle);applyStyleByRun(rec(handle),selStart,selStart+bytes.length,s.nullMode,style,0);recalc(handle);if(!suppressUpdate){update(handle);autoView(handle);}}finally{memory.free(style);}
		}
	}
	/** Replaces text[start..end) with {@code bytes}, leaving the insertion point after them (the Text Services Manager's inline input edits through this). */
	void replace(int handle,int start,int end,byte[] bytes) throws M68kCpuException {
		int r=rec(handle);int length=memory.readWord(r+LENGTH);start=Math.max(0,Math.min(length,start));end=Math.max(start,Math.min(length,end));
		if(length-(end-start)+bytes.length>32767)return;
		replaceRange(handle,start,end,bytes,start+bytes.length,start+bytes.length);
	}
	/**
	 * Replaces text[start..end) with {@code bytes}, lays the record out again, sets the selection and redraws only what
	 * changed: from the line holding {@code start} to the last line whose start moved (lines after the edit that merely
	 * shifted by the byte difference are the same), or down to the bottom of the view when lines were added or removed.
	 */
	private void replaceRange(int handle,int start,int end,byte[] bytes,int newSelStart,int newSelEnd) throws M68kCpuException {
		int r=rec(handle);byte[] text=text(handle);
		int oldCount=lineCount(r);int[] oldStarts=new int[oldCount];for(int i=0;i<oldCount;i++)oldStarts[i]=lineStart(r,i);
		int firstLine=lineOf(r,start);
		byte[] joined=new byte[text.length-(end-start)+bytes.length];System.arraycopy(text,0,joined,0,start);System.arraycopy(bytes,0,joined,start,bytes.length);System.arraycopy(text,end,joined,start+bytes.length,text.length-end);
		tb.api.loadHandle(memory.readLong(r+H_TEXT),joined);memory.writeWord(r+LENGTH,joined.length);shiftRuns(r,start,end,bytes.length);recalc(handle);r=rec(handle);
		memory.writeWord(r+SEL_START,newSelStart);memory.writeWord(r+SEL_END,newSelEnd);
		int lastLine=TO_BOTTOM;
		if(lineCount(r)==oldCount){
			int byteDelta=bytes.length-(end-start);int j=oldCount-1;
			while(j>firstLine&&oldStarts[j]+byteDelta==lineStart(r,j))j--;
			lastLine=Math.max(j,lineOf(r,newSelEnd));
		}
		if(!suppressUpdate){update(handle,firstLine,lastLine);autoView(handle);}
	}
	/** With automatic scrolling on (TEAutoView, TEFeatureFlag teFAutoScroll) an edit or a moved insertion point is scrolled into view. */
	private void autoView(int handle) throws M68kCpuException {State s=records.get(handle);if(s!=null&&(s.features&1<<TE_F_AUTO_SCROLL)!=0)selView(handle);}
	/** Set while TEStyleInsert inserts: the text is drawn once its styles are applied. */
	private boolean suppressUpdate;
	void deleteSelection(int handle) throws M68kCpuException {int r=rec(handle);replace(handle,memory.readWord(r+SEL_START),memory.readWord(r+SEL_END),new byte[0]);}
	/** TEInit's scrap: an empty TEScrpHandle when there is none yet. */
	private void ensureScrap() throws M68kCpuException {if(memory.readLong(TE_SCRP_HANDLE)==0){memory.writeLong(TE_SCRP_HANDLE,tb.api.createHandle(new byte[0]));memory.writeWord(TE_SCRP_LENGTH,0);knownScrap=new byte[0];}knownScrapHandle=memory.readLong(TE_SCRP_HANDLE);}
	/** TEScrpHandle's contents as TextEdit last left them: when they differ at TEPaste, the program (or TEFromScrap) filled the
	 *  scrap itself and that text is pasted, not the host clipboard. */
	private byte[] knownScrap;
	/** The TEScrpHandle TextEdit made or last saw (0 = none yet). */
	private int knownScrapHandle;
	private void copy(int handle) throws M68kCpuException {
		int r=rec(handle);byte[] text=text(handle);int a=memory.readWord(r+SEL_START),b=memory.readWord(r+SEL_END);
		scrap=java.util.Arrays.copyOfRange(text,Math.min(a,text.length),Math.min(b,text.length));
		scrapStyles=styleHandle(r)!=0&&b>a?styleScrap(r,a,b):null;
		int h=memory.readLong(TE_SCRP_HANDLE);if(h==0){h=tb.api.createHandle(scrap);memory.writeLong(TE_SCRP_HANDLE,h);}else tb.api.loadHandle(h,scrap);
		memory.writeWord(TE_SCRP_LENGTH,scrap.length);knownScrap=scrap.clone();knownScrapHandle=h;
		try{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tb.decode(scrap).replace('\r','\n')),null);}catch(Exception ignored){}
	}
	private void paste(int handle) throws M68kCpuException {
		markEditable(handle);
		// TEFromScrap has loaded the desk scrap into TEScrpHandle (the Scrap Manager mirrors the host clipboard there);
		// text copied in another host application since then still wins, for programs that never call TEFromScrap
		int h=memory.readLong(TE_SCRP_HANDLE),n=memory.readWord(TE_SCRP_LENGTH);byte[] te=h==0?null:tb.api.handleContents(h);
		byte[] data=te!=null&&n>0&&n<=te.length?java.util.Arrays.copyOf(te,n):scrap;
		// a TEScrpHandle TextEdit did not make is the program's own too: THINK C's console prints each line through PtrToHand
		// into a new scrap Handle and TEPaste, and a line it repeated (the same bytes) pasted the host clipboard (wumpus)
		boolean filledByProgram=te!=null&&(knownScrap!=null&&!java.util.Arrays.equals(te,knownScrap)||knownScrapHandle!=0&&h!=knownScrapHandle||knownScrap==null&&knownScrapHandle==0);
		knownScrap=te==null?null:te.clone();knownScrapHandle=h;
		if(!filledByProgram)try{java.awt.datatransfer.Clipboard cb=Toolkit.getDefaultToolkit().getSystemClipboard();if(cb.isDataFlavorAvailable(DataFlavor.stringFlavor)){String s=(String)cb.getData(DataFlavor.stringFlavor);byte[] host=tb.encoding.encodeReplacing(s.replace("\r\n","\r").replace('\n','\r'));if(!java.util.Arrays.equals(host,data))data=host;}}catch(Exception ignored){}
		int r=rec(handle),at=Math.min(memory.readWord(r+SEL_START),memory.readWord(r+SEL_END));
		boolean styled=scrapStyles!=null&&styleHandle(r)!=0&&java.util.Arrays.equals(data,scrap)&&data.length>0; // the text copied here: its styles come with it
		if(!styled){insertAtSelection(handle,data,true);return;}
		suppressUpdate=true;try{insertAtSelection(handle,data,true);}finally{suppressUpdate=false;}
		int tmp=memory.allocate(scrapStyles.length,2);try{memory.load(tmp,scrapStyles);applyScrapStyles(handle,tmp,at,data.length);}finally{memory.free(tmp);}
		update(handle);
	}
	/** A StScrpRec (scrpNStyles, then 20-byte ScrpSTElements: startChar, height, ascent, font, face, size, colour) of the styles of text[a..b). */
	private byte[] styleScrap(int r,int a,int b) throws M68kCpuException {
		int length=memory.readWord(r+LENGTH);a=Math.max(0,Math.min(length,a));b=Math.max(a,Math.min(length,b));
		List<int[]> segs=segments(r,a,b);byte[] out=new byte[2+segs.size()*20];write16(out,0,segs.size());
		for(int k=0;k<segs.size();k++){int[] seg=segs.get(k);int o=2+k*20;write32(out,o,seg[0]-a);
			int[] m=styleMetrics(r,seg[2]);write16(out,o+4,m[0]);write16(out,o+6,m[1]);
			int e=seg[2]<0?0:styleElement(r,seg[2]);
			if(e!=0){write16(out,o+8,memory.readWord(e+6));out[o+10]=(byte)memory.readByte(e+8);write16(out,o+12,memory.readWord(e+10));for(int i=0;i<6;i++)out[o+14+i]=(byte)memory.readByte(e+12+i);}
			else{write16(out,o+8,memory.readWord(r+TX_FONT));out[o+10]=(byte)face(r);write16(out,o+12,memory.readWord(r+TX_SIZE));}}
		return out;
	}
	private void scroll(int handle,int dh,int dv) throws M68kCpuException {int r=rec(handle);int[] d=rect(r+DEST);writeRect(r+DEST,d[0]+dv,d[1]+dh,d[2]+dv,d[3]+dh);update(handle);}
	private void pinScroll(int handle,int dh,int dv) throws M68kCpuException {
		int r=rec(handle);int[] d=rect(r+DEST),v=rect(r+VIEW);int[] tops=lineTops(r);int height=tops[tops.length-1];
		int newTop=d[0]+dv;if(newTop>v[0])newTop=v[0];if(newTop+height<v[2]&&newTop<v[0])newTop=Math.min(v[0],v[2]-height);
		// horizontally pinned the same way against the destination rectangle's width (奈美江ちゃんとの世界's runtime asks TEPinScroll(32767, 32767) to show the start of a text)
		int width=d[3]-d[1],newLeft=d[1]+dh;if(newLeft>v[1])newLeft=v[1];if(newLeft+width<v[3]&&newLeft<v[1])newLeft=Math.min(v[1],v[3]-width);
		scroll(handle,newLeft-d[1],newTop-d[0]);
	}
	private void selView(int handle) throws M68kCpuException {
		int r=rec(handle);int[] d=rect(r+DEST),v=rect(r+VIEW);int[] tops=lineTops(r);int line=lineOf(r,memory.readWord(r+SEL_START));
		int y=d[0]+tops[line],lineHeight=tops[line+1]-tops[line];
		int dv=y<v[0]?v[0]-y:y+lineHeight>v[2]?v[2]-(y+lineHeight):0;
		// sideways too, when the lines are wider than the view (a one-line field with a wide destination rectangle: REALbasic's
		// EditField) or the text was scrolled left before; wrapped text keeps its left edge
		int x=xOf(r,text(handle),font(r),line,memory.readWord(r+SEL_START)),dh=0;
		if(x<v[1]&&d[1]<v[1])dh=Math.min(v[1]-x,v[1]-d[1]);else if(x>=v[3]&&d[3]-d[1]>v[3]-v[1])dh=v[3]-1-x;
		if(dv!=0||dh!=0)scroll(handle,dh,dv);
	}

	private boolean dispatch(M68kCpu cpu) throws M68kCpuException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		switch(selector){
			case 0:{int h=pop(cpu);if(known(h))paste(h);return true;} // TEStylePaste (paste keeps the copied styles in a styled record)
			case 1:{int h=memory.readLong(cpu.a[7]);boolean redraw=memory.readByte(cpu.a[7]+4)!=0;int style=memory.readLong(cpu.a[7]+6),mode=(short)memory.readWord(cpu.a[7]+10);cpu.a[7]+=12; // TESetStyle
				if(Mac68kApi.TRACE&&known(h))System.err.println("[68K] TESetStyle $"+Integer.toHexString(h)+" mode $"+Integer.toHexString(mode&0xffff)+" font "+(short)memory.readWord(style)+" face "+memory.readByte(style+2)+" size "+(short)memory.readWord(style+4)+" redraw "+redraw+" selection "+memory.readWord(rec(h)+SEL_START)+".."+memory.readWord(rec(h)+SEL_END)+" length "+memory.readWord(rec(h)+LENGTH)+" styled "+(styleHandle(rec(h))!=0));
				if(known(h)&&style!=0&&styleHandle(rec(h))!=0){int r=rec(h);int a=memory.readWord(r+SEL_START),b=memory.readWord(r+SEL_END); // styled: the selected runs take the style
					if(b>a){applyStyleByRun(r,a,b,mode,style,0);recalc(h);if(redraw)update(h);}
					else{State s=records.get(h);if(s!=null){s.nullMode=mode;s.nullStyle=memory.readBytes(style,12);}}
					return true;}
				if(known(h)&&style!=0){int r=rec(h);if((mode&1)!=0)memory.writeWord(r+TX_FONT,memory.readWord(style));
					if((mode&2)!=0){int f=memory.readByte(style+2);if((mode&32)!=0){int current=face(r);f=(current&f)==f?current&~f:current|f;}setFace(r,f);} // doToggle: remove the face when the text already has it, else add it
					if((mode&4)!=0&&memory.readWord(style+4)!=0)memory.writeWord(r+TX_SIZE,memory.readWord(style+4));recalc(h);if(redraw)update(h);}return true;}
			// TEReplaceStyle(mode, oldStyle, newStyle, redraw, hTE): in the selection, the runs whose attributes named by mode equal
			// oldStyle's take newStyle's (a face must match exactly). It used to write newStyle into txFont/txFace/txSize - in a styled
			// record those words hold the style handle, so every style of the field was lost (Rinaldi's ReplaceAttributes)
			case 2:{int h=memory.readLong(cpu.a[7]);boolean redraw=memory.readByte(cpu.a[7]+4)!=0;int newStyle=memory.readLong(cpu.a[7]+6),oldStyle=memory.readLong(cpu.a[7]+10),mode=(short)memory.readWord(cpu.a[7]+14)&15;cpu.a[7]+=16;
				if(!known(h)||newStyle==0||oldStyle==0||mode==0)return true;
				int r=rec(h);int a=memory.readWord(r+SEL_START),b=memory.readWord(r+SEL_END);
				if(styleHandle(r)!=0){if(b>a){applyStyleByRun(r,a,b,mode,newStyle,oldStyle);recalc(h);if(redraw)update(h);}return true;}
				// monostyled: the whole record has one style
				if((mode&1)!=0&&memory.readWord(r+TX_FONT)!=memory.readWord(oldStyle))return true;
				if((mode&2)!=0&&face(r)!=(memory.readByte(oldStyle+2)&0xff))return true;
				if((mode&4)!=0&&memory.readWord(r+TX_SIZE)!=memory.readWord(oldStyle+4))return true;
				if((mode&1)!=0)memory.writeWord(r+TX_FONT,memory.readWord(newStyle));
				if((mode&2)!=0)setFace(r,memory.readByte(newStyle+2));
				if((mode&4)!=0&&memory.readWord(newStyle+4)!=0)memory.writeWord(r+TX_SIZE,memory.readWord(newStyle+4));
				recalc(h);if(redraw)update(h);return true;}
			// TEGetStyle(offset, VAR theStyle, VAR lineHeight, VAR fontAscent, hTE): hTE on top, then fontAscent, lineHeight, theStyle, offset.
			// (theStyle and fontAscent had been swapped: the 12-byte TextStyle went to the caller's VAR fontAscent word - HyperCard passes a
			// stack local there - and wiped its frame, so its idle-time "style at the insertion point" jumped to address 0 whenever a
			// field was being edited, for example after "tabkey" or "ask".)
			case 3:{int h=memory.readLong(cpu.a[7]),ascent=memory.readLong(cpu.a[7]+4),lineHeight=memory.readLong(cpu.a[7]+8),style=memory.readLong(cpu.a[7]+12),offset=(short)memory.readWord(cpu.a[7]+16);cpu.a[7]+=18; // TEGetStyle
				if(known(h)){int r=rec(h);writeStyle(style,r,offset);int[] m=styleHandle(r)==0?new int[]{memory.readWord(r+LINE_HEIGHT),memory.readWord(r+FONT_ASCENT)}:lineMetrics(r,lineOf(r,Math.max(0,Math.min(memory.readWord(r+LENGTH),offset)))); // styled: the height and ascent of the offset's line
					if(lineHeight!=0)memory.writeWord(lineHeight,m[0]);if(ascent!=0)memory.writeWord(ascent,m[1]);}return true;}
			case 4:{int h=pop(cpu);memory.writeLong(cpu.a[7],known(h)?styleHandle(rec(h)):0);return true;} // TEGetStyleHandle
			case 5:{int h=memory.readLong(cpu.a[7]),st=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(known(h)&&st!=0){int r=rec(h);memory.writeLong(r+TX_FONT,st);memory.writeWord(r+TX_SIZE,-1);}return true;} // TESetStyleHandle
			case 6:{int h=pop(cpu);int result=0;if(known(h)&&styleHandle(rec(h))!=0){int r=rec(h);result=tb.api.createHandle(styleScrap(r,memory.readWord(r+SEL_START),memory.readWord(r+SEL_END)));}memory.writeLong(cpu.a[7],result);return true;} // TEGetStyleScrapHandle: a new StScrpRec of the selection's styles (NIL for a monostyled record)
			case 7:{int h=memory.readLong(cpu.a[7]),styles=memory.readLong(cpu.a[7]+4),length=memory.readLong(cpu.a[7]+8),text=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16; // TEStylInsert
				// the styles go on before anything is drawn: drawing the plain insert first could leave a read-me in 9-point
				// Geneva under its 14-point bold rendering (the styled TEUpdate does not erase what the insert drew)
				if(known(h)&&length>0){int at=memory.readWord(rec(h)+SEL_START);int firstLine=lineOf(rec(h),at);
					suppressUpdate=true;try{insertAtSelection(h,memory.readBytes(text,length),false);}finally{suppressUpdate=false;}int r=rec(h);
					int scrap=styles==0?0:memory.readLong(styles);if(scrap!=0&&styleHandle(r)!=0)applyScrapStyles(h,scrap,at,length);
					update(h,firstLine,TO_BOTTOM);}return true;} // TEStyleInsert
			case 8:{int h=memory.readLong(cpu.a[7]),offset=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;int result=0;if(known(h)){int r=rec(h);byte[] text=text(h);int line=lineOf(r,offset);int x=xOf(r,text,font(r),line,offset);int[] d=rect(r+DEST);int y=d[0]+lineTops(r)[line+1];result=(y&0xffff)<<16|(x&0xffff);}memory.writeLong(cpu.a[7],result);return true;} // TEGetPoint: the bottom of the offset's line (each line its own height when styled)
			case 9:{int h=memory.readLong(cpu.a[7]),start=memory.readLong(cpu.a[7]+4),end=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int result=0;if(known(h)){int r=rec(h);int n=lineCount(r);start=Math.max(1,Math.min(n,start));end=Math.max(1,Math.min(n,end));int[] tops=lineTops(r);result=tops[Math.max(start,end)]-tops[Math.min(start,end)-1];}memory.writeLong(cpu.a[7],result);return true;} // TEGetHeight(endLine, startLine): lines are 1-based and each has its own height in a styled record
			case 10:{int h=memory.readLong(cpu.a[7]),style=memory.readLong(cpu.a[7]+4),modePtr=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;if(known(h)){writeStyle(style,rec(h));}tb.writeBoolean(cpu.a[7],true);return true;} // TEContinuousStyle
			case 11:{int h=memory.readLong(cpu.a[7]);boolean redraw=memory.readByte(cpu.a[7]+4)!=0;int scrap=memory.readLong(cpu.a[7]+6);cpu.a[7]+=18; // TEUseStyleScrap(rangeStart, rangeEnd, newStyles, fRedraw, hTE): 18 bytes of arguments; popping 8 corrupts the return
				int data=scrap==0?0:memory.readLong(scrap);
				// a styled record takes every run of the scrap over the range (offsets relative to rangeStart): QuickTime's text media
				// handler sets each sample's styles this way (a karaoke movie's 1-point speech commands come first, its 36-point
				// words after - taking only the first run drew the whole text at 1 point)
				if(known(h)&&data!=0&&memory.readWord(data)>0&&styleHandle(rec(h))!=0){int r=rec(h),length=memory.readWord(r+LENGTH);
					int start=Math.max(0,Math.min(length,memory.readLong(cpu.a[7]-4))),end=Math.max(start,Math.min(length,memory.readLong(cpu.a[7]-8)));
					if(end>start){applyScrapStyles(h,data,start,end-start);if(redraw)update(h);}return true;}
				if(known(h)&&data!=0&&memory.readWord(data)>0){int r=rec(h),e=data+2; // the record is unstyled: the first StScrpRec element sets its font, face and size
					memory.writeWord(r+TX_FONT,memory.readWord(e+8));setFace(r,memory.readByte(e+10));if(memory.readWord(e+12)!=0)memory.writeWord(r+TX_SIZE,memory.readWord(e+12));recalc(h);if(redraw)update(h);}
				return true;}
			case 12:{ // TECustomHook(which, VAR addr, hTE): the new hook goes in, the one it replaces comes back in addr (a no-op
				// routine standing for the ROM's until the program installs one) - the hooks themselves are not called by this TextEdit
				int h=memory.readLong(cpu.a[7]),addr=memory.readLong(cpu.a[7]+4),which=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;
				if(addr!=0){java.util.Map<Integer,Integer> hooks=customHooks.computeIfAbsent(h,k->new java.util.HashMap<Integer,Integer>());
					Integer old=hooks.put(which,memory.readLong(addr));memory.writeLong(addr,old!=null?old:tb.gneFilterGate);}
				return true;}
			case 13:{int h=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int[][] runs=known(h)?runs(rec(h)):null;memory.writeLong(cpu.a[7],runs==null?1:Math.max(1,runs.length-1));return true;} // TENumStyles
			case 14:{int h=memory.readLong(cpu.a[7]),action=(short)memory.readWord(cpu.a[7]+4),feature=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;int old=0; // TEFeatureFlag(feature, action, hTE): the bit's previous state
				State s=known(h)?records.get(h):null;if(s!=null&&feature>=0&&feature<32){old=(s.features>>>feature)&1;if(action==1)s.features|=1<<feature;else if(action==0)s.features&=~(1<<feature);}
				memory.writeWord(cpu.a[7],old);return true;}
			case 15:{int h=memory.readLong(cpu.a[7]),region=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeWord(cpu.a[7],hiliteRegion(h,region));return true;} // TEGetHiliteRgn(region, hTE): OSErr
			default:cpu.a[7]+=0;memory.writeWord(cpu.a[7],0);return true;
		}
	}
	/** The selection's highlight as a region: one rectangle per selected line (to the destination's right edge when the
	 *  selection runs past the line's end), within the view rectangle. Empty for an insertion point or an inactive record. */
	private int hiliteRegion(int handle,int region) throws M68kCpuException {
		if(region==0)return -50;QdRegion shape=QdRegion.empty();
		if(known(handle)){
			int r=rec(handle);byte[] text=text(handle);Font font=font(r);
			int[] view=rect(r+VIEW),dest=rect(r+DEST);int[] tops=lineTops(r);
			int selStart=memory.readWord(r+SEL_START),selEnd=memory.readWord(r+SEL_END);
			if(selEnd>selStart){int n=lineCount(r);
				for(int i=0;i<n;i++){int start=lineStart(r,i),end=lineEnd(r,i);if(selEnd<=start||selStart>=end)continue;
					int a=Math.max(start,selStart),b=Math.min(end,selEnd);int y=dest[0]+tops[i],lineHeight=tops[i+1]-tops[i];
					int x1=xOf(r,text,font,i,a),x2=b>=end&&end>start&&text[end-1]=='\r'?dest[3]:xOf(r,text,font,i,b);
					int top=Math.max(y,view[0]),bottom=Math.min(y+lineHeight,view[2]),left=Math.max(x1,view[1]),right=Math.min(Math.max(x2,x1+1),view[3]);
					if(bottom>top&&right>left)shape=QdRegion.combine(shape,QdRegion.rect(top,left,bottom,right),QdRegion.UNION);}}
		}
		tb.api.quickDraw().setRegion(region,shape);return 0;
	}
	private void writeStyle(int style,int r) throws M68kCpuException {writeStyle(style,r,memory.readWord(r+SEL_START));}
	/** Writes the TextStyle in force at {@code offset} (the selection start for TEContinuousStyle, the caller's offset for TEGetStyle). */
	private void writeStyle(int style,int r,int offset) throws M68kCpuException {if(style==0)return;
		int e=styleHandle(r)==0?0:styleElement(r,styleIndexAt(r,Math.max(0,Math.min(memory.readWord(r+LENGTH),offset))));
		if(e!=0){memory.writeWord(style,memory.readWord(e+6));memory.writeByte(style+2,memory.readByte(e+8));memory.writeByte(style+3,0);memory.writeWord(style+4,memory.readWord(e+10));memory.copy(e+12,style+6,6);return;}
		memory.writeWord(style,memory.readWord(r+TX_FONT));memory.writeByte(style+2,face(r));memory.writeByte(style+3,0);memory.writeWord(style+4,memory.readWord(r+TX_SIZE));memory.writeWord(style+6,0);memory.writeWord(style+8,0);memory.writeWord(style+10,0);}

	// ------------------------------------------------------------------ dialog support

	boolean isRecord(int handle){try{return known(handle);}catch(M68kCpuException e){return false;}}
	/** Global {x, y, height} of the insertion point of the active record in a visible window, or null (host input methods
	 *  place their candidate window there). Read from the host thread: a best-effort peek at guest memory. */
	int[] caretLocation(){
		try{
			for(Map.Entry<Integer,State> e:new ArrayList<Map.Entry<Integer,State>>(records.entrySet())){
				int r=rec(e.getKey());if(memory.readWord(r+ACTIVE)==0)continue;
				WindowManager68k.Window w=tb.windows.window(memory.readLong(r+IN_PORT));if(w==null||!w.visible)continue;
				State s=e.getValue();if(s.caretHeight<=0)continue;
				return new int[]{w.left+s.caretX,w.top+s.caretY,s.caretHeight};
			}
		}catch(Exception ignored){}
		return null;
	}
	/**
	 * TEActivate/TEDeactivate: only the selection changes - the caret is shown or hidden in place, a selected range is redrawn
	 * on its own lines. Redrawing the whole view erased what the program had drawn over the text: DOCMaker places its pictures
	 * over option-space placeholders, and deactivating the page when another window came up wiped them all.
	 */
	void activate(int handle,boolean active) throws M68kCpuException {
		if(!known(handle))return;if(active)markEditable(handle);int r=rec(handle);State s=records.get(handle);
		boolean was=memory.readWord(r+ACTIVE)!=0;memory.writeWord(r+ACTIVE,active?1:0);
		int selStart=memory.readWord(r+SEL_START),selEnd=memory.readWord(r+SEL_END);
		if(s==null){update(handle);return;}
		if(selStart==selEnd){
			if(active&&!s.caretVisible){s.caretVisible=true;s.lastBlink=System.currentTimeMillis();toggleCaret(handle);}
			else if(!active&&s.caretVisible){toggleCaret(handle);s.caretVisible=false;}
			return;}
		s.caretVisible=false;
		if(was!=active)update(handle,lineOf(r,Math.min(selStart,selEnd)),lineOf(r,Math.max(selStart,selEnd)));
	}
	void setRects(int handle,int[] rect) throws M68kCpuException {if(!known(handle))return;int r=rec(handle);writeRect(r+DEST,rect[0],rect[1],rect[2],rect[3]);writeRect(r+VIEW,rect[0],rect[1],rect[2],rect[3]);recalc(handle);}
	/**
	 * The Dialog Manager keeps ONE TERecord for all edit text items of a dialog (DialogRecord.textH), so what a program writes
	 * into it holds for every field: Fractal Wizard sets txFont/txSize/lineHeight/fontAscent of (**textH) to Geneva 9 for its
	 * 13-pixel fields. Here each item has a record of its own; this copies the text style of the one textH names ({@code from})
	 * into another item's before that is drawn or becomes current. Styled records are left alone.
	 */
	void shareDialogStyle(int from,int to) throws M68kCpuException {
		if(from==to||!known(from)||!known(to))return;int f=rec(from),t=rec(to);
		if(styleHandle(f)!=0||styleHandle(t)!=0)return;
		boolean changed=false;
		for(int field:new int[]{TX_FONT,TX_FACE,TX_MODE,TX_SIZE,LINE_HEIGHT,FONT_ASCENT}){int v=memory.readWord(f+field);if(memory.readWord(t+field)!=v){memory.writeWord(t+field,v);changed=true;}}
		if(changed){int lineHeight=memory.readWord(t+LINE_HEIGHT),ascent=memory.readWord(t+FONT_ASCENT);recalc(to);memory.writeWord(t+LINE_HEIGHT,lineHeight);memory.writeWord(t+FONT_ASCENT,ascent);} // line starts for the new font; the program's own metrics stay
	}
	boolean pointInView(int handle,int h,int v) throws M68kCpuException {int[] view=rect(rec(handle)+VIEW);return h>=view[1]&&h<view[3]&&v>=view[0]&&v<view[2];}
	void clickFromDialog(int handle,int h,int v,boolean extend) throws M68kException {click(tb.cpu,handle,h,v,extend);}
	void keyFromDialog(int handle,int ch) throws M68kCpuException {key(handle,ch);}
	void idleFromDialog(int handle) throws M68kCpuException {idle(handle);}
	/** Cmd-X/C/V/A typed into a modal dialog's edit field: cut, copy, paste, select all; false for another command key. */
	boolean editCommand(int handle,int ch) throws M68kCpuException {
		switch(Character.toLowerCase((char)ch)){
			case 'x':copy(handle);deleteSelection(handle);return true;
			case 'c':copy(handle);return true;
			case 'v':paste(handle);return true;
			case 'a':selectAll(handle);return true;
			default:return false;
		}
	}
	/** TECustomHook's hooks per TextEdit record (by selector). */
	private final java.util.Map<Integer,java.util.Map<Integer,Integer>> customHooks=new java.util.HashMap<Integer,java.util.Map<Integer,Integer>>();
	void selectAll(int handle) throws M68kCpuException {int r=rec(handle);setSelection(handle,0,memory.readWord(r+LENGTH),true);}
	void redraw(int handle) throws M68kCpuException {update(handle);}
	/** Inline-input underlines for a record ({start,end,style} triples in text offsets, null = none) and a redraw. */
	void setInlineRanges(int handle,int[] ranges) throws M68kCpuException {if(!known(handle))return;State s=records.get(handle);if(s!=null)s.inlineRanges=ranges;update(handle);}
	/** The record's text bytes (empty when unknown). */
	byte[] textOf(int handle) throws M68kCpuException {return known(handle)?text(handle):new byte[0];}
	int[] selection(int handle) throws M68kCpuException {if(!known(handle))return new int[]{0,0};int r=rec(handle);return new int[]{memory.readWord(r+SEL_START),memory.readWord(r+SEL_END)};}
	/** Global {x, y, height} of a text offset in a record shown in a visible window, or null. */
	int[] locationOf(int handle,int offset) throws M68kCpuException {
		if(!known(handle))return null;int r=rec(handle);WindowManager68k.Window w=tb.windows.window(memory.readLong(r+IN_PORT));if(w==null)return null;
		byte[] text=text(handle);Font font=font(r);int[] dest=rect(r+DEST);int[] tops=lineTops(r);offset=Math.max(0,Math.min(text.length,offset));
		int line=lineOf(r,offset);return new int[]{w.left+xOf(r,text,font,line,offset),w.top+dest[0]+tops[line],tops[line+1]-tops[line]};
	}

	private int[] rect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	private int[] readRect(int p) throws M68kCpuException {return rect(p);}
	private void writeRect(int p,int top,int left,int bottom,int right) throws M68kCpuException {memory.writeWord(p,top);memory.writeWord(p+2,left);memory.writeWord(p+4,bottom);memory.writeWord(p+6,right);}
}

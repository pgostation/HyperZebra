import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Text Services Manager (_TSMDispatch $AA54) for hosted applications. The input method is the host's: while a TSM-aware
 * program has an active document, what the user composes arrives here (through the host's InputMethodListener) and goes
 * to the program the way an input method's would - as kTextServiceClass/kUpdateActiveInputArea Apple events for a
 * document that supports the 'tsvc' interface, or straight into the TERec of a TSMTE ('tmTE') document, with the
 * composed text underlined. Events are handed over from the program's own event traps (TSMEvent, WaitNextEvent), where a
 * real input method would have run. Programs without an active inline document keep the host's bottom-line behaviour
 * (a floating composition window, committed text typed as key events).
 */
final class TextServices68k {
	static final int NO_ERR=0,PARAM_ERR=-50;
	static final int ERR_NOT_AN_APP=-2502,ERR_ALREADY_REGISTERED=-2503,ERR_NEVER_REGISTERED=-2504,ERR_INVALID_DOC=-2505,ERR_DOC_NOT_ACTIVE=-2507,ERR_CANT_OPEN=-2509,ERR_TS_NOT_FOUND=-2510,ERR_USE_INPUT_WINDOW=-2512,ERR_UNSUPPORTED_TYPE=-2518;
	static final int TYPE_TSVC=0x74737663,TYPE_TSMTE=0x746d5445,TYPE_UDOC=0x75646f63;
	static final int CLASS_TSVC=0x74737663,ID_UPDATE=0x75706474,ID_OFFSET2POS=0x73743270;
	static final int KEY_REFCON=0x72656663,KEY_SERVER=0x73727669,KEY_DATA=0x6b646174,KEY_FIX_LENGTH=0x6669786c,KEY_HILITE=0x68726e67,KEY_UPDATE_RANGE=0x75646e67,KEY_CLAUSES=0x636c6175,KEY_SCRIPT_TAG=0x73636c67,KEY_OFFSET=0x6f667374,KEY_POINT=0x67706f73,KEY_LINE_HEIGHT=0x6b746c68;
	static final int TYPE_LONG=0x6c6f6e67,TYPE_SHORT=0x73686f72,TYPE_TEXT=0x54455854,TYPE_COMPONENT_INSTANCE=0x636d7069,TYPE_RANGE_ARRAY=0x74726179,TYPE_OFFSET_ARRAY=0x6f666179,TYPE_WRITING_CODE=0x696e746c,TYPE_QD_POINT=0x51447074,TYPE_POINT=0x51447074;
	static final int STYLE_CARET=1,STYLE_RAW=2,STYLE_SELECTED_RAW=3,STYLE_CONVERTED=4,STYLE_SELECTED_CONVERTED=5;

	final class Document {
		final int id,refcon;boolean tsvc,tsmte,useWindow,active;
		/** TSMTE: the TERec being edited and the active input area within its text (start == end: no composition going on). */
		int teHandle,areaStart=-1,areaEnd=-1;
		/** 'tsvc': the active area's byte length as last sent (the whole area is replaced by every update). */
		int areaBytes;
		Document(int id,int refcon){this.id=id;this.refcon=refcon;}
	}
	/** One update from the host input method, waiting for the program's event loop. */
	private static final class Update {final String committed,composing;final int[] styles;final int caret;Update(String committed,String composing,int[] styles,int caret){this.committed=committed;this.composing=composing;this.styles=styles;this.caret=caret;}}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final LinkedHashMap<Integer,Document> documents=new LinkedHashMap<Integer,Document>();
	private boolean aware;private int nextId=0x54530001;
	private Document activeDocument;
	private final List<Update> pending=new ArrayList<Update>();
	/** Global {x, y, height} of the caret inside the active input area, as the program answered kOffset2Pos (null = unknown). */
	private volatile int[] inlineCaret;
	private int scriptCode=-1,languageCode=-1;

	TextServices68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}
	/** The active document's ID (CarbonLib's TSMGetActiveDocument), 0 when none is active. */
	int activeDocumentId(){Document d=activeDocument;return d!=null&&d.active?d.id:0;}
	/** Whether {@code id} names one of the program's TSM documents. */
	boolean isDocument(int id){return documents.containsKey(id);}

	static String name(int selector){
		switch(selector){
			case 0:return "NewTSMDocument";case 1:return "DeleteTSMDocument";case 2:return "ActivateTSMDocument";case 3:return "DeactivateTSMDocument";case 4:return "TSMEvent";case 5:return "TSMMenuSelect";
			case 6:return "SetTSMCursor";case 7:return "FixTSMDocument";case 8:return "GetServiceList";case 9:return "OpenTextService";case 10:return "CloseTextService";case 11:return "SendAEFromTSMComponent";
			case 12:return "SetDefaultInputMethod";case 13:return "GetDefaultInputMethod";case 14:return "SetTextServiceLanguage";case 15:return "GetTextServiceLanguage";case 16:return "UseInputWindow";
			case 17:return "NewServiceWindow";case 18:return "CloseServiceWindow";case 19:return "GetFrontServiceWindow";case 20:return "InitTSMAwareApplication";case 21:return "CloseTSMAwareApplication";
			case 23:return "FindServiceWindow";case 26:return "NewCServiceWindow";default:return null;
		}
	}
	/** Bytes of Pascal arguments each routine takes (the selector alone is in D0). */
	private static int argBytes(int selector){
		switch(selector){
			case 0:return 14;case 1:case 2:case 3:case 4:case 5:case 6:case 7:return 4;case 8:return 14;case 9:return 12;case 10:return 8;case 11:return 26;
			case 12:case 13:return 8;case 14:case 15:return 4;case 16:return 6;case 17:case 26:return 30;case 18:case 19:return 4;case 20:case 21:return 0;case 23:return 8;default:return 0;
		}
	}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;int sp=cpu.a[7],args=argBytes(selector);
		if(Mac68kApi.TRACE)System.err.println("[68K] TSMDispatch "+(name(selector)==null?"#"+selector:name(selector)));
		int result=NO_ERR;boolean bool=false,isBoolean=false;
		switch(selector){
			case 20:result=aware?ERR_ALREADY_REGISTERED:NO_ERR;aware=true;break;
			case 21:{if(!aware)result=ERR_NEVER_REGISTERED;aware=false;for(Document d:new ArrayList<Document>(documents.values()))delete(d);break;}
			case 0:{ // NewTSMDocument(numOfInterface, supportedInterfaceTypes, VAR idocID, refcon)
				int refcon=memory.readLong(sp),idPtr=memory.readLong(sp+4),types=memory.readLong(sp+8),count=(short)memory.readWord(sp+12);
				// tsmNeverRegisteredErr without InitTSMAwareApplication (a Carbon program, 'cbon' answered, is TSM-aware without it, which
				// Carbon lacks): WASTE's WENew passes that one error over and failed on tsmNotAnAppErr - the SIOUX consoles of Alsoft's
				// List Open Files then divided by the line height of a NIL WASTE instance
				if(!aware&&!tb.gestaltValues.containsKey(0x63626f6e)){result=ERR_NEVER_REGISTERED;break;}
				Document d=new Document(nextId++,refcon);
				for(int i=0;i<count&&types!=0;i++){int t=memory.readLong(types+i*4);if(t==TYPE_TSVC||t==TYPE_UDOC)d.tsvc=true;else if(t==TYPE_TSMTE){d.tsmte=true;int rec=refcon==0?0:memory.readLong(refcon);d.teHandle=rec==0?0:memory.readLong(rec);}else{result=ERR_UNSUPPORTED_TYPE;}}
				if(result!=NO_ERR){if(idPtr!=0)memory.writeLong(idPtr,0);break;}
				documents.put(d.id,d);if(idPtr!=0)memory.writeLong(idPtr,d.id);
				if(Mac68kApi.TRACE)System.err.println("[68K] NewTSMDocument -> $"+Integer.toHexString(d.id)+(d.tsmte?" TSMTE TE $"+Integer.toHexString(d.teHandle):d.tsvc?" inline (Apple events)":" no interface")+" refcon $"+Integer.toHexString(refcon));
				break;}
			case 1:{Document d=documents.get(memory.readLong(sp));if(d==null){result=ERR_INVALID_DOC;break;}delete(d);break;}
			case 2:{Document d=documents.get(memory.readLong(sp));if(d==null){result=ERR_INVALID_DOC;break;}activate(d);break;}
			case 3:{Document d=documents.get(memory.readLong(sp));if(d==null){result=ERR_INVALID_DOC;break;}if(activeDocument==d){fix(d);d.active=false;activeDocument=null;}break;}
			case 4:{isBoolean=true;deliverPending(cpu);break;}            // TSMEvent: the host input method took its keys before they became events
			case 5:case 6:isBoolean=true;break;                             // TSMMenuSelect / SetTSMCursor: no input method menu or cursor of ours
			case 7:{Document d=documents.get(memory.readLong(sp));if(d==null){result=ERR_INVALID_DOC;break;}if(!d.active){result=ERR_DOC_NOT_ACTIVE;break;}fix(d);break;}
			case 8:{int seedPtr=memory.readLong(sp),listPtr=memory.readLong(sp+4);if(listPtr!=0)memory.writeLong(listPtr,tb.api.createHandle(new byte[2]));if(seedPtr!=0)memory.writeLong(seedPtr,1);break;} // no text service components to list
			case 9:{int instancePtr=memory.readLong(sp);if(instancePtr!=0)memory.writeLong(instancePtr,0);result=ERR_CANT_OPEN;break;}
			case 10:break;
			case 11:{int event=memory.readLong(sp+22),reply=memory.readLong(sp+18);result=tb.appleEvents.sendFromComponent(event,reply);break;}
			case 12:break;
			case 13:{int slPtr=memory.readLong(sp),componentPtr=memory.readLong(sp+4);if(componentPtr!=0)memory.writeLong(componentPtr,0);if(slPtr!=0)writeScriptLanguage(slPtr);result=ERR_TS_NOT_FOUND;break;}
			case 14:{int slPtr=memory.readLong(sp);if(slPtr!=0){scriptCode=(short)memory.readWord(slPtr);languageCode=(short)memory.readWord(slPtr+2);}break;}
			case 15:{int slPtr=memory.readLong(sp);if(slPtr!=0)writeScriptLanguage(slPtr);break;}
			case 16:{boolean useWindow=memory.readByte(sp)!=0;int id=memory.readLong(sp+2); // UseInputWindow: NIL = every document of the program (ｼｽﾃﾑｿﾌﾄｴﾃﾞｨﾀ's Find window and dialogs)
				Document d=id==0?activeDocument:documents.get(id);if(id!=0&&d==null){result=ERR_INVALID_DOC;break;}if(useWindow&&d!=null&&d==activeDocument)fix(d);allUseWindow=id==0&&useWindow;if(d!=null&&id!=0)d.useWindow=useWindow;break;} // going back to its document it names that one with false: the last call stands
			case 17:case 26:{ // NewServiceWindow/NewCServiceWindow(wStorage, boundsRect, title, visible, theProc, behind, goAwayFlag, ts, VAR window):
				// a floating window above the program's own - REALbasic makes its "always in front" windows this way (何かon林檎's ghost)
				int windowPtr=memory.readLong(sp);boolean goAway=memory.readByte(sp+8)!=0;int behind=memory.readLong(sp+10),procId=(short)memory.readWord(sp+14);
				boolean visible=memory.readByte(sp+16)!=0;int title=memory.readLong(sp+18),bounds=memory.readLong(sp+22),storage=memory.readLong(sp+26);
				int[] r={(short)memory.readWord(bounds),(short)memory.readWord(bounds+2),(short)memory.readWord(bounds+4),(short)memory.readWord(bounds+6)};
				int w=tb.windows.createServiceWindow(storage,r,tb.readPascal(title),visible,procId,behind,goAway,selector==26);
				if(windowPtr!=0)memory.writeLong(windowPtr,w);if(w==0)result=-108;break;}
			case 18:{int w=memory.readLong(sp);if(tb.windows.isServiceWindow(w))tb.windows.disposeWindow(w);break;}
			case 19:{int windowPtr=memory.readLong(sp);if(windowPtr!=0)memory.writeLong(windowPtr,tb.windows.frontServiceWindow());break;}
			case 23:{int windowPtr=memory.readLong(sp),v=(short)memory.readWord(sp+4),h=(short)memory.readWord(sp+6); // FindServiceWindow(thePoint, VAR theWindow): partCode
				int[] hit=tb.windows.findServiceWindow(h,v);if(windowPtr!=0)memory.writeLong(windowPtr,hit[0]);
				cpu.a[7]+=args;memory.writeWord(cpu.a[7],hit[0]==0?0:hit[1]);return true;}
			default:result=PARAM_ERR;break;
		}
		cpu.a[7]+=args;
		if(isBoolean)tb.writeBoolean(cpu.a[7],bool);else memory.writeWord(cpu.a[7],result);
		return true;
	}
	private void writeScriptLanguage(int p) throws M68kCpuException {
		boolean japanese=tb.encoding==StackTextEncoding.JAPANESE;
		memory.writeWord(p,scriptCode>=0?scriptCode:japanese?1:0);memory.writeWord(p+2,languageCode>=0?languageCode:japanese?11:0);
	}
	private void activate(Document d){
		if(activeDocument!=null&&activeDocument!=d){activeDocument.active=false;}
		activeDocument=d;d.active=true;
	}
	private void delete(Document d) throws M68kException {
		if(activeDocument==d){fix(d);activeDocument=null;}
		documents.remove(d.id);
	}
	/** FixTSMDocument: the host input method is asked to confirm what it is composing; its committed text follows as an update. */
	private void fix(Document d){
		if(d.tsmte?d.areaEnd>d.areaStart:d.areaBytes>0)tb.host().endComposition();
	}

	/** Whether the host's composed text belongs to a program document (inline input) rather than to the host's own bottom line. */
	/** UseInputWindow(NIL, true): the program's documents all take the input window. */
	private boolean allUseWindow;
	private Document inlineDocument(){
		Document d=activeDocument;if(d==null||!d.active||d.useWindow||allUseWindow)return null;
		if(d.tsmte)return d.teHandle!=0&&tb.textEdit.isRecord(d.teHandle)?d:null;
		if(d.tsvc)return tb.appleEvents.hasHandler(CLASS_TSVC,ID_UPDATE)?d:null;
		return null;
	}
	/** Host thread: text from the input method. True when an inline document takes it (delivered from the emulation thread later). */
	boolean offer(String committed,String composing,int[] styles,int caret){
		if(inlineDocument()==null&&pendingEmpty())return false;
		synchronized(pending){pending.add(new Update(committed,composing,styles,caret));}
		return true;
	}
	private boolean pendingEmpty(){synchronized(pending){return pending.isEmpty();}}
	int[] inlineCaret(){return inlineCaret;}

	/** Emulation thread, from the program's event traps: hands queued input-method updates to the active document. */
	void deliverPending(M68kCpu cpu) throws M68kException {
		if(pendingEmpty())return;
		List<Update> updates;synchronized(pending){updates=new ArrayList<Update>(pending);pending.clear();}
		Document d=inlineDocument();
		if(d==null){ // the document went away while text was on its way: type what was committed
			for(Update u:updates)if(!u.committed.isEmpty())tb.host().postText(u.committed);
			inlineCaret=null;return;}
		int[] dRegs=cpu.d.clone(),aRegs=cpu.a.clone();
		try{for(Update u:updates){if(d.tsmte)updateTextEdit(d,u);else updateByEvent(d,u);}}
		finally{System.arraycopy(dRegs,0,cpu.d,0,8);System.arraycopy(aRegs,0,cpu.a,0,8);}
	}

	/** Byte ranges of the composed text: {committedBytes, composingBytes, caretByte, per-character byte offsets...}. */
	private byte[] encode(String s){return tb.encoding.encodeReplacing(s);}
	private int[] charOffsets(String s){int[] o=new int[s.length()+1];int n=0;for(int i=0;i<s.length();i++){o[i]=n;n+=encode(String.valueOf(s.charAt(i))).length;}o[s.length()]=n;return o;}
	/** Hilite ranges {start,end,style} over the composing bytes (offsets relative to {@code base}), runs of equal style, plus the caret. */
	private int[] hiliteRanges(Update u,int base){
		int[] offsets=charOffsets(u.composing);List<int[]> runs=new ArrayList<int[]>();
		for(int i=0;i<u.composing.length();){int style=i<u.styles.length?u.styles[i]:STYLE_RAW;int j=i+1;while(j<u.composing.length()&&(j<u.styles.length?u.styles[j]:STYLE_RAW)==style)j++;runs.add(new int[]{base+offsets[i],base+offsets[j],style});i=j;}
		int caret=base+offsets[Math.max(0,Math.min(u.composing.length(),u.caret))];runs.add(new int[]{caret,caret,STYLE_CARET});
		int[] r=new int[runs.size()*3];for(int i=0;i<runs.size();i++){r[i*3]=runs.get(i)[0];r[i*3+1]=runs.get(i)[1];r[i*3+2]=runs.get(i)[2];}return r;
	}

	/** TSMTE: the active area of the TERec is replaced by the update's text; the committed part leaves the area, the rest stays underlined. */
	private void updateTextEdit(Document d,Update u) throws M68kException {
		int te=d.teHandle;byte[] committed=encode(u.committed),composing=encode(u.composing);
		int rec=memory.readLong(d.refcon);int pre=rec==0?0:memory.readLong(rec+4),post=rec==0?0:memory.readLong(rec+8),refCon=rec==0?0:memory.readLong(rec+16);
		if(pre!=0){tb.cpu.pushLong(te);tb.cpu.pushLong(refCon);tb.cpu.callPascal(pre,8);}
		int[] sel=tb.textEdit.selection(te);int textLength=tb.textEdit.textOf(te).length;
		int start,end;
		if(d.areaStart>=0&&d.areaEnd>=d.areaStart&&d.areaEnd<=textLength){start=d.areaStart;end=d.areaEnd;}else{start=sel[0];end=sel[1];}
		byte[] all=new byte[committed.length+composing.length];System.arraycopy(committed,0,all,0,committed.length);System.arraycopy(composing,0,all,committed.length,composing.length);
		tb.textEdit.replace(te,start,end,all);
		int areaStart=start+committed.length,areaEnd=start+all.length;
		int[] hilite=hiliteRanges(u,areaStart);int caretOffset=hilite[hilite.length-3];
		if(composing.length==0){d.areaStart=-1;d.areaEnd=-1;tb.textEdit.setSelection(te,areaEnd,areaEnd,false);tb.textEdit.setInlineRanges(te,null);inlineCaret=null;}
		else{d.areaStart=areaStart;d.areaEnd=areaEnd;tb.textEdit.setSelection(te,caretOffset,caretOffset,false);tb.textEdit.setInlineRanges(te,hilite);inlineCaret=tb.textEdit.locationOf(te,caretOffset);}
		if(post!=0){M68kCpu cpu=tb.cpu;cpu.pushLong(te);cpu.pushLong(committed.length);cpu.pushLong(areaStart);cpu.pushLong(areaEnd);cpu.pushLong(caretOffset);cpu.pushLong(caretOffset);cpu.pushLong(refCon);cpu.callPascal(post,28);}
		if(Mac68kApi.TRACE)System.err.println("[68K] TSMTE update: fixed "+committed.length+" bytes, active area "+d.areaStart+".."+d.areaEnd);
	}

	/** 'tsvc' document: kUpdateActiveInputArea with the whole new active text, the fixed length and the hilite ranges; then kOffset2Pos for the caret. */
	private void updateByEvent(Document d,Update u) throws M68kException {
		byte[] committed=encode(u.committed),composing=encode(u.composing);
		byte[] all=new byte[committed.length+composing.length];System.arraycopy(committed,0,all,0,committed.length);System.arraycopy(composing,0,all,committed.length,composing.length);
		LinkedHashMap<Integer,Object[]> params=new LinkedHashMap<Integer,Object[]>();
		params.put(KEY_REFCON,new Object[]{TYPE_LONG,longBytes(d.refcon)});
		params.put(KEY_SERVER,new Object[]{TYPE_COMPONENT_INSTANCE,longBytes(0)});
		params.put(KEY_DATA,new Object[]{TYPE_TEXT,all});
		boolean japanese=tb.encoding==StackTextEncoding.JAPANESE;
		params.put(KEY_SCRIPT_TAG,new Object[]{TYPE_WRITING_CODE,new byte[]{0,(byte)(japanese?1:0),0,(byte)(japanese?11:0)}});
		params.put(KEY_FIX_LENGTH,new Object[]{TYPE_LONG,longBytes(committed.length)});
		int[] hilite=hiliteRanges(u,committed.length);
		// the hilite and update ranges come with every update, the last one that only fixes text too: ｼｽﾃﾑｿﾌﾄｴﾃﾞｨﾀ's handler gives up with
		// errAEDescNotFound without them. Update range: the area's old bytes, then the bytes that replace them (the whole area, see areaBytes).
		params.put(KEY_HILITE,new Object[]{TYPE_RANGE_ARRAY,rangeArray(hilite)});
		params.put(KEY_UPDATE_RANGE,new Object[]{TYPE_RANGE_ARRAY,rangeArray(new int[]{0,d.areaBytes,0,0,all.length,0})});
		if(composing.length>0){
			int n=hilite.length/3;
			List<Integer> clauses=new ArrayList<Integer>();for(int i=0;i<n;i++){if(hilite[i*3+2]==STYLE_CARET)continue;if(clauses.isEmpty()||clauses.get(clauses.size()-1)!=hilite[i*3])clauses.add(hilite[i*3]);if(hilite[i*3+1]!=hilite[i*3])clauses.add(hilite[i*3+1]);}
			byte[] offsets=new byte[2+clauses.size()*4];offsets[0]=(byte)(clauses.size()>>8);offsets[1]=(byte)clauses.size();for(int i=0;i<clauses.size();i++)putLong(offsets,2+i*4,clauses.get(i));
			params.put(KEY_CLAUSES,new Object[]{TYPE_OFFSET_ARRAY,offsets});
		}
		int result=tb.appleEvents.sendToSelf(CLASS_TSVC,ID_UPDATE,params,0,null);
		d.areaBytes=composing.length;
		if(Mac68kApi.TRACE)System.err.println("[68K] kUpdateActiveInputArea: "+all.length+" bytes, fixed "+committed.length+" -> "+result);
		if(composing.length==0){inlineCaret=null;return;}
		// where the caret is, for the host input method's candidate window
		LinkedHashMap<Integer,Object[]> q=new LinkedHashMap<Integer,Object[]>();
		q.put(KEY_REFCON,new Object[]{TYPE_LONG,longBytes(d.refcon)});q.put(KEY_OFFSET,new Object[]{TYPE_LONG,longBytes(hilite[hilite.length-3]-committed.length)});
		List<byte[]> reply=new ArrayList<byte[]>();
		if(tb.appleEvents.hasHandler(CLASS_TSVC,ID_OFFSET2POS)&&tb.appleEvents.sendToSelf(CLASS_TSVC,ID_OFFSET2POS,q,KEY_POINT,reply)==0&&reply.size()==2&&reply.get(1).length>=4){
			byte[] pt=reply.get(1);inlineCaret=new int[]{(short)MacApp68k.u2(pt,2),(short)MacApp68k.u2(pt,0)-14,16};}
	}
	/** A TextRangeArray from {start,end,style} triples. */
	private static byte[] rangeArray(int[] r){
		int n=r.length/3;byte[] ranges=new byte[2+n*10];ranges[0]=(byte)(n>>8);ranges[1]=(byte)n;
		for(int i=0;i<n;i++){int o=2+i*10;putLong(ranges,o,r[i*3]);putLong(ranges,o+4,r[i*3+1]);ranges[o+8]=(byte)(r[i*3+2]>>8);ranges[o+9]=(byte)r[i*3+2];}
		return ranges;
	}
	private static byte[] longBytes(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
	private static void putLong(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
}

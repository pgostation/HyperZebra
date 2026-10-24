import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeSet;

/** Pure string, number, and memory callbacks from the classic HyperXLib API. */
final class HyperCard68kCallbacks implements M68kCallbackHandler {
	private static final int RESULT_OFFSET=78,IN_ARGS_OFFSET=80,OUT_ARGS_OFFSET=112;
	private final M68kMemory memory;
	private final Mac68kApi macApi;
	/** The XCmdBlock of the call being served (HyperCard hands the external one per call and per XWindoid event). */
	private int block;
	/** The external's windows (XWindoid callbacks); null outside a VM session. */
	XWindows68k xwindows;
	/** The VM being served (GetXResInfo needs the resource the code came from); null outside a VM session. */
	Xcmd68k.Session session;

	HyperCard68kCallbacks(M68kMemory memory,Mac68kApi macApi,int block) {
		this.memory=memory;this.macApi=macApi;this.block=block;
	}

	@Override public boolean handle(int request,int block) throws M68kException {
		int saved=this.block;if(block!=0)this.block=block;
		try{return handle(request);}finally{this.block=saved;}
	}

	@Override public boolean handle(int request) throws M68kException {
		for(int i=0;i<4;i++)out(i,0);
		if(Mac68kApi.TRACE)System.out.println("[xcmd] callback "+request+" in "+Integer.toHexString(in(0))+" "+Integer.toHexString(in(1))+(request>=43&&request<=46?" \""+(request>=45?readPascalAscii(in(0)):"")+"\"":""));
		if(request==2)return evalExpr();
		if(request>=100&&request<=113&&xwindows!=null&&xwindows.callback(request,block))return true;
		switch(request) {
			case 1:return sendMessage(true);
			case 3:out(0,memory.readCString(in(0),Integer.MAX_VALUE).length);break; // StringLength (of a string of any size, up to its NUL)
			case 4:out(0,stringMatch(in(0),in(1)));break;
			case 5:return sendMessage(false);
			case 6:memory.fill(in(0),in(1),0);break;
			case 7:out(0,pasToZero(in(0)));break;
			case 8:writePascalTerminated(in(1),memory.readCString(in(0),Integer.MAX_VALUE));break; // ZeroToPas: the first 255 bytes of a string of any size
			case 9:out(0,hyperCardDigits(readPascalAscii(in(0)),false));break;  // StrToLong
			case 10:out(0,hyperCardDigits(readPascalAscii(in(0)),true));break;  // StrToNum
			case 11:{ // StrToBool: "true"/"false" in any case, trailing spaces allowed; anything else is xresFail (HC J-2.3 measured:
				// Rinaldi's SetFileSharing/SendPS/FolderSize test the result for their "must be boolean" errors - " true", "1", "maybe" fail)
				String text=readPascalAscii(in(0));int end=text.length();while(end>0&&text.charAt(end-1)==' ')end--;text=text.substring(0,end);
				boolean isTrue=text.equalsIgnoreCase("true");out(0,isTrue?1:0);
				if(!isTrue&&!text.equalsIgnoreCase("false")){memory.writeWord(block+RESULT_OFFSET,1);return true;}
				break;}
			case 12:Sane68k.writeExtended(memory,in(1),parseDouble(readPascalAscii(in(0))));break;
			case 13:writePascalAscii(in(1),Integer.toUnsignedString(in(0)));break; // LongToStr: unsigned 32 bits (MtGetFInfo's dates, 2.8e9 seconds)
			case 14:writePascalAscii(in(1),Integer.toString(in(0)));break;
			case 15:numToHex();break;
			case 16:writePascalAscii(in(1),in(0)!=0?"true":"false");break;
			case 17:writePascalAscii(in(1),TTalk.applyNumberFormat(TTalk.formatRawNumber(Sane68k.readExtended(memory,in(0)))));break; // ExtToStr: as HyperCard writes the number (the numberFormat): UxMovie's currRate 256 read "256.0"
			case 18:return getGlobal();
			case 19:return setGlobal();
			case 20:return getField(fieldByName());
			case 21:return getField(fieldByNumber());
			case 22:return getField(fieldById());
			case 23:return setField(fieldByName(),in(2));
			case 24:return setField(fieldByNumber(),in(2));
			case 25:return setField(fieldById(),in(2));
			case 26:out(0,pascalEquals(in(0),in(1))?1:0);break;
			case 27:returnToPas();break;
			case 28:scan(false);break;
			case 29:return getMaskAndData(in(0),in(1));
			case 30:return changedMaskAndData();
			case 31:return formatScript(in(0),in(1)); // FormatScript(scriptHndl, &insertionPoint, quickFormat): the script editor's indenting (ぽな's XT_FormatScript)
			case 32:return zeroTermHandle();
			// HyperCard 2.x additions (numbering from HyperXCmd.h: 29/30 Get/ChangedMaskAndData, 33 PrintTEHandle, 34 SendHCEvent, 35 HCWordBreakProc,
			// 36/37 Begin/EndXSound, 38 RunHandler, 40 GetXResInfo, 42 FrontDocWindow, 43-46 Point/Rect <-> Str,
			// 47/48 Get/SetFieldTE, 52 StackNameToNum, 53 Notify, 100+ XWindows)
			case 34:{ // SendHCEvent(EventRecord*): an event the external did not want (AMPlay's stopping click); the card already had that click.
				// An update of the card window is HyperCard's to draw: HTLock forceFalse invalidates the card and asks for it,
				// and HyperTint's bitsProc on the card window's port paints its picture during HyperCard's CopyBits
				int event=in(0);Toolbox68k tb=macApi.application();
				if(Boolean.getBoolean("hz68k.cardwin"))System.err.println("[cardwin] SendHCEvent what="+(event==0?-1:memory.readWord(event))+" message=$"+(event==0?"":Integer.toHexString(memory.readLong(event+2)))+" card=$"+(tb.cardWindow==null?"none":Integer.toHexString(tb.cardWindow.record)+" hooked="+tb.cardWindow.drawingHooked()));
				// what is invalid there is HyperCard's to copy anew (CardWindow68k.updateInvalidated); a hooked card gets the whole card
				if(event!=0&&memory.readWord(event)==6&&tb.cardWindow!=null&&memory.readLong(event+2)==tb.cardWindow.record
					&&!tb.cardWindow.updateInvalidated(tb.cpu)&&tb.cardWindow.drawingHooked()){
					tb.cardWindow.redrawCard(tb.cpu,0,0,32767,32767);
				}
				break;}
			case 36:case 37:break; // BeginXSound/EndXSound: the sound channel is not shared with scripts here
			case 38:{ // RunHandler(Handle): the handle holds a complete handler; run its body on the current card
				OStack stack=macApi.stack();OObject target=stack==null?null:stack.curCard;
				if(target==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
				String text=readZeroHandle(in(0));
				if(Boolean.getBoolean("hz68k.runhandler"))System.out.println("[runhandler] "+text.replace('\r','\n'));
				try{TTalk.runExternalHandler(text,target);}
				catch(xTalkException e){System.out.println("XCMD RunHandler: "+e.getMessage());memory.writeWord(block+RESULT_OFFSET,1);return true;}
				break;
			}
			case 39:scan(true);break;
			case 40:{ // GetXResInfo: the glue passes &resFile, &resID, &rType, name in inArgs 0-3
				Rsrc.xcmdClass x=session==null?null:session.external;
				// the stack file the external lives in: a stack in use when that is where it came from (Spelunx's game stack has HyperTint
				// and is in use by the launcher, which calls it; HyperTint looks itself up through this refNum)
				int resFile=session!=null&&session.runningFrom!=null?macApi.application().resources.refNumForStackFolder(session.runningFrom):macApi.application().resources.applicationRefNum();
				String type=x==null?"XCMD":"function".equalsIgnoreCase(x.type)?"XFCN":"XCMD";
				if(in(0)!=0)memory.writeWord(in(0),resFile);
				if(in(1)!=0)memory.writeWord(in(1),x==null?0:x.id);
				if(in(2)!=0)memory.writeLong(in(2),(type.charAt(0)<<24)|(type.charAt(1)<<16)|(type.charAt(2)<<8)|type.charAt(3));
				if(in(3)!=0)writePascal(in(3),x==null?new byte[0]:(session.encoding==null?StackTextEncoding.ROMAN:session.encoding).encodeReplacing(x.name));
				break;
			}
			// FrontDocWindow: the front document window - the external's own non-floating window when it is the active one (clicked or
			// selected, in front of the card), else the card window. Dartmouth's ListWindow takes a click in its document window only
			// when it is FrontDocWindow and otherwise just brings itself forward: the stack's Index never went anywhere
			case 42:{Toolbox68k tb=macApi.application();int doc=xwindows==null?0:xwindows.frontDocument();out(0,doc!=0?doc:tb.cardWindow!=null?tb.cardWindow.record:tb.windows.frontWindow());break;}
			case 43:{int p=in(0);writePascalAscii(in(1),(short)memory.readWord(p+2)+","+(short)memory.readWord(p));break;} // PointToStr: inArgs[0] = &pt as for RectToStr (the glue's ORD(@pt); read by value, Textoid's globalLoc came out as an address), "h,v"
			case 44:{int r=in(0);writePascalAscii(in(1),(short)memory.readWord(r+2)+","+(short)memory.readWord(r)+","+(short)memory.readWord(r+6)+","+(short)memory.readWord(r+4));break;} // RectToStr: left,top,right,bottom
			// StrToPoint "h,v" / StrToRect "l,t,r,b": a string of another shape fails (xresFail) and leaves the variable alone. Movie XCMD 1.2
			// tries StrToRect first and, when that fails, offsets the same rect by StrToPoint's point: StrToRect("314,88") used to
			// answer 314,88,0,0, so Myst's movies opened at twice their place on the card
			case 45:{int[] v=numbers(readPascalCString(in(0)),2);if(v==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}memory.writeWord(in(1),v[1]);memory.writeWord(in(1)+2,v[0]);break;}
			case 46:{int[] v=numbers(readPascalCString(in(0)),4);if(v==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}int r=in(1);memory.writeWord(r,v[1]);memory.writeWord(r+2,v[0]);memory.writeWord(r+4,v[3]);memory.writeWord(r+6,v[2]);break;}
			case 47:{ // GetFieldTE(cardFieldFlag, fieldID, fieldNum, fieldName): a TextEdit record holding the field's text
				OField field=fieldForTE();
				if(field==null){out(0,0);memory.writeWord(block+RESULT_OFFSET,1);return true;}
				Toolbox68k tb=macApi.application();
				StackTextEncoding enc=session!=null&&session.encoding!=null?session.encoding:StackTextEncoding.ROMAN;
				String chars=Xcmd68k.swapLineBreaks(field.getText());
				byte[] text=enc.encodeReplacing(chars);
				// always a styled record with the field's runs (bold words, another font or size; one run for a plain field), as
				// HyperCard 2.x fields are: a monostyled record for a field without runs dropped the styles Rinaldi's FullText
				// TEStylInserts into it ("FullText Set" of a styled text into an unstyled field came out all plain)
				out(0,tb.textEdit.createForStyledField(field.top,field.left,field.top+field.height,field.left+field.width,styleRuns(field,chars,enc),text));
				break;
			}
			case 48:{ // SetFieldTE(cardFieldFlag, fieldID, fieldNum, fieldName, hTE): the record's text becomes the field's
				OField field=fieldForTE();int h=in(4);
				if(field==null||h==0){memory.writeWord(block+RESULT_OFFSET,1);return true;}
				StackTextEncoding enc=session!=null&&session.encoding!=null?session.encoding:StackTextEncoding.ROMAN;
				TextEdit68k te=macApi.application().textEdit;
				// the field's text ends at a NUL, as HyperCard's C-string values do: UxReadST inserts the file's text with its zero
				// terminator and HC J-2.3's field then has the file's 43 characters, not a 44th numToChar(0)
				byte[] bytes=te.textOf(h);for(int i=0;i<bytes.length;i++)if(bytes[i]==0){bytes=java.util.Arrays.copyOf(bytes,i);break;}
				String chars=Xcmd68k.swapLineBreaks(Xcmd68k.decodeText(enc,bytes,0,bytes.length));
				java.util.List<int[]> runs=te.styleRunsOf(h);
				if(runs==null){field.setText(chars);break;}
				// a styled record: its runs become the field's text styles (those that differ from the field's own font, size and style)
				field.styleList=null;field.setText(chars);
				for(int i=0;i<runs.size();i++){int[] run=runs.get(i);
					int start=charIndex(bytes,run[0],enc),end=i+1<runs.size()?charIndex(bytes,runs.get(i+1)[0],enc):chars.length();
					String font=Fonts68k.nameOf(run[1]);int face=run[2]&0x7f;if(font.isEmpty())font=field.textFont;
					if(end<=start||(font.equalsIgnoreCase(field.textFont)&&run[3]==field.textSize&&face==(field.textStyle&0x7f)))continue;
					field.applyTextStyle(start,end,font,run[3],face);}
				break;
			}
			case 41:return getFilePath(); // GetFilePath (HyperCard 2.x): QuickTime Tools' MoviePath XFCN finds its movie with it
			case 52:out(0,1);break; // StackNameToNum: one stack is open here; its number is 1
			case 53:notifyUser();break; // Notify: only while HyperCard is in the background (UxAnswer/UxAbout call it before every dialog: each beeped)
			case 54:{ // ShowHCAlert(dlgID, prompt): 1 = OK, 2 = OK/Cancel, 3 = Cancel/Delete, 4 = Yes/Cancel/No; the answer is the button number
				//the prompt is the alert's static text, so ^0-^3 show the ParamText strings (Pictoid XCMD: ParamText(verb) +
				//ShowHCAlert(1, "Invalid message: ^0"))
				String prompt=macApi.application().dialogs.substituteParamText(readPascalText(in(1)));int kind=in(0);
				String[][] buttons={{"OK"},{"OK","Cancel"},{"Cancel","Delete"},{"Yes","Cancel","No"}};
				String[] b=buttons[Math.max(0,Math.min(3,kind-1))];
				int choice=javax.swing.JOptionPane.showOptionDialog(PCARD.pc,prompt,"",javax.swing.JOptionPane.DEFAULT_OPTION,
					javax.swing.JOptionPane.PLAIN_MESSAGE,null,b,b[0]);
				memory.writeWord(block+0x72,choice<0?(kind==1?1:2):choice+1); // outArgs[0]'s first word: the glue reads a short
				break;
			}
			case 55:out(0,TTalk.stop?1:0);break; // AbortInQueue: command-period pressed
			case 56:break; // FlushStackFile: the stack is written by HyperZebra's own saving
			// 203 CountHandlers: UxProgressBar asks it at every event of its windoid and closes the window when no handler runs (the
			// script that showed it ended, by itself, by cmd-. or by an error); a Boolean 1 written into the INTEGER read as 256
			case 203:if(in(0)!=0)memory.writeWord(in(0),ScriptDebugger.frameDepth());break; // CountHandlers(VAR INTEGER): the handlers running (0 at idle: UxProgressBar's windows close then)
			case 202:TXcmd.abortScriptAfterExternal=true;break; // AbortScript: the script stops when the external returns, silently, as by cmd-.
			// HyperCard 2.x debugger callbacks (names and argument order from MPW HyperXLib.o: 200 GoScript, 201 StepScript,
			// 202 AbortScript, 203 CountHandlers, 204 GetHandlerInfo, 205 GetVarInfo, 206 SetVarValue, 207 GetStackCrawl,
			// 208 TraceScript). Handler 0 is the global variables: HyperBib's SaveCurrentGlobals asks GetHandlerInfo(0) for their
			// count and GetVarInfo(0, i) for each name. Handlers of the running script are not listed (no variables).
			case 204:{ // GetHandlerInfo(handlerNum, handlerName, objectName, VAR varCount)
				int count=0;
				if((short)in(0)==0){MemoryData globals=globals();synchronized(globals){count=globals.nameList.size();}}
				if(in(1)!=0)writePascal(in(1),new byte[0]);
				if(in(2)!=0)writePascal(in(2),new byte[0]);
				if(in(3)!=0)memory.writeWord(in(3),count);
				break;
			}
			case 205:{ // GetVarInfo(handlerNum, varNum, varName, VAR isGlobal, varValue, varHandle): the value's first 255 bytes, all of it in varHandle
				String name=null,value="";
				if((short)in(0)==0){MemoryData globals=globals();synchronized(globals){int v=(short)in(1);if(v>=1&&v<=globals.nameList.size()){name=globals.nameList.get(v-1);value=globals.valueList.get(v-1);}}}
				if(name==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
				if(in(2)!=0)writePascal(in(2),encode(name));
				if(in(3)!=0)memory.writeByte(in(3),1);
				byte[] encoded=encode(classicLineEndings(value==null?"":value));
				if(in(4)!=0)writePascal(in(4),encoded);
				if(in(5)!=0){macApi.resizeHandle(in(5),encoded.length+1);int p=memory.readLong(in(5));for(int i=0;i<encoded.length;i++)memory.writeByte(p+i,encoded[i]);memory.writeByte(p+encoded.length,0);}
				break;
			}
			case 206:{ // SetVarValue(handlerNum, varNum, varHandle)
				if((short)in(0)!=0){memory.writeWord(block+RESULT_OFFSET,1);return true;}
				String value=readZeroHandle(in(2));MemoryData globals=globals();
				synchronized(globals){int v=(short)in(1);if(v<1||v>globals.nameList.size()){memory.writeWord(block+RESULT_OFFSET,1);return true;}globals.valueList.set(v-1,value);}
				break;
			}
			// GetStackCrawl: Handle of the running handlers, oldest first, one a line, 2 spaces more indent each, named as their
			// "on" line spells them (HC J-2.3 measured with Caliban's activeHandlers in its answer dialog: "mouseUp" CR "  myHandler")
			case 207:{
				StringBuilder crawl=new StringBuilder();int depth=0;
				for(ScriptDebugger.Frame f:ScriptDebugger.snapshot()){
					String name=f.memData!=null&&f.memData.message!=null?f.memData.message:f.message;
					if(crawl.length()>0)crawl.append('\r');for(int i=0;i<depth;i++)crawl.append("  ");crawl.append(name);depth++;}
				byte[] text=encode(crawl.toString());byte[] z=java.util.Arrays.copyOf(text,text.length+1);
				out(0,macApi.createHandle(z));break;
			}
			default:
				// a request number HyperCard has none for is xresNotImp, and the external goes on (Double-XX's answer• asks its shell
				// with request 16384 after GetFilePath and stopped "HyperCard callback request 16384"). A number in HyperCard's own blocks
				// (below 128: the core calls and XWindoids; 200-255: the debugger) that HyperZebra lacks still stops the external, so the gap shows
				if(!hyperCardRequest(request)){memory.writeWord(block+RESULT_OFFSET,XRES_NOT_IMP);return true;}
				return false;
		}
		memory.writeWord(block+RESULT_OFFSET,0);
		return true;
	}
	private static final int XRES_NOT_IMP=2;
	/** Whether {@code request} lies in one of the blocks HyperCard 2.x numbers its callbacks in (HyperXCmd.h). */
	static boolean hyperCardRequest(int request){return request>=1&&request<=127||request>=200&&request<=255;}

	/**
	 * GetFilePath(fileName, numTypes, typeList, askUser, VAR fileType, VAR fullName) (HyperCard 2.x, request 41): looks for a file
	 * as HyperCard looks for stacks - a full path, else the name in the folder of the stack and of HyperCard's Home, then in the
 * search paths (the globals Documents, Stacks, Applications) - and, when
	 * askUser is true and nothing is found, lets the user pick it. The result is xresSucc with the type and the full path, or
	 * xresFail (QuickTime Tools' MoviePath XFCN: "Mac API is not supported yet ... request 41").
	 */
	private boolean getFilePath() throws M68kException {
		String name=readPascalText(in(0)).trim();
		Toolbox68k tb=macApi.application();
		java.io.File found=null;
		if(!name.isEmpty()){
			java.io.File full=new java.io.File(TTalk.convertFromMacPath(name));
			//":private:tmp:…:BGMデータ:#201" is HyperZebra's own colon form of a host path (the destination, the long name of a
			//stack), so a leading colon names a full path when it exists (宇宙への旅's sound module plays its BGM that way)
			if((name.indexOf(':')>0 || name.startsWith(":") && full.isAbsolute()) && full.exists()) found=full;
			OStack stack=macApi.stack();
			java.io.File stackFolder=stack!=null&&stack.file!=null?GMenuBrowse.stackChooserDirectory(stack.file):null;
			//a Mac full path the program built from what the File Manager gave it ("Macintosh HD:…:Documents-H#70:…": long host
			//names in their 31-character form): the File Manager resolves it back
			if(found==null&&name.indexOf(':')>0&&stackFolder!=null){
				// a volume that is not there (M.J.DASH's BGM button names its author's disk) is no file, not an error that ends the handler
				try{java.io.File f=tb.files.fileManager.resolveClassicPath(stackFolder,name);if(f!=null&&f.exists())found=f;}catch(java.io.IOException|SecurityException e){}
			}
			//a relative Mac path (":BGMデータ:#201"): from the stack's folder
			if(found==null&&stackFolder!=null&&name.startsWith(":")&&name.length()>1){
				java.io.File f=new java.io.File(stackFolder,TTalk.convertFromMacPath(name.substring(1)));
				if(f.exists())found=f;
			}
			String leaf=name.substring(name.lastIndexOf(':')+1);
			if(found==null&&stackFolder!=null)found=FileManager68k.existingOrUnarchived(stackFolder,leaf.replace('/',':'));
			if(found==null&&PCARD.home!=null&&PCARD.home.file!=null){
				java.io.File homeFolder=GMenuBrowse.stackChooserDirectory(PCARD.home.file);
				if(homeFolder!=null){java.io.File f=new java.io.File(homeFolder,leaf.replace('/',':'));if(f.exists())found=f;}
			}
			//then HyperCard's search paths, the globals Documents, Stacks and Applications (Myst Preview's Movie XCMD finds
			//"Brøderbund MooV" in the "Preview Files:" folder its startUp handler put there)
			for(String global:new String[]{"documents","stacks","applications"}){
				if(found!=null)break;
				for(java.io.File folder:TTalk.searchPathFolders(global)){java.io.File f=FileManager68k.existingOrUnarchived(folder,leaf.replace('/',':'));if(f!=null){found=f;break;}}
			}
		}
		if(found==null&&in(3)!=0){
			javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
			OStack stack=macApi.stack();
			java.io.File stackFolder=stack!=null&&stack.file!=null?GMenuBrowse.stackChooserDirectory(stack.file):null;
			if(stackFolder!=null)chooser.setCurrentDirectory(stackFolder);
			chooser.setDialogTitle(name.isEmpty()?"":"Where is "+name+"?");
			if(PCARD.showFileChooser(chooser,PCARD.pc,false)==javax.swing.JFileChooser.APPROVE_OPTION)found=chooser.getSelectedFile();
		}
		if(found==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		if(in(4)!=0)memory.writeLong(in(4),tb.files.fileType(found));
		if(in(5)!=0){
			String path;
			try{path=tb.files.macFullPath(found);}catch(java.io.IOException e){path=found.getName();}
			StackTextEncoding enc=session!=null&&session.encoding!=null?session.encoding:StackTextEncoding.ROMAN;
			writePascal(in(5),enc.encodeReplacing(path));
		}
		memory.writeWord(block+RESULT_OFFSET,0);
		return true;
	}

	/** The picture GetMaskAndData handed out (card, or background while editBkgnd is true), where its bitmaps are and what
	 *  they held then (ChangedMaskAndData applies only the pixels the external changed). */
	private OCardBase maskAndDataOwner;
	private int maskInfo,dataInfo;
	private byte[] maskBefore,dataBefore;

	/**
	 * GetMaskAndData(VAR mask, data: LocalInfo) (HyperCard 2.0, request 29): the current picture as two 1-bit bitmaps - data
	 * (black pixels) and mask (painted pixels, black or white) - each described like a BitMap (addr, rowBytes, bounds). UxBack's
	 * "CardPicture" copies the card picture into its window through this; it stopped with "Mac API is not supported yet".
	 */
	private boolean getMaskAndData(int mask,int data) throws M68kCpuException {
		OStack stack=macApi.stack();
		OCard card=stack==null?null:stack.curCard;
		OCardBase owner=card==null?null:PaintTool.editBackground&&card.bg!=null?card.bg:card;
		if(owner==null||mask==0||data==0){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		int width=Math.max(1,stack.width),height=Math.max(1,stack.height),rowBytes=((width+15)/16)*2;
		java.awt.image.BufferedImage picture=owner.composite();
		int dataBits=memory.allocate(rowBytes*height,2),maskBits=memory.allocate(rowBytes*height,2);
		memory.fill(dataBits,rowBytes*height,0);memory.fill(maskBits,rowBytes*height,0);
		if(picture!=null&&Boolean.TRUE.equals(owner.showPict)){
			int w=Math.min(width,picture.getWidth()),h=Math.min(height,picture.getHeight());
			for(int y=0;y<h;y++){
				for(int x=0;x<w;x++){
					int argb=picture.getRGB(x,y);
					if((argb>>>24)<128)continue; // transparent: not painted
					int bit=0x80>>(x&7),at=y*rowBytes+(x>>3);
					memory.writeByte(maskBits+at,memory.readByte(maskBits+at)|bit);
					int luminance=((argb>>16&0xff)*30+(argb>>8&0xff)*59+(argb&0xff)*11)/100;
					if(luminance<128)memory.writeByte(dataBits+at,memory.readByte(dataBits+at)|bit);
				}
			}
		}
		writeLocalInfo(mask,maskBits,rowBytes,width,height);
		writeLocalInfo(data,dataBits,rowBytes,width,height);
		maskAndDataOwner=owner;maskInfo=mask;dataInfo=data;
		maskBefore=memory.readBytes(maskBits,rowBytes*height);dataBefore=memory.readBytes(dataBits,rowBytes*height);
		memory.writeWord(block+RESULT_OFFSET,0);
		return true;
	}

	private void writeLocalInfo(int info,int bits,int rowBytes,int width,int height) throws M68kCpuException {
		memory.writeLong(info,bits);memory.writeWord(info+4,rowBytes);
		memory.writeWord(info+6,0);memory.writeWord(info+8,0);memory.writeWord(info+10,height);memory.writeWord(info+12,width); // bounds top, left, bottom, right
	}

	/**
	 * ChangedMaskAndData(whatChanged) (request 30): the external drew into the bitmaps GetMaskAndData gave; they become the
	 * picture. Only the pixels it changed are taken (black where data is set, white where only the mask is, erased where
	 * neither), so the rest of a colour picture stays as it was. G-XCollection's MtDrawIcon/MtITexture/MtPTexture PlotIcon or
	 * CopyBits into the data bitmap this way.
	 */
	private boolean changedMaskAndData() throws M68kCpuException {
		OCardBase owner=maskAndDataOwner;
		if(owner==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		int maskBits=memory.readLong(maskInfo),dataBits=memory.readLong(dataInfo),rowBytes=memory.readWord(dataInfo+4)&0x3fff;
		int height=(short)memory.readWord(dataInfo+10)-(short)memory.readWord(dataInfo+6),width=(short)memory.readWord(dataInfo+12)-(short)memory.readWord(dataInfo+8);
		if(width<=0||height<=0||maskBits==0||dataBits==0){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		byte[] mask=memory.readBytes(maskBits,rowBytes*height),data=memory.readBytes(dataBits,rowBytes*height);
		boolean same=maskBefore!=null&&dataBefore!=null&&mask.length==maskBefore.length&&data.length==dataBefore.length;
		java.awt.image.BufferedImage picture=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		if(same&&owner.pict!=null){java.awt.Graphics2D g=picture.createGraphics();g.setComposite(java.awt.AlphaComposite.Src);g.drawImage(owner.pict,0,0,null);g.dispose();}
		boolean changed=false;
		for(int y=0;y<height;y++){
			for(int x=0;x<width;x++){
				int bit=0x80>>(x&7),at=y*rowBytes+(x>>3);
				boolean black=(data[at]&bit)!=0,painted=black||(mask[at]&bit)!=0;
				if(same&&((data[at]^dataBefore[at])&bit)==0&&((mask[at]^maskBefore[at])&bit)==0)continue; // untouched: as it was
				picture.setRGB(x,y,!painted?0:black?0xff000000:0xffffffff);changed=true;
			}
		}
		if(!changed){memory.writeWord(block+RESULT_OFFSET,0);return true;}
		maskBefore=mask;dataBefore=data;
		owner.pict=picture;
		owner.invalidateComposite();
		owner.changed=true;
		OStack stack=owner.stack!=null?owner.stack:macApi.stack();
		if(stack!=null&&stack.file!=null){
			if(owner.bitmapName==null||!owner.bitmapName.matches(".*\\.png"))owner.bitmapName="BMAP_"+owner.id+".png";
			java.io.File file=new java.io.File(stack.file.getParent(),owner.bitmapName);
			try{StackProtection.writeImage(stack,picture,file);PictureCache.invalidate(file);}catch(java.io.IOException e){e.printStackTrace();}
		}
		//a card that had no picture has no picture layer on show: the card is built again (the new picture shows at once, as
		//HyperCard redraws the card - MtDrawIcon's icon stayed invisible until the card was left and opened again)
		boolean shown=PCARD.pc!=null&&stack!=null&&PCARD.pc.stack==stack&&stack.curCard!=null&&(owner==stack.curCard||owner==stack.curCard.bg);
		if(shown&&(owner.label==null||owner.label.cd!=owner||owner.label.getParent()==null))OCard.reloadCurrentCard();
		else if(shown&&PCARD.pc.mainPane!=null)PCARD.pc.mainPane.repaint();
		memory.writeWord(block+RESULT_OFFSET,0);
		return true;
	}

	private int pasToZero(int pascal) throws M68kCpuException {
		byte[] value=readPascal(pascal),zero=new byte[value.length+1];
		System.arraycopy(value,0,zero,0,value.length);
		return macApi.createHandle(zero);
	}

	private int stringMatch(int patternPointer,int targetPointer) throws M68kCpuException {
		byte[] pattern=readPascal(patternPointer),target=memory.readCString(targetPointer,Integer.MAX_VALUE);
		outer:for(int i=0;i<=target.length-pattern.length;i++) {
			for(int j=0;j<pattern.length;j++)
				if(asciiLower(target[i+j])!=asciiLower(pattern[j]))continue outer;
			return targetPointer+i;
		}
		return 0;
	}

	private boolean pascalEquals(int first,int second) throws M68kCpuException {
		byte[] a=readPascal(first),b=readPascal(second);if(a.length!=b.length)return false;
		for(int i=0;i<a.length;i++)if(asciiLower(a[i])!=asciiLower(b[i]))return false;
		return true;
	}

	private void numToHex() throws M68kCpuException {
		int digits=Math.max(0,Math.min(8,in(1)));
		String value=Long.toHexString(Integer.toUnsignedLong(in(0))).toUpperCase(Locale.ROOT);
		if(value.length()>digits&&digits>0)value=value.substring(value.length()-digits);
		while(value.length()<digits)value="0"+value;
		writePascalAscii(in(2),value);
	}

	private void returnToPas() throws M68kCpuException {
		int pointer=in(0),length=0;
		while(length<255) {int value=memory.readByte(pointer+length);if(value==0||value==13)break;length++;}
		byte[] bytes=new byte[length];for(int i=0;i<length;i++)bytes[i]=(byte)memory.readByte(pointer+i);
		writePascalTerminated(in(1),bytes);
	}

	/** HyperCard 2.x ZeroTermHandle appends a C-string terminator to a Handle. */
	private boolean zeroTermHandle() throws M68kCpuException {
		int handle=in(0);byte[] contents=macApi.handleContents(handle);
		if(contents==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		byte[] terminated=new byte[contents.length+1];
		System.arraycopy(contents,0,terminated,0,contents.length);
		macApi.loadHandle(handle,terminated);
		// the Handle in outArgs[0] too: CompileIt!'s glue returns outArgs[0] after it, and TinyEdit's GetPropEvt answered `the text
		// of window` with NIL ("Window has no property text")
		out(0,handle);
		memory.writeWord(block+RESULT_OFFSET,0);return true;
	}

	/** HyperCard 2.x FormatScript: the script in the handle laid out as "the script" reads it (two spaces per level); the
	 *  insertion point (a byte offset, when given) stays on the same character of its line. The handle stays C-terminated. */
	private boolean formatScript(int handle,int insertionPoint) throws M68kException {
		if(macApi.handleContents(handle)==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		String text=readZeroHandle(handle);
		java.util.List<String> lines=java.util.Arrays.asList(text.split("\n",-1));
		String formatted=TTalk.formatScript(lines);
		if(insertionPoint!=0){
			byte[] before=encode(classicLineEndings(text));int offset=Math.max(0,Math.min(before.length,memory.readLong(insertionPoint)));
			String head=charsBefore(before,offset);
			int line=0,column=head.length();
			for(int i=0;i<head.length();i++) if(head.charAt(i)=='\n'){line++;column=head.length()-i-1;}
			String[] oldLines=text.split("\n",-1),newLines=formatted.split("\n",-1);
			if(line<oldLines.length&&line<newLines.length){
				int oldIndent=leadingBlanks(oldLines[line]),newIndent=leadingBlanks(newLines[line]);
				int col=Math.min(newLines[line].length(),newIndent+Math.max(0,column-oldIndent));
				StringBuilder upTo=new StringBuilder();
				for(int i=0;i<line;i++) upTo.append(newLines[i]).append('\n');
				upTo.append(newLines[line],0,col);
				memory.writeLong(insertionPoint,encode(classicLineEndings(upTo.toString())).length);
			}
		}
		byte[] encoded=encode(classicLineEndings(formatted));byte[] zero=new byte[encoded.length+1];
		System.arraycopy(encoded,0,zero,0,encoded.length);
		macApi.loadHandle(handle,zero);
		memory.writeWord(block+RESULT_OFFSET,0);return true;
	}
	private String charsBefore(byte[] bytes,int offset) {OStack stack=macApi.stack();String value=stack==null?new String(bytes,0,offset,StandardCharsets.US_ASCII):Xcmd68k.decodeText(stack.legacyTextEncoding,bytes,0,offset);return Xcmd68k.swapLineBreaks(value);}
	private static int leadingBlanks(String line) {int k=0;while(k<line.length()&&(line.charAt(k)==' '||line.charAt(k)=='\t'))k++;return k;}

	private boolean getField(OField field) throws M68kException {
		String text=field==null?"":field.getText();out(0,zeroHandle(text));memory.writeWord(block+RESULT_OFFSET,field==null?1:0);return true;
	}

	private boolean setField(OField field,int valueHandle) throws M68kException {
		if(field==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		field.setText(readZeroHandle(valueHandle));memory.writeWord(block+RESULT_OFFSET,0);return true;
	}

	/** The field named by Get/SetFieldTE's arguments: cardFieldFlag (inArgs 0), then id (1), number (2) or name (3). */
	/** The field's text styles as TextEdit runs {startByte, font, face, size} over {@code chars} in {@code enc}: a run where they change. */
	private static java.util.List<int[]> styleRuns(OField field,String chars,StackTextEncoding enc){
		java.util.List<int[]> runs=new ArrayList<int[]>();int bytes=0;int[] last=null;
		for(int i=0;i<chars.length();){
			int cp=chars.codePointAt(i),n=Character.charCount(cp);
			OField.TextStyleSpec spec=field.textStyleAt(i);int[] style={Fonts68k.numberOf(spec.font),spec.style&0x7f,spec.size};
			if(last==null||last[0]!=style[0]||last[1]!=style[1]||last[2]!=style[2]){runs.add(new int[]{bytes,style[0],style[1],style[2]});last=style;}
			bytes+=enc.encodeReplacing(chars.substring(i,i+n)).length;i+=n;
		}
		if(runs.isEmpty())runs.add(new int[]{0,Fonts68k.numberOf(field.textFont),field.textStyle&0x7f,field.textSize});
		return runs;
	}
	/** The character index of byte offset {@code offset} of {@code bytes} in {@code enc}. */
	private static int charIndex(byte[] bytes,int offset,StackTextEncoding enc){
		offset=Math.max(0,Math.min(bytes.length,offset));
		return Xcmd68k.decodeText(enc,bytes,0,offset).length();
	}

	private OField fieldForTE() throws M68kException {
		OStack stack=macApi.stack();if(stack==null||stack.curCard==null)return null;
		boolean cardField=in(0)!=0;int id=(short)in(1),number=(short)in(2);
		OCardBase owner=cardField?stack.curCard:stack.curCard.getBg();
		if(owner==null)return null;
		if(id!=0)return owner.GetFldbyId(id);
		if(number!=0)return owner.GetFldbyNum(number);
		return in(3)==0?null:owner.GetFld(readPascalText(in(3)));
	}

	private OField fieldByName() throws M68kException {
		OStack stack=macApi.stack();if(stack==null||stack.curCard==null)return null;
		String name=Xcmd68k.decodeText(stack.legacyTextEncoding,readPascal(in(1)));OCardBase container=fieldContainer();return container==null?null:container.GetFld(name);
	}

	private OField fieldByNumber() throws M68kException {OCardBase container=fieldContainer();return container==null?null:container.GetFldbyNum(in(1));}
	private OField fieldById() throws M68kException {OCardBase container=fieldContainer();return container==null?null:container.GetFldbyId(in(1));}

	private OCardBase fieldContainer() throws M68kCpuException {
		OStack stack=macApi.stack();if(stack==null||stack.curCard==null)return null;
		if(in(0)!=0)return stack.curCard;return stack.curCard.getBg();
	}

	private boolean getGlobal() throws M68kException {
		String name=readName(in(0)),value="";MemoryData globals=globals();synchronized(globals) {
			int index=globals.nameList.indexOf(name);if(index>=0)value=globals.valueList.get(index);
		}
		out(0,zeroHandle(value));memory.writeWord(block+RESULT_OFFSET,0);return true;
	}

	private boolean setGlobal() throws M68kException {
		String name=readName(in(0)),value=readZeroHandle(in(1));MemoryData globals=globals();synchronized(globals) {
			int index=globals.nameList.indexOf(name);if(index<0){globals.nameList.add(name);globals.valueList.add(value);globals.treeset.add(name);}else globals.valueList.set(index,value);
		}
		memory.writeWord(block+RESULT_OFFSET,0);return true;
	}

	/**
	 * The stack HyperCard sends an external's messages to: the current one. That is the VM's own stack unless a message the
	 * external sent went to another stack: ResCopy sends "push card", "go home", then "pop card" and the unlock lines; the
	 * closed stack's card took none of them (result 1), and the user was left in Home with lockMessages on. After the pop card
	 * the stack is open again, as another OStack of the same file.
	 */
	private OStack currentStack(){
		OStack own=macApi.stack();PCARD pc=PCARD.pc;OStack current=pc==null?null:pc.stack;
		if(current==null||current.curCard==null||current==own)return own;
		return own==null||own.curCard==null||!Xcmd68k.sameStack(current,own)?current:own;
	}

	/**
	 * SendCardMessage / SendHCMessage: the line runs as a statement. SendCardMessage's in the current card (a message goes to the
	 * card); SendHCMessage's in the handler that called the external (HC J-2.3 measured: `put the name of me` is that handler's
	 * object, `send "x"` goes to it), or the current card when no handler runs - an external window's events at idle: UxFileDropPalette
	 * sends `send "OpenPalette …"` that way and its card's handlers never ran when the stack was `me`.
	 */
	private boolean sendMessage(boolean cardMessage) throws M68kException {
		OStack stack=currentStack();OObject caller=cardMessage?null:ScriptDebugger.currentObject();
		OObject target=stack==null?null:cardMessage||caller==null?stack.curCard:caller;
		if(target==null){memory.writeWord(block+RESULT_OFFSET,1);return true;}
		String text=readPascalText(in(0));
		if(Mac68kApi.TRACE)System.out.println("[xcmd] "+(cardMessage?"SendCardMessage":"SendHCMessage")+" \""+text+"\"");
		if(!cardMessage&&keyboardCommandForHyperCard(text))return true;
		OObject messageTarget=TXcmd.callTarget.get(); // "the target" in the statement: the target of the message that called the external
		try{TTalk.runExternalStatement(text,target,messageTarget!=null?messageTarget:target);memory.writeWord(block+RESULT_OFFSET,0);}
		catch(xTalkException e){System.out.println("XCMD "+(cardMessage?"SendCardMessage":"SendHCMessage")+" \""+text+"\": "+e.getMessage());memory.writeWord(block+RESULT_OFFSET,1);}
		return true;
	}

	/**
	 * SendHCMessage of a keyboard command (tabKey, returnKey, enterKey, returnInField, enterInField, arrowKey left|right|up|down):
	 * it goes to HyperCard itself, past the stack's handlers, so HyperCard's own action happens. FastTab's documented use is
	 * `on tabKey / fastTab / end tabKey` with its SendHCMessage "tabkey": run as a statement in the handler, the tabKey went back
	 * to that handler ("too much recursion") instead of moving to the next field.
	 */
	private boolean keyboardCommandForHyperCard(String text) throws M68kException {
		String[] words=text.trim().toLowerCase(Locale.ROOT).split("\\s+");
		String message;String param=null;
		switch(words[0]){
			case "tabkey":message="tabKey";break;case "returnkey":message="returnKey";break;case "enterkey":message="enterKey";break;
			case "returninfield":message="returnInField";break;case "enterinfield":message="enterInField";break;
			case "arrowkey":message="arrowKey";if(words.length==2&&words[1].matches("left|right|up|down"))param=words[1];break;
			default:return false;
		}
		if(words.length!=(param==null?1:2))return false;
		TTalk talk=TTalk.talk;if(talk==null)return false;
		MemoryData m=TXcmd.callVariables.get();if(m==null){m=new MemoryData();m.treeset=new TreeSet<String>();m.nameList=new ArrayList<String>();m.valueList=new ArrayList<String>();}
		try{talk.keyboardCommand(message,param,m);memory.writeWord(block+RESULT_OFFSET,0);}
		catch(xTalkException e){System.out.println("XCMD SendHCMessage \""+text+"\": "+e.getMessage());memory.writeWord(block+RESULT_OFFSET,1);}
		return true;
	}

	/** Notify: the Notification Manager's alert when HyperCard is not the active application; nothing when the user is here. */
	private static void notifyUser(){
		if(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=null)return;
		try{if(java.awt.Taskbar.isTaskbarSupported()&&java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)){java.awt.Taskbar.getTaskbar().requestUserAttention(true,false);return;}}
		catch(RuntimeException ignored){}
		java.awt.Toolkit.getDefaultToolkit().beep();
	}

	private boolean evalExpr() throws M68kException {
		String expression=readPascalText(in(0)).trim(),value=null;
		if(expression.equalsIgnoreCase("the version"))value="2.4";
		// one quoted literal only: NewXWind asks `"3,284" is within "1,1,17,300"`, which starts and ends with a quote too (its
		// scroll bar never tracked: the text between the outer quotes is no "true")
		else if(expression.length()>=2&&expression.charAt(0)=='"'&&expression.indexOf('"',1)==expression.length()-1)value=expression.substring(1,expression.length()-1);
		else if(expression.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")||expression.equalsIgnoreCase("true")||expression.equalsIgnoreCase("false"))value=expression;
		else {
			OStack stack=currentStack();OObject target=stack==null?null:stack.curCard;
			OObject messageTarget=TXcmd.callTarget.get(); // "the target" is the target of the message whose handler called the external
			if(target!=null){
				TUtil.ExternalFrame outer=TUtil.externalFrame.get(),frame=externalFrame(expression);if(frame!=null)TUtil.externalFrame.set(frame);
				try{value=TTalk.evaluateExternalExpression(expression,target,messageTarget!=null?messageTarget:target);}
				catch(xTalkException|RuntimeException e){ // an evaluation that breaks is a failure to the external, not the end of its call
					value=literalOnFailure(expression);
					if(value==null)System.out.println("XCMD EvalExpr \""+expression+"\": "+e.getMessage());
				}
				finally{TUtil.externalFrame.set(outer);}
			}
			else{MemoryData globals=globals();String name=expression.toLowerCase(Locale.ROOT);synchronized(globals){int index=globals.nameList.indexOf(name);if(index>=0)value=globals.valueList.get(index);}}
		}
		if(Mac68kApi.TRACE)System.out.println("[xcmd] EvalExpr \""+expression+"\" -> "+(value==null?"failure":"\""+value+"\""));
		// a failure is xresFail with no Handle (HC J-2.3 measured: outArgs[0] NIL)
		out(0,value==null?0:zeroHandle(value));memory.writeWord(block+RESULT_OFFSET,value==null?1:0);return true;
	}

	/**
	 * What HyperCard's EvalExpr answers for an expression it cannot evaluate (HC J-2.3 measured): words and card references are
	 * the text itself, with no error - "cd alpha", "cd buf", "cd 99", "foo bar", even "1+" come back as they are (PgColorX
	 * evaluates every non-number it is given and then looks for its port names "cd buf"/"cd alpha" in the answer); a missing
	 * field ("fld alpha") or an unknown function ("xyz(1)") fail. Null for those.
	 */
	static String literalOnFailure(String expression){
		String lower=expression.trim().toLowerCase(Locale.ROOT);
		if(lower.isEmpty()||lower.indexOf('(')>=0)return null;
		// a property or a part of something ("the short name of card "PASSENGERS"" with no such card, "item 1 of the origin of cd
		// window"): HyperTalk's error ("No such card"), so a failure - Xrules' chain went to the missing card instead of marking the
		// fact unknown
		if(lower.startsWith("the ")||lower.matches("(?s).*\\bof\\b.*"))return null;
		if(lower.matches("(?s)(?:.*\\b)?(?:field|fld|button|btn|message|msg)\\b.*"))return null;
		return expression;
	}

	/** The external's own screen when it is not the display (a classic screen round a small card window), for the geometry an
	 *  expression reads (the card window's rect, the screenRect); null otherwise. The host is only asked about a window or screen. */
	private TUtil.ExternalFrame externalFrame(String expression) {
		String lower=expression.toLowerCase(Locale.ROOT);if(!lower.contains("window")&&!lower.contains("screenrect"))return null;
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted())return null;
		MacAppHost host=tb.host();java.awt.Rectangle screen=host.externalScreen();
		return screen==null?null:new TUtil.ExternalFrame(screen,host::toGuestRect);
	}

	private MemoryData globals() {
		MemoryData globals=TTalk.globalData;if(globals.treeset==null)globals.treeset=new TreeSet<String>();
		if(globals.nameList==null)globals.nameList=new ArrayList<String>();if(globals.valueList==null)globals.valueList=new ArrayList<String>();return globals;
	}

	private String readName(int pointer) throws M68kException {return readPascalText(pointer).toLowerCase(Locale.ROOT);}
	private String readPascalText(int pointer) throws M68kException {OStack stack=macApi.stack();return stack==null?readPascalAscii(pointer):Xcmd68k.decodeText(stack.legacyTextEncoding,readPascal(pointer));}
	private int zeroHandle(String value) throws M68kCpuException {OStack stack=macApi.stack();value=classicLineEndings(value);byte[] encoded=stack==null?value.getBytes(StandardCharsets.US_ASCII):stack.legacyTextEncoding.encodeReplacing(value);byte[] zero=new byte[encoded.length+1];System.arraycopy(encoded,0,zero,0,encoded.length);return macApi.createHandle(zero);}
	private String readZeroHandle(int handle) throws M68kException {byte[] bytes=macApi.handleContents(handle);if(bytes==null)return "";int length=0;while(length<bytes.length&&bytes[length]!=0)length++;OStack stack=macApi.stack();String value=stack==null?new String(bytes,0,length,StandardCharsets.US_ASCII):Xcmd68k.decodeText(stack.legacyTextEncoding,bytes,0,length);return Xcmd68k.swapLineBreaks(value);}
	private byte[] encode(String value) {OStack stack=macApi.stack();return stack==null?value.getBytes(StandardCharsets.US_ASCII):stack.legacyTextEncoding.encodeReplacing(value);}
	private static String classicLineEndings(String value) {return Xcmd68k.swapLineBreaks(value);}

	private void scan(boolean toZero) throws M68kCpuException {
		int pointerAddress=in(0),pointer=memory.readLong(pointerAddress);
		while(memory.readByte(pointer)!=0 && (toZero||memory.readByte(pointer)!=13))pointer++;
		memory.writeLong(pointerAddress,pointer);
	}

	/** Exactly {@code count} comma-separated numbers (rounded; an empty item is 0), or null. */
	static int[] numbers(String text,int count) {
		int[] v=new int[count];String[] items=text.split(",",-1);
		if(items.length!=count)return null;
		for(int i=0;i<count;i++){String item=items[i].trim();if(item.isEmpty())continue;try{v[i]=(int)Math.round(Double.parseDouble(item));}catch(NumberFormatException e){return null;}}
		return v;
	}

	/**
	 * StrToPoint/StrToRect's string as HyperCard reads it: the Pascal string copied into a C string, so it ends at a NUL.
	 * ColorizeHC (LineFeedNuker) passes its parameter's C string "0,0,192,160" itself: the "length" is the '0' (48 bytes),
	 * the text ",0,192,160" up to the NUL - an empty first item (0) - and HyperCard 2.4 colours the card with that rect.
	 */
	private String readPascalCString(int pointer) throws M68kCpuException {
		String text=readPascalAscii(pointer);int nul=text.indexOf('\0');
		return nul<0?text:text.substring(0,nul);
	}

	private byte[] readPascal(int pointer) throws M68kCpuException {
		int length=memory.readByte(pointer);byte[] value=new byte[length];
		for(int i=0;i<length;i++)value[i]=(byte)memory.readByte(pointer+1+i);return value;
	}

	private String readPascalAscii(int pointer) throws M68kCpuException {
		return new String(readPascal(pointer),StandardCharsets.US_ASCII);
	}

	private void writePascalAscii(int pointer,String value) throws M68kCpuException {
		writePascal(pointer,value.getBytes(StandardCharsets.US_ASCII));
	}

	private void writePascal(int pointer,byte[] value) throws M68kCpuException {
		int length=Math.min(255,value.length);memory.writeByte(pointer,length);
		for(int i=0;i<length;i++)memory.writeByte(pointer+1+i,value[i]);
	}
	/** ReturnToPas and ZeroToPas also end the string with a zero byte (HC J-2.3 measured: "ab" is 02 61 62 00; the number
	 *  conversions leave the byte alone). NewList draws its lines with ReturnToPas + DrawString and steps by StringLength of the
	 *  result: without the zero a shorter line ran into the longer one before it, and its second update looped forever. */
	private void writePascalTerminated(int pointer,byte[] value) throws M68kCpuException {
		writePascal(pointer,value);
		if(value.length<255)memory.writeByte(pointer+1+value.length,0); // a full Str255 has no room for it
	}

	private int in(int index) throws M68kCpuException {return memory.readLong(block+IN_ARGS_OFFSET+index*4);}
	private void out(int index,int value) throws M68kCpuException {memory.writeLong(block+OUT_ARGS_OFFSET+index*4,value);}
	private static int asciiLower(byte value){int c=value&0xff;return c>='A'&&c<='Z'?c+32:c;}
	/**
	 * StrToNum/StrToLong as HyperCard reads a number (HC 2.2 and J-2.3 measured with XTernal Collection's externals): the decimal
	 * digits mod 2^32, every other character skipped, up to the first "."; StrToNum negates when a "-" is anywhere in it. So
	 * "6.0" is 6, "1,000" 1000, "1-2" -12, "6a" 6, "4294967302" 6, ".5" and "true" 0. Java's parsing gave 0 for all but plain
	 * integers, and a computed value such as "255.5" or "6.0" made externals report "not a number".
	 */
	static int hyperCardDigits(String value,boolean signed){
		long n=0;boolean minus=false;
		for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='.')break;if(c>='0'&&c<='9')n=(n*10+(c-'0'))&0xffffffffL;else if(c=='-'&&signed)minus=true;}
		return (int)(minus?-n:n);
	}
	private static double parseDouble(String value){try{return Double.parseDouble(value.trim());}catch(NumberFormatException e){return 0.0;}}
}

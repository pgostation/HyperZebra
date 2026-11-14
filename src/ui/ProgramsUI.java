import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.nio.charset.Charset;
import java.io.*;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.*;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

/** Desktop code moved out of the core's OStack unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class ProgramsUI {
	private ProgramsUI(){}

	// from OStack
	static boolean launch68kApplication(String path) {
		File file = MacApp68k.programOf(new File(path)); // a Mac OS 9 package ("X.app" folder) runs the program inside
		if(!file.isFile()) return false;
		if(MacApp68k.isControlPanel(file)) return false; // a 'cdev' file with CODE of its own opens as a control panel, as from the Finder
		boolean accessory = false;
		File openDoc = MacApp68k.isOpenDocDocument(file) ? MacApp68k.openDocProgram() : null;
		if(openDoc!=null){ // an OpenDoc document opens in OpenDoc™ as the Finder does it (that runs the document's own process)
			java.util.List<File> docs = StationeryPads.documentsFor(PCARD.pc, openDoc, java.util.Collections.singletonList(file)); // a stationery pad: a new document from it
			if(docs.isEmpty()) return true;
			try {
				MacApp68k.launch(openDoc, new java.util.ArrayList<File>(docs)); // the launcher lists it as OpenDoc™'s document
			} catch (IOException e) {
				new GDialog(PCARD.pc, HZ.intl().getDialogText("Can't launch the Mac application.")+"\n"+e.getMessage(), null,"OK",null,null);
			}
			return true;
		}
		if(!MacApp68k.isApplication(file)){ // a Finder alias to an application launches the application
			accessory = MacApp68k.isDeskAccessory(file); //デスクアクセサリ('dfil')はそれだけのプロセスで開く(System 7のDA Handler)
			if(!accessory){
				File target=MacApp68k.resolveAliasFile(file);
				if(target==null) return false;
				file=target;
			}
		}
		try {
			if(accessory) MacApp68k.openDeskAccessory(file, null);
			else MacApp68k.launch(file, null);
			if(LauncherStore.enabled) LauncherStore.get().recordOpened(file, LauncherStore.KIND_APP); //ランチャーの履歴(起動できたものだけ)
			return true;
		} catch (IOException e) {
			if(GLauncher.enabled() && !PCARD.pc.isVisible()) GLauncher.showFailure(file.getPath(), e.getMessage()); //ランチャーだけのとき: そこに理由を出す
			else new GDialog(PCARD.pc, HZ.intl().getDialogText("Can't launch the Mac application.")+"\n"+e.getMessage(),
					null,"OK",null,null);
			return true;
		}
	}

	// from TTalk (instance)
	static String openApplication(TTalk self, String app, String document) {
		String appPath = app;
		if(appPath.indexOf(':')>=0 && !new File(appPath).exists()) appPath = self.convertFromMacPath(appPath);
		File appFile = new File(appPath);
		if(!appFile.isAbsolute() && PCARD.pc!=null && HZ.stack()!=null && HZ.stack().path!=null){
			File stackDir = new File(self.stackIdentityPath(HZ.stack().path)).getParentFile();
			if(stackDir!=null && new File(stackDir, appPath).exists()) appFile = new File(stackDir, appPath);
		}
		String docPath = document;
		if(docPath!=null && docPath.indexOf(':')>=0 && !new File(docPath).exists()) docPath = self.convertFromMacPath(docPath);
		//68K Macアプリケーション
		if(appFile.isFile() && MacApp68k.isApplication(appFile)){
			if(!Sandbox.allowFile(appFile, Sandbox.Access.EXECUTE, Sandbox.scriptRequester())) return "Couldn't open application: access not allowed.";
			if(docPath!=null && !Sandbox.allowFile(new File(docPath), Sandbox.Access.READ_WRITE, Sandbox.scriptRequester())) return "Couldn't open application: access not allowed.";
			try{
				MacApp68k.launch(appFile, docPath!=null ? java.util.Collections.singletonList(new File(docPath)) : null);
				return "";
			}catch(IOException e){
				return "Couldn't open application: "+e.getMessage();
			}
		}
		//OSのアプリケーション: このコンピュータのプログラムの起動はスタックの外の世界なので必ず確認する(Sandbox)
		if(!Sandbox.allowOther(Sandbox.scriptRequester(), Sandbox.RUN_PROGRAM, docPath!=null ? app+" ← "+docPath : app))
			return "Couldn't open application: access not allowed.";
		try{
			ArrayList<String> cmd = new ArrayList<String>();
			if(System.getProperty("os.name","").toLowerCase().contains("mac")){
				cmd.add("open");
				if(docPath!=null){ cmd.add("-a"); cmd.add(appFile.exists() ? appFile.getPath() : app); cmd.add(docPath); }
				else if(appFile.exists()) cmd.add(appFile.getPath());
				else { cmd.add("-a"); cmd.add(app); }
			}else{
				//macOSの open -a と同じ結果にする("No such application."ではなく)
				if(!appFile.exists()) return "Couldn't open application: "+app;
				cmd.add(appFile.getPath());
				if(docPath!=null) cmd.add(docPath);
				//起動したプログラムの終了は待たない(open は起動だけして戻る。待つとそのプログラムを閉じるまでスクリプトが止まっていた)
				new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				return "";
			}
			Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			int code = proc.waitFor();
			return code==0 ? "" : "Couldn't open application: "+app;
		}catch(Exception e){
			return "Couldn't open application: "+e.getMessage();
		}
	}

	// from TTalk
	static void commandKeyShortcut(String ch) {
		if(PCARD.pc==null || PCARD.pc.menu==null || ch==null || ch.isEmpty()) return;
		//command-B from a script is Edit > Background whatever the tool (HyperCard; HyperZebra's own item waits for a
		//paint or authoring tool): らくがきカレンダー types it in the browse tool, then chooses the select tool
		if(Thread.currentThread()==TTalk.talk && ch.equalsIgnoreCase("b")){
			if(StackProtection.effectiveUserLevel()<3) return;
			try{ GMenuBrowse.doMenu("Background"); }
			catch(xTalkException e){ System.out.println("commandKeyDown b: "+e.getMessage()); }
			return;
		}
		//command-Q from a script is File > Quit HyperCard (M.J.DASH's 筋肉終了: type Q with cmdkey). On macOS Quit lives in the
		//application menu, which has no JMenuItem here, so nothing was found; a script's Quit returns to the launcher
		if(Thread.currentThread()==TTalk.talk && ch.equalsIgnoreCase("q")){
			try{ TTalk.doScriptforMenu("doMenu \"Quit HyperCard\""); }
			catch(xTalkException e){ System.out.println("commandKeyDown q: "+e.getMessage()); }
			return;
		}
		//command-X, C and V from a script are Edit's Cut, Copy and Paste Text on what is selected (HyperPiece 2 Type)
		if(!javax.swing.SwingUtilities.isEventDispatchThread() && "xcv".indexOf(Character.toLowerCase(ch.charAt(0)))>=0 &&
				TTalk.editSelectedText(new String[]{"cut","copy","paste"}["xcv".indexOf(Character.toLowerCase(ch.charAt(0)))])) return;
		//the items follow the tool a script just chose
		GMenu.updateAuthoringMenus();
		int code = KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(ch.charAt(0)));
		//with a paint tool chosen, HyperCard's paint menus answer first (Paint > Select All for command-A, Edit > Copy Picture for
		//command-C): ClipToPict's demo does "choose select tool", then types A and C with the command key
		java.util.List<JMenu> menus = new java.util.ArrayList<JMenu>();
		boolean painting = Host.get().paintToolActive() && PCARD.pc.paintMenu!=null && PCARD.pc.paintMenu.mb!=null;
		if(painting) for(int i=0; i<PCARD.pc.paintMenu.mb.getComponentCount(); i++) if(PCARD.pc.paintMenu.mb.getComponent(i) instanceof JMenu) menus.add((JMenu)PCARD.pc.paintMenu.mb.getComponent(i));
		int paintMenus = menus.size();
		menus.addAll(SwingMenus.unwrapAll(TTalk.menuList()));
		for(int k=0; k<menus.size(); k++){
			JMenu m = menus.get(k);
			for(int j=0; j<m.getPopupMenu().getComponentCount(); j++){
				Component c = m.getPopupMenu().getComponent(j);
				if(!(c instanceof JMenuItem)) continue;
				javax.swing.KeyStroke ks = ((JMenuItem)c).getAccelerator();
				if(ks!=null && ks.getKeyCode()==code && ((JMenuItem)c).isEnabled()){
					if(Thread.currentThread()==TTalk.talk){
						//from a script (type "B" with commandKey): the menu ignores clicks while a script runs, so the
						//item is chosen as doMenu here, before the script's next line
						//a paint menu's item by its name alone: doMenu finds it in the paint menus (the Edit menu of the bar shown has no Copy Picture)
						String text = ((JMenuItem)c).getText();
						if(k<paintMenus && HZ.intl()!=null) text = HZ.intl().getEngText(text);
						try{ TTalk.doScriptforMenu("doMenu "+TTalk.quoteLiteral(text)+(k<paintMenus ? "" : ","+TTalk.quoteLiteral(m.getText()))); }
						catch(xTalkException e){ System.out.println("commandKeyDown "+ch+": "+e.getMessage()); }
						return;
					}
					((JMenuItem)c).doClick();
					return;
				}
			}
		}
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction3 (the chain asks this in their place): true when one of them matched. */
	static boolean programsFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("programs")){
			//実行中のアプリケーション(macOSではSystem Eventsに問い合わせる)。先頭は自分自身
			java.util.List<String> progs = new java.util.ArrayList<String>();
			progs.add(HZ.AppName);
			if(System.getProperty("os.name","").toLowerCase().contains("mac")){
				try{
					Process p = new ProcessBuilder("osascript", "-e", "tell application \"System Events\" to get name of every process whose background only is false").start();
					String out = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
					p.waitFor();
					for(String n : out.split(",")){ n = n.trim(); if(!n.isEmpty() && !progs.contains(n)) progs.add(n); }
				}catch(Exception e){}
			}
			result.theResult = String.join("\n", progs);
		}
		else return false;
		return true;
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void virusScanOpened(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OStack ostack) throws xTalkException {
				StackVirusScan.stackOpened(ostack);
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void writeStackOnce(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OStack ostack) throws xTalkException {
					try{ XMLwrite.writeOnce(ostack); }catch(RuntimeException e){ e.printStackTrace(); }
	}

	// from TTalk (instance)
	/** Branches of TTalk.CommandExec10 (the chain asks this in their place): true when one of them matched. */
	static boolean appleEventCommands(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if(str=="request"){
			//request <式> from program <プログラム> / request appleEvent class|id|sender|data [with keyword <k>]
			//AppleEventは扱えないので、HyperCardの失敗時と同じく it は空、the result にエラーを入れる
			if(stringList.size()<2) throw new xTalkException("Cannot understand request");
			self.setVariable(memData, "it", "");
			if(stringList.get(1).equalsIgnoreCase("appleevent")){
				//an Apple event an external sent to HyperCard (SendAEData: AESend to "HyperCard" runs the appleEvent message,
				//whose handler does request appleEvent data / reply): class, id, sender or data [with keyword k]
				AppleEvents68k.HyperCardEvent ev = AppleEvents68k.currentHyperCardEvent();
				if(ev==null) result.theResult = "No current Apple event";
				else{
					int with = self.withKeywordAt(stringList, typeAry, 2);
					String what = stringList.size()>2 ? stringList.get(2) : "data";
					String keyword = with>0 ? self.Evalution(stringList, typeAry, with+2, stringList.size()-1, memData, object, target) : null;
					String v = ev.request(what, keyword);
					if(v==null) result.theResult = "No such parameter"; //the event lacks it: it stays empty
					else{ self.setVariable(memData, "it", v); result.theResult = ""; }
				}
			}else{
				int fromPos = -1;
				//request "name of stack" of program remoteHyperCard (AOL HyperNet Communicator): of as from
				for(int k=2; k+1<stringList.size(); k++){
					if(typeAry[k]!=TTalk.wordType.STRING && (stringList.get(k).equalsIgnoreCase("from") || stringList.get(k).equalsIgnoreCase("of")) &&
							typeAry[k+1]!=TTalk.wordType.STRING && stringList.get(k+1).equalsIgnoreCase("program")){ fromPos = k; break; }
				}
				if(fromPos<0 || fromPos+1>=stringList.size() || !stringList.get(fromPos+1).equalsIgnoreCase("program"))
					throw new xTalkException("request requires from program");
				String expr = self.Evalution(stringList, typeAry, 1, fromPos-1, memData, object, target);
				if(self.isThisProgram(stringList, fromPos+1)){
					//this program = HyperZebra 自身: 式をここで評価して it に入れる
					self.setVariable(memData, "it", self.Evalution(expr, memData, object, target));
					result.theResult = "";
				}else{
					result.theResult = "No such program";
				}
			}
		}
		else if(str=="reply"){
			//reply <式> [with keyword <k>] / reply error <式>: the answer to the Apple event an external sent HyperCard
			AppleEvents68k.HyperCardEvent ev = AppleEvents68k.currentHyperCardEvent();
			if(ev==null) result.theResult = "No current Apple event";
			else if(stringList.size()>2 && typeAry[1]!=TTalk.wordType.STRING && stringList.get(1).equalsIgnoreCase("error")){
				ev.reply(self.Evalution(stringList, typeAry, 2, stringList.size()-1, memData, object, target), null, true);
				result.theResult = "";
			}
			else if(stringList.size()>1){
				int with = self.withKeywordAt(stringList, typeAry, 1);
				String text = self.Evalution(stringList, typeAry, 1, with>0 ? with-1 : stringList.size()-1, memData, object, target);
				String keyword = with>0 ? self.Evalution(stringList, typeAry, with+2, stringList.size()-1, memData, object, target) : null;
				ev.reply(text, keyword, false);
				result.theResult = "";
			}
			else throw new xTalkException("Cannot understand reply");
		}
		else return false;
		return true;
	}

	// from TTalk (instance)
	static String printCommand(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, MemoryData memData, OObject object, OObject target) throws xTalkException {
		int next = 1;
		if(next>=stringList.size()) throw new xTalkException("Missing object to print");
		int last = stringList.size()-1;
		String w = stringList.get(next);
		boolean quoted = typeAry[next]==TTalk.wordType.STRING && !w.matches("[0-9.]+"); //素の数値もSTRING型
		//print <file> with <application>
		int withPos = -1;
		for(int k=next+1; k<=last; k++){
			if(typeAry[k]!=TTalk.wordType.STRING && stringList.get(k).equalsIgnoreCase("with") &&
				!(k+1<=last && stringList.get(k+1).equalsIgnoreCase("dialog"))){ withPos = k; break; }
		}
		if(withPos>=0 && !(self.isCardWord(w) && !quoted)){
			String doc = self.Evalution(stringList, typeAry, next, withPos-1, memData, object, target);
			String app = self.Evalution(stringList, typeAry, withPos+1, last, memData, object, target);
			String r = self.openApplication(app, doc);
			return r.isEmpty() ? "" : "Couldn't open that application.";
		}
		//print all|marked|<n> cards
		if(!quoted && (stringList.get(last).equalsIgnoreCase("cards") || stringList.get(last).equalsIgnoreCase("cds")) && last>next){
			OStack st = HZ.stack();
			boolean markedOnly = w.equalsIgnoreCase("marked");
			int count;
			if(w.equalsIgnoreCase("all") && last==next+1) count = st.cardIdList.size();
			else if(markedOnly && last==next+1) count = st.markedCardCount();
			else{
				String n = self.Evalution(stringList, typeAry, next, last-1, memData, object, target);
				int v = self.ordinalChunkNumber(n);
				if(v<=0){
					try{ v = (int)Math.floor(Double.parseDouble(n.trim())); }
					catch(NumberFormatException e){ throw new xTalkException("The card count for print … cards is not a number: {0}", n); }
				}
				if(v<1) throw new xTalkException("The card count for print … cards must be at least 1: {0}", n);
				count = Math.min(v, markedOnly ? st.markedCardCount() : st.cardIdList.size());
			}
			if(count<=0) return "";
			boolean wasOpen = Printing.open;
			Printing.open = true;
			int startId = st.curCard!=null ? st.curCard.id : -1;
			boolean saveLock = self.lockMessages;
			self.lockMessages = true;
			try{
				if(!HZ.lockedScreen){ VEffect.setOldOff(); HZ.lockedScreen = true; }
				for(int i=0; i<count; i++){
					if(i>0 || !(w.equalsIgnoreCase("all") || markedOnly)) {}
					if(i>0) self.doScriptLine(markedOnly ? "go next marked card" : "go next card", object, target, memData, 0);
					else if(markedOnly && (st.curCard==null || !st.curCard.marked)) self.doScriptLine("go next marked card", object, target, memData, 0);
					java.awt.image.BufferedImage img = Printing.captureCurrentCard(null);
					if(img!=null) Printing.pages.add(new Printing.ImagePage(img, Printing.pixelsPerPoint()));
				}
				if(startId>=0 && st.curCard!=null && st.curCard.id!=startId) self.doScriptLine("go card id "+startId, object, target, memData, 0);
			}finally{
				self.lockMessages = saveLock;
				Printing.open = wasOpen;
			}
			return wasOpen ? "" : Printing.flush();
		}
		//print card [from p1 to p2] / print <card expr> [from p1 to p2]
		int fromPos = -1;
		for(int k=next; k<=last; k++){
			if(typeAry[k]!=TTalk.wordType.STRING && stringList.get(k).equalsIgnoreCase("from")){ fromPos = k; break; }
		}
		boolean cardRef = !quoted && (self.isCardWord(w) || w.equalsIgnoreCase("this") || w.equalsIgnoreCase("first") || w.equalsIgnoreCase("last") ||
			w.equalsIgnoreCase("next") || w.equalsIgnoreCase("prev") || w.equalsIgnoreCase("previous") || w.equalsIgnoreCase("marked") ||
			w.equalsIgnoreCase("middle") || w.equalsIgnoreCase("any") || self.ordinalChunkNumber(w)>0 || w.equalsIgnoreCase("recent"));
		if(cardRef && (last==next+0 && self.isCardWord(w) || (fromPos>=0 || self.isCardWord(w) || last>next))){
			int refEnd = (fromPos>=0 ? fromPos : last+1) - 1;
			OObject cd;
			if(refEnd==next && self.isCardWord(w)) cd = HZ.stack().curCard;
			else{
				try{ cd = self.getObject(new ArrayList<String>(stringList), typeAry.clone(), next, refEnd, memData, object, target); }
				catch(xTalkException e){ cd = null; }
			}
			if(!(cd instanceof OCard)){
				if(cd instanceof OField) return self.printText(((OField)cd).getText(), new java.awt.Font(((OField)cd).textFont, ((OField)cd).textStyle & 0x03, ((OField)cd).textSize));
				if(cd instanceof OButton) return self.printText(((OButton)cd).getText(), null);
				if(fromPos<0 && !(refEnd==next && self.isCardWord(w))){
					//カードでなければ式として印刷する
					try{ return self.printText(self.Evalution(stringList, typeAry, next, last, memData, object, target), null); }
					catch(xTalkException e){ return "No such card."; }
				}
				return "No such card.";
			}
			java.awt.Rectangle rect = null;
			if(fromPos>=0){
				int toPos = -1;
				for(int k=fromPos+1; k<=last; k++){ if(typeAry[k]!=TTalk.wordType.STRING && stringList.get(k).equalsIgnoreCase("to")){ toPos = k; break; } }
				if(toPos<0) throw new xTalkException("print card from requires to");
				String p1 = self.evaluateCommaList(stringList, typeAry, fromPos+1, toPos-1, memData, object, target);
				String p2 = self.evaluateCommaList(stringList, typeAry, toPos+1, last, memData, object, target);
				String[] a = p1.split(","), b = p2.split(",");
				if(a.length!=2 || b.length!=2) throw new xTalkException("Cannot understand the coordinates in print card from");
				try{
					int x1 = Integer.parseInt(a[0].trim()), y1 = Integer.parseInt(a[1].trim()), x2 = Integer.parseInt(b[0].trim()), y2 = Integer.parseInt(b[1].trim());
					rect = new java.awt.Rectangle(Math.min(x1,x2), Math.min(y1,y2), Math.abs(x2-x1), Math.abs(y2-y1));
				}catch(NumberFormatException e){ throw new xTalkException("Cannot understand the coordinates in print card from"); }
			}
			OStack st = HZ.stack();
			int startId = st.curCard!=null ? st.curCard.id : -1;
			boolean saveLock = self.lockMessages;
			self.lockMessages = true;
			java.awt.image.BufferedImage img;
			try{
				if(st.curCard==null || cd.id!=st.curCard.id){
					if(!HZ.lockedScreen){ VEffect.setOldOff(); HZ.lockedScreen = true; }
					self.doScriptLine("go card id "+cd.id, object, target, memData, 0);
				}
				img = Printing.captureCurrentCard(rect);
				if(startId>=0 && st.curCard!=null && st.curCard.id!=startId) self.doScriptLine("go card id "+startId, object, target, memData, 0);
			}finally{
				self.lockMessages = saveLock;
			}
			if(img==null) return "Couldn't print.";
			Printing.add(new Printing.ImagePage(img, Printing.pixelsPerPoint()));
			return Printing.open ? "" : Printing.flush();
		}
		//print <field|button>: そのフィールドの書体で印刷。それ以外は式として printText* プロパティで印刷
		OObject obj = null;
		if(!quoted){
			try{ obj = self.getObject(new ArrayList<String>(stringList), typeAry.clone(), next, last, memData, object, target); }catch(xTalkException e){ obj = null; }
		}
		if(obj instanceof OField){
			OField f = (OField)obj;
			return self.printText(f.getText(), new java.awt.Font(f.textFont, f.textStyle & 0x03, f.textSize));
		}
		if(obj instanceof OButton){
			return self.printText(((OButton)obj).getText(), null);
		}
		return self.printText(self.Evalution(stringList, typeAry, next, last, memData, object, target), null);
	}

	// from TTalk
	static String runAppleScript(String code) {
		if(!System.getProperty("os.name","").toLowerCase().contains("mac")) return "AppleScript is not available";
		//AppleScriptは何でもできるので、スクリプト本文を見せて確認する(Sandbox)
		if(!Sandbox.allowOther(Sandbox.scriptRequester(), Sandbox.RUN_APPLESCRIPT, code)) return "AppleScript error: access not allowed";
		try{
			Process p = new ProcessBuilder("osascript", "-").redirectErrorStream(true).start();
			try(java.io.OutputStream os = p.getOutputStream()){ os.write(code.getBytes("UTF-8")); }
			String out = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
			p.waitFor();
			return out;
		}catch(Exception e){
			return "AppleScript error: "+e.getMessage();
		}
	}

	// from OStack
	static boolean revealInFileManager(File item) {
		File folder = item.isDirectory() ? item : item.getParentFile();
		java.awt.Desktop desktop = null;
		try {
			if(java.awt.Desktop.isDesktopSupported()) desktop = java.awt.Desktop.getDesktop();
		} catch(Exception ignored) {
		}
		if(desktop!=null && item.isFile() && desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)){
			try { desktop.browseFileDirectory(item); return true; } catch(Exception ignored) {}
		}
		String os = System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
		if(os.startsWith("windows") && item.isFile()){ //JavaのBROWSE_FILE_DIRはWindowsでは未対応なのでExplorerに直接頼む
			try { new ProcessBuilder("explorer.exe","/select,"+item.getAbsolutePath()).start(); return true; } catch(Exception ignored) {}
		}
		if(folder==null || !folder.isDirectory()) return false;
		if(desktop!=null && desktop.isSupported(java.awt.Desktop.Action.OPEN)){
			try { desktop.open(folder); return true; } catch(Exception ignored) {}
		}
		return false;
	}

	// from TTalk
	/** Lines of TTalk.returnToIdle (the core calls this in their place). */
	static void gcAtIdle() {
		if(!TTalk.idle && !GUI.mouseDown && System.nanoTime()-TTalk.lastExplicitGc > 30_000_000_000L){
			TTalk.lastExplicitGc = System.nanoTime();
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					java.lang.System.gc();//GCをなるべく呼ぶ
				}
			});
		}
	}

	// from OStack (instance)
	/** Lines of OStack.clean (the core calls this in their place). */
	static void externalsAtStackClose(OStack self) {
		Xcmd68k.stackClosing(self); //外部ウインドウを開いたままの68K externalも閉じる(xCloseEvt)
		CardWindow68k.stackClosing(self); //externalがカードに描いたもの(ColorizeHCの絵)を次のスタックに残さない
		TalkImage.resetScreen(); //a script's screenFilter / screenOverlay belong to its stack
	}

	// from TTalk (instance)
	/** Lines of TTalk.goToStack2 (the core calls this in their place). */
	static void virusScanStack(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target, boolean res) throws xTalkException {
		if(res) StackVirusScan.stackOpened(HZ.stack());
	}

	// from TTalk (instance)
	static String printText(TTalk self, String text, java.awt.Font font) throws xTalkException {
		int size = Integer.parseInt(TUtil.compatibilityProperty("printTextSize"));
		int height = Integer.parseInt(TUtil.compatibilityProperty("printTextHeight"));
		int align = 0;
		String a = TUtil.compatibilityProperty("printTextAlign");
		if(a.equalsIgnoreCase("center")) align = 1; else if(a.equalsIgnoreCase("right")) align = 2;
		int[] margins = new int[]{0,0,0,0};
		String[] m = TUtil.compatibilityProperty("printMargins").split(",");
		for(int i=0; i<4 && i<m.length; i++){ try{ margins[i] = Integer.parseInt(m[i].trim()); }catch(NumberFormatException e){} }
		if(font==null){
			int style = java.awt.Font.PLAIN;
			String st = TUtil.compatibilityProperty("printTextStyle").toLowerCase();
			if(st.contains("bold")) style |= java.awt.Font.BOLD;
			if(st.contains("italic")) style |= java.awt.Font.ITALIC;
			font = new java.awt.Font(TUtil.compatibilityProperty("printTextFont"), style, size);
		}else{
			height = (int)Math.round(font.getSize()*1.33);
		}
		Printing.add(new Printing.TextPage(text, font, height, align, margins));
		return Printing.open ? "" : Printing.flush();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void openPrinting(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				Printing.open = true;
				Printing.withDialog = stringList.size()>=4 && stringList.get(2).equalsIgnoreCase("with") && stringList.get(3).equalsIgnoreCase("dialog");
				Printing.pages.clear();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void closePrinting(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				Printing.open = false;
				result.theResult = Printing.flush();
	}
}

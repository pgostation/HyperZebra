import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.*;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
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
import java.awt.RenderingHints;
import java.io.DataInputStream;
import java.nio.charset.Charset;
import java.io.*;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/** Desktop code moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class EditorsUI {
	private EditorsUI(){}

	// from TTalk (instance)
	/** Branches of TTalk.CommandExec6 (the chain asks this in their place): true when one of them matched. */
	static boolean editorsAndPictures(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if(str=="edit"){
			String newStr="";
			OObject obj=null;
			int next=1;
			if(next>=stringList.size()) throw new xTalkException("Cannot understand edit");

			if(stringList.get(next).equalsIgnoreCase("picture")){
				//picture
				if(!Sandbox.allowFile(new File(stringList.get(2)), Sandbox.Access.READ, Sandbox.scriptRequester()))
					throw new xTalkException("File access was denied: {0}", stringList.get(2));
				//ランチャーへの画像ドロップ等で開く、スタックを持たない画像ビューア/エディタ。元画像を見る・なるべく情報を
				//落とさずに編集するためのものなので、白黒32x32に潰れるICONではなく、任意サイズ・フルカラーのPICTとして扱う
				if(ResEdit.nulleditor==null || ResEdit.nulleditor.child.pcard==null
						|| !ResEdit.nulleditor.child.isDisplayable() || !"PICT".equals(ResEdit.nulleditor.child.type)){
					ResEdit.nulleditor = new ResEdit((PCARD)null, "PICT", (OObject)null);
				}
				//作業用コピー(一時フォルダ)へコピーして登録する。以前は元ファイルの絶対パスをそのまま登録していたため、
				//エディタを閉じたときの書き戻し先が解決できず、画像が壊れた表示(❌)になっていた
				((IconTypeEditor)ResEdit.nulleditor.child).addPictureFile(new File(stringList.get(2)));
				ResEdit.nulleditor.child.open(ResEdit.nulleditor.child.pcard, 0);
				/*Rsrc.rsrcClass[] newAry = new Rsrc.rsrcClass[ResEdit.nulleditor.child.rsrcAry.length+1];
				for(int i=0; i<newAry.length-1; i++){
					newAry[i] = ResEdit.nulleditor.child.rsrcAry[i];
				}
				newAry[newAry.length-1] = ResEdit.nulleditor.child.pcard.stack.rsrc.
					new rsrcClass(newAry.length, "icon", stringList.get(2), stringList.get(2), "0", "0", null);
				ResEdit.nulleditor.child.rsrcAry = newAry;*/
			}
			else{
				//script
				while(!stringList.get(next).equalsIgnoreCase("of")){
					if(!newStr.equals("")) newStr += " ";
					newStr += stringList.get(next);
					next++;
					if(next>=stringList.size()) throw new xTalkException("edit requires of");
				}
				next++;
				if(next>=stringList.size()) throw new xTalkException("Cannot understand edit of");
				String only = next==stringList.size()-1 && typeAry[next]!=TTalk.wordType.STRING ? stringList.get(next) : "";
				if(HZ.stack()!=null && HZ.stack().curCard!=null && (self.isCardWord(only) || self.isBackgroundWord(only) || only.equalsIgnoreCase("stack"))){
					//edit script of card (AOL AppendXcmd): a bare card/bkgnd/stack at the end is this one
					obj = self.isCardWord(only) ? HZ.stack().curCard : self.isBackgroundWord(only) ? HZ.stack().curCard.getBg() : HZ.stack();
				}
				else obj = self.getObject(stringList, typeAry, next, stringList.size()-1, memData, object, target);
				if(obj == null) throw new xTalkException("Cannot identify the object to edit");
				//edit the script of this stack (HyperCard Instance Variables): "the" may come first
				if(newStr.equalsIgnoreCase("script") || newStr.equalsIgnoreCase("the script")){
					if(HZ.stack()!=null && HZ.stack().cantPeek) throw new xTalkException("This stack's scripts cannot be viewed (cantPeek)");
					ScriptEditor.openScriptEditor(PCARD.pc, obj);
				} else {
					 throw new xTalkException("Cannot edit {0}", newStr);
				}
			}
		}
		else if(str=="export"){
			//export paint/card to file <expr>; export paint|card|screen to <variable> (image data, HyperZebra; LiveCode exports
			//to variables too): screen = the card window as it looks, screenFilter and screenOverlay included
			int next=1;
			if(next>=stringList.size() || (!stringList.get(next).equalsIgnoreCase("paint") && !stringList.get(next).equalsIgnoreCase("card") &&
					!stringList.get(next).equalsIgnoreCase("screen")))
				throw new xTalkException("Cannot understand export");
			boolean exportCard=stringList.get(next).equalsIgnoreCase("card");
			boolean exportScreen=stringList.get(next).equalsIgnoreCase("screen");
			next++;
			if(next>=stringList.size() || !stringList.get(next).equalsIgnoreCase("to"))
				throw new xTalkException("export paint requires to file");
			next++;
			if(next==stringList.size()-1 && typeAry[next]!=TTalk.wordType.STRING && !stringList.get(next).equalsIgnoreCase("file")){
				BufferedImage shot;
				if(exportScreen) shot=TalkImage.screenImage();
				else if(exportCard) shot=CardImageExport.render(HZ.stack().curCard);
				else if(Host.get().paintToolActive()) shot = PaintFile.exportImage(PCARD.pc);
				else shot = HZ.editBackground ? HZ.stack().curCard.bg.pict : HZ.stack().curCard.pict;
				if(shot==null) shot = new BufferedImage(HZ.stack().width, HZ.stack().height, BufferedImage.TYPE_INT_ARGB);
				self.setVariable(memData, stringList.get(next), TalkImage.data(TalkImage.ownCopy(shot))); //not the card's own picture: it changes
				return true;
			}
			if(next>=stringList.size() || !stringList.get(next).equalsIgnoreCase("file"))
				throw new xTalkException("export paint requires to file");
			next++;
			if(next>=stringList.size()) throw new xTalkException("Cannot understand export paint to file");
			String path = self.Evalution(stringList, typeAry, next, stringList.size()-1, memData, object, target);
			if(!new File(path).exists()){
				path = self.convertFromMacPath(path);
			}
			if(PaintFile.formatOf(path)==null){
				path = path+".png";
			}
			//ペイントツール中なら浮いている選択範囲も含めて(Export Paint…と同じ)
			BufferedImage exportPict;
			if(exportScreen) exportPict=TalkImage.screenImage();
			else if(exportCard) exportPict=CardImageExport.render(HZ.stack().curCard);
			else if(Host.get().paintToolActive()) exportPict = PaintFile.exportImage(PCARD.pc);
			else exportPict = HZ.editBackground ?
				HZ.stack().curCard.bg.pict : HZ.stack().curCard.pict;
			if(exportPict==null) throw new xTalkException("No image to export");
			if(!Sandbox.allowFile(new File(path), Sandbox.Access.WRITE, Sandbox.scriptRequester()))
				throw new xTalkException("File access was denied: {0}", path);
			try{
				PaintFile.write(exportPict, new File(path));
			}catch(IOException e){
				throw new xTalkException("Could not export to file: {0}", e.getMessage());
			}
		}
		else if(str=="feather" || str=="grow" || str=="shrink"){
			//feather [selection] [by] <expr> / grow [selection] [by] <expr> / shrink [selection] [by] <expr>
			//今の浮動選択の形を変える(ペイントメニューのFeather/Grow/Shrink Selection…と同じ)
			int next=1;
			if(next<stringList.size() && stringList.get(next).equalsIgnoreCase("the")) next++;
			if(next<stringList.size() && stringList.get(next).equalsIgnoreCase("selection")) next++;
			if(next<stringList.size() && stringList.get(next).equalsIgnoreCase("by")) next++;
			if(next>=stringList.size()) throw new xTalkException("{0} requires a width in pixels", str);
			String v = self.Evalution(stringList, typeAry, next, stringList.size()-1, memData, object, target);
			final float radius;
			try{ radius = Float.parseFloat(v.trim()); }
			catch(NumberFormatException e){ throw new xTalkException("The width for {0} must be a number: {1}", str, v); }
			if(radius<0) throw new xTalkException("The width for {0} must be at least 0", str);
			if(PCARD.pc.floating==null) throw new xTalkException("No selection");
			final String which = str;
			Host.get().runOnEDT(() -> {
				PaintTool.owner = PCARD.pc;
				if(radius==0) return;
				if(which.equals("feather")) PaintTool.featherSelection(radius);
				else if(which.equals("grow")) PaintTool.growSelection(radius);
				else PaintTool.shrinkSelection(radius);
			});
		}
		else if(str=="import"){
			//import paint from file <expr>
			int next=1;
			if(next>=stringList.size() || !stringList.get(next).equalsIgnoreCase("paint"))
				throw new xTalkException("Cannot understand import");
			next++;
			if(next>=stringList.size() || !stringList.get(next).equalsIgnoreCase("from"))
				throw new xTalkException("import paint requires from file");
			next++;
			if(next>=stringList.size()) throw new xTalkException("import paint requires from file");
			BufferedImage imported;
			if(!stringList.get(next).equalsIgnoreCase("file") || typeAry[next]==TTalk.wordType.STRING){
				//import paint from <image data> (HyperZebra): imageFilter's results, URL "binfile:…"
				imported = TalkImage.image(self.Evalution(stringList, typeAry, next, stringList.size()-1, memData, object, target), "import paint");
			}else{
			next++;
			if(next>=stringList.size()) throw new xTalkException("Cannot understand import paint from file");
			String path = self.Evalution(stringList, typeAry, next, stringList.size()-1, memData, object, target);
			if(!new File(path).exists()){
				path = self.convertFromMacPath(path);
			}
			if(!Sandbox.allowFile(new File(path), Sandbox.Access.READ, Sandbox.scriptRequester()))
				throw new xTalkException("File access was denied: {0}", path);
			try{
				imported = PaintFile.read(new File(path));
			}catch(IOException e){
				throw new xTalkException("Could not read file: {0}", e.getMessage());
			}
			if(imported==null) throw new xTalkException("Could not read the file as an image");
			}
			if(Host.get().paintToolActive()){
				//ペイントツール中はHyperCardと同じく選択範囲(浮動選択)として置く
				final BufferedImage img = imported;
				Host.get().runOnEDT(() -> { PaintTool.owner = PCARD.pc; PaintTool.pasteAsSelection(img); });
				return true;
			}
			OCardBase cdbase = HZ.editBackground ?
				(OCardBase)HZ.stack().curCard.bg : (OCardBase)HZ.stack().curCard;
			if(cdbase.pict==null){
				cdbase.pict = new BufferedImage(HZ.stack().width, HZ.stack().height, BufferedImage.TYPE_INT_ARGB);
			}
			cdbase.pict = PictureCache.writable(cdbase.pict);
			Graphics2D g2 = (Graphics2D) cdbase.pict.getGraphics();
			g2.drawImage(imported, 0, 0, null);
			cdbase.changed = true;
			PCARD.pc.mainPane.repaint();
		}
		else return false;
		return true;
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void traceMode(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
					ScriptEditor.setTracemode();
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction4 (the chain asks this in their place): true when one of them matched. */
	static boolean watcherFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("messagewatcher")){
			result.theResult = Boolean.toString(MessageWatcher.watcherWindow!=null && MessageWatcher.watcherWindow.isVisible());
			result.ret = 0;
		}
		else if(0==message.compareTo("variablewatcher")){
			result.theResult = Boolean.toString(VariableWatcher.watcherWindow!=null && VariableWatcher.watcherWindow.isVisible());
			result.ret = 0;
		}
		else if(0==message.compareTo("debugger")){
			result.theResult = Boolean.toString(ScriptDebugger.paused);
			result.ret = 0;
		}
		else return false;
		return true;
	}

	// from OStack (instance)
	/** Lines of OStack.clean (the core calls this in their place). */
	static void editorsAtStackClose(OStack self) {
		ScriptEditor.stackClosed(self); //the editor's tabs show this stack's objects: it goes with them
	}

	// from TalkBinary
	static String imageToArray(String data) throws xTalkException {
		BufferedImage img = TalkImage.image(data, "imageToArray");
		int w = img.getWidth(), h = img.getHeight();
		if((long)w * h > 50_000_000L) throw new xTalkException("imageToArray: the image is too large");
		int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
		return TalkPixels.arrayText(w, h, argb); //remembered with its pixels: a variable's array keeps them as numbers
	}

	// from TalkBinary
	static String arrayToImage(String array, String format) throws xTalkException {
		if(!TalkCollection.isCollection(array)) throw new xTalkException("arrayToImage requires an array of rows of pixels");
		String f0 = format == null ? "png" : format.trim().toLowerCase(Locale.ROOT);
		Object[] known = TalkPixels.lookup(array);
		if(known != null && (f0.isEmpty() || f0.equals("png"))) { //the pixels of the array, without reading its text
			int kw = (Integer)known[0], kh = (Integer)known[1];
			BufferedImage img = new BufferedImage(kw, kh, BufferedImage.TYPE_INT_ARGB);
			img.setRGB(0, 0, kw, kh, (int[])known[2], 0, kw);
			return TalkImage.data(img);
		}
		TalkCollection rows = TalkCollection.decode(array);
		if(!rows.isArray()) throw new xTalkException("arrayToImage requires an array of rows of pixels");
		int h = rows.items.size(), w = 0;
		ArrayList<java.util.List<String>> pixels = new ArrayList<>(h);
		for(String r : rows.items) {
			java.util.List<String> row = TalkCollection.isCollection(r) && TalkCollection.decode(r).isArray()
				? TalkCollection.decode(r).items : java.util.List.of();
			pixels.add(row);
			w = Math.max(w, row.size());
		}
		if(w == 0 || h == 0) throw new xTalkException("arrayToImage: the array has no pixels");
		if((long)w * h > 50_000_000L) throw new xTalkException("arrayToImage: the image is too large");
		String f = format == null ? "png" : format.trim().toLowerCase(Locale.ROOT);
		if(f.isEmpty()) f = "png";
		if(f.equals("jpg")) f = "jpeg";
		if(!f.equals("png") && !f.equals("jpeg") && !f.equals("gif") && !f.equals("bmp"))
			throw new xTalkException("arrayToImage: the format must be png, jpeg, gif or bmp: {0}", format);
		boolean alpha = f.equals("png") || f.equals("gif");
		int[] argb = new int[w*h];
		int[] c = new int[4];
		for(int y=0; y<h; y++) {
			java.util.List<String> row = pixels.get(y);
			for(int x=0; x<row.size(); x++) {
				TalkBinary.pixel(row.get(x), c);
				argb[y*w + x] = c[3] << 24 | c[0] << 16 | c[1] << 8 | c[2];
			}
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, w, h, argb, 0, w);
		if(!alpha) {
			//JPEG and BMP have no transparency: on white
			BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			g.setColor(java.awt.Color.WHITE);
			g.fillRect(0, 0, w, h);
			g.drawImage(img, 0, 0, null);
			g.dispose();
			img = rgb;
		}
		if(f.equals("png")) return TalkImage.data(img);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if(!javax.imageio.ImageIO.write(img, f, out)) throw new xTalkException("arrayToImage: cannot write {0}", f);
			return TalkBinary.string(out.toByteArray());
		}catch(IOException e){ throw new xTalkException("arrayToImage: cannot write {0}", f); }
	}

	// from TUtil
	static boolean setWatcherWindowProperty(OWindow w, String prpty, String value) throws xTalkException {
		javax.swing.JDialog d = w.dlog;
		switch(prpty){
		case "rect": case "rectangle":{
			int[] r = TUtil.coordinates(value, 4, "A rectangle is required here");
			d.setBounds(TUtil.windowOrigin().x+Host.get().toPhysical(r[0]), TUtil.windowOrigin().y+Host.get().toPhysical(r[1]), Host.get().toPhysical(r[2]-r[0]), Host.get().toPhysical(r[3]-r[1]));
			return true;}
		case "loc": case "location": case "topleft":{
			int[] p = TUtil.coordinates(value, 2, "Coordinates are required here");
			d.setLocation(TUtil.windowOrigin().x+Host.get().toPhysical(p[0]), TUtil.windowOrigin().y+Host.get().toPhysical(p[1]));
			return true;}
		case "hbarloc": case "vbarloc":
			if(!(d instanceof VariableWatcher)) return false;
			int v = TUtil.propertyInteger(prpty, value.trim());
			if(prpty.equals("hbarloc")) ((VariableWatcher)d).setHBarLoc(Host.get().toPhysical(v)); else ((VariableWatcher)d).setVBarLoc(Host.get().toPhysical(v));
			return true;
		default: return false;
		}
	}

	// from ScriptDebugger
	static void pause(OObject object, int line, MemoryData memData, boolean error, String errorMessage)
	throws xTalkException
	{
		ScriptDebugger.paused = true;
		ScriptDebugger.pausedOnError = error;
		ScriptDebugger.pausedErrorMessage = errorMessage;
		ScriptDebugger.pausedObject = object;
		ScriptDebugger.pausedLine = line;
		ScriptDebugger.pausedMemData = memData;
		try{
			ScriptEditor.showDebugLine(object, line, error);
			VariableWatcher.refresh();

			if(TTalk.tracemode==ScriptDebugger.MODE_TRACE){
				traceSleep();
				return;
			}

			//ステップ実行: メニュー操作(stepflag)か中断(stop)を待つ
			while(!TTalk.stepflag && !TTalk.stop){
				try{
					Thread.sleep(20);
				}catch(InterruptedException e){
					Thread.currentThread().interrupt();
					break;
				}
				if(!scriptEditorVisible()){
					//エディタを閉じたら通常実行に戻る
					TTalk.tracemode = ScriptDebugger.MODE_RUN;
					break;
				}
			}
			TTalk.stepflag = false;
			if(TTalk.stop) throw new xTalkException("user abort.");
		}finally{
			ScriptDebugger.paused = false;
			ScriptDebugger.pausedOnError = false;
			ScriptDebugger.pausedErrorMessage = null;
			ScriptDebugger.pausedMemData = null;
		}
	}

	// from ScriptDebugger
	static boolean scriptEditorVisible(){
		if(PCARD.pc==null || PCARD.pc.stack==null) return true;
		ScriptEditor editor = PCARD.pc.stack.scriptEditor;
		return editor==null || editor.isVisible();
	}

	// from ScriptDebugger
	static void traceSleep(){
		int ticks = 0;
		try{
			ticks = Integer.parseInt(TUtil.compatibilityProperty("tracedelay").trim());
		}catch(NumberFormatException e){
		}
		if(ticks<=0) return;
		long endTime = System.currentTimeMillis() + ticks*1000L/60L;
		while(!TTalk.stop && System.currentTimeMillis()<endTime){
			try{
				Thread.sleep(20);
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	// from TTalk (instance)
	/** Lines of TTalk.handleScriptError (the core calls this in their place). */
	static void openEditorAtError(TTalk self, xTalkException e, OObject object, ArrayList<String> scriptList, int line, MemoryData memData, int mode) throws xTalkException {
			ScriptEditor.openScriptEditor(PCARD.pc, object, line);
	}

	// from TUtil
	/** A block of TUtil.getProperty that always returns (the core keeps its condition). */
	static String watcherBarLoc(String inProperty, OObject obj, OObject target, MemoryData memData, String prpty) throws xTalkException {
			VariableWatcher w = (VariableWatcher)((OWindow)obj).dlog;
			return Integer.toString(Host.get().toLogical(prpty.equals("hbarloc") ? w.hBarLoc() : w.vBarLoc()));
		}

	// from TTalk (instance)
	/** Lines of TTalk.ReceiveMessage (the core calls this in their place). */
	static void showInMessageWatcher(TTalk self, String message, String[] params, OObject object, OObject target, MemoryData memData, Result result, boolean isFunc, boolean isDynamic, boolean isBgroot, int usingCnt) throws xTalkException {
			if(MessageWatcher.watcherWindow!=null && MessageWatcher.watcherWindow.isVisible()){
				MessageWatcher.watcherWindow.setTable(self.messageRingArray, self.objectRingArray, self.ringCnt);
			}
	}
}

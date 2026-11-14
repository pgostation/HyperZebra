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

/** Desktop code moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class FilesUI {
	private FilesUI(){}

	// from TTalk (instance)
	/** Branches of TTalk.CommandExec10 (the chain asks this in their place): true when one of them matched. */
	static boolean saveCommand(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if(str=="save"){
			//save [this] stack as [stack] <file> / save stack <name> as <file> : スタックのコピーを作る(Save A Copy)。
			//カレントスタックはそのまま。失敗は the result で知らせる。
			//HyperZebra拡張: as の無い "save [this] stack" は今のファイルへ書き戻す。
			int asPos = -1;
			for(int i=1; i<stringList.size(); i++){
				if(typeAry[i]!=TTalk.wordType.STRING && stringList.get(i).equalsIgnoreCase("as")){ asPos = i; break; }
			}
			int srcEnd = (asPos>=0 ? asPos : stringList.size()) - 1;
			OStack stack = HZ.stack();
			String sourcePath = null; //開いていないスタックをコピーする場合のパス
			boolean current = srcEnd<1 ||
				(srcEnd==2 && stringList.get(1).equalsIgnoreCase("this") && stringList.get(2).equalsIgnoreCase("stack")) ||
				(srcEnd==1 && stringList.get(1).equalsIgnoreCase("stack"));
			if(!current){
				int p = stringList.get(1).equalsIgnoreCase("stack") ? 2 : 1;
				if(p>srcEnd) throw new xTalkException("Cannot identify the stack to save");
				String ref = self.Evalution(stringList, typeAry, p, srcEnd, memData, object, target);
				String resolved = self.resolveStackPath(ref);
				if(resolved==null) throw new xTalkException("No such stack: {0}", ref);
				if(self.isCurrentStackPath(resolved)) current = true;
				else sourcePath = self.stackIdentityPath(resolved);
			}
			if(asPos<0){
				if(!current) throw new xTalkException("save requires as <filename>");
				if(stack.saveXML==null) throw new xTalkException("This stack cannot be saved");
				//書けなかったことを the result で返す(以前は失敗しても空=成功だった)
				result.theResult = stack.saveXML.saveStackNow() ? "" : "Couldn't save stack: "+stack.saveXML.lastError;
				return true;
			}
			int destStart = asPos+1;
			if(destStart<stringList.size() && typeAry[destStart]!=TTalk.wordType.STRING && stringList.get(destStart).equalsIgnoreCase("stack")) destStart++;
			if(destStart>=stringList.size()) throw new xTalkException("Missing destination for save as");
			String dest = self.Evalution(stringList, typeAry, destStart, stringList.size()-1, memData, object, target);
			File destFile = self.saveAsDestination(dest);
			//a HyperCard stack file that is not open is copied as one (HyperCard's save stack as): the copy keeps the name given,
			//so an external handed that path finds it (UxCompressRsrc's sample: save stack it as it & "@3", then compresses the copy)
			File rawSource = !current && sourcePath!=null && new File(sourcePath).isFile() && !StackBundle.isPacked(new File(sourcePath)) &&
				OStack.looksLikeHyperCardStack(sourcePath) ? new File(sourcePath) : null;
			if(rawSource==null && !destFile.getName().toLowerCase().endsWith(".hzb")) destFile = new File(destFile.getPath()+".hzb");
			if(destFile.exists()){
				result.theResult = "Couldn't save stack: a file with that name already exists.";
				return true;
			}
			if(!Sandbox.allowFile(destFile, Sandbox.Access.WRITE, Sandbox.scriptRequester())){
				result.theResult = "Couldn't save stack: access not allowed.";
				return true;
			}
			if(current && StackSaving.canSaveElsewhere(stack)){
				//File > Save a Copy… と同じ: 未保存の変更を書いてからフォルダごと写す(自動保存と重ならない・失敗したら残さない)
				String failure = StackSaving.saveACopy(stack, destFile, Sandbox.scriptRequester());
				result.theResult = failure==null ? "" : "Couldn't save stack: "+failure;
				return true;
			}
			if(rawSource!=null){
				try{
					WorkingCopies.copyFile(rawSource, destFile); //data fork, resource fork and Finder info
					result.theResult = "";
				}catch(IOException e){
					destFile.delete();
					result.theResult = "Couldn't save stack: "+e.getMessage();
				}
				return true;
			}
			File srcDir;
			File packedSource = current ? null : StackBundle.isPacked(new File(sourcePath)) ? new File(sourcePath) : null;
			if(packedSource!=null){
				//a stack file (.hzb ZIP) that is not open is copied as it is
				try{
					java.nio.file.Files.copy(packedSource.toPath(), destFile.toPath());
					result.theResult = "";
				}catch(IOException e){
					result.theResult = "Couldn't save stack: "+e.getMessage();
				}
				return true;
			}
			if(current){
				//現在の内容を書き出してから写す(書けなかったら写さない: 古い内容のコピーを成功扱いにしない)
				if(stack.saveXML!=null && !stack.saveXML.saveStackNow()){
					result.theResult = "Couldn't save stack: "+stack.saveXML.lastError;
					return true;
				}
				srcDir = new File(self.stackIdentityPath(stack.path));
			}else{
				srcDir = new File(sourcePath);
			}
			if(!srcDir.isDirectory()){
				result.theResult = "Couldn't save stack.";
				return true;
			}
			try{
				StackBundle.packFolder(srcDir, destFile); //the copy is a stack file (.hzb ZIP)
				result.theResult = "";
			}catch(IOException e){
				result.theResult = "Couldn't save stack: "+e.getMessage();
			}
		}
		else return false;
		return true;
	}

	// from TTalk
	static File expandShortHfsNames(File path) {
		java.util.List<String> parts = new java.util.ArrayList<String>();
		File root = path;
		while(root.getParentFile()!=null){ parts.add(0, root.getName()); root = root.getParentFile(); }
		File f = root; boolean replaced = false;
		FileManager68k namer = null;
		for(int i=0; i<parts.size(); i++){
			String part = parts.get(i);
			File next = new File(f, part);
			if(!next.exists() && f.isDirectory()){
				File other = UiHost.get().fileManager68kExistingOrUnarchived(f, part); //the other encoding's reading of the same bytes
				int hash = part.lastIndexOf('#');
				if(other==null && hash>0){
					String rest = part.substring(hash+1); int dot = rest.indexOf('.');
					String id = dot>=0 ? rest.substring(0, dot) : rest, extension = dot>=0 ? rest.substring(dot) : "";
					String prefix = part.substring(0, hash);
					File[] entries = id.matches("[0-9A-F]+") ? f.listFiles() : null;
					if(entries!=null){
						//a folder's ID is kept across VMs (FileManager68k.CatalogIds), so its short name is the same here: exact first
						if(namer==null){
							java.util.List<File> volumes = new java.util.ArrayList<File>(); java.util.List<String> names = new java.util.ArrayList<String>();
							FileSystem68k.namingVolumes(volumes, names);
							namer = new FileManager68k(null, TTalk.fileEncoding(), volumes, names);
						}
						for(File e : entries){
							try{ if(e.getName().length()>prefix.length() && e.isDirectory() && namer.macNameOf(e).equalsIgnoreCase(part)){ other = e; break; } }
							catch(IOException | RuntimeException ex){ /* not this one */ }
						}
						//a file's number is the VM's own: the entry whose name starts so
						if(other==null) for(File e : entries){
							String n = UiHost.get().fileManager68kProgramName(TTalk.fileEncoding(), e.getName());
							if(n.length()>prefix.length() && (n.startsWith(prefix) || e.getName().startsWith(prefix)) && n.endsWith(extension)){ other = e; break; }
						}
					}
				}
				if(other!=null){ next = other; replaced = true; }
			}
			f = next;
		}
		if(!replaced) return null;
		return f.exists() || f.getParentFile()!=null && f.getParentFile().isDirectory() ? f : null;
	}

	// from TUtil
	/** Lines of TUtil.SetProperty2 (the core calls this in their place). */
	static void saveLockNow(OObject obj, String inProperty, String value, String prpty, OButton btn, OField fld, OCard cd) throws xTalkException {
				if(HZ.stack().saveXML!=null && HZ.stack().saveXML.saveThread!=null){
					//cantmodifyにした/外したことを保存する
					HZ.stack().saveXML.saveThread.interrupt();
					try {
						Platform.get().sleep(100);
					} catch (InterruptedException e) {
					}
				}
	}
}

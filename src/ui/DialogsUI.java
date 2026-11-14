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

/** Desktop code moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class DialogsUI {
	private DialogsUI(){}

	// from TTalk (instance)
	/** A block of TTalk.CommandExec4 that always returns (the core keeps its condition). */
	static Result answerProgram(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException {
				//answer program <prompt> [of type <type>]: HyperCard 2.x のプログラムリンク(PPCブラウザ)。The programs the 68K PPC
				//Browser lists (PpcBrowser68k: HyperCard = this program, the running VM programs, the Finder); it = "zone:machine:
				//program" (HC J-2.3 through that browser, measured: "*:HyperZebra:HyperCard", the result empty), Cancel: it empty,
				//the result "Cancel". SendAEData's "Select Target App:" button always got Cancel (an address had to be typed)
				int ofType = -1;
				for(int k=next+1; k+1<stringList.size(); k++){
					if(typeAry[k]!=TTalk.wordType.STRING && typeAry[k+1]!=TTalk.wordType.STRING && stringList.get(k).equalsIgnoreCase("of") && stringList.get(k+1).equalsIgnoreCase("type")){ ofType = k; break; }
				}
				String prompt = ofType==next+1 ? "" : self.Evalution(stringList, typeAry, next+1, ofType>0 ? ofType-1 : stringList.size()-1, memData, object, target);
				java.util.List<String> names = self.answerProgramNames();
				int choice = self.chooseProgram(StackTranslation.dialogText(self.stackOf(object), prompt), names);
				if(choice<0 || choice>=names.size()){
					self.setVariable(memData, "it", "");
					result.theResult = "Cancel";
				}else{
					self.setVariable(memData, "it", "*:"+Toolbox68k.COMPUTER_NAME+":"+names.get(choice));
					result.theResult = "";
				}
				return result;
			}

	// from TTalk (instance)
	/** A block of TTalk.CommandExec4 that always returns (the core keeps its condition). */
	static Result answerFolder(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException {
				//answer folder <prompt> (HyperCard 2.4; The Update: answer folder "Select the source folder:"): a folder chooser,
				//it = the folder's path ending with the separator, as a folder path does; cancelled: it empty, the result "Cancel"
				String prompt = self.Evalution(stringList, typeAry, next+1, stringList.size()-1, memData, object, target);
				JFileChooser chooser = new JFileChooser();
				if(HZ.stack()!=null&&HZ.stack().file!=null){
					File directory = self.fileDialogFolder(GMenuBrowse.stackChooserDirectory(HZ.stack().file));
					if(directory!=null) chooser.setCurrentDirectory(directory);
				}
				chooser.setDialogTitle(StackTranslation.dialogText(self.stackOf(object), prompt));
				chooser.setDialogType(JFileChooser.OPEN_DIALOG);
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				int selected = PCARD.showFileChooser(chooser, PCARD.pc, false);
				GPictWindow.restorePictureWindows();
				if(selected == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile()!=null){
					String path = self.scriptPath(chooser.getSelectedFile());
					if(!path.endsWith(":") && !path.endsWith(File.separator)) path += path.indexOf(':')>=0 ? ":" : File.separator;
					self.setVariable(memData, "it", path);
				}else{
					self.setVariable(memData, "it", "");
					result.theResult = "Cancel";
				}
				return result;
			}

	// from TTalk (instance)
	/** Branches of TTalk.CommandExec4 (the chain asks this in their place): true when one of them matched. */
	static boolean answerFile(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int next) throws xTalkException {
		if(stringList.get(next).equals("file")){
				//ファイルオープンダイアログ
				next++;
				int j;
				for(j=next; j+1<stringList.size(); j++){
					if(stringList.get(j).equals("of") && stringList.get(j+1).equals("type")){
						break;
					}
				}
				//no "of type": the prompt runs to the end (AOL ppat Library: answer file "…“" & thename & "” in what file?" lost its last word)
				if(j+1>=stringList.size()) j = stringList.size();
				String answerStr=self.Evalution(stringList,typeAry,next,j-1, memData, object, target);
				//of type "tfil" or "FFIL" (HyperCard 2.2+; GX Font Display): several types joined by "or", or by commas
				//(MiniWriter: of type PNTG,PICT)
				java.util.List<String> types = new ArrayList<String>();
				if(j+2<stringList.size()){
					int from = j+2;
					for(int k=j+2; k<=stringList.size(); k++){
						if(k==stringList.size() || (typeAry[k]!=TTalk.wordType.STRING && (stringList.get(k).equalsIgnoreCase("or") || stringList.get(k).equals(",")) && k>from)){
							types.add(self.Evalution(stringList,typeAry,from,k-1, memData, object, target));
							from = k+1;
						}
					}
				}
				JFileChooser chooser = new JFileChooser();
				for(String type : types){
					class AnswerFileFilter extends FileFilter{
						String type;
						String type2;
						
						AnswerFileFilter(String type){
							this.type = type;
							if(type.equals("TEXT")) type2 = "txt";
							if(type.equals("PICT")) type2 = "png";
							if(type.equals("STAK")) type2 = "xml";
							if(type.equals("JPEG") || type.equals("JFIF")) type2 = "jpg";
							if(type.equals("GIFf")) type2 = "gif";
						}
						//a stack is also a HyperZebra stack file (.hzb), the kind HyperZebra makes
						private boolean stackFile(String ext){ return type.equals("STAK") && "hzb".equalsIgnoreCase(ext); }
						
						public boolean accept(File f){
							if (f.isDirectory()){
								return true; //フォルダは移動できるように常に表示する
							}

							String ext = getExtension(f);
							if (ext==null || ext.equalsIgnoreCase(type) || ext.equalsIgnoreCase(type2) || stackFile(ext)){
								return true;
							}
							return false;
						}

						public String getDescription(){
							return type+"ファイル";
						}

						private String getExtension(File f){
							String ext = null;
							String filename = f.getName();
							int dotIndex = filename.lastIndexOf('.');

							if ((dotIndex > 0) && (dotIndex < filename.length() - 1)){
								ext = filename.substring(dotIndex + 1).toLowerCase();
							}

							return ext;
						}
					}
					chooser.addChoosableFileFilter(new AnswerFileFilter(type));
				}
				if(HZ.stack()!=null&&HZ.stack().file!=null){
					File directory = self.fileDialogFolder(GMenuBrowse.stackChooserDirectory(HZ.stack().file));
					if(directory!=null) chooser.setCurrentDirectory(directory);
				}
				chooser.setDialogTitle(StackTranslation.dialogText(self.stackOf(object), answerStr));
				chooser.setDialogType(JFileChooser.OPEN_DIALOG);
				chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
				//lowerPictureWindows()はPictureウインドウを全ウインドウの最背面(カードウインドウの
				//背面)へ送ってしまうため呼ばない。ファイル選択ダイアログは新規表示時に自然と
				//最前面へ来るため、Pictureウインドウ側の順序には触れる必要がない。
				int selected = PCARD.showFileChooser(chooser, PCARD.pc, false);
				GPictWindow.restorePictureWindows();
				if (selected == JFileChooser.APPROVE_OPTION) {
					//a stack is named as "the long name" names it, without .hzb: Miko's installer refuses to install into the stack
					//itself when it is pathThis(), and scripts compare the two
					File chosenFile = chooser.getSelectedFile();
					boolean stackFile = chosenFile!=null && StackBundle.hasHzbExtension(chosenFile);
					self.setVariable(memData, "it", stackFile ? self.macStackPath(chosenFile.getPath()) : self.scriptPath(chosenFile));
				}else{
					//キャンセル: itは空、the resultは"Cancel"(HyperCard)
					self.setVariable(memData, "it", "");
					result.theResult = "Cancel";
				}
			}
		else return false;
		return true;
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void aboutDialog(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
					new GDialog(null, HZ.AppName+" "+HZ.longVersion,
							null,"OK",null,null);
	}

	// from TTalk
	static int showScriptErrorDialog(String errorMessage, String statement, int line, OObject object,
			boolean hasScript, boolean canDebug)
	{
		final int[] choice = { TTalk.ERROR_CANCEL };
		Runnable show = new Runnable() {
			public void run() {
				choice[0] = TTalk.showScriptErrorDialogOnEdt(errorMessage, statement, line, object, hasScript, canDebug);
			}
		};
		if(SwingUtilities.isEventDispatchThread()){
			show.run();
		}
		else{
			try{
				SwingUtilities.invokeAndWait(show);
			}catch(Exception ex){
				ex.printStackTrace();
			}
		}
		return choice[0];
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec11 (the core calls this in their place). */
	static void openPanelInfo(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OPanel panel) throws xTalkException {
					javax.swing.SwingUtilities.invokeLater(() -> AuthDialog.openAuthDialog(PCARD.pc, "panel", panel));
	}
}

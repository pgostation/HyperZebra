import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;

/**
 * Standard File Package (Pack 3).
 *
 * Plain SFGetFile/SFPutFile/StandardGetFile/StandardPutFile calls use the host
 * file chooser. Calls that supply their own dialog, dialog hook, event filter
 * or file filter (SFPGetFile, CustomGetFile and friends: self-extracting
 * archives, "select a folder" dialogs) run a classic Standard File dialog
 * inside the emulated Dialog Manager instead, so the program's DLOG/DITL,
 * hook and filter procedures see exactly what they expect.
 */
final class StandardFile68k {
	private static final int SF_SAVE_DISK=0x214,CUR_DIR_STORE=0x398;
	private static final int GET_DLG_ID=-4000,PUT_DLG_ID=-3999,SF_GET_DLG_ID=-6042,SF_PUT_DLG_ID=-6043;
	// old-style item numbers (getDlgID / putDlgID layouts)
	private static final int GET_OPEN=1,GET_CANCEL=3,GET_DISK=4,GET_EJECT=5,GET_DRIVE=6,GET_LIST=7,GET_SCROLL=8,GET_PROMPT=10;
	private static final int PUT_SAVE=1,PUT_CANCEL=2,PUT_PROMPT=3,PUT_DISK=4,PUT_EJECT=5,PUT_DRIVE=6,PUT_NAME=7,PUT_LIST=8,PUT_SCROLL=9;
	// System 7 item numbers (sfGetDialogID / sfPutDialogID layouts, used by CustomGetFile/CustomPutFile dialogs): the list
	// user item includes its scroll bar, item 8 is the folder pop-up, 6 is "Desktop", and put dialogs add 10-12
	private static final int SF_OPEN=1,SF_CANCEL=2,SF_VOLUME=4,SF_EJECT=5,SF_DESKTOP=6,SF_LIST=7,SF_POPUP=8,SF_NAME=10,SF_PROMPT=11,SF_NEW_FOLDER=12;
	// pseudo-items returned by dialog hooks
	// pseudo-items produced by the dialog's native event hook (folder pop-up clicks and keyboard navigation); never shown to the program's hook
	private static final int NATIVE_POPUP=1001,NATIVE_PARENT=1002,NATIVE_OPEN=1003,NATIVE_DESKTOP=1004,NATIVE_SORT=1005;
	private static final int POPUP_HEIGHT=20;
	/**
	 * The wide layout: the dialog (the program's own DLOG included) is stretched to {@link #WIDE_WIDTH} x {@link #WIDE_GET_HEIGHT}
	 * (or as much as the screen allows - it stays within a 640x480 screen), the file list gets a second, switchable column
	 * (date, size or kind) under a sortable header, the folder pop-up lists favourite and recent folders, and a preview of the
	 * selected file goes into the gap of the button column. The Preferences dialog sets this (PCARD.wideFileDialogs68k);
	 * {@code -Dhz68k.standardfile=classic} keeps the original sizes, {@code =wide} forces the wide layout, for scripted runs.
	 */
	static boolean wide=!"classic".equals(System.getProperty("hz68k.standardfile"));
	/** The layout for this program: its own launcher setting when it has one (and no -Dhz68k.standardfile), else the preference. */
	private boolean wide(){
		if(System.getProperty("hz68k.standardfile")!=null||tb.app==null)return wide;
		Boolean own=tb.app.wideDialogsOverride;return own==null?wide:own;
	}
	private static final int WIDE_WIDTH=500,WIDE_GET_HEIGHT=340,WIDE_PUT_HEIGHT=370,HEADER_HEIGHT=18,MIN_DETAIL_WIDTH=300,MIN_PREVIEW_GAP=96;
	private static final int[] COLUMN_WIDTHS={0,132,-76,112};
	private static final String[] COLUMN_NAMES={"Name","Date Modified","Size","Kind"};
	private static final int RECENT_FOLDERS=8;
	/** The detail column shown beside the name (1 date, 2 size, 3 kind), chosen from the header's pop-up; remembered across runs. */
	static int detailColumn=1;
	/** Sort order of the file list (column 0-3, {@link #COLUMN_NAMES}), remembered across runs. */
	static int sortColumn;static boolean sortDescending;
	private static final java.util.prefs.Preferences prefs=preferences();
	private static java.util.prefs.Preferences preferences(){try{return java.util.prefs.Preferences.userNodeForPackage(StandardFile68k.class);}catch(Exception e){return null;}}
	static{try{if(prefs!=null){sortColumn=Math.max(0,Math.min(3,prefs.getInt("fileDialogSort68k",0)));sortDescending=prefs.getBoolean("fileDialogSortDescending68k",false);detailColumn=Math.max(1,Math.min(3,prefs.getInt("fileDialogColumn68k",1)));}}catch(Exception e){}}
	static void setSort(int column,boolean descending){sortColumn=column;sortDescending=descending;try{if(prefs!=null){prefs.putInt("fileDialogSort68k",column);prefs.putBoolean("fileDialogSortDescending68k",descending);}}catch(Exception e){}}
	/** Shows another detail column; a sort by the old one becomes a sort by the new one. */
	static void setDetailColumn(int column){detailColumn=Math.max(1,Math.min(3,column));if(sortColumn!=0)setSort(detailColumn,sortDescending);try{if(prefs!=null)prefs.putInt("fileDialogColumn68k",detailColumn);}catch(Exception e){}}
	/** The folders most recently chosen in (newest first), for the folder pop-up; kept across runs. */
	static final List<String> recentFolders=new ArrayList<String>();
	static{try{if(prefs!=null)for(String path:prefs.get("fileDialogRecent68k","").split("\n"))if(!path.isEmpty())recentFolders.add(path);}catch(Exception e){}}
	static void addRecentFolder(File folder){
		if(folder==null)return;String path=folder.getAbsolutePath();
		recentFolders.remove(path);recentFolders.add(0,path);while(recentFolders.size()>RECENT_FOLDERS)recentFolders.remove(recentFolders.size()-1);
		try{if(prefs!=null)prefs.put("fileDialogRecent68k",String.join("\n",recentFolders));}catch(Exception e){}
	}
	private static final int HOOK_FIRST_CALL=-1,HOOK_NULL=100,HOOK_REBUILD=101,HOOK_FOLDER_POPUP=102,HOOK_OPEN_FOLDER=103,HOOK_OPEN_ALIAS=104,HOOK_GO_DESKTOP=105,HOOK_GO_ALIAS_TARGET=106,HOOK_GO_PARENT=107,HOOK_NEXT_DRIVE=108,HOOK_PREV_DRIVE=109,HOOK_CHANGE_SELECTION=110,HOOK_SET_ACTIVE=200,HOOK_LAST_CALL=-2;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private File lastDirectory;

	/** One Standard File call. */
	private static final class Request {
		boolean put,newStyle;int reply,dlgId,dlgHook,filterProc,fileFilter,yourData,activeList;String prompt="",defaultName="";int[] types;int numTypes=-1;
		boolean custom(){return dlgHook!=0||filterProc!=0||fileFilter!=0||(dlgId!=0&&dlgId!=GET_DLG_ID&&dlgId!=PUT_DLG_ID&&dlgId!=SF_GET_DLG_ID&&dlgId!=SF_PUT_DLG_ID)||"guest".equals(System.getProperty("hz68k.standardfile"));}
	}

	StandardFile68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean pack3(M68kCpu cpu) throws M68kException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;int sp=cpu.a[7];Request r=new Request();
		switch(selector){
			case 1:{r.put=true;r.reply=memory.readLong(sp);r.dlgHook=memory.readLong(sp+4);r.defaultName=tb.readPascal(memory.readLong(sp+8));r.prompt=tb.readPascal(memory.readLong(sp+12));cpu.a[7]+=20;break;}
			case 3:{r.put=true;r.filterProc=memory.readLong(sp);r.dlgId=(short)memory.readWord(sp+4);r.reply=memory.readLong(sp+6);r.dlgHook=memory.readLong(sp+10);r.defaultName=tb.readPascal(memory.readLong(sp+14));r.prompt=tb.readPascal(memory.readLong(sp+18));cpu.a[7]+=26;break;}
			case 2:{r.reply=memory.readLong(sp);r.dlgHook=memory.readLong(sp+4);r.numTypes=(short)memory.readWord(sp+12);r.types=types(memory.readLong(sp+8),r.numTypes);r.fileFilter=memory.readLong(sp+14);r.prompt=tb.readPascal(memory.readLong(sp+18));cpu.a[7]+=26;break;}
			case 4:{r.filterProc=memory.readLong(sp);r.dlgId=(short)memory.readWord(sp+4);r.reply=memory.readLong(sp+6);r.dlgHook=memory.readLong(sp+10);r.numTypes=(short)memory.readWord(sp+18);r.types=types(memory.readLong(sp+14),r.numTypes);r.fileFilter=memory.readLong(sp+20);r.prompt=tb.readPascal(memory.readLong(sp+24));cpu.a[7]+=32;break;}
			case 5:{r.put=true;r.newStyle=true;r.reply=memory.readLong(sp);r.defaultName=tb.readPascal(memory.readLong(sp+4));r.prompt=tb.readPascal(memory.readLong(sp+8));cpu.a[7]+=12;break;}
			case 6:{r.newStyle=true;r.reply=memory.readLong(sp);r.numTypes=(short)memory.readWord(sp+8);r.types=types(memory.readLong(sp+4),r.numTypes);r.fileFilter=memory.readLong(sp+10);cpu.a[7]+=14;break;}
			case 7:{r.put=true;r.newStyle=true;r.yourData=memory.readLong(sp);r.activeList=memory.readLong(sp+8);r.filterProc=memory.readLong(sp+12);r.dlgHook=memory.readLong(sp+16);r.dlgId=(short)memory.readWord(sp+24);r.reply=memory.readLong(sp+26);r.defaultName=tb.readPascal(memory.readLong(sp+30));r.prompt=tb.readPascal(memory.readLong(sp+34));cpu.a[7]+=38;break;}
			case 8:{r.newStyle=true;r.yourData=memory.readLong(sp);r.activeList=memory.readLong(sp+8);r.filterProc=memory.readLong(sp+12);r.dlgHook=memory.readLong(sp+16);r.dlgId=(short)memory.readWord(sp+24);r.reply=memory.readLong(sp+26);r.numTypes=(short)memory.readWord(sp+34);r.types=types(memory.readLong(sp+30),r.numTypes);r.fileFilter=memory.readLong(sp+36);cpu.a[7]+=40;break;}
			default:throw new M68kMacApiException(cpu.pc,0xa9ea,"Pack3 selector "+selector);
		}
		File scripted=r.put?scriptedSave:scriptedReply;
		if(scripted!=null){if(r.put)scriptedSave=null;else scriptedReply=null;lastDirectory=scripted.getParentFile();writeReply(r.reply,r.newStyle,scripted,r.put);} // debug `sfreply` / `sfsave`, custom dialogs too
		else if(r.custom()&&tb.hosted())guestDialog(r);
		else if(r.put)put(r.reply,r.newStyle,r.prompt,r.defaultName);
		else get(r.reply,r.newStyle,r.prompt,r.types);
		return true;
	}

	private int[] types(int typeList,int numTypes) throws M68kCpuException {
		if(numTypes<0||typeList==0)return null;int[] result=new int[Math.min(numTypes,16)];
		for(int i=0;i<result.length;i++)result[i]=memory.readLong(typeList+i*4);return result;
	}

	// ------------------------------------------------------------------ host file chooser

	/** Plain SFGetFile/StandardGetFile: the platform's own Open panel (java.awt.FileDialog), which is what the user knows,
	 *  filtered by the program's type list; Swing's chooser stays as the fallback when the native panel is unavailable. */
	/** The answer of the next plain Open panel instead of showing it (debug command `sfreply <path>`: scripted runs). */
	static volatile File scriptedReply;
	/** The answer of the next Save panel (debug command `sfsave <path>`), the counterpart of {@link #scriptedReply}. */
	static volatile File scriptedSave;
	private void get(int reply,boolean standard,final String prompt,final int[] types) throws M68kCpuException {
		File scripted=scriptedReply;
		if(scripted!=null){scriptedReply=null;lastDirectory=scripted.getParentFile();writeReply(reply,standard,scripted,false);return;}
		final File start=lastDirectory!=null?lastDirectory:tb.programDirectory();
		final java.io.FilenameFilter filter=types==null||types.length==0?null:new java.io.FilenameFilter(){
			@Override public boolean accept(File dir,String name){File f=new File(dir,name);if(f.isDirectory())return true;int type=tb.files.fileManager.shownType(f);if(type==0)return true;for(int t:types)if(t==type)return true;return false;}};
		File chosen=tb.host().ask(new Callable<File>(){@Override public File call(){
			File native_=nativeDialog(prompt.isEmpty()?"Open":prompt,start,null,filter,false);if(native_!=null||nativeDialogShown)return native_;
			JFileChooser chooser=new JFileChooser(start);chooser.setDialogTitle(prompt.isEmpty()?"Open":prompt);FileChooserIcons.install(chooser);
			chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			if(types!=null&&types.length>0)chooser.setFileFilter(new FileFilter(){
				@Override public boolean accept(File f){if(f.isDirectory())return true;int type=tb.files.fileManager.shownType(f);if(type==0)return true;for(int t:types)if(t==type)return true;return false;}
				@Override public String getDescription(){StringBuilder sb=new StringBuilder();for(int t:types)sb.append(ResourceFile68k.typeName(t)).append(' ');return sb.toString().trim();}});
			return chooser.showOpenDialog(tb.host().anyWindow())==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile():null;}});
		if(chosen!=null)lastDirectory=chosen.getParentFile();
		writeReply(reply,standard,chosen,false);
	}

	private void put(int reply,boolean standard,final String prompt,final String defaultName) throws M68kCpuException {
		File scripted=scriptedSave;
		if(scripted!=null){scriptedSave=null;lastDirectory=scripted.getParentFile();writeReply(reply,standard,scripted,true);return;}
		final File start=lastDirectory!=null?lastDirectory:tb.programDirectory();
		if(unattended()){writeReply(reply,standard,unusedName(start,defaultName),true);return;}
		File chosen=tb.host().ask(new Callable<File>(){@Override public File call(){
			File native_=nativeDialog(prompt.isEmpty()?"Save":prompt,start,defaultName,null,true);if(native_!=null||nativeDialogShown)return native_;
			JFileChooser chooser=new JFileChooser(start);chooser.setDialogTitle(prompt.isEmpty()?"Save":prompt);FileChooserIcons.install(chooser);
			if(!defaultName.isEmpty())chooser.setSelectedFile(new File(start,defaultName));
			return chooser.showSaveDialog(tb.host().anyWindow())==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile():null;}});
		if(chosen!=null)lastDirectory=chosen.getParentFile();
		writeReply(reply,standard,chosen,true);
	}

	/** A program run without the user (an expander's installer): its Save dialogs take the offered name in its own folder. */
	private boolean unattended(){return tb.app!=null&&tb.app.unattended;}
	/** The offered name in the folder, numbered when something of that name is there already (a folder an earlier run left);
	 *  a '/' in the Mac name is the host's ':'. */
	private static File unusedName(File folder,String name){
		if(name==null||name.trim().isEmpty())name="untitled";
		name=FileManager68k.hostFileName(name.replace('/',':'));
		File f=new File(folder,name);
		for(int n=2;f.exists()&&n<100;n++){String numbered=name+" "+n;f=new File(folder,numbered.length()>31?name.substring(0,Math.max(1,31-(" "+n).length()))+" "+n:numbered);}
		return f;
	}
	/** The host file a name typed in the Save dialog stands for, as the File Manager resolves it: a '/' in a Mac name is the host's
	 *  ':' - Anubis W/PC's installer (StuffIt InstallerMaker) offers "Atto install W/PC folder", and the reply's folder became a
	 *  nonexistent "Atto install W" (its DirCreate of "PC folder" then failed with dirNFErr -120). */
	File typedFile(File folder,String name){
		try{File f=tb.files.fileManager.childNamed(folder,name);if(f!=null)return f;}catch(IOException|RuntimeException e){}
		return new File(folder,name);
	}

	/** Set by {@link #nativeDialog} when the platform panel was actually shown (so a cancel is not retried in Swing). */
	private boolean nativeDialogShown;
	/** The platform's Open/Save panel on the host thread; null when it could not be shown or was cancelled. */
	private File nativeDialog(String title,File start,String defaultName,java.io.FilenameFilter filter,boolean save){
		nativeDialogShown=false;
		if(java.awt.GraphicsEnvironment.isHeadless())return null;
		try{
			java.awt.Window owner=tb.host().anyWindow();
			java.awt.FileDialog fd=owner instanceof java.awt.Frame?new java.awt.FileDialog((java.awt.Frame)owner,title,save?java.awt.FileDialog.SAVE:java.awt.FileDialog.LOAD)
				:owner instanceof java.awt.Dialog?new java.awt.FileDialog((java.awt.Dialog)owner,title,save?java.awt.FileDialog.SAVE:java.awt.FileDialog.LOAD)
				:new java.awt.FileDialog((java.awt.Frame)null,title,save?java.awt.FileDialog.SAVE:java.awt.FileDialog.LOAD);
			if(start!=null&&start.isDirectory())fd.setDirectory(start.getAbsolutePath());
			if(defaultName!=null&&!defaultName.isEmpty())fd.setFile(defaultName);
			if(filter!=null)fd.setFilenameFilter(filter);
			nativeDialogShown=true;
			fd.setVisible(true);
			String dir=fd.getDirectory(),name=fd.getFile();
			return dir==null||name==null?null:new File(dir,name);
		}catch(Exception e){nativeDialogShown=false;return null;}
	}

	private void writeReply(int reply,boolean standard,File file,boolean saving) throws M68kCpuException {
		if(file!=null)Sandbox.chosenByUser(tb.name,file,saving); // the user picked it: the program's to change, its folder to read
		if(file!=null&&!saving&&file.isFile()&&tb.app!=null)tb.app.recordDocument(file); // a document the user opened: the launcher lists it under the program
		// the package leaves the answer's folder in SFSaveDisk/CurDirStore: 3DMF Viewer builds its FSSpec from the old reply's
		// working directory and LMGetCurDirStore()
		if(file!=null){File dir=file.getAbsoluteFile().getParentFile();try{if(dir!=null){memory.writeWord(SF_SAVE_DISK,-tb.files.volumeRefNumFor(dir));memory.writeLong(CUR_DIR_STORE,tb.files.directoryIdFor(dir));}}catch(Exception e){}}
		if(reply==0)return;
		boolean good=file!=null;
		if(standard){
			memory.fill(reply,88,0);
			memory.writeByte(reply,good?1:0);
			if(good){
				memory.writeByte(reply+1,file.exists()&&saving?1:0);
				memory.writeLong(reply+2,file.exists()?tb.files.fileManager.shownType(file):0);
				try{tb.files.writeFileSpec(reply+6,file);}catch(IOException e){memory.writeByte(reply,0);}
				memory.writeWord(reply+76,0);memory.writeWord(reply+78,0);
				writeItemKind(reply,file);
			}
		}else{
			memory.fill(reply,74,0);
			memory.writeByte(reply,good?1:0);
			if(good){
				memory.writeLong(reply+2,file.exists()?tb.files.fileManager.shownType(file):0);
				memory.writeWord(reply+6,tb.files.workingDirectoryFor(file.getAbsoluteFile().getParentFile()));
				tb.writePascal(reply+10,displayName(file),63);
			}
		}
	}

	// ------------------------------------------------------------------ host panels for Navigation Services (PpcNavServices)

	/** What {@link #hostPanel} asks for: files to open, a file to save, a folder, or any file or folder. */
	static final int PANEL_OPEN=0,PANEL_SAVE=1,PANEL_FOLDER=2,PANEL_OBJECT=3;
	/** The folder the next host panel opens in: where the last Standard File or Navigation dialog was left, else the program's. */
	File panelDirectory(){return lastDirectory!=null&&lastDirectory.isDirectory()?lastDirectory:tb.programDirectory();}
	/** Whether no host panel can be shown (a headless run, a service VM): file dialogs then answer "cancelled". */
	boolean panelUnavailable(){return java.awt.GraphicsEnvironment.isHeadless()||!tb.hosted()||tb.host().headless;}
	/** Whether a Standard File type list admits {@code f}: folders, files of no known type, and files of a listed type. */
	boolean typeListAccepts(File f,int[] types){
		if(types==null||types.length==0||f.isDirectory())return true;
		int type=tb.files.fileManager.shownType(f);if(type==0)return true;
		for(int t:types)if(t==type)return true;
		return false;
	}
	/**
	 * The host's own panel for a Navigation Services dialog, on the program's thread (blocks until dismissed): the platform
	 * Open panel (with multiple selection when {@code multiple}), Save panel or folder chooser, falling back to Swing's chooser;
	 * {@code accept} filters the files shown (null: all). Returns the files chosen, empty when cancelled or when no panel can be
	 * shown. The caller hands the answer to {@link #chosen} (also for scripted answers).
	 */
	List<File> hostPanel(final int kind,final String title,final String button,File start,final String defaultName,final java.util.function.Predicate<File> accept,final boolean multiple){
		return hostPanel(kind,title,button,start,defaultName,accept,multiple,null);
	}
	/**
	 * What a program adds to a Navigation Services file dialog: the Format pop-up's items (popupExtension) and its own check
	 * boxes (kNavCtlAddControlList). {@link #hostPanel} shows them beside the chooser and, when the panel is approved, leaves the
	 * user's choices in {@link #format} and {@link #checked}.
	 */
	static final class PanelExtras {
		final String formatLabel;final List<String> formats=new ArrayList<String>();int format;
		final List<String> checks=new ArrayList<String>();final List<Boolean> checked=new ArrayList<Boolean>(),enabled=new ArrayList<Boolean>();
		PanelExtras(String formatLabel){this.formatLabel=formatLabel;}
		boolean isEmpty(){return formats.isEmpty()&&checks.isEmpty();}
	}
	/** {@link #hostPanel(int,String,String,File,String,java.util.function.Predicate,boolean)} with the program's additions shown as
	 *  an accessory of Swing's chooser (the platform panel takes none); {@code extras} null or empty: the plain panel. */
	List<File> hostPanel(final int kind,final String title,final String button,File start,final String defaultName,final java.util.function.Predicate<File> accept,final boolean multiple,final PanelExtras extras){
		final List<File> none=new ArrayList<File>();
		if(panelUnavailable())return none;
		final File from=start!=null&&start.isDirectory()?start:panelDirectory();
		final boolean extra=extras!=null&&!extras.isEmpty();
		List<File> answer=tb.host().ask(new Callable<List<File>>(){@Override public List<File> call(){
			boolean mac=System.getProperty("os.name","").startsWith("Mac");
			if(!extra&&(kind==PANEL_OPEN||kind==PANEL_SAVE||kind==PANEL_FOLDER&&mac)){ // the platform panel (macOS picks folders with fileDialogForDirectories)
				String property=kind==PANEL_FOLDER?System.getProperty("apple.awt.fileDialogForDirectories"):null;
				try{
					java.awt.Window owner=tb.host().anyWindow();int mode=kind==PANEL_SAVE?java.awt.FileDialog.SAVE:java.awt.FileDialog.LOAD;
					java.awt.FileDialog fd=owner instanceof java.awt.Frame?new java.awt.FileDialog((java.awt.Frame)owner,title,mode)
						:owner instanceof java.awt.Dialog?new java.awt.FileDialog((java.awt.Dialog)owner,title,mode):new java.awt.FileDialog((java.awt.Frame)null,title,mode);
					if(kind==PANEL_FOLDER)System.setProperty("apple.awt.fileDialogForDirectories","true");
					fd.setDirectory(from.getAbsolutePath());
					if(defaultName!=null&&!defaultName.isEmpty())fd.setFile(defaultName);
					if(accept!=null&&kind==PANEL_OPEN)fd.setFilenameFilter(new java.io.FilenameFilter(){@Override public boolean accept(File dir,String name){return accept.test(new File(dir,name));}});
					if(kind==PANEL_OPEN)fd.setMultipleMode(multiple);
					fd.setVisible(true);
					List<File> files=new ArrayList<File>();
					if(kind==PANEL_OPEN)files.addAll(Arrays.asList(fd.getFiles()));
					else if(fd.getDirectory()!=null&&fd.getFile()!=null)files.add(new File(fd.getDirectory(),fd.getFile()));
					return files;
				}catch(Exception e){/* no platform panel: Swing's chooser below */}
				finally{if(kind==PANEL_FOLDER){if(property==null)System.clearProperty("apple.awt.fileDialogForDirectories");else System.setProperty("apple.awt.fileDialogForDirectories",property);}}
			}
			JFileChooser chooser=new JFileChooser(from);chooser.setDialogTitle(title);FileChooserIcons.install(chooser);
			chooser.setFileSelectionMode(kind==PANEL_FOLDER?JFileChooser.DIRECTORIES_ONLY:kind==PANEL_OBJECT?JFileChooser.FILES_AND_DIRECTORIES:JFileChooser.FILES_ONLY);
			chooser.setMultiSelectionEnabled(multiple&&kind!=PANEL_SAVE);
			if(button!=null&&!button.isEmpty())chooser.setApproveButtonText(button);
			if(kind==PANEL_SAVE&&defaultName!=null&&!defaultName.isEmpty())chooser.setSelectedFile(new File(from,defaultName));
			if(accept!=null&&kind!=PANEL_SAVE)chooser.setFileFilter(new FileFilter(){
				@Override public boolean accept(File f){return accept.test(f);}
				@Override public String getDescription(){return "Documents";}});
			javax.swing.JComboBox<String> formatBox=null;javax.swing.JCheckBox[] checkBoxes=new javax.swing.JCheckBox[extra?extras.checks.size():0];
			if(extra){
				javax.swing.JPanel box=new javax.swing.JPanel();box.setLayout(new javax.swing.BoxLayout(box,javax.swing.BoxLayout.Y_AXIS));
				box.setBorder(javax.swing.BorderFactory.createEmptyBorder(4,12,4,4));
				if(!extras.formats.isEmpty()){
					javax.swing.JLabel label=new javax.swing.JLabel(extras.formatLabel);label.setAlignmentX(0f);box.add(label);
					formatBox=new javax.swing.JComboBox<String>(extras.formats.toArray(new String[0]));formatBox.setSelectedIndex(Math.max(0,Math.min(extras.formats.size()-1,extras.format)));
					formatBox.setAlignmentX(0f);formatBox.setMaximumSize(formatBox.getPreferredSize());box.add(formatBox);box.add(javax.swing.Box.createVerticalStrut(8));
				}
				for(int i=0;i<checkBoxes.length;i++){checkBoxes[i]=new javax.swing.JCheckBox(extras.checks.get(i),extras.checked.get(i));checkBoxes[i].setEnabled(extras.enabled.get(i));checkBoxes[i].setAlignmentX(0f);box.add(checkBoxes[i]);}
				chooser.setAccessory(box);
			}
			int result=kind==PANEL_SAVE?chooser.showSaveDialog(tb.host().anyWindow()):chooser.showDialog(tb.host().anyWindow(),button==null||button.isEmpty()?(kind==PANEL_OPEN?"Open":"Choose"):button);
			List<File> files=new ArrayList<File>();
			if(result==JFileChooser.APPROVE_OPTION){if(chooser.isMultiSelectionEnabled())files.addAll(Arrays.asList(chooser.getSelectedFiles()));else if(chooser.getSelectedFile()!=null)files.add(chooser.getSelectedFile());
				if(extra){if(formatBox!=null)extras.format=formatBox.getSelectedIndex();for(int i=0;i<checkBoxes.length;i++)extras.checked.set(i,checkBoxes[i].isSelected());}}
			return files;}});
		return answer==null?none:answer;
	}
	/** Files the user chose in a Navigation Services dialog, as {@link #writeReply} treats Standard File's: the next panel opens
	 *  where this one was left, the Sandbox trusts their folders, and opened documents are listed under the program. */
	void chosen(List<File> files,boolean saving){
		if(files==null||files.isEmpty())return;
		File first=files.get(0).getAbsoluteFile();File folder=first.getParentFile();
		if(folder!=null&&folder.isDirectory())lastDirectory=folder;
		for(File f:files){
			Sandbox.chosenByUser(tb.name,f,saving);
			if(!saving&&f.isFile()&&tb.app!=null)tb.app.recordDocument(f);
		}
	}

	// ------------------------------------------------------------------ classic dialog run by the emulated Dialog Manager

	/** State of one guest Standard File dialog. */
	private final class Session {
		final Request r;int window;DialogManager68k.Dialog dialog;int list;File directory;
		/** The folder the list showed last (see {@link #rebuild}). */
		File listed;
		final List<File> entries=new ArrayList<File>();
		/** Where the folder pop-up is drawn: the pop-up item (System 7 layouts) or, as the HFS Standard File Package does for the
		 *  old getDlgID/putDlgID layouts, the strip just above the file list (some DITLs leave exactly that gap). */
		int[] popupRect;
		/** Type-to-select state: the letters typed so far and when the last one arrived. */
		String typed="";long typedAt;
		/** Wide layout: the sortable column header above the list (null in the classic layout or when the list is too narrow for columns). */
		int[] headerRect,listView;
		/** The preview pane in the button column's gap (null: no room), placed by {@link #stretch}. */
		int[] previewRect;
		/** Where the button column starts when it is not beside the list: the old put layout's dividing line, set by {@link #makeRoom} (-1: the list's right edge). */
		int columnEdge=-1;
		/** The preview pane's cache: the file whose image or text was last loaded. */
		File previewFile;java.awt.image.BufferedImage previewImage;String[] previewText;
		final int openItem,cancelItem,diskItem,ejectItem,driveItem,desktopItem,listItem,popupItem,nameItem,promptItem,newFolderItem;int scrollItem;
		Session(Request r){this.r=r;boolean put=r.put;openItem=1;diskItem=4;ejectItem=5;
			if(r.newStyle){cancelItem=SF_CANCEL;driveItem=0;desktopItem=SF_DESKTOP;listItem=SF_LIST;scrollItem=0;popupItem=SF_POPUP;nameItem=put?SF_NAME:0;promptItem=put?SF_PROMPT:0;newFolderItem=put?SF_NEW_FOLDER:0;}
			else{cancelItem=put?PUT_CANCEL:GET_CANCEL;driveItem=6;desktopItem=0;listItem=put?PUT_LIST:GET_LIST;scrollItem=put?PUT_SCROLL:GET_SCROLL;popupItem=0;nameItem=put?PUT_NAME:0;promptItem=put?PUT_PROMPT:GET_PROMPT;newFolderItem=0;}}
	}

	private void guestDialog(Request r) throws M68kException {
		if(Mac68kApi.TRACE)System.err.println("[sf] guest dialog put="+r.put+" dlgId="+r.dlgId+" filter="+Integer.toHexString(r.filterProc)+" hook="+Integer.toHexString(r.dlgHook)+" fileFilter="+Integer.toHexString(r.fileFilter)+" numTypes="+r.numTypes+" name='"+r.defaultName+"' prompt='"+r.prompt+"'");
		Session s=new Session(r);
		// the caller's port, taken before the dialog is made: making it (OpenPort) leaves the new port current, and restoring that
		// one left the program drawing into the disposed dialog - MPW's Commando lost its command line after "Files to count..."
		final int callerPort=tb.api.currentQuickDrawPort();
		s.directory=lastDirectory!=null&&lastDirectory.isDirectory()?lastDirectory:tb.programDirectory();
		// programs may point SFSaveDisk/CurDirStore at the boot volume's root, which is writable on a Mac but not here
		File stored=storedDirectory();if(stored!=null&&(!r.put||stored.canWrite()))s.directory=stored;
		int dlgId=r.dlgId;
		// SFPutFile/SFGetFile name no dialog: the package reads DLOG/DITL putDlgID (-3999) / getDlgID (-4000) from the resource chain,
		// so a program's own copy of them wins (MacWrite 2.2 adds its "Entire Document / Text Only" radio buttons to -3999). Its
		// items are laid out for the 1980s dialog, so it keeps its own geometry instead of the roomier built-in layout.
		boolean ownDialog=false;
		if(dlgId==0&&!r.newStyle&&tb.app!=null){int implicit=r.put?PUT_DLG_ID:GET_DLG_ID;
			if(tb.app.resources.get(ResourceFile68k.fourcc("DITL"),implicit)!=null&&tb.app.resources.get(ResourceFile68k.fourcc("DLOG"),implicit)!=null){dlgId=implicit;ownDialog=true;}}
		final Session ls=s;
		final boolean wide=wide()&&!ownDialog;
		DialogManager68k.Layout layout=new DialogManager68k.Layout(){@Override public byte[] rewrite(byte[] ditl,int[] bounds){byte[] roomy=makeRoom(ls,ditl,bounds);return wide?stretch(ls,roomy,bounds):roomy;}};
		s.window=dlgId!=0?tb.dialogs.fromResource(dlgId,0,-1,true,layout):0;
		if(s.window==0)s.window=tb.dialogs.fromItems(new int[]{0,0,r.put?PUT_HEIGHT:GET_HEIGHT,DIALOG_WIDTH},1,standardItems(r.put,r.newStyle),layout);
		if(s.window==0){setPort(callerPort);writeReply(r.reply,r.newStyle,null,r.put);return;}
		s.dialog=tb.dialogs.dialogs.get(s.window);
		s.dialog.defaultItem=s.openItem;s.dialog.ringDefault=true;s.dialog.cancelItem=s.cancelItem;
		// unattended (an expander's installer): Open needs a selection, so a folder chooser ends with the button the program
		// added for it (Compact Pro's "Extract"), on the folder the dialog opened in - the program's own
		if(unattended()&&!r.put)s.dialog.unattendedItem=tb.dialogs.addedButton(s.dialog,new java.util.HashSet<Integer>(Arrays.asList(s.openItem,s.cancelItem,s.ejectItem,s.driveItem,s.desktopItem,s.newFolderItem,2)));
		memory.writeLong(s.window+152,0x73746466); // refCon 'stdf': hooks check it before touching the dialog, including folder choosers
		final Session hs=s;
		// New-style hooks see sfHookNullEvent on every idle pass and the reply record mirrors the current selection meanwhile (folder
		// choosers read reply->sfFile/sfIsFolder there to name their "Select" button); a hook answer other than the null code is an item.
		// Old-style get hooks get the null item too: THINK C 5's Add dialog (SFPGetFile) retitles its Add/Open button from the
		// SFReply on item 100. Old put hooks (HyperCard's New Stack) keep seeing only real items.
		if(r.dlgHook!=0&&(r.newStyle||!r.put))s.dialog.nullEvents=new DialogManager68k.NullEvents(){@Override public int idle() throws M68kException {int h=callHook(hs,HOOK_NULL);return h==HOOK_NULL||h<=0?0:h;}};
		DialogManager68k.Item listItem=tb.dialogs.item(s.window,s.listItem);
		int[] view=listItem!=null?listItem.rect.clone():new int[]{12,12,125,230};
		DialogManager68k.Item scrollItem=s.scrollItem>0?tb.dialogs.item(s.window,s.scrollItem):null;
		// the old layouts' scroll-bar slot is only a scroll bar when the program's DITL keeps it a user item (HyperCard's
		// New Stack dialog has its "copy current background" check box at item 9, which is the program's to draw and track)
		if(scrollItem!=null&&(scrollItem.type&0x7f)!=0){scrollItem=null;s.scrollItem=0;}
		int listRight=view[3]; // the slot's right edge, scroll bar included
		if(scrollItem!=null&&scrollItem.rect[1]>=view[3]-2&&scrollItem.rect[1]<=view[3]+2){view[3]=scrollItem.rect[1]-1;listRight=scrollItem.rect[3];}
		else{view[3]-=16;
			// a user item away from the list is not its scroll bar but the program's own: Claris XTND's Save dialog (DITL 24001) has
			// its file-format pop-up there, which stayed invisible behind an empty drawing
			if(scrollItem!=null){scrollItem=null;s.scrollItem=0;}}
		view[0]+=1;view[1]+=1;view[2]-=1;
		// the wide layout's column header takes the top of the list's slot; the list starts under it
		if(wide&&view[3]-view[1]>=MIN_DETAIL_WIDTH&&view[2]-view[0]>=HEADER_HEIGHT+3*16){
			s.headerRect=new int[]{view[0]-1,view[1]-1,view[0]-1+HEADER_HEIGHT,listRight};view[0]+=HEADER_HEIGHT;}
		s.listView=view;
		s.list=tb.listManager.newList(s.window,view,16);
		final Session fs=s;
		Runnable drawList=new Runnable(){@Override public void run(){try{tb.listManager.drawList(fs.list);}catch(M68kCpuException e){}drawHeader(fs);drawPreview(fs);}};
		s.dialog.nativeItems.put(s.listItem,drawList);
		if(scrollItem!=null)s.dialog.nativeItems.put(s.scrollItem,new Runnable(){@Override public void run(){}});
		s.dialog.nativeItems.put(s.diskItem,new Runnable(){@Override public void run(){drawDiskName(fs);}});
		DialogManager68k.Item popupItem=s.popupItem>0?tb.dialogs.item(s.window,s.popupItem):null;
		if(popupItem!=null){s.popupRect=popupItem.rect.clone();s.dialog.nativeItems.put(s.popupItem,new Runnable(){@Override public void run(){drawFolderPopup(fs);}});}
		// the old dialogs have no item for it: the ROM package makes a pop-up control above the file list (MainHookAfterFirstHook),
		// drawn after the items - over item 10, the prompt it sets "for historical reasons" (Word 5.1's Open: "Select a Document:");
		// that item reaches into the volume name's corner too (Word's DITL 256: 8..350 against 307..445), so the name comes again
		else if(listItem!=null&&listItem.rect[3]-listItem.rect[1]>=MIN_LIST_WIDTH){int[] lr=listItem.rect;s.popupRect=new int[]{lr[0]-POPUP_HEIGHT-1,lr[1],lr[0]-1,lr[3]};s.dialog.afterItems=new Runnable(){@Override public void run(){drawFolderPopup(fs);drawDiskName(fs);}};}
		s.dialog.nativeEvents=new DialogManager68k.NativeEvents(){@Override public int unhandled(EventManager68k.Event e,int h,int v) throws M68kException {return nativeEvent(fs,e,h,v);}};
		// the prompt goes into the layout's static-text slot of custom dialogs too (SFPPutFile's item 3: "New stack name:")
		DialogManager68k.Item promptItem=s.promptItem>0?tb.dialogs.item(s.window,s.promptItem):null;
		if(promptItem!=null&&!r.prompt.isEmpty()&&(r.dlgId==0||(promptItem.type&0x7f)==8))tb.dialogs.setText(s.window,s.promptItem,tb.encoding.encodeReplacing(r.prompt));
		// the default name comes up selected, as in the ROM package: typing replaces it
		if(s.nameItem>0){tb.dialogs.setText(s.window,s.nameItem,tb.encoding.encodeReplacing(unattended()?FileManager68k.displayName(unusedName(s.directory,r.defaultName).getName()):r.defaultName));tb.dialogs.selectText(s.window,s.nameItem,0,32767);}
		// The package works in the dialog's port (SFGetFile does a SetPort of its own): the list's mouse tracking and the scroll
		// bar's TrackControl read the mouse in the current port's coordinates, and the program's hook may restore whatever port
		// it found (HyperCard's does), so every pass through the loop sets it again.
		int savedPort=callerPort;setPort(s.window);
		storeDirectory(s);
		File chosen=null;boolean good=false;
		try{
			// sfHookFirstCall goes to old-style hooks too (the ROM package does; HyperCard's SFPPutFile hook installs its user-item
			// procedure and sets up its card-size pop-up on its first call, whatever the item). The list is filled afterwards:
			// the hook may point SFSaveDisk/CurDirStore somewhere else.
			int itemsBefore=s.dialog.list.size();
			if(r.dlgHook!=0){callHook(s,HOOK_FIRST_CALL);tb.dialogs.syncItems(s.window);} // the hook may have appended records to the item list itself (FilePath: HandAndHand)
			if(s.dialog.list.size()>itemsBefore)placeAppendedItems(s,itemsBefore);
			File redirected=storedDirectory();if(redirected!=null&&!redirected.equals(s.directory)&&(!r.put||redirected.canWrite()))s.directory=redirected;
			rebuild(s);
			tb.windows.show(s.window,true);
			while(true){
				setPort(s.window);
				setUpSaveButton(s);
				int hit=tb.dialogs.runModal(s.dialog,r.filterProc,r.newStyle,r.yourData);
				EventManager68k.Event e=tb.dialogs.lastEvent;
				if(Mac68kApi.TRACE)System.err.println("[sf] hit "+hit);
				// navigation the Standard File Package handles by itself (pop-up, Cmd-arrows): the program's hook never sees it
				if(hit==NATIVE_POPUP){folderPopup(s);continue;}
				if(hit==NATIVE_PARENT){File p=parent(s.directory);if(p!=null)enter(s,p);continue;}
				if(hit==NATIVE_OPEN){int row=tb.listManager.selectedRow(s.list);if(row>=0&&row<s.entries.size()&&s.entries.get(row).isDirectory())enter(s,s.entries.get(row));continue;}
				if(hit==NATIVE_DESKTOP){File desk=tb.files.specialFolder(0x6465736b,false);if(desk!=null)enter(s,desk);continue;}
				if(hit==NATIVE_SORT){rebuild(s);continue;}
				int original=hit;
				if(r.dlgHook!=0&&r.newStyle)mirrorSelection(s); // the hook (and, after a Cancel, the program) finds the dialog's current folder or selection in the reply
				if(r.dlgHook!=0){hit=callHook(s,hit);if(Mac68kApi.TRACE)System.err.println("[sf] hook -> "+hit);}
				if(hit==0)continue; // a filter ended ModalDialog without naming an item (scrollItem is 0 in dialogs without one)
				if(hit==s.openItem&&original!=s.openItem&&original!=s.listItem&&r.newStyle&&!r.put){
					// The hook turned one of its own items into Open (for example, a "Select folder" button): it has put the answer into
					// reply->sfFile, so the dialog ends with that instead of opening the folder selected in the list.
					File named=null;try{named=tb.files.fileFor((short)memory.readWord(r.reply+6),memory.readLong(r.reply+8),tb.readPascal(r.reply+12));}catch(IOException ex){}
					if(named!=null){chosen=named;good=true;break;}
				}
				if(hit==s.openItem&&original!=s.openItem&&original!=s.listItem&&!r.newStyle&&!r.put){
					// The same with an old-style hook (SFPGetFile runs on System 7's package, where opening a folder reaches the hook as
					// sfHookOpenFolder): Open returned for one of its own buttons answers the selection as it is, a folder too. StuffIt
					// InstallerMaker's "Install into" button does so and then checks reply.good; the folder was opened instead.
					int row=tb.listManager.selectedRow(s.list);chosen=row>=0&&row<s.entries.size()?s.entries.get(row):s.directory;good=true;break;
				}
				if(hit==s.openItem){
					int row=tb.listManager.selectedRow(s.list);File selected=row>=0&&row<s.entries.size()?s.entries.get(row):null;
					if(r.put){
						String name=tb.decode(tb.dialogs.text(s.window,s.nameItem)).trim();
						if(name.isEmpty()){if(selected!=null&&selected.isDirectory()){enter(s,selected);}continue;}
						chosen=typedFile(s.directory,name);good=true;break;
					}
					if(selected!=null&&selected.isDirectory()){enter(s,selected);continue;}
					// no selection and the user's own Open/Return: the real package dims Open then and Return does nothing
					// (StandardFile v2's "get" on an empty list answered the folder as the chosen file)
					if(selected==null&&original==s.openItem)continue;
					// no selection: a dialog hook forced the exit (folder choosers): answer with the current folder
					chosen=selected;good=true;break;
				}
				if(hit==s.cancelItem){good=false;break;}
				if(hit==s.listItem||hit==s.scrollItem){
					int[] m=e!=null&&e.what==EventManager68k.MOUSE_DOWN?tb.windows.globalToLocal(s.window,e.whereH,e.whereV):tb.events.localMouse();
					if(Mac68kApi.TRACE)System.err.println("[sf] list click at "+m[0]+","+m[1]+" hits="+tb.listManager.hitsList(s.list,m[0],m[1]));
					if(headerClick(s,m[0],m[1])){rebuild(s);continue;} // the column header lies inside the list item's rectangle
					if(!tb.listManager.hitsList(s.list,m[0],m[1]))continue;
					boolean dbl=tb.listManager.clickList(s.list,m[0],m[1],e==null?0:e.modifiers);
					if(Mac68kApi.TRACE)System.err.println("[sf] list click -> row "+tb.listManager.selectedRow(s.list)+(dbl?" (double)":""));
					int row=tb.listManager.selectedRow(s.list);File selected=row>=0&&row<s.entries.size()?s.entries.get(row):null;
					mirrorSelection(s);drawPreview(s);
					if(r.put&&selected!=null&&!selected.isDirectory()&&s.nameItem>0)tb.dialogs.setText(s.window,s.nameItem,tb.encoding.encodeReplacing(displayName(selected)));
					if(dbl&&selected!=null){
						if(selected.isDirectory()){enter(s,selected);continue;}
						if(r.put)continue;
						// a double-clicked file is a click on Open, which the hook sees first: THINK C 5's Add dialog turns it into
						// "add to the project" and keeps the dialog up
						int answer=r.dlgHook!=0?callHook(s,s.openItem):s.openItem;
						if(Mac68kApi.TRACE&&r.dlgHook!=0)System.err.println("[sf] double click: hook -> "+answer);
						if(answer==s.openItem){chosen=selected;good=true;break;}
						if(answer==s.cancelItem){good=false;break;}
					}
					continue;
				}
				if(hit==s.driveItem||hit==HOOK_NEXT_DRIVE){nextVolume(s,1);continue;}
				if(s.desktopItem>0&&hit==s.desktopItem){File desk=tb.files.specialFolder(0x6465736b,false);if(desk!=null)enter(s,desk);continue;}
				if(s.popupItem>0&&hit==s.popupItem){folderPopup(s);continue;}
				if(s.newFolderItem>0&&hit==s.newFolderItem){newFolder(s);continue;}
				if(hit==HOOK_PREV_DRIVE){nextVolume(s,-1);continue;}
				if(hit==s.ejectItem||hit==HOOK_NULL||hit==HOOK_FOLDER_POPUP||hit==HOOK_CHANGE_SELECTION||hit==HOOK_SET_ACTIVE||hit==0)continue;
				if(hit==HOOK_REBUILD){File d=storedDirectory();if(d!=null)s.directory=d;rebuild(s);continue;}
				if(hit==HOOK_OPEN_FOLDER||hit==HOOK_OPEN_ALIAS){int row=tb.listManager.selectedRow(s.list);if(row>=0&&row<s.entries.size()&&s.entries.get(row).isDirectory())enter(s,s.entries.get(row));continue;}
				if(hit==HOOK_GO_PARENT){File p=parent(s.directory);if(p!=null)enter(s,p);continue;}
				if(hit==HOOK_GO_DESKTOP){File desk=tb.files.specialFolder(0x6465736b,false);if(desk!=null)enter(s,desk);continue;}
				if(hit>0&&hit<=s.dialog.list.size()){
					DialogManager68k.Item item=tb.dialogs.item(s.window,hit);
					// custom edit-text/check-box items belong to the program; a hook usually maps its own buttons above
					if(item!=null&&(item.type&0x7f)==16)continue;
				}
			}
			if(r.dlgHook!=0&&r.newStyle)callHook(s,HOOK_LAST_CALL);
		}finally{
			tb.listManager.disposeList(s.list);
			tb.dialogs.dispose(s.window);
			setPort(savedPort);
		}
		lastDirectory=s.directory;
		if(good&&chosen==null)chosen=s.directory;
		if(good)addRecentFolder(chosen!=null&&chosen.isDirectory()&&!s.r.put?chosen:s.directory);
		writeReplyIn(r,s,chosen,good);
	}

	/**
	 * The package's SetUpButtons before every event (ROM StandardFilePACK.a mainLoop -> SetUpOpenButton): a put dialog's Save
	 * button is dimmed while the typed name is empty or the folder cannot be written, enabled otherwise (a selected folder makes
	 * it Open). Word 5.1's hook dims it on sfHookFirstCall when it passes no default name and leaves enabling it to the package.
	 */
	private void setUpSaveButton(Session s) throws M68kException {
		if(s.openItem<=0||s.r.put&&s.nameItem<=0||!s.r.put&&s.list==0)return;
		DialogManager68k.Item item=tb.dialogs.item(s.window,s.openItem);int kind=item==null?0:item.type&0x7f;
		if(item==null||item.handle==0||kind<4||kind>7)return;
		int d=tb.controls.data(item.handle);if(d==0)return;
		int row=s.list==0?-1:tb.listManager.selectedRow(s.list);File selected=row>=0&&row<s.entries.size()?s.entries.get(row):null;
		boolean enable;
		if(s.r.put){byte[] typed=tb.dialogs.text(s.window,s.nameItem);
			enable=selected!=null&&selected.isDirectory()||typed!=null&&typed.length>0&&s.directory!=null&&s.directory.canWrite();}
		else enable=selected!=null; // a get dialog's Open: dimmed while nothing is selected (an empty folder), as SetUpOpenButton does
		int hilite=enable?0:255,old=memory.readByte(d+17);
		if(old!=hilite&&(old==0||old==255)){memory.writeByte(d+17,hilite);tb.controls.draw(item.handle);}
	}

	private void setPort(int port) throws M68kCpuException {if(port==0)return;M68kCpu cpu=tb.cpu;int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}

	/** Old-style dialog hook: pascal short hook(short item, DialogPtr); new style adds yourDataPtr. */
	private int callHook(Session s,int item) throws M68kException {
		// An old-style put dialog's SFReply.fName is the name being typed: PageMaker 6's installer checks the folder it is about to
		// create from reply.fName when its own Install button (item 13) comes to the hook.
		if(s.r.put&&!s.r.newStyle&&s.r.reply!=0&&s.nameItem>0&&s.dialog!=null){byte[] typed=tb.dialogs.text(s.window,s.nameItem);
			if(typed!=null){int n=Math.min(typed.length,63);memory.writeByte(s.r.reply+10,n);for(int i=0;i<n;i++)memory.writeByte(s.r.reply+11+i,typed[i]);}}
		// ...and a StandardFileReply's sfFile.name is the typed name: FreeHand 5's installer creates the folder named there inside the
		// folder (vRefNum, parID) once its Install button has become Cancel.
		if(s.r.put&&s.r.newStyle&&s.r.reply!=0&&s.nameItem>0&&s.dialog!=null){byte[] typed=tb.dialogs.text(s.window,s.nameItem);
			if(typed!=null){int n=Math.min(typed.length,63);memory.writeByte(s.r.reply+12,n);for(int i=0;i<n;i++)memory.writeByte(s.r.reply+13+i,typed[i]);}}
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		cpu.a[7]-=2;cpu.pushWord(item);cpu.pushLong(s.window);if(s.r.newStyle)cpu.pushLong(s.r.yourData);
		try{cpu.callPascal(s.r.dlgHook,s.r.newStyle?10:6);return (short)memory.readWord(cpu.a[7]);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	/** pascal Boolean fileFilter(CInfoPBPtr[, void* yourData]): true excludes the file. The block is the one HGetFileInfo fills,
	 *  with the Mac name (a MacBinary container answers to its header name, so extension-based filters see ".sit", not ".bin"). */
	private boolean excludedByFilter(Session s,File file) throws M68kException {
		if(s.r.fileFilter==0)return false;
		int pb=memory.allocate(128,4),name=memory.allocate(64,2);
		try{
			memory.fill(pb,128,0);tb.writePascal(name,displayName(file),63);
			memory.writeLong(pb+18,name);memory.writeWord(pb+22,tb.files.volumeRefNumFor(file));memory.writeLong(pb+48,tb.files.directoryIdFor(file.getParentFile()));
			if(tb.files.osTrap(tb.cpu,pb,0xa20c)!=0)return false; // HGetFileInfo
			M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
			cpu.a[7]-=2;cpu.pushLong(pb);if(s.r.newStyle)cpu.pushLong(s.r.yourData);
			try{cpu.callPascal(s.r.fileFilter,s.r.newStyle?8:4);boolean excluded=memory.readByte(cpu.a[7])!=0;
				if(Mac68kApi.TRACE)System.err.println("[sf] fileFilter "+displayName(file)+" type "+ResourceFile68k.typeName(memory.readLong(pb+32))+" -> "+(excluded?"hidden":"shown"));
				return excluded;}
			finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		}catch(IOException e){return false;}
		finally{memory.free(pb);memory.free(name);}
	}

	private void enter(Session s,File directory) throws M68kException {s.directory=directory;rebuild(s);}

	/** Mouse-downs outside the dialog's items and key-downs, before the Dialog Manager's own handling. */
	private int nativeEvent(Session s,EventManager68k.Event e,int h,int v) throws M68kException {
		if(e.what==EventManager68k.MOUSE_DOWN){
			int[] r=s.popupRect;
			if(r!=null&&v>=r[0]&&v<r[2]&&h>=r[1]&&h<r[3])return NATIVE_POPUP;
			if(headerClick(s,h,v))return NATIVE_SORT;
			// the list and its scroll bar come first: in the classic layouts the disk name's rectangle overlaps the scroll bar's
			// up arrow (HyperCard's Open dialog), and FindDialogItem answers with the lower-numbered disk item there
			if(tb.listManager.hitsList(s.list,h,v))return s.listItem;
			// the disk name (top right): a click goes up one folder, as the Mac OS 8 Finder's dialogs did
			DialogManager68k.Item disk=tb.dialogs.item(s.window,s.diskItem);
			if(disk!=null&&v>=disk.rect[0]&&v<disk.rect[2]&&h>=disk.rect[1]&&h<disk.rect[3])return parent(s.directory)!=null?NATIVE_PARENT:0;
			return 0;
		}
		if(e.what!=EventManager68k.KEY_DOWN&&e.what!=EventManager68k.AUTO_KEY)return 0;
		int ch=e.message&0xff;boolean command=(e.modifiers&0x100)!=0,shift=(e.modifiers&0x200)!=0;
		if(command){
			if(ch==0x1e)return shift?NATIVE_DESKTOP:NATIVE_PARENT; // Cmd-Up: enclosing folder; Cmd-Shift-Up: desktop
			if(ch==0x1f)return NATIVE_OPEN;                        // Cmd-Down: open the selected folder
			if(ch=='d'||ch=='D')return NATIVE_DESKTOP;
			return 0;
		}
		int rows=tb.listManager.rowCount(s.list);
		if(ch==0x1e||ch==0x1f){ // arrows move the selection
			if(rows==0)return 0;
			int row=tb.listManager.selectedRow(s.list),next=row;
			if(ch==0x1e){for(int i=(row<0?rows-1:row-1);i>=0;i--)if(selectable(s,i)){next=i;break;}}
			else{for(int i=(row<0?0:row+1);i<rows;i++)if(selectable(s,i)){next=i;break;}}
			if(next>=0&&next!=row)select(s,next);return 0;
		}
		if(s.r.put||ch<0x20)return 0; // save dialogs: letters belong to the name field
		long now=System.currentTimeMillis();
		s.typed=(now-s.typedAt<1500?s.typed:"")+Character.toLowerCase((char)ch);s.typedAt=now;
		for(int i=0;i<s.entries.size();i++){
			File f=s.entries.get(i);String name=displayName(f).toLowerCase();
			if(selectable(s,i)&&name.startsWith(s.typed)){select(s,i);break;}
		}
		return 0;
	}
	/** Save dialogs list files only for orientation: they cannot be chosen. */
	private boolean selectable(Session s,int row){return row>=0&&row<s.entries.size()&&(!s.r.put||s.entries.get(row).isDirectory());}
	private void select(Session s,int row) throws M68kException {
		tb.listManager.selectRow(s.list,row);
		File f=row>=0&&row<s.entries.size()?s.entries.get(row):null;
		mirrorSelection(s);drawPreview(s);
		if(s.r.put&&f!=null&&!f.isDirectory()&&s.nameItem>0)tb.dialogs.setText(s.window,s.nameItem,tb.encoding.encodeReplacing(displayName(f)));
	}

	/** The folder pop-up: a menu of the current folder and its ancestors up to the volume (plus the desktop, as in System 7);
	 *  in the wide layout it goes on with favourite folders (home, Documents, Downloads, the program's folder, the volumes)
	 *  and the folders chosen most recently. Choosing one shows it. Headless runs (no menus) go up one level. */
	private void folderPopup(Session s) throws M68kException {
		List<File> path=new ArrayList<File>();
		for(File d=s.directory;d!=null;d=parent(d))path.add(d);
		File desk=tb.files.specialFolder(0x6465736b,false);
		MacAppHost.MenuModel model=new MacAppHost.MenuModel(0,"",true);List<File> targets=new ArrayList<File>();
		for(File d:path){MacAppHost.ItemModel item=new MacAppHost.ItemModel();item.text=folderLabel(d);model.items.add(item);targets.add(d);}
		if(desk!=null&&!path.contains(desk)){separator(model,targets);popupFolder(model,targets,desk,"Desktop");}
		if(wide()){
			List<File> seen=new ArrayList<File>();for(File d:targets)if(d!=null)seen.add(canonical(d));
			int before=model.items.size();
			File home=new File(System.getProperty("user.home",""));
			popupFolder(model,targets,seen,home,home.getName().isEmpty()?"Home":home.getName());
			popupFolder(model,targets,seen,new File(home,"Documents"),"Documents");
			popupFolder(model,targets,seen,new File(home,"Downloads"),"Downloads");
			File appFolder=tb.programDirectory();
			if(appFolder!=null)popupFolder(model,targets,seen,appFolder,folderLabel(appFolder));
			for(File volume:tb.files.fileManager.volumes)popupFolder(model,targets,seen,volume,volumeName(volume));
			if(model.items.size()>before){model.items.add(before,separatorItem());targets.add(before,null);}
			before=model.items.size();
			for(String recent:new ArrayList<String>(recentFolders)){File d=new File(recent);popupFolder(model,targets,seen,d,folderLabel(d));}
			if(model.items.size()>before){model.items.add(before,separatorItem());targets.add(before,null);}
		}
		int chosen;
		if(tb.host().headless){chosen=path.size()>1?2:0;}
		else{
			WindowManager68k.Window w=tb.windows.window(s.window);int[] r=s.popupRect;
			int left=r==null?0:r[1],top=r==null?0:r[2];
			chosen=tb.host().popUpMenuSelect(model,left+(w==null?0:w.left),top+(w==null?0:w.top),1);
		}
		if(Mac68kApi.TRACE)System.err.println("[sf] folder pop-up "+path+" -> "+chosen);
		if(chosen<=0||chosen>targets.size())return;
		File target=targets.get(chosen-1);
		if(target!=null&&target.isDirectory()&&!canonical(target).equals(canonical(s.directory)))enter(s,target);
	}
	private static MacAppHost.ItemModel separatorItem(){MacAppHost.ItemModel sep=new MacAppHost.ItemModel();sep.separator=true;return sep;}
	private static void separator(MacAppHost.MenuModel model,List<File> targets){model.items.add(separatorItem());targets.add(null);}
	private static void popupFolder(MacAppHost.MenuModel model,List<File> targets,File folder,String label){MacAppHost.ItemModel item=new MacAppHost.ItemModel();item.text=label;model.items.add(item);targets.add(folder);}
	/** Adds a favourite or recent folder once, and only when the File Manager can reach it (it lies on a volume). */
	private void popupFolder(MacAppHost.MenuModel model,List<File> targets,List<File> seen,File folder,String label){
		if(folder==null||!folder.isDirectory()||!tb.files.fileManager.onVolume(folder))return;
		File c=canonical(folder);if(seen.contains(c))return;seen.add(c);
		popupFolder(model,targets,folder,label);
	}
	private static File canonical(File f){try{return f.getCanonicalFile();}catch(IOException e){return f.getAbsoluteFile();}}
	/** What a folder is called in the pop-up: its name, or the volume name at a volume's root. */
	private String folderLabel(File d){String name=d.getName();return name.isEmpty()||tb.files.fileManager.volumeRoot(d)?volumeName(d):tb.files.fileManager.programName(name);}
	/** The folder above, or null at the volume's root - also when another volume (the host disk's "Macintosh HD") holds the
	 *  folder the root stands for: Resorcerer's path pop-up went on from "HyperZebra HD" up through the host's folders. */
	private File parent(File directory){try{FileManager68k fm=tb.files.fileManager;if(!fm.onVolume(directory)||fm.volumeRoot(directory))return null;File p=directory.getCanonicalFile().getParentFile();return p!=null&&fm.onVolume(p)?p:null;}catch(IOException e){return null;}}
	private void nextVolume(Session s,int step) throws M68kException {
		List<File> volumes=tb.files.fileManager.volumes;if(volumes.isEmpty())return;
		int index=0;try{int ref=tb.files.volumeRefNumFor(s.directory);if(ref<0)index=-ref-1;}catch(IOException e){}
		index=((index+step)%volumes.size()+volumes.size())%volumes.size();
		enter(s,volumes.get(index));
	}

	/** Reads the directory the program stored in SFSaveDisk/CurDirStore (after a hook returned sfHookRebuildList). */
	private File storedDirectory(){
		try{int vRef=-(short)memory.readWord(SF_SAVE_DISK),dirId=memory.readLong(CUR_DIR_STORE);if(Mac68kApi.TRACE)System.err.println("[sf] SFSaveDisk/CurDirStore -> vRefNum "+vRef+" dirID "+dirId);if(vRef==0&&dirId==0)return null;
			File d=tb.files.fileManager.directoryForId(dirId==0?2:dirId,vRef);return d!=null&&d.isDirectory()?d:null;}
		catch(Exception e){return null;}
	}
	private void storeDirectory(Session s){
		try{memory.writeWord(SF_SAVE_DISK,-tb.files.volumeRefNumFor(s.directory));memory.writeLong(CUR_DIR_STORE,tb.files.directoryIdFor(s.directory));}catch(Exception e){}
	}

	/** Lists the current folder into the file list: folders first ("ƒ"), then files passing the type list and filter. */
	/**
	 * Keeps a new-style reply record showing the dialog's current state, as the System 7 package does while its dialog runs:
	 * the selected item's spec with sfIsFolder/sfIsVolume, or - with nothing selected - the current folder as (vRefNum, dirID, "")
	 * so a hook can PBGetCatInfo it. sfGood stays false until the dialog ends.
	 */
	private void mirrorSelection(Session s) throws M68kCpuException {
		int reply=s.r.reply;if(reply==0)return;
		int row=tb.listManager.selectedRow(s.list);File selected=row>=0&&row<s.entries.size()?s.entries.get(row):null;
		if(!s.r.newStyle){mirrorOldSelection(s,reply,selected);return;}
		try{
			if(selected!=null){
				memory.writeLong(reply+2,selected.isDirectory()?0:tb.files.fileManager.shownType(selected));
				tb.files.writeFileSpec(reply+6,selected);
				writeItemKind(reply,selected);
			}else{
				memory.writeLong(reply+2,0);
				memory.writeWord(reply+6,tb.files.volumeRefNumFor(s.directory));memory.writeLong(reply+8,tb.files.directoryIdFor(s.directory));memory.writeByte(reply+12,0);
				memory.writeByte(reply+80,0);memory.writeByte(reply+81,0);
			}
		}catch(IOException e){}
	}

	/**
	 * The old-style SFReply keeps up with the list the same way: a selected file's type and name (fName, with vRefNum the
	 * folder's working directory), or for a selected folder an empty fName with the folder's dirID in fType - the convention
	 * pre-System 7 folder choosers rely on. THINK C 5's Add dialog hook reads it on every call to turn its button into
	 * "Add" (a file) or "Open" (a folder); sfGood stays false until the dialog ends. Get dialogs only: in a put dialog
	 * fName is the name being typed.
	 */
	private void mirrorOldSelection(Session s,int reply,File selected) throws M68kCpuException {
		if(s.r.put)return;
		memory.writeWord(reply+6,tb.files.workingDirectoryFor(s.directory));memory.writeWord(reply+8,0);
		if(selected==null){memory.writeLong(reply+2,0);memory.writeByte(reply+10,0);return;}
		if(selected.isDirectory()){int id=0;try{id=tb.files.directoryIdFor(selected);}catch(Exception e){}memory.writeLong(reply+2,id);memory.writeByte(reply+10,0);return;}
		memory.writeLong(reply+2,tb.files.fileManager.shownType(selected));
		tb.writePascal(reply+10,displayName(selected),63);
	}

	private void rebuild(Session s) throws M68kException {
		storeDirectory(s);
		int was=s.list==0?-1:tb.listManager.selectedRow(s.list);File wasSelected=was>=0&&was<s.entries.size()?s.entries.get(was):null;
		s.entries.clear();
		File[] all=FileManager68k.catalogEntries(s.directory);if(all==null)all=new File[0]; // hidden files, packages and the host's system folders stay out, as in a folder walk
		Arrays.sort(all,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
		// folders first, then the files passing the type list and filter; each group in the chosen order
		List<File> folders=new ArrayList<File>(),files=new ArrayList<File>();List<Integer> fileIcons=new ArrayList<Integer>();
		for(File f:all){
			String n=f.getName();if(n.startsWith(".")||n.startsWith("._"))continue;
			if(f.isDirectory())folders.add(f);
		}
		for(File f:all){
			String n=f.getName();if(n.startsWith(".")||!f.isFile())continue;
			byte[] info=FinderInfo68k.read(f);int type=tb.files.fileManager.shownType(f);
			if(info!=null&&(info[8]&0x40)!=0)continue; // kIsInvisible: a folder's own "Icon\r" file and other hidden files stay out, as on a real Mac
			int icon=type==0x4150504c?ListManager68k.ICON_APPLICATION:ListManager68k.ICON_DOCUMENT;
			if(s.r.put){files.add(f);fileIcons.add(icon|ListManager68k.ICON_DIMMED);continue;} // save dialogs show files dimmed, for orientation
			if(s.r.numTypes==0)continue;
			if(s.r.types!=null&&s.r.types.length>0){boolean ok=type==0;for(int t:s.r.types)if(t==type)ok=true;if(!ok)continue;}
			if(excludedByFilter(s,f))continue;
			files.add(f);fileIcons.add(icon);
		}
		Comparator<File> order=sortOrder();
		folders.sort(order);
		Integer[] index=new Integer[files.size()];for(int i=0;i<index.length;i++)index[i]=i;
		final List<File> unsorted=files;Arrays.sort(index,(a,b)->order.compare(unsorted.get(a),unsorted.get(b)));
		List<byte[]> rows=new ArrayList<byte[]>();List<Integer> icons=new ArrayList<Integer>();List<java.awt.image.BufferedImage> images=new ArrayList<java.awt.image.BufferedImage>();List<String[]> details=new ArrayList<String[]>();
		// the desktop lists the mounted disks first, as System 7's does: choosing one answers sfIsVolume
		File desk=tb.files.specialFolder(0x6465736b,false);
		if(desk!=null&&canonical(desk).equals(canonical(s.directory)))for(File volume:tb.files.fileManager.volumes){if(!volume.isDirectory())continue;
			s.entries.add(volume);rows.add(tb.encoding.encodeReplacing(volumeName(volume)));icons.add(ListManager68k.ICON_DISK);images.add(null);details.add(detailsOf(volume));}
		for(File f:folders){s.entries.add(f);rows.add(tb.encoding.encodeReplacing(displayName(f)));icons.add(ListManager68k.ICON_FOLDER);images.add(FinderIcons68k.of(f));details.add(detailsOf(f));}
		for(int i:index){File f=files.get(i);s.entries.add(f);rows.add(tb.encoding.encodeReplacing(displayName(f)));icons.add(fileIcons.get(i));images.add(FinderIcons68k.of(f));details.add(detailsOf(f));}
		int[] iconArray=new int[icons.size()];for(int i=0;i<iconArray.length;i++)iconArray[i]=icons.get(i);
		boolean detail=s.headerRect!=null;
		tb.listManager.setRows(s.list,rows,iconArray,images.toArray(new java.awt.image.BufferedImage[0]),detail?details.toArray(new String[0][]):null,detail?new int[]{COLUMN_WIDTHS[detailColumn]}:null);
		// A get dialog always has an item selected while its list has any, as System 7's does: the first one, the one still
		// there after a re-sort, or the folder just left when going up. Hooks follow the selection through the reply: The
		// Writing Center's picture chooser turns its button into "Open" with nothing selected and back into "Place in Document"
		// only from a folder, so with no selection after entering a folder its pictures could not be placed.
		if(!s.r.put&&s.list!=0&&!s.entries.isEmpty()){
			int row=wasSelected==null?-1:s.entries.indexOf(wasSelected);
			for(int i=0;row<0&&s.listed!=null&&i<s.entries.size();i++)if(canonical(s.entries.get(i)).equals(canonical(s.listed)))row=i;
			tb.listManager.selectRow(s.list,Math.max(0,row));
		}
		s.listed=s.directory;
		drawHeader(s);drawPreview(s);drawDiskName(s);drawFolderPopup(s);
		mirrorSelection(s);
	}

	// ------------------------------------------------------------------ wide layout: stretched dialog, detail columns, sorting

	/**
	 * Stretches a Standard File DITL to the wide size: the list grows to fill the new room, items to its right (the button
	 * column, the disk name) move right, items below it (the name field and prompt of a Save dialog, a program's extra check
	 * box) move down, and items spanning its width (the folder pop-up) widen. The default and Cancel buttons at the foot of the
	 * button column follow the list's bottom. Hooks find the items by number and read their rectangles, so they keep working.
	 */
	private byte[] stretch(Session s,byte[] ditl,int[] bounds){
		try{
			int[] screen=tb.api.quickDraw().screenBounds();
			int w=bounds[3]-bounds[1],h=bounds[2]-bounds[0];
			int targetW=Math.min(WIDE_WIDTH,screen[3]-screen[1]-40),targetH=Math.min(s.r.put?WIDE_PUT_HEIGHT:WIDE_GET_HEIGHT,screen[2]-screen[0]-MacAppHost.MENU_BAR_HEIGHT-60);
			int dx=Math.max(0,targetW-w),dy=Math.max(0,targetH-h);
			if(dx==0&&dy==0)return ditl;
			List<Integer> offsets=itemOffsets(ditl);
			if(s.listItem<1||s.listItem>offsets.size())return ditl;
			int[] list=rect(ditl,offsets.get(s.listItem-1));
			// a list the program put outside its (shrunk) dialog is hidden on purpose: Apple's VolumePath makes a 109x179 volume
			// chooser that way, and stretching it showed the whole file list beside the disk name
			if(list[1]>=w||list[0]>=h||list[3]<=0||list[2]<=0)return ditl;
			int[] scroll=s.scrollItem>0&&s.scrollItem<=offsets.size()?rect(ditl,offsets.get(s.scrollItem-1)):null;
			boolean scrollBar=scroll!=null&&(ditl[offsets.get(s.scrollItem-1)+12]&0x7f)==0&&Math.abs(scroll[1]-list[3])<=2;
			int listRight=scrollBar?scroll[3]:list[3];
			int sidebar=0,listGrow=dx;
			// the foot of the button column - the default and Cancel buttons and whatever sits level with or below them - follows the list's bottom
			int foot=Integer.MAX_VALUE;
			for(int item:new int[]{s.openItem,s.cancelItem})if(item>=1&&item<=offsets.size())foot=Math.min(foot,rect(ditl,offsets.get(item-1))[0]);
			byte[] out=ditl.clone();
			// the button column's items (and whether each followed the list's bottom), for a preview in the column's gap when there is no room beside the list
			List<int[]> column=new ArrayList<int[]>();List<Boolean> columnDown=new ArrayList<Boolean>();
			for(int i=1;i<=offsets.size();i++){
				int at=offsets.get(i-1);int[] r=rect(ditl,at);
				if(r[0]>=1000)continue; // parked off the window
				if(i==s.listItem){r[1]+=sidebar;r[3]+=sidebar+listGrow;r[2]+=dy;}
				else if(scrollBar&&i==s.scrollItem){r[1]+=sidebar+listGrow;r[3]+=sidebar+listGrow;r[2]+=dy;}
				else{
					boolean rightColumn=r[1]>=(s.columnEdge>=0?s.columnEdge:listRight)-20;
					if(rightColumn){r[1]+=dx;r[3]+=dx;}
					// only items spanning the list's width (from its left part to its right edge) widen: Painter 1.0's "Type" label
					// (144-176 beside a list ending at 197) grew over its pop-up user item and took its clicks
					else{boolean spans=r[3]>=listRight-24&&r[1]<=list[1]+(listRight-list[1])/3;r[1]+=sidebar;r[3]+=sidebar;if(spans)r[3]+=listGrow;}
					boolean down=r[0]>=list[2]-4||(rightColumn&&r[0]>=foot-4);
					if(down){r[0]+=dy;r[2]+=dy;}
					else if(r[0]<list[0]&&r[2]>list[2]+4)r[2]+=dy;
					if(rightColumn&&r[2]>r[0]&&r[3]>r[1]){column.add(r.clone());columnDown.add(down);}
				}
				writeRect(out,at,r);
			}
			bounds[3]+=dx;bounds[2]+=dy;
			{
				// the preview goes into the column of the default and Cancel buttons: the gap between the items above it (in the same column) and them
				int colLeft=Integer.MAX_VALUE,colRight=0,lowerTop=Integer.MAX_VALUE,upperBottom=0;
				for(int i=0;i<column.size();i++)if(columnDown.get(i)){int[] r=column.get(i);colLeft=Math.min(colLeft,r[1]);colRight=Math.max(colRight,r[3]);lowerTop=Math.min(lowerTop,r[0]);}
				for(int i=0;i<column.size();i++)if(!columnDown.get(i)){int[] r=column.get(i);if(r[3]>colLeft-8&&r[1]<colRight+8)upperBottom=Math.max(upperBottom,r[2]);}
				// not over the list: the old put layout's column sits under it
				boolean besideList=colLeft-8>=listRight+sidebar+listGrow||upperBottom+12>=list[2]+dy;
				if(colRight>colLeft&&lowerTop-upperBottom>=MIN_PREVIEW_GAP&&besideList)s.previewRect=new int[]{upperBottom+12,Math.max(6,colLeft-8),lowerTop-12,Math.min(bounds[3]-bounds[1]-6,colRight+8)};
			}
			return out;
		}catch(RuntimeException e){return ditl;}
	}
	/**
	 * Old-style dialogs laid out for the 64K ROM's Standard File have no room for what the HFS package adds, and the package
	 * makes it as Executor's transformsfpdialog does: a get dialog whose list starts at the top (Font/DA Mover 4.1's DLOG 300,
	 * list at 14: the folder pop-up would sit above the window) gets the list and everything reaching below its top moved down
	 * 20 pixels; a put dialog without a file list (item 8 is the old layout's 1-pixel dividing line: Font/DA Mover's DITL 301,
	 * MacWrite 2.2's -3999) gets the list at (16,24)-(231,106) and every other item 110 pixels lower. The window grows to match.
	 */
	private byte[] makeRoom(Session s,byte[] ditl,int[] bounds){
		if(s.r.newStyle)return ditl;
		try{
			List<Integer> offsets=itemOffsets(ditl);
			if(s.listItem<1||s.listItem>offsets.size())return ditl;
			int[] list=rect(ditl,offsets.get(s.listItem-1));
			int shift;int[] newList=null;
			if(s.r.put&&list[3]-list[1]<=1){shift=110;newList=new int[]{24,16,106,231};s.columnEdge=list[1];}
			else if(!s.r.put&&list[0]<POPUP_HEIGHT+5)shift=20;
			else return ditl;
			byte[] out=ditl.clone();
			for(int i=1;i<=offsets.size();i++){
				int at=offsets.get(i-1);int[] r=rect(out,at);
				if(i==s.listItem&&newList!=null)r=newList;
				else if(newList!=null||r[2]>list[0]){r[0]+=shift;r[2]+=shift;}
				writeRect(out,at,r);
			}
			bounds[2]+=shift;
			return out;
		}catch(RuntimeException e){return ditl;}
	}
	/**
	 * Items a hook appended at its first call (AppendDITL) where Apple's 348x200 dialog had room below its list: in the
	 * roomier layouts here they fall on the list. They go below it, the window growing to hold them - Apple's FilePath and
	 * FolderPath (PromptedSFGetFile glue) append their prompt at (12,191), drawn over the list and erased by its next redraw.
	 */
	private void placeAppendedItems(Session s,int before) throws M68kCpuException {
		DialogManager68k.Item listItem=tb.dialogs.item(s.window,s.listItem);if(listItem==null)return;
		int[] list=listItem.rect;int dy=0;List<Integer> onList=new ArrayList<Integer>(); // only those on the list move (FolderPath's Select button stays)
		for(int i=before;i<s.dialog.list.size();i++){int[] r=s.dialog.list.get(i).rect;
			if(r[0]<list[2]&&r[2]>list[0]&&r[1]<list[3]+16&&r[3]>list[1]){dy=Math.max(dy,list[2]+8-r[0]);onList.add(i);}}
		if(dy<=0)return;
		int bottom=0;
		for(int i:onList){int[] r=s.dialog.list.get(i).rect.clone();r[0]+=dy;r[2]+=dy;tb.dialogs.moveItem(s.window,i+1,r);bottom=Math.max(bottom,r[2]);}
		WindowManager68k.Window w=tb.windows.window(s.window);
		if(w!=null&&bottom+8>w.height)tb.windows.resize(s.window,w.width,bottom+8,true,false);
	}
	/** Where each item of a DITL starts. */
	private static List<Integer> itemOffsets(byte[] ditl){
		int count=((ditl[0]&0xff)<<8|(ditl[1]&0xff))+1;List<Integer> offsets=new ArrayList<Integer>();
		for(int i=1,at=2;i<=count&&at+14<=ditl.length;i++){offsets.add(at);int length=ditl[at+13]&0xff;at+=14+length+(length&1);}
		return offsets;
	}
	private static int[] rect(byte[] ditl,int at){int[] r=new int[4];for(int k=0;k<4;k++)r[k]=(short)((ditl[at+4+k*2]&0xff)<<8|(ditl[at+5+k*2]&0xff));return r;}
	private static void writeRect(byte[] ditl,int at,int[] r){for(int k=0;k<4;k++){ditl[at+4+k*2]=(byte)(r[k]>>8);ditl[at+5+k*2]=(byte)r[k];}}

	/** The file list's order: folders before files, each group by the chosen column, names as the tie-breaker. */
	private Comparator<File> sortOrder(){
		Comparator<File> byName=Comparator.comparing(this::displayName,String.CASE_INSENSITIVE_ORDER);
		Comparator<File> order;
		switch(sortColumn){
			case 1:order=Comparator.comparingLong(File::lastModified).thenComparing(byName);break;
			case 2:order=Comparator.comparingLong((File f)->f.isDirectory()?0:f.length()).thenComparing(byName);break;
			case 3:order=Comparator.comparing(this::kindOf).thenComparing(byName);break;
			default:order=byName;
		}
		return sortDescending?order.reversed():order;
	}
	private static final java.text.SimpleDateFormat DATE_FORMAT=new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm");
	/** The detail columns of a row: date modified, size and kind. */
	private String[] detailsOf(File f){
		String date;synchronized(DATE_FORMAT){date=DATE_FORMAT.format(new java.util.Date(f.lastModified()));}
		String[] all={date,f.isDirectory()?"--":sizeText(f.length()),kindOf(f)};
		return new String[]{all[detailColumn-1]};
	}
	/** Date, size and kind of an entry (for the preview). */
	private String[] allDetailsOf(File f){
		String date;synchronized(DATE_FORMAT){date=DATE_FORMAT.format(new java.util.Date(f.lastModified()));}
		return new String[]{date,f.isDirectory()?"--":sizeText(f.length()),kindOf(f)};
	}
	static String sizeText(long bytes){
		if(bytes<1024)return bytes+" bytes";
		if(bytes<1024L*1024)return (bytes+1023)/1024+" KB";
		if(bytes<1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f MB",bytes/(1024.0*1024));
		return String.format(java.util.Locale.ROOT,"%.2f GB",bytes/(1024.0*1024*1024));
	}
	/** What the Finder would call the file: from its type, or its extension for files without Finder info. */
	private String kindOf(File f){
		if(f.isDirectory())return "Folder";
		int type=tb.files.fileManager.shownType(f);
		switch(type){
			case 0x4150504c:return "Application";      // APPL
			case 0x5354414b:return "HyperCard stack";  // STAK
			case 0x54455854:return "Text";             // TEXT
			case 0x50494354:return "Picture";          // PICT
			case 0x4a504547:return "JPEG image";       // JPEG
			case 0x47494666:return "GIF image";        // GIFf
			case 0x504e4766:return "PNG image";        // PNGf
			case 0x4d6f6f56:return "QuickTime movie";  // MooV
			case 0x73666966:return "Sound";            // sfil
			case 0x464e4454:return "Font suitcase";    // FNDT
			case 0x5349542a:case 0x53495435:return "StuffIt archive"; // SIT*, SIT5
			case 0:{String n=f.getName();int dot=n.lastIndexOf('.');return dot>0&&dot<n.length()-1&&n.length()-dot<=6?n.substring(dot+1).toUpperCase(java.util.Locale.ROOT)+" file":"Document";}
			default:return ResourceFile68k.typeName(type).trim()+" file";
		}
	}
	/** A click on a column header sorts the list by that column (on the current one, it flips the order); the arrow at the
	 *  right of the second column opens the pop-up that chooses what the column shows. */
	private boolean headerClick(Session s,int h,int v) throws M68kException {
		int[] hr=s.headerRect;
		if(hr==null||v<hr[0]||v>=hr[2]||h<hr[1]||h>=hr[3])return false;
		int listRight=s.listView[3];
		if(h>=listRight)return true;
		if(h>=listRight-HEADER_MENU_WIDTH){columnMenu(s,listRight-HEADER_MENU_WIDTH,hr[2]);return true;}
		int column=h>=listRight-Math.abs(COLUMN_WIDTHS[detailColumn])?detailColumn:0;
		setSort(column,column==sortColumn&&!sortDescending);return true;
	}
	private static final int HEADER_MENU_WIDTH=16;
	/** The pop-up under the second column's arrow: Date Modified / Size / Kind. Headless runs cycle to the next column. */
	private void columnMenu(Session s,int left,int top) throws M68kException {
		int chosen;
		if(tb.host().headless)chosen=detailColumn%3+1;
		else{
			MacAppHost.MenuModel model=new MacAppHost.MenuModel(0,"",true);
			for(int c=1;c<=3;c++){MacAppHost.ItemModel item=new MacAppHost.ItemModel();item.text=COLUMN_NAMES[c];item.checked=c==detailColumn;model.items.add(item);}
			WindowManager68k.Window w=tb.windows.window(s.window);
			chosen=tb.host().popUpMenuSelect(model,left+(w==null?0:w.left),top+(w==null?0:w.top),detailColumn);
		}
		if(chosen>=1&&chosen<=3)setDetailColumn(chosen);
	}
	private static final Color HEADER_TOP=new Color(0xfa,0xfa,0xfa),HEADER_BOTTOM=new Color(0xe8,0xe8,0xe8),HEADER_LINE=new Color(0x9a,0x9a,0x9a),HEADER_TEXT=new Color(0x20,0x20,0x20);
	/** The column header above the list: "Name" and the chosen detail column with its pop-up arrow, a triangle on the one the list is sorted by. */
	private void drawHeader(Session s){
		int[] hr=s.headerRect;if(hr==null)return;
		try{
			Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
			try{
				int w=hr[3]-hr[1],h=hr[2]-hr[0];
				g.setPaint(new java.awt.GradientPaint(hr[1],hr[0],HEADER_TOP,hr[1],hr[0]+h,HEADER_BOTTOM));g.fillRect(hr[1],hr[0],w,h);
				g.setColor(HEADER_LINE);g.drawRect(hr[1],hr[0],w-1,h-1);
				Font font=Fonts68k.font(0,12,0);g.setFont(font);FontMetrics fm=Fonts68k.metrics(font);
				int listRight=s.listView[3],split=listRight-Math.abs(COLUMN_WIDTHS[detailColumn]);
				int[] lefts={hr[1],split},rights={split,listRight-HEADER_MENU_WIDTH};int[] columns={0,detailColumn};
				int baseline=hr[0]+(h-fm.getHeight())/2+fm.getAscent();
				for(int k=0;k<2;k++){
					int c=columns[k];
					if(k>0){g.setColor(HEADER_LINE);g.drawLine(lefts[k],hr[0]+3,lefts[k],hr[2]-4);}
					boolean rightAligned=COLUMN_WIDTHS[c]<0;
					String name=COLUMN_NAMES[c];int textWidth=rights[k]-lefts[k]-12-(c==sortColumn?12:0);
					while(name.length()>1&&fm.stringWidth(name)>textWidth)name=name.substring(0,name.length()-2)+"…";
					int tx=rightAligned?rights[k]-6-fm.stringWidth(name)-(c==sortColumn?12:0):lefts[k]+6;
					g.setColor(HEADER_TEXT);Fonts68k.drawString(g,fm,name,tx,baseline);
					if(c==sortColumn){ // the sort triangle: up for ascending, down for descending
						int ax=rightAligned?rights[k]-9:tx+fm.stringWidth(name)+9,ay=hr[0]+h/2;g.setColor(HEADER_TEXT);
						if(sortDescending)g.fillPolygon(new int[]{ax-4,ax+4,ax},new int[]{ay-2,ay-2,ay+3},3);else g.fillPolygon(new int[]{ax-4,ax+4,ax},new int[]{ay+2,ay+2,ay-3},3);}
				}
				// the pop-up arrow that switches the column
				int mx=listRight-HEADER_MENU_WIDTH,ay=hr[0]+h/2-2;g.setColor(HEADER_LINE);g.drawLine(mx,hr[0]+3,mx,hr[2]-4);
				g.setColor(HEADER_TEXT);g.fillPolygon(new int[]{mx+4,mx+12,mx+8},new int[]{ay,ay,ay+5},3);
			}finally{g.dispose();}
		}catch(M68kCpuException e){}
	}

	// ------------------------------------------------------------------ preview pane

	private static final Color PREVIEW_BACK=new Color(0xf7,0xf7,0xf7),PREVIEW_EDGE=new Color(0xc0,0xc4,0xca),PREVIEW_DIM=new Color(0x60,0x60,0x60);
	private static final int PREVIEW_TEXT_BYTES=4096,PREVIEW_IMAGE_BYTES=24*1024*1024;
	/** The preview pane: the selected entry's icon, name, kind, size and date, then a thumbnail of a picture (PICT, JPEG, GIF,
	 *  PNG) or the first lines of a text file; folders show how many items they hold. */
	private void drawPreview(Session s){
		int[] r=s.previewRect;if(r==null)return;
		int row=tb.listManager.selectedRow(s.list);File f=row>=0&&row<s.entries.size()?s.entries.get(row):null;
		if(f!=null&&!f.equals(s.previewFile)){s.previewFile=f;s.previewImage=null;s.previewText=null;loadPreview(s,f);}
		if(f==null){s.previewFile=null;s.previewImage=null;s.previewText=null;}
		try{
			Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
			try{
				int w=r[3]-r[1],h=r[2]-r[0];
				g.setColor(PREVIEW_BACK);g.fillRect(r[1],r[0],w,h);g.setColor(PREVIEW_EDGE);g.drawRect(r[1],r[0],w-1,h-1);
				g.setClip(r[1]+1,r[0]+1,w-2,h-2);
				Font font=Fonts68k.font(0,12,0),small=Fonts68k.font(0,10,0);FontMetrics fm=Fonts68k.metrics(font),sm=Fonts68k.metrics(small);
				if(f==null){g.setFont(font);g.setColor(PREVIEW_DIM);String none="No selection";Fonts68k.drawString(g,fm,none,r[1]+(w-fm.stringWidth(none))/2,r[0]+h/2);return;}
				int y=r[0]+12,cx=r[1]+w/2;
				java.awt.image.BufferedImage icon=FinderIcons68k.of(f);
				if(icon!=null)ListManager68k.paintImage(g,icon,cx-16,y,32,false);
				else{Graphics2D big=(Graphics2D)g.create();big.translate(cx-16,y);big.scale(32/12.0,32/12.0);ListManager68k.paintIcon(big,f.isDirectory()?ListManager68k.ICON_FOLDER:tb.files.fileManager.shownType(f)==0x4150504c?ListManager68k.ICON_APPLICATION:ListManager68k.ICON_DOCUMENT,0,0);big.dispose();}
				y+=40;
				g.setFont(font);g.setColor(Color.BLACK);
				for(String line:wrap(fm,displayName(f),w-16)){if(y+fm.getHeight()>r[2]-3)break;Fonts68k.drawString(g,fm,line,cx-fm.stringWidth(line)/2,y+fm.getAscent());y+=fm.getHeight();}
				y+=4;g.setFont(small);g.setColor(PREVIEW_DIM);
				String[] details=allDetailsOf(f);
				String[] lines=new String[]{kindOf(f),f.isDirectory()?folderItemCount(f):details[1],details[0]};
				if(sm.stringWidth(lines[2])>w-12)lines[2]=lines[2].substring(0,10); // a narrow pane shows the date without the time
				for(String line:lines){if(y+sm.getHeight()>r[2]-3)break;while(line.length()>1&&sm.stringWidth(line)>w-12)line=line.substring(0,line.length()-2)+"…";Fonts68k.drawString(g,sm,line,cx-sm.stringWidth(line)/2,y+sm.getAscent());y+=sm.getHeight();}
				y+=10;
				if(s.previewImage!=null){
					java.awt.image.BufferedImage img=s.previewImage;int maxW=w-16,maxH=r[2]-y-8;
					if(maxH>16){double scale=Math.min(1.0,Math.min(maxW/(double)img.getWidth(),maxH/(double)img.getHeight()));
						int iw=Math.max(1,(int)(img.getWidth()*scale)),ih=Math.max(1,(int)(img.getHeight()*scale));
						Object hint=g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
						g.drawImage(img,cx-iw/2,y,iw,ih,null);g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,hint==null?java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR:hint);
						g.setColor(PREVIEW_EDGE);g.drawRect(cx-iw/2-1,y-1,iw+1,ih+1);}
				}else if(s.previewText!=null){
					g.setFont(small);g.setColor(Color.BLACK);int left=r[1]+8;
					for(String line:s.previewText){if(y+sm.getHeight()>r[2]-6)break;String t=line;while(t.length()>1&&sm.stringWidth(t)>w-16)t=t.substring(0,t.length()-2)+"…";Fonts68k.drawString(g,sm,t,left,y+sm.getAscent());y+=sm.getHeight();}
				}
			}finally{g.dispose();}
		}catch(M68kCpuException e){}
	}
	/** Loads what the pane shows for a file: a picture for image types, the first lines for text files. */
	private void loadPreview(Session s,File f){
		if(!f.isFile()||f.length()>PREVIEW_IMAGE_BYTES)return;
		int type=tb.files.fileManager.shownType(f);String n=f.getName().toLowerCase(java.util.Locale.ROOT);
		try{
			if(type==0x50494354||n.endsWith(".pict")||n.endsWith(".pct"))s.previewImage=PictureFile.loadPICT(f.getPath());
			else if(type==0x4a504547||type==0x47494666||type==0x504e4766||n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".gif")||n.endsWith(".png")||n.endsWith(".bmp"))s.previewImage=javax.imageio.ImageIO.read(f);
			else if(type==0x54455854||(type==0&&(n.endsWith(".txt")||n.endsWith(".text")||n.endsWith(".html")||n.endsWith(".htm")||n.endsWith(".c")||n.endsWith(".h")||n.endsWith(".p")||n.endsWith(".r")))){
				byte[] bytes=new byte[(int)Math.min(PREVIEW_TEXT_BYTES,f.length())];
				try(java.io.FileInputStream in=new java.io.FileInputStream(f)){int got=0;while(got<bytes.length){int k=in.read(bytes,got,bytes.length-got);if(k<0)break;got+=k;}if(got<bytes.length)bytes=Arrays.copyOf(bytes,got);}
				String text=tb.decode(bytes).replace("\r\n","\r").replace('\n','\r');
				List<String> lines=new ArrayList<String>();for(String line:text.split("\r")){lines.add(line.replace('\t',' '));if(lines.size()>=40)break;}
				s.previewText=lines.toArray(new String[0]);
			}
		}catch(Exception|OutOfMemoryError e){s.previewImage=null;s.previewText=null;}
	}
	private static String folderItemCount(File d){File[] all=FileManager68k.catalogEntries(d);int n=0;if(all!=null)for(File f:all)if(!f.getName().startsWith("."))n++;return n==1?"1 item":n+" items";}
	/** Breaks a name into lines no wider than {@code width} (at spaces when possible, else anywhere). */
	private static List<String> wrap(FontMetrics fm,String text,int width){
		List<String> lines=new ArrayList<String>();StringBuilder line=new StringBuilder();
		for(int i=0;i<text.length();i++){
			char c=text.charAt(i);
			if(fm.stringWidth(line.toString()+c)>width&&line.length()>0){
				int space=line.lastIndexOf(" ");
				if(space>0){lines.add(line.substring(0,space));line.delete(0,space+1);}else{lines.add(line.toString());line.setLength(0);}
			}
			line.append(c);
		}
		if(line.length()>0)lines.add(line.toString());
		if(lines.size()>3){lines=new ArrayList<String>(lines.subList(0,3));String last=lines.get(2);lines.set(2,last.substring(0,Math.max(1,last.length()-1))+"…");}
		return lines;
	}

	/** MacBinary containers show the name from their header, as they do in the File Manager; other names as the File Manager reports them. */
	private String displayName(File f){
		String n=f.getName();
		if(n.endsWith(".bin")){byte[] header=ResourceFile68k.MacBinary.headerNameBytes(f);if(header!=null&&header.length>0)return tb.decode(header);}
		return tb.files.fileManager.programName(n);
	}

	private static final Color POPUP_TOP=new Color(0xfc,0xfc,0xfc),POPUP_BOTTOM=new Color(0xe2,0xe2,0xe2),POPUP_EDGE=new Color(0x8a,0x8a,0x8a),POPUP_ARROW=new Color(0x30,0x30,0x30);
	/** The disk name (top right) with a disk icon; the whole strip is a button that goes up one folder. */
	private void drawDiskName(Session s){
		DialogManager68k.Item item=tb.dialogs.item(s.window,s.diskItem);if(item==null)return;
		try{
			Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
			try{
				int[] r=item.rect;int w=r[3]-r[1],h=r[2]-r[0];
				g.setColor(windowBackground(s));g.fillRect(r[1],r[0],w,h);
				Font font=Fonts68k.font(0,12,0);g.setFont(font);FontMetrics fm=Fonts68k.metrics(font);
				String text=volumeName(s.directory);boolean canGoUp=parent(s.directory)!=null;
				boolean arrow=canGoUp&&fm.stringWidth(text)<=w-22-14; // the up arrow yields to a long name in a narrow item
				int textWidth=w-22-(arrow?14:0);while(text.length()>1&&fm.stringWidth(text)>textWidth)text=text.substring(0,text.length()-2)+"…";
				int iy=r[0]+(h-12)/2;ListManager68k.paintIcon(g,ListManager68k.ICON_DISK,r[1]+2,iy);
				g.setColor(Color.BLACK);Fonts68k.drawString(g,fm,text,r[1]+20,r[0]+(h-fm.getHeight())/2+fm.getAscent());
				if(arrow){ // an up arrow says the strip is a button
					int ax=r[3]-11,ay=r[0]+h/2;g.setColor(POPUP_ARROW);g.fillPolygon(new int[]{ax-4,ax,ax+4},new int[]{ay+1,ay-4,ay+1},3);g.fillRect(ax-1,ay+1,3,3);}
			}finally{g.dispose();}
		}catch(M68kCpuException e){}
	}
	/** The folder pop-up button: a bevelled pop-up with a folder icon, the current folder's name and a menu arrow, in {@link Session#popupRect}. */
	private void drawFolderPopup(Session s){
		if(s.popupRect==null)return;
		try{
			Graphics2D g=tb.quickDraw.windowSurface(s.window);if(g==null)return;
			try{
				int[] r=s.popupRect;int w=r[3]-r[1],h=r[2]-r[0];
				g.setColor(windowBackground(s));g.fillRect(r[1],r[0],w,h);
				Font font=Fonts68k.font(0,12,0);g.setFont(font);FontMetrics fm=Fonts68k.metrics(font);
				// a Kaleidoscope scheme's pop-up button (its frame, arrow part and glyph; the name in its text colour), as the
				// real engine draws the System's folder pop-up; else a bevelled one with a menu arrow
				KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);
				Color ink=scheme!=null&&scheme.hasPopup()?scheme.drawPopupBox(g,r[1],r[0],r[3],r[2],"",false,false,fm):null;
				if(ink==null){
					g.setPaint(new java.awt.GradientPaint(r[1],r[0],POPUP_TOP,r[1],r[0]+h,POPUP_BOTTOM));g.fillRoundRect(r[1],r[0],w-1,h-1,6,6);
					g.setColor(POPUP_EDGE);g.drawRoundRect(r[1],r[0],w-2,h-2,6,6); // crisp edge (no antialiasing): tests and the eye find the frame on one row
					int ax=r[1]+w-18,ay=r[0]+h/2-2;g.setColor(POPUP_ARROW);g.fillPolygon(new int[]{ax,ax+11,ax+5},new int[]{ay,ay,ay+6},3);}
				String name=folderLabel(s.directory);
				int textWidth=w-44;while(name.length()>1&&fm.stringWidth(name)>textWidth)name=name.substring(0,name.length()-2)+"…";
				ListManager68k.paintIcon(g,tb.files.fileManager.volumeRoot(s.directory)?ListManager68k.ICON_DISK:ListManager68k.ICON_FOLDER,r[1]+6,r[0]+(h-12)/2);
				g.setColor(ink!=null?ink:Color.BLACK);Fonts68k.drawString(g,fm,name,r[1]+24,r[0]+(h-fm.getHeight())/2+fm.getAscent());
			}finally{g.dispose();}
		}catch(M68kCpuException e){}
	}
	/** The dialog's content colour, behind the pop-up and the disk name. */
	private Color windowBackground(Session s){WindowManager68k.Window w=tb.windows.window(s.window);return new Color(w==null?0xffffff:w.contentColor);}
	private String volumeName(File directory){return tb.files.fileManager.volumeNameOf(directory);}
	/** sfIsFolder / sfIsVolume of a StandardFileReply: a volume's root is a volume, not a folder (IM Files, "Standard File Package"):
	 *  a program taking sfIsFolder for "open it as a folder" otherwise treats the disk as a file or a folder inside itself. */
	private void writeItemKind(int reply,File item) throws M68kCpuException {
		boolean volume=item.isDirectory()&&tb.files.fileManager.volumeRoot(item);
		memory.writeByte(reply+80,item.isDirectory()&&!volume?1:0);memory.writeByte(reply+81,volume?1:0);
	}

	/** The "New Folder" button: creates "untitled folder" (numbered when needed) in the current folder and shows it. */
	private void newFolder(Session s) throws M68kException {
		if(!s.directory.canWrite())return;
		File created=new File(s.directory,"untitled folder");for(int i=2;created.exists();i++)created=new File(s.directory,"untitled folder "+i);
		if(created.mkdir())enter(s,s.directory);
	}

	private void writeReplyIn(Request r,Session s,File chosen,boolean good) throws M68kCpuException {
		if(r.reply==0)return;
		// Cancel only clears an old-style SFReply's good flag: the rest stays as the dialog left it. PageMaker 6's installer
		// turns its own Install button into Cancel in the hook and then creates the folder named in reply.fName.
		// A StandardFileReply is left the same way when a hook ended the dialog with Cancel (the dialog's folder and selection stay in
		// sfFile): FreeHand 5's installer reads the destination folder from it after its own Install button became Cancel.
		if(!good&&(!r.newStyle||r.dlgHook!=0)){memory.writeByte(r.reply,0);return;}
		if(!good){writeReply(r.reply,r.newStyle,null,r.put);return;}
		if(chosen==null||chosen.equals(s.directory)){
			// folder answer: old replies carry the folder as vRefNum with an empty name and its directory ID in fType (folder pickers
			// walk up from there: G-XCollection's MtSelectFldr stops at dirID 2 and ended in "Error! : PBGetCatInfo" for a volume
			// root when fType was 0), StandardFileReply as the FSSpec of the folder
			if(r.newStyle)writeReply(r.reply,true,s.directory,r.put);
			else{memory.fill(r.reply,74,0);memory.writeByte(r.reply,1);
				try{memory.writeLong(r.reply+2,tb.files.directoryIdFor(s.directory));}catch(IOException e){}
				memory.writeWord(r.reply+6,tb.files.workingDirectoryFor(s.directory));memory.writeByte(r.reply+10,0);}
			return;
		}
		writeReply(r.reply,r.newStyle,chosen,r.put);
		if(!r.newStyle&&chosen.isDirectory())try{memory.writeLong(r.reply+2,tb.files.directoryIdFor(chosen));}catch(IOException e){} // a folder's fType is its directory ID, as in the list
		if(Mac68kApi.TRACE)System.err.println("[sf] reply "+chosen+" newStyle="+r.newStyle+" vRef="+(short)memory.readWord(r.reply+6)+" parID="+memory.readLong(r.reply+8)+" CurDirStore="+memory.readLong(CUR_DIR_STORE));
	}

	/** A list slot narrower than this is not shown (MacWrite 2.2 squeezes putDlgID's list to a 1-pixel rule): no folder pop-up above it. */
	private static final int MIN_LIST_WIDTH=40;
	private static final int DIALOG_WIDTH=440,GET_HEIGHT=330,PUT_HEIGHT=360;
	/**
	 * The System file's Standard File dialogs (DLOG and DITL getDlgID -4000, putDlgID -3999, sfGetDialogID -6042, sfPutDialogID
	 * -6043) with the layouts below. Programs look them up before asking for a file: 六角大王 5.5's framework loads DLOG -3999
	 * before Save As and reported a missing one as "not enough memory" (-108).
	 */
	static void addSystemResources(ResourceFile68k f){
		int[][] dialogs={{GET_DLG_ID,0,0},{PUT_DLG_ID,1,0},{SF_GET_DLG_ID,0,1},{SF_PUT_DLG_ID,1,1}};
		for(int[] d:dialogs){boolean put=d[1]!=0,newStyle=d[2]!=0;
			byte[] dlog=new byte[24]; // bounds, procID dBoxProc, invisible, no go-away box, refCon 0, DITL, empty title, position 0
			int height=put?PUT_HEIGHT:GET_HEIGHT;dlog[4]=(byte)(height>>8);dlog[5]=(byte)height;dlog[6]=(byte)(DIALOG_WIDTH>>8);dlog[7]=(byte)DIALOG_WIDTH;
			dlog[9]=1;dlog[18]=(byte)(d[0]>>8);dlog[19]=(byte)d[0];
			f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("DLOG"),d[0],null,0x20,dlog));
			f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("DITL"),d[0],null,0x20,standardItems(put,newStyle)));}
	}
	/**
	 * The getDlgID / putDlgID and sfGetDialogID / sfPutDialogID item lists, for programs that pass dlgID 0 (with a hook or
	 * filter). The item numbers are the System's, so hooks written against them keep working; the layout is roomier than
	 * the 1980s original: a wide list with a folder pop-up above it, the disk name (a button that goes up a folder) at the
	 * top right, and the buttons in a column on the right.
	 */
	private static byte[] standardItems(boolean put,boolean newStyle){
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		List<Object[]> items=new ArrayList<Object[]>();
		int height=put?PUT_HEIGHT:GET_HEIGHT,bl=306,br=416; // button column
		int listTop=newStyle?40:33,listBottom=put?height-94:height-18; // old layouts get the pop-up drawn in the strip above the list (POPUP_HEIGHT+1), level with the disk name
		int[] list=new int[]{listTop,16,listBottom,274},scroll=new int[]{listTop,274,listBottom,290},listWithScroll=new int[]{listTop,16,listBottom,290};
		int[] popup=new int[]{12,16,32,290},disk=new int[]{12,bl,32,br+10};
		int[] defaultButton=new int[]{height-30,bl,height-10,br},cancel=new int[]{height-58,bl,height-38,br};
		int[] eject=new int[]{44,bl,64,br},driveOrDesktop=new int[]{72,bl,92,br},newFolder=new int[]{100,bl,120,br};
		int[] prompt=new int[]{listBottom+12,16,listBottom+28,290},name=new int[]{listBottom+32,19,listBottom+48,287};
		int[] hidden=new int[]{1000,bl,1020,br},nothing=new int[]{0,0,0,0};
		if(newStyle){ // sfGetDialogID / sfPutDialogID layouts
			items.add(new Object[]{4,defaultButton,put?"Save":"Open"});
			items.add(new Object[]{4,cancel,"Cancel"});
			items.add(new Object[]{0x80,nothing,""});
			items.add(new Object[]{0x80,disk,""});
			items.add(new Object[]{4,eject,"Eject"});
			items.add(new Object[]{4,driveOrDesktop,"Desktop"});
			items.add(new Object[]{0,listWithScroll,""});
			items.add(new Object[]{0,popup,""});
			items.add(new Object[]{0x80,nothing,""});
			if(put){
				items.add(new Object[]{16,name,""});
				items.add(new Object[]{0x88,prompt,"Save as:"});
				items.add(new Object[]{4,newFolder,"New Folder"});
			}
		}else if(!put){ // getDlgID: the pop-up is drawn in the strip above the list
			items.add(new Object[]{4,defaultButton,"Open"});
			items.add(new Object[]{4,hidden,"Hidden"});
			items.add(new Object[]{4,cancel,"Cancel"});
			items.add(new Object[]{0x80,disk,""});
			items.add(new Object[]{4,eject,"Eject"});
			items.add(new Object[]{4,driveOrDesktop,"Drive"});
			items.add(new Object[]{0,list,""});
			items.add(new Object[]{0,scroll,""});
			items.add(new Object[]{0x80,nothing,""});
			items.add(new Object[]{0x88,nothing,""});
		}else{ // putDlgID
			items.add(new Object[]{4,defaultButton,"Save"});
			items.add(new Object[]{4,cancel,"Cancel"});
			items.add(new Object[]{0x88,prompt,""});
			items.add(new Object[]{0x80,disk,""});
			items.add(new Object[]{4,eject,"Eject"});
			items.add(new Object[]{4,driveOrDesktop,"Drive"});
			items.add(new Object[]{16,name,""});
			items.add(new Object[]{0,list,""});
			items.add(new Object[]{0,scroll,""});
			items.add(new Object[]{0x80,nothing,""});
		}
		out.write((items.size()-1)>>8);out.write((items.size()-1)&0xff);
		for(Object[] it:items){
			int type=(Integer)it[0];int[] rect=(int[])it[1];byte[] text=((String)it[2]).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
			out.write(new byte[4],0,4);for(int v:rect){out.write(v>>8);out.write(v&0xff);}
			out.write(type);out.write(text.length);out.write(text,0,text.length);if((text.length&1)!=0)out.write(0);
		}
		return out.toByteArray();
	}
}

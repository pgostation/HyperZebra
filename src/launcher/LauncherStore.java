import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * What the launcher window ({@link GLauncher}) remembers between runs, kept in HyperZebra HD as launcher.properties:
 * <ul>
 * <li>the programs and stacks opened most recently (newest first, at most {@link #MAX_RECENT}, one entry per file),</li>
 * <li>the documents the user chose to open in a 68K program (its Open dialog, a drop, a launch with documents), each with
 *     the program that opened it (newest first, at most {@link #MAX_RECENT}); the dock's per-program "Recent Documents"
 *     is the same list narrowed to one program (at most {@link #MAX_DOCUMENTS}),</li>
 * <li>the items registered in the dock, in the order they are shown - one list per dock tab ({@link DockTab}; the first
 *     tab is the dock of earlier versions, and the only one shown while the launcher's tabs are off),</li>
 * <li>per-program settings: overrides of the VM preferences (colours, screen, 2x, wide dialogs, speed, controller bindings; null = follow the
 *     VM setting),</li>
 * <li>the program chosen to open each kind of document (file type → program), for documents dropped on the launcher
 *     ("Always open this kind of file with this program").</li>
 * </ul>
 * Removing a history entry or clearing the history never touches the dock or the per-program settings; leaving the dock
 * keeps the program's settings too. Nothing is read or written unless {@link #enabled} (PCARD.main sets it; tests and
 * -Dhz.launcher=false leave it off, so they neither see nor change the user's history).
 */
final class LauncherStore {
	static final int MAX_RECENT=500, MAX_DOCUMENTS=10;
	static final String KIND_APP="app", KIND_STACK="stack", KIND_DOC="doc";

	/** A recently opened program, stack or document. */
	static final class Item {
		String path; final String kind; long lastUsed;
		/** KIND_DOC only: the program that opened the document (its identity path). */
		String app;
		Item(String path,String kind,long lastUsed){this.path=path;this.kind=kind;this.lastUsed=lastUsed;}
		File file(){return new File(path);}
		boolean isApp(){return KIND_APP.equals(kind);}
		boolean isDocument(){return KIND_DOC.equals(kind);}
		/** The program a document was opened with, or null for a program or stack. */
		File appFile(){return app==null?null:new File(app);}
		/** The name shown: a folder stack (.hzb) by its folder name without the extension. */
		String name(){return displayName(file());}
		/** The file is there - or it is a stack just converted (or saved as a new .hzb) whose file the autosave has not written yet. */
		boolean exists(){File f=file();return f.exists()||StackBundle.folderOf(f)!=null;}
	}

	/** One program's overrides of the VM preferences. */
	/** A MIPS rate as written in launcher.properties and shown to the user: "2", "12.5" (no trailing ".0"). */
	static String mipsText(double mips){double r=Math.round(mips*10)/10.0;return r==Math.rint(r)?Long.toString((long)r):Double.toString(r);}
	static final class AppSettings {
		/** 1, 4, 8, 16 or 32; null follows the VM setting. */
		Integer depth;
		/** "" for the largest screen of the time, "display" for the whole display, "640x480" ...; null follows the VM setting. */
		String screenSize;
		/** 1 or 2; null follows the VM setting. */
		Integer screenScale;
		/** A game screen (a chosen size smaller than the display) at the display's top left (true) or centre (false); null follows the VM setting. */
		Boolean screenTopLeft;
		/** The smooth zoom (SmoothZoom.OFF/BRESENHAM/BICUBIC/SCALE2X/AUTO); null follows the Mac application settings. */
		Integer smoothZoom;
		Boolean wideDialogs;
		/** Control-arrow and the other system shortcuts go to the program while Control is held (macOS); null follows the preference. */
		Boolean controlKeys;
		Boolean realSpeed;
		/** A fixed instruction rate in MIPS, 0.1 or more (a machine of the time from GAppSettingsDialog.SPEED_MIPS, e.g. 2 =
		 *  "Classic Macintosh", a 68000 at 8 MHz for programs that pace themselves by busy loops, Seven Dice; or a rate the
		 *  user typed); null follows realSpeed. */
		Double mips;
		/** The program's own game controller bindings (GamepadHost.Mapping.format()); null follows the preference. */
		String gamepadMapping;
		/** A fat program (68K and PowerPC code): true runs its PowerPC code; null follows the default (PowerPC, -Dhzppc.fat=false for 68K). */
		Boolean powerPC;
		/** PowerPC code through the JIT compiler (PpcJit: true) or the interpreter alone (false); null follows the Mac application settings. */
		Boolean ppcJit;
		/** The host keys that stand for Command, Option and Control in this program (ModifierKeys.format()); null follows the default. */
		String modifierKeys;
		/** The System version the program sees (Gestalt sysv, SysVersion), 0x0755 or 0x0922; null follows the standard (Mac OS 9.2.2). */
		Integer systemVersion;
		/** The language of the System the program sees (Toolbox68k.applicationEncoding): "English" (a U.S. System) or "Japanese"
		 *  (KanjiTalk: Shift-JIS, Osaka, itl region verJapan); null follows HyperZebra's language. The U.S. QuarkXPress 3.31
		 *  refuses a Japanese System ("can't be run on this System. [4]"). */
		String language;
		/** Access outside the program's folders (Sandbox): "ask", "allow" (always) or "deny" (always); null follows the preference. */
		String accessPolicy;
		/** The program changes nothing outside HyperZebra HD but what the user picks in its Save dialog (Sandbox); null = no. */
		Boolean readOnly;
		/** Translation of what the program shows (VMTranslation): on/off, the target and source languages ("auto" source:
		 *  found from the program's texts) and the engine; null follows the Mac application settings (source: auto). */
		Boolean translate;String translateTo,translateFrom,translateEngine;
		/** Its translated resources (ResourceTranslation, made by "Resource Translation…"): false = run with its own resources;
		 *  null = use them when there are some for the language it is translated into. */
		Boolean translatedResources;
		/** A stack: false = the text in its pictures is not translated (PictureTranslation); null = it is, when the stack is. */
		Boolean translatePictures;
		/** Folders the user allowed the program to use ("Allow Its Folder and Below"), as "level\tpath" (Sandbox.Level). */
		final List<String> grants=new ArrayList<String>();
		/** Its font substitutions (Fonts68k.Substitution, as Font Patchin' made them), as "original\treplacement" family names:
		 *  the program's text in the first is drawn in the second (an English iTunes's Lucida Grande in Osaka). */
		final List<String> fontSubstitutions=new ArrayList<String>();
		/** A copy of these settings (another program file gets them: a working copy, a moved program). */
		AppSettings copy(){
			AppSettings c=new AppSettings();c.depth=depth;c.screenSize=screenSize;c.screenScale=screenScale;c.screenTopLeft=screenTopLeft;c.smoothZoom=smoothZoom;c.wideDialogs=wideDialogs;c.controlKeys=controlKeys;
			c.realSpeed=realSpeed;c.mips=mips;c.gamepadMapping=gamepadMapping;c.powerPC=powerPC;c.ppcJit=ppcJit;c.modifierKeys=modifierKeys;c.systemVersion=systemVersion;c.language=language;
			c.accessPolicy=accessPolicy;c.readOnly=readOnly;c.grants.addAll(grants);c.fontSubstitutions.addAll(fontSubstitutions);
			c.translate=translate;c.translateTo=translateTo;c.translateFrom=translateFrom;c.translateEngine=translateEngine;c.translatedResources=translatedResources;c.translatePictures=translatePictures;return c;
		}
		/** Takes what this program has not set from {@code other} (a moved program's old settings onto the new place's). */
		void fillFrom(AppSettings o){
			if(depth==null)depth=o.depth;if(screenSize==null)screenSize=o.screenSize;if(screenScale==null)screenScale=o.screenScale;if(screenTopLeft==null)screenTopLeft=o.screenTopLeft;if(smoothZoom==null)smoothZoom=o.smoothZoom;if(wideDialogs==null)wideDialogs=o.wideDialogs;
			if(controlKeys==null)controlKeys=o.controlKeys;if(realSpeed==null&&mips==null){realSpeed=o.realSpeed;mips=o.mips;}if(gamepadMapping==null)gamepadMapping=o.gamepadMapping;
			if(powerPC==null)powerPC=o.powerPC;if(ppcJit==null)ppcJit=o.ppcJit;if(modifierKeys==null)modifierKeys=o.modifierKeys;if(systemVersion==null)systemVersion=o.systemVersion;if(language==null)language=o.language;
			if(accessPolicy==null)accessPolicy=o.accessPolicy;if(readOnly==null)readOnly=o.readOnly;
			if(translate==null)translate=o.translate;if(translateTo==null)translateTo=o.translateTo;if(translateFrom==null)translateFrom=o.translateFrom;if(translateEngine==null)translateEngine=o.translateEngine;
			if(translatedResources==null)translatedResources=o.translatedResources;
			if(translatePictures==null)translatePictures=o.translatePictures;
			for(String g:o.grants)if(!grants.contains(g))grants.add(g);
			if(fontSubstitutions.isEmpty())fontSubstitutions.addAll(o.fontSubstitutions);
		}
		boolean isEmpty(){return accessPolicy==null&&readOnly==null&&grants.isEmpty()&&fontSubstitutions.isEmpty()&& depth==null&&screenSize==null&&screenScale==null&&screenTopLeft==null&&smoothZoom==null&&wideDialogs==null&&controlKeys==null&&realSpeed==null&&mips==null&&gamepadMapping==null&&powerPC==null&&ppcJit==null&&modifierKeys==null&&systemVersion==null&&language==null&&translate==null&&translateTo==null&&translateFrom==null&&translateEngine==null&&translatedResources==null&&translatePictures==null;}
	}

	/** A tab of the dock (DragThing's tabs): a name (null = the first tab's default name), a colour (RGB, null = none) and its items' identity paths. */
	static final class DockTab {
		String name; Integer color;
		final List<String> items=new ArrayList<String>();
		DockTab(String name){this.name=name;}
	}

	interface Listener { void storeChanged(); }

	/** Whether the store is in use at all (see the class comment). */
	static volatile boolean enabled;
	private static LauncherStore instance;
	private static final List<Listener> listeners=new ArrayList<Listener>();

	final List<Item> recent=new ArrayList<Item>();
	final List<Item> documents=new ArrayList<Item>();
	/** The dock's tabs, at least one. */
	final List<DockTab> tabs=new ArrayList<DockTab>(Collections.singletonList(new DockTab(null)));
	/** The tab shown when the launcher shows tabs. */
	private int selectedTab;
	/** Whether the launcher shows the tabs (its appearance setting, not saved here): without them the dock is the first tab. */
	private boolean tabsShown;
	final Map<String,AppSettings> apps=new LinkedHashMap<String,AppSettings>();
	/** File type (four characters) → identity path of the program that opens documents of that type from now on. */
	final Map<String,String> openers=new LinkedHashMap<String,String>();
	private final File file;
	private boolean loaded;

	LauncherStore(File file){this.file=file;}

	static synchronized LauncherStore get(){
		if(instance==null)instance=new LauncherStore(new File(AppPaths.stateDirectory(),"launcher.properties"));
		if(enabled&&!instance.loaded)instance.load(); // the menus ask for the history before PCARD.main enables the store
		return instance;
	}
	static synchronized void addListener(Listener l){listeners.add(l);}
	static synchronized void removeListener(Listener l){listeners.remove(l);}
	private static void notifyChanged(){
		List<Listener> copy;synchronized(LauncherStore.class){copy=new ArrayList<Listener>(listeners);}
		for(Listener l:copy)l.storeChanged();
	}

	/** One path for a file however it was named: a folder stack by its folder (not its _stack.xml), canonical where possible. */
	static String identity(File f){
		if(f==null)return null;
		f=StackBundle.host(f); // a stack file (.hzb ZIP) by its file, not its working folder
		if(f.getName().equals("_stack.xml")||f.getName().equals("toc.xml")){File p=f.getParentFile();if(p!=null)f=p;}
		try{return f.getCanonicalPath();}catch(IOException e){return f.getAbsolutePath();}
	}
	static String displayName(File f){
		if(f==null)return "";
		f=StackBundle.host(f);
		if(f.getName().equals("_stack.xml")||f.getName().equals("toc.xml")){File p=f.getParentFile();if(p!=null)f=p;}
		String n=f.getName();
		if(n.toLowerCase(java.util.Locale.ROOT).endsWith(".hzb"))n=n.substring(0,n.length()-4);
		return composed(n);
	}
	/** A file name as it should be drawn: Mac disks hand out decomposed names, where a handakuten is its own combining mark, and
	 *  Windows draws the pair as two glyphs ("ウルティメイトスペル"). Composed, it is the one character the name means.
	 *  Only the label changes: the name on disk is left as it is. */
	static String composed(String name){
		return name==null?null:Normalizer.normalize(name,Normalizer.Form.NFC);
	}
	/** Whether this file would be a stack in the history (anything that is not a 68K program). */
	static String kindOf(File f){f=MacApp68k.programOf(f);return f.isFile()&&(MacApp68k.isApplication(f)||MacApp68k.isDeskAccessory(f)||MacApp68k.isControlPanel(f))?KIND_APP:KIND_STACK;}

	// ------------------------------------------------------------------ recent items

	/** Creators of self-extracting archives: StuffIt ('aust'), Compact Pro ('EXTR'), DiskDoubler ('DSEA'). */
	private static final int[] SEA_CREATORS={ResourceFile68k.fourcc("aust"),ResourceFile68k.fourcc("EXTR"),ResourceFile68k.fourcc("DSEA")};
	/**
	 * A self-extracting archive: a program that is run once to unpack what it carries, so the history does not list it
	 * (nor documents "opened" in it). Known by its creator (MacBinary header, sidecar or extended attribute) or its ".sea" name.
	 */
	static boolean isSelfExtracting(File f){
		if(f==null)return false;
		String n=f.getName().toLowerCase(java.util.Locale.ROOT);
		for(String suffix:new String[]{".bin",".hqx","_"})if(n.endsWith(suffix))n=n.substring(0,n.length()-suffix.length());
		if(n.endsWith(".sea"))return true;
		byte[] info=f.isFile()?FinderInfo68k.stored(f):null;
		if(info==null)return false;
		int creator=MacApp68k.u4(info,4);
		for(int c:SEA_CREATORS)if(c==creator)return true;
		return false;
	}

	/** Records a successful open: the item moves to the front (a file is listed once) and the oldest beyond MAX_RECENT are dropped. */
	synchronized Item recordOpened(File f,String kind){
		String id=identity(f);if(id==null)return null;
		if(KIND_APP.equals(kind)&&isSelfExtracting(f))return null; // run once to unpack: not a program to come back to
		Item found=null;
		for(int i=recent.size()-1;i>=0;i--){Item it=recent.get(i);if(identity(it.file()).equals(id)){found=it;recent.remove(i);}}
		Item item=found!=null&&found.kind.equals(kind)?found:new Item(id,kind,0);
		item.path=id;item.lastUsed=System.currentTimeMillis();
		recent.add(0,item);
		while(recent.size()>MAX_RECENT)recent.remove(recent.size()-1);
		save();return item;
	}
	synchronized void remove(Item item){if(recent.remove(item)||documents.remove(item))save();}
	/** Removes several history entries at once (one save). */
	synchronized void removeAll(List<Item> items){boolean any=false;for(Item it:items)any|=recent.remove(it)||documents.remove(it);if(any)save();}
	/** Clears the whole history: programs, stacks and documents (the dock and the settings stay). */
	synchronized void clearRecent(){if(recent.isEmpty()&&documents.isEmpty())return;recent.clear();documents.clear();save();}
	/**
	 * The user pointed at the moved file (Locate…): the entry keeps its place and date, and everything else that refers to
	 * the file follows - its dock button, its Application Settings (speed, keys, colours…, and the folders it was allowed to
	 * use), the documents recorded as opened with it, the file types it opens. When it moved with its folder (the same name
	 * at the end of both paths), other entries for files of that folder that are missing now but are found at the same place
	 * under the new folder follow too (its documents, the other programs of a games folder). Returns how many references changed.
	 */
	synchronized int relocate(Item item,File target){
		String id=identity(MacApp68k.programOf(target));if(id==null)return 0; // a Mac OS 9 package: the program inside
		String old=item.path;
		List<Item> list=item.isDocument()?documents:recent;
		for(int i=list.size()-1;i>=0;i--){Item it=list.get(i);if(it!=item&&identity(it.file()).equals(id))list.remove(i);}
		item.path=id;
		int changed=1+rebaseAll(old,id);
		save();return changed;
	}
	/** {@link #relocate(Item,File)} for a file known only by its old path (a dock button whose file is missing). */
	synchronized int relocate(File oldFile,File target){
		String id=identity(MacApp68k.programOf(target));if(id==null||oldFile==null)return 0;
		String old=identity(oldFile);
		for(Item it:recent)if(it.path.equals(old)){return relocate(it,target);}
		int changed=rebaseAll(old,id);
		save();return changed;
	}
	/** Moves every reference to {@code old} (and, for a folder move, to missing files of its folder) to the new place. */
	private int rebaseAll(String old,String now){
		Rebase r=new Rebase(old,now);int n=0;
		for(int i=recent.size()-1;i>=0;i--){Item it=recent.get(i);String p=r.apply(it.path);if(p.equals(it.path))continue;
			boolean dup=false;for(Item o:recent)if(o!=it&&o.path.equals(p))dup=true;
			if(dup)recent.remove(i);else it.path=p;n++;}
		for(int i=documents.size()-1;i>=0;i--){Item it=documents.get(i);String p=r.apply(it.path),a=it.app==null?null:r.apply(it.app);
			if(p.equals(it.path)&&java.util.Objects.equals(a,it.app))continue;
			boolean dup=false;if(!p.equals(it.path))for(Item o:documents)if(o!=it&&o.path.equals(p))dup=true;
			if(dup)documents.remove(i);else{it.path=p;it.app=a;}n++;}
		for(DockTab t:tabs){List<String> dock=t.items;
			for(int i=0;i<dock.size();i++){String p=r.apply(dock.get(i));if(p.equals(dock.get(i)))continue;
				if(dock.contains(p)){dock.remove(i);i--;}else dock.set(i,p);n++;}}
		Map<String,AppSettings> moved=new LinkedHashMap<String,AppSettings>();
		for(Map.Entry<String,AppSettings> e:apps.entrySet()){
			String p=r.apply(e.getKey());AppSettings s=e.getValue();
			for(int i=0;i<s.grants.size();i++){String g=s.grants.get(i);int tab=g.indexOf('\t');if(tab<0)continue;String q=r.apply(g.substring(tab+1));if(!q.equals(g.substring(tab+1))){s.grants.set(i,g.substring(0,tab+1)+q);n++;}}
			if(!p.equals(e.getKey())){moved.put(e.getKey(),s);n++;}
		}
		for(Map.Entry<String,AppSettings> e:moved.entrySet()){
			apps.remove(e.getKey());String p=r.apply(e.getKey());
			AppSettings there=apps.get(p);
			if(there==null)apps.put(p,e.getValue());else there.fillFrom(e.getValue()); // set up at the new place already: what it lacks comes along
		}
		for(Map.Entry<String,String> e:openers.entrySet()){String p=r.apply(e.getValue());if(!p.equals(e.getValue())){e.setValue(p);n++;}}
		return n;
	}
	/** Where a remembered path is now: the moved file itself, or (for a folder move) a missing file found under the new folder. */
	private static final class Rebase {
		final String old,now,oldBase,newBase;
		Rebase(String old,String now){
			this.old=old;this.now=now;
			// the common tail of both paths ("Games/Lode Runner/Lode Runner") is what moved; the rest is the folder it moved from/to
			String[] a=old.split(java.util.regex.Pattern.quote(File.separator)),b=now.split(java.util.regex.Pattern.quote(File.separator));
			int common=0;while(common<a.length-1&&common<b.length-1&&a[a.length-1-common].equals(b[b.length-1-common]))common++;
			if(common==0){oldBase=null;newBase=null;}
			else{oldBase=join(a,a.length-common);newBase=join(b,b.length-common);}
		}
		private static String join(String[] parts,int n){StringBuilder sb=new StringBuilder();for(int i=0;i<n;i++){if(i>0)sb.append(File.separator);sb.append(parts[i]);}return sb.length()==0?File.separator:sb.toString();}
		String apply(String p){
			if(p==null)return null;
			if(p.equals(old))return now;
			if(oldBase==null||oldBase.equals(newBase))return p;
			String prefix=oldBase.endsWith(File.separator)?oldBase:oldBase+File.separator;
			if(!p.startsWith(prefix)||new File(p).exists())return p; // still there: it did not move
			String moved=(newBase.endsWith(File.separator)?newBase:newBase+File.separator)+p.substring(prefix.length());
			return new File(moved).exists()?identity(new File(moved)):p;
		}
	}
	/** Programs, stacks and documents together, newest first (undated entries, imported from recent.txt, last). */
	synchronized List<Item> recentItems(){
		List<Item> all=new ArrayList<Item>(recent);all.addAll(documents);
		all.sort((a,b)->Long.compare(b.lastUsed,a.lastUsed)); // stable: ties keep programs and stacks before documents
		return all;
	}
	/** The most recent stacks (for the File menu's "Open Recent Stack"). */
	synchronized List<Item> recentStacks(int max){
		List<Item> r=new ArrayList<Item>();for(Item it:recent)if(!it.isApp()){r.add(it);if(r.size()>=max)break;}return r;
	}
	synchronized void clearRecentStacks(){boolean any=recent.removeIf(it->!it.isApp());if(any)save();}

	// ------------------------------------------------------------------ dock

	// isInDock, addToDock, removeFromDock, moveInDock and dockItems act on the dock on show: the selected tab, or the first while the tabs are off
	synchronized boolean isInDock(File f){return isInDockTab(f,shownDockTab());}
	synchronized void addToDock(File f){addToDockTab(f,shownDockTab());}
	synchronized void removeFromDock(File f){removeFromDockTab(f,shownDockTab());}
	synchronized void moveInDock(File f,int index){List<String> dock=tabs.get(shownDockTab()).items;String id=identity(f);if(id==null||!dock.remove(id))return;dock.add(Math.max(0,Math.min(index,dock.size())),id);save();}
	synchronized List<File> dockItems(){return dockItems(shownDockTab());}
	synchronized List<File> dockItems(int tab){List<File> r=new ArrayList<File>();for(String p:tabs.get(tab).items)r.add(new File(p));return r;}
	/** The items of every tab, each once (programs known to the launcher: openers, settings menus). */
	synchronized List<File> allDockItems(){
		List<String> ids=new ArrayList<String>();for(DockTab t:tabs)for(String p:t.items)if(!ids.contains(p))ids.add(p);
		List<File> r=new ArrayList<File>();for(String p:ids)r.add(new File(p));return r;
	}

	// ------------------------------------------------------------------ dock tabs

	synchronized void setDockTabsShown(boolean shown){tabsShown=shown;}
	synchronized boolean dockTabsShown(){return tabsShown;}
	/** The tab whose items the dock shows. */
	synchronized int shownDockTab(){return tabsShown?selectedTab:0;}
	synchronized int selectedDockTab(){return selectedTab;}
	synchronized void selectDockTab(int tab){tab=Math.max(0,Math.min(tab,tabs.size()-1));if(tab==selectedTab)return;selectedTab=tab;save();}
	/** The tabs (a copy of the list; the tabs themselves are read only, changed through the methods here). */
	synchronized List<DockTab> dockTabs(){return new ArrayList<DockTab>(tabs);}
	/** Adds an empty tab at the end and returns its index. */
	synchronized int addDockTab(String name){tabs.add(new DockTab(name));save();return tabs.size()-1;}
	/** Deletes a tab with its items (never the last one left); the selection stays on the same tab, or the one taking the deleted one's place. */
	synchronized void removeDockTab(int tab){
		if(tabs.size()<=1||tab<0||tab>=tabs.size())return;
		tabs.remove(tab);
		if(selectedTab>tab||selectedTab>=tabs.size())selectedTab--;
		save();
	}
	/** Moves a tab to another place (its index after the move); the selected tab stays selected. */
	synchronized void moveDockTab(int from,int to){
		if(from<0||from>=tabs.size())return;to=Math.max(0,Math.min(to,tabs.size()-1));if(from==to)return;
		DockTab selected=tabs.get(selectedTab),t=tabs.remove(from);tabs.add(to,t);selectedTab=tabs.indexOf(selected);
		save();
	}
	synchronized void renameDockTab(int tab,String name){if(name==null||name.trim().isEmpty())return;tabs.get(tab).name=name.trim();save();}
	synchronized void setDockTabColor(int tab,Integer rgb){tabs.get(tab).color=rgb==null?null:rgb&0xFFFFFF;save();}
	synchronized boolean isInDockTab(File f,int tab){String id=identity(f);return id!=null&&tabs.get(tab).items.contains(id);}
	synchronized void addToDockTab(File f,int tab){List<String> dock=tabs.get(tab).items;String id=identity(MacApp68k.programOf(f));if(id==null||dock.contains(id))return;dock.add(id);save();}
	synchronized void removeFromDockTab(File f,int tab){String id=identity(f);if(id!=null&&tabs.get(tab).items.remove(id))save();}
	/**
	 * Moves an item from one tab into another at an index of that tab (past the end: last); an item already in the target
	 * tab just moves there; from = -1 (an item in no tab, a running program) only adds it. One save.
	 */
	synchronized void moveToDockTab(File f,int from,int to,int index){
		String id=identity(MacApp68k.programOf(f));if(id==null||to<0||to>=tabs.size())return;
		if(from>=0&&from<tabs.size()&&from!=to)tabs.get(from).items.remove(id);
		List<String> dock=tabs.get(to).items;dock.remove(id);
		dock.add(Math.max(0,Math.min(index,dock.size())),id);
		save();
	}

	// ------------------------------------------------------------------ per-program settings and documents

	/** This program's settings, or null when it has none. */
	synchronized AppSettings settingsFor(File app){String id=identity(app);return id==null?null:apps.get(id);}
	/** The access policy ("ask", "allow", "deny") stored for the program with this identity path, or null. */
	synchronized String accessPolicyOf(String id){AppSettings s=id==null?null:apps.get(id);return s==null?null:s.accessPolicy;}
	/** Stores only the access policy of a program (the dialog's "always" checkbox), keeping its other settings. */
	synchronized void putAccessPolicy(File app,String policy){
		String id=identity(app);if(id==null)return;
		AppSettings s=apps.get(id);if(s==null)s=new AppSettings();
		s.accessPolicy=policy;
		if(s.isEmpty())apps.remove(id);else apps.put(id,s);
		save();
	}
	/** Remembers a folder the user allowed the program to use (Sandbox's "Allow Its Folder and Below"), as "level\tpath". */
	synchronized void addGrant(File app,String grant){
		String id=identity(app);if(id==null||grant==null)return;
		AppSettings s=apps.get(id);if(s==null)s=new AppSettings();
		if(s.grants.contains(grant))return;
		s.grants.add(grant);apps.put(id,s);save();
	}
	/** Forgets one of them (Application Settings' "Remove"). */
	synchronized void removeGrant(File app,String grant){
		String id=identity(app);AppSettings s=id==null?null:apps.get(id);
		if(s==null||!s.grants.remove(grant))return;
		if(s.isEmpty())apps.remove(id);
		save();
		Sandbox.forgetGrant(app,grant);
	}
	/** Gives {@code to} (a working copy of a program) the settings of {@code from}, without the folders it was allowed to use. */
	synchronized void copySettings(File from,File to){
		AppSettings s=settingsFor(from),t;String id=identity(to);if(s==null||id==null)return;
		t=s.copy();t.grants.clear();t.readOnly=null;
		if(!t.isEmpty()){apps.put(id,t);save();}
	}
	/** Replaces this program's overrides; empty settings are dropped. */
	synchronized void putSettings(File app,AppSettings s){
		String id=identity(app);if(id==null)return;
		if(s.isEmpty())apps.remove(id);else apps.put(id,s);
		save();
	}
	/**
	 * A document the user opened in the program: to the front of the document history (listed once, with the program
	 * that opened it last), the oldest beyond MAX_RECENT dropped.
	 */
	synchronized Item recordDocument(File app,File document){
		String id=identity(app);if(id==null||document==null||!document.isFile()||isSelfExtracting(app))return null;
		String doc=identity(document);if(doc==null)return null;
		Item found=null;
		for(int i=documents.size()-1;i>=0;i--){Item it=documents.get(i);if(identity(it.file()).equals(doc)){found=it;documents.remove(i);}}
		Item item=found!=null?found:new Item(doc,KIND_DOC,0);
		item.path=doc;item.app=id;item.lastUsed=System.currentTimeMillis();
		documents.add(0,item);
		while(documents.size()>MAX_RECENT)documents.remove(documents.size()-1);
		save();return item;
	}
	/** The documents this program opened, newest first, at most MAX_DOCUMENTS (the dock's "Recent Documents"). */
	synchronized List<File> documentsOf(File app){
		String id=identity(app);if(id==null)return Collections.emptyList();
		List<File> r=new ArrayList<File>();
		for(Item it:documents)if(id.equals(it.app)){r.add(it.file());if(r.size()>=MAX_DOCUMENTS)break;}
		return r;
	}

	/** The program that opened this document last (the document history), or null. */
	synchronized File openerOf(File document){
		String id=document==null?null:identity(document);if(id==null)return null;
		for(Item it:documents)if(id.equals(it.path))return it.appFile();
		return null;
	}
	/** The programs chosen for any file type (each once). */
	synchronized List<File> chosenOpeners(){List<File> r=new ArrayList<File>();for(String p:openers.values()){File f=new File(p);if(!r.contains(f))r.add(f);}return r;}
	/** The program chosen to open documents of this file type, or null (none chosen, or the program is gone). */
	synchronized File openerFor(int fileType){
		String id=openers.get(ResourceFile68k.typeName(fileType));if(id==null)return null;
		File f=new File(id);return f.isFile()?f:null;
	}
	/** Remembers (or with null forgets) the program that opens documents of this file type. */
	synchronized void setOpenerFor(int fileType,File app){
		String key=ResourceFile68k.typeName(fileType),id=app==null?null:identity(app);
		if(id==null?openers.remove(key)==null:id.equals(openers.put(key,id)))return;
		save();
	}

	// ------------------------------------------------------------------ file

	synchronized void load(){
		loaded=true;
		recent.clear();documents.clear();apps.clear();openers.clear();
		tabs.clear();tabs.add(new DockTab(null));selectedTab=0;
		if(!file.isFile()){importRecentStacks();return;}
		Properties p=new Properties();
		try(Reader in=new InputStreamReader(Files.newInputStream(file.toPath()),StandardCharsets.UTF_8)){p.load(in);}
		catch(IOException e){System.err.println("Could not read "+file+": "+e.getMessage());return;}
		int n=intOf(p,"recent.count");
		for(int i=0;i<n;i++){
			String path=p.getProperty("recent."+i+".path");if(path==null||path.isEmpty())continue;
			String kind=KIND_APP.equals(p.getProperty("recent."+i+".kind"))?KIND_APP:KIND_STACK;
			long time=0;try{time=Long.parseLong(p.getProperty("recent."+i+".time","0"));}catch(NumberFormatException ignored){}
			if(KIND_APP.equals(kind)&&isSelfExtracting(new File(path)))continue; // recorded before they were left out
			recent.add(new Item(path,kind,time));
		}
		n=intOf(p,"doc.count");
		for(int i=0;i<n;i++){
			String path=p.getProperty("doc."+i+".path");if(path==null||path.isEmpty())continue;
			long time=0;try{time=Long.parseLong(p.getProperty("doc."+i+".time","0"));}catch(NumberFormatException ignored){}
			Item it=new Item(path,KIND_DOC,time);it.app=p.getProperty("doc."+i+".app");
			if(it.app!=null&&!it.app.isEmpty()&&!isSelfExtracting(new File(it.app)))documents.add(it);
		}
		boolean oldDocuments=p.getProperty("doc.count")==null; // written before the document history: the per-program lists seed it, undated
		// the first tab is the dock of earlier versions (dock.N); the other tabs follow as dock.tab.T.N
		n=intOf(p,"dock.count");
		List<String> dock=tabs.get(0).items;
		for(int i=0;i<n;i++){String path=p.getProperty("dock."+i);if(path!=null&&!path.isEmpty()&&!dock.contains(path))dock.add(path);}
		int tabCount=intOf(p,"dock.tab.count");
		for(int t=0;t<Math.max(1,tabCount);t++){
			DockTab tab;
			if(t==0)tab=tabs.get(0);
			else{
				tabs.add(tab=new DockTab(null));
				int m=intOf(p,"dock.tab."+t+".count");
				for(int i=0;i<m;i++){String path=p.getProperty("dock.tab."+t+"."+i);if(path!=null&&!path.isEmpty()&&!tab.items.contains(path))tab.items.add(path);}
			}
			String name=p.getProperty("dock.tab."+t+".name");if(name!=null&&!name.isEmpty())tab.name=name;
			String color=p.getProperty("dock.tab."+t+".color");if(color!=null)try{tab.color=Integer.parseInt(color,16)&0xFFFFFF;}catch(NumberFormatException ignored){}
		}
		selectedTab=Math.max(0,Math.min(intOf(p,"dock.tab.selected"),tabs.size()-1));
		n=intOf(p,"opener.count");
		for(int i=0;i<n;i++){String type=p.getProperty("opener."+i+".type"),path=p.getProperty("opener."+i+".path");if(type!=null&&type.length()==4&&path!=null&&!path.isEmpty())openers.put(type,path);}
		n=intOf(p,"app.count");
		for(int i=0;i<n;i++){
			String path=p.getProperty("app."+i+".path");if(path==null||path.isEmpty())continue;
			AppSettings s=new AppSettings();
			String v=p.getProperty("app."+i+".depth");if(v!=null)try{s.depth=Integer.valueOf(v);}catch(NumberFormatException ignored){}
			s.screenSize=p.getProperty("app."+i+".screen");
			v=p.getProperty("app."+i+".scale");if(v!=null)try{s.screenScale=Integer.valueOf(v);}catch(NumberFormatException ignored){}
			v=p.getProperty("app."+i+".screenpos");if("topleft".equals(v)||"centre".equals(v))s.screenTopLeft="topleft".equals(v);
			v=p.getProperty("app."+i+".smooth");if(v!=null){int c=SmoothZoom.parse(v,-1);if(c>=0)s.smoothZoom=c;}
			v=p.getProperty("app."+i+".wide");if(v!=null)s.wideDialogs=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".ctrlkeys");if(v!=null)s.controlKeys=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".speed");if(v!=null)s.realSpeed=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".mips");if(v!=null)try{double m=Double.parseDouble(v);if(m>0&&m<Double.POSITIVE_INFINITY)s.mips=m;}catch(NumberFormatException ignored){}
			s.gamepadMapping=p.getProperty("app."+i+".gamepad");
			v=p.getProperty("app."+i+".ppc");if(v!=null)s.powerPC=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".jit");if(v!=null)s.ppcJit=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".sysv");if(v!=null)try{s.systemVersion=Integer.parseInt(v,16);}catch(NumberFormatException ignored){}
			v=p.getProperty("app."+i+".lang");if("English".equals(v)||"Japanese".equals(v))s.language=v;
			v=p.getProperty("app."+i+".access");if("ask".equals(v)||"allow".equals(v)||"deny".equals(v))s.accessPolicy=v;
			v=p.getProperty("app."+i+".modkeys");if(v!=null&&!ModifierKeys.parse(v).isEmpty())s.modifierKeys=v;
			v=p.getProperty("app."+i+".readonly");if(v!=null)s.readOnly=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".translate");if(v!=null)s.translate=Boolean.valueOf(v);
			s.translateTo=p.getProperty("app."+i+".translate.to");s.translateFrom=p.getProperty("app."+i+".translate.from");s.translateEngine=p.getProperty("app."+i+".translate.engine");
			v=p.getProperty("app."+i+".translate.resources");if(v!=null)s.translatedResources=Boolean.valueOf(v);
			v=p.getProperty("app."+i+".translate.pictures");if(v!=null)s.translatePictures=Boolean.valueOf(v);
			int grants=intOf(p,"app."+i+".grant.count");
			for(int j=0;j<grants;j++){String g=p.getProperty("app."+i+".grant."+j);if(g!=null&&g.indexOf('\t')>0&&!s.grants.contains(g))s.grants.add(g);}
			int fonts=intOf(p,"app."+i+".font.count");
			for(int j=0;j<fonts;j++){String f=p.getProperty("app."+i+".font."+j);if(f!=null&&f.indexOf('\t')>0&&!s.fontSubstitutions.contains(f))s.fontSubstitutions.add(f);}
			if(!s.isEmpty())apps.put(path,s);
			if(oldDocuments){
				int docs=intOf(p,"app."+i+".doc.count");
				for(int j=0;j<docs;j++){
					String d=p.getProperty("app."+i+".doc."+j);if(d==null||d.isEmpty()||documents.size()>=MAX_RECENT)continue;
					boolean seen=false;for(Item it:documents)if(it.path.equals(d))seen=true;
					if(seen)continue;
					Item it=new Item(d,KIND_DOC,0);it.app=path;documents.add(it);
				}
			}
		}
	}
	/** First run with the launcher: the stacks of the older "Open Recent Stack" list (recent.txt) seed the history, undated. */
	private void importRecentStacks(){
		File old=AppPaths.recentFile();if(!old.isFile())return;
		String tmp=identity(new File(System.getProperty("java.io.tmpdir",""))); // the test suites' temporary stacks
		try{
			for(String line:Files.readAllLines(old.toPath(),StandardCharsets.UTF_8)){
				String path=line.trim();if(path.isEmpty())continue;
				if(!new File(path).exists())continue; // the old list was never pruned; only what is still there is worth carrying over
				String id=identity(new File(path));boolean seen=false;
				if(tmp!=null&&!tmp.isEmpty()&&id.startsWith(tmp+File.separator))continue;
				for(Item it:recent)if(it.path.equals(id))seen=true;
				if(!seen&&recent.size()<MAX_RECENT)recent.add(new Item(id,KIND_STACK,0));
			}
		}catch(IOException e){System.err.println("Could not read "+old+": "+e.getMessage());}
	}
	private static int intOf(Properties p,String key){try{return Integer.parseInt(p.getProperty(key,"0"));}catch(NumberFormatException e){return 0;}}

	synchronized void save(){
		if(enabled)write();
		notifyChanged();
	}
	/** Writes the file (also used by the tests, which keep {@link #enabled} off). */
	synchronized void write(){
		Properties p=new Properties();
		p.setProperty("recent.count",Integer.toString(recent.size()));
		for(int i=0;i<recent.size();i++){Item it=recent.get(i);p.setProperty("recent."+i+".path",it.path);p.setProperty("recent."+i+".kind",it.kind);p.setProperty("recent."+i+".time",Long.toString(it.lastUsed));}
		p.setProperty("doc.count",Integer.toString(documents.size()));
		for(int i=0;i<documents.size();i++){Item it=documents.get(i);p.setProperty("doc."+i+".path",it.path);p.setProperty("doc."+i+".app",it.app);p.setProperty("doc."+i+".time",Long.toString(it.lastUsed));}
		List<String> dock=tabs.get(0).items;
		p.setProperty("dock.count",Integer.toString(dock.size()));
		for(int i=0;i<dock.size();i++)p.setProperty("dock."+i,dock.get(i));
		p.setProperty("dock.tab.count",Integer.toString(tabs.size()));
		p.setProperty("dock.tab.selected",Integer.toString(selectedTab));
		for(int t=0;t<tabs.size();t++){
			DockTab tab=tabs.get(t);
			if(tab.name!=null)p.setProperty("dock.tab."+t+".name",tab.name);
			if(tab.color!=null)p.setProperty("dock.tab."+t+".color",String.format("%06x",tab.color));
			if(t==0)continue;
			p.setProperty("dock.tab."+t+".count",Integer.toString(tab.items.size()));
			for(int i=0;i<tab.items.size();i++)p.setProperty("dock.tab."+t+"."+i,tab.items.get(i));
		}
		p.setProperty("opener.count",Integer.toString(openers.size()));
		{int j=0;for(Map.Entry<String,String> e:openers.entrySet()){p.setProperty("opener."+j+".type",e.getKey());p.setProperty("opener."+j+".path",e.getValue());j++;}}
		p.setProperty("app.count",Integer.toString(apps.size()));
		int i=0;
		for(Map.Entry<String,AppSettings> e:apps.entrySet()){
			AppSettings s=e.getValue();String k="app."+i;
			p.setProperty(k+".path",e.getKey());
			if(s.depth!=null)p.setProperty(k+".depth",s.depth.toString());
			if(s.screenSize!=null)p.setProperty(k+".screen",s.screenSize);
			if(s.screenScale!=null)p.setProperty(k+".scale",s.screenScale.toString());
			if(s.screenTopLeft!=null)p.setProperty(k+".screenpos",s.screenTopLeft?"topleft":"centre");
			if(s.smoothZoom!=null)p.setProperty(k+".smooth",s.smoothZoom.toString());
			if(s.wideDialogs!=null)p.setProperty(k+".wide",s.wideDialogs.toString());
			if(s.controlKeys!=null)p.setProperty(k+".ctrlkeys",s.controlKeys.toString());
			if(s.realSpeed!=null)p.setProperty(k+".speed",s.realSpeed.toString());
			if(s.mips!=null)p.setProperty(k+".mips",mipsText(s.mips));
			if(s.gamepadMapping!=null)p.setProperty(k+".gamepad",s.gamepadMapping);
			if(s.powerPC!=null)p.setProperty(k+".ppc",s.powerPC.toString());
			if(s.ppcJit!=null)p.setProperty(k+".jit",s.ppcJit.toString());
			if(s.systemVersion!=null)p.setProperty(k+".sysv",String.format("%04x",s.systemVersion));
			if(s.language!=null)p.setProperty(k+".lang",s.language);
			if(s.accessPolicy!=null)p.setProperty(k+".access",s.accessPolicy);
			if(s.modifierKeys!=null)p.setProperty(k+".modkeys",s.modifierKeys);
			if(s.readOnly!=null)p.setProperty(k+".readonly",s.readOnly.toString());
			if(s.translate!=null)p.setProperty(k+".translate",s.translate.toString());
			if(s.translateTo!=null)p.setProperty(k+".translate.to",s.translateTo);
			if(s.translateFrom!=null)p.setProperty(k+".translate.from",s.translateFrom);
			if(s.translateEngine!=null)p.setProperty(k+".translate.engine",s.translateEngine);
			if(s.translatedResources!=null)p.setProperty(k+".translate.resources",s.translatedResources.toString());
			if(s.translatePictures!=null)p.setProperty(k+".translate.pictures",s.translatePictures.toString());
			if(!s.grants.isEmpty()){p.setProperty(k+".grant.count",Integer.toString(s.grants.size()));for(int j=0;j<s.grants.size();j++)p.setProperty(k+".grant."+j,s.grants.get(j));}
			if(!s.fontSubstitutions.isEmpty()){p.setProperty(k+".font.count",Integer.toString(s.fontSubstitutions.size()));for(int j=0;j<s.fontSubstitutions.size();j++)p.setProperty(k+".font."+j,s.fontSubstitutions.get(j));}
			i++;
		}
		File dir=file.getParentFile();if(dir!=null&&!dir.isDirectory())dir.mkdirs();
		File tmp=new File(file.getPath()+".tmp");
		try(Writer out=new OutputStreamWriter(Files.newOutputStream(tmp.toPath()),StandardCharsets.UTF_8)){p.store(out,"HyperZebra launcher");}
		catch(IOException e){System.err.println("Could not write "+file+": "+e.getMessage());return;}
		try{Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
		catch(IOException e){try{Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(IOException e2){System.err.println("Could not write "+file+": "+e2.getMessage());}}
	}
}

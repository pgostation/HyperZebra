import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The permission gate between hosted code (HyperTalk scripts, XCMDs, 68K programs) and this computer.
 *
 * System 7.5 had no such gate: a stack or a program could read, write and delete any file, open any network
 * connection and launch anything. Here every file request names who asks (the program or the stack) and what it wants,
 * at one of three levels - reading (and listing, running), changing (writing, renaming, creating) or deleting:
 * <ul>
 * <li>HyperZebra HD (the folder that holds the virtual System Folder, {@link AppPaths#stateDirectory}) is open to all.</li>
 * <li>Opening a program gives that program alone its own folder to read, its own file to change (where classic programs
 *     keep their settings) and the documents it was opened with; new files may be created beside them, and those it
 *     made itself it may change and delete. Changing or deleting anything else that was there - the user's other
 *     documents in the same folder - is asked for.</li>
 * <li>A file the user picks in the program's Open or Save dialog (or drops on it) is that program's to change.</li>
 * <li>A program can be opened read-only ({@link #readOnlyNextLaunch}, or its Application Settings): then nothing outside
 *     HyperZebra HD may be changed by it except files the user picks in its Save dialog, and it is refused without a
 *     dialog; a read-only document is shown to the program as locked.</li>
 * <li>Stacks are treated the same way, by stack: a stack's own bundle is its own, the folder around it is to read
 *     (their answers last for the session).</li>
 * </ul>
 * Answers are kept per requester: "this file only" for the session, "its folder and below" (at the level asked for)
 * also in the program's Application Settings, where the user can see and remove them ({@link LauncherStore}). Other
 * requests - the network, programs of this computer and AppleScript, the microphone - are asked for with a dialog and
 * remembered for the session. Deleted files are not lost: see {@link DeletedFiles}.
 *
 * {@code -Dhz.sandbox=allow} answers every request yes (unattended harnesses, the test suite) and {@code deny} answers
 * no; a headless run that would need a dialog is a denial. Tests plug in {@link #decider} instead of the dialog.
 */
final class Sandbox {
	/** What hosted code wants to do with a file or folder. */
	enum Access {
		READ("wants to read the file:"),WRITE("wants to write the file:"),READ_WRITE("wants to read and write the file:"),
		DELETE("wants to delete the file:"),LIST("wants to list the folder:"),EXECUTE("wants to run the program:"),
		CREATE("wants to create the file:");
		final String verb;
		Access(String verb){this.verb=verb;}
		boolean folder(){return this==LIST;}
		/** The level this needs. */
		Level level(){switch(this){case WRITE:case READ_WRITE:case CREATE:return Level.WRITE;case DELETE:return Level.DELETE;default:return Level.READ;}}
	}
	/** How far a grant goes: each level includes the ones before it. */
	enum Level{READ,WRITE,DELETE;
		boolean covers(Level need){return ordinal()>=need.ordinal();}
		/** As written in launcher.properties. */
		String key(){return name().toLowerCase(java.util.Locale.ROOT);}
		static Level parse(String s){for(Level l:values())if(l.key().equals(s))return l;return null;}
	}
	/** A request shown to the user (or to {@link #decider}). */
	static final class Request {
		final String requester,verb,subject,note;
		/** The file for a file request, null otherwise. */
		final File file;
		final Access access;
		Request(String requester,String verb,String subject,String note,File file,Access access){this.requester=requester;this.verb=verb;this.subject=subject;this.note=note;this.file=file;this.access=access;}
		@Override public String toString(){return requester+" "+verb+" "+subject;}
	}
	enum Answer{DENY,ALLOW_ONCE,ALLOW_FOLDER}
	static final String NET_CONNECT="wants to open a TCP connection to:",NET_LISTEN="wants to accept TCP connections on:",NET_UDP="wants to send and receive UDP packets on:",NET_LOOKUP="wants to look up the host name:",NET_APPLETALK="wants to join the AppleTalk network (LocalTalk over UDP) at:";
	static final String RUN_PROGRAM="wants to run a program of this computer:",RUN_APPLESCRIPT="wants to run an AppleScript:",USE_MICROPHONE="wants to record from the microphone.",OPEN_URL="wants to open in the web browser:";

	/** Replaces the dialog when set (tests); a null result means "show the dialog". */
	static volatile java.util.function.Function<Request,Answer> decider;
	private static final java.util.prefs.Preferences prefs=preferences();
	private static java.util.prefs.Preferences preferences(){try{return java.util.prefs.Preferences.userNodeForPackage(Sandbox.class);}catch(Exception e){return null;}}
	/** {@code -Dhz.sandbox=allow}: no gate at all (test suites, unattended harnesses). */
	private static final boolean ALLOW_ALL="allow".equalsIgnoreCase(System.getProperty("hz.sandbox",""));
	/** The preference: off means every request is allowed silently (as System 7.5 did). */
	static volatile boolean enabled=prefs==null||prefs.getBoolean("confirmAccess",true);
	static void setEnabled(boolean on){enabled=on;try{if(prefs!=null)prefs.putBoolean("confirmAccess",on);}catch(Exception ignored){}}

	/** One permission: a file or a folder (with everything below it) at a level, for one requester or (null) for everyone. */
	static final class Grant {
		final String scope,path;final boolean subtree;final Level level;
		Grant(String scope,String path,boolean subtree,Level level){this.scope=scope;this.path=path;this.subtree=subtree;this.level=level;}
		boolean covers(String p,String requester){return (scope==null||scope.equals(requester))&&(subtree?under(p,path):p.equals(path));}
		@Override public String toString(){return (scope==null?"*":scope)+" "+level.key()+" "+path+(subtree?"/**":"");}
	}
	private static final List<Grant> grants=new java.util.concurrent.CopyOnWriteArrayList<Grant>();
	/** Requesters running read-only: they change nothing outside HyperZebra HD but what the user picks in their Save dialog. */
	private static final Set<String> readOnly=Collections.synchronizedSet(new HashSet<String>());
	/** Documents opened read-only, by requester (canonical paths): shown as locked, never written. */
	private static final java.util.Map<String,Set<String>> readOnlyDocuments=new java.util.concurrent.ConcurrentHashMap<String,Set<String>>();
	/** Files and folders each requester created itself this session (canonical paths): its own to change and delete. */
	private static final java.util.Map<String,Set<String>> created=new java.util.concurrent.ConcurrentHashMap<String,Set<String>>();
	private static final Set<String> deniedPaths=Collections.synchronizedSet(new HashSet<String>());
	private static final Set<String> allowedHosts=Collections.synchronizedSet(new HashSet<String>()),deniedHosts=Collections.synchronizedSet(new HashSet<String>());
	private static final Set<String> allowedOther=Collections.synchronizedSet(new HashSet<String>()),deniedOther=Collections.synchronizedSet(new HashSet<String>());
	/** Programs whose next launch is read-only (identity paths; the launcher's "Open Read-Only"). */
	private static final Set<String> readOnlyLaunches=Collections.synchronizedSet(new HashSet<String>());
	/** HyperZebra HD and the Home stack shipped beside the program; found on first use so that {@code hz.state.dir} set later is honoured. */
	private static volatile List<String> builtInFolders;
	private static List<String> builtInFolders(){
		List<String> roots=builtInFolders;
		if(roots==null){
			roots=new java.util.ArrayList<String>();
			roots.add(canonical(AppPaths.stateDirectory()));
			roots.add(canonical(new File(new File(System.getProperty("user.home","."),"Library"+File.separator+"Application Support"),"HyperZebra"))); // FileSystem68k's boot volume on every platform
			File home=HomeStack.folder();if(home.isDirectory())roots.add(canonical(home));
			builtInFolders=roots;
		}
		return roots;
	}
	/** The Home stack folder was just created (HomeStack): find the built-in folders again on the next use. */
	static void forgetBuiltInFolders(){builtInFolders=null;}
	/** Whether {@code path} lies in HyperZebra HD (or another built-in folder), open to everyone. */
	static boolean isBuiltIn(File path){String p=canonical(path);for(String root:builtInFolders())if(under(p,root))return true;return false;}
	private Sandbox(){}

	// ------------------------------------------------------------------ per-program policy

	/** Requester names of stacks (their settings are Individual Stack Settings; the dialog says "this stack"). */
	private static final Set<String> stacks=Collections.synchronizedSet(new HashSet<String>());
	/** Requester name → the program's (or stack's) file, whose Application Settings hold its access policy and its saved grants. */
	private static final java.util.Map<String,File> apps=new java.util.concurrent.ConcurrentHashMap<String,File>();
	/** A Mac program starts (or a stack opens): its own access policy and saved grants apply to requests made under this name. */
	static void registerApp(String name,File file){
		if(name==null||file==null)return;
		apps.put(name,file);
		if(!LauncherStore.enabled)return;
		try{
			LauncherStore.AppSettings s=LauncherStore.get().settingsFor(file);
			if(s!=null)for(String g:s.grants){Grant grant=parseSaved(name,g);if(grant!=null)addGrant(grant);}
		}catch(RuntimeException e){e.printStackTrace();}
	}
	/** "ask", "allow" or "deny" when the requester's program has its own policy, else null (follow the preference). */
	static String policyFor(String requester){
		File app=requester==null?null:apps.get(requester);
		if(app==null||!LauncherStore.enabled)return null;
		try{return LauncherStore.get().accessPolicyOf(LauncherStore.identity(app));}catch(RuntimeException e){return null;}
	}
	/** Requester names of programs run without the user (the expanders' installers): nobody is there to answer a dialog. */
	private static final Set<String> unattended=Collections.synchronizedSet(new HashSet<String>());
	/**
	 * The program under this name runs without the user (or no longer does): what it asks for outside HyperZebra HD and the
	 * folders it was given is refused without a dialog - an installer's alias on the desktop stays out of the user's own.
	 */
	static void runsUnattended(String requester,boolean on){if(requester==null)return;if(on)unattended.add(requester);else unattended.remove(requester);}
	/** The answer settled without asking: TRUE allow, FALSE deny, null = ask. */
	private static Boolean settled(String requester){
		if(requester!=null&&unattended.contains(requester)){System.err.println("[sandbox] refused (unattended): "+requester);return Boolean.FALSE;}
		String policy=policyFor(requester);
		if("allow".equals(policy))return Boolean.TRUE;
		if("deny".equals(policy))return Boolean.FALSE;
		if(policy==null&&!enabled)return Boolean.TRUE;
		return null;
	}

	// ------------------------------------------------------------------ granting

	private static void addGrant(Grant g){for(Grant old:grants)if(old.covers(g.path,g.scope)&&java.util.Objects.equals(old.scope,g.scope)&&old.level.covers(g.level)&&(old.subtree||!g.subtree))return;grants.add(g);}
	/** Gives {@code requester} (null: everyone) {@code level} on a file, or on a folder and everything below it. */
	static void grant(String requester,File path,boolean subtree,Level level){if(path!=null)addGrant(new Grant(requester,canonical(path),subtree,level));}
	private static File folderOf(File chosen){return chosen.isDirectory()&&!isStackBundle(chosen)?chosen:chosen.getAbsoluteFile().getParentFile();}

	/**
	 * A program starts from {@code program} with {@code documents}: its folder is its own to read (new files may be made
	 * there), the program file its own to change, the documents its own to change. Read-only - this launch
	 * ({@link #readOnlyNextLaunch}) or its Application Settings - changes nothing outside HyperZebra HD.
	 */
	static void programLaunched(String requester,File program,List<File> documents){
		if(requester==null||program==null)return;
		stacks.remove(requester);
		registerApp(requester,program);
		boolean ro=readOnlyLaunches.remove(LauncherStore.identity(program))||readOnlySetting(program);
		if(ro)readOnly.add(requester);else readOnly.remove(requester);
		File folder=program.getAbsoluteFile().getParentFile();
		if(folder!=null)grant(requester,folder,true,Level.READ);
		if(!ro)grant(requester,program,false,Level.WRITE);
		if(documents!=null)for(File d:documents)chosenByUser(requester,d,false);
	}
	/** Documents to be opened read-only by the program they are handed to next (the launcher's "Open Read-Only"). */
	private static final Set<String> readOnlyDocumentsNext=Collections.synchronizedSet(new HashSet<String>());
	/** The document is opened read-only by whichever program it is handed to next. */
	static void readOnlyDocumentNext(File document){if(document!=null)readOnlyDocumentsNext.add(canonical(document));}
	/** The program's next launch is read-only (the launcher's "Open Read-Only"). */
	static void readOnlyNextLaunch(File program){if(program!=null)readOnlyLaunches.add(LauncherStore.identity(program));}
	private static boolean readOnlySetting(File program){
		if(!LauncherStore.enabled)return false;
		try{LauncherStore.AppSettings s=LauncherStore.get().settingsFor(program);return s!=null&&Boolean.TRUE.equals(s.readOnly);}catch(RuntimeException e){return false;}
	}
	/** Whether the requester runs read-only. */
	static boolean isReadOnly(String requester){return requester!=null&&readOnly.contains(requester);}

	/**
	 * The user picked {@code chosen} for {@code requester} (its Open or Save dialog, a drop, a launch with documents): the
	 * file is the requester's to change - a read-only requester only reads what it opens - and its folder to read.
	 * A picked folder (Standard File's folder choice, "answer folder") may be written into.
	 */
	static void chosenByUser(String requester,File chosen,boolean saving){
		if(chosen==null)return;
		if(chosen.isDirectory()&&!isStackBundle(chosen)){grant(requester,chosen,true,isReadOnly(requester)?Level.READ:Level.WRITE);return;}
		File folder=chosen.getAbsoluteFile().getParentFile();
		if(folder!=null)grant(requester,folder,true,Level.READ);
		if(!saving&&(isReadOnly(requester)||readOnlyDocumentsNext.remove(canonical(chosen)))){openReadOnly(requester,chosen);return;}
		grant(requester,chosen,isStackBundle(chosen),Level.DELETE);
	}
	/** A document opened read-only in the requester (the launcher's "Open Read-Only"): readable, shown as locked, never changed. */
	static void openReadOnly(String requester,File document){
		if(requester==null||document==null)return;
		grant(requester,document,false,Level.READ);
		File folder=document.getAbsoluteFile().getParentFile();if(folder!=null)grant(requester,folder,true,Level.READ);
		readOnlyDocuments.computeIfAbsent(requester,k->Collections.synchronizedSet(new HashSet<String>())).add(canonical(document));
	}
	/** Whether the requester must not change {@code path}: it runs read-only, or the document was opened read-only. */
	static boolean readOnlyFor(File path,String requester){
		if(path==null||requester==null||isBuiltIn(path))return false;
		String p=canonical(path);
		Set<String> docs=readOnlyDocuments.get(requester);
		if(docs!=null&&docs.contains(p))return true;
		if(!readOnly.contains(requester))return false;
		Level have=levelAt(p,requester);return have==null||!have.covers(Level.WRITE);
	}

	/** The user chose this file in one of HyperZebra's own dialogs: everyone may read its folder, and the file itself be used. */
	static void trust(File chosen){
		if(chosen==null)return;
		File folder=folderOf(chosen);
		if(folder!=null)grant(null,folder,true,Level.READ);
		if(!chosen.isDirectory()||isStackBundle(chosen))grant(null,chosen,isStackBundle(chosen),Level.DELETE);
	}
	static void trustAll(List<File> chosen){if(chosen!=null)for(File f:chosen)trust(f);}
	/**
	 * The folder of a stack that is open (it was chosen or allowed): a stack bundle (.hzb, or a folder holding _stack.xml)
	 * is the stack's own; any other folder is only to read.
	 */
	static void trustStackFolder(File folder){if(folder!=null)grant(null,folder,true,isStackFolder(folder)?Level.DELETE:Level.READ);}
	/**
	 * A stack opens from {@code path} (the user opened it, or a script went to it): the stack's bundle is its own, the
	 * folder around it is to read (and to make new files in), its other files are asked for - by stack, as for programs.
	 */
	static void stackOpened(File path){
		if(path==null)return;
		File f=path;if(f.getName().equals("_stack.xml")||f.getName().equals("toc.xml")){File p=f.getParentFile();if(p!=null)f=p;}
		// a stack file (.hzb ZIP): the file and its working folder are the stack's own, the folder around the file is to read
		File packed=StackBundle.isPacked(f)?f:StackBundle.packedOf(f);
		if(packed!=null){
			String requester=stackRequester(packed);
			stacks.add(requester);registerApp(requester,packed);
			File folder=StackBundle.folderOf(packed);if(folder!=null)grant(requester,folder,true,Level.DELETE);
			grant(requester,packed,false,Level.DELETE);
			File around=packed.getAbsoluteFile().getParentFile();if(around!=null)grant(requester,around,true,Level.READ);
			return;
		}
		String requester=stackRequester(f);
		// its Individual Stack Settings: the access policy, and the places allowed "and below" kept for its next opens
		if(isStackFolder(f)||f.isFile()){stacks.add(requester);registerApp(requester,f);}
		if(f.isDirectory()&&isStackFolder(f)){grant(requester,f,true,Level.DELETE);File around=f.getAbsoluteFile().getParentFile();if(around!=null)grant(requester,around,true,Level.READ);}
		else{grant(requester,f,false,Level.DELETE);File around=f.getAbsoluteFile().getParentFile();if(around!=null)grant(requester,around,true,Level.READ);}
	}
	private static boolean isStackBundle(File f){return f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb");}
	private static boolean isStackFolder(File f){return isStackBundle(f)||new File(f,"_stack.xml").isFile()||new File(f,"toc.xml").isFile();}

	/** The requester made this file or folder: its own to change and delete for the rest of the session. */
	static void created(String requester,File path){
		if(requester==null||path==null)return;
		created.computeIfAbsent(requester,k->Collections.synchronizedSet(new HashSet<String>())).add(canonical(path));
	}
	/** Whether the requester made this file itself this session (DeletedFiles deletes those at once). */
	static boolean isCreatedBy(String requester,File path){
		Set<String> mine=requester==null?null:created.get(requester);
		return mine!=null&&mine.contains(canonical(path));
	}

	/** The highest level any grant gives {@code requester} on the canonical path, or null. */
	private static Level levelAt(String p,String requester){
		Level best=null;
		for(Grant g:grants)if(g.covers(p,requester)&&(best==null||g.level.ordinal()>best.ordinal()))best=g.level;
		Set<String> mine=requester==null?null:created.get(requester);
		if(mine!=null){synchronized(mine){for(String c:mine)if(under(p,c))return Level.DELETE;}}
		return best;
	}

	// ------------------------------------------------------------------ what is allowed

	/** True when {@code path} lies in HyperZebra HD or anyone may read it (a file the user chose in HyperZebra's own dialogs). */
	static boolean isAllowed(File path){
		if(path==null)return false;
		String p=canonical(path);
		for(String root:builtInFolders())if(under(p,root))return true;
		return levelAt(p,null)!=null;
	}
	/** True when {@code requester} may use {@code path} at {@code level} without asking. */
	static boolean isAllowed(File path,Level level,String requester){
		if(path==null)return false;
		String p=canonical(path);
		for(String root:builtInFolders())if(under(p,root))return true;
		Level have=levelAt(p,requester);
		return have!=null&&have.covers(level);
	}
	private static boolean under(String path,String root){return path.equals(root)||path.startsWith(root.endsWith(File.separator)?root:root+File.separator);}
	static String canonical(File f){try{return f.getCanonicalPath();}catch(IOException e){return f.getAbsolutePath();}}

	// ------------------------------------------------------------------ requests

	/** Whether {@code path} may be used without asking: for lookups the program did not ask for by name (a desktop database
	 *  search walks the volumes), which must not raise a permission dialog. */
	static boolean allowedWithoutAsking(File path){return path!=null&&(!enabled||ALLOW_ALL||isAllowed(path));}
	static boolean allowedWithoutAsking(File path,String requester){return allowedWithoutAsking(path,Level.READ,requester);}
	/** Whether {@code requester} may use {@code path} at {@code level} with no dialog (its policy or the preference may also allow it). */
	static boolean allowedWithoutAsking(File path,Level level,String requester){
		if(path==null)return false;
		if(!enabled&&!isReadOnly(requester)||ALLOW_ALL||isAllowed(path,level,requester))return true;
		return "allow".equals(policyFor(requester))&&!readOnlyFor(path,requester);
	}
	/** The folders programs may use without asking (HyperZebra HD and the other built-in folders, the folders the user chose). */
	static List<File> allowedRoots(){return allowedRoots(null);}
	/** The folders {@code requester} may read without asking. */
	static List<File> allowedRoots(String requester){
		List<File> roots=new java.util.ArrayList<File>();for(String r:builtInFolders())roots.add(new File(r));
		for(Grant g:grants)if(g.subtree&&(g.scope==null||g.scope.equals(requester))){File f=new File(g.path);if(!roots.contains(f))roots.add(f);}
		return roots;
	}
	/**
	 * Whether {@code canonicalPath} is the application file of a program running in the VM: the user started it, and on a Mac
	 * every program may read another one's resources - AppleScript opens the target's file for its dictionary ('aete') when it
	 * compiles a tell block, and the Script Editor waited on a permission dialog for every application it scripted.
	 */
	private static boolean runningProgramFile(String canonicalPath){
		for(File f:runningPrograms.get())if(canonical(f).equals(canonicalPath))return true;
		return false;
	}
	/** The application files of the programs running in the VM (replaceable by tests). */
	static volatile java.util.function.Supplier<List<File>> runningPrograms=()->{
		List<File> files=new java.util.ArrayList<File>();
		for(MacApp68k app:MacApp68k.runningApplications())if(app.file!=null&&!app.finished)files.add(app.file);
		return files;};
	/** Asks for {@code access} to {@code path} unless it is already allowed; false when the user (or the policy) says no. */
	static boolean allowFile(File path,Access access,String requester){
		if(path==null)return false;
		if(ALLOW_ALL)return true;
		String p=canonical(path);
		File subject=new File(p);
		for(String root:builtInFolders())if(under(p,root))return true;
		if(access==Access.READ&&runningProgramFile(p))return true;
		// writing a file that is not there yet makes a new one
		if((access==Access.WRITE||access==Access.READ_WRITE)&&!subject.exists())access=Access.CREATE;
		Level need=access.level(),have=levelAt(p,requester);
		if(have!=null&&have.covers(need))return true;
		boolean ro=readOnlyFor(subject,requester);
		// a new file beside the ones it may read: the program's (or stack's) own from now on - nothing that was there changes
		if(access==Access.CREATE&&have!=null&&!ro){created(requester,subject);return true;}
		if(ro&&need!=Level.READ){System.err.println("[sandbox] refused (read-only): "+requester+" "+access+" "+p);return false;}
		Boolean settled=settled(requester);
		if(settled!=null){if(settled&&access==Access.CREATE)created(requester,subject);return settled;}
		String deniedKey=requester+"\0"+need+"\0"+p;
		if(deniedPaths.contains(deniedKey))return false;
		String note=have!=null?"It may read this place but not change it.":"It is outside HyperZebra HD and the folders of the files you opened.";
		Answer a=decide(new Request(requester,access.verb,path.getAbsolutePath(),note,subject,access)); // shown as asked for, decided on the canonical path
		switch(a){
			case ALLOW_FOLDER:{
				File folder=access.folder()||subject.isDirectory()&&!isStackBundle(subject)?subject:subject.getAbsoluteFile().getParentFile();
				if(folder!=null){grant(requester,folder,true,need);remember(requester,folder,need);}
				if(access==Access.CREATE)created(requester,subject);
				return true;
			}
			case ALLOW_ONCE:grant(requester,subject,isStackBundle(subject),need);if(access==Access.CREATE)created(requester,subject);return true;
			default:deniedPaths.add(deniedKey);return false;
		}
	}
	/** "Its folder and below" for a program (or stack) with Application Settings: kept there for its next runs. */
	private static void remember(String requester,File folder,Level level){
		File app=requester==null?null:apps.get(requester);
		if(app==null||!LauncherStore.enabled)return;
		try{LauncherStore.get().addGrant(app,level.key()+"\t"+canonical(folder));}catch(RuntimeException e){e.printStackTrace();}
	}
	/** A saved grant ("level\tpath") as a grant for {@code requester}, or null. */
	static Grant parseSaved(String requester,String saved){
		int tab=saved==null?-1:saved.indexOf('\t');if(tab<0)return null;
		Level level=Level.parse(saved.substring(0,tab));String path=saved.substring(tab+1);
		return level==null||path.isEmpty()?null:new Grant(requester,path,true,level);
	}
	/** Drops the grants a program's settings no longer list (Application Settings removed one). */
	static void forgetGrant(File app,String saved){
		Grant gone=null;
		for(java.util.Map.Entry<String,File> e:apps.entrySet())if(LauncherStore.identity(e.getValue()).equals(LauncherStore.identity(app))){gone=parseSaved(e.getKey(),saved);if(gone==null)continue;
			for(Grant g:grants)if(java.util.Objects.equals(g.scope,gone.scope)&&g.path.equals(gone.path)&&g.subtree&&g.level==gone.level)grants.remove(g);}
	}
	/** {@link #allowFile} for code that reports failures as exceptions: the File Manager turns it into permErr. */
	static void checkFile(File path,Access access,String requester){
		if(!allowFile(path,access,requester))throw new SecurityException("access to "+path+" not allowed");
	}
	/** A network use, {@code verb} saying which (one of the NET_ texts); a host once allowed stays allowed, port or not. */
	static boolean allowNetwork(String requester,String verb,String host,int port){
		Boolean settled=settled(requester);
		if(settled!=null)return settled;
		String key=host==null?"":host.toLowerCase(java.util.Locale.ROOT);
		if(allowedHosts.contains(key))return true;
		if(deniedHosts.contains(key))return false;
		String subject=port>0?(host==null||host.isEmpty()?"port "+port:host+":"+port):host;
		Answer a=decide(new Request(requester,verb,subject,null,null,null));
		if(a==Answer.DENY){deniedHosts.add(key);return false;}
		allowedHosts.add(key);return true;
	}
	/** Records that {@code name} resolved to these addresses: a host allowed by name is allowed by address too. */
	static void hostResolved(String name,String[] addresses){
		if(name==null)return;String key=name.toLowerCase(java.util.Locale.ROOT);
		if(allowedHosts.contains(key))for(String a:addresses)allowedHosts.add(a.toLowerCase(java.util.Locale.ROOT));
	}
	/** Something other than a file or the network: a program of this computer, an AppleScript, the microphone. */
	static boolean allowOther(String requester,String verb,String detail){
		Boolean settled=settled(requester);
		if(settled!=null)return settled;
		String key=requester+"\0"+verb+"\0"+detail;
		if(allowedOther.contains(key))return true;
		if(deniedOther.contains(key))return false;
		Answer a=decide(new Request(requester,verb,detail,null,null,null));
		if(a==Answer.DENY){deniedOther.add(key);return false;}
		allowedOther.add(key);return true;
	}

	/** The current stack, for requests made by HyperTalk. */
	static String scriptRequester(){
		try{
			OStack stack=PCARD.pc==null?null:PCARD.pc.stack;
			if(stack!=null&&stack.file!=null)return stackRequester(stack.file);
		}catch(RuntimeException ignored){}
		return "HyperTalk";
	}
	/** The requester name of the stack at {@code f} (its bundle, _stack.xml or HyperCard file): the name without .hzb. */
	static String stackRequester(File f){
		f=StackBundle.host(f);
		if(f.getName().equals("_stack.xml")||f.getName().equals("toc.xml")){File p=f.getParentFile();if(p!=null)f=p;}
		return f.getName().replaceFirst("(?i)\\.hzb$","");
	}

	// ------------------------------------------------------------------ the dialog

	private static Answer decide(Request r){
		String policy=System.getProperty("hz.sandbox","ask");
		if(policy.equalsIgnoreCase("allow"))return Answer.ALLOW_FOLDER;
		if(policy.equalsIgnoreCase("deny"))return Answer.DENY;
		java.util.function.Function<Request,Answer> d=decider;
		if(d!=null){Answer a=d.apply(r);if(a!=null)return a;}
		if(java.awt.GraphicsEnvironment.isHeadless()){System.err.println("[sandbox] denied (headless): "+r);return Answer.DENY;}
		final Answer[] answer={Answer.DENY};
		Runnable show=new Runnable(){@Override public void run(){answer[0]=showDialog(r);}};
		if(SwingUtilities.isEventDispatchThread())show.run();
		else try{SwingUtilities.invokeAndWait(show);}catch(Exception e){e.printStackTrace();}
		return answer[0];
	}

	private static Answer showDialog(Request r){
		StringBuilder text=new StringBuilder();
		text.append(International.t("\"{0}\" {1}", r.requester, text(r.verb))).append("\n\n").append(wrap(r.subject));
		if(r.note!=null)text.append("\n\n").append(text(r.note));
		String deny=text("Don't Allow"),once=text(r.access==null?"Allow":r.access.folder()?"Allow This Folder Only":"Allow This File Only"),folder=text(r.access==null?null:r.access.folder()?"Allow the Folder and Below":"Allow Its Folder and Below");
		String[] options=r.access==null?new String[]{deny,once}:new String[]{deny,once,folder};
		File app=apps.get(r.requester);
		javax.swing.JCheckBox always=app==null||!LauncherStore.enabled?null:new javax.swing.JCheckBox(text(stacks.contains(r.requester)?"Always use this answer for this stack":"Always use this answer for this program"));
		Object message=always==null?text.toString():new Object[]{text.toString(),always};
		int choice=JOptionPane.showOptionDialog(PCARD.pc,message,text("Access Request"),JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,options,deny);
		if(always!=null&&always.isSelected()&&choice>=0){try{LauncherStore.get().putAccessPolicy(app,choice==0?"deny":"allow");}catch(RuntimeException e){e.printStackTrace();}}
		if(choice==1)return Answer.ALLOW_ONCE;
		if(choice==2)return Answer.ALLOW_FOLDER;
		return Answer.DENY;
	}
	private static String text(String english){
		if(english==null)return null;
		try{if(PCARD.pc!=null&&PCARD.pc.intl!=null)return PCARD.pc.intl.getDialogText(english);}catch(RuntimeException ignored){}
		return english;
	}
	/** Long paths and scripts get line breaks so the dialog stays narrow. */
	private static String wrap(String s){
		if(s==null)return "";
		StringBuilder out=new StringBuilder();int column=0;
		for(int i=0;i<s.length();i++){char c=s.charAt(i);out.append(c);column=c=='\n'?0:column+1;if(column>=72&&i+1<s.length()){out.append('\n');column=0;}}
		return out.length()>1200?out.substring(0,1200)+"…":out.toString();
	}
}

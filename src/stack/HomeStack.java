import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * The Home stack is distributed as Support Files/home.hzb, without HyperCard resources. It is only a template - the
 * Home the user sees and edits is unpacked into the state folder - so it stays out of the distribution folder's top
 * level. Support Files/sampleHomeApp supplies resources only, never cards or scripts. The distributed archive stays clean.
 * The legacy Home stack folder ("home" beside the program, a folder stack with _stack.xml) is also supported. When
 * no template is available, the user can put a HyperCard Player distribution beside the program
 * (a folder named "HyperCard Player 2.4.1", or any "HyperCard Player…" folder, with its "Home" stack inside: a real
 * resource fork, an AppleDouble "._Home" sidecar or a MacBinary "Home.bin"). At start-up, when "home" is missing, that
 * Home stack is converted into it the way File > Open converts a HyperCard stack - the cards and scripts of the data
 * fork, and every resource of the resource fork as one file each - without dialogs or progress bar. The Home stack of
 * a Player holds almost nothing itself; the standard icons (ICON 1000…), sounds (Boing, Harpsichord, Flute, …),
 * patterns and cursors that stacks borrow through HyperCard's resource chain (stack, Home, the application, the System)
 * live in the "HyperCard Player" application beside it, so those types are taken from the application as well, behind
 * the Home stack's own resources (one Home already has is not replaced). The conversion runs in a scratch folder which
 * becomes "home" only when it is complete; a failed attempt leaves nothing behind.
 */
final class HomeStack {
	private HomeStack(){}

	/** The Home stack beside the program (the project tree: "./home"). */
	static final File FOLDER = new File("home");

	private static volatile File folder;

	/**
	 * Where the Home stack lives: "home" beside the program when there is one (the project tree, or a folder the user
	 * put there), otherwise "home" in HyperZebra's state folder. A packaged app converts into the state folder even
	 * when its own folder looks writable: the app bundle is signed and "Program Files" is not the user's to write to.
	 */
	static File folder(){
		File found = folder;
		if(found!=null) return found;
		File beside = FOLDER, inState = new File(AppPaths.stateDirectory(), "home");
		if(bundledHome().isFile()) found = inState;
		else if(exists(beside)) found = beside;
		else if(exists(inState)) found = inState;
		else if(System.getProperty("jpackage.app-path")!=null) found = inState;
		else found = java.nio.file.Files.isWritable(beside.getAbsoluteFile().getParentFile().toPath()) ? beside : inState;
		folder = found;
		return found;
	}

	/** The Home stack folder was just made (or removed): find it again on the next use. */
	static void forgetFolder(){ folder = null; }

	/** True when the Home stack folder is usable (has its _stack.xml). */
	static boolean exists(){ return exists(folder()); }
	private static boolean exists(File folder){ return new File(folder, "_stack.xml").isFile() || new File(folder, "toc.xml").isFile(); }

	/**
	 * Makes sure the Home stack folder exists: nothing when it does, otherwise converts the Home stack of a HyperCard
	 * Player distribution found beside the program. True when the folder is usable afterwards.
	 */
	static boolean ensure(){
		if(exists()) return true;
		File bundled = bundledHome();
		if(bundled.isFile()) return install(bundled, sampleApplication(), folder());
		File source = playerHome();
		if(source==null) return false;
		File target = folder();
		boolean made = ensure(source, target);
		if(made) forgetFolder();
		return made;
	}

	static File bundledHome(){ return new File(AppPaths.distributionDirectory(), "Support Files/home.hzb"); }
	static File sampleApplication(){ return new File(AppPaths.distributionDirectory(), "Support Files/sampleHomeApp"); }

	/** Unpack the clean Home template and extract Player resources only into the user's writable copy. */
	static boolean install(File source, File application, File target){
		if(exists(target)) return true;
		File scratch = new File(target.getAbsoluteFile().getParentFile(), target.getName()+".installing");
		deleteTree(scratch);
		try {
			Files.createDirectories(scratch.toPath());
			try(java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source)){
				java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
				while(entries.hasMoreElements()){
					java.util.zip.ZipEntry entry = entries.nextElement();
					java.nio.file.Path destination = scratch.toPath().resolve(entry.getName()).normalize();
					if(!destination.startsWith(scratch.toPath())) throw new IOException("Invalid Home entry: "+entry.getName());
					if(entry.isDirectory()) Files.createDirectories(destination);
					else {
						Files.createDirectories(destination.getParent());
						try(java.io.InputStream input = zip.getInputStream(entry)){ Files.copy(input, destination); }
					}
				}
			}
			if(!exists(scratch)) return false;
			OStack stack = StackTranslation.read(scratch);
			if(stack==null || stack.cdCacheList.isEmpty()) return false;
			byte[] resources = application.isFile() ? HCConvert.getResourceForkBytes(application.getPath()) : null;
			if(resources!=null && resources.length>0){
				if(!HCResource.readResourceFork(resources, stack, APPLICATION_TYPES)) return false;
				if(!HCData.waitForPictures(60_000L)) return false;
				XMLwrite.writeOnce(stack);
			}
			deleteTree(target);
			if(!scratch.renameTo(target)) return false;
			forgetFolder();
			Sandbox.forgetBuiltInFolders();
			return true;
		} catch(IOException e){
			System.err.println("Home stack: could not install "+source+": "+e.getMessage());
			return false;
		} finally { deleteTree(scratch); }
	}

	static boolean ensure(File source, File folder){
		if(exists(folder)) return true;
		System.out.println("Home stack: converting "+source.getPath()+" into "+folder.getPath());
		File scratch = new File(folder.getAbsoluteFile().getParentFile(), folder.getName()+".converting");
		deleteTree(scratch);
		if(!scratch.mkdirs()){
			System.err.println("Home stack: could not create "+scratch.getPath());
			return false;
		}
		boolean ok = false;
		try {
			ok = convert(source, playerApplication(source), scratch, encodingFor(source));
		} catch (RuntimeException e) {
			System.err.println("Home stack: conversion failed: "+e);
			e.printStackTrace();
		}
		if(ok){
			deleteTree(folder); //a half folder without _stack.xml is worth nothing
			ok = scratch.renameTo(folder) && exists(folder);
			if(ok) Sandbox.forgetBuiltInFolders(); //the new folder is one of the built-in ones
		}
		if(!ok) deleteTree(scratch);
		return ok;
	}

	/**
	 * The Home stack of a HyperCard Player distribution beside the program: in a "HyperCard Player…" folder of the
	 * working directory (a packaged app), of its parent (the project tree) or of "resource". "Home" with its resource
	 * fork (native or a "._Home" sidecar) is preferred, then a MacBinary "Home.bin". Null when there is none.
	 */
	static File playerHome(){ return playerHome(AppPaths.distributionDirectory(), new File("."), new File(".."), new File("resource")); }
	/** As above, looking in the given folders in turn (the tests point it at a made-up distribution). */
	static File playerHome(File... bases){
		for(File base: bases){
			File[] folders = base.listFiles((d,n)->n.toLowerCase(java.util.Locale.ROOT).startsWith("hypercard player") && new File(d,n).isDirectory());
			if(folders==null) continue;
			java.util.Arrays.sort(folders);
			for(File folder: folders){
				File home = homeIn(folder);
				if(home!=null) return home;
			}
		}
		return null;
	}
	private static File homeIn(File folder){
		for(String name: new String[]{"Home", "Home.bin", "Home.hqx"}){
			File f = new File(folder, name);
			if(!f.isFile()) continue;
			if(name.endsWith(".hqx")) continue; //BinHex is not decoded here
			if(isStack(f)) return f;
		}
		return null;
	}

	/**
	 * The HyperCard application in the Home stack's folder ("HyperCard Player", "HyperCard", "HyperCard Player.bin"…): a
	 * file with CODE resources, other than a stack, whose name starts with "HyperCard"; the MacBinary form is used when
	 * there is no native or sidecar resource fork. Null when there is none.
	 */
	static File playerApplication(File home){
		File folder = home.getAbsoluteFile().getParentFile();
		File[] files = folder==null ? null : folder.listFiles((d,n)->n.toLowerCase(java.util.Locale.ROOT).startsWith("hypercard") && new File(d,n).isFile());
		if(files==null) return null;
		java.util.Arrays.sort(files);
		File macBinary = null;
		for(File f: files){
			if(f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".bin")){
				if(macBinary==null && applicationForkOfMacBinary(f)!=null) macBinary = f;
				continue;
			}
			if(!MacApp68k.isApplication(f)) continue;
			byte[] fork = HCConvert.getResourceForkBytes(f.getPath());
			if(fork!=null && fork.length>0) return f;
		}
		return macBinary;
	}
	/** The resource fork of the application, from its native fork or sidecar, or out of a MacBinary file. */
	static byte[] applicationFork(File app){
		if(app==null) return null;
		if(app.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".bin")) return applicationForkOfMacBinary(app);
		return HCConvert.getResourceForkBytes(app.getPath());
	}
	private static byte[] applicationForkOfMacBinary(File f){
		try {
			byte[] all = Files.readAllBytes(f.toPath());
			if(all.length<128 || all[65]!='A' || all[66]!='P' || all[67]!='P' || all[68]!='L') return null;
			long dataLength = unsignedInt(all, 83), resourceLength = unsignedInt(all, 87);
			long resourceOffset = 128L+((dataLength+127L)/128L)*128L;
			if(resourceLength<=0 || resourceOffset+resourceLength>all.length) return null;
			return java.util.Arrays.copyOfRange(all, (int)resourceOffset, (int)(resourceOffset+resourceLength));
		} catch (IOException e) { return null; }
	}

	/** The resource types stacks borrow from the HyperCard application: icons, sounds, cursors, patterns and pictures. */
	static final java.util.Set<String> APPLICATION_TYPES = java.util.Set.of("ICON", "cicn", "snd ", "CURS", "crsr", "PAT#", "ppat", "PICT");

	/** Whether a file is a HyperCard stack (a "STAK" data fork, a PowerPC "HCSA" header in front of one, or MacBinary around one). */
	static boolean isStack(File f){
		try {
			byte[] head = readHead(f, 128);
			return head!=null && kindOf(head)!=0;
		} catch (IOException e) { return false; }
	}
	/** 1 = plain data fork, 2 = MacBinary, 0 = not a stack. */
	private static int kindOf(byte[] head){
		if(head.length>=8 && head[4]=='S' && head[5]=='T' && head[6]=='A' && head[7]=='K') return 1;
		if(head.length>=8 && head[0]=='H' && head[1]=='C' && head[2]=='S' && head[3]=='A') return 1;
		if(head.length>=128 && head[65]=='S' && head[66]=='T' && head[67]=='A' && head[68]=='K') return 2;
		return 0;
	}
	private static byte[] readHead(File f, int n) throws IOException {
		try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
			byte[] b = in.readNBytes(n);
			return b.length==0 ? null : b;
		}
	}

	/** The text encoding of a Player's Home: Japanese for a Japanese Player ("HyperCard Player J-2.4.1" and the like), Roman otherwise. */
	static StackTextEncoding encodingFor(File source){
		String folder = source.getParentFile()==null ? "" : source.getParentFile().getName();
		if(folder.matches("(?i).*\\bJ-?\\d.*") || folder.matches(".*[\\u3040-\\u30FF\\u4E00-\\u9FFF].*")) return StackTextEncoding.JAPANESE;
		return StackTextEncoding.ROMAN;
	}

	/**
	 * Converts a HyperCard stack file into {@code folder} (which must exist and be empty) as a folder stack: the data
	 * fork's cards, backgrounds, pictures and scripts, then every resource of the resource fork, then _stack.xml. No
	 * window, dialog or progress bar is involved. True when the folder holds a readable stack afterwards.
	 */
	static boolean convert(File source, File folder, StackTextEncoding encoding){ return convert(source, null, folder, encoding); }
	/** As above, then the {@link #APPLICATION_TYPES} of the HyperCard application {@code app} (may be null) that Home lacks. */
	static boolean convert(File source, File app, File folder, StackTextEncoding encoding){
		byte[] data, resources;
		try {
			byte[] all = Files.readAllBytes(source.toPath());
			int kind = kindOf(all);
			if(kind==2){
				long dataLength = unsignedInt(all, 83), resourceLength = unsignedInt(all, 87);
				long resourceOffset = 128L+((dataLength+127L)/128L)*128L;
				if(dataLength<=0 || resourceOffset+resourceLength>all.length){ System.err.println("Home stack: truncated MacBinary "+source); return false; }
				data = java.util.Arrays.copyOfRange(all, 128, (int)(128+dataLength));
				resources = resourceLength==0 ? null : java.util.Arrays.copyOfRange(all, (int)resourceOffset, (int)(resourceOffset+resourceLength));
			}
			else if(kind==1){
				data = all;
				resources = HCConvert.getResourceForkBytes(source.getPath());
			}
			else { System.err.println("Home stack: not a HyperCard stack: "+source); return false; }
		} catch (IOException e) {
			System.err.println("Home stack: could not read "+source+": "+e.getMessage());
			return false;
		}

		OStack stack = new OStack(null);
		stack.file = new File(folder, "_stack.xml");
		stack.path = stack.file.getPath();
		stack.scriptList = new ArrayList<String>();
		stack.name = "Home";
		stack.legacyTextEncoding = encoding;
		StackTextEncoding before = HCData.currentEncoding;
		HCData.setCurrentEncoding(encoding);
		try {
			if(!HCData.readDataFork(new DataInputStream(new ByteArrayInputStream(data)), stack) || stack.cdCacheList.isEmpty()){
				System.err.println("Home stack: could not read the data fork of "+source);
				return false;
			}
			if(resources!=null && !HCResource.readResourceFork(resources, stack))
				System.err.println("Home stack: some resources of "+source+" could not be converted");
			byte[] appFork = applicationFork(app);
			if(appFork!=null){
				System.out.println("Home stack: taking icons, sounds, cursors, patterns and pictures from "+app.getPath());
				if(!HCResource.readResourceFork(appFork, stack, APPLICATION_TYPES))
					System.err.println("Home stack: some resources of "+app+" could not be converted");
			}
		} finally {
			HCData.setCurrentEncoding(before);
		}
		//the card and background pictures are decoded and written by other threads
		if(!HCData.waitForPictures(60_000L)) System.err.println("Home stack: pictures of "+source+" were still being written");
		XMLwrite.writeOnce(stack);
		return stack.file.isFile();
	}

	//---- HyperCard's "User Preferences" and search path cards ----

	/** The card HyperCard's Home keeps the user's name and level on; stacks read it ("GenChart", "Cipher": getHomeInfo). */
	static final String PREFERENCES_CARD = "User Preferences";
	/** The search path cards of HyperCard's Home (background "Paths", background field "Paths" on each). */
	static final String[] PATH_CARDS = {"Stacks", "Applications", "Documents"};

	/** Whether a stack file (its _stack.xml or folder) is the Home stack. */
	static boolean isHomeFile(String path){
		if(path==null) return false;
		String id = LauncherStore.identity(new File(path)), home = LauncherStore.identity(folder());
		return id!=null && id.equals(home);
	}

	/**
	 * HyperCard 1.x/2.x Home has a "User Preferences" card (card field "User Name", hidden card field "User Level",
	 * card buttons "Power Keys", "Text Arrows", "Blind Typing") and search path cards "Stacks", "Applications" and
	 * "Documents" (background field "Paths"); stacks carry a copy of Home's getHomeInfo that goes there
	 * (go to card "User Preferences" of stack "Home" / put card field "User Name" into userName / set userLevel to
	 * card field "User Level" / put field "paths" of card "stacks" into stacks). A Home without them (HyperZebra's own)
	 * gets HyperCard-style ones after its last card, User Preferences last as in HyperCard, in memory: they reach the file with the next save of Home (the
	 * user typing a name, for one). A Home that has them (a HyperCard Player's) is left alone. Button ids follow
	 * HyperCard 2.x Home (Power Keys = card button id 3, arrow keys in text = id 2, Blind Typing =
	 * id 1), the names HyperCard 1.x Home.
	 */
	static void addPreferenceCards(OStack st){
		if(st==null || st.cdCacheList.isEmpty()) return;
		boolean japanese = st.legacyTextEncoding==StackTextEncoding.JAPANESE;
		int w = st.width>0 ? st.width : 512, h = st.height>0 ? st.height : 342;
		try {
			OCard prefs = cardNamed(st, PREFERENCES_CARD);
			if(prefs!=null) repairPreferenceIds(prefs);
			OBackground paths = null;
			for(OBackground bg: st.bgCacheList) if("Paths".equalsIgnoreCase(bg.name) && bg.GetFld("Paths")!=null) paths = bg;
			for(int i=0; i<PATH_CARDS.length; i++){
				if(cardNamed(st, PATH_CARDS[i])!=null) continue;
				if(paths==null) paths = readBackground(st, pathsBackgroundXml(st.newCardOrBgId(), w, h));
				readCard(st, pathsCardXml(st.newCardOrBgId(), paths, PATH_CARDS[i], japanese ? JAPANESE_PATH_TITLES[i] : ENGLISH_PATH_TITLES[i]));
			}
			if(prefs==null){
				OBackground bg = readBackground(st, preferencesBackgroundXml(st.newCardOrBgId(), w, h));
				readCard(st, preferencesCardXml(st.newCardOrBgId(), bg.id, japanese));
			}
			else movePreferencesLast(st, prefs);
		} catch (Exception e) {
			System.err.println("Home stack: could not add the User Preferences card: "+e);
		}
	}

	/**
	 * User Preferences is the last card of HyperCard's Home, after Stacks, Applications and Documents (HyperCard J-2.3's
	 * Home): Type It 2.3 reads card field "User Name" of last card there. Older HyperZebra put the search path cards after
	 * it; a User Preferences card followed by those cards only goes to the end. Any other order (the user's own cards after
	 * it) is left alone.
	 */
	private static void movePreferencesLast(OStack st, OCard prefs){
		int at = st.cardIdList.indexOf(prefs.id);
		if(at<0 || at==st.cardIdList.size()-1) return;
		for(int i=at+1; i<st.cardIdList.size(); i++){
			String name = null;
			for(OCard cd: st.cdCacheList) if(cd.id==st.cardIdList.get(i)) name = cd.name;
			boolean pathCard = false;
			for(String p: PATH_CARDS) pathCard |= p.equalsIgnoreCase(name);
			if(!pathCard) return;
		}
		st.cardIdList.remove(at);
		st.cardIdList.add(prefs.id);
	}

	/** Blind Typing is card button id 1 (HyperCard 2.x Home's getHomeInfo reads the hilite of cd btn id 1), so the User
	 *  Name field is id 4 */
	static final int BLIND_TYPING_ID = 1, USER_NAME_ID = 4;

	/** A User Preferences card this class made before Blind Typing was id 1 (User Name field 1, Blind Typing 4): the two
	 *  swap ids. Any other card (a HyperCard Home's) is left alone. */
	private static void repairPreferenceIds(OCard prefs){
		OButton blind = prefs.GetBtn("Blind Typing");
		OField name = prefs.GetFld("User Name");
		if(blind==null || name==null || blind.id!=USER_NAME_ID || name.id!=BLIND_TYPING_ID) return;
		name.id = USER_NAME_ID;
		blind.id = BLIND_TYPING_ID;
	}

	private static final String[] ENGLISH_PATH_TITLES = {"Where to look for stacks", "Where to look for applications", "Where to look for documents"};
	private static final String[] JAPANESE_PATH_TITLES = {"スタックの検索パス", "アプリケーションの検索パス", "書類の検索パス"};

	static OCard cardNamed(OStack st, String name){
		for(OCard cd: st.cdCacheList) if(cd.name!=null && cd.name.equalsIgnoreCase(name)) return cd;
		return null;
	}

	private static OBackground readBackground(OStack st, String xml) throws Exception {
		OBackground bg = new OBackground(st);
		bg.readXML(elementReader(xml, "background"));
		st.AddNewBg(bg);
		return bg;
	}
	private static OCard readCard(OStack st, String xml) throws Exception {
		OCard cd = new OCard(st); //joins st.cdCacheList
		try {
			cd.readXML(elementReader(xml, "card"));
		} catch (Exception e) {
			st.cdCacheList.remove(cd);
			throw e;
		}
		st.AddNewCard(cd.id);
		return cd;
	}
	private static javax.xml.stream.XMLStreamReader elementReader(String xml, String element) throws Exception {
		javax.xml.stream.XMLStreamReader reader = javax.xml.stream.XMLInputFactory.newInstance().createXMLStreamReader(new java.io.StringReader(xml));
		while(reader.hasNext()){
			if(reader.next()==javax.xml.stream.XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals(element)) return reader;
		}
		throw new IOException("no "+element);
	}

	private static String preferencesBackgroundXml(int id, int w, int h){
		StringBuilder x = new StringBuilder("<background><id>").append(id).append("</id><bitmap></bitmap>");
		x.append("<cantDelete><false/></cantDelete><showPict><true/></showPict><dontSearch><false/></dontSearch>");
		arrowButtons(x, 1, w, h);
		return x.append("<name>User Preferences</name><script></script></background>").toString();
	}
	private static String pathsBackgroundXml(int id, int w, int h){
		StringBuilder x = new StringBuilder("<background><id>").append(id).append("</id><bitmap></bitmap>");
		x.append("<cantDelete><false/></cantDelete><showPict><true/></showPict><dontSearch><false/></dontSearch>");
		field(x, 1, "Title", 20, 14, w-20, 40, "transparent", true, true, true, 18, "bold", "");
		field(x, 2, "Paths", 20, 52, w-20, h-56, "scrolling", false, true, false, 12, "plain", "");
		arrowButtons(x, 3, w, h);
		return x.append("<name>Paths</name><script></script></background>").toString();
	}
	private static void arrowButtons(StringBuilder x, int firstId, int w, int h){
		button(x, firstId, "←", w-96, h-44, w-58, h-16, "standard", 0, false, true, "on mouseUp\n  go prev\nend mouseUp");
		button(x, firstId+1, "→", w-54, h-44, w-16, h-16, "standard", 0, false, true, "on mouseUp\n  go next\nend mouseUp");
	}

	private static String preferencesCardXml(int id, int bgId, boolean japanese){
		int level = 5; //HyperZebra's preference level
		StringBuilder x = new StringBuilder("<card><id>").append(id).append("</id><bitmap></bitmap>");
		x.append("<cantDelete><true/></cantDelete><showPict><true/></showPict><dontSearch><false/></dontSearch>");
		x.append("<background>").append(bgId).append("</background>");
		field(x, USER_NAME_ID, "User Name", 140, 56, 420, 78, "rectangle", false, true, true, 12, "plain",
			"on closeField\n  global UserName\n  put me into UserName\nend closeField\n\non exitField\n  closeField\nend exitField");
		button(x, 2, "Text Arrows", 280, 142, 480, 162, "checkbox", 0, "true".equalsIgnoreCase(TUtil.compatibilityProperty("textArrows")), true,
			"on mouseUp\n  set the textArrows to the hilite of me\nend mouseUp");
		button(x, 3, "Power Keys", 280, 120, 480, 140, "checkbox", 0, PaintTool.powerKeys, true,
			"on mouseUp\n  set the powerKeys to the hilite of me\nend mouseUp");
		button(x, BLIND_TYPING_ID, "Blind Typing", 280, 164, 480, 184, "checkbox", 0, "true".equalsIgnoreCase(TUtil.compatibilityProperty("blindTyping")), true,
			"on mouseUp\n  set the blindTyping to the hilite of me\nend mouseUp");
		field(x, 5, "User Level", 20, 250, 80, 270, "rectangle", true, false, true, 12, "plain", "");
		String[] levels = {"Browsing", "Typing", "Painting", "Authoring", "Scripting"};
		for(int i=0; i<levels.length; i++){
			int top = 120+22*i;
			button(x, 6+i, levels[i], 40, top, 220, top+20, "radio", 1, level==i+1, true,
				"on mouseUp\n  put "+(i+1)+" into card field \"User Level\"\n  set the userLevel to "+(i+1)+"\nend mouseUp");
		}
		field(x, 11, "", 20, 58, 138, 78, "transparent", true, true, true, 12, "plain", "");
		field(x, 12, "", 20, 96, 240, 116, "transparent", true, true, true, 12, "plain", "");
		field(x, 13, "", 260, 96, 480, 116, "transparent", true, true, true, 12, "plain", "");
		field(x, 14, "", 20, 14, 492, 40, "transparent", true, true, true, 18, "bold", "");
		content(x, "card", 5, Integer.toString(level));
		content(x, "card", 11, japanese ? "ユーザー名：" : "User Name:");
		content(x, "card", 12, japanese ? "ユーザーレベル：" : "User Level:");
		content(x, "card", 13, japanese ? "オプション：" : "Options:");
		content(x, "card", 14, japanese ? "ユーザー設定" : "User Preferences");
		x.append("<name>").append(PREFERENCES_CARD).append("</name><script></script></card>");
		return x.toString();
	}
	private static String pathsCardXml(int id, OBackground bg, String name, String title){
		StringBuilder x = new StringBuilder("<card><id>").append(id).append("</id><bitmap></bitmap>");
		x.append("<cantDelete><true/></cantDelete><showPict><true/></showPict><dontSearch><false/></dontSearch>");
		x.append("<background>").append(bg.id).append("</background>");
		OField titleField = bg.GetFld("Title");
		if(titleField!=null) content(x, "background", titleField.id, title);
		x.append("<name>").append(xmlText(name)).append("</name><script></script></card>");
		return x.toString();
	}

	private static void field(StringBuilder x, int id, String name, int left, int top, int right, int bottom, String style,
			boolean locked, boolean visible, boolean dontWrap, int size, String textStyle, String script){
		x.append("<part><id>").append(id).append("</id><type>field</type>");
		x.append("<visible>").append(visible ? "<true/>" : "<false/>").append("</visible>");
		x.append("<dontWrap>").append(dontWrap ? "<true/>" : "<false/>").append("</dontWrap>");
		x.append("<dontSearch><false/></dontSearch><sharedText><false/></sharedText><fixedLineHeight><false/></fixedLineHeight>");
		x.append("<autoTab>").append(!locked && dontWrap ? "<true/>" : "<false/>").append("</autoTab>");
		x.append("<lockText>").append(locked ? "<true/>" : "<false/>").append("</lockText>");
		rect(x, left, top, right, bottom);
		x.append("<style>").append(style).append("</style>");
		x.append("<autoSelect><false/></autoSelect><showLines><false/></showLines><wideMargins><false/></wideMargins>");
		x.append("<multipleLines><false/></multipleLines><textAlign>left</textAlign><textFontID>0</textFontID>");
		x.append("<textSize>").append(size).append("</textSize><textStyle>").append(textStyle).append("</textStyle>");
		x.append("<textHeight>").append(size+4).append("</textHeight>");
		colors(x);
		x.append("<name>").append(xmlText(name)).append("</name><script>").append(xmlText(script)).append("</script></part>");
	}
	private static void button(StringBuilder x, int id, String name, int left, int top, int right, int bottom, String style,
			int family, boolean hilite, boolean autoHilite, String script){
		x.append("<part><id>").append(id).append("</id><type>button</type><visible><true/></visible><enabled><true/></enabled>");
		rect(x, left, top, right, bottom);
		x.append("<style>").append(style).append("</style><showName><true/></showName>");
		x.append("<highlight>").append(hilite ? "<true/>" : "<false/>").append("</highlight>");
		x.append("<autoHighlight>").append(autoHilite ? "<true/>" : "<false/>").append("</autoHighlight>");
		x.append("<sharedHighlight><false/></sharedHighlight><family>").append(family).append("</family>");
		x.append("<titleWidth>0</titleWidth><icon>0</icon><iconURI></iconURI>");
		x.append("<textAlign>").append(style.equals("standard") ? "center" : "left").append("</textAlign>");
		x.append("<textFontID>0</textFontID><textSize>12</textSize><textStyle>plain</textStyle>");
		colors(x);
		x.append("<iconAspect><false/></iconAspect><scaleIcon><false/></scaleIcon><blendingMode>copy</blendingMode><blendingLevel>100</blendingLevel>");
		x.append("<name>").append(xmlText(name)).append("</name><script>").append(xmlText(script)).append("</script></part>");
	}
	private static void rect(StringBuilder x, int left, int top, int right, int bottom){
		x.append("<rect><left>").append(left).append("</left><top>").append(top).append("</top><right>").append(right)
			.append("</right><bottom>").append(bottom).append("</bottom></rect>");
	}
	private static void colors(StringBuilder x){
		x.append("<color><red>0</red><green>0</green><blue>0</blue></color>");
		x.append("<bgColor><red>65535</red><green>65535</green><blue>65535</blue></bgColor>");
	}
	private static void content(StringBuilder x, String layer, int id, String text){
		x.append("<content><layer>").append(layer).append("</layer><id>").append(id).append("</id><text>")
			.append(xmlText(text)).append("</text></content>");
	}
	private static String xmlText(String s){
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static long unsignedInt(byte[] b, int o){
		return ((long)(b[o]&0xFF)<<24)|((long)(b[o+1]&0xFF)<<16)|((long)(b[o+2]&0xFF)<<8)|(long)(b[o+3]&0xFF);
	}

	private static void deleteTree(File f){
		if(f==null || !f.exists()) return;
		File[] children = f.isDirectory() ? f.listFiles() : null;
		if(children!=null) for(File c: children) deleteTree(c);
		if(!f.delete()) System.err.println("Home stack: could not delete "+f.getPath());
	}
}

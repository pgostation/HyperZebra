import java.io.File;

/** Host-side writable locations which must not live beside an installed application. */
final class AppPaths {
	private static File stateDirectory;

	private AppPaths(){}

	/** Optional distribution data lives beside the .app bundle, not inside Contents/app. */
	static File distributionDirectory(){
		String launcher=System.getProperty("jpackage.app-path");
		if(launcher==null || launcher.isBlank()){
			try {
				File code = new File(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				if(code.isFile()) return code.getAbsoluteFile().getParentFile();
			} catch(Exception ignored){}
			return new File(".").getAbsoluteFile();
		}
		File executable=new File(launcher).getAbsoluteFile();
		for(File dir=executable.getParentFile(); dir!=null; dir=dir.getParentFile()){
			if(dir.getName().endsWith(".app")) return untranslocated(dir).getParentFile();
		}
		return executable.getParentFile();
	}

	private static File untranslocatedApp, untranslocatedFrom;

	/**
	 * A quarantined app opened where its ZIP was expanded runs from a read-only copy with nothing beside it (Gatekeeper's
	 * App Translocation): the real bundle, whose folder holds Support Files (home.hzb, sampleHomeApp), comes from Security.framework.
	 */
	private static synchronized File untranslocated(File app){
		if(app.equals(untranslocatedFrom)) return untranslocatedApp;
		File found=app;
		if(app.getPath().contains("/AppTranslocation/")){
			try {
				Object original=Class.forName("NativeTranslocation").getMethod("originalPath",String.class).invoke(null,app.getPath());
				if(original instanceof String && !((String)original).isEmpty()) found=new File((String)original);
			} catch(Throwable t){
				System.err.println("App Translocation: could not find the original app: "+t);
			}
		}
		untranslocatedFrom=app;
		untranslocatedApp=found;
		return found;
	}

	static synchronized File stateDirectory(){
		if(stateDirectory!=null)return stateDirectory;
		String override=System.getProperty("hz.state.dir");
		if(override!=null&&!override.trim().isEmpty())stateDirectory=new File(override.trim());
		else stateDirectory=platformDirectory();
		if(!stateDirectory.isDirectory()&&!stateDirectory.mkdirs())
			System.err.println("Could not create HyperZebra state directory: "+stateDirectory.getAbsolutePath());
		return stateDirectory;
	}

	private static String os(){return System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);}
	private static File home(){return new File(System.getProperty("user.home","."));}

	/**
	 * HyperZebra's per-user folder on this platform, whatever hz.state.dir says: ~/Library/Application Support/HyperZebra on the
	 * Mac, %LOCALAPPDATA%\HyperZebra on Windows, $XDG_STATE_HOME/HyperZebra (~/.local/state/HyperZebra) on Linux. Worked out on
	 * every call from user.home, which tests change while running. When user.home is not the account's own home (a scratch
	 * -Duser.home), LOCALAPPDATA and XDG_STATE_HOME still name the real account's folders, so the default under user.home is used.
	 */
	static File platformDirectory(){
		String os=os();File home=home();
		if(os.contains("linux")){
			String xdg=System.getenv("XDG_STATE_HOME");
			boolean own=xdg!=null&&!xdg.isBlank()&&new File(xdg).isAbsolute()&&isAccountHome(home,"HOME");
			return new File(own?new File(xdg):new File(home,".local"+File.separator+"state"),"HyperZebra");
		}
		if(os.contains("windows")){
			String local=System.getenv("LOCALAPPDATA");
			boolean own=local!=null&&!local.isBlank()&&isAccountHome(home,"USERPROFILE");
			return new File(own?new File(local):new File(home,"AppData"+File.separator+"Local"),"HyperZebra");
		}
		return new File(new File(home,"Library"+File.separator+"Application Support"),"HyperZebra");
	}

	/** Whether user.home is the folder the environment variable names (HOME, USERPROFILE): the account's own home, not a scratch one. */
	private static boolean isAccountHome(File home,String variable){
		String env=System.getenv(variable);
		if(env==null||env.isBlank())return true;
		try{return new File(env).getCanonicalFile().equals(home.getCanonicalFile());}
		catch(java.io.IOException e){return new File(env).getAbsoluteFile().equals(home.getAbsoluteFile());}
	}

	/** user.home values whose old Windows/Linux boot volume has been looked at (moved, or found missing). */
	private static final java.util.Set<String> legacyChecked=java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * The startup disk "HyperZebra HD" programs in the VM see, holding the virtual System Folder: the same folder as
	 * {@link #platformDirectory()} (and, without hz.state.dir, as {@link #stateDirectory()}), so the launcher's Open HyperZebra HD
	 * shows it. Up to 3.0.2 it was <user home>/Library/Application Support/HyperZebra on Windows and Linux too; the first call
	 * there moves that folder's contents here.
	 */
	static File bootVolumeDirectory(){
		File dir=platformDirectory();
		if(os().contains("windows")||os().contains("linux")){
			String key=home().getAbsolutePath();
			if(!legacyChecked.contains(key))synchronized(legacyChecked){
				if(legacyChecked.add(key))moveLegacyBootVolume(dir);
			}
		}
		return dir;
	}

	private static void moveLegacyBootVolume(File dir){
		File support=new File(new File(home(),"Library"),"Application Support"),legacy=new File(support,"HyperZebra");
		if(!legacy.isDirectory())return;
		try{if(legacy.getCanonicalFile().equals(dir.getCanonicalFile()))return;}catch(java.io.IOException ignored){}
		if(!dir.isDirectory()&&!dir.mkdirs()){System.err.println("HyperZebra HD: could not create "+dir);return;}
		System.err.println("HyperZebra HD: moving "+legacy+" to "+dir);
		mergeInto(legacy.toPath(),dir.toPath());
		// the folders 3.0.2 made, once nothing is left in them (a Library of the user's own with something in it stays)
		for(File f:new File[]{legacy,support,support.getParentFile()})
			if(f.isDirectory()&&f.list()!=null&&f.list().length==0&&!f.delete())break;
	}

	/** Moves what {@code from} holds into {@code to}: an entry already there is kept and the old one left where it was; folders in both are merged. */
	private static void mergeInto(java.nio.file.Path from,java.nio.file.Path to){
		String[] names=from.toFile().list();
		if(names==null)return;
		for(String name:names){
			java.nio.file.Path source=from.resolve(name),target=to.resolve(name);
			try{
				if(!java.nio.file.Files.exists(target,java.nio.file.LinkOption.NOFOLLOW_LINKS))moveTree(source,target);
				else if(java.nio.file.Files.isDirectory(source,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&java.nio.file.Files.isDirectory(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
					mergeInto(source,target);
					String[] left=source.toFile().list();
					if(left!=null&&left.length==0)java.nio.file.Files.delete(source); // all of it moved
				}
				else System.err.println("HyperZebra HD: kept "+source+" ("+target+" is already there)");
			}catch(java.io.IOException e){
				System.err.println("HyperZebra HD: could not move "+source+": "+e);
			}
		}
	}

	private static void moveTree(java.nio.file.Path source,java.nio.file.Path target) throws java.io.IOException {
		try{java.nio.file.Files.move(source,target);return;}catch(java.io.IOException moveFailure){/* another volume: copy, then delete */}
		java.nio.file.Files.walkFileTree(source,new java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){
			@Override public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path d,java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
				java.nio.file.Files.createDirectories(target.resolve(source.relativize(d).toString()));return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f,java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
				java.nio.file.Files.copy(f,target.resolve(source.relativize(f).toString()),java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
		java.nio.file.Files.walkFileTree(source,new java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){
			@Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f,java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
				java.nio.file.Files.delete(f);return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path d,java.io.IOException e) throws java.io.IOException {
				java.nio.file.Files.delete(d);return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}

	static File resourceTrashDirectory(){
		File directory=new File(stateDirectory(),"resource_trash");
		if(!directory.isDirectory()&&!directory.mkdirs())
			System.err.println("Could not create resource directory: "+directory.getAbsolutePath());
		return directory;
	}

	static File recentFile(){return new File(resourceTrashDirectory(),"recent.txt");}

	static File resourceTrashFile(String name){
		return new File(resourceTrashDirectory(),new File(name==null?"resource":name).getName());
	}

	/** A recoverable destination which never overwrites an earlier deleted resource. */
	static File uniqueResourceTrashFile(String name){
		File first=resourceTrashFile(name);
		if(!first.exists())return first;
		String filename=first.getName(),base=filename,extension="";int dot=filename.lastIndexOf('.');
		if(dot>0){base=filename.substring(0,dot);extension=filename.substring(dot);}
		for(int i=1;;i++){File candidate=new File(first.getParentFile(),base+" "+i+extension);if(!candidate.exists())return candidate;}
	}

	static File moveResourceToTrash(File source) throws java.io.IOException {
		File destination=uniqueResourceTrashFile(source.getName());
		try{return java.nio.file.Files.move(source.toPath(),destination.toPath()).toFile();}
		catch(java.io.IOException moveFailure){
			try{
				java.nio.file.Files.copy(source.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
				java.nio.file.Files.delete(source.toPath());
				return destination;
			}catch(java.io.IOException copyFailure){copyFailure.addSuppressed(moveFailure);throw copyFailure;}
		}
	}
}

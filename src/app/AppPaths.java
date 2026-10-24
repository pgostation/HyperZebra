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
		else {
			String os=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
			File home=new File(System.getProperty("user.home","."));
			if(os.contains("linux")){
				String xdg=System.getenv("XDG_STATE_HOME");
				File base=xdg!=null&&!xdg.isBlank()&&new File(xdg).isAbsolute()?new File(xdg):new File(home,".local/state");
				stateDirectory=new File(base,"HyperZebra");
			}else if(os.contains("windows")){
				String local=System.getenv("LOCALAPPDATA");
				stateDirectory=new File(local==null||local.isBlank()?home:new File(local),"HyperZebra");
			}else stateDirectory=new File(new File(home,"Library/Application Support"),"HyperZebra");
		}
		if(!stateDirectory.isDirectory()&&!stateDirectory.mkdirs())
			System.err.println("Could not create HyperZebra state directory: "+stateDirectory.getAbsolutePath());
		return stateDirectory;
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

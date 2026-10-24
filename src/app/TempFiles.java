import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;

/**
 * HyperZebra's working files (a 68K program's resource forks and converted text files, decoded sounds, …) in one folder per
 * run: {@code <java.io.tmpdir>/HyperZebra/<pid>-<process start in ms>}. File.deleteOnExit never runs when the JVM is killed
 * (a stopped program, a test cut off) and the system temp folder used to fill up with tens of thousands of them, slowing every
 * program that lists it. The run's folder goes at exit; at the next start the folders of runs whose process is gone are swept.
 * Nothing outside {@code <java.io.tmpdir>/HyperZebra} is touched, and in there only folders named like a run's.
 */
final class TempFiles {
	static final String FOLDER = "HyperZebra";
	/** A run folder whose owner cannot be told apart from a later process with the same pid is kept this long. */
	static final long STALE_MILLIS = 24L*60*60*1000;
	private static File runDirectory;
	private static boolean swept;

	private TempFiles(){}

	/** A new empty file in this run's folder (like File.createTempFile, without deleteOnExit's growing list). */
	static File createFile(String prefix, String suffix) throws IOException {
		return Files.createTempFile(runDirectory().toPath(), prefix, suffix).toFile();
	}

	/** A new empty folder in this run's folder. */
	static File createDirectory(String prefix) throws IOException {
		return Files.createTempDirectory(runDirectory().toPath(), prefix).toFile();
	}

	/** This run's folder, made at first use (and again if something removed it), removed at exit. */
	static synchronized File runDirectory() throws IOException {
		if(runDirectory!=null&&runDirectory.isDirectory())return runDirectory;
		File root=root();
		File dir=new File(root,runName(ProcessHandle.current()));
		Files.createDirectories(dir.toPath());
		if(runDirectory==null){
			Runtime.getRuntime().addShutdownHook(new Thread(()->deleteTree(dir.toPath()),"HyperZebra temp files"));
		}
		runDirectory=dir;
		sweepLater();
		return dir;
	}

	/** Removes the folders of earlier runs that ended without removing theirs (killed), in the background; once per run. */
	static synchronized void sweepLater(){
		if(swept)return;
		swept=true;
		File root=root();
		String own=runName(ProcessHandle.current());
		Thread sweeper=new Thread(()->sweep(root,own,System.currentTimeMillis()),"HyperZebra temp sweep");
		sweeper.setDaemon(true);
		sweeper.setPriority(Thread.MIN_PRIORITY);
		sweeper.start();
	}

	static File root(){return new File(System.getProperty("java.io.tmpdir","."),FOLDER);}

	static String runName(ProcessHandle process){
		return process.pid()+"-"+process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
	}

	/** Sweeps {@code root}: the number of run folders removed. {@code own} (this run's folder name) is always kept. */
	static int sweep(File root,String own,long now){
		File[] all=root.listFiles();
		if(all==null)return 0;
		int removed=0;
		for(File f:all){
			String name=f.getName();
			if(name.equals(own)||!f.isDirectory()||!isStale(name,f.lastModified(),now))continue;
			if(deleteTree(f.toPath()))removed++;
		}
		return removed;
	}

	/**
	 * Whether the run folder {@code name} belongs to a run that is over: its process is gone, or the pid now belongs to a
	 * process started at another time. When the start time cannot be read, a folder untouched for a day counts as over.
	 * Names not made by {@link #runName} are never stale (not ours to remove).
	 */
	static boolean isStale(String name,long lastModified,long now){
		int dash=name.indexOf('-');
		if(dash<=0||!name.matches("[0-9]+-[0-9]+"))return false;
		long pid,start;
		try{pid=Long.parseLong(name.substring(0,dash));start=Long.parseLong(name.substring(dash+1));}
		catch(NumberFormatException e){return false;}
		Optional<ProcessHandle> process=ProcessHandle.of(pid);
		if(process.isEmpty()||!process.get().isAlive())return true;
		Optional<Instant> started=process.get().info().startInstant();
		if(start!=0&&started.isPresent())return Math.abs(started.get().toEpochMilli()-start)>2000;
		return now-lastModified>STALE_MILLIS;
	}

	/** Deletes a folder and what is in it (links themselves, never what they point to); true when it is gone. */
	static boolean deleteTree(Path dir){
		try{
			Files.walkFileTree(dir,new SimpleFileVisitor<Path>(){
				@Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes){
					try{Files.deleteIfExists(file);}catch(IOException ignored){}
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult visitFileFailed(Path file,IOException e){return FileVisitResult.CONTINUE;}
				@Override public FileVisitResult postVisitDirectory(Path d,IOException e){
					try{Files.deleteIfExists(d);}catch(IOException ignored){}
					return FileVisitResult.CONTINUE;
				}
			});
		}catch(IOException ignored){}
		return !Files.exists(dir,java.nio.file.LinkOption.NOFOLLOW_LINKS);
	}
}

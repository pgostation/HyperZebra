import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Where files deleted by hosted programs go. A real Mac's File Manager deletes for good; here a file outside HyperZebra HD
 * that was there before the program made it - the user's own documents beside an old program - is moved to HyperZebra HD's
 * "Deleted Files" folder instead (one folder per day, with an index naming where each file came from and who deleted it),
 * so a program that deleted the wrong file costs nothing. Files the program made itself this session (temporary files, the
 * half of a safe save) and anything inside HyperZebra HD (the virtual Mac's own disk) are deleted as before.
 */
final class DeletedFiles {
	private DeletedFiles(){}

	/** The folder that keeps deleted files (made on first use). */
	static File folder(){ return new File(AppPaths.stateDirectory(), "Deleted Files"); }

	/**
	 * Deletes a file (with its resource fork sidecar) or an empty folder on behalf of {@code requester}; true when it is
	 * gone from its place. Kept in {@link #folder} unless the requester made it itself or it lies in HyperZebra HD.
	 */
	static boolean delete(File path, String requester){
		if(path==null || !path.exists()) return false;
		if(!keeps(path, requester)) return path.delete();
		try{
			File dest = keep(path, requester);
			System.err.println("[deleted] "+path+" -> "+dest);
			return true;
		}catch(IOException e){
			System.err.println("[deleted] could not keep "+path+" ("+e.getMessage()+"); not deleted");
			return false;
		}
	}

	/** Whether a delete of {@code path} keeps the file (a file the user had before, outside HyperZebra HD). */
	static boolean keeps(File path, String requester){
		if(Sandbox.isBuiltIn(path)) return false;
		if(Sandbox.isCreatedBy(requester, path)) return false;
		if(path.isDirectory()){ String[] children = path.list(); if(children!=null && children.length>0) return false; } // only empty folders are deleted anyway
		return true;
	}

	/** Moves {@code path} into today's folder under a name not used yet and notes where it came from. */
	static File keep(File path, String requester) throws IOException {
		File day = new File(folder(), new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()));
		if(!day.isDirectory() && !day.mkdirs()) throw new IOException("cannot create "+day);
		File dest = unique(day, path.getName());
		boolean file = path.isFile();
		try{
			Files.move(path.toPath(), dest.toPath());
		}catch(IOException moveFailure){ // another disk: copy, then delete
			if(!file) throw moveFailure;
			Files.copy(path.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
			byte[] fork = null; byte[] info = null;
			try{ fork = ResourceFile68k.readForkBytes(path, new ResourceFile68k(path)); }catch(Exception ignored){}
			try{ info = FinderInfo68k.read(path); }catch(Exception ignored){}
			try{
				if(fork!=null && fork.length>0) ResourceFile68k.writeForkBytes(dest, fork, ResourceFile68k.Storage.NONE);
				if(info!=null) FinderInfo68k.write(dest, info);
			}catch(Exception ignored){}
			Files.delete(path.toPath());
		}
		if(file) ResourceFile68k.AppleDouble.moveWith(path, dest);
		String line = dest.getName()+"\t"+path.getAbsolutePath()+"\t"+(requester==null ? "" : requester)+"\t"+new java.util.Date()+System.lineSeparator();
		try{
			Files.write(new File(day, "index.txt").toPath(), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}catch(IOException ignored){}
		return dest;
	}

	private static File unique(File dir, String name){
		File first = new File(dir, name);
		if(!first.exists() && !name.equals("index.txt")) return first;
		String base = name, ext = ""; int dot = name.lastIndexOf('.');
		if(dot>0){ base = name.substring(0, dot); ext = name.substring(dot); }
		for(int i=2;; i++){ File f = new File(dir, base+" "+i+ext); if(!f.exists()) return f; }
	}
}

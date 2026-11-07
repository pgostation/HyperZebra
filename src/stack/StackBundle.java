import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * HyperZebra's single-file stack: a .hzb that is a ZIP archive holding what a .hzb folder holds (_stack.xml, the
 * pictures, patterns, icons, sounds and other resources), so a stack is one file that can be double-clicked, mailed and
 * copied on every platform. The older .hzb folder still opens and is saved as a folder.
 * <p>
 * A packed stack is worked on in a working folder (named like the stack, "X.hzb") under HyperZebra's state folder: the
 * stack's code reads and writes that folder exactly as it does a .hzb folder, and {@link #sync} writes it back into the
 * .hzb file - a new archive written beside the file and moved over it, so a crash or a full disk never leaves a broken
 * stack. Where a stack is (its long name, the folder around it, the launcher's history, the access gate) is asked of
 * {@link #host}, which turns a path in the working folder into the same path under the .hzb file.
 * <p>
 * The archive starts with an uncompressed "mimetype" entry (as EPUB and OpenDocument do) so the file is known by its
 * contents; pictures and sounds that are compressed already are stored, the rest is deflated. A working folder is kept
 * for the session (paths into it may be remembered by the card history or other windows) and removed at quit once it
 * is written back; one left by a HyperZebra that did not quit holds the changes that did not reach the file, and is
 * used (and written back) when that stack is opened again, as long as the file did not change meanwhile.
 */
final class StackBundle {
	static final String MIME_TYPE = "application/x-hyperzebra-stack";
	static final String MIME_ENTRY = "mimetype";
	private static final String MARKER = "bundle.properties";
	/** Movies converted by ffmpeg (TQTMovie) are made again when needed: not carried in the file. */
	private static final String MOVIE_CACHE = "_qtmovie_cache";
	/** How often changes written to working folders outside the stack's own save (paint, resources) are written back. */
	private static final long WATCH_MS = 3000;

	/** A packed stack and its working folder. */
	static final class Bundle {
		final File packed, folder, root;
		/** The .hzb file as this bundle last read or wrote it (size and time), "" before it is first written. */
		String packedStamp = "";
		/** The working folder's contents when they were last written to (or read from) the file. */
		String signature = "";
		/** Why the last write-back failed, or null. */
		volatile String lastError;
		Bundle(File packed, File folder, File root){ this.packed = packed; this.folder = folder; this.root = root; }
	}

	private static final Map<String, Bundle> byFolder = new LinkedHashMap<String, Bundle>();
	private static final Map<String, Bundle> byPacked = new LinkedHashMap<String, Bundle>();
	private static Thread watcher;

	private StackBundle(){}

	// ------------------------------------------------------------------ recognising

	/** A .hzb (or other) file that is a packed stack: a ZIP archive named .hzb, or one whose first entry says it is one. */
	static boolean isPacked(File f){
		if(f==null || !f.isFile()) return false;
		byte[] head = new byte[30+MIME_ENTRY.length()+MIME_TYPE.length()];
		int n;
		try(InputStream in = new FileInputStream(f)){ n = in.readNBytes(head, 0, head.length); }
		catch(IOException e){ return false; }
		if(n<4 || head[0]!='P' || head[1]!='K' || head[2]!=3 || head[3]!=4) return false;
		if(hasHzbExtension(f)) return true;
		String s = new String(head, 0, n, StandardCharsets.ISO_8859_1);
		return s.indexOf(MIME_ENTRY+MIME_TYPE)>=0;
	}

	static boolean hasHzbExtension(File f){
		return f!=null && f.getName().toLowerCase(Locale.ROOT).endsWith(".hzb") && f.getName().length()>4;
	}

	/** The packed stack whose working folder holds {@code f} (the folder itself, its _stack.xml or any file in it), or null. */
	static File packedOf(File f){
		Bundle b = bundleOf(f);
		return b==null ? null : b.packed;
	}

	/** The working folder of a packed stack that is open, or null. */
	static File folderOf(File packed){
		Bundle b;
		synchronized(StackBundle.class){ b = byPacked.get(key(packed)); }
		return b==null ? null : b.folder;
	}

	/**
	 * Where {@code f} is as far as the user and scripts are concerned: a path in a packed stack's working folder is the same
	 * path under the .hzb file ("…/X.hzb/_stack.xml" with the .hzb beside the user's other files); anything else is itself.
	 */
	static File host(File f){
		if(f==null) return null;
		Bundle b = bundleOf(f);
		if(b==null) return f;
		String rel = relative(b.folder, f);
		return rel==null || rel.isEmpty() ? b.packed : new File(b.packed, rel);
	}

	/** {@link #host} of a path string (null stays null). */
	static String host(String path){
		if(path==null) return null;
		File h = host(new File(path));
		return h==null ? path : h.getPath();
	}

	/** The folder the stack is kept in, given the stack's file (_stack.xml), its folder or a packed .hzb. */
	static File folderAround(File stackFile){
		File f = host(stackFile);
		if(f==null) return null;
		f = f.getAbsoluteFile();
		String n = f.getName();
		if(n.equals("_stack.xml") || n.equals("toc.xml")) f = f.getParentFile();
		return f==null ? null : f.getParentFile();
	}

	/**
	 * A folder holding a stack file's icon files (icon.png and the icl8_/ics8_/ICN#_/ics#_ resources FinderIcons68k reads):
	 * its working folder when the stack is open, otherwise those entries read out of the file into a small cache that is
	 * read again when the file changes. Null when the file cannot be read.
	 */
	static File iconFolder(File packed){
		File open = folderOf(packed);
		if(open!=null && open.isDirectory()) return open;
		File dir = new File(new File(AppPaths.stateDirectory(), ICON_CACHE), digest(key(packed)).substring(0, 16));
		File stampFile = new File(dir, ".stamp");
		String stamp = stamp(packed);
		try{
			if(stampFile.isFile() && new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8).equals(stamp)){
				if(!new File(dir, SOURCE).isFile()) writeSource(dir, packed); // made before the source was kept
				return dir;
			}
		}catch(IOException ignored){}
		synchronized(StackBundle.class){
			deleteTree(dir);
			try(ZipFile zip = new ZipFile(packed, StandardCharsets.UTF_8)){
				Files.createDirectories(dir.toPath());
				byte[] buffer = new byte[16384];
				for(Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ){
					ZipEntry e = en.nextElement();
					String n = e.getName();
					if(e.isDirectory() || n.indexOf('/')>=0 || n.indexOf('\\')>=0 || !isIconEntry(n)) continue;
					try(InputStream in = zip.getInputStream(e); OutputStream o = new FileOutputStream(new File(dir, n))){ copy(in, o, buffer); }
				}
				Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
				writeSource(dir, packed);
			}catch(IOException e){
				return null;
			}
		}
		return dir;
	}

	/**
	 * A folder for what HyperZebra makes from a stack file and keeps between sessions without putting it in the file
	 * ({@code kind}: "Movie Cache" for TQTMovie's converted movies), one per stack file.
	 */
	static File cacheFolder(File packed, String kind){
		File dir = new File(new File(AppPaths.stateDirectory(), kind), digest(key(packed.getAbsoluteFile())).substring(0, 16));
		dir.mkdirs();
		if(!new File(dir, SOURCE).isFile()) try{ writeSource(dir, packed.getAbsoluteFile()); }catch(IOException ignored){}
		return dir;
	}

	static final String ICON_CACHE = "Stack Icons", MOVIE_CACHE_KIND = "Movie Cache";
	/** In a cache folder: the path of the stack file it was made from (purgeCaches). */
	private static final String SOURCE = ".source";
	/** A cache folder made before {@link #SOURCE} was written goes when it was not used for this long. */
	static final long UNKNOWN_CACHE_MILLIS = 30L*24*60*60*1000;

	private static void writeSource(File dir, File packed) throws IOException {
		Files.write(new File(dir, SOURCE).toPath(), packed.getPath().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The caches made from stack files that are gone (deleted, moved, renamed): kept one folder per stack file for good,
	 * they piled up in the state folder. A folder without its source (made before the source was kept) goes once it was
	 * not used for {@link #UNKNOWN_CACHE_MILLIS}.
	 */
	static void purgeCaches(long now){
		for(String kind: new String[]{ ICON_CACHE, MOVIE_CACHE_KIND }){
			File[] dirs = new File(AppPaths.stateDirectory(), kind).listFiles(File::isDirectory);
			if(dirs==null) continue;
			for(File dir: dirs){
				File source = new File(dir, SOURCE);
				String packed = null;
				try{ if(source.isFile()) packed = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8).trim(); }
				catch(IOException e){ continue; }
				boolean gone = packed!=null ? !packed.isEmpty() && !new File(packed).isFile() && !onMissingVolume(new File(packed))
					: now-newest(dir)>UNKNOWN_CACHE_MILLIS;
				if(gone) synchronized(StackBundle.class){ deleteTree(dir); }
			}
		}
	}

	/** On a disk that is not there now (a drive letter, /Volumes/<name>, /media/…/<name>): the file may come back with it. */
	private static boolean onMissingVolume(File f){
		java.nio.file.Path p = f.getAbsoluteFile().toPath(), root = p.getRoot();
		if(root==null || !Files.isDirectory(root)) return true;
		String top = p.getNameCount()>2 ? p.getName(0).toString() : "";
		int disk = top.equals("Volumes") || top.equals("mnt") ? 2 : top.equals("media") ? Math.min(3, p.getNameCount()-1) : 0;
		return disk>0 && !Files.isDirectory(root.resolve(p.subpath(0, disk)));
	}

	private static long newest(File f){
		long t = f.lastModified();
		File[] children = f.isDirectory() ? f.listFiles() : null;
		if(children!=null) for(File c: children) t = Math.max(t, newest(c));
		return t;
	}

	private static boolean isIconEntry(String n){
		return n.equals("icon.png") || n.startsWith("icl8_") || n.startsWith("ics8_") || n.startsWith("ICN#_") || n.startsWith("ics#_");
	}

	// ------------------------------------------------------------------ opening and making

	/** Opens a packed stack: its working folder (made from the file, or the one already in use), to be opened as a .hzb folder. */
	static File open(File packed) throws IOException {
		packed = canonical(packed);
		synchronized(StackBundle.class){
			Bundle known = byPacked.get(key(packed));
			if(known!=null && known.folder.isDirectory()){
				String stamp = stamp(packed);
				//the file was replaced by someone else and nothing here is waiting to be written: read it again
				if(!stamp.equals(known.packedStamp) && !known.packedStamp.isEmpty() && signature(known.folder).equals(known.signature)){
					synchronized(known){ extractInto(known); }
				}
				return known.folder;
			}
			File root = workRoot(packed);
			Bundle b = new Bundle(packed, new File(root, packed.getName()), root);
			Properties marker = readMarker(root);
			if(marker!=null && b.folder.isDirectory() && stamp(packed).equals(marker.getProperty("stamp"))
					&& packed.getPath().equals(marker.getProperty("packed"))){
				//left by a HyperZebra that did not quit: its changes are the newest (written back by the next sync)
				b.packedStamp = marker.getProperty("stamp", "");
				b.signature = marker.getProperty("signature", "");
				if(!b.signature.equals(signature(b.folder))) System.err.println("[stack] recovered unsaved changes of "+packed);
				writeMarker(b); //this process works on it now
			}else{
				deleteTree(root);
				try{ extractInto(b); }
				catch(IOException e){ deleteTree(root); throw e; } //a damaged file leaves no half working folder
			}
			//a file written before stack files got their icons gets it now. Both ways above leave packedStamp as the file
			//is; the resource fork moves the file's time, so the stamp follows (or the next open would read the file again)
			try{
				if(StackFileIcon.applyIfMissing(b.folder, packed)){
					b.packedStamp = stamp(packed);
					writeMarker(b);
				}
			}catch(RuntimeException e){
				System.err.println("[stack] could not give "+packed.getName()+" its icon: "+e);
			}
			register(b);
			return b.folder;
		}
	}

	/**
	 * A new packed stack {@code packed} (not there yet): an empty working folder to write the stack into. The file is made
	 * by the first {@link #sync}. Returns the working folder.
	 */
	static File create(File packed) throws IOException {
		packed = canonical(packed);
		synchronized(StackBundle.class){
			Bundle known = byPacked.get(key(packed));
			if(known!=null) unregister(known);
			File root = workRoot(packed);
			deleteTree(root);
			Bundle b = new Bundle(packed, new File(root, packed.getName()), root);
			Files.createDirectories(b.folder.toPath());
			writeMarker(b);
			register(b);
			return b.folder;
		}
	}

	/**
	 * Packs an existing .hzb folder (or any stack folder) into the new file {@code packed} (Save a Copy, Save Elsewhere,
	 * converting an older stack). Temporary files of unfinished writes are left out. Throws when it could not be written;
	 * nothing is then left at {@code packed}.
	 */
	static void packFolder(File folder, File packed) throws IOException {
		pack(folder, packed.getAbsoluteFile());
	}

	/**
	 * Gives up a packed stack whose working folder holds {@code f} without writing it back (a conversion that was cancelled
	 * or failed, a copy that could not be written): the working folder goes, the .hzb file (if any) stays as it is. False
	 * when {@code f} is not in a working folder.
	 */
	static boolean discard(File f){
		Bundle b = bundleOf(f);
		if(b==null) return false;
		synchronized(StackBundle.class){
			unregister(b);
			synchronized(b){ deleteTree(b.root); }
		}
		return true;
	}

	// ------------------------------------------------------------------ writing back

	/**
	 * Writes a packed stack's working folder back into its .hzb file when it changed since the last write. {@code f} is
	 * anything in the working folder (the stack's _stack.xml). Null when written, unchanged, or not a packed stack;
	 * otherwise why it could not be written (in XMLwrite's words: "The disk is full: …").
	 */
	static String sync(File f){
		Bundle b = bundleOf(f);
		if(b==null) return null;
		PictureSaver.flush();
		return sync(b);
	}

	private static String sync(Bundle b){
		synchronized(b){
			if(!b.folder.isDirectory()) return null; //removed at quit, or by the user: nothing to write
			String sig = signature(b.folder);
			//unchanged: nothing to write - also when the file was moved to the trash meanwhile (it is not made again)
			if(sig.equals(b.signature) && !b.packedStamp.isEmpty()) return b.lastError = null;
			if(!new File(b.folder, "_stack.xml").isFile() && !new File(b.folder, "toc.xml").isFile())
				return null; //a new stack before its first write: nothing to make a file of yet
			try{
				File dir = b.packed.getParentFile();
				if(dir==null || !dir.isDirectory()) return b.lastError = "The folder is missing: "+(dir==null ? b.packed.getPath() : dir.getPath());
				if(b.packed.exists() && !b.packed.canWrite()) return b.lastError = "Writing is not allowed: "+b.packed.getPath();
				pack(b.folder, b.packed);
			}catch(IOException e){
				return b.lastError = XMLwrite.describe(e, b.packed);
			}
			b.signature = sig;
			b.packedStamp = stamp(b.packed);
			writeMarker(b);
			return b.lastError = null;
		}
	}

	/** Writes every open packed stack back (quitting); the first failure, or null. */
	static String syncAll(){
		String first = null;
		for(Bundle b: bundles()){
			String why = sync(b);
			if(why!=null && first==null) first = why;
		}
		return first;
	}

	/** Quitting: every open packed stack is written back, and the working folders that reached their files are removed. */
	static void shutDown(){
		PictureSaver.flush();
		for(Bundle b: bundles()){
			String why = sync(b);
			if(why==null && b.packed.isFile()) synchronized(b){ deleteTree(b.root); }
			else if(why!=null) System.err.println("[stack] kept the working copy of "+b.packed+" ("+why+"): "+b.folder);
		}
		synchronized(StackBundle.class){ byFolder.clear(); byPacked.clear(); }
	}

	/** Forgets the open stack files without writing them back, as a HyperZebra that stopped without quitting (tests). */
	static synchronized void forgetAll(){ byFolder.clear(); byPacked.clear(); }

	/**
	 * At start-up: working folders left behind that hold nothing more than their files (or whose files were changed
	 * elsewhere since) are removed; ones with changes that never reached their file stay for the next open. The icon and
	 * movie caches of stack files that are gone go too ({@link #purgeCaches}).
	 */
	static void purgeStaleInBackground(){
		Thread t = new Thread(() -> { purgeStale(); purgeCaches(System.currentTimeMillis()); }, "stack working copies");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	static void purgeStale(){
		File base = workBase();
		File[] roots = base.listFiles();
		if(roots==null) return;
		for(File root: roots){
			if(!root.isDirectory()) continue;
			synchronized(StackBundle.class){
				boolean inUse = false;
				for(Bundle b: byFolder.values()) if(b.root.equals(root)) inUse = true;
				if(inUse) continue;
				Properties marker = readMarker(root);
				if(marker==null){ deleteTree(root); continue; }
				//another HyperZebra running now has this stack open (a second copy of the program): its working folder,
				//written back a moment ago, looked like one left behind, and that copy's next save found its folder gone
				if(heldByOtherProcess(marker)) continue;
				File packed = new File(marker.getProperty("packed", ""));
				File folder = new File(root, packed.getName());
				boolean unsaved = folder.isDirectory() && !signature(folder).equals(marker.getProperty("signature", ""));
				boolean fileSame = packed.isFile() && stamp(packed).equals(marker.getProperty("stamp"));
				if(!unsaved || !fileSame && packed.exists()) deleteTree(root);
			}
		}
	}

	/** The marker names a HyperZebra process other than this one that is still running. */
	private static boolean heldByOtherProcess(Properties marker){
		try{
			long pid = Long.parseLong(marker.getProperty("pid", "").trim());
			return pid!=ProcessHandle.current().pid() && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		}catch(NumberFormatException | UnsupportedOperationException | SecurityException e){
			return false;
		}
	}

	// ------------------------------------------------------------------ the archive

	private static void pack(File folder, File packed) throws IOException {
		List<String> names = new ArrayList<String>();
		collect(folder, "", names);
		Collections.sort(names);
		//the stack file first after the mimetype: a reader finds it without the central directory
		for(String first: new String[]{ "toc.xml", "_stack.xml" }) if(names.remove(first)) names.add(0, first);
		File dir = packed.getAbsoluteFile().getParentFile();
		File temp = File.createTempFile("."+packed.getName()+".", ".tmp", dir);
		try{
			try(FileOutputStream fileOut = new FileOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)){
				byte[] mime = MIME_TYPE.getBytes(StandardCharsets.US_ASCII);
				ZipEntry m = new ZipEntry(MIME_ENTRY);
				m.setMethod(ZipEntry.STORED);
				m.setSize(mime.length);
				CRC32 crc = new CRC32(); crc.update(mime); m.setCrc(crc.getValue());
				zip.putNextEntry(m); zip.write(mime); zip.closeEntry();
				byte[] buffer = new byte[65536];
				for(String name: names){
					File f = new File(folder, name.replace('/', File.separatorChar));
					if(name.endsWith("/")){
						ZipEntry e = new ZipEntry(name);
						e.setTime(f.lastModified());
						zip.putNextEntry(e); zip.closeEntry();
						continue;
					}
					if(!f.isFile()) continue; //removed meanwhile (an atomic write's temporary file)
					ZipEntry e = new ZipEntry(name);
					e.setTime(f.lastModified());
					try{
						if(storedAsIs(name)){
							CRC32 c = new CRC32();
							long size = 0;
							try(InputStream in = new FileInputStream(f)){
								for(int r; (r = in.read(buffer))>0; ){ c.update(buffer, 0, r); size += r; }
							}
							e.setMethod(ZipEntry.STORED); e.setSize(size); e.setCompressedSize(size); e.setCrc(c.getValue());
							zip.putNextEntry(e);
							long left = size;
							try(InputStream in = new FileInputStream(f)){
								for(int r; left>0 && (r = in.read(buffer, 0, (int)Math.min(buffer.length, left)))>0; ){ zip.write(buffer, 0, r); left -= r; }
							}
							if(left!=0) throw new IOException("The file changed while it was written: "+f.getPath());
						}else{
							e.setMethod(ZipEntry.DEFLATED);
							zip.putNextEntry(e);
							try(InputStream in = new FileInputStream(f)){ copy(in, zip, buffer); }
						}
					}catch(java.io.FileNotFoundException gone){
						if(f.exists()) throw gone;
						//an atomic write's temporary file went while the archive was made: the entry stays empty and the
						//next sync (the folder's contents differ from what was written) writes the archive again
					}
					zip.closeEntry();
				}
				zip.finish();
				fileOut.getFD().sync();
			}
			//the stack's custom icon (macOS): on the new file, so it is there the moment the file replaces the old one
			try{ StackFileIcon.apply(folder, temp); }
			catch(RuntimeException e){ System.err.println("[stack] could not give "+packed.getName()+" its icon: "+e); }
			try{
				Files.move(temp.toPath(), packed.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}catch(AtomicMoveNotSupportedException e){
				Files.move(temp.toPath(), packed.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}finally{
			if(temp.exists()) temp.delete();
		}
	}

	/** The working folder's files and folders, as archive names ("PICT_128.pict", "BGMs/", "BGMs/a.mp3"). */
	private static void collect(File dir, String prefix, List<String> into){
		File[] files = dir.listFiles();
		if(files==null) return;
		for(File f: files){
			String n = f.getName();
			if(skipped(prefix, n)) continue;
			if(f.isDirectory()){
				into.add(prefix+n+"/");
				collect(f, prefix+n+"/", into);
			}else if(f.isFile()) into.add(prefix+n);
		}
	}

	/** Not carried in the file: the movie cache, the Finder's .DS_Store, temporary files of writes in progress. */
	private static boolean skipped(String prefix, String n){
		if(prefix.isEmpty() && n.equals(MOVIE_CACHE)) return true;
		if(n.equals(".DS_Store") || n.equals("Thumbs.db")) return true;
		return n.matches("(?s).+\\.-?[0-9]+\\.tmp"); //File.createTempFile's "<name>.<digits>.tmp" (XMLwrite.writeAtomically, pack)
	}

	/** Already compressed (pictures, sounds, movies, archives): stored, not deflated again. */
	private static boolean storedAsIs(String name){
		String n = name.toLowerCase(Locale.ROOT);
		int dot = n.lastIndexOf('.');
		if(dot<0) return false;
		switch(n.substring(dot+1)){
			case "png": case "jpg": case "jpeg": case "gif": case "webp": case "mp3": case "m4a": case "aac": case "ogg":
			case "mp4": case "mov": case "m4v": case "zip": case "gz": case "sit": case "jar":
				return true;
			default:
				return false;
		}
	}

	private static void extractInto(Bundle b) throws IOException {
		deleteTree(b.folder);
		Files.createDirectories(b.folder.toPath());
		File canonicalFolder = b.folder.getCanonicalFile();
		try(ZipFile zip = new ZipFile(b.packed, StandardCharsets.UTF_8)){
			byte[] buffer = new byte[65536];
			for(Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ){
				ZipEntry e = en.nextElement();
				String name = e.getName().replace('\\', '/');
				if(name.equals(MIME_ENTRY)) continue;
				while(name.startsWith("/")) name = name.substring(1);
				if(name.isEmpty()) continue;
				File out = new File(b.folder, name.replace('/', File.separatorChar));
				//nothing outside the working folder ("../x" or an absolute name in a damaged or hostile archive)
				if(!out.getCanonicalFile().toPath().startsWith(canonicalFolder.toPath()))
					throw new IOException("The stack file holds an entry outside the stack: "+e.getName());
				if(e.isDirectory()){ Files.createDirectories(out.toPath()); continue; }
				Files.createDirectories(out.getParentFile().toPath());
				try(InputStream in = zip.getInputStream(e); OutputStream o = new FileOutputStream(out)){ copy(in, o, buffer); }
				if(e.getTime()>0) out.setLastModified(e.getTime());
			}
		}catch(java.util.zip.ZipException e){
			throw new IOException("The stack file is damaged: "+e.getMessage(), e);
		}
		b.signature = signature(b.folder);
		b.packedStamp = stamp(b.packed);
		writeMarker(b);
	}

	private static void copy(InputStream in, OutputStream out, byte[] buffer) throws IOException {
		for(int r; (r = in.read(buffer))>0; ) out.write(buffer, 0, r);
	}

	// ------------------------------------------------------------------ bookkeeping

	private static void register(Bundle b){
		byFolder.put(key(b.folder), b);
		byPacked.put(key(b.packed), b);
		if(watcher==null){
			watcher = new Thread(StackBundle::watch, "stack file writer");
			watcher.setDaemon(true);
			watcher.setPriority(Thread.MIN_PRIORITY);
			watcher.start();
			//quitting (QuitSequence has saved the stack on show and asked when it failed): the rest is written back
			Runtime.getRuntime().addShutdownHook(new Thread(StackBundle::shutDown, "stack files at quit"));
		}
	}

	private static void unregister(Bundle b){
		byFolder.remove(key(b.folder));
		byPacked.remove(key(b.packed));
	}

	private static synchronized List<Bundle> bundles(){ return new ArrayList<Bundle>(byPacked.values()); }

	/** Writes back what changed in working folders outside the stack's own save (paint pictures, resources, XCMD files). */
	private static void watch(){
		while(true){
			try{ Thread.sleep(WATCH_MS); }catch(InterruptedException e){ return; }
			for(Bundle b: bundles()){
				try{
					String why = sync(b);
					if(why!=null) System.err.println("[stack] could not write "+b.packed+": "+why);
				}catch(RuntimeException e){
					e.printStackTrace();
				}
			}
		}
	}

	private static Bundle bundleOf(File f){
		if(f==null) return null;
		synchronized(StackBundle.class){
			if(byFolder.isEmpty()) return null;
			File a = f.getAbsoluteFile();
			for(int depth = 0; a!=null && depth<64; depth++, a = a.getParentFile()){
				Bundle b = byFolder.get(key(a));
				if(b!=null) return b;
			}
		}
		return null;
	}

	/** One spelling of a stack file however it was named (the disk's case on a case-insensitive disk, no symbolic links). */
	private static File canonical(File f){
		try{ return f.getCanonicalFile(); }catch(IOException e){ return f.getAbsoluteFile(); }
	}

	private static String relative(File folder, File f){
		String base = folder.getAbsolutePath(), p = f.getAbsolutePath();
		if(p.equals(base)) return "";
		if(!p.startsWith(base+File.separator)) return null;
		return p.substring(base.length()+1);
	}

	private static String key(File f){
		String p = f.getAbsoluteFile().toPath().normalize().toString();
		return java.text.Normalizer.normalize(p, java.text.Normalizer.Form.NFC);
	}

	/** Where the working folders are: "Open Stacks" in HyperZebra's state folder. */
	static File workBase(){
		File base = new File(AppPaths.stateDirectory(), "Open Stacks");
		base.mkdirs();
		return base;
	}

	private static File workRoot(File packed){
		return new File(workBase(), digest(key(packed)).substring(0, 16));
	}

	private static String stamp(File packed){
		return packed.isFile() ? packed.length()+":"+packed.lastModified() : "";
	}

	/** The working folder's files, sizes and times in one digest (what the archive holds when it equals the last written one). */
	static String signature(File folder){
		List<String> names = new ArrayList<String>();
		collect(folder, "", names);
		Collections.sort(names);
		StringBuilder sb = new StringBuilder();
		for(String n: names){
			File f = new File(folder, n.replace('/', File.separatorChar));
			sb.append(n).append('\0');
			if(!n.endsWith("/")) sb.append(f.length()).append('\0').append(f.lastModified());
			sb.append('\n');
		}
		return digest(sb.toString());
	}

	private static String digest(String s){
		try{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for(byte x: d) hex.append(String.format("%02x", x&0xff));
			return hex.toString();
		}catch(NoSuchAlgorithmException e){
			throw new IllegalStateException(e);
		}
	}

	private static Properties readMarker(File root){
		File f = new File(root, MARKER);
		if(!f.isFile()) return null;
		Properties p = new Properties();
		try(InputStream in = new FileInputStream(f)){ p.load(in); }
		catch(IOException e){ return null; }
		return p;
	}

	private static void writeMarker(Bundle b){
		Properties p = new Properties();
		p.setProperty("packed", b.packed.getPath());
		p.setProperty("stamp", b.packedStamp);
		p.setProperty("signature", b.signature);
		p.setProperty("pid", Long.toString(ProcessHandle.current().pid())); //the process working on it (purgeStale)
		try{
			b.root.mkdirs();
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			p.store(bytes, "HyperZebra working copy of a stack file");
			XMLwrite.writeAtomically(new File(b.root, MARKER), bytes.toByteArray());
		}catch(IOException e){
			System.err.println("[stack] could not note the working copy of "+b.packed+": "+e.getMessage());
		}
	}

	private static void deleteTree(File f){
		if(f==null || !f.exists()) return;
		if(f.isDirectory() && !Files.isSymbolicLink(f.toPath())){
			File[] children = f.listFiles();
			if(children!=null) for(File c: children) deleteTree(c);
		}
		f.delete();
	}
}

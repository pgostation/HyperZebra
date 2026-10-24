import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.swing.JOptionPane;

/**
 * Working copies: an old program or document copied into HyperZebra HD's "Working Copies" folder and opened from there,
 * so that whatever it does - saving, rewriting its own file, deleting its neighbours - happens to the copy and the
 * originals stay as they were. A program is copied with its folder when the folder is its own (small, and not one of the
 * user's main folders such as Documents or Downloads), otherwise alone; a document alone. The copy gets the program's
 * Application Settings. Mac files keep their resource forks and Finder info.
 */
final class WorkingCopies {
	/** A program's folder is copied with it up to this size and number of items. */
	static final long MAX_FOLDER_BYTES = 512L*1024*1024;
	static final int MAX_FOLDER_ITEMS = 5000;

	/** Replaces the confirmation when set (tests): the message → true to copy. */
	static volatile java.util.function.Predicate<String> confirmer;

	private WorkingCopies(){}

	static File folder(){ return new File(AppPaths.stateDirectory(), "Working Copies"); }

	/** What a copy of {@code original} takes: the program's own folder, or just the file (or the package) itself. */
	static File source(File original){
		File f = original.getAbsoluteFile();
		File unit = f;
		for(File p = f.getParentFile(); p!=null; p = p.getParentFile()) if(p.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".app")){ unit = p; break; } // a Mac OS 9 package as a whole
		if(!unit.isFile() || !MacApp68k.isApplication(unit)) return unit;
		File folder = unit.getParentFile();
		return folder!=null && isOwnFolder(folder) ? folder : unit;
	}

	/** A folder that holds one program and its files, not one of the user's main folders, and small enough to copy. */
	static boolean isOwnFolder(File folder){
		File home = new File(System.getProperty("user.home", "."));
		String c = Sandbox.canonical(folder);
		if(folder.getParentFile()==null || c.equals(Sandbox.canonical(home))) return false;
		for(String main: new String[]{"Desktop", "Documents", "Downloads", "Applications", "Library"})
			if(c.equals(Sandbox.canonical(new File(home, main)))) return false;
		if(Sandbox.isBuiltIn(folder)) return false; // already in HyperZebra HD
		long[] size = size(folder, new long[2]);
		return size[0]<=MAX_FOLDER_BYTES && size[1]<=MAX_FOLDER_ITEMS;
	}
	private static long[] size(File f, long[] acc){
		if(acc[1]>MAX_FOLDER_ITEMS || acc[0]>MAX_FOLDER_BYTES) return acc;
		acc[1]++;
		if(f.isFile()){ acc[0] += f.length(); return acc; }
		File[] children = f.listFiles();
		if(children!=null) for(File c: children) size(c, acc);
		return acc;
	}

	/**
	 * Asks (on the event thread), then copies {@code original} (a program or a document) into Working Copies on a thread of
	 * its own and hands the file to open in the copy (the program inside a copied folder) to {@code then} on the event
	 * thread. Nothing happens when the user cancels; a failed copy is reported and {@code then} is not called.
	 */
	static void make(final Component owner, final File original, final java.util.function.Consumer<File> then){
		final File source = source(original);
		long[] size = size(source, new long[2]);
		String name = LauncherStore.composed(source.getName());
		String what = source.isDirectory()
			? International.t("the folder \"{0}\" ({1} items, {2})", name, size[1], megabytes(size[0]))
			: International.t("\"{0}\" ({1})", name, megabytes(size[0]));
		String message = International.t("HyperZebra copies {0} into the \"Working Copies\" folder of HyperZebra HD and opens the copy.\nThe originals are not changed.", what);
		if(!confirm(owner, message)) return;
		Thread t = new Thread(new Runnable(){ public void run(){
			final File opened;
			try{
				opened = copyFor(original, source);
			}catch(final IOException e){
				final String text = text("The working copy could not be made.")+"\n"+e.getMessage();
				System.err.println(text);
				if(!java.awt.GraphicsEnvironment.isHeadless() && confirmer==null)
					javax.swing.SwingUtilities.invokeLater(new Runnable(){ public void run(){ JOptionPane.showMessageDialog(owner, text, text("Working Copy"), JOptionPane.ERROR_MESSAGE); }});
				return;
			}
			javax.swing.SwingUtilities.invokeLater(new Runnable(){ public void run(){ then.accept(opened); }});
		}}, "working copy");
		t.start();
	}

	/** Copies {@code source} (what {@link #source} chose for {@code original}) and returns the copy of {@code original}; the
	 *  copied program gets the original's Application Settings. */
	static File copyFor(File original, File source) throws IOException {
		File target = copy(source);
		File opened = source.equals(original.getAbsoluteFile()) ? target : new File(target, source.toPath().relativize(original.getAbsoluteFile().toPath()).toString());
		File program = MacApp68k.programOf(original);
		if(LauncherStore.enabled && program.isFile() && MacApp68k.isApplication(program)) LauncherStore.get().copySettings(program, MacApp68k.programOf(opened));
		return opened;
	}
	private static boolean confirm(Component owner, String message){
		java.util.function.Predicate<String> c = confirmer;
		if(c!=null) return c.test(message);
		if(java.awt.GraphicsEnvironment.isHeadless()) return false;
		String[] options = { text("Copy and Open"), text("Cancel") };
		return JOptionPane.showOptionDialog(owner, message, text("Working Copy"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0])==0;
	}

	/** Copies a file or a folder into Working Copies under a name not used yet; returns the copy. */
	static File copy(File source) throws IOException {
		File dir = folder();
		if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create "+dir);
		File target = unique(dir, source.getName());
		try{
			copyTree(source, target);
		}catch(IOException e){
			deleteTree(target);
			throw e;
		}
		return target;
	}
	private static void copyTree(File from, File to) throws IOException {
		if(from.isDirectory()){
			if(!to.mkdirs() && !to.isDirectory()) throw new IOException("cannot create "+to);
			byte[] info = FinderInfo68k.stored(from);
			if(info!=null) try{ FinderInfo68k.write(to, info); }catch(RuntimeException ignored){}
			File[] children = from.listFiles();
			if(children!=null) for(File c: children){
				String n = c.getName();
				if(n.startsWith("._") || n.equals(".DS_Store")) continue; // sidecars go with their file (copyFile)
				copyTree(c, new File(to, n));
			}
			return;
		}
		copyFile(from, to);
	}

	/** Copies one Mac file: its data, its resource fork (whatever way it is stored here) and its Finder info. */
	static void copyFile(File from, File to) throws IOException {
		Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
		ResourceFile68k probe = new ResourceFile68k(from);
		byte[] fork = ResourceFile68k.readForkBytes(from, probe);
		if(fork!=null && fork.length>0 && probe.storage!=ResourceFile68k.Storage.MACBINARY)
			ResourceFile68k.writeForkBytes(to, fork, ResourceFile68k.Storage.NONE);
		byte[] info = FinderInfo68k.stored(from);
		if(info!=null) FinderInfo68k.write(to, info);
	}

	private static File unique(File dir, String name){
		File first = new File(dir, name);
		if(!first.exists()) return first;
		String base = name, ext = ""; int dot = name.lastIndexOf('.');
		if(dot>0){ base = name.substring(0, dot); ext = name.substring(dot); }
		for(int i=2;; i++){ File f = new File(dir, base+" "+i+ext); if(!f.exists()) return f; }
	}
	private static void deleteTree(File f){
		if(f==null || !f.exists()) return;
		File[] children = f.listFiles();
		if(children!=null) for(File c: children) deleteTree(c);
		f.delete();
	}
	private static String megabytes(long bytes){
		if(bytes<1024*1024) return Math.max(1, (bytes+1023)/1024)+" KB";
		return String.format(java.util.Locale.ROOT, "%.1f MB", bytes/1048576.0);
	}
	private static String text(String english){
		try{ if(PCARD.pc!=null && PCARD.pc.intl!=null) return PCARD.pc.intl.getDialogText(english); }catch(RuntimeException ignored){}
		return english;
	}
}

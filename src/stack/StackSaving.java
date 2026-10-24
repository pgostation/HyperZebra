import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * What happens when a stack cannot be written ({@link XMLwrite} reports it): a full disk, a folder without write
 * permission, a removed external disk. The user is told, and the changes stay marked as unsaved until a write succeeds.
 * <ul>
 * <li>The autosave tells once per failure ({@link #autosaveFailed}): Try Again, Save Elsewhere…, Later. It keeps trying in
 *     the background meanwhile, and the card window's title says the stack is not saved.</li>
 * <li>Closing the stack, quitting and going to another stack wait for the write ({@link #ensureSaved}); when it fails they
 *     ask: Try Again, Save Elsewhere…, or stop (Don't Close / Don't Quit / Stay Here), and nothing is thrown away.</li>
 * </ul>
 * "Save Elsewhere…" copies the stack to a new stack file (.hzb) chosen by the user, writes it there and goes on with the copy.
 * Copies are stack files (StackBundle) whether the stack is one or an older .hzb folder.
 */
final class StackSaving {
	/** Why the stack is being written. */
	enum Purpose { AUTOSAVE, CLOSE, QUIT, LEAVE }
	/** The user's answer to a failed write. */
	enum Choice { RETRY, SAVE_ELSEWHERE, STOP }

	/** Replaces the dialog when set (tests): the stack and the failure → the answer; null shows the dialog. */
	static volatile BiFunction<OStack, String, Choice> decider;
	/** Replaces the file chooser of "Save Elsewhere…" when set (tests): the stack → the new .hzb file, null = cancelled. */
	static volatile java.util.function.Function<OStack, File> elsewhereChooser;

	private StackSaving(){}

	/**
	 * Writes the stack's unsaved changes before it is closed (or HyperZebra quits, or a script leaves it); true when they
	 * are on disk or there were none, false when the user chose to keep the stack open after a failed write.
	 * Any thread; a thread other than the event thread waits for the dialog.
	 */
	static boolean ensureSaved(OStack stack, Purpose purpose){
		if(stack==null || stack.saveXML==null) return true;
		XMLwrite writer = stack.saveXML;
		while(true){
			if(stack.cdCacheList==null) return true; //既に閉じた
			boolean saved;
			//閉じる・終了・移動の保存は進捗ウインドウを出さず、待ちカーソルだけにする(自動保存はカーソルも変えない)
			try(StackProgress.Busy busy = purpose==Purpose.AUTOSAVE ? () -> {} : StackProgress.busy()){
				saved = writer.saveIfNeeded();
			}
			if(saved){ recovered(stack); return true; }
			Choice choice = ask(stack, writer.lastError, purpose);
			if(choice==Choice.RETRY) continue;
			if(choice==Choice.SAVE_ELSEWHERE){
				if(saveElsewhere(stack)){ recovered(stack); return true; }
				continue; //別の場所にも保存できなかった(またはやめた): もう一度尋ねる
			}
			writer.failureReported = true;
			markUnsaved(stack, true);
			return false;
		}
	}

	/** The autosave could not write the stack: tell the user once (until a write succeeds) without holding up the autosave. */
	static void autosaveFailed(final OStack stack){
		final XMLwrite writer = stack.saveXML;
		if(writer==null) return;
		writer.failureReported = true;
		markUnsaved(stack, true);
		if(decider==null && java.awt.GraphicsEnvironment.isHeadless()) return;
		Thread t = new Thread(new Runnable(){ public void run(){ ensureSaved(stack, Purpose.AUTOSAVE); }}, "stack save failure");
		t.setDaemon(true);
		t.start();
	}

	/** A write succeeded after a failure: the title loses its "not saved" mark. */
	static void recovered(OStack stack){
		markUnsaved(stack, false);
	}

	/** The card window's title says the stack's changes are not on disk. */
	static void markUnsaved(final OStack stack, final boolean unsaved){
		final PCARD pc = PCARD.pc;
		if(pc==null || pc.stack!=stack || stack.name==null) return;
		final String title = unsaved ? stack.getWindowTitle()+" — "+text("Not Saved") : stack.getWindowTitle();
		SwingUtilities.invokeLater(new Runnable(){ public void run(){ if(pc.stack==stack) pc.setTitle(title); }});
	}

	// ------------------------------------------------------------------ the dialog

	private static Choice ask(final OStack stack, final String failure, final Purpose purpose){
		BiFunction<OStack, String, Choice> d = decider;
		if(d!=null){ Choice c = d.apply(stack, failure); if(c!=null) return c; }
		if(java.awt.GraphicsEnvironment.isHeadless()){
			System.err.println("[stack] not saved (headless, kept open): "+failure);
			return Choice.STOP;
		}
		final Choice[] answer = { Choice.STOP };
		Runnable show = new Runnable(){ public void run(){ answer[0] = showDialog(stack, failure, purpose); }};
		if(SwingUtilities.isEventDispatchThread()) show.run();
		else try{ SwingUtilities.invokeAndWait(show); }catch(Exception e){ e.printStackTrace(); }
		return answer[0];
	}

	private static Choice showDialog(OStack stack, String failure, Purpose purpose){
		String name = stack.name!=null ? stack.name : LauncherStore.displayName(stack.file);
		StringBuilder message = new StringBuilder();
		message.append(International.t("The stack \"{0}\" could not be saved.", name));
		message.append("\n\n").append(reason(failure));
		message.append("\n\n").append(text(purpose==Purpose.AUTOSAVE
			? "Your changes are kept in memory but are not on disk yet. HyperZebra keeps trying; you can also save the stack somewhere else."
			: "Your changes are not on disk yet. Try again, save the stack somewhere else, or keep it open."));
		String stop = text(purpose==Purpose.AUTOSAVE ? "Later" : purpose==Purpose.QUIT ? "Don't Quit" : purpose==Purpose.LEAVE ? "Stay Here" : "Don't Close");
		boolean elsewhere = canSaveElsewhere(stack);
		String[] options = elsewhere ? new String[]{ text("Try Again"), text("Save Elsewhere…"), stop } : new String[]{ text("Try Again"), stop };
		Component owner = PCARD.pc!=null && PCARD.pc.isVisible() ? PCARD.pc : GLauncher.instance;
		int choice = JOptionPane.showOptionDialog(owner, message.toString(), text("Stack Not Saved"), JOptionPane.DEFAULT_OPTION,
			JOptionPane.ERROR_MESSAGE, null, options, options[0]);
		if(choice==0) return Choice.RETRY;
		if(elsewhere && choice==1) return Choice.SAVE_ELSEWHERE;
		return Choice.STOP;
	}

	/** XMLwrite's failure text in the user's language (it starts with one of a few known phrases). */
	static String reason(String failure){
		if(failure==null) return "";
		for(String known: new String[]{ "The disk is full: ", "Writing is not allowed: ", "The folder is missing: " }){
			if(failure.startsWith(known)) return text(known.substring(0, known.length()-2)+".")+"\n"+failure.substring(known.length());
		}
		return failure;
	}

	// ------------------------------------------------------------------ Save Elsewhere

	/** A folder stack (_stack.xml or toc.xml in its folder) can be copied to another place as a whole. */
	static boolean canSaveElsewhere(OStack stack){
		if(stack==null || stack.file==null) return false;
		String n = stack.file.getName();
		return (n.equals("_stack.xml") || n.equals("toc.xml")) && stack.file.getParentFile()!=null;
	}

	/**
	 * Copies the stack to a new stack file (.hzb) the user chooses, writes the stack there and goes on with the copy (later
	 * changes are saved there); false when the user cancelled or it could not be written there either (the original
	 * stays as it was, and the copy is removed).
	 */
	static boolean saveElsewhere(OStack stack){
		if(!canSaveElsewhere(stack)) return false;
		File dest = chooseDestination(stack);
		if(dest==null) return false;
		return saveTo(stack, dest);
	}

	/** {@link #saveElsewhere} to a known folder (also the test hook). */
	static boolean saveTo(OStack stack, File dest){
		XMLwrite writer = stack.saveXML;
		if(writer==null || dest==null) return false;
		if(!dest.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) dest = new File(dest.getPath()+".hzb");
		if(dest.exists()){ writer.lastError = "A file with that name already exists: "+dest.getPath(); return false; }
		synchronized(writer){
			File oldFile = stack.file; String oldPath = stack.path;
			File source = oldFile.getParentFile();
			//the copy is a stack file: its working folder gets the stack's files, the stack's own write makes the file
			File folder;
			try{
				folder = StackBundle.create(dest);
				copyBundle(source.toPath(), folder.toPath(), oldFile.getName());
			}catch(IOException e){
				StackBundle.discard(dest);
				writer.lastError = XMLwrite.describe(e, dest);
				return false;
			}
			stack.file = new File(folder, oldFile.getName());
			stack.path = stack.file.getPath();
			Sandbox.trust(dest);
			Sandbox.stackOpened(stack.file);
			stack.changed = true; //全体を書き直す
			if(!writer.saveStackNow()){
				String why = writer.lastError;
				stack.file = oldFile; stack.path = oldPath;
				StackBundle.discard(folder);
				dest.delete();
				writer.lastError = why;
				return false;
			}
		}
		if(LauncherStore.enabled) LauncherStore.get().recordOpened(dest, LauncherStore.KIND_STACK);
		System.err.println("[stack] saved elsewhere: "+dest);
		return true;
	}

	private static File chooseDestination(final OStack stack){
		java.util.function.Function<OStack, File> c = elsewhereChooser;
		if(c!=null) return c.apply(stack);
		if(java.awt.GraphicsEnvironment.isHeadless()) return null;
		while(true){
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(text("Save Elsewhere…"));
			File documents = new File(System.getProperty("user.home", "."), "Documents");
			chooser.setCurrentDirectory(documents.isDirectory() ? documents : new File(System.getProperty("user.home", ".")));
			String name = LauncherStore.displayName(stack.file);
			chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name+".hzb"));
			FileChooserPlaces.attach(chooser);
			Component owner = PCARD.pc!=null && PCARD.pc.isVisible() ? PCARD.pc : GLauncher.instance;
			if(PCARD.showFileChooser(chooser, owner, true, false)!=JFileChooser.APPROVE_OPTION) return null;
			File f = chooser.getSelectedFile();
			if(f==null) return null;
			if(!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) f = new File(f.getPath()+".hzb");
			if(!f.exists()) return f;
			final File taken = f;
			try{
				SwingUtilities.invokeAndWait(new Runnable(){ public void run(){
					JOptionPane.showMessageDialog(PCARD.pc, text("A file with that name already exists. Choose another name.")+"\n"+taken.getPath(),
						text("Save Elsewhere…"), JOptionPane.WARNING_MESSAGE);
				}});
			}catch(Exception ignored){}
		}
	}

	// ------------------------------------------------------------------ Save a Copy…

	/** Replaces the file dialog of "Save a Copy…" when set (tests): the stack → the copy's .hzb file, null = cancelled. */
	static volatile java.util.function.Function<OStack, File> copyChooser;

	/**
	 * HyperCard's File menu "Save a Copy…": asks for a name and place, writes the stack's pending changes and copies the
	 * whole stack there. The open stack stays as it is (later changes still go to its own file). Tells the user when the
	 * copy could not be made; returns null when it was made or the user cancelled, else why not.
	 */
	static String saveACopyFromMenu(OStack stack){
		if(!canSaveElsewhere(stack)) return "This stack cannot be copied.";
		if(PCARD.pc!=null && PCARD.pc.stack==stack && PCARD.pc.tool!=null) PaintTool.saveCdPictures();
		File dest = chooseCopyDestination(stack);
		if(dest==null) return null;
		String failure = saveACopy(stack, dest, Sandbox.scriptRequester());
		if(failure!=null){
			System.err.println("[stack] copy not saved: "+failure);
			if(!java.awt.GraphicsEnvironment.isHeadless() && copyChooser==null && PCARD.pc!=null)
				new GDialog(PCARD.pc, text("The copy of the stack could not be saved.")+"\n"+reason(failure), null, "OK", null, null);
		}
		return failure;
	}

	/**
	 * Writes a copy of {@code stack} as the new stack file {@code dest} (".hzb" is added when missing): the stack's unsaved
	 * changes are written first, then its folder (the stack file, pictures, resources; sealed files stay sealed) is packed.
	 * The open stack keeps its file. Null when done, otherwise why not (in XMLwrite's words; nothing is left at {@code dest}).
	 */
	static String saveACopy(OStack stack, File dest, String requester){
		if(!canSaveElsewhere(stack) || dest==null) return "This stack cannot be copied.";
		if(!dest.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) dest = new File(dest.getPath()+".hzb");
		dest = dest.getAbsoluteFile();
		if(dest.exists()) return "A file with that name already exists: "+dest.getPath();
		File source = stack.file.getAbsoluteFile().getParentFile();
		try{
			Path src = source.toPath().toRealPath();
			Path parent = dest.getParentFile()!=null && dest.getParentFile().exists() ? dest.getParentFile().toPath().toRealPath() : null;
			if(parent!=null && parent.startsWith(src)) return "The copy cannot be saved inside the stack itself: "+dest.getPath();
		}catch(IOException e){
			return "The folder is missing: "+source.getPath();
		}
		if(!Sandbox.allowFile(dest, Sandbox.Access.WRITE, requester)) return "Writing is not allowed: "+dest.getPath();
		XMLwrite writer = stack.saveXML;
		Object lock = writer!=null ? writer : stack;
		synchronized(lock){ //the autosave does not write while the files are copied
			if(writer!=null && !writer.saveIfNeeded()) return writer.lastError!=null ? writer.lastError : "The stack could not be saved.";
			try{
				StackBundle.packFolder(source, dest); //the copy is a stack file (.hzb ZIP); nothing is left when it fails
			}catch(IOException e){
				return XMLwrite.describe(e, dest);
			}
		}
		System.err.println("[stack] copy saved: "+dest);
		return null;
	}

	private static File chooseCopyDestination(OStack stack){
		java.util.function.Function<OStack, File> c = copyChooser;
		if(c!=null) return c.apply(stack);
		if(java.awt.GraphicsEnvironment.isHeadless()) return null;
		String name = stack.name!=null && !stack.name.isEmpty() ? stack.name : LauncherStore.displayName(stack.file);
		name = String.format(text("Copy of %s"), name).replace('/', '_').replace(':', '_');
		File directory = GMenuBrowse.stackChooserDirectory(stack.file);
		while(true){
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(text("Save a Copy of the Stack"));
			chooser.setDialogType(JFileChooser.SAVE_DIALOG);
			if(directory!=null && directory.isDirectory()) chooser.setCurrentDirectory(directory);
			chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name+".hzb"));
			FileChooserPlaces.attach(chooser);
			Component owner = PCARD.pc!=null && PCARD.pc.isVisible() ? PCARD.pc : GLauncher.instance;
			//the stack may write where its user chose (Sandbox), and nowhere else on the way
			if(PCARD.showFileChooser(chooser, owner, true, true)!=JFileChooser.APPROVE_OPTION) return null;
			File f = chooser.getSelectedFile();
			if(f==null) return null;
			if(!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) f = new File(f.getPath()+".hzb");
			if(!f.exists()) return f;
			directory = f.getParentFile();
			name = LauncherStore.displayName(f);
			final File taken = f;
			PCARD.runOnEDT(new Runnable(){ public void run(){
				JOptionPane.showMessageDialog(owner, text("A file with that name already exists. Choose another name.")+"\n"+taken.getPath(),
					text("Save a Copy of the Stack"), JOptionPane.WARNING_MESSAGE);
			}});
		}
	}

	/** Copies the stack folder's files (not the stack file itself, which is written afresh, nor unfinished temporary files). */
	static void copyBundle(Path from, Path to, String stackFileName) throws IOException {
		Files.createDirectories(to);
		try(Stream<Path> all = Files.walk(from)){
			for(Path p: (Iterable<Path>)all::iterator){
				if(p.equals(from)) continue;
				Path rel = from.relativize(p);
				String n = p.getFileName().toString();
				if(rel.getNameCount()==1 && (n.equals(stackFileName) || n.startsWith(stackFileName+".") && n.endsWith(".tmp"))) continue;
				Path q = to.resolve(rel.toString());
				if(Files.isDirectory(p)) Files.createDirectories(q);
				else Files.copy(p, q, StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
	}

	private static String text(String english){
		try{ if(PCARD.pc!=null && PCARD.pc.intl!=null) return PCARD.pc.intl.getDialogText(english); }catch(RuntimeException ignored){}
		return english;
	}
}

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import javax.swing.SwingUtilities;

/**
 * One resource-editing session per stack, shared by the type index, the type windows and the editors of single
 * resources, in the manner of a resource-editor file window: every change goes into a working copy of the stack's resources
 * ({@link Rsrc#workingCopy}) whose files live in a temporary folder, the stack and its folder are untouched until
 * Save (Cmd-S in any of the windows), and closing the last window (or the index, which closes them all) asks whether
 * to save when there are unsaved changes.
 */
class ResSession {
	private static final IdentityHashMap<OStack,ResSession> sessions = new IdentityHashMap<OStack,ResSession>();

	final OStack stack;
	final Rsrc work;
	final File stagingDir;
	final ArrayList<Window> windows = new ArrayList<Window>();
	private boolean ended;

	private ResSession(OStack stack){
		this.stack = stack;
		if(stack.rsrc==null) stack.rsrc = new Rsrc(stack);
		File dir = null;
		try {
			File base = new File(AppPaths.stateDirectory(), "resource_staging");
			base.mkdirs();
			dir = java.nio.file.Files.createTempDirectory(base.toPath(), "edit").toFile();
		} catch(IOException e) {
			System.err.println("Could not create the resource staging folder: "+e.getMessage());
			try { dir = TempFiles.createDirectory("resources-"); }
			catch(IOException e2) { dir = new File(System.getProperty("java.io.tmpdir"), "HyperZebra-resources-"+System.nanoTime()); dir.mkdirs(); }
		}
		stagingDir = dir;
		work = stack.rsrc.workingCopy(dir);
	}

	/** The session of the stack, started when there is none. */
	static synchronized ResSession of(OStack stack){
		ResSession s = sessions.get(stack);
		if(s==null){
			s = new ResSession(stack);
			sessions.put(stack, s);
		}
		return s;
	}

	static synchronized ResSession find(OStack stack){
		return stack==null ? null : sessions.get(stack);
	}

	/** The session a working copy belongs to (null for the stack's own Rsrc). */
	static ResSession ofRsrc(Rsrc rsrc){
		if(rsrc==null || rsrc.stagingDir==null) return null;
		ResSession s = find(rsrc.ownerstack);
		return s!=null && s.work==rsrc ? s : null;
	}

	boolean isDirty(){ return work.dirty; }

	String stackName(){
		String name = stack.getTitleName();
		return name.isEmpty() ? text("Untitled") : name;
	}

	/** A window of the session: the session lives while any of its windows does. */
	void attach(Window w){
		if(windows.contains(w)) return;
		windows.add(w);
		w.addWindowListener(new WindowAdapter(){
			@Override public void windowClosed(WindowEvent e){ detach(w); }
		});
	}

	void detach(Window w){
		windows.remove(w);
		if(windows.isEmpty()) end();
	}

	boolean isLastWindow(Window w){
		return windows.size()==1 && windows.get(0)==w;
	}

	/**
	 * Called before a window of the session closes. When it is the last one and there are unsaved changes, asks
	 * whether to save them; returns false when the user cancelled (the window stays open).
	 */
	boolean confirmClose(Window w){
		if(!isLastWindow(w) || !isDirty()) return true;
		return askSave(w);
	}

	/** Closing the index closes every window of the session, asking about unsaved changes first. */
	boolean confirmCloseAll(Window w){
		if(isDirty() && !askSave(w)) return false;
		for(Window other : new ArrayList<Window>(windows)){
			if(other!=w) other.dispose();
		}
		return true;
	}

	/** Save / Don't Save / Cancel; false when cancelled. */
	private boolean askSave(Window w){
		String dontSave = dialogText("Don't Save");
		String cancel = dialogText("Cancel");
		String save = dialogText("Save");
		String message = dialogText("Save changes to the resources of “%1” before closing?").replace("%1", stackName());
		new GDialog(w instanceof java.awt.Frame ? (java.awt.Frame)w : null, message, null, dontSave, cancel, save);
		if(GDialog.clicked.equals(cancel)) return false;
		if(GDialog.clicked.equals(save)) return save();
		discard();
		return true;
	}

	/** Writes the working copy back to the stack; false (after telling why) when that failed. */
	boolean save(){
		try {
			stack.rsrc.commit(work);
		} catch(IOException e) {
			e.printStackTrace();
			new GDialog(null, dialogText("The resources could not be saved.")+"\n"+e.getMessage(), null, "OK", null, null);
			return false;
		}
		refreshWindows();
		if(PCARD.pc!=null && PCARD.pc.stack==stack) OCard.reloadCurrentCard();
		return true;
	}

	/** Forgets the unsaved changes (the working copy stays for the remaining windows). */
	void discard(){
		work.dirty = false;
	}

	/** The type windows and the index show the state after a save or a change from another window. */
	void refreshWindows(){
		for(Window w : new ArrayList<Window>(windows)){
			if(w instanceof ResEditIndex) ((ResEditIndex)w).setTable(work);
		}
	}

	/** The type windows other than {@code except} list the resources again (a paste added resources of their types). */
	void refreshTypeWindows(Window except){
		for(Window w : new ArrayList<Window>(windows)){
			if(w!=except && w instanceof ResTypeEditor && ((ResTypeEditor)w).object==null) ((ResTypeEditor)w).refreshList();
		}
	}

	private void end(){
		if(ended) return;
		ended = true;
		synchronized(ResSession.class){ sessions.remove(stack); }
		deleteRecursively(stagingDir);
	}

	private static void deleteRecursively(File f){
		if(f==null) return;
		File[] children = f.listFiles();
		if(children!=null) for(File c : children) deleteRecursively(c);
		f.delete();
	}

	/**
	 * The stack closes: unsaved resource changes are saved or dropped (asked, since a cancel is not possible any
	 * more), then the session's windows close.
	 */
	static void stackClosing(OStack stack){
		ResSession s = find(stack);
		if(s==null) return;
		if(s.isDirty()){
			String dontSave = dialogText("Don't Save");
			String save = dialogText("Save");
			String message = dialogText("Save changes to the resources of “%1” before closing?").replace("%1", s.stackName());
			new GDialog(null, message, null, dontSave, null, save);
			if(GDialog.clicked.equals(save)){
				try { stack.rsrc.commit(s.work); } catch(IOException e){ e.printStackTrace(); }
			}
			s.work.dirty = false;
		}
		Runnable close = () -> { for(Window w : new ArrayList<Window>(s.windows)) w.dispose(); };
		if(SwingUtilities.isEventDispatchThread()) close.run();
		else SwingUtilities.invokeLater(close);
	}

	static String text(String english){
		return PCARDFrame.pc!=null && PCARDFrame.pc.intl!=null ? PCARDFrame.pc.intl.getText(english) : english;
	}

	static String dialogText(String english){
		return PCARDFrame.pc!=null && PCARDFrame.pc.intl!=null ? PCARDFrame.pc.intl.getDialogText(english) : english;
	}
}

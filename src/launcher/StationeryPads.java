import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Stationery pads (kIsStationery, $0800 of the Finder flags) opened from the launcher, the way the System 7 Finder opens
 * them: a program that is not stationery-aware (SIZE flag $0010) is handed a new document - a copy of the pad the user
 * names, in the pad's folder (Working Copies when that folder cannot be written) - never the pad itself, which stays as
 * it is for the next document. A stationery-aware program gets the pad and makes its untitled document from it itself.
 * The copy has its data, resource fork and Finder info, without the stationery flag.
 */
final class StationeryPads {
	/** Replaces the naming dialog when set (tests): the pad and the name offered → the name chosen, null to cancel. */
	static volatile java.util.function.BiFunction<File,String,String> namer;

	private StationeryPads(){}

	/** A stationery pad: the stationery bit of the Finder flags recorded for the file. */
	static boolean isStationery(File f){
		if(f==null || !f.isFile()) return false;
		byte[] info = FinderInfo68k.stored(f);
		return info!=null && info.length>=10 && (info[8]&0x08)!=0;
	}

	/**
	 * The documents to hand {@code program}: each pad replaced by a new document made from it, unless the program is
	 * stationery-aware; a pad whose naming was cancelled (or whose copy failed) is left out.
	 */
	static List<File> documentsFor(Component owner, File program, List<File> documents){
		List<File> out = new ArrayList<File>(documents.size());
		Boolean aware = null;
		for(File d: documents){
			if(!isStationery(d)){ out.add(d); continue; }
			if(aware==null) aware = MacApp68k.isStationeryAware(program);
			if(aware){ out.add(d); continue; }
			File made = newDocument(owner, d);
			if(made!=null) out.add(made);
		}
		return out;
	}

	/** A new document made from the pad under the name the user gives it; null when cancelled or when it could not be made. */
	static File newDocument(Component owner, File pad){
		File dir = pad.getAbsoluteFile().getParentFile();
		if(dir==null || !Files.isWritable(dir.toPath())){ // a locked disk, a read-only disk image: the copy goes where HyperZebra may write
			dir = WorkingCopies.folder();
			if(!dir.isDirectory() && !dir.mkdirs()){ problem(owner, International.t("The new document could not be made: {0}", dir.getPath())); return null; }
		}
		String name = unusedName(dir, International.t("{0} copy", LauncherStore.displayName(pad)));
		while(true){
			name = askName(owner, pad, name);
			if(name==null) return null;
			name = name.trim().replace('/', '-');
			if(name.isEmpty()) continue;
			File target = new File(dir, name);
			if(target.exists()){ problem(owner, International.t("\"{0}\" already exists. Choose another name.", name)); continue; }
			try{
				copy(pad, target);
				return target;
			}catch(IOException e){
				target.delete();
				problem(owner, International.t("The new document could not be made: {0}", e.getMessage()));
				return null;
			}
		}
	}

	/** Copies the pad into {@code target}, which then is an ordinary document: no stationery flag, no lock, placed afresh. */
	static void copy(File pad, File target) throws IOException {
		WorkingCopies.copyFile(pad, target);
		target.setWritable(true);
		byte[] info = FinderInfo68k.stored(target);
		if(info!=null){
			info = info.clone();
			info[8] &= ~(0x08|0x01); // kIsStationery, kHasBeenInited: the Finder places the new document itself
			for(int i=10; i<14 && i<info.length; i++) info[i] = 0; // fdLocation
			FinderInfo68k.write(target, info);
		}
	}

	private static String unusedName(File dir, String name){
		if(!new File(dir, name).exists()) return name;
		for(int i=2;; i++){ String n = name+" "+i; if(!new File(dir, n).exists()) return n; }
	}

	private static String askName(Component owner, File pad, String offered){
		java.util.function.BiFunction<File,String,String> n = namer;
		if(n!=null) return n.apply(pad, offered);
		if(java.awt.GraphicsEnvironment.isHeadless()) return null;
		JTextField field = new JTextField(offered, 28);
		JPanel panel = new JPanel(new java.awt.BorderLayout(0, 8));
		panel.add(new JLabel("<html>"+escape(International.t("\"{0}\" is a stationery pad: a new document is made from it and opened.", LauncherStore.displayName(pad)))
			+"<br><br>"+escape(International.t("Name of the new document:"))+"</html>"), java.awt.BorderLayout.NORTH);
		panel.add(field, java.awt.BorderLayout.CENTER);
		SwingUtilities.invokeLater(new Runnable(){ public void run(){ field.requestFocusInWindow(); field.selectAll(); }});
		String[] options = { International.t("Save"), International.t("Cancel") };
		int chosen = JOptionPane.showOptionDialog(owner, panel, International.t("Stationery Pad"), JOptionPane.DEFAULT_OPTION,
			JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
		return chosen==0 ? field.getText() : null;
	}

	private static void problem(Component owner, String message){
		if(namer!=null || java.awt.GraphicsEnvironment.isHeadless()){ System.err.println("[stationery] "+message); return; }
		JOptionPane.showMessageDialog(owner, message, International.t("Stationery Pad"), JOptionPane.WARNING_MESSAGE);
	}

	private static String escape(String s){ return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}

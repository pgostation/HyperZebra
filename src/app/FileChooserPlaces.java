import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * A "Places" list shown beside a JFileChooser: the home folder, the desktop, the documents folder,
 * HyperZebra HD and every mounted volume, each one click away.
 *
 * Swing's chooser has no Finder sidebar, and on macOS its only root is "/": /Volumes carries the
 * Finder "hidden" flag, so the chooser hides it and an external drive, a network share or a mounted
 * disk image cannot be reached at all (the launcher's AppleDouble conversion could only ever see the
 * boot volume). The other hosts get their drive letters and mount points here for the same reason.
 */
final class FileChooserPlaces {
	private FileChooserPlaces(){}

	/** One entry of the list: the name shown and the folder it goes to. */
	private static final class Place {
		final String name;final File file;
		Place(String name,File file){this.name=name;this.file=file;}
		public String toString(){return name;}
	}

	/** Puts the list into the chooser's accessory pane (no-op when the chooser already has one). */
	static void attach(final JFileChooser chooser){
		if(chooser==null||chooser.getAccessory()!=null)return;
		List<Place> places=places(chooser);
		if(places.isEmpty())return;
		DefaultListModel<Place> model=new DefaultListModel<Place>();
		for(Place place:places)model.addElement(place);
		final JList<Place> list=new JList<Place>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setVisibleRowCount(Math.min(model.size(),12));
		list.addListSelectionListener(new ListSelectionListener(){
			public void valueChanged(ListSelectionEvent e){
				if(e.getValueIsAdjusting())return;
				Place place=list.getSelectedValue();
				if(place!=null&&place.file.isDirectory())chooser.setCurrentDirectory(place.file);
			}
		});
		JPanel panel=new JPanel(new java.awt.BorderLayout(0,4));
		panel.setBorder(BorderFactory.createEmptyBorder(0,8,0,0));
		JLabel title=new JLabel(text("Places"));
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD,title.getFont().getSize2D()-1f));
		panel.add(title,java.awt.BorderLayout.NORTH);
		JScrollPane scroller=new JScrollPane(list);
		scroller.setPreferredSize(new java.awt.Dimension(190,scroller.getPreferredSize().height));
		panel.add(scroller,java.awt.BorderLayout.CENTER);
		chooser.setAccessory(panel);
	}

	/** The usual folders, then every volume: existing directories only, each one listed once. */
	private static List<Place> places(JFileChooser chooser){
		javax.swing.filechooser.FileSystemView view=chooser.getFileSystemView();
		List<Place> places=new ArrayList<Place>();
		Set<String> seen=new LinkedHashSet<String>();
		File home=new File(System.getProperty("user.home","."));
		add(places,seen,text("Home Folder"),home);
		add(places,seen,text("Desktop"),new File(home,"Desktop"));
		add(places,seen,text("Documents"),new File(home,"Documents"));
		add(places,seen,text("HyperZebra HD"),AppPaths.stateDirectory());
		for(File volume:volumes()){
			String name=view!=null?view.getSystemDisplayName(volume):null;
			if(name==null||name.trim().isEmpty())name=volume.getName().isEmpty()?volume.getPath():volume.getName();
			add(places,seen,name,volume);
		}
		return places;
	}

	private static void add(List<Place> places,Set<String> seen,String name,File file){
		if(file==null||!file.isDirectory())return;
		if(seen.add(canonical(file)))places.add(new Place(name,file));
	}

	private static String canonical(File file){
		try{return file.getCanonicalPath();}catch(java.io.IOException e){return file.getAbsolutePath();}
	}

	/**
	 * Mounted volumes: the children of /Volumes which macOS hides, then the file system roots.
	 * That order gives the boot volume its Finder name ("Macintosh HD" rather than "/", which is the
	 * same folder and drops out of the list as a duplicate); Apple's own mount points (the Time Machine
	 * snapshots) are left out.
	 */
	private static List<File> volumes(){
		List<File> volumes=new ArrayList<File>();
		File[] children=new File("/Volumes").listFiles();
		if(children!=null)java.util.Arrays.sort(children,new java.util.Comparator<File>(){
			public int compare(File a,File b){return a.getName().compareToIgnoreCase(b.getName());}
		});
		if(children!=null)for(File child:children){
			String name=child.getName();
			if(name.startsWith(".")||name.startsWith("com.apple."))continue;
			if(child.isDirectory())volumes.add(child);
		}
		File[] roots=File.listRoots();
		if(roots!=null)for(File root:roots)if(root.isDirectory())volumes.add(root);
		return volumes;
	}

	private static String text(String key){return PCARD.pc!=null&&PCARD.pc.intl!=null?PCARD.pc.intl.getDialogText(key):key;}
}

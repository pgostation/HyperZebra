import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;

/**
 * The launcher: the window HyperZebra starts with, the place to open, switch between and set up stacks and 68K
 * programs. It replaces the Home stack as the start-up display (Home is still loaded with "start using" and opened
 * from Tools > "Open Home Stack") and the old "Drop file here" stand-in.
 * <ul>
 * <li>Top: the name and version, "About HyperZebra" (shown in the middle when there is no history yet).</li>
 * <li>Middle: the programs and stacks opened recently (all / programs / stacks), newest first; a missing file says so
 *     and offers "Locate…". Double-click or Return opens; the context menu also registers in the dock, shows the file
 *     in the Finder and removes the entry. Files dropped anywhere on the window are opened.</li>
 * <li>The column on the left: Open File… and Open HyperZebra HD, then the tabs of the middle's pages - History,
 *     Settings, Mac Application Settings, Translation, Appearance, About. A tab stays selected while its page is up (clicking it
 *     again changes nothing); the arrow keys move between the tabs, and View > the pages (Command-1…6) choose one.</li>
 * <li>Bottom: the dock - the running programs and the open stack come first, registered or not (the left of the
 *     row, or the top of the column; a dot marks them), then the other registered programs and stacks in their saved
 *     order (a registered program that quits goes back to its place there). One click launches or
 *     brings to the front; the context menu (right click or the keyboard's context-menu key) opens, lists the program's recent documents, opens its own settings, shows the file, registers or
 *     unregisters (the only way in: a drop never registers), quits or force-quits a running program and closes the open
 *     stack. The dock's own button (or View > "Show the Dock in the Whole Window") spreads it over the middle as a grid
 *     for a while - it is not a setting: a tab, the button again or Escape put it back at its edge.
 *     Files dropped on the dock are opened like anywhere else on the window; dropped on a program's icon (in the dock
 *     or in the history) documents are opened with that program, as the Finder does - an application among them too
 *     when the program claims any file ('****'), so a program dropped on ResEdit is opened in it, not launched.</li>
 * <li>A document dropped away from any program goes to the program chosen for its kind of file, an archive to its
 *     only known expander, and otherwise the user picks from the programs that can open it ({@link #openDocument},
 *     {@link DocumentOpeners}); font suitcases and system extensions are installed in the virtual System Folder
 *     (PCARD.openFiles).</li>
 * <li>Tools: Open Home Stack, the resource editor for a chosen file, the running programs' information
 *     ({@link GAppInfoWindow}: CPU, memory, screen, colours, 24-bit mode) and, on macOS, the AppleDouble conversion.</li>
 * </ul>
 * There is one launcher. It is shown at start-up: in front, or behind the stack or program named on the command line.
 * Its close box only hides it (on macOS; elsewhere it quits HyperZebra when nothing else is open); it comes back
 * when the last stack or program closes, from the Go menu's "Show Launcher", and from a click on the Dock icon.
 * -Dhz.launcher=false leaves it out (tests).
 */
final class GLauncher extends JFrame implements LauncherStore.Listener, MacApp68k.Listener {
	private static final long serialVersionUID = 1L;
	static GLauncher instance;
	static final int FILTER_ALL=0, FILTER_APPS=1, FILTER_STACKS=2, FILTER_DOCS=3;
	private static final int ICON=32;
	private final LauncherAppearance appearance = new LauncherAppearance(new File(AppPaths.stateDirectory(), "launcher-appearance.properties"));
	private int dockIconSize(){ return appearance.dockSize; }
	private JScrollPane dockScroll;
	private JScrollPane listScroll;
	/** Where each filter's list was scrolled to when the user last left it (All/Programs/Stacks/Documents). */
	private final int[] filterScroll = new int[4];
	private boolean statusError;

	private final JPanel center = appearance.surfacePanel(new java.awt.CardLayout());
	private final DefaultListModel<LauncherStore.Item> model = new DefaultListModel<LauncherStore.Item>();
	private final JList<LauncherStore.Item> list = new JList<LauncherStore.Item>(model){
		private static final long serialVersionUID = 1L;
		@Override public String getToolTipText(MouseEvent e){ // the full path (the row shows at most the last folders)
			int i = locationToIndex(e.getPoint());
			return i>=0 && getCellBounds(i, i).contains(e.getPoint()) ? model.get(i).path : null;
		}
	};
	private final JToggleButton allButton, appsButton, stacksButton, docsButton;
	/** The tabs of the middle's pages in the side column, in order (History, Settings, Mac Application Settings, Translation, Appearance, About). */
	private final List<JButton> tabs = new ArrayList<JButton>();
	/** The dock's button that spreads it over the window and back, and View's menu item for the same. */
	private JButton expandButton; private javax.swing.JCheckBoxMenuItem expandItem;
	/** The tab that was up when the dock was spread over the window: it comes back with the dock's return. */
	private String tabBeforeExpand = "recent";
	private final JLabel status = new JLabel(" ");
	/** What a click on the status line does (the program settings after a short run), or null. */
	private Runnable statusAction;
	/**
	 * The dock's buttons - a row, a column, or a grid that wraps at the window's width ({@link LauncherAppearance#dockGrid});
	 * while a button is dragged it paints the insertion marker (see {@link #dockDrag}).
	 */
	private final class DockPanel extends JPanel implements javax.swing.Scrollable {
		private static final long serialVersionUID = 1L;
		@Override protected void paintComponent(Graphics graphics){
			super.paintComponent(graphics);
			if((dockDrag==null && historyDrag==null) || dockDropIndex<0) return;
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setColor(appearance.accent());
			if(appearance.dockGrid){ java.awt.Rectangle r = dockGridMarker(dockDropIndex); g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4); }
			else{
				int at = dockMarkerPosition(dockDropIndex);
				if(appearance.dockLeft) g.fillRoundRect(6, at-2, getWidth()-12, 4, 4, 4);
				else g.fillRoundRect(at-2, 6, 4, getHeight()-12, 4, 4);
			}
			g.dispose();
		}
		// the grid wraps at the viewport's width and scrolls vertically; a row or a column keeps its preferred size
		public Dimension getPreferredScrollableViewportSize(){ return getPreferredSize(); }
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction){ return 24; }
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction){ return orientation==SwingConstants.VERTICAL ? visible.height : visible.width; }
		public boolean getScrollableTracksViewportWidth(){ return appearance.dockGrid; }
		public boolean getScrollableTracksViewportHeight(){ return false; }
	}
	private final DockPanel dockPanel = new DockPanel();
	/** A flow layout whose preferred height follows the rows its components wrap into at the container's width. */
	private static final class WrapLayout extends FlowLayout {
		private static final long serialVersionUID = 1L;
		WrapLayout(int align, int hgap, int vgap){ super(align, hgap, vgap); }
		@Override public Dimension preferredLayoutSize(java.awt.Container target){ return layoutSize(target, true); }
		@Override public Dimension minimumLayoutSize(java.awt.Container target){ Dimension d = layoutSize(target, false); d.width -= getHgap()+1; return d; }
		private Dimension layoutSize(java.awt.Container target, boolean preferred){
			synchronized(target.getTreeLock()){
				java.awt.Container c = target;
				while(c.getWidth()==0 && c.getParent()!=null) c = c.getParent(); // not laid out yet: the nearest sized ancestor's width
				int width = c.getWidth()==0 ? Integer.MAX_VALUE : c.getWidth();
				Insets in = target.getInsets();
				int hgap = getHgap(), vgap = getVgap(), max = width-(in.left+in.right+hgap*2);
				Dimension dim = new Dimension(0, 0); int rowWidth = 0, rowHeight = 0;
				for(Component m: target.getComponents()){
					if(!m.isVisible()) continue;
					Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
					if(rowWidth+d.width>max){ addRow(dim, rowWidth, rowHeight); rowWidth = 0; rowHeight = 0; }
					if(rowWidth!=0) rowWidth += hgap;
					rowWidth += d.width; rowHeight = Math.max(rowHeight, d.height);
				}
				addRow(dim, rowWidth, rowHeight);
				dim.width += in.left+in.right+hgap*2; dim.height += in.top+in.bottom+vgap*2;
				return dim;
			}
		}
		private void addRow(Dimension dim, int rowWidth, int rowHeight){
			dim.width = Math.max(dim.width, rowWidth);
			if(dim.height>0) dim.height += getVgap();
			dim.height += rowHeight;
		}
	}
	/** The dock with its status line (a row at the bottom, or a column on the left: {@link LauncherAppearance#dockLeft}). */
	private JPanel bottom;
	private JPanel middle, dockMoreHolder; private JComponent dockContainer;
	/** Saves the window's position and size a moment after it stops moving or resizing. */
	private javax.swing.Timer boundsSaver;
	/** The dock button being dragged to a new place, and the registered index it would take. */
	private DockButton dockDrag; private int dockDropIndex = -1;
	/** The tab the dragged dock button was in when its drag began (a resting drag may open another tab). */
	private int dockDragTab;
	/** The dock's tabs ({@link LauncherAppearance#dockTabs}). */
	private final DockTabStrip tabStrip = new DockTabStrip();
	/** While the dock is rebuilt for another tab: its items neither grow in nor shrink away. */
	private boolean dockQuiet;
	/** The history item being dragged out of the list (null otherwise): dropped on the dock, it is registered there. */
	private File historyDrag;
	/** The program a file drag hovers over - a dock button, or a history row - highlighted: dropping opens the files with it. */
	private DockButton dockDropButton; private int listDropRow = -1;
	private final List<DockButton> dockButtons = new ArrayList<DockButton>();
	/** Files whose program or stack just closed: their icons shrink back into the dock once it is laid out (see {@link DockZoom}). */
	private final Set<String> shrinkPending = new HashSet<String>();
	/**
	 * Items that just joined the dock (a program launched or a stack opened that is not registered), by identity, with
	 * the moment they appeared: their icons grow in over {@link #APPEAR_MS} ms ({@link AppearIcon}); the map is keyed by
	 * identity so the growth carries on through the dock rebuilds that follow a launch.
	 */
	private final Map<String,Long> appearing = new HashMap<String,Long>();
	/** Items on their way out of the dock (their program or stack closed, and they are not registered): their icons shrink away, then the dock is rebuilt without them. */
	private final Map<String,Long> departing = new HashMap<String,Long>();
	private static final int APPEAR_MS = 300;
	private final javax.swing.Timer dockAnimation = new javax.swing.Timer(16, new ActionListener(){ public void actionPerformed(ActionEvent e){
		long now = System.currentTimeMillis();
		appearing.values().removeIf(start -> now-start>=APPEAR_MS);
		boolean left = false;
		for(Long start: departing.values()) if(now-start>=APPEAR_MS) left = true;
		if(appearing.isEmpty() && departing.isEmpty()) dockAnimation.stop();
		if(left) refreshDock(); else dockPanel.repaint(); // the rebuild drops the ones that have gone
	}});
	/** Whether the dock was on show when it was last built: only then does a newcomer grow in (not the first build). */
	private boolean dockShownAtBuild;
	private int filter = FILTER_ALL;
	/** Which card the middle shows: "recent", "about", "settings", "vm", "translation", "appearance", or "dock" while the dock fills the window. */
	private String currentCard = "recent";
	private JComponent appearancePage;
	/** The "vm" card until the Mac Application Settings page is first shown. */
	private JPanel vmPlaceholder;
	/** The "translation" card until the Translation page is first shown (its status asks the translation helper). */
	private JPanel translationPlaceholder;
	/** Programs being launched right now (a second click while the file is read does nothing). */
	private final Set<String> launching = new HashSet<String>();
	/**
	 * Until this moment (after a click on a dock button) the dock keeps its order: the program just launched does not
	 * jump to the front yet, so the second click of a mistaken double click lands on the same icon, not on its neighbour.
	 */
	private long dockOrderHeldUntil;
	private static final int DOCK_HOLD_MS = 1000;
	private final javax.swing.Timer dockHoldEnd = new javax.swing.Timer(DOCK_HOLD_MS, new ActionListener(){ public void actionPerformed(ActionEvent e){ refreshDock(); }});
	{ dockHoldEnd.setRepeats(false); }
	private final Map<String,Icon> iconCache = new HashMap<String,Icon>();
	private static BufferedImage appIconImage, stackIconImage, documentIconImage, hyperZebraIcon;

	// ------------------------------------------------------------------ life cycle

	static boolean enabled(){ return instance!=null; }

	/**
	 * Where a dialog for this owner goes: the owner's bounds while it is on screen, else the launcher's while that is
	 * (a stack dropped on the launcher is converted while the card window is still hidden at its start-up bounds, so a
	 * dialog centred on it ended up half off the top-left corner of the screen), else null for the screen centre.
	 */
	static java.awt.Rectangle dialogArea(java.awt.Window owner){
		if(owner!=null && owner.isShowing()) return owner.getBounds();
		if(instance!=null && instance.isShowing()) return instance.getBounds();
		return null;
	}

	/** Creates the launcher (once, on the EDT); nothing in headless runs or with -Dhz.launcher=false. */
	static void install(){
		if(instance!=null || java.awt.GraphicsEnvironment.isHeadless() || "false".equals(System.getProperty("hz.launcher"))) return;
		PCARD.runOnEDT(new Runnable(){ public void run(){
			if(instance!=null) return;
			instance = new GLauncher();
			LauncherStore.addListener(instance);
			MacApp68k.addListener(instance);
		}});
		// The Mac Application Settings page lists the host's MIDI devices; CoreMIDI's first enumeration takes about a second.
		// Done once in the background after start-up, the page (built when first shown) opens at once.
		Thread midi = new Thread(new Runnable(){ public void run(){
			try{ Thread.sleep(2000); }catch(InterruptedException e){ return; }
			MidiHost68k.outputDeviceNames(); MidiHost68k.inputDeviceNames();
		}}, "MIDI device list");
		midi.setDaemon(true);
		midi.setPriority(Thread.MIN_PRIORITY);
		midi.start();
	}

	/**
	 * Builds the launcher again in place (same bounds, same page): after the language changed, so that it reads in the new
	 * language at once. The old window's listeners are dropped with it.
	 */
	static void rebuild(final String note){
		if(instance==null) return;
		PCARD.runOnEDT(new Runnable(){ public void run(){
			GLauncher old = instance;
			java.awt.Rectangle bounds = old.getBounds();
			String card = old.currentCard;
			boolean visible = old.isVisible();
			LauncherStore.removeListener(old);
			MacApp68k.removeListener(old);
			if(old.boundsSaver!=null) old.boundsSaver.stop();
			old.setVisible(false);
			old.dispose();
			instance = null;
			install();
			if(instance==null) return;
			instance.setBounds(bounds);
			if("dock".equals(card)){ instance.tabBeforeExpand = old.tabBeforeExpand; instance.setDockExpanded(true); }
			else instance.showCard(card);
			if(note!=null) instance.setStatus(note, false);
			if(visible) showLauncher(true);
		}});
	}

	/** Shows the launcher: in front with the focus, or behind (visible but not taking the focus from what is opening). */
	static void showLauncher(final boolean front){
		if(instance==null) return;
		PCARD.runOnEDT(new Runnable(){ public void run(){
			instance.refresh();
			if(front){
				if(!instance.isVisible()) instance.setVisible(true);
				instance.setState(JFrame.NORMAL);
				instance.toFront();
				instance.requestFocus();
			}else if(!instance.isVisible()){
				instance.setAutoRequestFocus(false);
				instance.setVisible(true);
				instance.setAutoRequestFocus(true);
				// a program started from the command line may already have its window up (it is launched before the launcher is built)
				for(java.awt.Window w: java.awt.Window.getWindows()) if(w!=instance && w.isShowing() && !(w instanceof javax.swing.JWindow)){ instance.toBack(); break; }
			}
		}});
	}

	/** The application menu's About HyperZebra (macOS): the launcher in front on its About page; without a launcher, a small dialog. */
	static void showAbout(){
		PCARD.runOnEDT(new Runnable(){ public void run(){
			if(instance!=null){
				showLauncher(true);
				instance.showCard("about");
				return;
			}
			BufferedImage icon = hyperZebraIcon();
			JOptionPane.showMessageDialog(null, PCARD.AppName+" "+PCARD.longVersion, text("About HyperZebra"),
				JOptionPane.PLAIN_MESSAGE, icon==null ? null : new ImageIcon(icon.getScaledInstance(64, 64, Image.SCALE_SMOOTH)));
		}});
	}

	/** After a stack or program closed: the launcher comes back when nothing else is open (not while HyperZebra quits). */
	static void showIfNothingOpen(){
		if(instance==null || PCARD.exiting) return;
		SwingUtilities.invokeLater(new Runnable(){ public void run(){
			if(PCARD.exiting) return;
			boolean stackShown = PCARD.pc!=null && PCARD.pc.isVisible();
			boolean programRunning = false;
			for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service) programRunning = true;
			if(!stackShown && !programRunning) showLauncher(true);
			else if(instance.isVisible()) instance.refreshDock(); // the closed stack leaves the dock
		}});
	}

	/** The file named at start-up (or opened from the launcher) could not be opened: the launcher comes to the front and says so. */
	static void showFailure(final String path, final String reason){
		if(instance==null) return;
		SwingUtilities.invokeLater(new Runnable(){ public void run(){
			String name = path==null ? "" : LauncherStore.composed(new File(path).getName());
			instance.setStatus(text("Could not open")+" \""+name+"\""+(reason==null||reason.isEmpty() ? "" : ": "+reason), true);
			showLauncher(true);
		}});
	}

	/** HyperZebra's quit stopped (a program cancelled it, or the user did): the launcher says why, behind what is open. */
	static void quitStopped(final String message){
		if(instance==null) return;
		SwingUtilities.invokeLater(new Runnable(){ public void run(){
			instance.setStatus(message, false);
			boolean stackShown = PCARD.pc!=null && PCARD.pc.isVisible();
			boolean programRunning = false;
			for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service) programRunning = true;
			showLauncher(!stackShown && !programRunning);
		}});
	}

	/** A program's magnified screen did not fit the display (MacAppHost.scaleFor): the launcher tells, the setting stays. */
	static void note(final MacApp68k app, final int scale){
		if(instance==null) return;
		SwingUtilities.invokeLater(new Runnable(){ public void run(){
			instance.setStatus((app==null ? "" : "\""+app.name+"\": ")+text("The screen does not fit the display at")+" "+scale+"x; "+text("shown at 1x this time."), false);
		}});
	}

	private static String text(String key){ return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getDialogText(key) : key; }

	private GLauncher(){
		super(PCARD.AppName+" "+text("Launcher"));
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		if(PCARD.pc!=null && PCARD.pc.getIconImage()!=null) setIconImage(PCARD.pc.getIconImage());
		//ツールチップはSwingの標準(4秒)では読み終わる前に消えるので、マウスが乗っている間は長めに出しておく
		javax.swing.ToolTipManager.sharedInstance().setDismissDelay(Math.max(javax.swing.ToolTipManager.sharedInstance().getDismissDelay(), 15000));
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosing(WindowEvent e){ closeRequested(); }
			@Override public void windowActivated(WindowEvent e){ refresh(); }
		});
		setJMenuBar(buildMenuBar());
		JPanel canvas = appearance.canvas();
		canvas.setLayout(new BorderLayout(0, 8));
		canvas.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
		setContentPane(canvas);
		center.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		// the middle: the column of buttons on the left; the history, the introduction or a settings page on the right
		center.add(buildRecentPanel(), "recent");
		center.add(buildAboutPanel(), "about");
		GPreferencesDialog.SettingsForm settings = new GPreferencesDialog.SettingsForm(true);
		settings.languageChanged = new Runnable(){ public void run(){ languageChanged(); } };
		center.add(settingsPage(stackSettingsPage(settings)), "settings");
		center.add(vmPlaceholder = new JPanel(), "vm"); // built when first shown: its MIDI device list asks CoreMIDI (~0.7 s at start-up)
		center.add(translationPlaceholder = new JPanel(), "translation"); // built when first shown: it starts the translation helper
		center.add(appearancePage = settingsPage(buildAppearancePanel()), "appearance");
		middle = new JPanel(new BorderLayout(10, 0));
		middle.add(buildSideButtons(), BorderLayout.WEST);
		middle.add(center, BorderLayout.CENTER);

		// the dock and the status line (only while it says something); the window's minimum size shows just the dock
		bottom = new JPanel(new BorderLayout());
		status.setBorder(BorderFactory.createEmptyBorder(0, 12, 4, 12));
		status.setFont(status.getFont().deriveFont(status.getFont().getSize2D()-1f));
		status.setVisible(false);
		status.addMouseListener(new MouseAdapter(){
			@Override public void mouseClicked(MouseEvent e){ if(statusAction!=null) statusAction.run(); }
		});
		dockContainer = buildDock();
		arrangeDock();

		allButton = filterButton(text("All"), FILTER_ALL);
		appsButton = filterButton(text("Programs"), FILTER_APPS);
		stacksButton = filterButton(text("Stacks"), FILTER_STACKS);
		docsButton = filterButton(text("Documents"), FILTER_DOCS);
		ButtonGroup group = new ButtonGroup();
		group.add(allButton); group.add(appsButton); group.add(stacksButton); group.add(docsButton);
		allButton.setSelected(true);
		filterBar.add(allButton); filterBar.add(appsButton); filterBar.add(stacksButton); filterBar.add(docsButton);

		getRootPane().registerKeyboardAction(e -> setDockExpanded(false), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		new DropTarget(getContentPane(), new FileDrop());
		new DropTarget(list, new FileDrop());
		new DropTarget(dockPanel, new FileDrop());
		new DropTarget(tabStrip, new FileDrop());

		refresh();
		applyAppearance();
		setMinimumSize(new Dimension(200, 200));
		addWindowListener(new WindowAdapter(){ @Override public void windowOpened(WindowEvent e){ updateMinimumSize(); } });
		// the window comes back where it was left (when that is still on a display), else at its default size in the middle
		java.awt.Rectangle saved = appearance.window;
		if(saved!=null && onSomeDisplay(saved)) setBounds(saved);
		else { setSize(760, 560); setLocationRelativeTo(null); }
		boundsSaver = new javax.swing.Timer(500, new ActionListener(){ public void actionPerformed(ActionEvent e){ rememberBounds(); }});
		boundsSaver.setRepeats(false);
		addComponentListener(new java.awt.event.ComponentAdapter(){
			@Override public void componentMoved(java.awt.event.ComponentEvent e){ boundsSaver.restart(); }
			@Override public void componentResized(java.awt.event.ComponentEvent e){ boundsSaver.restart(); }
		});
	}

	/** Whether a good part of the saved frame lies on a display that is there now (a screen may have been unplugged). */
	private static boolean onSomeDisplay(java.awt.Rectangle r){
		try{
			for(java.awt.GraphicsDevice gd: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
				java.awt.Rectangle i = gd.getDefaultConfiguration().getBounds().intersection(r);
				if(i.width>=100 && i.height>=60) return true;
			}
		}catch(Exception ignored){}
		return false;
	}
	private void rememberBounds(){
		if(!isShowing() || getExtendedState()!=JFrame.NORMAL) return;
		java.awt.Rectangle b = getBounds();
		if(b.equals(appearance.window)) return;
		appearance.window = b;
		try{ appearance.save(); }catch(IOException e){ System.err.println("Could not save the launcher's window: "+e.getMessage()); }
	}

	/**
	 * Puts the dock (with the status line) at the bottom or on the left, as the appearance says, and the middle beside it;
	 * or, while it is spread over the window ({@link #setDockExpanded}), as a grid in the middle's page area (the "dock"
	 * card), no tab selected.
	 */
	private void arrangeDock(){
		java.awt.Container content = getContentPane();
		content.remove(middle); content.remove(bottom);
		bottom.removeAll();
		center.remove(dockContainer);
		dockContainer.setVisible(true); // the card layout hides the cards it is not showing
		middle.remove(status);
		boolean grid = appearance.dockGrid, vertical = !grid && appearance.dockLeft;
		if(grid){
			middle.add(status, BorderLayout.NORTH);
			center.add(dockContainer, "dock");
		}else if(vertical){
			bottom.add(dockContainer, BorderLayout.CENTER);
			middle.add(status, BorderLayout.NORTH);
			content.add(bottom, BorderLayout.WEST);
		}else{
			bottom.add(dockContainer, BorderLayout.CENTER);
			bottom.add(status, BorderLayout.NORTH);
			content.add(bottom, BorderLayout.SOUTH);
		}
		content.add(middle, BorderLayout.CENTER);
		if(grid) dockPanel.setLayout(new WrapLayout(FlowLayout.LEFT, 4, 4));
		else dockPanel.setLayout(new BoxLayout(dockPanel, vertical ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
		dockScroll.setVerticalScrollBarPolicy(vertical || grid ? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
		dockScroll.setHorizontalScrollBarPolicy(vertical || grid ? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER : ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		dockContainer.remove(dockMoreHolder);
		dockContainer.add(dockMoreHolder, vertical ? BorderLayout.SOUTH : BorderLayout.EAST);
		dockContainer.remove(tabStrip);
		LauncherStore.get().setDockTabsShown(appearance.dockTabs);
		if(appearance.dockTabs){ tabStrip.vertical = vertical; dockContainer.add(tabStrip, vertical ? BorderLayout.WEST : BorderLayout.NORTH); }
		dockMoreHolder.setBorder(vertical ? BorderFactory.createEmptyBorder(6, 0, 0, 0) : BorderFactory.createEmptyBorder(4, 6, 0, 0));
		refreshDock(); // the struts and glue of a row or column, none in the grid
		updateExpandControls();
		if(grid) showCard("dock");
		else showCard("dock".equals(currentCard) ? tabBeforeExpand : currentCard); // keeps the tabs' selection right
		content.revalidate(); content.repaint();
	}

	/**
	 * Spreads the dock over the window as a grid (for many items), or puts it back at its edge. A passing view, not a
	 * setting ({@link LauncherAppearance#dockGrid} is never saved): the dock's own button, View's menu item, Escape and
	 * choosing a tab switch it; the tab that was up comes back with the dock.
	 */
	void setDockExpanded(boolean expanded){
		if(expanded==appearance.dockGrid){ updateExpandControls(); return; }
		if(expanded && !"dock".equals(currentCard)) tabBeforeExpand = currentCard;
		appearance.dockGrid = expanded;
		arrangeDock();
		updateMinimumSize();
		if(expanded) dockPanel.requestFocusInWindow();
	}
	boolean dockExpanded(){ return appearance.dockGrid; }
	private void updateExpandControls(){
		boolean on = appearance.dockGrid;
		if(expandButton!=null){
			expandButton.setIcon(new ExpandIcon(14, !on));
			String tip = text(on ? "Put the Dock Back" : "Show the Dock in the Whole Window");
			expandButton.setToolTipText(tip);
			expandButton.getAccessibleContext().setAccessibleName(tip);
		}
		if(expandItem!=null) expandItem.setSelected(on);
	}
	/** Four corner brackets pointing out (spread the dock) or in (put it back), in the button's text colour. */
	private static final class ExpandIcon implements Icon {
		private final int size; private final boolean out;
		ExpandIcon(int size, boolean out){ this.size = size; this.out = out; }
		public int getIconWidth(){ return size; }
		public int getIconHeight(){ return size; }
		public void paintIcon(Component c, Graphics graphics, int x, int y){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setColor(c==null ? Color.DARK_GRAY : c.getForeground());
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int arm = out ? size*2/7 : size/4, m = 1, far = size-1-m; // short arms: the brackets must not close into a square (out) or a cross (in)
			int[][] corners = {{m, m, 1, 1}, {far, m, -1, 1}, {m, far, 1, -1}, {far, far, -1, -1}};
			for(int[] k: corners){
				// out: the bracket sits in the corner; in: it is drawn at the arm's end, its elbow toward the centre
				int cx = x+(out ? k[0] : k[0]+k[2]*arm), cy = y+(out ? k[1] : k[1]+k[3]*arm);
				int dx = out ? k[2] : -k[2], dy = out ? k[3] : -k[3];
				g.drawLine(cx, cy, cx+dx*arm, cy);
				g.drawLine(cx, cy, cx, cy+dy*arm);
			}
			g.dispose();
		}
	}

	/** The smallest window: the dock alone (the middle collapses to nothing) - a row at the bottom, or a column on the left. */
	private void updateMinimumSize(){
		if(!isDisplayable() || bottom==null) return;
		Insets in = getInsets();
		Insets cb = getContentPane() instanceof JComponent ? ((JComponent)getContentPane()).getInsets() : new Insets(0,0,0,0);
		JMenuBar mb = getJMenuBar();
		int menu = mb!=null && mb.isShowing() ? mb.getHeight() : 0; // 0 with the macOS screen menu bar
		if(appearance.dockGrid){
			setMinimumSize(new Dimension(560, in.top+in.bottom+cb.top+cb.bottom+menu+dockIconSize()*2+120));
		}else if(appearance.dockLeft){
			int w = in.left+in.right+cb.left+cb.right+bottom.getPreferredSize().width+8; // 8: the canvas layout's gap
			setMinimumSize(new Dimension(w, in.top+in.bottom+cb.top+cb.bottom+menu+dockIconSize()*2+120));
		}else{
			int h = in.top+in.bottom+cb.top+cb.bottom+menu+bottom.getPreferredSize().height+8;
			setMinimumSize(new Dimension(560, h));
		}
	}

    private void applyAppearance(){
        appearance.styleTree(getContentPane());
        list.setOpaque(false);
        list.setBackground(appearance.surface());
        list.setForeground(appearance.foreground());
        list.setSelectionForeground(appearance.foreground());
        list.setSelectionBackground(appearance.selection());
        list.setFixedCellHeight(appearance.compact ? 42 : 52);
        status.setForeground(statusError ? appearance.error() : appearance.secondary());
        getRootPane().putClientProperty("apple.awt.windowAppearance", appearance.dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        refreshDock();
        revalidate(); repaint();
        updateMinimumSize();
    }

    /** The appearance page of the middle: theme, accent colour, list density and dock size, each applied at once. */
    private JComponent buildAppearancePanel(){
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JPanel theme = appearanceRow(page, "Theme");
        ButtonGroup themes = new ButtonGroup();
        appearanceChoice(theme, themes, "Light", !appearance.dark(), () -> appearance.theme = "light");
        appearanceChoice(theme, themes, "Dark", appearance.dark(), () -> appearance.theme = "dark");
        JPanel accents = appearanceRow(page, "Accent color");
        String[] names = {"Blue", "Purple", "Pink", "Green", "Orange"};
        ButtonGroup colors = new ButtonGroup();
        for(int i=0; i<names.length; i++){
            final int index = i;
            javax.swing.JRadioButton b = appearanceChoice(accents, colors, names[i], appearance.accentIndex==i, () -> appearance.accentIndex=index);
            b.setIcon(swatch(new Color(LauncherAppearance.ACCENTS[i]), false));
            b.setSelectedIcon(swatch(new Color(LauncherAppearance.ACCENTS[i]), true));
        }
        JPanel density = appearanceRow(page, "List density");
        ButtonGroup densities = new ButtonGroup();
        appearanceChoice(density, densities, "Comfortable", !appearance.compact, () -> appearance.compact=false);
        appearanceChoice(density, densities, "Compact", appearance.compact, () -> appearance.compact=true);
        JPanel side = appearanceRow(page, "Dock position");
        ButtonGroup sides = new ButtonGroup();
        appearanceChoice(side, sides, "Bottom", !appearance.dockLeft, () -> { appearance.dockLeft=false; arrangeDock(); });
        appearanceChoice(side, sides, "Left", appearance.dockLeft, () -> { appearance.dockLeft=true; arrangeDock(); }); // spreading it over the window is the dock's own button
        JPanel tabRow = appearanceRow(page, "Dock tabs");
        JCheckBox tabsBox = new JCheckBox(text("Sort the dock into tabs"), appearance.dockTabs);
        tabsBox.addActionListener(e -> { setDockTabs(tabsBox.isSelected()); saveAppearance(); });
        tabRow.add(tabsBox);
        JPanel size = appearanceRow(page, "Dock size");
        ButtonGroup sizes = new ButtonGroup();
        String[] labels = {"Small", "Medium", "Large"};
        int[] values = {32, 48, 64}; // 32: the classic icon dot by dot; 64: each dot doubled
        for(int i=0; i<values.length; i++){
            final int value = values[i];
            appearanceChoice(size, sizes, labels[i], dockIconSize()==value, () -> appearance.dockSize=value);
        }
        JPanel resetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8)){
            private static final long serialVersionUID = 1L;
            @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        JButton reset = new JButton(text("Reset appearance"));
        reset.addActionListener(e -> {
            appearance.theme="light"; appearance.accentIndex=0; appearance.compact=false; appearance.dockSize=48;
            if(appearance.dockLeft){ appearance.dockLeft=false; arrangeDock(); }
            saveAppearance();
            rebuildAppearancePage();
        });
        resetRow.add(reset);
        page.add(resetRow);
        page.add(Box.createVerticalGlue()); // rows keep their height when the page is taller than they need
        for(Component c: page.getComponents()) if(c instanceof JPanel || c instanceof JLabel || c instanceof JSeparator){ ((JComponent)c).setAlignmentX(Component.LEFT_ALIGNMENT); if(!(c instanceof JPanel)) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height)); }
        return page;
    }
    /** A labelled row of choices on the appearance page. */
    private JPanel appearanceRow(JPanel page, String label){
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)){
            private static final long serialVersionUID = 1L;
            @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); } // follows the pop-up's real height
        };
        JLabel l = new JLabel(text(label)+":");
        l.setPreferredSize(new Dimension(120, l.getPreferredSize().height));
        row.add(l);
        page.add(row);
        return row;
    }
    private javax.swing.JRadioButton appearanceChoice(JPanel row, ButtonGroup group, String label, boolean selected, Runnable change){
        javax.swing.JRadioButton item = new javax.swing.JRadioButton(text(label), selected);
        group.add(item); row.add(item);
        item.addActionListener(e -> { change.run(); saveAppearance(); });
        return item;
    }
    /** A round colour sample for the accent choices (ringed when chosen). */
    private static javax.swing.Icon swatch(final Color color, final boolean chosen){
        return new javax.swing.Icon(){
            public int getIconWidth(){ return 18; }
            public int getIconHeight(){ return 18; }
            public void paintIcon(Component c, Graphics graphics, int x, int y){
                Graphics2D g = LauncherAppearance.smooth(graphics);
                g.setColor(color); g.fillOval(x+2, y+2, 14, 14);
                if(chosen){ g.setColor(c==null ? Color.DARK_GRAY : c.getForeground()); g.setStroke(new BasicStroke(2f)); g.drawOval(x+1, y+1, 16, 16); } // c is null when Aqua paints the focus ring around the icon
                g.dispose();
            }
        };
    }
    /** After "Reset": the page is rebuilt so its choices show the defaults. */
    private void rebuildAppearancePage(){
        center.remove(appearancePage);
        appearancePage = settingsPage(buildAppearancePanel());
        center.add(appearancePage, "appearance");
        appearance.styleTree(appearancePage);
        showCard("appearance");
    }
    /** A settings form in the middle: scrolls when the window is short. */
    private JComponent settingsPage(JComponent form){
        JScrollPane scroll = new JScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel page = new JPanel(new BorderLayout());
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }
    private void saveAppearance(){
        applyAppearance();
        try { appearance.save(); }
        catch(IOException e){ setStatus(text("Could not save appearance settings."), true); }
    }

	private void closeRequested(){
		if(!GMenu.isMacOSX()){
			boolean stackShown = PCARD.pc!=null && PCARD.pc.isVisible();
			boolean programRunning = false;
			for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service) programRunning = true;
			if(!stackShown && !programRunning){ PCARD.exit(); return; } // nothing else would be left to reach the launcher from
		}
		setVisible(false);
	}

	// ------------------------------------------------------------------ pieces

	private final JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

	private JMenuBar buildMenuBar(){
		int s = GMenu.isMacOSX() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
		JMenuBar bar = new JMenuBar();
		JMenu file = new JMenu(menuText("File"));
		JMenuItem mi;
		file.add(mi = new JMenuItem(text("Open File…"))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openFileDialog(); }});
		file.add(mi = new JMenuItem(menuText("New Stack…"))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, s));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ newStack(); }});
		file.add(mi = new JMenuItem(text("Open HyperZebra HD")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openHyperZebraHD(); }});
		final JMenu disks = new JMenu(text("Disk Images"));
		disks.addMenuListener(new javax.swing.event.MenuListener(){
			public void menuSelected(javax.swing.event.MenuEvent e){ DiskImages.fillMenu(disks); }
			public void menuDeselected(javax.swing.event.MenuEvent e){}
			public void menuCanceled(javax.swing.event.MenuEvent e){}
		});
		DiskImages.fillMenu(disks); // a menu with no items would not open on macOS's screen menu bar
		file.add(disks);
		if(!GMenu.isMacOSX()){
			file.addSeparator();
			file.add(mi = new JMenuItem(menuText("Quit"))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, s));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ PCARD.quitFromMenu(); }});
		}
		bar.add(file);
		// View: the middle's pages, as the side column's tabs choose them, and the dock spread over the window
		JMenu view = new JMenu(text("View"));
		String[][] pages = {{"recent", "History"}, {"settings", "Settings"}, {"vm", "Mac Application Settings"}, {"translation", "Translation"}, {"appearance", "Appearance"}, {"about", "About HyperZebra"}};
		for(int i=0; i<pages.length; i++){
			final String card = pages[i][0];
			view.add(mi = new JMenuItem(text(pages[i][1]))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1+i, s));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ showCard(card); }});
		}
		view.addSeparator();
		view.add(expandItem = new javax.swing.JCheckBoxMenuItem(text("Show the Dock in the Whole Window")));
		expandItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, s));
		expandItem.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ setDockExpanded(expandItem.isSelected()); }});
		bar.add(view);
		JMenu tools = new JMenu(text("Tools"));
		tools.add(mi = new JMenuItem(text("Open Home Stack"))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, s));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openHome(); }});
		tools.add(mi = new JMenuItem(text("Resource Editor…"))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, s));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openResourceEditor(); }});
		tools.add(mi = new JMenuItem(text("Running Programs Info…"))); mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppInfoWindow.showWindow(GLauncher.this); }});
		if(GMenu.isMacOSX()){
			// what only makes sense where the file system has forks - a sidecar is made from the native fork
			tools.addSeparator();
			tools.add(mi = new JMenuItem(text("Convert to AppleDouble…")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ convertToAppleDouble(); }});
			tools.add(mi = new JMenuItem(text("About AppleDouble Conversion…")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GHelpWindow.show(GHelpWindow.TOPIC_APPLEDOUBLE); }});
		}
		bar.add(tools);
		JMenu help = new JMenu(menuText("Help"));
		help.add(mi = new JMenuItem(text("HyperZebra Help")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GHelpWindow.show(null); }});
		bar.add(help);
		return bar;
	}

	/** Tools > Open Home Stack: shows the HyperCard-compatible Home stack, including when no card is currently open. */
	private void openHome(){
		if(!HomeStack.ensure()){
			setStatus(text("Home stack not found"), true);
			return;
		}
		setStatus("", false);
		File home = HomeStack.folder();
		if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null
				&& LauncherStore.identity(new File(PCARD.pc.stack.path)).equals(LauncherStore.identity(home))){
			bringCardWindowToFront();
		}else{
			PCARD.openFiles(Collections.singletonList(home));
		}
	}

	/**
	 * Tools > Resource Editor…: the resources of a chosen file (a program, a stack, a document, a suitcase - the resource
	 * fork, an AppleDouble sidecar or MacBinary) open in HyperZebra's resource editor, apart from any open stack.
	 */
	private void openResourceEditor(){
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(text("Open in the Resource Editor"));
		chooser.setApproveButtonText(text("Open"));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		FileChooserPlaces.attach(chooser);
		if(PCARD.showFileChooser(chooser, this, false, false)!=JFileChooser.APPROVE_OPTION || chooser.getSelectedFile()==null) return;
		File f = chooser.getSelectedFile();
		setStatus("", false);
		if(!OStack.openResourceForkInEditor(f.getPath()))
			JOptionPane.showMessageDialog(this, text("\"%s\" has no resources to edit.").replace("%s", LauncherStore.displayName(f)), text("Resource Editor"), JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Tools > Convert to AppleDouble…: the chosen files and folders get "._name" sidecars carrying their native resource
	 * forks and Finder info (AppleDoubleConverter), for a copy headed to Windows or Linux. The walk runs off the EDT with
	 * the file count in the status line; the summary comes as a dialog.
	 */
	private void convertToAppleDouble(){
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(text("Convert to AppleDouble"));
		chooser.setApproveButtonText(text("Convert"));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		chooser.setMultiSelectionEnabled(true);
		FileChooserPlaces.attach(chooser);
		if(PCARD.showFileChooser(chooser, this, false, false)!=JFileChooser.APPROVE_OPTION) return;
		final List<File> targets = new ArrayList<File>(java.util.Arrays.asList(chooser.getSelectedFiles()));
		if(targets.isEmpty() && chooser.getSelectedFile()!=null) targets.add(chooser.getSelectedFile());
		if(targets.isEmpty()) return;
		setStatus(text("Converting to AppleDouble…"), false);
		Thread worker = new Thread(new Runnable(){ public void run(){
			final AppleDoubleConverter.Report report = AppleDoubleConverter.convert(targets, new AppleDoubleConverter.Progress(){
				long shown;
				public void examined(File file, final AppleDoubleConverter.Report r){
					long now = System.currentTimeMillis();
					if(now-shown<200) return;
					shown = now;
					final int seen = r.seen(), written = r.written;
					SwingUtilities.invokeLater(new Runnable(){ public void run(){ setStatus(text("Converting to AppleDouble…")+" "+seen+" ("+text("sidecars written")+": "+written+")", false); }});
				}
			});
			SwingUtilities.invokeLater(new Runnable(){ public void run(){
				setStatus(text("Convert to AppleDouble")+": "+text("sidecars written")+" "+report.written, report.failed>0);
				JOptionPane.showMessageDialog(GLauncher.this, AppleDoubleConverter.summary(report), text("Convert to AppleDouble"),
					report.failed>0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
			}});
		}}, "AppleDouble conversion");
		worker.setDaemon(true);
		worker.start();
	}
	private static String menuText(String key){ return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getText(key) : key; }

	private JComponent buildRecentPanel(){
		JPanel panel = new JPanel(new BorderLayout());
		JPanel top = new JPanel(new BorderLayout());
		JLabel title = new JLabel(text("Recently opened programs, stacks and documents"));
		title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
		title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
		// The label keeps its full width inside a clipping holder (no "…"); the holder takes only the room the filters leave.
		JPanel titleClip = new JPanel(new BorderLayout());
		titleClip.add(title, BorderLayout.WEST);
		top.add(titleClip, BorderLayout.CENTER);
		filterBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
		top.add(filterBar, BorderLayout.EAST);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		panel.add(top, BorderLayout.NORTH);

		list.setCellRenderer(new ItemRenderer());
		javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
		list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); // Shift/Cmd-click several rows to remove them at once
		list.setFixedCellHeight(52);
		list.setDragEnabled(true);
		list.setTransferHandler(new TransferHandler(){
			private static final long serialVersionUID = 1L;
			@Override public int getSourceActions(JComponent c){ return COPY; }
			@Override protected Transferable createTransferable(JComponent c){
				List<LauncherStore.Item> items = list.getSelectedValuesList();
				historyDrag = items.size()==1 ? items.get(0).file() : null; // only a single row registers on the dock
				if(items.isEmpty()) return null;
				List<File> files = new ArrayList<File>(); for(LauncherStore.Item it: items) files.add(it.file());
				return new FileTransferable(files);
			}
			@Override protected void exportDone(JComponent c, Transferable data, int action){ historyDrag = null; dockDropIndex = -1; dockPanel.repaint(); }
		});
		list.addMouseListener(new MouseAdapter(){
			@Override public void mouseClicked(MouseEvent e){
				if(e.getClickCount()==2 && SwingUtilities.isLeftMouseButton(e)){
					int i = list.locationToIndex(e.getPoint());
					if(i>=0 && list.getCellBounds(i, i).contains(e.getPoint())) openItem(model.get(i));
				}
			}
			@Override public void mousePressed(MouseEvent e){ maybePopup(e); }
			@Override public void mouseReleased(MouseEvent e){ maybePopup(e); }
			private void maybePopup(MouseEvent e){
				if(!e.isPopupTrigger()) return;
				int i = list.locationToIndex(e.getPoint());
				if(i<0 || !list.getCellBounds(i, i).contains(e.getPoint())) return;
				if(!list.isSelectedIndex(i)) list.setSelectedIndex(i); // a click inside a multiple selection keeps it
				historyMenu(model.get(i)).show(list, e.getX(), e.getY());
			}
		});
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "hz.open");
		list.getActionMap().put("hz.open", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			public void actionPerformed(ActionEvent e){ LauncherStore.Item it = list.getSelectedValue(); if(it!=null) openItem(it); }
		});
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "hz.menu");
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "hz.menu");
		list.getActionMap().put("hz.menu", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			public void actionPerformed(ActionEvent e){
				int i = list.getLeadSelectionIndex(); if(i<0 || !list.isSelectedIndex(i)) i = list.getSelectedIndex();
				if(i<0) return;
				java.awt.Rectangle r = list.getCellBounds(i, i);
				historyMenu(model.get(i)).show(list, r.x+ICON, r.y+r.height/2);
			}
		});
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "hz.remove");
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "hz.remove");
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, GMenu.isMacOSX() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK), "hz.remove");
		list.getActionMap().put("hz.remove", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			public void actionPerformed(ActionEvent e){ removeSelectedFromHistory(); }
		});
		JScrollPane scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		listScroll = scroll;
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private JTextArea aboutText;
	private JComponent buildAboutPanel(){
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		JLabel welcome = new JLabel(text("Run HyperCard stacks and classic Mac apps"));
		welcome.setFont(welcome.getFont().deriveFont(Font.BOLD, 20f));
		welcome.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
		panel.add(welcome, BorderLayout.NORTH);
		aboutText = new JTextArea();
		aboutText.setEditable(false);
		aboutText.setLineWrap(true);
		aboutText.setWrapStyleWord(true);
		aboutText.setOpaque(false);
		aboutText.setFocusable(false);
		aboutText.setFont(new JLabel().getFont().deriveFont(14f));
		aboutText.putClientProperty("launcher.secondary", true);
		aboutText.setText(aboutMessage());
		JScrollPane introScroll = new JScrollPane(aboutText);
		introScroll.setBorder(BorderFactory.createEmptyBorder());
		panel.add(introScroll, BorderLayout.CENTER);
		return panel;
	}
	private static String aboutMessage(){
		return PCARD.AppName+" "+PCARD.longVersion+"\n\n"
            +text("Run HyperCard stacks and classic Mac apps (68K and PowerPC), all in one place.")+"\n\n"
            +text("Drop a file here or choose Open File. Your recent items will appear here, ready for next time.")+"\n\n"
            +text("Keep favorites in the dock. HyperZebra HD holds your virtual System Folder and shared files.")+"\n\n"
            +text("HyperCard and Macintosh are trademarks of Apple Inc. This project is not affiliated with Apple Inc. in any way.");
	}

	/** A gear drawn in the button's text colour (so it turns white with the label when the page is up): eight teeth around a ring. */
	private static final class GearIcon implements Icon {
		private final int size;
		GearIcon(int size){ this.size = size; }
		public int getIconWidth(){ return size; }
		public int getIconHeight(){ return size; }
		public void paintIcon(Component c, Graphics graphics, int x, int y){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setColor(c.getForeground());
			double cx = x+size/2.0, cy = y+size/2.0, outer = size/2.0, inner = size*0.36, hole = size*0.14;
			java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
			int teeth = 8; double step = Math.PI*2/teeth, half = step*0.24;
			for(int i=0; i<teeth; i++){
				double a = i*step;
				double[] as = {a-half*1.6, a-half, a+half, a+half*1.6};
				double[] rs = {inner, outer, outer, inner};
				for(int k=0; k<4; k++){ double px = cx+Math.cos(as[k])*rs[k], py = cy+Math.sin(as[k])*rs[k]; if(i==0 && k==0) p.moveTo(px, py); else p.lineTo(px, py); }
			}
			p.closePath();
			java.awt.geom.Area gear = new java.awt.geom.Area(p);
			gear.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(cx-hole, cy-hole, hole*2, hole*2)));
			g.fill(gear);
			g.dispose();
		}
	}
	/** A clock face: the History tab's icon, in the tab's text colour. */
	private static final class ClockIcon implements Icon {
		private final int size;
		ClockIcon(int size){ this.size = size; }
		public int getIconWidth(){ return size; }
		public int getIconHeight(){ return size; }
		public void paintIcon(Component c, Graphics graphics, int x, int y){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setColor(c.getForeground());
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			float cx = x+size/2f, cy = y+size/2f, r = size/2f-1f;
			g.draw(new java.awt.geom.Ellipse2D.Float(cx-r, cy-r, r*2, r*2));
			g.draw(new java.awt.geom.Line2D.Float(cx, cy, cx, cy-r*0.62f));
			g.draw(new java.awt.geom.Line2D.Float(cx, cy, cx+r*0.5f, cy+r*0.2f));
			g.dispose();
		}
	}
	/** A globe (a circle, its equator and a meridian): the Translation tab's icon, in the tab's text colour. */
	private static final class GlobeIcon implements Icon {
		private final int size;
		GlobeIcon(int size){ this.size = size; }
		public int getIconWidth(){ return size; }
		public int getIconHeight(){ return size; }
		public void paintIcon(Component c, Graphics graphics, int x, int y){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setColor(c.getForeground());
			g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			float cx = x+size/2f, cy = y+size/2f, r = size/2f-1f;
			g.draw(new java.awt.geom.Ellipse2D.Float(cx-r, cy-r, r*2, r*2));
			g.draw(new java.awt.geom.Ellipse2D.Float(cx-r*0.45f, cy-r, r*0.9f, r*2));
			g.draw(new java.awt.geom.Line2D.Float(cx-r, cy, cx+r, cy));
			g.dispose();
		}
	}
	/** An "i" in a circle: the About tab's icon, in the tab's text colour. */
	private static final class InfoIcon implements Icon {
		private final int size;
		InfoIcon(int size){ this.size = size; }
		public int getIconWidth(){ return size; }
		public int getIconHeight(){ return size; }
		public void paintIcon(Component c, Graphics graphics, int x, int y){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setColor(c.getForeground());
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			float cx = x+size/2f, cy = y+size/2f, r = size/2f-1f;
			g.draw(new java.awt.geom.Ellipse2D.Float(cx-r, cy-r, r*2, r*2));
			g.draw(new java.awt.geom.Line2D.Float(cx, cy-r*0.05f, cx, cy+r*0.5f));
			g.fill(new java.awt.geom.Ellipse2D.Float(cx-1.1f, cy-r*0.5f-1.1f, 2.2f, 2.2f));
			g.dispose();
		}
	}
	/**
	 * A tab of the side column: shows one of the middle's pages and stays selected while it is up (clicking it again
	 * changes nothing). The arrow keys move to the tab above or below and show its page.
	 */
	private JButton tab(final String card, String label, Icon icon){
		final JButton b = new JButton(label, icon);
		b.setHorizontalAlignment(SwingConstants.LEFT);
		b.setIconTextGap(8);
		b.putClientProperty("launcher.tab", true);
		b.putClientProperty("launcher.card", card);
		b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ showCard(card); }});
		b.addKeyListener(new KeyAdapter(){
			@Override public void keyPressed(KeyEvent e){
				int step = e.getKeyCode()==KeyEvent.VK_UP ? -1 : e.getKeyCode()==KeyEvent.VK_DOWN ? 1 : 0;
				if(step==0) return;
				int i = tabs.indexOf(b)+step;
				if(i<0 || i>=tabs.size()) return;
				JButton next = tabs.get(i);
				next.requestFocusInWindow();
				showCard((String)next.getClientProperty("launcher.card"));
				e.consume();
			}
		});
		tabs.add(b);
		return b;
	}

	/** The language was changed on the Settings page: the launcher is rebuilt in it at once; the stack window's menus and dialogs follow at the next start. */
	private void languageChanged(){
		try{ PCARD.pc.lang = PCARD.language; PCARD.pc.intl = new International(PCARD.language); }
		catch(Exception ex){ return; }
		//スタックを開いていなければ、カードウインドウのメニュー(ブラウズ用・ペイント用)も今の言語で作り直しておく
		if(PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) PCARD.resetMenuBarAfterClose();
		SwingUtilities.invokeLater(new Runnable(){ public void run(){ rebuild(text("The stack window's menus and dialogs use the new language at the next start.")); }});
	}

	/** The column on the left of the middle: the two actions, then the tabs of the pages, one under the other, all the same width. */
	private JComponent buildSideButtons(){
		JPanel actions = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
		JButton b;
		actions.add(b = new JButton(text("Open File…")));
		b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openFileDialog(); }});
		actions.add(b = new JButton(menuText("New Stack…")));
		b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ newStack(); }});
		actions.add(b = new JButton(text("Open HyperZebra HD")));
		b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openHyperZebraHD(); }});
		JPanel tabColumn = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
		tabColumn.add(tab("recent", text("History"), new ClockIcon(14)));
		tabColumn.add(tab("settings", text("Settings"), new GearIcon(14))); // the settings pages carry a gear
		tabColumn.add(tab("vm", text("Mac Application Settings"), new GearIcon(14)));
		tabColumn.add(tab("translation", text("Translation"), new GlobeIcon(14)));
		tabColumn.add(tab("appearance", text("Appearance"), new GearIcon(14)));
		tabColumn.add(tab("about", text("About HyperZebra"), new InfoIcon(14)));
		JPanel column = new JPanel(new BorderLayout(0, 16));
		column.add(actions, BorderLayout.NORTH);
		column.add(tabColumn, BorderLayout.CENTER);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.add(column, BorderLayout.NORTH);
		JLabel hint = new JLabel("<html><div style='text-align:center'>"+text("Drop programs or stacks here to open them.")+"</div></html>", SwingConstants.CENTER);
		hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D()-2f));
		hint.putClientProperty("launcher.secondary", true);
		hint.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));
		wrap.add(hint, BorderLayout.SOUTH);
		wrap.setPreferredSize(new Dimension(168, 10));
		return wrap;
	}

	private JComponent buildDock(){
		dockPanel.setLayout(new BoxLayout(dockPanel, BoxLayout.X_AXIS));
		dockPanel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		JScrollPane scroll = new JScrollPane(dockPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED){
			private static final long serialVersionUID = 1L;
			// room for the buttons plus the scroll bar when it shows: the bar would otherwise cover the buttons' running dots
			@Override public Dimension getPreferredSize(){
				if(appearance.dockLeft && !appearance.dockGrid){
					javax.swing.JScrollBar bar = getVerticalScrollBar();
					return new Dimension(dockIconSize()+48+(bar.isVisible() ? bar.getPreferredSize().width : 0), 100);
				}
				javax.swing.JScrollBar bar = getHorizontalScrollBar();
				return new Dimension(100, dockIconSize()+36+(!appearance.dockGrid && bar.isVisible() ? bar.getPreferredSize().height : 0));
			}
		};
		dockScroll = scroll;
		java.awt.event.ComponentAdapter barShown = new java.awt.event.ComponentAdapter(){
			@Override public void componentShown(java.awt.event.ComponentEvent e){ barChanged(); }
			@Override public void componentHidden(java.awt.event.ComponentEvent e){ barChanged(); }
			private void barChanged(){
				SwingUtilities.invokeLater(() -> { scroll.revalidate(); updateMinimumSize(); });
			}
		};
		scroll.getHorizontalScrollBar().addComponentListener(barShown);
		scroll.getVerticalScrollBar().addComponentListener(barShown);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getHorizontalScrollBar().setUnitIncrement(24);
		scroll.getVerticalScrollBar().setUnitIncrement(24);
		// the grid's rows follow the viewport's width: once the panel takes a new width its wrapped height is asked for again
		dockPanel.addComponentListener(new java.awt.event.ComponentAdapter(){
			@Override public void componentResized(java.awt.event.ComponentEvent e){
				if(appearance.dockGrid && dockPanel.getPreferredSize().height!=dockPanel.getHeight()) dockPanel.revalidate();
			}
		});
		JPanel dock = appearance.surfacePanel(new BorderLayout(), this::dockTint);
		dock.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
		dock.add(scroll, BorderLayout.CENTER);
		expandButton = new JButton();
		expandButton.setMargin(new Insets(2, 6, 2, 6));
		expandButton.addActionListener(e -> setDockExpanded(!appearance.dockGrid));
		updateExpandControls();
		dockMoreHolder = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
		dockMoreHolder.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 0));
		dockMoreHolder.add(expandButton);
		dock.add(dockMoreHolder, BorderLayout.EAST);
		return dock;
	}

	/** A dock item's context menu (tests): the button showing {@code f}, or null when the dock does not show it. */
	/** Test hook: the history row's context menu for {@code f}, or null when it is not in the history. */
	JPopupMenu historyMenuFor(File f){ String id = LauncherStore.identity(f); for(LauncherStore.Item it: LauncherStore.get().recentItems()) if(id.equals(LauncherStore.identity(it.file()))) return itemMenu(it); return null; }
	JPopupMenu dockMenuFor(File f){ String id = LauncherStore.identity(f); for(DockButton b: dockButtons) if(!b.departing && id.equals(LauncherStore.identity(b.file))) return b.buildMenu(); return null; }

	private JToggleButton filterButton(String label, final int which){
		JToggleButton b = new JToggleButton(label);
		b.setMargin(new Insets(1, 8, 1, 8));
		b.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ selectFilter(which); }});
		return b;
	}

	/** Switches the history to a filter; each filter keeps its own scroll position. */
	private void selectFilter(int which){
		if(listScroll!=null) filterScroll[filter] = listScroll.getVerticalScrollBar().getValue();
		filter = which;
		refreshList();
		if(listScroll==null) return;
		final int value = filterScroll[which];
		SwingUtilities.invokeLater(new Runnable(){ public void run(){ // after the list has laid out its new rows
			listScroll.getVerticalScrollBar().setValue(value);
		}});
	}

	// ------------------------------------------------------------------ refreshing

	/** Rebuilds the history and the dock from the store and the running programs (EDT). */
	void refresh(){
		refreshList();
		refreshDock();
	}

	/**
	 * Rebuilds the history rows. The scroll position stays where the user left it (this runs whenever the window is
	 * activated, so a list scrolled while the launcher was in the background must not jump back to the selection).
	 */
	private void refreshList(){
		List<LauncherStore.Item> items = new ArrayList<LauncherStore.Item>();
		for(LauncherStore.Item it: LauncherStore.get().recentItems()){
			if(filter==FILTER_APPS && !it.isApp()) continue;
			if(filter==FILTER_STACKS && (it.isApp() || it.isDocument())) continue;
			if(filter==FILTER_DOCS && !it.isDocument()) continue;
			items.add(it);
		}
		if(sameRows(items)){
			list.repaint(); // running/open marks and times may have changed
		}else{
			List<LauncherStore.Item> selected = list.getSelectedValuesList();
			final int view = listScroll!=null ? listScroll.getVerticalScrollBar().getValue() : -1;
			model.clear();
			for(LauncherStore.Item it: items) model.addElement(it);
			List<Integer> keep = new ArrayList<Integer>();
			for(LauncherStore.Item it: selected){ int i = items.indexOf(it); if(i>=0) keep.add(i); }
			if(!keep.isEmpty()) list.setSelectedIndices(keep.stream().mapToInt(Integer::intValue).toArray());
			if(view>=0){
				listScroll.getVerticalScrollBar().setValue(view);
				SwingUtilities.invokeLater(new Runnable(){ public void run(){ // again after the list has laid out its new rows
					listScroll.getVerticalScrollBar().setValue(view);
				}});
			}
		}
		aboutText.setText(aboutMessage());
		showCard(currentCard);
	}
	/** Whether the history already shows exactly these items in this order. */
	private boolean sameRows(List<LauncherStore.Item> items){
		if(model.getSize()!=items.size()) return false;
		for(int i=0; i<items.size(); i++) if(model.getElementAt(i)!=items.get(i)) return false;
		return true;
	}

	/**
	 * Shows a page of the middle and selects its tab; the History tab with an empty history shows the introduction. A
	 * page asked for while the dock fills the window puts the dock back first.
	 */
	void showCard(String card){
		if(!"dock".equals(card) && appearance.dockGrid){ tabBeforeExpand = card; setDockExpanded(false); return; } // arrangeDock comes back here
		if("dock".equals(card) && !appearance.dockGrid) card = tabBeforeExpand;
		currentCard = card;
		if("vm".equals(card) && vmPlaceholder != null){
			center.remove(vmPlaceholder); vmPlaceholder = null;
			JComponent vm = settingsPage(vmPage(new GPreferencesDialog.VmForm(true)));
			center.add(vm, "vm", 3); appearance.styleTree(vm);
		}
		if("translation".equals(card) && translationPlaceholder != null){
			int at = java.util.Arrays.asList(center.getComponents()).indexOf(translationPlaceholder);
			center.remove(translationPlaceholder); translationPlaceholder = null;
			final GTranslationForm form = new GTranslationForm();
			form.addChangeListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ form.apply(); }}); // stored at once, like the other pages
			JComponent page = settingsPage(translationPage(form));
			center.add(page, "translation", at); appearance.styleTree(page);
		}
		String page = "recent".equals(card) && LauncherStore.get().recentItems().isEmpty() ? "about" : card; // nothing else to show
		((java.awt.CardLayout)center.getLayout()).show(center, page);
		for(JButton t: tabs) t.setSelected(card.equals(t.getClientProperty("launcher.card")));
		center.repaint();
	}
	/** The page on show: a tab's card, or "dock" while the dock fills the window (tests). */
	String currentPage(){ return currentCard; }

	/** The stack on show in the card window (its .hzb folder or file), or null: nothing open, or only Home. */
	static File openStack(){
		PCARD pc = PCARD.pc;
		if(pc==null || pc.stack==null || pc.stack.curCard==null) return null;
		return stackItem(pc.stack.path);
	}
	/** A stack's path as the launcher lists it (the folder of a _stack.xml), or null for none or Home. */
	private static File stackItem(String path){
		if(path==null || path.isEmpty() || LauncherStore.identity(new File(path)).equals(LauncherStore.identity(HomeStack.folder()))) return null;
		File f = new File(path);
		if(f.getName().equals("_stack.xml") || f.getName().equals("toc.xml")) f = f.getParentFile();
		return StackBundle.host(f); //a stack file (.hzb ZIP) as the file, not its working folder
	}
	/** Every open stack: the one on show first, then those in windows of their own behind it (GStackWindow), front to back. Home is left out. */
	static List<File> openStacks(){
		List<File> stacks = new ArrayList<File>();
		File shown = openStack();
		if(shown!=null) stacks.add(shown);
		for(String path: TTalk.windowStackPaths()){ File f = stackItem(path); if(f!=null) stacks.add(f); }
		return stacks;
	}
	/** Whether this file is an open stack (on show or in a window of its own). */
	private static boolean isOpenStack(File f){
		String id = LauncherStore.identity(f);
		for(File open: openStacks()) if(LauncherStore.identity(open).equals(id)) return true;
		return false;
	}
	/** Whether this file is the stack on show in the card window. */
	private static boolean isStackOnShow(File f){ File open = openStack(); return open!=null && LauncherStore.identity(open).equals(LauncherStore.identity(f)); }

	private void refreshDock(){
		// the files that were open or running when the dock was last built: the ones no longer so shrink back into it;
		// what the previous build showed, and where, for the newcomers and the leavers
		Set<String> wereActive = new HashSet<String>(), wereShown = new HashSet<String>();
		List<DockButton> before = new ArrayList<DockButton>(dockButtons);
		for(DockButton b: before){ String id = LauncherStore.identity(b.file); wereShown.add(id); if(b.running!=null || b.stackOpen) wereActive.add(id); }
		boolean wasShown = dockShownAtBuild && !dockQuiet; dockShownAtBuild = isShowing();
		dockPanel.removeAll();
		dockButtons.clear();
		LauncherStore store = LauncherStore.get();
		List<MacApp68k> running = new ArrayList<MacApp68k>();
		for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service) running.add(app);
		Set<String> shown = new HashSet<String>();
		List<DockButton> built = new ArrayList<DockButton>();
		// the running programs and the open stack come first (the left of a row, the top of a column), registered or not;
		// a registered one goes back to its saved place when it quits or closes
		Set<String> registered = new HashSet<String>();
		for(File f: store.dockItems()) registered.add(LauncherStore.identity(f));
		for(MacApp68k a: running){
			String id = LauncherStore.identity(a.file);
			if(shown.contains(id)) continue;
			shown.add(id);
			built.add(new DockButton(a.file, registered.contains(id), a));
		}
		for(File stack: openStacks()){
			String id = LauncherStore.identity(stack);
			if(shown.contains(id)) continue;
			shown.add(id);
			built.add(new DockButton(stack, registered.contains(id), null));
		}
		// then the other registered items in their saved order
		for(File f: store.dockItems()){
			String id = LauncherStore.identity(f);
			if(shown.contains(id)) continue;
			shown.add(id);
			built.add(new DockButton(f, true, null));
		}
		long now = System.currentTimeMillis();
		if(now<dockOrderHeldUntil){
			// just after a click: the items stay where they were (newcomers after them) and take their places when the hold ends
			final List<String> was = new ArrayList<String>(); for(DockButton b: before) was.add(LauncherStore.identity(b.file));
			built.sort(Comparator.comparingInt(b -> { int i = was.indexOf(LauncherStore.identity(b.file)); return i<0 ? Integer.MAX_VALUE : i; }));
			dockHoldEnd.setInitialDelay((int)(dockOrderHeldUntil-now)+1); dockHoldEnd.restart();
		}
		departing.keySet().retainAll(wereShown); departing.keySet().removeAll(shown);
		boolean animate = !"false".equals(System.getProperty("hz.launcher.animate"));
		if(wasShown && dockShownAtBuild && animate){
			// the leavers (unregistered items whose program or stack closed) stay in their place while their icon shrinks away
			for(int i=0; i<before.size(); i++){
				DockButton old = before.get(i);
				String id = LauncherStore.identity(old.file);
				if(shown.contains(id)) continue;
				Long since = departing.get(id);
				if(since==null){ since = now; departing.put(id, since); }
				else if(now-since>=APPEAR_MS){ departing.remove(id); continue; } // gone
				DockButton leaver = new DockButton(old.file, false, null);
				leaver.departing = true;
				built.add(Math.min(i, built.size()), leaver);
			}
			// the newcomers grow in
			for(DockButton b: built){ String id = LauncherStore.identity(b.file); if(!b.departing && !wereShown.contains(id) && !appearing.containsKey(id)) appearing.put(id, now); }
			if((!appearing.isEmpty() || !departing.isEmpty()) && !dockAnimation.isRunning()) dockAnimation.start();
		}else departing.clear();
		for(DockButton b: built) addDockButton(b);
		if(!appearance.dockGrid) dockPanel.add(appearance.dockLeft ? Box.createVerticalGlue() : Box.createHorizontalGlue());
		if(dockDrag!=null){ dockDrag.setVisible(false); dockPanel.add(dockDrag); } // a button being dragged must stay in the window to get the rest of its drag (another tab came up)
		appearance.styleTree(dockPanel);
		dockPanel.revalidate();
		dockPanel.repaint();
		if(!wereActive.isEmpty() && isVisible()){
			for(DockButton b: dockButtons) if(b.isActive() || b.departing) wereActive.remove(LauncherStore.identity(b.file));
			wereActive.retainAll(shown); // a registered item that stays: its icon comes back onto the button
			shrinkPending.addAll(wereActive);
			// once the new buttons are laid out (the dock may be rebuilt again before then: the zoom finds the place of that time)
			if(!shrinkPending.isEmpty()) SwingUtilities.invokeLater(new Runnable(){ public void run(){
				List<String> ids = new ArrayList<String>(shrinkPending); shrinkPending.clear();
				for(String id: ids) DockZoom.shrink(zoomSpot(new File(id)));
			}});
		}
	}
	private void addDockButton(DockButton b){ dockButtons.add(b); dockPanel.add(b); if(!appearance.dockGrid) dockPanel.add(appearance.dockLeft ? Box.createVerticalStrut(4) : Box.createHorizontalStrut(4)); }
	/** The files shown in the dock, in order (tests). */
	List<File> dockFiles(){ List<File> r = new ArrayList<File>(); for(DockButton b: dockButtons) if(!b.departing) r.add(new File(LauncherStore.identity(b.file))); return r; }
	/** The dock button of a file, or null (tests). */
	/** Drops files on the dock at a program's button, or on the history's row for it (tests; on the event thread). */
	void dropOnProgram(File program, List<File> files, boolean onHistoryRow) throws IOException {
		if(onHistoryRow){
			for(int i=0; i<model.size(); i++) if(model.get(i).isApp() && LauncherStore.identity(program).equals(model.get(i).path)){
				java.awt.Rectangle r = list.getCellBounds(i, i);
				new FileDrop().dropped(list, new java.awt.Point(r.x+r.width/2, r.y+r.height/2), files);
				return;
			}
			throw new IOException("not in the history: "+program);
		}
		Component b = dockButtonFor(program);
		if(b==null) throw new IOException("not in the dock: "+program);
		new FileDrop().dropped(dockPanel, new java.awt.Point(b.getX()+b.getWidth()/2, b.getY()+b.getHeight()/2), files);
	}
	/** Whether a drag of these files over the program's icon marks it as a place to drop them (tests). */
	boolean marksDropOn(File program, List<File> files){ FileDrop d = new FileDrop(); d.dragged = files; return d.takesDragged(program); }
	/** Drops files on the window away from any program (tests; on the event thread). */
	void dropOnWindow(List<File> files) throws IOException { new FileDrop().dropped(getContentPane(), new java.awt.Point(1, 1), files); }
	java.awt.Component dockButtonFor(File f){ String id = LauncherStore.identity(f); for(DockButton b: dockButtons) if(id.equals(LauncherStore.identity(b.file))) return b; return null; }
	/** The screen position of a dock button's centre, or null (tests). */
	java.awt.Point dockButtonCenterOnScreen(File f){
		String id = LauncherStore.identity(f);
		for(DockButton b: dockButtons) if(id.equals(LauncherStore.identity(b.file)) && b.isShowing()){
			java.awt.Point p = b.getLocationOnScreen(); return new java.awt.Point(p.x+b.getWidth()/2, p.y+b.getHeight()/2);
		}
		return null;
	}
	/** Whether the dock marks this file as running (tests). */
	boolean dockShowsRunning(File f){ String id = LauncherStore.identity(f); for(DockButton b: dockButtons) if(id.equals(LauncherStore.identity(b.file))) return b.isActive(); return false; }

	/** The registered dock buttons at their saved places, in order (the running programs and the open stack come before them). */
	private List<DockButton> registeredButtons(){ List<DockButton> r = new ArrayList<DockButton>(); for(DockButton b: dockButtons) if(b.registered && b.running==null && !b.stackOpen) r.add(b); return r; }
	/** Where a registered item among {@link #registeredButtons} at {@code index} (or past the last) stands in the saved dock order. */
	private static int savedDockIndex(List<DockButton> resting, int index){
		List<File> order = LauncherStore.get().dockItems();
		if(index < resting.size()) return savedDockIndex(order, resting.get(index).file);
		return order.size();
	}
	private static int savedDockIndex(List<File> order, File f){ String id = LauncherStore.identity(f); for(int i=0; i<order.size(); i++) if(id.equals(LauncherStore.identity(order.get(i)))) return i; return -1; }
	/** A button's position along the dock's axis (x in a row, y in a column) and its extent there. */
	private int along(DockButton b){ return appearance.dockLeft ? b.getY() : b.getX(); }
	private int extent(DockButton b){ return appearance.dockLeft ? b.getHeight() : b.getWidth(); }
	/** The place among the registered items a point (dock panel coordinates) points at: how many of them lie before it. */
	private int dockInsertionIndex(java.awt.Point p){
		int at = appearance.dockLeft ? p.y : p.x, index = 0;
		for(DockButton b: registeredButtons()){
			if(appearance.dockGrid){ if(p.y>=b.getY()+b.getHeight() || (p.y>=b.getY() && b.getX()+b.getWidth()/2 < p.x)) index++; } // rows above, then the row's left part
			else if(along(b)+extent(b)/2 < at) index++;
		}
		return index;
	}
	/** The insertion marker in the grid: a bar down the left of the button at a registered index, or past the last one (dock panel coordinates). */
	private java.awt.Rectangle dockGridMarker(int index){
		List<DockButton> r = registeredButtons();
		DockButton b = index < r.size() ? r.get(index) : null;
		if(b!=null) return new java.awt.Rectangle(b.getX()-4, b.getY()+6, 4, b.getHeight()-12);
		DockButton last = !r.isEmpty() ? r.get(r.size()-1) : dockButtons.isEmpty() ? null : dockButtons.get(dockButtons.size()-1);
		if(last==null) return new java.awt.Rectangle(4, 10, 4, dockIconSize()+18);
		return new java.awt.Rectangle(last.getX()+last.getWidth()+1, last.getY()+6, 4, last.getHeight()-12);
	}
	/** Where along the dock the insertion marker for a registered index is drawn (dock panel coordinates). */
	private int dockMarkerPosition(int index){
		List<DockButton> r = registeredButtons();
		if(index < r.size()) return along(r.get(index))-3;
		if(!r.isEmpty()){ DockButton last = r.get(r.size()-1); return along(last)+extent(last)+3; }
		if(dockButtons.isEmpty()) return 4;
		DockButton last = dockButtons.get(dockButtons.size()-1); // only unregistered items: a registered one would follow them
		return along(last)+extent(last)+3;
	}
	/**
	 * Registers a history item dragged onto the dock at the place a point (dock panel coordinates) points at; an item
	 * already registered moves there.
	 */
	private void registerFromHistory(File f, java.awt.Point at){
		if(!f.exists()) return;
		int index = savedDockIndex(registeredButtons(), dockInsertionIndex(at)); // the running items in front keep their saved places
		int own = savedDockIndex(LauncherStore.get().dockItems(), f);
		if(own>=0 && index>own) index--; // the item leaves its old place first
		setStatus("", false);
		placeInDock(f, index);
	}
	/** Drops a history item on the dock at a point (dock panel coordinates; tests, on the event thread). */
	void dropHistoryItemOnDock(File f, java.awt.Point at) throws IOException {
		historyDrag = f;
		try{ new FileDrop().dropped(dockPanel, at, Collections.singletonList(f)); }finally{ historyDrag = null; dockDropIndex = -1; }
	}
	/** Moves (or registers) a file at a registered index and rebuilds the dock; the order is saved. */
	private void placeInDock(File f, int index){
		LauncherStore store = LauncherStore.get();
		if(!store.isInDock(f)) store.addToDock(f);
		store.moveInDock(f, index);
		refreshDock();
	}

	public void storeChanged(){ SwingUtilities.invokeLater(new Runnable(){ public void run(){ if(isVisible()) refresh(); }}); }
	public void applicationLaunched(MacApp68k app){ launching.remove(LauncherStore.identity(app.file)); refreshDock(); }
	public void applicationFinished(MacApp68k app){
		refreshDock(); showIfNothingOpen();
		// a program that stops soon after it starts has often just said it wants other colours or another screen: say where they are set
		if(!app.service && app.file!=null && app.file.isFile() && System.currentTimeMillis()-app.startedAt<SHORT_RUN_MS){
			final File program = app.file;
			setStatus(text("Click here to change the colors, screen size or speed of \"{0}\"")
				.replace("{0}", LauncherStore.displayName(program)), false, new Runnable(){ public void run(){ GAppSettingsDialog.show(GLauncher.this, program); }});
		}
	}
	/** A run shorter than this ends with the hint to the program's own settings. */
	private static final long SHORT_RUN_MS = 60_000;

	/** Shows a line in the launcher's status bar from any thread (empty clears it); nothing without a launcher. */
	static void showStatus(final String message){
		SwingUtilities.invokeLater(new Runnable(){ public void run(){ if(instance!=null) instance.setStatus(message, false); }});
	}
	private void setStatus(String message, boolean error){ setStatus(message, error, null); }
	private void setStatus(String message, boolean error, Runnable action){
		statusAction = action;
		status.setCursor(action!=null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
		status.setToolTipText(action!=null ? text("Also: right-click the program in the history or the dock > Application Settings…") : null);
		status.setText(message==null || message.isEmpty() ? " " : message);
		status.setVisible(message!=null && !message.isEmpty());
		statusError = error;
		status.setForeground(error ? appearance.error() : appearance.secondary());
		updateMinimumSize();
	}

	// ------------------------------------------------------------------ actions

	/** Opens a program or stack from the history or the dock: a running program comes to the front instead. */
	void open(File f){
		if(f==null) return;
		f = MacApp68k.programOf(f); // a Mac OS 9 package ("X.app" folder): the program inside
		if(!f.exists()){ setStatus(text("Not found")+": "+f.getPath(), true); return; }
		setStatus("", false);
		String id = LauncherStore.identity(f);
		MacApp68k running = MacApp68k.runningFrom(f);
		if(running!=null){ running.bringToFront(); return; }
		if(isOpenStack(f)){ bringStackToFront(f); return; }
		if(launching.contains(id)) return; // still being read: no second instance from a double click
		if(runsAsProgram(f)){ final File stack = f; DockZoom.grow(zoomSpot(f), () -> launchStandalone(stack)); return; } // run in the VM last time: again
		final boolean app = f.isFile() && MacApp68k.isApplication(f);
		if(app){
			launching.add(id); // from here until the file is opened (the zoom runs before that)
			setStatus(text("Launching")+" \""+LauncherStore.displayName(f)+"\"…", false);
		}
		final File file = f;
		Runnable openNow = new Runnable(){ public void run(){
			if(app){
				try{ PCARD.openFiles(Collections.singletonList(file)); }
				finally{ launching.remove(id); if(MacApp68k.runningFrom(file)!=null) setStatus("", false); }
			}else PCARD.openFiles(Collections.singletonList(file), false); // a stack of the history or the dock opens the way it was listed: imported
		}};
		DockZoom.grow(zoomSpot(f), openNow); // the icon zooms out of the dock or the history while the file opens
	}

	/**
	 * Where a file's icon is on show, for the zoom: its dock button, else its history row (when the list is on show and
	 * the row is scrolled into view), else null.
	 */
	private DockZoom.Spot zoomSpot(File f){
		Component button = dockButtonFor(f);
		if(button instanceof DockButton){
			DockButton b = (DockButton)button;
			Icon icon = b.getIcon();
			if(icon==null) return null;
			Rectangle viewR = SwingUtilities.calculateInnerArea(b, null), iconR = new Rectangle(), textR = new Rectangle();
			SwingUtilities.layoutCompoundLabel(b, b.getFontMetrics(b.getFont()), b.getText(), icon,
				b.getVerticalAlignment(), b.getHorizontalAlignment(), b.getVerticalTextPosition(), b.getHorizontalTextPosition(),
				viewR, iconR, textR, b.getIconTextGap());
			return new DockZoom.Spot(b, icon, iconR);
		}
		if(!list.isShowing()) return null;
		String id = LauncherStore.identity(f);
		for(int i=0; i<model.size(); i++){
			LauncherStore.Item item = model.get(i);
			if(item.isDocument() || !id.equals(item.path)) continue;
			Rectangle cell = list.getCellBounds(i, i);
			if(cell==null || !list.getVisibleRect().contains(cell)) return null;
			Component c = list.getCellRenderer().getListCellRendererComponent(list, item, i, false, false);
			if(!(c instanceof ItemRenderer)) return null;
			ItemRenderer row = (ItemRenderer)c;
			row.setBounds(0, 0, cell.width, cell.height); row.doLayout();
			Rectangle label = row.iconLabel.getBounds();
			Icon icon = row.iconLabel.getIcon();
			if(icon==null) return null;
			Rectangle iconR = new Rectangle(cell.x+label.x, cell.y+label.y+(label.height-icon.getIconHeight())/2, icon.getIconWidth(), icon.getIconHeight());
			return new DockZoom.Spot(list, icon, iconR);
		}
		return null;
	}

	/** An open stack comes to the front: the card window for the stack on show, else that stack comes back into it from its own window. */
	private static void bringStackToFront(File f){
		if(isStackOnShow(f)) bringCardWindowToFront();
		else TTalk.resumeWindowStack(TTalk.windowStackKey(f.getPath()));
	}
	/** The card window (the stack on show) comes to the front. */
	private static void bringCardWindowToFront(){
		final PCARD pc = PCARD.pc;
		if(pc==null) return;
		PCARD.runOnEDT(new Runnable(){ public void run(){
			if(!pc.isVisible()) return;
			pc.setState(JFrame.NORMAL);
			pc.toFront();
			pc.requestFocus();
		}});
	}

	private void openItem(LauncherStore.Item item){
		if(!item.exists()){ locate(item); return; }
		if(item.isDocument()) openDocument(item.appFile(), item.file());
		else open(item.file());
	}

	/** A document from the history or a dock menu: handed to the running instance of its program, or the program is launched with it. */
	void openDocument(File app, File d){ openDocuments(app, Collections.singletonList(d)); }
	/** Documents (dropped on the program's icon) handed to the running instance of the program, or the program is launched with them. */
	void openDocuments(File app, List<File> documents){
		for(File d: documents) if(d==null || !d.exists()){ setStatus(text("Not found")+": "+(d==null ? "" : d.getPath()), true); return; }
		if(app==null || !app.isFile()){ setStatus(text("Not found")+": "+(app==null ? "" : app.getPath()), true); return; }
		if(documents.isEmpty()) return;
		MacApp68k running = MacApp68k.runningFrom(app);
		if(running!=null){
			if(!running.acceptsDocumentsWhileRunning()){
				setStatus("\""+running.name+"\" "+text("does not take documents while it runs; quit it and open the document again."), true);
				running.bringToFront();
				return;
			}
			running.openDocuments(documents);
			running.bringToFront();
			return;
		}
		String id = LauncherStore.identity(app);
		if(launching.contains(id)) return;
		launching.add(id);
		setStatus(text("Launching")+" \""+LauncherStore.displayName(app)+"\"…", false);
		try{
			MacApp68k.launch(app, documents);
			LauncherStore.get().recordOpened(app, LauncherStore.KIND_APP);
			setStatus("", false);
		}catch(IOException e){
			setStatus(text("Can't launch the Mac application.")+" "+e.getMessage(), true);
		}finally{ launching.remove(id); }
	}

	// ------------------------------------------------------------------ documents dropped away from a program

	/**
	 * A document dropped (or opened) away from any program's icon: opened in the program chosen for its kind of file,
	 * else - an archive with one known expander - in that; otherwise the user picks from the programs that can open it
	 * ({@link DocumentOpeners}), HyperZebra's own picture editor for a picture, and the resource editor for a file with
	 * resources. Returns false when the file is not a document this handles (a stack, a picture or ZIP archive HyperZebra
	 * opens itself, a folder) so the caller opens it the usual way; true when it was taken care of, cancelled included.
	 */
	static boolean openDocument(final File doc){ return openDocument(doc, true); }
	/** {@code offerExpanders}: false once the expanders were installed for this archive (so a format nothing opens does not ask again). */
	private static boolean openDocument(final File doc, boolean offerExpanders){
		if(instance==null || doc==null || !doc.isFile()) return false;
		if(OStack.looksLikeHyperCardStack(doc.getPath()) || StackBundle.isPacked(doc) || ZipExpander.isZip(doc)) return false;
		final int type = DocumentOpeners.typeOf(doc);
		final boolean picture = isPicture(doc, type), archive = DocumentOpeners.isArchive(doc);
		File chosen = type==0 ? null : LauncherStore.get().openerFor(type);
		if(chosen==null && archive) chosen = DocumentOpeners.obvious(doc);
		if(chosen!=null){ instance.openDocuments(chosen, Collections.singletonList(doc)); return true; }
		List<File> candidates = DocumentOpeners.candidates(doc);
		if(candidates.isEmpty() && picture) return false; // HyperZebra's own picture editor, as always
		if(candidates.isEmpty() && archive && offerExpanders && offerExpanders(doc)) return true;
		boolean resources = hasResources(doc);
		if(candidates.isEmpty()){ // nothing opens it; a file with resources can still be looked at in the resource editor
			String kind = type==0 ? "" : " ('"+ResourceFile68k.typeName(type)+"')", editor = text("Resource Editor");
			new GDialog(PCARD.pc, text("No Mac program that opens %s is known.").replace("%s", "\""+LauncherStore.displayName(doc)+"\""+kind)+"\n"
				+text("Open the program once, or put it in the dock, then drop the document again."), null, resources ? editor : null, null, "OK");
			if(resources && editor.equals(GDialog.clicked)) OStack.openResourceForkInEditor(doc.getPath());
			return true;
		}
		Object choice = instance.chooseOpener(doc, type, candidates, picture, resources);
		if(choice==null) return true;
		if(choice instanceof File) instance.openDocuments((File)choice, Collections.singletonList(doc));
		else if(OPEN_PICTURE.equals(choice)) TTalk.CallMessage("edit picture \""+doc.getPath()+"\"", "", null, false, true);
		else if(OPEN_RESOURCES.equals(choice)) OStack.openResourceForkInEditor(doc.getPath());
		return true;
	}
	/**
	 * An archive was dropped and no known program opens it: the expander its format needs, among those shipped with HyperZebra
	 * ({@link ExtractorApps}), is installed - registered at once when it is in HyperZebra HD already, otherwise after one
	 * question, by its installer run in the 68K VM without the user. The archive is then opened again so it goes to it.
	 * False when there is nothing to install for it (the caller says that no program opens it).
	 */
	private static boolean offerExpanders(final File archive){
		final java.util.Set<ExtractorApps.Kit> kits = ExtractorApps.kitsFor(archive);
		java.util.Set<ExtractorApps.Kit> missing = ExtractorApps.missing(kits, ExtractorApps.installedExpanders(kits));
		if(missing.size()<kits.size() && !ExtractorApps.register(kits).isEmpty()){ // installed before but no longer in the launcher's lists
			if(instance!=null) instance.refresh();
			if(missing.isEmpty() || !DocumentOpeners.candidates(archive).isEmpty()) return openDocument(archive, false);
		}
		final java.util.Set<ExtractorApps.Kit> toInstall = ExtractorApps.available(missing);
		if(toInstall.isEmpty()) return false;
		String install = text("Install"), names = ExtractorApps.titles(toInstall);
		new GDialog(PCARD.pc, text("No program that opens this archive is registered.")+"\n"
			+text("Install %s now? The installation runs by itself in the 68K VM; there is nothing to click.").replace("%s", names)+"\n"
			+text("Installing means accepting the license, which is installed alongside in HyperZebra HD."), null, text("Cancel"), null, install);
		if(!install.equals(GDialog.clicked)) return true;
		final List<File> programs;
		try{ programs = ExtractorApps.prepare(toInstall); }
		catch(IOException e){ new GDialog(PCARD.pc, text("Can't install the expanders.")+"\n"+e.getMessage(), null, "OK", null, null); return true; }
		if(instance!=null) instance.setStatus(text("Installing %s").replace("%s", names)+"…", false);
		ExtractorApps.runAll(programs, new Runnable(){ public void run(){
			final List<File> found = ExtractorApps.register(kits);
			SwingUtilities.invokeLater(new Runnable(){ public void run(){
				if(instance!=null){ instance.refresh(); instance.setStatus("", false); }
				if(found.isEmpty()){
					new GDialog(PCARD.pc, text("No expander program was found in HyperZebra HD after the installation."), null, "OK", null, null);
					return;
				}
				openDocument(archive, false);
			}});
		}});
		return true;
	}
	/**
	 * A HyperCard stack the user handed over (a drop, Open File…): when it can also run in the 68K VM - a stand-alone
	 * application stack (Myst Preview) runs itself, any stack opens in a known HyperCard or HyperCard Player - the user
	 * picks between importing it as a HyperZebra stack and running it there. Returns false when the stack is to be
	 * imported the usual way (nothing in the VM opens it, or the user chose to import); true when it was launched or the
	 * choice was cancelled.
	 */
	static boolean openStack(final File stack){
		if(instance==null || stack==null || !stack.isFile() || !OStack.looksLikeHyperCardStack(stack.getPath())) return false;
		boolean standalone = MacApp68k.isStandaloneStack(stack);
		List<File> players = DocumentOpeners.stackPrograms();
		if(!standalone && players.isEmpty()) return false;
		List<Object> choices = new ArrayList<Object>();
		choices.add(IMPORT_STACK);
		if(standalone) choices.add(RUN_STANDALONE);
		choices.addAll(players);
		Object choice = instance.chooseFrom(stack, 0, choices, standalone ? 1 : 0, false, text("How do you want to open %s?"));
		if(IMPORT_STACK.equals(choice)) return false;
		if(RUN_STANDALONE.equals(choice)) instance.launchStandalone(stack);
		else if(choice instanceof File) instance.openDocuments((File)choice, Collections.singletonList(stack));
		return true;
	}
	/** Runs a stand-alone application stack in the 68K VM (its own player code); the history then lists it as a program. */
	void launchStandalone(File stack){
		MacApp68k running = MacApp68k.runningFrom(stack);
		if(running!=null){ running.bringToFront(); return; }
		String id = LauncherStore.identity(stack);
		if(launching.contains(id)) return;
		launching.add(id);
		setStatus(text("Launching")+" \""+LauncherStore.displayName(stack)+"\"…", false);
		try{
			MacApp68k.launch(stack, null);
			LauncherStore.get().recordOpened(stack, LauncherStore.KIND_APP);
			setStatus("", false);
			refresh();
		}catch(IOException e){
			setStatus(text("Can't launch the Mac application.")+" "+e.getMessage(), true);
		}finally{ launching.remove(id); }
	}
	/** A stand-alone application stack the history lists as a program (it was run in the VM last time). */
	private static boolean runsAsProgram(File f){
		if(!MacApp68k.isStandaloneStack(f)) return false;
		String id = LauncherStore.identity(f);
		for(LauncherStore.Item it: LauncherStore.get().recentItems()) if(!it.isDocument() && id.equals(LauncherStore.identity(it.file()))) return it.isApp();
		return false;
	}
	/** A program the launcher runs in the VM: a 68K/PowerPC application, or a stand-alone stack last run as one. */
	private static boolean isProgram(File f){ return f.isFile() && (MacApp68k.isApplication(f) || runsAsProgram(f)); }
	static final String OPEN_PICTURE = "picture", OPEN_RESOURCES = "resources", IMPORT_STACK = "import", RUN_STANDALONE = "standalone";
	/** Tests: answers the "Open With" chooser (the choices offered → the one taken, null for Cancel) instead of showing it. */
	static java.util.function.Function<List<Object>,Object> openerChoiceForTest;
	/** A picture HyperZebra's paint editor opens: PICT, PNG, JPEG, GIF, PBM by type or name. */
	private static boolean isPicture(File f, int type){
		if(type==PictureFile68k.PICT || PictureFile68k.isHostImageType(type) || type==0x47494666) return true; // GIFf
		String n = f.getName().toLowerCase();
		return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".pict") || n.endsWith(".pct") || n.endsWith(".pbm");
	}
	private static boolean hasResources(File f){
		try{ return !ResourceFile68k.open(f).types().isEmpty(); }catch(IOException e){ return false; }
	}
	/**
	 * A file the Finder of a Mac program (DragThing's dock, a launcher sending the Finder 'odoc') hands to the host instead of
	 * the VM: a program of the host (isHostProgram), or a document nothing in the VM opens - no Mac program claims it or
	 * was chosen for its type, and it is no stack, picture, archive or file with resources (those keep HyperZebra's ways).
	 */
	static boolean opensOnHost(File f){
		if(f==null || !f.exists()) return false;
		if(isHostProgram(f)) return true;
		if(!f.isFile() || MacApp68k.isApplication(f) || OStack.looksLikeHyperCardStack(f.getPath()) || StackBundle.isPacked(f) || ZipExpander.isZip(f) || DocumentOpeners.isArchive(f)) return false;
		int type = DocumentOpeners.typeOf(f);
		if(isPicture(f, type) || hasResources(f) || (type!=0 && LauncherStore.get().openerFor(type)!=null)) return false;
		return DocumentOpeners.candidates(f).isEmpty();
	}
	/** A program of the host itself: a Mac OS X application on macOS, a program or shortcut on Windows, a launcher file on Linux. */
	static boolean isHostProgram(File f){
		if(f==null) return false;
		if(GMenu.isMacOSX()) return MacApp68k.isMachOPackage(f);
		String n = f.getName().toLowerCase(java.util.Locale.ROOT);
		if(!f.isFile()) return false;
		if(ResourceFile68k.WINDOWS) return n.endsWith(".exe") || n.endsWith(".lnk") || n.endsWith(".bat") || n.endsWith(".cmd") || n.endsWith(".com") || n.endsWith(".msc") || n.endsWith(".appref-ms");
		return n.endsWith(".desktop") || n.endsWith(".appimage");
	}

	/**
	 * "Open "doc" with:" - a list of the programs that can open the document (their icons and names), the picture editor
	 * and the resource editor when they apply, and "Always open this kind of file with this program". Returns the File
	 * of the program, OPEN_PICTURE, OPEN_RESOURCES, or null when cancelled.
	 */
	private Object chooseOpener(File doc, final int type, List<File> candidates, boolean picture, boolean resources){
		final List<Object> choices = new ArrayList<Object>(candidates);
		if(picture) choices.add(OPEN_PICTURE);
		if(resources) choices.add(OPEN_RESOURCES);
		return chooseFrom(doc, type, choices, 0, true, text("Open %s with:"));
	}
	/** The chooser of {@link #chooseOpener}: {@code always} offers "Always open this kind of file with this program", {@code prompt} has %s for the file name. */
	private Object chooseFrom(File doc, final int type, final List<Object> choices, int selected, boolean offerAlways, String prompt){
		if(openerChoiceForTest!=null) return openerChoiceForTest.apply(choices);
		java.awt.Window owner = isShowing() ? this : PCARD.pc;
		final JDialog dialog = new JDialog(owner instanceof java.awt.Frame ? (java.awt.Frame)owner : null, text("Open With"), true);
		final Object[] result = {null};
		final java.util.Map<File,String> details = new java.util.HashMap<File,String>();
		for(Object c : choices) if(c instanceof File) details.put((File)c, programDetail((File)c));
		final JList<Object> list = new JList<Object>(choices.toArray());
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(40);
		list.setCellRenderer(new ListCellRenderer<Object>(){
			private final JLabel label = new JLabel();
			public Component getListCellRendererComponent(JList<? extends Object> l, Object value, int index, boolean selected, boolean focus){
				label.setToolTipText(null);
				if(value instanceof File){
					File app = (File)value; label.setIcon(icon(app, ICON));
					Color fg = selected ? l.getSelectionForeground() : l.getForeground(), bg = selected ? l.getSelectionBackground() : l.getBackground();
					Color dim = new Color((fg.getRed()*3+bg.getRed()*2)/5, (fg.getGreen()*3+bg.getGreen()*2)/5, (fg.getBlue()*3+bg.getBlue()*2)/5);
					label.setText("<html>"+escapeHtml(LauncherStore.displayName(app))+"<br><font size=-2 color=#"+String.format("%06x", dim.getRGB()&0xffffff)+">"
						+escapeHtml(details.get(app))+"</font></html>");
					label.setToolTipText(LauncherStore.composed(app.toPath().toAbsolutePath().normalize().toString()));
				}
				else if(IMPORT_STACK.equals(value)){ label.setIcon(new ImageIcon(stackIcon())); label.setText(text("Import as a HyperZebra stack")); }
				else if(RUN_STANDALONE.equals(value)){ label.setIcon(new ImageIcon(scaleIcon(documentIconFor(doc), ICON))); label.setText(text("Run as an application in the 68K VM")); }
				else{ label.setIcon(new ImageIcon(OPEN_PICTURE.equals(value) ? stackIcon() : documentIcon())); label.setText(OPEN_PICTURE.equals(value) ? text("HyperZebra picture editor") : text("Resource editor")); }
				label.setOpaque(true);
				label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
				label.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
				label.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
				return label;
			}
		});
		list.setSelectedIndex(selected);
		final JCheckBox always = new JCheckBox(text("Always open this kind of file with this program"));
		always.setEnabled(type!=0 && choices.get(selected) instanceof File);
		always.setVisible(offerAlways);
		list.addListSelectionListener(e -> always.setEnabled(type!=0 && list.getSelectedValue() instanceof File));
		final JButton open = new JButton(text("Open")), cancel = new JButton(text("Cancel"));
		ActionListener ok = new ActionListener(){ public void actionPerformed(ActionEvent e){
			Object v = list.getSelectedValue(); if(v==null) return;
			if(v instanceof File && always.isSelected() && type!=0) LauncherStore.get().setOpenerFor(type, (File)v);
			result[0] = v; dialog.dispose();
		}};
		open.addActionListener(ok);
		cancel.addActionListener(e -> dialog.dispose());
		list.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if(e.getClickCount()==2) ok.actionPerformed(null); } });
		list.addKeyListener(new KeyAdapter(){ @Override public void keyPressed(KeyEvent e){ if(e.getKeyCode()==KeyEvent.VK_ENTER) ok.actionPerformed(null); } });
		dialog.getRootPane().setDefaultButton(open);
		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		JLabel title = new JLabel("<html>"+escapeHtml(prompt).replace("%s", "\""+escapeHtml(LauncherStore.displayName(doc))+"\"")+"</html>");
		title.setIcon(new ImageIcon(scaleIcon(documentIconFor(doc), ICON)));
		content.add(title, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(460, Math.min(6, Math.max(3, choices.size()))*40+4));
		content.add(scroll, BorderLayout.CENTER);
		JPanel south = new JPanel(new BorderLayout());
		south.add(always, BorderLayout.NORTH);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(cancel); buttons.add(open);
		south.add(buttons, BorderLayout.SOUTH);
		content.add(south, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.pack();
		Rectangle area = dialogArea(owner);
		if(area!=null) dialog.setLocation(area.x+(area.width-dialog.getWidth())/2, area.y+(area.height-dialog.getHeight())/2);
		else dialog.setLocationRelativeTo(null);
		list.requestFocusInWindow();
		dialog.setVisible(true);
		return result[0];
	}
	/** The second line of a program in the chooser, so that copies of one program can be told apart: its 'vers' and the folder it is in. */
	static String programDetail(File app){
		String version = programVersion(app), folder = app.toPath().toAbsolutePath().normalize().toFile().getParent();
		if(folder==null) folder = "";
		String home = System.getProperty("user.home");
		if(home!=null && !home.isEmpty() && (folder.equals(home) || folder.startsWith(home+File.separator))) folder = "~"+folder.substring(home.length());
		folder = LauncherStore.composed(folder);
		if(folder.length()>64) folder = folder.substring(0, 24)+"…"+folder.substring(folder.length()-39);
		return version!=null ? version+"  ·  "+folder : folder;
	}
	/** The short version of a program's 'vers' 1 (else 2) resource ("2.1.3", "1.0J"), or null when it has none. */
	static String programVersion(File app){
		try{
			ResourceFile68k rf = ResourceFile68k.open(MacApp68k.programOf(app));
			ResourceFile68k.Resource r = rf.get(ResourceFile68k.fourcc("vers"), 1);
			if(r==null) r = rf.get(ResourceFile68k.fourcc("vers"), 2);
			byte[] d = r!=null ? r.data() : null;
			if(d==null || d.length<6) return null;
			int n = d.length>6 ? d[6]&0xff : 0;
			if(n>0 && 7+n<=d.length){
				boolean ascii = true;
				for(int i = 7; i<7+n; i++) if(d[i]<0x20) ascii = false;
				if(ascii){ String v = new String(d, 7, n, java.nio.charset.StandardCharsets.US_ASCII).trim(); if(!v.isEmpty()) return v; }
			}
			int major = ((d[0]>>4)&15)*10+(d[0]&15), minor = (d[1]>>4)&15, bug = d[1]&15;
			if(major==0 && minor==0 && bug==0) return null;
			String v = major+"."+minor+(bug!=0 ? "."+bug : "");
			int stage = d[2]&0xff, release = d[3]&0xff;
			String s = stage==0x20 ? "d" : stage==0x40 ? "a" : stage==0x60 ? "b" : "";
			return !s.isEmpty() ? v+s+release : v;
		}catch(IOException | RuntimeException e){ return null; }
	}
	private BufferedImage documentIconFor(File doc){
		BufferedImage image = documentImage(doc, null, ICON);
		return image!=null ? image : documentIcon();
	}
	private static String escapeHtml(String s){ return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

	/** The entry's file moved: the user points at it (or removes the entry). */
	private void locate(final LauncherStore.Item item){
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(text("Locate")+" \""+item.name()+"\"");
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		File parent = item.file().getParentFile();
		if(parent!=null && parent.isDirectory()) chooser.setCurrentDirectory(parent);
		FileChooserPlaces.attach(chooser);
		if(PCARD.showFileChooser(chooser, this, false, false)==JFileChooser.APPROVE_OPTION){
			int changed = LauncherStore.get().relocate(item, chooser.getSelectedFile());
			refresh();
			relocated(item.name(), changed);
		}
	}
	/** A dock button's file moved: the user points at it, and its settings, history and documents follow (LauncherStore.relocate). */
	void locate(final File missing){
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(text("Locate")+" \""+LauncherStore.displayName(missing)+"\"");
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		File parent = missing.getParentFile();
		while(parent!=null && !parent.isDirectory()) parent = parent.getParentFile();
		if(parent!=null) chooser.setCurrentDirectory(parent);
		FileChooserPlaces.attach(chooser);
		if(PCARD.showFileChooser(chooser, this, false, false)!=JFileChooser.APPROVE_OPTION) return;
		int changed = LauncherStore.get().relocate(missing, chooser.getSelectedFile());
		refresh();
		relocated(LauncherStore.displayName(missing), changed);
	}
	/** Says what moved along with the located file. */
	private void relocated(String name, int changed){
		setStatus(text("\"%s\" was found; its dock button, settings and documents now point to the new place.").replace("%s", name)
			+(changed>1 ? " ("+text("%d references updated").replace("%d", Integer.toString(changed))+")" : ""), false);
	}

	/** Opens a program read-only (it changes nothing outside HyperZebra HD), or a document read-only in its program. */
	void openReadOnly(File app, File document){
		if(document==null){
			if(MacApp68k.runningFrom(app)!=null){ setStatus(text("\"%s\" is running; quit it first to open it read-only.").replace("%s", LauncherStore.displayName(app)), true); return; }
			Sandbox.readOnlyNextLaunch(app);
			open(app);
		}else{
			Sandbox.readOnlyDocumentNext(document);
			openDocument(app, document);
		}
	}
	/** Copies a program (with its own folder) or a document into HyperZebra HD's Working Copies and opens the copy. */
	void openWorkingCopy(final File app, final File document){
		File original = document!=null ? document : app;
		WorkingCopies.make(this, original, new java.util.function.Consumer<File>(){ public void accept(File copy){
			if(document==null) open(copy); else openDocument(app, copy);
			refresh();
		}});
	}

	private void openFileDialog(){
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(text("Open File"));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		chooser.setMultiSelectionEnabled(true);
		FileChooserPlaces.attach(chooser);
		//開き方: 古いプログラムや書類を、元のファイルを変えずに開く導線(読み取り専用/作業コピー)
		javax.swing.JRadioButton normal = new javax.swing.JRadioButton(text("Normally"), true),
			readOnly = new javax.swing.JRadioButton(text("Read-only")), copy = new javax.swing.JRadioButton(text("As a working copy"));
		javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
		group.add(normal); group.add(readOnly); group.add(copy);
		readOnly.setToolTipText(text("Files outside HyperZebra HD are not changed."));
		copy.setToolTipText(text("Opens a copy in HyperZebra HD; the original stays as it is."));
		JPanel how = new JPanel(new java.awt.GridLayout(0, 1));
		how.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 0));
		JLabel howTitle = new JLabel(text("Open programs and documents:"));
		howTitle.setFont(howTitle.getFont().deriveFont(Font.BOLD, howTitle.getFont().getSize2D()-1f));
		how.add(howTitle); how.add(normal); how.add(readOnly); how.add(copy);
		JPanel accessory = new JPanel(new BorderLayout());
		if(chooser.getAccessory()!=null) accessory.add(chooser.getAccessory(), BorderLayout.CENTER);
		accessory.add(how, BorderLayout.SOUTH);
		chooser.setAccessory(accessory);
		if(PCARD.showFileChooser(chooser, this, false, false)!=JFileChooser.APPROVE_OPTION) return;
		List<File> files = new ArrayList<File>();
		for(File f: chooser.getSelectedFiles()) files.add(GMenuBrowse.stackFolderOf(f));
		if(files.isEmpty()) files.add(GMenuBrowse.stackFolderOf(chooser.getSelectedFile()));
		setStatus("", false);
		if(readOnly.isSelected()){
			for(File f: files){
				File program = MacApp68k.programOf(f);
				if(program.isFile() && MacApp68k.isApplication(program)) Sandbox.readOnlyNextLaunch(program);
				else if(f.isFile()) Sandbox.readOnlyDocumentNext(f);
			}
		}else if(copy.isSelected()){
			for(File f: files) WorkingCopies.make(this, f, new java.util.function.Consumer<File>(){ public void accept(File c){
				PCARD.openFiles(Collections.singletonList(c)); refresh(); }});
			return;
		}
		PCARD.openFiles(files);
	}

	/** File > New Stack… (and the side button): the card window's New Stack dialog, without a stack open first; the new stack opens. */
	private void newStack(){
		File f = GMenuBrowse.newStackDialog(this, false);
		if(f!=null) PCARD.openFiles(Collections.singletonList(f), false);
	}

	private void openHyperZebraHD(){
		File hd = AppPaths.stateDirectory();
		if(!OStack.revealInFileManager(hd)) setStatus(text("HyperZebra HD")+": "+hd.getPath(), false);
	}

	private void revealFile(File f){
		if(!OStack.revealLocationInFileManager(f)) setStatus(f.getPath(), false);
	}

	/** The context menu of a history row: its own menu, or the menu of the selected rows when it is one of several. */
	private JPopupMenu historyMenu(LauncherStore.Item item){
		List<LauncherStore.Item> selected = list.getSelectedValuesList();
		if(selected.size()<2 || !selected.contains(item)) return itemMenu(item);
		JPopupMenu menu = new JPopupMenu();
		JMenuItem mi;
		menu.add(mi = new JMenuItem(text("Remove {0} Items from History").replace("{0}", Integer.toString(selected.size()))));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ removeSelectedFromHistory(); }});
		menu.add(mi = new JMenuItem(text("Clear History")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ LauncherStore.get().clearRecent(); showCard("recent"); refresh(); }});
		return menu;
	}

	/** Removes the selected history rows (the files stay); the row now at the first removed place is selected, so Delete can go on. */
	void removeSelectedFromHistory(){
		List<LauncherStore.Item> selected = list.getSelectedValuesList();
		if(selected.isEmpty()) return;
		int first = list.getMinSelectionIndex();
		list.clearSelection();
		LauncherStore.get().removeAll(selected);
		refresh();
		if(model.getSize()>0){ int i = Math.min(first, model.getSize()-1); list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
	}

	private JPopupMenu itemMenu(final LauncherStore.Item item){
		JPopupMenu menu = new JPopupMenu();
		JMenuItem mi;
		final boolean exists = item.exists();
		if(exists && item.isDocument()){
			File app = item.appFile();
			menu.add(mi = new JMenuItem(text("Open with \"{0}\"").replace("{0}", LauncherStore.displayName(app))));
			mi.setEnabled(app!=null && app.isFile());
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openItem(item); }});
			addSafeOpenItems(menu, app, item.file());
			menu.add(mi = new JMenuItem(text("Show in Finder")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ revealFile(item.file()); }});
			if(app!=null && app.isFile()) addSettingsItem(menu, app, true);
		}else if(exists){
			final MacApp68k running = MacApp68k.runningFrom(item.file());
			menu.add(mi = new JMenuItem(text(running!=null ? "Bring to Front" : "Open")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openItem(item); }});
			if(running==null && item.isApp() && MacApp68k.isApplication(item.file())) addSafeOpenItems(menu, item.file(), null);
			if(running==null && MacApp68k.isStandaloneStack(item.file())){ // a stand-alone application stack: the other way of opening it
				final boolean asProgram = item.isApp();
				menu.add(mi = new JMenuItem(text(asProgram ? "Import as a HyperZebra stack" : "Run as an application in the 68K VM")));
				mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
					if(asProgram) PCARD.openFiles(Collections.singletonList(item.file()), false); else launchStandalone(item.file()); }});
			}
			if(appearance.dockTabs) menu.add(dockTabsMenu(item.file()));
			else{
				boolean inDock = LauncherStore.get().isInDock(item.file());
				menu.add(mi = new JMenuItem(text(inDock ? "Remove from Dock" : "Add to Dock")));
				mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
					if(inDock) LauncherStore.get().removeFromDock(item.file()); else LauncherStore.get().addToDock(item.file()); refresh(); }});
			}
			menu.add(mi = new JMenuItem(text("Show in Finder")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ revealFile(item.file()); }});
			if(item.isApp() && isProgram(item.file())){ addSettingsItem(menu, item.file(), false); addTranslateAheadItem(menu, item.file()); }
			else if(StackTranslation.isStackFile(item.file())){ addStackSettingsItem(menu, item.file()); addTranslateAheadItem(menu, item.file()); }
			if(running!=null){
				menu.addSeparator();
				addQuitItems(menu, running);
			}
		}else{
			menu.add(mi = new JMenuItem(text("Locate…")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ locate(item); }});
		}
		menu.addSeparator();
		menu.add(mi = new JMenuItem(text("Remove from History")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ LauncherStore.get().remove(item); refresh(); }});
		menu.add(mi = new JMenuItem(text("Clear History")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ LauncherStore.get().clearRecent(); showCard("recent"); refresh(); }});
		return menu;
	}

	/** "Open Read-Only" and "Open a Working Copy…" of a program (document null) or of a document in its program. */
	private void addSafeOpenItems(JPopupMenu menu, final File app, final File document){
		JMenuItem mi;
		menu.add(mi = new JMenuItem(text("Open Read-Only")));
		mi.setToolTipText(text("Files outside HyperZebra HD are not changed."));
		mi.setEnabled(app!=null && app.isFile());
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openReadOnly(app, document); }});
		menu.add(mi = new JMenuItem(text("Open a Working Copy…")));
		mi.setToolTipText(text("Opens a copy in HyperZebra HD; the original stays as it is."));
		mi.setEnabled(app!=null && app.isFile());
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openWorkingCopy(app, document); }});
	}

	/** "Application Settings…" of a program (colours, screen, speed…); for a document row it names the program that opens it. */
	private void addSettingsItem(JPopupMenu menu, final File app, boolean named){
		JMenuItem mi;
		menu.add(mi = new JMenuItem(named ? text("Settings of \"{0}\"…").replace("{0}", LauncherStore.displayName(app)) : text("Application Settings…")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppSettingsDialog.show(GLauncher.this, app); }});
	}

	/** "Individual Stack Settings…" of a HyperZebra stack: its zoom, keyboard and controller, and access outside its folders. */
	private void addStackSettingsItem(JPopupMenu menu, final File stack){
		JMenuItem mi;
		menu.add(mi = new JMenuItem(text("Individual Stack Settings…")));
		mi.setToolTipText(text("Zoom, keys, game controller and access confirmation for this stack only"));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppSettingsDialog.show(GLauncher.this, stack); }});
	}

	/** "Resource Translation…": the program's menus, dialogs and strings translated into resources of their own it runs with from
	 *  its next launch (ResourceTranslation; when this Mac can translate); of a
	 *  stack, its button names and field texts (shown translated from then on). "Resource Translation with AI Chat…" does the
	 *  same through ChatGPT or another AI chat (GLlmTranslationDialog; on any computer). */
	private void addTranslateAheadItem(JPopupMenu menu, final File app){
		JMenuItem mi;
		if(VMTranslation.unavailableReason()==null){
			menu.add(mi = new JMenuItem(text("Resource Translation…")));
			mi.setToolTipText(text(StackTranslation.isStackFile(app) ? "Translates the stack's button names and fields now." : "Translates its menus and dialogs into a copy of its resources."));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GTranslateAheadDialog.show(GLauncher.this, app); }});
		}
		menu.add(mi = new JMenuItem(text("Resource Translation with AI Chat…")));
		mi.setToolTipText(text("Copy a request into ChatGPT or another AI chat and paste its answer back."));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GLlmTranslationDialog.show(GLauncher.this, app); }});
		menu.add(mi = new JMenuItem(text("Translation Settings…")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GTranslationSettingsDialog.show(GLauncher.this, app); }});
	}

	/**
	 * The "Mac Application Settings" page: the shared settings, headed by the way to one program's own settings (the
	 * programs in the history and the dock, or any program file) - otherwise only the dock's and history's menus lead there.
	 */
	private JComponent vmPage(JComponent form){
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(16, 11, 0, 16));
		final JButton button = new JButton(text("Application Settings…"));
		button.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ programSettingsMenu().show(button, 0, button.getHeight()); }});
		row.add(button);
		JLabel note = new JLabel(text("Colors, screen size and speed for one program"));
		note.setFont(note.getFont().deriveFont(note.getFont().getSize2D()-1f));
		row.add(note);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		page.add(row);
		form.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(form);
		return page;
	}
	/**
	 * The "Settings" page: the settings every stack starts with, headed by the way to one stack's own (zoom, keys, game
	 * controller, access) - also in the history's and the dock's menus and the stack's File menu.
	 */
	private JComponent stackSettingsPage(JComponent form){
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(16, 11, 0, 16));
		final JButton button = new JButton(text("Individual Stack Settings…"));
		button.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ stackSettingsMenu().show(button, 0, button.getHeight()); }});
		row.add(button);
		JLabel note = new JLabel(text("Zoom, keys, game controller and access confirmation for one stack"));
		note.setFont(note.getFont().deriveFont(note.getFont().getSize2D()-1f));
		row.add(note);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		page.add(row);
		form.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(form);
		return page;
	}
	/** The stacks to choose from for their own settings: the open stack, the dock's, the history's; then "Other…" (a stack folder). */
	JPopupMenu stackSettingsMenu(){
		JPopupMenu menu = new JPopupMenu();
		java.util.LinkedHashMap<String, File> stacks = new java.util.LinkedHashMap<String, File>();
		File open = openStack();
		if(open!=null) stacks.put(LauncherStore.identity(open), open);
		for(LauncherStore.Item it: LauncherStore.get().recentItems()) if(it.exists() && StackTranslation.isStackFile(it.file())) stacks.put(LauncherStore.identity(it.file()), it.file());
		for(File f: LauncherStore.get().allDockItems()) if(StackTranslation.isStackFile(f)) stacks.put(LauncherStore.identity(f), f);
		int n = 0;
		for(final File f: stacks.values()){
			if(!StackTranslation.isStackFile(f)) continue;
			if(++n>20) break;
			JMenuItem mi = new JMenuItem(LauncherStore.displayName(f), icon(f, 16));
			mi.setToolTipText(f.getPath());
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppSettingsDialog.show(GLauncher.this, f); }});
			menu.add(mi);
		}
		if(n>0) menu.addSeparator();
		JMenuItem other = new JMenuItem(text("Other…"));
		other.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(text("Individual Stack Settings…"));
			chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
			FileChooserPlaces.attach(chooser);
			if(PCARD.showFileChooser(chooser, GLauncher.this, false, false)!=JFileChooser.APPROVE_OPTION) return;
			File f = chooser.getSelectedFile();
			if(f==null) return;
			if(f.isFile() && (f.getName().equals("_stack.xml") || f.getName().equals("toc.xml"))) f = f.getParentFile();
			if(!StackTranslation.isStackFile(f)){ setStatus(text("Not a HyperZebra stack")+": "+LauncherStore.displayName(f), true); return; }
			GAppSettingsDialog.show(GLauncher.this, f);
		}});
		menu.add(other);
		return menu;
	}
	/**
	 * The "Translation" page: the translation settings every program and stack starts with, headed by the way to one
	 * program's or stack's own translation settings.
	 */
	private JComponent translationPage(JComponent form){
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(16, 11, 0, 16));
		final JButton button = new JButton(text("Translation Settings…"));
		button.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ translationSettingsMenu().show(button, 0, button.getHeight()); }});
		row.add(button);
		JLabel note = new JLabel(text("Language and engine for one program or stack"));
		note.setFont(note.getFont().deriveFont(note.getFont().getSize2D()-1f));
		row.add(note);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		page.add(row);
		form.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(form);
		return page;
	}
	/** The programs and stacks to choose from for their own translation settings: the open stack, running programs, the dock's, the history's; then "Other…". */
	JPopupMenu translationSettingsMenu(){
		JPopupMenu menu = new JPopupMenu();
		java.util.LinkedHashMap<String, File> files = new java.util.LinkedHashMap<String, File>();
		File open = openStack();
		if(open!=null) files.put(LauncherStore.identity(open), open);
		for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service && app.file!=null) files.put(LauncherStore.identity(app.file), app.file);
		for(LauncherStore.Item it: LauncherStore.get().recentItems()) if(it.exists() && (it.isApp() || StackTranslation.isStackFile(it.file()))) files.put(LauncherStore.identity(it.file()), it.file());
		for(File f: LauncherStore.get().allDockItems()) if(f.exists() && (MacApp68k.isApplication(f) || StackTranslation.isStackFile(f))) files.put(LauncherStore.identity(f), f);
		int n = 0;
		for(final File f: files.values()){
			if(!StackTranslation.isStackFile(f) && !(f.isFile() && MacApp68k.isApplication(f))) continue;
			if(++n>20) break;
			JMenuItem mi = new JMenuItem(LauncherStore.displayName(f), icon(f, 16));
			mi.setToolTipText(f.getPath());
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GTranslationSettingsDialog.show(GLauncher.this, f); }});
			menu.add(mi);
		}
		if(n>0) menu.addSeparator();
		JMenuItem other = new JMenuItem(text("Other…"));
		other.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(text("Translation Settings…"));
			chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
			FileChooserPlaces.attach(chooser);
			if(PCARD.showFileChooser(chooser, GLauncher.this, false, false)!=JFileChooser.APPROVE_OPTION) return;
			File f = chooser.getSelectedFile();
			if(f==null) return;
			if(f.isFile() && (f.getName().equals("_stack.xml") || f.getName().equals("toc.xml"))) f = f.getParentFile();
			if(!StackTranslation.isStackFile(f) && !(f.isFile() && MacApp68k.isApplication(f))){ setStatus(text("Not a Mac application or a HyperZebra stack")+": "+LauncherStore.displayName(f), true); return; }
			GTranslationSettingsDialog.show(GLauncher.this, f);
		}});
		menu.add(other);
		return menu;
	}
	/** The programs to choose from for their own settings: running ones, the dock's, the history's; then "Other…" (a file). */
	JPopupMenu programSettingsMenu(){
		JPopupMenu menu = new JPopupMenu();
		java.util.LinkedHashMap<String, File> programs = new java.util.LinkedHashMap<String, File>();
		for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service && app.file!=null) programs.put(LauncherStore.identity(app.file), app.file);
		for(LauncherStore.Item it: LauncherStore.get().recentItems()) if(it.isApp() && it.exists()) programs.put(LauncherStore.identity(it.file()), it.file());
		for(File f: LauncherStore.get().allDockItems()) if(f.isFile() && MacApp68k.isApplication(f)) programs.put(LauncherStore.identity(f), f);
		int n = 0;
		for(final File f: programs.values()){
			if(!MacApp68k.isApplication(f)) continue;
			if(++n>20) break;
			JMenuItem mi = new JMenuItem(LauncherStore.displayName(f), icon(f, 16));
			mi.setToolTipText(f.getPath());
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppSettingsDialog.show(GLauncher.this, f); }});
			menu.add(mi);
		}
		if(n>0) menu.addSeparator();
		JMenuItem other = new JMenuItem(text("Other…"));
		other.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(text("Application Settings…"));
			chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			FileChooserPlaces.attach(chooser);
			if(PCARD.showFileChooser(chooser, GLauncher.this, false, false)!=JFileChooser.APPROVE_OPTION) return;
			File f = chooser.getSelectedFile();
			if(f==null) return;
			if(!MacApp68k.isApplication(f)){ setStatus(text("Not a Mac application")+": "+LauncherStore.displayName(f), true); return; }
			GAppSettingsDialog.show(GLauncher.this, f);
		}});
		menu.add(other);
		return menu;
	}

	/**
	 * A running program's "Quit" (it is asked, the way its own Quit menu item does, and may save) and "Force Quit…" (after
	 * a confirmation it is stopped at once: {@link MacApp68k#forceQuit}) - for a program that no longer answers.
	 */
	private void addQuitItems(JPopupMenu menu, final MacApp68k running){
		JMenuItem mi;
		menu.add(mi = new JMenuItem(text("Quit")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ running.requestQuit(); }});
		menu.add(mi = new JMenuItem(text("Force Quit…")));
		mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppInfoWindow.confirmForceQuit(GLauncher.this, running); }});
	}

	// ------------------------------------------------------------------ the dock

	private final class DockButton extends JButton {
		private static final long serialVersionUID = 1L;
		final File file; final boolean registered; final MacApp68k running;
		/** The stack on show in the card window (decided when the dock is built, like {@link #running}). */
		final boolean stackOpen;
		/** On its way out of the dock (see {@link GLauncher#departing}): shown while its icon shrinks away, not counted as a dock item. */
		boolean departing;
		DockButton(File file, boolean registered, MacApp68k running){
			this.file = file; this.registered = registered; this.running = running;
			this.stackOpen = running==null && isOpenStack(file);
			String name = LauncherStore.displayName(file);
			setToolTipText(name+(running!=null ? " ("+text("running")+")" : stackOpen ? " ("+text("open")+")" : "")+(!registered ? " - "+text("not in the dock") : ""));
			putClientProperty("launcher.dock", true);
		setIcon(new AppearIcon(icon(file, dockIconSize()), LauncherStore.identity(file)));
		getAccessibleContext().setAccessibleName(name);
			setFont(getFont().deriveFont(11f));
			setText(shorten(name));
			setVerticalTextPosition(SwingConstants.BOTTOM);
			setHorizontalTextPosition(SwingConstants.CENTER);
			setMargin(new Insets(4, 4, 12, 4));
		setBorder(BorderFactory.createEmptyBorder(4, 4, 12, 4));
			setFocusPainted(true);
			Dimension d = new Dimension(dockIconSize()+32, dockIconSize()+30);
			setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ activate(); }});
			MouseAdapter mouse = new MouseAdapter(){
				private java.awt.Point pressed;
				@Override public void mousePressed(MouseEvent e){
					if(e.isPopupTrigger()){ requestFocusInWindow(); showMenu(); return; }
					if(SwingUtilities.isLeftMouseButton(e)) pressed = e.getPoint();
				}
				@Override public void mouseDragged(MouseEvent e){
					if(pressed==null) return;
					if(dockDrag==null){
						if(Math.abs(e.getX()-pressed.x)<6 && Math.abs(e.getY()-pressed.y)<6) return;
						dockDrag = DockButton.this; // from here on it is a drag, not a click
						dockDragTab = LauncherStore.get().shownDockTab();
						getModel().setArmed(false); getModel().setPressed(false);
						setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
					}
					// by the screen position: after another tab comes up this button is no longer laid out in the dock
					java.awt.Point onTabs = tabStripPoint(e.getLocationOnScreen());
					tabStrip.dragHover(onTabs);
					if(onTabs!=null) dockDropIndex = -1;
					else{
						java.awt.Point p = new java.awt.Point(e.getLocationOnScreen());
						SwingUtilities.convertPointFromScreen(p, dockPanel);
						dockDropIndex = dockInsertionIndex(p);
					}
					dockPanel.repaint();
				}
				@Override public void mouseReleased(MouseEvent e){
					if(e.isPopupTrigger()){ pressed = null; requestFocusInWindow(); showMenu(); return; }
					pressed = null;
					if(dockDrag!=DockButton.this) return;
					int index = dockDropIndex;
					dockDrag = null; dockDropIndex = -1;
					setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
					dockPanel.repaint();
					java.awt.Point onTabs = tabStripPoint(e.getLocationOnScreen());
					tabStrip.dragHover(null);
					boolean left = !isVisible(); // kept for its drag only (see refreshDock): the dock no longer shows it
					int from = registered ? dockDragTab : -1, shownTab = LauncherStore.get().shownDockTab();
					if(onTabs!=null){ dropOnTab(file, from, tabStrip.tabAt(onTabs)); if(left) refreshDock(); return; }
					if(index<0){ if(left) refreshDock(); return; }
					index = savedDockIndex(registeredButtons(), index); // the running items in front keep their saved places too
					int own = savedDockIndex(LauncherStore.get().dockItems(), file);
					if(own>=0 && index>own) index--; // the item leaves its old place first
					if(from>=0 && from!=shownTab){ LauncherStore.get().moveToDockTab(file, from, shownTab, index); refreshDock(); return; } // from another tab: it moves into this one
					if(own==index){ if(left) refreshDock(); return; }
					placeInDock(file, index);
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
			addKeyListener(new KeyAdapter(){
				@Override public void keyPressed(KeyEvent e){
					if(e.getKeyCode()==KeyEvent.VK_CONTEXT_MENU || (e.getKeyCode()==KeyEvent.VK_F10 && e.isShiftDown())){ showMenu(); e.consume(); }
				}
			});
		}
		private String shorten(String name){
            java.awt.FontMetrics metrics = getFontMetrics(getFont());
            int available = dockIconSize()+20;
            if(metrics.stringWidth(name)<=available) return name;
            int end = name.length();
            while(end>0 && metrics.stringWidth(name.substring(0, end)+"...")>available)
                end = name.offsetByCodePoints(end, -1);
            return name.substring(0, end)+"...";
        }
		/** A running program, or the stack on show: the dot is painted and a click brings it to the front. */
		boolean isActive(){ return (running!=null && !running.finished) || (stackOpen && isOpenStack(file)); }
		void activate(){
			dockOrderHeldUntil = System.currentTimeMillis()+DOCK_HOLD_MS; dockHoldEnd.stop();
			if(running!=null && !running.finished){ running.bringToFront(); return; }
			if(stackOpen && isOpenStack(file)){ bringStackToFront(file); return; }
			if(!MacApp68k.programOf(file).exists()){ GLauncher.this.locate(file); return; } //移動した: 場所を尋ねる(履歴の項目と同じ)
			open(file);
		}
		/** A program's button: documents dropped on it open in it. */
		boolean takesDocuments(){ return file.isFile() && MacApp68k.isApplication(file); }
		@Override protected void paintComponent(Graphics g){
			super.paintComponent(g);
			if(dockDropButton==this){ // a file drag hovers here
				Graphics2D g2 = LauncherAppearance.smooth(g);
				g2.setColor(appearance.accent());
				g2.setStroke(new BasicStroke(2f));
				g2.drawRoundRect(2, 2, getWidth()-5, getHeight()-5, 12, 12);
				g2.dispose();
			}
			if(isActive()){
				Graphics2D g2 = (Graphics2D)g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(appearance.secondary());
				g2.fillOval(getWidth()/2-3, getHeight()-8, 6, 6);
				g2.dispose();
			}
		}
		void showMenu(){
            JPopupMenu menu = buildMenu();
            if(appearance.dockGrid) menu.show(this, 0, getHeight()); else menu.show(this, 0, -menu.getPreferredSize().height-6);
        }
        JPopupMenu buildMenu(){
			JPopupMenu menu = new JPopupMenu();
			JMenuItem mi;
			final boolean isRunning = running!=null && !running.finished;
			final boolean isOpen = stackOpen && isOpenStack(file);
			final boolean isApp = file.isFile() && MacApp68k.isApplication(file);
			menu.add(mi = new JMenuItem(text(isRunning || isOpen ? "Bring to Front" : "Open")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ activate(); }});
			mi.setEnabled(isRunning || isOpen || file.exists());
			if(!file.exists() && !isRunning && !isOpen){
				//移動した: 場所を教えると、ドック・個別設定・書類の記録がまとめて新しい場所に移る
				menu.add(mi = new JMenuItem(text("Locate…")));
				mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GLauncher.this.locate(file); }});
			}
			if(isApp && !isRunning) addSafeOpenItems(menu, file, null);
			if(isApp){
				JMenu docs = new JMenu(text("Recent Documents"));
				List<File> documents = LauncherStore.get().documentsOf(file);
				if(documents.isEmpty()){ docs.add(mi = new JMenuItem(text("None"))); mi.setEnabled(false); }
				for(final File d: documents){
					docs.add(mi = new JMenuItem(LauncherStore.displayName(d)));
					mi.setToolTipText(d.getPath());
					mi.setEnabled(d.exists());
					mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ openDocument(file, d); }});
				}
				menu.add(docs);
				menu.add(mi = new JMenuItem(text("Application Settings…")));
				mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ GAppSettingsDialog.show(GLauncher.this, file); }});
				addTranslateAheadItem(menu, file);
			}else if(StackTranslation.isStackFile(file)){ addStackSettingsItem(menu, file); addTranslateAheadItem(menu, file); }
			menu.add(mi = new JMenuItem(text("Show in Finder")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ revealFile(file); }});
			mi.setEnabled(file.exists());
			menu.add(mi = new JMenuItem(text(registered ? "Remove from Dock" : "Add to Dock")));
			mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
				if(registered) LauncherStore.get().removeFromDock(file); else LauncherStore.get().addToDock(file); refreshDock(); }});
			if(isRunning){
				menu.addSeparator();
				addQuitItems(menu, running);
			}else if(isOpen){
				menu.addSeparator();
				menu.add(mi = new JMenuItem(text("Close Stack")));
				mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
					if(isStackOnShow(file)) PCARD.closeCurrentStack(); else GStackWindow.closeStack(TTalk.windowStackKey(file.getPath())); }});
			}
			return menu;
		}
	}

	/** The colours a tab can take from its menu (Other… chooses any). */
	private static final int[] TAB_COLORS = {0xD64545, 0xE5832B, 0xE0BA2A, 0x3D9E58, 0x3A78D2, 0x8A5BC6, 0xD5528D, 0x878D98};
	private static final String[] TAB_COLOR_NAMES = {"Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink", "Gray"};
	/** How long a drag must rest on a tab before the tab opens (a drag passing over it quickly changes nothing). */
	private static final int TAB_SPRING_MS = 600;

	/**
	 * The dock's tabs (DragThing's): a row above the dock's row or grid, or a column beside its column with the names
	 * turned to read upwards. A click shows a tab's items; the last tab, "+", adds one; tabs are dragged to a new order;
	 * a tab's menu renames, colours or deletes it. They share the strip's length, shrinking (names cut short) when they
	 * do not fit. A drag that rests on a tab opens it (dock buttons, history rows, files), and a dock button or a history
	 * row dropped on a tab goes into it.
	 */
	private final class DockTabStrip extends JComponent {
		private static final long serialVersionUID = 1L;
		private static final int THICK = 22, PLUS = 26, GAP = 3, MAX_TAB = 160, MIN_TAB = 10;
		/** Index of the "+" tab in {@link #tabAt}'s answers; -1 is no tab. */
		static final int PLUS_TAB = Integer.MAX_VALUE;
		boolean vertical;
		private int hover = -1, pressed = -1, dropAt = -1, spring = -1;
		private java.awt.Point pressedAt; private boolean dragging;
		private final javax.swing.Timer springTimer = new javax.swing.Timer(TAB_SPRING_MS, e -> {
			int t = spring; spring = -1; repaint();
			if(t>=0 && t<LauncherStore.get().dockTabs().size()) selectDockTab(t);
		});
		DockTabStrip(){
			springTimer.setRepeats(false);
			setFont(new JLabel().getFont().deriveFont(11f));
			setToolTipText("");
			MouseAdapter mouse = new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){
					if(e.isPopupTrigger()){ showMenu(e.getPoint()); return; }
					if(!SwingUtilities.isLeftMouseButton(e)) return;
					int t = tabAt(e.getPoint());
					pressed = t; pressedAt = e.getPoint(); dragging = false;
					if(t>=0 && t!=PLUS_TAB){
						if(e.getClickCount()==2){ pressed = -1; renameTab(t); return; }
						selectDockTab(t);
					}
				}
				@Override public void mouseDragged(MouseEvent e){
					if(pressed<0 || pressed==PLUS_TAB || pressedAt==null) return;
					if(!dragging && Math.abs(e.getX()-pressedAt.x)<5 && Math.abs(e.getY()-pressedAt.y)<5) return;
					dragging = true;
					int at = insertionAt(e.getPoint());
					if(at!=dropAt){ dropAt = at; repaint(); }
				}
				@Override public void mouseReleased(MouseEvent e){
					if(e.isPopupTrigger()){ pressed = -1; showMenu(e.getPoint()); return; }
					int t = pressed, at = dropAt; boolean moved = dragging;
					pressed = -1; dragging = false; dropAt = -1; repaint();
					if(moved){ if(at>=0) moveTab(t, at); return; }
					if(t==PLUS_TAB && tabAt(e.getPoint())==PLUS_TAB) selectDockTab(addTab());
				}
				@Override public void mouseMoved(MouseEvent e){ int t = tabAt(e.getPoint()); if(t!=hover){ hover = t; repaint(); } }
				@Override public void mouseExited(MouseEvent e){ if(hover!=-1){ hover = -1; repaint(); } }
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}
		@Override public Dimension getPreferredSize(){ return vertical ? new Dimension(THICK+4, 60) : new Dimension(60, THICK+4); }
		@Override public Dimension getMinimumSize(){ return getPreferredSize(); }
		private int length(){ return vertical ? getHeight() : getWidth(); }
		private String name(LauncherStore.DockTab t){ return t.name!=null ? t.name : text("Dock"); }
		/** The tabs' places along the strip, the "+" tab last; the tabs share what the "+" leaves, the longest giving way first. */
		private List<Rectangle> layoutTabs(List<LauncherStore.DockTab> tabs){
			FontMetrics fm = getFontMetrics(getFont());
			int n = tabs.size();
			int[] want = new int[n];
			for(int i=0; i<n; i++) want[i] = Math.min(MAX_TAB, fm.stringWidth(name(tabs.get(i)))+22);
			int room = length()-PLUS-GAP*n-2, total = 0;
			for(int w: want) total += w;
			int cap = Integer.MAX_VALUE;
			if(total>room && n>0){ // the widest share one width, the ones under it keep theirs
				int[] sorted = want.clone(); java.util.Arrays.sort(sorted);
				int left = room, count = n;
				for(int w: sorted){ if(w*count<=left){ left -= w; count--; } else break; }
				cap = Math.max(MIN_TAB, count>0 ? left/count : MIN_TAB);
			}
			List<Rectangle> r = new ArrayList<Rectangle>();
			int at = 1;
			for(int i=0; i<=n; i++){
				int w = i<n ? Math.min(want[i], cap) : PLUS;
				r.add(vertical ? new Rectangle(2, at, THICK, w) : new Rectangle(at, 2, w, THICK));
				at += w+GAP;
			}
			return r;
		}
		/** The tab at a point: its index, {@link #PLUS_TAB}, or -1. */
		int tabAt(java.awt.Point p){
			List<Rectangle> r = layoutTabs(LauncherStore.get().dockTabs());
			for(int i=0; i<r.size(); i++) if(r.get(i).contains(p)) return i==r.size()-1 ? PLUS_TAB : i;
			return -1;
		}
		/** Where a dragged tab would go: how many tabs' middles lie before the point. */
		private int insertionAt(java.awt.Point p){
			List<Rectangle> r = layoutTabs(LauncherStore.get().dockTabs());
			int along = vertical ? p.y : p.x, at = 0;
			for(int i=0; i<r.size()-1; i++){ Rectangle t = r.get(i); if((vertical ? t.y+t.height/2 : t.x+t.width/2) < along) at++; }
			return at;
		}
		/** A drag (of a dock button, a history row, files) over the strip at a point, or off it (null): resting on a tab opens it. */
		void dragHover(java.awt.Point p){
			int t = p==null ? -1 : tabAt(p);
			if(t==PLUS_TAB || t==LauncherStore.get().shownDockTab()) t = -1;
			if(t==spring) return;
			spring = t; repaint();
			if(t>=0) springTimer.restart(); else springTimer.stop();
		}
		@Override public String getToolTipText(MouseEvent e){
			int t = tabAt(e.getPoint());
			if(t==PLUS_TAB) return text("New Tab");
			List<LauncherStore.DockTab> tabs = LauncherStore.get().dockTabs();
			return t>=0 && t<tabs.size() ? name(tabs.get(t)) : null;
		}
		@Override protected void paintComponent(Graphics graphics){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			LauncherStore store = LauncherStore.get();
			List<LauncherStore.DockTab> tabs = store.dockTabs();
			List<Rectangle> r = layoutTabs(tabs);
			int selected = store.shownDockTab();
			FontMetrics fm = g.getFontMetrics(getFont());
			for(int i=0; i<r.size(); i++){
				Rectangle b = r.get(i);
				boolean plus = i==tabs.size(), on = i==selected, over = i==(hover==PLUS_TAB ? tabs.size() : hover) || i==spring;
				Integer rgb = plus ? null : tabs.get(i).color;
				Color base = rgb!=null ? new Color(rgb) : appearance.accent();
				Color fill = on ? base : rgb!=null ? LauncherAppearance.mix(appearance.surface(), base, appearance.dark() ? .42f : .30f) : LauncherAppearance.mix(appearance.surface(), appearance.line(), .55f);
				if(over && !on) fill = LauncherAppearance.mix(fill, base, .22f);
				if(i==pressed && dragging) fill = LauncherAppearance.mix(fill, appearance.foreground(), .12f);
				g.setColor(fill);
				g.fillRoundRect(b.x, b.y, b.width, b.height, 10, 10);
				if(i==spring){ g.setColor(appearance.accent()); g.setStroke(new BasicStroke(2f)); g.drawRoundRect(b.x+1, b.y+1, b.width-2, b.height-2, 10, 10); }
				Color ink = on ? (luminance(base)>0.6 ? new Color(0x222735) : Color.WHITE) : appearance.foreground();
				g.setColor(plus ? appearance.secondary() : ink);
				if(plus){
					int cx = b.x+b.width/2, cy = b.y+b.height/2;
					g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g.drawLine(cx-5, cy, cx+5, cy); g.drawLine(cx, cy-5, cx, cy+5);
					continue;
				}
				Font font = on ? getFont().deriveFont(Font.BOLD) : getFont();
				g.setFont(font); fm = g.getFontMetrics(font);
				String s = fit(name(tabs.get(i)), fm, (vertical ? b.height : b.width)-10);
				int tw = fm.stringWidth(s), base0 = (THICK+fm.getAscent()-fm.getDescent())/2;
				if(vertical){
					Graphics2D t = (Graphics2D)g.create();
					t.translate(b.x, b.y+b.height); t.rotate(-Math.PI/2); // the name reads upwards
					t.drawString(s, (b.height-tw)/2, base0);
					t.dispose();
				}else g.drawString(s, b.x+(b.width-tw)/2, b.y+base0);
			}
			if(dropAt>=0){
				Rectangle b = dropAt<tabs.size() ? r.get(dropAt) : r.get(tabs.size());
				g.setColor(appearance.accent());
				if(vertical) g.fillRoundRect(b.x, b.y-GAP/2-2, b.width, 3, 3, 3); else g.fillRoundRect(b.x-GAP/2-2, b.y, 3, b.height, 3, 3);
			}
			g.dispose();
		}
		private double luminance(Color c){ return (0.299*c.getRed()+0.587*c.getGreen()+0.114*c.getBlue())/255; }
		private String fit(String s, FontMetrics fm, int room){
			if(fm.stringWidth(s)<=room) return s;
			int end = s.length();
			while(end>0 && fm.stringWidth(s.substring(0, end)+"…")>room) end = s.offsetByCodePoints(end, -1);
			if(end>0) return s.substring(0, end)+"…";
			String first = s.substring(0, s.offsetByCodePoints(0, 1)); // a narrow tab still shows how its name begins
			return fm.stringWidth(first)<=room ? first : "";
		}
		/** A tab's menu (on a tab), or just New Tab (on "+" or past the tabs). */
		private void showMenu(java.awt.Point p){
			int t = tabAt(p);
			JPopupMenu menu = t>=0 && t!=PLUS_TAB ? tabMenu(t) : new JPopupMenu();
			if(menu.getComponentCount()==0){ JMenuItem mi = new JMenuItem(text("New Tab")); mi.addActionListener(e -> selectDockTab(addTab())); menu.add(mi); }
			menu.show(this, p.x, p.y);
		}
	}

	/** The dock's colour: its selected tab's (null: none, or the tabs are off). */
	Color dockTint(){
		LauncherStore store = LauncherStore.get();
		if(!appearance.dockTabs) return null;
		List<LauncherStore.DockTab> tabs = store.dockTabs();
		int shown = store.shownDockTab();
		Integer rgb = shown<tabs.size() ? tabs.get(shown).color : null;
		return rgb==null ? null : new Color(rgb);
	}
	/** After a tab is chosen, added, moved, renamed or coloured: the tabs and the dock (its colour is the selected tab's). */
	private void tabsChanged(){ tabStrip.repaint(); if(dockContainer!=null) dockContainer.repaint(); }
	/** Turns the dock's tabs on or off (the appearance page; tests): off, the dock shows the first tab. */
	void setDockTabs(boolean on){ appearance.dockTabs = on; arrangeDock(); }
	/** Shows a tab of the dock (its items; the running programs and the open stack come first in every tab). */
	void selectDockTab(int tab){
		LauncherStore store = LauncherStore.get();
		if(tab==store.selectedDockTab() && store.dockTabsShown()) return;
		tabStrip.dragHover(null);
		store.selectDockTab(tab);
		dockQuiet = true;
		try{ refreshDock(); }finally{ dockQuiet = false; }
		dockScroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
		tabsChanged();
	}
	/** Adds a tab after the last ("Tab 2", "Tab 3"…: a number no tab's name uses yet) and returns its index. */
	int addTab(){
		List<LauncherStore.DockTab> tabs = LauncherStore.get().dockTabs();
		Set<String> names = new HashSet<String>(); for(LauncherStore.DockTab t: tabs) if(t.name!=null) names.add(t.name);
		int n = tabs.size()+1; while(names.contains(text("Tab {0}").replace("{0}", Integer.toString(n)))) n++;
		int index = LauncherStore.get().addDockTab(text("Tab {0}").replace("{0}", Integer.toString(n)));
		tabsChanged();
		return index;
	}
	/** Moves a tab to an insertion place (how many tabs come before it, counted with it still in its old place). */
	private void moveTab(int from, int insertion){
		int to = insertion>from ? insertion-1 : insertion;
		if(to==from) return;
		LauncherStore.get().moveDockTab(from, to);
		refreshDock(); tabsChanged();
	}
	private void renameTab(int tab){
		List<LauncherStore.DockTab> tabs = LauncherStore.get().dockTabs();
		if(tab<0 || tab>=tabs.size()) return;
		String old = tabs.get(tab).name!=null ? tabs.get(tab).name : text("Dock");
		Object name = JOptionPane.showInputDialog(this, text("Tab name:"), text("Rename Tab"), JOptionPane.PLAIN_MESSAGE, null, null, old);
		if(name==null || name.toString().trim().isEmpty()) return;
		LauncherStore.get().renameDockTab(tab, name.toString());
		tabsChanged();
	}
	private void deleteTab(int tab){
		List<LauncherStore.DockTab> tabs = LauncherStore.get().dockTabs();
		if(tabs.size()<=1 || tab<0 || tab>=tabs.size()) return;
		LauncherStore.DockTab t = tabs.get(tab);
		if(!t.items.isEmpty()){
			String name = t.name!=null ? t.name : text("Dock");
			int answer = JOptionPane.showConfirmDialog(this, text("Delete the tab \"{0}\" and the {1} items in it?").replace("{0}", name).replace("{1}", Integer.toString(t.items.size())),
				text("Delete Tab"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if(answer!=JOptionPane.OK_OPTION) return;
		}
		LauncherStore.get().removeDockTab(tab);
		dockQuiet = true;
		try{ refreshDock(); }finally{ dockQuiet = false; }
		tabsChanged();
	}
	/**
	 * "Add to Dock" while the dock has tabs: a submenu of the tabs, each checked when the file is in it (choosing a
	 * checked tab takes the file out of it), then New Tab, which makes a tab holding the file.
	 */
	JMenu dockTabsMenu(final File f){
		JMenu menu = new JMenu(text("Add to Dock"));
		List<LauncherStore.DockTab> tabs = LauncherStore.get().dockTabs();
		for(int i=0; i<tabs.size(); i++){
			final int tab = i;
			final boolean in = LauncherStore.get().isInDockTab(f, tab);
			javax.swing.JCheckBoxMenuItem mi = new javax.swing.JCheckBoxMenuItem(tabName(tab), in);
			if(tabs.get(i).color!=null) mi.setIcon(swatch(new Color(tabs.get(i).color), false));
			mi.addActionListener(e -> {
				if(in) LauncherStore.get().removeFromDockTab(f, tab); else LauncherStore.get().addToDockTab(f, tab);
				refreshDock(); tabsChanged();
			});
			menu.add(mi);
		}
		menu.addSeparator();
		JMenuItem mi = new JMenuItem(text("New Tab"));
		mi.addActionListener(e -> dropOnTab(f, -1, DockTabStrip.PLUS_TAB));
		menu.add(mi);
		return menu;
	}
	/** A tab's context menu: rename, colour, a new tab, delete (not the last tab). */
	JPopupMenu tabMenu(final int tab){
		JPopupMenu menu = new JPopupMenu();
		JMenuItem mi;
		menu.add(mi = new JMenuItem(text("Rename Tab…")));
		mi.addActionListener(e -> renameTab(tab));
		JMenu colors = new JMenu(text("Tab Color"));
		Integer current = LauncherStore.get().dockTabs().get(tab).color;
		colors.add(mi = new javax.swing.JCheckBoxMenuItem(text("None"), current==null));
		mi.addActionListener(e -> { LauncherStore.get().setDockTabColor(tab, null); tabsChanged(); });
		colors.addSeparator();
		for(int i=0; i<TAB_COLORS.length; i++){
			final int rgb = TAB_COLORS[i];
			colors.add(mi = new javax.swing.JCheckBoxMenuItem(text(TAB_COLOR_NAMES[i]), current!=null && current==rgb));
			mi.setIcon(swatch(new Color(rgb), false));
			mi.addActionListener(e -> { LauncherStore.get().setDockTabColor(tab, rgb); tabsChanged(); });
		}
		colors.addSeparator();
		colors.add(mi = new JMenuItem(text("Other…")));
		mi.addActionListener(e -> {
			Integer was = LauncherStore.get().dockTabs().get(tab).color;
			Color c = OklabColorChooserPanel.showDialog(this, text("Tab Color"), was!=null ? new Color(was) : appearance.accent());
			if(c!=null){ LauncherStore.get().setDockTabColor(tab, c.getRGB()); tabsChanged(); }
		});
		menu.add(colors);
		menu.add(mi = new JMenuItem(text("New Tab")));
		mi.addActionListener(e -> selectDockTab(addTab()));
		menu.addSeparator();
		menu.add(mi = new JMenuItem(text("Delete Tab")));
		mi.setEnabled(LauncherStore.get().dockTabs().size()>1);
		mi.addActionListener(e -> deleteTab(tab));
		return menu;
	}
	/** A point on the screen in the tab strip's coordinates, or null when the strip is not there. */
	private java.awt.Point tabStripPoint(java.awt.Point screen){
		if(!appearance.dockTabs || !tabStrip.isShowing() || screen==null) return null;
		java.awt.Point p = new java.awt.Point(screen);
		SwingUtilities.convertPointFromScreen(p, tabStrip);
		return tabStrip.contains(p) ? p : null;
	}
	/** Puts a file into a tab dropped on (a dock button from tab {@code from}, -1: none; a history row); "+" makes a new tab for it. */
	private void dropOnTab(File f, int from, int tab){
		if(tab==DockTabStrip.PLUS_TAB) tab = addTab();
		if(tab<0 || tab==from) return;
		LauncherStore.get().moveToDockTab(f, from, tab, Integer.MAX_VALUE);
		setStatus(text("Put \"{0}\" in the tab \"{1}\".").replace("{0}", LauncherStore.displayName(f)).replace("{1}", tabName(tab)), false);
		refreshDock(); tabsChanged();
	}
	private String tabName(int tab){ List<LauncherStore.DockTab> t = LauncherStore.get().dockTabs(); return tab<t.size() && t.get(tab).name!=null ? t.get(tab).name : text("Dock"); }

	/** A dock icon that grows in (from nothing, fading in) while its item is in {@link #appearing}, shrinks away while it is in {@link #departing}; the plain icon otherwise. */
	private final class AppearIcon implements Icon {
		private final Icon icon; private final String id;
		AppearIcon(Icon icon, String id){ this.icon = icon; this.id = id; }
		public int getIconWidth(){ return icon.getIconWidth(); }
		public int getIconHeight(){ return icon.getIconHeight(); }
		public void paintIcon(Component c, Graphics g, int x, int y){
			Long start = appearing.get(id); boolean leaving = false;
			if(start==null){ start = departing.get(id); leaving = start!=null; }
			if(start==null || !dockAnimation.isRunning()){ icon.paintIcon(c, g, x, y); return; }
			float t = Math.min(1f, (System.currentTimeMillis()-start)/(float)APPEAR_MS);
			float eased = leaving ? 1f-t*t : 1f-(1f-t)*(1f-t); // in: fast then settling; away: gathering pace
			if(eased<=0f) return;
			Graphics2D g2 = (Graphics2D)g.create();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, eased));
			double cx = x+getIconWidth()/2.0, cy = y+getIconHeight()/2.0;
			g2.translate(cx, cy); g2.scale(eased, eased); g2.translate(-cx, -cy);
			icon.paintIcon(c, g2, x, y);
			g2.dispose();
		}
	}

	/**
	 * The zoom an icon makes when its file opens or closes. Opening: the icon grows out of its place to {@link #SCALE}
	 * times its size while it fades, and the file opens meanwhile (a stack is read on the script thread and a program
	 * on its own, so the event thread keeps painting). Closing: the icon comes back the same way in reverse, settling
	 * on its place. It is painted on the frame's layered pane over the icon's component (so it may spill past its edges,
	 * and it outlives the dock rebuild the opened program triggers). -Dhz.launcher.animate=false leaves the zoom out
	 * (tests).
	 */
	private static final class DockZoom extends JComponent {
		private static final long serialVersionUID = 1L;
		private static final int DURATION = 280;
		private static final float SCALE = 2.2f;
		private final Icon icon; private final Rectangle iconBounds; private final boolean growing;
		private final long started = System.currentTimeMillis();
		private float scale = 1f, alpha = 1f;
		private final javax.swing.Timer timer;

		/** Where a file's icon is shown: the component and the icon's rectangle in it (see {@link #zoomSpot(File)}). */
		static final class Spot { final JComponent in; final Icon icon; final Rectangle bounds; Spot(JComponent in, Icon icon, Rectangle bounds){ this.in=in; this.icon=icon; this.bounds=bounds; } }

		/** The icon grows out of its place while {@code then} (the opening) runs: the first frame is painted, then it is run. */
		static void grow(Spot spot, final Runnable then){
			if(start(spot, true)) SwingUtilities.invokeLater(then); else then.run();
		}
		/** The icon comes back to its place. */
		static void shrink(Spot spot){ start(spot, false); }
		private static boolean start(Spot spot, boolean growing){
			if(spot==null || "false".equals(System.getProperty("hz.launcher.animate"))) return false;
			JFrame frame = (JFrame)SwingUtilities.getWindowAncestor(spot.in);
			if(frame==null || !spot.in.isShowing()) return false;
			Rectangle onPane = SwingUtilities.convertRectangle(spot.in, spot.bounds, frame.getLayeredPane());
			DockZoom zoom = new DockZoom(frame, spot.icon, onPane, growing);
			zoom.paintImmediately(0, 0, zoom.getWidth(), zoom.getHeight());
			return true;
		}
		private DockZoom(JFrame frame, Icon icon, Rectangle iconBounds, boolean growing){
			this.icon = icon; this.iconBounds = iconBounds; this.growing = growing;
			setOpaque(false);
			int pad = (int)Math.ceil(Math.max(iconBounds.width, iconBounds.height)*(SCALE-1f)/2)+2;
			setBounds(iconBounds.x-pad, iconBounds.y-pad, iconBounds.width+pad*2, iconBounds.height+pad*2);
			frame.getLayeredPane().add(this, JLayeredPane.POPUP_LAYER);
			if(!growing){ scale = SCALE; alpha = 0f; }
			timer = new javax.swing.Timer(16, new ActionListener(){ public void actionPerformed(ActionEvent e){ tick(); }});
			timer.start();
		}
		private void tick(){
			long elapsed = System.currentTimeMillis()-started;
			if(elapsed>=DURATION){ finish(); return; }
			float t = (float)elapsed/DURATION;
			// ease out: the growing icon leaps, the returning one lands softly
			float eased = 1f-(1f-t)*(1f-t);
			float progress = growing ? eased : 1f-eased;
			scale = 1f+(SCALE-1f)*progress;
			alpha = 1f-progress;
			repaint();
		}
		private void finish(){
			timer.stop();
			java.awt.Container parent = getParent();
			if(parent!=null){ parent.remove(this); parent.repaint(getX(), getY(), getWidth(), getHeight()); }
		}
		@Override protected void paintComponent(Graphics g){
			if(alpha<=0f) return;
			Graphics2D g2 = (Graphics2D)g.create();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, alpha)));
			double cx = iconBounds.x-getX()+iconBounds.width/2.0, cy = iconBounds.y-getY()+iconBounds.height/2.0;
			g2.translate(cx, cy); g2.scale(scale, scale); g2.translate(-iconBounds.width/2.0, -iconBounds.height/2.0);
			icon.paintIcon(this, g2, 0, 0);
			g2.dispose();
		}
	}

	// ------------------------------------------------------------------ the history's rows

	private final class ItemRenderer extends JPanel implements ListCellRenderer<LauncherStore.Item> {
		private static final long serialVersionUID = 1L;
		private boolean rowSelected, dropTarget;
		@Override protected void paintComponent(Graphics graphics){
			Graphics2D g = LauncherAppearance.smooth(graphics);
			if(rowSelected){
				g.setColor(appearance.selection());
				g.fillRoundRect(0, 2, getWidth(), getHeight()-4, 14, 14);
			}
			if(dropTarget){ // a file drag hovers over this program's row
				g.setColor(appearance.accent());
				g.setStroke(new BasicStroke(2f));
				g.drawRoundRect(1, 3, getWidth()-3, getHeight()-7, 14, 14);
			}
			g.dispose();
		}
		private final JLabel iconLabel = new JLabel();
		private final JLabel nameLabel = new JLabel();
		private final JLabel detailLabel = new JLabel();
		private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
		ItemRenderer(){
			setLayout(new BorderLayout(14, 0));
			setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
			add(iconLabel, BorderLayout.WEST);
			JPanel lines = new JPanel(new java.awt.GridLayout(2, 1));
			lines.setOpaque(false);
			nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
			detailLabel.setFont(detailLabel.getFont().deriveFont(detailLabel.getFont().getSize2D()-1f));
			lines.add(nameLabel); lines.add(detailLabel);
			add(lines, BorderLayout.CENTER);
		}
		public Component getListCellRendererComponent(JList<? extends LauncherStore.Item> l, LauncherStore.Item item, int index, boolean selected, boolean focus){
			boolean exists = item.exists();
			iconLabel.setIcon(exists ? icon(item, ICON) : new ImageIcon(item.isApp() ? appIcon() : item.isDocument() ? documentIcon() : stackIcon()));
			iconLabel.setEnabled(exists);
			nameLabel.setText(item.name());
			StringBuilder detail = new StringBuilder(text(item.isApp() ? "Mac program" : item.isDocument() ? "Document" : "Stack"));
			if(item.isDocument()) detail.append(" · ").append(text("opened with {0}").replace("{0}", LauncherStore.displayName(item.appFile())));
			if(duplicateNames.contains(item.name())){ File p = item.file().getParentFile(); if(p!=null) detail.append(" · ").append(shortFolder(p)); }
			if(item.lastUsed>0) detail.append(" · ").append(dateFormat.format(new Date(item.lastUsed)));
			if(!exists) detail.append(" · ").append(text("Not found"));
			MacApp68k running = exists ? MacApp68k.runningFrom(item.file()) : null;
			if(running!=null) detail.append(" · ").append(text("running"));
			detailLabel.setText(detail.toString());
			Color fg = selected ? l.getSelectionForeground() : l.getForeground();
			nameLabel.setForeground(exists ? fg : appearance.secondary());
			detailLabel.setForeground(!exists ? appearance.error() : appearance.secondary());
			setBackground(selected ? l.getSelectionBackground() : l.getBackground());
			rowSelected = selected;
			dropTarget = index==listDropRow;
			setOpaque(false);
			return this;
		}
	}
	/** The last two folders of a path, enough to tell same-named entries apart; the tooltip has it all. */
	private static String shortFolder(File folder){
		File parent = folder.getParentFile();
		String s = LauncherStore.composed(folder.getName());
		if(parent!=null && !parent.getName().isEmpty()) s = LauncherStore.composed(parent.getName())+File.separator+s;
		return (parent==null || parent.getParentFile()==null ? "" : "…"+File.separator)+s;
	}
	private final Set<String> duplicateNames = new HashSet<String>();
	{ // kept in step with the model: names listed more than once show their folder
		model.addListDataListener(new javax.swing.event.ListDataListener(){
			public void intervalAdded(javax.swing.event.ListDataEvent e){ recount(); }
			public void intervalRemoved(javax.swing.event.ListDataEvent e){ recount(); }
			public void contentsChanged(javax.swing.event.ListDataEvent e){ recount(); }
			private void recount(){
				duplicateNames.clear(); Set<String> seen = new HashSet<String>();
				for(int i=0; i<model.size(); i++){ String n = model.get(i).name(); if(!seen.add(n)) duplicateNames.add(n); }
			}
		});
	}

	// ------------------------------------------------------------------ icons

	/** A history row's icon: a document's comes from the program that opened it (see {@link #documentImage}). */
	private Icon icon(LauncherStore.Item item, int size){
		if(!item.isDocument()) return icon(item.file(), size);
		String key = size+":doc:"+item.path+"@"+item.app;
		Icon cached = iconCache.get(key);
		if(cached!=null) return cached;
		BufferedImage image = documentImage(item.file(), item.appFile(), size);
		Icon icon = new ImageIcon(scaleIcon(image==null ? documentIcon() : image, size));
		iconCache.put(key, icon);
		return icon;
	}
	/**
	 * The icon the Finder would show for a document: its own custom icon when it has one, else the icon family that a
	 * program's bundle (BNDL/FREF) gives the document's file type — the program that opened it first, then any known
	 * program whose signature is the document's creator; a program's "any file" icon ('****') only when none claims the
	 * type itself. Null when nothing has one.
	 */
	private BufferedImage documentImage(File doc, File app, int size){
		if(doc==null || !doc.isFile()) return null;
		BufferedImage image = null;
		try{ image = FinderIcons68k.ofCustom(doc, size); }catch(RuntimeException ignored){}
		if(image!=null) return image;
		byte[] info = FinderInfo68k.read(doc);
		int type = info==null ? 0 : MacApp68k.u4(info, 0), creator = info==null ? 0 : MacApp68k.u4(info, 4);
		if(type==0) return null;
		int[] types = PictureFile68k.isHostImageType(type) ? new int[]{type, PictureFile68k.PICT} : new int[]{type}; // programs see a host picture as a PICT
		List<File> candidates = new ArrayList<File>();
		if(app!=null && app.isFile()) candidates.add(app);
		if(creator!=0 && creator!=0x3F3F3F3F){ // '????': no creator to look for
			LauncherStore store = LauncherStore.get();
			for(LauncherStore.Item it: store.recentItems()) if(it.isApp() && it.exists() && !candidates.contains(it.file()) && FinderIcons68k.bundleOf(it.file()).signature==creator) candidates.add(it.file());
			for(File f: store.allDockItems()) if(f.isFile() && !candidates.contains(f) && MacApp68k.isApplication(f) && FinderIcons68k.bundleOf(f).signature==creator) candidates.add(f);
		}
		for(File candidate: candidates) for(int t: types){
			try{ image = FinderIcons68k.ofDocument(candidate, t, size); }catch(RuntimeException ignored){}
			if(image!=null) return image;
		}
		for(File candidate: candidates){
			try{ image = FinderIcons68k.ofDocument(candidate, FinderIcons68k.ANY_TYPE, size); }catch(RuntimeException ignored){}
			if(image!=null) return image;
		}
		return null;
	}
	private Icon icon(File f, int size){
		String key = size+":"+f.getPath();
		Icon cached = iconCache.get(key);
		if(cached!=null) return cached;
		BufferedImage image = null;
		boolean app = f.isFile() && MacApp68k.isApplication(f);
		// a program's own icon is what its bundle gives 'APPL' (the Finder's choice); a bare custom icon or ICN# 128 stands in
		if(app) try{ image = FinderIcons68k.ofDocument(f, FinderIcons68k.APPL, size); }catch(RuntimeException ignored){}
		// a control panel's icon is what its own bundle gives its file type 'cdev' (Kaleidoscope's ICN#/icl8 -1000)
		if(!app && image==null && MacApp68k.isControlPanel(f)) try{ image = FinderIcons68k.ofDocument(f, FinderIcons68k.CDEV, size); }catch(RuntimeException ignored){}
		try{ if(image==null && f.exists()) image = FinderIcons68k.of(f); }catch(RuntimeException ignored){}
		// a stack file's icons, read from the file - or from its working folder while it is open (a stack just converted
		// from HyperCard is listed before the autosave writes its .hzb file)
		File bundle = !app && (StackBundle.isPacked(f) || StackBundle.folderOf(f)!=null) ? StackBundle.iconFolder(f) : f;
		if(image==null && !app && bundle!=null && bundle.isDirectory()){
			File custom = new File(bundle, "icon.png");
			if(custom.isFile()) try{ image = ImageIO.read(custom); }catch(IOException ignored){}
			// a converted stack keeps its custom icon as raw icl8/ics8 (+ ICN#/ics#) files beside _stack.xml
			if(image==null) try{ image = FinderIcons68k.ofStackBundle(bundle, size); }catch(RuntimeException ignored){}
		}
		if(image==null) image = app ? appIcon() : stackIcon();
		Icon icon = new ImageIcon(scaleIcon(image, size));
		if(size>=24 && isHyperZebraStack(f)) icon = new HzBadgeIcon(icon);
		if(f.exists()) iconCache.put(key, icon); // not yet written: asked again once the file is there
		return icon;
	}
	/** A stack in HyperZebra's own format: a stack file (.hzb ZIP), or a folder (usually .hzb) holding _stack.xml or toc.xml. */
	static boolean isHyperZebraStack(File f){
		if(StackBundle.isPacked(f)) return true;
		if(!f.exists() && StackBundle.folderOf(f)!=null) return true; // open, before its .hzb file is first written
		return f.isDirectory() && (new File(f, "_stack.xml").isFile() || new File(f, "toc.xml").isFile());
	}
	/**
	 * A stack icon with a small "HZ" tag at its lower right, so HyperZebra stacks stand out from Mac programs and
	 * documents (whose icons they often resemble). Drawn at paint time so the letters stay sharp on HiDPI screens.
	 */
	private final class HzBadgeIcon implements Icon {
		private final Icon icon;
		HzBadgeIcon(Icon icon){ this.icon = icon; }
		public int getIconWidth(){ return icon.getIconWidth(); }
		public int getIconHeight(){ return icon.getIconHeight(); }
		public void paintIcon(Component c, Graphics g, int x, int y){
			icon.paintIcon(c, g, x, y);
			int size = getIconWidth();
			Graphics2D g2 = (Graphics2D)g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, Math.round(size*0.28f)));
			FontMetrics fm = g2.getFontMetrics(font);
			int padX = Math.max(2, size/16), h = fm.getAscent()+Math.max(2, size/16);
			int w = fm.stringWidth("HZ")+padX*2;
			int bx = x+size-w, by = y+size-h, arc = Math.max(4, h/2);
			g2.setColor(new Color(255, 255, 255, 220));
			g2.fillRoundRect(bx-1, by-1, w+2, h+2, arc+2, arc+2); // a light rim keeps it apart from dark icons
			g2.setColor(appearance.accent());
			g2.fillRoundRect(bx, by, w, h, arc, arc);
			g2.setColor(Color.WHITE);
			g2.setFont(font);
			g2.drawString("HZ", bx+padX, by+(h-fm.getAscent())/2+fm.getAscent()-fm.getDescent()/3);
			g2.dispose();
		}
	}
	private static Image scaleIcon(BufferedImage image, int size){
		Image scaled;
		if(image.getWidth()==size) scaled = image;
		else if(size%image.getWidth()==0) scaled = image.getScaledInstance(size, size, Image.SCALE_REPLICATE); // pixel icons at 2x/3x stay crisp
		else scaled = image.getScaledInstance(size, size, Image.SCALE_SMOOTH);
		return scaled;
	}
	private static BufferedImage hyperZebraIcon(){
		if(hyperZebraIcon==null){
			File iconFile = new File("."+File.separatorChar+"resource"+File.separatorChar+"icon.png");
			if(iconFile.isFile()) try{ hyperZebraIcon = ImageIO.read(iconFile); }catch(IOException ignored){}
		}
		return hyperZebraIcon;
	}
	/** A generic classic-program icon: a diamond with a hand-drawn "A". */
	private static BufferedImage appIcon(){
		if(appIconImage!=null) return appIconImage;
		BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int[] xs = {16, 30, 16, 2}, ys = {2, 16, 30, 16};
		g.setColor(new Color(0xE8E8E8)); g.fillPolygon(xs, ys, 4);
		g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1.5f)); g.drawPolygon(xs, ys, 4);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		g.drawString("A", 11, 21);
		g.dispose();
		return appIconImage = img;
	}
	/** A generic stack icon: three cards. */
	private static BufferedImage stackIcon(){
		if(stackIconImage!=null) return stackIconImage;
		BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for(int i=2; i>=0; i--){
			g.setColor(Color.WHITE); g.fillRoundRect(3+i*3, 9-i*3, 22, 16, 3, 3);
			g.setColor(Color.BLACK); g.drawRoundRect(3+i*3, 9-i*3, 22, 16, 3, 3);
		}
		g.setColor(Color.GRAY);
		g.drawLine(7, 14, 20, 14); g.drawLine(7, 18, 18, 18);
		g.dispose();
		return stackIconImage = img;
	}
	/** A generic document icon: a dog-eared page with a few lines of text. */
	private static BufferedImage documentIcon(){
		if(documentIconImage!=null) return documentIconImage;
		BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int[] xs = {6, 19, 26, 26, 6}, ys = {2, 2, 9, 30, 30};
		g.setColor(Color.WHITE); g.fillPolygon(xs, ys, 5);
		g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1.2f)); g.drawPolygon(xs, ys, 5);
		g.drawLine(19, 2, 19, 9); g.drawLine(19, 9, 26, 9);
		g.setColor(Color.GRAY);
		for(int y=14; y<=24; y+=4) g.drawLine(10, y, 22, y);
		g.dispose();
		return documentIconImage = img;
	}

	// ------------------------------------------------------------------ drag and drop

	/**
	 * Files dropped on the window (the dock included) are opened; a drop from elsewhere never registers in the dock -
	 * that is the context menu's "Add to Dock" - but a history row dragged onto the dock is registered at the place
	 * dropped on (the insertion marker shows it, as when a dock button is dragged). Dropped on a program - its dock button, or its row in the history - they are opened
	 * with that program instead (documents and stacks, the way the Finder opens a file dropped on an application;
	 * programs among them are still opened); a history row too, when the program opens it.
	 */
	private final class FileDrop extends DropTargetAdapter {
		/** The files under the drag (null while unknown: Java gives a drag's data only at its drop), and which programs take any of them. */
		private List<File> dragged; private final java.util.Map<File,Boolean> takes = new java.util.HashMap<File,Boolean>();
		@Override public void dragEnter(DropTargetDragEvent e){
			if(!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){ e.rejectDrag(); return; }
			e.acceptDrag(DnDConstants.ACTION_COPY);
			takes.clear(); dragged = null;
			// Java gives the data of a drag from another program only at the drop: macOS reads the system's drag pasteboard instead
			// (macOS answers null, other hosts may throw "No drop current")
			try{ @SuppressWarnings("unchecked") List<File> files = (List<File>)e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor); dragged = files; }catch(Exception ignored){}
			if(dragged==null || dragged.isEmpty()) dragged = nativeDraggedFiles();
			hover(e.getDropTargetContext().getComponent(), e.getLocation());
		}
		@Override public void dragOver(DropTargetDragEvent e){ if(e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) hover(e.getDropTargetContext().getComponent(), e.getLocation()); }
		@Override public void dragExit(java.awt.dnd.DropTargetEvent e){ hover(null, null); dragged = null; takes.clear(); }
		@Override public void drop(DropTargetDropEvent e){
			Component on = e.getDropTargetContext().getComponent();
			java.awt.Point at = e.getLocation();
			hover(null, null);
			try{
				if(!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){ e.rejectDrop(); return; }
				e.acceptDrop(DnDConstants.ACTION_COPY);
				@SuppressWarnings("unchecked")
				List<File> files = (List<File>)e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
				e.dropComplete(true);
				dropped(on, at, files);
			}catch(Exception ex){ e.dropComplete(false); ex.printStackTrace(); }
			finally{ dragged = null; takes.clear(); }
		}
		/** What a drop of these files at a point of the target does (also driven directly by tests). */
		void dropped(Component on, java.awt.Point at, List<File> files) throws IOException {
				if(files==null || files.isEmpty()) return;
				// a history row dropped on a tab goes into it ("+": a new tab)
				if(on==tabStrip && fromHistory(files)){ int tab = tabStrip.tabAt(at); if(tab!=-1){ setStatus("", false); dropOnTab(files.get(0), -1, tab); } return; }
				// dropped on a program: its documents open in it, the rest goes the usual way
				File app = programAt(on, at);
				// a history row dropped on the dock: registered there, not opened - unless the program it is dropped on opens it
				if(on==dockPanel && fromHistory(files) && (app==null || !isDocumentFor(app, files.get(0)))){ registerFromHistory(files.get(0), at); return; }
				if(app!=null){
					List<File> documents = new ArrayList<File>(), rest = new ArrayList<File>();
					for(File f: files){ if(f.exists() && isDocumentFor(app, f)) documents.add(f); else rest.add(f); }
					if(!documents.isEmpty()){ setStatus("", false); openDocuments(app, documents); }
					files = rest;
					if(files.isEmpty()) return;
				}
				setStatus("", false);
				PCARD.openFiles(files);
		}
		/** Marks the program under the drag (or clears the mark when there is none). */
		private void hover(Component on, java.awt.Point at){
			DockButton button = null; int row = -1;
			tabStrip.dragHover(on==tabStrip ? at : null); // resting on a tab opens it
			if(on==dockPanel && at!=null){ Component c = dockPanel.getComponentAt(at); if(c instanceof DockButton && ((DockButton)c).takesDocuments() && takesDragged(((DockButton)c).file)) button = (DockButton)c; }
			if(historyDrag!=null){ // a history row: over the dock, the place it will be registered at (over a program that opens it, that program)
				int index = on==dockPanel && at!=null && button==null ? dockInsertionIndex(at) : -1;
				if(index!=dockDropIndex){ dockDropIndex = index; dockPanel.repaint(); }
			}
			if(on==list && at!=null){ int i = list.locationToIndex(at); if(i>=0 && list.getCellBounds(i, i).contains(at) && isProgramRow(model.get(i)) && takesDragged(model.get(i).file())) row = i; }
			if(button!=dockDropButton){ DockButton was = dockDropButton; dockDropButton = button; if(was!=null) was.repaint(); if(button!=null) button.repaint(); }
			if(row!=listDropRow){ listDropRow = row; list.repaint(); }
		}
		/** The files of a drag still going on from the macOS drag pasteboard (NativeDragPasteboard, ffm/), or null (other hosts, no helper). */
		private List<File> nativeDraggedFiles(){
			if(!GMenu.isMacOSX()) return null;
			try{
				@SuppressWarnings("unchecked") List<String> paths = (List<String>)Class.forName("NativeDragPasteboard").getMethod("files").invoke(null);
				if(paths==null) return null;
				List<File> r = new ArrayList<File>(); for(String p: paths) r.add(new File(p)); return r;
			}catch(Throwable t){ return null; }
		}
		/** Whether the dropped files are the history row being dragged (a drop from inside the launcher). */
		private boolean fromHistory(List<File> files){
			return historyDrag!=null && files.size()==1 && java.util.Objects.equals(LauncherStore.identity(historyDrag), LauncherStore.identity(files.get(0)));
		}
		/** Whether the program would open any of the dragged files (the mark shows where a drop opens something). */
		private boolean takesDragged(File app){
			if(dragged==null) return takes.computeIfAbsent(app, a -> DocumentOpeners.opensDocuments(a)); // files not known yet: programs that open documents at all
			return takes.computeIfAbsent(app, a -> { for(File f: dragged) if(f.exists() && isDocumentFor(a, f)) return true; return false; });
		}
		/** The program a point on the dock or the history names, or null. */
		private File programAt(Component on, java.awt.Point at){
			if(on==dockPanel){ Component c = dockPanel.getComponentAt(at); return c instanceof DockButton && ((DockButton)c).takesDocuments() ? ((DockButton)c).file : null; }
			if(on==list){ int i = list.locationToIndex(at); return i>=0 && list.getCellBounds(i, i).contains(at) && isProgramRow(model.get(i)) ? model.get(i).file() : null; }
			return null;
		}
		private boolean isProgramRow(LauncherStore.Item item){ return item.isApp() && item.exists() && item.file().isFile(); }
		/**
		 * Whether a dropped file is something to open in the program: a file that is not itself a program and that the
		 * program opens (DocumentOpeners.opens: its type or creator, as the Finder decides) - or a program, when the
		 * program is a resource editor (its bundle claims any file, '****', and resource files, 'rsrc' or
		 * 'RSRC': ResEdit, Resorcerer), the way ResEdit opens an application dropped on it in the Finder instead of the
		 * Finder launching it. '****' alone does not count: iTunes and Script Editor claim it for the files they take
		 * and would be handed the program; nor does 'APPL': nearly every program has one for its own icon. Folders,
		 * fonts and programs nothing claims are registered or opened as before.
		 */
		private boolean isDocumentFor(File app, File f){
			if(!f.isFile() || f.equals(app) || InstalledFonts68k.isFontFile(f)) return false;
			if(!MacApp68k.isApplication(f)) return DocumentOpeners.opens(app, f);
			FinderIcons68k.Bundle bundle = FinderIcons68k.bundleOf(app);
			return bundle.claims(FinderIcons68k.ANY_TYPE) && (bundle.claims(ResourceFile68k.fourcc("rsrc")) || bundle.claims(ResourceFile68k.fourcc("RSRC")));
		}
	}

	private static final class FileTransferable implements Transferable {
		private final List<File> files;
		FileTransferable(List<File> files){ this.files = files; }
		public DataFlavor[] getTransferDataFlavors(){ return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
		public boolean isDataFlavorSupported(DataFlavor flavor){ return DataFlavor.javaFileListFlavor.equals(flavor); }
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException { if(!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor); return files; }
	}
}

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The launcher's Tools > "Running Programs Info…": the 68K programs running now, one row each - the host CPU their
 * emulation thread takes, the speed they run at, the guest memory in use, the screen they see (size and colours) and
 * whether they run in the 24-bit world - with the particulars of the selected one below (file, kind, run time,
 * instructions, the SIZE resource, free memory, magnification, thread state). Refreshed every second while it is on
 * show; "Bring to Front", "Quit" and "Force Quit…" act on the selected program. There is one such window.
 */
final class GAppInfoWindow extends JFrame {
	private static final long serialVersionUID = 1L;
	private static GAppInfoWindow instance;

	/** Shows the window (made once), in front. */
	static void showWindow(Window near){
		if(instance==null) instance = new GAppInfoWindow();
		if(!instance.isVisible()){
			if(near!=null && near.isShowing()) instance.setLocationRelativeTo(near); else instance.setLocationRelativeTo(null);
		}
		instance.refresh();
		instance.setVisible(true);
		instance.setState(JFrame.NORMAL);
		instance.toFront();
		instance.timer.start();
	}

	private static String text(String key){ return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getDialogText(key) : key; }

	/** What was measured for a program at the last refresh: the rates are the differences to the next one. */
	private static final class Sample { long wallNanos, cpuNanos = -1, instructions; double cpuPercent = -1, mips = -1; }

	/** One program as the table shows it (a snapshot, so that the rows do not change while Swing paints them). */
	private static final class Row {
		MacApp68k app; String name, cpu, speed, memory, screen, colors, mode;
		List<String[]> details = new ArrayList<String[]>();
	}

	private final List<Row> rows = new ArrayList<Row>();
	private final Map<MacApp68k,Sample> samples = new HashMap<MacApp68k,Sample>();
	private final ThreadMXBean threads;
	private final Timer timer = new Timer(1000, e -> refresh());
	private final JLabel empty = new JLabel(text("No Mac program is running."), SwingConstants.CENTER);
	private final JButton front = new JButton(text("Bring to Front")), quit = new JButton(text("Quit")), force = new JButton(text("Force Quit…"));

	private final String[] columns = {text("Program"), text("CPU"), text("Speed"), text("Memory"), text("Screen size"), text("Colors"), text("Addressing")};
	private final AbstractTableModel summary = new AbstractTableModel(){
		private static final long serialVersionUID = 1L;
		public int getRowCount(){ return rows.size(); }
		public int getColumnCount(){ return columns.length; }
		@Override public String getColumnName(int c){ return columns[c]; }
		public Object getValueAt(int r, int c){
			Row row = rows.get(r);
			switch(c){
				case 0: return row.name; case 1: return row.cpu; case 2: return row.speed; case 3: return row.memory;
				case 4: return row.screen; case 5: return row.colors; default: return row.mode;
			}
		}
	};
	private final JTable table = new JTable(summary);
	private List<String[]> shownDetails = new ArrayList<String[]>();
	private final AbstractTableModel detailModel = new AbstractTableModel(){
		private static final long serialVersionUID = 1L;
		public int getRowCount(){ return shownDetails.size(); }
		public int getColumnCount(){ return 2; }
		@Override public String getColumnName(int c){ return c==0 ? text("Item") : text("Value"); }
		public Object getValueAt(int r, int c){ return shownDetails.get(r)[c]; }
	};
	private final JTable detailTable = new JTable(detailModel);

	private GAppInfoWindow(){
		super(text("Running Programs"));
		ThreadMXBean bean = null;
		try{ bean = ManagementFactory.getThreadMXBean(); if(!bean.isThreadCpuTimeSupported()) bean = null; else if(!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true); }
		catch(Throwable t){ bean = null; } // a runtime without java.management: the CPU column says "-"
		threads = bean;
		setDefaultCloseOperation(HIDE_ON_CLOSE);
		if(PCARD.pc!=null && PCARD.pc.getIconImage()!=null) setIconImage(PCARD.pc.getIconImage());
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosed(WindowEvent e){ timer.stop(); }
			@Override public void windowIconified(WindowEvent e){ timer.stop(); }
			@Override public void windowDeiconified(WindowEvent e){ refresh(); timer.start(); }
		});
		addComponentListener(new java.awt.event.ComponentAdapter(){
			@Override public void componentHidden(java.awt.event.ComponentEvent e){ timer.stop(); samples.clear(); }
		});
		getRootPane().registerKeyboardAction(e -> setVisible(false), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().registerKeyboardAction(e -> setVisible(false), KeyStroke.getKeyStroke(KeyEvent.VK_W, GMenu.isMacOSX() ? java.awt.event.InputEvent.META_DOWN_MASK : java.awt.event.InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 22));
		table.getTableHeader().setReorderingAllowed(false);
		DefaultTableCellRenderer right = new DefaultTableCellRenderer();
		right.setHorizontalAlignment(SwingConstants.RIGHT);
		int[] widths = {200, 64, 90, 150, 110, 120, 80};
		for(int c=0; c<widths.length; c++){
			table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
			if(c==1 || c==2) table.getColumnModel().getColumn(c).setCellRenderer(right);
		}
		table.getSelectionModel().addListSelectionListener(e -> { if(!e.getValueIsAdjusting()) showDetails(); });
		detailTable.setFillsViewportHeight(true);
		detailTable.setRowHeight(Math.max(detailTable.getRowHeight(), 20));
		detailTable.getTableHeader().setReorderingAllowed(false);
		detailTable.getColumnModel().getColumn(0).setPreferredWidth(170);
		detailTable.getColumnModel().getColumn(1).setPreferredWidth(560);

		JPanel top = new JPanel(new BorderLayout());
		top.add(new JScrollPane(table), BorderLayout.CENTER);
		empty.setFont(empty.getFont().deriveFont(Font.ITALIC));
		empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		top.add(empty, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, new JScrollPane(detailTable));
		split.setResizeWeight(0.3);
		split.setBorder(BorderFactory.createEmptyBorder());

		front.addActionListener(e -> { MacApp68k app = selected(); if(app!=null) app.bringToFront(); });
		quit.addActionListener(e -> { MacApp68k app = selected(); if(app!=null) app.requestQuit(); });
		force.addActionListener(e -> { MacApp68k app = selected(); if(app!=null) confirmForceQuit(this, app); });
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(front); buttons.add(quit); buttons.add(force);
		JLabel note = new JLabel(text("CPU: the host time the program's emulation thread takes (100% = one core)."));
		note.setFont(note.getFont().deriveFont(note.getFont().getSize2D()-1f));
		JPanel south = new JPanel(new BorderLayout(8, 0));
		south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		south.add(note, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(split, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);
		setContentPane(content);
		setSize(new Dimension(900, 640));
	}

	/**
	 * The launcher's and this window's "Force Quit": asks first (unsaved changes are lost), then stops the program without
	 * its Quit handler ({@link MacApp68k#forceQuit}).
	 */
	/** Asks, then force quits the program; true when it was forced. */
	static boolean confirmForceQuit(Component parent, MacApp68k app){
		if(app==null || app.finished) return false;
		String message = text("Force \"%s\" to quit? Changes it has not saved are lost.").replace("%s", app.name);
		String[] options = {text("Force Quit"), text("Cancel")};
		int answer = JOptionPane.showOptionDialog(parent, message, text("Force Quit"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if(answer!=0) return false;
		app.forceQuit();
		return true;
	}

	private MacApp68k selected(){
		int i = table.getSelectedRow();
		return i>=0 && i<rows.size() ? rows.get(i).app : null;
	}

	/** Measures every running program again and redraws both tables, keeping the selected program selected. */
	private void refresh(){
		MacApp68k was = selected();
		List<MacApp68k> apps = new ArrayList<MacApp68k>();
		for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service) apps.add(app);
		samples.keySet().retainAll(apps);
		rows.clear();
		for(MacApp68k app: apps) rows.add(describe(app));
		summary.fireTableDataChanged();
		int select = 0;
		for(int i=0; i<rows.size(); i++) if(rows.get(i).app==was) select = i;
		if(!rows.isEmpty()) table.setRowSelectionInterval(select, select);
		empty.setVisible(rows.isEmpty());
		showDetails();
	}

	private void showDetails(){
		int i = table.getSelectedRow();
		shownDetails = i>=0 && i<rows.size() ? rows.get(i).details : new ArrayList<String[]>();
		detailModel.fireTableDataChanged();
		boolean any = i>=0 && i<rows.size();
		front.setEnabled(any); quit.setEnabled(any); force.setEnabled(any);
	}

	/** Measures one program. Guest memory is read while the program runs: a value may be a moment old, never wrong in kind. */
	private Row describe(MacApp68k app){
		Row row = new Row();
		row.app = app;
		row.name = app.name;
		long now = System.nanoTime();
		Sample last = samples.get(app), sample = new Sample();
		sample.wallNanos = now;
		M68kCpu cpu = app.cpu;
		sample.instructions = (cpu==null ? 0 : cpu.instructions)+(app.ppc==null ? 0 : app.ppc.cpu.instructions); // a PowerPC program: its PowerPC and 68K instructions
		Thread thread = app.thread;
		if(threads!=null && thread!=null) try{ sample.cpuNanos = threads.getThreadCpuTime(thread.getId()); }catch(Throwable ignored){}
		if(last!=null && now>last.wallNanos){
			double seconds = (now-last.wallNanos)/1e9;
			if(sample.cpuNanos>=0 && last.cpuNanos>=0) sample.cpuPercent = Math.max(0, (sample.cpuNanos-last.cpuNanos)/1e9/seconds*100);
			sample.mips = Math.max(0, (sample.instructions-last.instructions)/seconds/1e6);
		}else if(last!=null){ sample.cpuPercent = last.cpuPercent; sample.mips = last.mips; }
		samples.put(app, sample);
		row.cpu = sample.cpuPercent<0 ? "–" : String.format("%.1f%%", sample.cpuPercent);
		row.speed = sample.mips<0 ? "–" : String.format("%.1f MIPS", sample.mips);

		M68kMemory memory = app.memory;
		long used = -1, heap = -1;
		if(memory!=null){
			used = memory.allocatedBytes();
			heap = (long)memory.heapEnd()-memory.heapStart();
			row.memory = bytes(used)+" / "+bytes(heap);
		}else row.memory = "–";

		int[] frame = null;
		try{ if(app.api!=null) frame = app.api.quickDraw().mainDeviceFrame(); }catch(Exception ignored){}
		int depth = frame==null ? 0 : frame[2];
		int width = frame==null ? 0 : frame[5]-frame[3], height = frame==null ? 0 : frame[6]-frame[4];
		row.screen = frame==null ? "–" : width+"×"+height;
		row.colors = depth==0 ? "–" : colors(depth);
		row.mode = text(app.addressing24 ? "24-bit" : "32-bit");

		List<String[]> d = row.details;
		d.add(new String[]{text("Name"), app.name});
		d.add(new String[]{text("File"), app.file.getPath()});
		d.add(new String[]{text("Kind"), text(app.controlPanel!=null ? "Control panel" : app.hosted!=null ? "Desk accessory" : app.ppc!=null ? "PowerPC application" : "Application")});
		d.add(new String[]{text("Running for"), duration(System.currentTimeMillis()-app.startedAt)});
		d.add(new String[]{text("CPU usage"), row.cpu+(sample.cpuPercent<0 ? "" : "  ("+text("host time of the emulation thread; 100% = one core")+")")});
		d.add(new String[]{text("Instructions per second"), row.speed});
		d.add(new String[]{text("Instructions executed"), String.format("%,d", sample.instructions)});
		double mips = app.mips();
		String machine = GAppSettingsDialog.speedName(mips, app.powerPC);
		d.add(new String[]{text("Speed limit"), mips<=0 ? text("Unlimited") : LauncherStore.mipsText(mips)+" MIPS"+(machine!=null ? " ("+machine+")" : "")});
		if(memory!=null){
			d.add(new String[]{text("Memory in use"), bytes(used)+" ("+text("heap")+" "+bytes(heap)+", "+percent(used, heap)+")"});
			d.add(new String[]{text("Free memory"), bytes(memory.availableBytes())+" ("+text("largest block")+" "+bytes(memory.largestFreeBlock())+")"});
			d.add(new String[]{text("Emulated memory"), bytes(memory.size())});
		}
		d.add(new String[]{text("SIZE resource"), app.hasSizeResource()
			? text("preferred")+" "+bytes(app.preferredHeapSize())+", "+sizeFlags(app.sizeFlags())
			: text("none")});
		d.add(new String[]{text("Addressing"), app.addressing24 ? text("24-bit (16 MB world, the top address byte ignored)") : text("32-bit")});
		if(frame!=null){
			d.add(new String[]{text("Screen size"), width+" × "+height+(app.screenSizeOverride!=null ? "  ("+text("chosen for this program")+")" : "")});
			d.add(new String[]{text("Colors"), colors(depth)+"  ("+depth+" "+text("bits per pixel")+")"+(app.depthOverride!=null ? "  ("+text("chosen for this program")+")" : "")});
		}
		MacAppHost host = app.host;
		if(host!=null) d.add(new String[]{text("Magnification"), (host.scPct%100==0 ? host.scPct/100+"×" : host.scPct+"%")});
		d.add(new String[]{text("Takes documents while running"), text(app.acceptsDocumentsWhileRunning() ? "Yes" : "No")});
		if(thread!=null) d.add(new String[]{text("Thread"), thread.getName()+" - "+thread.getState()});
		return row;
	}

	/** The table as it reads now, one row per program: the summary cells, then "item=value" for each particular (tests; EDT). */
	static List<List<String>> contentsForTest(){
		List<List<String>> all = new ArrayList<List<String>>();
		if(instance==null) return all;
		instance.refresh();
		for(Row row: instance.rows){
			List<String> cells = new ArrayList<String>(java.util.Arrays.asList(row.name, row.cpu, row.speed, row.memory, row.screen, row.colors, row.mode));
			for(String[] d: row.details) cells.add(d[0]+"="+d[1]);
			all.add(cells);
		}
		return all;
	}

	/** The colours a pixel depth gives, as the Monitors control panel named them. */
	private static String colors(int depth){
		switch(depth){
			case 1: return text("Black & White");
			case 2: return text("4 colors");
			case 4: return text("16 colors");
			case 8: return text("256 colors");
			case 16: return text("Thousands of colors");
			case 24: case 32: return text("Millions of colors");
			default: return depth+" "+text("bits per pixel");
		}
	}
	private static String sizeFlags(int flags){
		List<String> on = new ArrayList<String>();
		if((flags&0x0080)!=0) on.add(text("32-bit compatible"));
		if((flags&0x0040)!=0) on.add(text("high-level event aware"));
		if((flags&0x1000)!=0) on.add(text("can background"));
		if((flags&0x4000)!=0) on.add(text("suspend/resume events"));
		return on.isEmpty() ? String.format("$%04X", flags) : String.join(", ", on)+String.format(" ($%04X)", flags);
	}
	static String bytes(long n){
		if(n<0) return "–";
		if(n<1024) return n+" B";
		if(n<1024*1024) return String.format("%.1f KB", n/1024.0);
		if(n<1024L*1024*1024) return String.format("%.1f MB", n/1048576.0);
		return String.format("%.2f GB", n/1073741824.0);
	}
	private static String percent(long part, long whole){ return whole<=0 ? "–" : String.format("%.0f%%", part*100.0/whole); }
	private static String duration(long ms){
		long s = Math.max(0, ms/1000);
		return s>=3600 ? String.format("%d:%02d:%02d", s/3600, s/60%60, s%60) : String.format("%d:%02d", s/60, s%60);
	}
}

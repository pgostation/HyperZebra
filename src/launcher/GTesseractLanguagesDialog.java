import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

/**
 * The languages Tesseract reads the text in pictures in (GTranslationForm's Languages…): ticked ones are there - the
 * tesseract's own (cannot be removed here) or in HyperZebra's state folder (TesseractInstall). Ticking one downloads it
 * from tessdata_fast, unticking removes it, Add Files… copies traineddata files the user has.
 */
final class GTesseractLanguagesDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	private static final class Row {
		final String code, name;
		final boolean own;
		boolean added, wanted;
		Row(String code, boolean own, boolean added) { this.code = code; this.name = TesseractInstall.languageName(code); this.own = own; this.added = added; this.wanted = own || added; }
	}

	private final List<Row> rows = new ArrayList<Row>();
	private final JLabel status = new JLabel(" ");
	private final JButton ok = new JButton(International.t("OK")), cancel = new JButton(International.t("Cancel")), addFiles = new JButton(International.t("Add Files…"));
	private volatile boolean cancelled, running;
	private final Runnable changed;
	private final Model model = new Model();

	/** @param changed runs on the event thread after languages were added or removed */
	GTesseractLanguagesDialog(Window owner, Runnable changed) {
		super(owner, International.t("Languages for Text in Pictures"), ModalityType.APPLICATION_MODAL);
		this.changed = changed;
		load();
		JTable table = new JTable(model);
		table.getColumnModel().getColumn(0).setMaxWidth(36);
		table.getColumnModel().getColumn(2).setPreferredWidth(110);
		table.getColumnModel().getColumn(2).setMaxWidth(160);
		table.setRowHeight(Math.max(table.getRowHeight(), 20));
		JScrollPane scroll = new JScrollPane(table);
		scroll.setPreferredSize(new java.awt.Dimension(440, 360));

		JLabel intro = new JLabel(GPreferencesDialog.wrap(International.t("Tick the languages of the pictures to read. They are downloaded from Tesseract's tessdata_fast models (Apache License 2.0) into HyperZebra's state folder.")));
		intro.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
		status.setBorder(BorderFactory.createEmptyBorder(4, 12, 0, 12));
		status.setForeground(Color.GRAY);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(addFiles); buttons.add(cancel); buttons.add(ok);
		JPanel south = new JPanel(new BorderLayout());
		south.add(status, BorderLayout.NORTH);
		south.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout());
		content.add(intro, BorderLayout.NORTH);
		JPanel middle = new JPanel(new BorderLayout());
		middle.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		middle.add(scroll);
		content.add(middle, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);
		setContentPane(content);
		getRootPane().setDefaultButton(ok);

		ok.addActionListener(e -> apply());
		cancel.addActionListener(e -> { if(running) cancelled = true; else dispose(); });
		addFiles.addActionListener(e -> addFiles());
		pack();
		setLocationRelativeTo(owner);
	}

	private void load() {
		rows.clear();
		Set<String> own = PictureOcr.ownLanguages(), added = TesseractInstall.added();
		Set<String> codes = new TreeSet<String>(TesseractInstall.availableCodes());
		codes.addAll(own); codes.addAll(added);
		codes.remove("osd"); codes.remove("equ");
		codes.removeIf(c -> c.contains("/")); // Tesseract's script models (script/Latin…): pictures are read by language
		for(String c : codes) rows.add(new Row(c, own.contains(c), added.contains(c)));
		// those there first, then by name
		java.text.Collator byName = java.text.Collator.getInstance(java.util.Locale.forLanguageTag(International.language()));
		Collections.sort(rows, (a, b) -> a.wanted != b.wanted ? (a.wanted ? -1 : 1) : byName.compare(a.name, b.name));
	}

	private final class Model extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		@Override public int getRowCount() { return rows.size(); }
		@Override public int getColumnCount() { return 3; }
		@Override public String getColumnName(int c) { return c==0 ? "" : c==1 ? International.t("Language (code)") : International.t("Where"); }
		@Override public Class<?> getColumnClass(int c) { return c==0 ? Boolean.class : String.class; }
		@Override public boolean isCellEditable(int r, int c) { return c==0 && !rows.get(r).own && !running; }
		@Override public Object getValueAt(int r, int c) {
			Row row = rows.get(r);
			if(c==0) return row.wanted;
			if(c==1) return row.name+" ("+row.code+")";
			if(row.own) return International.t("Tesseract's own");
			if(row.added) return International.t("Added");
			int kb = TesseractInstall.sizeKB(row.code);
			return row.wanted ? International.t("Download")+(kb>0 ? " "+(kb+512)/1024+" MB" : "") : kb>0 ? (kb<1024 ? "<1 MB" : (kb+512)/1024+" MB") : "";
		}
		@Override public void setValueAt(Object v, int r, int c) { if(c==0){ rows.get(r).wanted = Boolean.TRUE.equals(v); fireTableRowsUpdated(r, r); } }
	}

	private void addFiles() {
		JFileChooser fc = new JFileChooser();
		fc.setMultiSelectionEnabled(true);
		fc.setFileFilter(new FileNameExtensionFilter(International.t("Tesseract language files (.traineddata)"), "traineddata"));
		if(fc.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION) return;
		File[] files = fc.getSelectedFiles();
		String problem = TesseractInstall.addFiles(files);
		if(problem!=null) JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
		load();
		model.fireTableDataChanged();
		if(changed!=null) changed.run();
	}

	private void apply() {
		List<String> download = new ArrayList<String>(), remove = new ArrayList<String>();
		for(Row r : rows){
			if(r.own) continue;
			if(r.wanted && !r.added) download.add(r.code);
			if(!r.wanted && r.added) remove.add(r.code);
		}
		for(String c : remove) TesseractInstall.remove(c);
		if(download.isEmpty()){ dispose(); if(changed!=null) changed.run(); return; }
		running = true;
		ok.setEnabled(false); addFiles.setEnabled(false);
		Thread t = new Thread(() -> {
			String problem = TesseractInstall.installLanguages(download, new TesseractInstall.Progress() {
				@Override public void report(String text, double fraction) {
					SwingUtilities.invokeLater(() -> status.setText(fraction>=0 ? text+" "+(int)Math.round(fraction*100)+"%" : text));
				}
				@Override public boolean cancelled() { return cancelled; }
			});
			SwingUtilities.invokeLater(() -> {
				running = false;
				if(changed!=null) changed.run();
				if(problem!=null && !cancelled){
					status.setText(" ");
					JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
					load(); model.fireTableDataChanged();
					ok.setEnabled(true); addFiles.setEnabled(true);
					return;
				}
				dispose();
			});
		}, "Tesseract languages");
		t.setDaemon(true); t.start();
	}
}

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

/**
 * Panel Info > Data…: a panel's named values (the data "score" of panel "P") as a Key/Value table. Like Content…, the table
 * is written back with OK as one edit that Undo reverses; rows with an empty key are left out and a repeated key keeps its
 * last value.
 */
final class PanelDataDialog {
	private PanelDataDialog(){}

	/** The rows as values: empty keys dropped, keys compared ignoring case (the first spelling kept, the last value). */
	static LinkedHashMap<String,String> values(java.util.List<String[]> rows){
		LinkedHashMap<String,String> values = new LinkedHashMap<String,String>();
		for(String[] row : rows){
			String key = row[0]==null ? "" : row[0].trim();
			if(key.isEmpty()) continue;
			String existing = null;
			for(String k : values.keySet()) if(k.equalsIgnoreCase(key)) existing = k;
			values.put(existing==null ? key : existing, row[1]==null ? "" : row[1]);
		}
		return values;
	}

	/** Writes the rows to the panel unless they are what it has. */
	static void apply(OPanel panel, java.util.List<String[]> rows) throws xTalkException {
		LinkedHashMap<String,String> values = values(rows);
		if(new ArrayList<Map.Entry<String,String>>(panel.values.entrySet()).equals(new ArrayList<Map.Entry<String,String>>(values.entrySet()))) return;
		TalkCollection all = TalkCollection.dictionary();
		all.entries.putAll(values);
		panel.setAllData(values.isEmpty() ? "" : all.encode());
	}

	static void show(OPanel panel){
		JDialog dialog = new JDialog(AuthDialog.authDialog!=null ? AuthDialog.authDialog : null, International.t("Panel Data"), true);
		DefaultTableModel model = new DefaultTableModel(new Object[]{International.t("Key"), International.t("Value")}, 0);
		for(Map.Entry<String,String> e : panel.values.entrySet()) model.addRow(new Object[]{e.getKey(), e.getValue()});
		JTable table = new JTable(model);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		JScrollPane tablePane = new JScrollPane(table);
		tablePane.setPreferredSize(new Dimension(380, 200));
		JButton add = new JButton(International.t("Add Key"));
		JButton remove = new JButton(International.t("Remove"));
		add.addActionListener(e -> {
			if(table.isEditing()) table.getCellEditor().stopCellEditing();
			model.addRow(new Object[]{"", ""});
			int row = model.getRowCount()-1;
			table.setRowSelectionInterval(row, row);
			table.editCellAt(row, 0);
			table.requestFocusInWindow();
		});
		remove.addActionListener(e -> {
			if(table.isEditing()) table.getCellEditor().cancelCellEditing();
			int row = table.getSelectedRow();
			if(row>=0) model.removeRow(row);
		});
		JPanel dataButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		dataButtons.add(add);
		dataButtons.add(remove);

		boolean[] ok = new boolean[1];
		JButton okButton = new JButton(International.t("OK"));
		JButton cancel = new JButton(International.t("Cancel"));
		okButton.addActionListener(e -> {
			if(table.isEditing()) table.getCellEditor().stopCellEditing();
			ok[0] = true;
			dialog.dispose();
		});
		cancel.addActionListener(e -> dialog.dispose());
		JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
		buttons.add(cancel);
		buttons.add(okButton);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(dataButtons, BorderLayout.WEST);
		bottom.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
		content.add(new JLabel(panel.getShortName()), BorderLayout.NORTH);
		content.add(tablePane, BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(okButton);
		dialog.pack();
		dialog.setLocationRelativeTo(PCARD.pc);
		dialog.setVisible(true);
		if(!ok[0]) return;

		java.util.List<String[]> rows = new ArrayList<String[]>();
		for(int r=0; r<model.getRowCount(); r++)
			rows.add(new String[]{String.valueOf(model.getValueAt(r, 0)), String.valueOf(model.getValueAt(r, 1))});
		AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try{
			apply(panel, rows);
		}catch(xTalkException e){
			JOptionPane.showMessageDialog(PCARD.pc, e.getMessage());
		}finally{ AuthoringUndo.commit(before); }
	}
}

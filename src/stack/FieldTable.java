import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemColor;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventObject;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.CaretEvent;
import javax.swing.event.ChangeEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * The table view of a field whose tableStyle is true. The field's text stays the data: each line is a row and each tab-delimited
 * item a cell, so "item 3 of line 2 of field" (with the itemDelimiter set to tab) reads and writes the same cell the table shows.
 * The view is a scrolling pane (it takes the place of MyScrollPane on the card; the field's MyTextArea keeps the text but is not
 * shown) with a header (columnNames, click to sort when sortable), resizable columns (columnWidths), grid lines (gridLines), row
 * selection (several rows when multipleLines) and cell editing while the text is not locked: typing, Return or a double-click
 * edits a cell, Return and Tab go on to the next one, an empty row at the bottom takes a new line, and a right-click (or
 * Control-click) on a cell or a heading inserts or deletes a column (its name and width move with it). Sorting and editing
 * rewrite the field's text; the text changing from a script redraws the table.
 */
final class FieldTable extends MyScrollPane {
	private static final long serialVersionUID = 1L;

	final JTable table;
	final Model model = new Model();
	private final TypingField editor = new TypingField();
	/** Set while the view itself changes the selection or the column widths (not the user). */
	private boolean syncing;
	/** The column last sorted (0-based, -1 none) and its direction, for the header's arrow. */
	int sortColumn = -1;
	boolean sortAscending = true;
	private boolean widthsChanged;
	private boolean reloadPending;
	private boolean mouseDownSent;
	private boolean pointerInside;
	private static final javax.swing.PopupFactory PLAIN_POPUPS = new javax.swing.PopupFactory();
	/** Set when a press opened the column menu: its release neither sorts nor sends mouseUp. */
	private boolean menuPress;
	/** Set while a cell edit writes the field's text: the table already shows it, so the text change reloads nothing. */
	private boolean writingText;
	private final DocumentListener textListener = new DocumentListener(){
		public void insertUpdate(DocumentEvent e){ reloadLater(); }
		public void removeUpdate(DocumentEvent e){ reloadLater(); }
		public void changedUpdate(DocumentEvent e){ reloadLater(); }
	};

	/** Builds the table of a field (on the EDT); OField.buildOField puts it on the card as the field's scrollPane. */
	FieldTable(OField field){
		fldData = field;
		table = new JTable(model){
			private static final long serialVersionUID = 1L;
			@Override public boolean isCellEditable(int row, int column){ return cellsEditable(); }
			//a key typed on a selected cell replaces what it says, as in a spreadsheet (Escape gives it back)
			@Override public boolean editCellAt(int row, int column, EventObject e){
				boolean editing = super.editCellAt(row, column, e);
				if(editing && e instanceof KeyEvent) editor.setText("");
				return editing;
			}
			@Override public void paint(Graphics g){
				if(PCARD.lockedScreen && !PCARD.capturingEffect) return;
				super.paint(g);
			}
		};
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setFillsViewportHeight(true);
		table.setRowSelectionAllowed(true);
		table.setColumnSelectionAllowed(false);
		table.setSurrendersFocusOnKeystroke(true);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Object.class, new CellRenderer());
		table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
		editor.setBorder(new LineBorder(Color.BLACK));
		table.setDefaultEditor(Object.class, new DefaultCellEditor(editor));
		setViewportView(table);
		setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

		table.getSelectionModel().addListSelectionListener(this::rowsSelected);
		table.getColumnModel().getSelectionModel().addListSelectionListener(this::rowsSelected);
		table.getColumnModel().addColumnModelListener(new TableColumnModelListener(){
			public void columnMarginChanged(ChangeEvent e){ if(!syncing) widthsChanged = true; }
			public void columnAdded(TableColumnModelEvent e){}
			public void columnRemoved(TableColumnModelEvent e){}
			public void columnMoved(TableColumnModelEvent e){}
			public void columnSelectionChanged(ListSelectionEvent e){}
		});
		table.getTableHeader().addMouseListener(new MouseAdapter(){
			private boolean resizing;
			@Override public void mousePressed(MouseEvent e){
				resizing = table.getTableHeader().getCursor().getType()==java.awt.Cursor.E_RESIZE_CURSOR;
			}
			@Override public void mouseReleased(MouseEvent e){
				if(widthsChanged){ widthsChanged = false; storeColumnWidths(); return; }
				if(menuPress) return;
				if(resizing || !SwingUtilities.isLeftMouseButton(e) || !browsing()) return;
				int column = table.columnAtPoint(e.getPoint());
				if(column>=0 && fldData.sortable) sortBy(column);
			}
		});
		MouseAdapter messages = new MessageMouse();
		table.addMouseListener(messages);
		table.addMouseMotionListener(messages);
		table.getTableHeader().addMouseListener(messages);
		getViewport().addMouseListener(messages);

		field.fld.getDocument().addDocumentListener(textListener);
		//select line N of field … sets the text's selection: show it as selected rows
		field.fld.addCaretListener(this::caretMoved);
		applyAppearance();
		reload();
	}

	private static boolean browsing(){
		return PCARD.editMode==0 && AuthTool.tool==null && (PCARD.pc==null || PCARD.pc.tool==null);
	}

	/** Cells are edited like the field's text: only with the browse tool and while lockText is false. */
	private boolean cellsEditable(){
		return fldData!=null && fldData.textEditable();
	}

	/** The tool, lockText or the user level changed: the table takes the keyboard (and shows the new row) only while it may. */
	void editableChanged(){
		table.setFocusable(browsing());
		boolean editable = cellsEditable();
		if(!editable && table.isEditing()) table.getCellEditor().stopCellEditing();
		if(editable==model.newRow) return;
		model.newRow = editable;
		syncing = true;
		try{
			if(editable) model.fireTableRowsInserted(model.rows.size(), model.rows.size());
			else model.fireTableRowsDeleted(model.rows.size(), model.rows.size());
		}finally{ syncing = false; }
	}

	/** Line widths and insets: the card magnification rounded to whole pixels (fonts and sizes use the exact one). */
	private int scale(){ return Math.max(1, (int)Math.round(PCARD.scale())); }

	/** The fonts, colours, border, grid and selection mode from the field's properties. */
	void applyAppearance(){
		OField f = fldData;
		int scale = scale();
		Font font = OObject.stackFont(f.textFont, f.textStyle&0x03, f.textSize*PCARD.scale());
		table.setFont(font);
		table.getTableHeader().setFont(font);
		table.setRowHeight(PCARD.toPhysical(Math.max(f.textHeight, f.textSize+2)));
		table.setForeground(f.color);
		table.setBackground(f.bgColor);
		getViewport().setBackground(f.bgColor);
		table.setSelectionBackground(SystemColor.textHighlight);
		table.setSelectionForeground(SystemColor.textHighlightText);
		table.setShowGrid(f.gridLines);
		table.setIntercellSpacing(f.gridLines ? new java.awt.Dimension(1, 1) : new java.awt.Dimension(0, 0));
		table.setGridColor(MyTextArea.lineSeparatorColor(f.color, f.bgColor));
		table.getSelectionModel().setSelectionMode(f.multipleLines
			? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
		table.setFocusable(browsing());
		//(0標準) 1透明 2不透明 3長方形 4シャドウ 5スクロール: a table always has a frame, except when transparent
		if(f.style==1){
			setBorder(new EmptyBorder(0, 0, 0, 0));
			setOpaque(false);
			getViewport().setOpaque(false);
			table.setOpaque(false);
		}
		else if(f.style==4){
			//HyperCardの影: 欠けた角からカードが見えるよう、外側は描かず中身(viewport)だけ不透明にする
			setBorder(new ShadowFieldBorder(f));
			setOpaque(false);
			getViewport().setOpaque(true);
			table.setOpaque(true);
		}
		else{
			setBorder(new LineBorder(f.color, scale));
			setOpaque(true);
			getViewport().setOpaque(true);
			table.setOpaque(true);
		}
		applyColumnWidths();
		table.getTableHeader().repaint();
		table.repaint();
	}

	/** Column widths from columnWidths (logical pixels); columns it leaves out share what is left of the field's width. */
	void applyColumnWidths(){
		int count = table.getColumnCount();
		if(count==0) return;
		int[] widths = Data.parseWidths(fldData.columnWidths);
		int given = 0, missing = 0;
		for(int c=0; c<count; c++){ if(c<widths.length && widths[c]>0) given += widths[c]; else missing++; }
		int inner = Math.max(0, fldData.width-2-(fldData.style==4 ? 2 : 0)); //the shadow takes 2 pixels on the right
		int share = missing==0 ? 0 : Math.max(40, (inner-given)/missing);
		syncing = true;
		try{
			for(int c=0; c<count; c++){
				TableColumn column = table.getColumnModel().getColumn(c);
				int w = c<widths.length && widths[c]>0 ? widths[c] : share;
				column.setPreferredWidth(PCARD.toPhysical(w));
				column.setWidth(PCARD.toPhysical(w));
			}
		}finally{ syncing = false; }
	}

	/** The widths the user dragged the header to, kept as the field's columnWidths. */
	private void storeColumnWidths(){
		int count = table.getColumnCount();
		StringBuilder sb = new StringBuilder();
		for(int c=0; c<count; c++){
			if(c>0) sb.append(',');
			sb.append(Math.max(1, Math.round(table.getColumnModel().getColumn(c).getWidth()/(float)PCARD.scale())));
		}
		fldData.columnWidths = sb.toString();
		if(fldData.parent instanceof OCardBase) ((OCardBase)fldData.parent).changed = true;
	}

	private void reloadLater(){
		if(reloadPending || writingText) return;
		reloadPending = true;
		SwingUtilities.invokeLater(() -> { reloadPending = false; reload(); });
	}

	/** Reads the rows from the field's text again, keeping the selection (the field's selectedRows). */
	void reload(){
		if(fldData==null || fldData.fld==null) return;
		if(table.isEditing()) table.getCellEditor().cancelCellEditing();
		List<String[]> rows = Data.parseRows(fldData.getText());
		String[] names = Data.parseNames(fldData.columnNames);
		int columns = Math.max(1, Math.max(names.length, Data.columnCount(rows)));
		boolean structure = columns!=model.columns || !Arrays.equals(names, model.names);
		model.rows = rows;
		model.names = names;
		model.columns = columns;
		model.newRow = cellsEditable();
		syncing = true;
		try{
			if(structure) model.fireTableStructureChanged();
			else model.fireTableDataChanged();
		}finally{ syncing = false; }
		if(structure) applyColumnWidths();
		showSelection();
	}

	/** Shows the field's selectedRows and selectedColumn in the table. */
	void showSelection(){
		OField f = fldData;
		if(f==null) return;
		syncing = true;
		try{
			ListSelectionModel sel = table.getSelectionModel();
			sel.clearSelection();
			int first = -1;
			for(int row : f.selectedRows){
				if(row<1 || row>model.rows.size()) continue;
				sel.addSelectionInterval(row-1, row-1);
				if(first<0) first = row-1;
			}
			if(f.selectedColumn>=1 && f.selectedColumn<=table.getColumnCount())
				table.getColumnModel().getSelectionModel().setSelectionInterval(f.selectedColumn-1, f.selectedColumn-1);
			if(first>=0) table.scrollRectToVisible(table.getCellRect(first, Math.max(0, f.selectedColumn-1), true));
		}finally{ syncing = false; }
		table.repaint();
	}

	/** The user selected rows (click, shift/command-click, arrow keys): kept as the field's selection. */
	private void rowsSelected(ListSelectionEvent e){
		if(syncing || e.getValueIsAdjusting()) return;
		//the empty row for a new line is no line of the text
		int[] lines = Arrays.stream(table.getSelectedRows()).filter(r -> r<model.rows.size()).map(r -> r+1).toArray();
		fldData.selectedRows = lines;
		fldData.selectedLine = lines.length>0 ? lines[0] : 0;
		int lead = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
		fldData.selectedColumn = lines.length>0 && lead>=0 ? lead+1 : 0;
	}

	/** "select line 2 to 4 of field …" selects the text of those lines: select the rows too. */
	private void caretMoved(CaretEvent e){
		if(syncing || fldData==null || fldData.fld==null) return;
		int start = Math.min(e.getDot(), e.getMark()), end = Math.max(e.getDot(), e.getMark());
		if(end<=start) return;
		String text = fldData.getText();
		int first = 1, last = 1;
		for(int i=0; i<text.length() && i<end; i++){
			if(text.charAt(i)=='\n'){ if(i<start) first++; if(i<end-1) last++; }
		}
		int[] lines = new int[last-first+1];
		for(int i=0; i<lines.length; i++) lines[i] = first+i;
		fldData.selectedRows = lines;
		fldData.selectedLine = first;
		showSelection();
	}

	/** Sorts the rows by a column (again: the other way round) and writes them back as the field's text. */
	void sortBy(int column){
		if(table.isEditing()) table.getCellEditor().stopCellEditing();
		boolean ascending = column==sortColumn ? !sortAscending : true;
		String text = fldData.getText();
		int[] order = Data.sortOrder(Data.parseRows(text), column, ascending);
		//the selected rows stay selected where they move to
		int[] selected = fldData.selectedRows;
		int[] moved = new int[selected.length];
		for(int i=0; i<selected.length; i++){
			moved[i] = selected[i];
			for(int k=0; k<order.length; k++) if(order[k]==selected[i]-1){ moved[i] = k+1; break; }
		}
		Arrays.sort(moved);
		sortColumn = column;
		sortAscending = ascending;
		fldData.selectedRows = moved;
		fldData.selectedLine = moved.length>0 ? moved[0] : 0;
		fldData.setText(Data.reorder(text, order));
		table.getTableHeader().repaint();
		TTalk.CallMessage("sortTable", new String[]{Integer.toString(column+1), ascending ? "ascending" : "descending"}, fldData);
	}

	/**
	 * The column menu (right-click or Control-click while the cells can be edited) for a column (0-based; -1: below the rows,
	 * which stands for the last one). Returns whether it was shown.
	 */
	boolean showColumnMenu(Component on, int x, int y, int column){
		if(!cellsEditable()) return false;
		if(table.isEditing()) table.getCellEditor().stopCellEditing();
		int c = column>=0 && column<model.columns ? column : model.columns-1;
		JPopupMenu menu = new JPopupMenu();
		JMenuItem left = new JMenuItem(International.t("Insert Column Left"));
		left.addActionListener(e -> insertColumn(c+1));
		JMenuItem right = new JMenuItem(International.t("Insert Column Right"));
		right.addActionListener(e -> insertColumn(c+2));
		JMenuItem delete = new JMenuItem(International.t("Delete Column"));
		delete.setEnabled(model.columns>1);
		delete.addActionListener(e -> deleteColumn(c+1));
		menu.add(left);
		menu.add(right);
		menu.addSeparator();
		menu.add(delete);
		//inside the card window, not a window of its own: on macOS a menu window leaves no window active when it closes, and
		//the keys typed next went nowhere. The Mac look makes every menu a window (its PopupFactory ignores the lightweight
		//setting), so the plain factory shows this one
		menu.setLightWeightPopupEnabled(true);
		menu.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Color.GRAY), new EmptyBorder(3, 0, 3, 0)));
		javax.swing.JRootPane root = SwingUtilities.getRootPane(on);
		java.awt.Dimension size = menu.getPreferredSize();
		if(root!=null && (size.height>root.getHeight() || size.width>root.getWidth())) menu.setLightWeightPopupEnabled(false);
		else if(root!=null){
			Point at = SwingUtilities.convertPoint(on, x, y, root);
			x += Math.max(0, Math.min(at.x, root.getWidth()-size.width))-at.x;
			y += Math.max(0, Math.min(at.y, root.getHeight()-size.height))-at.y;
		}
		javax.swing.PopupFactory shared = javax.swing.PopupFactory.getSharedInstance();
		try{
			if(menu.isLightWeightPopupEnabled()) javax.swing.PopupFactory.setSharedInstance(PLAIN_POPUPS);
			menu.show(on, x, y);
		}finally{ javax.swing.PopupFactory.setSharedInstance(shared); }
		return true;
	}

	/** Puts an empty column in at a place (1-based; one past the last adds it at the end), with an empty name and a shared width. */
	void insertColumn(int column){
		if(table.isEditing()) table.getCellEditor().stopCellEditing();
		OField f = fldData;
		String text = Data.insertColumn(f.getText(), column, model.columns);
		f.columnNames = Data.insertName(f.columnNames, column);
		f.columnWidths = Data.insertWidth(f.columnWidths, column);
		if(f.selectedColumn>=column) f.selectedColumn++;
		if(sortColumn>=column-1) sortColumn++;
		columnsChanged(text);
	}

	/** Takes a column (1-based) out of every row, with its name and width; the last column stays. */
	void deleteColumn(int column){
		if(model.columns<=1) return;
		if(table.isEditing()) table.getCellEditor().cancelCellEditing();
		OField f = fldData;
		String text = Data.deleteColumn(f.getText(), column);
		f.columnNames = Data.deleteName(f.columnNames, column);
		f.columnWidths = Data.deleteWidth(f.columnWidths, column);
		if(f.selectedColumn==column) f.selectedColumn = 0;
		else if(f.selectedColumn>column) f.selectedColumn--;
		if(sortColumn==column-1) sortColumn = -1;
		else if(sortColumn>column-1) sortColumn--;
		columnsChanged(text);
	}

	private void columnsChanged(String text){
		if(fldData.parent instanceof OCardBase) ((OCardBase)fldData.parent).changed = true;
		writingText = true;
		try{ fldData.setText(text); }finally{ writingText = false; }
		reload();
		table.getTableHeader().repaint();
		TTalk.CallMessage("closeField", fldData);
	}

	/** Stops editing when the card goes away, keeping what was typed. */
	@Override
	public void removeNotify(){
		if(table.isEditing()) table.getCellEditor().stopCellEditing();
		super.removeNotify();
	}

	/** Detaches from the field's text (the field is being rebuilt or its card closed). */
	void dispose(){
		if(fldData!=null && fldData.fld!=null){
			fldData.fld.getDocument().removeDocumentListener(textListener);
		}
	}

	/**
	 * The editor of a cell or an outline node. The Mac look selects all of a text field's text when it gets the keyboard
	 * (when the caret is at its start or end): the characters typed just after the edit started replaced the whole cell.
	 * Here the caret stays where it was.
	 */
	static final class TypingField extends JTextField {
		private static final long serialVersionUID = 1L;
		@Override protected void processFocusEvent(FocusEvent e){
			int dot = getCaretPosition(), mark = getCaret().getMark();
			super.processFocusEvent(e);
			if(e.getID()==FocusEvent.FOCUS_GAINED && (getCaretPosition()!=dot || getCaret().getMark()!=mark)){
				setCaretPosition(mark);
				moveCaretPosition(dot);
			}
		}
	}

	/** The rows of the field's text as table cells; editing a cell writes the item back into the text. */
	final class Model extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		List<String[]> rows = new ArrayList<String[]>();
		String[] names = new String[0];
		int columns = 0;
		/** An empty row after the last line, while the cells can be edited: typing into it adds a line to the text. */
		boolean newRow;

		public int getRowCount(){ return rows.size()+(newRow ? 1 : 0); }
		public int getColumnCount(){ return columns; }
		//a column without a name (also one just inserted between named ones) shows its number
		@Override public String getColumnName(int column){
			return column<names.length && !names[column].isEmpty() ? names[column] : Integer.toString(column+1);
		}
		public Object getValueAt(int row, int column){
			if(row>=rows.size()) return "";
			String[] cells = rows.get(row);
			return column<cells.length ? cells[column] : "";
		}
		@Override public void setValueAt(Object value, int row, int column){
			String v = value==null ? "" : value.toString();
			if(v.equals(getValueAt(row, column))) return;
			boolean added = row>=rows.size();
			String text = Data.setCell(fldData.getText(), row+1, column+1, v);
			writingText = true;
			try{ fldData.setText(text); }finally{ writingText = false; }
			//the rows change here and now (not when the text's change comes round), so Return or Tab moves on from this cell
			//and a typed new line has the next empty row under it
			rows = Data.parseRows(text);
			fireTableRowsUpdated(row, row);
			if(added && newRow) fireTableRowsInserted(rows.size(), rows.size());
			TTalk.CallMessage("closeField", fldData);
		}
	}

	/** A cell as drawn: in the stack's own translation of its whole line (the tabs kept), else of the cell (StackLanguages). */
	private String shownCell(int row, int column, String value){
		if(row<model.rows.size()){
			String[] cells = model.rows.get(row);
			if(cells.length>1){
				String line = String.join("\t", cells), shown = StackTranslation.cell(fldData, line);
				String[] translated = shown.split("\t", -1);
				if(shown!=line && translated.length==cells.length && column<translated.length) return translated[column];
			}
		}
		return StackTranslation.cell(fldData, value);
	}
	/** A column name as drawn: from the stack's own translation of all the columnNames, else of the name. */
	private String shownColumnName(int column, String name){
		String all = fldData.columnNames;
		if(all!=null && !all.isEmpty()){
			String shown = StackTranslation.cell(fldData, all);
			String[] names = Data.parseNames(shown);
			if(shown!=all && names.length==model.names.length && column<names.length && !names[column].isEmpty()) return names[column];
		}
		return StackTranslation.cell(fldData, name);
	}

	private final class CellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column){
			//the stack's own translation is drawn (StackLanguages); the cell editor and scripts have the cell's own text
			super.getTableCellRendererComponent(t, value instanceof String ? shownCell(row, column, (String)value) : value, selected, false, row, column);
			setBorder(new EmptyBorder(0, 3*scale(), 0, 3*scale()));
			setHorizontalAlignment(fldData.textAlign==1 ? SwingConstants.CENTER : fldData.textAlign==2 ? SwingConstants.RIGHT : SwingConstants.LEADING);
			setOpaque(selected || fldData.style!=1);
			if(!selected){ setForeground(fldData.color); setBackground(fldData.bgColor); }
			return this;
		}
	}

	/** A plain classic header: light grey, a line under and between the columns, the sort arrow on the sorted column. */
	private final class HeaderRenderer extends JLabel implements TableCellRenderer {
		private static final long serialVersionUID = 1L;
		HeaderRenderer(){ setOpaque(true); }
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column){
			String name = value==null ? "" : shownColumnName(column, value.toString());
			if(column==sortColumn) name += sortAscending ? " ▲" : " ▼";
			setText(name);
			setFont(t.getTableHeader().getFont());
			setForeground(fldData.color);
			setBackground(new Color(0xE8, 0xE8, 0xE8));
			setHorizontalAlignment(SwingConstants.LEADING);
			int s = scale();
			setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, s, s, Color.GRAY), new EmptyBorder(s, 3*s, s, 3*s)));
			return this;
		}
	}

	/**
	 * The browse tool's messages for the field, as for a list field: mouseDown/mouseStillDown/mouseUp/mouseDoubleClick,
	 * mouseEnter/mouseWithin/mouseLeave; the clickLine is the clicked row and the clickText the clicked cell.
	 */
	private final class MessageMouse extends MouseAdapter {
		private Point toPane(MouseEvent e){
			return SwingUtilities.convertPoint((Component)e.getSource(), e.getPoint(), FieldTable.this);
		}
		/** The column under the pointer (0-based; -1 below the rows). */
		private int columnAt(MouseEvent e){
			if(e.getSource()==table) return table.columnAtPoint(e.getPoint());
			if(e.getSource()==table.getTableHeader()) return table.getTableHeader().columnAtPoint(e.getPoint());
			return -1;
		}
		private boolean columnMenu(MouseEvent e){
			if(!e.isPopupTrigger() || !browsing()) return false;
			if(!showColumnMenu((Component)e.getSource(), e.getX(), e.getY(), columnAt(e))) return false;
			menuPress = true;
			return true;
		}
		@Override public void mousePressed(MouseEvent e){
			mouseDownSent = false;
			menuPress = false;
			GUI.mouseDowned();
			OField f = fldData;
			if(f==null) return;
			if(PCARD.editMode==0 && columnMenu(e)) return;
			if(PCARD.editMode!=0){
				//Command-Option-Shift shows the field outlines: a click opens the script
				//(key[]に Command-Option が残っていただけの普通のクリックならピークを終える)
				if(!GUI.isPeekClick(e)) GUI.endPeek();
				else if(GUI.stroke_frag==2 && PCARD.pc!=null && PCARD.pc.stack!=null){
					ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, f);
					GUI.endPeek();
				}
				return;
			}
			if(!browsing()) return;
			Point p = toPane(e);
			GUI.clickH = f.left + PCARD.toLogical(p.x);
			GUI.clickV = f.top + PCARD.toLogical(p.y);
			GUI.noteFieldClick(f, p.x, p.y);
			GUI.clickField = f;
			if(e.getSource()==table){
				int row = table.rowAtPoint(e.getPoint());
				int column = table.columnAtPoint(e.getPoint());
				f.clickRow = row>=0 ? row+1 : 0;
				f.clickColumn = row>=0 && column>=0 ? column+1 : 0;
				if(row>=0) GUI.clickLine = "line "+(row+1)+" of "+f.getShortName();
			}
			if(!TTalk.idle) return;
			mouseDownSent = true;
			TTalk.CallMessage("mouseDown", f);
		}
		@Override public void mouseReleased(MouseEvent e){
			GUI.mouseUped();
			boolean sent = mouseDownSent;
			mouseDownSent = false;
			if(!browsing() || fldData==null) return;
			//Windows and Linux open the menu on the release
			if(!menuPress && columnMenu(e)) sent = false;
			if(menuPress){ menuPress = false; return; }
			Point p = toPane(e);
			boolean inside = new Rectangle(0, 0, getWidth(), getHeight()).contains(p);
			if(sent && inside && TTalk.idle){
				TTalk.CallMessage("mouseUp", fldData);
				if(e.getClickCount()==2) TTalk.CallMessage("mouseDoubleClick", fldData);
			}
			GUI.mouseClicked();
		}
		@Override public void mouseDragged(MouseEvent e){
			if(mouseDownSent && TTalk.idle && fldData!=null) TTalk.CallMessage("mouseStillDown", fldData);
		}
		@Override public void mouseMoved(MouseEvent e){
			if(TTalk.idle && browsing() && fldData!=null) TTalk.CallMessage("mouseWithin", fldData);
		}
		@Override public void mouseEntered(MouseEvent e){
			if(pointerInside || fldData==null) return;
			pointerInside = true;
			if(TTalk.idle && browsing()) TTalk.CallMessage("mouseEnter", fldData);
		}
		@Override public void mouseExited(MouseEvent e){
			//moving between the header and the rows stays inside the field
			if(new Rectangle(0, 0, getWidth(), getHeight()).contains(toPane(e)) || fldData==null) return;
			pointerInside = false;
			if(TTalk.idle && browsing()) TTalk.CallMessage("mouseLeave", fldData);
		}
	}

	/** The field's text as rows of tab-delimited cells (no Swing: also used by the HyperTalk properties and the tests). */
	static final class Data {
		private Data(){}

		/** The lines of the text (a final return does not start another row); each split at the tabs. */
		static List<String[]> parseRows(String text){
			List<String[]> rows = new ArrayList<String[]>();
			if(text==null || text.isEmpty()) return rows;
			String[] lines = lines(text);
			for(String line : lines) rows.add(line.split("\t", -1));
			return rows;
		}

		static String[] lines(String text){
			String t = text.replace("\r\n", "\n").replace('\r', '\n');
			if(t.endsWith("\n")) t = t.substring(0, t.length()-1);
			return t.split("\n", -1);
		}

		static int columnCount(List<String[]> rows){
			int n = 0;
			for(String[] r : rows) n = Math.max(n, r.length);
			return n;
		}

		/** "120,80,200" → {120,80,200}; an empty or non-number item leaves that column to share the rest (0). */
		static int[] parseWidths(String spec){
			if(spec==null || spec.trim().isEmpty()) return new int[0];
			String[] items = spec.split(",", -1);
			int[] widths = new int[items.length];
			for(int i=0; i<items.length; i++){
				try{ widths[i] = Math.max(0, (int)Math.round(Double.parseDouble(items[i].trim()))); }
				catch(NumberFormatException e){ widths[i] = 0; }
			}
			return widths;
		}

		/** Checks a columnWidths value: numbers (or empty items) separated by commas. */
		static String normalizeWidths(String spec) throws xTalkException {
			if(spec==null || spec.trim().isEmpty()) return "";
			String[] items = spec.split(",", -1);
			StringBuilder sb = new StringBuilder();
			for(int i=0; i<items.length; i++){
				String item = items[i].trim();
				if(i>0) sb.append(',');
				if(item.isEmpty()) continue;
				try{
					double w = Double.parseDouble(item);
					if(w<0) throw new NumberFormatException();
					sb.append((int)Math.round(w));
				}catch(NumberFormatException e){
					throw new xTalkException("columnWidths requires comma-separated widths such as \"120,80,200\"");
				}
			}
			return sb.toString();
		}

		/** Column names separated by tabs, or else by commas (a list typed in a script); a return also separates them. */
		static String[] parseNames(String spec){
			if(spec==null || spec.isEmpty()) return new String[0];
			String s = spec.replace("\r\n", "\n").replace('\r', '\n');
			String sep = s.indexOf('\t')>=0 ? "\t" : s.indexOf('\n')>=0 ? "\n" : ",";
			if(s.endsWith(sep)) s = s.substring(0, s.length()-1);
			return s.split(sep, -1);
		}

		/** Puts a value into item column of line row (1-based), adding lines and tabs as needed; tabs and returns in it become spaces. */
		static String setCell(String text, int row, int column, String value){
			if(text==null) text = "";
			String v = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			List<String> lines = new ArrayList<String>(text.isEmpty() ? new ArrayList<String>() : Arrays.asList(lines(text)));
			while(lines.size()<row) lines.add("");
			List<String> cells = new ArrayList<String>(Arrays.asList(lines.get(row-1).split("\t", -1)));
			while(cells.size()<column) cells.add("");
			cells.set(column-1, v);
			lines.set(row-1, String.join("\t", cells));
			return String.join("\n", lines)+(finalReturn ? "\n" : "");
		}

		/**
		 * The text with an empty cell put in at a column (1-based) of every row that reaches it (a shorter row shows the column
		 * empty as it is; when none does, the first row is made to). Text without rows gets one empty row of the columns, the new
		 * one too, so that the column shows.
		 */
		static String insertColumn(String text, int column, int columns){
			if(text==null || text.isEmpty()) return "\t".repeat(Math.max(columns, column-1));
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			String[] lines = lines(text);
			boolean reached = false;
			for(int i=0; i<lines.length; i++){
				List<String> cells = new ArrayList<String>(Arrays.asList(lines[i].split("\t", -1)));
				if(cells.size()<column) continue;
				cells.add(column-1, "");
				lines[i] = String.join("\t", cells);
				reached = true;
			}
			//a column after the last cells (at the end): the first row reaches it, so that the table has it
			if(!reached) lines[0] = lines[0]+"\t".repeat(column-lines[0].split("\t", -1).length);
			return String.join("\n", lines)+(finalReturn ? "\n" : "");
		}

		/** The text without one column (1-based): that cell taken out of every row that has it. */
		static String deleteColumn(String text, int column){
			if(text==null || text.isEmpty()) return "";
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			String[] lines = lines(text);
			for(int i=0; i<lines.length; i++){
				List<String> cells = new ArrayList<String>(Arrays.asList(lines[i].split("\t", -1)));
				if(cells.size()<column) continue;
				cells.remove(column-1);
				lines[i] = String.join("\t", cells);
			}
			return String.join("\n", lines)+(finalReturn ? "\n" : "");
		}

		/** columnNames with an empty name put in at a column (1-based), in the same separators; no names stay no names. */
		static String insertName(String spec, int column){ return editList(spec, nameSeparator(spec), column, true); }
		static String deleteName(String spec, int column){ return editList(spec, nameSeparator(spec), column, false); }
		/** columnWidths with an empty width (a share of the rest) put in at a column, or a column's width taken out. */
		static String insertWidth(String spec, int column){ return editList(spec, ",", column, true); }
		static String deleteWidth(String spec, int column){ return editList(spec, ",", column, false); }

		private static String nameSeparator(String spec){
			String s = spec==null ? "" : spec.replace("\r\n", "\n").replace('\r', '\n');
			return s.indexOf('\t')>=0 ? "\t" : s.indexOf('\n')>=0 ? "\n" : ",";
		}

		private static String editList(String spec, String sep, int column, boolean insert){
			if(spec==null || spec.isEmpty()) return "";
			String s = spec.replace("\r\n", "\n").replace('\r', '\n');
			boolean finalSep = s.endsWith(sep);
			if(finalSep) s = s.substring(0, s.length()-1);
			List<String> items = new ArrayList<String>(Arrays.asList(s.split(java.util.regex.Pattern.quote(sep), -1)));
			if(items.size()<column) return spec;
			if(insert) items.add(column-1, "");
			else items.remove(column-1);
			//nothing left (the only name or width taken out): no names, no widths
			if(items.stream().allMatch(String::isEmpty)) return "";
			return String.join(sep, items)+(finalSep ? sep : "");
		}

		/**
		 * The new order of the rows (indexes into rows) sorted by one column: numerically when every non-empty cell of it is a
		 * number, else as text ignoring case; empty cells go last; rows that compare equal keep their order.
		 */
		static int[] sortOrder(List<String[]> rows, int column, boolean ascending){
			boolean numeric = true, any = false;
			for(String[] r : rows){
				String c = cell(r, column).trim();
				if(c.isEmpty()) continue;
				any = true;
				if(number(c)==null){ numeric = false; break; }
			}
			final boolean byNumber = numeric && any;
			java.text.Collator collator = java.text.Collator.getInstance();
			collator.setStrength(java.text.Collator.SECONDARY);
			Integer[] order = new Integer[rows.size()];
			for(int i=0; i<order.length; i++) order[i] = i;
			Arrays.sort(order, (a, b) -> {
				String x = cell(rows.get(a), column).trim(), y = cell(rows.get(b), column).trim();
				if(x.isEmpty() || y.isEmpty()) return x.isEmpty()==y.isEmpty() ? 0 : x.isEmpty() ? 1 : -1;
				int c = byNumber ? Double.compare(number(x), number(y)) : collator.compare(x, y);
				return ascending ? c : -c;
			});
			int[] result = new int[order.length];
			for(int i=0; i<order.length; i++) result[i] = order[i];
			return result;
		}

		/** The text with its lines in a new order (from sortOrder). */
		static String reorder(String text, int[] order){
			if(text==null || text.isEmpty()) return "";
			String[] lines = lines(text);
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			StringBuilder sb = new StringBuilder();
			for(int i=0; i<order.length; i++){
				if(i>0) sb.append('\n');
				sb.append(lines[order[i]]);
			}
			return sb.toString()+(finalReturn ? "\n" : "");
		}

		private static String cell(String[] row, int column){
			return column<row.length ? row[column] : "";
		}

		private static Double number(String s){
			try{ return Double.valueOf(s); }
			catch(NumberFormatException e){ return null; }
		}
	}
}

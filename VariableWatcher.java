import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;

import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;


public class VariableWatcher extends JDialog
{
	private static final long serialVersionUID = 3795782126509780013L;

	static VariableWatcher watcherWindow;
	static private JScrollPane scrollpane;
	static int rowSize;
	
	private String[] columnNames = {"Name", "Value"};
	
	public VariableWatcher()
	{
		super(/*PCARD.pc*/);
		
		//オブジェクトをウィンドウリストに登録
		setTitle("VariableWatcher");
		new OWindow(this);
	    watcherWindow = this;
		
	    //テーブルを用意
		String[][] tabledata = { {"it", ""} };

		JTable table = new JTable(tabledata, columnNames);
	    //table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
	    table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new MultiLineCellRenderer());
	    
	    //スクロール
	    scrollpane = new JScrollPane(table);
	    add(scrollpane);
	    
	    //ウィンドウ位置とサイズ設定
	    setBounds(0,0,320,240);
	    setLocationRelativeTo(null);
	}
	
	public void setTable(MemoryData globalData, MemoryData memData){
		int total = globalData.nameList.size();
		if(memData!=null) total += memData.nameList.size();
		String[][] tabledata = new String[total][2];

		int i;
		for(i=0; i<globalData.nameList.size(); i++){
			tabledata[i][0] = "*"+globalData.nameList.get(i);
			tabledata[i][1] = globalData.valueList.get(i);
		}
		if(memData!=null) {
			for(int j=0; j<memData.nameList.size(); j++){
				tabledata[i+j][0] = memData.nameList.get(j);
				tabledata[i+j][1] = memData.valueList.get(j);
			}
		}

		rowSize = TTalk.globalData.nameList.size();
		
		JTable table = new JTable(tabledata, columnNames);
	    //table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
	    table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new MultiLineCellRenderer());

	    Rectangle rect = scrollpane.getBounds();
	    int scroll = scrollpane.getVerticalScrollBar().getValue();
	    remove(scrollpane);
	    scrollpane = new JScrollPane(table);
	    add(scrollpane);
	    scrollpane.getVerticalScrollBar().setValue(scroll);
	    /*scrollpane.removeAll();
	    scrollpane.add(table);
	    scrollpane.setViewportView(table);*/
	    scrollpane.setBounds(rect);
	    //repaint();
	}
}

class MultiLineCellRenderer extends JTextArea implements TableCellRenderer {
	private static final long serialVersionUID = -8300422827510636123L;
	protected static Border noFocusBorder = new EmptyBorder(1, 1, 1, 1);

	public MultiLineCellRenderer() {
		setLineWrap(true);
		setWrapStyleWord(true);
		setOpaque(true);
	}

	public Component getTableCellRendererComponent(JTable table, Object value,
			boolean isSelected, boolean hasFocus, int row, int column) {
		if (isSelected) {
			super.setForeground(table.getSelectionForeground());
			super.setBackground(table.getSelectionBackground());
		} else {
			super.setForeground(table.getForeground());
			Color bgcolor = table.getBackground();
			if(row%2==1) bgcolor = new Color(Math.max(0,bgcolor.getRed()-30),Math.max(0,bgcolor.getGreen()-20),bgcolor.getBlue());
			super.setBackground(bgcolor);
		}

		setFont(table.getFont());

		if (hasFocus) {
			setBorder(UIManager.getBorder("Table.focusCellHighlightBorder"));
		} else {
			setBorder(noFocusBorder);
		}

		setText((value == null) ? "" : value.toString());

		this.setBounds(0,0,128,128);
		int h = this.getPreferredSize().height;
		table.setRowHeight(row, h);

		return this;
	}
}

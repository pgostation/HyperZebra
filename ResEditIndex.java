import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Iterator;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;


public class ResEditIndex extends JFrame
{
	private static final long serialVersionUID = -766099048104505721L;
	
	private JScrollPane scrollpane;
	ResEdit TypeIndexEditor;
	OStack stack;
	
	private String[] columnNames = {"Type", "Count"};
	
	public ResEditIndex(PCARDFrame parent)
	{
		super();
		stack = parent.stack;
		
		setTitle("Resource Editor");
		
	    //テーブルを用意
		String[][] tabledata = { {"", ""} };

		JTable table = new JTable(tabledata, columnNames);
	    //table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
	    table.setEnabled(false);
		//table.setDefaultRenderer(Object.class, new MultiLineCellRenderer());
	    
	    //スクロール
	    scrollpane = new JScrollPane(table);
	    add(scrollpane);
	    
	    setTable(stack.rsrc);
	    
	    //ウィンドウ位置とサイズ設定
	    setBounds(0,0,560,480);
	    setLocationRelativeTo(parent);
	    setVisible(true);
	}
	
	JTable table;
	String[][] tabledata;
	
	public void setTable(Rsrc rsrc){
		ArrayList<Integer> countList = new ArrayList<Integer>();
		ArrayList<String> typeList = new ArrayList<String>();
		
		Iterator<Rsrc.rsrcClass> it = rsrc.rsrcIdMap.values().iterator();
		while(it.hasNext()){
			Rsrc.rsrcClass r = it.next();
			if(typeList.contains(r.type)){
				int i = typeList.indexOf(r.type);
				int count = countList.get(i);
				countList.set(i, count+1);
			}
			else{
				typeList.add(r.type);
				countList.add(1);
			}
		}
		
		
		tabledata = new String[typeList.size()][2];

		int i;
		for(i=0; i<typeList.size(); i++){
			tabledata[i][0] = typeList.get(i);
			tabledata[i][1] = Integer.toString(countList.get(i));
		}
		
		table = new JTable(tabledata, columnNames);
	    //table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
	    table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new MultiLineCellRenderer());

		table.setAutoCreateRowSorter(true);
		ArrayList<RowSorter.SortKey> s = new ArrayList<RowSorter.SortKey>();
		s.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
		table.getRowSorter().setSortKeys(s);
		
		table.addMouseListener(new MouseAdapter() {
		  @Override public void mouseClicked(final MouseEvent me) {
		    if(me.getClickCount()==2) {
		      Point pt = me.getPoint();
		      int idx = table.rowAtPoint(pt);
		      if(idx>=0) {
		        int row = table.convertRowIndexToModel(idx);
		        new ResEdit(stack.pcard, tabledata[row][0], null);
		      }
		    }
		  }
		});
		
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

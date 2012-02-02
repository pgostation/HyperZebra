import java.awt.Rectangle;

import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTable;


public class MessageWatcher extends JDialog
{
	private static final long serialVersionUID = -766099048104505721L;
	
	static MessageWatcher watcherWindow;
	static private JScrollPane scrollpane;
	
	private String[] columnNames = {"Message", "Object"};
	
	public MessageWatcher()
	{
		super(/*PCARD.pc*/);
		
		//オブジェクトをウィンドウリストに登録
		setTitle("MessageWatcher");
		new OWindow(this);
	    watcherWindow = this;
		
	    //テーブルを用意
		String[][] tabledata = { {"", ""} };

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
	
	public void setTable(String[] messageRingArray, OObject[] objectRingArray, int ringCnt){
		int total = Math.min(ringCnt, messageRingArray.length);
		String[][] tabledata = new String[total][2];

		int i;
		for(i=0; i<total; i++){
			int j = (ringCnt+i)%messageRingArray.length;
			int i2 = total-i-1;
			tabledata[i2][0] = messageRingArray[j];
			if(objectRingArray[j]!=null){
				tabledata[i2][1] = objectRingArray[j].getShortName();
			}
		}
		
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

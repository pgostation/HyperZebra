import java.awt.BorderLayout;

import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;


/**
 * メッセージ表示ウィンドウ。直近に実行されたハンドラ(メッセージ名と受け取ったオブジェクト)を
 * 新しいものから順に表示する。スクリプトスレッドから呼ばれるので、内容のコピーだけを
 * その場で作り、表の更新はEDTで行う。
 */
public class MessageWatcher extends JDialog
{
	private static final long serialVersionUID = -766099048104505721L;
	
	static MessageWatcher watcherWindow;
	private final DefaultTableModel model;
	private final JTable table;
	private final JScrollPane scrollpane;
	/** まだEDTで反映していない最新の内容(何度も呼ばれても最後のものだけ表示すればよい) */
	private static volatile String[][] pendingData;
	
	public MessageWatcher()
	{
		super(/*PCARD.pc*/);
		
		//オブジェクトをウィンドウリストに登録
		setTitle(text("Message Watcher"));
		new OWindow(this);
	    watcherWindow = this;
		
	    //テーブルを用意
		model = new DefaultTableModel(new Object[]{ text("Message"), text("Object") }, 0) {
			private static final long serialVersionUID = 1L;
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		table = new JTable(model);
		table.setDefaultRenderer(Object.class, new MultiLineCellRenderer());
	    
	    //スクロール
	    scrollpane = new JScrollPane(table);
	    add(scrollpane, BorderLayout.CENTER);
	    
	    //ウィンドウ位置とサイズ設定
	    setBounds(0,0,320,240);
	    setLocationRelativeTo(null);
	}

	private static String text(String name){
		if(PCARD.pc==null || PCARD.pc.intl==null) return name;
		return PCARD.pc.intl.getDialogText(name);
	}
	
	/** リングバッファの内容を、新しいものが上になるよう並べた表にする */
	static String[][] snapshot(String[] messageRingArray, OObject[] objectRingArray, int ringCnt){
		int total = Math.min(ringCnt, messageRingArray.length);
		String[][] tabledata = new String[total][2];

		//リングバッファの古いものから順に取り出し、新しいものが上になるように並べる。
		//(ringCnt+i)%lengthだと、まだ一周していないうちは空の要素を指してしまう
		for(int i=0; i<total; i++){
			int j = (ringCnt-total+i)%messageRingArray.length;
			int i2 = total-i-1;
			tabledata[i2][0] = messageRingArray[j];
			if(objectRingArray[j]!=null){
				tabledata[i2][1] = objectRingArray[j].getShortName();
			}
		}
		return tabledata;
	}

	public void setTable(String[] messageRingArray, OObject[] objectRingArray, int ringCnt){
		String[][] tabledata = snapshot(messageRingArray, objectRingArray, ringCnt);
		
		boolean schedule = pendingData==null;
		pendingData = tabledata;
		if(!schedule) return;
		Runnable update = new Runnable() {
			public void run() {
				String[][] data = pendingData;
				pendingData = null;
				if(data==null) return;
				int scroll = scrollpane.getVerticalScrollBar().getValue();
				model.setDataVector(data, new Object[]{ text("Message"), text("Object") });
				scrollpane.getVerticalScrollBar().setValue(scroll);
			}
		};
		if(SwingUtilities.isEventDispatchThread()) update.run();
		else SwingUtilities.invokeLater(update);
	}
}

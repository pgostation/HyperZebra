import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;


/**
 * 変数表示ウィンドウ。
 *
 * グローバル変数("*"付き)と、選択したハンドラ(呼び出しスタックのフレーム)のローカル変数を表示する。
 * デバッガで一時停止している間(またはスクリプトが動いていない間)は、値の欄を編集して
 * 変数の内容を書き換えられる。
 *
 * 表示の更新はスクリプトスレッドからrefresh()で依頼され、実際の作業はEDT上で行う。
 */
public class VariableWatcher extends JDialog
{
	private static final long serialVersionUID = 3795782126509780013L;

	static VariableWatcher watcherWindow;

	private final DefaultTableModel model;
	private final JTable table;
	private final JScrollPane scrollpane;
	private final JComboBox<String> frameBox;
	private final JLabel statusLabel;
	/** 呼び出し元のフレームを選んでいるとき、そのハンドラが待っている行(停止位置の行と混同しないよう別の行に出す) */
	private final JLabel frameLineLabel;
	/** "*"とダブルクリックでの編集の説明 */
	private final JLabel hintLabel;
	private ScriptDebugger.Frame[] frames = new ScriptDebugger.Frame[0];
	/** 各行の変数を持つMemoryData(グローバル変数の行はnull) */
	private MemoryData[] rowMemData = new MemoryData[0];
	private boolean updating;
	private static volatile boolean refreshPending;

	public VariableWatcher()
	{
		super(/*PCARD.pc*/);

		//オブジェクトをウィンドウリストに登録
		setTitle(text("Variable Watcher"));
		new OWindow(this);
	    watcherWindow = this;

	    //上部: 呼び出しスタックと状態
	    JPanel top = new JPanel(new BorderLayout());
	    top.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
	    frameBox = new JComboBox<String>();
	    frameBox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(!updating) rebuild();
			}
		});
	    //狭いウィンドウでもフレーム名が全部読めるよう、一覧は項目の幅まで広げる
	    frameBox.addPopupMenuListener(new PopupMenuListener() {
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
				SwingUtilities.invokeLater(new Runnable() {
					public void run() { widenPopup(); }
				});
			}
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
			public void popupMenuCanceled(PopupMenuEvent e) {}
		});
	    top.add(frameBox, BorderLayout.NORTH);
	    frameLineLabel = new JLabel();
	    frameLineLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
	    frameLineLabel.setVisible(false);
	    top.add(frameLineLabel, BorderLayout.CENTER);
	    statusLabel = new JLabel(text("(not running)"));
	    statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
	    top.add(statusLabel, BorderLayout.SOUTH);
	    add(top, BorderLayout.NORTH);

	    hintLabel = new JLabel();
	    hintLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
	    hintLabel.setFont(hintLabel.getFont().deriveFont(hintLabel.getFont().getSize2D()-2f));
	    hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
	    add(hintLabel, BorderLayout.SOUTH);

	    //テーブルを用意
		model = new DefaultTableModel(new Object[]{ text("Name"), text("Value") }, 0) {
			private static final long serialVersionUID = 1L;
			@Override
			public boolean isCellEditable(int row, int column) {
				return column==1 && (ScriptDebugger.paused || TTalk.idle);
			}
			@Override
			public void setValueAt(Object value, int row, int column) {
				if(column==1 && row<rowMemData.length){
					String name = String.valueOf(getValueAt(row, 0));
					if(name.startsWith("*")) name = name.substring(1);
					if(!ScriptDebugger.setVariableValue(rowMemData[row], name, String.valueOf(value))){
						java.awt.Toolkit.getDefaultToolkit().beep();
						return;
					}
				}
				super.setValueAt(value, row, column);
			}
		};
		table = new JTable(model);
		table.setDefaultRenderer(Object.class, new MultiLineCellRenderer(){
			private static final long serialVersionUID = 1L;
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value,
					boolean isSelected, boolean hasFocus, int row, int column) {
				super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				if(column==0) setToolTipText(row<rowMemData.length && rowMemData[row]==null ? text("Global variable") : text("Local variable"));
				else setToolTipText(model.isCellEditable(row, column) ? text("Double-click to change the value") : null);
				return this;
			}
		});
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.getColumnModel().getColumn(0).setPreferredWidth(100);
		table.getColumnModel().getColumn(1).setPreferredWidth(200);

	    //スクロール
	    scrollpane = new JScrollPane(table);
	    add(scrollpane, BorderLayout.CENTER);

	    //ウィンドウ位置とサイズ設定
	    setBounds(0,0,420,320);
	    setLocationRelativeTo(null);
	}

	/**
	 * HyperCard's Variable Watcher has two bars a script can move (Rinaldi's GlobalMaster demo sets them): hBarLoc, the height of the
	 * variable list above the value pane, and vBarLoc, the width of the names. This watcher edits values in the table itself: vBarLoc
	 * is the names column's width, hBarLoc is kept for scripts to read back. Pixels, -1 = not set yet.
	 */
	private int hBarLoc=-1;
	int hBarLoc(){return hBarLoc>=0?hBarLoc:scrollpane.getHeight();}
	void setHBarLoc(int y){hBarLoc=Math.max(0,y);}
	private int vBarLoc=-1;
	int vBarLoc(){return vBarLoc>=0?vBarLoc:table.getColumnModel().getColumn(0).getWidth();}
	void setVBarLoc(int x){
		int w=Math.max(0,x),total=table.getColumnModel().getTotalColumnWidth();vBarLoc=w;
		table.getColumnModel().getColumn(0).setPreferredWidth(w);
		table.getColumnModel().getColumn(1).setPreferredWidth(Math.max(1,total-w));
	}

	/** 一覧(BasicComboPopup。AquaのものもBasicComboPopup)を、いちばん長いフレーム名が収まる幅にする */
	private void widenPopup(){
		Object child = frameBox.getUI().getAccessibleChild(frameBox, 0);
		if(!(child instanceof BasicComboPopup)) return;
		BasicComboPopup popup = (BasicComboPopup) child;
		if(!popup.isVisible()) return;
		JList<?> list = popup.getList();
		int width = list.getPreferredSize().width + popup.getInsets().left + popup.getInsets().right;
		if(frameBox.getItemCount() > frameBox.getMaximumRowCount()) width += UIManager.getInt("ScrollBar.width") > 0 ? UIManager.getInt("ScrollBar.width") : 16;
		if(width <= popup.getWidth()) return;
		((JPopupMenu) popup).setPopupSize(width, popup.getHeight());
	}

	private static String text(String name){
		return International.t(name);
	}

	@Override
	public void setVisible(boolean visible) {
		super.setVisible(visible);
		if(visible) refresh();
	}

	/** 表示を最新の状態にする(どのスレッドから呼んでもよい) */
	static void refresh(){
		if(watcherWindow==null) return;
		if(refreshPending) return;
		refreshPending = true;
		ScriptDebugger.runOnEdt(new Runnable() {
			public void run() {
				refreshPending = false;
				if(watcherWindow!=null && watcherWindow.isVisible()) watcherWindow.rebuild();
			}
		});
	}

	/** 旧インターフェース。引数は使わず現在の状態を表示し直す */
	public void setTable(MemoryData globalData, MemoryData memData){
		refresh();
	}

	private void rebuild(){
		if(table.isEditing()) table.getCellEditor().stopCellEditing();
		updating = true;
		try{
			ScriptDebugger.Frame[] newFrames = ScriptDebugger.snapshot();
			int selected = frameBox.getSelectedIndex();
			if(selected<0 || newFrames.length!=frames.length) selected = 0;
			frames = newFrames;
			frameBox.removeAllItems();
			for(int i=frames.length-1; i>=0; i--){
				ScriptDebugger.Frame frame = frames[i];
				//行番号は一覧に付けない(停止位置の行と並ぶと区別しにくい)。選んだフレームの行は下の欄に書く
				frameBox.addItem(frame.title());
			}
			frameBox.setEnabled(frames.length>0);
			if(frames.length==0){
				frameBox.addItem(TTalk.idle ? text("(not running)") : text("(running)"));
				selected = 0;
			}
			if(selected>=frameBox.getItemCount()) selected = 0;
			frameBox.setSelectedIndex(selected);

			MemoryData memData = null;
			ScriptDebugger.Frame selectedFrame = null;
			if(frames.length>0){
				selectedFrame = frames[frames.length-1-selected];
				memData = selectedFrame.memData;
			}
			else if(ScriptDebugger.paused){
				memData = ScriptDebugger.pausedMemData;
			}
			frameBox.setToolTipText(selectedFrame==null ? null : selectedFrame.title());
			//呼び出し元(一番上の項目以外)を選んだときは、そのハンドラが次のハンドラを呼んでいる行を書く
			if(selectedFrame!=null && selected>0 && selectedFrame.line>=0){
				String where = selectedFrame.object==null ? "" : selectedFrame.object.getShortName();
				frameLineLabel.setText(International.t("Calling from line {0} of {1}", selectedFrame.line+1, where));
				frameLineLabel.setToolTipText(frameLineLabel.getText());
				frameLineLabel.setVisible(true);
			}
			else{
				frameLineLabel.setVisible(false);
			}

			int scroll = scrollpane.getVerticalScrollBar().getValue();
			MemoryData globalData = TTalk.globalData;
			int globals = globalData==null ? 0 : globalData.nameList.size();
			int locals = memData==null ? 0 : memData.nameList.size();
			Object[][] data = new Object[globals+locals][2];
			MemoryData[] owners = new MemoryData[globals+locals];
			int row = 0;
			for(int i=0; i<globals; i++, row++){
				data[row][0] = "*"+globalData.nameList.get(i);
				data[row][1] = globalData.valueList.get(i);
				owners[row] = null;
			}
			for(int i=0; i<locals; i++, row++){
				data[row][0] = memData.nameList.get(i);
				data[row][1] = memData.valueList.get(i);
				owners[row] = memData;
			}
			rowMemData = owners;
			model.setDataVector(data, new Object[]{ text("Name"), text("Value") });
			table.getColumnModel().getColumn(0).setPreferredWidth(100);
			table.getColumnModel().getColumn(1).setPreferredWidth(200);
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					scrollpane.getVerticalScrollBar().setValue(scroll);
				}
			});

			if(ScriptDebugger.paused){
				int line = ScriptDebugger.pausedLine+1;
				String where = ScriptDebugger.pausedObject==null ? "" : ScriptDebugger.pausedObject.getShortName();
				if(ScriptDebugger.pausedOnError){
					String msg = ScriptDebugger.pausedErrorMessage;
					statusLabel.setText(International.t("Error at line {0} of {1}", line, where) + (msg==null ? "" : " : " + msg));
					statusLabel.setForeground(new Color(160, 0, 0));
				}
				else{
					statusLabel.setText(International.t("Stopped at line {0} of {1}", line, where));
					statusLabel.setForeground(UIManager.getColor("Label.foreground"));
				}
			}
			else{
				statusLabel.setText(TTalk.idle ? text("(not running)") : text("(running)"));
				statusLabel.setForeground(UIManager.getColor("Label.foreground"));
			}
			boolean editable = ScriptDebugger.paused || TTalk.idle;
			hintLabel.setText((globals>0 ? text("* = global variable") + "   " : "")
				+ (editable ? text("Double-click a value to change it.") : text("Pause the script to change values.")));
		}finally{
			updating = false;
		}
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

		//列幅に合わせて折り返した高さを行の高さにする。各列は同じ行の中で順に描かれるので、
		//最後の列を描くときにその行で一番高い値を採用する
		int width = table.getColumnModel().getColumn(column).getWidth();
		this.setSize(Math.max(1, width), 1);
		int h = this.getPreferredSize().height;
		if(column==0 || row!=rowInProgress){
			rowInProgress = row;
			rowHeightInProgress = 0;
		}
		rowHeightInProgress = Math.max(rowHeightInProgress, h);
		if(column==table.getColumnCount()-1 && table.getRowHeight(row)!=rowHeightInProgress){
			table.setRowHeight(row, rowHeightInProgress);
		}

		return this;
	}

	private int rowInProgress = -1;
	private int rowHeightInProgress;
}

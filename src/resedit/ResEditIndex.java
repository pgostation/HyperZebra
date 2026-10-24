import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Iterator;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;


/**
 * The resource editor's file window: the types of the stack's resources with their counts (XCMD/XFCN among them), opened
 * with a double-click (or Return) into the type window; Copy/Paste carry all resources of the selected types to another
 * stack's resource editor. Every change made in the type windows and the editors
 * goes into the session's working copy (ResSession); File > Save (Cmd-S) writes it back to the stack, and closing
 * this window closes all of them, asking first when there are unsaved changes.
 */
public class ResEditIndex extends JFrame
{
	private static final long serialVersionUID = -766099048104505721L;

	private JScrollPane scrollpane;
	ResEdit TypeIndexEditor;
	OStack stack;
	ResSession session;

	private String[] columnNames = {"Type", "Count"};

	public ResEditIndex(PCARDFrame parent)
	{
		super();
		stack = parent.stack;
		session = ResSession.of(stack);
		session.attach(this);

		String stackName = stack.getTitleName();
		setTitle(stackName.isEmpty() ? "Resource Editor" : "Resource Editor - "+stackName);
		getContentPane().setLayout(new BorderLayout());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosing(WindowEvent e){ requestClose(); }
		});

	    //テーブルを用意
		String[][] tabledata = { {"", ""} };

		JTable table = new JTable(tabledata, columnNames);
	    table.setEnabled(false);

	    //スクロール
	    scrollpane = new JScrollPane(table);
	    getContentPane().add("Center", scrollpane);

	    //新規タイプ作成ボタン
	    {
	    	JPanel bottomPanel = new JPanel();
	    	bottomPanel.setLayout(new FlowLayout());
	    	JButton newTypeButton = new JButton(PCARD.pc.intl.getDialogText("New Type..."));
	    	newTypeButton.addActionListener(new ActionListener(){
	    		@Override public void actionPerformed(ActionEvent e){
	    			new NewResourceTypeDialog(ResEditIndex.this);
	    		}
	    	});
	    	bottomPanel.add(newTypeButton);
	    	getContentPane().add("South", bottomPanel);
	    }

	    buildMenu();
	    setTable(session.work);

	    //ウィンドウ位置とサイズ設定
	    setBounds(0,0,560,480);
	    setLocationRelativeTo(parent);
	    setVisible(true);
	}

	//File: Save/Close, Resource: New Type/Open, Edit: Copy/Paste/Clear(種類ごと)
	private void buildMenu(){
		JMenuBar mb = new JMenuBar();
		int s = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		JMenu m = new JMenu(PCARD.pc.intl.getText("File"));
		JMenuItem mi;
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save")));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));
		mi.addActionListener(e -> session.save());
		m.addSeparator();
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Close")));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));
		mi.addActionListener(e -> requestClose());
		mb.add(m);

		m = new JMenu(PCARD.pc.intl.getText("Edit"));
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy")));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));
		mi.addActionListener(e -> copySelectedTypes());
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste")));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));
		mi.addActionListener(e -> paste());
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete")));
		mi.addActionListener(e -> clearSelectedTypes());
		mb.add(m);

		m = new JMenu(PCARD.pc.intl.getText("Resource"));
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Create New Resource")));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, s));
		mi.addActionListener(e -> new NewResourceTypeDialog(this));
		m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Open")));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));
		mi.addActionListener(e -> openSelectedTypes());
		mb.add(m);
		setJMenuBar(mb);
	}

	//閉じる: 未保存の変更があれば尋ね、セッションのウインドウをすべて閉じる
	void requestClose(){
		if(!session.confirmCloseAll(this)) return;
		dispose();
	}

	JTable table;
	String[][] tabledata;

	private java.util.List<String> selectedTypes(){
		java.util.List<String> types = new ArrayList<String>();
		if(table==null) return types;
		for(int viewRow : table.getSelectedRows()){
			types.add(tabledata[table.convertRowIndexToModel(viewRow)][0]);
		}
		return types;
	}

	private void openSelectedTypes(){
		for(String type : selectedTypes()) openTypeEditor(type);
	}

	//選んだ種類のリソースをすべて消す(作業用コピー上で。保存するまでスタックには影響しない)
	private void clearSelectedTypes(){
		java.util.List<String> types = selectedTypes();
		if(types.isEmpty()) return;
		String message = PCARD.pc.intl.getDialogText("Delete all resources of type “%1”?").replace("%1", String.join(", ", types));
		new GDialog(this, message, null, PCARD.pc.intl.getDialogText("Cancel"), null, "OK");
		if(!GDialog.clicked.equals("OK")) return;
		for(String type : types){
			for(Rsrc.rsrcClass r : new ArrayList<Rsrc.rsrcClass>(session.work.rsrcIdMap.values())){
				if(r.type.equals(type)) session.work.deleteResource(type, r.id);
			}
			if(Rsrc.isExternalMacType(type)){
				for(Rsrc.xcmdClass x : session.work.externalsOfType(type)) session.work.deleteExternal(x);
			}
		}
		setTable(session.work);
		session.refreshTypeWindows(this);
	}

	//選んだ種類のリソースをすべてコピーする(ほかのスタックのリソースエディタにペーストできる)
	private void copySelectedTypes(){
		java.util.List<String> types = selectedTypes();
		java.util.List<Rsrc.rsrcClass> resources = new ArrayList<Rsrc.rsrcClass>();
		java.util.List<Rsrc.xcmdClass> externals = new ArrayList<Rsrc.xcmdClass>();
		for(Rsrc.rsrcClass r : session.work.rsrcIdMap.values()) if(types.contains(r.type)) resources.add(r);
		for(String type : types) if(Rsrc.isExternalMacType(type)) externals.addAll(session.work.externalsOfType(type));
		ResClipboard.copy(session.work, resources, externals);
	}

	//コピーしたリソースを(ほかのスタックのものも)このスタックの作業用コピーに足す
	private void paste(){
		java.util.List<ResClipboard.Pasted> pasted = ResClipboard.paste(this, session.work);
		if(pasted.isEmpty()) return;
		java.util.List<String> types = new ArrayList<String>();
		for(ResClipboard.Pasted p : pasted) if(!types.contains(p.type())) types.add(p.type());
		setTable(session.work);
		session.refreshTypeWindows(this);
		table.clearSelection();
		for(int row=0; row<tabledata.length; row++){
			if(types.contains(tabledata[row][0])){
				int viewRow = table.convertRowIndexToView(row);
				table.addRowSelectionInterval(viewRow, viewRow);
			}
		}
	}

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
		//XCMD/XFCN(PowerPC版はxcmd/xfcn)はほかのリソースと別に持っているので、ここで種類に加える
		for(Rsrc.xcmdClass x : rsrc.getXcmdResources()){
			String type = Rsrc.externalMacType(x);
			int i = typeList.indexOf(type);
			if(i>=0) countList.set(i, countList.get(i)+1);
			else{
				typeList.add(type);
				countList.add(1);
			}
		}

		java.util.List<String> selected = selectedTypes();

		tabledata = new String[typeList.size()][2];

		int i;
		for(i=0; i<typeList.size(); i++){
			tabledata[i][0] = typeList.get(i);
			tabledata[i][1] = Integer.toString(countList.get(i));
		}

		table = new JTable(tabledata, columnNames){
			private static final long serialVersionUID = 1L;
			@Override public boolean isCellEditable(int row, int column){ return false; }
		};
	    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
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
		        openTypeEditor(tabledata[row][0]);
		      }
		    }
		  }
		});
		//Return=開く、Delete/BackSpace=その種類のリソースをすべて消去
		table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "resOpen");
		table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "resClear");
		table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "resClear");
		table.getActionMap().put("resOpen", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(ActionEvent e){ openSelectedTypes(); }
		});
		table.getActionMap().put("resClear", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(ActionEvent e){ clearSelectedTypes(); }
		});

	    Rectangle rect = scrollpane.getBounds();
	    int scroll = scrollpane.getVerticalScrollBar().getValue();
	    getContentPane().remove(scrollpane);
	    scrollpane = new JScrollPane(table);
	    getContentPane().add("Center", scrollpane);
	    scrollpane.getVerticalScrollBar().setValue(scroll);
	    scrollpane.setBounds(rect);
	    for(int row=0; row<tabledata.length; row++){
	    	if(selected.contains(tabledata[row][0])){
	    		int viewRow = table.convertRowIndexToView(row);
	    		table.addRowSelectionInterval(viewRow, viewRow);
	    	}
	    }
	    validate();
	    repaint();
	}

	//指定した種類のリソースエディタを開く。閉じたときにこの一覧を
	//更新し直す(「新規タイプ…」で作った直後の種類が、最初のリソースを
	//1件作った時点で一覧に反映されるようにするため)。
	void openTypeEditor(String type){
		//既に開いていれば前面へ
		for(java.awt.Window w : session.windows){
			if(w instanceof ResTypeEditor && ((ResTypeEditor)w).type.equals(type) && ((ResTypeEditor)w).object==null){
				w.toFront();
				return;
			}
		}
		ResEdit editor = new ResEdit(stack.pcard, type, null);
		if(editor.child != null){
			editor.child.addWindowListener(new WindowAdapter(){
				@Override public void windowClosed(WindowEvent e){
					if(isDisplayable()) setTable(session.work);
				}
			});
			editor.child.toFront();
			editor.child.requestFocus();
		}
	}
}


//「新規タイプ…」ダイアログ。4文字のリソースタイプを直接入力するか、
//よく使う既定の種類から選ぶ(JComboBoxをeditable=trueにすることで、
//プルダウンからの選択と直接入力の両方を1つの部品で実現している)。
class NewResourceTypeDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	ResEditIndex owner;
	JComboBox<String> typeCombo;

	//よく使うリソースタイプ。ICON/CURS/PICTは、このアプリ内部では
	//グラフィックエディタが使えるよう別名(icon/cursor/picture)で
	//扱われるため、選ばれたときにmapRawTypeToInternalで変換する。
	private static final String[] PRESET_TYPES = {
		"ICON", "cicn", "CURS", "crsr", "PICT", "PAT#", "ppat",
		"STR#", "STR ", "TEXT", "snd ", "clut", "vers",
		"FONT", "NFNT", "MENU", "WIND", "DLOG", "DITL", "XCMD", "XFCN"
	};

	NewResourceTypeDialog(ResEditIndex owner){
		super(owner, PCARD.pc.intl.getDialogText("New Resource Type"), true);
		this.owner = owner;
		getContentPane().setLayout(new BorderLayout());

		JPanel topPanel = new JPanel();
		topPanel.setLayout(new FlowLayout());
		JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Type (4 characters):"));
		topPanel.add(label);

		typeCombo = new JComboBox<String>(PRESET_TYPES);
		typeCombo.setEditable(true);
		typeCombo.setSelectedItem("");
		typeCombo.setPreferredSize(new Dimension(100, typeCombo.getPreferredSize().height));
		topPanel.add(typeCombo);
		getContentPane().add("Center", topPanel);

		JPanel bottomPanel = new JPanel();
		bottomPanel.setLayout(new FlowLayout());
		JButton cancelButton = new JButton(PCARD.pc.intl.getDialogText("Cancel"));
		cancelButton.setActionCommand("Cancel");
		cancelButton.addActionListener(this);
		bottomPanel.add(cancelButton);
		JButton okButton = new JButton(PCARD.pc.intl.getDialogText("OK"));
		okButton.setActionCommand("OK");
		okButton.addActionListener(this);
		bottomPanel.add(okButton);
		getContentPane().add("South", bottomPanel);
		getRootPane().setDefaultButton(okButton);

		setSize(320, 120);
		setLocationRelativeTo(owner);
		setVisible(true);
	}

	//real Macのリソースタイプは常に4文字。短い入力は末尾に空白を
	//補って4文字にし(例:"snd"→"snd "、"vers"はそのまま)、長い入力は
	//先頭4文字に切り詰める。
	static String normalizeType(String raw){
		if(raw==null) return null;
		if(raw.length()>4) raw = raw.substring(0,4);
		while(raw.length()<4) raw = raw+" ";
		return raw;
	}

	//このアプリの内部では、ICON/CURS/PICT/SND/PAT#は本物のMacタイプコード
	//(ICON/CURS/PICT/SND/PAT#)をそのまま内部の型名としても使う。それ以外
	//(cicn/crsr/ppat/その他)も同様に本物のMacタイプコードをそのまま使う。
	static String mapRawTypeToInternal(String raw){
		if(raw.trim().equalsIgnoreCase("snd")) return "SND";
		return raw;
	}

	@Override
	public void actionPerformed(ActionEvent e){
		if(e.getActionCommand().equals("OK")){
			Object selected = typeCombo.getEditor().getItem();
			String raw = selected==null ? "" : selected.toString();
			if(raw.length()==0){
				new GDialog(this.owner, PCARD.pc.intl.getDialogText("Please enter a 4-character resource type."),
						null, "OK", null, null);
				return;
			}
			String normalized = normalizeType(raw);
			String internalType = mapRawTypeToInternal(normalized);
			//このダイアログが閉じて一覧が前面に戻ったあとで開く(先に開くと新しい種類のウインドウが一覧の後ろに隠れる)
			dispose();
			SwingUtilities.invokeLater(() -> owner.openTypeEditor(internalType));
			return;
		}
		dispose();
	}
}

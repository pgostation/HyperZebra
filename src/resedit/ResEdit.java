import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;


public class ResEdit {
	static ResEdit nulleditor;
	ResTypeEditor child;

	public ResEdit(PCARDFrame pc, String type, OObject object){
		if(pc==null){
			pc = new PCARDFrame();
			pc.stack = new OStack(pc);
		}

		//各Typeごとに開いて選択する
		if(type.equals("ICON") || type.equals("cicn") || type.equals("PICT") || type.equals("Pdat") || type.equals("CURS")
				|| type.equals("crsr") || type.equals("PAT#") || type.equals("ppat")){
			child = new IconTypeEditor(pc, type, object);
		}
		else if(type.equals("SND")){
			child = new SoundTypeEditor(pc, object);
		}
		else if(type.equals("STR ") || type.equals("STR#") || type.equals("TEXT")){
			child = new StrTypeEditor(pc, type, object);
		}
		else if(Rsrc.isExternalMacType(type) && object==null){
			child = new ExternalTypeEditor(pc, type);
		}
		else{
			child = new OtherTypeEditor(pc, type, object);
		}
	}

	static boolean isPictureType(String type){
		return type.equals("ICON") || type.equals("cicn") || type.equals("PICT") || type.equals("Pdat") || type.equals("CURS")
			|| type.equals("crsr") || type.equals("PAT#") || type.equals("ppat");
	}

	static String text(String english){ return ResSession.text(english); }
	static String dialogText(String english){ return ResSession.dialogText(english); }

	//ファイルを複製する(リソースファイルのコピー・複製・ペースト用)
	static void copyFile(File from, File to) throws IOException {
		try(FileChannel src = new FileInputStream(from).getChannel();
			FileChannel dest = new FileOutputStream(to).getChannel()){
			src.transferTo(0, src.size(), dest);
		}
	}
}

/**
 * 種類ごとの一覧ウインドウに使う共通の土台。一覧の表示は
 * サブクラス(アイコンの格子、または表)が受け持ち、選択・メニュー(新規/開く/
 * 情報/複製/カット/コピー/ペースト/消去/すべて選択/保存/閉じる)・キーボード操作
 * (Return=開く、Delete=消去)はここで共通に扱う。変更はすべてResSessionの作業用
 * コピー(rsrc)に対して行い、保存(Cmd-S)で初めてスタックに書き戻される。
 */
class ResTypeEditor extends JFrame {
	private static final long serialVersionUID = 1L;
	ResTypeEditor editor;
	String type;
	OObject object;
	PCARDFrame pcard;
	ResSession session;
	//編集対象(作業用コピー)。スタック本来のRsrcには保存するまで触れない
	Rsrc rsrc;

	//複数スタックを同時に開いたときに区別できるよう、タイトルにスタック名を付ける
	String withStackName(String title){
		String stackName = pcard.stack.getTitleName();
		return stackName.isEmpty() ? title : title+" - "+stackName;
	}
	JScrollPane scrollpane;
	JPanel contpane;
	Rsrc.rsrcClass[] rsrcAry;
	//選択中のリソースID(選んだ順)
	LinkedHashSet<Integer> selection = new LinkedHashSet<Integer>();
	int scroll;
	REMenu menu;

	public ResTypeEditor(PCARDFrame pc, String type, OObject object){
		super();
		editor = this;
		this.type = type;
		this.object = object;
		this.pcard = pc;

		session = ResSession.of(pcard.stack);
		rsrc = session.work;
		session.attach(this);

		int w=560;
		int h=480;

		//frame
		setTitle(withStackName(type));
		getContentPane().setLayout(new BorderLayout());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				requestClose();
			}
		});

		//scroll
		{
			scrollpane = new JScrollPane();
			scrollpane.setName("JScrollPane");
			scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			scrollpane.getVerticalScrollBar().setUnitIncrement(12);
			getContentPane().add(scrollpane, BorderLayout.CENTER);

			contpane = new JPanel(){
				private static final long serialVersionUID = 1L;
				@Override protected void paintComponent(Graphics g){
					super.paintComponent(g);
					paintMarquee(g);
				}
			};
			contpane.setOpaque(true);
			contpane.setBackground(new Color(219,223,230));
			scrollpane.setViewportView(contpane);
		}

		//アイコン選択ダイアログのときはok buttonを表示
		if(object!=null){
			JPanel okPanel = new JPanel();
			okPanel.setLayout(new FlowLayout());

			JButton jbtn = new JButton("OK");
			jbtn.addActionListener(new ResOkButtonListener());
			okPanel.add(jbtn);
			getContentPane().add(okPanel, BorderLayout.SOUTH);
		}

		if(pc!=null){
			setBounds(pc.getX()+pc.getWidth()/2-w/2,pc.getY()+pc.getHeight()/2-h/2,w,h);
		}
		else{
			setBounds(0,0,w,h);
		}

		//menu
		menu = new REMenu(this);
		installKeys(getRootPane());
	}

	//Return/Enterで開く、Delete/BackSpaceで消去
	void installKeys(JComponent c){
		InputMap im = c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "resOpen");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "resClear");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "resClear");
		c.getActionMap().put("resOpen", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(ActionEvent e){ openSelected(); }
		});
		c.getActionMap().put("resClear", new AbstractAction(){
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(ActionEvent e){ clearSelected(); }
		});
	}

	void paintMarquee(Graphics g){ }

	//閉じる: このセッションの最後のウインドウで未保存の変更があれば保存するか尋ねる
	void requestClose(){
		if(session!=null && !session.confirmClose(this)) return;
		maybeExit();
	}

	//保存(Cmd-S): 作業用コピーをスタックに書き戻す
	boolean save(){
		return session.save();
	}

	//スタックのリソースを読み直して一覧を作り直す(選択はIDで保つ)
	void open(PCARDFrame pc, int in_scroll){
		while(contpane.getComponentCount()>0){
			contpane.remove(contpane.getComponent(0));
		}

		if(object==null){
			rsrcAry = ownResources();
			Arrays.sort(rsrcAry, new DataComparator());
		}else{
			//リソースをソートしてリストに保存(使用中スタックの分も含む)
			int number = rsrc.getRsrcCount(type);
			int numberall = rsrc.getRsrcCountAll(type);

			rsrcAry = new Rsrc.rsrcClass[numberall];
			int i=0;
			for(i=0; i<number; i++){
				Rsrc.rsrcClass r = rsrc.getRsrcByIndex(type, i);
				rsrcAry[i] = r;
			}
			for(int j=0; j<pc.stack.usingStacks.size(); j++){
				OStack rsrcstack = pc.stack.usingStacks.get(j);
				Iterator<Rsrc.rsrcClass> it = rsrcstack.rsrc.rsrcIdMap.values().iterator();
				while(it.hasNext()){
					Rsrc.rsrcClass r = it.next();
					if(r.type.equals(type)){
						String path1 = (rsrcstack.file.getParent()+File.separatorChar+r.filename);
						String path2 = rsrc.getFilePathAll(r.id, type);
						if(path1.equals(path2))
						{
							rsrcAry[i] = r;
							i++;
						}
					}
				}
			}
			Arrays.sort(rsrcAry, new DataComparator());
		}
		//無くなったリソースは選択から外す
		for(Iterator<Integer> it = selection.iterator(); it.hasNext();){
			if(indexOf(it.next())<0) it.remove();
		}
	}

	//このスタックのこの種類のリソース(並べ替えは呼び出し側)
	Rsrc.rsrcClass[] ownResources(){
		int number = rsrc.getRsrcCount(type);
		Rsrc.rsrcClass[] list = new Rsrc.rsrcClass[number];
		for(int i=0; i<number; i++) list[i] = rsrc.getRsrcByIndex(type, i);
		return list;
	}

	//リソースのファイル(作業用コピーで編集済みならそちら)
	String filePathOf(int id){
		return rsrc.getFilePathAll(id, type);
	}

	void reload(){
		refreshList();
		//種類一覧(件数)も追従させる
		if(session!=null) session.refreshWindows();
	}

	//ほかのウインドウでの変更(ペーストなど)に一覧を合わせる
	void refreshList(){
		if(scrollpane==null) return;
		scroll = scrollpane.getVerticalScrollBar().getValue();
		open(pcard, scroll);
	}

	int indexOf(int id){
		if(rsrcAry==null) return -1;
		for(int i=0; i<rsrcAry.length; i++) if(rsrcAry[i].id==id) return i;
		return -1;
	}

	@SuppressWarnings("rawtypes")
	class DataComparator implements Comparator
	{
		@Override
		public int compare(Object o1, Object o2){
			return ((Rsrc.rsrcClass)o1).id - ((Rsrc.rsrcClass)o2).id;
		}
	}

	//----- 選択 -----

	int[] selectedIds(){
		int[] ids = new int[selection.size()];
		int i=0;
		for(Integer id : selection) ids[i++] = id;
		return ids;
	}

	//最後に選んだ(先頭の)リソースID。無ければ0
	int primarySelectedId(){
		return selection.isEmpty() ? 0 : selection.iterator().next();
	}

	void selectIds(int[] ids){
		selection.clear();
		for(int id : ids) if(indexOf(id)>=0) selection.add(id);
		showSelection();
	}

	void selectAll(){
		selection.clear();
		if(rsrcAry!=null) for(Rsrc.rsrcClass r : rsrcAry) selection.add(r.id);
		showSelection();
	}

	//一覧の見た目を選択に合わせる(サブクラスが実装)
	void showSelection(){ }

	//----- メニュー動作(サブクラスが種類に応じて上書きする) -----

	void createNewResource(){ }

	void openResource(int id){ }

	void openSelected(){
		for(int id : selectedIds()) openResource(id);
	}

	//選択中のリソースを作業用コピーから消す(スタックのファイルには保存まで触れない)
	void clearSelected(){
		int[] ids = selectedIds();
		if(ids.length==0) return;
		for(int id : ids) rsrc.deleteResource(type, id);
		selection.clear();
		reload();
	}

	//種類ごとのファイル名は「タイプ_ID拡張子」
	String fileNameFor(int id, String ext){
		return type+"_"+id+ext;
	}

	String extensionOf(String filename){
		int dot = filename==null ? -1 : filename.lastIndexOf('.');
		return dot>=0 ? filename.substring(dot) : "";
	}

	//選択中のリソースを新しいIDで複製する(Cmd-D)
	void duplicateSelected(){
		int[] ids = selectedIds();
		if(ids.length==0) return;
		ArrayList<Integer> made = new ArrayList<Integer>();
		for(int id : ids){
			Rsrc.rsrcClass r = rsrc.getResource1(id, type);
			if(r==null) continue;
			int newId = rsrc.getNewResourceId(type, id+1);
			String newFileName = rsrc.freeFileName(fileNameFor(newId, extensionOf(r.filename)), null);
			String srcPath = rsrc.getFilePathAll(id, type);
			try {
				if(srcPath!=null && new File(srcPath).exists()) ResEdit.copyFile(new File(srcPath), rsrc.fileToWrite(newFileName));
			} catch(IOException e) {
				e.printStackTrace();
				continue;
			}
			rsrc.addResource(newId, type, r.name, newFileName, Integer.toString(r.hotsporleft),
				Integer.toString(r.hotsportop), r.optionInfo);
			made.add(newId);
		}
		reload();
		int[] sel = new int[made.size()];
		for(int i=0; i<sel.length; i++) sel[i] = made.get(i);
		selectIds(sel);
	}

	//リソース情報(Cmd-I): IDと名前を変える
	void showResourceInfo(){
		int id = primarySelectedId();
		Rsrc.rsrcClass r = id==0 ? null : rsrc.getResource1(id, type);
		if(r==null) return;
		new ResourceInfoDialog(this, r.type, r.id, r.name);
	}

	//リソース情報で選んだIDと名前が使えないわけ(使えればnull)
	String infoProblem(int oldId, int newId, String newName){
		if(newId!=oldId && rsrc.getResource1(newId, type)!=null) return ResEdit.dialogText("A resource with this ID already exists.");
		return null;
	}

	//リソース情報のIDと名前を作業用コピーに入れる
	void applyInfo(int oldId, int newId, String newName){
		Rsrc.rsrcClass r = rsrc.getResource1(oldId, type);
		if(r==null) return;
		rsrc.setIdAndName(r, newId, newName);
	}

	//----- カット/コピー/ペースト(ほかのスタックのリソースエディタへも) -----

	void copySelected(){
		ArrayList<Rsrc.rsrcClass> list = new ArrayList<Rsrc.rsrcClass>();
		for(int id : selectedIds()){
			Rsrc.rsrcClass r = rsrc.getResource1(id, type);
			if(r!=null) list.add(r);
		}
		ResClipboard.copy(rsrc, list, new ArrayList<Rsrc.xcmdClass>());
	}

	//クリップボードのリソースを作業用コピーに足し、この種類のものを選ぶ
	void paste(){
		java.util.List<ResClipboard.Pasted> pasted = ResClipboard.paste(this, rsrc);
		if(pasted.isEmpty()) return;
		ArrayList<Integer> mine = new ArrayList<Integer>();
		for(ResClipboard.Pasted p : pasted) if(p.type().equals(type)) mine.add(p.id());
		reload();
		session.refreshTypeWindows(this);
		int[] sel = new int[mine.size()];
		for(int i=0; i<sel.length; i++) sel[i] = mine.get(i);
		if(sel.length>0) selectIds(sel);
	}

	//IDを変えた後の一覧の更新(サブクラスは一覧の表示名を更新する)
	void resourceChanged(int oldId, int newId){
		reload();
		selectIds(new int[]{newId});
	}

	class ResOkButtonListener implements ActionListener
	{
		@Override
		public void actionPerformed(ActionEvent e) {
			AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {
			//ボタンが参照するアイコンがスタックに無いままにならないよう、選ぶ前に保存する
			if(session.isDirty() && !session.save()) return;
			if(type.equals("ICON") && object!=null){
				((OButton)object).setIcon(primarySelectedId());
			}
			editor.dispose();

		} finally { AuthoringUndo.commit(before); }
	}
	}

	void maybeExit() {
		editor.dispose();
		object = null;
		scrollpane = null;
		contpane = null;
		rsrcAry = null;

		System.gc();
		editor = null;
	}
}


/** 画像系(ICON/cicn/PICT/Pdat/CURS/crsr/PAT#/ppat)を格子状に一覧表示する。 */
class IconTypeEditor extends ResTypeEditor {
	private static final long serialVersionUID = 1L;
	//ドラッグで囲んで選ぶ矩形(contpane座標)。無いときはnull
	Rectangle marquee;
	private Point marqueeStart;
	private LinkedHashSet<Integer> marqueeBase;

	public IconTypeEditor(PCARDFrame pc, String type, OObject object) {
		super(pc, type, object);

		if(object!=null){
			selection.add(((OButton)object).icon);
		}

		contpane.setLayout(new FlowLayout(FlowLayout.LEFT));
		IconBackListener back = new IconBackListener();
		contpane.addMouseListener(back);
		contpane.addMouseMotionListener(back);
		contpane.setFocusable(true);
		installKeys(contpane);
		installArrowKeys();

		scrollpane.getVerticalScrollBar().setUnitIncrement(133);
		addComponentListener(new ComponentAdapter(){
			@Override public void componentResized(ComponentEvent e){ layoutGrid(); }
		});

		open(pcard, 0);

		new DropTarget(this, new IconDropListener());

		//setVisible()より前にtoFront()を呼んでもまだウインドウが実体化して
		//いないため効果が無く、新規リソースタイプ作成時などにウインドウが
		//前面に来ないことがあった。setVisible()を先に呼んでからtoFront()する。
		setVisible(true);
		toFront();
	}

	//矢印キーで選択を隣へ移す(Shiftで範囲を広げる)
	private void installArrowKeys(){
		int[][] keys = {{KeyEvent.VK_LEFT,-1},{KeyEvent.VK_RIGHT,1},{KeyEvent.VK_UP,-columns()},{KeyEvent.VK_DOWN,columns()}};
		for(int shift=0; shift<2; shift++){
			for(int[] k : keys){
				final int key = k[0];
				final boolean extend = shift==1;
				String name = "resArrow"+key+extend;
				contpane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
					KeyStroke.getKeyStroke(key, extend ? InputEvent.SHIFT_DOWN_MASK : 0), name);
				contpane.getActionMap().put(name, new AbstractAction(){
					private static final long serialVersionUID = 1L;
					@Override public void actionPerformed(ActionEvent e){ moveSelection(key, extend); }
				});
			}
		}
	}

	private int columns(){
		int n = (scrollpane==null ? getWidth() : scrollpane.getViewport().getWidth())/133;
		return n<=0 ? 1 : n;
	}

	private void moveSelection(int key, boolean extend){
		if(rsrcAry==null || rsrcAry.length==0) return;
		int delta = key==KeyEvent.VK_LEFT ? -1 : key==KeyEvent.VK_RIGHT ? 1 : key==KeyEvent.VK_UP ? -columns() : columns();
		int cur = indexOf(primarySelectedId());
		int next = cur<0 ? 0 : Math.max(0, Math.min(rsrcAry.length-1, cur+delta));
		int id = rsrcAry[next].id;
		if(extend){
			selection.remove(id);
			LinkedHashSet<Integer> newSel = new LinkedHashSet<Integer>();
			newSel.add(id);
			newSel.addAll(selection);
			selection = newSel;
		}
		else{
			selection.clear();
			selection.add(id);
		}
		showSelection();
		scrollToIndex(next);
	}

	private void scrollToIndex(int index){
		if(index<0 || index>=contpane.getComponentCount()) return;
		contpane.scrollRectToVisible(contpane.getComponent(index).getBounds());
	}

	private void layoutGrid(){
		if(scrollpane==null || contpane==null) return;
		int n = columns();
		int len = rsrcAry==null ? 0 : rsrcAry.length;
		int width = scrollpane.getViewport().getWidth();
		contpane.setPreferredSize(new Dimension(Math.max(133, width), 133*((len+n-1)/n)+20));
		contpane.revalidate();
	}

	@Override
	void open(PCARDFrame pc, int in_scroll){
		super.open(pc, in_scroll);
		LineBorder border = new LineBorder(Color.GRAY);
		IconButtonListener listener = new IconButtonListener();

		scroll = in_scroll;

		int number = rsrcAry.length;
		setTitle(withStackName(type+"("+number+")"));

		int first = indexOf(primarySelectedId());
		for(int i=0; i<number; i++){
			Rsrc.rsrcClass r = rsrcAry[i];
			JPanel panel = new JPanel();
			panel.setLayout(new BorderLayout());
			panel.setPreferredSize(new Dimension(128,128));
			panel.setBackground(new Color(219,223,230));

			IconButton iconlabel = new IconButton(this);
			iconlabel.setName(Integer.toString(r.id));
			iconlabel.setPreferredSize(new Dimension(126,112));
			if(object!=null&&!rsrc.rsrcIdMap.containsKey(type+r.id)){
				iconlabel.setEnabled(false);
			}
			iconlabel.setBorder(border);
			iconlabel.setHorizontalAlignment(SwingConstants.CENTER);
			iconlabel.addMouseListener(listener);
			//iconはすべて読み込めないので後で表示する
			panel.add("North", iconlabel);

			JLabel label = new JLabel(r.id+" "+r.name);
			label.setPreferredSize(new Dimension(128,16));
			panel.add("South", label);

			contpane.add(panel);
		}
		if(first>=0 && in_scroll==0) scroll = 133*(first/columns());
		showSelection();
		layoutGrid();

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				if(scrollpane==null) return;
				scrollpane.getVerticalScrollBar().setValue(scroll);
				scrollpane.revalidate();
				scrollpane.repaint();
			}
		});
	}

	@Override
	void showSelection(){
		int primary = primarySelectedId();
		for(int i=0; i<contpane.getComponentCount(); i++){
			IconButton b = buttonAt(i);
			if(b==null) continue;
			int id = Integer.valueOf(b.getName());
			//選択がひと目で分かるよう、選択中は濃い青の枠(先頭はさらに濃く)
			if(!selection.contains(id)) b.setBorder(new LineBorder(Color.GRAY));
			else b.setBorder(id==primary ? getSelectedBorder(32,64,192) : getSelectedBorder(64,96,208));
		}
		contpane.repaint();
	}

	IconButton buttonAt(int index){
		Component c = contpane.getComponent(index);
		if(!(c instanceof JPanel) || ((JPanel)c).getComponentCount()==0) return null;
		Component b = ((JPanel)c).getComponent(0);
		return b instanceof IconButton ? (IconButton)b : null;
	}

	//エディタで絵や名前が変わったときの表示更新(IDは同じ)
	void updateContent(int id){
		int number = rsrcAry.length;
		setTitle(withStackName(type+"("+number+")"));

		for(int i=0; i<contpane.getComponentCount(); i++){
			IconButton b = buttonAt(i);
			if(b==null || !b.getName().equals(Integer.toString(id))) continue;
			Rsrc.rsrcClass r = rsrcAry[i];
			b.setIcon(null);
			b.repaint();
			JPanel panel = (JPanel)contpane.getComponent(i);
			for(int j=0; j<panel.getComponentCount(); j++){
				if(panel.getComponent(j) instanceof JLabel) ((JLabel)panel.getComponent(j)).setText(r.id+" "+r.name);
			}
		}
	}

	@Override
	void createNewResource(){
		int rsrcid = rsrc.getNewResourceId(type);
		//Pdatは68KのColorizeHCがそのまま読むので、最初からヘッダ無しのPICTデータ(.data)で置く
		boolean pdat = type.equals("Pdat");
		String newFileName = fileNameFor(rsrcid, pdat ? ".data" : ".png");
		File newFile = rsrc.fileToWrite(newFileName);
		//PAT#/ppatとも、新規作成時は8x8のパターン1枚分から始める
		//(PAT#は8*N x 8の横並び表現なのでN=1のとき8x8と同じ)
		int size = type.equals("PAT#") || type.equals("ppat") ? 8 : 32;
		BufferedImage bi = new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
		try {
			if(pdat) Files.write(newFile.toPath(), HCResourceExport.pict(bi));
			else ImageIO.write(bi,"png",newFile);
		} catch (IOException e1) {
			e1.printStackTrace();
			return;
		}
		rsrc.addResource(rsrcid, type, "", newFileName);
		selection.clear();
		selection.add(rsrcid);
		reload();
		openResource(rsrcid);
	}

	@Override
	void openResource(int id){
		if(rsrc.rsrcIdMap.containsKey(type+id)){
			//開く
			new IconEditor(editor, rsrc, type, id);
		}else{
			//このスタックのリソースでない場合はコピーを作るか尋ねる
			new GDialog(editor, PCARD.pc.intl.getDialogText("This resource is not in this stack. Make a copy?"), null, "Cancel", "OK", null);
			if(GDialog.clicked.equals("OK")){
				Rsrc.rsrcClass r = rsrc.getResourceAll(id, type);
				if(r!=null){
					String srcFilePath = rsrc.getFilePathAll(id, type);
					String newFileName = new File(srcFilePath).getName();
					try {
						ResEdit.copyFile(new File(srcFilePath), rsrc.fileToWrite(newFileName));
					} catch (Exception e1) {
						e1.printStackTrace();
					}
					rsrc.addResource(r.id, type, r.name, newFileName, Integer.toString(r.hotsporleft),
						Integer.toString(r.hotsportop), r.optionInfo);
					reload();
					new IconEditor(editor, rsrc, type, id);
				}
				else{
					System.out.println("resource not found.");
				}
			}
		}
	}

	static Border getSelectedBorder(int r, int g, int b){
		Color color1 = new Color(r,g,b);
		Color color2 = new Color((255+r*3)/4, (255+g*3)/4, (255+b*3)/4);
		Color color3 = new Color((255+r)/2, (255+g)/2, (255+b)/2);
		CompoundBorder border1 = new CompoundBorder(
				new LineBorder(color3),
				new LineBorder(color2));
		CompoundBorder border2 = new CompoundBorder(
				border1,
				new LineBorder(color1));

		return border2;
	}

	@Override
	void paintMarquee(Graphics g){
		if(marquee==null) return;
		g.setColor(new Color(96,96,128,48));
		g.fillRect(marquee.x, marquee.y, marquee.width, marquee.height);
		g.setColor(new Color(96,96,128));
		g.drawRect(marquee.x, marquee.y, marquee.width, marquee.height);
	}


	class IconButton extends JButton
	{
		private static final long serialVersionUID = 1L;
		BufferedImage bi;
		IconTypeEditor owner;

		IconButton(IconTypeEditor owner){
			this.owner = owner;
		}

		@Override
	    protected void paintComponent(Graphics g) {
			g.setColor(Color.WHITE);
			g.fillRect(0,0,128,128);
	    	if(getIcon()==null){
	    		BufferedImage bi = rsrc.getImage(Integer.valueOf(this.getName()),type);
				int w = 124;
				int h = 110;
				if(bi!=null && (bi.getWidth()<=w && bi.getHeight()<=h))
				{
					//そのままの大きさ
					setIcon(new ImageIcon(bi));
	    			bi.flush();
	    			setAlignmentX(CENTER_ALIGNMENT);
	    	    	super.paintComponent(g);
	    	    	return;
		    	}
				if(bi!=null && (bi.getWidth()>w || bi.getHeight()>h)){
					//縮小表示
					float rate = (float)w/bi.getWidth();
					if((float)h/bi.getHeight() < rate){
						rate = (float)h/bi.getHeight();
					}
					int nw = (int)(rate * bi.getWidth());
					int nh = (int)(rate * bi.getHeight());

					//3倍の大きさの画像も用意
					if(bi.getWidth()>nw*3){
						this.bi = new BufferedImage(nw*3,nh*3,BufferedImage.TYPE_INT_ARGB);
						Graphics2D g1 = (Graphics2D)this.bi.getGraphics();
						g1.drawImage(bi, 0, 0, nw*3, nh*3, 0, 0, bi.getWidth(), bi.getHeight(), this);
					}else{
						this.bi = bi;
					}

					BufferedImage newbi = new BufferedImage(nw,nh,BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = (Graphics2D)newbi.getGraphics();
					g2.drawImage(bi, 0, 0, nw, nh, 0, 0, bi.getWidth(), bi.getHeight(), this);
					bi.flush();
					bi = newbi;

					//後から高画質にする
					if(updateThread==null || !updateThread.isAlive()){
						updateThread = new lateUpdateThread();
						updateThread.setButton(this);
						updateThread.start();
					}else{
						updateThread.setButton(this);
					}
				}
				if(bi==null){
	    			bi = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
	    			Graphics g2 = bi.getGraphics();
	    			g2.setColor(Color.RED);
	    			g2.drawLine(0,0,32,32);
	    			g2.drawLine(32,0,0,32);
	    		}
    			setIcon(new ImageIcon(bi));
    			bi.flush();
    			setAlignmentX(CENTER_ALIGNMENT);
	    	}
	    	super.paintComponent(g);

			if(updateThread!=null && !updateThread.isAlive() && updateList.size()>0){
				updateThread = new lateUpdateThread();
				updateThread.start();
			}
	    }
	}

	lateUpdateThread updateThread;
	ArrayList<IconButton> updateList = new ArrayList<IconButton>();

	class lateUpdateThread extends Thread
	{
		void setButton(IconButton button){
			updateList.add(button);
		}

		public void run(){
			//高画質にする
			setPriority(MIN_PRIORITY);

			try{
				sleep(100);
			} catch (InterruptedException e) {
		        this.interrupt();
			}

			for(int i=0; i<updateList.size(); i++){
				IconButton button = updateList.get(i);

				try{
					sleep(10);
				} catch (InterruptedException e) {
			        this.interrupt();
				}

				//画面上に見えているか？
				JScrollPane sp = button.owner.scrollpane;
				if(sp==null || button.getParent()==null || button.bi==null){
					updateList.remove(i);
					i--;
					continue;
				}
				int top = sp.getVerticalScrollBar().getValue();
				int bottom = top + sp.getHeight();
				if(button.getParent().getBounds().y + button.getBounds().height > top &&
						button.getParent().getBounds().y < bottom )
				{
					BufferedImage bi = button.bi;
					int w = 124;
					int h = 110;
					float rate = (float)w/bi.getWidth();
					if((float)h/bi.getHeight() < rate){
						rate = (float)h/bi.getHeight();
					}
					int nw = (int)(rate * bi.getWidth());
					int nh = (int)(rate * bi.getHeight());
					//そこからきれいに縮小
					Image img = bi.getScaledInstance(nw, -1, Image.SCALE_AREA_AVERAGING );
					BufferedImage newbi = new BufferedImage(nw,nh,BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = (Graphics2D)newbi.getGraphics();
					g2.drawImage(img, 0, 0, nw, nh, button);
					button.setIcon(new ImageIcon(newbi));
	    			bi.flush();
	    			newbi.flush();
	    			System.gc();

	    			updateList.remove(i);
	    			i--;
				}
			}
		}
	}

	//アイコンのクリック: 単独選択、Shift+クリックで範囲、Cmd/Ctrl+クリックで追加・解除、ダブルクリックで開く
	class IconButtonListener extends MouseAdapter
	{
		@Override
		public void mouseClicked(MouseEvent e) {
        	if (e.getClickCount() >= 2){
        		int id = Integer.valueOf(((IconButton)e.getSource()).getName());
        		openResource(id);
        	}
		}

		@Override
		public void mousePressed(MouseEvent e) {
    		IconButton btn = (IconButton)e.getSource();
    		int id = Integer.valueOf(btn.getName());
    		contpane.requestFocusInWindow();
  		  	if((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0 && !selection.isEmpty()){
    			//範囲選択: 直前に選んだものからこれまで
    			int from = indexOf(primarySelectedId());
    			int to = indexOf(id);
    			if(from<0) from = to;
    			for(int i=Math.min(from,to); i<=Math.max(from,to); i++) selection.add(rsrcAry[i].id);
    		}
  		  	else if(((e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0) ||
  		  			((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0)){
    			//追加選択/解除
    			if(selection.contains(id)) selection.remove(id);
    			else{
    				LinkedHashSet<Integer> newSel = new LinkedHashSet<Integer>();
    				newSel.add(id);
    				newSel.addAll(selection);
    				selection = newSel;
    			}
    		}
    		else if(!selection.contains(id)){
    			//一つ選択
    			selection.clear();
    			selection.add(id);
    		}
    		else if(selection.size()>1 && e.getClickCount()==1){
    			//選択済みのものをクリック: それを先頭にする(ドラッグ相当の操作は無いのでそのまま)
    			selection.remove(id);
    			LinkedHashSet<Integer> newSel = new LinkedHashSet<Integer>();
    			newSel.add(id);
    			newSel.addAll(selection);
    			selection = newSel;
    		}
    		showSelection();
		}
	}

	//背景のクリックで選択解除、ドラッグで囲んで選択
	class IconBackListener extends MouseAdapter
	{
		@Override
		public void mousePressed(MouseEvent e) {
			contpane.requestFocusInWindow();
			boolean extend = ((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0)||
  	  		  	((e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0) ||
  	    		((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0);
			if(!extend) selection.clear();
			marqueeBase = new LinkedHashSet<Integer>(selection);
			marqueeStart = e.getPoint();
			marquee = null;
			showSelection();
		}

		@Override
		public void mouseDragged(MouseEvent e) {
			if(marqueeStart==null) return;
			Point p = e.getPoint();
			marquee = new Rectangle(Math.min(marqueeStart.x,p.x), Math.min(marqueeStart.y,p.y),
				Math.abs(p.x-marqueeStart.x), Math.abs(p.y-marqueeStart.y));
			selection = new LinkedHashSet<Integer>(marqueeBase);
			for(int i=0; i<contpane.getComponentCount(); i++){
				Component c = contpane.getComponent(i);
				if(c.getBounds().intersects(marquee)) selection.add(rsrcAry[i].id);
			}
			contpane.scrollRectToVisible(new Rectangle(p.x, p.y, 1, 1));
			showSelection();
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			marqueeStart = null;
			marqueeBase = null;
			if(marquee!=null){
				marquee = null;
				contpane.repaint();
			}
		}
	}

	/**
	 * Registers a picture file (PBM/PNG/JPEG/GIF/PICT) as a new resource of this type, copying it into the working
	 * copy (the staging folder); the original is never touched. A stack without a file of its own (a picture dropped
	 * on the launcher) registers the file itself by its absolute path: edits stay in the staging folder until Save
	 * writes them back into that file. Returns the new ID, or Integer.MIN_VALUE after showing the "can't open" dialog.
	 */
	int addPictureFile(File file){
		String path = file.toString();
		String ext = "";
		BufferedImage bi = null;
		try{
			ext = ".ppm";
			bi = PictureFile.loadPbm(path);
			if(bi==null){
				ext = extensionOf(path);
				bi = javax.imageio.ImageIO.read(file);
			}
			if(bi==null){
				ext = ".pict";
				bi = PictureFile.loadPICT(path);
			}
		} catch (IOException e2) {
			e2.printStackTrace();
		}
		if(bi==null){
			String str = PCARD.pc.intl.getDialogText("Can't open the file '%1'.");
			str = str.replace("%1",path);
			new GDialog(editor, str,null,"OK",null,null);
			return Integer.MIN_VALUE;
		}
		String name = file.getName();
		Pattern p = Pattern.compile("([0-9]{1,5})([^0-9])");
		Matcher m = p.matcher(name);
		int baseid = 1000;
		if(m.find()){
			baseid = Integer.valueOf(m.group(1));
		}
		if(baseid<-32768 || baseid > 32767){
			baseid = (baseid+1000000)%10000;
		}
		int iconid = rsrc.getNewResourceId(type, baseid);
		String newFileName;
		if(pcard.stack.file==null){
			//スタックを持たない画像ビューア(ランチャーへのドロップ): ファイルそのものを絶対パスで登録する。
			//編集は一時フォルダで行われ、保存でそのファイル自身に書き戻される(Rsrc.isExternalFile参照)
			newFileName = file.getAbsolutePath();
		}
		else{
			newFileName = fileNameFor(iconid, type.equals("Pdat") ? ".data" : ext);
			try{
				if(type.equals("Pdat")){
					//Pdatはヘッダ無しのPICTデータ: PICTファイルは512バイトのヘッダを外し、ほかの画像はPICTにする
					byte[] b = ext.equals(".pict") ? Files.readAllBytes(file.toPath()) : null;
					Files.write(rsrc.fileToWrite(newFileName).toPath(), b!=null && b.length>512 ? Arrays.copyOfRange(b, 512, b.length) : HCResourceExport.pict(bi));
				}
				else ResEdit.copyFile(file, rsrc.fileToWrite(newFileName));
			} catch (IOException e2) {
				e2.printStackTrace();
				String str = PCARD.pc.intl.getDialogText("Can't open the file '%1'.");
				new GDialog(editor, str.replace("%1",path),null,"OK",null,null);
				return Integer.MIN_VALUE;
			}
		}
		boolean wasDirty = rsrc.dirty;
		rsrc.addResource(iconid, type, name, newFileName);
		//スタックを持たないビューアでは登録自体は保存されるものではない(保存されるのは画像の編集だけ)ので、
		//開いて閉じただけで「保存しますか」と聞かれないよう、登録は変更に数えない
		if(pcard.stack.file==null) rsrc.dirty = wasDirty;
		return iconid;
	}

	class IconDropListener extends DropTargetAdapter {
		@Override
		public void drop(DropTargetDropEvent e) {
			try {
				Transferable transfer = e.getTransferable();
				if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
				{
					e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
					@SuppressWarnings("unchecked")
					List<File> fileList =
						(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));

					selection.clear();

					for(int j=0; j<fileList.size(); j++){
						int iconid = addPictureFile(fileList.get(j));
						if(iconid!=Integer.MIN_VALUE) selection.add(iconid);
					}
					//開き直す
					reload();
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}
}


/**
 * 表で一覧する種類(サウンド、文字列、その他)の共通部分。ID列を先頭に持ち、複数選択・
 * ダブルクリック/Returnで開く・Delete/BackSpaceで消去ができる。
 */
class TableTypeEditor extends ResTypeEditor {
	private static final long serialVersionUID = 1L;
	JTable table;
	DefaultTableModel tableModel;
	private boolean syncing;

	TableTypeEditor(PCARDFrame pc, String type, OObject object, String[] columns){
		super(pc, type, object);
		tableModel = new DefaultTableModel(columns, 0) {
			private static final long serialVersionUID = 1L;
			@Override public boolean isCellEditable(int row, int column) { return false; }
		};
		table = new JTable(tableModel);
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.setAutoCreateRowSorter(true);
		table.getSelectionModel().addListSelectionListener(e -> {
			if(e.getValueIsAdjusting() || syncing) return;
			selection.clear();
			for(int viewRow : table.getSelectedRows()){
				int row = table.convertRowIndexToModel(viewRow);
				selection.add(((Number)tableModel.getValueAt(row, 0)).intValue());
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				if(e.getClickCount() >= 2) openSelected();
			}
		});
		//JTableのReturn(次の行へ)ではなく「開く」にする
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "resOpen");
		table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "resOpen");
		table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "resClear");
		table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "resClear");
		installKeys(table);
		scrollpane.setViewportView(table);
		scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
	}

	//1行分の値(先頭はID)
	Object[] rowFor(Rsrc.rsrcClass r){
		return new Object[]{r.id, r.name};
	}

	@Override void open(PCARDFrame pc, int in_scroll){
		super.open(pc, in_scroll);
		if(tableModel==null) return;
		syncing = true;
		tableModel.setRowCount(0);
		for(Rsrc.rsrcClass r : rsrcAry) tableModel.addRow(rowFor(r));
		syncing = false;
		setTitle(withStackName(typeTitle()+" ("+rsrcAry.length+")"));
		if(selection.isEmpty() && rsrcAry.length>0) selection.add(rsrcAry[0].id);
		showSelection();
		SwingUtilities.invokeLater(() -> { if(scrollpane!=null) scrollpane.getVerticalScrollBar().setValue(in_scroll); });
	}

	String typeTitle(){ return type; }

	@Override void showSelection(){
		if(table==null) return;
		syncing = true;
		table.clearSelection();
		for(int row=0; row<tableModel.getRowCount(); row++){
			int id = ((Number)tableModel.getValueAt(row, 0)).intValue();
			if(selection.contains(id)){
				int viewRow = table.convertRowIndexToView(row);
				table.addRowSelectionInterval(viewRow, viewRow);
			}
		}
		syncing = false;
		int first = table.getSelectedRow();
		if(first>=0) table.scrollRectToVisible(table.getCellRect(first, 0, true));
	}

	@Override void selectAll(){
		super.selectAll();
	}

	@Override void selectIds(int[] ids){
		super.selectIds(ids);
	}
}


/** 画像でもサウンドでも文字列でもない種類の一覧: ID/名前/サイズの表。開くと16進ダンプを見られる。 */
class OtherTypeEditor extends TableTypeEditor {
	private static final long serialVersionUID = 1L;

	public OtherTypeEditor(PCARDFrame pc, String type, OObject object) {
		super(pc, type, object, new String[]{"ID", ResEdit.text("Name"), ResEdit.dialogText("Size")});
		open(pcard, 0);
		setVisible(true);
		toFront();
	}

	@Override Object[] rowFor(Rsrc.rsrcClass r){
		String path = rsrc.getFilePathAll(r.id, type);
		long size = path==null ? 0 : new File(path).length();
		return new Object[]{r.id, r.name, Long.valueOf(size)};
	}

	@Override void createNewResource(){
		//まだ専用エディタの無い種類は、生の(中身が空の)リソースファイルとして作成する。
		//ID/名前を持つリソースとしては正しく登録・エクスポートできる(HCResourceExport.convertの
		//4文字パススルー参照)。
		int rsrcid = rsrc.getNewResourceId(type);
		String newFileName = fileNameFor(rsrcid, ".data");
		try {
			new FileOutputStream(rsrc.fileToWrite(newFileName)).close();
		} catch (IOException e1) {
			e1.printStackTrace();
			return;
		}
		rsrc.addResource(rsrcid, type, "", newFileName);
		selection.clear();
		selection.add(rsrcid);
		reload();
	}

	@Override void openResource(int id){
		Rsrc.rsrcClass r = rsrc.getResource1(id, type);
		if(r==null) return;
		new HexDumpWindow(this, r.type+" "+r.id+(r.name.isEmpty() ? "" : " \""+r.name+"\""), rsrc.getFilePathAll(r.id, r.type));
	}
}


/**
 * XCMD/XFCN (68K) and xcmd/xfcn (PowerPC) of the stack: ID/name/size, opened as a hex dump. They can be copied to other
 * stacks (Copy here, Paste in the other stack's resource editor), removed, renamed and renumbered (Get Resource Info); each
 * name once per type, as HyperCard calls externals by name. Rsrc keeps them apart from the other resources (xcmdClass), so
 * the rows stand for them as rsrcClass entries of this window's type.
 */
class ExternalTypeEditor extends TableTypeEditor {
	private static final long serialVersionUID = 1L;

	ExternalTypeEditor(PCARDFrame pc, String type){
		super(pc, type, null, new String[]{"ID", ResEdit.text("Name"), ResEdit.dialogText("Size")});
		open(pcard, 0);
		setVisible(true);
		toFront();
	}

	@Override String typeTitle(){
		return type+(Character.isUpperCase(type.charAt(0)) ? " (68K)" : " (PowerPC)");
	}

	@Override Rsrc.rsrcClass[] ownResources(){
		ArrayList<Rsrc.xcmdClass> list = rsrc.externalsOfType(type);
		Rsrc.rsrcClass[] rows = new Rsrc.rsrcClass[list.size()];
		for(int i=0; i<rows.length; i++){
			Rsrc.xcmdClass x = list.get(i);
			rows[i] = rsrc.new rsrcClass(x.id, type, x.name, x.filename, "0", "0", null);
		}
		return rows;
	}

	@Override Object[] rowFor(Rsrc.rsrcClass r){
		String path = filePathOf(r.id);
		long size = path==null ? 0 : new File(path).length();
		return new Object[]{r.id, r.name, Long.valueOf(size)};
	}

	@Override String filePathOf(int id){
		Rsrc.xcmdClass x = rsrc.getExternal(type, id);
		return x==null ? null : rsrc.externalPath(x);
	}

	//外部コマンドのコードは書けないので、新規作成はしない(ほかのスタックからペーストする)
	@Override void createNewResource(){
		Toolkit.getDefaultToolkit().beep();
	}

	//同じ名前の外部コマンドは1つだけ(HyperCardは名前で呼ぶ)なので、複製もしない
	@Override void duplicateSelected(){
		Toolkit.getDefaultToolkit().beep();
	}

	@Override void openResource(int id){
		Rsrc.xcmdClass x = rsrc.getExternal(type, id);
		if(x==null) return;
		new HexDumpWindow(this, type+" "+x.id+" \""+x.name+"\"", rsrc.externalPath(x));
	}

	@Override void clearSelected(){
		int[] ids = selectedIds();
		if(ids.length==0) return;
		for(int id : ids){
			Rsrc.xcmdClass x = rsrc.getExternal(type, id);
			if(x!=null) rsrc.deleteExternal(x);
		}
		selection.clear();
		reload();
	}

	@Override void showResourceInfo(){
		Rsrc.xcmdClass x = rsrc.getExternal(type, primarySelectedId());
		if(x==null) return;
		new ResourceInfoDialog(this, type, x.id, x.name);
	}

	@Override String infoProblem(int oldId, int newId, String newName){
		Rsrc.xcmdClass x = rsrc.getExternal(type, oldId);
		if(newId!=oldId && rsrc.getExternal(type, newId)!=null) return ResEdit.dialogText("A resource with this ID already exists.");
		if(newName.trim().isEmpty()) return ResEdit.dialogText("An external command needs a name.");
		Rsrc.xcmdClass named = rsrc.getExternalNamed(type, newName);
		if(named!=null && named!=x) return ResEdit.dialogText("An external command with this name already exists.");
		return null;
	}

	@Override void applyInfo(int oldId, int newId, String newName){
		Rsrc.xcmdClass x = rsrc.getExternal(type, oldId);
		if(x!=null) rsrc.setExternalIdAndName(x, newId, newName);
	}

	@Override void copySelected(){
		ArrayList<Rsrc.xcmdClass> list = new ArrayList<Rsrc.xcmdClass>();
		for(int id : selectedIds()){
			Rsrc.xcmdClass x = rsrc.getExternal(type, id);
			if(x!=null) list.add(x);
		}
		ResClipboard.copy(rsrc, new ArrayList<Rsrc.rsrcClass>(), list);
	}
}


/** 生のリソースの16進ダンプ(読むだけ)。 */
class HexDumpWindow extends JFrame {
	private static final long serialVersionUID = 1L;

	HexDumpWindow(ResTypeEditor owner, String title, String path){
		setTitle(title);
		byte[] data = new byte[0];
		try {
			if(path!=null) data = Files.readAllBytes(new File(path).toPath());
		} catch(IOException e) {
			e.printStackTrace();
		}
		JTextArea area = new JTextArea(dump(data));
		area.setEditable(false);
		area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		getContentPane().add(new JScrollPane(area), BorderLayout.CENTER);
		JMenuBar mb = new JMenuBar();
		JMenu m = new JMenu(ResEdit.text("File"));
		JMenuItem mi = new JMenuItem(ResEdit.text("Close"));
		mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
		mi.addActionListener(e -> dispose());
		m.add(mi);
		mb.add(m);
		setJMenuBar(mb);
		setSize(560, 400);
		setLocationRelativeTo(owner);
		if(owner.session!=null) owner.session.attach(this);
		setVisible(true);
	}

	static String dump(byte[] data){
		StringBuilder sb = new StringBuilder();
		for(int off=0; off<data.length; off+=16){
			sb.append(String.format("%06X  ", off));
			StringBuilder chars = new StringBuilder();
			for(int i=0; i<16; i++){
				if(off+i<data.length){
					int b = data[off+i]&0xFF;
					sb.append(String.format("%02X", b));
					chars.append(b>=0x20 && b<0x7F ? (char)b : '.');
				}
				else sb.append("  ");
				if(i%2==1) sb.append(' ');
			}
			sb.append(' ').append(chars).append('\n');
		}
		if(data.length==0) sb.append("(0 bytes)\n");
		return sb.toString();
	}
}


/** リソース情報(Cmd-I): IDと名前を変更する。 */
class ResourceInfoDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	ResourceInfoDialog(ResTypeEditor owner, String type, int id, String name){
		super(owner, ResEdit.dialogText("Resource Info"), true);
		getContentPane().setLayout(new BorderLayout(8,8));
		JPanel fields = new JPanel(new java.awt.GridLayout(3, 2, 8, 8));
		fields.add(new JLabel(ResEdit.dialogText("Type:")));
		fields.add(new JLabel(type));
		fields.add(new JLabel(ResEdit.dialogText("ID:")));
		JTextField idField = new JTextField(Integer.toString(id), 8);
		fields.add(idField);
		fields.add(new JLabel(ResEdit.dialogText("Name:")));
		JTextField nameField = new JTextField(name, 24);
		fields.add(nameField);
		JPanel pad = new JPanel(new FlowLayout());
		pad.add(fields);
		getContentPane().add(pad, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton cancel = new JButton(ResEdit.dialogText("Cancel"));
		cancel.addActionListener(e -> dispose());
		JButton ok = new JButton("OK");
		ok.addActionListener(e -> {
			int newId;
			try {
				newId = Integer.parseInt(idField.getText().trim());
			} catch(NumberFormatException error) {
				new GDialog(owner, ResEdit.dialogText("Please enter a valid numeric ID."), null, "OK", null, null);
				return;
			}
			String newName = nameField.getText();
			String problem = owner.infoProblem(id, newId, newName);
			if(problem!=null){
				new GDialog(owner, problem, null, "OK", null, null);
				return;
			}
			if(newId!=id || !newName.equals(name)){
				owner.applyInfo(id, newId, newName);
				owner.resourceChanged(id, newId);
			}
			dispose();
		});
		buttons.add(cancel);
		buttons.add(ok);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(ok);
		pack();
		setLocationRelativeTo(owner);
		setVisible(true);
	}
}


/** 種類一覧ウインドウ共通のFile/Edit/Resourceメニュー。 */
class REMenu {
	JMenuItem viewFileItem;

	public REMenu(ResTypeEditor owner){
		ActionListener listener = new REMenuListener(owner);

    	// メニューバーの設定
		JMenuBar mb=new JMenuBar();
		owner.setJMenuBar(mb);

		JMenu m;
		JMenuItem mi;
		int s = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

	    // Fileメニュー
	    m=new JMenu(PCARDFrame.pc.intl.getText("File"));
	    mb.add(m);
	    mi = item(m, "Save", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));
	    mi = item(m, "View File", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, s));
	    viewFileItem = mi;
	    String os=System.getProperty("os.name");
        if(os==null || !os.startsWith("Mac OS X")){
	    	mi.setEnabled(false);
	    }
	    m.addSeparator();
	    mi = item(m, "Close", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));

	    // Editメニュー
	    m=new JMenu(PCARDFrame.pc.intl.getText("Edit"));
	    mb.add(m);
	    mi = item(m, "Cut", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));
	    mi = item(m, "Copy", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));
	    mi = item(m, "Paste", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));
	    mi = item(m, "Delete", listener);
	    m.addSeparator();
	    mi = item(m, "Duplicate", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, s));
	    mi = item(m, "Select All", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));

	    // Resourceメニュー
	    m=new JMenu(PCARDFrame.pc.intl.getText("Resource"));
	    mb.add(m);
	    mi = item(m, "Create New Resource", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, s));
	    mi = item(m, "Open", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));
	    mi = item(m, "Get Resource Info…", listener);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));
	}

	//訳語から英語へ逆引きすると同じ訳の項目(「消去」= Delete/Clear)が取り違えられるので、コマンドは英語の名前で持つ
	private static JMenuItem item(JMenu m, String english, ActionListener listener){
		JMenuItem mi = new JMenuItem(PCARDFrame.pc.intl.getText(english));
		mi.setActionCommand(english);
		mi.addActionListener(listener);
		m.add(mi);
		return mi;
	}
}

//リソース編集のメニュー動作
class REMenuListener implements ActionListener {
	ResTypeEditor editor;

	REMenuListener(ResTypeEditor owner){
		super();
		this.editor = owner;
	}

	@Override
	public void actionPerformed (ActionEvent e) {
		//REMenuの項目はコマンドを英語の名前で持つ
		String cmd = e.getActionCommand();

		if(cmd.equals("Close")){
			editor.requestClose();
			return;
		}

		if(editor.pcard.stack==null || editor.rsrc==null){
			return;
		}
		Rsrc rsrc = editor.rsrc;

		if(cmd.equals("Save")){
			editor.save();
		}
		else if(cmd.equals("Create New Resource") || cmd.equals("New Item")){
			editor.createNewResource();
		}
		else if(cmd.equals("Open")){
			editor.openSelected();
		}
		else if(cmd.equals("Get Resource Info…")){
			editor.showResourceInfo();
		}
		else if(cmd.equals("Duplicate")){
			editor.duplicateSelected();
		}
		else if(cmd.equals("Select All")){
			editor.selectAll();
		}
		else if(cmd.equals("View File")){
			int[] ids = editor.selectedIds();
			if(ids.length>0){
				String filename = "";
				for(int id : ids){
					String path = editor.filePathOf(id);
					if(path==null) continue;
					if(filename.length()>0) filename += ":";
					filename += path;
				}
				try{
					//"-R"はOSX10.6以上 バンドルの中はあらかじめ開いておかないと表示できない
					ProcessBuilder pb = new ProcessBuilder("open", "-R", filename);
					pb.start();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
		}
		else if(cmd.equals("Cut") || cmd.equals("Copy")){
			editor.copySelected();
		}
		else if(cmd.equals("Paste")){
			if(ResClipboard.hasResources()){
				editor.paste();
			}
			else if(editor.type.equals("ICON")){
				Image img = GMenuPaint.getClipboardImage();
				if(img!=null){
					//画像の場合
					int rsrcid = rsrc.getNewResourceId("ICON");
					String newFileName = rsrc.freeFileName("ICON_"+rsrcid+".png", null);
					try {
						ImageIO.write((RenderedImage)img,"png",rsrc.fileToWrite(newFileName));
					} catch (IOException e1) {
						e1.printStackTrace();
						return;
					}
					rsrc.addResource(rsrcid, "ICON", "", newFileName);
					editor.reload();
					editor.selectIds(new int[]{rsrcid});
				}
			}
		}

		if(cmd.equals("Delete") || cmd.equals("Cut")){
			editor.clearSelected();
		}
	}

}

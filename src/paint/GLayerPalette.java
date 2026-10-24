import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JWindow;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

//レイヤーパレット(Paint > Layers、Cmd-L)。ツールパレットと同じ窓(GWindoidBar付きの
//JWindow)に、編集中のグループ(カードかバックグラウンド)のレイヤーを上から順に並べる。
//一番下は本来の「Card Paint」「Background Paint」。行の左の目で表示/非表示、クリックで
//編集するレイヤーの切り替え、ダブルクリックで名前の変更。下のボタンで追加・複製・
//削除・上下・結合、その下に合成モード・不透明度・Alpha Lock・Clip to Below。
//実際の操作はすべてPaintLayers(メニュー・HyperTalkと共通)。
class GLayerPalette {
	static final int WIDTH = 220;
	static final int THUMB_W = 32, THUMB_H = 24;

	private static GLayerPalette palette;

	final JWindow window;
	private final JList<Integer> list;
	private final DefaultListModel<Integer> model = new DefaultListModel<Integer>();
	private final JComboBox<String> blend;
	private final JSlider opacity;
	private final JCheckBox alphaLock, clip;
	private final JLabel title;
	private final JButton add, dup, del, up, down, merge;
	private final Timer watcher;
	private boolean updating;
	//前回並べたときの状態(変わったときだけ並べ直す)
	private OCardBase shownGroup; private int shownVersion=-1, shownActive=-1; private boolean shownEditBg; private boolean shownEnabled;

	static GLayerPalette get(){
		if(palette==null) palette = new GLayerPalette();
		return palette;
	}

	static boolean isShowing(){
		return palette!=null && palette.window.isVisible();
	}

	//Paint > Layers: 出す/しまう
	static void toggle(){
		show(!isShowing());
	}

	static void show(boolean visible){
		if(!visible){
			if(palette!=null) palette.window.setVisible(false);
		}else{
			GLayerPalette p = get();
			if(!p.window.isVisible()){ p.place(); p.window.setVisible(true); }
			p.rebuild(true);
		}
		syncMenu();
	}

	//Paintメニューの「Show Layers」のチェックを合わせる
	static void syncMenu(){
		GMenu.changeSelected("Paint", "Show Layers", isShowing());
	}

	//レイヤーが変わった(PaintLayers.changed等)。見えていれば並べ直す
	static void refresh(){
		if(palette==null || !palette.window.isVisible()) return;
		if(SwingUtilities.isEventDispatchThread()) palette.rebuild(false);
		else SwingUtilities.invokeLater(() -> palette.rebuild(false));
	}

	//カードウインドウのアクティブ化に合わせて出し入れ(ツールパレットと同じ)
	private boolean wasVisible;
	static void activate(){ if(palette!=null && palette.wasVisible){ palette.window.setVisible(true); palette.wasVisible = false; } }
	static void deactivate(){ if(palette!=null && palette.window.isVisible()){ palette.wasVisible = true; palette.window.setVisible(false); } }
	//スタックが無くなった: 隠し、次にカードウインドウがアクティブになっても出さない
	static void hideForNoStack(){ if(palette!=null){ palette.wasVisible = false; palette.window.setVisible(false); } }

	private GLayerPalette(){
		window = new JWindow();
		//ツールパレットと同じ: 角丸の本体(null layout)に、タイトルバー帯と中身のパネルを置く
		JPanel root = new JPanel(null) {
			private static final long serialVersionUID = 1L;
			@Override protected void paintComponent(Graphics g) {
				GWindoidBar.paintWindowBody((Graphics2D)g, getWidth(), getHeight(), new Color(0xE8,0xE8,0xE8));
			}
		};
		root.setOpaque(true);
		window.setContentPane(root);
		window.setAlwaysOnTop(true);
		window.addComponentListener(new java.awt.event.ComponentAdapter() {
			@Override public void componentResized(java.awt.event.ComponentEvent e) { GWindoidBar.applyShape(window); }
		});
		//(無名クラスの中のWIDTHはComponentのImageObserver.WIDTH=1になるので、外側の名前で参照する)
		GWindoidBar bar = new GWindoidBar(window, () -> { window.setVisible(false); syncMenu(); });
		bar.setBounds(0, 0, GLayerPalette.WIDTH, GWindoidBar.HEIGHT);
		root.add(bar);
		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
		root.add(body);

		title = new JLabel(text("Layers"));
		title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
		title.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		body.add(title, BorderLayout.NORTH);

		list = new JList<Integer>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new Row());
		list.setFixedCellHeight(THUMB_H+6);
		list.setOpaque(true);
		list.setBackground(Color.WHITE);
		list.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				int idx = list.locationToIndex(e.getPoint());
				if(idx<0 || !list.getCellBounds(idx, idx).contains(e.getPoint())) return;
				int n = model.get(idx);
				if(e.getX()<22){
					//目: 表示の切り替え(本来のピクチャは常に表示)
					if(n>0){
						OCardBase g = PCARD.pc.paintGroup();
						if(g!=null && n<=g.layers.size()) PaintLayers.setVisible(PCARD.pc, n, !g.layers.get(n-1).visible);
					}
					return;
				}
				if(e.getClickCount()==2 && n>0){
					rename(n);
					return;
				}
				if(n!=PCARD.pc.activeLayer()) PaintLayers.setActive(PCARD.pc, n);
			}
		});
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(WIDTH-12, (THUMB_H+6)*4+4));
		scroll.setBorder(BorderFactory.createLineBorder(new Color(0xA0A0A0)));
		body.add(scroll, BorderLayout.CENTER);

		JPanel south = new JPanel();
		south.setOpaque(false);
		south.setLayout(new javax.swing.BoxLayout(south, javax.swing.BoxLayout.Y_AXIS));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
		buttons.setOpaque(false);
		add = small("+", "New Layer", () -> PaintLayers.add(PCARD.pc));
		dup = small("❐", "Duplicate Layer", () -> PaintLayers.duplicate(PCARD.pc));
		del = small("−", "Delete Layer", () -> PaintLayers.delete(PCARD.pc));
		up = small("▲", "Move Layer Up", () -> PaintLayers.move(PCARD.pc, 1));
		down = small("▼", "Move Layer Down", () -> PaintLayers.move(PCARD.pc, -1));
		merge = small("↓≡", "Merge Down", () -> PaintLayers.mergeDown(PCARD.pc));
		for(JButton b : new JButton[]{add, dup, del, up, down, merge}) buttons.add(b);
		south.add(buttons);

		JPanel props = new JPanel(new GridLayout(0, 1, 0, 2));
		props.setOpaque(false);
		blend = new JComboBox<String>();
		for(String n : PaintLayer.BLEND_NAMES) blend.addItem(modeText(n));
		blend.setFont(blend.getFont().deriveFont(11f));
		blend.addActionListener(e -> { if(!updating) PaintLayers.setBlend(PCARD.pc, blend.getSelectedIndex()); });
		props.add(labelled("Blend Mode", blend));
		opacity = new JSlider(0, 100, 100);
		opacity.setOpaque(false);
		opacity.addChangeListener(e -> { if(!updating) PaintLayers.setOpacity(PCARD.pc, opacity.getValue()); });
		props.add(labelled("Opacity", opacity));
		alphaLock = new JCheckBox(text("Alpha Lock"));
		alphaLock.setOpaque(false);
		alphaLock.setFont(alphaLock.getFont().deriveFont(11f));
		alphaLock.addActionListener(e -> { if(!updating) PaintLayers.setAlphaLock(PCARD.pc, alphaLock.isSelected()); });
		clip = new JCheckBox(text("Clip to Below"));
		clip.setOpaque(false);
		clip.setFont(clip.getFont().deriveFont(11f));
		clip.addActionListener(e -> { if(!updating) PaintLayers.setClipToBelow(PCARD.pc, clip.isSelected()); });
		JPanel checks = new JPanel(new GridLayout(2, 1));
		checks.setOpaque(false);
		checks.add(alphaLock); checks.add(clip);
		props.add(checks);
		south.add(props);
		body.add(south, BorderLayout.SOUTH);

		Dimension pref = body.getPreferredSize();
		body.setBounds(0, GWindoidBar.HEIGHT, WIDTH, pref.height);
		window.setSize(WIDTH, GWindoidBar.HEIGHT+pref.height);

		//ストローク・カード移動・Undoなど、こちらを経由しない変化も拾う
		watcher = new Timer(400, e -> { if(window.isVisible()) rebuild(false); });
		watcher.setRepeats(true);
		watcher.start();
	}

	private static String text(String key){
		return PCARD.pc==null ? key : PCARD.pc.intl.getText(key);
	}
	//合成モードの名前(Blending Modeダイアログと同じ訳)
	private static String modeText(String key){
		return PCARD.pc==null ? key : PCARD.pc.intl.getDialogText(key);
	}

	private JButton small(String glyph, String tip, Runnable action){
		JButton b = new JButton(glyph);
		b.setToolTipText(text(tip));
		b.setMargin(new Insets(0, 4, 0, 4));
		b.setFont(b.getFont().deriveFont(11f));
		b.setFocusable(false);
		b.setPreferredSize(new Dimension(30, 22));
		b.addActionListener(e -> action.run());
		return b;
	}

	private static JPanel labelled(String name, Component c){
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		JLabel l = new JLabel(text(name));
		l.setFont(l.getFont().deriveFont(11f));
		l.setPreferredSize(new Dimension(62, l.getPreferredSize().height));
		row.add(l, BorderLayout.WEST);
		row.add(c, BorderLayout.CENTER);
		return row;
	}

	//カードウインドウの右上に置く(ツールパレットは左)
	private void place(){
		if(PCARD.pc==null) return;
		Point p = PCARD.pc.getLocation();
		window.setLocation(p.x+PCARD.pc.getWidth()+2, p.y+24);
	}

	private void rename(int n){
		PCARD.pc.toFront();
		String cur = PaintLayers.nameOf(PCARD.pc, n);
		String v = JOptionPane.showInputDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Layer name:"), cur);
		if(v==null || v.trim().isEmpty()) return;
		if(n==PCARD.pc.activeLayer()) PaintLayers.rename(PCARD.pc, v);
		else{
			OCardBase g = PCARD.pc.paintGroup();
			if(g!=null && n<=g.layers.size()){ g.layers.get(n-1).name = v.trim(); g.changed = true; }
			rebuild(true);
		}
	}

	//今の状態で並べ直す。forceでなければ変化が無いときは何もしない
	void rebuild(boolean force){
		PCARDFrame pc = PCARD.pc;
		boolean enabled = pc!=null && pc.tool!=null && pc.mainImg!=null && pc.paintGroup()!=null;
		OCardBase g = enabled ? pc.paintGroup() : null;
		int version = g==null ? -1 : g.layerVersion;
		int active = enabled ? pc.activeLayer() : -1;
		boolean editBg = enabled && pc.isEditingBackground();
		if(!force && shownEnabled==enabled && shownGroup==g && shownVersion==version && shownActive==active && shownEditBg==editBg){
			list.repaint(); //サムネイル(ストローク中の絵)だけ更新
			return;
		}
		shownEnabled = enabled; shownGroup = g; shownVersion = version; shownActive = active; shownEditBg = editBg;
		updating = true;
		try{
			model.clear();
			if(enabled){
				int count = PaintLayers.count(pc);
				for(int n=count-1; n>=0; n--) model.addElement(n);
				list.setSelectedIndex(count-1-active);
				title.setText(text(editBg ? "Background Layers" : "Card Layers"));
			}else{
				title.setText(text("Layers"));
			}
			PaintLayer l = enabled ? pc.activePaintLayer() : null;
			boolean extra = l!=null;
			blend.setEnabled(extra); opacity.setEnabled(extra); alphaLock.setEnabled(extra); clip.setEnabled(extra);
			del.setEnabled(extra); merge.setEnabled(extra);
			up.setEnabled(extra && g!=null && active<g.layers.size());
			down.setEnabled(extra && active>1);
			add.setEnabled(enabled); dup.setEnabled(enabled);
			if(extra){
				blend.setSelectedIndex(Math.max(0, Math.min(PaintLayer.BLEND_NAMES.length-1, l.blend)));
				opacity.setValue(l.opacity);
				alphaLock.setSelected(l.alphaLock);
				clip.setSelected(l.clipToBelow);
			}else{
				blend.setSelectedIndex(0); opacity.setValue(100); alphaLock.setSelected(false); clip.setSelected(false);
			}
		}finally{
			updating = false;
		}
		list.repaint();
	}

	//1行: 目・サムネイル・名前
	private class Row extends JPanel implements ListCellRenderer<Integer> {
		private static final long serialVersionUID = 1L;
		private int n; private boolean selected;

		Row(){ setOpaque(true); setPreferredSize(new Dimension(WIDTH-40, THUMB_H+6)); }

		@Override
		public Component getListCellRendererComponent(JList<? extends Integer> l, Integer value, int index, boolean isSelected, boolean cellHasFocus){
			n = value; selected = isSelected;
			setBackground(isSelected ? new Color(0xCC, 0xDD, 0xFF) : Color.WHITE);
			return this;
		}

		@Override
		protected void paintComponent(Graphics g0){
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			PCARDFrame pc = PCARD.pc;
			OCardBase group = pc==null ? null : pc.paintGroup();
			PaintLayer layer = (group!=null && n>0 && n<=group.layers.size()) ? group.layers.get(n-1) : null;
			BufferedImage img = layer!=null ? layer.image : (pc==null ? null : (pc.isEditingBackground() ? pc.bgImg : pc.mainImg));
			boolean visible = layer==null || layer.visible;
			//目
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(visible ? Color.DARK_GRAY : new Color(0xC0C0C0));
			int ex = 4, ey = getHeight()/2;
			g.drawOval(ex, ey-4, 14, 8);
			if(visible) g.fillOval(ex+5, ey-2, 4, 4);
			//サムネイル(市松の上に縮小)
			int tx = 24, ty = 3;
			for(int y=0; y<THUMB_H; y+=6) for(int x=0; x<THUMB_W; x+=6){
				g.setColor((((x/6)+(y/6))&1)==0 ? Color.WHITE : new Color(0xDDDDDD));
				g.fillRect(tx+x, ty+y, Math.min(6, THUMB_W-x), Math.min(6, THUMB_H-y));
			}
			if(img!=null){
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(img, tx, ty, tx+THUMB_W, ty+THUMB_H, 0, 0, img.getWidth(), img.getHeight(), null);
			}
			g.setColor(new Color(0x808080));
			g.drawRect(tx-1, ty-1, THUMB_W+1, THUMB_H+1);
			//名前(と合成モード・不透明度)
			String name = pc==null ? "" : PaintLayers.nameOf(pc, n);
			if(n==0) name = text(name);
			g.setColor(visible ? Color.BLACK : Color.GRAY);
			g.setFont(getFont().deriveFont(layer==null ? Font.ITALIC : Font.PLAIN, 11f));
			g.drawString(name, tx+THUMB_W+6, ey+4);
			if(layer!=null && (!layer.plain() || layer.alphaLock)){
				StringBuilder sb = new StringBuilder();
				if(layer.blend!=MyPanel.blendMode.Copy) sb.append(modeText(PaintLayer.blendName(layer.blend)));
				if(layer.opacity<100){ if(sb.length()>0) sb.append(' '); sb.append(layer.opacity).append('%'); }
				if(layer.alphaLock){ if(sb.length()>0) sb.append(' '); sb.append("α"); }
				if(layer.clipToBelow){ if(sb.length()>0) sb.append(' '); sb.append("↳"); }
				g.setFont(getFont().deriveFont(Font.PLAIN, 9f));
				g.setColor(selected ? new Color(0x405080) : Color.GRAY);
				g.drawString(sb.toString(), tx+THUMB_W+6, getHeight()-4);
			}
		}
	}

	//テスト用: 並んでいる行のレイヤー番号(上から)
	ArrayList<Integer> rows(){
		ArrayList<Integer> r = new ArrayList<Integer>();
		for(int i=0; i<model.size(); i++) r.add(model.get(i));
		return r;
	}
}

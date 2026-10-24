import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Random;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

//ツールパレットのツールをダブルクリック(または右クリック)したときに出る
//ツールオプション。以前は「Brush 4」「Line 1.5」「Color 5 %」のような文字だけの
//チェックメニューで、実際の太さや形が分からず、チェックの位置もメニューから
//選び直したときにしか更新されなかった(スクリプトのset the lineSize等は
//反映されない)。ここでは、
//  - ブラシ/線/スプレー/正多角形/塗りつぶし(四角・角丸・楕円・曲線・多角形)/
//    消しゴムの大きさ: 実際の見た目をそのまま描いたマス目を出し、押した瞬間に
//    決まって閉じる(色のクイックパレットと同じ操作感)
//  - 自動選択の許容範囲/バケツの許容範囲/透明度: スライダーで連続的に変えられ、
//    動かした瞬間に効く
//にする。今の値はポップアップを出すたびに読み直して強調表示する。
//ツールごとの対応: 鉛筆=FatBits、矩形選択=すべて選択、投げなわ=全体を投げなわ選択、
//消しゴム=全消去(以上はダブルクリック、HyperCardどおり)、文字=フォントダイアログ。
//消しゴムの大きさだけは右クリックで出す(ダブルクリックは全消去のため)。
class GToolOptions {
	static final int CELL = 44;   //マス目の大きさ
	static final int PAD = 4;     //マス目の中の余白

	static final int[] BRUSH_SIZES = {1,2,3,4,6,8,16,32};
	static final float[] LINE_SIZES = {0,0.5f,1,1.5f,2,3,4,5,6,8,10,12,16};
	static final int[] SPRAY_SIZES = {2,4,6,8,12,16,24,32};
	static final int[] POLY_SIDES = {3,4,5,6,7,8,10,12};
	static final int[] ERASER_SIZES = {4,8,12,16,24,32};
	static final int WAND_MAX = 50;      //自動選択の許容範囲(%)の上限
	static final int TRANS_MAX = 95;     //透明度(%)の上限(100%は何も描かれないので除く)

	//ツールの英語名(TBButton.getName())に対応するオプションがあるか
	static boolean hasOptions(String tool){
		return "Brush".equals(tool) || "Line".equals(tool) || "Rect".equals(tool) ||
			"RoundRect".equals(tool) || "Oval".equals(tool) || "Curve".equals(tool) || "Poly".equals(tool) ||
			"MagicWand".equals(tool) || "SprayCan".equals(tool) || "RegPoly".equals(tool) || "Transparency".equals(tool) ||
			"PaintBucket".equals(tool) || "Eraser".equals(tool) || "Smudge".equals(tool) || "Stamp".equals(tool);
	}

	//invoker(ツールボタン)の下にそのツールのオプションを出す。
	static void show(Component invoker, String tool){
		JPopupMenu popup = build(tool);
		if(popup==null || !invoker.isShowing()) return;
		popup.pack();
		popup.show(invoker, 0, invoker.getHeight());
	}

	//テスト用にポップアップだけ組み立てる(nullならそのツールにオプションはない)
	static JPopupMenu build(String tool){
		JPopupMenu popup = new JPopupMenu();
		JPanel body = new JPanel(new BorderLayout(0,4));
		body.setBorder(BorderFactory.createEmptyBorder(4,6,4,6));
		String title;
		Component content;
		if("Brush".equals(tool)){
			title = "Brush";
			content = brushOptions(popup);
		}
		else if("Line".equals(tool)){
			title = "Line Size";
			content = lineCells(popup);
		}
		else if("Rect".equals(tool) || "RoundRect".equals(tool) || "Oval".equals(tool) || "Curve".equals(tool) || "Poly".equals(tool)){
			title = "Draw Filled";
			content = fillCells(popup, tool);
		}
		else if("Eraser".equals(tool)){
			title = "Eraser Size";
			content = eraserCells(popup);
		}
		else if("Smudge".equals(tool)){
			//指先: ブラシと同じ大きさ・硬さ・間隔に、強さ
			title = "Smudge";
			JPanel panel = new JPanel();
			panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
			panel.add(brushCells(popup));
			panel.add(javax.swing.Box.createVerticalStrut(4));
			panel.add(labelled("Strength", slider(1, 100, PaintTool.smudgeStrength, "%", v -> PaintTool.smudgeStrength = v)));
			panel.add(labelled("Hardness", slider(0, 100, PaintTool.brushHardness, "%", v -> PaintTool.brushHardness = v)));
			panel.add(labelled("Spacing", slider(1, 200, PaintTool.brushSpacing, "%", v -> PaintTool.brushSpacing = v)));
			content = panel;
		}
		else if("Stamp".equals(tool)){
			//スタンプ: ブラシと同じオプション(大きさ・硬さ・不透明度・間隔)
			title = "Stamp";
			JPanel panel = new JPanel();
			panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
			panel.add(brushCells(popup));
			panel.add(javax.swing.Box.createVerticalStrut(4));
			panel.add(labelled("Hardness", slider(0, 100, PaintTool.brushHardness, "%", v -> PaintTool.brushHardness = v)));
			panel.add(labelled("Opacity", slider(1, 100, PaintTool.brushOpacity, "%", v -> PaintTool.brushOpacity = v)));
			panel.add(labelled("Spacing", slider(1, 200, PaintTool.brushSpacing, "%", v -> PaintTool.brushSpacing = v)));
			content = panel;
		}
		else if("PaintBucket".equals(tool)){
			title = "Fill Tolerance";
			content = slider(0, WAND_MAX, PaintTool.fillTolerancePercent, "%", v -> {
				PaintTool.fillTolerancePercent = v;
			});
		}
		else if("SprayCan".equals(tool)){
			title = "Spray Size";
			content = sprayCells(popup);
		}
		else if("RegPoly".equals(tool)){
			title = "Polygon Sides";
			content = polygonCells(popup);
		}
		else if("MagicWand".equals(tool)){
			title = "Color Tolerance";
			content = slider(0, WAND_MAX, PaintTool.smartSelectPercent, "%", v -> {
				PaintTool.smartSelectPercent = v;
				changeCursor();
			});
		}
		else if("Transparency".equals(tool)){
			title = "Transparency";
			content = slider(0, TRANS_MAX, 100-PaintTool.alpha, "%", v -> {
				PaintTool.alpha = 100-v;
				changeCursor();
				if(PaintTool.owner!=null && PaintTool.owner.trans!=null){
					PaintTool.owner.trans.setText(v+"%");
				}
			});
		}
		else{
			return null;
		}
		JLabel head = new JLabel(PCARD.pc.intl.getToolText(title));
		head.setFont(head.getFont().deriveFont(Font.PLAIN, 11f));
		head.setForeground(UIManager.getColor("Label.disabledForeground"));
		body.add(head, BorderLayout.NORTH);
		body.add(content, BorderLayout.CENTER);
		popup.add(body);
		return popup;
	}

	static void changeCursor(){
		PCARDFrame frame = PaintTool.owner!=null ? PaintTool.owner : PCARD.pc;
		if(frame!=null && frame.mainPane!=null) TBCursor.changeCursor(frame);
	}

	//マス目1つ。painterで中身を描き、今の値なら枠を強調する。押した瞬間に
	//onPickを呼んでポップアップを閉じる。
	static class OptionCell extends JPanel {
		private static final long serialVersionUID = 1L;
		final Consumer<Graphics2D> painter;
		final String label;
		final boolean selected;
		OptionCell(int width, int height, String label, boolean selected, Consumer<Graphics2D> painter, Runnable onPick, JPopupMenu popup){
			this.painter = painter;
			this.label = label;
			this.selected = selected;
			setOpaque(true);
			setBackground(Color.WHITE);
			setPreferredSize(new Dimension(width, height));
			setToolTipText(label);
			addMouseListener(new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){
					onPick.run();
					popup.setVisible(false);
				}
			});
		}
		@Override
		protected void paintComponent(Graphics g){
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D)g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(Color.BLACK);
			painter.accept(g2);
			g2.dispose();
			//値のラベル(右下)
			if(label!=null){
				g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
				g.setColor(Color.GRAY);
				int w = g.getFontMetrics().stringWidth(label);
				g.drawString(label, getWidth()-w-2, getHeight()-2);
			}
			//枠。今の値は太い青枠
			if(selected){
				g.setColor(new Color(0x3478F6));
				((Graphics2D)g).setStroke(new BasicStroke(2));
				g.drawRect(1, 1, getWidth()-3, getHeight()-3);
			}else{
				g.setColor(Color.LIGHT_GRAY);
				g.drawRect(0, 0, getWidth()-1, getHeight()-1);
			}
		}
	}

	static JPanel row(int columns){
		return new JPanel(new GridLayout(1, columns, 2, 2));
	}

	//ブラシ: 実際に描かれる丸(直径=brushSize*2、BrushTool参照)。マスに収まら
	//ない大きさは収まる範囲で描く(数字のラベルで区別できる)。
	static JPanel brushCells(JPopupMenu popup){
		JPanel row = row(BRUSH_SIZES.length);
		for(int size : BRUSH_SIZES){
			int d = Math.min(size*2, CELL-PAD*2);
			row.add(new OptionCell(CELL, CELL, String.valueOf(size), size==PaintTool.brushSize,
				g -> g.fillOval((CELL-d)/2, (CELL-d)/2, d, d),
				() -> { PaintTool.brushSize = size; changeCursor(); }, popup));
		}
		return row;
	}

	//ブラシ: 大きさのマス目の下に、硬さ・不透明度・間隔・手ぶれ補正のスライダー
	//(どれも動かした瞬間に効く)。BrushTool参照。
	static JPanel brushOptions(JPopupMenu popup){
		JPanel panel = new JPanel();
		panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
		panel.add(brushCells(popup));
		panel.add(javax.swing.Box.createVerticalStrut(4));
		panel.add(labelled("Hardness", slider(0, 100, PaintTool.brushHardness, "%", v -> PaintTool.brushHardness = v)));
		panel.add(labelled("Opacity", slider(1, 100, PaintTool.brushOpacity, "%", v -> PaintTool.brushOpacity = v)));
		panel.add(labelled("Spacing", slider(1, 200, PaintTool.brushSpacing, "%", v -> PaintTool.brushSpacing = v)));
		panel.add(labelled("Stabilizer", slider(0, 98, PaintTool.brushStabilization, "%", v -> PaintTool.brushStabilization = v)));
		return panel;
	}

	//見出し付きの行(スライダーの左に小さな見出し)
	static JPanel labelled(String name, Component content){
		JPanel row = new JPanel(new BorderLayout(6,0));
		JLabel label = new JLabel(PCARD.pc.intl.getToolText(name));
		label.setName(name);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
		label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
		row.add(label, BorderLayout.WEST);
		row.add(content, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	//線の太さ: 実際の太さの横線を並べる(HyperCardのLine Sizeダイアログと
	//同じ見せ方)。13段あるので2列にして縦に長くなりすぎないようにする。
	static JPanel lineCells(JPopupMenu popup){
		JPanel grid = new JPanel(new GridLayout(0, 2, 2, 2));
		int w = CELL*2+8, h = 28;
		for(float size : LINE_SIZES){
			String label = (size==(int)size) ? String.valueOf((int)size) : String.valueOf(size);
			grid.add(new OptionCell(w, h, label, size==PaintTool.lineSize,
				g -> {
					g.setStroke(new BasicStroke(size, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
					g.drawLine(PAD+2, h/2, w-PAD-20, h/2);
				},
				() -> { PaintTool.lineSize = size; changeCursor(); }, popup));
		}
		return grid;
	}

	//四角/楕円の塗りつぶし: 枠だけ/塗りつぶしの2択を形で見せる
	static JPanel fillCells(JPopupMenu popup, String tool){
		JPanel row = row(2);
		boolean filled = PaintTool.owner!=null && PaintTool.owner.fill;
		String[] names = {"Don't Fill", "Fill"};
		for(int i=0; i<2; i++){
			final boolean fill = (i==1);
			row.add(new OptionCell(CELL*2, CELL+8, PCARD.pc.intl.getToolText(names[i]), fill==filled,
				g -> {
					//下のラベル分を空けて形を描く
					int x = PAD+6, y = PAD+2, w = CELL*2-PAD*2-12, h = CELL-PAD*2-8;
					g.setStroke(new BasicStroke(1.5f));
					if("Oval".equals(tool)){
						if(fill) g.fillOval(x, y, w, h); else g.drawOval(x, y, w, h);
					}else if("RoundRect".equals(tool)){
						if(fill) g.fillRoundRect(x, y, w, h, 12, 12); else g.drawRoundRect(x, y, w, h, 12, 12);
					}else if("Curve".equals(tool)){
						java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
						p.moveTo(x, y+h);
						p.curveTo(x+w/4.0, y-2, x+w*3/4.0, y+h+2, x+w, y);
						if(fill) { p.closePath(); g.fill(p); } else g.draw(p);
					}else if("Poly".equals(tool)){
						int[] xs = {x, x+w*2/3, x+w, x+w/2, x+w/5};
						int[] ys = {y+h/3, y, y+h/2, y+h, y+h*3/4};
						if(fill) g.fillPolygon(xs, ys, 5); else g.drawPolygon(xs, ys, 5);
					}else{
						if(fill) g.fillRect(x, y, w, h); else g.drawRect(x, y, w, h);
					}
				},
				() -> { if(PaintTool.owner!=null) PaintTool.owner.fill = fill; }, popup));
		}
		return row;
	}

	//消しゴム: 実際の一辺の正方形(EraserTool.brushSize参照)。マスに収まらない
	//大きさは収まる範囲で描く。
	static JPanel eraserCells(JPopupMenu popup){
		JPanel row = row(ERASER_SIZES.length);
		for(int size : ERASER_SIZES){
			int d = Math.min(size, CELL-PAD*2);
			row.add(new OptionCell(CELL, CELL, String.valueOf(size), size==PaintTool.eraserSize,
				g -> {
					g.setColor(Color.WHITE);
					g.fillRect((CELL-d)/2, (CELL-d)/2, d, d);
					g.setColor(Color.BLACK);
					g.drawRect((CELL-d)/2, (CELL-d)/2, d-1, d-1);
				},
				() -> { PaintTool.eraserSize = size; changeCursor(); }, popup));
		}
		return row;
	}

	//スプレー: 散布半径の円の中に点を散らす(SprayCanTool参照)。マスに収まら
	//ない半径は収まる範囲で描く。
	static JPanel sprayCells(JPopupMenu popup){
		JPanel row = row(SPRAY_SIZES.length);
		for(int radius : SPRAY_SIZES){
			int r = Math.min(radius, (CELL-PAD*2)/2);
			row.add(new OptionCell(CELL, CELL, String.valueOf(radius), radius==PaintTool.sprayRadius,
				g -> {
					Random rng = new Random(radius);
					int n = Math.max(12, r*r/2);
					for(int i=0; i<n; i++){
						double a = rng.nextDouble()*Math.PI*2;
						double d = Math.sqrt(rng.nextDouble())*r;
						g.fillRect(CELL/2+(int)Math.round(Math.cos(a)*d), CELL/2+(int)Math.round(Math.sin(a)*d), 1, 1);
					}
				},
				() -> { PaintTool.sprayRadius = radius; changeCursor(); }, popup));
		}
		return row;
	}

	//正多角形: 辺の数どおりの形(RegPolyTool同様、上を頂点にする)
	static JPanel polygonCells(JPopupMenu popup){
		JPanel row = row(POLY_SIDES.length);
		for(int sides : POLY_SIDES){
			row.add(new OptionCell(CELL, CELL, String.valueOf(sides), sides==PaintTool.regPolySides,
				g -> {
					int[] xs = new int[sides], ys = new int[sides];
					double r = (CELL-PAD*2)/2.0-2;
					for(int i=0; i<sides; i++){
						double a = -Math.PI/2 + i*2*Math.PI/sides;
						xs[i] = (int)Math.round(CELL/2.0+Math.cos(a)*r);
						ys[i] = (int)Math.round(CELL/2.0+Math.sin(a)*r);
					}
					g.setStroke(new BasicStroke(1.5f));
					g.drawPolygon(xs, ys, sides);
				},
				() -> { PaintTool.regPolySides = sides; }, popup));
		}
		return row;
	}

	//グラデーションボタンのポップアップ。上段は塗りの種類のマス目(切=今の色/
	//パターン、線形、放射状。押した瞬間に決まって閉じる)、下段は角度のダイヤル
	//(ドラッグで1°刻み、shiftで15°刻み。隣のプレビューに前景色→背景色の
	//グラデーションを動かした瞬間に映す。ボタンの絵もその場で変わる)。
	static void showGradientOptions(GradButton btn){
		JPopupMenu popup = buildGradientOptions(btn);
		if(!btn.isShowing()) return;
		popup.pack();
		popup.show(btn, 0, btn.getHeight());
	}

	//テスト用にポップアップだけ組み立てる
	static JPopupMenu buildGradientOptions(GradButton btn){
		JPopupMenu popup = new JPopupMenu();
		JPanel body = new JPanel(new BorderLayout(0,4));
		body.setBorder(BorderFactory.createEmptyBorder(4,6,4,6));
		JLabel head = new JLabel(PCARD.pc.intl.getToolText("Fill Style"));
		head.setFont(head.getFont().deriveFont(Font.PLAIN, 11f));
		head.setForeground(UIManager.getColor("Label.disabledForeground"));
		body.add(head, BorderLayout.NORTH);
		body.add(gradientKindCells(popup, btn), BorderLayout.CENTER);
		JPanel angle = new JPanel(new BorderLayout(0,4));
		JLabel head2 = new JLabel(PCARD.pc.intl.getToolText("Gradient Angle"));
		head2.setFont(head2.getFont().deriveFont(Font.PLAIN, 11f));
		head2.setForeground(UIManager.getColor("Label.disabledForeground"));
		angle.add(head2, BorderLayout.NORTH);
		angle.add(gradientAngleContent(btn), BorderLayout.CENTER);
		//テクスチャ(内蔵+読み込んだもの)と、読み込み/選択範囲から作るボタン
		JPanel textures = new JPanel(new BorderLayout(0,4));
		JLabel head3 = new JLabel(PCARD.pc.intl.getToolText("Texture"));
		head3.setFont(head3.getFont().deriveFont(Font.PLAIN, 11f));
		head3.setForeground(UIManager.getColor("Label.disabledForeground"));
		textures.add(head3, BorderLayout.NORTH);
		textures.add(textureCells(popup, btn), BorderLayout.CENTER);
		textures.add(textureButtons(popup, btn), BorderLayout.SOUTH);
		JPanel south = new JPanel(new BorderLayout(0,4));
		south.add(angle, BorderLayout.NORTH);
		south.add(textures, BorderLayout.SOUTH);
		body.add(south, BorderLayout.SOUTH);
		popup.add(body);
		return popup;
	}

	//テクスチャのマス目: 内蔵の8つ+読み込んだもの(あれば)。押すと塗りの種類も
	//テクスチャになる。
	static JPanel textureCells(JPopupMenu popup, GradButton btn){
		java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList(PaintTexture.BUILT_IN));
		if(PaintTexture.custom!=null) names.add(PaintTexture.CUSTOM);
		int columns = 4;
		JPanel grid = new JPanel(new GridLayout(0, columns, 2, 2));
		for(String name : names){
			java.awt.image.BufferedImage thumb = PaintTexture.thumbnail(PaintTexture.named(name), CELL-PAD*2);
			boolean selected = btn.kind()==3 && name.equalsIgnoreCase(PaintTexture.currentName);
			grid.add(new OptionCell(CELL, CELL, null, selected, g -> {
				PatButton.paintTiled(g, null, PAD, PAD, CELL-PAD*2, CELL-PAD*2);
				g.drawImage(thumb, PAD, PAD, null);
			}, () -> { PaintTexture.select(name); btn.setKind(3); }, popup){
				private static final long serialVersionUID = 1L;
				{ setToolTipText(PCARD.pc.intl.getToolText(name.equals(PaintTexture.CUSTOM) ? "Custom Texture" : name)); }
			});
		}
		return grid;
	}

	//「テクスチャを読み込む…」「選択範囲からテクスチャ」
	static JPanel textureButtons(JPopupMenu popup, GradButton btn){
		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		javax.swing.JButton load = new javax.swing.JButton(PCARD.pc.intl.getToolText("Import Texture…"));
		load.setFont(load.getFont().deriveFont(Font.PLAIN, 11f));
		load.setFocusable(false);
		load.addActionListener(e -> {
			popup.setVisible(false);
			importTexture(btn);
		});
		javax.swing.JButton pick = new javax.swing.JButton(PCARD.pc.intl.getToolText("Texture from Selection"));
		pick.setFont(pick.getFont().deriveFont(Font.PLAIN, 11f));
		pick.setFocusable(false);
		pick.setEnabled(PaintTool.floating()!=null);
		pick.addActionListener(e -> {
			popup.setVisible(false);
			FloatingSelection f = PaintTool.floating();
			if(f==null) return;
			if(PaintTexture.setCustom(f.cropped())) btn.setKind(3);
		});
		row.add(load);
		row.add(pick);
		return row;
	}

	//画像ファイルをテクスチャとして読み込む(PaintFile.read: ImageIOかPICT)
	static void importTexture(GradButton btn){
		javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getToolText("Import Texture…").replace("…",""));
		chooser.setDialogType(javax.swing.JFileChooser.OPEN_DIALOG);
		Component parent = PaintTool.owner!=null ? PaintTool.owner : btn;
		if(PCARD.showFileChooser(chooser, parent, false)!=javax.swing.JFileChooser.APPROVE_OPTION) return;
		java.awt.image.BufferedImage img = null;
		try{ img = PaintFile.read(chooser.getSelectedFile()); }catch(java.io.IOException e){ }
		if(img==null){
			javax.swing.JOptionPane.showMessageDialog(parent, PCARD.pc.intl.getDialogText("Not a picture file"));
			return;
		}
		if(PaintTexture.setCustom(img)) btn.setKind(3);
	}

	//塗りの種類: 切(今のパターンを前景色/背景色で敷き詰めた見本)・線形・放射状
	static JPanel gradientKindCells(JPopupMenu popup, GradButton btn){
		JPanel row = row(4);
		Color c1 = Color.BLACK, c2 = Color.WHITE;
		java.awt.image.BufferedImage pat = null;
		if(PaintTool.owner!=null){
			if(PaintTool.owner.fore!=null) c1 = PaintTool.owner.fore.color;
			if(PaintTool.owner.back!=null) c2 = PaintTool.owner.back.color;
			if(PaintTool.owner.pat!=null) pat = PaintTool.owner.pat.colorized(PaintTool.owner.pat.pattern);
		}
		final Color fore = c1, back = c2;
		final java.awt.image.BufferedImage swatch = pat;
		String[] names = {"Color / Pattern", "Linear", "Radial", "Texture"};
		for(int kind=0; kind<4; kind++){
			final int k = kind;
			row.add(new OptionCell(CELL, CELL, null, btn.kind()==k, g -> {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
				int inner = CELL-PAD*2;
				if(k==0){
					if(swatch!=null) PatButton.paintTiled(g, swatch, PAD, PAD, inner, inner);
					else { g.setColor(fore); g.fillRect(PAD, PAD, inner, inner); }
				}else if(k==3){
					//今のテクスチャ(未選択なら最初の内蔵テクスチャ)の見本
					java.awt.image.BufferedImage tex = PaintTexture.current!=null ? PaintTexture.current : PaintTexture.named(PaintTexture.BUILT_IN[0]);
					PatButton.paintTiled(g, null, PAD, PAD, inner, inner);
					g.drawImage(PaintTexture.thumbnail(tex, inner), PAD, PAD, null);
				}else{
					java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(inner, inner, java.awt.image.BufferedImage.TYPE_INT_ARGB);
					bi.getGraphics().fillRect(0, 0, inner, inner);
					PaintBucketTool.gradfill(bi, fore, back, btn.angle, k==2);
					PatButton.paintTiled(g, null, PAD, PAD, inner, inner);
					g.drawImage(bi, PAD, PAD, null);
				}
			}, () -> btn.setKind(k), popup){
				private static final long serialVersionUID = 1L;
				{ setToolTipText(PCARD.pc.intl.getToolText(names[k])); }
			});
		}
		return row;
	}

	//テスト用にダイヤル+プレビュー+数値だけ組み立てる
	static JPanel gradientAngleContent(GradButton btn){
		JPanel panel = new JPanel(new BorderLayout(8,0));
		GradientPreview preview = new GradientPreview(btn);
		JLabel val = new JLabel("", SwingConstants.CENTER);
		val.setName("value");
		AngleDial dial = new AngleDial(btn.angle, deg -> {
			btn.angle = Math.toRadians(deg);
			btn.makeIcon();
			preview.repaint();
			val.setText(deg+"°");
		});
		val.setText(dial.degrees()+"°");
		JPanel east = new JPanel(new BorderLayout(0,4));
		east.add(preview, BorderLayout.CENTER);
		east.add(val, BorderLayout.SOUTH);
		panel.add(dial, BorderLayout.CENTER);
		panel.add(east, BorderLayout.EAST);
		return panel;
	}

	//角度ダイヤル。つまみの向きはグラデーションの進む向き(PaintBucketTool.
	//gradfillのp=x*sin+y*cosの向き: 0°で上→下、90°で左→右)に合わせる。
	static class AngleDial extends JPanel {
		private static final long serialVersionUID = 1L;
		static final int SIZE = 96;
		int degrees;
		final Consumer<Integer> onChange;
		AngleDial(double radians, Consumer<Integer> onChange){
			this.onChange = onChange;
			this.degrees = normalize((int)Math.round(Math.toDegrees(radians)));
			setOpaque(true);
			setBackground(Color.WHITE);
			setPreferredSize(new Dimension(SIZE, SIZE));
			MouseAdapter m = new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){ track(e); }
				@Override public void mouseDragged(MouseEvent e){ track(e); }
			};
			addMouseListener(m);
			addMouseMotionListener(m);
		}
		static int normalize(int deg){
			return ((deg%360)+360)%360;
		}
		int degrees(){ return degrees; }
		//マウス位置から角度を決める。shiftで15°刻み。
		void track(MouseEvent e){
			double dx = e.getX()-getWidth()/2.0, dy = e.getY()-getHeight()/2.0;
			if(dx*dx+dy*dy<4) return;
			int deg = normalize((int)Math.round(Math.toDegrees(Math.atan2(dx, dy))));
			if(e.isShiftDown()) deg = normalize((int)Math.round(deg/15.0)*15);
			setDegrees(deg);
		}
		void setDegrees(int deg){
			deg = normalize(deg);
			if(deg==degrees) return;
			degrees = deg;
			repaint();
			onChange.accept(degrees);
		}
		@Override
		protected void paintComponent(Graphics g){
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D)g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			double cx = getWidth()/2.0, cy = getHeight()/2.0, r = Math.min(cx, cy)-8;
			g2.setColor(Color.LIGHT_GRAY);
			g2.setStroke(new BasicStroke(1));
			g2.draw(new java.awt.geom.Ellipse2D.Double(cx-r, cy-r, r*2, r*2));
			//30°ごとの目盛り(90°ごとは長め)
			for(int a=0; a<360; a+=30){
				double s = Math.sin(Math.toRadians(a)), c = Math.cos(Math.toRadians(a));
				double len = (a%90==0) ? 6 : 3;
				g2.draw(new java.awt.geom.Line2D.Double(cx+s*(r-len), cy+c*(r-len), cx+s*r, cy+c*r));
			}
			//針とつまみ
			double s = Math.sin(Math.toRadians(degrees)), c = Math.cos(Math.toRadians(degrees));
			g2.setColor(new Color(0x3478F6));
			g2.setStroke(new BasicStroke(2));
			g2.draw(new java.awt.geom.Line2D.Double(cx, cy, cx+s*r, cy+c*r));
			g2.fill(new java.awt.geom.Ellipse2D.Double(cx+s*r-5, cy+c*r-5, 10, 10));
			g2.setColor(Color.GRAY);
			g2.fill(new java.awt.geom.Ellipse2D.Double(cx-2, cy-2, 4, 4));
			g2.dispose();
		}
	}

	//前景色→背景色を今の角度で塗ったプレビュー
	static class GradientPreview extends JPanel {
		private static final long serialVersionUID = 1L;
		final GradButton btn;
		GradientPreview(GradButton btn){
			this.btn = btn;
			setPreferredSize(new Dimension(CELL, CELL));
		}
		@Override
		protected void paintComponent(Graphics g){
			super.paintComponent(g);
			Color c1 = Color.BLACK, c2 = Color.WHITE;
			if(PaintTool.owner!=null && PaintTool.owner.fore!=null && PaintTool.owner.back!=null){
				c1 = PaintTool.owner.fore.color;
				c2 = PaintTool.owner.back.color;
			}
			java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
			bi.getGraphics().fillRect(0, 0, getWidth(), getHeight());
			PaintBucketTool.gradfill(bi, c1, c2, btn.angle, btn.radial);
			g.drawImage(bi, 0, 0, null);
			g.setColor(Color.LIGHT_GRAY);
			g.drawRect(0, 0, getWidth()-1, getHeight()-1);
		}
	}

	//連続値のスライダー。動かした瞬間にonChangeを呼び、右の数字も更新する。
	static JPanel slider(int min, int max, int value, String unit, Consumer<Integer> onChange){
		JPanel panel = new JPanel(new BorderLayout(6,0));
		JSlider slider = new JSlider(SwingConstants.HORIZONTAL, min, max, Math.max(min, Math.min(max, value)));
		slider.setName("slider");
		slider.setPreferredSize(new Dimension(CELL*4, slider.getPreferredSize().height));
		slider.setMajorTickSpacing((max-min)/5);
		slider.setPaintTicks(true);
		slider.setFocusable(false);
		JLabel val = new JLabel(slider.getValue()+unit, SwingConstants.RIGHT);
		val.setName("value");
		val.setPreferredSize(new Dimension(36, val.getPreferredSize().height));
		slider.addChangeListener(e -> {
			val.setText(slider.getValue()+unit);
			onChange.accept(slider.getValue());
		});
		panel.add(slider, BorderLayout.CENTER);
		panel.add(val, BorderLayout.EAST);
		return panel;
	}
}

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.event.ChangeListener;


//カラーパレットのOklabタブ。JColorChooser標準のサンプル/HSV/HSL/CMYKタブの代わりに
//使う(installPanels参照。残すのはこのタブとRGBとMacシステムカラー)。
//円は今の明度LでのOKLCHの色相環で、角度が色相h(右がa+、上がb+)、半径が彩度。
//半径はその明度・色相でsRGBに収まる最大の彩度を1とした割合なので、円のどこを
//押しても表示できる色になる(表示できない部分で欠けた形にならない)。右の縦の
//バーで明度を変える(円の上でのスクロールでも変わる)。数値欄はL・C・h(OKLCH)と
//a・b(Oklab)の実際の値と16進のsRGB。
class OklabColorChooserPanel extends AbstractColorChooserPanel {
	private static final long serialVersionUID = 1L;
	private static final int WHEEL=184; //円の直径
	private static final int PAD=9;     //円・バーの周り(影とつまみがはみ出す分)
	private static final int BAR=14;    //明度バーの幅

	//今の色。8bitに丸めた色からは戻さず、この値を持ち続ける(ドラッグや数値の
	//入力で少しずつ動かしたときに丸めで値が飛ばないように)。
	private double lightness=0.6, relChroma=0.5, hue=30;
	private int alpha=255;
	//このタブが選択色にした色。updateChooserでこれと違えば、他のタブなどで
	//変わったので今の色を選択色から計算し直す。
	private Color lastSet;
	private boolean syncing;

	private Wheel wheel;
	private LightnessBar bar;
	private JSpinner lField, cField, hField, aField, bField;
	private JTextField hexField;

	//---------------------------------------------------------------- 組み込み

	//chooserの中身をOklab・RGB・Macシステムカラーの切り替え(CompactColorChooserPanel)
	//1枚にする。パネルが1枚ならJColorChooserはタブ(と周りの枠の余白)を出さない。
	//LAFが変わるとJColorChooserは標準のタブに戻すので、createChooserのものは
	//updateUIのたびにこれを呼び直す。
	static void installPanels(JColorChooser chooser){
		chooser.setChooserPanels(new AbstractColorChooserPanel[]{new CompactColorChooserPanel()});
	}

	//Oklab・RGB・Macシステムカラーのタブで、標準のプレビュー(「プレビュー」枠の
	//見本の文字)は出さないchooser。新旧の色はColorCompareSwatchで見せる。
	static JColorChooser createChooser(Color initial){
		JColorChooser chooser = new JColorChooser(initial==null ? Color.WHITE : initial){
			private static final long serialVersionUID = 1L;
			@Override public void updateUI(){
				super.updateUI();
				installPanels(this);
			}
		};
		chooser.setPreviewPanel(new JPanel());
		return chooser;
	}

	//JColorChooser.showDialogの代わり(同じくキャンセルではnull)。
	static Color showDialog(Component parent, String title, Color initial){
		final JColorChooser chooser = createChooser(initial);
		ColorCompareSwatch compare = new ColorCompareSwatch(chooser);
		compare.setOriginal(chooser.getColor());
		chooser.setPreviewPanel(compare);
		final Color[] result = new Color[1];
		//own OK/Cancel buttons (HyperZebra's texts, as GColorDialog): JColorChooser.createDialog's are the JDK's
		//"OK / 取消 / リセット(R)" (a 68K GetColor, XTernal Collection's PickColor)
		java.awt.Window owner = parent==null ? null : parent instanceof java.awt.Window ? (java.awt.Window)parent :
			javax.swing.SwingUtilities.getWindowAncestor(parent);
		final JDialog dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
		buttons.setBorder(BorderFactory.createEmptyBorder(6, 8, 7, 8));
		javax.swing.JButton cancel = ColorPaint.small(new javax.swing.JButton(International.t("Cancel")));
		cancel.addActionListener(e -> dialog.dispose());
		javax.swing.JButton ok = ColorPaint.small(new javax.swing.JButton("OK"));
		ok.addActionListener(e -> { result[0] = chooser.getColor(); dialog.dispose(); });
		buttons.add(cancel);
		buttons.add(ok);
		dialog.getContentPane().add(chooser, BorderLayout.CENTER);
		dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
			javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
		dialog.dispose();
		return result[0];
	}

	@Override public String getDisplayName(){ return "Oklab"; }
	@Override public Icon getSmallDisplayIcon(){ return null; }
	@Override public Icon getLargeDisplayIcon(){ return null; }

	@Override
	protected void buildChooser(){
		setLayout(new BorderLayout());
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		wheel = new Wheel();
		bar = new LightnessBar();
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridy = 0;
		gc.anchor = GridBagConstraints.CENTER;
		row.add(wheel, gc);
		gc.insets = new Insets(0, 0, 0, 6);
		row.add(bar, gc);
		gc.insets = new Insets(0, 0, 0, 0);
		row.add(buildFields(), gc);
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
		add(row, BorderLayout.CENTER);
	}

	private JPanel buildFields(){
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		lField = spinner(0, 100, 0.5, "0.0");
		cField = spinner(0, 0.4, 0.005, "0.000");
		hField = spinner(0, 360, 1, "0.0");
		aField = spinner(-0.4, 0.4, 0.005, "0.000");
		bField = spinner(-0.4, 0.4, 0.005, "0.000");
		hexField = new JTextField(6);
		ColorPaint.small(hexField);
		hexField.setHorizontalAlignment(SwingConstants.RIGHT);
		hexField.addActionListener(e -> hexEdited());
		hexField.addFocusListener(new java.awt.event.FocusAdapter(){
			@Override public void focusLost(java.awt.event.FocusEvent e){ hexEdited(); }
		});

		int y = 0;
		y = header(p, y, "OKLCH");
		y = fieldRow(p, y, "L", International.t("Lightness"), lField, "%");
		y = fieldRow(p, y, "C", International.t("Chroma"), cField, "");
		y = fieldRow(p, y, "h", International.t("Hue"), hField, "°");
		y = header(p, y, "Oklab");
		y = fieldRow(p, y, "a", International.t("Green–red axis"), aField, "");
		y = fieldRow(p, y, "b", International.t("Blue–yellow axis"), bField, "");
		y = header(p, y, "sRGB");
		y = fieldRow(p, y, "#", null, hexField, "");

		ChangeListener lch = e -> lchEdited();
		lField.addChangeListener(lch);
		cField.addChangeListener(lch);
		hField.addChangeListener(lch);
		ChangeListener ab = e -> abEdited();
		aField.addChangeListener(ab);
		bField.addChangeListener(ab);
		return p;
	}

	private static JSpinner spinner(double min, double max, double step, String format){
		JSpinner s = new JSpinner(new SpinnerNumberModel(min, min, max, step));
		s.setEditor(new JSpinner.NumberEditor(s, format));
		JSpinner.DefaultEditor ed = (JSpinner.DefaultEditor)s.getEditor();
		ed.getTextField().setColumns(4);
		ColorPaint.small(s);
		return s;
	}

	static int header(JPanel p, int y, String text){
		JLabel l = ColorPaint.header(text);
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0; gc.gridy = y; gc.gridwidth = 3;
		gc.anchor = GridBagConstraints.WEST;
		gc.insets = new Insets(y==0 ? 0 : 5, 0, 1, 0);
		p.add(l, gc);
		return y+1;
	}

	static int fieldRow(JPanel p, int y, String label, String tip, JComponent field, String unit){
		JLabel l = ColorPaint.small(new JLabel(label));
		l.setToolTipText(tip);
		field.setToolTipText(tip);
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridy = y;
		gc.gridx = 0; gc.anchor = GridBagConstraints.EAST; gc.insets = new Insets(0, 0, 0, 4);
		p.add(l, gc);
		gc.gridx = 1; gc.anchor = GridBagConstraints.WEST; gc.fill = GridBagConstraints.HORIZONTAL; gc.insets = new Insets(0, 0, 0, 0);
		p.add(field, gc);
		JLabel u = ColorPaint.small(new JLabel(unit));
		u.setForeground(ColorPaint.secondaryText());
		u.setPreferredSize(new Dimension(11, u.getPreferredSize().height));
		gc.gridx = 2; gc.fill = GridBagConstraints.NONE; gc.insets = new Insets(0, 2, 0, 0);
		p.add(u, gc);
		return y+1;
	}

	//---------------------------------------------------------------- 色の受け渡し

	@Override
	public void updateChooser(){
		Color c = getColorFromModel();
		if(c==null) return;
		if(!c.equals(lastSet)){
			lastSet = c;
			alpha = c.getAlpha();
			double[] lab = Oklab.fromSrgb(c.getRed()/255.0, c.getGreen()/255.0, c.getBlue()/255.0);
			double ch = Math.hypot(lab[1], lab[2]);
			lightness = clamp01(lab[0]);
			//灰色(彩度がほぼ0)では色相が決まらないので、前の色相のままにする
			if(ch>1e-4) hue = Oklab.hueDegrees(lab[1], lab[2]);
			double max = Oklab.maxChroma(lightness, hue);
			relChroma = max<=1e-6 ? relChroma : clamp01(ch/max);
		}
		refresh();
	}

	//今の色(lightness/relChroma/hue)を選択色にする。
	private void apply(){
		Color c = currentColor();
		lastSet = c;
		getColorSelectionModel().setSelectedColor(c);
		refresh();
	}

	private double chroma(){
		return relChroma*Oklab.maxChroma(lightness, hue);
	}

	private Color currentColor(){
		int rgb = Oklab.toRgb(lightness, chroma(), hue);
		return new Color((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF, alpha);
	}

	private void refresh(){
		if(wheel==null) return;
		wheel.repaint();
		bar.repaint();
		syncing = true;
		try{
			double c = chroma(), rad = Math.toRadians(hue);
			setSpinner(lField, lightness*100);
			setSpinner(cField, c);
			setSpinner(hField, hue);
			setSpinner(aField, c*Math.cos(rad));
			setSpinner(bField, c*Math.sin(rad));
			Color col = currentColor();
			if(!hexField.isFocusOwner()){
				hexField.setText(String.format("%02X%02X%02X", col.getRed(), col.getGreen(), col.getBlue()));
			}
		} finally {
			syncing = false;
		}
	}

	private static void setSpinner(JSpinner s, double v){
		SpinnerNumberModel m = (SpinnerNumberModel)s.getModel();
		double min = ((Number)m.getMinimum()).doubleValue(), max = ((Number)m.getMaximum()).doubleValue();
		s.setValue(Math.max(min, Math.min(max, v)));
	}

	private static double value(JSpinner s){
		return ((Number)s.getValue()).doubleValue();
	}

	private void lchEdited(){
		if(syncing) return;
		lightness = clamp01(value(lField)/100);
		hue = ((value(hField)%360)+360)%360;
		setChroma(value(cField));
		apply();
	}

	private void abEdited(){
		if(syncing) return;
		double a = value(aField), b = value(bField), c = Math.hypot(a, b);
		if(c>1e-4) hue = Oklab.hueDegrees(a, b);
		setChroma(c);
		apply();
	}

	//実際の彩度cを、sRGBに収まる最大の彩度に対する割合にする(収まらない分は削る)。
	private void setChroma(double c){
		double max = Oklab.maxChroma(lightness, hue);
		if(max>1e-6) relChroma = clamp01(c/max);
	}

	private void hexEdited(){
		if(syncing) return;
		String s = hexField.getText().trim();
		if(s.startsWith("#")) s = s.substring(1);
		if(s.length()==3) s = ""+s.charAt(0)+s.charAt(0)+s.charAt(1)+s.charAt(1)+s.charAt(2)+s.charAt(2);
		int rgb;
		try{
			if(s.length()!=6) throw new NumberFormatException();
			rgb = Integer.parseInt(s, 16);
		} catch(NumberFormatException e){
			refresh(); //読めなければ今の色に戻す
			return;
		}
		Color c = new Color((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF, alpha);
		if(!c.equals(lastSet)) getColorSelectionModel().setSelectedColor(c);
	}

	private void nudgeLightness(double delta){
		double l = clamp01(lightness+delta);
		if(l==lightness) return;
		lightness = l;
		apply();
	}

	private static double clamp01(double v){
		return v<0 ? 0 : v>1 ? 1 : v;
	}

	//---------------------------------------------------------------- 色相環

	private final class Wheel extends JComponent {
		private static final long serialVersionUID = 1L;
		private BufferedImage image;
		private double imageL = -1, imageScale;
		private boolean dragging;

		Wheel(){
			setPreferredSize(new Dimension(WHEEL+2*PAD, WHEEL+2*PAD));
			setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
			MouseAdapter m = new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){
					//円の外(影のあたりまでは含める)を押したときは何もしない
					dragging = distance(e.getPoint()) <= radius()+PAD/2.0;
					if(dragging) pick(e.getPoint());
				}
				@Override public void mouseDragged(MouseEvent e){
					if(dragging) pick(e.getPoint());
				}
				@Override public void mouseReleased(MouseEvent e){
					dragging = false;
				}
				@Override public void mouseWheelMoved(MouseWheelEvent e){
					nudgeLightness(-e.getPreciseWheelRotation()*0.01);
				}
			};
			addMouseListener(m);
			addMouseMotionListener(m);
			addMouseWheelListener(m);
		}

		private double cx(){ return getWidth()/2.0; }
		private double cy(){ return getHeight()/2.0; }
		private int diameter(){ return Math.max(8, Math.min(getWidth(), getHeight())-2*PAD); }
		private double radius(){ return diameter()/2.0; }
		private double distance(Point p){ return Math.hypot(p.x-cx(), p.y-cy()); }

		private void pick(Point p){
			double dx = p.x-cx(), dy = cy()-p.y, d = Math.hypot(dx, dy);
			relChroma = clamp01(d/radius());
			if(d>=0.5) hue = Oklab.hueDegrees(dx, dy);
			apply();
		}

		@Override
		protected void paintComponent(Graphics g){
			Graphics2D g2 = ColorPaint.smooth(g);
			int d = diameter();
			int x0 = (getWidth()-d)/2, y0 = (getHeight()-d)/2;
			ColorPaint.softShadow(g2, k -> new Ellipse2D.Double(x0-k, y0-k, d+2*k, d+2*k));

			double scale = ColorPaint.deviceScale(g2);
			int px = (int)Math.round(d*scale);
			if(image==null || image.getWidth()!=px || imageL!=lightness || imageScale!=scale){
				image = renderWheel(px, lightness);
				imageL = lightness;
				imageScale = scale;
			}
			g2.drawImage(image, x0, y0, d, d, null);

			//縁: 外側に細い暗い線、内側に細い明るい線(どの明度でも縁が見えるように)
			g2.setStroke(new BasicStroke(1f));
			g2.setColor(new Color(0, 0, 0, 50));
			g2.draw(new Ellipse2D.Double(x0-0.5, y0-0.5, d+1, d+1));
			g2.setColor(new Color(255, 255, 255, 60));
			g2.draw(new Ellipse2D.Double(x0+0.5, y0+0.5, d-1, d-1));

			double r = d/2.0, rad = Math.toRadians(hue);
			double mx = x0+r+Math.cos(rad)*relChroma*r, my = y0+r-Math.sin(rad)*relChroma*r;
			ColorPaint.drawKnob(g2, mx, my, 7.5, currentColor());
			g2.dispose();
		}
	}

	//明度Lでの色相環の絵(px×px、円の外は透明、縁は1ピクセルでなめらかに)。
	static BufferedImage renderWheel(int px, double l){
		int bins = 720;
		double[] max = new double[bins+1];
		for(int i=0; i<=bins; i++) max[i] = Oklab.maxChroma(l, i*360.0/bins);
		BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
		int[] row = new int[px];
		double r = px/2.0;
		double[] lin = new double[3];
		for(int y=0; y<px; y++){
			double dy = r-(y+0.5);
			for(int x=0; x<px; x++){
				double dx = (x+0.5)-r, d = Math.hypot(dx, dy);
				double cover = r-d+0.5;
				if(cover<=0){ row[x] = 0; continue; }
				double h = Oklab.hueDegrees(dx, dy);
				double f = h/360.0*bins;
				int i = (int)f;
				if(i>=bins) i = bins-1;
				double mc = max[i]+(max[i+1]-max[i])*(f-i);
				double c = Math.min(d/r, 1)*mc;
				double rad = Math.toRadians(h);
				Oklab.toLinear(l, c*Math.cos(rad), c*Math.sin(rad), lin);
				int a = cover>=1 ? 255 : (int)Math.round(cover*255);
				row[x] = (a<<24)|(Oklab.encode8(lin[0])<<16)|(Oklab.encode8(lin[1])<<8)|Oklab.encode8(lin[2]);
			}
			img.setRGB(0, y, px, 1, row, 0, px);
		}
		return img;
	}

	//---------------------------------------------------------------- 明度バー

	private final class LightnessBar extends JComponent {
		private static final long serialVersionUID = 1L;
		private BufferedImage image;
		private double imageHue = -1, imageRel = -1, imageScale;

		LightnessBar(){
			setPreferredSize(new Dimension(BAR+2*PAD, WHEEL+2*PAD));
			setToolTipText(International.t("Lightness"));
			MouseAdapter m = new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){ pick(e.getY()); }
				@Override public void mouseDragged(MouseEvent e){ pick(e.getY()); }
				@Override public void mouseWheelMoved(MouseWheelEvent e){
					nudgeLightness(-e.getPreciseWheelRotation()*0.01);
				}
			};
			addMouseListener(m);
			addMouseMotionListener(m);
			addMouseWheelListener(m);
		}

		private int trackHeight(){ return Math.max(8, getHeight()-2*PAD); }

		private void pick(int y){
			double l = clamp01(1-(y-PAD)/(double)trackHeight());
			if(l==lightness) return;
			lightness = l;
			apply();
		}

		@Override
		protected void paintComponent(Graphics g){
			Graphics2D g2 = ColorPaint.smooth(g);
			int h = trackHeight();
			int x0 = (getWidth()-BAR)/2, y0 = PAD;
			ColorPaint.softShadow(g2, k -> new RoundRectangle2D.Double(x0-k, y0-k, BAR+2*k, h+2*k, BAR+2*k, BAR+2*k));
			RoundRectangle2D track = new RoundRectangle2D.Double(x0, y0, BAR, h, BAR, BAR);

			double scale = ColorPaint.deviceScale(g2);
			int ph = (int)Math.round(h*scale);
			if(image==null || image.getHeight()!=ph || imageHue!=hue || imageRel!=relChroma || imageScale!=scale){
				image = renderBar(Math.max(1, (int)Math.round(BAR*scale)), ph, hue, relChroma);
				imageHue = hue;
				imageRel = relChroma;
				imageScale = scale;
			}
			Shape oldClip = g2.getClip();
			g2.clip(track);
			g2.drawImage(image, x0, y0, BAR, h, null);
			g2.setClip(oldClip);

			ColorPaint.rim(g2, x0, y0, BAR, h);

			ColorPaint.drawKnob(g2, x0+BAR/2.0, y0+(1-lightness)*h, BAR/2.0+1.5, currentColor());
			g2.dispose();
		}
	}

	//上が明度1、下が明度0の、色相hueで最大彩度に対する割合relの帯。
	static BufferedImage renderBar(int w, int h, double hue, double rel){
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[w];
		double rad = Math.toRadians(hue);
		for(int y=0; y<h; y++){
			double l = 1-(y+0.5)/h;
			java.util.Arrays.fill(row, Oklab.toRgb(l, rel*Oklab.maxChroma(l, hue), hue));
			img.setRGB(0, y, w, 1, row, 0, w);
		}
		return img;
	}

	//---------------------------------------------------------------- Oklabの計算

	//Björn OttossonのOklab(https://bottosson.github.io/posts/oklab/)とsRGBの変換。
	static final class Oklab {
		private Oklab(){}

		//線形sRGB(0..1)からOklabのL,a,b。
		static double[] fromLinear(double r, double g, double b){
			double l = Math.cbrt(0.4122214708*r+0.5363325363*g+0.0514459929*b);
			double m = Math.cbrt(0.2119034982*r+0.6806995451*g+0.1073969566*b);
			double s = Math.cbrt(0.0883024619*r+0.2817188376*g+0.6299787005*b);
			return new double[]{
				0.2104542553*l+0.7936177850*m-0.0040720468*s,
				1.9779984951*l-2.4285922050*m+0.4505937099*s,
				0.0259040371*l+0.7827717662*m-0.8086757660*s};
		}

		//sRGB(ガンマ付き0..1)からOklab。
		static double[] fromSrgb(double r, double g, double b){
			return fromLinear(decode(r), decode(g), decode(b));
		}

		//OklabのL,a,bから線形sRGB(範囲外もそのまま)をoutに入れる。
		static void toLinear(double lightness, double a, double b, double[] out){
			double l = lightness+0.3963377774*a+0.2158037573*b;
			double m = lightness-0.1055613458*a-0.0638541728*b;
			double s = lightness-0.0894841775*a-1.2914855480*b;
			l = l*l*l; m = m*m*m; s = s*s*s;
			out[0] = +4.0767416621*l-3.3077115913*m+0.2309699292*s;
			out[1] = -1.2684380046*l+2.6097574011*m-0.3413193965*s;
			out[2] = -0.0041960863*l-0.7034186147*m+1.7076147010*s;
		}

		//OKLCHから0xRRGGBB(sRGBの外は各成分を0..1に切り詰める)。
		static int toRgb(double lightness, double chroma, double hueDeg){
			double rad = Math.toRadians(hueDeg);
			double[] lin = new double[3];
			toLinear(lightness, chroma*Math.cos(rad), chroma*Math.sin(rad), lin);
			return (encodeExact(lin[0])<<16)|(encodeExact(lin[1])<<8)|encodeExact(lin[2]);
		}

		static double hueDegrees(double a, double b){
			double h = Math.toDegrees(Math.atan2(b, a));
			return h<0 ? h+360 : h;
		}

		//明度lightness・色相hueDegでsRGBに収まる最大の彩度(二分法)。
		static double maxChroma(double lightness, double hueDeg){
			if(lightness<=0 || lightness>=1) return 0;
			double rad = Math.toRadians(hueDeg), ca = Math.cos(rad), sb = Math.sin(rad);
			double lo = 0, hi = 0.5;
			double[] lin = new double[3];
			for(int i=0; i<24; i++){
				double mid = (lo+hi)/2;
				toLinear(lightness, mid*ca, mid*sb, lin);
				if(inGamut(lin)) lo = mid; else hi = mid;
			}
			return lo;
		}

		private static boolean inGamut(double[] lin){
			final double e = 1e-5;
			return lin[0]>=-e && lin[0]<=1+e && lin[1]>=-e && lin[1]<=1+e && lin[2]>=-e && lin[2]<=1+e;
		}

		static double decode(double c){
			return c<=0.04045 ? c/12.92 : Math.pow((c+0.055)/1.055, 2.4);
		}

		static int encodeExact(double x){
			x = x<0 ? 0 : x>1 ? 1 : x;
			double c = x<=0.0031308 ? 12.92*x : 1.055*Math.pow(x, 1/2.4)-0.055;
			return (int)Math.round(c*255);
		}

		//色相環の絵用(ピクセルごとにpowを呼ばないよう表を引く)。
		private static final int LUT = 4096;
		private static final int[] ENCODE = new int[LUT+1];
		static {
			for(int i=0; i<=LUT; i++) ENCODE[i] = encodeExact(i/(double)LUT);
		}
		static int encode8(double x){
			if(x<=0) return 0;
			if(x>=1) return 255;
			return ENCODE[(int)(x*LUT+0.5)];
		}
	}
}


//新しい色と元の色を左右に並べた見本(角の丸い横長の枠)。右の元の色を押すと
//その色に戻す。chooserの選択色が変わるたびに描き直す。
class ColorCompareSwatch extends JComponent {
	private static final long serialVersionUID = 1L;
	private final JColorChooser chooser;
	private Color original;
	private final ChangeListener repaint = e -> repaint();

	ColorCompareSwatch(JColorChooser chooser){
		this.chooser = chooser;
		setPreferredSize(new Dimension(76, 26));
		setToolTipText("");
		addMouseListener(new MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){
				if(original!=null && e.getX()>=getWidth()/2) ColorCompareSwatch.this.chooser.setColor(original);
			}
		});
	}

	void setOriginal(Color c){
		original = c;
		repaint();
	}

	@Override public String getToolTipText(MouseEvent e){
		return e.getX()<getWidth()/2 ? International.t("New color") : International.t("Original color (click to restore)");
	}

	@Override public void addNotify(){
		super.addNotify();
		chooser.getSelectionModel().addChangeListener(repaint);
	}

	@Override public void removeNotify(){
		chooser.getSelectionModel().removeChangeListener(repaint);
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics g){
		Graphics2D g2 = (Graphics2D)g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		int w = getWidth()-2, h = getHeight()-3, x = 1, y = 1;
		RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, 10, 10);
		g2.setColor(new Color(0, 0, 0, 25));
		g2.fill(new RoundRectangle2D.Double(x, y+1, w, h, 10, 10));
		Shape oldClip = g2.getClip();
		g2.clip(shape);
		//半透明の色が分かるよう市松模様を下に敷く
		g2.setColor(Color.WHITE);
		g2.fillRect(x, y, w, h);
		g2.setColor(new Color(0xD6D6D6));
		for(int cy=y, r=0; cy<y+h; cy+=6, r++){
			for(int cx=x+(r%2)*6; cx<x+w; cx+=12) g2.fillRect(cx, cy, 6, 6);
		}
		Color now = chooser.getColor();
		if(now!=null){ g2.setColor(now); g2.fillRect(x, y, w/2, h); }
		if(original!=null){ g2.setColor(original); g2.fillRect(x+w/2, y, w-w/2, h); }
		g2.setClip(oldClip);
		g2.setStroke(new BasicStroke(1f));
		g2.setColor(new Color(0, 0, 0, 60));
		g2.draw(new RoundRectangle2D.Double(x-0.5, y-0.5, w+1, h+1, 11, 11));
		g2.setColor(new Color(255, 255, 255, 70));
		g2.draw(new RoundRectangle2D.Double(x+0.5, y+0.5, w-1, h-1, 9, 9));
		g2.dispose();
	}
}
